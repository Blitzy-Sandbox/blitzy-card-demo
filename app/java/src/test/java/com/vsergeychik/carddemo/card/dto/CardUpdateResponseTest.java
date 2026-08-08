package com.vsergeychik.carddemo.card.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

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
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldHighlight;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldValidationState;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenTitles;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies {@link CardUpdateResponse} against its sources: {@code app/cpy-bms/COCRDUP.CPY} (the
 * {@code 01 CCRDUPAO} output group at L121), {@code app/bms/COCRDUP.bms} and
 * {@code app/cbl/COCRDUPC.cbl}.
 *
 * <p>The first three nested classes are about one property that the review found broken and that no
 * test previously covered: the response and the request are two halves of one CICS conversation, so
 * the state they both carry has to be <em>one</em> thing. This type used to declare its own
 * {@code ProgCommarea}, {@code CcupDetails}, {@code DetailsPrefix} and {@code CardUpdateRecord}
 * against the request's {@link CommArea}, {@link CardDetails}, {@link DetailGroup} and
 * {@link CardUpdateRecord}, and published them under the member names {@code progCommarea},
 * {@code screenState} and {@code navigation} against the request's {@code commArea},
 * {@code cardScreenState} and {@code navigationContext}. Same 329 bytes, two JSON shapes, so a
 * client could not echo back what it received. Those assertions are grouped first because they are
 * the ones a reviewer is most likely to want to check against the COBOL.
 *
 * <p>The remaining nested classes cover the geometry and the projection, which had no test at all.
 */
@DisplayName("CardUpdateResponse - CCRDUPAO, 17 named fields of 34, and one shared 329-byte area")
class CardUpdateResponseTest {

    /** The text fixtures' code page. Named explicitly; the platform default is never used. */
    private static final FixedWidthCodec ASCII = new FixedWidthCodec(StandardCharsets.US_ASCII);

    /** The EBCDIC datasets' code page, so every byte assertion is checked in both. */
    private static final Charset IBM037 = Charset.forName("IBM037");

    /** A codec over {@link #IBM037}. */
    private static final FixedWidthCodec EBCDIC = new FixedWidthCodec(IBM037);

    /**
     * The 17 {@code DFHMDF} labels in {@code app/bms/COCRDUP.bms} declaration order.
     *
     * <p>Written out rather than read from the type under test, so a reordering of
     * {@code CardUpdateResponse.FIELDS} is a test failure rather than a silent change of contract.
     */
    private static final List<String> FIELD_NAMES = List.of("TRNNAME", "TITLE01", "CURDATE",
            "PGMNAME", "TITLE02", "CURTIME", "ACCTSID", "CARDSID", "CRDNAME", "CRDSTCD", "EXPMON",
            "EXPYEAR", "EXPDAY", "INFOMSG", "ERRMSG", "FKEYS", "FKEYSC");

    /** The three JSON members that must be spelled identically on the request and the response. */
    private static final List<String> SHARED_CARRIERS =
            List.of("commArea", "cardScreenState", "navigationContext");

    /** The member names this type published before the carriers were unified. */
    private static final List<String> RETIRED_CARRIER_NAMES =
            List.of("progCommarea", "screenState", "navigation");

    /** The nested type names this type declared before the carriers were unified. */
    private static final List<String> RETIRED_NESTED_TYPES =
            List.of("ProgCommarea", "CcupDetails", "DetailsPrefix", "CardUpdateRecord");

    /** A card number that is distinguishable from padding in every assertion below. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** Collects the top-level member names of a serialised value, in emission order. */
    private static List<String> membersOf(Object value) {
        ObjectNode node = (ObjectNode) new ObjectMapper().valueToTree(value);
        List<String> members = new ArrayList<>();
        node.fieldNames().forEachRemaining(members::add);
        return members;
    }

    /** A populated {@code CCUP-OLD-DETAILS} or {@code CCUP-NEW-DETAILS} snapshot. */
    private static CardDetails details(DetailGroup group, String cardid) {
        return new CardDetails(group, "00000000011", cardid, "123",
                "JOHN Q PUBLIC                                     ", "2026", "07", "19", "Y");
    }

    /** A populated {@code WS-THIS-PROGCOMMAREA} with every one of its four groups distinguishable. */
    private static CommArea populatedCommArea() {
        return new CommArea(ChangeAction.changesOkNotConfirmed(),
                details(DetailGroup.OLD, CARD_NUMBER),
                details(DetailGroup.NEW, "4111111111112222"),
                CardUpdateRecord.initialised().withCardUpdateNum(CARD_NUMBER)
                        .withCardUpdateAcctId(11L).withCardUpdateCvvCd(123));
    }

