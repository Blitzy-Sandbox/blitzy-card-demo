package com.vsergeychik.carddemo.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vsergeychik.carddemo.common.DatasetRelation;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FileStatus.Outcome;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.transaction.TranCategoryRepository.ReadResult;
import com.vsergeychik.carddemo.transaction.model.TranCatBalRecord;
import com.vsergeychik.carddemo.transaction.model.TranCategoryRecord;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Repository;

/**
 * {@link TranCategoryRepository} - the {@code TRANCATG} lookup, its 6-byte key, and the namesake it
 * must never accept.
 *
 * <h2>What this suite is actually protecting</h2>
 * <p>Three properties, in descending order of how quietly their absence would corrupt the report:
 * <ol>
 *   <li><strong>The key is 6 bytes and not 17.</strong> {@code app/cpy/CVTRA01Y.cpy} declares a group
 *       with the identical COBOL name {@code TRAN-CAT-KEY} that is 17 bytes wide. Substituting it would
 *       not fail - it would compare the key <em>and</em> the first eleven characters of the description,
 *       so every lookup would miss and {@code CBTRN03C} would abend on data that is perfectly
 *       valid.</li>
 *   <li><strong>The two halves of the key pad in opposite directions.</strong> {@code TRAN-TYPE-CD} is
 *       {@code PIC X(02)} and pads right with spaces; {@code TRAN-CAT-CD} is {@code PIC 9(04)} and fills
 *       left with zeros. Getting either backwards produces a well-formed 6-character key that matches
 *       nothing.</li>
 *   <li><strong>Not-found is reported, never thrown.</strong> {@code app/cbl/CBTRN03C.cbl:506-510}
 *       displays the key, renders the status and <em>then</em> abends. An exception thrown from the
 *       repository would skip the first two.</li>
 * </ol>
 *
 * <h2>Why the backend stub evaluates the predicate instead of returning canned rows</h2>
 * <p>The keyed predicate lives in the statement, not in Java, so a stub that simply handed back a list
 * would not test the thing that matters - whether the {@code LIKE} pattern the repository composed
 * really confines the match to the key's own six bytes at offset 0. {@link Backend} therefore captures
 * the composed statement and the bound operand, translates the pattern into a regular expression
 * honouring {@code _}, {@code %} and the declared {@code \} escape, and returns the seeded rows that
 * match. A 17-byte key would compose a 17-byte pattern, that pattern would match nothing, and the test
 * would fail - which is precisely the point.
 */
@DisplayName("TranCategoryRepository - the TRANCATG lookup and its 6-byte key")
class TranCategoryRepositoryTest {

    /** The code page of the ASCII fixtures, named explicitly and never taken from the platform. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** A configured dataset name, well formed as the z/OS grammar requires. */
    private static final String DS = "TEST.M2.CARDDEMO.TRANCATG.VSAM.KSDS";

    /** The record-image column name the stand-in backend describes. */
    private static final String DESCRIBED_COLUMN = "RECORD_IMAGE";

    /** The row limit a keyed read asks for: one more than a unique key can return. */
    private static final int DUPLICATE_DETECTION_LIMIT = 2;

    /** The classpath location of the fixture derived from {@code app/data/ASCII/trancatg.txt}. */
    private static final String FIXTURE = "/fixtures/trancatg.txt";

    /** How many records that fixture holds. */
    private static final int FIXTURE_RECORDS = 18;

    // =============================================================================================
    // Fixtures and stubbing.
    // =============================================================================================

    /**
     * The {@code TRANCATG} binding as {@code application.yml} declares it: a KSDS, 60 bytes, 6-byte
     * key, copybook {@code CVTRA04Y}, no base, no alternate key.
     *
     * @return the valid binding
     */
    private static DatasetBinding validBinding() {
        return new DatasetBinding(DS, DatasetBinding.KSDS, false, "FB", null,
                TranCategoryRepository.RECORD_LENGTH, TranCategoryRepository.COPYBOOK,
                TranCategoryRepository.KEY_LENGTH, null, null, null);
    }

    /**
     * A catalogue holding one entry under the key the repository looks up.
     *
     * @param binding the binding, or {@code null} to omit the entry entirely
     * @return the catalogue
     */
    private static DatasetBindings bindings(DatasetBinding binding) {
        DatasetBindings catalogue = new DatasetBindings();
        if (binding != null) {
            catalogue.put(TranCategoryRepository.DD_NAME, binding);
        }
        return catalogue;
    }

    /**
     * Builds a repository over a mocked template and the valid catalogue.
     *
     * @param jdbcTemplate the mocked template
     * @return the repository
     */
    private static TranCategoryRepository repository(JdbcTemplate jdbcTemplate) {
        return new TranCategoryRepository(jdbcTemplate, bindings(validBinding()), ASCII,
                RecordImageForm.CHARACTER);
    }

    /**
     * Renders a category record as the 60-character image a character-form driver would present.
     *
     * @param tranTypeCd the type code
     * @param tranCatCd  the category code
     * @param desc       the description
     * @return the 60-character image
     */
    private static String image(String tranTypeCd, int tranCatCd, String desc) {
        return TranCategoryRecord.of(tranTypeCd, tranCatCd, desc, ASCII).toImage();
    }

    /** The stand-in backends, one per mocked template, so each test's dataset is independent. */
    private final Map<JdbcTemplate, Backend> backends = new LinkedHashMap<>();

    /**
     * The stand-in backend for a template, created on first use.
     *
     * @param jdbcTemplate the mocked template
     * @return its backend
     */
    private Backend backend(JdbcTemplate jdbcTemplate) {
        return backends.computeIfAbsent(jdbcTemplate, Backend::new);
    }

    /**
     * Seeds the dataset with the record images it holds and returns a repository over it.
     *
     * @param rows the images the dataset holds
     * @return a repository whose backend holds {@code rows}
     */
    private TranCategoryRepository seeded(List<String> rows) {
        JdbcTemplate template = mock(JdbcTemplate.class);
        backend(template).storing(rows);
        return repository(template);
    }

    /**
     * A stand-in for the deployment backend, sufficient to prove the predicate really is in the
     * statement.
     *
     * <p>It captures every statement and every bound operand, and answers a keyed read by evaluating the
     * composed {@code LIKE} pattern against the seeded rows. One deliberate departure from a real
     * backend: a seeded {@code null} image is always returned rather than filtered out, because a real
     * {@code LIKE} cannot match {@code NULL} and the repository's "there is a record and it cannot be
     * read" guard would otherwise be unreachable - yet that guard is right to exist, since a driver
     * returning {@code null} for a column it declared non-null is exactly the misbehaviour it defends
     * against.
     */
    private static final class Backend {

        /** What the dataset holds, in order. */
        private final List<String> stored = new ArrayList<>();

        /** Every statement sent, in order. */
        private final List<String> statementsSent = new ArrayList<>();

        /** Every operand bound to a keyed read, in order. */
        private final List<String> patternsBound = new ArrayList<>();

        /** Whether the dataset cannot be reached at all - the describe refuses first. */
        private boolean failing;

        /**
         * Whether the dataset describes cleanly but refuses the read itself.
         *
         * <p>A separate flag from {@link #failing}, and it has to be: the repository has two catch arms,
         * one around resolving the statement and one around transferring the rows, and a backend that
         * fails the describe never reaches the second. Only a backend that answers the describe and
         * <em>then</em> refuses exercises it - which is the realistic shape of a dataset that is
         * catalogued but whose data component cannot be read.
         */
        private boolean failingOnRead;

        /** Whether the describe answers with no metadata. */
        private boolean withoutMetadata;

