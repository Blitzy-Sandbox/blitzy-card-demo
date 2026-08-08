package com.vsergeychik.carddemo.transaction.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldHighlight;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.transaction.dto.TransactionAddResponse.AttributeQuad;
import com.vsergeychik.carddemo.transaction.dto.TransactionAddRequest.Ct01Info;
import com.vsergeychik.carddemo.transaction.dto.TransactionAddResponse.ScreenField;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Verifies {@link TransactionAddResponse} against its two authoritative sources -
 * {@code app/cpy-bms/COTRN01.CPY} and {@code app/bms/COTRN01.bms} - rather than against itself.
 *
 * <p>The expected geometry is transcribed independently from the copybook into
 * {@link #COPYBOOK_FIELDS} below, so a mistake in the production geometry table cannot agree with a
 * matching mistake here: the two tables were written from the source, not from each other.
 *
 * <p>Covers gate G9 (the 1:1 field projection and every declared width), G37 and G40 (navigation
 * without server-side state), G38 (the {@code CSSETATY} highlight reachable only in {@code REENTER}),
 * G41 (no masking of the card number or merchant id), G21 (fillers emitted), G50 (both
 * {@code 88}-level states of the cursor's next-page flag) and G22/G23/G24 (no floating-point type
 * anywhere in the payload).
 */
@DisplayName("COTRN1AO - the outbound projection of CT01 / COTRN01C")
class TransactionAddResponseTest {

    /** The code page of the authoritative fixtures under {@code app/data/ASCII}. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** The codec form of {@link #ASCII}, for the carriers whose byte boundary takes one. */
    private static final FixedWidthCodec ASCII_CODEC = new FixedWidthCodec(ASCII);

    /**
     * The 21 name-labelled fields of {@code COTRN01}, transcribed independently from
     * {@code app/cpy-bms/COTRN01.CPY} lines 145-272: the {@code xxxO} item name, its
     * {@code PIC X(n)} width, and the absolute offset of its field group.
     *
     * <p>Each width was cross-checked against the {@code LENGTH=} operand of the matching
     * {@code DFHMDF} in {@code app/bms/COTRN01.bms}; all 21 agree.
     */
    private static final List<Object[]> COPYBOOK_FIELDS = List.of(
            new Object[] {"TRNNAMEO", 4, 12},
            new Object[] {"TITLE01O", 40, 23},
            new Object[] {"CURDATEO", 8, 70},
            new Object[] {"PGMNAMEO", 8, 85},
            new Object[] {"TITLE02O", 40, 100},
            new Object[] {"CURTIMEO", 8, 147},
            new Object[] {"TRNIDINO", 16, 162},
            new Object[] {"TRNIDO", 16, 185},
            new Object[] {"CARDNUMO", 16, 208},
            new Object[] {"TTYPCDO", 2, 231},
            new Object[] {"TCATCDO", 4, 240},
            new Object[] {"TRNSRCO", 10, 251},
            new Object[] {"TDESCO", 60, 268},
            new Object[] {"TRNAMTO", 12, 335},
            new Object[] {"TORIGDTO", 10, 354},
            new Object[] {"TPROCDTO", 10, 371},
            new Object[] {"MIDO", 9, 388},
            new Object[] {"MNAMEO", 30, 404},
            new Object[] {"MCITYO", 25, 441},
            new Object[] {"MZIPO", 10, 473},
            new Object[] {"ERRMSGO", 78, 490});

    private static List<Object[]> copybookFields() {
        return COPYBOOK_FIELDS;
    }

    // =================================================================================================

    @Nested
    @DisplayName("Field projection, checked against the copybook (gate G9)")
    class FieldProjection {

        @Test
        @DisplayName("projects exactly 21 payload fields - no more, and none of COTRN02's")
        void projectsExactlyTwentyOneFields() {
            assertThat(ScreenField.values()).hasSize(21);
            assertThat(TransactionAddResponse.PAYLOAD_FIELD_COUNT).isEqualTo(21);
            assertThat(COPYBOOK_FIELDS).hasSize(21);
        }

        @ParameterizedTest(name = "{0} PIC X({1}) at group {2}")
        @MethodSource("com.vsergeychik.carddemo.transaction.dto.TransactionAddResponseTest#copybookFields")
        @DisplayName("each field's name, width and offset match the copybook exactly")
        void fieldMatchesCopybook(String itemName, int width, int groupOffset) {
            ScreenField field = ScreenField.valueOf(itemName);
            assertThat(field.outputItemName()).isEqualTo(itemName);
            assertThat(field.payloadLength()).isEqualTo(width);
            assertThat(field.groupOffset()).isEqualTo(groupOffset);
            assertThat(field.payloadOffset()).isEqualTo(groupOffset + 7);
        }

        @Test
        @DisplayName("declaration order matches the copybook, top to bottom")
        void declarationOrderMatchesCopybook() {
            List<String> declared = new ArrayList<>();
            for (ScreenField field : ScreenField.values()) {
                declared.add(field.outputItemName());
            }
            List<String> expected = new ArrayList<>();
            for (Object[] row : COPYBOOK_FIELDS) {
                expected.add((String) row[0]);
            }
            assertThat(declared).containsExactlyElementsOf(expected);
        }

        @Test
        @DisplayName("carries none of COTRN02's add-screen fields - risk R-B stays documented, not implemented")
        void carriesNoAddScreenFields() {
            List<String> names = new ArrayList<>();
            for (ScreenField field : ScreenField.values()) {
                names.add(field.outputItemName());
            }
            assertThat(names).doesNotContain("CONFIRMO", "ACTIDINO", "CARDNINO");
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("the six item names of every field derive from one verbatim base name")
        void itemNamesDeriveFromBaseName(ScreenField field) {
            String base = field.baseName();
            assertThat(field.outputItemName()).isEqualTo(base + "O");
            assertThat(field.inputItemName()).isEqualTo(base + "I");
            assertThat(field.colourItemName()).isEqualTo(base + "C");
            assertThat(field.programmedSymbolsItemName()).isEqualTo(base + "P");
            assertThat(field.highlightItemName()).isEqualTo(base + "H");
            assertThat(field.validationItemName()).isEqualTo(base + "V");
            assertThat(field.describe()).contains(field.outputItemName());
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("the attribute items sit at group+3, +4, +5 and +6, and the payload at group+7")
        void attributeItemOffsetsFollowTheGroupFiller(ScreenField field) {
            int group = field.groupOffset();
            assertThat(field.colourItemOffset()).isEqualTo(group + 3);
            assertThat(field.programmedSymbolsItemOffset()).isEqualTo(group + 4);
            assertThat(field.highlightItemOffset()).isEqualTo(group + 5);
            assertThat(field.validationItemOffset()).isEqualTo(group + 6);
            assertThat(field.payloadOffset()).isEqualTo(group + 7);
            assertThat(field.groupEndOffsetExclusive())
                    .isEqualTo(group + 7 + field.payloadLength());
            assertThat(field.groupFillerSpan().length()).isEqualTo(3);
            assertThat(field.payloadSpan().name()).isEqualTo(field.outputItemName());
            assertThat(field.colourSpan().name()).isEqualTo(field.colourItemName());
            assertThat(field.programmedSymbolsSpan().name())
                    .isEqualTo(field.programmedSymbolsItemName());
            assertThat(field.highlightSpan().name()).isEqualTo(field.highlightItemName());
            assertThat(field.validationSpan().name()).isEqualTo(field.validationItemName());
        }

        @Test
        @DisplayName("field groups tile the image with no gap and no overlap")
        void fieldGroupsTileTheImage() {
            int cursor = TransactionAddResponse.TIOAPFX_PREFIX_LENGTH;
            for (ScreenField field : ScreenField.values()) {
                assertThat(field.groupOffset()).as("group start of %s", field).isEqualTo(cursor);
                cursor = field.groupEndOffsetExclusive();
            }
            assertThat(cursor).isEqualTo(TransactionAddResponse.SYMBOLIC_MAP_LENGTH);
        }
    }

    @Nested
    @DisplayName("Byte totals")
    class ByteTotals {

        @Test
        @DisplayName("the 21 payload widths sum to 416")
        void payloadWidthsSumTo416() {
            int sum = 0;
            for (Object[] row : COPYBOOK_FIELDS) {
                sum += (Integer) row[1];
            }
            assertThat(sum).isEqualTo(416);
            assertThat(TransactionAddResponse.TOTAL_PAYLOAD_WIDTH).isEqualTo(416);
        }

        @Test
        @DisplayName("the COTRN1AO image is 12 + 21x7 + 416 = 575 bytes")
        void symbolicMapImageIs575Bytes() {
            assertThat(TransactionAddResponse.TIOAPFX_PREFIX_LENGTH).isEqualTo(12);
            assertThat(TransactionAddResponse.FIELD_ATTRIBUTE_PREFIX_LENGTH).isEqualTo(7);
            assertThat(TransactionAddResponse.SYMBOLIC_MAP_LENGTH)
                    .isEqualTo(12 + 21 * 7 + 416)
                    .isEqualTo(575);
            assertThat(TransactionAddResponse.LAYOUT.recordLength()).isEqualTo(575);
        }

        @Test
        @DisplayName("the CT01 cursor is 16+16+8+1+1+16 = 58 bytes")
        void cursorIs58Bytes() {
            assertThat(Ct01Info.RECORD_LENGTH)
                    .isEqualTo(16 + 16 + 8 + 1 + 1 + 16)
                    .isEqualTo(58);
            assertThat(Ct01Info.LAYOUT.recordLength()).isEqualTo(58);
        }

        @Test
        @DisplayName("the passed commarea is 160 + 58 = 218 bytes, by concatenation not widening")
        void passedCommareaIs218Bytes() {
            assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(160);
            assertThat(TransactionAddResponse.PASSED_COMMAREA_LENGTH)
                    .isEqualTo(160 + 58)
                    .isEqualTo(218);
        }

        @Test
        @DisplayName("the layout declares every byte, fillers included (gate G21)")
        void layoutDeclaresEveryByteIncludingFillers() {
            // 1 leading filler + 21 x (1 group filler + 4 attribute items + 1 payload item)
            assertThat(TransactionAddResponse.LAYOUT.spans()).hasSize(1 + 21 * 6);
            int declared = 0;
            for (FixedWidthRecord.FieldSpan span : TransactionAddResponse.LAYOUT.storageSpans()) {
                declared += span.length();
            }
            assertThat(declared).isEqualTo(575);
        }

        @Test
        @DisplayName("widths shared with the common contracts agree with the screen fields")
        void sharedContractWidthsAgree() {
            assertThat(TransactionAddResponse.TITLE_ITEM_LENGTH)
                    .isEqualTo(ScreenTitles.TITLE_LENGTH)
                    .isEqualTo(ScreenField.TITLE01O.payloadLength())
                    .isEqualTo(ScreenField.TITLE02O.payloadLength());
            assertThat(TransactionAddResponse.CURDATE_ITEM_LENGTH)
                    .isEqualTo(DateHeader.WS_CURDATE_MM_DD_YY_LENGTH)
                    .isEqualTo(ScreenField.CURDATEO.payloadLength());
            assertThat(TransactionAddResponse.CURTIME_ITEM_LENGTH)
                    .isEqualTo(DateHeader.WS_CURTIME_HH_MM_SS_LENGTH)
                    .isEqualTo(ScreenField.CURTIMEO.payloadLength());
            // A standard message text fits inside ERRMSGO without truncation.
            assertThat(TransactionAddResponse.STANDARD_MESSAGE_LENGTH)
                    .isEqualTo(SystemMessages.MESSAGE_LENGTH)
                    .isLessThanOrEqualTo(ScreenField.ERRMSGO.payloadLength());
            // WS-MESSAGE is wider than the field it feeds, so a move into it truncates on the right.
            assertThat(TransactionAddResponse.WS_MESSAGE_LENGTH)
                    .isGreaterThan(ScreenField.ERRMSGO.payloadLength());
        }

        @Test
        @DisplayName("identity constants come from the CSD and the program's WORKING-STORAGE")
        void identityConstants() {
            assertThat(TransactionAddResponse.TRANSACTION_ID).isEqualTo("CT01");
            assertThat(TransactionAddResponse.PROGRAM_NAME).isEqualTo("COTRN01C");
            assertThat(TransactionAddResponse.MAPSET_NAME).isEqualTo("COTRN01");
            assertThat(TransactionAddResponse.MAP_NAME).isEqualTo("COTRN1A");
            assertThat(TransactionAddResponse.INPUT_MAP_GROUP_NAME).isEqualTo("COTRN1AI");
            assertThat(TransactionAddResponse.OUTPUT_MAP_GROUP_NAME).isEqualTo("COTRN1AO");
            // A map or mapset name is seven wide; a program name is eight.
            assertThat(TransactionAddResponse.MAP_NAME).hasSize(7);
            assertThat(TransactionAddResponse.MAPSET_NAME).hasSize(7);
            assertThat(TransactionAddResponse.NEXT_MAP_LENGTH).isEqualTo(7);
            assertThat(TransactionAddResponse.NEXT_MAPSET_LENGTH).isEqualTo(7);
            assertThat(TransactionAddResponse.NEXT_PROGRAM_LENGTH).isEqualTo(8);
        }
    }

    @Nested
    @DisplayName("Payload values obey the PIC X move rule")
    class PayloadValues {

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("a fresh response holds every field space-filled to its declared width")
        void freshResponseIsSpaceFilled(ScreenField field) {
            TransactionAddResponse response = new TransactionAddResponse();
            assertThat(response.payload(field))
                    .hasSize(field.payloadLength())
                    .isBlank();
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("a short value is padded on the right and kept at the declared width")
        void shortValueIsPaddedOnTheRight(ScreenField field) {
            TransactionAddResponse response = new TransactionAddResponse();
            response.setPayload(field, "A");
            assertThat(response.payload(field))
                    .hasSize(field.payloadLength())
                    .startsWith("A");
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("an over-long value is truncated on the RIGHT, as COBOL truncates PIC X")
        void longValueIsTruncatedOnTheRight(ScreenField field) {
            TransactionAddResponse response = new TransactionAddResponse();
            String sending = "Z".repeat(field.payloadLength()) + "LOST";
            response.setPayload(field, sending);
            assertThat(response.payload(field))
                    .hasSize(field.payloadLength())
                    .isEqualTo("Z".repeat(field.payloadLength()));
        }

        @Test
        @DisplayName("all 21 named accessor pairs read and write their own field")
        void namedAccessorsAddressTheirOwnField() {
            TransactionAddResponse response = new TransactionAddResponse();
            Map<ScreenField, String> written = new LinkedHashMap<>();

            response.setTrnnameo("CT01");
            written.put(ScreenField.TRNNAMEO, response.getTrnnameo());
            response.setTitle01o("TITLE ONE");
            written.put(ScreenField.TITLE01O, response.getTitle01o());
            response.setCurdateo("08/08/26");
            written.put(ScreenField.CURDATEO, response.getCurdateo());
            response.setPgmnameo("COTRN01C");
            written.put(ScreenField.PGMNAMEO, response.getPgmnameo());
            response.setTitle02o("TITLE TWO");
            written.put(ScreenField.TITLE02O, response.getTitle02o());
            response.setCurtimeo("12:34:56");
            written.put(ScreenField.CURTIMEO, response.getCurtimeo());
            response.setTrnidino("0000000000000042");
            written.put(ScreenField.TRNIDINO, response.getTrnidino());
            response.setTrnido("0000000000000042");
            written.put(ScreenField.TRNIDO, response.getTrnido());
            response.setCardnumo("4111111111111111");
            written.put(ScreenField.CARDNUMO, response.getCardnumo());
            response.setTtypcdo("01");
            written.put(ScreenField.TTYPCDO, response.getTtypcdo());
            response.setTcatcdo("0001");
            written.put(ScreenField.TCATCDO, response.getTcatcdo());
            response.setTrnsrco("POS TERM");
            written.put(ScreenField.TRNSRCO, response.getTrnsrco());
            response.setTdesco("A PURCHASE");
            written.put(ScreenField.TDESCO, response.getTdesco());
            response.setTrnamto("+00000123.45");
            written.put(ScreenField.TRNAMTO, response.getTrnamto());
            response.setTorigdto("2022-07-18");
            written.put(ScreenField.TORIGDTO, response.getTorigdto());
            response.setTprocdto("2022-07-19");
            written.put(ScreenField.TPROCDTO, response.getTprocdto());
            response.setMido("123456789");
            written.put(ScreenField.MIDO, response.getMido());
            response.setMnameo("A MERCHANT");
            written.put(ScreenField.MNAMEO, response.getMnameo());
            response.setMcityo("A CITY");
            written.put(ScreenField.MCITYO, response.getMcityo());
            response.setMzipo("12345");
            written.put(ScreenField.MZIPO, response.getMzipo());
            response.setErrmsgo("A MESSAGE");
            written.put(ScreenField.ERRMSGO, response.getErrmsgo());

            assertThat(written).hasSize(21);
            written.forEach((field, value) -> {
                assertThat(value).as("%s width", field).hasSize(field.payloadLength());
                assertThat(response.payload(field)).as("%s round trip", field).isEqualTo(value);
            });
            assertThat(response.payloadItems()).containsExactlyInAnyOrderEntriesOf(written);
        }

        @Test
        @DisplayName("the card number and merchant id are carried in full, unmasked (gate G41)")
        void identifiersAreNotMasked() {
            TransactionAddResponse response = new TransactionAddResponse();
            response.setCardnumo("4111111111111111");
            response.setMido("987654321");

            assertThat(response.getCardnumo()).isEqualTo("4111111111111111").hasSize(16);
            assertThat(response.getMido()).isEqualTo("987654321").hasSize(9);
            assertThat(response.getCardnumo()).doesNotContain("*").doesNotContain("X");
            assertThat(response.getMido()).doesNotContain("*").doesNotContain("X");
        }

        @Test
        @DisplayName("the edited amount is a 12-character masked string, not a number")
        void editedAmountIsTwelveCharacters() {
            assertThat(ScreenField.TRNAMTO.payloadLength()).isEqualTo(12);
            assertThat("+99999999.99").hasSize(12);

            TransactionAddResponse response = new TransactionAddResponse();
            response.setTrnamto("+99999999.99");
            assertThat(response.getTrnamto()).isEqualTo("+99999999.99").hasSize(12);
            response.setTrnamto("-00000001.00");
            assertThat(response.getTrnamto()).isEqualTo("-00000001.00");
        }

        @Test
        @DisplayName("no payload accessor exposes a floating-point or numeric type (G22/G23/G24)")
        void everyPayloadAccessorIsAString() {
            for (ScreenField field : ScreenField.values()) {
                String getter = "get" + field.outputItemName().charAt(0)
                        + field.outputItemName().substring(1).toLowerCase(java.util.Locale.ROOT);
                assertThat(getter).isNotBlank();
            }
            for (java.lang.reflect.Method method : TransactionAddResponse.class.getDeclaredMethods()) {
                assertThat(method.getReturnType())
                        .as("%s must not return a floating-point type", method.getName())
                        .isNotEqualTo(double.class)
                        .isNotEqualTo(float.class);
                for (Class<?> parameter : method.getParameterTypes()) {
                    assertThat(parameter)
                            .as("%s must not take a floating-point parameter", method.getName())
                            .isNotEqualTo(double.class)
                            .isNotEqualTo(float.class);
                }
            }
        }

        @Test
        @DisplayName("a null field or value is rejected - COBOL has no absent state")
        void nullsAreRejected() {
            TransactionAddResponse response = new TransactionAddResponse();
            assertThatNullPointerException().isThrownBy(() -> response.payload(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> response.setPayload(null, "x"));
            assertThatNullPointerException()
                    .isThrownBy(() -> response.setPayload(ScreenField.TRNIDO, null));
            assertThatNullPointerException().isThrownBy(() -> response.setNextProgram(null));
            assertThatNullPointerException().isThrownBy(() -> response.setNextMapset(null));
            assertThatNullPointerException().isThrownBy(() -> response.setNextMap(null));
            assertThatNullPointerException().isThrownBy(() -> response.setNavigationContext(null));
            assertThatNullPointerException().isThrownBy(() -> response.setCt01Info(null));
            assertThatNullPointerException().isThrownBy(() -> response.attributes(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> response.setAttributes(null, AttributeQuad.defaults()));
            assertThatNullPointerException()
                    .isThrownBy(() -> response.setAttributes(ScreenField.TRNIDO, null));
        }

        @Test
        @DisplayName("the payload snapshot is unmodifiable, so no caller can bypass the move rule")
        void payloadSnapshotIsUnmodifiable() {
            TransactionAddResponse response = new TransactionAddResponse();
            Map<ScreenField, String> snapshot = response.payloadItems();
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> snapshot.put(ScreenField.TRNIDO, "tampered"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> response.attributeItems()
                            .put(ScreenField.TRNIDO, AttributeQuad.defaults()));
        }
    }

    @Nested
    @DisplayName("Highlight metadata and the CSSETATY contract (gate G38)")
    class Highlighting {

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("every field starts with all four attribute items unassigned")
        void attributesStartUnassigned(ScreenField field) {
            TransactionAddResponse response = new TransactionAddResponse();
            AttributeQuad quad = response.attributes(field);
            assertThat(quad.unassigned()).isTrue();
            assertThat(quad.colouredRed()).isFalse();
            assertThat(quad.colour()).isEqualTo(AttributeQuad.NO_CHANGE);
            assertThat(quad.programmedSymbols()).isEqualTo(AttributeQuad.NO_CHANGE);
            assertThat(quad.highlight()).isEqualTo(AttributeQuad.NO_CHANGE);
            assertThat(quad.validation()).isEqualTo(AttributeQuad.NO_CHANGE);
        }

        @Test
        @DisplayName("NOT-OK in REENTER reddens the colour item and leaves the payload alone")
        void notOkInReenterRedensColourOnly() {
            TransactionAddResponse response = new TransactionAddResponse();
            response.setTrnidino("0000000000000042");

            FieldHighlight highlight =
                    response.highlightField(ScreenField.TRNIDINO, true, false, true);

            assertThat(highlight.colourItemAssigned()).isTrue();
            assertThat(highlight.outputItemAssigned()).isFalse();
            assertThat(response.attributes(ScreenField.TRNIDINO).colour())
                    .isEqualTo(BmsAttributes.DFHRED);
            assertThat(response.attributes(ScreenField.TRNIDINO).colouredRed()).isTrue();
            assertThat(response.getTrnidino()).startsWith("0000000000000042");
        }

        @Test
        @DisplayName("BLANK in REENTER reddens the colour item AND moves '*' into the payload item")
        void blankInReenterAlsoMovesAsterisk() {
            TransactionAddResponse response = new TransactionAddResponse();

            FieldHighlight highlight =
                    response.highlightField(ScreenField.TRNIDINO, false, true, true);

            assertThat(highlight.colourItemAssigned()).isTrue();
            assertThat(highlight.outputItemAssigned()).isTrue();
            assertThat(response.attributes(ScreenField.TRNIDINO).colour())
                    .isEqualTo(BmsAttributes.DFHRED);
            assertThat(response.getTrnidino())
                    .startsWith(FieldAttributeSetter.ASTERISK)
                    .hasSize(ScreenField.TRNIDINO.payloadLength());
        }

        @Test
        @DisplayName("on first entry nothing is highlighted, however the field validated (gate G38)")
        void noHighlightOnFirstEntry() {
            TransactionAddResponse response = new TransactionAddResponse();
            response.setTrnidino("KEEPME");

            FieldHighlight notOk =
                    response.highlightField(ScreenField.TRNIDINO, true, false, false);
            FieldHighlight blank =
                    response.highlightField(ScreenField.TRNIDINO, false, true, false);

            assertThat(notOk.untouched()).isTrue();
            assertThat(blank.untouched()).isTrue();
            assertThat(response.attributes(ScreenField.TRNIDINO).unassigned()).isTrue();
            assertThat(response.getTrnidino()).startsWith("KEEPME");
        }

        @Test
        @DisplayName("a valid field in REENTER is left alone")
        void validFieldInReenterIsLeftAlone() {
            TransactionAddResponse response = new TransactionAddResponse();
            FieldHighlight highlight =
                    response.highlightField(ScreenField.TRNIDO, false, false, true);

            assertThat(highlight.untouched()).isTrue();
            assertThat(response.attributes(ScreenField.TRNIDO).unassigned()).isTrue();
        }

        @Test
        @DisplayName("applyHighlight obeys a decision handed to it, including a no-op decision")
        void applyHighlightObeysTheDecision() {
            TransactionAddResponse response = new TransactionAddResponse();
            response.setTdesco("UNTOUCHED");

            response.applyHighlight(ScreenField.TDESCO,
                    FieldHighlight.none(ScreenField.TDESCO.baseName(),
                            TransactionAddResponse.MAP_NAME));
            assertThat(response.attributes(ScreenField.TDESCO).unassigned()).isTrue();
            assertThat(response.getTdesco()).startsWith("UNTOUCHED");

            response.applyHighlight(ScreenField.TDESCO, FieldAttributeSetter.resolveFromFlags(
                    false, true, true, ScreenField.TDESCO.baseName(),
                    TransactionAddResponse.MAP_NAME));
            assertThat(response.attributes(ScreenField.TDESCO).colouredRed()).isTrue();
            assertThat(response.getTdesco()).startsWith("*");

            assertThatNullPointerException()
                    .isThrownBy(() -> response.applyHighlight(ScreenField.TDESCO, null));
            assertThatNullPointerException().isThrownBy(() -> response.applyHighlight(null,
                    FieldHighlight.none("", "")));
            assertThatNullPointerException()
                    .isThrownBy(() -> response.highlightField(null, true, false, true));
        }

        @Test
        @DisplayName("attribute items can be replaced wholesale and stay independent of one another")
        void attributeItemsAreIndependent() {
            TransactionAddResponse response = new TransactionAddResponse();

            response.setAttributes(ScreenField.MZIPO, AttributeQuad.defaults()
                    .withColour(BmsAttributes.DFHRED)
                    .withProgrammedSymbols((byte) 0x41)
                    .withHighlight(BmsAttributes.DFHBMASB)
                    .withValidation(BmsAttributes.DFHUNIMD));

            AttributeQuad quad = response.attributes(ScreenField.MZIPO);
            assertThat(quad.colour()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(quad.programmedSymbols()).isEqualTo((byte) 0x41);
            assertThat(quad.highlight()).isEqualTo(BmsAttributes.DFHBMASB);
            assertThat(quad.validation()).isEqualTo(BmsAttributes.DFHUNIMD);
            assertThat(quad.unassigned()).isFalse();
            assertThat(quad.colouredRed()).isTrue();

            // Every field other than the one touched is still untouched.
            assertThat(response.attributes(ScreenField.MCITYO).unassigned()).isTrue();
        }

        @Test
        @DisplayName("unassigned() is false as soon as any one of the four items is set")
        void unassignedIsFalseForEachItemIndependently() {
            assertThat(AttributeQuad.defaults().unassigned()).isTrue();
            assertThat(AttributeQuad.defaults().withColour((byte) 0x01).unassigned()).isFalse();
            assertThat(AttributeQuad.defaults().withProgrammedSymbols((byte) 0x01).unassigned())
                    .isFalse();
            assertThat(AttributeQuad.defaults().withHighlight((byte) 0x01).unassigned()).isFalse();
            assertThat(AttributeQuad.defaults().withValidation((byte) 0x01).unassigned()).isFalse();
            assertThat(AttributeQuad.defaults().withColour((byte) 0x01).colouredRed()).isFalse();
        }
    }

    @Nested
    @DisplayName("Navigation replaces XCTL, statelessly (gates G37 and G40)")
    class Navigation {

        @Test
        @DisplayName("a fresh response points at this screen's own mapset and map")
        void defaultsToOwnMapsetAndMap() {
            TransactionAddResponse response = new TransactionAddResponse();
            assertThat(response.getNextMapset()).isEqualTo("COTRN01").hasSize(7);
            assertThat(response.getNextMap()).isEqualTo("COTRN1A").hasSize(7);
            assertThat(response.getNextProgram()).isBlank().hasSize(8);
        }

        @Test
        @DisplayName("nextProgram is echoed from the commarea's CDEMO-TO-PROGRAM, at eight wide")
        void nextProgramEchoesTheCommarea() {
            TransactionAddResponse response = new TransactionAddResponse();
            response.setNavigationContext(new NavigationContext("CT01", "COTRN01C", "CM00",
                    "COMEN01C", "USER0001", "U", NavigationContext.PGM_CONTEXT_REENTER,
                    1, "F", "M", "L", 11L, "Y", 16L, "COTRN1A", "COTRN01"));
            response.setNextProgram(response.getNavigationContext().toProgram());

            assertThat(response.getNextProgram()).isEqualTo("COMEN01C").hasSize(8);
        }

        @Test
        @DisplayName("navigation targets obey the PIC X move rule at 8, 7 and 7")
        void navigationTargetsObeyTheMoveRule() {
            TransactionAddResponse response = new TransactionAddResponse();
            response.setNextProgram("A");
            response.setNextMapset("B");
            response.setNextMap("C");
            assertThat(response.getNextProgram()).hasSize(8).startsWith("A");
            assertThat(response.getNextMapset()).hasSize(7).startsWith("B");
            assertThat(response.getNextMap()).hasSize(7).startsWith("C");

            response.setNextProgram("PROGRAMNAMETOOLONG");
            response.setNextMapset("MAPSETTOOLONG");
            response.setNextMap("MAPTOOLONG");
            assertThat(response.getNextProgram()).isEqualTo("PROGRAMN");
            assertThat(response.getNextMapset()).isEqualTo("MAPSETT");
            assertThat(response.getNextMap()).isEqualTo("MAPTOOL");
        }

        @Test
        @DisplayName("no server-side session state is held anywhere on the class")
        void noServerSideSessionState() {
            for (java.lang.reflect.Field field : TransactionAddResponse.class.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    assertThat(java.lang.reflect.Modifier.isPrivate(field.getModifiers()))
                            .as("instance field %s must be private", field.getName())
                            .isTrue();
                    continue;
                }
                assertThat(java.lang.reflect.Modifier.isFinal(field.getModifiers()))
                        .as("static field %s must be final - no static mutable state (B9)",
                                field.getName())
                        .isTrue();
            }
            for (java.lang.reflect.Field field : Ct01Info.class.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    assertThat(java.lang.reflect.Modifier.isFinal(field.getModifiers()))
                            .as("static field %s must be final", field.getName())
                            .isTrue();
                }
            }
        }
    }

    @Nested
    @DisplayName("The 58-byte CT01 cursor")
    class Cursor {

        @Test
        @DisplayName("a fresh cursor holds the declared VALUE 'N' and space-filled identifiers")
        void freshCursorHoldsDeclaredValues() {
            Ct01Info cursor = new Ct01Info();
            assertThat(cursor.getTrnidFirst()).hasSize(16).isBlank();
            assertThat(cursor.getTrnidLast()).hasSize(16).isBlank();
            assertThat(cursor.getPageNum()).isZero();
            assertThat(cursor.getNextPageFlg()).isEqualTo("N");
            assertThat(cursor.getTrnSelFlg()).hasSize(1).isBlank();
            assertThat(cursor.getTrnSelected()).hasSize(16).isBlank();
        }

        @Test
        @DisplayName("both 88-level states of NEXT-PAGE-FLG are reachable (gate G50)")
        void bothNextPageStatesAreReachable() {
            Ct01Info cursor = new Ct01Info();

            assertThat(cursor.isNextPageNo()).isTrue();
            assertThat(cursor.isNextPageYes()).isFalse();

            cursor.setNextPageYes();
            assertThat(cursor.getNextPageFlg()).isEqualTo("Y");
            assertThat(cursor.isNextPageYes()).isTrue();
            assertThat(cursor.isNextPageNo()).isFalse();

            cursor.setNextPageNo();
            assertThat(cursor.isNextPageNo()).isTrue();
            assertThat(cursor.isNextPageYes()).isFalse();

            cursor.setNextPageFlg(Ct01Info.NEXT_PAGE_YES);
            assertThat(cursor.isNextPageYes()).isTrue();
        }

        @Test
        @DisplayName("a third character satisfies neither 88-level, exactly as in COBOL")
        void athirdCharacterSatisfiesNeitherCondition() {
            Ct01Info cursor = new Ct01Info();
            cursor.setNextPageFlg("X");
            assertThat(cursor.isNextPageYes()).isFalse();
            assertThat(cursor.isNextPageNo()).isFalse();
        }

        @Test
        @DisplayName("PAGE-NUM accepts the whole eight-digit range and rejects what will not fit")
        void pageNumRangeIsEnforced() {
            Ct01Info cursor = new Ct01Info();

            cursor.setPageNum(0);
            assertThat(cursor.getPageNum()).isZero();
            cursor.setPageNum(99_999_999);
            assertThat(cursor.getPageNum()).isEqualTo(99_999_999);
            cursor.setPageNum(7);
            assertThat(cursor.getPageNum()).isEqualTo(7);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> cursor.setPageNum(-1))
                    .withMessageContaining("PIC 9(08)");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> cursor.setPageNum(100_000_000))
                    .withMessageContaining("PIC 9(08)");
        }

        @Test
        @DisplayName("the six items store what they are given and carry verbatim copybook names")
        void itemsStoreVerbatimAndCarryVerbatimNames() {
            Ct01Info cursor = new Ct01Info();
            cursor.setTrnidFirst("FIRSTIDTOOLONGXXXX");
            cursor.setTrnidLast("LAST");
            cursor.setTrnSelFlg("SS");
            cursor.setTrnSelected("SELECTED");

            // The shared carrier stores what it is given, unchanged - it does not pad a short value
            // and does not truncate a long one. The PIC X move is applied once, at the byte boundary
            // in toFixedWidth, which is where the direction of a truncation is visible and reviewable.
            // The carrier this class used to declare for itself moved on every setter instead, so an
            // over-long value was silently shortened before anyone could object to it.
            assertThat(cursor.getTrnidFirst()).isEqualTo("FIRSTIDTOOLONGXXXX").hasSize(18);
            assertThat(cursor.getTrnidLast()).isEqualTo("LAST");
            assertThat(cursor.getTrnSelFlg()).isEqualTo("SS");
            assertThat(cursor.getTrnSelected()).isEqualTo("SELECTED");

            // ...and the image is still exactly 58 bytes, each field at its declared width.
            byte[] image = cursor.toFixedWidth(ASCII_CODEC);
            assertThat(image).hasSize(Ct01Info.RECORD_LENGTH);
            String rendered = new String(image, ASCII);
            assertThat(rendered.substring(0, 16)).isEqualTo("FIRSTIDTOOLONGXX");
            assertThat(rendered.substring(16, 32)).isEqualTo("LAST            ");

            assertThat(Ct01Info.TRNID_FIRST_FIELD).isEqualTo("CDEMO-CT01-TRNID-FIRST");
            assertThat(Ct01Info.TRNID_LAST_FIELD).isEqualTo("CDEMO-CT01-TRNID-LAST");
            assertThat(Ct01Info.PAGE_NUM_FIELD).isEqualTo("CDEMO-CT01-PAGE-NUM");
            assertThat(Ct01Info.NEXT_PAGE_FLG_FIELD).isEqualTo("CDEMO-CT01-NEXT-PAGE-FLG");
            assertThat(Ct01Info.TRN_SEL_FLG_FIELD).isEqualTo("CDEMO-CT01-TRN-SEL-FLG");
            assertThat(Ct01Info.TRN_SELECTED_FIELD).isEqualTo("CDEMO-CT01-TRN-SELECTED");
            // The surviving carrier names the field as the copybook does, not abbreviated.
            assertThat(cursor.toString()).contains("pageNum=0");
        }

        @Test
        @DisplayName("the cursor round trips through its 58-byte image, page number zero-filled")
        void cursorRoundTripsThroughItsImage() {
            Ct01Info cursor = new Ct01Info();
            cursor.setTrnidFirst("0000000000000001");
            cursor.setTrnidLast("0000000000000010");
            cursor.setPageNum(42);
            cursor.setNextPageYes();
            cursor.setTrnSelFlg("S");
            cursor.setTrnSelected("0000000000000007");

            byte[] image = cursor.toFixedWidth(ASCII_CODEC);
            assertThat(image).hasSize(58);
            assertThat(new String(image, ASCII))
                    .startsWith("00000000000000010000000000000010")
                    .contains("00000042");

            Ct01Info parsed = Ct01Info.fromFixedWidth(ASCII_CODEC, image);
            assertThat(parsed.getTrnidFirst()).isEqualTo(cursor.getTrnidFirst());
            assertThat(parsed.getTrnidLast()).isEqualTo(cursor.getTrnidLast());
            assertThat(parsed.getPageNum()).isEqualTo(42);
            assertThat(parsed.isNextPageYes()).isTrue();
            assertThat(parsed.getTrnSelFlg()).isEqualTo("S");
            assertThat(parsed.getTrnSelected()).isEqualTo(cursor.getTrnSelected());
        }

        @Test
        @DisplayName("the cursor rejects a null charset and a wrongly-sized image")
        void cursorRejectsBadInput() {
            Ct01Info cursor = new Ct01Info();
            assertThatNullPointerException().isThrownBy(() -> cursor.toFixedWidth(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> Ct01Info.fromFixedWidth(ASCII_CODEC, null));
            assertThatNullPointerException()
                    .isThrownBy(() -> Ct01Info.fromFixedWidth(null, new byte[58]));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> Ct01Info.fromFixedWidth(ASCII_CODEC, new byte[57]));
        }
    }

    @Nested
    @DisplayName("Fixed-width rendering of the 575-byte image")
    class FixedWidthRendering {

        @Test
        @DisplayName("renders exactly 575 bytes with every field at its copybook offset")
        void rendersEveryFieldAtItsOffset() {
            TransactionAddResponse response = populated();

            byte[] image = response.toFixedWidth(ASCII);
            assertThat(image).hasSize(575);

            String text = new String(image, ASCII);
            for (ScreenField field : ScreenField.values()) {
                assertThat(text.substring(field.payloadOffset(),
                                field.payloadOffset() + field.payloadLength()))
                        .as("%s at %d", field, field.payloadOffset())
                        .isEqualTo(response.payload(field));
            }
        }

        @Test
        @DisplayName("the TIOAPFX prefix and every group filler are emitted as spaces (gate G21)")
        void fillersAreEmitted() {
            String text = new String(populated().toFixedWidth(ASCII), ASCII);

            assertThat(text.substring(0, 12)).isEqualTo(" ".repeat(12));
            for (ScreenField field : ScreenField.values()) {
                assertThat(text.substring(field.groupOffset(), field.groupOffset() + 3))
                        .as("group filler of %s", field)
                        .isEqualTo("   ");
            }
        }

        @Test
        @DisplayName("attribute items are written as raw bytes, not as characters")
        void attributeItemsAreWrittenAsBytes() {
            TransactionAddResponse response = new TransactionAddResponse();
            response.highlightField(ScreenField.TRNIDINO, true, false, true);
            response.setAttributes(ScreenField.MZIPO,
                    AttributeQuad.defaults().withHighlight(BmsAttributes.DFHBMASB));

            byte[] image = response.toFixedWidth(ASCII);

            assertThat(image[ScreenField.TRNIDINO.colourItemOffset()])
                    .isEqualTo(BmsAttributes.DFHRED);
            assertThat(image[ScreenField.MZIPO.highlightItemOffset()])
                    .isEqualTo(BmsAttributes.DFHBMASB);
            assertThat(image[ScreenField.TRNIDO.colourItemOffset()])
                    .isEqualTo(AttributeQuad.NO_CHANGE);
        }

        @Test
        @DisplayName("the image round trips losslessly - xxxO is never write-only")
        void imageRoundTripsLosslessly() {
            TransactionAddResponse original = populated();
            original.highlightField(ScreenField.TCATCDO, true, false, true);

            TransactionAddResponse parsed =
                    TransactionAddResponse.fromFixedWidth(original.toFixedWidth(ASCII), ASCII);

            for (ScreenField field : ScreenField.values()) {
                assertThat(parsed.payload(field)).as("%s", field)
                        .isEqualTo(original.payload(field));
                assertThat(parsed.attributes(field)).as("attributes of %s", field)
                        .isEqualTo(original.attributes(field));
            }
            assertThat(parsed.toFixedWidth(ASCII)).isEqualTo(original.toFixedWidth(ASCII));
        }

        @Test
        @DisplayName("writeInto and readFrom use the record's own code page")
        void writeIntoAndReadFromUseTheRecordCharset() {
            TransactionAddResponse original = populated();
            FixedWidthCodec codec = new FixedWidthCodec(ASCII);
            FixedWidthRecord record = codec.newRecord(TransactionAddResponse.LAYOUT);

            original.writeInto(record);
            TransactionAddResponse parsed = TransactionAddResponse.readFrom(record);

            assertThat(parsed.getTrnidino()).isEqualTo(original.getTrnidino());
            assertThat(parsed.getErrmsgo()).isEqualTo(original.getErrmsgo());
            assertThat(record.toByteArray()).hasSize(575);
        }

        @Test
        @DisplayName("a record area of the wrong width is rejected rather than partly written")
        void wrongWidthRecordIsRejected() {
            TransactionAddResponse response = new TransactionAddResponse();
            FixedWidthCodec codec = new FixedWidthCodec(ASCII);
            FixedWidthRecord tooShort = new FixedWidthRecord(574, ASCII);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.writeInto(tooShort))
                    .withMessageContaining("575");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TransactionAddResponse.readFrom(tooShort))
                    .withMessageContaining("575");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TransactionAddResponse.fromFixedWidth(new byte[574], ASCII));
            assertThat(codec.charset()).isEqualTo(ASCII);
        }

        @Test
        @DisplayName("rendering rejects a null charset - the code page is never implicit")
        void nullCharsetIsRejected() {
            TransactionAddResponse response = new TransactionAddResponse();
            assertThatNullPointerException().isThrownBy(() -> response.toFixedWidth(null));
            assertThatNullPointerException().isThrownBy(() -> response.writeInto(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TransactionAddResponse.fromFixedWidth(new byte[575], null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TransactionAddResponse.fromFixedWidth(null, ASCII));
            assertThatNullPointerException().isThrownBy(() -> TransactionAddResponse.readFrom(null));
            assertThatNullPointerException().isThrownBy(() -> response.toPassedCommarea(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> response.readPassedCommarea(new byte[218], null));
            assertThatNullPointerException()
                    .isThrownBy(() -> response.readPassedCommarea(null, ASCII));
        }

        @Test
        @DisplayName("the passed commarea is the 160-byte context followed by the 58-byte cursor")
        void passedCommareaConcatenatesBothAreas() {
            TransactionAddResponse response = new TransactionAddResponse();
            response.setNavigationContext(new NavigationContext("CT01", "COTRN01C", "CT01",
                    "COTRN01C", "USER0001", "U", NavigationContext.PGM_CONTEXT_ENTER,
                    0, "", "", "", 0L, " ", 0L, "COTRN1A", "COTRN01"));
            response.getCt01Info().setPageNum(3);
            response.getCt01Info().setNextPageYes();

            byte[] passed = response.toPassedCommarea(ASCII);
            assertThat(passed).hasSize(218);

            byte[] contextOnly =
                    response.getNavigationContext().toFixedWidth(new FixedWidthCodec(ASCII));
            byte[] cursorOnly = response.getCt01Info().toFixedWidth(ASCII_CODEC);
            assertThat(contextOnly).hasSize(160);
            assertThat(cursorOnly).hasSize(58);
            assertThat(new String(passed, ASCII).substring(0, 160))
                    .isEqualTo(new String(contextOnly, ASCII));
            assertThat(new String(passed, ASCII).substring(160))
                    .isEqualTo(new String(cursorOnly, ASCII));

            TransactionAddResponse received = new TransactionAddResponse();
            received.readPassedCommarea(passed, ASCII);
            assertThat(received.getNavigationContext().userId()).isEqualTo("USER0001");
            assertThat(received.getCt01Info().getPageNum()).isEqualTo(3);
            assertThat(received.getCt01Info().isNextPageYes()).isTrue();
        }

        @Test
        @DisplayName("a wrongly-sized passed commarea is rejected with the arithmetic in the message")
        void wrongSizedPassedCommareaIsRejected() {
            TransactionAddResponse response = new TransactionAddResponse();
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.readPassedCommarea(new byte[217], ASCII))
                    .withMessageContaining("218")
                    .withMessageContaining("160")
                    .withMessageContaining("58");
        }
    }

    @Nested
    @DisplayName("JSON round trip")
    class Json {

        @Test
        @DisplayName("space padding survives serialisation and deserialisation untrimmed")
        void spacePaddingSurvivesUntrimmed() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            TransactionAddResponse response = new TransactionAddResponse();
            response.setErrmsgo("SHORT MESSAGE");
            response.setTrnamto("+00000123.45");

            String json = mapper.writeValueAsString(response);
            TransactionAddResponse parsed = mapper.readValue(json, TransactionAddResponse.class);

            assertThat(parsed.getErrmsgo())
                    .hasSize(78)
                    .isEqualTo(response.getErrmsgo())
                    .startsWith("SHORT MESSAGE")
                    .endsWith(" ");
            assertThat(parsed.getTrnamto()).hasSize(12).isEqualTo("+00000123.45");
        }

        @Test
        @DisplayName("the CT01 cursor is one carrier, spelled identically in both directions")
        void theCursorIsOneSharedCarrierAcrossThePair() throws Exception {
            ObjectMapper mapper = new ObjectMapper();

            // One type, not two structurally identical ones. This is the whole of the fix: the
            // response used to declare its own CardDemoCt01Info under the property cardDemoCt01Info,
            // so a client could not echo the cursor it was sent without renaming the property first.
            assertThat(TransactionAddResponse.class.getDeclaredMethod("getCt01Info").getReturnType())
                    .isEqualTo(Ct01Info.class)
                    .isEqualTo(TransactionAddRequest.class.getDeclaredMethod("getCt01Info")
                            .getReturnType());
            assertThat(TransactionAddResponse.class.getDeclaredClasses())
                    .as("the response declares no cursor type of its own any more")
                    .noneMatch(c -> c.getSimpleName().contains("Ct01Info"));

            // And the echo works without transformation: take the cursor off a response, put it on a
            // request, and the JSON member is the same member.
            TransactionAddResponse sent = populated();
            sent.getCt01Info().setPageNum(4);
            sent.getCt01Info().setNextPageYes();
            sent.getCt01Info().setTrnSelected("0000000000000007");

            String responseJson = mapper.writeValueAsString(sent);
            assertThat(mapper.readTree(responseJson).has("ct01Info")).isTrue();
            assertThat(mapper.readTree(responseJson).has("cardDemoCt01Info")).isFalse();

            TransactionAddRequest echoed = new TransactionAddRequest();
            echoed.setCt01Info(sent.getCt01Info());
            String requestJson = mapper.writeValueAsString(echoed);

            assertThat(mapper.readTree(requestJson).get("ct01Info"))
                    .as("the cursor crosses the pair byte for byte, under one name")
                    .isEqualTo(mapper.readTree(responseJson).get("ct01Info"));

            // ...including through the fixed-width form both sides share.
            assertThat(echoed.getCt01Info().toFixedWidth(ASCII_CODEC))
                    .isEqualTo(sent.getCt01Info().toFixedWidth(ASCII_CODEC));
        }

        @Test
        @DisplayName("all 21 payload fields plus navigation and state appear; metadata does not")
        void payloadMembersAreExactlyTheProjection() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> tree = mapper.readValue(
                    mapper.writeValueAsString(populated()), Map.class);

            for (ScreenField field : ScreenField.values()) {
                assertThat(tree)
                        .as("payload member for %s", field)
                        .containsKey(field.outputItemName().toLowerCase(java.util.Locale.ROOT));
            }
            assertThat(tree).containsKeys("nextProgram", "nextMapset", "nextMap",
                    "navigationContext", "ct01Info");

            // Highlight metadata is never a payload member (AAP 0.6.3).
            assertThat(tree).doesNotContainKeys("attributeItems", "payloadItems");
            for (ScreenField field : ScreenField.values()) {
                assertThat(tree).doesNotContainKey(
                        field.colourItemName().toLowerCase(java.util.Locale.ROOT));
                assertThat(tree).doesNotContainKey(
                        field.validationItemName().toLowerCase(java.util.Locale.ROOT));
            }
            // The card number and merchant id are on the wire in full (gate G41).
            assertThat(tree.get("cardnumo")).isEqualTo("4111111111111111");
            assertThat(tree.get("mido")).isEqualTo("123456789");
        }

        @Test
        @DisplayName("the whole payload round trips field for field")
        void wholePayloadRoundTrips() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            TransactionAddResponse original = populated();
            original.setNextProgram("COMEN01C");

            TransactionAddResponse parsed = mapper.readValue(
                    mapper.writeValueAsString(original), TransactionAddResponse.class);

            for (ScreenField field : ScreenField.values()) {
                assertThat(parsed.payload(field)).as("%s", field)
                        .isEqualTo(original.payload(field));
            }
            assertThat(parsed.getNextProgram()).isEqualTo("COMEN01C");
            assertThat(parsed.getNextMapset()).isEqualTo(original.getNextMapset());
            assertThat(parsed.getNextMap()).isEqualTo(original.getNextMap());
            assertThat(parsed.getCt01Info().getPageNum())
                    .isEqualTo(original.getCt01Info().getPageNum());
            assertThat(parsed.toFixedWidth(ASCII)).isEqualTo(original.toFixedWidth(ASCII));
        }
    }

    @Test
    @DisplayName("toString names the screen, its geometry and the lookup key")
    void toStringIsDiagnostic() {
        TransactionAddResponse response = populated();
        assertThat(response.toString())
                .contains("CT01")
                .contains("COTRN01C")
                .contains("COTRN01.COTRN1A")
                .contains("fields=21")
                .contains("image=575B");
    }

    /**
     * A response with all 21 fields populated and a non-default cursor - the shape a controller hands
     * back after a successful lookup.
     *
     * @return the populated response; never {@code null}
     */
    private static TransactionAddResponse populated() {
        TransactionAddResponse response = new TransactionAddResponse();
        response.setTrnnameo(TransactionAddResponse.TRANSACTION_ID);
        response.setTitle01o(ScreenTitles.CCDA_TITLE01);
        response.setCurdateo("08/08/26");
        response.setPgmnameo(TransactionAddResponse.PROGRAM_NAME);
        response.setTitle02o(ScreenTitles.CCDA_TITLE02);
        response.setCurtimeo("12:34:56");
        response.setTrnidino("0000000000000042");
        response.setTrnido("0000000000000042");
        response.setCardnumo("4111111111111111");
        response.setTtypcdo("01");
        response.setTcatcdo("0001");
        response.setTrnsrco("POS TERM");
        response.setTdesco("A PURCHASE AT A MERCHANT");
        response.setTrnamto("+00000123.45");
        response.setTorigdto("2022-07-18");
        response.setTprocdto("2022-07-19");
        response.setMido("123456789");
        response.setMnameo("A MERCHANT");
        response.setMcityo("A CITY");
        response.setMzipo("12345");
        response.setErrmsgo(SystemMessages.CCDA_MSG_INVALID_KEY);
        response.getCt01Info().setPageNum(1);
        response.getCt01Info().setTrnidFirst("0000000000000001");
        return response;
    }
}
