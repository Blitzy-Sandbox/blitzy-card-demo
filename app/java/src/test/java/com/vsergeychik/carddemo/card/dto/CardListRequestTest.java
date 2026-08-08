package com.vsergeychik.carddemo.card.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

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
 * </ol>
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
            assertThat(Stream.of(CardListRequest.class.getAnnotations())).isEmpty();
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
    // Test data
    // -------------------------------------------------------------------------------------------------

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
