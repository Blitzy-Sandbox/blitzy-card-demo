package com.vsergeychik.carddemo.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import com.vsergeychik.carddemo.common.FileStatus.Outcome;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.transaction.DateParmReader.DateParm;
import com.vsergeychik.carddemo.transaction.DateParmReader.ReadResult;
import com.vsergeychik.carddemo.common.RecordImageForm;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

/**
 * Proves the {@code DATEPARM} reader against {@code app/cbl/CBTRN03C.cbl}.
 *
 * <p>This is the one contract in the estate where a batch program's parameter arrives in a
 * <strong>dataset</strong> rather than in a JCL {@code PARM}: {@code app/jcl/TRANREPT.jcl} declares a
 * {@code DATEPARM} DD, {@code CBTRN03C} opens it at {@code L166}, reads it exactly once at {@code L168}
 * and closes it at {@code L213}, and the eighty-byte record it reads carries the report's date range in
 * its leading twenty-one bytes. Three obligations follow, and each is held here:
 * <ul>
 *   <li>the record is decoded by absolute offset - {@code 10 + 1 + 10}, then a fifty-nine byte tail the
 *       program never looks at - so a differently shaped record is refused rather than misread;</li>
 *   <li>the three-armed {@code EVALUATE} at {@code L222-L229} keeps its three arms and no more, and an
 *       empty dataset is reported as the end of file it is rather than substituted for something
 *       friendlier;</li>
 *   <li>a backend refusal reaches the caller as a status <em>and</em> as the driver's own diagnosis, so
 *       the abend that follows can be traced to a cause.</li>
 * </ul>
 */
@DisplayName("DateParmReader - the report date range, read from a dataset rather than a PARM")
class DateParmReaderTest {

    /** The code page, always named. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** A stand-in dataset name: this suite proves the name comes from configuration. */
    private static final String DSNAME = "TEST.CARDDEMO.DATEPARM";

    /** The column name the stand-in backend describes. Deliberately not a copybook field name. */
    private static final String COLUMN = "PS_RECORD_IMAGE";

    /** The describe the reader issues for an open, a close and before any read. */
    private static final String DESCRIBE_SQL = "SELECT * FROM \"" + DSNAME + "\" WHERE 1 = 0";

    /** The read the reader issues: unordered, because a physical-sequential dataset has no key. */
    private static final String SELECT_SQL = "SELECT * FROM \"" + DSNAME + "\"";

    /** The first shipped date-range record, padded to its declared eighty bytes. */
    private static final String RECORD = "2022-01-01 2022-12-31" + " ".repeat(59);

    /**
     * A binding catalogue declaring {@code DATEPARM} at the width the copybook declares.
     *
     * @param dsname       the dataset name
     * @param recordLength the declared record width
     * @return the catalogue
     */
    private static DatasetBindings bindings(String dsname, int recordLength) {
        DatasetBindings catalogue = new DatasetBindings();
        catalogue.put(DateParmReader.DD_NAME, new DatasetBinding(dsname, "ps", false, "FB", null,
                recordLength, null, null, null, null, null));
        return catalogue;
    }

    /** The catalogue most of these tests use. */
    private static DatasetBindings validBindings() {
        return bindings(DSNAME, DateParmReader.RECORD_LENGTH);
    }

    /**
     * Stubs the describe a reader issues before it composes or probes anything.
     *
     * @param jdbcTemplate the mocked template
     */
    private static void stubDescribe(JdbcTemplate jdbcTemplate) {
        when(jdbcTemplate.query(eq(DESCRIBE_SQL),
                ArgumentMatchers.<ResultSetExtractor<String>>any())).thenReturn(FileStatus.OK);
    }

    /**
     * Stubs the read to return the given record images.
     *
     * @param jdbcTemplate the mocked template
     * @param rows         the images, which may contain a {@code null} element, or {@code null} to make
     *                     the template yield no result object at all
     */
    private static void stubRows(JdbcTemplate jdbcTemplate, List<String> rows) {
        // The row mapper now yields the stored BYTES, because the record-image representation - not this
        // reader - decides whether the column is read as characters or as bytes. The fixture rows stay
        // text here, where they are legible, and are encoded in the same code page the reader is given.
        List<byte[]> images = rows == null
                ? null
                : rows.stream().map(row -> row == null ? null : row.getBytes(ASCII)).toList();
        when(jdbcTemplate.query(eq(SELECT_SQL), ArgumentMatchers.<RowMapper<byte[]>>any()))
                .thenReturn(images);
    }

