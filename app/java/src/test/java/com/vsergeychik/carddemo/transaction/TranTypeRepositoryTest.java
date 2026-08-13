package com.vsergeychik.carddemo.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
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
import com.vsergeychik.carddemo.common.DatasetRelation.BackendDiagnostic;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FileStatus.Outcome;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.transaction.TranTypeRepository.ReadResult;
import com.vsergeychik.carddemo.transaction.model.TranTypeRecord;

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
import java.util.Arrays;
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
 * {@link TranTypeRepository} - the {@code TRANTYPE} lookup: 60 bytes, a two-character key, and a
 * description that must never be trimmed.
 */
@DisplayName("TranTypeRepository - the TRANTYPE lookup, its X(02) key and its untrimmed description")
class TranTypeRepositoryTest {
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    private static final String DS = "TEST.M2.CARDDEMO.TRANTYPE.VSAM.KSDS";

    private static final String DESCRIBED_COLUMN = "RECORD_IMAGE";

    private static final int DUPLICATE_DETECTION_LIMIT = 2;

    private static final String FIXTURE = "/fixtures/trantype.txt";

    private static final int FIXTURE_RECORDS = 7;

    private static final String FIXTURE_FILLER = "00000000";

    private static final String SPACE_FILLER = " ".repeat(TranTypeRecord.FILLER_LENGTH);

    private static DatasetBinding validBinding() {
        return new DatasetBinding(DS, DatasetBinding.KSDS, false, "FB", null,
                TranTypeRepository.RECORD_LENGTH, TranTypeRepository.EXPECTED_COPYBOOK,
                TranTypeRepository.TRAN_TYPE_KEY_LENGTH, null, null, null);
    }

    private static DatasetBindings bindings(DatasetBinding binding) {
        DatasetBindings catalogue = new DatasetBindings();
        if (binding != null) {
            catalogue.put(TranTypeRepository.DD_NAME, binding);
        }
        return catalogue;
    }

    private static TranTypeRepository repository(JdbcTemplate jdbcTemplate) {
        return new TranTypeRepository(jdbcTemplate, bindings(validBinding()), ASCII,
                RecordImageForm.CHARACTER);
    }

    private static String image(String tranType, String tranTypeDesc) {
        return TranTypeRecord.of(tranType, tranTypeDesc, ASCII).image();
    }

    private static List<String> fixtureRows() {
        try (InputStream source = TranTypeRepositoryTest.class.getResourceAsStream(FIXTURE)) {
            assertThat(source).as("%s must be on the test classpath", FIXTURE).isNotNull();
            return new String(source.readAllBytes(), ASCII).lines().toList();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("Could not read " + FIXTURE, unreadable);
        }
    }

    private final Map<JdbcTemplate, Backend> backends = new LinkedHashMap<>();

    private Backend backend(JdbcTemplate jdbcTemplate) {
        return backends.computeIfAbsent(jdbcTemplate, Backend::new);
    }

    private TranTypeRepository seeded(List<String> rows) {
        JdbcTemplate template = mock(JdbcTemplate.class);
        backend(template).storing(rows);
        return repository(template);
    }

