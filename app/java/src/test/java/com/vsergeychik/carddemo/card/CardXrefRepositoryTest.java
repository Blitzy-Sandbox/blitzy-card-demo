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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
 * Tests for {@link CardXrefRepository}: the single {@code @Repository} covering the {@code CCXREF} base
 * KSDS and the {@code CXACAIX} alternate-index path over it.
 */
@DisplayName("CardXrefRepository - one cluster, two access paths, three operations")
class CardXrefRepositoryTest {
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    private static final String BASE_DS = "TEST.XREF.BASE";

    private static final String ALT_DS = "TEST.XREF.ACCTPATH";

    private static final String DESCRIBED_COLUMN = "VSAM_RECORD_IMAGE";

    private static final String IMAGE = "\"" + DESCRIBED_COLUMN + "\"";

    private static final String BASE_DESCRIBE_SQL =
            "SELECT * FROM \"" + BASE_DS + "\" WHERE 1 = 0";

    private static final String ALT_DESCRIBE_SQL = "SELECT * FROM \"" + ALT_DS + "\" WHERE 1 = 0";

    private static final String BASE_KEYED_SQL = "SELECT * FROM \"" + BASE_DS + "\" WHERE " + IMAGE
            + " LIKE ? ESCAPE '\\' ORDER BY " + IMAGE + " ASC";

    private static final String ALT_KEYED_SQL = "SELECT * FROM \"" + ALT_DS + "\" WHERE " + IMAGE
            + " LIKE ? ESCAPE '\\' ORDER BY " + IMAGE + " ASC";

    private static final String BASE_BROWSE_SQL = "SELECT * FROM \"" + BASE_DS + "\" ORDER BY " + IMAGE
            + " ASC";

    private static final String BASE_BROWSE_AFTER_SQL = "SELECT * FROM \"" + BASE_DS + "\" WHERE "
            + "(" + IMAGE + " > ? OR " + IMAGE + " IS NULL) ORDER BY " + IMAGE + " ASC";

    private static final String BASE_PROBE_SQL = "SELECT * FROM \"" + BASE_DS + "\" WHERE " + IMAGE
            + " IS NULL";

    private static final String ALT_PROBE_SQL = "SELECT * FROM \"" + ALT_DS + "\" WHERE " + IMAGE
            + " IS NULL";

    private static final String CARD_1 = "0500024453765740";

    private static final String CARD_2 = "0683586198171516";

    private static final FixedWidthCodec CODEC = new FixedWidthCodec(ASCII);

    private static DatasetBinding ksds(String dsname) {
        return new DatasetBinding(dsname, "ksds", false, "FB", null, CardXrefRecord.RECORD_LENGTH,
                "CVACT03Y", null, null, null, null);
    }

    private static DatasetBinding aixPath(String dsname) {
        return new DatasetBinding(dsname, "aix-path", false, "FB", null, CardXrefRecord.RECORD_LENGTH,
                "CVACT03Y", null, null, CardXrefRepository.BASE_DD_NAME,
                CardXrefRepository.EXPECTED_ALTERNATE_KEY_FIELD);
    }

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

    private static DatasetBindings validBindings() {
        return bindings(ksds(BASE_DS), aixPath(ALT_DS));
    }

    private static CardXrefRepository repository(JdbcTemplate jdbcTemplate) {
        return new CardXrefRepository(jdbcTemplate, validBindings(), ASCII, RecordImageForm.CHARACTER);
    }

    private static String image(String cardNumber, int customerId, long accountId) {
        return new String(new CardXrefRecord(cardNumber, customerId, accountId).encode(ASCII), ASCII);
    }

    private static String rowOfWidth(String cardNumber, int width) {
        String declared = image(cardNumber, 50, 50L);
        if (width <= declared.length()) {
            return declared.substring(0, width);
        }
        return declared + " ".repeat(width - declared.length());
    }

    private static String withNonDigitCustomerId(String declaredWidthImage) {
        int custIdOffset = CardXrefRecord.XREF_CARD_NUM_LENGTH;
        return declaredWidthImage.substring(0, custIdOffset) + "X"
                + declaredWidthImage.substring(custIdOffset + 1);
    }

    private final Map<JdbcTemplate, Backend> backends = new IdentityHashMap<>();

    private Backend backend(JdbcTemplate jdbcTemplate) {
        return backends.computeIfAbsent(jdbcTemplate, Backend::new);
    }

    private void stubRows(JdbcTemplate jdbcTemplate, String dataset, List<String> rows) {
        if (rows == null) {
            backend(jdbcTemplate).yieldingNothing(dataset);
        } else {
            backend(jdbcTemplate).storing(dataset, rows);
        }
    }

    private void stubFailure(JdbcTemplate jdbcTemplate, String dataset) {
        backend(jdbcTemplate).failing(dataset);
    }

    private static final class Backend {
        private final Map<String, List<String>> stored = new LinkedHashMap<>();

        private final Set<String> failing = new LinkedHashSet<>();

        private final Set<String> yieldingNothing = new LinkedHashSet<>();

        private final Set<String> presentingUnreadableRowsToKeyedReads = new LinkedHashSet<>();

        private final List<String> statementsSent = new ArrayList<>();

        private final List<String> patternsBound = new ArrayList<>();

        Backend(JdbcTemplate template) {
            when(template.query(anyString(), ArgumentMatchers.<ResultSetExtractor<String>>any()))
                    .thenAnswer(invocation -> describe(invocation.getArgument(0)));
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

        Backend presentingUnreadableRowsToKeyedReads(String dataset) {
            presentingUnreadableRowsToKeyedReads.add(dataset);
            return this;
        }

        List<String> statementsSent() {
            return List.copyOf(statementsSent);
        }

        List<String> patternsBound() {
            return List.copyOf(patternsBound);
        }

        private String describe(String sql) {
            statementsSent.add(sql);
            requireReachable(sql);
            return DESCRIBED_COLUMN;
        }

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
            if (statement.endsWith("IS NULL")) {
                ResultSetExtractor<?> probeExtractor = invocation.getArgument(1);
                boolean anyUnreadable = rowsOf(statement).stream().anyMatch(row -> row == null);
                return probeExtractor.extractData(oneRowResultSet(anyUnreadable, null));
            }
            if (statement.contains("LIKE")) {
                String pattern = captured.get(0);
                patternsBound.add(pattern);
                return keyedMatches(statement, pattern);
            }
            String after = captured.isEmpty() ? null : captured.get(0);
            ResultSetExtractor<?> extractor = invocation.getArgument(1);
            List<String> rows = rowsOf(statement);
            int index = nextBrowseRowIndex(rows, after);
            return extractor.extractData(
                    oneRowResultSet(index >= 0, index >= 0 ? rows.get(index) : null));
        }

        private List<String> keyedMatches(String statement, String pattern) {
            Pattern matcher = likeAsRegex(pattern);
            boolean nullsMatch = presentingUnreadableRowsToKeyedReads.contains(datasetOf(statement));
            List<String> matches = new ArrayList<>();
            for (String row : rowsOf(statement)) {
                if (matches.size() == DUPLICATE_DETECTION_LIMIT) {
                    break;
                }
                if (row == null ? nullsMatch : matcher.matcher(row).matches()) {
                    matches.add(row);
                }
            }
            return matches;
        }

