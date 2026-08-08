package com.vsergeychik.carddemo.statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.statement.StatementHtmlWriter.AddressField;
import com.vsergeychik.carddemo.statement.StatementHtmlWriter.BasicDetail;
import com.vsergeychik.carddemo.statement.StatementHtmlWriter.HtmlFixedLine;
import com.vsergeychik.carddemo.statement.StatementHtmlWriter.HtmlRecordSink;
import com.vsergeychik.carddemo.statement.StatementHtmlWriter.HtmlStatementFile;
import com.vsergeychik.carddemo.statement.StatementHtmlWriter.JdbcHtmlRecordSink;
import com.vsergeychik.carddemo.statement.StatementHtmlWriter.TransactionField;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;

/**
 * Behavioural-parity tests for {@link StatementHtmlWriter}, the owner of the 100-byte
 * {@code HTMLFILE} record of {@code app/cbl/CBSTM03A.CBL}.
 *
 * <p>Every assertion here is anchored to a line of the COBOL or of the JCL. Nothing runs a Spring
 * context, a {@code JobLauncher} or a filesystem write: the writer's sink is injected as a lambda
 * that collects the 100-byte images, so the bytes and their order are asserted directly (gate G51,
 * practice B10).
 *
 * <p>The literal catalogue is asserted twice over. First against expected values written out
 * independently in this file, and then - in {@link ObjectionableTranscription} - against
 * {@code app/cbl/CBSTM03A.CBL} itself, re-extracted from the source with the same fixed-format
 * continuation rule the COBOL compiler applies. The second check is what makes the first
 * trustworthy: a transcription error would have to be made identically in two places to survive.
 *
 * <p>{@code review_rules} returns exactly one line - "No user rules provided." - so no user rule
 * governs this file either.
 */
@DisplayName("StatementHtmlWriter - the 100-byte HTMLFILE record of CBSTM03A")
class StatementHtmlWriterTest {

    /** The account-master record width, reused to build a deliberately wrong binding. */
    private static final int WRONG_RECORD_LENGTH_FROM_PREDELETE_STEP = 80;

    /** A dataset name shaped like the real one, but not the real one. */
    private static final String TEST_DSNAME = "TEST.M2.STATEMNT.HTML";

    /** The single space that a padded field is filled with. */
    private static final char SPACE = ' ';

    /** Collected records, in emission order. */
    private List<byte[]> emitted;

    /** The writer under test, wired with a mocked template and the ASCII code page. */
    private StatementHtmlWriter writer;

    /** The open handle. */
    private HtmlStatementFile file;

    /** The mocked template; only the default sink ever touches it. */
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void openWithACollectingSink() {
        this.jdbcTemplate = mock(JdbcTemplate.class);
        this.writer = newWriter(this.jdbcTemplate, StatementHtmlWriter.RECORD_LENGTH, TEST_DSNAME);
        this.emitted = new ArrayList<>();
        this.file = this.writer.open(record -> {
            this.emitted.add(record);
            return FileStatus.OK;
        });
    }

    /**
     * Builds a writer over a synthetic {@code carddemo.datasets} catalogue.
     *
     * @param template     the template to inject
     * @param recordLength the record length the {@code HTMLFILE} binding will declare
     * @param dsname       the dataset name the binding will declare
     * @return the writer
     */
    private static StatementHtmlWriter newWriter(final JdbcTemplate template, final int recordLength,
                                                 final String dsname) {
        DatasetBindings bindings = new DatasetBindings();
        bindings.put(StatementHtmlWriter.HTMLFILE_DD_NAME, new DatasetBinding(
                dsname, "sequential", false, "FB", StatementHtmlWriter.BLOCK_SIZE, recordLength,
                null, null, null, null, null));
        return new StatementHtmlWriter(template, StandardCharsets.US_ASCII, bindings);
    }

    /**
     * The last collected record, decoded as ASCII.
     *
     * @return the record as text, all 100 characters of it
     */
    private String lastRecordText() {
        assertThat(this.emitted).isNotEmpty();
        return new String(this.emitted.get(this.emitted.size() - 1), StandardCharsets.US_ASCII);
    }

    /**
     * Every collected record, decoded as ASCII, in emission order.
     *
     * @return the records as text
     */
    private List<String> allRecordText() {
        return this.emitted.stream()
                .map(record -> new String(record, StandardCharsets.US_ASCII))
                .toList();
    }

    // =============================================================================================

    @Nested
    @DisplayName("The record is 100 bytes, not 80 - gate G20, risk R-G")
    class RecordWidthIsOneHundredNotEighty {

        @Test
        @DisplayName("RECORD_LENGTH is 100: CREASTMT.JCL:L94 LRECL=100 and CBSTM03A.CBL:L47 PIC X(100)")
        void recordLengthIsOneHundred() {
            assertThat(StatementHtmlWriter.RECORD_LENGTH).isEqualTo(100);
            assertThat(StatementHtmlWriterTest.this.writer.recordLength()).isEqualTo(100);
        }

        @Test
        @DisplayName("BLOCK_SIZE is 800 from the creating step, and is documentation only")
        void blockSizeIsEightHundred() {
            assertThat(StatementHtmlWriter.BLOCK_SIZE).isEqualTo(800);
            assertThat(StatementHtmlWriterTest.this.writer.blockSize()).isEqualTo(800);
        }

