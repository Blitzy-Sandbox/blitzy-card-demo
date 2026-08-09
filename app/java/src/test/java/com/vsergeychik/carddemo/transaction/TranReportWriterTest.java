package com.vsergeychik.carddemo.transaction;

import com.vsergeychik.carddemo.common.DatasetRelation;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.transaction.TranReportWriter.RecordSink;
import com.vsergeychik.carddemo.transaction.TranReportWriter.ReportFile;
import com.vsergeychik.carddemo.transaction.model.TranReportLayouts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

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
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link TranReportWriter}, the {@code TRANREPT} transaction detail report writer.
 *
 * <p>Plain JUnit 5 throughout: no application context, no {@code JobLauncher}, no filesystem and no
 * database. Every record is collected by an in-memory {@link RecordSink}, which is the seam the class
 * under test exposes precisely so that this is possible (practice B10, gate G51). The one place a
 * database type appears at all is {@link JdbcSinkTests}, where a mocked
 * {@link java.sql.PreparedStatement} is used to assert what reaches the driver - and even there nothing
 * is connected to anything.
 *
 * <h2>Every expectation is transcribed from the sources, not from the implementation</h2>
 *
 * <p>The pad counts below - <strong>18, 0, 19, 0, 19, 21, 21, 21</strong> - are written as literals
 * rather than computed from {@link TranReportWriter}'s own constants, and that is the whole point of
 * this suite. They were obtained by adding up the {@code PIC} clauses in {@code app/cpy/CVTRA07Y.cpy}
 * and subtracting from the {@code LRECL=133} that {@code app/jcl/TRANREPT.jcl:L78} and
 * {@code app/proc/TRANREPT.prc:L76} both declare. Asserting against the class's derived constants
 * instead would be circular: a wrong width would agree with itself.
 *
 * <table border="1">
 *   <caption>The arithmetic these literals come from</caption>
 *   <tr><th>Layout</th><th>Sum of PIC widths</th><th>133 minus that</th></tr>
 *   <tr><td>{@code REPORT-NAME-HEADER}</td><td>38+41+12+10+4+10 = 115</td><td>18</td></tr>
 *   <tr><td>{@code WS-BLANK-LINE}</td><td>{@code PIC X(133)} = 133</td><td>0</td></tr>
 *   <tr><td>{@code TRANSACTION-HEADER-1}</td><td>17+12+19+35+14+1+16 = 114</td><td>19</td></tr>
 *   <tr><td>{@code TRANSACTION-HEADER-2}</td><td>{@code PIC X(133)} = 133</td><td>0</td></tr>
 *   <tr><td>{@code TRANSACTION-DETAIL-REPORT}</td>
 *       <td>16+1+11+1+2+1+15+1+4+1+29+1+10+4+15+2 = 114</td><td>19</td></tr>
 *   <tr><td>{@code REPORT-PAGE-TOTALS}</td><td>11+86+15 = 112</td><td>21</td></tr>
 *   <tr><td>{@code REPORT-ACCOUNT-TOTALS}</td><td>13+84+15 = 112</td><td>21</td></tr>
 *   <tr><td>{@code REPORT-GRAND-TOTALS}</td><td>11+86+15 = 112</td><td>21</td></tr>
 * </table>
 *
 * <p>The eight layout images themselves are produced by {@link TranReportLayouts}, which has its own
 * suite asserting them field by field against the copybook. Using them here rather than hand-typing 133
 * characters eight times is deliberate: it means this suite tests the <em>seam between</em> the two
 * classes - the {@code MOVE <layout> TO FD-REPTFILE-REC PIC X(133)} - which is exactly where a missing
 * pad would hide.
 */
@DisplayName("TranReportWriter - the 133-byte TRANREPT report record writer")
class TranReportWriterTest {

    /** The code page, named explicitly. Never the platform default (practice B8). */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** {@code LRECL=133} from app/jcl/TRANREPT.jcl:L78, written as a literal on purpose. */
    private static final int LRECL = 133;

    /**
     * A well-formed z/OS dataset name for the tests, carrying the {@code (+1)} relative generation the
     * real binding uses because {@code TRANREPT} is a generation data group
     * (app/jcl/REPTFILE.jcl:L25-L28). No production dataset name appears in Java (gate G46).
     */
    private static final String TEST_DSNAME = "TEST.M2.TRANREPT(+1)";

    // =================================================================================================
    // Fixtures.
    // =================================================================================================

    /**
     * A {@code TRANREPT} catalogue with the given geometry.
     *
     * @param recordLength the record length to declare
     * @param recordFormat the record format to declare, or {@code null} to omit the key
     * @return the catalogue
     */
    private static DatasetBindings bindings(int recordLength, String recordFormat) {
        return catalogue(TEST_DSNAME, recordLength, recordFormat);
    }

    /**
     * A {@code TRANREPT} catalogue naming an arbitrary location, so the dataset-name checks can be
     * driven over every shape a deployment might supply.
     *
     * @param dsname       the value to bind, which need not be a dataset name
     * @param recordLength the record length to declare
     * @param recordFormat the record format to declare, or {@code null} to omit the key
     * @return the catalogue
     */
    private static DatasetBindings catalogue(String dsname, int recordLength, String recordFormat) {
        DatasetBindings bindings = new DatasetBindings();
        bindings.put(TranReportWriter.DD_NAME, new DatasetBinding(dsname, "sequential", true,
                recordFormat, 0, recordLength, "CVTRA07Y", null, null, null, null));
        return bindings;
    }

    /** A correctly configured writer over {@link #ASCII}. */
    private static TranReportWriter writer() {
        return new TranReportWriter(new JdbcTemplate(), ASCII, bindings(LRECL, "FB"),
                RecordImageForm.CHARACTER);
    }

    /**
     * A correctly configured writer whose {@code TRANREPT} binding names the given location.
     *
     * @param dsname the value to bind, which need not be a dataset name
     * @return the writer
     */
    private static TranReportWriter writerBoundTo(String dsname) {
        return new TranReportWriter(new JdbcTemplate(), ASCII, catalogue(dsname, LRECL, "FB"),
                RecordImageForm.CHARACTER);
    }

