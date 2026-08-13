package com.vsergeychik.carddemo.billing.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.vsergeychik.carddemo.common.NavigationContext;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
 * Unit tests for {@link BillPaymentRequest}, the inbound payload of the CardDemo bill-payment screen and a
 * field-for-field projection of the {@code xxxI} items of {@code 01 COBIL0AI}.
 */
@DisplayName("BillPaymentRequest - the COBIL00 symbolic-map input projection (POST /api/billpay, CB00)")
class BillPaymentRequestTest {
    private static final Map<String, Integer> MAP_MEMBER_WIDTHS = buildMapMemberWidths();

    private static final Map<String, String> WIDTH_CONSTANT_NAMES = buildWidthConstantNames();

    private static final List<String> SYMBOLIC_MAP_ITEM_STEMS = List.of("TRNNAME",
            "TITLE01",
            "CURDATE",
            "PGMNAME",
            "TITLE02",
            "CURTIME",
            "ACTIDIN",
            "CURBAL",
            "CONFIRM",
            "ERRMSG");

    private static final List<String> METADATA_SUFFIXES =
            List.of("L", "F", "A", "C", "P", "H", "V");

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

    private static final java.util.Set<String> SCREEN_FIELD_MEMBERS = java.util.Set.of(
            "trnName", "title01", "curDate", "pgmName", "title02", "curTime", "actIdIn", "curBal",
            "confirm", "errMsg");

    private static String wireNameOf(String member) {
        return SCREEN_FIELD_MEMBERS.contains(member)
                ? member.toLowerCase(java.util.Locale.ROOT) : member;
    }

    private static List<String> wireNamesOf(java.util.Collection<String> members) {
        return members.stream().map(BillPaymentRequestTest::wireNameOf).toList();
    }

    private static final Map<String, Integer> CB00_INFO_WIDTHS = buildCb00InfoWidths();

    private static final List<String> UNREFERENCED_CB00_MEMBERS =
            List.of("trnIdFirst", "trnIdLast", "pageNum", "nextPageFlg", "trnSelFlg");

    private static final ValidatorFactory VALIDATOR_FACTORY = Validation.buildDefaultValidatorFactory();

    private static final Validator VALIDATOR = VALIDATOR_FACTORY.getValidator();

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void confirmValidatorIsAvailable() {
        assertThat(VALIDATOR)
                .as("a Bean Validation provider must be on the test classpath, otherwise every "
                        + "@Size assertion in this suite would succeed vacuously")
                .isNotNull();
    }

    @AfterAll
    static void closeValidator() {
        VALIDATOR_FACTORY.close();
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
        return Collections.unmodifiableMap(widths);
    }

    private static Map<String, String> buildWidthConstantNames() {
        Map<String, String> constants = new LinkedHashMap<>();
        constants.put("trnName", "TRN_NAME_LENGTH");
        constants.put("title01", "TITLE01_LENGTH");
        constants.put("curDate", "CUR_DATE_LENGTH");
        constants.put("pgmName", "PGM_NAME_LENGTH");
        constants.put("title02", "TITLE02_LENGTH");
        constants.put("curTime", "CUR_TIME_LENGTH");
        constants.put("actIdIn", "ACT_ID_IN_LENGTH");
        constants.put("curBal", "CUR_BAL_LENGTH");
        constants.put("confirm", "CONFIRM_LENGTH");
        constants.put("errMsg", "ERR_MSG_LENGTH");
        return Collections.unmodifiableMap(constants);
    }

    private static Map<String, Integer> buildCb00InfoWidths() {
        Map<String, Integer> widths = new LinkedHashMap<>();
        widths.put("trnIdFirst", BillPaymentRequest.TRN_ID_FIRST_LENGTH);
        widths.put("trnIdLast", BillPaymentRequest.TRN_ID_LAST_LENGTH);
        widths.put("pageNum", BillPaymentRequest.PAGE_NUM_DIGITS);
        widths.put("nextPageFlg", BillPaymentRequest.NEXT_PAGE_FLG_LENGTH);
        widths.put("trnSelFlg", BillPaymentRequest.TRN_SEL_FLG_LENGTH);
        widths.put("trnSelected", BillPaymentRequest.TRN_SELECTED_LENGTH);
        return Collections.unmodifiableMap(widths);
    }

    private static Stream<Arguments> mapMemberWidths() {
        return MAP_MEMBER_WIDTHS.entrySet().stream()
                .map(entry -> Arguments.of(entry.getKey(), entry.getValue()));
    }

    private static Stream<Arguments> cb00InfoWidths() {
        return CB00_INFO_WIDTHS.entrySet().stream()
                .map(entry -> Arguments.of(entry.getKey(), entry.getValue()));
    }

    private static Stream<Arguments> editedBalances() {
        return Stream.of(Arguments.of("+0000000019.40"),
                Arguments.of("-0000000019.40"),
                Arguments.of("+0000000000.00"));
    }

    private static String repeat(int length) {
        return "x".repeat(length);
    }

    private static void set(BillPaymentRequest request, String member, String value) throws Exception {
        String setter = "set" + Character.toUpperCase(member.charAt(0)) + member.substring(1);
        BillPaymentRequest.class.getMethod(setter, String.class).invoke(request, value);
    }

    private static String get(BillPaymentRequest request, String member) throws Exception {
        String getter = "get" + Character.toUpperCase(member.charAt(0)) + member.substring(1);
        return (String) BillPaymentRequest.class.getMethod(getter).invoke(request);
    }

    private static BillPaymentRequest atDeclaredWidths() throws Exception {
        BillPaymentRequest request = new BillPaymentRequest();
        for (Map.Entry<String, Integer> member : MAP_MEMBER_WIDTHS.entrySet()) {
            set(request, member.getKey(), repeat(member.getValue()));
        }
        return request;
    }

