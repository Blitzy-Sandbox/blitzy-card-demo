package com.vsergeychik.carddemo.transaction.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldHighlight;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldValidationState;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.transaction.dto.TransactionViewResponse.Ct02Info;
import com.vsergeychik.carddemo.transaction.dto.TransactionViewResponse.FieldMetadata;
import com.vsergeychik.carddemo.transaction.dto.TransactionViewResponse.FieldSpans;
import com.vsergeychik.carddemo.transaction.dto.TransactionViewResponse.ScreenField;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
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
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Parity tests for {@link TransactionViewResponse}, the {@code xxxO} projection of
 * {@code 01 COTRN2AO REDEFINES COTRN2AI} in {@code app/cpy-bms/COTRN02.CPY}.
 *
 * <p>Every expected value here is transcribed from the <strong>copybook, the mapset and the
 * program</strong>, never read back from the class under test, so this suite compares the
 * implementation against its source rather than against itself. A change to either side that moved a
 * width, renamed an item or altered a literal fails here.
 *
 * <p>The suite is organised around the properties that determine the implementation, so a failure
 * names a translation decision rather than merely a value:
 *
 * <ol>
 *   <li>The 21 declared widths sum to <strong>396</strong> and the group image is
 *       <strong>555</strong> bytes.</li>
 *   <li>The field set is the <strong>add</strong> screen's - risk <strong>R-B</strong> - so
 *       {@code CONFIRMO} is present and {@code TRNIDINO} and {@code TRNIDO} are absent.</li>
 *   <li>{@code PIC X} moves pad and truncate on the right and never trim, and survive a JSON round
 *       trip untrimmed.</li>
 *   <li>The {@code CSSETATY} highlight is reachable in {@code REENTER} and unreachable in
 *       {@code ENTER} - gate <strong>G38</strong>.</li>
 *   <li>Card, account and merchant identifiers are carried unmasked - gate <strong>G41</strong>.</li>
 *   <li>The 58-byte cursor's two {@code 88}-levels are reachable in both states - gate
 *       <strong>G50</strong>.</li>
 * </ol>
 */
@DisplayName("TransactionViewResponse - COTRN02 COTRN2AO output projection (CT02 / COTRN02C)")
class TransactionViewResponseTest {

    /** The code page of the authoritative fixtures under {@code app/data/ASCII}. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /**
     * The 21 labelled fields with the widths transcribed from {@code app/cpy-bms/COTRN02.CPY} lines
     * 152 to 272, in declaration order. Independently equal to the {@code DFHMDF LENGTH=} values in
     * {@code app/bms/COTRN02.bms}.
     */
    private static Map<String, Integer> copybookWidths() {
        Map<String, Integer> widths = new LinkedHashMap<>();
        widths.put("TRNNAMEO", 4);
        widths.put("TITLE01O", 40);
        widths.put("CURDATEO", 8);
        widths.put("PGMNAMEO", 8);
        widths.put("TITLE02O", 40);
        widths.put("CURTIMEO", 8);
        widths.put("ACTIDINO", 11);
        widths.put("CARDNINO", 16);
        widths.put("TTYPCDO", 2);
        widths.put("TCATCDO", 4);
        widths.put("TRNSRCO", 10);
        widths.put("TDESCO", 60);
        widths.put("TRNAMTO", 12);
        widths.put("TORIGDTO", 10);
        widths.put("TPROCDTO", 10);
        widths.put("MIDO", 9);
        widths.put("MNAMEO", 30);
        widths.put("MCITYO", 25);
        widths.put("MZIPO", 10);
        widths.put("CONFIRMO", 1);
        widths.put("ERRMSGO", 78);
        return widths;
    }

    /** A response with every payload item set to a distinguishable value at its declared width. */
    private static TransactionViewResponse populated() {
        TransactionViewResponse response = new TransactionViewResponse();
        for (ScreenField field : ScreenField.values()) {
            response.setOutputItem(field, filled(field));
        }
        response.setNextProgram("COMEN01C");
        response.setNavigationContext(NavigationContext.empty().withToProgram("COMEN01C"));
        Ct02Info cursor = new Ct02Info();
        cursor.setTrnidFirst("0000000000000001");
        cursor.setTrnidLast("0000000000000010");
        cursor.setPageNum(3);
        cursor.setNextPageYes();
        cursor.setTrnSelFlg("S");
        cursor.setTrnSelected("0000000000000007");
        response.setCt02Info(cursor);
        for (ScreenField field : ScreenField.values()) {
            response.getMetadata(field).setColour(BmsAttributes.DFHGREEN);
            response.getMetadata(field).setProgrammedSymbols((byte) 'p');
            response.getMetadata(field).setHighlight(BmsAttributes.DFHBLINK);
            response.getMetadata(field).setValidation((byte) 'v');
        }
        return response;
    }

    /** A repeatable value of exactly the field's declared width, distinct per field. */
    private static String filled(ScreenField field) {
        String stem = field.label() + "-";
        StringBuilder text = new StringBuilder(field.width());
        while (text.length() < field.width()) {
            text.append(stem);
        }
        return text.substring(0, field.width());
    }

    @Nested
    @DisplayName("Group geometry - 21 fields, 396 payload bytes, a 555-byte image")
    class Geometry {

        @Test
        @DisplayName("the map declares exactly 21 name-labelled fields")
        void fieldCountIs21() {
            assertThat(ScreenField.values()).hasSize(21);
            assertThat(TransactionViewResponse.FIELD_COUNT).isEqualTo(21);
        }

        @Test
        @DisplayName("the 21 declared widths equal the copybook's, name for name and in order")
        void widthsMatchCopybook() {
            Map<String, Integer> expected = copybookWidths();
            Map<String, Integer> actual = new LinkedHashMap<>();
            for (ScreenField field : ScreenField.values()) {
                actual.put(field.outputItemName(), field.width());
            }
            assertThat(actual).containsExactlyEntriesOf(expected);
        }

        @Test
        @DisplayName("ACTIDINO is 11 and CARDNINO is 16 - the pair most easily transposed")
        void accountIs11AndCardIs16() {
            assertThat(TransactionViewResponse.ACTIDINO_LENGTH).isEqualTo(11);
            assertThat(TransactionViewResponse.CARDNINO_LENGTH).isEqualTo(16);
        }

        @Test
        @DisplayName("4+40+8+8+40+8+11+16+2+4+10+60+12+10+10+9+30+25+10+1+78 = 396")
        void payloadWidthsSumTo396() {
            int sum = copybookWidths().values().stream().mapToInt(Integer::intValue).sum();
            assertThat(sum).isEqualTo(396);
            assertThat(TransactionViewResponse.PAYLOAD_LENGTH).isEqualTo(396);
        }

        @Test
        @DisplayName("the AO prefix is 7 bytes per field: FILLER X(3) plus C, P, H and V")
        void perFieldPrefixIsSeven() {
            assertThat(TransactionViewResponse.ATTRIBUTE_PREFIX_FILLER_LENGTH).isEqualTo(3);
            assertThat(TransactionViewResponse.ATTRIBUTE_ITEM_COUNT).isEqualTo(4);
            assertThat(TransactionViewResponse.ATTRIBUTE_ITEM_LENGTH).isEqualTo(1);
            assertThat(TransactionViewResponse.PER_FIELD_PREFIX_LENGTH).isEqualTo(7);
        }

        @Test
        @DisplayName("12 + 21*7 + 396 = 555, and the layout is declared at that length")
        void groupImageIs555() {
            assertThat(TransactionViewResponse.TIOAPFX_LENGTH).isEqualTo(12);
            assertThat(TransactionViewResponse.SYMBOLIC_MAP_LENGTH).isEqualTo(555);
            assertThat(12 + 21 * 7 + 396).isEqualTo(555);
            assertThat(TransactionViewResponse.LAYOUT.recordLength()).isEqualTo(555);
        }

        @Test
        @DisplayName("the layout declares 127 spans: one leading FILLER plus six per field")
        void layoutDeclares127Spans() {
            assertThat(TransactionViewResponse.LAYOUT.spans()).hasSize(1 + 21 * 6);
        }

