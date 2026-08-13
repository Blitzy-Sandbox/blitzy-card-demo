package com.vsergeychik.carddemo.statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
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
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.common.DatasetRelation;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import javax.sql.DataSource;
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
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * Behavioural-parity tests for {@link StatementHtmlWriter}, the owner of the 100-byte {@code HTMLFILE}
 * record of {@code app/cbl/CBSTM03A.CBL}.
 */
@DisplayName("StatementHtmlWriter - the 100-byte HTMLFILE record of CBSTM03A")
class StatementHtmlWriterTest {
    private static final int WRONG_RECORD_LENGTH_FROM_PREDELETE_STEP = 80;

    private static final String TEST_DSNAME = "TEST.M2.STATEMNT.HTML";

    private static final char SPACE = ' ';

    private List<byte[]> emitted;

    private StatementHtmlWriter writer;

    private HtmlStatementFile file;

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

    private static StatementHtmlWriter newWriter(final JdbcTemplate template, final int recordLength,
                                                 final String dsname) {
        return newWriter(template, recordLength, dsname, RecordImageForm.CHARACTER);
    }

    private static StatementHtmlWriter newWriter(final JdbcTemplate template, final int recordLength,
                                                 final String dsname, final RecordImageForm form) {
        return new StatementHtmlWriter(template, StandardCharsets.US_ASCII,
                bindingsFor(dsname, recordLength), form);
    }

    private static DatasetBindings bindingsFor(final String dsname) {
        return bindingsFor(dsname, StatementHtmlWriter.RECORD_LENGTH);
    }

    private static DatasetBindings bindingsFor(final String dsname, final int recordLength) {
        return bindingsFor(dsname, recordLength, "FB");
    }

    private static DatasetBindings bindingsFor(final String dsname, final int recordLength,
                                               final String recordFormat) {
        DatasetBindings bindings = new DatasetBindings();
        bindings.put(StatementHtmlWriter.HTMLFILE_DD_NAME, new DatasetBinding(
                dsname, "sequential", false, recordFormat, StatementHtmlWriter.BLOCK_SIZE,
                recordLength, null, null, null, null, null));
        return bindings;
    }

    private String lastRecordText() {
        assertThat(this.emitted).isNotEmpty();
        return new String(this.emitted.get(this.emitted.size() - 1), StandardCharsets.US_ASCII);
    }