    /** A work area with something in every one of the members the response echoes. */
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

    /** A fully populated response, used wherever a non-default instance is needed. */
    private static CardUpdateResponse populatedResponse() {
        CardUpdateResponse response = new CardUpdateResponse();
        response.screenInit(DateHeader.of(ASCII, LocalDateTime.of(2022, 7, 19, 23, 15, 58)));
        response.setAcctsido("00000000011");
        response.setCardsido(CARD_NUMBER);
        response.setCrdnameo("JOHN Q PUBLIC");
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
            assertThat(shared).containsExactlyInAnyOrderElementsOf(SHARED_CARRIERS);
        }

        @Test
        @DisplayName("the response carries 23 members: 17 xxxO items, 3 carriers, 3 XCTL targets")
        void theResponseCarriesTwentyThreeMembers() {
            List<String> members = membersOf(new CardUpdateResponse());
            assertThat(members).hasSize(23);
            assertThat(members).containsAll(SHARED_CARRIERS);
            assertThat(members).contains("nextProgram", "nextMapset", "nextMap");
            for (String name : FIELD_NAMES) {
                assertThat(members).contains(name.toLowerCase(java.util.Locale.ROOT) + "o");
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
        @DisplayName("the response's commArea node binds into a request and compares equal")
        void theCommAreaCrossesThePairWithoutRewriting() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            CardUpdateResponse response = populatedResponse();
            JsonNode emitted = mapper.valueToTree(response).get("commArea");

            ObjectNode body = mapper.createObjectNode();
            body.set("commArea", emitted);
            CardUpdateRequest bound =
                    mapper.readValue(mapper.writeValueAsString(body), CardUpdateRequest.class);

            assertThat(bound.getCommArea()).isEqualTo(response.getCommArea());
        }

        @Test
        @DisplayName("the 329-byte image is byte-identical on both sides of the pair")
        void theImageIsIdenticalAcrossThePair() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            CardUpdateResponse response = populatedResponse();
            ObjectNode body = mapper.createObjectNode();
            body.set("commArea", mapper.valueToTree(response).get("commArea"));
            CardUpdateRequest bound =
                    mapper.readValue(mapper.writeValueAsString(body), CardUpdateRequest.class);

            assertThat(bound.getCommArea().encode(ASCII))
                    .hasSize(CommArea.RECORD_LENGTH)
                    .isEqualTo(response.getCommArea().encode(ASCII));
            assertThat(bound.getCommArea().encode(EBCDIC))
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
            ObjectNode body = mapper.createObjectNode();
            body.set("commArea", mapper.valueToTree(populatedResponse()).get("commArea"));
            CardUpdateRequest bound =
                    mapper.readValue(mapper.writeValueAsString(body), CardUpdateRequest.class);

            assertThat(bound.getNavigationContext()).isNull();
            assertThat(bound.hasNavigationContext()).isFalse();
            assertThat(bound.commareaLength()).isZero();
            assertThat(bound.getCommArea()).isEqualTo(populatedResponse().getCommArea());
        }

        @Test
        @DisplayName("a whole response round-trips through JSON and compares equal")
        void aWholeResponseRoundTrips() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            CardUpdateResponse response = populatedResponse();
            CardUpdateResponse restored = mapper.readValue(
                    mapper.writeValueAsString(response), CardUpdateResponse.class);

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

            response.screenInit(DateHeader.of(ASCII, LocalDateTime.of(2022, 7, 19, 23, 15, 58)));

            assertThat(response.getTrnnameo()).isEqualTo("CCUP");
            assertThat(response.getPgmnameo()).isEqualTo("COCRDUPC");
            assertThat(response.getTitle01o()).isEqualTo(ScreenTitles.CCDA_TITLE01);
            assertThat(response.getTitle02o()).isEqualTo(ScreenTitles.CCDA_TITLE02);
            assertThat(response.getCurdateo()).isEqualTo("07/19/22");
            assertThat(response.getCurtimeo()).isEqualTo("23:15:58");
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
    @DisplayName("The attribute quad - CSSETATY's target, never a JSON member")
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
        @DisplayName("CSSETATY in REENTER state moves DFHRED and an asterisk onto the field")
        void theHighlightAppliesInReenterState() {
            CardUpdateResponse response = new CardUpdateResponse();

            FieldHighlight applied = response.applyHighlight("ACCTSID",
                    FieldValidationState.NOT_OK, true);

            assertThat(applied.colourItemAssigned()).isTrue();
            assertThat(response.colourOf("ACCTSID")).isEqualTo(applied.colourItemValue());
        }