    /** A populated layout set, so the detail and total lines carry real content rather than blanks. */
    private static TranReportLayouts populatedLayouts() {
        TranReportLayouts layouts = new TranReportLayouts(ASCII);
        layouts.moveReptStartDate("2022-01-01");
        layouts.moveReptEndDate("2022-07-06");
        layouts.initializeTransactionDetailReport();
        layouts.moveTranReportTransId("0000000000000001");
        layouts.moveTranReportAccountId(12345678901L);
        layouts.moveTranReportTypeCd("01");
        layouts.moveTranReportTypeDesc("Purchase");
        layouts.moveTranReportCatCd(1010L);
        layouts.moveTranReportCatDesc("Regular Sales Draft");
        layouts.moveTranReportSource("POS TERM");
        layouts.moveTranReportAmt(new BigDecimal("-1234.56"));
        layouts.moveReptPageTotal(new BigDecimal("1234.56"));
        layouts.moveReptAccountTotal(new BigDecimal("2345.67"));
        layouts.moveReptGrandTotal(new BigDecimal("3456.78"));
        return layouts;
    }

    /**
     * The eight layouts, each paired with the width the copybook declares and the pad this writer must
     * add. Every number is a literal transcribed from the source, never read back from the subject.
     *
     * @return name, image, natural width, expected pad
     */
    private static Stream<Arguments> theEightLayouts() {
        TranReportLayouts layouts = populatedLayouts();
        return Stream.of(
                Arguments.of("REPORT-NAME-HEADER", layouts.renderReportNameHeader(), 115, 18),
                Arguments.of("WS-BLANK-LINE", TranReportWriter.WS_BLANK_LINE_IMAGE, 133, 0),
                Arguments.of("TRANSACTION-HEADER-1", layouts.renderTransactionHeader1(), 114, 19),
                Arguments.of("TRANSACTION-HEADER-2", layouts.renderTransactionHeader2(), 133, 0),
                Arguments.of("TRANSACTION-DETAIL-REPORT", layouts.renderTransactionDetailReport(),
                        114, 19),
                Arguments.of("REPORT-PAGE-TOTALS", layouts.renderReportPageTotals(), 112, 21),
                Arguments.of("REPORT-ACCOUNT-TOTALS", layouts.renderReportAccountTotals(), 112, 21),
                Arguments.of("REPORT-GRAND-TOTALS", layouts.renderReportGrandTotals(), 112, 21));
    }

    /** The same eight layouts as byte images, for the {@code byte[]} overloads. */
    private static Stream<Arguments> theEightLayoutsAsBytes() {
        TranReportLayouts layouts = populatedLayouts();
        return Stream.of(
                Arguments.of("REPORT-NAME-HEADER", layouts.renderReportNameHeaderBytes(), 18),
                Arguments.of("WS-BLANK-LINE",
                        TranReportWriter.WS_BLANK_LINE_IMAGE.getBytes(ASCII), 0),
                Arguments.of("TRANSACTION-HEADER-1", layouts.renderTransactionHeader1Bytes(), 19),
                Arguments.of("TRANSACTION-HEADER-2", layouts.renderTransactionHeader2Bytes(), 0),
                Arguments.of("TRANSACTION-DETAIL-REPORT",
                        layouts.renderTransactionDetailReportBytes(), 19),
                Arguments.of("REPORT-PAGE-TOTALS", layouts.renderReportPageTotalsBytes(), 21),
                Arguments.of("REPORT-ACCOUNT-TOTALS", layouts.renderReportAccountTotalsBytes(), 21),
                Arguments.of("REPORT-GRAND-TOTALS", layouts.renderReportGrandTotalsBytes(), 21));
    }

    /**
     * An in-memory sink that keeps every record it is handed, in order.
     *
     * <p>The answer it gives is configurable so both arms of the COBOL guard chain - the
     * {@code TRANREPT-STATUS = '00'} arm and the arm that sets {@code APPL-RESULT} to 12 - are reachable
     * without a backend (gate G47).
     */
    private static final class Collector implements RecordSink {

        /** Every record handed over, in call order. */
        private final List<byte[]> records = new ArrayList<>();

        /** What {@link #open()} answers, or {@code null} to exercise the contract violation. */
        private FileStatus.Outcome openAnswer = FileStatus.Outcome.OK;

        /** What {@link #write(byte[])} answers, or {@code null} to exercise the contract violation. */
        private FileStatus.Outcome writeAnswer = FileStatus.Outcome.OK;

        /** What {@link #close()} answers, or {@code null} to exercise the contract violation. */
        private FileStatus.Outcome closeAnswer = FileStatus.Outcome.OK;

        /** How many times {@link #open()} was reached. */
        private int opens;

        /** How many times {@link #close()} was reached. */
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

        /** The records as decoded strings. */
        private List<String> images() {
            return records.stream().map(record -> new String(record, ASCII)).toList();
        }

        /** The single record written, as a string. */
        private String onlyImage() {
            assertThat(records).hasSize(1);
            return images().get(0);
        }
    }

    /**
     * Counts the trailing spaces of an image, which is the pad this writer added.
     *
     * @param image the emitted record
     * @return the trailing space count
     */
    private static int trailingSpaces(String image) {
        int count = 0;
        while (count < image.length() && image.charAt(image.length() - 1 - count) == ' ') {
            count++;
        }
        return count;
    }

    // =================================================================================================
    // The dataset contract.
    // =================================================================================================

    @Nested
    @DisplayName("The dataset contract - app/jcl/TRANREPT.jcl:L76-L80")
    class DatasetContract {

        @Test
        @DisplayName("the DD name is TRANREPT, from SELECT REPORT-FILE ASSIGN TO TRANREPT")
        void ddNameIsTranrept() {
            assertThat(TranReportWriter.DD_NAME).isEqualTo("TRANREPT");
        }

        @Test
        @DisplayName("the record is 133 bytes - LRECL=133 and 01 FD-REPTFILE-REC PIC X(133) (G20)")
        void recordLengthIs133() {
            assertThat(TranReportWriter.RECORD_LENGTH).isEqualTo(133);
            assertThat(writer().recordLength()).isEqualTo(133);
        }

