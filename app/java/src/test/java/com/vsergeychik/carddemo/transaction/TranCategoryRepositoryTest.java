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
import java.util.Arrays;
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
 * {@link TranCategoryRepository} - the {@code TRANCATG} lookup, its 6-byte key, and the namesake it must
 * never accept.
 */
@DisplayName("TranCategoryRepository - the TRANCATG lookup and its 6-byte key")
class TranCategoryRepositoryTest {
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    private static final String DS = "TEST.M2.CARDDEMO.TRANCATG.VSAM.KSDS";

    private static final String DESCRIBED_COLUMN = "RECORD_IMAGE";

    private static final int DUPLICATE_DETECTION_LIMIT = 2;

    private static final String FIXTURE = "/fixtures/trancatg.txt";

    private static final int FIXTURE_RECORDS = 18;

    private static final int TRAN_REPORT_CAT_DESC_LENGTH = 29;

    private static final String INVALID_KEY_DISPLAY_LITERAL = "INVALID TRAN CATG KEY : ";

    private static final String OVERLONG_DESCRIPTION = "Convenience Check Debit Adjustment Reversal";

    private static final String BOUNDARY_DESCRIPTION = "Online purchase authorization";

    private static DatasetBinding validBinding() {
        return new DatasetBinding(DS, DatasetBinding.KSDS, false, "FB", null,
                TranCategoryRepository.RECORD_LENGTH, TranCategoryRepository.COPYBOOK,
                TranCategoryRepository.KEY_LENGTH, null, null, null);
    }

    private static DatasetBindings bindings(DatasetBinding binding) {
        DatasetBindings catalogue = new DatasetBindings();
        if (binding != null) {
            catalogue.put(TranCategoryRepository.DD_NAME, binding);
        }
        return catalogue;
    }

    private static TranCategoryRepository repository(JdbcTemplate jdbcTemplate) {
        return new TranCategoryRepository(jdbcTemplate, bindings(validBinding()), ASCII,
                RecordImageForm.CHARACTER);
    }

    private static String image(String tranTypeCd, int tranCatCd, String desc) {
        return TranCategoryRecord.of(tranTypeCd, tranCatCd, desc, ASCII).toImage();
    }

    private final Map<JdbcTemplate, Backend> backends = new LinkedHashMap<>();

    private static List<String> seedRows() {
        return List.of(image("01", 1, "Regular Sales Draft"),
                image("01", 2, "Cash Advance"),
                image("02", 1, "Refund"),
                image("05", 1, "Payment"));
    }

    private Backend backend(JdbcTemplate jdbcTemplate) {
        return backends.computeIfAbsent(jdbcTemplate, Backend::new);
    }

    private TranCategoryRepository seeded(List<String> rows) {
        JdbcTemplate template = mock(JdbcTemplate.class);
        backend(template).storing(rows);
        return repository(template);
    }

    private static final class Backend {
        private final List<String> stored = new ArrayList<>();

        private final List<String> statementsSent = new ArrayList<>();

        private final List<String> patternsBound = new ArrayList<>();

        private boolean failing;

        private boolean failingOnRead;

        private boolean withoutMetadata;

        private boolean withoutColumn;

        private boolean blankColumnName;

        private boolean yieldingNothing;

        private boolean failingOnProbe;

        private boolean probeYieldingNothing;

        private boolean unreadableRowsMatchKeyedReads;

        private final List<String> preparedSql = new ArrayList<>();

        private final List<String> boundOperands = new ArrayList<>();

        private ResultSet described;

        private Connection connection;

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

        Backend failingOnProbe() {
            failingOnProbe = true;
            return this;
        }

        Backend probeYieldingNothing() {
            probeYieldingNothing = true;
            return this;
        }

        Backend presentingUnreadableRowsToKeyedReads() {
            unreadableRowsMatchKeyedReads = true;
            return this;
        }

        List<String> statementsSent() {
            return List.copyOf(statementsSent);
        }

        List<String> patternsBound() {
            return List.copyOf(patternsBound);
        }

        private Object describe(InvocationOnMock invocation) throws SQLException {
            statementsSent.add(invocation.getArgument(0));
            requireReachable();
            ResultSetExtractor<?> extractor = invocation.getArgument(1);
            return extractor.extractData(describedResultSet());
        }

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