        @Test
        @DisplayName("the storage spans sum to the record length, so no FILLER was dropped")
        void storageSpansSumToRecordLength() {
            int sum = TransactionViewResponse.LAYOUT.storageSpans().stream()
                    .mapToInt(span -> span.length())
                    .sum();
            assertThat(sum).isEqualTo(555);
        }

        @Test
        @DisplayName("the 22 FILLER spans are present - the leading X(12) and one X(3) per field")
        void fillerSpansArePresent() {
            long fillers = TransactionViewResponse.LAYOUT.spans().stream()
                    .filter(span -> span.kind().filler())
                    .count();
            assertThat(fillers).isEqualTo(22);
            assertThat(TransactionViewResponse.LAYOUT.spans().get(0).length()).isEqualTo(12);
            assertThat(TransactionViewResponse.LAYOUT.spans().get(0).offset()).isZero();
        }

        @Test
        @DisplayName("the payload of each field begins 7 bytes after its own prefix start")
        void offsetsFollowTheSevenByteStride() {
            int offset = 12;
            for (ScreenField field : ScreenField.values()) {
                FieldSpans spans = TransactionViewResponse.FIELD_SPANS.get(field);
                assertThat(spans.colour().offset()).as("%sC", field.label()).isEqualTo(offset + 3);
                assertThat(spans.ps().offset()).as("%sP", field.label()).isEqualTo(offset + 4);
                assertThat(spans.highlight().offset()).as("%sH", field.label())
                        .isEqualTo(offset + 5);
                assertThat(spans.validn().offset()).as("%sV", field.label()).isEqualTo(offset + 6);
                assertThat(spans.output().offset()).as("%sO", field.label()).isEqualTo(offset + 7);
                assertThat(spans.output().length()).isEqualTo(field.width());
                offset += 7 + field.width();
            }
            assertThat(offset).isEqualTo(555);
        }

        @Test
        @DisplayName("the first and last payload items sit at 19 and 477..554")
        void firstAndLastPayloadOffsets() {
            assertThat(TransactionViewResponse.FIELD_SPANS.get(ScreenField.TRNNAME).output()
                    .offset()).isEqualTo(19);
            FieldSpans errmsg = TransactionViewResponse.FIELD_SPANS.get(ScreenField.ERRMSG);
            assertThat(errmsg.output().offset()).isEqualTo(477);
            assertThat(errmsg.output().endOffsetExclusive()).isEqualTo(555);
        }

        @Test
        @DisplayName("every span carries its symbolic-map item name verbatim")
        void spanNamesAreVerbatim() {
            List<String> names = TransactionViewResponse.LAYOUT.spans().stream()
                    .map(span -> span.name())
                    .filter(name -> !"FILLER".equals(name))
                    .toList();
            assertThat(names).hasSize(21 * 5)
                    .contains("TRNAMTC", "TRNAMTP", "TRNAMTH", "TRNAMTV", "TRNAMTO")
                    .contains("ERRMSGO", "CONFIRMO", "ACTIDINO", "CARDNINO", "MIDO");
        }
    }

    @Nested
    @DisplayName("Risk R-B - the field set is the ADD screen's, whatever the class is called")
    class RiskRb {

        @Test
        @DisplayName("CONFIRMO is present: COTRN02 confirms an add, COTRN01 has no such field")
        void confirmIsPresent() {
            assertThat(ScreenField.valueOf("CONFIRM")).isNotNull();
            assertThat(TransactionViewResponse.CONFIRMO_LENGTH).isEqualTo(1);
        }

        @Test
        @DisplayName("there is no TRNIDINO and no TRNIDO - those belong to COTRN01")
        void noTransactionIdField() {
            List<String> outputNames = Stream.of(ScreenField.values())
                    .map(ScreenField::outputItemName)
                    .toList();
            assertThat(outputNames).doesNotContain("TRNIDINO", "TRNIDO", "TRNIDI");
            assertThat(TransactionViewResponse.LAYOUT.hasSpan("TRNIDINO")).isFalse();
            assertThat(TransactionViewResponse.LAYOUT.hasSpan("TRNIDO")).isFalse();
        }