        @Test
        @DisplayName("the record format is FB and the block size is the JCL's BLKSIZE=0")
        void recordFormatIsFixedBlocked() {
            assertThat(TranReportWriter.RECORD_FORMAT).isEqualTo("FB");
            assertThat(TranReportWriter.BLOCK_SIZE).isZero();
        }

        @Test
        @DisplayName("the FD record area is named exactly as the COBOL names it")
        void fdRecordAreaIsNamedAfterTheCobol() {
            assertThat(TranReportWriter.FD_REPTFILE_REC).isEqualTo("FD-REPTFILE-REC");
        }

        @Test
        @DisplayName("the injected code page is exposed, so a caller decodes with the same one")
        void exposesTheInjectedCodePage() {
            assertThat(writer().datasetCharset()).isEqualTo(ASCII);
        }

        @Test
        @DisplayName("the configured binding is exposed verbatim")
        void exposesTheConfiguredBinding() {
            DatasetBinding binding = writer().datasetBinding();

            assertThat(binding.dsname()).isEqualTo(TEST_DSNAME);
            assertThat(binding.organization()).isEqualTo("sequential");
            assertThat(binding.gdg()).isTrue();
            assertThat(binding.recordFormat()).isEqualTo("FB");
            assertThat(binding.recordLength()).isEqualTo(LRECL);
            assertThat(binding.copybook()).isEqualTo("CVTRA07Y");
        }
    }

    // =================================================================================================
    // The pad arithmetic. Literals from the copybook, never from the subject.
    // =================================================================================================

    @Nested
    @DisplayName("The pad arithmetic - 133 minus each layout's declared width")
    class PadArithmetic {

        @Test
        @DisplayName("18 / 0 / 19 / 0 / 19 / 21 / 21 / 21, in the copybook's own declaration order")
        void thePadsAreTheDeclaredDifferences() {
            assertThat(TranReportWriter.REPORT_NAME_HEADER_PAD).isEqualTo(18);
            assertThat(TranReportWriter.WS_BLANK_LINE_PAD).isEqualTo(0);
            assertThat(TranReportWriter.TRANSACTION_HEADER_1_PAD).isEqualTo(19);
            assertThat(TranReportWriter.TRANSACTION_HEADER_2_PAD).isEqualTo(0);
            assertThat(TranReportWriter.TRANSACTION_DETAIL_REPORT_PAD).isEqualTo(19);
            assertThat(TranReportWriter.REPORT_PAGE_TOTALS_PAD).isEqualTo(21);
            assertThat(TranReportWriter.REPORT_ACCOUNT_TOTALS_PAD).isEqualTo(21);
            assertThat(TranReportWriter.REPORT_GRAND_TOTALS_PAD).isEqualTo(21);
        }

        @Test
        @DisplayName("WS-BLANK-LINE is 133 spaces - PIC X(133) VALUE SPACES at CBTRN03C:L133")
        void blankLineIsAFullWidthOfSpaces() {
            assertThat(TranReportWriter.WS_BLANK_LINE_IMAGE).hasSize(133)
                    .isEqualTo(" ".repeat(133));
        }

        @Test
        @DisplayName("both edit masks are 15 bytes: sign + 3 + , + 3 + , + 3 + . + 2")
        void bothEditMasksAreFifteenBytes() {
            // Not this class's constants, but the arithmetic that makes its 19 and 21 correct: the
            // detail line and all three totals end in a 15-byte edited amount.
            assertThat(TranReportLayouts.DETAIL_AMOUNT_MASK).hasSize(15);
            assertThat(TranReportLayouts.TOTAL_AMOUNT_MASK).hasSize(15);
            assertThat(TranReportLayouts.AMOUNT_MASK_WIDTH).isEqualTo(15);
        }

        @Test
        @DisplayName("the ALL '.' leaders are 86, 84 and 86, and 11+86 == 13+84 == 97")
        void theLeadersAlignAllThreeAmounts() {
            assertThat(TranReportLayouts.PAGE_TOTAL_LEADER_LENGTH).isEqualTo(86);
            assertThat(TranReportLayouts.ACCOUNT_TOTAL_LEADER_LENGTH).isEqualTo(84);
            assertThat(TranReportLayouts.GRAND_TOTAL_LEADER_LENGTH).isEqualTo(86);

            assertThat(TranReportLayouts.PAGE_TOTAL_LABEL_LENGTH
                    + TranReportLayouts.PAGE_TOTAL_LEADER_LENGTH).isEqualTo(97);
            assertThat(TranReportLayouts.ACCOUNT_TOTAL_LABEL_LENGTH
                    + TranReportLayouts.ACCOUNT_TOTAL_LEADER_LENGTH).isEqualTo(97);
            assertThat(TranReportLayouts.GRAND_TOTAL_LABEL_LENGTH
                    + TranReportLayouts.GRAND_TOTAL_LEADER_LENGTH).isEqualTo(97);
        }
    }

    // =================================================================================================
    // The eight layouts, written. This is gate G20.
    // =================================================================================================

    @Nested
    @DisplayName("All eight layouts, written - exactly 133 bytes each (G20)")
    class TheEightLayouts {

        @ParameterizedTest(name = "{0}: {2} bytes + {3} pad = 133")
        @MethodSource("com.vsergeychik.carddemo.transaction.TranReportWriterTest#theEightLayouts")
        @DisplayName("each is emitted as 133 bytes with exactly the pad the copybook implies")
        void eachIsEmittedAs133BytesWithTheDeclaredPad(String name, String image, int naturalWidth,
                                                       int expectedPad) {
            assertThat(image).as("%s renders at its natural width", name).hasSize(naturalWidth);

            Collector sink = new Collector();
            try (ReportFile file = writer().openOutput(sink)) {
                assertThat(file.writeLine(image)).isEqualTo(FileStatus.Outcome.OK);
            }

            String written = sink.onlyImage();
            assertThat(written).as("%s must reach the dataset as 133 bytes", name).hasSize(133);
            assertThat(written).as("%s must keep its rendered prefix", name).startsWith(image);
            assertThat(written.substring(naturalWidth))
                    .as("%s must be padded with exactly %d space(s)", name, expectedPad)
                    .hasSize(expectedPad)
                    .matches(pad -> pad.chars().allMatch(character -> character == ' '));
        }

