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
import java.util.Locale;
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
 *
 * <h2>What is asserted about the record's interior, and why</h2>
 *
 * <p>A record of the right length can still be the wrong record, so five further families of
 * assertion look inside the 133 bytes at absolute offsets (rule R5):
 *
 * <ul>
 *   <li>{@link RecordFormatSemantics} - the pad is {@code 0x20}, never {@code NUL} and never the digit
 *       zero; the rule line's byte at 0-based offset 132 is a hyphen rather than a pad space; every
 *       line kind written in one run is the same length; and no record carries a newline, carriage
 *       return or any other non-printable byte, because {@code RECFM=FB} has no record delimiter and a
 *       delimiter inside the image would <em>be</em> the defect.</li>
 *   <li>{@link NinetySevenColumnInvariant} - the {@code ALL '.'} leaders are 86, 84 and 86 because the
 *       labels are 11, 13 and 11, so label + leader is 97 on all three total lines and the 15-byte
 *       amount always occupies 1-based columns 98-112. {@code TRANSACTION-HEADER-1}'s {@code 'Amount'}
 *       heading ends on column 112 for the same reason.</li>
 *   <li>{@link EditMasks} - {@code PIC -ZZZ,ZZZ,ZZZ.ZZ} and {@code PIC +ZZZ,ZZZ,ZZZ.ZZ} rendered byte
 *       for byte, including {@code Z} suppression, the suppression of a comma inside a suppressed run,
 *       the all-{@code Z} zero rule, truncation with {@code RoundingMode.DOWN} rather than any
 *       {@code HALF_*} mode (rule R2, gate G24), and the two {@code '-'}-valued {@code FILLER}s at
 *       1-based columns 32 and 53 (gate G21).</li>
 *   <li>{@link DescriptionTruncation} - {@code TRAN-TYPE-DESC PIC X(50)} into
 *       {@code TRAN-REPORT-TYPE-DESC PIC X(15)} and {@code TRAN-CAT-TYPE-DESC PIC X(50)} into
 *       {@code TRAN-REPORT-CAT-DESC PIC X(29)}, both truncating on the <em>right</em>.</li>
 *   <li>{@link NotThisWritersJob} - the layouts are <em>not</em> pre-padded, so 133 appears in this one
 *       class and nowhere else, and a write moves no line counter.</li>
 * </ul>
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

    /**
     * Writes one layout image through a fresh handle and returns the single emitted record.
     *
     * <p>A new writer, a new handle and a new sink per call, so no assertion can depend on what ran
     * before it and the suite stays order-independent (practice B7).
     *
     * @param layoutImage the rendered layout image to move and write
     * @return the emitted record as characters, exactly {@link #LRECL} of them
     */
    private static String emitted(String layoutImage) {
        return new String(emittedBytes(layoutImage), ASCII);
    }

    /**
     * Writes one layout image through a fresh handle and returns the single emitted record's bytes.
     *
     * <p>The byte form is what the assertions about pad bytes, control characters and the {@code '-'}
     * separators need: a {@code char} comparison cannot distinguish {@code 0x20} from any other
     * whitespace, and it is the bytes that reach the dataset.
     *
     * @param layoutImage the rendered layout image to move and write
     * @return the emitted record's bytes in the injected code page
     */
    private static byte[] emittedBytes(String layoutImage) {
        Collector sink = new Collector();
        try (ReportFile file = writer().openOutput(sink)) {
            assertThat(file.writeLine(layoutImage)).isEqualTo(FileStatus.Outcome.OK);
        }
        assertThat(sink.records).hasSize(1);
        return sink.records.get(0);
    }

    /**
     * The names of a type's integer-valued <em>instance</em> fields, in declaration order.
     *
     * <p>Used to state which numbers a class owns, which is how "this writer does not keep a line
     * counter" is asserted structurally rather than by matching field names against words - a name
     * match would flag {@code WS_BLANK_LINE_IMAGE} and {@code REPORT_PAGE_TOTALS_PAD}, both of which
     * are layout facts rather than pagination state.
     *
     * @param type the type to inspect
     * @return the names of its {@code int}, {@code long}, {@code Integer} and {@code Long} instance
     *         fields
     */
    private static List<String> numericFieldNames(Class<?> type) {
        List<String> names = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            Class<?> fieldType = field.getType();
            if (fieldType == int.class || fieldType == long.class || fieldType == Integer.class
                    || fieldType == Long.class) {
                names.add(field.getName());
            }
        }
        return names;
    }

    /**
     * A {@code REPORT-PAGE-TOTALS} image carrying the given amount, rendered at its natural 112.
     *
     * <p>The page total is used wherever an assertion needs one total line rather than all three,
     * because it is the line the COBOL writes most often and the one whose amount
     * {@code 1110-WRITE-PAGE-TOTALS} resets to zero after every page.
     *
     * @param pageTotal the amount to edit through {@code PIC +ZZZ,ZZZ,ZZZ.ZZ}
     * @return the rendered layout image, 112 characters
     */
    private static String pageTotalOf(BigDecimal pageTotal) {
        TranReportLayouts layouts = new TranReportLayouts(ASCII);
        layouts.moveReptPageTotal(pageTotal);
        return layouts.renderReportPageTotals();
    }

    /**
     * The three total lines, each with its label text, both declared widths and the 15-byte amount image
     * the line must carry.
     *
     * <p>The widths are transcribed from {@code app/cpy/CVTRA07Y.cpy} and the amount images are derived
     * by hand from the {@code PIC +ZZZ,ZZZ,ZZZ.ZZ} mask, never read back from the subject. The three
     * amounts are chosen to be different shapes on purpose - one positive, one negative, one zero - so
     * a single hard-coded expectation cannot satisfy all three.
     *
     * @return name, label text, label width, leader width, rendered image, expected amount image
     */
    private static Stream<Arguments> theThreeTotalLines() {
        TranReportLayouts layouts = new TranReportLayouts(ASCII);
        layouts.moveReptPageTotal(new BigDecimal("1234.56"));
        layouts.moveReptAccountTotal(new BigDecimal("-2345.67"));
        layouts.moveReptGrandTotal(new BigDecimal("0.00"));
        return Stream.of(
                Arguments.of("REPORT-PAGE-TOTALS", "Page Total", 11, 86,
                        layouts.renderReportPageTotals(), "+      1,234.56"),
                Arguments.of("REPORT-ACCOUNT-TOTALS", "Account Total", 13, 84,
                        layouts.renderReportAccountTotals(), "-      2,345.67"),
                Arguments.of("REPORT-GRAND-TOTALS", "Grand Total", 11, 86,
                        layouts.renderReportGrandTotals(), "               "));
    }

    /**
     * Cases for {@code TRAN-REPORT-AMT PIC -ZZZ,ZZZ,ZZZ.ZZ}.
     *
     * <p>Every expected image is 15 characters derived by hand from the mask: position 1 is the fixed
     * sign insertion, positions 2-4, 6-8 and 10-12 are the nine {@code Z} integer digit slots, positions
     * 5 and 9 the two commas, position 13 the decimal point and 14-15 the two fractional {@code Z}s.
     * Suppression replaces every leading zero - and every comma to the left of the first significant
     * digit - with a space, and stops at the decimal point.
     *
     * @return rule described, sending value, expected 15-character image
     */
    private static Stream<Arguments> detailAmountCases() {
        return Stream.of(
                Arguments.of("zero blanks the whole item - every digit position is Z",
                        new BigDecimal("0.00"), "               "),
                Arguments.of("a negative shows '-' in position 1",
                        new BigDecimal("-1234.56"), "-      1,234.56"),
                Arguments.of("a positive leaves position 1 blank, because the mask's sign is '-'",
                        new BigDecimal("1234.56"), "       1,234.56"),
                Arguments.of("both commas are suppressed when both lie left of the first digit",
                        new BigDecimal("100.00"), "         100.00"),
                Arguments.of("a comma at or right of the first digit prints",
                        new BigDecimal("1000000.00"), "   1,000,000.00"),
                Arguments.of("a full-width value suppresses nothing",
                        new BigDecimal("999999999.99"), " 999,999,999.99"),
                Arguments.of("a full-width negative fills position 1 with the minus",
                        new BigDecimal("-999999999.99"), "-999,999,999.99"),
                Arguments.of("suppression stops at the decimal point, never past it",
                        new BigDecimal("0.05"), "            .05"),
                Arguments.of("a negative sub-unit value keeps its sign and its blank integer part",
                        new BigDecimal("-0.05"), "-           .05"),
                Arguments.of("excess fractional digits truncate DOWN - HALF_UP would render 2.00",
                        new BigDecimal("1.999"), "           1.99"),
                Arguments.of("truncation is toward zero, so -1.999 stores -1.99 and not -2.00",
                        new BigDecimal("-1.999"), "-          1.99"),
                Arguments.of("a value that truncates to zero blanks the item; HALF_UP would show .01",
                        new BigDecimal("-0.009"), "               "));
    }

    /**
     * Cases for {@code REPT-PAGE-TOTAL}, {@code REPT-ACCOUNT-TOTAL} and {@code REPT-GRAND-TOTAL}, all
     * three {@code PIC +ZZZ,ZZZ,ZZZ.ZZ}.
     *
     * <p>Identical to the detail mask in every respect but one: position 1 carries a {@code '+'} when
     * the value is not negative instead of a space. The zero case is the exception that proves the
     * all-{@code Z} rule outranks the fixed sign - a total netting to zero prints a blank column, not
     * {@code +0.00}.
     *
     * @return rule described, sending value, expected 15-character image
     */
    private static Stream<Arguments> totalAmountCases() {
        return Stream.of(
                Arguments.of("zero blanks the whole item, sign included",
                        new BigDecimal("0.00"), "               "),
                Arguments.of("a positive total always shows its '+'",
                        new BigDecimal("1234.56"), "+      1,234.56"),
                Arguments.of("a negative total shows '-' in the same position",
                        new BigDecimal("-1234.56"), "-      1,234.56"),
                Arguments.of("both commas are suppressed when both lie left of the first digit",
                        new BigDecimal("100.00"), "+        100.00"),
                Arguments.of("a comma at or right of the first digit prints",
                        new BigDecimal("1000000.00"), "+  1,000,000.00"),
                Arguments.of("a full-width total suppresses nothing",
                        new BigDecimal("999999999.99"), "+999,999,999.99"),
                Arguments.of("suppression stops at the decimal point",
                        new BigDecimal("0.05"), "+           .05"),
                Arguments.of("excess fractional digits truncate DOWN",
                        new BigDecimal("1234567.891"), "+  1,234,567.89"));
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
    // RECFM=FB. app/jcl/TRANREPT.jcl:L78 and app/proc/TRANREPT.prc:L76, both DCB=(LRECL=133,RECFM=FB).
    // =================================================================================================

    @Nested
    @DisplayName("RECFM=FB semantics - one uniform 133-byte image with nothing inside it but data")
    class RecordFormatSemantics {

        @ParameterizedTest(name = "{0}: pad {3} byte(s)")
        @MethodSource("com.vsergeychik.carddemo.transaction.TranReportWriterTest#theEightLayouts")
        @DisplayName("the pad is 0x20 - never NUL, never the digit zero, never any other whitespace")
        void thePadIsSpacesAndNothingElse(String name, String image, int naturalWidth,
                                         int expectedPad) {
            assertThat(naturalWidth + expectedPad)
                    .as("%s: the copybook's declared width plus its pad is the record width", name)
                    .isEqualTo(LRECL);

            byte[] record = emittedBytes(image);

            assertThat(record).as("%s reaches the dataset as %d bytes", name, LRECL).hasSize(LRECL);
            for (int offset = naturalWidth; offset < LRECL; offset++) {
                // A COBOL alphanumeric MOVE space-fills the remainder of the receiver. Zero-filling it
                // would be the PIC 9 rule applied to a PIC X receiver, and NUL-filling it would be a
                // freshly allocated Java array left untouched - both produce a 133-byte record that
                // looks correct to a length check and is wrong on the dataset.
                assertThat(record[offset])
                        .as("%s pad byte at 0-based offset %d", name, offset)
                        .isEqualTo((byte) 0x20)
                        .isNotEqualTo((byte) 0x00)
                        .isNotEqualTo((byte) '0');
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.vsergeychik.carddemo.transaction.TranReportWriterTest#theEightLayouts")
        @DisplayName("no newline, carriage return or control byte appears anywhere in the image")
        void theImageCarriesNoRecordDelimiter(String name, String image, int naturalWidth,
                                              int expectedPad) {
            assertThat(naturalWidth + expectedPad).isEqualTo(LRECL);

            byte[] record = emittedBytes(image);

            for (int offset = 0; offset < record.length; offset++) {
                // RECFM=FB carries no record delimiter: records are separated by their fixed length and
                // nothing else. A newline inside the image would not delimit anything - it would be
                // one of the 133 data bytes, silently displacing every column after it.
                assertThat(record[offset])
                        .as("%s byte at 0-based offset %d must be printable data", name, offset)
                        .isNotEqualTo((byte) '\n')
                        .isNotEqualTo((byte) '\r')
                        .isNotEqualTo((byte) 0x00)
                        .isBetween((byte) 0x20, (byte) 0x7E);
            }
        }

        @Test
        @DisplayName("every line kind written in one run is the same length - the F in RECFM=FB")
        void everyLineKindInOneRunIsTheSameLength() {
            TranReportLayouts layouts = populatedLayouts();
            Collector sink = new Collector();
            try (ReportFile file = writer().openOutput(sink)) {
                file.writeLine(layouts.renderReportNameHeader());
                file.writeLine(TranReportWriter.WS_BLANK_LINE_IMAGE);
                file.writeLine(layouts.renderTransactionHeader1());
                file.writeLine(layouts.renderTransactionHeader2());
                file.writeLine(layouts.renderTransactionDetailReport());
                file.writeLine(layouts.renderReportPageTotals());
                file.writeLine(layouts.renderReportAccountTotals());
                file.writeLine(layouts.renderReportGrandTotals());
            }

            assertThat(sink.records).hasSize(8);
            assertThat(sink.images().stream().map(String::length).distinct().toList())
                    .as("eight line kinds of four different natural widths, one emitted width")
                    .containsExactly(LRECL);
        }

        @Test
        @DisplayName("TRANSACTION-HEADER-2's byte at 0-based offset 132 is a hyphen, not a pad space")
        void theRuleLinesLastByteIsAHyphen() {
            byte[] record = emittedBytes(new TranReportLayouts(ASCII).renderTransactionHeader2());

            assertThat(record).hasSize(LRECL);
            // 01 TRANSACTION-HEADER-2 PIC X(133) VALUE ALL '-' is an ELEMENTARY item that is already
            // the record width, so it must pass through with no pad at all. A writer that padded it
            // would still emit 133 hyphens - but only because the truncation happened to remove
            // exactly what the pad added, and offset 132 is where that accident shows.
            assertThat(record[132])
                    .as("the 133rd byte of PIC X(133) VALUE ALL '-'")
                    .isEqualTo((byte) '-');
            assertThat(record[LRECL - 1]).isEqualTo((byte) '-');
            for (int offset = 0; offset < LRECL; offset++) {
                assertThat(record[offset]).as("0-based offset %d", offset).isEqualTo((byte) '-');
            }
            assertThat(TranReportLayouts.TRANSACTION_HEADER_2_RULE_CHARACTER).isEqualTo('-');
        }

        @Test
        @DisplayName("WS-BLANK-LINE's 133 bytes are all 0x20, offset 132 included")
        void theBlankLinesBytesAreAllSpaces() {
            byte[] record = emittedBytes(TranReportWriter.WS_BLANK_LINE_IMAGE);

            assertThat(record).hasSize(LRECL);
            assertThat(record[132]).isEqualTo((byte) 0x20);
            for (int offset = 0; offset < LRECL; offset++) {
                assertThat(record[offset]).as("0-based offset %d", offset).isEqualTo((byte) 0x20);
            }
        }
    }

    // =================================================================================================
    // The 97-column invariant. app/cpy/CVTRA07Y.cpy, the three total lines plus the detail line.
    // =================================================================================================

    @Nested
    @DisplayName("The 97-column invariant - why the ALL '.' leaders are 86, 84 and 86")
    class NinetySevenColumnInvariant {

        @ParameterizedTest(name = "{0}: label {2} + leader {3} = 97")
        @MethodSource("com.vsergeychik.carddemo.transaction.TranReportWriterTest#theThreeTotalLines")
        @DisplayName("label + leader is 97 on every total line, and the leader really is dots")
        void labelPlusLeaderIsNinetySeven(String name, String label, int labelWidth, int leaderWidth,
                                          String image, String expectedAmount) {
            assertThat(labelWidth + leaderWidth)
                    .as("%s: FILLER X(%d) label then FILLER X(%d) VALUE ALL '.'", name, labelWidth,
                            leaderWidth)
                    .isEqualTo(97);

            String record = emitted(image);

            assertThat(record).hasSize(LRECL);
            assertThat(record.substring(0, labelWidth))
                    .as("%s label, space-padded to its declared X(%d)", name, labelWidth)
                    .isEqualTo(label + " ".repeat(labelWidth - label.length()));
            assertThat(record.substring(labelWidth, 97))
                    .as("%s leader: FILLER X(%d) VALUE ALL '.'", name, leaderWidth)
                    .hasSize(leaderWidth)
                    .matches("\\.{" + leaderWidth + "}");
            assertThat(record.substring(97, 112))
                    .as("%s amount, 1-based columns 98-112", name)
                    .hasSize(15)
                    .isEqualTo(expectedAmount);
            assertThat(record.substring(112))
                    .as("%s: the 21 pad spaces this writer added, 1-based columns 113-133", name)
                    .isEqualTo(" ".repeat(21));
        }

        @Test
        @DisplayName("one window - 1-based columns 98-112 - holds the amount on all three total lines")
        void oneWindowHoldsTheAmountOnAllThreeTotalLines() {
            // The three leaders differ - 86, 84, 86 - BECAUSE the three labels differ - 11, 13, 11.
            // Only the sums agree: 11 + 86 == 13 + 84 == 97. That is what puts the 15-byte
            // +ZZZ,ZZZ,ZZZ.ZZ mask in the same columns on every total line, so one window reads all
            // three. Normalising the leaders to a single width would move the account total's amount
            // two columns left of the other two and break the report's alignment. Never "tidy" them.
            List<String> amounts = theThreeTotalLines()
                    .map(line -> emitted((String) line.get()[4]).substring(97, 112))
                    .toList();

            assertThat(amounts).hasSize(3)
                    .allSatisfy(amount -> assertThat(amount).hasSize(15))
                    .containsExactly("+      1,234.56", "-      2,345.67", " ".repeat(15));
        }

        @Test
        @DisplayName("the same window holds TRAN-REPORT-AMT on the detail line")
        void theDetailLineSharesTheSameWindow() {
            String record = emitted(populatedLayouts().renderTransactionDetailReport());

            // 16+1+11+1+2+1+15+1+4+1+29+1+10+4 = 97 bytes precede TRAN-REPORT-AMT, which is the same
            // 97 the totals reach through a label and a dot leader. Two entirely different item
            // sequences, one amount column - and that is the whole design of CVTRA07Y.
            assertThat(record.substring(97, 112)).hasSize(15).isEqualTo("-      1,234.56");
            assertThat(record.substring(112, 114))
                    .as("FILLER PIC X(02) VALUE SPACES closes the detail line at its natural 114")
                    .isEqualTo("  ");
            assertThat(TranReportLayouts.AMOUNT_OFFSET).isEqualTo(97);
            assertThat(TranReportLayouts.AMOUNT_COLUMN_START)
                    .as("the 1-based column of a 0-based offset is one greater")
                    .isEqualTo(TranReportLayouts.AMOUNT_OFFSET + 1)
                    .isEqualTo(98);
            assertThat(TranReportLayouts.AMOUNT_COLUMN_END)
                    .isEqualTo(TranReportLayouts.AMOUNT_COLUMN_START + 15 - 1)
                    .isEqualTo(112);
        }

        @Test
        @DisplayName("TRANSACTION-HEADER-1's 'Amount' heading ends on column 112, over that window")
        void theHeadingAmountEndsOnColumnOneHundredAndTwelve() {
            String record = emitted(new TranReportLayouts(ASCII).renderTransactionHeader1());

            // The seventh and last item is FILLER PIC X(16) VALUE '        Amount' - fourteen
            // characters with exactly eight leading spaces, in a sixteen-wide item. Those eight
            // spaces are why the heading looks right: they push 'Amount' onto 1-based columns 107-112,
            // so its last character sits on the last column of the amount window the 97-invariant
            // establishes. Trim them and the heading floats away from the numbers it labels.
            assertThat(record.substring(98, 106)).isEqualTo(" ".repeat(8));
            assertThat(record.substring(106, 112)).isEqualTo("Amount");
            assertThat(record.indexOf("Amount") + "Amount".length())
                    .as("the 0-based offset just past 'Amount' is its 1-based end column")
                    .isEqualTo(TranReportLayouts.AMOUNT_COLUMN_END);
            assertThat(TranReportLayouts.HEADER_1_AMOUNT_LEADING_SPACES).isEqualTo(8);
            assertThat(TranReportLayouts.HEADER_1_AMOUNT_WORD_COLUMN_START).isEqualTo(107);
            assertThat(record.substring(112))
                    .as("the item's own two trailing spaces plus this writer's 19 pad spaces")
                    .isEqualTo(" ".repeat(21));
        }
    }

    // =================================================================================================
    // The two edit masks. PIC -ZZZ,ZZZ,ZZZ.ZZ and PIC +ZZZ,ZZZ,ZZZ.ZZ, app/cpy/CVTRA07Y.cpy.
    // =================================================================================================

    @Nested
    @DisplayName("The edit masks - byte-exact, truncating DOWN, and proof against every locale")
    class EditMasks {

        @ParameterizedTest(name = "{1} -> \"{2}\" ({0})")
        @MethodSource("com.vsergeychik.carddemo.transaction.TranReportWriterTest#detailAmountCases")
        @DisplayName("PIC -ZZZ,ZZZ,ZZZ.ZZ renders 15 bytes and they land on columns 98-112")
        void theDetailMaskIsByteExact(String rule, BigDecimal value, String expected) {
            assertThat(expected).as("the expectation is itself 15 characters").hasSize(15);

            TranReportLayouts layouts = populatedLayouts();
            layouts.moveTranReportAmt(value);
            String record = emitted(layouts.renderTransactionDetailReport());

            assertThat(record).hasSize(LRECL);
            assertThat(record.substring(97, 112)).as(rule).hasSize(15).isEqualTo(expected);
            assertThat(TranReportLayouts.editDetailAmount(value)).as(rule).isEqualTo(expected);
        }

        @ParameterizedTest(name = "{1} -> \"{2}\" ({0})")
        @MethodSource("com.vsergeychik.carddemo.transaction.TranReportWriterTest#totalAmountCases")
        @DisplayName("PIC +ZZZ,ZZZ,ZZZ.ZZ renders 15 bytes on all three total lines alike")
        void theTotalMaskIsByteExact(String rule, BigDecimal value, String expected) {
            assertThat(expected).as("the expectation is itself 15 characters").hasSize(15);

            TranReportLayouts layouts = new TranReportLayouts(ASCII);
            layouts.moveReptPageTotal(value);
            layouts.moveReptAccountTotal(value);
            layouts.moveReptGrandTotal(value);

            assertThat(emitted(layouts.renderReportPageTotals()).substring(97, 112))
                    .as("REPT-PAGE-TOTAL: %s", rule).isEqualTo(expected);
            assertThat(emitted(layouts.renderReportAccountTotals()).substring(97, 112))
                    .as("REPT-ACCOUNT-TOTAL: %s", rule).isEqualTo(expected);
            assertThat(emitted(layouts.renderReportGrandTotals()).substring(97, 112))
                    .as("REPT-GRAND-TOTAL: %s", rule).isEqualTo(expected);
            assertThat(TranReportLayouts.editTotalAmount(value)).as(rule).isEqualTo(expected);
        }

        @Test
        @DisplayName("the sign is a fixed insertion in position 1: '-' only for negatives, '+' always")
        void theSignIsFixedInPositionOne() {
            assertThat(TranReportLayouts.DETAIL_AMOUNT_MASK).isEqualTo("-ZZZ,ZZZ,ZZZ.ZZ").hasSize(15);
            assertThat(TranReportLayouts.TOTAL_AMOUNT_MASK).isEqualTo("+ZZZ,ZZZ,ZZZ.ZZ").hasSize(15);

            // A '-' insertion prints the minus for a negative value and a SPACE otherwise; a '+'
            // insertion prints a sign either way. Both occupy character position 1 of the item, which
            // is why both masks are 15 bytes and not 14.
            assertThat(TranReportLayouts.editDetailAmount(new BigDecimal("1.00")).charAt(0))
                    .isEqualTo(' ');
            assertThat(TranReportLayouts.editDetailAmount(new BigDecimal("-1.00")).charAt(0))
                    .isEqualTo('-');
            assertThat(TranReportLayouts.editTotalAmount(new BigDecimal("1.00")).charAt(0))
                    .isEqualTo('+');
            assertThat(TranReportLayouts.editTotalAmount(new BigDecimal("-1.00")).charAt(0))
                    .isEqualTo('-');
        }

        @Test
        @DisplayName("Z suppression writes spaces, and a comma inside the suppressed run goes too")
        void suppressionWritesSpacesIncludingTheCommas() {
            String hundred = emitted(pageTotalOf(new BigDecimal("100.00"))).substring(97, 112);

            // 1 + 86 characters of label and leader precede this, so these are 1-based columns 98-112.
            assertThat(hundred).isEqualTo("+        100.00");
            assertThat(hundred.charAt(4)).as("the first comma, left of the first digit").isEqualTo(' ');
            assertThat(hundred.charAt(8)).as("the second comma, also suppressed").isEqualTo(' ');
            assertThat(hundred.substring(1, 9)).as("eight suppressed positions").isEqualTo(" ".repeat(8));

            String million = emitted(pageTotalOf(new BigDecimal("1000000.00"))).substring(97, 112);

            assertThat(million).isEqualTo("+  1,000,000.00");
            assertThat(million.charAt(4)).as("a comma at or right of the first digit prints")
                    .isEqualTo(',');
            assertThat(million.charAt(8)).isEqualTo(',');
            assertThat(million.charAt(12)).as("the decimal point always prints").isEqualTo('.');
        }

        @Test
        @DisplayName("the all-Z zero rule: a zero amount blanks all 15 bytes, not '+0.00' and not 0")
        void aZeroAmountBlanksTheWholeItem() {
            // Every one of the eleven digit positions in both masks is Z, and COBOL blanks the entire
            // item when the sending value is zero. This is reachable in the real report rather than
            // synthetic: 1110-WRITE-PAGE-TOTALS does MOVE 0 TO WS-PAGE-TOTAL immediately after writing
            // its line (app/cbl/CBTRN03C.cbl:L297), so a page carrying no qualifying transaction
            // prints a blank page total. It is also the single most commonly mis-implemented COBOL
            // editing rule.
            for (BigDecimal zero : List.of(BigDecimal.ZERO, new BigDecimal("0.00"),
                    new BigDecimal("-0.00"), new BigDecimal("0.004"), new BigDecimal("-0.009"))) {
                assertThat(TranReportLayouts.editTotalAmount(zero))
                        .as("+ZZZ,ZZZ,ZZZ.ZZ applied to %s", zero)
                        .isEqualTo(" ".repeat(15));
                assertThat(TranReportLayouts.editDetailAmount(zero))
                        .as("-ZZZ,ZZZ,ZZZ.ZZ applied to %s", zero)
                        .isEqualTo(" ".repeat(15));
            }

            String record = emitted(pageTotalOf(new BigDecimal("0.00")));

            assertThat(record.substring(97, 112)).isEqualTo(" ".repeat(15));
            assertThat(record.substring(11, 97))
                    .as("the dot leader still prints - only the amount blanks")
                    .isEqualTo(".".repeat(86));
            assertThat(record).hasSize(LRECL);
        }

        @Test
        @DisplayName("no default locale can change the bytes - the mask is placed character by character")
        void theMaskIsLocaleIndependent() {
            BigDecimal value = new BigDecimal("1234567.89");
            Locale original = Locale.getDefault();
            try {
                // In de-DE and fr-FR the grouping separator is not ',' and the decimal separator is
                // not '.', so String.format("%,.2f"), DecimalFormat and NumberFormat would all render
                // this value differently on a machine configured that way - and none of them
                // implements Z suppression or the all-Z zero rule in the first place. The mask is
                // therefore placed character by character, and this asserts that it is: the emitted
                // bytes are identical under every default locale (practices B7 and B8).
                for (Locale locale : List.of(Locale.ROOT, Locale.US, Locale.GERMANY, Locale.FRANCE,
                        Locale.forLanguageTag("ar-EG"), Locale.forLanguageTag("hi-IN-u-nu-deva"))) {
                    Locale.setDefault(locale);

                    String edited = TranReportLayouts.editTotalAmount(value);
                    String record = emitted(pageTotalOf(value));

                    assertThat(edited).as("under default locale %s", locale)
                            .isEqualTo("+  1,234,567.89");
                    assertThat(record.substring(97, 112)).as("under default locale %s", locale)
                            .isEqualTo("+  1,234,567.89");
                    assertThat(edited.charAt(4)).as("grouping separator under %s", locale)
                            .isEqualTo(',');
                    assertThat(edited.charAt(12)).as("decimal separator under %s", locale)
                            .isEqualTo('.');
                    assertThat(edited.chars().allMatch(character -> character < 0x80))
                            .as("no locale's native digits reach the record under %s", locale)
                            .isTrue();
                }
            } finally {
                Locale.setDefault(original);
            }
        }

        @Test
        @DisplayName("neither collaborator holds a java.text formatter, so no locale seam exists")
        void noLocaleSensitiveFormatterIsHeld() {
            List<String> formatters = new ArrayList<>();
            for (Class<?> type : List.of(TranReportWriter.class, ReportFile.class,
                    TranReportLayouts.class)) {
                for (Field field : type.getDeclaredFields()) {
                    if (field.getType().getName().startsWith("java.text")
                            || field.getType().getName().equals("java.util.Locale")) {
                        formatters.add(type.getSimpleName() + "." + field.getName() + " : "
                                + field.getType().getName());
                    }
                }
            }

            assertThat(formatters)
                    .as("a DecimalFormat, NumberFormat or Locale field would be a seam through which "
                            + "a machine's configuration could reach the report's bytes")
                    .isEmpty();
        }

        @Test
        @DisplayName("the two '-'-valued FILLERs land on 1-based columns 32 and 53 (G21)")
        void theDetailSeparatorsAreHyphens() {
            byte[] record = emittedBytes(populatedLayouts().renderTransactionDetailReport());

            assertThat(record).hasSize(LRECL);
            // FILLER PIC X(01) VALUE '-' follows TRAN-REPORT-TYPE-CD PIC X(02), which follows
            // 16 + 1 + 11 + 1 = 29 bytes: so the separator is 0-based offset 31, 1-based column 32.
            assertThat(record[31])
                    .as("FILLER PIC X(01) VALUE '-' after TRAN-REPORT-TYPE-CD, 1-based column 32")
                    .isEqualTo((byte) '-');
            // And FILLER PIC X(01) VALUE '-' follows TRAN-REPORT-CAT-CD PIC 9(04) at 48-51, so it is
            // 0-based offset 52, 1-based column 53.
            assertThat(record[52])
                    .as("FILLER PIC X(01) VALUE '-' after TRAN-REPORT-CAT-CD, 1-based column 53")
                    .isEqualTo((byte) '-');
            assertThat(TranReportLayouts.DETAIL_SEPARATOR_VALUE).isEqualTo("-");

            // The other four single-byte FILLERs on this line declare VALUE SPACES, and emitting a
            // hyphen there would be the same defect in the other direction. A FILLER emits exactly
            // the VALUE it declares - which is what gate G21 says and why the total width holds.
            for (int spaceFiller : new int[] {16, 28, 47, 82}) {
                assertThat(record[spaceFiller])
                        .as("FILLER PIC X(01) VALUE SPACES at 0-based offset %d", spaceFiller)
                        .isEqualTo((byte) ' ');
            }
        }

        @Test
        @DisplayName("INITIALIZE leaves both separators alone, because FILLER is not a receiving item")
        void initializeLeavesTheSeparatorsInPlace() {
            TranReportLayouts layouts = new TranReportLayouts(ASCII);
            layouts.initializeTransactionDetailReport();

            byte[] record = emittedBytes(layouts.renderTransactionDetailReport());

            // INITIALIZE TRANSACTION-DETAIL-REPORT (app/cbl/CBTRN03C.cbl:L362) runs immediately before
            // the eight detail moves on every detail line. With no FILLER phrase written, a FILLER is
            // not a receiving operand, so both '-' separators survive it - and a Java equivalent that
            // blanked the whole record area first would silently lose them on every line.
            assertThat(record[31]).isEqualTo((byte) '-');
            assertThat(record[52]).isEqualTo((byte) '-');
            assertThat(record).hasSize(LRECL);
        }
    }

    // =================================================================================================
    // Right-truncation of the two X(50) descriptions. CBTRN03C:366 and CBTRN03C:368.
    // =================================================================================================

    @Nested
    @DisplayName("The two X(50) descriptions truncate on the RIGHT (CBTRN03C:L366, L368)")
    class DescriptionTruncation {

        /**
         * Fifty distinguishable characters, so which end was kept is unambiguous.
         *
         * <p>{@code TRAN-TYPE-DESC} of {@code app/cpy/CVTRA03Y.cpy} and {@code TRAN-CAT-TYPE-DESC} of
         * {@code app/cpy/CVTRA04Y.cpy} are both {@code PIC X(50)}, so a real sender is exactly this
         * wide and both receivers are narrower.
         */
        private static final String FIFTY = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwx";

        @Test
        @DisplayName("TRAN-TYPE-DESC X(50) -> X(15): the leading fifteen survive, the other 35 go")
        void theTypeDescriptionKeepsItsLeadingFifteen() {
            assertThat(FIFTY).as("the sender is a full PIC X(50)").hasSize(50);

            TranReportLayouts layouts = populatedLayouts();
            layouts.moveTranReportTypeDesc(FIFTY);
            String record = emitted(layouts.renderTransactionDetailReport());

            assertThat(record.substring(32, 47))
                    .as("TRAN-REPORT-TYPE-DESC PIC X(15), 1-based columns 33-47")
                    .hasSize(15)
                    .isEqualTo(FIFTY.substring(0, 15))
                    .isEqualTo("ABCDEFGHIJKLMNO");
            assertThat(record)
                    .as("the discarded 35 characters reach the dataset nowhere")
                    .doesNotContain("PQRST");
            assertThat(TranReportLayouts.TRAN_REPORT_TYPE_DESC_LENGTH).isEqualTo(15);
            assertThat(TranReportLayouts.TRAN_REPORT_TYPE_DESC_OFFSET).isEqualTo(32);
        }

        @Test
        @DisplayName("TRAN-CAT-TYPE-DESC X(50) -> X(29): the leading twenty-nine survive")
        void theCategoryDescriptionKeepsItsLeadingTwentyNine() {
            TranReportLayouts layouts = populatedLayouts();
            layouts.moveTranReportCatDesc(FIFTY);
            String record = emitted(layouts.renderTransactionDetailReport());

            assertThat(record.substring(53, 82))
                    .as("TRAN-REPORT-CAT-DESC PIC X(29), 1-based columns 54-82")
                    .hasSize(29)
                    .isEqualTo(FIFTY.substring(0, 29))
                    .isEqualTo("ABCDEFGHIJKLMNOPQRSTUVWXYZabc");
            assertThat(record).doesNotContain("defghi");
            assertThat(TranReportLayouts.TRAN_REPORT_CAT_DESC_LENGTH).isEqualTo(29);
            assertThat(TranReportLayouts.TRAN_REPORT_CAT_DESC_OFFSET).isEqualTo(53);
        }

        @Test
        @DisplayName("truncation is on the right, which is the opposite of the PIC 9 rule")
        void truncationIsOnTheRightForAlphanumerics() {
            TranReportLayouts layouts = populatedLayouts();
            layouts.moveTranReportTypeDesc(FIFTY);
            layouts.moveTranReportCatDesc(FIFTY);
            String record = emitted(layouts.renderTransactionDetailReport());

            // A COBOL alphanumeric MOVE aligns the sender left in the receiver and discards whatever
            // does not fit off the RIGHT. A numeric MOVE aligns on the decimal point and discards off
            // the LEFT. Getting the direction backwards yields a record of exactly the right width
            // carrying exactly the wrong characters.
            assertThat(record.substring(32, 47)).isNotEqualTo(FIFTY.substring(35));
            assertThat(record.substring(32, 47)).doesNotEndWith("uvwx");
            assertThat(record.substring(53, 82)).isNotEqualTo(FIFTY.substring(21));
            assertThat(record.substring(53, 82)).doesNotEndWith("uvwx");
        }

        @Test
        @DisplayName("a shorter description is space-padded to its declared width, never left ragged")
        void aShorterDescriptionIsSpacePadded() {
            TranReportLayouts layouts = populatedLayouts();
            layouts.moveTranReportTypeDesc("Purchase");
            layouts.moveTranReportCatDesc("Regular Sales Draft");
            String record = emitted(layouts.renderTransactionDetailReport());

            assertThat(record.substring(32, 47)).isEqualTo("Purchase" + " ".repeat(7));
            assertThat(record.substring(53, 82))
                    .isEqualTo("Regular Sales Draft" + " ".repeat(10));
            // The neighbours must be untouched by that padding, or the offsets have drifted.
            assertThat(record.charAt(31)).isEqualTo('-');
            assertThat(record.charAt(47)).isEqualTo(' ');
            assertThat(record.charAt(52)).isEqualTo('-');
            assertThat(record.charAt(82)).isEqualTo(' ');
            assertThat(record).hasSize(LRECL);
        }
    }

    // =================================================================================================
    // What belongs to the caller. WS-LINE-COUNTER and WS-PAGE-SIZE are CBTRN03C WORKING-STORAGE.
    // =================================================================================================

    @Nested
    @DisplayName("What belongs to the report job, not to this writer")
    class NotThisWritersJob {

        @Test
        @DisplayName("TranReportLayouts renders at natural width - 133 lives in this class alone")
        void theLayoutsAreNotPrePaddedToTheRecordWidth() {
            TranReportLayouts layouts = populatedLayouts();

            assertThat(layouts.renderReportNameHeader()).hasSize(115);
            assertThat(layouts.renderTransactionHeader1()).hasSize(114);
            assertThat(layouts.renderTransactionDetailReport()).hasSize(114);
            assertThat(layouts.renderReportPageTotals()).hasSize(112);
            assertThat(layouts.renderReportAccountTotals()).hasSize(112);
            assertThat(layouts.renderReportGrandTotals()).hasSize(112);
            // The one layout that is already 133 is elementary rather than a group:
            // 01 TRANSACTION-HEADER-2 PIC X(133) VALUE ALL '-'. It is 133 because the copybook says
            // so, not because anything padded it.
            assertThat(layouts.renderTransactionHeader2()).hasSize(133);
            assertThat(TranReportLayouts.TRANSACTION_HEADER_2_LENGTH).isEqualTo(LRECL);

            assertThat(List.of(TranReportLayouts.REPORT_NAME_HEADER_LENGTH,
                            TranReportLayouts.TRANSACTION_HEADER_1_LENGTH,
                            TranReportLayouts.TRANSACTION_DETAIL_REPORT_LENGTH,
                            TranReportLayouts.REPORT_PAGE_TOTALS_LENGTH,
                            TranReportLayouts.REPORT_ACCOUNT_TOTALS_LENGTH,
                            TranReportLayouts.REPORT_GRAND_TOTALS_LENGTH))
                    .as("no group layout is pre-padded to the record width; normalising is this "
                            + "writer's job and happens in exactly one place")
                    .doesNotContain(LRECL);
        }

        @Test
        @DisplayName("writing moves no line counter - a second handle from the same bean starts at 0")
        void writingMovesNoLineCounter() {
            TranReportWriter subject = writer();

            Collector first = new Collector();
            try (ReportFile file = subject.openOutput(first)) {
                file.writeLine(TranReportWriter.WS_BLANK_LINE_IMAGE);
                file.writeLine(TranReportWriter.WS_BLANK_LINE_IMAGE);
                assertThat(file.recordsWritten()).isEqualTo(2);
            }

            Collector second = new Collector();
            try (ReportFile file = subject.openOutput(second)) {
                // WS-LINE-COUNTER PIC 9(09) COMP-3 and WS-PAGE-SIZE PIC 9(03) COMP-3 VALUE 20 are
                // CBTRN03C's own WORKING-STORAGE (app/cbl/CBTRN03C.cbl:L129-L132), and the calling
                // paragraphs increment the counter at their own points - four times in
                // 1120-WRITE-HEADERS, twice in each of the two page/account total paragraphs and NOT
                // AT ALL in 1110-WRITE-GRAND-TOTALS. A counter kept here would have to guess which
                // caller it was serving. Pagination, page breaks and header re-emission therefore
                // belong to TransactionReportJob and are asserted in TransactionReportJobTest.
                assertThat(file.recordsWritten())
                        .as("the bean carried no count forward from the previous run")
                        .isZero();
                assertThat(file.reportRecord()).isEqualTo(" ".repeat(LRECL));
            }

            assertThat(subject.recordLength()).isEqualTo(LRECL);
            assertThat(subject.datasetCharset()).isEqualTo(ASCII);
            assertThat(subject.datasetBinding().recordLength()).isEqualTo(LRECL);
        }

        @Test
        @DisplayName("the only number either type owns is recordsWritten - no counter, no page size")
        void theOnlyTallyIsRecordsWritten() {
            // WS-LINE-COUNTER PIC 9(09) COMP-3 and WS-PAGE-SIZE PIC 9(03) COMP-3 VALUE 20 are
            // CBTRN03C's own WORKING-STORAGE (app/cbl/CBTRN03C.cbl:L129-L132). Neither has a
            // counterpart here, and the structural way to say so is to enumerate the state that could
            // hold one: the bean owns no number at all, and a handle owns exactly one - the handover
            // tally, which paginates nothing and is reset by being a new handle rather than by a MOVE.
            assertThat(numericFieldNames(TranReportWriter.class))
                    .as("the writer bean holds no number of its own, so it cannot be counting lines")
                    .isEmpty();
            assertThat(numericFieldNames(ReportFile.class))
                    .as("a handle's only number is its handover tally")
                    .containsExactly("recordsWritten");
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