    private TranTypeRepository seededWithFixture() {
        return seeded(fixtureRows());
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
                    .thenReturn(withoutColumn ? 0 : TranTypeRepository.RECORD_IMAGE_COLUMN_INDEX);
            when(metaData.getColumnName(TranTypeRepository.RECORD_IMAGE_COLUMN_INDEX))
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

        private static ResultSet rowsResultSet(List<String> rows) throws SQLException {
            ResultSet resultSet = mock(ResultSet.class);
            int[] cursor = {-1};
            when(resultSet.next()).thenAnswer(call -> ++cursor[0] < rows.size());
            when(resultSet.getString(TranTypeRepository.RECORD_IMAGE_COLUMN_INDEX))
                    .thenAnswer(call -> rows.get(cursor[0]));
            return resultSet;
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
    @DisplayName("TRAN-TYPE is a String, not an int: PIC X(02) is alphanumeric")
    class KeyIsCharacterData {
        @Test
        @DisplayName("the lookup takes a String, and offers no numeric overload to fall into")
        void theKeyIsDeclaredAsCharacterData() throws NoSuchMethodException {
            Method lookup = TranTypeRepository.class.getMethod("readByTranType", String.class);

            assertThat(lookup.getParameterTypes())
                    .as("app/cpy/CVTRA03Y.cpy:5 is TRAN-TYPE PIC X(02) - alphanumeric - and "
                            + "app/cbl/CBTRN03C.cbl:189 MOVEs a PIC X(02) item into the PIC X(02) "
                            + "FD-TRAN-TYPE, so the key travels as characters end to end")
                    .containsExactly(String.class);
            assertThat(lookup.getReturnType()).isEqualTo(ReadResult.class);

            List<String> numericOverloads = new ArrayList<>();
            for (Method method : TranTypeRepository.class.getMethods()) {
                if (!"readByTranType".equals(method.getName())) {
                    continue;
                }
                for (Class<?> parameterType : method.getParameterTypes()) {
                    if (parameterType == int.class || parameterType == long.class
                            || parameterType == Integer.class || parameterType == Long.class) {
                        numericOverloads.add(method.toString());
                    }
                }
            }
            assertThat(numericOverloads)
                    .as("an int overload would render key 1 rather than \"01\" and miss every "
                            + "record in the dataset, so there must be no such door to walk through")
                    .isEmpty();
        }

        @Test
        @DisplayName("the record's key accessors are Strings too, so the round trip never narrows")
        void theRecordExposesTheKeyAsCharacterData() throws NoSuchMethodException {
            assertThat(TranTypeRecord.class.getMethod("tranType").getReturnType())
                    .isEqualTo(String.class);
            assertThat(TranTypeRecord.class.getMethod("tranTypeKey").getReturnType())
                    .as("the key a lookup passes and the key a record reports are the same two "
                            + "characters, so their types have to line up exactly")
                    .isEqualTo(String.class);
        }

        @ParameterizedTest(name = "key \"{0}\" resolves to \"{1}\"")
        @CsvSource({"01,Purchase", "02,Payment", "03,Credit", "04,Authorization", "05,Refund",
                    "06,Reversal", "07,Adjustment"})
        @DisplayName("every leading-zero key in the shipped data resolves, exactly as written")
        void everyShippedKeyResolvesAsWritten(String tranType, String description) {
            TranTypeRepository repository = seededWithFixture();

            ReadResult result = repository.readByTranType(tranType);

            assertThat(result.isFound())
                    .as("key %s is one of the seven the shipped fixture holds", tranType)
                    .isTrue();
            assertThat(result.status()).isEqualTo(FileStatus.OK);
            assertThat(result.record().orElseThrow().tranType()).isEqualTo(tranType);
            assertThat(result.record().orElseThrow().tranTypeDesc().stripTrailing())
                    .isEqualTo(description);
        }

        @Test
        @DisplayName("\"1\" does NOT find type 01: a PIC X move pads on the RIGHT")
        void aNumericallyNormalisedKeyFindsNothing() {
            TranTypeRepository repository = seededWithFixture();

            ReadResult found = repository.readByTranType("01");
            ReadResult missed = repository.readByTranType("1");

            assertThat(found.isFound())
                    .as("the key as the copybook spells it")
                    .isTrue();
            assertThat(missed.isNotFound())
                    .as("\"1\" is what an int-modelled key renders. A PIC X(02) receiver pads it on "
                            + "the RIGHT to \"1 \", never on the left to \"01\", so it names no "
                            + "record - and this suite would rather see that stated than discovered")
                    .isTrue();
            assertThat(missed.keyImage())
                    .as("the bytes the read actually used, which app/cbl/CBTRN03C.cbl:497 displays")
                    .isEqualTo("1 ")
                    .isNotEqualTo("01")
                    .isNotEqualTo(" 1");
            assertThat(missed.status()).isEqualTo(FileStatus.NOT_FOUND);
        }

        @ParameterizedTest(name = "\"{0}\" is reshaped to a 2-character key image")
        @CsvSource(value = {"01|01", "1|1 ", "|  ", "7|7 ", "0123|01", "AB|AB"},
                   delimiter = '|', ignoreLeadingAndTrailingWhitespace = false)
        @DisplayName("the key image is always exactly 2 characters: right-padded, right-truncated")
        void theKeyImageIsAlwaysTwoCharacters(String supplied, String expectedImage) {
            TranTypeRepository repository = seeded(List.of());

            ReadResult result = repository.readByTranType(supplied == null ? "" : supplied);

            assertThat(result.keyImage())
                    .as("FD-TRAN-TYPE at app/cbl/CBTRN03C.cbl:75 is PIC X(02) and always holds "
                            + "exactly two characters, whatever was moved into it")
                    .hasSize(TranTypeRepository.TRAN_TYPE_KEY_LENGTH)
                    .isEqualTo(expectedImage == null ? "  " : expectedImage);
        }

        @Test
        @DisplayName("the composed predicate is confined to the key's own two bytes at offset 0")
        void thePredicateMatchesOnlyTheKeySpan() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            Backend stub = backend(template).storing(fixtureRows());
            TranTypeRepository repository = repository(template);

            ReadResult result = repository.readByTranType("04");

            assertThat(result.isFound()).isTrue();
            assertThat(stub.patternsBound())
                    .as("two characters at offset 0, then anything: the comparison VSAM performs on "
                            + "RECORD KEY IS FD-TRAN-TYPE (app/cbl/CBTRN03C.cbl:42)")
                    .containsExactly("04%");
            assertThat(repository.keyedPredicatePattern("04"))
                    .as("no leading wildcard, because the key begins the record at offset "
                            + TranTypeRepository.TRAN_TYPE_KEY_OFFSET)
                    .isEqualTo("04%")
                    .doesNotStartWith("_");
        }

        @Test
        @DisplayName("a key whose text appears inside another record's description is not confused")
        void aDescriptionThatLooksLikeAKeyDoesNotMatch() {
            TranTypeRepository repository = seeded(List.of(
                    image("09", "Nine"),
                    image("10", "01 looks like a key but is not one")));

            ReadResult result = repository.readByTranType("01");

            assertThat(result.isNotFound())
                    .as("a predicate that scanned the whole image rather than the key span would "
                            + "return the second record here, and the report would name the wrong "
                            + "transaction type")
                    .isTrue();
        }

        @Test
        @DisplayName("a null key is a defect in the caller, not a lookup that misses")
        void aNullKeyIsRejected() {
            TranTypeRepository repository = seededWithFixture();

            assertThatNullPointerException()
                    .isThrownBy(() -> repository.readByTranType(null))
                    .withMessageContaining(TranTypeRecord.TRAN_TYPE_FIELD);
        }

        @Test
        @DisplayName("the copybook name is TRAN-TYPE, never harmonised to TRAN-TYPE-CD (practice B4)")
        void theFieldNameIsNeverHarmonised() {
            assertThat(TranTypeRecord.TRAN_TYPE_FIELD)
                    .as("CVTRA03Y names its key TRAN-TYPE while CVTRA04Y and CVTRA05Y both name "
                            + "theirs TRAN-TYPE-CD - which is exactly why app/cbl/CBTRN03C.cbl:189 "
                            + "has to write TRAN-TYPE-CD OF TRAN-RECORD when it qualifies. Renaming "
                            + "either one would break field-for-field diffing")
                    .isEqualTo("TRAN-TYPE")
                    .isNotEqualTo("TRAN-TYPE-CD");
            assertThat(TranTypeRecord.TRAN_TYPE.name()).isEqualTo("TRAN-TYPE");
            assertThat(TranTypeRecord.TRAN_TYPE_DESC.name()).isEqualTo("TRAN-TYPE-DESC");
        }
    }

    @Nested
    @DisplayName("TRAN-TYPE-DESC decodes untrimmed: all 50 bytes, padding included")
    class UntrimmedDescription {
        @Test
        @DisplayName("the description comes back at its full 50 characters, trailing spaces kept")
        void theDescriptionIsFiftyCharacters() {
            TranTypeRepository repository = seededWithFixture();

            TranTypeRecord record = repository.readByTranType("01").record().orElseThrow();

            assertThat(record.tranTypeDesc())
                    .as("TRAN-TYPE-DESC is PIC X(50) and the padding is part of the field's value")
                    .hasSize(TranTypeRecord.TRAN_TYPE_DESC_LENGTH)
                    .isEqualTo("Purchase" + " ".repeat(42))
                    .endsWith(" ");
            assertThat(record.tranTypeDesc().stripTrailing())
                    .as("the underlying text, for the reader's orientation only - nothing in the "
                            + "report is derived from a stripped value")
                    .isEqualTo("Purchase");
        }

        @ParameterizedTest(name = "type {0} reports \"{1}\" in a PIC X(15) receiver")
        @CsvSource(value = {"01|Purchase       ", "02|Payment        ", "03|Credit         ",
                            "04|Authorization  ", "05|Refund         ", "06|Reversal       ",
                            "07|Adjustment     "},
                   delimiter = '|', ignoreLeadingAndTrailingWhitespace = false)
        @DisplayName("the report field is the FIRST 15 of the 50, exactly as CBTRN03C:366 moves it")
        void theReportFieldIsTheFirstFifteenCharacters(String tranType, String reportField) {
            TranTypeRepository repository = seededWithFixture();

            TranTypeRecord record = repository.readByTranType(tranType).record().orElseThrow();

            assertThat(reportField)
                    .as("the expectation itself must be 15 characters, or it is not what "
                            + "app/cpy/CVTRA07Y.cpy:22 declares")
                    .hasSize(TranTypeRecord.REPORT_TYPE_DESC_LENGTH);
            assertThat(record.tranTypeDescMovedTo(TranTypeRecord.REPORT_TYPE_DESC_LENGTH))
                    .as("MOVE TRAN-TYPE-DESC TO TRAN-REPORT-TYPE-DESC, "
                            + "app/cbl/CBTRN03C.cbl:366, into PIC X(15)")
                    .isEqualTo(reportField);
            assertThat(record.tranTypeDesc()
                            .substring(0, TranTypeRecord.REPORT_TYPE_DESC_LENGTH))
                    .as("and taking the first 15 characters of the untrimmed 50 gives the same "
                            + "answer, which is the whole reason the 50 are kept")
                    .isEqualTo(reportField);
        }

        @Test
        @DisplayName("a trimmed description could not be truncated to 15 at all - the counterfactual")
        void aTrimmedDescriptionCannotReproduceTheMove() {
            TranTypeRepository repository = seededWithFixture();
            TranTypeRecord record = repository.readByTranType("01").record().orElseThrow();

            String asStored = record.tranTypeDesc();
            String asItWouldBeIfTrimmed = asStored.strip();

            assertThat(asStored).hasSize(TranTypeRecord.TRAN_TYPE_DESC_LENGTH);
            assertThat(asItWouldBeIfTrimmed)
                    .as("every one of the seven shipped descriptions is shorter than the 15-character "
                            + "report receiver, so a trimmed value has nothing to truncate")
                    .hasSizeLessThan(TranTypeRecord.REPORT_TYPE_DESC_LENGTH);
            assertThatExceptionOfType(StringIndexOutOfBoundsException.class)
                    .as("this is the observable failure a trimming repository would cause: the "
                            + "15-character truncation the report performs is not even expressible")
                    .isThrownBy(() -> asItWouldBeIfTrimmed
                            .substring(0, TranTypeRecord.REPORT_TYPE_DESC_LENGTH));
        }

        @Test
        @DisplayName("a LEADING space survives, so trimming would land on different report bytes")
        void aLeadingSpaceMakesTheDifferenceObservable() {
            TranTypeRepository repository = seeded(List.of(image("08", " Refund")));

            TranTypeRecord record = repository.readByTranType("08").record().orElseThrow();
            String faithful = record.tranTypeDescMovedTo(TranTypeRecord.REPORT_TYPE_DESC_LENGTH);
            String ifTrimmed = new FixedWidthCodec(ASCII).movePicX(record.tranTypeDesc().strip(),
                    TranTypeRecord.REPORT_TYPE_DESC_LENGTH);

            assertThat(faithful)
                    .as("the leading space occupies the first byte of TRAN-REPORT-TYPE-DESC")
                    .isEqualTo(" Refund        ")
                    .hasSize(TranTypeRecord.REPORT_TYPE_DESC_LENGTH);
            assertThat(ifTrimmed)
                    .as("what a trimming repository would produce - fifteen characters, and every "
                            + "one of them in the wrong column")
                    .isEqualTo("Refund         ");
            assertThat(faithful)
                    .as("two different report lines from one stored record: the difference a "
                            + "trimming read would introduce, made observable")
                    .isNotEqualTo(ifTrimmed);
        }

        @Test
        @DisplayName("a description longer than 15 is truncated on the right, never the left")
        void anOverlongDescriptionTruncatesOnTheRight() {
            TranTypeRepository repository =
                    seeded(List.of(image("08", "Authorization reversal adjustment")));

            TranTypeRecord record = repository.readByTranType("08").record().orElseThrow();

            assertThat(record.tranTypeDesc())
                    .hasSize(TranTypeRecord.TRAN_TYPE_DESC_LENGTH)
                    .startsWith("Authorization reversal adjustment");
            assertThat(record.tranTypeDescMovedTo(TranTypeRecord.REPORT_TYPE_DESC_LENGTH))
                    .as("COBOL fills an alphanumeric receiver from its leftmost position and "
                            + "discards the overflow")
                    .isEqualTo("Authorization r");
        }

        @Test
        @DisplayName("the receiver width is CVTRA07Y's, and this repository states none of its own")
        void theReceiverWidthComesFromTheReportCopybook() {
            assertThat(TranTypeRecord.REPORT_TYPE_DESC_LENGTH)
                    .as("app/cpy/CVTRA07Y.cpy:22 - TRAN-REPORT-TYPE-DESC PIC X(15)")
                    .isEqualTo(15);
            assertThat(TranTypeRecord.REPORT_TYPE_DESC_LENGTH)
                    .as("the receiver is narrower than the sender, which is why the move truncates")
                    .isLessThan(TranTypeRecord.TRAN_TYPE_DESC_LENGTH);

            List<String> reportGeometry = new ArrayList<>();
            for (Field field : TranTypeRepository.class.getDeclaredFields()) {
                if (field.getName().toLowerCase(Locale.ROOT).contains("report")) {
                    reportGeometry.add(field.getName());
                }
            }
            assertThat(reportGeometry)
                    .as("the repository decodes and hands over the record; it formats nothing, so no "
                            + "report geometry belongs on it")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("The 60-byte image: 2 + 50 + 8, FILLER included")
    class SixtyByteImage {
        @Test
        @DisplayName("gate G19: the width is 60, re-derived by addition and never restated")
        void theWidthIsProvedByAddition() {
            int derived = TranTypeRecord.TRAN_TYPE_LENGTH
                    + TranTypeRecord.TRAN_TYPE_DESC_LENGTH
                    + TranTypeRecord.FILLER_LENGTH;

            assertThat(TranTypeRecord.TRAN_TYPE_LENGTH)
                    .as("app/cpy/CVTRA03Y.cpy:5 - TRAN-TYPE PIC X(02)")
                    .isEqualTo(2);
            assertThat(TranTypeRecord.TRAN_TYPE_DESC_LENGTH)
                    .as("app/cpy/CVTRA03Y.cpy:6 - TRAN-TYPE-DESC PIC X(50)")
                    .isEqualTo(50);
            assertThat(TranTypeRecord.FILLER_LENGTH)
                    .as("app/cpy/CVTRA03Y.cpy:7 - FILLER PIC X(08)")
                    .isEqualTo(8);
            assertThat(derived)
                    .as("2 + 50 + 8 = 60, which is the copybook's own \"RECLN = 60\" header comment, "
                            + "and which app/cbl/CBTRN03C.cbl:73-75 confirms independently by "
                            + "splitting the same record as FD-TRAN-TYPE X(02) + FD-TRAN-DATA X(58)")
                    .isEqualTo(60)
                    .isEqualTo(TranTypeRepository.RECORD_LENGTH)
                    .isEqualTo(TranTypeRecord.RECORD_LENGTH);
            assertThat(TranTypeRecord.verifyDeclaredWidth())
                    .as("and the model's own self-check agrees, so a transcription error cannot "
                            + "reach a caller")
                    .isEqualTo(derived);
        }

        @Test
        @DisplayName("the three spans are contiguous from offset 0, with no gap and no overlap")
        void theSpansAreContiguous() {
            assertThat(TranTypeRecord.TRAN_TYPE_OFFSET)
                    .as("TRAN-TYPE begins the record")
                    .isZero();
            assertThat(TranTypeRecord.TRAN_TYPE_OFFSET + TranTypeRecord.TRAN_TYPE_LENGTH)
                    .as("0 + 2 = 2, where TRAN-TYPE-DESC begins")
                    .isEqualTo(TranTypeRecord.TRAN_TYPE_DESC_OFFSET);
            assertThat(TranTypeRecord.TRAN_TYPE_DESC_OFFSET + TranTypeRecord.TRAN_TYPE_DESC_LENGTH)
                    .as("2 + 50 = 52, where the trailing FILLER begins")
                    .isEqualTo(TranTypeRecord.FILLER_OFFSET);
            assertThat(TranTypeRecord.FILLER_OFFSET + TranTypeRecord.FILLER_LENGTH)
                    .as("52 + 8 = 60, the end of the record")
                    .isEqualTo(TranTypeRecord.RECORD_LENGTH);

            int total = 0;
            for (FieldSpan span : TranTypeRecord.LAYOUT.storageSpans()) {
                assertThat(span.offset())
                        .as("span %s must begin where the previous one ended", span.name())
                        .isEqualTo(total);
                total += span.length();
            }
            assertThat(total)
                    .as("gate G21: the storage spans sum to the declared width, so a dropped FILLER "
                            + "fails here and not silently in the data")
                    .isEqualTo(TranTypeRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("gate G21: FILLER X(08) sits at offset 52 and is space-filled on a built record")
        void theFillerIsPresentAndSpaceFilledWhenBuilt() {
            TranTypeRepository repository = seeded(List.of(image("01", "Purchase")));

            TranTypeRecord record = repository.readByTranType("01").record().orElseThrow();

            assertThat(TranTypeRecord.FILLER.kind())
                    .as("a reserved span, declared as one rather than as a named field")
                    .isEqualTo(PictureKind.FILLER);
            assertThat(TranTypeRecord.FILLER.offset()).isEqualTo(52);
            assertThat(TranTypeRecord.FILLER.length()).isEqualTo(8);
            assertThat(record.filler())
                    .as("CVTRA03Y declares no VALUE for the span, so a record initialised from the "
                            + "layout carries eight spaces - the COBOL INITIALIZE convention")
                    .isEqualTo(SPACE_FILLER)
                    .hasSize(TranTypeRecord.FILLER_LENGTH);
            assertThat(record.image().substring(TranTypeRecord.FILLER_OFFSET))
                    .as("and it is emitted, not omitted: without it the image would be 52 bytes and "
                            + "every downstream offset would be wrong")
                    .isEqualTo(SPACE_FILLER);
            assertThat(record.image()).hasSize(TranTypeRecord.RECORD_LENGTH);
        }

        @ParameterizedTest(name = "record {0} decomposes at absolute offsets 0, 2 and 52")
        @ValueSource(strings = {"01", "02", "03", "04", "05", "06", "07"})
        @DisplayName("rule R5: the image is addressed by ABSOLUTE offset, and every span agrees")
        void theImageIsAddressedByAbsoluteOffset(String tranType) {
            TranTypeRepository repository = seededWithFixture();

            TranTypeRecord record = repository.readByTranType(tranType).record().orElseThrow();
            String stored = record.image();

            assertThat(stored).hasSize(TranTypeRecord.RECORD_LENGTH);
            assertThat(stored.substring(TranTypeRecord.TRAN_TYPE_OFFSET,
                            TranTypeRecord.TRAN_TYPE_OFFSET + TranTypeRecord.TRAN_TYPE_LENGTH))
                    .as("bytes 0-1 are TRAN-TYPE")
                    .isEqualTo(record.tranType());
            assertThat(stored.substring(TranTypeRecord.TRAN_TYPE_DESC_OFFSET,
                            TranTypeRecord.TRAN_TYPE_DESC_OFFSET
                                    + TranTypeRecord.TRAN_TYPE_DESC_LENGTH))
                    .as("bytes 2-51 are TRAN-TYPE-DESC, all fifty of them")
                    .isEqualTo(record.tranTypeDesc());
            assertThat(stored.substring(TranTypeRecord.FILLER_OFFSET,
                            TranTypeRecord.FILLER_OFFSET + TranTypeRecord.FILLER_LENGTH))
                    .as("bytes 52-59 are the reserved FILLER")
                    .isEqualTo(record.filler());
            assertThat(record.tranTypeBytes())
                    .isEqualTo(record.tranType().getBytes(ASCII));
            assertThat(record.tranTypeDescBytes())
                    .as("the raw span, for a caller diffing byte for byte")
                    .hasSize(TranTypeRecord.TRAN_TYPE_DESC_LENGTH)
                    .isEqualTo(record.tranTypeDesc().getBytes(ASCII));
        }

        @Test
        @DisplayName("the shipped fixture's FILLER is eight ZEROS, and is carried across verbatim")
        void theFixtureFillerIsPreservedVerbatim() {
            TranTypeRepository repository = seededWithFixture();

            TranTypeRecord record = repository.readByTranType("01").record().orElseThrow();

            assertThat(record.filler())
                    .as("measured from app/data/ASCII/trantype.txt, where every row ends in eight "
                            + "ASCII zeros. Normalising them to spaces would change the stored record "
                            + "while every field accessor still answered correctly")
                    .isEqualTo(FIXTURE_FILLER)
                    .isNotEqualTo(SPACE_FILLER);
            assertThat(record.fillerBytes())
                    .isEqualTo(FIXTURE_FILLER.getBytes(ASCII));
        }

        @ParameterizedTest(name = "record {0} round-trips byte-identically")
        @ValueSource(strings = {"01", "02", "03", "04", "05", "06", "07"})
        @DisplayName("every decoded record re-encodes to the identical 60 bytes it was read from")
        void everyRecordRoundTripsByteIdentically(String tranType) {
            List<String> rows = fixtureRows();
            TranTypeRepository repository = seeded(rows);
            String storedRow = rows.stream()
                    .filter(row -> row.startsWith(tranType))
                    .findFirst()
                    .orElseThrow();

            TranTypeRecord record = repository.readByTranType(tranType).record().orElseThrow();

            assertThat(record.image())
                    .as("the encode side of the round trip, reserved bytes included, because they "
                            + "are carried rather than rebuilt")
                    .isEqualTo(storedRow);
            assertThat(record.toByteArray())
                    .isEqualTo(storedRow.getBytes(ASCII));
            assertThat(record.recordLength()).isEqualTo(TranTypeRepository.RECORD_LENGTH);
            assertThat(record.charset())
                    .as("practice B8: the code page is the one that was injected, never a platform "
                            + "default")
                    .isEqualTo(ASCII);
        }

        @Test
        @DisplayName("the shipped fixture is 7 records of exactly 60 bytes, keyed 01 through 07")
        void theShippedFixtureIsSevenSixtyByteRecords() {
            List<String> rows = fixtureRows();

            assertThat(rows).hasSize(FIXTURE_RECORDS);
            assertThat(rows).allSatisfy(row ->
                    assertThat(row).hasSize(TranTypeRepository.RECORD_LENGTH));
            assertThat(rows.stream()
                            .map(row -> row.substring(TranTypeRecord.TRAN_TYPE_OFFSET,
                                    TranTypeRecord.TRAN_TYPE_OFFSET
                                            + TranTypeRecord.TRAN_TYPE_LENGTH))
                            .toList())
                    .as("the keys are character data with a significant leading zero")
                    .containsExactly("01", "02", "03", "04", "05", "06", "07");
        }

        @Test
        @DisplayName("a row of any other width is reported, never padded into a plausible record")
        void aRowOfTheWrongWidthIsReported() {
            String shortRow = image("01", "Purchase")
                    .substring(0, TranTypeRepository.RECORD_LENGTH - 1);
            TranTypeRepository repository = seeded(Collections.singletonList(shortRow));

            ReadResult result = repository.readByTranType("01");

            assertThat(result.isOther())
                    .as("padding a short row with spaces looks like the faithful repair, but the "
                            + "description is moved straight into a PIC X(15) report field, so a row "
                            + "truncated inside TRAN-TYPE-DESC would pad into a DIFFERENT description "
                            + "and the report would come out wrong, successfully and silently")
                    .isTrue();
            assertThat(result.status()).isEqualTo(TranTypeRepository.PERMANENT_ERROR_STATUS);
            assertThat(result.record()).isEmpty();
        }
    }

    @Nested
    @DisplayName("open() and close() report a status and never abend")
    class OpenAndClose {
        @Test
        @DisplayName("a describable dataset opens with '00', exactly as IF TRANTYPE-STATUS = '00'")
        void aDescribableDatasetOpens() {
            TranTypeRepository repository = seededWithFixture();

            assertThat(repository.open())
                    .as("app/cbl/CBTRN03C.cbl:433-436 - the two-way test, and this is its true arm")
                    .isEqualTo(FileStatus.OK)
                    .hasSize(FileStatus.STATUS_LENGTH);
        }

        @Test
        @DisplayName("the probe is a describe: it names the dataset and transfers no row")
        void theProbeTransfersNoRow() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            Backend stub = backend(template).storing(fixtureRows());
            TranTypeRepository repository = repository(template);

            repository.open();

            assertThat(stub.statementsSent())
                    .as("one round trip, carrying metadata only")
                    .hasSize(1);
            assertThat(stub.statementsSent().get(0))
                    .isEqualTo(repository.describeStatement())
                    .contains(DS);
            assertThat(stub.patternsBound())
                    .as("an OPEN reads no record, so no key operand is bound")
                    .isEmpty();
        }

        @Test
        @DisplayName("a second open after a close succeeds: nothing leaks and nothing is stale")
        void openAfterCloseSucceeds() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            Backend stub = backend(template).storing(fixtureRows());
            TranTypeRepository repository = repository(template);

            assertThat(repository.open()).isEqualTo(FileStatus.OK);
            assertThat(repository.readByTranType("01").isFound()).isTrue();
            assertThat(repository.resolvedKeyedReadStatement())
                    .as("the open composed the keyed statement")
                    .isNotNull();

            assertThat(repository.close()).isEqualTo(FileStatus.OK);
            assertThat(repository.resolvedKeyedReadStatement())
                    .as("a CLOSE forgets what the OPEN learned, so a read afterwards describes the "
                            + "dataset again rather than reusing a statement over a relation that "
                            + "may since have been de-allocated")
                    .isNull();

            assertThat(repository.open())
                    .as("and the dataset opens again cleanly - no handle was held, so none leaked")
                    .isEqualTo(FileStatus.OK);
            assertThat(repository.readByTranType("07").isFound()).isTrue();
            assertThat(stub.statementsSent())
                    .as("open, read, close, open, read - five round trips, in that order")
                    .hasSize(5);
        }

        @Test
        @DisplayName("an unreachable dataset reports the permanent-error status, and does not throw")
        void anUnreachableDatasetReportsAStatus() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            backend(template).storing(fixtureRows()).failing();
            TranTypeRepository repository = repository(template);

            assertThatCode(() -> {
                assertThat(repository.open())
                        .as("app/cbl/CBTRN03C.cbl:436 moves 12 into APPL-RESULT - the ELSE arm - and "
                                + "the DISPLAY at :441 and the abend at :445 are the caller's")
                        .isEqualTo(TranTypeRepository.PERMANENT_ERROR_STATUS);
                assertThat(repository.close())
                        .as("and :575 / :580 are its exact mirror for the close")
                        .isEqualTo(TranTypeRepository.PERMANENT_ERROR_STATUS);
            }).doesNotThrowAnyException();
            assertThat(FileStatus.outcomeOfStatus(TranTypeRepository.PERMANENT_ERROR_STATUS))
                    .as("a permanent error is the catch-all arm, not one of the enumerated statuses")
                    .isEqualTo(Outcome.OTHER);
        }

        @Test
        @DisplayName("a relation presenting no record-image column is not a successful open")
        void aRelationWithoutTheColumnIsNotASuccessfulOpen() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            backend(template).storing(fixtureRows()).withoutColumn();
            TranTypeRepository repository = repository(template);

            assertThat(repository.open())
                    .as("reporting success on a relation this repository cannot read would hand the "
                            + "report job a description it never actually read")
                    .isEqualTo(TranTypeRepository.PERMANENT_ERROR_STATUS);
        }

        @Test
        @DisplayName("a driver answering with no metadata at all is not a successful open")
        void aDriverWithoutMetadataIsNotASuccessfulOpen() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            backend(template).storing(fixtureRows()).withoutMetadata();
            TranTypeRepository repository = repository(template);

            assertThat(repository.open()).isEqualTo(TranTypeRepository.PERMANENT_ERROR_STATUS);
        }

        @Test
        @DisplayName("a template yielding no column name at all is not a successful open")
        void aTemplateYieldingNothingIsNotASuccessfulOpen() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            backend(template).storing(fixtureRows()).blankColumnName();
            TranTypeRepository repository = repository(template);

            assertThat(repository.open()).isEqualTo(TranTypeRepository.PERMANENT_ERROR_STATUS);
        }