        @ParameterizedTest(name = "{0}: + {2} pad")
        @MethodSource(
                "com.vsergeychik.carddemo.transaction.TranReportWriterTest#theEightLayoutsAsBytes")
        @DisplayName("the byte overload pads identically, decoding under the injected code page")
        void theByteOverloadPadsIdentically(String name, byte[] image, int expectedPad) {
            Collector sink = new Collector();
            try (ReportFile file = writer().openOutput(sink)) {
                assertThat(file.writeLine(image)).isEqualTo(FileStatus.Outcome.OK);
            }

            String written = sink.onlyImage();
            assertThat(written).as("%s must reach the dataset as 133 bytes", name).hasSize(133);
            assertThat(trailingSpaces(written) >= expectedPad)
                    .as("%s must carry at least its %d pad space(s)", name, expectedPad)
                    .isTrue();
            assertThat(written).startsWith(new String(image, ASCII));
        }

        @Test
        @DisplayName("TRANSACTION-HEADER-2 is 133 consecutive hyphens - no pad, no truncation")
        void header2IsOneHundredAndThirtyThreeHyphens() {
            String rule = new TranReportLayouts(ASCII).renderTransactionHeader2();
            Collector sink = new Collector();
            try (ReportFile file = writer().openOutput(sink)) {
                file.writeLine(rule);
            }

            assertThat(sink.onlyImage()).hasSize(133).isEqualTo("-".repeat(133))
                    .matches("-{133}");
        }

        @Test
        @DisplayName("WS-BLANK-LINE is 133 spaces - no pad, no truncation")
        void blankLineIsOneHundredAndThirtyThreeSpaces() {
            Collector sink = new Collector();
            try (ReportFile file = writer().openOutput(sink)) {
                file.writeLine(TranReportWriter.WS_BLANK_LINE_IMAGE);
            }

            assertThat(sink.onlyImage()).hasSize(133).isEqualTo(" ".repeat(133))
                    .containsOnlyWhitespaces();
        }
    }

    // =================================================================================================
    // The MOVE rule itself.
    // =================================================================================================

    @Nested
    @DisplayName("MOVE <layout> TO FD-REPTFILE-REC PIC X(133)")
    class TheMoveRule {

        @Test
        @DisplayName("an over-long image is truncated on the RIGHT, not rejected and not wrapped")
        void anOverLongImageIsTruncatedOnTheRight() {
            // 140 characters, so seven must be discarded. COBOL fills a PIC X receiver from its
            // leftmost position and drops the overflow, so the SURVIVORS are the leading 133 - the
            // opposite of what a numeric MOVE would do.
            String tooLong = "A".repeat(133) + "BBBBBBB";
            Collector sink = new Collector();

            try (ReportFile file = writer().openOutput(sink)) {
                assertThatCode(() -> file.writeLine(tooLong)).doesNotThrowAnyException();
            }

            assertThat(sink.records).as("no wrapping onto a second record").hasSize(1);
            assertThat(sink.onlyImage()).hasSize(133).isEqualTo("A".repeat(133))
                    .doesNotContain("B");
        }

        @Test
        @DisplayName("an empty image becomes 133 spaces, because the receiver is space-padded")
        void anEmptyImageBecomesAFullWidthOfSpaces() {
            Collector sink = new Collector();
            try (ReportFile file = writer().openOutput(sink)) {
                file.writeLine("");
            }

            assertThat(sink.onlyImage()).isEqualTo(" ".repeat(133));
        }

        @Test
        @DisplayName("a freshly opened handle's record area is already 133 spaces")
        void aFreshRecordAreaIsSpaces() {
            try (ReportFile file = writer().openOutput(new Collector())) {
                assertThat(file.reportRecord()).isEqualTo(" ".repeat(133));
                assertThat(file.reportRecordBytes()).hasSize(133);
                assertThat(file.recordsWritten()).isZero();
                assertThat(file.isOpen()).isTrue();
            }
        }

        @Test
        @DisplayName("the MOVE and the WRITE are separable, as the COBOL separates them")
        void theMoveAndTheWriteAreSeparable() {
            Collector sink = new Collector();
            try (ReportFile file = writer().openOutput(sink)) {
                file.moveToReportRecord("Page Total");

                assertThat(sink.records).as("MOVE alone writes nothing").isEmpty();
                assertThat(file.reportRecord()).hasSize(133).startsWith("Page Total");

                assertThat(file.writeReportRec()).isEqualTo(FileStatus.Outcome.OK);
                assertThat(file.recordsWritten()).isEqualTo(1);
            }

            assertThat(sink.onlyImage()).isEqualTo("Page Total" + " ".repeat(123));
        }

        @Test
        @DisplayName("writing twice without a new MOVE emits the same record twice")
        void writingTwiceWithoutAMoveRepeatsTheRecord() {
            Collector sink = new Collector();
            try (ReportFile file = writer().openOutput(sink)) {
                file.moveToReportRecord("-".repeat(133));
                file.writeReportRec();
                file.writeReportRec();
            }

            assertThat(sink.images()).containsExactly("-".repeat(133), "-".repeat(133));
        }

        @Test
        @DisplayName("the byte overload of the MOVE reaches the same record area")
        void theByteOverloadOfTheMoveReachesTheSameArea() {
            try (ReportFile file = writer().openOutput(new Collector())) {
                file.moveToReportRecord("Grand Total".getBytes(ASCII));

                assertThat(file.reportRecord()).isEqualTo("Grand Total" + " ".repeat(122));
            }
        }

        @Test
        @DisplayName("reportRecordBytes hands out a fresh array, never the record's own storage")
        void reportRecordBytesIsACopy() {
            try (ReportFile file = writer().openOutput(new Collector())) {
                byte[] first = file.reportRecordBytes();
                first[0] = 'Z';

                assertThat(file.reportRecord()).startsWith(" ");
                assertThat(file.reportRecordBytes()).isNotSameAs(first);
            }
        }

