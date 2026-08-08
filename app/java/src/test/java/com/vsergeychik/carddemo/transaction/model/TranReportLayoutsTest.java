package com.vsergeychik.carddemo.transaction.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The print image of the CardDemo daily transaction report, under test.
 *
 * <p>{@link TranReportLayouts} is the single Java type for {@code app/cpy/CVTRA07Y.cpy}, and it is
 * pure presentation bytes: every literal, every space, every dot and every hyphen is part of the
 * contract that {@code app/cbl/CBTRN03C.cbl} writes to the 133-byte {@code TRANREPT} dataset. This
 * suite therefore asserts <em>exact</em> images and <em>exact</em> lengths rather than "contains" or
 * "starts with" — a report line of the right shape and the wrong width mis-aligns every column
 * after it, and a mis-suppressed comma shifts an amount by one byte in every row of a page.
 *
 * <h2>Provenance of every expected value</h2>
 *
 * <p>The legacy COBOL <b>cannot be executed in this environment</b>: there is no z/OS runtime, the
 * available compiler has indexed file support disabled, no Language Environment {@code CEE*}
 * services exist and no CICS emulator is present. Every expectation below is consequently
 * <em>statically derived</em> — measured out of {@code app/cpy/CVTRA07Y.cpy}, read out of {@code
 * app/cbl/CBTRN03C.cbl}, and cross-checked against the <em>IBM Enterprise COBOL for z/OS Language
 * Reference</em> sections "Zero suppression and replacement editing", "Fixed insertion editing" and
 * "Initializing a structure (INITIALIZE)" — rather than captured from a live run. Expected images
 * are written as explicit field-by-field concatenations with their 1-based columns in comments, so a
 * failure points at a single copybook item rather than at a 114-character blob.
 *
 * <h2>The four properties of the copybook that shape every assertion</h2>
 *
 * <ol>
 *   <li><b>Seven record areas, of 115, 114, 114, 133, 112, 112 and 112 bytes.</b> All measured from
 *       the {@code PICTURE} clauses; none of them is 133 except the rule line, because padding to
 *       the record width belongs to {@code TranReportWriter}.</li>
 *   <li><b>The amount always occupies 1-based columns 98-112.</b> In all four amount-bearing
 *       layouts the bytes ahead of it sum to exactly 97 — which is only true because the dot
 *       leaders are 86, 84 and 86, compensating label widths of 11, 13 and 11.</li>
 *   <li><b>Every digit position of both masks is a {@code Z}.</b> So a zero value blanks the whole
 *       15-byte item, sign and commas and decimal point included.</li>
 *   <li><b>{@code ROUNDED} appears zero times in all 28 programs.</b> So every store truncates, and
 *       {@code RoundingMode.DOWN} is the only faithful mode — which is indistinguishable from
 *       {@code FLOOR} until a negative value is edited, and from {@code HALF_UP} until a half-way
 *       one is.</li>
 * </ol>
 *
 * <h2>Three traps this suite exists to catch</h2>
 *
 * <ol>
 *   <li><b>A normalised dot leader.</b> Unifying 86/84/86 looks like tidying and silently moves the
 *       account-total amount two columns. Caught by {@code AmountColumn}.</li>
 *   <li><b>A "fixed" {@code INITIALIZE}.</b> Blanking the whole detail line would erase the two
 *       {@code '-'} separators, which COBOL's {@code INITIALIZE} leaves standing because they are
 *       {@code FILLER}. Caught by {@code InitializeSemantics}.</li>
 *   <li><b>A locale-sensitive formatter.</b> {@code String.format}, {@code DecimalFormat} and
 *       {@code NumberFormat} would render {@code 1.234,56} under a European default locale and none
 *       of them implements {@code Z} suppression at all. Caught by the exact-image mask tables.</li>
 * </ol>
 */
@DisplayName("TranReportLayouts - the five report line layouts of app/cpy/CVTRA07Y.cpy")
class TranReportLayoutsTest {

    /** The charset of the {@code app/data/ASCII} fixtures, and the default for these tests. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** The EBCDIC code page of the {@code app/data/EBCDIC} datasets. */
    private static final Charset EBCDIC = Charset.forName("IBM037");

    /** The edited image of zero under either mask. */
    private static final String BLANK_AMOUNT = " ".repeat(TranReportLayouts.AMOUNT_MASK_WIDTH);

    /** {@code REPT-SHORT-NAME}: {@code 'DALYREPT'} right-padded into {@code X(38)}. */
    private static final String SHORT_NAME_IMAGE = "DALYREPT" + " ".repeat(30);

    /** {@code REPT-LONG-NAME}: the long title right-padded into {@code X(41)}. */
    private static final String LONG_NAME_IMAGE = "Daily Transaction Report" + " ".repeat(17);

    /** 86 dots — the {@code REPORT-PAGE-TOTALS} and {@code REPORT-GRAND-TOTALS} leader. */
    private static final String LEADER_86 = ".".repeat(86);

    /** 84 dots — the {@code REPORT-ACCOUNT-TOTALS} leader, two shorter and deliberately so. */
    private static final String LEADER_84 = ".".repeat(84);

    private TranReportLayouts layouts;

    @BeforeEach
    void allocateFreshRecordAreas() {
        layouts = new TranReportLayouts(ASCII);
    }

    /**
     * Replaces every {@code _} with a space, so that a {@code @CsvSource} row can carry an expected
     * image whose leading and interior spaces are significant. A literal space would be trimmed by the
     * CSV parser and would be invisible in the test source besides.
     *
     * @param template the expected image with underscores standing in for spaces
     * @return the template with every underscore replaced by a space
     */
    private static String spaced(String template) {
        return template.replace('_', ' ');
    }

    /**
     * Performs the whole of {@code 1120-WRITE-DETAIL} ({@code CBTRN03C:362-370}) with the values used
     * throughout this suite: the {@code INITIALIZE} followed by all eight moves. The two descriptions
     * are supplied at their source widths — {@code PIC X(50)} in {@code app/cpy/CVTRA03Y.cpy} and
     * {@code app/cpy/CVTRA04Y.cpy} — so the receiving truncation is genuinely exercised.
     */
    private void writeDetailLine() {
        layouts.initializeTransactionDetailReport();
        layouts.moveTranReportTransId("0000000000000001");
        layouts.moveTranReportAccountId(10000000001L);
        layouts.moveTranReportTypeCd("01");
        layouts.moveTranReportTypeDesc(padTo("Purchase", 50));
        layouts.moveTranReportCatCd(5001L);
        layouts.moveTranReportCatDesc(padTo("Regular Sales Draft", 50));
        layouts.moveTranReportSource("POS TERM  ");
        layouts.moveTranReportAmt(new BigDecimal("-1234.56"));
    }

    /**
     * Right-pads with spaces to a fixed width, used to present a sending field at its own declared
     * {@code PIC X(n)} width.
     *
     * @param value the sending value
     * @param width the sending field's declared width
     * @return {@code value} padded on the right to {@code width}
     */
    private static String padTo(String value, int width) {
        return value + " ".repeat(width - value.length());
    }

    @Nested
    @DisplayName("Layout widths - 115 / 114 / 114 / 133 / 112 / 112 / 112 (gate G19)")
    class LayoutWidths {

        @Test
        @DisplayName("the seven transcribed widths are the seven the copybook declares")
        void transcribedWidths() {
            assertThat(TranReportLayouts.REPORT_NAME_HEADER_LENGTH).isEqualTo(115);
            assertThat(TranReportLayouts.TRANSACTION_DETAIL_REPORT_LENGTH).isEqualTo(114);
            assertThat(TranReportLayouts.TRANSACTION_HEADER_1_LENGTH).isEqualTo(114);
            assertThat(TranReportLayouts.TRANSACTION_HEADER_2_LENGTH).isEqualTo(133);
            assertThat(TranReportLayouts.REPORT_PAGE_TOTALS_LENGTH).isEqualTo(112);
            assertThat(TranReportLayouts.REPORT_ACCOUNT_TOTALS_LENGTH).isEqualTo(112);
            assertThat(TranReportLayouts.REPORT_GRAND_TOTALS_LENGTH).isEqualTo(112);
        }