        @Test
        @DisplayName("close() forgets the composed statement even when the probe itself failed")
        void closeForgetsRegardlessOfTheProbeOutcome() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            Backend stub = backend(template).storing(fixtureRows());
            TranTypeRepository repository = repository(template);
            assertThat(repository.open()).isEqualTo(FileStatus.OK);
            assertThat(repository.resolvedKeyedReadStatement()).isNotNull();

            stub.failing();

            assertThat(repository.close())
                    .isEqualTo(TranTypeRepository.PERMANENT_ERROR_STATUS);
            assertThat(repository.resolvedKeyedReadStatement())
                    .as("the forgetting happens in a finally, because the dataset is closed either "
                            + "way - there is no path on which a stale statement survives")
                    .isNull();
        }

        @Test
        @DisplayName("close() is predictable before and after a not-found, and stays repeatable")
        void closeIsPredictableAroundANotFound() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            backend(template).storing(fixtureRows());
            TranTypeRepository repository = repository(template);

            assertThat(repository.close())
                    .as("a close before any open is still just a describe, and still reports '00'")
                    .isEqualTo(FileStatus.OK);

            ReadResult missed = repository.readByTranType("99");
            assertThat(missed.isNotFound()).isTrue();

            assertThat(repository.close())
                    .as("an INVALID KEY leaves nothing to unwind: ACCESS MODE IS RANDOM holds no "
                            + "position, so the close after a miss is the close after anything else")
                    .isEqualTo(FileStatus.OK);
            assertThat(repository.close())
                    .as("and it is idempotent - the COBOL closes once, but a second call must not "
                            + "invent a failure")
                    .isEqualTo(FileStatus.OK);
        }
    }

    @Nested
    @DisplayName("readByTranType reproduces READ ... INVALID KEY, and reports rather than throws")
    class KeyedReadAndNotFound {
        @Test
        @DisplayName("gate G47: an absent key yields '23' WITHOUT throwing - the INVALID KEY arm")
        void anAbsentKeyIsReportedAndNeverThrown() {
            TranTypeRepository repository = seededWithFixture();

            assertThatCode(() -> repository.readByTranType("99"))
                    .as("app/cbl/CBTRN03C.cbl:496-500 keeps the DISPLAY 'INVALID TRANSACTION TYPE : ', "
                            + "the MOVE 23 TO IO-STATUS, the rendered status line and the abend inside "
                            + "the PROGRAM. TransactionReportJob has to emit all four, in that order, "
                            + "so the repository must hand back a status and get out of the way")
                    .doesNotThrowAnyException();

            ReadResult result = repository.readByTranType("99");

            assertThat(result.isNotFound()).isTrue();
            assertThat(result.outcome()).isEqualTo(Outcome.NOT_FOUND);
            assertThat(result.status())
                    .as("FILE STATUS '23' is the same 23 the program moves into IO-STATUS at :498")
                    .isEqualTo(FileStatus.NOT_FOUND)
                    .isEqualTo("23");
            assertThat(result.record())
                    .as("no record reached TRAN-TYPE-RECORD")
                    .isEmpty();
            assertThat(result.diagnostic())
                    .as("nothing was refused, so there is nothing for a driver to have said")
                    .isEmpty();
            assertThat(result.keyImage())
                    .as("the bytes :497 displays are FD-TRAN-TYPE's own, so the caller gets exactly "
                            + "those rather than the value it passed in")
                    .isEqualTo("99");
            assertThat(result.applResult())
                    .as("the convention every other guard in the program uses: 12 for anything but "
                            + "'00' (app/cbl/CBTRN03C.cbl:436)")
                    .isEqualTo(TranTypeRepository.APPL_RESULT_FATAL);
            assertThat(result.statusImage())
                    .as("what 9910-DISPLAY-IO-STATUS renders at :643-644 for a numeric status")
                    .isEqualTo("0023")
                    .hasSize(FileStatus.STATUS_IMAGE_LENGTH);
        }

        @Test
        @DisplayName("a matched row carrying no record image is reported, never treated as absent")
        void aMatchedRowWithNoRecordImageIsReported() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            List<String> withUnreadable = new ArrayList<>();
            withUnreadable.add(null);
            backend(template).storing(withUnreadable).presentingUnreadableRowsToKeyedReads();

            ReadResult result = repository(template).readByTranType("01");

            assertThat(result.isNotFound())
                    .as("there IS a record; it simply cannot be read")
                    .isFalse();
            assertThat(result.outcome()).isEqualTo(Outcome.OTHER);
            assertThat(result.status()).isEqualTo(TranTypeRepository.PERMANENT_ERROR_STATUS);
        }

        @Test
        @DisplayName("finding DB-05: an unreadable row is not reported as INVALID KEY")
        void anUnreadableRowIsNotReportedAsAbsent() {
            List<String> withUnreadable = new ArrayList<>(fixtureRows());
            withUnreadable.add(null);

            ReadResult result = seeded(withUnreadable).readByTranType("99");

            assertThat(result.isNotFound())
                    .as("the unreadable row's key cannot be known, so no absence can be asserted")
                    .isFalse();
            assertThat(result.outcome()).isEqualTo(Outcome.OTHER);
            assertThat(result.status()).isEqualTo(TranTypeRepository.PERMANENT_ERROR_STATUS);
        }

        @Test
        @DisplayName("finding DB-05: a genuinely absent key still reports '23'")
        void aGenuinelyAbsentKeyIsStillNotFound() {
            assertThat(seededWithFixture().readByTranType("99").status())
                    .isEqualTo(FileStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("finding DB-05: a read that found its record is unaffected by an unreadable row")
        void aFoundRecordIsUnaffectedByAnUnreadableRowElsewhere() {
            List<String> withUnreadable = new ArrayList<>(fixtureRows());
            withUnreadable.add(null);

            assertThat(seeded(withUnreadable).readByTranType("01").isFound()).isTrue();
        }

        @Test
        @DisplayName("finding DB-05: a refused probe is reported rather than reported as absent")
        void aRefusedProbeIsReportedRatherThanAssumedAbsent() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            backend(template).storing(fixtureRows()).failingOnProbe();

            ReadResult result = repository(template).readByTranType("99");

            assertThat(result.isNotFound())
                    .as("the probe established nothing, so the absence stays unproved")
                    .isFalse();
            assertThat(result.status()).isEqualTo(TranTypeRepository.PERMANENT_ERROR_STATUS);
            assertThat(result.diagnostic())
                    .as("the driver's own diagnosis reaches the caller")
                    .isPresent();
        }

        @Test
        @DisplayName("finding DB-05: a probe answering with no result object is reported, not absent")
        void aProbeYieldingNoResultObjectIsReportedRatherThanAbsent() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            backend(template).storing(fixtureRows()).probeYieldingNothing();

            ReadResult result = repository(template).readByTranType("99");

            assertThat(result.isNotFound())
                    .as("nothing came back, so nothing was established")
                    .isFalse();
            assertThat(result.status()).isEqualTo(TranTypeRepository.PERMANENT_ERROR_STATUS);
        }

        @Test
        @DisplayName("finding DB-05: the probe runs only on the not-found path, and binds no key")
        void theProbeRunsOnlyOnTheNotFoundPathAndBindsNoKey() {
            JdbcTemplate found = mock(JdbcTemplate.class);
            backend(found).storing(fixtureRows());
            JdbcTemplate missing = mock(JdbcTemplate.class);
            backend(missing).storing(fixtureRows());

            repository(found).readByTranType("01");
            repository(missing).readByTranType("99");

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
        @DisplayName("a not-found is not an end of file: a keyed read has reached the end of nothing")
        void aNotFoundIsNotAnEndOfFile() {
            TranTypeRepository repository = seededWithFixture();

            ReadResult result = repository.readByTranType("42");

            assertThat(result.outcome())
                    .as("app/cbl/CBTRN03C.cbl:41 is ACCESS MODE IS RANDOM - there is no sequence to "
                            + "exhaust, so '10' is not a status this read can report")
                    .isEqualTo(Outcome.NOT_FOUND)
                    .isNotEqualTo(Outcome.END_OF_FILE);
            assertThat(result.status()).isNotEqualTo(FileStatus.END_OF_FILE);
        }

        @Test
        @DisplayName("a found record decodes at the copybook offsets and carries the OK status")
        void aFoundRecordDecodesAtTheCopybookOffsets() {
            TranTypeRepository repository = seededWithFixture();

            ReadResult result = repository.readByTranType("04");
            TranTypeRecord record = result.record().orElseThrow();

            assertThat(result.outcome()).isEqualTo(Outcome.OK);
            assertThat(result.status()).isEqualTo(FileStatus.OK);
            assertThat(result.applResult())
                    .as("app/cbl/CBTRN03C.cbl:434 - MOVE 0 TO APPL-RESULT on the '00' arm")
                    .isEqualTo(FileStatus.APPL_AOK);
            assertThat(record.tranType()).isEqualTo("04");
            assertThat(record.tranTypeKey()).isEqualTo("04");
            assertThat(record.tranTypeDesc()).startsWith("Authorization");
            assertThat(record.filler()).isEqualTo(FIXTURE_FILLER);
        }

        @Test
        @DisplayName("reading the same key twice reads it twice: no position is held between calls")
        void theReadHoldsNoPosition() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            Backend stub = backend(template).storing(fixtureRows());
            TranTypeRepository repository = repository(template);

            ReadResult first = repository.readByTranType("03");
            ReadResult second = repository.readByTranType("03");

            assertThat(first.record().orElseThrow())
                    .as("ACCESS MODE IS RANDOM holds no position, and 1500-B-LOOKUP-TRANTYPE is "
                            + "performed once per report line, so the same key must answer the same "
                            + "way every time")
                    .isEqualTo(second.record().orElseThrow());
            assertThat(stub.patternsBound()).containsExactly("03%", "03%");
        }

        @Test
        @DisplayName("a backend refusal reports the permanent-error status and keeps the diagnosis")
        void aBackendRefusalIsReportedWithItsDiagnosis() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            backend(template).storing(fixtureRows()).failing();
            TranTypeRepository repository = repository(template);

            ReadResult result = repository.readByTranType("01");

            assertThat(result.isOther()).isTrue();
            assertThat(result.status()).isEqualTo(TranTypeRepository.PERMANENT_ERROR_STATUS);
            assertThat(result.record()).isEmpty();
            assertThat(result.diagnostic())
                    .as("a permanent-error status says something went wrong and nothing about what, "
                            + "whereas the driver's SQLSTATE distinguishes an unreachable backend "
                            + "from a missing dataset from a rejected credential")
                    .isPresent();
            assertThat(result.applResult()).isEqualTo(TranTypeRepository.APPL_RESULT_FATAL);
        }

        @Test
        @DisplayName("a dataset that describes cleanly but refuses the READ is reported separately")
        void aDatasetThatRefusesOnlyTheReadIsReported() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            backend(template).storing(fixtureRows()).failingOnRead();
            TranTypeRepository repository = repository(template);

            assertThat(repository.open())
                    .as("the catalogue entry resolves, so the OPEN succeeds")
                    .isEqualTo(FileStatus.OK);

            ReadResult result = repository.readByTranType("01");

            assertThat(result.isOther())
                    .as("the realistic shape of a dataset that is catalogued but whose data component "
                            + "cannot be read - and the only way to reach the repository's second "
                            + "catch arm")
                    .isTrue();
            assertThat(result.diagnostic()).isPresent();
        }

        @Test
        @DisplayName("a relation that cannot present its column reports a status, not an exception")
        void anUnusableRelationIsReportedAsAStatus() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            backend(template).storing(fixtureRows()).withoutColumn();
            TranTypeRepository repository = repository(template);

            ReadResult result = repository.readByTranType("01");

            assertThat(result.isOther())
                    .as("the statement could not be composed at all, which is an I/O-level failure "
                            + "and not a missing record - and a COBOL READ reports a status and "
                            + "leaves the guard chain in control")
                    .isTrue();
            assertThat(result.status()).isEqualTo(TranTypeRepository.PERMANENT_ERROR_STATUS);
            assertThat(result.diagnostic())
                    .as("no driver refused, so there is no SQLSTATE to carry")
                    .isEmpty();
        }

        @Test
        @DisplayName("a template yielding no result object is reported, not mistaken for INVALID KEY")
        void aTemplateYieldingNoResultIsReported() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            backend(template).storing(fixtureRows()).yieldingNothing();
            TranTypeRepository repository = repository(template);

            ReadResult result = repository.readByTranType("01");

            assertThat(result.isOther())
                    .as("a template that yielded nothing at all has told us nothing, and nothing is "
                            + "not an absent record")
                    .isTrue();
            assertThat(result.isNotFound()).isFalse();
            assertThat(result.status()).isEqualTo(TranTypeRepository.PERMANENT_ERROR_STATUS);
        }

        @Test
        @DisplayName("a row present but unreadable is reported, not mistaken for INVALID KEY")
        void aRowWithNoImageIsReported() {
            TranTypeRepository repository = seeded(Collections.singletonList(null));

            ReadResult result = repository.readByTranType("01");

            assertThat(result.isOther())
                    .as("there IS a record and it simply cannot be read, so reporting it as absent "
                            + "would send the caller down the INVALID KEY path with the wrong message")
                    .isTrue();
            assertThat(result.isNotFound()).isFalse();
            assertThat(result.status()).isEqualTo(TranTypeRepository.PERMANENT_ERROR_STATUS);
        }

        @Test
        @DisplayName("a key selecting two rows is reported: a KSDS primary key is unique")
        void aDuplicateKeyIsReportedOnTheCatchAllArm() {
            TranTypeRepository repository = seeded(List.of(
                    image("01", "Purchase"),
                    image("01", "Purchase again")));

            ReadResult result = repository.readByTranType("01");

            assertThat(result.isOther())
                    .as("RECORD KEY IS FD-TRAN-TYPE (app/cbl/CBTRN03C.cbl:42) is unique by "
                            + "construction, so a second matching row means the relation is not "
                            + "presenting this dataset - a condition the legacy READ cannot produce")
                    .isTrue();
            assertThat(result.outcome())
                    .as("and it is emphatically NOT reported as a duplicate: this READ has three "
                            + "arms, and DUPLICATE is not one of them")
                    .isEqualTo(Outcome.OTHER)
                    .isNotEqualTo(Outcome.DUPLICATE);
            assertThat(result.record())
                    .as("handing back one of several rows as though it were the only one would be "
                            + "the silent wrong answer")
                    .isEmpty();
        }

        @Test
        @DisplayName("gate G47: every FileStatus arm this dataset can report is exercised")
        void everyStatusArmIsExercised() {
            List<String> observed = new ArrayList<>();

            observed.add(seededWithFixture().readByTranType("01").status());
            observed.add(seededWithFixture().readByTranType("99").status());
            JdbcTemplate refusing = mock(JdbcTemplate.class);
            backend(refusing).storing(fixtureRows()).failing();
            observed.add(repository(refusing).readByTranType("01").status());

            assertThat(observed)
                    .as("'00', '23' and the permanent error - the three the COBOL guard chain can "
                            + "see, each driven from this suite")
                    .containsExactly(FileStatus.OK, FileStatus.NOT_FOUND,
                            TranTypeRepository.PERMANENT_ERROR_STATUS);
            assertThat(observed.stream().map(FileStatus::outcomeOfStatus).toList())
                    .containsExactly(Outcome.OK, Outcome.NOT_FOUND, Outcome.OTHER);
            assertThat(observed)
                    .as("'22' never appears: a unique primary key cannot report a duplicate")
                    .doesNotContain(FileStatus.DUPLICATE)
                    .doesNotContain(FileStatus.END_OF_FILE);
            assertThat(TranTypeRepository.PERMANENT_ERROR_STATUS)
                    .as("and the catch-all is genuinely a THIRD status, distinct from both enumerated "
                            + "ones - so a caller's guard chain has three arms to take, exactly as "
                            + "0300-TRANTYPE-OPEN and 1500-B-LOOKUP-TRANTYPE between them do")
                    .isNotEqualTo(FileStatus.OK)
                    .isNotEqualTo(FileStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("gate G50: both states of every outcome predicate are driven")
        void bothStatesOfEveryPredicateAreDriven() {
            ReadResult found = seededWithFixture().readByTranType("01");
            ReadResult missed = seededWithFixture().readByTranType("99");
            JdbcTemplate refusing = mock(JdbcTemplate.class);
            backend(refusing).storing(fixtureRows()).failing();
            ReadResult other = repository(refusing).readByTranType("01");

            assertThat(found.isFound()).isTrue();
            assertThat(found.isNotFound()).isFalse();
            assertThat(found.isOther()).isFalse();

            assertThat(missed.isFound()).isFalse();
            assertThat(missed.isNotFound()).isTrue();
            assertThat(missed.isOther()).isFalse();

            assertThat(other.isFound()).isFalse();
            assertThat(other.isNotFound()).isFalse();
            assertThat(other.isOther()).isTrue();
        }
    }

    @Nested
    @DisplayName("The negative contract: no write, no rewrite, no delete, no browse")
    class NegativeContract {
        @Test
        @DisplayName("no mutating or browsing operation is exposed at all")
        void noMutatingOrBrowsingOperationIsExposed() {
            List<String> forbidden = new ArrayList<>();
            for (Method method : TranTypeRepository.class.getMethods()) {
                if (method.getDeclaringClass() != TranTypeRepository.class) {
                    continue;
                }
                String name = method.getName().toLowerCase(Locale.ROOT);
                if (name.contains("write") || name.contains("rewrite") || name.contains("delete")
                        || name.contains("browse") || name.contains("insert")
                        || name.contains("update") || name.contains("save")
                        || name.contains("readnext") || name.contains("readprev")
                        || name.contains("startbr") || name.contains("endbr")) {
                    forbidden.add(method.getName());
                }
            }

            assertThat(forbidden)
                    .as("a deliberate parity boundary, not an oversight: CBTRN03C opens TRANTYPE for "
                            + "INPUT at app/cbl/CBTRN03C.cbl:432, READs it at :495 and CLOSEs it at "
                            + ":571, and app/jcl/TRANREPT.jcl:69 binds the DD with DISP=SHR as an "
                            + "input. There is no WRITE, REWRITE, DELETE, STARTBR or READ NEXT against "
                            + "it anywhere in the estate")
                    .isEmpty();
        }

        @Test
        @DisplayName("the public surface is exactly the three operations plus the dataset name")
        void thePublicSurfaceIsExactlyTheThreeOperations() {
            List<String> declared = new ArrayList<>();
            for (Method method : TranTypeRepository.class.getMethods()) {
                if (method.getDeclaringClass() == TranTypeRepository.class
                        && Modifier.isPublic(method.getModifiers())) {
                    declared.add(method.getName());
                }
            }

            assertThat(declared)
                    .as("OPEN INPUT, READ ... INVALID KEY and CLOSE, plus the configured dataset name "
                            + "for a caller reporting which dataset it read")
                    .containsExactlyInAnyOrder("open", "close", "readByTranType", "datasetName");
        }

        @Test
        @DisplayName("nothing returns a mutable view of the record, so no caller can write through one")
        void noAccessorHandsOutMutableState() throws NoSuchMethodException {
            TranTypeRepository repository = seededWithFixture();
            TranTypeRecord record = repository.readByTranType("01").record().orElseThrow();

            byte[] first = record.toByteArray();
            byte[] second = record.toByteArray();
            first[TranTypeRecord.FILLER_OFFSET] = (byte) 'X';

            assertThat(second)
                    .as("a fresh copy per call: the internal array is never handed out, so mutating "
                            + "what a caller was given cannot reach the record")
                    .isEqualTo(record.toByteArray())
                    .isNotEqualTo(first);
            assertThat(TranTypeRecord.class.getMethod("filler").getReturnType())
                    .isEqualTo(String.class);
        }
    }

    @Nested
    @DisplayName("Construction is configuration-bound and refuses to guess")
    class Construction {
        @Test
        @DisplayName("gate G46: the dataset name comes from configuration and is returned unchanged")
        void theDatasetNameComesFromConfiguration() {
            TranTypeRepository repository = repository(mock(JdbcTemplate.class));

            assertThat(repository.datasetName())
                    .as("application.yml:568 spells it as ${CARDDEMO_DATASET_TRANTYPE:...}, so the "
                            + "value is a deployment input and this test supplies a different one to "
                            + "prove the repository does not carry a name of its own")
                    .isEqualTo(DS)
                    .doesNotContain("AWS.M2.CARDDEMO.");
        }

        @Test
        @DisplayName("the identity constants match the copybook, the SELECT and the JCL")
        void theIdentityConstantsMatchTheSources() {
            assertThat(TranTypeRepository.DD_NAME)
                    .as("app/cbl/CBTRN03C.cbl:39 ASSIGN TO TRANTYPE, and app/jcl/TRANREPT.jcl:69 "
                            + "//TRANTYPE DD")
                    .isEqualTo("TRANTYPE");
            assertThat(TranTypeRepository.EXPECTED_COPYBOOK)
                    .as("app/cbl/CBTRN03C.cbl:103 COPY CVTRA03Y")
                    .isEqualTo("CVTRA03Y");
            assertThat(TranTypeRepository.EXPECTED_ORGANIZATION)
                    .as("app/cbl/CBTRN03C.cbl:40 ORGANIZATION IS INDEXED - an indexed base cluster")
                    .isEqualTo(DatasetBinding.KSDS);
            assertThat(TranTypeRepository.TRAN_TYPE_KEY_LENGTH).isEqualTo(2);
            assertThat(TranTypeRepository.TRAN_TYPE_KEY_OFFSET)
                    .as("TRAN-TYPE begins the record, so a non-zero offset would belong to an "
                            + "alternate-index path - and this dataset has none")
                    .isZero();
            assertThat(TranTypeRepository.RECORD_IMAGE_COLUMN_INDEX)
                    .isEqualTo(DatasetRelation.RECORD_IMAGE_COLUMN_INDEX);
            assertThat(TranTypeRepository.APPL_RESULT_FATAL)
                    .as("app/cbl/CBTRN03C.cbl:436 and :575 both MOVE 12 TO APPL-RESULT")
                    .isEqualTo(12);
            assertThat(TranTypeRepository.PERMANENT_ERROR_STATUS)
                    .hasSize(FileStatus.STATUS_LENGTH)
                    .startsWith("9");
        }

        @Test
        @DisplayName("every collaborator is required: an absent one is a wiring defect")
        void everyCollaboratorIsRequired() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            DatasetBindings catalogue = bindings(validBinding());

            assertThatNullPointerException().isThrownBy(() -> new TranTypeRepository(
                    null, catalogue, ASCII, RecordImageForm.CHARACTER));
            assertThatNullPointerException().isThrownBy(() -> new TranTypeRepository(
                    template, null, ASCII, RecordImageForm.CHARACTER));
            assertThatNullPointerException().isThrownBy(() -> new TranTypeRepository(
                    template, catalogue, null, RecordImageForm.CHARACTER));
            assertThatNullPointerException().isThrownBy(() -> new TranTypeRepository(
                    template, catalogue, ASCII, null));
        }

        @Test
        @DisplayName("an absent TRANTYPE binding is refused and the diagnostic names the DD")
        void anAbsentBindingIsRefused() {
            JdbcTemplate template = mock(JdbcTemplate.class);

            assertThatIllegalStateException()
                    .isThrownBy(() -> new TranTypeRepository(template, bindings(null), ASCII,
                            RecordImageForm.CHARACTER))
                    .withMessageContaining(TranTypeRepository.DD_NAME);
        }

        @Test
        @DisplayName("a record length other than 60 is refused, and the shipped 60 passes")
        void theRecordLengthMustBeSixty() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> construct(binding(56, TranTypeRepository.EXPECTED_COPYBOOK,
                            DatasetBinding.KSDS, 2, null, null, DS)))
                    .withMessageContaining("56")
                    .withMessageContaining(TranTypeRepository.EXPECTED_COPYBOOK);

            assertThatCode(() -> construct(validBinding())).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a copybook other than CVTRA03Y is refused, including none at all")
        void theCopybookMustBeCvtra03y() {
            assertThatIllegalStateException()
                    .as("CVTRA04Y is also 60 bytes and is a different record in every field after "
                            + "the key, so the width alone does not identify the layout")
                    .isThrownBy(() -> construct(binding(60, "CVTRA04Y", DatasetBinding.KSDS, 2,
                            null, null, DS)))
                    .withMessageContaining("CVTRA04Y")
                    .withMessageContaining(TranTypeRepository.EXPECTED_COPYBOOK);

            assertThatIllegalStateException()
                    .as("an omitted copybook is a binding that has not said which layout it holds")
                    .isThrownBy(() -> construct(binding(60, null, DatasetBinding.KSDS, 2, null,
                            null, DS)));
        }

        @ParameterizedTest(name = "organization \"{0}\" is refused")
        @ValueSource(strings = {"sequential", DatasetBinding.AIX_PATH, "ksds "})
        @DisplayName("only an indexed base cluster is accepted")
        void onlyAnIndexedBaseClusterIsAccepted(String organization) {
            assertThatIllegalStateException()
                    .isThrownBy(() -> construct(binding(60,
                            TranTypeRepository.EXPECTED_COPYBOOK, organization, 2, null, null, DS)))
                    .withMessageContaining(organization);
        }

        @Test
        @DisplayName("a binding declaring a base is refused: TRANTYPE has no alternate index")
        void aBindingDeclaringABaseIsRefused() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> construct(binding(60,
                            TranTypeRepository.EXPECTED_COPYBOOK, DatasetBinding.KSDS, 2, null,
                            "CARDDAT", DS)))
                    .withMessageContaining("CARDDAT");
        }

        @Test
        @DisplayName("a key of any width but 2, or at any offset but 0, is refused")
        void theKeyGeometryMustMatchTheCopybook() {
            assertThatIllegalStateException()
                    .as("a predicate composed from a key of another width matches records this key "
                            + "does not name - and matches them successfully")
                    .isThrownBy(() -> construct(binding(60,
                            TranTypeRepository.EXPECTED_COPYBOOK, DatasetBinding.KSDS, 6, null,
                            null, DS)))
                    .withMessageContaining("6");

            assertThatIllegalStateException()
                    .as("a keyed binding that declares no key length has not said how to read itself")
                    .isThrownBy(() -> construct(binding(60,
                            TranTypeRepository.EXPECTED_COPYBOOK, DatasetBinding.KSDS, null, null,
                            null, DS)));

            assertThatIllegalStateException()
                    .as("a non-zero offset belongs to an alternate-index path, and this dataset has "
                            + "none")
                    .isThrownBy(() -> construct(binding(60,
                            TranTypeRepository.EXPECTED_COPYBOOK, DatasetBinding.KSDS, 2, 16, null,
                            DS)))
                    .withMessageContaining("16");

            assertThatCode(() -> construct(binding(60, TranTypeRepository.EXPECTED_COPYBOOK,
                    DatasetBinding.KSDS, 2, 0, null, DS)))
                    .as("an explicitly declared offset of zero says the same thing as omitting it")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("an absent, blank or malformed dataset name is refused - none is defaulted")
        void theDatasetNameMustBeUsable() {
            assertThatIllegalStateException()
                    .as("application.yml spells the name as an environment placeholder, so a "
                            + "deployment that never supplied one yields an empty string")
                    .isThrownBy(() -> construct(binding(60,
                            TranTypeRepository.EXPECTED_COPYBOOK, DatasetBinding.KSDS, 2, null,
                            null, "   ")))
                    .withMessageContaining(TranTypeRepository.DD_NAME);

            assertThatIllegalStateException()
                    .isThrownBy(() -> construct(binding(60,
                            TranTypeRepository.EXPECTED_COPYBOOK, DatasetBinding.KSDS, 2, null,
                            null, null)));

            assertThatIllegalArgumentException()
                    .as("the z/OS dataset-name grammar lives in DatasetRelation, so it is stated once "
                            + "for the module and its own diagnosis is not re-wrapped")
                    .isThrownBy(() -> construct(binding(60,
                            TranTypeRepository.EXPECTED_COPYBOOK, DatasetBinding.KSDS, 2, null,
                            null, "not a dataset name")));
        }

        @Test
        @DisplayName("a multi-byte code page is refused: 2 characters must be provably 2 bytes")
        void aMultiByteCodePageIsRefused() {
            JdbcTemplate template = mock(JdbcTemplate.class);

            assertThatIllegalArgumentException()
                    .as("a fixed-width record is bytes, and a key of two characters is only a key of "
                            + "two bytes under a single-byte code page")
                    .isThrownBy(() -> new TranTypeRepository(template, bindings(validBinding()),
                            StandardCharsets.UTF_16, RecordImageForm.CHARACTER));
        }

        @Test
        @DisplayName("the valid shipped binding constructs cleanly and reaches no backend")
        void theValidBindingConstructsWithoutTouchingTheBackend() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            Backend stub = backend(template).storing(fixtureRows());

            TranTypeRepository repository = repository(template);

            assertThat(repository.datasetName()).isEqualTo(DS);
            assertThat(stub.statementsSent())
                    .as("a repository must be constructible in a context that has not reached its "
                            + "backend yet, so the statement is composed on first use and not here")
                    .isEmpty();
            assertThat(repository.resolvedKeyedReadStatement()).isNull();
        }

        private static DatasetBinding binding(int recordLength, String copybook, String organization,
                                              Integer keyLength, Integer keyOffset, String base,
                                              String dsname) {
            return new DatasetBinding(dsname, organization, false, "FB", null, recordLength,
                    copybook, keyLength, keyOffset, base, null);
        }

        private static TranTypeRepository construct(DatasetBinding binding) {
            return new TranTypeRepository(mock(JdbcTemplate.class), bindings(binding), ASCII,
                    RecordImageForm.CHARACTER);
        }
    }

    @Nested
    @DisplayName("ReadResult admits exactly the three arms this READ can produce")
    class ReadResultInvariants {
        @Test
        @DisplayName("every component is required")
        void everyComponentIsRequired() {
            assertThatNullPointerException().isThrownBy(() -> new ReadResult(
                    null, FileStatus.OK, Outcome.OK, Optional.empty(), Optional.empty()));
            assertThatNullPointerException().isThrownBy(() -> new ReadResult(
                    "01", null, Outcome.OK, Optional.empty(), Optional.empty()));
            assertThatNullPointerException().isThrownBy(() -> new ReadResult(
                    "01", FileStatus.OK, null, Optional.empty(), Optional.empty()));
            assertThatNullPointerException().isThrownBy(() -> new ReadResult(
                    "01", FileStatus.NOT_FOUND, Outcome.NOT_FOUND, null, Optional.empty()));
            assertThatNullPointerException().isThrownBy(() -> new ReadResult(
                    "01", FileStatus.NOT_FOUND, Outcome.NOT_FOUND, Optional.empty(), null));
        }

        @Test
        @DisplayName("the key image is always exactly 2 characters, and the status exactly 2")
        void theWidthsAreEnforced() {
            assertThatIllegalArgumentException()
                    .as("FD-TRAN-TYPE at app/cbl/CBTRN03C.cbl:75 always holds exactly two characters")
                    .isThrownBy(() -> ReadResult.notFound("0"))
                    .withMessageContaining("PIC X(02)");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ReadResult.notFound("001"));
            assertThatIllegalArgumentException()
                    .as("TRANTYPE-STATUS is two PIC X items at app/cbl/CBTRN03C.cbl:104-106")
                    .isThrownBy(() -> ReadResult.other("01", "9"));
        }

        @Test
        @DisplayName("the status and its classification are two views of one fact and may not disagree")
        void theStatusAndItsClassificationMustAgree() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ReadResult("01", FileStatus.OK, Outcome.NOT_FOUND,
                            Optional.empty(), Optional.empty()));
            assertThatIllegalArgumentException()
                    .as("a named status cannot be relabelled as the catch-all arm")
                    .isThrownBy(() -> ReadResult.other("01", FileStatus.OK));
        }

        @Test
        @DisplayName("END_OF_FILE and DUPLICATE are refused: this READ can produce neither")
        void theTwoImpossibleArmsAreRefused() {
            assertThatIllegalArgumentException()
                    .as("ACCESS MODE IS RANDOM (app/cbl/CBTRN03C.cbl:41) holds no position, so there "
                            + "is no sequence to exhaust")
                    .isThrownBy(() -> new ReadResult("01", FileStatus.END_OF_FILE,
                            Outcome.END_OF_FILE, Optional.empty(), Optional.empty()))
                    .withMessageContaining(FileStatus.NOT_FOUND);
            assertThatIllegalArgumentException()
                    .as("RECORD KEY IS FD-TRAN-TYPE (:42) is the primary key of a KSDS and is unique "
                            + "by construction")
                    .isThrownBy(() -> new ReadResult("01", FileStatus.DUPLICATE, Outcome.DUPLICATE,
                            Optional.empty(), Optional.empty()));
        }

        @Test
        @DisplayName("a record is present exactly when the outcome is success, and never otherwise")
        void theRecordPresenceFollowsTheOutcome() {
            TranTypeRecord record = TranTypeRecord.of("01", "Purchase", ASCII);

            assertThatIllegalArgumentException()
                    .as("only the '00' arm reaches TRAN-TYPE-RECORD")
                    .isThrownBy(() -> new ReadResult("01", FileStatus.NOT_FOUND, Outcome.NOT_FOUND,
                            Optional.of(record), Optional.empty()));
            assertThatIllegalArgumentException()
                    .as("a successful read carries the record it read")
                    .isThrownBy(() -> new ReadResult("01", FileStatus.OK, Outcome.OK,
                            Optional.empty(), Optional.empty()));
            assertThatNullPointerException()
                    .isThrownBy(() -> ReadResult.found("01", null));
        }

        @Test
        @DisplayName("the diagnostic-carrying catch-all arm keeps what the backend said")
        void theCatchAllArmCanCarryADiagnosis() {
            BackendDiagnostic diagnostic = BackendDiagnostic.of(
                    new DataAccessResourceFailureException("the dataset cannot be reached"));

            ReadResult result = ReadResult.other("01",
                    TranTypeRepository.PERMANENT_ERROR_STATUS, diagnostic);

            assertThat(result.diagnostic()).contains(diagnostic);
            assertThat(result.isOther()).isTrue();
            assertThatNullPointerException()
                    .as("the three-argument factory exists to carry a diagnosis, so a null one is a "
                            + "call that should have used the two-argument form")
                    .isThrownBy(() -> ReadResult.other("01",
                            TranTypeRepository.PERMANENT_ERROR_STATUS, null));
        }

        @Test
        @DisplayName("APPL-RESULT is 0 on the success arm and 12 on every other, as the program moves")
        void applResultFollowsTheProgramsConvention() {
            TranTypeRecord record = TranTypeRecord.of("01", "Purchase", ASCII);

            assertThat(ReadResult.found("01", record).applResult())
                    .isEqualTo(FileStatus.APPL_AOK);
            assertThat(ReadResult.notFound("99").applResult())
                    .isEqualTo(TranTypeRepository.APPL_RESULT_FATAL);
            assertThat(ReadResult.other("99", TranTypeRepository.PERMANENT_ERROR_STATUS)
                            .applResult())
                    .as("app/cbl/CBTRN03C.cbl:436 - the ELSE arm moves 12, and both a missing record "
                            + "and an I/O failure end this program")
                    .isEqualTo(TranTypeRepository.APPL_RESULT_FATAL);
        }

        @Test
        @DisplayName("the status image is what 9910-DISPLAY-IO-STATUS renders, for both status shapes")
        void theStatusImageMatchesTheProgramsRendering() {
            assertThat(ReadResult.notFound("99").statusImage())
                    .as("a numeric status takes the ELSE arm at app/cbl/CBTRN03C.cbl:642-644: '0000' "
                            + "with the two characters moved into positions 3 and 4")
                    .isEqualTo("0023");
            assertThat(ReadResult.other("99", TranTypeRepository.PERMANENT_ERROR_STATUS)
                            .statusImage())
                    .as("a '9' first byte takes the IF arm at :636-641, which renders the second byte "
                            + "as a three-digit binary value")
                    .isEqualTo("9000")
                    .hasSize(FileStatus.STATUS_IMAGE_LENGTH);
            assertThat(FileStatus.toDisplayLine(TranTypeRepository.PERMANENT_ERROR_STATUS))
                    .isEqualTo(FileStatus.DISPLAY_PREFIX + "9000");
        }
    }

    @Nested
    @DisplayName("Wiring, structural contracts and the source-level gates")
    class SourceLevelGates {
        @Test
        @DisplayName("gate G3: the class is a @Repository the component scan will discover")
        void theStereotypeIsWhatTheContainerScansFor() {
            assertThat(TranTypeRepository.class.isAnnotationPresent(Repository.class))
                    .as("com.vsergeychik.carddemo.transaction is one of the scanned packages, so the "
                            + "stereotype is what makes this bean exist")
                    .isTrue();
            assertThat(TranTypeRepository.class.getPackageName())
                    .isEqualTo("com.vsergeychik.carddemo.transaction");
        }

        @Test
        @DisplayName("there is exactly one constructor, so the container needs no @Autowired marker")
        void thereIsExactlyOneConstructor() {
            assertThat(TranTypeRepository.class.getConstructors())
                    .as("practice B9: constructor injection throughout, so an instance is either "
                            + "fully wired or does not exist")
                    .hasSize(1);
        }

        @Test
        @DisplayName("the charset injection point names its bean, so no code page is guessed")
        void theCharsetInjectionPointNamesItsBean() {
            Parameter[] parameters = TranTypeRepository.class.getConstructors()[0].getParameters();

            assertThat(TranTypeRepository.class.getConstructors()[0].getParameterTypes())
                    .containsExactly(JdbcTemplate.class, DatasetBindings.class, Charset.class,
                            RecordImageForm.class);
            assertThat(parameters[2].getAnnotation(Qualifier.class))
                    .as("practice B8: more than one Charset bean exists and none is primary, so "
                            + "without the qualifier the container could inject the wrong code page "
                            + "and a two-character key would stop being a two-byte key")
                    .isNotNull()
                    .extracting(Qualifier::value)
                    .isEqualTo(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME);
        }

        @Test
        @DisplayName("gate G53: no class in the file holds mutable static state")
        void thereIsNoStaticMutableState() {
            for (Class<?> type : List.of(TranTypeRepository.class, ReadResult.class)) {
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
        @DisplayName("the one non-final instance field is volatile and immutable-typed, and no other is")
        void theOnlyNonFinalFieldIsSafelyPublished() {
            List<String> unsafe = new ArrayList<>();
            for (Field field : TranTypeRepository.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()
                        || Modifier.isFinal(field.getModifiers())) {
                    continue;
                }
                boolean safelyPublished = Modifier.isVolatile(field.getModifiers())
                        && isDeeplyImmutable(field.getType());
                if (!safelyPublished) {
                    unsafe.add(field.getName() + " (" + field.getType().getSimpleName() + ")");
                }
            }

            assertThat(unsafe)
                    .as("a @Repository is a singleton shared across threads. The statements are resolved "
                            + "lazily because the record-image column is discovered from the backend, so "
                            + "the field cannot be final - but it holds an immutable value published "
                            + "through a volatile write, and recomposing it yields the same text, so no "
                            + "lock is needed and no per-request state lives on the bean")
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
        @DisplayName("gates G22, G24, G44, G46 and G52 hold over the repository's own source")
        void theSourceLevelGatesHold() {
            String source = repositorySource();

            assertThat(source)
                    .as("gate G46: the dataset name is resolved from carddemo.datasets.TRANTYPE and "
                            + "no AWS.M2.CARDDEMO.* literal is ever written into Java")
                    .doesNotContain("AWS.M2.CARDDEMO.");
            assertThat(Pattern.compile("^import .*\\*;", Pattern.MULTILINE).matcher(source).find())
                    .as("gate G52: no wildcard import, so every copybook-to-type correspondence stays "
                            + "auditable")
                    .isFalse();
            assertThat(Pattern.compile("\\b(double|float)\\b").matcher(source).find())
                    .as("gate G22: no binary approximate numeric primitive anywhere")
                    .isFalse();
            assertThat(source)
                    .as("gate G24: rounding in this module is always truncating, so no half-rounding "
                            + "mode may appear - and CVTRA03Y gives it nothing to round in any case")
                    .doesNotContain("HALF_UP")
                    .doesNotContain("HALF_EVEN")
                    .doesNotContain("RoundingMode");
            assertThat(source)
                    .as("gate G44: no DDL, no entity annotation, no version column, no created index")
                    .doesNotContain("@Entity")
                    .doesNotContain("@Table")
                    .doesNotContain("@Version")
                    .doesNotContain("CREATE TABLE")
                    .doesNotContain("ALTER TABLE")
                    .doesNotContain("CREATE INDEX");
            assertThat(source)
                    .as("no placeholder and no deferred work")
                    .doesNotContain("TODO")
                    .doesNotContain("FIXME");
        }

        @Test
        @DisplayName("the CobolDecimal dependency is DELIBERATELY absent: CVTRA03Y has no numeric item")
        void theCobolDecimalDependencyIsDeliberatelyAbsent() throws ClassNotFoundException {
            Class<?> cobolDecimal = Class.forName("com.vsergeychik.carddemo.common.CobolDecimal");
            assertThat(cobolDecimal.getPackageName())
                    .as("the fixed-point seam exists and is used where a PICTURE declares a scale - "
                            + "just not here")
                    .isEqualTo("com.vsergeychik.carddemo.common");

            String source = repositorySource();
            assertThat(source)
                    .as("no import of it, and no mention of it: an unused numeric seam in a purely "
                            + "alphanumeric record would suggest a scale the copybook does not declare")
                    .doesNotContain("CobolDecimal")
                    .doesNotContain("BigDecimal");

            List<String> numericSignatures = new ArrayList<>();
            for (Class<?> type : List.of(TranTypeRepository.class, ReadResult.class,
                    TranTypeRecord.class)) {
                for (Method method : type.getDeclaredMethods()) {
                    List<Class<?>> signature = new ArrayList<>(List.of(method.getParameterTypes()));
                    signature.add(method.getReturnType());
                    for (Class<?> referenced : signature) {
                        if ("java.math.BigDecimal".equals(referenced.getName())
                                || cobolDecimal.getName().equals(referenced.getName())) {
                            numericSignatures.add(type.getSimpleName() + "." + method.getName());
                        }
                    }
                }
            }
            assertThat(numericSignatures)
                    .as("nothing on the TRANTYPE access path speaks in fixed-point values, because "
                            + "nothing in CVTRA03Y is a fixed-point value")
                    .isEmpty();

            for (FieldSpan span : TranTypeRecord.LAYOUT.spans()) {
                assertThat(span.kind())
                        .as("span %s must be alphanumeric or reserved - CVTRA03Y declares no numeric "
                                + "item, and there is zero COMP-3 anywhere in app/cpy, so no nibble "
                                + "unpacking is ever needed either", span.name())
                        .isIn(PictureKind.ALPHANUMERIC, PictureKind.FILLER);
            }
        }

        @Test
        @DisplayName("this suite is self-contained: it depends on no parity harness and no case fixture")
        void thisSuiteIsSelfContained() {
            String source = suiteSource();

            String harnessPackage = "com.vsergeychik.carddemo." + "par" + "ity";
            String caseResourcePath = "/" + "par" + "ity" + "/";
            String platformDefault = "Charset." + "defaultCharset";
            String unnamedEncoding = "get" + "Bytes()";

            assertThat(Pattern.compile("^import\\s+(static\\s+)?" + Pattern.quote(harnessPackage)
                            + "\\.", Pattern.MULTILINE).matcher(source).find())
                    .as("the parity harness is a separate concern with its own 20 declarative cases "
                            + "per program; this is a unit suite over one repository, so it imports "
                            + "nothing from that package")
                    .isFalse();
            assertThat(source)
                    .as("and it reads no case fixture either - the only resource it opens is the "
                            + "shipped %s data", FIXTURE)
                    .doesNotContain(caseResourcePath);
            assertThat(Pattern.compile("^import .*\\*;", Pattern.MULTILINE).matcher(source).find())
                    .as("gate G52 applies to this file too")
                    .isFalse();
            assertThat(source)
                    .as("practice B8: the code page is always named, never taken from the platform")
                    .doesNotContain(platformDefault)
                    .doesNotContain(unnamedEncoding);
        }

        private static String repositorySource() {
            return moduleSource("app/java/src/main/java/com/vsergeychik/carddemo/transaction/"
                    + "TranTypeRepository.java");
        }

        private static String suiteSource() {
            return moduleSource("app/java/src/test/java/com/vsergeychik/carddemo/transaction/"
                    + "TranTypeRepositoryTest.java");
        }

        private static String moduleSource(String repositoryRelativePath) {
            Path candidate = Path.of("").toAbsolutePath();
            while (candidate != null) {
                Path resolved = candidate.resolve(repositoryRelativePath);
                if (Files.isRegularFile(resolved)) {
                    try {
                        return Files.readString(resolved, StandardCharsets.UTF_8);
                    } catch (IOException unreadable) {
                        throw new UncheckedIOException("Could not read " + resolved, unreadable);
                    }
                }
                candidate = candidate.getParent();
            }
            throw new IllegalStateException("Could not locate " + repositoryRelativePath
                    + " at or above " + Path.of("").toAbsolutePath());
        }
    }
}
