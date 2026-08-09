package com.vsergeychik.carddemo.transaction;

import com.vsergeychik.carddemo.CardDemoApplication;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
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
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
 * </table>
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
            assertThat(sink.only().substring(350, 354)).isEqualTo(expected);
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
            // A negative amount's zoned overpunch survives, which a decode-and-re-encode would risk.
            assertThat(transaction.dalytranAmtImage()).isNotEqualTo("0000005047")
                    .hasSize(11);
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
        @ValueSource(strings = {"", "/var/tmp/dalyrejs.dat", "not a dataset", "TOOLONGQUALIFIER.X",
                "AWS.M2.DALYREJS(BAD)"})
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
        @DisplayName("binds the whole 430-byte image as one positional parameter")
        void bindsTheWholeImage() throws SQLException {
            DataSource dataSource = Mockito.mock(DataSource.class);
            Connection connection = Mockito.mock(Connection.class);
            PreparedStatement statement = Mockito.mock(PreparedStatement.class);
            Mockito.when(dataSource.getConnection()).thenReturn(connection);
            Mockito.when(connection.prepareStatement(Mockito.anyString())).thenReturn(statement);
            Mockito.when(statement.executeUpdate()).thenReturn(1);

            DalyRejectWriter subject = new DalyRejectWriter(new JdbcTemplate(dataSource), ASCII,
                    bindings(LRECL, "F"), RecordImageForm.CHARACTER);
            try (RejectsFile file = subject.openOutput()) {
                assertThat(file.writeRejectRec(image('T'), 102)).isEqualTo(FileStatus.Outcome.OK);
            }

            Mockito.verify(connection).prepareStatement(subject.insertStatement());
            Mockito.verify(statement).setString(Mockito.eq(1), Mockito.argThat(
                    value -> value != null && value.length() == LRECL
                            && value.startsWith("T") && value.substring(350, 354).equals("0102")));
        }

        @Test
        @DisplayName("maps a rejected write onto the arm that sets APPL-RESULT to 12")
        void mapsARejectedWriteToOther() throws SQLException {
            DataSource dataSource = Mockito.mock(DataSource.class);
            Connection connection = Mockito.mock(Connection.class);
            PreparedStatement statement = Mockito.mock(PreparedStatement.class);
            Mockito.when(dataSource.getConnection()).thenReturn(connection);
            Mockito.when(connection.prepareStatement(Mockito.anyString())).thenReturn(statement);
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
}
