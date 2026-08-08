package com.vsergeychik.carddemo.transaction.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.transaction.dto.TransactionAddRequest.Ct01Info;
import com.vsergeychik.carddemo.transaction.dto.TransactionAddRequest.ScreenFieldMetadata;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Verifies {@link TransactionAddRequest} against its three authoritative sources:
 * {@code app/cpy-bms/COTRN01.CPY} (the {@code xxxI} items and their {@code PICTURE} widths),
 * {@code app/bms/COTRN01.bms} (the name-labelled {@code DFHMDF} definitions and their
 * {@code LENGTH=}), and {@code app/cbl/COTRN01C.cbl} (the behaviour, the commarea extension and the
 * edited amount mask).
 *
 * <p>The expected values below are written out as literals on purpose rather than read back from the
 * class under test. A test that derives its expectations from the code it is testing proves only
 * self-consistency; these literals were transcribed from the copybook and the mapset, so they fail if
 * the class drifts from the source.
 */
@DisplayName("TransactionAddRequest - CT01 / COTRN01C / mapset COTRN01, map COTRN1A")
class TransactionAddRequestTest {

    /** Explicit code page, never the platform default. */
    private static final FixedWidthCodec CODEC = new FixedWidthCodec(StandardCharsets.US_ASCII);

    /**
     * The twenty-one {@code xxxI} items in copybook declaration order with their declared widths,
     * transcribed from {@code app/cpy-bms/COTRN01.CPY} lines 24 to 144.
     */
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

    // =================================================================================================

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

            // Absence is the honest default: nothing has been passed to a request nobody has filled
            // in yet, and that is exactly the EIBCALEN = 0 state COTRN01C.cbl:94 tests for. Defaulting
            // to an initialised area would make that state unreachable through the API, because every
            // request would then carry one whether or not it was passed.
            assertThat(request.getNavigationContext()).isNull();
            assertThat(request.hasNavigationContext()).isFalse();
            assertThat(request.commareaLength()).isZero();
            assertThat(request.isEnter()).isFalse();
            assertThat(request.isReenter()).isFalse();

            // The extension is a different case and keeps its fresh default - see
            // aNullExtensionIsNormalised.
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

            // A request that carries an area renders one whatever else is set on it...
            TransactionAddRequest fresh = new TransactionAddRequest();
            fresh.setNavigationContext(NavigationContext.empty());
            assertThat(fresh.toCommareaImage(CODEC)).hasSize(218);

            // ...and a request that carries none renders nothing, rather than 218 invented bytes.
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

            // COTRN01C.cbl:94-96 acts on the absence itself - IF EIBCALEN = 0 transfers to COSGN00C
            // without ever reading a context byte - so substituting an initialised area would send
            // the request down the ELSE branch instead. The absence has to survive.
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

            // Unlike the commarea, the extension has no absence semantics of its own: COTRN01C reads
            // it only on the branch where a commarea was passed, so a fresh one is the honest default.
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

            // Three states, not two. Printing a cold start as pgmContext=0 would hide exactly the
            // distinction this payload was corrected to preserve.
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
        @DisplayName("the EIBAID attention identifier is one byte and is carried in the payload")
        void attentionIdentifierIsCarried() {
            assertThat(TransactionAddRequest.AID_LENGTH).isEqualTo(1);
            TransactionAddRequest request = new TransactionAddRequest();
            request.setAid("\u0027");
            assertThat(request.getAid()).isEqualTo("\u0027");
        }
    }

    @Nested
    @DisplayName("Bean Validation is never stricter than the COBOL")
    class Validation {

        private Set<ConstraintViolation<TransactionAddRequest>> validate(
                TransactionAddRequest request) {
            try (ValidatorFactory factory = jakarta.validation.Validation
                    .buildDefaultValidatorFactory()) {
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
}
