package com.vsergeychik.carddemo.transaction.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.transaction.dto.TransactionAddRequest.Ct01Info;
import com.vsergeychik.carddemo.transaction.dto.TransactionAddRequest.ScreenFieldMetadata;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies {@link TransactionAddRequest} - the inbound payload of CSD transaction {@code CT01}, program
 * {@code COTRN01C}, mapset {@code COTRN01}, map {@code COTRN1A} - against the legacy sources that define
 * it.
 */
@DisplayName("TransactionAddRequest - CT01 / COTRN01C / mapset COTRN01, map COTRN1A")
class TransactionAddRequestTest {
    private static final FixedWidthCodec CODEC = new FixedWidthCodec(StandardCharsets.US_ASCII);

    private static final List<String> EXPECTED_FIELDS = List.of(
            "TRNNAMEI:4", "TITLE01I:40", "CURDATEI:8", "PGMNAMEI:8", "TITLE02I:40",
            "CURTIMEI:8", "TRNIDINI:16", "TRNIDI:16", "CARDNUMI:16", "TTYPCDI:2",
            "TCATCDI:4", "TRNSRCI:10", "TDESCI:60", "TRNAMTI:12", "TORIGDTI:10",
            "TPROCDTI:10", "MIDI:9", "MNAMEI:30", "MCITYI:25", "MZIPI:10", "ERRMSGI:78");

    private static TransactionAddRequest populated() {
        TransactionAddRequest request = new TransactionAddRequest();
        request.setTrnname("CT01");
        request.setTitle01("AWS Mainframe Modernization");
        request.setCurdate("08/08/26");
        request.setPgmname("COTRN01C");
        request.setTitle02("CardDemo");
        request.setCurtime("09:41:00");
        request.setTrnidin("0000000000000001");
        request.setTrnid("0000000000000001");
        request.setCardnum("4111111111111111");
        request.setTtypcd("01");
        request.setTcatcd("0001");
        request.setTrnsrc("POS TERM");
        request.setTdesc("PURCHASE");
        request.setTrnamt("+00000123.45");
        request.setTorigdt("2022-07-18");
        request.setTprocdt("2022-07-19");
        request.setMid("123456789");
        request.setMname("ACME STORES");
        request.setMcity("SEATTLE");
        request.setMzip("98101");
        request.setErrmsg("Transaction not found");
        request.setAid("\u0027");
        request.setNavigationContext(NavigationContext.empty()
                .withFromTranid("CT00")
                .withFromProgram("COTRN00C")
                .withUserId("USER0001")
                .withPgmReenter());
        Ct01Info info = request.getCt01Info();
        info.setTrnidFirst("0000000000000001");
        info.setTrnidLast("0000000000000010");
        info.setPageNum(3);
        info.setNextPageYes();
        info.setTrnSelFlg("S");
        info.setTrnSelected("0000000000000007");
        return request;
    }

    private static final List<Object[]> OVERLAY = List.of(
            new Object[] {"TRNNAME", 12, 19, 4},
            new Object[] {"TITLE01", 23, 30, 40},
            new Object[] {"CURDATE", 70, 77, 8},
            new Object[] {"PGMNAME", 85, 92, 8},
            new Object[] {"TITLE02", 100, 107, 40},
            new Object[] {"CURTIME", 147, 154, 8},
            new Object[] {"TRNIDIN", 162, 169, 16},
            new Object[] {"TRNID", 185, 192, 16},
            new Object[] {"CARDNUM", 208, 215, 16},
            new Object[] {"TTYPCD", 231, 238, 2},
            new Object[] {"TCATCD", 240, 247, 4},
            new Object[] {"TRNSRC", 251, 258, 10},
            new Object[] {"TDESC", 268, 275, 60},
            new Object[] {"TRNAMT", 335, 342, 12},
            new Object[] {"TORIGDT", 354, 361, 10},
            new Object[] {"TPROCDT", 371, 378, 10},
            new Object[] {"MID", 388, 395, 9},
            new Object[] {"MNAME", 404, 411, 30},
            new Object[] {"MCITY", 441, 448, 25},
            new Object[] {"MZIP", 473, 480, 10},
            new Object[] {"ERRMSG", 490, 497, 78});

    private static final List<Integer> PUBLISHED_GROUP_OFFSETS = List.of(
            TransactionAddRequest.TRNNAME_GROUP_OFFSET, TransactionAddRequest.TITLE01_GROUP_OFFSET,
            TransactionAddRequest.CURDATE_GROUP_OFFSET, TransactionAddRequest.PGMNAME_GROUP_OFFSET,
            TransactionAddRequest.TITLE02_GROUP_OFFSET, TransactionAddRequest.CURTIME_GROUP_OFFSET,
            TransactionAddRequest.TRNIDIN_GROUP_OFFSET, TransactionAddRequest.TRNID_GROUP_OFFSET,
            TransactionAddRequest.CARDNUM_GROUP_OFFSET, TransactionAddRequest.TTYPCD_GROUP_OFFSET,
            TransactionAddRequest.TCATCD_GROUP_OFFSET, TransactionAddRequest.TRNSRC_GROUP_OFFSET,
            TransactionAddRequest.TDESC_GROUP_OFFSET, TransactionAddRequest.TRNAMT_GROUP_OFFSET,
            TransactionAddRequest.TORIGDT_GROUP_OFFSET, TransactionAddRequest.TPROCDT_GROUP_OFFSET,
            TransactionAddRequest.MID_GROUP_OFFSET, TransactionAddRequest.MNAME_GROUP_OFFSET,
            TransactionAddRequest.MCITY_GROUP_OFFSET, TransactionAddRequest.MZIP_GROUP_OFFSET,
            TransactionAddRequest.ERRMSG_GROUP_OFFSET);

    private static final List<Integer> PUBLISHED_VALUE_OFFSETS = List.of(
            TransactionAddRequest.TRNNAME_OFFSET, TransactionAddRequest.TITLE01_OFFSET,
            TransactionAddRequest.CURDATE_OFFSET, TransactionAddRequest.PGMNAME_OFFSET,
            TransactionAddRequest.TITLE02_OFFSET, TransactionAddRequest.CURTIME_OFFSET,
            TransactionAddRequest.TRNIDIN_OFFSET, TransactionAddRequest.TRNID_OFFSET,
            TransactionAddRequest.CARDNUM_OFFSET, TransactionAddRequest.TTYPCD_OFFSET,
            TransactionAddRequest.TCATCD_OFFSET, TransactionAddRequest.TRNSRC_OFFSET,
            TransactionAddRequest.TDESC_OFFSET, TransactionAddRequest.TRNAMT_OFFSET,
            TransactionAddRequest.TORIGDT_OFFSET, TransactionAddRequest.TPROCDT_OFFSET,
            TransactionAddRequest.MID_OFFSET, TransactionAddRequest.MNAME_OFFSET,
            TransactionAddRequest.MCITY_OFFSET, TransactionAddRequest.MZIP_OFFSET,
            TransactionAddRequest.ERRMSG_OFFSET);

    static Stream<Arguments> overlayRows() {
        return OVERLAY.stream().map(Arguments::of);
    }

    static Stream<Arguments> sizedFields() {
        return Stream.of(
                sized("trnname", 4, TransactionAddRequest::setTrnname),
                sized("title01", 40, TransactionAddRequest::setTitle01),
                sized("curdate", 8, TransactionAddRequest::setCurdate),
                sized("pgmname", 8, TransactionAddRequest::setPgmname),
                sized("title02", 40, TransactionAddRequest::setTitle02),
                sized("curtime", 8, TransactionAddRequest::setCurtime),
                sized("trnidin", 16, TransactionAddRequest::setTrnidin),
                sized("trnid", 16, TransactionAddRequest::setTrnid),
                sized("cardnum", 16, TransactionAddRequest::setCardnum),
                sized("ttypcd", 2, TransactionAddRequest::setTtypcd),
                sized("tcatcd", 4, TransactionAddRequest::setTcatcd),
                sized("trnsrc", 10, TransactionAddRequest::setTrnsrc),
                sized("tdesc", 60, TransactionAddRequest::setTdesc),
                sized("trnamt", 12, TransactionAddRequest::setTrnamt),
                sized("torigdt", 10, TransactionAddRequest::setTorigdt),
                sized("tprocdt", 10, TransactionAddRequest::setTprocdt),
                sized("mid", 9, TransactionAddRequest::setMid),
                sized("mname", 30, TransactionAddRequest::setMname),
                sized("mcity", 25, TransactionAddRequest::setMcity),
                sized("mzip", 10, TransactionAddRequest::setMzip),
                sized("errmsg", 78, TransactionAddRequest::setErrmsg),
                sized("aid", TransactionAddRequest.AID_TOKEN_LENGTH, TransactionAddRequest::setAid));
    }

    private static Arguments sized(String property,
                                   int declaredWidth,
                                   BiConsumer<TransactionAddRequest, String> setter) {
        Consumer<TransactionAddRequest> atWidth =
                request -> setter.accept(request, "X".repeat(declaredWidth));
        Consumer<TransactionAddRequest> overWidth =
                request -> setter.accept(request, "X".repeat(declaredWidth + 1));
        return Arguments.of(property, declaredWidth, declaredWidth + 1, atWidth, overWidth);
    }

    @Nested
    @DisplayName("Screen provenance - CSD, mapset and program identity")
    class Provenance {
        @Test
        @DisplayName("names CT01 / COTRN01C / COTRN01 / COTRN1A exactly as the CSD and program do")
        void identityMatchesTheSources() {
            assertThat(TransactionAddRequest.TRANSACTION_ID).isEqualTo("CT01");
            assertThat(TransactionAddRequest.PROGRAM_NAME).isEqualTo("COTRN01C");
            assertThat(TransactionAddRequest.MAPSET_NAME).isEqualTo("COTRN01");
            assertThat(TransactionAddRequest.MAP_NAME).isEqualTo("COTRN1A");
        }

        @Test
        @DisplayName("projects the AI group and records the AO group that redefines it")
        void recordsBothSymbolicMapGroups() {
            assertThat(TransactionAddRequest.SYMBOLIC_MAP_GROUP).isEqualTo("COTRN1AI");
            assertThat(TransactionAddRequest.SYMBOLIC_MAP_OUTPUT_GROUP).isEqualTo("COTRN1AO");
        }
    }

    @Nested
    @DisplayName("Field set - exactly 21 fields, 1:1 with the xxxI items, nothing from COTRN02")
    class FieldSet {
        @Test
        @DisplayName("declares exactly 21 payload fields")
        void declaresTwentyOneFields() {
            assertThat(TransactionAddRequest.PAYLOAD_FIELD_COUNT).isEqualTo(21);
            assertThat(TransactionAddRequest.PAYLOAD_FIELD_NAMES).hasSize(21);
        }

        @Test
        @DisplayName("field names and widths match the copybook 1:1, in declaration order")
        void matchesTheCopybookExactly() {
            List<String> actual = new ArrayList<>();
            for (String name : TransactionAddRequest.PAYLOAD_FIELD_NAMES) {
                actual.add(name + ":" + TransactionAddRequest.declaredLengthOf(name));
            }
            assertThat(actual).containsExactlyElementsOf(EXPECTED_FIELDS);
        }

        @Test
        @DisplayName("carries no CONFIRM, ACTIDIN or CARDNIN - those belong to the COTRN02 map")
        void carriesNothingFromTheSiblingAddScreen() {
            assertThat(TransactionAddRequest.PAYLOAD_FIELD_NAMES)
                    .doesNotContain("CONFIRMI", "ACTIDINI", "CARDNINI");
        }