        @Test
        @DisplayName("the record image is read back untrimmed, because the pad is part of the record")
        void theRecordIsReadBackUntrimmed() {
            try (ReportFile file = writer().openOutput(new Collector())) {
                file.moveToReportRecord("Account Total");

                assertThat(file.reportRecord()).hasSize(133).endsWith(" ");
            }
        }

        @Test
        @DisplayName("a null sending value is refused, because a COBOL MOVE has no null sender")
        void aNullSendingValueIsRefused() {
            try (ReportFile file = writer().openOutput(new Collector())) {
                assertThatNullPointerException()
                        .isThrownBy(() -> file.moveToReportRecord((String) null))
                        .withMessageContaining("FD-REPTFILE-REC");
                assertThatNullPointerException()
                        .isThrownBy(() -> file.moveToReportRecord((byte[]) null))
                        .withMessageContaining("FD-REPTFILE-REC");
                assertThatNullPointerException().isThrownBy(() -> file.writeLine((String) null));
                assertThatNullPointerException().isThrownBy(() -> file.writeLine((byte[]) null));
            }
        }
    }

    // =================================================================================================
    // Write order and counting.
    // =================================================================================================

    @Nested
    @DisplayName("Write order - the report's line sequence is its content")
    class WriteOrder {

        @Test
        @DisplayName("records reach the sink in call order, one per call, nothing coalesced")
        void recordsReachTheSinkInCallOrder() {
            // The order 1120-WRITE-HEADERS produces (app/cbl/CBTRN03C.cbl:L324-L341), followed by a
            // detail line and a page total - which is the shape of a real page.
            TranReportLayouts layouts = populatedLayouts();
            Collector sink = new Collector();

            try (ReportFile file = writer().openOutput(sink)) {
                file.writeLine(layouts.renderReportNameHeader());
                file.writeLine(TranReportWriter.WS_BLANK_LINE_IMAGE);
                file.writeLine(layouts.renderTransactionHeader1());
                file.writeLine(layouts.renderTransactionHeader2());
                file.writeLine(layouts.renderTransactionDetailReport());
                file.writeLine(layouts.renderReportPageTotals());

                assertThat(file.recordsWritten()).isEqualTo(6);
            }

            List<String> written = sink.images();
            assertThat(written).hasSize(6).allSatisfy(record -> assertThat(record).hasSize(133));
            assertThat(written.get(0)).startsWith("DALYREPT");
            assertThat(written.get(1)).isEqualTo(" ".repeat(133));
            assertThat(written.get(2)).startsWith("Transaction ID");
            assertThat(written.get(3)).isEqualTo("-".repeat(133));
            assertThat(written.get(4)).startsWith("0000000000000001");
            assertThat(written.get(5)).startsWith("Page Total");
        }

        @Test
        @DisplayName("the record count is not WS-LINE-COUNTER: it counts handovers, rejects included")
        void theRecordCountCountsEveryHandover() {
            Collector sink = new Collector();
            sink.writeAnswer = FileStatus.Outcome.OTHER;

            try (ReportFile file = writer().openOutput(sink)) {
                assertThat(file.writeLine("first")).isEqualTo(FileStatus.Outcome.OTHER);
                assertThat(file.writeLine("second")).isEqualTo(FileStatus.Outcome.OTHER);

                assertThat(file.recordsWritten()).isEqualTo(2);
            }
        }

        @Test
        @DisplayName("two handles are independent, so concurrent runs cannot share a record area")
        void twoHandlesAreIndependent() {
            TranReportWriter writer = writer();
            Collector first = new Collector();
            Collector second = new Collector();

            try (ReportFile one = writer.openOutput(first);
                 ReportFile two = writer.openOutput(second)) {
                one.moveToReportRecord("ONE");
                two.moveToReportRecord("TWO");

                assertThat(one.reportRecord()).startsWith("ONE");
                assertThat(two.reportRecord()).startsWith("TWO");

                one.writeReportRec();
            }

            assertThat(first.records).hasSize(1);
            assertThat(second.records).isEmpty();
        }
    }

    // =================================================================================================
    // The guard chain: outcomes, never abends.
    // =================================================================================================

    @Nested
    @DisplayName("1111-WRITE-REPORT-REC - outcomes, never an abend (G47)")
    class Outcomes {

        @Test
        @DisplayName("the '00' arm and the APPL-RESULT 12 arm are both reachable")
        void bothArmsAreReachable() {
            Collector sink = new Collector();
            try (ReportFile file = writer().openOutput(sink)) {
                assertThat(file.writeLine("ok")).isEqualTo(FileStatus.Outcome.OK);

                sink.writeAnswer = FileStatus.Outcome.OTHER;
                assertThat(file.writeLine("rejected")).isEqualTo(FileStatus.Outcome.OTHER);
            }
        }

