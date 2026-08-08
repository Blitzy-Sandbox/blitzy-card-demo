package com.vsergeychik.carddemo.transaction.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldHighlight;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldValidationState;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.transaction.dto.ReportRequestResponse.FieldAttributes;
import com.vsergeychik.carddemo.transaction.dto.ReportRequestResponse.ScreenField;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Parity tests for {@link ReportRequestResponse}, the projection of {@code 01 CORPT0AO REDEFINES
 * CORPT0AI} in {@code app/cpy-bms/CORPT00.CPY} and of the name-labelled {@code DFHMDF} fields of
 * {@code app/bms/CORPT00.bms}.
 *
 * <p>The expected values are not transcribed into this file where they can be <em>read from the
 * sources instead</em>. Three of the suites below parse {@code app/cpy-bms/CORPT00.CPY} and
 * {@code app/bms/CORPT00.bms} at run time and diff the class against them, so the copybook and the
 * mapset stay the authority and a retyping slip in either the class or the test cannot pass unnoticed.
 *
 * <p>The suite is organised around the properties that determine the implementation, so a failure
 * names a translation decision rather than merely a value:
 *
 * <ol>
 *   <li>Seventeen payload items - the name-labelled {@code DFHMDF} fields - and no more. The other 25
 *       {@code DFHMDF} entries are screen literals that CICS never transmits.</li>
 *   <li>The group image is <strong>337</strong> bytes: {@code 12} for the {@code TIOAPFX} prefix,
 *       {@code 17 x 7} for the attribute prefixes and {@code 206} for the payload.</li>
 *   <li>{@code xxxI} and {@code xxxO} are storage <strong>aliases</strong>, so the inbound and
 *       outbound projections must carry identical names and identical widths, and a round trip must be
 *       lossless.</li>
 *   <li>The {@code xxxC}/{@code xxxP}/{@code xxxH}/{@code xxxV} quad is metadata and never a JSON
 *       payload member, even though it occupies real bytes.</li>
 *   <li>The {@code CSSETATY} highlight is reachable only in {@code CDEMO-PGM-REENTER} state, and the
 *       {@code '*'} only in the {@code BLANK} case (gate <strong>G38</strong>).</li>
 *   <li>{@code EXEC CICS XCTL} becomes a {@code nextProgram} field and nothing else - no session, no
 *       forward, no static state (gates <strong>G40</strong> and <strong>G37</strong>).</li>
 * </ol>
 */
@DisplayName("ReportRequestResponse - CORPT00 CORPT0AO, the outbound projection of CORPT00C")
class ReportRequestResponseTest {

    /** Both code pages are named explicitly; neither is ever the platform default. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** The EBCDIC code page of the datasets under {@code app/data/EBCDIC}. */
    private static final Charset EBCDIC = Charset.forName("IBM037");

    /** {@code app/java/../cpy-bms/CORPT00.CPY}, the authoritative symbolic map. */
    private static final Path COPYBOOK = Paths.get("..", "cpy-bms", "CORPT00.CPY");

    /** {@code app/java/../bms/CORPT00.bms}, the authoritative field definitions. */
    private static final Path MAPSET = Paths.get("..", "bms", "CORPT00.bms");

    /** The class under test, for the source-level checks that no reflection can express. */
    private static final Path SOURCE = Paths.get("src", "main", "java", "com", "vsergeychik",
            "carddemo", "transaction", "dto", "ReportRequestResponse.java");

    // =============================================================================================
    // Helpers that read the sources, so the sources remain the oracle.
    // =============================================================================================

    /**
     * Extracts the {@code 02 xxx<suffix> PIC X(n)} items of one {@code 01} group of the copybook, in
     * declaration order.
     *
     * @param groupHeader the group name, {@code CORPT0AI} or {@code CORPT0AO}
     * @param suffix      the item suffix, {@code I} or {@code O}
     * @return item name to declared width, in copybook order
     * @throws IOException if the copybook cannot be read
     */
    private static Map<String, Integer> parseGroupItems(String groupHeader, String suffix)
            throws IOException {
        Pattern item =
                Pattern.compile("^\\s*02\\s+([A-Z0-9]+" + suffix + ")\\s+PIC\\s+X\\((\\d+)\\)\\.");
        Map<String, Integer> items = new LinkedHashMap<>();
        boolean inGroup = false;
        for (String line : Files.readAllLines(COPYBOOK, ASCII)) {
            if (line.contains("01  " + groupHeader)) {
                inGroup = true;
                continue;
            }
            if (inGroup && line.contains("01  ") && !line.contains(groupHeader)) {
                break;
            }
            if (!inGroup) {
                continue;
            }
            Matcher matcher = item.matcher(line);
            if (matcher.find()) {
                items.put(matcher.group(1), Integer.parseInt(matcher.group(2)));
            }
        }
        return items;
    }

    /**
     * Extracts the name-labelled {@code DFHMDF} fields of the mapset and their {@code LENGTH=}.
     * Unlabelled entries - the screen literals - are skipped, which is exactly the distinction that
     * decides what becomes a payload member.
     *
     * @return field label to declared length, in mapset order
     * @throws IOException if the mapset cannot be read
     */
    private static Map<String, Integer> parseLabelledFields() throws IOException {
        Pattern label = Pattern.compile("^([A-Z0-9]+)\\s+DFHMDF");
        Pattern length = Pattern.compile("LENGTH=(\\d+)");
        Map<String, Integer> labelled = new LinkedHashMap<>();
        String current = null;
        for (String line : Files.readAllLines(MAPSET, ASCII)) {
            Matcher labelMatcher = label.matcher(line);
            if (labelMatcher.find()) {
                current = labelMatcher.group(1);
            } else if (line.trim().startsWith("DFHMDF")) {
                current = null;
            }
            if (current != null) {
                Matcher lengthMatcher = length.matcher(line);
                if (lengthMatcher.find() && !labelled.containsKey(current)) {
                    labelled.put(current, Integer.parseInt(lengthMatcher.group(1)));
                }
            }
        }
        return labelled;
    }

