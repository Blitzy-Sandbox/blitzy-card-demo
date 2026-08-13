package com.vsergeychik.carddemo.card.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.CardDetails;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.CardUpdateRecord;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.ChangeAction;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.CommArea;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.DetailGroup;
import com.vsergeychik.carddemo.card.dto.CardUpdateResponse.FieldAttributes;
import com.vsergeychik.carddemo.card.dto.CardUpdateResponse.ScreenField;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.ConversationStateSeal;
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
import com.vsergeychik.carddemo.testsupport.ConversationStateSealFixture;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies {@link CardUpdateResponse} against its sources: {@code app/cpy-bms/COCRDUP.CPY} (the
 * {@code 01 CCRDUPAO} output group at L121), {@code app/bms/COCRDUP.bms} and {@code app/cbl/COCRDUPC.cbl}.
 */
@DisplayName("CardUpdateResponse - CCRDUPAO, 17 named fields of 34, and one shared 329-byte area")
class CardUpdateResponseTest {
    private static final FixedWidthCodec ASCII = new FixedWidthCodec(StandardCharsets.US_ASCII);

    private static final Charset IBM037 = Charset.forName("IBM037");

    private static final FixedWidthCodec EBCDIC = new FixedWidthCodec(IBM037);

    private static final List<String> FIELD_NAMES = List.of("TRNNAME", "TITLE01", "CURDATE",
            "PGMNAME", "TITLE02", "CURTIME", "ACCTSID", "CARDSID", "CRDNAME", "CRDSTCD", "EXPMON",
            "EXPYEAR", "EXPDAY", "INFOMSG", "ERRMSG", "FKEYS", "FKEYSC");

    private static final List<String> SHARED_CARRIERS =
            List.of("stateToken", "cardScreenState", "navigationContext");

    /**
     * The seal this suite produces and verifies every {@code stateToken} with.
     *
     * <p>{@code WS-THIS-PROGCOMMAREA} no longer crosses the pair as a structured object, so the assertions
     * that used to bind a {@code commArea} node now bind a token and unseal it. One seal for the suite,
     * because two instances built from different secrets would refuse each other's tokens and every
     * round-trip assertion would pass for the wrong reason.
     */
    private static final ConversationStateSeal SEAL = ConversationStateSealFixture.seal();

    /**
     * The purpose {@code CardUpdateController} binds its tokens to, spelled out here because the
     * controller's constant is package visible in {@code ..card} and this suite is in {@code ..card.dto}.
     */
    private static final String STATE_PURPOSE = "COCRDUPC/stateToken";

    /**
     * Seals a response's {@code WS-THIS-PROGCOMMAREA} into its {@code stateToken}, as the controller does
     * at the HTTP boundary.
     *
     * @param response the response to seal; mutated in place
     * @return the same response
     */
    private static CardUpdateResponse sealed(CardUpdateResponse response) {
        response.setStateToken(SEAL.seal(STATE_PURPOSE, CARD_NUMBER,
                response.getCommArea().encode(ASCII)));
        return response;
    }

    /**
     * Unseals whatever a request carried in {@code stateToken}.
     *
     * @param token the token as it arrived
     * @return the 329-byte area it holds
     */
    private static CommArea unsealed(String token) {
        return CommArea.decode(SEAL.unseal("stateToken", STATE_PURPOSE, CARD_NUMBER, token), ASCII);
    }

    private static final List<String> RETIRED_CARRIER_NAMES =
            List.of("progCommarea", "screenState", "navigation");

    private static final List<String> RETIRED_NESTED_TYPES =
            List.of("ProgCommarea", "CcupDetails", "DetailsPrefix", "CardUpdateRecord");

    private static final String CARD_NUMBER = "4111111111111111";

    private static final String CVV_CODE = "123";

    private static final String EMBOSSED_NAME = "JOHN Q PUBLIC";

    private static final String FKEYS_LITERAL = "ENTER=Process F3=Exit";

    private static final String FKEYSC_LITERAL = "F5=Save F12=Cancel";

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2022-07-19T23:15:58Z"), ZoneOffset.UTC);

    private static final String FIXED_CURDATE = "07/19/22";

    private static final String FIXED_CURTIME = "23:15:58";

    private static List<String> membersOf(Object value) {
        ObjectNode node = (ObjectNode) new ObjectMapper().valueToTree(value);
        List<String> members = new ArrayList<>();
        node.fieldNames().forEachRemaining(members::add);
        return members;
    }

    private static CardDetails details(DetailGroup group, String cardid) {
        return new CardDetails(group, "00000000011", cardid, CVV_CODE, EMBOSSED_NAME, "2026", "07",
                "19", "Y");
    }

    private static CommArea populatedCommArea() {
        return new CommArea(ChangeAction.changesOkNotConfirmed(),
                details(DetailGroup.OLD, CARD_NUMBER),
                details(DetailGroup.NEW, "4111111111112222"),
                CardUpdateRecord.initialised().withCardUpdateNum(CARD_NUMBER)
                        .withCardUpdateAcctId(11L)
                        .withCardUpdateCvvCd(Integer.parseInt(CVV_CODE)));
    }

    private static DateHeader fixedDateHeader() {
        return DateHeader.from(ASCII, FIXED_CLOCK);
    }

    static List<Arguments> namedFieldTriples() {
        return List.of(
                Arguments.of("TRNNAME", 4, "(1,7)"),
                Arguments.of("TITLE01", 40, "(1,21)"),
                Arguments.of("CURDATE", 8, "(1,71)"),
                Arguments.of("PGMNAME", 8, "(2,7)"),
                Arguments.of("TITLE02", 40, "(2,21)"),
                Arguments.of("CURTIME", 8, "(2,71)"),
                Arguments.of("ACCTSID", 11, "(7,45)"),
                Arguments.of("CARDSID", 16, "(8,45)"),
                Arguments.of("CRDNAME", 50, "(11,25)"),
                Arguments.of("CRDSTCD", 1, "(13,25)"),
                Arguments.of("EXPMON", 2, "(15,25)"),
                Arguments.of("EXPYEAR", 4, "(15,30)"),
                Arguments.of("EXPDAY", 2, "(15,36)"),
                Arguments.of("INFOMSG", 40, "(20,25)"),
                Arguments.of("ERRMSG", 80, "(23,1)"),
                Arguments.of("FKEYS", 21, "(24,1)"),
                Arguments.of("FKEYSC", 18, "(24,23)"));
    }

    static List<Arguments> highlightTruthTable() {
        return List.of(
                Arguments.of(FieldValidationState.OK, false, false, false),
                Arguments.of(FieldValidationState.NOT_OK, false, false, false),
                Arguments.of(FieldValidationState.BLANK, false, false, false),
                Arguments.of(FieldValidationState.OK, true, false, false),
                Arguments.of(FieldValidationState.NOT_OK, true, true, false),
                Arguments.of(FieldValidationState.BLANK, true, true, true));
    }

    private static CardScreenState populatedScreenState() {
        CardScreenState state = new CardScreenState();
        state.setCcardAid("PF03 ");
        state.setCcardNextProg("COCRDLIC");
        state.setCcardNextMapset("COCRDLI");
        state.setCcardNextMap("CCRDLIA");
        state.setCcAcctId("00000000011");
        state.setCcCardNum(CARD_NUMBER);
        return state;
    }

    private static CardUpdateResponse populatedResponse() {
        CardUpdateResponse response = new CardUpdateResponse();
        response.screenInit(fixedDateHeader());
        response.setAcctsido("00000000011");
        response.setCardsido(CARD_NUMBER);
        response.setCrdnameo(EMBOSSED_NAME);
        response.setCrdstcdo("Y");
        response.setExpmono("07");
        response.setExpyearo("2026");
        response.setExpdayo("19");
        response.setInfomsgo("Enter your changes and press ENTER");
        response.setErrmsgo("Account filter not valid");
        response.setCommArea(populatedCommArea());
        response.setCardScreenState(populatedScreenState());
        response.setNavigationContext(NavigationContext.empty());
        response.transferTo("COCRDLIC", "COCRDLI", "CCRDLIA");
        return response;
    }