        @Test
        @DisplayName("CSSETATY does nothing in ENTER state, because line 20 guards on REENTER")
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
            FieldHighlight foreign = com.vsergeychik.carddemo.common.FieldAttributeSetter
                    .resolve(FieldValidationState.NOT_OK, true, "ACCTSID", "CCRDLIA");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.applyHighlight(foreign))
                    .withMessageContaining("CCRDLIA")
                    .withMessageContaining("CCRDUPA");
        }

        @Test
        @DisplayName("applyHighlight refuses a decision naming a field this map does not declare")
        void applyHighlightRefusesAForeignField() {
            CardUpdateResponse response = new CardUpdateResponse();
            FieldHighlight foreign = com.vsergeychik.carddemo.common.FieldAttributeSetter.resolve(
                    FieldValidationState.NOT_OK, true, "CRDSTP1", CardUpdateResponse.MAP_NAME);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.applyHighlight(foreign))
                    .withMessageContaining("CRDSTP1");
        }

        @Test
        @DisplayName("applyHighlight refuses a decision that names no field at all")
        void applyHighlightRefusesAPrefixlessDecision() {
            CardUpdateResponse response = new CardUpdateResponse();
            FieldHighlight prefixless = com.vsergeychik.carddemo.common.FieldAttributeSetter
                    .resolve(FieldValidationState.NOT_OK, true);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.applyHighlight(prefixless))
                    .withMessageContaining("SCRNVAR2");
        }

        @Test
        @DisplayName("a decision that names a field but no map is accepted, since it cannot clash")
        void applyHighlightAcceptsAMaplessDecision() {
            CardUpdateResponse response = new CardUpdateResponse();
            FieldHighlight mapless = com.vsergeychik.carddemo.common.FieldAttributeSetter
                    .resolve(FieldValidationState.NOT_OK, true, "ACCTSID", "");

            response.applyHighlight(mapless);

            assertThat(response.colourOf("ACCTSID")).isEqualTo(mapless.colourItemValue());
        }

        @Test
        @DisplayName("a decision that assigns no output item leaves the payload alone")
        void applyHighlightLeavesThePayloadAloneWhenNoOutputIsAssigned() {
            CardUpdateResponse response = new CardUpdateResponse();
            response.setOutputItem("ACCTSID", "00000000011");
            FieldHighlight colourOnly = com.vsergeychik.carddemo.common.FieldAttributeSetter
                    .resolve(FieldValidationState.OK, true, "ACCTSID", CardUpdateResponse.MAP_NAME);

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

    /**
     * The seven {@code 88}-level states of {@code CCUP-CHANGE-ACTION} paired with the name the
     * diagnostic must report, in {@code app/cbl/COCRDUPC.cbl:276-290} declaration order.
     *
     * @return one argument pair per condition name
     */
    static List<org.junit.jupiter.params.provider.Arguments> changeActionStates() {
        return List.of(
                org.junit.jupiter.params.provider.Arguments.of(ChangeAction.initial(),
                        "CCUP-DETAILS-NOT-FETCHED"),
                org.junit.jupiter.params.provider.Arguments.of(ChangeAction.showDetails(),
                        "CCUP-SHOW-DETAILS"),
                org.junit.jupiter.params.provider.Arguments.of(ChangeAction.changesNotOk(),
                        "CCUP-CHANGES-NOT-OK"),
                org.junit.jupiter.params.provider.Arguments.of(ChangeAction.changesOkNotConfirmed(),
                        "CCUP-CHANGES-OK-NOT-CONFIRMED"),
                org.junit.jupiter.params.provider.Arguments.of(ChangeAction.changesOkayedAndDone(),
                        "CCUP-CHANGES-OKAYED-AND-DONE"),
                org.junit.jupiter.params.provider.Arguments.of(ChangeAction.changesOkayedLockError(),
                        "CCUP-CHANGES-OKAYED-LOCK-ERROR"),
                org.junit.jupiter.params.provider.Arguments.of(
                        ChangeAction.changesOkayedButFailed(),
                        "CCUP-CHANGES-OKAYED-BUT-FAILED"));
    }
}
