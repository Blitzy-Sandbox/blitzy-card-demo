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
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
 */
@DisplayName("DalyRejectWriter - the 430-byte DALYREJS reject record writer")
class DalyRejectWriterTest {
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    private static final int LRECL = 430;

    private static final int TRAN_DATA_WIDTH = 350;

    private static final int REASON_WIDTH = 4;

    private static final int DESC_WIDTH = 76;

    private static final int FILLER_WIDTH = 20;

    private static final int FILLER_OFFSET = 330;

    private static final int AMT_OFFSET = 132;

    private static final int AMT_WIDTH = 11;

    private static final int AMT_SIGN_OFFSET = AMT_OFFSET + AMT_WIDTH - 1;

    private static final String FIXTURE_RESOURCE = "/fixtures/dailytran.txt";

    private static final int FIXTURE_ROWS = 300;

    private static final int[] NEGATIVE_ZERO_DIGIT_ROWS = {2, 55, 87, 150, 165, 210};

    private static final String ROW_2_AMT_IMAGE = "0000009190}";

    private static final String ROW_2_ID = "0000000001774260";

    private static final String NEGATIVE_ZERO_AMT_IMAGE = "0000000000}";

    private static final String POSITIVE_ZERO_AMT_IMAGE = "0000000000{";

    private static final String TEST_DSNAME = "TEST.M2.DALYREJS(+1)";

    private static DatasetBindings bindings(int recordLength, String recordFormat) {
        return catalogue(TEST_DSNAME, recordLength, recordFormat);
    }

    private static DatasetBindings catalogue(String dsname, int recordLength, String recordFormat) {
        DatasetBindings bindings = new DatasetBindings();
        bindings.put(DalyRejectWriter.DD_NAME, new DatasetBinding(dsname, "sequential", true,
                recordFormat, 0, recordLength, null, null, null, null, null));
        return bindings;
    }

    private static DalyRejectWriter writer() {
        return new DalyRejectWriter(new JdbcTemplate(), ASCII, bindings(LRECL, "F"),
                RecordImageForm.CHARACTER);
    }

    private static byte[] image(char body) {
        return (String.valueOf(body).repeat(TRAN_DATA_WIDTH - FILLER_WIDTH)
                + "F".repeat(FILLER_WIDTH)).getBytes(ASCII);
    }

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