        @Test
        @DisplayName("Construction is refused when the binding declares the pre-delete step's 80")
        void constructionIsRefusedForTheEightyByteWidthOfTheIefbr14PredeleteStep() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> newWriter(template, WRONG_RECORD_LENGTH_FROM_PREDELETE_STEP,
                            TEST_DSNAME))
                    .withMessageContaining("record-length is 80")
                    .withMessageContaining("LRECL=80")
                    .withMessageContaining("LRECL=100")
                    .withMessageContaining("L69")
                    .withMessageContaining("L94")
                    .withMessageContaining("PIC X(100)");
        }

        @Test
        @DisplayName("Any other declared width is refused too, not only 80")
        void constructionIsRefusedForAnyOtherWidth() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> newWriter(template, 133, TEST_DSNAME))
                    .withMessageContaining("record-length is 133");
        }

        @Test
        @DisplayName("A missing HTMLFILE binding is refused by the catalogue, not defaulted")
        void aMissingBindingIsRefused() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            DatasetBindings empty = new DatasetBindings();
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new StatementHtmlWriter(template, StandardCharsets.US_ASCII,
                            empty))
                    .withMessageContaining(StatementHtmlWriter.HTMLFILE_DD_NAME);
        }

        @Test
        @DisplayName("The verified binding is exposed for inspection")
        void theBindingIsExposed() {
            DatasetBinding binding = StatementHtmlWriterTest.this.writer.datasetBinding();
            assertThat(binding.recordLength()).isEqualTo(StatementHtmlWriter.RECORD_LENGTH);
            assertThat(binding.blockSize()).isEqualTo(StatementHtmlWriter.BLOCK_SIZE);
            assertThat(binding.dsname()).isEqualTo(TEST_DSNAME);
        }

        @Test
        @DisplayName("The injected charset is the one supplied, never a platform default")
        void theCharsetIsTheInjectedOne() {
            Charset charset = StatementHtmlWriterTest.this.writer.datasetCharset();
            assertThat(charset).isEqualTo(StandardCharsets.US_ASCII);
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("The 34 fixed literals - CBSTM03A.CBL:L150-L211")
    class FixedLiteralCatalogue {

        @Test
        @DisplayName("Exactly 34 constants, in COBOL declaration order")
        void thereAreThirtyFourConstantsInDeclarationOrder() {
            assertThat(HtmlFixedLine.values()).hasSize(34);
            assertThat(HtmlFixedLine.values()[0]).isEqualTo(HtmlFixedLine.HTML_L01);
            assertThat(HtmlFixedLine.values()[8]).isEqualTo(HtmlFixedLine.HTML_LTRS);
            assertThat(HtmlFixedLine.values()[33]).isEqualTo(HtmlFixedLine.HTML_L80);
        }

        @ParameterizedTest(name = "{0} = [{1}]")
        @MethodSource(
                "com.vsergeychik.carddemo.statement.StatementHtmlWriterTest#expectedFixedLiterals")
        @DisplayName("Each literal is byte-exact")
        void eachLiteralIsByteExact(final HtmlFixedLine line, final String expected) {
            assertThat(line.literal()).isEqualTo(expected);
            assertThat(line.literalLength()).isEqualTo(expected.length());
        }

        @Test
        @DisplayName("HTML-L08 has TWO spaces after <table, not one")
        void htmlL08HasTwoSpacesAfterTheTableTag() {
            assertThat(HtmlFixedLine.HTML_L08.literal()).startsWith("<table  align=");
            assertThat(HtmlFixedLine.HTML_L08.literal()).doesNotContain("<table align=");
        }

        @Test
        @DisplayName("The colspan cells have NO space after 'padding:0px 5px;'")
        void theColspanCellsHaveNoSpaceAfterThePadding() {
            for (HtmlFixedLine line : List.of(HtmlFixedLine.HTML_L10, HtmlFixedLine.HTML_L15,
                    HtmlFixedLine.HTML_L22_35, HtmlFixedLine.HTML_L30_42)) {
                assertThat(line.literal()).contains("padding:0px 5px;background-color:");
            }
        }

        @Test
        @DisplayName("The width cells DO have a space after 'padding:0px 5px;'")
        void theWidthCellsHaveASpaceAfterThePadding() {
            for (HtmlFixedLine line : List.of(HtmlFixedLine.HTML_L47, HtmlFixedLine.HTML_L50,
                    HtmlFixedLine.HTML_L53, HtmlFixedLine.HTML_L58, HtmlFixedLine.HTML_L61,
                    HtmlFixedLine.HTML_L64)) {
                assertThat(line.literal()).contains("padding:0px 5px; background-color:");
            }
        }

        @ParameterizedTest
        @EnumSource(HtmlFixedLine.class)
        @DisplayName("No literal exceeds the PIC X(100) field it is SET into")
        void noLiteralExceedsOneHundredCharacters(final HtmlFixedLine line) {
            assertThat(line.literalLength())
                    .isBetween(1, StatementHtmlWriter.RECORD_LENGTH);
        }

        @Test
        @DisplayName("The compound COBOL names are preserved, hyphens and all")
        void theCompoundNamesArePreserved() {
            assertThat(HtmlFixedLine.HTML_L22_35.cobolName()).isEqualTo("HTML-L22-35");
            assertThat(HtmlFixedLine.HTML_L30_42.cobolName()).isEqualTo("HTML-L30-42");
            assertThat(HtmlFixedLine.HTML_LTRS.cobolName()).isEqualTo("HTML-LTRS");
        }

        @ParameterizedTest
        @EnumSource(HtmlFixedLine.class)
        @DisplayName("Every constant resolves by its own COBOL name")
        void everyConstantResolvesByCobolName(final HtmlFixedLine line) {
            assertThat(HtmlFixedLine.ofCobolName(line.cobolName())).isSameAs(line);
            assertThat(HtmlFixedLine.isDeclared(line.cobolName())).isTrue();
        }

        @Test
        @DisplayName("An unknown COBOL name is refused with the full list of declared names")
        void anUnknownCobolNameIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> HtmlFixedLine.ofCobolName("HTML-L99"))
                    .withMessageContaining("HTML-L99")
                    .withMessageContaining("HTML-L01");
        }

        @Test
        @DisplayName("Name matching is case-sensitive and exact, as COBOL's own resolution is")
        void nameMatchingIsCaseSensitive() {
            assertThat(HtmlFixedLine.isDeclared("html-l01")).isFalse();
            assertThat(HtmlFixedLine.isDeclared("HTML_L01")).isFalse();
            assertThat(HtmlFixedLine.isDeclared(null)).isFalse();
        }

        @Test
        @DisplayName("A null COBOL name is rejected outright")
        void aNullCobolNameIsRejected() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> HtmlFixedLine.ofCobolName(null));
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("Every emitted record is exactly 100 bytes - gates G19 and G20")
    class EveryRecordIsOneHundredBytes {

        @ParameterizedTest
        @EnumSource(HtmlFixedLine.class)
        @DisplayName("SET HTML-Lxx TO TRUE then WRITE FROM HTML-FIXED-LN")
        void everyFixedLineIsOneHundredBytes(final HtmlFixedLine line) {
            FileStatus.Outcome outcome =
                    StatementHtmlWriterTest.this.writer.writeFixedLine(
                            StatementHtmlWriterTest.this.file, line);

            assertThat(outcome).isEqualTo(FileStatus.Outcome.OK);
            assertThat(StatementHtmlWriterTest.this.emitted).hasSize(1);
            assertThat(StatementHtmlWriterTest.this.emitted.get(0))
                    .hasSize(StatementHtmlWriter.RECORD_LENGTH);
            assertThat(StatementHtmlWriterTest.this.lastRecordText())
                    .isEqualTo(padded(line.literal()));
        }

        @Test
        @DisplayName("HTML-L11 is 59 declared bytes padded to 100")
        void theAccountHeadingIsOneHundredBytes() {
            StatementHtmlWriterTest.this.writer.writeAccountHeading(
                    StatementHtmlWriterTest.this.file, "00000000011");

            assertThat(StatementHtmlWriterTest.this.emitted.get(0))
                    .hasSize(StatementHtmlWriter.RECORD_LENGTH);
            assertThat(StatementHtmlWriterTest.this.lastRecordText()).isEqualTo(padded(
                    "<h3>Statement for Account Number: 00000000011         </h3>"));
        }

        @Test
        @DisplayName("The name line is 100 bytes")
        void theNameLineIsOneHundredBytes() {
            StatementHtmlWriterTest.this.writer.writeNameLine(StatementHtmlWriterTest.this.file,
                    "MARGARET GOLD");

            assertThat(StatementHtmlWriterTest.this.emitted.get(0))
                    .hasSize(StatementHtmlWriter.RECORD_LENGTH);
        }

        @ParameterizedTest
        @EnumSource(AddressField.class)
        @DisplayName("Each address line is 100 bytes")
        void eachAddressLineIsOneHundredBytes(final AddressField field) {
            StatementHtmlWriterTest.this.writer.writeAddressLine(
                    StatementHtmlWriterTest.this.file, field, "410 TERRY AVE N");

            assertThat(StatementHtmlWriterTest.this.emitted.get(0))
                    .hasSize(StatementHtmlWriter.RECORD_LENGTH);
        }

        @ParameterizedTest
        @EnumSource(BasicDetail.class)
        @DisplayName("Each basic-detail line is 100 bytes")
        void eachBasicDetailLineIsOneHundredBytes(final BasicDetail detail) {
            StatementHtmlWriterTest.this.writer.writeBasicDetail(
                    StatementHtmlWriterTest.this.file, detail, "123456789");

            assertThat(StatementHtmlWriterTest.this.emitted.get(0))
                    .hasSize(StatementHtmlWriter.RECORD_LENGTH);
        }

        @ParameterizedTest
        @EnumSource(TransactionField.class)
        @DisplayName("Each transaction line is 100 bytes")
        void eachTransactionLineIsOneHundredBytes(final TransactionField field) {
            StatementHtmlWriterTest.this.writer.writeTransactionField(
                    StatementHtmlWriterTest.this.file, field, "0000000000000001");

            assertThat(StatementHtmlWriterTest.this.emitted.get(0))
                    .hasSize(StatementHtmlWriter.RECORD_LENGTH);
        }

        @Test
        @DisplayName("All ten STRING-built shapes plus a literal and the heading: twelve records, all 100")
        void allTwelveShapesAreOneHundredBytes() {
            StatementHtmlWriter local = StatementHtmlWriterTest.this.writer;
            HtmlStatementFile handle = StatementHtmlWriterTest.this.file;

            local.writeFixedLine(handle, HtmlFixedLine.HTML_L01);
            local.writeAccountHeading(handle, "00000000011");
            local.writeNameLine(handle, "A  B");
            local.writeAddressLine(handle, AddressField.ADDRESS_LINE_1, "L1");
            local.writeAddressLine(handle, AddressField.ADDRESS_LINE_2, "L2");
            local.writeAddressLine(handle, AddressField.ADDRESS_LINE_3, "L3");
            local.writeBasicDetail(handle, BasicDetail.ACCOUNT_ID, "1");
            local.writeBasicDetail(handle, BasicDetail.CURRENT_BALANCE, "000001234.56-");
            local.writeBasicDetail(handle, BasicDetail.FICO_SCORE, "700");
            local.writeTransactionField(handle, TransactionField.TRAN_ID, "T1");
            local.writeTransactionField(handle, TransactionField.TRAN_DETAILS, "D1");
            local.writeTransactionField(handle, TransactionField.TRAN_AMOUNT, "        1.00 ");

            assertThat(StatementHtmlWriterTest.this.emitted).hasSize(12);
            assertThat(StatementHtmlWriterTest.this.emitted)
                    .allSatisfy(record ->
                            assertThat(record).hasSize(StatementHtmlWriter.RECORD_LENGTH));
            assertThat(handle.recordsWritten()).isEqualTo(12L);
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("COBOL STRING ... DELIMITED BY semantics")
    class StringDelimitedBySemantics {

        @Test
        @DisplayName("DELIMITED BY '  ' stops at the first two consecutive spaces")
        void twoSpaceDelimiterStopsAtTheFirstRun() {
            assertThat(StatementHtmlWriter.delimitedBy("AL  SMITH",
                    StatementHtmlWriter.TWO_SPACE_DELIMITER)).isEqualTo("AL");
        }

        @Test
        @DisplayName("DELIMITED BY '  ' transfers the whole item when there is no two-space run")
        void twoSpaceDelimiterTransfersEverythingWhenNotFound() {
            assertThat(StatementHtmlWriter.delimitedBy("ALSMITH",
                    StatementHtmlWriter.TWO_SPACE_DELIMITER)).isEqualTo("ALSMITH");
        }

        @Test
        @DisplayName("A SINGLE space is not the delimiter - this is not a trim")
        void aSingleSpaceIsNotTheDelimiter() {
            assertThat(StatementHtmlWriter.delimitedBy("AL SMITH",
                    StatementHtmlWriter.TWO_SPACE_DELIMITER)).isEqualTo("AL SMITH");
        }

        @Test
        @DisplayName("An all-spaces item transfers nothing: the run starts at position 1")
        void anAllSpacesItemTransfersNothing() {
            assertThat(StatementHtmlWriter.delimitedBy("    ",
                    StatementHtmlWriter.TWO_SPACE_DELIMITER)).isEmpty();
        }

        @Test
        @DisplayName("A one-character item shorter than the delimiter transfers whole")
        void anItemShorterThanTheDelimiterTransfersWhole() {
            assertThat(StatementHtmlWriter.delimitedBy(" ",
                    StatementHtmlWriter.TWO_SPACE_DELIMITER)).isEqualTo(" ");
            assertThat(StatementHtmlWriter.delimitedBy("",
                    StatementHtmlWriter.TWO_SPACE_DELIMITER)).isEmpty();
        }

        @Test
        @DisplayName("DELIMITED BY '*' on an asterisk-free item keeps the trailing spaces")
        void asteriskDelimiterKeepsTrailingSpaces() {
            assertThat(StatementHtmlWriter.delimitedBy("12345      ",
                    StatementHtmlWriter.ASTERISK_DELIMITER)).isEqualTo("12345      ");
        }

        @Test
        @DisplayName("DELIMITED BY '*' does stop at an asterisk, when there is one")
        void asteriskDelimiterStopsAtAnAsterisk() {
            assertThat(StatementHtmlWriter.delimitedBy("AB*CD",
                    StatementHtmlWriter.ASTERISK_DELIMITER)).isEqualTo("AB");
        }

        @Test
        @DisplayName("A partial delimiter match does not end the transfer")
        void aPartialMatchDoesNotEndTheTransfer() {
            // 'X Y Z ' has single spaces only, so the two-space scan matches at no position and must
            // fall through the inner-loop mismatch break at every one of them.
            assertThat(StatementHtmlWriter.delimitedBy("X Y Z ",
                    StatementHtmlWriter.TWO_SPACE_DELIMITER)).isEqualTo("X Y Z ");
        }

        @Test
        @DisplayName("DELIMITED BY SIZE is the identity, and is named so the call site reads as COBOL")
        void delimitedBySizeIsTheIdentity() {
            assertThat(StatementHtmlWriter.delimitedBySize("  ")).isEqualTo("  ");
            assertThat(StatementHtmlWriter.delimitedBySize("")).isEmpty();
        }

        @Test
        @DisplayName("An empty delimiter is rejected: no DELIMITED BY phrase can express it")
        void anEmptyDelimiterIsRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> StatementHtmlWriter.delimitedBy("ABC", ""))
                    .withMessageContaining("DELIMITED BY");
        }

        @Test
        @DisplayName("Null operands are rejected")
        void nullOperandsAreRejected() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> StatementHtmlWriter.delimitedBy(null, "*"));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> StatementHtmlWriter.delimitedBy("ABC", null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> StatementHtmlWriter.delimitedBySize(null));
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("The name line - CBSTM03A.CBL:L560-L568, a bare WRITE with no FROM")
    class NameLine {

        @Test
        @DisplayName("A name with two consecutive spaces is truncated at them")
        void aNameIsTruncatedAtTheFirstTwoSpaceRun() {
            StatementHtmlWriterTest.this.writer.writeNameLine(StatementHtmlWriterTest.this.file,
                    "MARGARET  GOLD");

            assertThat(StatementHtmlWriterTest.this.lastRecordText())
                    .isEqualTo(padded("<p style=\"font-size:16px\">MARGARET  </p>"));
        }

        @Test
        @DisplayName("A name with single spaces only transfers whole, then is padded to 50 and stopped there")
        void aNameWithSingleSpacesTransfersWhole() {
            StatementHtmlWriterTest.this.writer.writeNameLine(StatementHtmlWriterTest.this.file,
                    "MARGARET GOLD");

            // MOVE ST-NAME TO L23-NAME pads to 50, so the two-space run begins right after the text.
            assertThat(StatementHtmlWriterTest.this.lastRecordText())
                    .isEqualTo(padded("<p style=\"font-size:16px\">MARGARET GOLD  </p>"));
        }

        @Test
        @DisplayName("An all-spaces name contributes nothing at all")
        void anAllSpacesNameContributesNothing() {
            StatementHtmlWriterTest.this.writer.writeNameLine(StatementHtmlWriterTest.this.file, "");

            assertThat(StatementHtmlWriterTest.this.lastRecordText())
                    .isEqualTo(padded("<p style=\"font-size:16px\">  </p>"));
        }

        @Test
        @DisplayName("A name of exactly 50 characters with no two-space run transfers all 50")
        void aFiftyCharacterNameTransfersWhole() {
            String fifty = "A".repeat(StatementHtmlWriter.L23_NAME_LENGTH);
            StatementHtmlWriterTest.this.writer.writeNameLine(StatementHtmlWriterTest.this.file,
                    fifty);

            assertThat(StatementHtmlWriterTest.this.lastRecordText())
                    .isEqualTo(padded("<p style=\"font-size:16px\">" + fifty + "  </p>"));
        }

        @Test
        @DisplayName("ST-NAME PIC X(75) is truncated on the RIGHT to L23-NAME PIC X(50) first")
        void stNameIsTruncatedToFiftyCharactersFirst() {
            String seventyFive = "B".repeat(75);
            StatementHtmlWriterTest.this.writer.writeNameLine(StatementHtmlWriterTest.this.file,
                    seventyFive);

            assertThat(StatementHtmlWriterTest.this.lastRecordText()).isEqualTo(padded(
                    "<p style=\"font-size:16px\">"
                            + "B".repeat(StatementHtmlWriter.L23_NAME_LENGTH) + "  </p>"));
        }

        @Test
        @DisplayName("The record area holds the composed line, because the WRITE had no FROM clause")
        void theRecordAreaHoldsTheComposedLine() {
            StatementHtmlWriterTest.this.writer.writeNameLine(StatementHtmlWriterTest.this.file,
                    "X");

            assertThat(StatementHtmlWriterTest.this.file.recordArea().toByteArray())
                    .isEqualTo(StatementHtmlWriterTest.this.emitted.get(0));
        }

        @Test
        @DisplayName("MOVE SPACES clears the receiver, so a long line never leaves a tail behind")
        void moveSpacesClearsTheReceiverBetweenRecords() {
            StatementHtmlWriter local = StatementHtmlWriterTest.this.writer;
            HtmlStatementFile handle = StatementHtmlWriterTest.this.file;

            local.writeNameLine(handle, "Z".repeat(StatementHtmlWriter.L23_NAME_LENGTH));
            local.writeNameLine(handle, "Q");

            assertThat(StatementHtmlWriterTest.this.allRecordText().get(1))
                    .isEqualTo(padded("<p style=\"font-size:16px\">Q  </p>"));
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("The address lines - CBSTM03A.CBL:L569-L592")
    class AddressLines {

        @Test
        @DisplayName("ST-ADD1 and ST-ADD2 send from PIC X(50); ST-ADD3 from PIC X(80)")
        void theDeclaredWidthsAreFiftyFiftyAndEighty() {
            assertThat(AddressField.ADDRESS_LINE_1.declaredLength()).isEqualTo(50);
            assertThat(AddressField.ADDRESS_LINE_2.declaredLength()).isEqualTo(50);
            assertThat(AddressField.ADDRESS_LINE_3.declaredLength()).isEqualTo(80);
            assertThat(AddressField.ADDRESS_LINE_1.cobolName()).isEqualTo("ST-ADD1");
            assertThat(AddressField.ADDRESS_LINE_2.cobolName()).isEqualTo("ST-ADD2");
            assertThat(AddressField.ADDRESS_LINE_3.cobolName()).isEqualTo("ST-ADD3");
        }

        @Test
        @DisplayName("An address is wrapped and stopped at its first two-space run")
        void anAddressIsWrappedAndStopped() {
            StatementHtmlWriterTest.this.writer.writeAddressLine(
                    StatementHtmlWriterTest.this.file, AddressField.ADDRESS_LINE_1,
                    "410 TERRY AVE N");

            assertThat(StatementHtmlWriterTest.this.lastRecordText())
                    .isEqualTo(padded("<p>410 TERRY AVE N  </p>"));
        }

        @Test
        @DisplayName("An address that already contains a two-space run is cut there")
        void anAddressWithAnInternalRunIsCutThere() {
            StatementHtmlWriterTest.this.writer.writeAddressLine(
                    StatementHtmlWriterTest.this.file, AddressField.ADDRESS_LINE_2,
                    "SUITE 5  BUILDING 2");

            assertThat(StatementHtmlWriterTest.this.lastRecordText())
                    .isEqualTo(padded("<p>SUITE 5  </p>"));
        }

        @Test
        @DisplayName("A blank address still yields a well-formed empty paragraph")
        void aBlankAddressYieldsAnEmptyParagraph() {
            StatementHtmlWriterTest.this.writer.writeAddressLine(
                    StatementHtmlWriterTest.this.file, AddressField.ADDRESS_LINE_3, "");

            assertThat(StatementHtmlWriterTest.this.lastRecordText())
                    .isEqualTo(padded("<p>  </p>"));
        }

        @Test
        @DisplayName("The 80-byte third line accepts its full width without overflowing 100")
        void theThirdLineAcceptsItsFullWidth() {
            String eighty = "C".repeat(80);
            StatementHtmlWriterTest.this.writer.writeAddressLine(
                    StatementHtmlWriterTest.this.file, AddressField.ADDRESS_LINE_3, eighty);

            assertThat(StatementHtmlWriterTest.this.lastRecordText())
                    .isEqualTo(padded("<p>" + eighty + "  </p>"));
            assertThat(StatementHtmlWriterTest.this.lastRecordText()).hasSize(100);
        }

        @Test
        @DisplayName("A value longer than the declared width is truncated on the right first")
        void anOverWideValueIsTruncatedOnTheRight() {
            StatementHtmlWriterTest.this.writer.writeAddressLine(
                    StatementHtmlWriterTest.this.file, AddressField.ADDRESS_LINE_1,
                    "D".repeat(60));

            assertThat(StatementHtmlWriterTest.this.lastRecordText())
                    .isEqualTo(padded("<p>" + "D".repeat(50) + "  </p>"));
        }

        @Test
        @DisplayName("The record area holds the moved line, because the WRITE had a FROM clause")
        void theRecordAreaHoldsTheMovedLine() {
            StatementHtmlWriterTest.this.writer.writeAddressLine(
                    StatementHtmlWriterTest.this.file, AddressField.ADDRESS_LINE_1, "X");

            assertThat(StatementHtmlWriterTest.this.file.addressLine().toByteArray())
                    .isEqualTo(StatementHtmlWriterTest.this.emitted.get(0));
            assertThat(StatementHtmlWriterTest.this.file.recordArea().toByteArray())
                    .isEqualTo(StatementHtmlWriterTest.this.emitted.get(0));
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("The basic-detail lines - CBSTM03A.CBL:L613-L633")
    class BasicDetailLines {

        @Test
        @DisplayName("Each label is exactly 24 characters, spaces counted from the source")
        void eachLabelIsTwentyFourCharacters() {
            assertThat(BasicDetail.ACCOUNT_ID.label()).isEqualTo("<p>Account ID         : ");
            assertThat(BasicDetail.CURRENT_BALANCE.label()).isEqualTo("<p>Current Balance    : ");
            assertThat(BasicDetail.FICO_SCORE.label()).isEqualTo("<p>FICO Score         : ");
            for (BasicDetail detail : BasicDetail.values()) {
                assertThat(detail.label()).hasSize(24);
            }
        }

        @Test
        @DisplayName("The value's declared width comes from the label identity")
        void theDeclaredWidthsAreTwentyThirteenAndTwenty() {
            assertThat(BasicDetail.ACCOUNT_ID.declaredLength()).isEqualTo(20);
            assertThat(BasicDetail.CURRENT_BALANCE.declaredLength()).isEqualTo(13);
            assertThat(BasicDetail.FICO_SCORE.declaredLength()).isEqualTo(20);
            assertThat(BasicDetail.ACCOUNT_ID.cobolName()).isEqualTo("ST-ACCT-ID");
            assertThat(BasicDetail.CURRENT_BALANCE.cobolName()).isEqualTo("ST-CURR-BAL");
            assertThat(BasicDetail.FICO_SCORE.cobolName()).isEqualTo("ST-FICO-SCORE");
        }

        @Test
        @DisplayName("DELIMITED BY '*' keeps the value's trailing spaces - the whole field transfers")
        void theValuesTrailingSpacesSurvive() {
            StatementHtmlWriterTest.this.writer.writeBasicDetail(
                    StatementHtmlWriterTest.this.file, BasicDetail.ACCOUNT_ID, "00000000011");

            assertThat(StatementHtmlWriterTest.this.lastRecordText()).isEqualTo(
                    padded("<p>Account ID         : 00000000011         </p>"));
        }

        @Test
        @DisplayName("The already-edited balance image is embedded verbatim, mask and sign included")
        void theEditedBalanceIsEmbeddedVerbatim() {
            StatementHtmlWriterTest.this.writer.writeBasicDetail(
                    StatementHtmlWriterTest.this.file, BasicDetail.CURRENT_BALANCE,
                    "000001234.56-");

            assertThat(StatementHtmlWriterTest.this.lastRecordText()).isEqualTo(
                    padded("<p>Current Balance    : 000001234.56-</p>"));
        }

        @Test
        @DisplayName("A blank value still transfers its 20 spaces, because nothing is trimmed")
        void aBlankValueTransfersItsPadding() {
            StatementHtmlWriterTest.this.writer.writeBasicDetail(
                    StatementHtmlWriterTest.this.file, BasicDetail.FICO_SCORE, "");

            assertThat(StatementHtmlWriterTest.this.lastRecordText()).isEqualTo(
                    padded("<p>FICO Score         : " + " ".repeat(20) + "</p>"));
        }

        @Test
        @DisplayName("The record area holds the moved HTML-BSIC-LN")
        void theRecordAreaHoldsTheMovedBasicLine() {
            StatementHtmlWriterTest.this.writer.writeBasicDetail(
                    StatementHtmlWriterTest.this.file, BasicDetail.ACCOUNT_ID, "1");

            assertThat(StatementHtmlWriterTest.this.file.basicLine().toByteArray())
                    .isEqualTo(StatementHtmlWriterTest.this.emitted.get(0));
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("The transaction lines - CBSTM03A.CBL:L686-L716")
    class TransactionLines {

        @Test
        @DisplayName("The declared widths are 16, 49 and 13")
        void theDeclaredWidthsAreSixteenFortyNineAndThirteen() {
            assertThat(TransactionField.TRAN_ID.declaredLength()).isEqualTo(16);
            assertThat(TransactionField.TRAN_DETAILS.declaredLength()).isEqualTo(49);
            assertThat(TransactionField.TRAN_AMOUNT.declaredLength()).isEqualTo(13);
            assertThat(TransactionField.TRAN_ID.cobolName()).isEqualTo("ST-TRANID");
            assertThat(TransactionField.TRAN_DETAILS.cobolName()).isEqualTo("ST-TRANDT");
            assertThat(TransactionField.TRAN_AMOUNT.cobolName()).isEqualTo("ST-TRANAMT");
        }

        @Test
        @DisplayName("A 16-character transaction identifier fills its field exactly")
        void theTransactionIdentifierFillsItsField() {
            StatementHtmlWriterTest.this.writer.writeTransactionField(
                    StatementHtmlWriterTest.this.file, TransactionField.TRAN_ID,
                    "2022071800000001");

            assertThat(StatementHtmlWriterTest.this.lastRecordText())
                    .isEqualTo(padded("<p>2022071800000001</p>"));
        }

        @Test
        @DisplayName("A description is right-padded to 49 and every space transfers")
        void theDescriptionIsPaddedToFortyNine() {
            StatementHtmlWriterTest.this.writer.writeTransactionField(
                    StatementHtmlWriterTest.this.file, TransactionField.TRAN_DETAILS, "GROCERIES");

            assertThat(StatementHtmlWriterTest.this.lastRecordText())
                    .isEqualTo(padded("<p>GROCERIES" + " ".repeat(40) + "</p>"));
        }

        @Test
        @DisplayName("A description longer than 49 characters is truncated on the right")
        void anOverLongDescriptionIsTruncatedOnTheRight() {
            StatementHtmlWriterTest.this.writer.writeTransactionField(
                    StatementHtmlWriterTest.this.file, TransactionField.TRAN_DETAILS,
                    "E".repeat(100));

            assertThat(StatementHtmlWriterTest.this.lastRecordText())
                    .isEqualTo(padded("<p>" + "E".repeat(49) + "</p>"));
        }

        @Test
        @DisplayName("The already-edited amount image is embedded verbatim, leading spaces included")
        void theEditedAmountIsEmbeddedVerbatim() {
            StatementHtmlWriterTest.this.writer.writeTransactionField(
                    StatementHtmlWriterTest.this.file, TransactionField.TRAN_AMOUNT,
                    "     1234.56 ");

            assertThat(StatementHtmlWriterTest.this.lastRecordText())
                    .isEqualTo(padded("<p>     1234.56 </p>"));
        }

        @Test
        @DisplayName("The record area holds the moved HTML-TRAN-LN")
        void theRecordAreaHoldsTheMovedTransactionLine() {
            StatementHtmlWriterTest.this.writer.writeTransactionField(
                    StatementHtmlWriterTest.this.file, TransactionField.TRAN_AMOUNT, "1");

            assertThat(StatementHtmlWriterTest.this.file.transactionLine().toByteArray())
                    .isEqualTo(StatementHtmlWriterTest.this.emitted.get(0));
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("HTML-L11 - the account heading, CBSTM03A.CBL:L529-L530")
    class AccountHeading {

        @Test
        @DisplayName("The group is 34 + 20 + 5 = 59 declared bytes")
        void theGroupIsFiftyNineBytes() {
            assertThat(StatementHtmlWriter.HTML_L11_LENGTH).isEqualTo(59);
            assertThat(StatementHtmlWriter.ACCOUNT_HEADING_PREFIX).hasSize(34);
            assertThat(StatementHtmlWriter.ACCOUNT_HEADING_SUFFIX).hasSize(5);
            assertThat(StatementHtmlWriter.L11_ACCT_LENGTH).isEqualTo(20);
            assertThat(StatementHtmlWriter.HTML_L11_LAYOUT.recordLength()).isEqualTo(59);
        }

        @Test
        @DisplayName("The 34-character FILLER ends with a space, inside the literal")
        void theFillerLiteralEndsWithASpace() {
            assertThat(StatementHtmlWriter.ACCOUNT_HEADING_PREFIX)
                    .isEqualTo("<h3>Statement for Account Number: ")
                    .endsWith(": ");
        }

        @Test
        @DisplayName("PIC 9(11) into PIC X(20) is left-justified with leading zeros kept")
        void theAccountIdentifierIsLeftJustifiedWithLeadingZeros() {
            StatementHtmlWriterTest.this.writer.writeAccountHeading(
                    StatementHtmlWriterTest.this.file, "00000000001");

            assertThat(StatementHtmlWriterTest.this.lastRecordText()).isEqualTo(
                    padded("<h3>Statement for Account Number: 00000000001         </h3>"));
        }

        @Test
        @DisplayName("A blank identifier still leaves the two FILLERs intact")
        void aBlankIdentifierLeavesTheFillersIntact() {
            StatementHtmlWriterTest.this.writer.writeAccountHeading(
                    StatementHtmlWriterTest.this.file, "");

            assertThat(StatementHtmlWriterTest.this.lastRecordText()).isEqualTo(padded(
                    "<h3>Statement for Account Number: " + " ".repeat(20) + "</h3>"));
        }

        @Test
        @DisplayName("The FILLERs are re-initialised on every call, so no previous value survives")
        void theFillersAreReinitialisedEveryCall() {
            StatementHtmlWriter local = StatementHtmlWriterTest.this.writer;
            HtmlStatementFile handle = StatementHtmlWriterTest.this.file;

            local.writeAccountHeading(handle, "99999999999");
            local.writeAccountHeading(handle, "1");

            assertThat(StatementHtmlWriterTest.this.allRecordText().get(1)).isEqualTo(padded(
                    "<h3>Statement for Account Number: 1                   </h3>"));
        }

        @Test
        @DisplayName("An over-wide identifier is truncated on the right to 20 characters")
        void anOverWideIdentifierIsTruncatedOnTheRight() {
            StatementHtmlWriterTest.this.writer.writeAccountHeading(
                    StatementHtmlWriterTest.this.file, "9".repeat(30));

            assertThat(StatementHtmlWriterTest.this.lastRecordText()).isEqualTo(padded(
                    "<h3>Statement for Account Number: " + "9".repeat(20) + "</h3>"));
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("HTML-L23 - declared but never written, preserved rather than deleted")
    class DeclaredButUnwrittenNameGroup {

        @Test
        @DisplayName("The group is 26 + 50 = 76 declared bytes")
        void theGroupIsSeventySixBytes() {
            assertThat(StatementHtmlWriter.HTML_L23_LENGTH).isEqualTo(76);
            assertThat(StatementHtmlWriter.STYLED_PARAGRAPH_OPEN_TAG).hasSize(26);
            assertThat(StatementHtmlWriter.L23_NAME_LENGTH).isEqualTo(50);
            assertThat(StatementHtmlWriter.HTML_L23_LAYOUT.recordLength()).isEqualTo(76);
        }

        @Test
        @DisplayName("It materialises as its declared 76 bytes, L23-NAME padded to 50")
        void itMaterialisesAsSeventySixBytes() {
            byte[] group = StatementHtmlWriterTest.this.writer
                    .composeNameParagraphGroup("MARGARET GOLD");

            assertThat(group).hasSize(76);
            assertThat(new String(group, StandardCharsets.US_ASCII)).isEqualTo(
                    "<p style=\"font-size:16px\">MARGARET GOLD" + " ".repeat(37));
        }

        @Test
        @DisplayName("It is genuinely different from the STRING-built line: no </p> and full padding")
        void itDiffersFromTheStringBuiltLine() {
            String group = new String(StatementHtmlWriterTest.this.writer
                    .composeNameParagraphGroup("A"), StandardCharsets.US_ASCII);
            StatementHtmlWriterTest.this.writer.writeNameLine(StatementHtmlWriterTest.this.file,
                    "A");

            assertThat(group).doesNotContain(StatementHtmlWriter.PARAGRAPH_CLOSE_TAG);
            assertThat(StatementHtmlWriterTest.this.lastRecordText())
                    .contains(StatementHtmlWriter.PARAGRAPH_CLOSE_TAG);
            assertThat(group).isNotEqualTo(StatementHtmlWriterTest.this.lastRecordText());
        }

        @Test
        @DisplayName("A name over 50 characters is truncated on the right, exactly as MOVE does")
        void anOverWideNameIsTruncated() {
            byte[] group = StatementHtmlWriterTest.this.writer
                    .composeNameParagraphGroup("F".repeat(75));

            assertThat(new String(group, StandardCharsets.US_ASCII))
                    .isEqualTo("<p style=\"font-size:16px\">" + "F".repeat(50));
        }

        @Test
        @DisplayName("A null name is rejected")
        void aNullNameIsRejected() {
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                    () -> StatementHtmlWriterTest.this.writer.composeNameParagraphGroup(null));
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("No escaping and no trimming - practice B6")
    class NoEscapingAndNoTrimming {

        @Test
        @DisplayName("A customer name containing < > & is emitted raw, byte for byte")
        void aNameWithMarkupCharactersIsEmittedRaw() {
            StatementHtmlWriterTest.this.writer.writeNameLine(StatementHtmlWriterTest.this.file,
                    "<b>A & B</b>");

            String record = StatementHtmlWriterTest.this.lastRecordText();
            assertThat(record)
                    .isEqualTo(padded("<p style=\"font-size:16px\"><b>A & B</b>  </p>"));
            assertThat(record).doesNotContain("&lt;").doesNotContain("&gt;")
                    .doesNotContain("&amp;").doesNotContain("&quot;").doesNotContain("&#");
        }

        @Test
        @DisplayName("An address containing a quotation mark and an ampersand is emitted raw")
        void anAddressWithMarkupCharactersIsEmittedRaw() {
            StatementHtmlWriterTest.this.writer.writeAddressLine(
                    StatementHtmlWriterTest.this.file, AddressField.ADDRESS_LINE_1,
                    "\"O'HARA\" & SONS");

            assertThat(StatementHtmlWriterTest.this.lastRecordText())
                    .isEqualTo(padded("<p>\"O'HARA\" & SONS  </p>"));
        }

        @Test
        @DisplayName("A basic-detail value containing markup is emitted raw and untrimmed")
        void aBasicDetailValueWithMarkupIsEmittedRaw() {
            StatementHtmlWriterTest.this.writer.writeBasicDetail(
                    StatementHtmlWriterTest.this.file, BasicDetail.FICO_SCORE, "<script>");

            assertThat(StatementHtmlWriterTest.this.lastRecordText()).isEqualTo(
                    padded("<p>FICO Score         : <script>" + " ".repeat(12) + "</p>"));
        }

        @Test
        @DisplayName("A transaction description containing markup is emitted raw")
        void aTransactionDescriptionWithMarkupIsEmittedRaw() {
            StatementHtmlWriterTest.this.writer.writeTransactionField(
                    StatementHtmlWriterTest.this.file, TransactionField.TRAN_DETAILS, "A<B>C&D");

            assertThat(StatementHtmlWriterTest.this.lastRecordText())
                    .isEqualTo(padded("<p>A<B>C&D" + " ".repeat(42) + "</p>"));
        }

        @Test
        @DisplayName("Nothing is masked or redacted: the account identifier appears in full")
        void nothingIsMaskedOrRedacted() {
            StatementHtmlWriterTest.this.writer.writeBasicDetail(
                    StatementHtmlWriterTest.this.file, BasicDetail.ACCOUNT_ID, "12345678901");

            assertThat(StatementHtmlWriterTest.this.lastRecordText())
                    .contains("12345678901")
                    .doesNotContain("*")
                    .doesNotContain("REDACTED");
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("Sink injectability, ordering and lifecycle")
    class SinkAndLifecycle {

        @Test
        @DisplayName("Records reach the sink in exact call order, nothing reordered or deduplicated")
        void recordsReachTheSinkInCallOrder() {
            StatementHtmlWriter local = StatementHtmlWriterTest.this.writer;
            HtmlStatementFile handle = StatementHtmlWriterTest.this.file;

            local.writeFixedLine(handle, HtmlFixedLine.HTML_LTRS);
            local.writeFixedLine(handle, HtmlFixedLine.HTML_LTRS);
            local.writeFixedLine(handle, HtmlFixedLine.HTML_LTDE);
            local.writeFixedLine(handle, HtmlFixedLine.HTML_LTRE);

            assertThat(StatementHtmlWriterTest.this.allRecordText()).containsExactly(
                    padded("<tr>"), padded("<tr>"), padded("</td>"), padded("</tr>"));
        }

        @Test
        @DisplayName("A fresh handle starts open, at zero records, with the sink's open status")
        void aFreshHandleStartsOpen() {
            assertThat(StatementHtmlWriterTest.this.file.isOpen()).isTrue();
            assertThat(StatementHtmlWriterTest.this.file.recordsWritten()).isZero();
            assertThat(StatementHtmlWriterTest.this.file.openStatus()).isEqualTo(FileStatus.OK);
            assertThat(StatementHtmlWriterTest.this.file.sink()).isNotNull();
            assertThat(StatementHtmlWriterTest.this.file.fixedLine().recordLength()).isEqualTo(100);
            assertThat(StatementHtmlWriterTest.this.file.accountHeadingLine().recordLength())
                    .isEqualTo(59);
        }

        @Test
        @DisplayName("A non-OK open status is recorded, not acted on: CBSTM03A:L293 has no guard")
        void aNonOkOpenStatusIsRecordedNotActedOn() {
            HtmlStatementFile handle = StatementHtmlWriterTest.this.writer.open(
                    new StatusReportingSink(FileStatus.OK, "35", FileStatus.OK));

            assertThat(handle.openStatus()).isEqualTo("35");
            assertThat(handle.isOpen()).isTrue();
        }

        @Test
        @DisplayName("close returns the sink's own close status as an outcome")
        void closeReturnsTheSinksCloseStatus() {
            HtmlStatementFile handle = StatementHtmlWriterTest.this.writer.open(
                    new StatusReportingSink(FileStatus.OK, FileStatus.OK,
                            StatementHtmlWriter.PERMANENT_ERROR_STATUS));

            assertThat(StatementHtmlWriterTest.this.writer.close(handle))
                    .isEqualTo(FileStatus.Outcome.OTHER);
            assertThat(handle.isOpen()).isFalse();
        }

        @Test
        @DisplayName("A clean close reports OK")
        void aCleanCloseReportsOk() {
            assertThat(StatementHtmlWriterTest.this.writer.close(StatementHtmlWriterTest.this.file))
                    .isEqualTo(FileStatus.Outcome.OK);
        }

        @Test
        @DisplayName("A second close is refused: CBSTM03A closes once, at L339")
        void aSecondCloseIsRefused() {
            StatementHtmlWriterTest.this.writer.close(StatementHtmlWriterTest.this.file);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> StatementHtmlWriterTest.this.writer
                            .close(StatementHtmlWriterTest.this.file))
                    .withMessageContaining("closed")
                    .withMessageContaining("L339");
        }

        @Test
        @DisplayName("Every write is refused after close")
        void everyWriteIsRefusedAfterClose() {
            StatementHtmlWriter local = StatementHtmlWriterTest.this.writer;
            HtmlStatementFile handle = StatementHtmlWriterTest.this.file;
            local.close(handle);

            assertThatExceptionOfType(IllegalStateException.class).isThrownBy(
                    () -> local.writeFixedLine(handle, HtmlFixedLine.HTML_L01));
            assertThatExceptionOfType(IllegalStateException.class).isThrownBy(
                    () -> local.writeAccountHeading(handle, "1"));
            assertThatExceptionOfType(IllegalStateException.class).isThrownBy(
                    () -> local.writeNameLine(handle, "A"));
            assertThatExceptionOfType(IllegalStateException.class).isThrownBy(
                    () -> local.writeAddressLine(handle, AddressField.ADDRESS_LINE_1, "A"));
            assertThatExceptionOfType(IllegalStateException.class).isThrownBy(
                    () -> local.writeBasicDetail(handle, BasicDetail.ACCOUNT_ID, "A"));
            assertThatExceptionOfType(IllegalStateException.class).isThrownBy(
                    () -> local.writeTransactionField(handle, TransactionField.TRAN_ID, "A"));
            assertThat(StatementHtmlWriterTest.this.emitted).isEmpty();
        }

        @Test
        @DisplayName("A null handle, sink, line, identity or value is rejected")
        void nullArgumentsAreRejected() {
            StatementHtmlWriter local = StatementHtmlWriterTest.this.writer;
            HtmlStatementFile handle = StatementHtmlWriterTest.this.file;

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> local.open((HtmlRecordSink) null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> local.close(null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> local.writeFixedLine(null, HtmlFixedLine.HTML_L01));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> local.writeFixedLine(handle, null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> local.writeAccountHeading(handle, null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> local.writeNameLine(handle, null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> local.writeAddressLine(handle, null, "A"));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> local.writeAddressLine(handle,
                            AddressField.ADDRESS_LINE_1, null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> local.writeBasicDetail(handle, null, "A"));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> local.writeBasicDetail(handle,
                            BasicDetail.ACCOUNT_ID, null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> local.writeTransactionField(handle, null, "A"));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> local.writeTransactionField(handle,
                            TransactionField.TRAN_ID, null));
        }

        @Test
        @DisplayName("A sink returning a null status from open, write or close is rejected")
        void aSinkReturningNullIsRejected() {
            StatementHtmlWriter local = StatementHtmlWriterTest.this.writer;

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> local.open(new StatusReportingSink(FileStatus.OK, null,
                            FileStatus.OK)))
                    .withMessageContaining("open()");

            HtmlStatementFile nullWrite = local.open(
                    new StatusReportingSink(null, FileStatus.OK, FileStatus.OK));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> local.writeFixedLine(nullWrite, HtmlFixedLine.HTML_L01))
                    .withMessageContaining("write(byte[])");

            HtmlStatementFile nullClose = local.open(
                    new StatusReportingSink(FileStatus.OK, FileStatus.OK, null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> local.close(nullClose))
                    .withMessageContaining("close()");
        }

        @Test
        @DisplayName("The default HtmlRecordSink open and close report OK without doing anything")
        void theDefaultOpenAndCloseReportOk() {
            HtmlRecordSink lambda = record -> FileStatus.OK;

            assertThat(lambda.open()).isEqualTo(FileStatus.OK);
            assertThat(lambda.close()).isEqualTo(FileStatus.OK);
        }

        @Test
        @DisplayName("A sink reporting a failure is surfaced as Outcome.OTHER, not thrown")
        void aFailingSinkIsSurfacedAsAnOutcome() {
            HtmlStatementFile handle = StatementHtmlWriterTest.this.writer.open(
                    record -> StatementHtmlWriter.PERMANENT_ERROR_STATUS);

            assertThat(StatementHtmlWriterTest.this.writer
                    .writeFixedLine(handle, HtmlFixedLine.HTML_L01))
                    .isEqualTo(FileStatus.Outcome.OTHER);
        }

        @Test
        @DisplayName("Two handles are fully independent - no shared state anywhere")
        void twoHandlesAreIndependent() {
            List<byte[]> other = new ArrayList<>();
            HtmlStatementFile second = StatementHtmlWriterTest.this.writer.open(record -> {
                other.add(record);
                return FileStatus.OK;
            });

            StatementHtmlWriterTest.this.writer.writeFixedLine(
                    StatementHtmlWriterTest.this.file, HtmlFixedLine.HTML_L01);
            StatementHtmlWriterTest.this.writer.writeFixedLine(second, HtmlFixedLine.HTML_L80);

            assertThat(StatementHtmlWriterTest.this.emitted).hasSize(1);
            assertThat(other).hasSize(1);
            assertThat(StatementHtmlWriterTest.this.lastRecordText())
                    .isEqualTo(padded("<!DOCTYPE html>"));
            assertThat(new String(other.get(0), StandardCharsets.US_ASCII))
                    .isEqualTo(padded("</html>"));
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("The default JdbcTemplate-backed sink")
    class DefaultJdbcSink {

        @Test
        @DisplayName("It issues one single-column INSERT per record, with no DDL and no column name")
        void itIssuesOneSingleColumnInsertPerRecord() {
            JdbcHtmlRecordSink sink = StatementHtmlWriterTest.this.writer.defaultSink();

            assertThat(sink.dsname()).isEqualTo(TEST_DSNAME);
            assertThat(sink.insertStatement())
                    .isEqualTo("INSERT INTO \"" + TEST_DSNAME + "\" VALUES (?)");
            assertThat(sink.insertStatement())
                    .doesNotContain("CREATE").doesNotContain("ALTER").doesNotContain("(RECORD");
        }

        @Test
        @DisplayName("The dotted name is ONE delimited identifier, not a qualified SQL reference")
        void theDottedNameIsOneDelimitedIdentifier() {
            // This is the defect F07 named. The statement used to be assembled as prefix + raw name
            // + suffix, so a name like A.B.C.D reached the parser as a four-part qualified reference
            // and addressed - at best - nothing. Quoting makes it one object.
            JdbcHtmlRecordSink sink = StatementHtmlWriterTest.this.writer.defaultSink();

            assertThat(sink.insertStatement()).startsWith("INSERT INTO \"").contains("\" VALUES (?)");
            assertThat(sink.insertStatement()).doesNotContain("INTO " + TEST_DSNAME);
        }

        @Test
        @DisplayName("Both statement writers render the same dataset name identically (F07)")
        void bothWritersRenderTheSameNameIdentically() {
            // The two writers face one deployment driver whose syntax neither can exercise here, so
            // if they rendered a name differently at most one of them could be right. Bound to the
            // same name they must produce the same statement, character for character - and both are
            // pinned to DatasetRelation, so that equality is structural rather than coincidental.
            String shared = "TEST.M2.SHARED.SEQ";

            DatasetBindings textCatalogue = new DatasetBindings();
            textCatalogue.put("STMTFILE", new DatasetBinding(shared, "sequential", false, "FB",
                    8000, 80, null, null, null, null, null));
            StatementTextWriter textWriter = new StatementTextWriter(mock(JdbcTemplate.class),
                    StandardCharsets.US_ASCII, textCatalogue);

            JdbcHtmlRecordSink htmlSink = newWriter(mock(JdbcTemplate.class),
                    StatementHtmlWriter.RECORD_LENGTH, shared).defaultSink();

            assertThat(htmlSink.insertStatement()).isEqualTo(textWriter.insertStatement());
        }

        @Test
        @DisplayName("A successful write reports OK and clears any previous failure")
        void aSuccessfulWriteReportsOk() {
            when(StatementHtmlWriterTest.this.jdbcTemplate.update(anyString(),
                    any(PreparedStatementSetter.class))).thenReturn(1);
            JdbcHtmlRecordSink sink = StatementHtmlWriterTest.this.writer.defaultSink();

            assertThat(sink.write(new byte[StatementHtmlWriter.RECORD_LENGTH]))
                    .isEqualTo(FileStatus.OK);
            assertThat(sink.lastFailure()).isEmpty();
        }

        @Test
        @DisplayName("The record image is bound as bytes on parameter 1, as the sibling writer binds")
        void theRecordImageIsBoundAsBytes() throws SQLException {
            // Not merely a stylistic match with StatementTextWriter. An untyped argument leaves the
            // driver to pick a type for a byte[]; setBytes states it. The image is already the final
            // 100 bytes in the injected code page, so the two writers must ask for the same thing in
            // the same words or one of them can be silently re-encoded.
            DataSource dataSource = mock(DataSource.class);
            Connection connection = mock(Connection.class);
            PreparedStatement statement = mock(PreparedStatement.class);
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.prepareStatement(anyString())).thenReturn(statement);

            byte[] image = new byte[StatementHtmlWriter.RECORD_LENGTH];
            image[0] = (byte) 'X';

            assertThat(newWriter(new JdbcTemplate(dataSource), StatementHtmlWriter.RECORD_LENGTH,
                    TEST_DSNAME).defaultSink().write(image)).isEqualTo(FileStatus.OK);

            verify(connection).prepareStatement("INSERT INTO \"" + TEST_DSNAME + "\" VALUES (?)");
            verify(statement).setBytes(1, image);
            verify(statement).executeUpdate();
        }

        @Test
        @DisplayName("A DataAccessException becomes the permanent-error status and is retained")
        void aDataAccessExceptionBecomesThePermanentErrorStatus() {
            doThrow(new DataAccessResourceFailureException("no driver"))
                    .when(StatementHtmlWriterTest.this.jdbcTemplate)
                    .update(anyString(), any(PreparedStatementSetter.class));
            JdbcHtmlRecordSink sink = StatementHtmlWriterTest.this.writer.defaultSink();

            assertThat(sink.write(new byte[StatementHtmlWriter.RECORD_LENGTH]))
                    .isEqualTo(StatementHtmlWriter.PERMANENT_ERROR_STATUS);
            assertThat(FileStatus.outcomeOfStatus(StatementHtmlWriter.PERMANENT_ERROR_STATUS))
                    .isEqualTo(FileStatus.Outcome.OTHER);
            assertThat(sink.lastFailure()).containsInstanceOf(
                    DataAccessResourceFailureException.class);
        }

        @Test
        @DisplayName("open() uses the default sink, and its records go through the template")
        void openUsesTheDefaultSink() {
            when(StatementHtmlWriterTest.this.jdbcTemplate.update(anyString(),
                    any(PreparedStatementSetter.class))).thenReturn(1);
            HtmlStatementFile handle = StatementHtmlWriterTest.this.writer.open();

            assertThat(StatementHtmlWriterTest.this.writer
                    .writeFixedLine(handle, HtmlFixedLine.HTML_L01))
                    .isEqualTo(FileStatus.Outcome.OK);
            assertThat(handle.recordsWritten()).isEqualTo(1L);
        }

        @Test
        @DisplayName("A filesystem location - what the test profile binds - is refused, with guidance")
        void aFilesystemLocationIsRefused() {
            StatementHtmlWriter fileBound = newWriter(mock(JdbcTemplate.class),
                    StatementHtmlWriter.RECORD_LENGTH, "/tmp/carddemo/statement.html");

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(fileBound::defaultSink)
                    .withMessageContaining("cannot be addressed as a dataset")
                    .withMessageContaining("open(HtmlRecordSink)")
                    .withCauseInstanceOf(IllegalArgumentException.class);
        }

        @ParameterizedTest
        @DisplayName("Every shape a z/OS dataset name cannot take is refused, not merely quoted")
        @ValueSource(strings = {
            "A.B; DROP TABLE C",
            "A.B'C",
            "A.B\"C",
            "A.B C",
            "A.B,C",
            "A.B(C",
            "TOOLONGQUALIFIER.B",
            "9BAD.START",
            "A..B",
            "A.B.",
            "classpath:fixtures/statement.html",
        })
        void everyUnusableShapeIsRefused(final String candidate) {
            // A grammar, not an allowlist. The allowlist this replaced admitted parentheses and
            // repeated hyphens anywhere in a name, and admitted a nine-character qualifier, while its
            // own documentation claimed comment markers were refused (F20).
            StatementHtmlWriter bound = newWriter(mock(JdbcTemplate.class),
                    StatementHtmlWriter.RECORD_LENGTH, candidate);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(bound::defaultSink);
        }

        @Test
        @DisplayName("An empty configured name is refused")
        void anEmptyNameIsRefused() {
            StatementHtmlWriter blank = newWriter(mock(JdbcTemplate.class),
                    StatementHtmlWriter.RECORD_LENGTH, "");

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(blank::defaultSink)
                    .withMessageContaining("dsname is not configured");
        }

        @Test
        @DisplayName("An absent configured name is refused")
        void anAbsentNameIsRefused() {
            StatementHtmlWriter absent = newWriter(mock(JdbcTemplate.class),
                    StatementHtmlWriter.RECORD_LENGTH, null);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(absent::defaultSink)
                    .withMessageContaining("dsname is not configured");
        }

        @Test
        @DisplayName("A generation-qualified name is accepted: the grammar admits the (+n) suffix")
        void aGenerationQualifiedNameIsAccepted() {
            // The three generation-data-group outputs in application.yml carry this form, so the
            // grammar has to admit it. National characters and a hyphen inside a qualifier are legal
            // too; an underscore is not, which is why the earlier expectation for this case used a
            // name z/OS would itself have rejected.
            StatementHtmlWriter gdg = newWriter(mock(JdbcTemplate.class),
                    StatementHtmlWriter.RECORD_LENGTH, "TEST.M2-A.B$C@D#E.SEQ(+1)");

            assertThatCode(gdg::defaultSink).doesNotThrowAnyException();
            assertThat(gdg.defaultSink().insertStatement())
                    .isEqualTo("INSERT INTO \"TEST.M2-A.B$C@D#E.SEQ(+1)\" VALUES (?)");
        }

        @Test
        @DisplayName("Construction still succeeds under a fixture-backed binding, so G3 holds")
        void constructionStillSucceedsUnderAFixtureBackedBinding() {
            StatementHtmlWriter fileBound = newWriter(mock(JdbcTemplate.class),
                    StatementHtmlWriter.RECORD_LENGTH, "/tmp/carddemo/statement.html");

            assertThat(fileBound.recordLength()).isEqualTo(StatementHtmlWriter.RECORD_LENGTH);
            assertThatCode(() -> fileBound.open(record -> FileStatus.OK))
                    .doesNotThrowAnyException();
        }
    }

    // =============================================================================================

    /**
     * A {@link JdbcTemplate} whose {@code execute(ConnectionCallback)} really runs the callback
     * against a driver that reports {@code reportedQuote} as its identifier quote string.
     *
     * <p>The callback is invoked rather than stubbed away, so the body under test - ask the connection
     * for its metadata, ask the metadata for its quote - is the thing being exercised.
     *
     * @param reportedQuote what {@code getIdentifierQuoteString()} answers, or {@code null} to make
     *                      {@code getMetaData()} itself answer {@code null}
     * @return the template
     * @throws SQLException never; declared because the mocked driver methods declare it
     */
    private static JdbcTemplate driverReporting(final String reportedQuote) throws SQLException {
        JdbcTemplate template = mock(JdbcTemplate.class);
        Connection connection = mock(Connection.class);
        if (reportedQuote == null) {
            when(connection.getMetaData()).thenReturn(null);
        } else {
            DatabaseMetaData metaData = mock(DatabaseMetaData.class);
            when(metaData.getIdentifierQuoteString()).thenReturn(reportedQuote);
            when(connection.getMetaData()).thenReturn(metaData);
        }
        doAnswer(invocation -> {
            ConnectionCallback<?> callback = invocation.getArgument(0);
            return callback.doInConnection(connection);
        }).when(template).execute(ArgumentMatchers.<ConnectionCallback<String>>any());
        return template;
    }

    @Nested
    @DisplayName("The dataset name is one delimited identifier - F14, SQL statement structure")
    class DelimitedDatasetIdentifier {

        @Test
        @DisplayName("The configured name is quoted, so it contributes one identifier and no tokens")
        void theConfiguredNameIsQuoted() {
            JdbcHtmlRecordSink sink = StatementHtmlWriterTest.this.writer.defaultSink();

            assertThat(sink.insertStatement())
                    .isEqualTo("INSERT INTO \"TEST.M2.STATEMNT.HTML\" VALUES (?)");
            assertThat(sink.identifierQuote()).isEqualTo("\"");
        }

        @Test
        @DisplayName("A name shaped like a column reference cannot become one")
        void aNameShapedLikeAColumnReferenceCannotBecomeOne() {
            StatementHtmlWriter targeted = newWriter(mock(JdbcTemplate.class),
                    StatementHtmlWriter.RECORD_LENGTH, "TARGET(COLUMN)");

            // Two barriers stand between a configured name and a SQL statement, and this name never
            // reaches the second. The dataset-name grammar admits only a relative generation inside
            // parentheses, so "TARGET(COLUMN)" is refused outright rather than delimited: no statement
            // is composed at all, which is a stronger outcome than composing one that happens to be
            // safe. The escaping barrier itself is asserted directly further down, against a name that
            // carries a quotation mark.
            assertThatIllegalStateException().isThrownBy(targeted::defaultSink)
                    .withMessageContaining("cannot be addressed as a dataset")
                    .satisfies(refused -> assertThat(refused.getCause())
                            .hasMessageContaining("not a well-formed z/OS dataset name")
                            .hasMessageContaining("relative generation"));
        }

        @Test
        @DisplayName("A comment marker cannot comment out the rest of the statement")
        void aCommentMarkerCannotCommentOutTheStatement() {
            StatementHtmlWriter commented = newWriter(mock(JdbcTemplate.class),
                    StatementHtmlWriter.RECORD_LENGTH, "A.B--C");

            String statement = commented.defaultSink().insertStatement();

            assertThat(statement).isEqualTo("INSERT INTO \"A.B--C\" VALUES (?)");
            // The parameter is still there: nothing after the name has been commented away.
            assertThat(statement).endsWith(" VALUES (?)");
            // And the comment marker is bracketed by the delimiters rather than opening a comment.
            assertThat(statement.indexOf("--")).isGreaterThan(statement.indexOf('"'));
            assertThat(statement.indexOf("--")).isLessThan(statement.lastIndexOf('"'));
        }

        @Test
        @DisplayName("A generation-qualified name survives unchanged inside the delimiters")
        void aGenerationQualifiedNameSurvivesUnchanged() {
            // Every character here is one a z/OS qualifier admits - letters, digits, a hyphen and the
            // three national characters - followed by the relative generation the configuration
            // actually uses for the generation-data-group outputs. All of it survives the delimiting
            // verbatim, which is what a deployment confirming its own statement needs to see.
            StatementHtmlWriter gdg = newWriter(mock(JdbcTemplate.class),
                    StatementHtmlWriter.RECORD_LENGTH, "TEST.M2-A9$@#.SEQ(+1)");

            assertThat(gdg.defaultSink().insertStatement())
                    .isEqualTo("INSERT INTO \"TEST.M2-A9$@#.SEQ(+1)\" VALUES (?)");
        }

        @Test
        @DisplayName("An underscore is not a z/OS qualifier character, so such a name is refused")
        void anUnderscoreIsRefused() {
            // The grammar is a grammar rather than a list of forbidden characters, which is why it
            // refuses a name no platform would accept even though nothing about an underscore is
            // dangerous in SQL. A name that is not a dataset name is not addressed as one.
            StatementHtmlWriter underscored = newWriter(mock(JdbcTemplate.class),
                    StatementHtmlWriter.RECORD_LENGTH, "TEST.M2_A.SEQ");

            assertThatIllegalStateException().isThrownBy(underscored::defaultSink)
                    .withMessageContaining("cannot be addressed as a dataset");
        }

        @Test
        @DisplayName("The driver's own quote character is used when it reports one")
        void theDriversOwnQuoteCharacterIsUsed() throws SQLException {
            StatementHtmlWriter backtick = newWriter(driverReporting("`"),
                    StatementHtmlWriter.RECORD_LENGTH, TEST_DSNAME);

            JdbcHtmlRecordSink sink = backtick.defaultSink();

            assertThat(sink.identifierQuote()).isEqualTo("`");
            assertThat(sink.insertStatement())
                    .isEqualTo("INSERT INTO `TEST.M2.STATEMNT.HTML` VALUES (?)");
        }

        @Test
        @DisplayName("A driver that reports no quoting support gets the SQL-standard quote")
        void aDriverThatReportsNoQuotingSupportGetsTheStandardQuote() throws SQLException {
            // The JDBC contract defines a single space as "identifier quoting is not supported".
            StatementHtmlWriter unquoting = newWriter(driverReporting(" "),
                    StatementHtmlWriter.RECORD_LENGTH, TEST_DSNAME);

            assertThat(unquoting.defaultSink().identifierQuote()).isEqualTo("\"");
        }

        @Test
        @DisplayName("A driver whose metadata is absent gets the SQL-standard quote")
        void aDriverWhoseMetadataIsAbsentGetsTheStandardQuote() throws SQLException {
            StatementHtmlWriter noMetadata = newWriter(driverReporting(null),
                    StatementHtmlWriter.RECORD_LENGTH, TEST_DSNAME);

            assertThat(noMetadata.defaultSink().identifierQuote()).isEqualTo("\"");
        }

        @Test
        @DisplayName("An unreachable driver still composes a delimited statement, and does not throw")
        void anUnreachableDriverStillComposesADelimitedStatement() {
            JdbcTemplate unreachable = mock(JdbcTemplate.class);
            doThrow(new DataAccessResourceFailureException("no driver"))
                    .when(unreachable).execute(ArgumentMatchers.<ConnectionCallback<String>>any());
            StatementHtmlWriter writer = newWriter(unreachable,
                    StatementHtmlWriter.RECORD_LENGTH, TEST_DSNAME);

            JdbcHtmlRecordSink sink = writer.defaultSink();

            assertThat(sink.identifierQuote()).isEqualTo("\"");
            assertThat(sink.insertStatement())
                    .isEqualTo("INSERT INTO \"TEST.M2.STATEMNT.HTML\" VALUES (?)");
        }

        @Test
        @DisplayName("A quote character inside the name is doubled, the SQL-standard escape")
        void aQuoteCharacterInsideTheNameIsDoubled() {
            // The allowlist refuses a quotation mark in a configured name, so this asserts the
            // escape directly - the second, independent barrier, on the assumption the first is gone.
            assertThat(StatementHtmlWriter.insertStatement("ODD\"NAME", "\""))
                    .isEqualTo("INSERT INTO \"ODD\"\"NAME\" VALUES (?)");
            assertThat(StatementHtmlWriter.insertStatement("A`B", "`"))
                    .isEqualTo("INSERT INTO `A``B` VALUES (?)");
        }

        @Test
        @DisplayName("A name that tries to close its own identifier cannot escape it")
        void aNameThatTriesToCloseItsOwnIdentifierCannotEscapeIt() {
            String statement = StatementHtmlWriter.insertStatement("X\"; DROP TABLE Y; --", "\"");

            // The injected closing quote is doubled, so it is a literal character in the name rather
            // than the end of the identifier, and everything after it stays inside.
            assertThat(statement)
                    .isEqualTo("INSERT INTO \"X\"\"; DROP TABLE Y; --\" VALUES (?)");
            assertThat(statement).endsWith(" VALUES (?)");
        }

        @Test
        @DisplayName("The composer refuses a blank or absent quote rather than emitting a bare name")
        void theComposerRefusesABlankOrAbsentQuote() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> StatementHtmlWriter.insertStatement(TEST_DSNAME, " "))
                    .withMessageContaining("cannot be blank")
                    .withMessageContaining("normaliseIdentifierQuote");
            assertThatNullPointerException()
                    .isThrownBy(() -> StatementHtmlWriter.insertStatement(TEST_DSNAME, null))
                    .withMessageContaining("identifier quote");
            assertThatNullPointerException()
                    .isThrownBy(() -> StatementHtmlWriter.insertStatement(null, "\""))
                    .withMessageContaining("HTMLFILE");
        }

        @Test
        @DisplayName("Normalisation substitutes the standard quote for null and blank, and only those")
        void normalisationSubstitutesTheStandardQuoteForNullAndBlank() {
            assertThat(StatementHtmlWriter.normaliseIdentifierQuote(null)).isEqualTo("\"");
            assertThat(StatementHtmlWriter.normaliseIdentifierQuote(" ")).isEqualTo("\"");
            assertThat(StatementHtmlWriter.normaliseIdentifierQuote("")).isEqualTo("\"");
            assertThat(StatementHtmlWriter.normaliseIdentifierQuote("`")).isEqualTo("`");
            assertThat(StatementHtmlWriter.normaliseIdentifierQuote("\"")).isEqualTo("\"");
            assertThat(StatementHtmlWriter.normaliseIdentifierQuote("[")).isEqualTo("[");
        }

        @Test
        @DisplayName("Record bytes stay parameter-bound: the name is the only interpolated text")
        void recordBytesStayParameterBound() {
            when(StatementHtmlWriterTest.this.jdbcTemplate.update(anyString(), any(Object[].class)))
                    .thenReturn(1);
            JdbcHtmlRecordSink sink = StatementHtmlWriterTest.this.writer.defaultSink();
            byte[] record = new byte[StatementHtmlWriter.RECORD_LENGTH];
            java.util.Arrays.fill(record, (byte) '\'');

            assertThat(sink.write(record)).isEqualTo(FileStatus.OK);

            // One parameter placeholder, and no record byte anywhere in the statement text.
            assertThat(sink.insertStatement()).containsOnlyOnce("?");
            assertThat(sink.insertStatement()).doesNotContain("'");
        }

        @Test
        @DisplayName("The record width is untouched by the quoting change: still 100 bytes, gate G20")
        void theRecordWidthIsUntouched() {
            assertThat(StatementHtmlWriter.RECORD_LENGTH).isEqualTo(100);
            assertThat(StatementTextWriter.RECORD_LENGTH).isEqualTo(80);
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("Cross-check against the oracle: app/cbl/CBSTM03A.CBL itself")
    class ObjectionableTranscription {

        /** Where the COBOL sits, relative to the Maven module directory. */
        private static final String COBOL_SOURCE = "../cbl/CBSTM03A.CBL";

        /** {@code 88  HTML-Lxx ...} - the start of a condition-name declaration. */
        private static final Pattern CONDITION_NAME = Pattern.compile("88\\s+(\\S+)");

        @Test
        @DisplayName("Every literal matches the COBOL, re-extracted with the continuation rule")
        void everyLiteralMatchesTheCobolSource() throws IOException {
            Path source = Path.of(COBOL_SOURCE);
            Assumptions.assumeTrue(Files.isReadable(source),
                    "app/cbl/CBSTM03A.CBL is not reachable from this build directory");

            Map<String, String> fromSource = extractConditionNameLiterals(source);

            assertThat(fromSource).hasSize(HtmlFixedLine.values().length);
            for (HtmlFixedLine line : HtmlFixedLine.values()) {
                assertThat(fromSource)
                        .as("literal of %s", line.cobolName())
                        .containsEntry(line.cobolName(), line.literal());
            }
        }

        @Test
        @DisplayName("The COBOL FD declares PIC X(100), independently of the JCL")
        void theCobolFileDescriptionDeclaresOneHundred() throws IOException {
            Path source = Path.of(COBOL_SOURCE);
            Assumptions.assumeTrue(Files.isReadable(source),
                    "app/cbl/CBSTM03A.CBL is not reachable from this build directory");

            String text = Files.readString(source, StandardCharsets.ISO_8859_1);

            assertThat(text).contains("FD  HTML-FILE.");
            assertThat(text).contains("01  FD-HTMLFILE-REC         PIC X(100).");
        }

        /**
         * Re-extracts the {@code 88}-level literals of {@code HTML-FIXED-LN} from the COBOL source,
         * applying the fixed-format continuation rule: a hyphen in column 7 continues the literal,
         * which is reopened by a quotation mark, and an unterminated literal runs to column 72.
         *
         * @param source the COBOL file
         * @return condition name to literal, in declaration order
         * @throws IOException if the source cannot be read
         */
        private static Map<String, String> extractConditionNameLiterals(final Path source)
                throws IOException {
            List<String> lines = Files.readAllLines(source, StandardCharsets.ISO_8859_1);
            Map<String, String> literals = new LinkedHashMap<>();
            String currentName = null;
            StringBuilder currentLiteral = new StringBuilder();
            boolean unterminated = false;
            boolean inCatalogue = false;

            for (String raw : lines) {
                String line = raw.replace("\r", "");
                if (line.contains("HTML-FIXED-LN")) {
                    inCatalogue = true;
                    continue;
                }
                if (!inCatalogue) {
                    continue;
                }
                if (line.contains("05  HTML-L11.")) {
                    break;
                }
                char indicator = line.length() > 6 ? line.charAt(6) : SPACE;
                String body = line.length() > 7
                        ? line.substring(7, Math.min(72, line.length()))
                        : "";
                if (indicator == '-') {
                    int opening = body.indexOf('\'');
                    String rest = body.substring(opening + 1);
                    currentLiteral.append(rest, 0, rest.indexOf('\''));
                    unterminated = false;
                    continue;
                }
                Matcher matcher = CONDITION_NAME.matcher(body);
                if (matcher.find()) {
                    if (currentName != null && !unterminated) {
                        literals.put(currentName, currentLiteral.toString());
                    }
                    currentName = matcher.group(1);
                    currentLiteral = new StringBuilder();
                }
                int opening = body.indexOf('\'');
                if (opening >= 0) {
                    String rest = body.substring(opening + 1);
                    int closing = rest.indexOf('\'');
                    if (closing >= 0) {
                        currentLiteral.append(rest, 0, closing);
                        unterminated = false;
                    } else {
                        currentLiteral.append(rest);
                        unterminated = true;
                    }
                }
            }
            if (currentName != null) {
                literals.put(currentName, currentLiteral.toString());
            }
            return literals;
        }
    }

    // =============================================================================================

    /**
     * The expected literal of every {@link HtmlFixedLine} constant, written out independently of the
     * enum so a transcription error has to be made twice to survive.
     *
     * @return one argument pair per constant
     */
    static Stream<Arguments> expectedFixedLiterals() {
        return Stream.of(
                Arguments.of(HtmlFixedLine.HTML_L01, "<!DOCTYPE html>"),
                Arguments.of(HtmlFixedLine.HTML_L02, "<html lang=\"en\">"),
                Arguments.of(HtmlFixedLine.HTML_L03, "<head>"),
                Arguments.of(HtmlFixedLine.HTML_L04, "<meta charset=\"utf-8\">"),
                Arguments.of(HtmlFixedLine.HTML_L05, "<title>HTML Table Layout</title>"),
                Arguments.of(HtmlFixedLine.HTML_L06, "</head>"),
                Arguments.of(HtmlFixedLine.HTML_L07, "<body style=\"margin:0px;\">"),
                Arguments.of(HtmlFixedLine.HTML_L08, "<table  align=\"center\" frame=\"box\" "
                        + "style=\"width:70%; font:12px Segoe UI,sans-serif;\">"),
                Arguments.of(HtmlFixedLine.HTML_LTRS, "<tr>"),
                Arguments.of(HtmlFixedLine.HTML_LTRE, "</tr>"),
                Arguments.of(HtmlFixedLine.HTML_LTDS, "<td>"),
                Arguments.of(HtmlFixedLine.HTML_LTDE, "</td>"),
                Arguments.of(HtmlFixedLine.HTML_L10, "<td colspan=\"3\" style=\"padding:0px 5px;"
                        + "background-color:#1d1d96b3;\">"),
                Arguments.of(HtmlFixedLine.HTML_L15, "<td colspan=\"3\" style=\"padding:0px 5px;"
                        + "background-color:#FFAF33;\">"),
                Arguments.of(HtmlFixedLine.HTML_L16,
                        "<p style=\"font-size:16px\">Bank of XYZ</p>"),
                Arguments.of(HtmlFixedLine.HTML_L17, "<p>410 Terry Ave N</p>"),
                Arguments.of(HtmlFixedLine.HTML_L18, "<p>Seattle WA 99999</p>"),
                Arguments.of(HtmlFixedLine.HTML_L22_35, "<td colspan=\"3\" style=\""
                        + "padding:0px 5px;background-color:#f2f2f2;\">"),
                Arguments.of(HtmlFixedLine.HTML_L30_42, "<td colspan=\"3\" style=\""
                        + "padding:0px 5px;background-color:#33FFD1; text-align:center;\">"),
                Arguments.of(HtmlFixedLine.HTML_L31,
                        "<p style=\"font-size:16px\">Basic Details</p>"),
                Arguments.of(HtmlFixedLine.HTML_L43,
                        "<p style=\"font-size:16px\">Transaction Summary</p>"),
                Arguments.of(HtmlFixedLine.HTML_L47, "<td style=\"width:25%; padding:0px 5px; "
                        + "background-color:#33FF5E; text-align:left;\">"),
                Arguments.of(HtmlFixedLine.HTML_L48, "<p style=\"font-size:16px\">Tran ID</p>"),
                Arguments.of(HtmlFixedLine.HTML_L50, "<td style=\"width:55%; padding:0px 5px; "
                        + "background-color:#33FF5E; text-align:left;\">"),
                Arguments.of(HtmlFixedLine.HTML_L51,
                        "<p style=\"font-size:16px\">Tran Details</p>"),
                Arguments.of(HtmlFixedLine.HTML_L53, "<td style=\"width:20%; padding:0px 5px; "
                        + "background-color:#33FF5E; text-align:right;\">"),
                Arguments.of(HtmlFixedLine.HTML_L54, "<p style=\"font-size:16px\">Amount</p>"),
                Arguments.of(HtmlFixedLine.HTML_L58, "<td style=\"width:25%; padding:0px 5px; "
                        + "background-color:#f2f2f2; text-align:left;\">"),
                Arguments.of(HtmlFixedLine.HTML_L61, "<td style=\"width:55%; padding:0px 5px; "
                        + "background-color:#f2f2f2; text-align:left;\">"),
                Arguments.of(HtmlFixedLine.HTML_L64, "<td style=\"width:20%; padding:0px 5px; "
                        + "background-color:#f2f2f2; text-align:right;\">"),
                Arguments.of(HtmlFixedLine.HTML_L75, "<h3>End of Statement</h3>"),
                Arguments.of(HtmlFixedLine.HTML_L78, "</table>"),
                Arguments.of(HtmlFixedLine.HTML_L79, "</body>"),
                Arguments.of(HtmlFixedLine.HTML_L80, "</html>"));
    }

    /**
     * Right-space pads an expected line to the 100-byte record width, exactly as the COBOL
     * {@code PIC X(100)} receiver does.
     *
     * @param content the expected content
     * @return the content padded to {@link StatementHtmlWriter#RECORD_LENGTH} characters
     */
    private static String padded(final String content) {
        return content
                + String.valueOf(SPACE).repeat(StatementHtmlWriter.RECORD_LENGTH
                        - content.length());
    }

    /**
     * A sink that reports exactly the statuses it was constructed with, so the writer's handling of
     * each can be driven independently. Collects nothing; the tests that need the bytes use a lambda.
     */
    private static final class StatusReportingSink implements HtmlRecordSink {

        /** The status {@link #write(byte[])} reports. */
        private final String writeStatus;

        /** The status {@link #open()} reports. */
        private final String openStatus;

        /** The status {@link #close()} reports. */
        private final String closeStatus;

        /**
         * @param writeStatus what write reports, possibly {@code null}
         * @param openStatus  what open reports, possibly {@code null}
         * @param closeStatus what close reports, possibly {@code null}
         */
        StatusReportingSink(final String writeStatus, final String openStatus,
                            final String closeStatus) {
            this.writeStatus = writeStatus;
            this.openStatus = openStatus;
            this.closeStatus = closeStatus;
        }

        @Override
        public String write(final byte[] record) {
            return this.writeStatus;
        }

        @Override
        public String open() {
            return this.openStatus;
        }

        @Override
        public String close() {
            return this.closeStatus;
        }
    }
}