        private Object keyedRead(InvocationOnMock invocation) throws SQLException {
            PreparedStatementCreator creator = invocation.getArgument(0);
            preparedSql.clear();
            boundOperands.clear();
            creator.createPreparedStatement(connection());

            String statement = preparedSql.get(0);
            statementsSent.add(statement);
            requireReachable();
            if (isUnreadableRowProbe(statement)) {
                if (failingOnProbe) {
                    throw new DataAccessResourceFailureException(
                            "the unreadable-row probe cannot be answered");
                }
                if (probeYieldingNothing) {
                    return null;
                }
                ResultSetExtractor<?> probeExtractor = invocation.getArgument(1);
                return probeExtractor.extractData(rowsResultSet(unreadableRows()));
            }
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

        private List<String> matching(String pattern) {
            Pattern matcher = likeAsRegex(pattern);
            List<String> matches = new ArrayList<>();
            for (String row : stored) {
                if (matches.size() == DUPLICATE_DETECTION_LIMIT) {
                    break;
                }
                if (row == null ? unreadableRowsMatchKeyedReads : matcher.matcher(row).matches()) {
                    matches.add(row);
                }
            }
            return matches;
        }

        private List<String> unreadableRows() {
            List<String> unreadable = new ArrayList<>(1);
            for (String row : stored) {
                if (row == null) {
                    unreadable.add(null);
                    break;
                }
            }
            return unreadable;
        }

        private static boolean isUnreadableRowProbe(String statement) {
            return statement.endsWith(" IS NULL");
        }

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

    @Nested
    @DisplayName("The three COBOL name collisions, documented and asserted (practice B4)")
    class CobolNameCollisions {
        @Test
        @DisplayName("collision 1: the group is still called TRAN-CAT-KEY, and it is 6 bytes not 17")
        void collisionOneTheGroupNameIsCarriedThroughVerbatim() {
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
            assertThat(TranCategoryRecord.TRAN_TYPE_CD.name()).isEqualTo("TRAN-TYPE-CD");
            assertThat(TranCategoryRecord.TRAN_CAT_CD.name()).isEqualTo("TRAN-CAT-CD");

            assertThat(TranCategoryRecord.TRAN_TYPE_CD.length()).isEqualTo(2);
            assertThat(TranCategoryRecord.TRAN_CAT_CD.length()).isEqualTo(4);
        }

        @Test
        @DisplayName("collision 3: CVTRA03Y names its analogous key TRAN-TYPE, without the -CD suffix")
        void collisionThreeTheSiblingLookupDropsTheCdSuffix() {
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

            assertThat(repository.open()).isEqualTo(FileStatus.OK);
            assertThat(repository.close()).isEqualTo(FileStatus.OK);
            assertThat(repository.open())
                    .as("a closed dataset re-opens cleanly, exactly as the first open did")
                    .isEqualTo(FileStatus.OK);

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

            assertThatNoException()
                    .isThrownBy(() -> repository.readByKey("99", 9999));

            ReadResult result = repository.readByKey("99", 9999);

            assertThat(result.isNotFound()).isTrue();
            assertThat(result.status()).isEqualTo(FileStatus.NOT_FOUND);
            assertThat(result.outcome()).isEqualTo(Outcome.NOT_FOUND);
            assertThat(result.record()).isEmpty();
            assertThat(result.diagnostic()).isEmpty();
            assertThat(result.abends()).isTrue();
            assertThat(result.statusImage()).isEqualTo("0023");
        }

        @Test
        @DisplayName("a matched row carrying no record image is reported, never treated as absent")
        void aMatchedRowWithNoRecordImageIsReported() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            List<String> withUnreadable = new ArrayList<>();
            withUnreadable.add(null);
            backend(template).storing(withUnreadable).presentingUnreadableRowsToKeyedReads();

            ReadResult result = repository(template).readByKey("01", 1);

            assertThat(result.isNotFound())
                    .as("there IS a record; it simply cannot be read")
                    .isFalse();
            assertThat(result.isOther()).isTrue();
            assertThat(result.status()).isEqualTo(TranCategoryRepository.PERMANENT_ERROR_STATUS);
        }

        @Test
        @DisplayName("finding DB-05: an unreadable row is not reported as INVALID KEY")
        void anUnreadableRowIsNotReportedAsAbsent() {
            List<String> withUnreadable = new ArrayList<>();
            withUnreadable.add(image("01", 1, "Regular Sales Draft"));
            withUnreadable.add(null);

            ReadResult result = seeded(withUnreadable).readByKey("99", 9999);

            assertThat(result.isNotFound())
                    .as("the unreadable row's key cannot be known, so no absence can be asserted")
                    .isFalse();
            assertThat(result.isOther()).isTrue();
            assertThat(result.status()).isEqualTo(TranCategoryRepository.PERMANENT_ERROR_STATUS);
        }

        @Test
        @DisplayName("finding DB-05: a genuinely absent key still reports '23'")
        void aGenuinelyAbsentKeyIsStillNotFound() {
            assertThat(seeded(List.of(image("01", 1, "Regular Sales Draft")))
                    .readByKey("99", 9999).status()).isEqualTo(FileStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("finding DB-05: a read that found its record is unaffected by an unreadable row")
        void aFoundRecordIsUnaffectedByAnUnreadableRowElsewhere() {
            List<String> withUnreadable = new ArrayList<>();
            withUnreadable.add(image("01", 1, "Regular Sales Draft"));
            withUnreadable.add(null);

            assertThat(seeded(withUnreadable).readByKey("01", 1).isFound()).isTrue();
        }

        @Test
        @DisplayName("finding DB-05: a refused probe is reported rather than reported as absent")
        void aRefusedProbeIsReportedRatherThanAssumedAbsent() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            backend(template).storing(List.of(image("01", 1, "Regular Sales Draft")))
                    .failingOnProbe();

            ReadResult result = repository(template).readByKey("99", 9999);

            assertThat(result.isNotFound())
                    .as("the probe established nothing, so the absence stays unproved")
                    .isFalse();
            assertThat(result.status()).isEqualTo(TranCategoryRepository.PERMANENT_ERROR_STATUS);
            assertThat(result.diagnostic())
                    .as("the driver's own diagnosis reaches the caller")
                    .isPresent();
        }

        @Test
        @DisplayName("finding DB-05: a probe answering with no result object is reported, not absent")
        void aProbeYieldingNoResultObjectIsReportedRatherThanAbsent() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            backend(template).storing(List.of(image("01", 1, "Regular Sales Draft")))
                    .probeYieldingNothing();

            ReadResult result = repository(template).readByKey("99", 9999);

            assertThat(result.isNotFound())
                    .as("nothing came back, so nothing was established")
                    .isFalse();
            assertThat(result.status()).isEqualTo(TranCategoryRepository.PERMANENT_ERROR_STATUS);
        }

        @Test
        @DisplayName("finding DB-05: the probe runs only on the not-found path, and binds no key")
        void theProbeRunsOnlyOnTheNotFoundPathAndBindsNoKey() {
            JdbcTemplate found = mock(JdbcTemplate.class);
            backend(found).storing(List.of(image("01", 1, "Regular Sales Draft")));
            JdbcTemplate missing = mock(JdbcTemplate.class);
            backend(missing).storing(List.of(image("01", 1, "Regular Sales Draft")));

            repository(found).readByKey("01", 1);
            repository(missing).readByKey("99", 9999);

            assertThat(backend(found).statementsSent())
                    .as("a successful read pays for no probe")
                    .noneMatch(sent -> sent.endsWith(" IS NULL"));
            assertThat(backend(missing).statementsSent())
                    .as("the not-found path proves the absence before reporting it")
                    .anyMatch(sent -> sent.endsWith(" IS NULL"));
            assertThat(backend(missing).patternsBound())
                    .as("the probe names no key: only the keyed read binds a pattern")
                    .hasSize(1);
        }

        @Test
        @DisplayName("the three arms are mutually exclusive: 'other' is neither '00' nor '23'")
        void theInducedFailureStatusIsDistinctFromBothNamedStatuses() {
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

        private static List<String> fixtureRows() {
            try (InputStream source = TranCategoryRepositoryTest.class.getResourceAsStream(FIXTURE)) {
                assertThat(source).as("%s must be on the test classpath", FIXTURE).isNotNull();
                return new String(source.readAllBytes(), ASCII).lines().toList();
            } catch (IOException unreadable) {
                throw new UncheckedIOException("Could not read " + FIXTURE, unreadable);
            }
        }
    }

    @Nested
    @DisplayName("The X(50)-into-X(29) report projection, and the trailing FILLER")
    class ReportProjection {
        private final FixedWidthCodec codec = new FixedWidthCodec(ASCII);

        @Test
        @DisplayName("a description longer than 29 characters keeps all 50, and projects to its first 29")
        void anOverlongDescriptionIsTruncatedOnlyAtThePointOfUse() {
            TranCategoryRepository repository =
                    seeded(List.of(image("09", 7, OVERLONG_DESCRIPTION)));

            TranCategoryRecord record = repository.readByKey("09", 7).record().orElseThrow();
            String description = record.tranCatTypeDesc();

            assertThat(OVERLONG_DESCRIPTION.length())
                    .as("the constructed description really does cross the boundary")
                    .isGreaterThan(TRAN_REPORT_CAT_DESC_LENGTH);
            assertThat(description)
                    .hasSize(TranCategoryRecord.TRAN_CAT_TYPE_DESC_LENGTH)
                    .startsWith(OVERLONG_DESCRIPTION)
                    .endsWith(" ");

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
                boolean safelyPublished = Modifier.isVolatile(field.getModifiers())
                        && isDeeplyImmutable(field.getType());
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

        private static boolean isDeeplyImmutable(Class<?> type) {
            if (type == String.class || type.isPrimitive()) {
                return true;
            }
            if (!type.isRecord()) {
                return false;
            }
            return Arrays.stream(type.getRecordComponents())
                    .allMatch(component -> isDeeplyImmutable(component.getType()));
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

        private static String repositorySource() {
            return mainSource("transaction/TranCategoryRepository.java");
        }

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