    private static BillPaymentRequest canonical() throws Exception {
        BillPaymentRequest request = atDeclaredWidths();
        request.setActIdIn("00000000011");
        request.setCurBal("+0000000019.40");
        request.setConfirm("Y");
        request.setNavigationContext(NavigationContext.empty()
                .withFromTranid("CB00")
                .withFromProgram("COBIL00C")
                .withUserId("USER0001")
                .withUserTypeUser()
                .withPgmReenter());
        request.setAid("PFK03");
        request.setTrnIdFirst("0000000000000001");
        request.setTrnIdLast("0000000000000010");
        request.setPageNum(3);
        request.setNextPageFlg(BillPaymentRequest.NEXT_PAGE_YES);
        request.setTrnSelFlg("S");
        request.setTrnSelected("0000000000000007");
        return request;
    }

    private Set<String> publishedNames(BillPaymentRequest request) {
        JsonNode body = mapper.valueToTree(request);
        Set<String> names = new LinkedHashSet<>();
        body.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static List<String> instanceFieldNames() {
        List<String> names = new ArrayList<>();
        for (Field field : BillPaymentRequest.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                names.add(field.getName());
            }
        }
        return names;
    }

    @Nested
    @DisplayName("1. The width policy - a maximum at the copybook width, and no minimum")
    class WidthPolicy {
        @ParameterizedTest(name = "{0} declares @Size(max = {1}) and no minimum")
        @MethodSource(
                "com.vsergeychik.carddemo.billing.dto.BillPaymentRequestTest#mapMemberWidths")
        @DisplayName("each map member is bounded at its xxxI PICTURE width (COBIL00.CPY:24-78)")
        void eachMapMemberIsBoundedAtItsCopybookWidth(String member, int width) throws Exception {
            Field field = BillPaymentRequest.class.getDeclaredField(member);
            Size size = field.getAnnotation(Size.class);

            assertThat(size)
                    .as("%s must carry @Size - the symbolic-map PICTURE clause is a hard source "
                            + "width, since a 3270 RECEIVE MAP cannot deliver more bytes than the "
                            + "DFHMDF declares", member)
                    .isNotNull();
            assertThat(size.max()).as("%s maximum", member).isEqualTo(width);
            assertThat(size.min())
                    .as("%s must not carry a minimum: a short or blank screen field is an ordinary "
                            + "inbound state, not a protocol error", member)
                    .isZero();
        }

        @ParameterizedTest(name = "{0} declares String, never a numeric type")
        @MethodSource(
                "com.vsergeychik.carddemo.billing.dto.BillPaymentRequestTest#mapMemberWidths")
        @DisplayName("every map member is a String - there is no PICIN and no PICOUT in the mapset")
        void everyMapMemberIsAString(String member, int width) throws Exception {
            assertThat(BillPaymentRequest.class.getDeclaredField(member).getType())
                    .as("%s projects PIC X(%d); with no PICIN or PICOUT anywhere in "
                            + "app/bms/COBIL00.bms or app/cpy-bms/COBIL00.CPY, every payload item is "
                            + "plain character data", member, width)
                    .isEqualTo(String.class);
        }

        @ParameterizedTest(name = "{0} accepts exactly {1} characters")
        @MethodSource(
                "com.vsergeychik.carddemo.billing.dto.BillPaymentRequestTest#mapMemberWidths")
        @DisplayName("a value exactly at the declared width is valid")
        void aValueAtTheDeclaredWidthIsValid(String member, int width) throws Exception {
            BillPaymentRequest request = new BillPaymentRequest();
            set(request, member, repeat(width));

            assertThat(VALIDATOR.validate(request)).isEmpty();
        }

        @ParameterizedTest(name = "{0} refuses {1} + 1 characters")
        @MethodSource(
                "com.vsergeychik.carddemo.billing.dto.BillPaymentRequestTest#mapMemberWidths")
        @DisplayName("a value one character over the declared width is refused, naming that member")
        void aValueOverTheDeclaredWidthIsRefused(String member, int width) throws Exception {
            BillPaymentRequest request = new BillPaymentRequest();
            set(request, member, repeat(width + 1));

            Set<ConstraintViolation<BillPaymentRequest>> violations = VALIDATOR.validate(request);

            assertThat(violations).hasSize(1);
            ConstraintViolation<BillPaymentRequest> violation = violations.iterator().next();
            assertThat(violation.getPropertyPath()).hasToString(member);
            assertThat(violation.getConstraintDescriptor().getAnnotation().annotationType())
                    .as("the refusal must come from the width rule, not from a presence rule")
                    .isEqualTo(Size.class);
        }