        @Test
        @DisplayName("each width is the arithmetic sum of that layout's declared item widths")
        void itemWidthsSumToTheDeclaredWidth() {
            assertThat(TranReportLayouts.REPT_SHORT_NAME_LENGTH
                    + TranReportLayouts.REPT_LONG_NAME_LENGTH
                    + TranReportLayouts.REPT_DATE_HEADER_LENGTH
                    + TranReportLayouts.REPT_START_DATE_LENGTH
                    + TranReportLayouts.DATE_RANGE_SEPARATOR_LENGTH
                    + TranReportLayouts.REPT_END_DATE_LENGTH)
                    .isEqualTo(TranReportLayouts.REPORT_NAME_HEADER_LENGTH);
            assertThat(TranReportLayouts.HEADER_1_TRANSACTION_ID_LENGTH
                    + TranReportLayouts.HEADER_1_ACCOUNT_ID_LENGTH
                    + TranReportLayouts.HEADER_1_TRANSACTION_TYPE_LENGTH
                    + TranReportLayouts.HEADER_1_TRAN_CATEGORY_LENGTH
                    + TranReportLayouts.HEADER_1_TRAN_SOURCE_LENGTH
                    + TranReportLayouts.HEADER_1_GAP_LENGTH
                    + TranReportLayouts.HEADER_1_AMOUNT_LENGTH)
                    .isEqualTo(TranReportLayouts.TRANSACTION_HEADER_1_LENGTH);
            assertThat(TranReportLayouts.PAGE_TOTAL_LABEL_LENGTH
                    + TranReportLayouts.PAGE_TOTAL_LEADER_LENGTH
                    + TranReportLayouts.AMOUNT_MASK_WIDTH)
                    .isEqualTo(TranReportLayouts.REPORT_PAGE_TOTALS_LENGTH);
            assertThat(TranReportLayouts.ACCOUNT_TOTAL_LABEL_LENGTH
                    + TranReportLayouts.ACCOUNT_TOTAL_LEADER_LENGTH
                    + TranReportLayouts.AMOUNT_MASK_WIDTH)
                    .isEqualTo(TranReportLayouts.REPORT_ACCOUNT_TOTALS_LENGTH);
            assertThat(TranReportLayouts.GRAND_TOTAL_LABEL_LENGTH
                    + TranReportLayouts.GRAND_TOTAL_LEADER_LENGTH
                    + TranReportLayouts.AMOUNT_MASK_WIDTH)
                    .isEqualTo(TranReportLayouts.REPORT_GRAND_TOTALS_LENGTH);
        }

        @Test
        @DisplayName("every RecordLayout agrees with its declared width, so no item was omitted")
        void recordLayoutsAgree() {
            assertThat(TranReportLayouts.REPORT_NAME_HEADER_LAYOUT.recordLength()).isEqualTo(115);
            assertThat(TranReportLayouts.TRANSACTION_DETAIL_REPORT_LAYOUT.recordLength())
                    .isEqualTo(114);
            assertThat(TranReportLayouts.TRANSACTION_HEADER_1_LAYOUT.recordLength()).isEqualTo(114);
            assertThat(TranReportLayouts.TRANSACTION_HEADER_2_LAYOUT.recordLength()).isEqualTo(133);
            assertThat(TranReportLayouts.REPORT_PAGE_TOTALS_LAYOUT.recordLength()).isEqualTo(112);
            assertThat(TranReportLayouts.REPORT_ACCOUNT_TOTALS_LAYOUT.recordLength()).isEqualTo(112);
            assertThat(TranReportLayouts.REPORT_GRAND_TOTALS_LAYOUT.recordLength()).isEqualTo(112);
        }

        @Test
        @DisplayName("TRANSACTION-DETAIL-REPORT declares 16 items: 8 named and 8 FILLER")
        void detailDeclaresSixteenItems() {
            RecordLayout layout = TranReportLayouts.TRANSACTION_DETAIL_REPORT_LAYOUT;
            assertThat(layout.storageSpans()).hasSize(16);
            assertThat(layout.storageSpans().stream().filter(span -> span.kind().filler()).count())
                    .as("eight FILLERs: six carrying SPACES and two carrying '-'")
                    .isEqualTo(8);
            assertThat(layout.storageSpans().stream()
                    .filter(span -> span.kind().filler() && span.hasInitialValue()).count())
                    .as("the two '-' separator FILLERs are the only ones carrying a literal")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("TRANSACTION-HEADER-1 declares 7 items and every one of them is a FILLER")
        void headerOneIsAllFiller() {
            RecordLayout layout = TranReportLayouts.TRANSACTION_HEADER_1_LAYOUT;
            assertThat(layout.storageSpans()).hasSize(7);
            assertThat(layout.storageSpans()).allMatch(span -> span.kind().filler());
        }

        @Test
        @DisplayName("TRANSACTION-HEADER-2 is elementary: one span covering all 133 bytes")
        void headerTwoIsElementary() {
            assertThat(TranReportLayouts.TRANSACTION_HEADER_2_LAYOUT.storageSpans()).hasSize(1);
            assertThat(TranReportLayouts.TRANSACTION_HEADER_2.length()).isEqualTo(133);
            assertThat(TranReportLayouts.TRANSACTION_HEADER_2.offset()).isZero();
        }

        @Test
        @DisplayName("each total line declares a label FILLER, a leader FILLER and one amount")
        void totalLinesDeclareThreeItems() {
            assertThat(TranReportLayouts.REPORT_PAGE_TOTALS_LAYOUT.storageSpans()).hasSize(3);
            assertThat(TranReportLayouts.REPORT_ACCOUNT_TOTALS_LAYOUT.storageSpans()).hasSize(3);
            assertThat(TranReportLayouts.REPORT_GRAND_TOTALS_LAYOUT.storageSpans()).hasSize(3);
        }

        @Test
        @DisplayName("every rendered image carries its layout's natural width")
        void renderedImagesCarryTheirWidths() {
            assertThat(layouts.renderReportNameHeader()).hasSize(115);
            assertThat(layouts.renderTransactionDetailReport()).hasSize(114);
            assertThat(layouts.renderTransactionHeader1()).hasSize(114);
            assertThat(layouts.renderTransactionHeader2()).hasSize(133);
            assertThat(layouts.renderReportPageTotals()).hasSize(112);
            assertThat(layouts.renderReportAccountTotals()).hasSize(112);
            assertThat(layouts.renderReportGrandTotals()).hasSize(112);
        }

        @Test
        @DisplayName("every byte image carries its layout's natural width")
        void byteImagesCarryTheirWidths() {
            assertThat(layouts.renderReportNameHeaderBytes()).hasSize(115);
            assertThat(layouts.renderTransactionDetailReportBytes()).hasSize(114);
            assertThat(layouts.renderTransactionHeader1Bytes()).hasSize(114);
            assertThat(layouts.renderTransactionHeader2Bytes()).hasSize(133);
            assertThat(layouts.renderReportPageTotalsBytes()).hasSize(112);
            assertThat(layouts.renderReportAccountTotalsBytes()).hasSize(112);
            assertThat(layouts.renderReportGrandTotalsBytes()).hasSize(112);
        }

        @Test
        @DisplayName("widths survive a full detail write, because the layout is fixed")
        void widthsSurviveWrites() {
            writeDetailLine();
            layouts.moveReptStartDate("2022-01-01");
            layouts.moveReptEndDate("2022-07-06");
            layouts.moveReptPageTotal(new BigDecimal("123456789.12"));
            layouts.moveReptAccountTotal(new BigDecimal("-0.05"));
            layouts.moveReptGrandTotal(new BigDecimal("0.00"));
            assertThat(layouts.renderTransactionDetailReport()).hasSize(114);
            assertThat(layouts.renderReportNameHeader()).hasSize(115);
            assertThat(layouts.renderReportPageTotals()).hasSize(112);
            assertThat(layouts.renderReportAccountTotals()).hasSize(112);
            assertThat(layouts.renderReportGrandTotals()).hasSize(112);
        }
    }

    @Nested
    @DisplayName("The column-97 invariant - every amount on 1-based columns 98-112")
    class AmountColumn {

        @Test
        @DisplayName("the amount column constants agree: offset 97, columns 98-112, width 15")
        void amountColumnConstants() {
            assertThat(TranReportLayouts.AMOUNT_COLUMN_START).isEqualTo(98);
            assertThat(TranReportLayouts.AMOUNT_COLUMN_END).isEqualTo(112);
            assertThat(TranReportLayouts.AMOUNT_OFFSET).isEqualTo(97);
            assertThat(TranReportLayouts.AMOUNT_MASK_WIDTH).isEqualTo(15);
            assertThat(TranReportLayouts.AMOUNT_OFFSET)
                    .isEqualTo(TranReportLayouts.AMOUNT_COLUMN_START - 1);
            assertThat(TranReportLayouts.AMOUNT_COLUMN_END).isEqualTo(
                    TranReportLayouts.AMOUNT_COLUMN_START + TranReportLayouts.AMOUNT_MASK_WIDTH - 1);
            assertThat(TranReportLayouts.AMOUNT_INTEGER_DIGITS).isEqualTo(9);
            assertThat(TranReportLayouts.AMOUNT_FRACTION_DIGITS).isEqualTo(2);
        }

        @Test
        @DisplayName("all four amount spans begin at 0-based offset 97 and are 15 bytes wide")
        void allFourAmountSpansShareTheColumn() {
            for (FieldSpan span : new FieldSpan[] {TranReportLayouts.TRAN_REPORT_AMT,
                    TranReportLayouts.REPT_PAGE_TOTAL, TranReportLayouts.REPT_ACCOUNT_TOTAL,
                    TranReportLayouts.REPT_GRAND_TOTAL}) {
                assertThat(span.offset()).as("%s offset", span.name()).isEqualTo(97);
                assertThat(span.length()).as("%s length", span.name()).isEqualTo(15);
                assertThat(span.endOffsetExclusive()).as("%s end", span.name()).isEqualTo(112);
            }
        }

