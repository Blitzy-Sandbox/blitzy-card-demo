package com.vsergeychik.carddemo.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vsergeychik.carddemo.card.CardXrefRepository.BrowseCursor;
import com.vsergeychik.carddemo.card.CardXrefRepository.ReadResult;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FileStatus.Outcome;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.config.DataSourceConfig;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.ResultSetExtractor;

/**
 * Tests for {@link CardXrefRepository}: the single {@code @Repository} covering the {@code CCXREF}
 * base KSDS <em>and</em> the {@code CXACAIX} alternate-index path over it.
 *
 * <h2>The unit, and the COBOL that is its only oracle</h2>
 *
 * <p>This is a like-for-like COBOL to Java migration, so every expectation below traces to a source
 * line rather than to a preference. The three operations and their call sites are:
 * <ul>
 *   <li><strong>{@link CardXrefRepository#readByCardNumber(String)}</strong> - the base keyed read on
 *       {@code XREF-CARD-NUM}. {@code app/cbl/COTRN02C.cbl:610} {@code READ-CCXREF-FILE}, plus the
 *       batch keyed reads of {@code CBTRN02C}, {@code CBTRN01C}, {@code CBTRN03C} and
 *       {@code CBACT04C}.</li>
 *   <li><strong>{@link CardXrefRepository#readByAccountIdViaAltIndex(long)}</strong> - the
 *       {@code CXACAIX} finder on {@code XREF-ACCT-ID}. {@code app/cbl/COACTVWC.cbl:727}
 *       {@code 9200-GETCARDXREF-BYACCT}, {@code app/cbl/COACTUPC.cbl:3655},
 *       {@code app/cbl/COTRN02C.cbl:577} and {@code app/cbl/COBIL00C.cbl:411}.</li>
 *   <li><strong>{@link CardXrefRepository#openBrowse()}</strong> - the sequential base browse in
 *       card-number order. {@code app/cbl/CBACT03C.cbl:29-32} {@code SELECT}/{@code ASSIGN},
 *       {@code :93} open, {@code :120} read, {@code :138} close; and {@code CBSTM03B}'s {@code 'R'}
 *       read-next and {@code 'K'} keyed-read operations for {@code XREFFILE}.</li>
 * </ul>
 *
 * <h2>Governing standard: no user rules exist, and their absence lowers nothing</h2>
 *
 * <p>{@code review_rules} returns exactly one line - "No user rules provided." - and that single line
 * is the whole document, so <strong>no user rule governs this file</strong>. None has been invented,
 * and the absence is not treated as licence to lower the bar. The binding standard is instead
 * enterprise best practice as the Agent Action Plan codifies it in &sect;0.10.2 B1-B12, plus the
 * absolutes of &sect;0.8.9. The ones this file answers to directly:
 * <ul>
 *   <li><strong>B1</strong> - only the closed test stack {@code app/java/pom.xml} already declares:
 *       JUnit Jupiter, Mockito and AssertJ by way of {@code spring-boot-starter-test}. No dependency
 *       is added and no version literal appears here.</li>
 *   <li><strong>B2</strong> - JUnit 5 throughout; no JUnit 4 and no Spring Boot 4 or Batch 6 API.</li>
 *   <li><strong>B3</strong> - {@code app/cbl}, {@code app/cpy}, {@code app/csd} and {@code app/data}
 *       are <em>cited</em> below as the contract and are never opened, copied or written at runtime.
 *       The only data this suite reads is the classpath fixture the {@code test} profile binds.</li>
 *   <li><strong>B4</strong> - divergences are recorded, not corrected. See the note on consumers
 *       below.</li>
 *   <li><strong>B5</strong> and <strong>B12</strong> - the 36-versus-50 fixture deviation (risk R-F)
 *       is named, asserted and left in place. The fixture is not "fixed" and the record is not
 *       shortened to 36.</li>
 *   <li><strong>B7</strong> - deterministic under {@code mvn -f app/java/pom.xml -B clean verify}: no
 *       watch mode, no randomness, no network, no shared mutable state and therefore no order
 *       dependence between tests.</li>
 *   <li><strong>B8</strong> - the {@link Charset} is always stated explicitly and never inherited from
 *       the platform; imports are explicit with no wildcard; no dataset name is written in Java.</li>
 *   <li><strong>B9</strong> - no static mutable state: every {@code static} member here is
 *       {@code final} and immutable, and the per-test backends live on an instance field.</li>
 *   <li><strong>B11</strong> - offsets and widths are asserted against {@code app/cpy/CVACT03Y.cpy}
 *       explicitly. There is no copybook parser and no reflective deep-equality anywhere.</li>
 * </ul>
 *
 * <h2>Gates this class is responsible for</h2>
 *
 * <p><strong>G45</strong> two access paths over one cluster - never a second repository, table or
 * {@code DataSource}; <strong>G16</strong> with risk <strong>R-F</strong> - the 36 to 50 right-pad;
 * <strong>G47</strong> every {@code FILE STATUS} outcome per call site; <strong>G19</strong> and
 * <strong>G21</strong> - fifty bytes with a space-filled {@code FILLER}; <strong>G46</strong> no
 * dataset-name literal; <strong>G44</strong> nothing schema-shaped; <strong>G49</strong> branch
 * coverage at or above 0.90 for {@code com.vsergeychik.carddemo.card}, the JaCoCo rule being per
 * package; <strong>G52</strong> no wildcard imports; <strong>G53</strong> no static mutable state;
 * <strong>G54</strong> non-interactive under Surefire.
 *
 * <h2>Why plain JUnit with a mocked template, and where Spring does appear</h2>
 *
 * <p>Mostly plain JUnit 5 with a mocked {@link JdbcTemplate} and no application context. That is
 * deliberate and it is what makes the whole class reachable: production connectivity cannot be
 * exercised from this build (risk R-E), so every outcome the COBOL enumerates - {@code '00'},
 * {@code '10'}, {@code '22'}, {@code '23'} and the {@code WHEN OTHER} arm - has to be provable without
 * a backend. Driving all of them is gate G47, and doing it from a plain unit test is what lets the
 * {@code card} package meet gate G49 honestly rather than by exercising a happy path.
 *
 * <p>{@link ConfigurationBinding} is the one exception, and it exists because one property cannot be
 * proved without the real configuration document: that the two dataset names are resolved <em>through
 * the keys</em> {@code carddemo.datasets.CCXREF} and {@code carddemo.datasets.CXACAIX}. It slices
 * Spring as thinly as that property allows - an {@link ApplicationContextRunner} with
 * {@link ConfigDataApplicationContextInitializer} over {@code spring.profiles.active=test}, which is
 * the convention {@code config/DataSourceConfigTest} already established in this module and which is
 * both thinner and more deterministic than a {@code @SpringBootTest} with {@code @ActiveProfiles}.
 * {@code CardDemoApplicationTest} avoids a full context for the same reason.
 *
 * <p>The record images the mock returns are built through {@link CardXrefRecord} rather than typed out,
 * so a change to the copybook layout would break these tests at their source instead of leaving them
 * asserting a stale shape. The one place a literal image appears is the fixture round-trip, which reads
 * the real 36-byte rows of {@code app/data/ASCII/cardxref.txt} from the classpath copy.
 *
 * <p>Every internal import here is either the unit itself, its record model, the status vocabulary, the
 * codec or the two configuration classes the constructor's arguments come from.
 * {@link RecordImageForm} is imported for the same reason and no other: it is the fourth argument of
 * {@link CardXrefRepository}'s own constructor, so the unit cannot be built without naming it. Recorded
 * rather than left implicit, so a reviewer auditing the import list can account for each entry.
 *
 * <h2>Recorded per B4: this type's consumers are mostly not in this package</h2>
 *
 * <p>{@code CVACT03Y} has <strong>twelve</strong> consumers, more than any other copybook in the
 * estate, and only three of them are card programs. The rest are elsewhere: {@code COACTVWC} and
 * {@code COACTUPC} in {@code account}, {@code COTRN02C} and {@code CBTRN01C} / {@code CBTRN02C} /
 * {@code CBTRN03C} in {@code transaction}, {@code COBIL00C} in {@code billing}, and
 * {@code CBACT03C} / {@code CBACT04C} / {@code CBSTM03B} among the batch jobs. So a width or offset
 * error here fails far outside the {@code card} package, which is precisely why the assertions below
 * are explicit about bytes rather than reflective about objects. The class nonetheless stays in
 * {@code card} because that is where the Agent Action Plan assigns it; the mismatch between where the
 * type lives and where it is used is <em>documented here rather than corrected</em>.
 *
 * @see CardXrefRepository
 * @see CardXrefRecord
 * @see FileStatus
 */
@DisplayName("CardXrefRepository - one cluster, two access paths, three operations")
class CardXrefRepositoryTest {

    // =================================================================================================
    // THE BYTE ARITHMETIC, STATED SO A REVIEWER NEED NOT OPEN THE COPYBOOK.
    //
    // app/cpy/CVACT03Y.cpy, "01 CARD-XREF-RECORD", header comment "(RECLN 50)":
    //
    //   offset  length  field            PICTURE     role
    //   ------  ------  ---------------  ----------  ------------------------------------------------
    //        0      16  XREF-CARD-NUM    PIC X(16)   CCXREF base key
    //       16       9  XREF-CUST-ID     PIC 9(09)
    //       25      11  XREF-ACCT-ID     PIC 9(11)   CXACAIX alternate key
    //       36      14  FILLER           PIC X(14)   emitted as spaces, never omitted
    //
    //   16 + 9 + 11 + 14 = 50   <- the authoritative record width
    //   fixture prefix     = 36 <- what app/data/ASCII/cardxref.txt actually holds per row
    //   pad                = 14 <- 50 - 36, the omitted FILLER, restored as spaces (risk R-F)
    //
    // Every one of those five numbers is asserted below against the constants CardXrefRecord derives
    // from the copybook, so the table cannot drift away from the source without a test going red.
    // =================================================================================================

    // =================================================================================================
    // Fixtures. The dataset names here are TEST values and are deliberately not the production ones:
    // this suite proves that names come from configuration, so putting a production name in it would
    // undermine the very thing being proved. The production names appear nowhere in this file at all
    // (gate G46); ConfigurationBinding reads them from the environment instead of restating them.
    // =================================================================================================

    /**
     * The code page every assertion in this class states explicitly.
     *
     * <p>Never {@code Charset.defaultCharset()} and never an overload that omits the charset (practice
     * B8). A fixed-width mainframe record is bytes in a specific code page, so the code page is part of
     * the contract rather than an environmental accident. The module makes the same decision in
     * configuration rather than in Java: {@link CobolCharsetConfig} publishes
     * {@value CobolCharsetConfig#DATASET_CHARSET_BEAN_NAME} from
     * {@value CobolCharsetConfig#DATASET_CHARSET_PROPERTY}, which each profile states on one line -
     * {@code IBM037} in {@code application.yml}, whose bindings address the mainframe datasets, and
     * {@code US-ASCII} in {@code application-test.yml}, whose bindings address the nine text fixtures.
     * {@code US-ASCII} is therefore the right value here, and
     * {@link ConfigurationBinding#theTestProfileNamesTheAsciiCodePageExplicitly()} proves it is the
     * value the shipped profile actually injects rather than one this class assumed.
     */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** A stand-in dataset name for the base cluster. */
    private static final String BASE_DS = "TEST.XREF.BASE";

    /** A stand-in dataset name for the alternate-index path - deliberately a different name. */
    private static final String ALT_DS = "TEST.XREF.ACCTPATH";

    /**
     * The record-image column name the stand-in backend describes.
     *
     * <p>Deliberately a name no copybook contains. The repository discovers this name from result-set
     * metadata rather than assuming one, so a name drawn from nowhere in {@code app/cpy} is what proves
     * the discovery is real and that no copybook field name has been smuggled into a statement.
     */
    private static final String DESCRIBED_COLUMN = "VSAM_RECORD_IMAGE";

    /** {@link #DESCRIBED_COLUMN} as the delimited identifier every statement carries. */
    private static final String IMAGE = "\"" + DESCRIBED_COLUMN + "\"";

    /** The describe the repository issues against the base cluster before composing anything. */
    private static final String BASE_DESCRIBE_SQL =
            "SELECT * FROM \"" + BASE_DS + "\" WHERE 1 = 0";

    /** The describe the repository issues against the alternate-index path. */
    private static final String ALT_DESCRIBE_SQL = "SELECT * FROM \"" + ALT_DS + "\" WHERE 1 = 0";

    /** The keyed read the repository composes for the base cluster: the predicate is in the statement. */
    private static final String BASE_KEYED_SQL = "SELECT * FROM \"" + BASE_DS + "\" WHERE " + IMAGE
            + " LIKE ? ESCAPE '\\' ORDER BY " + IMAGE + " ASC";

    /** The keyed read the repository composes for the alternate-index path. */
    private static final String ALT_KEYED_SQL = "SELECT * FROM \"" + ALT_DS + "\" WHERE " + IMAGE
            + " LIKE ? ESCAPE '\\' ORDER BY " + IMAGE + " ASC";

    /** The sequential browse of the base cluster, in ascending record-image - and so key - order. */
    private static final String BASE_BROWSE_SQL = "SELECT * FROM \"" + BASE_DS + "\" ORDER BY " + IMAGE
            + " ASC";

    /** The advancing browse read: the lowest key strictly above the record already returned. */
    private static final String BASE_BROWSE_AFTER_SQL = "SELECT * FROM \"" + BASE_DS + "\" WHERE "
            + IMAGE + " > ? ORDER BY " + IMAGE + " ASC";

    /** The first row of {@code app/data/ASCII/cardxref.txt}: card 0500024453765740, customer and account 50. */
    private static final String CARD_1 = "0500024453765740";

    /** The second fixture row's card number. */
    private static final String CARD_2 = "0683586198171516";

    /**
     * The codec every assertion uses, over the explicitly named code page.
     *
     * <p>{@code static final} and a reference to a stateless, immutable collaborator, so it introduces
     * no shared mutable state (practice B9, gate G53). It is <strong>the</strong> home of the 36 to 50
     * right-pad: {@link FixedWidthCodec#padToDeclaredWidth(String, int)} is what
     * {@link ByteLevelParity} exercises as a consumer, and no padding is re-implemented in this file.
     */
    private static final FixedWidthCodec CODEC = new FixedWidthCodec(ASCII);

    /**
     * Builds a base-cluster binding: a KSDS, copybook width, no base of its own.
     *
     * @param dsname the dataset name
     * @return the binding
     */
    private static DatasetBinding ksds(String dsname) {
        return new DatasetBinding(dsname, "ksds", false, "FB", null, CardXrefRecord.RECORD_LENGTH,
                "CVACT03Y", null, null, null, null);
    }

    /**
     * Builds an alternate-index path binding over {@code CCXREF}, keyed on {@code XREF-ACCT-ID}.
     *
     * @param dsname the dataset name
     * @return the binding
     */
    private static DatasetBinding aixPath(String dsname) {
        return new DatasetBinding(dsname, "aix-path", false, "FB", null, CardXrefRecord.RECORD_LENGTH,
                "CVACT03Y", null, null, CardXrefRepository.BASE_DD_NAME,
                CardXrefRepository.EXPECTED_ALTERNATE_KEY_FIELD);
    }

    /**
     * Assembles a two-entry catalogue under the two keys the repository looks up.
     *
     * @param base           the {@code CCXREF} binding, or {@code null} to omit the entry
     * @param alternateIndex the {@code CXACAIX} binding, or {@code null} to omit the entry
     * @return the catalogue
     */
    private static DatasetBindings bindings(DatasetBinding base, DatasetBinding alternateIndex) {
        DatasetBindings catalogue = new DatasetBindings();
        if (base != null) {
            catalogue.put(CardXrefRepository.BASE_DD_NAME, base);
        }
        if (alternateIndex != null) {
            catalogue.put(CardXrefRepository.ALTERNATE_INDEX_DD_NAME, alternateIndex);
        }
        return catalogue;
    }

