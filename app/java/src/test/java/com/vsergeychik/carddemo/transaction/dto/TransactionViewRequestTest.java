package com.vsergeychik.carddemo.transaction.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
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
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link TransactionViewRequest}, the inbound REST payload of CSD transaction
 * {@code CT02} (program {@code COTRN02C}, mapset {@code COTRN02}).
 *
 * <p>Every declared width, byte total and payload offset is asserted against the copybook and mapset
 * independently of the production constants, so a transcription slip in either cannot pass unnoticed.
 * The two {@code 88}-levels of {@code CDEMO-CT02-NEXT-PAGE-FLG} and the enter/re-enter context are each
 * driven from both sides, and the neither-state is asserted too, because the conditions are not
 * exhaustive over a {@code PIC X(01)} / {@code PIC 9(01)} field.
 */
class TransactionViewRequestTest {

    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** The 21 verbatim xxxI item names of 01 COTRN2AI, in copybook declaration order. */
    private static final List<String> EXPECTED_INPUT_ITEMS = List.of(
            "TRNNAMEI", "TITLE01I", "CURDATEI", "PGMNAMEI", "TITLE02I", "CURTIMEI",
            "ACTIDINI", "CARDNINI", "TTYPCDI", "TCATCDI", "TRNSRCI", "TDESCI",
            "TRNAMTI", "TORIGDTI", "TPROCDTI", "MIDI", "MNAMEI", "MCITYI",
            "MZIPI", "CONFIRMI", "ERRMSGI");

    /** label -> declared width, from app/cpy-bms/COTRN02.CPY cross-checked against app/bms/COTRN02.bms. */
    private static final Map<String, Integer> EXPECTED_WIDTHS = buildExpectedWidths();

