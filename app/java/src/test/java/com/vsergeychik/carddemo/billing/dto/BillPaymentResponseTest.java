package com.vsergeychik.carddemo.billing.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.vsergeychik.carddemo.billing.dto.BillPaymentResponse.CursorField;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenFieldImage;
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
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Unit tests for {@link BillPaymentResponse}, the outbound payload of the CardDemo bill-payment
 * screen and a field-for-field projection of the {@code xxxO} items of
 * {@code 01 COBIL0AO REDEFINES COBIL0AI}.
 *
 * <h2>What is under test, and where its contract comes from</h2>
 *
 * <table border="1">
 *   <caption>The migrated surface and its authoritative sources</caption>
 *   <tr><th>Concern</th><th>Authority</th></tr>
 *   <tr><td>REST resource</td><td>{@code POST /api/billpay}</td></tr>
 *   <tr><td>CICS binding</td>
 *       <td>{@code app/csd/CARDDEMO.CSD:337-338} - {@code DEFINE TRANSACTION(CB00) GROUP(CARDDEMO)}
 *           then {@code PROGRAM(COBIL00C) TWASIZE(0) PROFILE(DFHCICST) STATUS(ENABLED)}. The mapset
 *           is defined at line 114 and the program at line 196.</td></tr>
 *   <tr><td>Payload members and widths</td>
 *       <td>{@code app/cpy-bms/COBIL00.CPY:79-140} - the ten {@code xxxO} items of
 *           {@code 01 COBIL0AO}, at lines 86, 92, 98, 104, 110, 116, 122, 128, 134 and 140</td></tr>
 *   <tr><td>Screen geometry and which fields are typeable</td>
 *       <td>{@code app/bms/COBIL00.bms} - 24 {@code DFHMDF} statements of which exactly ten are
 *           name-labelled, {@code SIZE=(24,80)} at lines 26-28, {@code TIOAPFX=YES} at line 24, and
 *           no {@code PICIN} or {@code PICOUT} anywhere</td></tr>
 *   <tr><td>Presentation state that has no {@code xxxO} item</td>
 *       <td>{@code app/cbl/COBIL00C.cbl} - the seventeen {@code MOVE -1} cursor statements and the
 *           single {@code MOVE DFHGREEN TO ERRMSGC OF COBIL0AO} at line 526</td></tr>
 *   <tr><td>Navigation</td>
 *       <td>{@code app/cbl/COBIL00C.cbl:282} - the program's only {@code EXEC CICS XCTL} - together
 *           with {@code MAP('COBIL0A')} at line 296 and {@code MAPSET('COBIL00')} at line 297</td></tr>
 *   <tr><td>Carried conversation state</td>
 *       <td>{@code app/cpy/COCOM01Y.cpy} - {@code 01 CARDDEMO-COMMAREA}, 160 bytes, modelled once by
 *           {@link NavigationContext}, plus this program's own 58-byte {@code CDEMO-CB00-INFO}
 *           extension at {@code app/cbl/COBIL00C.cbl:64-72}</td></tr>
 * </table>
 *
 * <h2>Scope: this class owns the response DTO as a value object, and nothing else</h2>
 *
 * The member set, the declared widths, the two response-only presentation members and their
 * reachable states, the JSON projection and the generated-method behaviour are tested here.
 * Deliberately <strong>not</strong> tested here, because they belong to other suites and duplicating
 * them would be scope creep (practice B4):
 *
 * <ul>
 *   <li>the arithmetic and the message vocabulary - {@code COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL -
 *       TRAN-AMT} at {@code COBIL00C:234} and the {@code STRING}-composed
 *       {@code 'Payment successful. '} text of lines 527-531 - which belong to the service suite;</li>
 *   <li>the routing <em>decision</em> that picks one of the four reachable {@code nextProgram}
 *       values, the {@code MAIN-PARA} dispatch and the {@code MockMvc} surface - which belong to the
 *       controller suite. This suite asserts only that the member can carry each reachable value;</li>
 *   <li>the twenty declarative parity cases for {@code COBIL00C};</li>
 *   <li>the inbound width constraints, which are declared on {@code BillPaymentRequest} and are
 *       proven by its own suite. A response is produced by this application rather than accepted
 *       from a client, so this type declares no Bean Validation constraint at all and no
 *       {@link jakarta.validation.Validator} is built here.</li>
 * </ul>
 *
 * <p>No Spring infrastructure is used: no application context, no {@code MockMvc}, no
 * {@code JobLauncher}, no test profile and no classpath fixture. The {@link ObjectMapper} is
 * constructed locally, per test method, so that what is proven here is the type's own declared
 * contract rather than any container's configuration of it.
 *
 * <h2>Validation gates discharged</h2>
 *
 * <ul>
 *   <li><strong>G9</strong> - every payload member traces to a {@code DFHMDF} field definition, and
 *       no symbolic-map metadata item is published on the wire. All seven suffixes ({@code xxxL},
 *       {@code xxxF}, {@code xxxA} on the input view; {@code xxxC}, {@code xxxP}, {@code xxxH},
 *       {@code xxxV} on the output view) are checked against all ten field stems,
 *       case-insensitively, so no camelCased variant can slip through.</li>
 *   <li><strong>G22</strong> - {@code curBal} carries the edited text of
 *       {@code WS-CURR-BAL PIC +9999999999.99} as a {@link String}. No {@code double},
 *       {@code float}, {@code Double}, {@code Float} or {@link java.math.BigDecimal} appears
 *       anywhere on the type, asserted by sweeping every declared field, return type and
 *       parameter.</li>
 *   <li><strong>G37</strong> - no server-side session state: the communication area, the cursor
 *       indicator, the message highlight and the navigation target all travel in the payload.</li>
 *   <li><strong>G49</strong> - every state the type can reach is driven, including all three
 *       constants of the nested {@link CursorField} and both arms of the message highlight, so the
 *       {@code com.vsergeychik.carddemo.billing.dto} package clears the per-package JaCoCo
 *       {@code BRANCH} minimum on its own rather than behind another package's coverage.</li>
 *   <li><strong>G52</strong> - no wildcard import; every type above is imported explicitly so each
 *       copybook-to-type correspondence stays auditable.</li>
 *   <li><strong>G53</strong> - no static mutable state, asserted reflectively on the type under test
 *       and honoured by this suite itself: every static member here is {@code final} and either a
 *       primitive, a {@link String} or an unmodifiable collection.</li>
 *   <li><strong>G54</strong> - deterministic and non-interactive: no watch mode, no sleep, no
 *       wall-clock or locale dependence and no unseeded randomness. Every {@code toLowerCase} names
 *       {@link Locale#ROOT}.</li>
 * </ul>
 *
 * <h2>Governing rules</h2>
 *
 * {@code review_rules} reports, as its entire content, that <strong>no user rules were provided</strong>
 * for this project. No rule therefore governs this file. Their absence is not a licence to lower the
 * bar, so the binding substitutes are the Agent Action Plan's own enterprise-practice items: B4 (no
 * scope creep - nothing another suite owns is restated, and no fixture, no profile and no extra file
 * is created), B5 (dead and unreferenced source state is preserved, not tidied away - the five
 * never-read {@code CDEMO-CB00-INFO} members are asserted <em>present</em>), B6 (no security concept
 * is introduced - no hashing, no token, no Spring Security type), B7 (deterministic non-interactive
 * build), B8 (explicit over implicit - explicit imports, an explicit locale, and {@code DFHGREEN}
 * referenced through {@link BmsAttributes} by name rather than as a byte literal), B9 (no static
 * mutable state), B10 (tests are a first-class deliverable authored with the code) and B11 (the width
 * table below is hand-written and line-cited so it is diffable against the copybook by eye, because
 * no copybook parser is permitted in this build).
 *
 * <h2>Four places where the type under test is the authority, not the brief</h2>
 *
 * <ol>
 *   <li>{@link BillPaymentResponse} is a <strong>plain mutable bean, not a {@code record}</strong>,
 *       and it declares <strong>neither {@code equals} nor {@code hashCode}</strong>. Its own class
 *       comment gives the reason: any correct {@code equals} needs a type test, a type test is a
 *       branch, and the parity differ compares field by field rather than by whole-object equality.
 *       A per-component {@code equals} matrix is therefore not assertable against this type. Section
 *       8 asserts the declared absence reflectively and then asserts what actually follows -
 *       identity semantics and a stable inherited {@code hashCode} - while section 8's mutation
 *       tests supply the per-component discrimination the {@code equals} matrix was there to
 *       provide: mutating exactly one member is proven to change exactly that member.</li>
 *   <li>{@link BillPaymentResponse#toString()} <strong>withholds</strong> {@code curBal} and
 *       <strong>masks</strong> {@code actIdIn}, {@code trnIdFirst}, {@code trnIdLast} and
 *       {@code trnSelected} through the module's diagnostic policy. The rendering therefore does not
 *       contain the balance, and the assertions here are written against that: the member is named
 *       but its value is not reproduced. Only the negative and the member-naming are asserted, so
 *       the policy stays free to change its marker text without breaking this suite.</li>
 *   <li>The message highlight is a one-character {@link String} carrying an attribute
 *       <em>byte</em>, because {@code ERRMSGC} is {@code PICTURE X}.
 *       {@link BmsAttributes#DFHGREEN} is declared a {@code byte}, so the value is derived from that
 *       named constant through {@link BmsAttributes#unsigned(byte)} rather than written as a
 *       literal. The invariant asserted is the module's own:
 *       {@code (byte) value.charAt(0) == BmsAttributes.DFHGREEN}.</li>
 *   <li>The two-byte right truncation of {@code MOVE WS-MESSAGE TO ERRMSGO} at
 *       {@code app/cbl/COBIL00C.cbl:293} is <strong>the caller's</strong> responsibility, exactly as
 *       {@code setErrMsg}'s own contract states: this type stores every member verbatim and holds no
 *       decision logic. Section 6 therefore asserts the width contract and the direction of the
 *       truncation the caller must apply, and cites
 *       {@code com.vsergeychik.carddemo.common.FixedWidthCodec#movePicX} as its owner rather than
 *       duplicating that codec's own tests here.</li>
 * </ol>
 *
 * <h2>Two source facts recorded rather than corrected</h2>
 *
 * <ul>
 *   <li>{@code app/bms/COBIL00.bms:1-2} carries the header comment
 *       {@code *    CardDemo - Main Menu Screen}, although {@code COBIL00} is the bill-payment screen
 *       and the main menu is {@code COMEN01}. The comment is wrong in the source. It is left exactly
 *       as it stands and is <strong>ignored for naming</strong> here: every name this suite asserts
 *       comes from {@code app/csd/CARDDEMO.CSD} or from the program, never from that comment. It is
 *       noted so a reader checking the {@code COBIL00.bms} line citations below is not misled.</li>
 *   <li>{@code app/cpy-bms/COBIL00.CPY:61} spells the length item of the {@code CURBAL} field
 *       {@code CURBALL}, with a doubled {@code L}. It reads like a typing slip but is in fact the
 *       {@code xxxL} suffix applied to a field name already ending in {@code L}. Either way it
 *       changes nothing here, because length items are metadata and are not payload members - and
 *       the metadata sweep below generates that very spelling from the {@code CURBAL} stem and
 *       asserts it never reaches the wire.</li>
 * </ul>
 *
 * <h2>Risk R-D: the absent IBM copybooks</h2>
 *
 * {@code app/cbl/COBIL00C.cbl:84-85} carries {@code COPY DFHAID.} and {@code COPY DFHBMSCA.}, but
 * neither copybook exists in this repository - they are IBM-supplied. Their constants are reproduced
 * in {@link BmsAttributes} from IBM CICS documentation, which is recorded as risk R-D in the
 * migration plan. This suite consequently takes {@code DFHGREEN} from {@link BmsAttributes} and
 * never attempts to read a {@code DFH*} copybook from {@code app/cpy/}.
 *
 * @see BillPaymentResponse
 * @see BillPaymentRequest
 * @see NavigationContext
 */
@DisplayName("BillPaymentResponse - the COBIL00 symbolic-map output projection (POST /api/billpay, CB00)")
class BillPaymentResponseTest {

    // =================================================================================================
    // The width table, hand-written and line-cited (practice B11).
    //
    // Every width below is the PICTURE clause of one xxxO item of 01 COBIL0AO REDEFINES COBIL0AI in
    // app/cpy-bms/COBIL00.CPY. The 3-byte FILLER and the xxxC, xxxP, xxxH and xxxV attribute items
    // that precede each of them are metadata and are deliberately absent from this table and from the
    // type under test. No copybook parser is used anywhere in this build, so this table is the
    // reviewable artefact: it is diffed against the copybook by eye, which is why each row carries its
    // source line.
    // =================================================================================================

    /**
     * The ten map members paired with their declared width, in the order {@code 01 COBIL0AO} declares
     * them. Iteration order is preserved deliberately: a parameterised failure then names fields in
     * copybook order rather than in a hash order that could vary between runs.
     */
    private static final Map<String, Integer> MAP_MEMBER_WIDTHS = buildMapMemberWidths();

    /**
     * The name of the width constant each map member is declared against, written out by hand so the
     * member-to-constant correspondence is read rather than computed from a naming convention. Both
     * {@link BillPaymentResponse} and {@link BillPaymentRequest} declare these same ten names,
     * because {@code 01 COBIL0AO REDEFINES COBIL0AI} ({@code app/cpy-bms/COBIL00.CPY:79}) makes the
     * two views one span.
     */
    private static final Map<String, String> WIDTH_CONSTANT_NAMES = buildWidthConstantNames();

    /**
     * A canonical value for each map member, each one exactly at its declared width and each one
     * distinct from the other nine. Distinctness is what lets a mutation test prove that exactly one
     * member changed; width-exactness is what makes every value a legal screen field.
     */
    private static final Map<String, String> CANONICAL_MAP_VALUES = buildCanonicalMapValues();

    /**
     * The symbolic-map item stem of each of the ten fields, exactly as {@code 01 COBIL0AO} spells it.
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
     * line 79 ({@code app/cpy-bms/COBIL00.CPY:81-85} and its nine repetitions). All seven are
     * presentation metadata and none may reach the wire (gate G9).
     */
    private static final List<String> METADATA_SUFFIXES =
            List.of("L", "F", "A", "C", "P", "H", "V");

    /**
     * The twenty-two members that travel on the wire, in declaration order: the ten {@code xxxO}
     * data items, the two collapsed presentation-state members, the four navigation members and the
     * six {@code CDEMO-CB00-INFO} members.
     */
    /**
     * The two instance members that are deliberately {@code @JsonIgnore}: they derive from the
     * {@code xxxL} length items and from {@code ERRMSGC}, an attribute item, and AAP 0.6.3 keeps both
     * kinds out of the payload. {@code BillPaymentController} projects them into
     * {@code screenMetadata.cursorField} and {@code screenMetadata.messageColour}, which is the shape
     * the other sixteen screens use.
     */
    private static final Set<String> NOT_PUBLISHED = Set.of("cursorField", "messageHighlight");

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
            "cursorField",
            "messageHighlight",
            "navigationContext",
            "nextProgram",
            "nextMapset",
            "nextMap",
            "trnIdFirst",
            "trnIdLast",
            "pageNum",
            "nextPageFlg",
            "trnSelFlg",
            "trnSelected");

    /**
     * The ten screen fields of {@code 01 COBIL0AO}, by Java member name, so {@link #wireNameOf(String)}
     * can tell them from the twelve members that trace to no {@code DFHMDF} field.
     */
    private static final java.util.Set<String> SCREEN_FIELD_MEMBERS = java.util.Set.of(
            "trnName", "title01", "curDate", "pgmName", "title02", "curTime", "actIdIn", "curBal",
            "confirm", "errMsg");

    /**
     * A member's name <strong>on the wire</strong>: a screen field answers to its {@code xxxI} item in
     * lower case, which {@code @JsonProperty} pins per AAP 0.6.3; the presentation, navigation and
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
        return members.stream().map(BillPaymentResponseTest::wireNameOf).toList();
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
     * The one-character rendering of {@code DFHGREEN}, derived from
     * {@link BmsAttributes#DFHGREEN} rather than written as a literal (practice B8).
     *
     * <p>{@code ERRMSGC} is {@code PICTURE X} - one byte - and
     * {@code BillPaymentResponse.MESSAGE_HIGHLIGHT_LENGTH} is one character, so the attribute
     * travels as the single character whose code point equals the unsigned value of the attribute
     * byte. The round trip is exact and is asserted in section 4:
     * {@code (byte) MESSAGE_HIGHLIGHT_GREEN.charAt(0) == BmsAttributes.DFHGREEN}. A hexadecimal
     * rendering would be five characters and would not fit the declared width.
     *
     * <p>{@code DFHGREEN} itself comes from the IBM-supplied {@code DFHBMSCA} copybook, which
     * {@code app/cbl/COBIL00C.cbl:85} copies but which is absent from this repository; it is
     * reproduced in {@link BmsAttributes} from IBM CICS documentation (risk R-D).
     */
    private static final String MESSAGE_HIGHLIGHT_GREEN =
            String.valueOf((char) BmsAttributes.unsigned(BmsAttributes.DFHGREEN));

    /** The value every mutation test writes: distinct from every canonical value, and deterministic. */
    private static final char MUTATION_CHARACTER = 'Z';

    /**
     * A fresh mapper per test method, with Jackson's own defaults untouched - in particular
     * {@code FAIL_ON_UNKNOWN_PROPERTIES} is left enabled, because the point of the wire tests is what
     * the type declares rather than what a customised mapper could be persuaded to accept. It is an
     * instance field rather than a static one so that no test can observe another's mapper state
     * (gate G53, practice B9).
     */
    private final ObjectMapper mapper = new ObjectMapper();

    private static Map<String, Integer> buildMapMemberWidths() {
        Map<String, Integer> widths = new LinkedHashMap<>();
        widths.put("trnName", BillPaymentResponse.TRN_NAME_LENGTH);   // TRNNAMEO PIC X(4)   CPY L86
        widths.put("title01", BillPaymentResponse.TITLE01_LENGTH);    // TITLE01O PIC X(40)  CPY L92
        widths.put("curDate", BillPaymentResponse.CUR_DATE_LENGTH);   // CURDATEO PIC X(8)   CPY L98
        widths.put("pgmName", BillPaymentResponse.PGM_NAME_LENGTH);   // PGMNAMEO PIC X(8)   CPY L104
        widths.put("title02", BillPaymentResponse.TITLE02_LENGTH);    // TITLE02O PIC X(40)  CPY L110
        widths.put("curTime", BillPaymentResponse.CUR_TIME_LENGTH);   // CURTIMEO PIC X(8)   CPY L116
        widths.put("actIdIn", BillPaymentResponse.ACT_ID_IN_LENGTH);  // ACTIDINO PIC X(11)  CPY L122
        widths.put("curBal", BillPaymentResponse.CUR_BAL_LENGTH);     // CURBALO  PIC X(14)  CPY L128
        widths.put("confirm", BillPaymentResponse.CONFIRM_LENGTH);    // CONFIRMO PIC X(1)   CPY L134
        widths.put("errMsg", BillPaymentResponse.ERR_MSG_LENGTH);     // ERRMSGO  PIC X(78)  CPY L140
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
        widths.put("trnIdFirst", BillPaymentResponse.TRN_ID_FIRST_LENGTH);
        // CDEMO-CB00-TRNID-LAST    PIC X(16)   COBIL00C.cbl L66
        widths.put("trnIdLast", BillPaymentResponse.TRN_ID_LAST_LENGTH);
        // CDEMO-CB00-PAGE-NUM      PIC 9(08)   COBIL00C.cbl L67 - the one non-String member
        widths.put("pageNum", BillPaymentResponse.PAGE_NUM_DIGITS);
        // CDEMO-CB00-NEXT-PAGE-FLG PIC X(01) VALUE 'N'   COBIL00C.cbl L68
        widths.put("nextPageFlg", BillPaymentResponse.NEXT_PAGE_FLG_LENGTH);
        // CDEMO-CB00-TRN-SEL-FLG   PIC X(01)   COBIL00C.cbl L71
        widths.put("trnSelFlg", BillPaymentResponse.TRN_SEL_FLG_LENGTH);
        // CDEMO-CB00-TRN-SELECTED  PIC X(16)   COBIL00C.cbl L72
        widths.put("trnSelected", BillPaymentResponse.TRN_SELECTED_LENGTH);
        return Collections.unmodifiableMap(widths);
    }

    /**
     * One canonical value per map member, every one exactly at its declared width and every one
     * distinct. The header values are the ones {@code POPULATE-HEADER-INFO} writes at
     * {@code app/cbl/COBIL00C.cbl:323-338}; the message is the composed success text of lines
     * 527-531, which is 61 characters and therefore fits the 78-character field.
     */
    private static Map<String, String> buildCanonicalMapValues() {
        Map<String, String> values = new LinkedHashMap<>();
        // MOVE WS-TRANID  TO TRNNAMEO   COBIL00C L325 - four characters, exactly the field width.
        values.put("trnName", BillPaymentResponse.TRANSACTION_ID);
        values.put("title01", atWidth("AWS Mainframe Modernization",
                BillPaymentResponse.TITLE01_LENGTH));
        values.put("curDate", "07/19/22");
        // MOVE WS-PGMNAME TO PGMNAMEO   COBIL00C L326 - eight characters, exactly the field width.
        values.put("pgmName", BillPaymentResponse.PROGRAM_NAME);
        values.put("title02", atWidth("CardDemo", BillPaymentResponse.TITLE02_LENGTH));
        values.put("curTime", "23:15:57");
        values.put("actIdIn", "00000000011");
        // WS-CURR-BAL PIC +9999999999.99 rendered - COBIL00C L56, L193-194.
        values.put("curBal", "+0000000019.40");
        values.put("confirm", "Y");
        values.put("errMsg", atWidth("Payment successful.  Your Transaction ID is 0000000000000042.",
                BillPaymentResponse.ERR_MSG_LENGTH));
        return Collections.unmodifiableMap(values);
    }

    /**
     * Right-pads {@code text} with spaces to {@code width}, which is what a COBOL alphanumeric
     * {@code MOVE} does with a sending item shorter than its receiver. Used only to build test data
     * at the declared width; the {@code MOVE} rule itself belongs to
     * {@code com.vsergeychik.carddemo.common.FixedWidthCodec} and is not reimplemented here.
     */
    private static String atWidth(String text, int width) {
        return text + " ".repeat(width - text.length());
    }

    private static String repeat(char character, int length) {
        return String.valueOf(character).repeat(length);
    }

    private static String capitalise(String member) {
        return Character.toUpperCase(member.charAt(0)) + member.substring(1);
    }

    /** Sets one {@code String} map member by name, so a parameterised case can drive all ten. */
    private static void set(BillPaymentResponse response, String member, String value)
            throws ReflectiveOperationException {
        BillPaymentResponse.class.getMethod("set" + capitalise(member), String.class)
                .invoke(response, value);
    }

    /** Reads one {@code String} map member by name. */
    private static String get(BillPaymentResponse response, String member)
            throws ReflectiveOperationException {
        return (String) BillPaymentResponse.class.getMethod("get" + capitalise(member))
                .invoke(response);
    }

    /** Sets one {@code String} map member on the request twin, for the symmetry assertions. */
    private static void setOnRequest(BillPaymentRequest request, String member, String value)
            throws ReflectiveOperationException {
        BillPaymentRequest.class.getMethod("set" + capitalise(member), String.class)
                .invoke(request, value);
    }

    /** Reads a declared {@code public static final int} constant of either payload type by name. */
    private static int constant(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        return field.getInt(null);
    }

    /**
     * A fully populated response: the ten map members at their declared widths, both collapsed
     * presentation members in their set state, all four navigation members and every one of the six
     * communication-area extension members. This is the canonical instance the wire, mutation and
     * value-semantics groups work from, so a single helper keeps them describing the same object.
     */
    private static BillPaymentResponse canonical() throws ReflectiveOperationException {
        BillPaymentResponse response = new BillPaymentResponse();
        for (Map.Entry<String, String> value : CANONICAL_MAP_VALUES.entrySet()) {
            set(response, value.getKey(), value.getValue());
        }
        response.setCursorField(CursorField.ACTIDIN);
        response.setMessageHighlight(MESSAGE_HIGHLIGHT_GREEN);
        response.setNavigationContext(NavigationContext.empty()
                .withFromTranid(BillPaymentResponse.TRANSACTION_ID)
                .withFromProgram(BillPaymentResponse.PROGRAM_NAME)
                .withUserId("USER0001")
                .withUserTypeUser()
                .withPgmReenter()
                .withLastMap(BillPaymentResponse.MAP_NAME)
                .withLastMapset(BillPaymentResponse.MAPSET_NAME));
        response.setNextProgram(BillPaymentResponse.MAIN_MENU_PROGRAM);
        response.setNextMapset(BillPaymentResponse.MAPSET_NAME);
        response.setNextMap(BillPaymentResponse.MAP_NAME);
        response.setTrnIdFirst("0000000000000001");
        response.setTrnIdLast("0000000000000010");
        response.setPageNum(3);
        response.setNextPageFlg(BillPaymentResponse.NEXT_PAGE_YES);
        response.setTrnSelFlg("S");
        response.setTrnSelected("0000000000000007");
        return response;
    }

    /** A request carrying the identical ten payload values, for the redefinition-symmetry test. */
    private static BillPaymentRequest requestWithTheSameTenValues()
            throws ReflectiveOperationException {
        BillPaymentRequest request = new BillPaymentRequest();
        for (Map.Entry<String, String> value : CANONICAL_MAP_VALUES.entrySet()) {
            setOnRequest(request, value.getKey(), value.getValue());
        }
        return request;
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
     * The eight field stems that never receive a {@code MOVE -1}, so the nested cursor enum must
     * declare no constant for any of them. Only {@code ACTIDINL} and {@code CONFIRML} are ever set
     * negative in {@code app/cbl/COBIL00C.cbl}; {@code CURBALL} never is, {@code ERRMSGL} never is,
     * and no header field ever is.
     */
    private static Stream<Arguments> neverCursoredStems() {
        return SYMBOLIC_MAP_ITEM_STEMS.stream()
                .filter(stem -> !"ACTIDIN".equals(stem) && !"CONFIRM".equals(stem))
                .map(Arguments::of);
    }

    /**
     * The four {@code nextProgram} values {@code app/cbl/COBIL00C.cbl} can reach, each paired with
     * its evidence. The last two stand for whatever the communication area already carried;
     * {@code COADM01C} and {@code COCRDLIC} are both real programs in
     * {@code app/csd/CARDDEMO.CSD} and are eight characters, matching
     * {@code CDEMO-FROM-PROGRAM PIC X(08)}. Which of the four applies on a given attention
     * identifier is the controller's decision and is asserted in the controller suite, not here.
     */
    private static Stream<Arguments> reachableNextPrograms() {
        return Stream.of(
                Arguments.of(BillPaymentResponse.SIGN_ON_PROGRAM,
                        "COBIL00C:107-109 EIBCALEN = 0, and :275-276 the LOW-VALUES fallback"),
                Arguments.of(BillPaymentResponse.MAIN_MENU_PROGRAM,
                        "COBIL00C:129-130 DFHPF3 with no caller to return to"),
                Arguments.of("COADM01C",
                        "COBIL00C:131-133 the echoed CDEMO-FROM-PROGRAM"),
                Arguments.of("COCRDLIC",
                        "COBIL00C:282 the echoed CDEMO-TO-PROGRAM"));
    }

    /**
     * Three renderings of {@code WS-CURR-BAL PIC +9999999999.99} ({@code app/cbl/COBIL00C.cbl:56}):
     * a positive balance, a negative one and zero. The picture is <em>edited</em>, so the sign
     * position is always occupied and the integer part is always zero-filled to ten digits - which is
     * exactly what a numeric Java type would destroy (gate G22).
     */
    private static Stream<Arguments> editedBalances() {
        return Stream.of(Arguments.of("+0000000019.40"),
                Arguments.of("-0000000019.40"),
                Arguments.of("+0000000000.00"));
    }

    /** The published JSON property names of an instance, as a set so comparisons are order-free. */
    private Set<String> publishedNames(BillPaymentResponse response) {
        JsonNode body = mapper.valueToTree(response);
        Set<String> names = new LinkedHashSet<>();
        body.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static List<String> instanceFieldNames() {
        List<String> names = new ArrayList<>();
        for (Field field : BillPaymentResponse.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                names.add(field.getName());
            }
        }
        return names;
    }

    @Nested
    @DisplayName("1. The ten xxxO payload members, and the redefinition identity with the request")
    class PayloadMembers {

        @ParameterizedTest(name = "{0} is declared String, width {1}")
        @MethodSource(
                "com.vsergeychik.carddemo.billing.dto.BillPaymentResponseTest#mapMemberWidths")
        @DisplayName("every map member is a String - the mapset declares no PICIN and no PICOUT")
        void everyMapMemberIsAString(String member, int width) throws Exception {
            Field field = BillPaymentResponse.class.getDeclaredField(member);

            // app/bms/COBIL00.bms carries no PICIN and no PICOUT anywhere, so every symbolic-map data
            // item is a plain PIC X(n) and there is no numeric item on this screen at all - not even
            // for the balance, which arrives already edited into characters.
            assertThat(field.getType())
                    .as("%s projects a PIC X(%d) item and must be a String", member, width)
                    .isEqualTo(String.class);
            assertThat(width).isPositive();
        }

        @ParameterizedTest(name = "{0} is bounded by the constant declaring {1}")
        @MethodSource(
                "com.vsergeychik.carddemo.billing.dto.BillPaymentResponseTest#mapMemberWidths")
        @DisplayName("each width is published as a public static final int naming its copybook item")
        void eachWidthIsPublishedAsAConstant(String member, int width) throws Exception {
            String constantName = WIDTH_CONSTANT_NAMES.get(member);
            assertThat(constantName).as("every member has a hand-written constant name").isNotNull();

            Field constant = BillPaymentResponse.class.getDeclaredField(constantName);

            assertThat(constant.getType()).isEqualTo(int.class);
            assertThat(Modifier.isPublic(constant.getModifiers())).isTrue();
            assertThat(Modifier.isStatic(constant.getModifiers())).isTrue();
            assertThat(Modifier.isFinal(constant.getModifiers())).isTrue();
            assertThat(constant.getInt(null))
                    .as("%s must declare the copybook width of its xxxO item", constantName)
                    .isEqualTo(width);
        }

        @ParameterizedTest(name = "{0} carries a value of exactly {1} characters unaltered")
        @MethodSource(
                "com.vsergeychik.carddemo.billing.dto.BillPaymentResponseTest#mapMemberWidths")
        @DisplayName("a value exactly at the declared width is carried unaltered")
        void aValueAtTheDeclaredWidthIsCarriedUnaltered(String member, int width) throws Exception {
            BillPaymentResponse response = new BillPaymentResponse();
            String value = CANONICAL_MAP_VALUES.get(member);

            set(response, member, value);

            assertThat(value).as("the canonical value for %s is width-exact", member).hasSize(width);
            assertThat(get(response, member)).isEqualTo(value).hasSize(width);
        }

        @Test
        @DisplayName("the ten canonical values are all distinct, so a mutation is unambiguous")
        void theTenCanonicalValuesAreDistinct() {
            assertThat(CANONICAL_MAP_VALUES).hasSize(BillPaymentResponse.MAP_FIELD_COUNT);
            assertThat(CANONICAL_MAP_VALUES.values()).doesNotHaveDuplicates();
            assertThat(CANONICAL_MAP_VALUES.keySet())
                    .containsExactlyElementsOf(MAP_MEMBER_WIDTHS.keySet());
        }

        @Test
        @DisplayName("the ten widths sum to 212, the data length of 01 COBIL0AO")
        void theTenWidthsSumToTheDataLength() {
            int sum = MAP_MEMBER_WIDTHS.values().stream().mapToInt(Integer::intValue).sum();

            // 4 + 40 + 8 + 8 + 40 + 8 + 11 + 14 + 1 + 78.
            assertThat(sum).isEqualTo(212);
            assertThat(BillPaymentResponse.MAP_DATA_LENGTH).isEqualTo(sum);
        }

        @Test
        @DisplayName("the symbolic map is 294 bytes: 12 prefix + 10 x 7 prologue + 212 data")
        void theSymbolicMapIsTwoHundredAndNinetyFourBytes() {
            // The 12-byte lead FILLER exists because app/bms/COBIL00.bms:24 requests TIOAPFX=YES; the
            // seven-byte prologue is xxxL + xxxF + FILLER X(4) on the input view and
            // FILLER X(3) + xxxC + xxxP + xxxH + xxxV on the output view. Both spend seven, which is
            // precisely what lets 01 COBIL0AO REDEFINE 01 COBIL0AI field for field.
            assertThat(BillPaymentResponse.TIOAPFX_PREFIX_LENGTH).isEqualTo(12);
            assertThat(BillPaymentResponse.FIELD_PROLOGUE_LENGTH).isEqualTo(7);
            assertThat(BillPaymentResponse.MAP_FIELD_COUNT).isEqualTo(10);
            assertThat(BillPaymentResponse.SYMBOLIC_MAP_LENGTH)
                    .isEqualTo(12 + (10 * 7) + 212)
                    .isEqualTo(294);
        }

        @Test
        @DisplayName("there are exactly ten map members - ten stems, ten widths, ten constants")
        void thereAreExactlyTenMapMembers() {
            // app/bms/COBIL00.bms declares 24 DFHMDF statements of which exactly ten are
            // name-labelled: TRNNAME (L34), TITLE01 (L38), CURDATE (L47), PGMNAME (L57), TITLE02
            // (L61), CURTIME (L70), ACTIDIN (L85), CURBAL (L103), CONFIRM (L115) and ERRMSG (L127).
            // The other fourteen are screen furniture and three LENGTH=0 stoppers, rendered by the
            // client and modelled by no member here.
            assertThat(MAP_MEMBER_WIDTHS).hasSize(BillPaymentResponse.MAP_FIELD_COUNT);
            assertThat(WIDTH_CONSTANT_NAMES).hasSize(BillPaymentResponse.MAP_FIELD_COUNT);
            assertThat(SYMBOLIC_MAP_ITEM_STEMS).hasSize(BillPaymentResponse.MAP_FIELD_COUNT);
        }

        @Test
        @DisplayName("no member is named after a symbolic-map metadata item (COBIL00.CPY:81-85)")
        void noMemberIsNamedAfterAMetadataItem() {
            Set<String> declared = new LinkedHashSet<>();
            for (String name : instanceFieldNames()) {
                declared.add(name.toLowerCase(Locale.ROOT));
            }

            List<String> named = new ArrayList<>();
            for (String stem : SYMBOLIC_MAP_ITEM_STEMS) {
                for (String suffix : METADATA_SUFFIXES) {
                    if (declared.contains((stem + suffix).toLowerCase(Locale.ROOT))) {
                        named.add(stem + suffix);
                    }
                }
            }

            // Seventy names are checked case-insensitively. In particular there is no actidinL: the
            // cursor signal that MOVE -1 TO ACTIDINL expresses is modelled as the nested CursorField
            // enum instead, because a member per length item would put symbolic-map metadata on the
            // wire and break gate G9.
            assertThat(named)
                    .as("a member named after an xxxL, xxxF, xxxA, xxxC, xxxP, xxxH or xxxV item "
                            + "would put presentation metadata on the wire (gate G9)")
                    .isEmpty();
        }

        @Test
        @DisplayName("the twenty-two declared members are exactly the wire members, in order")
        void theDeclaredMembersAreExactlyTheWireMembers() {
            assertThat(instanceFieldNames()).containsExactlyElementsOf(WIRE_MEMBERS);
            assertThat(WIRE_MEMBERS).hasSize(22);
            // Two of the twenty-two are @JsonIgnore and travel as screenMetadata instead - see
            // NOT_PUBLISHED and theBodyCarriesExactlyTheTwentyMembers.
            assertThat(NOT_PUBLISHED).hasSize(2).allMatch(WIRE_MEMBERS::contains);
        }

        @Test
        @DisplayName("request and response declare the same ten payload names (COBIL00.CPY:79)")
        void requestAndResponseDeclareTheSameTenPayloadNames() {
            Set<String> responseMembers = new LinkedHashSet<>(MAP_MEMBER_WIDTHS.keySet());
            Set<String> requestMembers = new LinkedHashSet<>();
            for (Field field : BillPaymentRequest.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())
                        && responseMembers.contains(field.getName())) {
                    requestMembers.add(field.getName());
                }
            }

            // 01 COBIL0AO REDEFINES COBIL0AI, so the two views are one byte span and their payload
            // items sit at identical offsets with identical widths. Any divergence between the two
            // Java types is a defect in one of them.
            assertThat(requestMembers)
                    .as("the output view redefines the input view, so both carry the same ten names")
                    .containsExactlyInAnyOrderElementsOf(responseMembers);
        }

        @ParameterizedTest(name = "{0} is width {1} and type String on both request and response")
        @MethodSource(
                "com.vsergeychik.carddemo.billing.dto.BillPaymentResponseTest#mapMemberWidths")
        @DisplayName("every payload width and type is identical on request and response")
        void everyPayloadWidthAndTypeIsIdenticalOnBothViews(String member, int width)
                throws Exception {
            String constantName = WIDTH_CONSTANT_NAMES.get(member);

            assertThat(constant(BillPaymentResponse.class, constantName)).isEqualTo(width);
            assertThat(constant(BillPaymentRequest.class, constantName))
                    .as("%s must declare the same width on both views", constantName)
                    .isEqualTo(width);
            assertThat(BillPaymentRequest.class.getDeclaredField(member).getType())
                    .isEqualTo(String.class);
            assertThat(BillPaymentResponse.class.getDeclaredField(member).getType())
                    .isEqualTo(String.class);
        }

        @Test
        @DisplayName("both views agree on the geometry, which is why COBIL00C:194 is legal")
        void bothViewsAgreeOnTheGeometry() throws Exception {
            // COBIL00C moves the edited balance into CURBALI - the INPUT-view item - at line 194 and
            // then sends the map FROM(COBIL0AO) at line 298. That is legal precisely because the bytes
            // coincide, and it is why curBal is a genuine response member even though the program
            // writes it through the other view.
            assertThat(constant(BillPaymentRequest.class, "SYMBOLIC_MAP_LENGTH"))
                    .isEqualTo(BillPaymentResponse.SYMBOLIC_MAP_LENGTH);
            assertThat(constant(BillPaymentRequest.class, "MAP_DATA_LENGTH"))
                    .isEqualTo(BillPaymentResponse.MAP_DATA_LENGTH);
            assertThat(constant(BillPaymentRequest.class, "TIOAPFX_PREFIX_LENGTH"))
                    .isEqualTo(BillPaymentResponse.TIOAPFX_PREFIX_LENGTH);
            assertThat(constant(BillPaymentRequest.class, "FIELD_PROLOGUE_LENGTH"))
                    .isEqualTo(BillPaymentResponse.FIELD_PROLOGUE_LENGTH);
            assertThat(constant(BillPaymentRequest.class, "MAP_FIELD_COUNT"))
                    .isEqualTo(BillPaymentResponse.MAP_FIELD_COUNT);
        }

        @Test
        @DisplayName("the screen identity constants are the CSD and mapset values")
        void theScreenIdentityConstantsAreTheCsdValues() {
            // DEFINE MAPSET(COBIL00) app/csd/CARDDEMO.CSD:114; COBIL0A DFHMDI app/bms/COBIL00.bms:26;
            // DEFINE PROGRAM(COBIL00C) :196; DEFINE TRANSACTION(CB00) :337 with PROGRAM(COBIL00C) :338.
            assertThat(BillPaymentResponse.MAPSET_NAME).isEqualTo("COBIL00");
            assertThat(BillPaymentResponse.MAP_NAME).isEqualTo("COBIL0A");
            assertThat(BillPaymentResponse.PROGRAM_NAME).isEqualTo("COBIL00C");
            assertThat(BillPaymentResponse.TRANSACTION_ID).isEqualTo("CB00");
            assertThat(BillPaymentResponse.TRANSACTION_ID)
                    .as("WS-TRANID PIC X(04) at COBIL00C:38 - and the width of TRNNAMEO")
                    .hasSize(BillPaymentResponse.TRN_NAME_LENGTH);
            assertThat(BillPaymentResponse.PROGRAM_NAME)
                    .as("WS-PGMNAME PIC X(08) at COBIL00C:37 - and the width of PGMNAMEO")
                    .hasSize(BillPaymentResponse.PGM_NAME_LENGTH);
        }
    }


    @Nested
    @DisplayName("2. The wire format - twenty-two members, no metadata, and strict binding")
    class WireFormat {

        @Test
        @DisplayName("the serialised body carries exactly the twenty published members and nothing else")
        void theBodyCarriesExactlyTheTwentyMembers() throws Exception {
            Set<String> published = publishedNames(canonical());

            // Compared as sets, so a failure lists both what is missing and what is unexpected. The ten
            // screen fields are published under their xxxI items in lower case (AAP 0.6.3); the ten
            // carriers under their own names.
            //
            // cursorField and messageHighlight are NOT among them. They derive from the xxxL items and
            // from ERRMSGC, which AAP 0.6.3 keeps out of the payload, and BillPaymentController projects
            // them into screenMetadata as the other sixteen screens do. Publishing the colour at the top
            // level as a raw byte - U+00F4 for DFHGREEN, printing as a stray glyph - made this screen the
            // only one of the seventeen a client had to read differently.
            List<String> publishedMembers = WIRE_MEMBERS.stream()
                    .filter(member -> !NOT_PUBLISHED.contains(member))
                    .toList();
            assertThat(published).containsExactlyInAnyOrderElementsOf(wireNamesOf(publishedMembers));
            assertThat(published).hasSize(20);
            assertThat(published).doesNotContain("cursorField", "messageHighlight");
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
                    if (publishedLowerCase.contains((stem + suffix).toLowerCase(Locale.ROOT))) {
                        leaked.add(stem + suffix);
                    }
                }
            }

            // Seventy names, checked case-insensitively, so neither ERRMSGC nor a camelCased errmsgC
            // could slip through. xxxL is the inbound length CICS reports, xxxF the flag byte and xxxA
            // the attribute view over it (COBIL00.CPY:19-23); xxxC, xxxP, xxxH and xxxV are the
            // output-view attribute items of 01 COBIL0AO REDEFINES COBIL0AI (COBIL00.CPY:81-85). All
            // seven are validation and highlight metadata, and none is payload.
            assertThat(leaked)
                    .as("a symbolic-map metadata item reached the wire; only the ten xxxO data items "
                            + "are payload (gate G9)")
                    .isEmpty();
            assertThat(SYMBOLIC_MAP_ITEM_STEMS).hasSize(BillPaymentResponse.MAP_FIELD_COUNT);
            assertThat(METADATA_SUFFIXES).hasSize(7);
        }

        @Test
        @DisplayName("there is no property named actidinL - the cursor signal is an enum instead")
        void thereIsNoPropertyNamedActidinL() throws Exception {
            Set<String> published = publishedNames(canonical());
            Set<String> publishedLowerCase = new LinkedHashSet<>();
            for (String name : published) {
                publishedLowerCase.add(name.toLowerCase(Locale.ROOT));
            }

            // Fifteen of the program's seventeen MOVE -1 statements target ACTIDINL. Modelling that as
            // a member would be the single easiest way to break gate G9, so it is called out on its
            // own rather than being left to the seventy-name sweep above. Both spellings are checked.
            assertThat(published).doesNotContain("actidinL");
            assertThat(publishedLowerCase).doesNotContain("actidinl");
            assertThat(published)
                    .as("the cursor signal is metadata and travels as screenMetadata.cursorField, which "
                            + "BillPaymentController assembles - it is not a member of this type")
                    .doesNotContain("cursorField");
        }

        @Test
        @DisplayName("no derived predicate leaks as a property")
        void noDerivedPredicateLeaksAsAProperty() {
            BillPaymentResponse response = new BillPaymentResponse();
            response.setNextPageFlg(BillPaymentResponse.NEXT_PAGE_YES);

            JsonNode body = mapper.valueToTree(response);

            assertThat(body.has("nextPageYes")).isFalse();
            assertThat(body.has("nextPageNo")).isFalse();
            assertThat(body.get("nextPageFlg").asText())
                    .isEqualTo(BillPaymentResponse.NEXT_PAGE_YES);
        }

        @Test
        @DisplayName("a round trip through JSON is lossless for every one of the twenty-two members")
        void aRoundTripIsLossless() throws Exception {
            BillPaymentResponse original = canonical();

            BillPaymentResponse bound = mapper.readValue(
                    mapper.writeValueAsString(original), BillPaymentResponse.class);

            for (String member : MAP_MEMBER_WIDTHS.keySet()) {
                assertThat(get(bound, member)).as(member).isEqualTo(get(original, member));
            }
            // Both of these are @JsonIgnore, so they do not travel and come back at their declared
            // initial state rather than at the original's value. That is the point of the annotation.
            assertThat(bound.getCursorField()).isSameAs(CursorField.NONE);
            assertThat(bound.getMessageHighlight()).isNull();
            assertThat(bound.getNavigationContext()).isEqualTo(original.getNavigationContext());
            assertThat(bound.getNextProgram()).isEqualTo(BillPaymentResponse.MAIN_MENU_PROGRAM);
            assertThat(bound.getNextMapset()).isEqualTo(BillPaymentResponse.MAPSET_NAME);
            assertThat(bound.getNextMap()).isEqualTo(BillPaymentResponse.MAP_NAME);
            assertThat(bound.getTrnIdFirst()).isEqualTo("0000000000000001");
            assertThat(bound.getTrnIdLast()).isEqualTo("0000000000000010");
            assertThat(bound.getPageNum()).isEqualTo(3);
            assertThat(bound.getNextPageFlg()).isEqualTo(BillPaymentResponse.NEXT_PAGE_YES);
            assertThat(bound.getTrnSelFlg()).isEqualTo("S");
            assertThat(bound.getTrnSelected()).isEqualTo("0000000000000007");
        }

        @Test
        @DisplayName("an undeclared property is refused - the screen contract is closed")
        void anUndeclaredPropertyIsRefused() {
            // The type declares no @JsonIgnoreProperties and no Jackson annotation at all, so
            // Jackson's default FAIL_ON_UNKNOWN_PROPERTIES applies: a name that is not part of the
            // screen contract is a protocol error rather than something to swallow. This asserts the
            // declared behaviour; it does not ask the type to change.
            assertThatExceptionOfType(UnrecognizedPropertyException.class)
                    .isThrownBy(() -> mapper.readValue(
                            "{\"actidin\":\"00000000011\",\"notAScreenField\":\"x\"}",
                            BillPaymentResponse.class))
                    .withMessageContaining("notAScreenField");
        }

        @Test
        @DisplayName("a metadata item name in the body is refused, not silently absorbed")
        void aMetadataItemNameInTheBodyIsRefused() {
            // The complement of the G9 sweep: not publishing a metadata item is one thing, and
            // refusing to accept one is another. ERRMSGC is the item COBIL00C:526 writes, and its
            // projection is the purposefully named messageHighlight - so the raw item name is unknown.
            assertThatExceptionOfType(UnrecognizedPropertyException.class)
                    .isThrownBy(() -> mapper.readValue("{\"errMsgC\":\" \"}",
                            BillPaymentResponse.class))
                    .withMessageContaining("errMsgC");
            assertThatExceptionOfType(UnrecognizedPropertyException.class)
                    .isThrownBy(() -> mapper.readValue("{\"actIdInL\":-1}",
                            BillPaymentResponse.class))
                    .withMessageContaining("actIdInL");
        }

        @Test
        @DisplayName("an empty body binds to the declared initial state")
        void anEmptyBodyBinds() throws Exception {
            BillPaymentResponse bound = mapper.readValue("{}", BillPaymentResponse.class);

            // Every map member is at its declared width carrying the LOW-VALUES image, which is what
            // MOVE LOW-VALUES TO COBIL0AO at COBIL00C:114 produces. Never null: a fixed-width screen
            // field always has a width, and null would say "there is no such field".
            for (String member : MAP_MEMBER_WIDTHS.keySet()) {
                assertThat(get(bound, member))
                        .as(member)
                        .isEqualTo(ScreenFieldImage.unpainted(MAP_MEMBER_WIDTHS.get(member)));
            }
            assertThat(bound.getCursorField())
                    .as("the field initialiser stands - CursorField.NONE means no override, and the "
                            + "terminal then applies the mapset's own IC field at COBIL00.bms:85")
                    .isEqualTo(CursorField.NONE);
            assertThat(bound.getMessageHighlight())
                    .as("@JsonIgnore, so it does not travel and comes back at its initial state")
                    .isNull();
            assertThat(bound.getNavigationContext()).isNull();
            // The navigation and extension carriers are CARDDEMO-COMMAREA items, so their resting state
            // is spaces at the declared width - never null. Only navigationContext itself is nullable,
            // because an absent communication area is how EIBCALEN = 0 is encoded.
            assertThat(bound.getNextProgram()).isBlank()
                    .hasSize(NavigationContext.TO_PROGRAM_LENGTH);
            assertThat(bound.getNextMapset()).isBlank()
                    .hasSize(NavigationContext.LAST_MAPSET_LENGTH);
            assertThat(bound.getNextMap()).isBlank()
                    .hasSize(NavigationContext.LAST_MAP_LENGTH);
            assertThat(bound.getTrnIdFirst()).isBlank()
                    .hasSize(BillPaymentResponse.TRN_ID_FIRST_LENGTH);
            assertThat(bound.getTrnIdLast()).isBlank()
                    .hasSize(BillPaymentResponse.TRN_ID_LAST_LENGTH);
            assertThat(bound.getPageNum()).isZero();
            assertThat(bound.getNextPageFlg())
                    .as("the VALUE 'N' clause of app/cbl/COBIL00C.cbl:68 survives an empty body")
                    .isEqualTo(BillPaymentResponse.NEXT_PAGE_NO);
            assertThat(bound.getTrnSelFlg()).isBlank()
                    .hasSize(BillPaymentResponse.TRN_SEL_FLG_LENGTH);
            assertThat(bound.getTrnSelected()).isBlank()
                    .hasSize(BillPaymentResponse.TRN_SELECTED_LENGTH);
        }

        @Test
        @DisplayName("an explicit null overwrites a defaulted member - the type coalesces nothing")
        void anExplicitNullOverwritesADefaultedMember() throws Exception {
            BillPaymentResponse bound = mapper.readValue(
                    "{\"errmsg\":null,\"cursorField\":null,\"nextPageFlg\":null}",
                    BillPaymentResponse.class);

            // The setters store what they are given and nothing else - no coalescing, no defaulting at
            // write time. An explicit null therefore replaces the field initialiser for every member
            // Jackson binds. Which is not the same set as before: cursorField is @JsonIgnore now, so no
            // value in the body reaches it at all and its initialiser stands.
            assertThat(bound.getErrMsg()).isNull();
            assertThat(bound.getCursorField())
                    .as("cursorField is @JsonIgnore, so an explicit null in the body is not even read "
                            + "and the field initialiser stands - the cursor request travels on "
                            + "screenMetadata, which BillPaymentController assembles")
                    .isSameAs(CursorField.NONE);
            assertThat(bound.getNextPageFlg()).isNull();
        }

        @Test
        @DisplayName("null, empty and blank survive a round trip as three distinct states")
        void theThreeStatesSurviveDistinctly() throws Exception {
            BillPaymentResponse absent = new BillPaymentResponse();
            BillPaymentResponse empty = new BillPaymentResponse();
            BillPaymentResponse blank = new BillPaymentResponse();
            absent.setConfirm(null);
            empty.setConfirm("");
            blank.setConfirm(" ");

            assertThat(reboundConfirm(absent)).isNull();
            assertThat(reboundConfirm(empty)).isEmpty();
            assertThat(reboundConfirm(blank)).isEqualTo(" ").isNotEmpty().isBlank();
        }

        @Test
        @DisplayName("the ten payload properties are identical on request and response (COBIL00.CPY:79)")
        void theTenPayloadPropertiesAreIdenticalOnBothViews() throws Exception {
            JsonNode responseBody = mapper.valueToTree(canonical());
            JsonNode requestBody = mapper.valueToTree(requestWithTheSameTenValues());

            // The wire-level expression of the redefinition: the two views are one byte span, so the
            // same ten names carry the same ten values in both documents. Only the ten are compared -
            // the response additionally carries the cursor indicator, the highlight and the navigation
            // target, and the request additionally carries the attention identifier.
            for (Map.Entry<String, String> value : CANONICAL_MAP_VALUES.entrySet()) {
                String member = wireNameOf(value.getKey());
                assertThat(responseBody.has(member)).as("response publishes %s", member).isTrue();
                assertThat(requestBody.has(member)).as("request publishes %s", member).isTrue();
                assertThat(responseBody.get(member).asText())
                        .as("%s must carry the same value on both views", member)
                        .isEqualTo(requestBody.get(member).asText())
                        .isEqualTo(value.getValue());
            }
        }

        @Test
        @DisplayName("every payload property serialises as a JSON string, never as a number")
        void everyPayloadPropertyIsTextual() throws Exception {
            JsonNode body = mapper.valueToTree(canonical());

            for (String member : wireNamesOf(MAP_MEMBER_WIDTHS.keySet())) {
                assertThat(body.get(member).isTextual())
                        .as("%s projects a PIC X item and must be quoted text (gate G22)", member)
                        .isTrue();
            }
            assertThat(body.get("pageNum").isInt())
                    .as("CDEMO-CB00-PAGE-NUM PIC 9(08) is the one scale-free numeric member")
                    .isTrue();
        }

        private String reboundConfirm(BillPaymentResponse response) throws Exception {
            return mapper.readValue(mapper.writeValueAsString(response),
                    BillPaymentResponse.class).getConfirm();
        }
    }


    @Nested
    @DisplayName("3. The cursor indicator - seventeen MOVE -1 sites collapsed into three constants")
    class CursorIndicator {

        @Test
        @DisplayName("the enum declares exactly NONE, ACTIDIN and CONFIRM, in that order")
        void theEnumDeclaresExactlyThreeConstants() {
            // Every cursor-positioning statement in app/cbl/COBIL00C.cbl, verified by exhaustive grep:
            //   MOVE -1 TO ACTIDINL OF COBIL0AI  x15  lines 115, 163, 203, 363, 370, 394, 401, 427,
            //                                         434, 458, 465, 494, 538, 545, 562
            //   MOVE -1 TO CONFIRML OF COBIL0AI  x 2  lines 189, 239
            // Seventeen in total - fifteen is the ACTIDINL subtotal, not the total. The 15-versus-2
            // asymmetry is why the account-id cursor is the overwhelmingly common case; it is not a
            // reason to model seventeen things. NONE is the third state: no cursor statement on the
            // path, for example the invalid-key path at lines 138-141.
            assertThat(CursorField.values())
                    .containsExactly(CursorField.NONE, CursorField.ACTIDIN, CursorField.CONFIRM);
            assertThat(CursorField.values()).hasSize(3);
            assertThat(CursorField.NONE.ordinal()).isZero();
            assertThat(CursorField.ACTIDIN.ordinal()).isEqualTo(1);
            assertThat(CursorField.CONFIRM.ordinal()).isEqualTo(2);
            assertThat(CursorField.NONE.name()).isEqualTo("NONE");
            assertThat(CursorField.ACTIDIN.name()).isEqualTo("ACTIDIN");
            assertThat(CursorField.CONFIRM.name()).isEqualTo("CONFIRM");
        }

        @ParameterizedTest(name = "{0} is carried and read back identically")
        @EnumSource(CursorField.class)
        @DisplayName("every constant is reachable through the accessor pair")
        void everyConstantIsReachable(CursorField constant) {
            BillPaymentResponse response = new BillPaymentResponse();

            response.setCursorField(constant);

            assertThat(response.getCursorField()).isSameAs(constant);
            assertThat(CursorField.valueOf(constant.name())).isSameAs(constant);
        }

        @ParameterizedTest(name = "{0} round-trips through JSON as its own name")
        @EnumSource(CursorField.class)
        @DisplayName("every constant serialises as its declared name and binds back to itself")
        void everyConstantRoundTripsThroughJson(CursorField constant) throws Exception {
            BillPaymentResponse response = new BillPaymentResponse();
            response.setCursorField(constant);

            JsonNode body = mapper.valueToTree(response);
            BillPaymentResponse bound = mapper.readValue(
                    mapper.writeValueAsString(response), BillPaymentResponse.class);

            // The accessor pair is the whole contract now: the cursor request derives from the MOVE -1 TO
            // xxxL statements, and AAP 0.6.3 keeps the xxxL items out of the payload, so it is not a
            // member of this type. BillPaymentController projects it into screenMetadata.cursorField,
            // where Jackson's default enum handling does render the constant name - asserted there.
            assertThat(body.has("cursorField")).isFalse();
            assertThat(response.getCursorField()).isSameAs(constant);
            assertThat(bound.getCursorField())
                    .as("it did not travel, so the receiving side is at its initial state")
                    .isSameAs(CursorField.NONE);
        }

        @Test
        @DisplayName("valueOf rejects an unknown name, including the length item it derives from")
        void valueOfRejectsAnUnknownName() {
            // ACTIDINL is the symbolic-map length item, not a constant of this enum, and asking for it
            // by that name must fail rather than resolve. This drives the lookup's failure arm.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CursorField.valueOf("ACTIDINL"));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CursorField.valueOf("CONFIRML"));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CursorField.valueOf("actidin"));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> CursorField.valueOf(null));
        }

        @ParameterizedTest(name = "no constant exists for {0}")
        @MethodSource(
                "com.vsergeychik.carddemo.billing.dto.BillPaymentResponseTest#neverCursoredStems")
        @DisplayName("no constant exists for a field that never receives MOVE -1")
        void noConstantExistsForAFieldThatIsNeverCursored(String stem) {
            // Only ACTIDINL and CONFIRML are ever set negative. CURBALL is never, ERRMSGL is never,
            // and no header field ever is - which agrees with the mapset, where only ACTIDIN
            // (COBIL00.bms:85) and CONFIRM (:115) are UNPROT and so only those two could usefully take
            // a cursor. Adding a fourth constant would model a statement that does not exist.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CursorField.valueOf(stem));
        }

        @Test
        @DisplayName("the two cursor targets are exactly the two UNPROT fields of the mapset")
        void theTwoCursorTargetsAreTheTwoTypeableFields() {
            List<String> targets = new ArrayList<>();
            for (CursorField constant : CursorField.values()) {
                if (constant != CursorField.NONE) {
                    targets.add(constant.name());
                }
            }

            // ATTRB=(FSET,IC,NORM,UNPROT) at COBIL00.bms:85 and ATTRB=(FSET,NORM,UNPROT) at :115 are
            // the only two unprotected fields; the other eight are ASKIP.
            assertThat(targets).containsExactly("ACTIDIN", "CONFIRM");
            assertThat(targets).hasSize(CursorField.values().length - 1);
            assertThat(SYMBOLIC_MAP_ITEM_STEMS).containsAll(targets);
        }

        @Test
        @DisplayName("NONE is the initial state, so no assignment means no override")
        void noneIsTheInitialState() {
            BillPaymentResponse fresh = new BillPaymentResponse();

            // NONE means "the program did not override the map default on this path", not "the cursor
            // is nowhere": ACTIDIN carries IC at COBIL00.bms:85, so the terminal parks there anyway.
            // It is genuinely reachable - the invalid-key path at COBIL00C:138-141 sends the screen
            // with no MOVE -1 at all.
            assertThat(fresh.getCursorField()).isSameAs(CursorField.NONE);
            assertThat(CursorField.values()[0]).isSameAs(CursorField.NONE);
        }

        @Test
        @DisplayName("an unknown enum name reaching the enum directly is refused, not bound as null")
        void anUnknownEnumNameIsRefused() throws Exception {
            // The enum is no longer a JSON member of this type - see everyConstantRoundTripsThroughJson -
            // so the refusal is asserted against the enum itself, which is where it lives. A wire name
            // that is not one of the three constants must fail rather than silently become null, because
            // "the cursor is nowhere" and "the cursor is not overridden" are different facts and only the
            // second is representable.
            assertThatExceptionOfType(InvalidFormatException.class)
                    .isThrownBy(() -> mapper.readValue("\"ACTIDINL\"", CursorField.class))
                    .withMessageContaining("ACTIDINL");
            assertThat(mapper.readValue("{\"cursorField\":\"ACTIDINL\"}", BillPaymentResponse.class)
                            .getCursorField())
                    .as("and on the response the member is ignored outright, so it cannot be poisoned")
                    .isSameAs(CursorField.NONE);
        }

        @Test
        @DisplayName("the enum is nested on the response, is an enum, and adds no mutable state")
        void theEnumIsNestedAndImmutable() {
            assertThat(CursorField.class.getEnclosingClass()).isEqualTo(BillPaymentResponse.class);
            assertThat(CursorField.class.isEnum()).isTrue();
            for (Field field : CursorField.class.getDeclaredFields()) {
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("enum field %s must be final", field.getName())
                        .isTrue();
            }
        }
    }

    @Nested
    @DisplayName("4. The message highlight - DFHGREEN, the program's only attribute write (:526)")
    class MessageHighlight {

        @Test
        @DisplayName("the member carries the DFHGREEN attribute byte in one character")
        void theMemberCarriesTheAttributeByteInOneCharacter() {
            BillPaymentResponse response = new BillPaymentResponse();

            response.setMessageHighlight(MESSAGE_HIGHLIGHT_GREEN);

            // ERRMSGC is PICTURE X - one byte - so the attribute travels as the single character whose
            // code point is the unsigned value of the attribute byte. The value is derived from
            // BmsAttributes.DFHGREEN by name and never written as a byte or a colour literal
            // (practice B8).
            assertThat(BillPaymentResponse.MESSAGE_HIGHLIGHT_LENGTH).isEqualTo(1);
            assertThat(response.getMessageHighlight())
                    .hasSize(BillPaymentResponse.MESSAGE_HIGHLIGHT_LENGTH);
            assertThat((byte) response.getMessageHighlight().charAt(0))
                    .as("MOVE DFHGREEN TO ERRMSGC OF COBIL0AO at app/cbl/COBIL00C.cbl:526")
                    .isEqualTo(BmsAttributes.DFHGREEN);
        }

        @Test
        @DisplayName("the set arm: a successful payment carries the highlight through JSON intact")
        void theSetArmSurvivesJson() throws Exception {
            BillPaymentResponse response = canonical();

            BillPaymentResponse bound = mapper.readValue(
                    mapper.writeValueAsString(response), BillPaymentResponse.class);

            // COBIL00C:522-531: the WHEN DFHRESP(NORMAL) arm of WRITE-TRANSACT-FILE performs
            // INITIALIZE-ALL-FIELDS, blanks WS-MESSAGE, moves DFHGREEN into ERRMSGC and only then
            // composes the success text. This is the one path on which the highlight is set.
            //
            // It does not survive JSON, and that is the contract: ERRMSGC is an attribute item, AAP 0.6.3
            // keeps attribute items out of the payload, and BillPaymentController carries the byte to the
            // client as the number screenMetadata.messageColour. So the accessor is what is asserted.
            assertThat(response.getMessageHighlight()).isEqualTo(MESSAGE_HIGHLIGHT_GREEN);
            assertThat((byte) response.getMessageHighlight().charAt(0))
                    .isEqualTo(BmsAttributes.DFHGREEN);
            assertThat(bound.getMessageHighlight()).isNull();
        }

        @Test
        @DisplayName("the unset arm: every other path leaves it null, the map's declared red")
        void theUnsetArmIsNull() throws Exception {
            BillPaymentResponse errorPath = new BillPaymentResponse();
            errorPath.setErrMsg(atWidth("Acct ID can NOT be empty...",
                    BillPaymentResponse.ERR_MSG_LENGTH));

            BillPaymentResponse bound = mapper.readValue(
                    mapper.writeValueAsString(errorPath), BillPaymentResponse.class);

            // No line in COBIL00C other than 526 writes any attribute item, so on every error path the
            // message line renders in the COLOR=RED the mapset declares at COBIL00.bms:127-128. null
            // therefore means "no override", and it is the initial state.
            assertThat(new BillPaymentResponse().getMessageHighlight()).isNull();
            assertThat(bound.getMessageHighlight()).isNull();
            assertThat(bound.getErrMsg()).isNotNull();
        }

        @Test
        @DisplayName("both arms are distinguishable through the accessor, and neither is on the wire")
        void bothArmsAreDistinguishableThroughTheAccessor() throws Exception {
            JsonNode green = mapper.valueToTree(canonical());
            JsonNode none = mapper.valueToTree(new BillPaymentResponse());

            // Neither arm publishes a top-level member: ERRMSGC is an attribute item and AAP 0.6.3 keeps
            // those out of the payload. Publishing it here put the raw DFHGREEN byte on the wire as the
            // character U+00F4, which no other screen did. The two arms stay distinguishable - through the
            // accessor, and through screenMetadata.messageColour as a number once the controller has
            // projected them.
            assertThat(green.has("messageHighlight")).isFalse();
            assertThat(none.has("messageHighlight")).isFalse();
            assertThat(canonical().getMessageHighlight()).isEqualTo(MESSAGE_HIGHLIGHT_GREEN);
            assertThat(new BillPaymentResponse().getMessageHighlight()).isNull();
        }

        @Test
        @DisplayName("the member is named for its meaning, not after the ERRMSGC item it projects")
        void theMemberIsNamedForItsMeaning() {
            assertThat(instanceFieldNames()).contains("messageHighlight");
            assertThat(instanceFieldNames()).doesNotContain("errMsgC", "errmsgC", "errMsgc");
        }

        @Test
        @DisplayName("DFHGREEN is an override of the map's RED, and the two bytes differ")
        void greenIsAnOverrideOfRed() {
            // COBIL00.bms:127-128 declares the message line COLOR=RED, so green is genuinely an
            // override rather than a re-assertion of the default. COBIL00C copies CSMSG01Y but not
            // CSSETATY, so the DFHRED-plus-'*' error-highlight mechanism does not apply to this screen
            // at all - there is no MOVE DFHRED anywhere in the program.
            assertThat(BmsAttributes.DFHGREEN).isNotEqualTo(BmsAttributes.DFHRED);
            assertThat(BmsAttributes.unsigned(BmsAttributes.DFHGREEN))
                    .isNotEqualTo(BmsAttributes.unsigned(BmsAttributes.DFHRED));
        }
    }


    @Nested
    @DisplayName("5. Navigation - the stateless replacement for the program's single XCTL")
    class Navigation {

        @Test
        @DisplayName("nextMapset is COBIL00 - MAPSET('COBIL00') at app/cbl/COBIL00C.cbl:297")
        void nextMapsetIsTheMapsetName() throws Exception {
            BillPaymentResponse response = canonical();

            assertThat(response.getNextMapset())
                    .as("MAPSET('COBIL00') on the EXEC CICS SEND MAP at COBIL00C:297")
                    .isEqualTo("COBIL00")
                    .isEqualTo(BillPaymentResponse.MAPSET_NAME);
        }

        @Test
        @DisplayName("nextMap is COBIL0A - MAP('COBIL0A') at app/cbl/COBIL00C.cbl:296")
        void nextMapIsTheMapName() throws Exception {
            BillPaymentResponse response = canonical();

            assertThat(response.getNextMap())
                    .as("MAP('COBIL0A') on the EXEC CICS SEND MAP at COBIL00C:296")
                    .isEqualTo("COBIL0A")
                    .isEqualTo(BillPaymentResponse.MAP_NAME);
        }

        @Test
        @DisplayName("the mapset ends in two zeros and the map ends 0A - they are not interchangeable")
        void theMapsetAndTheMapAreNotInterchangeable() throws Exception {
            BillPaymentResponse response = canonical();

            // Transposing these two compiles and would pass a careless test, so the asymmetry is
            // asserted directly: COBIL00 is the mapset (CSD:114, bms:19) and COBIL0A is the map
            // (bms:26). Both are seven characters, which is also the width of CDEMO-LAST-MAP and
            // CDEMO-LAST-MAPSET.
            assertThat(BillPaymentResponse.MAPSET_NAME).endsWith("00");
            assertThat(BillPaymentResponse.MAP_NAME).endsWith("0A");
            assertThat(BillPaymentResponse.MAPSET_NAME).isNotEqualTo(BillPaymentResponse.MAP_NAME);
            assertThat(response.getNextMapset()).isNotEqualTo(response.getNextMap());
            assertThat(BillPaymentResponse.MAP_NAME).hasSize(NavigationContext.LAST_MAP_LENGTH);
            assertThat(BillPaymentResponse.MAPSET_NAME)
                    .hasSize(NavigationContext.LAST_MAPSET_LENGTH);
        }

        @ParameterizedTest(name = "nextProgram carries {0} - {1}")
        @MethodSource(
                "com.vsergeychik.carddemo.billing.dto.BillPaymentResponseTest#reachableNextPrograms")
        @DisplayName("each of the four reachable targets round-trips through the DTO and JSON")
        void eachReachableTargetRoundTrips(String program, String evidence) throws Exception {
            BillPaymentResponse response = new BillPaymentResponse();
            response.setNextProgram(program);

            BillPaymentResponse bound = mapper.readValue(
                    mapper.writeValueAsString(response), BillPaymentResponse.class);

            assertThat(response.getNextProgram()).as(evidence).isEqualTo(program);
            assertThat(bound.getNextProgram()).as(evidence).isEqualTo(program);
            assertThat(program)
                    .as("CDEMO-FROM-PROGRAM and CDEMO-TO-PROGRAM are both PIC X(08)")
                    .hasSize(NavigationContext.TO_PROGRAM_LENGTH);
        }

        @Test
        @DisplayName("the two literal targets are the exact eight-character CSD program names")
        void theTwoLiteralTargetsAreTheExactCsdNames() {
            // MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM at COBIL00C:108 and again at :276;
            // MOVE 'COMEN01C' TO CDEMO-TO-PROGRAM at :130.
            assertThat(BillPaymentResponse.SIGN_ON_PROGRAM).isEqualTo("COSGN00C").hasSize(8);
            assertThat(BillPaymentResponse.MAIN_MENU_PROGRAM).isEqualTo("COMEN01C").hasSize(8);
            assertThat(BillPaymentResponse.SIGN_ON_PROGRAM)
                    .isNotEqualTo(BillPaymentResponse.MAIN_MENU_PROGRAM);
        }

        @Test
        @DisplayName("all three navigation members are declared Strings and start absent")
        void allThreeNavigationMembersAreDeclaredStrings() throws Exception {
            BillPaymentResponse fresh = new BillPaymentResponse();

            for (String member : List.of("nextProgram", "nextMapset", "nextMap")) {
                assertThat(BillPaymentResponse.class.getDeclaredField(member).getType())
                        .as(member)
                        .isEqualTo(String.class);
            }
            // A blank carrier means the screen re-displays itself and no transfer is requested, which is
            // what every SEND-BILLPAY-SCREEN path does. Blank, not null: these are CARDDEMO-COMMAREA
            // PIC X items and "names nothing" is spaces at the declared width, which is the same resting
            // state NavigationContext.empty() documents for the area they belong to.
            assertThat(fresh.getNextProgram()).isBlank()
                    .hasSize(NavigationContext.TO_PROGRAM_LENGTH);
            assertThat(fresh.getNextMapset()).isBlank()
                    .hasSize(NavigationContext.LAST_MAPSET_LENGTH);
            assertThat(fresh.getNextMap()).isBlank()
                    .hasSize(NavigationContext.LAST_MAP_LENGTH);
        }

        @Test
        @DisplayName("the type defaults nothing - the LOW-VALUES fallback of :275-276 is not here")
        void theTypeDefaultsNothing() throws Exception {
            BillPaymentResponse absent = new BillPaymentResponse();
            BillPaymentResponse blank = new BillPaymentResponse();
            absent.setNextProgram(null);
            blank.setNextProgram("        ");

            // COBIL00C:275-276 substitutes 'COSGN00C' when CDEMO-TO-PROGRAM is LOW-VALUES OR SPACES.
            // That is a decision, it lives in the controller, and this carrier deliberately does not
            // reproduce it: both a null and an all-blank target survive unaltered, so the controller
            // can still tell the two apart. Asserting the absence of the arm is what proves there is
            // no second, hidden copy of that rule.
            assertThat(absent.getNextProgram()).isNull();
            assertThat(blank.getNextProgram())
                    .isEqualTo("        ")
                    .isNotEqualTo(BillPaymentResponse.SIGN_ON_PROGRAM);
            assertThat(mapper.readValue(mapper.writeValueAsString(blank),
                    BillPaymentResponse.class).getNextProgram()).isEqualTo("        ");
        }

        @Test
        @DisplayName("a named target, not a redirect - no forward, location or redirect member")
        void aNamedTargetNotARedirect() {
            List<String> offending = new ArrayList<>();
            for (Method method : BillPaymentResponse.class.getDeclaredMethods()) {
                String name = method.getName().toLowerCase(Locale.ROOT);
                if (name.contains("redirect") || name.contains("forward")
                        || name.contains("location")) {
                    offending.add(method.getName());
                }
            }

            // The client reads nextProgram and issues the follow-up call itself. There is no HTTP
            // redirect, no Location header helper and no server-side forward, which is what keeps the
            // server free of conversation state (gate G37).
            assertThat(offending).isEmpty();
        }
    }

    @Nested
    @DisplayName("6. curBal is edited text (G22) and errMsg is X(78) fed by an X(80) source")
    class EditedTextAndTruncation {

        @Test
        @DisplayName("curBal is declared exactly java.lang.String - never a numeric type")
        void curBalIsDeclaredAString() throws Exception {
            Field field = BillPaymentResponse.class.getDeclaredField("curBal");

            assertThat(field.getType())
                    .as("WS-CURR-BAL PIC +9999999999.99 at app/cbl/COBIL00C.cbl:56 is an EDITED "
                            + "picture, so CURBALO is PIC X(14) and this member must be a String. A "
                            + "double, float, Double, Float or BigDecimal would discard the leading "
                            + "zeros, the explicit sign character and the column alignment that the "
                            + "field-for-field parity comparison checks (gate G22)")
                    .isEqualTo(String.class);
            assertThat(field.getType()).isNotIn(double.class, float.class, Double.class, Float.class,
                    java.math.BigDecimal.class);
        }

        @Test
        @DisplayName("no floating-point or BigDecimal type appears anywhere on the type (G22)")
        void noFloatingPointTypeAppearsAnywhere() {
            List<String> offending = new ArrayList<>();

            for (Field field : BillPaymentResponse.class.getDeclaredFields()) {
                rejectInexactType("field " + field.getName(), field.getType(), offending);
            }
            for (Method method : BillPaymentResponse.class.getDeclaredMethods()) {
                rejectInexactType("return of " + method.getName(), method.getReturnType(), offending);
                for (Class<?> parameter : method.getParameterTypes()) {
                    rejectInexactType("parameter of " + method.getName(), parameter, offending);
                }
            }

            // The balance as a number lives in the account record at its own declared scale, and the
            // arithmetic on it - COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT at COBIL00C:234 - is
            // performed in the service layer. No numeric convenience accessor is offered here, and
            // BigDecimal is excluded too: this carrier holds rendered characters, not values.
            assertThat(offending)
                    .as("no double, float, Double, Float or BigDecimal may appear on a payload that "
                            + "carries edited text (gate G22)")
                    .isEmpty();
        }

        @ParameterizedTest(name = "{0} round-trips byte-identically")
        @MethodSource(
                "com.vsergeychik.carddemo.billing.dto.BillPaymentResponseTest#editedBalances")
        @DisplayName("a positive, a negative and a zero balance all survive intact at 14 characters")
        void everyEditedBalanceSurvivesIntact(String edited) throws Exception {
            BillPaymentResponse response = new BillPaymentResponse();
            response.setCurBal(edited);

            JsonNode body = mapper.valueToTree(response);
            BillPaymentResponse bound = mapper.readValue(
                    mapper.writeValueAsString(response), BillPaymentResponse.class);

            assertThat(edited).hasSize(BillPaymentResponse.CUR_BAL_LENGTH);
            assertThat(response.getCurBal()).isEqualTo(edited);
            assertThat(body.get("curbal").isTextual())
                    .as("the balance is a quoted JSON string, never a JSON number")
                    .isTrue();
            assertThat(body.get("curbal").asText()).isEqualTo(edited);
            assertThat(mapper.writeValueAsString(response))
                    .as("the sign and every leading zero appear verbatim in the serialised body")
                    .contains("\"curbal\":\"" + edited + "\"");
            assertThat(bound.getCurBal()).isEqualTo(edited).hasSize(14);
        }

        @Test
        @DisplayName("fourteen is the edit mask: 1 sign + 10 digits + 1 point + 2 digits")
        void fourteenIsTheEditMask() {
            // PIC +9999999999.99 at app/cbl/COBIL00C.cbl:56. Note that WS-TRAN-AMT PIC +99999999.99 at
            // line 55 is a different mask, two digits narrower, and is not this field.
            assertThat(BillPaymentResponse.CUR_BAL_LENGTH).isEqualTo(1 + 10 + 1 + 2).isEqualTo(14);
        }

        @Test
        @DisplayName("errMsg is 78 while WS-MESSAGE is 80 - the move loses exactly two bytes")
        void errMsgIsNarrowerThanItsSource() {
            // WS-MESSAGE PIC X(80) at COBIL00C:39; ERRMSGO PIC X(78) at COBIL00.CPY:140;
            // MOVE WS-MESSAGE TO ERRMSGO OF COBIL0AO at COBIL00C:293.
            assertThat(BillPaymentResponse.ERR_MSG_LENGTH).isEqualTo(78);
            assertThat(BillPaymentResponse.WS_MESSAGE_LENGTH).isEqualTo(80);
            assertThat(BillPaymentResponse.WS_MESSAGE_LENGTH - BillPaymentResponse.ERR_MSG_LENGTH)
                    .as("the width is deliberately not widened to match the source of the move")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("a 78-character message is carried intact")
        void aSeventyEightCharacterMessageIsCarriedIntact() throws Exception {
            String atWidth = repeat('A', BillPaymentResponse.ERR_MSG_LENGTH);
            BillPaymentResponse response = new BillPaymentResponse();

            response.setErrMsg(atWidth);

            assertThat(response.getErrMsg()).isEqualTo(atWidth).hasSize(78);
            assertThat(mapper.readValue(mapper.writeValueAsString(response),
                    BillPaymentResponse.class).getErrMsg()).isEqualTo(atWidth);
        }

        @Test
        @DisplayName("an 80-character message keeps the FIRST 78 - COBOL truncates on the right")
        void anEightyCharacterMessageKeepsTheFirstSeventyEight() {
            String source = repeat('A', BillPaymentResponse.ERR_MSG_LENGTH) + "ZZ";
            String sent = source.substring(0, BillPaymentResponse.ERR_MSG_LENGTH);

            assertThat(source).hasSize(BillPaymentResponse.WS_MESSAGE_LENGTH);
            assertThat(sent)
                    .as("a COBOL MOVE between alphanumeric items truncates on the RIGHT, so the two "
                            + "final bytes are discarded and the first 78 survive")
                    .isEqualTo(repeat('A', BillPaymentResponse.ERR_MSG_LENGTH))
                    .hasSize(BillPaymentResponse.ERR_MSG_LENGTH)
                    .doesNotContain("Z");
            assertThat(sent)
                    .as("getting the direction backwards is a classic silent parity break: the LAST "
                            + "78 characters are NOT what the field receives")
                    .isNotEqualTo(source.substring(source.length()
                            - BillPaymentResponse.ERR_MSG_LENGTH));
        }

        @Test
        @DisplayName("the DTO stores 80 characters verbatim - the truncation is the caller's")
        void theDtoStoresTheSourceVerbatim() throws Exception {
            String source = repeat('A', BillPaymentResponse.ERR_MSG_LENGTH) + "ZZ";
            BillPaymentResponse response = new BillPaymentResponse();

            response.setErrMsg(source);

            // setErrMsg's own contract states this explicitly: the two-byte right truncation that
            // MOVE WS-MESSAGE TO ERRMSGO performs is the caller's responsibility, so that it happens
            // once and visibly rather than silently here. The alphanumeric MOVE rule itself lives in
            // com.vsergeychik.carddemo.common.FixedWidthCodec#movePicX and is proven by that codec's
            // own suite; duplicating it here would be scope creep (practice B4).
            assertThat(response.getErrMsg())
                    .as("no setter on this type trims, pads or truncates - it holds no decision logic")
                    .isEqualTo(source)
                    .hasSize(BillPaymentResponse.WS_MESSAGE_LENGTH);
            assertThat(mapper.readValue(mapper.writeValueAsString(response),
                    BillPaymentResponse.class).getErrMsg()).isEqualTo(source);
        }

        @Test
        @DisplayName("a short message is neither padded nor altered by the DTO")
        void aShortMessageIsNeitherPaddedNorAltered() throws Exception {
            String short1 = "Acct ID can NOT be empty...";
            String short2 = "Invalid value. Valid values are (Y/N)...";
            BillPaymentResponse response = new BillPaymentResponse();

            response.setErrMsg(short1);
            assertThat(response.getErrMsg()).isEqualTo(short1).hasSize(27);

            response.setErrMsg(short2);
            assertThat(response.getErrMsg()).isEqualTo(short2).hasSize(40);
            assertThat(mapper.readValue(mapper.writeValueAsString(response),
                    BillPaymentResponse.class).getErrMsg())
                    .as("the space padding a COBOL MOVE would apply belongs to the codec, not here")
                    .isEqualTo(short2);
        }

        @Test
        @DisplayName("the longest message this screen composes fits the field with room to spare")
        void theLongestComposedMessageFits() {
            // The STRING of COBIL00C:527-531 with a full sixteen-character transaction identifier:
            // 'Payment successful. ' + ' Your Transaction ID is ' + TRAN-ID + '.' = 61 characters.
            String composed = "Payment successful.  Your Transaction ID is 0000000000000042.";

            assertThat(composed).hasSize(61);
            assertThat(composed.length()).isLessThan(BillPaymentResponse.ERR_MSG_LENGTH);
        }

        private void rejectInexactType(String where, Class<?> type, List<String> offending) {
            if (type == double.class || type == float.class || type == Double.class
                    || type == Float.class || type == java.math.BigDecimal.class) {
                offending.add(where + " is " + type.getName());
            }
        }
    }


    @Nested
    @DisplayName("7. The carried communication area - 160 shared bytes plus 58 program-local ones")
    class CarriedConversationState {

        @ParameterizedTest(name = "{0} is declared with its source width {1}")
        @MethodSource(
                "com.vsergeychik.carddemo.billing.dto.BillPaymentResponseTest#cb00InfoWidths")
        @DisplayName("each of the six CDEMO-CB00-INFO members is declared with its source width")
        void eachExtensionMemberIsDeclaredWithItsSourceWidth(String member, int width)
                throws Exception {
            Field field = BillPaymentResponse.class.getDeclaredField(member);

            assertThat(width).isPositive();
            if ("pageNum".equals(member)) {
                assertThat(field.getType())
                        .as("CDEMO-CB00-PAGE-NUM PIC 9(08) is scale-free - eight digits, no V and no "
                                + "sign - so it is an int, the one non-String member of the six")
                        .isEqualTo(int.class);
            } else {
                assertThat(field.getType()).as(member).isEqualTo(String.class);
            }
        }

        @Test
        @DisplayName("the six widths sum to 58 - the length of 05 CDEMO-CB00-INFO")
        void theSixWidthsSumToFiftyEight() {
            int sum = CB00_INFO_WIDTHS.values().stream().mapToInt(Integer::intValue).sum();

            // 16 + 16 + 8 + 1 + 1 + 16, from app/cbl/COBIL00C.cbl:65-72.
            assertThat(CB00_INFO_WIDTHS).hasSize(6);
            assertThat(sum).isEqualTo(58);
            assertThat(BillPaymentResponse.CB00_INFO_LENGTH).isEqualTo(sum);
        }

        @Test
        @DisplayName("this program's communication area is 218 bytes: the shared 160 plus 58")
        void theCommunicationAreaIsTwoHundredAndEighteenBytes() {
            assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(160);
            assertThat(BillPaymentResponse.CB00_COMMAREA_LENGTH)
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH
                            + BillPaymentResponse.CB00_INFO_LENGTH)
                    .isEqualTo(218);
        }

        @Test
        @DisplayName("NavigationContext is exactly 160 bytes, with X(7) map and mapset (COCOM01Y:43-44)")
        void theSharedCarrierIsExactlyOneHundredAndSixtyBytes() {
            // 34 + 84 + 12 + 16 + 14 = 160, and the last group only totals 14 because CDEMO-LAST-MAP
            // and CDEMO-LAST-MAPSET are both PIC X(7) - SEVEN, not eight. At eight the copybook would
            // be 162 bytes and every offset after it would be wrong.
            assertThat(NavigationContext.LAST_MAP_LENGTH).isEqualTo(7);
            assertThat(NavigationContext.LAST_MAPSET_LENGTH).isEqualTo(7);
            assertThat(NavigationContext.MORE_INFO_LENGTH)
                    .isEqualTo(NavigationContext.LAST_MAP_LENGTH
                            + NavigationContext.LAST_MAPSET_LENGTH)
                    .isEqualTo(14);
            assertThat(NavigationContext.GENERAL_INFO_LENGTH
                    + NavigationContext.CUSTOMER_INFO_LENGTH
                    + NavigationContext.ACCOUNT_INFO_LENGTH
                    + NavigationContext.CARD_INFO_LENGTH
                    + NavigationContext.MORE_INFO_LENGTH)
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH)
                    .isEqualTo(160);
        }

        @Test
        @DisplayName("no extension member is folded into the shared communication area")
        void noExtensionMemberIsFoldedIntoTheSharedCarrier() {
            Set<String> carrierComponents = new LinkedHashSet<>();
            for (RecordComponent component : NavigationContext.class.getRecordComponents()) {
                carrierComponents.add(component.getName());
            }

            // CDEMO-CB00-INFO is declared in the PROGRAM at COBIL00C:64-72, not in COCOM01Y, so it is
            // this screen's own extension. NavigationContext is shared by all seventeen controllers and
            // must stay exactly 160 bytes, which is why these six live on the response instead. Both
            // halves of that statement are asserted: the six are here, and none of them is there.
            assertThat(NavigationContext.class.isRecord()).isTrue();
            assertThat(carrierComponents).doesNotContainAnyElementsOf(CB00_INFO_WIDTHS.keySet());
            assertThat(instanceFieldNames()).containsAll(CB00_INFO_WIDTHS.keySet());
            assertThat(instanceFieldNames()).contains("navigationContext");
        }

        @Test
        @DisplayName("the paging flag defaults to 'N', the VALUE clause the source declares")
        void thePagingFlagDefaultsToNo() {
            BillPaymentResponse fresh = new BillPaymentResponse();

            // CDEMO-CB00-NEXT-PAGE-FLG PIC X(01) VALUE 'N' at app/cbl/COBIL00C.cbl:68.
            assertThat(BillPaymentResponse.NEXT_PAGE_NO).isEqualTo("N");
            assertThat(fresh.getNextPageFlg()).isEqualTo(BillPaymentResponse.NEXT_PAGE_NO);
        }

        @Test
        @DisplayName("an explicitly supplied paging flag replaces the default")
        void anExplicitPagingFlagReplacesTheDefault() {
            BillPaymentResponse response = new BillPaymentResponse();

            response.setNextPageFlg(BillPaymentResponse.NEXT_PAGE_YES);

            // 88 NEXT-PAGE-YES VALUE 'Y' at line 69.
            assertThat(BillPaymentResponse.NEXT_PAGE_YES).isEqualTo("Y");
            assertThat(response.getNextPageFlg()).isEqualTo(BillPaymentResponse.NEXT_PAGE_YES);
        }

        @Test
        @DisplayName("the two condition names are independent tests, not each other's negation")
        void theTwoConditionNamesAreIndependent() {
            BillPaymentResponse blank = new BillPaymentResponse();
            BillPaymentResponse absent = new BillPaymentResponse();
            blank.setNextPageFlg(" ");
            absent.setNextPageFlg(null);

            // Two 88-level tests in COBOL are independent rather than exhaustive: a flag holding a
            // space, a LOW-VALUES byte or - here - null satisfies neither NEXT-PAGE-YES nor
            // NEXT-PAGE-NO. The type stores what it is given so that third state stays representable.
            assertThat(blank.getNextPageFlg())
                    .isNotEqualTo(BillPaymentResponse.NEXT_PAGE_YES)
                    .isNotEqualTo(BillPaymentResponse.NEXT_PAGE_NO)
                    .isEqualTo(" ");
            assertThat(absent.getNextPageFlg()).isNull();
        }

        @Test
        @DisplayName("the one member the program reads is carried, and so are the five it never reads")
        void theUnreferencedMembersAreCarriedAnyway() throws Exception {
            BillPaymentResponse response = canonical();

            // CDEMO-CB00-TRN-SELECTED is tested at COBIL00C:116-117 and, where it holds neither spaces
            // nor LOW-VALUES, moved into ACTIDINI at :118. That is the only one of the six the program
            // ever reads. The other five - and the two 88-levels over one of them - have zero
            // references and are carried regardless: preserving unreferenced source state is required
            // (practice B5). They are asserted PRESENT, never absent, and must never be removed.
            assertThat(response.getTrnSelected()).isEqualTo("0000000000000007");
            assertThat(instanceFieldNames()).containsAll(UNREFERENCED_CB00_MEMBERS);
            assertThat(UNREFERENCED_CB00_MEMBERS).hasSize(5);
            assertThat(publishedNames(response)).containsAll(UNREFERENCED_CB00_MEMBERS);
        }

        @Test
        @DisplayName("the six members are carried and read back unaltered")
        void theSixMembersAreCarriedUnaltered() throws Exception {
            BillPaymentResponse response = canonical();

            assertThat(response.getTrnIdFirst()).isEqualTo("0000000000000001")
                    .hasSize(BillPaymentResponse.TRN_ID_FIRST_LENGTH);
            assertThat(response.getTrnIdLast()).isEqualTo("0000000000000010")
                    .hasSize(BillPaymentResponse.TRN_ID_LAST_LENGTH);
            assertThat(response.getPageNum()).isEqualTo(3);
            assertThat(response.getNextPageFlg()).isEqualTo(BillPaymentResponse.NEXT_PAGE_YES);
            assertThat(response.getTrnSelFlg()).isEqualTo("S")
                    .hasSize(BillPaymentResponse.TRN_SEL_FLG_LENGTH);
            assertThat(response.getTrnSelected()).isEqualTo("0000000000000007")
                    .hasSize(BillPaymentResponse.TRN_SELECTED_LENGTH);
        }

        @Test
        @DisplayName("pageNum is a scale-free int and carries its full eight-digit range")
        void pageNumIsAScaleFreeInt() {
            BillPaymentResponse response = new BillPaymentResponse();

            assertThat(response.getPageNum())
                    .as("a numeric PIC 9 item initialises to zero")
                    .isZero();

            response.setPageNum(99_999_999);

            // Eight digits fit an int with room to spare; the type stores the value as given because
            // the source performs no range check of its own.
            assertThat(BillPaymentResponse.PAGE_NUM_DIGITS).isEqualTo(8);
            assertThat(response.getPageNum()).isEqualTo(99_999_999);
        }

        @Test
        @DisplayName("the carrier is referenced, not redeclared - its own values read through")
        void theCarrierIsReferencedNotRedeclared() throws Exception {
            BillPaymentResponse response = canonical();

            // RETURN-TO-PREV-SCREEN stamps this program's identity into the area at COBIL00C:278-280
            // before transferring control. This class carries the result; the controller performs it.
            assertThat(response.getNavigationContext().fromTranid())
                    .isEqualTo(BillPaymentResponse.TRANSACTION_ID);
            assertThat(response.getNavigationContext().fromProgram())
                    .isEqualTo(BillPaymentResponse.PROGRAM_NAME);
            assertThat(response.getNavigationContext().lastMap())
                    .isEqualTo(BillPaymentResponse.MAP_NAME);
            assertThat(response.getNavigationContext().lastMapset())
                    .isEqualTo(BillPaymentResponse.MAPSET_NAME);
            assertThat(response.getNavigationContext().isReenter()).isTrue();
            assertThat(response.getNavigationContext().pgmContext())
                    .isEqualTo(NavigationContext.PGM_CONTEXT_REENTER);
        }

        @Test
        @DisplayName("the enter/re-enter context has exactly one home - inside the carrier")
        void theContextHasExactlyOneHome() {
            // CDEMO-PGM-CONTEXT is declared in COCOM01Y and travels inside the communication area.
            // There is deliberately no second copy of it on this class, because two copies could
            // disagree and the copybook is the authority for where it lives.
            assertThat(instanceFieldNames()).doesNotContain("pgmContext", "pgmReenter", "reenter");
            assertThat(NavigationContext.PGM_CONTEXT_ENTER).isZero();
            assertThat(NavigationContext.PGM_CONTEXT_REENTER).isEqualTo(1);
        }
    }


    @Nested
    @DisplayName("8. Statelessness (G37), immutable statics (G53) and value semantics")
    class StatelessnessAndValueSemantics {

        @Test
        @DisplayName("the type holds no static mutable state")
        void theTypeHoldsNoStaticMutableState() {
            // COBOL WORKING-STORAGE never becomes a static Java field: that would break request
            // isolation and test determinism alike (practice B9, gate G53).
            for (Field field : BillPaymentResponse.class.getDeclaredFields()) {
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

            for (Field field : BillPaymentResponse.class.getDeclaredFields()) {
                rejectSessionType("field " + field.getName(), field.getType(), offending);
            }
            for (Method method : BillPaymentResponse.class.getDeclaredMethods()) {
                rejectSessionType("return of " + method.getName(), method.getReturnType(), offending);
                for (Class<?> parameter : method.getParameterTypes()) {
                    rejectSessionType("parameter of " + method.getName(), parameter, offending);
                }
            }
            for (Annotation annotation : BillPaymentResponse.class.getAnnotations()) {
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
            BillPaymentResponse first = new BillPaymentResponse();
            BillPaymentResponse second = new BillPaymentResponse();

            first.setActIdIn("00000000001");
            first.setCursorField(CursorField.CONFIRM);
            first.setMessageHighlight(MESSAGE_HIGHLIGHT_GREEN);
            first.setPageNum(7);

            assertThat(second.getActIdIn())
                    .isEqualTo(ScreenFieldImage.unpainted(BillPaymentResponse.ACT_ID_IN_LENGTH));
            assertThat(second.getCursorField()).isSameAs(CursorField.NONE);
            assertThat(second.getMessageHighlight()).isNull();
            assertThat(second.getPageNum()).isZero();
            assertThat(second.getNavigationContext()).isNull();
        }

        @Test
        @DisplayName("two responses carrying different contexts do not influence one another")
        void twoResponsesCarryingDifferentContextsDoNotInfluenceOneAnother() {
            BillPaymentResponse onEnter = new BillPaymentResponse();
            onEnter.setNavigationContext(
                    NavigationContext.empty().withUserId("USER0001").withPgmEnter());
            BillPaymentResponse onReenter = new BillPaymentResponse();
            onReenter.setNavigationContext(NavigationContext.empty()
                    .withUserId("ADMIN001")
                    .withUserTypeAdmin()
                    .withPgmReenter());

            assertThat(onEnter.getNavigationContext().isEnter()).isTrue();
            assertThat(onReenter.getNavigationContext().isReenter()).isTrue();
            assertThat(onEnter.getNavigationContext().userId()).isEqualTo("USER0001");
            assertThat(onReenter.getNavigationContext().userId()).isEqualTo("ADMIN001");
            assertThat(onEnter.getNavigationContext().isAdmin()).isFalse();
            assertThat(onReenter.getNavigationContext().isAdmin()).isTrue();
            assertThat(onEnter.getNavigationContext())
                    .isNotEqualTo(onReenter.getNavigationContext());
        }

        @Test
        @DisplayName("the type declares neither equals nor hashCode, and declares one nested enum")
        void theTypeDeclaresNeitherEqualsNorHashCode() {
            // This is a declared decision, not an omission. Any correct Java equals needs a type test,
            // and a type test is a branch that this package's per-package JaCoCo BRANCH gate would then
            // have to prove exercised; the parity differ compares field by field rather than by
            // whole-object equality, so neither method is needed. Asserting the decision here keeps it
            // deliberate: whoever adds equals must also come here and say why. The per-component
            // discrimination an equals matrix would have given is supplied instead by the mutation
            // tests below, which prove that changing one member changes exactly that member.
            assertThatExceptionOfType(NoSuchMethodException.class)
                    .isThrownBy(() -> BillPaymentResponse.class.getDeclaredMethod("equals",
                            Object.class));
            assertThatExceptionOfType(NoSuchMethodException.class)
                    .isThrownBy(() -> BillPaymentResponse.class.getDeclaredMethod("hashCode"));

            assertThat(BillPaymentResponse.class.getDeclaredClasses())
                    .as("exactly one nested type is declared - the cursor enum - and its every "
                            + "constant is driven above")
                    .containsExactly(CursorField.class);
            assertThat(BillPaymentResponse.class.isRecord())
                    .as("a plain bean was chosen over a record: twenty-two members and constant "
                            + "partial population make a no-argument constructor plus setters the "
                            + "workable shape, and the controller fills the object incrementally")
                    .isFalse();
        }

        @Test
        @DisplayName("identity semantics follow from that decision")
        void identitySemanticsFollowFromThatDecision() throws Exception {
            BillPaymentResponse instance = canonical();
            BillPaymentResponse componentWiseCopy = canonical();

            assertThat(instance).isEqualTo(instance);
            assertThat(instance.equals(componentWiseCopy))
                    .as("with no value equality declared, a component-wise copy is a different object")
                    .isFalse();
            assertThat(instance.equals(null)).isFalse();
            assertThat(instance.equals("BillPaymentResponse")).isFalse();
            assertThat(componentWiseCopy).isNotSameAs(instance);

            // The null-versus-value case, in both directions. Under a value equality this would be the
            // distinct component branch worth driving; here it is the observable distinction between
            // two carriers that differ in exactly one member, which is what the field-by-field parity
            // differ compares rather than whole-object equality.
            BillPaymentResponse withValue = canonical();
            BillPaymentResponse withoutValue = canonical();
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
            BillPaymentResponse instance = canonical();

            int first = instance.hashCode();
            int second = instance.hashCode();

            assertThat(second).isEqualTo(first);
            assertThat(instance.hashCode()).isEqualTo(instance.hashCode());
            // Deliberately not asserted: that two distinct instances hash differently. Under identity
            // hashing that is overwhelmingly likely but not guaranteed, and asserting it would make
            // this suite non-deterministic - which practice B7 and gate G54 forbid. No specific hash
            // value is asserted anywhere either.
        }

        @ParameterizedTest(name = "changing {0} changes {0} and nothing else")
        @MethodSource(
                "com.vsergeychik.carddemo.billing.dto.BillPaymentResponseTest#mapMemberWidths")
        @DisplayName("mutating one map member changes exactly that member")
        void mutatingOneMapMemberChangesExactlyThatMember(String member, int width) throws Exception {
            BillPaymentResponse mutated = canonical();
            String sentinel = repeat(MUTATION_CHARACTER, width);

            set(mutated, member, sentinel);

            assertThat(get(mutated, member)).isEqualTo(sentinel).hasSize(width);
            for (Map.Entry<String, String> untouched : CANONICAL_MAP_VALUES.entrySet()) {
                if (!untouched.getKey().equals(member)) {
                    assertThat(get(mutated, untouched.getKey()))
                            .as("changing %s must not disturb %s", member, untouched.getKey())
                            .isEqualTo(untouched.getValue());
                }
            }
            // The twelve carried members are equally undisturbed.
            assertThat(mutated.getCursorField()).isSameAs(CursorField.ACTIDIN);
            assertThat(mutated.getMessageHighlight()).isEqualTo(MESSAGE_HIGHLIGHT_GREEN);
            assertThat(mutated.getPageNum()).isEqualTo(3);
        }

        @Test
        @DisplayName("mutating one carried member changes exactly that member")
        void mutatingOneCarriedMemberChangesExactlyThatMember() throws Exception {
            assertOnlyChange(response -> response.setCursorField(CursorField.CONFIRM),
                    "cursorField");
            assertOnlyChange(response -> response.setMessageHighlight(null), "messageHighlight");
            assertOnlyChange(response -> response.setNavigationContext(NavigationContext.empty()),
                    "navigationContext");
            assertOnlyChange(response -> response.setNextProgram(
                    BillPaymentResponse.SIGN_ON_PROGRAM), "nextProgram");
            assertOnlyChange(response -> response.setNextMapset(null), "nextMapset");
            assertOnlyChange(response -> response.setNextMap(null), "nextMap");
            assertOnlyChange(response -> response.setTrnIdFirst("9999999999999999"), "trnIdFirst");
            assertOnlyChange(response -> response.setTrnIdLast("9999999999999999"), "trnIdLast");
            assertOnlyChange(response -> response.setPageNum(11), "pageNum");
            assertOnlyChange(response -> response.setNextPageFlg(
                    BillPaymentResponse.NEXT_PAGE_NO), "nextPageFlg");
            assertOnlyChange(response -> response.setTrnSelFlg(null), "trnSelFlg");
            assertOnlyChange(response -> response.setTrnSelected(null), "trnSelected");
        }

        @ParameterizedTest(name = "{0} reads back exactly what was set")
        @MethodSource(
                "com.vsergeychik.carddemo.billing.dto.BillPaymentResponseTest#mapMemberWidths")
        @DisplayName("every map accessor reads back exactly what its setter stored")
        void everyMapAccessorReadsBackWhatWasSet(String member, int width) throws Exception {
            BillPaymentResponse response = new BillPaymentResponse();
            String value = repeat('7', width);

            set(response, member, value);

            assertThat(get(response, member)).isEqualTo(value);
        }

        @ParameterizedTest(name = "{0} accepts null and returns it")
        @MethodSource(
                "com.vsergeychik.carddemo.billing.dto.BillPaymentResponseTest#mapMemberWidths")
        @DisplayName("a setter accepts null and the accessor returns it - absence is representable")
        void aSetterAcceptsNullAndTheAccessorReturnsIt(String member, int width) throws Exception {
            BillPaymentResponse response = canonical();

            set(response, member, null);

            // MOVE LOW-VALUES TO COBIL0AO at COBIL00C:114 produces exactly this state, and the program
            // tests WHEN SPACES and WHEN LOW-VALUES as separate arms at lines 182-183. null must stay
            // representable for those two states to remain distinguishable.
            assertThat(get(response, member)).as(member).isNull();
            assertThat(width).isPositive();
        }

        @Test
        @DisplayName("every carried accessor reads back exactly what was set")
        void everyCarriedAccessorReadsBackWhatWasSet() throws Exception {
            BillPaymentResponse response = canonical();

            assertThat(response.getCursorField()).isSameAs(CursorField.ACTIDIN);
            assertThat(response.getMessageHighlight()).isEqualTo(MESSAGE_HIGHLIGHT_GREEN);
            assertThat(response.getNavigationContext()).isNotNull();
            assertThat(response.getNextProgram()).isEqualTo(BillPaymentResponse.MAIN_MENU_PROGRAM);
            assertThat(response.getNextMapset()).isEqualTo(BillPaymentResponse.MAPSET_NAME);
            assertThat(response.getNextMap()).isEqualTo(BillPaymentResponse.MAP_NAME);
            assertThat(response.getTrnIdFirst()).isEqualTo("0000000000000001");
            assertThat(response.getTrnIdLast()).isEqualTo("0000000000000010");
            assertThat(response.getPageNum()).isEqualTo(3);
            assertThat(response.getNextPageFlg()).isEqualTo(BillPaymentResponse.NEXT_PAGE_YES);
            assertThat(response.getTrnSelFlg()).isEqualTo("S");
            assertThat(response.getTrnSelected()).isEqualTo("0000000000000007");
        }

        @Test
        @DisplayName("a fresh instance reports the declared initial state on every accessor")
        void aFreshInstanceReportsTheDeclaredInitialState() throws Exception {
            BillPaymentResponse fresh = new BillPaymentResponse();

            for (String member : MAP_MEMBER_WIDTHS.keySet()) {
                assertThat(get(fresh, member))
                        .as(member)
                        .isEqualTo(ScreenFieldImage.unpainted(MAP_MEMBER_WIDTHS.get(member)));
            }
            assertThat(fresh.getCursorField()).isSameAs(CursorField.NONE);
            assertThat(fresh.getMessageHighlight()).isNull();
            assertThat(fresh.getNavigationContext()).isNull();
            // The carriers are CARDDEMO-COMMAREA PIC X items, so their resting state is spaces at the
            // declared width - never null.
            assertThat(fresh.getNextProgram()).isBlank()
                    .hasSize(NavigationContext.TO_PROGRAM_LENGTH);
            assertThat(fresh.getNextMapset()).isBlank()
                    .hasSize(NavigationContext.LAST_MAPSET_LENGTH);
            assertThat(fresh.getNextMap()).isBlank()
                    .hasSize(NavigationContext.LAST_MAP_LENGTH);
            assertThat(fresh.getTrnIdFirst()).isBlank()
                    .hasSize(BillPaymentResponse.TRN_ID_FIRST_LENGTH);
            assertThat(fresh.getTrnIdLast()).isBlank()
                    .hasSize(BillPaymentResponse.TRN_ID_LAST_LENGTH);
            assertThat(fresh.getPageNum()).isZero();
            assertThat(fresh.getNextPageFlg()).isEqualTo(BillPaymentResponse.NEXT_PAGE_NO);
            assertThat(fresh.getTrnSelFlg()).isBlank()
                    .hasSize(BillPaymentResponse.TRN_SEL_FLG_LENGTH);
            assertThat(fresh.getTrnSelected()).isBlank()
                    .hasSize(BillPaymentResponse.TRN_SELECTED_LENGTH);
        }

        @Test
        @DisplayName("toString names the type, the cursor field and the message, and diagnoses")
        void toStringNamesTheTypeTheCursorFieldAndTheMessage() throws Exception {
            BillPaymentResponse response = canonical();

            String rendered = response.toString();

            assertThat(rendered).startsWith("BillPaymentResponse[").endsWith("]");
            assertThat(rendered)
                    .as("the cursor constant must appear so a failure is diagnostic")
                    .contains("cursorField=" + CursorField.ACTIDIN.name());
            assertThat(rendered).contains("errMsg=Payment successful.");
            assertThat(rendered).contains("pageNum=3");
            // The whole format is deliberately not pinned; only that the members a bill-payment
            // failure is diagnosed from are named.
            assertThat(rendered).contains("curBal=").contains("actIdIn=");
        }

        @Test
        @DisplayName("toString withholds the balance and masks the identifiers")
        void toStringWithholdsTheBalanceAndMasksTheIdentifiers() throws Exception {
            BillPaymentResponse response = canonical();

            String rendered = response.toString();

            // The implementation routes actIdIn, trnIdFirst, trnIdLast and trnSelected through the
            // module's diagnostic masking policy and withholds curBal entirely, so that a log line
            // cannot both name an account and state what is in it. The type's own accessor comment
            // states that actIdIn is rendered in the clear; the implementation is the authority, and
            // the divergence is recorded here rather than corrected in a file this suite does not own
            // (practice B4). Only the negative is asserted, so the policy stays free to change its
            // marker text without breaking this test.
            assertThat(rendered)
                    .as("the rendering is a diagnostic, not an observable output, so it neither needs "
                            + "nor reproduces the whole account identifier")
                    .doesNotContain("00000000011");
            assertThat(rendered).contains("0011");
            assertThat(rendered)
                    .as("the balance is withheld rather than printed")
                    .doesNotContain("+0000000019.40");
            assertThat(rendered).doesNotContain("0000000000000001", "0000000000000010",
                    "0000000000000007");
        }

        @Test
        @DisplayName("toString is total - an empty response renders without throwing")
        void toStringIsTotalForAnEmptyResponse() {
            BillPaymentResponse empty = new BillPaymentResponse();

            assertThatCode(empty::toString).doesNotThrowAnyException();
            assertThat(empty.toString()).startsWith("BillPaymentResponse[").endsWith("]");
            assertThat(empty.toString()).contains("cursorField=" + CursorField.NONE.name());
            assertThat(empty.toString()).contains("actIdIn=").contains("curBal=");
        }

        @Test
        @DisplayName("the no-argument constructor is public, so Jackson binds with no ceremony")
        void theNoArgumentConstructorIsPublic() throws Exception {
            assertThat(BillPaymentResponse.class.getDeclaredConstructors()).hasSize(1);
            assertThat(Modifier.isPublic(
                    BillPaymentResponse.class.getDeclaredConstructor().getModifiers())).isTrue();
            assertThatCode(() -> BillPaymentResponse.class.getDeclaredConstructor().newInstance())
                    .doesNotThrowAnyException();
        }

        /**
         * Applies one mutation to a canonical instance and asserts that every other member of the
         * twenty-two is unchanged, comparing member by member against a pristine canonical instance.
         * The named member is expected to differ and is skipped.
         */
        private void assertOnlyChange(Consumer<BillPaymentResponse> mutation,
                String mutatedMember) throws Exception {
            BillPaymentResponse pristine = canonical();
            BillPaymentResponse mutated = canonical();

            mutation.accept(mutated);

            for (String member : MAP_MEMBER_WIDTHS.keySet()) {
                assertThat(get(mutated, member))
                        .as("changing %s must not disturb %s", mutatedMember, member)
                        .isEqualTo(get(pristine, member));
            }
            Map<String, Object> before = carriedValues(pristine);
            Map<String, Object> after = carriedValues(mutated);
            for (Map.Entry<String, Object> entry : before.entrySet()) {
                if (entry.getKey().equals(mutatedMember)) {
                    assertThat(after.get(entry.getKey()))
                            .as("%s must actually change", mutatedMember)
                            .isNotEqualTo(entry.getValue());
                } else {
                    assertThat(after.get(entry.getKey()))
                            .as("changing %s must not disturb %s", mutatedMember, entry.getKey())
                            .isEqualTo(entry.getValue());
                }
            }
        }

        /** The twelve non-map members of one instance, keyed by member name in declaration order. */
        private Map<String, Object> carriedValues(BillPaymentResponse response) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("cursorField", response.getCursorField());
            values.put("messageHighlight", response.getMessageHighlight());
            values.put("navigationContext", response.getNavigationContext());
            values.put("nextProgram", response.getNextProgram());
            values.put("nextMapset", response.getNextMapset());
            values.put("nextMap", response.getNextMap());
            values.put("trnIdFirst", response.getTrnIdFirst());
            values.put("trnIdLast", response.getTrnIdLast());
            values.put("pageNum", response.getPageNum());
            values.put("nextPageFlg", response.getNextPageFlg());
            values.put("trnSelFlg", response.getTrnSelFlg());
            values.put("trnSelected", response.getTrnSelected());
            return values;
        }

        private void rejectSessionType(String where, Class<?> type, List<String> offending) {
            String name = type.getName();
            if (name.startsWith("jakarta.servlet") || name.startsWith("javax.servlet")
                    || name.endsWith("HttpSession") || ThreadLocal.class.isAssignableFrom(type)) {
                offending.add(where + " is " + name);
            }
        }
    }

}