    /**
     * Assembles the catalogue the majority of these tests use: both entries present and both valid.
     *
     * @return a catalogue whose two entries are both valid
     */
    private static DatasetBindings validBindings() {
        return bindings(ksds(BASE_DS), aixPath(ALT_DS));
    }

    /**
     * Builds a repository over a mocked template and a valid catalogue.
     *
     * @param jdbcTemplate the mocked template
     * @return the repository
     */
    private static CardXrefRepository repository(JdbcTemplate jdbcTemplate) {
        return new CardXrefRepository(jdbcTemplate, validBindings(), ASCII, RecordImageForm.CHARACTER);
    }

    /**
     * Renders a record as the fifty-character image a driver would present.
     *
     * @param cardNumber the card number
     * @param customerId the customer id
     * @param accountId  the account id
     * @return the fifty-character image
     */
    private static String image(String cardNumber, int customerId, long accountId) {
        return new String(new CardXrefRecord(cardNumber, customerId, accountId).encode(ASCII), ASCII);
    }

    /**
     * The stand-in backends, one per mocked template, so a test can seed both access paths on one
     * template and a test with three templates gets three independent datasets.
     */
    private final Map<JdbcTemplate, Backend> backends = new IdentityHashMap<>();

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
     * Seeds a dataset with the record images it holds.
     *
     * @param jdbcTemplate the mocked template
     * @param dataset      the dataset name, {@link #BASE_DS} or {@link #ALT_DS}
     * @param rows         the images the dataset holds, or {@code null} to make the template yield no
     *                     result object at all
     */
    private void stubRows(JdbcTemplate jdbcTemplate, String dataset, List<String> rows) {
        if (rows == null) {
            backend(jdbcTemplate).yieldingNothing(dataset);
        } else {
            backend(jdbcTemplate).storing(dataset, rows);
        }
    }

    /**
     * Makes a dataset fail the way one that cannot be reached fails.
     *
     * @param jdbcTemplate the mocked template
     * @param dataset      the dataset name
     */
    private void stubFailure(JdbcTemplate jdbcTemplate, String dataset) {
        backend(jdbcTemplate).failing(dataset);
    }

    /**
     * A stand-in for the deployment backend, sufficient to prove the predicate is really in the
     * statement.
     *
     * <p>This is more than a canned answer, and it has to be. Since the keyed predicate moved out of
     * Java and into the statement, a stub that simply returned a list would no longer test the thing
     * that matters - whether the {@code LIKE} pattern the repository composed actually confines the
     * match to the key's own bytes at the key's own offset. So this evaluates the pattern: it captures
     * the statement and the bound parameter, translates the {@code LIKE} pattern into a regular
     * expression honouring {@code _}, {@code %} and the declared {@code \} escape, and returns the
     * seeded rows that match, up to the row limit the repository asked for.
     *
     * <p>One deliberate departure from a real backend: a {@code null} record image is always returned
     * rather than filtered out. A real {@code LIKE} cannot match {@code NULL}, so the repository's
     * "there is a record and it cannot be read" guard would be unreachable through a faithful backend -
     * yet the guard is right to exist, because a driver returning {@code null} for a column it declared
     * non-null is precisely the misbehaviour it defends against. Returning it keeps that arm honest.
     */
    private static final class Backend {

        /** What each dataset holds, keyed by dataset name. */
        private final Map<String, List<String>> stored = new LinkedHashMap<>();

        /** The datasets that cannot be reached. */
        private final Set<String> failing = new LinkedHashSet<>();

        /** The datasets whose template yields no result object at all. */
        private final Set<String> yieldingNothing = new LinkedHashSet<>();

        /** Every statement sent, in order, so a test can assert what was composed. */
        private final List<String> statementsSent = new ArrayList<>();

        /** Every parameter bound to a keyed read, in order. */
        private final List<String> patternsBound = new ArrayList<>();

        Backend(JdbcTemplate template) {
            when(template.query(anyString(), ArgumentMatchers.<ResultSetExtractor<String>>any()))
                    .thenAnswer(invocation -> describe(invocation.getArgument(0)));
            // One answer for every prepared read, keyed and browse alike, because after the browse became
            // lazy (finding BD-06) both go through a creator and an extractor. Which of the two it is is
            // read off the statement the repository composed, not off the shape of the call.
            when(template.query(any(PreparedStatementCreator.class),
                            ArgumentMatchers.<ResultSetExtractor<Object>>any()))
                    .thenAnswer(this::preparedRead);
        }

        Backend storing(String dataset, List<String> rows) {
            stored.put(dataset, rows);
            return this;
        }

        Backend failing(String dataset) {
            failing.add(dataset);
            return this;
        }

        Backend yieldingNothing(String dataset) {
            yieldingNothing.add(dataset);
            return this;
        }

        List<String> statementsSent() {
            return List.copyOf(statementsSent);
        }

        List<String> patternsBound() {
            return List.copyOf(patternsBound);
        }

        /** Answers the metadata describe: the column name, unless the dataset cannot be reached. */
        private String describe(String sql) {
            statementsSent.add(sql);
            requireReachable(sql);
            return DESCRIBED_COLUMN;
        }

        /**
         * Answers any prepared read: a keyed one by evaluating the composed predicate, a browse one by
         * driving the repository's own extractor over the row it should see.
         *
         * @param invocation the template call
         * @return what that read yields
         * @throws SQLException never; declared because the mocked JDBC methods declare it
         */
        private Object preparedRead(InvocationOnMock invocation) throws SQLException {
            PreparedStatementCreator creator = invocation.getArgument(0);
            Connection connection = mock(Connection.class);
            PreparedStatement prepared = mock(PreparedStatement.class);
            List<String> captured = new ArrayList<>();
            List<String> sql = new ArrayList<>();
            when(connection.prepareStatement(anyString())).thenAnswer(prepare -> {
                sql.add(prepare.getArgument(0));
                return prepared;
            });
            doAnswer(bind -> {
                captured.add(bind.getArgument(1));
                return null;
            }).when(prepared).setString(eq(1), anyString());
            creator.createPreparedStatement(connection);

            String statement = sql.get(0);
            statementsSent.add(statement);
            requireReachable(statement);
            if (yieldingNothing.contains(datasetOf(statement))) {
                return null;
            }
            if (statement.contains("LIKE")) {
                String pattern = captured.get(0);
                patternsBound.add(pattern);
                return keyedMatches(statement, pattern);
            }
            // A browse read. The repository asked for one row - the first, or the first strictly after the
            // image it bound - and it reads that row through its own extractor, so the extractor is what
            // this answers with rather than a fabricated return value.
            String after = captured.isEmpty() ? null : captured.get(0);
            ResultSetExtractor<?> extractor = invocation.getArgument(1);
            List<String> rows = rowsOf(statement);
            int index = nextBrowseRowIndex(rows, after);
            return extractor.extractData(
                    oneRowResultSet(index >= 0, index >= 0 ? rows.get(index) : null));
        }

        /** The rows a keyed pattern selects, up to the limit the repository asks for. */
        private List<String> keyedMatches(String statement, String pattern) {
            Pattern matcher = likeAsRegex(pattern);
            List<String> matches = new ArrayList<>();
            for (String row : rowsOf(statement)) {
                if (matches.size() == DUPLICATE_DETECTION_LIMIT) {
                    break;
                }
                if (row == null || matcher.matcher(row).matches()) {
                    matches.add(row);
                }
            }
            return matches;
        }

        /**
         * Which seeded row a browse read should see: the first, or the first strictly after an image.
         *
         * <p>Ordering by the whole image is ordering by the key, because the key is the leading span and
         * every image is the same width - which is the property the repository's advancing statement
         * relies on. An index rather than the row itself, because a seeded {@code null} is a row that is
         * present and unreadable and has to stay distinguishable from no row at all.
         *
         * @param rows  the seeded rows, in order
         * @param after the image to advance past, or {@code null} for the first read
         * @return the index of the row, or {@code -1} when the browse has run out
         */
        private static int nextBrowseRowIndex(List<String> rows, String after) {
            for (int index = 0; index < rows.size(); index++) {
                String row = rows.get(index);
                if (after == null || (row != null && row.compareTo(after) > 0)) {
                    return index;
                }
            }
            return -1;
        }

        /**
         * A result set carrying at most one row.
         *
         * @param present whether a row is there at all
         * @param image   that row's record image, which may be {@code null} for a row whose column holds
         *                nothing - a row that is present and unreadable
         * @return the stubbed result set
         * @throws SQLException never; declared because the mocked JDBC methods declare it
         */
        private static ResultSet oneRowResultSet(boolean present, String image) throws SQLException {
            ResultSet resultSet = mock(ResultSet.class);
            when(resultSet.next()).thenReturn(present, false);
            when(resultSet.getString(CardXrefRepository.RECORD_IMAGE_COLUMN_INDEX)).thenReturn(image);
            return resultSet;
        }

        private void requireReachable(String sql) {
            if (failing.contains(datasetOf(sql))) {
                throw new DataAccessResourceFailureException("the dataset cannot be reached");
            }
        }

        private List<String> rowsOf(String sql) {
            return stored.getOrDefault(datasetOf(sql), List.of());
        }

        /** Which seeded dataset a statement addresses, read from the delimited identifier it carries. */
        private static String datasetOf(String sql) {
            return sql.contains("\"" + ALT_DS + "\"") ? ALT_DS : BASE_DS;
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

    /** The row limit a keyed read asks for: one more than a unique key can return. */
    private static final int DUPLICATE_DETECTION_LIMIT = 2;

    // =================================================================================================
    // Startup. Everything checkable about the configuration is checked in the constructor, so a
    // misconfiguration fails at context refresh rather than at the first read.
    // =================================================================================================

    @Nested
    @DisplayName("Construction is configuration-bound and refuses to guess")
    class Construction {

        @Test
        @DisplayName("A valid catalogue resolves both dataset names from configuration")
        void aValidCatalogueResolvesBothDatasetNames() {
            CardXrefRepository repository = repository(mock(JdbcTemplate.class));

            assertThat(repository.baseDatasetName()).isEqualTo(BASE_DS);
            assertThat(repository.alternateIndexDatasetName()).isEqualTo(ALT_DS);
        }

        @Test
        @DisplayName("Gate G45: the base and the alternate-index path are DIFFERENT dataset names")
        void theTwoAccessPathsResolveDifferentDatasetNames() {
            CardXrefRepository repository = repository(mock(JdbcTemplate.class));

            assertThat(repository.baseDatasetName())
                    .isNotEqualTo(repository.alternateIndexDatasetName());
        }

        @Test
        @DisplayName("The two CICS FILE names are the eight-byte PIC X(08) literals the programs declare")
        void theCicsFileNamesArePaddedToEightBytes() {
            CardXrefRepository repository = repository(mock(JdbcTemplate.class));

            assertThat(repository.baseFileNameForCics())
                    .isEqualTo("CCXREF  ")
                    .hasSize(CardXrefRepository.CICS_FILE_NAME_LENGTH);
            assertThat(repository.alternateIndexFileNameForCics())
                    .isEqualTo("CXACAIX ")
                    .hasSize(CardXrefRepository.CICS_FILE_NAME_LENGTH);
        }

        @Test
        @DisplayName("A base binding whose record length is not 50 is rejected, naming the copybook")
        void aBaseBindingOfTheWrongWidthIsRejected() {
            DatasetBindings catalogue = bindings(
                    new DatasetBinding(BASE_DS, "ksds", false, "FB", null, 36, "CVACT03Y", null, null, null,
                            null),
                    aixPath(ALT_DS));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new CardXrefRepository(mock(JdbcTemplate.class), catalogue, ASCII, RecordImageForm.CHARACTER))
                    .withMessageContaining(CardXrefRepository.BASE_DD_NAME)
                    .withMessageContaining("CVACT03Y")
                    .withMessageContaining("36");
        }

        @Test
        @DisplayName("An alternate-index binding whose record length is not 50 is rejected too")
        void anAlternateIndexBindingOfTheWrongWidthIsRejected() {
            DatasetBindings catalogue = bindings(ksds(BASE_DS),
                    new DatasetBinding(ALT_DS, "aix-path", false, "FB", null, 50 + 1, "CVACT03Y", null,
                            null,
                            CardXrefRepository.BASE_DD_NAME,
                            CardXrefRepository.EXPECTED_ALTERNATE_KEY_FIELD));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new CardXrefRepository(mock(JdbcTemplate.class), catalogue, ASCII, RecordImageForm.CHARACTER))
                    .withMessageContaining(CardXrefRepository.ALTERNATE_INDEX_DD_NAME);
        }

        @Test
        @DisplayName("Gate G45 asserted: an alternate-index path that names a different base is rejected")
        void anAlternateIndexOverTheWrongBaseIsRejected() {
            DatasetBindings catalogue = bindings(ksds(BASE_DS),
                    new DatasetBinding(ALT_DS, "aix-path", false, "FB", null,
                            CardXrefRecord.RECORD_LENGTH, "CVACT03Y", null, null, "CARDDAT",
                            CardXrefRepository.EXPECTED_ALTERNATE_KEY_FIELD));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new CardXrefRepository(mock(JdbcTemplate.class), catalogue, ASCII, RecordImageForm.CHARACTER))
                    .withMessageContaining("CARDDAT")
                    .withMessageContaining("gate G45");
        }

        @Test
        @DisplayName("Gate G45 asserted: an alternate-index path keyed on the wrong field is rejected")
        void anAlternateIndexOnTheWrongKeyIsRejected() {
            DatasetBindings catalogue = bindings(ksds(BASE_DS),
                    new DatasetBinding(ALT_DS, "aix-path", false, "FB", null,
                            CardXrefRecord.RECORD_LENGTH, "CVACT03Y", null,
                            null,
                            CardXrefRepository.BASE_DD_NAME, CardXrefRecord.XREF_CUST_ID_NAME));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new CardXrefRepository(mock(JdbcTemplate.class), catalogue, ASCII, RecordImageForm.CHARACTER))
                    .withMessageContaining(CardXrefRecord.XREF_CUST_ID_NAME)
                    .withMessageContaining(CardXrefRepository.EXPECTED_ALTERNATE_KEY_FIELD);
        }