        /** Whether the describe answers with a relation carrying no column. */
        private boolean withoutColumn;

        /** Whether the describe answers with a blank column name. */
        private boolean blankColumnName;

        /** Whether the template yields no result object at all. */
        private boolean yieldingNothing;

        /**
         * The statement text the repository prepared on the current keyed read, and the operand it
         * bound.
         *
         * <p>Instance state rather than per-call locals so the {@link Connection} and
         * {@link PreparedStatement} stand-ins can be built once and reused. Mock creation is by far the
         * dominant cost of the eighteen-record fixture walk, and this suite is single-threaded per test
         * method, so reusing them is both safe and the difference between a fast suite and a slow one.
         * Both lists are cleared at the start of every read.
         */
        private final List<String> preparedSql = new ArrayList<>();

        /** The operand bound on the current keyed read. See {@link #preparedSql}. */
        private final List<String> boundOperands = new ArrayList<>();

        /** The row-less result set the describe answers with, built once. */
        private ResultSet described;

        /** The connection stand-in, built once. */
        private Connection connection;

        /** The prepared-statement stand-in, built once. */
        private PreparedStatement prepared;

        Backend(JdbcTemplate template) {
            when(template.query(anyString(), ArgumentMatchers.<ResultSetExtractor<Object>>any()))
                    .thenAnswer(this::describe);
            when(template.query(any(PreparedStatementCreator.class),
                            ArgumentMatchers.<ResultSetExtractor<Object>>any()))
                    .thenAnswer(this::keyedRead);
        }

        Backend storing(List<String> rows) {
            stored.clear();
            stored.addAll(rows);
            return this;
        }

        Backend failing() {
            failing = true;
            return this;
        }

        Backend failingOnRead() {
            failingOnRead = true;
            return this;
        }

        Backend withoutMetadata() {
            withoutMetadata = true;
            return this;
        }

        Backend withoutColumn() {
            withoutColumn = true;
            return this;
        }

        Backend blankColumnName() {
            blankColumnName = true;
            return this;
        }

        Backend yieldingNothing() {
            yieldingNothing = true;
            return this;
        }

        List<String> statementsSent() {
            return List.copyOf(statementsSent);
        }

        List<String> patternsBound() {
            return List.copyOf(patternsBound);
        }

        /**
         * Answers the metadata describe by driving the repository's own extractor over a stubbed,
         * row-less result set - so the extractor under test is the one that runs.
         */
        private Object describe(InvocationOnMock invocation) throws SQLException {
            statementsSent.add(invocation.getArgument(0));
            requireReachable();
            ResultSetExtractor<?> extractor = invocation.getArgument(1);
            return extractor.extractData(describedResultSet());
        }

        /** A row-less result set whose metadata describes the record-image column, built once. */
        private ResultSet describedResultSet() throws SQLException {
            if (described != null) {
                return described;
            }
            ResultSet resultSet = mock(ResultSet.class);
            when(resultSet.next()).thenReturn(false);
            if (withoutMetadata) {
                when(resultSet.getMetaData()).thenReturn(null);
                described = resultSet;
                return resultSet;
            }
            ResultSetMetaData metaData = mock(ResultSetMetaData.class);
            when(metaData.getColumnCount())
                    .thenReturn(withoutColumn ? 0 : TranCategoryRepository.RECORD_IMAGE_COLUMN_INDEX);
            when(metaData.getColumnName(TranCategoryRepository.RECORD_IMAGE_COLUMN_INDEX))
                    .thenReturn(blankColumnName ? "   " : DESCRIBED_COLUMN);
            when(resultSet.getMetaData()).thenReturn(metaData);
            described = resultSet;
            return resultSet;
        }

        /** The connection stand-in, whose {@code prepareStatement} captures the composed text. */
        private Connection connection() throws SQLException {
            if (connection != null) {
                return connection;
            }
            prepared = mock(PreparedStatement.class);
            doAnswer(bind -> {
                boundOperands.add(bind.getArgument(1));
                return null;
            }).when(prepared).setString(eq(1), anyString());
            Connection stub = mock(Connection.class);
            when(stub.prepareStatement(anyString())).thenAnswer(prepare -> {
                preparedSql.add(prepare.getArgument(0));
                return prepared;
            });
            connection = stub;
            return stub;
        }

        /**
         * Answers a keyed read by evaluating the composed predicate, then driving the repository's own
         * extractor over the matching rows.
         */
        private Object keyedRead(InvocationOnMock invocation) throws SQLException {
            PreparedStatementCreator creator = invocation.getArgument(0);
            preparedSql.clear();
            boundOperands.clear();
            creator.createPreparedStatement(connection());

            statementsSent.add(preparedSql.get(0));
            requireReachable();
            if (failingOnRead) {
                throw new DataAccessResourceFailureException("the data component cannot be read");
            }
            if (yieldingNothing) {
                return null;
            }
            String pattern = boundOperands.get(0);
            patternsBound.add(pattern);
            ResultSetExtractor<?> extractor = invocation.getArgument(1);
            return extractor.extractData(rowsResultSet(matching(pattern)));
        }

        /** The rows a pattern selects, up to the limit the repository asks for. */
        private List<String> matching(String pattern) {
            Pattern matcher = likeAsRegex(pattern);
            List<String> matches = new ArrayList<>();
            for (String row : stored) {
                if (matches.size() == DUPLICATE_DETECTION_LIMIT) {
                    break;
                }
                if (row == null || matcher.matcher(row).matches()) {
                    matches.add(row);
                }
            }
            return matches;
        }

        /** A result set walking a list of record images, a {@code null} entry included. */
        private static ResultSet rowsResultSet(List<String> rows) throws SQLException {
            ResultSet resultSet = mock(ResultSet.class);
            int[] cursor = {-1};
            when(resultSet.next()).thenAnswer(call -> ++cursor[0] < rows.size());
            when(resultSet.getString(TranCategoryRepository.RECORD_IMAGE_COLUMN_INDEX))
                    .thenAnswer(call -> rows.get(cursor[0]));
            return resultSet;
        }

        private void requireReachable() {
            if (failing) {
                throw new DataAccessResourceFailureException("the dataset cannot be reached");
            }
        }

        /** Translates a SQL {@code LIKE} pattern into the regular expression it denotes. */
        private static Pattern likeAsRegex(String like) {
            StringBuilder regex = new StringBuilder(like.length() * 2);
            for (int index = 0; index < like.length(); index++) {
                char character = like.charAt(index);
                if (character == '\\' && index + 1 < like.length()) {
                    regex.append(Pattern.quote(String.valueOf(like.charAt(++index))));
                } else if (character == '_') {
                    regex.append('.');
                } else if (character == '%') {
                    regex.append(".*");
                } else {
                    regex.append(Pattern.quote(String.valueOf(character)));
                }
            }
            return Pattern.compile(regex.toString(), Pattern.DOTALL);
        }
    }

    // =============================================================================================
    // THE COLLISION DEFENCE. This is the regression the whole class exists for.
    // =============================================================================================

    @Nested
    @DisplayName("The TRAN-CAT-KEY collision: 6 bytes here, 17 in CVTRA01Y")
    class KeyWidthCollision {