    private List<String> allRecordText() {
        return this.emitted.stream()
                .map(record -> new String(record, StandardCharsets.US_ASCII))
                .toList();
    }

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
        @DisplayName("Construction is refused when the binding declares anything but RECFM=FB, and "
                + "says whether the key was wrong or absent (gate G20)")
        void constructionIsRefusedForAnyOtherRecordFormat() {
            JdbcTemplate template = mock(JdbcTemplate.class);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new StatementHtmlWriter(template, StandardCharsets.US_ASCII,
                            bindingsFor(TEST_DSNAME, StatementHtmlWriter.RECORD_LENGTH, "V"),
                            RecordImageForm.CHARACTER))
                    .withMessageContaining("record-format is 'V'")
                    .withMessageContaining("RECFM=FB")
                    .withMessageContaining("L94");

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new StatementHtmlWriter(template, StandardCharsets.US_ASCII,
                            bindingsFor(TEST_DSNAME, StatementHtmlWriter.RECORD_LENGTH, null),
                            RecordImageForm.CHARACTER))
                    .withMessageContaining("record-format is absent");
        }

        @Test
        @DisplayName("The record format is accepted however configuration cases it")
        void theRecordFormatIsAcceptedCaseInsensitively() {
            JdbcTemplate template = mock(JdbcTemplate.class);

            assertThatCode(() -> new StatementHtmlWriter(template, StandardCharsets.US_ASCII,
                    bindingsFor(TEST_DSNAME, StatementHtmlWriter.RECORD_LENGTH, "fb"),
                    RecordImageForm.CHARACTER)).doesNotThrowAnyException();
            assertThatCode(() -> new StatementHtmlWriter(template, StandardCharsets.US_ASCII,
                    bindingsFor(TEST_DSNAME, StatementHtmlWriter.RECORD_LENGTH, "FB"),
                    RecordImageForm.CHARACTER)).doesNotThrowAnyException();
            assertThat(StatementHtmlWriter.RECORD_FORMAT).isEqualTo("FB");
        }

        @Test
        @DisplayName("A missing HTMLFILE binding is refused by the catalogue, not defaulted")
        void aMissingBindingIsRefused() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            DatasetBindings empty = new DatasetBindings();
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new StatementHtmlWriter(template, StandardCharsets.US_ASCII,
                            empty, RecordImageForm.CHARACTER))
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

        @Test
        @DisplayName("The dataset is addressed by the HTMLFILE binding key - CBSTM03A.CBL:L40, gate G46")
        void theDatasetIsAddressedByItsBindingKey() {
            assertThat(StatementHtmlWriter.HTMLFILE_DD_NAME).isEqualTo("HTMLFILE");
            assertThat(StatementHtmlWriterTest.this.writer.datasetBinding().dsname())
                    .isEqualTo(TEST_DSNAME);
        }

        @Test
        @DisplayName("No mainframe dataset name is known to this suite: it comes from configuration")
        void noMainframeDatasetNameIsKnownHere() {
            assertThat(TEST_DSNAME).doesNotContain("CARDDEMO").doesNotStartWith("AWS.");
            assertThat(StatementHtmlWriterTest.this.writer.datasetBinding().dsname())
                    .doesNotContain("CARDDEMO");
        }

        @Test
        @DisplayName("The COBOL names of the record area and the four lines are carried verbatim")
        void theCobolLineNamesAreCarriedVerbatim() {
            assertThat(StatementHtmlWriter.RECORD_AREA_NAME).isEqualTo("FD-HTMLFILE-REC");
            assertThat(StatementHtmlWriter.FIXED_LINE_NAME).isEqualTo("HTML-FIXED-LN");
            assertThat(StatementHtmlWriter.ADDRESS_LINE_NAME).isEqualTo("HTML-ADDR-LN");
            assertThat(StatementHtmlWriter.BASIC_LINE_NAME).isEqualTo("HTML-BSIC-LN");
            assertThat(StatementHtmlWriter.TRANSACTION_LINE_NAME).isEqualTo("HTML-TRAN-LN");
        }
    }

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

        @Test
        @DisplayName("HTML-L22-35 is ONE value written at L551 and again at L610, not two values")
        void htmlL22To35IsOneValueEmittedAtTwoPoints() {
            assertThat(HtmlFixedLine.isDeclared("HTML-L22")).isFalse();
            assertThat(HtmlFixedLine.isDeclared("HTML-L35")).isFalse();
            assertThat(HtmlFixedLine.isDeclared("HTML-L22-35")).isTrue();

            StatementHtmlWriterTest.this.writer.writeFixedLine(
                    StatementHtmlWriterTest.this.file, HtmlFixedLine.HTML_L22_35);
            StatementHtmlWriterTest.this.writer.writeFixedLine(
                    StatementHtmlWriterTest.this.file, HtmlFixedLine.HTML_L22_35);

            List<String> records = StatementHtmlWriterTest.this.allRecordText();
            assertThat(records).hasSize(2);
            assertThat(records.get(1)).isEqualTo(records.get(0));
            assertThat(records.get(0)).isEqualTo(padded(HtmlFixedLine.HTML_L22_35.literal()));
        }

        @Test
        @DisplayName("HTML-L30-42 is ONE value written at L600 and again at L640, not two values")
        void htmlL30To42IsOneValueEmittedAtTwoPoints() {
            assertThat(HtmlFixedLine.isDeclared("HTML-L30")).isFalse();
            assertThat(HtmlFixedLine.isDeclared("HTML-L42")).isFalse();
            assertThat(HtmlFixedLine.isDeclared("HTML-L30-42")).isTrue();

            StatementHtmlWriterTest.this.writer.writeFixedLine(
                    StatementHtmlWriterTest.this.file, HtmlFixedLine.HTML_L30_42);
            StatementHtmlWriterTest.this.writer.writeFixedLine(
                    StatementHtmlWriterTest.this.file, HtmlFixedLine.HTML_L30_42);

            List<String> records = StatementHtmlWriterTest.this.allRecordText();
            assertThat(records).hasSize(2);
            assertThat(records.get(1)).isEqualTo(records.get(0));
            assertThat(records.get(0)).isEqualTo(padded(HtmlFixedLine.HTML_L30_42.literal()));
        }

        @Test
        @DisplayName("HTML-L10 is reused too, at L441 in the footer and L526 in the header")
        void htmlL10IsAlsoReused() {
            StatementHtmlWriterTest.this.writer.writeFixedLine(
                    StatementHtmlWriterTest.this.file, HtmlFixedLine.HTML_L10);
            StatementHtmlWriterTest.this.writer.writeFixedLine(
                    StatementHtmlWriterTest.this.file, HtmlFixedLine.HTML_L10);

            List<String> records = StatementHtmlWriterTest.this.allRecordText();
            assertThat(records.get(1)).isEqualTo(records.get(0));
            assertThat(records.get(0))
                    .isEqualTo(padded("<td colspan=\"3\" style=\"padding:0px 5px;"
                            + "background-color:#1d1d96b3;\">"));
        }

        @Test
        @DisplayName("HTML-LTDS survives although CBSTM03A never selects it - practice B5")
        void htmlLtdsSurvivesAlthoughNeverSelected() {
            assertThat(HtmlFixedLine.isDeclared("HTML-LTDS")).isTrue();
            assertThat(HtmlFixedLine.ofCobolName("HTML-LTDS")).isSameAs(HtmlFixedLine.HTML_LTDS);
            assertThat(HtmlFixedLine.HTML_LTDS.literal()).isEqualTo("<td>");
            assertThat(HtmlFixedLine.HTML_LTDE.literal()).isEqualTo("</td>");

            StatementHtmlWriterTest.this.writer.writeFixedLine(
                    StatementHtmlWriterTest.this.file, HtmlFixedLine.HTML_LTDS);

            assertThat(StatementHtmlWriterTest.this.lastRecordText()).isEqualTo(padded("<td>"));
        }
    }

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

        @Test
        @DisplayName("One call emits exactly ONE record: nothing is ever wrapped onto a second")
        void oneCallEmitsExactlyOneRecord() {
            StatementHtmlWriter local = StatementHtmlWriterTest.this.writer;
            HtmlStatementFile handle = StatementHtmlWriterTest.this.file;

            local.writeFixedLine(handle, HtmlFixedLine.HTML_L08);
            assertThat(StatementHtmlWriterTest.this.emitted).hasSize(1);

            local.writeAccountHeading(handle, "9".repeat(20));
            assertThat(StatementHtmlWriterTest.this.emitted).hasSize(2);

            local.writeNameLine(handle, "N".repeat(75));
            assertThat(StatementHtmlWriterTest.this.emitted).hasSize(3);

            local.writeAddressLine(handle, AddressField.ADDRESS_LINE_3, "C".repeat(80));
            assertThat(StatementHtmlWriterTest.this.emitted).hasSize(4);

            local.writeBasicDetail(handle, BasicDetail.ACCOUNT_ID, "A".repeat(20));
            assertThat(StatementHtmlWriterTest.this.emitted).hasSize(5);

            local.writeTransactionField(handle, TransactionField.TRAN_DETAILS, "D".repeat(49));
            assertThat(StatementHtmlWriterTest.this.emitted).hasSize(6);

            assertThat(StatementHtmlWriterTest.this.emitted)
                    .allSatisfy(record ->
                            assertThat(record).hasSize(StatementHtmlWriter.RECORD_LENGTH));
            assertThat(handle.recordsWritten()).isEqualTo(6L);
        }

        @Test
        @DisplayName("No shape can overflow 100: the widest is the 80-byte third address line at 89")
        void noShapeCanOverflowTheRecord() {
            int nameLine = StatementHtmlWriter.STYLED_PARAGRAPH_OPEN_TAG.length()
                    + StatementHtmlWriter.L23_NAME_LENGTH
                    + StatementHtmlWriter.TWO_SPACE_SEPARATOR.length()
                    + StatementHtmlWriter.PARAGRAPH_CLOSE_TAG.length();
            int widestAddress = StatementHtmlWriter.PARAGRAPH_OPEN_TAG.length()
                    + AddressField.ADDRESS_LINE_3.declaredLength()
                    + StatementHtmlWriter.TWO_SPACE_SEPARATOR.length()
                    + StatementHtmlWriter.PARAGRAPH_CLOSE_TAG.length();
            int widestBasicDetail = BasicDetail.ACCOUNT_ID.label().length()
                    + BasicDetail.ACCOUNT_ID.declaredLength()
                    + StatementHtmlWriter.PARAGRAPH_CLOSE_TAG.length();
            int widestTransaction = StatementHtmlWriter.PARAGRAPH_OPEN_TAG.length()
                    + TransactionField.TRAN_DETAILS.declaredLength()
                    + StatementHtmlWriter.PARAGRAPH_CLOSE_TAG.length();

            assertThat(nameLine).isEqualTo(82);
            assertThat(widestAddress).isEqualTo(89);
            assertThat(widestBasicDetail).isEqualTo(48);
            assertThat(widestTransaction).isEqualTo(56);
            assertThat(StatementHtmlWriter.HTML_L11_LENGTH).isEqualTo(59);

            int widestShape = Math.max(Math.max(nameLine, widestAddress),
                    Math.max(widestBasicDetail, widestTransaction));
            assertThat(widestShape).isEqualTo(89)
                    .isLessThan(StatementHtmlWriter.RECORD_LENGTH);
            for (HtmlFixedLine line : HtmlFixedLine.values()) {
                assertThat(line.literalLength())
                        .as("literal %s must fit the PIC X(100) it is SET into", line.cobolName())
                        .isLessThanOrEqualTo(StatementHtmlWriter.RECORD_LENGTH);
            }
        }
    }

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
            assertThat(StatementHtmlWriter.delimitedBy("X Y Z ",
                    StatementHtmlWriter.TWO_SPACE_DELIMITER)).isEqualTo("X Y Z ");
        }

        @Test
        @DisplayName("A run at the very last scannable position is still found, not missed")
        void aRunAtTheLastPositionIsStillFound() {
            assertThat(StatementHtmlWriter.delimitedBy("ABC  ",
                    StatementHtmlWriter.TWO_SPACE_DELIMITER)).isEqualTo("ABC");
            assertThat(StatementHtmlWriter.delimitedBy("ABC ",
                    StatementHtmlWriter.TWO_SPACE_DELIMITER)).isEqualTo("ABC ");
            assertThat(StatementHtmlWriter.delimitedBy("  ABC",
                    StatementHtmlWriter.TWO_SPACE_DELIMITER)).isEmpty();
        }

        @Test
        @DisplayName("A delimiter longer than one character is matched as a SEQUENCE, not per byte")
        void aMultiCharacterDelimiterIsMatchedAsASequence() {
            assertThat(StatementHtmlWriter.delimitedBy("A B  C",
                    StatementHtmlWriter.TWO_SPACE_DELIMITER)).isEqualTo("A B");
            assertThat(StatementHtmlWriter.delimitedBy("A B  C",
                    StatementHtmlWriter.TWO_SPACE_DELIMITER)).isNotEqualTo("A");
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

        @Test
        @DisplayName("The three spans sit at absolute offsets 0..33, 34..53 and 54..58 - L213-L216")
        void theThreeSpansSitAtTheirDeclaredOffsets() {
            List<FieldSpan> spans = StatementHtmlWriter.HTML_L11_LAYOUT.spans();
            assertThat(spans).hasSize(3);

            FieldSpan leadingFiller = spans.get(0);
            assertThat(leadingFiller.name()).isEqualTo("FILLER");
            assertThat(leadingFiller.offset()).isZero();
            assertThat(leadingFiller.length()).isEqualTo(34);
            assertThat(leadingFiller.endOffsetExclusive()).isEqualTo(34);
            assertThat(leadingFiller.initialValue())
                    .isEqualTo(StatementHtmlWriter.ACCOUNT_HEADING_PREFIX);

            FieldSpan account = spans.get(1);
            assertThat(account.name()).isEqualTo(StatementHtmlWriter.L11_ACCT_FIELD_NAME);
            assertThat(account.offset()).isEqualTo(34);
            assertThat(account.length()).isEqualTo(StatementHtmlWriter.L11_ACCT_LENGTH);
            assertThat(account.endOffsetExclusive()).isEqualTo(54);

            FieldSpan trailingFiller = spans.get(2);
            assertThat(trailingFiller.name()).isEqualTo("FILLER");
            assertThat(trailingFiller.offset()).isEqualTo(54);
            assertThat(trailingFiller.length()).isEqualTo(5);
            assertThat(trailingFiller.endOffsetExclusive())
                    .isEqualTo(StatementHtmlWriter.HTML_L11_LENGTH);
            assertThat(trailingFiller.initialValue())
                    .isEqualTo(StatementHtmlWriter.ACCOUNT_HEADING_SUFFIX);

            assertThat(StatementHtmlWriter.HTML_L11_LAYOUT.storageSpans()).hasSize(3);
            assertThat(StatementHtmlWriter.HTML_L11_LAYOUT.redefinitions()).isEmpty();
            assertThat(StatementHtmlWriter.HTML_L11_LAYOUT.span(
                    StatementHtmlWriter.L11_ACCT_FIELD_NAME).offset()).isEqualTo(34);
        }

        @Test
        @DisplayName("The emitted record carries those spans at those offsets, then 41 pad bytes")
        void theEmittedRecordCarriesTheSpansAtTheirOffsets() {
            StatementHtmlWriterTest.this.writer.writeAccountHeading(
                    StatementHtmlWriterTest.this.file, "00000000011");

            String record = StatementHtmlWriterTest.this.lastRecordText();
            assertThat(record).hasSize(StatementHtmlWriter.RECORD_LENGTH);
            assertThat(record.substring(0, 34))
                    .isEqualTo(StatementHtmlWriter.ACCOUNT_HEADING_PREFIX);
            assertThat(record.substring(34, 54)).isEqualTo("00000000011         ");
            assertThat(record.substring(54, 59))
                    .isEqualTo(StatementHtmlWriter.ACCOUNT_HEADING_SUFFIX);
            assertThat(record.substring(59))
                    .isEqualTo(String.valueOf(SPACE).repeat(41))
                    .hasSize(41);
        }
    }

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

        @Test
        @DisplayName("The two spans sit at absolute offsets 0..25 and 26..75 - L218-L220")
        void theTwoSpansSitAtTheirDeclaredOffsets() {
            List<FieldSpan> spans = StatementHtmlWriter.HTML_L23_LAYOUT.spans();
            assertThat(spans).hasSize(2);

            FieldSpan filler = spans.get(0);
            assertThat(filler.name()).isEqualTo("FILLER");
            assertThat(filler.offset()).isZero();
            assertThat(filler.length()).isEqualTo(26);
            assertThat(filler.endOffsetExclusive()).isEqualTo(26);
            assertThat(filler.initialValue())
                    .isEqualTo(StatementHtmlWriter.STYLED_PARAGRAPH_OPEN_TAG);

            FieldSpan name = spans.get(1);
            assertThat(name.name()).isEqualTo(StatementHtmlWriter.L23_NAME_FIELD_NAME);
            assertThat(name.offset()).isEqualTo(26);
            assertThat(name.length()).isEqualTo(StatementHtmlWriter.L23_NAME_LENGTH);
            assertThat(name.endOffsetExclusive()).isEqualTo(StatementHtmlWriter.HTML_L23_LENGTH);

            assertThat(StatementHtmlWriter.HTML_L23_LENGTH).isEqualTo(26 + 50);
        }

        @Test
        @DisplayName("Materialising the group emits NOTHING: CBSTM03A never writes it - practice B5")
        void materialisingTheGroupEmitsNoRecord() {
            byte[] group = StatementHtmlWriterTest.this.writer
                    .composeNameParagraphGroup("MARGARET GOLD");

            assertThat(group).hasSize(StatementHtmlWriter.HTML_L23_LENGTH);
            assertThat(StatementHtmlWriterTest.this.emitted).isEmpty();
            assertThat(StatementHtmlWriterTest.this.file.recordsWritten()).isZero();
            StatementHtmlWriterTest.this.writer.composeNameParagraphGroup("A");
            StatementHtmlWriterTest.this.writer.composeNameParagraphGroup("");
            assertThat(StatementHtmlWriterTest.this.emitted).isEmpty();
            assertThat(StatementHtmlWriterTest.this.file.isOpen()).isTrue();
        }

        @Test
        @DisplayName("No public operation emits the group, and none can: every record is 100 bytes")
        void noPublicOperationEmitsTheGroup() {
            List<String> emitOperations = new ArrayList<>();
            for (Method method : StatementHtmlWriter.class.getDeclaredMethods()) {
                if (method.getName().startsWith("write")) {
                    emitOperations.add(method.getName());
                }
            }
            assertThat(emitOperations)
                    .isNotEmpty()
                    .containsExactlyInAnyOrder("writeFixedLine", "writeAccountHeading",
                            "writeNameLine", "writeAddressLine", "writeBasicDetail",
                            "writeTransactionField", "writeFrom");
            assertThat(emitOperations)
                    .noneMatch(name -> name.contains("Group") || name.contains("group"));

            StatementHtmlWriterTest.this.writer.writeNameLine(
                    StatementHtmlWriterTest.this.file, "MARGARET GOLD");
            assertThat(StatementHtmlWriterTest.this.emitted).hasSize(1);
            assertThat(StatementHtmlWriterTest.this.emitted.get(0))
                    .hasSize(StatementHtmlWriter.RECORD_LENGTH);
            assertThat(StatementHtmlWriter.HTML_L23_LENGTH)
                    .isNotEqualTo(StatementHtmlWriter.RECORD_LENGTH);
        }
    }

    @Nested
    @DisplayName("MOVE SPACES clears each scratch line - CBSTM03A.CBL:L561, L569, L613, L686")
    class ScratchBufferClearing {
        @Test
        @DisplayName("HTML-ADDR-LN: a 59-character line then a 10-character one leaves no tail")
        void theAddressLineIsClearedBetweenRecords() {
            StatementHtmlWriter local = StatementHtmlWriterTest.this.writer;
            HtmlStatementFile handle = StatementHtmlWriterTest.this.file;

            local.writeAddressLine(handle, AddressField.ADDRESS_LINE_1, "L".repeat(50));
            local.writeAddressLine(handle, AddressField.ADDRESS_LINE_1, "X");

            List<String> records = StatementHtmlWriterTest.this.allRecordText();
            assertThat(records.get(0)).isEqualTo(padded("<p>" + "L".repeat(50) + "  </p>"));
            assertThat(records.get(1))
                    .isEqualTo(padded("<p>X  </p>"))
                    .doesNotContain("L");
            assertThat(handle.addressLine().readString(10, 90))
                    .isEqualTo(String.valueOf(SPACE).repeat(90));
        }

        @Test
        @DisplayName("HTML-BSIC-LN: a 48-character line then a 41-character one leaves no tail")
        void theBasicLineIsClearedBetweenRecords() {
            StatementHtmlWriter local = StatementHtmlWriterTest.this.writer;
            HtmlStatementFile handle = StatementHtmlWriterTest.this.file;

            local.writeBasicDetail(handle, BasicDetail.ACCOUNT_ID, "9".repeat(20));
            local.writeBasicDetail(handle, BasicDetail.CURRENT_BALANCE, "000000012.34-");

            List<String> records = StatementHtmlWriterTest.this.allRecordText();
            assertThat(records.get(0)).isEqualTo(
                    padded("<p>Account ID         : " + "9".repeat(20) + "</p>"));
            assertThat(records.get(1))
                    .isEqualTo(padded("<p>Current Balance    : 000000012.34-</p>"))
                    .doesNotContain("9999");
            assertThat(handle.basicLine().readString(41, 59))
                    .isEqualTo(String.valueOf(SPACE).repeat(59));
        }

        @Test
        @DisplayName("HTML-TRAN-LN: a 56-character line then a 20-character one leaves no tail")
        void theTransactionLineIsClearedBetweenRecords() {
            StatementHtmlWriter local = StatementHtmlWriterTest.this.writer;
            HtmlStatementFile handle = StatementHtmlWriterTest.this.file;

            local.writeTransactionField(handle, TransactionField.TRAN_DETAILS, "D".repeat(49));
            local.writeTransactionField(handle, TransactionField.TRAN_AMOUNT, "       12.34 ");

            List<String> records = StatementHtmlWriterTest.this.allRecordText();
            assertThat(records.get(0)).isEqualTo(padded("<p>" + "D".repeat(49) + "</p>"));
            assertThat(records.get(1))
                    .isEqualTo(padded("<p>       12.34 </p>"))
                    .doesNotContain("D");
            assertThat(handle.transactionLine().readString(20, 80))
                    .isEqualTo(String.valueOf(SPACE).repeat(80));
        }

        @Test
        @DisplayName("The three buffers are independent: writing one never disturbs another")
        void theThreeBuffersAreIndependent() {
            StatementHtmlWriter local = StatementHtmlWriterTest.this.writer;
            HtmlStatementFile handle = StatementHtmlWriterTest.this.file;

            local.writeAddressLine(handle, AddressField.ADDRESS_LINE_1, "ADDRESS");
            local.writeBasicDetail(handle, BasicDetail.FICO_SCORE, "700");
            local.writeTransactionField(handle, TransactionField.TRAN_ID, "TRAN0000000000001");

            assertThat(handle.addressLine().readString(0, StatementHtmlWriter.RECORD_LENGTH))
                    .isEqualTo(padded("<p>ADDRESS  </p>"));
            assertThat(handle.basicLine().readString(0, StatementHtmlWriter.RECORD_LENGTH))
                    .isEqualTo(padded("<p>FICO Score         : 700"
                            + String.valueOf(SPACE).repeat(17) + "</p>"));
            assertThat(handle.transactionLine().readString(0, StatementHtmlWriter.RECORD_LENGTH))
                    .isEqualTo(padded("<p>TRAN000000000000</p>"));
        }

        @Test
        @DisplayName("Every buffer is 100 bytes wide - CBSTM03A.CBL:L47, L149, L221-L223")
        void everyBufferIsOneHundredBytesWide() {
            HtmlStatementFile handle = StatementHtmlWriterTest.this.file;

            assertThat(handle.recordArea().recordLength())
                    .isEqualTo(StatementHtmlWriter.RECORD_LENGTH);
            assertThat(handle.fixedLine().recordLength())
                    .isEqualTo(StatementHtmlWriter.RECORD_LENGTH);
            assertThat(handle.addressLine().recordLength())
                    .isEqualTo(StatementHtmlWriter.RECORD_LENGTH);
            assertThat(handle.basicLine().recordLength())
                    .isEqualTo(StatementHtmlWriter.RECORD_LENGTH);
            assertThat(handle.transactionLine().recordLength())
                    .isEqualTo(StatementHtmlWriter.RECORD_LENGTH);
            assertThat(handle.accountHeadingLine().recordLength())
                    .isEqualTo(StatementHtmlWriter.HTML_L11_LENGTH);
        }
    }

    @Nested
    @DisplayName("The dataset code page is not the payload's charset declaration - practice B8")
    class DatasetCodePageIsNotThePayloadCharset {
        private static final String EBCDIC_CODE_PAGE = "IBM037";

        @Test
        @DisplayName("HTML-L04 declares utf-8 as CONTENT: CBSTM03A.CBL:L153, transcribed not obeyed")
        void theMetaCharsetIsContentNotConfiguration() {
            assertThat(HtmlFixedLine.HTML_L04.literal()).isEqualTo("<meta charset=\"utf-8\">");
            assertThat(StatementHtmlWriterTest.this.writer.datasetCharset())
                    .isEqualTo(StandardCharsets.US_ASCII)
                    .isNotEqualTo(StandardCharsets.UTF_8);
        }

        @Test
        @DisplayName("The same literal is written in the INJECTED code page, not in the one it names")
        void theRecordIsWrittenInTheInjectedCodePage() {
            Charset ebcdic = Charset.forName(EBCDIC_CODE_PAGE);
            List<byte[]> ebcdicRecords = new ArrayList<>();
            StatementHtmlWriter ebcdicWriter = new StatementHtmlWriter(
                    mock(JdbcTemplate.class), ebcdic, bindingsFor(TEST_DSNAME),
                    RecordImageForm.CHARACTER);
            HtmlStatementFile ebcdicFile = ebcdicWriter.open(record -> {
                ebcdicRecords.add(record);
                return FileStatus.OK;
            });

            ebcdicWriter.writeFixedLine(ebcdicFile, HtmlFixedLine.HTML_L04);
            StatementHtmlWriterTest.this.writer.writeFixedLine(
                    StatementHtmlWriterTest.this.file, HtmlFixedLine.HTML_L04);

            byte[] asEbcdic = ebcdicRecords.get(0);
            byte[] asAscii = StatementHtmlWriterTest.this.emitted.get(0);

            assertThat(asEbcdic).hasSize(StatementHtmlWriter.RECORD_LENGTH);
            assertThat(asAscii).hasSize(StatementHtmlWriter.RECORD_LENGTH);
            assertThat(asEbcdic).isNotEqualTo(asAscii);
            assertThat(new String(asEbcdic, ebcdic))
                    .isEqualTo(padded(HtmlFixedLine.HTML_L04.literal()));
            assertThat(new String(asAscii, StandardCharsets.US_ASCII))
                    .isEqualTo(padded(HtmlFixedLine.HTML_L04.literal()));
        }

        @Test
        @DisplayName("The pad byte follows the code page too: EBCDIC space x'40', ASCII space x'20'")
        void thePadByteFollowsTheCodePage() {
            Charset ebcdic = Charset.forName(EBCDIC_CODE_PAGE);
            List<byte[]> ebcdicRecords = new ArrayList<>();
            StatementHtmlWriter ebcdicWriter = new StatementHtmlWriter(
                    mock(JdbcTemplate.class), ebcdic, bindingsFor(TEST_DSNAME),
                    RecordImageForm.CHARACTER);
            HtmlStatementFile ebcdicFile = ebcdicWriter.open(record -> {
                ebcdicRecords.add(record);
                return FileStatus.OK;
            });

            ebcdicWriter.writeFixedLine(ebcdicFile, HtmlFixedLine.HTML_L03);
            StatementHtmlWriterTest.this.writer.writeFixedLine(
                    StatementHtmlWriterTest.this.file, HtmlFixedLine.HTML_L03);

            assertThat(ebcdicRecords.get(0)[99]).isEqualTo((byte) 0x40);
            assertThat(StatementHtmlWriterTest.this.emitted.get(0)[99]).isEqualTo((byte) 0x20);
            assertThat(ebcdicFile.recordArea().spacePadByte()).isEqualTo((byte) 0x40);
            assertThat(StatementHtmlWriterTest.this.file.recordArea().spacePadByte())
                    .isEqualTo((byte) 0x20);
        }

        @Test
        @DisplayName("Composed lines follow the code page as well, not only the fixed literals")
        void composedLinesFollowTheCodePageToo() {
            Charset ebcdic = Charset.forName(EBCDIC_CODE_PAGE);
            List<byte[]> ebcdicRecords = new ArrayList<>();
            StatementHtmlWriter ebcdicWriter = new StatementHtmlWriter(
                    mock(JdbcTemplate.class), ebcdic, bindingsFor(TEST_DSNAME),
                    RecordImageForm.CHARACTER);
            HtmlStatementFile ebcdicFile = ebcdicWriter.open(record -> {
                ebcdicRecords.add(record);
                return FileStatus.OK;
            });

            ebcdicWriter.writeBasicDetail(ebcdicFile, BasicDetail.ACCOUNT_ID, "00000000011");
            ebcdicWriter.writeAccountHeading(ebcdicFile, "00000000011");
            byte[] group = ebcdicWriter.composeNameParagraphGroup("MARGARET GOLD");

            assertThat(new String(ebcdicRecords.get(0), ebcdic)).isEqualTo(
                    padded("<p>Account ID         : 00000000011         </p>"));
            assertThat(new String(ebcdicRecords.get(1), ebcdic)).isEqualTo(
                    padded("<h3>Statement for Account Number: 00000000011         </h3>"));
            assertThat(group).hasSize(StatementHtmlWriter.HTML_L23_LENGTH);
            assertThat(new String(group, ebcdic)).isEqualTo(
                    "<p style=\"font-size:16px\">MARGARET GOLD"
                            + String.valueOf(SPACE).repeat(37));
            assertThat(ebcdicWriter.datasetCharset()).isEqualTo(ebcdic);
        }
    }

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

        @ParameterizedTest(name = "FILE STATUS ''{0}'' -> {1}")
        @MethodSource("com.vsergeychik.carddemo.statement.StatementHtmlWriterTest#writeStatuses")
        @DisplayName("Every FILE STATUS a sink reports is surfaced as its outcome, never thrown")
        void everyReportedStatusIsSurfacedAsItsOutcome(final String status,
                                                       final FileStatus.Outcome outcome) {
            List<byte[]> records = new ArrayList<>();
            HtmlStatementFile handle = StatementHtmlWriterTest.this.writer.open(record -> {
                records.add(record);
                return status;
            });

            FileStatus.Outcome reported = StatementHtmlWriterTest.this.writer.writeFixedLine(
                    handle, HtmlFixedLine.HTML_L01);

            assertThat(reported).isEqualTo(outcome);
            assertThat(records).hasSize(1);
            assertThat(records.get(0)).hasSize(StatementHtmlWriter.RECORD_LENGTH);
            assertThat(handle.recordsWritten()).isEqualTo(1L);
            assertThat(handle.isOpen()).isTrue();
        }

        @Test
        @DisplayName("The writer's own permanent-error status is '30', and it classifies as OTHER")
        void thePermanentErrorStatusClassifiesAsOther() {
            assertThat(StatementHtmlWriter.PERMANENT_ERROR_STATUS).isEqualTo("30");
            assertThat(FileStatus.outcomeOfStatus(StatementHtmlWriter.PERMANENT_ERROR_STATUS))
                    .isEqualTo(FileStatus.Outcome.OTHER);
            assertThat(StatementHtmlWriter.PERMANENT_ERROR_STATUS).isNotEqualTo(FileStatus.OK);
        }

        @Test
        @DisplayName("close() surfaces every status too, and still closes the handle")
        void closeSurfacesEveryStatusAndStillCloses() {
            for (String status : List.of(FileStatus.OK, FileStatus.END_OF_FILE,
                    StatementHtmlWriter.PERMANENT_ERROR_STATUS)) {
                HtmlStatementFile handle = StatementHtmlWriterTest.this.writer.open(
                        new StatusReportingSink(FileStatus.OK, FileStatus.OK, status));

                FileStatus.Outcome reported =
                        StatementHtmlWriterTest.this.writer.close(handle);

                assertThat(reported)
                        .as("close outcome for FILE STATUS '%s'", status)
                        .isEqualTo(FileStatus.outcomeOfStatus(status));
                assertThat(handle.isOpen()).isFalse();
            }
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
            JdbcHtmlRecordSink sink = StatementHtmlWriterTest.this.writer.defaultSink();

            assertThat(sink.insertStatement()).startsWith("INSERT INTO \"").contains("\" VALUES (?)");
            assertThat(sink.insertStatement()).doesNotContain("INTO " + TEST_DSNAME);
        }

        @Test
        @DisplayName("Both statement writers render the same dataset name identically (F07)")
        void bothWritersRenderTheSameNameIdentically() {
            String shared = "TEST.M2.SHARED.SEQ";

            DatasetBindings textCatalogue = new DatasetBindings();
            textCatalogue.put("STMTFILE", new DatasetBinding(shared, "sequential", false, "FB",
                    8000, 80, null, null, null, null, null));
            StatementTextWriter textWriter = new StatementTextWriter(mock(JdbcTemplate.class),
                    StandardCharsets.US_ASCII, textCatalogue, RecordImageForm.CHARACTER);

            JdbcHtmlRecordSink htmlSink = newWriter(mock(JdbcTemplate.class),
                    StatementHtmlWriter.RECORD_LENGTH, shared).defaultSink();

            assertThat(htmlSink.insertStatement()).isEqualTo(textWriter.insertStatement());
        }

        @Test
        @DisplayName("The open establishes the destination and empties it - one describe, one delete, "
                + "no DDL (OPEN OUTPUT HTML-FILE, CBSTM03A.CBL:L293; CREASTMT.JCL:L92-L96)")
        void theOpenEstablishesAndClearsTheDestination() {
            JdbcHtmlRecordSink sink = StatementHtmlWriterTest.this.writer.defaultSink();

            assertThat(sink.open()).isEqualTo(FileStatus.OK);
            assertThat(sink.lastFailure()).isEmpty();

            verify(StatementHtmlWriterTest.this.jdbcTemplate)
                    .execute("SELECT * FROM \"" + TEST_DSNAME + "\" WHERE 1 = 0");
            verify(StatementHtmlWriterTest.this.jdbcTemplate)
                    .update("DELETE FROM \"" + TEST_DSNAME + "\"");
            verify(StatementHtmlWriterTest.this.jdbcTemplate, never())
                    .execute(org.mockito.ArgumentMatchers.contains("CREATE"));
            verify(StatementHtmlWriterTest.this.jdbcTemplate, never())
                    .execute(org.mockito.ArgumentMatchers.contains("DROP"));
            verify(StatementHtmlWriterTest.this.jdbcTemplate, never())
                    .execute(org.mockito.ArgumentMatchers.contains("TRUNCATE"));
        }

        @Test
        @DisplayName("The close probes the destination again and clears nothing "
                + "(CLOSE HTML-FILE, CBSTM03A.CBL:L339)")
        void theCloseProbesAndClearsNothing() {
            JdbcHtmlRecordSink sink = StatementHtmlWriterTest.this.writer.defaultSink();

            assertThat(sink.close()).isEqualTo(FileStatus.OK);

            verify(StatementHtmlWriterTest.this.jdbcTemplate)
                    .execute("SELECT * FROM \"" + TEST_DSNAME + "\" WHERE 1 = 0");
            verify(StatementHtmlWriterTest.this.jdbcTemplate, never())
                    .update("DELETE FROM \"" + TEST_DSNAME + "\"");
        }

        @Test
        @DisplayName("A destination that cannot be described reports the permanent-error status from "
                + "the open, and retains the backend's own diagnosis")
        void aRefusedOpenReportsThePermanentErrorStatus() {
            doThrow(new DataAccessResourceFailureException("no driver"))
                    .when(StatementHtmlWriterTest.this.jdbcTemplate).execute(anyString());
            JdbcHtmlRecordSink sink = StatementHtmlWriterTest.this.writer.defaultSink();

            assertThat(sink.open()).isEqualTo(StatementHtmlWriter.PERMANENT_ERROR_STATUS);
            assertThat(sink.lastFailure()).isPresent();
            verify(StatementHtmlWriterTest.this.jdbcTemplate, never()).update(anyString());
        }

        @Test
        @DisplayName("A destination that refuses the clear reports the permanent-error status too - a "
                + "NEW generation that cannot be emptied is not open")
        void aRefusedClearReportsThePermanentErrorStatus() {
            when(StatementHtmlWriterTest.this.jdbcTemplate.update(anyString()))
                    .thenThrow(new DataAccessResourceFailureException("read-only"));
            JdbcHtmlRecordSink sink = StatementHtmlWriterTest.this.writer.defaultSink();

            assertThat(sink.open()).isEqualTo(StatementHtmlWriter.PERMANENT_ERROR_STATUS);
            assertThat(sink.lastFailure()).isPresent();
        }

        @Test
        @DisplayName("A destination that goes away mid-run reports the permanent-error status from "
                + "the close")
        void aRefusedCloseReportsThePermanentErrorStatus() {
            doThrow(new DataAccessResourceFailureException("dataset dropped"))
                    .when(StatementHtmlWriterTest.this.jdbcTemplate).execute(anyString());
            JdbcHtmlRecordSink sink = StatementHtmlWriterTest.this.writer.defaultSink();

            assertThat(sink.close()).isEqualTo(StatementHtmlWriter.PERMANENT_ERROR_STATUS);
            assertThat(sink.lastFailure()).isPresent();
        }

        @Test
        @DisplayName("A successful open after a failure clears the retained diagnosis, as a "
                + "successful write does")
        void aSuccessfulOpenClearsTheRetainedFailure() {
            JdbcHtmlRecordSink sink = StatementHtmlWriterTest.this.writer.defaultSink();
            doThrow(new DataAccessResourceFailureException("transient"))
                    .when(StatementHtmlWriterTest.this.jdbcTemplate).execute(anyString());
            assertThat(sink.open()).isEqualTo(StatementHtmlWriter.PERMANENT_ERROR_STATUS);
            assertThat(sink.lastFailure()).isPresent();

            org.mockito.Mockito.reset(StatementHtmlWriterTest.this.jdbcTemplate);

            assertThat(sink.open()).isEqualTo(FileStatus.OK);
            assertThat(sink.lastFailure()).isEmpty();
            assertThat(sink.close()).isEqualTo(FileStatus.OK);
            assertThat(sink.lastFailure()).isEmpty();
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
        @DisplayName("binds the record image through the configured representation, never an untyped "
                + "argument")
        void theRecordImageIsBoundThroughTheConfiguredForm() throws SQLException {
            for (RecordImageForm form : RecordImageForm.values()) {
                DataSource dataSource = mock(DataSource.class);
                Connection connection = mock(Connection.class);
                PreparedStatement statement = mock(PreparedStatement.class);
                when(dataSource.getConnection()).thenReturn(connection);
                when(connection.prepareStatement(anyString())).thenReturn(statement);

                byte[] image = new byte[StatementHtmlWriter.RECORD_LENGTH];
                java.util.Arrays.fill(image, (byte) ' ');
                image[0] = (byte) 'X';

                assertThat(newWriter(new JdbcTemplate(dataSource), StatementHtmlWriter.RECORD_LENGTH,
                        TEST_DSNAME, form).defaultSink().write(image)).isEqualTo(FileStatus.OK);

                verify(connection).prepareStatement("INSERT INTO \"" + TEST_DSNAME + "\" VALUES (?)");
                if (form == RecordImageForm.BINARY) {
                    verify(statement).setBytes(1, image);
                    verify(statement, never()).setString(anyInt(), anyString());
                } else {
                    verify(statement).setString(1,
                            new String(image, StandardCharsets.US_ASCII));
                    verify(statement, never()).setBytes(anyInt(), any());
                }
                verify(statement).executeUpdate();
            }
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
            assertThat(sink.lastFailure()).isPresent();
            DatasetRelation.BackendDiagnostic reported = sink.lastFailure().orElseThrow();
            assertThat(reported.exceptionType())
                    .isEqualTo(DataAccessResourceFailureException.class.getName());
            assertThat(reported.toString()).doesNotContain("no driver");
            assertThat(reported.describe()).doesNotContain("no driver");
        }

        @Test
        @DisplayName("a driver message carrying record content never reaches lastFailure()")
        void aDriverMessageCarryingRecordContentIsNotRetained() {
            String customer = "MARGARET GOLD, 1 HIGH STREET";
            doThrow(new DataAccessResourceFailureException("rejected: " + customer,
                    new java.sql.SQLException("value '" + customer + "' too long", "22001", 1)))
                    .when(StatementHtmlWriterTest.this.jdbcTemplate)
                    .update(anyString(), any(PreparedStatementSetter.class));
            JdbcHtmlRecordSink sink = StatementHtmlWriterTest.this.writer.defaultSink();

            assertThat(sink.write(new byte[StatementHtmlWriter.RECORD_LENGTH]))
                    .isEqualTo(StatementHtmlWriter.PERMANENT_ERROR_STATUS);
            DatasetRelation.BackendDiagnostic reported = sink.lastFailure().orElseThrow();
            assertThat(reported.toString()).doesNotContain(customer);
            assertThat(reported.describe()).doesNotContain(customer);
            assertThat(reported.sqlState()).isEqualTo("22001");
            assertThat(reported.vendorCode()).isEqualTo(1);
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
            assertThat(statement).endsWith(" VALUES (?)");
            assertThat(statement.indexOf("--")).isGreaterThan(statement.indexOf('"'));
            assertThat(statement.indexOf("--")).isLessThan(statement.lastIndexOf('"'));
        }

        @Test
        @DisplayName("A generation-qualified name survives unchanged inside the delimiters")
        void aGenerationQualifiedNameSurvivesUnchanged() {
            StatementHtmlWriter gdg = newWriter(mock(JdbcTemplate.class),
                    StatementHtmlWriter.RECORD_LENGTH, "TEST.M2-A9$@#.SEQ(+1)");

            assertThat(gdg.defaultSink().insertStatement())
                    .isEqualTo("INSERT INTO \"TEST.M2-A9$@#.SEQ(+1)\" VALUES (?)");
        }

        @Test
        @DisplayName("An underscore is not a z/OS qualifier character, so such a name is refused")
        void anUnderscoreIsRefused() {
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
            assertThat(StatementHtmlWriter.insertStatement("ODD\"NAME", "\""))
                    .isEqualTo("INSERT INTO \"ODD\"\"NAME\" VALUES (?)");
            assertThat(StatementHtmlWriter.insertStatement("A`B", "`"))
                    .isEqualTo("INSERT INTO `A``B` VALUES (?)");
        }

        @Test
        @DisplayName("A name that tries to close its own identifier cannot escape it")
        void aNameThatTriesToCloseItsOwnIdentifierCannotEscapeIt() {
            String statement = StatementHtmlWriter.insertStatement("X\"; DROP TABLE Y; --", "\"");

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

    @Nested
    @DisplayName("The eleven continued literals - the fixed-format continuation rule applied by hand")
    class ContinuationRule {
        @ParameterizedTest(name = "{1} {0}")
        @MethodSource(
                "com.vsergeychik.carddemo.statement.StatementHtmlWriterTest#continuedLiterals")
        @DisplayName("Each continued literal is its two fragments joined with nothing between")
        void theJoinInsertsNothing(final HtmlFixedLine line, final String sourceLines,
                                   final String firstFragment, final String secondFragment) {
            assertThat(line.literal())
                    .as("%s is continued across %s", line.cobolName(), sourceLines)
                    .isEqualTo(firstFragment + secondFragment);
            assertThat(line.literalLength())
                    .isEqualTo(firstFragment.length() + secondFragment.length());

            assertThat(line.literal().charAt(firstFragment.length() - 1))
                    .as("last character of the first fragment of %s", line.cobolName())
                    .isEqualTo(firstFragment.charAt(firstFragment.length() - 1));
            assertThat(line.literal().charAt(firstFragment.length()))
                    .as("first character of the continuation of %s", line.cobolName())
                    .isEqualTo(secondFragment.charAt(0));
        }

        @ParameterizedTest(name = "{1} {0}")
        @MethodSource(
                "com.vsergeychik.carddemo.statement.StatementHtmlWriterTest#continuedLiterals")
        @DisplayName("Inserting a space at the join would change the value, and does not match")
        void insertingASpaceAtTheJoinWouldNotMatch(final HtmlFixedLine line,
                                                   final String sourceLines,
                                                   final String firstFragment,
                                                   final String secondFragment) {
            assertThat(line.literal())
                    .as("%s, continued across %s, must not carry an inserted space", line.cobolName(),
                            sourceLines)
                    .isNotEqualTo(firstFragment + SPACE + secondFragment);
            assertThat(line.literal()).doesNotContain("\n").doesNotContain("\r")
                    .doesNotContain("\t").doesNotContain("'");
        }

        @Test
        @DisplayName("No fragment carries whitespace at the join: every continued line is 72 columns")
        void noFragmentCarriesWhitespaceAtTheJoin() {
            continuedLiterals().forEach(arguments -> {
                Object[] parts = arguments.get();
                String firstFragment = (String) parts[2];
                String secondFragment = (String) parts[3];
                assertThat(firstFragment).as("first fragment of %s", parts[0])
                        .doesNotEndWith(String.valueOf(SPACE));
                assertThat(secondFragment).as("continuation of %s", parts[0])
                        .doesNotStartWith(String.valueOf(SPACE));
            });
        }

        @Test
        @DisplayName("Exactly eleven of the thirty-four literals are continued; twenty-three are not")
        void exactlyElevenLiteralsAreContinued() {
            assertThat(continuedLiterals()).hasSize(11);
            assertThat(HtmlFixedLine.values()).hasSize(34);
            assertThat(HtmlFixedLine.values().length - 11).isEqualTo(23);
        }

        @Test
        @DisplayName("The four colspan cells share ONE first fragment ending at 'padding:0px 5px;'")
        void theColspanCellsShareOneFirstFragment() {
            for (HtmlFixedLine line : List.of(HtmlFixedLine.HTML_L10, HtmlFixedLine.HTML_L15,
                    HtmlFixedLine.HTML_L22_35, HtmlFixedLine.HTML_L30_42)) {
                assertThat(line.literal())
                        .as("%s opens with the shared colspan fragment", line.cobolName())
                        .startsWith(COLSPAN_FIRST_FRAGMENT);
            }
            assertThat(COLSPAN_FIRST_FRAGMENT).endsWith("padding:0px 5px;").hasSize(39);
        }

        @Test
        @DisplayName("The six width cells split the word 'background-color' across the join")
        void theWidthCellsSplitTheWordBackgroundColour() {
            for (HtmlFixedLine line : List.of(HtmlFixedLine.HTML_L47, HtmlFixedLine.HTML_L50,
                    HtmlFixedLine.HTML_L53, HtmlFixedLine.HTML_L58, HtmlFixedLine.HTML_L61,
                    HtmlFixedLine.HTML_L64)) {
                assertThat(line.literal())
                        .as("%s carries the space the colspan cells do not", line.cobolName())
                        .contains("padding:0px 5px; background-color:");
            }
            assertThat(WIDTH_FIRST_FRAGMENT_25).endsWith("background-").hasSize(50);
            assertThat(WIDTH_FIRST_FRAGMENT_55).endsWith("background-").hasSize(50);
            assertThat(WIDTH_FIRST_FRAGMENT_20).endsWith("background-").hasSize(50);
            assertThat(HtmlFixedLine.HTML_L47.literal()).startsWith(WIDTH_FIRST_FRAGMENT_25);
            assertThat(HtmlFixedLine.HTML_L58.literal()).startsWith(WIDTH_FIRST_FRAGMENT_25);
            assertThat(HtmlFixedLine.HTML_L50.literal()).startsWith(WIDTH_FIRST_FRAGMENT_55);
            assertThat(HtmlFixedLine.HTML_L61.literal()).startsWith(WIDTH_FIRST_FRAGMENT_55);
            assertThat(HtmlFixedLine.HTML_L53.literal()).startsWith(WIDTH_FIRST_FRAGMENT_20);
            assertThat(HtmlFixedLine.HTML_L64.literal()).startsWith(WIDTH_FIRST_FRAGMENT_20);
        }

        @Test
        @DisplayName("HTML-L08's join falls mid-word: 'styl' + 'e=' - CBSTM03A.CBL:L157-L158")
        void theTableJoinFallsMidWord() {
            assertThat(TABLE_FIRST_FRAGMENT).endsWith("styl").hasSize(39);
            assertThat(HtmlFixedLine.HTML_L08.literal())
                    .startsWith(TABLE_FIRST_FRAGMENT)
                    .contains("style=\"width:70%;")
                    .hasSize(85);
        }
    }

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

    private static final String TABLE_FIRST_FRAGMENT =
            "<table  align=\"center\" frame=\"box\" styl";

    private static final String COLSPAN_FIRST_FRAGMENT =
            "<td colspan=\"3\" style=\"padding:0px 5px;";

    private static final String WIDTH_FIRST_FRAGMENT_25 =
            "<td style=\"width:25%; padding:0px 5px; background-";

    private static final String WIDTH_FIRST_FRAGMENT_55 =
            "<td style=\"width:55%; padding:0px 5px; background-";

    private static final String WIDTH_FIRST_FRAGMENT_20 =
            "<td style=\"width:20%; padding:0px 5px; background-";

    static Stream<Arguments> writeStatuses() {
        return Stream.of(
                Arguments.of(FileStatus.OK, FileStatus.Outcome.OK),
                Arguments.of(FileStatus.END_OF_FILE, FileStatus.Outcome.END_OF_FILE),
                Arguments.of(FileStatus.DUPLICATE, FileStatus.Outcome.DUPLICATE),
                Arguments.of(FileStatus.NOT_FOUND, FileStatus.Outcome.NOT_FOUND),
                Arguments.of(StatementHtmlWriter.PERMANENT_ERROR_STATUS,
                        FileStatus.Outcome.OTHER));
    }

    static Stream<Arguments> continuedLiterals() {
        return Stream.of(
                Arguments.of(HtmlFixedLine.HTML_L08, "L157-L158", TABLE_FIRST_FRAGMENT,
                        "e=\"width:70%; font:12px Segoe UI,sans-serif;\">"),
                Arguments.of(HtmlFixedLine.HTML_L10, "L163-L164", COLSPAN_FIRST_FRAGMENT,
                        "background-color:#1d1d96b3;\">"),
                Arguments.of(HtmlFixedLine.HTML_L15, "L165-L166", COLSPAN_FIRST_FRAGMENT,
                        "background-color:#FFAF33;\">"),
                Arguments.of(HtmlFixedLine.HTML_L22_35, "L174-L175", COLSPAN_FIRST_FRAGMENT,
                        "background-color:#f2f2f2;\">"),
                Arguments.of(HtmlFixedLine.HTML_L30_42, "L177-L178", COLSPAN_FIRST_FRAGMENT,
                        "background-color:#33FFD1; text-align:center;\">"),
                Arguments.of(HtmlFixedLine.HTML_L47, "L184-L185", WIDTH_FIRST_FRAGMENT_25,
                        "color:#33FF5E; text-align:left;\">"),
                Arguments.of(HtmlFixedLine.HTML_L50, "L189-L190", WIDTH_FIRST_FRAGMENT_55,
                        "color:#33FF5E; text-align:left;\">"),
                Arguments.of(HtmlFixedLine.HTML_L53, "L194-L195", WIDTH_FIRST_FRAGMENT_20,
                        "color:#33FF5E; text-align:right;\">"),
                Arguments.of(HtmlFixedLine.HTML_L58, "L199-L200", WIDTH_FIRST_FRAGMENT_25,
                        "color:#f2f2f2; text-align:left;\">"),
                Arguments.of(HtmlFixedLine.HTML_L61, "L202-L203", WIDTH_FIRST_FRAGMENT_55,
                        "color:#f2f2f2; text-align:left;\">"),
                Arguments.of(HtmlFixedLine.HTML_L64, "L205-L206", WIDTH_FIRST_FRAGMENT_20,
                        "color:#f2f2f2; text-align:right;\">"));
    }

    private static String padded(final String content) {
        return content
                + String.valueOf(SPACE).repeat(StatementHtmlWriter.RECORD_LENGTH
                        - content.length());
    }

    private static final class StatusReportingSink implements HtmlRecordSink {
        private final String writeStatus;

        private final String openStatus;

        private final String closeStatus;

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

    @Nested
    @DisplayName("The abnormal disposition deletes the HTML an abended run wrote")
    class TheAbnormalDisposition {
        private JdbcTemplate liveTemplate() {
            final JdbcTemplate template = new JdbcTemplate(new SimpleDriverDataSource(
                    new org.h2.Driver(),
                    "jdbc:h2:mem:htmlfile-disp-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
            template.execute("CREATE TABLE \"" + TEST_DSNAME + "\" (RECORD_IMAGE CHAR("
                    + StatementHtmlWriter.RECORD_LENGTH + "))");
            return template;
        }

        private int held(final JdbcTemplate template) {
            final Integer count = template.queryForObject(
                    "SELECT COUNT(*) FROM \"" + TEST_DSNAME + "\"", Integer.class);
            return count == null ? 0 : count;
        }

        @Test
        @DisplayName("every HTML line this run wrote is deleted, and the close deleted nothing")
        void theGenerationIsDeleted() {
            final JdbcTemplate template = liveTemplate();
            final StatementHtmlWriter subject = newWriter(template,
                    StatementHtmlWriter.RECORD_LENGTH, TEST_DSNAME);
            final HtmlStatementFile handle = subject.open();

            assertThat(subject.writeFixedLine(handle, HtmlFixedLine.HTML_L01))
                    .isEqualTo(FileStatus.Outcome.OK);
            assertThat(subject.writeFixedLine(handle, HtmlFixedLine.HTML_L02))
                    .isEqualTo(FileStatus.Outcome.OK);
            assertThat(subject.close(handle)).isEqualTo(FileStatus.Outcome.OK);
            assertThat(held(template)).isEqualTo(2);

            assertThat(subject.discardGeneration(handle)).isEqualTo(FileStatus.Outcome.OK);
            assertThat(held(template)).isZero();
        }

        @Test
        @DisplayName("a closed handle is accepted, because the abnormal path closes before it disposes")
        void aClosedHandleIsAccepted() {
            final JdbcTemplate template = liveTemplate();
            final StatementHtmlWriter subject = newWriter(template,
                    StatementHtmlWriter.RECORD_LENGTH, TEST_DSNAME);
            final HtmlStatementFile handle = subject.open();
            assertThat(subject.writeFixedLine(handle, HtmlFixedLine.HTML_L01))
                    .isEqualTo(FileStatus.Outcome.OK);
            assertThat(subject.close(handle)).isEqualTo(FileStatus.Outcome.OK);
            assertThat(handle.isOpen()).isFalse();

            assertThatCode(() -> assertThat(subject.discardGeneration(handle))
                    .isEqualTo(FileStatus.Outcome.OK)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a second discard neither issues anything nor contradicts the first")
        void theDiscardIsIdempotent() {
            final JdbcTemplate template = liveTemplate();
            final StatementHtmlWriter subject = newWriter(template,
                    StatementHtmlWriter.RECORD_LENGTH, TEST_DSNAME);
            final HtmlStatementFile handle = subject.open();
            assertThat(subject.writeFixedLine(handle, HtmlFixedLine.HTML_L01))
                    .isEqualTo(FileStatus.Outcome.OK);

            assertThat(subject.discardGeneration(handle)).isEqualTo(FileStatus.Outcome.OK);
            assertThat(subject.discardGeneration(handle)).isEqualTo(FileStatus.Outcome.OK);
            assertThat(held(template)).isZero();
        }

        @Test
        @DisplayName("a run that wrote no line deletes nothing and reports OK")
        void anEmptyRunDeletesNothing() {
            final JdbcTemplate template = liveTemplate();
            template.update("INSERT INTO \"" + TEST_DSNAME + "\" VALUES (?)",
                    " ".repeat(StatementHtmlWriter.RECORD_LENGTH));
            final StatementHtmlWriter subject = newWriter(template,
                    StatementHtmlWriter.RECORD_LENGTH, TEST_DSNAME);
            final HtmlStatementFile handle = subject.open(record -> FileStatus.OK);

            assertThat(subject.discardGeneration(handle)).isEqualTo(FileStatus.Outcome.OK);
            assertThat(held(template)).isOne();
        }

        @Test
        @DisplayName("a relation holding HTML this run did not write is left untouched")
        void aCountMismatchIsRefused() {
            final JdbcTemplate template = liveTemplate();
            final StatementHtmlWriter subject = newWriter(template,
                    StatementHtmlWriter.RECORD_LENGTH, TEST_DSNAME);
            final HtmlStatementFile handle = subject.open();
            assertThat(subject.writeFixedLine(handle, HtmlFixedLine.HTML_L01))
                    .isEqualTo(FileStatus.Outcome.OK);
            template.update("INSERT INTO \"" + TEST_DSNAME + "\" VALUES (?)",
                    " ".repeat(StatementHtmlWriter.RECORD_LENGTH));

            assertThat(subject.discardGeneration(handle)).isEqualTo(FileStatus.Outcome.OTHER);
            assertThat(held(template)).isEqualTo(2);
        }

        @Test
        @DisplayName("a count the backend will not state is refused exactly as a wrong count is")
        void anUnstatedCountIsRefused() {
            final JdbcTemplate live = liveTemplate();
            final JdbcTemplate template = spy(live);
            final StatementHtmlWriter subject = newWriter(template,
                    StatementHtmlWriter.RECORD_LENGTH, TEST_DSNAME);
            final HtmlStatementFile handle = subject.open();
            assertThat(subject.writeFixedLine(handle, HtmlFixedLine.HTML_L01))
                    .isEqualTo(FileStatus.Outcome.OK);

            doReturn(null).when(template)
                    .queryForObject(anyString(), ArgumentMatchers.eq(Long.class));

            assertThat(subject.discardGeneration(handle)).isEqualTo(FileStatus.Outcome.OTHER);
            assertThat(held(live))
                    .as("refused means untouched: the HTML line this run wrote is still there")
                    .isOne();
        }

        @Test
        @DisplayName("a delete that removes a different number than it counted is reported, not called OK")
        void aDeleteRemovingADifferentCountIsReported() {
            final JdbcTemplate template = spy(liveTemplate());
            final StatementHtmlWriter subject = newWriter(template,
                    StatementHtmlWriter.RECORD_LENGTH, TEST_DSNAME);
            final HtmlStatementFile handle = subject.open();
            assertThat(subject.writeFixedLine(handle, HtmlFixedLine.HTML_L01))
                    .isEqualTo(FileStatus.Outcome.OK);

            doReturn(99).when(template).update(anyString());

            assertThat(subject.discardGeneration(handle)).isEqualTo(FileStatus.Outcome.OTHER);
        }

        @Test
        @DisplayName("a null handle is reported rather than raised, because this path is already abending")
        void aNullHandleIsReported() {
            assertThatCode(() -> assertThat(
                    StatementHtmlWriterTest.this.writer.discardGeneration(null))
                    .isEqualTo(FileStatus.Outcome.OTHER)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a sink that holds no catalogued generation reports OK without implementing "
                + "anything, and the method is a default so a lambda still compiles")
        void aLambdaSinkDefaultsToOk() {
            final HtmlRecordSink minimal = record -> FileStatus.OK;
            final HtmlStatementFile handle = StatementHtmlWriterTest.this.writer.open(minimal);
            assertThat(StatementHtmlWriterTest.this.writer.writeFixedLine(handle,
                    HtmlFixedLine.HTML_L01)).isEqualTo(FileStatus.Outcome.OK);

            assertThat(StatementHtmlWriterTest.this.writer.discardGeneration(handle))
                    .isEqualTo(FileStatus.Outcome.OK);
        }

        @Test
        @DisplayName("a sink answering null from discard is read as OTHER rather than raising")
        void aNullDiscardStatusIsReported() {
            final HtmlStatementFile handle = StatementHtmlWriterTest.this.writer.open(
                    new HtmlRecordSink() {
                        @Override
                        public String write(final byte[] record) {
                            return FileStatus.OK;
                        }

                        @Override
                        public String discard(final long recordsWritten) {
                            return null;
                        }
                    });
            assertThat(StatementHtmlWriterTest.this.writer.writeFixedLine(handle,
                    HtmlFixedLine.HTML_L01)).isEqualTo(FileStatus.Outcome.OK);

            assertThat(StatementHtmlWriterTest.this.writer.discardGeneration(handle))
                    .isEqualTo(FileStatus.Outcome.OTHER);
        }
    }
}
