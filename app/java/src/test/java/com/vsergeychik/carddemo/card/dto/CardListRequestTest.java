package com.vsergeychik.carddemo.card.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.card.dto.CardListRequest.CardKey;
import com.vsergeychik.carddemo.card.dto.CardListRequest.FieldMetadata;
import com.vsergeychik.carddemo.card.dto.CardListRequest.FirstListRow;
import com.vsergeychik.carddemo.card.dto.CardListRequest.ListRow;
import com.vsergeychik.carddemo.card.dto.CardListRequest.PageCursor;
import com.vsergeychik.carddemo.card.dto.CardListRequest.ScreenRow;
import com.vsergeychik.carddemo.card.dto.CardListRequest.ScreenRowTable;
import com.vsergeychik.carddemo.card.dto.CardListRequest.SelectionErrorFlags;
import com.vsergeychik.carddemo.card.dto.CardListRequest.SelectionFlags;
import com.vsergeychik.carddemo.card.dto.CardListRequest.StopperListRow;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.NavigationContext;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Parity tests for {@link CardListRequest}, the projection of {@code app/cpy-bms/COCRDLI.CPY} and
 * {@code app/bms/COCRDLI.bms} onto the inbound payload of {@code GET /api/cards}.
 *
 * <p>Every expected value here is transcribed from the COBOL sources rather than read back from the
 * class under test, so the sources stay the authority and a drifted width or a moved field fails here.
 * The suite is organised around the properties that determine the implementation:
 *
 * <ol>
 *   <li>The screen carries exactly <strong>45</strong> payload members, and the count only reconciles
 *       with the row-1 asymmetry intact - {@code 9 + 4 + 6 x 5 + 2}.</li>
 *   <li>Row 1 has <strong>four</strong> members and no {@code CRDSTP1}; rows 2 through 7 have
 *       <strong>five</strong> with {@code CRDSTPn} second. The invariant is enforced, not assumed.</li>
 *   <li>The group image is <strong>797</strong> bytes, the cursor <strong>58</strong>, the row table
 *       <strong>196</strong> and the transported communication area <strong>254</strong>.</li>
 *   <li>All three seven-element tables are 1-based, and index 1 and index 7 are both addressable and
 *       both correct.</li>
 *   <li>{@code SPACES}, {@code LOW-VALUES} and {@code null} are three different things.</li>
 *   <li>Page size 7 is a compile-time constant with no configuration path.</li>
 *   <li>Every one of the 45 name-labelled {@code DFHMDF} definitions is reachable at its declared
 *       {@code LENGTH}, and the whole 797-byte image reconciles offset by offset.</li>
 * </ol>
 *
 * <h2>Two source divergences recorded rather than corrected</h2>
 *
 * <p><strong>1. This mapset declares no {@code CTRL}, no {@code ALARM} and no {@code EXTATT}.</strong>
 * The Agent Action Plan &sect;0.6.2 states that all seventeen mapsets declare
 * {@code DFHMSD CTRL=(ALARM,FREEKB) EXTATT=YES LANG=COBOL MODE=INOUT STORAGE=AUTO TIOAPFX=YES}. For
 * {@code COCRDLI} that is not what the source says. {@code app/bms/COCRDLI.bms:20-28} is:
 *
 * <pre>
 *   COCRDLI DFHMSD LANG=COBOL, MODE=INOUT, STORAGE=AUTO, TIOAPFX=YES, TYPE=&amp;&amp;SYSPARM
 *   CCRDLIA DFHMDI CTRL=(FREEKB), DSATTS=(COLOR,HILIGHT,PS,VALIDN),
 *                  MAPATTS=(COLOR,HILIGHT,PS,VALIDN), SIZE=(24,80)
 * </pre>
 *
 * <p>The {@code DFHMSD} carries <em>none</em> of the three; the {@code DFHMDI} carries
 * {@code CTRL=(FREEKB)} - so {@code FREEKB} is present but at map level, {@code ALARM} is absent
 * altogether, and extended attributes arrive through {@code DSATTS}/{@code MAPATTS} rather than
 * {@code EXTATT=YES}. Per AAP &sect;0.10.2 B4 the divergence is documented here and the <em>source</em>
 * is what the tests assert; the plan text is not quietly adopted and the mapset is not quietly
 * "corrected". Only {@code SIZE=(24,80)} is common ground, and that is the one the geometry tests use.
 *
 * <p><strong>2. The COBOL communication area is 254 bytes, not 58.</strong>
 * {@code app/cbl/COCRDLIC.cbl:229} opens {@code 01 WS-THIS-PROGCOMMAREA.} and the paging cursor items
 * beneath it are all level <strong>10</strong>. Then {@code :252} declares
 * {@code 05 WS-SCREEN-DATA.} - level <strong>05</strong>, which is <em>numerically lower</em> than 10,
 * so it does not nest inside the cursor group: it closes that group and becomes a <em>sibling</em>
 * within the same {@code 01}. The transported area is therefore
 * {@code 58 + 196 = 254} bytes, which is why {@code :317} and {@code :330} pass
 * {@code LENGTH OF WS-THIS-PROGCOMMAREA} whole. The Java type keeps the two halves as separate nested
 * types because they have separate lifetimes - the cursor round-trips through the payload while the row
 * table is re-read from the card file on every request - so all three widths are asserted
 * independently: 58, 196 and 254.
 */
@DisplayName("CardListRequest - COCRDLI symbolic map projection")
class CardListRequestTest {

    /**
     * The 34 numbered row members in copybook order, {@code app/cpy-bms/COCRDLI.CPY:78-276}. Row 1
     * contributes four and rows 2 through 7 contribute five each; there is deliberately no
     * {@code crdstp1}.
     */
    private static final List<String> NUMBERED_ROW_MEMBERS = numberedRowMembers();

    private static List<String> numberedRowMembers() {
        List<String> members = new ArrayList<>();
        members.add("crdsel1");
        members.add("acctno1");
        members.add("crdnum1");
        members.add("crdsts1");
        for (int row = 2; row <= CardListRequest.SCREEN_ROW_COUNT; row++) {
            members.add("crdsel" + row);
            members.add("crdstp" + row);
            members.add("acctno" + row);
            members.add("crdnum" + row);
            members.add("crdsts" + row);
        }
        return List.copyOf(members);
    }

    /** Charset for the codec under test. The fixtures in {@code app/data/ASCII} are US-ASCII. */
    private static final FixedWidthCodec CODEC = new FixedWidthCodec(StandardCharsets.US_ASCII);

    /** {@code LOW-VALUES} as a one-character string. */
    private static final String LOW = "\u0000";

    /**
     * One name-labelled {@code DFHMDF} definition, transcribed by hand from
     * {@code app/bms/COCRDLI.bms}.
     *
     * @param dfhmdfName   the label in column 1 of the {@code DFHMDF} line
     * @param length       the declared {@code LENGTH=}
     * @param screenLine   the first element of {@code POS=(line,column)}
     * @param screenColumn the second element of {@code POS=(line,column)}
     */
    private record BmsField(String dfhmdfName, int length, int screenLine, int screenColumn) { }

    /**
     * All <strong>45</strong> name-labelled {@code DFHMDF} definitions of {@code app/bms/COCRDLI.bms},
     * in source order, each with its declared {@code LENGTH} and {@code POS}. Written out one literal
     * entry per field on purpose (AAP &sect;0.10.2 B11): this table is the <em>oracle</em>, so it is
     * transcribed from the mapset and never derived from {@link CardListRequest}. A reader can diff it
     * against the {@code .bms} line by line, which is impossible for a table a loop produced.
     *
     * <p>The mapset holds <strong>72</strong> {@code DFHMDF} entries in total. The other <strong>27</strong>
     * are unnamed and are not payload: they are the literal captions ({@code 'Tran:'}, {@code 'Date:'},
     * {@code 'Prog:'}, {@code 'Time:'}, {@code 'List Credit Cards'}, {@code 'Page '}, the two field
     * prompts, the four column headings, the four rules of dashes and the function-key legend) plus the
     * {@code LENGTH=0} attribute terminators. 72 = 45 + 27, and only the 45 appear here.
     *
     * <p>Note in particular what is <em>absent</em>: there is no {@code FKEYS} field on this map at all.
     * The function-key legend at {@code app/bms/COCRDLI.bms:335-339} is an <em>unnamed</em>
     * {@code DFHMDF LENGTH=78 POS=(24,1) INITIAL='  F3=Exit F7=Backward  F8=Forward'} - a static caption
     * the program never addresses. The sibling card maps do declare one ({@code COCRDSL} at 75 bytes,
     * {@code COCRDUP} at 21 and 18), so a field set may not be carried from one map to the next.
     */
    private static final List<BmsField> BMS_FIELDS = List.of(
            // -- Header: 9 fields, screen lines 1, 2, 4, 6 and 7 -------------------------------------
            new BmsField("TRNNAME", 4, 1, 7),
            new BmsField("TITLE01", 40, 1, 21),
            new BmsField("CURDATE", 8, 1, 71),
            new BmsField("PGMNAME", 8, 2, 7),
            new BmsField("TITLE02", 40, 2, 21),
            new BmsField("CURTIME", 8, 2, 71),
            new BmsField("PAGENO", 3, 4, 76),
            new BmsField("ACCTSID", 11, 6, 44),
            new BmsField("CARDSID", 16, 7, 44),
            // -- Row 1: FOUR fields on screen line 11. There is deliberately no CRDSTP1. -------------
            new BmsField("CRDSEL1", 1, 11, 12),
            new BmsField("ACCTNO1", 11, 11, 22),
            new BmsField("CRDNUM1", 16, 11, 43),
            new BmsField("CRDSTS1", 1, 11, 67),
            // -- Rows 2-7: FIVE fields each, CRDSTPn second, on screen lines 12 through 17 -----------
            new BmsField("CRDSEL2", 1, 12, 12),
            new BmsField("CRDSTP2", 1, 12, 14),
            new BmsField("ACCTNO2", 11, 12, 22),
            new BmsField("CRDNUM2", 16, 12, 43),
            new BmsField("CRDSTS2", 1, 12, 67),
            new BmsField("CRDSEL3", 1, 13, 12),
            new BmsField("CRDSTP3", 1, 13, 14),
            new BmsField("ACCTNO3", 11, 13, 22),
            new BmsField("CRDNUM3", 16, 13, 43),
            new BmsField("CRDSTS3", 1, 13, 67),
            new BmsField("CRDSEL4", 1, 14, 12),
            new BmsField("CRDSTP4", 1, 14, 14),
            new BmsField("ACCTNO4", 11, 14, 22),
            new BmsField("CRDNUM4", 16, 14, 43),
            new BmsField("CRDSTS4", 1, 14, 67),
            new BmsField("CRDSEL5", 1, 15, 12),
            new BmsField("CRDSTP5", 1, 15, 14),
            new BmsField("ACCTNO5", 11, 15, 22),
            new BmsField("CRDNUM5", 16, 15, 43),
            new BmsField("CRDSTS5", 1, 15, 67),
            new BmsField("CRDSEL6", 1, 16, 12),
            new BmsField("CRDSTP6", 1, 16, 14),
            new BmsField("ACCTNO6", 11, 16, 22),
            new BmsField("CRDNUM6", 16, 16, 43),
            new BmsField("CRDSTS6", 1, 16, 67),
            new BmsField("CRDSEL7", 1, 17, 12),
            new BmsField("CRDSTP7", 1, 17, 14),
            new BmsField("ACCTNO7", 11, 17, 22),
            new BmsField("CRDNUM7", 16, 17, 43),
            new BmsField("CRDSTS7", 1, 17, 67),
            // -- Footer: 2 fields. 45 and 78 here, NOT the 40 and 80 of COCRDSL and COCRDUP. ---------
            new BmsField("INFOMSG", 45, 20, 19),
            new BmsField("ERRMSG", 78, 23, 1));

    /**
     * The <strong>45</strong> JSON member names the payload must expose, written out as an explicit
     * literal list. It is deliberately <em>not</em> produced by transforming {@link CardListRequest}'s
     * own accessors or record components: a list derived from the class under test would agree with that
     * class by construction and could never detect a member that should not be there.
     *
     * <p>One member per {@code xxxI} item of {@code app/cpy-bms/COCRDLI.CPY:17-288}, in copybook order.
     * There is no {@code crdstp1}, because there is no {@code CRDSTP1I}.
     */
    private static final List<String> PAYLOAD_JSON_MEMBERS = List.of(
            "trnname", "title01", "curdate", "pgmname", "title02", "curtime", "pageno", "acctsid",
            "cardsid",
            "crdsel1", "acctno1", "crdnum1", "crdsts1",
            "crdsel2", "crdstp2", "acctno2", "crdnum2", "crdsts2",
            "crdsel3", "crdstp3", "acctno3", "crdnum3", "crdsts3",
            "crdsel4", "crdstp4", "acctno4", "crdnum4", "crdsts4",
            "crdsel5", "crdstp5", "acctno5", "crdnum5", "crdsts5",
            "crdsel6", "crdstp6", "acctno6", "crdnum6", "crdsts6",
            "crdsel7", "crdstp7", "acctno7", "crdnum7", "crdsts7",
            "infomsg", "errmsg");

    /**
     * The leading {@code 02 FILLER PIC X(12).} of {@code app/cpy-bms/COCRDLI.CPY:18}, present because
     * the mapset declares {@code TIOAPFX=YES}. Transcribed, not imported, so the offset walk below has
     * an oracle independent of the class under test.
     */
    private static final int TIOAPFX_FILLER_BYTES = 12;

    /**
     * The per-field prefix every {@code xxxI} item sits behind:
     * {@code 02 xxxL COMP PIC S9(4).} (2) + {@code 02 xxxF PICTURE X.} (1) +
     * {@code 02 FILLER PICTURE X(4).} (4) = <strong>7</strong>. The {@code xxxA} item is a
     * {@code REDEFINES} of {@code xxxF} and so occupies no storage of its own.
     */
    private static final int SYMBOLIC_PREFIX_BYTES = 7;

    /** Screen width declared by {@code CCRDLIA DFHMDI SIZE=(24,80)} - {@code app/bms/COCRDLI.bms:28}. */
    private static final int SCREEN_COLUMNS = 80;

    /** Screen depth declared by {@code CCRDLIA DFHMDI SIZE=(24,80)} - {@code app/bms/COCRDLI.bms:28}. */
    private static final int SCREEN_LINES = 24;

    // -------------------------------------------------------------------------------------------------
    // 1. FIELD INVENTORY AND GEOMETRY
    // -------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Field inventory and geometry")
    class Geometry {

        @Test
        @DisplayName("declares exactly 45 payload members, reconciling 9 + 4 + 6x5 + 2")
        void fieldCountIsFortyFive() {
            assertThat(CardListRequest.HEADER_FIELD_COUNT).isEqualTo(9);
            assertThat(CardListRequest.FIRST_ROW_FIELD_COUNT).isEqualTo(4);
            assertThat(CardListRequest.STOPPER_ROW_FIELD_COUNT).isEqualTo(5);
            assertThat(CardListRequest.FOOTER_FIELD_COUNT).isEqualTo(2);
            assertThat(CardListRequest.FIELD_COUNT).isEqualTo(45);
            assertThat(new CardListRequest().payloadFieldCount()).isEqualTo(45);
        }

        @Test
        @DisplayName("45 is reachable only with the asymmetry: a uniform model gives 46 or 39")
        void asymmetryIsWhatMakesFortyFiveWork() {
            int uniformFive = 9 + 5 * CardListRequest.SCREEN_ROW_COUNT + 2;
            int uniformFour = 9 + 4 * CardListRequest.SCREEN_ROW_COUNT + 2;
            int oneStopperDropped = CardListRequest.FIELD_COUNT - CardListRequest.CRDSTP_LENGTH;
            assertThat(uniformFive).as("a uniform 7x5 model over-counts by one").isEqualTo(46);
            assertThat(uniformFour).as("a uniform 7x4 model under-counts by six").isEqualTo(39);
            assertThat(oneStopperDropped).as("dropping any one CRDSTPn under-counts by one")
                    .isEqualTo(44);
            assertThat(CardListRequest.FIELD_COUNT)
                    .isNotIn(uniformFive, uniformFour, oneStopperDropped)
                    .isEqualTo(9 + 4 + 5 * (CardListRequest.SCREEN_ROW_COUNT - 1) + 2);
        }

        @Test
        @DisplayName("widths come from the xxxI PICTURE clauses of COCRDLI.CPY")
        void declaredWidths() {
            assertThat(CardListRequest.TRNNAME_LENGTH).isEqualTo(4);
            assertThat(CardListRequest.TITLE01_LENGTH).isEqualTo(40);
            assertThat(CardListRequest.CURDATE_LENGTH).isEqualTo(8);
            assertThat(CardListRequest.PGMNAME_LENGTH).isEqualTo(8);
            assertThat(CardListRequest.TITLE02_LENGTH).isEqualTo(40);
            assertThat(CardListRequest.CURTIME_LENGTH).isEqualTo(8);
            assertThat(CardListRequest.PAGENO_LENGTH).isEqualTo(3);
            assertThat(CardListRequest.ACCTSID_LENGTH).isEqualTo(11);
            assertThat(CardListRequest.CARDSID_LENGTH).isEqualTo(16);
            assertThat(CardListRequest.CRDSEL_LENGTH).isEqualTo(1);
            assertThat(CardListRequest.CRDSTP_LENGTH).isEqualTo(1);
            assertThat(CardListRequest.ACCTNO_LENGTH).isEqualTo(11);
            assertThat(CardListRequest.CRDNUM_LENGTH).isEqualTo(16);
            assertThat(CardListRequest.CRDSTS_LENGTH).isEqualTo(1);
        }

        @Test
        @DisplayName("INFOMSG is 45 and ERRMSG is 78, not the 40/80 of COCRDSL and COCRDUP")
        void footerWidthsDivergeFromTheSiblingCardMaps() {
            assertThat(CardListRequest.INFOMSG_LENGTH).isEqualTo(45).isNotEqualTo(40);
            assertThat(CardListRequest.ERRMSG_LENGTH).isEqualTo(78).isNotEqualTo(80);
        }

        @Test
        @DisplayName("the group image is 12 + 45x7 + 470 = 797 bytes")
        void groupGeometry() {
            assertThat(CardListRequest.TIOAPFX_LENGTH).isEqualTo(12);
            assertThat(CardListRequest.LENGTH_ITEM_LENGTH).isEqualTo(2);
            assertThat(CardListRequest.FLAG_ITEM_LENGTH).isEqualTo(1);
            assertThat(CardListRequest.ATTRIBUTE_ITEM_LENGTH).isZero();
            assertThat(CardListRequest.RESERVED_FILLER_LENGTH).isEqualTo(4);
            assertThat(CardListRequest.FIELD_OVERHEAD_LENGTH).isEqualTo(7);
            assertThat(CardListRequest.HEADER_DATA_LENGTH).isEqualTo(138);
            assertThat(CardListRequest.FIRST_ROW_DATA_LENGTH).isEqualTo(29);
            assertThat(CardListRequest.STOPPER_ROW_DATA_LENGTH).isEqualTo(30);
            assertThat(CardListRequest.STOPPER_ROWS_DATA_LENGTH).isEqualTo(180);
            assertThat(CardListRequest.FOOTER_DATA_LENGTH).isEqualTo(123);
            assertThat(CardListRequest.PAYLOAD_DATA_LENGTH).isEqualTo(470);
            assertThat(CardListRequest.GROUP_LENGTH).isEqualTo(797);
        }

        @Test
        @DisplayName("row data bytes are derived from the rows themselves: 29 + 180 = 209")
        void rowDataLengthIsDerived() {
            assertThat(new CardListRequest().rowDataLength()).isEqualTo(209);
            assertThat(CardListRequest.FIRST_ROW_DATA_LENGTH
                    + CardListRequest.STOPPER_ROWS_DATA_LENGTH).isEqualTo(209);
        }