        @Test
        @DisplayName("every declared width is reachable by name, and an unknown name is rejected")
        void declaredLengthOfRejectsUnknownNames() {
            assertThat(TransactionAddRequest.declaredLengthOf("CARDNUMI")).isEqualTo(16);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TransactionAddRequest.declaredLengthOf("NOSUCHI"))
                    .withMessageContaining("is not a field of COTRN1AI");
            assertThatNullPointerException()
                    .isThrownBy(() -> TransactionAddRequest.declaredLengthOf(null));
        }
    }

    @Nested
    @DisplayName("Byte geometry - 12 + 21x7 + 416 = 575, extension 58, commarea 218")
    class Geometry {
        @Test
        @DisplayName("the metadata stride is 2 + 1 + 4 = 7 bytes per field")
        void metadataStrideIsSeven() {
            assertThat(TransactionAddRequest.TIOA_PREFIX_LENGTH).isEqualTo(12);
            assertThat(TransactionAddRequest.LENGTH_ITEM_LENGTH).isEqualTo(2);
            assertThat(TransactionAddRequest.FLAG_ITEM_LENGTH).isEqualTo(1);
            assertThat(TransactionAddRequest.RESERVED_FILLER_LENGTH).isEqualTo(4);
            assertThat(TransactionAddRequest.FIELD_METADATA_LENGTH).isEqualTo(7);
        }

        @Test
        @DisplayName("the 21 declared widths sum to 416")
        void declaredWidthsSumTo416() {
            int sum = 0;
            for (String name : TransactionAddRequest.PAYLOAD_FIELD_NAMES) {
                sum += TransactionAddRequest.declaredLengthOf(name);
            }
            assertThat(sum).isEqualTo(416);
            assertThat(TransactionAddRequest.PAYLOAD_TOTAL_LENGTH).isEqualTo(416);
        }

        @Test
        @DisplayName("the COTRN1AI image is 575 bytes, derivable two independent ways")
        void symbolicMapImageIs575() {
            assertThat(TransactionAddRequest.SYMBOLIC_MAP_LENGTH).isEqualTo(575);
            assertThat(12 + 21 * 7 + 416).isEqualTo(575);
            assertThat(TransactionAddRequest.ERRMSG_OFFSET + TransactionAddRequest.ERRMSG_LENGTH)
                    .isEqualTo(575);
        }

        @Test
        @DisplayName("the commarea is 160 + 58 = 218 bytes and NavigationContext stays 160")
        void commareaIs218() {
            assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(160);
            assertThat(Ct01Info.RECORD_LENGTH).isEqualTo(58);
            assertThat(TransactionAddRequest.COMMAREA_TOTAL_LENGTH).isEqualTo(218);
        }

        @Test
        @DisplayName("the offset chain places TRNIDIN at 169, TRNAMT at 342 and ERRMSG at 497")
        void offsetChainIsContiguous() {
            assertThat(TransactionAddRequest.TRNNAME_GROUP_OFFSET).isEqualTo(12);
            assertThat(TransactionAddRequest.TRNNAME_OFFSET).isEqualTo(19);
            assertThat(TransactionAddRequest.TRNIDIN_OFFSET).isEqualTo(169);
            assertThat(TransactionAddRequest.TRNAMT_OFFSET).isEqualTo(342);
            assertThat(TransactionAddRequest.ERRMSG_OFFSET).isEqualTo(497);
        }

        @Test
        @DisplayName("sumOf totals a width list and refuses a null list or a null element")
        void sumOfTotalsWidths() {
            assertThat(TransactionAddRequest.sumOf(List.of(1, 2, 3))).isEqualTo(6);
            assertThat(TransactionAddRequest.sumOf(List.of())).isZero();
            assertThatNullPointerException().isThrownBy(() -> TransactionAddRequest.sumOf(null));
            List<Integer> withNull = new ArrayList<>();
            withNull.add(null);
            assertThatNullPointerException()
                    .isThrownBy(() -> TransactionAddRequest.sumOf(withNull));
        }
    }

    @Nested
    @DisplayName("The geometry self-check actually fires - every guard is demonstrated")
    class GeometrySelfCheck {
        @Test
        @DisplayName("accepts the real geometry")
        void acceptsTheRealGeometry() {
            TransactionAddRequest.verifyGeometry(21, 21, 416, 575, 575);
        }

        @Test
        @DisplayName("rejects a wrong name count and a wrong width count independently")
        void rejectsMismatchedListSizes() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> TransactionAddRequest.verifyGeometry(20, 21, 416, 575, 575))
                    .withMessageContaining("declares 21 payload fields");
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> TransactionAddRequest.verifyGeometry(21, 22, 416, 575, 575))
                    .withMessageContaining("22 width(s)");
        }

        @Test
        @DisplayName("rejects a width total that is not 416")
        void rejectsWrongWidthTotal() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> TransactionAddRequest.verifyGeometry(21, 21, 415, 575, 575))
                    .withMessageContaining("sum to 415, not 416");
        }

        @Test
        @DisplayName("rejects a broken offset chain and a broken component sum independently")
        void rejectsWrongImageLength() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> TransactionAddRequest.verifyGeometry(21, 21, 416, 574, 575))
                    .withMessageContaining("offset chain ends at 574");
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> TransactionAddRequest.verifyGeometry(21, 21, 416, 575, 576))
                    .withMessageContaining("component sum is 576");
        }

        @Test
        @DisplayName("screenNameOf strips the directional I, and refuses a name without one")
        void screenNameOfStripsTheSuffix() {
            assertThat(TransactionAddRequest.screenNameOf("TRNNAMEI")).isEqualTo("TRNNAME");
            assertThat(TransactionAddRequest.screenNameOf("MIDI")).isEqualTo("MID");
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> TransactionAddRequest.screenNameOf("TRNNAME"))
                    .withMessageContaining("directional 'I' suffix");
        }

        @Test
        @DisplayName("spaces sizes a blank field and refuses a negative width")
        void spacesSizesABlankField() {
            assertThat(TransactionAddRequest.spaces(0)).isEmpty();
            assertThat(TransactionAddRequest.spaces(78)).hasSize(78).isBlank();
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TransactionAddRequest.spaces(-1));
        }
    }

    @Nested
    @DisplayName("Defaults - a COBOL work area holds spaces, not null")
    class Defaults {
        @Test
        @DisplayName("every payload field starts blank at its declared width")
        void everyFieldStartsBlankAtItsDeclaredWidth() {
            Map<String, String> images = new TransactionAddRequest().toFieldImages(CODEC);
            assertThat(images).hasSize(21);
            for (Map.Entry<String, String> entry : images.entrySet()) {
                assertThat(entry.getValue())
                        .as("%s renders at its declared width", entry.getKey())
                        .hasSize(TransactionAddRequest.declaredLengthOf(entry.getKey()))
                        .isBlank();
            }
        }

        @Test
        @DisplayName("ERRMSG is 78 blanks and TRNAMT is 12 blanks before anything is set")
        void headlineWidthsAreCorrectByDefault() {
            TransactionAddRequest request = new TransactionAddRequest();
            assertThat(request.getErrmsg()).hasSize(78);
            assertThat(request.getTrnamt()).hasSize(12);
            assertThat(request.getCardnum()).hasSize(16);
            assertThat(request.getMid()).hasSize(9);
        }

        @Test
        @DisplayName("a fresh request carries no communication area, but does carry its extension")
        void conversationStateIsInitialised() {
            TransactionAddRequest request = new TransactionAddRequest();

            assertThat(request.getNavigationContext()).isNull();
            assertThat(request.hasNavigationContext()).isFalse();
            assertThat(request.commareaLength()).isZero();
            assertThat(request.isEnter()).isFalse();
            assertThat(request.isReenter()).isFalse();

            assertThat(request.getCt01Info()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Numeric ruling - TRNAMT is a 12-character edited image, never a number")
    class NumericRuling {
        @Test
        @DisplayName("carries the +99999999.99 mask from COTRN01C line 49 at exactly 12 characters")
        void trnamtCarriesTheEditMask() {
            assertThat(TransactionAddRequest.TRNAMT_LENGTH).isEqualTo(12);
            assertThat("+99999999.99").hasSize(12);
            TransactionAddRequest request = new TransactionAddRequest();
            request.setTrnamt("+00000123.45");
            assertThat(request.getTrnamt()).isEqualTo("+00000123.45").hasSize(12);
            assertThat(request.toFieldImages(CODEC).get("TRNAMTI")).isEqualTo("+00000123.45");
        }

        @Test
        @DisplayName("the screen width 12 differs from the record's 11-byte PIC S9(09)V99")
        void screenWidthIsNotTheRecordWidth() {
            assertThat(TransactionAddRequest.TRNAMT_LENGTH).isEqualTo(12).isNotEqualTo(11);
        }

        @Test
        @DisplayName("a negative edited amount fits the mask")
        void negativeAmountsFitTheMask() {
            TransactionAddRequest request = new TransactionAddRequest();
            request.setTrnamt("-00000050.00");
            assertThat(request.toFieldImages(CODEC).get("TRNAMTI"))
                    .isEqualTo("-00000050.00").hasSize(12);
        }
    }

    @Nested
    @DisplayName("Security posture is inherited - CARDNUM and MID are never masked")
    class SecurityPosture {
        @Test
        @DisplayName("CARDNUM round-trips all 16 digits unaltered")
        void cardNumberIsNotMasked() {
            TransactionAddRequest request = new TransactionAddRequest();
            request.setCardnum("4111111111111111");
            assertThat(request.getCardnum()).isEqualTo("4111111111111111");
            assertThat(request.toFieldImages(CODEC).get("CARDNUMI")).isEqualTo("4111111111111111");
        }

        @Test
        @DisplayName("MID round-trips all 9 digits unaltered")
        void merchantIdIsNotMasked() {
            TransactionAddRequest request = new TransactionAddRequest();
            request.setMid("123456789");
            assertThat(request.getMid()).isEqualTo("123456789");
            assertThat(request.toFieldImages(CODEC).get("MIDI")).isEqualTo("123456789");
        }

        @Test
        @DisplayName("both survive a JSON round trip in the clear")
        void bothSurviveJsonInTheClear() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            String json = mapper.writeValueAsString(populated());
            assertThat(json).contains("4111111111111111").contains("123456789");
        }
    }

    @Nested
    @DisplayName("JSON round trip is lossless - padding is content")
    class JsonRoundTrip {
        @Test
        @DisplayName("a fully populated request survives serialise then deserialise, and compares equal")
        void fullRoundTripIsLossless() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            TransactionAddRequest original = populated();
            TransactionAddRequest revived =
                    mapper.readValue(mapper.writeValueAsString(original), TransactionAddRequest.class);
            assertThat(revived).isEqualTo(original);
            assertThat(revived.hashCode()).isEqualTo(original.hashCode());
        }

        @Test
        @DisplayName("78 space-padded ERRMSG characters and 12 TRNAMT characters survive UNTRIMMED")
        void paddedFieldsSurviveUntrimmed() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            TransactionAddRequest original = new TransactionAddRequest();
            String paddedErrmsg = CODEC.movePicX("Transaction not found", 78);
            original.setErrmsg(paddedErrmsg);
            original.setTrnamt("+00000123.45");
            assertThat(paddedErrmsg).hasSize(78);

            TransactionAddRequest revived =
                    mapper.readValue(mapper.writeValueAsString(original), TransactionAddRequest.class);

            assertThat(revived.getErrmsg()).isEqualTo(paddedErrmsg).hasSize(78);
            assertThat(revived.getTrnamt()).isEqualTo("+00000123.45").hasSize(12);
            assertThat(revived).isEqualTo(original);
        }

        @Test
        @DisplayName("metadata is never serialised - it is not payload")
        void metadataIsNotSerialised() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            TransactionAddRequest request = new TransactionAddRequest();
            request.requestCursorAt("TRNIDINI");
            String json = mapper.writeValueAsString(request);
            assertThat(json)
                    .doesNotContain("metadata")
                    .doesNotContain("allMetadata")
                    .doesNotContain("TRNIDINL")
                    .doesNotContain("cursorField");
        }

        @Test
        @DisplayName("the wire carries all 21 xxxI keys and none of the 63 xxxL, xxxF or xxxA names")
        void thePayloadIsTheXxxIItemsAndNothingElse() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            TransactionAddRequest request = populated();
            request.requestCursorAt("TRNIDINI");
            request.getMetadata("ERRMSGI").setAttributeItem("*");

            Map<String, Object> tree = mapper.readValue(
                    mapper.writeValueAsString(request),
                    new TypeReference<Map<String, Object>>() { });

            for (String field : TransactionAddRequest.PAYLOAD_FIELD_NAMES) {
                String property = field.substring(0, field.length() - 1).toLowerCase(Locale.ROOT);
                assertThat(tree).as("payload key for %s", field).containsKey(property);
            }

            List<String> metadataNames = new ArrayList<>();
            for (String field : TransactionAddRequest.PAYLOAD_FIELD_NAMES) {
                ScreenFieldMetadata carrier = request.getMetadata(field);
                metadataNames.add(carrier.lengthItemName());
                metadataNames.add(carrier.flagItemName());
                metadataNames.add(carrier.attributeItemName());
            }
            assertThat(metadataNames).hasSize(63).doesNotHaveDuplicates();

            String json = mapper.writeValueAsString(request);
            for (String metadataName : metadataNames) {
                assertThat(json).as("%s is metadata, not payload", metadataName)
                        .doesNotContain(metadataName);
            }

            Set<String> wireKeys = new LinkedHashSet<>(tree.keySet());
            Map<String, Object> extension = mapper.convertValue(
                    tree.get("ct01Info"), new TypeReference<Map<String, Object>>() { });
            wireKeys.addAll(extension.keySet());
            for (String metadataName : metadataNames) {
                assertThat(wireKeys)
                        .as("%s must not appear as a wire property", metadataName)
                        .noneMatch(key -> key.equalsIgnoreCase(metadataName));
            }

            assertThat(tree).hasSize(TransactionAddRequest.PAYLOAD_FIELD_COUNT + 3);
            assertThat(extension).hasSize(6);
        }

        @Test
        @DisplayName("the derived ENTER/REENTER predicates are not payload members either")
        void derivedPredicatesAreNotSerialised() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            String json = mapper.writeValueAsString(new TransactionAddRequest());
            assertThat(json).doesNotContain("\"enter\"").doesNotContain("\"reenter\"");
        }
    }

    @Nested
    @DisplayName("Rendering - every width goes through FixedWidthCodec")
    class Rendering {
        @Test
        @DisplayName("the payload image is exactly 416 characters")
        void payloadImageIs416() {
            assertThat(new TransactionAddRequest().toPayloadImage(CODEC)).hasSize(416);
            assertThat(populated().toPayloadImage(CODEC)).hasSize(416);
        }

        @Test
        @DisplayName("the commarea image is exactly 218 bytes: 160 + 58")
        void commareaImageIs218() {
            assertThat(populated().toCommareaImage(CODEC)).hasSize(218);

            TransactionAddRequest fresh = new TransactionAddRequest();
            fresh.setNavigationContext(NavigationContext.empty());
            assertThat(fresh.toCommareaImage(CODEC)).hasSize(218);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new TransactionAddRequest().toCommareaImage(CODEC));
        }

        @Test
        @DisplayName("a null field renders as the blank field COBOL would hold")
        void nullRendersAsBlank() {
            TransactionAddRequest request = new TransactionAddRequest();
            request.setTdesc(null);
            assertThat(request.getTdesc()).isNull();
            assertThat(request.toFieldImages(CODEC).get("TDESCI")).hasSize(60).isBlank();
        }

        @Test
        @DisplayName("a short value is padded on the right and an over-long one truncated on the right")
        void movePicXRulesApplyAtTheRenderBoundary() {
            TransactionAddRequest request = new TransactionAddRequest();
            request.setTtypcd("1");
            assertThat(request.toFieldImages(CODEC).get("TTYPCDI")).isEqualTo("1 ");
            request.setTtypcd("ABCD");
            assertThat(request.toFieldImages(CODEC).get("TTYPCDI")).isEqualTo("AB");
        }

        @Test
        @DisplayName("rendering requires an explicit codec - the code page is never defaulted")
        void renderingRequiresACodec() {
            TransactionAddRequest request = new TransactionAddRequest();
            assertThatNullPointerException().isThrownBy(() -> request.toFieldImages(null));
            assertThatNullPointerException().isThrownBy(() -> request.toCommareaImage(null));
        }
    }

    @Nested
    @DisplayName("Screen metadata - xxxL is signed, xxxA aliases xxxF")
    class Metadata {
        @Test
        @DisplayName("one carrier exists per field, keyed by the verbatim xxxI name")
        void oneCarrierPerField() {
            TransactionAddRequest request = new TransactionAddRequest();
            assertThat(request.getAllMetadata()).hasSize(21)
                    .containsOnlyKeys(TransactionAddRequest.PAYLOAD_FIELD_NAMES.toArray(String[]::new));
        }

        @Test
        @DisplayName("the companion item names are derived verbatim: L, F, A and I")
        void companionItemNamesAreVerbatim() {
            ScreenFieldMetadata carrier = new TransactionAddRequest().getMetadata("TRNNAMEI");
            assertThat(carrier.getScreenName()).isEqualTo("TRNNAME");
            assertThat(carrier.lengthItemName()).isEqualTo("TRNNAMEL");
            assertThat(carrier.flagItemName()).isEqualTo("TRNNAMEF");
            assertThat(carrier.attributeItemName()).isEqualTo("TRNNAMEA");
            assertThat(carrier.inputItemName()).isEqualTo("TRNNAMEI");
            assertThat(carrier.getDeclaredLength()).isEqualTo(4);
        }

        @Test
        @DisplayName("the length item holds -1, as MOVE -1 TO TRNIDINL requires")
        void lengthItemHoldsMinusOne() {
            TransactionAddRequest request = new TransactionAddRequest();
            request.requestCursorAt("TRNIDINI");
            ScreenFieldMetadata carrier = request.getMetadata("TRNIDINI");
            assertThat(TransactionAddRequest.CURSOR_REQUEST).isEqualTo((short) -1);
            assertThat(carrier.getLengthItem()).isEqualTo((short) -1);
            assertThat(carrier.isCursorRequested()).isTrue();
            assertThat(carrier.hasInput()).isFalse();
            assertThat(request.cursorField()).isEqualTo("TRNIDINI");
        }

        @Test
        @DisplayName("a positive length item means the terminal sent input")
        void positiveLengthItemMeansInput() {
            ScreenFieldMetadata carrier = new TransactionAddRequest().getMetadata("TRNIDINI");
            carrier.setLengthItem((short) 16);
            assertThat(carrier.hasInput()).isTrue();
            assertThat(carrier.isCursorRequested()).isFalse();
        }

        @Test
        @DisplayName("no cursor is requested until one is, and reset clears it")
        void cursorFieldIsNullUntilRequestedAndResetClearsIt() {
            TransactionAddRequest request = new TransactionAddRequest();
            assertThat(request.cursorField()).isNull();
            request.requestCursorAt("TRNIDINI");
            assertThat(request.cursorField()).isEqualTo("TRNIDINI");
            request.resetMetadata();
            assertThat(request.cursorField()).isNull();
            assertThat(request.getMetadata("TRNIDINI").getLengthItem()).isZero();
        }

        @Test
        @DisplayName("xxxA REDEFINES xxxF, so writing either name changes the one shared byte")
        void attributeAliasesTheFlagByte() {
            ScreenFieldMetadata carrier = new TransactionAddRequest().getMetadata("TRNIDINI");
            carrier.setFlagItem("X");
            assertThat(carrier.getFlagItem()).isEqualTo("X");
            assertThat(carrier.getAttributeItem()).isEqualTo("X");
            carrier.setAttributeItem("Y");
            assertThat(carrier.getAttributeItem()).isEqualTo("Y");
            assertThat(carrier.getFlagItem()).isEqualTo("Y");
        }

        @Test
        @DisplayName("the shared byte normalises null and empty to a space and refuses two characters")
        void theSharedByteIsOneByteWide() {
            ScreenFieldMetadata carrier = new TransactionAddRequest().getMetadata("TRNIDINI");
            carrier.setFlagItem(null);
            assertThat(carrier.getFlagItem()).isEqualTo(" ");
            carrier.setFlagItem("");
            assertThat(carrier.getFlagItem()).isEqualTo(" ");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> carrier.setAttributeItem("AB"))
                    .withMessageContaining("TRNIDINA");
        }

        @Test
        @DisplayName("an unknown field name is rejected rather than silently defaulted")
        void unknownFieldNamesAreRejected() {
            TransactionAddRequest request = new TransactionAddRequest();
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> request.getMetadata("NOSUCHI"));
            assertThatNullPointerException().isThrownBy(() -> request.getMetadata(null));
        }

        @Test
        @DisplayName("a carrier requires a real label and a positive width")
        void carrierConstructionIsGuarded() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new ScreenFieldMetadata(null, 4));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new ScreenFieldMetadata("   ", 4))
                    .withMessageContaining("cannot be blank");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new ScreenFieldMetadata("TRNNAME", 0))
                    .withMessageContaining("at least 1 byte");
        }

        @Test
        @DisplayName("carriers compare and describe by value")
        void carriersCompareByValue() {
            ScreenFieldMetadata left = new ScreenFieldMetadata("TRNNAME", 4);
            ScreenFieldMetadata right = new ScreenFieldMetadata("TRNNAME", 4);
            assertThat(left).isEqualTo(right).isEqualTo(left).hasSameHashCodeAs(right);
            assertThat(left).isNotEqualTo(null).isNotEqualTo("TRNNAME");
            right.requestCursor();
            assertThat(left).isNotEqualTo(right);
            assertThat(new ScreenFieldMetadata(right)).isEqualTo(right);
            assertThat(right.toString()).contains("TRNNAMEL=-1").contains("TRNNAME");
        }
    }

    @Nested
    @DisplayName("CDEMO-CT01-INFO - the 58-byte commarea extension")
    class Extension {
        @Test
        @DisplayName("the six items are 16, 16, 8, 1, 1 and 16 and sum to 58")
        void widthsSumTo58() {
            assertThat(Ct01Info.TRNID_FIRST_LENGTH).isEqualTo(16);
            assertThat(Ct01Info.TRNID_LAST_LENGTH).isEqualTo(16);
            assertThat(Ct01Info.PAGE_NUM_LENGTH).isEqualTo(8);
            assertThat(Ct01Info.NEXT_PAGE_FLG_LENGTH).isEqualTo(1);
            assertThat(Ct01Info.TRN_SEL_FLG_LENGTH).isEqualTo(1);
            assertThat(Ct01Info.TRN_SELECTED_LENGTH).isEqualTo(16);
            assertThat(16 + 16 + 8 + 1 + 1 + 16).isEqualTo(Ct01Info.RECORD_LENGTH);
            assertThat(Ct01Info.LAYOUT.recordLength()).isEqualTo(58);
        }

        @Test
        @DisplayName("the item names keep the CT01 prefix, so CT00 and CT02 stay distinguishable")
        void itemNamesKeepTheCt01Prefix() {
            assertThat(Ct01Info.TRNID_FIRST_FIELD).isEqualTo("CDEMO-CT01-TRNID-FIRST");
            assertThat(Ct01Info.TRNID_LAST_FIELD).isEqualTo("CDEMO-CT01-TRNID-LAST");
            assertThat(Ct01Info.PAGE_NUM_FIELD).isEqualTo("CDEMO-CT01-PAGE-NUM");
            assertThat(Ct01Info.NEXT_PAGE_FLG_FIELD).isEqualTo("CDEMO-CT01-NEXT-PAGE-FLG");
            assertThat(Ct01Info.TRN_SEL_FLG_FIELD).isEqualTo("CDEMO-CT01-TRN-SEL-FLG");
            assertThat(Ct01Info.TRN_SELECTED_FIELD).isEqualTo("CDEMO-CT01-TRN-SELECTED");
        }

        @Test
        @DisplayName("VALUE 'N' is the declared initial state of the next-page flag")
        void nextPageFlagStartsAtN() {
            Ct01Info info = new Ct01Info();
            assertThat(info.getNextPageFlg()).isEqualTo("N");
            assertThat(info.isNextPageNo()).isTrue();
            assertThat(info.isNextPageYes()).isFalse();
        }

        @Test
        @DisplayName("both 88-level conditions are reachable in both directions")
        void bothConditionNamesAreReachable() {
            Ct01Info info = new Ct01Info();
            info.setNextPageYes();
            assertThat(info.getNextPageFlg()).isEqualTo("Y");
            assertThat(info.isNextPageYes()).isTrue();
            assertThat(info.isNextPageNo()).isFalse();
            info.setNextPageNo();
            assertThat(info.isNextPageNo()).isTrue();
            assertThat(info.isNextPageYes()).isFalse();
            info.setNextPageFlg(null);
            assertThat(info.isNextPageYes()).isFalse();
            assertThat(info.isNextPageNo()).isFalse();

            info.setNextPageFlg("X");
            assertThat(info.getNextPageFlg()).isEqualTo("X");
            assertThat(info.isNextPageYes()).isFalse();
            assertThat(info.isNextPageNo()).isFalse();

            info.setNextPageFlg(" ");
            assertThat(info.isNextPageYes()).isFalse();
            assertThat(info.isNextPageNo()).isFalse();
        }

        @Test
        @DisplayName("PAGE-NUM is an unsigned 8-digit picture: 0 to 99999999 and nothing else")
        void pageNumberHonoursItsPicture() {
            Ct01Info info = new Ct01Info();
            assertThat(info.getPageNum()).isZero();
            info.setPageNum(99_999_999);
            assertThat(info.getPageNum()).isEqualTo(99_999_999);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> info.setPageNum(-1))
                    .withMessageContaining("unsigned picture");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> info.setPageNum(100_000_000))
                    .withMessageContaining("holds at most 99999999");
        }

        @Test
        @DisplayName("hasSelection mirrors NOT = SPACES AND LOW-VALUES from COTRN01C line 103")
        void hasSelectionMirrorsTheCobolTest() {
            Ct01Info info = new Ct01Info();
            assertThat(info.hasSelection()).as("all spaces is no selection").isFalse();
            info.setTrnSelected(null);
            assertThat(info.hasSelection()).as("null is no selection").isFalse();
            info.setTrnSelected("");
            assertThat(info.hasSelection()).as("empty is no selection").isFalse();
            info.setTrnSelected("\0\0\0\0");
            assertThat(info.hasSelection()).as("LOW-VALUES is no selection").isFalse();
            info.setTrnSelected("0000000000000007");
            assertThat(info.hasSelection()).as("a real id is a selection").isTrue();
        }

        @Test
        @DisplayName("renders 58 bytes and reads them back losslessly, untrimmed")
        void roundTripsThroughItsFixedWidthImage() {
            Ct01Info info = new Ct01Info();
            info.setTrnidFirst("0000000000000001");
            info.setTrnidLast("0000000000000010");
            info.setPageNum(3);
            info.setNextPageYes();
            info.setTrnSelFlg("S");
            info.setTrnSelected("0000000000000007");

            byte[] image = info.toFixedWidth(CODEC);
            assertThat(image).hasSize(58);

            Ct01Info revived = Ct01Info.fromFixedWidth(CODEC, image);
            assertThat(revived).isEqualTo(info);
            assertThat(revived.getPageNum()).isEqualTo(3);
            assertThat(revived.isNextPageYes()).isTrue();
            assertThat(revived.getTrnSelected()).isEqualTo("0000000000000007");
            assertThat(revived.toFixedWidth(CODEC)).isEqualTo(image);
        }

        @Test
        @DisplayName("a blank extension still renders exactly 58 bytes, zero-filled page number")
        void blankExtensionStillRenders58() {
            Ct01Info blank = new Ct01Info();
            blank.setTrnidFirst(null);
            blank.setTrnidLast(null);
            blank.setTrnSelFlg(null);
            blank.setTrnSelected(null);
            byte[] image = blank.toFixedWidth(CODEC);
            assertThat(image).hasSize(58);
            assertThat(new String(image, StandardCharsets.US_ASCII))
                    .startsWith(" ".repeat(32))
                    .contains("00000000");
        }

        @Test
        @DisplayName("reading and writing an image both require an explicit codec and a real image")
        void imageAccessIsGuarded() {
            Ct01Info info = new Ct01Info();
            assertThatNullPointerException().isThrownBy(() -> info.toFixedWidth(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> Ct01Info.fromFixedWidth(null, new byte[58]));
            assertThatNullPointerException()
                    .isThrownBy(() -> Ct01Info.fromFixedWidth(CODEC, null));
        }

        @Test
        @DisplayName("extensions compare, hash and describe by value")
        void extensionsCompareByValue() {
            Ct01Info left = new Ct01Info();
            Ct01Info right = new Ct01Info();
            assertThat(left).isEqualTo(right).isEqualTo(left).hasSameHashCodeAs(right);
            assertThat(left).isNotEqualTo(null).isNotEqualTo("CDEMO-CT01-INFO");
            right.setPageNum(2);
            assertThat(left).isNotEqualTo(right);
            assertThat(new Ct01Info(right)).isEqualTo(right);
            assertThatNullPointerException().isThrownBy(() -> new Ct01Info(null));
            assertThat(left.toString()).contains("pageNum=0").contains("nextPageFlg='N'");
        }

        @Test
        @DisplayName("the list-to-detail handoff carries the selected id and the paging window")
        void carriesTheListHandoff() {
            TransactionAddRequest request = populated();
            assertThat(request.getCt01Info().getTrnSelected()).isEqualTo("0000000000000007");
            assertThat(request.getCt01Info().hasSelection()).isTrue();
            assertThat(request.getCt01Info().getTrnidFirst()).isEqualTo("0000000000000001");
            assertThat(request.getCt01Info().getTrnidLast()).isEqualTo("0000000000000010");
            assertThat(request.getCt01Info().getPageNum()).isEqualTo(3);
            assertThat(request.getCt01Info().getTrnSelFlg()).isEqualTo("S");
        }
    }

    @Nested
    @DisplayName("Statelessness - the conversation travels in the payload")
    class Statelessness {
        @Test
        @DisplayName("the communication area is carried and is never widened past 160 bytes")
        void commareaIsCarriedAtItsDeclaredWidth() {
            TransactionAddRequest request = populated();
            assertThat(request.getNavigationContext().fromTranid()).isEqualTo("CT00");
            assertThat(request.getNavigationContext().fromProgram()).isEqualTo("COTRN00C");
            assertThat(request.getNavigationContext().userId()).isEqualTo("USER0001");
            assertThat(request.getNavigationContext().toFixedWidth(CODEC)).hasSize(160);
        }

        @Test
        @DisplayName("ENTER and REENTER are both expressible and are derived, never duplicated")
        void enterAndReenterAreBothReachable() {
            TransactionAddRequest request = new TransactionAddRequest();
            request.setNavigationContext(NavigationContext.empty().withPgmEnter());
            assertThat(request.isEnter()).isTrue();
            assertThat(request.isReenter()).isFalse();
            request.setNavigationContext(NavigationContext.empty().withPgmReenter());
            assertThat(request.isReenter()).isTrue();
            assertThat(request.isEnter()).isFalse();
        }

        @Test
        @DisplayName("a null communication area is preserved: it is EIBCALEN = 0, not a missing value")
        void aNullCommareaIsPreserved() {
            TransactionAddRequest request = populated();
            request.setNavigationContext(null);

            assertThat(request.getNavigationContext()).isNull();
            assertThat(request.hasNavigationContext()).isFalse();
            assertThat(request.commareaLength()).isZero();
            assertThat(request.isEnter()).isFalse();
            assertThat(request.isReenter()).isFalse();
        }

        @Test
        @DisplayName("a null CDEMO-CT01-INFO extension is still normalised to a fresh one")
        void aNullExtensionIsNormalised() {
            TransactionAddRequest request = populated();
            request.setCt01Info(null);

            assertThat(request.getCt01Info()).isEqualTo(new Ct01Info());
            assertThat(request.getCt01Info().getNextPageFlg()).isEqualTo("N");
        }

        @Test
        @DisplayName("a present area reports EIBCALEN 218 and its own context state")
        void aPresentAreaReportsItsState() {
            TransactionAddRequest request = populated();
            request.setNavigationContext(NavigationContext.empty());

            assertThat(request.hasNavigationContext()).isTrue();
            assertThat(request.commareaLength())
                    .isEqualTo(TransactionAddRequest.COMMAREA_TOTAL_LENGTH)
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH + Ct01Info.RECORD_LENGTH);
            assertThat(request.isEnter()).isTrue();
            assertThat(request.isReenter()).isFalse();

            request.setNavigationContext(NavigationContext.empty().withPgmReenter());
            assertThat(request.isReenter()).isTrue();
            assertThat(request.isEnter()).isFalse();
        }

        @Test
        @DisplayName("neither predicate holds for a context digit that names no condition")
        void anUnknownContextDigitSatisfiesNeither() {
            TransactionAddRequest request = populated();
            request.setNavigationContext(NavigationContext.empty().withPgmContext(9));

            assertThat(request.isEnter()).isFalse();
            assertThat(request.isReenter()).isFalse();
        }

        @Test
        @DisplayName("with no area there are no commarea bytes to render, and none are invented")
        void aColdStartHasNoCommareaImage() {
            TransactionAddRequest request = populated();
            request.setNavigationContext(null);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> request.toCommareaImage(CODEC))
                    .withMessageContaining("EIBCALEN is 0")
                    .withMessageContaining("hasNavigationContext");
        }

        @Test
        @DisplayName("the diagnostic rendering names the cold start rather than printing a context")
        void theDiagnosticNamesTheColdStart() {
            TransactionAddRequest cold = populated();
            cold.setNavigationContext(null);

            assertThat(cold.toString()).contains("pgmContext=none (EIBCALEN=0)");

            TransactionAddRequest warm = populated();
            warm.setNavigationContext(NavigationContext.empty());
            assertThat(warm.toString()).contains("pgmContext=0")
                    .doesNotContain("EIBCALEN");
        }

        @Test
        @DisplayName("the absence survives a JSON round trip in both directions")
        void theAbsenceSurvivesJson() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            TransactionAddRequest before = populated();
            before.setNavigationContext(null);

            String json = mapper.writeValueAsString(before);
            TransactionAddRequest after = mapper.readValue(json, TransactionAddRequest.class);

            assertThat(mapper.readTree(json).get("navigationContext").isNull()).isTrue();
            assertThat(after.hasNavigationContext()).isFalse();
            assertThat(after).isEqualTo(before);
        }

        @Test
        @DisplayName("the EIBAID attention identifier is carried in the payload, as the raw byte or as "
                + "the five-character CCARD-AID token")
        void attentionIdentifierIsCarried() {
            assertThat(TransactionAddRequest.AID_LENGTH).isEqualTo(1);
            assertThat(TransactionAddRequest.AID_TOKEN_LENGTH).isEqualTo(5);
            TransactionAddRequest request = new TransactionAddRequest();
            request.setAid("\u0027");
            assertThat(request.getAid()).isEqualTo("\u0027");
            request.setAid("PFK05");
            assertThat(request.getAid()).isEqualTo("PFK05");
        }
    }

    @Nested
    @DisplayName("Bean Validation is never stricter than the COBOL")
    class BeanValidation {
        private Set<ConstraintViolation<TransactionAddRequest>> validate(
                TransactionAddRequest request) {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                Validator validator = factory.getValidator();
                return validator.validate(request);
            }
        }

        @Test
        @DisplayName("a blank request is valid - COTRN01C does its own editing")
        void blankRequestIsValid() {
            assertThat(validate(new TransactionAddRequest())).isEmpty();
        }

        @Test
        @DisplayName("a fully populated request is valid")
        void populatedRequestIsValid() {
            assertThat(validate(populated())).isEmpty();
        }

        @Test
        @DisplayName("a null field is valid - @Size ignores null, so nothing extra is rejected")
        void nullFieldsAreValid() {
            TransactionAddRequest request = new TransactionAddRequest();
            request.setTrnidin(null);
            request.setErrmsg(null);
            assertThat(validate(request)).isEmpty();
        }

        @Test
        @DisplayName("an over-long value is the ONLY thing reported, since BMS cannot deliver one")
        void overLongValuesAreReported() {
            TransactionAddRequest request = new TransactionAddRequest();
            request.setTrnidin("X".repeat(17));
            Set<ConstraintViolation<TransactionAddRequest>> violations = validate(request);
            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath().toString())
                    .isEqualTo("trnidin");
        }

        @Test
        @DisplayName("the nested extension is validated too - @Valid cascades")
        void nestedExtensionIsValidated() {
            TransactionAddRequest request = new TransactionAddRequest();
            request.getCt01Info().setTrnSelected("X".repeat(17));
            Set<ConstraintViolation<TransactionAddRequest>> violations = validate(request);
            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath().toString())
                    .isEqualTo("ct01Info.trnSelected");
        }

        @ParameterizedTest(name = "{0} accepts {1} characters and rejects {2}")
        @MethodSource(
                "com.vsergeychik.carddemo.transaction.dto.TransactionAddRequestTest#sizedFields")
        @DisplayName("each constrained field passes at its width and fails one character over")
        void everyConstrainedFieldIsDrivenBothWays(String property,
                                                   int declaredWidth,
                                                   int overLongWidth,
                                                   Consumer<TransactionAddRequest> atWidth,
                                                   Consumer<TransactionAddRequest> overWidth) {
            assertThat(overLongWidth).isEqualTo(declaredWidth + 1);

            TransactionAddRequest exact = new TransactionAddRequest();
            atWidth.accept(exact);
            assertThat(validate(exact))
                    .as("%s holds %d characters without complaint", property, declaredWidth)
                    .isEmpty();

            TransactionAddRequest tooLong = new TransactionAddRequest();
            overWidth.accept(tooLong);
            Set<ConstraintViolation<TransactionAddRequest>> violations = validate(tooLong);
            assertThat(violations).as("%s at %d characters", property, overLongWidth).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath().toString())
                    .isEqualTo(property);
        }

        @Test
        @DisplayName("the extension's own five character items are each driven both ways")
        void theExtensionCharacterItemsAreDrivenBothWays() {
            record Item(String property, int width, Consumer<Ct01Info> setter) { }
            List<Item> items = List.of(
                    new Item("trnidFirst", 16, info -> info.setTrnidFirst("X".repeat(17))),
                    new Item("trnidLast", 16, info -> info.setTrnidLast("X".repeat(17))),
                    new Item("nextPageFlg", 1, info -> info.setNextPageFlg("XX")),
                    new Item("trnSelFlg", 1, info -> info.setTrnSelFlg("XX")),
                    new Item("trnSelected", 16, info -> info.setTrnSelected("X".repeat(17))));

            assertThat(validate(new TransactionAddRequest())).isEmpty();

            for (Item item : items) {
                TransactionAddRequest request = new TransactionAddRequest();
                item.setter().accept(request.getCt01Info());
                Set<ConstraintViolation<TransactionAddRequest>> violations = validate(request);
                assertThat(violations)
                        .as("%s is PIC X(%d), so %d characters cannot be stored",
                                item.property(), item.width(), item.width() + 1)
                        .hasSize(1);
                assertThat(violations.iterator().next().getPropertyPath().toString())
                        .isEqualTo("ct01Info." + item.property());
            }
        }

        @Test
        @DisplayName("PAGE-NUM is bounded on both sides, by the setter and by @Min/@Max alike")
        void pageNumberIsBoundedBothWays() {
            Ct01Info info = new Ct01Info();
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> info.setPageNum(-1))
                    .withMessageContaining("unsigned");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> info.setPageNum(100_000_000))
                    .withMessageContaining("holds at most");

            TransactionAddRequest low = new TransactionAddRequest();
            low.getCt01Info().setPageNum(0);
            assertThat(validate(low)).isEmpty();

            TransactionAddRequest high = new TransactionAddRequest();
            high.getCt01Info().setPageNum(99_999_999);
            assertThat(validate(high)).isEmpty();
            assertThat(high.getCt01Info().getPageNum()).isEqualTo(Ct01Info.PAGE_NUM_MAX);
        }

        @Test
        @DisplayName("nothing beyond @Size is asserted - COTRN01C edits the key itself, at line 151")
        void noValidationBeyondSizeIsImposed() {
            TransactionAddRequest blankKey = new TransactionAddRequest();
            blankKey.setTrnidin(TransactionAddRequest.spaces(16));
            assertThat(validate(blankKey)).isEmpty();

            TransactionAddRequest oddKey = new TransactionAddRequest();
            oddKey.setTrnidin("NOT-A-TRAN-ID");
            assertThat(validate(oddKey)).isEmpty();
            assertThat(oddKey.getTrnidin()).isEqualTo("NOT-A-TRAN-ID");
        }
    }

    @Nested
    @DisplayName("Every one of the 21 accessors is wired to its own field")
    class Accessors {
        static Stream<Arguments> accessorPairs() {
            return Stream.of(
                    Arguments.of("TRNNAMEI", (Consumer<TransactionAddRequest>) r -> r.setTrnname("v"),
                            (java.util.function.Function<TransactionAddRequest, String>)
                                    TransactionAddRequest::getTrnname),
                    Arguments.of("TITLE01I", (Consumer<TransactionAddRequest>) r -> r.setTitle01("v"),
                            (java.util.function.Function<TransactionAddRequest, String>)
                                    TransactionAddRequest::getTitle01),
                    Arguments.of("CURDATEI", (Consumer<TransactionAddRequest>) r -> r.setCurdate("v"),
                            (java.util.function.Function<TransactionAddRequest, String>)
                                    TransactionAddRequest::getCurdate),
                    Arguments.of("PGMNAMEI", (Consumer<TransactionAddRequest>) r -> r.setPgmname("v"),
                            (java.util.function.Function<TransactionAddRequest, String>)
                                    TransactionAddRequest::getPgmname),
                    Arguments.of("TITLE02I", (Consumer<TransactionAddRequest>) r -> r.setTitle02("v"),
                            (java.util.function.Function<TransactionAddRequest, String>)
                                    TransactionAddRequest::getTitle02),
                    Arguments.of("CURTIMEI", (Consumer<TransactionAddRequest>) r -> r.setCurtime("v"),
                            (java.util.function.Function<TransactionAddRequest, String>)
                                    TransactionAddRequest::getCurtime),
                    Arguments.of("TRNIDINI", (Consumer<TransactionAddRequest>) r -> r.setTrnidin("v"),
                            (java.util.function.Function<TransactionAddRequest, String>)
                                    TransactionAddRequest::getTrnidin),
                    Arguments.of("TRNIDI", (Consumer<TransactionAddRequest>) r -> r.setTrnid("v"),
                            (java.util.function.Function<TransactionAddRequest, String>)
                                    TransactionAddRequest::getTrnid),
                    Arguments.of("CARDNUMI", (Consumer<TransactionAddRequest>) r -> r.setCardnum("v"),
                            (java.util.function.Function<TransactionAddRequest, String>)
                                    TransactionAddRequest::getCardnum),
                    Arguments.of("TTYPCDI", (Consumer<TransactionAddRequest>) r -> r.setTtypcd("v"),
                            (java.util.function.Function<TransactionAddRequest, String>)
                                    TransactionAddRequest::getTtypcd),
                    Arguments.of("TCATCDI", (Consumer<TransactionAddRequest>) r -> r.setTcatcd("v"),
                            (java.util.function.Function<TransactionAddRequest, String>)
                                    TransactionAddRequest::getTcatcd),
                    Arguments.of("TRNSRCI", (Consumer<TransactionAddRequest>) r -> r.setTrnsrc("v"),
                            (java.util.function.Function<TransactionAddRequest, String>)
                                    TransactionAddRequest::getTrnsrc),
                    Arguments.of("TDESCI", (Consumer<TransactionAddRequest>) r -> r.setTdesc("v"),
                            (java.util.function.Function<TransactionAddRequest, String>)
                                    TransactionAddRequest::getTdesc),
                    Arguments.of("TRNAMTI", (Consumer<TransactionAddRequest>) r -> r.setTrnamt("v"),
                            (java.util.function.Function<TransactionAddRequest, String>)
                                    TransactionAddRequest::getTrnamt),
                    Arguments.of("TORIGDTI", (Consumer<TransactionAddRequest>) r -> r.setTorigdt("v"),
                            (java.util.function.Function<TransactionAddRequest, String>)
                                    TransactionAddRequest::getTorigdt),
                    Arguments.of("TPROCDTI", (Consumer<TransactionAddRequest>) r -> r.setTprocdt("v"),
                            (java.util.function.Function<TransactionAddRequest, String>)
                                    TransactionAddRequest::getTprocdt),
                    Arguments.of("MIDI", (Consumer<TransactionAddRequest>) r -> r.setMid("v"),
                            (java.util.function.Function<TransactionAddRequest, String>)
                                    TransactionAddRequest::getMid),
                    Arguments.of("MNAMEI", (Consumer<TransactionAddRequest>) r -> r.setMname("v"),
                            (java.util.function.Function<TransactionAddRequest, String>)
                                    TransactionAddRequest::getMname),
                    Arguments.of("MCITYI", (Consumer<TransactionAddRequest>) r -> r.setMcity("v"),
                            (java.util.function.Function<TransactionAddRequest, String>)
                                    TransactionAddRequest::getMcity),
                    Arguments.of("MZIPI", (Consumer<TransactionAddRequest>) r -> r.setMzip("v"),
                            (java.util.function.Function<TransactionAddRequest, String>)
                                    TransactionAddRequest::getMzip),
                    Arguments.of("ERRMSGI", (Consumer<TransactionAddRequest>) r -> r.setErrmsg("v"),
                            (java.util.function.Function<TransactionAddRequest, String>)
                                    TransactionAddRequest::getErrmsg));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("accessorPairs")
        @DisplayName("the setter stores exactly what it is given and the getter returns it")
        void accessorIsWiredToItsOwnField(String fieldName,
                                          Consumer<TransactionAddRequest> setter,
                                          java.util.function.Function<TransactionAddRequest, String>
                                                  getter) {
            TransactionAddRequest request = new TransactionAddRequest();
            setter.accept(request);

            assertThat(getter.apply(request))
                    .as("%s setter stores the value unchanged - no pad, no truncate", fieldName)
                    .isEqualTo("v");

            Map<String, String> images = request.toFieldImages(CODEC);
            assertThat(images.get(fieldName))
                    .as("%s renders at its declared width", fieldName)
                    .hasSize(TransactionAddRequest.declaredLengthOf(fieldName))
                    .startsWith("v");

            long changed = images.entrySet().stream()
                    .filter(e -> !e.getValue().isBlank())
                    .count();
            assertThat(changed)
                    .as("%s setter touches exactly one field", fieldName)
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Value semantics")
    class ValueSemantics {
        @Test
        @DisplayName("two identically populated requests are equal and hash alike")
        void identicalRequestsAreEqual() {
            assertThat(populated()).isEqualTo(populated()).hasSameHashCodeAs(populated());
        }

        @Test
        @DisplayName("a request equals itself and nothing of another type")
        void reflexiveAndTypeChecked() {
            TransactionAddRequest request = populated();
            assertThat(request).isEqualTo(request);
            assertThat(request).isNotEqualTo(null).isNotEqualTo("COTRN1AI");
        }

        @Test
        @DisplayName("the deep copy is equal but shares no mutable component")
        void deepCopyIsEqualButIndependent() {
            TransactionAddRequest original = populated();
            TransactionAddRequest copy = new TransactionAddRequest(original);
            assertThat(copy).isEqualTo(original);
            assertThat(copy.getCt01Info()).isNotSameAs(original.getCt01Info());
            assertThat(copy.getMetadata("TRNIDINI"))
                    .isNotSameAs(original.getMetadata("TRNIDINI"));

            copy.getCt01Info().setPageNum(99);
            copy.requestCursorAt("TRNIDINI");
            assertThat(original.getCt01Info().getPageNum()).isEqualTo(3);
            assertThat(original.cursorField()).isNull();
            assertThat(copy).isNotEqualTo(original);

            assertThatNullPointerException()
                    .isThrownBy(() -> new TransactionAddRequest(null));
        }

        @Test
        @DisplayName("a difference in any single field breaks equality")
        void anyFieldDifferenceBreaksEquality() {
            List<Consumer<TransactionAddRequest>> mutators = List.of(
                    r -> r.setTrnname("x"), r -> r.setTitle01("x"), r -> r.setCurdate("x"),
                    r -> r.setPgmname("x"), r -> r.setTitle02("x"), r -> r.setCurtime("x"),
                    r -> r.setTrnidin("x"), r -> r.setTrnid("x"), r -> r.setCardnum("x"),
                    r -> r.setTtypcd("x"), r -> r.setTcatcd("x"), r -> r.setTrnsrc("x"),
                    r -> r.setTdesc("x"), r -> r.setTrnamt("x"), r -> r.setTorigdt("x"),
                    r -> r.setTprocdt("x"), r -> r.setMid("x"), r -> r.setMname("x"),
                    r -> r.setMcity("x"), r -> r.setMzip("x"), r -> r.setErrmsg("x"),
                    r -> r.setAid("x"),
                    r -> r.setNavigationContext(NavigationContext.empty().withUserId("OTHER")),
                    r -> r.getCt01Info().setPageNum(42),
                    r -> r.requestCursorAt("TRNIDINI"));

            for (Consumer<TransactionAddRequest> mutator : mutators) {
                TransactionAddRequest mutated = populated();
                mutator.accept(mutated);
                assertThat(mutated)
                        .as("a single-field change must break equality")
                        .isNotEqualTo(populated());
            }
        }

        @Test
        @DisplayName("padding is content - trailing spaces are part of the value")
        void paddingIsContent() {
            TransactionAddRequest padded = new TransactionAddRequest();
            padded.setTdesc(CODEC.movePicX("PURCHASE", 60));
            TransactionAddRequest unpadded = new TransactionAddRequest();
            unpadded.setTdesc("PURCHASE");
            assertThat(padded).isNotEqualTo(unpadded);
            assertThat(padded.toFieldImages(CODEC).get("TDESCI"))
                    .isEqualTo(unpadded.toFieldImages(CODEC).get("TDESCI"));
        }

        @Test
        @DisplayName("toString names the screen and the identifying fields without redacting anything")
        void toStringIsADiagnosticSummary() {
            String description = populated().toString();
            assertThat(description)
                    .contains("CT01")
                    .contains("COTRN01C")
                    .contains("COTRN01.COTRN1A")
                    .contains("TRNIDINI='0000000000000001'");
        }
    }

    @Nested
    @DisplayName("Risk R-B - named Add, shaped View, and the map itself is the proof")
    class RiskRB {
        private static final String PROGRAM_FUNCTION_HEADER =
                "Function    : View a Transaction from TRANSACT file";

        private static final String README_CT01_ROW =
                "|             | CT01 | COTRN01 | COTRN01C | Transaction View    |";
        private static final String README_CT02_ROW =
                "|             | CT02 | COTRN02 | COTRN02C | Transaction Add     |";

        @Test
        @DisplayName("proof 1 and 2 - the program header and the README both say View, not Add")
        void theSourceAndTheReadmeBothSayView() {
            assertThat(PROGRAM_FUNCTION_HEADER).contains("View a Transaction").doesNotContain("Add");
            assertThat(README_CT01_ROW).contains("COTRN01C").contains("Transaction View");
            assertThat(README_CT02_ROW).contains("COTRN02C").contains("Transaction Add");

            assertThat(TransactionAddRequest.class.getSimpleName())
                    .isEqualTo("TransactionAddRequest");
            assertThat(TransactionAddRequest.PROGRAM_NAME).isEqualTo("COTRN01C");
        }

        @Test
        @DisplayName("proof 3a - it carries BOTH TRNIDIN and TRNID at 16, a pair COTRN02 lacks")
        void itCarriesTheTypedKeyAndTheDisplayedKey() {
            assertThat(TransactionAddRequest.TRNIDIN_FIELD).isEqualTo("TRNIDINI");
            assertThat(TransactionAddRequest.TRNID_FIELD).isEqualTo("TRNIDI");
            assertThat(TransactionAddRequest.TRNIDIN_LENGTH).isEqualTo(16);
            assertThat(TransactionAddRequest.TRNID_LENGTH).isEqualTo(16);
            assertThat(TransactionAddRequest.PAYLOAD_FIELD_NAMES)
                    .containsSequence("TRNIDINI", "TRNIDI");

            assertThat(TransactionAddRequest.TRNIDIN_OFFSET)
                    .isNotEqualTo(TransactionAddRequest.TRNID_OFFSET);
            TransactionAddRequest request = new TransactionAddRequest();
            request.setTrnidin("0000000000000001");
            request.setTrnid("0000000000000002");
            assertThat(request.getTrnidin()).isEqualTo("0000000000000001");
            assertThat(request.getTrnid()).isEqualTo("0000000000000002");
        }

        @Test
        @DisplayName("proof 3b - no CONFIRM, and none of COTRN02's ACTIDIN or CARDNIN")
        void itCarriesNothingFromTheAddScreen() {
            assertThat(TransactionAddRequest.PAYLOAD_FIELD_NAMES)
                    .doesNotContain("CONFIRMI", "ACTIDINI", "CARDNINI")
                    .doesNotContain("CONFIRM", "ACTIDIN", "CARDNIN");

            for (String absent : List.of("CONFIRMI", "ACTIDINI", "CARDNINI")) {
                assertThatExceptionOfType(IllegalArgumentException.class)
                        .as("%s is not a field of COTRN1AI", absent)
                        .isThrownBy(() -> TransactionAddRequest.declaredLengthOf(absent))
                        .withMessageContaining("is not a field of COTRN1AI");
            }
            assertThat(new TransactionAddRequest().getAllMetadata())
                    .doesNotContainKeys("CONFIRMI", "ACTIDINI", "CARDNINI");
        }

        @Test
        @DisplayName("the six MOVE -1 sites all target one field: TRNIDINL, the only enterable one")
        void everyCursorRequestTargetsTheOnlyEnterableField() {
            List<Integer> cursorSites = List.of(102, 151, 154, 287, 294, 311);
            assertThat(cursorSites).hasSize(6).doesNotHaveDuplicates();

            TransactionAddRequest request = new TransactionAddRequest();
            for (Integer site : cursorSites) {
                request.resetMetadata();
                request.requestCursorAt("TRNIDINI");
                assertThat(request.cursorField())
                        .as("COTRN01C line %d positions the cursor on TRNIDIN", site)
                        .isEqualTo("TRNIDINI");
                assertThat(request.getMetadata("TRNIDINI").getLengthItem())
                        .isEqualTo(TransactionAddRequest.CURSOR_REQUEST)
                        .isEqualTo((short) -1);
            }
        }
    }

    @Nested
    @DisplayName("Per-field byte overlay - xxxL at k, xxxF and xxxA at k+2, xxxI at k+7")
    class ByteOverlay {
        @ParameterizedTest(name = "{0} occupies k={1}, xxxI at {2}, width {3}")
        @MethodSource(
                "com.vsergeychik.carddemo.transaction.dto.TransactionAddRequestTest#overlayRows")
        @DisplayName("every field lays out its four sub-spans exactly where the copybook puts them")
        void everyFieldLaysOutItsFourSubSpans(String screenName,
                                             int groupOffset,
                                             int valueOffset,
                                             int width) {
            int lengthItemEnd = groupOffset + TransactionAddRequest.LENGTH_ITEM_LENGTH;
            assertThat(lengthItemEnd).isEqualTo(groupOffset + 2);

            int flagItemOffset = lengthItemEnd;
            assertThat(flagItemOffset).isEqualTo(groupOffset + 2);
            assertThat(flagItemOffset + TransactionAddRequest.FLAG_ITEM_LENGTH)
                    .isEqualTo(groupOffset + 3);

            int fillerOffset = flagItemOffset + TransactionAddRequest.FLAG_ITEM_LENGTH;
            assertThat(fillerOffset).isEqualTo(groupOffset + 3);
            assertThat(fillerOffset + TransactionAddRequest.RESERVED_FILLER_LENGTH)
                    .isEqualTo(groupOffset + 7);

            assertThat(valueOffset).isEqualTo(groupOffset + TransactionAddRequest.FIELD_METADATA_LENGTH)
                    .isEqualTo(groupOffset + 7);
            assertThat(valueOffset + width).isLessThanOrEqualTo(
                    TransactionAddRequest.SYMBOLIC_MAP_LENGTH);

            assertThat(TransactionAddRequest.declaredLengthOf(screenName + "I")).isEqualTo(width);

            int aoFillerEnd = groupOffset + 3;
            int aoOutputOffset = aoFillerEnd + 4;
            assertThat(aoOutputOffset).isEqualTo(valueOffset);
            assertThat(aoFillerEnd).isEqualTo(fillerOffset);
        }

        @Test
        @DisplayName("the published offset constants agree with the copybook, field for field")
        void publishedOffsetsAgreeWithTheCopybook() {
            assertThat(PUBLISHED_GROUP_OFFSETS).hasSize(21);
            assertThat(PUBLISHED_VALUE_OFFSETS).hasSize(21);
            for (int i = 0; i < OVERLAY.size(); i++) {
                Object[] row = OVERLAY.get(i);
                assertThat(PUBLISHED_GROUP_OFFSETS.get(i))
                        .as("%s group offset", row[0]).isEqualTo(row[1]);
                assertThat(PUBLISHED_VALUE_OFFSETS.get(i))
                        .as("%s value offset", row[0]).isEqualTo(row[2]);
            }
        }

        @Test
        @DisplayName("the chain is contiguous: 12 bytes of TIOAPFX, then 21 groups, ending at 575")
        void theChainIsContiguousAndEndsAt575() {
            assertThat((Integer) OVERLAY.get(0)[1])
                    .as("the first group starts after the 12-byte TIOAPFX FILLER")
                    .isEqualTo(TransactionAddRequest.TIOA_PREFIX_LENGTH);

            int cursor = TransactionAddRequest.TIOA_PREFIX_LENGTH;
            for (Object[] row : OVERLAY) {
                int groupOffset = (Integer) row[1];
                int valueOffset = (Integer) row[2];
                int width = (Integer) row[3];
                assertThat(groupOffset).as("no gap or overlap before %s", row[0]).isEqualTo(cursor);
                assertThat(valueOffset).isEqualTo(groupOffset + 7);
                cursor = valueOffset + width;
            }
            assertThat(cursor).isEqualTo(TransactionAddRequest.SYMBOLIC_MAP_LENGTH).isEqualTo(575);
        }

        @Test
        @DisplayName("xxxA and xxxF are two accessors over one byte, in both directions")
        void theAttributeAndFlagItemsShareOneByte() {
            ScreenFieldMetadata carrier = new TransactionAddRequest().getMetadata("TRNIDINI");
            assertThat(carrier.flagItemName()).isEqualTo("TRNIDINF");
            assertThat(carrier.attributeItemName()).isEqualTo("TRNIDINA");

            carrier.setFlagItem("X");
            assertThat(carrier.getAttributeItem()).isEqualTo("X");
            carrier.setAttributeItem("Q");
            assertThat(carrier.getFlagItem()).isEqualTo("Q");

            assertThat(TransactionAddRequest.FLAG_ITEM_LENGTH).isEqualTo(1);
            assertThat(TransactionAddRequest.LENGTH_ITEM_LENGTH
                    + TransactionAddRequest.FLAG_ITEM_LENGTH
                    + TransactionAddRequest.RESERVED_FILLER_LENGTH)
                    .isEqualTo(TransactionAddRequest.FIELD_METADATA_LENGTH);
        }

        @Test
        @DisplayName("both FILLER spans are real positioned bytes, and they stay spaces")
        void everyFillerSpanIsSpaceFilled() {
            FixedWidthRecord area = new FixedWidthRecord(
                    TransactionAddRequest.SYMBOLIC_MAP_LENGTH, StandardCharsets.US_ASCII);
            assertThat(area.recordLength()).isEqualTo(575);

            for (Object[] row : OVERLAY) {
                area.writeString((Integer) row[2], (Integer) row[3], "#".repeat((Integer) row[3]));
            }

            assertThat(area.readString(0, TransactionAddRequest.TIOA_PREFIX_LENGTH))
                    .isEqualTo(" ".repeat(12)).isBlank();

            for (Object[] row : OVERLAY) {
                int fillerOffset = (Integer) row[1] + 3;
                assertThat(area.readString(fillerOffset, 4))
                        .as("%s reserved FILLER X(4) at %d", row[0], fillerOffset)
                        .isEqualTo("    ");
            }

            assertThat(area.readString(TransactionAddRequest.ERRMSG_OFFSET, 78))
                    .isEqualTo("#".repeat(78));
            assertThat(area.toByteArray()).hasSize(575);
        }

        @Test
        @DisplayName("dropping either FILLER changes the total, which is what makes it load-bearing")
        void droppingAFillerBreaksTheTotal() {
            int prefix = TransactionAddRequest.TIOA_PREFIX_LENGTH;
            int metadata = TransactionAddRequest.PAYLOAD_FIELD_COUNT
                    * TransactionAddRequest.FIELD_METADATA_LENGTH;
            int payload = TransactionAddRequest.PAYLOAD_TOTAL_LENGTH;
            assertThat(prefix + metadata + payload).isEqualTo(575);

            assertThat(metadata + payload).isEqualTo(563);
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> TransactionAddRequest.verifyGeometry(21, 21, 416, 575, 563))
                    .withMessageContaining("component sum is 563");

            assertThat(prefix + (metadata - TransactionAddRequest.RESERVED_FILLER_LENGTH) + payload)
                    .isEqualTo(571);
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> TransactionAddRequest.verifyGeometry(21, 21, 416, 575, 571))
                    .withMessageContaining("component sum is 571");
        }

        @Test
        @DisplayName("xxxL is signed, so a cursor request reads back as -1 and never as 65535")
        void theLengthItemIsSignedAcrossEveryField() {
            TransactionAddRequest request = new TransactionAddRequest();
            for (String field : TransactionAddRequest.PAYLOAD_FIELD_NAMES) {
                ScreenFieldMetadata carrier = request.getMetadata(field);
                carrier.setLengthItem(TransactionAddRequest.CURSOR_REQUEST);
                assertThat(carrier.getLengthItem()).as("%sL", carrier.getScreenName())
                        .isEqualTo((short) -1);

                assertThat((int) carrier.getLengthItem()).isEqualTo(-1).isNotEqualTo(65_535);
                assertThat(Short.toUnsignedInt(carrier.getLengthItem())).isEqualTo(65_535);
                assertThat(carrier.isCursorRequested()).isTrue();
                assertThat(carrier.hasInput()).isFalse();
            }
        }
    }

    @Nested
    @DisplayName("BMS attributes - one enterable field against twenty output-only ones")
    class BmsFieldAttributes {
        private static final int TOTAL_FIELD_DEFINITIONS = 56;

        private static final int NAMED_FIELD_DEFINITIONS = 21;

        private static final List<String> INPUT_CAPABLE = List.of("TRNIDIN");

        @Test
        @DisplayName("21 of the 56 DFHMDF definitions are named, and the other 35 are literals")
        void theNamedFieldsAreTheSymbolicMapFields() {
            assertThat(NAMED_FIELD_DEFINITIONS)
                    .isEqualTo(TransactionAddRequest.PAYLOAD_FIELD_COUNT)
                    .isEqualTo(21);
            assertThat(TOTAL_FIELD_DEFINITIONS - NAMED_FIELD_DEFINITIONS)
                    .as("unnamed literal and label fields have no symbolic-map entry")
                    .isEqualTo(35);

            assertThat(TransactionAddRequest.PAYLOAD_FIELD_NAMES)
                    .hasSize(NAMED_FIELD_DEFINITIONS);
        }

        @Test
        @DisplayName("the input-capable set is exactly {TRNIDIN}; the other twenty are ASKIP")
        void exactlyOneFieldIsInputCapable() {
            assertThat(INPUT_CAPABLE).containsExactly("TRNIDIN").hasSize(1);

            List<String> outputOnly = new ArrayList<>();
            for (String field : TransactionAddRequest.PAYLOAD_FIELD_NAMES) {
                String screenName = field.substring(0, field.length() - 1);
                if (!INPUT_CAPABLE.contains(screenName)) {
                    outputOnly.add(screenName);
                }
            }
            assertThat(outputOnly).hasSize(20).doesNotContain("TRNIDIN");
            assertThat(outputOnly).contains("TRNID", "CARDNUM", "TRNAMT", "ERRMSG");
        }

        @Test
        @DisplayName("TRNIDIN's declared attributes are carried as metadata, not as payload")
        void theEnterableFieldCarriesItsAttributesAsMetadata() {
            assertThat(TransactionAddRequest.TRNIDIN_LENGTH).isEqualTo(16);

            TransactionAddRequest request = new TransactionAddRequest();
            ScreenFieldMetadata carrier = request.getMetadata("TRNIDINI");
            assertThat(carrier.getDeclaredLength()).isEqualTo(16);
            assertThat(carrier.getAttributeItem())
                    .as("no attribute is asserted until a program sets one")
                    .isEqualTo(ScreenFieldMetadata.UNSET_BYTE);

            request.requestCursorAt("TRNIDINI");
            assertThat(request.cursorField()).isEqualTo("TRNIDINI");

            carrier.setLengthItem((short) 16);
            assertThat(carrier.hasInput()).isTrue();
            assertThat(carrier.isCursorRequested()).isFalse();
        }

        @Test
        @DisplayName("ERRMSG is a 78-character bright red output field on line 23")
        void theErrorFieldIsOutputOnlyAndFullWidth() {
            assertThat(TransactionAddRequest.ERRMSG_LENGTH).isEqualTo(78);
            assertThat(TransactionAddRequest.ERRMSG_FIELD).isEqualTo("ERRMSGI");
            assertThat(INPUT_CAPABLE).doesNotContain("ERRMSG");

            TransactionAddRequest request = new TransactionAddRequest();
            request.setErrmsg("Transaction ID NOT found...");
            assertThat(request.toFieldImages(CODEC).get("ERRMSGI")).hasSize(78)
                    .startsWith("Transaction ID NOT found...");
        }
    }

    @Nested
    @DisplayName("The edit mask is a positional string contract, never a parsed number")
    class EditMaskPositions {
        private static final String MASK = "+99999999.99";

        @Test
        @DisplayName("the mask is 12 characters: sign, 8 digits, a point, 2 digits")
        void theMaskIsTwelveCharactersInFixedPositions() {
            assertThat(MASK).hasSize(12);
            assertThat(TransactionAddRequest.TRNAMT_LENGTH).isEqualTo(12);

            assertThat(MASK.charAt(0)).as("sign at position 1").isEqualTo('+');
            assertThat(MASK.substring(1, 9)).as("digits at positions 2 to 9").isEqualTo("99999999");
            assertThat(MASK.charAt(9)).as("decimal point at position 10").isEqualTo('.');
            assertThat(MASK.substring(10, 12)).as("cents at positions 11 and 12").isEqualTo("99");
        }

        @ParameterizedTest(name = "{0} keeps its sign, point and cents in place")
        @ValueSource(strings = {"+00000000.00", "+00000123.45", "-00000050.00", "+99999999.99",
                                "-99999999.99"})
        @DisplayName("a stored amount honours the mask positions, character by character")
        void aStoredAmountHonoursTheMaskPositions(String edited) {
            TransactionAddRequest request = new TransactionAddRequest();
            request.setTrnamt(edited);

            String image = request.toFieldImages(CODEC).get("TRNAMTI");
            assertThat(image).hasSize(12).isEqualTo(edited);
            assertThat(image.charAt(0)).isIn('+', '-');
            assertThat(image.charAt(9)).isEqualTo('.');
            assertThat(image.substring(1, 9)).containsOnlyDigits();
            assertThat(image.substring(10, 12)).containsOnlyDigits();
        }

        @Test
        @DisplayName("the DTO never parses it - a value that is not a number is stored verbatim")
        void theDtoNeverParsesTheAmount() {
            TransactionAddRequest request = new TransactionAddRequest();
            request.setTrnamt("NOT A NUMBER");
            assertThat(request.getTrnamt()).isEqualTo("NOT A NUMBER").hasSize(12);
            assertThat(request.toFieldImages(CODEC).get("TRNAMTI")).isEqualTo("NOT A NUMBER");

            TransactionAddRequest blank = new TransactionAddRequest();
            assertThat(blank.getTrnamt()).isEqualTo(TransactionAddRequest.spaces(12)).isBlank();
        }

        @Test
        @DisplayName("the 12-character screen width is not the record's 11-byte PIC S9(09)V99")
        void theScreenWidthIsNotTheRecordWidth() {
            assertThat(TransactionAddRequest.TRNAMT_LENGTH).isEqualTo(12).isNotEqualTo(9 + 2);
        }
    }

    @Nested
    @DisplayName("Cross-width MOVE from CVTRA05Y - truncation is always on the right")
    class CrossWidthMoves {
        private static final String TRAN_DESC =
                "PURCHASE AT ACME STORES SEATTLE WASHINGTON UNITED STATES OF AMERICA REFERENCE 0001";

        private static final String TRAN_MERCHANT_NAME = "ACME STORES INCORPORATED OF GREATER SEATTLE";

        private static final String TRAN_MERCHANT_CITY = "SEATTLE METROPOLITAN AREA WASHINGTON";

        private static final String TRAN_ORIG_TS = "2022-07-18-13.45.02.123456";
        private static final String TRAN_PROC_TS = "2022-07-19-01.02.03.987654";

        @Test
        @DisplayName("TRAN-DESC X(100) into TDESC X(60) keeps the first 60 characters")
        void descriptionIsTruncatedOnTheRight() {
            String source = CODEC.movePicX(TRAN_DESC, 100);
            assertThat(source).hasSize(100);

            String moved = CODEC.movePicX(source, TransactionAddRequest.TDESC_LENGTH);
            assertThat(moved).hasSize(60).isEqualTo(source.substring(0, 60));

            TransactionAddRequest request = new TransactionAddRequest();
            request.setTdesc(moved);
            assertThat(request.toFieldImages(CODEC).get("TDESCI")).isEqualTo(moved).hasSize(60);
        }

        @Test
        @DisplayName("MERCHANT-NAME X(50) into MNAME X(30) and CITY X(50) into MCITY X(25)")
        void merchantNameAndCityAreTruncatedOnTheRight() {
            String name = CODEC.movePicX(
                    CODEC.movePicX(TRAN_MERCHANT_NAME, 50), TransactionAddRequest.MNAME_LENGTH);
            assertThat(name).hasSize(30).isEqualTo(TRAN_MERCHANT_NAME.substring(0, 30));

            String city = CODEC.movePicX(
                    CODEC.movePicX(TRAN_MERCHANT_CITY, 50), TransactionAddRequest.MCITY_LENGTH);
            assertThat(city).hasSize(25).isEqualTo(TRAN_MERCHANT_CITY.substring(0, 25));

            TransactionAddRequest request = new TransactionAddRequest();
            request.setMname(name);
            request.setMcity(city);
            Map<String, String> images = request.toFieldImages(CODEC);
            assertThat(images.get("MNAMEI")).isEqualTo(name);
            assertThat(images.get("MCITYI")).isEqualTo(city);
        }

        @Test
        @DisplayName("the X(26) timestamps into X(10) keep the YYYY-MM-DD prefix")
        void timestampsKeepTheirDatePrefix() {
            String orig = CODEC.movePicX(TRAN_ORIG_TS, TransactionAddRequest.TORIGDT_LENGTH);
            String proc = CODEC.movePicX(TRAN_PROC_TS, TransactionAddRequest.TPROCDT_LENGTH);

            assertThat(orig).hasSize(10).isEqualTo("2022-07-18");
            assertThat(proc).hasSize(10).isEqualTo("2022-07-19");

            assertThat(orig).isNotEqualTo(TRAN_ORIG_TS.substring(TRAN_ORIG_TS.length() - 10));
        }

        @Test
        @DisplayName("the equal-width moves are exact: TRAN-ID, CARD-NUM, SOURCE, TYPE-CD and ZIP")
        void equalWidthMovesAreExact() {
            record Move(String label, String source, int target, String expected) { }
            List<Move> moves = List.of(
                    new Move("TRAN-ID -> TRNIDI", "0000000000000001",
                            TransactionAddRequest.TRNID_LENGTH, "0000000000000001"),
                    new Move("TRAN-ID -> TRNIDINI", "0000000000000001",
                            TransactionAddRequest.TRNIDIN_LENGTH, "0000000000000001"),
                    new Move("TRAN-CARD-NUM -> CARDNUMI", "4111111111111111",
                            TransactionAddRequest.CARDNUM_LENGTH, "4111111111111111"),
                    new Move("TRAN-SOURCE -> TRNSRCI", "POS TERM  ",
                            TransactionAddRequest.TRNSRC_LENGTH, "POS TERM  "),
                    new Move("TRAN-TYPE-CD -> TTYPCDI", "01",
                            TransactionAddRequest.TTYPCD_LENGTH, "01"),
                    new Move("TRAN-MERCHANT-ZIP -> MZIPI", "98101-0000",
                            TransactionAddRequest.MZIP_LENGTH, "98101-0000"));

            for (Move move : moves) {
                assertThat(CODEC.movePicX(move.source(), move.target()))
                        .as(move.label())
                        .hasSize(move.target())
                        .isEqualTo(move.expected());
            }
        }

        @Test
        @DisplayName("the numeric sources reach alphanumeric screen fields at their own widths")
        void numericSourcesBecomeAlphanumericScreenFields() {
            String categoryCode = CODEC.movePic9(1L, TransactionAddRequest.TCATCD_LENGTH);
            assertThat(categoryCode).hasSize(4).isEqualTo("0001");

            String merchantId = CODEC.movePic9(123456789L, TransactionAddRequest.MID_LENGTH);
            assertThat(merchantId).hasSize(9).isEqualTo("123456789");

            TransactionAddRequest request = new TransactionAddRequest();
            request.setTcatcd(categoryCode);
            request.setMid(merchantId);
            assertThat(request.getTcatcd()).isInstanceOf(String.class).isEqualTo("0001");
            assertThat(request.getMid()).isInstanceOf(String.class).isEqualTo("123456789");
        }

        @Test
        @DisplayName("a short value is right-space-padded to the declared width, never left short")
        void shortValuesAreRightSpacePadded() {
            TransactionAddRequest request = new TransactionAddRequest();
            request.setTrnname("CT");
            request.setTdesc("FEE");
            request.setMzip("98101");
            request.setErrmsg("");

            Map<String, String> images = request.toFieldImages(CODEC);
            assertThat(images.get("TRNNAMEI")).isEqualTo("CT  ").hasSize(4);
            assertThat(images.get("TDESCI")).startsWith("FEE ").hasSize(60);
            assertThat(images.get("MZIPI")).isEqualTo("98101     ").hasSize(10);
            assertThat(images.get("ERRMSGI")).hasSize(78).isBlank();

            for (Object[] row : OVERLAY) {
                String name = row[0] + "I";
                assertThat(images.get(name)).as(name).isNotNull().hasSize((Integer) row[3]);
            }
        }

        @Test
        @DisplayName("TRAN-AMT is never moved into TRNAMT directly - only the mask renders it")
        void theAmountIsNeverMovedDirectly() {
            assertThat(FixedWidthRecord.ZonedSign.overpunch(5, false)).isEqualTo('E');
            String zonedImage = "0000001234E";
            assertThat(zonedImage).hasSize(11).doesNotContain(".");

            String wrong = CODEC.movePicX(zonedImage, TransactionAddRequest.TRNAMT_LENGTH);
            assertThat(wrong).hasSize(12).doesNotContain(".").endsWith(" ");

            String edited = "+00000123.45";
            assertThat(edited).hasSize(12).isNotEqualTo(wrong);

            TransactionAddRequest request = new TransactionAddRequest();
            request.setTrnamt(edited);
            assertThat(request.getTrnamt()).isEqualTo(edited);
        }
    }

    @Nested
    @DisplayName("Type discipline - the negatives, asserted rather than assumed")
    class TypeDiscipline {
        private static final List<Class<?>> TYPES_UNDER_TEST = List.of(
                TransactionAddRequest.class, ScreenFieldMetadata.class, Ct01Info.class);

        private static List<Field> allDeclaredFields() {
            List<Field> fields = new ArrayList<>();
            for (Class<?> type : TYPES_UNDER_TEST) {
                for (Field field : type.getDeclaredFields()) {
                    if (!field.isSynthetic()) {
                        fields.add(field);
                    }
                }
            }
            return fields;
        }

        private static List<Method> allDeclaredMethods() {
            List<Method> methods = new ArrayList<>();
            for (Class<?> type : TYPES_UNDER_TEST) {
                for (Method method : type.getDeclaredMethods()) {
                    if (!method.isSynthetic() && !method.isBridge()) {
                        methods.add(method);
                    }
                }
            }
            return methods;
        }

        private static List<Field> instanceFieldsOf(Class<?> type) {
            List<Field> fields = new ArrayList<>();
            for (Field field : type.getDeclaredFields()) {
                if (!field.isSynthetic() && !Modifier.isStatic(field.getModifiers())) {
                    fields.add(field);
                }
            }
            return fields;
        }

        @Test
        @DisplayName("all 21 payload members are String, and each getter returns String")
        void everyPayloadMemberIsAString() {
            Set<String> stringFieldNames = new LinkedHashSet<>();
            for (Field field : instanceFieldsOf(TransactionAddRequest.class)) {
                if (field.getType() == String.class) {
                    stringFieldNames.add(field.getName());
                }
            }
            assertThat(stringFieldNames).hasSize(22).contains("trnidin", "trnid", "trnamt", "errmsg");

            for (Method method : allDeclaredMethods()) {
                boolean payloadGetter = method.getName().startsWith("get")
                        && method.getParameterCount() == 0
                        && stringFieldNames.contains(decapitalise(method.getName().substring(3)));
                if (payloadGetter) {
                    assertThat(method.getReturnType())
                            .as("%s must return String", method.getName())
                            .isEqualTo(String.class);
                }
            }
        }

        @Test
        @DisplayName("no double, float or BigDecimal appears anywhere - not a field, not a signature")
        void noBinaryFloatingPointOrDecimalTypeIsUsed() {
            Set<Class<?>> forbidden = Set.of(double.class, float.class, Double.class, Float.class,
                    BigDecimal.class, BigInteger.class);

            for (Field field : allDeclaredFields()) {
                assertThat(forbidden)
                        .as("%s.%s is declared %s", field.getDeclaringClass().getSimpleName(),
                                field.getName(), field.getType().getSimpleName())
                        .doesNotContain(field.getType());
            }
            for (Method method : allDeclaredMethods()) {
                assertThat(forbidden)
                        .as("%s returns %s", method.getName(),
                                method.getReturnType().getSimpleName())
                        .doesNotContain(method.getReturnType());
                for (Class<?> parameter : method.getParameterTypes()) {
                    assertThat(forbidden)
                            .as("%s takes a %s", method.getName(), parameter.getSimpleName())
                            .doesNotContain(parameter);
                }
            }
        }

        @Test
        @DisplayName("the only non-String member is the scale-free PIC 9(08) page number, an int")
        void theOnlyNumericMemberIsTheScaleFreePageNumber() {
            List<String> nonStringInstanceFields = new ArrayList<>();
            for (Class<?> type : TYPES_UNDER_TEST) {
                for (Field field : instanceFieldsOf(type)) {
                    if (field.getType() != String.class) {
                        nonStringInstanceFields.add(
                                type.getSimpleName() + "." + field.getName() + ":"
                                        + field.getType().getSimpleName());
                    }
                }
            }
            assertThat(nonStringInstanceFields).containsExactlyInAnyOrder(
                    "TransactionAddRequest.navigationContext:NavigationContext",
                    "TransactionAddRequest.ct01Info:Ct01Info",
                    "TransactionAddRequest.metadata:Map",
                    "ScreenFieldMetadata.declaredLength:int",
                    "ScreenFieldMetadata.lengthItem:short",
                    "Ct01Info.pageNum:int");
            assertThat(Ct01Info.PAGE_NUM_LENGTH).isEqualTo(8);
            assertThat(Ct01Info.PAGE_NUM_MAX).isEqualTo(99_999_999);
        }

        @Test
        @DisplayName("no rounding mode is reachable - COBOL truncates, and ROUNDED never appears")
        void noRoundingModeIsReachable() {
            for (Field field : allDeclaredFields()) {
                assertThat(field.getType().getName()).doesNotContain("RoundingMode");
            }
            for (Method method : allDeclaredMethods()) {
                assertThat(method.getReturnType().getName()).doesNotContain("RoundingMode");
                for (Class<?> parameter : method.getParameterTypes()) {
                    assertThat(parameter.getName()).doesNotContain("RoundingMode");
                }
            }
        }

        @Test
        @DisplayName("no static mutable state: every static field is final")
        void noStaticMutableStateExists() {
            for (Field field : allDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("static field %s.%s must be final",
                                    field.getDeclaringClass().getSimpleName(), field.getName())
                            .isTrue();
                }
            }
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> TransactionAddRequest.PAYLOAD_FIELD_NAMES.add("X"));
        }

        @Test
        @DisplayName("no server-side session type is referenced - the conversation is in the payload")
        void noServerSideSessionTypeIsReferenced() {
            List<String> forbidden = List.of("HttpSession", "HttpServletRequest", "ThreadLocal",
                    "SessionAttribute", "RequestContextHolder", "ScopedValue");
            for (Field field : allDeclaredFields()) {
                for (String name : forbidden) {
                    assertThat(field.getType().getName())
                            .as("%s.%s", field.getDeclaringClass().getSimpleName(), field.getName())
                            .doesNotContain(name);
                }
            }
            for (Method method : allDeclaredMethods()) {
                for (String name : forbidden) {
                    assertThat(method.getReturnType().getName()).doesNotContain(name);
                    for (Class<?> parameter : method.getParameterTypes()) {
                        assertThat(parameter.getName()).doesNotContain(name);
                    }
                }
            }
        }

        @Test
        @DisplayName("no persistence annotation anywhere - no schema is implied by this payload")
        void noPersistenceAnnotationIsPresent() {
            List<String> forbidden = List.of("Entity", "Table", "Column", "Id", "Version",
                    "GeneratedValue", "Embeddable", "MappedSuperclass");

            for (Class<?> type : TYPES_UNDER_TEST) {
                assertAnnotationsAreClean(type.getAnnotations(), type.getSimpleName(), forbidden);
            }
            for (Field field : allDeclaredFields()) {
                assertAnnotationsAreClean(field.getAnnotations(),
                        field.getDeclaringClass().getSimpleName() + "." + field.getName(), forbidden);
            }
            for (Method method : allDeclaredMethods()) {
                assertAnnotationsAreClean(method.getAnnotations(),
                        method.getDeclaringClass().getSimpleName() + "#" + method.getName(),
                        forbidden);
            }
        }

        private static void assertAnnotationsAreClean(Annotation[] annotations,
                                                      String subject,
                                                      List<String> forbidden) {
            for (Annotation annotation : annotations) {
                String simpleName = annotation.annotationType().getSimpleName();
                assertThat(forbidden).as("%s carries @%s", subject, simpleName)
                        .doesNotContain(simpleName);
            }
        }

        @Test
        @DisplayName("COTRN1AI declares no OCCURS table, so row indexing does not apply here")
        void noOccursTableExistsOnThisMap() {
            assertThat(TransactionAddRequest.PAYLOAD_FIELD_NAMES).doesNotHaveDuplicates();
            assertThat(TransactionAddRequest.PAYLOAD_FIELD_NAMES)
                    .doesNotContain("SEL0001I", "TRNID01I", "TDATE01I", "TDESC01I", "TAMT001I");

            for (Method method : allDeclaredMethods()) {
                boolean indexedRead = !Modifier.isStatic(method.getModifiers())
                        && method.getReturnType() == String.class
                        && method.getParameterCount() == 1
                        && (method.getParameterTypes()[0] == int.class
                            || method.getParameterTypes()[0] == short.class
                            || method.getParameterTypes()[0] == long.class);
                assertThat(indexedRead)
                        .as("%s must not read a value out by ordinal position", method.getName())
                        .isFalse();
            }
            for (Class<?> type : TYPES_UNDER_TEST) {
                for (Field field : instanceFieldsOf(type)) {
                    assertThat(field.getType().isArray())
                            .as("%s.%s must not be an array", type.getSimpleName(), field.getName())
                            .isFalse();
                    assertThat(List.class.isAssignableFrom(field.getType()))
                            .as("%s.%s must not be a List", type.getSimpleName(), field.getName())
                            .isFalse();
                }
            }
            assertThat(TransactionAddRequest.PAYLOAD_FIELD_COUNT).isEqualTo(21);
        }

        @Test
        @DisplayName("this test declares no wildcard import and reaches no harness or datasource")
        void theTestItselfHoldsToItsOwnBoundaries() {
            assertThat(TransactionAddRequestTest.class.getPackageName())
                    .isEqualTo("com.vsergeychik.carddemo.transaction.dto");
            assertThat(TransactionAddRequestTest.class.getAnnotations())
                    .noneMatch(annotation -> {
                        String name = annotation.annotationType().getSimpleName();
                        return name.contains("SpringBootTest") || name.contains("WebMvcTest")
                                || name.contains("DataJdbcTest") || name.contains("ExtendWith");
                    });

            assertThat(CODEC.charset()).isEqualTo(StandardCharsets.US_ASCII);
        }

        private static String decapitalise(String name) {
            return name.isEmpty()
                    ? name
                    : Character.toLowerCase(name.charAt(0)) + name.substring(1);
        }
    }
}
