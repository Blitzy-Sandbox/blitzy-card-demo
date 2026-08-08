package com.vsergeychik.carddemo.statement;

import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.DatasetRelation;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.statement.StatementTextWriter.RecordSink;
import com.vsergeychik.carddemo.statement.StatementTextWriter.SlotKind;
import com.vsergeychik.carddemo.statement.StatementTextWriter.StatementFile;
import com.vsergeychik.carddemo.statement.StatementTextWriter.StatementLine;
import com.vsergeychik.carddemo.statement.StatementTextWriter.StatementSlot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
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
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link StatementTextWriter}, the 80-byte {@code STMTFILE} statement record writer.
 *
 * <p>Plain JUnit 5 throughout: no application context, no {@code JobLauncher}, no filesystem and no
 * database. Every record is collected by an in-memory {@link RecordSink}, which is the seam the class
 * under test exposes precisely so that this is possible (practice B10, gate G51). The one place a
 * database type appears at all is {@link JdbcSinkTests}, where a mocked
 * {@link java.sql.PreparedStatement} is used to assert that the record image is bound as
 * <em>bytes</em> - and even there nothing is connected to anything.
 *
 * <h2>Every expectation is transcribed from the COBOL, not from the implementation</h2>
 * The expected images below were written by reading {@code app/cbl/CBSTM03A.CBL:L85-L146} and counting
 * {@code PIC} widths, and the two mask expectations by applying IBM Enterprise COBOL's editing rules
 * to the declared pictures. That is what makes them an audit rather than a restatement: if the
 * implementation and these strings agree, they agree with the copybook.
 *
 * <h2>The gates asserted here</h2>
 * <ul>
 *   <li><strong>G19, G20</strong> - every rendered line is exactly 80 bytes, in both the freshly
 *       reset and the fully populated state, and the configured record length is cross-checked at
 *       construction.</li>
 *   <li><strong>G21</strong> - {@code FILLER} survives {@code INITIALIZE}. This is the assertion that
 *       catches the highest-risk misreading in the class: implementing the reset as a whole-buffer
 *       blank would still leave every line 80 bytes long, so only a byte-level check finds it.</li>
 *   <li><strong>G22, G23, G24</strong> - the masks take {@link BigDecimal} and truncate; a value that
 *       overflows nine integer digits loses its high-order digit rather than being rounded or
 *       rejected.</li>
 *   <li><strong>G46</strong> - the dataset name reaches the insert statement from configuration; the
 *       tests supply their own, and no mainframe dataset literal is needed by the class.</li>
 *   <li><strong>G53</strong> - {@link StructuralTests#everyStaticFieldIsFinal()} walks the class and
 *       its nested types by reflection and asserts there is no mutable static state.</li>
 * </ul>
 */
@DisplayName("StatementTextWriter - the 80-byte STMTFILE plain-text statement record")
class StatementTextWriterTest {

    /** The code page every test names explicitly; never a platform default. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** The EBCDIC code page, used to prove the encoding is genuinely the injected one. */
    private static final Charset EBCDIC = Charset.forName("IBM037");

    /**
     * A stand-in dataset name. The real one lives only in {@code application.yml}.
     *
     * <p>Well formed as z/OS requires: four qualifiers of at most eight characters each, every one
     * beginning with a letter. The earlier stand-in here spelled out {@code STATEMENT}, which is nine
     * characters and so is not a qualifier a mainframe would accept - a reminder that a stand-in still
     * has to obey the grammar it stands in for.
     */
    private static final String TEST_DSNAME = "TEST.M2.STATEMNT.PS";

    /** The declared record width, restated here from the COBOL rather than read from the class. */
    private static final int EIGHTY = 80;

    /** The declared width of both edited pictures, restated from the COBOL. */
    private static final int THIRTEEN = 13;

    /**
     * Builds the {@code STMTFILE} binding a test uses, with the record length under the test's
     * control so the constructor's cross-check can be driven both ways.
     *
     * @param recordLength the record length to configure
     * @return a catalogue containing exactly that one binding
     */
    private static DatasetBindings bindings(int recordLength) {
        DatasetBindings catalogue = new DatasetBindings();
        catalogue.put("STMTFILE", new DatasetBinding(TEST_DSNAME, "sequential", false, "FB", 8000,
                recordLength, null, null, null, null, null));
        return catalogue;
    }

    /**
     * A writer over the given code page with a correctly configured 80-byte binding.
     *
     * @param charset the dataset code page, named explicitly
     * @return the writer
     */
    private static StatementTextWriter writer(Charset charset) {
        return new StatementTextWriter(new JdbcTemplate(), charset, bindings(EIGHTY));
    }

    /** A writer over {@link #ASCII}. */
    private static StatementTextWriter writer() {
        return writer(ASCII);
    }

    /**
     * A correctly configured 80-byte writer whose {@code STMTFILE} binding names an arbitrary
     * location, so the dataset-name checks can be driven over every shape a deployment might supply.
     *
     * @param dsname the configured name, well formed or not, possibly {@code null}
     * @return the writer, which construction always succeeds for - see
     *         {@link JdbcSinkTests#stillConstructsUnderAFixtureBackedBinding()}
     */
    private static StatementTextWriter writerBoundTo(String dsname) {
        DatasetBindings catalogue = new DatasetBindings();
        catalogue.put("STMTFILE", new DatasetBinding(dsname, "sequential", false, "FB", 8000,
                EIGHTY, null, null, null, null, null));
        return new StatementTextWriter(new JdbcTemplate(), ASCII, catalogue);
    }

    /**
     * An in-memory {@link RecordSink}: the whole reason the sink is an interface.
     *
     * <p>Records are appended in the order they arrive, so a test can assert emission order as well
     * as content, and the configured close outcome is settable so the close-reporting path can be
     * driven both ways.
     */
    private static final class CollectingSink implements RecordSink {

        /** Every record handed over, in call order. */
        private final List<byte[]> records = new ArrayList<>();

        /** What {@link #write(byte[])} reports. */
        private final FileStatus.Outcome writeOutcome;

        /** What {@link #close()} reports. */
        private final FileStatus.Outcome closeOutcome;

        /** How many times {@link #close()} was called, so idempotency can be asserted. */
        private int closeCalls;

        CollectingSink() {
            this(FileStatus.Outcome.OK, FileStatus.Outcome.OK);
        }

        CollectingSink(FileStatus.Outcome writeOutcome, FileStatus.Outcome closeOutcome) {
            this.writeOutcome = writeOutcome;
            this.closeOutcome = closeOutcome;
        }

        @Override
        public FileStatus.Outcome write(byte[] recordImage) {
            records.add(recordImage);
            return writeOutcome;
        }

        @Override
        public FileStatus.Outcome close() {
            closeCalls++;
            return closeOutcome;
        }

        /**
         * The collected records decoded under a named code page.
         *
         * @param charset the code page to decode with
         * @return one string per record, in emission order
         */
        List<String> decoded(Charset charset) {
            List<String> images = new ArrayList<>(records.size());
            for (byte[] record : records) {
                images.add(new String(record, charset));
            }
            return images;
        }
    }

    /**
     * Populates all eleven slots with values chosen to exercise every {@code MOVE} rule at once: an
     * under-width name, equal-width address lines, an account id needing an eleven-digit zero fill, a
     * balance needing high-order truncation, a three-digit score, an over-width description and both
     * amount signs.
     *
     * @param file the handle to populate
     */
    private static void populate(StatementFile file) {
        file.setName("JOHN Q PUBLIC");
        file.setAddressLine1("100 MAIN STREET");
        file.setAddressLine2("APT 3B");
        file.setAddressLine3("SPRINGFIELD IL USA 62701");
        file.setAccountId(1L);
        file.setCurrentBalance(new BigDecimal("9999999999.99"));
        file.setFicoScore(705);
        file.setTransactionId("0000000000000001");
        file.setTransactionDetails("D".repeat(100));
        file.setTransactionAmount(new BigDecimal("-919.00"));
        file.setTotalTransactionAmount(new BigDecimal("0.41"));
    }

    // =============================================================================================

    @Nested
    @DisplayName("Record geometry - 80 bytes per line, 17 lines, 1360 bytes of group")
    class GeometryTests {

        @Test
        @DisplayName("declares the 17 line groups of 01 STATEMENT-LINES, ST-LINE14A included")
        void declaresSeventeenLines() {
            assertThat(StatementLine.values()).hasSize(17);
            assertThat(StatementTextWriter.LINE_COUNT).isEqualTo(17);
            assertThat(StatementLine.valueOf("ST_LINE14A").cobolName()).isEqualTo("ST-LINE14A");
            assertThat(StatementLine.ST_LINE14A.ordinal())
                    .isEqualTo(StatementLine.ST_LINE14.ordinal() + 1);
            assertThat(StatementLine.ST_LINE15.ordinal())
                    .isEqualTo(StatementLine.ST_LINE14A.ordinal() + 1);
        }

        @Test
        @DisplayName("carries the widths the COBOL and the creating JCL step both declare")
        void carriesTheDeclaredWidths() {
            assertThat(StatementTextWriter.RECORD_LENGTH).isEqualTo(EIGHTY);
            assertThat(StatementTextWriter.BLOCK_SIZE).isEqualTo(8000);
            assertThat(StatementTextWriter.DD_NAME).isEqualTo("STMTFILE");
            assertThat(StatementTextWriter.EDITED_AMOUNT_LENGTH).isEqualTo(THIRTEEN);
            assertThat(StatementTextWriter.STATEMENT_LINES_LENGTH).isEqualTo(17 * EIGHTY);
            assertThat(writer().recordLength()).isEqualTo(EIGHTY);
        }

        @ParameterizedTest
        @EnumSource(StatementLine.class)
        @DisplayName("places every line at ordinal * 80 and gives it a width of 80")
        void placesEveryLineAtItsOrdinalOffset(StatementLine line) {
            assertThat(line.offset()).isEqualTo(line.ordinal() * EIGHTY);
            assertThat(line.length()).isEqualTo(EIGHTY);
            assertThat(line.cobolName()).isEqualTo(line.name().replace("ST_LINE", "ST-LINE"));
        }

        @ParameterizedTest
        @EnumSource(StatementLine.class)
        @DisplayName("renders every line as exactly 80 characters and 80 bytes when freshly reset")
        void rendersEveryResetLineAtEightyBytes(StatementLine line) {
            StatementFile file = writer().openOutput(new CollectingSink());
            assertThat(file.renderLine(line)).hasSize(EIGHTY);
            assertThat(file.renderLineBytes(line)).hasSize(EIGHTY);
        }

        @ParameterizedTest
        @EnumSource(StatementLine.class)
        @DisplayName("renders every line as exactly 80 characters and 80 bytes when fully populated")
        void rendersEveryPopulatedLineAtEightyBytes(StatementLine line) {
            StatementFile file = writer().openOutput(new CollectingSink());
            populate(file);
            assertThat(file.renderLine(line)).hasSize(EIGHTY);
            assertThat(file.renderLineBytes(line)).hasSize(EIGHTY);
        }

        @ParameterizedTest
        @EnumSource(StatementSlot.class)
        @DisplayName("places every slot inside its own line, at its line's offset plus its own")
        void placesEverySlotInsideItsLine(StatementSlot slot) {
            assertThat(slot.offset()).isEqualTo(slot.line().offset() + slot.offsetWithinLine());
            assertThat(slot.offsetWithinLine() + slot.length()).isLessThanOrEqualTo(EIGHTY);
            assertThat(slot.cobolName()).startsWith("ST-");
            assertThat(slot.kind()).isNotNull();
        }

        @Test
        @DisplayName("declares the eleven mutable slots at the offsets counted from the copybook")
        void declaresTheElevenSlotsAtTheirCountedOffsets() {
            assertThat(StatementSlot.values()).hasSize(11);

            assertThat(StatementSlot.ST_NAME.offset()).isEqualTo(80);
            assertThat(StatementSlot.ST_NAME.length()).isEqualTo(75);
            assertThat(StatementSlot.ST_ADD1.offset()).isEqualTo(160);
            assertThat(StatementSlot.ST_ADD2.offset()).isEqualTo(240);
            assertThat(StatementSlot.ST_ADD3.offset()).isEqualTo(320);
            assertThat(StatementSlot.ST_ADD3.length()).isEqualTo(EIGHTY);
            assertThat(StatementSlot.ST_ACCT_ID.offset()).isEqualTo(580);
            assertThat(StatementSlot.ST_CURR_BAL.offset()).isEqualTo(660);
            assertThat(StatementSlot.ST_CURR_BAL.length()).isEqualTo(THIRTEEN);
            assertThat(StatementSlot.ST_FICO_SCORE.offset()).isEqualTo(740);
            assertThat(StatementSlot.ST_TRANID.offset()).isEqualTo(1120);
            assertThat(StatementSlot.ST_TRANDT.offset()).isEqualTo(1137);
            assertThat(StatementSlot.ST_TRANDT.length()).isEqualTo(49);
            assertThat(StatementSlot.ST_TRANAMT.offset()).isEqualTo(1187);
            assertThat(StatementSlot.ST_TOTAL_TRAMT.offset()).isEqualTo(1267);

            // The amount column must line up between a detail line and the total line.
            assertThat(StatementSlot.ST_TOTAL_TRAMT.offsetWithinLine())
                    .isEqualTo(StatementSlot.ST_TRANAMT.offsetWithinLine());
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("INITIALIZE STATEMENT-LINES - clears the eleven slots and no FILLER byte")
    class InitializeTests {

        @Test
        @DisplayName("leaves ST-LINE0's banner intact: 31 asterisks, the literal, 31 asterisks")
        void leavesTheOpeningBannerIntact() {
            StatementFile file = writer().openOutput(new CollectingSink());
            String image = file.renderLine(StatementLine.ST_LINE0);

            assertThat(image.substring(0, 31)).isEqualTo("*".repeat(31));
            assertThat(image.substring(31, 49)).isEqualTo("START OF STATEMENT");
            assertThat(image.substring(49)).isEqualTo("*".repeat(31));
        }

        @Test
        @DisplayName("leaves ST-LINE15's banner intact: 32 asterisks, the literal, 32 asterisks")
        void leavesTheClosingBannerIntact() {
            StatementFile file = writer().openOutput(new CollectingSink());
            String image = file.renderLine(StatementLine.ST_LINE15);

            assertThat(image.substring(0, 32)).isEqualTo("*".repeat(32));
            assertThat(image.substring(32, 48)).isEqualTo("END OF STATEMENT");
            assertThat(image.substring(48)).isEqualTo("*".repeat(32));
        }

        @Test
        @DisplayName("leaves all three ALL '-' rule lines as 80 hyphens")
        void leavesTheThreeRuleLinesIntact() {
            StatementFile file = writer().openOutput(new CollectingSink());
            String rule = "-".repeat(EIGHTY);

            assertThat(file.renderLine(StatementLine.ST_LINE5)).isEqualTo(rule);
            assertThat(file.renderLine(StatementLine.ST_LINE10)).isEqualTo(rule);
            assertThat(file.renderLine(StatementLine.ST_LINE12)).isEqualTo(rule);
        }

        @Test
        @DisplayName("leaves ST-LINE13's three headings at offsets 0, 16 and 67, padded as declared")
        void leavesTheColumnHeadingsIntact() {
            StatementFile file = writer().openOutput(new CollectingSink());
            String image = file.renderLine(StatementLine.ST_LINE13);

            // 'Tran ID         ' is 16 characters and exactly fills X(16).
            assertThat(image.substring(0, 16)).isEqualTo("Tran ID         ");
            // 'Tran Details    ' is 16 characters declared into X(51), so it is padded to 51.
            assertThat(image.substring(16, 67)).isEqualTo("Tran Details    " + " ".repeat(35));
            // '  Tran Amount' begins with TWO leading spaces and exactly fills X(13).
            assertThat(image.substring(67)).isEqualTo("  Tran Amount");
        }

        @Test
        @DisplayName("still shows every FILLER after a populate-then-reset cycle")
        void stillShowsEveryFillerAfterAResetCycle() {
            StatementFile file = writer().openOutput(new CollectingSink());
            populate(file);
            file.initializeStatementLines();

            // The FILLER content is back - or rather, was never touched.
            assertThat(file.renderLine(StatementLine.ST_LINE0).substring(31, 49))
                    .isEqualTo("START OF STATEMENT");
            assertThat(file.renderLine(StatementLine.ST_LINE12)).isEqualTo("-".repeat(EIGHTY));
            assertThat(file.renderLine(StatementLine.ST_LINE13).substring(67))
                    .isEqualTo("  Tran Amount");
            assertThat(file.renderLine(StatementLine.ST_LINE7).substring(0, 20))
                    .isEqualTo("Account ID         :");
            assertThat(file.renderLine(StatementLine.ST_LINE14A).substring(0, 10))
                    .isEqualTo("Total EXP:");
            assertThat(file.renderLine(StatementLine.ST_LINE14).charAt(66)).isEqualTo('$');

            // And the previous customer's values are gone.
            assertThat(file.slotImage(StatementSlot.ST_NAME)).isEqualTo(" ".repeat(75));
            assertThat(file.slotImage(StatementSlot.ST_TRANDT)).isEqualTo(" ".repeat(49));
            assertThat(file.slotImage(StatementSlot.ST_ACCT_ID)).isEqualTo(" ".repeat(20));
        }

        @Test
        @DisplayName("clears an alphanumeric slot to spaces across its full declared width")
        void clearsAlphanumericSlotsToSpaces() {
            StatementFile file = writer().openOutput(new CollectingSink());

            assertThat(file.slotImage(StatementSlot.ST_NAME)).isEqualTo(" ".repeat(75));
            assertThat(file.slotImage(StatementSlot.ST_ADD1)).isEqualTo(" ".repeat(50));
            assertThat(file.slotImage(StatementSlot.ST_ADD2)).isEqualTo(" ".repeat(50));
            assertThat(file.slotImage(StatementSlot.ST_ADD3)).isEqualTo(" ".repeat(EIGHTY));
            assertThat(file.slotImage(StatementSlot.ST_ACCT_ID)).isEqualTo(" ".repeat(20));
            assertThat(file.slotImage(StatementSlot.ST_FICO_SCORE)).isEqualTo(" ".repeat(20));
            assertThat(file.slotImage(StatementSlot.ST_TRANID)).isEqualTo(" ".repeat(16));
            assertThat(file.slotImage(StatementSlot.ST_TRANDT)).isEqualTo(" ".repeat(49));
        }

        @Test
        @DisplayName("clears an edited slot to its picture's zero image, not to spaces or to zeros")
        void clearsEditedSlotsToTheirPictureZeroImage() {
            StatementFile file = writer().openOutput(new CollectingSink());

            // PIC 9(9).99- retains its leading zeros, so zero is a zero-filled image.
            assertThat(file.slotImage(StatementSlot.ST_CURR_BAL)).isEqualTo("000000000.00 ");
            // PIC Z(9).99- suppresses them, and declares no BLANK WHEN ZERO, so the fraction shows.
            assertThat(file.slotImage(StatementSlot.ST_TRANAMT)).isEqualTo("         .00 ");
            assertThat(file.slotImage(StatementSlot.ST_TOTAL_TRAMT)).isEqualTo("         .00 ");
        }

        @ParameterizedTest
        @EnumSource(SlotKind.class)
        @DisplayName("declares an initial image for every slot kind, and each kind is actually used")
        void everySlotKindIsUsedByAtLeastOneSlot(SlotKind kind) {
            assertThat(StatementSlot.values())
                    .anySatisfy(slot -> assertThat(slot.kind()).isEqualTo(kind));
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("Literal fidelity - every heading byte, including the awkward spaces")
    class LiteralTests {

        @Test
        @DisplayName("renders the three twenty-character labels exactly as declared")
        void rendersTheThreeLabelsExactly() {
            StatementFile file = writer().openOutput(new CollectingSink());

            assertThat(file.renderLine(StatementLine.ST_LINE7).substring(0, 20))
                    .isEqualTo("Account ID         :")
                    .hasSize(20);
            assertThat(file.renderLine(StatementLine.ST_LINE8).substring(0, 20))
                    .isEqualTo("Current Balance    :")
                    .hasSize(20);
            assertThat(file.renderLine(StatementLine.ST_LINE9).substring(0, 20))
                    .isEqualTo("FICO Score         :")
                    .hasSize(20);
        }

        @Test
        @DisplayName("pads 'Basic Details' from 13 characters to its declared X(14)")
        void padsTheBasicDetailsHeading() {
            StatementFile file = writer().openOutput(new CollectingSink());
            String image = file.renderLine(StatementLine.ST_LINE6);

            assertThat(image.substring(0, 33)).isEqualTo(" ".repeat(33));
            assertThat(image.substring(33, 47)).isEqualTo("Basic Details ");
            assertThat(image.substring(47)).isEqualTo(" ".repeat(33));
        }

        @Test
        @DisplayName("keeps the trailing space inside 'TRANSACTION SUMMARY '")
        void keepsTheTrailingSpaceInsideTheSummaryHeading() {
            StatementFile file = writer().openOutput(new CollectingSink());
            String image = file.renderLine(StatementLine.ST_LINE11);

            assertThat(image.substring(30, 50)).isEqualTo("TRANSACTION SUMMARY ").hasSize(20);
            assertThat(image.charAt(49)).isEqualTo(' ');
            assertThat(image.substring(0, 30)).isEqualTo(" ".repeat(30));
            assertThat(image.substring(50)).isEqualTo(" ".repeat(30));
        }

        @Test
        @DisplayName("renders ST-LINE14's one-space separator and dollar sign at 16 and 66")
        void rendersTheDetailLineSeparators() {
            StatementFile file = writer().openOutput(new CollectingSink());
            String image = file.renderLine(StatementLine.ST_LINE14);

            assertThat(image.charAt(16)).isEqualTo(' ');
            assertThat(image.charAt(66)).isEqualTo('$');
        }

        @Test
        @DisplayName("renders ST-LINE14A's 'Total EXP:' label, 56 spaces and dollar sign")
        void rendersTheTotalLine() {
            StatementFile file = writer().openOutput(new CollectingSink());
            String image = file.renderLine(StatementLine.ST_LINE14A);

            assertThat(image.substring(0, 10)).isEqualTo("Total EXP:");
            assertThat(image.substring(10, 66)).isEqualTo(" ".repeat(56));
            assertThat(image.charAt(66)).isEqualTo('$');
        }
    }

    // =============================================================================================

    /**
     * The two numeric-edited masks. Every expected image below is exactly 13 characters and was
     * written by applying the picture's editing rules by hand, which is what makes these assertions
     * an independent check rather than a copy of the implementation.
     */
    @Nested
    @DisplayName("Numeric-edited masks - PIC 9(9).99- and PIC Z(9).99-")
    class MaskTests {

        /**
         * Cases for {@code PIC 9(9).99-}: leading zeros retained, trailing sign position.
         *
         * @return value and expected image pairs
         */
        static Stream<Arguments> zeroFilledCases() {
            return Stream.of(
                    // Positive: the sign position is a SPACE, never a plus.
                    Arguments.of(new BigDecimal("194.00"), "000000194.00 "),
                    // Negative: the sign position is a minus, and the digits are the magnitude.
                    Arguments.of(new BigDecimal("-919.00"), "000000919.00-"),
                    // Zero: no BLANK WHEN ZERO is declared, so the zeros print.
                    Arguments.of(new BigDecimal("0.00"), "000000000.00 "),
                    // A sub-one value keeps all nine integer zeros.
                    Arguments.of(new BigDecimal("0.41"), "000000000.41 "),
                    // The largest value that fits nine integer digits.
                    Arguments.of(new BigDecimal("999999999.99"), "999999999.99 "),
                    // ACCT-CURR-BAL is S9(10)V99: the tenth integer digit is discarded.
                    Arguments.of(new BigDecimal("9999999999.99"), "999999999.99 "),
                    // Truncation keeps the LOW-order nine digits, not the high-order nine.
                    Arguments.of(new BigDecimal("1234567890.12"), "234567890.12 "),
                    // Negative and over-wide together: sign from the sender, digits truncated.
                    Arguments.of(new BigDecimal("-9999999999.99"), "999999999.99-"),
                    // Excess fraction digits are TRUNCATED, never rounded: .239 becomes .23.
                    Arguments.of(new BigDecimal("1.239"), "000000001.23 "),
                    Arguments.of(new BigDecimal("1.999"), "000000001.99 "),
                    // A scale-0 sender is padded up to two fraction digits.
                    Arguments.of(new BigDecimal("7"), "000000007.00 "));
        }

        /**
         * Cases for {@code PIC Z(9).99-}: leading zeros suppressed to spaces.
         *
         * @return value and expected image pairs
         */
        static Stream<Arguments> zeroSuppressedCases() {
            return Stream.of(
                    Arguments.of(new BigDecimal("194.00"), "      194.00 "),
                    Arguments.of(new BigDecimal("-919.00"), "      919.00-"),
                    // Zero suppresses all nine integer positions; the fraction still prints.
                    Arguments.of(new BigDecimal("0.00"), "         .00 "),
                    // Suppression stops at the DECIMAL POINT, so it never reaches the fraction.
                    Arguments.of(new BigDecimal("0.41"), "         .41 "),
                    // A single significant digit leaves eight spaces before it.
                    Arguments.of(new BigDecimal("1.00"), "        1.00 "),
                    // Nothing is suppressed when the leading position is significant.
                    Arguments.of(new BigDecimal("999999999.99"), "999999999.99 "),
                    Arguments.of(new BigDecimal("9999999999.99"), "999999999.99 "),
                    Arguments.of(new BigDecimal("1234567890.12"), "234567890.12 "),
                    Arguments.of(new BigDecimal("-9999999999.99"), "999999999.99-"),
                    Arguments.of(new BigDecimal("1.239"), "        1.23 "),
                    // Truncating away the whole integer part re-enables suppression of all nine.
                    Arguments.of(new BigDecimal("1000000000.00"), "         .00 "),
                    Arguments.of(new BigDecimal("7"), "        7.00 "));
        }

        @ParameterizedTest(name = "9(9).99- of {0} is [{1}]")
        @MethodSource("zeroFilledCases")
        @DisplayName("edits through PIC 9(9).99- with leading zeros retained")
        void editsWithLeadingZerosRetained(BigDecimal value, String expected) {
            assertThat(StatementTextWriter.editZeroFilledAmount(value))
                    .isEqualTo(expected)
                    .hasSize(THIRTEEN);
        }

        @ParameterizedTest(name = "Z(9).99- of {0} is [{1}]")
        @MethodSource("zeroSuppressedCases")
        @DisplayName("edits through PIC Z(9).99- with leading zeros suppressed to spaces")
        void editsWithLeadingZerosSuppressed(BigDecimal value, String expected) {
            assertThat(StatementTextWriter.editZeroSuppressedAmount(value))
                    .isEqualTo(expected)
                    .hasSize(THIRTEEN);
        }

        @Test
        @DisplayName("differs between the two masks only in the integer positions")
        void differsOnlyInTheIntegerPositions() {
            BigDecimal value = new BigDecimal("194.00");
            String retained = StatementTextWriter.editZeroFilledAmount(value);
            String suppressed = StatementTextWriter.editZeroSuppressedAmount(value);

            assertThat(retained.substring(9)).isEqualTo(suppressed.substring(9));
            assertThat(retained.substring(0, 9)).isEqualTo("000000194");
            assertThat(suppressed.substring(0, 9)).isEqualTo("      194");
        }

        @Test
        @DisplayName("renders CobolDecimal's scale-2 zero as each picture's own zero image")
        void rendersTheScaleTwoZero() {
            assertThat(CobolDecimal.monetaryZero().scale()).isEqualTo(2);
            assertThat(StatementTextWriter.editZeroFilledAmount(CobolDecimal.monetaryZero()))
                    .isEqualTo("000000000.00 ");
            assertThat(StatementTextWriter.editZeroSuppressedAmount(CobolDecimal.monetaryZero()))
                    .isEqualTo("         .00 ");
        }

        @Test
        @DisplayName("rejects a null value rather than inventing a zero")
        void rejectsANullValue() {
            assertThatNullPointerException()
                    .isThrownBy(() -> StatementTextWriter.editZeroFilledAmount(null))
                    .withMessageContaining("monetaryZero");
            assertThatNullPointerException()
                    .isThrownBy(() -> StatementTextWriter.editZeroSuppressedAmount(null));
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("Slot setters - COBOL MOVE semantics, never a Java assignment")
    class MoveSemanticsTests {

        @Test
        @DisplayName("left justifies a short name in X(75) and pads it on the right")
        void padsAShortNameOnTheRight() {
            StatementFile file = writer().openOutput(new CollectingSink());
            file.setName("JOHN Q PUBLIC");

            assertThat(file.slotImage(StatementSlot.ST_NAME))
                    .isEqualTo("JOHN Q PUBLIC" + " ".repeat(62))
                    .hasSize(75);
        }

        @Test
        @DisplayName("truncates an over-long name on the RIGHT, keeping the leading characters")
        void truncatesAnOverLongNameOnTheRight() {
            StatementFile file = writer().openOutput(new CollectingSink());
            file.setName("A".repeat(70) + "ZZZZZZZZZZ");

            assertThat(file.slotImage(StatementSlot.ST_NAME))
                    .isEqualTo("A".repeat(70) + "ZZZZZ")
                    .hasSize(75);
        }

        @Test
        @DisplayName("truncates a 100-character TRNX-DESC to the first 49 characters")
        void truncatesTheTransactionDescriptionToFortyNine() {
            StatementFile file = writer().openOutput(new CollectingSink());
            file.setTransactionDetails("A".repeat(49) + "B".repeat(51));

            assertThat(file.slotImage(StatementSlot.ST_TRANDT))
                    .isEqualTo("A".repeat(49))
                    .hasSize(49);
        }

        @Test
        @DisplayName("zero-fills ACCT-ID to eleven digits on the LEFT, then pads X(20) on the right")
        void zeroFillsTheAccountIdThenPadsIt() {
            StatementFile file = writer().openOutput(new CollectingSink());
            file.setAccountId(1L);

            assertThat(file.slotImage(StatementSlot.ST_ACCT_ID))
                    .isEqualTo("00000000001" + " ".repeat(9))
                    .hasSize(20);
        }

        @Test
        @DisplayName("keeps all eleven digits of a full-width account id")
        void keepsAFullWidthAccountId() {
            StatementFile file = writer().openOutput(new CollectingSink());
            file.setAccountId(99999999999L);

            assertThat(file.slotImage(StatementSlot.ST_ACCT_ID))
                    .isEqualTo("99999999999" + " ".repeat(9));
        }

        @Test
        @DisplayName("rejects a negative account id, because PIC 9(11) has no sign position")
        void rejectsANegativeAccountId() {
            StatementFile file = writer().openOutput(new CollectingSink());

            assertThatIllegalArgumentException().isThrownBy(() -> file.setAccountId(-1L));
        }

        @Test
        @DisplayName("zero-fills a FICO score to three digits, left justified in X(20)")
        void zeroFillsTheFicoScore() {
            StatementFile file = writer().openOutput(new CollectingSink());

            file.setFicoScore(705);
            assertThat(file.slotImage(StatementSlot.ST_FICO_SCORE))
                    .isEqualTo("705" + " ".repeat(17));

            file.setFicoScore(12);
            assertThat(file.slotImage(StatementSlot.ST_FICO_SCORE))
                    .isEqualTo("012" + " ".repeat(17));
        }

        @Test
        @DisplayName("rejects a negative FICO score, because PIC 9(03) has no sign position")
        void rejectsANegativeFicoScore() {
            StatementFile file = writer().openOutput(new CollectingSink());

            assertThatIllegalArgumentException().isThrownBy(() -> file.setFicoScore(-1));
        }

        @Test
        @DisplayName("moves the equal-width address lines through unchanged, padded to their width")
        void movesTheAddressLines() {
            StatementFile file = writer().openOutput(new CollectingSink());
            file.setAddressLine1("100 MAIN STREET");
            file.setAddressLine2("APT 3B");
            file.setAddressLine3("SPRINGFIELD IL USA 62701");
            file.setTransactionId("0000000000000001");

            assertThat(file.slotImage(StatementSlot.ST_ADD1))
                    .isEqualTo("100 MAIN STREET" + " ".repeat(35));
            assertThat(file.slotImage(StatementSlot.ST_ADD2))
                    .isEqualTo("APT 3B" + " ".repeat(44));
            assertThat(file.slotImage(StatementSlot.ST_ADD3))
                    .isEqualTo("SPRINGFIELD IL USA 62701" + " ".repeat(56));
            assertThat(file.slotImage(StatementSlot.ST_TRANID)).isEqualTo("0000000000000001");
        }

        @Test
        @DisplayName("writes each edited amount into its own slot without disturbing its line")
        void writesTheEditedAmountsIntoTheirSlots() {
            StatementFile file = writer().openOutput(new CollectingSink());
            file.setCurrentBalance(new BigDecimal("194.00"));
            file.setTransactionAmount(new BigDecimal("-919.00"));
            file.setTotalTransactionAmount(new BigDecimal("0.41"));

            assertThat(file.slotImage(StatementSlot.ST_CURR_BAL)).isEqualTo("000000194.00 ");
            assertThat(file.slotImage(StatementSlot.ST_TRANAMT)).isEqualTo("      919.00-");
            assertThat(file.slotImage(StatementSlot.ST_TOTAL_TRAMT)).isEqualTo("         .41 ");

            // The surrounding FILLER is untouched.
            assertThat(file.renderLine(StatementLine.ST_LINE8))
                    .isEqualTo("Current Balance    :000000194.00 " + " ".repeat(47));
            assertThat(file.renderLine(StatementLine.ST_LINE14A))
                    .isEqualTo("Total EXP:" + " ".repeat(56) + "$         .41 ");
        }

        @Test
        @DisplayName("renders a fully populated detail line byte for byte")
        void rendersAFullyPopulatedDetailLine() {
            StatementFile file = writer().openOutput(new CollectingSink());
            file.setTransactionId("0000000000000001");
            file.setTransactionDetails("D".repeat(100));
            file.setTransactionAmount(new BigDecimal("-919.00"));

            assertThat(file.renderLine(StatementLine.ST_LINE14))
                    .isEqualTo("0000000000000001 " + "D".repeat(49) + "$      919.00-")
                    .hasSize(EIGHTY);
        }

        @Test
        @DisplayName("rejects a null sending value on every alphanumeric setter")
        void rejectsNullSendingValues() {
            StatementFile file = writer().openOutput(new CollectingSink());

            assertThatNullPointerException().isThrownBy(() -> file.setName(null));
            assertThatNullPointerException().isThrownBy(() -> file.setAddressLine1(null));
            assertThatNullPointerException().isThrownBy(() -> file.setAddressLine2(null));
            assertThatNullPointerException().isThrownBy(() -> file.setAddressLine3(null));
            assertThatNullPointerException().isThrownBy(() -> file.setTransactionId(null));
            assertThatNullPointerException().isThrownBy(() -> file.setTransactionDetails(null));
            assertThatNullPointerException().isThrownBy(() -> file.setCurrentBalance(null));
            assertThatNullPointerException().isThrownBy(() -> file.setTransactionAmount(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> file.setTotalTransactionAmount(null));
        }

        @Test
        @DisplayName("rejects a null slot or line identity when reading back")
        void rejectsNullIdentities() {
            StatementFile file = writer().openOutput(new CollectingSink());

            assertThatNullPointerException().isThrownBy(() -> file.slotImage(null));
            assertThatNullPointerException().isThrownBy(() -> file.renderLine(null));
            assertThatNullPointerException().isThrownBy(() -> file.renderLineBytes(null));
            assertThatNullPointerException().isThrownBy(() -> file.writeLine(null));
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("Writing - one record per call, in call order, into an injected sink")
    class WriteTests {

        @Test
        @DisplayName("emits records in call order, with repeats, and never reorders or coalesces")
        void emitsRecordsInCallOrder() {
            CollectingSink sink = new CollectingSink();
            StatementFile file = writer().openOutput(sink);

            // The COBOL writes ST-LINE5 twice and ST-LINE12 three times per statement; both
            // repetitions must reach the dataset.
            assertThat(file.writeLine(StatementLine.ST_LINE0)).isEqualTo(FileStatus.Outcome.OK);
            file.writeLine(StatementLine.ST_LINE5);
            file.writeLine(StatementLine.ST_LINE6);
            file.writeLine(StatementLine.ST_LINE5);
            file.writeLine(StatementLine.ST_LINE12);
            file.writeLine(StatementLine.ST_LINE12);
            file.writeLine(StatementLine.ST_LINE15);

            List<String> emitted = sink.decoded(ASCII);
            assertThat(emitted).hasSize(7).allSatisfy(image -> assertThat(image).hasSize(EIGHTY));
            assertThat(emitted.get(0)).startsWith("*".repeat(31));
            assertThat(emitted.get(1)).isEqualTo("-".repeat(EIGHTY));
            assertThat(emitted.get(2)).contains("Basic Details");
            assertThat(emitted.get(3)).isEqualTo("-".repeat(EIGHTY));
            assertThat(emitted.get(4)).isEqualTo("-".repeat(EIGHTY));
            assertThat(emitted.get(5)).isEqualTo("-".repeat(EIGHTY));
            assertThat(emitted.get(6)).contains("END OF STATEMENT");
            assertThat(file.recordsWritten()).isEqualTo(7);
        }

        @Test
        @DisplayName("emits the values current at the moment of the write, not at the moment of set")
        void emitsTheValuesCurrentAtWriteTime() {
            CollectingSink sink = new CollectingSink();
            StatementFile file = writer().openOutput(sink);

            file.setTransactionId("TRAN0000000000A1");
            file.setTransactionDetails("FIRST PURCHASE");
            file.setTransactionAmount(new BigDecimal("10.00"));
            file.writeLine(StatementLine.ST_LINE14);

            file.setTransactionId("TRAN0000000000A2");
            file.setTransactionDetails("SECOND PURCHASE");
            file.setTransactionAmount(new BigDecimal("-20.50"));
            file.writeLine(StatementLine.ST_LINE14);

            List<String> emitted = sink.decoded(ASCII);
            assertThat(emitted.get(0)).startsWith("TRAN0000000000A1 FIRST PURCHASE")
                    .endsWith("$       10.00 ");
            assertThat(emitted.get(1)).startsWith("TRAN0000000000A2 SECOND PURCHASE")
                    .endsWith("$       20.50-");
        }

        @Test
        @DisplayName("hands the sink a fresh array each time, so a sink may retain it")
        void handsTheSinkAFreshArrayEachTime() {
            CollectingSink sink = new CollectingSink();
            StatementFile file = writer().openOutput(sink);

            file.writeLine(StatementLine.ST_LINE12);
            file.writeLine(StatementLine.ST_LINE12);

            assertThat(sink.records).hasSize(2);
            assertThat(sink.records.get(0)).isNotSameAs(sink.records.get(1));
            assertThat(sink.records.get(0)).isEqualTo(sink.records.get(1));
        }

        @Test
        @DisplayName("reports a rejected write as the WHEN OTHER outcome and still counts it")
        void reportsARejectedWriteAsOther() {
            CollectingSink sink = new CollectingSink(FileStatus.Outcome.OTHER,
                    FileStatus.Outcome.OK);
            StatementFile file = writer().openOutput(sink);

            assertThat(file.writeLine(StatementLine.ST_LINE0))
                    .isEqualTo(FileStatus.Outcome.OTHER);
            assertThat(FileStatus.Outcome.OTHER.batchStatus()).isEmpty();
            assertThat(file.recordsWritten()).isEqualTo(1);
        }

        @Test
        @DisplayName("does not mutate the line area when a line is written")
        void doesNotMutateTheLineAreaOnWrite() {
            CollectingSink sink = new CollectingSink();
            StatementFile file = writer().openOutput(sink);
            String before = file.renderLine(StatementLine.ST_LINE0);

            file.writeLine(StatementLine.ST_LINE0);

            assertThat(file.renderLine(StatementLine.ST_LINE0)).isEqualTo(before);
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("Open and close - OPEN OUTPUT at L293, CLOSE at L339")
    class LifecycleTests {

        @Test
        @DisplayName("opens already reset, open, and with nothing written")
        void opensReadyToUse() {
            StatementFile file = writer().openOutput(new CollectingSink());

            assertThat(file.isOpen()).isTrue();
            assertThat(file.recordsWritten()).isZero();
            assertThat(file.slotImage(StatementSlot.ST_CURR_BAL)).isEqualTo("000000000.00 ");
        }

        @Test
        @DisplayName("gives each open its own line area, so two runs never share slot state")
        void givesEachOpenItsOwnLineArea() {
            StatementTextWriter writer = writer();
            StatementFile first = writer.openOutput(new CollectingSink());
            StatementFile second = writer.openOutput(new CollectingSink());

            first.setName("CUSTOMER ONE");

            assertThat(first.slotImage(StatementSlot.ST_NAME)).startsWith("CUSTOMER ONE");
            assertThat(second.slotImage(StatementSlot.ST_NAME)).isEqualTo(" ".repeat(75));
        }

        @Test
        @DisplayName("closes once, reports the sink's outcome, and is idempotent afterwards")
        void closesOnceAndIsIdempotent() {
            CollectingSink sink = new CollectingSink();
            StatementFile file = writer().openOutput(sink);

            assertThat(file.closeOutput()).isEqualTo(FileStatus.Outcome.OK);
            assertThat(file.isOpen()).isFalse();
            assertThat(sink.closeCalls).isEqualTo(1);

            // Closing again is reported OK and does not reach the sink a second time.
            assertThat(file.closeOutput()).isEqualTo(FileStatus.Outcome.OK);
            assertThat(sink.closeCalls).isEqualTo(1);
        }

        @Test
        @DisplayName("reports a failed close as the WHEN OTHER outcome")
        void reportsAFailedClose() {
            CollectingSink sink = new CollectingSink(FileStatus.Outcome.OK,
                    FileStatus.Outcome.OTHER);
            StatementFile file = writer().openOutput(sink);

            assertThat(file.closeOutput()).isEqualTo(FileStatus.Outcome.OTHER);
        }

        @Test
        @DisplayName("supports try-with-resources, and logs rather than throws on a failed close")
        void supportsTryWithResources() {
            CollectingSink clean = new CollectingSink();
            try (StatementFile file = writer().openOutput(clean)) {
                file.writeLine(StatementLine.ST_LINE0);
            }
            assertThat(clean.closeCalls).isEqualTo(1);
            assertThat(clean.records).hasSize(1);

            // A non-OK close must not throw out of a try-with-resources block.
            CollectingSink failing = new CollectingSink(FileStatus.Outcome.OK,
                    FileStatus.Outcome.OTHER);
            StatementFile file = writer().openOutput(failing);
            file.close();
            assertThat(file.isOpen()).isFalse();
            assertThat(failing.closeCalls).isEqualTo(1);
        }

        @Test
        @DisplayName("refuses a write after close, naming the record count reached")
        void refusesAWriteAfterClose() {
            StatementFile file = writer().openOutput(new CollectingSink());
            file.writeLine(StatementLine.ST_LINE0);
            file.closeOutput();

            assertThatIllegalStateException()
                    .isThrownBy(() -> file.writeLine(StatementLine.ST_LINE15))
                    .withMessageContaining("ST-LINE15")
                    .withMessageContaining("STMTFILE")
                    .withMessageContaining("1 record(s)");
        }

        @Test
        @DisplayName("still renders lines after close, because rendering writes nothing")
        void stillRendersAfterClose() {
            StatementFile file = writer().openOutput(new CollectingSink());
            file.closeOutput();

            assertThat(file.renderLine(StatementLine.ST_LINE12)).isEqualTo("-".repeat(EIGHTY));
        }

        @Test
        @DisplayName("rejects a null sink rather than silently discarding records")
        void rejectsANullSink() {
            StatementTextWriter writer = writer();

            assertThatNullPointerException().isThrownBy(() -> writer.openOutput(null))
                    .withMessageContaining("STMTFILE");
        }
    }

    // =============================================================================================

    /**
     * A sink that breaks its contract by answering {@code null}.
     *
     * <p>Not reachable through the production sink - {@link StatementTextWriter.JdbcRecordSink} always
     * answers - but entirely reachable through the injectable seam, which is the point: the seam exists
     * so a site can substitute its own sink, and a substituted sink is exactly the thing that might
     * return nothing on a path its author did not think about.
     */
    private static final class NullAnsweringSink implements RecordSink {

        /**
         * How many records this sink answers properly before it starts answering {@code null}, or
         * {@link Integer#MAX_VALUE} to answer properly always.
         *
         * <p>Counted rather than switched, because the interesting case is a sink that has already
         * accepted records: the record count the diagnostic names is only meaningful if the run got
         * somewhere first.
         */
        private final int writesBeforeNull;

        /** Whether {@link #close()} answers {@code null}. */
        private final boolean nullOnClose;

        /** How many records reached the sink. */
        private int writeCalls;

        /** How many closes reached the sink. */
        private int closeCalls;

        private NullAnsweringSink(final int writesBeforeNull, final boolean nullOnClose) {
            this.writesBeforeNull = writesBeforeNull;
            this.nullOnClose = nullOnClose;
        }

        /** A sink that answers every write and every close properly. */
        private static NullAnsweringSink compliant() {
            return new NullAnsweringSink(Integer.MAX_VALUE, false);
        }

        @Override
        public FileStatus.Outcome write(final byte[] recordImage) {
            this.writeCalls++;
            return this.writeCalls > this.writesBeforeNull ? null : FileStatus.Outcome.OK;
        }

        @Override
        public FileStatus.Outcome close() {
            this.closeCalls++;
            return this.nullOnClose ? null : FileStatus.Outcome.OK;
        }
    }

    @Nested
    @DisplayName("The sink contract - a null outcome is a defect, not a FILE STATUS")
    class SinkContractTests {

        @Test
        @DisplayName("A null write outcome is rejected at the write, naming the line and the count")
        void aNullWriteOutcomeIsRejected() {
            NullAnsweringSink sink = new NullAnsweringSink(1, false);
            StatementFile file = writer().openOutput(sink);
            file.writeLine(StatementLine.ST_LINE0);

            assertThatNullPointerException()
                    .isThrownBy(() -> file.writeLine(StatementLine.ST_LINE15))
                    .withMessageContaining("STMTFILE")
                    .withMessageContaining("write(byte[])")
                    .withMessageContaining("ST-LINE15")
                    .withMessageContaining("1 record(s)")
                    .withMessageContaining("FileStatus.Outcome.OTHER");
        }

        @Test
        @DisplayName("The rejected write does not advance the record count")
        void theRejectedWriteDoesNotAdvanceTheCount() {
            NullAnsweringSink sink = new NullAnsweringSink(0, false);
            StatementFile file = writer().openOutput(sink);

            assertThatNullPointerException()
                    .isThrownBy(() -> file.writeLine(StatementLine.ST_LINE0));

            // The record did reach the sink; what did not happen is the count advancing past a
            // record whose fate is unknown.
            assertThat(sink.writeCalls).isEqualTo(1);
            assertThat(file.recordsWritten()).isZero();
        }

        @Test
        @DisplayName("A null close outcome is rejected at closeOutput(), naming the count")
        void aNullCloseOutcomeIsRejected() {
            NullAnsweringSink sink = new NullAnsweringSink(Integer.MAX_VALUE, true);
            StatementFile file = writer().openOutput(sink);
            file.writeLine(StatementLine.ST_LINE0);

            assertThatNullPointerException()
                    .isThrownBy(file::closeOutput)
                    .withMessageContaining("STMTFILE")
                    .withMessageContaining("close()")
                    .withMessageContaining("1 record(s)");
        }

        @Test
        @DisplayName("A handle whose close was rejected is still closed, so the sink is reached once")
        void aRejectedCloseStillClosesTheHandle() {
            NullAnsweringSink sink = new NullAnsweringSink(Integer.MAX_VALUE, true);
            StatementFile file = writer().openOutput(sink);

            assertThatNullPointerException().isThrownBy(file::closeOutput);

            assertThat(file.isOpen()).isFalse();
            assertThat(sink.closeCalls).isEqualTo(1);

            // The second close is the idempotent no-op arm, so it cannot reach the broken sink again.
            assertThat(file.closeOutput()).isEqualTo(FileStatus.Outcome.OK);
            assertThat(sink.closeCalls).isEqualTo(1);
        }

        @Test
        @DisplayName("close() surfaces the contract violation rather than an unrelated NPE later")
        void closeSurfacesTheContractViolation() {
            NullAnsweringSink sink = new NullAnsweringSink(Integer.MAX_VALUE, true);
            StatementFile file = writer().openOutput(sink);

            // Before the fix this NPE came from Outcome.name() on a null, with the sink long gone
            // from the stack; now the message names the sink, the DD and the count.
            assertThatNullPointerException()
                    .isThrownBy(file::close)
                    .withMessageContaining("STMTFILE")
                    .withMessageContaining("close()");
        }

        @Test
        @DisplayName("A sink that answers properly is unaffected: OK and OTHER both pass through")
        void aCompliantSinkIsUnaffected() {
            NullAnsweringSink compliant = NullAnsweringSink.compliant();
            StatementFile file = writer().openOutput(compliant);

            assertThat(file.writeLine(StatementLine.ST_LINE0)).isEqualTo(FileStatus.Outcome.OK);
            assertThat(file.closeOutput()).isEqualTo(FileStatus.Outcome.OK);

            CollectingSink rejecting = new CollectingSink(FileStatus.Outcome.OTHER,
                    FileStatus.Outcome.OTHER);
            StatementFile other = writer().openOutput(rejecting);

            assertThat(other.writeLine(StatementLine.ST_LINE0)).isEqualTo(FileStatus.Outcome.OTHER);
            assertThat(other.closeOutput()).isEqualTo(FileStatus.Outcome.OTHER);
        }

        @Test
        @DisplayName("The interface documents both outcomes as non-null, so the guard is the contract")
        void theInterfaceDocumentsBothOutcomesAsNonNull() {
            // The default close() - the one a sink inherits when it declares nothing - answers OK,
            // which is what makes a null answer a deliberate act rather than an omission.
            RecordSink inheriting = recordImage -> FileStatus.Outcome.OK;

            assertThat(inheriting.close()).isEqualTo(FileStatus.Outcome.OK);
            assertThat(inheriting.write(new byte[StatementTextWriter.RECORD_LENGTH]))
                    .isEqualTo(FileStatus.Outcome.OK);
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("Construction - configuration-bound, and cross-checked against the copybook")
    class ConstructionTests {

        @Test
        @DisplayName("resolves the STMTFILE binding by DD name and exposes it verbatim")
        void resolvesTheBindingByDdName() {
            StatementTextWriter writer = writer();
            DatasetBinding binding = writer.datasetBinding();

            assertThat(binding.dsname()).isEqualTo(TEST_DSNAME);
            assertThat(binding.organization()).isEqualTo("sequential");
            assertThat(binding.recordFormat()).isEqualTo("FB");
            assertThat(binding.blockSize()).isEqualTo(8000);
            assertThat(binding.recordLength()).isEqualTo(EIGHTY);
        }

        @Test
        @DisplayName("exposes the injected charset rather than resolving one of its own")
        void exposesTheInjectedCharset() {
            assertThat(writer(ASCII).datasetCharset()).isEqualTo(ASCII);
            assertThat(writer(EBCDIC).datasetCharset()).isEqualTo(EBCDIC);
        }

        @Test
        @DisplayName("refuses to start when the configured record length is not 80")
        void refusesAWrongRecordLength() {
            DatasetBindings wrong = bindings(100);

            assertThatIllegalStateException()
                    .isThrownBy(() -> new StatementTextWriter(new JdbcTemplate(), ASCII, wrong))
                    .withMessageContaining("record-length 100")
                    .withMessageContaining("CBSTM03A.CBL:L45")
                    .withMessageContaining("CREASTMT.JCL:L89");
        }

        @Test
        @DisplayName("refuses to start when no STMTFILE binding is configured at all")
        void refusesAMissingBinding() {
            DatasetBindings empty = new DatasetBindings();

            assertThatIllegalStateException()
                    .isThrownBy(() -> new StatementTextWriter(new JdbcTemplate(), ASCII, empty))
                    .withMessageContaining("STMTFILE");
        }

        @Test
        @DisplayName("rejects every null collaborator, naming what is missing and why")
        void rejectsNullCollaborators() {
            DatasetBindings catalogue = bindings(EIGHTY);

            assertThatNullPointerException()
                    .isThrownBy(() -> new StatementTextWriter(null, ASCII, catalogue))
                    .withMessageContaining("JdbcTemplate");
            assertThatNullPointerException()
                    .isThrownBy(() -> new StatementTextWriter(new JdbcTemplate(), null, catalogue))
                    .withMessageContaining("code page");
            assertThatNullPointerException()
                    .isThrownBy(() -> new StatementTextWriter(new JdbcTemplate(), ASCII, null))
                    .withMessageContaining("carddemo.datasets");
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("The default JDBC sink - no column list, one parameter, bound as bytes")
    class JdbcSinkTests {

        @Test
        @DisplayName("builds a column-free insert with the dataset name as a delimited identifier")
        void buildsAColumnFreeInsert() {
            // A multi-part dotted name must survive as ONE identifier rather than being read as a
            // qualified catalogue-schema-table reference. The name used here is deliberately a
            // stand-in: the production dataset name belongs in application.yml alone, so writing it
            // into a Java source - even a test - would put a hit into the negative scan that gate
            // G46 relies on being empty.
            assertThat(writerBoundTo("FIVE.PART.DOTTED.DSN.NAME").insertStatement())
                    .isEqualTo("INSERT INTO \"FIVE.PART.DOTTED.DSN.NAME\" VALUES (?)");
        }

        @Test
        @DisplayName("composes that statement through DatasetRelation, not through text of its own")
        void composesThroughTheOneContract() {
            // Pinned to the module's one data-access contract rather than to a string literal, so a
            // second renderer cannot reappear here and pass: the only way this holds is if the writer
            // asks DatasetRelation. Its sibling StatementHtmlWriter is pinned the same way, which is
            // what makes the two statements identical apart from the dataset they name (F07).
            assertThat(writer().insertStatement())
                    .isEqualTo(DatasetRelation.of(TEST_DSNAME, EIGHTY).insertRecordImage());
        }

        @Test
        @DisplayName("refuses a name that is not a well-formed dataset name, rather than quoting it")
        void refusesAMalformedDatasetName() {
            // An embedded quotation mark was previously doubled and composed. Doubling is the right
            // rule for an identifier that legitimately contains a quote - and a z/OS dataset name
            // never does, so a name carrying one is not a dataset name and is refused outright. The
            // grammar's own verdict travels as the cause so the offending position is not lost.
            StatementTextWriter odd = writerBoundTo("ODD\"NAME");

            assertThatIllegalStateException()
                    .isThrownBy(odd::insertStatement)
                    .withMessageContaining("carddemo.datasets.STMTFILE.dsname")
                    .withMessageContaining("openOutput(RecordSink)")
                    .withCauseInstanceOf(IllegalArgumentException.class);
        }

        @ParameterizedTest
        @DisplayName("refuses every shape a dataset name cannot take, and never composes it")
        @ValueSource(strings = {
            "A.B; DROP TABLE C",
            "A.B--C.D E",
            "A.B'C",
            "TOOLONGQUALIFIER.B",
            "9BAD.START",
            "A..B",
            "/tmp/carddemo/statement.txt",
            "classpath:fixtures/statement.txt",
        })
        void refusesEveryUnusableShape(String candidate) {
            StatementTextWriter bound = writerBoundTo(candidate);

            assertThatIllegalStateException().isThrownBy(bound::insertStatement);
            assertThatIllegalStateException().isThrownBy(bound::openOutput);
        }

        @Test
        @DisplayName("rejects a blank or absent dataset name, naming the property to set")
        void rejectsABlankDatasetName() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> writerBoundTo("   ").insertStatement())
                    .withMessageContaining("carddemo.datasets.STMTFILE.dsname");
            assertThatIllegalStateException()
                    .isThrownBy(() -> writerBoundTo(null).insertStatement())
                    .withMessageContaining("carddemo.datasets.STMTFILE.dsname");
        }

        @Test
        @DisplayName("still constructs under a fixture-backed binding, so the context starts (G3)")
        void stillConstructsUnderAFixtureBackedBinding() {
            // The 'test' profile binds STMTFILE to a filesystem location. That is not a dataset name
            // and can never become a SQL identifier - but refusing the bean outright would stop the
            // application context from starting under the only profile this environment can run,
            // even though every test and the parity harness supply their own sink. So construction
            // succeeds, the geometry is still checked, and the refusal waits until something actually
            // asks for the default sink.
            StatementTextWriter fixtureBound = writerBoundTo("/tmp/carddemo/statement.txt");

            assertThat(fixtureBound.recordLength()).isEqualTo(EIGHTY);
            assertThat(fixtureBound.datasetBinding().dsname())
                    .isEqualTo("/tmp/carddemo/statement.txt");
            assertThatCode(() -> fixtureBound.openOutput(new CollectingSink()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("binds the whole 80-byte record image as bytes on parameter 1")
        void bindsTheRecordImageAsBytes() throws SQLException {
            DataSource dataSource = Mockito.mock(DataSource.class);
            Connection connection = Mockito.mock(Connection.class);
            PreparedStatement statement = Mockito.mock(PreparedStatement.class);
            Mockito.when(dataSource.getConnection()).thenReturn(connection);
            Mockito.when(connection.prepareStatement(Mockito.anyString())).thenReturn(statement);

            StatementTextWriter writer = new StatementTextWriter(new JdbcTemplate(dataSource),
                    ASCII, bindings(EIGHTY));
            StatementFile file = writer.openOutput();

            assertThat(file.writeLine(StatementLine.ST_LINE12))
                    .isEqualTo(FileStatus.Outcome.OK);

            Mockito.verify(connection)
                    .prepareStatement("INSERT INTO \"" + TEST_DSNAME + "\" VALUES (?)");
            Mockito.verify(statement).setBytes(1, "-".repeat(EIGHTY).getBytes(ASCII));
            Mockito.verify(statement).executeUpdate();
        }

        @Test
        @DisplayName("maps a rejected write onto the WHEN OTHER outcome instead of throwing")
        void mapsARejectedWriteOntoOther() throws SQLException {
            DataSource dataSource = Mockito.mock(DataSource.class);
            Mockito.when(dataSource.getConnection())
                    .thenThrow(new SQLException("dataset unavailable"));

            StatementTextWriter writer = new StatementTextWriter(new JdbcTemplate(dataSource),
                    ASCII, bindings(EIGHTY));
            StatementFile file = writer.openOutput();

            assertThat(file.writeLine(StatementLine.ST_LINE0))
                    .isEqualTo(FileStatus.Outcome.OTHER);
            assertThat(file.recordsWritten()).isEqualTo(1);
        }

        @Test
        @DisplayName("lets a configuration defect propagate rather than reporting it as a bad write")
        void letsAConfigurationDefectPropagate() {
            // A JdbcTemplate with no DataSource is a wiring defect, not a dataset condition. It
            // raises an unchecked type outside the DataAccessException family, which the sink
            // deliberately does not catch: reporting it as a failed write would make every record
            // abend with a misleading reason instead of failing once, clearly.
            StatementFile file = writer().openOutput();

            assertThatIllegalStateException()
                    .isThrownBy(() -> file.writeLine(StatementLine.ST_LINE0));
        }

        @Test
        @DisplayName("closes cleanly, because the pooled connection is released per record")
        void closesCleanly() {
            StatementFile file = writer().openOutput();

            assertThat(file.closeOutput()).isEqualTo(FileStatus.Outcome.OK);
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("Encoding - the injected code page, never a platform default")
    class EncodingTests {

        @Test
        @DisplayName("encodes the same characters to different bytes under the two code pages")
        void encodesUnderTheInjectedCodePage() {
            CollectingSink ascii = new CollectingSink();
            CollectingSink ebcdic = new CollectingSink();
            writer(ASCII).openOutput(ascii).writeLine(StatementLine.ST_LINE12);
            writer(EBCDIC).openOutput(ebcdic).writeLine(StatementLine.ST_LINE12);

            byte[] asciiRecord = ascii.records.get(0);
            byte[] ebcdicRecord = ebcdic.records.get(0);

            assertThat(asciiRecord).hasSize(EIGHTY);
            assertThat(ebcdicRecord).hasSize(EIGHTY);
            assertThat(asciiRecord).isNotEqualTo(ebcdicRecord);
            assertThat(asciiRecord[0]).isEqualTo((byte) 0x2D);
            assertThat(ebcdicRecord[0]).isEqualTo((byte) 0x60);

            // The same characters, whichever code page carries them.
            assertThat(ascii.decoded(ASCII)).isEqualTo(ebcdic.decoded(EBCDIC));
        }

        @Test
        @DisplayName("encodes an EBCDIC space as 0x40 where an ASCII space is 0x20")
        void encodesSpacesUnderBothCodePages() {
            CollectingSink ebcdic = new CollectingSink();
            writer(EBCDIC).openOutput(ebcdic).writeLine(StatementLine.ST_LINE1);

            assertThat(ebcdic.records.get(0)).containsOnly((byte) 0x40);
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("Structure - no mutable static state anywhere (gate G53)")
    class StructuralTests {

        @Test
        @DisplayName("declares every static field final, in the class and in every nested type")
        void everyStaticFieldIsFinal() {
            List<Class<?>> types = new ArrayList<>();
            types.add(StatementTextWriter.class);
            types.addAll(List.of(StatementTextWriter.class.getDeclaredClasses()));

            assertThat(types).hasSizeGreaterThan(4);
            for (Class<?> type : types) {
                for (Field field : type.getDeclaredFields()) {
                    // Synthetic fields are compiler and agent artefacts - an enum's $VALUES and
                    // JaCoCo's non-final $jacocoData probe array - and are not this class's state.
                    if (field.isSynthetic()) {
                        continue;
                    }
                    if (Modifier.isStatic(field.getModifiers())) {
                        assertThat(Modifier.isFinal(field.getModifiers()))
                                .withFailMessage("static field %s.%s is not final",
                                        type.getSimpleName(), field.getName())
                                .isTrue();
                    }
                }
            }
        }

        @Test
        @DisplayName("keeps every instance field of the writer bean final, so it is never re-wired")
        void everyWriterFieldIsFinal() {
            for (Field field : StatementTextWriter.class.getDeclaredFields()) {
                if (!field.isSynthetic() && !Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .withFailMessage("instance field %s is not final", field.getName())
                            .isTrue();
                }
            }
        }

        @Test
        @DisplayName("exposes exactly one constructor, so wiring cannot be ambiguous")
        void exposesExactlyOneConstructor() {
            assertThat(StatementTextWriter.class.getDeclaredConstructors()).hasSize(1);
        }
    }
}