        @Test
        @DisplayName("A base cluster that claims to be an index over something else is rejected")
        void aBaseThatDeclaresItsOwnBaseIsRejected() {
            DatasetBindings catalogue = bindings(
                    new DatasetBinding(BASE_DS, "ksds", false, "FB", null,
                            CardXrefRecord.RECORD_LENGTH, "CVACT03Y", null, null, "CARDDAT", null),
                    aixPath(ALT_DS));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new CardXrefRepository(mock(JdbcTemplate.class), catalogue, ASCII, RecordImageForm.CHARACTER))
                    .withMessageContaining(CardXrefRepository.BASE_DD_NAME)
                    .withMessageContaining("IS the base cluster");
        }

        @Test
        @DisplayName("An absent dataset name is rejected, and so is a blank one")
        void anAbsentOrBlankDatasetNameIsRejected() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new CardXrefRepository(mock(JdbcTemplate.class),
                            bindings(ksds(null), aixPath(ALT_DS)), ASCII, RecordImageForm.CHARACTER))
                    .withMessageContaining("gate G46");

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new CardXrefRepository(mock(JdbcTemplate.class),
                            bindings(ksds(BASE_DS), aixPath("   ")), ASCII, RecordImageForm.CHARACTER))
                    .withMessageContaining(CardXrefRepository.ALTERNATE_INDEX_DD_NAME);
        }

        @Test
        @DisplayName("A dataset name holding a control character is rejected, naming the position")
        void aDatasetNameWithAControlCharacterIsRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new CardXrefRepository(mock(JdbcTemplate.class),
                            bindings(ksds("TEST.X\nREF"), aixPath(ALT_DS)), ASCII, RecordImageForm.CHARACTER))
                    .withMessageContaining("0-based position 6");
        }

        @Test
        @DisplayName("An unconfigured DD name is reported by the catalogue, not defaulted")
        void anUnconfiguredDdNameIsReported() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new CardXrefRepository(mock(JdbcTemplate.class),
                            bindings(null, aixPath(ALT_DS)), ASCII, RecordImageForm.CHARACTER))
                    .withMessageContaining(CardXrefRepository.BASE_DD_NAME);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new CardXrefRepository(mock(JdbcTemplate.class),
                            bindings(ksds(BASE_DS), null), ASCII, RecordImageForm.CHARACTER))
                    .withMessageContaining(CardXrefRepository.ALTERNATE_INDEX_DD_NAME);
        }

        @Test
        @DisplayName("Every collaborator is required: none is defaulted and none is optional")
        void everyCollaboratorIsRequired() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new CardXrefRepository(null, validBindings(), ASCII, RecordImageForm.CHARACTER));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new CardXrefRepository(mock(JdbcTemplate.class), null, ASCII, RecordImageForm.CHARACTER));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new CardXrefRepository(mock(JdbcTemplate.class), validBindings(),
                            null, RecordImageForm.CHARACTER));
        }

        @Test
        @DisplayName("The declared geometry is carried from the copybook, never restated")
        void theDeclaredGeometryComesFromTheCopybook() {
            assertThat(CardXrefRepository.RECORD_LENGTH).isEqualTo(50);
            assertThat(CardXrefRepository.CARD_NUMBER_KEY_LENGTH).isEqualTo(16);
            assertThat(CardXrefRepository.ACCOUNT_ID_KEY_LENGTH).isEqualTo(11);
            assertThat(CardXrefRepository.EXPECTED_ALTERNATE_KEY_FIELD).isEqualTo("XREF-ACCT-ID");
            // The offset table stated at the head of this class, enforced field by field so the comment
            // cannot drift from app/cpy/CVACT03Y.cpy: X(16) at 0, 9(09) at 16, 9(11) at 25,
            // FILLER X(14) at 36. Names included, because a renamed field breaks field-for-field
            // diffing for all twelve consumers even when every offset is still right (AAP 0.8.9).
            assertThat(CardXrefRecord.XREF_CARD_NUM_NAME).isEqualTo("XREF-CARD-NUM");
            assertThat(CardXrefRecord.XREF_CARD_NUM_OFFSET).isZero();
            assertThat(CardXrefRecord.XREF_CARD_NUM_LENGTH).isEqualTo(16);
            assertThat(CardXrefRecord.XREF_CUST_ID_NAME).isEqualTo("XREF-CUST-ID");
            assertThat(CardXrefRecord.XREF_CUST_ID_OFFSET).isEqualTo(16);
            assertThat(CardXrefRecord.XREF_CUST_ID_LENGTH).isEqualTo(9);
            assertThat(CardXrefRecord.XREF_ACCT_ID_NAME).isEqualTo("XREF-ACCT-ID");
            assertThat(CardXrefRecord.XREF_ACCT_ID_OFFSET).isEqualTo(25);
            assertThat(CardXrefRecord.XREF_ACCT_ID_LENGTH).isEqualTo(11);
            assertThat(CardXrefRecord.FILLER_OFFSET).isEqualTo(36);
            assertThat(CardXrefRecord.FILLER_LENGTH).isEqualTo(14);
            // 16 + 9 + 11 + 14 = 50, and each field begins where the previous one ends: the two
            // properties that together make decoding by absolute offset safe.
            assertThat(CardXrefRecord.XREF_CARD_NUM_LENGTH
                    + CardXrefRecord.XREF_CUST_ID_LENGTH
                    + CardXrefRecord.XREF_ACCT_ID_LENGTH
                    + CardXrefRecord.FILLER_LENGTH)
                    .isEqualTo(CardXrefRepository.RECORD_LENGTH);
            assertThat(CardXrefRecord.XREF_CARD_NUM_OFFSET + CardXrefRecord.XREF_CARD_NUM_LENGTH)
                    .isEqualTo(CardXrefRecord.XREF_CUST_ID_OFFSET);
            assertThat(CardXrefRecord.XREF_CUST_ID_OFFSET + CardXrefRecord.XREF_CUST_ID_LENGTH)
                    .isEqualTo(CardXrefRecord.XREF_ACCT_ID_OFFSET);
            assertThat(CardXrefRecord.XREF_ACCT_ID_OFFSET + CardXrefRecord.XREF_ACCT_ID_LENGTH)
                    .isEqualTo(CardXrefRecord.FILLER_OFFSET);
            // The fixture prefix is exactly the data span, and the pad is exactly the FILLER: 36 + 14.
            // This is risk R-F expressed as arithmetic rather than as prose.
            assertThat(CardXrefRecord.FILLER_OFFSET)
                    .as("the 36-byte fixture row is the record MINUS its FILLER, nothing else")
                    .isEqualTo(CardXrefRepository.RECORD_LENGTH - CardXrefRecord.FILLER_LENGTH);
            assertThat(CardXrefRepository.RECORD_IMAGE_COLUMN_INDEX).isOne();
            assertThat(CardXrefRepository.APPL_RESULT_FATAL).isEqualTo(12);
            assertThat(CardXrefRepository.CICS_RESP2_NOT_APPLICABLE).isZero();
            assertThat(CardXrefRepository.PERMANENT_ERROR_STATUS)
                    .hasSize(FileStatus.STATUS_LENGTH)
                    .startsWith("9");
            assertThat(FileStatus.outcomeOfStatus(CardXrefRepository.PERMANENT_ERROR_STATUS))
                    .isEqualTo(Outcome.OTHER);
        }
    }

    // =================================================================================================
    // The base keyed read - app/cbl/COTRN02C.cbl:609-637 READ-CCXREF-FILE, plus the four batch keyed
    // reads. All three EVALUATE arms, and every I/O path that can reach them.
    // =================================================================================================

    @Nested
    @DisplayName("readByCardNumber - the CCXREF base key, XREF-CARD-NUM at offset 0")
    class BaseKeyedRead {

        @Test
        @DisplayName("WHEN DFHRESP(NORMAL): a matching record comes back with status '00'")
        void aMatchingRecordIsFound() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_DS, List.of(image(CARD_1, 50, 50L), image(CARD_2, 27, 27L)));

            ReadResult result = repository(jdbc).readByCardNumber(CARD_1);

            assertThat(result.isFound()).isTrue();
            assertThat(result.status()).isEqualTo(FileStatus.OK);
            assertThat(result.outcome()).isEqualTo(Outcome.OK);
            assertThat(result.cicsResp()).isEqualTo(FileStatus.NORMAL);
            assertThat(result.cicsResp2()).isEqualTo(CardXrefRepository.CICS_RESP2_NOT_APPLICABLE);
            assertThat(result.ddName()).isEqualTo(CardXrefRepository.BASE_DD_NAME);
            assertThat(result.applResult()).isEqualTo(FileStatus.APPL_AOK);
            assertThat(result.record()).contains(new CardXrefRecord(CARD_1, 50, 50L));
        }

        @Test
        @DisplayName("Gate G45: the base read addresses the BASE dataset, never the alternate path")
        void theBaseReadAddressesTheBaseDataset() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_DS, List.of(image(CARD_1, 50, 50L)));
            // The alternate-index statement is stubbed to a DIFFERENT record. If the base read reached
            // for it, the assertion below would see account 999 instead of 50.
            stubRows(jdbc, ALT_DS, List.of(image(CARD_1, 999, 999L)));

            ReadResult result = repository(jdbc).readByCardNumber(CARD_1);

            assertThat(result.record().orElseThrow().xrefAcctId()).isEqualTo(50L);
        }

        @Test
        @DisplayName("WHEN DFHRESP(NOTFND): an unmatched key is status '23' and is NOT an exception")
        void anUnmatchedKeyIsNotFound() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_DS, List.of(image(CARD_2, 27, 27L)));

            ReadResult result = repository(jdbc).readByCardNumber(CARD_1);

            assertThat(result.isNotFound()).isTrue();
            assertThat(result.status()).isEqualTo(FileStatus.NOT_FOUND);
            assertThat(result.cicsResp()).isEqualTo(FileStatus.NOTFND);
            assertThat(result.record()).isEmpty();
            assertThat(result.applResult()).isEqualTo(CardXrefRepository.APPL_RESULT_FATAL);
        }

        @Test
        @DisplayName("An unmatched key is NOT end of file: a keyed read has not reached the end of anything")
        void anUnmatchedKeyIsNotEndOfFile() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_DS, Collections.emptyList());

            ReadResult result = repository(jdbc).readByCardNumber(CARD_1);

            assertThat(result.isNotFound()).isTrue();
            assertThat(result.isEndOfFile()).isFalse();
            assertThat(result.status()).isNotEqualTo(FileStatus.END_OF_FILE);
        }

        @Test
        @DisplayName("Two records on one base key report DUPREC and hand back the first")
        void aDuplicateBaseKeyReportsDuprec() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_DS, List.of(image(CARD_1, 50, 50L), image(CARD_1, 77, 77L)));

            ReadResult result = repository(jdbc).readByCardNumber(CARD_1);

            assertThat(result.isDuplicate()).isTrue();
            assertThat(result.status()).isEqualTo(FileStatus.DUPLICATE);
            assertThat(result.cicsResp()).isEqualTo(FileStatus.DUPREC);
            assertThat(result.record().orElseThrow().xrefCustId()).isEqualTo(50);
            assertThat(result.applResult()).isEqualTo(CardXrefRepository.APPL_RESULT_FATAL);
        }

        @Test
        @DisplayName("WHEN OTHER: an unreachable dataset is a permanent-error status, never a throw")
        void anUnreachableDatasetIsReportedAsAStatus() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubFailure(jdbc, BASE_DS);

            ReadResult result = repository(jdbc).readByCardNumber(CARD_1);

            assertThat(result.isOther()).isTrue();
            assertThat(result.status()).isEqualTo(CardXrefRepository.PERMANENT_ERROR_STATUS);
            assertThat(result.record()).isEmpty();
            assertThat(result.statusImage()).isEqualTo(
                    FileStatus.toStatusImage(CardXrefRepository.PERMANENT_ERROR_STATUS));

            // BOTH CICS response codes travel to the caller on the WHEN OTHER arm, because both are
            // rendered into observable message text and field-for-field parity diffing compares that
            // text. app/cbl/COACTVWC.cbl:745-746 moves WS-RESP-CD to ERROR-RESP and WS-REAS-CD to
            // ERROR-RESP2 and STRINGs both into WS-RETURN-MSG; app/cbl/COTRN02C.cbl:598 and
            // app/cbl/COBIL00C.cbl:431 DISPLAY 'RESP:' ... 'REAS:' ... So the fields must exist and must
            // hold something definite.
            assertThat(result.cicsResp()).isEqualTo(FileStatus.NOTOPEN);
            assertThat(result.cicsResp2())
                    .as("RESP2 is honestly zero: a read served over JDBC has no CICS secondary reason "
                            + "code, and inventing a plausible one would put a fabricated number into "
                            + "message text that parity diffing compares")
                    .isEqualTo(CardXrefRepository.CICS_RESP2_NOT_APPLICABLE);
            // And the backend's own diagnosis rides along, so an operator can tell an unreachable
            // backend from a missing relation - without the coarse two-character status losing anything.
            assertThat(result.diagnostic())
                    .as("the WHEN OTHER arm carries the reason it was taken")
                    .isPresent();
        }

        @Test
        @DisplayName("A template that yields no result at all is WHEN OTHER, not an empty dataset")
        void aNullResultIsNotAnEmptyDataset() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_DS, null);

            ReadResult result = repository(jdbc).readByCardNumber(CARD_1);

            assertThat(result.isOther()).isTrue();
            assertThat(result.isNotFound()).isFalse();
        }

        @Test
        @DisplayName("A row whose record image is absent is WHEN OTHER, never silently skipped")
        void aNullRowImageIsAnIoDefect() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_DS, Arrays.asList(image(CARD_1, 50, 50L), null));

            ReadResult result = repository(jdbc).readByCardNumber(CARD_1);

            assertThat(result.isOther()).isTrue();
            assertThat(result.status()).isEqualTo(CardXrefRepository.PERMANENT_ERROR_STATUS);
        }

        @Test
        @DisplayName("The key is a PIC X MOVE: a short key is space-padded on the RIGHT")
        void aShortKeyIsSpacePaddedOnTheRight() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            // "0500" occupies the first four bytes and the remaining twelve are the padding a
            // PIC X(16) receiver supplies. A record stored that way must be found by the short key.
            stubRows(jdbc, BASE_DS, List.of(image("0500", 9, 9L)));

            ReadResult result = repository(jdbc).readByCardNumber("0500");

            assertThat(result.isFound()).isTrue();
            assertThat(result.record().orElseThrow().xrefCardNum())
                    .isEqualTo("0500            ")
                    .hasSize(CardXrefRepository.CARD_NUMBER_KEY_LENGTH);
        }

        @Test
        @DisplayName("The key is a PIC X MOVE: an over-long key is truncated on the RIGHT, not the left")
        void anOverLongKeyIsTruncatedOnTheRight() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_DS, List.of(image(CARD_1, 50, 50L)));

            // Seventeen characters: the leading sixteen survive, which is the PIC X rule. Truncating on
            // the left instead would have kept "500024453765740X" and found nothing.
            ReadResult result = repository(jdbc).readByCardNumber(CARD_1 + "X");

            assertThat(result.isFound()).isTrue();
            assertThat(result.record().orElseThrow().xrefCardNum()).isEqualTo(CARD_1);
        }

        @Test
        @DisplayName("An all-spaces key is a legitimate lookup, not a missing argument")
        void anEmptyKeyLooksUpSpaces() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_DS, List.of(image("", 1, 1L)));

            ReadResult result = repository(jdbc).readByCardNumber("");

            assertThat(result.isFound()).isTrue();
            assertThat(result.record().orElseThrow().xrefCardNum()).isEqualTo(" ".repeat(16));
        }

        @Test
        @DisplayName("An absent key is a defect in the caller, not a NOTFND outcome")
        void anAbsentKeyIsRejected() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> repository(jdbc).readByCardNumber(null))
                    .withMessageContaining(CardXrefRecord.XREF_CARD_NUM_NAME);
        }

        @Test
        @DisplayName("Gate G19 / risk R-F: a 36-byte row is rejected, not quietly accepted")
        void aShortRowIsRejected() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            // Exactly what app/data/ASCII/cardxref.txt holds: the three fields and no trailing FILLER.
            stubRows(jdbc, BASE_DS, List.of(CARD_1 + "000000050" + "00000000050"));

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> repository(jdbc).readByCardNumber(CARD_1))
                    .withMessageContaining("padToDeclaredWidth");
        }

        @Test
        @DisplayName("A row wider than the copybook is rejected as well")
        void anOverWideRowIsRejected() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_DS, List.of(image(CARD_1, 50, 50L) + " "));

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> repository(jdbc).readByCardNumber(CARD_1));
        }
    }

    // =================================================================================================
    // The alternate-index keyed read - app/cbl/COACTVWC.cbl:723-770, COACTUPC:3655, COTRN02C:576-604
    // and COBIL00C:408-437. Two COBOL views of one eleven-byte span, and they must not diverge.
    // =================================================================================================

    @Nested
    @DisplayName("readByAccountIdViaAltIndex - the CXACAIX key, XREF-ACCT-ID at offset 25")
    class AlternateIndexKeyedRead {

        @Test
        @DisplayName("WHEN DFHRESP(NORMAL): the record comes back and carries the two fields COACTVWC moves")
        void aMatchingRecordCarriesTheFieldsTheCallerMoves() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, ALT_DS, List.of(image(CARD_1, 50, 50L), image(CARD_2, 27, 27L)));

            ReadResult result = repository(jdbc).readByAccountIdViaAltIndex(27L);

            assertThat(result.isFound()).isTrue();
            assertThat(result.ddName()).isEqualTo(CardXrefRepository.ALTERNATE_INDEX_DD_NAME);
            assertThat(result.cicsResp()).isEqualTo(FileStatus.NORMAL);
            // app/cbl/COACTVWC.cbl:739-740 - MOVE XREF-CUST-ID TO CDEMO-CUST-ID and
            //                                MOVE XREF-CARD-NUM TO CDEMO-CARD-NUM.
            assertThat(result.record().orElseThrow().xrefCustId()).isEqualTo(27);
            assertThat(result.record().orElseThrow().xrefCardNum()).isEqualTo(CARD_2);
        }

        @Test
        @DisplayName("Gate G45: the alternate read addresses the PATH dataset, never the base")
        void theAlternateReadAddressesThePathDataset() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_DS, List.of(image(CARD_1, 111, 50L)));
            stubRows(jdbc, ALT_DS, List.of(image(CARD_1, 222, 50L)));

            ReadResult result = repository(jdbc).readByAccountIdViaAltIndex(50L);

            assertThat(result.record().orElseThrow().xrefCustId()).isEqualTo(222);
        }

        @Test
        @DisplayName("The key is a PIC 9 MOVE: it is zero-filled on the LEFT, so account 50 is 00000000050")
        void theKeyIsZeroFilledOnTheLeft() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, ALT_DS, List.of(image(CARD_1, 50, 50L)));

            ReadResult result = repository(jdbc).readByAccountIdViaAltIndex(50L);

            assertThat(result.isFound()).isTrue();
            // The stored span really is left-zero-filled; the match proves the key image was too.
            assertThat(image(CARD_1, 50, 50L)
                    .substring(CardXrefRecord.XREF_ACCT_ID_OFFSET,
                            CardXrefRecord.XREF_ACCT_ID_OFFSET + CardXrefRecord.XREF_ACCT_ID_LENGTH))
                    .isEqualTo("00000000050");
        }

        @Test
        @DisplayName("The two COBOL views of the key are identical on the wire")
        void bothKeyViewsProduceTheSameResult() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, ALT_DS, List.of(image(CARD_1, 50, 50L)));
            CardXrefRepository repository = repository(jdbc);

            // COTRN02C:582 and COBIL00C:414 pass the PIC 9(11) field; COACTVWC:729 and COACTUPC:3655
            // pass its PIC X(11) REDEFINES. One span, two pictures, one outcome.
            ReadResult numericView = repository.readByAccountIdViaAltIndex(50L);
            ReadResult alphanumericView = repository.readByAccountIdViaAltIndex("00000000050");

            assertThat(alphanumericView).isEqualTo(numericView);
            assertThat(alphanumericView.isFound()).isTrue();
        }

        @Test
        @DisplayName("The alphanumeric view is left-zero-filled too, so a short image still matches")
        void theAlphanumericViewIsLeftZeroFilled() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, ALT_DS, List.of(image(CARD_1, 50, 50L)));

            assertThat(repository(jdbc).readByAccountIdViaAltIndex("50").isFound()).isTrue();
        }

        @Test
        @DisplayName("The alphanumeric view keeps the LOW-order digits when the image is too long")
        void theAlphanumericViewTruncatesOnTheLeft() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, ALT_DS, List.of(image(CARD_1, 50, 50L)));

            // Twelve digits: the leading 9 is discarded and 00000000050 survives, which is the PIC 9
            // rule and the opposite of the PIC X rule applied to the base key.
            ReadResult result = repository(jdbc).readByAccountIdViaAltIndex("900000000050");

            assertThat(result.isFound()).isTrue();
            assertThat(result.record().orElseThrow().xrefAcctId()).isEqualTo(50L);
        }

        @Test
        @DisplayName("A non-digit key image is rejected: the span it stands for is a PIC 9(11)")
        void aNonDigitKeyImageIsRejected() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> repository(jdbc).readByAccountIdViaAltIndex("0000000005A"));
        }

        @Test
        @DisplayName("An absent key image is a defect in the caller")
        void anAbsentKeyImageIsRejected() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> repository(jdbc).readByAccountIdViaAltIndex((String) null))
                    .withMessageContaining(CardXrefRecord.XREF_ACCT_ID_NAME);
        }

        @Test
        @DisplayName("A negative account id has no unsigned representation and is rejected")
        void aNegativeAccountIdIsRejected() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> repository(jdbc).readByAccountIdViaAltIndex(-1L));
        }

        @Test
        @DisplayName("WHEN DFHRESP(NOTFND): COACTVWC's DID-NOT-FIND-ACCT-IN-CARDXREF branch")
        void anUnmatchedAccountIsNotFound() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, ALT_DS, List.of(image(CARD_1, 50, 50L)));

            ReadResult result = repository(jdbc).readByAccountIdViaAltIndex(99999999999L);

            assertThat(result.isNotFound()).isTrue();
            assertThat(result.cicsResp()).isEqualTo(FileStatus.NOTFND);
            assertThat(result.ddName()).isEqualTo(CardXrefRepository.ALTERNATE_INDEX_DD_NAME);
        }

        @Test
        @DisplayName("A non-unique alternate key reports DUPKEY - not DUPREC - and returns the first")
        void aDuplicateAlternateKeyReportsDupkey() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            // One account holding two cards: legitimate, and exactly the DUPKEY condition a CICS READ
            // through a path over a non-unique alternate index raises. Ordered by record image, so the
            // lower card number is first.
            stubRows(jdbc, ALT_DS, List.of(image(CARD_1, 50, 50L), image(CARD_2, 50, 50L)));

            ReadResult result = repository(jdbc).readByAccountIdViaAltIndex(50L);

            assertThat(result.isDuplicate()).isTrue();
            assertThat(result.status()).isEqualTo(FileStatus.DUPLICATE);
            assertThat(result.cicsResp())
                    .isEqualTo(FileStatus.DUPKEY)
                    .isNotEqualTo(FileStatus.DUPREC);
            assertThat(result.record().orElseThrow().xrefCardNum()).isEqualTo(CARD_1);
        }

        @Test
        @DisplayName("WHEN OTHER: an unreachable path, a null result and an absent row image")
        void everyIoFailurePathIsReportedAsOther() {
            JdbcTemplate unreachable = mock(JdbcTemplate.class);
            stubFailure(unreachable, ALT_DS);
            assertThat(repository(unreachable).readByAccountIdViaAltIndex(50L).isOther()).isTrue();

            JdbcTemplate nullResult = mock(JdbcTemplate.class);
            stubRows(nullResult, ALT_DS, null);
            assertThat(repository(nullResult).readByAccountIdViaAltIndex(50L).isOther()).isTrue();

            JdbcTemplate nullRow = mock(JdbcTemplate.class);
            stubRows(nullRow, ALT_DS, Collections.singletonList(null));
            ReadResult result = repository(nullRow).readByAccountIdViaAltIndex(50L);
            assertThat(result.isOther()).isTrue();
            assertThat(result.cicsResp()).isEqualTo(FileStatus.NOTOPEN);
        }

        @Test
        @DisplayName("The widest representable account id round-trips through the key")
        void theWidestAccountIdIsRepresentable() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            long widest = CardXrefRecord.XREF_ACCT_ID_MAX_VALUE;
            stubRows(jdbc, ALT_DS, List.of(image(CARD_1, 50, widest)));

            ReadResult result = repository(jdbc).readByAccountIdViaAltIndex(widest);

            assertThat(result.isFound()).isTrue();
            assertThat(result.record().orElseThrow().xrefAcctId()).isEqualTo(widest);
        }
    }

    // =================================================================================================
    // The sequential browse - app/cbl/CBACT03C.cbl:118-134 open, :92-116 get-next, :136 close, driving
    // the whole program's loop at :74. Also CBSTM03B's 2000-XREFFILE-PROC open/read/close dispatch.
    // =================================================================================================

    @Nested
    @DisplayName("openBrowse - the CBACT03C sequential pass, in XREF-CARD-NUM order")
    class SequentialBrowse {

        @Test
        @DisplayName("A successful OPEN INPUT reports '00' and positions at the first record")
        void aSuccessfulOpenPositionsAtTheStart() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_DS, List.of(image(CARD_1, 50, 50L)));
            CardXrefRepository repository = repository(jdbc);

            try (BrowseCursor cursor = repository.openBrowse()) {
                assertThat(cursor.openStatus()).isEqualTo(FileStatus.OK);
                assertThat(cursor.openOutcome()).isEqualTo(Outcome.OK);
                assertThat(cursor.openApplResult()).isEqualTo(FileStatus.APPL_AOK);
                assertThat(cursor.isOpen()).isTrue();
                assertThat(cursor.position()).isZero();
                assertThat(cursor.datasetName()).isEqualTo(BASE_DS);
            }
        }

        @Test
        @DisplayName("The whole CBACT03C loop: every record, in order, then end of file")
        void theWholeLoopReadsEveryRecordThenReportsEndOfFile() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_DS, List.of(image(CARD_1, 50, 50L), image(CARD_2, 27, 27L)));
            List<String> displayed = new ArrayList<>();

            try (BrowseCursor cursor = repository(jdbc).openBrowse()) {
                for (ReadResult next = cursor.readNext(); !next.isEndOfFile(); next = cursor.readNext()) {
                    assertThat(next.isFound()).isTrue();
                    assertThat(next.ddName()).isEqualTo(CardXrefRepository.BASE_DD_NAME);
                    displayed.add(next.record().orElseThrow().xrefCardNum());
                }
                assertThat(cursor.position()).isEqualTo(2);
            }

            assertThat(displayed).containsExactly(CARD_1, CARD_2);
        }

        @Test
        @DisplayName("End of file is status '10', APPL-RESULT 16, and is reported repeatedly")
        void endOfFileIsIdempotent() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_DS, Collections.emptyList());

            try (BrowseCursor cursor = repository(jdbc).openBrowse()) {
                ReadResult first = cursor.readNext();
                ReadResult second = cursor.readNext();

                assertThat(first.isEndOfFile()).isTrue();
                assertThat(first.status()).isEqualTo(FileStatus.END_OF_FILE);
                assertThat(first.cicsResp()).isEqualTo(FileStatus.ENDFILE);
                assertThat(first.applResult()).isEqualTo(FileStatus.APPL_EOF);
                assertThat(first.record()).isEmpty();
                assertThat(second).isEqualTo(first);
            }
        }

        @Test
        @DisplayName("ERROR OPENING XREFFILE: a failed open reports 12 and stays unusable")
        void aFailedOpenIsReportedAndStaysUnusable() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubFailure(jdbc, BASE_DS);

            try (BrowseCursor cursor = repository(jdbc).openBrowse()) {
                assertThat(cursor.openStatus()).isEqualTo(CardXrefRepository.PERMANENT_ERROR_STATUS);
                assertThat(cursor.openOutcome()).isEqualTo(Outcome.OTHER);
                assertThat(cursor.openApplResult()).isEqualTo(CardXrefRepository.APPL_RESULT_FATAL);
                assertThat(cursor.isOpen()).isFalse();

                // A caller that ignored openStatus still cannot mistake an unreachable dataset for an
                // empty one: the open's own status comes back, not a fresh end of file.
                ReadResult next = cursor.readNext();
                assertThat(next.isOther()).isTrue();
                assertThat(next.isEndOfFile()).isFalse();
                assertThat(next.status()).isEqualTo(CardXrefRepository.PERMANENT_ERROR_STATUS);
            }
        }

        @Test
        @DisplayName("The open transfers no row, and each read transfers exactly one")
        void theOpenTransfersNoRowAndEachReadTransfersOne() {
            // Finding BD-06 stated as an observable fact. The open used to fetch the entire cross
            // reference; it now describes the dataset and stops, and the rows arrive one per read - which
            // is what makes a later row's failure the read's failure and keeps a browse of a large dataset
            // from materialising it.
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            Backend backend = backend(jdbc);
            backend.storing(BASE_DS, List.of(image(CARD_1, 50, 50L), image(CARD_2, 27, 27L)));

            try (BrowseCursor cursor = repository(jdbc).openBrowse()) {
                assertThat(cursor.openStatus()).isEqualTo(FileStatus.OK);
                assertThat(backend.statementsSent())
                        .as("the open describes the dataset and transfers no row")
                        .containsExactly(BASE_DESCRIBE_SQL, ALT_DESCRIBE_SQL);

                assertThat(cursor.readNext().record().orElseThrow().xrefCardNum()).isEqualTo(CARD_1);
                assertThat(backend.statementsSent()).containsExactly(
                        BASE_DESCRIBE_SQL, ALT_DESCRIBE_SQL, BASE_BROWSE_SQL);

                assertThat(cursor.readNext().record().orElseThrow().xrefCardNum()).isEqualTo(CARD_2);
                assertThat(backend.statementsSent())
                        .as("every read after the first advances past the record it returned")
                        .containsExactly(BASE_DESCRIBE_SQL, ALT_DESCRIBE_SQL, BASE_BROWSE_SQL,
                                BASE_BROWSE_AFTER_SQL);

                assertThat(cursor.readNext().isEndOfFile()).isTrue();
                assertThat(cursor.readNext().isEndOfFile())
                        .as("the end of the pass is remembered, so it costs no further round trip")
                        .isTrue();
                assertThat(backend.statementsSent()).hasSize(5);
            }
        }

        @Test
        @DisplayName("A template that yields no result at all is an empty read, never a silent end of file")
        void aNullResultIsAnEmptyRead() {
            // The open no longer reads (finding BD-06), so a template that answers nothing cannot make the
            // OPEN fail - and should not: the dataset was described successfully. What it must not do is
            // masquerade as the end of the file, because a browse that stops early and silently loses
            // records with nothing said about it. It is reported as the end of the pass here only because
            // there is no row to return, and the guard that turns a null answer into an empty one is what
            // keeps a NullPointerException out of the cursor.
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_DS, null);

            try (BrowseCursor cursor = repository(jdbc).openBrowse()) {
                assertThat(cursor.openStatus()).isEqualTo(FileStatus.OK);
                assertThat(cursor.isOpen()).isTrue();
                assertThat(cursor.readNext().isEndOfFile()).isTrue();
                assertThat(cursor.position()).isZero();
            }
        }

        @Test
        @DisplayName("An unreachable dataset is the OPEN's failure, and the read reports the open's status")
        void anUnreachableDatasetIsTheOpensFailure() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubFailure(jdbc, BASE_DS);

            try (BrowseCursor cursor = repository(jdbc).openBrowse()) {
                assertThat(cursor.openStatus()).isEqualTo(CardXrefRepository.PERMANENT_ERROR_STATUS);
                assertThat(cursor.isOpen()).isFalse();
                assertThat(cursor.openApplResult()).isEqualTo(CardXrefRepository.APPL_RESULT_FATAL);
                // The read reports the OPEN's own status, so a caller that ignored openStatus() still
                // cannot mistake a dataset it never reached for one that was empty.
                assertThat(cursor.readNext().isOther()).isTrue();
                assertThat(cursor.readNext().status())
                        .isEqualTo(CardXrefRepository.PERMANENT_ERROR_STATUS);
                // And CLOSE of a file that never opened is not a success either - CBACT03C tests this
                // status at :139 and abends on 'ERROR CLOSING XREFFILE'.
                assertThat(cursor.closeBrowse()).isEqualTo(CardXrefRepository.PERMANENT_ERROR_STATUS);
                assertThat(cursor.closeApplResult()).isEqualTo(CardXrefRepository.APPL_RESULT_FATAL);
            }
        }

        @Test
        @DisplayName("A read that the backend refuses is the READ's own failure, and does not advance")
        void aRefusedReadIsTheReadsOwnFailure() {
            // The heart of finding BD-06: a failure on the fourth-thousandth row used to be reported as a
            // failure to OPEN, because the open was what read all four thousand. The dataset opens, the
            // first read succeeds, and the failure belongs to the read that met it.
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            Backend backend = backend(jdbc);
            backend.storing(BASE_DS, List.of(image(CARD_1, 50, 50L), image(CARD_2, 27, 27L)));
            BrowseCursor cursor = repository(jdbc).openBrowse();

            assertThat(cursor.openStatus()).isEqualTo(FileStatus.OK);
            assertThat(cursor.readNext().isFound()).isTrue();
            assertThat(cursor.position()).isEqualTo(1);

            backend.failing(BASE_DS);
            ReadResult refused = cursor.readNext();

            assertThat(refused.isOther()).isTrue();
            assertThat(refused.isEndOfFile()).as("a refused read is not an end of file").isFalse();
            assertThat(refused.status()).isEqualTo(CardXrefRepository.PERMANENT_ERROR_STATUS);
            assertThat(refused.diagnostic()).isPresent();
            assertThat(cursor.position()).as("a refused read does not advance the position")
                    .isEqualTo(1);
            cursor.closeBrowse();
        }

        @Test
        @DisplayName("A row whose record image is absent is WHEN OTHER, and the pass stops rather than "
                + "re-reading it for ever")
        void anAbsentRowImageStopsThePass() {
            // A row that is present and unreadable. It is emphatically not an end of file - reporting it
            // as one would lose every record after it with nothing said - and the pass cannot advance past
            // it either, because what a lazy browse advances by is the image it does not have. So the read
            // reports the failure and the pass ends there, which is also where CBACT03C ends: :100-107
            // displays 'ERROR READING XREFFILE', moves 12 into APPL-RESULT and abends.
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_DS, Arrays.asList(null, image(CARD_2, 27, 27L)));

            try (BrowseCursor cursor = repository(jdbc).openBrowse()) {
                ReadResult broken = cursor.readNext();

                assertThat(broken.isOther()).isTrue();
                assertThat(broken.isEndOfFile()).isFalse();
                assertThat(broken.status()).isEqualTo(CardXrefRepository.PERMANENT_ERROR_STATUS);
                assertThat(cursor.position()).as("an unreadable row is not a record returned").isZero();

                // Repeating the read reports the end of the pass rather than the same broken row again.
                assertThat(cursor.readNext().isEndOfFile()).isTrue();
            }
        }

        @Test
        @DisplayName("CLOSE reports '00', is idempotent, and leaves the cursor unusable")
        void closeIsIdempotentAndFinal() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_DS, List.of(image(CARD_1, 50, 50L)));

            BrowseCursor cursor = repository(jdbc).openBrowse();
            assertThat(cursor.closeBrowse()).isEqualTo(FileStatus.OK);
            assertThat(cursor.closeApplResult()).isEqualTo(FileStatus.APPL_AOK);
            assertThat(cursor.closeBrowse()).isEqualTo(FileStatus.OK);
            assertThat(cursor.closeApplResult())
                    .as("closing twice reports the same outcome, so a caller's guard reads the same")
                    .isEqualTo(FileStatus.APPL_AOK);
            assertThat(cursor.isOpen()).isFalse();
        }

        @Test
        @DisplayName("Reading a closed pass is a defect in the caller, not a file status")
        void readingAClosedCursorThrows() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_DS, List.of(image(CARD_1, 50, 50L)));

            BrowseCursor cursor = repository(jdbc).openBrowse();
            cursor.close();

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(cursor::readNext)
                    .withMessageContaining(BASE_DS);
        }

        @Test
        @DisplayName("try-with-resources and an explicit close in the same block are both safe")
        void tryWithResourcesToleratesAnExplicitClose() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_DS, List.of(image(CARD_1, 50, 50L)));

            try (BrowseCursor cursor = repository(jdbc).openBrowse()) {
                assertThat(cursor.readNext().isFound()).isTrue();
                assertThat(cursor.closeBrowse()).isEqualTo(FileStatus.OK);
            }
        }

        @Test
        @DisplayName("Two passes are independent: a @Repository singleton holds no browse position")
        void twoPassesAreIndependent() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_DS, List.of(image(CARD_1, 50, 50L), image(CARD_2, 27, 27L)));
            CardXrefRepository repository = repository(jdbc);

            try (BrowseCursor first = repository.openBrowse();
                    BrowseCursor second = repository.openBrowse()) {
                first.readNext();
                first.readNext();

                assertThat(first.position()).isEqualTo(2);
                assertThat(second.position()).isZero();
                assertThat(second.readNext().record().orElseThrow().xrefCardNum()).isEqualTo(CARD_1);
            }
        }

        @Test
        @DisplayName("Gate G19: a malformed row is raised by the readNext that reaches it, not by the open")
        void aMalformedRowIsRaisedWhenItIsReached() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_DS,
                    List.of(image(CARD_1, 50, 50L), CARD_2 + "000000027" + "00000000027"));

            try (BrowseCursor cursor = repository(jdbc).openBrowse()) {
                // The open itself decodes nothing, so it succeeds.
                assertThat(cursor.openStatus()).isEqualTo(FileStatus.OK);
                assertThat(cursor.readNext().isFound()).isTrue();

                assertThatExceptionOfType(IllegalArgumentException.class)
                        .isThrownBy(cursor::readNext)
                        .withMessageContaining("padToDeclaredWidth");
            }
        }

        @Test
        @DisplayName("A pass reads the base cluster, never the alternate-index path")
        void aPassReadsTheBaseCluster() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_DS, List.of(image(CARD_1, 111, 50L)));
            stubRows(jdbc, ALT_DS, List.of(image(CARD_1, 222, 50L)));

            try (BrowseCursor cursor = repository(jdbc).openBrowse()) {
                assertThat(cursor.readNext().record().orElseThrow().xrefCustId()).isEqualTo(111);
            }
        }

        @Test
        @DisplayName("Gate G47: a sequential pass cannot report DUPLICATE '22' - and that is asserted, "
                + "not assumed")
        void aSequentialPassNeverReportsADuplicate() {
            // WHY THIS OUTCOME IS UNREACHABLE HERE RATHER THAN MERELY UNTESTED. '22' is the duplicate
            // condition, and a duplicate is a statement about a KEY: CICS raises DUPREC when an added
            // base key already exists, and DUPKEY when a READ through a path over a non-unique alternate
            // index matches more than one record. A sequential READ supplies no key at all -
            // app/cbl/CBACT03C.cbl:120 is a bare "READ XREFFILE-FILE INTO CARD-XREF-RECORD" under
            // ACCESS MODE IS SEQUENTIAL - so there is nothing for a duplicate to be a duplicate OF.
            // Accordingly CBACT03C's 1000-XREFFILE-GET-NEXT tests exactly three arms, '00', '10' and
            // WHEN OTHER, and never '22'; CBSTM03B's 'R' read-next arm does the same. The Java browse
            // reproduces that three-arm shape, and this test pins it: the branch is absent because the
            // COBOL has no such branch, which is a parity property rather than a coverage gap.
            //
            // Two records sharing one key are seeded to make the point concretely - a keyed read of that
            // key WOULD report DUPREC, and the assertion below shows the browse simply returns both.
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_DS, List.of(image(CARD_1, 50, 50L), image(CARD_1, 77, 77L)));
            List<ReadResult> outcomes = new ArrayList<>();

            try (BrowseCursor cursor = repository(jdbc).openBrowse()) {
                assertThat(cursor.openOutcome()).isEqualTo(Outcome.OK);
                for (ReadResult next = cursor.readNext(); ; next = cursor.readNext()) {
                    outcomes.add(next);
                    if (next.isEndOfFile()) {
                        break;
                    }
                }
                assertThat(cursor.closeBrowse()).isEqualTo(FileStatus.OK);
            }

            // Every outcome a pass produced is one of the three arms the COBOL enumerates for a
            // sequential read, and not one of them is the duplicate arm.
            assertThat(outcomes).isNotEmpty();
            assertThat(outcomes).allSatisfy(outcome -> {
                assertThat(outcome.isDuplicate())
                        .as("a keyless read has no key to duplicate")
                        .isFalse();
                assertThat(outcome.status()).isNotEqualTo(FileStatus.DUPLICATE);
                assertThat(outcome.outcome()).isIn(Outcome.OK, Outcome.END_OF_FILE, Outcome.OTHER);
            });
            // The pass surfaced both same-key records in order and then stopped - no deduplication, no
            // condition raised, which is what a sequential COBOL pass over a corrupted cluster does.
            assertThat(outcomes.get(outcomes.size() - 1).isEndOfFile()).isTrue();
            assertThat(outcomes.subList(0, outcomes.size() - 1))
                    .allSatisfy(outcome -> assertThat(outcome.isFound()).isTrue());

            // And the contrast, so "unreachable for this operation" is demonstrated rather than
            // asserted: the very same two rows, read BY KEY, do report the duplicate condition.
            assertThat(repository(jdbc).readByCardNumber(CARD_1).isDuplicate())
                    .as("'22' is reachable - for a keyed read, which is the operation it belongs to")
                    .isTrue();
        }
    }

    // =================================================================================================
    // Gates G19, G21 and G16: the record is fifty bytes, the FILLER is fourteen spaces, and the
    // real 36-byte fixture is normalised UP by the harness rather than absorbed by production code.
    // =================================================================================================

    @Nested
    @DisplayName("Byte-level parity - the offset audit and the real fixture")
    class ByteLevelParity {

        // =============================================================================================
        // RISK R-F, RECORDED IN FULL. This nested class is where that risk bites, so it is named here
        // rather than left to a commit message (practice B12).
        //
        //   THE DEVIATION.  app/data/ASCII/cardxref.txt is 50 records of 36 bytes each. Eight of the
        //                   nine shipped ASCII fixtures match their copybook width exactly; this is the
        //                   one that does not. It OMITS CVACT03Y's trailing FILLER X(14).
        //
        //   THE AUTHORITY.  50 is the record width, not 36. app/cpy/CVACT03Y.cpy declares four fields
        //                   summing to 50 and its own header says "(RECLN 50)". The fixture is the
        //                   party that is short, and the copybook is the party that is right.
        //
        //   THE FIX.        Rows are RIGHT-PADDED 36 -> 50 with spaces BEFORE any decode or comparison.
        //                   Padding up preserves every declared offset; shortening the record to 36
        //                   would instead move XREF-CUST-ID and XREF-ACCT-ID out of position for all
        //                   twelve consumers of this copybook.
        //
        //   THE OWNER.      The normaliser is FixedWidthCodec.padToDeclaredWidth, and it is DELIBERATELY
        //                   NOT inside CardXrefRecord. The model does not self-heal a short row and this
        //                   class asserts that it does not - see aShortRowIsNotSilentlyUsableAsARecord.
        //                   This suite is therefore a CONSUMER of the normaliser: no padding is
        //                   re-implemented here as a private helper, because a second implementation is
        //                   a second thing to get wrong.
        //
        //   THE HARNESS.    The parity harness applies exactly the same padding before field-for-field
        //                   comparison, driven by carddemo.test.fixtures.cardxref.pad-to: 50 in
        //                   application-test.yml. One rule, stated in configuration, applied in both
        //                   places (gate G16).
        //
        //   WHAT IS FORBIDDEN. Do NOT "fix" the fixture by padding the file on disk: app/data is the
        //                   read-only parity oracle (practice B3) and rewriting it would destroy the
        //                   only evidence of what the legacy system actually ships. Do NOT shorten the
        //                   record, the configured record-length or the codec to 36 (practice B5).
        //                   The deviation is preserved and compensated for, never absorbed.
        // =============================================================================================

        /** Where the fixture rows are copied to on the test classpath. */
        private static final String FIXTURE = "/fixtures/cardxref.txt";

        /**
         * Reads the shipped fixture rows.
         *
         * <p>From the classpath copy, never from {@code app/data/ASCII} on disk (practice B3). The
         * build copies the fixture into {@code target/test-classes}; this suite opens that copy
         * read-only and writes nothing anywhere.
         *
         * @return the fifty rows, exactly as stored - 36 bytes each, unpadded
         * @throws IOException if the classpath resource cannot be read
         */
        private List<String> fixtureRows() throws IOException {
            try (InputStream stream = CardXrefRepositoryTest.class.getResourceAsStream(FIXTURE)) {
                assertThat(stream).as("the fixture must be on the test classpath").isNotNull();
                return new String(stream.readAllBytes(), ASCII).lines().toList();
            }
        }

        /**
         * The fixture rows after the one normalisation the module recognises.
         *
         * <p>Through {@link FixedWidthCodec#padToDeclaredWidth(String, int)} - the codec's own
         * normaliser - so this suite consumes it rather than reproducing it.
         *
         * @return the fifty rows, each exactly {@link CardXrefRepository#RECORD_LENGTH} characters
         * @throws IOException if the classpath resource cannot be read
         */
        private List<String> normalisedFixtureRows() throws IOException {
            List<String> padded = new ArrayList<>();
            for (String row : fixtureRows()) {
                padded.add(CODEC.padToDeclaredWidth(row, CardXrefRepository.RECORD_LENGTH));
            }
            return padded;
        }

        @Test
        @DisplayName("Gates G19 and G21: a round-tripped record is 50 bytes and bytes 36-49 are spaces")
        void aRoundTrippedRecordIsFiftyBytesWithASpaceFilledFiller() {
            byte[] encoded = new CardXrefRecord(CARD_1, 50, 50L).encode(ASCII);

            assertThat(encoded).hasSize(CardXrefRepository.RECORD_LENGTH);
            assertThat(new String(encoded, ASCII).substring(CardXrefRecord.FILLER_OFFSET))
                    .isEqualTo(" ".repeat(CardXrefRecord.FILLER_LENGTH));
        }

        @Test
        @DisplayName("Risk R-F confirmed: every shipped fixture row is 36 bytes, not 50")
        void everyFixtureRowIsThirtySixBytes() throws IOException {
            List<String> rows = fixtureRows();

            // 50 records of 36 bytes: the deviation itself, measured rather than assumed. If a future
            // change padded the file on disk this assertion goes red - which is the intent, because
            // app/data is the read-only oracle and must keep shipping what the legacy system ships.
            assertThat(rows).hasSize(50);
            assertThat(rows).allSatisfy(row -> assertThat(row).hasSize(CardXrefRecord.FILLER_OFFSET));
            assertThat(CardXrefRecord.FILLER_OFFSET)
                    .as("36 is the record's data prefix - the record minus its FILLER X(14)")
                    .isEqualTo(36);
        }

        @Test
        @DisplayName("Gate G16: the pad restores exactly the FILLER as spaces and touches nothing else")
        void theRightPadAddsFourteenSpacesAndChangesNoDataByte() throws IOException {
            List<String> raw = fixtureRows();
            List<String> padded = normalisedFixtureRows();

            assertThat(padded).hasSameSizeAs(raw);
            for (int index = 0; index < padded.size(); index++) {
                String before = raw.get(index);
                String after = padded.get(index);

                // 36 -> 50. The record is exactly its declared width once normalised (gate G19).
                assertThat(after)
                        .as("row %d is the copybook width after normalisation", index + 1)
                        .hasSize(CardXrefRepository.RECORD_LENGTH);
                // Bytes 36..49 - the FILLER X(14) span - are spaces, never zeros and never absent.
                // A FILLER omitted from a codec breaks the total width and every downstream offset,
                // so it is emitted as spaces (gate G21).
                assertThat(after.substring(CardXrefRecord.FILLER_OFFSET))
                        .as("row %d FILLER span is 14 spaces", index + 1)
                        .isEqualTo(" ".repeat(CardXrefRecord.FILLER_LENGTH))
                        .hasSize(CardXrefRecord.FILLER_LENGTH);
                // And the three data fields are untouched: the pad appends, it does not reshape. This
                // is the half of the property that a length check alone would miss.
                assertThat(after.substring(0, CardXrefRecord.FILLER_OFFSET))
                        .as("row %d data prefix survives the pad byte for byte", index + 1)
                        .isEqualTo(before);

                // Field for field, through the decode the repository itself uses.
                CardXrefRecord decoded = CardXrefRecord.decode(after.getBytes(ASCII), ASCII);
                assertThat(decoded.xrefCardNum())
                        .isEqualTo(before.substring(CardXrefRecord.XREF_CARD_NUM_OFFSET,
                                CardXrefRecord.XREF_CUST_ID_OFFSET))
                        .hasSize(CardXrefRecord.XREF_CARD_NUM_LENGTH);
                assertThat(decoded.xrefCustId()).isEqualTo(Integer.parseInt(
                        before.substring(CardXrefRecord.XREF_CUST_ID_OFFSET,
                                CardXrefRecord.XREF_ACCT_ID_OFFSET)));
                assertThat(decoded.xrefAcctId()).isEqualTo(Long.parseLong(
                        before.substring(CardXrefRecord.XREF_ACCT_ID_OFFSET,
                                CardXrefRecord.FILLER_OFFSET)));
            }
        }

        @Test
        @DisplayName("Risk R-F: the MODEL does not self-heal a short row - the codec is the normaliser")
        void aShortRowIsNotSilentlyUsableAsARecord() throws IOException {
            String raw = fixtureRows().get(0);

            // CardXrefRecord decodes by absolute offset and therefore requires the declared width. It
            // deliberately does NOT pad: were it to, a genuinely truncated production row would be
            // silently completed with spaces and read as valid. The responsibility sits one layer up,
            // in FixedWidthCodec, and this assertion is what keeps it there.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a 36-byte image is not a 50-byte record and the model must say so")
                    .isThrownBy(() -> CardXrefRecord.decode(raw.getBytes(ASCII), ASCII))
                    .withMessageContaining("padToDeclaredWidth");

            // The same bytes, normalised by the codec, decode cleanly. One collaboration, two roles.
            assertThat(CardXrefRecord.decode(
                    CODEC.padToDeclaredWidth(raw, CardXrefRepository.RECORD_LENGTH).getBytes(ASCII),
                    ASCII))
                    .isEqualTo(new CardXrefRecord(CARD_1, 50, 50L));
        }

        @Test
        @DisplayName("Gate G16: padded 36 to 50, all fifty fixture rows decode and re-encode byte-identically")
        void theWholeFixtureRoundTripsAfterNormalisation() throws IOException {
            // normalise -> decode -> re-serialise, and every step is 50 bytes wide. The normalisation
            // is the one application-test.yml declares as carddemo.test.fixtures.cardxref.pad-to: 50,
            // performed by the codec and by the harness, never re-implemented here.
            List<String> padded = normalisedFixtureRows();
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_DS, padded);
            List<CardXrefRecord> decoded = new ArrayList<>();

            try (BrowseCursor cursor = repository(jdbc).openBrowse()) {
                for (ReadResult next = cursor.readNext(); !next.isEndOfFile(); next = cursor.readNext()) {
                    decoded.add(next.record().orElseThrow());
                }
            }

            assertThat(decoded).hasSize(50);
            for (int index = 0; index < decoded.size(); index++) {
                byte[] reserialised = decoded.get(index).encode(ASCII);
                assertThat(reserialised)
                        .as("fixture row %d re-serialises at the copybook width", index + 1)
                        .hasSize(CardXrefRepository.RECORD_LENGTH);
                assertThat(new String(reserialised, ASCII))
                        .as("fixture row %d re-encodes byte-identically", index + 1)
                        .isEqualTo(padded.get(index));
            }
            // The first shipped row, field for field: card 0500024453765740, customer 50, account 50.
            assertThat(decoded.get(0)).isEqualTo(new CardXrefRecord(CARD_1, 50, 50L));
        }

        @Test
        @DisplayName("Both keys find the first fixture row, at their two different offsets")
        void bothKeysFindTheFirstFixtureRowFromTheRealData() throws IOException {
            List<String> padded = normalisedFixtureRows();
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_DS, padded);
            stubRows(jdbc, ALT_DS, padded);
            CardXrefRepository repository = repository(jdbc);

            ReadResult byCard = repository.readByCardNumber(CARD_1);
            ReadResult byAccount = repository.readByAccountIdViaAltIndex(50L);

            assertThat(byCard.isFound()).isTrue();
            assertThat(byAccount.isFound()).isTrue();
            // Two access paths, two key offsets - 0 and 25 - and one record.
            assertThat(byAccount.record()).isEqualTo(byCard.record());
            assertThat(byCard.ddName()).isNotEqualTo(byAccount.ddName());

            // ALL FOUR FIELDS OF A REAL FIXTURE ROW, AT THEIR EXACT OFFSETS AND WIDTHS, reached through
            // the base keyed read. The expectations are sliced out of the raw 36-byte fixture line, so
            // this compares the decode against the shipped bytes rather than against a restated literal.
            String rawRow = fixtureRows().get(0);
            CardXrefRecord found = byCard.record().orElseThrow();

            // XREF-CARD-NUM PIC X(16) at offset 0 - sixteen characters, LEADING ZEROS INTACT. Trimming
            // it, or holding it as a number, would lose the leading zero the fixture actually carries
            // and would break every one of this copybook's twelve consumers.
            assertThat(found.xrefCardNum())
                    .isEqualTo(rawRow.substring(CardXrefRecord.XREF_CARD_NUM_OFFSET,
                            CardXrefRecord.XREF_CARD_NUM_OFFSET + CardXrefRecord.XREF_CARD_NUM_LENGTH))
                    .hasSize(16)
                    .startsWith("0");
            // XREF-CUST-ID PIC 9(09) at offset 16 and XREF-ACCT-ID PIC 9(11) at offset 25.
            assertThat(found.xrefCustId()).isEqualTo(50);
            assertThat(found.xrefAcctId()).isEqualTo(50L);
            // FILLER X(14) at offset 36 - present, and spaces.
            String image = new String(found.encode(ASCII), ASCII);
            assertThat(image).hasSize(CardXrefRepository.RECORD_LENGTH);
            assertThat(image.substring(CardXrefRecord.FILLER_OFFSET))
                    .isEqualTo(" ".repeat(CardXrefRecord.FILLER_LENGTH));

            // ZERO-FILLED TO 9 AND 11 ON WRITE. Customer 50 and account 50 are two-digit values and the
            // spans are nine and eleven wide; a PIC 9 receiver fills on the LEFT, so they are stored as
            // 000000050 and
            // 00000000050 - which is exactly how app/data/ASCII/cardxref.txt holds them. Writing them
            // right-padded, or unpadded, would shift every following byte.
            assertThat(image.substring(CardXrefRecord.XREF_CUST_ID_OFFSET,
                    CardXrefRecord.XREF_ACCT_ID_OFFSET))
                    .isEqualTo("000000050")
                    .hasSize(CardXrefRecord.XREF_CUST_ID_LENGTH);
            assertThat(image.substring(CardXrefRecord.XREF_ACCT_ID_OFFSET,
                    CardXrefRecord.FILLER_OFFSET))
                    .isEqualTo("00000000050")
                    .hasSize(CardXrefRecord.XREF_ACCT_ID_LENGTH);
            // The three data spans re-serialise to exactly the bytes the fixture shipped.
            assertThat(image.substring(0, CardXrefRecord.FILLER_OFFSET)).isEqualTo(rawRow);
        }

        @Test
        @DisplayName("Gate G45: the same record, reached both ways, is byte-identical across all 50 bytes")
        void theTwoAccessPathsReturnTheSameFiftyBytes() throws IOException {
            // ONE CLUSTER, TWO ACCESS PATHS. app/csd/CARDDEMO.CSD:37 defines FILE(CCXREF) as the base
            // KSDS, "CARD TO ACCOUNT XREF"; :63 defines FILE(CXACAIX) as the "ALTERNATE INDEX TO CCXREF
            // VIA ACCOUNT KEY". app/jcl/INTCALC.jcl is the proof text: it opens the cross reference
            // TWICE IN ONE STEP - XREFFILE on the base and XREFFIL1 on the AIX path over that same base
            // - and app/cbl/CBACT04C.cbl carries both keys on a single SELECT. So the alternate index is
            // a second key on one dataset, never a second dataset, and never a second repository, table
            // or DataSource.
            List<String> padded = normalisedFixtureRows();
            JdbcTemplate oneTemplate = mock(JdbcTemplate.class);
            stubRows(oneTemplate, BASE_DS, padded);
            stubRows(oneTemplate, ALT_DS, padded);
            // ONE repository instance over ONE JdbcTemplate - hence one DataSource - serves both paths.
            CardXrefRepository oneRepository = repository(oneTemplate);

            ReadResult byCard = oneRepository.readByCardNumber(CARD_1);
            assertThat(byCard.isFound()).isTrue();
            CardXrefRecord viaBaseKey = byCard.record().orElseThrow();

            // The alternate key is taken from the record the BASE read returned, not from a literal, so
            // the two reads are provably about the same record rather than coincidentally about two.
            ReadResult byAccount =
                    oneRepository.readByAccountIdViaAltIndex(viaBaseKey.xrefAcctId());
            assertThat(byAccount.isFound()).isTrue();
            CardXrefRecord viaAlternateKey = byAccount.record().orElseThrow();

            // BYTE-IDENTICAL ACROSS ALL FIFTY BYTES - FILLER included, which is the half a field-by-
            // field comparison of the three data fields would silently skip.
            byte[] fromBase = viaBaseKey.encode(ASCII);
            byte[] fromPath = viaAlternateKey.encode(ASCII);
            assertThat(fromBase).hasSize(CardXrefRepository.RECORD_LENGTH);
            assertThat(fromPath).hasSize(CardXrefRepository.RECORD_LENGTH);
            assertThat(fromPath)
                    .as("the CXACAIX path returns the very bytes the CCXREF base holds")
                    .containsExactly(fromBase);
            assertThat(new String(fromPath, ASCII).substring(CardXrefRecord.FILLER_OFFSET))
                    .isEqualTo(" ".repeat(CardXrefRecord.FILLER_LENGTH));
            // Field for field as well, so a reviewer sees both the whole and the parts.
            assertThat(viaAlternateKey.xrefCardNum()).isEqualTo(viaBaseKey.xrefCardNum());
            assertThat(viaAlternateKey.xrefCustId()).isEqualTo(viaBaseKey.xrefCustId());
            assertThat(viaAlternateKey.xrefAcctId()).isEqualTo(viaBaseKey.xrefAcctId());

            // Two paths, distinguishable only by which key and which configured name each addresses.
            assertThat(byCard.ddName()).isEqualTo(CardXrefRepository.BASE_DD_NAME);
            assertThat(byAccount.ddName()).isEqualTo(CardXrefRepository.ALTERNATE_INDEX_DD_NAME);
            assertThat(CardXrefRepository.CARD_NUMBER_KEY_LENGTH).isEqualTo(16);
            assertThat(CardXrefRepository.ACCOUNT_ID_KEY_LENGTH).isEqualTo(11);
            assertThat(CardXrefRecord.XREF_CARD_NUM_OFFSET)
                    .isNotEqualTo(CardXrefRecord.XREF_ACCT_ID_OFFSET);
        }

        @Test
        @DisplayName("An unpadded fixture row is rejected: production never absorbs the 36-byte form")
        void anUnpaddedFixtureRowIsRejected() throws IOException {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_DS, fixtureRows());

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> repository(jdbc).readByCardNumber(CARD_1))
                    .withMessageContaining("padToDeclaredWidth");
        }
    }

    // =================================================================================================
    // The outcome type's own invariants. A result whose status and meaning disagreed, or that carried a
    // record it should not, would make every caller's guard chain unreliable - so it cannot be built.
    // =================================================================================================

    @Nested
    @DisplayName("ReadResult - a status and its meaning are two views of one fact")
    class ReadResultInvariants {

        /** A record to attach to the outcomes that carry one. */
        private static final CardXrefRecord RECORD = new CardXrefRecord(CARD_1, 50, 50L);

        @Test
        @DisplayName("Each factory produces the status, outcome, RESP and record presence it stands for")
        void eachFactoryIsInternallyConsistent() {
            ReadResult found = ReadResult.found(CardXrefRepository.BASE_DD_NAME, RECORD);
            ReadResult notFound = ReadResult.notFound(CardXrefRepository.BASE_DD_NAME);
            ReadResult endOfFile = ReadResult.endOfFile(CardXrefRepository.BASE_DD_NAME);
            ReadResult duplicate = ReadResult.duplicate(CardXrefRepository.ALTERNATE_INDEX_DD_NAME,
                    RECORD, FileStatus.DUPKEY);
            ReadResult other = ReadResult.other(CardXrefRepository.BASE_DD_NAME,
                    CardXrefRepository.PERMANENT_ERROR_STATUS);

            assertThat(found.status()).isEqualTo(FileStatus.OK);
            assertThat(found.record()).isPresent();
            assertThat(notFound.status()).isEqualTo(FileStatus.NOT_FOUND);
            assertThat(notFound.record()).isEmpty();
            assertThat(endOfFile.status()).isEqualTo(FileStatus.END_OF_FILE);
            assertThat(endOfFile.record()).isEmpty();
            assertThat(duplicate.status()).isEqualTo(FileStatus.DUPLICATE);
            assertThat(duplicate.record()).isPresent();
            assertThat(other.cicsResp()).isEqualTo(FileStatus.NOTOPEN);
            assertThat(other.record()).isEmpty();
        }

        @Test
        @DisplayName("Gate G47: all four enumerated statuses are reachable and mutually exclusive")
        void allFourEnumeratedStatusesAreReachable() {
            List<ReadResult> results = List.of(
                    ReadResult.found(CardXrefRepository.BASE_DD_NAME, RECORD),
                    ReadResult.endOfFile(CardXrefRepository.BASE_DD_NAME),
                    ReadResult.duplicate(CardXrefRepository.BASE_DD_NAME, RECORD, FileStatus.DUPREC),
                    ReadResult.notFound(CardXrefRepository.BASE_DD_NAME));

            assertThat(results).extracting(ReadResult::status)
                    .containsExactly(FileStatus.OK, FileStatus.END_OF_FILE, FileStatus.DUPLICATE,
                            FileStatus.NOT_FOUND);
            // Exactly one predicate answers true for each outcome.
            for (ReadResult result : results) {
                long trueCount = List.of(result.isFound(), result.isEndOfFile(), result.isDuplicate(),
                        result.isNotFound(), result.isOther()).stream().filter(flag -> flag).count();
                assertThat(trueCount).as("%s answers exactly one predicate", result.status()).isOne();
            }
        }

        @Test
        @DisplayName("APPL-RESULT is 0 for '00', 16 for '10' and 12 for everything else")
        void applResultFollowsTheCbact03cEvaluate() {
            assertThat(ReadResult.found(CardXrefRepository.BASE_DD_NAME, RECORD).applResult())
                    .isEqualTo(FileStatus.APPL_AOK);
            assertThat(ReadResult.endOfFile(CardXrefRepository.BASE_DD_NAME).applResult())
                    .isEqualTo(FileStatus.APPL_EOF);
            assertThat(ReadResult.notFound(CardXrefRepository.BASE_DD_NAME).applResult())
                    .isEqualTo(CardXrefRepository.APPL_RESULT_FATAL);
            assertThat(ReadResult.duplicate(CardXrefRepository.BASE_DD_NAME, RECORD, FileStatus.DUPREC)
                    .applResult()).isEqualTo(CardXrefRepository.APPL_RESULT_FATAL);
            assertThat(ReadResult.other(CardXrefRepository.BASE_DD_NAME,
                    CardXrefRepository.PERMANENT_ERROR_STATUS).applResult())
                    .isEqualTo(CardXrefRepository.APPL_RESULT_FATAL);
        }

        @Test
        @DisplayName("A status of the wrong length is rejected: COBOL compares exactly two characters")
        void aStatusOfTheWrongLengthIsRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new ReadResult(CardXrefRepository.BASE_DD_NAME, "000",
                            Outcome.OK, java.util.Optional.of(RECORD), FileStatus.NORMAL, 0, java.util.Optional.empty()))
                    .withMessageContaining("exactly");
        }

        @Test
        @DisplayName("A status and an outcome that disagree cannot be built")
        void aStatusAndOutcomeThatDisagreeAreRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new ReadResult(CardXrefRepository.BASE_DD_NAME, FileStatus.OK,
                            Outcome.NOT_FOUND, java.util.Optional.empty(), FileStatus.NOTFND, 0, java.util.Optional.empty()))
                    .withMessageContaining("does not classify");
        }

        @Test
        @DisplayName("A record present on an outcome that carries none - and absent when it should - is rejected")
        void recordPresenceMustMatchTheOutcome() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new ReadResult(CardXrefRepository.BASE_DD_NAME,
                            FileStatus.NOT_FOUND, Outcome.NOT_FOUND, java.util.Optional.of(RECORD),
                            FileStatus.NOTFND, 0, java.util.Optional.empty()))
                    .withMessageContaining("carries no record");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new ReadResult(CardXrefRepository.BASE_DD_NAME, FileStatus.OK,
                            Outcome.OK, java.util.Optional.empty(), FileStatus.NORMAL, 0, java.util.Optional.empty()))
                    .withMessageContaining("but none was supplied");
        }

        @Test
        @DisplayName("Every component is required: no null escapes the type")
        void everyComponentIsRequired() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new ReadResult(null, FileStatus.OK, Outcome.OK,
                            java.util.Optional.of(RECORD), FileStatus.NORMAL, 0, java.util.Optional.empty()));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new ReadResult(CardXrefRepository.BASE_DD_NAME, null, Outcome.OK,
                            java.util.Optional.of(RECORD), FileStatus.NORMAL, 0, java.util.Optional.empty()));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new ReadResult(CardXrefRepository.BASE_DD_NAME, FileStatus.OK,
                            null, java.util.Optional.of(RECORD), FileStatus.NORMAL, 0, java.util.Optional.empty()));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new ReadResult(CardXrefRepository.BASE_DD_NAME, FileStatus.OK,
                            Outcome.OK, null, FileStatus.NORMAL, 0, java.util.Optional.empty()));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new ReadResult(CardXrefRepository.BASE_DD_NAME, FileStatus.OK,
                            Outcome.OK, java.util.Optional.of(RECORD), FileStatus.NORMAL, 0, null));
        }

        @Test
        @DisplayName("A found or duplicate outcome must actually carry its record")
        void aCarryingOutcomeMustCarryItsRecord() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> ReadResult.found(CardXrefRepository.BASE_DD_NAME, null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> ReadResult.duplicate(CardXrefRepository.BASE_DD_NAME, null,
                            FileStatus.DUPREC));
        }

        @Test
        @DisplayName("A duplicate must name WHICH key duplicated - DUPREC or DUPKEY, nothing else")
        void aDuplicateMustNameWhichKeyDuplicated() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> ReadResult.duplicate(CardXrefRepository.BASE_DD_NAME, RECORD,
                            FileStatus.NORMAL))
                    .withMessageContaining("DUPREC")
                    .withMessageContaining("DUPKEY");
        }

        @Test
        @DisplayName("The WHEN OTHER arm rejects a status that classifies as one of the enumerated four")
        void theOtherArmRejectsAnEnumeratedStatus() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> ReadResult.other(CardXrefRepository.BASE_DD_NAME, FileStatus.OK))
                    .withMessageContaining("does not classify");
        }

        @Test
        @DisplayName("The status renders as the four-character image 9910-DISPLAY-IO-STATUS produces")
        void theStatusRendersAsItsDisplayImage() {
            assertThat(ReadResult.found(CardXrefRepository.BASE_DD_NAME, RECORD).statusImage())
                    .isEqualTo(FileStatus.toStatusImage(FileStatus.OK))
                    .hasSize(FileStatus.STATUS_IMAGE_LENGTH);
        }
    }

    // =================================================================================================
    // Residual risk R-E made testable. The driver is a deployment-time input, so the two decisions that
    // follow from it - no column name anywhere, and a statement composed only from configuration - are
    // asserted rather than merely documented.
    // =================================================================================================

    @Nested
    @DisplayName("Risk R-E - a discovered column name, and statements built only from configuration")
    class DriverIndependence {

        @Test
        @DisplayName("The record image is read by column POSITION 1, and by no column name at all")
        void theRecordImageIsReadByPositionAndNeverByName() throws SQLException {
            // Driven through the browse read's own extractor. Since the browse became lazy (finding
            // BD-06) that is the code that touches a result set, and asserting on the real one is what
            // makes this a test of the repository rather than of a stub.
            ResultSet row = mock(ResultSet.class);
            when(row.next()).thenReturn(true, false);
            when(row.getString(CardXrefRepository.RECORD_IMAGE_COLUMN_INDEX))
                    .thenReturn(image(CARD_1, 50, 50L));

            assertThat(browseExtractorOver(row)).isNotNull();
            verify(row).getString(CardXrefRepository.RECORD_IMAGE_COLUMN_INDEX);
            // The whole point: a dataset carrying no relational metadata is presented as a single
            // record-image column, so the value is fetched by position. The column's NAME is discovered
            // from result-set metadata where a statement has to name it, never written in Java.
            verify(row, never()).getString(anyString());
        }

        @Test
        @DisplayName("An absent column value is surfaced, not swallowed, so the caller can classify it")
        void anAbsentColumnValueIsSurfaced() throws SQLException {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_DS, Arrays.asList((String) null));

            try (BrowseCursor cursor = repository(jdbc).openBrowse()) {
                ReadResult result = cursor.readNext();

                // Surfaced as its own outcome rather than swallowed into an end of file, which is the only
                // way the caller can tell "there are no more records" from "there is one I cannot read".
                assertThat(result.isOther()).isTrue();
                assertThat(result.isEndOfFile()).isFalse();
                assertThat(result.status()).isEqualTo(CardXrefRepository.PERMANENT_ERROR_STATUS);
            }
        }

        @Test
        @DisplayName("A keyed read stops at the duplicate-detection limit and maps each row by position")
        void aKeyedReadStopsAtItsLimitAndMapsByPosition() throws SQLException {
            // Driven through the keyed read's own extractor, over a result set that would hand over more
            // rows than the read asked for. Two properties at once: the limit is honoured in the extractor
            // and not only on the statement, and each row's image is fetched by column POSITION.
            ResultSet rows = mock(ResultSet.class);
            when(rows.next()).thenReturn(true, true, true, false);
            when(rows.getString(CardXrefRepository.RECORD_IMAGE_COLUMN_INDEX))
                    .thenReturn(image(CARD_1, 50, 50L), image(CARD_2, 27, 27L),
                            image(CARD_1, 99, 99L));

            List<Object> extracted = keyedExtractorOver(rows);

            assertThat(extracted)
                    .as("a unique key cannot return more than two rows, so two is all that is fetched")
                    .hasSize(2)
                    .containsExactly(image(CARD_1, 50, 50L), image(CARD_2, 27, 27L));
            verify(rows, never()).getString(anyString());
        }

        @Test
        @DisplayName("A keyed read surfaces a row whose record image is absent rather than dropping it")
        void aKeyedReadSurfacesAnAbsentRowImage() throws SQLException {
            // A real LIKE cannot match NULL, so this is a driver misbehaving - returning nothing for a
            // column it declared. Dropping the row would report a record that exists as absent, so the
            // null is carried out of the mapper and classified by the caller.
            ResultSet rows = mock(ResultSet.class);
            when(rows.next()).thenReturn(true, false);
            when(rows.getString(CardXrefRepository.RECORD_IMAGE_COLUMN_INDEX)).thenReturn(null);

            assertThat(keyedExtractorOver(rows)).containsExactly((Object) null);
        }

        /**
         * Drives the extractor a keyed read supplies, over a stubbed result set.
         *
         * @param rows the result set to drive it with
         * @return the record images it produced
         * @throws SQLException never; declared because the mocked JDBC methods declare it
         */
        @SuppressWarnings("unchecked")
        private List<Object> keyedExtractorOver(ResultSet rows) throws SQLException {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            when(jdbc.query(anyString(), ArgumentMatchers.<ResultSetExtractor<String>>any()))
                    .thenReturn(DESCRIBED_COLUMN);
            ArgumentCaptor<ResultSetExtractor<Object>> extractorCaptor = ArgumentCaptor.captor();
            when(jdbc.query(any(PreparedStatementCreator.class), extractorCaptor.capture()))
                    .thenReturn(null);

            repository(jdbc).readByCardNumber(CARD_1);

            return (List<Object>) extractorCaptor.getValue().extractData(rows);
        }

        /**
         * Drives the extractor the repository's browse read supplies, over a stubbed result set.
         *
         * @param row the result set to drive it with
         * @return whatever the extractor produced
         * @throws SQLException never; declared because the mocked JDBC methods declare it
         */
        private Object browseExtractorOver(ResultSet row) throws SQLException {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            when(jdbc.query(anyString(), ArgumentMatchers.<ResultSetExtractor<String>>any()))
                    .thenReturn(DESCRIBED_COLUMN);
            ArgumentCaptor<ResultSetExtractor<Object>> extractorCaptor = ArgumentCaptor.captor();
            when(jdbc.query(any(PreparedStatementCreator.class), extractorCaptor.capture()))
                    .thenReturn(null);

            repository(jdbc).openBrowse().readNext();

            return extractorCaptor.getValue().extractData(row);
        }

        @Test
        @DisplayName("Every statement is composed from the configured name and the discovered column")
        void bothStatementsComeOnlyFromConfiguration() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            Backend backend = backend(jdbc);
            backend.storing(BASE_DS, List.of(image(CARD_1, 50, 50L)))
                    .storing(ALT_DS, List.of(image(CARD_1, 50, 50L)));
            CardXrefRepository repository = repository(jdbc);

            repository.readByCardNumber(CARD_1);
            repository.readByAccountIdViaAltIndex(50L);
            // The open describes and transfers nothing (finding BD-06), so the browse statement appears
            // when the first READ is issued - which is the point: the open opens and the reads read.
            repository.openBrowse().readNext();

            assertThat(backend.statementsSent()).containsExactly(
                    BASE_DESCRIBE_SQL, ALT_DESCRIBE_SQL, BASE_KEYED_SQL, ALT_KEYED_SQL,
                    BASE_BROWSE_SQL);
            assertThat(backend.statementsSent()).allSatisfy(sql -> assertThat(sql)
                    // The dataset name is a delimited identifier, because a mainframe name carries
                    // periods and would otherwise be parsed as a qualified name.
                    .contains("\"")
                    // No copybook field name reaches a statement: XREF-CARD-NUM and XREF-ACCT-ID name
                    // spans of app/cpy/CVACT03Y.cpy, not columns of anything.
                    .doesNotContain(CardXrefRecord.XREF_CARD_NUM_NAME)
                    .doesNotContain(CardXrefRecord.XREF_ACCT_ID_NAME));
        }

        @Test
        @DisplayName("Gate G45: the two access paths are addressed by their two configured names")
        void eachAccessPathIsAddressedByItsOwnName() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            Backend backend = backend(jdbc);
            backend.storing(BASE_DS, List.of(image(CARD_1, 50, 50L)))
                    .storing(ALT_DS, List.of(image(CARD_1, 50, 50L)));
            CardXrefRepository repository = repository(jdbc);

            repository.readByCardNumber(CARD_1);
            repository.readByAccountIdViaAltIndex(50L);

            assertThat(BASE_KEYED_SQL).contains(BASE_DS).doesNotContain(ALT_DS);
            assertThat(ALT_KEYED_SQL).contains(ALT_DS);
            assertThat(backend.statementsSent()).contains(BASE_KEYED_SQL, ALT_KEYED_SQL);
        }

        @Test
        @DisplayName("The keyed predicate confines the match to the key's own bytes at its own offset")
        void theKeyedPredicateIsConfinedToTheKeySpan() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            Backend backend = backend(jdbc);
            backend.storing(BASE_DS, List.of(image(CARD_1, 50, 50L)))
                    .storing(ALT_DS, List.of(image(CARD_1, 50, 50L)));
            CardXrefRepository repository = repository(jdbc);

            repository.readByCardNumber(CARD_1);
            repository.readByAccountIdViaAltIndex(50L);

            // XREF-CARD-NUM is at offset 0, so its pattern begins with the key and ends with the
            // any-sequence wildcard covering the remaining 34 bytes.
            assertThat(backend.patternsBound().get(0)).isEqualTo(CARD_1 + "%");
            // XREF-ACCT-ID is at offset 25, so 25 single-character wildcards precede the key. That
            // leading run of 25 IS the offset, expressed in SQL - and it is what stops this predicate
            // from matching the card record's account id, which is the same width at offset 16.
            assertThat(backend.patternsBound().get(1))
                    .isEqualTo("_".repeat(CardXrefRecord.XREF_ACCT_ID_OFFSET) + "00000000050%");
        }

        @Test
        @DisplayName("A LIKE metacharacter inside a key is escaped, so it matches only itself")
        void aLikeMetacharacterInAKeyIsEscaped() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            Backend backend = backend(jdbc);
            // A record whose card number really contains a per-cent sign, and one that would be matched
            // by an unescaped '%' but must not be.
            String literal = "50%0000000000000";
            backend.storing(BASE_DS, List.of(image(literal, 50, 50L), image("5099999999999999", 77, 77L)));

            ReadResult result = repository(jdbc).readByCardNumber(literal);

            assertThat(backend.patternsBound().get(0)).isEqualTo("50\\%0000000000000%");
            assertThat(result.isFound())
                    .as("an unescaped '%' would have matched two records and reported DUPREC")
                    .isTrue();
            assertThat(result.record().orElseThrow().xrefCustId()).isEqualTo(50);
        }

        @Test
        @DisplayName("A configured name that is not a well-formed z/OS dataset name is refused")
        void aMalformedDatasetNameIsRefused() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);

            // A quotation mark is not a character a z/OS dataset name admits, so the name is refused
            // rather than quoted into a statement. The grammar is the defence; the delimited rendering
            // that follows it is belt and braces.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new CardXrefRepository(jdbc,
                            bindings(ksds("TEST.\"ODD\".NAME"), aixPath(ALT_DS)), ASCII, RecordImageForm.CHARACTER))
                    .withMessageContaining("well-formed z/OS dataset name");
        }

        @Test
        @DisplayName("Gate G44: nothing this repository composes is schema-shaped - every statement reads")
        void noComposedStatementIsSchemaShaped() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            Backend backend = backend(jdbc);
            backend.storing(BASE_DS, List.of(image(CARD_1, 50, 50L), image(CARD_2, 27, 27L)))
                    .storing(ALT_DS, List.of(image(CARD_1, 50, 50L)));
            CardXrefRepository repository = repository(jdbc);

            // Every operation the unit offers, so every statement it can compose is captured: the two
            // describes, the two keyed reads, the browse open read and the browse advancing read.
            repository.readByCardNumber(CARD_1);
            repository.readByAccountIdViaAltIndex(50L);
            try (BrowseCursor cursor = repository.openBrowse()) {
                cursor.readNext();
                cursor.readNext();
            }

            assertThat(backend.statementsSent()).isNotEmpty();
            assertThat(backend.statementsSent()).allSatisfy(sql -> {
                // The migration reaches the EXISTING cluster exactly as it is. No data-definition
                // statement, no migration script, no table or column creation, no index creation, no
                // mutation of any kind, and no optimistic-locking version column - the COBOL's own
                // 9300-CHECK-CHANGE-IN-REC re-read is how concurrency is expressed in this estate.
                assertThat(sql.toUpperCase(Locale.ROOT)).startsWith("SELECT ");
                assertThat(sql.toUpperCase(Locale.ROOT))
                        .as("no schema-shaped verb may appear in %s", sql)
                        .doesNotContain("CREATE ")
                        .doesNotContain("ALTER ")
                        .doesNotContain("DROP ")
                        .doesNotContain("TRUNCATE ")
                        .doesNotContain("INSERT ")
                        .doesNotContain("UPDATE ")
                        .doesNotContain("DELETE ")
                        .doesNotContain("MERGE ")
                        .doesNotContain("GRANT ")
                        .doesNotContain("INDEX")
                        .doesNotContain("PRIMARY KEY")
                        .doesNotContain("VERSION");
            });
        }
    }

    // =================================================================================================
    // Configuration binding. Gate G46 has a positive form as well as a negative one: it is not enough
    // that no dataset name is WRITTEN here, the names must demonstrably be READ from the two
    // configuration keys. That cannot be shown with a hand-built catalogue, so this one nested class
    // slices Spring - as thinly as the property allows - over the shipped test profile document.
    // =================================================================================================

    @Nested
    @DisplayName("Gate G46 - both names resolved from carddemo.datasets, none written in Java")
    class ConfigurationBinding {

        /** The configuration key that names the {@code CCXREF} base cluster's dataset. */
        private static final String BASE_DSNAME_KEY =
                "carddemo.datasets." + CardXrefRepository.BASE_DD_NAME + ".dsname";

        /** The configuration key that names the {@code CXACAIX} alternate-index path's dataset. */
        private static final String ALTERNATE_DSNAME_KEY =
                "carddemo.datasets." + CardXrefRepository.ALTERNATE_INDEX_DD_NAME + ".dsname";

        /**
         * A slice over the shipped {@code test} profile: the real {@code application.yml} plus
         * {@code application-test.yml}, with nothing supplied inline.
         *
         * <p>An {@link ApplicationContextRunner} with a {@link ConfigDataApplicationContextInitializer}
         * rather than {@code @SpringBootTest} with {@code @ActiveProfiles}, which is the convention
         * {@code config/DataSourceConfigTest} established in this module: it reads the same documents,
         * activates the same profile, starts no web server and no batch infrastructure, and cannot be
         * perturbed by an unrelated bean failing elsewhere in the graph. Naming the profile inline also
         * outranks anything the surrounding JVM exported, so the slice is hermetic (practice B7).
         *
         * <p>Only the two configuration classes this repository's constructor arguments come from are
         * registered, so nothing else can supply a value by accident.
         *
         * @return the runner
         */
        private ApplicationContextRunner shippedTestProfile() {
            return new ApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withUserConfiguration(DataSourceConfig.class, CobolCharsetConfig.class)
                    .withPropertyValues("spring.profiles.active=test");
        }

        @Test
        @DisplayName("Both dataset names come from carddemo.datasets.CCXREF and .CXACAIX, not from Java")
        void bothDatasetNamesAreResolvedFromTheirConfigurationKeys() {
            shippedTestProfile().run(context -> {
                CardXrefRepository repository = new CardXrefRepository(
                        context.getBean(JdbcTemplate.class),
                        context.getBean(DatasetBindings.class),
                        context.getBean(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME, Charset.class),
                        context.getBean(RecordImageForm.class));

                // The expected values are READ from the environment under the two keys rather than
                // written here. That is what makes this assertion a proof of gate G46 instead of a
                // restatement of it: were a name ever hard-coded in Java, this would still pass only
                // while the two agreed, and the moment configuration changed it would go red.
                String configuredBase = context.getEnvironment().getProperty(BASE_DSNAME_KEY);
                String configuredAlternate =
                        context.getEnvironment().getProperty(ALTERNATE_DSNAME_KEY);

                assertThat(configuredBase).as(BASE_DSNAME_KEY).isNotBlank();
                assertThat(configuredAlternate).as(ALTERNATE_DSNAME_KEY).isNotBlank();
                assertThat(repository.baseDatasetName()).isEqualTo(configuredBase);
                assertThat(repository.alternateIndexDatasetName()).isEqualTo(configuredAlternate);
            });
        }

        @Test
        @DisplayName("Gate G45 in the shipped configuration: CXACAIX declares CCXREF as its base")
        void theShippedCatalogueDeclaresThePathOverTheBase() {
            shippedTestProfile().run(context -> {
                DatasetBindings catalogue = context.getBean(DatasetBindings.class);
                DatasetBinding base = catalogue.binding(CardXrefRepository.BASE_DD_NAME);
                DatasetBinding path =
                        catalogue.binding(CardXrefRepository.ALTERNATE_INDEX_DD_NAME);

                // app/csd/CARDDEMO.CSD:63-65, DESCRIPTION(ALTERNATE INDEX TO CCXREF VIA ACCOUNT KEY),
                // and app/jcl/INTCALC.jcl opening the cross reference twice in one step - as XREFFILE on
                // the base and XREFFIL1 on the path - both expressed as configuration, not as prose.
                assertThat(base.base()).as("the base cluster declares no base of its own").isNull();
                assertThat(path.base()).isEqualTo(CardXrefRepository.BASE_DD_NAME);
                assertThat(path.alternateKey())
                        .isEqualTo(CardXrefRepository.EXPECTED_ALTERNATE_KEY_FIELD);
                // One copybook, one width, for both access paths - because they are one dataset.
                assertThat(base.recordLength()).isEqualTo(CardXrefRepository.RECORD_LENGTH);
                assertThat(path.recordLength()).isEqualTo(CardXrefRepository.RECORD_LENGTH);
                assertThat(base.copybook()).isEqualTo(path.copybook());
                // And the 36-byte fixture width is nowhere in the dataset catalogue: risk R-F is
                // compensated for by padding UP at the fixture boundary, never by configuring DOWN here.
                assertThat(base.recordLength()).isNotEqualTo(CardXrefRecord.FILLER_OFFSET);
            });
        }

        @Test
        @DisplayName("The test profile names US-ASCII explicitly; the platform default is never used")
        void theTestProfileNamesTheAsciiCodePageExplicitly() {
            shippedTestProfile().run(context -> {
                Charset injected =
                        context.getBean(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME, Charset.class);

                // carddemo.charset.dataset carries NO default, so this value was stated by a document:
                // US-ASCII in application-test.yml, whose bindings address the nine text fixtures, and
                // IBM037 in application.yml, whose bindings address the mainframe datasets. Either way
                // it is named, never inherited from the JVM (practice B8).
                assertThat(context.getEnvironment()
                        .getProperty(CobolCharsetConfig.DATASET_CHARSET_PROPERTY))
                        .isEqualTo(StandardCharsets.US_ASCII.name());
                assertThat(injected).isEqualTo(ASCII);
                // The code page this suite asserts with IS the one the profile injects, so no assertion
                // here is decoding fixture bytes differently from the way the module would.
                assertThat(CODEC.charset()).isEqualTo(injected);
            });
        }

        @Test
        @DisplayName("Gate G44: the profile-bound repository still only ever composes a read")
        void theProfileBoundRepositoryComposesOnlyReads() {
            shippedTestProfile().run(context -> {
                CardXrefRepository repository = new CardXrefRepository(
                        context.getBean(JdbcTemplate.class),
                        context.getBean(DatasetBindings.class),
                        context.getBean(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME, Charset.class),
                        context.getBean(RecordImageForm.class));

                // The two dataset-scoped statements the repository can compose without reaching a
                // backend. Both are reads, and neither creates, alters or migrates anything: the
                // existing cluster is reached exactly as it is, with no DDL and no schema change.
                assertThat(repository.describeBaseStatement()).startsWith("SELECT ");
                assertThat(repository.describeAlternateIndexStatement()).startsWith("SELECT ");
                assertThat(repository.describeBaseStatement()).contains("WHERE 1 = 0");
                assertThat(repository.describeAlternateIndexStatement()).contains("WHERE 1 = 0");

                // Each statement addresses the name ITS OWN key declares, whatever that name is - the
                // one property that holds under both profiles.
                //
                // GATE G45, MADE LITERAL BY THIS PROFILE. Under the default profile the two keys carry
                // two names, a .VSAM.KSDS for the base and a .VSAM.AIX.PATH for the path, and the
                // statements therefore differ. Under the test profile they carry the SAME name, because
                // one seeded relation stands for the whole cluster and the alternate index is an
                // additional access path over it rather than a dataset of its own. Both arrangements are
                // correct and neither is asserted away here: what is asserted is that each access path
                // follows its own configuration key. Asserting the two statements must DIFFER would have
                // been asserting the opposite of gate G45.
                assertThat(repository.describeBaseStatement())
                        .contains(context.getEnvironment().getProperty(BASE_DSNAME_KEY));
                assertThat(repository.describeAlternateIndexStatement())
                        .contains(context.getEnvironment().getProperty(ALTERNATE_DSNAME_KEY));
                // The CICS FILE names the online programs hold as PIC X(08), padded through the codec's
                // own PIC X move so the module's single truncation-and-padding rule governs them.
                assertThat(repository.baseFileNameForCics())
                        .hasSize(CardXrefRepository.CICS_FILE_NAME_LENGTH)
                        .startsWith(CardXrefRepository.BASE_DD_NAME);
                assertThat(repository.alternateIndexFileNameForCics())
                        .hasSize(CardXrefRepository.CICS_FILE_NAME_LENGTH)
                        .startsWith(CardXrefRepository.ALTERNATE_INDEX_DD_NAME);
            });
        }
    }

    // =============================================================================================
    // The batch DD view - the DD-mapping finding.
    //
    // CBACT03C reads through //XREFFILE and CBACT04C through //XREFFILE plus //XREFFIL1. Those are the
    // JCL's names for the two things the CSD calls CCXREF and CXACAIX, and a job resolves them through
    // its own view of the catalogue - so it needs a way to say "address MY bindings".
    // =============================================================================================

    @Nested
    @DisplayName("addressing - the caller's own DD bindings are the ones read")
    class TheBatchDdView {

        private final JdbcTemplate template = mock(JdbcTemplate.class);

        @Test
        @DisplayName("the batch DD names are the ones READXREF.jcl and INTCALC.jcl bind")
        void theBatchDdNamesAreTheJcls() {
            assertThat(CardXrefRepository.BATCH_DD_NAME).isEqualTo("XREFFILE");
            assertThat(CardXrefRepository.ALTERNATE_INDEX_BATCH_DD_NAME).isEqualTo("XREFFIL1");
            assertThat(CardXrefRepository.BATCH_DD_NAME)
                    .isNotEqualTo(CardXrefRepository.BASE_DD_NAME);
            assertThat(CardXrefRepository.ALTERNATE_INDEX_BATCH_DD_NAME)
                    .isNotEqualTo(CardXrefRepository.ALTERNATE_INDEX_DD_NAME);
        }

        @Test
        @DisplayName("bindings naming the datasets already addressed hand back the same instance")
        void identicalBindingsAreIdentity() {
            CardXrefRepository subject = repository(template);

            CardXrefRepository same = subject.addressing(ksds(BASE_DS),
                    CardXrefRepository.BATCH_DD_NAME, aixPath(ALT_DS),
                    CardXrefRepository.ALTERNATE_INDEX_BATCH_DD_NAME);

            assertThat(same).isSameAs(subject);
        }

        @Test
        @DisplayName("an omitted alternate-index binding keeps the configured path and is not identity "
                + "when the base moves")
        void anOmittedAlternateIndexKeepsThePath() {
            // CBACT03C declares one SELECT and READXREF.jcl one DD, so a sequential reader supplies no
            // alternate-index binding. The path must not become null, because the instance still has to
            // be a whole repository.
            CardXrefRepository subject = repository(template);

            CardXrefRepository rebound = subject.addressing(ksds("TEST.XREF.BATCH"),
                    CardXrefRepository.BATCH_DD_NAME, null,
                    CardXrefRepository.ALTERNATE_INDEX_BATCH_DD_NAME);

            assertThat(rebound).isNotSameAs(subject);
            assertThat(rebound.baseDatasetName()).isEqualTo("TEST.XREF.BATCH");
            assertThat(rebound.alternateIndexDatasetName()).isEqualTo(ALT_DS);
        }

        @Test
        @DisplayName("an omitted alternate-index binding over an unchanged base is identity")
        void anOmittedAlternateIndexOverTheSameBaseIsIdentity() {
            CardXrefRepository subject = repository(template);

            CardXrefRepository same = subject.addressing(ksds(BASE_DS),
                    CardXrefRepository.BATCH_DD_NAME, null,
                    CardXrefRepository.ALTERNATE_INDEX_BATCH_DD_NAME);

            assertThat(same).isSameAs(subject);
        }

        @Test
        @DisplayName("both bindings move together, and the statements follow them")
        void bothBindingsRebase() {
            CardXrefRepository subject = repository(template);

            CardXrefRepository rebound = subject.addressing(ksds("TEST.XREF.BATCH"),
                    CardXrefRepository.BATCH_DD_NAME, aixPath("TEST.XREF.BATCH.PATH"),
                    CardXrefRepository.ALTERNATE_INDEX_BATCH_DD_NAME);

            assertThat(rebound.baseDatasetName()).isEqualTo("TEST.XREF.BATCH");
            assertThat(rebound.alternateIndexDatasetName()).isEqualTo("TEST.XREF.BATCH.PATH");
            // The statements address the new relations. Carrying the source's resolved statements over
            // would have sent them at the old datasets - the defect, expressed in SQL.
            assertThat(rebound.describeBaseStatement())
                    .contains("TEST.XREF.BATCH")
                    .doesNotContain(BASE_DS);
            assertThat(rebound.describeAlternateIndexStatement())
                    .contains("TEST.XREF.BATCH.PATH")
                    .doesNotContain(ALT_DS);
            // The instance it came from is untouched: two views, one of them the online one.
            assertThat(subject.baseDatasetName()).isEqualTo(BASE_DS);
        }

        @Test
        @DisplayName("a wrong record width is refused for either binding, naming the key to correct")
        void aWrongWidthIsRefused() {
            CardXrefRepository subject = repository(template);
            DatasetBinding wrongBase = new DatasetBinding("TEST.XREF.BATCH", "ksds", false, "FB", null,
                    CardXrefRecord.RECORD_LENGTH + 1, "CVACT03Y", null, null, null, null);
            DatasetBinding wrongPath = new DatasetBinding("TEST.XREF.BATCH.PATH", "aix-path", false,
                    "FB", null, CardXrefRecord.RECORD_LENGTH - 1, "CVACT03Y", null, null,
                    CardXrefRepository.BASE_DD_NAME, CardXrefRepository.EXPECTED_ALTERNATE_KEY_FIELD);

            assertThatIllegalStateException()
                    .isThrownBy(() -> subject.addressing(wrongBase,
                            CardXrefRepository.BATCH_DD_NAME, aixPath(ALT_DS),
                            CardXrefRepository.ALTERNATE_INDEX_BATCH_DD_NAME))
                    .withMessageContaining(CardXrefRepository.BATCH_DD_NAME);
            assertThatIllegalStateException()
                    .isThrownBy(() -> subject.addressing(ksds("TEST.XREF.BATCH"),
                            CardXrefRepository.BATCH_DD_NAME, wrongPath,
                            CardXrefRepository.ALTERNATE_INDEX_BATCH_DD_NAME))
                    .withMessageContaining(CardXrefRepository.ALTERNATE_INDEX_BATCH_DD_NAME);
        }

        @Test
        @DisplayName("a blank dataset name is refused")
        void aBlankDatasetNameIsRefused() {
            CardXrefRepository subject = repository(template);

            assertThatIllegalStateException()
                    .isThrownBy(() -> subject.addressing(ksds("  "),
                            CardXrefRepository.BATCH_DD_NAME, null,
                            CardXrefRepository.ALTERNATE_INDEX_BATCH_DD_NAME))
                    .withMessageContaining(CardXrefRepository.BATCH_DD_NAME);
        }

        @Test
        @DisplayName("the base binding and both DD names are required; only the path may be omitted")
        void theRequiredArgumentsAreRequired() {
            CardXrefRepository subject = repository(template);

            assertThatNullPointerException()
                    .isThrownBy(() -> subject.addressing(ksds(BASE_DS), null, null, "XREFFIL1"))
                    .withMessageContaining("base DD name");
            assertThatNullPointerException()
                    .isThrownBy(() -> subject.addressing(ksds(BASE_DS), "XREFFILE", null, null))
                    .withMessageContaining("alternate-index DD name");
            assertThatNullPointerException()
                    .isThrownBy(() -> subject.addressing(null, "XREFFILE", null, "XREFFIL1"))
                    .withMessageContaining("XREFFILE");
        }
    }
}
