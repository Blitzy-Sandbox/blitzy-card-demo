package com.vsergeychik.carddemo.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;
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
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
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
 * <h2>Three overlapping COBOL names, in one Java package - and which is which</h2>
 * <p>{@code app/cpy} reuses the same handful of identifiers at different widths, and three of those
 * reuses land inside this one package. The point of {@link CobolNameCollisions} below is that a future
 * reader can tell them apart <em>from the tests alone</em>, without opening a copybook:
 * <ol>
 *   <li><strong>{@code TRAN-CAT-KEY} - 6 bytes here, 17 in {@code CVTRA01Y}.</strong> The group name is
 *       <em>identical</em>. This one is {@code TRAN-TYPE-CD X(02)} plus {@code TRAN-CAT-CD 9(04)} on a
 *       60-byte {@code TRAN-CAT-RECORD} ({@code TRANCATG}). The namesake is
 *       {@code TRANCAT-ACCT-ID 9(11)} plus {@code TRANCAT-TYPE-CD X(02)} plus {@code TRANCAT-CD 9(04)}
 *       on a 50-byte {@code TRAN-CAT-BAL-RECORD} ({@code TCATBALF}), modelled by
 *       {@code TranCatBalRecord}, reached through {@code TranCatBalRepository} and covered by
 *       {@code TranCatBalRepositoryTest}. <strong>Different width, different members, different record,
 *       different dataset, same name.</strong></li>
 *   <li><strong>{@code TRAN-TYPE-CD} and {@code TRAN-CAT-CD} are also {@code CVTRA05Y}'s item
 *       names.</strong> The 350-byte {@code TRAN-RECORD} declares both, at the same pictures. Because
 *       {@code CBTRN03C} copies {@code CVTRA05Y} ({@code :93}) and {@code CVTRA04Y} ({@code :108}) into
 *       one program, the bare names are genuinely ambiguous there and COBOL <em>forces</em> explicit
 *       qualification at five sites - {@code :189}, {@code :191}, {@code :193}, {@code :365} and
 *       {@code :367}, all reading {@code OF TRAN-RECORD}.</li>
 *   <li><strong>{@code CVTRA03Y} names its analogous key {@code TRAN-TYPE}, without the {@code -CD}
 *       suffix.</strong> The 60-byte {@code TRAN-TYPE-RECORD} ({@code TRANTYPE}, the sibling lookup
 *       {@code CBTRN03C} performs immediately before this one at {@code :190}) keys on
 *       {@code TRAN-TYPE PIC X(02)}. Same two bytes, same meaning, one suffix apart.</li>
 * </ol>
 * <p>Not one of these is resolved by renaming a field (practice B4). Java's type system separates them
 * because the records are distinct types; the COBOL names are carried through verbatim, and this suite
 * asserts that they are, so a later "tidy-up" that disambiguated by renaming would fail here.
 *
 * <h2>There is deliberately no {@code BigDecimal} anywhere in this suite</h2>
 * <p>{@code CVTRA04Y} declares no signed decimal at all - its four spans are {@code X(02)},
 * {@code 9(04)}, {@code X(50)} and {@code FILLER X(04)} - so rule R4 and gate G22 have no subject here
 * and the production classes correctly do not depend on {@code common.CobolDecimal}. That omission is
 * <em>asserted</em> rather than assumed ({@link Contracts#theSourceLevelGatesHold()}), because
 * introducing a {@code BigDecimal} for the category code would be the natural-looking mistake: it is a
 * {@code PIC 9} field, and a scale-free {@code PIC 9} maps to {@code int}.
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

    /**
     * The width of {@code CVTRA07Y}'s {@code TRAN-REPORT-CAT-DESC PIC X(29)} - the report field this
     * record's 50-byte description is moved into at {@code app/cbl/CBTRN03C.cbl:368}.
     *
     * <p>Declared here rather than imported, because the report layout is not this repository's
     * business: the width is the <em>receiver's</em>, and this suite only proves that the sender arrives
     * wide enough for the move to be reproducible. The formatting of the report line itself belongs to
     * {@code TranReportWriterTest} and to the {@code TranReportLayouts} test, and is deliberately not
     * asserted here.
     */
    private static final int TRAN_REPORT_CAT_DESC_LENGTH = 29;

    /**
     * The literal {@code app/cbl/CBTRN03C.cbl:507} displays ahead of the key, including its trailing
     * space: {@code DISPLAY 'INVALID TRAN CATG KEY : ' FD-TRAN-CAT-KEY}.
     *
     * <p>COBOL's {@code DISPLAY} concatenates its operands with no separator of its own, so the emitted
     * {@code SYSOUT} line is this literal immediately followed by the six raw key bytes.
     */
    private static final String INVALID_KEY_DISPLAY_LITERAL = "INVALID TRAN CATG KEY : ";

    /**
     * A description deliberately longer than {@value #TRAN_REPORT_CAT_DESC_LENGTH} characters, so the
     * {@code X(50)}-into-{@code X(29)} truncation at {@code app/cbl/CBTRN03C.cbl:368} is observable.
     *
     * <p><strong>No shipped row can serve this purpose.</strong> Measuring every description in
     * {@code app/data/ASCII/trancatg.txt} gives a maximum trimmed length of exactly
     * {@value #TRAN_REPORT_CAT_DESC_LENGTH} - {@code "Online purchase authorization"} (type {@code 04},
     * category {@code 0002}) and {@code "Sales draft credit adjustment"} (type {@code 07}, category
     * {@code 0001}). The shipped data therefore <em>reaches</em> the report field's width and never
     * crosses it, so a suite that used only shipped rows would assert that truncation never happens and
     * would pass just as happily if the description were being trimmed. This constructed value crosses
     * the boundary; {@link ReportProjection} asserts both sides of it.
     */
    private static final String OVERLONG_DESCRIPTION = "Convenience Check Debit Adjustment Reversal";

    /** A shipped description that is exactly the report field's width: the boundary case, untruncated. */
    private static final String BOUNDARY_DESCRIPTION = "Online purchase authorization";

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
    /** Four TRANCATG rows spanning two type codes, enough to stand in for four report detail lines. */
    private static List<String> seedRows() {
        return List.of(image("01", 1, "Regular Sales Draft"),
                image("01", 2, "Cash Advance"),
                image("02", 1, "Refund"),
                image("05", 1, "Payment"));
    }

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

        @Test
        @DisplayName("the six-byte key image is exactly what CBTRN03C:507's DISPLAY renders")
        void theKeyImageIsWhatTheDisplayRenders() {
            TranCategoryRepository repository = repository(mock(JdbcTemplate.class));

            // 1500-C-LOOKUP-TRANCATG's INVALID KEY arm emits, at app/cbl/CBTRN03C.cbl:507,
            //     DISPLAY 'INVALID TRAN CATG KEY : '  FD-TRAN-CAT-KEY
            // and FD-TRAN-CAT-KEY (:79-81) is the SIX raw bytes FD-TRAN-TYPE-CD X(02) followed by
            // FD-TRAN-CAT-CD 9(04) - not a formatted number, not a trimmed value, and not the 17-byte
            // namesake. DISPLAY concatenates its operands with no separator, so the SYSOUT line the
            // parity harness fingerprints is the literal followed immediately by those six bytes.
            String keyImage = repository.keyImage("01", 1);

            assertThat(keyImage)
                    .as("PIC X(02) then PIC 9(04): the leading zeros of both halves survive")
                    .isEqualTo("010001")
                    .hasSize(TranCategoryRepository.KEY_LENGTH);
            assertThat(INVALID_KEY_DISPLAY_LITERAL + keyImage)
                    .as("the whole SYSOUT line CBTRN03C:507 would emit for this key")
                    .isEqualTo("INVALID TRAN CATG KEY : 010001");
        }

        @Test
        @DisplayName("there is ONE authoritative key image: the repository's equals the record's own")
        void thereIsOneAuthoritativeKeyImage() {
            // The repository composes the key from two fields; the record reads it straight out of the
            // stored bytes. If those two ever disagreed, a lookup could succeed while the DISPLAY on the
            // failing path printed something else - so they are proven to be the same six characters.
            TranCategoryRepository repository = seeded(List.of(image("04", 2, BOUNDARY_DESCRIPTION)));

            TranCategoryRecord record = repository.readByKey("04", 2).record().orElseThrow();

            assertThat(record.tranCatKeyImage())
                    .isEqualTo(repository.keyImage("04", 2))
                    .isEqualTo("040002");
            assertThat(record.tranCatKeyBytes())
                    .hasSize(TranCategoryRepository.KEY_LENGTH)
                    .isEqualTo("040002".getBytes(ASCII));
        }
    }

    // =============================================================================================
    // The three overlapping COBOL names, and the COBOL-to-Java type mapping that depends on them.
    // =============================================================================================

    /**
     * The three name collisions this package has to live with, each documented and asserted.
     *
     * <p>Practice B4 forbids resolving any of them by renaming a COBOL field, so what these tests
     * protect is that the names really were carried through verbatim. That is a stronger guarantee than
     * a comment: a later change that disambiguated {@code TRAN-CAT-KEY} by calling this one
     * {@code TRAN-CAT-TYPE-KEY}, or that dropped the {@code -CD} suffix to match {@code CVTRA03Y}, would
     * fail here rather than sail through review looking like an improvement.
     */
    @Nested
    @DisplayName("The three COBOL name collisions, documented and asserted (practice B4)")
    class CobolNameCollisions {

        @Test
        @DisplayName("collision 1: the group is still called TRAN-CAT-KEY, and it is 6 bytes not 17")
        void collisionOneTheGroupNameIsCarriedThroughVerbatim() {
            // app/cpy/CVTRA04Y.cpy       05 TRAN-CAT-KEY = X(02) + 9(04)                    ->  6 bytes
            // app/cpy/CVTRA01Y.cpy       05 TRAN-CAT-KEY = 9(11) + X(02) + 9(04)            -> 17 bytes
            // The 17-byte one belongs to TRAN-CAT-BAL-RECORD on TCATBALF: it is modelled by
            // TranCatBalRecord, reached through TranCatBalRepository, and covered by
            // TranCatBalRepositoryTest. Neither group is renamed - the clash is part of the contract.
            assertThat(TranCategoryRecord.TRAN_CAT_KEY_NAME)
                    .as("the COBOL group name, verbatim - not disambiguated")
                    .isEqualTo("TRAN-CAT-KEY");
            assertThat(TranCategoryRecord.TRAN_CAT_KEY_LENGTH)
                    .as("CVTRA04Y: TRAN-TYPE-CD X(02) + TRAN-CAT-CD 9(04), by addition")
                    .isEqualTo(TranCategoryRecord.TRAN_TYPE_CD_LENGTH
                            + TranCategoryRecord.TRAN_CAT_CD_LENGTH)
                    .isEqualTo(6);
            assertThat(TranCatBalRecord.TRAN_CAT_KEY_NAME)
                    .as("CVTRA01Y uses the very same name for a different group")
                    .isEqualTo(TranCategoryRecord.TRAN_CAT_KEY_NAME);
            assertThat(TranCatBalRecord.TRAN_CAT_KEY_LENGTH)
                    .as("and it is 17 bytes, so the two are told apart by WIDTH and never by name")
                    .isEqualTo(17)
                    .isNotEqualTo(TranCategoryRecord.TRAN_CAT_KEY_LENGTH);
        }

        @Test
        @DisplayName("collision 2: TRAN-TYPE-CD and TRAN-CAT-CD are also CVTRA05Y's item names")
        void collisionTwoTheItemNamesAreSharedWithTheTransactionRecord() {
            // app/cpy/CVTRA05Y.cpy - the 350-byte TRAN-RECORD - declares
            //     05  TRAN-TYPE-CD  PIC X(02).
            //     05  TRAN-CAT-CD   PIC 9(04).
            // exactly the names and pictures CVTRA04Y uses for the two halves of its key. CBTRN03C
            // copies BOTH copybooks (CVTRA05Y at :93, CVTRA04Y at :108), so inside that one program the
            // bare names are ambiguous and COBOL FORCES qualification at five sites, every one of them
            // reading "OF TRAN-RECORD":
            //     :189  MOVE TRAN-TYPE-CD OF TRAN-RECORD TO FD-TRAN-TYPE            (the TRANTYPE key)
            //     :191  MOVE TRAN-TYPE-CD OF TRAN-RECORD TO FD-TRAN-TYPE-CD OF FD-TRAN-CAT-KEY
            //     :193  MOVE TRAN-CAT-CD  OF TRAN-RECORD TO FD-TRAN-CAT-CD  OF FD-TRAN-CAT-KEY
            //     :365  MOVE TRAN-TYPE-CD OF TRAN-RECORD TO TRAN-REPORT-TYPE-CD
            //     :367  MOVE TRAN-CAT-CD  OF TRAN-RECORD TO TRAN-REPORT-CAT-CD
            // Note that the RECEIVERS at :191 and :193 are qualified too - the ambiguity runs both ways.
            // In Java the two records are distinct types, so no qualification is needed and none is
            // invented; what must hold is that the names were not changed to dodge the clash.
            assertThat(TranCategoryRecord.TRAN_TYPE_CD.name()).isEqualTo("TRAN-TYPE-CD");
            assertThat(TranCategoryRecord.TRAN_CAT_CD.name()).isEqualTo("TRAN-CAT-CD");

            // And the pictures agree with CVTRA05Y's, which is why the two halves can be passed straight
            // through from a transaction record without any reshaping at the call site.
            assertThat(TranCategoryRecord.TRAN_TYPE_CD.length()).isEqualTo(2);
            assertThat(TranCategoryRecord.TRAN_CAT_CD.length()).isEqualTo(4);
        }

        @Test
        @DisplayName("collision 3: CVTRA03Y names its analogous key TRAN-TYPE, without the -CD suffix")
        void collisionThreeTheSiblingLookupDropsTheCdSuffix() {
            // app/cpy/CVTRA03Y.cpy - the 60-byte TRAN-TYPE-RECORD behind the TRANTYPE dataset - keys on
            //     05  TRAN-TYPE  PIC X(02).
            // Same two bytes, same meaning, ONE SUFFIX APART from this record's TRAN-TYPE-CD. The two
            // lookups are performed back to back by CBTRN03C - 1500-B-LOOKUP-TRANTYPE at :190, then
            // 1500-C-LOOKUP-TRANCATG at :195 - which is precisely where a suffix would be dropped by
            // accident. This record keeps the suffix.
            assertThat(TranCategoryRecord.TRAN_TYPE_CD.name())
                    .as("CVTRA04Y's item keeps its -CD suffix")
                    .isEqualTo("TRAN-TYPE-CD")
                    .isNotEqualTo("TRAN-TYPE");
            assertThat(TranCategoryRecord.TRAN_TYPE_CD.name())
                    .as("and CVTRA03Y's TRAN-TYPE is a strict prefix of it, which is the whole trap")
                    .startsWith("TRAN-TYPE");
        }

        @Test
        @DisplayName("TRAN-TYPE-CD is a String because PIC X(02) makes the leading zero significant")
        void theTypeCodeIsAStringAndNeverAnInt() throws ReflectiveOperationException {
            // PIC X(02) is alphanumeric. Every shipped value looks numeric ("01".."07"), which is exactly
            // why an int is the tempting mistake: it would turn "01" into 1, render back as "1 " or "1",
            // and compose a key that matches nothing. The picture, the Java type and the observable
            // behaviour are all asserted, because any one of them alone could drift.
            assertThat(TranCategoryRecord.TRAN_TYPE_CD.kind()).isEqualTo(PictureKind.ALPHANUMERIC);
            assertThat(TranCategoryRecord.class.getMethod("tranTypeCd").getReturnType())
                    .as("PIC X(02) decodes to String")
                    .isEqualTo(String.class);
            assertThat(TranCategoryRepository.class.getMethod("keyImage", String.class, int.class))
                    .as("the lookup's first parameter is the 2-character code, not a number")
                    .isNotNull();

            TranCategoryRecord record = TranCategoryRecord.of("01", 1, "Regular Sales Draft", ASCII);
            assertThat(record.tranTypeCd()).isEqualTo("01");
            assertThat(record.tranCatKeyImage()).startsWith("01");
        }

        @Test
        @DisplayName("TRAN-CAT-CD is an int because PIC 9(04) is scale-free and unsigned")
        void theCategoryCodeIsAnIntFromPic9() throws ReflectiveOperationException {
            // A scale-free PIC 9 maps to int (rule R4 reserves BigDecimal for PIC 9...V... and COMP-3,
            // and CVTRA04Y declares neither). Stored zoned DISPLAY, one digit per byte, zero-filled on
            // the LEFT - which is how the fixture's "0001" denotes 1.
            assertThat(TranCategoryRecord.TRAN_CAT_CD.kind()).isEqualTo(PictureKind.UNSIGNED_NUMERIC);
            assertThat(TranCategoryRecord.class.getMethod("tranCatCd").getReturnType())
                    .as("PIC 9(04) with no V decodes to int, never to BigDecimal and never to double")
                    .isEqualTo(int.class);
            assertThat(TranCategoryRepository.class.getMethod("readByKey", String.class, int.class)
                    .getParameterTypes())
                    .as("readByKey takes the two halves in the order of the moves at :191-194")
                    .containsExactly(String.class, int.class);

            TranCategoryRecord record = TranCategoryRecord.of("01", 1, "Regular Sales Draft", ASCII);
            assertThat(record.tranCatCd()).isEqualTo(1);
            assertThat(record.fieldImage(TranCategoryRecord.TRAN_CAT_CD))
                    .as("the int 1 is stored as four zoned digits")
                    .isEqualTo("0001");
        }

        @Test
        @DisplayName("no accidental all-numeric overload exists through which \"01\" could become 1")
        void thereIsNoNumericKeyOverload() {
            List<Method> numericEntryPoints = new ArrayList<>();
            for (Method method : TranCategoryRepository.class.getMethods()) {
                if (method.getDeclaringClass() != TranCategoryRepository.class) {
                    continue;
                }
                if (!"readByKey".equals(method.getName()) && !"keyImage".equals(method.getName())) {
                    continue;
                }
                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length > 0 && !String.class.equals(parameters[0])) {
                    numericEntryPoints.add(method);
                }
            }

            assertThat(numericEntryPoints)
                    .as("an int-typed TRAN-TYPE-CD would collapse \"01\" to 1 and silently miss every "
                            + "lookup, so no such overload is offered")
                    .isEmpty();
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
        @DisplayName("re-opening after a close succeeds: no handle is held between operations")
        void reOpeningAfterACloseSucceeds() {
            TranCategoryRepository repository = seeded(List.of(image("01", 1, "Regular Sales Draft")));

            // CBTRN03C opens once (:165) and closes once (:212), so a re-open is not something the
            // legacy program does - but this repository is a singleton shared across report runs, and
            // nothing about one run may leave it unusable for the next. There is no persistent cursor,
            // no cached column name and no open flag: each operation borrows and returns a connection,
            // so open/close/open is simply three independent probes. Were any state retained, the
            // second open would be the first place it showed up.
            assertThat(repository.open()).isEqualTo(FileStatus.OK);
            assertThat(repository.close()).isEqualTo(FileStatus.OK);
            assertThat(repository.open())
                    .as("a closed dataset re-opens cleanly, exactly as the first open did")
                    .isEqualTo(FileStatus.OK);

            // And a read still works after the whole cycle, which is what "usable" actually means.
            assertThat(repository.readByKey("01", 1).isFound()).isTrue();
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
        @DisplayName("the open's describe is the only one: four reads after it add no metadata query")
        void theDescribeHappensOnceAtOpenAndIsReusedByEveryRead() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            Backend stub = backend(template).storing(seedRows());
            TranCategoryRepository repository = repository(template);

            assertThat(repository.open()).isEqualTo(FileStatus.OK);
            String probe = repository.columnProbeSql();
            assertThat(stub.statementsSent()).containsExactly(probe);

            // CBTRN03C performs 1500-C-LOOKUP-TRANCATG once per report line (:195). Four lines here.
            repository.readByKey("01", 1);
            repository.readByKey("01", 2);
            repository.readByKey("02", 1);
            repository.readByKey("05", 1);

            assertThat(stub.statementsSent())
                    .as("one describe at OPEN INPUT and one keyed read per report line - describing "
                            + "before each lookup would double the detail loop's query count and would "
                            + "answer a question that cannot have changed while the dataset is open")
                    .filteredOn(probe::equals)
                    .hasSize(1);
            assertThat(stub.statementsSent()).hasSize(5);
        }

        @Test
        @DisplayName("a CLOSE forgets the statement, so the next read describes the dataset again")
        void aCloseForgetsWhatTheOpenLearned() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            Backend stub = backend(template).storing(seedRows());
            TranCategoryRepository repository = repository(template);
            String probe = repository.columnProbeSql();

            assertThat(repository.open()).isEqualTo(FileStatus.OK);
            repository.readByKey("01", 1);
            assertThat(repository.close()).isEqualTo(FileStatus.OK);
            // A read after a CLOSE must not reuse a statement over a relation that may since have been
            // de-allocated, so it describes the dataset afresh.
            repository.readByKey("01", 1);

            assertThat(stub.statementsSent())
                    .filteredOn(probe::equals)
                    .as("one describe for the open, one for the close, and one for the read that "
                            + "followed the close")
                    .hasSize(3);
        }

        @Test
        @DisplayName("a read on an unopened dataset still works, describing it once and then reusing it")
        void aReadWithoutAnOpenResolvesOnceAndCaches() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            Backend stub = backend(template).storing(seedRows());
            TranCategoryRepository repository = repository(template);
            String probe = repository.columnProbeSql();

            assertThat(repository.readByKey("01", 1).isFound()).isTrue();
            assertThat(repository.readByKey("01", 2).isFound()).isTrue();

            assertThat(stub.statementsSent()).filteredOn(probe::equals).hasSize(1);
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

            // NOTHING ESCAPES. Asserted explicitly rather than left to be implied by the test simply not
            // erroring: "reported, never thrown" is the contract this whole class turns on, so it is
            // stated as an assertion in its own right. An exception here would skip CBTRN03C's DISPLAY of
            // the key at :507 and the rendered status from 9910-DISPLAY-IO-STATUS, and would replace the
            // program's own abend path with this repository's.
            assertThatNoException()
                    .isThrownBy(() -> repository.readByKey("99", 9999));

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
        @DisplayName("the three arms are mutually exclusive: 'other' is neither '00' nor '23'")
        void theInducedFailureStatusIsDistinctFromBothNamedStatuses() {
            // The agent contract for this dataset is three arms and exactly three. Proving the catch-all
            // arm exists is not enough - it has to be DISTINGUISHABLE, because the caller branches on the
            // status and CBTRN03C's guard chain treats '00' as success and '23' as INVALID KEY. A
            // permanent error that rendered as either of those would be routed down the wrong arm.
            TranCategoryRepository ok = seeded(List.of(image("01", 1, "Regular Sales Draft")));
            JdbcTemplate refusing = mock(JdbcTemplate.class);
            backend(refusing).storing(List.of(image("01", 1, "Regular Sales Draft")))
                    .failingOnRead();
            TranCategoryRepository broken = repository(refusing);

            ReadResult found = ok.readByKey("01", 1);
            ReadResult missing = ok.readByKey("99", 9999);
            ReadResult other = broken.readByKey("01", 1);

            assertThat(found.status()).isEqualTo(FileStatus.OK);
            assertThat(missing.status()).isEqualTo(FileStatus.NOT_FOUND);
            assertThat(other.status())
                    .as("the catch-all status is neither of the two the READ names explicitly")
                    .isEqualTo(TranCategoryRepository.PERMANENT_ERROR_STATUS)
                    .isNotEqualTo(FileStatus.OK)
                    .isNotEqualTo(FileStatus.NOT_FOUND);
            assertThat(List.of(found.status(), missing.status(), other.status()))
                    .as("three arms, three distinct statuses")
                    .doesNotHaveDuplicates();
            assertThat(List.of(found.outcome(), missing.outcome(), other.outcome()))
                    .containsExactly(Outcome.OK, Outcome.NOT_FOUND, Outcome.OTHER)
                    .doesNotHaveDuplicates();
            // And the rendered IO-STATUS images differ too, which is what actually reaches SYSOUT.
            assertThat(other.statusImage())
                    .isNotEqualTo(found.statusImage())
                    .isNotEqualTo(missing.statusImage());
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

    // =============================================================================================
    // The 50-into-29 move, and the FILLER. Both are pure layout facts, and both are load-bearing.
    // =============================================================================================

    /**
     * Why the description must arrive at all 50 bytes, proven on both sides of the 29-character
     * boundary.
     *
     * <p>{@code app/cbl/CBTRN03C.cbl:368} performs
     * {@code MOVE TRAN-CAT-TYPE-DESC TO TRAN-REPORT-CAT-DESC}: a {@code PIC X(50)} sender into
     * {@code app/cpy/CVTRA07Y.cpy}'s {@code PIC X(29)} receiver. COBOL fills an alphanumeric receiver
     * from its leftmost position and discards the overflow, so the report line carries the
     * <strong>first {@value #TRAN_REPORT_CAT_DESC_LENGTH} characters</strong> and nothing else.
     *
     * <p>That truncation belongs at the point of use, never in the repository. These tests prove the
     * repository hands over a sender wide enough for the move to be reproduced faithfully - they do
     * <em>not</em> assert the shape of the report line itself, which is
     * {@code TranReportWriterTest}'s and the {@code TranReportLayouts} test's subject.
     */
    @Nested
    @DisplayName("The X(50)-into-X(29) report projection, and the trailing FILLER")
    class ReportProjection {

        /** The codec the point of use would apply the move with, over the named dataset code page. */
        private final FixedWidthCodec codec = new FixedWidthCodec(ASCII);

        @Test
        @DisplayName("a description longer than 29 characters keeps all 50, and projects to its first 29")
        void anOverlongDescriptionIsTruncatedOnlyAtThePointOfUse() {
            TranCategoryRepository repository =
                    seeded(List.of(image("09", 7, OVERLONG_DESCRIPTION)));

            TranCategoryRecord record = repository.readByKey("09", 7).record().orElseThrow();
            String description = record.tranCatTypeDesc();

            // (a) the repository returns the sender UNTRIMMED, at its full declared width.
            assertThat(OVERLONG_DESCRIPTION.length())
                    .as("the constructed description really does cross the boundary")
                    .isGreaterThan(TRAN_REPORT_CAT_DESC_LENGTH);
            assertThat(description)
                    .hasSize(TranCategoryRecord.TRAN_CAT_TYPE_DESC_LENGTH)
                    .startsWith(OVERLONG_DESCRIPTION)
                    .endsWith(" ");

            // (b) the report field carries exactly the leading 29 characters - truncated on the RIGHT,
            //     which is the COBOL rule for a PIC X receiver. "Convenience Check Debit Adjus" here:
            //     the surviving characters are the leading ones, never the trailing ones.
            String projected = codec.movePicX(description, TRAN_REPORT_CAT_DESC_LENGTH);

            assertThat(projected)
                    .hasSize(TRAN_REPORT_CAT_DESC_LENGTH)
                    .isEqualTo(description.substring(0, TRAN_REPORT_CAT_DESC_LENGTH))
                    .isEqualTo("Convenience Check Debit Adjus");
            assertThat(projected)
                    .as("right truncation, not left: the tail is discarded")
                    .doesNotEndWith("Reversal");
        }

        @Test
        @DisplayName("a pre-trimmed description would still project correctly, which is why the width "
                + "itself must be asserted")
        void trimmingWouldBeInvisibleToTheProjectionAlone() {
            // This is the assertion that justifies all the others. For any description at or below the
            // report width, projecting a TRIMMED value and projecting the full 50 bytes give different
            // results - the untrimmed one is space-padded out to 29, the trimmed one is not - so the
            // padding is observable. For a description LONGER than the report width the two agree, which
            // is exactly why "the projection looks right" can never stand in for "the sender is 50 bytes
            // wide". Both facts are pinned here so neither can be weakened later.
            String shortDescription = "Refund credit";

            assertThat(codec.movePicX(shortDescription, TRAN_REPORT_CAT_DESC_LENGTH))
                    .as("a trimmed sender pads to the receiver's width")
                    .isEqualTo(shortDescription + " ".repeat(
                            TRAN_REPORT_CAT_DESC_LENGTH - shortDescription.length()));
            assertThat(codec.movePicX(
                    codec.movePicX(shortDescription, TranCategoryRecord.TRAN_CAT_TYPE_DESC_LENGTH),
                    TRAN_REPORT_CAT_DESC_LENGTH))
                    .as("and so does the untrimmed 50-byte sender - identically, hence the width check")
                    .isEqualTo(shortDescription + " ".repeat(
                            TRAN_REPORT_CAT_DESC_LENGTH - shortDescription.length()));
        }

        @ParameterizedTest(name = "[{0}] is {1} characters and survives the move intact")
        @CsvSource({
            "Online purchase authorization, 29",
            "Sales draft credit adjustment, 29",
        })
        @DisplayName("the two shipped 29-character descriptions sit exactly ON the boundary, untruncated")
        void theShippedBoundaryDescriptionsAreNotTruncated(String description, int length) {
            // Measuring all 18 rows of app/data/ASCII/trancatg.txt gives a maximum trimmed length of
            // exactly 29, reached by these two rows and exceeded by none. They are therefore the real
            // boundary case: the widest data the system actually carries fits the report field to the
            // character, with not one byte to spare. A report field of 28 would silently clip both.
            assertThat(description).hasSize(length).hasSize(TRAN_REPORT_CAT_DESC_LENGTH);

            TranCategoryRecord record = TranCategoryRecord.of("04", 2, description, ASCII);

            assertThat(codec.movePicX(record.tranCatTypeDesc(), TRAN_REPORT_CAT_DESC_LENGTH))
                    .as("no character is lost at the boundary")
                    .isEqualTo(description);
        }

        @Test
        @DisplayName("gate G21: a BUILT record's FILLER X(04) at offset 56 is present and space-filled")
        void aBuiltRecordSpaceFillsItsFiller() {
            TranCategoryRecord built =
                    TranCategoryRecord.of("01", 1, "Regular Sales Draft", ASCII);

            // AAP 0.3.7: a FILLER span is emitted as SPACES. It is never an implied gap - omit it and the
            // record is 56 bytes and every downstream offset breaks - and it is never elided on write.
            FieldSpan filler = TranCategoryRecord.FILLER;

            assertThat(filler.offset())
                    .as("2 + 4 + 50 = 56, so the FILLER begins at byte 56")
                    .isEqualTo(TranCategoryRecord.TRAN_TYPE_CD_LENGTH
                            + TranCategoryRecord.TRAN_CAT_CD_LENGTH
                            + TranCategoryRecord.TRAN_CAT_TYPE_DESC_LENGTH)
                    .isEqualTo(56);
            assertThat(filler.endOffsetExclusive())
                    .as("and ends exactly at the record's declared width")
                    .isEqualTo(TranCategoryRecord.RECORD_LENGTH);
            assertThat(filler.kind()).isEqualTo(PictureKind.FILLER);

            assertThat(built.filler())
                    .as("gate G21: four spaces, emitted rather than implied")
                    .isEqualTo("    ")
                    .hasSize(TranCategoryRecord.FILLER_LENGTH);
            assertThat(built.fillerBytes())
                    .as("0x20 four times, asserted as bytes so no charset assumption hides in the check")
                    .containsExactly((byte) 0x20, (byte) 0x20, (byte) 0x20, (byte) 0x20);
            assertThat(built.toImage())
                    .hasSize(TranCategoryRecord.RECORD_LENGTH)
                    .endsWith("    ");
            assertThat(built.toImage().substring(56, 60))
                    .as("read back at its absolute offsets, not by trusting the suffix")
                    .isEqualTo("    ");
        }

        @Test
        @DisplayName("a DECODED row keeps whatever its FILLER held - the shipped rows hold four zeros")
        void aDecodedRecordPreservesTheStoredFiller() {
            // The distinction G21 turns on: a record this module BUILDS space-fills its FILLER, while a
            // record DECODED from the dataset carries the stored bytes through verbatim so the row can be
            // re-emitted byte for byte. Every row of app/data/ASCII/trancatg.txt holds ASCII zeros there,
            // so the two cases are genuinely different and both are pinned.
            String storedRow = "010001" + "Regular Sales Draft"
                    + " ".repeat(TranCategoryRecord.TRAN_CAT_TYPE_DESC_LENGTH
                            - "Regular Sales Draft".length())
                    + "0000";
            assertThat(storedRow).hasSize(TranCategoryRecord.RECORD_LENGTH);

            TranCategoryRecord decoded =
                    TranCategoryRecord.decode(storedRow.getBytes(ASCII), ASCII);

            assertThat(decoded.filler()).isEqualTo("0000");
            assertThat(TranCategoryRecord.of("01", 1, "Regular Sales Draft", ASCII).filler())
                    .as("a built record space-fills where a decoded one preserves")
                    .isNotEqualTo(decoded.filler());
            assertThat(decoded.toImage())
                    .as("and the stored row round-trips byte for byte, FILLER included")
                    .isEqualTo(storedRow);
        }
    }

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
        @DisplayName("practice B7: this suite itself carries no static mutable state, so it is "
                + "order-independent")
        void theSuiteItselfIsOrderIndependent() {
            // The suite is held to the same rule it enforces. Static mutable state shared between test
            // classes is the classic way a suite starts passing only in one execution order - and this
            // file deliberately keys its stand-in backends off the mock instance in an INSTANCE field
            // (`backends`), so every test gets its own dataset and no test can observe another's rows.
            List<Class<?>> everyClassInThisFile = new ArrayList<>();
            everyClassInThisFile.add(TranCategoryRepositoryTest.class);
            Collections.addAll(everyClassInThisFile, TranCategoryRepositoryTest.class.getDeclaredClasses());

            for (Class<?> type : everyClassInThisFile) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) {
                        assertThat(Modifier.isFinal(field.getModifiers()))
                                .as("%s.%s is static in the test suite and must be final",
                                        type.getSimpleName(), field.getName())
                                .isTrue();
                    }
                }
            }

            assertThat(everyClassInThisFile)
                    .as("the nested groups really were enumerated, so this check cannot pass vacuously")
                    .hasSizeGreaterThan(1);
        }

        @Test
        @DisplayName("every instance field is final, so an instance is immutable and shareable")
        void everyInstanceFieldIsFinal() {
            List<String> unsafe = new ArrayList<>();
            for (Field field : TranCategoryRepository.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                if (Modifier.isFinal(field.getModifiers())) {
                    continue;
                }
                // The one exception, and it is the same one TranTypeRepository makes for the same
                // reason: the keyed-read statement cannot be final because composing it needs the
                // record-image column's name, which is discovered by describing the backend and so is
                // not available at construction. It holds an immutable String published through a
                // volatile write, recomposing it yields the same text, and it is per-dataset rather
                // than per-call - so no request state and no lock.
                boolean safelyPublished = Modifier.isVolatile(field.getModifiers())
                        && field.getType() == String.class;
                if (!safelyPublished) {
                    unsafe.add(field.getName() + " (" + field.getType().getSimpleName() + ")");
                }
            }

            assertThat(unsafe)
                    .as("a @Repository is a singleton shared across threads, so a non-final instance "
                            + "field must be a volatile reference to an immutable type and must hold "
                            + "nothing per-call")
                    .isEmpty();
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

        @Test
        @DisplayName("neither production class depends on common.CobolDecimal - CVTRA04Y has no decimal")
        void noFixedPointSeamIsPulledInWhereThereIsNoDecimal() {
            // Rule R4 routes every PIC 9...V... and COMP-3 field through common.CobolDecimal at
            // RoundingMode.DOWN. CVTRA04Y declares NO such field: its four spans are TRAN-TYPE-CD X(02),
            // TRAN-CAT-CD 9(04), TRAN-CAT-TYPE-DESC X(50) and FILLER X(04). A scale-free PIC 9 is an int.
            //
            // So the omission is deliberate, and asserting it is the point: importing the fixed-point
            // seam here would be a strong signal that TRAN-CAT-CD had been modelled as a BigDecimal at
            // some scale, which would render "0001" through a decimal formatter instead of as four zoned
            // digits and compose a key that matches nothing. The failure would be silent - the field is
            // numeric-looking, so a BigDecimal round-trips plausibly - which is exactly why it is pinned
            // rather than left to review. Contrast TranCatBalRepository, whose CVTRA01Y record carries
            // TRAN-CAT-BAL PIC S9(09)V99 and therefore SHOULD depend on CobolDecimal.
            for (String relativePath : List.of(
                    "transaction/TranCategoryRepository.java",
                    "transaction/model/TranCategoryRecord.java")) {
                String source = mainSource(relativePath);

                assertThat(source)
                        .as("%s must not reach for the fixed-point seam: CVTRA04Y has no signed decimal",
                                relativePath)
                        .doesNotContain("CobolDecimal");
                assertThat(source)
                        .as("%s: TRAN-CAT-CD is PIC 9(04) with no V, so it is an int and never a "
                                + "BigDecimal", relativePath)
                        .doesNotContain("BigDecimal");
                assertThat(Pattern.compile("\\b(double|float)\\b").matcher(source).find())
                        .as("%s: gate G22 - no binary approximate numeric primitive", relativePath)
                        .isFalse();
                assertThat(source)
                        .as("%s: gate G24 - no half-rounding mode, because ROUNDED appears zero times "
                                + "in all 28 programs", relativePath)
                        .doesNotContain("HALF_UP")
                        .doesNotContain("HALF_EVEN");
            }
        }

        /**
         * This repository's own source text.
         *
         * @return the file's contents
         */
        private static String repositorySource() {
            return mainSource("transaction/TranCategoryRepository.java");
        }

        /**
         * A main source file of this module, located by walking upwards from the working directory.
         *
         * <p>The walk exists because the working directory a test runs in is not fixed - the module
         * directory under Maven, the repository root under some IDE launchers - so resolving the path
         * relatively would make a correct source pass or fail on the runner's layout rather than on the
         * code. The files are only ever <em>read</em>; nothing here writes to the main tree.
         *
         * @param relativePath the path below {@code com/vsergeychik/carddemo/}
         * @return the file's contents
         * @throws IllegalStateException if the file cannot be located from anywhere on the walk
         */
        private static String mainSource(String relativePath) {
            Path candidate = Path.of("").toAbsolutePath();
            while (candidate != null) {
                Path resolved = candidate.resolve(
                        "app/java/src/main/java/com/vsergeychik/carddemo/" + relativePath);
                if (Files.isRegularFile(resolved)) {
                    try {
                        return Files.readString(resolved, StandardCharsets.UTF_8);
                    } catch (IOException unreadable) {
                        throw new UncheckedIOException("Could not read " + resolved, unreadable);
                    }
                }
                candidate = candidate.getParent();
            }
            throw new IllegalStateException("Could not locate " + relativePath + " at or above "
                    + Path.of("").toAbsolutePath());
        }
    }
}