        @Test
        @DisplayName("no path throws AbendException, because the abend belongs to the report job")
        void noPathAbends() {
            Collector sink = new Collector();
            sink.writeAnswer = FileStatus.Outcome.OTHER;
            sink.closeAnswer = FileStatus.Outcome.OTHER;

            ReportFile file = writer().openOutput(sink);

            assertThatCode(() -> {
                file.writeLine("rejected");
                file.closeOutput();
            }).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the '00' close arm and the failing close arm are both reachable")
        void bothCloseArmsAreReachable() {
            Collector clean = new Collector();
            assertThat(writer().openOutput(clean).closeOutput()).isEqualTo(FileStatus.Outcome.OK);

            Collector refusing = new Collector();
            refusing.closeAnswer = FileStatus.Outcome.OTHER;
            assertThat(writer().openOutput(refusing).closeOutput())
                    .isEqualTo(FileStatus.Outcome.OTHER);
        }

        @Test
        @DisplayName("close is idempotent and does not reach the sink twice")
        void closeIsIdempotent() {
            Collector sink = new Collector();
            ReportFile file = writer().openOutput(sink);

            assertThat(file.closeOutput()).isEqualTo(FileStatus.Outcome.OK);
            assertThat(file.isOpen()).isFalse();
            assertThat(file.closeOutput()).isEqualTo(FileStatus.Outcome.OK);
            file.close();

            assertThat(sink.closes).isEqualTo(1);
        }

        @Test
        @DisplayName("AutoCloseable close swallows a dataset condition rather than masking the block")
        void autoCloseableCloseSwallowsADatasetCondition() {
            Collector sink = new Collector();
            sink.closeAnswer = FileStatus.Outcome.OTHER;

            assertThatCode(() -> {
                try (ReportFile file = writer().openOutput(sink)) {
                    file.writeLine("line");
                }
            }).doesNotThrowAnyException();

            assertThat(sink.closes).isEqualTo(1);
        }

        @Test
        @DisplayName("a sink answering null is a contract breach and is rejected at the call site")
        void aNullOutcomeFromWriteIsRejected() {
            Collector sink = new Collector();
            sink.writeAnswer = null;
            ReportFile file = writer().openOutput(sink);

            assertThatNullPointerException().isThrownBy(() -> file.writeLine("line"))
                    .withMessageContaining("write(byte[])")
                    .withMessageContaining("no answer");
        }

        @Test
        @DisplayName("a sink answering null from close is rejected too, and propagates from close()")
        void aNullOutcomeFromCloseIsRejected() {
            Collector sink = new Collector();
            sink.closeAnswer = null;
            ReportFile file = writer().openOutput(sink);

            assertThatNullPointerException().isThrownBy(file::close)
                    .withMessageContaining("close()")
                    .withMessageContaining("no answer");
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"move", "moveBytes", "write", "writeLine", "writeLineBytes"})
        @DisplayName("every operation on a closed handle is a sequencing defect and throws")
        void everyOperationOnAClosedHandleThrows(String operation) {
            ReportFile file = writer().openOutput(new Collector());
            file.closeOutput();

            assertThatIllegalStateException().isThrownBy(() -> {
                switch (operation) {
                    case "move" -> file.moveToReportRecord("x");
                    case "moveBytes" -> file.moveToReportRecord("x".getBytes(ASCII));
                    case "write" -> file.writeReportRec();
                    case "writeLine" -> file.writeLine("x");
                    default -> file.writeLine("x".getBytes(ASCII));
                }
            }).withMessageContaining("TRANREPT").withMessageContaining("closed after 0 record(s)");
        }

        @Test
        @DisplayName("the default sink open() and close() answer OK, because they hold nothing")
        void theDefaultSinkOpenAndCloseAnswerOk() {
            // The seam stays a single-abstract-method interface: both open() and close() are defaults,
            // so a lambda is still a valid sink. If either ever stopped being a default this line would
            // not compile, which is the point of writing it as a lambda.
            RecordSink minimal = recordImage -> FileStatus.Outcome.OK;

            assertThat(minimal.open()).isEqualTo(FileStatus.Outcome.OK);
            assertThat(minimal.close()).isEqualTo(FileStatus.Outcome.OK);
        }
    }

    // =================================================================================================
    // 0100-REPTFILE-OPEN - the third outcome, which completes the set.
    // =================================================================================================

    @Nested
    @DisplayName("0100-REPTFILE-OPEN - the open outcome (app/cbl/CBTRN03C.cbl:L394-L410)")
    class OpenOutcome {

        @Test
        @DisplayName("the sink is asked exactly once, and the '00' arm is reported")
        void theSinkIsAskedOnceAndReportsTheOkArm() {
            Collector sink = new Collector();

            try (ReportFile file = writer().openOutput(sink)) {
                assertThat(file.openOutcome()).isEqualTo(FileStatus.Outcome.OK);
                assertThat(sink.opens).as("OPEN OUTPUT happens once per run").isEqualTo(1);
            }
        }

        @Test
        @DisplayName("the failing arm is reported, and does NOT throw - the abend is the job's")
        void theFailingArmIsReportedRatherThanThrown() {
            Collector sink = new Collector();
            sink.openAnswer = FileStatus.Outcome.OTHER;

            assertThatCode(() -> {
                ReportFile file = writer().openOutput(sink);
                assertThat(file.openOutcome()).isEqualTo(FileStatus.Outcome.OTHER);
            }).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a failed open still yields a usable, still-open handle for the job to unwind")
        void aFailedOpenStillYieldsAUsableHandle() {
            // The COBOL abends on this arm (L405-L408), but the abend belongs to the report job. A
            // writer that pre-closed the handle or threw would take that decision away from it, and
            // would be inconsistent with how a rejected WRITE is treated three lines further down.
            Collector sink = new Collector();
            sink.openAnswer = FileStatus.Outcome.OTHER;

            ReportFile file = writer().openOutput(sink);

            assertThat(file.isOpen()).isTrue();
            assertThat(file.recordsWritten()).isZero();
            assertThat(file.reportRecord()).isEqualTo(" ".repeat(133));
            assertThat(file.closeOutput()).isEqualTo(FileStatus.Outcome.OK);
        }

        @Test
        @DisplayName("the open outcome is stable: a later rejected write does not rewrite history")
        void theOpenOutcomeIsStable() {
            Collector sink = new Collector();

            try (ReportFile file = writer().openOutput(sink)) {
                assertThat(file.openOutcome()).isEqualTo(FileStatus.Outcome.OK);

                sink.writeAnswer = FileStatus.Outcome.OTHER;
                assertThat(file.writeLine("line")).isEqualTo(FileStatus.Outcome.OTHER);

                assertThat(file.openOutcome()).isEqualTo(FileStatus.Outcome.OK);
            }
        }

        @Test
        @DisplayName("a sink answering null from open() is a contract breach and is rejected")
        void aNullOutcomeFromOpenIsRejected() {
            Collector sink = new Collector();
            sink.openAnswer = null;
            TranReportWriter subject = writer();

            assertThatNullPointerException().isThrownBy(() -> subject.openOutput(sink))
                    .withMessageContaining("open()")
                    .withMessageContaining("no answer");
        }

        @Test
        @DisplayName("the JdbcTemplate-backed default sink reports OK: it prepares nothing at open")
        void theDefaultSinkReportsOkAtOpen() throws SQLException {
            // It borrows a pooled connection per record rather than holding one open, so there is
            // nothing for it to establish here and open() is correctly left defaulted.
            DataSource dataSource = Mockito.mock(DataSource.class);
            Connection connection = Mockito.mock(Connection.class);
            PreparedStatement statement = Mockito.mock(PreparedStatement.class);
            Mockito.when(dataSource.getConnection()).thenReturn(connection);
            Mockito.when(connection.prepareStatement(Mockito.anyString())).thenReturn(statement);

            TranReportWriter subject = new TranReportWriter(new JdbcTemplate(dataSource), ASCII,
                    bindings(LRECL, "FB"), RecordImageForm.CHARACTER);

            try (ReportFile file = subject.openOutput()) {
                assertThat(file.openOutcome()).isEqualTo(FileStatus.Outcome.OK);
            }

            Mockito.verifyNoInteractions(statement);
        }

        @Test
        @DisplayName("all three of the COBOL paragraphs now have an outcome the job can branch on")
        void allThreeParagraphsReportAnOutcome() {
            // 0100-REPTFILE-OPEN, 1111-WRITE-REPORT-REC and 9100-REPTFILE-CLOSE run the same shape of
            // guard, so all three must be readable here and none of them decided here.
            Collector sink = new Collector();
            ReportFile file = writer().openOutput(sink);

            assertThat(file.openOutcome()).isEqualTo(FileStatus.Outcome.OK);
            assertThat(file.writeLine(TranReportWriter.WS_BLANK_LINE_IMAGE))
                    .isEqualTo(FileStatus.Outcome.OK);
            assertThat(file.closeOutput()).isEqualTo(FileStatus.Outcome.OK);
        }
    }