        @Test
        @DisplayName("resolving the label TRNID fails, and says it belongs to COTRN01")
        void trnidLabelIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ScreenField.ofLabel("TRNID"))
                    .withMessageContaining("COTRN01");
        }

        @Test
        @DisplayName("exactly 14 of the 21 fields are UNPROT input, as app/bms/COTRN02.bms declares")
        void fourteenInputFields() {
            long inputs = Stream.of(ScreenField.values()).filter(ScreenField::input).count();
            assertThat(inputs).isEqualTo(14);
        }

        @Test
        @DisplayName("the seven protected fields are the six header items and the error line")
        void sevenProtectedFields() {
            List<String> protectedLabels = Stream.of(ScreenField.values())
                    .filter(field -> !field.input())
                    .map(ScreenField::label)
                    .toList();
            assertThat(protectedLabels).containsExactly("TRNNAME", "TITLE01", "CURDATE", "PGMNAME",
                    "TITLE02", "CURTIME", "ERRMSG");
        }

        @Test
        @DisplayName("the identity constants name CT02, COTRN02C, COTRN02 and COTRN2A")
        void identityConstants() {
            assertThat(TransactionViewResponse.TRANSACTION_ID).isEqualTo("CT02");
            assertThat(TransactionViewResponse.PROGRAM_ID).isEqualTo("COTRN02C");
            assertThat(TransactionViewResponse.MAPSET_NAME).isEqualTo("COTRN02");
            assertThat(TransactionViewResponse.MAP_NAME).isEqualTo("COTRN2A");
        }

        @Test
        @DisplayName("the mapset and map are 7 characters, so the X(7) commarea items fit exactly")
        void mapsetAndMapAreSevenCharacters() {
            assertThat(TransactionViewResponse.MAPSET_NAME)
                    .hasSize(NavigationContext.LAST_MAPSET_LENGTH);
            assertThat(TransactionViewResponse.MAP_NAME)
                    .hasSize(NavigationContext.LAST_MAP_LENGTH);
            assertThat(NavigationContext.LAST_MAP_LENGTH).isEqualTo(7);
            assertThat(NavigationContext.LAST_MAPSET_LENGTH).isEqualTo(7);
        }
    }

    @Nested
    @DisplayName("ScreenField - labels, derived item names and resolution")
    class Fields {

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("the five derived item names are the label plus C, P, H, V and O")
        void derivedItemNames(ScreenField field) {
            assertThat(field.colourItemName()).isEqualTo(field.label() + "C");
            assertThat(field.psItemName()).isEqualTo(field.label() + "P");
            assertThat(field.highlightItemName()).isEqualTo(field.label() + "H");
            assertThat(field.validnItemName()).isEqualTo(field.label() + "V");
            assertThat(field.outputItemName()).isEqualTo(field.label() + "O");
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("every field resolves from its own label, and round trips")
        void resolvesFromOwnLabel(ScreenField field) {
            assertThat(ScreenField.ofLabel(field.label())).isSameAs(field);
        }

        @Test
        @DisplayName("a space-padded label still resolves, because a prefix may arrive padded")
        void paddedLabelResolves() {
            assertThat(ScreenField.ofLabel("TRNAMT   ")).isSameAs(ScreenField.TRNAMT);
        }

        @Test
        @DisplayName("an unknown label is rejected and the message names the legal labels")
        void unknownLabelIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ScreenField.ofLabel("NOSUCH"))
                    .withMessageContaining("COTRN2A")
                    .withMessageContaining("TRNAMT");
        }

        @Test
        @DisplayName("a null label is rejected")
        void nullLabelIsRejected() {
            assertThatNullPointerException().isThrownBy(() -> ScreenField.ofLabel(null));
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("every declared width is at least one byte")
        void widthsArePositive(ScreenField field) {
            assertThat(field.width()).isPositive();
        }
    }

    @Nested
    @DisplayName("PIC X move semantics - pad right, truncate right, never trim")
    class PictureMoves {

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("a fresh response holds spaces at every declared width")
        void freshResponseIsSpaces(ScreenField field) {
            TransactionViewResponse response = new TransactionViewResponse();
            assertThat(response.getOutputItem(field))
                    .hasSize(field.width())
                    .isBlank();
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("a short value is padded on the right and stays padded on read")
        void shortValueIsPaddedNotTrimmed(ScreenField field) {
            TransactionViewResponse response = new TransactionViewResponse();
            response.setOutputItem(field, "A");
            assertThat(response.getOutputItem(field))
                    .hasSize(field.width())
                    .startsWith("A");
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("an over-long value is truncated on the right, never on the left")
        void longValueIsTruncatedOnTheRight(ScreenField field) {
            TransactionViewResponse response = new TransactionViewResponse();
            String tooLong = "9876543210".repeat(20);
            response.setOutputItem(field, tooLong);
            assertThat(response.getOutputItem(field))
                    .hasSize(field.width())
                    .isEqualTo(tooLong.substring(0, field.width()));
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("null is rejected with a message naming the figurative constants")
        void nullIsRejected(ScreenField field) {
            TransactionViewResponse response = new TransactionViewResponse();
            assertThatNullPointerException()
                    .isThrownBy(() -> response.setOutputItem(field, null))
                    .withMessageContaining("COBOL has no null");
        }

        @Test
        @DisplayName("ERRMSGO takes 78 of WS-MESSAGE's 80 characters, per COTRN02C:520")
        void errmsgTruncatesWsMessage() {
            TransactionViewResponse response = new TransactionViewResponse();
            String wsMessage = "X".repeat(78) + "YZ";
            assertThat(wsMessage).hasSize(80);
            response.setErrmsgo(wsMessage);
            assertThat(response.getErrmsgo()).hasSize(78).isEqualTo("X".repeat(78));
        }

        @Test
        @DisplayName("TDESCO takes 60 of TRAN-DESC's 100 characters, per COTRN02C:486")
        void descriptionTruncatesTo60() {
            TransactionViewResponse response = new TransactionViewResponse();
            response.setTdesco("D".repeat(100));
            assertThat(response.getTdesco()).hasSize(60);
        }

        @Test
        @DisplayName("spaces(n) and lowValues(n) render n characters and differ from each other")
        void figurativeConstants() {
            assertThat(TransactionViewResponse.spaces(5)).isEqualTo("     ");
            assertThat(TransactionViewResponse.lowValues(3)).isEqualTo("\u0000\u0000\u0000");
            assertThat(TransactionViewResponse.spaces(3))
                    .isNotEqualTo(TransactionViewResponse.lowValues(3));
        }

        @ParameterizedTest
        @MethodSource("com.vsergeychik.carddemo.transaction.dto."
                + "TransactionViewResponseTest#nonPositiveWidths")
        @DisplayName("a figurative constant of zero or negative width is rejected")
        void figurativeConstantsRejectNonPositiveWidth(int width) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TransactionViewResponse.spaces(width));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TransactionViewResponse.lowValues(width));
        }

        @Test
        @DisplayName("getOutputItem and setOutputItem reject a null field")
        void genericAccessorsRejectNullField() {
            TransactionViewResponse response = new TransactionViewResponse();
            assertThatNullPointerException().isThrownBy(() -> response.getOutputItem(null));
            assertThatNullPointerException().isThrownBy(() -> response.setOutputItem(null, "x"));
        }

        @Test
        @DisplayName("the named setters and the generic setter address the same 21 items")
        void namedAndGenericAccessorsAgree() {
            TransactionViewResponse named = new TransactionViewResponse();
            named.setTrnnameo("CT02");
            named.setTitle01o("t1");
            named.setCurdateo("01/02/03");
            named.setPgmnameo("COTRN02C");
            named.setTitle02o("t2");
            named.setCurtimeo("04:05:06");
            named.setActidino("00000000099");
            named.setCardnino("4111111111111111");
            named.setTtypcdo("01");
            named.setTcatcdo("0002");
            named.setTrnsrco("POS");
            named.setTdesco("desc");
            named.setTrnamto("+00000001.23");
            named.setTorigdto("2022-07-18");
            named.setTprocdto("2022-07-19");
            named.setMido("123456789");
            named.setMnameo("merchant");
            named.setMcityo("city");
            named.setMzipo("12345");
            named.setConfirmo("Y");
            named.setErrmsgo("msg");

            TransactionViewResponse generic = new TransactionViewResponse();
            generic.setOutputItem(ScreenField.TRNNAME, "CT02");
            generic.setOutputItem(ScreenField.TITLE01, "t1");
            generic.setOutputItem(ScreenField.CURDATE, "01/02/03");
            generic.setOutputItem(ScreenField.PGMNAME, "COTRN02C");
            generic.setOutputItem(ScreenField.TITLE02, "t2");
            generic.setOutputItem(ScreenField.CURTIME, "04:05:06");
            generic.setOutputItem(ScreenField.ACTIDIN, "00000000099");
            generic.setOutputItem(ScreenField.CARDNIN, "4111111111111111");
            generic.setOutputItem(ScreenField.TTYPCD, "01");
            generic.setOutputItem(ScreenField.TCATCD, "0002");
            generic.setOutputItem(ScreenField.TRNSRC, "POS");
            generic.setOutputItem(ScreenField.TDESC, "desc");
            generic.setOutputItem(ScreenField.TRNAMT, "+00000001.23");
            generic.setOutputItem(ScreenField.TORIGDT, "2022-07-18");
            generic.setOutputItem(ScreenField.TPROCDT, "2022-07-19");
            generic.setOutputItem(ScreenField.MID, "123456789");
            generic.setOutputItem(ScreenField.MNAME, "merchant");
            generic.setOutputItem(ScreenField.MCITY, "city");
            generic.setOutputItem(ScreenField.MZIP, "12345");
            generic.setOutputItem(ScreenField.CONFIRM, "Y");
            generic.setOutputItem(ScreenField.ERRMSG, "msg");

            assertThat(generic).isEqualTo(named);
            assertThat(named.getTrnnameo()).isEqualTo(generic.getOutputItem(ScreenField.TRNNAME));
            assertThat(named.getTitle01o()).isEqualTo(generic.getOutputItem(ScreenField.TITLE01));
            assertThat(named.getCurdateo()).isEqualTo(generic.getOutputItem(ScreenField.CURDATE));
            assertThat(named.getPgmnameo()).isEqualTo(generic.getOutputItem(ScreenField.PGMNAME));
            assertThat(named.getTitle02o()).isEqualTo(generic.getOutputItem(ScreenField.TITLE02));
            assertThat(named.getCurtimeo()).isEqualTo(generic.getOutputItem(ScreenField.CURTIME));
            assertThat(named.getActidino()).isEqualTo(generic.getOutputItem(ScreenField.ACTIDIN));
            assertThat(named.getCardnino()).isEqualTo(generic.getOutputItem(ScreenField.CARDNIN));
            assertThat(named.getTtypcdo()).isEqualTo(generic.getOutputItem(ScreenField.TTYPCD));
            assertThat(named.getTcatcdo()).isEqualTo(generic.getOutputItem(ScreenField.TCATCD));
            assertThat(named.getTrnsrco()).isEqualTo(generic.getOutputItem(ScreenField.TRNSRC));
            assertThat(named.getTdesco()).isEqualTo(generic.getOutputItem(ScreenField.TDESC));
            assertThat(named.getTrnamto()).isEqualTo(generic.getOutputItem(ScreenField.TRNAMT));
            assertThat(named.getTorigdto()).isEqualTo(generic.getOutputItem(ScreenField.TORIGDT));
            assertThat(named.getTprocdto()).isEqualTo(generic.getOutputItem(ScreenField.TPROCDT));
            assertThat(named.getMido()).isEqualTo(generic.getOutputItem(ScreenField.MID));
            assertThat(named.getMnameo()).isEqualTo(generic.getOutputItem(ScreenField.MNAME));
            assertThat(named.getMcityo()).isEqualTo(generic.getOutputItem(ScreenField.MCITY));
            assertThat(named.getMzipo()).isEqualTo(generic.getOutputItem(ScreenField.MZIP));
            assertThat(named.getConfirmo()).isEqualTo(generic.getOutputItem(ScreenField.CONFIRM));
            assertThat(named.getErrmsgo()).isEqualTo(generic.getOutputItem(ScreenField.ERRMSG));
        }
    }

    static Stream<Arguments> nonPositiveWidths() {
        return Stream.of(Arguments.of(0), Arguments.of(-1));
    }

    @Nested
    @DisplayName("The edited amount - TRNAMTO is a 12-character mask, not a number")
    class EditedAmount {

        @Test
        @DisplayName("the field is 12 characters: sign, 8 integer digits, a point, 2 fraction digits")
        void maskIsTwelveCharacters() {
            assertThat(TransactionViewResponse.TRNAMTO_LENGTH).isEqualTo(12);
            assertThat("+99999999.99").hasSize(12);
        }

        @Test
        @DisplayName("a well-formed mask is stored verbatim and satisfies COTRN02C:340-343 positionally")
        void wellFormedMaskIsStoredVerbatim() {
            TransactionViewResponse response = new TransactionViewResponse();
            response.setTrnamto("-00001234.56");
            String amount = response.getTrnamto();
            assertThat(amount).isEqualTo("-00001234.56").hasSize(12);
            assertThat(amount.charAt(0)).isIn('-', '+');
            assertThat(amount.substring(1, 9)).containsOnlyDigits();
            assertThat(amount.charAt(9)).isEqualTo('.');
            assertThat(amount.substring(10, 12)).containsOnlyDigits();
        }

        @Test
        @DisplayName("a malformed amount is still stored, so the error path can redisplay it")
        void malformedAmountIsStored() {
            TransactionViewResponse response = new TransactionViewResponse();
            response.setTrnamto("not a number");
            assertThat(response.getTrnamto()).isEqualTo("not a number").hasSize(12);
        }

        @Test
        @DisplayName("the mask holds 8 integer digits while TRAN-AMT holds 9, so a 9-digit amount "
                + "left-truncates exactly as COTRN02C:481-485 does")
        void ninthIntegerDigitIsLeftTruncated() {
            // TRAN-AMT PIC S9(09)V99 can hold 123456789.99; the PIC +99999999.99 mask cannot.
            // COBOL's numeric move drops high-order digits, so the leading 1 is lost.
            TransactionViewResponse response = new TransactionViewResponse();
            response.setTrnamto("+23456789.99");
            assertThat(response.getTrnamto()).hasSize(12);
            assertThat(TransactionViewResponse.TRNAMTO_LENGTH).isEqualTo(12);
            assertThat("123456789.99".length()).isGreaterThan(11);
        }
    }

    @Nested
    @DisplayName("Security posture - identifiers are carried unmasked (gate G41)")
    class SecurityPosture {

        @Test
        @DisplayName("CARDNINO returns all 16 characters of the card number, unredacted")
        void cardNumberIsNotMasked() {
            TransactionViewResponse response = new TransactionViewResponse();
            response.setCardnino("4111111111111111");
            assertThat(response.getCardnino())
                    .isEqualTo("4111111111111111")
                    .doesNotContain("*")
                    .doesNotContain("X");
        }

        @Test
        @DisplayName("ACTIDINO returns all 11 characters of the account id, unredacted")
        void accountIdIsNotMasked() {
            TransactionViewResponse response = new TransactionViewResponse();
            response.setActidino("00000000011");
            assertThat(response.getActidino()).isEqualTo("00000000011");
        }

        @Test
        @DisplayName("MIDO returns all 9 characters of the merchant id, unredacted")
        void merchantIdIsNotMasked() {
            TransactionViewResponse response = new TransactionViewResponse();
            response.setMido("123456789");
            assertThat(response.getMido()).isEqualTo("123456789");
        }

        @Test
        @DisplayName("the three identifiers survive a JSON round trip in clear")
        void identifiersSurviveJsonInClear() throws Exception {
            TransactionViewResponse response = new TransactionViewResponse();
            response.setCardnino("4111111111111111");
            response.setActidino("00000000011");
            response.setMido("123456789");
            String json = new ObjectMapper().writeValueAsString(response);
            assertThat(json)
                    .contains("4111111111111111")
                    .contains("00000000011")
                    .contains("123456789");
        }
    }

    @Nested
    @DisplayName("CSSETATY highlight - reachable in REENTER, unreachable in ENTER (gate G38)")
    class Highlighting {

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("every field starts with all four attribute items at DFHDFCOL")
        void attributesStartAtDefault(ScreenField field) {
            FieldMetadata quad = new TransactionViewResponse().getMetadata(field);
            assertThat(quad.getColour()).isEqualTo(BmsAttributes.DFHDFCOL);
            assertThat(quad.getProgrammedSymbols()).isEqualTo(BmsAttributes.DFHDFCOL);
            assertThat(quad.getHighlight()).isEqualTo(BmsAttributes.DFHDFCOL);
            assertThat(quad.getValidation()).isEqualTo(BmsAttributes.DFHDFCOL);
            assertThat(quad.isDefault()).isTrue();
        }

        @ParameterizedTest
        @EnumSource(value = ScreenField.class, names = {"ACTIDIN", "CARDNIN", "TTYPCD", "TCATCD",
                "TRNSRC", "TDESC", "TRNAMT", "TORIGDT", "TPROCDT", "MID", "MNAME", "MCITY", "MZIP",
                "CONFIRM"})
        @DisplayName("in REENTER a BLANK field takes DFHRED in xxxC and '*' in xxxO")
        void reenterBlankHighlightsAndStars(ScreenField field) {
            TransactionViewResponse response = new TransactionViewResponse();
            FieldHighlight highlight = FieldAttributeSetter.resolve(FieldValidationState.BLANK, true,
                    field.label(), TransactionViewResponse.MAP_NAME);

            assertThat(response.applyHighlight(highlight)).isTrue();

            assertThat(response.getMetadata(field).getColour()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(response.getOutputItem(field)).startsWith("*").hasSize(field.width());
        }

        @ParameterizedTest
        @EnumSource(value = ScreenField.class, names = {"ACTIDIN", "CARDNIN", "TRNAMT", "TDESC",
                "MZIP", "CONFIRM"})
        @DisplayName("in REENTER a NOT-OK field takes DFHRED but NOT the asterisk")
        void reenterNotOkHighlightsWithoutStar(ScreenField field) {
            TransactionViewResponse response = new TransactionViewResponse();
            FieldHighlight highlight = FieldAttributeSetter.resolve(FieldValidationState.NOT_OK,
                    true, field.label(), TransactionViewResponse.MAP_NAME);

            assertThat(response.applyHighlight(highlight)).isTrue();

            assertThat(response.getMetadata(field).getColour()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(response.getOutputItem(field)).isBlank();
        }

        @ParameterizedTest
        @EnumSource(value = FieldValidationState.class)
        @DisplayName("in ENTER no state highlights anything - the whole rule is unreachable")
        void enterNeverHighlights(FieldValidationState state) {
            TransactionViewResponse response = new TransactionViewResponse();
            TransactionViewResponse untouched = new TransactionViewResponse();
            FieldHighlight highlight = FieldAttributeSetter.resolve(state, false,
                    ScreenField.TRNAMT.label(), TransactionViewResponse.MAP_NAME);

            assertThat(response.applyHighlight(highlight)).isFalse();

            assertThat(response.getMetadata(ScreenField.TRNAMT).getColour())
                    .isEqualTo(BmsAttributes.DFHDFCOL);
            assertThat(response).isEqualTo(untouched);
        }

        @Test
        @DisplayName("in REENTER an OK field is left alone")
        void reenterOkLeavesFieldAlone() {
            TransactionViewResponse response = new TransactionViewResponse();
            FieldHighlight highlight = FieldAttributeSetter.resolve(FieldValidationState.OK, true,
                    ScreenField.TRNAMT.label(), TransactionViewResponse.MAP_NAME);

            assertThat(response.applyHighlight(highlight)).isFalse();
            assertThat(response.getMetadata(ScreenField.TRNAMT).isDefault()).isTrue();
        }

        @Test
        @DisplayName("a highlight naming a field this map does not declare is rejected")
        void highlightForForeignFieldIsRejected() {
            TransactionViewResponse response = new TransactionViewResponse();
            FieldHighlight highlight = FieldAttributeSetter.resolve(FieldValidationState.BLANK, true,
                    "TRNID", TransactionViewResponse.MAP_NAME);
            assertThatIllegalArgumentException().isThrownBy(() -> response.applyHighlight(highlight));
        }

        @Test
        @DisplayName("a null highlight is rejected, and the message points at FieldAttributeSetter")
        void nullHighlightIsRejected() {
            TransactionViewResponse response = new TransactionViewResponse();
            assertThatNullPointerException()
                    .isThrownBy(() -> response.applyHighlight(null))
                    .withMessageContaining("FieldAttributeSetter");
        }

        @Test
        @DisplayName("every one of the 14 input fields has a reachable colour item")
        void everyInputFieldHasAReachableColourItem() {
            TransactionViewResponse response = new TransactionViewResponse();
            List<String> highlighted = new ArrayList<>();
            for (ScreenField field : ScreenField.values()) {
                if (!field.input()) {
                    continue;
                }
                FieldHighlight highlight = FieldAttributeSetter.resolve(FieldValidationState.NOT_OK,
                        true, field.label(), TransactionViewResponse.MAP_NAME);
                response.applyHighlight(highlight);
                if (response.getMetadata(field).getColour() == BmsAttributes.DFHRED) {
                    highlighted.add(field.label());
                }
            }
            assertThat(highlighted).hasSize(14);
        }

        @Test
        @DisplayName("an asterisk without a colour is impossible: CSSETATY nests the '*' inside the "
                + "DFHRED move, and FieldHighlight enforces that invariant at construction")
        void asteriskWithoutColourIsUnreachable() {
            assertThatIllegalArgumentException().isThrownBy(
                    () -> new FieldHighlight(false, true, "TRNAMT",
                            TransactionViewResponse.MAP_NAME));

            // The three reachable combinations, which is why applyHighlight tests each write
            // independently rather than assuming both always happen together.
            assertThat(new FieldHighlight(false, false, "TRNAMT", "COTRN2A").untouched()).isTrue();
            assertThat(new FieldHighlight(true, false, "TRNAMT", "COTRN2A").untouched()).isFalse();
            assertThat(new FieldHighlight(true, true, "TRNAMT", "COTRN2A").untouched()).isFalse();
        }

        @Test
        @DisplayName("resetMetadata clears every highlight on all 21 fields")
        void resetMetadataClearsEverything() {
            TransactionViewResponse response = populated();
            assertThat(response.getMetadata(ScreenField.TRNAMT).isDefault()).isFalse();

            response.resetMetadata();

            for (ScreenField field : ScreenField.values()) {
                assertThat(response.getMetadata(field).isDefault()).as(field.label()).isTrue();
            }
        }

        @Test
        @DisplayName("the metadata map exposes all 21 quads and cannot gain or lose a field")
        void metadataMapIsFixedAndComplete() {
            TransactionViewResponse response = new TransactionViewResponse();
            assertThat(response.getMetadata()).hasSize(21);
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> response.getMetadata().remove(ScreenField.TRNAMT));
        }

        @Test
        @DisplayName("getMetadata rejects a null field")
        void getMetadataRejectsNull() {
            TransactionViewResponse response = new TransactionViewResponse();
            assertThatNullPointerException().isThrownBy(() -> response.getMetadata(null));
        }

        @Test
        @DisplayName("isDefault is false as soon as any one of the four items is set")
        void isDefaultTracksAllFourItems() {
            for (int index = 0; index < 4; index++) {
                FieldMetadata quad = new FieldMetadata();
                switch (index) {
                    case 0 -> quad.setColour(BmsAttributes.DFHRED);
                    case 1 -> quad.setProgrammedSymbols((byte) 1);
                    case 2 -> quad.setHighlight(BmsAttributes.DFHBLINK);
                    default -> quad.setValidation((byte) 1);
                }
                assertThat(quad.isDefault()).as("item %d", index).isFalse();
                quad.reset();
                assertThat(quad.isDefault()).isTrue();
            }
        }

        @Test
        @DisplayName("FieldMetadata has value semantics over all four items")
        void fieldMetadataValueSemantics() {
            FieldMetadata one = new FieldMetadata();
            FieldMetadata two = new FieldMetadata();
            assertThat(one).isEqualTo(two).hasSameHashCodeAs(two);
            assertThat(one).isEqualTo(one).isNotEqualTo(null).isNotEqualTo("text");

            List<Consumer<FieldMetadata>> mutators = List.of(
                    quad -> quad.setColour(BmsAttributes.DFHRED),
                    quad -> quad.setProgrammedSymbols((byte) 7),
                    quad -> quad.setHighlight(BmsAttributes.DFHREVRS),
                    quad -> quad.setValidation((byte) 9));
            for (Consumer<FieldMetadata> mutator : mutators) {
                FieldMetadata mutated = new FieldMetadata();
                mutator.accept(mutated);
                assertThat(mutated).isNotEqualTo(new FieldMetadata());
            }
            assertThat(one.toString()).contains("FieldMetadata").contains("C=");
        }
    }

    @Nested
    @DisplayName("CDEMO-CT02-INFO - the 58-byte cursor and its two 88-levels (gate G50)")
    class Cursor {

        @Test
        @DisplayName("16 + 16 + 8 + 1 + 1 + 16 = 58, and the passed commarea is 160 + 58 = 218")
        void cursorGeometry() {
            assertThat(Ct02Info.TRNID_FIRST_LENGTH).isEqualTo(16);
            assertThat(Ct02Info.TRNID_LAST_LENGTH).isEqualTo(16);
            assertThat(Ct02Info.PAGE_NUM_LENGTH).isEqualTo(8);
            assertThat(Ct02Info.NEXT_PAGE_FLG_LENGTH).isEqualTo(1);
            assertThat(Ct02Info.TRN_SEL_FLG_LENGTH).isEqualTo(1);
            assertThat(Ct02Info.TRN_SELECTED_LENGTH).isEqualTo(16);
            assertThat(Ct02Info.LENGTH).isEqualTo(58);
            assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(160);
            assertThat(Ct02Info.PASSED_COMMAREA_LENGTH).isEqualTo(218);
        }

        @Test
        @DisplayName("a fresh cursor honours VALUE 'N': NEXT-PAGE-NO is true, NEXT-PAGE-YES false")
        void defaultsToNextPageNo() {
            Ct02Info cursor = new Ct02Info();
            assertThat(cursor.getNextPageFlg()).isEqualTo("N");
            assertThat(cursor.isNextPageNo()).isTrue();
            assertThat(cursor.isNextPageYes()).isFalse();
            assertThat(cursor.getTrnidFirst()).hasSize(16).isBlank();
            assertThat(cursor.getTrnidLast()).hasSize(16).isBlank();
            assertThat(cursor.getTrnSelFlg()).hasSize(1).isBlank();
            assertThat(cursor.getTrnSelected()).hasSize(16).isBlank();
            assertThat(cursor.getPageNum()).isZero();
        }

        @Test
        @DisplayName("SET NEXT-PAGE-YES TO TRUE makes YES true and NO false, and back again")
        void bothConditionsAreReachable() {
            Ct02Info cursor = new Ct02Info();

            cursor.setNextPageYes();
            assertThat(cursor.getNextPageFlg()).isEqualTo("Y");
            assertThat(cursor.isNextPageYes()).isTrue();
            assertThat(cursor.isNextPageNo()).isFalse();

            cursor.setNextPageNo();
            assertThat(cursor.getNextPageFlg()).isEqualTo("N");
            assertThat(cursor.isNextPageYes()).isFalse();
            assertThat(cursor.isNextPageNo()).isTrue();
        }

        @Test
        @DisplayName("a third value leaves both 88-levels false, exactly as COBOL would")
        void athirdValueSatisfiesNeitherCondition() {
            Ct02Info cursor = new Ct02Info();
            cursor.setNextPageFlg("X");
            assertThat(cursor.isNextPageYes()).isFalse();
            assertThat(cursor.isNextPageNo()).isFalse();
        }

        @Test
        @DisplayName("the two identifiers pad to 16 and are not trimmed")
        void identifiersPadTo16() {
            Ct02Info cursor = new Ct02Info();
            cursor.setTrnidFirst("1");
            cursor.setTrnidLast("2");
            cursor.setTrnSelected("3");
            cursor.setTrnSelFlg("SS");
            assertThat(cursor.getTrnidFirst()).hasSize(16).startsWith("1");
            assertThat(cursor.getTrnidLast()).hasSize(16).startsWith("2");
            assertThat(cursor.getTrnSelected()).hasSize(16).startsWith("3");
            assertThat(cursor.getTrnSelFlg()).isEqualTo("S");
        }

        @Test
        @DisplayName("CDEMO-CT02-TRN-SELECTED distinguishes SPACES from LOW-VALUES, per COTRN02C:124")
        void selectedDistinguishesSpacesFromLowValues() {
            Ct02Info spaces = new Ct02Info();
            spaces.setTrnSelected(TransactionViewResponse.spaces(16));
            Ct02Info low = new Ct02Info();
            low.setTrnSelected(TransactionViewResponse.lowValues(16));
            assertThat(spaces.getTrnSelected()).isNotEqualTo(low.getTrnSelected());
        }

        @Test
        @DisplayName("PIC 9(08) accepts 0 and 99999999 and rejects -1 and 100000000")
        void pageNumberHonoursItsPicture() {
            Ct02Info cursor = new Ct02Info();
            cursor.setPageNum(0);
            assertThat(cursor.getPageNum()).isZero();
            cursor.setPageNum(99_999_999);
            assertThat(cursor.getPageNum()).isEqualTo(99_999_999);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> cursor.setPageNum(-1))
                    .withMessageContaining("unsigned");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> cursor.setPageNum(100_000_000))
                    .withMessageContaining("PIC 9(08)");
        }

        @Test
        @DisplayName("every cursor setter rejects null")
        void settersRejectNull() {
            Ct02Info cursor = new Ct02Info();
            assertThatNullPointerException().isThrownBy(() -> cursor.setTrnidFirst(null));
            assertThatNullPointerException().isThrownBy(() -> cursor.setTrnidLast(null));
            assertThatNullPointerException().isThrownBy(() -> cursor.setNextPageFlg(null));
            assertThatNullPointerException().isThrownBy(() -> cursor.setTrnSelFlg(null));
            assertThatNullPointerException().isThrownBy(() -> cursor.setTrnSelected(null));
        }

        @Test
        @DisplayName("the cursor has value semantics over all six items")
        void cursorValueSemantics() {
            assertThat(new Ct02Info()).isEqualTo(new Ct02Info())
                    .hasSameHashCodeAs(new Ct02Info());
            Ct02Info one = new Ct02Info();
            assertThat(one).isEqualTo(one).isNotEqualTo(null).isNotEqualTo("text");

            List<Consumer<Ct02Info>> mutators = List.of(
                    cursor -> cursor.setTrnidFirst("a"),
                    cursor -> cursor.setTrnidLast("b"),
                    cursor -> cursor.setPageNum(1),
                    cursor -> cursor.setNextPageYes(),
                    cursor -> cursor.setTrnSelFlg("s"),
                    cursor -> cursor.setTrnSelected("c"));
            for (Consumer<Ct02Info> mutator : mutators) {
                Ct02Info mutated = new Ct02Info();
                mutator.accept(mutated);
                assertThat(mutated).isNotEqualTo(new Ct02Info());
            }
            assertThat(one.toString()).contains("CDEMO-CT02-TRNID-FIRST")
                    .contains("CDEMO-CT02-NEXT-PAGE-FLG");
        }

        @Test
        @DisplayName("the cursor is echoed on the response and can be replaced, but never nulled")
        void cursorIsEchoed() {
            TransactionViewResponse response = new TransactionViewResponse();
            assertThat(response.getCt02Info()).isNotNull();
            assertThat(response.getCt02Info().isNextPageNo()).isTrue();

            Ct02Info replacement = new Ct02Info();
            replacement.setPageNum(5);
            response.setCt02Info(replacement);
            assertThat(response.getCt02Info().getPageNum()).isEqualTo(5);

            assertThatNullPointerException().isThrownBy(() -> response.setCt02Info(null));
        }
    }

    @Nested
    @DisplayName("Statelessness - navigation replaces XCTL (gates G37 and G40)")
    class Navigation {

        @Test
        @DisplayName("the navigation targets default to this screen, and the program to spaces")
        void navigationDefaults() {
            TransactionViewResponse response = new TransactionViewResponse();
            assertThat(response.getNextMapset()).isEqualTo("COTRN02");
            assertThat(response.getNextMap()).isEqualTo("COTRN2A");
            assertThat(response.getNextProgram()).hasSize(8).isBlank();
        }

        @Test
        @DisplayName("nextProgram is X(8) and nextMapset and nextMap are X(7), not X(8)")
        void navigationWidths() {
            TransactionViewResponse response = new TransactionViewResponse();
            response.setNextProgram("A");
            response.setNextMapset("B");
            response.setNextMap("C");
            assertThat(response.getNextProgram()).hasSize(8);
            assertThat(response.getNextMapset()).hasSize(7);
            assertThat(response.getNextMap()).hasSize(7);
        }

        @Test
        @DisplayName("nextProgram carries the COMMAREA-driven XCTL target of COTRN02C:509")
        void nextProgramCarriesXctlTarget() {
            TransactionViewResponse response = new TransactionViewResponse();
            NavigationContext context = NavigationContext.empty().withToProgram("COMEN01C");
            response.setNavigationContext(context);
            response.setNextProgram(context.toProgram());
            assertThat(response.getNextProgram()).isEqualTo("COMEN01C");
        }

        @Test
        @DisplayName("the navigation setters reject null")
        void navigationSettersRejectNull() {
            TransactionViewResponse response = new TransactionViewResponse();
            assertThatNullPointerException().isThrownBy(() -> response.setNextProgram(null));
            assertThatNullPointerException().isThrownBy(() -> response.setNextMapset(null));
            assertThatNullPointerException().isThrownBy(() -> response.setNextMap(null));
            assertThatNullPointerException().isThrownBy(() -> response.setNavigationContext(null));
        }

        @Test
        @DisplayName("the echoed commarea is exactly 160 bytes - the response never widens it")
        void commareaIsNotWidened() {
            TransactionViewResponse response = new TransactionViewResponse();
            FixedWidthCodec codec = new FixedWidthCodec(ASCII);
            assertThat(response.getNavigationContext().toFixedWidth(codec)).hasSize(160);
        }

        @Test
        @DisplayName("ENTER and REENTER both travel in the echoed commarea, not in server state")
        void programContextTravelsInThePayload() {
            TransactionViewResponse response = new TransactionViewResponse();
            response.setNavigationContext(NavigationContext.empty().withPgmEnter());
            assertThat(response.getNavigationContext().isEnter()).isTrue();
            assertThat(response.getNavigationContext().isReenter()).isFalse();

            response.setNavigationContext(NavigationContext.empty().withPgmReenter());
            assertThat(response.getNavigationContext().isReenter()).isTrue();
            assertThat(response.getNavigationContext().isEnter()).isFalse();
        }
    }

    @Nested
    @DisplayName("The COTRN02C send path, reproduced move for move")
    class SendPath {

        @Test
        @DisplayName("populateHeaderInfo reproduces POPULATE-HEADER-INFO at COTRN02C:552-571")
        void populateHeaderInfoSetsSixItems() {
            TransactionViewResponse response = new TransactionViewResponse();
            DateHeader header = DateHeader.of(new FixedWidthCodec(ASCII),
                    LocalDateTime.of(2022, 7, 18, 4, 5, 6));

            response.populateHeaderInfo(header);

            assertThat(response.getTitle01o()).isEqualTo(ScreenTitles.CCDA_TITLE01).hasSize(40);
            assertThat(response.getTitle02o()).isEqualTo(ScreenTitles.CCDA_TITLE02).hasSize(40);
            assertThat(response.getTrnnameo()).isEqualTo("CT02");
            assertThat(response.getPgmnameo()).isEqualTo("COTRN02C");
            assertThat(response.getCurdateo()).isEqualTo("07/18/22").hasSize(8);
            assertThat(response.getCurtimeo()).isEqualTo("04:05:06").hasSize(8);
        }

        @Test
        @DisplayName("populateHeaderInfo rejects null, because COTRN02C:554 captures the date first")
        void populateHeaderInfoRejectsNull() {
            TransactionViewResponse response = new TransactionViewResponse();
            assertThatNullPointerException()
                    .isThrownBy(() -> response.populateHeaderInfo(null))
                    .withMessageContaining("CURRENT-DATE");
        }

        @Test
        @DisplayName("setErrmsgoInvalidKey reproduces COTRN02C:150, padded from X(50) to X(78)")
        void invalidKeyMessage() {
            TransactionViewResponse response = new TransactionViewResponse();
            response.setErrmsgoInvalidKey();
            assertThat(response.getErrmsgo())
                    .hasSize(78)
                    .startsWith(SystemMessages.CCDA_MSG_INVALID_KEY.stripTrailing());
        }

        @Test
        @DisplayName("clearErrmsgo reproduces COTRN02C:112-113")
        void clearErrorLine() {
            TransactionViewResponse response = new TransactionViewResponse();
            response.setErrmsgo("something went wrong");
            response.clearErrmsgo();
            assertThat(response.getErrmsgo()).hasSize(78).isBlank();
        }

        @Test
        @DisplayName("moveLowValuesToOutputMap reproduces COTRN02C:122 across all 21 items and 84 "
                + "attribute items")
        void lowValuesFillsTheWholeGroup() {
            TransactionViewResponse response = populated();

            response.moveLowValuesToOutputMap();

            for (ScreenField field : ScreenField.values()) {
                assertThat(response.getOutputItem(field)).as(field.outputItemName())
                        .hasSize(field.width())
                        .isEqualTo(TransactionViewResponse.lowValues(field.width()));
                assertThat(response.getMetadata(field).isDefault()).as(field.colourItemName())
                        .isTrue();
            }
        }

        @Test
        @DisplayName("LOW-VALUES and SPACES leave the group in genuinely different states")
        void lowValuesDiffersFromSpaces() {
            TransactionViewResponse low = new TransactionViewResponse();
            low.moveLowValuesToOutputMap();
            TransactionViewResponse blank = new TransactionViewResponse();
            blank.moveSpacesToOutputMap();

            assertThat(low).isNotEqualTo(blank);
            assertThat(low.getTrnamto()).isNotEqualTo(blank.getTrnamto());
            assertThat(blank.getTrnamto()).isBlank();
        }

        @Test
        @DisplayName("moveSpacesToOutputMap blanks all 21 items at their declared widths")
        void spacesFillsEveryPayloadItem() {
            TransactionViewResponse response = populated();

            response.moveSpacesToOutputMap();

            for (ScreenField field : ScreenField.values()) {
                assertThat(response.getOutputItem(field)).as(field.outputItemName())
                        .hasSize(field.width())
                        .isBlank();
            }
        }
    }

    @Nested
    @DisplayName("Fixed-width rendering of the 555-byte group image")
    class FixedWidth {

        @Test
        @DisplayName("a rendered image is exactly 555 bytes")
        void imageIs555Bytes() {
            assertThat(populated().toFixedWidth(ASCII)).hasSize(555);
            assertThat(new TransactionViewResponse().toFixedWidth(ASCII)).hasSize(555);
        }

        @Test
        @DisplayName("the round trip is lossless for all 21 payload and 84 attribute items")
        void roundTripIsLossless() {
            TransactionViewResponse original = populated();

            TransactionViewResponse restored =
                    TransactionViewResponse.fromFixedWidth(original.toFixedWidth(ASCII), ASCII);

            for (ScreenField field : ScreenField.values()) {
                assertThat(restored.getOutputItem(field)).as(field.outputItemName())
                        .isEqualTo(original.getOutputItem(field));
                assertThat(restored.getMetadata(field)).as(field.label())
                        .isEqualTo(original.getMetadata(field));
            }
        }

        @Test
        @DisplayName("a DFHRED colour byte survives the round trip, because attributes go as bytes")
        void attributeBytesSurvive() {
            TransactionViewResponse original = new TransactionViewResponse();
            original.getMetadata(ScreenField.TRNAMT).setColour(BmsAttributes.DFHRED);

            TransactionViewResponse restored =
                    TransactionViewResponse.fromFixedWidth(original.toFixedWidth(ASCII), ASCII);

            assertThat(restored.getMetadata(ScreenField.TRNAMT).getColour())
                    .isEqualTo(BmsAttributes.DFHRED);
        }

        @Test
        @DisplayName("a LOW-VALUES group round trips without becoming spaces")
        void lowValuesGroupRoundTrips() {
            TransactionViewResponse original = new TransactionViewResponse();
            original.moveLowValuesToOutputMap();

            TransactionViewResponse restored =
                    TransactionViewResponse.fromFixedWidth(original.toFixedWidth(ASCII), ASCII);

            assertThat(restored.getTrnamto()).isEqualTo(original.getTrnamto());
            assertThat(restored.getTrnamto().charAt(0)).isEqualTo('\u0000');
        }

        @Test
        @DisplayName("trailing spaces survive the round trip untrimmed")
        void trailingSpacesSurvive() {
            TransactionViewResponse original = new TransactionViewResponse();
            original.setErrmsgo("short");
            original.setConfirmo(" ");
            original.setTrnamto("+00000000.00");

            TransactionViewResponse restored =
                    TransactionViewResponse.fromFixedWidth(original.toFixedWidth(ASCII), ASCII);

            assertThat(restored.getErrmsgo()).hasSize(78).isEqualTo(original.getErrmsgo());
            assertThat(restored.getConfirmo()).hasSize(1).isEqualTo(" ");
            assertThat(restored.getTrnamto()).hasSize(12).isEqualTo("+00000000.00");
        }

        @Test
        @DisplayName("writeInto and readFrom address a caller-supplied record")
        void writeIntoAndReadFrom() {
            TransactionViewResponse original = populated();
            FixedWidthRecord record =
                    FixedWidthRecord.forLayout(TransactionViewResponse.LAYOUT, ASCII);

            original.writeInto(record);
            TransactionViewResponse restored = new TransactionViewResponse();
            restored.readFrom(record);

            for (ScreenField field : ScreenField.values()) {
                assertThat(restored.getOutputItem(field)).as(field.outputItemName())
                        .isEqualTo(original.getOutputItem(field));
            }
        }

        @Test
        @DisplayName("an image of the wrong length is rejected, and the message shows the arithmetic")
        void wrongLengthIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TransactionViewResponse.fromFixedWidth(new byte[554], ASCII))
                    .withMessageContaining("555");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TransactionViewResponse.fromFixedWidth(new byte[556], ASCII));
        }

        @Test
        @DisplayName("a record laid out for something else is rejected by writeInto and readFrom")
        void foreignRecordIsRejected() {
            TransactionViewResponse response = new TransactionViewResponse();
            FixedWidthRecord foreign =
                    FixedWidthRecord.forLayout(NavigationContext.LAYOUT, ASCII);
            assertThatIllegalArgumentException().isThrownBy(() -> response.writeInto(foreign));
            assertThatIllegalArgumentException().isThrownBy(() -> response.readFrom(foreign));
        }

        @Test
        @DisplayName("the charset is always supplied by the caller and never defaulted")
        void charsetIsMandatory() {
            TransactionViewResponse response = new TransactionViewResponse();
            assertThatNullPointerException()
                    .isThrownBy(() -> response.toFixedWidth(null))
                    .withMessageContaining("platform default");
            assertThatNullPointerException()
                    .isThrownBy(() -> TransactionViewResponse.fromFixedWidth(new byte[555], null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TransactionViewResponse.fromFixedWidth(null, ASCII));
            assertThatNullPointerException().isThrownBy(() -> response.writeInto(null));
            assertThatNullPointerException().isThrownBy(() -> response.readFrom(null));
        }

        @Test
        @DisplayName("an ASCII image and an EBCDIC image differ in bytes but agree in fields")
        void encodingIsExplicitNotAmbient() {
            TransactionViewResponse original = new TransactionViewResponse();
            original.setErrmsgo("Account ID NOT found...");
            Charset ebcdic = Charset.forName("IBM037");

            byte[] asciiImage = original.toFixedWidth(ASCII);
            byte[] ebcdicImage = original.toFixedWidth(ebcdic);

            assertThat(asciiImage).hasSize(555);
            assertThat(ebcdicImage).hasSize(555).isNotEqualTo(asciiImage);
            assertThat(TransactionViewResponse.fromFixedWidth(ebcdicImage, ebcdic).getErrmsgo())
                    .isEqualTo(original.getErrmsgo());
        }
    }

    @Nested
    @DisplayName("JSON projection - 21 payload members, no metadata, nothing trimmed")
    class Json {

        private final ObjectMapper mapper = new ObjectMapper();

        @Test
        @DisplayName("all 21 payload members are serialised under their xxxO-derived names")
        void allPayloadMembersAreSerialised() throws Exception {
            String json = mapper.writeValueAsString(populated());
            for (ScreenField field : ScreenField.values()) {
                String property = field.outputItemName().toLowerCase(java.util.Locale.ROOT);
                assertThat(json).as(property).contains("\"" + property + "\"");
            }
        }

        @Test
        @DisplayName("no attribute item is serialised - metadata never reaches the payload")
        void metadataIsNotSerialised() throws Exception {
            TransactionViewResponse response = populated();
            String json = mapper.writeValueAsString(response);
            assertThat(json)
                    .doesNotContain("\"metadata\"")
                    .doesNotContain("trnamtc")
                    .doesNotContain("colour")
                    .doesNotContain("programmedSymbols")
                    .doesNotContain("nextPageYes")
                    .doesNotContain("nextPageNo");
        }

        @Test
        @DisplayName("a full round trip through JSON preserves all 21 payload items untrimmed")
        void jsonRoundTripPreservesPadding() throws Exception {
            TransactionViewResponse original = new TransactionViewResponse();
            original.setErrmsgo("short message");
            original.setTrnamto("+00000012.34");
            original.setConfirmo("Y");
            original.setCardnino("4111111111111111");

            String json = mapper.writeValueAsString(original);
            TransactionViewResponse restored =
                    mapper.readValue(json, TransactionViewResponse.class);

            assertThat(restored.getErrmsgo()).hasSize(78).isEqualTo(original.getErrmsgo());
            assertThat(restored.getTrnamto()).hasSize(12).isEqualTo("+00000012.34");
            assertThat(restored.getConfirmo()).hasSize(1).isEqualTo("Y");
            assertThat(restored.getCardnino()).hasSize(16).isEqualTo("4111111111111111");
            for (ScreenField field : ScreenField.values()) {
                assertThat(restored.getOutputItem(field)).as(field.outputItemName())
                        .isEqualTo(original.getOutputItem(field));
            }
        }

        @Test
        @DisplayName("the navigation trio and the cursor survive a JSON round trip")
        void navigationAndCursorSurviveJson() throws Exception {
            TransactionViewResponse original = new TransactionViewResponse();
            original.setNextProgram("COMEN01C");
            original.getCt02Info().setPageNum(4);
            original.getCt02Info().setNextPageYes();
            original.getCt02Info().setTrnSelected("0000000000000009");

            TransactionViewResponse restored = mapper.readValue(
                    mapper.writeValueAsString(original), TransactionViewResponse.class);

            assertThat(restored.getNextProgram()).isEqualTo("COMEN01C");
            assertThat(restored.getNextMapset()).isEqualTo("COTRN02");
            assertThat(restored.getNextMap()).isEqualTo("COTRN2A");
            assertThat(restored.getCt02Info().getPageNum()).isEqualTo(4);
            assertThat(restored.getCt02Info().isNextPageYes()).isTrue();
            assertThat(restored.getCt02Info().getTrnSelected()).hasSize(16);
        }
    }

    @Nested
    @DisplayName("Value semantics over everything the response carries")
    class ValueSemantics {

        @Test
        @DisplayName("two fresh responses are equal and share a hash code")
        void freshResponsesAreEqual() {
            assertThat(new TransactionViewResponse())
                    .isEqualTo(new TransactionViewResponse())
                    .hasSameHashCodeAs(new TransactionViewResponse());
        }

        @Test
        @DisplayName("a response equals itself and nothing of another type")
        void reflexiveAndTypeSafe() {
            TransactionViewResponse response = new TransactionViewResponse();
            assertThat(response).isEqualTo(response).isNotEqualTo(null).isNotEqualTo("text");
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("changing any one payload item breaks equality")
        void everyPayloadItemParticipatesInEquality(ScreenField field) {
            TransactionViewResponse mutated = new TransactionViewResponse();
            mutated.setOutputItem(field, "Z");
            assertThat(mutated).isNotEqualTo(new TransactionViewResponse());
        }

        @ParameterizedTest
        @MethodSource("com.vsergeychik.carddemo.transaction.dto."
                + "TransactionViewResponseTest#nonPayloadMutators")
        @DisplayName("changing any non-payload item also breaks equality")
        void everyNonPayloadItemParticipatesInEquality(
                String description, Consumer<TransactionViewResponse> mutator) {
            TransactionViewResponse mutated = new TransactionViewResponse();
            mutator.accept(mutated);
            assertThat(mutated).as(description).isNotEqualTo(new TransactionViewResponse());
        }

        @Test
        @DisplayName("toString quotes every payload item so trailing spaces are visible")
        void toStringQuotesEveryItem() {
            String text = new TransactionViewResponse().toString();
            assertThat(text).startsWith("TransactionViewResponse[")
                    .contains("CT02/COTRN02C")
                    .contains("COTRN02.COTRN2A")
                    .contains("nextProgram='")
                    .contains("CDEMO-CT02-TRNID-FIRST");
            for (ScreenField field : ScreenField.values()) {
                assertThat(text).as(field.outputItemName())
                        .contains(field.outputItemName() + "='");
            }
        }
    }

    static Stream<Arguments> nonPayloadMutators() {
        return Stream.of(
                Arguments.of("nextProgram",
                        (Consumer<TransactionViewResponse>) r -> r.setNextProgram("COMEN01C")),
                Arguments.of("nextMapset",
                        (Consumer<TransactionViewResponse>) r -> r.setNextMapset("COTRN01")),
                Arguments.of("nextMap",
                        (Consumer<TransactionViewResponse>) r -> r.setNextMap("COTRN1A")),
                Arguments.of("navigationContext",
                        (Consumer<TransactionViewResponse>) r -> r.setNavigationContext(
                                NavigationContext.empty().withToProgram("COMEN01C"))),
                Arguments.of("ct02Info",
                        (Consumer<TransactionViewResponse>) r -> r.getCt02Info().setPageNum(2)),
                Arguments.of("metadata",
                        (Consumer<TransactionViewResponse>) r -> r.getMetadata(ScreenField.TRNAMT)
                                .setColour(BmsAttributes.DFHRED)));
    }
}