        @Test
        @DisplayName("TRAN-REPORT-AMT's offset is reached by summing the 14 items ahead of it")
        void detailPrefixSumsTo97() {
            int prefix = TranReportLayouts.TRAN_REPORT_TRANS_ID_LENGTH + 1
                    + TranReportLayouts.TRAN_REPORT_ACCOUNT_ID_LENGTH + 1
                    + TranReportLayouts.TRAN_REPORT_TYPE_CD_LENGTH + 1
                    + TranReportLayouts.TRAN_REPORT_TYPE_DESC_LENGTH + 1
                    + TranReportLayouts.TRAN_REPORT_CAT_CD_LENGTH + 1
                    + TranReportLayouts.TRAN_REPORT_CAT_DESC_LENGTH + 1
                    + TranReportLayouts.TRAN_REPORT_SOURCE_LENGTH + 4;
            assertThat(prefix)
                    .as("16+1+11+1+2+1+15+1+4+1+29+1+10+4")
                    .isEqualTo(97);
            assertThat(TranReportLayouts.TRAN_REPORT_AMT_OFFSET).isEqualTo(prefix);
        }

        @Test
        @DisplayName("the dot leaders are 86, 84 and 86 - never normalised to one width")
        void dotLeadersAreNotNormalised() {
            assertThat(TranReportLayouts.PAGE_TOTAL_LEADER_LENGTH).isEqualTo(86);
            assertThat(TranReportLayouts.ACCOUNT_TOTAL_LEADER_LENGTH).isEqualTo(84);
            assertThat(TranReportLayouts.GRAND_TOTAL_LEADER_LENGTH).isEqualTo(86);
            assertThat(TranReportLayouts.ACCOUNT_TOTAL_LEADER_LENGTH)
                    .as("the account leader is deliberately two shorter than the other two")
                    .isNotEqualTo(TranReportLayouts.PAGE_TOTAL_LEADER_LENGTH)
                    .isEqualTo(TranReportLayouts.PAGE_TOTAL_LEADER_LENGTH - 2);
        }

        @Test
        @DisplayName("label plus leader is exactly 97 in all three total lines, which is why")
        void labelPlusLeaderReachesTheAmountColumn() {
            assertThat(TranReportLayouts.PAGE_TOTAL_LABEL_LENGTH
                    + TranReportLayouts.PAGE_TOTAL_LEADER_LENGTH).isEqualTo(97);
            assertThat(TranReportLayouts.ACCOUNT_TOTAL_LABEL_LENGTH
                    + TranReportLayouts.ACCOUNT_TOTAL_LEADER_LENGTH).isEqualTo(97);
            assertThat(TranReportLayouts.GRAND_TOTAL_LABEL_LENGTH
                    + TranReportLayouts.GRAND_TOTAL_LEADER_LENGTH).isEqualTo(97);
            assertThat(TranReportLayouts.REPT_PAGE_TOTAL_OFFSET).isEqualTo(97);
            assertThat(TranReportLayouts.REPT_ACCOUNT_TOTAL_OFFSET).isEqualTo(97);
            assertThat(TranReportLayouts.REPT_GRAND_TOTAL_OFFSET).isEqualTo(97);
        }

        @Test
        @DisplayName("each leader image is its own width of dots and nothing else")
        void leaderImagesAreDots() {
            assertThat(TranReportLayouts.PAGE_TOTAL_LEADER_IMAGE)
                    .isEqualTo(LEADER_86).hasSize(86).matches("\\.+");
            assertThat(TranReportLayouts.ACCOUNT_TOTAL_LEADER_IMAGE)
                    .isEqualTo(LEADER_84).hasSize(84).matches("\\.+");
            assertThat(TranReportLayouts.GRAND_TOTAL_LEADER_IMAGE)
                    .isEqualTo(LEADER_86).hasSize(86).matches("\\.+");
            assertThat(TranReportLayouts.TOTAL_LEADER_CHARACTER).isEqualTo('.');
        }

        @Test
        @DisplayName("the rendered amount really lands on columns 98-112 in all four layouts")
        void renderedAmountsOccupyColumns98To112() {
            writeDetailLine();
            layouts.moveReptPageTotal(new BigDecimal("123456789.12"));
            layouts.moveReptAccountTotal(new BigDecimal("123456789.12"));
            layouts.moveReptGrandTotal(new BigDecimal("123456789.12"));
            assertThat(layouts.renderTransactionDetailReport().substring(97, 112))
                    .isEqualTo(spaced("-______1,234.56"));
            assertThat(layouts.renderReportPageTotals().substring(97, 112))
                    .isEqualTo("+123,456,789.12");
            assertThat(layouts.renderReportAccountTotals().substring(97, 112))
                    .isEqualTo("+123,456,789.12");
            assertThat(layouts.renderReportGrandTotals().substring(97, 112))
                    .isEqualTo("+123,456,789.12");
        }

        @Test
        @DisplayName("the byte at column 97 is the last of the prefix, never part of the amount")
        void columnNinetySevenBelongsToThePrefix() {
            layouts.moveReptPageTotal(new BigDecimal("123456789.12"));
            layouts.moveReptAccountTotal(new BigDecimal("123456789.12"));
            assertThat(layouts.renderReportPageTotals().charAt(96)).isEqualTo('.');
            assertThat(layouts.renderReportAccountTotals().charAt(96)).isEqualTo('.');
            writeDetailLine();
            assertThat(layouts.renderTransactionDetailReport().charAt(96)).isEqualTo(' ');
        }
    }

    @Nested
    @DisplayName("REPORT-NAME-HEADER - 115 bytes, CBTRN03C:277-278 and :325")
    class ReportNameHeader {

        @Test
        @DisplayName("the three captions and the ' to ' separator are byte-exact")
        void literalsAreByteExact() {
            assertThat(TranReportLayouts.REPT_SHORT_NAME_VALUE).isEqualTo("DALYREPT").hasSize(8);
            assertThat(TranReportLayouts.REPT_LONG_NAME_VALUE)
                    .isEqualTo("Daily Transaction Report").hasSize(24);
            assertThat(TranReportLayouts.REPT_DATE_HEADER_VALUE).isEqualTo("Date Range: ")
                    .hasSize(12)
                    .as("the trailing space exactly fills X(12) and is data, not padding")
                    .endsWith(" ");
            assertThat(TranReportLayouts.DATE_RANGE_SEPARATOR_VALUE).isEqualTo(" to ").hasSize(4)
                    .as("both spaces exactly fill X(04) and are data, not padding")
                    .startsWith(" ").endsWith(" ");
        }

        @Test
        @DisplayName("'DALYREPT' is right-padded to 38 and the long title to 41")
        void shortAndLongNamesArePadded() {
            assertThat(layouts.reptShortName()).isEqualTo(SHORT_NAME_IMAGE).hasSize(38);
            assertThat(layouts.reptLongName()).isEqualTo(LONG_NAME_IMAGE).hasSize(41);
            assertThat(layouts.reptDateHeader()).isEqualTo("Date Range: ").hasSize(12);
        }

        @Test
        @DisplayName("both dates are spaces until they are moved into")
        void datesStartAsSpaces() {
            assertThat(layouts.reptStartDate()).isEqualTo(" ".repeat(10));
            assertThat(layouts.reptEndDate()).isEqualTo(" ".repeat(10));
        }

        @Test
        @DisplayName("MOVE WS-START-DATE / WS-END-DATE produces the whole 115-byte header")
        void theWholeHeaderImage() {
            layouts.moveReptStartDate("2022-01-01");
            layouts.moveReptEndDate("2022-07-06");
            String expected = SHORT_NAME_IMAGE   // cols   1- 38  REPT-SHORT-NAME   X(38)
                    + LONG_NAME_IMAGE            // cols  39- 79  REPT-LONG-NAME    X(41)
                    + "Date Range: "             // cols  80- 91  REPT-DATE-HEADER  X(12)
                    + "2022-01-01"               // cols  92-101  REPT-START-DATE   X(10)
                    + " to "                     // cols 102-105  FILLER            X(04)
                    + "2022-07-06";              // cols 106-115  REPT-END-DATE     X(10)
            assertThat(layouts.renderReportNameHeader()).isEqualTo(expected).hasSize(115);
            assertThat(layouts.reptStartDate()).isEqualTo("2022-01-01");
            assertThat(layouts.reptEndDate()).isEqualTo("2022-07-06");
        }

        @Test
        @DisplayName("the ' to ' separator sits on columns 102-105, spaces included")
        void separatorSitsOnColumns102To105() {
            layouts.moveReptStartDate("2022-01-01");
            layouts.moveReptEndDate("2022-07-06");
            assertThat(layouts.renderReportNameHeader().substring(101, 105)).isEqualTo(" to ");
        }

        @ParameterizedTest(name = "[{index}] MOVE \"{0}\" -> \"{1}\"")
        @DisplayName("a short date is right-padded and an over-long one truncated on the right")
        @CsvSource(delimiter = '|', value = {
            "2022-01-01          | 2022-01-01",
            "2022-1-1            | 2022-1-1__",
            "__________          | __________",
            "_                   | __________",
            "2022-01-01T         | 2022-01-01",
            "2022-01-01T12:00:00 | 2022-01-01",
        })
        void datesFollowThePicXMoveRule(String sent, String expected) {
            layouts.moveReptStartDate(spaced(sent));
            assertThat(layouts.reptStartDate()).isEqualTo(spaced(expected)).hasSize(10);
        }

