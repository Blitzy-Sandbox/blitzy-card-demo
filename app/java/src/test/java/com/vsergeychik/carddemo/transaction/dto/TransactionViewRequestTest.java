package com.vsergeychik.carddemo.transaction.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.transaction.dto.TransactionViewRequest.Ct02Info;
import com.vsergeychik.carddemo.transaction.dto.TransactionViewRequest.FieldMetadata;
import com.vsergeychik.carddemo.transaction.dto.TransactionViewRequest.ScreenField;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link TransactionViewRequest}, the inbound REST payload of CSD transaction {@code CT02}:
 * program {@code COTRN02C} (783 lines), mapset {@code COTRN02}, map {@code COTRN2A}.
 */
class TransactionViewRequestTest {
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    private static final FixedWidthCodec CODEC = new FixedWidthCodec(ASCII);

    private static final int EXPECTED_GROUP_LENGTH = 555;

    private static final int EXPECTED_PAYLOAD_TOTAL = 396;

    private static final int EXPECTED_TIOAPFX_LENGTH = 12;

    private static final int EXPECTED_METADATA_PREFIX = 7;

    private static final int COTRN01_GROUP_LENGTH_FOR_CONTRAST = 575;

    private static final int COTRN01_EXCLUSIVE_WIDTH = 16 + 16 + 16;

    private static final int COTRN02_EXCLUSIVE_WIDTH = 11 + 16 + 1;

    private static final int TOTAL_DFHMDF_ENTRIES = 61;

    private static final int NAMED_DFHMDF_ENTRIES = 21;

    private static final int MOVE_MINUS_ONE_SITES = 35;

    private static final List<String> EXPECTED_INPUT_ITEMS = List.of(
            "TRNNAMEI", "TITLE01I", "CURDATEI", "PGMNAMEI", "TITLE02I", "CURTIMEI",
            "ACTIDINI", "CARDNINI", "TTYPCDI", "TCATCDI", "TRNSRCI", "TDESCI",
            "TRNAMTI", "TORIGDTI", "TPROCDTI", "MIDI", "MNAMEI", "MCITYI",
            "MZIPI", "CONFIRMI", "ERRMSGI");

    private static final List<String> EXPECTED_INPUT_CAPABLE = List.of(
            "ACTIDIN", "CARDNIN", "TTYPCD", "TCATCD", "TRNSRC", "TDESC", "TRNAMT",
            "TORIGDT", "TPROCDT", "MID", "MNAME", "MCITY", "MZIP", "CONFIRM");

    private static final List<String> EXPECTED_OUTPUT_ONLY = List.of(
            "TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME", "ERRMSG");

    private static final Map<String, Integer> EXPECTED_WIDTHS = buildExpectedWidths();

    private static final Map<String, Integer> EXPECTED_GROUP_OFFSETS = buildExpectedGroupOffsets();

    private static Map<String, Integer> buildExpectedWidths() {
        Map<String, Integer> widths = new LinkedHashMap<>();
        widths.put("TRNNAME", 4);
        widths.put("TITLE01", 40);
        widths.put("CURDATE", 8);
        widths.put("PGMNAME", 8);
        widths.put("TITLE02", 40);
        widths.put("CURTIME", 8);
        widths.put("ACTIDIN", 11);
        widths.put("CARDNIN", 16);
        widths.put("TTYPCD", 2);
        widths.put("TCATCD", 4);
        widths.put("TRNSRC", 10);
        widths.put("TDESC", 60);
        widths.put("TRNAMT", 12);
        widths.put("TORIGDT", 10);
        widths.put("TPROCDT", 10);
        widths.put("MID", 9);
        widths.put("MNAME", 30);
        widths.put("MCITY", 25);
        widths.put("MZIP", 10);
        widths.put("CONFIRM", 1);
        widths.put("ERRMSG", 78);
        return Collections.unmodifiableMap(widths);
    }

    private static Map<String, Integer> buildExpectedGroupOffsets() {
        Map<String, Integer> offsets = new LinkedHashMap<>();
        int cursor = EXPECTED_TIOAPFX_LENGTH;
        for (Map.Entry<String, Integer> field : EXPECTED_WIDTHS.entrySet()) {
            offsets.put(field.getKey(), cursor);
            cursor += EXPECTED_METADATA_PREFIX + field.getValue();
        }
        return Collections.unmodifiableMap(offsets);
    }

    private static List<String> expectedLabels() {
        return List.copyOf(EXPECTED_WIDTHS.keySet());
    }

    private static TransactionViewRequest populated() {
        TransactionViewRequest request = new TransactionViewRequest();
        for (ScreenField field : ScreenField.values()) {
            request.setPayloadValue(field, sampleOf(field));
        }
        return request;
    }

    private static String sampleOf(ScreenField field) {
        return CODEC.movePicX(field.label(), field.length()).replace(' ', '.');
    }

    @Test
    void projectsExactlyTwentyOnePayloadFieldsInCopybookOrder() {
        assertThat(ScreenField.values()).hasSize(NAMED_DFHMDF_ENTRIES);
        assertThat(TransactionViewRequest.PAYLOAD_FIELD_COUNT).isEqualTo(NAMED_DFHMDF_ENTRIES);

        List<String> actual = new ArrayList<>();
        for (ScreenField field : ScreenField.values()) {
            actual.add(field.inputItem());
        }
        assertThat(actual).containsExactlyElementsOf(EXPECTED_INPUT_ITEMS);
    }

    @Test
    void everyPayloadFieldTracesToANamedDfhmdfEntry() {
        assertThat(NAMED_DFHMDF_ENTRIES + 40).isEqualTo(TOTAL_DFHMDF_ENTRIES);

        List<String> labels = new ArrayList<>();
        for (ScreenField field : ScreenField.values()) {
            labels.add(field.label());
        }
        assertThat(labels).containsExactlyElementsOf(expectedLabels());
        assertThat(labels).hasSameElementsAs(concat(EXPECTED_INPUT_CAPABLE, EXPECTED_OUTPUT_ONLY));
        assertThat(EXPECTED_INPUT_CAPABLE).doesNotContainAnyElementsOf(EXPECTED_OUTPUT_ONLY);
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> joined = new ArrayList<>(first);
        joined.addAll(second);
        return joined;
    }

    @Test
    void carriesNeitherTrnidinNorTrnidBecauseThisIsTheAddMap() {
        Set<String> projected = new LinkedHashSet<>();
        for (ScreenField field : ScreenField.values()) {
            projected.add(field.label());
            projected.add(field.inputItem());
            projected.add(field.outputItem());
        }
        assertThat(projected).doesNotContain("TRNID", "TRNIDI", "TRNIDO", "TRNIDIN", "TRNIDINI",
                "TRNIDINO");

        assertThat(TransactionViewRequest.AI_LAYOUT.hasSpan("TRNIDI")).isFalse();
        assertThat(TransactionViewRequest.AI_LAYOUT.hasSpan("TRNIDINI")).isFalse();

        assertThat(projected).doesNotContain("CARDNUM", "CARDNUMI", "CARDNUMO");
        assertThat(projected).contains("CARDNIN", "CARDNINI", "CARDNINO");
    }

