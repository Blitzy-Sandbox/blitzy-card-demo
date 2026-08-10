package com.vsergeychik.carddemo.transaction;

import com.vsergeychik.carddemo.CardDemoApplication;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.ZonedSign;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.transaction.DalyRejectWriter.RecordSink;
import com.vsergeychik.carddemo.transaction.DalyRejectWriter.RejectsFile;
import com.vsergeychik.carddemo.transaction.model.DalyTranRecord;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link DalyRejectWriter}, the {@code DALYREJS} rejected-transaction writer.
 *
 * <p>Plain JUnit 5 throughout: no application context, no {@code JobLauncher}, no filesystem and no
 * database. Every record is collected by an in-memory {@link RecordSink}, which is the seam the class
 * under test exposes precisely so that this is possible (practice B10, gate G51). The one place a
 * database type appears at all is {@link JdbcSinkTests}, where a mocked {@link PreparedStatement} is
 * used to assert what reaches the driver - and even there nothing is connected to anything.
 *
 * <h2>Every expectation is transcribed from the sources, not from the implementation</h2>
 *
 * <p>The widths below - <strong>430</strong>, <strong>350</strong>, <strong>4</strong> and
 * <strong>76</strong> - and the offsets <strong>0</strong>, <strong>350</strong> and
 * <strong>354</strong> are written as literals rather than read from {@link DalyRejectWriter}'s own
 * constants, and that is the whole point of this suite. Asserting against the class's derived constants
 * instead would be circular: a wrong width would agree with itself.
 *
 * <table border="1">
 *   <caption>Where each literal comes from</caption>
 *   <tr><th>Literal</th><th>Source</th></tr>
 *   <tr><td>{@code LRECL=430}, {@code RECFM=F}</td><td>{@code app/jcl/POSTTRAN.jcl:L36}</td></tr>
 *   <tr><td>{@code FD-REJECT-RECORD PIC X(350)}</td><td>{@code app/cbl/CBTRN02C.cbl:L83}</td></tr>
 *   <tr><td>{@code FD-VALIDATION-TRAILER PIC X(80)}</td><td>{@code app/cbl/CBTRN02C.cbl:L84}</td></tr>
 *   <tr><td>{@code WS-VALIDATION-FAIL-REASON PIC 9(04)}</td>
 *       <td>{@code app/cbl/CBTRN02C.cbl:L181}</td></tr>
 *   <tr><td>{@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)}</td>
 *       <td>{@code app/cbl/CBTRN02C.cbl:L182}</td></tr>
 *   <tr><td>{@code DALYTRAN-RECORD} is 350 with a trailing {@code FILLER X(20)}</td>
 *       <td>{@code app/cpy/CVTRA06Y.cpy:L4-L18}</td></tr>
 *   <tr><td>The five reason codes and their texts</td>
 *       <td>{@code app/cbl/CBTRN02C.cbl:L385-L387}, {@code L397-L399}, {@code L410-L412},
 *           {@code L417-L419}, {@code L556-L558}</td></tr>
 *   <tr><td>{@code DALYTRAN-AMT} at offset 132, 11 bytes; {@code FILLER} at offset 330</td>
 *       <td>{@code app/cpy/CVTRA06Y.cpy:L5-L18}, offsets summed field by field</td></tr>
 *   <tr><td>Exactly six fixture rows carry a {@code '&#125;'} overpunch</td>
 *       <td>{@code app/data/ASCII/dailytran.txt}, counted over all 300 rows</td></tr>
 * </table>
 *
 * <h2>The one thing this suite reads at run time, and why</h2>
 *
 * <p>{@link RawSpanCopyTests} loads {@code /fixtures/dailytran.txt} - the module's shipped verbatim copy
 * of {@code app/data/ASCII/dailytran.txt} - from the test classpath. Nothing else in this file opens a
 * file, touches a database, a network or the clock, and nothing anywhere in it reads
 * {@code src/test/resources/parity/**} or imports from {@code com.vsergeychik.carddemo.parity}: the
 * writer's own {@link RecordSink} seam is the only input this suite needs (gate <strong>G51</strong>).
 *
 * <p>The fixture is loaded rather than transcribed because the assertion it supports is a claim
 * <em>about the real production-shaped data</em>: that a rejected transaction carrying a sign overpunch
 * the JVM's numeric model cannot represent still crosses into the rejects file unchanged. Transcribing
 * the row would make the test agree with the transcription instead of with the data. Two guards keep the
 * failure modes distinguishable, which is the objection to loading a resource at all: an absent resource
 * fails with a named assertion rather than an {@code IOException}, and the located row is cross-checked
 * against a transcribed literal, so an <em>altered</em> fixture fails as a mismatch rather than silently
 * weakening the test.
 *
 * <h2>Why a negative zero is the assertion that bites</h2>
 *
 * <p>{@code MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA} is a byte copy, and the difference between copying
 * and re-serialising is invisible on almost every record - measured, {@code decode} then {@code encode}
 * reproduces all 300 fixture rows exactly, because {@link DalyTranRecord#encode(Charset)} transcodes the
 * record area rather than rebuilding it from decoded values. The difference becomes visible on exactly
 * one input: a <em>negative zero</em>. {@code PIC S9(09)V99} distinguishes {@code '&#125;'} (negative,
 * final digit zero) from {@code '&#123;'} (positive, final digit zero); {@link BigDecimal} does not
 * distinguish {@code -0.00} from {@code 0.00} at all. So a value that went out through a number and back
 * loses the byte, and {@code 0000000000&#125;} returns as {@code 0000000000&#123;}.
 * {@link RawSpanCopyTests} drives both halves: it exhibits that loss, then asserts the writer does not
 * suffer it.
 *
 * <h2>Dependency surface</h2>
 *
 * <p>Everything imported here is either the class under test, one of its declared collaborators
 * ({@link DalyTranRecord}, {@link FileStatus}, {@link FixedWidthRecord},
 * {@code DataSourceConfig.DatasetBinding}), a type one of those signatures requires - {@link
 * RecordImageForm} is a constructor parameter of {@link DalyRejectWriter}, so it cannot be avoided
 * without failing to construct one - or test infrastructure. No wildcard imports anywhere, so every
 * correspondence stays auditable (gate <strong>G52</strong>).
 *
 * <p>The single import that is none of those is {@link CardDemoApplication}, and it earns its place.
 * {@link ContextWiringTests} exists to satisfy gate <strong>G3</strong>: that the writer bean really
 * wires against the {@code application.yml} and {@code application-test.yml} this module ships, rather
 * than against a catalogue this file invented. Booting a context needs the application's own entry
 * point; substituting a local configuration class would prove only that <em>some</em> catalogue
 * satisfies the constructor and would leave the shipped one untested.
 */
@DisplayName("DalyRejectWriter - the 430-byte DALYREJS reject record writer")
class DalyRejectWriterTest {

    /** The code page, named explicitly. Never the platform default (practice B8). */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** {@code LRECL=430} from app/jcl/POSTTRAN.jcl:L36, written as a literal on purpose. */
    private static final int LRECL = 430;

    /** {@code FD-REJECT-RECORD PIC X(350)} from app/cbl/CBTRN02C.cbl:L83. */
    private static final int TRAN_DATA_WIDTH = 350;

    /** {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} from app/cbl/CBTRN02C.cbl:L181. */
    private static final int REASON_WIDTH = 4;

    /** {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)} from app/cbl/CBTRN02C.cbl:L182. */
    private static final int DESC_WIDTH = 76;

    /** {@code CVTRA06Y}'s trailing {@code FILLER PIC X(20)}, from app/cpy/CVTRA06Y.cpy:L18. */
    private static final int FILLER_WIDTH = 20;

    /**
     * Where {@code FILLER} starts inside a {@code DALYTRAN-RECORD}: 0-based 330, obtained by summing
     * app/cpy/CVTRA06Y.cpy:L5-L17 - 16 + 2 + 4 + 10 + 100 + 11 + 9 + 50 + 50 + 10 + 16 + 26 + 26.
     */
    private static final int FILLER_OFFSET = 330;

    /**
     * Where {@code DALYTRAN-AMT PIC S9(09)V99} starts: 0-based 132, from app/cpy/CVTRA06Y.cpy:L10,
     * being 16 + 2 + 4 + 10 + 100.
     */
    private static final int AMT_OFFSET = 132;

    /** {@code PIC S9(09)V99} occupies 9 + 2 = 11 bytes, the sign overpunched into the last of them. */
    private static final int AMT_WIDTH = 11;

    /**
     * The 0-based offset of the byte carrying {@code DALYTRAN-AMT}'s low-order digit and its sign:
     * {@value #AMT_OFFSET} + {@value #AMT_WIDTH} - 1 = 142.
     */
    private static final int AMT_SIGN_OFFSET = AMT_OFFSET + AMT_WIDTH - 1;

    /** The module's shipped verbatim copy of {@code app/data/ASCII/dailytran.txt}. */
    private static final String FIXTURE_RESOURCE = "/fixtures/dailytran.txt";

    /** Rows in {@code app/data/ASCII/dailytran.txt}, counted. */
    private static final int FIXTURE_ROWS = 300;

    /**
     * The 1-based fixture lines whose {@code DALYTRAN-AMT} ends in {@code '&#125;'} - negative,
     * low-order digit zero. Counted over all {@value #FIXTURE_ROWS} rows of
     * {@code app/data/ASCII/dailytran.txt}: exactly six, and these are they.
     */
    private static final int[] NEGATIVE_ZERO_DIGIT_ROWS = {2, 55, 87, 150, 165, 210};

    /**
     * The stored {@code DALYTRAN-AMT} image of fixture line 2, transcribed from
     * {@code app/data/ASCII/dailytran.txt} 1-based columns 133-143. Held as a literal so that an
     * <em>altered</em> fixture fails as a named mismatch rather than quietly weakening
     * {@link RawSpanCopyTests}.
     */
    private static final String ROW_2_AMT_IMAGE = "0000009190}";

    /** Fixture line 2's {@code DALYTRAN-ID}, 1-based columns 1-16, transcribed. */
    private static final String ROW_2_ID = "0000000001774260";

    /**
     * A genuine negative zero: eleven digits, all zero, with the negative overpunch on the last.
     * {@code PIC S9(09)V99} can hold this; {@link BigDecimal} cannot, which is the whole point.
     */
    private static final String NEGATIVE_ZERO_AMT_IMAGE = "0000000000}";

    /** What a {@link BigDecimal} round trip turns {@link #NEGATIVE_ZERO_AMT_IMAGE} into. */
    private static final String POSITIVE_ZERO_AMT_IMAGE = "0000000000{";

    /**
     * A well-formed z/OS dataset name for the tests, carrying the {@code (+1)} relative generation the
     * real binding uses because {@code DALYREJS} is a generation data group
     * (app/jcl/DALYREJS.jcl:L24-L28). No production dataset name appears in Java (gate G46).
     */
    private static final String TEST_DSNAME = "TEST.M2.DALYREJS(+1)";

    // =================================================================================================
    // Fixtures.
    // =================================================================================================

    /**
     * A {@code DALYREJS} catalogue with the given geometry.
     *
     * @param recordLength the record length to declare
     * @param recordFormat the record format to declare, or {@code null} to omit the key
     * @return the catalogue
     */
    private static DatasetBindings bindings(int recordLength, String recordFormat) {
        return catalogue(TEST_DSNAME, recordLength, recordFormat);
    }

    /**
     * A {@code DALYREJS} catalogue naming an arbitrary location, so the dataset-name checks can be
     * driven over every shape a deployment might supply.
     *
     * @param dsname       the value to bind, which need not be a dataset name
     * @param recordLength the record length to declare
     * @param recordFormat the record format to declare, or {@code null} to omit the key
     * @return the catalogue
     */
    private static DatasetBindings catalogue(String dsname, int recordLength, String recordFormat) {
        DatasetBindings bindings = new DatasetBindings();
        bindings.put(DalyRejectWriter.DD_NAME, new DatasetBinding(dsname, "sequential", true,
                recordFormat, 0, recordLength, null, null, null, null, null));
        return bindings;
    }

    /** A correctly configured writer over {@link #ASCII}. */
    private static DalyRejectWriter writer() {
        return new DalyRejectWriter(new JdbcTemplate(), ASCII, bindings(LRECL, "F"),
                RecordImageForm.CHARACTER);
    }

    /**
     * A synthetic {@value #TRAN_DATA_WIDTH}-byte transaction image whose last
     * {@value #FILLER_WIDTH} bytes differ from the rest, so the trailing {@code FILLER X(20)} is
     * individually visible in an emitted record.
     *
     * @param body the character filling the first 330 bytes
     * @return exactly {@value #TRAN_DATA_WIDTH} bytes
     */
    private static byte[] image(char body) {
        return (String.valueOf(body).repeat(TRAN_DATA_WIDTH - FILLER_WIDTH)
                + "F".repeat(FILLER_WIDTH)).getBytes(ASCII);
    }

    /** Collects emitted records in call order and can be told what to answer. */
    private static final class Collector implements RecordSink {

        private final List<byte[]> records = new ArrayList<>();
        private FileStatus.Outcome writeAnswer = FileStatus.Outcome.OK;
        private FileStatus.Outcome openAnswer = FileStatus.Outcome.OK;
        private FileStatus.Outcome closeAnswer = FileStatus.Outcome.OK;
        private int opens;
        private int closes;

        @Override
        public FileStatus.Outcome write(byte[] recordImage) {
            records.add(recordImage);
            return writeAnswer;
        }

        @Override
        public FileStatus.Outcome open() {
            opens++;
            return openAnswer;
        }

        @Override
        public FileStatus.Outcome close() {
            closes++;
            return closeAnswer;
        }

        /** The one record written, as characters. */
        String only() {
            assertThat(records).hasSize(1);
            return new String(records.get(0), ASCII);
        }
    }

    /**
     * The shipped fixture's rows, each exactly {@value #TRAN_DATA_WIDTH} characters.
     *
     * <p>An absent resource is reported as a named assertion failure rather than as an
     * {@code IOException}, so a packaging problem can never be mistaken at the gate for a parity
     * defect. The row count and row width are asserted here rather than in a caller, so every caller
     * gets the check for free.
     *
     * @return all {@value #FIXTURE_ROWS} rows in file order
     */
    private static List<String> fixtureRows() {
        try (InputStream stream = DalyRejectWriterTest.class.getResourceAsStream(FIXTURE_RESOURCE)) {
            assertThat(stream)
                    .as("the shipped fixture %s must be on the test classpath", FIXTURE_RESOURCE)
                    .isNotNull();
            List<String> rows = new String(stream.readAllBytes(), ASCII).lines()
                    .filter(row -> !row.isEmpty())
                    .toList();
            assertThat(rows).as("%s holds 300 rows of app/data/ASCII/dailytran.txt", FIXTURE_RESOURCE)
                    .hasSize(FIXTURE_ROWS);
            assertThat(rows).allSatisfy(row -> assertThat(row).hasSize(TRAN_DATA_WIDTH));
            return rows;
        } catch (IOException unreadable) {
            throw new UncheckedIOException("Could not read " + FIXTURE_RESOURCE, unreadable);
        }
    }

    /**
     * One shipped fixture row, by its 1-based line number as quoted throughout this file.
     *
     * @param line the 1-based line number
     * @return exactly {@value #TRAN_DATA_WIDTH} bytes in {@link #ASCII}
     */
    private static byte[] fixtureRow(int line) {
        return fixtureRows().get(line - 1).getBytes(ASCII);
    }

    /** The reason code and description pairs the program moves, transcribed from the source. */
    private static Stream<Arguments> reasonPairs() {
        return Stream.of(
                Arguments.of(100, "0100", "INVALID CARD NUMBER FOUND"),
                Arguments.of(101, "0101", "ACCOUNT RECORD NOT FOUND"),
                Arguments.of(102, "0102", "OVERLIMIT TRANSACTION"),
                Arguments.of(103, "0103", "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"),
                Arguments.of(109, "0109", "ACCOUNT RECORD NOT FOUND"));
    }

    // =================================================================================================
    // The dataset contract.
    // =================================================================================================

    @Nested
    @DisplayName("The dataset contract - app/jcl/POSTTRAN.jcl:L34-L38")
    class DatasetContractTests {

        @Test
        @DisplayName("names DD DALYREJS, 430 bytes, RECFM=F, BLKSIZE=0")
        void declaresTheJclGeometry() {
            assertThat(DalyRejectWriter.DD_NAME).isEqualTo("DALYREJS");
            assertThat(DalyRejectWriter.RECORD_LENGTH).isEqualTo(430);
            assertThat(DalyRejectWriter.RECORD_FORMAT).isEqualTo("F");
            assertThat(DalyRejectWriter.BLOCK_SIZE).isZero();
            assertThat(writer().recordLength()).isEqualTo(430);
            assertThat(writer().datasetCharset()).isEqualTo(ASCII);
            assertThat(writer().datasetBinding().recordFormat()).isEqualTo("F");
            assertThat(writer().datasetBinding().dsname()).isEqualTo(TEST_DSNAME);
        }

        @Test
        @DisplayName("is fixed UNBLOCKED - F, not the FB its two sibling outputs use")
        void isFixedUnblockedRatherThanBlocked() {
            assertThat(DalyRejectWriter.RECORD_FORMAT).isEqualTo("F").isNotEqualTo("FB");
            assertThat(TranReportWriter.RECORD_FORMAT).isEqualTo("FB");
        }

        @Test
        @DisplayName("430 decomposes as 350 + 4 + 76 with no gap and no overlap")
        void theRecordDecomposesExactly() {
            // The derivation, by addition, from the two sources rather than from this module:
            //
            //   350  app/cpy/CVTRA06Y.cpy:L4-L18, 01 DALYTRAN-RECORD, being
            //        16 + 2 + 4 + 10 + 100 + 11 + 9 + 50 + 50 + 10 + 16 + 26 + 26 + 20
            // +   4  app/cbl/CBTRN02C.cbl:L181, 05 WS-VALIDATION-FAIL-REASON      PIC 9(04)
            // +  76  app/cbl/CBTRN02C.cbl:L182, 05 WS-VALIDATION-FAIL-REASON-DESC PIC X(76)
            // = 430  app/jcl/POSTTRAN.jcl:L36,   DCB=(RECFM=F,LRECL=430,BLKSIZE=0)
            //
            // The JCL is the tie-breaker. If the copybook arithmetic and the DD ever disagreed, the DD
            // is what the dataset was allocated with, so the DD wins and the arithmetic is wrong.
            assertThat(16 + 2 + 4 + 10 + 100 + 11 + 9 + 50 + 50 + 10 + 16 + 26 + 26 + FILLER_WIDTH)
                    .as("app/cpy/CVTRA06Y.cpy sums to 350 only with its trailing FILLER X(20)")
                    .isEqualTo(TRAN_DATA_WIDTH);
            assertThat(TRAN_DATA_WIDTH + REASON_WIDTH + DESC_WIDTH).isEqualTo(LRECL);
            assertThat(DalyRejectWriter.FD_REJECT_RECORD_LENGTH).isEqualTo(TRAN_DATA_WIDTH);
            assertThat(DalyRejectWriter.WS_VALIDATION_FAIL_REASON_LENGTH).isEqualTo(REASON_WIDTH);
            assertThat(DalyRejectWriter.WS_VALIDATION_FAIL_REASON_DESC_LENGTH).isEqualTo(DESC_WIDTH);
            assertThat(DalyRejectWriter.FD_REJECT_RECORD_OFFSET).isZero();
            assertThat(DalyRejectWriter.WS_VALIDATION_FAIL_REASON_OFFSET).isEqualTo(350);
            assertThat(DalyRejectWriter.WS_VALIDATION_FAIL_REASON_DESC_OFFSET).isEqualTo(354);

            // The FD's own view: 350 + 80, which must cover the same bytes as 350 + 4 + 76.
            assertThat(DalyRejectWriter.VALIDATION_TRAILER_OFFSET).isEqualTo(350);
            assertThat(DalyRejectWriter.VALIDATION_TRAILER_LENGTH).isEqualTo(80);
            assertThat(REASON_WIDTH + DESC_WIDTH).isEqualTo(80);

            // Storage spans are contiguous from 0 and sum to exactly 430; the layout's own geometry
            // self-check already refuses anything else, and this states the outcome.
            List<FieldSpan> storage = DalyRejectWriter.FD_REJS_RECORD_LAYOUT.storageSpans();
            assertThat(storage).hasSize(3);
            int cursor = 0;
            for (FieldSpan span : storage) {
                assertThat(span.offset()).isEqualTo(cursor);
                cursor = span.endOffsetExclusive();
            }
            assertThat(cursor).isEqualTo(LRECL);
            assertThat(DalyRejectWriter.FD_REJS_RECORD_LAYOUT.recordLength()).isEqualTo(LRECL);
        }

        @Test
        @DisplayName("carries the FD's 80-byte trailer as a REDEFINES overlay, not as extra storage")
        void theTrailerViewIsAnOverlay() {
            assertThat(DalyRejectWriter.FD_REJS_RECORD_LAYOUT.redefinitions())
                    .singleElement()
                    .satisfies(span -> {
                        assertThat(span.name()).isEqualTo("FD-VALIDATION-TRAILER");
                        assertThat(span.offset()).isEqualTo(350);
                        assertThat(span.length()).isEqualTo(80);
                        assertThat(span.redefinition()).isTrue();
                    });
        }

        @Test
        @DisplayName("names the copybook items, not the Java fields")
        void spanNamesAreCopybookNames() {
            assertThat(DalyRejectWriter.FD_REJS_RECORD).isEqualTo("FD-REJS-RECORD");
            assertThat(DalyRejectWriter.FD_REJECT_RECORD).isEqualTo("FD-REJECT-RECORD");
            assertThat(DalyRejectWriter.FD_VALIDATION_TRAILER).isEqualTo("FD-VALIDATION-TRAILER");
            assertThat(DalyRejectWriter.WS_VALIDATION_FAIL_REASON)
                    .isEqualTo("WS-VALIDATION-FAIL-REASON");
            assertThat(DalyRejectWriter.WS_VALIDATION_FAIL_REASON_DESC)
                    .isEqualTo("WS-VALIDATION-FAIL-REASON-DESC");
        }

        @Test
        @DisplayName("copies a span exactly as wide as a DALYTRAN-RECORD, so L447 pads nothing")
        void theCopiedSpanMatchesTheDalytranWidth() {
            assertThat(DalyRejectWriter.FD_REJECT_RECORD_LENGTH)
                    .isEqualTo(DalyTranRecord.RECORD_LENGTH)
                    .isEqualTo(350);
        }
    }

    // =================================================================================================
    // Gate G20, on emitted bytes rather than on constants.
    //
    // RECFM=F is not decoration. Fixed unblocked means the file is addressed by multiplication: record
    // n begins at n * 430 and ends at (n + 1) * 430, with nothing in between to find it by. There is no
    // record-descriptor word, no delimiter and no terminator - those belong to RECFM=V and to
    // line-oriented files respectively, and either one appearing inside a record image here would shift
    // every following record while leaving each individual record looking plausible.
    // =================================================================================================

    @Nested
    @DisplayName("Gate G20 - fixed unblocked geometry, measured on the emitted bytes")
    class FixedFormatGeometryTests {

        @Test
        @DisplayName("the three spans occupy 0..349, 350..353 and 354..429 of the emitted record")
        void theThreeSpansOccupyTheirAbsoluteRanges() {
            // Mutually distinguishable content in all three spans at once, so a span that overlapped,
            // shifted or overwrote its neighbour cannot pass by coincidence. The transaction body is
            // 'T', its FILLER is 'F' (see image(char)), the reason is 103 and its text is the longest
            // of the five at 42 characters - long enough to prove the description starts at 354 and
            // short enough to leave 34 pad bytes proving it ends at 429.
            Collector sink = new Collector();
            try (RejectsFile file = writer().openOutput(sink)) {
                file.writeRejectRec(image('T'), 103,
                        DalyRejectWriter.DESC_TRANSACTION_AFTER_EXPIRATION);
            }
            byte[] record = sink.records.get(0);
            assertThat(record).hasSize(LRECL);

            String recordImage = new String(record, ASCII);
            // 0..349 - REJECT-TRAN-DATA, app/cbl/CBTRN02C.cbl:L177.
            assertThat(recordImage.substring(0, TRAN_DATA_WIDTH))
                    .as("REJECT-TRAN-DATA occupies bytes 0..349")
                    .hasSize(TRAN_DATA_WIDTH)
                    .isEqualTo("T".repeat(TRAN_DATA_WIDTH - FILLER_WIDTH) + "F".repeat(FILLER_WIDTH));
            // 350..353 - WS-VALIDATION-FAIL-REASON PIC 9(04), app/cbl/CBTRN02C.cbl:L181.
            assertThat(recordImage.substring(350, 354))
                    .as("WS-VALIDATION-FAIL-REASON occupies bytes 350..353")
                    .hasSize(REASON_WIDTH)
                    .isEqualTo("0103");
            // 354..429 - WS-VALIDATION-FAIL-REASON-DESC PIC X(76), app/cbl/CBTRN02C.cbl:L182.
            assertThat(recordImage.substring(354, 430))
                    .as("WS-VALIDATION-FAIL-REASON-DESC occupies bytes 354..429")
                    .hasSize(DESC_WIDTH)
                    .isEqualTo("TRANSACTION RECEIVED AFTER ACCT EXPIRATION" + " ".repeat(34));

            // And the three ranges tile the record exactly: no byte belongs to two spans or to none.
            assertThat(recordImage.substring(0, TRAN_DATA_WIDTH) + recordImage.substring(350, 354)
                    + recordImage.substring(354, 430)).isEqualTo(recordImage);
        }

        @Test
        @DisplayName("N records concatenate to exactly N * 430, with record k at offset k * 430")
        void recordsAreAddressedByMultiplication() {
            char[] bodies = {'0', '1', '2', '3', '4'};
            Collector sink = new Collector();
            try (RejectsFile file = writer().openOutput(sink)) {
                for (char body : bodies) {
                    file.writeRejectRec(image(body), 100);
                }
            }

            // Every record the same length - the defining property of RECFM=F.
            assertThat(sink.records).hasSize(bodies.length)
                    .allSatisfy(record -> assertThat(record).hasSize(LRECL));

            // Lay them down as the dataset would and address them arithmetically.
            byte[] dataset = new byte[bodies.length * LRECL];
            for (int index = 0; index < bodies.length; index++) {
                System.arraycopy(sink.records.get(index), 0, dataset, index * LRECL, LRECL);
            }
            assertThat(dataset).hasSize(bodies.length * LRECL);
            for (int index = 0; index < bodies.length; index++) {
                assertThat((char) dataset[index * LRECL])
                        .as("record %d begins at %d * 430", index, index)
                        .isEqualTo(bodies[index]);
                assertThat(new String(dataset, index * LRECL + 350, 4, ASCII))
                        .as("record %d's trailer begins at %d * 430 + 350", index, index)
                        .isEqualTo("0100");
            }
        }

        @Test
        @DisplayName("carries no record-descriptor word - byte 0 is the transaction's byte 0")
        void thereIsNoLengthPrefix() {
            // A RECFM=V record would begin with a four-byte RDW carrying its own length. This one does
            // not: the very first byte of the emitted image is the first byte of DALYTRAN-ID.
            byte[] sent = image('Z');
            Collector sink = new Collector();
            try (RejectsFile file = writer().openOutput(sink)) {
                file.writeRejectRec(sent, 102);
            }
            byte[] record = sink.records.get(0);
            assertThat(record[0]).isEqualTo(sent[0]).isEqualTo((byte) 'Z');
            assertThat(Arrays.copyOf(record, 4)).isEqualTo(Arrays.copyOf(sent, 4));
            // Not the big-endian 430 an RDW would hold, in either of its two plausible widths.
            assertThat(new byte[] {record[0], record[1]}).isNotEqualTo(new byte[] {0x01, (byte) 0xAE});
        }

        @Test
        @DisplayName("carries no delimiter and no terminator inside the record image")
        void thereIsNoDelimiterOrTerminator() {
            // The fixture is a line-oriented text file, so its rows are newline-separated on disk. The
            // separator is the file's, not the record's: a 350-byte row carries none, and the writer
            // must not introduce one either at the trailer boundary or after the record.
            byte[] sent = fixtureRow(NEGATIVE_ZERO_DIGIT_ROWS[0]);
            Collector sink = new Collector();
            try (RejectsFile file = writer().openOutput(sink)) {
                file.writeRejectRec(sent, 100);
                file.writeRejectRec(sent, 101);
            }
            assertThat(sink.records).allSatisfy(record -> {
                assertThat(record).hasSize(LRECL);
                assertThat(new String(record, ASCII))
                        .doesNotContain("\n")
                        .doesNotContain("\r")
                        .doesNotContain("\u0000");
                // The last byte is a description pad byte, not a terminator.
                assertThat(record[LRECL - 1]).isEqualTo((byte) ' ');
            });
        }

        @Test
        @DisplayName("refuses a record area of any width but 430 rather than writing it")
        void aWrongWidthAreaIsRefusedRatherThanWritten() {
            // The guard this drives is unreachable through the public API - every move keeps the area
            // at 430 - which is exactly why it is worth proving it is there. Substituting a short area
            // is the only way to ask "and if the area were ever the wrong width?", and the answer must
            // be a refusal: in a fixed-format file a 429-byte record does not merely truncate itself,
            // it moves every record after it. The field is located by TYPE rather than by name so a
            // rename in the class under test does not turn this into a spurious failure.
            Collector sink = new Collector();
            RejectsFile file = writer().openOutput(sink);
            Field area = Arrays.stream(RejectsFile.class.getDeclaredFields())
                    .filter(field -> field.getType() == FixedWidthRecord.class)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("RejectsFile must hold its record area in a "
                            + "FixedWidthRecord field; without one the 430-byte width guard cannot be "
                            + "reached and gate G20 rests on nothing but the happy path"));
            area.setAccessible(true);
            assertThatCode(() -> area.set(file, new FixedWidthRecord(LRECL - 1, ASCII)))
                    .doesNotThrowAnyException();

            assertThatIllegalStateException().isThrownBy(file::writeRejectRec)
                    .withMessageContaining("429")
                    .withMessageContaining("430")
                    .withMessageContaining("RECFM=F");
            assertThat(sink.records).as("a wrong-width record must never reach the dataset").isEmpty();
        }
    }

    // =================================================================================================
    // The reject reasons.
    // =================================================================================================

    @Nested
    @DisplayName("The reject reasons - transcribed from app/cbl/CBTRN02C.cbl")
    class ReasonTests {

        @Test
        @DisplayName("declares the five codes the program sets, plus 0 for 'not rejected'")
        void declaresEveryCode() {
            assertThat(DalyRejectWriter.REASON_NONE).isZero();
            assertThat(DalyRejectWriter.REASON_INVALID_CARD_NUMBER).isEqualTo(100);
            assertThat(DalyRejectWriter.REASON_ACCOUNT_RECORD_NOT_FOUND).isEqualTo(101);
            assertThat(DalyRejectWriter.REASON_OVERLIMIT_TRANSACTION).isEqualTo(102);
            assertThat(DalyRejectWriter.REASON_TRANSACTION_AFTER_EXPIRATION).isEqualTo(103);
            assertThat(DalyRejectWriter.REASON_ACCOUNT_NOT_FOUND_ON_REWRITE).isEqualTo(109);
        }

        @Test
        @DisplayName("declares each description byte-exactly and within the 76-byte receiver")
        void declaresEveryDescription() {
            assertThat(DalyRejectWriter.DESC_INVALID_CARD_NUMBER)
                    .isEqualTo("INVALID CARD NUMBER FOUND").hasSize(25);
            assertThat(DalyRejectWriter.DESC_ACCOUNT_RECORD_NOT_FOUND)
                    .isEqualTo("ACCOUNT RECORD NOT FOUND").hasSize(24);
            assertThat(DalyRejectWriter.DESC_OVERLIMIT_TRANSACTION)
                    .isEqualTo("OVERLIMIT TRANSACTION").hasSize(21);
            assertThat(DalyRejectWriter.DESC_TRANSACTION_AFTER_EXPIRATION)
                    .isEqualTo("TRANSACTION RECEIVED AFTER ACCT EXPIRATION").hasSize(42);
            assertThat(DalyRejectWriter.DESC_SPACES).isEmpty();

            Stream.of(DalyRejectWriter.DESC_INVALID_CARD_NUMBER,
                            DalyRejectWriter.DESC_ACCOUNT_RECORD_NOT_FOUND,
                            DalyRejectWriter.DESC_OVERLIMIT_TRANSACTION,
                            DalyRejectWriter.DESC_TRANSACTION_AFTER_EXPIRATION)
                    .forEach(text -> assertThat(text.length()).isLessThanOrEqualTo(DESC_WIDTH));
        }

        @ParameterizedTest(name = "reason {0} pairs with \"{2}\"")
        @MethodSource("com.vsergeychik.carddemo.transaction.DalyRejectWriterTest#reasonPairs")
        @DisplayName("pairs each code with the text the program moves beside it")
        void resolvesEveryDescription(int code, String image, String text) {
            assertThat(DalyRejectWriter.descriptionOfReason(code)).isEqualTo(text);
            assertThat(image).hasSize(REASON_WIDTH);
        }

        @ParameterizedTest(name = "an unknown code {0} resolves to spaces")
        @ValueSource(ints = {0, 1, 99, 104, 108, 110, 999, 9999})
        @DisplayName("resolves a code the program never sets to spaces, rather than raising")
        void resolvesUnknownCodesToSpaces(int code) {
            assertThat(DalyRejectWriter.descriptionOfReason(code))
                    .isEqualTo(DalyRejectWriter.DESC_SPACES);
        }

        @Test
        @DisplayName("101 and 109 are distinct codes that deliberately share one text")
        void twoCodesShareOneText() {
            assertThat(DalyRejectWriter.REASON_ACCOUNT_RECORD_NOT_FOUND)
                    .isNotEqualTo(DalyRejectWriter.REASON_ACCOUNT_NOT_FOUND_ON_REWRITE);
            assertThat(DalyRejectWriter.descriptionOfReason(101))
                    .isEqualTo(DalyRejectWriter.descriptionOfReason(109));
        }

        @Test
        @DisplayName("109 is renderable here but unreachable from the posting job - and stays that way")
        void oneOfTheFiveCodesCanNeverReachThisWriter() {
            // Reason 109 is real, transcribed, and dead. Tracing app/cbl/CBTRN02C.cbl:
            //
            //   L208-L209  MOVE 0 TO WS-VALIDATION-FAIL-REASON
            //              MOVE SPACES TO WS-VALIDATION-FAIL-REASON-DESC   <- reset, per record
            //   L210       PERFORM 1500-VALIDATE-TRAN                      <- may set 100/101/102/103
            //   L211       IF WS-VALIDATION-FAIL-REASON = 0
            //   L212         PERFORM 2000-POST-TRANSACTION                 <- the POST arm
            //   L213-L215  ELSE ADD 1 TO WS-REJECT-COUNT
            //                   PERFORM 2500-WRITE-REJECT-REC              <- the REJECT arm
            //
            // 109 is set at L556-L558, inside 2800-UPDATE-ACCOUNT-REC, which 2000-POST-TRANSACTION
            // performs. That is the POST arm - reached only when the reason was already 0, and the
            // reject decision at L211 is behind it, never in front of it. The next iteration then
            // resets the reason at L208. So a failed account rewrite produces no reject record at all:
            // it sets a field nobody reads and the run continues.
            //
            // That is a defect in the legacy program, and preserving it is required (practice B5);
            // routing 109 to this writer would be a behaviour change (practice B4). So the code and its
            // text are transcribed and the trailer will render them - a trailer is a value object and
            // does not police which paragraph filled it - but nothing here creates a path that emits
            // one, and nothing should be added that does.
            assertThat(DalyRejectWriter.REASON_ACCOUNT_NOT_FOUND_ON_REWRITE).isEqualTo(109);
            assertThat(DalyRejectWriter.descriptionOfReason(109))
                    .isEqualTo("ACCOUNT RECORD NOT FOUND");

            Collector sink = new Collector();
            try (RejectsFile file = writer().openOutput(sink)) {
                assertThat(file.writeRejectRec(image('9'),
                        DalyRejectWriter.REASON_ACCOUNT_NOT_FOUND_ON_REWRITE))
                        .isEqualTo(FileStatus.Outcome.OK);
            }
            String record = sink.only();
            assertThat(record.substring(350, 354)).isEqualTo("0109");
            assertThat(record.substring(354, 430))
                    .isEqualTo("ACCOUNT RECORD NOT FOUND" + " ".repeat(DESC_WIDTH - 24));
        }
    }

    // =================================================================================================
    // 2500-WRITE-REJECT-REC. app/cbl/CBTRN02C.cbl:L446-L465.
    // =================================================================================================

    @Nested
    @DisplayName("2500-WRITE-REJECT-REC - the emitted record")
    class WriteTests {

        @Test
        @DisplayName("every written record is exactly 430 bytes")
        void everyRecordIs430Bytes() {
            Collector sink = new Collector();
            try (RejectsFile file = writer().openOutput(sink)) {
                file.writeRejectRec(image('A'), 102);
                file.writeRejectRec(image('B'), 100, "");
                file.writeRejectRec(image('C'), 0, "z".repeat(200));
            }
            assertThat(sink.records).hasSize(3).allSatisfy(record ->
                    assertThat(record).hasSize(LRECL));
        }

        @Test
        @DisplayName("the first 350 bytes are byte-identical, trailing FILLER X(20) included")
        void theTransactionIsCopiedVerbatim() {
            byte[] sent = image('A');
            Collector sink = new Collector();
            try (RejectsFile file = writer().openOutput(sink)) {
                file.writeRejectRec(sent, 100);
            }
            byte[] written = sink.records.get(0);
            assertThat(Arrays.copyOf(written, TRAN_DATA_WIDTH)).isEqualTo(sent);
            // The FILLER occupies 330..349 and is not re-derived from anything.
            assertThat(new String(written, TRAN_DATA_WIDTH - FILLER_WIDTH, FILLER_WIDTH, ASCII))
                    .isEqualTo("F".repeat(FILLER_WIDTH));
        }

        @ParameterizedTest(name = "reason {0} renders as \"{1}\" and its text pads to 76")
        @MethodSource("com.vsergeychik.carddemo.transaction.DalyRejectWriterTest#reasonPairs")
        @DisplayName("renders every reason code left-zero-filled and every text right-space-padded")
        void rendersEveryReason(int code, String codeImage, String text) {
            Collector sink = new Collector();
            try (RejectsFile file = writer().openOutput(sink)) {
                file.writeRejectRec(image('D'), code);
            }
            String record = sink.only();
            assertThat(record).hasSize(LRECL);
            assertThat(record.substring(350, 354)).isEqualTo(codeImage);
            assertThat(record.substring(354, 430))
                    .hasSize(DESC_WIDTH)
                    .isEqualTo(text + " ".repeat(DESC_WIDTH - text.length()));
        }

        @ParameterizedTest(name = "{0} is stored as \"{1}\"")
        @CsvSource({"0, 0000", "1, 0001", "7, 0007", "99, 0099", "100, 0100", "102, 0102",
                "999, 0999", "9999, 9999"})
        @DisplayName("zero-fills the reason on the LEFT, so 102 is \"0102\" and never \"102 \"")
        void zeroFillsTheReasonOnTheLeft(int code, String expected) {
            Collector sink = new Collector();
            try (RejectsFile file = writer().openOutput(sink)) {
                file.writeRejectRec(image('E'), code, "");
            }
            String reason = sink.only().substring(350, 354);
            assertThat(reason).isEqualTo(expected);

            // The padding rule stated as an exclusion, so it cannot be right by accident.
            // WS-VALIDATION-FAIL-REASON is PIC 9(04): numeric, so it pads on the LEFT with the digit
            // zero. Under an alphanumeric receiver the same value would be left-justified and
            // space-padded - "102 " for 102 - which is a different four bytes that a reader splitting
            // the trailer on whitespace would still parse as a number, so nothing downstream would
            // complain about the wrong one.
            assertThat(reason).doesNotContain(" ").containsOnlyDigits();
            String digits = String.valueOf(code);
            if (digits.length() < REASON_WIDTH) {
                // Only meaningful for a code narrower than the receiver; 9999 fills all four bytes, so
                // the two renderings coincide and there is nothing left to distinguish.
                assertThat(reason)
                        .as("PIC 9(04) pads on the left with zeroes, not on the right with spaces")
                        .isNotEqualTo(digits + " ".repeat(REASON_WIDTH - digits.length()));
            }
        }

        @Test
        @DisplayName("the reason pads with zeroes and the description with spaces - never the reverse")
        void theTwoPaddingDirectionsAreOpposite() {
            // One record, both rules, stated against each other. Swapping the two pad characters is the
            // single most reversible mistake available here, and it would leave a record that is still
            // 430 bytes and still parses.
            Collector sink = new Collector();
            try (RejectsFile file = writer().openOutput(sink)) {
                file.writeRejectRec(image('X'), DalyRejectWriter.REASON_INVALID_CARD_NUMBER,
                        DalyRejectWriter.DESC_INVALID_CARD_NUMBER);
            }
            String record = sink.only();

            // PIC 9(04): zero-filled, and on the left.
            assertThat(record.substring(350, 354)).isEqualTo("0100")
                    .startsWith("0")
                    .doesNotEndWith(" ");
            // PIC X(76): space-filled, and on the right.
            assertThat(record.substring(354, 430))
                    .startsWith("INVALID CARD NUMBER FOUND")
                    .endsWith(" ")
                    .doesNotStartWith(" ")
                    .doesNotContain("0".repeat(DESC_WIDTH - 25));
            assertThat(record.substring(354 + 25, 430))
                    .as("everything after the 25-character text is spaces, not zeroes")
                    .isEqualTo(" ".repeat(DESC_WIDTH - 25));
        }

        @Test
        @DisplayName("truncates the reason on the LEFT when it exceeds four digits, as COBOL does")
        void truncatesAnOverwideReasonOnTheLeft() {
            Collector sink = new Collector();
            try (RejectsFile file = writer().openOutput(sink)) {
                file.writeRejectRec(image('E'), 10102, "");
            }
            // A numeric receiver is aligned on its implied decimal point, so the HIGH-order digit goes.
            assertThat(sink.only().substring(350, 354)).isEqualTo("0102");
        }

        @Test
        @DisplayName("space-pads the description on the RIGHT to exactly 76 characters")
        void spacePadsTheDescriptionOnTheRight() {
            Collector sink = new Collector();
            try (RejectsFile file = writer().openOutput(sink)) {
                file.writeRejectRec(image('F'), 102, "OVERLIMIT TRANSACTION");
            }
            String desc = sink.only().substring(354, 430);
            assertThat(desc).hasSize(DESC_WIDTH)
                    .startsWith("OVERLIMIT TRANSACTION")
                    .endsWith(" ".repeat(DESC_WIDTH - 21));
        }

        @Test
        @DisplayName("truncates the description on the RIGHT when it exceeds 76 characters")
        void truncatesAnOverlongDescriptionOnTheRight() {
            String tooLong = "L".repeat(DESC_WIDTH) + "DISCARDED";
            Collector sink = new Collector();
            try (RejectsFile file = writer().openOutput(sink)) {
                file.writeRejectRec(image('G'), 100, tooLong);
            }
            String desc = sink.only().substring(354, 430);
            assertThat(desc).hasSize(DESC_WIDTH)
                    .isEqualTo("L".repeat(DESC_WIDTH))
                    .doesNotContain("DISCARDED");
        }

        @Test
        @DisplayName("an empty description blanks the field, as MOVE SPACES at L209 does")
        void anEmptyDescriptionBlanksTheField() {
            Collector sink = new Collector();
            try (RejectsFile file = writer().openOutput(sink)) {
                file.writeRejectRec(image('H'), DalyRejectWriter.REASON_NONE,
                        DalyRejectWriter.DESC_SPACES);
            }
            String record = sink.only();
            assertThat(record.substring(350, 354)).isEqualTo("0000");
            assertThat(record.substring(354, 430)).isEqualTo(" ".repeat(DESC_WIDTH));
        }

        @Test
        @DisplayName("preserves write order - the rejects file's sequence is its content")
        void preservesWriteOrder() {
            char[] bodies = {'1', '2', '3', '4', '5', '6', '7'};
            Collector sink = new Collector();
            try (RejectsFile file = writer().openOutput(sink)) {
                for (char body : bodies) {
                    file.writeRejectRec(image(body), 100);
                }
            }
            assertThat(sink.records).hasSize(bodies.length);
            for (int index = 0; index < bodies.length; index++) {
                assertThat((char) sink.records.get(index)[0]).isEqualTo(bodies[index]);
            }
        }

        @Test
        @DisplayName("writes the same record twice when called twice without an intervening move")
        void writesTheSameRecordTwiceOnRepeatedCalls() {
            Collector sink = new Collector();
            try (RejectsFile file = writer().openOutput(sink)) {
                file.moveToRejectTranData(image('I'));
                file.moveToValidationTrailer(103);
                assertThat(file.writeRejectRec()).isEqualTo(FileStatus.Outcome.OK);
                assertThat(file.writeRejectRec()).isEqualTo(FileStatus.Outcome.OK);
            }
            assertThat(sink.records).hasSize(2);
            assertThat(sink.records.get(0)).isEqualTo(sink.records.get(1));
        }

        @Test
        @DisplayName("copies a DalyTranRecord verbatim, sign overpunch and all")
        void copiesADalyTranRecordVerbatim() {
            DalyTranRecord transaction = new DalyTranRecord(ASCII);
            transaction.moveDalytranId("DT00000000000001");
            transaction.moveDalytranCardNum("4111111111111111");
            transaction.moveDalytranAmt(new BigDecimal("-50.47"));
            byte[] expected = transaction.encode(ASCII);
            assertThat(expected).hasSize(TRAN_DATA_WIDTH);

            Collector sink = new Collector();
            try (RejectsFile file = writer().openOutput(sink)) {
                assertThat(file.writeRejectRec(transaction, 102)).isEqualTo(FileStatus.Outcome.OK);
            }
            assertThat(Arrays.copyOf(sink.records.get(0), TRAN_DATA_WIDTH)).isEqualTo(expected);

            // The stored image of -50.47, in full: eleven bytes, the low-order digit 7 replaced by the
            // negative overpunch carrying it. NEGATIVE_DIGITS is "}JKLMNOPQR", so digit 7 is 'P'.
            //
            // Asserting the whole image matters. An earlier form of this test said only that the image
            // was not "0000005047" - a ten-character literal against an eleven-character image, which
            // can never be equal, so it asserted nothing at all.
            assertThat(transaction.dalytranAmtImage())
                    .hasSize(AMT_WIDTH)
                    .isEqualTo("0000000504P")
                    .endsWith(String.valueOf(ZonedSign.overpunch(7, true)));
            assertThat(sink.records.get(0)[AMT_SIGN_OFFSET]).isEqualTo((byte) 'P');
        }

        @Test
        @DisplayName("transcodes a record built over another code page rather than mixing bytes")
        void transcodesARecordFromAnotherCodePage() {
            DalyTranRecord ebcdic = new DalyTranRecord(Charset.forName("IBM037"));
            ebcdic.moveDalytranId("DT00000000000002");
            Collector sink = new Collector();
            try (RejectsFile file = writer().openOutput(sink)) {
                file.writeRejectRec(ebcdic, 101);
            }
            // Written in the writer's own ASCII code page, not the record's EBCDIC one.
            assertThat(new String(sink.records.get(0), 0, 16, ASCII)).isEqualTo("DT00000000000002");
        }

        @Test
        @DisplayName("reads the record area back through both trailer views, untrimmed")
        void readsTheRecordAreaBack() {
            Collector sink = new Collector();
            try (RejectsFile file = writer().openOutput(sink)) {
                file.moveToRejectTranData(image('J'));
                file.moveToValidationTrailer(103);

                assertThat(file.rejectRecordBytes()).hasSize(LRECL);
                assertThat(file.rejectRecord()).hasSize(LRECL);
                assertThat(file.rejectTranDataBytes()).hasSize(TRAN_DATA_WIDTH).isEqualTo(image('J'));
                assertThat(file.validationFailReason()).isEqualTo(103);
                assertThat(file.validationFailReasonDesc()).hasSize(DESC_WIDTH)
                        .startsWith("TRANSACTION RECEIVED AFTER ACCT EXPIRATION");
                // The FD's 80-byte overlay must cover exactly the two working-storage items.
                assertThat(file.validationTrailer()).hasSize(80)
                        .isEqualTo("0103" + file.validationFailReasonDesc());
                assertThat(file.rejectRecord())
                        .isEqualTo(new String(image('J'), ASCII) + file.validationTrailer());
            }
        }

        @Test
        @DisplayName("a freshly opened area is established at each span's category default")
        void aFreshAreaIsEstablishedPerCategory() {
            try (RejectsFile file = writer().openOutput(new Collector())) {
                // Character spans blank, the PIC 9(04) reason zoned-zero - which is precisely the state
                // MOVE 0 / MOVE SPACES at app/cbl/CBTRN02C.cbl:L208-L209 establish before each
                // validation, so a fresh area already reads as reason 0 with a blank description.
                assertThat(file.rejectRecord())
                        .hasSize(LRECL)
                        .isEqualTo(" ".repeat(TRAN_DATA_WIDTH) + "0000" + " ".repeat(DESC_WIDTH));
                assertThat(file.validationFailReason()).isZero();
                assertThat(file.validationFailReasonDesc()).isEqualTo(" ".repeat(DESC_WIDTH));
                assertThat(file.validationTrailer()).isEqualTo("0000" + " ".repeat(DESC_WIDTH));
                assertThat(file.recordsWritten()).isZero();
            }
        }
    }

    // =================================================================================================
    // The raw-span copy. app/cbl/CBTRN02C.cbl:L447.
    //
    //     MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA
    //
    // Both operands are 350 bytes - CVTRA06Y's 01 DALYTRAN-RECORD and CBTRN02C:L177's
    // 05 REJECT-TRAN-DATA PIC X(350) - so this alphanumeric group move neither pads nor truncates: the
    // rejected transaction's bytes cross over unchanged. It is a copy. It is not a re-serialisation,
    // and the distinction is the single highest-risk thing about this writer, because on almost every
    // input the two are indistinguishable.
    // =================================================================================================

    @Nested
    @DisplayName("MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA - a copy, never a rebuild")
    class RawSpanCopyTests {

        @Test
        @DisplayName("the shipped fixture is the one measured here - 300 rows, six ending in '}'")
        void theFixtureCensusHolds() {
            // Everything below rests on this census, so it is asserted rather than trusted. If the
            // fixture is ever re-shipped, this fails first and says so, instead of the negative-zero
            // tests quietly degrading into assertions about ordinary positive amounts.
            List<String> rows = fixtureRows();
            assertThat(rows).hasSize(FIXTURE_ROWS);

            List<Integer> negativeZeroDigitRows = new ArrayList<>();
            for (int index = 0; index < rows.size(); index++) {
                if (rows.get(index).charAt(AMT_SIGN_OFFSET) == ZonedSign.NEGATIVE_ZERO) {
                    negativeZeroDigitRows.add(index + 1);
                }
            }
            assertThat(negativeZeroDigitRows)
                    .as("rows of app/data/ASCII/dailytran.txt whose DALYTRAN-AMT ends in '}'")
                    .containsExactly(Arrays.stream(NEGATIVE_ZERO_DIGIT_ROWS).boxed()
                            .toArray(Integer[]::new));

            // And the row this suite names is the row it thinks it is.
            DalyTranRecord row2 = DalyTranRecord.decode(fixtureRow(2), ASCII);
            assertThat(row2.dalytranId()).isEqualTo(ROW_2_ID);
            assertThat(row2.dalytranAmtImage()).isEqualTo(ROW_2_AMT_IMAGE);
            assertThat(row2.dalytranAmt()).isEqualByComparingTo(new BigDecimal("-919.00"));
        }

        @ParameterizedTest(name = "fixture line {0} crosses into the reject record byte for byte")
        @ValueSource(ints = {2, 55, 87, 150, 165, 210})
        @DisplayName("every '}' row of the real fixture is copied verbatim, overpunch included")
        void everyNegativeOverpunchRowIsCopiedVerbatim(int line) {
            byte[] sent = fixtureRow(line);
            assertThat(sent).hasSize(TRAN_DATA_WIDTH);
            assertThat(sent[AMT_SIGN_OFFSET])
                    .as("fixture line %d must carry the '}' this test exists to protect", line)
                    .isEqualTo((byte) ZonedSign.NEGATIVE_ZERO);

            Collector sink = new Collector();
            try (RejectsFile file = writer().openOutput(sink)) {
                assertThat(file.writeRejectRec(sent, DalyRejectWriter.REASON_OVERLIMIT_TRANSACTION))
                        .isEqualTo(FileStatus.Outcome.OK);
            }
            byte[] written = sink.records.get(0);

            assertThat(written).hasSize(LRECL);
            assertThat(Arrays.copyOf(written, TRAN_DATA_WIDTH))
                    .as("bytes 0..349 of the reject record are the transaction, unchanged")
                    .isEqualTo(sent);
            assertThat(written[AMT_SIGN_OFFSET])
                    .as("byte %d - DALYTRAN-AMT's sign overpunch", AMT_SIGN_OFFSET)
                    .isEqualTo((byte) ZonedSign.NEGATIVE_ZERO);
            assertThat(new String(written, AMT_OFFSET, AMT_WIDTH, ASCII))
                    .as("DALYTRAN-AMT, bytes %d..%d", AMT_OFFSET, AMT_SIGN_OFFSET)
                    .isEqualTo(new String(sent, AMT_OFFSET, AMT_WIDTH, ASCII))
                    .endsWith(String.valueOf(ZonedSign.NEGATIVE_ZERO));
        }

        @Test
        @DisplayName("a TRUE negative zero survives, where a BigDecimal round trip would lose it")
        void aTrueNegativeZeroSurvivesTheCopy() {
            // This is the assertion with teeth, and it is worth being explicit about why the six
            // fixture rows above are not enough on their own.
            //
            // Measured over all 300 fixture rows, decode-then-encode reproduces every one exactly,
            // because DalyTranRecord.encode transcodes the record AREA rather than rebuilding it from
            // decoded values. Line 2's '}' therefore survives either way: '}' means "negative, low-order
            // digit zero", and -919.00 is still unambiguously negative once decoded.
            //
            // A TRUE negative zero is where the two paths part. PIC S9(09)V99 distinguishes
            //     0000000000}   negative, all digits zero
            // from
            //     0000000000{   positive, all digits zero
            // and BigDecimal cannot: it has no -0. So a value routed out through a number and back
            // comes home as '{'. First prove that loss is real, then prove the writer does not suffer
            // it. Without the first half the second half would be an assertion about nothing.
            DalyTranRecord viaNumber = DalyTranRecord.decode(fixtureRow(2), ASCII);
            viaNumber.writeDalytranAmtImage(NEGATIVE_ZERO_AMT_IMAGE);
            assertThat(viaNumber.dalytranAmtImage()).isEqualTo(NEGATIVE_ZERO_AMT_IMAGE);
            assertThat(viaNumber.hasZeroDalytranAmt()).isTrue();

            BigDecimal roundTripped = viaNumber.dalytranAmt();
            assertThat(roundTripped).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(roundTripped.signum()).as("BigDecimal has no negative zero").isZero();

            DalyTranRecord rebuilt = viaNumber.copy();
            rebuilt.moveDalytranAmt(roundTripped);
            assertThat(rebuilt.dalytranAmtImage())
                    .as("the loss this test guards against, demonstrated")
                    .isEqualTo(POSITIVE_ZERO_AMT_IMAGE)
                    .isNotEqualTo(NEGATIVE_ZERO_AMT_IMAGE);
            assertThat(rebuilt.encode(ASCII)[AMT_SIGN_OFFSET]).isEqualTo((byte) ZonedSign.POSITIVE_ZERO);

            // Now the writer, given the un-rebuilt record. If this ever reported '{' the writer would
            // be decoding where it must be copying.
            byte[] sent = viaNumber.encode(ASCII);
            Collector viaBytes = new Collector();
            Collector viaRecord = new Collector();
            try (RejectsFile file = writer().openOutput(viaBytes)) {
                file.writeRejectRec(sent, DalyRejectWriter.REASON_INVALID_CARD_NUMBER);
            }
            try (RejectsFile file = writer().openOutput(viaRecord)) {
                file.writeRejectRec(viaNumber, DalyRejectWriter.REASON_INVALID_CARD_NUMBER);
            }

            for (Collector sink : List.of(viaBytes, viaRecord)) {
                byte[] written = sink.records.get(0);
                assertThat(written[AMT_SIGN_OFFSET])
                        .as("byte %d is '}' and not '{'", AMT_SIGN_OFFSET)
                        .isEqualTo((byte) ZonedSign.NEGATIVE_ZERO);
                assertThat(new String(written, AMT_OFFSET, AMT_WIDTH, ASCII))
                        .isEqualTo(NEGATIVE_ZERO_AMT_IMAGE);
                assertThat(Arrays.copyOf(written, TRAN_DATA_WIDTH)).isEqualTo(sent);
            }
            // Both routes into the record area agree, so neither is quietly the odd one out.
            assertThat(viaBytes.records.get(0)).isEqualTo(viaRecord.records.get(0));
        }

        @Test
        @DisplayName("FILLER X(20) at offset 330 arrives exactly as it left, whatever it holds")
        void theFillerSpanIsCopiedNotRegenerated() {
            // Gate G21. CVTRA06Y:L18's FILLER is 20 bytes of the record's 350 and belongs to the
            // sender, not to the writer. Drop it and the reject record is 410 bytes with every trailer
            // byte 20 positions early; regenerate it and a dataset whose FILLER is not blank silently
            // changes. The shipped fixture pads FILLER with spaces, so both are driven: the real row,
            // and a row whose FILLER is deliberately not blank.
            byte[] fromFixture = fixtureRow(2);
            assertThat(new String(fromFixture, FILLER_OFFSET, FILLER_WIDTH, ASCII))
                    .as("app/data/ASCII/dailytran.txt pads FILLER with spaces")
                    .isEqualTo(" ".repeat(FILLER_WIDTH));

            byte[] nonBlankFiller = fromFixture.clone();
            for (int offset = FILLER_OFFSET; offset < TRAN_DATA_WIDTH; offset++) {
                nonBlankFiller[offset] = (byte) '*';
            }

            Collector sink = new Collector();
            try (RejectsFile file = writer().openOutput(sink)) {
                file.writeRejectRec(fromFixture, DalyRejectWriter.REASON_ACCOUNT_RECORD_NOT_FOUND);
                file.writeRejectRec(nonBlankFiller,
                        DalyRejectWriter.REASON_ACCOUNT_RECORD_NOT_FOUND);
            }

            assertThat(new String(sink.records.get(0), FILLER_OFFSET, FILLER_WIDTH, ASCII))
                    .isEqualTo(" ".repeat(FILLER_WIDTH));
            assertThat(new String(sink.records.get(1), FILLER_OFFSET, FILLER_WIDTH, ASCII))
                    .as("FILLER is copied, not re-derived from the picture's pad character")
                    .isEqualTo("*".repeat(FILLER_WIDTH));
            // The trailer still begins at 350 in both, which is what dropping FILLER would break.
            assertThat(sink.records).allSatisfy(record -> {
                assertThat(record).hasSize(LRECL);
                assertThat(new String(record, 350, 4, ASCII)).isEqualTo("0101");
            });
        }

        @Test
        @DisplayName("no byte of the copied span is re-derived - all 350 offsets are the sender's")
        void everyOffsetOfTheCopiedSpanBelongsToTheSender() {
            // A per-offset statement, so a writer that got 349 of 350 bytes right cannot pass. Changing
            // one byte of a real row at a time would need 350 runs; giving every offset its own
            // distinguishable byte separates all 350 positions in a single one.
            byte[] sent = fixtureRow(2).clone();
            for (int offset = 0; offset < TRAN_DATA_WIDTH; offset++) {
                // Printable, deterministic, and different from its neighbours in a 95-wide cycle.
                sent[offset] = (byte) ('!' + (offset % 94));
            }

            Collector sink = new Collector();
            try (RejectsFile file = writer().openOutput(sink)) {
                file.writeRejectRec(sent, DalyRejectWriter.REASON_TRANSACTION_AFTER_EXPIRATION);
            }
            byte[] written = sink.records.get(0);
            for (int offset = 0; offset < TRAN_DATA_WIDTH; offset++) {
                assertThat(written[offset])
                        .as("byte %d of REJECT-TRAN-DATA", offset)
                        .isEqualTo(sent[offset]);
            }
            // And the copy stopped at 350: byte 350 is the trailer, not a 351st transaction byte.
            assertThat(new String(written, 350, 4, ASCII)).isEqualTo("0103");
        }

        @Test
        @DisplayName("moving a transaction leaves the trailer alone, and vice versa")
        void theTwoMovesAreIndependent() {
            // L447 and L448 are two separate MOVEs into two disjoint receivers. Either one running
            // alone must leave the other's bytes as they were, or a reject record could carry one
            // transaction's data beside another's reason.
            Collector sink = new Collector();
            try (RejectsFile file = writer().openOutput(sink)) {
                file.moveToValidationTrailer(DalyRejectWriter.REASON_OVERLIMIT_TRANSACTION);
                assertThat(file.rejectTranDataBytes())
                        .as("moving only the trailer leaves the transaction span blank")
                        .isEqualTo(" ".repeat(TRAN_DATA_WIDTH).getBytes(ASCII));

                file.moveToRejectTranData(fixtureRow(55));
                assertThat(file.validationFailReason())
                        .as("moving only the transaction leaves the trailer as it was")
                        .isEqualTo(DalyRejectWriter.REASON_OVERLIMIT_TRANSACTION);
                assertThat(file.validationFailReasonDesc())
                        .startsWith(DalyRejectWriter.DESC_OVERLIMIT_TRANSACTION);

                assertThat(file.writeRejectRec()).isEqualTo(FileStatus.Outcome.OK);
            }
            byte[] written = sink.records.get(0);
            assertThat(Arrays.copyOf(written, TRAN_DATA_WIDTH)).isEqualTo(fixtureRow(55));
            assertThat(new String(written, 350, 4, ASCII)).isEqualTo("0102");
        }
    }

    // =================================================================================================
    // Error and edge paths.
    // =================================================================================================

    @Nested
    @DisplayName("Error and edge paths")
    class ErrorPathTests {

        @ParameterizedTest(name = "a {0}-byte image is refused")
        @ValueSource(ints = {0, 1, 330, 349, 351, 430})
        @DisplayName("refuses an image that is not exactly 350 bytes rather than padding it")
        void refusesAWrongWidthImage(int width) {
            try (RejectsFile file = writer().openOutput(new Collector())) {
                assertThatIllegalArgumentException()
                        .isThrownBy(() -> file.moveToRejectTranData(new byte[width]))
                        .withMessageContaining("exactly 350 bytes")
                        .withMessageContaining(String.valueOf(width))
                        .withMessageContaining("FILLER X(20)");
            }
        }

        @Test
        @DisplayName("says 'short' or 'long' so the diagnostic points the right way")
        void namesTheDirectionOfTheWidthError() {
            try (RejectsFile file = writer().openOutput(new Collector())) {
                assertThatIllegalArgumentException()
                        .isThrownBy(() -> file.moveToRejectTranData(new byte[330]))
                        .withMessageContaining("short");
                assertThatIllegalArgumentException()
                        .isThrownBy(() -> file.moveToRejectTranData(new byte[360]))
                        .withMessageContaining("long");
            }
        }

        @ParameterizedTest(name = "reason {0} is refused - PIC 9 is unsigned")
        @ValueSource(ints = {-1, -102, Integer.MIN_VALUE})
        @DisplayName("refuses a negative reason, because PIC 9(04) has nowhere to record a sign")
        void refusesANegativeReason(int code) {
            try (RejectsFile file = writer().openOutput(new Collector())) {
                assertThatIllegalArgumentException()
                        .isThrownBy(() -> file.moveToValidationTrailer(code, ""));
                assertThatIllegalArgumentException()
                        .isThrownBy(() -> file.moveToValidationTrailer(code));
            }
        }

        @Test
        @DisplayName("refuses null senders - a COBOL MOVE has no null sender")
        void refusesNullSenders() {
            try (RejectsFile file = writer().openOutput(new Collector())) {
                assertThatNullPointerException()
                        .isThrownBy(() -> file.moveToRejectTranData((byte[]) null))
                        .withMessageContaining("null sender");
                assertThatNullPointerException()
                        .isThrownBy(() -> file.moveToRejectTranData((DalyTranRecord) null));
                assertThatNullPointerException()
                        .isThrownBy(() -> file.moveToValidationTrailer(102, null))
                        .withMessageContaining("DESC_SPACES");
                assertThatNullPointerException()
                        .isThrownBy(() -> file.writeRejectRec(null, 102, "x"));
                assertThatNullPointerException()
                        .isThrownBy(() -> file.writeRejectRec((DalyTranRecord) null, 102));
            }
        }

        @Test
        @DisplayName("refuses every operation on a closed handle, naming how far the run got")
        void refusesOperationsOnAClosedHandle() {
            RejectsFile file = writer().openOutput(new Collector());
            file.writeRejectRec(image('K'), 100);
            assertThat(file.closeOutput()).isEqualTo(FileStatus.Outcome.OK);

            assertThatIllegalStateException().isThrownBy(file::writeRejectRec)
                    .withMessageContaining("closed after 1 record(s)");
            assertThatIllegalStateException()
                    .isThrownBy(() -> file.moveToRejectTranData(image('K')));
            assertThatIllegalStateException()
                    .isThrownBy(() -> file.moveToValidationTrailer(100, "x"));
            assertThatIllegalStateException()
                    .isThrownBy(() -> file.writeRejectRec(image('K'), 100, "x"));
            assertThatIllegalStateException()
                    .isThrownBy(() -> file.writeRejectRec(image('K'), 100));
        }

        @Test
        @DisplayName("rejects a sink that answers null from write, open or close")
        void rejectsANullOutcome() {
            DalyRejectWriter subject = writer();
            RecordSink nullWrite = recordImage -> null;
            try (RejectsFile file = subject.openOutput(nullWrite)) {
                assertThatNullPointerException()
                        .isThrownBy(() -> file.writeRejectRec(image('L'), 100))
                        .withMessageContaining("null outcome from write(byte[])");
            }

            RecordSink nullOpen = new RecordSink() {
                @Override
                public FileStatus.Outcome write(byte[] recordImage) {
                    return FileStatus.Outcome.OK;
                }

                @Override
                public FileStatus.Outcome open() {
                    return null;
                }
            };
            assertThatNullPointerException().isThrownBy(() -> subject.openOutput(nullOpen))
                    .withMessageContaining("null outcome from open()");

            RecordSink nullClose = new RecordSink() {
                @Override
                public FileStatus.Outcome write(byte[] recordImage) {
                    return FileStatus.Outcome.OK;
                }

                @Override
                public FileStatus.Outcome close() {
                    return null;
                }
            };
            RejectsFile handle = subject.openOutput(nullClose);
            assertThatNullPointerException().isThrownBy(handle::closeOutput)
                    .withMessageContaining("null outcome from close()");
            // Marked closed before the sink was reached, so it cannot be closed twice.
            assertThat(handle.isOpen()).isFalse();
        }

        @Test
        @DisplayName("refuses a null sink and directs the caller to openOutput()")
        void refusesANullSink() {
            assertThatNullPointerException().isThrownBy(() -> writer().openOutput(null))
                    .withMessageContaining("openOutput()");
        }
    }

    // =================================================================================================
    // Lifecycle: 0300-DALYREJS-OPEN and 9300-DALYREJS-CLOSE.
    // =================================================================================================

    @Nested
    @DisplayName("Lifecycle - 0300-DALYREJS-OPEN and 9300-DALYREJS-CLOSE")
    class LifecycleTests {

        @Test
        @DisplayName("reports the open outcome instead of deciding on it")
        void reportsTheOpenOutcome() {
            Collector ok = new Collector();
            try (RejectsFile file = writer().openOutput(ok)) {
                assertThat(file.openOutcome()).isEqualTo(FileStatus.Outcome.OK);
                assertThat(file.isOpen()).isTrue();
            }
            assertThat(ok.opens).isOne();

            Collector failing = new Collector();
            failing.openAnswer = FileStatus.Outcome.OTHER;
            try (RejectsFile file = writer().openOutput(failing)) {
                // A failed open leaves the handle usable: the abend is the posting job's to raise.
                assertThat(file.openOutcome()).isEqualTo(FileStatus.Outcome.OTHER);
                assertThat(file.isOpen()).isTrue();
                assertThat(file.writeRejectRec(image('M'), 100)).isEqualTo(FileStatus.Outcome.OK);
            }
        }

        @Test
        @DisplayName("a later rejected write does not retroactively change the open outcome")
        void theOpenOutcomeIsStable() {
            Collector sink = new Collector();
            try (RejectsFile file = writer().openOutput(sink)) {
                sink.writeAnswer = FileStatus.Outcome.OTHER;
                assertThat(file.writeRejectRec(image('N'), 100)).isEqualTo(FileStatus.Outcome.OTHER);
                assertThat(file.openOutcome()).isEqualTo(FileStatus.Outcome.OK);
            }
        }

        @Test
        @DisplayName("reports both the '00' and the non-'00' write arms (gate G47)")
        void bothWriteArmsAreReachable() {
            Collector sink = new Collector();
            try (RejectsFile file = writer().openOutput(sink)) {
                assertThat(file.writeRejectRec(image('O'), 100)).isEqualTo(FileStatus.Outcome.OK);
                sink.writeAnswer = FileStatus.Outcome.OTHER;
                assertThat(file.writeRejectRec(image('O'), 101)).isEqualTo(FileStatus.Outcome.OTHER);
                // Counts every record handed over, rejected ones included.
                assertThat(file.recordsWritten()).isEqualTo(2);
            }
            assertThat(sink.records).hasSize(2);
        }

        @Test
        @DisplayName("closeOutput is idempotent and reaches the sink exactly once")
        void closeIsIdempotent() {
            Collector sink = new Collector();
            RejectsFile file = writer().openOutput(sink);
            assertThat(file.closeOutput()).isEqualTo(FileStatus.Outcome.OK);
            assertThat(file.closeOutput()).isEqualTo(FileStatus.Outcome.OK);
            file.close();
            assertThat(sink.closes).isOne();
            assertThat(file.isOpen()).isFalse();
        }

        @Test
        @DisplayName("reports a failed close rather than throwing, and logs it from close()")
        void reportsAFailedClose() {
            Collector viaCloseOutput = new Collector();
            viaCloseOutput.closeAnswer = FileStatus.Outcome.OTHER;
            RejectsFile handle = writer().openOutput(viaCloseOutput);
            assertThat(handle.closeOutput()).isEqualTo(FileStatus.Outcome.OTHER);

            // The AutoCloseable form cannot return a value, so it logs and does not throw.
            Collector viaTryWithResources = new Collector();
            viaTryWithResources.closeAnswer = FileStatus.Outcome.OTHER;
            assertThatCode(() -> {
                try (RejectsFile file = writer().openOutput(viaTryWithResources)) {
                    file.writeRejectRec(image('P'), 100);
                }
            }).doesNotThrowAnyException();
            assertThat(viaTryWithResources.closes).isOne();
        }

        @Test
        @DisplayName("open, write and close all report a FILE STATUS outcome and decide nothing")
        void allThreeOperationsReportRatherThanDecide() {
            // app/cbl/CBTRN02C.cbl gives DALYREJS three paragraphs with one shape between them:
            //
            //   0300-DALYREJS-OPEN   L292-L307   MOVE 8 -> OPEN OUTPUT  -> '00'? 0 : 12 -> APPL-AOK?
            //   2500-WRITE-REJECT-REC L446-L465  MOVE 8 -> WRITE        -> '00'? 0 : 12 -> APPL-AOK?
            //   9300-DALYREJS-CLOSE  L637-L653   MOVE 8 -> CLOSE        -> '00'? 0 : 12 -> APPL-AOK?
            //
            // Everything to the right of the arrow is the posting job's: APPL-RESULT, the DISPLAY of
            // 'ERROR WRITING TO REJECTS FILE', 9910-DISPLAY-IO-STATUS and 9999-ABEND-PROGRAM. What the
            // writer owes the caller is only the left-hand side - the FILE STATUS the operation got -
            // and that is what these three methods return (gates G47 and G51).
            Collector sink = new Collector();
            RejectsFile file = writer().openOutput(sink);
            assertThat(file.openOutcome()).isNotNull().isEqualTo(FileStatus.Outcome.OK);
            assertThat(file.writeRejectRec(image('a'), 100)).isNotNull()
                    .isEqualTo(FileStatus.Outcome.OK);
            assertThat(file.closeOutput()).isNotNull().isEqualTo(FileStatus.Outcome.OK);
        }

        @ParameterizedTest(name = "a sink reporting {0} is reported onward as {0}, unchanged")
        @EnumSource(FileStatus.Outcome.class)
        @DisplayName("transports the outcome the destination reported and never reinterprets it")
        void theOutcomeIsTransportedNotJudged(FileStatus.Outcome reported) {
            // The writer is not entitled to an opinion about what a status means. Whatever the
            // destination said, the caller must see, because the caller is the one holding the
            // EVALUATE - and a writer that helpfully collapsed statuses into "worked" and "did not"
            // would erase the two-character value that 9910-DISPLAY-IO-STATUS exists to print.
            // Driven over every declared Outcome, including the three that would be surprising here,
            // because "surprising" is not the writer's call to make.
            Collector sink = new Collector();
            sink.openAnswer = reported;
            sink.writeAnswer = reported;
            sink.closeAnswer = reported;

            RejectsFile file = writer().openOutput(sink);
            assertThat(file.openOutcome()).isEqualTo(reported);
            assertThat(file.writeRejectRec(image('t'), 102)).isEqualTo(reported);
            assertThat(file.closeOutput()).isEqualTo(reported);
            // And the record still went out: reporting a status is not the same as refusing to write.
            assertThat(sink.records).singleElement()
                    .satisfies(record -> assertThat(record).hasSize(LRECL));
        }

        @Test
        @DisplayName("the ladder hinges on batchStatus() being '00' - the same test the COBOL makes")
        void theLadderHingesOnTheStatusBeingZeroZero() {
            // IF DALYREJS-STATUS = '00' is a two-character comparison, and Outcome carries exactly that
            // two-character status. Stating the hinge here means the caller's arm selection is derivable
            // from what the writer returns, rather than from a convention agreed by nobody.
            Collector ok = new Collector();
            Collector failing = new Collector();
            failing.writeAnswer = FileStatus.Outcome.OTHER;

            FileStatus.Outcome accepted;
            FileStatus.Outcome refused;
            try (RejectsFile file = writer().openOutput(ok)) {
                accepted = file.writeRejectRec(image('b'), 100);
            }
            try (RejectsFile file = writer().openOutput(failing)) {
                refused = file.writeRejectRec(image('c'), 100);
            }

            // The '00' arm: MOVE 0 TO APPL-RESULT, and APPL-AOK is then true.
            assertThat(accepted.batchStatus()).contains(FileStatus.OK);
            assertThat(accepted.batchStatus()).contains("00");
            assertThat(FileStatus.isOk(accepted.batchStatus().orElseThrow())).isTrue();
            assertThat(FileStatus.outcomeOfStatus("00")).isEqualTo(accepted);

            // The other arm: MOVE 12 TO APPL-RESULT, and APPL-AOK is then false. OTHER deliberately
            // carries no two-character status, because there is no COBOL FILE STATUS meaning
            // "some failure" - which is exactly why the caller must treat "not '00'" as the condition.
            assertThat(refused).isEqualTo(FileStatus.Outcome.OTHER);
            assertThat(refused.batchStatus()).isEmpty();
            assertThat(refused).isNotEqualTo(accepted);
        }

        @Test
        @DisplayName("a refused write raises nothing - the abend is the posting job's to raise")
        void aRefusedWriteRaisesNothing() {
            // The whole point of returning an outcome rather than throwing: 2500-WRITE-REJECT-REC does
            // not abend, it sets APPL-RESULT and lets 9999-ABEND-PROGRAM be performed by the caller. So
            // a refused write must leave the handle usable, and the run must be able to carry on to the
            // next rejected transaction - which is what a posting run that logged and continued would
            // do. Nothing below may throw.
            Collector sink = new Collector();
            assertThatCode(() -> {
                try (RejectsFile file = writer().openOutput(sink)) {
                    sink.writeAnswer = FileStatus.Outcome.OTHER;
                    assertThat(file.writeRejectRec(image('d'), 100))
                            .isEqualTo(FileStatus.Outcome.OTHER);
                    // Still open, still usable, still counting.
                    assertThat(file.isOpen()).isTrue();
                    sink.writeAnswer = FileStatus.Outcome.OK;
                    assertThat(file.writeRejectRec(image('e'), 101)).isEqualTo(FileStatus.Outcome.OK);
                    assertThat(file.recordsWritten()).isEqualTo(2);
                }
            }).doesNotThrowAnyException();
            assertThat(sink.records).hasSize(2);
            assertThat(sink.closes).isOne();
        }

        @Test
        @DisplayName("closing after zero writes and after N writes both behave, and say which it was")
        void closeAfterZeroAndAfterManyWritesBothBehave() {
            // The zero case is the common one for this dataset: a posting run that rejected nothing
            // opens DALYREJS, writes nothing and closes it, and 9300-DALYREJS-CLOSE still runs
            // unconditionally at L224. It must not be a special case, and it must not be an error.
            Collector empty = new Collector();
            RejectsFile afterNone = writer().openOutput(empty);
            assertThat(afterNone.recordsWritten()).isZero();
            assertThat(afterNone.closeOutput()).isEqualTo(FileStatus.Outcome.OK);
            assertThat(afterNone.isOpen()).isFalse();
            assertThat(afterNone.recordsWritten())
                    .as("the count survives the close, so the caller can still report it")
                    .isZero();
            assertThat(empty.opens).isOne();
            assertThat(empty.closes).isOne();
            assertThat(empty.records).isEmpty();

            // And the N case, with the count and the collected records agreeing.
            Collector several = new Collector();
            RejectsFile afterSome = writer().openOutput(several);
            for (int reject = 0; reject < 4; reject++) {
                assertThat(afterSome.writeRejectRec(image((char) ('1' + reject)), 100))
                        .isEqualTo(FileStatus.Outcome.OK);
            }
            assertThat(afterSome.recordsWritten()).isEqualTo(4);
            assertThat(afterSome.closeOutput()).isEqualTo(FileStatus.Outcome.OK);
            assertThat(afterSome.isOpen()).isFalse();
            assertThat(afterSome.recordsWritten()).isEqualTo(4);
            assertThat(several.records).hasSize(4).allSatisfy(record ->
                    assertThat(record).hasSize(LRECL));
            assertThat(several.closes).isOne();

            // A failing close is reported the same way whether anything was written or not, so the
            // caller's 9300 ladder has one shape rather than two.
            Collector emptyThenFails = new Collector();
            emptyThenFails.closeAnswer = FileStatus.Outcome.OTHER;
            assertThat(writer().openOutput(emptyThenFails).closeOutput())
                    .isEqualTo(FileStatus.Outcome.OTHER);
        }

        @Test
        @DisplayName("isOpen answers both ways, as an 88-level condition name does")
        void theOpenPredicateAnswersBothWays() {
            // Gate G50: a condition name is only tested when both of its states have been seen.
            RejectsFile file = writer().openOutput(new Collector());
            assertThat(file.isOpen()).isTrue();
            file.closeOutput();
            assertThat(file.isOpen()).isFalse();
        }

        @Test
        @DisplayName("each open returns an independent handle with its own record area")
        void handlesAreIndependent() {
            DalyRejectWriter subject = writer();
            Collector first = new Collector();
            Collector second = new Collector();
            try (RejectsFile one = subject.openOutput(first);
                 RejectsFile two = subject.openOutput(second)) {
                one.moveToRejectTranData(image('Q'));
                one.moveToValidationTrailer(100);
                two.moveToRejectTranData(image('R'));
                two.moveToValidationTrailer(103);

                assertThat(one.rejectRecord()).isNotEqualTo(two.rejectRecord());
                assertThat(one.validationFailReason()).isEqualTo(100);
                assertThat(two.validationFailReason()).isEqualTo(103);
            }
        }
    }

    // =================================================================================================
    // Configuration: the geometry cross-checks and the composed statement.
    // =================================================================================================

    @Nested
    @DisplayName("Configuration - the geometry cross-checks and the composed statement")
    class ConfigurationTests {

        @ParameterizedTest(name = "record-length {0} is refused")
        @ValueSource(ints = {1, 350, 410, 429, 431, 860})
        @DisplayName("refuses a record length that is not 430, naming both numbers")
        void refusesAWrongRecordLength(int recordLength) {
            assertThatIllegalStateException().isThrownBy(() -> new DalyRejectWriter(new JdbcTemplate(),
                            ASCII, bindings(recordLength, "F"), RecordImageForm.CHARACTER))
                    .withMessageContaining("carddemo.datasets.DALYREJS")
                    .withMessageContaining(String.valueOf(recordLength))
                    .withMessageContaining("430");
        }

        @Test
        @DisplayName("refuses a record format that is not F, including the siblings' FB")
        void refusesAWrongRecordFormat() {
            assertThatIllegalStateException().isThrownBy(() -> new DalyRejectWriter(new JdbcTemplate(),
                            ASCII, bindings(LRECL, "FB"), RecordImageForm.CHARACTER))
                    .withMessageContaining("'FB'")
                    .withMessageContaining("RECFM=F");
            assertThatIllegalStateException().isThrownBy(() -> new DalyRejectWriter(new JdbcTemplate(),
                            ASCII, bindings(LRECL, null), RecordImageForm.CHARACTER))
                    .withMessageContaining("absent");
        }

        @Test
        @DisplayName("accepts the format case-insensitively, as configuration may supply either case")
        void acceptsTheFormatCaseInsensitively() {
            assertThatCode(() -> new DalyRejectWriter(new JdbcTemplate(), ASCII,
                    bindings(LRECL, "f"), RecordImageForm.CHARACTER)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("refuses null collaborators - constructor injection only, never half-configured")
        void refusesNullCollaborators() {
            DatasetBindings valid = bindings(LRECL, "F");
            assertThatNullPointerException().isThrownBy(() -> new DalyRejectWriter(null, ASCII, valid,
                    RecordImageForm.CHARACTER));
            assertThatNullPointerException().isThrownBy(() -> new DalyRejectWriter(new JdbcTemplate(),
                    null, valid, RecordImageForm.CHARACTER));
            assertThatNullPointerException().isThrownBy(() -> new DalyRejectWriter(new JdbcTemplate(),
                    ASCII, null, RecordImageForm.CHARACTER));
            assertThatNullPointerException().isThrownBy(() -> new DalyRejectWriter(new JdbcTemplate(),
                    ASCII, valid, null));
        }

        @Test
        @DisplayName("refuses a catalogue with no DALYREJS entry at all")
        void refusesAnAbsentBinding() {
            assertThatIllegalStateException().isThrownBy(() -> new DalyRejectWriter(new JdbcTemplate(),
                    ASCII, new DatasetBindings(), RecordImageForm.CHARACTER));
        }

        @Test
        @DisplayName("composes a single-parameter insert naming only the configured dataset")
        void composesTheInsertStatement() {
            String statement = writer().insertStatement();
            assertThat(statement).contains(TEST_DSNAME)
                    .contains("?")
                    .doesNotContain("AWS.M2.CARDDEMO");
            assertThat(statement.chars().filter(character -> character == '?').count()).isOne();
        }

        @ParameterizedTest(name = "dsname \"{0}\" defers its refusal to insertStatement()")
        // "TEST." rather than the production high-level qualifier: gate G46 wants no AWS.M2.CARDDEMO
        // literal anywhere in Java, and a deliberately malformed name is no reason to smuggle one in.
        @ValueSource(strings = {"", "/var/tmp/dalyrejs.dat", "not a dataset", "TOOLONGQUALIFIER.X",
                "TEST.M2.DALYREJS(BAD)"})
        @DisplayName("still constructs when the name is not a dataset, so the context can start")
        void defersANonDatasetName(String dsname) {
            DalyRejectWriter subject = new DalyRejectWriter(new JdbcTemplate(), ASCII,
                    catalogue(dsname, LRECL, "F"), RecordImageForm.CHARACTER);
            // Gate G3: the bean exists, so a fixture-backed profile still starts.
            assertThat(subject.recordLength()).isEqualTo(LRECL);
            assertThatIllegalStateException().isThrownBy(subject::insertStatement)
                    .withMessageContaining("cannot be addressed as a dataset")
                    .withMessageContaining("openOutput(RecordSink)");
            assertThatIllegalStateException().isThrownBy(subject::openOutput);
            // A caller-supplied sink is unaffected by the unusable name.
            Collector sink = new Collector();
            try (RejectsFile file = subject.openOutput(sink)) {
                assertThat(file.writeRejectRec(image('S'), 100)).isEqualTo(FileStatus.Outcome.OK);
            }
            assertThat(sink.records).hasSize(1);
        }

        @Test
        @DisplayName("a null dsname is reported as unconfigured rather than as malformed")
        void reportsAnUnconfiguredNameDistinctly() {
            DalyRejectWriter subject = new DalyRejectWriter(new JdbcTemplate(), ASCII,
                    catalogue(null, LRECL, "F"), RecordImageForm.CHARACTER);
            assertThatIllegalStateException().isThrownBy(subject::insertStatement)
                    .havingCause()
                    .withMessageContaining("is not configured");
        }
    }

    // =================================================================================================
    // The default JDBC sink - risk R-E: production connectivity cannot be exercised here.
    // =================================================================================================

    @Nested
    @DisplayName("The default JDBC sink - what reaches the driver")
    class JdbcSinkTests {

        @Test
        @DisplayName("the open establishes the generation and empties it - one describe, one delete "
                + "(0300-DALYREJS-OPEN, POSTTRAN.jcl:L34-L38)")
        void theOpenEstablishesAndClearsTheGeneration() throws SQLException {
            // DISP=(NEW,CATLG,DELETE) over DALYREJS(+1) means this run writes into an empty generation.
            // The describe resolves the destination and transfers nothing; the delete is what NEW means.
            // Neither is a data-definition statement (gate G44).
            DataSource dataSource = Mockito.mock(DataSource.class);
            Connection connection = Mockito.mock(Connection.class);
            PreparedStatement insert = Mockito.mock(PreparedStatement.class);
            Statement plain = Mockito.mock(Statement.class);
            Mockito.when(dataSource.getConnection()).thenReturn(connection);
            Mockito.when(connection.prepareStatement(Mockito.anyString())).thenReturn(insert);
            Mockito.when(connection.createStatement()).thenReturn(plain);

            DalyRejectWriter subject = new DalyRejectWriter(new JdbcTemplate(dataSource), ASCII,
                    bindings(LRECL, "F"), RecordImageForm.CHARACTER);
            RejectsFile file = subject.openOutput();

            String describe = "SELECT * FROM \"" + TEST_DSNAME + "\" WHERE 1 = 0";
            assertThat(file.openOutcome()).isEqualTo(FileStatus.Outcome.OK);
            Mockito.verify(plain).execute(describe);
            Mockito.verify(plain).executeUpdate("DELETE FROM \"" + TEST_DSNAME + "\"");
            // Nothing was rejected yet, so no reject record was bound: the open makes the generation
            // ready, it does not write into it.
            Mockito.verifyNoInteractions(insert);

            // 9300-DALYREJS-CLOSE probes again and clears nothing - exactly one DELETE per run.
            assertThat(file.closeOutput()).isEqualTo(FileStatus.Outcome.OK);
            Mockito.verify(plain, Mockito.times(2)).execute(describe);
            Mockito.verify(plain, Mockito.times(1))
                    .executeUpdate("DELETE FROM \"" + TEST_DSNAME + "\"");
        }

        @Test
        @DisplayName("a destination that cannot be opened is reported by the open, not by the first "
                + "reject (CBTRN02C.cbl 0300-DALYREJS-OPEN)")
        void aRefusedOpenIsReportedByTheOpen() throws SQLException {
            DataSource dataSource = Mockito.mock(DataSource.class);
            Mockito.when(dataSource.getConnection())
                    .thenThrow(new SQLException("dataset unavailable"));

            DalyRejectWriter subject = new DalyRejectWriter(new JdbcTemplate(dataSource), ASCII,
                    bindings(LRECL, "F"), RecordImageForm.CHARACTER);

            try (RejectsFile file = subject.openOutput()) {
                assertThat(file.openOutcome()).isEqualTo(FileStatus.Outcome.OTHER);
                // The same unreachable destination is reported by the close too. A close reporting OK
                // over a dataset that was never there would tell the posting job the run completed.
                assertThat(file.closeOutput()).isEqualTo(FileStatus.Outcome.OTHER);
            }
        }

        @Test
        @DisplayName("a destination that goes away mid-run is reported by the close "
                + "(9300-DALYREJS-CLOSE)")
        void aRefusedCloseIsReported() throws SQLException {
            DataSource dataSource = Mockito.mock(DataSource.class);
            Connection connection = Mockito.mock(Connection.class);
            Statement plain = Mockito.mock(Statement.class);
            Mockito.when(dataSource.getConnection()).thenReturn(connection);
            Mockito.when(connection.createStatement()).thenReturn(plain);
            Mockito.when(plain.execute(Mockito.anyString()))
                    .thenReturn(true)
                    .thenThrow(new SQLException("dataset dropped"));

            DalyRejectWriter subject = new DalyRejectWriter(new JdbcTemplate(dataSource), ASCII,
                    bindings(LRECL, "F"), RecordImageForm.CHARACTER);
            RejectsFile file = subject.openOutput();

            assertThat(file.openOutcome()).isEqualTo(FileStatus.Outcome.OK);
            assertThat(file.closeOutput()).isEqualTo(FileStatus.Outcome.OTHER);
        }

        @Test
        @DisplayName("binds the whole 430-byte image as one positional parameter")
        void bindsTheWholeImage() throws SQLException {
            DataSource dataSource = Mockito.mock(DataSource.class);
            Connection connection = Mockito.mock(Connection.class);
            PreparedStatement statement = Mockito.mock(PreparedStatement.class);
            Mockito.when(dataSource.getConnection()).thenReturn(connection);
            Mockito.when(connection.prepareStatement(Mockito.anyString())).thenReturn(statement);
            Mockito.when(connection.createStatement()).thenReturn(Mockito.mock(Statement.class));
            Mockito.when(statement.executeUpdate()).thenReturn(1);

            DalyRejectWriter subject = new DalyRejectWriter(new JdbcTemplate(dataSource), ASCII,
                    bindings(LRECL, "F"), RecordImageForm.CHARACTER);
            try (RejectsFile file = subject.openOutput()) {
                assertThat(file.writeRejectRec(image('T'), 102)).isEqualTo(FileStatus.Outcome.OK);
            }

            // Once, and it is the write's own: the open resolves the destination with a describe and
            // clears the generation, neither of which prepares an insert. The statement TEXT is what this
            // test is asserting - the whole 430-byte image is bound to it as one value.
            Mockito.verify(connection, Mockito.times(1)).prepareStatement(subject.insertStatement());
            Mockito.verify(statement).setString(Mockito.eq(1), Mockito.argThat(
                    value -> value != null && value.length() == LRECL
                            && value.startsWith("T") && value.substring(350, 354).equals("0102")));
        }

        @Test
        @DisplayName("the open probes the destination, and a refusal is reported there, not by a write")
        void theOpenProbesTheDestination() throws SQLException {
            // OPEN OUTPUT DALYREJS-FILE is status-checked at app/cbl/CBTRN02C.cbl:293-302. Leaving the
            // open unable to fail mattered more here than anywhere else in the module: a posting run whose
            // input is entirely clean writes no reject at all, so an unaddressable destination would have
            // gone unnoticed for the whole run.
            DataSource dataSource = Mockito.mock(DataSource.class);
            Connection connection = Mockito.mock(Connection.class);
            Mockito.when(dataSource.getConnection()).thenReturn(connection);
            Mockito.when(connection.createStatement())
                    .thenThrow(new SQLException("no such table", "42S02", 42102));

            DalyRejectWriter subject = new DalyRejectWriter(new JdbcTemplate(dataSource), ASCII,
                    bindings(LRECL, "F"), RecordImageForm.CHARACTER);

            try (RejectsFile file = subject.openOutput()) {
                assertThat(file.openOutcome()).isEqualTo(FileStatus.Outcome.OTHER);
            }

            // Nothing was prepared for insert: an open that could not resolve its destination writes
            // no row, exactly as the COBOL's abend arm leaves the generation untouched.
            Mockito.verify(connection, Mockito.never()).prepareStatement(Mockito.anyString());
        }

        @Test
        @DisplayName("a reachable destination reports OK at open, with no row written")
        void aReachableDestinationReportsOkAtOpen() throws SQLException {
            DataSource dataSource = Mockito.mock(DataSource.class);
            Connection connection = Mockito.mock(Connection.class);
            PreparedStatement statement = Mockito.mock(PreparedStatement.class);
            Statement plain = Mockito.mock(Statement.class);
            Mockito.when(dataSource.getConnection()).thenReturn(connection);
            Mockito.when(connection.prepareStatement(Mockito.anyString())).thenReturn(statement);
            Mockito.when(connection.createStatement()).thenReturn(plain);

            DalyRejectWriter subject = new DalyRejectWriter(new JdbcTemplate(dataSource), ASCII,
                    bindings(LRECL, "F"), RecordImageForm.CHARACTER);

            try (RejectsFile file = subject.openOutput()) {
                assertThat(file.openOutcome()).isEqualTo(FileStatus.Outcome.OK);
            }

            // Resolved and emptied, never written to: the open issues its describe and its clear over a
            // plain statement and prepares no insert, so a rejects generation whose open the caller
            // abends on is left exactly as empty as the COBOL leaves it.
            Mockito.verify(plain, Mockito.atLeastOnce()).close();
            Mockito.verify(connection, Mockito.never()).prepareStatement(Mockito.anyString());
            Mockito.verify(statement, Mockito.never()).executeUpdate();
        }

        @Test
        @DisplayName("maps a rejected write onto the arm that sets APPL-RESULT to 12")
        void mapsARejectedWriteToOther() throws SQLException {
            DataSource dataSource = Mockito.mock(DataSource.class);
            Connection connection = Mockito.mock(Connection.class);
            PreparedStatement statement = Mockito.mock(PreparedStatement.class);
            Mockito.when(dataSource.getConnection()).thenReturn(connection);
            Mockito.when(connection.prepareStatement(Mockito.anyString())).thenReturn(statement);
            Mockito.when(connection.createStatement()).thenReturn(Mockito.mock(Statement.class));
            Mockito.when(statement.executeUpdate())
                    .thenThrow(new SQLException("refused", "23000", 1));

            DalyRejectWriter subject = new DalyRejectWriter(new JdbcTemplate(dataSource), ASCII,
                    bindings(LRECL, "F"), RecordImageForm.CHARACTER);
            try (RejectsFile file = subject.openOutput()) {
                assertThat(file.writeRejectRec(image('U'), 100)).isEqualTo(FileStatus.Outcome.OTHER);
                assertThat(file.recordsWritten()).isOne();
            }
        }

        @Test
        @DisplayName("binds bytes rather than characters when the deployment says so")
        void bindsBytesWhenConfigured() throws SQLException {
            DataSource dataSource = Mockito.mock(DataSource.class);
            Connection connection = Mockito.mock(Connection.class);
            PreparedStatement statement = Mockito.mock(PreparedStatement.class);
            Mockito.when(dataSource.getConnection()).thenReturn(connection);
            Mockito.when(connection.prepareStatement(Mockito.anyString())).thenReturn(statement);
            Mockito.when(connection.createStatement()).thenReturn(Mockito.mock(Statement.class));
            Mockito.when(statement.executeUpdate()).thenReturn(1);

            DalyRejectWriter subject = new DalyRejectWriter(new JdbcTemplate(dataSource), ASCII,
                    bindings(LRECL, "F"), RecordImageForm.BINARY);
            try (RejectsFile file = subject.openOutput()) {
                file.writeRejectRec(image('V'), 100);
            }
            Mockito.verify(statement).setBytes(Mockito.eq(1),
                    Mockito.argThat(value -> value != null && value.length == LRECL));
        }
    }

    // =================================================================================================
    // Gate G3 - the bean really does wire, against the configuration the module actually ships.
    // =================================================================================================

    @Nested
    @DisplayName("The context-load gate - the writer bean really does wire (G3)")
    @SpringBootTest(classes = CardDemoApplication.class)
    @ActiveProfiles("test")
    class ContextWiringTests {

        /** The started context, injected so the bean can be looked up by type. */
        @Autowired
        private ApplicationContext context;

        @Test
        @DisplayName("wires as a singleton whose geometry the shipped application.yml satisfies")
        void theWriterBeanWires() {
            DalyRejectWriter subject = context.getBean(DalyRejectWriter.class);

            // The constructor refuses anything but 430/F, so its mere existence proves that
            // carddemo.datasets.DALYREJS declares the JCL's geometry in the configuration that ships.
            assertThat(subject.recordLength()).isEqualTo(LRECL);
            assertThat(subject.datasetBinding().recordLength()).isEqualTo(LRECL);
            assertThat(subject.datasetBinding().recordFormat()).isEqualToIgnoringCase("F");
            assertThat(subject.datasetBinding().organization()).isEqualTo("sequential");
            // app/jcl/DALYREJS.jcl:L24-L28 defines a generation data group.
            assertThat(subject.datasetBinding().gdg()).isTrue();
            assertThat(subject.datasetCharset()).isNotNull();
            assertThat(context.getBeanNamesForType(DalyRejectWriter.class)).hasSize(1);
        }

        @Test
        @DisplayName("addresses the dataset configuration names, never a literal in Java (G46)")
        void addressesTheConfiguredDataset() {
            DalyRejectWriter subject = context.getBean(DalyRejectWriter.class);

            assertThat(subject.datasetBinding().dsname()).isNotBlank();
            assertThat(subject.insertStatement())
                    .contains(subject.datasetBinding().dsname())
                    .doesNotContain("AWS.M2.CARDDEMO");
        }

        @Test
        @DisplayName("writes through a caller-supplied sink with no dataset and no database")
        void writesThroughASuppliedSink() {
            DalyRejectWriter subject = context.getBean(DalyRejectWriter.class);
            Collector sink = new Collector();

            try (RejectsFile file = subject.openOutput(sink)) {
                assertThat(file.writeRejectRec(image('W'), 102)).isEqualTo(FileStatus.Outcome.OK);
            }

            assertThat(sink.records).singleElement()
                    .satisfies(record -> assertThat(record).hasSize(LRECL));
            assertThat(sink.only().substring(350, 354)).isEqualTo("0102");
        }
    }

    // =================================================================================================
    // Practice B9 / gate G53 - nothing static is mutable.
    // =================================================================================================

    @Nested
    @DisplayName("Practice B9 - no static mutable state")
    class NoStaticMutableStateTests {

        @Test
        @DisplayName("every static field is final")
        void everyStaticFieldIsFinal() {
            for (Field field : DalyRejectWriter.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("static field %s must be final", field.getName())
                            .isTrue();
                }
            }
        }

        @Test
        @DisplayName("every instance field is final, so the bean is an immutable singleton")
        void everyInstanceFieldIsFinal() {
            for (Field field : DalyRejectWriter.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("instance field %s must be final", field.getName())
                            .isTrue();
                }
            }
        }
    }

    // =================================================================================================
    // The abnormal disposition - the THIRD positional of DISP=(NEW,CATLG,DELETE).
    //
    // app/jcl/POSTTRAN.jcl:L34-L38 declares three dispositions for DALYREJS and the writer used to
    // reproduce two. NEW is the open's clear; CATLG is what the close leaves behind; DELETE is what an
    // abended run must leave - which is nothing at all. Not a rollback: every reject is durable as it is
    // written, so this removes them afterwards, in the order z/OS uses.
    // =================================================================================================

    @Nested
    @DisplayName("The abnormal disposition deletes the generation an abended run wrote")
    class TheAbnormalDisposition {

        /**
         * A real in-memory relation, so the count-then-delete is measured rather than mocked.
         *
         * <p>{@code DB_CLOSE_DELAY=-1} because {@link SimpleDriverDataSource} opens a connection per
         * call: without it H2 would discard the database the moment the connection that created the
         * relation was returned.
         *
         * @return a template over a private H2 database already holding the DALYREJS relation
         */
        private JdbcTemplate liveTemplate() {
            JdbcTemplate template = new JdbcTemplate(new SimpleDriverDataSource(new org.h2.Driver(),
                    "jdbc:h2:mem:dalyrejs-disp-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
            // Gate G44 forbids DDL in the module, and this is not that. Nothing here is shipped, mapped
            // or migrated: it is a per-test, per-UUID, in-memory relation that exists so the
            // count-then-delete inside discardGeneration() can be MEASURED instead of mocked, and it is
            // gone when the last connection to this URL closes. The module itself creates no schema -
            // its own test profile is H2-backed by configuration (application-test.yml), and its
            // production DataSource is configuration-bound with initialize-schema never.
            template.execute("CREATE TABLE \"" + TEST_DSNAME + "\" (RECORD_IMAGE CHAR("
                    + LRECL + "))");
            return template;
        }

        /** @return how many records the relation holds */
        private int held(JdbcTemplate template) {
            Integer count = template.queryForObject(
                    "SELECT COUNT(*) FROM \"" + TEST_DSNAME + "\"", Integer.class);
            return count == null ? 0 : count;
        }

        @Test
        @DisplayName("every reject this run wrote is deleted, and the close before it deleted nothing")
        void theGenerationIsDeleted() {
            JdbcTemplate template = liveTemplate();
            DalyRejectWriter subject = new DalyRejectWriter(template, ASCII, bindings(LRECL, "F"),
                    RecordImageForm.CHARACTER);
            RejectsFile file = subject.openOutput();

            assertThat(file.writeRejectRec(image('A'), 102)).isEqualTo(FileStatus.Outcome.OK);
            assertThat(file.writeRejectRec(image('B'), 100)).isEqualTo(FileStatus.Outcome.OK);
            // CATLG: the close leaves the rejects where they are. That is the whole distinction.
            assertThat(file.closeOutput()).isEqualTo(FileStatus.Outcome.OK);
            assertThat(held(template)).isEqualTo(2);

            assertThat(file.discardGeneration()).isEqualTo(FileStatus.Outcome.OK);
            assertThat(held(template)).isZero();
        }

        @Test
        @DisplayName("a second discard neither issues anything nor contradicts the first")
        void theDiscardIsIdempotent() {
            JdbcTemplate template = liveTemplate();
            DalyRejectWriter subject = new DalyRejectWriter(template, ASCII, bindings(LRECL, "F"),
                    RecordImageForm.CHARACTER);
            RejectsFile file = subject.openOutput();
            assertThat(file.writeRejectRec(image('A'), 102)).isEqualTo(FileStatus.Outcome.OK);

            assertThat(file.discardGeneration()).isEqualTo(FileStatus.Outcome.OK);
            assertThat(file.discardGeneration()).isEqualTo(FileStatus.Outcome.OK);
            assertThat(held(template)).isZero();
        }

        @Test
        @DisplayName("a run whose input was entirely clean deletes nothing and reports OK")
        void anEmptyRunDeletesNothing() {
            // The common case for this dataset: a posting run that rejected nothing wrote nothing, so
            // there is no generation to delete and nothing to say about it.
            JdbcTemplate template = liveTemplate();
            template.update("INSERT INTO \"" + TEST_DSNAME + "\" VALUES (?)", " ".repeat(LRECL));
            DalyRejectWriter subject = new DalyRejectWriter(template, ASCII, bindings(LRECL, "F"),
                    RecordImageForm.CHARACTER);
            RejectsFile file = subject.openOutput(new Collector());

            assertThat(file.discardGeneration()).isEqualTo(FileStatus.Outcome.OK);
            assertThat(held(template)).isOne();
        }

        @Test
        @DisplayName("a relation holding rejects this run did not write is left untouched")
        void aCountMismatchIsRefused() {
            // Deliberately loud. These are rejected customer transactions, so silently deleting records
            // this run did not write would be far worse than an operator seeing an outcome.
            JdbcTemplate template = liveTemplate();
            DalyRejectWriter subject = new DalyRejectWriter(template, ASCII, bindings(LRECL, "F"),
                    RecordImageForm.CHARACTER);
            RejectsFile file = subject.openOutput();
            assertThat(file.writeRejectRec(image('A'), 102)).isEqualTo(FileStatus.Outcome.OK);
            template.update("INSERT INTO \"" + TEST_DSNAME + "\" VALUES (?)", " ".repeat(LRECL));

            assertThat(file.discardGeneration()).isEqualTo(FileStatus.Outcome.OTHER);
            assertThat(held(template)).isEqualTo(2);
        }

        @Test
        @DisplayName("a count the backend will not state is refused exactly as a wrong count is")
        void anUnstatedCountIsRefused() {
            // The other half of the guard aCountMismatchIsRefused drives. A backend that answers the
            // count query with SQL NULL has not said the generation holds what this run wrote - it has
            // said nothing - and "nothing" is not permission to delete rejected customer transactions.
            // A real COUNT(*) cannot be null, so the one call is bent and everything else runs for real.
            JdbcTemplate live = liveTemplate();
            JdbcTemplate template = Mockito.spy(live);
            DalyRejectWriter subject = new DalyRejectWriter(template, ASCII, bindings(LRECL, "F"),
                    RecordImageForm.CHARACTER);
            RejectsFile file = subject.openOutput();
            assertThat(file.writeRejectRec(image('A'), 102)).isEqualTo(FileStatus.Outcome.OK);

            // Stubbed after the open, so the open's own clear and probe are the real ones.
            Mockito.doReturn(null).when(template)
                    .queryForObject(Mockito.anyString(), Mockito.eq(Integer.class));

            assertThat(file.discardGeneration()).isEqualTo(FileStatus.Outcome.OTHER);
            assertThat(held(live))
                    .as("and the reject this run wrote is still there: refused means untouched")
                    .isOne();
        }

        @Test
        @DisplayName("a delete that removes a different number than it counted is reported, not called OK")
        void aDeleteRemovingADifferentCountIsReported() {
            // The count agreed, so the delete was issued - and then removed a different number of rows
            // than the count promised. Something changed the destination between the two statements, so
            // this run cannot claim it deleted its own generation and nothing else. Reporting OK here
            // would tell the operator the DISP=(NEW,CATLG,DELETE) obligation was met when it may not be.
            JdbcTemplate live = liveTemplate();
            JdbcTemplate template = Mockito.spy(live);
            DalyRejectWriter subject = new DalyRejectWriter(template, ASCII, bindings(LRECL, "F"),
                    RecordImageForm.CHARACTER);
            RejectsFile file = subject.openOutput();
            assertThat(file.writeRejectRec(image('A'), 102)).isEqualTo(FileStatus.Outcome.OK);

            // The count still runs for real and agrees; only the delete's answer is bent.
            Mockito.doReturn(99).when(template).update(Mockito.anyString());

            assertThat(file.discardGeneration()).isEqualTo(FileStatus.Outcome.OTHER);
        }

        @Test
        @DisplayName("a backend that refuses the disposition is reported, never raised")
        void aRefusedDispositionIsReported() throws SQLException {
            DataSource dataSource = Mockito.mock(DataSource.class);
            Connection connection = Mockito.mock(Connection.class);
            PreparedStatement statement = Mockito.mock(PreparedStatement.class);
            Statement plain = Mockito.mock(Statement.class);
            Mockito.when(dataSource.getConnection()).thenReturn(connection);
            Mockito.when(connection.prepareStatement(Mockito.anyString())).thenReturn(statement);
            Mockito.when(connection.createStatement()).thenReturn(plain);
            Mockito.when(plain.executeQuery(Mockito.anyString()))
                    .thenThrow(new SQLException("dataset dropped"));

            DalyRejectWriter subject = new DalyRejectWriter(new JdbcTemplate(dataSource), ASCII,
                    bindings(LRECL, "F"), RecordImageForm.CHARACTER);
            RejectsFile file = subject.openOutput();
            assertThat(file.writeRejectRec(image('A'), 102)).isEqualTo(FileStatus.Outcome.OK);

            assertThatCode(() -> assertThat(file.discardGeneration())
                    .isEqualTo(FileStatus.Outcome.OTHER)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a collector sink reports OK without implementing anything, and the method is a "
                + "default so a lambda still compiles")
        void aCollectorSinkDefaultsToOk() {
            RecordSink minimal = recordImage -> FileStatus.Outcome.OK;
            RejectsFile file = writer().openOutput(minimal);
            assertThat(file.writeRejectRec(image('A'), 102)).isEqualTo(FileStatus.Outcome.OK);

            assertThat(file.discardGeneration()).isEqualTo(FileStatus.Outcome.OK);
        }

        @Test
        @DisplayName("a sink answering null from discard is read as OTHER rather than raising")
        void aNullDiscardOutcomeIsReported() {
            RejectsFile file = writer().openOutput(new RecordSink() {
                @Override
                public FileStatus.Outcome write(byte[] recordImage) {
                    return FileStatus.Outcome.OK;
                }

                @Override
                public FileStatus.Outcome discard(int recordsWritten) {
                    return null;
                }
            });
            assertThat(file.writeRejectRec(image('A'), 102)).isEqualTo(FileStatus.Outcome.OK);

            assertThat(file.discardGeneration()).isEqualTo(FileStatus.Outcome.OTHER);
        }
    }
}