    // =================================================================================================
    // Construction - the fail-fast geometry checks.
    // =================================================================================================

    @Nested
    @DisplayName("Construction - the geometry is verified before the application can start")
    class Construction {

        @Test
        @DisplayName("every collaborator is required, and the diagnostic says which and why")
        void everyCollaboratorIsRequired() {
            DatasetBindings valid = bindings(LRECL, "FB");

            assertThatNullPointerException().isThrownBy(() -> new TranReportWriter(null, ASCII, valid,
                    RecordImageForm.CHARACTER)).withMessageContaining("JdbcTemplate");
            assertThatNullPointerException().isThrownBy(() -> new TranReportWriter(new JdbcTemplate(),
                    null, valid, RecordImageForm.CHARACTER)).withMessageContaining("charset");
            assertThatNullPointerException().isThrownBy(() -> new TranReportWriter(new JdbcTemplate(),
                    ASCII, null, RecordImageForm.CHARACTER)).withMessageContaining("carddemo.datasets");
            assertThatNullPointerException().isThrownBy(() -> new TranReportWriter(new JdbcTemplate(),
                    ASCII, valid, null)).withMessageContaining("record-image");
        }

        @Test
        @DisplayName("a record length other than 133 fails at startup, naming both widths (G20)")
        void aWrongRecordLengthFailsAtStartup() {
            assertThatIllegalStateException().isThrownBy(() -> new TranReportWriter(
                    new JdbcTemplate(), ASCII, bindings(132, "FB"), RecordImageForm.CHARACTER))
                    .withMessageContaining("record-length 132")
                    .withMessageContaining("133 bytes")
                    .withMessageContaining("app/cbl/CBTRN03C.cbl:L85");
        }

        @Test
        @DisplayName("a record format other than FB fails at startup")
        void aWrongRecordFormatFailsAtStartup() {
            assertThatIllegalStateException().isThrownBy(() -> new TranReportWriter(
                    new JdbcTemplate(), ASCII, bindings(LRECL, "F"), RecordImageForm.CHARACTER))
                    .withMessageContaining("record-format 'F'")
                    .withMessageContaining("RECFM=FB");
        }

        @Test
        @DisplayName("an omitted record format reads as absent, not as the word null")
        void anAbsentRecordFormatIsDescribedAsAbsent() {
            assertThatIllegalStateException().isThrownBy(() -> new TranReportWriter(
                    new JdbcTemplate(), ASCII, bindings(LRECL, null), RecordImageForm.CHARACTER))
                    .withMessageContaining("record-format absent")
                    .withMessageNotContaining("'null'");
        }