    /**
     * A reader over a mocked template whose describe already succeeds.
     *
     * @param jdbcTemplate the mocked template
     * @return the reader
     */
    private static DateParmReader reader(JdbcTemplate jdbcTemplate) {
        stubDescribe(jdbcTemplate);
        return new DateParmReader(jdbcTemplate, validBindings(), ASCII, RecordImageForm.CHARACTER);
    }

    // =================================================================================================
    // Construction. Everything checkable about the configuration is checked before the context finishes
    // starting, so a misconfiguration fails at refresh rather than mid-report.
    // =================================================================================================

    @Nested
    @DisplayName("Construction is configuration-bound and refuses to guess")
    class Construction {

        @Test
        @DisplayName("resolves the dataset name from configuration and composes both statements from it")
        void resolvesTheNameFromConfiguration() {
            DateParmReader reader = reader(mock(JdbcTemplate.class));

            assertThat(reader.datasetName()).isEqualTo(DSNAME);
            assertThat(reader.describeStatement()).isEqualTo(DESCRIBE_SQL);
            assertThat(reader.selectRecordSql()).isEqualTo(SELECT_SQL);
        }

        @Test
        @DisplayName("the read is deliberately unordered: a physical-sequential dataset has no key")
        void thePsReadIsUnordered() {
            // A PS file's records are in the order they were written and READ returns them in that
            // order, so an ORDER BY would reorder the file rather than make the read deterministic. A
            // keyed dataset is the opposite case, and its browse states its ordering explicitly.
            assertThat(reader(mock(JdbcTemplate.class)).selectRecordSql())
                    .doesNotContain("ORDER BY");
        }

        @Test
        @DisplayName("every collaborator is required and none is defaulted")
        void everyCollaboratorIsRequired() {
            DatasetBindings catalogue = validBindings();

            assertThatNullPointerException()
                    .isThrownBy(() -> new DateParmReader(null, catalogue, ASCII, RecordImageForm.CHARACTER))
                    .withMessageContaining("JdbcTemplate is required");
            assertThatNullPointerException()
                    .isThrownBy(() -> new DateParmReader(new JdbcTemplate(), null, ASCII, RecordImageForm.CHARACTER))
                    .withMessageContaining("carddemo.datasets");
            assertThatNullPointerException()
                    .isThrownBy(() -> new DateParmReader(new JdbcTemplate(), catalogue, null, RecordImageForm.CHARACTER))
                    .withMessageContaining("code page is stated explicitly");
        }

        @Test
        @DisplayName("a binding that disagrees with the copybook width is rejected, not accommodated")
        void aWidthDisagreementIsRejected() {
            DatasetBindings wrong = bindings(DSNAME, 79);

            assertThatIllegalStateException()
                    .isThrownBy(() -> new DateParmReader(new JdbcTemplate(), wrong, ASCII, RecordImageForm.CHARACTER))
                    .withMessageContaining("record length of 79");
        }

        @ParameterizedTest(name = "an absent dataset name [{0}] is rejected")
        @CsvSource(value = { "NULL", "''", "'   '" }, nullValues = "NULL")
        @DisplayName("a dataset name the configuration never supplied is rejected")
        void anAbsentDatasetNameIsRejected(String dsname) {
            DatasetBindings absent = bindings(dsname, DateParmReader.RECORD_LENGTH);

            assertThatIllegalStateException()
                    .isThrownBy(() -> new DateParmReader(new JdbcTemplate(), absent, ASCII, RecordImageForm.CHARACTER))
                    .withMessageContaining("carddemo.datasets." + DateParmReader.DD_NAME);
        }

        @ParameterizedTest(name = "a malformed dataset name [{0}] is rejected")
        @ValueSource(strings = { "BAD\tNAME", "TEST.\"ODD\".NAME", "TOOLONGQUALIFIER.X" })
        @DisplayName("a name that is not a well-formed z/OS dataset name never reaches a statement")
        void aMalformedDatasetNameIsRejected(String dsname) {
            DatasetBindings malformed = bindings(dsname, DateParmReader.RECORD_LENGTH);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new DateParmReader(new JdbcTemplate(), malformed, ASCII, RecordImageForm.CHARACTER))
                    .withMessageContaining("well-formed z/OS dataset name");
        }