        @Test
        @DisplayName("a null date is rejected: COBOL has no null and the sender is PIC X(10)")
        void nullDatesRejected() {
            assertThatNullPointerException().isThrownBy(() -> layouts.moveReptStartDate(null));
            assertThatNullPointerException().isThrownBy(() -> layouts.moveReptEndDate(null));
        }
    }

    @Nested
    @DisplayName("TRANSACTION-HEADER-1 - 114 bytes, every item a FILLER, CBTRN03C:333")
    class TransactionHeaderOne {

        @Test
        @DisplayName("the five captions are 14, 10, 16, 13 and 11 characters")
        void captionLengths() {
            assertThat(TranReportLayouts.HEADER_1_TRANSACTION_ID_VALUE)
                    .isEqualTo("Transaction ID").hasSize(14);
            assertThat(TranReportLayouts.HEADER_1_ACCOUNT_ID_VALUE)
                    .isEqualTo("Account ID").hasSize(10);
            assertThat(TranReportLayouts.HEADER_1_TRANSACTION_TYPE_VALUE)
                    .isEqualTo("Transaction Type").hasSize(16);
            assertThat(TranReportLayouts.HEADER_1_TRAN_CATEGORY_VALUE)
                    .isEqualTo("Tran Category").hasSize(13);
            assertThat(TranReportLayouts.HEADER_1_TRAN_SOURCE_VALUE)
                    .isEqualTo("Tran Source").hasSize(11);
        }

        @Test
        @DisplayName("'        Amount' carries exactly eight leading spaces")
        void amountCaptionHasEightLeadingSpaces() {
            assertThat(TranReportLayouts.HEADER_1_AMOUNT_VALUE).hasSize(14).endsWith("Amount");
            assertThat(TranReportLayouts.HEADER_1_AMOUNT_LEADING_SPACES).isEqualTo(8);
            assertThat(TranReportLayouts.countLeadingSpaces(TranReportLayouts.HEADER_1_AMOUNT_VALUE))
                    .isEqualTo(8);
            assertThat(TranReportLayouts.HEADER_1_AMOUNT_VALUE)
                    .isEqualTo(spaced("________Amount"));
        }

        @Test
        @DisplayName("the whole 114-byte header, caption by caption")
        void theWholeHeaderImage() {
            String expected = "Transaction ID   "        // cols   1- 17  X(17)
                    + "Account ID  "                     // cols  18- 29  X(12)
                    + "Transaction Type   "              // cols  30- 48  X(19)
                    + "Tran Category" + " ".repeat(22)   // cols  49- 83  X(35)
                    + "Tran Source   "                   // cols  84- 97  X(14)
                    + " "                                // col       98  X
                    + "        Amount  ";                // cols  99-114  X(16)
            assertThat(layouts.renderTransactionHeader1()).isEqualTo(expected).hasSize(114);
        }

        @Test
        @DisplayName("the word 'Amount' occupies columns 107-112, ending on the mask's last column")
        void amountWordOccupiesColumns107To112() {
            assertThat(TranReportLayouts.HEADER_1_AMOUNT_WORD_COLUMN_START).isEqualTo(107);
            assertThat(layouts.renderTransactionHeader1().substring(106, 112)).isEqualTo("Amount");
            assertThat(TranReportLayouts.HEADER_1_AMOUNT_WORD_COLUMN_START + "Amount".length() - 1)
                    .as("its last character sits on the amount mask's last digit column")
                    .isEqualTo(TranReportLayouts.AMOUNT_COLUMN_END);
        }

        @Test
        @DisplayName("the one-byte gap at column 98 is what stops the captions at column 97")
        void gapAtColumnNinetyEight() {
            assertThat(TranReportLayouts.HEADER_1_GAP_LENGTH).isEqualTo(1);
            assertThat(layouts.renderTransactionHeader1().charAt(97)).isEqualTo(' ');
            assertThat(layouts.renderTransactionHeader1().substring(83, 97))
                    .isEqualTo("Tran Source   ");
        }

        @Test
        @DisplayName("the line is constant: nothing in it is ever moved into")
        void headerOneIsConstant() {
            String before = layouts.renderTransactionHeader1();
            writeDetailLine();
            layouts.moveReptGrandTotal(new BigDecimal("999999999.99"));
            assertThat(layouts.renderTransactionHeader1()).isEqualTo(before);
            assertThat(new TranReportLayouts(EBCDIC).renderTransactionHeader1()).isEqualTo(before);
        }
    }

    @Nested
    @DisplayName("TRANSACTION-HEADER-2 - 133 hyphens, CBTRN03C:300, :312 and :337")
    class TransactionHeaderTwo {

        @Test
        @DisplayName("exactly 133 characters and every one of them a hyphen")
        void exactlyOneHundredThirtyThreeHyphens() {
            assertThat(layouts.renderTransactionHeader2()).hasSize(133).matches("-+")
                    .isEqualTo("-".repeat(133));
            assertThat(TranReportLayouts.TRANSACTION_HEADER_2_RULE_CHARACTER).isEqualTo('-');
            assertThat(TranReportLayouts.TRANSACTION_HEADER_2_IMAGE).isEqualTo("-".repeat(133));
        }

        @Test
        @DisplayName("it is already the record width, so the writer passes it through unpadded")
        void alreadyTheRecordWidth() {
            assertThat(layouts.renderTransactionHeader2()).hasSize(133);
            assertThat(TranReportLayouts.TRANSACTION_HEADER_2_LENGTH).isEqualTo(133);
        }

        @Test
        @DisplayName("the same three write sites see the same image every time")
        void stableAcrossReads() {
            String first = layouts.renderTransactionHeader2();
            writeDetailLine();
            assertThat(layouts.renderTransactionHeader2()).isEqualTo(first);
            assertThat(layouts.renderTransactionHeader2()).isEqualTo(first);
        }
    }

    @Nested
    @DisplayName("TRANSACTION-DETAIL-REPORT - the eight moves of CBTRN03C:363-370")
    class DetailMoves {

        @Test
        @DisplayName("the whole 114-byte detail line, item by item")
        void theWholeDetailImage() {
            writeDetailLine();
            String expected = "0000000000000001"              // cols   1- 16  X(16) TRANS-ID
                    + " "                                     // col       17  X(01) FILLER
                    + "10000000001"                           // cols  18- 28  X(11) ACCOUNT-ID
                    + " "                                     // col       29  X(01) FILLER
                    + "01"                                    // cols  30- 31  X(02) TYPE-CD
                    + "-"                                     // col       32  X(01) FILLER '-'
                    + "Purchase       "                       // cols  33- 47  X(15) TYPE-DESC
                    + " "                                     // col       48  X(01) FILLER
                    + "5001"                                  // cols  49- 52  9(04) CAT-CD
                    + "-"                                     // col       53  X(01) FILLER '-'
                    + "Regular Sales Draft" + " ".repeat(10)  // cols  54- 82  X(29) CAT-DESC
                    + " "                                     // col       83  X(01) FILLER
                    + "POS TERM  "                            // cols  84- 93  X(10) SOURCE
                    + "    "                                  // cols  94- 97  X(04) FILLER
                    + spaced("-______1,234.56")               // cols  98-112  the edit mask
                    + "  ";                                   // cols 113-114  X(02) FILLER
            assertThat(layouts.renderTransactionDetailReport()).isEqualTo(expected).hasSize(114);
        }

        @Test
        @DisplayName("every field accessor reads back exactly what was moved in")
        void fieldAccessorsReadBack() {
            writeDetailLine();
            assertThat(layouts.tranReportTransId()).isEqualTo("0000000000000001").hasSize(16);
            assertThat(layouts.tranReportAccountId()).isEqualTo("10000000001").hasSize(11);
            assertThat(layouts.tranReportTypeCd()).isEqualTo("01").hasSize(2);
            assertThat(layouts.tranReportTypeDesc()).isEqualTo("Purchase       ").hasSize(15);
            assertThat(layouts.tranReportCatCd()).isEqualTo("5001").hasSize(4);
            assertThat(layouts.tranReportCatCdValue()).isEqualTo(5001);
            assertThat(layouts.tranReportCatDesc())
                    .isEqualTo("Regular Sales Draft" + " ".repeat(10)).hasSize(29);
            assertThat(layouts.tranReportSource()).isEqualTo("POS TERM  ").hasSize(10);
            assertThat(layouts.tranReportAmt()).isEqualTo(spaced("-______1,234.56")).hasSize(15);
        }

        @Test
        @DisplayName("the two '-' separators render on 1-based columns 32 and 53 (gate G21)")
        void separatorsOnColumns32And53() {
            writeDetailLine();
            String image = layouts.renderTransactionDetailReport();
            assertThat(image.charAt(31)).as("1-based column 32").isEqualTo('-');
            assertThat(image.charAt(52)).as("1-based column 53").isEqualTo('-');
            assertThat(image.substring(29, 33)).as("type code, separator, first of the description")
                    .isEqualTo("01-P");
            assertThat(image.substring(48, 54)).as("category code, separator, first of description")
                    .isEqualTo("5001-R");
        }