        private static int nextBrowseRowIndex(List<String> rows, String after) {
            for (int index = 0; index < rows.size(); index++) {
                String row = rows.get(index);
                if (after == null || (row != null && row.compareTo(after) > 0)) {
                    return index;
                }
            }
            return -1;
        }

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

        private static String datasetOf(String sql) {
            return sql.contains("\"" + ALT_DS + "\"") ? ALT_DS : BASE_DS;
        }

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

    private static final int DUPLICATE_DETECTION_LIMIT = 2;

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
        @DisplayName("A NOTFND is proved: the probe runs against the BASE cluster and finds nothing")
        void anUnmatchedKeyIsProvedAgainstTheBaseCluster() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            Backend backend = backend(jdbc);
            backend.storing(BASE_DS, List.of(image(CARD_2, 27, 27L)));

            ReadResult result = repository(jdbc).readByCardNumber(CARD_1);

            assertThat(result.isNotFound()).isTrue();
            assertThat(backend.statementsSent())
                    .as("the probe addresses the relation that reported nothing, and only on this path")
                    .containsExactly(BASE_DESCRIBE_SQL, BASE_KEYED_SQL, BASE_PROBE_SQL);
        }

        @Test
        @DisplayName("A present-but-unreadable row is NOT reported as absent: it is the WHEN OTHER arm")
        void anUnreadableRowIsNotReportedAsAbsent() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            Backend backend = backend(jdbc);
            backend.storing(BASE_DS, Arrays.asList(image(CARD_2, 27, 27L), null));

            ReadResult result = repository(jdbc).readByCardNumber(CARD_1);