        @Test
        @DisplayName("gate G21: the layout is contiguous from offset zero and sums to eighty bytes")
        void theLayoutProvesItsOwnWidth() {
            assertThat(reader(mock(JdbcTemplate.class)).layout().recordLength())
                    .isEqualTo(DateParmReader.RECORD_LENGTH)
                    .isEqualTo(80);
            assertThat(DateParmReader.START_DATE_LENGTH + DateParmReader.SEPARATOR_LENGTH
                    + DateParmReader.END_DATE_LENGTH).isEqualTo(DateParmReader.RECEIVER_LENGTH);
            assertThat(DateParmReader.RECEIVER_LENGTH + DateParmReader.DISCARDED_TAIL_LENGTH)
                    .isEqualTo(DateParmReader.RECORD_LENGTH);
        }
    }

    // =================================================================================================
    // 0500-DATEPARM-OPEN and 9500-DATEPARM-CLOSE - app/cbl/CBTRN03C.cbl:L466-L482 and L605-L621.
    // =================================================================================================

    @Nested
    @DisplayName("open and close - the two-way status the COBOL guard tests")
    class OpenAndClose {

        @Test
        @DisplayName("a describable dataset opens and closes with status '00'")
        void aDescribableDatasetOpensAndCloses() {
            DateParmReader reader = reader(mock(JdbcTemplate.class));

            assertThat(reader.open()).isEqualTo(FileStatus.OK);
            assertThat(reader.close()).isEqualTo(FileStatus.OK);
        }

        @Test
        @DisplayName("the probe describes the DATASET, not merely the connection")
        void theProbeIsDatasetScoped() throws SQLException {
            // A connection-only check succeeds against a reachable backend that has no such dataset, and
            // would then hand the job an end of file it should have seen as a failed open. This probe
            // names the dataset, so an absent one fails here - which is what an OPEN INPUT reports.
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            DateParmReader reader = new DateParmReader(jdbc, validBindings(), ASCII, RecordImageForm.CHARACTER);
            ResultSetMetaData metaData = mock(ResultSetMetaData.class);
            when(metaData.getColumnCount()).thenReturn(1);
            when(metaData.getColumnName(1)).thenReturn(COLUMN);
            ResultSet described = mock(ResultSet.class);
            when(described.getMetaData()).thenReturn(metaData);
            when(jdbc.query(eq(DESCRIBE_SQL), ArgumentMatchers.<ResultSetExtractor<String>>any()))
                    .thenAnswer(invocation -> invocation
                            .<ResultSetExtractor<String>>getArgument(1)
                            .extractData(described));

            assertThat(reader.open()).isEqualTo(FileStatus.OK);
        }

        @Test
        @DisplayName("a relation with no record-image column is unusable, not a successful open")
        void aRelationWithNoImageColumnIsUnusable() throws SQLException {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            DateParmReader reader = new DateParmReader(jdbc, validBindings(), ASCII, RecordImageForm.CHARACTER);
            ResultSetMetaData metaData = mock(ResultSetMetaData.class);
            when(metaData.getColumnCount()).thenReturn(0);
            ResultSet described = mock(ResultSet.class);
            when(described.getMetaData()).thenReturn(metaData);
            when(jdbc.query(eq(DESCRIBE_SQL), ArgumentMatchers.<ResultSetExtractor<String>>any()))
                    .thenAnswer(invocation -> invocation
                            .<ResultSetExtractor<String>>getArgument(1)
                            .extractData(described));

            // Reporting success on a relation this reader cannot read would hand the job a range it
            // never actually read.
            assertThat(reader.open()).isEqualTo(DateParmReader.PERMANENT_ERROR_STATUS);
        }

        @Test
        @DisplayName("a driver that supplies no metadata at all is unusable rather than successful")
        void noMetadataIsUnusable() throws SQLException {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            DateParmReader reader = new DateParmReader(jdbc, validBindings(), ASCII, RecordImageForm.CHARACTER);
            ResultSet described = mock(ResultSet.class);
            when(described.getMetaData()).thenReturn(null);
            when(jdbc.query(eq(DESCRIBE_SQL), ArgumentMatchers.<ResultSetExtractor<String>>any()))
                    .thenAnswer(invocation -> invocation
                            .<ResultSetExtractor<String>>getArgument(1)
                            .extractData(described));

            assertThat(reader.open()).isEqualTo(DateParmReader.PERMANENT_ERROR_STATUS);
        }

        @Test
        @DisplayName("a template that yields no status at all is a permanent error, not success")
        void aNullStatusIsAPermanentError() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            when(jdbc.query(anyString(), ArgumentMatchers.<ResultSetExtractor<String>>any()))
                    .thenReturn(null);
            DateParmReader reader = new DateParmReader(jdbc, validBindings(), ASCII, RecordImageForm.CHARACTER);

            assertThat(reader.open()).isEqualTo(DateParmReader.PERMANENT_ERROR_STATUS);
            assertThat(reader.close()).isEqualTo(DateParmReader.PERMANENT_ERROR_STATUS);
        }

        @Test
        @DisplayName("an unreachable dataset is a status, never a throw - the caller owns the abend")
        void anUnreachableDatasetIsAStatus() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            when(jdbc.query(anyString(), ArgumentMatchers.<ResultSetExtractor<String>>any()))
                    .thenThrow(new DataAccessResourceFailureException("gone",
                            new SQLException("gone", "08006", 17_002)));
            DateParmReader reader = new DateParmReader(jdbc, validBindings(), ASCII, RecordImageForm.CHARACTER);

            assertThat(reader.open()).isEqualTo(DateParmReader.PERMANENT_ERROR_STATUS);
            assertThat(reader.close()).isEqualTo(DateParmReader.PERMANENT_ERROR_STATUS);
        }
    }

    // =================================================================================================
    // 0550-DATEPARM-READ - app/cbl/CBTRN03C.cbl:L220-L243, performed exactly once from L168.
    // =================================================================================================

    @Nested
    @DisplayName("read - the three-armed EVALUATE, arm by arm")
    class Read {

        @Test
        @DisplayName("WHEN '00': the record decodes into the two dates and the separator")
        void aRecordDecodesIntoItsThreeSpans() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            DateParmReader reader = reader(jdbc);
            stubRows(jdbc, List.of(RECORD));

            ReadResult result = reader.read();

            assertThat(result.isFound()).isTrue();
            assertThat(result.status()).isEqualTo(FileStatus.OK);
            assertThat(result.outcome()).isEqualTo(Outcome.OK);
            assertThat(result.applResult()).isEqualTo(FileStatus.APPL_AOK);
            assertThat(result.diagnostic()).isEmpty();
            DateParm range = result.dateParm().orElseThrow();
            assertThat(range.startDate()).isEqualTo("2022-01-01");
            assertThat(range.separator()).isEqualTo(" ");
            assertThat(range.endDate()).isEqualTo("2022-12-31");
            assertThat(range.receiverImage())
                    .isEqualTo("2022-01-01 2022-12-31")
                    .hasSize(DateParmReader.RECEIVER_LENGTH);
        }

        @Test
        @DisplayName("WHEN '10': an empty dataset is the end of file, and is reported as such")
        void anEmptyDatasetIsEndOfFile() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            DateParmReader reader = reader(jdbc);
            stubRows(jdbc, Collections.emptyList());

            ReadResult result = reader.read();

            // Real legacy behaviour: the report body comes out empty. It is reported, never substituted.
            assertThat(result.isEndOfFile()).isTrue();
            assertThat(result.status()).isEqualTo(FileStatus.END_OF_FILE);
            assertThat(result.dateParm()).isEmpty();
            assertThat(result.applResult()).isEqualTo(FileStatus.APPL_EOF);
        }

        @Test
        @DisplayName("WHEN OTHER: a refusal carries a status AND what the backend said about it")
        void aRefusalCarriesTheBackendsOwnWords() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            DateParmReader reader = reader(jdbc);
            when(jdbc.query(eq(SELECT_SQL), ArgumentMatchers.<RowMapper<String>>any()))
                    .thenThrow(new DataAccessResourceFailureException("unreachable",
                            new SQLException("no route to host", "08001", 17_002)));

            ReadResult result = reader.read();

            assertThat(result.isOther()).isTrue();
            assertThat(result.status()).isEqualTo(DateParmReader.PERMANENT_ERROR_STATUS);
            assertThat(result.applResult()).isEqualTo(DateParmReader.APPL_RESULT_FATAL);
            // A permanent-error status says something went wrong and nothing about what. The driver's
            // own SQLSTATE distinguishes an unreachable backend from a missing dataset from a rejected
            // credential - three failures needing three different responses from whoever is on call.
            assertThat(result.diagnostic()).isPresent();
            assertThat(result.diagnostic().orElseThrow().sqlState()).isEqualTo("08001");
            assertThat(result.diagnostic().orElseThrow().vendorCode()).isEqualTo(17_002);
            assertThat(result.diagnostic().orElseThrow().connectionFailure()).isTrue();
        }

        @Test
        @DisplayName("a template that yields no result at all is WHEN OTHER, not an empty dataset")
        void aNullResultIsNotAnEmptyDataset() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            DateParmReader reader = reader(jdbc);
            stubRows(jdbc, null);

            ReadResult result = reader.read();

            assertThat(result.isEndOfFile()).isTrue();
        }

        @Test
        @DisplayName("a row whose image is absent is WHEN OTHER: there is a record and it cannot be read")
        void anAbsentRowImageIsAnIoDefect() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            DateParmReader reader = reader(jdbc);
            stubRows(jdbc, Collections.singletonList(null));

            ReadResult result = reader.read();

            assertThat(result.isOther()).isTrue();
            assertThat(result.isEndOfFile()).isFalse();
            assertThat(result.diagnostic())
                    .as("no backend refusal occurred, so there is nothing for the driver to diagnose")
                    .isEmpty();
        }

        @Test
        @DisplayName("a stored row that is not 80 bytes is WHEN OTHER, never a padded date range")
        void aRowOfTheWrongWidthIsAnIoDefect() {
            // Finding BD-05. Padded, this row would have decoded to the end date '2022-07   ' - a real
            // range, three weeks short of the one the operator asked for - and the job would have reported
            // it successfully with nothing anywhere saying the parameter had been altered. Reported on the
            // WHEN OTHER arm instead, which is where CBTRN03C displays 'ERROR READING DATEPARM FILE',
            // renders the status and abends.
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            DateParmReader reader = reader(jdbc);
            stubRows(jdbc, List.of("2022-01-01 2022-07"));

            ReadResult result = reader.read();

            assertThat(result.isOther()).isTrue();
            assertThat(result.isFound()).isFalse();
            assertThat(result.isEndOfFile()).isFalse();
            assertThat(result.dateParm()).isEmpty();
            assertThat(result.status()).isEqualTo(DateParmReader.PERMANENT_ERROR_STATUS);
            assertThat(result.applResult()).isEqualTo(DateParmReader.APPL_RESULT_FATAL);
            assertThat(result.diagnostic())
                    .as("no backend refusal occurred; the row itself is the defect")
                    .isEmpty();
        }

        @Test
        @DisplayName("an over-wide stored row is WHEN OTHER too, so the width check is symmetric")
        void anOverWideStoredRowIsAlsoAnIoDefect() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            DateParmReader reader = reader(jdbc);
            stubRows(jdbc, List.of(RECORD + " "));

            ReadResult result = reader.read();

            assertThat(result.isOther()).isTrue();
            assertThat(result.status()).isEqualTo(DateParmReader.PERMANENT_ERROR_STATUS);
        }

        @Test
        @DisplayName("a row of exactly 80 bytes still decodes, so the check is a width check")
        void aRowOfExactlyEightyBytesStillDecodes() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            DateParmReader reader = reader(jdbc);
            stubRows(jdbc, List.of(RECORD));

            ReadResult result = reader.read();

            assertThat(result.isFound()).isTrue();
            assertThat(result.dateParm().orElseThrow().receiverImage())
                    .isEqualTo("2022-01-01 2022-12-31");
        }

        @Test
        @DisplayName("only the first record is read: CBTRN03C performs 0550-DATEPARM-READ exactly once")
        void onlyTheFirstRecordIsRead() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            DateParmReader reader = reader(jdbc);
            stubRows(jdbc, Arrays.asList(RECORD, "1999-01-01 1999-12-31" + " ".repeat(59)));

            assertThat(reader.read().dateParm().orElseThrow().startDate()).isEqualTo("2022-01-01");
        }
    }

    // =================================================================================================
    // The decode seam, free of JDBC so that every offset is exercisable with no backend in the path.
    // =================================================================================================

    @Nested
    @DisplayName("decode - by absolute offset, and the tail the program never looks at")
    class Decode {

        @Test
        @DisplayName("the fifty-nine byte tail is skipped, whatever it holds")
        void theTailIsSkipped() {
            DateParmReader reader = reader(mock(JdbcTemplate.class));
            String withRubbishTail = "2022-01-01 2022-12-31" + "X".repeat(59);

            DateParm range = reader.decode(withRubbishTail);

            assertThat(range.receiverImage()).isEqualTo("2022-01-01 2022-12-31");
        }

        @Test
        @DisplayName("a short image is refused rather than padded into a different reporting range")
        void aShortImageIsRefused() {
            // The receiver move takes the first 21 bytes, so padding a short row is not the harmless
            // repair it looks like: a row truncated inside WS-END-DATE pads into a range the dataset
            // never held, and decodes without complaint. The 21-byte image here is the extreme case -
            // exactly the receiver - and it is refused like any other wrong width.
            DateParmReader reader = reader(mock(JdbcTemplate.class));

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> reader.decode("2022-01-01 2022-12-31"))
                    .withMessageContaining("declared PIC X(" + DateParmReader.RECORD_LENGTH + ")")
                    .withMessageContaining("stored record width in bytes = 21")
                    .withMessageContaining("80-into-21 move");
        }

        @Test
        @DisplayName("a row truncated inside the end date is refused, not turned into another range")
        void aRowTruncatedInsideTheEndDateIsRefused() {
            // The case the finding names. Padded, '2022-01-01 2022-07-06' truncated at 17 bytes would
            // have decoded to the end date '2022-07   ' - a real range, three weeks short, reported
            // successfully.
            DateParmReader reader = reader(mock(JdbcTemplate.class));
            String truncated = "2022-01-01 2022-07";

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> reader.decode(truncated))
                    .withMessageContaining("stored record width in bytes = 18");
            // And the same range at its full declared width decodes exactly as it always did, so the
            // check is a width check and not a refusal to read.
            String full = truncated + "-06" + " ".repeat(DateParmReader.RECORD_LENGTH - 21);
            assertThat(reader.decode(full).endDate()).isEqualTo("2022-07-06");
        }

        @Test
        @DisplayName("an over-wide image is refused rather than silently truncated")
        void anOverWideImageIsRefused() {
            DateParmReader reader = reader(mock(JdbcTemplate.class));

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> reader.decode(RECORD + " "))
                    .withMessageContaining("stored record width in bytes = 81");
        }

        @Test
        @DisplayName("both decode forms agree, and neither accepts an absent image")
        void bothFormsAgree() {
            DateParmReader reader = reader(mock(JdbcTemplate.class));

            assertThat(reader.decode(RECORD)).isEqualTo(reader.decode(RECORD.getBytes(ASCII)));
            assertThatNullPointerException().isThrownBy(() -> reader.decode((String) null));
            assertThatNullPointerException().isThrownBy(() -> reader.decode((byte[]) null));
        }

        @Test
        @DisplayName("a stored byte the code page cannot represent is refused, not substituted")
        void anUndecodableImageIsRefused() {
            DateParmReader reader = reader(mock(JdbcTemplate.class));

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> reader.decode("2022-01-01\u00e92022-12-31"))
                    .withMessageContaining("code page US-ASCII cannot represent")
                    .withMessageContaining("content is withheld deliberately");
        }
    }

    // =================================================================================================
    // The two outcome types' own invariants.
    // =================================================================================================

    @Nested
    @DisplayName("DateParm and ReadResult enforce their own invariants")
    class Invariants {

        @Test
        @DisplayName("every span carries its padding: never trimmed and never short")
        void everySpanIsExactlyItsDeclaredWidth() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new DateParm("2022-01-1", " ", "2022-12-31"))
                    .withMessageContaining(DateParmReader.START_DATE_FIELD);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new DateParm("2022-01-01", "", "2022-12-31"))
                    .withMessageContaining("FILLER");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new DateParm("2022-01-01", " ", "2022-12-3"))
                    .withMessageContaining(DateParmReader.END_DATE_FIELD);
            assertThatNullPointerException()
                    .isThrownBy(() -> new DateParm(null, " ", "2022-12-31"));
        }

        @Test
        @DisplayName("a status and its classification are two views of one fact and may not disagree")
        void aStatusAndItsClassificationMustAgree() {
            DateParm range = new DateParm("2022-01-01", " ", "2022-12-31");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ReadResult(FileStatus.OK, Outcome.END_OF_FILE,
                            Optional.empty(), Optional.empty()))
                    .withMessageContaining("must agree");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ReadResult(FileStatus.END_OF_FILE, Outcome.OK,
                            Optional.of(range), Optional.empty()))
                    .withMessageContaining("must agree");
        }

        @Test
        @DisplayName("the EVALUATE has three arms, so a keyed classification cannot be built")
        void onlyThreeArmsExist() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ReadResult(FileStatus.NOT_FOUND, Outcome.NOT_FOUND,
                            Optional.empty(), Optional.empty()))
                    .withMessageContaining("three arms");
        }

        @Test
        @DisplayName("the range is present exactly on the successful arm")
        void theRangeIsPresentExactlyOnSuccess() {
            DateParm range = new DateParm("2022-01-01", " ", "2022-12-31");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ReadResult(FileStatus.END_OF_FILE, Outcome.END_OF_FILE,
                            Optional.of(range), Optional.empty()))
                    .withMessageContaining("carries no date range");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ReadResult(FileStatus.OK, Outcome.OK, Optional.empty(),
                            Optional.empty()))
                    .withMessageContaining("carries the decoded date range");
        }

        @Test
        @DisplayName("a status the EVALUATE names explicitly cannot be classified as WHEN OTHER")
        void anExplicitlyNamedStatusIsNotWhenOther() {
            // Both of them: the EVALUATE names '00' and '10', and neither may arrive dressed as the
            // catch-all, or a caller switching on the outcome would take a different arm from one
            // switching on the status.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ReadResult(FileStatus.OK, Outcome.OTHER, Optional.empty(),
                            Optional.empty()))
                    .withMessageContaining("cannot be classified as WHEN OTHER");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ReadResult(FileStatus.END_OF_FILE, Outcome.OTHER,
                            Optional.empty(), Optional.empty()))
                    .withMessageContaining("cannot be classified as WHEN OTHER");
        }

        @Test
        @DisplayName("a status is exactly two characters, and no component may be null")
        void everyComponentIsRequired() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ReadResult.other("9"))
                    .withMessageContaining("exactly 2 characters");
            assertThatNullPointerException()
                    .isThrownBy(() -> new ReadResult(null, Outcome.OTHER, Optional.empty(),
                            Optional.empty()));
            assertThatNullPointerException()
                    .isThrownBy(() -> new ReadResult(DateParmReader.PERMANENT_ERROR_STATUS, null,
                            Optional.empty(), Optional.empty()));
            assertThatNullPointerException()
                    .isThrownBy(() -> new ReadResult(DateParmReader.PERMANENT_ERROR_STATUS,
                            Outcome.OTHER, null, Optional.empty()))
                    .withMessageContaining("empty range");
            assertThatNullPointerException()
                    .isThrownBy(() -> new ReadResult(DateParmReader.PERMANENT_ERROR_STATUS,
                            Outcome.OTHER, Optional.empty(), null))
                    .withMessageContaining("empty diagnostic");
            assertThatNullPointerException().isThrownBy(() -> ReadResult.found(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> ReadResult.other(DateParmReader.PERMANENT_ERROR_STATUS, null))
                    .withMessageContaining("diagnostic is required");
        }

        @Test
        @DisplayName("the end-of-file arm is built without a range and reports the EOF APPL-RESULT")
        void theEndOfFileArmIsWellFormed() {
            ReadResult endOfFile = ReadResult.endOfFile();

            assertThat(endOfFile.isEndOfFile()).isTrue();
            assertThat(endOfFile.isFound()).isFalse();
            assertThat(endOfFile.isOther()).isFalse();
            assertThat(endOfFile.applResult()).isEqualTo(FileStatus.APPL_EOF);
        }
    }
}