        @ParameterizedTest(name = "{0} accepts a short, a blank, an empty and an absent value")
        @MethodSource(
                "com.vsergeychik.carddemo.billing.dto.BillPaymentRequestTest#mapMemberWidths")
        @DisplayName("short, blank, empty and absent values are all valid and are stored unaltered")
        void shortBlankAndAbsentValuesAreValidAndUnaltered(String member, int width) throws Exception {
            BillPaymentRequest shortValue = new BillPaymentRequest();
            set(shortValue, member, "a");
            BillPaymentRequest blank = new BillPaymentRequest();
            set(blank, member, " ".repeat(width));
            BillPaymentRequest empty = new BillPaymentRequest();
            set(empty, member, "");
            BillPaymentRequest absent = new BillPaymentRequest();
            set(absent, member, null);

            assertThat(VALIDATOR.validate(shortValue)).isEmpty();
            assertThat(VALIDATOR.validate(blank)).isEmpty();
            assertThat(VALIDATOR.validate(empty)).isEmpty();
            assertThat(VALIDATOR.validate(absent)).isEmpty();

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
        @DisplayName("the two UNPROT fields are bounded at 11 and 1 (COBIL00.bms:85, :115)")
        void theTwoTypeableFieldsAreBoundedAtTheirTerminalWidths() throws Exception {
            assertThat(BillPaymentRequest.class.getDeclaredField("actIdIn")
                    .getAnnotation(Size.class).max())
                    .isEqualTo(BillPaymentRequest.ACT_ID_IN_LENGTH)
                    .isEqualTo(11);
            assertThat(BillPaymentRequest.class.getDeclaredField("confirm")
                    .getAnnotation(Size.class).max())
                    .isEqualTo(BillPaymentRequest.CONFIRM_LENGTH)
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a fully populated request at declared widths is valid")
        void aFullyPopulatedRequestIsValid() throws Exception {
            assertThat(VALIDATOR.validate(atDeclaredWidths())).isEmpty();
            assertThat(VALIDATOR.validate(canonical())).isEmpty();
        }

        @Test
        @DisplayName("an empty request is valid - no member carries a presence constraint")
        void anEmptyRequestIsValid() {
            assertThat(VALIDATOR.validate(new BillPaymentRequest())).isEmpty();
        }

        @Test
        @DisplayName("a blank account id and a blank confirmation are valid (COBIL00C:159, :182-184)")
        void blankTypeableFieldsProduceNoViolation() {
            BillPaymentRequest blank = new BillPaymentRequest();
            blank.setActIdIn("           ");
            blank.setConfirm(" ");
            BillPaymentRequest emptyText = new BillPaymentRequest();
            emptyText.setActIdIn("");
            emptyText.setConfirm("");
            BillPaymentRequest absent = new BillPaymentRequest();
            absent.setActIdIn(null);
            absent.setConfirm(null);

            assertThat(VALIDATOR.validate(blank)).isEmpty();
            assertThat(VALIDATOR.validate(emptyText)).isEmpty();
            assertThat(VALIDATOR.validate(absent)).isEmpty();
        }

        @Test
        @DisplayName("Size is the only constraint on any field or accessor - no presence rule exists")
        void sizeIsTheOnlyConstraintDeclared() {
            List<String> offending = new ArrayList<>();
            for (Field field : BillPaymentRequest.class.getDeclaredFields()) {
                collectForbiddenConstraints("field " + field.getName(), field.getAnnotations(),
                        offending);
            }
            for (Method method : BillPaymentRequest.class.getDeclaredMethods()) {
                collectForbiddenConstraints("method " + method.getName(), method.getAnnotations(),
                        offending);
            }
            collectForbiddenConstraints("the class itself",
                    BillPaymentRequest.class.getAnnotations(), offending);

            assertThat(offending)
                    .as("only @Size may appear; a presence or pattern rule would contradict "
                            + "app/cbl/COBIL00C.cbl:159 and :182-184")
                    .isEmpty();
        }

        @Test
        @DisplayName("no presence or pattern annotation appears anywhere on the type")
        void noPresenceOrPatternAnnotationAppears() {
            List<Class<? extends Annotation>> forbidden =
                    List.of(NotNull.class, NotBlank.class, NotEmpty.class, Pattern.class);

            for (Class<? extends Annotation> annotation : forbidden) {
                assertThat(BillPaymentRequest.class.getAnnotation(annotation))
                        .as("@%s must not annotate the type", annotation.getSimpleName())
                        .isNull();
                for (Field field : BillPaymentRequest.class.getDeclaredFields()) {
                    assertThat(field.getAnnotation(annotation))
                            .as("@%s must not annotate field %s", annotation.getSimpleName(),
                                    field.getName())
                            .isNull();
                }
                for (Method method : BillPaymentRequest.class.getDeclaredMethods()) {
                    assertThat(method.getAnnotation(annotation))
                            .as("@%s must not annotate method %s", annotation.getSimpleName(),
                                    method.getName())
                            .isNull();
                }
            }
        }

        private void collectForbiddenConstraints(String where, Annotation[] annotations,
                List<String> offending) {
            for (Annotation annotation : annotations) {
                String declaringPackage = annotation.annotationType().getPackageName();
                if (declaringPackage.startsWith("jakarta.validation")
                        && annotation.annotationType() != Size.class) {
                    offending.add(where + " carries @" + annotation.annotationType().getSimpleName());
                }
            }
        }
    }

    @Nested
    @DisplayName("2. CDEMO-PGM-CONTEXT has exactly one home - the communication area")
    class ProgramContextHasOneHome {
        @Test
        @DisplayName("the eighteen wire members are declared and no pgmContext field joins them")
        void noPgmContextFieldIsDeclared() {
            List<String> fields = instanceFieldNames();

            assertThat(fields).containsExactlyInAnyOrderElementsOf(WIRE_MEMBERS);
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

            JsonNode body = mapper.valueToTree(request);

            assertThat(body.has("pgmContext"))
                    .as("pgmContext must not be a property of the request - it belongs to the "
                            + "communication area, where app/cpy/COCOM01Y.cpy:29 declares it")
                    .isFalse();
            assertThat(body.get("navigationContext").get("pgmContext").asInt())
                    .isEqualTo(NavigationContext.PGM_CONTEXT_REENTER);
        }

        @Test
        @DisplayName("a pgmContext in the body cannot displace the carrier's value")
        void aContextInTheBodyCannotDisplaceTheCarrier() throws Exception {
            String carrier = mapper.writeValueAsString(NavigationContext.empty().withPgmReenter());
            String body = "{\"navigationContext\":" + carrier + ",\"pgmContext\":0}";

            BillPaymentRequest bound = mapper.readValue(body, BillPaymentRequest.class);

            assertThat(bound.getPgmContext())
                    .as("the carrier decides, not a sibling property")
                    .isEqualTo(NavigationContext.PGM_CONTEXT_REENTER);
            assertThat(bound.getNavigationContext().isReenter()).isTrue();
        }

        @Test
        @DisplayName("the accessor reads the value the carrier holds - the ENTER and REENTER arms")
        void theAccessorReadsThroughToTheCarrier() {
            BillPaymentRequest onFirstEntry = new BillPaymentRequest();
            onFirstEntry.setNavigationContext(NavigationContext.empty().withPgmEnter());
            BillPaymentRequest onReentry = new BillPaymentRequest();
            onReentry.setNavigationContext(NavigationContext.empty().withPgmReenter());

            assertThat(onFirstEntry.getPgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_ENTER);
            assertThat(onFirstEntry.getNavigationContext().isEnter()).isTrue();
            assertThat(onReentry.getPgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_REENTER);
            assertThat(onReentry.getNavigationContext().isReenter()).isTrue();
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
            String carrier = mapper.writeValueAsString(NavigationContext.empty().withPgmEnter());

            BillPaymentRequest absent =
                    mapper.readValue("{\"actidin\":\"1\"}", BillPaymentRequest.class);
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

            String ownMembers = rendered.substring(0, rendered.indexOf("navigationContext="));
            assertThat(ownMembers).doesNotContain("pgmContext");
            assertThat(rendered).contains("navigationContext=");
            assertThat(rendered).contains("pgmContext=1");
        }
    }

    @Nested
    @DisplayName("3. The wire format - eighteen members, no metadata, and strict binding")
    class WireFormat {
        @Test
        @DisplayName("the serialised body carries exactly the eighteen members and nothing else")
        void theBodyCarriesExactlyTheEighteenMembers() throws Exception {
            Set<String> published = publishedNames(canonical());

            assertThat(published).containsExactlyInAnyOrderElementsOf(wireNamesOf(WIRE_MEMBERS));
            assertThat(published).hasSize(18);
        }

        @Test
        @DisplayName("no symbolic-map metadata item is published - all 7 suffixes x 10 fields (G9)")
        void noSymbolicMapMetadataItemIsPublished() throws Exception {
            Set<String> publishedLowerCase = new LinkedHashSet<>();
            for (String name : publishedNames(canonical())) {
                publishedLowerCase.add(name.toLowerCase(Locale.ROOT));
            }

            List<String> leaked = new ArrayList<>();
            for (String stem : SYMBOLIC_MAP_ITEM_STEMS) {
                for (String suffix : METADATA_SUFFIXES) {
                    String metadataItem = (stem + suffix).toLowerCase(Locale.ROOT);
                    if (publishedLowerCase.contains(metadataItem)) {
                        leaked.add(stem + suffix);
                    }
                }
            }

            assertThat(leaked)
                    .as("a symbolic-map metadata item reached the wire; only the ten xxxI data items "
                            + "are payload (gate G9)")
                    .isEmpty();
            assertThat(SYMBOLIC_MAP_ITEM_STEMS).hasSize(BillPaymentRequest.MAP_FIELD_COUNT);
            assertThat(METADATA_SUFFIXES).hasSize(7);
        }

        @Test
        @DisplayName("no derived predicate leaks as a property")
        void noDerivedPredicateLeaksAsAProperty() {
            BillPaymentRequest request = new BillPaymentRequest();
            request.setNextPageFlg(BillPaymentRequest.NEXT_PAGE_YES);

            JsonNode body = mapper.valueToTree(request);

            assertThat(body.has("nextPageYes")).isFalse();
            assertThat(body.has("nextPageNo")).isFalse();
            assertThat(body.get("nextPageFlg").asText()).isEqualTo(BillPaymentRequest.NEXT_PAGE_YES);
        }

        @Test
        @DisplayName("a round trip through JSON is lossless for every member")
        void aRoundTripIsLossless() throws Exception {
            BillPaymentRequest original = canonical();

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

        @Test
        @DisplayName("an undeclared property is refused, while the ignored context name is tolerated")
        void undeclaredPropertiesAreRefusedButIgnoredNamesAreNot() throws Exception {
            assertThatExceptionOfType(UnrecognizedPropertyException.class)
                    .isThrownBy(() -> mapper.readValue(
                            "{\"actidin\":\"00000000011\",\"notAScreenField\":\"x\"}",
                            BillPaymentRequest.class))
                    .withMessageContaining("notAScreenField");

            BillPaymentRequest bound =
                    mapper.readValue("{\"pgmContext\":1}", BillPaymentRequest.class);

            assertThat(bound.getNavigationContext()).isNull();
            assertThat(bound.getPgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_ENTER);
        }

        @Test
        @DisplayName("an empty body binds to the declared initial state and validates")
        void anEmptyBodyBinds() throws Exception {
            BillPaymentRequest bound = mapper.readValue("{}", BillPaymentRequest.class);

            for (String member : MAP_MEMBER_WIDTHS.keySet()) {
                assertThat(get(bound, member)).as(member).isNull();
            }
            assertThat(bound.getNavigationContext()).isNull();
            assertThat(bound.getAid()).isNull();
            assertThat(bound.getPageNum()).isZero();
            assertThat(bound.getNextPageFlg())
                    .as("the VALUE 'N' clause of app/cbl/COBIL00C.cbl:68 survives an empty body")
                    .isEqualTo(BillPaymentRequest.NEXT_PAGE_NO);
            assertThat(VALIDATOR.validate(bound)).isEmpty();
        }

        @Test
        @DisplayName("explicit nulls for the two typeable fields bind and raise no violation")
        void explicitNullsForTheTypeableFieldsBind() throws Exception {
            BillPaymentRequest bound = mapper.readValue(
                    "{\"actidin\":null,\"confirm\":null}", BillPaymentRequest.class);

            assertThat(bound.getActIdIn()).isNull();
            assertThat(bound.getConfirm()).isNull();
            assertThat(VALIDATOR.validate(bound)).isEmpty();
        }
    }

    @Nested
    @DisplayName("4. Verbatim binding - null, empty and blank are three distinct inbound states")
    class VerbatimBinding {
        @Test
        @DisplayName("the three inbound states survive a round trip distinctly")
        void theThreeInboundStatesSurviveDistinctly() throws Exception {
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
        @DisplayName("curBal is a String carrying PIC +9999999999.99 edited text (COBIL00C:56) - G22")
        void theBalanceIsCarriedAsEditedText() throws Exception {
            Class<?> declaredType = BillPaymentRequest.class.getDeclaredField("curBal").getType();

            assertThat(declaredType)
                    .as("curBal must be exactly java.lang.String: a double, float, Double, Float or "
                            + "BigDecimal here would re-open the edited text as a number and would "
                            + "violate gate G22, which forbids a floating-point type for any value "
                            + "derived from a PICTURE clause")
                    .isEqualTo(String.class);
            assertThat(declaredType.isPrimitive()).isFalse();
            assertThat(Number.class.isAssignableFrom(declaredType))
                    .as("curBal must not be a numeric type (gate G22)")
                    .isFalse();

            BillPaymentRequest request = new BillPaymentRequest();
            request.setCurBal("+0000001234.56");

            assertThat(request.getCurBal()).isEqualTo("+0000001234.56");
            assertThat(request.getCurBal()).hasSize(BillPaymentRequest.CUR_BAL_LENGTH);
            assertThat(BillPaymentRequest.CUR_BAL_LENGTH).isEqualTo(14);
        }

        @ParameterizedTest(name = "{0} travels as a quoted JSON string and returns byte-identical")
        @MethodSource(
                "com.vsergeychik.carddemo.billing.dto.BillPaymentRequestTest#editedBalances")
        @DisplayName("the edited balance is quoted in JSON and round-trips unchanged (G22)")
        void theEditedBalanceTravelsAsAQuotedJsonString(String edited) throws Exception {
            BillPaymentRequest request = new BillPaymentRequest();
            request.setCurBal(edited);

            String body = mapper.writeValueAsString(request);

            assertThat(body)
                    .as("the edited text must be quoted, so the sign and every leading zero survive")
                    .contains("\"curbal\":\"" + edited + "\"");
            assertThat(body)
                    .as("curBal must never be emitted as a bare JSON number")
                    .doesNotContain("\"curbal\":" + edited.charAt(0) + "0");

            BillPaymentRequest bound = mapper.readValue(body, BillPaymentRequest.class);

            assertThat(bound.getCurBal())
                    .as("no trimming, no reformatting and no locale involvement")
                    .isEqualTo(edited)
                    .hasSize(BillPaymentRequest.CUR_BAL_LENGTH);
            assertThat(VALIDATOR.validate(bound)).isEmpty();
        }

        @Test
        @DisplayName("errMsg is X(78) and a 78-character value round-trips intact (COBIL00.CPY:78)")
        void theMessageFieldCarriesSeventyEightCharactersIntact() throws Exception {
            String atWidth = "m".repeat(78);
            BillPaymentRequest request = new BillPaymentRequest();
            request.setErrMsg(atWidth);

            BillPaymentRequest bound = mapper.readValue(
                    mapper.writeValueAsString(request), BillPaymentRequest.class);

            assertThat(BillPaymentRequest.ERR_MSG_LENGTH).isEqualTo(78);
            assertThat(bound.getErrMsg()).isEqualTo(atWidth).hasSize(78);
            assertThat(VALIDATOR.validate(bound)).isEmpty();
        }
    }

    @Nested
    @DisplayName("5. The declared geometry of 01 COBIL0AI and of the communication area")
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
            assertThat(BillPaymentRequest.TIOAPFX_PREFIX_LENGTH).isEqualTo(12);
            assertThat(BillPaymentRequest.FIELD_PROLOGUE_LENGTH).isEqualTo(7);
            assertThat(BillPaymentRequest.SYMBOLIC_MAP_LENGTH)
                    .isEqualTo(BillPaymentRequest.TIOAPFX_PREFIX_LENGTH
                            + BillPaymentRequest.MAP_FIELD_COUNT
                                    * BillPaymentRequest.FIELD_PROLOGUE_LENGTH
                            + BillPaymentRequest.MAP_DATA_LENGTH)
                    .isEqualTo(294);
        }

        @Test
        @DisplayName("the communication area is exactly 160 bytes (COCOM01Y.cpy:19-44)")
        void theCommunicationAreaIsOneHundredAndSixtyBytes() {
            assertThat(NavigationContext.GENERAL_INFO_LENGTH).isEqualTo(34);
            assertThat(NavigationContext.CUSTOMER_INFO_LENGTH).isEqualTo(84);
            assertThat(NavigationContext.ACCOUNT_INFO_LENGTH).isEqualTo(12);
            assertThat(NavigationContext.CARD_INFO_LENGTH).isEqualTo(16);
            assertThat(NavigationContext.MORE_INFO_LENGTH).isEqualTo(14);
            assertThat(NavigationContext.COMMAREA_LENGTH)
                    .isEqualTo(NavigationContext.GENERAL_INFO_LENGTH
                            + NavigationContext.CUSTOMER_INFO_LENGTH
                            + NavigationContext.ACCOUNT_INFO_LENGTH
                            + NavigationContext.CARD_INFO_LENGTH
                            + NavigationContext.MORE_INFO_LENGTH)
                    .isEqualTo(160);
        }

        @Test
        @DisplayName("CDEMO-LAST-MAP and -LAST-MAPSET are X(7), not X(08) (COCOM01Y.cpy:43-44)")
        void theMapAndMapsetFieldsAreSevenCharactersWide() {
            assertThat(NavigationContext.LAST_MAP_LENGTH).isEqualTo(7);
            assertThat(NavigationContext.LAST_MAPSET_LENGTH).isEqualTo(7);
            assertThat(NavigationContext.LAST_MAP_LENGTH + NavigationContext.LAST_MAPSET_LENGTH)
                    .isEqualTo(NavigationContext.MORE_INFO_LENGTH);
            assertThat(BillPaymentRequest.MAP_NAME)
                    .as("the map name itself is the seven characters the copybook field can hold")
                    .hasSize(NavigationContext.LAST_MAP_LENGTH);
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

        @ParameterizedTest(name = "{0} is {1} characters wide on the request and on the response")
        @MethodSource(
                "com.vsergeychik.carddemo.billing.dto.BillPaymentRequestTest#mapMemberWidths")
        @DisplayName("request and response widths are identical - COBIL0AO REDEFINES COBIL0AI (CPY:79)")
        void requestAndResponseCarryTheSameTenWidths(String member, int width) throws Exception {
            String constantName = WIDTH_CONSTANT_NAMES.get(member);

            assertThat(constantName).as("no width constant is mapped for %s", member).isNotNull();
            int onRequest = BillPaymentRequest.class.getField(constantName).getInt(null);
            int onResponse = BillPaymentResponse.class.getField(constantName).getInt(null);

            assertThat(onRequest).as("%s on the request", member).isEqualTo(width);
            assertThat(onResponse)
                    .as("%s must be declared at the same width on both views of one redefined span",
                            member)
                    .isEqualTo(onRequest);
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
    @DisplayName("6. The six CDEMO-CB00-INFO members (COBIL00C:64-72)")
    class Cb00Extension {
        @ParameterizedTest(name = "{0} is declared and its width constant is {1}")
        @MethodSource(
                "com.vsergeychik.carddemo.billing.dto.BillPaymentRequestTest#cb00InfoWidths")
        @DisplayName("each of the six extension members is declared with its source width")
        void eachExtensionMemberIsDeclaredWithItsSourceWidth(String member, int width)
                throws Exception {
            Field field = BillPaymentRequest.class.getDeclaredField(member);

            assertThat(field.getName()).isEqualTo(member);
            assertThat(width).isPositive();
            if ("pageNum".equals(member)) {
                assertThat(field.getType()).isEqualTo(int.class);
                assertThat(width).isEqualTo(BillPaymentRequest.PAGE_NUM_DIGITS).isEqualTo(8);
            } else {
                assertThat(field.getType()).isEqualTo(String.class);
            }
        }

        @Test
        @DisplayName("the six widths sum to 58 - the length of 05 CDEMO-CB00-INFO")
        void theSixWidthsSumToFiftyEight() {
            int sum = CB00_INFO_WIDTHS.values().stream().mapToInt(Integer::intValue).sum();

            assertThat(CB00_INFO_WIDTHS).hasSize(6);
            assertThat(sum).isEqualTo(BillPaymentRequest.CB00_INFO_LENGTH).isEqualTo(58);
        }

        @Test
        @DisplayName("no extension member is folded into the shared communication area")
        void noExtensionMemberIsFoldedIntoTheCommunicationArea() {
            List<String> commareaComponents = new ArrayList<>();
            for (RecordComponent component : NavigationContext.class.getRecordComponents()) {
                commareaComponents.add(component.getName());
            }

            assertThat(commareaComponents)
                    .hasSize(16)
                    .doesNotContainAnyElementsOf(CB00_INFO_WIDTHS.keySet());
            assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(160);
            assertThat(instanceFieldNames()).containsAll(CB00_INFO_WIDTHS.keySet());
        }

        @Test
        @DisplayName("the paging flag defaults to 'N', the VALUE clause the source declares")
        void thePagingFlagDefaultsToNo() {
            assertThat(new BillPaymentRequest().getNextPageFlg())
                    .isEqualTo(BillPaymentRequest.NEXT_PAGE_NO);
            assertThat(new BillPaymentRequest().nextPageNo()).isTrue();
            assertThat(new BillPaymentRequest().nextPageYes()).isFalse();
        }

        @Test
        @DisplayName("an explicitly supplied paging flag replaces the default")
        void anExplicitPagingFlagReplacesTheDefault() {
            BillPaymentRequest explicit = new BillPaymentRequest();
            explicit.setNextPageFlg(BillPaymentRequest.NEXT_PAGE_YES);

            assertThat(explicit.getNextPageFlg()).isEqualTo(BillPaymentRequest.NEXT_PAGE_YES);
            assertThat(explicit.nextPageYes()).isTrue();
            assertThat(explicit.nextPageNo()).isFalse();
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
        @DisplayName("the one member the program reads is carried, and so are the five it never reads")
        void theUnreferencedMembersAreCarriedAnyway() {
            BillPaymentRequest request = new BillPaymentRequest();
            request.setTrnSelected("0000000000000007");

            assertThat(request.getTrnSelected())
                    .as("the read member: COBIL00C:116-118 moves it into ACTIDINI")
                    .isEqualTo("0000000000000007");
            assertThat(instanceFieldNames())
                    .as("the five deliberately unreferenced members must remain declared")
                    .containsAll(UNREFERENCED_CB00_MEMBERS);
            assertThat(UNREFERENCED_CB00_MEMBERS).hasSize(5);
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
    @DisplayName("7. Statelessness (G37) and immutable statics (G53)")
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
        @DisplayName("no session, servlet or thread-local type appears anywhere on the type")
        void noServerSideSessionTypeAppears() {
            List<String> offending = new ArrayList<>();

            for (Field field : BillPaymentRequest.class.getDeclaredFields()) {
                rejectSessionType("field " + field.getName(), field.getType(), offending);
            }
            for (Method method : BillPaymentRequest.class.getDeclaredMethods()) {
                rejectSessionType("return of " + method.getName(), method.getReturnType(), offending);
                for (Class<?> parameter : method.getParameterTypes()) {
                    rejectSessionType("parameter of " + method.getName(), parameter, offending);
                }
            }
            for (Annotation annotation : BillPaymentRequest.class.getAnnotations()) {
                String name = annotation.annotationType().getSimpleName();
                if (name.contains("Session") || name.contains("Scope")) {
                    offending.add("the class carries @" + name);
                }
            }

            assertThat(offending)
                    .as("no HttpSession, no jakarta.servlet or javax.servlet type, no ThreadLocal and "
                            + "no session or scope annotation may appear (gate G37)")
                    .isEmpty();
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
        @DisplayName("two instances carrying different contexts do not influence one another")
        void twoInstancesCarryingDifferentContextsDoNotInfluenceOneAnother() {
            BillPaymentRequest onEnter = new BillPaymentRequest();
            onEnter.setNavigationContext(
                    NavigationContext.empty().withUserId("USER0001").withPgmEnter());
            BillPaymentRequest onReenter = new BillPaymentRequest();
            onReenter.setNavigationContext(
                    NavigationContext.empty().withUserId("ADMIN001").withUserTypeAdmin()
                            .withPgmReenter());

            assertThat(onEnter.getPgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_ENTER);
            assertThat(onReenter.getPgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_REENTER);
            assertThat(onEnter.getNavigationContext().userId()).isEqualTo("USER0001");
            assertThat(onReenter.getNavigationContext().userId()).isEqualTo("ADMIN001");
            assertThat(onEnter.getNavigationContext().isAdmin()).isFalse();
            assertThat(onReenter.getNavigationContext().isAdmin()).isTrue();
            assertThat(onEnter.getNavigationContext())
                    .isNotEqualTo(onReenter.getNavigationContext());
        }

        private void rejectSessionType(String where, Class<?> type, List<String> offending) {
            String name = type.getName();
            if (name.startsWith("jakarta.servlet") || name.startsWith("javax.servlet")
                    || name.endsWith("HttpSession") || ThreadLocal.class.isAssignableFrom(type)) {
                offending.add(where + " is " + name);
            }
        }
    }

    @Nested
    @DisplayName("8. Value semantics - what the type declares, and the branches it generates")
    class ValueSemantics {
        @Test
        @DisplayName("the type declares neither equals nor hashCode, and declares no nested type")
        void theTypeDeclaresNeitherEqualsNorHashCode() {
            assertThatExceptionOfType(NoSuchMethodException.class)
                    .isThrownBy(() -> BillPaymentRequest.class.getDeclaredMethod("equals",
                            Object.class));
            assertThatExceptionOfType(NoSuchMethodException.class)
                    .isThrownBy(() -> BillPaymentRequest.class.getDeclaredMethod("hashCode"));

            assertThat(BillPaymentRequest.class.getDeclaredClasses())
                    .as("no nested enum or nested record is declared, so there is no nested branch "
                            + "to drive")
                    .isEmpty();
            assertThat(BillPaymentRequest.class.isRecord())
                    .as("a plain bean was chosen over a record: eighteen members and constant "
                            + "partial population make a no-argument constructor plus setters the "
                            + "workable shape")
                    .isFalse();
        }

        @Test
        @DisplayName("identity semantics follow from that decision")
        void identitySemanticsFollowFromThatDecision() throws Exception {
            BillPaymentRequest instance = canonical();
            BillPaymentRequest componentWiseCopy = canonical();

            assertThat(instance).isEqualTo(instance);
            assertThat(instance.equals(componentWiseCopy))
                    .as("with no value equality declared, a component-wise copy is a different object")
                    .isFalse();
            assertThat(instance.equals(null)).isFalse();
            assertThat(instance.equals("BillPaymentRequest")).isFalse();
            assertThat(componentWiseCopy).isNotSameAs(instance);

            BillPaymentRequest withValue = canonical();
            BillPaymentRequest withoutValue = canonical();
            withoutValue.setConfirm(null);

            assertThat(withValue.getConfirm()).isEqualTo("Y");
            assertThat(withoutValue.getConfirm()).isNull();
            assertThat(withValue.getConfirm()).isNotEqualTo(withoutValue.getConfirm());
            assertThat(withoutValue.getConfirm()).isNotEqualTo(withValue.getConfirm());
            assertThat(withValue.equals(withoutValue)).isFalse();
            assertThat(withoutValue.equals(withValue)).isFalse();
        }

        @Test
        @DisplayName("the inherited hashCode is stable, and equal references agree on it")
        void theInheritedHashCodeIsStable() throws Exception {
            BillPaymentRequest instance = canonical();

            int first = instance.hashCode();
            int second = instance.hashCode();

            assertThat(second).isEqualTo(first);
            assertThat(instance.hashCode()).isEqualTo(instance.hashCode());
        }

        @Test
        @DisplayName("toString names the type, masks the account id and shows the confirmation")
        void toStringNamesTheTypeAndMasksTheAccountIdentifier() throws Exception {
            BillPaymentRequest request = new BillPaymentRequest();
            request.setActIdIn("12345678901");
            request.setConfirm("Y");
            request.setCurBal("+0000000019.40");

            String rendered = request.toString();

            assertThat(rendered).startsWith("BillPaymentRequest[").endsWith("]");
            assertThat(rendered).contains("confirm=Y");
            assertThat(rendered).contains("curBal=+0000000019.40");
            assertThat(rendered)
                    .as("the rendering is a diagnostic, not an observable output, so it need not - and "
                            + "does not - reproduce the whole account identifier")
                    .doesNotContain("12345678901");
            assertThat(rendered).contains("8901");
        }

        @Test
        @DisplayName("toString is total - an empty request renders without throwing")
        void toStringIsTotalForAnEmptyRequest() {
            BillPaymentRequest empty = new BillPaymentRequest();

            assertThatCode(empty::toString).doesNotThrowAnyException();
            assertThat(empty.toString()).startsWith("BillPaymentRequest[").endsWith("]");
            assertThat(empty.toString()).contains("actIdIn=");
        }

        @ParameterizedTest(name = "{0} reads back exactly what was set")
        @MethodSource(
                "com.vsergeychik.carddemo.billing.dto.BillPaymentRequestTest#mapMemberWidths")
        @DisplayName("every map accessor reads back exactly what its setter stored")
        void everyMapAccessorReadsBackWhatWasSet(String member, int width) throws Exception {
            BillPaymentRequest request = new BillPaymentRequest();
            String value = repeat(width);
            set(request, member, value);

            assertThat(get(request, member)).isEqualTo(value);
        }

        @Test
        @DisplayName("every carried accessor reads back exactly what was set")
        void everyCarriedAccessorReadsBackWhatWasSet() throws Exception {
            BillPaymentRequest request = canonical();

            assertThat(request.getActIdIn()).isEqualTo("00000000011");
            assertThat(request.getCurBal()).isEqualTo("+0000000019.40");
            assertThat(request.getConfirm()).isEqualTo("Y");
            assertThat(request.getAid())
                    .as("CCARD-AID PIC X(5) of app/cpy/CVCRD01Y.cpy - a five-character token")
                    .isEqualTo("PFK03")
                    .hasSize(5);
            assertThat(request.getNavigationContext().fromTranid()).isEqualTo("CB00");
            assertThat(request.getNavigationContext().fromProgram()).isEqualTo("COBIL00C");
            assertThat(request.getNavigationContext().isUser()).isTrue();
            assertThat(request.getTrnIdFirst()).isEqualTo("0000000000000001");
            assertThat(request.getTrnIdLast()).isEqualTo("0000000000000010");
            assertThat(request.getPageNum()).isEqualTo(3);
            assertThat(request.getNextPageFlg()).isEqualTo(BillPaymentRequest.NEXT_PAGE_YES);
            assertThat(request.getTrnSelFlg()).isEqualTo("S");
            assertThat(request.getTrnSelected()).isEqualTo("0000000000000007");
        }

        @Test
        @DisplayName("a fresh instance reports the declared initial state on every accessor")
        void aFreshInstanceReportsTheDeclaredInitialState() throws Exception {
            BillPaymentRequest fresh = new BillPaymentRequest();

            for (String member : MAP_MEMBER_WIDTHS.keySet()) {
                assertThat(get(fresh, member)).as(member).isNull();
            }
            assertThat(fresh.getNavigationContext()).isNull();
            assertThat(fresh.getAid()).isNull();
            assertThat(fresh.getTrnIdFirst()).isNull();
            assertThat(fresh.getTrnIdLast()).isNull();
            assertThat(fresh.getTrnSelFlg()).isNull();
            assertThat(fresh.getTrnSelected()).isNull();
            assertThat(fresh.getPageNum()).isZero();
            assertThat(fresh.getNextPageFlg()).isEqualTo(BillPaymentRequest.NEXT_PAGE_NO);
        }

        @Test
        @DisplayName("a setter accepts null and the accessor returns it - absence is representable")
        void aSetterAcceptsNullAndTheAccessorReturnsIt() throws Exception {
            BillPaymentRequest request = canonical();

            for (String member : MAP_MEMBER_WIDTHS.keySet()) {
                set(request, member, null);
            }
            request.setNavigationContext(null);
            request.setAid(null);
            request.setTrnIdFirst(null);
            request.setTrnIdLast(null);
            request.setNextPageFlg(null);
            request.setTrnSelFlg(null);
            request.setTrnSelected(null);
            request.setPageNum(0);

            for (String member : MAP_MEMBER_WIDTHS.keySet()) {
                assertThat(get(request, member)).as(member).isNull();
            }
            assertThat(request.getNavigationContext()).isNull();
            assertThat(request.getAid()).isNull();
            assertThat(request.getNextPageFlg()).isNull();
            assertThat(request.getPageNum()).isZero();
            assertThat(request.getPgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_ENTER);
            assertThat(VALIDATOR.validate(request)).isEmpty();
        }
    }

    @Nested
    @DisplayName("the published member order is the order app/cpy-bms/COBIL00.CPY declares")
    class BmsSerialisationOrder {
        private static final List<String> MAP_PROJECTION = List.of(
                "trnname", "title01", "curdate", "pgmname", "title02", "curtime", "actidin",
                "curbal", "confirm", "errmsg");

        private static final List<String> TRANSPORT_EXTENSIONS = List.of(
                "navigationContext", "aid", "trnIdFirst", "trnIdLast", "pageNum", "nextPageFlg",
                "trnSelFlg", "trnSelected");

        private static final List<String> PUBLISHED_ORDER =
                joined(MAP_PROJECTION, TRANSPORT_EXTENSIONS);

        private static List<String> joined(List<String> first, List<String> second) {
            List<String> all = new ArrayList<>(first);
            all.addAll(second);
            return List.copyOf(all);
        }

        private static List<String> publishedMembers() {
            JsonNode body = new ObjectMapper().valueToTree(new BillPaymentRequest());
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
            JsonPropertyOrder declared = BillPaymentRequest.class.getAnnotation(JsonPropertyOrder.class);
            assertThat(declared).as("the published order must be fixed by annotation").isNotNull();
            assertThat(declared.value()).containsExactlyElementsOf(PUBLISHED_ORDER);
        }
    }
}