            assertThat(result.isNotFound()).isFalse();
            assertThat(result.isOther()).isTrue();
            assertThat(result.status()).isEqualTo(CardXrefRepository.PERMANENT_ERROR_STATUS);
            assertThat(result.record()).isEmpty();
            assertThat(backend.statementsSent()).containsExactly(BASE_DESCRIBE_SQL, BASE_KEYED_SQL,
                    BASE_PROBE_SQL);
        }

        @Test
        @DisplayName("A read that FOUND its record is untouched by an unreadable row elsewhere")
        void aFoundRecordIsUnaffectedByAnUnreadableRowElsewhere() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            Backend backend = backend(jdbc);
            backend.storing(BASE_DS, Arrays.asList(image(CARD_1, 50, 50L), null));

            ReadResult result = repository(jdbc).readByCardNumber(CARD_1);

            assertThat(result.isFound()).isTrue();
            assertThat(result.record().orElseThrow().xrefAcctId()).isEqualTo(50L);
            assertThat(backend.statementsSent())
                    .as("no probe: the read has its record")
                    .containsExactly(BASE_DESCRIBE_SQL, BASE_KEYED_SQL);
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

            assertThat(result.cicsResp()).isEqualTo(FileStatus.NOTOPEN);
            assertThat(result.cicsResp2())
                    .as("RESP2 is honestly zero: a read served over JDBC has no CICS secondary reason "
                            + "code, and inventing a plausible one would put a fabricated number into "
                            + "message text that parity diffing compares")
                    .isEqualTo(CardXrefRepository.CICS_RESP2_NOT_APPLICABLE);
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
            backend(jdbc).storing(BASE_DS, Arrays.asList(image(CARD_1, 50, 50L), null))
                    .presentingUnreadableRowsToKeyedReads(BASE_DS);

            ReadResult result = repository(jdbc).readByCardNumber(CARD_1);

            assertThat(result.isOther()).isTrue();
            assertThat(result.status()).isEqualTo(CardXrefRepository.PERMANENT_ERROR_STATUS);
        }

        @Test
        @DisplayName("The key is a PIC X MOVE: a short key is space-padded on the RIGHT")
        void aShortKeyIsSpacePaddedOnTheRight() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
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
        @DisplayName("Gate G19 / risk R-F: a 36-byte row is reported as '04', not quietly accepted")
        void aShortRowIsRejected() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_DS, List.of(CARD_1 + "000000050" + "00000000050"));

            ReadResult result = repository(jdbc).readByCardNumber(CARD_1);

            assertThat(result.status()).isEqualTo(FileStatus.RECORD_LENGTH_CONFLICT);
            assertThat(result.outcome()).isEqualTo(Outcome.OTHER);
            assertThat(result.cicsResp()).isEqualTo(FileStatus.LENGERR);
            assertThat(result.isFound()).isFalse();
            assertThat(result.isNotFound())
                    .as("a record that is present and unreadable is not a missing record")
                    .isFalse();
            assertThat(result.record()).isEmpty();
        }

        @Test
        @DisplayName("A row wider than the copybook is reported as '04' as well")
        void anOverWideRowIsRejected() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_DS, List.of(image(CARD_1, 50, 50L) + " "));

            ReadResult result = repository(jdbc).readByCardNumber(CARD_1);

            assertThat(result.status()).isEqualTo(FileStatus.RECORD_LENGTH_CONFLICT);
            assertThat(result.isFound()).isFalse();
            assertThat(result.record()).isEmpty();
        }

        @ParameterizedTest(name = "a {0}-byte row is reported as ''04'', never thrown")
        @ValueSource(ints = {16, 35, 36, 49, 51, 100})
        @DisplayName("Every width but fifty is reported as '04', on the read that met it")
        void everyWidthButFiftyIsReported(int width) {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_DS, List.of(rowOfWidth(CARD_1, width)));

            ReadResult result = repository(jdbc).readByCardNumber(CARD_1);

            assertThat(result.status()).isEqualTo(FileStatus.RECORD_LENGTH_CONFLICT);
            assertThat(result.outcome()).isEqualTo(Outcome.OTHER);
            assertThat(result.cicsResp()).isEqualTo(FileStatus.LENGERR);
            assertThat(result.applResult()).isEqualTo(CardXrefRepository.APPL_RESULT_FATAL);
            assertThat(result.isFound()).isFalse();
            assertThat(result.isNotFound())
                    .as("a record that is present and unreadable is not a missing record")
                    .isFalse();
            assertThat(result.record()).isEmpty();
        }

        @Test
        @DisplayName("A row of the declared width whose numeric span holds a non-digit is reported, "
                + "not thrown")
        void anUndecodableRowOfTheDeclaredWidthIsReported() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_DS, List.of(withNonDigitCustomerId(image(CARD_1, 50, 50L))));

            ReadResult result = repository(jdbc).readByCardNumber(CARD_1);

            assertThat(result.status()).isEqualTo(CardXrefRepository.PERMANENT_ERROR_STATUS);
            assertThat(result.outcome()).isEqualTo(Outcome.OTHER);
            assertThat(result.applResult()).isEqualTo(CardXrefRepository.APPL_RESULT_FATAL);
            assertThat(result.isNotFound()).isFalse();
            assertThat(result.record()).isEmpty();
        }
    }

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
        @DisplayName("Finding DB-04: the alternate read describes the PATH only, never the base cluster")
        void theAlternateReadDescribesThePathOnly() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            Backend backend = backend(jdbc);
            backend.storing(ALT_DS, List.of(image(CARD_1, 50, 50L)));

            ReadResult result = repository(jdbc).readByAccountIdViaAltIndex(50L);

            assertThat(result.isFound()).isTrue();
            assertThat(backend.statementsSent()).containsExactly(ALT_DESCRIBE_SQL, ALT_KEYED_SQL);
        }

        @Test
        @DisplayName("Finding DB-04: a read through the path survives a base cluster that is unreachable")
        void theAlternateReadSurvivesAnUnreachableBaseCluster() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            Backend backend = backend(jdbc);
            backend.storing(ALT_DS, List.of(image(CARD_1, 50, 50L))).failing(BASE_DS);

            ReadResult result = repository(jdbc).readByAccountIdViaAltIndex(50L);

            assertThat(result.isFound())
                    .as("CXACAIX answered, so the read is satisfied; CCXREF was not asked")
                    .isTrue();
            assertThat(backend.statementsSent()).doesNotContain(BASE_DESCRIBE_SQL);
        }

        @Test
        @DisplayName("Finding DB-04: a browse of the base cluster survives a path that is unreachable")
        void theBrowseSurvivesAnUnreachablePath() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            Backend backend = backend(jdbc);
            backend.storing(BASE_DS, List.of(image(CARD_1, 50, 50L))).failing(ALT_DS);

            try (BrowseCursor cursor = repository(jdbc).openBrowse()) {
                assertThat(cursor.openStatus())
                        .as("OPEN INPUT XREFFILE establishes XREFFILE; the path is another DD name")
                        .isEqualTo(FileStatus.OK);
                assertThat(cursor.readNext().isFound()).isTrue();
            }
            assertThat(backend.statementsSent()).doesNotContain(ALT_DESCRIBE_SQL);
        }

        @Test
        @DisplayName("Finding DB-05: a NOTFND through the path is proved against the PATH's own rows")
        void anUnmatchedAccountIsProvedAgainstThePath() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            Backend backend = backend(jdbc);
            backend.storing(ALT_DS, List.of(image(CARD_2, 27, 27L)));

            ReadResult result = repository(jdbc).readByAccountIdViaAltIndex(50L);

            assertThat(result.isNotFound()).isTrue();
            assertThat(backend.statementsSent())
                    .containsExactly(ALT_DESCRIBE_SQL, ALT_KEYED_SQL, ALT_PROBE_SQL);
        }

        @Test
        @DisplayName("Finding DB-05: a probe the backend refuses is the WHEN OTHER arm, never NOTFND")
        @SuppressWarnings("unchecked")
        void aRefusedProbeIsReportedRatherThanAssumedAbsent() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            when(jdbc.query(anyString(), ArgumentMatchers.<ResultSetExtractor<String>>any()))
                    .thenReturn(DESCRIBED_COLUMN);
            when(jdbc.query(any(PreparedStatementCreator.class),
                    ArgumentMatchers.<ResultSetExtractor<Object>>any()))
                    .thenReturn(List.of())
                    .thenThrow(new DataAccessResourceFailureException("the probe was refused"));

            ReadResult result = repository(jdbc).readByCardNumber(CARD_1);

            assertThat(result.isNotFound()).isFalse();
            assertThat(result.isOther()).isTrue();
            assertThat(result.status()).isEqualTo(CardXrefRepository.PERMANENT_ERROR_STATUS);
        }

        @Test
        @DisplayName("Finding DB-05: a probe that yields no result object leaves the NOTFND standing")
        @SuppressWarnings("unchecked")
        void aProbeThatYieldsNothingLeavesTheNotFoundStanding() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            when(jdbc.query(anyString(), ArgumentMatchers.<ResultSetExtractor<String>>any()))
                    .thenReturn(DESCRIBED_COLUMN);
            when(jdbc.query(any(PreparedStatementCreator.class),
                    ArgumentMatchers.<ResultSetExtractor<Object>>any()))
                    .thenReturn(List.of())
                    .thenReturn(null);

            ReadResult result = repository(jdbc).readByCardNumber(CARD_1);

            assertThat(result.isNotFound()).isTrue();
            assertThat(result.status()).isEqualTo(FileStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("Finding DB-05: an unreadable row on the path is the WHEN OTHER arm, not NOTFND")
        void anUnreadableRowOnThePathIsNotAbsence() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            Backend backend = backend(jdbc);
            backend.storing(ALT_DS, Arrays.asList(image(CARD_2, 27, 27L), null));

            ReadResult result = repository(jdbc).readByAccountIdViaAltIndex(50L);

            assertThat(result.isNotFound()).isFalse();
            assertThat(result.isOther()).isTrue();
            assertThat(result.status()).isEqualTo(CardXrefRepository.PERMANENT_ERROR_STATUS);
            assertThat(result.ddName()).isEqualTo(CardXrefRepository.ALTERNATE_INDEX_DD_NAME);
        }

        @Test
        @DisplayName("The key is a PIC 9 MOVE: it is zero-filled on the LEFT, so account 50 is 00000000050")
        void theKeyIsZeroFilledOnTheLeft() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, ALT_DS, List.of(image(CARD_1, 50, 50L)));

            ReadResult result = repository(jdbc).readByAccountIdViaAltIndex(50L);

            assertThat(result.isFound()).isTrue();
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
            backend(nullRow).storing(ALT_DS, Collections.singletonList(null))
                    .presentingUnreadableRowsToKeyedReads(ALT_DS);
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

        @ParameterizedTest(name = "a {0}-byte row is reported as ''04'' through the path too")
        @ValueSource(ints = {36, 49, 51, 100})
        @DisplayName("A malformed row is reported as '04' on the path read as well, not thrown")
        void aMalformedRowIsReportedThroughThePath(int width) {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, ALT_DS, List.of(rowOfWidth(CARD_1, width)));

            ReadResult numericView = repository(jdbc).readByAccountIdViaAltIndex(50L);
            ReadResult alphanumericView = repository(jdbc).readByAccountIdViaAltIndex("00000000050");

            for (ReadResult result : List.of(numericView, alphanumericView)) {
                assertThat(result.status()).isEqualTo(FileStatus.RECORD_LENGTH_CONFLICT);
                assertThat(result.outcome()).isEqualTo(Outcome.OTHER);
                assertThat(result.cicsResp()).isEqualTo(FileStatus.LENGERR);
                assertThat(result.ddName()).isEqualTo(CardXrefRepository.ALTERNATE_INDEX_DD_NAME);
                assertThat(result.isNotFound()).isFalse();
                assertThat(result.record()).isEmpty();
            }
        }

        @Test
        @DisplayName("An undecodable row of the declared width is reported through the path too")
        void anUndecodableRowIsReportedThroughThePath() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, ALT_DS, List.of(withNonDigitCustomerId(image(CARD_1, 50, 50L))));

            ReadResult result = repository(jdbc).readByAccountIdViaAltIndex(50L);

            assertThat(result.status()).isEqualTo(CardXrefRepository.PERMANENT_ERROR_STATUS);
            assertThat(result.outcome()).isEqualTo(Outcome.OTHER);
            assertThat(result.ddName()).isEqualTo(CardXrefRepository.ALTERNATE_INDEX_DD_NAME);
            assertThat(result.record()).isEmpty();
        }
    }

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
        @DisplayName("The OPEN probes on every call, so its outcome never depends on what ran before it")
        void theOpenProbesOnEveryCall() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            Backend backend = backend(jdbc);
            backend.storing(BASE_DS, List.of(image(CARD_1, 50, 50L)));
            CardXrefRepository repository = repository(jdbc);

            repository.openBrowse().closeBrowse();
            repository.openBrowse().closeBrowse();

            assertThat(backend.statementsSent())
                    .filteredOn(statement -> statement.equals(repository.describeBaseStatement()))
                    .hasSize(2);
        }

        @Test
        @DisplayName("An OPEN after a successful pass still detects a dataset that has gone away")
        void theOpenDetectsAnAbsentDatasetAfterASuccessfulPass() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_DS, List.of(image(CARD_1, 50, 50L)));
            CardXrefRepository repository = repository(jdbc);

            try (BrowseCursor warm = repository.openBrowse()) {
                assertThat(warm.openStatus()).isEqualTo(FileStatus.OK);
                assertThat(warm.readNext().isFound()).isTrue();
                assertThat(warm.readNext().isEndOfFile()).isTrue();
            }
            assertThat(repository.resolvedBaseStatements())
                    .as("the pass has resolved and memoised the statement text")
                    .isNotNull();

            stubFailure(jdbc, BASE_DS);

            try (BrowseCursor cold = repository.openBrowse()) {
                assertThat(cold.openStatus()).isEqualTo(CardXrefRepository.PERMANENT_ERROR_STATUS);
                assertThat(cold.openOutcome()).isEqualTo(Outcome.OTHER);
                assertThat(cold.openApplResult()).isEqualTo(CardXrefRepository.APPL_RESULT_FATAL);
                assertThat(cold.isOpen()).isFalse();
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

                ReadResult next = cursor.readNext();
                assertThat(next.isOther()).isTrue();
                assertThat(next.isEndOfFile()).isFalse();
                assertThat(next.status()).isEqualTo(CardXrefRepository.PERMANENT_ERROR_STATUS);
            }
        }

        @Test
        @DisplayName("The open transfers no row, and each read transfers exactly one")
        void theOpenTransfersNoRowAndEachReadTransfersOne() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            Backend backend = backend(jdbc);
            backend.storing(BASE_DS, List.of(image(CARD_1, 50, 50L), image(CARD_2, 27, 27L)));

            try (BrowseCursor cursor = repository(jdbc).openBrowse()) {
                assertThat(cursor.openStatus()).isEqualTo(FileStatus.OK);
                assertThat(backend.statementsSent())
                        .as("the open describes THE DATASET IT OPENS and transfers no row: OPEN INPUT "
                                + "XREFFILE establishes XREFFILE, and the alternate-index path is a DD "
                                + "name this operation never reads (finding DB-04)")
                        .containsExactly(BASE_DESCRIBE_SQL);

                assertThat(cursor.readNext().record().orElseThrow().xrefCardNum()).isEqualTo(CARD_1);
                assertThat(backend.statementsSent()).containsExactly(
                        BASE_DESCRIBE_SQL, BASE_BROWSE_SQL);

                assertThat(cursor.readNext().record().orElseThrow().xrefCardNum()).isEqualTo(CARD_2);
                assertThat(backend.statementsSent())
                        .as("every read after the first advances past the record it returned")
                        .containsExactly(BASE_DESCRIBE_SQL, BASE_BROWSE_SQL,
                                BASE_BROWSE_AFTER_SQL);

                assertThat(cursor.readNext().isEndOfFile()).isTrue();
                assertThat(cursor.readNext().isEndOfFile())
                        .as("the end of the pass is remembered, so it costs no further round trip")
                        .isTrue();
                assertThat(backend.statementsSent()).hasSize(4);
            }
        }

        @Test
        @DisplayName("A template that yields no result at all is an empty read, never a silent end of file")
        void aNullResultIsAnEmptyRead() {
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
                assertThat(cursor.readNext().isOther()).isTrue();
                assertThat(cursor.readNext().status())
                        .isEqualTo(CardXrefRepository.PERMANENT_ERROR_STATUS);
                assertThat(cursor.closeBrowse()).isEqualTo(CardXrefRepository.PERMANENT_ERROR_STATUS);
                assertThat(cursor.closeApplResult()).isEqualTo(CardXrefRepository.APPL_RESULT_FATAL);
            }
        }

        @Test
        @DisplayName("A read that the backend refuses is the READ's own failure, and does not advance")
        void aRefusedReadIsTheReadsOwnFailure() {
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
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_DS, Arrays.asList(null, image(CARD_2, 27, 27L)));

            try (BrowseCursor cursor = repository(jdbc).openBrowse()) {
                ReadResult broken = cursor.readNext();

                assertThat(broken.isOther()).isTrue();
                assertThat(broken.isEndOfFile()).isFalse();
                assertThat(broken.status()).isEqualTo(CardXrefRepository.PERMANENT_ERROR_STATUS);
                assertThat(cursor.position()).as("an unreadable row is not a record returned").isZero();

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
        @DisplayName("Gate G19: a malformed row is reported by the readNext that reaches it, not by the open")
        void aMalformedRowIsRaisedWhenItIsReached() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_DS,
                    List.of(image(CARD_1, 50, 50L), CARD_2 + "000000027" + "00000000027"));

            try (BrowseCursor cursor = repository(jdbc).openBrowse()) {
                assertThat(cursor.openStatus()).isEqualTo(FileStatus.OK);
                assertThat(cursor.readNext().isFound()).isTrue();

                ReadResult malformed = cursor.readNext();
                assertThat(malformed.status()).isEqualTo(FileStatus.RECORD_LENGTH_CONFLICT);
                assertThat(malformed.outcome()).isEqualTo(Outcome.OTHER);
                assertThat(malformed.applResult()).isEqualTo(CardXrefRepository.APPL_RESULT_FATAL);
                assertThat(malformed.isEndOfFile())
                        .as("a record that cannot be read is emphatically not an end of file")
                        .isFalse();
                assertThat(malformed.record()).isEmpty();
            }
        }

        @ParameterizedTest(name = "a {0}-byte row is reported by the browse as ''04''")
        @ValueSource(ints = {1, 16, 36, 49, 51, 100})
        @DisplayName("The browse reports every width but fifty as '04', at either boundary")
        void theBrowseReportsEveryWidthButFifty(int width) {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_DS, List.of(rowOfWidth(CARD_1, width)));

            try (BrowseCursor cursor = repository(jdbc).openBrowse()) {
                ReadResult malformed = cursor.readNext();

                assertThat(malformed.status()).isEqualTo(FileStatus.RECORD_LENGTH_CONFLICT);
                assertThat(malformed.outcome()).isEqualTo(Outcome.OTHER);
                assertThat(malformed.cicsResp()).isEqualTo(FileStatus.LENGERR);
                assertThat(malformed.applResult()).isEqualTo(CardXrefRepository.APPL_RESULT_FATAL);
                assertThat(malformed.isEndOfFile()).isFalse();
                assertThat(malformed.record()).isEmpty();
            }
        }

        @Test
        @DisplayName("The browse reports an undecodable row of the declared width, and does not re-read it")
        void theBrowseReportsAnUndecodableRow() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_DS, List.of(withNonDigitCustomerId(image(CARD_1, 50, 50L)),
                    image(CARD_2, 27, 27L)));

            try (BrowseCursor cursor = repository(jdbc).openBrowse()) {
                ReadResult undecodable = cursor.readNext();

                assertThat(undecodable.status()).isEqualTo(CardXrefRepository.PERMANENT_ERROR_STATUS);
                assertThat(undecodable.outcome()).isEqualTo(Outcome.OTHER);
                assertThat(undecodable.applResult()).isEqualTo(CardXrefRepository.APPL_RESULT_FATAL);
                assertThat(undecodable.record()).isEmpty();

                assertThat(cursor.position()).isEqualTo(1);
                assertThat(cursor.readNext().record().orElseThrow().xrefCardNum()).isEqualTo(CARD_2);
            }
        }

        @Test
        @DisplayName("A malformed row does not stop the browse from reaching the records after it")
        void aMalformedRowDoesNotStopThePass() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            String afterTheMalformedRow = "0700000000000000";
            stubRows(jdbc, BASE_DS, List.of(image(CARD_1, 50, 50L), rowOfWidth(CARD_2, 49),
                    image(afterTheMalformedRow, 27, 27L)));

            try (BrowseCursor cursor = repository(jdbc).openBrowse()) {
                assertThat(cursor.readNext().record().orElseThrow().xrefCardNum()).isEqualTo(CARD_1);
                assertThat(cursor.readNext().status()).isEqualTo(FileStatus.RECORD_LENGTH_CONFLICT);
                assertThat(cursor.readNext().record().orElseThrow().xrefCustId()).isEqualTo(27);
                assertThat(cursor.readNext().isEndOfFile()).isTrue();
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

            assertThat(outcomes).isNotEmpty();
            assertThat(outcomes).allSatisfy(outcome -> {
                assertThat(outcome.isDuplicate())
                        .as("a keyless read has no key to duplicate")
                        .isFalse();
                assertThat(outcome.status()).isNotEqualTo(FileStatus.DUPLICATE);
                assertThat(outcome.outcome()).isIn(Outcome.OK, Outcome.END_OF_FILE, Outcome.OTHER);
            });
            assertThat(outcomes.get(outcomes.size() - 1).isEndOfFile()).isTrue();
            assertThat(outcomes.subList(0, outcomes.size() - 1))
                    .allSatisfy(outcome -> assertThat(outcome.isFound()).isTrue());

            assertThat(repository(jdbc).readByCardNumber(CARD_1).isDuplicate())
                    .as("'22' is reachable - for a keyed read, which is the operation it belongs to")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("Raw record image fidelity - the FILLER X(14) of CVACT03Y")
    class RawRecordImageFidelity {
        private static final String DIRTY_ROW =
                image(CARD_1, 50, 50L).substring(0, CardXrefRecord.FILLER_OFFSET)
                        + "*".repeat(CardXrefRecord.FILLER_LENGTH);

        @Test
        @DisplayName("a browse step hands back the row's own bytes, FILLER included")
        void aBrowseStepRetainsTheRowsBytes() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_DS, List.of(DIRTY_ROW));

            try (BrowseCursor cursor = repository(jdbc).openBrowse()) {
                ReadResult next = cursor.readNext();

                assertThat(next.isFound()).isTrue();
                assertThat(next.requireStoredImage())
                        .as("the record area DISPLAY writes at :78 and again at :96 is the row's own")
                        .hasSize(CardXrefRecord.RECORD_LENGTH)
                        .isEqualTo(DIRTY_ROW);
            }
        }

        @Test
        @DisplayName("a base keyed read hands back the row's own bytes, FILLER included")
        void aBaseKeyedReadRetainsTheRowsBytes() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_DS, List.of(DIRTY_ROW));

            ReadResult read = repository(jdbc).readByCardNumber(CARD_1);

            assertThat(read.isFound()).isTrue();
            assertThat(read.requireStoredImage()).isEqualTo(DIRTY_ROW);
        }

        @Test
        @DisplayName("an alternate-index read hands back the row's own bytes, FILLER included")
        void anAlternateIndexReadRetainsTheRowsBytes() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, ALT_DS, List.of(DIRTY_ROW));

            ReadResult read = repository(jdbc).readByAccountIdViaAltIndex(50L);

            assertThat(read.isFound()).isTrue();
            assertThat(read.requireStoredImage()).isEqualTo(DIRTY_ROW);
        }

        @Test
        @DisplayName("re-encoding the decoded record would have lost the FILLER, which is the finding")
        void reEncodingTheDecodedRecordWouldHaveLostIt() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_DS, List.of(DIRTY_ROW));

            ReadResult read = repository(jdbc).readByCardNumber(CARD_1);
            CardXrefRecord decoded = read.record().orElseThrow();

            assertThat(decoded).isEqualTo(new CardXrefRecord(CARD_1, 50, 50L));
            assertThat(xrefImageOf(decoded))
                    .as("a fresh record area blanks FILLER X(14), so this is not what CBACT03C writes")
                    .hasSize(CardXrefRecord.RECORD_LENGTH)
                    .isNotEqualTo(DIRTY_ROW)
                    .endsWith(" ".repeat(CardXrefRecord.FILLER_LENGTH));
        }
    }

    @Nested
    @DisplayName("Byte-level parity - the offset audit and the real fixture")
    class ByteLevelParity {
        private static final String FIXTURE = "/fixtures/cardxref.txt";

        private List<String> fixtureRows() throws IOException {
            try (InputStream stream = CardXrefRepositoryTest.class.getResourceAsStream(FIXTURE)) {
                assertThat(stream).as("the fixture must be on the test classpath").isNotNull();
                return new String(stream.readAllBytes(), ASCII).lines().toList();
            }
        }

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

                assertThat(after)
                        .as("row %d is the copybook width after normalisation", index + 1)
                        .hasSize(CardXrefRepository.RECORD_LENGTH);
                assertThat(after.substring(CardXrefRecord.FILLER_OFFSET))
                        .as("row %d FILLER span is 14 spaces", index + 1)
                        .isEqualTo(" ".repeat(CardXrefRecord.FILLER_LENGTH))
                        .hasSize(CardXrefRecord.FILLER_LENGTH);
                assertThat(after.substring(0, CardXrefRecord.FILLER_OFFSET))
                        .as("row %d data prefix survives the pad byte for byte", index + 1)
                        .isEqualTo(before);

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

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a 36-byte image is not a 50-byte record and the model must say so")
                    .isThrownBy(() -> CardXrefRecord.decode(raw.getBytes(ASCII), ASCII))
                    .withMessageContaining("padToDeclaredWidth");

            assertThat(CardXrefRecord.decode(
                    CODEC.padToDeclaredWidth(raw, CardXrefRepository.RECORD_LENGTH).getBytes(ASCII),
                    ASCII))
                    .isEqualTo(new CardXrefRecord(CARD_1, 50, 50L));
        }

        @Test
        @DisplayName("Gate G16: padded 36 to 50, all fifty fixture rows decode and re-encode byte-identically")
        void theWholeFixtureRoundTripsAfterNormalisation() throws IOException {
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
            assertThat(byAccount.record()).isEqualTo(byCard.record());
            assertThat(byCard.ddName()).isNotEqualTo(byAccount.ddName());

            String rawRow = fixtureRows().get(0);
            CardXrefRecord found = byCard.record().orElseThrow();

            assertThat(found.xrefCardNum())
                    .isEqualTo(rawRow.substring(CardXrefRecord.XREF_CARD_NUM_OFFSET,
                            CardXrefRecord.XREF_CARD_NUM_OFFSET + CardXrefRecord.XREF_CARD_NUM_LENGTH))
                    .hasSize(16)
                    .startsWith("0");
            assertThat(found.xrefCustId()).isEqualTo(50);
            assertThat(found.xrefAcctId()).isEqualTo(50L);
            String image = new String(found.encode(ASCII), ASCII);
            assertThat(image).hasSize(CardXrefRepository.RECORD_LENGTH);
            assertThat(image.substring(CardXrefRecord.FILLER_OFFSET))
                    .isEqualTo(" ".repeat(CardXrefRecord.FILLER_LENGTH));

            assertThat(image.substring(CardXrefRecord.XREF_CUST_ID_OFFSET,
                    CardXrefRecord.XREF_ACCT_ID_OFFSET))
                    .isEqualTo("000000050")
                    .hasSize(CardXrefRecord.XREF_CUST_ID_LENGTH);
            assertThat(image.substring(CardXrefRecord.XREF_ACCT_ID_OFFSET,
                    CardXrefRecord.FILLER_OFFSET))
                    .isEqualTo("00000000050")
                    .hasSize(CardXrefRecord.XREF_ACCT_ID_LENGTH);
            assertThat(image.substring(0, CardXrefRecord.FILLER_OFFSET)).isEqualTo(rawRow);
        }

        @Test
        @DisplayName("Gate G45: the same record, reached both ways, is byte-identical across all 50 bytes")
        void theTwoAccessPathsReturnTheSameFiftyBytes() throws IOException {
            List<String> padded = normalisedFixtureRows();
            JdbcTemplate oneTemplate = mock(JdbcTemplate.class);
            stubRows(oneTemplate, BASE_DS, padded);
            stubRows(oneTemplate, ALT_DS, padded);
            CardXrefRepository oneRepository = repository(oneTemplate);

            ReadResult byCard = oneRepository.readByCardNumber(CARD_1);
            assertThat(byCard.isFound()).isTrue();
            CardXrefRecord viaBaseKey = byCard.record().orElseThrow();

            ReadResult byAccount =
                    oneRepository.readByAccountIdViaAltIndex(viaBaseKey.xrefAcctId());
            assertThat(byAccount.isFound()).isTrue();
            CardXrefRecord viaAlternateKey = byAccount.record().orElseThrow();

            byte[] fromBase = viaBaseKey.encode(ASCII);
            byte[] fromPath = viaAlternateKey.encode(ASCII);
            assertThat(fromBase).hasSize(CardXrefRepository.RECORD_LENGTH);
            assertThat(fromPath).hasSize(CardXrefRepository.RECORD_LENGTH);
            assertThat(fromPath)
                    .as("the CXACAIX path returns the very bytes the CCXREF base holds")
                    .containsExactly(fromBase);
            assertThat(new String(fromPath, ASCII).substring(CardXrefRecord.FILLER_OFFSET))
                    .isEqualTo(" ".repeat(CardXrefRecord.FILLER_LENGTH));
            assertThat(viaAlternateKey.xrefCardNum()).isEqualTo(viaBaseKey.xrefCardNum());
            assertThat(viaAlternateKey.xrefCustId()).isEqualTo(viaBaseKey.xrefCustId());
            assertThat(viaAlternateKey.xrefAcctId()).isEqualTo(viaBaseKey.xrefAcctId());

            assertThat(byCard.ddName()).isEqualTo(CardXrefRepository.BASE_DD_NAME);
            assertThat(byAccount.ddName()).isEqualTo(CardXrefRepository.ALTERNATE_INDEX_DD_NAME);
            assertThat(CardXrefRepository.CARD_NUMBER_KEY_LENGTH).isEqualTo(16);
            assertThat(CardXrefRepository.ACCOUNT_ID_KEY_LENGTH).isEqualTo(11);
            assertThat(CardXrefRecord.XREF_CARD_NUM_OFFSET)
                    .isNotEqualTo(CardXrefRecord.XREF_ACCT_ID_OFFSET);
        }

        @Test
        @DisplayName("An unpadded fixture row is refused: production never absorbs the 36-byte form")
        void anUnpaddedFixtureRowIsRejected() throws IOException {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_DS, fixtureRows());

            ReadResult result = repository(jdbc).readByCardNumber(CARD_1);

            assertThat(result.status()).isEqualTo(FileStatus.RECORD_LENGTH_CONFLICT);
            assertThat(result.isFound()).isFalse();
            assertThat(result.record()).isEmpty();
        }
    }

    @Nested
    @DisplayName("ReadResult - a status and its meaning are two views of one fact")
    class ReadResultInvariants {
        private static final CardXrefRecord RECORD = new CardXrefRecord(CARD_1, 50, 50L);

        @Test
        @DisplayName("Each factory produces the status, outcome, RESP and record presence it stands for")
        void eachFactoryIsInternallyConsistent() {
            ReadResult found = xrefFound(CardXrefRepository.BASE_DD_NAME, RECORD);
            ReadResult notFound = ReadResult.notFound(CardXrefRepository.BASE_DD_NAME);
            ReadResult endOfFile = ReadResult.endOfFile(CardXrefRepository.BASE_DD_NAME);
            ReadResult duplicate = xrefDuplicate(CardXrefRepository.ALTERNATE_INDEX_DD_NAME,
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
                    xrefFound(CardXrefRepository.BASE_DD_NAME, RECORD),
                    ReadResult.endOfFile(CardXrefRepository.BASE_DD_NAME),
                    xrefDuplicate(CardXrefRepository.BASE_DD_NAME, RECORD, FileStatus.DUPREC),
                    ReadResult.notFound(CardXrefRepository.BASE_DD_NAME));

            assertThat(results).extracting(ReadResult::status)
                    .containsExactly(FileStatus.OK, FileStatus.END_OF_FILE, FileStatus.DUPLICATE,
                            FileStatus.NOT_FOUND);
            for (ReadResult result : results) {
                long trueCount = List.of(result.isFound(), result.isEndOfFile(), result.isDuplicate(),
                        result.isNotFound(), result.isOther()).stream().filter(flag -> flag).count();
                assertThat(trueCount).as("%s answers exactly one predicate", result.status()).isOne();
            }
        }

        @Test
        @DisplayName("APPL-RESULT is 0 for '00', 16 for '10' and 12 for everything else")
        void applResultFollowsTheCbact03cEvaluate() {
            assertThat(xrefFound(CardXrefRepository.BASE_DD_NAME, RECORD).applResult())
                    .isEqualTo(FileStatus.APPL_AOK);
            assertThat(ReadResult.endOfFile(CardXrefRepository.BASE_DD_NAME).applResult())
                    .isEqualTo(FileStatus.APPL_EOF);
            assertThat(ReadResult.notFound(CardXrefRepository.BASE_DD_NAME).applResult())
                    .isEqualTo(CardXrefRepository.APPL_RESULT_FATAL);
            assertThat(xrefDuplicate(CardXrefRepository.BASE_DD_NAME, RECORD, FileStatus.DUPREC)
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
                            Outcome.OK, java.util.Optional.of(RECORD), java.util.Optional.of(xrefImageOf(RECORD)), FileStatus.NORMAL, 0, java.util.Optional.empty()))
                    .withMessageContaining("exactly");
        }

        @Test
        @DisplayName("A status and an outcome that disagree cannot be built")
        void aStatusAndOutcomeThatDisagreeAreRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new ReadResult(CardXrefRepository.BASE_DD_NAME, FileStatus.OK,
                            Outcome.NOT_FOUND, java.util.Optional.empty(), java.util.Optional.empty(), FileStatus.NOTFND, 0, java.util.Optional.empty()))
                    .withMessageContaining("does not classify");
        }

        @Test
        @DisplayName("A record present on an outcome that carries none - and absent when it should - is rejected")
        void recordPresenceMustMatchTheOutcome() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new ReadResult(CardXrefRepository.BASE_DD_NAME,
                            FileStatus.NOT_FOUND, Outcome.NOT_FOUND, java.util.Optional.of(RECORD),
                            java.util.Optional.of(xrefImageOf(RECORD)), FileStatus.NOTFND, 0, java.util.Optional.empty()))
                    .withMessageContaining("carries no record");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new ReadResult(CardXrefRepository.BASE_DD_NAME, FileStatus.OK,
                            Outcome.OK, java.util.Optional.empty(), java.util.Optional.empty(), FileStatus.NORMAL, 0, java.util.Optional.empty()))
                    .withMessageContaining("but none was supplied");
        }

        @Test
        @DisplayName("Every component is required: no null escapes the type")
        void everyComponentIsRequired() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new ReadResult(null, FileStatus.OK, Outcome.OK,
                            java.util.Optional.of(RECORD), java.util.Optional.of(xrefImageOf(RECORD)), FileStatus.NORMAL, 0, java.util.Optional.empty()));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new ReadResult(CardXrefRepository.BASE_DD_NAME, null, Outcome.OK,
                            java.util.Optional.of(RECORD), java.util.Optional.of(xrefImageOf(RECORD)), FileStatus.NORMAL, 0, java.util.Optional.empty()));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new ReadResult(CardXrefRepository.BASE_DD_NAME, FileStatus.OK,
                            null, java.util.Optional.of(RECORD), java.util.Optional.of(xrefImageOf(RECORD)), FileStatus.NORMAL, 0, java.util.Optional.empty()));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new ReadResult(CardXrefRepository.BASE_DD_NAME, FileStatus.OK,
                            Outcome.OK, null, java.util.Optional.of(xrefImageOf(RECORD)), FileStatus.NORMAL, 0, java.util.Optional.empty()));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new ReadResult(CardXrefRepository.BASE_DD_NAME, FileStatus.OK,
                            Outcome.OK, java.util.Optional.of(RECORD), java.util.Optional.of(xrefImageOf(RECORD)), FileStatus.NORMAL, 0, null));
            assertThatExceptionOfType(NullPointerException.class)
                    .as("the stored image is an Optional too: empty rather than null")
                    .isThrownBy(() -> new ReadResult(CardXrefRepository.BASE_DD_NAME, FileStatus.OK,
                            Outcome.OK, java.util.Optional.of(RECORD), null, FileStatus.NORMAL, 0,
                            java.util.Optional.empty()));
        }

        @Test
        @DisplayName("The stored image travels with the record and never without it, at its exact width")
        void enforcesTheStoredImageInvariant() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a record with no image to display would leave the caller re-encoding")
                    .isThrownBy(() -> new ReadResult(CardXrefRepository.BASE_DD_NAME, FileStatus.OK,
                            Outcome.OK, java.util.Optional.of(RECORD), java.util.Optional.empty(),
                            FileStatus.NORMAL, 0, java.util.Optional.empty()))
                    .withMessageContaining("must carry the stored image");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("and an image with no record to belong to has no meaning")
                    .isThrownBy(() -> new ReadResult(CardXrefRepository.BASE_DD_NAME,
                            FileStatus.NOT_FOUND, Outcome.NOT_FOUND, java.util.Optional.empty(),
                            java.util.Optional.of(xrefImageOf(RECORD)), FileStatus.NOTFND, 0,
                            java.util.Optional.empty()))
                    .withMessageContaining("carries no record");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a row that omitted CVACT03Y's trailing FILLER would render a line the program "
                            + "cannot produce, so a short image is refused rather than padded")
                    .isThrownBy(() -> new ReadResult(CardXrefRepository.BASE_DD_NAME, FileStatus.OK,
                            Outcome.OK, java.util.Optional.of(RECORD),
                            java.util.Optional.of(" ".repeat(CardXrefRecord.RECORD_LENGTH - 1)),
                            FileStatus.NORMAL, 0, java.util.Optional.empty()))
                    .withMessageContaining(String.valueOf(CardXrefRecord.RECORD_LENGTH));
        }

        @Test
        @DisplayName("A found or duplicate outcome must actually carry its record")
        void aCarryingOutcomeMustCarryItsRecord() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> xrefFound(CardXrefRepository.BASE_DD_NAME, null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> xrefDuplicate(CardXrefRepository.BASE_DD_NAME, null,
                            FileStatus.DUPREC));
        }

        @Test
        @DisplayName("A duplicate must name WHICH key duplicated - DUPREC or DUPKEY, nothing else")
        void aDuplicateMustNameWhichKeyDuplicated() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> xrefDuplicate(CardXrefRepository.BASE_DD_NAME, RECORD,
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
            assertThat(xrefFound(CardXrefRepository.BASE_DD_NAME, RECORD).statusImage())
                    .isEqualTo(FileStatus.toStatusImage(FileStatus.OK))
                    .hasSize(FileStatus.STATUS_IMAGE_LENGTH);
        }
    }

    @Nested
    @DisplayName("Risk R-E - a discovered column name, and statements built only from configuration")
    class DriverIndependence {
        @Test
        @DisplayName("The record image is read by column POSITION 1, and by no column name at all")
        void theRecordImageIsReadByPositionAndNeverByName() throws SQLException {
            ResultSet row = mock(ResultSet.class);
            when(row.next()).thenReturn(true, false);
            when(row.getString(CardXrefRepository.RECORD_IMAGE_COLUMN_INDEX))
                    .thenReturn(image(CARD_1, 50, 50L));

            assertThat(browseExtractorOver(row)).isNotNull();
            verify(row).getString(CardXrefRepository.RECORD_IMAGE_COLUMN_INDEX);
            verify(row, never()).getString(anyString());
        }

        @Test
        @DisplayName("An absent column value is surfaced, not swallowed, so the caller can classify it")
        void anAbsentColumnValueIsSurfaced() throws SQLException {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stubRows(jdbc, BASE_DS, Arrays.asList((String) null));

            try (BrowseCursor cursor = repository(jdbc).openBrowse()) {
                ReadResult result = cursor.readNext();

                assertThat(result.isOther()).isTrue();
                assertThat(result.isEndOfFile()).isFalse();
                assertThat(result.status()).isEqualTo(CardXrefRepository.PERMANENT_ERROR_STATUS);
            }
        }

        @Test
        @DisplayName("A keyed read stops at the duplicate-detection limit and maps each row by position")
        void aKeyedReadStopsAtItsLimitAndMapsByPosition() throws SQLException {
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
            ResultSet rows = mock(ResultSet.class);
            when(rows.next()).thenReturn(true, false);
            when(rows.getString(CardXrefRepository.RECORD_IMAGE_COLUMN_INDEX)).thenReturn(null);

            assertThat(keyedExtractorOver(rows)).containsExactly((Object) null);
        }

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
            repository.openBrowse().readNext();

            assertThat(backend.statementsSent()).containsExactly(
                    BASE_DESCRIBE_SQL, BASE_KEYED_SQL, ALT_DESCRIBE_SQL, ALT_KEYED_SQL,
                    BASE_DESCRIBE_SQL, BASE_BROWSE_SQL);
            assertThat(backend.statementsSent()).allSatisfy(sql -> assertThat(sql)
                    .contains("\"")
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

            assertThat(backend.patternsBound().get(0)).isEqualTo(CARD_1 + "%");
            assertThat(backend.patternsBound().get(1))
                    .isEqualTo("_".repeat(CardXrefRecord.XREF_ACCT_ID_OFFSET) + "00000000050%");
        }

        @Test
        @DisplayName("A LIKE metacharacter inside a key is escaped, so it matches only itself")
        void aLikeMetacharacterInAKeyIsEscaped() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            Backend backend = backend(jdbc);
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

            repository.readByCardNumber(CARD_1);
            repository.readByAccountIdViaAltIndex(50L);
            try (BrowseCursor cursor = repository.openBrowse()) {
                cursor.readNext();
                cursor.readNext();
            }

            assertThat(backend.statementsSent()).isNotEmpty();
            assertThat(backend.statementsSent()).allSatisfy(sql -> {
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

    @Nested
    @DisplayName("Gate G46 - both names resolved from carddemo.datasets, none written in Java")
    class ConfigurationBinding {
        private static final String BASE_DSNAME_KEY =
                "carddemo.datasets." + CardXrefRepository.BASE_DD_NAME + ".dsname";

        private static final String ALTERNATE_DSNAME_KEY =
                "carddemo.datasets." + CardXrefRepository.ALTERNATE_INDEX_DD_NAME + ".dsname";

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

                assertThat(base.base()).as("the base cluster declares no base of its own").isNull();
                assertThat(path.base()).isEqualTo(CardXrefRepository.BASE_DD_NAME);
                assertThat(path.alternateKey())
                        .isEqualTo(CardXrefRepository.EXPECTED_ALTERNATE_KEY_FIELD);
                assertThat(base.recordLength()).isEqualTo(CardXrefRepository.RECORD_LENGTH);
                assertThat(path.recordLength()).isEqualTo(CardXrefRepository.RECORD_LENGTH);
                assertThat(base.copybook()).isEqualTo(path.copybook());
                assertThat(base.recordLength()).isNotEqualTo(CardXrefRecord.FILLER_OFFSET);
            });
        }

        @Test
        @DisplayName("The test profile names US-ASCII explicitly; the platform default is never used")
        void theTestProfileNamesTheAsciiCodePageExplicitly() {
            shippedTestProfile().run(context -> {
                Charset injected =
                        context.getBean(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME, Charset.class);

                assertThat(context.getEnvironment()
                        .getProperty(CobolCharsetConfig.DATASET_CHARSET_PROPERTY))
                        .isEqualTo(StandardCharsets.US_ASCII.name());
                assertThat(injected).isEqualTo(ASCII);
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

                assertThat(repository.describeBaseStatement()).startsWith("SELECT ");
                assertThat(repository.describeAlternateIndexStatement()).startsWith("SELECT ");
                assertThat(repository.describeBaseStatement()).contains("WHERE 1 = 0");
                assertThat(repository.describeAlternateIndexStatement()).contains("WHERE 1 = 0");

                assertThat(repository.describeBaseStatement())
                        .contains(context.getEnvironment().getProperty(BASE_DSNAME_KEY));
                assertThat(repository.describeAlternateIndexStatement())
                        .contains(context.getEnvironment().getProperty(ALTERNATE_DSNAME_KEY));
                assertThat(repository.baseFileNameForCics())
                        .hasSize(CardXrefRepository.CICS_FILE_NAME_LENGTH)
                        .startsWith(CardXrefRepository.BASE_DD_NAME);
                assertThat(repository.alternateIndexFileNameForCics())
                        .hasSize(CardXrefRepository.CICS_FILE_NAME_LENGTH)
                        .startsWith(CardXrefRepository.ALTERNATE_INDEX_DD_NAME);
            });
        }
    }

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
            assertThat(rebound.describeBaseStatement())
                    .contains("TEST.XREF.BATCH")
                    .doesNotContain(BASE_DS);
            assertThat(rebound.describeAlternateIndexStatement())
                    .contains("TEST.XREF.BATCH.PATH")
                    .doesNotContain(ALT_DS);
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

    private static CardXrefRepository.ReadResult xrefFound(String ddName, CardXrefRecord record) {
        return CardXrefRepository.ReadResult.found(ddName, record, xrefImageOf(record));
    }

    private static CardXrefRepository.ReadResult xrefDuplicate(String ddName, CardXrefRecord first,
            int cicsResp) {
        return CardXrefRepository.ReadResult.duplicate(ddName, first, xrefImageOf(first), cicsResp);
    }

    private static String xrefImageOf(CardXrefRecord record) {
        return new String(record.encode(StandardCharsets.US_ASCII), StandardCharsets.US_ASCII);
    }
}
