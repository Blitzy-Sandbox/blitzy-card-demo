package com.vsergeychik.carddemo.transaction.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
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
 *       the record width belongs to {@code TranReportWriter}. Seven, counted from the copybook
 *       itself: {@code CVTRA07Y} declares an {@code 01} level at each of its lines 4, 15, 33, 48, 50,
 *       56 and 62, the last three being the page, account and grand total lines as three separate
 *       declarations rather than one repeated group. Descriptions of this copybook as holding "five"
 *       layouts undercount those three; {@code LayoutWidths} asserts all seven.</li>
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
 *       of them implements {@code Z} suppression at all. Caught by the exact-image mask tables and,
 *       directly, by {@code LocaleIndependence}.</li>
 * </ol>
 *
 * <h2>Provenance — every source consulted, and nothing else</h2>
 *
 * <p>Nine legacy artefacts define everything asserted here. They are <b>read-only</b>: this suite
 * writes nothing to them and, being a unit test, reads nothing from the filesystem or the classpath
 * at run time either. Where real data bytes are wanted they are embedded below as {@code String}
 * literals, each carrying the file and the record it was transcribed from.
 *
 * <ul>
 *   <li>{@code app/cpy/CVTRA07Y.cpy} — the copybook itself: seven layouts, every {@code PICTURE},
 *       every {@code VALUE} literal, both edit masks and the three dot leaders.</li>
 *   <li>{@code app/cbl/CBTRN03C.cbl} — the sole consumer. {@code :362} is the
 *       {@code INITIALIZE TRANSACTION-DETAIL-REPORT} that opens {@code 1120-WRITE-DETAIL} at
 *       {@code :361}; {@code :363-370} are the eight moves; {@code :277-278} move the two dates;
 *       {@code :294}, {@code :307} and {@code :319} move the three totals; {@code :85} declares the
 *       receiving {@code FD-REPTFILE-REC PIC X(133)}. {@code :129-136} declare
 *       {@code WS-LINE-COUNTER PIC 9(09) COMP-3}, {@code WS-PAGE-SIZE PIC 9(03) COMP-3 VALUE 20},
 *       {@code WS-BLANK-LINE PIC X(133) VALUE SPACES} and the three {@code PIC S9(09)V99} totals in
 *       <em>{@code WORKING-STORAGE}</em> — which is why {@code DeliberateAbsences} asserts that none
 *       of them is a member of this class.</li>
 *   <li>{@code app/jcl/TRANREPT.jcl} {@code :78} and {@code app/proc/TRANREPT.prc} {@code :76} —
 *       {@code TRANREPT DCB=(LRECL=133,RECFM=FB,BLKSIZE=0)}. Corroboration only: these layouts
 *       render at their natural widths and the pad to 133 belongs to {@code TranReportWriter}.</li>
 *   <li>{@code app/cpy/CVTRA05Y.cpy} — the sending record: {@code TRAN-ID PIC X(16)},
 *       {@code TRAN-TYPE-CD PIC X(02)}, {@code TRAN-CAT-CD PIC 9(04)},
 *       {@code TRAN-SOURCE PIC X(10)} and {@code TRAN-AMT PIC S9(09)V99}, the scale-2 sender behind
 *       every edited amount.</li>
 *   <li>{@code app/cpy/CVTRA03Y.cpy} — {@code TRAN-TYPE-DESC PIC X(50)}, the over-wide sender of
 *       {@code :366}, whose receiver is only {@code PIC X(15)}.</li>
 *   <li>{@code app/cpy/CVTRA04Y.cpy} — {@code TRAN-CAT-TYPE-DESC PIC X(50)}, the over-wide sender of
 *       {@code :368}, whose receiver is only {@code PIC X(29)}.</li>
 *   <li>{@code app/data/ASCII/trantype.txt} and {@code app/data/ASCII/trancatg.txt} — the real
 *       description bytes embedded as {@link #FIXTURE_TYPE_DESC_PURCHASE} and its siblings.</li>
 * </ul>
 *
 * <p><b>User-specified rules: none.</b> {@code review_rules} returns exactly one line, "No user
 * rules provided", and that absence is not licence to assert less. The constraints this suite is
 * written against are consequently the migration plan's own, and each is named at the assertion that
 * discharges it: {@code R2}/{@code G24} truncation with {@code RoundingMode.DOWN},
 * {@code R3}/{@code G23} scale from the {@code PICTURE}, {@code R4}/{@code G22} no {@code double} or
 * {@code float} anywhere, {@code R5} bytes asserted at absolute offsets, {@code R7}/{@code G50}
 * every mask branch driven, {@code B5} the 86/84/86 leaders preserved rather than tidied,
 * {@code B8}/{@code G52} explicit imports, a named charset and no locale-sensitive formatting,
 * {@code B9}/{@code G53} no static mutable state, {@code B11} every width proved by addition,
 * {@code G19}/{@code G21} declared widths and emitted {@code FILLER}s, {@code G20} natural width and
 * no padding, {@code G33} no fabricated {@code OCCURS} assertion because {@code CVTRA07Y} declares
 * none, and {@code G44} no persistence artefact.
 */
@DisplayName("TranReportLayouts - the seven report line layouts of app/cpy/CVTRA07Y.cpy")
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

    // =================================================================================================
    // Real description bytes, transcribed from the ASCII fixtures. Embedded as literals rather than
    // read from the classpath: this is a unit test, and reading app/data or src/test/resources at run
    // time would make it depend on the filesystem (practice B7) as well as on files it must never
    // write (practice B3). Each carries the fixture and the record it came from, and its measured
    // length, so a reviewer can check it against the fixture without running anything.
    //
    // The two receivers under test are TRAN-REPORT-TYPE-DESC PIC X(15) and TRAN-REPORT-CAT-DESC
    // PIC X(29); both senders are PIC X(50). The measured fixture maxima are 13 and 29 respectively,
    // so REAL data alone can never demonstrate the X(15) receiver truncating and only exactly fills
    // the X(29) one. That is precisely why the two SYNTHETIC over-long values below exist: without
    // them the truncation branch would be untested, and a receiver mis-transcribed as X(50) would
    // pass every assertion drawn from real data.
    // =================================================================================================

    /** {@code app/data/ASCII/trantype.txt} record 1, {@code TRAN-TYPE '01'} — 8 characters. */
    private static final String FIXTURE_TYPE_DESC_PURCHASE = "Purchase";

    /**
     * {@code app/data/ASCII/trantype.txt} record 4, {@code TRAN-TYPE '04'} — 13 characters, and the
     * <b>longest of all seven</b> transaction-type descriptions in the fixture. Being 13, it still
     * fits {@code PIC X(15)} with two spaces to spare, which is the measured fact that makes a
     * synthetic value necessary for the truncation case.
     */
    private static final String FIXTURE_TYPE_DESC_AUTHORIZATION = "Authorization";

    /** {@code app/data/ASCII/trancatg.txt} record 1, key {@code 010001} — 19 characters. */
    private static final String FIXTURE_CAT_DESC_REGULAR_SALES = "Regular Sales Draft";

    /**
     * {@code app/data/ASCII/trancatg.txt} record 13, key {@code 040002} — <b>exactly 29</b>
     * characters, so it fills {@code PIC X(29)} with no padding and no truncation.
     */
    private static final String FIXTURE_CAT_DESC_ONLINE_AUTH = "Online purchase authorization";

    /**
     * {@code app/data/ASCII/trancatg.txt} record 18, key {@code 070001} — the other <b>exactly
     * 29</b>-character description, and jointly the fixture's maximum.
     */
    private static final String FIXTURE_CAT_DESC_CREDIT_ADJUSTMENT = "Sales draft credit adjustment";

    /**
     * A synthetic 50-character sender, over-long for both receivers. Not fixture data, and labelled
     * so: no real description reaches 15 characters, so this is the only way to drive the {@code PIC
     * X(15)} receiver's truncation branch. Its content is deliberately positional — the character at
     * index {@code i} identifies {@code i} — so a truncated image names the exact cut point.
     */
    private static final String SYNTHETIC_DESC_50 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789ABCDEFGHIJKLMN";

    /**
     * A synthetic 30-character sender: one character longer than {@code PIC X(29)}, so it proves the
     * category receiver truncates at 29 rather than 30. The fixture maximum is exactly 29 and
     * therefore cannot distinguish the two.
     */
    private static final String SYNTHETIC_DESC_30 = FIXTURE_CAT_DESC_CREDIT_ADJUSTMENT + "!";

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
        layouts.moveTranReportTypeDesc(padTo(FIXTURE_TYPE_DESC_PURCHASE, 50));
        layouts.moveTranReportCatCd(5001L);
        layouts.moveTranReportCatDesc(padTo(FIXTURE_CAT_DESC_REGULAR_SALES, 50));
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

    /**
     * Asserts a layout's storage spans sum to its declared record length and are contiguous from
     * offset 0 — the arithmetic proof that no item and no {@code FILLER} was dropped from the
     * transcription (gates G19 and G21). Contiguity is checked span by span rather than by summing
     * alone, because two compensating errors can sum correctly while sitting at the wrong offsets.
     *
     * @param layout the layout to prove
     */
    private static void assertSpansSumToRecordLength(RecordLayout layout) {
        int cursor = 0;
        for (FieldSpan span : layout.storageSpans()) {
            assertThat(span.offset())
                    .as("%s must begin where the previous span ended", span.describe())
                    .isEqualTo(cursor);
            cursor += span.length();
        }
        assertThat(cursor)
                .as("the spans of a %d-byte layout must sum to exactly that, FILLER included",
                        layout.recordLength())
                .isEqualTo(layout.recordLength());
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
            // REPORT-NAME-HEADER    38 + 41 + 12 + 10 + 4 + 10          = 115
            // TRANSACTION-HEADER-1  17 + 12 + 19 + 35 + 14 + 1 + 16     = 114
            // REPORT-PAGE-TOTALS    11 + 86 + 15                        = 112
            // REPORT-ACCOUNT-TOTALS 13 + 84 + 15                        = 112
            // REPORT-GRAND-TOTALS   11 + 86 + 15                        = 112
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
        @DisplayName("all sixteen detail items sum to 114, FILLERs included (practice B11)")
        void detailItemWidthsSumToOneHundredFourteen() {
            // Every one of the sixteen declarations of TRANSACTION-DETAIL-REPORT, in copybook order.
            // Eight are FILLER and are written as literals because COBOL FILLER has no referable name:
            //
            //   16 + 1 + 11 + 1 + 2 + 1 + 15 + 1 + 4 + 1 + 29 + 1 + 10 + 4 + 15 + 2 = 114
            //   ^^   ^   ^^   ^   ^   ^   ^^   ^   ^   ^   ^^   ^   ^^   ^   ^^   ^
            //   |    |   |    |   |   |   |    |   |   |   |    |   |    |   |    +- FILLER cols 113-114
            //   |    |   |    |   |   |   |    |   |   |   |    |   |    |   +------ TRAN-REPORT-AMT
            //   |    |   |    |   |   |   |    |   |   |   |    |   |    +---------- FILLER cols 94-97
            //   |    |   |    |   |   |   |    |   |   |   |    |   +--------------- TRAN-REPORT-SOURCE
            //   |    |   |    |   |   |   |    |   |   |   |    +------------------- FILLER col 83
            //   |    |   |    |   |   |   |    |   |   |   +------------------------ TRAN-REPORT-CAT-DESC
            //   |    |   |    |   |   |   |    |   |   +---------------------------- FILLER col 53 = '-'
            //   |    |   |    |   |   |   |    |   +-------------------------------- TRAN-REPORT-CAT-CD
            //   |    |   |    |   |   |   |    +------------------------------------ FILLER col 48
            //   |    |   |    |   |   |   +----------------------------------------- TRAN-REPORT-TYPE-DESC
            //   |    |   |    |   |   +--------------------------------------------- FILLER col 32 = '-'
            //   |    |   |    |   +------------------------------------------------- TRAN-REPORT-TYPE-CD
            //   |    |   |    +----------------------------------------------------- FILLER col 29
            //   |    |   +---------------------------------------------------------- TRAN-REPORT-ACCOUNT-ID
            //   |    +-------------------------------------------------------------- FILLER col 17
            //   +------------------------------------------------------------------- TRAN-REPORT-TRANS-ID
            int spaceFillers = 1 + 1 + 1 + 1 + 4 + 2;
            int separatorFillers = 1 + 1;
            int namedItems = TranReportLayouts.TRAN_REPORT_TRANS_ID_LENGTH
                    + TranReportLayouts.TRAN_REPORT_ACCOUNT_ID_LENGTH
                    + TranReportLayouts.TRAN_REPORT_TYPE_CD_LENGTH
                    + TranReportLayouts.TRAN_REPORT_TYPE_DESC_LENGTH
                    + TranReportLayouts.TRAN_REPORT_CAT_CD_LENGTH
                    + TranReportLayouts.TRAN_REPORT_CAT_DESC_LENGTH
                    + TranReportLayouts.TRAN_REPORT_SOURCE_LENGTH
                    + TranReportLayouts.AMOUNT_MASK_WIDTH;
            assertThat(namedItems).as("the eight named items").isEqualTo(102);
            assertThat(spaceFillers).as("the six FILLERs carrying SPACES").isEqualTo(10);
            assertThat(separatorFillers).as("the two FILLERs carrying '-'").isEqualTo(2);
            assertThat(namedItems + spaceFillers + separatorFillers)
                    .as("102 + 10 + 2 = 114, which is only true if every FILLER is counted")
                    .isEqualTo(TranReportLayouts.TRANSACTION_DETAIL_REPORT_LENGTH);

            // Independently: the descriptors themselves must sum to the same 114.
            assertThat(TranReportLayouts.TRANSACTION_DETAIL_REPORT_LAYOUT.storageSpans().stream()
                    .mapToInt(FieldSpan::length).sum())
                    .isEqualTo(TranReportLayouts.TRANSACTION_DETAIL_REPORT_LENGTH);
        }

        @Test
        @DisplayName("every layout's spans sum to its declared width, every FILLER included")
        void everyLayoutSumsToItsDeclaredWidth() {
            assertSpansSumToRecordLength(TranReportLayouts.REPORT_NAME_HEADER_LAYOUT);
            assertSpansSumToRecordLength(TranReportLayouts.TRANSACTION_DETAIL_REPORT_LAYOUT);
            assertSpansSumToRecordLength(TranReportLayouts.TRANSACTION_HEADER_1_LAYOUT);
            assertSpansSumToRecordLength(TranReportLayouts.TRANSACTION_HEADER_2_LAYOUT);
            assertSpansSumToRecordLength(TranReportLayouts.REPORT_PAGE_TOTALS_LAYOUT);
            assertSpansSumToRecordLength(TranReportLayouts.REPORT_ACCOUNT_TOTALS_LAYOUT);
            assertSpansSumToRecordLength(TranReportLayouts.REPORT_GRAND_TOTALS_LAYOUT);
        }

        @Test
        @DisplayName("the width proof is real: a wrong descriptor set is rejected, not accepted")
        void aWrongDescriptorSetFailsTheWidthProof() {
            // A width check nobody can fail is decoration. These three prove the layouts above are
            // load-bearing by building deliberately wrong descriptor sets and watching each be
            // rejected - which is exactly what would happen if a FILLER were dropped from the real
            // copybook transcription (gate G21) or a width mis-typed (gate G19).

            // 1. A dropped trailing FILLER: the totals line without its 15-byte amount.
            assertThatIllegalArgumentException()
                    .as("11 + 86 = 97 does not reach the declared 112, so the layout must be rejected")
                    .isThrownBy(() -> RecordLayout.of(TranReportLayouts.REPORT_PAGE_TOTALS_LENGTH,
                            FieldSpan.filler(0, TranReportLayouts.PAGE_TOTAL_LABEL_LENGTH),
                            FieldSpan.filler(TranReportLayouts.PAGE_TOTAL_LABEL_LENGTH,
                                    TranReportLayouts.PAGE_TOTAL_LEADER_LENGTH)))
                    .withMessageContaining("97");

            // 2. A "normalised" dot leader: 86 where the copybook says 84 overshoots the width.
            assertThatIllegalArgumentException()
                    .as("normalising the account leader to 86 makes 13 + 86 + 15 = 114, not 112")
                    .isThrownBy(() -> RecordLayout.of(TranReportLayouts.REPORT_ACCOUNT_TOTALS_LENGTH,
                            FieldSpan.filler(0, TranReportLayouts.ACCOUNT_TOTAL_LABEL_LENGTH),
                            FieldSpan.filler(TranReportLayouts.ACCOUNT_TOTAL_LABEL_LENGTH,
                                    TranReportLayouts.PAGE_TOTAL_LEADER_LENGTH),
                            FieldSpan.alphanumeric("REPT-ACCOUNT-TOTAL",
                                    TranReportLayouts.ACCOUNT_TOTAL_LABEL_LENGTH
                                            + TranReportLayouts.PAGE_TOTAL_LEADER_LENGTH,
                                    TranReportLayouts.AMOUNT_MASK_WIDTH)));

            // 3. A mis-transcribed declared width, with the items themselves correct.
            assertThatIllegalArgumentException()
                    .as("declaring 133 for a 112-byte total line must fail, not silently pad")
                    .isThrownBy(() -> RecordLayout.of(133,
                            FieldSpan.filler(0, TranReportLayouts.GRAND_TOTAL_LABEL_LENGTH),
                            FieldSpan.filler(TranReportLayouts.GRAND_TOTAL_LABEL_LENGTH,
                                    TranReportLayouts.GRAND_TOTAL_LEADER_LENGTH),
                            FieldSpan.alphanumeric("REPT-GRAND-TOTAL", TranReportLayouts.AMOUNT_OFFSET,
                                    TranReportLayouts.AMOUNT_MASK_WIDTH)))
                    .withMessageContaining("133");
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
            // Exact image and exact width, then "every byte is a dot" through the copybook class's
            // own hand-written predicate rather than a regular expression. A regex would assert the
            // same thing less legibly and would be the one piece of pattern matching in a suite whose
            // whole discipline is byte-at-an-offset comparison (rule R5, practice B11); using
            // isRepetitionOf additionally keeps that predicate under test where it is actually relied
            // on, which is what it was made package-private for.
            assertThat(TranReportLayouts.PAGE_TOTAL_LEADER_IMAGE).isEqualTo(LEADER_86).hasSize(86);
            assertThat(TranReportLayouts.isRepetitionOf(TranReportLayouts.PAGE_TOTAL_LEADER_IMAGE,
                    TranReportLayouts.TOTAL_LEADER_CHARACTER, 86)).isTrue();
            assertThat(TranReportLayouts.ACCOUNT_TOTAL_LEADER_IMAGE).isEqualTo(LEADER_84).hasSize(84);
            assertThat(TranReportLayouts.isRepetitionOf(TranReportLayouts.ACCOUNT_TOTAL_LEADER_IMAGE,
                    TranReportLayouts.TOTAL_LEADER_CHARACTER, 84)).isTrue();
            assertThat(TranReportLayouts.GRAND_TOTAL_LEADER_IMAGE).isEqualTo(LEADER_86).hasSize(86);
            assertThat(TranReportLayouts.isRepetitionOf(TranReportLayouts.GRAND_TOTAL_LEADER_IMAGE,
                    TranReportLayouts.TOTAL_LEADER_CHARACTER, 86)).isTrue();
            assertThat(TranReportLayouts.TOTAL_LEADER_CHARACTER).isEqualTo('.');

            // And the 84 is not interchangeable with the 86: asserting the wrong width must fail, or
            // the three assertions above would pass a normalised leader too (practice B5).
            assertThat(TranReportLayouts.isRepetitionOf(TranReportLayouts.ACCOUNT_TOTAL_LEADER_IMAGE,
                    TranReportLayouts.TOTAL_LEADER_CHARACTER, 86))
                    .as("the account leader is 84 dots, so it is not 86 dots")
                    .isFalse();
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
            String image = layouts.renderTransactionHeader2();
            assertThat(image).hasSize(133).isEqualTo("-".repeat(133));
            // Byte by byte at its absolute offset rather than by pattern (rule R5): a regex would
            // report "did not match" where this names the column that is wrong.
            for (int offset = 0; offset < TranReportLayouts.TRANSACTION_HEADER_2_LENGTH; offset++) {
                assertThat(image.charAt(offset))
                        .as("1-based column %d of the rule line", offset + 1)
                        .isEqualTo(TranReportLayouts.TRANSACTION_HEADER_2_RULE_CHARACTER);
            }
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
            layouts.moveTranReportTypeDesc(SYNTHETIC_DESC_50);
            assertThat(layouts.tranReportTypeDesc()).isEqualTo("ABCDEFGHIJKLMNO").hasSize(15);
            layouts.moveTranReportTypeDesc("Debit");
            assertThat(layouts.tranReportTypeDesc()).isEqualTo("Debit          ").hasSize(15);
        }

        @Test
        @DisplayName("TRAN-CAT-TYPE-DESC X(50) is right-truncated to the leftmost 29 characters")
        void categoryDescriptionRightTruncatedTo29() {
            layouts.moveTranReportCatDesc(SYNTHETIC_DESC_50);
            assertThat(layouts.tranReportCatDesc()).isEqualTo("ABCDEFGHIJKLMNOPQRSTUVWXYZ012")
                    .hasSize(29);
            layouts.moveTranReportCatDesc("Cash");
            assertThat(layouts.tranReportCatDesc()).isEqualTo("Cash" + " ".repeat(25)).hasSize(29);
        }

        @Test
        @DisplayName("real trantype.txt descriptions: the longest, 13, still fits X(15) padded")
        void realTypeDescriptionsFromTheFixture() {
            // app/data/ASCII/trantype.txt record 1: 'Purchase', 8 characters into PIC X(15).
            layouts.moveTranReportTypeDesc(padTo(FIXTURE_TYPE_DESC_PURCHASE, 50));
            assertThat(layouts.tranReportTypeDesc())
                    .isEqualTo(FIXTURE_TYPE_DESC_PURCHASE + " ".repeat(7))
                    .hasSize(TranReportLayouts.TRAN_REPORT_TYPE_DESC_LENGTH);

            // Record 4: 'Authorization', 13 characters - the longest of the seven, and STILL two
            // short of the receiver. No fixture row can therefore exercise truncation, which is why
            // typeDescriptionRightTruncatedTo15 has to use a synthetic sender.
            assertThat(FIXTURE_TYPE_DESC_AUTHORIZATION.length())
                    .as("the fixture maximum, measured")
                    .isEqualTo(13)
                    .isLessThan(TranReportLayouts.TRAN_REPORT_TYPE_DESC_LENGTH);
            layouts.moveTranReportTypeDesc(padTo(FIXTURE_TYPE_DESC_AUTHORIZATION, 50));
            assertThat(layouts.tranReportTypeDesc())
                    .isEqualTo(FIXTURE_TYPE_DESC_AUTHORIZATION + "  ")
                    .hasSize(TranReportLayouts.TRAN_REPORT_TYPE_DESC_LENGTH);
        }

        @Test
        @DisplayName("real trancatg.txt descriptions: the two 29-character ones fill X(29) exactly")
        void realCategoryDescriptionsFromTheFixture() {
            // app/data/ASCII/trancatg.txt record 1: 19 characters, so 10 spaces of padding.
            layouts.moveTranReportCatDesc(padTo(FIXTURE_CAT_DESC_REGULAR_SALES, 50));
            assertThat(layouts.tranReportCatDesc())
                    .isEqualTo(FIXTURE_CAT_DESC_REGULAR_SALES + " ".repeat(10))
                    .hasSize(TranReportLayouts.TRAN_REPORT_CAT_DESC_LENGTH);

            // Records 13 and 18: exactly 29 characters each, the fixture's joint maximum. They fill
            // the receiver with neither a pad byte nor a discarded one - the boundary case, where an
            // off-by-one in either direction is visible.
            assertThat(FIXTURE_CAT_DESC_ONLINE_AUTH.length())
                    .isEqualTo(TranReportLayouts.TRAN_REPORT_CAT_DESC_LENGTH);
            assertThat(FIXTURE_CAT_DESC_CREDIT_ADJUSTMENT.length())
                    .isEqualTo(TranReportLayouts.TRAN_REPORT_CAT_DESC_LENGTH);
            layouts.moveTranReportCatDesc(padTo(FIXTURE_CAT_DESC_ONLINE_AUTH, 50));
            assertThat(layouts.tranReportCatDesc()).isEqualTo(FIXTURE_CAT_DESC_ONLINE_AUTH)
                    .hasSize(29)
                    .doesNotEndWith(" ");
            layouts.moveTranReportCatDesc(padTo(FIXTURE_CAT_DESC_CREDIT_ADJUSTMENT, 50));
            assertThat(layouts.tranReportCatDesc()).isEqualTo(FIXTURE_CAT_DESC_CREDIT_ADJUSTMENT)
                    .hasSize(29)
                    .doesNotEndWith(" ");

            // One character more, and exactly one character is discarded. The fixture cannot show
            // this, so the sender is synthetic and labelled as such.
            layouts.moveTranReportCatDesc(padTo(SYNTHETIC_DESC_30, 50));
            assertThat(layouts.tranReportCatDesc())
                    .as("the 30th character is dropped, not wrapped into the next field")
                    .isEqualTo(FIXTURE_CAT_DESC_CREDIT_ADJUSTMENT)
                    .doesNotContain("!");
        }

        @Test
        @DisplayName("an over-long description does not bleed past its span: columns 32, 48 and 53")
        void longDescriptionsDoNotBleedPastTheirSpans() {
            // The off-by-one catcher. Both receivers are bounded by FILLERs - the '-' at column 32 and
            // the space at column 48 bracket TRAN-REPORT-TYPE-DESC, and the '-' at column 53 opens the
            // category description - so a receiver one byte too wide, or a MOVE that writes past its
            // span, destroys a separator the COBOL never rewrites. Nothing else in the suite would
            // notice: the descriptions themselves would still read back correctly.
            writeDetailLine();
            layouts.moveTranReportTypeDesc(SYNTHETIC_DESC_50);
            layouts.moveTranReportCatDesc(SYNTHETIC_DESC_50);
            String image = layouts.renderTransactionDetailReport();

            assertThat(image).hasSize(TranReportLayouts.TRANSACTION_DETAIL_REPORT_LENGTH);
            assertThat(image.charAt(31)).as("the '-' FILLER on 1-based column 32").isEqualTo('-');
            assertThat(image.charAt(47)).as("the space FILLER on 1-based column 48").isEqualTo(' ');
            assertThat(image.charAt(52)).as("the '-' FILLER on 1-based column 53").isEqualTo('-');
            assertThat(image.charAt(82)).as("the space FILLER on 1-based column 83").isEqualTo(' ');

            // The neighbours are intact too, so the truncated text sits wholly inside its own span.
            assertThat(image.substring(29, 31)).as("TRAN-REPORT-TYPE-CD, columns 30-31").isEqualTo("01");
            assertThat(image.substring(32, 47)).as("TRAN-REPORT-TYPE-DESC, columns 33-47")
                    .isEqualTo("ABCDEFGHIJKLMNO");
            assertThat(image.substring(48, 52)).as("TRAN-REPORT-CAT-CD, columns 49-52")
                    .isEqualTo("5001");
            assertThat(image.substring(53, 82)).as("TRAN-REPORT-CAT-DESC, columns 54-82")
                    .isEqualTo("ABCDEFGHIJKLMNOPQRSTUVWXYZ012");
            assertThat(image.substring(83, 93)).as("TRAN-REPORT-SOURCE, columns 84-93")
                    .isEqualTo("POS TERM  ");
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

        @Test
        @DisplayName("on TRANSACTION-HEADER-1 the rule has no receiver at all, so it is a no-op")
        void headerOneHasNoReceivingItem() {
            // COBOL's INITIALIZE skips FILLER, and every one of TRANSACTION-HEADER-1's seven items is
            // a FILLER, so initialising it would change nothing. CBTRN03C never initialises it either
            // - :333 simply moves the whole constant line out. The class correspondingly exposes no
            // mutator for it, so the strongest available statement of "no-op" is that the image is
            // byte-identical after every mutator this type has has been exercised.
            assertThat(TranReportLayouts.TRANSACTION_HEADER_1_LAYOUT.storageSpans())
                    .as("no non-FILLER item exists to receive SPACE or ZERO")
                    .allMatch(span -> span.kind().filler());
            String before = layouts.renderTransactionHeader1();
            writeDetailLine();
            layouts.initializeTransactionDetailReport();
            layouts.moveReptStartDate("2022-01-01");
            layouts.moveReptEndDate("2022-07-06");
            layouts.moveReptPageTotal(new BigDecimal("1.00"));
            layouts.moveReptAccountTotal(new BigDecimal("-2.00"));
            layouts.moveReptGrandTotal(new BigDecimal("3.00"));
            assertThat(layouts.renderTransactionHeader1()).isEqualTo(before).hasSize(114);
        }

        @Test
        @DisplayName("REPORT-NAME-HEADER's asymmetry: named items blank, the ' to ' FILLER survives")
        void nameHeaderNamedItemsBlankButFillerSurvives() {
            // The rule applied to REPORT-NAME-HEADER would blank its five NAMED alphanumeric items -
            // destroying 'DALYREPT', 'Daily Transaction Report' and 'Date Range: ' - while leaving the
            // ' to ' FILLER standing. That asymmetry is a genuine and easily-missed consequence of the
            // FILLER exclusion, and it is asserted here for the two named items the type can actually
            // blank: CBTRN03C does NOT initialise this group, it moves the dates in at :277-278, so
            // the three captions have no mutator by design and the rule has no way to reach them.
            layouts.moveReptStartDate("2022-01-01");
            layouts.moveReptEndDate("2022-07-06");
            layouts.moveReptStartDate("");
            layouts.moveReptEndDate("");
            assertThat(layouts.reptStartDate()).as("a named PIC X item blanks to spaces")
                    .isEqualTo(" ".repeat(10));
            assertThat(layouts.reptEndDate()).isEqualTo(" ".repeat(10));
            String image = layouts.renderReportNameHeader();
            assertThat(image.substring(101, 105))
                    .as("the ' to ' FILLER on columns 102-105 is untouched, spaces and all")
                    .isEqualTo(" to ");
            assertThat(image.substring(79, 91))
                    .as("and the captions, having no mutator, are still there")
                    .isEqualTo("Date Range: ");
            assertThat(image).startsWith(SHORT_NAME_IMAGE).hasSize(115);
        }

        @Test
        @DisplayName("TRANSACTION-HEADER-2 is never initialised: all 133 hyphens stand")
        void headerTwoIsNeverInitialised() {
            // TRANSACTION-HEADER-2 is a NAMED elementary alphanumeric item, so the INITIALIZE rule
            // would blank all 133 hyphens and erase the report's rule line. CBTRN03C never initialises
            // it - :300, :312 and :337 only move it out - and this type exposes no mutator that could,
            // which is what keeps the separator intact.
            String before = layouts.renderTransactionHeader2();
            writeDetailLine();
            layouts.initializeTransactionDetailReport();
            layouts.moveReptGrandTotal(new BigDecimal("-0.01"));
            assertThat(layouts.renderTransactionHeader2())
                    .isEqualTo(before)
                    .isEqualTo(TranReportLayouts.TRANSACTION_HEADER_2_IMAGE)
                    .hasSize(133);
            assertThat(TranReportLayouts.isRepetitionOf(layouts.renderTransactionHeader2(), '-', 133))
                    .isTrue();
        }

        @Test
        @DisplayName("on a total line only the amount blanks: label and dot leader survive")
        void totalLinesKeepTheirLabelAndLeader() {
            // The same rule on a total line: the label and the ALL '.' leader are FILLER and survive,
            // and only the numeric-edited amount receives ZERO - which the all-Z mask renders as 15
            // spaces. CBTRN03C reaches this state by MOVE 0 TO WS-PAGE-TOTAL at :297 followed by the
            // next :294 move, rather than by an INITIALIZE, and the observable bytes are the same.
            layouts.moveReptPageTotal(new BigDecimal("1234.56"));
            layouts.moveReptAccountTotal(new BigDecimal("-1234.56"));
            layouts.moveReptGrandTotal(new BigDecimal("999999999.99"));
            layouts.moveReptPageTotal(new BigDecimal("0.00"));
            layouts.moveReptAccountTotal(new BigDecimal("0.00"));
            layouts.moveReptGrandTotal(new BigDecimal("0.00"));
            assertThat(layouts.renderReportPageTotals())
                    .isEqualTo("Page Total " + LEADER_86 + BLANK_AMOUNT).hasSize(112);
            assertThat(layouts.renderReportAccountTotals())
                    .isEqualTo("Account Total" + LEADER_84 + BLANK_AMOUNT).hasSize(112);
            assertThat(layouts.renderReportGrandTotals())
                    .isEqualTo("Grand Total" + LEADER_86 + BLANK_AMOUNT).hasSize(112);
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

        @Test
        @DisplayName("the sender is PIC S9(09)V99, so it reaches the mask at scale 2 (R3 / G23)")
        void theSenderReachesTheMaskAtScaleTwo() {
            // R3: the scale comes from the PICTURE, not from the caller. Every value the mask edits
            // originates in a PIC S9(09)V99 field - TRAN-AMT of app/cpy/CVTRA05Y.cpy for the detail
            // line, and WS-PAGE-TOTAL / WS-ACCOUNT-TOTAL / WS-GRAND-TOTAL of app/cbl/CBTRN03C.cbl:
            // 134-136 for the totals - so nine integer digits and exactly two decimals.
            assertThat(TranReportLayouts.AMOUNT_INTEGER_DIGITS).as("the 9 of S9(09)").isEqualTo(9);
            assertThat(TranReportLayouts.AMOUNT_FRACTION_DIGITS).as("the 2 of V99").isEqualTo(2);

            // G24 / R2: the store truncates. Pinning the seam's mode here means a future switch to
            // HALF_UP or HALF_EVEN fails this test as well as the truncation table below, and the
            // failure names the constant that changed.
            assertThat(CobolDecimal.COBOL_ROUNDING)
                    .as("ROUNDED appears zero times in all 28 programs, so the mode is DOWN")
                    .isEqualTo(RoundingMode.DOWN);
            assertThat(CobolDecimal.MONETARY_SCALE)
                    .isEqualTo(TranReportLayouts.AMOUNT_FRACTION_DIGITS);

            // The value is at scale 2 BEFORE it is edited, whatever scale the caller held it at, and
            // editing the already-stored value gives the identical image. That equality is the proof
            // that the mask applies the store itself rather than trusting its caller to have done so.
            BigDecimal overPrecise = new BigDecimal("1234.567");
            BigDecimal stored = CobolDecimal.storeAtPicture(overPrecise,
                    TranReportLayouts.AMOUNT_INTEGER_DIGITS,
                    TranReportLayouts.AMOUNT_FRACTION_DIGITS);
            assertThat(stored.scale()).as("scale from the PICTURE, not from the sender").isEqualTo(2);
            assertThat(stored).isEqualByComparingTo(new BigDecimal("1234.56"));
            assertThat(TranReportLayouts.editDetailAmount(overPrecise))
                    .isEqualTo(TranReportLayouts.editDetailAmount(stored))
                    .isEqualTo(spaced("_______1,234.56"));
            assertThat(TranReportLayouts.editTotalAmount(overPrecise))
                    .isEqualTo(TranReportLayouts.editTotalAmount(stored))
                    .isEqualTo(spaced("+______1,234.56"));
        }
    }

    /**
     * The masks are rendered by explicit character placement, so their output cannot vary by machine.
     *
     * <p>This is practice {@code B8} made executable. A {@code String.format("%,.2f", …)}, a {@code
     * DecimalFormat} or a {@code NumberFormat} built without an explicit {@link Locale} takes its
     * grouping separator, its decimal separator, its digits and its negative form from the default
     * locale: under {@link Locale#GERMANY} the same value renders {@code 1.234,56} rather than {@code
     * 1,234.56}, and under a locale whose numbering system is not Latin the digits themselves change.
     * Any of those would put different bytes in the {@code TRANREPT} dataset depending on where the
     * job ran, which is a parity defect that no amount of arithmetic testing would reveal.
     *
     * <p>Each test below changes the default locale, asserts, and restores the previous default in a
     * {@code finally} block <em>within the same test</em> — never in a shared fixture, because a
     * static or {@code @BeforeAll} holder of the previous default would be exactly the mutable static
     * state practice {@code B9} forbids, and a leaked default would silently re-target every test
     * that ran afterwards.
     */
    @Nested
    @DisplayName("Locale independence - explicit character placement, never a formatter (B8)")
    class LocaleIndependence {

        @Test
        @DisplayName("under Locale.GERMANY the separators do not swap: still 1,234.56 not 1.234,56")
        void germanDefaultLocaleDoesNotSwapSeparators() {
            Locale previous = Locale.getDefault();
            try {
                Locale.setDefault(Locale.GERMANY);
                assertThat(TranReportLayouts.editDetailAmount(new BigDecimal("1234.56")))
                        .as("a locale-sensitive formatter would give 1.234,56 here")
                        .isEqualTo(spaced("_______1,234.56"));
                assertThat(TranReportLayouts.editTotalAmount(new BigDecimal("123456789.12")))
                        .isEqualTo("+123,456,789.12");
                assertThat(TranReportLayouts.editTotalAmount(new BigDecimal("-1000000.00")))
                        .as("a European default would render -1.000.000,00")
                        .isEqualTo(spaced("-__1,000,000.00"));
                assertThat(TranReportLayouts.editTotalAmount(new BigDecimal("0.00")))
                        .as("the all-Z zero rule is not a formatting choice either")
                        .isEqualTo(BLANK_AMOUNT);
            } finally {
                Locale.setDefault(previous);
            }
        }

        @Test
        @DisplayName("under a Turkish default the captions keep their ASCII 'I' and 'i'")
        void turkishDefaultLocaleDoesNotChangeCaseFolding() {
            // Turkish is the classic locale trap: 'I' lower-cases to a dotless 'ı' and 'i'
            // upper-cases to 'İ'. Any case folding applied to a caption - a "tidy" toUpperCase on a
            // report heading, say - would corrupt 'Transaction ID' and 'Daily Transaction Report'
            // here and nowhere else. The literals are emitted verbatim, so nothing moves.
            Locale previous = Locale.getDefault();
            try {
                Locale.setDefault(Locale.forLanguageTag("tr-TR"));
                assertThat(layouts.renderTransactionHeader1())
                        .startsWith("Transaction ID   ")
                        .contains("Tran Category")
                        .hasSize(TranReportLayouts.TRANSACTION_HEADER_1_LENGTH);
                assertThat(layouts.reptLongName()).startsWith("Daily Transaction Report");
                assertThat(layouts.reptDateHeader()).isEqualTo("Date Range: ");
            } finally {
                Locale.setDefault(previous);
            }
        }

        @Test
        @DisplayName("under a non-Latin numbering system the digits stay ASCII 0-9")
        void nonLatinNumberingSystemDoesNotChangeTheDigits() {
            // A locale requesting the Arabic-Indic numbering system makes NumberFormat emit ٠١٢٣…
            // instead of 0123…. The mask writes the sending value's own digit characters, so the
            // report stays ASCII - which it must, since TRANREPT is read back byte for byte.
            Locale previous = Locale.getDefault();
            try {
                Locale.setDefault(Locale.forLanguageTag("ar-EG-u-nu-arab"));
                String image = TranReportLayouts.editDetailAmount(new BigDecimal("999999999.99"));
                assertThat(image).isEqualTo(spaced("_999,999,999.99")).hasSize(15);
                for (int index = 0; index < image.length(); index++) {
                    char rendered = image.charAt(index);
                    assertThat(rendered == ' ' || rendered == ',' || rendered == '.'
                            || (rendered >= '0' && rendered <= '9'))
                            .as("character at 0-based index %d of the edited image is ASCII", index)
                            .isTrue();
                }
                layouts.moveTranReportCatCd(5001L);
                assertThat(layouts.tranReportCatCd()).isEqualTo("5001");
                layouts.moveTranReportAccountId(10000000001L);
                assertThat(layouts.tranReportAccountId()).isEqualTo("10000000001");
            } finally {
                Locale.setDefault(previous);
            }
        }

        @Test
        @DisplayName("the same value gives byte-identical images under three different defaults")
        void imagesAreIdenticalAcrossDefaults() {
            Locale previous = Locale.getDefault();
            try {
                BigDecimal amount = new BigDecimal("-1234567.89");
                Locale.setDefault(Locale.US);
                String underUs = TranReportLayouts.editDetailAmount(amount);
                Locale.setDefault(Locale.GERMANY);
                String underGermany = TranReportLayouts.editDetailAmount(amount);
                Locale.setDefault(Locale.forLanguageTag("hi-IN-u-nu-deva"));
                String underDevanagari = TranReportLayouts.editDetailAmount(amount);
                assertThat(underGermany).isEqualTo(underUs);
                assertThat(underDevanagari).isEqualTo(underUs);
                assertThat(underUs).isEqualTo(spaced("-__1,234,567.89"));
            } finally {
                Locale.setDefault(previous);
            }
        }

        @Test
        @DisplayName("the default locale is restored, so no later test inherits a changed default")
        void theDefaultLocaleIsRestored() {
            Locale before = Locale.getDefault();
            Locale previous = Locale.getDefault();
            try {
                Locale.setDefault(Locale.GERMANY);
                assertThat(Locale.getDefault()).isEqualTo(Locale.GERMANY);
            } finally {
                Locale.setDefault(previous);
            }
            assertThat(Locale.getDefault())
                    .as("a leaked default would silently re-target every test after this one")
                    .isEqualTo(before);
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

    /**
     * What this type must <em>not</em> carry. An absence is as much a part of the contract as a
     * presence, and it is the part no positive assertion can defend: a page-size constant added here
     * "for convenience" would compile, pass every other test in this suite, and quietly split
     * pagination state across two types.
     *
     * <p>Three groups of absentees, each for its own reason:
     *
     * <ul>
     *   <li><b>{@code CBTRN03C}'s {@code WORKING-STORAGE}.</b> {@code WS-LINE-COUNTER PIC 9(09)
     *       COMP-3}, {@code WS-PAGE-SIZE PIC 9(03) COMP-3 VALUE 20} and {@code WS-BLANK-LINE PIC
     *       X(133) VALUE SPACES} are declared at {@code app/cbl/CBTRN03C.cbl:129-133} — in the
     *       <em>program</em>, not in {@code CVTRA07Y}. They are the report job's own state and belong
     *       to {@code TransactionReportJob}; {@code WS-BLANK-LINE} is additionally a 133-byte item,
     *       and admitting it here would smuggle the record width into a class whose whole discipline
     *       is to render at natural width.</li>
     *   <li><b>Persistence artefacts (gate G44).</b> The migration adds no DDL, no entity mapping and
     *       no version column, so no {@code jakarta.persistence} or {@code javax.persistence}
     *       annotation may appear on the type, its fields, its methods or its constructors.</li>
     *   <li><b>An {@code OCCURS} index accessor (gate G33).</b> {@code CVTRA07Y} declares no {@code
     *       OCCURS} at all, so the 1-based-to-0-based conversion has no subject here and this suite
     *       deliberately asserts nothing about it. Fabricating such a test would assert a property of
     *       a copybook this class does not model.</li>
     * </ul>
     */
    @Nested
    @DisplayName("Deliberate absences - what CVTRA07Y does not declare, this class must not carry")
    class DeliberateAbsences {

        @Test
        @DisplayName("no page-size, line-counter or blank-line member: that is CBTRN03C's state")
        void noWorkingStorageOfTheReportJob() {
            for (Field field : TranReportLayouts.class.getDeclaredFields()) {
                assertThat(normalise(field.getName()))
                        .as("field %s names CBTRN03C WORKING-STORAGE that belongs to "
                                + "TransactionReportJob, not to this copybook", field.getName())
                        .doesNotContain("PAGESIZE")
                        .doesNotContain("LINECOUNTER")
                        .doesNotContain("LINECOUNT")
                        .doesNotContain("BLANKLINE");
            }
            for (Method method : TranReportLayouts.class.getDeclaredMethods()) {
                assertThat(normalise(method.getName()))
                        .as("method %s exposes report-job state", method.getName())
                        .doesNotContain("PAGESIZE")
                        .doesNotContain("LINECOUNTER")
                        .doesNotContain("LINECOUNT")
                        .doesNotContain("BLANKLINE");
            }
        }

        @Test
        @DisplayName("no constant holds 20, the page size, or 133 spaces, the blank line")
        void noPageSizeValueAndNoBlankLineValue() throws IllegalAccessException {
            String blankLine = " ".repeat(TranReportLayouts.TRANSACTION_HEADER_2_LENGTH);
            for (Field field : TranReportLayouts.class.getDeclaredFields()) {
                if (field.isSynthetic() || !Modifier.isStatic(field.getModifiers())
                        || !Modifier.isPublic(field.getModifiers())) {
                    continue;
                }
                Object value = field.get(null);
                if (value instanceof String text) {
                    assertThat(text)
                            .as("public constant %s must not be the 133-space WS-BLANK-LINE",
                                    field.getName())
                            .isNotEqualTo(blankLine);
                }
                if (value instanceof Integer number) {
                    // 20 is WS-PAGE-SIZE's VALUE. No width, offset or column of this copybook is 20 -
                    // the widths are 115/114/114/133/112 and the item widths are 1, 2, 4, 10, 11, 12,
                    // 13, 14, 15, 16, 17, 19, 29, 35, 38, 41, 84, 86 and 133 - so a 20 appearing here
                    // could only be the page size arriving by the back door.
                    assertThat(number)
                            .as("public constant %s is 20, which is WS-PAGE-SIZE and nothing in "
                                    + "CVTRA07Y", field.getName())
                            .isNotEqualTo(20);
                }
            }
        }

        @Test
        @DisplayName("no jakarta.persistence or javax.persistence annotation anywhere (gate G44)")
        void noPersistenceAnnotations() {
            assertNoPersistenceAnnotation(TranReportLayouts.class.getAnnotations(),
                    "the type TranReportLayouts");
            for (Field field : TranReportLayouts.class.getDeclaredFields()) {
                assertNoPersistenceAnnotation(field.getAnnotations(), "field " + field.getName());
            }
            for (Method method : TranReportLayouts.class.getDeclaredMethods()) {
                assertNoPersistenceAnnotation(method.getAnnotations(), "method " + method.getName());
                assertNoParameterPersistenceAnnotation(method);
            }
            for (Constructor<?> constructor : TranReportLayouts.class.getDeclaredConstructors()) {
                assertNoPersistenceAnnotation(constructor.getAnnotations(), "a constructor");
                assertNoParameterPersistenceAnnotation(constructor);
            }
        }

        @Test
        @DisplayName("no Spring stereotype either: this is a copybook record, not a bean")
        void noSpringStereotype() {
            for (Annotation annotation : TranReportLayouts.class.getAnnotations()) {
                assertThat(annotation.annotationType().getName())
                        .as("TranReportLayouts is constructed with an explicit charset, never "
                                + "component-scanned")
                        .doesNotStartWith("org.springframework");
            }
        }

        /**
         * Upper-cases and strips the separators a Java identifier might use, so that
         * {@code pageSize}, {@code PAGE_SIZE} and {@code page_size} all normalise to the same token
         * and one spelling cannot slip past the check.
         *
         * @param identifier the declared field or method name
         * @return the identifier upper-cased with {@code _} and {@code $} removed
         */
        private String normalise(String identifier) {
            return identifier.toUpperCase(Locale.ROOT).replace("_", "").replace("$", "");
        }

        /**
         * Asserts none of the given annotations comes from a persistence API.
         *
         * @param annotations the annotations present on some element
         * @param subject     what is being checked, for the failure message
         */
        private void assertNoPersistenceAnnotation(Annotation[] annotations, String subject) {
            for (Annotation annotation : annotations) {
                assertThat(annotation.annotationType().getName())
                        .as("%s carries %s; the migration adds no DDL, no entity mapping and no "
                                + "version column (gate G44)", subject, annotation.annotationType())
                        .doesNotStartWith("jakarta.persistence")
                        .doesNotStartWith("javax.persistence");
            }
        }

        /**
         * Asserts no parameter of a method or constructor carries a persistence annotation.
         *
         * @param executable the method or constructor to inspect
         */
        private void assertNoParameterPersistenceAnnotation(Executable executable) {
            for (Annotation[] perParameter : executable.getParameterAnnotations()) {
                assertNoPersistenceAnnotation(perParameter,
                        "a parameter of " + executable.getName());
            }
        }
    }
}