        String only() {
            assertThat(records).hasSize(1);
            return new String(records.get(0), ASCII);
        }
    }

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

    private static byte[] fixtureRow(int line) {
        return fixtureRows().get(line - 1).getBytes(ASCII);
    }

    private static Stream<Arguments> reasonPairs() {
        return Stream.of(
                Arguments.of(100, "0100", "INVALID CARD NUMBER FOUND"),
                Arguments.of(101, "0101", "ACCOUNT RECORD NOT FOUND"),
                Arguments.of(102, "0102", "OVERLIMIT TRANSACTION"),
                Arguments.of(103, "0103", "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"),
                Arguments.of(109, "0109", "ACCOUNT RECORD NOT FOUND"));
    }

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

            assertThat(DalyRejectWriter.VALIDATION_TRAILER_OFFSET).isEqualTo(350);
            assertThat(DalyRejectWriter.VALIDATION_TRAILER_LENGTH).isEqualTo(80);
            assertThat(REASON_WIDTH + DESC_WIDTH).isEqualTo(80);

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

    @Nested
    @DisplayName("Gate G20 - fixed unblocked geometry, measured on the emitted bytes")
    class FixedFormatGeometryTests {
        @Test
        @DisplayName("the three spans occupy 0..349, 350..353 and 354..429 of the emitted record")
        void theThreeSpansOccupyTheirAbsoluteRanges() {
            Collector sink = new Collector();
            try (RejectsFile file = writer().openOutput(sink)) {
                file.writeRejectRec(image('T'), 103,
                        DalyRejectWriter.DESC_TRANSACTION_AFTER_EXPIRATION);
            }
            byte[] record = sink.records.get(0);
            assertThat(record).hasSize(LRECL);

            String recordImage = new String(record, ASCII);
            assertThat(recordImage.substring(0, TRAN_DATA_WIDTH))
                    .as("REJECT-TRAN-DATA occupies bytes 0..349")
                    .hasSize(TRAN_DATA_WIDTH)
                    .isEqualTo("T".repeat(TRAN_DATA_WIDTH - FILLER_WIDTH) + "F".repeat(FILLER_WIDTH));
            assertThat(recordImage.substring(350, 354))
                    .as("WS-VALIDATION-FAIL-REASON occupies bytes 350..353")
                    .hasSize(REASON_WIDTH)
                    .isEqualTo("0103");
            assertThat(recordImage.substring(354, 430))
                    .as("WS-VALIDATION-FAIL-REASON-DESC occupies bytes 354..429")
                    .hasSize(DESC_WIDTH)
                    .isEqualTo("TRANSACTION RECEIVED AFTER ACCT EXPIRATION" + " ".repeat(34));

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

            assertThat(sink.records).hasSize(bodies.length)
                    .allSatisfy(record -> assertThat(record).hasSize(LRECL));

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
            byte[] sent = image('Z');
            Collector sink = new Collector();
            try (RejectsFile file = writer().openOutput(sink)) {
                file.writeRejectRec(sent, 102);
            }
            byte[] record = sink.records.get(0);
            assertThat(record[0]).isEqualTo(sent[0]).isEqualTo((byte) 'Z');
            assertThat(Arrays.copyOf(record, 4)).isEqualTo(Arrays.copyOf(sent, 4));
            assertThat(new byte[] {record[0], record[1]}).isNotEqualTo(new byte[] {0x01, (byte) 0xAE});
        }

        @Test
        @DisplayName("carries no delimiter and no terminator inside the record image")
        void thereIsNoDelimiterOrTerminator() {
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
                assertThat(record[LRECL - 1]).isEqualTo((byte) ' ');
            });
        }

        @Test
        @DisplayName("refuses a record area of any width but 430 rather than writing it")
        void aWrongWidthAreaIsRefusedRatherThanWritten() {
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

            assertThat(reason).doesNotContain(" ").containsOnlyDigits();
            String digits = String.valueOf(code);
            if (digits.length() < REASON_WIDTH) {
                assertThat(reason)
                        .as("PIC 9(04) pads on the left with zeroes, not on the right with spaces")
                        .isNotEqualTo(digits + " ".repeat(REASON_WIDTH - digits.length()));
            }
        }

        @Test
        @DisplayName("the reason pads with zeroes and the description with spaces - never the reverse")
        void theTwoPaddingDirectionsAreOpposite() {
            Collector sink = new Collector();
            try (RejectsFile file = writer().openOutput(sink)) {
                file.writeRejectRec(image('X'), DalyRejectWriter.REASON_INVALID_CARD_NUMBER,
                        DalyRejectWriter.DESC_INVALID_CARD_NUMBER);
            }
            String record = sink.only();

            assertThat(record.substring(350, 354)).isEqualTo("0100")
                    .startsWith("0")
                    .doesNotEndWith(" ");
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

    @Nested
    @DisplayName("MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA - a copy, never a rebuild")
    class RawSpanCopyTests {
        @Test
        @DisplayName("the shipped fixture is the one measured here - 300 rows, six ending in '}'")
        void theFixtureCensusHolds() {
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
            assertThat(viaBytes.records.get(0)).isEqualTo(viaRecord.records.get(0));
        }

        @Test
        @DisplayName("FILLER X(20) at offset 330 arrives exactly as it left, whatever it holds")
        void theFillerSpanIsCopiedNotRegenerated() {
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
            assertThat(sink.records).allSatisfy(record -> {
                assertThat(record).hasSize(LRECL);
                assertThat(new String(record, 350, 4, ASCII)).isEqualTo("0101");
            });
        }

        @Test
        @DisplayName("no byte of the copied span is re-derived - all 350 offsets are the sender's")
        void everyOffsetOfTheCopiedSpanBelongsToTheSender() {
            byte[] sent = fixtureRow(2).clone();
            for (int offset = 0; offset < TRAN_DATA_WIDTH; offset++) {
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
            assertThat(new String(written, 350, 4, ASCII)).isEqualTo("0103");
        }

        @Test
        @DisplayName("moving a transaction leaves the trailer alone, and vice versa")
        void theTwoMovesAreIndependent() {
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
            assertThat(handle.isOpen()).isFalse();
        }

        @Test
        @DisplayName("refuses a null sink and directs the caller to openOutput()")
        void refusesANullSink() {
            assertThatNullPointerException().isThrownBy(() -> writer().openOutput(null))
                    .withMessageContaining("openOutput()");
        }
    }

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
            Collector sink = new Collector();
            sink.openAnswer = reported;
            sink.writeAnswer = reported;
            sink.closeAnswer = reported;

            RejectsFile file = writer().openOutput(sink);
            assertThat(file.openOutcome()).isEqualTo(reported);
            assertThat(file.writeRejectRec(image('t'), 102)).isEqualTo(reported);
            assertThat(file.closeOutput()).isEqualTo(reported);
            assertThat(sink.records).singleElement()
                    .satisfies(record -> assertThat(record).hasSize(LRECL));
        }

        @Test
        @DisplayName("the ladder hinges on batchStatus() being '00' - the same test the COBOL makes")
        void theLadderHingesOnTheStatusBeingZeroZero() {
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

            assertThat(accepted.batchStatus()).contains(FileStatus.OK);
            assertThat(accepted.batchStatus()).contains("00");
            assertThat(FileStatus.isOk(accepted.batchStatus().orElseThrow())).isTrue();
            assertThat(FileStatus.outcomeOfStatus("00")).isEqualTo(accepted);

            assertThat(refused).isEqualTo(FileStatus.Outcome.OTHER);
            assertThat(refused.batchStatus()).isEmpty();
            assertThat(refused).isNotEqualTo(accepted);
        }

        @Test
        @DisplayName("a refused write raises nothing - the abend is the posting job's to raise")
        void aRefusedWriteRaisesNothing() {
            Collector sink = new Collector();
            assertThatCode(() -> {
                try (RejectsFile file = writer().openOutput(sink)) {
                    sink.writeAnswer = FileStatus.Outcome.OTHER;
                    assertThat(file.writeRejectRec(image('d'), 100))
                            .isEqualTo(FileStatus.Outcome.OTHER);
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

            Collector emptyThenFails = new Collector();
            emptyThenFails.closeAnswer = FileStatus.Outcome.OTHER;
            assertThat(writer().openOutput(emptyThenFails).closeOutput())
                    .isEqualTo(FileStatus.Outcome.OTHER);
        }

        @Test
        @DisplayName("isOpen answers both ways, as an 88-level condition name does")
        void theOpenPredicateAnswersBothWays() {
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
                "TEST.M2.DALYREJS(BAD)"})
        @DisplayName("still constructs when the name is not a dataset, so the context can start")
        void defersANonDatasetName(String dsname) {
            DalyRejectWriter subject = new DalyRejectWriter(new JdbcTemplate(), ASCII,
                    catalogue(dsname, LRECL, "F"), RecordImageForm.CHARACTER);
            assertThat(subject.recordLength()).isEqualTo(LRECL);
            assertThatIllegalStateException().isThrownBy(subject::insertStatement)
                    .withMessageContaining("cannot be addressed as a dataset")
                    .withMessageContaining("openOutput(RecordSink)");
            assertThatIllegalStateException().isThrownBy(subject::openOutput);
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

    private static <T> T inUnitOfWork(java.util.function.Supplier<T> work) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            return work.get();
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Nested
    @DisplayName("The default JDBC sink - what reaches the driver")
    class JdbcSinkTests {
        @Test
        @DisplayName("the open establishes the generation and empties it - one describe, one delete "
                + "(0300-DALYREJS-OPEN, POSTTRAN.jcl:L34-L38)")
        void theOpenEstablishesAndClearsTheGeneration() throws SQLException {
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
            Mockito.verifyNoInteractions(insert);

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
                assertThat(inUnitOfWork(() -> file.writeRejectRec(image('T'), 102))).isEqualTo(FileStatus.Outcome.OK);
            }

            Mockito.verify(connection, Mockito.times(1)).prepareStatement(subject.insertStatement());
            Mockito.verify(statement).setString(Mockito.eq(1), Mockito.argThat(
                    value -> value != null && value.length() == LRECL
                            && value.startsWith("T") && value.substring(350, 354).equals("0102")));
        }

        @Test
        @DisplayName("the open probes the destination, and a refusal is reported there, not by a write")
        void theOpenProbesTheDestination() throws SQLException {
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
                assertThat(inUnitOfWork(() -> file.writeRejectRec(image('U'), 100)))
                        .isEqualTo(FileStatus.Outcome.OTHER);
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
                inUnitOfWork(() -> file.writeRejectRec(image('V'), 100));
            }
            Mockito.verify(statement).setBytes(Mockito.eq(1),
                    Mockito.argThat(value -> value != null && value.length == LRECL));
        }

        @Test
        @DisplayName("outside a unit of work the write is refused, because a lost reject cannot be "
                + "detected by anything downstream")
        void outsideAUnitOfWorkTheWriteIsRefused() throws SQLException {
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
                assertThat(file.openOutcome())
                        .as("the open carries no unit-of-work requirement")
                        .isEqualTo(FileStatus.Outcome.OK);

                assertThatIllegalStateException()
                        .isThrownBy(() -> file.writeRejectRec(image('Y'), 100))
                        .withMessageContaining("no transaction is open on this thread")
                        .withMessageContaining("changes stored records")
                        .withMessageContaining(TEST_DSNAME);

                assertThat(file.recordsWritten())
                        .as("a refused write is not a written record")
                        .isZero();
            }
            Mockito.verify(statement, Mockito.never()).executeUpdate();
        }
    }

    @Nested
    @DisplayName("The context-load gate - the writer bean really does wire (G3)")
    @SpringBootTest(classes = CardDemoApplication.class)
    @ActiveProfiles("test")
    class ContextWiringTests {
        @Autowired
        private ApplicationContext context;

        @Test
        @DisplayName("wires as a singleton whose geometry the shipped application.yml satisfies")
        void theWriterBeanWires() {
            DalyRejectWriter subject = context.getBean(DalyRejectWriter.class);

            assertThat(subject.recordLength()).isEqualTo(LRECL);
            assertThat(subject.datasetBinding().recordLength()).isEqualTo(LRECL);
            assertThat(subject.datasetBinding().recordFormat()).isEqualToIgnoringCase("F");
            assertThat(subject.datasetBinding().organization()).isEqualTo("sequential");
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
                assertThat(inUnitOfWork(() -> file.writeRejectRec(image('W'), 102))).isEqualTo(FileStatus.Outcome.OK);
            }

            assertThat(sink.records).singleElement()
                    .satisfies(record -> assertThat(record).hasSize(LRECL));
            assertThat(sink.only().substring(350, 354)).isEqualTo("0102");
        }
    }

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

    @Nested
    @DisplayName("The abnormal disposition deletes the generation an abended run wrote")
    class TheAbnormalDisposition {
        private JdbcTemplate liveTemplate() {
            JdbcTemplate template = new JdbcTemplate(new SimpleDriverDataSource(new org.h2.Driver(),
                    "jdbc:h2:mem:dalyrejs-disp-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
            template.execute("CREATE TABLE \"" + TEST_DSNAME + "\" (RECORD_IMAGE CHAR("
                    + LRECL + "))");
            return template;
        }

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

            assertThat(inUnitOfWork(() -> file.writeRejectRec(image('A'), 102))).isEqualTo(FileStatus.Outcome.OK);
            assertThat(inUnitOfWork(() -> file.writeRejectRec(image('B'), 100))).isEqualTo(FileStatus.Outcome.OK);
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
            assertThat(inUnitOfWork(() -> file.writeRejectRec(image('A'), 102))).isEqualTo(FileStatus.Outcome.OK);

            assertThat(file.discardGeneration()).isEqualTo(FileStatus.Outcome.OK);
            assertThat(file.discardGeneration()).isEqualTo(FileStatus.Outcome.OK);
            assertThat(held(template)).isZero();
        }

        @Test
        @DisplayName("a run whose input was entirely clean deletes nothing and reports OK")
        void anEmptyRunDeletesNothing() {
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
            JdbcTemplate template = liveTemplate();
            DalyRejectWriter subject = new DalyRejectWriter(template, ASCII, bindings(LRECL, "F"),
                    RecordImageForm.CHARACTER);
            RejectsFile file = subject.openOutput();
            assertThat(inUnitOfWork(() -> file.writeRejectRec(image('A'), 102))).isEqualTo(FileStatus.Outcome.OK);
            template.update("INSERT INTO \"" + TEST_DSNAME + "\" VALUES (?)", " ".repeat(LRECL));

            assertThat(file.discardGeneration()).isEqualTo(FileStatus.Outcome.OTHER);
            assertThat(held(template)).isEqualTo(2);
        }

        @Test
        @DisplayName("a count the backend will not state is refused exactly as a wrong count is")
        void anUnstatedCountIsRefused() {
            JdbcTemplate live = liveTemplate();
            JdbcTemplate template = Mockito.spy(live);
            DalyRejectWriter subject = new DalyRejectWriter(template, ASCII, bindings(LRECL, "F"),
                    RecordImageForm.CHARACTER);
            RejectsFile file = subject.openOutput();
            assertThat(inUnitOfWork(() -> file.writeRejectRec(image('A'), 102))).isEqualTo(FileStatus.Outcome.OK);

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
            JdbcTemplate live = liveTemplate();
            JdbcTemplate template = Mockito.spy(live);
            DalyRejectWriter subject = new DalyRejectWriter(template, ASCII, bindings(LRECL, "F"),
                    RecordImageForm.CHARACTER);
            RejectsFile file = subject.openOutput();
            assertThat(inUnitOfWork(() -> file.writeRejectRec(image('A'), 102))).isEqualTo(FileStatus.Outcome.OK);

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
            assertThat(inUnitOfWork(() -> file.writeRejectRec(image('A'), 102))).isEqualTo(FileStatus.Outcome.OK);

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
