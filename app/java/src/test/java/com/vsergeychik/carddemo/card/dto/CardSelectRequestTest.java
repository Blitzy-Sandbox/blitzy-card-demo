package com.vsergeychik.carddemo.card.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.card.dto.CardSelectRequest.ScreenField;
import com.vsergeychik.carddemo.card.dto.CardSelectRequest.ScreenFieldMetadata;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Parity tests for {@link CardSelectRequest}, the inbound projection of the {@code CCRDSLAI} input
 * group of {@code app/cpy-bms/COCRDSL.CPY} and of the fifteen name-labelled {@code DFHMDF} entries of
 * {@code app/bms/COCRDSL.bms}.
 *
 * <p>Every expected value below is transcribed from those two files and from
 * {@code app/cbl/COCRDSLC.cbl}, never read back out of the class under test, so the sources stay the
 * authority: a width, a line number or an offset that drifted in either place fails here.
 *
 * <p>The suite is organised around the properties that determine the implementation, so a failure names
 * a translation decision rather than merely a value:
 *
 * <ol>
 *   <li>There are <strong>fifteen</strong> fields, from 15 name-labelled {@code DFHMDF} entries of 31,
 *       and the two independent width columns - {@code LENGTH=} operands and {@code PICTURE} clauses -
 *       agree at <strong>387</strong>.</li>
 *   <li>The group is <strong>504</strong> bytes: 12 + 15 &times; 7 + 387, and the field strides abut
 *       with no gap and no overlap.</li>
 *   <li>{@code xxxL} and {@code xxxA} are metadata and never reach the JSON wire, while the fifteen
 *       {@code xxxI} values and the two conversation-state carriers do.</li>
 *   <li>The {@code PIC X} move rule - pad right, truncate right - is applied only through
 *       {@link FixedWidthCodec}, so a setter never resizes and a getter never trims.</li>
 *   <li>{@code EXPDAY} and {@code PAGENO} are absent, and the widths of {@code INFOMSG},
 *       {@code ERRMSG} and {@code FKEYS} are the ones {@code COCRDSL} declares rather than a value
 *       shared with the sibling card maps.</li>
 * </ol>
 */
@DisplayName("CardSelectRequest - COCRDSL CCRDSLAI, the CCDL inbound payload")
class CardSelectRequestTest {

    /**
     * The card number, account identifier and embossed name used throughout this suite are the first
     * record of {@code app/data/ASCII/carddata.txt} and its {@code app/data/ASCII/cardxref.txt} mate -
     * card {@code 0500024453765740}, account {@code 00000000050}, name {@code Aniya Von}. Seeding from
     * the shipped fixtures rather than from invented digits keeps the inputs production-shaped and
     * keeps this suite consistent with the data the parity harness uses.
     */
    private static final String FIXTURE_CARD_NUMBER = "0500024453765740";

    /** The code page of the ASCII fixtures under {@code app/data/ASCII}, named explicitly. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** The code page of the datasets under {@code app/data/EBCDIC}, named explicitly. */
    private static final Charset EBCDIC = Charset.forName("IBM037");

    private static final FixedWidthCodec ASCII_CODEC = new FixedWidthCodec(ASCII);

    private static final FixedWidthCodec EBCDIC_CODEC = new FixedWidthCodec(EBCDIC);

    /**
     * The fifteen {@code DFHMDF} labels, in the order {@code app/bms/COCRDSL.bms} declares them.
     * Transcribed from the mapset, so the enumeration is compared against the source and not against
     * itself.
     */
    private static final String[] LABELS = {
        "TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME", "ACCTSID", "CARDSID",
        "CRDNAME", "CRDSTCD", "EXPMON", "EXPYEAR", "INFOMSG", "ERRMSG", "FKEYS"
    };

    /** The fifteen {@code LENGTH=} operands, which are also the fifteen {@code PICTURE} widths. */
    private static final int[] WIDTHS = {4, 40, 8, 8, 40, 8, 11, 16, 50, 1, 2, 4, 40, 80, 75};

    /** The lines of {@code app/cpy-bms/COCRDSL.CPY} declaring each {@code xxxI} item. */
    private static final int[] CPY_LINES = {24, 30, 36, 42, 48, 54, 60, 66, 72, 78, 84, 90, 96, 102,
        108};

    /** The lines of {@code app/bms/COCRDSL.bms} opening each {@code DFHMDF} entry. */
    private static final int[] BMS_LINES = {34, 38, 47, 57, 61, 70, 84, 96, 107, 116, 126, 133, 139,
        144, 148};

    /** The {@code POS=} operands of the fifteen entries, row then column. */
    private static final int[][] POSITIONS = {{1, 7}, {1, 21}, {1, 71}, {2, 7}, {2, 21}, {2, 71},
        {7, 45}, {8, 45}, {11, 25}, {13, 25}, {15, 25}, {15, 30}, {20, 25}, {23, 1}, {24, 1}};

    /** Where each field's {@code xxxI} data begins inside the 504-byte group. */
    private static final int[] DATA_OFFSETS = {19, 30, 77, 92, 107, 154, 169, 187, 210, 267, 275, 284,
        295, 342, 429};

    /**
     * The fifteen named setters, in copybook order, so that a test can drive each one through its own
     * method rather than only through {@link CardSelectRequest#setValue}. That distinction matters:
     * {@code setValue} delegating correctly is itself a property under test.
     */
    private static List<BiConsumer<CardSelectRequest, String>> namedSetters() {
        List<BiConsumer<CardSelectRequest, String>> setters = new ArrayList<>();
        setters.add(CardSelectRequest::setTrnname);
        setters.add(CardSelectRequest::setTitle01);
        setters.add(CardSelectRequest::setCurdate);
        setters.add(CardSelectRequest::setPgmname);
        setters.add(CardSelectRequest::setTitle02);
        setters.add(CardSelectRequest::setCurtime);
        setters.add(CardSelectRequest::setAcctsid);
        setters.add(CardSelectRequest::setCardsid);
        setters.add(CardSelectRequest::setCrdname);
        setters.add(CardSelectRequest::setCrdstcd);
        setters.add(CardSelectRequest::setExpmon);
        setters.add(CardSelectRequest::setExpyear);
        setters.add(CardSelectRequest::setInfomsg);
        setters.add(CardSelectRequest::setErrmsg);
        setters.add(CardSelectRequest::setFkeys);
        return setters;
    }