        @Test
        @DisplayName("TRAN-TYPE-DESC X(50) is right-truncated to the leftmost 15 characters")
        void typeDescriptionRightTruncatedTo15() {
            layouts.moveTranReportTypeDesc("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789ABCDEFGHIJKLMN");
            assertThat(layouts.tranReportTypeDesc()).isEqualTo("ABCDEFGHIJKLMNO").hasSize(15);
            layouts.moveTranReportTypeDesc("Debit");
            assertThat(layouts.tranReportTypeDesc()).isEqualTo("Debit          ").hasSize(15);
        }

        @Test
        @DisplayName("TRAN-CAT-TYPE-DESC X(50) is right-truncated to the leftmost 29 characters")
        void categoryDescriptionRightTruncatedTo29() {
            layouts.moveTranReportCatDesc("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789ABCDEFGHIJKLMN");
            assertThat(layouts.tranReportCatDesc()).isEqualTo("ABCDEFGHIJKLMNOPQRSTUVWXYZ012")
                    .hasSize(29);
            layouts.moveTranReportCatDesc("Cash");
            assertThat(layouts.tranReportCatDesc()).isEqualTo("Cash" + " ".repeat(25)).hasSize(29);
        }

        @ParameterizedTest(name = "[{index}] XREF-ACCT-ID {0} -> \"{1}\"")
        @DisplayName("XREF-ACCT-ID 9(11) into X(11) is a numeric-to-alphanumeric move")
        @CsvSource(delimiter = '|', value = {
            "0            | 00000000000",
            "1            | 00000000001",
            "10000000001  | 10000000001",
            "99999999999  | 99999999999",
        })
        void accountIdNumericToAlphanumeric(long accountId, String expected) {
            layouts.moveTranReportAccountId(accountId);
            assertThat(layouts.tranReportAccountId()).isEqualTo(expected).hasSize(11);
        }

        @Test
        @DisplayName("the account id can also be moved from the sender's already-rendered digits")
        void accountIdFromDigitImage() {
            layouts.moveTranReportAccountId("00000000042");
            assertThat(layouts.tranReportAccountId()).isEqualTo("00000000042");
        }

        @Test
        @DisplayName("a negative account id is rejected, because PIC 9(11) is unsigned")
        void negativeAccountIdRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> layouts.moveTranReportAccountId(-1L));
        }

        @ParameterizedTest(name = "[{index}] TRAN-CAT-CD {0} -> \"{1}\"")
        @DisplayName("TRAN-CAT-CD 9(04) zero-fills on the left and truncates on the left")
        @CsvSource(delimiter = '|', value = {
            "0     | 0000",
            "1     | 0001",
            "5001  | 5001",
            "9999  | 9999",
            "15001 | 5001",
        })
        void categoryCodeFollowsThePic9MoveRule(long categoryCode, String expected) {
            layouts.moveTranReportCatCd(categoryCode);
            assertThat(layouts.tranReportCatCd()).isEqualTo(expected).hasSize(4);
            assertThat(layouts.tranReportCatCdValue()).isEqualTo(Integer.parseInt(expected));
        }

        @Test
        @DisplayName("the category code can also be moved from the sender's digit image")
        void categoryCodeFromDigitImage() {
            layouts.moveTranReportCatCd("0007");
            assertThat(layouts.tranReportCatCd()).isEqualTo("0007");
            assertThat(layouts.tranReportCatCdValue()).isEqualTo(7);
            layouts.moveTranReportCatCd("7");
            assertThat(layouts.tranReportCatCd()).isEqualTo("0007");
        }

        @Test
        @DisplayName("a negative category code is rejected, because PIC 9(04) is unsigned")
        void negativeCategoryCodeRejected() {
            assertThatIllegalArgumentException().isThrownBy(() -> layouts.moveTranReportCatCd(-1L));
        }

        @Test
        @DisplayName("a non-digit category code image is rejected rather than silently zeroed")
        void nonDigitCategoryCodeRejected() {
            assertThatIllegalArgumentException().isThrownBy(() -> layouts.moveTranReportCatCd("0A1"));
        }

        @Test
        @DisplayName("the transaction id and source are equal-width moves, so nothing truncates")
        void equalWidthMoves() {
            layouts.moveTranReportTransId("ABCDEFGHIJKLMNOP");
            layouts.moveTranReportTypeCd("XY");
            layouts.moveTranReportSource("WEBSITE   ");
            assertThat(layouts.tranReportTransId()).isEqualTo("ABCDEFGHIJKLMNOP");
            assertThat(layouts.tranReportTypeCd()).isEqualTo("XY");
            assertThat(layouts.tranReportSource()).isEqualTo("WEBSITE   ");
        }

        @Test
        @DisplayName("TRAN-REPORT-AMT uses the '-' mask, so a positive amount leaves column 98 blank")
        void amountUsesTheMinusMask() {
            layouts.moveTranReportAmt(new BigDecimal("1234.56"));
            assertThat(layouts.tranReportAmt()).isEqualTo(spaced("_______1,234.56"));
            assertThat(layouts.renderTransactionDetailReport().charAt(97))
                    .as("column 98 is blank for a positive detail amount, never '+'")
                    .isEqualTo(' ');
            layouts.moveTranReportAmt(new BigDecimal("-1234.56"));
            assertThat(layouts.renderTransactionDetailReport().charAt(97)).isEqualTo('-');
        }