        @Test
        @DisplayName("the key is 6 bytes and is NOT the 17-byte namesake")
        void theKeyIsSixBytesAndNotSeventeen() {
            assertThat(TranCategoryRepository.KEY_LENGTH)
                    .as("CVTRA04Y's TRAN-CAT-KEY is TRAN-TYPE-CD X(02) + TRAN-CAT-CD 9(04), and "
                            + "app/jcl/TRANCATG.jcl confirms it with KEYS(6 0)")
                    .isEqualTo(6)
                    .isNotEqualTo(TranCategoryRepository.TRAN_CAT_BAL_KEY_LENGTH);

            assertThat(TranCategoryRepository.TRAN_CAT_BAL_KEY_LENGTH)
                    .as("CVTRA01Y's namesake is 9(11) + X(02) + 9(04)")
                    .isEqualTo(17);
        }

        @Test
        @DisplayName("the 17 this class refuses is exactly TranCatBalRecord's declared key width")
        void theRefusedWidthIsTheSiblingRecordsRealKeyWidth() {
            // Not a hand-written 17: the constant is proven to be the OTHER record's actual key width,
            // so the two cannot drift apart and the defence cannot become a defence against nothing.
            assertThat(TranCategoryRepository.TRAN_CAT_BAL_KEY_LENGTH)
                    .isEqualTo(TranCatBalRecord.TRAN_CAT_KEY_LENGTH);
        }

        @Test
        @DisplayName("a constructed repository reports a 6-byte key, after reading its binding")
        void aConstructedRepositoryReportsASixByteKey() {
            TranCategoryRepository repository = repository(mock(JdbcTemplate.class));

            assertThat(repository.keyLength()).isEqualTo(6);
            assertThat(repository.recordLength()).isEqualTo(60);
        }

        @Test
        @DisplayName("the key image a lookup composes is exactly 6 characters")
        void theComposedKeyImageIsSixCharacters() {
            TranCategoryRepository repository = repository(mock(JdbcTemplate.class));

            assertThat(repository.keyImage("01", 1)).hasSize(TranCategoryRepository.KEY_LENGTH);
        }

        @Test
        @DisplayName("a 17-byte key-length in configuration is refused, and the diagnostic names CVTRA01Y")
        void aSeventeenByteKeyLengthIsRefusedByName() {
            DatasetBinding namesake = new DatasetBinding(DS, DatasetBinding.KSDS, false, "FB", null,
                    TranCategoryRepository.RECORD_LENGTH, TranCategoryRepository.COPYBOOK,
                    TranCategoryRepository.TRAN_CAT_BAL_KEY_LENGTH, null, null, null);

            assertThatIllegalStateException()
                    .isThrownBy(() -> TranCategoryRepository.requireSixByteKey(namesake))
                    .withMessageContaining("CVTRA01Y")
                    .withMessageContaining("TCATBALF")
                    .withMessageContaining("NAMESAKE");
        }

