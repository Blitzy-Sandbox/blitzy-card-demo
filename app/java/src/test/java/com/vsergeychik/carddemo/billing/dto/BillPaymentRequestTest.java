package com.vsergeychik.carddemo.billing.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.common.NavigationContext;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Size;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests for {@link BillPaymentRequest}, the inbound projection of the {@code xxxI} items of
 * {@code 01 COBIL0AI} in {@code app/cpy-bms/COBIL00.CPY}, driven by {@code app/cbl/COBIL00C.cbl}.
 *
 * <p>The suite is organised around the translation decisions that determine the implementation, so a
 * failure names a decision rather than merely a value:
 *
 * <ol>
 *   <li><strong>The width policy.</strong> All ten map members carry {@code @Size(max = <declared
 *       width>)} and nothing else. The widths are 4, 40, 8, 8, 40, 8, 11, 14, 1 and 78, taken from
 *       {@code app/cpy-bms/COBIL00.CPY:24-78}. A value exactly at the width binds, a value one
 *       character over is refused, and short, blank and absent values all bind unaltered.</li>
 *   <li><strong>No presence constraint anywhere.</strong> {@code COBIL00C.cbl:159} tests the account
 *       identifier for {@code SPACES OR LOW-VALUES} and {@code :180-184} treats a blank confirmation
 *       as a valid path, so a presence rule would delete reachable behaviour rather than protect
 *       it.</li>
 *   <li><strong>{@code CDEMO-PGM-CONTEXT} has exactly one home.</strong> It is declared inside
 *       {@code 01 CARDDEMO-COMMAREA} at {@code app/cpy/COCOM01Y.cpy:29}, and
 *       {@code COBIL00C.cbl:64-72} appends six items to that area and no second program-context
 *       field. The class therefore declares no {@code pgmContext} member: it is not a JSON property,
 *       it has no setter, and {@link BillPaymentRequest#getPgmContext()} reads through to the
 *       carrier.</li>
 *   <li><strong>Verbatim binding.</strong> {@code null}, the empty string and an all-blank string are
 *       three distinct inbound states, because COBOL distinguishes {@code LOW-VALUES} from
 *       {@code SPACES} and the program tests for both separately.</li>
 *   <li><strong>Statelessness</strong> (gate G37) and <strong>no static mutable state</strong>
 *       (gate G53).</li>
 * </ol>
 */
@DisplayName("BillPaymentRequest - the COBIL00 symbolic-map input projection (POST /api/billpay, CB00)")
class BillPaymentRequestTest {

    /**
     * The ten map members paired with their declared width, in the order {@code 01 COBIL0AI} declares
     * them. This is the whole of the F04 width contract in one place.
     */
    private static final Map<String, Integer> MAP_MEMBER_WIDTHS = buildMapMemberWidths();

    /** The eighteen members that travel on the wire, in declaration order. */
    private static final List<String> WIRE_MEMBERS = List.of("trnName",
            "title01",
            "curDate",
            "pgmName",
            "title02",
            "curTime",
            "actIdIn",
            "curBal",
            "confirm",
            "errMsg",
            "navigationContext",
            "aid",
            "trnIdFirst",
            "trnIdLast",
            "pageNum",
            "nextPageFlg",
            "trnSelFlg",
            "trnSelected");

    private static ValidatorFactory validatorFactory;

    private static Validator validator;

    @BeforeAll
    static void openValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    private static Map<String, Integer> buildMapMemberWidths() {
        Map<String, Integer> widths = new LinkedHashMap<>();
        widths.put("trnName", BillPaymentRequest.TRN_NAME_LENGTH);
        widths.put("title01", BillPaymentRequest.TITLE01_LENGTH);
        widths.put("curDate", BillPaymentRequest.CUR_DATE_LENGTH);
        widths.put("pgmName", BillPaymentRequest.PGM_NAME_LENGTH);
        widths.put("title02", BillPaymentRequest.TITLE02_LENGTH);
        widths.put("curTime", BillPaymentRequest.CUR_TIME_LENGTH);
        widths.put("actIdIn", BillPaymentRequest.ACT_ID_IN_LENGTH);
        widths.put("curBal", BillPaymentRequest.CUR_BAL_LENGTH);
        widths.put("confirm", BillPaymentRequest.CONFIRM_LENGTH);
        widths.put("errMsg", BillPaymentRequest.ERR_MSG_LENGTH);
        return Map.copyOf(widths);
    }

    private static Stream<Arguments> mapMemberWidths() {
        return MAP_MEMBER_WIDTHS.entrySet().stream()
                .map(entry -> Arguments.of(entry.getKey(), entry.getValue()));
    }

    private static String repeat(int length) {
        return "x".repeat(length);
    }

    /** Sets one map member by name, so a parameterised case can drive all ten through one path. */
    private static void set(BillPaymentRequest request, String member, String value) throws Exception {
        String setter = "set" + Character.toUpperCase(member.charAt(0)) + member.substring(1);
        BillPaymentRequest.class.getMethod(setter, String.class).invoke(request, value);
    }

    private static String get(BillPaymentRequest request, String member) throws Exception {
        String getter = "get" + Character.toUpperCase(member.charAt(0)) + member.substring(1);
        return (String) BillPaymentRequest.class.getMethod(getter).invoke(request);
    }

    /** A request with every map member filled exactly at its declared width. */
    private static BillPaymentRequest atDeclaredWidths() throws Exception {
        BillPaymentRequest request = new BillPaymentRequest();
        for (Map.Entry<String, Integer> member : MAP_MEMBER_WIDTHS.entrySet()) {
            set(request, member.getKey(), repeat(member.getValue()));
        }
        return request;
    }

    @Nested
    @DisplayName("1. The width policy - maximum only, on all ten map members")
    class WidthPolicy {

        @ParameterizedTest(name = "{0} declares @Size(max = {1}) and no minimum")
        @MethodSource(
                "com.vsergeychik.carddemo.billing.dto.BillPaymentRequestTest#mapMemberWidths")
        @DisplayName("each map member declares a maximum-only Size at its copybook width")
        void eachMapMemberIsBoundedAtItsCopybookWidth(String member, int width) throws Exception {
            Field field = BillPaymentRequest.class.getDeclaredField(member);
            Size size = field.getAnnotation(Size.class);

            assertThat(size)
                    .as("%s must carry @Size - the symbolic-map PICTURE clause is a hard source width",
                            member)
                    .isNotNull();
            assertThat(size.max()).as("%s maximum", member).isEqualTo(width);
            assertThat(size.min())
                    .as("%s must not carry a minimum: a short or blank screen field is an ordinary "
                            + "inbound state, not a protocol error", member)
                    .isZero();
        }

        @ParameterizedTest(name = "{0} accepts exactly {1} characters")
        @MethodSource(
                "com.vsergeychik.carddemo.billing.dto.BillPaymentRequestTest#mapMemberWidths")
        @DisplayName("a value exactly at the declared width is valid")
        void aValueAtTheDeclaredWidthIsValid(String member, int width) throws Exception {
            BillPaymentRequest request = new BillPaymentRequest();
            set(request, member, repeat(width));

            assertThat(validator.validate(request)).isEmpty();
        }

        @ParameterizedTest(name = "{0} refuses {1} + 1 characters")
        @MethodSource(
                "com.vsergeychik.carddemo.billing.dto.BillPaymentRequestTest#mapMemberWidths")
        @DisplayName("a value one character over the declared width is refused, naming that member")
        void aValueOverTheDeclaredWidthIsRefused(String member, int width) throws Exception {
            BillPaymentRequest request = new BillPaymentRequest();
            set(request, member, repeat(width + 1));

            Set<ConstraintViolation<BillPaymentRequest>> violations = validator.validate(request);

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath())
                    .hasToString(member);
        }

        @ParameterizedTest(name = "{0} accepts a short, a blank and an absent value")
        @MethodSource(
                "com.vsergeychik.carddemo.billing.dto.BillPaymentRequestTest#mapMemberWidths")
        @DisplayName("short, blank and absent values are all valid and are stored unaltered")
        void shortBlankAndAbsentValuesAreValidAndUnaltered(String member, int width) throws Exception {
            BillPaymentRequest shortValue = new BillPaymentRequest();
            set(shortValue, member, "a");
            BillPaymentRequest blank = new BillPaymentRequest();
            set(blank, member, " ".repeat(width));
            BillPaymentRequest empty = new BillPaymentRequest();
            set(empty, member, "");
            BillPaymentRequest absent = new BillPaymentRequest();
            set(absent, member, null);

            assertThat(validator.validate(shortValue)).isEmpty();
            assertThat(validator.validate(blank)).isEmpty();
            assertThat(validator.validate(empty)).isEmpty();
            assertThat(validator.validate(absent)).isEmpty();

            assertThat(get(shortValue, member))
                    .as("%s must not be padded to its declared width during binding", member)
                    .isEqualTo("a");
            assertThat(get(blank, member)).isEqualTo(" ".repeat(width));
            assertThat(get(empty, member)).isEmpty();
            assertThat(get(absent, member)).isNull();
        }

        @Test
        @DisplayName("all ten members are bounded - none is left unguarded")
        void allTenMapMembersAreBounded() {
            List<String> unbounded = new ArrayList<>();
            for (String member : MAP_MEMBER_WIDTHS.keySet()) {
                try {
                    if (BillPaymentRequest.class.getDeclaredField(member).getAnnotation(Size.class)
                            == null) {
                        unbounded.add(member);
                    }
                } catch (NoSuchFieldException absent) {
                    unbounded.add(member + " (missing)");
                }
            }

            assertThat(unbounded).isEmpty();
            assertThat(MAP_MEMBER_WIDTHS).hasSize(BillPaymentRequest.MAP_FIELD_COUNT);
        }

        @Test
        @DisplayName("a fully populated request at declared widths is valid")
        void aFullyPopulatedRequestIsValid() throws Exception {
            assertThat(validator.validate(atDeclaredWidths())).isEmpty();
        }

        @Test
        @DisplayName("an empty request is valid - no member carries a presence constraint")
        void anEmptyRequestIsValid() {
            assertThat(validator.validate(new BillPaymentRequest())).isEmpty();
        }

        @Test
        @DisplayName("Size is the only constraint annotation declared on any field")
        void sizeIsTheOnlyConstraintDeclared() {
            for (Field field : BillPaymentRequest.class.getDeclaredFields()) {
                for (java.lang.annotation.Annotation annotation : field.getAnnotations()) {
                    assertThat(annotation.annotationType())
                            .as("field %s must carry no constraint other than @Size", field.getName())
                            .isEqualTo(Size.class);
                }
            }
        }
    }

    @Nested
    @DisplayName("2. CDEMO-PGM-CONTEXT has exactly one home - the communication area")
    class ProgramContextHasOneHome {

        @Test
        @DisplayName("no pgmContext field is declared on the request")
        void noPgmContextFieldIsDeclared() {
            List<String> fields = new ArrayList<>();
            for (Field field : BillPaymentRequest.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    fields.add(field.getName());
                }
            }

            assertThat(fields).containsExactlyElementsOf(WIRE_MEMBERS);
            assertThat(fields).doesNotContain("pgmContext");
        }

        @Test
        @DisplayName("no setter for the context exists - it is set on the carrier or not at all")
        void noSetterForTheContextExists() {
            List<String> setters = new ArrayList<>();
            for (Method method : BillPaymentRequest.class.getDeclaredMethods()) {
                if (method.getName().startsWith("set")) {
                    setters.add(method.getName());
                }
            }

            assertThat(setters).doesNotContain("setPgmContext");
            assertThat(setters).hasSize(WIRE_MEMBERS.size());
        }

        @Test
        @DisplayName("the context does not serialise as a property of the request")
        void theContextDoesNotSerialise() {
            BillPaymentRequest request = new BillPaymentRequest();
            request.setNavigationContext(NavigationContext.empty().withPgmReenter());

            JsonNode body = new ObjectMapper().valueToTree(request);

            assertThat(body.has("pgmContext"))
                    .as("pgmContext must not be a property of the request - it belongs to the "
                            + "communication area")
                    .isFalse();
            assertThat(body.get("navigationContext").get("pgmContext").asInt())
                    .isEqualTo(NavigationContext.PGM_CONTEXT_REENTER);
        }

        @Test
        @DisplayName("a pgmContext in the body cannot displace the carrier's value")
        void aContextInTheBodyCannotDisplaceTheCarrier() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            String carrier = mapper.writeValueAsString(NavigationContext.empty().withPgmReenter());
            String body = "{\"navigationContext\":" + carrier + ",\"pgmContext\":0}";

            BillPaymentRequest bound = mapper.readValue(body, BillPaymentRequest.class);

            assertThat(bound.getPgmContext())
                    .as("the carrier decides, not a sibling property")
                    .isEqualTo(NavigationContext.PGM_CONTEXT_REENTER);
            assertThat(bound.getNavigationContext().isReenter()).isTrue();
        }

        @Test
        @DisplayName("the accessor reads the value the carrier holds")
        void theAccessorReadsThroughToTheCarrier() {
            BillPaymentRequest onFirstEntry = new BillPaymentRequest();
            onFirstEntry.setNavigationContext(NavigationContext.empty().withPgmEnter());
            BillPaymentRequest onReentry = new BillPaymentRequest();
            onReentry.setNavigationContext(NavigationContext.empty().withPgmReenter());

            assertThat(onFirstEntry.getPgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_ENTER);
            assertThat(onReentry.getPgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_REENTER);
        }

        @Test
        @DisplayName("a third digit reaches the accessor unaltered - PIC 9(01) is not an enumeration")
        void aThirdDigitReachesTheAccessorUnaltered() {
            BillPaymentRequest request = new BillPaymentRequest();
            request.setNavigationContext(NavigationContext.empty().withPgmContext(7));

            assertThat(request.getPgmContext()).isEqualTo(7);
            assertThat(request.getNavigationContext().isEnter()).isFalse();
            assertThat(request.getNavigationContext().isReenter()).isFalse();
        }

        @Test
        @DisplayName("with no communication area the accessor reports the enter arm")
        void withNoCommunicationAreaTheAccessorReportsEnter() {
            BillPaymentRequest coldStart = new BillPaymentRequest();

            assertThat(coldStart.getNavigationContext())
                    .as("EIBCALEN = 0 at app/cbl/COBIL00C.cbl:107 is represented as an absent carrier")
                    .isNull();
            assertThat(coldStart.getPgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_ENTER);
        }

        @Test
        @DisplayName("absence survives binding and is distinguishable from a present zero context")
        void absenceSurvivesBindingAndIsDistinguishable() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            String carrier = mapper.writeValueAsString(NavigationContext.empty().withPgmEnter());

            BillPaymentRequest absent =
                    mapper.readValue("{\"actIdIn\":\"1\"}", BillPaymentRequest.class);
            BillPaymentRequest present = mapper.readValue(
                    "{\"navigationContext\":" + carrier + "}", BillPaymentRequest.class);

            assertThat(absent.getNavigationContext()).isNull();
            assertThat(present.getNavigationContext()).isNotNull();
            assertThat(absent.getPgmContext()).isEqualTo(present.getPgmContext());
            assertThat(absent.getNavigationContext() == null)
                    .as("the carrier reference is the discriminator the read-through leaves to callers")
                    .isNotEqualTo(present.getNavigationContext() == null);
        }

        @Test
        @DisplayName("the diagnostic rendering names no separate context")
        void theDiagnosticRenderingNamesNoSeparateContext() {
            BillPaymentRequest request = new BillPaymentRequest();
            request.setNavigationContext(NavigationContext.empty().withPgmReenter());

            String rendered = request.toString();

            // The payload declares no context member of its own: the only pgmContext in the rendering
            // is the one inside the carrier it delegates to, so the request's own member list - the text
            // before the carrier begins - names none.
            String ownMembers = rendered.substring(0, rendered.indexOf("navigationContext="));
            assertThat(ownMembers).doesNotContain("pgmContext");
            assertThat(rendered).contains("navigationContext=");
            assertThat(rendered).contains("pgmContext=1");
        }
    }

    @Nested
    @DisplayName("3. The wire format - eighteen members and nothing else")
    class WireFormat {

        @Test
        @DisplayName("the serialised body carries exactly the eighteen members")
        void theBodyCarriesExactlyTheEighteenMembers() throws Exception {
            JsonNode body = new ObjectMapper().valueToTree(atDeclaredWidths());

            List<String> published = new ArrayList<>();
            body.fieldNames().forEachRemaining(published::add);

            assertThat(published).containsExactlyInAnyOrderElementsOf(WIRE_MEMBERS);
        }

        @Test
        @DisplayName("no metadata item is published - no xxxL, xxxF, xxxA or xxxC key")
        void noMetadataItemIsPublished() throws Exception {
            JsonNode body = new ObjectMapper().valueToTree(atDeclaredWidths());

            body.fieldNames().forEachRemaining(name -> assertThat(name)
                    .as("%s must not be a length, flag or attribute item", name)
                    .doesNotEndWith("L")
                    .doesNotEndWith("F")
                    .doesNotEndWith("A")
                    .doesNotEndWith("C"));
        }

        @Test
        @DisplayName("no derived predicate leaks as a property")
        void noDerivedPredicateLeaksAsAProperty() {
            BillPaymentRequest request = new BillPaymentRequest();
            request.setNextPageFlg(BillPaymentRequest.NEXT_PAGE_YES);

            JsonNode body = new ObjectMapper().valueToTree(request);

            assertThat(body.has("nextPageYes")).isFalse();
            assertThat(body.has("nextPageNo")).isFalse();
            assertThat(body.get("nextPageFlg").asText()).isEqualTo(BillPaymentRequest.NEXT_PAGE_YES);
        }

        @Test
        @DisplayName("a round trip through JSON is lossless for every member")
        void aRoundTripIsLossless() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            BillPaymentRequest original = atDeclaredWidths();
            original.setNavigationContext(NavigationContext.empty().withPgmReenter());
            original.setAid("PFK03");
            original.setTrnIdFirst("0000000000000001");
            original.setTrnIdLast("0000000000000010");
            original.setPageNum(3);
            original.setNextPageFlg(BillPaymentRequest.NEXT_PAGE_YES);
            original.setTrnSelFlg("S");
            original.setTrnSelected("0000000000000007");

            BillPaymentRequest bound = mapper.readValue(
                    mapper.writeValueAsString(original), BillPaymentRequest.class);

            for (String member : MAP_MEMBER_WIDTHS.keySet()) {
                assertThat(get(bound, member)).as(member).isEqualTo(get(original, member));
            }
            assertThat(bound.getNavigationContext()).isEqualTo(original.getNavigationContext());
            assertThat(bound.getAid()).isEqualTo("PFK03");
            assertThat(bound.getTrnIdFirst()).isEqualTo("0000000000000001");
            assertThat(bound.getTrnIdLast()).isEqualTo("0000000000000010");
            assertThat(bound.getPageNum()).isEqualTo(3);
            assertThat(bound.getNextPageFlg()).isEqualTo(BillPaymentRequest.NEXT_PAGE_YES);
            assertThat(bound.getTrnSelFlg()).isEqualTo("S");
            assertThat(bound.getTrnSelected()).isEqualTo("0000000000000007");
            assertThat(bound.getPgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_REENTER);
        }
    }

    @Nested
    @DisplayName("4. Verbatim binding - null, empty and blank are three distinct states")
    class VerbatimBinding {

        @Test
        @DisplayName("the three inbound states survive a round trip distinctly")
        void theThreeInboundStatesSurviveDistinctly() throws Exception {
            ObjectMapper mapper = new ObjectMapper();

            BillPaymentRequest absent = mapper.readValue(
                    "{\"confirm\":null}", BillPaymentRequest.class);
            BillPaymentRequest empty = mapper.readValue(
                    "{\"confirm\":\"\"}", BillPaymentRequest.class);
            BillPaymentRequest blank = mapper.readValue(
                    "{\"confirm\":\" \"}", BillPaymentRequest.class);

            assertThat(absent.getConfirm()).isNull();
            assertThat(empty.getConfirm()).isEmpty();
            assertThat(blank.getConfirm()).isEqualTo(" ");
        }

        @Test
        @DisplayName("a leading and trailing blank is preserved, never trimmed")
        void surroundingBlanksArePreserved() {
            BillPaymentRequest request = new BillPaymentRequest();
            request.setActIdIn(" 12345678 ");

            assertThat(request.getActIdIn()).isEqualTo(" 12345678 ");
        }

        @Test
        @DisplayName("the balance is carried as the edited text the mask produced, not as a number")
        void theBalanceIsCarriedAsEditedText() throws Exception {
            BillPaymentRequest request = new BillPaymentRequest();
            request.setCurBal("+0000001234.56");

            assertThat(request.getCurBal()).isEqualTo("+0000001234.56");
            assertThat(request.getCurBal()).hasSize(BillPaymentRequest.CUR_BAL_LENGTH);
            assertThat(BillPaymentRequest.class.getDeclaredField("curBal").getType())
                    .isEqualTo(String.class);
        }
    }

    @Nested
    @DisplayName("5. The declared geometry of 01 COBIL0AI")
    class Geometry {

        @Test
        @DisplayName("the ten widths sum to the declared data length")
        void theTenWidthsSumToTheDeclaredDataLength() {
            int sum = MAP_MEMBER_WIDTHS.values().stream().mapToInt(Integer::intValue).sum();

            assertThat(sum).isEqualTo(BillPaymentRequest.MAP_DATA_LENGTH).isEqualTo(212);
        }

        @Test
        @DisplayName("the symbolic map is the prefix plus ten prologues plus the data")
        void theSymbolicMapIsPrefixProloguesAndData() {
            assertThat(BillPaymentRequest.SYMBOLIC_MAP_LENGTH)
                    .isEqualTo(BillPaymentRequest.TIOAPFX_PREFIX_LENGTH
                            + BillPaymentRequest.MAP_FIELD_COUNT
                                    * BillPaymentRequest.FIELD_PROLOGUE_LENGTH
                            + BillPaymentRequest.MAP_DATA_LENGTH)
                    .isEqualTo(294);
        }

        @Test
        @DisplayName("the CB00 extension is 58 bytes on top of the 160-byte communication area")
        void theExtensionIsFiftyEightBytesOnTopOfTheCommunicationArea() {
            assertThat(BillPaymentRequest.CB00_INFO_LENGTH).isEqualTo(58);
            assertThat(BillPaymentRequest.CB00_COMMAREA_LENGTH)
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH
                            + BillPaymentRequest.CB00_INFO_LENGTH)
                    .isEqualTo(218);
        }

        @Test
        @DisplayName("the map, mapset, program and transaction names are the CSD names")
        void theCsdNamesAreCarried() {
            assertThat(BillPaymentRequest.MAPSET_NAME).isEqualTo("COBIL00");
            assertThat(BillPaymentRequest.MAP_NAME).isEqualTo("COBIL0A");
            assertThat(BillPaymentRequest.PROGRAM_NAME).isEqualTo("COBIL00C");
            assertThat(BillPaymentRequest.TRANSACTION_ID).isEqualTo("CB00");
        }
    }

    @Nested
    @DisplayName("6. The six CDEMO-CB00-INFO members")
    class Cb00Extension {

        @Test
        @DisplayName("the paging flag defaults to 'N', the VALUE clause the source declares")
        void thePagingFlagDefaultsToNo() {
            assertThat(new BillPaymentRequest().getNextPageFlg())
                    .isEqualTo(BillPaymentRequest.NEXT_PAGE_NO);
            assertThat(new BillPaymentRequest().nextPageNo()).isTrue();
            assertThat(new BillPaymentRequest().nextPageYes()).isFalse();
        }

        @Test
        @DisplayName("the two condition names are independent tests, not each other's negation")
        void theTwoConditionNamesAreIndependent() {
            BillPaymentRequest yes = new BillPaymentRequest();
            yes.setNextPageFlg(BillPaymentRequest.NEXT_PAGE_YES);
            BillPaymentRequest other = new BillPaymentRequest();
            other.setNextPageFlg(" ");
            BillPaymentRequest absent = new BillPaymentRequest();
            absent.setNextPageFlg(null);

            assertThat(yes.nextPageYes()).isTrue();
            assertThat(yes.nextPageNo()).isFalse();
            assertThat(other.nextPageYes()).isFalse();
            assertThat(other.nextPageNo()).isFalse();
            assertThat(absent.nextPageYes()).isFalse();
            assertThat(absent.nextPageNo()).isFalse();
        }

        @Test
        @DisplayName("the six members are carried and read back unaltered")
        void theSixMembersAreCarriedUnaltered() {
            BillPaymentRequest request = new BillPaymentRequest();
            request.setTrnIdFirst("0000000000000001");
            request.setTrnIdLast("0000000000000010");
            request.setPageNum(12);
            request.setNextPageFlg(BillPaymentRequest.NEXT_PAGE_YES);
            request.setTrnSelFlg("S");
            request.setTrnSelected("0000000000000007");

            assertThat(request.getTrnIdFirst()).isEqualTo("0000000000000001");
            assertThat(request.getTrnIdLast()).isEqualTo("0000000000000010");
            assertThat(request.getPageNum()).isEqualTo(12);
            assertThat(request.getNextPageFlg()).isEqualTo(BillPaymentRequest.NEXT_PAGE_YES);
            assertThat(request.getTrnSelFlg()).isEqualTo("S");
            assertThat(request.getTrnSelected()).isEqualTo("0000000000000007");
        }

        @Test
        @DisplayName("the extension members carry no width constraint of their own")
        void theExtensionMembersCarryNoConstraint() throws Exception {
            for (String member : List.of("trnIdFirst", "trnIdLast", "nextPageFlg", "trnSelFlg",
                    "trnSelected")) {
                assertThat(BillPaymentRequest.class.getDeclaredField(member)
                        .getAnnotation(Size.class))
                        .as("%s is a communication-area item, not a map field", member)
                        .isNull();
            }
        }
    }

    @Nested
    @DisplayName("7. Statelessness and immutable statics")
    class Statelessness {

        @Test
        @DisplayName("the type holds no static mutable state")
        void theTypeHoldsNoStaticMutableState() {
            for (Field field : BillPaymentRequest.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("static field %s must be final", field.getName())
                            .isTrue();
                    assertThat(field.getType().isPrimitive() || field.getType() == String.class)
                            .as("static field %s must be an immutable constant, was %s",
                                    field.getName(), field.getType().getName())
                            .isTrue();
                }
            }
        }

        @Test
        @DisplayName("two instances share nothing")
        void twoInstancesShareNothing() {
            BillPaymentRequest first = new BillPaymentRequest();
            BillPaymentRequest second = new BillPaymentRequest();
            first.setActIdIn("00000000001");

            assertThat(second.getActIdIn()).isNull();
            assertThat(second.getNavigationContext()).isNull();
        }

        @Test
        @DisplayName("the diagnostic rendering is total and never throws on an empty request")
        void theDiagnosticRenderingIsTotal() {
            BillPaymentRequest empty = new BillPaymentRequest();

            assertThatCode(empty::toString).doesNotThrowAnyException();
            assertThat(empty.toString()).startsWith("BillPaymentRequest[").endsWith("]");
        }
    }
}