        @Test
        @DisplayName("every null sending value is rejected")
        void nullsRejected() {
            assertThatNullPointerException().isThrownBy(() -> layouts.moveTranReportTransId(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> layouts.moveTranReportAccountId((String) null));
            assertThatNullPointerException().isThrownBy(() -> layouts.moveTranReportTypeCd(null));
            assertThatNullPointerException().isThrownBy(() -> layouts.moveTranReportTypeDesc(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> layouts.moveTranReportCatCd((String) null));
            assertThatNullPointerException().isThrownBy(() -> layouts.moveTranReportCatDesc(null));
            assertThatNullPointerException().isThrownBy(() -> layouts.moveTranReportSource(null));
            assertThatNullPointerException().isThrownBy(() -> layouts.moveTranReportAmt(null));
        }
    }

    @Nested
    @DisplayName("INITIALIZE semantics - CBTRN03C:362, and it must not touch FILLER")
    class InitializeSemantics {

        @Test
        @DisplayName("the whole 114-byte image after INITIALIZE, separators and all")
        void theWholeInitialisedImage() {
            writeDetailLine();
            layouts.initializeTransactionDetailReport();
            String expected = " ".repeat(31)   // cols   1- 31  TRANS-ID, FILLER, ACCOUNT-ID,
                                               //               FILLER, TYPE-CD - all spaced
                    + "-"                      // col       32  FILLER '-'   <== survives
                    + " ".repeat(16)           // cols  33- 48  TYPE-DESC and its FILLER
                    + "0000"                   // cols  49- 52  CAT-CD receives ZERO
                    + "-"                      // col       53  FILLER '-'   <== survives
                    + " ".repeat(44)           // cols  54- 97  CAT-DESC, FILLER, SOURCE, FILLER
                    + " ".repeat(15)           // cols  98-112  numeric-edited ZERO is 15 spaces
                    + "  ";                    // cols 113-114  FILLER
            assertThat(layouts.renderTransactionDetailReport()).isEqualTo(expected).hasSize(114);
        }

        @Test
        @DisplayName("both '-' separators survive: INITIALIZE does not affect FILLER")
        void separatorFillersSurvive() {
            writeDetailLine();
            layouts.initializeTransactionDetailReport();
            String image = layouts.renderTransactionDetailReport();
            assertThat(image.charAt(31)).as("1-based column 32").isEqualTo('-');
            assertThat(image.charAt(52)).as("1-based column 53").isEqualTo('-');
        }

        @Test
        @DisplayName("all six space FILLERs survive too, at columns 17, 29, 48, 83, 94-97, 113-114")
        void spaceFillersSurvive() {
            writeDetailLine();
            layouts.initializeTransactionDetailReport();
            String image = layouts.renderTransactionDetailReport();
            assertThat(image.charAt(16)).as("column 17").isEqualTo(' ');
            assertThat(image.charAt(28)).as("column 29").isEqualTo(' ');
            assertThat(image.charAt(47)).as("column 48").isEqualTo(' ');
            assertThat(image.charAt(82)).as("column 83").isEqualTo(' ');
            assertThat(image.substring(93, 97)).as("columns 94-97").isEqualTo("    ");
            assertThat(image.substring(112, 114)).as("columns 113-114").isEqualTo("  ");
        }

        @Test
        @DisplayName("TRAN-REPORT-CAT-CD is numeric, so it receives ZERO and becomes '0000'")
        void categoryCodeBecomesZeros() {
            layouts.moveTranReportCatCd(5001L);
            layouts.initializeTransactionDetailReport();
            assertThat(layouts.tranReportCatCd()).isEqualTo("0000");
            assertThat(layouts.tranReportCatCdValue()).isZero();
        }

        @Test
        @DisplayName("TRAN-REPORT-AMT is numeric-edited, so ZERO renders as 15 spaces")
        void amountBecomesFifteenSpaces() {
            layouts.moveTranReportAmt(new BigDecimal("-1234.56"));
            layouts.initializeTransactionDetailReport();
            assertThat(layouts.tranReportAmt()).isEqualTo(BLANK_AMOUNT).hasSize(15);
        }

        @Test
        @DisplayName("the six named PIC X items become spaces")
        void alphanumericItemsBecomeSpaces() {
            writeDetailLine();
            layouts.initializeTransactionDetailReport();
            assertThat(layouts.tranReportTransId()).isEqualTo(" ".repeat(16));
            assertThat(layouts.tranReportAccountId()).isEqualTo(" ".repeat(11));
            assertThat(layouts.tranReportTypeCd()).isEqualTo("  ");
            assertThat(layouts.tranReportTypeDesc()).isEqualTo(" ".repeat(15));
            assertThat(layouts.tranReportCatDesc()).isEqualTo(" ".repeat(29));
            assertThat(layouts.tranReportSource()).isEqualTo(" ".repeat(10));
        }

        @Test
        @DisplayName("it is idempotent, and a freshly allocated area already matches it")
        void idempotentAndMatchesAFreshArea() {
            String fresh = layouts.renderTransactionDetailReport();
            layouts.initializeTransactionDetailReport();
            String once = layouts.renderTransactionDetailReport();
            layouts.initializeTransactionDetailReport();
            assertThat(layouts.renderTransactionDetailReport()).isEqualTo(once);
            assertThat(once)
                    .as("VALUE-clause initialisation and the INITIALIZE verb agree on this layout, "
                            + "because its eight FILLERs already hold their declared values")
                    .isEqualTo(fresh);
        }

        @Test
        @DisplayName("it touches only the detail line, never another layout")
        void touchesOnlyTheDetailLine() {
            layouts.moveReptStartDate("2022-01-01");
            layouts.moveReptPageTotal(new BigDecimal("10.00"));
            String header = layouts.renderReportNameHeader();
            String totals = layouts.renderReportPageTotals();
            layouts.initializeTransactionDetailReport();
            assertThat(layouts.renderReportNameHeader()).isEqualTo(header);
            assertThat(layouts.renderReportPageTotals()).isEqualTo(totals);
        }
    }

    @Nested
    @DisplayName("The three total lines - 112 bytes each, CBTRN03C:294, :307 and :319")
    class TotalLines {

        @Test
        @DisplayName("the three labels are byte-exact, and only 'Page Total' leaves a trailing space")
        void labelsAreByteExact() {
            assertThat(TranReportLayouts.PAGE_TOTAL_LABEL_VALUE).isEqualTo("Page Total").hasSize(10);
            assertThat(TranReportLayouts.ACCOUNT_TOTAL_LABEL_VALUE).isEqualTo("Account Total")
                    .hasSize(13).hasSize(TranReportLayouts.ACCOUNT_TOTAL_LABEL_LENGTH);
            assertThat(TranReportLayouts.GRAND_TOTAL_LABEL_VALUE).isEqualTo("Grand Total")
                    .hasSize(11).hasSize(TranReportLayouts.GRAND_TOTAL_LABEL_LENGTH);
            assertThat(TranReportLayouts.PAGE_TOTAL_LABEL_VALUE.length())
                    .as("'Page Total' is the only label that does not fill its field")
                    .isLessThan(TranReportLayouts.PAGE_TOTAL_LABEL_LENGTH);
        }

        @Test
        @DisplayName("MOVE WS-PAGE-TOTAL produces the whole 112-byte page-total line")
        void theWholePageTotalImage() {
            layouts.moveReptPageTotal(new BigDecimal("123456789.12"));
            String expected = "Page Total "         // cols   1- 11  X(11), one trailing space
                    + LEADER_86                     // cols  12- 97  X(86) ALL '.'
                    + "+123,456,789.12";            // cols  98-112  +ZZZ,ZZZ,ZZZ.ZZ
            assertThat(layouts.renderReportPageTotals()).isEqualTo(expected).hasSize(112);
            assertThat(layouts.reptPageTotal()).isEqualTo("+123,456,789.12");
        }

        @Test
        @DisplayName("MOVE WS-ACCOUNT-TOTAL produces the whole 112-byte account-total line")
        void theWholeAccountTotalImage() {
            layouts.moveReptAccountTotal(new BigDecimal("-0.05"));
            String expected = "Account Total"        // cols   1- 13  X(13), exactly filled
                    + LEADER_84                      // cols  14- 97  X(84) ALL '.'
                    + spaced("-___________.05");     // cols  98-112  +ZZZ,ZZZ,ZZZ.ZZ
            assertThat(layouts.renderReportAccountTotals()).isEqualTo(expected).hasSize(112);
            assertThat(layouts.reptAccountTotal()).isEqualTo(spaced("-___________.05"));
        }

        @Test
        @DisplayName("MOVE WS-GRAND-TOTAL produces the whole 112-byte grand-total line")
        void theWholeGrandTotalImage() {
            layouts.moveReptGrandTotal(new BigDecimal("999999999.99"));
            String expected = "Grand Total"          // cols   1- 11  X(11), exactly filled
                    + LEADER_86                      // cols  12- 97  X(86) ALL '.'
                    + "+999,999,999.99";             // cols  98-112  +ZZZ,ZZZ,ZZZ.ZZ
            assertThat(layouts.renderReportGrandTotals()).isEqualTo(expected).hasSize(112);
            assertThat(layouts.reptGrandTotal()).isEqualTo("+999,999,999.99");
        }

        @Test
        @DisplayName("a total that nets to zero prints a blank amount column, not +0.00")
        void zeroTotalsPrintBlank() {
            layouts.moveReptPageTotal(new BigDecimal("0.00"));
            layouts.moveReptAccountTotal(BigDecimal.ZERO);
            layouts.moveReptGrandTotal(new BigDecimal("-0.00"));
            assertThat(layouts.reptPageTotal()).isEqualTo(BLANK_AMOUNT);
            assertThat(layouts.reptAccountTotal()).isEqualTo(BLANK_AMOUNT);
            assertThat(layouts.reptGrandTotal()).isEqualTo(BLANK_AMOUNT);
            assertThat(layouts.renderReportGrandTotals())
                    .isEqualTo("Grand Total" + LEADER_86 + BLANK_AMOUNT).hasSize(112);
        }

        @Test
        @DisplayName("an un-moved total reads as the zero image, because 15 spaces is exactly that")
        void unmovedTotalsReadAsZero() {
            assertThat(layouts.reptPageTotal()).isEqualTo(BLANK_AMOUNT);
            assertThat(layouts.reptAccountTotal()).isEqualTo(BLANK_AMOUNT);
            assertThat(layouts.reptGrandTotal()).isEqualTo(BLANK_AMOUNT);
        }

        @Test
        @DisplayName("the three total lines are independent of one another")
        void totalLinesAreIndependent() {
            layouts.moveReptPageTotal(new BigDecimal("1.00"));
            layouts.moveReptAccountTotal(new BigDecimal("2.00"));
            layouts.moveReptGrandTotal(new BigDecimal("3.00"));
            assertThat(layouts.reptPageTotal()).isEqualTo(spaced("+__________1.00"));
            assertThat(layouts.reptAccountTotal()).isEqualTo(spaced("+__________2.00"));
            assertThat(layouts.reptGrandTotal()).isEqualTo(spaced("+__________3.00"));
        }

        @Test
        @DisplayName("every null total is rejected")
        void nullTotalsRejected() {
            assertThatNullPointerException().isThrownBy(() -> layouts.moveReptPageTotal(null));
            assertThatNullPointerException().isThrownBy(() -> layouts.moveReptAccountTotal(null));
            assertThatNullPointerException().isThrownBy(() -> layouts.moveReptGrandTotal(null));
        }
    }

    @Nested
    @DisplayName("Numeric-edited rendering - the two 15-byte all-Z masks")
    class NumericEditing {

        @ParameterizedTest(name = "[{index}] -ZZZ,ZZZ,ZZZ.ZZ of {0} is \"{1}\"")
        @DisplayName("the detail mask: a fixed minus, and a space where a plus would be")
        @CsvSource(delimiter = '|', value = {
            "0             | _______________",
            "0.00          | _______________",
            "-0.00         | _______________",
            "0.001         | _______________",
            "-0.001        | _______________",
            "0.05          | ____________.05",
            "-0.05         | -___________.05",
            "0.99          | ____________.99",
            "1.00          | ___________1.00",
            "-1.00         | -__________1.00",
            "10.10         | __________10.10",
            "999.99        | _________999.99",
            "-999.99       | -________999.99",
            "1000.00       | _______1,000.00",
            "1234.56       | _______1,234.56",
            "-1234.56      | -______1,234.56",
            "1000000.00    | ___1,000,000.00",
            "-1000000.00   | -__1,000,000.00",
            "100000000.00  | _100,000,000.00",
            "123456789.12  | _123,456,789.12",
            "999999999.99  | _999,999,999.99",
            "-999999999.99 | -999,999,999.99",
        })
        void detailMask(String value, String expected) {
            assertThat(TranReportLayouts.editDetailAmount(new BigDecimal(value)))
                    .isEqualTo(spaced(expected)).hasSize(15);
        }

        @ParameterizedTest(name = "[{index}] +ZZZ,ZZZ,ZZZ.ZZ of {0} is \"{1}\"")
        @DisplayName("the total mask: a fixed plus for non-negative, a minus for negative")
        @CsvSource(delimiter = '|', value = {
            "0             | _______________",
            "0.00          | _______________",
            "-0.00         | _______________",
            "0.05          | +___________.05",
            "-0.05         | -___________.05",
            "1.00          | +__________1.00",
            "-1.00         | -__________1.00",
            "999.99        | +________999.99",
            "-999.99       | -________999.99",
            "1000.00       | +______1,000.00",
            "1234.56       | +______1,234.56",
            "-1234.56      | -______1,234.56",
            "1000000.00    | +__1,000,000.00",
            "-1000000.00   | -__1,000,000.00",
            "123456789.12  | +123,456,789.12",
            "999999999.99  | +999,999,999.99",
            "-999999999.99 | -999,999,999.99",
        })
        void totalMask(String value, String expected) {
            assertThat(TranReportLayouts.editTotalAmount(new BigDecimal(value)))
                    .isEqualTo(spaced(expected)).hasSize(15);
        }

        @ParameterizedTest(name = "[{index}] {0} blanks the whole item")
        @DisplayName("the all-Z zero rule: zero blanks sign, commas and the decimal point too")
        @ValueSource(strings = {"0", "0.0", "0.00", "-0", "-0.0", "-0.00", "0.000", "0.001",
            "-0.009", "0.0000000001", "-0.0000000001"})
        void zeroBlanksTheEntireItem(String value) {
            BigDecimal amount = new BigDecimal(value);
            assertThat(TranReportLayouts.editDetailAmount(amount)).isEqualTo(BLANK_AMOUNT)
                    .as("neither 0.00 nor .00 - the whole 15-byte item is spaces")
                    .doesNotContain(".").doesNotContain("0").doesNotContain("+")
                    .doesNotContain("-").doesNotContain(",");
            assertThat(TranReportLayouts.editTotalAmount(amount)).isEqualTo(BLANK_AMOUNT);
        }

        @ParameterizedTest(name = "[{index}] the sign of {0} sits in position 1")
        @DisplayName("the fixed insertion sign never floats away from position 1")
        @ValueSource(strings = {"-0.01", "-9.99", "-999.99", "-1234.56", "-1000000.00",
            "-999999999.99"})
        void theSignStaysInPositionOne(String value) {
            BigDecimal amount = new BigDecimal(value);
            assertThat(TranReportLayouts.editDetailAmount(amount)).startsWith("-")
                    .satisfies(image -> assertThat(image.substring(1)).doesNotContain("-"));
            assertThat(TranReportLayouts.editTotalAmount(amount)).startsWith("-");
            assertThat(TranReportLayouts.editTotalAmount(amount.negate())).startsWith("+")
                    .satisfies(image -> assertThat(image.substring(1)).doesNotContain("+"));
            assertThat(TranReportLayouts.editDetailAmount(amount.negate())).startsWith(" ");
        }

        @ParameterizedTest(name = "[{index}] a comma left of the first digit of {0} is suppressed")
        @DisplayName("insertion-character suppression: a comma left of the first digit blanks")
        @CsvSource(delimiter = '|', value = {
            "0.05       | 0 | 0",
            "999.99     | 0 | 0",
            "1234.56    | 1 | 1",
            "1000.00    | 1 | 1",
            "1000000.00 | 2 | 2",
        })
        void commasSuppressLeftOfTheFirstDigit(String value, int detailCommas, int totalCommas) {
            assertThat(TranReportLayouts.countOf(
                    TranReportLayouts.editDetailAmount(new BigDecimal(value)), ','))
                    .isEqualTo(detailCommas);
            assertThat(TranReportLayouts.countOf(
                    TranReportLayouts.editTotalAmount(new BigDecimal(value)), ','))
                    .isEqualTo(totalCommas);
        }

        @ParameterizedTest(name = "[{index}] {0} truncates to {1}, never rounds")
        @DisplayName("RoundingMode.DOWN: DOWN not HALF_UP, and DOWN not FLOOR")
        @CsvSource(delimiter = '|', value = {
            "1.239   | ___________1.23",
            "-1.239  | -__________1.23",
            "1.235   | ___________1.23",
            "-1.235  | -__________1.23",
            "1.999   | ___________1.99",
            "-1.999  | -__________1.99",
            "10.415  | __________10.41",
            "-10.415 | -_________10.41",
        })
        void truncatesTowardsZero(String value, String expected) {
            assertThat(TranReportLayouts.editDetailAmount(new BigDecimal(value)))
                    .as("HALF_UP would give .24 and FLOOR would give -1.24; ROUNDED appears zero "
                            + "times in all 28 programs, so COBOL truncates")
                    .isEqualTo(spaced(expected));
        }

        @ParameterizedTest(name = "[{index}] {0} wraps rather than raising a size error")
        @DisplayName("no ON SIZE ERROR anywhere: an over-large value loses its high-order digits")
        @CsvSource(delimiter = '|', value = {
            "1234567890.12  | _234,567,890.12",
            "-1234567890.12 | -234,567,890.12",
            "1000000000.00  | _______________",
        })
        void oversizedValuesWrapSilently(String value, String expected) {
            assertThat(TranReportLayouts.editDetailAmount(new BigDecimal(value)))
                    .isEqualTo(spaced(expected)).hasSize(15);
        }

        @ParameterizedTest(name = "[{index}] {0} still renders exactly 15 characters")
        @DisplayName("every image is exactly 15 characters, whatever the value")
        @ValueSource(strings = {"0", "0.01", "-0.01", "9.99", "-9.99", "99.99", "999.99",
            "9999.99", "99999.99", "999999.99", "9999999.99", "99999999.99", "999999999.99",
            "-999999999.99", "-1", "1", "500000000.50"})
        void everyImageIsFifteenCharacters(String value) {
            BigDecimal amount = new BigDecimal(value);
            assertThat(TranReportLayouts.editDetailAmount(amount)).hasSize(15);
            assertThat(TranReportLayouts.editTotalAmount(amount)).hasSize(15);
        }

        @Test
        @DisplayName("the two masks differ in their sign position only")
        void masksDifferOnlyInTheirSign() {
            assertThat(TranReportLayouts.DETAIL_AMOUNT_MASK).isEqualTo("-ZZZ,ZZZ,ZZZ.ZZ").hasSize(15);
            assertThat(TranReportLayouts.TOTAL_AMOUNT_MASK).isEqualTo("+ZZZ,ZZZ,ZZZ.ZZ").hasSize(15);
            assertThat(TranReportLayouts.DETAIL_AMOUNT_MASK.substring(1))
                    .isEqualTo(TranReportLayouts.TOTAL_AMOUNT_MASK.substring(1));
            assertThat(TranReportLayouts.countOf(TranReportLayouts.DETAIL_AMOUNT_MASK, 'Z'))
                    .as("all eleven digit positions are suppression symbols")
                    .isEqualTo(11);
            assertThat(TranReportLayouts.countOf(TranReportLayouts.DETAIL_AMOUNT_MASK, ','))
                    .isEqualTo(2);
            assertThat(TranReportLayouts.countOf(TranReportLayouts.DETAIL_AMOUNT_MASK, '.'))
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("the scale a sender arrives at does not change the image")
        void scaleOfTheSenderDoesNotMatter() {
            assertThat(TranReportLayouts.editDetailAmount(new BigDecimal("1234.5")))
                    .isEqualTo(spaced("_______1,234.50"));
            assertThat(TranReportLayouts.editDetailAmount(new BigDecimal("1234")))
                    .isEqualTo(spaced("_______1,234.00"));
            assertThat(TranReportLayouts.editDetailAmount(new BigDecimal("1234.500")))
                    .isEqualTo(spaced("_______1,234.50"));
            assertThat(TranReportLayouts.editDetailAmount(new BigDecimal("1.234E3")))
                    .isEqualTo(spaced("_______1,234.00"));
        }

        @Test
        @DisplayName("a null amount is rejected: COBOL has no null")
        void nullAmountRejected() {
            assertThatNullPointerException()
                    .isThrownBy(() -> TranReportLayouts.editDetailAmount(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TranReportLayouts.editTotalAmount(null));
        }
    }

    @Nested
    @DisplayName("Natural width, never 133 - the contract with TranReportWriter")
    class NaturalWidthContract {

        @Test
        @DisplayName("the detail render is 114 characters, not 133: padding is the writer's job")
        void detailIsOneHundredFourteenNotOneHundredThirtyThree() {
            writeDetailLine();
            assertThat(layouts.renderTransactionDetailReport())
                    .as("absorbing the writer's padding would hide a width defect behind a "
                            + "correct-looking 133-byte record")
                    .hasSize(114)
                    .doesNotEndWith("  " + " ".repeat(19));
            assertThat(layouts.renderTransactionDetailReport().length()).isNotEqualTo(133);
            assertThat(layouts.renderTransactionDetailReportBytes()).hasSize(114);
        }

        @Test
        @DisplayName("only TRANSACTION-HEADER-2 is already 133 bytes wide")
        void onlyTheRuleLineIsRecordWidth() {
            assertThat(layouts.renderReportNameHeader().length()).isNotEqualTo(133);
            assertThat(layouts.renderTransactionDetailReport().length()).isNotEqualTo(133);
            assertThat(layouts.renderTransactionHeader1().length()).isNotEqualTo(133);
            assertThat(layouts.renderReportPageTotals().length()).isNotEqualTo(133);
            assertThat(layouts.renderReportAccountTotals().length()).isNotEqualTo(133);
            assertThat(layouts.renderReportGrandTotals().length()).isNotEqualTo(133);
            assertThat(layouts.renderTransactionHeader2()).hasSize(133);
        }

        @Test
        @DisplayName("no render ever ends in the padding a 133-byte record would need")
        void noRenderIsPrePadded() {
            layouts.moveReptStartDate("2022-01-01");
            layouts.moveReptEndDate("2022-07-06");
            assertThat(layouts.renderReportNameHeader()).endsWith("2022-07-06");
            layouts.moveReptGrandTotal(new BigDecimal("999999999.99"));
            assertThat(layouts.renderReportGrandTotals()).endsWith("+999,999,999.99");
        }
    }

    @Nested
    @DisplayName("The charset is always explicit, never a platform default")
    class Charsets {

        @Test
        @DisplayName("US-ASCII and IBM037 give the same characters and different bytes")
        void sameCharactersDifferentBytes() {
            TranReportLayouts ascii = new TranReportLayouts(ASCII);
            TranReportLayouts ebcdic = new TranReportLayouts(EBCDIC);
            assertThat(ebcdic.renderReportNameHeader()).isEqualTo(ascii.renderReportNameHeader());
            assertThat(ebcdic.renderTransactionHeader2()).isEqualTo(ascii.renderTransactionHeader2());
            assertThat(ebcdic.renderReportNameHeaderBytes())
                    .hasSameSizeAs(ascii.renderReportNameHeaderBytes())
                    .isNotEqualTo(ascii.renderReportNameHeaderBytes());
            assertThat(ascii.renderReportNameHeaderBytes()[8]).as("US-ASCII space").isEqualTo((byte) 0x20);
            assertThat(ebcdic.renderReportNameHeaderBytes()[8]).as("IBM037 space").isEqualTo((byte) 0x40);
        }

        @Test
        @DisplayName("the charset accessor reports what was supplied")
        void charsetAccessor() {
            assertThat(new TranReportLayouts(ASCII).charset()).isEqualTo(ASCII);
            assertThat(new TranReportLayouts(EBCDIC).charset()).isEqualTo(EBCDIC);
        }

        @Test
        @DisplayName("a caller-supplied codec is used as-is, so one charset serves a whole step")
        void codecConstructor() {
            FixedWidthCodec codec = new FixedWidthCodec(EBCDIC);
            TranReportLayouts shared = new TranReportLayouts(codec);
            assertThat(shared.charset()).isEqualTo(EBCDIC);
            shared.moveReptStartDate("2022-01-01");
            assertThat(shared.reptStartDate()).isEqualTo("2022-01-01");
        }

        @Test
        @DisplayName("neither constructor accepts null")
        void nullArgumentsRejected() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new TranReportLayouts((Charset) null))
                    .withMessageContaining("US-ASCII");
            assertThatNullPointerException()
                    .isThrownBy(() -> new TranReportLayouts((FixedWidthCodec) null))
                    .withMessageContaining("FixedWidthCodec");
        }
    }

    @Nested
    @DisplayName("State - instances are mutable, nothing static is (gate G53)")
    class State {

        @Test
        @DisplayName("every declared static field is final")
        void everyStaticFieldIsFinal() {
            for (Field field : TranReportLayouts.class.getDeclaredFields()) {
                if (field.isSynthetic() || !Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("static field %s must be final", field.getName())
                        .isTrue();
                assertThat(field.getType().isArray())
                        .as("static field %s must not be an array", field.getName())
                        .isFalse();
            }
        }

        @Test
        @DisplayName("the byte accessors hand out fresh arrays, never the internal storage")
        void byteAccessorsCopyDefensively() {
            byte[] first = layouts.renderTransactionHeader2Bytes();
            byte[] second = layouts.renderTransactionHeader2Bytes();
            assertThat(first).isNotSameAs(second).isEqualTo(second);
            first[0] = (byte) '?';
            assertThat(layouts.renderTransactionHeader2Bytes()).isEqualTo(second);
            assertThat(layouts.renderTransactionHeader2()).startsWith("-");
        }

        @Test
        @DisplayName("two instances share no state")
        void instancesAreIndependent() {
            TranReportLayouts other = new TranReportLayouts(ASCII);
            layouts.moveReptStartDate("2022-01-01");
            layouts.moveReptPageTotal(new BigDecimal("42.00"));
            writeDetailLine();
            assertThat(other.reptStartDate()).isEqualTo(" ".repeat(10));
            assertThat(other.reptPageTotal()).isEqualTo(BLANK_AMOUNT);
            assertThat(other.tranReportTransId()).isEqualTo(" ".repeat(16));
        }

        @Test
        @DisplayName("the published layouts are immutable, so a caller cannot re-shape a record")
        void publishedLayoutsAreImmutable() {
            assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(
                    () -> TranReportLayouts.TRANSACTION_DETAIL_REPORT_LAYOUT.spans()
                            .add(FieldSpan.filler(0, 1)));
            assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(
                    () -> TranReportLayouts.REPORT_PAGE_TOTALS_LAYOUT.storageSpans().clear());
        }
    }

    @Nested
    @DisplayName("The transcription self-check - the check itself is under test")
    class SelfCheck {

        @Test
        @DisplayName("the class initialises, which means every invariant already held")
        void verifyGeometryPasses() {
            assertThatCode(TranReportLayouts::verifyGeometry).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the boolean guard throws on a false invariant and passes on a true one")
        void booleanGuard() {
            assertThatCode(() -> TranReportLayouts.requireGeometry(true, "a held invariant"))
                    .doesNotThrowAnyException();
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> TranReportLayouts.requireGeometry(false, "a broken invariant"))
                    .withMessageContaining("app/cpy/CVTRA07Y.cpy transcription check failed")
                    .withMessageContaining("a broken invariant");
        }

        @Test
        @DisplayName("the int guard reports both the wanted and the found value")
        void integerGuard() {
            assertThatCode(() -> TranReportLayouts.requireGeometry(97, 97, "the amount offset"))
                    .doesNotThrowAnyException();
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> TranReportLayouts.requireGeometry(95, 97, "the amount offset"))
                    .withMessageContaining("the amount offset must be 97 but is 95");
        }

        @Test
        @DisplayName("the char guard quotes both characters")
        void characterGuard() {
            assertThatCode(() -> TranReportLayouts.requireGeometry('-', '-', "the sign"))
                    .doesNotThrowAnyException();
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> TranReportLayouts.requireGeometry('+', '-', "the sign"))
                    .withMessageContaining("the sign must be '-' but is '+'");
        }

        @Test
        @DisplayName("the string guard quotes both literals, spaces included")
        void stringGuard() {
            assertThatCode(() -> TranReportLayouts.requireGeometry("Date Range: ", "Date Range: ",
                    "the caption")).doesNotThrowAnyException();
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> TranReportLayouts.requireGeometry("Date Range:",
                            "Date Range: ", "the caption"))
                    .withMessageContaining("the caption must be \"Date Range: \"")
                    .withMessageContaining("but is \"Date Range:\"");
        }