    @Nested
    @DisplayName("FKEYSC / FKEYSCC - four members, two of them one byte wide, and none of them fused")
    class FkeyscCollision {
        @Test
        @DisplayName("all four members exist under their own names: FKEYSO, FKEYSC, FKEYSCO, FKEYSCC")
        void allFourMembersExistUnderTheirOwnNames() {
            ScreenField fkeys = CardUpdateResponse.fieldOf(CardUpdateResponse.FKEYS);
            ScreenField fkeysc = CardUpdateResponse.fieldOf(CardUpdateResponse.FKEYSC);

            assertThat(fkeys.name()).isEqualTo("FKEYS");
            assertThat(fkeysc.name()).isEqualTo("FKEYSC");

            assertThat(fkeys.outputItemName()).isEqualTo("FKEYSO");
            assertThat(fkeys.colourItemName()).isEqualTo("FKEYSC");

            assertThat(fkeysc.outputItemName()).isEqualTo("FKEYSCO");
            assertThat(fkeysc.colourItemName()).isEqualTo("FKEYSCC");

            assertThat(List.of(fkeys.outputItemName(), fkeys.colourItemName(),
                    fkeysc.outputItemName(), fkeysc.colourItemName()))
                    .containsExactly("FKEYSO", "FKEYSC", "FKEYSCO", "FKEYSCC")
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("their widths are 21, 1, 18 and 1 - one assertion each catches any fusion")
        void theirWidthsAreTwentyOneOneEighteenAndOne() {
            ScreenField fkeys = CardUpdateResponse.fieldOf(CardUpdateResponse.FKEYS);
            ScreenField fkeysc = CardUpdateResponse.fieldOf(CardUpdateResponse.FKEYSC);

            assertThat(fkeys.outputItemSpan().length()).isEqualTo(21);
            assertThat(fkeys.length()).isEqualTo(CardUpdateResponse.FKEYSO_LENGTH).isEqualTo(21);

            assertThat(fkeys.colourItemSpan().length()).isEqualTo(1);
            assertThat(fkeys.colourItemSpan().name()).isEqualTo("FKEYSC");

            assertThat(fkeysc.outputItemSpan().length()).isEqualTo(18);
            assertThat(fkeysc.length()).isEqualTo(CardUpdateResponse.FKEYSCO_LENGTH).isEqualTo(18);

            assertThat(fkeysc.colourItemSpan().length()).isEqualTo(1);
            assertThat(fkeysc.colourItemSpan().name()).isEqualTo("FKEYSCC");

            assertThat(fkeys.length()).isNotEqualTo(fkeysc.length());
        }

        @Test
        @DisplayName("cross-pair 1 of 4: DFHRED into FKEYSC leaves FKEYSCO's 18 bytes untouched")
        void colouringFkeysDoesNotDisturbTheEighteenByteField() {
            CardUpdateResponse response = new CardUpdateResponse();
            response.setOutputItem(CardUpdateResponse.FKEYSC, FKEYSC_LITERAL);

            response.setColour(CardUpdateResponse.FKEYS, BmsAttributes.DFHRED);

            assertThat(response.attributeImages().get("FKEYSC"))
                    .isEqualTo(BmsAttributes.toHex(BmsAttributes.DFHRED));
            assertThat(response.getFkeysco()).isEqualTo(FKEYSC_LITERAL).hasSize(18);
            assertThat(response.outputItemOf(CardUpdateResponse.FKEYSC)).isEqualTo(FKEYSC_LITERAL);
        }

        @Test
        @DisplayName("cross-pair 2 of 4: 18 characters into FKEYSCO leave FKEYSC's byte untouched")
        void writingTheEighteenByteFieldDoesNotDisturbFkeysColour() {
            CardUpdateResponse response = new CardUpdateResponse();

            response.setFkeysco(FKEYSC_LITERAL);

            assertThat(response.getFkeysco()).isEqualTo(FKEYSC_LITERAL).hasSize(18);
            assertThat(response.colourOf(CardUpdateResponse.FKEYS))
                    .isEqualTo(CardUpdateResponse.FieldAttributes.DEFAULT);
            assertThat(response.attributeImages().get("FKEYSC"))
                    .isEqualTo(BmsAttributes.toHex((byte) 0x00));
        }

        @Test
        @DisplayName("cross-pair 3 of 4: DFHRED into FKEYSCC touches neither FKEYSC nor FKEYSO")
        void colouringTheEighteenByteFieldTouchesNeitherFkeysItem() {
            CardUpdateResponse response = new CardUpdateResponse();
            response.setFkeyso(FKEYS_LITERAL);

            response.setColour(CardUpdateResponse.FKEYSC, BmsAttributes.DFHRED);

            assertThat(response.attributeImages().get("FKEYSCC"))
                    .isEqualTo(BmsAttributes.toHex(BmsAttributes.DFHRED));
            assertThat(response.colourOf(CardUpdateResponse.FKEYS))
                    .isEqualTo(CardUpdateResponse.FieldAttributes.DEFAULT);
            assertThat(response.getFkeyso()).isEqualTo(FKEYS_LITERAL).hasSize(21);
        }

        @Test
        @DisplayName("cross-pair 4 of 4: 21 characters into FKEYSO leave FKEYSCC and FKEYSCO alone")
        void writingFkeysoLeavesTheEighteenByteFieldAndItsColourAlone() {
            CardUpdateResponse response = new CardUpdateResponse();
            response.setFkeysco(FKEYSC_LITERAL);
            response.setColour(CardUpdateResponse.FKEYSC, BmsAttributes.DFHYELLO);

            response.setFkeyso(FKEYS_LITERAL);

            assertThat(response.getFkeyso()).isEqualTo(FKEYS_LITERAL).hasSize(21);
            assertThat(response.getFkeysco()).isEqualTo(FKEYSC_LITERAL).hasSize(18);
            assertThat(response.colourOf(CardUpdateResponse.FKEYSC))
                    .isEqualTo(BmsAttributes.DFHYELLO);
        }

        @Test
        @DisplayName("the two attribute quads are eight distinct items, four per field")
        void theTwoAttributeQuadsAreEightDistinctItems() {
            ScreenField fkeys = CardUpdateResponse.fieldOf(CardUpdateResponse.FKEYS);
            ScreenField fkeysc = CardUpdateResponse.fieldOf(CardUpdateResponse.FKEYSC);

            assertThat(List.of(fkeys.colourItemName(), fkeys.psItemName(), fkeys.hilightItemName(),
                    fkeys.validnItemName()))
                    .containsExactly("FKEYSC", "FKEYSP", "FKEYSH", "FKEYSV");

            assertThat(List.of(fkeysc.colourItemName(), fkeysc.psItemName(),
                    fkeysc.hilightItemName(), fkeysc.validnItemName()))
                    .containsExactly("FKEYSCC", "FKEYSCP", "FKEYSCH", "FKEYSCV");

            assertThat(List.of("FKEYSC", "FKEYSP", "FKEYSH", "FKEYSV", "FKEYSCC", "FKEYSCP",
                    "FKEYSCH", "FKEYSCV")).doesNotHaveDuplicates();
            for (FieldSpan span : List.of(fkeys.colourItemSpan(), fkeys.psItemSpan(),
                    fkeys.hilightItemSpan(), fkeys.validnItemSpan(), fkeysc.colourItemSpan(),
                    fkeysc.psItemSpan(), fkeysc.hilightItemSpan(), fkeysc.validnItemSpan())) {
                assertThat(span.length()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("the four quads and the two payloads all sit at different offsets")
        void theSixItemsSitAtSixDifferentOffsets() {
            ScreenField fkeys = CardUpdateResponse.fieldOf(CardUpdateResponse.FKEYS);
            ScreenField fkeysc = CardUpdateResponse.fieldOf(CardUpdateResponse.FKEYSC);

            assertThat(fkeys.endOffsetExclusive()).isEqualTo(fkeysc.fieldOffset());
            assertThat(fkeysc.endOffsetExclusive()).isEqualTo(CardUpdateResponse.GROUP_LENGTH);

            assertThat(List.of(fkeys.colourItemOffset(), fkeys.outputItemOffset(),
                    fkeysc.colourItemOffset(), fkeysc.outputItemOffset()))
                    .doesNotHaveDuplicates();
            assertThat(fkeys.outputItemOffset() - fkeys.colourItemOffset()).isEqualTo(4);
            assertThat(fkeysc.colourItemOffset() - fkeys.colourItemOffset()).isEqualTo(28);
        }

        @Test
        @DisplayName("the display literals are carried byte for byte, at exactly 21 and 18")
        void theDisplayLiteralsAreCarriedByteForByte() {
            CardUpdateResponse response = new CardUpdateResponse();

            response.setFkeyso(FKEYS_LITERAL);
            response.setFkeysco(FKEYSC_LITERAL);

            assertThat(response.getFkeyso()).isEqualTo("ENTER=Process F3=Exit").hasSize(21);
            assertThat(response.getFkeysco()).isEqualTo("F5=Save F12=Cancel").hasSize(18);

            assertThat(response.fieldImages())
                    .containsEntry("FKEYSO", "ENTER=Process F3=Exit")
                    .containsEntry("FKEYSCO", "F5=Save F12=Cancel");
        }

        @Test
        @DisplayName("the input side gives FKEYSC a complete quintuple: FKEYSCL/F/A and FKEYSCI X(18)")
        void theInputSideGivesTheFieldItsOwnQuintuple() {
            assertThat(CardUpdateRequest.declaredLength("FKEYSC")).isEqualTo(18);
            assertThat(CardUpdateRequest.FKEYSC_LENGTH).isEqualTo(18);
            assertThat(CardUpdateRequest.FKEYS_LENGTH).isEqualTo(21);

            CardUpdateRequest request = new CardUpdateRequest();
            CardUpdateRequest.FieldMetadata metadata = request.metadataFor("FKEYSC");

            assertThat(metadata.fieldName()).isEqualTo("FKEYSC");
            assertThat(metadata.declaredLength()).isEqualTo(18);
            assertThat(metadata.flagItem()).hasSize(1);
            assertThat(metadata.attributeItem()).isEqualTo(metadata.flagItem());
            assertThat(request.getFkeysc()).hasSize(18);
            assertThat(request.getFkeys()).hasSize(21);
        }

        @Test
        @DisplayName("addressing is by DFHMDF label, so 'FKEYSO' and 'FKEYSCC' are not field names")
        void addressingIsByLabelNotByItemName() {
            assertThat(CardUpdateResponse.declaresField("FKEYS")).isTrue();
            assertThat(CardUpdateResponse.declaresField("FKEYSC")).isTrue();
            assertThat(CardUpdateResponse.declaresField("FKEYSO")).isFalse();
            assertThat(CardUpdateResponse.declaresField("FKEYSCO")).isFalse();
            assertThat(CardUpdateResponse.declaresField("FKEYSCC")).isFalse();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardUpdateResponse.fieldOf("FKEYSCO"))
                    .withMessageContaining("FKEYSCO");
        }

        @Test
        @DisplayName("both readings of FKEYSC survive the 484-byte image round trip")
        void bothReadingsSurviveTheImageRoundTrip() {
            CardUpdateResponse response = new CardUpdateResponse();
            response.setFkeyso(FKEYS_LITERAL);
            response.setFkeysco(FKEYSC_LITERAL);
            response.setColour(CardUpdateResponse.FKEYS, BmsAttributes.DFHRED);
            response.setColour(CardUpdateResponse.FKEYSC, BmsAttributes.DFHYELLO);

            CardUpdateResponse restored = CardUpdateResponse.fromFixedWidth(
                    response.toFixedWidth(StandardCharsets.US_ASCII), StandardCharsets.US_ASCII);

            assertThat(restored.getFkeyso()).isEqualTo(FKEYS_LITERAL);
            assertThat(restored.getFkeysco()).isEqualTo(FKEYSC_LITERAL);
            assertThat(restored.colourOf(CardUpdateResponse.FKEYS)).isEqualTo(BmsAttributes.DFHRED);
            assertThat(restored.colourOf(CardUpdateResponse.FKEYSC))
                    .isEqualTo(BmsAttributes.DFHYELLO);
        }
    }

    @Nested
    @DisplayName("One area, one type - the response declares no duplicate of the request's carriers")
    class OneAreaOneType {
        @Test
        @DisplayName("the only nested types left are ScreenField and FieldAttributes")
        void onlyScreenFieldAndFieldAttributesRemain() {
            List<String> nested = new ArrayList<>();
            for (Class<?> declared : CardUpdateResponse.class.getDeclaredClasses()) {
                nested.add(declared.getSimpleName());
            }
            assertThat(nested).containsExactlyInAnyOrder("ScreenField", "FieldAttributes");
        }

        @ParameterizedTest(name = "no nested type named {0}")
        @ValueSource(strings = {"ProgCommarea", "CcupDetails", "DetailsPrefix", "CardUpdateRecord"})
        @DisplayName("none of the four retired duplicate types is declared here any more")
        void noRetiredNestedTypeIsDeclared(String retired) {
            List<String> nested = new ArrayList<>();
            for (Class<?> declared : CardUpdateResponse.class.getDeclaredClasses()) {
                nested.add(declared.getSimpleName());
            }
            assertThat(nested).doesNotContain(retired);
            assertThat(RETIRED_NESTED_TYPES).contains(retired);
        }

        @Test
        @DisplayName("getCommArea returns the request's CommArea, not a response-local twin")
        void theProgramCommareaIsTheRequestsType() throws Exception {
            assertThat(CardUpdateResponse.class.getMethod("getCommArea").getReturnType())
                    .isEqualTo(CommArea.class)
                    .isEqualTo(CardUpdateRequest.class.getMethod("getCommArea").getReturnType());
        }

        @Test
        @DisplayName("the two 89-byte detail groups are the request's CardDetails and DetailGroup")
        void theDetailGroupsAreTheRequestsTypes() {
            CommArea area = populatedCommArea();
            assertThat(area.oldDetails()).isInstanceOf(CardDetails.class);
            assertThat(area.oldDetails().group()).isEqualTo(DetailGroup.OLD);
            assertThat(area.newDetails().group()).isEqualTo(DetailGroup.NEW);
            assertThat(CardDetails.RECORD_LENGTH).isEqualTo(89);
        }

        @Test
        @DisplayName("the embedded 150-byte record is the request's CardUpdateRecord")
        void theEmbeddedRecordIsTheRequestsType() {
            assertThat(populatedCommArea().cardUpdateRecord()).isInstanceOf(CardUpdateRecord.class);
            assertThat(CardUpdateRecord.RECORD_LENGTH).isEqualTo(150);
        }

        @Test
        @DisplayName("1 + 89 + 89 + 150 = 329 at the offsets COCRDUPC.cbl:274-321 declares")
        void theAreaIsThreeHundredAndTwentyNineBytes() {
            assertThat(CommArea.RECORD_LENGTH).isEqualTo(329);
            assertThat(ChangeAction.RECORD_LENGTH + CardDetails.RECORD_LENGTH
                    + CardDetails.RECORD_LENGTH + CardUpdateRecord.RECORD_LENGTH).isEqualTo(329);
            assertThat(CommArea.CHANGE_ACTION_OFFSET).isZero();
            assertThat(CommArea.OLD_DETAILS_OFFSET).isEqualTo(1);
            assertThat(CommArea.NEW_DETAILS_OFFSET).isEqualTo(90);
            assertThat(CommArea.CARD_UPDATE_RECORD_OFFSET).isEqualTo(179);
        }
    }

    @Nested
    @DisplayName("One name per carrier - the request and the response spell the shared state alike")
    class OneNamePerCarrier {
        @Test
        @DisplayName("the response publishes commArea, cardScreenState and navigationContext")
        void theResponsePublishesTheSharedNames() {
            assertThat(membersOf(new CardUpdateResponse())).containsAll(SHARED_CARRIERS);
        }

        @Test
        @DisplayName("the request publishes the same three names")
        void theRequestPublishesTheSameNames() {
            assertThat(membersOf(new CardUpdateRequest())).containsAll(SHARED_CARRIERS);
        }

        @ParameterizedTest(name = "{0} is gone from the response")
        @ValueSource(strings = {"progCommarea", "screenState", "navigation"})
        @DisplayName("none of the three retired member names is emitted any more")
        void noRetiredMemberNameIsEmitted(String retired) {
            assertThat(membersOf(populatedResponse())).doesNotContain(retired);
            assertThat(RETIRED_CARRIER_NAMES).contains(retired);
        }

        @Test
        @DisplayName("the pair shares exactly the three carriers and nothing else")
        void thePairSharesExactlyTheThreeCarriers() {
            List<String> shared = new ArrayList<>(membersOf(new CardUpdateResponse()));
            shared.retainAll(membersOf(new CardUpdateRequest()));

            assertThat(shared).containsAll(SHARED_CARRIERS);
            assertThat(shared)
                    .containsAll(FIELD_NAMES.stream()
                            .map(name -> name.toLowerCase(java.util.Locale.ROOT)).toList());
            assertThat(shared).hasSize(SHARED_CARRIERS.size() + FIELD_NAMES.size());
        }

        @Test
        @DisplayName("the response carries 23 members: 17 xxxO items, 3 carriers, 3 XCTL targets")
        void theResponseCarriesTwentyThreeMembers() {
            List<String> members = membersOf(new CardUpdateResponse());
            assertThat(members).hasSize(23);
            assertThat(members).containsAll(SHARED_CARRIERS);
            assertThat(members).contains("nextProgram", "nextMapset", "nextMap");
            for (String name : FIELD_NAMES) {
                assertThat(members).contains(name.toLowerCase(java.util.Locale.ROOT));
            }
        }

        @Test
        @DisplayName("the metadata and image views stay off the wire")
        void theInternalViewsStayOffTheWire() {
            assertThat(membersOf(populatedResponse()))
                    .doesNotContain("fieldImages", "attributeImages", "namedFields", "attributes",
                            "payload");
        }
    }

    @Nested
    @DisplayName("The pair round-trips - what the response emits binds straight into a request")
    class PairRoundTrip {
        @Test
        @DisplayName("the response's stateToken binds into a request and unseals to the same area")
        void theSealedCommAreaCrossesThePairWithoutRewriting() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            CardUpdateResponse response = sealed(populatedResponse());
            JsonNode emitted = mapper.valueToTree(response).get("stateToken");

            ObjectNode body = mapper.createObjectNode();
            body.set("stateToken", emitted);
            CardUpdateRequest bound =
                    mapper.readValue(mapper.writeValueAsString(body), CardUpdateRequest.class);

            // The token is what crosses; the area is what it means. A request binds the token verbatim and
            // the controller's unseal recovers the area, field for field.
            assertThat(bound.getStateToken()).isEqualTo(response.getStateToken());
            assertThat(unsealed(bound.getStateToken())).isEqualTo(response.getCommArea());
        }

        @Test
        @DisplayName("the token is opaque: it publishes neither the change action nor the CVV")
        void theTokenPublishesNothingItCarries() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            CardUpdateResponse response = sealed(populatedResponse());

            String json = mapper.writeValueAsString(response);

            // CCUP-OLD-DETAILS and CARD-UPDATE-RECORD both carry CARD-CVV-CD, and no DFHMDF field of
            // app/bms/COCRDUP.bms paints it, so a terminal never sees it and neither does this payload.
            assertThat(json).doesNotContain("\"" + CVV_CODE + "\"");
            assertThat(json).doesNotContain("changeAction");
            assertThat(json).doesNotContain("oldDetails").doesNotContain("newDetails");
            assertThat(json).doesNotContain("cardUpdateRecord");
            // What is there is a token, and it is not the area in disguise: the area's own bytes do not
            // appear in it.
            assertThat(mapper.readTree(json).get("stateToken").asText())
                    .isNotEmpty()
                    .doesNotContain(CARD_NUMBER);
        }

        @Test
        @DisplayName("the 329-byte image is byte-identical on both sides of the pair")
        void theImageIsIdenticalAcrossThePair() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            CardUpdateResponse response = sealed(populatedResponse());
            ObjectNode body = mapper.createObjectNode();
            body.set("stateToken", mapper.valueToTree(response).get("stateToken"));
            CardUpdateRequest bound =
                    mapper.readValue(mapper.writeValueAsString(body), CardUpdateRequest.class);

            CommArea recovered = unsealed(bound.getStateToken());
            assertThat(recovered.encode(ASCII))
                    .hasSize(CommArea.RECORD_LENGTH)
                    .isEqualTo(response.getCommArea().encode(ASCII));
            assertThat(recovered.encode(EBCDIC))
                    .isEqualTo(response.getCommArea().encode(EBCDIC));
        }

        @Test
        @DisplayName("the cardScreenState node binds into a request unchanged")
        void theWorkAreaCrossesThePair() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            CardUpdateResponse response = populatedResponse();
            ObjectNode body = mapper.createObjectNode();
            body.set("cardScreenState", mapper.valueToTree(response).get("cardScreenState"));
            CardUpdateRequest bound =
                    mapper.readValue(mapper.writeValueAsString(body), CardUpdateRequest.class);

            assertThat(bound.getCardScreenState().getCcardNextProg())
                    .isEqualTo(response.getCardScreenState().getCcardNextProg());
            assertThat(bound.getCardScreenState().getCcCardNum())
                    .isEqualTo(response.getCardScreenState().getCcCardNum());
        }

        @Test
        @DisplayName("the navigationContext node binds into a request unchanged")
        void theNavigationCommareaCrossesThePair() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            CardUpdateResponse response = populatedResponse();
            ObjectNode body = mapper.createObjectNode();
            body.set("navigationContext", mapper.valueToTree(response).get("navigationContext"));
            CardUpdateRequest bound =
                    mapper.readValue(mapper.writeValueAsString(body), CardUpdateRequest.class);