    /** The fifteen named getters, in copybook order. */
    private static List<java.util.function.Function<CardSelectRequest, String>> namedGetters() {
        List<java.util.function.Function<CardSelectRequest, String>> getters = new ArrayList<>();
        getters.add(CardSelectRequest::getTrnname);
        getters.add(CardSelectRequest::getTitle01);
        getters.add(CardSelectRequest::getCurdate);
        getters.add(CardSelectRequest::getPgmname);
        getters.add(CardSelectRequest::getTitle02);
        getters.add(CardSelectRequest::getCurtime);
        getters.add(CardSelectRequest::getAcctsid);
        getters.add(CardSelectRequest::getCardsid);
        getters.add(CardSelectRequest::getCrdname);
        getters.add(CardSelectRequest::getCrdstcd);
        getters.add(CardSelectRequest::getExpmon);
        getters.add(CardSelectRequest::getExpyear);
        getters.add(CardSelectRequest::getInfomsg);
        getters.add(CardSelectRequest::getErrmsg);
        getters.add(CardSelectRequest::getFkeys);
        return getters;
    }

    // =================================================================================================

    @Nested
    @DisplayName("The width contract - fifteen fields summing to 387 in a 504-byte group")
    class WidthContract {

        @Test
        @DisplayName("the fifteen width constants are the fifteen xxxI PICTURE widths")
        void widthConstantsMatchThePictureClauses() {
            assertThat(CardSelectRequest.TRNNAME_LENGTH).isEqualTo(4);
            assertThat(CardSelectRequest.TITLE01_LENGTH).isEqualTo(40);
            assertThat(CardSelectRequest.CURDATE_LENGTH).isEqualTo(8);
            assertThat(CardSelectRequest.PGMNAME_LENGTH).isEqualTo(8);
            assertThat(CardSelectRequest.TITLE02_LENGTH).isEqualTo(40);
            assertThat(CardSelectRequest.CURTIME_LENGTH).isEqualTo(8);
            assertThat(CardSelectRequest.ACCTSID_LENGTH).isEqualTo(11);
            assertThat(CardSelectRequest.CARDSID_LENGTH).isEqualTo(16);
            assertThat(CardSelectRequest.CRDNAME_LENGTH).isEqualTo(50);
            assertThat(CardSelectRequest.CRDSTCD_LENGTH).isEqualTo(1);
            assertThat(CardSelectRequest.EXPMON_LENGTH).isEqualTo(2);
            assertThat(CardSelectRequest.EXPYEAR_LENGTH).isEqualTo(4);
            assertThat(CardSelectRequest.INFOMSG_LENGTH).isEqualTo(40);
            assertThat(CardSelectRequest.ERRMSG_LENGTH).isEqualTo(80);
            assertThat(CardSelectRequest.FKEYS_LENGTH).isEqualTo(75);
        }

        @Test
        @DisplayName("each field costs 2 + 1 + 4 = 7 bytes before its data")
        void perFieldOverheadIsSeven() {
            assertThat(CardSelectRequest.LENGTH_ITEM_LENGTH).isEqualTo(2);
            assertThat(CardSelectRequest.FLAG_ITEM_LENGTH).isEqualTo(1);
            assertThat(CardSelectRequest.EXTENDED_ATTRIBUTE_ITEM_LENGTH).isEqualTo(4);
            assertThat(CardSelectRequest.FIELD_OVERHEAD).isEqualTo(7);
        }

        @Test
        @DisplayName("4+40+8+8+40+8+11+16+50+1+2+4+40+80+75 = 387")
        void theFifteenWidthsSumTo387() {
            int sum = 0;
            for (int width : WIDTHS) {
                sum += width;
            }
            assertThat(sum).isEqualTo(387);
            assertThat(CardSelectRequest.PAYLOAD_LENGTH).isEqualTo(387);
        }

        @Test
        @DisplayName("12 + 15 * 7 + 387 = 504")
        void theGroupIs504Bytes() {
            assertThat(CardSelectRequest.TIOAPFX_LENGTH).isEqualTo(12);
            assertThat(CardSelectRequest.FIELD_COUNT).isEqualTo(15);
            assertThat(CardSelectRequest.TIOAPFX_LENGTH
                    + CardSelectRequest.FIELD_COUNT * CardSelectRequest.FIELD_OVERHEAD
                    + CardSelectRequest.PAYLOAD_LENGTH).isEqualTo(504);
            assertThat(CardSelectRequest.GROUP_LENGTH).isEqualTo(504);
        }

        @Test
        @DisplayName("the three message widths are COCRDSL's own, not shared with COCRDLI or COCRDUP")
        void messageWidthsAreNotUnifiedAcrossTheCardMaps() {
            // COCRDLI declares INFOMSG X(45) and ERRMSG X(78); COCRDUP declares FKEYS X(21) and
            // COCRDLI declares none at all. Collapsing any of these would break the contract on the
            // sibling screens.
            assertThat(CardSelectRequest.INFOMSG_LENGTH).isEqualTo(40).isNotEqualTo(45);
            assertThat(CardSelectRequest.ERRMSG_LENGTH).isEqualTo(80).isNotEqualTo(78);
            assertThat(CardSelectRequest.FKEYS_LENGTH).isEqualTo(75).isNotEqualTo(21);
        }
    }

    @Nested
    @DisplayName("ScreenField - the fifteen name-labelled DFHMDF entries of thirty-one")
    class Fields {

        @Test
        @DisplayName("fifteen constants, in mapset declaration order, each with its full provenance")
        void theFifteenConstantsCarryTheirProvenance() {
            ScreenField[] fields = ScreenField.values();
            assertThat(fields).hasSize(15);
            for (int index = 0; index < fields.length; index++) {
                ScreenField field = fields[index];
                assertThat(field.label()).isEqualTo(LABELS[index]);
                assertThat(field.symbolicItemName()).isEqualTo(LABELS[index] + "I");
                assertThat(field.length()).isEqualTo(WIDTHS[index]);
                assertThat(field.copybookLine()).isEqualTo(CPY_LINES[index]);
                assertThat(field.mapsetLine()).isEqualTo(BMS_LINES[index]);
                assertThat(field.screenRow()).isEqualTo(POSITIONS[index][0]);
                assertThat(field.screenColumn()).isEqualTo(POSITIONS[index][1]);
                assertThat(field.dataOffset()).isEqualTo(DATA_OFFSETS[index]);
            }
        }

        @Test
        @DisplayName("the strides abut, leaving no gap and no overlap, and end exactly on 504")
        void stridesAbutAndEndOn504() {
            int cursor = CardSelectRequest.TIOAPFX_LENGTH;
            for (ScreenField field : ScreenField.values()) {
                assertThat(field.lengthItemOffset()).as(field.label() + " xxxL").isEqualTo(cursor);
                assertThat(field.flagItemOffset()).as(field.label() + " xxxF").isEqualTo(cursor + 2);
                assertThat(field.extendedAttributeItemOffset()).as(field.label() + " FILLER X(4)")
                        .isEqualTo(cursor + 3);
                assertThat(field.dataOffset()).as(field.label() + " xxxI").isEqualTo(cursor + 7);
                assertThat(field.endOffsetExclusive())
                        .isEqualTo(field.dataOffset() + field.length());
                cursor = field.endOffsetExclusive();
            }
            assertThat(cursor).isEqualTo(CardSelectRequest.GROUP_LENGTH);
        }