        @Test
        @DisplayName("isRepetitionOf rejects a wrong length and a wrong character, accepts a match")
        void repetitionPredicate() {
            assertThat(TranReportLayouts.isRepetitionOf("...", '.', 3)).isTrue();
            assertThat(TranReportLayouts.isRepetitionOf("..", '.', 3))
                    .as("a leader shortened by one").isFalse();
            assertThat(TranReportLayouts.isRepetitionOf("....", '.', 3))
                    .as("a leader lengthened by one").isFalse();
            assertThat(TranReportLayouts.isRepetitionOf(".-.", '.', 3))
                    .as("a leader with a stray character").isFalse();
            assertThat(TranReportLayouts.isRepetitionOf("", '.', 0))
                    .as("the degenerate empty case").isTrue();
        }

        @Test
        @DisplayName("countOf counts hits and skips misses")
        void countPredicate() {
            assertThat(TranReportLayouts.countOf("a,b,c", ',')).isEqualTo(2);
            assertThat(TranReportLayouts.countOf("abc", ',')).isZero();
            assertThat(TranReportLayouts.countOf("", ',')).isZero();
            assertThat(TranReportLayouts.countOf(",,,", ',')).isEqualTo(3);
        }

        @Test
        @DisplayName("countLeadingSpaces stops on a non-space and on the end of the string alike")
        void leadingSpacePredicate() {
            assertThat(TranReportLayouts.countLeadingSpaces("        Amount")).isEqualTo(8);
            assertThat(TranReportLayouts.countLeadingSpaces("Amount")).isZero();
            assertThat(TranReportLayouts.countLeadingSpaces("    ")).isEqualTo(4);
            assertThat(TranReportLayouts.countLeadingSpaces("")).isZero();
        }
    }
}