            assertThat(bound.getNavigationContext()).isEqualTo(response.getNavigationContext());
            assertThat(bound.hasNavigationContext()).isTrue();
            assertThat(bound.commareaLength())
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH);
        }

        @Test
        @DisplayName("a request echoing only the program area still reports the cold start")
        void echoingOnlyTheProgramAreaLeavesTheColdStartVisible() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            CardUpdateResponse response = sealed(populatedResponse());
            ObjectNode body = mapper.createObjectNode();
            body.set("stateToken", mapper.valueToTree(response).get("stateToken"));
            CardUpdateRequest bound =
                    mapper.readValue(mapper.writeValueAsString(body), CardUpdateRequest.class);

            // EIBCALEN is about CARDDEMO-COMMAREA, and the token is not it: a body carrying the program's
            // own area and no navigation context is still the EIBCALEN = 0 arm of :388.
            assertThat(bound.getNavigationContext()).isNull();
            assertThat(bound.hasNavigationContext()).isFalse();
            assertThat(bound.commareaLength()).isZero();
            assertThat(unsealed(bound.getStateToken())).isEqualTo(populatedResponse().getCommArea());
        }

        @Test
        @DisplayName("a whole response round-trips through JSON and compares equal")
        void aWholeResponseRoundTrips() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            CardUpdateResponse response = sealed(populatedResponse());
            CardUpdateResponse restored = mapper.readValue(
                    mapper.writeValueAsString(response), CardUpdateResponse.class);

            // The area is not a JSON member any more, so a round trip recovers it from the token rather
            // than from a nested object. Restoring it is what makes the two comparable, and it is exactly
            // what the controller's unseal does on the next turn.
            restored.setCommArea(unsealed(restored.getStateToken()));

            assertThat(restored).isEqualTo(response);
            assertThat(restored.hashCode()).isEqualTo(response.hashCode());
        }
    }

    @Nested
    @DisplayName("CCUP-CHANGE-ACTION - the 88-level the response reports, COCRDUPC.cbl:276-290")
    class ChangeActionReporting {
        @Test
        @DisplayName("a fresh response reports CCUP-DETAILS-NOT-FETCHED")
        void aFreshResponseReportsDetailsNotFetched() {
            assertThat(new CardUpdateResponse().toString())
                    .contains("changeAction=CCUP-DETAILS-NOT-FETCHED");
        }

        @ParameterizedTest(name = "{0} is reported as {1}")
        @MethodSource(
                "com.vsergeychik.carddemo.card.dto.CardUpdateResponseTest#changeActionStates")
        @DisplayName("every 88-level is named in the diagnostic, in declaration order")
        void everyConditionNameIsReported(ChangeAction action, String expected) {
            CardUpdateResponse response = new CardUpdateResponse();
            response.setCommArea(CommArea.initialised().withChangeAction(action));

            assertThat(response.toString()).contains("changeAction=" + expected);
        }

        @Test
        @DisplayName("a byte no 88-level covers is reported as unrecognised rather than guessed")
        void anUncoveredByteIsReportedAsUnrecognised() {
            CardUpdateResponse response = new CardUpdateResponse();
            response.setCommArea(CommArea.initialised().withChangeAction(ChangeAction.of('Z')));

            assertThat(response.toString()).contains("changeAction=UNRECOGNISED('Z')");
        }

        @Test
        @DisplayName("the two umbrella 88-levels are never reported, the specific name always is")
        void theUmbrellaConditionsAreNotReported() {
            CardUpdateResponse response = new CardUpdateResponse();

            for (ChangeAction action : List.of(ChangeAction.changesNotOk(),
                    ChangeAction.changesOkNotConfirmed(), ChangeAction.changesOkayedAndDone(),
                    ChangeAction.changesOkayedLockError(), ChangeAction.changesOkayedButFailed())) {
                response.setCommArea(CommArea.initialised().withChangeAction(action));

                assertThat(action.isChangesMade())
                        .as("CCUP-CHANGES-MADE at COCRDUPC.cbl:282 holds for all five")
                        .isTrue();
                assertThat(response.toString())
                        .doesNotContain("CCUP-CHANGES-MADE")
                        .doesNotContain("changeAction=CCUP-CHANGES-FAILED");
            }
            assertThat(ChangeAction.changesOkayedLockError().isChangesFailed()).isTrue();
            assertThat(ChangeAction.changesOkayedButFailed().isChangesFailed()).isTrue();
        }

        @Test
        @DisplayName("the diagnostic names the map, the group width and the XCTL triple")
        void theDiagnosticNamesTheStructure() {
            CardUpdateResponse response = populatedResponse();

            assertThat(response.toString())
                    .startsWith("CardUpdateResponse[COCRDUP/CCRDUPAO 484B, txn=CCUP")
                    .contains("next=COCRDLIC/COCRDLI/CCRDLIA")
                    .endsWith("]");
        }

        @Test
        @DisplayName("the diagnostic reports structure only - no card number reaches it")
        void theDiagnosticCarriesNoCardNumber() {
            assertThat(populatedResponse().toString()).doesNotContain(CARD_NUMBER);
        }
    }

    @Nested
    @DisplayName("The 17 xxxO items against the named DFHMDF entries, and 12 + 17 x 7 + 353 = 484")
    class NamedFieldProjection {
        @ParameterizedTest(name = "{0}O is {1} wide, DFHMDF LENGTH={1} at POS={2}")
        @MethodSource("com.vsergeychik.carddemo.card.dto.CardUpdateResponseTest#namedFieldTriples")
        @DisplayName("every payload width equals its xxxO PICTURE and its DFHMDF LENGTH (G9)")
        void everyPayloadWidthMatchesBothSources(String label, int width, String bmsPos) {
            ScreenField field = CardUpdateResponse.fieldOf(label);

            assertThat(field.length()).as("%sO declared width at POS=%s", label, bmsPos)
                    .isEqualTo(width);
            assertThat(field.outputItemSpan().length()).isEqualTo(width);
            assertThat(field.outputItemSpan().name()).isEqualTo(label + "O");

            CardUpdateResponse response = new CardUpdateResponse();
            response.setOutputItem(label, "");
            assertThat(response.outputItemOf(label)).hasSize(width);
        }

        @Test
        @DisplayName("17 named entries of the mapset's 34 DFHMDF entries, in declaration order")
        void seventeenNamedEntriesOfThirtyFour() {
            assertThat(CardUpdateResponse.NAMED_FIELD_COUNT).isEqualTo(17);
            assertThat(CardUpdateResponse.DFHMDF_TOTAL_COUNT).isEqualTo(34);
            assertThat(CardUpdateResponse.namedFieldPrefixes())
                    .hasSize(17)
                    .containsExactlyElementsOf(namedFieldTriples().stream()
                            .map(row -> (String) row.get()[0])
                            .toList());
        }

        @Test
        @DisplayName("the 17 widths sum to 353 and the group image is 484 = 12 + 17 x 7 + 353")
        void theWidthsSumToThreeHundredAndFiftyThree() {
            int transcribedSum = 0;
            for (Arguments row : namedFieldTriples()) {
                transcribedSum += (int) row.get()[1];
            }

            assertThat(transcribedSum).isEqualTo(353);
            assertThat(CardUpdateResponse.PAYLOAD_LENGTH).isEqualTo(353);

            assertThat(CardUpdateResponse.TIOAPFX_LENGTH).isEqualTo(12);
            assertThat(CardUpdateResponse.FIELD_PREFIX_FILLER_LENGTH).isEqualTo(3);
            assertThat(CardUpdateResponse.ATTRIBUTE_QUAD_LENGTH).isEqualTo(4);
            assertThat(CardUpdateResponse.ITEM_OVERHEAD_LENGTH).isEqualTo(7);
            assertThat(12 + 17 * 7 + 353).isEqualTo(484);
            assertThat(CardUpdateResponse.GROUP_LENGTH).isEqualTo(484);
        }

        @Test
        @DisplayName("the request and the response are both 484, which is what the REDEFINES forces")
        void theRequestAndTheResponseAreTheSameWidth() {
            assertThat(CardUpdateResponse.GROUP_LENGTH).isEqualTo(CardUpdateRequest.GROUP_LENGTH);

            assertThat(CardUpdateRequest.FIELD_METADATA_LENGTH).isEqualTo(7);
            assertThat(CardUpdateResponse.ITEM_OVERHEAD_LENGTH)
                    .isEqualTo(CardUpdateRequest.FIELD_METADATA_LENGTH);
            assertThat(CardUpdateResponse.FIELD_PREFIX_FILLER_LENGTH
                    + CardUpdateResponse.ATTRIBUTE_QUAD_LENGTH).isEqualTo(2 + 1 + 4);

            assertThat(CardUpdateResponse.TIOAPFX_LENGTH).isEqualTo(CardUpdateRequest.TIOAPFX_LENGTH);
            assertThat(CardUpdateResponse.PAYLOAD_LENGTH)
                    .isEqualTo(CardUpdateRequest.NAMED_FIELD_TOTAL_LENGTH);
            assertThat(CardUpdateResponse.NAMED_FIELD_COUNT)
                    .isEqualTo(CardUpdateRequest.NAMED_FIELD_COUNT);
            assertThat(CardUpdateResponse.DFHMDF_TOTAL_COUNT)
                    .isEqualTo(CardUpdateRequest.TOTAL_DFHMDF_COUNT);
        }

        @Test
        @DisplayName("EXPMONO 2, EXPYEARO 4 and EXPDAYO 2 are three fields, and '/' is not a member")
        void theExpiryDateIsThreeSeparateFields() {
            ScreenField expmon = CardUpdateResponse.fieldOf(CardUpdateResponse.EXPMON);
            ScreenField expyear = CardUpdateResponse.fieldOf(CardUpdateResponse.EXPYEAR);
            ScreenField expday = CardUpdateResponse.fieldOf(CardUpdateResponse.EXPDAY);

            assertThat(expmon.length()).isEqualTo(2);
            assertThat(expyear.length()).isEqualTo(4);
            assertThat(expday.length()).isEqualTo(2);
            assertThat(List.of(expmon.name(), expyear.name(), expday.name()))
                    .containsExactly("EXPMON", "EXPYEAR", "EXPDAY").doesNotHaveDuplicates();
            assertThat(List.of(expmon.fieldOffset(), expyear.fieldOffset(), expday.fieldOffset()))
                    .doesNotHaveDuplicates();

            assertThat(CardUpdateResponse.declaresField("/")).isFalse();
            assertThat(CardUpdateResponse.namedFieldPrefixes()).doesNotContain("/");

            assertThat(CardUpdateResponse.declaresField("EXPDAY")).isTrue();
            assertThat(new CardUpdateResponse().getExpdayo()).hasSize(2);
        }

        @ParameterizedTest(name = "{0}O pads a short sender to {1} and truncates a long one on the right")
        @MethodSource("com.vsergeychik.carddemo.card.dto.CardUpdateResponseTest#namedFieldTriples")
        @DisplayName("the PIC X move rule holds for all 17: right-pad short, right-truncate long")
        void thePicXMoveRuleHoldsForAllSeventeen(String label, int width, String bmsPos) {
            CardUpdateResponse response = new CardUpdateResponse();

            response.setOutputItem(label, "A");
            assertThat(response.outputItemOf(label))
                    .as("%sO at POS=%s pads on the right", label, bmsPos)
                    .isEqualTo(ASCII.movePicX("A", width))
                    .isEqualTo("A" + " ".repeat(width - 1));

            String overWide = "B".repeat(width + 3);
            response.setOutputItem(label, overWide);
            assertThat(response.outputItemOf(label))
                    .isEqualTo(ASCII.movePicX(overWide, width))
                    .isEqualTo("B".repeat(width))
                    .hasSize(width);
        }

        @Test
        @DisplayName("every FILLER span is declared and the image is exactly 484 bytes (G21)")
        void everyFillerSpanIsDeclaredAndCounted() {
            List<FieldSpan> spans = CardUpdateResponse.OUTPUT_GROUP_LAYOUT.spans();
            long fillerSpans = spans.stream().filter(span -> span.kind().filler()).count();
            int fillerBytes = spans.stream().filter(span -> span.kind().filler())
                    .mapToInt(FieldSpan::length).sum();

            assertThat(fillerSpans).isEqualTo(18);
            assertThat(fillerBytes).isEqualTo(12 + 17 * 3).isEqualTo(63);

            assertThat(spans).hasSize(1 + 17 * 6);
            assertThat(CardUpdateResponse.OUTPUT_GROUP_LAYOUT.recordLength()).isEqualTo(484);
            assertThat(spans.stream().mapToInt(FieldSpan::length).sum()).isEqualTo(484);
            assertThat(new CardUpdateResponse().toFixedWidth(StandardCharsets.US_ASCII))
                    .hasSize(484);
        }
    }

    @Nested
    @DisplayName("The wire projection - 17 display members, and not one attribute byte among them")
    class WireProjection {
        @Test
        @DisplayName("JSON carries a member for each of the 17 display items, FKEYSCO included")
        void jsonCarriesEveryDisplayItem() {
            JsonNode node = new ObjectMapper().valueToTree(populatedResponse());

            assertThat(node.has("trnname")).isTrue();
            assertThat(node.has("title01")).isTrue();
            assertThat(node.has("curdate")).isTrue();
            assertThat(node.has("pgmname")).isTrue();
            assertThat(node.has("title02")).isTrue();
            assertThat(node.has("curtime")).isTrue();
            assertThat(node.has("acctsid")).isTrue();
            assertThat(node.has("cardsid")).isTrue();
            assertThat(node.has("crdname")).isTrue();
            assertThat(node.has("crdstcd")).isTrue();
            assertThat(node.has("expmon")).isTrue();
            assertThat(node.has("expyear")).isTrue();
            assertThat(node.has("expday")).isTrue();
            assertThat(node.has("infomsg")).isTrue();
            assertThat(node.has("errmsg")).isTrue();
            assertThat(node.has("fkeys")).isTrue();
            assertThat(node.has("fkeysc")).isTrue();

            assertThat(node.get("cardsid").asText()).isEqualTo(CARD_NUMBER);
            assertThat(node.get("crdname").asText()).startsWith(EMBOSSED_NAME);
        }

        @Test
        @DisplayName("no xxxC, xxxP, xxxH or xxxV item is a JSON member - FKEYSC and FKEYSCC included")
        void noAttributeItemIsAJsonMember() {
            List<String> members = membersOf(populatedResponse());

            for (String label : CardUpdateResponse.namedFieldPrefixes()) {
                for (String suffix : List.of(CardUpdateResponse.COLOUR_ITEM_SUFFIX,
                        CardUpdateResponse.PS_ITEM_SUFFIX, CardUpdateResponse.HILIGHT_ITEM_SUFFIX,
                        CardUpdateResponse.VALIDN_ITEM_SUFFIX)) {
                    String item = label + suffix;
                    assertThat(members).doesNotContain(item);
                    if (!CardUpdateResponse.namedFieldPrefixes().contains(item)) {
                        assertThat(members).doesNotContain(item.toLowerCase(Locale.ROOT));
                    }
                }
            }

            assertThat(members).doesNotContain("FKEYSC", "FKEYSCC", "fkeyscc", "fkeyscp",
                    "fkeysch", "fkeyscv", "fkeysp", "fkeysh", "fkeysv");
            assertThat(members).contains("fkeysc", "fkeys");
        }

        @Test
        @DisplayName("the quad is exactly four items per field - the DSATTS derivation, 68 in all")
        void theQuadIsExactlyFourItemsPerField() {
            assertThat(CardUpdateResponse.ATTRIBUTE_QUAD_LENGTH).isEqualTo(4);
            assertThat(List.of(CardUpdateResponse.COLOUR_ITEM_SUFFIX,
                    CardUpdateResponse.PS_ITEM_SUFFIX, CardUpdateResponse.HILIGHT_ITEM_SUFFIX,
                    CardUpdateResponse.VALIDN_ITEM_SUFFIX)).containsExactly("C", "P", "H", "V");

            Map<String, String> attributes = new CardUpdateResponse().attributeImages();
            assertThat(attributes).hasSize(17 * 4).hasSize(68);
            for (ScreenField field : CardUpdateResponse.namedFields()) {
                assertThat(attributes).containsKeys(field.colourItemName(), field.psItemName(),
                        field.hilightItemName(), field.validnItemName());
            }
        }

        @Test
        @DisplayName("the FILLER spans are not JSON members yet are counted in the 484-byte image")
        void theFillerSpansAreOffTheWireAndInTheImage() {
            List<String> members = membersOf(new CardUpdateResponse());

            assertThat(members).doesNotContain("FILLER", "filler", "tioapfx", "TIOAPFX");
            for (String label : CardUpdateResponse.namedFieldPrefixes()) {
                assertThat(members).doesNotContain(label + "FILLER");
            }

            byte[] image = new CardUpdateResponse().toFixedWidth(StandardCharsets.US_ASCII);
            assertThat(image).hasSize(484);
            assertThat(484 - 12 - 17 * 3).isEqualTo(353 + 17 * 4);
        }

        @Test
        @DisplayName("an attribute byte reaches the image as a raw byte, never as decoded text")
        void anAttributeByteIsNeverCodePageDecoded() {
            CardUpdateResponse response = new CardUpdateResponse();
            response.setColour(CardUpdateResponse.FKEYSC, BmsAttributes.DFHRED);
            ScreenField fkeysc = CardUpdateResponse.fieldOf(CardUpdateResponse.FKEYSC);

            byte[] ascii = response.toFixedWidth(StandardCharsets.US_ASCII);
            byte[] ebcdic = response.toFixedWidth(IBM037);

            assertThat(ascii[fkeysc.colourItemOffset()]).isEqualTo(BmsAttributes.DFHRED);
            assertThat(ebcdic[fkeysc.colourItemOffset()]).isEqualTo(BmsAttributes.DFHRED);
            assertThat(response.attributeImages().get("FKEYSCC"))
                    .isEqualTo(BmsAttributes.toHex(BmsAttributes.DFHRED));
        }
    }

    @Nested
    @DisplayName("Declared geometry - 17 xxxO items of 34 DFHMDF entries, 353 and 484")
    class DeclaredGeometry {
        @Test
        @DisplayName("17 name-labelled fields of 34 entries, in mapset declaration order")
        void seventeenNamedFieldsInDeclarationOrder() {
            assertThat(CardUpdateResponse.NAMED_FIELD_COUNT).isEqualTo(17);
            assertThat(CardUpdateResponse.DFHMDF_TOTAL_COUNT).isEqualTo(34);
            assertThat(CardUpdateResponse.namedFieldPrefixes())
                    .containsExactlyElementsOf(FIELD_NAMES);
            assertThat(CardUpdateResponse.namedFields()).hasSize(17);
        }

        @Test
        @DisplayName("the widths come from the xxxO PICTURE clauses")
        void everyWidthComesFromItsPictureClause() {
            assertThat(CardUpdateResponse.namedFields())
                    .extracting(ScreenField::name, ScreenField::length)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("TRNNAME", 4),
                            org.assertj.core.groups.Tuple.tuple("TITLE01", 40),
                            org.assertj.core.groups.Tuple.tuple("CURDATE", 8),
                            org.assertj.core.groups.Tuple.tuple("PGMNAME", 8),
                            org.assertj.core.groups.Tuple.tuple("TITLE02", 40),
                            org.assertj.core.groups.Tuple.tuple("CURTIME", 8),
                            org.assertj.core.groups.Tuple.tuple("ACCTSID", 11),
                            org.assertj.core.groups.Tuple.tuple("CARDSID", 16),
                            org.assertj.core.groups.Tuple.tuple("CRDNAME", 50),
                            org.assertj.core.groups.Tuple.tuple("CRDSTCD", 1),
                            org.assertj.core.groups.Tuple.tuple("EXPMON", 2),
                            org.assertj.core.groups.Tuple.tuple("EXPYEAR", 4),
                            org.assertj.core.groups.Tuple.tuple("EXPDAY", 2),
                            org.assertj.core.groups.Tuple.tuple("INFOMSG", 40),
                            org.assertj.core.groups.Tuple.tuple("ERRMSG", 80),
                            org.assertj.core.groups.Tuple.tuple("FKEYS", 21),
                            org.assertj.core.groups.Tuple.tuple("FKEYSC", 18));
        }

        @Test
        @DisplayName("12 + 353 + 17 x 7 = 484, and RecordLayout machine-checks it")
        void theGroupIsFourHundredAndEightyFourBytes() {
            assertThat(CardUpdateResponse.PAYLOAD_LENGTH).isEqualTo(353);
            assertThat(CardUpdateResponse.TIOAPFX_LENGTH).isEqualTo(12);
            assertThat(CardUpdateResponse.ITEM_OVERHEAD_LENGTH).isEqualTo(7);
            assertThat(CardUpdateResponse.GROUP_LENGTH).isEqualTo(484);
            assertThat(CardUpdateResponse.TIOAPFX_LENGTH + CardUpdateResponse.PAYLOAD_LENGTH
                    + 17 * CardUpdateResponse.ITEM_OVERHEAD_LENGTH).isEqualTo(484);
        }

        @Test
        @DisplayName("the item names are built from the copybook suffixes, not spelled out")
        void theItemNamesAreBuiltFromTheSuffixes() {
            ScreenField acctsid = CardUpdateResponse.fieldOf("ACCTSID");

            assertThat(acctsid.outputItemName()).isEqualTo("ACCTSIDO");
            assertThat(acctsid.colourItemName()).isEqualTo("ACCTSIDC");
            assertThat(acctsid.psItemName()).isEqualTo("ACCTSIDP");
            assertThat(acctsid.hilightItemName()).isEqualTo("ACCTSIDH");
            assertThat(acctsid.validnItemName()).isEqualTo("ACCTSIDV");
        }

        @Test
        @DisplayName("every field's spans are contiguous and end where the next field begins")
        void theSpansAreContiguous() {
            int expected = CardUpdateResponse.TIOAPFX_LENGTH;
            for (ScreenField field : CardUpdateResponse.namedFields()) {
                assertThat(field.fieldOffset()).isEqualTo(expected);
                assertThat(field.prefixFillerOffset()).isEqualTo(expected);
                assertThat(field.colourItemOffset())
                        .isEqualTo(expected + CardUpdateResponse.FIELD_PREFIX_FILLER_LENGTH);
                assertThat(field.psItemOffset()).isEqualTo(field.colourItemOffset() + 1);
                assertThat(field.hilightItemOffset()).isEqualTo(field.psItemOffset() + 1);
                assertThat(field.validnItemOffset()).isEqualTo(field.hilightItemOffset() + 1);
                assertThat(field.outputItemOffset()).isEqualTo(field.validnItemOffset() + 1);
                assertThat(field.endOffsetExclusive())
                        .isEqualTo(field.outputItemOffset() + field.length());
                expected = field.endOffsetExclusive();
            }
            assertThat(expected).isEqualTo(CardUpdateResponse.GROUP_LENGTH);
        }

        @Test
        @DisplayName("each ScreenField describes itself with its own spans")
        void eachScreenFieldDescribesItself() {
            ScreenField trnname = CardUpdateResponse.fieldOf("TRNNAME");

            assertThat(trnname.describe()).contains("TRNNAME").contains("TRNNAMEO");
            assertThat(trnname.prefixFillerSpan().length())
                    .isEqualTo(CardUpdateResponse.FIELD_PREFIX_FILLER_LENGTH);
            assertThat(trnname.colourItemSpan().length()).isEqualTo(1);
            assertThat(trnname.psItemSpan().length()).isEqualTo(1);
            assertThat(trnname.hilightItemSpan().length()).isEqualTo(1);
            assertThat(trnname.validnItemSpan().length()).isEqualTo(1);
        }

        @ParameterizedTest(name = "declaresField(\"{0}\") is true")
        @ValueSource(strings = {"TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME",
                "ACCTSID", "CARDSID", "CRDNAME", "CRDSTCD", "EXPMON", "EXPYEAR", "EXPDAY",
                "INFOMSG", "ERRMSG", "FKEYS", "FKEYSC"})
        @DisplayName("every declared label is recognised by name")
        void everyDeclaredLabelIsRecognised(String name) {
            assertThat(CardUpdateResponse.declaresField(name)).isTrue();
            assertThat(CardUpdateResponse.fieldOf(name).name()).isEqualTo(name);
        }

        @Test
        @DisplayName("a label this map does not declare is refused by name, not defaulted")
        void anUndeclaredLabelIsRefused() {
            assertThat(CardUpdateResponse.declaresField("CRDSTP1")).isFalse();
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardUpdateResponse.fieldOf("CRDSTP1"))
                    .withMessageContaining("CRDSTP1");
        }

        @Test
        @DisplayName("declaresField takes null as simply not a label, and fieldOf refuses it")
        void declaresFieldTakesNullAsNotALabel() {
            assertThat(CardUpdateResponse.declaresField(null)).isFalse();
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> CardUpdateResponse.fieldOf(null));
        }

        @Test
        @DisplayName("the field list and the prefix list are unmodifiable views")
        void theFieldListsAreUnmodifiable() {
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> CardUpdateResponse.namedFields().clear());
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> CardUpdateResponse.namedFieldPrefixes().clear());
        }

        @Test
        @DisplayName("a ScreenField refuses a blank name, a non-positive width or line, or an "
                + "offset inside the TIOAPFX prefix")
        void screenFieldRefusesAnImpossibleDeclaration() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new ScreenField("  ", 4, 128, 12))
                    .withMessageContaining("name");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new ScreenField("TRNNAME", 0, 128, 12));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new ScreenField("TRNNAME", 4, 0, 12));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new ScreenField("TRNNAME", 4, 128, 11))
                    .withMessageContaining(String.valueOf(CardUpdateResponse.TIOAPFX_LENGTH));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new ScreenField(null, 4, 128, 12));
        }

        @Test
        @DisplayName("FKEYS and FKEYSC are two fields with two independent quads")
        void theFkeyscCollisionIsModelledAsTwoFields() {
            ScreenField fkeys = CardUpdateResponse.fieldOf("FKEYS");
            ScreenField fkeysc = CardUpdateResponse.fieldOf("FKEYSC");

            assertThat(fkeys.length()).isEqualTo(21);
            assertThat(fkeysc.length()).isEqualTo(18);
            assertThat(fkeys.colourItemName()).isEqualTo("FKEYSC");
            assertThat(fkeysc.colourItemName()).isEqualTo("FKEYSCC");
            assertThat(fkeys.outputItemName()).isEqualTo("FKEYSO");
            assertThat(fkeysc.outputItemName()).isEqualTo("FKEYSCO");
            assertThat(fkeys.fieldOffset()).isNotEqualTo(fkeysc.fieldOffset());
        }

        @Test
        @DisplayName("both FKEYSC readings appear in the attribute image and neither shadows the other")
        void bothFkeyscReadingsAppearInTheAttributeImage() {
            Map<String, String> attributes = new CardUpdateResponse().attributeImages();

            assertThat(attributes).hasSize(68);
            assertThat(attributes).containsKeys("FKEYSC", "FKEYSCC", "FKEYSCP", "FKEYSCH",
                    "FKEYSCV", "FKEYSP", "FKEYSH", "FKEYSV");
        }

        @Test
        @DisplayName("the identity constants come from the mapset and the CSD")
        void theIdentityConstantsAreTheSourceOnes() {
            assertThat(CardUpdateResponse.MAPSET_NAME).isEqualTo("COCRDUP");
            assertThat(CardUpdateResponse.MAP_NAME).isEqualTo("CCRDUPA");
            assertThat(CardUpdateResponse.INPUT_GROUP_NAME).isEqualTo("CCRDUPAI");
            assertThat(CardUpdateResponse.OUTPUT_GROUP_NAME).isEqualTo("CCRDUPAO");
            assertThat(CardUpdateResponse.TRANSACTION_ID).isEqualTo("CCUP");
            assertThat(CardUpdateResponse.PROGRAM_NAME).isEqualTo("COCRDUPC");
        }
    }

    @Nested
    @DisplayName("The send path - 3100-SCREEN-INIT and the PIC X moves it performs")
    class SendPath {
        @Test
        @DisplayName("a fresh response holds LOW-VALUES in all 17 items and 0x00 in all 68 bytes")
        void aFreshResponseIsLowValues() {
            CardUpdateResponse response = new CardUpdateResponse();

            assertThat(response.getTrnnameo()).isEqualTo("\u0000\u0000\u0000\u0000");
            assertThat(response.getCardsido()).isEqualTo("\u0000".repeat(16));
            for (String hex : response.attributeImages().values()) {
                assertThat(hex).isEqualTo(BmsAttributes.toHex((byte) 0x00));
            }
        }

        @Test
        @DisplayName("screenInit moves LOW-VALUES, the titles, CCUP and COCRDUPC, then the clock")
        void screenInitReproducesTheSendPath() {
            CardUpdateResponse response = new CardUpdateResponse();
            response.setAcctsido("00000000011");

            response.screenInit(fixedDateHeader());

            assertThat(response.getTrnnameo()).isEqualTo("CCUP");
            assertThat(response.getPgmnameo()).isEqualTo("COCRDUPC");
            assertThat(response.getTitle01o()).isEqualTo(ScreenTitles.CCDA_TITLE01);
            assertThat(response.getTitle02o()).isEqualTo(ScreenTitles.CCDA_TITLE02);
            assertThat(response.getCurdateo()).isEqualTo(FIXED_CURDATE).isEqualTo("07/19/22");
            assertThat(response.getCurtimeo()).isEqualTo(FIXED_CURTIME).isEqualTo("23:15:58");
            assertThat(response.getAcctsido()).isEqualTo("\u0000".repeat(11));
        }

        @Test
        @DisplayName("screenInit refuses a missing clock reading rather than taking one itself")
        void screenInitRefusesAMissingClock() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new CardUpdateResponse().screenInit(null))
                    .withMessageContaining("3100-SCREEN-INIT");
        }

        @Test
        @DisplayName("applyDateTimeHeader refuses a missing clock reading")
        void applyDateTimeHeaderRefusesAMissingClock() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new CardUpdateResponse().applyDateTimeHeader(null))
                    .withMessageContaining("CURDATEO");
        }

        @Test
        @DisplayName("moveLowValuesToGroup resets the group and leaves the carriers alone")
        void moveLowValuesToGroupIsScopedToTheGroup() {
            CardUpdateResponse response = populatedResponse();
            CommArea area = response.getCommArea();
            CardScreenState state = response.getCardScreenState();

            response.moveLowValuesToGroup();

            assertThat(response.getCardsido()).isEqualTo("\u0000".repeat(16));
            assertThat(response.getTitle01o()).isEqualTo("\u0000".repeat(40));
            assertThat(response.getCommArea()).isSameAs(area);
            assertThat(response.getCardScreenState()).isSameAs(state);
            assertThat(response.getNextProgram()).isEqualTo("COCRDLIC");
        }

        @Test
        @DisplayName("applyScreenTitles takes the literals from COTTL01Y rather than retyping them")
        void applyScreenTitlesUsesTheSharedLiterals() {
            CardUpdateResponse response = new CardUpdateResponse();

            response.applyScreenTitles();

            assertThat(response.getTitle01o()).isEqualTo(ScreenTitles.CCDA_TITLE01).hasSize(40);
            assertThat(response.getTitle02o()).isEqualTo(ScreenTitles.CCDA_TITLE02).hasSize(40);
        }

        @ParameterizedTest(name = "a short value is padded to {1} on {0}")
        @CsvSource({"ACCTSID,11", "CARDSID,16", "CRDNAME,50", "INFOMSG,40", "ERRMSG,80",
                "FKEYS,21", "FKEYSC,18"})
        @DisplayName("a short value is right-padded to the declared width, the PIC X move rule")
        void aShortValueIsPadded(String name, int width) {
            CardUpdateResponse response = new CardUpdateResponse();

            response.setOutputItem(name, "X");

            assertThat(response.outputItemOf(name)).isEqualTo("X" + " ".repeat(width - 1));
        }

        @ParameterizedTest(name = "an over-wide value is right-truncated to {1} on {0}")
        @CsvSource({"ACCTSID,11", "CARDSID,16", "CRDSTCD,1", "EXPMON,2"})
        @DisplayName("an over-wide value is truncated on the right, the PIC X move rule")
        void anOverWideValueIsTruncated(String name, int width) {
            CardUpdateResponse response = new CardUpdateResponse();

            response.setOutputItem(name, "9".repeat(width + 5));

            assertThat(response.outputItemOf(name)).isEqualTo("9".repeat(width));
        }

        @Test
        @DisplayName("ERRMSGO is 80 while WS-RETURN-MSG is 75, so the move pads by five")
        void theErrorMessageMoveIsAPad() {
            CardUpdateResponse response = new CardUpdateResponse();

            response.setErrmsgo("Y".repeat(75));

            assertThat(response.getErrmsgo()).hasSize(80).endsWith("     ");
        }

        @Test
        @DisplayName("every payload setter rejects null, because COBOL has no absent state")
        void everyPayloadSetterRejectsNull() {
            CardUpdateResponse response = new CardUpdateResponse();

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> response.setCardsido(null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> response.setOutputItem("ACCTSID", null));
        }

        @Test
        @DisplayName("setOutputItem and outputItemOf refuse a label this map does not declare")
        void theOutputItemAccessorsRefuseAnUnknownLabel() {
            CardUpdateResponse response = new CardUpdateResponse();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.setOutputItem("NOSUCH", "x"))
                    .withMessageContaining("NOSUCH");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.outputItemOf("NOSUCH"));
        }

        @Test
        @DisplayName("fieldImages is keyed by xxxO name, in declaration order, 17 entries")
        void fieldImagesIsKeyedByOutputItemName() {
            Map<String, String> images = populatedResponse().fieldImages();

            assertThat(images).hasSize(17);
            assertThat(images.keySet()).containsExactly("TRNNAMEO", "TITLE01O", "CURDATEO",
                    "PGMNAMEO", "TITLE02O", "CURTIMEO", "ACCTSIDO", "CARDSIDO", "CRDNAMEO",
                    "CRDSTCDO", "EXPMONO", "EXPYEARO", "EXPDAYO", "INFOMSGO", "ERRMSGO", "FKEYSO",
                    "FKEYSCO");
            assertThat(images.get("CARDSIDO")).isEqualTo(CARD_NUMBER);
        }

        @Test
        @DisplayName("fieldImages and attributeImages are unmodifiable views")
        void theImageViewsAreUnmodifiable() {
            CardUpdateResponse response = new CardUpdateResponse();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> response.fieldImages().put("TRNNAMEO", "x"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> response.attributeImages().put("TRNNAMEC", "x"));
        }
    }

    @Nested
    @DisplayName("The attribute quad - addressable metadata, never a JSON member")
    class AttributeQuad {
        @Test
        @DisplayName("a fresh quad is four 0x00 bytes and describes itself as such")
        void aFreshQuadIsAllZero() {
            FieldAttributes quad = new FieldAttributes();

            assertThat(quad.getColour()).isZero();
            assertThat(quad.getPs()).isZero();
            assertThat(quad.getHilight()).isZero();
            assertThat(quad.getValidn()).isZero();
            assertThat(quad.describe()).isNotBlank();
        }

        @Test
        @DisplayName("the four-argument form and the copy form both carry all four bytes")
        void theQuadConstructorsCarryAllFourBytes() {
            FieldAttributes quad = new FieldAttributes((byte) 0xF2, (byte) 0x01, (byte) 0x02,
                    (byte) 0x03);
            FieldAttributes copy = new FieldAttributes(quad);

            assertThat(copy).isEqualTo(quad);
            assertThat(copy.hashCode()).isEqualTo(quad.hashCode());
            assertThat(copy.getColour()).isEqualTo((byte) 0xF2);
            assertThat(copy.getPs()).isEqualTo((byte) 0x01);
            assertThat(copy.getHilight()).isEqualTo((byte) 0x02);
            assertThat(copy.getValidn()).isEqualTo((byte) 0x03);
        }

        @Test
        @DisplayName("isDefault is true only when all four bytes are 0x00 - one per byte, four ways")
        void isDefaultAnswersForEachOfTheFourBytesIndependently() {
            assertThat(new FieldAttributes().isDefault()).isTrue();
            assertThat(BmsAttributes.DFHDFCOL).isEqualTo(FieldAttributes.DEFAULT);
            assertThat(BmsAttributes.DFHDFHI).isEqualTo(FieldAttributes.DEFAULT);

            FieldAttributes colourMoved = new FieldAttributes();
            colourMoved.setColour(BmsAttributes.DFHRED);
            assertThat(colourMoved.isDefault()).isFalse();

            FieldAttributes psMoved = new FieldAttributes();
            psMoved.setPs((byte) 0x01);
            assertThat(psMoved.isDefault()).isFalse();

            FieldAttributes hilightMoved = new FieldAttributes();
            hilightMoved.setHilight(BmsAttributes.DFHBLINK);
            assertThat(hilightMoved.isDefault()).isFalse();

            FieldAttributes validnMoved = new FieldAttributes();
            validnMoved.setValidn((byte) 0x04);
            assertThat(validnMoved.isDefault()).isFalse();

            colourMoved.reset();
            assertThat(colourMoved.isDefault()).isTrue();
        }

        @Test
        @DisplayName("isDefault and isRed are independent readings of the same colour byte")
        void isDefaultAndIsRedReadTheSameByteDifferently() {
            CardUpdateResponse response = new CardUpdateResponse();

            assertThat(response.attributesOf(CardUpdateResponse.FKEYSC).isDefault()).isTrue();
            assertThat(response.attributesOf(CardUpdateResponse.FKEYSC).isRed()).isFalse();

            response.setColour(CardUpdateResponse.EXPDAY, BmsAttributes.DFHBMDAR);
            assertThat(response.attributesOf(CardUpdateResponse.EXPDAY).isDefault()).isFalse();
            assertThat(response.attributesOf(CardUpdateResponse.EXPDAY).isRed()).isFalse();

            response.setColour(CardUpdateResponse.ACCTSID, BmsAttributes.DFHRED);
            assertThat(response.attributesOf(CardUpdateResponse.ACCTSID).isRed()).isTrue();
            response.setColour(CardUpdateResponse.ACCTSID, BmsAttributes.DFHDFCOL);
            assertThat(response.attributesOf(CardUpdateResponse.ACCTSID).isDefault()).isTrue();
        }

        @Test
        @DisplayName("reset returns every byte to 0x00")
        void resetReturnsEveryByteToZero() {
            FieldAttributes quad = new FieldAttributes((byte) 1, (byte) 2, (byte) 3, (byte) 4);

            quad.reset();

            assertThat(quad).isEqualTo(new FieldAttributes());
        }

        @Test
        @DisplayName("the four setters are independent of one another")
        void theFourSettersAreIndependent() {
            FieldAttributes quad = new FieldAttributes();

            quad.setColour(BmsAttributes.DFHRED);
            assertThat(quad.getPs()).isZero();
            quad.setPs((byte) 0x11);
            quad.setHilight((byte) 0x22);
            quad.setValidn((byte) 0x33);

            assertThat(quad.getColour()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(quad.getPs()).isEqualTo((byte) 0x11);
            assertThat(quad.getHilight()).isEqualTo((byte) 0x22);
            assertThat(quad.getValidn()).isEqualTo((byte) 0x33);
            assertThat(quad.toString()).isNotBlank();
        }

        @Test
        @DisplayName("quad equality is reflexive, type-checked, and covers all four bytes")
        void quadEqualityCoversAllFourBytes() {
            FieldAttributes quad = new FieldAttributes((byte) 1, (byte) 2, (byte) 3, (byte) 4);

            assertThat(quad.equals(quad)).isTrue();
            assertThat(quad.equals(null)).isFalse();
            assertThat(quad.equals("X'01'")).isFalse();
            assertThat(quad).isNotEqualTo(new FieldAttributes((byte) 9, (byte) 2, (byte) 3,
                    (byte) 4));
            assertThat(quad).isNotEqualTo(new FieldAttributes((byte) 1, (byte) 9, (byte) 3,
                    (byte) 4));
            assertThat(quad).isNotEqualTo(new FieldAttributes((byte) 1, (byte) 2, (byte) 9,
                    (byte) 4));
            assertThat(quad).isNotEqualTo(new FieldAttributes((byte) 1, (byte) 2, (byte) 3,
                    (byte) 9));
            assertThat(quad).isEqualTo(new FieldAttributes((byte) 1, (byte) 2, (byte) 3, (byte) 4));
        }

        @Test
        @DisplayName("attributesOf hands out the live quad so a colour move is observable")
        void attributesOfHandsOutTheLiveQuad() {
            CardUpdateResponse response = new CardUpdateResponse();

            response.attributesOf("ACCTSID").setColour(BmsAttributes.DFHRED);

            assertThat(response.colourOf("ACCTSID")).isEqualTo(BmsAttributes.DFHRED);
            assertThat(response.attributeImages().get("ACCTSIDC"))
                    .isEqualTo(BmsAttributes.toHex(BmsAttributes.DFHRED));
        }

        @Test
        @DisplayName("setColour writes the same byte the quad reports")
        void setColourWritesThroughToTheQuad() {
            CardUpdateResponse response = new CardUpdateResponse();

            response.setColour("CARDSID", BmsAttributes.DFHRED);

            assertThat(response.attributesOf("CARDSID").getColour())
                    .isEqualTo(BmsAttributes.DFHRED);
            assertThat(response.colourOf("CARDSID")).isEqualTo(BmsAttributes.DFHRED);
        }

        @Test
        @DisplayName("the attribute accessors refuse a label this map does not declare")
        void theAttributeAccessorsRefuseAnUnknownLabel() {
            CardUpdateResponse response = new CardUpdateResponse();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.attributesOf("NOSUCH"));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.colourOf("NOSUCH"));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.setColour("NOSUCH", BmsAttributes.DFHRED));
        }

        @Test
        @DisplayName("a REENTER decision moves the colour byte the decision itself resolved")
        void theHighlightAppliesInReenterState() {
            CardUpdateResponse response = new CardUpdateResponse();

            FieldHighlight applied = response.applyHighlight("ACCTSID",
                    FieldValidationState.NOT_OK, true);

            assertThat(applied.colourItemAssigned()).isTrue();
            assertThat(response.colourOf("ACCTSID")).isEqualTo(applied.colourItemValue());
        }

        @Test
        @DisplayName("an ENTER decision moves nothing, because the rule is gated on re-entry")
        void theHighlightDoesNothingInEnterState() {
            CardUpdateResponse response = new CardUpdateResponse();

            FieldHighlight applied = response.applyHighlight("ACCTSID",
                    FieldValidationState.NOT_OK, false);

            assertThat(applied.colourItemAssigned()).isFalse();
            assertThat(response.colourOf("ACCTSID")).isZero();
        }

        @Test
        @DisplayName("applyHighlight refuses a decision resolved for another card map")
        void applyHighlightRefusesAForeignMap() {
            CardUpdateResponse response = new CardUpdateResponse();
            FieldHighlight foreign = FieldAttributeSetter.resolve(FieldValidationState.NOT_OK, true,
                    "ACCTSID", "CCRDLIA");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.applyHighlight(foreign))
                    .withMessageContaining("CCRDLIA")
                    .withMessageContaining("CCRDUPA");
        }

        @Test
        @DisplayName("applyHighlight refuses a decision naming a field this map does not declare")
        void applyHighlightRefusesAForeignField() {
            CardUpdateResponse response = new CardUpdateResponse();
            FieldHighlight foreign = FieldAttributeSetter.resolve(FieldValidationState.NOT_OK, true,
                    "CRDSTP1", CardUpdateResponse.MAP_NAME);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.applyHighlight(foreign))
                    .withMessageContaining("CRDSTP1");
        }

        @Test
        @DisplayName("applyHighlight refuses a decision that names no field at all")
        void applyHighlightRefusesAPrefixlessDecision() {
            CardUpdateResponse response = new CardUpdateResponse();
            FieldHighlight prefixless = FieldAttributeSetter.resolve(FieldValidationState.NOT_OK, true);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.applyHighlight(prefixless))
                    .withMessageContaining("SCRNVAR2");
        }

        @Test
        @DisplayName("a decision that names a field but no map is accepted, since it cannot clash")
        void applyHighlightAcceptsAMaplessDecision() {
            CardUpdateResponse response = new CardUpdateResponse();
            FieldHighlight mapless = FieldAttributeSetter.resolve(FieldValidationState.NOT_OK, true,
                    "ACCTSID", "");

            response.applyHighlight(mapless);

            assertThat(response.colourOf("ACCTSID")).isEqualTo(mapless.colourItemValue());
        }

        @Test
        @DisplayName("a decision that assigns no output item leaves the payload alone")
        void applyHighlightLeavesThePayloadAloneWhenNoOutputIsAssigned() {
            CardUpdateResponse response = new CardUpdateResponse();
            response.setOutputItem("ACCTSID", "00000000011");
            FieldHighlight colourOnly = FieldAttributeSetter.resolve(FieldValidationState.OK, true,
                    "ACCTSID", CardUpdateResponse.MAP_NAME);

            response.applyHighlight(colourOnly);

            assertThat(colourOnly.outputItemAssigned()).isFalse();
            assertThat(response.outputItemOf("ACCTSID")).isEqualTo("00000000011");
        }

        @Test
        @DisplayName("applyHighlight rejects a null decision, state or field name")
        void applyHighlightRejectsNulls() {
            CardUpdateResponse response = new CardUpdateResponse();

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> response.applyHighlight((FieldHighlight) null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> response.applyHighlight(null,
                            FieldValidationState.NOT_OK, true));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> response.applyHighlight("ACCTSID", null, true));
        }
    }

    @Nested
    @DisplayName("Highlighting - DFHRED on NOT-OK or BLANK, '*' on BLANK, nothing unless REENTER")
    class HighlightTruthTable {
        @ParameterizedTest(name = "EXPYEAR: {0} + reenter={1} -> colour={2}, asterisk={3}")
        @MethodSource("com.vsergeychik.carddemo.card.dto.CardUpdateResponseTest#highlightTruthTable")
        @DisplayName("all six cells, on the editable EXPYEAR field (ATTRB=(UNPROT), bms:135)")
        void allSixCellsOnAnEditableField(FieldValidationState state, boolean reenter,
                boolean colourExpected, boolean asteriskExpected) {
            assertHighlightCell(CardUpdateResponse.EXPYEAR, CardUpdateResponse.EXPYEARO_LENGTH,
                    state, reenter, colourExpected, asteriskExpected);
        }

        @ParameterizedTest(name = "ACCTSID: {0} + reenter={1} -> colour={2}, asterisk={3}")
        @MethodSource("com.vsergeychik.carddemo.card.dto.CardUpdateResponseTest#highlightTruthTable")
        @DisplayName("all six cells again, on ACCTSID, so the rule is not specific to one field")
        void allSixCellsOnASecondField(FieldValidationState state, boolean reenter,
                boolean colourExpected, boolean asteriskExpected) {
            assertHighlightCell(CardUpdateResponse.ACCTSID, CardUpdateResponse.ACCTSIDO_LENGTH,
                    state, reenter, colourExpected, asteriskExpected);
        }

        private void assertHighlightCell(String label, int width, FieldValidationState state,
                boolean reenter, boolean colourExpected, boolean asteriskExpected) {
            CardUpdateResponse response = new CardUpdateResponse();
            response.setOutputItem(label, "7");
            String before = response.outputItemOf(label);

            FieldHighlight applied = response.applyHighlight(label, state, reenter);

            assertThat(applied.colourItemAssigned()).isEqualTo(colourExpected);
            assertThat(applied.outputItemAssigned()).isEqualTo(asteriskExpected);

            if (colourExpected) {
                assertThat(response.colourOf(label)).isEqualTo(BmsAttributes.DFHRED);
                assertThat(response.attributesOf(label).isRed()).isTrue();
            } else {
                assertThat(response.colourOf(label))
                        .isEqualTo(CardUpdateResponse.FieldAttributes.DEFAULT);
                assertThat(response.attributesOf(label).isRed()).isFalse();
            }

            if (asteriskExpected) {
                assertThat(response.outputItemOf(label))
                        .isEqualTo(ASCII.movePicX(FieldAttributeSetter.ASTERISK, width))
                        .startsWith("*");
            } else {
                assertThat(response.outputItemOf(label)).isEqualTo(before).startsWith("7");
            }

            assertThat(response.outputItemOf(label)).hasSize(width);
            assertThat(response.toFixedWidth(StandardCharsets.US_ASCII)).hasSize(484);
        }

        @Test
        @DisplayName("exactly one of the six cells writes an asterisk, and it is (BLANK, REENTER)")
        void exactlyOneCellWritesAnAsterisk() {
            long asteriskCells = highlightTruthTable().stream()
                    .filter(row -> (boolean) row.get()[3])
                    .count();

            assertThat(asteriskCells).isEqualTo(1);
            assertThat(highlightTruthTable()).hasSize(6);

            CardUpdateResponse blank = new CardUpdateResponse();
            blank.applyHighlight(CardUpdateResponse.EXPYEAR, FieldValidationState.BLANK, true);
            CardUpdateResponse notOk = new CardUpdateResponse();
            notOk.applyHighlight(CardUpdateResponse.EXPYEAR, FieldValidationState.NOT_OK, true);

            assertThat(blank.getExpyearo()).isEqualTo("*   ");
            assertThat(notOk.getExpyearo()).doesNotContain("*");
            assertThat(blank.colourOf(CardUpdateResponse.EXPYEAR))
                    .isEqualTo(notOk.colourOf(CardUpdateResponse.EXPYEAR))
                    .isEqualTo(BmsAttributes.DFHRED);
        }

        @Test
        @DisplayName("the asterisk never reaches a colour item, and DFHRED never reaches a payload")
        void theTwoMovesNeverSwapTargets() {
            CardUpdateResponse response = new CardUpdateResponse();

            response.applyHighlight(CardUpdateResponse.CRDNAME, FieldValidationState.BLANK, true);

            assertThat(response.getCrdnameo()).startsWith("*").hasSize(50);
            assertThat(response.colourOf(CardUpdateResponse.CRDNAME))
                    .isEqualTo(BmsAttributes.DFHRED);
            assertThat(response.attributeImages().get("CRDNAMEC"))
                    .isEqualTo(BmsAttributes.toHex(BmsAttributes.DFHRED))
                    .isNotEqualTo(BmsAttributes.toHex((byte) '*'));
        }

        @Test
        @DisplayName("a highlight on one field leaves the other sixteen fields entirely alone")
        void aHighlightIsScopedToItsOwnField() {
            CardUpdateResponse response = new CardUpdateResponse();
            response.setFkeysco(FKEYSC_LITERAL);

            response.applyHighlight(CardUpdateResponse.ACCTSID, FieldValidationState.BLANK, true);

            for (String label : CardUpdateResponse.namedFieldPrefixes()) {
                if (!CardUpdateResponse.ACCTSID.equals(label)) {
                    assertThat(response.colourOf(label))
                            .as("%sC after highlighting ACCTSID", label)
                            .isEqualTo(CardUpdateResponse.FieldAttributes.DEFAULT);
                }
            }
            assertThat(response.getFkeysco()).isEqualTo(FKEYSC_LITERAL);
        }

        @Test
        @DisplayName("ERRMSGO is 80 and pads a shorter message; the mapset already declares COLOR=RED")
        void theErrorMessageFieldIsEightyAndAlreadyRed() {
            CardUpdateResponse response = new CardUpdateResponse();

            response.setErrmsgo("Card number not found");

            assertThat(CardUpdateResponse.ERRMSGO_LENGTH).isEqualTo(80);
            assertThat(response.getErrmsgo())
                    .isEqualTo("Card number not found" + " ".repeat(80 - 21))
                    .hasSize(80);
            assertThat(response.colourOf(CardUpdateResponse.ERRMSG))
                    .isEqualTo(CardUpdateResponse.FieldAttributes.DEFAULT);
        }
    }

    @Nested
    @DisplayName("The echoed commarea - 1 + 89 + 89 + 150 = 329, FILLER X(59) and all")
    class EchoedCommareaGeometry {
        @Test
        @DisplayName("the echoed image is exactly 329 bytes: 1 + 89 + 89 + 150")
        void theEchoedImageIsThreeHundredAndTwentyNine() {
            CommArea echoed = populatedResponse().getCommArea();

            assertThat(1 + 89 + 89 + 150).isEqualTo(329);
            assertThat(ChangeAction.RECORD_LENGTH).isEqualTo(1);
            assertThat(CardDetails.RECORD_LENGTH).isEqualTo(89);
            assertThat(CardUpdateRecord.RECORD_LENGTH).isEqualTo(150);
            assertThat(CommArea.RECORD_LENGTH).isEqualTo(329);

            assertThat(echoed.encode(ASCII)).hasSize(329);
            assertThat(echoed.encode(IBM037)).hasSize(329);

            assertThat(CommArea.CHANGE_ACTION_OFFSET).isZero();
            assertThat(CommArea.OLD_DETAILS_OFFSET).isEqualTo(1);
            assertThat(CommArea.NEW_DETAILS_OFFSET).isEqualTo(1 + 89).isEqualTo(90);
            assertThat(CommArea.CARD_UPDATE_RECORD_OFFSET).isEqualTo(1 + 89 + 89).isEqualTo(179);
        }

        @Test
        @DisplayName("each details group is 89 = 11+16+3+50+8+1, with a separator-free 8-byte expiry")
        void eachDetailsGroupIsEightyNine() {
            CardDetails old = populatedResponse().getCommArea().oldDetails();

            assertThat(11 + 16 + 3 + 50 + 8 + 1).isEqualTo(89);
            assertThat(CardDetails.ACCTID_LENGTH).isEqualTo(11);
            assertThat(CardDetails.CARDID_LENGTH).isEqualTo(16);
            assertThat(CardDetails.CVV_CD_LENGTH).isEqualTo(3);
            assertThat(CardDetails.CRDNAME_LENGTH).isEqualTo(50);
            assertThat(CardDetails.EXPYEAR_LENGTH + CardDetails.EXPMON_LENGTH
                    + CardDetails.EXPDAY_LENGTH).isEqualTo(8);
            assertThat(CardDetails.EXPIRAION_DATE_LENGTH).isEqualTo(8);
            assertThat(CardDetails.CRDSTCD_LENGTH).isEqualTo(1);

            assertThat(old.encode(ASCII)).hasSize(89);
            assertThat(old.ccupExpiraionDate()).isEqualTo("20260719").hasSize(8);
            assertThat(old.ccupExpiraionDate()).doesNotContain("-").doesNotContain("/");
        }

        @Test
        @DisplayName("the record is 150 = 16+11+3+50+10+1+59, with a separator-bearing 10-byte date")
        void theEmbeddedRecordIsOneHundredAndFifty() {
            CardUpdateRecord record = populatedResponse().getCommArea().cardUpdateRecord()
                    .withCardUpdateExpiraionDate("2026-07-19");

            assertThat(16 + 11 + 3 + 50 + 10 + 1 + 59).isEqualTo(150);
            assertThat(CardUpdateRecord.CARD_UPDATE_NUM_LENGTH).isEqualTo(16);
            assertThat(CardUpdateRecord.CARD_UPDATE_ACCT_ID_LENGTH).isEqualTo(11);
            assertThat(CardUpdateRecord.CARD_UPDATE_CVV_CD_LENGTH).isEqualTo(3);
            assertThat(CardUpdateRecord.CARD_UPDATE_EMBOSSED_NAME_LENGTH).isEqualTo(50);
            assertThat(CardUpdateRecord.CARD_UPDATE_EXPIRAION_DATE_LENGTH).isEqualTo(10);
            assertThat(CardUpdateRecord.CARD_UPDATE_ACTIVE_STATUS_LENGTH).isEqualTo(1);

            assertThat(record.encode(ASCII)).hasSize(150);
            assertThat(record.cardUpdateExpiraionDate()).isEqualTo("2026-07-19").hasSize(10);
            assertThat(record.cardUpdateExpiraionDateYear()).isEqualTo("2026");
            assertThat(record.cardUpdateExpiraionDateMonth()).isEqualTo("07");
            assertThat(record.cardUpdateExpiraionDateDay()).isEqualTo("19");
        }

        @Test
        @DisplayName("FILLER X(59) is present and space-filled; without it the record would be 91")
        void theFillerIsPresentAndSpaceFilled() {
            CardUpdateRecord record = populatedResponse().getCommArea().cardUpdateRecord();

            assertThat(CardUpdateRecord.FILLER_LENGTH).isEqualTo(59);
            assertThat(CardUpdateRecord.FILLER_OFFSET).isEqualTo(91);
            assertThat(16 + 11 + 3 + 50 + 10 + 1).isEqualTo(91);
            assertThat(91 + 59).isEqualTo(150);

            byte[] image = record.encode(ASCII);
            assertThat(image).hasSize(150);
            String tail = new String(image, CardUpdateRecord.FILLER_OFFSET,
                    CardUpdateRecord.FILLER_LENGTH, StandardCharsets.US_ASCII);
            assertThat(tail).isEqualTo(" ".repeat(59)).hasSize(59);

            assertThat(CardUpdateRecord.FILLER.length()).isEqualTo(59);
            assertThat(CardUpdateRecord.FILLER.kind().filler()).isTrue();
            assertThat(CardUpdateRecord.LAYOUT.recordLength()).isEqualTo(150);
        }

        @Test
        @DisplayName("the EXPIRAION misspelling is preserved and EXPIRATION appears nowhere")
        void theMisspellingIsPreserved() {
            CardDetails old = populatedResponse().getCommArea().oldDetails();
            CardUpdateRecord record = populatedResponse().getCommArea().cardUpdateRecord();

            assertThat(DetailGroup.OLD.expiraionDateSpan().name())
                    .isEqualTo("CCUP-OLD-EXPIRAION-DATE");
            assertThat(DetailGroup.NEW.expiraionDateSpan().name())
                    .isEqualTo("CCUP-NEW-EXPIRAION-DATE");
            assertThat(CardUpdateRecord.CARD_UPDATE_EXPIRAION_DATE.name())
                    .isEqualTo("CARD-UPDATE-EXPIRAION-DATE");

            assertThat(old.itemValues()).containsKey("CCUP-OLD-EXPIRAION-DATE");
            assertThat(record.itemValues(ASCII)).containsKey("CARD-UPDATE-EXPIRAION-DATE");

            assertThat(old.itemValues().keySet()).noneMatch(name -> name.contains("EXPIRATION"));
            assertThat(record.itemValues(ASCII).keySet())
                    .noneMatch(name -> name.contains("EXPIRATION"));
            assertThat(DetailGroup.OLD.layout().spans()).noneMatch(
                    span -> span.name().contains("EXPIRATION"));
            assertThat(CardUpdateRecord.LAYOUT.spans()).noneMatch(
                    span -> span.name().contains("EXPIRATION"));
        }

        @Test
        @DisplayName("X(11)/X(3) pad right with spaces while 9(11)/9(03) fill left with zeros")
        void thePictureKindAsymmetrySurvivesTheRoundTrip() {
            CommArea echoed = populatedResponse().getCommArea();
            CardDetails old = echoed.oldDetails();
            CardUpdateRecord record = echoed.cardUpdateRecord();

            assertThat(old.acctid()).isEqualTo("00000000011").hasSize(11);
            assertThat(old.withAcctid("11").acctid()).isEqualTo("11         ").hasSize(11);

            assertThat(old.cvvCd()).isEqualTo(CVV_CODE).isEqualTo("123").hasSize(3);
            assertThat(old.withCvvCd("7").cvvCd()).isEqualTo("7  ").hasSize(3);

            assertThat(record.cardUpdateAcctId()).isEqualTo(11L);
            assertThat(record.cardUpdateAcctIdImage(ASCII)).isEqualTo("00000000011").hasSize(11);

            assertThat(record.cardUpdateCvvCd()).isEqualTo(123);
            assertThat(record.cardUpdateCvvCdImage(ASCII)).isEqualTo("123").hasSize(3);
            assertThat(record.withCardUpdateCvvCd(7).cardUpdateCvvCdImage(ASCII)).isEqualTo("007");

            assertThat(old.cardid()).isEqualTo(CARD_NUMBER).hasSize(16);
            assertThat(record.cardUpdateNum()).isEqualTo(CARD_NUMBER).hasSize(16);
        }

        @ParameterizedTest(name = "the response carries CCUP-CHANGE-ACTION = {1}")
        @MethodSource(
                "com.vsergeychik.carddemo.card.dto.CardUpdateResponseTest#changeActionBytes")
        @DisplayName("every one of the nine 88-level states can be carried and read back")
        void everyChangeActionStateCanBeCarried(String value, String expectedName) {
            CardUpdateResponse response = populatedResponse();

            response.setCommArea(populatedCommArea().withChangeAction(ChangeAction.of(value)));

            assertThat(response.getCommArea().changeAction().value()).isEqualTo(value);
            assertThat(response.getCommArea().changeAction().describe()).isEqualTo(expectedName);
            assertThat(response.getCommArea().changeAction().isRecognised()).isTrue();
            assertThat(ChangeAction.DETAILS_NOT_FETCHED_VALUES).containsExactly("\u0000", " ");
        }

        @Test
        @DisplayName("'L' and 'F' each satisfy CHANGES-FAILED and CHANGES-MADE; 'S' satisfies neither")
        void theOverlappingGroupingsAreCarriedFaithfully() {
            CardUpdateResponse lockError = responseCarrying(ChangeAction.changesOkayedLockError());
            ChangeAction lock = lockError.getCommArea().changeAction();
            assertThat(lock.value()).isEqualTo("L");
            assertThat(lock.isChangesFailed()).isTrue();
            assertThat(lock.isChangesMade()).isTrue();
            assertThat(lock.isChangesOkayedLockError()).isTrue();
            assertThat(lock.isChangesOkayedButFailed()).isFalse();
            assertThat(lock.isShowDetails()).isFalse();
            assertThat(lock.isDetailsNotFetched()).isFalse();

            CardUpdateResponse updateFailed = responseCarrying(ChangeAction.changesOkayedButFailed());
            ChangeAction failed = updateFailed.getCommArea().changeAction();
            assertThat(failed.value()).isEqualTo("F");
            assertThat(failed.isChangesFailed()).isTrue();
            assertThat(failed.isChangesMade()).isTrue();
            assertThat(failed.isChangesOkayedButFailed()).isTrue();
            assertThat(failed.isChangesOkayedLockError()).isFalse();

            CardUpdateResponse showDetails = responseCarrying(ChangeAction.showDetails());
            ChangeAction show = showDetails.getCommArea().changeAction();
            assertThat(show.value()).isEqualTo("S");
            assertThat(show.isShowDetails()).isTrue();
            assertThat(show.isChangesFailed()).isFalse();
            assertThat(show.isChangesMade()).isFalse();

            assertThat(ChangeAction.CHANGES_MADE_VALUES).containsExactly("E", "N", "C", "L", "F");
            assertThat(ChangeAction.CHANGES_FAILED_VALUES).containsExactly("L", "F");
        }

        @Test
        @DisplayName("the initial state is LOW-VALUES, which is not a space even though both qualify")
        void theInitialStateIsLowValuesAndNotASpace() {
            ChangeAction lowValues = new CardUpdateResponse().getCommArea().changeAction();
            ChangeAction spaces = responseCarrying(ChangeAction.spacesState())
                    .getCommArea().changeAction();

            assertThat(lowValues.value()).isEqualTo("\u0000").isEqualTo(ChangeAction.LOW_VALUES);
            assertThat(spaces.value()).isEqualTo(" ").isEqualTo(ChangeAction.SPACES);
            assertThat(lowValues.value()).isNotEqualTo(spaces.value());
            assertThat(lowValues.isDetailsNotFetched()).isTrue();
            assertThat(spaces.isDetailsNotFetched()).isTrue();
            assertThat(lowValues).isNotEqualTo(spaces);
        }

        private CardUpdateResponse responseCarrying(ChangeAction action) {
            CardUpdateResponse response = populatedResponse();
            response.setCommArea(populatedCommArea().withChangeAction(action));
            return response;
        }
    }

    @Nested
    @DisplayName("Navigation instead of XCTL - 8, 7 and 7 characters, all of it in the payload")
    class StatelessNavigation {
        @ParameterizedTest(name = "nextProgram accepts {0} at 8 characters")
        @ValueSource(strings = {"COCRDUPC", "COCRDLIC", "COMEN01C"})
        @DisplayName("nextProgram is 8, the width of LIT-CARDUPDPGM at COCRDLIC.cbl:203-204")
        void theNextProgramTokenIsEightCharacters(String program) {
            CardUpdateResponse response = new CardUpdateResponse();

            response.setNextProgram(program);

            assertThat(response.getNextProgram()).isEqualTo(program).hasSize(8);
            assertThat(CardScreenState.CCARD_NEXT_PROG_LENGTH).isEqualTo(8);
        }

        @ParameterizedTest(name = "nextMapset {0} and nextMap {1} are both 7 characters")
        @CsvSource({"COCRDUP,CCRDUPA", "COCRDLI,CCRDLIA"})
        @DisplayName("nextMapset and nextMap are 7, per LIT-CARDUPDMAPSET and LIT-CARDUPDMAP")
        void theMapTokensAreSevenCharacters(String mapset, String map) {
            CardUpdateResponse response = new CardUpdateResponse();

            response.transferTo("COCRDUPC", mapset, map);

            assertThat(response.getNextMapset()).isEqualTo(mapset).hasSize(7);
            assertThat(response.getNextMap()).isEqualTo(map).hasSize(7);
            assertThat(CardScreenState.CCARD_NEXT_MAPSET_LENGTH).isEqualTo(7);
            assertThat(CardScreenState.CCARD_NEXT_MAP_LENGTH).isEqualTo(7);
        }

        @Test
        @DisplayName("7 is not widened to 8 and 8 is not truncated to 7")
        void theTwoWidthsAreNotConflated() {
            CardUpdateResponse response = new CardUpdateResponse();

            response.transferTo("COCRDUPC", "COCRDUP", "CCRDUPA");

            assertThat(response.getNextProgram()).hasSize(8);
            assertThat(response.getNextMapset()).hasSize(7);
            assertThat(response.getNextProgram()).isNotEqualTo(response.getNextMapset());
            assertThat(response.getNextProgram()).startsWith(response.getNextMapset());

            response.setNextMapset("COCRDUPC");
            assertThat(response.getNextMapset()).isEqualTo("COCRDUP").hasSize(7);
            response.setNextProgram("COCRDUP");
            assertThat(response.getNextProgram()).isEqualTo("COCRDUP ").hasSize(8);
        }

        @Test
        @DisplayName("the three carriers and the 329-byte area all travel in the payload, not a session")
        void everyPieceOfStateTravelsInThePayload() {
            List<String> members = membersOf(populatedResponse());

            assertThat(members).containsAll(SHARED_CARRIERS);
            assertThat(members).contains("nextProgram", "nextMapset", "nextMap");
            assertThat(members)
                    .as("the program's own work area travels sealed, not as an object a caller can write")
                    .doesNotContain("commArea");

            CardUpdateResponse response = populatedResponse();
            assertThat(response.getCommArea()).isNotNull();
            assertThat(response.getCommArea().encode(ASCII)).hasSize(329);
            assertThat(response.getCardScreenState()).isNotNull();
            assertThat(response.getNavigationContext()).isNotNull();
            // And all 329 bytes of it really are in the token, which is the whole point of sealing it
            // rather than dropping it.
            assertThat(unsealed(sealed(response).getStateToken())).isEqualTo(response.getCommArea());
        }

        @Test
        @DisplayName("two responses built independently share nothing, so there is no ambient holder")
        void twoResponsesAreFullyIndependent() {
            CardUpdateResponse first = new CardUpdateResponse();
            CardUpdateResponse second = new CardUpdateResponse();

            first.transferTo("COCRDLIC", "COCRDLI", "CCRDLIA");
            first.setCardsido(CARD_NUMBER);
            first.setColour(CardUpdateResponse.FKEYSC, BmsAttributes.DFHRED);
            first.setCommArea(populatedCommArea());
            first.getCardScreenState().setCcardNextProg("COMEN01C");

            assertThat(second.getNextProgram()).isEqualTo(" ".repeat(8));
            assertThat(second.getNextMapset()).isEqualTo(" ".repeat(7));
            assertThat(second.getNextMap()).isEqualTo(" ".repeat(7));
            assertThat(second.getCardsido()).isEqualTo("\u0000".repeat(16));
            assertThat(second.colourOf(CardUpdateResponse.FKEYSC))
                    .isEqualTo(CardUpdateResponse.FieldAttributes.DEFAULT);
            assertThat(second.getCommArea()).isEqualTo(CommArea.initialised());
            assertThat(second.getCardScreenState().getCcardNextProg()).isEqualTo(" ".repeat(8));
            assertThat(second.getNavigationContext()).isEqualTo(NavigationContext.empty());
        }
    }

    @Nested
    @DisplayName("Titles, messages, the clock and value semantics")
    class TitlesMessagesClockAndValueSemantics {
        @Test
        @DisplayName("TITLE01O and TITLE02O are 40, and the COTTL01Y literals fit them exactly")
        void theTitleFieldsAreFortyAndTheLiteralsFit() {
            CardUpdateResponse response = new CardUpdateResponse();

            response.applyScreenTitles();

            assertThat(CardUpdateResponse.TITLE01O_LENGTH).isEqualTo(40);
            assertThat(CardUpdateResponse.TITLE02O_LENGTH).isEqualTo(40);
            assertThat(ScreenTitles.TITLE_LENGTH).isEqualTo(40);
            assertThat(ScreenTitles.CCDA_TITLE01).hasSize(40);
            assertThat(ScreenTitles.CCDA_TITLE02).hasSize(40);
            assertThat(response.getTitle01o()).isEqualTo(ScreenTitles.CCDA_TITLE01).hasSize(40);
            assertThat(response.getTitle02o()).isEqualTo(ScreenTitles.CCDA_TITLE02).hasSize(40);
        }

        @Test
        @DisplayName("CCDA-THANK-YOU X(40) and CCDA-MSG-THANK-YOU X(50) are different literals")
        void theTwoThankYouLiteralsAreNotInterchangeable() {
            assertThat(ScreenTitles.CCDA_THANK_YOU).hasSize(ScreenTitles.TITLE_LENGTH).hasSize(40);
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU)
                    .hasSize(SystemMessages.MESSAGE_LENGTH).hasSize(50);
            assertThat(SystemMessages.MESSAGE_LENGTH).isNotEqualTo(ScreenTitles.TITLE_LENGTH);
            assertThat(ScreenTitles.CCDA_THANK_YOU.strip())
                    .isNotEqualTo(SystemMessages.CCDA_MSG_THANK_YOU.strip());
            assertThat(ScreenTitles.CCDA_THANK_YOU).contains("CCDA application");
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU).contains("CardDemo application");

            CardUpdateResponse response = new CardUpdateResponse();
            response.setTitle01o(SystemMessages.CCDA_MSG_THANK_YOU);
            assertThat(response.getTitle01o()).hasSize(40)
                    .isEqualTo(SystemMessages.CCDA_MSG_THANK_YOU.substring(0, 40));
        }

        @Test
        @DisplayName("CURDATEO and CURTIMEO are 8 each, filled from a fixed Clock")
        void theDateAndTimeFieldsAreEightEach() {
            CardUpdateResponse response = new CardUpdateResponse();

            response.applyDateTimeHeader(DateHeader.from(ASCII, FIXED_CLOCK));

            assertThat(CardUpdateResponse.CURDATEO_LENGTH).isEqualTo(8);
            assertThat(CardUpdateResponse.CURTIMEO_LENGTH).isEqualTo(8);
            assertThat(response.getCurdateo()).isEqualTo(FIXED_CURDATE).isEqualTo("07/19/22")
                    .hasSize(8);
            assertThat(response.getCurtimeo()).isEqualTo(FIXED_CURTIME).isEqualTo("23:15:58")
                    .hasSize(8);
        }

        @Test
        @DisplayName("the same fixed Clock through screenInit gives the same eight-character images")
        void theFixedClockIsStableThroughScreenInit() {
            CardUpdateResponse first = new CardUpdateResponse();
            CardUpdateResponse second = new CardUpdateResponse();

            first.screenInit(DateHeader.from(ASCII, FIXED_CLOCK));
            second.screenInit(DateHeader.from(ASCII, FIXED_CLOCK));

            assertThat(first.getCurdateo()).isEqualTo(second.getCurdateo()).isEqualTo("07/19/22");
            assertThat(first.getCurtimeo()).isEqualTo(second.getCurtimeo()).isEqualTo("23:15:58");
            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        }

        @Test
        @DisplayName("equal field sets are equal and hash alike; one byte anywhere breaks it")
        void equalityIsByValueAcrossEveryMember() {
            CardUpdateResponse left = populatedResponse();
            CardUpdateResponse right = populatedResponse();

            assertThat(left).isEqualTo(right).hasSameHashCodeAs(right);

            CardUpdateResponse cvvDiffers = populatedResponse();
            cvvDiffers.setCommArea(populatedCommArea().withCardUpdateRecord(
                    populatedCommArea().cardUpdateRecord().withCardUpdateCvvCd(124)));
            assertThat(left).isNotEqualTo(cvvDiffers);
        }

        @Test
        @DisplayName("FKEYSC and FKEYSCC each break equality on their own, one byte at a time")
        void theTwoColourItemsEachBreakEqualityIndependently() {
            CardUpdateResponse left = populatedResponse();

            CardUpdateResponse fkeysColourDiffers = populatedResponse();
            fkeysColourDiffers.setColour(CardUpdateResponse.FKEYS, BmsAttributes.DFHRED);
            assertThat(left).isNotEqualTo(fkeysColourDiffers);

            CardUpdateResponse fkeyscColourDiffers = populatedResponse();
            fkeyscColourDiffers.setColour(CardUpdateResponse.FKEYSC, BmsAttributes.DFHRED);
            assertThat(left).isNotEqualTo(fkeyscColourDiffers);
            assertThat(fkeysColourDiffers).isNotEqualTo(fkeyscColourDiffers);

            CardUpdateResponse fkeysoDiffers = populatedResponse();
            fkeysoDiffers.setFkeyso(FKEYS_LITERAL);
            assertThat(left).isNotEqualTo(fkeysoDiffers);

            CardUpdateResponse fkeyscoDiffers = populatedResponse();
            fkeyscoDiffers.setFkeysco(FKEYSC_LITERAL);
            assertThat(left).isNotEqualTo(fkeyscoDiffers);
            assertThat(fkeysoDiffers).isNotEqualTo(fkeyscoDiffers);
        }

        @Test
        @DisplayName("toString reports structure and masks nothing it reports")
        void toStringMasksNothing() {
            CardUpdateResponse response = populatedResponse();

            String rendered = response.toString();

            assertThat(rendered).contains("COCRDUP", "CCRDUPAO", "484", "CCUP",
                    "CCUP-CHANGES-OK-NOT-CONFIRMED", "COCRDLIC", "COCRDLI", "CCRDLIA");
            assertThat(rendered).doesNotContain("****", "[REDACTED]", "...");

            assertThat(response.getCardsido()).isEqualTo(CARD_NUMBER);
            assertThat(response.fieldImages()).containsEntry("CARDSIDO", CARD_NUMBER);
            assertThat(response.getCommArea().oldDetails().cvvCd()).isEqualTo(CVV_CODE);
        }
    }

    @Nested
    @DisplayName("The 484-byte image - written and read at the declared offsets, in both code pages")
    class GroupImage {
        @Test
        @DisplayName("toFixedWidth writes exactly 484 bytes in both code pages")
        void theImageIsAlwaysFourHundredAndEightyFourBytes() {
            CardUpdateResponse response = populatedResponse();

            assertThat(response.toFixedWidth(StandardCharsets.US_ASCII)).hasSize(484);
            assertThat(response.toFixedWidth(IBM037)).hasSize(484);
        }

        @Test
        @DisplayName("a payload item lands at its declared offset")
        void aPayloadItemLandsAtItsDeclaredOffset() {
            CardUpdateResponse response = populatedResponse();
            ScreenField cardsid = CardUpdateResponse.fieldOf("CARDSID");
            byte[] image = response.toFixedWidth(StandardCharsets.US_ASCII);

            String slice = new String(image, cardsid.outputItemOffset(), cardsid.length(),
                    StandardCharsets.US_ASCII);

            assertThat(slice).isEqualTo(CARD_NUMBER);
        }

        @Test
        @DisplayName("an attribute byte lands at its declared offset and is not code-page decoded")
        void anAttributeByteLandsAtItsDeclaredOffset() {
            CardUpdateResponse response = populatedResponse();
            response.setColour("ACCTSID", BmsAttributes.DFHRED);
            ScreenField acctsid = CardUpdateResponse.fieldOf("ACCTSID");

            assertThat(response.toFixedWidth(StandardCharsets.US_ASCII)[acctsid.colourItemOffset()])
                    .isEqualTo(BmsAttributes.DFHRED);
            assertThat(response.toFixedWidth(IBM037)[acctsid.colourItemOffset()])
                    .isEqualTo(BmsAttributes.DFHRED);
        }

        @Test
        @DisplayName("the image round-trips through fromFixedWidth in both code pages")
        void theImageRoundTrips() {
            CardUpdateResponse response = populatedResponse();
            response.setColour("ACCTSID", BmsAttributes.DFHRED);

            CardUpdateResponse ascii = CardUpdateResponse.fromFixedWidth(
                    response.toFixedWidth(StandardCharsets.US_ASCII), StandardCharsets.US_ASCII);
            CardUpdateResponse ebcdic = CardUpdateResponse.fromFixedWidth(
                    response.toFixedWidth(IBM037), IBM037);

            assertThat(ascii.fieldImages()).isEqualTo(response.fieldImages());
            assertThat(ascii.attributeImages()).isEqualTo(response.attributeImages());
            assertThat(ebcdic.fieldImages()).isEqualTo(response.fieldImages());
            assertThat(ebcdic.attributeImages()).isEqualTo(response.attributeImages());
        }

        @Test
        @DisplayName("fromFixedWidth leaves the carriers in their initial state")
        void fromFixedWidthLeavesTheCarriersInitial() {
            CardUpdateResponse decoded = CardUpdateResponse.fromFixedWidth(
                    populatedResponse().toFixedWidth(StandardCharsets.US_ASCII),
                    StandardCharsets.US_ASCII);

            assertThat(decoded.getCommArea()).isEqualTo(CommArea.initialised());
            assertThat(decoded.getNavigationContext()).isEqualTo(NavigationContext.empty());
            assertThat(decoded.getNextProgram()).isBlank();
        }

        @Test
        @DisplayName("fromFixedWidth refuses an image that is not exactly 484 bytes")
        void fromFixedWidthRefusesAWrongLengthImage() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardUpdateResponse.fromFixedWidth(new byte[483],
                            StandardCharsets.US_ASCII))
                    .withMessageContaining("484");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> CardUpdateResponse.fromFixedWidth(null,
                            StandardCharsets.US_ASCII));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> CardUpdateResponse.fromFixedWidth(new byte[484], null));
        }

        @Test
        @DisplayName("writeInto and readFrom agree with toFixedWidth and fromFixedWidth")
        void writeIntoAndReadFromAgreeWithTheByteForms() {
            CardUpdateResponse response = populatedResponse();
            FixedWidthRecord record = new FixedWidthRecord(CardUpdateResponse.GROUP_LENGTH, StandardCharsets.US_ASCII);

            response.writeInto(record);

            assertThat(record.toByteArray())
                    .isEqualTo(response.toFixedWidth(StandardCharsets.US_ASCII));
            assertThat(CardUpdateResponse.readFrom(record).fieldImages())
                    .isEqualTo(response.fieldImages());
        }

        @Test
        @DisplayName("writeInto and readFrom reject a null record")
        void writeIntoAndReadFromRejectNull() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new CardUpdateResponse().writeInto(null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> CardUpdateResponse.readFrom(null));
        }

        @Test
        @DisplayName("writeInto and readFrom refuse a record that is not 484 bytes wide")
        void writeIntoAndReadFromRefuseAWrongWidthRecord() {
            FixedWidthRecord tooNarrow = new FixedWidthRecord(CardUpdateResponse.GROUP_LENGTH - 1,
                    StandardCharsets.US_ASCII);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new CardUpdateResponse().writeInto(tooNarrow))
                    .withMessageContaining(String.valueOf(CardUpdateResponse.GROUP_LENGTH));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardUpdateResponse.readFrom(tooNarrow))
                    .withMessageContaining(String.valueOf(CardUpdateResponse.GROUP_LENGTH));
        }

        @Test
        @DisplayName("toFixedWidth rejects a null charset rather than taking the platform default")
        void toFixedWidthRejectsANullCharset() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new CardUpdateResponse().toFixedWidth(null));
        }
    }

    @Nested
    @DisplayName("Statelessness and the object contract")
    class StatelessnessAndObjectContract {
        @Test
        @DisplayName("no static mutable state: every static field is final")
        void everyStaticFieldIsFinal() {
            List<String> mutable = new ArrayList<>();
            for (Field field : CardUpdateResponse.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        && !Modifier.isFinal(field.getModifiers())) {
                    mutable.add(field.getName());
                }
            }
            assertThat(mutable).isEmpty();
        }

        @Test
        @DisplayName("the three carrier setters refuse null and name the empty-state factory")
        void theCarrierSettersRefuseNull() {
            CardUpdateResponse response = new CardUpdateResponse();

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> response.setCommArea(null))
                    .withMessageContaining("CommArea.initialised()");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> response.setCardScreenState(null))
                    .withMessageContaining("new CardScreenState()");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> response.setNavigationContext(null))
                    .withMessageContaining("NavigationContext.empty()");
        }

        @Test
        @DisplayName("transferTo names the next program, mapset and map at their declared widths")
        void transferToNamesTheNextTarget() {
            CardUpdateResponse response = new CardUpdateResponse();

            response.transferTo("COCRDLIC", "COCRDLI", "CCRDLIA");

            assertThat(response.getNextProgram()).isEqualTo("COCRDLIC")
                    .hasSize(CardScreenState.CCARD_NEXT_PROG_LENGTH);
            assertThat(response.getNextMapset()).isEqualTo("COCRDLI")
                    .hasSize(CardScreenState.CCARD_NEXT_MAPSET_LENGTH);
            assertThat(response.getNextMap()).isEqualTo("CCRDLIA")
                    .hasSize(CardScreenState.CCARD_NEXT_MAP_LENGTH);
        }

        @Test
        @DisplayName("a short XCTL token is padded and an over-wide one truncated")
        void theXctlTokensFollowThePicXRule() {
            CardUpdateResponse response = new CardUpdateResponse();

            response.setNextProgram("CO");
            response.setNextMapset("TOOLONGMAPSET");
            response.setNextMap("X");

            assertThat(response.getNextProgram()).isEqualTo("CO      ");
            assertThat(response.getNextMapset()).isEqualTo("TOOLONG");
            assertThat(response.getNextMap()).isEqualTo("X      ");
        }

        @Test
        @DisplayName("the copy constructor is deep enough to mutate the copy safely")
        void theCopyConstructorIsDeepEnough() {
            CardUpdateResponse original = populatedResponse();
            CardUpdateResponse copy = new CardUpdateResponse(original);

            assertThat(copy).isEqualTo(original);
            copy.setCardsido("5555555555554444");
            copy.setColour("ACCTSID", BmsAttributes.DFHRED);
            copy.getCardScreenState().setCcardNextProg("COMEN01C");

            assertThat(original.getCardsido()).isEqualTo(CARD_NUMBER);
            assertThat(original.colourOf("ACCTSID")).isZero();
            assertThat(original.getCardScreenState().getCcardNextProg()).isEqualTo("COCRDLIC");
            assertThat(copy).isNotEqualTo(original);
        }

        @Test
        @DisplayName("the copy constructor rejects a null source")
        void theCopyConstructorRejectsNull() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new CardUpdateResponse(null));
        }

        @Test
        @DisplayName("equality covers the payload, the attributes and all three carriers")
        void equalityCoversEverything() {
            CardUpdateResponse left = populatedResponse();
            CardUpdateResponse right = populatedResponse();
            assertThat(left).isEqualTo(right).hasSameHashCodeAs(right);

            right.setCommArea(CommArea.initialised());
            assertThat(left).isNotEqualTo(right);

            CardUpdateResponse attributeDiffers = populatedResponse();
            attributeDiffers.setColour("ACCTSID", BmsAttributes.DFHRED);
            assertThat(left).isNotEqualTo(attributeDiffers);
        }

        @Test
        @DisplayName("equality is reflexive and refuses an unrelated type and null")
        void equalityIsWellBehaved() {
            CardUpdateResponse response = populatedResponse();

            assertThat(response.equals(response)).isTrue();
            assertThat(response.equals(null)).isFalse();
            assertThat(response.equals("CCRDUPAO")).isFalse();
        }

        @Test
        @DisplayName("each of the three XCTL targets takes part in equality on its own")
        void eachXctlTargetTakesPartInEquality() {
            CardUpdateResponse left = populatedResponse();

            CardUpdateResponse mapDiffers = populatedResponse();
            mapDiffers.setNextMap("CCRDUPA");
            assertThat(left).isNotEqualTo(mapDiffers);

            CardUpdateResponse mapsetDiffers = populatedResponse();
            mapsetDiffers.setNextMapset("COCRDUP");
            assertThat(left).isNotEqualTo(mapsetDiffers);

            CardUpdateResponse programDiffers = populatedResponse();
            programDiffers.setNextProgram("COMEN01C");
            assertThat(left).isNotEqualTo(programDiffers);
        }

        @Test
        @DisplayName("each of the three carriers takes part in equality on its own")
        void eachCarrierTakesPartInEquality() {
            CardUpdateResponse left = populatedResponse();

            CardUpdateResponse workAreaDiffers = populatedResponse();
            workAreaDiffers.getCardScreenState().setCcardNextProg("COMEN01C");
            assertThat(left).isNotEqualTo(workAreaDiffers);

            CardUpdateResponse navigationDiffers = populatedResponse();
            navigationDiffers.setNavigationContext(
                    NavigationContext.empty().withToProgram("COCRDLIC"));
            assertThat(left).isNotEqualTo(navigationDiffers);

            CardUpdateResponse commAreaDiffers = populatedResponse();
            commAreaDiffers.setCommArea(
                    populatedCommArea().withChangeAction(ChangeAction.showDetails()));
            assertThat(left).isNotEqualTo(commAreaDiffers);
        }

        @Test
        @DisplayName("a payload item difference alone breaks equality")
        void aPayloadDifferenceAloneBreaksEquality() {
            CardUpdateResponse left = populatedResponse();
            CardUpdateResponse right = populatedResponse();

            right.setCrdnameo("JANE Q PUBLIC");

            assertThat(left).isNotEqualTo(right);
        }
    }

    static List<Arguments> changeActionBytes() {
        return List.of(
                Arguments.of(ChangeAction.LOW_VALUES, "CCUP-DETAILS-NOT-FETCHED"),
                Arguments.of(ChangeAction.SPACES, "CCUP-DETAILS-NOT-FETCHED"),
                Arguments.of(ChangeAction.SHOW_DETAILS, "CCUP-SHOW-DETAILS"),
                Arguments.of(ChangeAction.CHANGES_NOT_OK, "CCUP-CHANGES-NOT-OK"),
                Arguments.of(ChangeAction.CHANGES_OK_NOT_CONFIRMED,
                        "CCUP-CHANGES-OK-NOT-CONFIRMED"),
                Arguments.of(ChangeAction.CHANGES_OKAYED_AND_DONE, "CCUP-CHANGES-OKAYED-AND-DONE"),
                Arguments.of(ChangeAction.CHANGES_OKAYED_LOCK_ERROR,
                        "CCUP-CHANGES-OKAYED-LOCK-ERROR"),
                Arguments.of(ChangeAction.CHANGES_OKAYED_BUT_FAILED,
                        "CCUP-CHANGES-OKAYED-BUT-FAILED"));
    }

    static List<Arguments> changeActionStates() {
        return List.of(
                Arguments.of(ChangeAction.initial(),
                        "CCUP-DETAILS-NOT-FETCHED"),
                Arguments.of(ChangeAction.showDetails(),
                        "CCUP-SHOW-DETAILS"),
                Arguments.of(ChangeAction.changesNotOk(),
                        "CCUP-CHANGES-NOT-OK"),
                Arguments.of(ChangeAction.changesOkNotConfirmed(),
                        "CCUP-CHANGES-OK-NOT-CONFIRMED"),
                Arguments.of(ChangeAction.changesOkayedAndDone(),
                        "CCUP-CHANGES-OKAYED-AND-DONE"),
                Arguments.of(ChangeAction.changesOkayedLockError(),
                        "CCUP-CHANGES-OKAYED-LOCK-ERROR"),
                Arguments.of(ChangeAction.changesOkayedButFailed(),
                        "CCUP-CHANGES-OKAYED-BUT-FAILED"));
    }

    @Nested
    @DisplayName("the published member order is the order app/cpy-bms/COCRDUP.CPY declares")
    class BmsSerialisationOrder {
        private static final List<String> MAP_PROJECTION = List.of(
                "trnname", "title01", "curdate", "pgmname", "title02", "curtime", "acctsid",
                "cardsid", "crdname", "crdstcd", "expmon", "expyear", "expday", "infomsg",
                "errmsg", "fkeys", "fkeysc");

        private static final List<String> TRANSPORT_EXTENSIONS = List.of(
                "stateToken", "cardScreenState", "navigationContext", "nextProgram", "nextMapset",
                "nextMap");

        private static final List<String> PUBLISHED_ORDER =
                joined(MAP_PROJECTION, TRANSPORT_EXTENSIONS);

        private static List<String> joined(List<String> first, List<String> second) {
            List<String> all = new ArrayList<>(first);
            all.addAll(second);
            return List.copyOf(all);
        }

        private static List<String> publishedMembers() {
            JsonNode body = new ObjectMapper().valueToTree(new CardUpdateResponse());
            List<String> published = new ArrayList<>();
            body.fieldNames().forEachRemaining(published::add);
            return published;
        }

        @Test
        @DisplayName("every member is published exactly once, in exactly that order")
        void theOrderIsTheMapsOwnOrder() {
            assertThat(publishedMembers()).containsExactlyElementsOf(PUBLISHED_ORDER);
        }

        @Test
        @DisplayName("the map projection leads and the transport extensions follow it")
        void theMapProjectionLeadsAndTransportFollows() {
            List<String> published = publishedMembers();
            assertThat(published.subList(0, MAP_PROJECTION.size()))
                    .containsExactlyElementsOf(MAP_PROJECTION);
            assertThat(published.subList(MAP_PROJECTION.size(), published.size()))
                    .containsExactlyElementsOf(TRANSPORT_EXTENSIONS);
        }

        @Test
        @DisplayName("the order is declared on the type, so it cannot be derived from reflection")
        void theOrderIsDeclaredAndNotDerived() {
            JsonPropertyOrder declared = CardUpdateResponse.class.getAnnotation(JsonPropertyOrder.class);
            assertThat(declared).as("the published order must be fixed by annotation").isNotNull();
            assertThat(declared.value()).containsExactlyElementsOf(PUBLISHED_ORDER);
        }
    }
}