    @Test
    void carriesTheAccountKeyCardKeyAndConfirmFlagThatOnlyTheAddMapHas() {
        assertThat(TransactionViewRequest.ACTIDIN_LENGTH).isEqualTo(11);
        assertThat(TransactionViewRequest.CARDNIN_LENGTH).isEqualTo(16);
        assertThat(TransactionViewRequest.CONFIRM_LENGTH).isEqualTo(1);

        assertThat(ScreenField.ACTIDIN.length()).isEqualTo(11);
        assertThat(ScreenField.CARDNIN.length()).isEqualTo(16);
        assertThat(ScreenField.CONFIRM.length()).isEqualTo(1);

        assertThat(ScreenField.ACTIDIN.unprotectedField()).isTrue();
        assertThat(ScreenField.CARDNIN.unprotectedField()).isTrue();
        assertThat(ScreenField.CONFIRM.unprotectedField()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(ScreenField.class)
    void everyFieldMatchesItsDeclaredWidthAndGroupOffset(ScreenField field) {
        assertThat(field.length()).isEqualTo(EXPECTED_WIDTHS.get(field.label()));
        assertThat(field.groupOffset()).isEqualTo(EXPECTED_GROUP_OFFSETS.get(field.label()));
        assertThat(field.payloadOffset())
                .isEqualTo(field.groupOffset() + EXPECTED_METADATA_PREFIX);
    }

    @Test
    void byteTotalsMatchTheCopybookArithmetic() {
        int summedWidths = 0;
        for (ScreenField field : ScreenField.values()) {
            summedWidths += field.length();
        }
        assertThat(summedWidths).isEqualTo(EXPECTED_PAYLOAD_TOTAL);
        assertThat(TransactionViewRequest.PAYLOAD_WIDTH_TOTAL).isEqualTo(EXPECTED_PAYLOAD_TOTAL);

        assertThat(TransactionViewRequest.TIOAPFX_PREFIX_LENGTH).isEqualTo(EXPECTED_TIOAPFX_LENGTH);
        assertThat(TransactionViewRequest.LENGTH_ITEM_LENGTH).isEqualTo(2);
        assertThat(TransactionViewRequest.FLAG_ITEM_LENGTH).isEqualTo(1);
        assertThat(TransactionViewRequest.RESERVED_FILLER_LENGTH).isEqualTo(4);
        assertThat(TransactionViewRequest.METADATA_PREFIX_LENGTH).isEqualTo(EXPECTED_METADATA_PREFIX);

        assertThat(EXPECTED_TIOAPFX_LENGTH
                + NAMED_DFHMDF_ENTRIES * EXPECTED_METADATA_PREFIX
                + EXPECTED_PAYLOAD_TOTAL).isEqualTo(EXPECTED_GROUP_LENGTH);
        assertThat(TransactionViewRequest.AI_GROUP_LENGTH).isEqualTo(EXPECTED_GROUP_LENGTH);
        assertThat(TransactionViewRequest.AI_LAYOUT.recordLength()).isEqualTo(EXPECTED_GROUP_LENGTH);
    }

    @Test
    void equalFieldCountsAreNotEqualImagesWhichIsWhyTheTotalIsAsserted() {
        assertThat(COTRN01_GROUP_LENGTH_FOR_CONTRAST - EXPECTED_GROUP_LENGTH).isEqualTo(20);
        assertThat(TransactionViewRequest.AI_GROUP_LENGTH)
                .isNotEqualTo(COTRN01_GROUP_LENGTH_FOR_CONTRAST);

        assertThat(COTRN01_EXCLUSIVE_WIDTH).isEqualTo(48);
        assertThat(COTRN02_EXCLUSIVE_WIDTH).isEqualTo(28);
        assertThat(COTRN01_EXCLUSIVE_WIDTH - COTRN02_EXCLUSIVE_WIDTH).isEqualTo(20);

        assertThat(ScreenField.ACTIDIN.length()
                + ScreenField.CARDNIN.length()
                + ScreenField.CONFIRM.length()).isEqualTo(COTRN02_EXCLUSIVE_WIDTH);
        assertThat(18 + 3).isEqualTo(NAMED_DFHMDF_ENTRIES);
        assertThat(TransactionViewRequest.AI_LAYOUT.hasSpan("TRNIDI")).isFalse();
        assertThat(TransactionViewRequest.AI_LAYOUT.hasSpan("TRNIDINI")).isFalse();
        assertThat(TransactionViewRequest.AI_LAYOUT.hasSpan("CARDNUMI")).isFalse();
    }

    @Test
    void layoutSelfCheckProvesTheGroupGeometryIsGapless() {
        List<FieldSpan> spans = new ArrayList<>(TransactionViewRequest.AI_LAYOUT.storageSpans());
        spans.sort((left, right) -> Integer.compare(left.offset(), right.offset()));

        int cursor = 0;
        for (FieldSpan span : spans) {
            assertThat(span.offset())
                    .as("span %s must begin exactly where the previous span ended", span.name())
                    .isEqualTo(cursor);
            cursor = span.endOffsetExclusive();
        }
        assertThat(cursor).isEqualTo(EXPECTED_GROUP_LENGTH);
    }

    @Test
    void rendersExactlyFiveHundredAndFiftyFiveBytes() {
        assertThat(populated().toFixedWidth(ASCII)).hasSize(EXPECTED_GROUP_LENGTH);
        assertThat(new TransactionViewRequest().toFixedWidth(ASCII)).hasSize(EXPECTED_GROUP_LENGTH);
    }

    @Test
    void everyFieldLandsAtItsTrueOffsetInTheImage() {
        TransactionViewRequest request = populated();
        String image = new String(request.toFixedWidth(ASCII), ASCII);
        assertThat(image).hasSize(EXPECTED_GROUP_LENGTH);

        for (ScreenField field : ScreenField.values()) {
            int start = EXPECTED_GROUP_OFFSETS.get(field.label()) + EXPECTED_METADATA_PREFIX;
            assertThat(image.substring(start, start + field.length()))
                    .as("payload of %s at offset %d", field.inputItem(), start)
                    .isEqualTo(sampleOf(field));
        }
    }

    @Test
    void rejectsAWorkAreaOfTheWrongWidth() {
        FixedWidthRecord tooNarrow = new FixedWidthRecord(EXPECTED_GROUP_LENGTH - 1, ASCII);
        assertThatThrownBy(() -> populated().writeInto(tooNarrow))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(EXPECTED_GROUP_LENGTH));
        assertThatThrownBy(() -> TransactionViewRequest.readFrom(tooNarrow))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void writeIntoAndReadFromAgreeWithTheCodecPath() {
        TransactionViewRequest request = populated();

        FixedWidthRecord area = FixedWidthRecord.forLayout(TransactionViewRequest.AI_LAYOUT, ASCII);
        request.writeInto(area);

        assertThat(area.toByteArray()).isEqualTo(request.toFixedWidth(ASCII));

        TransactionViewRequest viaRecord = TransactionViewRequest.readFrom(area);
        TransactionViewRequest viaBytes =
                TransactionViewRequest.fromFixedWidth(request.toFixedWidth(ASCII), ASCII);
        for (ScreenField field : ScreenField.values()) {
            assertThat(viaRecord.payloadValue(field)).isEqualTo(sampleOf(field));
            assertThat(viaBytes.payloadValue(field)).isEqualTo(sampleOf(field));
        }
    }

    @Test
    void spacePaddedValuesSurviveAFixedWidthRoundTripUntrimmed() {
        TransactionViewRequest request = new TransactionViewRequest();
        request.setTdesc("GROCERY");
        request.setMname("ACME");

        TransactionViewRequest restored =
                TransactionViewRequest.fromFixedWidth(request.toFixedWidth(ASCII), ASCII);

        assertThat(restored.getTdesc())
                .hasSize(TransactionViewRequest.TDESC_LENGTH)
                .isEqualTo("GROCERY" + " ".repeat(TransactionViewRequest.TDESC_LENGTH - 7));
        assertThat(restored.getMname())
                .hasSize(TransactionViewRequest.MNAME_LENGTH)
                .startsWith("ACME")
                .endsWith(" ");
    }

    @ParameterizedTest
    @EnumSource(ScreenField.class)
    void derivesTheFourSymbolicMapItemNamesTheWayBmsDoes(ScreenField field) {
        assertThat(field.inputItem()).isEqualTo(field.label() + "I");
        assertThat(field.outputItem()).isEqualTo(field.label() + "O");
        assertThat(field.lengthItem()).isEqualTo(field.label() + "L");
        assertThat(field.flagItem()).isEqualTo(field.label() + "F");
        assertThat(field.attributeItem()).isEqualTo(field.label() + "A");
    }

    @Test
    void metadataAndDerivedPredicatesNeverAppearOnTheWire() throws Exception {
        TransactionViewRequest request = populated();
        request.requestCursor(ScreenField.TRNAMT);
        request.metadata(ScreenField.CONFIRM).setAttribute('*');

        String json = new ObjectMapper().writeValueAsString(request);

        for (String inputItem : EXPECTED_INPUT_ITEMS) {
            String property = payloadPropertyOf(inputItem);
            assertThat(json)
                    .as("payload property for %s", inputItem)
                    .contains("\"" + property + "\"");
        }
        for (ScreenField field : ScreenField.values()) {
            assertThat(json).doesNotContain(field.lengthItem(), field.flagItem(),
                    field.attributeItem());
            assertThat(json.toLowerCase(Locale.ROOT))
                    .doesNotContain(field.lengthItem().toLowerCase(Locale.ROOT));
        }
        assertThat(json).doesNotContain("fieldMetadata", "cursorRequested", "enterContext",
                "reenterContext", "commareaLength", "navigationContextPresent");
    }

    private static String payloadPropertyOf(String inputItem) {
        String stem = inputItem.substring(0, inputItem.length() - 1);
        return stem.toLowerCase(Locale.ROOT);
    }

    @Test
    void jsonRoundTripPreservesTrailingSpacesAndEquality() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        TransactionViewRequest request = populated();
        request.setNavigationContext(NavigationContext.empty().withPgmReenter());
        request.setAid("PF03 ");

        String json = mapper.writeValueAsString(request);
        TransactionViewRequest restored = mapper.readValue(json, TransactionViewRequest.class);

        for (ScreenField field : ScreenField.values()) {
            assertThat(restored.payloadValue(field))
                    .as("%s survives JSON byte for byte", field.inputItem())
                    .isEqualTo(sampleOf(field));
        }
        assertThat(restored.getAid()).isEqualTo("PF03 ");
        assertThat(restored.getNavigationContext()).isEqualTo(request.getNavigationContext());
        assertThat(restored.isReenterContext()).isTrue();
    }

    @Test
    void lengthItemIsSignedAndHoldsMinusOneWithoutClamping() {
        assertThat(MOVE_MINUS_ONE_SITES).isEqualTo(35);
        assertThat(TransactionViewRequest.CURSOR_REQUEST).isEqualTo(-1);

        TransactionViewRequest request = new TransactionViewRequest();
        for (ScreenField field : ScreenField.values()) {
            request.requestCursor(field);
            assertThat(request.metadata(field).length()).isEqualTo(-1);
            assertThat(request.metadata(field).length()).isNotEqualTo(65_535);
            assertThat(request.isCursorRequested(field)).isTrue();
            assertThat(request.metadata(field).hasInput()).isFalse();
        }

        FieldMetadata metadata = new FieldMetadata();
        metadata.setLength(-1);
        assertThat(metadata.isCursorRequested()).isTrue();
        metadata.setLength(Short.MIN_VALUE);
        assertThat(metadata.length()).isEqualTo(Short.MIN_VALUE);
        metadata.setLength(Short.MAX_VALUE);
        assertThat(metadata.length()).isEqualTo(Short.MAX_VALUE);
    }

    @Test
    void lengthItemRejectsValuesOutsideASignedHalfword() {
        FieldMetadata metadata = new FieldMetadata();
        assertThatThrownBy(() -> metadata.setLength(Short.MAX_VALUE + 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("S9(4)");
        assertThatThrownBy(() -> metadata.setLength(Short.MIN_VALUE - 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void metadataDefaultsToNoInputAndResetsBackToIt() {
        TransactionViewRequest request = populated();
        for (ScreenField field : ScreenField.values()) {
            assertThat(request.metadata(field).length()).isEqualTo(FieldMetadata.NO_INPUT_LENGTH);
            assertThat(request.metadata(field).hasInput()).isFalse();
            assertThat(request.metadata(field).attribute()).isEqualTo(FieldMetadata.UNSET_ATTRIBUTE);
        }

        request.metadata(ScreenField.TRNAMT).setLength(12);
        assertThat(request.metadata(ScreenField.TRNAMT).hasInput()).isTrue();
        request.metadata(ScreenField.TDESC).setAttribute('*');
        request.requestCursor(ScreenField.CONFIRM);

        request.resetMetadata();
        for (ScreenField field : ScreenField.values()) {
            assertThat(request.metadata(field).hasInput()).isFalse();
            assertThat(request.isCursorRequested(field)).isFalse();
            assertThat(request.metadata(field).attribute()).isEqualTo(FieldMetadata.UNSET_ATTRIBUTE);
        }
    }

    @Test
    void attributeAndFlagAreOneByteViewedThroughTwoNames() {
        TransactionViewRequest request = new TransactionViewRequest();
        FieldMetadata metadata = request.metadata(ScreenField.ACTIDIN);

        metadata.setFlag('X');
        assertThat(metadata.attribute()).isEqualTo('X');
        metadata.setAttribute('Y');
        assertThat(metadata.flag()).isEqualTo('Y');

        for (char candidate : new char[] {'\u0000', ' ', '*', 'A', 'z', '\u00ff'}) {
            metadata.setFlag(candidate);
            assertThat(metadata.attribute()).isEqualTo(candidate);
            metadata.setAttribute(candidate);
            assertThat(metadata.flag()).isEqualTo(candidate);
        }

        assertThat(request.toFixedWidth(ASCII)).hasSize(EXPECTED_GROUP_LENGTH);
    }

    @ParameterizedTest
    @EnumSource(ScreenField.class)
    void metadataSpansAccountForEveryPrefixByteAndAreFiller(ScreenField field) {
        List<FieldSpan> spans = field.metadataSpans();
        assertThat(spans).hasSize(3);

        int k = field.groupOffset();
        assertThat(spans.get(0).offset()).isEqualTo(k);
        assertThat(spans.get(0).length()).isEqualTo(2);
        assertThat(spans.get(1).offset()).isEqualTo(k + 2);
        assertThat(spans.get(1).length()).isEqualTo(1);
        assertThat(spans.get(2).offset()).isEqualTo(k + 3);
        assertThat(spans.get(2).length()).isEqualTo(4);

        for (FieldSpan span : spans) {
            assertThat(span.kind().filler())
                    .as("prefix span at offset %d is FILLER", span.offset())
                    .isTrue();
        }
        assertThat(spans.get(2).endOffsetExclusive()).isEqualTo(field.payloadOffset());
    }

    @ParameterizedTest
    @EnumSource(ScreenField.class)
    void payloadSpanOccupiesTheIdenticalBytesTheOutputViewCallsXxxO(ScreenField field) {
        FieldSpan payload = field.payloadSpan();
        assertThat(payload.name()).isEqualTo(field.inputItem());
        assertThat(payload.offset()).isEqualTo(field.groupOffset() + EXPECTED_METADATA_PREFIX);
        assertThat(payload.length()).isEqualTo(field.length());
        assertThat(payload.endOffsetExclusive())
                .isEqualTo(field.groupOffset() + EXPECTED_METADATA_PREFIX + field.length());

        FieldSpan resolved = TransactionViewRequest.AI_LAYOUT.span(field.inputItem());
        assertThat(resolved.offset()).isEqualTo(payload.offset());
        assertThat(resolved.length()).isEqualTo(payload.length());
    }

    @Test
    void leadingTioapfxFillerIsPresentAndSpaceFilled() {
        FieldSpan prefix = null;
        for (FieldSpan span : TransactionViewRequest.AI_LAYOUT.storageSpans()) {
            if (span.offset() == 0) {
                prefix = span;
            }
        }
        assertThat(prefix).isNotNull();
        assertThat(prefix.length()).isEqualTo(EXPECTED_TIOAPFX_LENGTH);
        assertThat(prefix.kind().filler()).isTrue();

        String image = new String(populated().toFixedWidth(ASCII), ASCII);
        assertThat(image.substring(0, EXPECTED_TIOAPFX_LENGTH))
                .isEqualTo(" ".repeat(EXPECTED_TIOAPFX_LENGTH));
    }

    @Test
    void perFieldFillerBytesAreSpaceFilledInTheRenderedImage() {
        String image = new String(populated().toFixedWidth(ASCII), ASCII);
        for (ScreenField field : ScreenField.values()) {
            int k = field.groupOffset();
            assertThat(image.substring(k + 3, k + 7))
                    .as("reserved FILLER of %s", field.label())
                    .isEqualTo("    ");
        }
    }

    @Test
    void nullFieldRendersAsLowValuesReproducingAFieldCicsDidNotTransmit() {
        TransactionViewRequest request = new TransactionViewRequest();
        request.setTdesc(null);

        String image = new String(request.toFixedWidth(ASCII), ASCII);
        int start = ScreenField.TDESC.payloadOffset();
        assertThat(image.substring(start, start + TransactionViewRequest.TDESC_LENGTH))
                .isEqualTo("\u0000".repeat(TransactionViewRequest.TDESC_LENGTH));

        assertThat(TransactionViewRequest.lowValues(3)).isEqualTo("\u0000\u0000\u0000");
        assertThat(TransactionViewRequest.spaces(3)).isEqualTo("   ");
        assertThat(TransactionViewRequest.spaces(0)).isEmpty();
        assertThat(TransactionViewRequest.lowValues(0)).isEmpty();
    }

    @Test
    void figurativeConstantsRejectANegativeWidth() {
        assertThatThrownBy(() -> TransactionViewRequest.spaces(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SPACES");
        assertThatThrownBy(() -> TransactionViewRequest.lowValues(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("LOW-VALUES");
    }

    @Test
    void payloadImagesAreKeyedByVerbatimCopybookNames() {
        Map<String, String> images = populated().payloadImages();
        assertThat(images.keySet()).containsExactlyElementsOf(EXPECTED_INPUT_ITEMS);
        for (ScreenField field : ScreenField.values()) {
            assertThat(images.get(field.inputItem()))
                    .hasSize(field.length())
                    .isEqualTo(sampleOf(field));
        }
    }

    private static String refMod(String value, int oneBasedStart, int length) {
        return value.substring(oneBasedStart - 1, oneBasedStart - 1 + length);
    }

    private static boolean isNumeric(String span) {
        if (span.isEmpty()) {
            return false;
        }
        for (int index = 0; index < span.length(); index++) {
            if (span.charAt(index) < '0' || span.charAt(index) > '9') {
                return false;
            }
        }
        return true;
    }

    private static boolean amountSignClauseFails(String amount) {
        String sign = refMod(amount, 1, 1);
        return !"-".equals(sign) && !"+".equals(sign);
    }

    private static boolean amountIntegerClauseFails(String amount) {
        return !isNumeric(refMod(amount, 2, 8));
    }

    private static boolean amountPointClauseFails(String amount) {
        return !".".equals(refMod(amount, 10, 1));
    }

    private static boolean amountFractionClauseFails(String amount) {
        return !isNumeric(refMod(amount, 11, 2));
    }

    private static boolean amountMaskRejects(String amount) {
        return amountSignClauseFails(amount)
                || amountIntegerClauseFails(amount)
                || amountPointClauseFails(amount)
                || amountFractionClauseFails(amount);
    }

    private static boolean dateMaskRejects(String date) {
        return !isNumeric(refMod(date, 1, 4))
                || !"-".equals(refMod(date, 5, 1))
                || !isNumeric(refMod(date, 6, 2))
                || !"-".equals(refMod(date, 8, 1))
                || !isNumeric(refMod(date, 9, 2));
    }

    @Test
    void amountIsTwelveCharactersMatchingTheEditMask() {
        assertThat(TransactionViewRequest.TRNAMT_LENGTH).isEqualTo(12);
        assertThat("+99999999.99").hasSize(TransactionViewRequest.TRNAMT_LENGTH);
        assertThat(1 + 8 + 1 + 2).isEqualTo(TransactionViewRequest.TRNAMT_LENGTH);
    }

    @Test
    void theFourAmountClausesPartitionAllTwelveBytes() {
        boolean[] covered = new boolean[TransactionViewRequest.TRNAMT_LENGTH];
        markCovered(covered, 1, 1);
        markCovered(covered, 2, 8);
        markCovered(covered, 10, 1);
        markCovered(covered, 11, 2);
        for (int index = 0; index < covered.length; index++) {
            assertThat(covered[index]).as("byte %d of TRNAMT is validated", index + 1).isTrue();
        }
    }

    private static void markCovered(boolean[] covered, int oneBasedStart, int length) {
        for (int offset = 0; offset < length; offset++) {
            int index = oneBasedStart - 1 + offset;
            assertThat(covered[index]).as("byte %d is claimed once only", index + 1).isFalse();
            covered[index] = true;
        }
    }

    @ParameterizedTest
    @CsvSource({
        "'+00000123.45', false, false,   false, false",
        "'-00000123.45', false, false,   false, false",
        "' 00000123.45', true,  false,   false, false",
        "'*00000123.45', true,  false,   false, false",
        "'+0000012A.45', false, true,    false, false",
        "'+0000012 .45', false, true,    false, false",
        "'+00000123,45', false, false,   true,  false",
        "'+00000123x45', false, false,   true,  false",
        "'+00000123.4A', false, false,   false, true",
        "'+00000123.  ', false, false,   false, true"
    })
    void everyAmountClauseIsDrivenToBothOutcomes(String amount,
                                                 boolean signFails,
                                                 boolean integerFails,
                                                 boolean pointFails,
                                                 boolean fractionFails) {
        assertThat(amount).hasSize(TransactionViewRequest.TRNAMT_LENGTH);

        assertThat(amountSignClauseFails(amount)).isEqualTo(signFails);
        assertThat(amountIntegerClauseFails(amount)).isEqualTo(integerFails);
        assertThat(amountPointClauseFails(amount)).isEqualTo(pointFails);
        assertThat(amountFractionClauseFails(amount)).isEqualTo(fractionFails);

        boolean rejected = signFails || integerFails || pointFails || fractionFails;
        assertThat(amountMaskRejects(amount)).isEqualTo(rejected);

        TransactionViewRequest request = new TransactionViewRequest();
        request.setTrnamt(amount);
        assertThat(request.getTrnamt()).isEqualTo(amount);

        TransactionViewRequest restored =
                TransactionViewRequest.fromFixedWidth(request.toFixedWidth(ASCII), ASCII);
        assertThat(restored.getTrnamt()).isEqualTo(amount);
        assertThat(amountMaskRejects(restored.getTrnamt())).isEqualTo(rejected);
    }

    @Test
    void theAllPassAmountIsAcceptedAndKeptCharacterForCharacter() {
        String accepted = "+00000123.45";
        assertThat(amountMaskRejects(accepted)).isFalse();

        TransactionViewRequest request = new TransactionViewRequest();
        request.setTrnamt(accepted);
        assertThat(request.payloadValue(ScreenField.TRNAMT)).isEqualTo(accepted);
        assertThat(request.payloadImages().get("TRNAMTI")).isEqualTo(accepted);
    }

    @Test
    void aNaiveNumericParseWouldAcceptWhatThePositionalMaskRejects() {
        String commaDecimal = "+00000123,45";
        assertThat(amountPointClauseFails(commaDecimal)).isTrue();
        assertThat(amountMaskRejects(commaDecimal)).isTrue();

        String asNumberInCommaLocale = commaDecimal.replace(',', '.');
        assertThat(new BigDecimal(asNumberInCommaLocale.substring(1)))
                .isEqualByComparingTo(new BigDecimal("123.45"));

        assertThat(dateMaskRejects("2022/07/18")).isTrue();
        assertThat(dateMaskRejects("2022-07-18")).isFalse();

        TransactionViewRequest request = new TransactionViewRequest();
        request.setTrnamt(commaDecimal);
        request.setTorigdt("2022/07/18");
        assertThat(request.getTrnamt()).isEqualTo(commaDecimal);
        assertThat(request.getTorigdt()).isEqualTo("2022/07/18");
    }

    @ParameterizedTest
    @CsvSource({
        "'2022-07-18', false, false, false, false, false",
        "'20A2-07-18', true,  false, false, false, false",
        "'2022/07-18', false, true,  false, false, false",
        "'2022-A7-18', false, false, true,  false, false",
        "'2022-07/18', false, false, false, true,  false",
        "'2022-07-1A', false, false, false, false, true",
        "'    -  -  ', true,  false, true,  false, true"
    })
    void everyDateClauseIsDrivenToBothOutcomesForBothDateFields(String date,
                                                               boolean yearFails,
                                                               boolean dash1Fails,
                                                               boolean monthFails,
                                                               boolean dash2Fails,
                                                               boolean dayFails) {
        assertThat(date).hasSize(TransactionViewRequest.TORIGDT_LENGTH);
        assertThat(TransactionViewRequest.TORIGDT_LENGTH)
                .isEqualTo(TransactionViewRequest.TPROCDT_LENGTH);

        assertThat(!isNumeric(refMod(date, 1, 4))).isEqualTo(yearFails);
        assertThat(!"-".equals(refMod(date, 5, 1))).isEqualTo(dash1Fails);
        assertThat(!isNumeric(refMod(date, 6, 2))).isEqualTo(monthFails);
        assertThat(!"-".equals(refMod(date, 8, 1))).isEqualTo(dash2Fails);
        assertThat(!isNumeric(refMod(date, 9, 2))).isEqualTo(dayFails);

        boolean rejected = yearFails || dash1Fails || monthFails || dash2Fails || dayFails;
        assertThat(dateMaskRejects(date)).isEqualTo(rejected);

        TransactionViewRequest request = new TransactionViewRequest();
        request.setTorigdt(date);
        request.setTprocdt(date);

        TransactionViewRequest restored =
                TransactionViewRequest.fromFixedWidth(request.toFixedWidth(ASCII), ASCII);
        assertThat(restored.getTorigdt()).isEqualTo(date);
        assertThat(restored.getTprocdt()).isEqualTo(date);
        assertThat(dateMaskRejects(restored.getTorigdt())).isEqualTo(rejected);
        assertThat(dateMaskRejects(restored.getTprocdt())).isEqualTo(rejected);
    }

    @Test
    void theFiveDateClausesPartitionAllTenBytes() {
        boolean[] covered = new boolean[TransactionViewRequest.TORIGDT_LENGTH];
        markCovered(covered, 1, 4);
        markCovered(covered, 5, 1);
        markCovered(covered, 6, 2);
        markCovered(covered, 8, 1);
        markCovered(covered, 9, 2);
        for (int index = 0; index < covered.length; index++) {
            assertThat(covered[index]).as("byte %d of the date is validated", index + 1).isTrue();
        }
    }

    private enum ConfirmOutcome { PROCEED, NOT_YET_CONFIRMED, INVALID }

    private static ConfirmOutcome confirmOutcome(String confirm) {
        if ("Y".equals(confirm) || "y".equals(confirm)) {
            return ConfirmOutcome.PROCEED;
        }
        if ("N".equals(confirm) || "n".equals(confirm)
                || " ".equals(confirm) || "\u0000".equals(confirm) || confirm == null) {
            return ConfirmOutcome.NOT_YET_CONFIRMED;
        }
        return ConfirmOutcome.INVALID;
    }

    @ParameterizedTest
    @CsvSource({
        "Y, PROCEED",
        "y, PROCEED",
        "N, NOT_YET_CONFIRMED",
        "n, NOT_YET_CONFIRMED",
        "X, INVALID",
        "1, INVALID",
        "*, INVALID"
    })
    void confirmCollapsesSevenClausesIntoThreeOutcomes(String confirm, String expected) {
        assertThat(confirm).hasSize(TransactionViewRequest.CONFIRM_LENGTH);
        assertThat(confirmOutcome(confirm)).isEqualTo(ConfirmOutcome.valueOf(expected));

        TransactionViewRequest request = new TransactionViewRequest();
        request.setConfirm(confirm);
        assertThat(request.getConfirm()).isEqualTo(confirm);

        TransactionViewRequest restored =
                TransactionViewRequest.fromFixedWidth(request.toFixedWidth(ASCII), ASCII);
        assertThat(restored.getConfirm()).isEqualTo(confirm);
        assertThat(confirmOutcome(restored.getConfirm()))
                .isEqualTo(ConfirmOutcome.valueOf(expected));
    }

    @Test
    void blankConfirmGroupsWithNoRatherThanBeingInvalid() {
        String spaces = TransactionViewRequest.spaces(TransactionViewRequest.CONFIRM_LENGTH);
        String lowValues = TransactionViewRequest.lowValues(TransactionViewRequest.CONFIRM_LENGTH);

        assertThat(confirmOutcome(spaces)).isEqualTo(ConfirmOutcome.NOT_YET_CONFIRMED);
        assertThat(confirmOutcome(lowValues)).isEqualTo(ConfirmOutcome.NOT_YET_CONFIRMED);
        assertThat(confirmOutcome("N")).isEqualTo(ConfirmOutcome.NOT_YET_CONFIRMED);
        assertThat(confirmOutcome(spaces)).isEqualTo(confirmOutcome("N"));
        assertThat(confirmOutcome(lowValues)).isEqualTo(confirmOutcome("n"));

        assertThat(confirmOutcome(spaces)).isNotEqualTo(ConfirmOutcome.INVALID);
        assertThat(confirmOutcome(lowValues)).isNotEqualTo(ConfirmOutcome.PROCEED);

        TransactionViewRequest fresh = new TransactionViewRequest();
        assertThat(fresh.getConfirm()).isEqualTo(spaces);
        assertThat(confirmOutcome(fresh.getConfirm())).isEqualTo(ConfirmOutcome.NOT_YET_CONFIRMED);

        TransactionViewRequest untransmitted = new TransactionViewRequest();
        untransmitted.setConfirm(null);
        String image = new String(untransmitted.toFixedWidth(ASCII), ASCII);
        int start = ScreenField.CONFIRM.payloadOffset();
        assertThat(image.substring(start, start + 1)).isEqualTo(lowValues);
        assertThat(confirmOutcome(TransactionViewRequest
                .fromFixedWidth(untransmitted.toFixedWidth(ASCII), ASCII).getConfirm()))
                .isEqualTo(ConfirmOutcome.NOT_YET_CONFIRMED);
    }

    @Test
    void allThreeConfirmOutcomesAreReachable() {
        Set<ConfirmOutcome> reached = new LinkedHashSet<>();
        for (String candidate : new String[] {"Y", "y", "N", "n", " ", "\u0000", "X", "?", "0"}) {
            reached.add(confirmOutcome(candidate));
        }
        assertThat(reached).containsExactlyInAnyOrder(ConfirmOutcome.values());
    }

    @Test
    void theCommareaExtensionIsFiftyEightBytesFieldByField() {
        assertThat(Ct02Info.TRNID_FIRST_LENGTH).isEqualTo(16);
        assertThat(Ct02Info.TRNID_LAST_LENGTH).isEqualTo(16);
        assertThat(Ct02Info.PAGE_NUM_LENGTH).isEqualTo(8);
        assertThat(Ct02Info.NEXT_PAGE_FLG_LENGTH).isEqualTo(1);
        assertThat(Ct02Info.TRN_SEL_FLG_LENGTH).isEqualTo(1);
        assertThat(Ct02Info.TRN_SELECTED_LENGTH).isEqualTo(16);

        assertThat(16 + 16 + 8 + 1 + 1 + 16).isEqualTo(58);
        assertThat(Ct02Info.CT02_INFO_LENGTH).isEqualTo(58);

        assertThat(Ct02Info.TRNID_FIRST_OFFSET).isZero();
        assertThat(Ct02Info.TRNID_LAST_OFFSET).isEqualTo(16);
        assertThat(Ct02Info.PAGE_NUM_OFFSET).isEqualTo(32);
        assertThat(Ct02Info.NEXT_PAGE_FLG_OFFSET).isEqualTo(40);
        assertThat(Ct02Info.TRN_SEL_FLG_OFFSET).isEqualTo(41);
        assertThat(Ct02Info.TRN_SELECTED_OFFSET).isEqualTo(42);
        assertThat(Ct02Info.TRN_SELECTED_OFFSET + Ct02Info.TRN_SELECTED_LENGTH)
                .isEqualTo(Ct02Info.CT02_INFO_LENGTH);

        assertThat(Ct02Info.LAYOUT.recordLength()).isEqualTo(58);
    }

    @Test
    void commareaFieldNamesAreVerbatimCobolNames() {
        assertThat(Ct02Info.TRNID_FIRST_FIELD).isEqualTo("CDEMO-CT02-TRNID-FIRST");
        assertThat(Ct02Info.TRNID_LAST_FIELD).isEqualTo("CDEMO-CT02-TRNID-LAST");
        assertThat(Ct02Info.PAGE_NUM_FIELD).isEqualTo("CDEMO-CT02-PAGE-NUM");
        assertThat(Ct02Info.NEXT_PAGE_FLG_FIELD).isEqualTo("CDEMO-CT02-NEXT-PAGE-FLG");
        assertThat(Ct02Info.TRN_SEL_FLG_FIELD).isEqualTo("CDEMO-CT02-TRN-SEL-FLG");
        assertThat(Ct02Info.TRN_SELECTED_FIELD).isEqualTo("CDEMO-CT02-TRN-SELECTED");
    }

    @Test
    void navigationContextStaysAtOneHundredAndSixtyBytesAndIsNeverWidened() {
        assertThat(NavigationContext.GENERAL_INFO_LENGTH
                + NavigationContext.CUSTOMER_INFO_LENGTH
                + NavigationContext.ACCOUNT_INFO_LENGTH
                + NavigationContext.CARD_INFO_LENGTH
                + NavigationContext.MORE_INFO_LENGTH).isEqualTo(160);
        assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(160);
        assertThat(NavigationContext.empty().toFixedWidth(CODEC)).hasSize(160);

        assertThat(NavigationContext.COMMAREA_LENGTH + Ct02Info.CT02_INFO_LENGTH).isEqualTo(218);
        assertThat(Ct02Info.COMMAREA_TOTAL_LENGTH).isEqualTo(218);
    }

    @Test
    void theCommareaExtensionIsANestedStaticTypeNotAWidenedCarrier() {
        Class<?> nested = Ct02Info.class;
        assertThat(nested.getEnclosingClass()).isEqualTo(TransactionViewRequest.class);
        assertThat(Modifier.isStatic(nested.getModifiers()))
                .as("Ct02Info must be static so it carries no hidden reference to its enclosing request")
                .isTrue();
        assertThat(NavigationContext.class.isAssignableFrom(nested)).isFalse();
    }

    @Test
    void commareaLengthIsTwoHundredAndEighteenOnlyWhenAnAreaWasPassed() {
        TransactionViewRequest fresh = new TransactionViewRequest();
        assertThat(fresh.hasNavigationContext()).isFalse();
        assertThat(fresh.commareaLength()).isZero();

        fresh.setNavigationContext(NavigationContext.empty());
        assertThat(fresh.hasNavigationContext()).isTrue();
        assertThat(fresh.commareaLength()).isEqualTo(218);

        fresh.setNavigationContext(null);
        assertThat(fresh.hasNavigationContext()).isFalse();
        assertThat(fresh.commareaLength()).isZero();
    }

    @Test
    void bothEightyEightLevelsAreReachableAndNeitherIsTheOthersNegation() {
        Ct02Info cursor = new Ct02Info();
        assertThat(Ct02Info.NEXT_PAGE_YES).isEqualTo("Y");
        assertThat(Ct02Info.NEXT_PAGE_NO).isEqualTo("N");

        assertThat(Ct02Info.NEXT_PAGE_DEFAULT).isEqualTo("N");
        assertThat(cursor.getNextPageFlg()).isEqualTo("N");
        assertThat(cursor.isNextPageNo()).isTrue();
        assertThat(cursor.isNextPageYes()).isFalse();

        cursor.setNextPageYes();
        assertThat(cursor.isNextPageYes()).isTrue();
        assertThat(cursor.isNextPageNo()).isFalse();

        cursor.setNextPageNo();
        assertThat(cursor.isNextPageNo()).isTrue();
        assertThat(cursor.isNextPageYes()).isFalse();

        cursor.setNextPageFlg(" ");
        assertThat(cursor.isNextPageYes()).isFalse();
        assertThat(cursor.isNextPageNo()).isFalse();

        cursor.setNextPageFlg(null);
        assertThat(cursor.isNextPageYes()).isFalse();
        assertThat(cursor.isNextPageNo()).isFalse();
    }

    @Test
    void bothProgramContextStatesAreDrivenAndNeitherIsTheOthersNegation() {
        assertThat(NavigationContext.PGM_CONTEXT_ENTER).isZero();
        assertThat(NavigationContext.PGM_CONTEXT_REENTER).isEqualTo(1);

        TransactionViewRequest request = new TransactionViewRequest();
        assertThat(request.isEnterContext()).isFalse();
        assertThat(request.isReenterContext()).isFalse();

        request.setNavigationContext(NavigationContext.empty().withPgmEnter());
        assertThat(request.isEnterContext()).isTrue();
        assertThat(request.isReenterContext()).isFalse();

        request.setNavigationContext(NavigationContext.empty().withPgmReenter());
        assertThat(request.isReenterContext()).isTrue();
        assertThat(request.isEnterContext()).isFalse();

        request.setNavigationContext(NavigationContext.empty().withPgmContext(9));
        assertThat(request.isEnterContext()).isFalse();
        assertThat(request.isReenterContext()).isFalse();
    }

    @Test
    void theCursorGroupRendersFiftyEightBytesAndRoundTrips() {
        Ct02Info cursor = new Ct02Info("0000000000000001", "0000000000000042", 7, "Y", "S",
                "0000000000000013");

        byte[] image = cursor.toFixedWidth(ASCII);
        assertThat(image).hasSize(58);

        String text = new String(image, ASCII);
        assertThat(text.substring(0, 16)).isEqualTo("0000000000000001");
        assertThat(text.substring(16, 32)).isEqualTo("0000000000000042");
        assertThat(text.substring(32, 40)).isEqualTo("00000007");
        assertThat(text.substring(40, 41)).isEqualTo("Y");
        assertThat(text.substring(41, 42)).isEqualTo("S");
        assertThat(text.substring(42, 58)).isEqualTo("0000000000000013");

        Ct02Info restored = Ct02Info.fromFixedWidth(image, ASCII);
        assertThat(restored).isEqualTo(cursor);
        assertThat(restored.hashCode()).isEqualTo(cursor.hashCode());
        assertThat(restored.getPageNum()).isEqualTo(7);
        assertThat(restored.isNextPageYes()).isTrue();
    }

    @Test
    void theCursorGroupRendersNullMembersAsSpaces() {
        Ct02Info cursor = new Ct02Info(null, null, 0, null, null, null);
        String text = new String(cursor.toFixedWidth(ASCII), ASCII);
        assertThat(text).hasSize(58);
        assertThat(text.substring(0, 32)).isEqualTo(" ".repeat(32));
        assertThat(text.substring(32, 40)).isEqualTo("00000000");
        assertThat(text.substring(40, 42)).isEqualTo("  ");
        assertThat(text.substring(42, 58)).isEqualTo(" ".repeat(16));
    }

    @Test
    void pageNumberIsAnUnsignedEightDigitField() {
        Ct02Info cursor = new Ct02Info();
        cursor.setPageNum(99_999_999);
        assertThat(cursor.getPageNum()).isEqualTo(99_999_999);
        cursor.setPageNum(0);
        assertThat(cursor.getPageNum()).isZero();

        assertThatThrownBy(() -> cursor.setPageNum(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsigned");
        assertThatThrownBy(() -> cursor.setPageNum(100_000_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("8 digits");
    }

    @Test
    void aNullCursorGroupIsReplacedByAFreshOneRatherThanLeftAbsent() {
        TransactionViewRequest request = new TransactionViewRequest();
        assertThat(request.getCt02Info()).isNotNull();

        request.setCt02Info(null);
        assertThat(request.getCt02Info()).isNotNull();
        assertThat(request.getCt02Info().getNextPageFlg()).isEqualTo("N");

        Ct02Info supplied = new Ct02Info();
        supplied.setPageNum(3);
        request.setCt02Info(supplied);
        assertThat(request.getCt02Info()).isSameAs(supplied);
    }

    @Test
    void carriesNoServerSideSessionStateOfAnyKind() {
        for (Class<?> type : typesUnderTest()) {
            for (Field field : type.getDeclaredFields()) {
                String fieldType = field.getType().getName();
                assertThat(fieldType)
                        .as("%s.%s must not hold server-side conversation state", type.getSimpleName(),
                                field.getName())
                        .doesNotContain("HttpSession")
                        .doesNotContain("ThreadLocal")
                        .doesNotContain("HttpServletRequest")
                        .doesNotContain("RequestContextHolder")
                        .doesNotContain("SecurityContext");
            }
        }
    }

    @Test
    void holdsNoStaticMutableState() {
        for (Class<?> type : typesUnderTest()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("static %s.%s must be final", type.getSimpleName(), field.getName())
                            .isTrue();
                    assertThat(Map.class.isAssignableFrom(field.getType())
                            || List.class.isAssignableFrom(field.getType())
                            || Set.class.isAssignableFrom(field.getType()))
                            .as("static %s.%s must not be a shared mutable collection",
                                    type.getSimpleName(), field.getName())
                            .isFalse();
                }
            }
        }
    }

    @Test
    void twoRequestsShareNoState() {
        TransactionViewRequest first = new TransactionViewRequest();
        TransactionViewRequest second = new TransactionViewRequest();

        first.setTrnamt("+00000123.45");
        first.requestCursor(ScreenField.TRNAMT);
        first.getCt02Info().setPageNum(5);

        assertThat(second.getTrnamt())
                .isEqualTo(TransactionViewRequest.spaces(TransactionViewRequest.TRNAMT_LENGTH));
        assertThat(second.isCursorRequested(ScreenField.TRNAMT)).isFalse();
        assertThat(second.getCt02Info().getPageNum()).isZero();
        assertThat(second.getCt02Info()).isNotSameAs(first.getCt02Info());
    }

    @Test
    void crossWidthMovesTruncateOnTheRightAndKeepTheLeadingCharacters() {
        String desc100 = "D".repeat(59) + "X" + "Z".repeat(40);
        assertThat(desc100).hasSize(100);

        String moved = CODEC.movePicX(desc100, TransactionViewRequest.TDESC_LENGTH);
        assertThat(moved).hasSize(60).isEqualTo("D".repeat(59) + "X").doesNotContain("Z");

        TransactionViewRequest request = new TransactionViewRequest();
        request.setTdesc(moved);
        assertThat(request.payloadImages().get("TDESCI")).isEqualTo(moved);
    }

    @ParameterizedTest
    @CsvSource({
        "TRAN-DESC,          100, TDESCI,   60",
        "TRAN-MERCHANT-NAME,  50, MNAMEI,   30",
        "TRAN-MERCHANT-CITY,  50, MCITYI,   25",
        "TRAN-ORIG-TS,        26, TORIGDTI, 10",
        "TRAN-PROC-TS,        26, TPROCDTI, 10"
    })
    void narrowingMovesKeepTheLeadingBytes(String sender, int senderWidth, String receiver,
                                           int receiverWidth) {
        assertThat(senderWidth).isGreaterThan(receiverWidth);
        assertThat(TransactionViewRequest.AI_LAYOUT.span(receiver).length()).isEqualTo(receiverWidth);

        StringBuilder ruler = new StringBuilder();
        for (int index = 0; index < senderWidth; index++) {
            ruler.append((char) ('0' + index % 10));
        }
        String moved = CODEC.movePicX(ruler.toString(), receiverWidth);

        assertThat(moved).hasSize(receiverWidth);
        assertThat(moved).isEqualTo(ruler.substring(0, receiverWidth));
        assertThat(sender).isNotBlank();
    }

    @ParameterizedTest
    @CsvSource({
        "TRAN-CARD-NUM,     CARDNINI, 16",
        "TRAN-SOURCE,       TRNSRCI,  10",
        "TRAN-TYPE-CD,      TTYPCDI,   2",
        "TRAN-MERCHANT-ZIP, MZIPI,    10"
    })
    void exactWidthMovesPassThroughUntouched(String sender, String receiver, int width) {
        assertThat(TransactionViewRequest.AI_LAYOUT.span(receiver).length()).isEqualTo(width);

        String value = "9".repeat(width);
        assertThat(CODEC.movePicX(value, width)).isEqualTo(value).hasSize(width);
        assertThat(sender).isNotBlank();
    }

    @Test
    void numericSendersLandInAlphanumericReceiversAsCharacters() {
        assertThat(TransactionViewRequest.TCATCD_LENGTH).isEqualTo(4);
        assertThat(TransactionViewRequest.MID_LENGTH).isEqualTo(9);

        assertThat(CODEC.movePic9("42", 4)).isEqualTo("0042");
        assertThat(CODEC.movePic9(123_456_789L, 9)).isEqualTo("123456789");

        TransactionViewRequest request = new TransactionViewRequest();
        request.setTcatcd(CODEC.movePic9("42", TransactionViewRequest.TCATCD_LENGTH));
        request.setMid(CODEC.movePic9(7L, TransactionViewRequest.MID_LENGTH));
        assertThat(request.getTcatcd()).isEqualTo("0042");
        assertThat(request.getMid()).isEqualTo("000000007");

        assertThat(request.getTcatcd()).isInstanceOf(String.class);
        assertThat(request.getMid()).isInstanceOf(String.class);
    }

    @Test
    void theAmountIsNeverAssignedFromTheScaledFieldDirectly() {
        for (Method method : TransactionViewRequest.class.getMethods()) {
            if (method.getName().equalsIgnoreCase("getTrnamt")
                    || method.getName().equalsIgnoreCase("setTrnamt")) {
                for (Class<?> parameter : method.getParameterTypes()) {
                    assertThat(parameter).isEqualTo(String.class);
                }
                if (method.getName().startsWith("get")) {
                    assertThat(method.getReturnType()).isEqualTo(String.class);
                }
            }
        }

        String rendered = "+00000123.45";
        assertThat(rendered).hasSize(TransactionViewRequest.TRNAMT_LENGTH);
        TransactionViewRequest request = new TransactionViewRequest();
        request.setTrnamt(rendered);
        assertThat(request.getTrnamt()).isEqualTo(rendered);
    }

    @ParameterizedTest
    @EnumSource(ScreenField.class)
    void shortValuesAreRightSpacePaddedToWidthNeitherShortNorNull(ScreenField field) {
        TransactionViewRequest request = new TransactionViewRequest();
        request.setPayloadValue(field, "A");

        String image = request.payloadImages().get(field.inputItem());
        assertThat(image).isNotNull().hasSize(field.length()).startsWith("A");
        if (field.length() > 1) {
            assertThat(image).isEqualTo("A" + " ".repeat(field.length() - 1)).endsWith(" ");
        }
    }

    @ParameterizedTest
    @EnumSource(ScreenField.class)
    void overWideValuesTruncateOnTheRightPerThePicXRule(ScreenField field) {
        TransactionViewRequest request = new TransactionViewRequest();
        String tooLong = "Q".repeat(field.length() + 5) + "TAIL";
        request.setPayloadValue(field, tooLong);

        String image = request.payloadImages().get(field.inputItem());
        assertThat(image).hasSize(field.length()).isEqualTo("Q".repeat(field.length()));
        assertThat(image).doesNotContain("TAIL");
    }

    @Test
    void theAccountKeyCardKeyAndMerchantIdAreCarriedUnmasked() {
        String acct = "00000000011";
        String card = "4111111111111111";
        String merchant = "000000042";

        TransactionViewRequest request = new TransactionViewRequest();
        request.setActidin(acct);
        request.setCardnin(card);
        request.setMid(merchant);

        assertThat(request.getActidin()).isEqualTo(acct);
        assertThat(request.getCardnin()).isEqualTo(card);
        assertThat(request.getMid()).isEqualTo(merchant);
        assertThat(request.payloadValue(ScreenField.ACTIDIN)).isEqualTo(acct);
        assertThat(request.payloadValue(ScreenField.CARDNIN)).isEqualTo(card);
        assertThat(request.payloadValue(ScreenField.MID)).isEqualTo(merchant);
        assertThat(request.payloadImages().get("ACTIDINI")).isEqualTo(acct);
        assertThat(request.payloadImages().get("CARDNINI")).isEqualTo(card);
        assertThat(request.payloadImages().get("MIDI")).isEqualTo(merchant);

        String image = new String(request.toFixedWidth(ASCII), ASCII);
        assertThat(image).contains(acct).contains(card).contains(merchant);
        assertThat(image).doesNotContain("*");
        assertThat(image.toLowerCase(Locale.ROOT)).doesNotContain("redacted");

        TransactionViewRequest restored =
                TransactionViewRequest.fromFixedWidth(request.toFixedWidth(ASCII), ASCII);
        assertThat(restored.getCardnin()).isEqualTo(card);
    }

    @Test
    void theWirePathIsUnmaskedEvenThoughTheDiagnosticPathRedacts() {
        String card = "4111111111111111";
        TransactionViewRequest request = new TransactionViewRequest();
        request.setCardnin(card);
        request.setActidin("00000000011");
        request.setMid("000000042");

        assertThat(request.toString()).doesNotContain(card);
        assertThat(new String(request.toFixedWidth(ASCII), ASCII)).contains(card);
        assertThat(request.toString()).contains("000000042");
        assertThat(request.toString()).contains("MIDI=").contains("CONFIRMI=").contains("TRNAMTI=");
    }

    @Test
    void theDiagnosticRenderingMarksTheCursorFieldAndCarriesBothStateObjects() {
        TransactionViewRequest request = populated();

        assertThat(request.toString()).doesNotContain("(cursor)");

        request.requestCursor(ScreenField.TRNAMT);
        String marked = request.toString();
        assertThat(marked).contains("(cursor)");
        assertThat(marked.indexOf("(cursor)")).isGreaterThan(marked.indexOf("TRNAMTI="));
        assertThat(marked.indexOf("(cursor)")).isLessThan(marked.indexOf("TORIGDTI="));

        assertThat(request.toString()).contains("navigationContext=null");
        request.setNavigationContext(NavigationContext.empty().withPgmReenter());
        assertThat(request.toString())
                .contains("navigationContext=")
                .doesNotContain("navigationContext=null")
                .contains("ct02Info=");

        assertThat(request.toString()).startsWith("TransactionViewRequest[CT02/COTRN02C/COTRN2AI");
    }

    @Test
    void everyPayloadMemberIsAStringAndNoneIsDoubleOrFloat() throws ReflectiveOperationException {
        for (ScreenField field : ScreenField.values()) {
            Method getter = TransactionViewRequest.class
                    .getMethod("get" + field.label().charAt(0)
                            + field.label().substring(1).toLowerCase(Locale.ROOT));
            assertThat(getter.getReturnType())
                    .as("accessor for %s returns String", field.inputItem())
                    .isEqualTo(String.class);
        }

        for (Class<?> type : typesUnderTest()) {
            for (Field member : type.getDeclaredFields()) {
                assertThat(member.getType())
                        .as("%s.%s", type.getSimpleName(), member.getName())
                        .isNotEqualTo(double.class)
                        .isNotEqualTo(float.class)
                        .isNotEqualTo(Double.class)
                        .isNotEqualTo(Float.class);
            }
        }
    }

    @Test
    void exposesNoRoundingSurfaceAtAllSoNoRoundingModeCanBeApplied() {
        for (Class<?> type : typesUnderTest()) {
            for (Field member : type.getDeclaredFields()) {
                assertThat(member.getType().getName())
                        .as("%s.%s", type.getSimpleName(), member.getName())
                        .doesNotContain("BigDecimal")
                        .doesNotContain("RoundingMode")
                        .doesNotContain("MathContext");
            }
            for (Method method : type.getDeclaredMethods()) {
                assertThat(method.getReturnType().getName())
                        .as("%s.%s return type", type.getSimpleName(), method.getName())
                        .doesNotContain("BigDecimal")
                        .doesNotContain("RoundingMode");
                for (Class<?> parameter : method.getParameterTypes()) {
                    assertThat(parameter.getName())
                            .as("%s.%s parameter", type.getSimpleName(), method.getName())
                            .doesNotContain("BigDecimal")
                            .doesNotContain("RoundingMode");
                }
            }
        }
    }

    @Test
    void carriesNoPersistenceAnnotationOrVersionColumn() {
        Set<String> forbidden = Set.of("Entity", "Table", "Column", "Id", "Version", "GeneratedValue",
                "JoinColumn", "Embeddable", "MappedSuperclass");

        for (Class<?> type : typesUnderTest()) {
            assertAnnotationsAreAllowed(forbidden, type.getSimpleName(), type.getAnnotations());
            for (Field member : type.getDeclaredFields()) {
                assertAnnotationsAreAllowed(forbidden, type.getSimpleName() + "." + member.getName(),
                        member.getAnnotations());
            }
            for (Method method : type.getDeclaredMethods()) {
                assertAnnotationsAreAllowed(forbidden, type.getSimpleName() + "." + method.getName(),
                        method.getAnnotations());
            }
        }
    }

    private static void assertAnnotationsAreAllowed(Set<String> forbidden, String subject,
                                                    Annotation[] annotations) {
        for (Annotation annotation : annotations) {
            String simpleName = annotation.annotationType().getSimpleName();
            assertThat(forbidden)
                    .as("%s carries @%s", subject, simpleName)
                    .doesNotContain(simpleName);
            assertThat(annotation.annotationType().getName())
                    .as("%s carries a persistence annotation", subject)
                    .doesNotContain("persistence");
        }
    }

    @Test
    void exactlyFourteenFieldsAreInputCapableWhichIsWhatMakesThisAnAddScreen() {
        List<String> inputCapable = new ArrayList<>();
        List<String> outputOnly = new ArrayList<>();
        for (ScreenField field : ScreenField.values()) {
            if (field.unprotectedField()) {
                inputCapable.add(field.label());
            } else {
                outputOnly.add(field.label());
            }
        }

        assertThat(inputCapable).containsExactlyElementsOf(EXPECTED_INPUT_CAPABLE).hasSize(14);
        assertThat(outputOnly).containsExactlyElementsOf(EXPECTED_OUTPUT_ONLY).hasSize(7);
        assertThat(inputCapable.size() + outputOnly.size()).isEqualTo(NAMED_DFHMDF_ENTRIES);

        assertThat(inputCapable).hasSizeGreaterThan(outputOnly.size());
    }

    @Test
    void theInitialCursorFieldIsTheAccountKey() {
        assertThat(EXPECTED_INPUT_CAPABLE.get(0)).isEqualTo("ACTIDIN");
        assertThat(ScreenField.ACTIDIN.unprotectedField()).isTrue();

        ScreenField firstUnprotected = null;
        for (ScreenField field : ScreenField.values()) {
            if (field.unprotectedField()) {
                firstUnprotected = field;
                break;
            }
        }
        assertThat(firstUnprotected).isEqualTo(ScreenField.ACTIDIN);
    }

    @Test
    void theErrorLineIsSeventyEightCharactersAndOutputOnly() {
        assertThat(TransactionViewRequest.ERRMSG_LENGTH).isEqualTo(78);
        assertThat(ScreenField.ERRMSG.length()).isEqualTo(78);
        assertThat(ScreenField.ERRMSG.unprotectedField()).isFalse();
        ScreenField[] fields = ScreenField.values();
        assertThat(fields[fields.length - 1]).isEqualTo(ScreenField.ERRMSG);
        assertThat(ScreenField.ERRMSG.payloadOffset() + ScreenField.ERRMSG.length())
                .isEqualTo(EXPECTED_GROUP_LENGTH);
    }

    private static List<Class<?>> typesUnderTest() {
        List<Class<?>> types = new ArrayList<>();
        types.add(TransactionViewRequest.class);
        types.add(Ct02Info.class);
        types.add(FieldMetadata.class);
        types.add(ScreenField.class);
        return types;
    }

    @Test
    void identityConstantsMatchTheCsdAndMapset() {
        assertThat(TransactionViewRequest.TRANSACTION_ID).isEqualTo("CT02");
        assertThat(TransactionViewRequest.PROGRAM_NAME).isEqualTo("COTRN02C");
        assertThat(TransactionViewRequest.MAPSET_NAME).isEqualTo("COTRN02");
        assertThat(TransactionViewRequest.MAP_NAME).isEqualTo("COTRN2A");
        assertThat(TransactionViewRequest.SYMBOLIC_MAP_INPUT_GROUP).isEqualTo("COTRN2AI");
        assertThat(TransactionViewRequest.SYMBOLIC_MAP_OUTPUT_GROUP).isEqualTo("COTRN2AO");
        assertThat(TransactionViewRequest.SYMBOLIC_MAP_OUTPUT_GROUP)
                .isEqualTo(TransactionViewRequest.SYMBOLIC_MAP_INPUT_GROUP
                        .substring(0, TransactionViewRequest.SYMBOLIC_MAP_INPUT_GROUP.length() - 1)
                        + "O");
    }

    @ParameterizedTest
    @EnumSource(ScreenField.class)
    void enumKeyedAccessorsAgreeWithTheBeanAccessors(ScreenField field) {
        TransactionViewRequest request = new TransactionViewRequest();
        request.setPayloadValue(field, "probe");
        assertThat(request.payloadValue(field)).isEqualTo("probe");

        request.setPayloadValue(field, sampleOf(field));
        assertThat(request.payloadImages().get(field.inputItem())).isEqualTo(sampleOf(field));
    }

    @Test
    void enumKeyedAccessorsRejectANullField() {
        TransactionViewRequest request = new TransactionViewRequest();
        assertThatThrownBy(() -> request.payloadValue(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> request.setPayloadValue(null, "x"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> request.metadata(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> request.requestCursor(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> request.isCursorRequested(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void everyBeanAccessorPairIsReachableAndIndependent() {
        TransactionViewRequest request = new TransactionViewRequest();
        request.setTrnname("CT02");
        request.setTitle01("T1");
        request.setCurdate("07/18/22");
        request.setPgmname("COTRN02C");
        request.setTitle02("T2");
        request.setCurtime("11:22:33");
        request.setActidin("00000000011");
        request.setCardnin("4111111111111111");
        request.setTtypcd("01");
        request.setTcatcd("0001");
        request.setTrnsrc("POS");
        request.setTdesc("GROCERY");
        request.setTrnamt("+00000123.45");
        request.setTorigdt("2022-07-18");
        request.setTprocdt("2022-07-19");
        request.setMid("000000042");
        request.setMname("ACME");
        request.setMcity("SEATTLE");
        request.setMzip("98101");
        request.setConfirm("Y");
        request.setErrmsg("");

        assertThat(request.getTrnname()).isEqualTo("CT02");
        assertThat(request.getTitle01()).isEqualTo("T1");
        assertThat(request.getCurdate()).isEqualTo("07/18/22");
        assertThat(request.getPgmname()).isEqualTo("COTRN02C");
        assertThat(request.getTitle02()).isEqualTo("T2");
        assertThat(request.getCurtime()).isEqualTo("11:22:33");
        assertThat(request.getActidin()).isEqualTo("00000000011");
        assertThat(request.getCardnin()).isEqualTo("4111111111111111");
        assertThat(request.getTtypcd()).isEqualTo("01");
        assertThat(request.getTcatcd()).isEqualTo("0001");
        assertThat(request.getTrnsrc()).isEqualTo("POS");
        assertThat(request.getTdesc()).isEqualTo("GROCERY");
        assertThat(request.getTrnamt()).isEqualTo("+00000123.45");
        assertThat(request.getTorigdt()).isEqualTo("2022-07-18");
        assertThat(request.getTprocdt()).isEqualTo("2022-07-19");
        assertThat(request.getMid()).isEqualTo("000000042");
        assertThat(request.getMname()).isEqualTo("ACME");
        assertThat(request.getMcity()).isEqualTo("SEATTLE");
        assertThat(request.getMzip()).isEqualTo("98101");
        assertThat(request.getConfirm()).isEqualTo("Y");
        assertThat(request.getErrmsg()).isEmpty();

        assertThat(TransactionViewRequest.CURDATE_LENGTH).isEqualTo(8);
        assertThat(TransactionViewRequest.CURTIME_LENGTH).isEqualTo(8);
    }

    @Test
    void allArgsConstructorStoresEveryFieldExactlyAsSupplied() {
        NavigationContext carrier = NavigationContext.empty().withPgmReenter();
        Ct02Info cursor = new Ct02Info();
        cursor.setPageNum(2);

        TransactionViewRequest request = new TransactionViewRequest(
                "CT02", "T1", "07/18/22", "COTRN02C", "T2", "11:22:33",
                "00000000011", "4111111111111111", "01", "0001", "POS", "GROCERY",
                "+00000123.45", "2022-07-18", "2022-07-19", "000000042", "ACME", "SEATTLE",
                "98101", "Y", "", carrier, cursor);

        assertThat(request.getTrnname()).isEqualTo("CT02");
        assertThat(request.getConfirm()).isEqualTo("Y");
        assertThat(request.getTrnamt()).isEqualTo("+00000123.45");
        assertThat(request.getNavigationContext()).isSameAs(carrier);
        assertThat(request.getCt02Info()).isSameAs(cursor);
        assertThat(request.isReenterContext()).isTrue();
        for (ScreenField field : ScreenField.values()) {
            assertThat(request.metadata(field)).isNotNull();
            assertThat(request.isCursorRequested(field)).isFalse();
        }
    }

    @Test
    void allArgsConstructorSubstitutesAFreshCursorWhenNoneIsSupplied() {
        TransactionViewRequest request = new TransactionViewRequest(
                "CT02", "T1", "07/18/22", "COTRN02C", "T2", "11:22:33",
                "00000000011", "4111111111111111", "01", "0001", "POS", "GROCERY",
                "+00000123.45", "2022-07-18", "2022-07-19", "000000042", "ACME", "SEATTLE",
                "98101", "N", "", null, null);

        assertThat(request.getNavigationContext()).isNull();
        assertThat(request.hasNavigationContext()).isFalse();
        assertThat(request.commareaLength()).isZero();
        assertThat(request.isEnterContext()).isFalse();
        assertThat(request.isReenterContext()).isFalse();

        assertThat(request.getCt02Info()).isNotNull();
        assertThat(request.getCt02Info().getNextPageFlg()).isEqualTo(Ct02Info.NEXT_PAGE_DEFAULT);
        assertThat(request.getCt02Info().isNextPageNo()).isTrue();
        assertThat(request.getCt02Info().getPageNum()).isZero();

        assertThat(request.toFixedWidth(ASCII)).hasSize(EXPECTED_GROUP_LENGTH);
        assertThat(request.getAid()).isEqualTo("     ");
    }

    @Test
    void copyConstructorSharesNoMutableState() {
        TransactionViewRequest original = populated();
        original.requestCursor(ScreenField.CONFIRM);
        original.getCt02Info().setPageNum(4);
        original.setNavigationContext(NavigationContext.empty().withPgmEnter());

        TransactionViewRequest copy = new TransactionViewRequest(original);
        assertThat(copy).isEqualTo(original);

        copy.getCt02Info().setPageNum(9);
        assertThat(original.getCt02Info().getPageNum()).isEqualTo(4);
        assertThat(copy.getCt02Info()).isNotSameAs(original.getCt02Info());

        copy.resetMetadata();
        assertThat(original.isCursorRequested(ScreenField.CONFIRM)).isTrue();
        assertThat(copy.isCursorRequested(ScreenField.CONFIRM)).isFalse();

        FieldMetadata source = new FieldMetadata(12, '*');
        FieldMetadata clone = new FieldMetadata(source);
        assertThat(clone).isEqualTo(source);
        clone.reset();
        assertThat(source.length()).isEqualTo(12);
        assertThat(source.attribute()).isEqualTo('*');

        Ct02Info cursorSource = new Ct02Info();
        cursorSource.setPageNum(6);
        Ct02Info cursorClone = new Ct02Info(cursorSource);
        assertThat(cursorClone).isEqualTo(cursorSource);
        cursorClone.setPageNum(7);
        assertThat(cursorSource.getPageNum()).isEqualTo(6);
    }

    @Test
    void copyConstructorsRejectNull() {
        assertThatThrownBy(() -> new TransactionViewRequest((TransactionViewRequest) null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FieldMetadata((FieldMetadata) null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Ct02Info((Ct02Info) null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void fixedWidthEntryPointsRejectNullArguments() {
        assertThatThrownBy(() -> TransactionViewRequest.fromFixedWidth(null, ASCII))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> TransactionViewRequest.readFrom(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> populated().writeInto(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Ct02Info.fromFixedWidth(null, ASCII))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void equalityAccountsForMetadataBecauseACursorRequestIsPartOfTheRequest() {
        TransactionViewRequest left = populated();
        TransactionViewRequest right = populated();
        assertThat(left).isEqualTo(right).hasSameHashCodeAs(right);

        right.requestCursor(ScreenField.TRNAMT);
        assertThat(left).isNotEqualTo(right);

        right.resetMetadata();
        assertThat(left).isEqualTo(right);

        right.setConfirm("Y");
        assertThat(left).isNotEqualTo(right);
    }

    @Test
    void requestEqualsDistinguishesBothCarriedStateObjects() {
        TransactionViewRequest base = populated();

        TransactionViewRequest differentCarrier = new TransactionViewRequest(base);
        differentCarrier.setNavigationContext(NavigationContext.empty().withPgmReenter());
        assertThat(base).isNotEqualTo(differentCarrier);

        TransactionViewRequest differentCursor = new TransactionViewRequest(base);
        differentCursor.getCt02Info().setPageNum(11);
        assertThat(base).isNotEqualTo(differentCursor);

        TransactionViewRequest differentAid = new TransactionViewRequest(base);
        differentAid.setAid("PF03 ");
        assertThat(base).isNotEqualTo(differentAid);
    }

    @Test
    void requestEqualsCoversEveryShortCircuit() {
        TransactionViewRequest request = populated();
        assertThat(request).isEqualTo(request);
        assertThat(request).isNotEqualTo(null);
        assertThat(request).isNotEqualTo("COTRN2AI");
        assertThat(request).isEqualTo(populated());
    }

    @Test
    void fieldMetadataEqualsCoversEveryShortCircuit() {
        FieldMetadata metadata = new FieldMetadata(5, 'A');
        assertThat(metadata).isEqualTo(metadata);
        assertThat(metadata).isNotEqualTo(null);
        assertThat(metadata).isNotEqualTo("5");
        assertThat(metadata).isEqualTo(new FieldMetadata(5, 'A'))
                .hasSameHashCodeAs(new FieldMetadata(5, 'A'));
        assertThat(metadata).isNotEqualTo(new FieldMetadata(6, 'A'));
        assertThat(metadata).isNotEqualTo(new FieldMetadata(5, 'B'));
        assertThat(metadata.toString()).contains("length=5").contains("attribute=0x");
    }

    @Test
    void cursorEqualsCoversEveryShortCircuit() {
        Ct02Info cursor = new Ct02Info("F", "L", 1, "Y", "S", "T");
        assertThat(cursor).isEqualTo(cursor);
        assertThat(cursor).isNotEqualTo(null);
        assertThat(cursor).isNotEqualTo("CDEMO-CT02-INFO");
        assertThat(cursor).isEqualTo(new Ct02Info("F", "L", 1, "Y", "S", "T"))
                .hasSameHashCodeAs(new Ct02Info("F", "L", 1, "Y", "S", "T"));

        assertThat(cursor).isNotEqualTo(new Ct02Info("X", "L", 1, "Y", "S", "T"));
        assertThat(cursor).isNotEqualTo(new Ct02Info("F", "X", 1, "Y", "S", "T"));
        assertThat(cursor).isNotEqualTo(new Ct02Info("F", "L", 2, "Y", "S", "T"));
        assertThat(cursor).isNotEqualTo(new Ct02Info("F", "L", 1, "N", "S", "T"));
        assertThat(cursor).isNotEqualTo(new Ct02Info("F", "L", 1, "Y", "X", "T"));
        assertThat(cursor).isNotEqualTo(new Ct02Info("F", "L", 1, "Y", "S", "X"));

        assertThat(cursor.toString()).contains("CDEMO-CT02");
    }

    @Test
    void everyCursorAccessorPairIsReachable() {
        Ct02Info cursor = new Ct02Info();
        cursor.setTrnidFirst("0000000000000001");
        cursor.setTrnidLast("0000000000000099");
        cursor.setPageNum(3);
        cursor.setNextPageFlg("Y");
        cursor.setTrnSelFlg("S");
        cursor.setTrnSelected("0000000000000007");

        assertThat(cursor.getTrnidFirst()).isEqualTo("0000000000000001");
        assertThat(cursor.getTrnidLast()).isEqualTo("0000000000000099");
        assertThat(cursor.getPageNum()).isEqualTo(3);
        assertThat(cursor.getNextPageFlg()).isEqualTo("Y");
        assertThat(cursor.getTrnSelFlg()).isEqualTo("S");
        assertThat(cursor.getTrnSelected()).isEqualTo("0000000000000007");
    }

    @Test
    void theAidTokenIsFiveCharactersAndDefaultsToNoKeyResolved() {
        assertThat(TransactionViewRequest.AID_LENGTH).isEqualTo(5);
        assertThat(TransactionViewRequest.AID_FIELD).isEqualTo("EIBAID");

        TransactionViewRequest request = new TransactionViewRequest();
        assertThat(request.getAid())
                .hasSize(TransactionViewRequest.AID_LENGTH)
                .isEqualTo("     ");

        for (String token : new String[] {"ENTER", "PF03 ", "PF04 ", "CLEAR", "PA1  "}) {
            assertThat(token).hasSize(TransactionViewRequest.AID_LENGTH);
            request.setAid(token);
            assertThat(request.getAid()).isEqualTo(token);
        }

        request.setAid(null);
        assertThat(request.getAid()).isEqualTo("     ");
    }

    @Test
    void anOverLongAidTokenIsRefusedByNameWithoutEchoingIt() {
        TransactionViewRequest request = new TransactionViewRequest();
        assertThatThrownBy(() -> request.setAid("PF13TOOLONG"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(TransactionViewRequest.AID_FIELD)
                .hasMessageNotContaining("PF13TOOLONG");
    }

    @Test
    void theAidIsNotAScreenFieldAndDoesNotWidenTheProjection() {
        for (ScreenField field : ScreenField.values()) {
            assertThat(field.label()).isNotEqualTo(TransactionViewRequest.AID_FIELD);
        }
        assertThat(TransactionViewRequest.AI_LAYOUT.hasSpan("EIBAID")).isFalse();

        TransactionViewRequest request = populated();
        request.setAid("ENTER");
        assertThat(request.toFixedWidth(ASCII)).hasSize(EXPECTED_GROUP_LENGTH);
        assertThat(request.payloadImages()).hasSize(NAMED_DFHMDF_ENTRIES);
    }

    @Test
    void widthConstraintsAcceptTheDeclaredWidthAndRejectOnlyOverflow() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            TransactionViewRequest atWidth = populated();
            assertThat(validator.validate(atWidth)).isEmpty();

            TransactionViewRequest tooWide = populated();
            tooWide.setTrnamt("+000000123.45");
            Set<ConstraintViolation<TransactionViewRequest>> violations = validator.validate(tooWide);
            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("trnamt");
        }
    }

    @Test
    void nullFieldsAreAcceptedBecauseAnAbsentScreenFieldIsLegal() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            TransactionViewRequest sparse = new TransactionViewRequest();
            for (ScreenField field : ScreenField.values()) {
                sparse.setPayloadValue(field, null);
            }
            assertThat(validator.validate(sparse)).isEmpty();
        }
    }

    @Test
    void noNumericOrDateConstraintPreEmptsTheCobolsOwnEditing() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            TransactionViewRequest malformed = new TransactionViewRequest();
            malformed.setTrnamt("NOT-A-NUM   ");
            malformed.setTorigdt("2022/07/18");
            malformed.setTprocdt("XX-XX-XXXX");
            malformed.setActidin("ABCDEFGHIJK");
            malformed.setCardnin("NOT-A-CARD-NUM  ");
            malformed.setConfirm("?");

            assertThat(validator.validate(malformed)).isEmpty();
            assertThat(amountMaskRejects(malformed.getTrnamt())).isTrue();
            assertThat(dateMaskRejects(malformed.getTorigdt())).isTrue();
            assertThat(confirmOutcome(malformed.getConfirm())).isEqualTo(ConfirmOutcome.INVALID);
        }
    }

    @Test
    void nestedCursorConstraintsCascade() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            TransactionViewRequest request = populated();
            Ct02Info cursor = new Ct02Info();
            cursor.setTrnidFirst("0".repeat(Ct02Info.TRNID_FIRST_LENGTH + 1));
            request.setCt02Info(cursor);

            Set<ConstraintViolation<TransactionViewRequest>> violations = validator.validate(request);
            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath().toString())
                    .isEqualTo("ct02Info.trnidFirst");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"US-ASCII", "IBM037"})
    void theCharsetIsAlwaysNamedNeverInheritedFromThePlatform(String charsetName) {
        Charset charset = Charset.forName(charsetName);
        TransactionViewRequest request = populated();

        byte[] image = request.toFixedWidth(charset);
        assertThat(image).hasSize(EXPECTED_GROUP_LENGTH);

        TransactionViewRequest restored = TransactionViewRequest.fromFixedWidth(image, charset);
        for (ScreenField field : ScreenField.values()) {
            assertThat(restored.payloadValue(field)).isEqualTo(sampleOf(field));
        }

        if ("IBM037".equals(charsetName)) {
            assertThat(image).isNotEqualTo(request.toFixedWidth(StandardCharsets.US_ASCII));
        }
    }

    @Test
    void theRenderedImageIsPureAsciiSoNoTranscodingSurpriseIsHidden() {
        byte[] image = populated().toFixedWidth(ASCII);
        for (byte rendered : image) {
            assertThat(rendered).as("every rendered byte is 7-bit").isBetween((byte) 0, (byte) 127);
        }
    }
}