        @Test
        @DisplayName("the format comparison ignores case, as the catalogue's own validation does")
        void theFormatComparisonIgnoresCase() {
            assertThatCode(() -> new TranReportWriter(new JdbcTemplate(), ASCII,
                    bindings(LRECL, "fb"), RecordImageForm.CHARACTER)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("an unconfigured DD name is reported by the catalogue itself")
        void anUnconfiguredDdNameIsReported() {
            assertThatIllegalStateException().isThrownBy(() -> new TranReportWriter(
                    new JdbcTemplate(), ASCII, new DatasetBindings(), RecordImageForm.CHARACTER))
                    .withMessageContaining("TRANREPT");
        }

        @Test
        @DisplayName("a valid dataset name composes the one-parameter positional insert")
        void aValidDatasetNameComposesTheInsert() {
            assertThat(writer().insertStatement())
                    .isEqualTo(DatasetRelation.of(TEST_DSNAME, LRECL).insertRecordImage())
                    .isEqualTo("INSERT INTO \"" + TEST_DSNAME + "\" VALUES (?)");
        }

        @ParameterizedTest(name = "dsname = \"{0}\"")
        @ValueSource(strings = {"", "not a dataset name", "TOOLONGAQUALIFIER.X", "/tmp/tranrept.txt"})
        @DisplayName("a name that is not a dataset name is refused where it would become SQL")
        void aNameThatIsNotADatasetNameIsRefusedAtUse(String dsname) {
            TranReportWriter subject = writerBoundTo(dsname);

            assertThatIllegalStateException().isThrownBy(subject::insertStatement)
                    .withMessageContaining("carddemo.datasets.TRANREPT.dsname");
        }

        @Test
        @DisplayName("an absent dsname is refused with its own reason, distinct from a malformed one")
        void anAbsentDsnameIsRefusedWithItsOwnReason() {
            assertThatIllegalStateException().isThrownBy(() -> writerBoundTo(null).insertStatement())
                    .withMessageContaining("carddemo.datasets.TRANREPT.dsname")
                    .satisfies(refused -> assertThat(refused.getCause())
                            .isInstanceOf(IllegalArgumentException.class));
        }

        @Test
        @DisplayName("it still constructs under a fixture-backed binding, so the context starts (G3)")
        void stillConstructsUnderAFixtureBackedBinding() {
            // The 'test' profile binds TRANREPT to a filesystem location. That is not a dataset name
            // and can never become a SQL identifier - but refusing the bean outright would stop the
            // application context from starting under the only profile this environment can run, even
            // though every test and the parity harness supply their own sink.
            TranReportWriter fixtureBound = writerBoundTo("/tmp/carddemo/tranrept.txt");

            assertThat(fixtureBound.recordLength()).isEqualTo(LRECL);
            assertThat(fixtureBound.datasetBinding().dsname()).isEqualTo("/tmp/carddemo/tranrept.txt");
            assertThatCode(() -> fixtureBound.openOutput(new Collector())).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("openOutput refuses a null sink and names the alternative")
        void openOutputRefusesANullSink() {
            assertThatNullPointerException().isThrownBy(() -> writer().openOutput(null))
                    .withMessageContaining("record sink is required");
        }

        @Test
        @DisplayName("the default openOutput refuses when the configured name cannot be addressed")
        void theDefaultOpenOutputRefusesAnUnaddressableName() {
            TranReportWriter fixtureBound = writerBoundTo("/tmp/carddemo/tranrept.txt");

            assertThatIllegalStateException().isThrownBy(fixtureBound::openOutput)
                    .withMessageContaining("openOutput(RecordSink)");
        }
    }

    // =================================================================================================
    // The default sink. The one part no test can prove end to end (risk R-E).
    // =================================================================================================

    @Nested
    @DisplayName("The JdbcTemplate-backed default sink")
    class JdbcSinkTests {

        @Test
        @DisplayName("binds the whole 133-byte image as one parameter, in the configured form")
        void bindsTheWholeImageAsOneParameter() throws SQLException {
            for (RecordImageForm form : RecordImageForm.values()) {
                DataSource dataSource = Mockito.mock(DataSource.class);
                Connection connection = Mockito.mock(Connection.class);
                PreparedStatement statement = Mockito.mock(PreparedStatement.class);
                Mockito.when(dataSource.getConnection()).thenReturn(connection);
                Mockito.when(connection.prepareStatement(Mockito.anyString())).thenReturn(statement);

                TranReportWriter subject = new TranReportWriter(new JdbcTemplate(dataSource), ASCII,
                        bindings(LRECL, "FB"), form);

                try (ReportFile file = subject.openOutput()) {
                    assertThat(file.writeLine("-".repeat(133))).isEqualTo(FileStatus.Outcome.OK);
                }

                Mockito.verify(connection)
                        .prepareStatement("INSERT INTO \"" + TEST_DSNAME + "\" VALUES (?)");
                if (form == RecordImageForm.BINARY) {
                    Mockito.verify(statement).setBytes(1, "-".repeat(133).getBytes(ASCII));
                    Mockito.verify(statement, Mockito.never())
                            .setString(Mockito.anyInt(), Mockito.anyString());
                } else {
                    Mockito.verify(statement).setString(1, "-".repeat(133));
                    Mockito.verify(statement, Mockito.never())
                            .setBytes(Mockito.anyInt(), Mockito.any());
                }
                Mockito.verify(statement).executeUpdate();
            }
        }

        @Test
        @DisplayName("maps a rejected write onto OTHER instead of throwing")
        void mapsARejectedWriteOntoOther() throws SQLException {
            DataSource dataSource = Mockito.mock(DataSource.class);
            Mockito.when(dataSource.getConnection())
                    .thenThrow(new SQLException("dataset unavailable"));

            TranReportWriter subject = new TranReportWriter(new JdbcTemplate(dataSource), ASCII,
                    bindings(LRECL, "FB"), RecordImageForm.CHARACTER);
            ReportFile file = subject.openOutput();

            assertThat(file.writeLine(TranReportWriter.WS_BLANK_LINE_IMAGE))
                    .isEqualTo(FileStatus.Outcome.OTHER);
            assertThat(file.recordsWritten()).isEqualTo(1);
            assertThat(file.closeOutput()).isEqualTo(FileStatus.Outcome.OK);
        }

        @Test
        @DisplayName("lets a wiring defect propagate rather than reporting it as a bad write")
        void letsAWiringDefectPropagate() {
            // A JdbcTemplate with no DataSource is a wiring defect, not a dataset condition. It raises
            // an unchecked type outside the DataAccessException family, which the sink deliberately does
            // not catch: reporting it as a failed write would make every line abend with a misleading
            // reason instead of failing once, clearly.
            ReportFile file = writer().openOutput();

            assertThatIllegalStateException().isThrownBy(() -> file.writeLine("line"));
        }
    }

    // =================================================================================================
    // Structural properties.
    // =================================================================================================

    @Nested
    @DisplayName("Structural properties")
    class Structure {

        @Test
        @DisplayName("no static field is mutable (practice B9, gate G53)")
        void noStaticFieldIsMutable() {
            List<String> mutable = new ArrayList<>();
            for (Class<?> type : List.of(TranReportWriter.class, ReportFile.class)) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())
                            && !Modifier.isFinal(field.getModifiers())
                            && !field.isSynthetic()
                            && !field.getName().contains("$")) {
                        mutable.add(type.getSimpleName() + "." + field.getName());
                    }
                }
            }

            assertThat(mutable).isEmpty();
        }

        @Test
        @DisplayName("the writer bean itself holds no mutable state - every instance field is final")
        void theWriterBeanIsImmutable() {
            List<String> nonFinal = new ArrayList<>();
            for (Field field : TranReportWriter.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())
                        && !Modifier.isFinal(field.getModifiers())
                        && !field.isSynthetic()) {
                    nonFinal.add(field.getName());
                }
            }

            assertThat(nonFinal).isEmpty();
        }

        @Test
        @DisplayName("the class is final, so the padding rule cannot be overridden away")
        void theClassIsFinal() {
            assertThat(Modifier.isFinal(TranReportWriter.class.getModifiers())).isTrue();
            assertThat(Modifier.isFinal(ReportFile.class.getModifiers())).isTrue();
        }
    }
}