        @Test
        @DisplayName("the cursor is 58 bytes and the transported commarea 254")
        void cursorGeometry() {
            assertThat(CardListRequest.CURSOR_CARD_NUM_LENGTH).isEqualTo(16);
            assertThat(CardListRequest.CURSOR_ACCT_ID_LENGTH).isEqualTo(11);
            assertThat(CardListRequest.CARD_KEY_LENGTH).isEqualTo(27);
            assertThat(CardListRequest.CURSOR_LENGTH).isEqualTo(58);
            assertThat(CardListRequest.SCREEN_ROW_LENGTH).isEqualTo(28);
            assertThat(CardListRequest.SCREEN_DATA_LENGTH).isEqualTo(196);
            assertThat(CardListRequest.PROG_COMMAREA_LENGTH).isEqualTo(254);
            assertThat(CardListRequest.SELECT_FLAGS_LENGTH).isEqualTo(7);
        }

        @Test
        @DisplayName("declaredLength() reports each structure's own width")
        void declaredLengthAccessors() {
            assertThat(CardKey.initialised().declaredLength()).isEqualTo(27);
            assertThat(PageCursor.initialised().declaredLength()).isEqualTo(58);
            assertThat(ScreenRow.lowValues().declaredLength()).isEqualTo(28);
            assertThat(ScreenRowTable.lowValues().declaredLength()).isEqualTo(196);
            assertThat(SelectionFlags.lowValues().declaredLength()).isEqualTo(7);
            assertThat(SelectionErrorFlags.none().declaredLength()).isEqualTo(7);
        }

        @Test
        @DisplayName("the screen identity literals match COCRDLIC's own constants")
        void screenIdentity() {
            assertThat(CardListRequest.TRANSACTION_ID).isEqualTo("CCLI");
            assertThat(CardListRequest.PROGRAM_NAME).isEqualTo("COCRDLIC");
            assertThat(CardListRequest.MAPSET_NAME).isEqualTo("COCRDLI");
            assertThat(CardListRequest.MAP_NAME).isEqualTo("CCRDLIA");
        }
    }

    // -------------------------------------------------------------------------------------------------
    // 2. THE ROW-1 ASYMMETRY
    // -------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("The row-1 asymmetry: 4 members for row 1, 5 for rows 2-7")
    class RowAsymmetry {

        @Test
        @DisplayName("FirstListRow declares exactly 4 record components and no crdStp")
        void firstRowHasFourComponents() {
            RecordComponent[] components = FirstListRow.class.getRecordComponents();
            assertThat(components).hasSize(4);
            assertThat(Stream.of(components).map(RecordComponent::getName))
                    .containsExactly("crdSel", "acctNo", "crdNum", "crdSts")
                    .doesNotContain("crdStp");
        }

        @Test
        @DisplayName("StopperListRow declares 5 components with crdStp SECOND, not last")
        void stopperRowHasCrdStpSecond() {
            RecordComponent[] components = StopperListRow.class.getRecordComponents();
            assertThat(components).hasSize(5);
            assertThat(Stream.of(components).map(RecordComponent::getName))
                    .containsExactly("crdSel", "crdStp", "acctNo", "crdNum", "crdSts");
            assertThat(components[1].getName()).isEqualTo("crdStp");
        }

        @Test
        @DisplayName("FirstListRow exposes no crdStp accessor at all")
        void firstRowHasNoStopperAccessor() {
            assertThatExceptionOfType(NoSuchMethodException.class)
                    .isThrownBy(() -> FirstListRow.class.getMethod("crdStp"));
        }

        @Test
        @DisplayName("memberCount, dataLength and hasStopper report the two shapes")
        void shapeMetadata() {
            ListRow first = FirstListRow.blank();
            ListRow stopper = StopperListRow.blank();
            assertThat(first.memberCount()).isEqualTo(4);
            assertThat(stopper.memberCount()).isEqualTo(5);
            assertThat(first.dataLength()).isEqualTo(29);
            assertThat(stopper.dataLength()).isEqualTo(30);
            assertThat(first.hasStopper()).isFalse();
            assertThat(stopper.hasStopper()).isTrue();
        }

        @Test
        @DisplayName("a blank request has a FirstListRow at row 1 and StopperListRow at rows 2-7")
        void defaultShapes() {
            CardListRequest request = new CardListRequest();
            assertThat(request.row(1)).isInstanceOf(FirstListRow.class);
            for (int rowNumber = 2; rowNumber <= 7; rowNumber++) {
                assertThat(request.row(rowNumber)).isInstanceOf(StopperListRow.class);
            }
            assertThat(request.rowFieldCount()).isEqualTo(34);
        }

        @Test
        @DisplayName("firstRow() and lastRow() return the exact types, covering index 1 and index 7")
        void firstAndLastRowAreTyped() {
            CardListRequest request = new CardListRequest();
            request.setRow(1, new FirstListRow("S", "00000000001", "4111111111111111", "Y"));
            request.setRow(7, new StopperListRow("U", "*", "00000000007", "4111111111111177", "N"));

            FirstListRow first = request.firstRow();
            StopperListRow last = request.lastRow();
            assertThat(first.crdSel()).isEqualTo("S");
            assertThat(first.acctNo()).isEqualTo("00000000001");
            assertThat(last.crdStp()).isEqualTo("*");
            assertThat(last.crdSts()).isEqualTo("N");
        }

        @Test
        @DisplayName("a StopperListRow is rejected for row 1")
        void stopperRejectedAtRowOne() {
            CardListRequest request = new CardListRequest();
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> request.setRow(1, StopperListRow.blank()))
                    .withMessageContaining("FirstListRow with 4 members")
                    .withMessageContaining("no CRDSTP1");
        }