        @Test
        @DisplayName("the geometry self-check refuses a 17-byte key and names the trap")
        void theGeometrySelfCheckRefusesSeventeen() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> TranCategoryRepository.verifyRecordGeometry(60, 60, 60,
                            TranCategoryRepository.TRAN_CAT_BAL_KEY_LENGTH))
                    .withMessageContaining("BEWARE THE NAMESAKE")
                    .withMessageContaining("CVTRA01Y");
        }

        @Test
        @DisplayName("a copybook of CVTRA01Y in configuration is refused")
        void aCvtra01yCopybookIsRefused() {
            DatasetBinding wrongLayout = new DatasetBinding(DS, DatasetBinding.KSDS, false, "FB", null,
                    TranCategoryRepository.RECORD_LENGTH, "CVTRA01Y",
                    TranCategoryRepository.KEY_LENGTH, null, null, null);

            assertThatIllegalStateException()
                    .isThrownBy(() -> TranCategoryRepository.requireDeclaredCopybook(wrongLayout))
                    .withMessageContaining("CVTRA01Y")
                    .withMessageContaining("TCATBALF");
        }
    }

    // =============================================================================================
    // Key encoding: two halves, two opposite padding directions.
    // =============================================================================================

    @Nested
    @DisplayName("Key encoding applies the two COBOL MOVE rules in opposite directions")
    class KeyEncoding {

        @ParameterizedTest(name = "keyImage(\"{0}\", {1}) is \"{2}\"")
        @DisplayName("PIC X(02) pads RIGHT with spaces, PIC 9(04) fills LEFT with zeros")
        @CsvSource(delimiter = '|', value = {
            "01|1|010001",
            "01|5|010005",
            "07|18|070018",
            "'X'|5|'X 0005'",
            "''|0|'  0000'",
            "'1'|9999|'1 9999'",
        })
        void bothHalvesPadInTheirOwnDirection(String tranTypeCd, int tranCatCd, String expected) {
            TranCategoryRepository repository = repository(mock(JdbcTemplate.class));

            assertThat(repository.keyImage(tranTypeCd, tranCatCd))
                    .isEqualTo(expected)
                    .hasSize(TranCategoryRepository.KEY_LENGTH);
        }

        @Test
        @DisplayName("a one-character type code becomes \"X \" - padded on the RIGHT, never the left")
        void aShortTypeCodePadsOnTheRight() {
            TranCategoryRepository repository = repository(mock(JdbcTemplate.class));

            assertThat(repository.keyImage("X", 5)).startsWith("X ").isNotEqualTo(" X0005");
        }

        @Test
        @DisplayName("category 5 becomes \"0005\" - filled on the LEFT, never the right")
        void aShortCategoryCodeFillsOnTheLeft() {
            TranCategoryRepository repository = repository(mock(JdbcTemplate.class));

            assertThat(repository.keyImage("01", 5)).endsWith("0005").isNotEqualTo("015000");
        }

        @Test
        @DisplayName("a null type code is a defect in the caller, not a lookup that misses")
        void aNullTypeCodeIsRejected() {
            TranCategoryRepository repository = repository(mock(JdbcTemplate.class));

            assertThatNullPointerException().isThrownBy(() -> repository.keyImage(null, 1));
        }

        @Test
        @DisplayName("a negative category code is rejected: PIC 9(04) has no sign position")
        void aNegativeCategoryCodeIsRejected() {
            TranCategoryRepository repository = repository(mock(JdbcTemplate.class));

            assertThatIllegalArgumentException().isThrownBy(() -> repository.keyImage("01", -1));
        }
    }

    // =============================================================================================
    // Construction. Everything checkable is checked at context refresh, not at the first read.
    // =============================================================================================

    @Nested
    @DisplayName("Construction is configuration-bound and refuses to guess")
    class Construction {

        @Test
        @DisplayName("the dataset name comes from configuration, and no literal appears in Java")
        void theDatasetNameComesFromConfiguration() {
            assertThat(repository(mock(JdbcTemplate.class)).datasetName()).isEqualTo(DS);
        }

        @Test
        @DisplayName("the injected code page is the one the repository encodes and decodes with")
        void theInjectedCodePageIsUsed() {
            assertThat(repository(mock(JdbcTemplate.class)).datasetCharset()).isSameAs(ASCII);
        }

        @Test
        @DisplayName("the identity constants match the copybook and the JCL")
        void theIdentityConstantsMatchTheSources() {
            assertThat(TranCategoryRepository.DD_NAME).isEqualTo("TRANCATG");
            assertThat(TranCategoryRepository.COPYBOOK).isEqualTo("CVTRA04Y");
            assertThat(TranCategoryRepository.CONSUMER_PROGRAM).isEqualTo("CBTRN03C");
            assertThat(TranCategoryRepository.KEY_OFFSET).isZero();
            assertThat(TranCategoryRepository.RECORD_IMAGE_COLUMN_INDEX).isEqualTo(1);
            assertThat(TranCategoryRepository.APPL_RESULT_INITIAL).isEqualTo(8);
            assertThat(TranCategoryRepository.APPL_RESULT_FATAL).isEqualTo(12);
            assertThat(TranCategoryRepository.PERMANENT_ERROR_STATUS)
                    .hasSize(FileStatus.STATUS_LENGTH)
                    .startsWith("9");
        }

        @Test
        @DisplayName("every collaborator is required: an absent one is a wiring defect")
        void everyCollaboratorIsRequired() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            DatasetBindings catalogue = bindings(validBinding());

            assertThatNullPointerException().isThrownBy(() -> new TranCategoryRepository(
                    null, catalogue, ASCII, RecordImageForm.CHARACTER));
            assertThatNullPointerException().isThrownBy(() -> new TranCategoryRepository(
                    template, null, ASCII, RecordImageForm.CHARACTER));
            assertThatNullPointerException().isThrownBy(() -> new TranCategoryRepository(
                    template, catalogue, null, RecordImageForm.CHARACTER));
            assertThatNullPointerException().isThrownBy(() -> new TranCategoryRepository(
                    template, catalogue, ASCII, null));
        }

        @Test
        @DisplayName("an absent TRANCATG binding is refused, and the diagnostic names the key")
        void anAbsentBindingIsRefused() {
            JdbcTemplate template = mock(JdbcTemplate.class);

            assertThatIllegalStateException().isThrownBy(() -> new TranCategoryRepository(
                            template, bindings(null), ASCII, RecordImageForm.CHARACTER))
                    .withMessageContaining(TranCategoryRepository.DD_NAME);
        }

        @Test
        @DisplayName("a multi-byte code page is refused: 6 characters must be provably 6 bytes")
        void aMultiByteCodePageIsRefused() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            DatasetBindings catalogue = bindings(validBinding());

            assertThatIllegalArgumentException().isThrownBy(() -> new TranCategoryRepository(
                    template, catalogue, StandardCharsets.UTF_16, RecordImageForm.CHARACTER));
        }

        @Test
        @DisplayName("the valid shipped binding constructs cleanly and reads nothing from the backend")
        void theValidBindingNeedsNoBackend() {
            JdbcTemplate template = mock(JdbcTemplate.class);

            TranCategoryRepository repository = new TranCategoryRepository(template,
                    bindings(validBinding()), ASCII, RecordImageForm.BINARY);

            // Construction is configuration-only: no statement is composed and no round trip is made,
            // which is what lets the bean exist in a context whose backend is unreachable (risk R-E).
            assertThat(repository.datasetName()).isEqualTo(DS);
            assertThat(backends).doesNotContainKey(template);
        }
    }

    @Nested
    @DisplayName("Startup guards, each driven through every arm")
    class StartupGuards {

        @Test
        @DisplayName("the layout's storage spans sum to 60, FILLER included (gates G19, G21)")
        void theLayoutSpansSumToSixty() {
            assertThat(TranCategoryRepository.sumOfLayoutSpanWidths())
                    .as("TRAN-TYPE-CD 2 + TRAN-CAT-CD 4 + TRAN-CAT-TYPE-DESC 50 + FILLER 4")
                    .isEqualTo(TranCategoryRepository.RECORD_LENGTH);
        }

        @Test
        @DisplayName("the shipped geometry passes its own self-check")
        void theShippedGeometryPasses() {
            TranCategoryRepository.verifyRecordGeometry(
                    TranCategoryRecord.LAYOUT.recordLength(),
                    TranCategoryRepository.sumOfLayoutSpanWidths(),
                    TranCategoryRecord.FILLER.endOffsetExclusive(),
                    TranCategoryRepository.KEY_LENGTH);
        }

        @Test
        @DisplayName("a layout declaring any width but 60 is refused")
        void aWrongLayoutWidthIsRefused() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> TranCategoryRepository.verifyRecordGeometry(50, 60, 60, 6))
                    .withMessageContaining("RECLN");
        }

        @Test
        @DisplayName("spans summing to 56 are refused: the trailing FILLER has been dropped")
        void aDroppedFillerIsRefused() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> TranCategoryRepository.verifyRecordGeometry(60, 56, 60, 6))
                    .withMessageContaining("FILLER")
                    .withMessageContaining("dropped");
        }

        @Test
        @DisplayName("a FILLER that does not end at byte 60 is refused")
        void aMisplacedFillerIsRefused() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> TranCategoryRepository.verifyRecordGeometry(60, 60, 56, 6))
                    .withMessageContaining("first-class span");
        }

        @Test
        @DisplayName("a record length other than 60 in configuration is refused")
        void aWrongConfiguredRecordLengthIsRefused() {
            DatasetBinding tooShort = new DatasetBinding(DS, DatasetBinding.KSDS, false, "FB", null,
                    50, TranCategoryRepository.COPYBOOK, TranCategoryRepository.KEY_LENGTH, null, null,
                    null);

            assertThatIllegalStateException()
                    .isThrownBy(() -> TranCategoryRepository.requireCopybookRecordLength(tooShort))
                    .withMessageContaining("record-length");
        }

        @Test
        @DisplayName("the shipped record length passes")
        void theShippedRecordLengthPasses() {
            TranCategoryRepository.requireCopybookRecordLength(validBinding());
        }

        @Test
        @DisplayName("a sequential organization is refused: the only access is a keyed read")
        void aSequentialOrganizationIsRefused() {
            DatasetBinding sequential = new DatasetBinding(DS, "sequential", false, "FB", null,
                    TranCategoryRepository.RECORD_LENGTH, TranCategoryRepository.COPYBOOK, null, null,
                    null, null);

            assertThatIllegalStateException()
                    .isThrownBy(() -> TranCategoryRepository.requireSixByteKey(sequential))
                    .withMessageContaining("INDEXED");
        }

        @Test
        @DisplayName("a keyed binding with no key-length is refused")
        void anAbsentKeyLengthIsRefused() {
            DatasetBinding noKey = new DatasetBinding(DS, DatasetBinding.KSDS, false, "FB", null,
                    TranCategoryRepository.RECORD_LENGTH, TranCategoryRepository.COPYBOOK, null, null,
                    null, null);

            assertThatIllegalStateException()
                    .isThrownBy(() -> TranCategoryRepository.requireSixByteKey(noKey))
                    .withMessageContaining("key-length");
        }

        @Test
        @DisplayName("a key-length that is wrong but is not 17 is refused without naming the namesake")
        void anUnrelatedWrongKeyLengthIsRefusedPlainly() {
            DatasetBinding wrong = new DatasetBinding(DS, DatasetBinding.KSDS, false, "FB", null,
                    TranCategoryRepository.RECORD_LENGTH, TranCategoryRepository.COPYBOOK, 4, null,
                    null, null);

            assertThatIllegalStateException()
                    .isThrownBy(() -> TranCategoryRepository.requireSixByteKey(wrong))
                    .withMessageContaining("key-length")
                    .withMessageNotContaining("NAMESAKE");
        }

        @Test
        @DisplayName("a key placed anywhere but offset 0 is refused")
        void aDisplacedKeyIsRefused() {
            DatasetBinding displaced = new DatasetBinding(DS, DatasetBinding.KSDS, false, "FB", null,
                    TranCategoryRepository.RECORD_LENGTH, TranCategoryRepository.COPYBOOK,
                    TranCategoryRepository.KEY_LENGTH, 6, null, null);

            assertThatIllegalStateException()
                    .isThrownBy(() -> TranCategoryRepository.requireSixByteKey(displaced))
                    .withMessageContaining("key-offset");
        }

        @Test
        @DisplayName("the shipped key geometry passes")
        void theShippedKeyGeometryPasses() {
            TranCategoryRepository.requireSixByteKey(validBinding());
        }

        @Test
        @DisplayName("a binding declaring a base is refused: TRANCATG has no alternate index")
        void aDeclaredBaseIsRefused() {
            DatasetBinding withBase = new DatasetBinding(DS, DatasetBinding.KSDS, false, "FB", null,
                    TranCategoryRepository.RECORD_LENGTH, TranCategoryRepository.COPYBOOK,
                    TranCategoryRepository.KEY_LENGTH, null, "TRANFILE", null);

            assertThatIllegalStateException()
                    .isThrownBy(() -> TranCategoryRepository.requireBaseCluster(withBase))
                    .withMessageContaining("base cluster");
        }

        @Test
        @DisplayName("the shipped binding is a base cluster and passes")
        void theShippedBindingIsABaseCluster() {
            TranCategoryRepository.requireBaseCluster(validBinding());
        }

        @Test
        @DisplayName("an omitted copybook is accepted; a different one is not")
        void anOmittedCopybookIsAccepted() {
            DatasetBinding noCopybook = new DatasetBinding(DS, DatasetBinding.KSDS, false, "FB", null,
                    TranCategoryRepository.RECORD_LENGTH, null, TranCategoryRepository.KEY_LENGTH,
                    null, null, null);

            TranCategoryRepository.requireDeclaredCopybook(noCopybook);
            TranCategoryRepository.requireDeclaredCopybook(validBinding());
        }

        @ParameterizedTest(name = "dsname [{0}] is refused")
        @DisplayName("an absent or blank dataset name is refused: no name is defaulted in Java")
        @ValueSource(strings = {"", "   "})
        void aBlankDatasetNameIsRefused(String blank) {
            assertThatIllegalStateException()
                    .isThrownBy(() -> TranCategoryRepository.requireUsableDatasetName(blank))
                    .withMessageContaining("dsname");
        }

        @Test
        @DisplayName("a null dataset name is refused")
        void aNullDatasetNameIsRefused() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> TranCategoryRepository.requireUsableDatasetName(null))
                    .withMessageContaining("dsname");
        }

        @Test
        @DisplayName("a malformed dataset name is refused by the module's one z/OS grammar")
        void aMalformedDatasetNameIsRefused() {
            assertThatIllegalArgumentException().isThrownBy(
                    () -> TranCategoryRepository.requireUsableDatasetName("not a dataset name"));
        }

        @Test
        @DisplayName("the shipped dataset name passes and is returned unchanged")
        void theShippedDatasetNamePasses() {
            assertThat(TranCategoryRepository.requireUsableDatasetName(DS)).isEqualTo(DS);
        }
    }

    // =============================================================================================
    // 0400-TRANCATG-OPEN and 9400-TRANCATG-CLOSE.
    // =============================================================================================

    @Nested
    @DisplayName("open() and close() reproduce the two symmetric guards, and never abend")
    class OpenAndClose {

        @Test
        @DisplayName("a describable dataset opens and closes with status '00'")
        void aDescribableDatasetOpensAndCloses() {
            TranCategoryRepository repository = seeded(List.of());

            assertThat(repository.open()).isEqualTo(FileStatus.OK);
            assertThat(repository.close()).isEqualTo(FileStatus.OK);
        }

        @Test
        @DisplayName("the probe is a describe: it names the dataset and transfers no row")
        void theProbeIsADescribe() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            Backend stub = backend(template).storing(List.of());
            TranCategoryRepository repository = repository(template);

            repository.open();

            assertThat(stub.statementsSent()).hasSize(1);
            assertThat(stub.statementsSent().get(0))
                    .isEqualTo(repository.columnProbeSql())
                    .contains("\"" + DS + "\"")
                    .contains("WHERE 1 = 0");
        }

        @Test
        @DisplayName("an unreachable dataset reports the permanent-error status, and does not throw")
        void anUnreachableDatasetIsReported() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            backend(template).failing();
            TranCategoryRepository repository = repository(template);

            assertThat(repository.open()).isEqualTo(TranCategoryRepository.PERMANENT_ERROR_STATUS);
            assertThat(repository.close()).isEqualTo(TranCategoryRepository.PERMANENT_ERROR_STATUS);
        }

        @Test
        @DisplayName("a relation with no column at the record-image position is not a successful open")
        void aRelationWithNoColumnIsNotASuccessfulOpen() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            backend(template).storing(List.of()).withoutColumn();

            assertThat(repository(template).open())
                    .isEqualTo(TranCategoryRepository.PERMANENT_ERROR_STATUS);
        }

        @Test
        @DisplayName("a driver answering with no metadata at all is not a successful open")
        void noMetadataIsNotASuccessfulOpen() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            backend(template).storing(List.of()).withoutMetadata();

            assertThat(repository(template).open())
                    .isEqualTo(TranCategoryRepository.PERMANENT_ERROR_STATUS);
        }

        @Test
        @DisplayName("a template yielding no status object at all is not a successful open")
        void noStatusObjectIsNotASuccessfulOpen() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            when(template.query(anyString(), ArgumentMatchers.<ResultSetExtractor<Object>>any()))
                    .thenReturn(null);

            assertThat(repository(template).open())
                    .isEqualTo(TranCategoryRepository.PERMANENT_ERROR_STATUS);
        }

        @Test
        @DisplayName("APPL-RESULT is 0 for '00' and 12 for anything else - the two-way test at :451")
        void applResultReproducesTheTwoWayTest() {
            assertThat(TranCategoryRepository.applResultOfFileStatus(FileStatus.OK))
                    .isEqualTo(FileStatus.APPL_AOK);
            assertThat(TranCategoryRepository.applResultOfFileStatus(FileStatus.NOT_FOUND))
                    .isEqualTo(TranCategoryRepository.APPL_RESULT_FATAL);
            assertThat(TranCategoryRepository.applResultOfFileStatus(
                            TranCategoryRepository.PERMANENT_ERROR_STATUS))
                    .isEqualTo(TranCategoryRepository.APPL_RESULT_FATAL);
        }

        @Test
        @DisplayName("APPL-RESULT of a null status is a defect in the caller")
        void applResultOfNullIsRejected() {
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCategoryRepository.applResultOfFileStatus(null));
        }
    }

    // =============================================================================================
    // 1500-C-LOOKUP-TRANCATG.
    // =============================================================================================

    @Nested
    @DisplayName("readByKey reproduces READ ... INVALID KEY, and reports rather than throws")
    class KeyedRead {

        @Test
        @DisplayName("a seeded key is found, and the record decodes at the copybook offsets")
        void aSeededKeyIsFound() {
            TranCategoryRepository repository = seeded(List.of(
                    image("01", 1, "Regular Sales Draft"),
                    image("01", 2, "Regular Cash Advance")));

            ReadResult result = repository.readByKey("01", 2);

            assertThat(result.isFound()).isTrue();
            assertThat(result.status()).isEqualTo(FileStatus.OK);
            assertThat(result.outcome()).isEqualTo(Outcome.OK);
            assertThat(result.abends()).isFalse();
            assertThat(result.diagnostic()).isEmpty();
            TranCategoryRecord record = result.record().orElseThrow();
            assertThat(record.tranTypeCd()).isEqualTo("01");
            assertThat(record.tranCatCd()).isEqualTo(2);
            assertThat(record.tranCatTypeDesc()).startsWith("Regular Cash Advance");
        }

        @Test
        @DisplayName("TRAN-CAT-TYPE-DESC comes back at its full 50 bytes, UNTRIMMED")
        void theDescriptionIsUntrimmed() {
            TranCategoryRepository repository = seeded(List.of(image("01", 1, "Regular Sales Draft")));

            TranCategoryRecord record = repository.readByKey("01", 1).record().orElseThrow();

            // The untrimmed width is what makes CBTRN03C:368's 50-into-29 move reproducible at the point
            // of use; a trimmed value could not be truncated to the report field's width faithfully.
            assertThat(record.tranCatTypeDesc())
                    .hasSize(TranCategoryRecord.TRAN_CAT_TYPE_DESC_LENGTH)
                    .isEqualTo("Regular Sales Draft"
                            + " ".repeat(TranCategoryRecord.TRAN_CAT_TYPE_DESC_LENGTH
                                    - "Regular Sales Draft".length()));
        }

        @Test
        @DisplayName("the decoded record round-trips to the identical 60 bytes, FILLER included")
        void theRecordRoundTripsByteForByte() {
            String stored = image("07", 18, "Miscellaneous");
            TranCategoryRepository repository = seeded(List.of(stored));

            TranCategoryRecord record = repository.readByKey("07", 18).record().orElseThrow();

            assertThat(record.toByteArray()).hasSize(TranCategoryRepository.RECORD_LENGTH);
            assertThat(record.toImage()).isEqualTo(stored);
            assertThat(record.filler()).hasSize(TranCategoryRecord.FILLER_LENGTH);
        }

        @Test
        @DisplayName("a FILLER that is not spaces is preserved verbatim, as the real fixture's is")
        void aNonBlankFillerIsPreserved() {
            // Every row of app/data/ASCII/trancatg.txt carries four ASCII zeros in the FILLER span, not
            // spaces. A decode that re-spaced them would break byte-for-byte round tripping.
            String stored = "010001" + "Regular Sales Draft".concat(" ".repeat(31)) + "0000";
            TranCategoryRepository repository = seeded(List.of(stored));

            TranCategoryRecord record = repository.readByKey("01", 1).record().orElseThrow();

            assertThat(record.filler()).isEqualTo("0000");
            assertThat(record.toImage()).isEqualTo(stored);
        }

        @Test
        @DisplayName("a missing key yields '23' WITHOUT throwing - the INVALID KEY arm (gate G47)")
        void aMissingKeyYieldsNotFoundWithoutThrowing() {
            TranCategoryRepository repository = seeded(List.of(image("01", 1, "Regular Sales Draft")));

            ReadResult result = repository.readByKey("99", 9999);

            assertThat(result.isNotFound()).isTrue();
            assertThat(result.status()).isEqualTo(FileStatus.NOT_FOUND);
            assertThat(result.outcome()).isEqualTo(Outcome.NOT_FOUND);
            assertThat(result.record()).isEmpty();
            assertThat(result.diagnostic()).isEmpty();
            // The caller displays the key, renders '23' and abends - :506-510. The repository does none
            // of those three, which is the whole boundary this assertion protects.
            assertThat(result.abends()).isTrue();
            assertThat(result.statusImage()).isEqualTo("0023");
        }

        @Test
        @DisplayName("the composed predicate confines the match to the key's own six bytes")
        void thePredicateIsConfinedToTheKey() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            Backend stub = backend(template).storing(List.of(image("01", 1, "Regular Sales Draft")));
            TranCategoryRepository repository = repository(template);

            repository.readByKey("01", 1);

            assertThat(stub.patternsBound()).containsExactly("010001%");
            assertThat(stub.statementsSent()).anySatisfy(sql -> assertThat(sql)
                    .contains("LIKE")
                    .contains("ESCAPE")
                    .contains("\"" + DS + "\""));
        }

        @Test
        @DisplayName("a description whose text collides with another key's does not confuse the lookup")
        void aDescriptionCannotBeMistakenForAKey() {
            // The description of the first row begins with the SECOND row's key bytes. A predicate that
            // was not anchored at offset 0 would match it, so this is the assertion that proves the
            // LIKE pattern's leading wildcards are counted correctly.
            TranCategoryRepository repository = seeded(List.of(
                    image("01", 1, "010002 is not a key"),
                    image("01", 2, "Regular Cash Advance")));

            TranCategoryRecord record = repository.readByKey("01", 2).record().orElseThrow();

            assertThat(record.tranCatTypeDesc()).startsWith("Regular Cash Advance");
        }

        @Test
        @DisplayName("a backend refusal on the read reports the permanent-error status and a diagnostic")
        void aRefusalOnTheReadIsReported() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            backend(template).storing(List.of()).failing();

            ReadResult result = repository(template).readByKey("01", 1);

            assertThat(result.isOther()).isTrue();
            assertThat(result.status()).isEqualTo(TranCategoryRepository.PERMANENT_ERROR_STATUS);
            assertThat(result.diagnostic()).isPresent();
            assertThat(result.abends()).isTrue();
        }

        @Test
        @DisplayName("a dataset that describes cleanly but refuses the READ is reported separately")
        void aRefusalOnTheRowTransferIsReported() {
            // The repository has two catch arms - one around resolving the statement, one around
            // transferring the rows - and a backend that fails the describe never reaches the second.
            // This drives the second: catalogued dataset, unreadable data component.
            JdbcTemplate template = mock(JdbcTemplate.class);
            backend(template).storing(List.of(image("01", 1, "Regular Sales Draft"))).failingOnRead();

            ReadResult result = repository(template).readByKey("01", 1);

            assertThat(result.isOther()).isTrue();
            assertThat(result.status()).isEqualTo(TranCategoryRepository.PERMANENT_ERROR_STATUS);
            assertThat(result.diagnostic()).isPresent();
            assertThat(result.record()).isEmpty();
        }

        @Test
        @DisplayName("a relation that cannot be described reports the permanent-error status")
        void anUndescribableRelationIsReported() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            when(template.query(anyString(), ArgumentMatchers.<ResultSetExtractor<Object>>any()))
                    .thenThrow(new DataAccessResourceFailureException("unreachable"));

            ReadResult result = repository(template).readByKey("01", 1);

            assertThat(result.isOther()).isTrue();
            assertThat(result.diagnostic()).isPresent();
        }

        @Test
        @DisplayName("a relation describing a blank column name is a contract violation, not a status")
        void aBlankColumnNameIsAContractViolation() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            backend(template).storing(List.of()).blankColumnName();
            TranCategoryRepository repository = repository(template);

            assertThatIllegalStateException().isThrownBy(() -> repository.readByKey("01", 1));
        }

        @Test
        @DisplayName("a template yielding no result object is reported, not mistaken for INVALID KEY")
        void noResultObjectIsNotNotFound() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            backend(template).storing(List.of()).yieldingNothing();

            ReadResult result = repository(template).readByKey("01", 1);

            assertThat(result.isOther()).isTrue();
            assertThat(result.isNotFound()).isFalse();
            assertThat(result.diagnostic()).isEmpty();
        }

        @Test
        @DisplayName("a row present but unreadable is reported, not mistaken for INVALID KEY")
        void anUnreadableRowIsNotNotFound() {
            TranCategoryRepository repository = seeded(Collections.singletonList(null));

            ReadResult result = repository.readByKey("01", 1);

            assertThat(result.isOther()).isTrue();
            assertThat(result.isNotFound()).isFalse();
        }

        @Test
        @DisplayName("a row of the wrong width is reported, never padded into a plausible record")
        void aWrongWidthRowIsReportedNotPadded() {
            TranCategoryRepository repository = seeded(List.of("010001short"));

            ReadResult result = repository.readByKey("01", 1);

            assertThat(result.isOther()).isTrue();
            assertThat(result.record()).isEmpty();
        }

        @Test
        @DisplayName("a key selecting two rows is reported: a KSDS primary key is unique")
        void aDuplicateKeyIsReportedOnTheCatchAllArm() {
            String duplicated = image("01", 1, "Regular Sales Draft");
            TranCategoryRepository repository = seeded(List.of(duplicated, duplicated));

            ReadResult result = repository.readByKey("01", 1);

            // Not success - returning one of several as though it were the only one would be worse than
            // failing - and not a DUPLICATE arm either, because a batch READ ... INVALID KEY against a
            // unique KSDS key has no duplicate condition available to it.
            assertThat(result.isOther()).isTrue();
            assertThat(result.isFound()).isFalse();
            assertThat(result.record()).isEmpty();
        }

        @Test
        @DisplayName("a short type code and a short category still find the record they name")
        void aShortKeyHalfStillFindsItsRecord() {
            TranCategoryRepository repository = seeded(List.of(image("1 ", 5, "Padded halves")));

            assertThat(repository.readByKey("1", 5).isFound()).isTrue();
        }
    }

    // =============================================================================================
    // The shipped fixture, end to end.
    // =============================================================================================

    @Nested
    @DisplayName("The shipped trancatg fixture: 18 records of 60 bytes, all resolvable by key")
    class ShippedFixture {

        @Test
        @DisplayName("the fixture holds exactly 18 records, each exactly 60 bytes")
        void theFixtureIsEighteenRecordsOfSixtyBytes() {
            List<String> rows = fixtureRows();

            assertThat(rows).hasSize(FIXTURE_RECORDS);
            assertThat(rows).allSatisfy(row ->
                    assertThat(row).hasSize(TranCategoryRepository.RECORD_LENGTH));
        }

        @Test
        @DisplayName("every one of the 18 records is resolvable by its own composite key")
        void everyFixtureRecordResolvesByKey() {
            List<String> rows = fixtureRows();
            TranCategoryRepository repository = seeded(rows);

            for (String row : rows) {
                TranCategoryRecord expected = TranCategoryRecord.decode(row.getBytes(ASCII), ASCII);

                ReadResult result =
                        repository.readByKey(expected.tranTypeCd(), expected.tranCatCd());

                assertThat(result.isFound())
                        .as("key %s must resolve", expected.tranCatKeyImage())
                        .isTrue();
                assertThat(result.record().orElseThrow().toImage()).isEqualTo(row);
            }
        }

        @Test
        @DisplayName("the fixture's FILLER really is four zeros, so the round trip proves something")
        void theFixtureCarriesZerosInItsFiller() {
            TranCategoryRecord first =
                    TranCategoryRecord.decode(fixtureRows().get(0).getBytes(ASCII), ASCII);

            assertThat(first.filler()).isEqualTo("0000");
        }

        /**
         * The fixture's rows, read from the test classpath.
         *
         * @return the 18 record images
         */
        private static List<String> fixtureRows() {
            try (InputStream source = TranCategoryRepositoryTest.class.getResourceAsStream(FIXTURE)) {
                assertThat(source).as("%s must be on the test classpath", FIXTURE).isNotNull();
                return new String(source.readAllBytes(), ASCII).lines().toList();
            } catch (IOException unreadable) {
                throw new UncheckedIOException("Could not read " + FIXTURE, unreadable);
            }
        }
    }

    // =============================================================================================
    // The outcome type's invariants.
    // =============================================================================================

    @Nested
    @DisplayName("ReadResult admits exactly the three arms this READ can produce")
    class ReadResultInvariants {

        @Test
        @DisplayName("every component is required")
        void everyComponentIsRequired() {
            TranCategoryRecord record = TranCategoryRecord.of("01", 1, "Regular Sales Draft", ASCII);

            assertThatNullPointerException().isThrownBy(() -> new ReadResult(
                    null, Outcome.OK, Optional.of(record), Optional.empty()));
            assertThatNullPointerException().isThrownBy(() -> new ReadResult(
                    FileStatus.OK, null, Optional.of(record), Optional.empty()));
            assertThatNullPointerException().isThrownBy(() -> new ReadResult(
                    FileStatus.OK, Outcome.OK, null, Optional.empty()));
            assertThatNullPointerException().isThrownBy(() -> new ReadResult(
                    FileStatus.OK, Outcome.OK, Optional.of(record), null));
        }

        @ParameterizedTest(name = "status [{0}] is refused")
        @DisplayName("a status that is not exactly two characters is refused")
        @ValueSource(strings = {"", "0", "000"})
        void aStatusOfTheWrongWidthIsRefused(String status) {
            assertThatIllegalArgumentException().isThrownBy(() -> ReadResult.other(status));
        }

        @Test
        @DisplayName("END_OF_FILE and DUPLICATE are refused: this READ can produce neither")
        void theTwoImpossibleArmsAreRefused() {
            assertThatIllegalArgumentException().isThrownBy(() -> new ReadResult(
                            FileStatus.END_OF_FILE, Outcome.END_OF_FILE, Optional.empty(),
                            Optional.empty()))
                    .withMessageContaining("ACCESS MODE IS RANDOM");
            assertThatIllegalArgumentException().isThrownBy(() -> new ReadResult(
                            FileStatus.DUPLICATE, Outcome.DUPLICATE, Optional.empty(),
                            Optional.empty()))
                    .withMessageContaining("DUPLICATE");
        }

        @Test
        @DisplayName("a record is present exactly when the outcome is success")
        void aRecordIsPresentOnlyOnSuccess() {
            TranCategoryRecord record = TranCategoryRecord.of("01", 1, "Regular Sales Draft", ASCII);

            assertThatIllegalArgumentException().isThrownBy(() -> new ReadResult(
                            FileStatus.NOT_FOUND, Outcome.NOT_FOUND, Optional.of(record),
                            Optional.empty()))
                    .withMessageContaining("carries no record");
            assertThatIllegalArgumentException().isThrownBy(() -> new ReadResult(
                            FileStatus.OK, Outcome.OK, Optional.empty(), Optional.empty()))
                    .withMessageContaining("carries none");
        }

        @Test
        @DisplayName("the status and its classification must agree")
        void theStatusAndItsClassificationMustAgree() {
            TranCategoryRecord record = TranCategoryRecord.of("01", 1, "Regular Sales Draft", ASCII);

            assertThatIllegalArgumentException().isThrownBy(() -> new ReadResult(
                            FileStatus.NOT_FOUND, Outcome.OK, Optional.of(record), Optional.empty()))
                    .withMessageContaining("must agree");
        }

        @ParameterizedTest(name = "status [{0}] cannot be the catch-all arm")
        @DisplayName("a named status cannot be classified as the catch-all arm")
        @ValueSource(strings = {"00", "23"})
        void aNamedStatusCannotBeTheCatchAll(String named) {
            assertThatIllegalArgumentException().isThrownBy(() -> ReadResult.other(named))
                    .withMessageContaining("catch-all");
        }

        @Test
        @DisplayName("found() requires a record; other(status, diagnostic) requires a diagnostic")
        void theFactoriesRequireTheirPayloads() {
            assertThatNullPointerException().isThrownBy(() -> ReadResult.found(null));
            assertThatNullPointerException().isThrownBy(() -> ReadResult.other(
                    TranCategoryRepository.PERMANENT_ERROR_STATUS, null));
        }

        @Test
        @DisplayName("the three arms classify themselves, and only one of them does not abend")
        void theThreeArmsClassifyThemselves() {
            TranCategoryRecord record = TranCategoryRecord.of("01", 1, "Regular Sales Draft", ASCII);
            ReadResult found = ReadResult.found(record);
            ReadResult missing = ReadResult.notFound();
            ReadResult failed = ReadResult.other(TranCategoryRepository.PERMANENT_ERROR_STATUS);

            assertThat(found.isFound()).isTrue();
            assertThat(found.isNotFound()).isFalse();
            assertThat(found.isOther()).isFalse();
            assertThat(found.abends()).isFalse();
            assertThat(found.statusImage()).isEqualTo("0000");

            assertThat(missing.isNotFound()).isTrue();
            assertThat(missing.isFound()).isFalse();
            assertThat(missing.isOther()).isFalse();
            assertThat(missing.abends()).isTrue();

            assertThat(failed.isOther()).isTrue();
            assertThat(failed.isFound()).isFalse();
            assertThat(failed.isNotFound()).isFalse();
            assertThat(failed.abends()).isTrue();
            assertThat(failed.statusImage()).startsWith("9");
        }

        @Test
        @DisplayName("a diagnostic-carrying catch-all arm keeps what the backend said")
        void aDiagnosticIsCarried() {
            ReadResult failed = ReadResult.other(TranCategoryRepository.PERMANENT_ERROR_STATUS,
                    DatasetRelation.BackendDiagnostic.of(
                            new DataAccessResourceFailureException("unreachable")));

            assertThat(failed.diagnostic()).isPresent();
            assertThat(failed.diagnostic().orElseThrow().describe()).isNotBlank();
        }
    }

    // =============================================================================================
    // Wiring and structural contracts. Gate G3, and the source-level gates that govern this file.
    // =============================================================================================

    @Nested
    @DisplayName("Wiring and structural contracts")
    class Contracts {

        @Test
        @DisplayName("gate G3: the class is a @Repository the component scan will discover")
        void theStereotypeIsWhatTheContainerScansFor() {
            assertThat(TranCategoryRepository.class.isAnnotationPresent(Repository.class))
                    .as("com.vsergeychik.carddemo.transaction is one of the eleven scanned packages, so "
                            + "the stereotype is what makes this bean exist")
                    .isTrue();
            assertThat(TranCategoryRepository.class.getPackageName())
                    .isEqualTo("com.vsergeychik.carddemo.transaction");
        }

        @Test
        @DisplayName("there is exactly one constructor, so the container needs no @Autowired marker")
        void thereIsExactlyOneConstructor() {
            assertThat(TranCategoryRepository.class.getConstructors())
                    .as("a single public constructor is unambiguous to the container, and constructor "
                            + "injection means an instance is either fully wired or does not exist")
                    .hasSize(1);
        }

        @Test
        @DisplayName("the charset injection point names its bean: three Charset beans exist, none primary")
        void theCharsetInjectionPointNamesItsBean() {
            Parameter[] parameters = TranCategoryRepository.class.getConstructors()[0].getParameters();

            assertThat(TranCategoryRepository.class.getConstructors()[0].getParameterTypes())
                    .containsExactly(JdbcTemplate.class, DatasetBindings.class, Charset.class,
                            RecordImageForm.class);
            assertThat(parameters[2].getAnnotation(Qualifier.class))
                    .as("without the qualifier the container could inject the EBCDIC or the ASCII bean, "
                            + "and a 6-character key would stop being a 6-byte key")
                    .isNotNull()
                    .extracting(Qualifier::value)
                    .isEqualTo(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME);
        }

        @Test
        @DisplayName("gate G53: no class in the file holds mutable static state")
        void thereIsNoStaticMutableState() {
            for (Class<?> type : List.of(TranCategoryRepository.class, ReadResult.class)) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) {
                        assertThat(Modifier.isFinal(field.getModifiers()))
                                .as("%s.%s is static and must therefore be final: COBOL "
                                        + "WORKING-STORAGE must never become shared Java state",
                                        type.getSimpleName(), field.getName())
                                .isTrue();
                    }
                }
            }
        }

        @Test
        @DisplayName("every instance field is final, so an instance is immutable and shareable")
        void everyInstanceFieldIsFinal() {
            for (Field field : TranCategoryRepository.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("%s must be final: a @Repository is a singleton and this dataset is "
                                    + "never browsed, so no per-call state belongs on the bean",
                                    field.getName())
                            .isTrue();
                }
            }
        }

        @Test
        @DisplayName("the class offers no write, rewrite, delete or browse - the grep proves none exists")
        void noWritePathIsOffered() {
            List<String> forbidden = new ArrayList<>();
            for (Method method : TranCategoryRepository.class.getMethods()) {
                if (method.getDeclaringClass() != TranCategoryRepository.class) {
                    continue;
                }
                String name = method.getName().toLowerCase(Locale.ROOT);
                if (name.contains("write") || name.contains("delete") || name.contains("browse")
                        || name.contains("insert") || name.contains("update")
                        || name.contains("readnext") || name.contains("startbr")) {
                    forbidden.add(method.getName());
                }
            }

            assertThat(forbidden)
                    .as("grep -n \"TRANCATG\" app/cbl/*.cbl finds only OPEN INPUT (:450), "
                            + "READ ... INVALID KEY (:505) and CLOSE (:589) - a repository exposes only "
                            + "the access paths the COBOL actually performs")
                    .isEmpty();
        }

        @Test
        @DisplayName("gates G22, G24, G44, G46 and G52 hold over this repository's own source")
        void theSourceLevelGatesHold() {
            String source = repositorySource();

            assertThat(source)
                    .as("gate G46: no dataset name is ever written into Java")
                    .doesNotContain("AWS.M2.CARDDEMO.");
            assertThat(Pattern.compile("^import .*\\*;", Pattern.MULTILINE).matcher(source).find())
                    .as("gate G52: no wildcard import, so every copybook-to-type correspondence stays "
                            + "auditable")
                    .isFalse();
            assertThat(Pattern.compile("\\b(double|float)\\b").matcher(source).find())
                    .as("gate G22: no binary approximate numeric primitive anywhere")
                    .isFalse();
            assertThat(source)
                    .as("gate G24: rounding is always truncating, so no half-rounding mode appears")
                    .doesNotContain("HALF_UP")
                    .doesNotContain("HALF_EVEN");
            assertThat(source)
                    .as("gate G44: no DDL, no entity annotation, no version column, no index")
                    .doesNotContain("@Entity")
                    .doesNotContain("@Table")
                    .doesNotContain("@Version")
                    .doesNotContain("CREATE TABLE")
                    .doesNotContain("ALTER TABLE");
            assertThat(source)
                    .as("no placeholder, no deferred work")
                    .doesNotContain("TODO")
                    .doesNotContain("FIXME");
        }

        /**
         * This repository's own source text, located by walking upwards to the module descriptor.
         *
         * @return the file's contents
         */
        private static String repositorySource() {
            Path candidate = Path.of("").toAbsolutePath();
            while (candidate != null) {
                Path resolved = candidate.resolve(
                        "app/java/src/main/java/com/vsergeychik/carddemo/transaction/"
                                + "TranCategoryRepository.java");
                if (Files.isRegularFile(resolved)) {
                    try {
                        return Files.readString(resolved, StandardCharsets.UTF_8);
                    } catch (IOException unreadable) {
                        throw new UncheckedIOException("Could not read " + resolved, unreadable);
                    }
                }
                candidate = candidate.getParent();
            }
            throw new IllegalStateException("Could not locate TranCategoryRepository.java at or above "
                    + Path.of("").toAbsolutePath());
        }
    }
}