        @Test
        @DisplayName("every field sits inside the SIZE=(24,80) screen")
        void everyFieldSitsOnA24By80Screen() {
            for (ScreenField field : ScreenField.values()) {
                assertThat(field.screenRow()).as(field.label()).isBetween(1, 24);
                assertThat(field.screenColumn()).as(field.label()).isBetween(1, 80);
            }
        }

        @Test
        @DisplayName("describe names the label, item, PICTURE, both source lines, POS and offsets")
        void describeNamesTheWholeProvenance() {
            assertThat(ScreenField.CARDSID.describe())
                    .isEqualTo("CARDSID CARDSIDI PIC X(16) COCRDSL.CPY:66 COCRDSL.bms:96 POS=(8,45) "
                            + "offset 187..203");
            assertThat(ScreenField.FKEYS.describe()).contains("offset 429..504");
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("byLabel resolves every one of the fifteen labels")
        void byLabelResolvesEveryField(ScreenField field) {
            assertThat(ScreenField.byLabel(field.label())).isSameAs(field);
        }

        @ParameterizedTest
        @ValueSource(strings = {"EXPDAY", "PAGENO", "cardsid", "", "CARDSID ", "CRDSTCDI"})
        @DisplayName("byLabel rejects what COCRDSL does not declare, EXPDAY and PAGENO included")
        void byLabelRejectsWhatCocrdslDoesNotDeclare(String label) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> ScreenField.byLabel(label))
                    .withMessageContaining("no name-labelled DFHMDF");
        }

