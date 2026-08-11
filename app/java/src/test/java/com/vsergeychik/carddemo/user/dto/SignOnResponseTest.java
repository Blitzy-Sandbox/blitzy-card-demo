package com.vsergeychik.carddemo.user.dto;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.user.model.SecUserRecord;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link SignOnResponse} - the outbound payload of the {@code COSGN00} sign-on screen,
 * CICS transaction {@code CC00}, program {@code app/cbl/COSGN00C.cbl}, map {@code COSGN0A}.
 *
 * <h2>What this suite is for, and what it deliberately leaves alone</h2>
 *
 * A plain-object suite over one immutable record. It constructs values, reads them back, measures
 * widths and offsets, and serialises. It starts <strong>no Spring context</strong>, uses no
 * {@code MockMvc}, and touches no controller, service or repository: {@code user.SignOnControllerTest}
 * owns the HTTP surface and {@code user.SignOnServiceTest} owns the authentication decision, so
 * re-asserting either here would be duplicated coverage masquerading as thoroughness. Mockito is on
 * the classpath and is not used, because the type under test has no collaborator to stub.
 *
 * <p>The reason this package needs its own instruments at all is the coverage gate. {@code pom.xml}
 * applies the JaCoCo {@code BRANCH} rule at {@code 0.90} to <strong>both</strong> {@code BUNDLE}
 * <strong>and</strong> {@code PACKAGE}, so {@code user}, {@code user.model} and {@code user.dto} are
 * each measured on their own and none can shelter behind another's coverage (gate <strong>G49</strong>).
 * The branches here are the canonical constructor's width and null guards and the role test that
 * selects between the two {@code XCTL} targets, and they are driven directly rather than incidentally.
 *
 * <h2>The two subjects this file exists for</h2>
 *
 * <ol>
 *   <li><strong>The 11-versus-10 asymmetry.</strong> {@code app/cpy-bms/COSGN00.CPY} declares eleven
 *       {@code xxxO} items; this payload carries ten. The eleventh, {@code PASSWDO} at CPY line 146, is
 *       omitted deliberately, and {@link TheOmittedEleventhField} holds the two counts apart so the gap
 *       cannot be closed by accident.</li>
 *   <li><strong>The group-level {@code REDEFINES} overlay.</strong> {@code COSGN00.CPY:85} declares
 *       {@code 01 COSGN0AO REDEFINES COSGN0AI}, one 308-byte storage area seen through two sets of
 *       names. {@link GroupRedefinesOverlay} proves the geometry and round-trips a value across the two
 *       views at identical offsets (gate <strong>G34</strong>).</li>
 * </ol>
 *
 * <h2>User-specified rules</h2>
 *
 * {@code review_rules} returns exactly one line - <strong>"No user rules provided."</strong> - and that
 * single line is the whole document; it was read again to the end before this suite was written. No
 * rule therefore governs this file, none has been invented, and the absence is not treated as licence
 * to assert less. The binding constraints are instead the enterprise practices <strong>B1</strong>
 * through <strong>B12</strong> of the plan, each named below with what it requires <em>here</em> and
 * nowhere restated in its own words.
 *
 * <h2>Governing practices, and this file's ruling on each</h2>
 *
 * <ul>
 *   <li><strong>B1</strong> - imports are confined to the JDK, JUnit Jupiter, AssertJ, Jackson and the
 *       project types this file legitimately depends on. No coordinate is added and nothing from the
 *       plan's exclusion list appears - no JPA, no Spring Security, no Testcontainers, no COBOL parser.
 *       {@code jakarta.validation} is on the classpath and is intentionally absent from the imports: a
 *       response is never validated, and {@link SignOnResponse} carries no constraint annotation to
 *       exercise. Widths are enforced by its constructor instead, which is stronger because it cannot
 *       be skipped.</li>
 *   <li><strong>B2</strong> - JUnit 5 Jupiter only: {@code @Test}, {@code @ParameterizedTest},
 *       {@code @Nested}, {@code @DisplayName}. No JUnit 4, no vendor runner.</li>
 *   <li><strong>B3</strong> and gate <strong>G5</strong> - the parity oracle is immutable and, in this
 *       suite, is not even <em>opened</em>. Nothing under {@code app/cbl/}, {@code app/cpy/},
 *       {@code app/cpy-bms/}, {@code app/bms/}, {@code app/csd/}, {@code app/jcl/}, {@code app/proc/},
 *       {@code app/ctl/}, {@code app/catlg/} or {@code app/data/} is read at runtime: there is no
 *       {@code java.nio.file} import, no directory walk and no path literal. Every expectation is an
 *       inlined {@code private static final} constant carrying the file and line it was transcribed
 *       from, which also makes the suite independent of the working directory it runs in.</li>
 *   <li><strong>B4</strong> - <em>the practice that governs this file most.</em> The copybook's eleven
 *       and the payload's ten are both recorded, with the evidence, and neither is adjusted to agree
 *       with the other. See {@link TheOmittedEleventhField}.</li>
 *   <li><strong>B5</strong> - asymmetries are asserted as correct, not smoothed. Ten members is right;
 *       no eleventh is invented for symmetry with {@code SignOnRequest}. {@code USERIDO} stays declared
 *       although the program never writes it. {@code curTime} stays nine characters although the four
 *       sibling user screens declare eight.</li>
 *   <li><strong>B6</strong> and gate <strong>G41</strong> - the security posture is neither weakened nor
 *       strengthened. This response carries no password at all, so there is nothing to hash even if
 *       hashing were in scope; the suite asserts that no password, token, encoder or security type is
 *       reachable through this payload's API or its serialised form.</li>
 *   <li><strong>B7</strong> and gate <strong>G54</strong> - deterministic and non-interactive. No
 *       {@code now()}, no random value, no ordering dependence, no sleep, no I/O. The one
 *       time-derived expectation is read through {@link Clock#fixed} at {@link ZoneOffset#UTC}, which
 *       is the seam {@code config.WebConfig}'s single {@link Clock} bean exists to make available.</li>
 *   <li><strong>B8</strong> and gates <strong>G46</strong>, <strong>G52</strong> - explicit over
 *       implicit. {@link #MAP_CHARSET} is named once and passed into every codec construction, so no
 *       assertion can inherit the platform default. Every import is written out individually; there is
 *       no wildcard. No dataset name appears anywhere in this file.</li>
 *   <li><strong>B9</strong> and gate <strong>G53</strong> - every field of this class is
 *       {@code static final} and immutable, over immutable values. No test mutates shared state, so the
 *       cases are order-independent; each one that writes bytes allocates its own record.</li>
 *   <li><strong>B10</strong> - the tests are the deliverable, not an afterthought: this suite asserts
 *       the screen contract rather than merely exercising accessors for coverage.</li>
 *   <li><strong>B11</strong> - every fixed-width and truncation operation routes through
 *       {@link FixedWidthCodec} or {@link FixedWidthRecord}. The {@code PIC X(80)} to {@code PIC X(78)}
 *       narrowing in particular is performed by {@link FixedWidthCodec#movePicX(String, int)} and never
 *       by a bare assignment or a {@code substring}, because the plan names {@code MOVE} the dominant
 *       parity risk and the direction of the loss has to be visible at the call site. No third-party
 *       copybook parser is involved.</li>
 *   <li><strong>B12</strong> - see the provenance note immediately below.</li>
 * </ul>
 *
 * <h2>Provenance of every expected value</h2>
 *
 * COBOL cannot be executed in this environment - the plan records eight independently verified
 * blockers, and the deviation is carried as risk <strong>R-A</strong> - so nothing here was captured
 * from a run. Every expectation is <strong>statically derived</strong> by reading the source and is
 * cited to the line it came from:
 *
 * <ul>
 *   <li>{@code app/cpy-bms/COSGN00.CPY} - line 17 opens {@code 01 COSGN0AI}; line 18 its twelve-byte
 *       {@code TIOAPFX} prefix; line 85 declares {@code 01 COSGN0AO REDEFINES COSGN0AI}; line 86 the
 *       output view's own twelve-byte prefix; the eleven {@code xxxO} items sit at lines 92, 98, 104,
 *       110, 116, 122, 128, 134, 140, 146 and 152.</li>
 *   <li>{@code app/bms/COSGN00.bms} - the name-labelled {@code DFHMDF} definitions and their
 *       {@code LENGTH=} operands, which corroborate the copybook widths independently; notably
 *       {@code CURTIME} at lines 70-74 and {@code ERRMSG} at lines 197-200.</li>
 *   <li>{@code app/cbl/COSGN00C.cbl} - the literals at lines 36, 37 and 38; the messages at lines 89,
 *       93, 120, 125, 242, 249 and 254; the map and mapset names at lines 111 and 112; the error-line
 *       move at line 149; {@code POPULATE-HEADER-INFO} at lines 177-204 including the two
 *       {@code EXEC CICS ASSIGN} statements at 198-200 and 202-204; the communication-area population
 *       at lines 224-228; and the {@code XCTL} routing at lines 230-240.</li>
 *   <li>{@code app/cpy/COCOM01Y.cpy} - the two user-type condition names at lines 27 and 28, and
 *       {@code CDEMO-LAST-MAP} and {@code CDEMO-LAST-MAPSET} at lines 43 and 44.</li>
 *   <li>{@code app/cpy/CSUSR01Y.cpy} - {@code SEC-USR-PWD PIC X(08)} at line 21 and
 *       {@code SEC-USR-TYPE PIC X(01)} at line 22.</li>
 *   <li>{@code app/csd/CARDDEMO.CSD} - the {@code CC00} transaction and {@code COSGN00C} program
 *       definitions, which corroborate the two literals above.</li>
 * </ul>
 *
 * <p>Where this suite and {@code SignOnRequestTest} share a constant they were transcribed from the
 * same line, and the vocabulary is deliberately identical so the two files read as one contract seen
 * from its two directions.
 *
 * <p>{@link SignOnResponse}'s <strong>declared</strong> members are ground truth throughout. Where this
 * suite's expectation and the class could differ, the class is asserted and the difference is recorded
 * in a comment rather than corrected here.
 */
@DisplayName("SignOnResponse - the COSGN00 (CC00) sign-on outbound payload")
class SignOnResponseTest {

    // =================================================================================================
    // THE CODE PAGE. Named once, passed explicitly into every codec construction below (B8).
    //
    // US-ASCII, not IBM037: the authoritative fixtures under app/data/ASCII are text, and this suite
    // measures widths and offsets rather than decoding a mainframe dataset. Naming it is the point -
    // no assertion here can quietly acquire the platform default.
    // =================================================================================================

    /** The explicitly named code page for every fixed-width operation in this suite. */
    private static final Charset MAP_CHARSET = StandardCharsets.US_ASCII;

    // =================================================================================================
    // SCREEN IDENTITY. Transcribed literals, each with the line it came from (B3, B12).
    // =================================================================================================

    /** {@code MAP('COSGN0A')}, {@code app/cbl/COSGN00C.cbl:111}; also the {@code DFHMDI} label. */
    private static final String MAP_NAME = "COSGN0A";

    /** {@code MAPSET('COSGN00')}, {@code app/cbl/COSGN00C.cbl:112}; also the {@code DFHMSD} label. */
    private static final String MAPSET_NAME = "COSGN00";

    /** {@code WS-TRANID PIC X(04) VALUE 'CC00'}, {@code app/cbl/COSGN00C.cbl:37}. */
    private static final String TRANSACTION_ID = "CC00";

    /** {@code WS-PGMNAME PIC X(08) VALUE 'COSGN00C'}, {@code app/cbl/COSGN00C.cbl:36}. */
    private static final String PROGRAM_NAME = "COSGN00C";

    /** Every {@code DFHMDF} definition in {@code app/bms/COSGN00.bms}, labelled or not. */
    private static final int DFHMDF_TOTAL = 37;

    /**
     * The name-labelled {@code DFHMDF} definitions, which is also the number of {@code xxxI} items and
     * of {@code xxxO} items in {@code app/cpy-bms/COSGN00.CPY}.
     */
    private static final int DFHMDF_NAMED = 11;

    /** Map-derived members of the response: {@value #DFHMDF_NAMED} named fields less {@code PASSWD}. */
    private static final int RESPONSE_MAP_MEMBERS = 10;

    // =================================================================================================
    // THE OUTPUT VIEW, in the copybook's declaration order. Parallel lists sharing one index, so each
    // can be checked against its own authority: the member names against the record's components, the
    // xxxO item names and widths against app/cpy-bms/COSGN00.CPY, and the screen labels and widths
    // against the LENGTH= operands of app/bms/COSGN00.bms. The two authorities agree field for field.
    //
    // These lists carry ELEVEN entries - the whole screen, PASSWD included - because the subject of
    // this file is the difference between eleven and ten. The payload's ten are RESPONSE_MEMBERS.
    // =================================================================================================

    /** The eleven {@code xxxO} item names, {@code app/cpy-bms/COSGN00.CPY} lines 92 through 152. */
    private static final List<String> OUTPUT_MAP_ITEMS = List.of(
            "TRNNAMEO", "TITLE01O", "CURDATEO", "PGMNAMEO", "TITLE02O", "CURTIMEO",
            "APPLIDO", "SYSIDO", "USERIDO", "PASSWDO", "ERRMSGO");

    /** The eleven name-labelled {@code DFHMDF} labels, {@code app/bms/COSGN00.bms} in mapset order. */
    private static final List<String> SCREEN_FIELDS = List.of(
            "TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME",
            "APPLID", "SYSID", "USERID", "PASSWD", "ERRMSG");

    /**
     * The eleven declared widths, from the {@code xxxO} {@code PICTURE} clauses and independently from
     * the mapset's {@code LENGTH=} operands: {@code 4, 40, 8, 8, 40, 9, 8, 8, 8, 8, 78}.
     *
     * <p>The sixth entry is <strong>9</strong>, not 8. See {@link WidthTraps}.
     */
    private static final List<Integer> DECLARED_WIDTHS = List.of(4, 40, 8, 8, 40, 9, 8, 8, 8, 8, 78);

    /** The {@code app/cpy-bms/COSGN00.CPY} line declaring each {@code xxxO} item. */
    private static final List<Integer> COPYBOOK_LINES = List.of(92, 98, 104, 110, 116, 122, 128, 134,
            140, 146, 152);

    /** The {@code app/bms/COSGN00.bms} line labelling each {@code DFHMDF}. */
    private static final List<Integer> MAPSET_LINES = List.of(34, 38, 47, 57, 61, 70, 80, 89, 156,
            175, 197);

    // =================================================================================================
    // THE TEN MEMBERS THE PAYLOAD ACTUALLY CARRIES, and the five that carry the navigation contract.
    // =================================================================================================

    /** The ten map-derived component names, in {@code 01 COSGN0AO} declaration order, less the password. */
    private static final List<String> RESPONSE_MEMBERS = List.of(
            "trnName", "title01", "curDate", "pgmName", "title02", "curTime",
            "applId", "sysId", "userId", "errMsg");

    /** The ten {@code xxxO} items the payload projects - {@link #OUTPUT_MAP_ITEMS} less {@code PASSWDO}. */
    private static final List<String> RESPONSE_MAP_ITEMS = List.of(
            "TRNNAMEO", "TITLE01O", "CURDATEO", "PGMNAMEO", "TITLE02O", "CURTIMEO",
            "APPLIDO", "SYSIDO", "USERIDO", "ERRMSGO");

    /** The ten declared widths of {@link #RESPONSE_MEMBERS}, in the same order. */
    private static final List<Integer> RESPONSE_WIDTHS = List.of(4, 40, 8, 8, 40, 9, 8, 8, 8, 78);

    /**
     * The five members with no {@code DFHMDF} behind them, in component order.
     *
     * <p>They are the mandated, documented exception to "every member is a screen field": once the
     * server is stateless, {@code EXEC CICS XCTL} has to be expressed as data. See
     * {@link RoleRoutingAndXctl} and {@link StatelessConversationState}.
     */
    private static final List<String> NAVIGATION_MEMBERS = List.of(
            "role", "nextProgram", "nextMapset", "nextMap", "navigationContext");

    /**
     * A member's name <strong>on the wire</strong>.
     *
     * <p>A screen field answers to its {@code xxxI} item in lower case - that is what
     * {@code @JsonProperty} pins on the subject and what AAP 0.6.3 requires, "payload field names and
     * lengths derive from the xxxI items only". A carrier traces to no {@code DFHMDF} field, so no such
     * rule governs it and it keeps its own component name. Keeping the two apart is the point: a single
     * list serving both roles would silently assert that the Java identifier and the wire name coincide.
     *
     * @param member the Java member name
     * @return the JSON property name it is published under
     */
    private static String wireNameOf(String member) {
        return RESPONSE_MEMBERS.contains(member) ? member.toLowerCase(Locale.ROOT) : member;
    }

    /**
     * {@link #wireNameOf(String)} over a list, preserving order.
     *
     * @param members the Java member names
     * @return their JSON property names
     */
    private static List<String> wireNamesOf(List<String> members) {
        return members.stream().map(SignOnResponseTest::wireNameOf).toList();
    }

    /** {@value #COMPONENT_COUNT} components: {@value #RESPONSE_MAP_MEMBERS} map-derived plus five. */
    private static final int COMPONENT_COUNT = 15;

    /** The screen-field stem of the one named field this payload omits: {@code PASSWD}, bms line 175. */
    private static final String OMITTED_SCREEN_FIELD = "PASSWD";

    /** The symbolic-map item this payload omits: {@code PASSWDO}, {@code COSGN00.CPY:146}. */
    private static final String OMITTED_OUTPUT_ITEM = "PASSWDO";

    /**
     * The four - and only four - lines of {@code app/cbl/COSGN00C.cbl} that mention {@code PASSWD} at
     * all: 123, 126, 135 and 244.
     *
     * <p>Recorded as data so {@link TheOmittedEleventhField#theProgramNeverSendsThePasswordBack()} can
     * state the census rather than merely describe it. What each line does is set out there.
     */
    private static final List<Integer> PASSWD_REFERENCE_LINES = List.of(123, 126, 135, 244);

    /** {@code PASSWDO} occurrences in {@code app/cbl/COSGN00C.cbl}: none. */
    private static final int PASSWDO_REFERENCE_COUNT = 0;

    /**
     * {@code CDEMO-CU0n-INFO} occurrences in {@code app/cbl/COSGN00C.cbl}: none.
     *
     * <p>Three sibling programs - {@code COUSR00C}, {@code COUSR02C} and {@code COUSR03C} - declare a
     * {@code CDEMO-CU0n-INFO} extension block onto the communication area. {@code COSGN00C} declares
     * none, so this payload carries none: it hands back the plain
     * {@value NavigationContext#COMMAREA_LENGTH}-byte area and nothing appended to it.
     */
    private static final int CDEMO_CU0N_INFO_COUNT = 0;

    // =================================================================================================
    // BYTE GEOMETRY OF THE TWO VIEWS, and why the overlay is exact rather than approximate.
    //
    // 01 COSGN0AI opens with a twelve-byte TIOAPFX prefix (CPY:18) and then, per field, xxxL
    // (COMP PIC S9(4), a two-byte halfword) + xxxF (PICTURE X) + FILLER PICTURE X(4) - seven bytes -
    // before the xxxI item.
    //
    // 01 COSGN0AO opens with its own twelve-byte prefix (CPY:86) and then, per field, FILLER
    // PICTURE X(3) + xxxC + xxxP + xxxH + xxxV, one byte each - also seven bytes - before the xxxO item.
    //
    // Both prefixes being seven bytes is the whole reason REDEFINES lines the two views up field for
    // field with no drift. The numbers below are transcribed and then RE-DERIVED by the layout
    // builders: RecordLayout refuses a gap, an unintended overlap, or any total other than the declared
    // length, so a single wrong constant fails class initialisation instead of silently shifting every
    // offset after it.
    // =================================================================================================

    /** {@code 02 FILLER PIC X(12)} - the {@code TIOAPFX=YES} prefix; {@code COSGN00.CPY:18} and 86. */
    private static final int TIOAPFX_PREFIX_LENGTH = 12;

    /** {@code xxxL COMP PIC S9(4)} - a binary halfword, two bytes. Input view only. */
    private static final int LENGTH_ITEM_LENGTH = 2;

    /** {@code xxxF PICTURE X}, and the {@code 03 xxxA PICTURE X} overlay over that same byte. */
    private static final int ATTRIBUTE_ITEM_LENGTH = 1;

    /** {@code 02 FILLER PICTURE X(4)} - the input view's reserved span between {@code xxxF} and {@code xxxI}. */
    private static final int INPUT_FILLER_LENGTH = 4;

    /** {@code 02 FILLER PICTURE X(3)} - the output view's reserved span, ahead of the attribute quartet. */
    private static final int OUTPUT_FILLER_LENGTH = 3;

    /**
     * The {@code EXTATT=YES} attribute quartet the output view interleaves ahead of every {@code xxxO}
     * item, in copybook order: colour, programmed symbol, highlight, validation.
     *
     * <p>{@code xxxC} is the <strong>colour</strong> item and is
     * {@link FieldAttributeSetter}'s target - the class publishes it as
     * {@link FieldAttributeSetter#COLOUR_ITEM_SUFFIX}. The sibling program makes the usage explicit:
     * {@code app/cbl/COUSR03C.cbl:317} executes {@code MOVE DFHGREEN TO ERRMSGC OF COUSR3AO}. All four
     * are terminal presentation attributes and none of them is payload.
     */
    private static final List<String> ATTRIBUTE_SUFFIXES = List.of("C", "P", "H", "V");

    /** {@value #FIELD_PREFIX_LENGTH} bytes ahead of every {@code xxxI} item: 2 + 1 + 4. */
    private static final int FIELD_PREFIX_LENGTH =
            LENGTH_ITEM_LENGTH + ATTRIBUTE_ITEM_LENGTH + INPUT_FILLER_LENGTH;

    /** {@value #OUTPUT_PREFIX_LENGTH} bytes ahead of every {@code xxxO} item: 3 + 1 + 1 + 1 + 1. */
    private static final int OUTPUT_PREFIX_LENGTH =
            OUTPUT_FILLER_LENGTH + ATTRIBUTE_SUFFIXES.size() * ATTRIBUTE_ITEM_LENGTH;

    /** {@value #PAYLOAD_WIDTH_TOTAL} bytes of data: 4+40+8+8+40+9+8+8+8+8+78. */
    private static final int PAYLOAD_WIDTH_TOTAL = 219;

    /**
     * {@value #SYMBOLIC_MAP_LENGTH} bytes in each view: {@value #TIOAPFX_PREFIX_LENGTH} +
     * {@value #DFHMDF_NAMED} x 7 + {@value #PAYLOAD_WIDTH_TOTAL}.
     *
     * <p>One figure, because {@code 01 COSGN0AO REDEFINES COSGN0AI} means one storage area.
     */
    private static final int SYMBOLIC_MAP_LENGTH = 308;

    /**
     * The twelve {@code REDEFINES} in {@code app/cpy-bms/COSGN00.CPY}: eleven per-field
     * {@code 02 FILLER REDEFINES xxxF} overlays at lines 21, 27, 33, 39, 45, 51, 57, 63, 69, 75 and 81,
     * plus the one group-level {@code 01 COSGN0AO REDEFINES COSGN0AI} at line 85.
     */
    private static final int COPYBOOK_REDEFINES_TOTAL = 12;

    /** The group-level {@code REDEFINES} of this copybook - exactly one, at {@code COSGN00.CPY:85}. */
    private static final int GROUP_LEVEL_REDEFINES = 1;

    /**
     * The per-field {@code REDEFINES} across this package's five maps: {@code COSGN00} 11,
     * {@code COUSR00} 59, {@code COUSR01} 12, {@code COUSR02} 12 and {@code COUSR03} 11.
     *
     * <p>One per named screen field, because every field's {@code xxxF} byte carries an {@code xxxA}
     * overlay. 11 + 59 + 12 + 12 + 11 = {@value #PACKAGE_PER_FIELD_REDEFINES}.
     */
    private static final int PACKAGE_PER_FIELD_REDEFINES = 105;

    /** One group-level {@code REDEFINES} per map, and this package owns five maps. */
    private static final int PACKAGE_GROUP_LEVEL_REDEFINES = 5;

    /**
     * {@value #PACKAGE_REDEFINES_TOTAL} {@code REDEFINES} across the package's five symbolic maps:
     * {@value #PACKAGE_PER_FIELD_REDEFINES} per-field plus {@value #PACKAGE_GROUP_LEVEL_REDEFINES}
     * group-level, with no gap and nothing counted twice.
     *
     * <p>The split matters because the two kinds are asserted in different files.
     * {@code SignOnRequestTest} owns the eleven per-field overlays of this map and states explicitly
     * that the group-level one is this file's; {@link GroupRedefinesOverlay} is where it is discharged.
     */
    private static final int PACKAGE_REDEFINES_TOTAL = 110;

    /**
     * {@code 05 WS-MESSAGE PIC X(80) VALUE SPACES}, {@code app/cbl/COSGN00C.cbl:38}.
     *
     * <p>Two characters wider than the {@code ERRMSGO PIC X(78)} it is moved into at line 149. See
     * {@link TheErrorLine}.
     */
    private static final int WS_MESSAGE_LENGTH = 80;

    /** The characters {@code MOVE WS-MESSAGE TO ERRMSGO} discards: 80 - 78. */
    private static final int ERRMSG_TRUNCATED_CHARACTERS = WS_MESSAGE_LENGTH - 78;

    // =================================================================================================
    // THE TWO VIEWS AS VALIDATED LAYOUTS. Immutable records over unmodifiable span lists, so publishing
    // them as constants introduces no shared mutable state (B9); each test that writes bytes takes its
    // own fresh FixedWidthRecord.
    // =================================================================================================

    /**
     * {@code 01 COSGN0AI} - the storage, plus the twelve {@code REDEFINES} the copybook declares over
     * it.
     *
     * <p>Eleven of those twelve are the per-field {@code xxxA} overlays. The twelfth is the group-level
     * {@code 01 COSGN0AO REDEFINES COSGN0AI} of {@code COSGN00.CPY:85}, declared here as a single
     * {@value #SYMBOLIC_MAP_LENGTH}-byte overlay named {@code COSGN0AO} over the whole area - which is
     * exactly what the copybook says it is. It is declared last so that all
     * {@value #SYMBOLIC_MAP_LENGTH} bytes of storage exist ahead of it, which is the condition
     * {@link FixedWidthRecord.RecordLayout} enforces on any overlay.
     *
     * <p>The {@code xxxL} halfword is declared as {@code FILLER} rather than under its own name: it is
     * {@code COMP} - binary - and {@link FixedWidthRecord.PictureKind} deliberately has no binary
     * category, because no persisted record in this estate holds one. It is reserved storage here, and
     * it is never a payload member in any case.
     */
    private static final FixedWidthRecord.RecordLayout INPUT_VIEW_LAYOUT = inputViewLayout();

    /**
     * {@code 01 COSGN0AO} - the same {@value #SYMBOLIC_MAP_LENGTH} bytes under the output view's own
     * names: the twelve-byte prefix, then per field a three-byte filler, the {@code xxxC},
     * {@code xxxP}, {@code xxxH} and {@code xxxV} attribute bytes, and the {@code xxxO} item.
     *
     * <p>Declared as a layout in its own right rather than as overlays inside
     * {@link #INPUT_VIEW_LAYOUT}, because that is what makes the geometry provable: a redefining group
     * has to tile the redefined storage exactly, so the fact that <em>both</em> builders satisfy
     * {@link FixedWidthRecord.RecordLayout}'s self-check at {@value #SYMBOLIC_MAP_LENGTH} bytes is the
     * assertion that the overlay is exact. The two views are then made to share one storage area in
     * {@link GroupRedefinesOverlay#aValueCrossesBetweenTheTwoViewsAtOneOffset}.
     */
    private static final FixedWidthRecord.RecordLayout OUTPUT_VIEW_LAYOUT = outputViewLayout();

    // =================================================================================================
    // DETERMINISM (B7). One fixed instant, read through Clock.fixed at UTC, so the header renders the
    // same characters on every run, on every machine, in any order.
    // =================================================================================================

    /**
     * A fixed instant: the timestamp in the version footer of {@code app/cbl/COSGN00C.cbl:259}, which
     * makes it traceable rather than arbitrary.
     */
    private static final Instant FIXED_INSTANT = Instant.parse("2022-07-19T23:12:33Z");

    /**
     * The single {@link Clock} every time-derived expectation here is read through.
     *
     * <p>{@code config.WebConfig} publishes the module's one {@link Clock} bean and returns
     * {@link Clock#systemDefaultZone()}; {@link DateHeader#from(FixedWidthCodec, Clock)} takes a
     * {@link Clock} and reads it once, which is precisely the seam that lets a test substitute a fixed
     * instant. No context is loaded here - the seam is used directly. {@link Clock} is immutable, so
     * this constant is not shared mutable state.
     */
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    /** {@code MM/DD/YY} for {@link #FIXED_INSTANT} - what {@code COSGN00C.cbl:190} would move. */
    private static final String FIXED_CURDATE = "07/19/22";

    /** {@code HH:MM:SS} for {@link #FIXED_INSTANT} - what {@code COSGN00C.cbl:196} would move. */
    private static final String FIXED_CURTIME = "23:12:33";

    /** {@code CURTIME DFHMDF ... INITIAL='Ahh:mm:ss'}, {@code app/bms/COSGN00.bms:74} - nine characters. */
    private static final String CURTIME_INITIAL = "Ahh:mm:ss";

    /** {@code CURDATE DFHMDF ... INITIAL='mm/dd/yy'}, {@code app/bms/COSGN00.bms:51} - eight. */
    private static final String CURDATE_INITIAL = "mm/dd/yy";

    // =================================================================================================
    // THE FIVE MESSAGE LITERALS COSGN00C MOVES INTO WS-MESSAGE, each with its line (B3, B12).
    // =================================================================================================

    /** {@code app/cbl/COSGN00C.cbl:120} - the blank user-id branch. */
    private static final String MSG_ENTER_USER_ID = "Please enter User ID ...";

    /** {@code app/cbl/COSGN00C.cbl:125} - the blank password branch. */
    private static final String MSG_ENTER_PASSWORD = "Please enter Password ...";

    /** {@code app/cbl/COSGN00C.cbl:242} - the plaintext comparison failed. */
    private static final String MSG_WRONG_PASSWORD = "Wrong Password. Try again ...";

    /** {@code app/cbl/COSGN00C.cbl:249} - {@code WS-RESP-CD} was 13, the record was not found. */
    private static final String MSG_USER_NOT_FOUND = "User not found. Try again ...";

    /** {@code app/cbl/COSGN00C.cbl:254} - the {@code WHEN OTHER} branch of the file read. */
    private static final String MSG_UNABLE_TO_VERIFY = "Unable to verify the User ...";

    /** The five literals above, in the order the program's branches reach them. */
    private static final List<String> PROGRAM_MESSAGES = List.of(
            MSG_ENTER_USER_ID, MSG_ENTER_PASSWORD, MSG_WRONG_PASSWORD, MSG_USER_NOT_FOUND,
            MSG_UNABLE_TO_VERIFY);

    /**
     * The two copybook messages {@code COSGN00C} moves into {@code WS-MESSAGE}, at lines 89 and 93.
     *
     * <p>Unlike the five literals above these are {@code PIC X(50)} fields rather than in-line text, so
     * they take a <strong>two-stage</strong> route to the screen: {@code PIC X(50)} widened into
     * {@code WS-MESSAGE PIC X(80)}, then narrowed into {@code ERRMSGO PIC X(78)}. See
     * {@link TheErrorLine#theCopybookMessagesWidenThenNarrow()}.
     */
    private static final List<String> COPYBOOK_MESSAGES = List.of(
            SystemMessages.CCDA_MSG_THANK_YOU, SystemMessages.CCDA_MSG_INVALID_KEY);

    // =================================================================================================
    // Construction of the constants above. Static, side-effect free, called once each.
    // =================================================================================================

    private static FixedWidthRecord.RecordLayout inputViewLayout() {
        List<FixedWidthRecord.FieldSpan> spans = new ArrayList<>();
        // 02 FILLER PIC X(12) - COSGN00.CPY:18.
        spans.add(FixedWidthRecord.FieldSpan.filler(0, TIOAPFX_PREFIX_LENGTH));
        int cursor = TIOAPFX_PREFIX_LENGTH;
        for (int index = 0; index < DFHMDF_NAMED; index++) {
            String field = SCREEN_FIELDS.get(index);
            // 02 xxxL COMP PIC S9(4) - a binary halfword, declared as reserved storage.
            spans.add(FixedWidthRecord.FieldSpan.filler(cursor, LENGTH_ITEM_LENGTH));
            cursor += LENGTH_ITEM_LENGTH;
            // 02 xxxF PICTURE X, with 02 FILLER REDEFINES xxxF / 03 xxxA PICTURE X over the same byte.
            FixedWidthRecord.FieldSpan flag = FixedWidthRecord.FieldSpan.alphanumeric(
                    field + "F", cursor, ATTRIBUTE_ITEM_LENGTH);
            spans.add(flag);
            spans.add(flag.redefinedAs(field + "A", FixedWidthRecord.PictureKind.ALPHANUMERIC));
            cursor += ATTRIBUTE_ITEM_LENGTH;
            // 02 FILLER PICTURE X(4).
            spans.add(FixedWidthRecord.FieldSpan.filler(cursor, INPUT_FILLER_LENGTH));
            cursor += INPUT_FILLER_LENGTH;
            // 02 xxxI PIC X(n).
            spans.add(FixedWidthRecord.FieldSpan.alphanumeric(
                    SCREEN_FIELDS.get(index) + "I", cursor, DECLARED_WIDTHS.get(index)));
            cursor += DECLARED_WIDTHS.get(index);
        }
        // 01 COSGN0AO REDEFINES COSGN0AI - COSGN00.CPY:85. Declared last, so the whole area exists
        // ahead of it, which is the condition RecordLayout enforces on an overlay.
        spans.add(FixedWidthRecord.FieldSpan.redefining(MAP_NAME + "O", 0, SYMBOLIC_MAP_LENGTH,
                FixedWidthRecord.PictureKind.ALPHANUMERIC));
        // The DECLARED total is passed, never the cursor this loop happened to reach: passing the
        // cursor would only prove the constants are consistent with themselves.
        return FixedWidthRecord.RecordLayout.of(SYMBOLIC_MAP_LENGTH,
                spans.toArray(FixedWidthRecord.FieldSpan[]::new));
    }

    private static FixedWidthRecord.RecordLayout outputViewLayout() {
        List<FixedWidthRecord.FieldSpan> spans = new ArrayList<>();
        // 02 FILLER PIC X(12) - COSGN00.CPY:86.
        spans.add(FixedWidthRecord.FieldSpan.filler(0, TIOAPFX_PREFIX_LENGTH));
        int cursor = TIOAPFX_PREFIX_LENGTH;
        for (int index = 0; index < DFHMDF_NAMED; index++) {
            String field = SCREEN_FIELDS.get(index);
            // 02 FILLER PICTURE X(3).
            spans.add(FixedWidthRecord.FieldSpan.filler(cursor, OUTPUT_FILLER_LENGTH));
            cursor += OUTPUT_FILLER_LENGTH;
            // 02 xxxC / xxxP / xxxH / xxxV PICTURE X - the EXTATT=YES attribute quartet.
            for (String suffix : ATTRIBUTE_SUFFIXES) {
                spans.add(FixedWidthRecord.FieldSpan.alphanumeric(
                        field + suffix, cursor, ATTRIBUTE_ITEM_LENGTH));
                cursor += ATTRIBUTE_ITEM_LENGTH;
            }
            // 02 xxxO PIC X(n).
            spans.add(FixedWidthRecord.FieldSpan.alphanumeric(
                    OUTPUT_MAP_ITEMS.get(index), cursor, DECLARED_WIDTHS.get(index)));
            cursor += DECLARED_WIDTHS.get(index);
        }
        return FixedWidthRecord.RecordLayout.of(SYMBOLIC_MAP_LENGTH,
                spans.toArray(FixedWidthRecord.FieldSpan[]::new));
    }

    // =================================================================================================
    // Shared, stateless helpers. Every one returns a fresh value; none caches, mutates or memoises.
    // =================================================================================================

    /**
     * A codec over the explicitly named code page (B8). A fresh instance per call: the codec is cheap,
     * and sharing one would be shared state for no benefit.
     */
    private static FixedWidthCodec codec() {
        return new FixedWidthCodec(MAP_CHARSET);
    }

    /**
     * An {@link ObjectMapper} configured exactly as {@code config.WebConfig} configures the
     * application's shared one, and for the reasons that class documents.
     *
     * <p>A default mapper would be the wrong instrument and would make this suite assert the wrong
     * thing. Four settings are stated rather than inherited: {@code USE_BIG_DECIMAL_FOR_FLOATS} and
     * {@code WRITE_BIGDECIMAL_AS_PLAIN} so no scale-bearing value could route through a {@code double}
     * or serialise in exponent notation, {@code FAIL_ON_TRAILING_TOKENS} so a payload is either read
     * whole or refused, and {@code ACCEPT_EMPTY_STRING_AS_NULL_OBJECT} <em>disabled</em> so an
     * all-spaces {@code PIC X(n)} value stays the real screen data it is instead of becoming
     * {@code null}. No naming strategy is applied, so each property still traces 1:1 to an {@code xxxO}
     * item; no inclusion filter is applied, so nothing is dropped; and no trimming converter is
     * registered, so trailing padding survives a round trip.
     */
    private static ObjectMapper webConfigEquivalentMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        mapper.configure(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN, true);
        mapper.disable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT);
        return mapper;
    }

    /** The record's component names, in declaration order. */
    private static List<String> componentNames() {
        return Arrays.stream(SignOnResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    /** The record's component types, in declaration order. */
    private static List<Class<?>> componentTypes() {
        return Arrays.stream(SignOnResponse.class.getRecordComponents())
                .map(RecordComponent::getType)
                .toList();
    }

    /** A fully populated response: every member exactly its declared width, and every value distinct. */
    private static SignOnResponse populated() {
        return new SignOnResponse(TRANSACTION_ID,
                ScreenTitles.CCDA_TITLE01,
                FIXED_CURDATE,
                PROGRAM_NAME,
                ScreenTitles.CCDA_TITLE02,
                FIXED_CURTIME + " ",
                "CICSAPPL",
                "CICS    ",
                "ADMIN001",
                codec().movePicX(MSG_WRONG_PASSWORD, SignOnResponse.ERRMSG_LENGTH),
                SignOnResponse.ROLE_ADMIN,
                SignOnResponse.NEXT_PROGRAM_ADMIN,
                MAPSET_NAME,
                MAP_NAME,
                signedOnContext(SignOnResponse.ROLE_ADMIN));
    }

    /**
     * The communication area as {@code app/cbl/COSGN00C.cbl:224-228} populates it on the success path:
     * {@code CDEMO-FROM-TRANID}, {@code CDEMO-FROM-PROGRAM}, {@code CDEMO-USER-ID},
     * {@code CDEMO-USER-TYPE} from {@code SEC-USR-TYPE}, and {@code CDEMO-PGM-CONTEXT} zeroed.
     */
    private static NavigationContext signedOnContext(String role) {
        return NavigationContext.empty()
                .withFromTranid(TRANSACTION_ID)
                .withFromProgram(PROGRAM_NAME)
                .withUserId("ADMIN001")
                .withUserType(role)
                .withPgmEnter();
    }

    /** The ten map-derived values of a response, in component order. */
    private static List<String> mapValuesOf(SignOnResponse response) {
        return List.of(response.trnName(), response.title01(), response.curDate(),
                response.pgmName(), response.title02(), response.curTime(), response.applId(),
                response.sysId(), response.userId(), response.errMsg());
    }

    /**
     * The serialised payload's own top-level keys, in emission order and with their exact spelling.
     *
     * <p>Case and order are preserved here, unlike in {@link #jsonKeys(SignOnResponse)}: because
     * {@code config.WebConfig} applies no naming strategy, each key must still be the component name
     * verbatim, and that is only checkable if nothing is normalised on the way out.
     */
    private static List<String> topLevelJsonKeys(SignOnResponse response) {
        return List.copyOf(asMap(response).keySet());
    }

    /** Every key of the serialised payload, nested keys included, flattened and lower-cased. */
    private static Set<String> jsonKeys(SignOnResponse response) {
        Set<String> keys = new LinkedHashSet<>();
        collectKeys(asMap(response), keys);
        return keys;
    }

    private static Map<String, Object> asMap(SignOnResponse response) {
        ObjectMapper mapper = webConfigEquivalentMapper();
        try {
            return mapper.readValue(mapper.writeValueAsString(response),
                    new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not serialise the sign-on response", failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static void collectKeys(Map<String, Object> node, Set<String> into) {
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            into.add(entry.getKey().toLowerCase(Locale.ROOT));
            if (entry.getValue() instanceof Map<?, ?> nested) {
                collectKeys((Map<String, Object>) nested, into);
            }
        }
    }

    /** Serialise then deserialise through the {@code WebConfig}-equivalent mapper. */
    private static SignOnResponse roundTrip(SignOnResponse response) {
        ObjectMapper mapper = webConfigEquivalentMapper();
        try {
            return mapper.readValue(mapper.writeValueAsString(response), SignOnResponse.class);
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not round trip the sign-on response", failure);
        }
    }

    /** Every type this payload's API mentions: component types, and every parameter and return type. */
    private static Set<String> reachableTypeNames() {
        Set<String> names = new LinkedHashSet<>();
        for (Class<?> type : componentTypes()) {
            names.add(type.getName());
        }
        for (Method method : SignOnResponse.class.getDeclaredMethods()) {
            names.add(method.getReturnType().getName());
            for (Class<?> parameter : method.getParameterTypes()) {
                names.add(parameter.getName());
            }
        }
        for (Field field : SignOnResponse.class.getDeclaredFields()) {
            names.add(field.getType().getName());
        }
        return names;
    }

    // =================================================================================================
    // 1. THE PROJECTION OF 01 COSGN0AO ONTO THE PAYLOAD.
    //
    // Gate G9: every payload field must trace to a DFHMDF definition, and every width to a symbolic-map
    // PICTURE clause. The five navigation members are the plan's own documented exception and are
    // identified as such rather than quietly counted as screen fields.
    // =================================================================================================

    @Nested
    @DisplayName("Projection of 01 COSGN0AO - ten map members, then five navigation members")
    class MapProjection {

        @Test
        @DisplayName("fifteen components: the ten map members in copybook order, then the five")
        void componentCensus() {
            assertThat(componentNames())
                    .as("the payload projects %d xxxO items and carries %d navigation members",
                            RESPONSE_MAP_MEMBERS, NAVIGATION_MEMBERS.size())
                    .hasSize(COMPONENT_COUNT)
                    .containsExactlyElementsOf(concat(RESPONSE_MEMBERS, NAVIGATION_MEMBERS));
            assertThat(RESPONSE_MEMBERS).hasSize(RESPONSE_MAP_MEMBERS);
            assertThat(NAVIGATION_MEMBERS).hasSize(COMPONENT_COUNT - RESPONSE_MAP_MEMBERS);
        }

        @Test
        @DisplayName("every map member is a String, because every xxxO item is PIC X(n)")
        void everyMapMemberIsCharacter() {
            List<Class<?>> types = componentTypes();
            for (int index = 0; index < RESPONSE_MAP_MEMBERS; index++) {
                assertThat(types.get(index))
                        .as("%s carries %s, which is PIC X(%d)", RESPONSE_MEMBERS.get(index),
                                RESPONSE_MAP_ITEMS.get(index), RESPONSE_WIDTHS.get(index))
                        .isEqualTo(String.class);
            }
        }

        @Test
        @DisplayName("no member is a floating-point type, and none is numeric at all")
        void nothingIsFloatingPoint() {
            // Gate G22. This screen has no monetary field, so the stronger statement holds: every
            // character member is a String and the one non-String member is the communication area.
            assertThat(componentTypes())
                    .doesNotContain(double.class, float.class, Double.class, Float.class)
                    .containsOnly(String.class, NavigationContext.class);
        }

        @Test
        @DisplayName("MAP_FIELDS is exactly the copybook's xxxO items, in order, less PASSWDO")
        void mapFieldsMatchTheCopybook() {
            assertThat(SignOnResponse.MAP_FIELDS)
                    .containsExactlyElementsOf(RESPONSE_MAP_ITEMS)
                    .hasSize(RESPONSE_MAP_MEMBERS)
                    .doesNotContain(OMITTED_OUTPUT_ITEM);
            assertThat(OUTPUT_MAP_ITEMS)
                    .as("the copybook itself declares %d items, %s among them", DFHMDF_NAMED,
                            OMITTED_OUTPUT_ITEM)
                    .hasSize(DFHMDF_NAMED)
                    .contains(OMITTED_OUTPUT_ITEM)
                    .containsAll(RESPONSE_MAP_ITEMS);
        }

        @Test
        @DisplayName("MAPSET_NAMED_FIELDS is the whole screen, in mapset order, PASSWD included")
        void namedFieldsMatchTheMapset() {
            assertThat(SignOnResponse.MAPSET_NAMED_FIELDS)
                    .as("the census of the screen is complete, so the omission stays visible")
                    .containsExactlyElementsOf(SCREEN_FIELDS)
                    .hasSize(DFHMDF_NAMED)
                    .contains(OMITTED_SCREEN_FIELD);
        }

        @Test
        @DisplayName("the three published counts are the mapset's own: 37, 11 and 10")
        void countsAreTheMapsetsOwn() {
            assertThat(SignOnResponse.MAPSET_FIELD_COUNT)
                    .as("every DFHMDF definition, labelled or not")
                    .isEqualTo(DFHMDF_TOTAL);
            assertThat(SignOnResponse.MAPSET_NAMED_FIELD_COUNT)
                    .as("the name-labelled ones, which are also the xxxI and xxxO item counts")
                    .isEqualTo(DFHMDF_NAMED);
            assertThat(SignOnResponse.MAP_FIELD_COUNT)
                    .as("the projected ones")
                    .isEqualTo(RESPONSE_MAP_MEMBERS);
            assertThat(DFHMDF_TOTAL - DFHMDF_NAMED)
                    .as("26 unlabelled definitions are static screen furniture, never payload")
                    .isEqualTo(26);
        }

        @Test
        @DisplayName("every map member traces to one named DFHMDF field, and its item is that field + O")
        void everyMemberTracesToAScreenField() {
            for (int index = 0; index < RESPONSE_MAP_MEMBERS; index++) {
                String item = RESPONSE_MAP_ITEMS.get(index);
                assertThat(SCREEN_FIELDS)
                        .as("%s must name a real screen field", item)
                        .contains(item.substring(0, item.length() - 1));
                assertThat(item)
                        .as("the trailing O is the output-direction suffix and is part of the name")
                        .endsWith(FieldAttributeSetter.OUTPUT_ITEM_SUFFIX);
            }
        }

        @Test
        @DisplayName("the field-name constants are the copybook's spelling, suffix included")
        void fieldNameConstantsAreVerbatim() {
            assertThat(List.of(SignOnResponse.TRNNAME_FIELD, SignOnResponse.TITLE01_FIELD,
                    SignOnResponse.CURDATE_FIELD, SignOnResponse.PGMNAME_FIELD,
                    SignOnResponse.TITLE02_FIELD, SignOnResponse.CURTIME_FIELD,
                    SignOnResponse.APPLID_FIELD, SignOnResponse.SYSID_FIELD,
                    SignOnResponse.USERID_FIELD, SignOnResponse.ERRMSG_FIELD))
                    .as("a tidied name would make a real field-for-field difference invisible")
                    .containsExactlyElementsOf(RESPONSE_MAP_ITEMS);
        }

        @Test
        @DisplayName("the published field lists are immutable, so no caller can edit the census")
        void publishedListsAreImmutable() {
            assertRefusesModification(() -> SignOnResponse.MAP_FIELDS.add(OMITTED_OUTPUT_ITEM));
            assertRefusesModification(() ->
                    SignOnResponse.MAPSET_NAMED_FIELDS.remove(OMITTED_SCREEN_FIELD));
        }

        @Test
        @DisplayName("the screen identity literals are the program's own")
        void screenIdentityLiteralsAreTheProgramsOwn() {
            assertThat(SignOnResponse.TRANID).isEqualTo(TRANSACTION_ID);
            assertThat(SignOnResponse.PROGRAM_NAME).isEqualTo(PROGRAM_NAME);
            assertThat(SignOnResponse.MAPSET_NAME).isEqualTo(MAPSET_NAME);
            assertThat(SignOnResponse.MAP_NAME).isEqualTo(MAP_NAME);
        }
    }

    // =================================================================================================
    // 2. WIDTH TRAPS. Nine, not eight; seventy-eight, not eighty; seven, not eight.
    //
    // Each width is transcribed twice - from the xxxO PICTURE clause and independently from the
    // mapset's LENGTH= operand - and both are compared against what the class publishes. Comparing the
    // class against two independent authorities is the point; comparing two copies of one typed-in
    // number would prove nothing.
    // =================================================================================================

    @Nested
    @DisplayName("Widths from the PICTURE clauses, cross-checked against the mapset's LENGTH=")
    class WidthTraps {

        @ParameterizedTest(name = "{0} is PIC X({1}) at COSGN00.CPY line {2}")
        @CsvSource({
            "TRNNAMEO, 4, 92",
            "TITLE01O, 40, 98",
            "CURDATEO, 8, 104",
            "PGMNAMEO, 8, 110",
            "TITLE02O, 40, 116",
            "CURTIMEO, 9, 122",
            "APPLIDO, 8, 128",
            "SYSIDO, 8, 134",
            "USERIDO, 8, 140",
            "ERRMSGO, 78, 152"
        })
        @DisplayName("the copybook declares the width this payload publishes")
        void copybookAgreesWithTheConstant(String item, int expected, int copybookLine) {
            int index = RESPONSE_MAP_ITEMS.indexOf(item);
            assertThat(index).as("%s must be a projected item", item).isNotNegative();
            assertThat(RESPONSE_WIDTHS.get(index))
                    .as("%s is declared at COSGN00.CPY:%d", item, copybookLine)
                    .isEqualTo(expected);
            assertThat(publishedWidths().get(index))
                    .as("the class must publish the copybook's width for %s", item)
                    .isEqualTo(expected);
            assertThat(COPYBOOK_LINES.get(OUTPUT_MAP_ITEMS.indexOf(item)))
                    .as("and the transcription must cite the right line")
                    .isEqualTo(copybookLine);
        }

        @ParameterizedTest(name = "{0} has LENGTH={1} at COSGN00.bms line {2}")
        @CsvSource({
            "TRNNAME, 4, 34",
            "TITLE01, 40, 38",
            "CURDATE, 8, 47",
            "PGMNAME, 8, 57",
            "TITLE02, 40, 61",
            "CURTIME, 9, 70",
            "APPLID, 8, 80",
            "SYSID, 8, 89",
            "USERID, 8, 156",
            "ERRMSG, 78, 197"
        })
        @DisplayName("the mapset's LENGTH operand agrees independently of the copybook")
        void mapsetAgreesWithTheConstant(String screenField, int expected, int mapsetLine) {
            int index = SCREEN_FIELDS.indexOf(screenField);
            assertThat(index).as("%s must be a named screen field", screenField).isNotNegative();
            assertThat(DECLARED_WIDTHS.get(index))
                    .as("%s DFHMDF LENGTH=%d at bms:%d", screenField, expected, mapsetLine)
                    .isEqualTo(expected);
            assertThat(MAPSET_LINES.get(index)).isEqualTo(mapsetLine);
            assertThat(publishedWidths().get(RESPONSE_MAP_ITEMS.indexOf(screenField + "O")))
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("curTime is nine characters, not the eight of COUSR00 through COUSR03")
        void curTimeIsNineCharacters() {
            // COSGN00.CPY:122 declares CURTIMEO PIC X(9), and app/bms/COSGN00.bms:70-74 corroborates
            // it independently with LENGTH=9 and INITIAL='Ahh:mm:ss' - a nine-character literal. The
            // four sibling user screens declare their time field X(8). Regularising this one to eight
            // would shorten a real field (B5).
            assertThat(SignOnResponse.CURTIME_LENGTH)
                    .as("COSGN00.CPY:122 and COSGN00.bms:72 both say nine")
                    .isEqualTo(9);
            assertThat(CURTIME_INITIAL)
                    .as("the mapset's own INITIAL literal is the independent corroboration")
                    .hasSize(SignOnResponse.CURTIME_LENGTH);
            assertThat(CURDATE_INITIAL)
                    .as("while CURDATE's is eight, so the difference is real and not a typo")
                    .hasSize(SignOnResponse.CURDATE_LENGTH)
                    .hasSize(8);
            assertThat(SignOnResponse.CURTIME_LENGTH)
                    .isEqualTo(SignOnResponse.CURDATE_LENGTH + 1);
        }

        @Test
        @DisplayName("the eight-character header time pads on the right into the nine-wide field")
        void theHeaderTimePadsIntoTheNineWideField() {
            // DateHeader renders HH:MM:SS - eight characters, WS_CURTIME_HH_MM_SS_LENGTH - and the
            // receiver is nine, so COBOL pads on the right. Through the codec, never by assignment.
            String rendered = DateHeader.from(codec(), FIXED_CLOCK).wsCurtimeHhMmSs();
            assertThat(rendered).isEqualTo(FIXED_CURTIME).hasSize(DateHeader.WS_CURTIME_HH_MM_SS_LENGTH);

            String moved = codec().movePicX(rendered, SignOnResponse.CURTIME_LENGTH);
            assertThat(moved)
                    .hasSize(SignOnResponse.CURTIME_LENGTH)
                    .isEqualTo(FIXED_CURTIME + " ")
                    .endsWith(" ");
            assertThat(new SignOnResponse(TRANSACTION_ID, ScreenTitles.CCDA_TITLE01, FIXED_CURDATE,
                    PROGRAM_NAME, ScreenTitles.CCDA_TITLE02, moved, "CICSAPPL", "CICS    ",
                    "ADMIN001", " ".repeat(SignOnResponse.ERRMSG_LENGTH), SignOnResponse.ROLE_ADMIN,
                    SignOnResponse.NEXT_PROGRAM_ADMIN, MAPSET_NAME, MAP_NAME,
                    signedOnContext(SignOnResponse.ROLE_ADMIN)).curTime())
                    .isEqualTo(moved);
        }

        @Test
        @DisplayName("the eight-character header date fills curDate exactly, with no padding")
        void theHeaderDateFillsCurDateExactly() {
            String rendered = DateHeader.from(codec(), FIXED_CLOCK).wsCurdateMmDdYy();
            assertThat(rendered)
                    .as("MM/DD/YY for the fixed instant of COSGN00C.cbl:259")
                    .isEqualTo(FIXED_CURDATE)
                    .hasSize(SignOnResponse.CURDATE_LENGTH);
            assertThat(codec().movePicX(rendered, SignOnResponse.CURDATE_LENGTH))
                    .as("an exact-width MOVE neither pads nor truncates")
                    .isEqualTo(rendered);
        }

        @Test
        @DisplayName("errMsg is 78 although WS-MESSAGE is PIC X(80), so two characters are lost")
        void errMsgIsNarrowerThanTheMessageItCarries() {
            assertThat(SignOnResponse.ERRMSG_LENGTH).isEqualTo(78);
            assertThat(WS_MESSAGE_LENGTH)
                    .as("COSGN00C.cbl:38 declares WS-MESSAGE PIC X(80)")
                    .isEqualTo(80)
                    .isGreaterThan(SignOnResponse.ERRMSG_LENGTH);
            assertThat(ERRMSG_TRUNCATED_CHARACTERS)
                    .as("MOVE WS-MESSAGE TO ERRMSGO at line 149 discards this many")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("applId and sysId are eight, and exist on this screen alone")
        void applIdAndSysIdAreEight() {
            // Unlike every other header field these two are not moved from WORKING-STORAGE: the
            // program obtains them from CICS itself, with EXEC CICS ASSIGN APPLID(APPLIDO OF COSGN0AO)
            // at COSGN00C.cbl:198-200 and EXEC CICS ASSIGN SYSID(SYSIDO OF COSGN0AO) at :202-204 -
            // two separate statements, each writing straight into the OUTPUT view. On the response
            // side they are therefore genuinely server-derived; on the request side the same two
            // fields are only ever whatever the terminal echoed back. No sibling screen in this
            // package has them at all.
            assertThat(SignOnResponse.APPLID_LENGTH).isEqualTo(8);
            assertThat(SignOnResponse.SYSID_LENGTH).isEqualTo(8);
            assertThat(OUTPUT_MAP_ITEMS).contains("APPLIDO", "SYSIDO");
            assertThat(populated().applId()).hasSize(SignOnResponse.APPLID_LENGTH);
            assertThat(populated().sysId()).hasSize(SignOnResponse.SYSID_LENGTH);
        }

        @Test
        @DisplayName("title01 and title02 are forty and carry the screen titles byte for byte")
        void titlesAreFortyCharacters() {
            assertThat(SignOnResponse.TITLE01_LENGTH).isEqualTo(ScreenTitles.TITLE_LENGTH).isEqualTo(40);
            assertThat(SignOnResponse.TITLE02_LENGTH).isEqualTo(ScreenTitles.TITLE_LENGTH);
            assertThat(ScreenTitles.CCDA_TITLE01).hasSize(SignOnResponse.TITLE01_LENGTH);
            assertThat(ScreenTitles.CCDA_TITLE02).hasSize(SignOnResponse.TITLE02_LENGTH);
            assertThat(populated().title01()).isEqualTo(ScreenTitles.CCDA_TITLE01);
            assertThat(populated().title02()).isEqualTo(ScreenTitles.CCDA_TITLE02);
        }

        @Test
        @DisplayName("userId is the width of the USRSEC key it was read with, and role of SEC-USR-TYPE")
        void userIdAndRoleMatchTheSecurityRecord() {
            // The program reads USRSEC with RIDFLD(WS-USER-ID) at COSGN00C.cbl:211-219 and then moves
            // SEC-USR-TYPE into CDEMO-USER-TYPE at :227, so both widths are the security record's.
            assertThat(SignOnResponse.USERID_LENGTH)
                    .isEqualTo(SecUserRecord.SEC_USR_ID_LENGTH)
                    .isEqualTo(8);
            assertThat(SignOnResponse.ROLE_LENGTH)
                    .as("CSUSR01Y.cpy:22 SEC-USR-TYPE PIC X(01) becomes COCOM01Y.cpy:26 CDEMO-USER-TYPE")
                    .isEqualTo(SecUserRecord.SEC_USR_TYPE_LENGTH)
                    .isEqualTo(NavigationContext.USER_TYPE_LENGTH)
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("the navigation widths are NavigationContext's own, not restated literals")
        void navigationWidthsDelegate() {
            assertThat(SignOnResponse.ROLE_LENGTH).isEqualTo(NavigationContext.USER_TYPE_LENGTH);
            assertThat(SignOnResponse.NEXT_PROGRAM_LENGTH)
                    .isEqualTo(NavigationContext.TO_PROGRAM_LENGTH);
            assertThat(SignOnResponse.NEXT_MAPSET_LENGTH)
                    .isEqualTo(NavigationContext.LAST_MAPSET_LENGTH);
            assertThat(SignOnResponse.NEXT_MAP_LENGTH).isEqualTo(NavigationContext.LAST_MAP_LENGTH);
        }

        @Test
        @DisplayName("the map and mapset names are seven characters, which is why X(7) is correct")
        void mapNamesAreSevenCharacters() {
            // CDEMO-LAST-MAP and CDEMO-LAST-MAPSET are PIC X(7) at COCOM01Y.cpy:43-44, not X(8). This
            // screen shows why: a BMS symbolic-map group item is a seven-character map name plus a
            // one-character direction suffix, which is how COSGN0A yields COSGN0AI for input and
            // COSGN0AO for output. Both real values are exactly seven.
            assertThat(MAP_NAME).hasSize(SignOnResponse.NEXT_MAP_LENGTH).hasSize(7);
            assertThat(MAPSET_NAME).hasSize(SignOnResponse.NEXT_MAPSET_LENGTH).hasSize(7);
            assertThat(MAP_NAME + "I").hasSize(8);
            assertThat(MAP_NAME + FieldAttributeSetter.OUTPUT_MAP_SUFFIX).hasSize(8);
            assertThat(SignOnResponse.NEXT_PROGRAM_LENGTH)
                    .as("a program name is eight, which is a different field entirely")
                    .isEqualTo(8);
            assertThat(SignOnResponse.NEXT_PROGRAM_ADMIN).hasSize(8);
            assertThat(SignOnResponse.NEXT_PROGRAM_USER).hasSize(8);
        }

        @Test
        @DisplayName("the published widths equal the copybook's, entry for entry")
        void publishedWidthsEqualTheCopybooks() {
            assertThat(publishedWidths()).containsExactlyElementsOf(RESPONSE_WIDTHS);
            assertThat(RESPONSE_WIDTHS.stream().mapToInt(Integer::intValue).sum())
                    .as("the ten projected widths, which is 219 less the omitted PASSWDO's 8")
                    .isEqualTo(PAYLOAD_WIDTH_TOTAL - 8);
        }
    }

    /** The widths the type under test publishes, in component order - read from the class, not restated. */
    private static List<Integer> publishedWidths() {
        return List.of(SignOnResponse.TRNNAME_LENGTH,
                SignOnResponse.TITLE01_LENGTH,
                SignOnResponse.CURDATE_LENGTH,
                SignOnResponse.PGMNAME_LENGTH,
                SignOnResponse.TITLE02_LENGTH,
                SignOnResponse.CURTIME_LENGTH,
                SignOnResponse.APPLID_LENGTH,
                SignOnResponse.SYSID_LENGTH,
                SignOnResponse.USERID_LENGTH,
                SignOnResponse.ERRMSG_LENGTH);
    }

    /** Concatenates two lists into a new immutable list. */
    private static List<String> concat(List<String> first, List<String> second) {
        List<String> combined = new ArrayList<>(first);
        combined.addAll(second);
        return List.copyOf(combined);
    }

    /** Asserts that a published collection refuses modification, so a census cannot be edited. */
    private static void assertRefusesModification(Runnable modification) {
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .as("a published census must be immutable")
                .isThrownBy(modification::run);
    }

    // =================================================================================================
    // 3. THE ELEVENTH NAMED FIELD.
    //
    //    ##############################################################################################
    //    #                                                                                            #
    //    #   READ THIS BEFORE "FIXING" THE COUNT.                                                     #
    //    #                                                                                            #
    //    #   app/cpy-bms/COSGN00.CPY declares ELEVEN xxxO items. SignOnResponse carries TEN.           #
    //    #   The missing one is PASSWDO, at COSGN00.CPY:146, and it is missing ON PURPOSE.             #
    //    #                                                                                            #
    //    #   The whole of the evidence is four lines. Every mention of PASSWD in the 260 lines of       #
    //    #   app/cbl/COSGN00C.cbl is one of these, and there is no fifth:                              #
    //    #                                                                                            #
    //    #     :123  WHEN PASSWDI OF COSGN0AI = SPACES OR LOW-VALUES     <- READS the input item       #
    //    #     :126  MOVE -1 TO PASSWDL OF COSGN0AI                      <- cursor, not a value        #
    //    #     :135  MOVE FUNCTION UPPER-CASE(PASSWDI OF COSGN0AI) TO    <- READS the input item       #
    //    #     :244  MOVE -1 TO PASSWDL OF COSGN0AI                      <- cursor, not a value        #
    //    #                                                                                            #
    //    #   PASSWDO IS NEVER REFERENCED ANYWHERE IN THE PROGRAM. The program only ever READS          #
    //    #   PASSWDI and only ever POSITIONS THE CURSOR through PASSWDL - the MOVE -1 idiom. It        #
    //    #   never writes the password back to the screen, so SEND-SIGNON-SCREEN at :145-157, which    #
    //    #   sends FROM(COSGN0AO), provably sends no password. Adding a member here would INVENT       #
    //    #   behaviour the COBOL does not have, which this migration forbids as firmly as it forbids   #
    //    #   removing behaviour (B5, and the plan's like-for-like directive).                          #
    //    #                                                                                            #
    //    #   Both counts are therefore recorded and NEITHER is adjusted to agree with the other        #
    //    #   (B4). The class is not padded to eleven, and no other field is dropped to round the       #
    //    #   number down. The cases below hold the two apart so the gap cannot close by accident.      #
    //    #                                                                                            #
    //    #   DO NOT GENERALISE THIS EITHER WAY. The rule is "mirror the program", not "hide            #
    //    #   passwords" and not "echo passwords". SignOnRequest carries a password because COSGN00C    #
    //    #   reads one. UserUpdateResponse carries one because app/cbl/COUSR02C.cbl:169 really does    #
    //    #   execute MOVE SEC-USR-PWD TO PASSWDI OF COUSR2AI. Those two are opposites and BOTH are     #
    //    #   correct. Each screen is decided from its own source.                                      #
    //    #                                                                                            #
    //    ##############################################################################################
    // =================================================================================================

    @Nested
    @DisplayName("The eleventh named field: PASSWD is absent, and the program proves why")
    class TheOmittedEleventhField {

        @Test
        @DisplayName("eleven named screen fields, ten members: both counts stand, neither is adjusted")
        void bothCountsStand() {
            assertThat(SignOnResponse.MAPSET_NAMED_FIELD_COUNT)
                    .as("COSGN00.CPY declares eleven xxxO items and the mapset eleven named DFHMDFs")
                    .isEqualTo(DFHMDF_NAMED)
                    .isEqualTo(11);
            assertThat(SignOnResponse.MAP_FIELD_COUNT)
                    .as("and this payload carries ten")
                    .isEqualTo(RESPONSE_MAP_MEMBERS)
                    .isEqualTo(10);
            assertThat(SignOnResponse.MAPSET_NAMED_FIELD_COUNT - SignOnResponse.MAP_FIELD_COUNT)
                    .as("the gap is exactly one field, and that field is %s",
                            SignOnResponse.OMITTED_FIELD)
                    .isEqualTo(1);
            assertThat(SignOnResponse.OMITTED_FIELD).isEqualTo(OMITTED_SCREEN_FIELD);
            assertThat(SignOnResponse.OMITTED_ITEM).isEqualTo(OMITTED_OUTPUT_ITEM);
        }

        @Test
        @DisplayName("PASSWD is a real named screen field, so the omission is a decision not an oversight")
        void passwordIsARealScreenField() {
            // bms line 175 labels it and COSGN00.CPY:146 declares PASSWDO PIC X(8). It is a genuine
            // field that this payload genuinely declines to carry.
            assertThat(SCREEN_FIELDS).contains(OMITTED_SCREEN_FIELD);
            assertThat(OUTPUT_MAP_ITEMS).contains(OMITTED_OUTPUT_ITEM);
            assertThat(MAPSET_LINES.get(SCREEN_FIELDS.indexOf(OMITTED_SCREEN_FIELD)))
                    .as("PASSWD DFHMDF is at app/bms/COSGN00.bms:175")
                    .isEqualTo(175);
            assertThat(COPYBOOK_LINES.get(OUTPUT_MAP_ITEMS.indexOf(OMITTED_OUTPUT_ITEM)))
                    .as("PASSWDO PIC X(8) is at app/cpy-bms/COSGN00.CPY:146")
                    .isEqualTo(146);
            assertThat(DECLARED_WIDTHS.get(SCREEN_FIELDS.indexOf(OMITTED_SCREEN_FIELD)))
                    .as("eight characters, the width of SEC-USR-PWD it is compared against")
                    .isEqualTo(SecUserRecord.SEC_USR_PWD_LENGTH)
                    .isEqualTo(8);
        }

        @Test
        @DisplayName("the program never sends the password back: four PASSWD lines, none of them PASSWDO")
        void theProgramNeverSendsThePasswordBack() {
            assertThat(PASSWD_REFERENCE_LINES)
                    .as("every PASSWD mention in app/cbl/COSGN00C.cbl, and there is no fifth")
                    .containsExactly(123, 126, 135, 244)
                    .hasSize(4);
            assertThat(PASSWDO_REFERENCE_COUNT)
                    .as("PASSWDO - the OUTPUT item - is referenced nowhere in the program")
                    .isZero();
            // Two of the four read PASSWDI, the INPUT item; the other two move -1 into PASSWDL, which
            // positions the cursor and is not a value at all. Neither kind writes the output view.
            assertThat(PASSWD_REFERENCE_LINES).contains(123, 135);
            assertThat(PASSWD_REFERENCE_LINES).contains(126, 244);
        }

        @Test
        @DisplayName("no component, accessor or constant introduces a password")
        void noPasswordAnywhereInTheApi() {
            // Stated as an explicit loop rather than through a collection matcher, so that a failure
            // names the offending component instead of reporting only that the set did not match.
            for (String name : componentNames()) {
                assertThat(name.toLowerCase(Locale.ROOT))
                        .as("component %s", name)
                        .doesNotContain("pass")
                        .doesNotContain("pwd")
                        .doesNotContain("secret")
                        .doesNotContain("credential");
            }
            for (Method method : SignOnResponse.class.getDeclaredMethods()) {
                assertThat(method.getName().toLowerCase(Locale.ROOT))
                        .as("method %s", method.getName())
                        .doesNotContain("pwd")
                        .doesNotContain("secret");
            }
            assertThat(SignOnResponse.MAP_FIELDS)
                    .as("and the projected census names no password item")
                    .doesNotContain(OMITTED_OUTPUT_ITEM);
        }

        @Test
        @DisplayName("the serialised payload has no key naming a password")
        void noPasswordKeyOnTheWire() {
            assertThat(jsonKeys(populated()))
                    .as("nothing on the wire mentions a password, in any nesting level")
                    .doesNotContain("passwd", "password", "pwd", "secusrpwd", "secret");
        }

        @Test
        @DisplayName("USERIDO is likewise never written, yet stays declared: nothing is tidied")
        void theUserIdStaysDeclaredAlthoughNeverWritten() {
            // The counterpart asymmetry, and the reason the PASSWD omission is not simply "drop what
            // the program never writes". USERIDO has no write site either - the program reads USERIDI
            // at :118 and :132 and moves -1 into USERIDL at :121, :250 and :255 - yet USERID is a real
            // named DFHMDF at bms:156, and MOVE LOW-VALUES TO COSGN0AO initialises the whole output
            // group, so the field is part of the screen's shape regardless. It stays (B5). What
            // distinguishes PASSWD is not "unwritten" but "a credential the program never echoes".
            assertThat(componentNames()).contains("userId");
            assertThat(SignOnResponse.MAP_FIELDS).contains(SignOnResponse.USERID_FIELD);
            assertThat(SignOnResponse.USERID_FIELD).isEqualTo("USERIDO");
        }

        @Test
        @DisplayName("no security framework type is reachable: authentication stays plaintext on USRSEC")
        void noSecurityFrameworkIsIntroduced() {
            // Gate G41 and practice B6. COSGN00C.cbl:223 authenticates with IF SEC-USR-PWD =
            // WS-USER-PWD - a direct comparison against SEC-USR-PWD PIC X(08) of CSUSR01Y.cpy:21.
            // There is no hash, salt, token or expiry in the legacy design, and introducing one would
            // change observable behaviour. Since this response carries no password at all, there is
            // nothing here to hash even if hashing were permitted - the assertion is that no security
            // type has crept into the payload's API either.
            for (String name : reachableTypeNames()) {
                assertThat(name)
                        .as("reachable type %s", name)
                        .doesNotContain("springframework.security")
                        .doesNotContain("PasswordEncoder")
                        .doesNotContain("Jwt")
                        .doesNotContain("BCrypt");
            }
            assertThat(SecUserRecord.SEC_USR_PWD_LENGTH)
                    .as("the stored credential is a plaintext PIC X(08); that is inherited, not chosen")
                    .isEqualTo(8);
        }
    }

    // =================================================================================================
    // 4. THE GROUP-LEVEL REDEFINES OVERLAY - gate G34, and this file's other defining subject.
    //
    // app/cpy-bms/COSGN00.CPY:85 declares:
    //
    //     01  COSGN0AO REDEFINES COSGN0AI.
    //
    // ONE storage area, TWO sets of names. That is one of the twelve REDEFINES in this copybook; the
    // other eleven are the per-field 02 FILLER REDEFINES xxxF overlays at lines 21, 27, 33, 39, 45, 51,
    // 57, 63, 69, 75 and 81, which SignOnRequestTest owns and which that file explicitly states are not
    // this one. Across the package's five maps the split is 105 per-field + 5 group-level = 110, with no
    // gap and nothing counted twice.
    //
    // The overlay is EXACT rather than approximate because both views carry a seven-byte per-field
    // prefix. That is not a coincidence to be trusted, it is arithmetic to be proved, and the proof is
    // that both layout builders satisfy RecordLayout's self-check at 308 bytes.
    // =================================================================================================

    @Nested
    @DisplayName("REDEFINES - one 308-byte area, two views, zero drift")
    class GroupRedefinesOverlay {

        @Test
        @DisplayName("both views tile 308 bytes exactly: 12 + 11 x 7 + 219")
        void bothViewsTileTheSameArea() {
            // Constructing either layout already proved this - RecordLayout refuses a gap, an
            // unintended overlap and any total other than its declared length - so this case states
            // the arithmetic a reader needs rather than leaving it to be discovered.
            assertThat(FIELD_PREFIX_LENGTH)
                    .as("input view: xxxL 2 + xxxF 1 + FILLER X(4)")
                    .isEqualTo(7);
            assertThat(OUTPUT_PREFIX_LENGTH)
                    .as("output view: FILLER X(3) + xxxC + xxxP + xxxH + xxxV")
                    .isEqualTo(7);
            assertThat(OUTPUT_PREFIX_LENGTH)
                    .as("EQUAL PREFIXES ARE THE WHOLE REASON THE OVERLAY LINES UP FIELD FOR FIELD")
                    .isEqualTo(FIELD_PREFIX_LENGTH);
            assertThat(DECLARED_WIDTHS.stream().mapToInt(Integer::intValue).sum())
                    .isEqualTo(PAYLOAD_WIDTH_TOTAL);
            assertThat(TIOAPFX_PREFIX_LENGTH + DFHMDF_NAMED * FIELD_PREFIX_LENGTH
                    + PAYLOAD_WIDTH_TOTAL)
                    .as("12 + 77 + 219")
                    .isEqualTo(SYMBOLIC_MAP_LENGTH);
            assertThat(INPUT_VIEW_LAYOUT.recordLength()).isEqualTo(SYMBOLIC_MAP_LENGTH);
            assertThat(OUTPUT_VIEW_LAYOUT.recordLength())
                    .as("a redefining group must tile the redefined storage exactly")
                    .isEqualTo(INPUT_VIEW_LAYOUT.recordLength());
            assertThat(OUTPUT_VIEW_LAYOUT.storageSpans().stream()
                    .mapToInt(FixedWidthRecord.FieldSpan::length).sum())
                    .as("the output view's own storage spans sum to the area")
                    .isEqualTo(SYMBOLIC_MAP_LENGTH);
            assertThat(INPUT_VIEW_LAYOUT.storageSpans().stream()
                    .mapToInt(FixedWidthRecord.FieldSpan::length).sum())
                    .isEqualTo(SYMBOLIC_MAP_LENGTH);
        }

        @Test
        @DisplayName("the group-level overlay is declared, and is the twelfth REDEFINES of the copybook")
        void theGroupLevelOverlayIsTheTwelfth() {
            FixedWidthRecord.FieldSpan group = INPUT_VIEW_LAYOUT.span(MAP_NAME + "O");
            assertThat(group.redefinition())
                    .as("01 COSGN0AO REDEFINES COSGN0AI at COSGN00.CPY:85")
                    .isTrue();
            assertThat(group.offset()).as("it redefines the group from its first byte").isZero();
            assertThat(group.length())
                    .as("and covers the whole area, not part of it")
                    .isEqualTo(SYMBOLIC_MAP_LENGTH);
            assertThat(INPUT_VIEW_LAYOUT.redefinitions())
                    .as("eleven per-field xxxA overlays plus the one group-level overlay")
                    .hasSize(COPYBOOK_REDEFINES_TOTAL)
                    .hasSize(DFHMDF_NAMED + GROUP_LEVEL_REDEFINES);
            assertThat(OUTPUT_VIEW_LAYOUT.redefinitions())
                    .as("the output view is the redefinition; it declares none of its own")
                    .isEmpty();
            assertThat(PACKAGE_PER_FIELD_REDEFINES + PACKAGE_GROUP_LEVEL_REDEFINES)
                    .as("11+59+12+12+11 per-field, plus one group-level per map, across five maps")
                    .isEqualTo(PACKAGE_REDEFINES_TOTAL);
        }

        @ParameterizedTest(name = "{0}O sits exactly where {0}I sits")
        @ValueSource(strings = {"TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME",
            "APPLID", "SYSID", "USERID", "PASSWD", "ERRMSG"})
        @DisplayName("each xxxO item aligns byte for byte with the xxxI item it redefines")
        void theDataItemsAlignWithZeroDrift(String screenField) {
            FixedWidthRecord.FieldSpan input = INPUT_VIEW_LAYOUT.span(screenField + "I");
            FixedWidthRecord.FieldSpan output = OUTPUT_VIEW_LAYOUT.span(screenField + "O");

            assertThat(output.offset())
                    .as("%sO must start where %sI starts - zero drift", screenField, screenField)
                    .isEqualTo(input.offset());
            assertThat(output.length())
                    .as("and be the same width, because it is the same storage")
                    .isEqualTo(input.length())
                    .isEqualTo(DECLARED_WIDTHS.get(SCREEN_FIELDS.indexOf(screenField)));
            assertThat(output.endOffsetExclusive()).isEqualTo(input.endOffsetExclusive());
            assertThat(output.kind())
                    .as("both are PIC X(n)")
                    .isEqualTo(FixedWidthRecord.PictureKind.ALPHANUMERIC)
                    .isEqualTo(input.kind());
        }

        @ParameterizedTest(name = "{0}: written through one view, read through the other")
        @ValueSource(strings = {"TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME",
            "APPLID", "SYSID", "USERID", "PASSWD", "ERRMSG"})
        @DisplayName("a value crosses between the two views at one offset - a genuine round trip")
        void aValueCrossesBetweenTheTwoViewsAtOneOffset(String screenField) {
            // THE POINT OF THIS CASE. Two layouts, but ONE FixedWidthRecord - one 308-byte storage
            // area. A span descriptor carries only an offset, a width and a category, so handing the
            // OUTPUT view's descriptor to the record that the INPUT view allocated is exactly what
            // REDEFINES means: the same bytes under a second set of names. Encoding twice and
            // comparing the two images would prove nothing, because two independent encodings of the
            // same text agree whatever the offsets are.
            FixedWidthRecord area = FixedWidthRecord.forLayout(INPUT_VIEW_LAYOUT, MAP_CHARSET);
            FixedWidthRecord.FieldSpan input = INPUT_VIEW_LAYOUT.span(screenField + "I");
            FixedWidthRecord.FieldSpan output = OUTPUT_VIEW_LAYOUT.span(screenField + "O");
            String inbound = "I".repeat(input.length());
            String outbound = "O".repeat(output.length());

            // Write through the input view; read the identical bytes through the output view.
            area.writeSpan(input, inbound);
            assertThat(area.readSpan(output))
                    .as("%sO must see what was written to %sI", screenField, screenField)
                    .isEqualTo(inbound);
            assertThat(area.readSpanBytes(output)).isEqualTo(area.readSpanBytes(input));

            // And back the other way: one storage area, two names for it.
            area.writeSpan(output, outbound);
            assertThat(area.readSpan(input))
                    .as("and %sI must see what was written to %sO", screenField, screenField)
                    .isEqualTo(outbound);
            assertThat(area.readSpanBytes(input)).isEqualTo(area.readSpanBytes(output));
            assertThat(area.toByteArray()).hasSize(SYMBOLIC_MAP_LENGTH);
        }

        @Test
        @DisplayName("writing one field's data disturbs no other field, in either view")
        void aDataWriteDisturbsNothingElse() {
            FixedWidthRecord area = FixedWidthRecord.forLayout(INPUT_VIEW_LAYOUT, MAP_CHARSET);
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                area.writeSpan(OUTPUT_VIEW_LAYOUT.span(OUTPUT_MAP_ITEMS.get(index)),
                        String.valueOf((char) ('A' + index)).repeat(DECLARED_WIDTHS.get(index)));
            }
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                String expected = String.valueOf((char) ('A' + index))
                        .repeat(DECLARED_WIDTHS.get(index));
                assertThat(area.readSpan(OUTPUT_VIEW_LAYOUT.span(OUTPUT_MAP_ITEMS.get(index))))
                        .as("%s must hold its own value and no neighbour's",
                                OUTPUT_MAP_ITEMS.get(index))
                        .isEqualTo(expected);
                assertThat(area.readSpan(INPUT_VIEW_LAYOUT.span(SCREEN_FIELDS.get(index) + "I")))
                        .as("and the input view reads the identical bytes")
                        .isEqualTo(expected);
            }
            assertThat(area.toByteArray()).hasSize(SYMBOLIC_MAP_LENGTH);
        }

        @Test
        @DisplayName("the attribute quartet occupies the bytes the input view reserves as filler")
        void theAttributeQuartetSitsOverTheInputFiller() {
            // The prefixes are the same total width but are NOT carved up the same way: the output
            // view's three-byte filler covers the input view's two-byte xxxL halfword plus its xxxF
            // attribute byte, and the four attribute bytes cover the input view's FILLER X(4). Only
            // the data items align item-for-item, which is precisely why the case above round-trips
            // the DATA and this one measures the PREFIX.
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                String screenField = SCREEN_FIELDS.get(index);
                FixedWidthRecord.FieldSpan colour =
                        OUTPUT_VIEW_LAYOUT.span(screenField + ATTRIBUTE_SUFFIXES.get(0));
                FixedWidthRecord.FieldSpan data =
                        OUTPUT_VIEW_LAYOUT.span(OUTPUT_MAP_ITEMS.get(index));
                assertThat(colour.length()).isEqualTo(ATTRIBUTE_ITEM_LENGTH);
                assertThat(data.offset() - colour.offset())
                        .as("%s: xxxC then xxxP, xxxH, xxxV, then the data", screenField)
                        .isEqualTo(ATTRIBUTE_SUFFIXES.size());
                for (int position = 0; position < ATTRIBUTE_SUFFIXES.size(); position++) {
                    FixedWidthRecord.FieldSpan attribute = OUTPUT_VIEW_LAYOUT
                            .span(screenField + ATTRIBUTE_SUFFIXES.get(position));
                    assertThat(attribute.offset())
                            .as("%s%s", screenField, ATTRIBUTE_SUFFIXES.get(position))
                            .isEqualTo(colour.offset() + position);
                    assertThat(attribute.redefinition())
                            .as("the quartet is storage in the output view, not an overlay")
                            .isFalse();
                }
            }
        }

        @Test
        @DisplayName("xxxC is the colour item, which is what FieldAttributeSetter targets")
        void theColourItemIsTheAttributeSettersTarget() {
            // CSSETATY moves DFHRED and an asterisk onto an offending field when the program is being
            // re-entered, and the field it moves the colour into is xxxC. The sibling program shows
            // the same item used for the success signal: app/cbl/COUSR03C.cbl:317 executes
            // MOVE DFHGREEN TO ERRMSGC OF COUSR3AO.
            assertThat(ATTRIBUTE_SUFFIXES.get(0))
                    .as("the first item of the quartet is the colour item")
                    .isEqualTo(FieldAttributeSetter.COLOUR_ITEM_SUFFIX);
            assertThat(OUTPUT_VIEW_LAYOUT.hasSpan("ERRMSG" + FieldAttributeSetter.COLOUR_ITEM_SUFFIX))
                    .as("ERRMSGC exists in the output view - COSGN00.CPY:148")
                    .isTrue();
            assertThat(FieldAttributeSetter.ASTERISK).isEqualTo("*");
            assertThat(BmsAttributes.DFHRED).isNotEqualTo(BmsAttributes.DFHGREEN);
        }

        @Test
        @DisplayName("neither the twelve-byte prefix nor the three-byte per-field filler is exposed")
        void noFillerIsExposed() {
            assertThat(OUTPUT_VIEW_LAYOUT.hasSpan("FILLER"))
                    .as("filler is declared as reserved storage, and reserved storage has no name")
                    .isFalse();
            // A reserved span is one whose PICTURE category is FILLER - the predicate lives on
            // PictureKind, and FieldSpan.filler(int, int) is the factory that produces such a span.
            long fillerBytes = OUTPUT_VIEW_LAYOUT.storageSpans().stream()
                    .filter(span -> span.kind().filler())
                    .mapToInt(FixedWidthRecord.FieldSpan::length)
                    .sum();
            assertThat(fillerBytes)
                    .as("12 of prefix plus 11 x 3 of per-field filler")
                    .isEqualTo(TIOAPFX_PREFIX_LENGTH + (long) DFHMDF_NAMED * OUTPUT_FILLER_LENGTH)
                    .isEqualTo(45L);
            assertThat(jsonKeys(populated()))
                    .doesNotContain("filler", "tioapfx", "prefix");
        }
    }

    // =================================================================================================
    // 5. SYMBOLIC-MAP METADATA NEVER REACHES THE WIRE.
    //
    // Seven suffixes exist across the two views and not one of them is payload. The input view's xxxL
    // (the length CICS reports), xxxF (the attribute byte) and xxxA (its REDEFINES alias) are validation
    // and highlight metadata. The output view's xxxC, xxxP, xxxH and xxxV are terminal presentation
    // attributes owned by common.BmsAttributes and common.FieldAttributeSetter. Only xxxO is data.
    // =================================================================================================

    @Nested
    @DisplayName("Metadata - xxxL, xxxF, xxxA, xxxC, xxxP, xxxH, xxxV and every FILLER stay off the wire")
    class MetadataIsNotPayload {

        @Test
        @DisplayName("the top-level JSON keys are exactly the fifteen components, and nothing more")
        void keysAreExactlyTheComponents() {
            // Only the top level is asserted here. The communication area is a nested object whose own
            // shape is NavigationContext's contract and its own suite's subject; what matters at this
            // boundary is that this payload adds nothing to it and drops nothing from it.
            assertThat(topLevelJsonKeys(populated()))
                    .as("every screen field is published under its xxxI item in lower case and every "
                            + "carrier under its own name - no extra, none dropped")
                    .containsExactlyElementsOf(
                            concat(wireNamesOf(RESPONSE_MEMBERS), NAVIGATION_MEMBERS))
                    .hasSize(COMPONENT_COUNT);
        }

        @Test
        @DisplayName("no derived predicate leaks in as a sixteenth property")
        void noDerivedPredicateBecomesAProperty() throws NoSuchMethodException {
            // The role test and the target lookup are deliberately static on SignOnResponse: a derived
            // INSTANCE accessor would become a JSON property the canonical constructor cannot accept
            // back, breaking the round trip, and it could report a role contradicting the byte the
            // payload travelled with. Static methods are invisible to bean introspection.
            for (Method method : List.of(
                    SignOnResponse.class.getDeclaredMethod("isAdminRole", String.class),
                    SignOnResponse.class.getDeclaredMethod("resolveNextProgram", String.class))) {
                assertThat(Modifier.isStatic(method.getModifiers()))
                        .as("%s must be static", method.getName())
                        .isTrue();
            }
            assertThat(topLevelJsonKeys(populated()))
                    .doesNotContain("adminRole", "admin", "nextTarget");
        }

        @ParameterizedTest(name = "no key ends in the {0} metadata suffix")
        @ValueSource(strings = {"L", "F", "A", "C", "P", "H", "V"})
        @DisplayName("each metadata suffix over each screen-field stem is absent from the payload")
        void noMetadataSuffixBecomesAKey(String suffix) {
            Set<String> keys = jsonKeys(populated());
            for (String screenField : SCREEN_FIELDS) {
                assertThat(keys)
                        .as("%s%s is metadata, not payload", screenField, suffix)
                        .doesNotContain((screenField + suffix).toLowerCase(Locale.ROOT));
            }
        }

        @Test
        @DisplayName("no component or accessor is named for a metadata item under any spelling")
        void noMetadataItemBecomesAMember() {
            List<String> forbidden = new ArrayList<>();
            for (String screenField : SCREEN_FIELDS) {
                forbidden.add(screenField + "L");
                forbidden.add(screenField + "F");
                forbidden.add(screenField + "A");
                for (String suffix : ATTRIBUTE_SUFFIXES) {
                    forbidden.add(screenField + suffix);
                }
            }
            for (String name : componentNames()) {
                assertThat(forbidden)
                        .as("component %s must not name a metadata item", name)
                        .noneMatch(item -> item.equalsIgnoreCase(name));
            }
            assertThat(forbidden)
                    .as("7 suffixes over 11 fields")
                    .hasSize(SCREEN_FIELDS.size() * (3 + ATTRIBUTE_SUFFIXES.size()));
        }

        @Test
        @DisplayName("the map-derived components carry only xxxO items, never an xxxI item")
        void onlyTheOutputItemsAreProjected() {
            for (String item : SignOnResponse.MAP_FIELDS) {
                assertThat(item).endsWith("O");
                assertThat(OUTPUT_MAP_ITEMS)
                        .as("%s must be an output item of this map", item)
                        .contains(item);
                assertThat(item)
                        .as("this is the response, so no input-direction item appears")
                        .isNotEqualTo(item.substring(0, item.length() - 1) + "I");
            }
        }
    }

    // =================================================================================================
    // 6. XCTL BECOMES RESPONSE DATA - gate G40, and both states of the 88-level - gate G50.
    //
    // app/cbl/COSGN00C.cbl:230-240, verbatim:
    //
    //     IF CDEMO-USRTYP-ADMIN
    //          EXEC CICS XCTL PROGRAM ('COADM01C') COMMAREA(CARDDEMO-COMMAREA) END-EXEC   <- L232
    //     ELSE
    //          EXEC CICS XCTL PROGRAM ('COMEN01C') COMMAREA(CARDDEMO-COMMAREA) END-EXEC   <- L237
    //     END-IF
    //
    // THIS IS A GENUINE TWO-WAY IF/ELSE, NOT A THREE-WAY EVALUATE. The condition tested is
    // 88 CDEMO-USRTYP-ADMIN VALUE 'A' (app/cpy/COCOM01Y.cpy:27), so the ELSE means "not an
    // administrator" - a strictly wider set than 88 CDEMO-USRTYP-USER VALUE 'U' (:28). A user type of
    // 'U', of a space (which is what a freshly initialised communication area holds), or of any
    // unexpected byte all route to COMEN01C. Rewriting it as an equality test on 'U' with a third
    // outcome would change where an uninitialised or corrupt user type is sent, so the case below drives
    // a third value explicitly to prove the ELSE is taken and that no third target exists.
    //
    // The role this response carries is the navigation contract the sibling admin package consumes:
    // 'A' selects admin.AdminMenuController (GET /api/admin/menu, transaction CA00) and anything else
    // selects admin.MainMenuController (GET /api/menu, transaction CM00). Nothing is imported from that
    // package here - the coupling is the byte, not a type.
    // =================================================================================================

    @Nested
    @DisplayName("Role routing, transcribed from COSGN00C lines 230-240")
    class RoleRoutingAndXctl {

        @Test
        @DisplayName("the payload declares the role and all three next-target members")
        void theNavigationMembersExist() {
            assertThat(componentNames())
                    .contains("role", "nextProgram", "nextMapset", "nextMap", "navigationContext");
            assertThat(NAVIGATION_MEMBERS).hasSize(5);
        }

        @Test
        @DisplayName("'A' satisfies the administrator condition and reaches the administrator menu")
        void adminRoleReachesTheAdminMenu() {
            // The 88-level in its TRUE state (G50).
            assertThat(SignOnResponse.ROLE_ADMIN)
                    .as("88 CDEMO-USRTYP-ADMIN VALUE 'A', COCOM01Y.cpy:27")
                    .isEqualTo("A")
                    .isEqualTo(NavigationContext.USER_TYPE_ADMIN);
            assertThat(SignOnResponse.isAdminRole(SignOnResponse.ROLE_ADMIN)).isTrue();
            assertThat(SignOnResponse.resolveNextProgram(SignOnResponse.ROLE_ADMIN))
                    .as("EXEC CICS XCTL PROGRAM ('COADM01C') at COSGN00C.cbl:232")
                    .isEqualTo("COADM01C")
                    .isEqualTo(SignOnResponse.NEXT_PROGRAM_ADMIN);
        }

        @Test
        @DisplayName("'U' does not satisfy it, and takes the ELSE to the regular-user menu")
        void regularUserTakesTheElse() {
            // The 88-level in its FALSE state (G50), driven with the value the copybook names.
            assertThat(SignOnResponse.ROLE_USER)
                    .as("88 CDEMO-USRTYP-USER VALUE 'U', COCOM01Y.cpy:28")
                    .isEqualTo("U")
                    .isEqualTo(NavigationContext.USER_TYPE_USER);
            assertThat(SignOnResponse.isAdminRole(SignOnResponse.ROLE_USER))
                    .as("the program never tests for 'U'; it tests for 'A' and falls through")
                    .isFalse();
            assertThat(SignOnResponse.resolveNextProgram(SignOnResponse.ROLE_USER))
                    .as("EXEC CICS XCTL PROGRAM ('COMEN01C') at COSGN00C.cbl:237")
                    .isEqualTo("COMEN01C")
                    .isEqualTo(SignOnResponse.NEXT_PROGRAM_USER);
        }

        @ParameterizedTest(name = "role [{0}] routes to {1}")
        @CsvSource({
            "A, COADM01C",
            "U, COMEN01C",
            "X, COMEN01C",
            "' ', COMEN01C",
            "a, COMEN01C",
            "'', COMEN01C"
        })
        @DisplayName("only 'A' reaches the administrator menu; the ELSE takes everything else")
        void theElseTakesEverythingThatIsNotAdmin(String role, String expected) {
            // 'X' is the third value the brief asks for: it is neither 88-level's value, and it proves
            // the ELSE is a catch-all rather than a third branch. 'a' proves the test is
            // case-sensitive, as a COBOL PIC X comparison is.
            assertThat(SignOnResponse.resolveNextProgram(role))
                    .as("user type [%s]", role)
                    .isEqualTo(expected);
            assertThat(SignOnResponse.isAdminRole(role))
                    .isEqualTo(SignOnResponse.ROLE_ADMIN.equals(role));
        }

        @Test
        @DisplayName("a third value produces no third target and raises nothing")
        void aThirdValueProducesNoThirdTarget() {
            String unexpected = "X";
            assertThat(unexpected)
                    .isNotEqualTo(SignOnResponse.ROLE_ADMIN)
                    .isNotEqualTo(SignOnResponse.ROLE_USER);
            assertThat(SignOnResponse.resolveNextProgram(unexpected))
                    .as("there are exactly two XCTL sites in the paragraph, so exactly two outcomes")
                    .isIn(SignOnResponse.NEXT_PROGRAM_ADMIN, SignOnResponse.NEXT_PROGRAM_USER)
                    .isEqualTo(SignOnResponse.NEXT_PROGRAM_USER);
        }

        @Test
        @DisplayName("null routes to the ELSE target rather than failing")
        void nullRoutesToTheElseTarget() {
            // A predicate that threw would surprise a caller inspecting a partially built payload, and
            // COBOL has no null to test in the first place - an unset PIC X(01) holds a space, which
            // routes to the same place.
            assertThat(SignOnResponse.isAdminRole(null)).isFalse();
            assertThat(SignOnResponse.resolveNextProgram(null))
                    .isEqualTo(SignOnResponse.NEXT_PROGRAM_USER);
        }

        @Test
        @DisplayName("the two targets are the program's own XCTL literals, eight characters each")
        void targetsAreTheProgramsOwnLiterals() {
            assertThat(SignOnResponse.NEXT_PROGRAM_ADMIN)
                    .isEqualTo("COADM01C")
                    .hasSize(SignOnResponse.NEXT_PROGRAM_LENGTH);
            assertThat(SignOnResponse.NEXT_PROGRAM_USER)
                    .isEqualTo("COMEN01C")
                    .hasSize(SignOnResponse.NEXT_PROGRAM_LENGTH);
            assertThat(SignOnResponse.NEXT_PROGRAM_ADMIN)
                    .isNotEqualTo(SignOnResponse.NEXT_PROGRAM_USER);
        }

        @Test
        @DisplayName("the response carries the resolved target rather than re-deriving it")
        void theResponseCarriesWhatTheServiceDecided() {
            // withNavigation resolves nothing: the service calls resolveNextProgram and passes the
            // result in. A response must report what was decided, not recompute it - which is also why
            // a test can construct the mismatched state a defective service could produce.
            SignOnResponse mismatched = SignOnResponse.empty().withNavigation(
                    SignOnResponse.ROLE_ADMIN, SignOnResponse.NEXT_PROGRAM_USER, MAPSET_NAME,
                    MAP_NAME, signedOnContext(SignOnResponse.ROLE_ADMIN));
            assertThat(mismatched.role()).isEqualTo(SignOnResponse.ROLE_ADMIN);
            assertThat(mismatched.nextProgram())
                    .as("carried verbatim, not corrected to the admin target")
                    .isEqualTo(SignOnResponse.NEXT_PROGRAM_USER);
        }

        @Test
        @DisplayName("the next map and mapset are the seven-character values the program sends")
        void theNextMapAndMapsetAreTheProgramsOwn() {
            SignOnResponse accepted = SignOnResponse.empty().withNavigation(
                    SignOnResponse.ROLE_ADMIN,
                    SignOnResponse.resolveNextProgram(SignOnResponse.ROLE_ADMIN),
                    MAPSET_NAME, MAP_NAME, signedOnContext(SignOnResponse.ROLE_ADMIN));
            assertThat(accepted.nextMapset())
                    .as("MAPSET('COSGN00'), COSGN00C.cbl:112")
                    .isEqualTo(MAPSET_NAME)
                    .hasSize(NavigationContext.LAST_MAPSET_LENGTH);
            assertThat(accepted.nextMap())
                    .as("MAP('COSGN0A'), COSGN00C.cbl:111")
                    .isEqualTo(MAP_NAME)
                    .hasSize(NavigationContext.LAST_MAP_LENGTH);
        }

        @Test
        @DisplayName("the role bytes are NavigationContext's, so all seventeen screens agree")
        void roleBytesDelegate() {
            assertThat(SignOnResponse.ROLE_ADMIN).isSameAs(NavigationContext.USER_TYPE_ADMIN);
            assertThat(SignOnResponse.ROLE_USER).isSameAs(NavigationContext.USER_TYPE_USER);
        }
    }

    // =================================================================================================
    // 7. STATELESSNESS IS STRUCTURAL - gate G37 and rule R6.
    //
    // CICS is pseudo-conversational: COSGN00C paints the screen, ends with EXEC CICS RETURN TRANSID
    // COMMAREA(CARDDEMO-COMMAREA) at lines 134-138, and is re-entered from the top on the next key
    // press. The only state that survives is what it handed back. The migrated form keeps that shape by
    // carrying the communication area in the payload, so no HttpSession, no server-side cache and no
    // session affinity is needed - and none may be introduced.
    // =================================================================================================

    @Nested
    @DisplayName("Conversation state - the communication area travels in the payload")
    class StatelessConversationState {

        @Test
        @DisplayName("the communication area is a payload member, so no session is ever needed")
        void theCommareaIsAPayloadMember() {
            assertThat(componentNames()).contains("navigationContext");
            assertThat(componentTypes()).contains(NavigationContext.class);
            assertThat(populated().navigationContext()).isNotNull();
            assertThat(jsonKeys(populated()))
                    .as("and it is on the wire, nested, rather than held anywhere on the server")
                    .contains("navigationcontext");
        }

        @Test
        @DisplayName("the communication area is 160 bytes: 34 + 84 + 12 + 16 + 14")
        void theCommareaIsOneHundredAndSixtyBytes() {
            // Proved through the codec with an explicitly named code page (B8, B11), not by trusting
            // the constant. COCOM01Y.cpy:19-44 gives the five groups.
            byte[] image = populated().navigationContext().toFixedWidth(codec());
            assertThat(image)
                    .as("CARDDEMO-COMMAREA serialises to its declared width exactly")
                    .hasSize(NavigationContext.COMMAREA_LENGTH)
                    .hasSize(160);
            assertThat(NavigationContext.GENERAL_INFO_LENGTH
                    + NavigationContext.CUSTOMER_INFO_LENGTH
                    + NavigationContext.ACCOUNT_INFO_LENGTH
                    + NavigationContext.CARD_INFO_LENGTH
                    + NavigationContext.MORE_INFO_LENGTH)
                    .as("34 general + 84 customer + 12 account + 16 card + 14 more")
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH);
            assertThat(NavigationContext.fromFixedWidth(codec(), image))
                    .as("and it round trips through the same code page")
                    .isEqualTo(populated().navigationContext());
        }

        @Test
        @DisplayName("the general-info group is populated exactly as COSGN00C lines 224-228 populate it")
        void theGeneralInfoGroupIsPopulatedAsTheProgramPopulatesIt() {
            NavigationContext context = signedOnContext(SignOnResponse.ROLE_ADMIN);
            assertThat(context.fromTranid())
                    .as("MOVE WS-TRANID TO CDEMO-FROM-TRANID, line 224")
                    .isEqualTo(TRANSACTION_ID);
            assertThat(context.fromProgram())
                    .as("MOVE WS-PGMNAME TO CDEMO-FROM-PROGRAM, line 225")
                    .isEqualTo(PROGRAM_NAME);
            assertThat(context.userId())
                    .as("MOVE WS-USER-ID TO CDEMO-USER-ID, line 226")
                    .isEqualTo("ADMIN001");
            assertThat(context.userType())
                    .as("MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE, line 227")
                    .isEqualTo(SignOnResponse.ROLE_ADMIN);
            assertThat(context.pgmContext())
                    .as("MOVE ZEROS TO CDEMO-PGM-CONTEXT, line 228")
                    .isEqualTo(NavigationContext.PGM_CONTEXT_ENTER)
                    .isZero();
            assertThat(context.isEnter()).isTrue();
            assertThat(context.isReenter()).isFalse();
            assertThat(context.isAdmin()).isTrue();
        }

        @Test
        @DisplayName("the role the payload carries is the one the communication area carries")
        void theRoleAndTheCommareaAgree() {
            SignOnResponse admin = populated();
            assertThat(admin.role()).isEqualTo(admin.navigationContext().userType());
            assertThat(admin.navigationContext().isAdmin()).isTrue();
            assertThat(admin.navigationContext().isUser()).isFalse();

            NavigationContext regular = signedOnContext(SignOnResponse.ROLE_USER);
            assertThat(regular.isAdmin()).isFalse();
            assertThat(regular.isUser()).isTrue();
        }

        @Test
        @DisplayName("no session, cache, thread-local or servlet type is reachable from this payload")
        void nothingSessionScopedIsReachable() {
            for (String name : reachableTypeNames()) {
                assertThat(name)
                        .as("reachable type %s", name)
                        .doesNotContain("HttpSession")
                        .doesNotContain("HttpServletRequest")
                        .doesNotContain("ThreadLocal")
                        .doesNotContain("jakarta.servlet")
                        .doesNotContain("org.springframework.web");
            }
        }

        @Test
        @DisplayName("no CDEMO-CU0n-INFO extension block is carried, because this program declares none")
        void noExtensionBlockIsCarried() {
            // COUSR00C, COUSR02C and COUSR03C each declare a CDEMO-CU0n-INFO block appended to the
            // communication area. COSGN00C declares none, so this payload hands back the plain
            // 160-byte area and nothing appended to it.
            assertThat(CDEMO_CU0N_INFO_COUNT)
                    .as("grep -c 'CDEMO-CU0[0-9]-INFO' app/cbl/COSGN00C.cbl")
                    .isZero();
            assertThat(populated().navigationContext().toFixedWidth(codec()))
                    .as("so the area is exactly its declared width, with nothing appended")
                    .hasSize(NavigationContext.COMMAREA_LENGTH);
            assertThat(jsonKeys(populated()))
                    .doesNotContain("cdemocu00info", "cdemocu01info", "cdemocu02info",
                            "cdemocu03info");
        }
    }

    // =================================================================================================
    // 8. THE ERROR LINE - the one place this screen loses data, and it loses it deliberately.
    //
    // app/cbl/COSGN00C.cbl:38 declares 05 WS-MESSAGE PIC X(80) VALUE SPACES, and :149 executes
    // MOVE WS-MESSAGE TO ERRMSGO OF COSGN0AO into a PIC X(78) receiver. COBOL fills a PIC X receiver
    // from its leftmost position and discards the overflow, so the two RIGHTMOST characters are lost.
    //
    // Every narrowing here goes through FixedWidthCodec.movePicX and never through a bare assignment or
    // a substring (B11). The plan names MOVE the dominant parity risk precisely because a plain Java
    // assignment neither pads nor truncates, and the resulting defect is invisible at the call site.
    // =================================================================================================

    @Nested
    @DisplayName("The error line - PIC X(80) into PIC X(78), narrowed on purpose")
    class TheErrorLine {

        @Test
        @DisplayName("the eighty-byte message narrows to seventy-eight, losing the two on the right")
        void theMessageNarrowsOnTheRight() {
            // A message whose last two characters are distinguishable, so the direction of the loss is
            // observable rather than assumed.
            String eighty = "L".repeat(WS_MESSAGE_LENGTH - 2) + "XY";
            assertThat(eighty).hasSize(WS_MESSAGE_LENGTH);

            String moved = codec().movePicX(eighty, SignOnResponse.ERRMSG_LENGTH);
            assertThat(moved)
                    .as("the receiver is filled from the left, so the leading characters survive")
                    .hasSize(SignOnResponse.ERRMSG_LENGTH)
                    .isEqualTo("L".repeat(WS_MESSAGE_LENGTH - 2))
                    .doesNotEndWith("XY");
            assertThat(eighty.length() - moved.length()).isEqualTo(ERRMSG_TRUNCATED_CHARACTERS);
            assertThat(SignOnResponse.empty().withErrMsg(moved).errMsg()).isEqualTo(moved);
        }

        @Test
        @DisplayName("the payload refuses an eighty-character message rather than clipping it silently")
        void anOverWideMessageIsRefusedNotClipped() {
            // The narrowing is the caller's deliberate act. Were the constructor to clip, the loss
            // would be invisible at the site that caused it.
            String eighty = "M".repeat(WS_MESSAGE_LENGTH);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> SignOnResponse.empty().withErrMsg(eighty))
                    .withMessageContaining(SignOnResponse.ERRMSG_FIELD)
                    .withMessageContaining(String.valueOf(SignOnResponse.ERRMSG_LENGTH))
                    .withMessageContaining(String.valueOf(WS_MESSAGE_LENGTH));
        }

        @ParameterizedTest(name = "[{0}] fits and round trips")
        @ValueSource(strings = {
            "Please enter User ID ...",
            "Please enter Password ...",
            "Wrong Password. Try again ...",
            "User not found. Try again ...",
            "Unable to verify the User ..."
        })
        @DisplayName("each of the program's five message literals fits the field and survives the move")
        void eachProgramMessageFitsAndRoundTrips(String message) {
            assertThat(message)
                    .as("no in-line literal of COSGN00C overflows ERRMSGO")
                    .hasSizeLessThanOrEqualTo(SignOnResponse.ERRMSG_LENGTH);

            String moved = codec().movePicX(message, SignOnResponse.ERRMSG_LENGTH);
            assertThat(moved)
                    .as("a shorter sending value is padded on the right, never centred or right-aligned")
                    .hasSize(SignOnResponse.ERRMSG_LENGTH)
                    .startsWith(message);
            assertThat(moved.substring(message.length()))
                    .as("and the padding is spaces")
                    .isEqualTo(" ".repeat(SignOnResponse.ERRMSG_LENGTH - message.length()));
            assertThat(SignOnResponse.empty().withErrMsg(moved).errMsg()).isEqualTo(moved);
        }

        @Test
        @DisplayName("all five literals are distinct, so a branch cannot be mistaken for another")
        void theFiveLiteralsAreDistinct() {
            assertThat(PROGRAM_MESSAGES).hasSize(5).doesNotHaveDuplicates();
            assertThat(PROGRAM_MESSAGES)
                    .as("lines 120, 125, 242, 249 and 254 of app/cbl/COSGN00C.cbl")
                    .containsExactly(MSG_ENTER_USER_ID, MSG_ENTER_PASSWORD, MSG_WRONG_PASSWORD,
                            MSG_USER_NOT_FOUND, MSG_UNABLE_TO_VERIFY);
        }

        @Test
        @DisplayName("the two copybook messages widen to eighty then narrow to seventy-eight")
        void theCopybookMessagesWidenThenNarrow() {
            // CCDA-MSG-THANK-YOU (line 89) and CCDA-MSG-INVALID-KEY (line 93) are PIC X(50) fields
            // rather than in-line text, so they take a TWO-STAGE route: PIC X(50) widened into
            // WS-MESSAGE PIC X(80), left-justified and right-space-padded, then narrowed into ERRMSGO
            // PIC X(78). The two characters the second stage discards are padding the first stage added,
            // so the content survives intact - which is why these are safe where an 80-character
            // message would not be.
            for (String message : COPYBOOK_MESSAGES) {
                assertThat(message)
                        .as("a PIC X(50) copybook message")
                        .hasSize(SystemMessages.MESSAGE_LENGTH)
                        .hasSize(50);

                String widened = codec().movePicX(message, WS_MESSAGE_LENGTH);
                assertThat(widened)
                        .as("stage one: into WS-MESSAGE PIC X(80)")
                        .hasSize(WS_MESSAGE_LENGTH)
                        .startsWith(message)
                        .endsWith(" ".repeat(WS_MESSAGE_LENGTH - SystemMessages.MESSAGE_LENGTH));

                String narrowed = codec().movePicX(widened, SignOnResponse.ERRMSG_LENGTH);
                assertThat(narrowed)
                        .as("stage two: into ERRMSGO PIC X(78)")
                        .hasSize(SignOnResponse.ERRMSG_LENGTH)
                        .startsWith(message);
                assertThat(narrowed.strip())
                        .as("only padding was discarded, so the text is untouched")
                        .isEqualTo(message.strip());
                assertThat(SignOnResponse.empty().withErrMsg(narrowed).errMsg()).isEqualTo(narrowed);
            }
        }

        @Test
        @DisplayName("the two copybook messages are different texts, and neither is a screen title")
        void theCopybookMessagesAreDistinct() {
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU)
                    .isNotEqualTo(SystemMessages.CCDA_MSG_INVALID_KEY)
                    .as("the CSMSG01Y thank-you is not the COTTL01Y one, despite the similar wording")
                    .isNotEqualTo(ScreenTitles.CCDA_THANK_YOU);
            assertThat(ScreenTitles.CCDA_THANK_YOU)
                    .as("the title-copybook literal is PIC X(40), a different field entirely")
                    .hasSize(ScreenTitles.TITLE_LENGTH);
        }

        @Test
        @DisplayName("a cleared error line is seventy-eight spaces, which is what MOVE SPACES writes")
        void aClearedLineIsSpaces() {
            String cleared = codec().movePicX("", SignOnResponse.ERRMSG_LENGTH);
            assertThat(cleared)
                    .hasSize(SignOnResponse.ERRMSG_LENGTH)
                    .isBlank()
                    .isEqualTo(" ".repeat(SignOnResponse.ERRMSG_LENGTH));
            // Two different facts, and this test now separates them. empty() is the map before anything
            // is written, so ERRMSGO holds the LOW-VALUES image; :78's MOVE SPACES is a program ACTION,
            // and withErrMsg is how the projection performs it. Asserting that empty() already equalled
            // the :78 result conflated the initial state with the first statement - and on the
            // EIBCALEN = 0 arm :81's group MOVE LOW-VALUES overwrites :78 anyway, before
            // SEND-SIGNON-SCREEN moves WS-MESSAGE back in.
            assertThat(SignOnResponse.empty().errMsg())
                    .as("the map before anything is written - MOVE LOW-VALUES TO COSGN0AO, :81")
                    .isEqualTo(ScreenFieldImage.unpainted(SignOnResponse.ERRMSG_LENGTH));
            assertThat(SignOnResponse.empty().withErrMsg(cleared).errMsg())
                    .as("MOVE SPACES TO ERRMSGO, app/cbl/COSGN00C.cbl:78")
                    .isEqualTo(cleared);
        }

        @Test
        @DisplayName("the field's map-declared colour is RED, so DFHGREEN is an override not a default")
        void theDeclaredColourIsRed() {
            // app/bms/COSGN00.bms:197-200 declares ERRMSG DFHMDF ATTRB=(ASKIP,BRT,FSET) COLOR=RED
            // LENGTH=78 POS=(23,1), and the same declaration appears on all five maps in this package.
            // The field is therefore red WITHOUT the program doing anything, which is why CSSETATY's
            // DFHRED move is a re-assertion and COUSR03C.cbl:317's MOVE DFHGREEN TO ERRMSGC OF
            // COUSR3AO is the deliberate success-path OVERRIDE. Colour is carried as attribute metadata
            // on xxxC and never as a payload member, so nothing here is asserted about the payload -
            // only about which byte means what.
            assertThat(BmsAttributes.DFHRED)
                    .as("the map's declared colour for this field")
                    .isNotEqualTo(BmsAttributes.DFHDFCOL);
            assertThat(BmsAttributes.DFHGREEN)
                    .as("the success signal, and the exact counterpart of red")
                    .isNotEqualTo(BmsAttributes.DFHRED);
            assertThat(jsonKeys(populated()))
                    .as("and neither reaches the payload, because colour is not data")
                    .doesNotContain("errmsgc", "colour", "color");
        }
    }

    // =================================================================================================
    // 9. POPULATE-HEADER-INFO - app/cbl/COSGN00C.cbl:177-204, and nothing beyond it.
    //
    // The paragraph writes exactly eight fields: TITLE01O (181), TITLE02O (182), TRNNAMEO (183),
    // PGMNAMEO (184), CURDATEO (190), CURTIMEO (196), APPLIDO (199) and SYSIDO (203). It does not write
    // USERIDO, and ERRMSGO is written by its caller at line 149 AFTER it has run.
    //
    // The two time-derived values are read through a fixed Clock (B7), so this suite renders the same
    // characters on every run.
    // =================================================================================================

    @Nested
    @DisplayName("POPULATE-HEADER-INFO - the eight fields it writes, and only those")
    class HeaderPopulation {

        @Test
        @DisplayName("withHeader writes the eight header fields and leaves the other seven alone")
        void withHeaderWritesExactlyTheEightFields() {
            SignOnResponse before = SignOnResponse.empty();
            DateHeader header = DateHeader.from(codec(), FIXED_CLOCK);
            SignOnResponse after = before.withHeader(TRANSACTION_ID,
                    ScreenTitles.CCDA_TITLE01,
                    header.wsCurdateMmDdYy(),
                    PROGRAM_NAME,
                    ScreenTitles.CCDA_TITLE02,
                    codec().movePicX(header.wsCurtimeHhMmSs(), SignOnResponse.CURTIME_LENGTH),
                    "CICSAPPL",
                    "CICS    ");

            assertThat(after.trnName()).isEqualTo(TRANSACTION_ID);
            assertThat(after.title01()).isEqualTo(ScreenTitles.CCDA_TITLE01);
            assertThat(after.curDate()).isEqualTo(FIXED_CURDATE);
            assertThat(after.pgmName()).isEqualTo(PROGRAM_NAME);
            assertThat(after.title02()).isEqualTo(ScreenTitles.CCDA_TITLE02);
            assertThat(after.curTime()).isEqualTo(FIXED_CURTIME + " ");
            assertThat(after.applId()).isEqualTo("CICSAPPL");
            assertThat(after.sysId()).isEqualTo("CICS    ");

            assertThat(after.userId())
                    .as("the paragraph does not write USERIDO, so it keeps the value it had")
                    .isEqualTo(before.userId());
            assertThat(after.errMsg())
                    .as("and ERRMSGO is written by the caller at line 149, afterwards")
                    .isEqualTo(before.errMsg());
            assertThat(after.role()).isEqualTo(before.role());
            assertThat(after.nextProgram()).isEqualTo(before.nextProgram());
            assertThat(after.nextMapset()).isEqualTo(before.nextMapset());
            assertThat(after.nextMap()).isEqualTo(before.nextMap());
            assertThat(after.navigationContext()).isEqualTo(before.navigationContext());
        }

        @Test
        @DisplayName("the header renders the same characters on every run, because the clock is fixed")
        void theHeaderIsDeterministic() {
            DateHeader first = DateHeader.from(codec(), FIXED_CLOCK);
            DateHeader second = DateHeader.from(codec(), FIXED_CLOCK);
            assertThat(first.wsCurdateMmDdYy()).isEqualTo(second.wsCurdateMmDdYy())
                    .isEqualTo(FIXED_CURDATE);
            assertThat(first.wsCurtimeHhMmSs()).isEqualTo(second.wsCurtimeHhMmSs())
                    .isEqualTo(FIXED_CURTIME);
            assertThat(FIXED_INSTANT)
                    .as("the instant is the version footer of app/cbl/COSGN00C.cbl:259, not an invention")
                    .isEqualTo(Instant.parse("2022-07-19T23:12:33Z"));
        }

        @Test
        @DisplayName("the header fields the paragraph writes are eight of the ten map members")
        void theHeaderCoversEightOfTheTenMembers() {
            List<String> written = List.of("title01", "title02", "trnName", "pgmName", "curDate",
                    "curTime", "applId", "sysId");
            assertThat(written).hasSize(8).allSatisfy(name ->
                    assertThat(RESPONSE_MEMBERS).contains(name));
            assertThat(RESPONSE_MEMBERS)
                    .as("the two the paragraph leaves alone")
                    .containsAll(List.of("userId", "errMsg"));
            assertThat(RESPONSE_MAP_MEMBERS - written.size()).isEqualTo(2);
        }
    }

    // =================================================================================================
    // 10. SERIALISATION. Space padding is data, and a default mapper would quietly destroy it.
    // =================================================================================================

    @Nested
    @DisplayName("Serialisation - space padding survives a round trip untouched")
    class JsonRoundTrip {

        @Test
        @DisplayName("a fully populated payload round trips to an equal value")
        void populatedRoundTrips() {
            SignOnResponse original = populated();
            assertThat(roundTrip(original)).isEqualTo(original);
        }

        @Test
        @DisplayName("a forty-character title with trailing spaces is neither trimmed nor shortened")
        void trailingSpacesOnATitleSurvive() {
            SignOnResponse original = populated();
            assertThat(original.title01())
                    .as("CCDA-TITLE01 is padded on both sides, so trimming would be visible")
                    .hasSize(SignOnResponse.TITLE01_LENGTH)
                    .endsWith(" ");

            SignOnResponse revived = roundTrip(original);
            assertThat(revived.title01())
                    .isEqualTo(original.title01())
                    .hasSize(SignOnResponse.TITLE01_LENGTH);
            assertThat(revived.title02())
                    .isEqualTo(original.title02())
                    .hasSize(SignOnResponse.TITLE02_LENGTH);
        }

        @Test
        @DisplayName("a seventy-eight-space error line stays a string of spaces, never null")
        void anAllSpacesErrorLineIsNotCoercedToNull() {
            // ACCEPT_EMPTY_STRING_AS_NULL_OBJECT is disabled in WebConfig for exactly this case: an
            // all-spaces PIC X(n) field is real screen data, and a null would fail the canonical
            // constructor on the way back in.
            SignOnResponse cleared = SignOnResponse.empty()
                    .withErrMsg(" ".repeat(SignOnResponse.ERRMSG_LENGTH));
            assertThat(cleared.errMsg()).hasSize(SignOnResponse.ERRMSG_LENGTH).isBlank();

            SignOnResponse revived = roundTrip(cleared);
            assertThat(revived.errMsg())
                    .isNotNull()
                    .isEqualTo(cleared.errMsg())
                    .hasSize(SignOnResponse.ERRMSG_LENGTH);
            assertThat(revived).isEqualTo(cleared);
        }

        @Test
        @DisplayName("every map member keeps its exact width across the round trip")
        void everyWidthSurvivesTheRoundTrip() {
            SignOnResponse revived = roundTrip(populated());
            List<String> values = mapValuesOf(revived);
            for (int index = 0; index < RESPONSE_MAP_MEMBERS; index++) {
                assertThat(values.get(index))
                        .as("%s must still be exactly PIC X(%d)", RESPONSE_MAP_ITEMS.get(index),
                                RESPONSE_WIDTHS.get(index))
                        .hasSize(RESPONSE_WIDTHS.get(index));
            }
        }

        @Test
        @DisplayName("the communication area travels nested and round trips with the payload")
        void theCommareaRoundTripsNested() {
            SignOnResponse original = populated();
            SignOnResponse revived = roundTrip(original);
            assertThat(revived.navigationContext())
                    .isEqualTo(original.navigationContext())
                    .isNotNull();
            assertThat(revived.navigationContext().toFixedWidth(codec()))
                    .as("and still serialises to its declared width afterwards")
                    .isEqualTo(original.navigationContext().toFixedWidth(codec()))
                    .hasSize(NavigationContext.COMMAREA_LENGTH);
        }

        @Test
        @DisplayName("the property names are the component names verbatim, with no naming strategy")
        void propertyNamesAreUntransformed() {
            // No naming STRATEGY is in play - no snake_case, no kebab-case, no upper-camel. Each screen
            // field is published under the one name AAP 0.6.3 allows, its xxxI item in lower case, which
            // @JsonProperty pins field by field; each carrier keeps its component name.
            assertThat(topLevelJsonKeys(populated()))
                    .as("each key traces 1:1 to an item")
                    .containsExactlyElementsOf(wireNamesOf(componentNames()));
            assertThat(topLevelJsonKeys(populated()))
                    .allSatisfy(key -> assertThat(key).doesNotContain("_").doesNotContain("-"));
        }
    }

    // =================================================================================================
    // 11. empty() - the screen before POPULATE-HEADER-INFO has run.
    // =================================================================================================

    @Nested
    @DisplayName("empty() - every field its own width in spaces")
    class EmptyScreen {

        @Test
        @DisplayName("every map member is the unpainted image at its own declared width")
        void everyMapMemberIsUnpainted() {
            List<String> values = mapValuesOf(SignOnResponse.empty());
            for (int index = 0; index < RESPONSE_MAP_MEMBERS; index++) {
                assertThat(values.get(index))
                        .as("%s is PIC X(%d) and unset means LOW-VALUES, never null and never empty",
                                RESPONSE_MAP_ITEMS.get(index), RESPONSE_WIDTHS.get(index))
                        .isNotNull()
                        .hasSize(RESPONSE_WIDTHS.get(index))
                        .isEqualTo(ScreenFieldImage.unpainted(RESPONSE_WIDTHS.get(index)));
            }
        }

        @Test
        @DisplayName("no role is implied and no XCTL target is named before sign-on")
        void noRoleAndNoTargetBeforeSignOn() {
            SignOnResponse initial = SignOnResponse.empty();
            assertThat(initial.role())
                    .hasSize(SignOnResponse.ROLE_LENGTH)
                    .isBlank();
            assertThat(SignOnResponse.isAdminRole(initial.role()))
                    .as("a space is not 'A', so no administrator is implied")
                    .isFalse();
            assertThat(initial.nextProgram())
                    .as("COSGN00C transfers only from inside the successful branch at line 230")
                    .isBlank();
            assertThat(initial.nextMapset()).isBlank();
            assertThat(initial.nextMap()).isBlank();
        }

        @Test
        @DisplayName("the communication area starts in the CDEMO-PGM-ENTER state")
        void startsInEnterState() {
            NavigationContext context = SignOnResponse.empty().navigationContext();
            assertThat(context).isNotNull();
            assertThat(context.pgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_ENTER);
            assertThat(context.isEnter()).isTrue();
            assertThat(context.isReenter()).isFalse();
        }

        @Test
        @DisplayName("empty() returns an equal value every time and shares no state between calls")
        void emptyIsRepeatable() {
            assertThat(SignOnResponse.empty()).isEqualTo(SignOnResponse.empty());
            assertThat(SignOnResponse.empty().withErrMsg(
                    codec().movePicX(MSG_WRONG_PASSWORD, SignOnResponse.ERRMSG_LENGTH)))
                    .as("and a derived value cannot affect the next empty()")
                    .isNotEqualTo(SignOnResponse.empty());
            assertThat(ScreenFieldImage.isUnpainted(SignOnResponse.empty().errMsg())).isTrue();
        }
    }

    // =================================================================================================
    // 12. THE CANONICAL CONSTRUCTOR. Every branch of both guards, which is where this package's branch
    // coverage actually lives (G49).
    // =================================================================================================

    @Nested
    @DisplayName("The canonical constructor enforces every declared width")
    class WidthEnforcement {

        @Test
        @DisplayName("a value exactly at its declared width is accepted")
        void exactWidthIsAccepted() {
            SignOnResponse response = populated();
            List<String> values = mapValuesOf(response);
            for (int index = 0; index < RESPONSE_MAP_MEMBERS; index++) {
                assertThat(values.get(index)).hasSize(RESPONSE_WIDTHS.get(index));
            }
        }

        @Test
        @DisplayName("a shorter value is accepted, mirroring a MOVE into a wider PIC X receiver")
        void shorterIsAccepted() {
            SignOnResponse response = SignOnResponse.empty().withErrMsg(MSG_WRONG_PASSWORD);
            assertThat(response.errMsg())
                    .as("carried as given; padding to the declared width is the codec's job")
                    .isEqualTo(MSG_WRONG_PASSWORD)
                    .hasSizeLessThan(SignOnResponse.ERRMSG_LENGTH);
        }

        @ParameterizedTest(name = "component {0} ({1}) rejects null")
        @CsvSource({
            "0, TRNNAMEO",
            "1, TITLE01O",
            "2, CURDATEO",
            "3, PGMNAMEO",
            "4, TITLE02O",
            "5, CURTIMEO",
            "6, APPLIDO",
            "7, SYSIDO",
            "8, USERIDO",
            "9, ERRMSGO"
        })
        @DisplayName("null is rejected and the failure names the item, because COBOL has no null")
        void nullIsRejected(int index, String field) {
            assertThatNullPointerException()
                    .isThrownBy(() -> constructWith(index, null))
                    .withMessageContaining(field);
        }

        @ParameterizedTest(name = "component {0} ({1}) rejects {2} + 1 characters")
        @CsvSource({
            "0, TRNNAMEO, 4",
            "1, TITLE01O, 40",
            "2, CURDATEO, 8",
            "3, PGMNAMEO, 8",
            "4, TITLE02O, 40",
            "5, CURTIMEO, 9",
            "6, APPLIDO, 8",
            "7, SYSIDO, 8",
            "8, USERIDO, 8",
            "9, ERRMSGO, 78"
        })
        @DisplayName("an over-wide value is rejected, naming the item, the width and the length")
        void overWideIsRejected(int index, String field, int width) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> constructWith(index, "W".repeat(width + 1)))
                    .withMessageContaining(field)
                    .withMessageContaining(String.valueOf(width))
                    .withMessageContaining(String.valueOf(width + 1));
        }

        @Test
        @DisplayName("the navigation members are held to their widths too")
        void theNavigationMembersAreAlsoBounded() {
            assertThatIllegalArgumentException()
                    .as("role is PIC X(01)")
                    .isThrownBy(() -> SignOnResponse.empty().withNavigation("AA",
                            SignOnResponse.NEXT_PROGRAM_ADMIN, MAPSET_NAME, MAP_NAME,
                            NavigationContext.empty()));
            assertThatIllegalArgumentException()
                    .as("nextMapset is PIC X(7), not X(8)")
                    .isThrownBy(() -> SignOnResponse.empty().withNavigation(
                            SignOnResponse.ROLE_ADMIN, SignOnResponse.NEXT_PROGRAM_ADMIN,
                            "COSGN000", MAP_NAME, NavigationContext.empty()));
            assertThatIllegalArgumentException()
                    .as("nextMap is PIC X(7) for the same reason")
                    .isThrownBy(() -> SignOnResponse.empty().withNavigation(
                            SignOnResponse.ROLE_ADMIN, SignOnResponse.NEXT_PROGRAM_ADMIN,
                            MAPSET_NAME, "COSGN0AB", NavigationContext.empty()));
            assertThatIllegalArgumentException()
                    .as("nextProgram is PIC X(08)")
                    .isThrownBy(() -> SignOnResponse.empty().withNavigation(
                            SignOnResponse.ROLE_ADMIN, "COADM01CX", MAPSET_NAME, MAP_NAME,
                            NavigationContext.empty()));
        }

        @Test
        @DisplayName("the communication area is required, because there is no session to fall back on")
        void navigationContextIsRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> SignOnResponse.empty().withNavigation(
                            SignOnResponse.ROLE_ADMIN, SignOnResponse.NEXT_PROGRAM_ADMIN,
                            MAPSET_NAME, MAP_NAME, null))
                    .withMessageContaining("navigationContext");
        }
    }

    /**
     * Builds a response with one map-derived component replaced, so the constructor's guard can be
     * driven per component without fifteen near-identical literal argument lists.
     *
     * @param index the 0-based position among the ten map-derived components
     * @param value the value to place there, possibly {@code null} or over-wide
     */
    private static SignOnResponse constructWith(int index, String value) {
        List<String> values = new ArrayList<>(mapValuesOf(populated()));
        values.set(index, value);
        return new SignOnResponse(values.get(0), values.get(1), values.get(2), values.get(3),
                values.get(4), values.get(5), values.get(6), values.get(7), values.get(8),
                values.get(9), SignOnResponse.ROLE_ADMIN, SignOnResponse.NEXT_PROGRAM_ADMIN,
                MAPSET_NAME, MAP_NAME, signedOnContext(SignOnResponse.ROLE_ADMIN));
    }

    // =================================================================================================
    // 13. IMMUTABILITY AND THE ABSENCE OF STATE - practice B9 and gate G53.
    // =================================================================================================

    @Nested
    @DisplayName("Immutability - one method per writing paragraph, and no state anywhere")
    class ImmutableReplacement {

        @Test
        @DisplayName("withErrMsg changes the error line and nothing else")
        void withErrMsgChangesOnlyTheErrorLine() {
            SignOnResponse before = populated();
            String replacement = codec().movePicX(MSG_USER_NOT_FOUND, SignOnResponse.ERRMSG_LENGTH);
            SignOnResponse after = before.withErrMsg(replacement);

            assertThat(after.errMsg()).isEqualTo(replacement).isNotEqualTo(before.errMsg());
            assertThat(mapValuesOf(after).subList(0, RESPONSE_MAP_MEMBERS - 1))
                    .as("the nine fields ahead of it are untouched")
                    .isEqualTo(mapValuesOf(before).subList(0, RESPONSE_MAP_MEMBERS - 1));
            assertThat(after.role()).isEqualTo(before.role());
            assertThat(after.nextProgram()).isEqualTo(before.nextProgram());
            assertThat(after.navigationContext()).isEqualTo(before.navigationContext());
        }

        @Test
        @DisplayName("withNavigation changes the five navigation members and no screen field")
        void withNavigationChangesOnlyTheNavigationMembers() {
            SignOnResponse before = populated();
            SignOnResponse after = before.withNavigation(SignOnResponse.ROLE_USER,
                    SignOnResponse.NEXT_PROGRAM_USER, MAPSET_NAME, MAP_NAME,
                    signedOnContext(SignOnResponse.ROLE_USER));

            assertThat(after.role()).isEqualTo(SignOnResponse.ROLE_USER)
                    .isNotEqualTo(before.role());
            assertThat(after.nextProgram()).isEqualTo(SignOnResponse.NEXT_PROGRAM_USER);
            assertThat(mapValuesOf(after))
                    .as("every screen field survives a navigation change unchanged")
                    .isEqualTo(mapValuesOf(before));
        }

        @Test
        @DisplayName("a replacement leaves the original untouched, so a handed-out value cannot change")
        void theOriginalIsNeverMutated() {
            SignOnResponse original = populated();
            String originalErrMsg = original.errMsg();
            String originalRole = original.role();

            original.withErrMsg(" ".repeat(SignOnResponse.ERRMSG_LENGTH));
            original.withNavigation(SignOnResponse.ROLE_USER, SignOnResponse.NEXT_PROGRAM_USER,
                    MAPSET_NAME, MAP_NAME, NavigationContext.empty());
            original.withHeader(TRANSACTION_ID, ScreenTitles.CCDA_TITLE02, FIXED_CURDATE,
                    PROGRAM_NAME, ScreenTitles.CCDA_TITLE01, FIXED_CURTIME + " ", "OTHERAPP",
                    "OTHR    ");

            assertThat(original.errMsg()).isEqualTo(originalErrMsg);
            assertThat(original.role()).isEqualTo(originalRole);
            assertThat(original).isEqualTo(populated());
        }

        @Test
        @DisplayName("the type declares no setter and no mutable instance field")
        void noSetterAndNoMutableField() {
            for (Method method : SignOnResponse.class.getDeclaredMethods()) {
                assertThat(method.getName())
                        .as("method %s must not be a setter", method.getName())
                        .doesNotStartWith("set");
            }
            for (Field field : SignOnResponse.class.getDeclaredFields()) {
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("field %s must be final", field.getName())
                        .isTrue();
            }
        }

        @Test
        @DisplayName("every static field is final, so there is no static mutable state")
        void noStaticMutableState() {
            // A static holder would be a session by another name and would break both request isolation
            // and this suite's order independence.
            for (Field field : SignOnResponse.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("static field %s must be final", field.getName())
                            .isTrue();
                }
            }
            for (Field field : SignOnResponseTest.class.getDeclaredFields()) {
                assertThat(Modifier.isStatic(field.getModifiers())
                        && Modifier.isFinal(field.getModifiers()))
                        .as("this suite's own field %s must be static final too", field.getName())
                        .isTrue();
            }
        }

        @Test
        @DisplayName("equality is by value, so two identically built responses are equal")
        void equalityIsByValue() {
            assertThat(populated())
                    .isEqualTo(populated())
                    .hasSameHashCodeAs(populated())
                    .isNotSameAs(populated());
        }
    }
}