    /** label -> payload offset in the 555-byte image, derived independently in Python during discovery. */
    private static final Map<String, Integer> EXPECTED_OFFSETS = buildExpectedOffsets();

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
        return widths;
    }

    private static Map<String, Integer> buildExpectedOffsets() {
        Map<String, Integer> offsets = new LinkedHashMap<>();
        offsets.put("TRNNAME", 19);
        offsets.put("TITLE01", 30);
        offsets.put("CURDATE", 77);
        offsets.put("PGMNAME", 92);
        offsets.put("TITLE02", 107);
        offsets.put("CURTIME", 154);
        offsets.put("ACTIDIN", 169);
        offsets.put("CARDNIN", 187);
        offsets.put("TTYPCD", 210);
        offsets.put("TCATCD", 219);
        offsets.put("TRNSRC", 230);
        offsets.put("TDESC", 247);
        offsets.put("TRNAMT", 314);
        offsets.put("TORIGDT", 333);
        offsets.put("TPROCDT", 350);
        offsets.put("MID", 367);
        offsets.put("MNAME", 383);
        offsets.put("MCITY", 420);
        offsets.put("MZIP", 452);
        offsets.put("CONFIRM", 469);
        offsets.put("ERRMSG", 477);
        return offsets;
    }

    // ---------------------------------------------------------------------------------------------
    // Field count and verbatim naming.
    // ---------------------------------------------------------------------------------------------

    @Test
    void projectsExactlyTwentyOnePayloadFieldsInCopybookOrder() {
        assertThat(ScreenField.values()).hasSize(21);
        assertThat(TransactionViewRequest.PAYLOAD_FIELD_COUNT).isEqualTo(21);

        List<String> actual = new ArrayList<>();
        for (ScreenField field : ScreenField.values()) {
            actual.add(field.inputItem());
        }
        assertThat(actual).containsExactlyElementsOf(EXPECTED_INPUT_ITEMS);
    }

    @Test
    void carriesNeitherTrnidinNorTrnidBecauseThoseBelongToTheCotrn01Map() {
        List<String> labels = new ArrayList<>();
        for (ScreenField field : ScreenField.values()) {
            labels.add(field.label());
        }
        assertThat(labels).doesNotContain("TRNIDIN", "TRNID");

        List<String> items = new ArrayList<>();
        for (ScreenField field : ScreenField.values()) {
            items.add(field.inputItem());
            items.add(field.outputItem());
        }
        assertThat(items).doesNotContain("TRNIDINI", "TRNIDI", "TRNIDINO", "TRNIDO");
    }

    @Test
    void derivesTheFourSymbolicMapItemNamesTheWayBmsDoes() {
        assertThat(ScreenField.TRNAMT.inputItem()).isEqualTo("TRNAMTI");
        assertThat(ScreenField.TRNAMT.outputItem()).isEqualTo("TRNAMTO");
        assertThat(ScreenField.TRNAMT.lengthItem()).isEqualTo("TRNAMTL");
        assertThat(ScreenField.TRNAMT.flagItem()).isEqualTo("TRNAMTF");
        assertThat(ScreenField.TRNAMT.attributeItem()).isEqualTo("TRNAMTA");
    }

    // ---------------------------------------------------------------------------------------------
    // Width and offset fidelity - gate G9.
    // ---------------------------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(ScreenField.class)
    void everyFieldMatchesItsDeclaredWidthAndOffset(ScreenField field) {
        assertThat(field.length())
                .as("%s width", field.label())
                .isEqualTo(EXPECTED_WIDTHS.get(field.label()));
        assertThat(field.payloadOffset())
                .as("%s payload offset", field.label())
                .isEqualTo(EXPECTED_OFFSETS.get(field.label()));
        assertThat(field.groupOffset())
                .isEqualTo(field.payloadOffset() - TransactionViewRequest.METADATA_PREFIX_LENGTH);
    }

    @Test
    void accountKeyIsElevenAndOnlyTheCardKeyIsSixteen() {
        assertThat(TransactionViewRequest.ACTIDIN_LENGTH).isEqualTo(11);
        assertThat(ScreenField.ACTIDIN.length()).isEqualTo(11);
        assertThat(TransactionViewRequest.CARDNIN_LENGTH).isEqualTo(16);
        assertThat(ScreenField.CARDNIN.length()).isEqualTo(16);
    }

    @Test
    void amountIsTwelveCharactersMatchingTheEditMask() {
        assertThat(TransactionViewRequest.TRNAMT_LENGTH).isEqualTo(12);
        assertThat("+99999999.99").hasSize(12);
    }

    // ---------------------------------------------------------------------------------------------
    // Byte totals: 396 / 555 / 58 / 218.
    // ---------------------------------------------------------------------------------------------

    @Test
    void byteTotalsMatchTheCopybookArithmetic() {
        int widths = 0;
        for (ScreenField field : ScreenField.values()) {
            widths += field.length();
        }
        assertThat(widths).isEqualTo(396);
        assertThat(TransactionViewRequest.PAYLOAD_WIDTH_TOTAL).isEqualTo(396);

        assertThat(TransactionViewRequest.TIOAPFX_PREFIX_LENGTH).isEqualTo(12);
        assertThat(TransactionViewRequest.METADATA_PREFIX_LENGTH).isEqualTo(7);
        assertThat(TransactionViewRequest.AI_GROUP_LENGTH).isEqualTo(12 + 21 * 7 + 396);
        assertThat(TransactionViewRequest.AI_GROUP_LENGTH).isEqualTo(555);

        assertThat(Ct02Info.CT02_INFO_LENGTH).isEqualTo(58);
        assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(160);
        assertThat(Ct02Info.COMMAREA_TOTAL_LENGTH).isEqualTo(218);
    }

    @Test
    void layoutSelfCheckProvesTheGroupGeometry() {
        assertThat(TransactionViewRequest.AI_LAYOUT.recordLength()).isEqualTo(555);
        for (ScreenField field : ScreenField.values()) {
            var span = TransactionViewRequest.AI_LAYOUT.span(field.inputItem());
            assertThat(span.offset()).isEqualTo(field.payloadOffset());
            assertThat(span.length()).isEqualTo(field.length());
        }
        assertThat(Ct02Info.LAYOUT.recordLength()).isEqualTo(58);
    }

    @Test
    void fourteenFieldsAreInputCapableWhichIsWhatMakesThisAnAddScreen() {
        List<String> unprotected = new ArrayList<>();
        for (ScreenField field : ScreenField.values()) {
            if (field.unprotectedField()) {
                unprotected.add(field.label());
            }
        }
        assertThat(unprotected).containsExactly(
                "ACTIDIN", "CARDNIN", "TTYPCD", "TCATCD", "TRNSRC", "TDESC", "TRNAMT",
                "TORIGDT", "TPROCDT", "MID", "MNAME", "MCITY", "MZIP", "CONFIRM");
        assertThat(unprotected).hasSize(14);
    }

    // ---------------------------------------------------------------------------------------------
    // Fixed-width image and lossless round trip.
    // ---------------------------------------------------------------------------------------------

    @Test
    void rendersExactlyFiveHundredAndFiftyFiveBytes() {
        assertThat(new TransactionViewRequest().toFixedWidth(ASCII)).hasSize(555);
    }

    @Test
    void spacePaddedValuesSurviveAFixedWidthRoundTripUntrimmed() {
        TransactionViewRequest request = new TransactionViewRequest();
        String errmsg = "Invalid value. Valid values are (Y/N)...";
        request.setErrmsg(pad(errmsg, 78));
        request.setTrnamt("+00000100.00");
        request.setConfirm("Y");
        request.setActidin("00000000011");
        request.setCardnin("4111111111111111");
        request.setMid("123456789");

        TransactionViewRequest decoded =
                TransactionViewRequest.fromFixedWidth(request.toFixedWidth(ASCII), ASCII);

        assertThat(decoded.getErrmsg()).hasSize(78).isEqualTo(pad(errmsg, 78));
        assertThat(decoded.getTrnamt()).hasSize(12).isEqualTo("+00000100.00");
        assertThat(decoded.getConfirm()).hasSize(1).isEqualTo("Y");
        assertThat(decoded.getActidin()).hasSize(11).isEqualTo("00000000011");
        assertThat(decoded.getCardnin()).hasSize(16).isEqualTo("4111111111111111");
        assertThat(decoded.getMid()).hasSize(9).isEqualTo("123456789");
    }

    @Test
    void everyFieldLandsAtItsTrueOffsetInTheImage() {
        TransactionViewRequest request = new TransactionViewRequest();
        request.setTrnamt("-00000042.75");
        request.setConfirm("N");
        request.setCardnin("4111111111111111");

        String image = new String(request.toFixedWidth(ASCII), ASCII);
        assertThat(image).hasSize(555);
        assertThat(image.substring(314, 314 + 12)).as("TRNAMTI").isEqualTo("-00000042.75");
        assertThat(image.substring(469, 469 + 1)).as("CONFIRMI").isEqualTo("N");
        assertThat(image.substring(187, 187 + 16)).as("CARDNINI").isEqualTo("4111111111111111");
    }

    @Test
    void writeIntoAndReadFromAgreeWithTheCodecPath() {
        TransactionViewRequest request = new TransactionViewRequest();
        request.setTdesc(pad("GROCERY STORE PURCHASE", 60));
        request.setTorigdt("2022-07-18");
        request.setTprocdt("2022-07-19");

        FixedWidthRecord area = FixedWidthRecord.forLayout(TransactionViewRequest.AI_LAYOUT, ASCII);
        request.writeInto(area);
        TransactionViewRequest decoded = TransactionViewRequest.readFrom(area);

        assertThat(decoded.getTdesc()).isEqualTo(pad("GROCERY STORE PURCHASE", 60));
        assertThat(decoded.getTorigdt()).isEqualTo("2022-07-18");
        assertThat(decoded.getTprocdt()).isEqualTo("2022-07-19");
        assertThat(area.toByteArray()).hasSize(555);
    }

    @Test
    void rejectsAWorkAreaOfTheWrongWidth() {
        FixedWidthRecord tooNarrow = new FixedWidthRecord(100, ASCII);
        assertThatThrownBy(() -> new TransactionViewRequest().writeInto(tooNarrow))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("555");
        assertThatThrownBy(() -> TransactionViewRequest.readFrom(tooNarrow))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullFieldRendersAsLowValuesReproducingAFieldCicsDidNotTransmit() {
        TransactionViewRequest request = new TransactionViewRequest();
        request.setTrnamt(null);
        String image = new String(request.toFixedWidth(ASCII), ASCII);
        assertThat(image.substring(314, 326)).isEqualTo("\u0000".repeat(12));
        assertThat(TransactionViewRequest.lowValues(3)).isEqualTo("\u0000\u0000\u0000");
        assertThat(TransactionViewRequest.spaces(3)).isEqualTo("   ");
    }

    @Test
    void overWideValueTruncatesOnTheRightPerThePicXRule() {
        TransactionViewRequest request = new TransactionViewRequest();
        request.setTtypcd("ABCDEF");
        String image = new String(request.toFixedWidth(ASCII), ASCII);
        assertThat(image.substring(210, 212)).isEqualTo("AB");
    }

    @Test
    void payloadImagesAreKeyedByVerbatimCopybookNames() {
        Map<String, String> images = new TransactionViewRequest().payloadImages();
        assertThat(images).hasSize(21);
        assertThat(images.keySet()).containsExactlyElementsOf(EXPECTED_INPUT_ITEMS);
        images.forEach((name, image) -> assertThat(image).as(name).isNotNull());
    }

    // ---------------------------------------------------------------------------------------------
    // JSON: untrimmed round trip, and no metadata on the wire.
    // ---------------------------------------------------------------------------------------------

    @Test
    void jsonRoundTripPreservesTrailingSpacesAndEquality() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        TransactionViewRequest request = new TransactionViewRequest();
        request.setErrmsg(pad("Confirm to add this transaction...", 78));
        request.setTrnamt("+00000100.00");
        request.setConfirm(" ");
        request.setCardnin("4111111111111111");
        request.getCt02Info().setNextPageYes();
        request.getCt02Info().setPageNum(7);
        request.setNavigationContext(NavigationContext.empty().withPgmReenter());

        String json = mapper.writeValueAsString(request);
        TransactionViewRequest decoded = mapper.readValue(json, TransactionViewRequest.class);

        assertThat(decoded.getErrmsg()).hasSize(78);
        assertThat(decoded.getErrmsg()).isEqualTo(request.getErrmsg());
        assertThat(decoded.getTrnamt()).isEqualTo("+00000100.00");
        assertThat(decoded.getConfirm()).isEqualTo(" ");
        assertThat(decoded.getCardnin()).isEqualTo("4111111111111111");
        assertThat(decoded.getCt02Info().isNextPageYes()).isTrue();
        assertThat(decoded.getCt02Info().getPageNum()).isEqualTo(7);
        assertThat(decoded.isReenterContext()).isTrue();
        assertThat(decoded).isEqualTo(request);
        assertThat(decoded).hasSameHashCodeAs(request);
    }

    @Test
    void metadataAndDerivedPredicatesNeverAppearOnTheWire() throws Exception {
        String json = new ObjectMapper().writeValueAsString(new TransactionViewRequest());

        // xxxL / xxxF / xxxA are metadata, never payload.
        assertThat(json).doesNotContain("fieldMetadata", "TRNAMTL", "TRNAMTF", "TRNAMTA",
                "ACTIDINL", "CONFIRML", "metadata");
        // Derived predicates are not DFHMDF fields.
        assertThat(json).doesNotContain("enterContext", "reenterContext",
                "nextPageYes", "nextPageNo");
        // But every one of the 21 payload members is present.
        for (ScreenField field : ScreenField.values()) {
            assertThat(json).as(field.label()).contains("\"" + beanName(field.label()) + "\"");
        }
    }

    private static String beanName(String label) {
        return label.toLowerCase(java.util.Locale.ROOT);
    }

    // ---------------------------------------------------------------------------------------------
    // Metadata carriers: signed length holding -1, and xxxA aliasing xxxF.
    // ---------------------------------------------------------------------------------------------

    @Test
    void lengthItemIsSignedAndHoldsMinusOneWithoutClamping() {
        TransactionViewRequest request = new TransactionViewRequest();
        assertThat(TransactionViewRequest.CURSOR_REQUEST).isEqualTo(-1);
        assertThat(request.isCursorRequested(ScreenField.TRNAMT)).isFalse();

        request.requestCursor(ScreenField.TRNAMT);

        assertThat(request.metadata(ScreenField.TRNAMT).length()).isEqualTo(-1);
        assertThat(request.isCursorRequested(ScreenField.TRNAMT)).isTrue();
        assertThat(request.metadata(ScreenField.TRNAMT).hasInput()).isFalse();
        assertThat(request.isCursorRequested(ScreenField.ACTIDIN)).isFalse();

        request.metadata(ScreenField.ACTIDIN).setLength(11);
        assertThat(request.metadata(ScreenField.ACTIDIN).hasInput()).isTrue();

        request.resetMetadata();
        assertThat(request.metadata(ScreenField.TRNAMT).length()).isZero();
        assertThat(request.isCursorRequested(ScreenField.TRNAMT)).isFalse();
    }

    @Test
    void lengthItemRejectsValuesOutsideASignedHalfword() {
        FieldMetadata metadata = new FieldMetadata();
        assertThatThrownBy(() -> metadata.setLength(40_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("S9(4)");
        assertThatThrownBy(() -> metadata.setLength(-40_000))
                .isInstanceOf(IllegalArgumentException.class);
        metadata.setLength(Short.MAX_VALUE);
        assertThat(metadata.length()).isEqualTo(Short.MAX_VALUE);
        metadata.setLength(Short.MIN_VALUE);
        assertThat(metadata.length()).isEqualTo(Short.MIN_VALUE);
    }

    @Test
    void attributeAndFlagAreOneByteViewedThroughTwoNames() {
        FieldMetadata metadata = new FieldMetadata();
        assertThat(metadata.flag()).isEqualTo(FieldMetadata.UNSET_ATTRIBUTE);
        assertThat(metadata.attribute()).isEqualTo(metadata.flag());

        metadata.setFlag('R');
        assertThat(metadata.attribute()).isEqualTo('R');

        metadata.setAttribute('*');
        assertThat(metadata.flag()).isEqualTo('*');

        assertThat(new FieldMetadata(5, 'X')).isEqualTo(new FieldMetadata(5, 'X'));
        assertThat(new FieldMetadata(5, 'X')).hasSameHashCodeAs(new FieldMetadata(5, 'X'));
        assertThat(new FieldMetadata(5, 'X')).isNotEqualTo(new FieldMetadata(6, 'X'));
        assertThat(new FieldMetadata(5, 'X')).isNotEqualTo("not metadata");
        assertThat(new FieldMetadata(5, 'X').toString()).contains("length=5");
    }

    // ---------------------------------------------------------------------------------------------
    // The 58-byte cursor and both 88-levels.
    // ---------------------------------------------------------------------------------------------

    @Test
    void cursorDefaultsToTheDeclaredValueN() {
        Ct02Info cursor = new Ct02Info();
        assertThat(cursor.getNextPageFlg()).isEqualTo("N");
        assertThat(cursor.isNextPageNo()).isTrue();
        assertThat(cursor.isNextPageYes()).isFalse();
        assertThat(cursor.getPageNum()).isZero();
        assertThat(cursor.getTrnidFirst()).hasSize(16);
    }

    @Test
    void bothEightyEightLevelsAreReachableAndNeitherIsTheOthersNegation() {
        Ct02Info cursor = new Ct02Info();

        cursor.setNextPageYes();
        assertThat(cursor.isNextPageYes()).isTrue();
        assertThat(cursor.isNextPageNo()).isFalse();

        cursor.setNextPageNo();
        assertThat(cursor.isNextPageNo()).isTrue();
        assertThat(cursor.isNextPageYes()).isFalse();

        // A blank flag satisfies NEITHER condition - which is why they are independent predicates.
        cursor.setNextPageFlg(" ");
        assertThat(cursor.isNextPageYes()).isFalse();
        assertThat(cursor.isNextPageNo()).isFalse();

        cursor.setNextPageFlg(null);
        assertThat(cursor.isNextPageYes()).isFalse();
        assertThat(cursor.isNextPageNo()).isFalse();
    }

    @Test
    void cursorRendersFiftyEightBytesAndRoundTrips() {
        Ct02Info cursor = new Ct02Info("0000000000000001", "0000000000000010", 3, "Y", "S",
                "0000000000000007");
        byte[] image = cursor.toFixedWidth(ASCII);
        assertThat(image).hasSize(58);

        Ct02Info decoded = Ct02Info.fromFixedWidth(image, ASCII);
        assertThat(decoded.getTrnidFirst()).isEqualTo("0000000000000001");
        assertThat(decoded.getTrnidLast()).isEqualTo("0000000000000010");
        assertThat(decoded.getPageNum()).isEqualTo(3);
        assertThat(decoded.isNextPageYes()).isTrue();
        assertThat(decoded.getTrnSelFlg()).isEqualTo("S");
        assertThat(decoded.getTrnSelected()).isEqualTo("0000000000000007");
        assertThat(decoded).isEqualTo(cursor);
        assertThat(decoded).hasSameHashCodeAs(cursor);
    }

    @Test
    void pageNumberIsAnUnsignedEightDigitField() {
        Ct02Info cursor = new Ct02Info();
        assertThatThrownBy(() -> cursor.setPageNum(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsigned");
        assertThatThrownBy(() -> cursor.setPageNum(100_000_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("8 digits");
        cursor.setPageNum(99_999_999);
        assertThat(cursor.getPageNum()).isEqualTo(99_999_999);
        // PIC 9(08) zero-fills on the LEFT, unlike the character fields around it.
        cursor.setPageNum(3);
        assertThat(new String(cursor.toFixedWidth(ASCII), ASCII).substring(32, 40))
                .isEqualTo("00000003");
    }

    @Test
    void cursorFieldNamesAreVerbatim() {
        assertThat(Ct02Info.TRNID_FIRST_FIELD).isEqualTo("CDEMO-CT02-TRNID-FIRST");
        assertThat(Ct02Info.TRNID_LAST_FIELD).isEqualTo("CDEMO-CT02-TRNID-LAST");
        assertThat(Ct02Info.PAGE_NUM_FIELD).isEqualTo("CDEMO-CT02-PAGE-NUM");
        assertThat(Ct02Info.NEXT_PAGE_FLG_FIELD).isEqualTo("CDEMO-CT02-NEXT-PAGE-FLG");
        assertThat(Ct02Info.TRN_SEL_FLG_FIELD).isEqualTo("CDEMO-CT02-TRN-SEL-FLG");
        assertThat(Ct02Info.TRN_SELECTED_FIELD).isEqualTo("CDEMO-CT02-TRN-SELECTED");
    }

    // ---------------------------------------------------------------------------------------------
    // Statelessness: the commarea travels in the payload, and is not widened.
    // ---------------------------------------------------------------------------------------------

    @Test
    void navigationContextIsCarriedAtItsUnwidenedOneHundredAndSixtyBytes() {
        TransactionViewRequest request = new TransactionViewRequest();
        assertThat(request.getNavigationContext()).isNotNull();
        assertThat(request.getNavigationContext().toFixedWidth(
                new com.vsergeychik.carddemo.common.FixedWidthCodec(ASCII))).hasSize(160);

        request.setNavigationContext(null);
        assertThat(request.getNavigationContext()).isEqualTo(NavigationContext.empty());

        request.setCt02Info(null);
        assertThat(request.getCt02Info().isNextPageNo()).isTrue();
    }

    @Test
    void programContextPredicatesDelegateAndAreNotMutualNegations() {
        TransactionViewRequest request = new TransactionViewRequest();
        assertThat(request.isEnterContext()).isTrue();
        assertThat(request.isReenterContext()).isFalse();

        request.setNavigationContext(NavigationContext.empty().withPgmReenter());
        assertThat(request.isReenterContext()).isTrue();
        assertThat(request.isEnterContext()).isFalse();

        // PIC 9(01) may hold another digit, in which case BOTH are false.
        request.setNavigationContext(NavigationContext.empty().withPgmContext(9));
        assertThat(request.isEnterContext()).isFalse();
        assertThat(request.isReenterContext()).isFalse();
    }

    // ---------------------------------------------------------------------------------------------
    // Enum-keyed access covers all 21 without a field list of its own.
    // ---------------------------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(ScreenField.class)
    void enumKeyedAccessorsReachEveryField(ScreenField field) {
        TransactionViewRequest request = new TransactionViewRequest();
        String marker = "Z".repeat(field.length());
        request.setPayloadValue(field, marker);
        assertThat(request.payloadValue(field)).isEqualTo(marker);

        String image = new String(request.toFixedWidth(ASCII), ASCII);
        assertThat(image.substring(field.payloadOffset(), field.payloadOffset() + field.length()))
                .isEqualTo(marker);
    }

    @Test
    void enumKeyedAccessorsAgreeWithTheBeanAccessors() {
        TransactionViewRequest request = new TransactionViewRequest();
        request.setActidin("00000000011");
        assertThat(request.payloadValue(ScreenField.ACTIDIN)).isEqualTo("00000000011");
        request.setPayloadValue(ScreenField.CARDNIN, "4111111111111111");
        assertThat(request.getCardnin()).isEqualTo("4111111111111111");
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
    }

    // ---------------------------------------------------------------------------------------------
    // Value semantics and copy isolation.
    // ---------------------------------------------------------------------------------------------

    @Test
    void copyConstructorSharesNoMutableState() {
        TransactionViewRequest original = new TransactionViewRequest();
        original.setTrnamt("+00000100.00");
        original.requestCursor(ScreenField.TRNAMT);
        original.getCt02Info().setNextPageYes();

        TransactionViewRequest copy = new TransactionViewRequest(original);
        assertThat(copy).isEqualTo(original);

        copy.getCt02Info().setNextPageNo();
        copy.metadata(ScreenField.TRNAMT).reset();
        copy.setTrnamt("-00000001.00");

        assertThat(original.getCt02Info().isNextPageYes()).isTrue();
        assertThat(original.isCursorRequested(ScreenField.TRNAMT)).isTrue();
        assertThat(original.getTrnamt()).isEqualTo("+00000100.00");
        assertThat(copy).isNotEqualTo(original);
    }

    @Test
    void equalityAccountsForMetadataBecauseACursorRequestIsPartOfTheRequest() {
        TransactionViewRequest left = new TransactionViewRequest();
        TransactionViewRequest right = new TransactionViewRequest();
        assertThat(left).isEqualTo(right).isEqualTo(left);
        assertThat(left).hasSameHashCodeAs(right);

        right.requestCursor(ScreenField.CONFIRM);
        assertThat(left).isNotEqualTo(right);
        assertThat(left).isNotEqualTo("not a request");
        assertThat(left).isNotEqualTo(null);
    }

    @Test
    void allArgsConstructorStoresEveryFieldExactlyAsSupplied() {
        TransactionViewRequest request = new TransactionViewRequest(
                "CT02", pad("T", 40), "07/18/22", "COTRN02C", pad("U", 40), "12:00:00",
                "00000000011", "4111111111111111", "01", "0001", "POS", pad("DESC", 60),
                "+00000100.00", "2022-07-18", "2022-07-19", "123456789", pad("M", 30),
                pad("CITY", 25), "12345-0000", "Y", pad("E", 78), null, null);

        assertThat(request.getTrnname()).isEqualTo("CT02");
        assertThat(request.getPgmname()).isEqualTo("COTRN02C");
        assertThat(request.getActidin()).isEqualTo("00000000011");
        assertThat(request.getCardnin()).isEqualTo("4111111111111111");
        assertThat(request.getTtypcd()).isEqualTo("01");
        assertThat(request.getTcatcd()).isEqualTo("0001");
        assertThat(request.getTrnsrc()).isEqualTo("POS");
        assertThat(request.getTrnamt()).isEqualTo("+00000100.00");
        assertThat(request.getTorigdt()).isEqualTo("2022-07-18");
        assertThat(request.getTprocdt()).isEqualTo("2022-07-19");
        assertThat(request.getMid()).isEqualTo("123456789");
        assertThat(request.getMzip()).isEqualTo("12345-0000");
        assertThat(request.getConfirm()).isEqualTo("Y");
        assertThat(request.getCurdate()).isEqualTo("07/18/22");
        assertThat(request.getCurtime()).isEqualTo("12:00:00");
        assertThat(request.getTitle01()).hasSize(40);
        assertThat(request.getTitle02()).hasSize(40);
        assertThat(request.getTdesc()).hasSize(60);
        assertThat(request.getMname()).hasSize(30);
        assertThat(request.getMcity()).hasSize(25);
        assertThat(request.getErrmsg()).hasSize(78);
        assertThat(request.getNavigationContext()).isEqualTo(NavigationContext.empty());
        assertThat(request.getCt02Info().isNextPageNo()).isTrue();
    }

    @Test
    void toStringMasksNothingAndIsKeyedByCopybookNames() {
        TransactionViewRequest request = new TransactionViewRequest();
        request.setCardnin("4111111111111111");
        request.setActidin("00000000011");
        request.setMid("123456789");
        request.requestCursor(ScreenField.CARDNIN);

        String rendering = request.toString();
        assertThat(rendering).contains("CARDNINI=4111111111111111");
        assertThat(rendering).contains("ACTIDINI=00000000011");
        assertThat(rendering).contains("MIDI=123456789");
        assertThat(rendering).contains("(cursor)");
        assertThat(rendering).contains("CT02", "COTRN02C", "COTRN2AI");
        assertThat(rendering).doesNotContain("****", "REDACTED");
    }

    @Test
    void identityConstantsMatchTheCsdAndMapset() {
        assertThat(TransactionViewRequest.TRANSACTION_ID).isEqualTo("CT02");
        assertThat(TransactionViewRequest.PROGRAM_NAME).isEqualTo("COTRN02C");
        assertThat(TransactionViewRequest.MAPSET_NAME).isEqualTo("COTRN02");
        assertThat(TransactionViewRequest.MAP_NAME).isEqualTo("COTRN2A");
        assertThat(TransactionViewRequest.SYMBOLIC_MAP_INPUT_GROUP).isEqualTo("COTRN2AI");
        assertThat(TransactionViewRequest.SYMBOLIC_MAP_OUTPUT_GROUP).isEqualTo("COTRN2AO");
    }

    @Test
    void figurativeConstantsRejectANegativeWidth() {
        assertThatThrownBy(() -> TransactionViewRequest.spaces(-1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("SPACES");
        assertThatThrownBy(() -> TransactionViewRequest.lowValues(-1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("LOW-VALUES");
    }

    // ---------------------------------------------------------------------------------------------
    // Bean Validation is width-only, and never stricter than the COBOL.
    // ---------------------------------------------------------------------------------------------

    @Test
    void widthConstraintsAcceptTheDeclaredWidthAndRejectOnlyOverflow() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            TransactionViewRequest atWidth = new TransactionViewRequest();
            atWidth.setErrmsg(pad("x", 78));
            atWidth.setCardnin("4111111111111111");
            assertThat(validator.validate(atWidth)).isEmpty();

            TransactionViewRequest overWidth = new TransactionViewRequest();
            overWidth.setErrmsg(pad("x", 79));
            Set<ConstraintViolation<TransactionViewRequest>> violations = validator.validate(overWidth);
            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath()).hasToString("errmsg");
        }
    }

    @Test
    void noNumericOrDateConstraintPreEmptsTheCobolsOwnEditing() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            // COTRN02C itself rejects these, with its own messages. Java must NOT reject them first,
            // or the program's IS NOT NUMERIC / CSUTLDTC / (Y/N) branches become unreachable.
            TransactionViewRequest garbage = new TransactionViewRequest();
            garbage.setActidin("ABCDEFGHIJK");    // not numeric
            garbage.setCardnin("NOT-A-CARD-NUM..");// not numeric, still 16
            garbage.setTrnamt("XXXXXXXXXXXX");    // fails the positional mask
            garbage.setTorigdt("9999-99-99");     // fails CSUTLDTC
            garbage.setTprocdt("not-a-date");     // fails CSUTLDTC
            garbage.setConfirm("Q");              // the WHEN OTHER branch
            garbage.setMzip("!!!!!!!!!!");

            assertThat(validator.validate(garbage))
                    .as("Java must defer every content rule to the COBOL")
                    .isEmpty();
        }
    }

    @Test
    void nullFieldsAreAcceptedBecauseAnAbsentScreenFieldIsLegal() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            TransactionViewRequest request = new TransactionViewRequest();
            for (ScreenField field : ScreenField.values()) {
                request.setPayloadValue(field, null);
            }
            assertThat(factory.getValidator().validate(request)).isEmpty();
        }
    }

    @Test
    void nestedCursorConstraintsCascade() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            TransactionViewRequest request = new TransactionViewRequest();
            request.getCt02Info().setTrnidFirst(pad("x", 17));
            Set<ConstraintViolation<TransactionViewRequest>> violations =
                    factory.getValidator().validate(request);
            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath())
                    .hasToString("ct02Info.trnidFirst");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Value-semantics branch coverage: every short-circuit of every equals chain, from both sides.
    // ---------------------------------------------------------------------------------------------

    @Test
    void fieldMetadataEqualsCoversEveryShortCircuit() {
        FieldMetadata metadata = new FieldMetadata(3, 'A');

        assertThat(metadata).isEqualTo(metadata);                       // same reference
        assertThat(metadata).isEqualTo(new FieldMetadata(3, 'A'));      // both components equal
        assertThat(metadata).isNotEqualTo(new FieldMetadata(4, 'A'));   // length differs
        assertThat(metadata).isNotEqualTo(new FieldMetadata(3, 'B'));   // attribute byte differs
        assertThat(metadata).isNotEqualTo(null);
        assertThat(metadata).isNotEqualTo(new Object());

        assertThat(new FieldMetadata(metadata)).isEqualTo(metadata);
    }

    @Test
    void cursorEqualsCoversEveryShortCircuit() {
        Ct02Info base = new Ct02Info("A".repeat(16), "B".repeat(16), 5, "Y", "S", "C".repeat(16));

        assertThat(base).isEqualTo(base);                               // same reference
        assertThat(base).isNotEqualTo(null);
        assertThat(base).isNotEqualTo("not a cursor");                  // not an instance
        assertThat(base).isEqualTo(new Ct02Info(base));                 // every component equal

        // One differing component per assertion, so each && in the chain is exercised both ways.
        Ct02Info differsPageNum = new Ct02Info(base);
        differsPageNum.setPageNum(6);
        assertThat(base).isNotEqualTo(differsPageNum);

        Ct02Info differsFirst = new Ct02Info(base);
        differsFirst.setTrnidFirst("Z".repeat(16));
        assertThat(base).isNotEqualTo(differsFirst);

        Ct02Info differsLast = new Ct02Info(base);
        differsLast.setTrnidLast("Z".repeat(16));
        assertThat(base).isNotEqualTo(differsLast);

        Ct02Info differsFlag = new Ct02Info(base);
        differsFlag.setNextPageNo();
        assertThat(base).isNotEqualTo(differsFlag);

        Ct02Info differsSelFlg = new Ct02Info(base);
        differsSelFlg.setTrnSelFlg("X");
        assertThat(base).isNotEqualTo(differsSelFlg);

        Ct02Info differsSelected = new Ct02Info(base);
        differsSelected.setTrnSelected("Z".repeat(16));
        assertThat(base).isNotEqualTo(differsSelected);

        assertThat(base.toString()).contains("CDEMO-CT02-PAGE-NUM=5");
    }

    @Test
    void requestEqualsDistinguishesBothCarriedStateObjects() {
        TransactionViewRequest base = new TransactionViewRequest();

        TransactionViewRequest differsContext = new TransactionViewRequest(base);
        differsContext.setNavigationContext(NavigationContext.empty().withPgmReenter());
        assertThat(base).isNotEqualTo(differsContext);

        TransactionViewRequest differsCursor = new TransactionViewRequest(base);
        differsCursor.getCt02Info().setNextPageYes();
        assertThat(base).isNotEqualTo(differsCursor);

        TransactionViewRequest differsPayload = new TransactionViewRequest(base);
        differsPayload.setConfirm("Y");
        assertThat(base).isNotEqualTo(differsPayload);

        assertThat(new TransactionViewRequest(base)).isEqualTo(base);
    }

    @Test
    void allArgsConstructorKeepsSuppliedStateObjectsRatherThanReplacingThem() {
        NavigationContext context = NavigationContext.empty().withPgmReenter().withUserId("ADMIN001");
        Ct02Info cursor = new Ct02Info();
        cursor.setNextPageYes();
        cursor.setPageNum(4);

        TransactionViewRequest request = new TransactionViewRequest(
                "CT02", pad("T", 40), "07/18/22", "COTRN02C", pad("U", 40), "12:00:00",
                "00000000011", "4111111111111111", "01", "0001", "POS", pad("DESC", 60),
                "+00000100.00", "2022-07-18", "2022-07-19", "123456789", pad("M", 30),
                pad("CITY", 25), "12345-0000", "Y", pad("E", 78), context, cursor);

        assertThat(request.getNavigationContext()).isSameAs(context);
        assertThat(request.getNavigationContext().userId()).isEqualTo("ADMIN001");
        assertThat(request.isReenterContext()).isTrue();
        assertThat(request.getCt02Info()).isSameAs(cursor);
        assertThat(request.getCt02Info().isNextPageYes()).isTrue();
        assertThat(request.getCt02Info().getPageNum()).isEqualTo(4);
    }

    @Test
    void cursorRendersNullCharacterFieldsAsSpaces() {
        // The commarea extension's declared initial state is spaces, so a null component renders as
        // spaces here - unlike a received screen field, which renders as LOW-VALUES.
        Ct02Info cursor = new Ct02Info(null, null, 0, null, null, null);
        String image = new String(cursor.toFixedWidth(ASCII), ASCII);

        assertThat(image).hasSize(58);
        assertThat(image.substring(0, 16)).isEqualTo(" ".repeat(16));
        assertThat(image.substring(16, 32)).isEqualTo(" ".repeat(16));
        assertThat(image.substring(32, 40)).isEqualTo("00000000");
        assertThat(image.substring(40, 41)).isEqualTo(" ");
        assertThat(image.substring(41, 42)).isEqualTo(" ");
        assertThat(image.substring(42, 58)).isEqualTo(" ".repeat(16));
    }

    @Test
    void copyConstructorsRejectNull() {
        assertThatThrownBy(() -> new TransactionViewRequest((TransactionViewRequest) null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Ct02Info((Ct02Info) null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FieldMetadata((FieldMetadata) null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void fixedWidthEntryPointsRejectNullArguments() {
        assertThatThrownBy(() -> TransactionViewRequest.fromFixedWidth(null, ASCII))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Ct02Info.fromFixedWidth(null, ASCII))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TransactionViewRequest().writeInto(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> TransactionViewRequest.readFrom(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void metadataSpansAccountForEveryPrefixByte() {
        for (ScreenField field : ScreenField.values()) {
            List<com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan> spans =
                    field.metadataSpans();
            assertThat(spans).hasSize(3);
            assertThat(spans.get(0).offset()).isEqualTo(field.groupOffset());
            assertThat(spans.get(0).length()).isEqualTo(TransactionViewRequest.LENGTH_ITEM_LENGTH);
            assertThat(spans.get(1).length()).isEqualTo(TransactionViewRequest.FLAG_ITEM_LENGTH);
            assertThat(spans.get(2).length()).isEqualTo(TransactionViewRequest.RESERVED_FILLER_LENGTH);

            int prefix = spans.get(0).length() + spans.get(1).length() + spans.get(2).length();
            assertThat(prefix).isEqualTo(TransactionViewRequest.METADATA_PREFIX_LENGTH);
            assertThat(spans.get(2).offset() + spans.get(2).length())
                    .isEqualTo(field.payloadOffset());
        }
    }

    private static String pad(String value, int width) {
        return value + " ".repeat(width - value.length());
    }
}
