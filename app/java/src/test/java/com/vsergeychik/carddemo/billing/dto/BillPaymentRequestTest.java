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
 * Unit tests for {@link BillPaymentRequest}, the inbound payload of the CardDemo bill-payment
 * screen and a field-for-field projection of the {@code xxxI} items of {@code 01 COBIL0AI}.
 *
 * <h2>What is under test, and where its contract comes from</h2>
 *
 * <table border="1">
 *   <caption>The migrated surface and its authoritative sources</caption>
 *   <tr><th>Concern</th><th>Authority</th></tr>
 *   <tr><td>REST resource</td><td>{@code POST /api/billpay}</td></tr>
 *   <tr><td>CICS binding</td>
 *       <td>{@code app/csd/CARDDEMO.CSD:337-338} - {@code DEFINE TRANSACTION(CB00) GROUP(CARDDEMO)}
 *           then {@code PROGRAM(COBIL00C) TWASIZE(0) PROFILE(DFHCICST) STATUS(ENABLED)}</td></tr>
 *   <tr><td>Payload members and widths</td>
 *       <td>{@code app/cpy-bms/COBIL00.CPY:17-78} - the ten {@code xxxI} items of
 *           {@code 01 COBIL0AI}</td></tr>
 *   <tr><td>Which fields a terminal can type into</td>
 *       <td>{@code app/bms/COBIL00.bms} - 24 {@code DFHMDF} statements, exactly ten
 *           name-labelled, and {@code UNPROT} on exactly two of them: {@code ACTIDIN} at line 85
 *           and {@code CONFIRM} at line 115</td></tr>
 *   <tr><td>Behaviour the payload must keep reachable</td>
 *       <td>{@code app/cbl/COBIL00C.cbl} - lines 39, 56, 64-72, 107, 116-118, 159, 182-184,
 *           193-194 and 293</td></tr>
 *   <tr><td>Carried conversation state</td>
 *       <td>{@code app/cpy/COCOM01Y.cpy} - {@code 01 CARDDEMO-COMMAREA}, 160 bytes, modelled once
 *           by {@link NavigationContext}</td></tr>
 * </table>
 *
 * <h2>Scope: this class owns the DTO as a value object, and nothing else</h2>
 *
 * The member set, the declared widths, the validation-constraint metadata, the JSON projection and
 * the generated-method behaviour are tested here. Deliberately <strong>not</strong> tested here,
 * because they belong to other suites and duplicating them would be scope creep (practice B4):
 *
 * <ul>
 *   <li>the arithmetic and the message vocabulary - {@code COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL -
 *       TRAN-AMT} at {@code COBIL00C:234}, the write-compute-rewrite ordering and every
 *       {@code WS-RESP-CD} arm - which belong to the service suite;</li>
 *   <li>the HTTP and dispatch surface - the {@code EIBCALEN = 0} cold start, the ENTER path and the
 *       {@code EVALUATE EIBAID} arms driven through {@code MockMvc} - which belong to the
 *       controller suite;</li>
 *   <li>the twenty declarative parity cases for {@code COBIL00C};</li>
 *   <li>the {@code X(80)} to {@code X(78)} right-truncating move of {@code COBIL00C:293}, which is
 *       an outbound concern and belongs to the response suite.</li>
 * </ul>
 *
 * <p>No Spring infrastructure is used: no application context, no {@code MockMvc}, no
 * {@code JobLauncher}, no test profile and no classpath fixture. The {@link Validator} and the
 * {@link ObjectMapper} are constructed locally so that what is proven here is the type's own
 * declared contract rather than any container's configuration of it.
 *
 * <h2>Validation gates discharged</h2>
 *
 * <ul>
 *   <li><strong>G9</strong> - every payload member traces to a {@code DFHMDF} field definition, and
 *       no symbolic-map metadata item ({@code xxxL}, {@code xxxF}, {@code xxxA}, {@code xxxC},
 *       {@code xxxP}, {@code xxxH}, {@code xxxV}) is published on the wire.</li>
 *   <li><strong>G22</strong> - {@code curBal} carries the edited text of
 *       {@code WS-CURR-BAL PIC +9999999999.99} as a {@link String}; no {@code double},
 *       {@code float} or {@code BigDecimal} appears in its path.</li>
 *   <li><strong>G37</strong> - no server-side session state: the communication area, the attention
 *       identifier and the enter-versus-re-enter context all travel in the payload.</li>
 *   <li><strong>G49</strong> - every branch this type generates is driven, so the
 *       {@code com.vsergeychik.carddemo.billing.dto} package meets the per-package JaCoCo
 *       {@code BRANCH} minimum on its own rather than behind another package's coverage.</li>
 *   <li><strong>G52</strong> - no wildcard import; every type above is imported explicitly so each
 *       copybook-to-type correspondence stays auditable.</li>
 *   <li><strong>G53</strong> - no static mutable state, asserted reflectively on the type under
 *       test and honoured by this suite itself.</li>
 *   <li><strong>G54</strong> - deterministic and non-interactive: no watch mode, no sleep, no
 *       wall-clock or locale dependence and no unseeded randomness.</li>
 * </ul>
 *
 * <h2>Governing rules</h2>
 *
 * {@code review_rules} reports, as its entire content, that <strong>no user rules were provided</strong>
 * for this project. No rule therefore governs this file. Their absence is not a licence to lower the
 * bar, so the binding substitutes are the Agent Action Plan's own enterprise-practice items: B4 (no
 * scope creep), B5 (dead and unreferenced source state is preserved, not tidied away), B6 (no
 * security concept is introduced), B7 (deterministic non-interactive build), B8 (explicit over
 * implicit - explicit imports, explicit locale), B9 (no static mutable state), B10 (tests are a
 * first-class deliverable authored with the code) and B11 (the width table is hand-written and
 * diffable against the copybook rather than produced by a parser).
 *
 * <h2>Two places where the type under test is the authority, not the brief</h2>
 *
 * <ol>
 *   <li>{@code @Size} is declared on <strong>all ten</strong> map members, not only on the two
 *       {@code UNPROT} ones. Only {@code ACTIDIN} and {@code CONFIRM} can be typed into at a
 *       terminal, but a pseudo-conversational client echoes the whole payload back, so all ten
 *       arrive from the network and all ten are bounded. This suite asserts what the type declares.</li>
 *   <li>{@link BillPaymentRequest#toString()} <strong>masks</strong> the account identifier through
 *       the module's diagnostic policy, although the type's own class comment states the opposite.
 *       The implementation is the authority; the divergence is recorded at the assertion rather than
 *       corrected in a file this suite does not own.</li>
 * </ol>
 */
@DisplayName("BillPaymentRequest - the COBIL00 symbolic-map input projection (POST /api/billpay, CB00)")
class BillPaymentRequestTest {

    // =================================================================================================
    // The width table, hand-written and line-cited (practice B11).
    //
    // Every width below is the PICTURE clause of one xxxI item of 01 COBIL0AI in
    // app/cpy-bms/COBIL00.CPY. The xxxL length item, the xxxF flag byte and the xxxA attribute view
    // that precede each of them are metadata and are deliberately absent from this table and from the
    // type under test. No copybook parser is used anywhere in this build, so this table is the
    // reviewable artefact: it is diffed against the copybook by eye, which is why each row carries its
    // source line.
    // =================================================================================================

    /**
     * The ten map members paired with their declared width, in the order {@code 01 COBIL0AI} declares
     * them. Iteration order is preserved deliberately: a parameterised failure then names fields in
     * copybook order rather than in a hash order that could vary between runs.
     */
    private static final Map<String, Integer> MAP_MEMBER_WIDTHS = buildMapMemberWidths();

    /**
     * The name of the width constant each map member is declared against, written out by hand so the
     * member-to-constant correspondence is read rather than computed from a naming convention. Both
     * {@link BillPaymentRequest} and {@link BillPaymentResponse} declare these same ten names, because
     * {@code 01 COBIL0AO REDEFINES COBIL0AI} ({@code app/cpy-bms/COBIL00.CPY:79}) makes the two views
     * one span.
     */
    private static final Map<String, String> WIDTH_CONSTANT_NAMES = buildWidthConstantNames();

    /**
     * The symbolic-map item stem of each of the ten fields, exactly as {@code 01 COBIL0AI} spells it.
     * A metadata item is one of these stems followed by one suffix from {@link #METADATA_SUFFIXES}.
     */
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

    /**
     * The seven metadata suffixes a BMS symbolic map generates around each data item. {@code L},
     * {@code F} and {@code A} belong to the input view {@code 01 COBIL0AI}
     * ({@code app/cpy-bms/COBIL00.CPY:19-23} and its nine repetitions); {@code C}, {@code P},
     * {@code H} and {@code V} belong to the output view {@code 01 COBIL0AO REDEFINES COBIL0AI} at
     * line 79. All seven are presentation metadata and none may reach the wire (gate G9).
     */
    private static final List<String> METADATA_SUFFIXES =
            List.of("L", "F", "A", "C", "P", "H", "V");

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

    /**
     * The ten screen fields of {@code 01 COBIL0AI}, by Java member name, so {@link #wireNameOf(String)}
     * can tell them from the eight members that trace to no {@code DFHMDF} field.
     */
    private static final java.util.Set<String> SCREEN_FIELD_MEMBERS = java.util.Set.of(
            "trnName", "title01", "curDate", "pgmName", "title02", "curTime", "actIdIn", "curBal",
            "confirm", "errMsg");

    /**
     * A member's name <strong>on the wire</strong>: a screen field answers to its {@code xxxI} item in
     * lower case, which {@code @JsonProperty} pins per AAP 0.6.3; the attention identifier and the
     * {@code CDEMO-CB00-INFO} carriers trace to no {@code DFHMDF} field and keep their own names.
     *
     * @param member the Java member name
     * @return the JSON property name it is published under
     */
    private static String wireNameOf(String member) {
        return SCREEN_FIELD_MEMBERS.contains(member)
                ? member.toLowerCase(java.util.Locale.ROOT) : member;
    }

    /**
     * {@link #wireNameOf(String)} over a collection, preserving order.
     *
     * @param members the Java member names
     * @return their JSON property names
     */
    private static List<String> wireNamesOf(java.util.Collection<String> members) {
        return members.stream().map(BillPaymentRequestTest::wireNameOf).toList();
    }

    /**
     * The six members of {@code 05 CDEMO-CB00-INFO}, declared at {@code app/cbl/COBIL00C.cbl:64-72}
     * inside {@code 01 CARDDEMO-COMMAREA} - in the program, not in the copybook. Their widths sum to
     * 58, which is why this program's communication area is 58 bytes longer than the shared 160-byte
     * one that {@link NavigationContext} models.
     */
    private static final Map<String, Integer> CB00_INFO_WIDTHS = buildCb00InfoWidths();

    /**
     * The five members of {@code CDEMO-CB00-INFO} that {@code COBIL00C} never reads. Only
     * {@code CDEMO-CB00-TRN-SELECTED} is read, at {@code app/cbl/COBIL00C.cbl:116-118}. The other
     * five - and the {@code 88 NEXT-PAGE-YES} / {@code 88 NEXT-PAGE-NO} conditions over one of them -
     * have zero references in the program. They are carried anyway: preserving unreferenced source
     * state is required (practice B5), so this suite asserts they are <strong>present</strong> and
     * never that they are absent.
     */
    private static final List<String> UNREFERENCED_CB00_MEMBERS =
            List.of("trnIdFirst", "trnIdLast", "pageNum", "nextPageFlg", "trnSelFlg");

    /**
     * The validator factory, built locally rather than obtained from an application context - what is
     * proven here is the type's own declared constraint metadata, not a container's configuration of
     * it. The reference is {@code static final} and is written exactly once at class initialisation:
     * there is deliberately no static field anywhere in this suite that a test could reassign or
     * accumulate into, because this suite asserts that same discipline of the type under test (gate
     * G53, practice B9). {@link #closeValidator()} releases it once the class is finished.
     */
    private static final ValidatorFactory VALIDATOR_FACTORY = Validation.buildDefaultValidatorFactory();

    /** The single validator, derived from {@link #VALIDATOR_FACTORY} and never reassigned. */
    private static final Validator VALIDATOR = VALIDATOR_FACTORY.getValidator();

    /**
     * A fresh mapper per test method, with Jackson's own defaults untouched - in particular
     * {@code FAIL_ON_UNKNOWN_PROPERTIES} is left enabled, because the point of the wire tests is what
     * the type declares rather than what a customised mapper could be persuaded to accept.
     */
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void confirmValidatorIsAvailable() {
        // A guard rather than an initialisation step: if Bean Validation were absent from the test
        // classpath the width assertions below would all pass vacuously, reporting an empty violation
        // set for every input. Failing here instead names the real cause.
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
        widths.put("trnName", BillPaymentRequest.TRN_NAME_LENGTH);   // TRNNAMEI PIC X(4)   CPY L24
        widths.put("title01", BillPaymentRequest.TITLE01_LENGTH);    // TITLE01I PIC X(40)  CPY L30
        widths.put("curDate", BillPaymentRequest.CUR_DATE_LENGTH);   // CURDATEI PIC X(8)   CPY L36
        widths.put("pgmName", BillPaymentRequest.PGM_NAME_LENGTH);   // PGMNAMEI PIC X(8)   CPY L42
        widths.put("title02", BillPaymentRequest.TITLE02_LENGTH);    // TITLE02I PIC X(40)  CPY L48
        widths.put("curTime", BillPaymentRequest.CUR_TIME_LENGTH);   // CURTIMEI PIC X(8)   CPY L54
        widths.put("actIdIn", BillPaymentRequest.ACT_ID_IN_LENGTH);  // ACTIDINI PIC X(11)  CPY L60
        widths.put("curBal", BillPaymentRequest.CUR_BAL_LENGTH);     // CURBALI  PIC X(14)  CPY L66
        widths.put("confirm", BillPaymentRequest.CONFIRM_LENGTH);    // CONFIRMI PIC X(1)   CPY L72
        widths.put("errMsg", BillPaymentRequest.ERR_MSG_LENGTH);     // ERRMSGI  PIC X(78)  CPY L78
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
        // CDEMO-CB00-TRNID-FIRST   PIC X(16)   COBIL00C.cbl L65
        widths.put("trnIdFirst", BillPaymentRequest.TRN_ID_FIRST_LENGTH);
        // CDEMO-CB00-TRNID-LAST    PIC X(16)   COBIL00C.cbl L66
        widths.put("trnIdLast", BillPaymentRequest.TRN_ID_LAST_LENGTH);
        // CDEMO-CB00-PAGE-NUM      PIC 9(08)   COBIL00C.cbl L67 - the one non-String member
        widths.put("pageNum", BillPaymentRequest.PAGE_NUM_DIGITS);
        // CDEMO-CB00-NEXT-PAGE-FLG PIC X(01) VALUE 'N'   COBIL00C.cbl L68
        widths.put("nextPageFlg", BillPaymentRequest.NEXT_PAGE_FLG_LENGTH);
        // CDEMO-CB00-TRN-SEL-FLG   PIC X(01)   COBIL00C.cbl L71
        widths.put("trnSelFlg", BillPaymentRequest.TRN_SEL_FLG_LENGTH);
        // CDEMO-CB00-TRN-SELECTED  PIC X(16)   COBIL00C.cbl L72
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

    /**
     * Three renderings of {@code WS-CURR-BAL PIC +9999999999.99} ({@code app/cbl/COBIL00C.cbl:56}):
     * a positive balance, a negative one and zero. The picture is <em>edited</em>, so the sign
     * position is always occupied and the integer part is always zero-filled to ten digits - which is
     * exactly what a numeric Java type would destroy.
     */
    private static Stream<Arguments> editedBalances() {
        return Stream.of(Arguments.of("+0000000019.40"),
                Arguments.of("-0000000019.40"),
                Arguments.of("+0000000000.00"));
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

    /**
     * A fully populated request: the ten map members at their declared widths plus every carried item
     * of conversation state. This is the canonical instance the wire and value-semantics groups work
     * from, so a single helper keeps them describing the same object.
     */
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

    /** The published JSON property names of an instance, as a set so comparisons are order-free. */
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
            // ACTIDIN at app/bms/COBIL00.bms:85 (FSET,IC,NORM,UNPROT) and CONFIRM at :115
            // (FSET,NORM,UNPROT) are the only two of the twenty-four DFHMDF fields a user can type
            // into; the other eight named fields are ASKIP echo-only. All ten are nevertheless bounded,
            // because a pseudo-conversational client echoes the whole payload back and every member
            // therefore arrives from the network.
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
            // COBIL00C:159 tests ACTIDINI for SPACES OR LOW-VALUES itself and answers with
            // 'Acct ID can NOT be empty...', and COBIL00C:182-184 treats WHEN SPACES / WHEN LOW-VALUES
            // on CONFIRMI as the VALID read-the-account path. A presence constraint here would turn
            // both into rejected requests and delete reachable behaviour.
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

            // Compared order-independently: the JVM does not guarantee the order of
            // Class#getDeclaredFields, so asserting a sequence here would be a latent flake.
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
    @DisplayName("3. The wire format - eighteen members, no metadata, and strict binding")
    class WireFormat {

        @Test
        @DisplayName("the serialised body carries exactly the eighteen members and nothing else")
        void theBodyCarriesExactlyTheEighteenMembers() throws Exception {
            Set<String> published = publishedNames(canonical());

            // Compared as sets, so a failure lists both what is missing and what is unexpected. The ten
            // screen fields are published under their xxxI items in lower case (AAP 0.6.3); the eight
            // carriers under their own names.
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

            // Seventy names are checked case-insensitively, so neither ACTIDINL nor a camelCased
            // actIdInL could slip through. xxxL is the inbound length CICS reports, xxxF the flag byte
            // and xxxA the attribute view over it (COBIL00.CPY:19-23); xxxC, xxxP, xxxH and xxxV are the
            // output-view attribute items of 01 COBIL0AO REDEFINES COBIL0AI at line 79. All are
            // validation and highlight metadata, owned by the controller, and none is payload.
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
            // The type declares no @JsonIgnoreProperties, so Jackson's default
            // FAIL_ON_UNKNOWN_PROPERTIES applies: a name that is not part of the screen contract is a
            // protocol error rather than something to swallow. This asserts the declared behaviour; it
            // does not ask the type to change.
            assertThatExceptionOfType(UnrecognizedPropertyException.class)
                    .isThrownBy(() -> mapper.readValue(
                            "{\"actidin\":\"00000000011\",\"notAScreenField\":\"x\"}",
                            BillPaymentRequest.class))
                    .withMessageContaining("notAScreenField");

            // pgmContext is different in kind: @JsonIgnore on the read-through accessor registers the
            // name as known-and-ignored, so it binds without error and changes nothing.
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
            // MOVE LOW-VALUES TO COBIL0AO at COBIL00C:114 and the MOVE SPACES of
            // INITIALIZE-ALL-FIELDS leave a field in two different observable states, and
            // COBIL00C:182-183 tests WHEN SPACES and WHEN LOW-VALUES as separate arms. Merging them
            // here would make one of those arms unreachable.
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
            // COBIL00C:56 declares 05 WS-CURR-BAL PIC +9999999999.99 - a 14-character EDITED picture,
            // sign plus ten integer digits plus '.' plus two decimals - and COBIL00C:193-194 moves
            // ACCT-CURR-BAL into it and then moves the already-formatted text into CURBALI. What the
            // screen carries is therefore text, not a number.
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
            // ERRMSGI PIC X(78) at app/cpy-bms/COBIL00.CPY:78. COBIL00C:39 declares WS-MESSAGE as
            // PIC X(80) and COBIL00C:293 moves it into ERRMSGO, an X(80) to X(78) right-truncating
            // move - but that truncation is an OUTBOUND concern and belongs to the response suite.
            // Inbound, the only obligation is that the declared width is 78 and that a value at that
            // width survives binding untouched.
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
            // 02 FILLER PIC X(12) at COBIL00.CPY:18 is the TIOAPFX=YES prefix; each field then carries
            // a 7-byte prologue (xxxL 2 + xxxF 1 + FILLER 4) before its data item.
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
            // 34 general + 84 customer + 12 account + 16 card + 14 more = 160. NavigationContext is
            // shared by all seventeen online programs, so this total is the one number this payload
            // must never move.
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
            // The instinct is eight, to match the eight-character program names of lines 22 and 24.
            // The copybook says seven, because a BMS symbolic-map group item is a seven-character map
            // name plus a one-character direction suffix - COBIL0AI for the input view and COBIL0AO
            // for the output view - so the eighth character belongs to the suffix. And 160 only totals
            // correctly at seven: widening both to eight would make CDEMO-MORE-INFO 16 and the record
            // 162.
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
            // app/cpy-bms/COBIL00.CPY:79 declares 01 COBIL0AO REDEFINES COBIL0AI, so the output view
            // occupies the identical byte span and merely renames the per-field prologue and the data
            // item. The cleanest evidence is COBIL00C:194 itself: it writes the formatted balance into
            // CURBALI - the INPUT-view item - which is only legal because both views start at the same
            // offset with the same X(14) width.
            //
            // This asserts the shared geometry from the request's side. Nothing about the response's own
            // behaviour is asserted here: the X(80) to X(78) truncating move of COBIL00C:293 and the
            // outbound field population belong to the response suite (practice B4).
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
            // app/csd/CARDDEMO.CSD:337-338 binds TRANSACTION(CB00) to PROGRAM(COBIL00C); the mapset
            // and map names come from app/bms/COBIL00.bms.
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
                // CDEMO-CB00-PAGE-NUM PIC 9(08) is the one scale-free numeric of the six, so it is an
                // int rather than a String; the width is a digit count, not a character span.
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

            // The 58 bytes are declared in the PROGRAM, at COBIL00C:64-72, not in the copybook. They
            // therefore belong to this payload and not to NavigationContext, which must stay exactly
            // 160 bytes because all seventeen online programs share it. That is the whole point of the
            // split.
            assertThat(commareaComponents)
                    .hasSize(16)
                    .doesNotContainAnyElementsOf(CB00_INFO_WIDTHS.keySet());
            assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(160);
            assertThat(instanceFieldNames()).containsAll(CB00_INFO_WIDTHS.keySet());
        }

        @Test
        @DisplayName("the paging flag defaults to 'N', the VALUE clause the source declares")
        void thePagingFlagDefaultsToNo() {
            // The default-taken branch: nothing was supplied, so the field initialiser stands.
            assertThat(new BillPaymentRequest().getNextPageFlg())
                    .isEqualTo(BillPaymentRequest.NEXT_PAGE_NO);
            assertThat(new BillPaymentRequest().nextPageNo()).isTrue();
            assertThat(new BillPaymentRequest().nextPageYes()).isFalse();
        }

        @Test
        @DisplayName("an explicitly supplied paging flag replaces the default")
        void anExplicitPagingFlagReplacesTheDefault() {
            // The value-supplied branch, driven separately from the default-taken one above.
            BillPaymentRequest explicit = new BillPaymentRequest();
            explicit.setNextPageFlg(BillPaymentRequest.NEXT_PAGE_YES);

            assertThat(explicit.getNextPageFlg()).isEqualTo(BillPaymentRequest.NEXT_PAGE_YES);
            assertThat(explicit.nextPageYes()).isTrue();
            assertThat(explicit.nextPageNo()).isFalse();
        }

        @Test
        @DisplayName("the two condition names are independent tests, not each other's negation")
        void theTwoConditionNamesAreIndependent() {
            // 88 NEXT-PAGE-YES VALUE 'Y' and 88 NEXT-PAGE-NO VALUE 'N' are declared over a PIC X(01)
            // that may hold any character, so the pair is not exhaustive: for a space, and for an
            // absent value, both conditions are false.
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
            // Practice B5 - preserve, do not tidy. CDEMO-CB00-TRN-SELECTED is the ONLY member of the
            // six that COBIL00C ever reads: app/cbl/COBIL00C.cbl:116-118,
            // IF CDEMO-CB00-TRN-SELECTED NOT = SPACES AND LOW-VALUES then MOVE it TO ACTIDINI. The
            // other five, and the two 88-levels over one of them, have zero references in the program.
            // They are carried because the source declares them. This asserts they are PRESENT; it must
            // never assert them away, and none of them may be removed.
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
            // These are communication-area items, not map fields: no DFHMDF declares them, so no
            // symbolic-map PICTURE clause bounds them at the boundary.
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
            // COBOL WORKING-STORAGE never becomes a static Java field: that would break request
            // isolation and test determinism alike.
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

            // CICS is pseudo-conversational: COBIL00C runs to completion for every keystroke and
            // survives only through the COMMAREA it hands back at lines 146-149. The migration keeps
            // that shape, so all conversation state travels in this payload and none of it becomes
            // server-side state (gate G37).
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
            // This is a declared decision, not an omission. Any correct Java equals needs a type test,
            // and a type test is a branch that this package's per-package JaCoCo BRANCH gate would then
            // have to prove exercised; the parity differ compares field by field rather than by
            // whole-object equality, so neither method is needed. Asserting the decision here keeps it
            // deliberate: whoever adds equals must also come here and say why.
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

            // The null-versus-value case, in both directions. Under a value equality this would be the
            // distinct component branch worth driving; here it is the observable distinction between two
            // carriers that differ in exactly one member, which is what the field-by-field parity differ
            // compares rather than whole-object equality.
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
            // Deliberately not asserted: that two distinct instances hash differently. Under identity
            // hashing that is overwhelmingly likely but not guaranteed, and asserting it would make this
            // suite non-deterministic - which practice B7 and gate G54 forbid.
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
            // The implementation routes actIdIn through the module's diagnostic masking policy, so the
            // rendering keeps only the trailing digits. The type's own class comment states the
            // opposite; the implementation is the authority, and the divergence is recorded here rather
            // than corrected in a file this suite does not own (practice B4). Only the trailing-digit
            // shape is asserted, so the policy stays free to change its mask character without
            // breaking this test - what must not change is that the full identifier does not appear.
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
            // The paging flag is the ONLY member the type initialises, because
            // CDEMO-CB00-NEXT-PAGE-FLG at app/cbl/COBIL00C.cbl:68 is the only one of the eighteen with
            // a VALUE clause. Nothing else is defaulted, trimmed, padded, upper-cased or coerced from
            // empty to null: the constructor performs no other normalisation, so there is no further
            // constructor arm to drive.
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

    /**
     * The order in which this type publishes its members.
     *
     * <p>Every member here traces to a {@code DFHMDF} field of {@code app/bms/COBIL00.bms} or to a
     * transport extension, and the projection is only faithful if it is published in the order the
     * screen declares - a client reading the object top to bottom must read the screen top to bottom.
     * Jackson does not give that for free: with no explicit order it derives one from reflection over
     * the accessors and moves every member renamed with {@code @JsonProperty} behind the ones that
     * were not renamed, which put this type's map fields out of screen order.
     */
    @Nested
    @DisplayName("the published member order is the order app/cpy-bms/COBIL00.CPY declares")
    class BmsSerialisationOrder {

        /**
         * The 10 {@code xxxI} items of {@code app/cpy-bms/COBIL00.CPY}, in that file's own
         * declaration order.
         */
        private static final List<String> MAP_PROJECTION = List.of(
                "trnname", "title01", "curdate", "pgmname", "title02", "curtime", "actidin",
                "curbal", "confirm", "errmsg");

        /**
         * The 8 members that are not {@code DFHMDF} fields: the CARDDEMO-COMMAREA of app/cpy/COCOM01Y.cpy, the EIBAID, and the six CDEMO-CB00 paging items COBIL00C carries between turns. They follow
         * the map and never interleave with it, so the screen reads as one contiguous run.
         */
        private static final List<String> TRANSPORT_EXTENSIONS = List.of(
                "navigationContext", "aid", "trnIdFirst", "trnIdLast", "pageNum", "nextPageFlg",
                "trnSelFlg", "trnSelected");

        /** The map projection followed by the transport extensions - the whole published object. */
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