        @Test
        @DisplayName("byLabel rejects a null label rather than matching something")
        void byLabelRejectsNull() {
            assertThatNullPointerException().isThrownBy(() -> ScreenField.byLabel(null));
        }
    }

    @Nested
    @DisplayName("Construction - a freshly initialised map area, with no null anywhere")
    class Construction {

        @Test
        @DisplayName("every field is spaces at its declared width and both carriers are present")
        void freshRequestIsAnInitialisedMapArea() {
            CardSelectRequest request = new CardSelectRequest();
            for (ScreenField field : ScreenField.values()) {
                assertThat(request.value(field)).as(field.label())
                        .hasSize(field.length())
                        .isBlank();
            }
            assertThat(request.getCardScreenState()).isNotNull();
            assertThat(request.getNavigationContext()).isEqualTo(NavigationContext.empty());
            assertThat(request.metadata()).hasSize(15);
        }

        @Test
        @DisplayName("every metadata holder starts unset, with no cursor anywhere")
        void everyMetadataHolderStartsUnset() {
            CardSelectRequest request = new CardSelectRequest();
            for (ScreenField field : ScreenField.values()) {
                ScreenFieldMetadata holder = request.metadata(field);
                assertThat(holder.isLengthUnset()).as(field.label()).isTrue();
                assertThat(holder.isAttributeUnset()).as(field.label()).isTrue();
                assertThat(holder.isCursorHere()).as(field.label()).isFalse();
                assertThat(holder.getLength()).isEqualTo(ScreenFieldMetadata.LENGTH_UNSET);
                assertThat(holder.getAttribute()).isEqualTo(ScreenFieldMetadata.ATTRIBUTE_UNSET);
            }
        }

        @Test
        @DisplayName("withSearchCriteria sets only the two fields COCRDSLC reads")
        void withSearchCriteriaSetsOnlyTheTwoTypedFields() {
            CardSelectRequest request = CardSelectRequest.withSearchCriteria("00000000050", "*");
            assertThat(request.getAcctsid()).isEqualTo("00000000050");
            assertThat(request.getCardsid()).isEqualTo("*");
            assertThat(request.getTrnname()).isBlank();
            assertThat(request.getCrdname()).isBlank();
            assertThat(request.getFkeys()).isBlank();
        }

        @Test
        @DisplayName("withSearchCriteria takes null as spaces at each declared width")
        void withSearchCriteriaTakesNullAsSpaces() {
            CardSelectRequest request = CardSelectRequest.withSearchCriteria(null, null);
            assertThat(request.getAcctsid()).isEqualTo(" ".repeat(11));
            assertThat(request.getCardsid()).isEqualTo(" ".repeat(16));
        }

        @Test
        @DisplayName("the copy constructor copies each metadata holder rather than sharing it")
        void copyConstructorDeepCopiesTheMutableParts() {
            CardSelectRequest original = new CardSelectRequest();
            original.setCardsid(FIXTURE_CARD_NUMBER);
            original.metadata(ScreenField.CARDSID).positionCursorHere();
            original.metadata(ScreenField.ACCTSID).setAttribute((byte) 0x61);
            original.getCardScreenState().setCcAcctId("00000000050");
            original.setNavigationContext(NavigationContext.empty().withPgmReenter());

            CardSelectRequest copy = new CardSelectRequest(original);
            assertThat(copy).isEqualTo(original).isNotSameAs(original);
            assertThat(copy.metadata(ScreenField.CARDSID))
                    .isNotSameAs(original.metadata(ScreenField.CARDSID));
            assertThat(copy.getCardScreenState()).isNotSameAs(original.getCardScreenState());

            copy.metadata(ScreenField.CARDSID).setLength(7);
            assertThat(original.metadata(ScreenField.CARDSID).isCursorHere()).isTrue();
            assertThat(copy).isNotEqualTo(original);
        }

        @Test
        @DisplayName("the copy constructor rejects null rather than producing a half-built request")
        void copyConstructorRejectsNull() {
            assertThatNullPointerException().isThrownBy(() -> new CardSelectRequest(null));
        }

        @Test
        @DisplayName("initializeMapArea returns a used request to its constructed state")
        void initializeMapAreaResetsEverything() {
            CardSelectRequest request = new CardSelectRequest();
            request.setCardsid(FIXTURE_CARD_NUMBER);
            request.setErrmsg("Did not find this Card in Cards Database");
            request.metadata(ScreenField.ACCTSID).positionCursorHere();
            request.metadata(ScreenField.CARDSID).setAttribute((byte) 0xC1);
            request.setNavigationContext(NavigationContext.empty().withPgmReenter());
            request.initializeMapArea();
            assertThat(request).isEqualTo(new CardSelectRequest());
        }

        @Test
        @DisplayName("spaces is SPACES repeated, and refuses a negative width")
        void spacesIsTheFigurativeConstantRepeated() {
            assertThat(CardSelectRequest.spaces(0)).isEmpty();
            assertThat(CardSelectRequest.spaces(1)).isEqualTo(" ");
            assertThat(CardSelectRequest.spaces(75)).hasSize(75).isBlank();
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardSelectRequest.spaces(-1))
                    .withMessageContaining("PIC X(n)");
        }
    }

    @Nested
    @DisplayName("Accessors - stored verbatim, returned untrimmed")
    class Accessors {

        @Test
        @DisplayName("a setter neither pads nor truncates: it is not a COBOL MOVE")
        void settersStoreVerbatim() {
            CardSelectRequest request = new CardSelectRequest();
            request.setCardsid("41");
            assertThat(request.getCardsid()).isEqualTo("41");
            request.setCardsid("05000244537657409999");
            assertThat(request.getCardsid()).isEqualTo("05000244537657409999");
            request.setCrdstcd("YES");
            assertThat(request.getCrdstcd()).isEqualTo("YES");
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("every named setter takes null as spaces at that field's declared width")
        void everyNamedSetterTakesNullAsSpaces(ScreenField field) {
            int index = field.ordinal();
            CardSelectRequest request = new CardSelectRequest();
            namedSetters().get(index).accept(request, "x");
            assertThat(namedGetters().get(index).apply(request)).isEqualTo("x");
            namedSetters().get(index).accept(request, null);
            assertThat(namedGetters().get(index).apply(request))
                    .isEqualTo(" ".repeat(field.length()));
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("setValue and value agree with the named accessors, field for field")
        void setValueAgreesWithTheNamedSetters(ScreenField field) {
            CardSelectRequest viaEnum = new CardSelectRequest();
            viaEnum.setValue(field, "Z");
            assertThat(viaEnum.value(field)).isEqualTo("Z");

            CardSelectRequest viaSetter = new CardSelectRequest();
            namedSetters().get(field.ordinal()).accept(viaSetter, "Z");
            assertThat(viaSetter).isEqualTo(viaEnum);
            assertThat(viaSetter).hasSameHashCodeAs(viaEnum);
            assertThat(viaEnum.value(field))
                    .isEqualTo(namedGetters().get(field.ordinal()).apply(viaSetter));
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("setValue takes null as spaces, whichever field is addressed")
        void setValueTakesNullAsSpaces(ScreenField field) {
            CardSelectRequest request = new CardSelectRequest();
            request.setValue(field, "x");
            request.setValue(field, null);
            assertThat(request.value(field)).isEqualTo(" ".repeat(field.length()));
        }

        @Test
        @DisplayName("value and setValue reject a null field rather than guessing one")
        void valueAndSetValueRejectANullField() {
            CardSelectRequest request = new CardSelectRequest();
            assertThatNullPointerException().isThrownBy(() -> request.value(null));
            assertThatNullPointerException().isThrownBy(() -> request.setValue(null, "x"));
        }

        @Test
        @DisplayName("nothing is masked: the sixteen-digit card number is returned as stored")
        void theCardNumberIsNotMasked() {
            CardSelectRequest request = new CardSelectRequest();
            request.setCardsid(FIXTURE_CARD_NUMBER);
            request.setAcctsid("00000000050");
            assertThat(request.getCardsid()).isEqualTo("0500024453765740");
            assertThat(request.getAcctsid()).isEqualTo("00000000050");
            assertThat(request.toString()).contains("0500024453765740").contains("00000000050");
        }
    }

    @Nested
    @DisplayName("Conversation state - carried in the payload, never in a session")
    class ConversationState {

        @Test
        @DisplayName("both carrier setters take null as the initialised form")
        void carrierSettersTakeNullAsTheInitialisedForm() {
            CardSelectRequest request = new CardSelectRequest();
            request.getCardScreenState().setCcCardNum(FIXTURE_CARD_NUMBER);
            request.setCardScreenState(null);
            request.setNavigationContext(null);
            assertThat(request.getCardScreenState()).isNotNull();
            assertThat(request.getCardScreenState().getCcCardNum()).isBlank();
            assertThat(request.getNavigationContext()).isEqualTo(NavigationContext.empty());
        }

        @Test
        @DisplayName("a supplied carrier is held as given, not copied away")
        void aSuppliedCarrierIsHeldAsGiven() {
            CardSelectRequest request = new CardSelectRequest();
            CardScreenState state = new CardScreenState();
            NavigationContext context = NavigationContext.empty().withToProgram("COCRDSLC");
            request.setCardScreenState(state);
            request.setNavigationContext(context);
            assertThat(request.getCardScreenState()).isSameAs(state);
            assertThat(request.getNavigationContext()).isSameAs(context);
        }

        @Test
        @DisplayName("CDEMO-PGM-CONTEXT 0 is ENTER, 1 is REENTER, and any other digit is neither")
        void enterAndReenterFollowTheProgramContext() {
            CardSelectRequest request = new CardSelectRequest();
            assertThat(request.isEnter()).isTrue();
            assertThat(request.isReenter()).isFalse();

            request.setNavigationContext(NavigationContext.empty().withPgmReenter());
            assertThat(request.isEnter()).isFalse();
            assertThat(request.isReenter()).isTrue();

            // PIC 9(01) can hold any digit, so isReenter is not the negation of isEnter.
            request.setNavigationContext(NavigationContext.empty().withPgmContext(9));
            assertThat(request.isEnter()).isFalse();
            assertThat(request.isReenter()).isFalse();

            request.setNavigationContext(NavigationContext.empty().withPgmEnter());
            assertThat(request.isEnter()).isTrue();
        }
    }

    @Nested
    @DisplayName("Metadata - xxxL and xxxA, which COCRDSLC writes on the input group")
    class Metadata {

        @Test
        @DisplayName("the map of holders is unmodifiable while the holders themselves stay live")
        void theMapIsUnmodifiableAndTheHoldersAreLive() {
            CardSelectRequest request = new CardSelectRequest();
            Map<ScreenField, ScreenFieldMetadata> view = request.metadata();
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> view.remove(ScreenField.FKEYS));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> view.put(ScreenField.FKEYS, new ScreenFieldMetadata()));
            view.get(ScreenField.FKEYS).setLength(75);
            assertThat(request.metadata(ScreenField.FKEYS).getLength()).isEqualTo(75);
        }

        @Test
        @DisplayName("addressing a null field is rejected")
        void addressingANullFieldIsRejected() {
            CardSelectRequest request = new CardSelectRequest();
            assertThatNullPointerException().isThrownBy(() -> request.metadata(null));
        }

        @Test
        @DisplayName("MOVE -1 TO xxxL is the cursor convention COCRDSLC uses at lines 518, 521, 523")
        void minusOnePositionsTheCursor() {
            ScreenFieldMetadata holder = new ScreenFieldMetadata();
            assertThat(holder.isCursorHere()).isFalse();
            holder.positionCursorHere();
            assertThat(holder.getLength()).isEqualTo(ScreenFieldMetadata.CURSOR_HERE).isEqualTo(-1);
            assertThat(holder.isCursorHere()).isTrue();
            assertThat(holder.isLengthUnset()).isFalse();
        }

        @Test
        @DisplayName("xxxL honours COMP PIC S9(4), not the halfword's wider capacity")
        void theLengthItemHonoursThePictureRange() {
            ScreenFieldMetadata holder = new ScreenFieldMetadata();
            holder.setLength(ScreenFieldMetadata.LENGTH_ITEM_MAX);
            assertThat(holder.getLength()).isEqualTo(9999);
            holder.setLength(ScreenFieldMetadata.LENGTH_ITEM_MIN);
            assertThat(holder.getLength()).isEqualTo(-9999);
            holder.setLength(0);
            assertThat(holder.isLengthUnset()).isTrue();

            // A halfword holds +/-32767 but S9(4) does not, and the declaration wins.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> holder.setLength(10000))
                    .withMessageContaining("COMP PIC S9(4)");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> holder.setLength(-10000))
                    .withMessageContaining("COMP PIC S9(4)");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> holder.setLength(32767));
        }

        @Test
        @DisplayName("xxxA is a raw byte and xxxF is the same byte, a REDEFINES over one position")
        void theAttributeAndFlagViewsShareOneByte() {
            ScreenFieldMetadata holder = new ScreenFieldMetadata();
            assertThat(holder.getAttribute()).isEqualTo(holder.getFlag());
            // DFHBMPRF is 0x61 and DFHBMFSE is 0xC1 - the two COCRDSLC assigns at lines 507 to 511.
            holder.setAttribute((byte) 0x61);
            assertThat(holder.getAttribute()).isEqualTo((byte) 0x61);
            assertThat(holder.getFlag()).isEqualTo((byte) 0x61);
            holder.setAttribute((byte) 0xC1);
            assertThat(holder.getFlag()).isEqualTo((byte) 0xC1);
            assertThat(holder.isAttributeUnset()).isFalse();
            holder.setAttribute(ScreenFieldMetadata.ATTRIBUTE_UNSET);
            assertThat(holder.isAttributeUnset()).isTrue();
        }

        @Test
        @DisplayName("reset returns a holder to LOW-VALUES and an unset length")
        void resetReturnsAHolderToItsInitialState() {
            ScreenFieldMetadata holder = new ScreenFieldMetadata(-1, (byte) 0xF2);
            holder.reset();
            assertThat(holder).isEqualTo(new ScreenFieldMetadata());
            assertThat(holder.isLengthUnset()).isTrue();
            assertThat(holder.isAttributeUnset()).isTrue();
        }

        @Test
        @DisplayName("a holder has value semantics over both of its items")
        void holderValueSemantics() {
            ScreenFieldMetadata holder = new ScreenFieldMetadata(-1, (byte) 0x61);
            ScreenFieldMetadata copy = new ScreenFieldMetadata(holder);
            assertThat(copy).isEqualTo(holder).isNotSameAs(holder).hasSameHashCodeAs(holder);
            assertThat(holder).isEqualTo(holder);
            assertThat(holder).isNotEqualTo(new ScreenFieldMetadata(-1, (byte) 0x60));
            assertThat(holder).isNotEqualTo(new ScreenFieldMetadata(0, (byte) 0x61));
            assertThat(holder).isNotEqualTo("not a holder");
            assertThat(holder).isNotEqualTo(null);
        }

        @Test
        @DisplayName("toString renders the attribute in hexadecimal, a bit pattern not a character")
        void holderToStringRendersHexadecimal() {
            assertThat(new ScreenFieldMetadata(-1, (byte) 0xC1))
                    .hasToString("ScreenFieldMetadata[length=-1, attribute=0xC1]");
            assertThat(new ScreenFieldMetadata())
                    .hasToString("ScreenFieldMetadata[length=0, attribute=0x00]");
        }

        @Test
        @DisplayName("a holder constructed with an out-of-range length is rejected outright")
        void holderConstructorValidates() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new ScreenFieldMetadata(99999, (byte) 0x00));
            assertThatNullPointerException().isThrownBy(() -> new ScreenFieldMetadata(null));
        }
    }

    @Nested
    @DisplayName("The PIC X move rule - applied only through FixedWidthCodec")
    class MoveRule {

        @Test
        @DisplayName("an image is padded on the right and truncated on the right")
        void imagePadsAndTruncatesOnTheRight() {
            CardSelectRequest request = new CardSelectRequest();
            request.setTrnname("CD");
            assertThat(request.image(ScreenField.TRNNAME, ASCII_CODEC)).isEqualTo("CD  ");
            request.setTrnname("ABCDEF");
            assertThat(request.image(ScreenField.TRNNAME, ASCII_CODEC)).isEqualTo("ABCD");
            request.setTrnname("CCDL");
            assertThat(request.image(ScreenField.TRNNAME, ASCII_CODEC)).isEqualTo("CCDL");
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("an image is exactly the declared width for every field")
        void everyImageIsExactlyTheDeclaredWidth(ScreenField field) {
            CardSelectRequest request = new CardSelectRequest();
            request.setValue(field, "X".repeat(field.length() + 5));
            assertThat(request.image(field, ASCII_CODEC)).hasSize(field.length());
            request.setValue(field, "");
            assertThat(request.image(field, ASCII_CODEC)).hasSize(field.length()).isBlank();
        }

        @Test
        @DisplayName("normalize brings all fifteen fields to their declared widths at once")
        void normalizeBringsEveryFieldToItsDeclaredWidth() {
            CardSelectRequest request = new CardSelectRequest();
            request.setTrnname("CCDL");
            request.setCardsid("05000244537657409999");
            request.setCrdstcd("YES");
            request.normalize(ASCII_CODEC);
            for (ScreenField field : ScreenField.values()) {
                assertThat(request.value(field)).as(field.label()).hasSize(field.length());
            }
            assertThat(request.getCardsid()).isEqualTo("0500024453765740");
            assertThat(request.getCrdstcd()).isEqualTo("Y");
            assertThat(request.getTrnname()).isEqualTo("CCDL");
        }

        @Test
        @DisplayName("the codec is mandatory, because the move rule lives nowhere else")
        void theCodecIsMandatory() {
            CardSelectRequest request = new CardSelectRequest();
            assertThatNullPointerException()
                    .isThrownBy(() -> request.image(ScreenField.TRNNAME, null));
            assertThatNullPointerException()
                    .isThrownBy(() -> request.image(null, ASCII_CODEC));
            assertThatNullPointerException().isThrownBy(() -> request.normalize(null));
        }
    }

    @Nested
    @DisplayName("The 504-byte group image")
    class GroupImage {

        private CardSelectRequest populated() {
            CardSelectRequest request = new CardSelectRequest();
            request.setTrnname("CCDL");
            request.setTitle01("AWS Mainframe Modernization");
            request.setPgmname("COCRDSLC");
            request.setAcctsid("00000000050");
            request.setCardsid(FIXTURE_CARD_NUMBER);
            request.setCrdname("Aniya Von");
            request.setCrdstcd("Y");
            request.setExpmon("12");
            request.setExpyear("2028");
            request.setErrmsg("Did not find this Card in Cards Database");
            request.setFkeys("ENTER=Search Cards  F3=Exit");
            request.metadata(ScreenField.ACCTSID).positionCursorHere();
            request.metadata(ScreenField.ACCTSID).setAttribute((byte) 0x61);
            request.metadata(ScreenField.CARDSID).setLength(16);
            request.metadata(ScreenField.CARDSID).setAttribute((byte) 0xC1);
            request.normalize(ASCII_CODEC);
            return request;
        }

        @Test
        @DisplayName("the image is 504 bytes, prefixed by twelve TIOAPFX spaces")
        void theImageIs504BytesWithATwelveByteSpacePrefix() {
            byte[] group = populated().toGroupImage(ASCII_CODEC);
            assertThat(group).hasSize(504);
            assertThat(new String(group, 0, 12, ASCII)).isEqualTo(" ".repeat(12));
        }

        @Test
        @DisplayName("each field's data sits at its declared offset")
        void eachFieldSitsAtItsDeclaredOffset() {
            CardSelectRequest request = populated();
            byte[] group = request.toGroupImage(ASCII_CODEC);
            for (ScreenField field : ScreenField.values()) {
                assertThat(new String(group, field.dataOffset(), field.length(), ASCII))
                        .as(field.describe())
                        .isEqualTo(request.value(field));
            }
            assertThat(new String(group, 429, 75, ASCII)).startsWith("ENTER=Search Cards  F3=Exit");
        }

        @Test
        @DisplayName("xxxL is a big-endian halfword, so -1 renders as 0xFFFF")
        void theLengthItemIsABigEndianHalfword() {
            byte[] group = populated().toGroupImage(ASCII_CODEC);
            assertThat(group[ScreenField.ACCTSID.lengthItemOffset()]).isEqualTo((byte) 0xFF);
            assertThat(group[ScreenField.ACCTSID.lengthItemOffset() + 1]).isEqualTo((byte) 0xFF);
            assertThat(group[ScreenField.CARDSID.lengthItemOffset()]).isEqualTo((byte) 0x00);
            assertThat(group[ScreenField.CARDSID.lengthItemOffset() + 1]).isEqualTo((byte) 0x10);
            assertThat(group[ScreenField.TRNNAME.lengthItemOffset()]).isEqualTo((byte) 0x00);
            assertThat(group[ScreenField.TRNNAME.lengthItemOffset() + 1]).isEqualTo((byte) 0x00);
        }

        @Test
        @DisplayName("a large positive halfword renders big-endian too")
        void aLargePositiveHalfwordRendersBigEndian() {
            CardSelectRequest request = new CardSelectRequest();
            request.metadata(ScreenField.TRNNAME).setLength(9999);
            byte[] group = request.toGroupImage(ASCII_CODEC);
            assertThat(group[ScreenField.TRNNAME.lengthItemOffset()]).isEqualTo((byte) 0x27);
            assertThat(group[ScreenField.TRNNAME.lengthItemOffset() + 1]).isEqualTo((byte) 0x0F);
        }

        @Test
        @DisplayName("xxxA is written raw, not charset-encoded, because it is a bit pattern")
        void theAttributeByteIsWrittenRaw() {
            CardSelectRequest request = populated();
            for (FixedWidthCodec codec : new FixedWidthCodec[] {ASCII_CODEC, EBCDIC_CODEC}) {
                byte[] group = request.toGroupImage(codec);
                assertThat(group[ScreenField.ACCTSID.flagItemOffset()]).isEqualTo((byte) 0x61);
                assertThat(group[ScreenField.CARDSID.flagItemOffset()]).isEqualTo((byte) 0xC1);
                assertThat(group[ScreenField.TRNNAME.flagItemOffset()]).isEqualTo((byte) 0x00);
            }
        }

        @Test
        @DisplayName("the four extended-attribute FILLER bytes are LOW-VALUES on input")
        void theExtendedAttributeFillerIsLowValues() {
            byte[] group = populated().toGroupImage(ASCII_CODEC);
            for (ScreenField field : ScreenField.values()) {
                for (int offset = 0; offset < CardSelectRequest.EXTENDED_ATTRIBUTE_ITEM_LENGTH;
                        offset++) {
                    assertThat(group[field.extendedAttributeItemOffset() + offset])
                            .as(field.label() + " extended-attribute byte " + offset)
                            .isEqualTo((byte) 0x00);
                }
            }
        }

        @Test
        @DisplayName("the charset is the codec's, so a space is 0x40 in IBM037 and 0x20 in US-ASCII")
        void theCharsetIsTheCodecsAndNeverThePlatformDefault() {
            CardSelectRequest request = populated();
            byte[] ebcdic = request.toGroupImage(EBCDIC_CODEC);
            byte[] ascii = request.toGroupImage(ASCII_CODEC);
            assertThat(ebcdic).hasSize(504);
            assertThat(ebcdic[0]).isEqualTo((byte) 0x40);
            assertThat(ascii[0]).isEqualTo((byte) 0x20);
            assertThat(ebcdic).isNotEqualTo(ascii);
            assertThat(new String(ebcdic, ScreenField.TRNNAME.dataOffset(), 4, EBCDIC))
                    .isEqualTo("CCDL");
        }

        @Test
        @DisplayName("a charset that is not one byte per character for this data is refused")
        void aMultiByteEncodingIsRefused() {
            // UTF-8 encodes every digit, sign overpunch and the space in one byte, so the codec accepts
            // it - but an accented character takes two, which would overflow the declared width and
            // shift every offset after it.
            FixedWidthCodec utf8 = new FixedWidthCodec(StandardCharsets.UTF_8);
            assertThatNoException().isThrownBy(() -> new CardSelectRequest().toGroupImage(utf8));

            CardSelectRequest accented = new CardSelectRequest();
            accented.setCrdname("JOSÉ");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> accented.toGroupImage(utf8))
                    .withMessageContaining("single-byte code page");
        }

        @Test
        @DisplayName("the codec and the image are both mandatory")
        void theCodecAndImageAreMandatory() {
            CardSelectRequest request = new CardSelectRequest();
            assertThatNullPointerException().isThrownBy(() -> request.toGroupImage(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> CardSelectRequest.fromGroupImage(null, ASCII_CODEC));
            assertThatNullPointerException()
                    .isThrownBy(() -> CardSelectRequest.fromGroupImage(new byte[504], null));
        }

        @Test
        @DisplayName("the fifteen values and their metadata round-trip under both code pages")
        void theGroupImageRoundTrips() {
            CardSelectRequest original = populated();
            for (FixedWidthCodec codec : new FixedWidthCodec[] {ASCII_CODEC, EBCDIC_CODEC}) {
                CardSelectRequest decoded =
                        CardSelectRequest.fromGroupImage(original.toGroupImage(codec), codec);
                assertThat(decoded).isEqualTo(original).hasSameHashCodeAs(original);
                assertThat(decoded.metadata(ScreenField.ACCTSID).isCursorHere()).isTrue();
                assertThat(decoded.metadata(ScreenField.CARDSID).getLength()).isEqualTo(16);
                assertThat(decoded.metadata(ScreenField.CARDSID).getAttribute())
                        .isEqualTo((byte) 0xC1);
                assertThat(decoded.toGroupImage(codec)).isEqualTo(original.toGroupImage(codec));
            }
        }

        @Test
        @DisplayName("reading back does not trim: a PIC X field's padding is part of its value")
        void readingBackDoesNotTrim() {
            CardSelectRequest original = new CardSelectRequest();
            original.setTrnname("CD");
            original.normalize(ASCII_CODEC);
            CardSelectRequest decoded =
                    CardSelectRequest.fromGroupImage(original.toGroupImage(ASCII_CODEC), ASCII_CODEC);
            assertThat(decoded.getTrnname()).isEqualTo("CD  ").hasSize(4);
        }

        @Test
        @DisplayName("both carriers come back initialised: they are not part of the BMS map")
        void theCarriersAreNotCarriedByTheImage() {
            CardSelectRequest original = new CardSelectRequest();
            original.setNavigationContext(NavigationContext.empty().withPgmReenter());
            original.getCardScreenState().setCcCardNum(FIXTURE_CARD_NUMBER);
            CardSelectRequest decoded =
                    CardSelectRequest.fromGroupImage(original.toGroupImage(ASCII_CODEC), ASCII_CODEC);
            assertThat(decoded.getNavigationContext()).isEqualTo(NavigationContext.empty());
            assertThat(decoded.isEnter()).isTrue();
            assertThat(decoded.getCardScreenState().getCcCardNum()).isBlank();
            assertThat(decoded).isNotEqualTo(original);
        }

        @Test
        @DisplayName("an image of the wrong length is rejected, not padded or truncated")
        void anImageOfTheWrongLengthIsRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardSelectRequest.fromGroupImage(new byte[503], ASCII_CODEC))
                    .withMessageContaining("504");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardSelectRequest.fromGroupImage(new byte[505], ASCII_CODEC));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardSelectRequest.fromGroupImage(new byte[0], ASCII_CODEC));
        }

        @Test
        @DisplayName("a length item outside COMP PIC S9(4) is rejected in either direction")
        void anOutOfPictureLengthItemIsRejected() {
            byte[] tooHigh = new CardSelectRequest().toGroupImage(ASCII_CODEC);
            int offset = ScreenField.CARDSID.lengthItemOffset();
            tooHigh[offset] = (byte) 0x7F;
            tooHigh[offset + 1] = (byte) 0xFF;
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardSelectRequest.fromGroupImage(tooHigh, ASCII_CODEC))
                    .withMessageContaining("COMP PIC S9(4) cannot represent");

            byte[] tooLow = new CardSelectRequest().toGroupImage(ASCII_CODEC);
            tooLow[offset] = (byte) 0x80;
            tooLow[offset + 1] = (byte) 0x00;
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardSelectRequest.fromGroupImage(tooLow, ASCII_CODEC))
                    .withMessageContaining("COMP PIC S9(4) cannot represent");
        }

        @Test
        @DisplayName("an all-zero image reads as fifteen fields of LOW-VALUES with unset metadata")
        void anAllZeroImageReadsBack() {
            CardSelectRequest decoded =
                    CardSelectRequest.fromGroupImage(new byte[504], ASCII_CODEC);
            for (ScreenField field : ScreenField.values()) {
                assertThat(decoded.value(field)).as(field.label()).hasSize(field.length());
                assertThat(decoded.metadata(field).isLengthUnset()).isTrue();
                assertThat(decoded.metadata(field).isAttributeUnset()).isTrue();
            }
        }
    }

    @Nested
    @DisplayName("Value semantics - every field participates")
    class ValueSemantics {

        @Test
        @DisplayName("a request equals itself and nothing of another type")
        void reflexiveAndTypeChecked() {
            CardSelectRequest request = new CardSelectRequest();
            assertThat(request).isEqualTo(request)
                    .isNotEqualTo("not a request")
                    .isNotEqualTo(null);
            assertThat(new CardSelectRequest()).isEqualTo(new CardSelectRequest())
                    .hasSameHashCodeAs(new CardSelectRequest());
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("changing any one of the fifteen fields makes two requests unequal")
        void changingAnySingleFieldBreaksEquality(ScreenField field) {
            CardSelectRequest baseline = new CardSelectRequest();
            CardSelectRequest changed = new CardSelectRequest();
            changed.setValue(field, "Q");
            assertThat(changed).as("changed " + field.label()).isNotEqualTo(baseline);
            assertThat(baseline).as("changed " + field.label()).isNotEqualTo(changed);
        }

        @Test
        @DisplayName("changing either carrier, or any metadata holder, makes two requests unequal")
        void changingACarrierOrMetadataBreaksEquality() {
            CardSelectRequest baseline = new CardSelectRequest();

            CardSelectRequest differentState = new CardSelectRequest();
            differentState.getCardScreenState().setCcAcctId("00000000050");
            assertThat(differentState).isNotEqualTo(baseline);

            CardSelectRequest differentContext = new CardSelectRequest();
            differentContext.setNavigationContext(NavigationContext.empty().withPgmReenter());
            assertThat(differentContext).isNotEqualTo(baseline);

            CardSelectRequest differentMetadata = new CardSelectRequest();
            differentMetadata.metadata(ScreenField.ACCTSID).positionCursorHere();
            assertThat(differentMetadata).isNotEqualTo(baseline);
        }

        @Test
        @DisplayName("toString names every field by its DFHMDF label and hides nothing")
        void toStringNamesEveryField() {
            CardSelectRequest request = new CardSelectRequest();
            request.setCardsid(FIXTURE_CARD_NUMBER);
            request.metadata(ScreenField.CARDSID).positionCursorHere();
            String rendered = request.toString();
            for (ScreenField field : ScreenField.values()) {
                assertThat(rendered).contains(field.label() + "='");
            }
            assertThat(rendered)
                    .startsWith("CardSelectRequest[")
                    .endsWith("]")
                    .contains("0500024453765740")
                    .contains("ScreenFieldMetadata[length=-1")
                    .contains("cardScreenState=")
                    .contains("navigationContext=");
        }
    }

    @Nested
    @DisplayName("Validation - @Size from the PICTURE widths, and nothing more")
    class BeanValidation {

        @Test
        @DisplayName("a blank request is valid: COCRDSLC owns its own blank and format edits")
        void aBlankRequestIsValid() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                Validator validator = factory.getValidator();
                assertThat(validator.validate(new CardSelectRequest())).isEmpty();

                CardSelectRequest odd = new CardSelectRequest();
                odd.setAcctsid("*");
                odd.setCardsid("not-a-number");
                odd.setExpmon("ZZ");
                odd.setExpyear("!!!!");
                assertThat(validator.validate(odd)).isEmpty();
            }
        }

        @Test
        @DisplayName("every field is valid at exactly its declared width")
        void everyFieldIsValidAtExactlyItsDeclaredWidth() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                Validator validator = factory.getValidator();
                CardSelectRequest atTheLimit = new CardSelectRequest();
                for (ScreenField field : ScreenField.values()) {
                    atTheLimit.setValue(field, "X".repeat(field.length()));
                }
                assertThat(validator.validate(atTheLimit)).isEmpty();
            }
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("one character over the declared width is one violation, citing the copybook")
        void oneCharacterTooManyIsOneViolation(ScreenField field) {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                Validator validator = factory.getValidator();
                CardSelectRequest overWide = new CardSelectRequest();
                overWide.setValue(field, "X".repeat(field.length() + 1));
                Set<ConstraintViolation<CardSelectRequest>> violations = validator.validate(overWide);
                assertThat(violations).hasSize(1);
                ConstraintViolation<CardSelectRequest> violation = violations.iterator().next();
                assertThat(violation.getPropertyPath()).hasToString(
                        field.label().toLowerCase(Locale.ROOT));
                assertThat(violation.getMessage())
                        .contains(field.label())
                        .contains("PIC X(" + field.length() + ")")
                        .contains("app/cpy-bms/COCRDSL.CPY:" + field.copybookLine());
            }
        }
    }

    @Nested
    @DisplayName("The JSON projection - fifteen fields and two carriers, nothing else")
    class JsonProjection {

        @Test
        @DisplayName("exactly seventeen members, with no metadata and no derived condition")
        void exactlySeventeenMembers() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            CardSelectRequest request = new CardSelectRequest();
            request.setCardsid(FIXTURE_CARD_NUMBER);
            request.metadata(ScreenField.CARDSID).positionCursorHere();
            request.metadata(ScreenField.CARDSID).setAttribute((byte) 0xC1);

            JsonNode json = mapper.readTree(mapper.writeValueAsString(request));
            List<String> members = new ArrayList<>();
            json.fieldNames().forEachRemaining(members::add);
            assertThat(members).containsExactlyInAnyOrder("trnname", "title01", "curdate", "pgmname",
                    "title02", "curtime", "acctsid", "cardsid", "crdname", "crdstcd", "expmon",
                    "expyear", "infomsg", "errmsg", "fkeys", "cardScreenState", "navigationContext");
            assertThat(json.has("metadata")).isFalse();
            assertThat(json.has("enter")).isFalse();
            assertThat(json.has("reenter")).isFalse();
            assertThat(json.has("value")).isFalse();
            assertThat(json.get("cardsid").asText()).isEqualTo("0500024453765740");
        }

        @Test
        @DisplayName("every wire name is its DFHMDF label in lower case")
        void everyWireNameIsTheLabelInLowerCase() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode json = mapper.readTree(mapper.writeValueAsString(new CardSelectRequest()));
            for (ScreenField field : ScreenField.values()) {
                assertThat(json.has(field.label().toLowerCase(Locale.ROOT)))
                        .as(field.label())
                        .isTrue();
            }
        }

        @Test
        @DisplayName("a payload round-trips, carrier and all")
        void aPayloadRoundTrips() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            CardSelectRequest request = new CardSelectRequest();
            request.setAcctsid("00000000050");
            request.setCardsid(FIXTURE_CARD_NUMBER);
            request.setNavigationContext(NavigationContext.empty().withPgmReenter());
            request.normalize(ASCII_CODEC);

            CardSelectRequest round =
                    mapper.readValue(mapper.writeValueAsString(request), CardSelectRequest.class);
            assertThat(round.getAcctsid()).isEqualTo(request.getAcctsid());
            assertThat(round.getCardsid()).isEqualTo("0500024453765740");
            assertThat(round.isReenter()).isTrue();
            assertThat(round.getNavigationContext()).isEqualTo(request.getNavigationContext());
        }

        @Test
        @DisplayName("an omitted field reads as spaces, because a COBOL record has no null")
        void anOmittedFieldReadsAsSpaces() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            CardSelectRequest sparse =
                    mapper.readValue("{\"cardsid\":\"0500024453765740\"}", CardSelectRequest.class);
            assertThat(sparse.getCardsid()).isEqualTo("0500024453765740");
            assertThat(sparse.getAcctsid()).isEqualTo(" ".repeat(11));
            assertThat(sparse.getFkeys()).hasSize(75).isBlank();
            assertThat(sparse.getNavigationContext()).isEqualTo(NavigationContext.empty());
            assertThat(sparse.getCardScreenState()).isNotNull();
            assertThat(sparse.metadata()).hasSize(15);
        }

        @Test
        @DisplayName("an explicit null reads as the initialised value, not as null")
        void anExplicitNullReadsAsTheInitialisedValue() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            CardSelectRequest nulled = mapper.readValue(
                    "{\"cardsid\":null,\"acctsid\":null,\"navigationContext\":null,"
                            + "\"cardScreenState\":null}",
                    CardSelectRequest.class);
            assertThat(nulled).isEqualTo(new CardSelectRequest());
        }
    }
}