    /** Removes block and line comments, so a forbidden-construct scan sees code and not prose. */
    private static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        boolean inBlock = false;
        for (String line : source.lines().toList()) {
            String working = line;
            if (inBlock) {
                int close = working.indexOf("*/");
                if (close < 0) {
                    continue;
                }
                working = working.substring(close + 2);
                inBlock = false;
            }
            int open = working.indexOf("/*");
            while (open >= 0) {
                int close = working.indexOf("*/", open + 2);
                if (close < 0) {
                    working = working.substring(0, open);
                    inBlock = true;
                    break;
                }
                working = working.substring(0, open) + working.substring(close + 2);
                open = working.indexOf("/*");
            }
            int lineComment = working.indexOf("//");
            if (lineComment >= 0) {
                working = working.substring(0, lineComment);
            }
            out.append(working).append('\n');
        }
        return out.toString();
    }

    /** A response with the header populated from a pinned instant, as every send does. */
    private static ReportRequestResponse populated() {
        ReportRequestResponse response = new ReportRequestResponse();
        response.populateHeaderInfo(DateHeader.of(new FixedWidthCodec(ASCII),
                LocalDateTime.of(2022, 8, 22, 17, 2, 43)));
        return response;
    }

    // =============================================================================================

    @Nested
    @DisplayName("Gate G9 - every payload field traces to a DFHMDF and every width to a PICTURE")
    class ProjectionMatchesTheSources {

        @Test
        @DisplayName("the 17 ScreenField constants are the xxxO items, verbatim and in order")
        void screenFieldsAreTheCopybookItems() throws IOException {
            Map<String, Integer> outbound = parseGroupItems("CORPT0AO", "O");

            assertThat(outbound).as("xxxO items in CORPT00.CPY").hasSize(17);
            assertThat(ReportRequestResponse.SCREEN_FIELD_COUNT).isEqualTo(17);
            assertThat(ScreenField.values()).hasSize(17);

            List<String> fromEnum = new ArrayList<>();
            for (ScreenField field : ScreenField.values()) {
                fromEnum.add(field.payloadItemName());
            }
            assertThat(fromEnum)
                    .as("names verbatim AND declaration order, which is load-bearing")
                    .containsExactlyElementsOf(new ArrayList<>(outbound.keySet()));

            for (ScreenField field : ScreenField.values()) {
                assertThat(field.payloadLength())
                        .as("PICTURE width of " + field.payloadItemName())
                        .isEqualTo(outbound.get(field.payloadItemName()));
            }
        }

        @Test
        @DisplayName("exactly 17 DFHMDF entries are name-labelled, and every LENGTH= equals its PIC")
        void bmsLengthsAgreeWithPictures() throws IOException {
            Map<String, Integer> labelled = parseLabelledFields();

            assertThat(labelled).as("name-labelled DFHMDF fields").hasSize(17);
            for (ScreenField field : ScreenField.values()) {
                assertThat(labelled.get(field.baseName()))
                        .as("BMS LENGTH= of " + field.baseName())
                        .isEqualTo(field.payloadLength());
            }
        }

        @Test
        @DisplayName("xxxI and xxxO alias one another, so Request and Response are field-identical")
        void inboundAndOutboundViewsAgree() throws IOException {
            Map<String, Integer> inbound = parseGroupItems("CORPT0AI", "I");
            Map<String, Integer> outbound = parseGroupItems("CORPT0AO", "O");

            assertThat(inbound).hasSize(17);
            assertThat(outbound).hasSize(17);

            List<String> inboundBases = new ArrayList<>();
            inbound.keySet().forEach(n -> inboundBases.add(n.substring(0, n.length() - 1)));
            List<String> outboundBases = new ArrayList<>();
            outbound.keySet().forEach(n -> outboundBases.add(n.substring(0, n.length() - 1)));

            assertThat(outboundBases).containsExactlyElementsOf(inboundBases);
            for (String base : inboundBases) {
                assertThat(outbound.get(base + "O"))
                        .as("width of " + base + " must be the same in both views")
                        .isEqualTo(inbound.get(base + "I"));
            }

            List<String> enumBases = new ArrayList<>();
            for (ScreenField field : ScreenField.values()) {
                enumBases.add(field.baseName());
            }
            assertThat(enumBases).containsExactlyElementsOf(outboundBases);
        }

        @Test
        @DisplayName("the provenance constants are the ones the sources declare")
        void provenanceConstants() {
            assertThat(ReportRequestResponse.TRANSACTION_ID).isEqualTo("CR00");
            assertThat(ReportRequestResponse.PROGRAM_NAME).isEqualTo("CORPT00C").hasSize(8);
            assertThat(ReportRequestResponse.MAPSET_NAME).isEqualTo("CORPT00").hasSize(7);
            assertThat(ReportRequestResponse.MAP_NAME).isEqualTo("CORPT0A").hasSize(7);
            assertThat(ReportRequestResponse.SYMBOLIC_MAP_GROUP)
                    .isEqualTo(ReportRequestResponse.MAP_NAME + "O");
            assertThat(ReportRequestResponse.DEFAULT_NEXT_PROGRAM).isEqualTo("COSGN00C");
            assertThat(ReportRequestResponse.PF3_NEXT_PROGRAM).isEqualTo("COMEN01C");
        }
    }

    @Nested
    @DisplayName("Byte geometry - 12 + 17 x 7 + 206 = 337")
    class ByteGeometry {

        @Test
        @DisplayName("the payload widths sum to 206 and the group image is 337 bytes")
        void totals() {
            int payloadSum = 0;
            for (ScreenField field : ScreenField.values()) {
                payloadSum += field.payloadLength();
            }
            assertThat(payloadSum).isEqualTo(206);
            assertThat(ReportRequestResponse.PAYLOAD_WIDTH_TOTAL).isEqualTo(payloadSum);
            assertThat(ReportRequestResponse.TIOAPFX_PREFIX_LENGTH).isEqualTo(12);
            assertThat(ReportRequestResponse.ATTRIBUTE_FILLER_LENGTH).isEqualTo(3);
            assertThat(ReportRequestResponse.COLOUR_ITEM_LENGTH).isEqualTo(1);
            assertThat(ReportRequestResponse.PS_ITEM_LENGTH).isEqualTo(1);
            assertThat(ReportRequestResponse.HILIGHT_ITEM_LENGTH).isEqualTo(1);
            assertThat(ReportRequestResponse.VALIDN_ITEM_LENGTH).isEqualTo(1);
            assertThat(ReportRequestResponse.FIELD_PREFIX_LENGTH).isEqualTo(7);
            assertThat(ReportRequestResponse.ATTRIBUTE_PREFIX_TOTAL).isEqualTo(119);
            assertThat(ReportRequestResponse.RECORD_LENGTH).isEqualTo(337);
        }

        @Test
        @DisplayName("LAYOUT declares all 103 spans and its storage sums to exactly 337")
        void layoutIsTheAssertion() {
            assertThat(ReportRequestResponse.LAYOUT.recordLength()).isEqualTo(337);
            assertThat(ReportRequestResponse.LAYOUT.spans()).hasSize(1 + 17 * 6);
            assertThat(ReportRequestResponse.LAYOUT.redefinitions()).isEmpty();

            int declared = 0;
            for (FieldSpan span : ReportRequestResponse.LAYOUT.storageSpans()) {
                declared += span.length();
            }
            assertThat(declared).isEqualTo(ReportRequestResponse.RECORD_LENGTH);

            assertThat(ReportRequestResponse.TIOAPFX_PREFIX_SPAN.offset()).isZero();
            assertThat(ReportRequestResponse.TIOAPFX_PREFIX_SPAN.length()).isEqualTo(12);
            for (ScreenField field : ScreenField.values()) {
                assertThat(ReportRequestResponse.LAYOUT.hasSpan(field.payloadItemName())).isTrue();
                assertThat(ReportRequestResponse.LAYOUT.hasSpan(field.colourItemName())).isTrue();
            }
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("each field's six spans follow the copybook's order and the 7-byte stride")
        void spanStride(ScreenField field) {
            int attr = field.attributePrefixOffset();
            assertThat(field.attributeFillerSpan().offset()).isEqualTo(attr);
            assertThat(field.attributeFillerSpan().length()).isEqualTo(3);
            assertThat(field.colourSpan().offset()).isEqualTo(attr + 3);
            assertThat(field.psSpan().offset()).isEqualTo(attr + 4);
            assertThat(field.hilightSpan().offset()).isEqualTo(attr + 5);
            assertThat(field.validnSpan().offset()).isEqualTo(attr + 6);
            assertThat(field.payloadSpan().offset()).isEqualTo(attr + 7).isEqualTo(
                    field.payloadOffset());
            assertThat(field.colourSpan().length()).isEqualTo(1);
            assertThat(field.psSpan().length()).isEqualTo(1);
            assertThat(field.hilightSpan().length()).isEqualTo(1);
            assertThat(field.validnSpan().length()).isEqualTo(1);
            assertThat(field.payloadSpan().length()).isEqualTo(field.payloadLength());
            assertThat(field.spans()).hasSize(6);
            assertThat(field.colourItemName()).isEqualTo(field.baseName() + "C");
            assertThat(field.payloadItemName()).isEqualTo(field.baseName() + "O");
        }

        @Test
        @DisplayName("the offsets are the copybook's, and the last field ends at byte 337")
        void absoluteOffsets() {
            int[] expectedAttr = {12, 23, 70, 85, 100, 147, 162, 170, 178, 186, 195, 204, 215, 224,
                233, 244, 252};
            int[] expectedPayload = {19, 30, 77, 92, 107, 154, 169, 177, 185, 193, 202, 211, 222,
                231, 240, 251, 259};
            ScreenField[] fields = ScreenField.values();
            for (int index = 0; index < fields.length; index++) {
                assertThat(fields[index].attributePrefixOffset())
                        .as("attribute prefix offset of " + fields[index].baseName())
                        .isEqualTo(expectedAttr[index]);
                assertThat(fields[index].payloadOffset())
                        .as("payload offset of " + fields[index].baseName())
                        .isEqualTo(expectedPayload[index]);
            }
            assertThat(ScreenField.ERRMSG.payloadSpan().endOffsetExclusive())
                    .isEqualTo(ReportRequestResponse.RECORD_LENGTH);
        }
    }

    @Nested
    @DisplayName("PIC X semantics - padded on write, truncated on the right, never trimmed on read")
    class WidthDiscipline {

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("a fresh field is SPACES at its declared width")
        void defaultsToSpaces(ScreenField field) {
            ReportRequestResponse response = new ReportRequestResponse();
            assertThat(response.payloadValue(field))
                    .hasSize(field.payloadLength())
                    .isEqualTo(" ".repeat(field.payloadLength()));
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("a short value is padded on the right and an over-long one keeps its leading bytes")
        void padsAndTruncates(ScreenField field) {
            ReportRequestResponse response = new ReportRequestResponse();

            response.setPayloadValue(field, "A");
            assertThat(response.payloadValue(field)).hasSize(field.payloadLength());
            assertThat(response.payloadValue(field).charAt(0)).isEqualTo('A');
            assertThat(response.payloadValue(field).substring(1))
                    .isEqualTo(" ".repeat(field.payloadLength() - 1));

            response.setPayloadValue(field, "Z".repeat(field.payloadLength()) + "OVERFLOW");
            assertThat(response.payloadValue(field))
                    .as("COBOL fills a PIC X receiver from the left")
                    .isEqualTo("Z".repeat(field.payloadLength()));

            // An exact-width value is returned unchanged.
            String exact = "e".repeat(field.payloadLength());
            response.setPayloadValue(field, exact);
            assertThat(response.payloadValue(field)).isEqualTo(exact);
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("null is refused, naming the copybook item and what to pass instead")
        void nullIsRefused(ScreenField field) {
            ReportRequestResponse response = new ReportRequestResponse();
            assertThatNullPointerException()
                    .isThrownBy(() -> response.setPayloadValue(field, null))
                    .withMessageContaining(field.payloadItemName())
                    .withMessageContaining("COBOL has no null");
        }

        @Test
        @DisplayName("the named accessors and the ScreenField accessor are the same storage")
        void namedAccessorsAgreeWithGeneric() {
            ReportRequestResponse r = new ReportRequestResponse();
            r.setTrnnameo("CR00");
            r.setTitle01o("title one");
            r.setCurdateo("01/02/03");
            r.setPgmnameo("CORPT00C");
            r.setTitle02o("title two");
            r.setCurtimeo("11:22:33");
            r.setMonthlyo("M");
            r.setYearlyo("Y");
            r.setCustomo("C");
            r.setSdtmmo("01");
            r.setSdtddo("02");
            r.setSdtyyyyo("2022");
            r.setEdtmmo("03");
            r.setEdtddo("04");
            r.setEdtyyyyo("2023");
            r.setConfirmo("Y");
            r.setErrmsgo("a message");

            assertThat(r.getTrnnameo()).isEqualTo(r.payloadValue(ScreenField.TRNNAME));
            assertThat(r.getTitle01o()).isEqualTo(r.payloadValue(ScreenField.TITLE01));
            assertThat(r.getCurdateo()).isEqualTo(r.payloadValue(ScreenField.CURDATE));
            assertThat(r.getPgmnameo()).isEqualTo(r.payloadValue(ScreenField.PGMNAME));
            assertThat(r.getTitle02o()).isEqualTo(r.payloadValue(ScreenField.TITLE02));
            assertThat(r.getCurtimeo()).isEqualTo(r.payloadValue(ScreenField.CURTIME));
            assertThat(r.getMonthlyo()).isEqualTo(r.payloadValue(ScreenField.MONTHLY));
            assertThat(r.getYearlyo()).isEqualTo(r.payloadValue(ScreenField.YEARLY));
            assertThat(r.getCustomo()).isEqualTo(r.payloadValue(ScreenField.CUSTOM));
            assertThat(r.getSdtmmo()).isEqualTo(r.payloadValue(ScreenField.SDTMM));
            assertThat(r.getSdtddo()).isEqualTo(r.payloadValue(ScreenField.SDTDD));
            assertThat(r.getSdtyyyyo()).isEqualTo(r.payloadValue(ScreenField.SDTYYYY));
            assertThat(r.getEdtmmo()).isEqualTo(r.payloadValue(ScreenField.EDTMM));
            assertThat(r.getEdtddo()).isEqualTo(r.payloadValue(ScreenField.EDTDD));
            assertThat(r.getEdtyyyyo()).isEqualTo(r.payloadValue(ScreenField.EDTYYYY));
            assertThat(r.getConfirmo()).isEqualTo(r.payloadValue(ScreenField.CONFIRM));
            assertThat(r.getErrmsgo()).isEqualTo(r.payloadValue(ScreenField.ERRMSG));
        }

        @Test
        @DisplayName("a null field selector is refused by every accessor that takes one")
        void nullSelectorIsRefused() {
            ReportRequestResponse response = new ReportRequestResponse();
            assertThatNullPointerException().isThrownBy(() -> response.payloadValue(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> response.setPayloadValue(null, "x"));
            assertThatNullPointerException().isThrownBy(() -> response.attributesOf(null));
            assertThatNullPointerException().isThrownBy(() -> response.colourAttribute(null));
            assertThatNullPointerException().isThrownBy(() -> response.psAttribute(null));
            assertThatNullPointerException().isThrownBy(() -> response.hilightAttribute(null));
            assertThatNullPointerException().isThrownBy(() -> response.validnAttribute(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> response.setColourAttribute(null, (byte) 1));
            assertThatNullPointerException()
                    .isThrownBy(() -> response.setPsAttribute(null, (byte) 1));
            assertThatNullPointerException()
                    .isThrownBy(() -> response.setHilightAttribute(null, (byte) 1));
            assertThatNullPointerException()
                    .isThrownBy(() -> response.setValidnAttribute(null, (byte) 1));
        }

        @ParameterizedTest
        @ValueSource(ints = {0, -1, -78})
        @DisplayName("a figurative constant cannot be built at a width no copybook item could have")
        void figurativeConstantsRejectImpossibleWidths(int width) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> ReportRequestResponse.spaces(width))
                    .withMessageContaining("SPACES");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> ReportRequestResponse.lowValues(width))
                    .withMessageContaining("LOW-VALUES");
        }

        @Test
        @DisplayName("SPACES and LOW-VALUES are different bytes, and both are available")
        void figurativeConstants() {
            assertThat(ReportRequestResponse.spaces(4)).isEqualTo("    ");
            assertThat(ReportRequestResponse.lowValues(4)).isEqualTo("\u0000\u0000\u0000\u0000");
            assertThat(ReportRequestResponse.spaces(1))
                    .isNotEqualTo(ReportRequestResponse.lowValues(1));
        }
    }

    @Nested
    @DisplayName("The group moves CORPT00C performs, each named after its source line")
    class GroupMoves {

        @Test
        @DisplayName("CORPT00C:179 MOVE LOW-VALUES TO CORPT0AO clears the payload AND all 68 attributes")
        void moveLowValuesReachesEveryByte() {
            ReportRequestResponse response = populated();
            response.setErrmsgc(BmsAttributes.DFHGREEN);
            response.setHilightAttribute(ScreenField.MONTHLY, BmsAttributes.DFHUNDLN);
            response.setPsAttribute(ScreenField.CONFIRM, (byte) 0x11);
            response.setValidnAttribute(ScreenField.CONFIRM, (byte) 0x22);

            response.moveLowValuesToMapGroup();

            for (ScreenField field : ScreenField.values()) {
                assertThat(response.payloadValue(field))
                        .as(field.payloadItemName() + " after a group MOVE of LOW-VALUES")
                        .isEqualTo("\u0000".repeat(field.payloadLength()));
                assertThat(response.attributesOf(field))
                        .as("a group MOVE reaches the attribute items too")
                        .isEqualTo(FieldAttributes.UNSET);
                assertThat(response.attributesOf(field).allUnset()).isTrue();
            }
        }

        @Test
        @DisplayName("CORPT00C:636-645 INITIALIZE-ALL-FIELDS clears ten items and leaves seven alone")
        void initializeAllFieldsTouchesOnlyItsTenItems() {
            ReportRequestResponse response = new ReportRequestResponse();
            response.moveLowValuesToMapGroup();

            response.initializeAllFields();

            List<ScreenField> initialised = List.of(ScreenField.MONTHLY, ScreenField.YEARLY,
                    ScreenField.CUSTOM, ScreenField.SDTMM, ScreenField.SDTDD, ScreenField.SDTYYYY,
                    ScreenField.EDTMM, ScreenField.EDTDD, ScreenField.EDTYYYY, ScreenField.CONFIRM);
            assertThat(initialised).as("the paragraph names exactly ten items").hasSize(10);
            for (ScreenField field : initialised) {
                assertThat(response.payloadValue(field))
                        .as("INITIALIZE sets an alphanumeric item to SPACES, not LOW-VALUES")
                        .isEqualTo(" ".repeat(field.payloadLength()));
            }
            for (ScreenField untouched : List.of(ScreenField.TRNNAME, ScreenField.TITLE01,
                    ScreenField.CURDATE, ScreenField.PGMNAME, ScreenField.TITLE02,
                    ScreenField.CURTIME, ScreenField.ERRMSG)) {
                assertThat(response.payloadValue(untouched))
                        .as(untouched.payloadItemName() + " is not named by the paragraph")
                        .isEqualTo("\u0000".repeat(untouched.payloadLength()));
            }
        }

        @Test
        @DisplayName("CORPT00C:169-170 and :193 - the error line is cleared, then carries the standard text")
        void errorLineMoves() {
            ReportRequestResponse response = new ReportRequestResponse();
            response.setErrmsgo("stale text from the previous send");

            response.moveSpacesToErrmsgo();
            assertThat(response.getErrmsgo()).isEqualTo(" ".repeat(78));

            response.moveInvalidKeyMessageToErrmsgo();
            assertThat(response.getErrmsgo())
                    .hasSize(78)
                    .startsWith(SystemMessages.CCDA_MSG_INVALID_KEY)
                    .as("a 50-byte message lands left-justified in a 78-byte field")
                    .endsWith(" ".repeat(78 - SystemMessages.CCDA_MSG_INVALID_KEY.length()));
        }

        @Test
        @DisplayName("CORPT00C:448 MOVE DFHGREEN TO ERRMSGC OF CORPT0AO")
        void dfhgreenIntoTheErrorLineColourItem() {
            ReportRequestResponse response = new ReportRequestResponse();
            assertThat(response.getErrmsgc()).isEqualTo(ReportRequestResponse.ATTRIBUTE_UNSET);

            response.moveDfhgreenToErrmsgc();

            assertThat(response.getErrmsgc()).isEqualTo(BmsAttributes.DFHGREEN);
            assertThat(response.colourAttribute(ScreenField.ERRMSG))
                    .isEqualTo(BmsAttributes.DFHGREEN);
            assertThat(response.attributesOf(ScreenField.ERRMSG).allUnset()).isFalse();

            response.setErrmsgc(BmsAttributes.DFHRED);
            assertThat(response.getErrmsgc()).isEqualTo(BmsAttributes.DFHRED);
        }

        @Test
        @DisplayName("CORPT00C:613-628 POPULATE-HEADER-INFO fills the six header fields")
        void populateHeaderInfo() {
            ReportRequestResponse response = populated();

            assertThat(response.getTitle01o()).isEqualTo(ScreenTitles.CCDA_TITLE01).hasSize(40);
            assertThat(response.getTitle02o()).isEqualTo(ScreenTitles.CCDA_TITLE02).hasSize(40);
            assertThat(response.getTrnnameo()).isEqualTo("CR00").hasSize(4);
            assertThat(response.getPgmnameo()).isEqualTo("CORPT00C").hasSize(8);
            assertThat(response.getCurdateo()).isEqualTo("08/22/22").hasSize(8);
            assertThat(response.getCurtimeo()).isEqualTo("17:02:43").hasSize(8);

            // The paragraph does not touch the eleven data fields.
            assertThat(response.getMonthlyo()).isEqualTo(" ");
            assertThat(response.getConfirmo()).isEqualTo(" ");
            assertThat(response.getErrmsgo()).isEqualTo(" ".repeat(78));

            assertThatNullPointerException()
                    .isThrownBy(() -> response.populateHeaderInfo(null))
                    .withMessageContaining("DateHeader");
        }

        @Test
        @DisplayName("moveSpacesToAllFields restores every field to SPACES")
        void moveSpacesToAllFields() {
            ReportRequestResponse response = new ReportRequestResponse();
            response.moveLowValuesToMapGroup();

            response.moveSpacesToAllFields();

            for (ScreenField field : ScreenField.values()) {
                assertThat(response.payloadValue(field))
                        .isEqualTo(" ".repeat(field.payloadLength()));
            }
        }
    }

    @Nested
    @DisplayName("Gate G38 - the CSSETATY highlight applies only in REENTER state")
    class Highlighting {

        @ParameterizedTest
        @EnumSource(FieldValidationState.class)
        @DisplayName("on first entry nothing is highlighted, whatever the validation state")
        void unreachableOnEnter(FieldValidationState state) {
            ReportRequestResponse response = new ReportRequestResponse();
            response.setSdtmmo("07");

            FieldHighlight highlight = FieldAttributeSetter.resolve(state, false,
                    ScreenField.SDTMM.baseName(), ReportRequestResponse.MAP_NAME);

            assertThat(response.applyHighlight(ScreenField.SDTMM, highlight))
                    .as("CSSETATY's outer IF requires CDEMO-PGM-REENTER").isFalse();
            assertThat(response.colourAttribute(ScreenField.SDTMM))
                    .isEqualTo(ReportRequestResponse.ATTRIBUTE_UNSET);
            assertThat(response.getSdtmmo()).isEqualTo("07");
        }

        @Test
        @DisplayName("in REENTER state NOT-OK reddens xxxC and leaves xxxO untouched")
        void notOkOnReenter() {
            ReportRequestResponse response = new ReportRequestResponse();
            response.setSdtmmo("99");

            FieldHighlight highlight = FieldAttributeSetter.resolveFromFlags(true, false, true,
                    ScreenField.SDTMM.baseName(), ReportRequestResponse.MAP_NAME);

            assertThat(response.applyHighlight(ScreenField.SDTMM, highlight)).isTrue();
            assertThat(response.colourAttribute(ScreenField.SDTMM)).isEqualTo(BmsAttributes.DFHRED);
            assertThat(response.getSdtmmo())
                    .as("the '*' move is nested inside the BLANK test, which did not hold")
                    .isEqualTo("99");
            assertThat(response.psAttribute(ScreenField.SDTMM))
                    .isEqualTo(ReportRequestResponse.ATTRIBUTE_UNSET);
            assertThat(response.hilightAttribute(ScreenField.SDTMM))
                    .isEqualTo(ReportRequestResponse.ATTRIBUTE_UNSET);
            assertThat(response.validnAttribute(ScreenField.SDTMM))
                    .isEqualTo(ReportRequestResponse.ATTRIBUTE_UNSET);
        }

        @Test
        @DisplayName("in REENTER state BLANK reddens xxxC and writes '*' into xxxO")
        void blankOnReenter() {
            ReportRequestResponse response = new ReportRequestResponse();

            FieldHighlight highlight = FieldAttributeSetter.resolveFromFlags(false, true, true,
                    ScreenField.CONFIRM.baseName(), ReportRequestResponse.MAP_NAME);

            assertThat(response.applyHighlight(ScreenField.CONFIRM, highlight)).isTrue();
            assertThat(response.colourAttribute(ScreenField.CONFIRM))
                    .isEqualTo(BmsAttributes.DFHRED);
            assertThat(response.getConfirmo()).isEqualTo(FieldAttributeSetter.ASTERISK);
        }

        @Test
        @DisplayName("the '*' is fitted to the receiving field's width, not written raw")
        void asteriskIsFittedToTheField() {
            ReportRequestResponse response = new ReportRequestResponse();
            FieldHighlight highlight = FieldAttributeSetter.resolveFromFlags(false, true, true,
                    ScreenField.SDTYYYY.baseName(), ReportRequestResponse.MAP_NAME);

            assertThat(response.applyHighlight(ScreenField.SDTYYYY, highlight)).isTrue();
            assertThat(response.getSdtyyyyo()).isEqualTo("*   ").hasSize(4);
        }

        @Test
        @DisplayName("an anonymous highlight carries no identity and is accepted for any field")
        void anonymousHighlight() {
            ReportRequestResponse response = new ReportRequestResponse();
            FieldHighlight anonymous = FieldAttributeSetter.resolveFromFlags(true, false, true);

            assertThat(anonymous.screenFieldPrefix()).isEmpty();
            assertThat(anonymous.mapName()).isEmpty();
            assertThat(response.applyHighlight(ScreenField.MONTHLY, anonymous)).isTrue();
            assertThat(response.colourAttribute(ScreenField.MONTHLY))
                    .isEqualTo(BmsAttributes.DFHRED);
        }

        @Test
        @DisplayName("a highlight resolved for another field is refused, not silently misapplied")
        void wrongFieldIsRefused() {
            ReportRequestResponse response = new ReportRequestResponse();
            FieldHighlight forMonthly = FieldAttributeSetter.resolveFromFlags(true, false, true,
                    ScreenField.MONTHLY.baseName(), ReportRequestResponse.MAP_NAME);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.applyHighlight(ScreenField.SDTMM, forMonthly))
                    .withMessageContaining("MONTHLY")
                    .withMessageContaining("SDTMM");
            assertThat(response.colourAttribute(ScreenField.SDTMM))
                    .isEqualTo(ReportRequestResponse.ATTRIBUTE_UNSET);
        }

        @Test
        @DisplayName("a highlight resolved for another map is refused")
        void wrongMapIsRefused() {
            ReportRequestResponse response = new ReportRequestResponse();
            FieldHighlight forAnotherScreen = FieldAttributeSetter.resolveFromFlags(true, false,
                    true, ScreenField.SDTMM.baseName(), "CACTUPA");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.applyHighlight(ScreenField.SDTMM, forAnotherScreen))
                    .withMessageContaining("CACTUPA")
                    .withMessageContaining(ReportRequestResponse.MAP_NAME);
        }

        @Test
        @DisplayName("null arguments are refused")
        void nullsRefused() {
            ReportRequestResponse response = new ReportRequestResponse();
            FieldHighlight highlight = FieldAttributeSetter.resolveFromFlags(true, false, true);

            assertThatNullPointerException()
                    .isThrownBy(() -> response.applyHighlight(null, highlight));
            assertThatNullPointerException()
                    .isThrownBy(() -> response.applyHighlight(ScreenField.SDTMM, null));
        }

        @Test
        @DisplayName("an untouched highlight resolved for the right field is still a no-op")
        void untouchedButIdentified() {
            ReportRequestResponse response = new ReportRequestResponse();
            FieldHighlight none = FieldHighlight.none(ScreenField.ERRMSG.baseName(),
                    ReportRequestResponse.MAP_NAME);

            assertThat(none.untouched()).isTrue();
            assertThat(response.applyHighlight(ScreenField.ERRMSG, none)).isFalse();
            assertThat(response.getErrmsgc()).isEqualTo(ReportRequestResponse.ATTRIBUTE_UNSET);
        }
    }

    @Nested
    @DisplayName("The xxxC/xxxP/xxxH/xxxV quad - metadata, and independently settable")
    class AttributeQuad {

        @Test
        @DisplayName("UNSET is all X'00', which is also the device default")
        void unsetQuad() {
            assertThat(FieldAttributes.UNSET.allUnset()).isTrue();
            assertThat(FieldAttributes.UNSET.colour()).isEqualTo(BmsAttributes.DFHDFCOL);
            assertThat(ReportRequestResponse.ATTRIBUTE_UNSET).isEqualTo((byte) 0x00);
        }

        @Test
        @DisplayName("each wither changes one item and leaves the other three alone")
        void withersAreIndependent() {
            FieldAttributes red = FieldAttributes.UNSET.withColour(BmsAttributes.DFHRED);
            assertThat(red.colour()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(red.ps()).isEqualTo((byte) 0x00);
            assertThat(red.hilight()).isEqualTo((byte) 0x00);
            assertThat(red.validn()).isEqualTo((byte) 0x00);

            FieldAttributes ps = FieldAttributes.UNSET.withPs((byte) 0x11);
            assertThat(ps.ps()).isEqualTo((byte) 0x11);
            assertThat(ps.colour()).isEqualTo((byte) 0x00);
            assertThat(ps.hilight()).isEqualTo((byte) 0x00);
            assertThat(ps.validn()).isEqualTo((byte) 0x00);

            FieldAttributes hilight = FieldAttributes.UNSET.withHilight(BmsAttributes.DFHUNDLN);
            assertThat(hilight.hilight()).isEqualTo(BmsAttributes.DFHUNDLN);
            assertThat(hilight.colour()).isEqualTo((byte) 0x00);
            assertThat(hilight.ps()).isEqualTo((byte) 0x00);
            assertThat(hilight.validn()).isEqualTo((byte) 0x00);

            FieldAttributes validn = FieldAttributes.UNSET.withValidn((byte) 0x22);
            assertThat(validn.validn()).isEqualTo((byte) 0x22);
            assertThat(validn.colour()).isEqualTo((byte) 0x00);
            assertThat(validn.ps()).isEqualTo((byte) 0x00);
            assertThat(validn.hilight()).isEqualTo((byte) 0x00);
        }

        @Test
        @DisplayName("allUnset() is false as soon as any single item is set")
        void allUnsetDetectsEachItem() {
            assertThat(FieldAttributes.UNSET.withColour((byte) 1).allUnset()).isFalse();
            assertThat(FieldAttributes.UNSET.withPs((byte) 1).allUnset()).isFalse();
            assertThat(FieldAttributes.UNSET.withHilight((byte) 1).allUnset()).isFalse();
            assertThat(FieldAttributes.UNSET.withValidn((byte) 1).allUnset()).isFalse();
            assertThat(FieldAttributes.UNSET.withColour((byte) 0).allUnset()).isTrue();
        }

        @Test
        @DisplayName("describe() names each byte by its CICS mnemonic")
        void describeUsesMnemonics() {
            FieldAttributes quad = FieldAttributes.UNSET.withColour(BmsAttributes.DFHRED)
                    .withHilight(BmsAttributes.DFHUNDLN);
            assertThat(quad.describe()).contains("DFHRED").contains("C=").contains("P=")
                    .contains("H=").contains("V=");
        }

        @Test
        @DisplayName("the quad is a value: equal quads are equal and hash alike")
        void valueSemantics() {
            FieldAttributes one = FieldAttributes.UNSET.withColour(BmsAttributes.DFHRED);
            FieldAttributes same = new FieldAttributes(BmsAttributes.DFHRED, (byte) 0, (byte) 0,
                    (byte) 0);
            assertThat(one).isEqualTo(same).hasSameHashCodeAs(same);
            assertThat(one).isNotEqualTo(FieldAttributes.UNSET);
        }

        @Test
        @DisplayName("a quad can be replaced wholesale, and null is refused")
        void wholesaleReplacement() {
            ReportRequestResponse response = new ReportRequestResponse();
            FieldAttributes quad = new FieldAttributes(BmsAttributes.DFHRED, (byte) 0x11,
                    BmsAttributes.DFHUNDLN, (byte) 0x22);

            response.setAttributes(ScreenField.ERRMSG, quad);
            assertThat(response.attributesOf(ScreenField.ERRMSG)).isEqualTo(quad);
            assertThat(response.colourAttribute(ScreenField.ERRMSG))
                    .isEqualTo(BmsAttributes.DFHRED);
            assertThat(response.psAttribute(ScreenField.ERRMSG)).isEqualTo((byte) 0x11);
            assertThat(response.hilightAttribute(ScreenField.ERRMSG))
                    .isEqualTo(BmsAttributes.DFHUNDLN);
            assertThat(response.validnAttribute(ScreenField.ERRMSG)).isEqualTo((byte) 0x22);

            assertThatNullPointerException()
                    .isThrownBy(() -> response.setAttributes(ScreenField.ERRMSG, null))
                    .withMessageContaining("UNSET");
        }

        @Test
        @DisplayName("every field's quad is present from construction, so no lookup can be absent")
        void everyFieldHasAQuad() {
            ReportRequestResponse response = new ReportRequestResponse();
            for (ScreenField field : ScreenField.values()) {
                assertThat(response.attributesOf(field)).isNotNull()
                        .isEqualTo(FieldAttributes.UNSET);
            }
        }
    }

    @Nested
    @DisplayName("Gates G40 and G37 - XCTL becomes a field, and nothing is held server-side")
    class Navigation {

        @Test
        @DisplayName("a fresh response names its own map and mapset at X(7) and no program yet")
        void defaults() {
            ReportRequestResponse response = new ReportRequestResponse();
            assertThat(response.getNextMapset()).isEqualTo("CORPT00").hasSize(7);
            assertThat(response.getNextMap()).isEqualTo("CORPT0A").hasSize(7);
            assertThat(response.getNextProgram()).isEqualTo(" ".repeat(8)).hasSize(8);
            assertThat(response.getNavigationContext()).isEqualTo(NavigationContext.empty());
        }

        @Test
        @DisplayName("nextProgram is echoed from CDEMO-TO-PROGRAM, the field XCTL names")
        void echoesTheCommareaTarget() {
            ReportRequestResponse response = new ReportRequestResponse();
            NavigationContext context = NavigationContext.empty()
                    .withToProgram(ReportRequestResponse.PF3_NEXT_PROGRAM)
                    .withUserId("ADMIN001")
                    .withUserTypeAdmin()
                    .withPgmReenter();

            response.echoNavigation(context);

            assertThat(response.getNextProgram()).isEqualTo("COMEN01C").hasSize(8);
            assertThat(response.getNextMapset()).isEqualTo(ReportRequestResponse.MAPSET_NAME);
            assertThat(response.getNextMap()).isEqualTo(ReportRequestResponse.MAP_NAME);
            assertThat(response.getNavigationContext()).isSameAs(context);
            assertThat(response.getNavigationContext().isReenter()).isTrue();
            assertThat(response.getNavigationContext().isAdmin()).isTrue();
        }

        @Test
        @DisplayName("the commarea is echoed at exactly 160 bytes and is never widened")
        void commareaIsNotWidened() {
            assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(160);
            NavigationContext context = NavigationContext.empty().withToProgram("COSGN00C");
            assertThat(context.toFixedWidth(new FixedWidthCodec(ASCII))).hasSize(160);

            ReportRequestResponse response = new ReportRequestResponse();
            response.setNavigationContext(context);
            assertThat(response.getNavigationContext().toFixedWidth(new FixedWidthCodec(ASCII)))
                    .hasSize(160);
        }

        @Test
        @DisplayName("the navigation trio is width-disciplined and refuses null")
        void trioWidths() {
            ReportRequestResponse response = new ReportRequestResponse();

            response.setNextProgram("X");
            assertThat(response.getNextProgram()).isEqualTo("X       ").hasSize(8);
            response.setNextMapset("TOOLONGMAPSET");
            assertThat(response.getNextMapset()).isEqualTo("TOOLONG").hasSize(7);
            response.setNextMap("M");
            assertThat(response.getNextMap()).isEqualTo("M      ").hasSize(7);

            assertThatNullPointerException().isThrownBy(() -> response.setNextProgram(null));
            assertThatNullPointerException().isThrownBy(() -> response.setNextMapset(null));
            assertThatNullPointerException().isThrownBy(() -> response.setNextMap(null));
            assertThatNullPointerException().isThrownBy(() -> response.setNavigationContext(null))
                    .withMessageContaining("empty()");
        }

        @Test
        @DisplayName("CORPT00C:542 - the LOW-VALUES-or-SPACES rule is a whole-group comparison")
        void spacesOrLowValuesRule() {
            assertThat(ReportRequestResponse.isSpacesOrLowValues("        ")).isTrue();
            assertThat(ReportRequestResponse.isSpacesOrLowValues("\u0000\u0000\u0000")).isTrue();
            assertThat(ReportRequestResponse.isSpacesOrLowValues("")).isTrue();
            assertThat(ReportRequestResponse.isSpacesOrLowValues(null)).isTrue();
            assertThat(ReportRequestResponse.isSpacesOrLowValues("COSGN00C")).isFalse();
            assertThat(ReportRequestResponse.isSpacesOrLowValues("  X     ")).isFalse();
            assertThat(ReportRequestResponse.isSpacesOrLowValues(" \u0000"))
                    .as("a mixed value satisfies neither figurative constant").isFalse();
        }

        @Test
        @DisplayName("Gate G53 - every static field is final and two instances share nothing")
        void noStaticMutableState() {
            for (var field : ReportRequestResponse.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("static field " + field.getName() + " must be final").isTrue();
                }
            }
            for (var field : ScreenField.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("static field " + field.getName() + " must be final").isTrue();
                }
            }

            ReportRequestResponse first = new ReportRequestResponse();
            ReportRequestResponse second = new ReportRequestResponse();
            first.setErrmsgo("only on the first");
            first.setColourAttribute(ScreenField.ERRMSG, BmsAttributes.DFHRED);

            assertThat(second.getErrmsgo()).isEqualTo(" ".repeat(78));
            assertThat(second.colourAttribute(ScreenField.ERRMSG))
                    .isEqualTo(ReportRequestResponse.ATTRIBUTE_UNSET);
        }

        @Test
        @DisplayName("Gates G22, G37, G52 - no forbidden construct appears in the code")
        void noForbiddenConstructs() throws IOException {
            String text = Files.readString(SOURCE, ASCII);
            String code = stripComments(text);

            assertThat(code).as("code with comments stripped, so the Javadoc's own explanation "
                    + "of what is absent cannot be mistaken for the thing itself")
                    .doesNotContain("HttpSession")
                    .doesNotContain("SessionAttributes")
                    .doesNotContain("lombok")
                    .doesNotContain("mapstruct")
                    .doesNotContain("JsonNaming")
                    .doesNotContain("JsonInclude")
                    .doesNotContain("ObjectMapper")
                    .doesNotContain("BigDecimal")
                    .doesNotContain("RoundingMode")
                    .doesNotContain("HALF_UP")
                    .doesNotContain("HALF_EVEN");
            assertThat(code).doesNotContainPattern("\\bdouble\\b")
                    .doesNotContainPattern("\\bfloat\\b");

            for (String line : text.lines().toList()) {
                if (line.startsWith("import ")) {
                    assertThat(line).as("gate G52 forbids wildcard imports")
                            .doesNotContain(".*;");
                }
            }

            for (Class<?> type : List.of(ReportRequestResponse.class, ScreenField.class,
                    FieldAttributes.class)) {
                for (var field : type.getDeclaredFields()) {
                    assertThat(field.getType()).as(type.getSimpleName() + "." + field.getName())
                            .isNotIn(double.class, float.class, Double.class, Float.class);
                }
                for (var method : type.getDeclaredMethods()) {
                    assertThat(method.getReturnType())
                            .as(type.getSimpleName() + "." + method.getName() + " return type")
                            .isNotIn(double.class, float.class, Double.class, Float.class);
                    for (Class<?> parameter : method.getParameterTypes()) {
                        assertThat(parameter)
                                .as(type.getSimpleName() + "." + method.getName() + " parameter")
                                .isNotIn(double.class, float.class, Double.class, Float.class);
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("The JSON body - 17 payload members plus the four stateless carriers, and no metadata")
    class JsonContract {

        private final ObjectMapper mapper = new ObjectMapper();

        @Test
        @DisplayName("the property set is exactly the 17 xxxO members, the trio and the commarea")
        void propertySet() {
            ObjectNode tree = mapper.valueToTree(new ReportRequestResponse());
            List<String> names = new ArrayList<>();
            tree.fieldNames().forEachRemaining(names::add);

            List<String> expected = List.of("trnnameo", "title01o", "curdateo", "pgmnameo",
                    "title02o", "curtimeo", "monthlyo", "yearlyo", "customo", "sdtmmo", "sdtddo",
                    "sdtyyyyo", "edtmmo", "edtddo", "edtyyyyo", "confirmo", "errmsgo",
                    "nextProgram", "nextMapset", "nextMap", "navigationContext");
            assertThat(names).containsExactlyInAnyOrderElementsOf(expected);
            assertThat(expected).hasSize(21);
        }

        @Test
        @DisplayName("no attribute item and no diagnostic map leaks into the body")
        void metadataIsExcluded() {
            ObjectNode tree = mapper.valueToTree(new ReportRequestResponse());
            List<String> names = new ArrayList<>();
            tree.fieldNames().forEachRemaining(names::add);

            assertThat(names)
                    .noneMatch(name -> name.toLowerCase(Locale.ROOT).equals("errmsgc"))
                    .noneMatch(name -> name.equals("fieldImages"))
                    .noneMatch(name -> name.equals("attributeImages"))
                    .noneMatch(name -> name.toLowerCase(Locale.ROOT).endsWith("attributes"));
            for (ScreenField field : ScreenField.values()) {
                assertThat(names).noneMatch(name ->
                        name.equalsIgnoreCase(field.baseName() + "C")
                                || name.equalsIgnoreCase(field.baseName() + "P")
                                || name.equalsIgnoreCase(field.baseName() + "H")
                                || name.equalsIgnoreCase(field.baseName() + "V"));
            }
        }

        @Test
        @DisplayName("a 78-character space-padded error line survives a round trip UNTRIMMED")
        void roundTripDoesNotTrim() throws Exception {
            ReportRequestResponse response = populated();
            response.setErrmsgo("Monthly report submitted for printing ...");
            response.setConfirmo("Y");
            response.setMonthlyo("X");
            response.echoNavigation(NavigationContext.empty().withToProgram("COMEN01C"));

            String json = mapper.writeValueAsString(response);
            ReportRequestResponse back = mapper.readValue(json, ReportRequestResponse.class);

            assertThat(back.getErrmsgo()).hasSize(78).isEqualTo(response.getErrmsgo());
            assertThat(back.getErrmsgo()).isNotEqualTo(back.getErrmsgo().trim());
            assertThat(back.getTitle01o()).isEqualTo(ScreenTitles.CCDA_TITLE01);
            assertThat(back.getCurdateo()).isEqualTo("08/22/22");
            assertThat(back.getCurtimeo()).isEqualTo("17:02:43");
            assertThat(back.getNextProgram()).isEqualTo("COMEN01C");
            assertThat(back.getNavigationContext()).isEqualTo(response.getNavigationContext());
            assertThat(back.fieldImages()).isEqualTo(response.fieldImages());
            assertThat(back).isEqualTo(response);
        }

        @Test
        @DisplayName("an all-spaces error line appears verbatim in the JSON text and returns equal")
        void allSpacesRoundTrip() throws Exception {
            ReportRequestResponse response = new ReportRequestResponse();
            response.setErrmsgo(" ".repeat(78));

            String json = mapper.writeValueAsString(response);
            assertThat(json).contains("\"errmsgo\":\"" + " ".repeat(78) + "\"");

            ReportRequestResponse back = mapper.readValue(json, ReportRequestResponse.class);
            assertThat(back.getErrmsgo()).isEqualTo(" ".repeat(78)).hasSize(78);
            assertThat(back).isEqualTo(response);
        }

        @Test
        @DisplayName("every one of the 17 members survives a round trip at its declared width")
        void allSeventeenRoundTrip() throws Exception {
            ReportRequestResponse response = new ReportRequestResponse();
            for (ScreenField field : ScreenField.values()) {
                response.setPayloadValue(field,
                        "v".repeat(Math.min(field.payloadLength(), field.payloadLength())));
            }
            ReportRequestResponse back = mapper.readValue(
                    mapper.writeValueAsString(response), ReportRequestResponse.class);
            for (ScreenField field : ScreenField.values()) {
                assertThat(back.payloadValue(field))
                        .as(field.payloadItemName())
                        .hasSize(field.payloadLength())
                        .isEqualTo(response.payloadValue(field));
            }
        }
    }

    @Nested
    @DisplayName("The 337-byte image - FILLER emitted, attribute bytes raw, both code pages")
    class FixedWidthImage {

        @Test
        @DisplayName("gate G21 - the TIOAPFX prefix and all 17 attribute FILLERs are space-filled")
        void fillerIsPresentAndSpaceFilled() {
            byte[] image = populated().toFixedWidth(ASCII);

            assertThat(image).hasSize(337);
            assertThat(new String(image, 0, 12, ASCII)).isEqualTo(" ".repeat(12));
            for (ScreenField field : ScreenField.values()) {
                assertThat(new String(image, field.attributePrefixOffset(), 3, ASCII))
                        .as("the FILLER X(3) before " + field.baseName())
                        .isEqualTo("   ");
            }
        }

        @Test
        @DisplayName("every payload item lands at its copybook offset")
        void payloadLandsAtCopybookOffsets() {
            ReportRequestResponse response = populated();
            response.setErrmsgo(SystemMessages.CCDA_MSG_INVALID_KEY);
            byte[] image = response.toFixedWidth(ASCII);

            for (ScreenField field : ScreenField.values()) {
                assertThat(new String(image, field.payloadOffset(), field.payloadLength(), ASCII))
                        .as(field.payloadItemName() + " at offset " + field.payloadOffset())
                        .isEqualTo(response.payloadValue(field));
            }
            assertThat(new String(image, ScreenField.TRNNAME.payloadOffset(), 4, ASCII))
                    .isEqualTo("CR00");
            assertThat(new String(image, ScreenField.PGMNAME.payloadOffset(), 8, ASCII))
                    .isEqualTo("CORPT00C");
        }

        @Test
        @DisplayName("an attribute byte is a code point and is never charset translated")
        void attributeBytesAreRaw() {
            ReportRequestResponse response = populated();
            response.setErrmsgc(BmsAttributes.DFHGREEN);
            response.setHilightAttribute(ScreenField.MONTHLY, BmsAttributes.DFHUNDLN);

            byte[] ascii = response.toFixedWidth(ASCII);
            byte[] ebcdic = response.toFixedWidth(EBCDIC);

            int colourAt = ScreenField.ERRMSG.colourSpan().offset();
            int hilightAt = ScreenField.MONTHLY.hilightSpan().offset();
            assertThat(ascii[colourAt]).isEqualTo(BmsAttributes.DFHGREEN);
            assertThat(ebcdic[colourAt])
                    .as("DFHGREEN is X'F4' in both images, because it is not text")
                    .isEqualTo(BmsAttributes.DFHGREEN);
            assertThat(ascii[hilightAt]).isEqualTo(BmsAttributes.DFHUNDLN);
            assertThat(ebcdic[hilightAt]).isEqualTo(BmsAttributes.DFHUNDLN);
        }

        @Test
        @DisplayName("the image round trips losslessly in US-ASCII and in IBM037")
        void roundTripsInBothCodePages() {
            ReportRequestResponse response = populated();
            response.setErrmsgo("a message with trailing padding");
            response.setErrmsgc(BmsAttributes.DFHGREEN);
            response.setPsAttribute(ScreenField.CONFIRM, (byte) 0x11);
            response.setValidnAttribute(ScreenField.SDTMM, (byte) 0x22);

            for (Charset charset : List.of(ASCII, EBCDIC)) {
                byte[] image = response.toFixedWidth(charset);
                assertThat(image).hasSize(337);
                ReportRequestResponse back =
                        ReportRequestResponse.fromFixedWidth(image, charset);
                assertThat(back.fieldImages())
                        .as("payload via " + charset).isEqualTo(response.fieldImages());
                assertThat(back.attributeImages())
                        .as("attributes via " + charset).isEqualTo(response.attributeImages());
            }
        }

        @Test
        @DisplayName("writeInto and readFrom work against a caller-supplied record area")
        void writeIntoAndReadFromARecord() {
            ReportRequestResponse response = populated();
            response.setConfirmo("N");
            response.setErrmsgc(BmsAttributes.DFHRED);

            FixedWidthCodec codec = new FixedWidthCodec(ASCII);
            FixedWidthRecord record = codec.newRecord(ReportRequestResponse.LAYOUT);
            response.writeInto(record);

            assertThat(record.toByteArray()).hasSize(337)
                    .isEqualTo(response.toFixedWidth(ASCII));

            ReportRequestResponse back = ReportRequestResponse.readFrom(record);
            assertThat(back.fieldImages()).isEqualTo(response.fieldImages());
            assertThat(back.getErrmsgc()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(back.getConfirmo()).isEqualTo("N");
        }

        @Test
        @DisplayName("a record area of the wrong width is refused, naming the arithmetic")
        void wrongWidthIsRefused() {
            ReportRequestResponse response = new ReportRequestResponse();
            FixedWidthRecord tooNarrow = new FixedWidthRecord(336, ASCII);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.writeInto(tooNarrow))
                    .withMessageContaining("337")
                    .withMessageContaining("336");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> ReportRequestResponse.readFrom(tooNarrow))
                    .withMessageContaining("337");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> ReportRequestResponse.fromFixedWidth(new byte[336], ASCII));
        }

        @Test
        @DisplayName("the charset is always the caller's explicit choice, never the platform's")
        void charsetIsAlwaysExplicit() {
            ReportRequestResponse response = new ReportRequestResponse();
            assertThatNullPointerException().isThrownBy(() -> response.toFixedWidth(null))
                    .withMessageContaining("charset");
            assertThatNullPointerException()
                    .isThrownBy(() -> ReportRequestResponse.fromFixedWidth(new byte[337], null));
            assertThatNullPointerException()
                    .isThrownBy(() -> ReportRequestResponse.fromFixedWidth(null, ASCII));
            assertThatNullPointerException().isThrownBy(() -> response.writeInto(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> ReportRequestResponse.readFrom(null));
        }

        @Test
        @DisplayName("the diagnostic maps are keyed by the verbatim copybook item names")
        void imageMaps() {
            ReportRequestResponse response = new ReportRequestResponse();

            Map<String, String> payload = response.fieldImages();
            assertThat(payload).hasSize(17);
            assertThat(new ArrayList<>(payload.keySet()))
                    .startsWith("TRNNAMEO", "TITLE01O", "CURDATEO", "PGMNAMEO")
                    .endsWith("CONFIRMO", "ERRMSGO");
            for (ScreenField field : ScreenField.values()) {
                assertThat(payload.get(field.payloadItemName()))
                        .hasSize(field.payloadLength());
            }

            Map<String, String> attributes = response.attributeImages();
            assertThat(attributes).hasSize(68);
            assertThat(attributes).containsKeys("TRNNAMEC", "TRNNAMEP", "TRNNAMEH", "TRNNAMEV",
                    "ERRMSGC", "ERRMSGV");
            assertThat(attributes.get("ERRMSGC"))
                    .isEqualTo(BmsAttributes.toHex(ReportRequestResponse.ATTRIBUTE_UNSET));
        }
    }

    @Nested
    @DisplayName("Value semantics - equality covers everything that would be transmitted")
    class ValueSemantics {

        @Test
        @DisplayName("the copy constructor produces an equal but independent response")
        void copyConstructor() {
            ReportRequestResponse original = populated();
            original.setMonthlyo("X");
            original.setErrmsgo("boom");
            original.moveDfhgreenToErrmsgc();
            original.echoNavigation(NavigationContext.empty().withToProgram("COSGN00C"));

            ReportRequestResponse copy = new ReportRequestResponse(original);

            assertThat(copy).isEqualTo(original).hasSameHashCodeAs(original).isNotSameAs(original);
            assertThat(copy.getErrmsgc()).isEqualTo(BmsAttributes.DFHGREEN);
            assertThat(copy.getNextProgram()).isEqualTo("COSGN00C");

            copy.setMonthlyo("Y");
            assertThat(copy).isNotEqualTo(original);
            assertThat(original.getMonthlyo()).as("the original is untouched").isEqualTo("X");

            copy.setMonthlyo("X");
            assertThat(copy).isEqualTo(original);
            copy.setColourAttribute(ScreenField.MONTHLY, BmsAttributes.DFHRED);
            assertThat(copy).as("attribute bytes are part of equality").isNotEqualTo(original);

            assertThatNullPointerException()
                    .isThrownBy(() -> new ReportRequestResponse(null));
        }

        @Test
        @DisplayName("equality is reflexive, type-checked and null-safe")
        void equalityContract() {
            ReportRequestResponse response = new ReportRequestResponse();
            assertThat(response).isEqualTo(response)
                    .isNotEqualTo(null)
                    .isNotEqualTo("a string")
                    .isEqualTo(new ReportRequestResponse());
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("a difference in any one of the 17 members breaks equality")
        void everyMemberParticipatesInEquality(ScreenField field) {
            ReportRequestResponse left = new ReportRequestResponse();
            ReportRequestResponse right = new ReportRequestResponse();
            assertThat(left).isEqualTo(right);

            right.setPayloadValue(field, "Q");
            assertThat(right).as("a change to " + field.payloadItemName() + " must be visible")
                    .isNotEqualTo(left);
        }

        @Test
        @DisplayName("the navigation members participate in equality too")
        void navigationParticipatesInEquality() {
            ReportRequestResponse left = new ReportRequestResponse();

            ReportRequestResponse program = new ReportRequestResponse();
            program.setNextProgram("COMEN01C");
            assertThat(program).isNotEqualTo(left);

            ReportRequestResponse mapset = new ReportRequestResponse();
            mapset.setNextMapset("OTHER");
            assertThat(mapset).isNotEqualTo(left);

            ReportRequestResponse map = new ReportRequestResponse();
            map.setNextMap("OTHERM");
            assertThat(map).isNotEqualTo(left);

            ReportRequestResponse commarea = new ReportRequestResponse();
            commarea.setNavigationContext(NavigationContext.empty().withUserId("SOMEONE"));
            assertThat(commarea).isNotEqualTo(left);
        }

        @Test
        @DisplayName("toString names every item, and flags only the attribute quads that were set")
        void diagnosticRendering() {
            ReportRequestResponse response = new ReportRequestResponse();
            String quiet = response.toString();
            assertThat(quiet).contains("CORPT0AO").contains("337");
            for (ScreenField field : ScreenField.values()) {
                assertThat(quiet).contains(field.payloadItemName() + "='");
            }
            assertThat(quiet).as("an untouched quad is not rendered").doesNotContain("C=");

            response.setErrmsgo("boom");
            response.moveDfhgreenToErrmsgc();
            response.echoNavigation(NavigationContext.empty().withToProgram("COSGN00C"));
            String loud = response.toString();
            assertThat(loud).contains("ERRMSGO='boom")
                    .contains("DFHGREEN")
                    .contains("nextProgram='COSGN00C")
                    .contains("nextMapset='CORPT00")
                    .contains("nextMap='CORPT0A")
                    .contains(NavigationContext.TO_PROGRAM_FIELD);
        }
    }
}