        @ParameterizedTest(name = "row {0} rejects a FirstListRow")
        @ValueSource(ints = {2, 3, 4, 5, 6, 7})
        @DisplayName("a FirstListRow is rejected for rows 2 through 7")
        void firstRejectedAtStopperRows(int rowNumber) {
            CardListRequest request = new CardListRequest();
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> request.setRow(rowNumber, FirstListRow.blank()))
                    .withMessageContaining("StopperListRow with 5 members");
        }

        @Test
        @DisplayName("a uniform seven-row list of one shape is rejected wholesale")
        void uniformListRejected() {
            List<ListRow> allStoppers = new ArrayList<>();
            List<ListRow> allFirsts = new ArrayList<>();
            for (int index = 0; index < 7; index++) {
                allStoppers.add(StopperListRow.blank());
                allFirsts.add(FirstListRow.blank());
            }
            CardListRequest request = new CardListRequest();
            assertThatIllegalArgumentException().isThrownBy(() -> request.setRows(allStoppers));
            assertThatIllegalArgumentException().isThrownBy(() -> request.setRows(allFirsts));
        }

        @Test
        @DisplayName("stopperOf is present for rows 2-7 and empty for row 1")
        void stopperOfExhaustiveSwitch() {
            CardListRequest request = new CardListRequest();
            request.setRow(4, new StopperListRow("S", "#", "00000000004", "4111111111111144", "Y"));
            assertThat(request.stopperOf(1)).isEmpty();
            assertThat(request.stopperOf(4)).contains("#");
            assertThat(request.stopperOf(7)).contains(" ");
        }

        @Test
        @DisplayName("setRows accepts a correctly shaped list and rejects a mis-sized or null one")
        void setRowsValidation() {
            CardListRequest request = new CardListRequest();
            List<ListRow> correct = new ArrayList<>(new CardListRequest().getRows());
            request.setRows(correct);
            assertThat(request.getRows()).hasSize(7);

            assertThatNullPointerException().isThrownBy(() -> request.setRows(null));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> request.setRows(List.of(FirstListRow.blank())))
                    .withMessageContaining("exactly 7 detail rows");

            List<ListRow> withNull = new ArrayList<>(correct);
            withNull.set(3, null);
            assertThatNullPointerException().isThrownBy(() -> request.setRows(withNull));
        }

        @Test
        @DisplayName("getRows returns an unmodifiable snapshot that is not the internal list")
        void getRowsIsDefensive() {
            CardListRequest request = new CardListRequest();
            List<ListRow> snapshot = request.getRows();
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> snapshot.set(0, FirstListRow.blank()));
            request.setRow(2, new StopperListRow("S", " ", "00000000002", "4", "Y"));
            assertThat(snapshot.get(1)).isNotEqualTo(request.row(2));
        }

        @Test
        @DisplayName("every row member rejects null, naming the field")
        void rowMembersRejectNull() {
            assertThatNullPointerException().isThrownBy(() -> new FirstListRow(null, "a", "b", "c"))
                    .withMessageContaining("CRDSEL1");
            assertThatNullPointerException().isThrownBy(() -> new FirstListRow("a", null, "b", "c"))
                    .withMessageContaining("ACCTNO1");
            assertThatNullPointerException().isThrownBy(() -> new FirstListRow("a", "b", null, "c"))
                    .withMessageContaining("CRDNUM1");
            assertThatNullPointerException().isThrownBy(() -> new FirstListRow("a", "b", "c", null))
                    .withMessageContaining("CRDSTS1");

            assertThatNullPointerException()
                    .isThrownBy(() -> new StopperListRow(null, "a", "b", "c", "d"))
                    .withMessageContaining("CRDSELn");
            assertThatNullPointerException()
                    .isThrownBy(() -> new StopperListRow("a", null, "b", "c", "d"))
                    .withMessageContaining("CRDSTPn");
            assertThatNullPointerException()
                    .isThrownBy(() -> new StopperListRow("a", "b", null, "c", "d"))
                    .withMessageContaining("ACCTNOn");
            assertThatNullPointerException()
                    .isThrownBy(() -> new StopperListRow("a", "b", "c", null, "d"))
                    .withMessageContaining("CRDNUMn");
            assertThatNullPointerException()
                    .isThrownBy(() -> new StopperListRow("a", "b", "c", "d", null))
                    .withMessageContaining("CRDSTSn");
        }
    }

    // -------------------------------------------------------------------------------------------------
    // 3. OCCURS IS 1-BASED
    // -------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("OCCURS is 1-based in COBOL and 0-based in Java")
    class OneBasedIndexing {

        @ParameterizedTest(name = "subscript {0} maps to index {1}")
        @CsvSource({"1,0", "2,1", "3,2", "4,3", "5,4", "6,5", "7,6"})
        @DisplayName("javaIndexOf shifts by exactly one, both ends included")
        void javaIndexOfShiftsByOne(int cobolRowNumber, int javaIndex) {
            assertThat(CardListRequest.javaIndexOf(cobolRowNumber)).isEqualTo(javaIndex);
            assertThat(CardListRequest.cobolRowNumberOf(javaIndex)).isEqualTo(cobolRowNumber);
        }

        @ParameterizedTest(name = "subscript {0} is rejected")
        @ValueSource(ints = {-1, 0, 8, 99})
        @DisplayName("there is no subscript 0 and no subscript 8; both are rejected, not clamped")
        void outOfRangeSubscriptsRejected(int cobolRowNumber) {
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> CardListRequest.javaIndexOf(cobolRowNumber));
        }

        @ParameterizedTest(name = "java index {0} is rejected")
        @ValueSource(ints = {-1, 7, 99})
        @DisplayName("cobolRowNumberOf rejects an out-of-range Java index")
        void outOfRangeJavaIndexRejected(int javaIndex) {
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> CardListRequest.cobolRowNumberOf(javaIndex));
        }

        @Test
        @DisplayName("row(1) and row(7) address the first and last detail rows, and row(8) throws")
        void detailRowTableIsOneBased() {
            CardListRequest request = new CardListRequest();
            request.setRow(1, new FirstListRow("S", "00000000001", "4111111111111111", "Y"));
            request.setRow(7, new StopperListRow("U", " ", "00000000007", "4111111111111177", "N"));
            assertThat(request.row(1).acctNo()).isEqualTo("00000000001");
            assertThat(request.row(7).acctNo()).isEqualTo("00000000007");
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> request.row(8));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> request.row(0));
        }

        @ParameterizedTest(name = "screen row {0} sits at byte offset {1}")
        @CsvSource({"1,0", "2,28", "3,56", "4,84", "5,112", "6,140", "7,168"})
        @DisplayName("WS-SCREEN-ROWS offsets step by 28 from 0 to 168")
        void screenRowOffsets(int cobolRowNumber, int expectedOffset) {
            assertThat(ScreenRowTable.rowOffset(cobolRowNumber)).isEqualTo(expectedOffset);
        }

        @ParameterizedTest(name = "selection flag {0} sits at byte offset {1}")
        @CsvSource({"1,0", "2,1", "3,2", "4,3", "5,4", "6,5", "7,6"})
        @DisplayName("the two X(7) flag tables step by 1 from 0 to 6")
        void flagOffsets(int cobolRowNumber, int expectedOffset) {
            assertThat(SelectionFlags.flagOffset(cobolRowNumber)).isEqualTo(expectedOffset);
            assertThat(SelectionErrorFlags.flagOffset(cobolRowNumber)).isEqualTo(expectedOffset);
        }

        @Test
        @DisplayName("offset helpers reject an out-of-range subscript")
        void offsetHelpersRejectOutOfRange() {
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> ScreenRowTable.rowOffset(0));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> SelectionFlags.flagOffset(8));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> SelectionErrorFlags.flagOffset(8));
        }

        @Test
        @DisplayName("the row table addresses index 1 and index 7 with no off-by-one")
        void screenRowTableIsOneBased() {
            ScreenRowTable table = ScreenRowTable.lowValues()
                    .withRow(1, new ScreenRow("00000000001", "4111111111111111", "Y"))
                    .withRow(7, new ScreenRow("00000000007", "4111111111111177", "N"));
            assertThat(table.row(1).acctNo()).isEqualTo("00000000001");
            assertThat(table.row(7).cardStatus()).isEqualTo("N");
            assertThat(table.row(4).isCleared()).isTrue();
            assertThat(table.row(1).isCleared()).isFalse();
            assertThatExceptionOfType(IndexOutOfBoundsException.class).isThrownBy(() -> table.row(8));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> table.withRow(0, ScreenRow.lowValues()));
        }
    }

    // -------------------------------------------------------------------------------------------------
    // 4. SPACES, LOW-VALUES AND NULL ARE THREE DIFFERENT THINGS
    // -------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("SPACES, LOW-VALUES and null are three different things")
    class FigurativeConstants {

        @Test
        @DisplayName("a fresh selection table holds seven LOW-VALUES bytes, not seven spaces")
        void selectionFlagsStartAtLowValues() {
            SelectionFlags fresh = new CardListRequest().getSelectionFlags();
            assertThat(fresh.flags()).isEqualTo(LOW.repeat(7)).isNotEqualTo(" ".repeat(7));
            for (int rowNumber = 1; rowNumber <= 7; rowNumber++) {
                assertThat(fresh.at(rowNumber)).isEqualTo('\u0000');
            }
        }

        @Test
        @DisplayName("SELECT-BLANK is true for BOTH a space and LOW-VALUES")
        void selectBlankAcceptsBoth() {
            assertThat(SelectionFlags.lowValues().isSelectBlank(1)).isTrue();
            assertThat(SelectionFlags.spacesFilled().isSelectBlank(1)).isTrue();
            assertThat(SelectionFlags.lowValues().withSelection(1, 'S').isSelectBlank(1)).isFalse();
            assertThat(SelectionFlags.lowValues().withSelection(1, 'X').isSelectBlank(1)).isFalse();
        }

        @Test
        @DisplayName("the cursor's off states are LOW-VALUES only, never a space and never empty")
        void offStatesAreLowValuesOnly() {
            PageCursor withLowValues = PageCursor.initialised()
                    .withNextPageNotExists()
                    .withReturnFlagOff();
            assertThat(withLowValues.isNextPageNotExists()).isTrue();
            assertThat(withLowValues.isReturnFlagOff()).isTrue();

            PageCursor initialised = PageCursor.initialised();
            assertThat(initialised.nextPageInd()).isEqualTo(" ");
            assertThat(initialised.isNextPageNotExists()).isFalse();
            assertThat(initialised.isNextPageExists()).isFalse();
            assertThat(initialised.isReturnFlagOff()).isFalse();
            assertThat(initialised.isReturnFlagOn()).isFalse();

            PageCursor empty = new PageCursor(CardKey.initialised(), CardKey.initialised(), 1, 9,
                    "", "");
            assertThat(empty.isNextPageNotExists()).isFalse();
            assertThat(empty.isReturnFlagOff()).isFalse();
        }

        @Test
        @DisplayName("spaces(n) and CardScreenState.lowValues(n) are different byte images")
        void spacesAndLowValuesDiffer() {
            assertThat(CardListRequest.spaces(4)).isEqualTo("    ");
            assertThat(CardListRequest.spaces(4)).isNotEqualTo(CardScreenState.lowValues(4));
            assertThatIllegalArgumentException().isThrownBy(() -> CardListRequest.spaces(0));
        }

        @Test
        @DisplayName("the row table is cleared to LOW-VALUES, matching MOVE LOW-VALUES TO WS-ALL-ROWS")
        void rowTableClearedToLowValues() {
            ScreenRow cleared = ScreenRow.lowValues();
            assertThat(cleared.acctNo()).isEqualTo(LOW.repeat(11));
            assertThat(cleared.cardNum()).isEqualTo(LOW.repeat(16));
            assertThat(cleared.cardStatus()).isEqualTo(LOW);
            assertThat(cleared.isCleared()).isTrue();
            assertThat(new ScreenRow(" ".repeat(11), LOW.repeat(16), LOW).isCleared()).isFalse();
            assertThat(new ScreenRow(LOW.repeat(11), " ".repeat(16), LOW).isCleared()).isFalse();
            assertThat(new ScreenRow(LOW.repeat(11), LOW.repeat(16), " ").isCleared()).isFalse();
        }

        @Test
        @DisplayName("map fields start space-filled to their declared widths, not LOW-VALUES")
        void mapFieldsStartAsSpaces() {
            CardListRequest request = new CardListRequest();
            assertThat(request.getTrnname()).isEqualTo("    ");
            assertThat(request.getTitle01()).hasSize(40).isBlank();
            assertThat(request.getCurdate()).hasSize(8).isBlank();
            assertThat(request.getPgmname()).hasSize(8).isBlank();
            assertThat(request.getTitle02()).hasSize(40).isBlank();
            assertThat(request.getCurtime()).hasSize(8).isBlank();
            assertThat(request.getPageno()).hasSize(3).isBlank();
            assertThat(request.getAcctsid()).hasSize(11).isBlank();
            assertThat(request.getCardsid()).hasSize(16).isBlank();
            assertThat(request.getInfomsg()).hasSize(45).isBlank();
            assertThat(request.getErrmsg()).hasSize(78).isBlank();
        }

        @Test
        @DisplayName("CardKey.initialised() is spaces and CardKey.lowValues() is binary zero")
        void cardKeyInitialStates() {
            assertThat(CardKey.initialised().cardNum()).isEqualTo(" ".repeat(16));
            assertThat(CardKey.initialised().isCardNumLowValues()).isFalse();
            assertThat(CardKey.lowValues().cardNum()).isEqualTo(LOW.repeat(16));
            assertThat(CardKey.lowValues().isCardNumLowValues()).isTrue();
            assertThat(new CardKey("4111111111111111", 1L).isCardNumLowValues()).isFalse();
            assertThat(new CardKey(LOW, 0L).isCardNumLowValues()).isFalse();
        }
    }

    // -------------------------------------------------------------------------------------------------
    // 5. PAGE SIZE IS BEHAVIOUR
    // -------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Page size 7 is behaviour, not configuration")
    class PageSize {

        @Test
        @DisplayName("PAGE_SIZE is 7 and is a compile-time constant")
        void pageSizeIsSeven() throws ReflectiveOperationException {
            assertThat(CardListRequest.PAGE_SIZE).isEqualTo(7);
            assertThat(CardListRequest.SCREEN_ROW_COUNT).isEqualTo(7);
            assertThat(CardListRequest.FIRST_ROW_NUMBER).isEqualTo(1);
            assertThat(CardListRequest.LAST_ROW_NUMBER).isEqualTo(7);

            java.lang.reflect.Field field = CardListRequest.class.getField("PAGE_SIZE");
            assertThat(java.lang.reflect.Modifier.isStatic(field.getModifiers())).isTrue();
            assertThat(java.lang.reflect.Modifier.isFinal(field.getModifiers())).isTrue();
            assertThat(field.getType()).isEqualTo(int.class);
        }

        @Test
        @DisplayName("no annotation on the class or its members can inject a page size")
        void noConfigurationPath() {
            assertThat(Stream.of(CardListRequest.class.getAnnotations())
                    .map(annotation -> annotation.annotationType().getName()))
                    .as("the one class annotation this type carries is the Jackson binding contract "
                            + "that lets a client send a response body back as the next request (rule "
                            + "R6, gate G37): it names response-only members and injects nothing. Page "
                            + "size 7 is behaviour and not configuration (AAP 0.3.9), so it stays a "
                            + "compile-time constant, and this pins the exact annotation list rather "
                            + "than merely requiring it to be short")
                    .containsExactly(JsonIgnoreProperties.class.getName())
                    .allSatisfy(name -> assertThat(name).doesNotContain("Value")
                            .doesNotContain("ConfigurationProperties"));
            assertThat(Stream.of(CardListRequest.class.getDeclaredFields())
                    .flatMap(field -> Stream.of(field.getAnnotations()))
                    .map(annotation -> annotation.annotationType().getName()))
                    .allSatisfy(name -> assertThat(name).doesNotContain("Value")
                            .doesNotContain("ConfigurationProperties"));
        }

        @Test
        @DisplayName("a request always holds exactly 7 rows")
        void alwaysSevenRows() {
            assertThat(new CardListRequest().getRows()).hasSize(7);
        }
    }

    // -------------------------------------------------------------------------------------------------
    // 6. THE PAGING CURSOR AND ITS 88-LEVELS
    // -------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("The 58-byte paging cursor")
    class Cursor {

        @Test
        @DisplayName("firstPage() is screenNum 1 with the last page not shown")
        void firstPageState() {
            PageCursor cursor = PageCursor.firstPage();
            assertThat(cursor.screenNum()).isEqualTo(1);
            assertThat(cursor.lastPageDisplayed()).isEqualTo(9);
            assertThat(cursor.isFirstPage()).isTrue();
            assertThat(cursor.isLastPageNotShown()).isTrue();
            assertThat(cursor.isLastPageShown()).isFalse();
        }

        @Test
        @DisplayName("initialised() is all zeros and spaces, matching COBOL INITIALIZE")
        void initialisedState() {
            PageCursor cursor = PageCursor.initialised();
            assertThat(cursor.screenNum()).isZero();
            assertThat(cursor.lastPageDisplayed()).isZero();
            assertThat(cursor.isFirstPage()).isFalse();
            assertThat(cursor.isLastPageShown()).isTrue();
            assertThat(cursor.isLastPageNotShown()).isFalse();
        }

        @ParameterizedTest(name = "lastPageDisplayed {0} satisfies neither 88-level")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
        @DisplayName("values 1 through 8 satisfy neither CA-LAST-PAGE-SHOWN nor -NOT-SHOWN")
        void middleValuesSatisfyNeitherCondition(int value) {
            PageCursor cursor = PageCursor.initialised().withLastPageDisplayed(value);
            assertThat(cursor.isLastPageShown()).isFalse();
            assertThat(cursor.isLastPageNotShown()).isFalse();
        }

        @Test
        @DisplayName("the next-page and return-flag setters write the declared 88-level values")
        void indicatorSetters() {
            PageCursor exists = PageCursor.firstPage().withNextPageExists();
            assertThat(exists.nextPageInd()).isEqualTo("Y");
            assertThat(exists.isNextPageExists()).isTrue();
            assertThat(exists.isNextPageNotExists()).isFalse();

            PageCursor notExists = exists.withNextPageNotExists();
            assertThat(notExists.isNextPageNotExists()).isTrue();
            assertThat(notExists.isNextPageExists()).isFalse();

            PageCursor on = PageCursor.firstPage().withReturnFlagOn();
            assertThat(on.returnFlag()).isEqualTo("1");
            assertThat(on.isReturnFlagOn()).isTrue();
            assertThat(on.isReturnFlagOff()).isFalse();

            PageCursor off = on.withReturnFlagOff();
            assertThat(off.isReturnFlagOff()).isTrue();
            assertThat(off.isReturnFlagOn()).isFalse();
        }

        @Test
        @DisplayName("withLastCardKeyFromFirst reproduces the 27-byte group move at COCRDLIC:1268")
        void groupMove() {
            CardKey first = new CardKey("4111111111111111", 12_345_678_901L);
            PageCursor cursor = PageCursor.firstPage()
                    .withFirstCardKey(first)
                    .withLastCardKey(CardKey.lowValues());
            assertThat(cursor.lastCardKey()).isNotEqualTo(first);

            PageCursor moved = cursor.withLastCardKeyFromFirst();
            assertThat(moved.lastCardKey()).isEqualTo(first);
            assertThat(moved.firstCardKey()).isEqualTo(first);
        }

        @Test
        @DisplayName("the flattened accessors expose all eight mandated cursor fields")
        void flattenedAccessors() {
            PageCursor cursor = PageCursor.firstPage()
                    .withLastCardKey(new CardKey("4111111111111199", 99L))
                    .withFirstCardKey(new CardKey("4111111111111100", 11L))
                    .withScreenNum(3)
                    .withLastPageDisplayed(0)
                    .withNextPageExists()
                    .withReturnFlagOn();
            assertThat(cursor.lastCardNum()).isEqualTo("4111111111111199");
            assertThat(cursor.lastCardAcctId()).isEqualTo(99L);
            assertThat(cursor.firstCardNum()).isEqualTo("4111111111111100");
            assertThat(cursor.firstCardAcctId()).isEqualTo(11L);
            assertThat(cursor.screenNum()).isEqualTo(3);
            assertThat(cursor.lastPageDisplayed()).isZero();
            assertThat(cursor.nextPageInd()).isEqualTo("Y");
            assertThat(cursor.returnFlag()).isEqualTo("1");
        }

        @Test
        @DisplayName("the cursor rejects nulls and out-of-range single digits")
        void cursorValidation() {
            CardKey key = CardKey.initialised();
            assertThatNullPointerException()
                    .isThrownBy(() -> new PageCursor(null, key, 1, 9, " ", " "));
            assertThatNullPointerException()
                    .isThrownBy(() -> new PageCursor(key, null, 1, 9, " ", " "));
            assertThatNullPointerException()
                    .isThrownBy(() -> new PageCursor(key, key, 1, 9, null, " "));
            assertThatNullPointerException()
                    .isThrownBy(() -> new PageCursor(key, key, 1, 9, " ", null));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new PageCursor(key, key, -1, 9, " ", " "))
                    .withMessageContaining("WS-CA-SCREEN-NUM");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new PageCursor(key, key, 10, 9, " ", " "))
                    .withMessageContaining("WS-CA-SCREEN-NUM");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new PageCursor(key, key, 1, -1, " ", " "))
                    .withMessageContaining("WS-CA-LAST-PAGE-DISPLAYED");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new PageCursor(key, key, 1, 10, " ", " "))
                    .withMessageContaining("WS-CA-LAST-PAGE-DISPLAYED");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new PageCursor(key, key, 1, 9, "YY", " "))
                    .withMessageContaining("WS-CA-NEXT-PAGE-IND");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new PageCursor(key, key, 1, 9, " ", "11"))
                    .withMessageContaining("WS-RETURN-FLAG");
        }

        @Test
        @DisplayName("the card key rejects a null number and an out-of-range account id")
        void cardKeyValidation() {
            assertThatNullPointerException().isThrownBy(() -> new CardKey(null, 0L));
            assertThatIllegalArgumentException().isThrownBy(() -> new CardKey("x", -1L))
                    .withMessageContaining("unsigned zoned");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new CardKey("x", CardKey.MAX_ACCT_ID + 1L))
                    .withMessageContaining("more than 11 digits");
            assertThat(new CardKey("x", CardKey.MAX_ACCT_ID).acctId())
                    .isEqualTo(99_999_999_999L);
        }
    }

    // -------------------------------------------------------------------------------------------------
    // 7. THE SELECTION TABLES AND THEIR FOUR 88-LEVELS
    // -------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("The selection tables")
    class Selection {

        @ParameterizedTest(name = "action ''{0}'' -> ok={1} view={2} update={3} blank={4}")
        @CsvSource({
            "S,true,true,false,false",
            "U,true,false,true,false",
            "' ',false,false,false,true",
            "X,false,false,false,false",
            "s,false,false,false,false"
        })
        @DisplayName("all four 88-levels are modelled and mutually consistent")
        void allFourConditionNames(String action, boolean ok, boolean view, boolean update,
                                   boolean blank) {
            SelectionFlags flags = SelectionFlags.spacesFilled().withSelection(1, action.charAt(0));
            assertThat(flags.isSelectOk(1)).isEqualTo(ok);
            assertThat(flags.isViewRequestedOn(1)).isEqualTo(view);
            assertThat(flags.isUpdateRequestedOn(1)).isEqualTo(update);
            assertThat(flags.isSelectBlank(1)).isEqualTo(blank);
        }

        @Test
        @DisplayName("fromRows reproduces MOVE CRDSELnI TO WS-EDIT-SELECT(n) for n = 1..7")
        void fromRowsMirrorsTheInboundMoves() {
            CardListRequest request = new CardListRequest();
            request.setRow(1, new FirstListRow("S", "1", "1", "Y"));
            request.setRow(2, new StopperListRow("U", " ", "2", "2", "Y"));
            request.setRow(7, new StopperListRow("X", " ", "7", "7", "Y"));
            request.refreshSelectionFlagsFromRows();

            SelectionFlags flags = request.getSelectionFlags();
            assertThat(flags.at(1)).isEqualTo('S');
            assertThat(flags.at(2)).isEqualTo('U');
            assertThat(flags.at(3)).isEqualTo(' ');
            assertThat(flags.at(7)).isEqualTo('X');
        }

        @Test
        @DisplayName("fromRows pads an empty CRDSELn with a space")
        void fromRowsPadsEmptySelection() {
            List<ListRow> rows = new ArrayList<>();
            rows.add(new FirstListRow("", "1", "1", "Y"));
            for (int index = 2; index <= 7; index++) {
                rows.add(new StopperListRow("", " ", "a", "b", "c"));
            }
            assertThat(SelectionFlags.fromRows(rows).flags()).isEqualTo(" ".repeat(7));
        }

        @Test
        @DisplayName("fromRows rejects null, a mis-sized list and a null element")
        void fromRowsValidation() {
            assertThatNullPointerException().isThrownBy(() -> SelectionFlags.fromRows(null));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> SelectionFlags.fromRows(List.of(FirstListRow.blank())));
            List<ListRow> withNull = new ArrayList<>(new CardListRequest().getRows());
            withNull.set(2, null);
            assertThatNullPointerException().isThrownBy(() -> SelectionFlags.fromRows(withNull));
        }

        @Test
        @DisplayName("I-SELECTED is the LAST qualifying subscript, and 0 when none qualifies")
        void selectedRowNumberFollowsTheCobolLoop() {
            assertThat(SelectionFlags.spacesFilled().selectedRowNumber()).isZero();
            assertThat(SelectionFlags.spacesFilled().isDetailWasRequested()).isFalse();
            assertThat(SelectionFlags.spacesFilled().selectedRowCount()).isZero();

            SelectionFlags one = SelectionFlags.spacesFilled().withSelection(1, 'S');
            assertThat(one.selectedRowNumber()).isEqualTo(1);
            assertThat(one.isDetailWasRequested()).isTrue();
            assertThat(one.selectedRowCount()).isEqualTo(1);

            SelectionFlags seven = SelectionFlags.spacesFilled().withSelection(7, 'U');
            assertThat(seven.selectedRowNumber()).isEqualTo(7);
            assertThat(seven.selectedRowCount()).isEqualTo(1);

            SelectionFlags several = SelectionFlags.spacesFilled()
                    .withSelection(2, 'S')
                    .withSelection(5, 'U');
            assertThat(several.selectedRowNumber()).isEqualTo(5);
            assertThat(several.selectedRowCount()).isEqualTo(2);
        }

        @ParameterizedTest(name = "I-SELECTED {0} -> DETAIL-WAS-REQUESTED {1}")
        @CsvSource({"-9999,false", "-1,false", "0,false", "1,true", "4,true", "7,true", "8,false",
            "9999,false"})
        @DisplayName("DETAIL-WAS-REQUESTED is a two-sided 1 THRU 7 range over I-SELECTED's full domain")
        void detailWasRequestedIsATwoSidedRange(int iSelected, boolean expected) {
            assertThat(SelectionFlags.isDetailRequested(iSelected)).isEqualTo(expected);
        }

        @Test
        @DisplayName("the selection table rejects null and a wrong length")
        void selectionFlagsValidation() {
            assertThatNullPointerException().isThrownBy(() -> new SelectionFlags(null));
            assertThatIllegalArgumentException().isThrownBy(() -> new SelectionFlags("SSS"))
                    .withMessageContaining("PIC X(7)");
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> SelectionFlags.lowValues().at(8));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> SelectionFlags.lowValues().withSelection(0, 'S'));
        }

        @Test
        @DisplayName("WS-ROW-SELECT-ERROR is VALUE '1' and is set per row")
        void errorFlagsPerRow() {
            SelectionErrorFlags none = SelectionErrorFlags.none();
            assertThat(none.isRowSelectError(1)).isFalse();
            assertThat(none.isRowSelectError(7)).isFalse();

            SelectionErrorFlags flagged = none.withRowInError(1).withRowInError(7);
            assertThat(flagged.isRowSelectError(1)).isTrue();
            assertThat(flagged.isRowSelectError(7)).isTrue();
            assertThat(flagged.isRowSelectError(4)).isFalse();
            assertThat(flagged.flags()).isEqualTo("1     1");
        }

        @Test
        @DisplayName("fromSelectionFlags reproduces the INSPECT REPLACING translation")
        void inspectReplacingTranslation() {
            SelectionFlags actions = SelectionFlags.spacesFilled()
                    .withSelection(1, 'S')
                    .withSelection(3, 'U')
                    .withSelection(5, 'X');
            SelectionErrorFlags translated = SelectionErrorFlags.fromSelectionFlags(actions);
            assertThat(translated.flags()).isEqualTo("1010000");
            assertThat(translated.isRowSelectError(1)).isTrue();
            assertThat(translated.isRowSelectError(3)).isTrue();
            assertThat(translated.isRowSelectError(5)).isFalse();
            assertThatNullPointerException()
                    .isThrownBy(() -> SelectionErrorFlags.fromSelectionFlags(null));
        }

        @Test
        @DisplayName("the error table rejects null and a wrong length")
        void errorFlagsValidation() {
            assertThatNullPointerException().isThrownBy(() -> new SelectionErrorFlags(null));
            assertThatIllegalArgumentException().isThrownBy(() -> new SelectionErrorFlags("11"))
                    .withMessageContaining("PIC X(7)");
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> SelectionErrorFlags.none().isRowSelectError(8));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> SelectionErrorFlags.none().withRowInError(0));
        }
    }

    // -------------------------------------------------------------------------------------------------
    // 8. THE ROW TABLE AND FIELD METADATA
    // -------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("The row table and the non-serialised metadata")
    class Metadata {

        @Test
        @DisplayName("the row table copies its element list defensively and enforces OCCURS 7")
        void rowTableValidation() {
            List<ScreenRow> mutable = new ArrayList<>();
            for (int index = 0; index < 7; index++) {
                mutable.add(ScreenRow.lowValues());
            }
            ScreenRowTable table = new ScreenRowTable(mutable);
            mutable.set(0, new ScreenRow("changed    ", "x", "y"));
            assertThat(table.row(1).isCleared()).isTrue();

            assertThatNullPointerException().isThrownBy(() -> new ScreenRowTable(null));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ScreenRowTable(List.of(ScreenRow.lowValues())))
                    .withMessageContaining("OCCURS 7 TIMES");
            assertThatNullPointerException().isThrownBy(() -> table.withRow(1, null));
        }

        @Test
        @DisplayName("a screen row rejects null in any member")
        void screenRowValidation() {
            assertThatNullPointerException().isThrownBy(() -> new ScreenRow(null, "a", "b"))
                    .withMessageContaining("WS-ROW-ACCTNO");
            assertThatNullPointerException().isThrownBy(() -> new ScreenRow("a", null, "b"))
                    .withMessageContaining("WS-ROW-CARD-NUM");
            assertThatNullPointerException().isThrownBy(() -> new ScreenRow("a", "b", null))
                    .withMessageContaining("WS-ROW-CARD-STATUS");
        }

        @Test
        @DisplayName("field metadata carries xxxL and xxxA and reports an untouched field")
        void fieldMetadata() {
            FieldMetadata untouched = FieldMetadata.untouched("ACCTSID");
            assertThat(untouched.dfhmdfName()).isEqualTo("ACCTSID");
            assertThat(untouched.inputLength()).isZero();
            assertThat(untouched.attributeByte()).isEqualTo(' ');
            assertThat(untouched.isUntouched()).isTrue();
            assertThat(new FieldMetadata("ACCTSID", 11, 'x').isUntouched()).isFalse();

            assertThatNullPointerException().isThrownBy(() -> new FieldMetadata(null, 0, ' '));
            assertThatIllegalArgumentException().isThrownBy(() -> new FieldMetadata("  ", 0, ' '))
                    .withMessageContaining("must not be blank");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new FieldMetadata("ACCTSID", -1, ' '))
                    .withMessageContaining("cannot be negative");
        }

        @Test
        @DisplayName("the metadata map is defensively copied on the way in and out")
        void metadataMapIsDefensive() {
            CardListRequest request = new CardListRequest();
            assertThat(request.getFieldMetadata()).isEmpty();
            request.putFieldMetadata(new FieldMetadata("ACCTSID", 11, 'r'));
            assertThat(request.fieldMetadataOf("ACCTSID")).isPresent();
            assertThat(request.fieldMetadataOf("CARDSID")).isEmpty();

            Map<String, FieldMetadata> snapshot = request.getFieldMetadata();
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> snapshot.remove("ACCTSID"));

            Map<String, FieldMetadata> supplied = new LinkedHashMap<>();
            supplied.put("ERRMSG", FieldMetadata.untouched("ERRMSG"));
            request.setFieldMetadata(supplied);
            supplied.clear();
            assertThat(request.fieldMetadataOf("ERRMSG")).isPresent();
            assertThat(request.fieldMetadataOf("ACCTSID")).isEmpty();

            assertThatNullPointerException().isThrownBy(() -> request.setFieldMetadata(null));
            assertThatNullPointerException().isThrownBy(() -> request.putFieldMetadata(null));
            assertThatNullPointerException().isThrownBy(() -> request.fieldMetadataOf(null));

            Map<String, FieldMetadata> withNullValue = new LinkedHashMap<>();
            withNullValue.put("PAGENO", null);
            assertThatNullPointerException()
                    .isThrownBy(() -> request.setFieldMetadata(withNullValue));
        }

        @Test
        @DisplayName("the non-serialised carriers round-trip through their setters")
        void nonSerialisedCarrierSetters() {
            CardListRequest request = new CardListRequest();
            ScreenRowTable table = ScreenRowTable.lowValues()
                    .withRow(1, new ScreenRow("00000000001", "4111111111111111", "Y"));
            request.setScreenRowTable(table);
            assertThat(request.getScreenRowTable().row(1).acctNo()).isEqualTo("00000000001");

            request.setSelectionErrorFlags(SelectionErrorFlags.none().withRowInError(2));
            assertThat(request.getSelectionErrorFlags().isRowSelectError(2)).isTrue();

            assertThatNullPointerException().isThrownBy(() -> request.setScreenRowTable(null));
            assertThatNullPointerException().isThrownBy(() -> request.setSelectionErrorFlags(null));
        }
    }

    // -------------------------------------------------------------------------------------------------
    // 9. ACCESSORS, CARRIERS AND STATELESSNESS
    // -------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Accessors, carriers and statelessness")
    class Accessors {

        @Test
        @DisplayName("every header and footer setter round-trips")
        void mapFieldSetters() {
            CardListRequest request = new CardListRequest();
            request.setTrnname("CCLI");
            request.setTitle01("AWS Mainframe Modernization");
            request.setCurdate("08/08/26");
            request.setPgmname("COCRDLIC");
            request.setTitle02("CardDemo");
            request.setCurtime("12:34:56");
            request.setPageno("001");
            request.setAcctsid("00000000011");
            request.setCardsid("4111111111111111");
            request.setInfomsg("Type S to view or U to update a card");
            request.setErrmsg("Please enter a valid account number");

            assertThat(request.getTrnname()).isEqualTo("CCLI");
            assertThat(request.getTitle01()).isEqualTo("AWS Mainframe Modernization");
            assertThat(request.getCurdate()).isEqualTo("08/08/26");
            assertThat(request.getPgmname()).isEqualTo("COCRDLIC");
            assertThat(request.getTitle02()).isEqualTo("CardDemo");
            assertThat(request.getCurtime()).isEqualTo("12:34:56");
            assertThat(request.getPageno()).isEqualTo("001");
            assertThat(request.getAcctsid()).isEqualTo("00000000011");
            assertThat(request.getCardsid()).isEqualTo("4111111111111111");
            assertThat(request.getInfomsg()).isEqualTo("Type S to view or U to update a card");
            assertThat(request.getErrmsg()).isEqualTo("Please enter a valid account number");
        }

        @ParameterizedTest(name = "{0} rejects null")
        @MethodSource("com.vsergeychik.carddemo.card.dto.CardListRequestTest#nullRejectingSetters")
        @DisplayName("every map field setter rejects null, naming its DFHMDF label")
        void mapFieldSettersRejectNull(String dfhmdfName, Consumer<CardListRequest> setter) {
            CardListRequest request = new CardListRequest();
            assertThatNullPointerException().isThrownBy(() -> setter.accept(request))
                    .withMessageContaining(dfhmdfName);
        }

        @Test
        @DisplayName("the four carriers round-trip and reject null")
        void carrierSetters() {
            CardListRequest request = new CardListRequest();
            PageCursor cursor = PageCursor.firstPage().withScreenNum(4);
            request.setPageCursor(cursor);
            assertThat(request.getPageCursor().screenNum()).isEqualTo(4);

            request.setSelectionFlags(SelectionFlags.spacesFilled().withSelection(3, 'S'));
            assertThat(request.getSelectionFlags().isViewRequestedOn(3)).isTrue();

            CardScreenState state = new CardScreenState();
            state.setCcAcctId("00000000011");
            request.setCardScreenState(state);
            assertThat(request.getCardScreenState().getCcAcctId()).isEqualTo("00000000011");

            request.setNavigationContext(NavigationContext.empty().withPgmReenter());
            assertThat(request.getNavigationContext().pgmContext())
                    .isEqualTo(NavigationContext.PGM_CONTEXT_REENTER);

            assertThatNullPointerException().isThrownBy(() -> request.setPageCursor(null));
            assertThatNullPointerException().isThrownBy(() -> request.setSelectionFlags(null));
            assertThatNullPointerException().isThrownBy(() -> request.setCardScreenState(null));
        }

        @Test
        @DisplayName("the communication area accepts null, because absence is a state the program tests")
        void navigationContextAcceptsAbsence() {
            CardListRequest request = new CardListRequest();

            assertThat(request.getNavigationContext())
                    .as("a fresh request has been passed no communication area, which is EIBCALEN = 0 "
                            + "at app/cbl/COCRDLIC.cbl:315")
                    .isNull();
            assertThat(request.hasNavigationContext()).isFalse();
            assertThat(request.commareaLength()).isZero();

            request.setNavigationContext(NavigationContext.empty());
            assertThat(request.hasNavigationContext()).isTrue();
            assertThat(request.commareaLength()).isEqualTo(NavigationContext.COMMAREA_LENGTH);

            request.setNavigationContext(null);
            assertThat(request.getNavigationContext())
                    .as("null is stored verbatim, never replaced with an initialised area")
                    .isNull();
            assertThat(request.hasNavigationContext()).isFalse();
            assertThat(request.commareaLength()).isZero();
        }

        @Test
        @DisplayName("the CardScreenState carrier is copied in both directions")
        void cardScreenStateIsDefensivelyCopied() {
            CardListRequest request = new CardListRequest();
            CardScreenState supplied = new CardScreenState();
            supplied.setCcAcctId("00000000011");
            request.setCardScreenState(supplied);

            supplied.setCcAcctId("99999999999");
            assertThat(request.getCardScreenState().getCcAcctId()).isEqualTo("00000000011");

            CardScreenState fetched = request.getCardScreenState();
            fetched.setCcAcctId("88888888888");
            assertThat(request.getCardScreenState().getCcAcctId()).isEqualTo("00000000011");
        }

        @Test
        @DisplayName("ENTER, REENTER and absence are three states, not two")
        void enterAndReenter() {
            CardListRequest request = new CardListRequest();

            assertThat(request.isEnter())
                    .as("with no communication area there is no CDEMO-PGM-CONTEXT to test, so the "
                            + "condition name is false rather than true")
                    .isFalse();
            assertThat(request.isReenter()).isFalse();
            assertThat(request.getPgmContext())
                    .as("the reported context falls back to the arm an uninitialised area takes")
                    .isEqualTo(NavigationContext.PGM_CONTEXT_ENTER);

            request.setNavigationContext(NavigationContext.empty().withPgmEnter());
            assertThat(request.isEnter()).isTrue();
            assertThat(request.isReenter()).isFalse();

            request.setNavigationContext(NavigationContext.empty().withPgmReenter());
            assertThat(request.isEnter()).isFalse();
            assertThat(request.isReenter()).isTrue();
            assertThat(request.getPgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_REENTER);

            request.setNavigationContext(NavigationContext.empty().withPgmContext(6));
            assertThat(request.isEnter())
                    .as("PIC 9(01) carries two condition names, not an enumeration")
                    .isFalse();
            assertThat(request.isReenter()).isFalse();
            assertThat(request.getPgmContext()).isEqualTo(6);
        }

        @Test
        @DisplayName("the copy constructor copies every mutable part and rejects null")
        void copyConstructor() {
            CardListRequest original = new CardListRequest();
            original.setAcctsid("00000000011");
            original.setRow(3, new StopperListRow("S", " ", "00000000003", "4", "Y"));
            original.putFieldMetadata(FieldMetadata.untouched("ACCTSID"));
            CardScreenState state = new CardScreenState();
            state.setCcCardNum("4111111111111111");
            original.setCardScreenState(state);

            CardListRequest copy = new CardListRequest(original);
            assertThat(copy).isEqualTo(original);

            original.setAcctsid("99999999999");
            original.setRow(3, StopperListRow.blank());
            original.putFieldMetadata(FieldMetadata.untouched("CARDSID"));
            assertThat(copy.getAcctsid()).isEqualTo("00000000011");
            assertThat(copy.row(3).crdSel()).isEqualTo("S");
            assertThat(copy.getFieldMetadata()).containsOnlyKeys("ACCTSID");
            assertThat(copy.getCardScreenState().getCcCardNum()).isEqualTo("4111111111111111");

            assertThatNullPointerException().isThrownBy(() -> new CardListRequest(null));
        }

        @Test
        @DisplayName("no server-side state: no session, no ThreadLocal, no static mutable field")
        void noServerSideState() {
            assertThat(Stream.of(CardListRequest.class.getDeclaredFields())
                    .filter(field -> java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                    .filter(field -> !java.lang.reflect.Modifier.isFinal(field.getModifiers())))
                    .isEmpty();
            assertThat(Stream.of(CardListRequest.class.getDeclaredFields())
                    .map(field -> field.getType().getName()))
                    .noneMatch(name -> name.contains("ThreadLocal") || name.contains("HttpSession"));
        }
    }

    // -------------------------------------------------------------------------------------------------
    // 10. FIXED-WIDTH NORMALISATION THROUGH THE CODEC
    // -------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Fixed-width normalisation goes through FixedWidthCodec")
    class Normalisation {

        @Test
        @DisplayName("normalised() renders all 45 members at their declared widths")
        void normalisedWidths() {
            CardListRequest request = new CardListRequest();
            request.setTrnname("CCLI");
            request.setTitle01("t");
            request.setAcctsid("1");
            request.setCardsid("2");
            request.setInfomsg("info");
            request.setErrmsg("err");
            request.setRow(1, new FirstListRow("S", "1", "2", "Y"));
            request.setRow(7, new StopperListRow("U", "*", "7", "8", "N"));

            CardListRequest normalised = request.normalised(CODEC);
            assertThat(normalised.getTrnname()).hasSize(4);
            assertThat(normalised.getTitle01()).hasSize(40);
            assertThat(normalised.getCurdate()).hasSize(8);
            assertThat(normalised.getPgmname()).hasSize(8);
            assertThat(normalised.getTitle02()).hasSize(40);
            assertThat(normalised.getCurtime()).hasSize(8);
            assertThat(normalised.getPageno()).hasSize(3);
            assertThat(normalised.getAcctsid()).hasSize(11).isEqualTo("1          ");
            assertThat(normalised.getCardsid()).hasSize(16);
            assertThat(normalised.getInfomsg()).hasSize(45);
            assertThat(normalised.getErrmsg()).hasSize(78);

            FirstListRow first = normalised.firstRow();
            assertThat(first.crdSel()).hasSize(1);
            assertThat(first.acctNo()).hasSize(11);
            assertThat(first.crdNum()).hasSize(16);
            assertThat(first.crdSts()).hasSize(1);

            StopperListRow last = normalised.lastRow();
            assertThat(last.crdSel()).isEqualTo("U");
            assertThat(last.crdStp()).isEqualTo("*");
            assertThat(last.acctNo()).hasSize(11);
            assertThat(last.crdNum()).hasSize(16);
            assertThat(last.crdSts()).isEqualTo("N");
        }

        @Test
        @DisplayName("an over-long value truncates on the right, as a COBOL PIC X MOVE does")
        void overLongValueTruncatesOnTheRight() {
            CardListRequest request = new CardListRequest();
            request.setTrnname("CCLIXXXX");
            assertThat(request.normalised(CODEC).getTrnname()).isEqualTo("CCLI");
        }

        @Test
        @DisplayName("normalised() rejects a null codec, and so does each row's normalised()")
        void normalisedRejectsNullCodec() {
            CardListRequest request = new CardListRequest();
            assertThatNullPointerException().isThrownBy(() -> request.normalised(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> FirstListRow.blank().normalised(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> StopperListRow.blank().normalised(null));
        }

        @Test
        @DisplayName("the card key renders and decodes its zoned account id through the codec")
        void cardKeyImages() {
            CardKey key = new CardKey("4111111111111111", 12_345_678_901L);
            assertThat(key.cardNumImage(CODEC)).isEqualTo("4111111111111111");
            assertThat(key.acctIdImage(CODEC)).isEqualTo("12345678901").hasSize(11);
            assertThat(new CardKey("4", 7L).acctIdImage(CODEC)).isEqualTo("00000000007");

            CardKey decoded = CardKey.decode(CODEC, "4111111111111111", "00000000007");
            assertThat(decoded.cardNum()).isEqualTo("4111111111111111");
            assertThat(decoded.acctId()).isEqualTo(7L);

            assertThatNullPointerException()
                    .isThrownBy(() -> CardKey.decode(null, "a", "0"));
            assertThatNullPointerException()
                    .isThrownBy(() -> CardKey.decode(CODEC, null, "0"));
            assertThatNullPointerException()
                    .isThrownBy(() -> CardKey.decode(CODEC, "a", null));
            assertThatNullPointerException().isThrownBy(() -> key.cardNumImage(null));
            assertThatNullPointerException().isThrownBy(() -> key.acctIdImage(null));
        }
    }

    // -------------------------------------------------------------------------------------------------
    // 11. THE JSON PROJECTION
    // -------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("The JSON projection carries exactly 45 payload members plus 4 carriers")
    class JsonProjection {

        private final ObjectMapper mapper = new ObjectMapper();

        @Test
        @DisplayName("the top level holds the 45 payload members and 4 carriers, and no rows array")
        void topLevelMembers() throws Exception {
            JsonNode json = mapper.valueToTree(new CardListRequest());
            List<String> names = new ArrayList<>();
            json.fieldNames().forEachRemaining(names::add);

            List<String> expected = new ArrayList<>(List.of(
                    "trnname", "title01", "curdate", "pgmname", "title02", "curtime", "pageno",
                    "acctsid", "cardsid",
                    "infomsg", "errmsg",
                    "pageCursor", "selectionFlags", "cardScreenState", "navigationContext"));
            expected.addAll(NUMBERED_ROW_MEMBERS);

            assertThat(names).containsExactlyInAnyOrderElementsOf(expected);
            assertThat(names)
                    .as("the rows travel as the 34 numbered members COCRDLI.CPY declares, not as a "
                            + "generic array under a name no DFHMDF carries")
                    .doesNotContain("rows");
        }

        @Test
        @DisplayName("the 34 numbered row members are exactly the COCRDLI.CPY:78-276 items")
        void theNumberedRowMembersAreTheCopybookItems() {
            JsonNode json = mapper.valueToTree(new CardListRequest());
            List<String> published = new ArrayList<>();
            json.fieldNames().forEachRemaining(published::add);

            assertThat(NUMBERED_ROW_MEMBERS).hasSize(34);
            assertThat(published).containsAll(NUMBERED_ROW_MEMBERS);
            assertThat(published)
                    .as("row 1 has no CRDSTP1: COCRDLI.CPY:78 is followed directly by :79")
                    .doesNotContain("crdstp1");
        }

        @Test
        @DisplayName("each numbered member binds inbound under its own copybook name")
        void eachNumberedMemberBindsInbound() throws Exception {
            String body = """
                    {"crdsel1":"S","acctno1":"00000000011","crdnum1":"4111111111111111",
                     "crdsts1":"Y","crdsel4":"U","crdstp4":"*","acctno4":"00000000044",
                     "crdnum4":"4111111111111144","crdsts4":"N","crdsts7":"Y"}""";

            CardListRequest bound = mapper.readValue(body, CardListRequest.class);

            assertThat(bound.getCrdsel1()).isEqualTo("S");
            assertThat(bound.getAcctno1()).isEqualTo("00000000011");
            assertThat(bound.getCrdnum1()).isEqualTo("4111111111111111");
            assertThat(bound.getCrdsts1()).isEqualTo("Y");
            assertThat(bound.getCrdsel4()).isEqualTo("U");
            assertThat(bound.getCrdstp4()).isEqualTo("*");
            assertThat(bound.getAcctno4()).isEqualTo("00000000044");
            assertThat(bound.getCrdnum4()).isEqualTo("4111111111111144");
            assertThat(bound.getCrdsts4()).isEqualTo("N");
            assertThat(bound.getCrdsts7()).isEqualTo("Y");
            assertThat(bound.firstRow()).isInstanceOf(FirstListRow.class);
            assertThat(bound.lastRow()).isInstanceOf(StopperListRow.class);
        }

        @Test
        @DisplayName("an unknown row member is refused rather than silently dropped")
        void anUnknownRowMemberIsRefused() {
            ObjectMapper strict = new ObjectMapper()
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

            assertThatExceptionOfType(JsonProcessingException.class)
                    .as("the deleted Map creator swallowed keys like this one; the typed accessors "
                            + "let the mapper see it")
                    .isThrownBy(() -> strict.readValue("{\"crdSell1\":\"S\"}",
                            CardListRequest.class));
            assertThatExceptionOfType(JsonProcessingException.class)
                    .isThrownBy(() -> strict.readValue("{\"crdstp1\":\"*\"}",
                            CardListRequest.class));
        }

        @Test
        @DisplayName("row 1 serialises 4 members and rows 2-7 serialise 5 each, totalling 34")
        void rowMembersSerialiseAsymmetrically() {
            JsonNode json = mapper.valueToTree(new CardListRequest());

            assertThat(json.has("crdsel1")).isTrue();
            assertThat(json.has("acctno1")).isTrue();
            assertThat(json.has("crdnum1")).isTrue();
            assertThat(json.has("crdsts1")).isTrue();
            assertThat(json.has("crdstp1"))
                    .as("the row-1 stopper is unnamed in the mapset - app/bms/COCRDLI.bms:145-146 - "
                            + "so it never reaches the symbolic map")
                    .isFalse();

            int total = 4;
            for (int row = 2; row <= CardListRequest.SCREEN_ROW_COUNT; row++) {
                for (String item : List.of("crdsel", "crdstp", "acctno", "crdnum", "crdsts")) {
                    assertThat(json.has(item + row)).as(item + row).isTrue();
                    total++;
                }
            }
            assertThat(total).isEqualTo(34);
        }

        @Test
        @DisplayName("the payload total is 9 + 34 + 2 = 45")
        void payloadTotalIsFortyFive() {
            JsonNode json = mapper.valueToTree(new CardListRequest());
            List<String> published = new ArrayList<>();
            json.fieldNames().forEachRemaining(published::add);

            int carriers = 4;
            assertThat(published.size() - carriers).isEqualTo(CardListRequest.FIELD_COUNT);
            assertThat(9 + NUMBERED_ROW_MEMBERS.size() + 2).isEqualTo(CardListRequest.FIELD_COUNT);
        }

        @Test
        @DisplayName("the metadata carriers and every derived predicate stay off the wire")
        void metadataIsNotSerialised() {
            CardListRequest request = new CardListRequest();
            request.putFieldMetadata(FieldMetadata.untouched("ACCTSID"));
            request.setSelectionErrorFlags(SelectionErrorFlags.none().withRowInError(1));
            JsonNode json = mapper.valueToTree(request);
            assertThat(json.has("screenRowTable")).isFalse();
            assertThat(json.has("selectionErrorFlags")).isFalse();
            assertThat(json.has("fieldMetadata")).isFalse();
            assertThat(json.has("enter")).isFalse();
            assertThat(json.has("reenter")).isFalse();
            assertThat(json.get("pageCursor").has("firstPage")).isFalse();
            assertThat(json.get("selectionFlags").has("detailWasRequested")).isFalse();
        }

        @Test
        @DisplayName("card numbers and account ids travel in the clear, exactly as the map declares")
        void noMaskingOfSensitiveFields() {
            CardListRequest request = new CardListRequest();
            request.setCardsid("4111111111111111");
            request.setAcctsid("00000000011");
            request.setRow(1, new FirstListRow("S", "00000000011", "4111111111111111", "Y"));
            JsonNode json = mapper.valueToTree(request);
            assertThat(json.get("cardsid").asText()).isEqualTo("4111111111111111");
            assertThat(json.get("acctsid").asText()).isEqualTo("00000000011");
            assertThat(json.get("crdnum1").asText()).isEqualTo("4111111111111111");
            assertThat(json.get("acctno1").asText()).isEqualTo("00000000011");
        }

        @Test
        @DisplayName("a row's shape is decided by its position, not by anything on the wire")
        void rowShapeIsDecidedByPosition() throws Exception {
            String body = """
                    {"crdsel1":"U","acctno1":"00000000001","crdnum1":"4111111111111111",
                     "crdsts1":"N","crdsel2":"S","crdstp2":"*","acctno2":"00000000002",
                     "crdnum2":"4111111111111122","crdsts2":"Y"}""";

            CardListRequest bound = mapper.readValue(body, CardListRequest.class);

            assertThat(bound.row(1))
                    .as("subscript 1 is a FirstListRow because the copybook declares no CRDSTP1, not "
                            + "because the body omitted one")
                    .isInstanceOf(FirstListRow.class);
            assertThat(bound.row(2)).isInstanceOf(StopperListRow.class);
            assertThat(bound.stopperOf(1)).isEmpty();
            assertThat(bound.stopperOf(2)).contains("*");
            assertThat(bound.getCrdsel1()).isEqualTo("U");
            assertThat(bound.getCrdsts1()).isEqualTo("N");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("row 1 has no stopper to read")
                    .isThrownBy(() -> bound.stopperRow(1))
                    .withMessageContaining("CRDSTP1");
        }

        @Test
        @DisplayName("an absent member leaves the row's blank value, and an explicit null is refused")
        void absentMembersLeaveTheBlankValue() throws Exception {
            CardListRequest sparse = mapper.readValue("{\"crdsel1\":\"S\"}", CardListRequest.class);

            assertThat(sparse.getCrdsel1()).isEqualTo("S");
            assertThat(sparse.getAcctno1())
                    .as("a field the operator never touched holds spaces, which is what the blank "
                            + "seven-row state already put there")
                    .isEqualTo(" ".repeat(CardListRequest.ACCTNO_LENGTH));
            assertThat(sparse.getCrdnum1())
                    .isEqualTo(" ".repeat(CardListRequest.CRDNUM_LENGTH));

            assertThatExceptionOfType(JsonProcessingException.class)
                    .as("there is no null in a COBOL record: a blank field holds spaces or LOW-VALUES")
                    .isThrownBy(() -> mapper.readValue("{\"acctno1\":null}", CardListRequest.class));
        }

        @Test
        @DisplayName("the payload round-trips through JSON unchanged")
        void roundTrip() throws Exception {
            CardListRequest original = new CardListRequest();
            original.setAcctsid("00000000011");
            original.setCardsid("4111111111111111");
            original.setPageno("002");
            original.setRow(1, new FirstListRow("S", "00000000011", "4111111111111111", "Y"));
            original.setRow(7, new StopperListRow("U", " ", "00000000077", "4111111111111177", "N"));
            original.setPageCursor(PageCursor.firstPage()
                    .withScreenNum(2)
                    .withNextPageExists()
                    .withLastCardKey(new CardKey("4111111111111177", 77L)));
            original.refreshSelectionFlagsFromRows();

            String encoded = mapper.writeValueAsString(original);
            CardListRequest decoded = mapper.readValue(encoded, CardListRequest.class);

            assertThat(decoded.getAcctsid()).isEqualTo("00000000011");
            assertThat(decoded.getCardsid()).isEqualTo("4111111111111111");
            assertThat(decoded.getPageno()).isEqualTo("002");
            assertThat(decoded.row(1)).isInstanceOf(FirstListRow.class);
            assertThat(decoded.row(7)).isInstanceOf(StopperListRow.class);
            assertThat(decoded.stopperOf(1)).isEmpty();
            assertThat(decoded.getPageCursor().screenNum()).isEqualTo(2);
            assertThat(decoded.getPageCursor().isNextPageExists()).isTrue();
            assertThat(decoded.getPageCursor().lastCardAcctId()).isEqualTo(77L);
            assertThat(decoded.getSelectionFlags().at(1)).isEqualTo('S');
            assertThat(decoded.payloadFieldCount()).isEqualTo(45);
        }
    }

    // -------------------------------------------------------------------------------------------------
    // 12. BEAN VALIDATION - declared widths only, nothing more
    // -------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Bean Validation constrains declared widths and nothing else")
    class BeanValidation {

        @Test
        @DisplayName("a blank request is valid")
        void blankRequestIsValid() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(new CardListRequest())).isEmpty();
            }
        }

        @Test
        @DisplayName("an over-long header or footer field violates its @Size(max = width)")
        void overLongFieldsViolateSize() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                Validator validator = factory.getValidator();

                CardListRequest tooLongPageno = new CardListRequest();
                tooLongPageno.setPageno("1234");
                assertThat(validator.validate(tooLongPageno))
                        .singleElement()
                        .satisfies(violation -> assertThat(violation.getPropertyPath().toString())
                                .isEqualTo("pageno"));

                CardListRequest tooLongInfomsg = new CardListRequest();
                tooLongInfomsg.setInfomsg("x".repeat(CardListRequest.INFOMSG_LENGTH + 1));
                assertThat(validator.validate(tooLongInfomsg)).hasSize(1);

                CardListRequest tooLongErrmsg = new CardListRequest();
                tooLongErrmsg.setErrmsg("x".repeat(CardListRequest.ERRMSG_LENGTH + 1));
                assertThat(validator.validate(tooLongErrmsg)).hasSize(1);

                CardListRequest atExactWidth = new CardListRequest();
                atExactWidth.setInfomsg("x".repeat(CardListRequest.INFOMSG_LENGTH));
                atExactWidth.setErrmsg("x".repeat(CardListRequest.ERRMSG_LENGTH));
                assertThat(validator.validate(atExactWidth)).isEmpty();
            }
        }

        @Test
        @DisplayName("@Valid cascades into the detail rows and the cursor")
        void validationCascades() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                Validator validator = factory.getValidator();

                CardListRequest badRow = new CardListRequest();
                badRow.setRow(3, new StopperListRow("SS", " ", "1", "2", "Y"));
                assertThat(validator.validate(badRow))
                        .singleElement()
                        .satisfies(violation -> assertThat(violation.getPropertyPath().toString())
                                .contains("rows").contains("crdSel"));

                CardListRequest badCursor = new CardListRequest();
                badCursor.setPageCursor(PageCursor.firstPage()
                        .withLastCardKey(new CardKey("4".repeat(17), 1L)));
                assertThat(validator.validate(badCursor))
                        .singleElement()
                        .satisfies(violation -> assertThat(violation.getPropertyPath().toString())
                                .contains("pageCursor").contains("cardNum"));
            }
        }

        @Test
        @DisplayName("no @NotBlank, @Pattern or numeric constraint pre-empts COCRDLIC's filter edits")
        void noConstraintsBeyondWidth() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                Validator validator = factory.getValidator();

                CardListRequest blankFilters = new CardListRequest();
                blankFilters.setAcctsid("");
                blankFilters.setCardsid("");
                assertThat(validator.validate(blankFilters))
                        .as("a blank filter is COCRDLIC's FLG-ACCTFILTER-BLANK case, not a violation")
                        .isEmpty();

                CardListRequest nonNumericFilters = new CardListRequest();
                nonNumericFilters.setAcctsid("ABCDEFGHIJK");
                nonNumericFilters.setCardsid("not-a-card-num-1");
                nonNumericFilters.setRow(1, new FirstListRow("Z", "???????????", "????????????????",
                        "?"));
                assertThat(validator.validate(nonNumericFilters))
                        .as("COCRDLIC decides which message a bad filter produces, and in what order")
                        .isEmpty();
            }

            assertThat(Stream.of(CardListRequest.class.getDeclaredFields())
                    .flatMap(field -> Stream.of(field.getAnnotations()))
                    .map(annotation -> annotation.annotationType().getSimpleName()))
                    .containsOnly("Size", "Valid", "JsonIgnore");
        }
    }

    // -------------------------------------------------------------------------------------------------
    // 13. VALUE SEMANTICS
    // -------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Value semantics")
    class ValueSemantics {

        @Test
        @DisplayName("two blank requests are equal and hash alike")
        void blanksAreEqual() {
            CardListRequest left = new CardListRequest();
            CardListRequest right = new CardListRequest();
            assertThat(left).isEqualTo(right).hasSameHashCodeAs(right);
            assertThat(left).isEqualTo(left);
            assertThat(left).isNotEqualTo("not a request");
            assertThat(left).isNotEqualTo(null);
        }

        @ParameterizedTest(name = "changing {0} breaks equality")
        @MethodSource("com.vsergeychik.carddemo.card.dto.CardListRequestTest#singleMemberMutators")
        @DisplayName("equality is sensitive to every one of the 19 members")
        void everyMemberParticipatesInEquality(String member, Consumer<CardListRequest> mutator) {
            CardListRequest baseline = new CardListRequest();
            CardListRequest mutated = new CardListRequest();
            mutator.accept(mutated);
            assertThat(mutated).as("member %s", member).isNotEqualTo(baseline);
            assertThat(mutated.hashCode()).as("member %s", member)
                    .isNotEqualTo(baseline.hashCode());
        }

        @Test
        @DisplayName("toString reports shape, not content")
        void toStringReportsShape() {
            CardListRequest request = new CardListRequest();
            request.setCardsid("4111111111111111");
            request.setPageCursor(PageCursor.firstPage().withScreenNum(3));

            String rendered = request.toString();
            assertThat(rendered)
                    .contains("CardListRequest[")
                    .contains("tranid=CCLI")
                    .contains("map=CCRDLIA")
                    .contains("payloadFields=45")
                    .contains("groupLength=797")
                    .contains("rows=4+6x5")
                    .contains("page=3");
            assertThat(rendered).doesNotContain("4111111111111111");
        }
    }

    // -------------------------------------------------------------------------------------------------
    // 10. BMS TRACEABILITY - every payload field back to its DFHMDF definition
    // -------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Every payload field traces to a DFHMDF definition and a symbolic-map PICTURE")
    class BmsTraceability {

        @ParameterizedTest(name = "{0} is LENGTH={1} at POS=({2},{3})")
        @MethodSource("com.vsergeychik.carddemo.card.dto.CardListRequestTest#bmsFieldCases")
        @DisplayName("all 45 fields carry the mapset LENGTH and fit inside the 24x80 map")
        void everyFieldCarriesItsDeclaredLength(String dfhmdfName, int length, int screenLine,
                                                int screenColumn) {
            assertThat(declaredWidthOf(dfhmdfName))
                    .as("%s: DFHMDF LENGTH=%d at POS=(%d,%d)", dfhmdfName, length, screenLine,
                            screenColumn)
                    .isEqualTo(length);

            assertThat(payloadValueOf(new CardListRequest().normalised(CODEC), dfhmdfName))
                    .as("%s renders at its declared width, not merely declares it", dfhmdfName)
                    .hasSize(length);

            assertThat(screenLine)
                    .as("%s sits on a line of the 24-line map", dfhmdfName)
                    .isBetween(1, SCREEN_LINES);
            assertThat(screenColumn + length - 1)
                    .as("%s starts at column %d and must end inside 80 columns", dfhmdfName,
                            screenColumn)
                    .isLessThanOrEqualTo(SCREEN_COLUMNS);
        }

        @Test
        @DisplayName("the oracle itself holds 45 entries whose widths sum to 470")
        void theTranscribedTableAgreesWithTheClassUnderTest() {
            int widthSum = 0;
            for (BmsField field : BMS_FIELDS) {
                widthSum += field.length();
            }
            assertThat(BMS_FIELDS).hasSize(45);
            assertThat(widthSum)
                    .as("470 data bytes: 138 header + 29 row 1 + 180 rows 2-7 + 123 footer")
                    .isEqualTo(470);
            assertThat(widthSum).isEqualTo(CardListRequest.PAYLOAD_DATA_LENGTH);
            assertThat(TIOAPFX_FILLER_BYTES + BMS_FIELDS.size() * SYMBOLIC_PREFIX_BYTES + widthSum)
                    .as("12 + 45 x 7 + 470")
                    .isEqualTo(797)
                    .isEqualTo(CardListRequest.GROUP_LENGTH);
        }

        @Test
        @DisplayName("PAGENO is 3 wide at POS=(4,76), so it ends at column 78")
        void pagenoGeometry() {
            BmsField pageno = fieldNamed("PAGENO");
            assertThat(pageno.length()).isEqualTo(3).isEqualTo(CardListRequest.PAGENO_LENGTH);
            assertThat(pageno.screenLine()).isEqualTo(4);
            assertThat(pageno.screenColumn()).isEqualTo(76);
            // The attribute byte occupies column 75, the caption 'Page ' runs 70-74, and the field
            // itself 76-78: the whole construct fits the 80-column line with room to spare.
            assertThat(pageno.screenColumn() + pageno.length() - 1).isEqualTo(78)
                    .isLessThan(SCREEN_COLUMNS);
        }

        @Test
        @DisplayName("this map declares no FKEYS field at all, unlike COCRDSL and COCRDUP")
        void thereIsNoFunctionKeyField() {
            // app/bms/COCRDLI.bms:335-339 renders the legend as an UNNAMED DFHMDF LENGTH=78
            // POS=(24,1) INITIAL='  F3=Exit F7=Backward  F8=Forward'. Unnamed means no symbolic-map
            // item, which means no payload member: the program can neither read nor rewrite it.
            // COCRDSL declares FKEYS at 75 bytes and COCRDUP at 21 and 18, so the field set does not
            // generalise across the three card maps and must not be carried between them.
            assertThat(BMS_FIELDS).extracting(BmsField::dfhmdfName).doesNotContain("FKEYS");
            assertThat(PAYLOAD_JSON_MEMBERS).doesNotContain("fkeys");
            assertThat(CardListRequest.class.getMethods())
                    .extracting(Method::getName)
                    .doesNotContain("getFkeys", "setFkeys", "getFkeys1", "getFkey");
        }

        @Test
        @DisplayName("the footer is 45/78 here where the sibling card maps are 40/80")
        void footerWidthsAreThisMapsOwn() {
            // AAP B5: a per-map difference is preserved, not regularised. COCRDSL and COCRDUP both
            // carry INFOMSG 40 and ERRMSG 80; COCRDLI carries 45 and 78. Neither set is "the right
            // one" - each map declares its own, and the parity differ compares byte counts.
            assertThat(fieldNamed("INFOMSG").length()).isEqualTo(45).isNotEqualTo(40);
            assertThat(fieldNamed("ERRMSG").length()).isEqualTo(78).isNotEqualTo(80);
            assertThat(fieldNamed("INFOMSG").screenLine()).isEqualTo(20);
            assertThat(fieldNamed("INFOMSG").screenColumn()).isEqualTo(19);
            assertThat(fieldNamed("ERRMSG").screenLine()).isEqualTo(23);
            assertThat(fieldNamed("ERRMSG").screenColumn()).isEqualTo(1);
            assertThat(fieldNamed("INFOMSG").length() + fieldNamed("ERRMSG").length())
                    .as("footer data bytes")
                    .isEqualTo(123)
                    .isEqualTo(CardListRequest.FOOTER_DATA_LENGTH);
        }

        @Test
        @DisplayName("the wire carries the 45 xxxI items and none of the xxxL, xxxF or xxxA items")
        void lengthFlagAndAttributeItemsAreNotPayload() throws JsonProcessingException {
            JsonNode json = new ObjectMapper().valueToTree(new CardListRequest().normalised(CODEC));

            // Presence: every one of the 45 names from the hand-written literal list must be there.
            for (String member : PAYLOAD_JSON_MEMBERS) {
                assertThat(json.has(member)).as("payload member %s", member).isTrue();
            }
            assertThat(PAYLOAD_JSON_MEMBERS).hasSize(45).doesNotHaveDuplicates();

            // Absence: xxxL is the length CICS reports, xxxF the flag byte and xxxA the REDEFINES of
            // xxxF used to set highlighting. All three are validation and highlight metadata, so none
            // may appear on the wire - in either the copybook's upper case or Jackson's lower case.
            for (BmsField field : BMS_FIELDS) {
                String label = field.dfhmdfName();
                for (String suffix : List.of("L", "F", "A")) {
                    String upper = label + suffix;
                    String lower = upper.toLowerCase(Locale.ROOT);
                    assertThat(json.has(upper)).as("metadata item %s must stay off the wire", upper)
                            .isFalse();
                    assertThat(json.has(lower)).as("metadata item %s must stay off the wire", lower)
                            .isFalse();
                }
            }

            // And the metadata that IS modelled lives in its own carrier, which is @JsonIgnore'd.
            assertThat(json.has("fieldMetadata")).isFalse();
        }

        @Test
        @DisplayName("FieldMetadata is where xxxL and xxxA go, keyed by the DFHMDF label")
        void metadataCarriesTheLengthAndAttributeItems() {
            CardListRequest request = new CardListRequest();
            request.putFieldMetadata(new FieldMetadata("ACCTSID", 11, 'A'));
            request.putFieldMetadata(FieldMetadata.untouched("CARDSID"));

            assertThat(request.fieldMetadataOf("ACCTSID")).contains(new FieldMetadata("ACCTSID", 11,
                    'A'));
            assertThat(request.fieldMetadataOf("CARDSID")).isPresent()
                    .get()
                    .extracting(FieldMetadata::isUntouched)
                    .isEqualTo(true);
            assertThat(request.fieldMetadataOf("PAGENO")).isEmpty();
        }

        private BmsField fieldNamed(String dfhmdfName) {
            return BMS_FIELDS.stream()
                    .filter(field -> field.dfhmdfName().equals(dfhmdfName))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(dfhmdfName + " is not a field "
                            + "of app/bms/COCRDLI.bms"));
        }
    }

    // -------------------------------------------------------------------------------------------------
    // 11. THE ASYMMETRY IN BYTES - 57 versus 65, and the absolute offsets it moves
    // -------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("The row-1 asymmetry expressed in bytes: row 1 spans 57, rows 2-7 span 65")
    class SymbolicMapOffsets {

        @Test
        @DisplayName("row 1 occupies 57 bytes and every other row 65 - the difference is one field")
        void rowSpansDifferByExactlyOneField() {
            // Row 1: 4 fields x 7 bytes of prefix + (1 + 11 + 16 + 1 = 29) data = 28 + 29 = 57.
            assertThat(rowSpanBytes(1))
                    .as("4 x 7 + 29")
                    .isEqualTo(57)
                    .isEqualTo(CardListRequest.FIRST_ROW_FIELD_COUNT
                            * CardListRequest.FIELD_OVERHEAD_LENGTH
                            + CardListRequest.FIRST_ROW_DATA_LENGTH);

            // Rows 2-7: 5 fields x 7 + (1 + 1 + 11 + 16 + 1 = 30) = 35 + 30 = 65.
            for (int row = 2; row <= CardListRequest.LAST_ROW_NUMBER; row++) {
                assertThat(rowSpanBytes(row))
                        .as("row %d: 5 x 7 + 30", row)
                        .isEqualTo(65)
                        .isEqualTo(CardListRequest.STOPPER_ROW_FIELD_COUNT
                                * CardListRequest.FIELD_OVERHEAD_LENGTH
                                + CardListRequest.STOPPER_ROW_DATA_LENGTH);
            }

            // 57 != 65 IS the asymmetry. A CRDSTP1 synthesised for symmetry would make row 1 65 too,
            // and this one inequality would fail before anything else did.
            assertThat(rowSpanBytes(1)).isNotEqualTo(rowSpanBytes(2));
            assertThat(65 - 57).as("the missing CRDSTP1: 7 bytes of prefix + 1 byte of data")
                    .isEqualTo(SYMBOLIC_PREFIX_BYTES + CardListRequest.CRDSTP_LENGTH);
        }

        @Test
        @DisplayName("the header ends at 213, so CRDSEL1I sits at 220")
        void headerEndsAtTwoHundredAndThirteen() {
            // 12 TIOAPFX + 9 x 7 prefix + 138 header data = 213, and CRDSEL1I follows its own prefix.
            assertThat(TIOAPFX_FILLER_BYTES
                    + CardListRequest.HEADER_FIELD_COUNT * CardListRequest.FIELD_OVERHEAD_LENGTH
                    + CardListRequest.HEADER_DATA_LENGTH)
                    .as("12 + 63 + 138")
                    .isEqualTo(213);
            assertThat(inputItemOffset("TRNNAME")).as("the first xxxI item, after 12 + 7")
                    .isEqualTo(19);
            assertThat(inputItemOffset("CRDSEL1")).as("213 + 7").isEqualTo(220);
        }

        @Test
        @DisplayName("the seven rows end at 660, and the footer runs 660 to 797")
        void rowBlockEndsAtSixHundredAndSixty() {
            int rowsStart = 213;
            int rowsEnd = rowsStart + rowSpanBytes(1);
            for (int row = 2; row <= CardListRequest.LAST_ROW_NUMBER; row++) {
                rowsEnd += rowSpanBytes(row);
            }
            assertThat(rowsEnd).as("213 + 57 + 6 x 65").isEqualTo(660);
            assertThat(inputItemOffset("INFOMSG")).as("660 + 7").isEqualTo(667);
            assertThat(inputItemOffset("ERRMSG")).as("667 + 45 + 7").isEqualTo(719);
            assertThat(inputItemOffset("ERRMSG") + CardListRequest.ERRMSG_LENGTH)
                    .as("719 + 78 closes the group")
                    .isEqualTo(797)
                    .isEqualTo(CardListRequest.GROUP_LENGTH);
        }

        @Test
        @DisplayName("the whole image reconciles as 12 + 201 + 57 + 390 + 137 = 797")
        void theWholeImageReconciles() {
            int header = CardListRequest.HEADER_FIELD_COUNT * CardListRequest.FIELD_OVERHEAD_LENGTH
                    + CardListRequest.HEADER_DATA_LENGTH;
            int stopperRows = 6 * rowSpanBytes(2);
            int footer = CardListRequest.FOOTER_FIELD_COUNT * CardListRequest.FIELD_OVERHEAD_LENGTH
                    + CardListRequest.FOOTER_DATA_LENGTH;

            assertThat(header).as("9 x 7 + 138").isEqualTo(201);
            assertThat(stopperRows).as("6 x 65").isEqualTo(390);
            assertThat(footer).as("2 x 7 + 123").isEqualTo(137);
            assertThat(TIOAPFX_FILLER_BYTES + header + rowSpanBytes(1) + stopperRows + footer)
                    .isEqualTo(797)
                    .isEqualTo(CardListRequest.GROUP_LENGTH);
        }

        @Test
        @DisplayName("row 1's four items are contiguous from 213 to 270, with nothing between them")
        void rowOneIsContiguous() {
            assertThat(rowFieldNames(1)).containsExactly("CRDSEL1", "ACCTNO1", "CRDNUM1", "CRDSTS1");
            assertThat(inputItemOffset("CRDSEL1")).isEqualTo(220);
            assertThat(inputItemOffset("ACCTNO1")).as("220 + 1 + 7").isEqualTo(228);
            assertThat(inputItemOffset("CRDNUM1")).as("228 + 11 + 7").isEqualTo(246);
            assertThat(inputItemOffset("CRDSTS1")).as("246 + 16 + 7").isEqualTo(269);
            assertThat(inputItemOffset("CRDSTS1") + CardListRequest.CRDSTS_LENGTH)
                    .as("row 1 closes at 270, that is 213 + 57")
                    .isEqualTo(270);
        }

        @ParameterizedTest(name = "row {0} orders its items CRDSEL, CRDSTP, ACCTNO, CRDNUM, CRDSTS")
        @ValueSource(ints = {2, 3, 4, 5, 6, 7})
        @DisplayName("CRDSTPn is the SECOND item of its row, proven by ascending byte offset")
        void stopperIsSecondByOffset(int row) {
            assertThat(rowFieldNames(row)).containsExactly("CRDSEL" + row, "CRDSTP" + row,
                    "ACCTNO" + row, "CRDNUM" + row, "CRDSTS" + row);

            int select = inputItemOffset("CRDSEL" + row);
            int stopper = inputItemOffset("CRDSTP" + row);
            int account = inputItemOffset("ACCTNO" + row);
            int card = inputItemOffset("CRDNUM" + row);
            int status = inputItemOffset("CRDSTS" + row);

            // Presence alone would be satisfied by a CRDSTPn appended at the end of the row. The
            // offsets are what pin it between CRDSELn and ACCTNOn, where COCRDLI.CPY declares it.
            assertThat(stopper).isGreaterThan(select).isLessThan(account);
            assertThat(select).isLessThan(stopper);
            assertThat(account).isLessThan(card);
            assertThat(card).isLessThan(status);
            assertThat(stopper).as("CRDSEL is 1 byte wide, so the next prefix starts 8 bytes on")
                    .isEqualTo(select + CardListRequest.CRDSEL_LENGTH + SYMBOLIC_PREFIX_BYTES);
        }

        @ParameterizedTest(name = "row {0} starts at byte {1}")
        @CsvSource({"1,213", "2,270", "3,335", "4,400", "5,465", "6,530", "7,595"})
        @DisplayName("row starts step by 57 once and then by 65 six times")
        void rowStartOffsets(int row, int expectedStart) {
            int start = 213;
            for (int earlier = 1; earlier < row; earlier++) {
                start += rowSpanBytes(earlier);
            }
            assertThat(start).isEqualTo(expectedStart);
        }

        @Test
        @DisplayName("row 1 has no name-labelled field at column 14, though rows 2-7 all do")
        void columnFourteenIsEmptyOnlyOnRowOne() {
            // Two independent confirmations of the same fact, both preserved deliberately (AAP B5):
            //   1. app/cpy-bms/COCRDLI.CPY:78 is `02 CRDSEL1I PIC X(1).` and :79 is
            //      `02 ACCTNO1L COMP PIC S9(4).` - the CRDSTP1 quintuple simply is not declared.
            //   2. app/bms/COCRDLI.bms places a name-labelled field at column 14 on screen lines 12
            //      through 17 and none on line 11. Line 11 does carry an unnamed `DFHMDF LENGTH=0,
            //      POS=(11,14)` at :145-146 - an attribute terminator, which generates no symbolic-map
            //      item and is therefore not payload.
            // The output group agrees: it holds CRDSEL1{C,P,H,V,O}, ACCTNO1{...}, CRDNUM1{...} and
            // CRDSTS1{...} with no CRDSTP1 quintuple.
            assertThat(BMS_FIELDS.stream()
                    .filter(field -> field.screenLine() == screenLineOfRow(1))
                    .filter(field -> field.screenColumn() == 14)
                    .toList())
                    .as("screen line 11, column 14 carries no name-labelled field")
                    .isEmpty();

            for (int row = 2; row <= CardListRequest.LAST_ROW_NUMBER; row++) {
                int line = screenLineOfRow(row);
                assertThat(BMS_FIELDS.stream()
                        .filter(field -> field.screenLine() == line)
                        .filter(field -> field.screenColumn() == 14)
                        .map(BmsField::dfhmdfName)
                        .toList())
                        .as("screen line %d, column 14", line)
                        .containsExactly("CRDSTP" + row);
            }
        }
    }

    // -------------------------------------------------------------------------------------------------
    // 12. REDEFINES - the flat 196-byte view and the 7-element view are ONE span
    // -------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("WS-ALL-ROWS and WS-SCREEN-ROWS are two views over one 196-byte span")
    class RedefinesOverOneSpan {

        @Test
        @DisplayName("one element is 28 bytes: 11 + 16 + 1, and seven of them are 196")
        void elementAndTableWidths() {
            assertThat(CardListRequest.SCREEN_ROW_ACCTNO_LENGTH).isEqualTo(11);
            assertThat(CardListRequest.SCREEN_ROW_CARD_NUM_LENGTH).isEqualTo(16);
            assertThat(CardListRequest.SCREEN_ROW_CARD_STATUS_LENGTH).isEqualTo(1);
            assertThat(11 + 16 + 1).isEqualTo(28).isEqualTo(CardListRequest.SCREEN_ROW_LENGTH);
            assertThat(28 * 7).as("the source says so itself at COCRDLIC:250")
                    .isEqualTo(196)
                    .isEqualTo(CardListRequest.SCREEN_DATA_LENGTH);
        }

        @Test
        @DisplayName("the element carries acctNo, cardNum and status only - no select flag, no CRDSTP")
        void theElementCarriesThreeMembers() {
            // WS-EACH-CARD is exactly the three record fields the browse read. The row action code
            // lives in its own X(7) table (COCRDLIC:72-82) and CRDSTPn is a screen-only stopper, which
            // is why there are two seven-element structures here rather than one of wider rows.
            assertThat(ScreenRow.class.getRecordComponents())
                    .extracting(RecordComponent::getName)
                    .containsExactly("acctNo", "cardNum", "cardStatus");
            assertThat(ScreenRow.class.getRecordComponents()).hasSize(3);
            assertThat(28 * 7 + CardListRequest.SELECT_FLAGS_LENGTH)
                    .as("196 record bytes plus a separate 7-byte action table")
                    .isEqualTo(203);
        }

        @Test
        @DisplayName("structured writes appear verbatim in the flat 196-byte image")
        void structuredWritesAreVisibleInTheFlatImage() {
            ScreenRowTable table = ScreenRowTable.lowValues()
                    .withRow(1, new ScreenRow("00000000001", "4111111111111111", "Y"))
                    .withRow(7, new ScreenRow("00000000007", "4111111111111777", "N"));

            byte[] flat = renderFlat(table);
            assertThat(flat).hasSize(196);

            // Read the flat span back by absolute offset. This is a byte reinterpretation of one
            // storage area, exactly as REDEFINES is - never a parse, so no Integer.parseInt appears.
            FixedWidthRecord view = FixedWidthRecord.copyOf(flat, CardListRequest.SCREEN_DATA_LENGTH,
                    StandardCharsets.US_ASCII);
            assertThat(view.readString(ScreenRowTable.rowOffset(1),
                    CardListRequest.SCREEN_ROW_LENGTH))
                    .isEqualTo("00000000001" + "4111111111111111" + "Y");
            assertThat(view.readString(ScreenRowTable.rowOffset(7),
                    CardListRequest.SCREEN_ROW_LENGTH))
                    .isEqualTo("00000000007" + "4111111111111777" + "N");
        }

        @Test
        @DisplayName("flat writes are visible through the structured view, byte for byte")
        void flatWritesAreVisibleThroughTheStructuredView() {
            FixedWidthRecord span = new FixedWidthRecord(CardListRequest.SCREEN_DATA_LENGTH,
                    StandardCharsets.US_ASCII);
            span.fill(0, CardListRequest.SCREEN_DATA_LENGTH, (byte) '.');
            span.writeString(ScreenRowTable.rowOffset(1), CardListRequest.SCREEN_ROW_LENGTH,
                    "00000000001" + "4111111111111111" + "Y");
            span.writeString(ScreenRowTable.rowOffset(7), CardListRequest.SCREEN_ROW_LENGTH,
                    "00000000007" + "4111111111111777" + "N");

            ScreenRowTable rebuilt = readFlat(span.toByteArray());

            assertThat(rebuilt.row(1)).isEqualTo(new ScreenRow("00000000001", "4111111111111111",
                    "Y"));
            assertThat(rebuilt.row(7)).isEqualTo(new ScreenRow("00000000007", "4111111111111777",
                    "N"));
            // And the round trip closes: structured -> flat -> structured -> flat is stable.
            assertThat(renderFlat(rebuilt)).isEqualTo(span.toByteArray());
        }

        @Test
        @DisplayName("writing row 1 and row 7 leaves rows 2 through 6 untouched - no bleed")
        void neighbouringSpansDoNotBleed() {
            FixedWidthRecord span = new FixedWidthRecord(CardListRequest.SCREEN_DATA_LENGTH,
                    StandardCharsets.US_ASCII);
            span.fill(0, CardListRequest.SCREEN_DATA_LENGTH, (byte) '.');
            span.writeString(ScreenRowTable.rowOffset(1), CardListRequest.SCREEN_ROW_LENGTH,
                    "11111111111" + "4111111111111111" + "Y");
            span.writeString(ScreenRowTable.rowOffset(7), CardListRequest.SCREEN_ROW_LENGTH,
                    "77777777777" + "4111111111111777" + "N");

            assertThat(span.readString(0, 28)).isEqualTo("11111111111" + "4111111111111111" + "Y");
            assertThat(span.readString(168, 28)).isEqualTo("77777777777" + "4111111111111777" + "N");
            for (int row = 2; row <= 6; row++) {
                assertThat(span.readString(ScreenRowTable.rowOffset(row),
                        CardListRequest.SCREEN_ROW_LENGTH))
                        .as("row %d must be exactly as it was left", row)
                        .isEqualTo(".".repeat(CardListRequest.SCREEN_ROW_LENGTH));
            }
        }

        @Test
        @DisplayName("subscript 1 is offset 0 and subscript 7 is offset 168, ending at 196")
        void firstAndLastElementOffsets() {
            // COCRDLIC:972 moves CRDSEL1I into WS-EDIT-SELECT(1) and :738 moves WS-EDIT-SELECT(7)
            // into CRDSEL7O, so subscript 1 is the first screen row and subscript 7 the last.
            assertThat(ScreenRowTable.rowOffset(1)).isZero();
            assertThat(CardListRequest.javaIndexOf(1)).isZero();
            assertThat(ScreenRowTable.rowOffset(7)).as("6 x 28").isEqualTo(168);
            assertThat(CardListRequest.javaIndexOf(7)).isEqualTo(6);
            assertThat(ScreenRowTable.rowOffset(7) + CardListRequest.SCREEN_ROW_LENGTH)
                    .as("the last element closes the table")
                    .isEqualTo(196);
            assertThat(CardListRequest.cobolRowNumberOf(0)).isEqualTo(1);
            assertThat(CardListRequest.cobolRowNumberOf(6)).isEqualTo(7);
        }

        @Test
        @DisplayName("there is no eighth element and no element before the first")
        void thereIsNoEighthElement() {
            ScreenRowTable table = ScreenRowTable.lowValues();
            assertThat(table.rows()).hasSize(7);
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> table.row(8));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> table.row(0));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> ScreenRowTable.rowOffset(8));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> CardListRequest.cobolRowNumberOf(-1));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> CardListRequest.cobolRowNumberOf(7));
        }

        /**
         * Renders the structured view into the flat 196-byte span, one element at a time at the offset
         * {@link ScreenRowTable#rowOffset(int)} names.
         *
         * @param table the seven-element view
         * @return the 196 bytes {@code WS-ALL-ROWS} would hold
         */
        private byte[] renderFlat(ScreenRowTable table) {
            FixedWidthRecord span = new FixedWidthRecord(CardListRequest.SCREEN_DATA_LENGTH,
                    StandardCharsets.US_ASCII);
            for (int row = CardListRequest.FIRST_ROW_NUMBER; row <= CardListRequest.LAST_ROW_NUMBER;
                    row++) {
                ScreenRow element = table.row(row);
                int base = ScreenRowTable.rowOffset(row);
                span.writeString(base, CardListRequest.SCREEN_ROW_ACCTNO_LENGTH,
                        CODEC.movePicX(element.acctNo(), CardListRequest.SCREEN_ROW_ACCTNO_LENGTH));
                span.writeString(base + CardListRequest.SCREEN_ROW_ACCTNO_LENGTH,
                        CardListRequest.SCREEN_ROW_CARD_NUM_LENGTH,
                        CODEC.movePicX(element.cardNum(), CardListRequest.SCREEN_ROW_CARD_NUM_LENGTH));
                span.writeString(base + CardListRequest.SCREEN_ROW_ACCTNO_LENGTH
                                + CardListRequest.SCREEN_ROW_CARD_NUM_LENGTH,
                        CardListRequest.SCREEN_ROW_CARD_STATUS_LENGTH,
                        CODEC.movePicX(element.cardStatus(),
                                CardListRequest.SCREEN_ROW_CARD_STATUS_LENGTH));
            }
            return span.toByteArray();
        }

        /**
         * Reinterprets a flat 196-byte span as the seven-element view, slicing at the same offsets.
         *
         * @param flat the 196 bytes
         * @return the structured view of those same bytes
         */
        private ScreenRowTable readFlat(byte[] flat) {
            FixedWidthRecord span = FixedWidthRecord.copyOf(flat,
                    CardListRequest.SCREEN_DATA_LENGTH, StandardCharsets.US_ASCII);
            List<ScreenRow> elements = new ArrayList<>(CardListRequest.SCREEN_ROW_COUNT);
            for (int row = CardListRequest.FIRST_ROW_NUMBER; row <= CardListRequest.LAST_ROW_NUMBER;
                    row++) {
                int base = ScreenRowTable.rowOffset(row);
                elements.add(new ScreenRow(
                        span.readString(base, CardListRequest.SCREEN_ROW_ACCTNO_LENGTH),
                        span.readString(base + CardListRequest.SCREEN_ROW_ACCTNO_LENGTH,
                                CardListRequest.SCREEN_ROW_CARD_NUM_LENGTH),
                        span.readString(base + CardListRequest.SCREEN_ROW_ACCTNO_LENGTH
                                        + CardListRequest.SCREEN_ROW_CARD_NUM_LENGTH,
                                CardListRequest.SCREEN_ROW_CARD_STATUS_LENGTH)));
            }
            return new ScreenRowTable(elements);
        }
    }

    // -------------------------------------------------------------------------------------------------
    // 13. MIXED PICTURE KINDS INSIDE ONE 27-BYTE GROUP, AND TWO DIFFERENT INITIAL STATES
    // -------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("PIC X and PIC 9 sit side by side in the key, and the two flag tables start differently")
    class MixedPictureKinds {

        @Test
        @DisplayName("the key is 27 = X(16) + 9(11), and the two halves pad in opposite directions")
        void oneGroupTwoPictureKinds() {
            // 15  WS-CA-LAST-CARD-NUM      PIC X(16).   alphanumeric -> pad on the RIGHT with spaces
            // 15  WS-CA-LAST-CARD-ACCT-ID  PIC 9(11).   numeric      -> pad on the LEFT with zeros
            assertThat(CardListRequest.CURSOR_CARD_NUM_LENGTH
                    + CardListRequest.CURSOR_ACCT_ID_LENGTH)
                    .as("16 + 11")
                    .isEqualTo(27)
                    .isEqualTo(CardListRequest.CARD_KEY_LENGTH);

            assertThat(CODEC.movePicX("41111", CardListRequest.CURSOR_CARD_NUM_LENGTH))
                    .as("PIC X pads after the value")
                    .isEqualTo("41111           ")
                    .hasSize(16);
            assertThat(CODEC.movePic9("11", CardListRequest.CURSOR_ACCT_ID_LENGTH))
                    .as("PIC 9 pads before the value")
                    .isEqualTo("00000000011")
                    .hasSize(11);

            // The direction is chosen per PICTURE and never guessed: the two calls above are different
            // methods on purpose. Using the alphanumeric rule on the account id would produce
            // "11         ", which reads as 11000000000 to anything that strips the pad.
            assertThat(CODEC.movePicX("11", CardListRequest.CURSOR_ACCT_ID_LENGTH))
                    .isNotEqualTo(CODEC.movePic9("11", CardListRequest.CURSOR_ACCT_ID_LENGTH));
        }

        @Test
        @DisplayName("CardKey renders each half through the matching move and decodes to the image")
        void keyImagesUseTheMatchingMove() {
            CardKey key = new CardKey("4111111111111111", 12_345_678_901L);
            assertThat(key.cardNumImage(CODEC)).isEqualTo("4111111111111111").hasSize(16);
            assertThat(key.acctIdImage(CODEC)).isEqualTo("12345678901").hasSize(11);
            // A key already at its declared widths survives the round trip untouched.
            assertThat(CardKey.decode(CODEC, key.cardNumImage(CODEC), key.acctIdImage(CODEC)))
                    .isEqualTo(key);

            CardKey shortKey = new CardKey("41111", 11L);
            assertThat(shortKey.cardNumImage(CODEC)).isEqualTo("41111" + " ".repeat(11)).hasSize(16);
            assertThat(shortKey.acctIdImage(CODEC)).isEqualTo("00000000011").hasSize(11);
            assertThat(shortKey.cardNumImage(CODEC).length()
                    + shortKey.acctIdImage(CODEC).length()).isEqualTo(27);

            // A short sending value comes back at its declared width, because that is what the
            // storage holds: the group is 27 bytes whether or not the operator filled them. The
            // numeric half recovers the same VALUE (11), while the alphanumeric half recovers the same
            // BYTES (five digits then eleven spaces) - the asymmetry made visible.
            CardKey decoded = CardKey.decode(CODEC, shortKey.cardNumImage(CODEC),
                    shortKey.acctIdImage(CODEC));
            assertThat(decoded).isEqualTo(new CardKey("41111" + " ".repeat(11), 11L));
            assertThat(decoded.acctId()).isEqualTo(shortKey.acctId());
            assertThat(decoded.cardNum()).isNotEqualTo(shortKey.cardNum())
                    .isEqualTo(shortKey.cardNumImage(CODEC));
            // Re-encoding the decoded key reproduces the same 27 bytes: the round trip is stable.
            assertThat(decoded.cardNumImage(CODEC)).isEqualTo(shortKey.cardNumImage(CODEC));
            assertThat(decoded.acctIdImage(CODEC)).isEqualTo(shortKey.acctIdImage(CODEC));
        }

        @Test
        @DisplayName("CA-FIRST-PAGE is VALUE 1 only: false at 0 and false at 2")
        void firstPageIsExactlyOne() {
            assertThat(PageCursor.initialised().withScreenNum(1).isFirstPage()).isTrue();
            assertThat(PageCursor.initialised().withScreenNum(0).isFirstPage()).isFalse();
            assertThat(PageCursor.initialised().withScreenNum(2).isFirstPage()).isFalse();
            assertThat(PageCursor.initialised().withScreenNum(9).isFirstPage()).isFalse();
        }

        @Test
        @DisplayName("a space satisfies neither next-page 88-level, and LOW-VALUES is not a space")
        void spaceIsNeitherNextPageState() {
            // 88 CA-NEXT-PAGE-NOT-EXISTS VALUE LOW-VALUES.   88 CA-NEXT-PAGE-EXISTS VALUE 'Y'.
            // There is no catch-all, so a space - a third, distinct byte - satisfies neither.
            PageCursor spaced = PageCursor.firstPage().withNextPageNotExists();
            assertThat(spaced.isNextPageNotExists()).isTrue();
            assertThat(spaced.nextPageInd()).isEqualTo(LOW).isNotEqualTo(" ");
            assertThat(LOW.charAt(0)).isEqualTo(CardListRequest.LOW_VALUE)
                    .isNotEqualTo(CardListRequest.SPACE);
        }

        @Test
        @DisplayName("the selection table starts at LOW-VALUES; the error table has no VALUE clause")
        void theTwoTablesDoNotStartAlike() {
            // COCRDLIC:72-73  05 WS-EDIT-SELECT-FLAGS       PIC X(7) VALUE LOW-VALUES.  <- has one
            // COCRDLIC:83     05 WS-EDIT-SELECT-ERROR-FLAGS PIC X(7).                   <- has none
            // Two adjacent, near-identical declarations with different initial states. The Java types
            // model each state explicitly rather than assuming the tables behave alike.
            SelectionFlags selection = SelectionFlags.lowValues();
            SelectionErrorFlags errors = SelectionErrorFlags.none();

            assertThat(selection.flags()).isEqualTo(LOW.repeat(7)).hasSize(7);
            assertThat(selection.flags()).isNotEqualTo(" ".repeat(7));
            for (int row = CardListRequest.FIRST_ROW_NUMBER;
                    row <= CardListRequest.LAST_ROW_NUMBER; row++) {
                assertThat(selection.at(row)).isEqualTo(CardListRequest.LOW_VALUE);
                assertThat(selection.isSelectBlank(row)).isTrue();
                assertThat(selection.isSelectOk(row)).isFalse();
            }

            // The error table's own initial state, asserted for what it is rather than assumed equal
            // to the selection table's.
            assertThat(errors.flags()).hasSize(7);
            for (int row = CardListRequest.FIRST_ROW_NUMBER;
                    row <= CardListRequest.LAST_ROW_NUMBER; row++) {
                assertThat(errors.isRowSelectError(row))
                        .as("no row is in error before any edit runs")
                        .isFalse();
            }
            assertThat(errors.flags())
                    .as("the two tables are not assumed to share an initial image")
                    .isNotEqualTo(selection.flags());
        }

        @Test
        @DisplayName("each element carries its own vector: row 1 'S' and row 7 'U', with no cross-talk")
        void selectionPredicatesArePerElement() {
            // COCRDLIC:972-978 loads WS-EDIT-SELECT(1) through (7) from seven separate screen fields,
            // and :683-738 reads them back one at a time, so every 88-level is evaluated against ONE
            // element. Setting two of them must not disturb each other or the five in between.
            SelectionFlags flags = SelectionFlags.lowValues()
                    .withSelection(1, CardListRequest.SELECT_VIEW)
                    .withSelection(7, CardListRequest.SELECT_UPDATE);

            assertThat(flags.at(1)).isEqualTo('S');
            assertThat(flags.isSelectOk(1)).isTrue();
            assertThat(flags.isViewRequestedOn(1)).isTrue();
            assertThat(flags.isUpdateRequestedOn(1)).isFalse();
            assertThat(flags.isSelectBlank(1)).isFalse();

            assertThat(flags.at(7)).isEqualTo('U');
            assertThat(flags.isSelectOk(7)).isTrue();
            assertThat(flags.isUpdateRequestedOn(7)).isTrue();
            assertThat(flags.isViewRequestedOn(7)).isFalse();
            assertThat(flags.isSelectBlank(7)).isFalse();

            for (int row = 2; row <= 6; row++) {
                assertThat(flags.at(row)).as("row %d", row).isEqualTo(CardListRequest.LOW_VALUE);
                assertThat(flags.isSelectBlank(row)).as("row %d", row).isTrue();
                assertThat(flags.isSelectOk(row)).as("row %d", row).isFalse();
                assertThat(flags.isViewRequestedOn(row)).as("row %d", row).isFalse();
                assertThat(flags.isUpdateRequestedOn(row)).as("row %d", row).isFalse();
            }
            assertThat(flags.selectedRowCount()).as("two rows were selected").isEqualTo(2);
            assertThat(flags.flags()).hasSize(7);
        }

        @Test
        @DisplayName("SELECT-OK membership is exactly {'S','U'} and SELECT-BLANK exactly {space, LOW}")
        void conditionNameMembershipIsExhaustive() {
            // 88 SELECT-OK   VALUES 'S', 'U'.          -> two members, not three: 's' is not 'S'.
            // 88 SELECT-BLANK VALUES ' ', LOW-VALUES.  -> two DIFFERENT bytes sharing one predicate,
            //                                             which is what COCRDLIC:757 relies on.
            List<Character> selectOk = new ArrayList<>();
            List<Character> selectBlank = new ArrayList<>();
            for (char candidate = 0; candidate < 128; candidate++) {
                SelectionFlags flags = SelectionFlags.lowValues().withSelection(1, candidate);
                if (flags.isSelectOk(1)) {
                    selectOk.add(candidate);
                }
                if (flags.isSelectBlank(1)) {
                    selectBlank.add(candidate);
                }
            }
            assertThat(selectOk).containsExactly('S', 'U').hasSize(2);
            assertThat(selectBlank).containsExactly(CardListRequest.LOW_VALUE, ' ').hasSize(2);
            assertThat(CardListRequest.LOW_VALUE).isNotEqualTo(' ');
            assertThat(selectOk).doesNotContain('s', 'u', 'X', ' ', CardListRequest.LOW_VALUE);
        }

        @ParameterizedTest(name = "{0} at rows 1 and 7 -> WS-ROW-SELECT-ERROR {2}")
        @MethodSource("com.vsergeychik.carddemo.card.dto.CardListRequestTest#rowSelectErrorCases")
        @DisplayName("WS-ROW-SELECT-ERROR is VALUE '1' and nothing else, at the first and last row")
        void errorConditionIsExactlyOne(String description, String value, boolean expected) {
            String flags = value + " ".repeat(5) + value;
            assertThat(flags).as("the table stays PIC X(7)").hasSize(7);

            SelectionErrorFlags errors = new SelectionErrorFlags(flags);
            assertThat(errors.isRowSelectError(1)).as("row 1 holds %s", description)
                    .isEqualTo(expected);
            assertThat(errors.isRowSelectError(7)).as("row 7 holds %s", description)
                    .isEqualTo(expected);
            assertThat(errors.isRowSelectError(4)).as("row 4 holds a space and was never set")
                    .isFalse();
        }

        @Test
        @DisplayName("setting an error flag changes no declared width and no offset")
        void anErrorFlagIsHighlightMetadataOnly() {
            // The resulting highlight bytes - DFHRED on CRDSELnC and a '*' in CRDSELnO - belong to
            // CardListResponseTest. Note that CSSETATY has NO card consumer at all: its only consumer
            // is COACTUPC. COCRDLIC highlights inline through DFHRED at nine sites, so what is
            // asserted here is the outcome contract, not a shared mechanism.
            CardListRequest request = new CardListRequest();
            request.setSelectionErrorFlags(SelectionErrorFlags.none().withRowInError(1)
                    .withRowInError(7));

            assertThat(request.getSelectionErrorFlags().isRowSelectError(1)).isTrue();
            assertThat(request.getSelectionErrorFlags().isRowSelectError(7)).isTrue();
            assertThat(request.getSelectionErrorFlags().isRowSelectError(2)).isFalse();
            assertThat(request.getSelectionErrorFlags().declaredLength()).isEqualTo(7);
            assertThat(request.payloadFieldCount()).isEqualTo(45);
            assertThat(CardListRequest.GROUP_LENGTH).isEqualTo(797);
            assertThat(CardListRequest.PROG_COMMAREA_LENGTH).isEqualTo(254);
            assertThat(CardListRequest.SELECT_FLAGS_LENGTH).isEqualTo(7);
            // No CARDDEMO-COMMAREA travelled with this request, so EIBCALEN is 0 - the false side of
            // the branch that COCRDLIC:315 tests.
            assertThat(request.commareaLength()).isZero();
            assertThat(request.hasNavigationContext()).isFalse();
        }
    }

    // -------------------------------------------------------------------------------------------------
    // 14. EVERYTHING TRAVELS IN THE PAYLOAD, AND TWO REQUESTS SHARE NOTHING
    // -------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Statelessness: the whole conversation rides in the payload")
    class PayloadCarriesTheConversation {

        @Test
        @DisplayName("cursor, row table, both flag tables, screen state and commarea are all members")
        void everyPieceOfStateIsAMember() {
            CardListRequest request = new CardListRequest();
            request.setNavigationContext(NavigationContext.empty());

            assertThat(request.getPageCursor().declaredLength()).isEqualTo(58);
            assertThat(request.getScreenRowTable().declaredLength()).isEqualTo(196);
            assertThat(request.getSelectionFlags().declaredLength()).isEqualTo(7);
            assertThat(request.getSelectionErrorFlags().declaredLength()).isEqualTo(7);
            assertThat(request.getCardScreenState()).isNotNull();
            assertThat(request.hasNavigationContext()).isTrue();

            // Two different communication areas, and they must not be conflated.
            //   commareaLength() is EIBCALEN - the 160-byte CARDDEMO-COMMAREA of COCOM01Y that arrives
            //   from the previous transaction and is what COCRDLIC:315 tests.
            //   PROG_COMMAREA_LENGTH is 254 - this program's OWN WS-THIS-PROGCOMMAREA, the level-05
            //   sibling sum of COCRDLIC:229-260, which it stores into CDEMO-CARD-DEMO storage and
            //   reads back at :330.
            assertThat(request.commareaLength())
                    .as("EIBCALEN: the 160-byte CARDDEMO-COMMAREA")
                    .isEqualTo(160)
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH);
            assertThat(CardListRequest.PROG_COMMAREA_LENGTH)
                    .as("58 + 196, the level-05 sibling sum of COCRDLIC:229-260")
                    .isEqualTo(254)
                    .isEqualTo(CardListRequest.CURSOR_LENGTH + CardListRequest.SCREEN_DATA_LENGTH);
            assertThat(CardListRequest.PROG_COMMAREA_LENGTH)
                    .isNotEqualTo(NavigationContext.COMMAREA_LENGTH);
        }

        @Test
        @DisplayName("CARDDEMO-COMMAREA is 160 bytes and ENTER/REENTER are both reachable")
        void navigationContextGeometryAndContext() {
            assertThat(NavigationContext.COMMAREA_LENGTH)
                    .as("COCOM01Y CARDDEMO-COMMAREA")
                    .isEqualTo(160);

            CardListRequest onEntry = new CardListRequest();
            onEntry.setNavigationContext(NavigationContext.empty().withPgmEnter());
            assertThat(onEntry.getPgmContext()).isZero();
            assertThat(onEntry.isEnter()).isTrue();
            assertThat(onEntry.isReenter()).isFalse();

            CardListRequest onReentry = new CardListRequest();
            onReentry.setNavigationContext(NavigationContext.empty().withPgmReenter());
            assertThat(onReentry.getPgmContext()).isEqualTo(1);
            assertThat(onReentry.isReenter()).isTrue();
            assertThat(onReentry.isEnter()).isFalse();
        }

        @Test
        @DisplayName("two requests are wholly independent, rows included")
        void twoRequestsShareNothing() {
            CardListRequest left = new CardListRequest();
            CardListRequest right = new CardListRequest();

            // Row 3 is a StopperListRow, so this exercises the five-member shape as well.
            left.setRow(3, new StopperListRow("S", "*", "00000000003", "4111111111111333", "Y"));
            left.setPageCursor(PageCursor.firstPage().withScreenNum(4));
            left.setSelectionFlags(SelectionFlags.lowValues().withSelection(3,
                    CardListRequest.SELECT_VIEW));
            left.setScreenRowTable(ScreenRowTable.lowValues().withRow(3,
                    new ScreenRow("00000000003", "4111111111111333", "Y")));

            assertThat(right.stopperRow(3).crdSel()).isEqualTo(" ");
            assertThat(right.stopperRow(3).crdStp()).isEqualTo(" ");
            assertThat(right.getPageCursor()).isNotEqualTo(left.getPageCursor());
            assertThat(right.getSelectionFlags().isViewRequestedOn(3)).isFalse();
            assertThat(right.getScreenRowTable().row(3).isCleared()).isTrue();
            assertThat(right.getCrdnum3()).isNotEqualTo(left.getCrdnum3());

            // And the reverse: a mutation on the right must not reach the left either.
            right.setRow(3, new StopperListRow("U", " ", "00000000099", "4111111111111999", "N"));
            assertThat(left.getCrdnum3()).isEqualTo("4111111111111333");
            assertThat(left.getCrdsel3()).isEqualTo("S");
        }

        @Test
        @DisplayName("a copy shares no row list, no flag table and no cursor with its original")
        void copiesShareNoMutableState() {
            CardListRequest original = new CardListRequest();
            original.setRow(5, new StopperListRow("U", "*", "00000000005", "4111111111111555",
                    "Y"));
            CardListRequest copy = new CardListRequest(original);
            assertThat(copy).isEqualTo(original);

            copy.setRow(5, new StopperListRow(" ", " ", "00000000000", "0000000000000000", "N"));
            assertThat(original.getCrdnum5()).isEqualTo("4111111111111555");
            assertThat(original.getCrdsel5()).isEqualTo("U");
            assertThat(copy).isNotEqualTo(original);
        }

        @Test
        @DisplayName("TRNNAME holds CCLI at 4 and PGMNAME holds COCRDLIC at 8, exactly")
        void theTransactionAndProgramNamesFitTheirFields() {
            // app/csd/CARDDEMO.CSD:357-358 DEFINE TRANSACTION(CCLI) PROGRAM(COCRDLIC), and
            // app/csd/CARDDEMO.CSD:203 DEFINE PROGRAM(COCRDLIC) DESCRIPTION(LIST CARDS). The program
            // states the same two literals itself at COCRDLIC:179-182.
            CardListRequest request = new CardListRequest();
            request.setTrnname(CardListRequest.TRANSACTION_ID);
            request.setPgmname(CardListRequest.PROGRAM_NAME);

            assertThat(CardListRequest.TRANSACTION_ID).isEqualTo("CCLI")
                    .hasSize(CardListRequest.TRNNAME_LENGTH);
            assertThat(CardListRequest.PROGRAM_NAME).isEqualTo("COCRDLIC")
                    .hasSize(CardListRequest.PGMNAME_LENGTH);

            CardListRequest normalised = request.normalised(CODEC);
            assertThat(normalised.getTrnname()).isEqualTo("CCLI").hasSize(4);
            assertThat(normalised.getPgmname()).isEqualTo("COCRDLIC").hasSize(8);
        }

        @Test
        @DisplayName("equality turns on a single byte of a single row element")
        void oneByteInOneRowBreaksEquality() {
            CardListRequest left = new CardListRequest();
            CardListRequest right = new CardListRequest();
            left.setRow(4, new StopperListRow("S", "*", "00000000004", "4111111111111444", "Y"));
            right.setRow(4, new StopperListRow("S", "*", "00000000004", "4111111111111444", "Y"));
            assertThat(left).isEqualTo(right).hasSameHashCodeAs(right);

            right.setRow(4, new StopperListRow("S", "*", "00000000004", "4111111111111445", "Y"));
            assertThat(left).isNotEqualTo(right);
        }

        @Test
        @DisplayName("toString neither prints nor disguises a card number")
        void toStringDoesNotMaskAnything() {
            // AAP B6: masking, redaction and obfuscation are all forbidden - a disguised card number
            // would be a value this screen never produces. toString reports shape and omits content
            // entirely, which is a different thing from printing a masked value.
            CardListRequest request = new CardListRequest();
            request.setCardsid("4111111111111111");
            request.setRow(2, new StopperListRow("S", "*", "00000000002", "4111111111111222", "Y"));

            String rendered = request.toString();
            assertThat(rendered).doesNotContain("****", "XXXX", "xxxx", "[REDACTED]", "REDACTED",
                    "1111111111", "1111111122");
            assertThat(rendered).contains("payloadFields=45").contains("groupLength=797");

            // The values themselves are untouched by rendering: they are carried in the clear, exactly
            // as CRDNUM1 through CRDNUM7 and CARDSID declare them.
            assertThat(request.getCardsid()).isEqualTo("4111111111111111");
            assertThat(request.getCrdnum2()).isEqualTo("4111111111111222");
        }
    }

    // -------------------------------------------------------------------------------------------------
    // Test data
    // -------------------------------------------------------------------------------------------------

    /**
     * The 45 {@code (label, LENGTH, POS line, POS column)} quadruples of {@code app/bms/COCRDLI.bms},
     * fed to the traceability test so every field appears in the surefire report with its geometry.
     *
     * @return one argument set per name-labelled {@code DFHMDF}
     */
    static Stream<Arguments> bmsFieldCases() {
        return BMS_FIELDS.stream().map(field -> Arguments.of(field.dfhmdfName(), field.length(),
                field.screenLine(), field.screenColumn()));
    }

    /**
     * The state bytes {@code WS-ROW-CRDSELECT-ERROR} can hold, with the one value its single
     * {@code 88}-level recognises and four that it does not.
     *
     * <p>Written as {@code Arguments} rather than a {@code @CsvSource}: a CSV cell holding a space or a
     * {@code LOW-VALUES} byte is normalised to {@code null} by the CSV parser, which would silently
     * replace the two most important cases in this table with something else entirely.
     *
     * @return a description, the byte to store, and whether {@code WS-ROW-SELECT-ERROR} holds
     */
    static Stream<Arguments> rowSelectErrorCases() {
        return Stream.of(
                Arguments.of("'1', the declared VALUE", "1", true),
                Arguments.of("a space", " ", false),
                Arguments.of("LOW-VALUES", "\u0000", false),
                Arguments.of("'S', a selection code", "S", false),
                Arguments.of("'0', the neighbouring digit", "0", false));
    }

    /**
     * The width {@link CardListRequest} declares for one {@code DFHMDF} label, resolved by an explicit
     * arm per label rather than by reflection or by string surgery on the name. Every one of the 45
     * labels is named, so removing or renaming a constant breaks <em>compilation</em> here - a stronger
     * signal than a runtime failure, and the reason this is a {@code switch} and not a map lookup.
     *
     * @param dfhmdfName the {@code DFHMDF} label
     * @return the declared width the class under test carries for it
     */
    private static int declaredWidthOf(String dfhmdfName) {
        return switch (dfhmdfName) {
            case "TRNNAME" -> CardListRequest.TRNNAME_LENGTH;
            case "TITLE01" -> CardListRequest.TITLE01_LENGTH;
            case "CURDATE" -> CardListRequest.CURDATE_LENGTH;
            case "PGMNAME" -> CardListRequest.PGMNAME_LENGTH;
            case "TITLE02" -> CardListRequest.TITLE02_LENGTH;
            case "CURTIME" -> CardListRequest.CURTIME_LENGTH;
            case "PAGENO" -> CardListRequest.PAGENO_LENGTH;
            case "ACCTSID" -> CardListRequest.ACCTSID_LENGTH;
            case "CARDSID" -> CardListRequest.CARDSID_LENGTH;
            case "CRDSEL1", "CRDSEL2", "CRDSEL3", "CRDSEL4", "CRDSEL5", "CRDSEL6", "CRDSEL7" ->
                    CardListRequest.CRDSEL_LENGTH;
            case "CRDSTP2", "CRDSTP3", "CRDSTP4", "CRDSTP5", "CRDSTP6", "CRDSTP7" ->
                    CardListRequest.CRDSTP_LENGTH;
            case "ACCTNO1", "ACCTNO2", "ACCTNO3", "ACCTNO4", "ACCTNO5", "ACCTNO6", "ACCTNO7" ->
                    CardListRequest.ACCTNO_LENGTH;
            case "CRDNUM1", "CRDNUM2", "CRDNUM3", "CRDNUM4", "CRDNUM5", "CRDNUM6", "CRDNUM7" ->
                    CardListRequest.CRDNUM_LENGTH;
            case "CRDSTS1", "CRDSTS2", "CRDSTS3", "CRDSTS4", "CRDSTS5", "CRDSTS6", "CRDSTS7" ->
                    CardListRequest.CRDSTS_LENGTH;
            case "INFOMSG" -> CardListRequest.INFOMSG_LENGTH;
            case "ERRMSG" -> CardListRequest.ERRMSG_LENGTH;
            default -> throw new IllegalArgumentException(dfhmdfName + " is not one of the 45 "
                    + "name-labelled DFHMDF fields of app/bms/COCRDLI.bms");
        };
    }

    /**
     * Reads one payload member by its {@code DFHMDF} label through the real accessor. Again an explicit
     * arm per label: a renamed accessor must fail to compile, and {@code CRDSTP1} must have no arm
     * because it has no accessor.
     *
     * @param request the request to read
     * @param dfhmdfName the {@code DFHMDF} label
     * @return the stored value, never {@code null}
     */
    private static String payloadValueOf(CardListRequest request, String dfhmdfName) {
        return switch (dfhmdfName) {
            case "TRNNAME" -> request.getTrnname();
            case "TITLE01" -> request.getTitle01();
            case "CURDATE" -> request.getCurdate();
            case "PGMNAME" -> request.getPgmname();
            case "TITLE02" -> request.getTitle02();
            case "CURTIME" -> request.getCurtime();
            case "PAGENO" -> request.getPageno();
            case "ACCTSID" -> request.getAcctsid();
            case "CARDSID" -> request.getCardsid();
            case "CRDSEL1" -> request.getCrdsel1();
            case "ACCTNO1" -> request.getAcctno1();
            case "CRDNUM1" -> request.getCrdnum1();
            case "CRDSTS1" -> request.getCrdsts1();
            case "CRDSEL2" -> request.getCrdsel2();
            case "CRDSTP2" -> request.getCrdstp2();
            case "ACCTNO2" -> request.getAcctno2();
            case "CRDNUM2" -> request.getCrdnum2();
            case "CRDSTS2" -> request.getCrdsts2();
            case "CRDSEL3" -> request.getCrdsel3();
            case "CRDSTP3" -> request.getCrdstp3();
            case "ACCTNO3" -> request.getAcctno3();
            case "CRDNUM3" -> request.getCrdnum3();
            case "CRDSTS3" -> request.getCrdsts3();
            case "CRDSEL4" -> request.getCrdsel4();
            case "CRDSTP4" -> request.getCrdstp4();
            case "ACCTNO4" -> request.getAcctno4();
            case "CRDNUM4" -> request.getCrdnum4();
            case "CRDSTS4" -> request.getCrdsts4();
            case "CRDSEL5" -> request.getCrdsel5();
            case "CRDSTP5" -> request.getCrdstp5();
            case "ACCTNO5" -> request.getAcctno5();
            case "CRDNUM5" -> request.getCrdnum5();
            case "CRDSTS5" -> request.getCrdsts5();
            case "CRDSEL6" -> request.getCrdsel6();
            case "CRDSTP6" -> request.getCrdstp6();
            case "ACCTNO6" -> request.getAcctno6();
            case "CRDNUM6" -> request.getCrdnum6();
            case "CRDSTS6" -> request.getCrdsts6();
            case "CRDSEL7" -> request.getCrdsel7();
            case "CRDSTP7" -> request.getCrdstp7();
            case "ACCTNO7" -> request.getAcctno7();
            case "CRDNUM7" -> request.getCrdnum7();
            case "CRDSTS7" -> request.getCrdsts7();
            case "INFOMSG" -> request.getInfomsg();
            case "ERRMSG" -> request.getErrmsg();
            default -> throw new IllegalArgumentException(dfhmdfName + " has no accessor on "
                    + "CardListRequest; CRDSTP1 in particular has none, because COCRDLI.CPY declares "
                    + "no CRDSTP1I");
        };
    }

    /**
     * The absolute 0-based offset of one field's {@code xxxI} item inside {@code 01 CCRDLIAI}, walked
     * from {@link #BMS_FIELDS} using only the two transcribed literals {@link #TIOAPFX_FILLER_BYTES}
     * and {@link #SYMBOLIC_PREFIX_BYTES}. The walk is an independent oracle: the class under test
     * publishes group and section widths but no per-field offsets, so nothing here is re-derived from
     * the code it checks.
     *
     * @param dfhmdfName the {@code DFHMDF} label
     * @return the offset of that field's {@code xxxI} item
     */
    private static int inputItemOffset(String dfhmdfName) {
        int offset = TIOAPFX_FILLER_BYTES;
        for (BmsField field : BMS_FIELDS) {
            offset += SYMBOLIC_PREFIX_BYTES;
            if (field.dfhmdfName().equals(dfhmdfName)) {
                return offset;
            }
            offset += field.length();
        }
        throw new IllegalArgumentException(dfhmdfName + " is not a field of COCRDLI");
    }

    /**
     * The screen line one detail row occupies. The seven rows sit on lines 11 through 17, so subscript
     * {@code n} is line {@code 10 + n} - {@code app/bms/COCRDLI.bms:140-323}.
     *
     * @param cobolRowNumber the COBOL subscript, 1 through 7
     * @return the screen line
     */
    private static int screenLineOfRow(int cobolRowNumber) {
        return 10 + cobolRowNumber;
    }

    /**
     * The total byte span one detail row occupies in {@code 01 CCRDLIAI}, prefixes included. Row 1
     * yields 57 and rows 2 through 7 yield 65 apiece, and that inequality is the asymmetry stated in
     * bytes.
     *
     * @param cobolRowNumber the COBOL subscript, 1 through 7
     * @return the row's byte span
     */
    private static int rowSpanBytes(int cobolRowNumber) {
        int line = screenLineOfRow(cobolRowNumber);
        int span = 0;
        for (BmsField field : BMS_FIELDS) {
            if (field.screenLine() == line) {
                span += SYMBOLIC_PREFIX_BYTES + field.length();
            }
        }
        return span;
    }

    /**
     * The labels of one detail row, in copybook order. Row 1 returns four and rows 2 through 7 return
     * five with the {@code CRDSTPn} label second.
     *
     * @param cobolRowNumber the COBOL subscript, 1 through 7
     * @return that row's labels in source order
     */
    private static List<String> rowFieldNames(int cobolRowNumber) {
        int line = screenLineOfRow(cobolRowNumber);
        List<String> names = new ArrayList<>();
        for (BmsField field : BMS_FIELDS) {
            if (field.screenLine() == line) {
                names.add(field.dfhmdfName());
            }
        }
        return List.copyOf(names);
    }

    /**
     * One entry per map field setter, so each can be shown to reject {@code null} while naming its own
     * {@code DFHMDF} label.
     *
     * @return the eleven setters
     */
    static Stream<Arguments> nullRejectingSetters() {
        return Stream.of(
                Arguments.of("TRNNAME", (Consumer<CardListRequest>) r -> r.setTrnname(null)),
                Arguments.of("TITLE01", (Consumer<CardListRequest>) r -> r.setTitle01(null)),
                Arguments.of("CURDATE", (Consumer<CardListRequest>) r -> r.setCurdate(null)),
                Arguments.of("PGMNAME", (Consumer<CardListRequest>) r -> r.setPgmname(null)),
                Arguments.of("TITLE02", (Consumer<CardListRequest>) r -> r.setTitle02(null)),
                Arguments.of("CURTIME", (Consumer<CardListRequest>) r -> r.setCurtime(null)),
                Arguments.of("PAGENO", (Consumer<CardListRequest>) r -> r.setPageno(null)),
                Arguments.of("ACCTSID", (Consumer<CardListRequest>) r -> r.setAcctsid(null)),
                Arguments.of("CARDSID", (Consumer<CardListRequest>) r -> r.setCardsid(null)),
                Arguments.of("INFOMSG", (Consumer<CardListRequest>) r -> r.setInfomsg(null)),
                Arguments.of("ERRMSG", (Consumer<CardListRequest>) r -> r.setErrmsg(null)));
    }

    /**
     * One mutator per member of the request, each altering exactly that member, so equality and hashing
     * can be shown to depend on all nineteen.
     *
     * @return the nineteen mutators
     */
    static Stream<Arguments> singleMemberMutators() {
        return Stream.of(
                Arguments.of("trnname", (Consumer<CardListRequest>) r -> r.setTrnname("CCLI")),
                Arguments.of("title01", (Consumer<CardListRequest>) r -> r.setTitle01("t1")),
                Arguments.of("curdate", (Consumer<CardListRequest>) r -> r.setCurdate("08/08/26")),
                Arguments.of("pgmname", (Consumer<CardListRequest>) r -> r.setPgmname("COCRDLIC")),
                Arguments.of("title02", (Consumer<CardListRequest>) r -> r.setTitle02("t2")),
                Arguments.of("curtime", (Consumer<CardListRequest>) r -> r.setCurtime("12:00:00")),
                Arguments.of("pageno", (Consumer<CardListRequest>) r -> r.setPageno("002")),
                Arguments.of("acctsid", (Consumer<CardListRequest>) r -> r.setAcctsid("11")),
                Arguments.of("cardsid", (Consumer<CardListRequest>) r -> r.setCardsid("4111")),
                Arguments.of("rows", (Consumer<CardListRequest>) r ->
                        r.setRow(1, new FirstListRow("S", "1", "2", "Y"))),
                Arguments.of("infomsg", (Consumer<CardListRequest>) r -> r.setInfomsg("info")),
                Arguments.of("errmsg", (Consumer<CardListRequest>) r -> r.setErrmsg("err")),
                Arguments.of("pageCursor", (Consumer<CardListRequest>) r ->
                        r.setPageCursor(PageCursor.firstPage().withScreenNum(5))),
                Arguments.of("selectionFlags", (Consumer<CardListRequest>) r ->
                        r.setSelectionFlags(SelectionFlags.spacesFilled())),
                Arguments.of("cardScreenState", (Consumer<CardListRequest>) r -> {
                    CardScreenState state = new CardScreenState();
                    state.setCcAcctId("00000000011");
                    r.setCardScreenState(state);
                }),
                Arguments.of("navigationContext", (Consumer<CardListRequest>) r ->
                        r.setNavigationContext(NavigationContext.empty().withPgmReenter())),
                Arguments.of("screenRowTable", (Consumer<CardListRequest>) r ->
                        r.setScreenRowTable(ScreenRowTable.lowValues()
                                .withRow(1, new ScreenRow("1", "2", "Y")))),
                Arguments.of("selectionErrorFlags", (Consumer<CardListRequest>) r ->
                        r.setSelectionErrorFlags(SelectionErrorFlags.none().withRowInError(1))),
                Arguments.of("fieldMetadata", (Consumer<CardListRequest>) r ->
                        r.putFieldMetadata(FieldMetadata.untouched("ACCTSID"))));
    }

    @Nested
    @DisplayName("The 34 numbered accessor pairs - one per COCRDLI.CPY xxxI item")
    class NumberedRowAccessors {

        @ParameterizedTest(name = "{0} round-trips and touches nothing else on its row")
        @MethodSource("com.vsergeychik.carddemo.card.dto.CardListRequestTest#numberedRowMemberCases")
        @DisplayName("each numbered member reads back exactly what its own setter wrote")
        void eachPairRoundTripsWithoutDisturbingItsRow(String member) throws Exception {
            CardListRequest request = new CardListRequest();
            String cap = member.substring(0, 1).toUpperCase() + member.substring(1);
            int row = Character.getNumericValue(member.charAt(member.length() - 1));
            String written = "Z";

            // Give every member of the row a distinct value first, so a setter that writes the wrong
            // component of the record is caught rather than masked by identical blanks.
            Map<String, String> before = new LinkedHashMap<>();
            for (String sibling : membersOfRow(row)) {
                String value = seedFor(sibling);
                setMember(request, sibling, value);
                before.put(sibling, value);
            }

            CardListRequest.class.getMethod("set" + cap, String.class).invoke(request, written);

            assertThat(getMember(request, member)).as(member).isEqualTo(written);
            for (String sibling : membersOfRow(row)) {
                if (!sibling.equals(member)) {
                    assertThat(getMember(request, sibling))
                            .as("%s must be untouched when %s is written", sibling, member)
                            .isEqualTo(before.get(sibling));
                }
            }
        }

        @ParameterizedTest(name = "{0} refuses null")
        @MethodSource("com.vsergeychik.carddemo.card.dto.CardListRequestTest#numberedRowMemberCases")
        @DisplayName("each numbered setter refuses null - a blank field holds spaces, never null")
        void eachSetterRefusesNull(String member) throws Exception {
            CardListRequest request = new CardListRequest();
            String cap = member.substring(0, 1).toUpperCase() + member.substring(1);
            Method setter = CardListRequest.class.getMethod("set" + cap, String.class);

            assertThatExceptionOfType(InvocationTargetException.class)
                    .isThrownBy(() -> setter.invoke(request, (Object) null))
                    .withCauseInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("there are exactly 34 pairs, and no crdstp1 among them")
        void thereAreExactlyThirtyFourPairs() {
            List<String> getters = new ArrayList<>();
            List<String> setters = new ArrayList<>();
            for (Method method : CardListRequest.class.getDeclaredMethods()) {
                String name = method.getName();
                if (name.matches("get(Crdsel|Crdstp|Acctno|Crdnum|Crdsts)\\d")) {
                    getters.add(name.substring(3).toLowerCase());
                } else if (name.matches("set(Crdsel|Crdstp|Acctno|Crdnum|Crdsts)\\d")) {
                    setters.add(name.substring(3).toLowerCase());
                }
            }

            assertThat(getters).containsExactlyInAnyOrderElementsOf(NUMBERED_ROW_MEMBERS);
            assertThat(setters).containsExactlyInAnyOrderElementsOf(NUMBERED_ROW_MEMBERS);
            assertThat(getters).doesNotContain("crdstp1");
        }

        @Test
        @DisplayName("the numbered members and the row records are one store, not two")
        void theNumberedMembersAndTheRowsAreOneStore() {
            CardListRequest request = new CardListRequest();

            request.setRow(3, new StopperListRow("S", "*", "00000000033", "4111111111111133", "Y"));

            assertThat(request.getCrdsel3()).isEqualTo("S");
            assertThat(request.getCrdstp3()).isEqualTo("*");
            assertThat(request.getAcctno3()).isEqualTo("00000000033");
            assertThat(request.getCrdnum3()).isEqualTo("4111111111111133");
            assertThat(request.getCrdsts3()).isEqualTo("Y");

            request.setCrdsel3("U");

            assertThat(request.stopperRow(3).crdSel())
                    .as("writing through the named member rewrites the row record behind it")
                    .isEqualTo("U");
            assertThat(request.stopperRow(3).crdStp()).isEqualTo("*");
        }

        @Test
        @DisplayName("the numbered setters store verbatim - no pad, no trim, no truncation")
        void theNumberedSettersStoreVerbatim() {
            CardListRequest request = new CardListRequest();

            request.setAcctno2("1");
            request.setCrdnum2(" 4111 ");

            assertThat(request.getAcctno2())
                    .as("binding must not pad to the declared width; the MOVE belongs to the explicit "
                            + "fixed-width step")
                    .isEqualTo("1");
            assertThat(request.getCrdnum2()).isEqualTo(" 4111 ");
        }

        private List<String> membersOfRow(int row) {
            List<String> members = new ArrayList<>();
            members.add("crdsel" + row);
            if (row != CardListRequest.FIRST_ROW_NUMBER) {
                members.add("crdstp" + row);
            }
            members.add("acctno" + row);
            members.add("crdnum" + row);
            members.add("crdsts" + row);
            return members;
        }

        private String seedFor(String member) {
            if (member.startsWith("acctno")) {
                return "0000000000" + member.charAt(member.length() - 1);
            }
            if (member.startsWith("crdnum")) {
                return "411111111111111" + member.charAt(member.length() - 1);
            }
            return member.startsWith("crdsel") ? "S" : member.startsWith("crdstp") ? "*" : "Y";
        }

        private void setMember(CardListRequest request, String member, String value)
                throws Exception {
            String cap = member.substring(0, 1).toUpperCase() + member.substring(1);
            CardListRequest.class.getMethod("set" + cap, String.class).invoke(request, value);
        }

        private String getMember(CardListRequest request, String member) throws Exception {
            String cap = member.substring(0, 1).toUpperCase() + member.substring(1);
            return (String) CardListRequest.class.getMethod("get" + cap).invoke(request);
        }
    }

    static Stream<Arguments> numberedRowMemberCases() {
        return NUMBERED_ROW_MEMBERS.stream().map(Arguments::of);
    }

}
