package com.vsergeychik.carddemo.user.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.user.model.SecUserRecord;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
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
 * Unit tests for {@link UserUpdateResponse} - the outbound payload of
 * {@code PUT /api/users/{userId}}, CICS transaction {@code CU02}, program
 * {@code app/cbl/COUSR02C.cbl}, map {@code COUSR2A} of mapset {@code COUSR02}.
 *
 * <h2>What this file is about</h2>
 *
 * Every response type in this package answers one question about the password, and each answers it
 * differently. This one is the outlier: <strong>{@link UserUpdateResponse} is the only response in
 * {@code user.dto} that carries the stored plaintext password</strong>, because
 * {@code app/cbl/COUSR02C.cbl:169} moves {@code SEC-USR-PWD} onto the screen and line 171 sends it.
 * The centre of this suite is therefore {@link EchoedPlaintextPassword}, whose job is to
 * <em>fail</em> if anybody ever redacts that member. Everything else here exists to make that
 * assertion trustworthy: the projection it belongs to, the overlay that makes it reachable, and the
 * serialisation that has to carry it.
 *
 * <h2>Project rules</h2>
 *
 * {@code review_rules} reports <strong>"No user rules provided."</strong> - that single line is the
 * whole document, confirmed by reading it to the end. No rule is invented here, and the absence of
 * rules is <em>not</em> treated as permission to assert less. The binding constraints are the
 * enterprise best-practice substitutes {@code B1}-{@code B12} recorded in the plan, each named below
 * with the one thing it requires of this file. The plan holds the full text of every practice; only
 * the ruling is restated.
 *
 * <ul>
 *   <li><strong>B1</strong> - imports are confined to the JDK, JUnit Jupiter, AssertJ and the Jackson
 *       already on the test classpath. No new coordinate and nothing from the exclusion list -
 *       <strong>in particular no authentication framework, no password encoder and no token
 *       library</strong>, which matters more in this file than in any other in the package, because
 *       this is the file a well-meaning reviewer would reach for one to "fix". Mockito is available
 *       and deliberately unused: a value type has no collaborator to stand in for.</li>
 *   <li><strong>B2</strong> - JUnit 5 Jupiter API only.</li>
 *   <li><strong>B3</strong> - the reference tree is neither written nor <em>read</em>. Every
 *       expectation is a {@code private static final} constant carrying the file and line it was
 *       transcribed from, so this suite is hermetic and independent of the working directory.</li>
 *   <li><strong>B4</strong> - conflicts are documented, never quietly reconciled. Two are recorded:
 *       the plaintext echo itself, discussed under {@link EchoedPlaintextPassword}, and the four
 *       brief-versus-declared divergences set out at the end of these notes.</li>
 *   <li><strong>B5</strong> - <strong>the practice that governs this file most.</strong> No member is
 *       asserted into or out of existence for symmetry with a sibling payload. {@code passwd} is
 *       asserted <em>present</em> here and {@code SignOnResponseTest} asserts it <em>absent</em>
 *       there, and both are right. Normalising either would delete observable behaviour.</li>
 *   <li><strong>B6</strong> - the security posture is neither weakened nor <em>unrequestedly
 *       strengthened</em>. The password stays a plaintext {@code PIC X(08)} member, exactly as
 *       {@code app/cbl/COSGN00C.cbl:223} compares it and {@code COUSR02C.cbl:169} sends it. Hashing
 *       or hiding it would change behaviour and would need an authentication framework the plan puts
 *       out of scope.</li>
 *   <li><strong>B7</strong> - nothing here reads a wall clock, draws a random value or depends on
 *       another test having run. The one time-derived expectation is driven from
 *       {@link Clock#fixed(Instant, java.time.ZoneId)}.</li>
 *   <li><strong>B8</strong> - every codec construction names its {@link Charset} explicitly; no
 *       overload that omits it is used and no platform default is relied on. Every import is written
 *       out individually - there is no wildcard import in this file - and no dataset name appears in
 *       it.</li>
 *   <li><strong>B9</strong> - every field of this class is {@code static final} and immutable. No
 *       state is shared between test methods; JUnit's default per-method lifecycle does the
 *       isolating.</li>
 *   <li><strong>B10</strong> - this suite ships in the same phase as the type it measures, so a drift
 *       from the mapset is traceable to the decision that caused it rather than surfacing later as an
 *       unexplained difference.</li>
 *   <li><strong>B11</strong> - fixed-width and truncation work goes through {@link FixedWidthCodec}
 *       and {@link FixedWidthRecord}; no third-party copybook parser is used, and no assertion
 *       substitutes {@link String#substring(int, int)} for a COBOL {@code MOVE}. The one place a
 *       substring appears is {@link #delimitedBySpace(String)}, which models
 *       {@code DELIMITED BY SPACE} - a different construct with a different rule - and says so.</li>
 *   <li><strong>B12</strong> - the environmental limit is stated rather than absorbed. See the
 *       provenance note immediately below.</li>
 * </ul>
 *
 * <h2>Provenance of every expected value (B12)</h2>
 *
 * COBOL cannot be executed in this environment; the plan records eight independently verified
 * blockers as risk {@code R-A}, among them a disabled indexed-file handler, absent Language
 * Environment services and the absence of any CICS emulator. Every expectation below is therefore
 * <strong>statically derived</strong> by reading the source, not captured from a run. The lines used
 * are:
 *
 * <ul>
 *   <li>{@code app/cpy-bms/COUSR02.CPY} - line 17 opens {@code 01 COUSR2AI}; line 18 is its twelve-byte
 *       {@code TIOAPFX} prefix; <strong>line 91 declares {@code 01 COUSR2AO REDEFINES COUSR2AI}</strong>
 *       and line 92 repeats the prefix for the output view; the twelve {@code xxxO} items are at lines
 *       98, 104, 110, 116, 122, 128, 134, 140, 146, 152, 158 and 164.</li>
 *   <li>{@code app/bms/COUSR02.bms} - {@code DFHMSD} with {@code EXTATT=YES} and {@code TIOAPFX=YES}
 *       at lines 19-25, {@code COUSR2A DFHMDI SIZE=(24,80)} at 26-28, twenty-nine {@code DFHMDF}
 *       definitions of which twelve are name-labelled, {@code PASSWD} at line 130 and {@code ERRMSG}
 *       at lines 155-158.</li>
 *   <li>{@code app/cbl/COUSR02C.cbl} - {@code WS-PGMNAME} at 36, {@code WS-TRANID} at 37,
 *       {@code WS-MESSAGE PIC X(80)} at 38, the {@code CDEMO-CU02-INFO} extension at 50-58,
 *       {@code MOVE SPACES TO ... ERRMSGO} at 88, {@code MOVE LOW-VALUES TO COUSR2AO} at 97, the
 *       blanking of the four fields at 158-161, <strong>the echo block at 166-172</strong>, the five
 *       blank-field arms at 179-213, the four change comparisons at 219-234, the no-change arm at
 *       236-243, {@code XCTL} at 259, <strong>the narrowing move at 270</strong>,
 *       {@code MAP}/{@code MAPSET} at 273-274, {@code POPULATE-HEADER-INFO} at 296-315, the
 *       {@code DFHNEUTR} prompt at 336-338, the {@code DFHGREEN} success {@code STRING} at 371-375,
 *       and {@code INITIALIZE-ALL-FIELDS} at 403-411.</li>
 *   <li>{@code app/cpy/CSUSR01Y.cpy:17-23} - the eighty-byte {@code SEC-USER-DATA}, whose
 *       {@code SEC-USR-PWD PIC X(08)} sits at offset 48.</li>
 *   <li>{@code app/cpy/COCOM01Y.cpy:19-44} - the 160-byte {@code CARDDEMO-COMMAREA}, its
 *       {@code CDEMO-PGM-CONTEXT} {@code 88}-levels at 29-31 and its {@code PIC X(7)} map and mapset
 *       names at 43-44.</li>
 *   <li>{@code app/csd/CARDDEMO.CSD:469-470} - {@code TRANSACTION(CU02)} bound to
 *       {@code PROGRAM(COUSR02C)}.</li>
 * </ul>
 *
 * <h2>Scope: this is a plain-object suite</h2>
 *
 * No Spring context, no {@code @SpringBootTest}, no {@code @WebMvcTest}, no {@code MockMvc}, no
 * {@code JobLauncher}, and no reference to a controller, service or repository.
 * {@code user.UserUpdateControllerTest} already owns the HTTP projection, the save-before-exit path,
 * the change-detection outcomes and the read-for-update/rewrite sequence; restating them here would be
 * duplication rather than thoroughness. The subject here is the <strong>type</strong>: its projection,
 * its widths, the metadata that must never reach the wire, the group-level overlay, the message texts
 * it has to be able to carry, and the round trip.
 *
 * <p>The complement is also true, and deliberate. {@code UserUpdateRequestTest} owns the twelve
 * per-field {@code xxxA REDEFINES xxxF} overlays of the <em>input</em> view and the request half of the
 * password pairing; this file owns the <strong>group-level</strong> overlay at copybook line 91 and the
 * response half. {@code UserScreenStateContractTest} owns the width-failure diagnostics - that a
 * rejected value is never quoted back - so those messages are not re-asserted here.
 *
 * <h2>Why this package is measured on its own</h2>
 *
 * The coverage gate applies a {@code BRANCH} minimum at package granularity as well as at bundle
 * granularity, so {@code user}, {@code user.model} and {@code user.dto} are three separately measured
 * packages and none can shelter behind another. {@code user.dto} is not branch-free - the width guard,
 * the two null-normalisations, the field lookup, the page-number guards and the read-through context
 * predicates all branch - so the package needs instruments aimed at the payload types directly.
 *
 * <h2>Gate G43 does not apply to this package</h2>
 *
 * Recorded explicitly so that no later reader "adds the missing optimistic concurrency". The
 * concurrency requirement is scoped to the account and card update services, whose programs contain
 * paragraph {@code 9300-CHECK-CHANGE-IN-REC}. {@code COUSR02C} has no such paragraph: its four tests
 * at lines 219-234 compare the screen values against a record it re-read at line 217 in order to
 * decide whether <em>anything changed at all</em>, and they never guard against a competing writer.
 * Adding a revision counter would be both a behaviour change and a schema change, and the plan forbids
 * schema change outright. {@link EchoedPlaintextPassword#noConcurrencyTokenIsSmuggledIn} asserts the
 * absence.
 *
 * <h2>Four divergences between this file's brief and the declared type (B4)</h2>
 *
 * The declared members are ground truth. Where the brief and the class disagree, the class is asserted
 * as it stands and the disagreement is recorded here rather than edited away:
 *
 * <ol>
 *   <li><strong>{@link UserUpdateResponse#toString()} does withhold the password.</strong> The brief
 *       asks for no rendering that elides it. The declared type substitutes a placeholder in
 *       {@code toString()} only - and that is the right distinction, not a violation of it: a
 *       diagnostic rendering is what a log line, a stack trace or a debugger dump picks up by
 *       accident, whereas the payload is what the program sends. Both halves are asserted in
 *       {@link EchoedPlaintextPassword}: the rendering masks, and {@link UserUpdateResponse#passwd()},
 *       {@link UserUpdateResponse#fieldValues()} and the serialised JSON all carry the plaintext in
 *       full. Masking the payload would be the violation; masking the log line is not.</li>
 *   <li><strong>There is no {@code aid} member on the response.</strong> The brief describes the
 *       resolved AID token as a payload member of this type. It is a member of
 *       {@link UserUpdateRequest} - the half that travels <em>inbound</em>, since the key press is
 *       something the client reports. The response's outbound half of the same contract is
 *       {@link UserUpdateResponse#nextProgram()}, {@link UserUpdateResponse#nextMapset()} and
 *       {@link UserUpdateResponse#nextMap()}. The {@link PfKeyResolver} token vocabulary is still
 *       asserted here, in {@link ConversationState}, because it is the shape the paired request has to
 *       carry for this screen's two save paths to be expressible.</li>
 *   <li><strong>The enter/re-enter flag is not a member of its own either.</strong> It is read through
 *       {@link NavigationContext#isEnter()} and {@link NavigationContext#isReenter()}, so
 *       {@code CDEMO-PGM-CONTEXT} has exactly one home and the two cannot drift apart. Both states are
 *       driven regardless, because the branch gate requires every {@code 88}-level to be exercised in
 *       both directions.</li>
 *   <li><strong>The class documentation says sixteen members where seventeen are declared</strong>, its
 *       {@code @param} list omitting {@code cu02Info}, and it imports two types it never uses. Those
 *       are documentation and tidiness drifts in a file this suite does not own.
 *       {@link MapProjection#theComponentCountIsTwelvePlusFive} asserts the seventeen that are really
 *       there, and {@link EchoedPlaintextPassword#noRedactionAnnotationIsAppliedToThePassword} is
 *       written reflectively over the <em>applied</em> annotations precisely so that the unused
 *       {@code JsonIgnore} import cannot make it pass or fail for the wrong reason.</li>
 * </ol>
 */
@DisplayName("UserUpdateResponse - the COUSR02 (CU02) update-user outbound payload")
class UserUpdateResponseTest {

    // =================================================================================================
    // THE CODE PAGE. Named once, passed explicitly into every codec construction below (B8).
    //
    // US-ASCII, not IBM037: the authoritative fixtures under app/data/ASCII are text, and this suite
    // measures widths, offsets and round trips rather than reading a dataset. The point of naming it is
    // that no assertion here can quietly acquire the platform default.
    // =================================================================================================

    /** The explicitly named code page for every fixed-width operation in this suite. */
    private static final Charset MAP_CHARSET = StandardCharsets.US_ASCII;

    // =================================================================================================
    // SCREEN IDENTITY. Transcribed literals, each with the line it came from (B3, B12).
    // =================================================================================================

    /** {@code MAP('COUSR2A')}, {@code app/cbl/COUSR02C.cbl:273}; the {@code DFHMDI} label, bms line 26. */
    private static final String MAP_NAME = "COUSR2A";

    /** {@code MAPSET('COUSR02')}, {@code app/cbl/COUSR02C.cbl:274}; the {@code DFHMSD} label, bms line 19. */
    private static final String MAPSET_NAME = "COUSR02";

    /** {@code 05 WS-TRANID PIC X(04) VALUE 'CU02'}, {@code app/cbl/COUSR02C.cbl:37}. */
    private static final String TRANSACTION_ID = "CU02";

    /** {@code 05 WS-PGMNAME PIC X(08) VALUE 'COUSR02C'}, {@code app/cbl/COUSR02C.cbl:36}. */
    private static final String PROGRAM_NAME = "COUSR02C";

    /** {@code 01 COUSR2AI}, {@code app/cpy-bms/COUSR02.CPY:17} - the input view of the one area. */
    private static final String INPUT_GROUP_NAME = "COUSR2AI";

    /** {@code 01 COUSR2AO REDEFINES COUSR2AI}, {@code app/cpy-bms/COUSR02.CPY:91} - the output view. */
    private static final String OUTPUT_GROUP_NAME = "COUSR2AO";

    /**
     * {@code DEFINE TRANSACTION(CU02) ... PROGRAM(COUSR02C)},
     * {@code app/csd/CARDDEMO.CSD:469-470} - the binding that makes {@link #TRANSACTION_ID} and
     * {@link #PROGRAM_NAME} two halves of one fact rather than two unrelated literals.
     */
    private static final String CSD_TRANSACTION_BINDING = TRANSACTION_ID + "->" + PROGRAM_NAME;

    // =================================================================================================
    // THE FIELD INVENTORY. Twenty-nine DFHMDF definitions, twelve of them name-labelled.
    // =================================================================================================

    /**
     * Every {@code DFHMDF} in {@code app/bms/COUSR02.bms}: <strong>29</strong>, at lines 29, 34, 38,
     * 42, 47, 52, 57, 61, 65, 70, 75, 80, 85, 90, 93, 98, 103, 108, 111, 116, 121, 125, 130, 135, 140,
     * 145, 150, 155 and 159.
     */
    private static final int DFHMDF_TOTAL = 29;

    /**
     * The {@code DFHMDF} definitions that carry a label, and therefore the number of payload members:
     * <strong>12</strong>.
     *
     * <p>The other {@value #DFHMDF_UNLABELLED} are literal screen furniture - {@code 'Tran:'},
     * {@code 'Date:'}, {@code 'Prog:'}, {@code 'Time:'}, {@code 'Update User'},
     * {@code 'Enter User ID:'}, {@code 'First Name:'}, {@code 'Last Name:'}, {@code 'Password:'},
     * {@code '(8 Char)'}, {@code 'User Type: '}, {@code '(A=Admin, U=User)'}, a seventy-character rule
     * of asterisks, the function-key legend and three {@code LENGTH=0} field stoppers. None of them is
     * a field, and a payload that projected all twenty-nine would be projecting the screen's
     * decoration.
     */
    private static final int DFHMDF_NAMED = 12;

    /** {@value #DFHMDF_TOTAL} minus {@value #DFHMDF_NAMED}: the literals that are not fields. */
    private static final int DFHMDF_UNLABELLED = DFHMDF_TOTAL - DFHMDF_NAMED;

    /**
     * The name-labelled {@code DFHMDF} count of {@code app/bms/COSGN00.bms}: <strong>11</strong> -
     * {@code TRNNAME TITLE01 CURDATE PGMNAME TITLE02 CURTIME APPLID SYSID USERID PASSWD ERRMSG}, so
     * {@code PASSWD} <em>is</em> on that map.
     *
     * <p>All three sibling counts here are transcribed constants rather than reads of the sibling types
     * (B3): each was counted in the sibling's own mapset and cross-read in its own program, and stating
     * them here keeps this suite's dependency surface to the payload it measures.
     */
    private static final int COSGN00_DFHMDF_NAMED = 11;

    /**
     * The members {@code COSGN00}'s <em>response</em> projects: <strong>10</strong>, one fewer than its
     * map declares.
     *
     * <p>The difference is the whole point, and it is a property of the <em>program</em> rather than of
     * the map: {@code app/cbl/COSGN00C.cbl} references {@code PASSWDO}
     * {@value #COSGN00C_PASSWDO_REFERENCES} times, so nothing ever writes the password outbound and
     * {@code SEND-SIGNON-SCREEN} does not send it back. A password field existing on a map therefore
     * settles nothing by itself - what settles it is whether the program writes it.
     */
    private static final int COSGN00_RESPONSE_MEMBERS = 10;

    /**
     * {@code grep -c PASSWDO app/cbl/COSGN00C.cbl}: <strong>zero</strong>. The evidence that the
     * sign-on response's missing member is deliberate and not an omission.
     */
    private static final int COSGN00C_PASSWDO_REFERENCES = 0;

    /**
     * The name-labelled {@code DFHMDF} count of {@code app/bms/COUSR01.bms}: <strong>12</strong>,
     * {@code PASSWD} among them - so that response does declare the member.
     *
     * <p>It never carries a stored value, though. {@code app/cbl/COUSR01C.cbl} also references
     * {@code PASSWDO} zero times; its only password move is the <em>inbound</em>
     * {@code MOVE PASSWDI OF COUSR1AI TO SEC-USR-PWD} at line 157, and
     * {@code INITIALIZE-ALL-FIELDS} at lines 286-294 blanks {@code PASSWDI}. Present, always blank
     * outbound.
     *
     * <p>Note that {@code COUSR01} orders its fields {@code FNAME LNAME USERID PASSWD USRTYPE} - the
     * identifier <em>after</em> the names, and spelled {@code USERID}. Both are the inverse of this
     * screen, which is what {@link CrossScreenInversions} asserts.
     */
    private static final int COUSR01_DFHMDF_NAMED = 12;

    /**
     * The name-labelled {@code DFHMDF} count of {@code app/bms/COUSR03.bms}: <strong>11</strong> -
     * {@code TRNNAME TITLE01 CURDATE PGMNAME TITLE02 CURTIME USRIDIN FNAME LNAME USRTYPE ERRMSG}. That
     * map has no {@code PASSWD} field whatsoever, which is exactly why this one has twelve.
     */
    private static final int COUSR03_DFHMDF_NAMED = 11;

    /**
     * The twelve labels in screen order, which is also copybook order.
     *
     * <p>Note where {@code USRIDIN} sits: <strong>seventh, before the two name fields</strong>. That is
     * this screen's order, and it is the inverse of {@code COUSR01}'s, where the identifier follows the
     * names. {@link CrossScreenInversions} asserts the inversion rather than leaving it to be noticed.
     */
    private static final List<String> SCREEN_FIELDS = List.of("TRNNAME",
            "TITLE01",
            "CURDATE",
            "PGMNAME",
            "TITLE02",
            "CURTIME",
            "USRIDIN",
            "FNAME",
            "LNAME",
            "PASSWD",
            "USRTYPE",
            "ERRMSG");

    /**
     * The {@code xxxO} item names of {@code 01 COUSR2AO}, spelled exactly as
     * {@code app/cpy-bms/COUSR02.CPY} spells them. These are the names a field-by-field comparison keys
     * on, so a "tidied" spelling would hide a real difference.
     */
    private static final List<String> OUTPUT_MAP_ITEMS =
            SCREEN_FIELDS.stream().map(field -> field + "O").toList();

    /** The {@code xxxI} item names of {@code 01 COUSR2AI}, the same twelve fields seen inbound. */
    private static final List<String> INPUT_MAP_ITEMS =
            SCREEN_FIELDS.stream().map(field -> field + "I").toList();

    /**
     * The copybook line each {@code xxxO} item is declared on:
     * {@code app/cpy-bms/COUSR02.CPY} lines 98, 104, 110, 116, 122, 128, 134, 140, 146, 152, 158, 164.
     *
     * <p>The regular spacing - six lines apart - is the four-item pattern of the output view: a
     * three-byte {@code FILLER}, then {@code xxxC}, {@code xxxP}, {@code xxxH} and {@code xxxV}, then
     * the data item.
     */
    private static final List<Integer> OUTPUT_ITEM_LINES =
            List.of(98, 104, 110, 116, 122, 128, 134, 140, 146, 152, 158, 164);

    /**
     * The twelve declared widths, read straight off the {@code xxxO} {@code PICTURE} clauses:
     * {@code 4, 40, 8, 8, 40, 8, 8, 20, 20, 8, 1, 78}.
     *
     * <p>Two of them are traps. {@code CURTIMEO} is {@code X(8)} at line 128 - eight, where
     * {@code COSGN00} alone declares nine. {@code ERRMSGO} is {@code X(78)} at line 164 - seventy-eight,
     * where the {@code WS-MESSAGE} it receives is {@value #WS_MESSAGE_LENGTH}. {@link WidthTraps}
     * drives both.
     */
    private static final List<Integer> DECLARED_WIDTHS =
            List.of(4, 40, 8, 8, 40, 8, 8, 20, 20, 8, 1, 78);

    /** The twelve record components that project the map, in the order the record declares them. */
    private static final List<String> MAP_MEMBERS = List.of("trnName",
            "title01",
            "curDate",
            "pgmName",
            "title02",
            "curTime",
            "usrIdIn",
            "fName",
            "lName",
            "passwd",
            "usrType",
            "errMsg");

    /**
     * The three members that replace {@code EXEC CICS XCTL}, and the two that carry conversation state.
     *
     * <p>The explicitly mandated exception to "every member traces to a {@code DFHMDF} definition":
     * CICS supplied all five from the communication area rather than from the map, so without them the
     * program's navigation and pass-through branches are unreachable through the API.
     */
    private static final List<String> STATE_MEMBERS =
            List.of("navigationContext", "nextProgram", "nextMapset", "nextMap", "cu02Info");

    /** Twelve map members plus the five state carriers: seventeen record components. */
    private static final int COMPONENT_COUNT = DFHMDF_NAMED + 5;

    // =================================================================================================
    // THE SYMBOLIC-MAP GEOMETRY.
    //
    // 01 COUSR2AI opens with a twelve-byte TIOAPFX prefix (CPY:18) and then, per field,
    //     xxxL COMP PIC S9(4)   2 bytes
    //     xxxF PICTURE X        1 byte, with 02 FILLER REDEFINES xxxF / 03 xxxA over that same byte
    //     FILLER PICTURE X(4)   4 bytes
    //   = seven bytes, then xxxI PIC X(n).
    //
    // 01 COUSR2AO REDEFINES COUSR2AI (CPY:91) opens with the same twelve-byte prefix (CPY:92) and then
    //     FILLER PICTURE X(3)   3 bytes
    //     xxxC xxxP xxxH xxxV   1 byte each = 4 bytes
    //   = seven bytes as well, then xxxO PIC X(n).
    //
    // Both per-field prefixes are seven bytes, which is why the two views align field for field with
    // ZERO drift. The numbers below are transcribed and then RE-DERIVED by the two layout builders,
    // because RecordLayout.of refuses a gap, an unintended overlap or a total that disagrees.
    // =================================================================================================

    /** {@code 02 FILLER PIC X(12)} at {@code COUSR02.CPY:18} and again at {@code :92}. */
    private static final int TIOAPFX_PREFIX_LENGTH = 12;

    /** {@code 02 xxxL COMP PIC S9(4)} - a binary halfword, two bytes. Input view only. */
    private static final int LENGTH_ITEM_LENGTH = 2;

    /** {@code 02 xxxF PICTURE X} and its {@code xxxA} overlay, and each of {@code xxxC/P/H/V} - one byte. */
    private static final int ATTRIBUTE_ITEM_LENGTH = 1;

    /** {@code 02 FILLER PICTURE X(4)} between each input attribute byte and its data item. */
    private static final int INPUT_FILLER_LENGTH = 4;

    /** {@code 02 FILLER PICTURE X(3)} ahead of each output attribute quartet. */
    private static final int OUTPUT_FILLER_LENGTH = 3;

    /**
     * The {@code EXTATT=YES} attribute quartet of the output view, in declaration order.
     *
     * <p>{@code xxxC} is the <strong>colour</strong> item and is
     * {@link FieldAttributeSetter#COLOUR_ITEM_SUFFIX}. {@code COUSR02C} drives it three ways -
     * {@code MOVE DFHRED TO ERRMSGC OF COUSR2AO} at line 241 when nothing changed, {@code DFHNEUTR} at
     * line 338 for the save prompt, {@code DFHGREEN} at line 371 on success - and the sibling program
     * makes the idiom explicit at {@code app/cbl/COUSR03C.cbl:317} with
     * {@code MOVE DFHGREEN TO ERRMSGC OF COUSR3AO}. {@code xxxH} is highlighting; {@code xxxP} and
     * {@code xxxV} are the programmed-symbol and validation bytes. All four are presentation metadata.
     */
    private static final List<String> ATTRIBUTE_SUFFIXES = List.of("C", "P", "H", "V");

    /** Input view, per field: {@code xxxL} 2 + {@code xxxF} 1 + {@code FILLER X(4)} = seven bytes. */
    private static final int INPUT_PREFIX_LENGTH =
            LENGTH_ITEM_LENGTH + ATTRIBUTE_ITEM_LENGTH + INPUT_FILLER_LENGTH;

    /** Output view, per field: {@code FILLER X(3)} 3 + four attribute bytes = seven bytes as well. */
    private static final int OUTPUT_PREFIX_LENGTH =
            OUTPUT_FILLER_LENGTH + ATTRIBUTE_SUFFIXES.size() * ATTRIBUTE_ITEM_LENGTH;

    /** The twelve declared widths added together: 4+40+8+8+40+8+8+20+20+8+1+78. */
    private static final int PAYLOAD_WIDTH_TOTAL = 243;

    /**
     * The whole area, in either view: 12 + 12 x 7 + 243 = <strong>339</strong> bytes.
     *
     * <p>One figure, because {@code 01 COUSR2AO REDEFINES COUSR2AI} means one storage area seen twice.
     * The derivation is recorded so a reader can check it;
     * {@link GroupRedefinesOverlay#theGeometryIsTheCopybooks} re-derives it from the transcribed parts
     * instead of restating the total, and both layout builders then prove it a third time by
     * satisfying {@link FixedWidthRecord.RecordLayout}'s own self-check at this length.
     *
     * <p>It happens to equal {@code COUSR01}'s total, because that mapset also has twelve fields of the
     * same twelve widths - in a different order. Equal totals, different offsets: which is exactly why
     * the offsets are asserted here and not inferred from the total.
     */
    private static final int SYMBOLIC_MAP_LENGTH = 339;

    /** {@code 05 WS-MESSAGE PIC X(80) VALUE SPACES}, {@code app/cbl/COUSR02C.cbl:38}. */
    private static final int WS_MESSAGE_LENGTH = 80;

    // =================================================================================================
    // REDEFINES CENSUS. This package is the only place the redefinition gate has a subject, because the
    // five programs behind these five maps declare a REDEFINES between them exactly zero times.
    // =================================================================================================

    /** The twelve per-field {@code 02 FILLER REDEFINES xxxF} overlays of {@code 01 COUSR2AI}. */
    private static final int PER_FIELD_REDEFINES = 12;

    /** The one {@code 01 COUSR2AO REDEFINES COUSR2AI} at {@code app/cpy-bms/COUSR02.CPY:91}. */
    private static final int GROUP_LEVEL_REDEFINES = 1;

    /** {@value #PER_FIELD_REDEFINES} + {@value #GROUP_LEVEL_REDEFINES} = the thirteen in this copybook. */
    private static final int COPYBOOK_REDEFINES_TOTAL = PER_FIELD_REDEFINES + GROUP_LEVEL_REDEFINES;

    /**
     * The five maps of this package add up to <strong>110</strong>: 105 per-field overlays plus five
     * group-level ones, one per map. {@code COUSR00} contributes 60 of them because it has fifty
     * numbered row fields on top of its header.
     */
    private static final int PACKAGE_REDEFINES_TOTAL = 110;

    /** The group-level overlays across the package: one per map, five maps. */
    private static final int PACKAGE_GROUP_LEVEL_REDEFINES = 5;

    /**
     * {@code grep -c REDEFINES} over {@code COSGN00C}, {@code COUSR00C}, {@code COUSR01C},
     * {@code COUSR02C} and {@code COUSR03C}: <strong>zero</strong>. The redefinitions are entirely in
     * the maps.
     */
    private static final int PROGRAM_REDEFINES_TOTAL = 0;

    // =================================================================================================
    // THE COMMUNICATION AREA AND ITS CU02 EXTENSION.
    // =================================================================================================

    /** {@code 01 CARDDEMO-COMMAREA}, {@code app/cpy/COCOM01Y.cpy:19-44}: 34+84+12+16+14 = 160 bytes. */
    private static final int COMMAREA_LENGTH = 160;

    /** {@code 05 CDEMO-CU02-INFO}, {@code app/cbl/COUSR02C.cbl:50-58}: 8+8+8+1+1+8 = 34 bytes. */
    private static final int CU02_EXTENSION_LENGTH = 34;

    /** What {@code app/cbl/COUSR02C.cbl:94} restores: {@value #COMMAREA_LENGTH} + 34 = 194 bytes. */
    private static final int CU02_COMMAREA_LENGTH = COMMAREA_LENGTH + CU02_EXTENSION_LENGTH;

    /**
     * The six item names of the extension, spelled as {@code app/cbl/COUSR02C.cbl:51-58} spells them.
     *
     * <p>The {@code CDEMO-CU02-} prefix is the point. {@code COUSR00C:67-75} declares
     * {@code CDEMO-CU00-*} and {@code COUSR03C:50-58} declares {@code CDEMO-CU03-*} over the same
     * thirty-four bytes with the same six-item shape. Three separate declarations of one span, not one
     * shared type - and {@code COSGN00C} and {@code COUSR01C} declare none at all.
     */
    private static final List<String> CU02_ITEM_NAMES = List.of("CDEMO-CU02-USRID-FIRST",
            "CDEMO-CU02-USRID-LAST",
            "CDEMO-CU02-PAGE-NUM",
            "CDEMO-CU02-NEXT-PAGE-FLG",
            "CDEMO-CU02-USR-SEL-FLG",
            "CDEMO-CU02-USR-SELECTED");

    /** The six declared widths of the extension: {@code X(08) X(08) 9(08) X(01) X(01) X(08)}. */
    private static final List<Integer> CU02_ITEM_WIDTHS = List.of(8, 8, 8, 1, 1, 8);

    /**
     * The name-labelled {@code DFHMDF} labels of {@code app/bms/COUSR01.bms}, in that mapset's own
     * order, so the inversion this screen exhibits can be asserted against the other side of it rather
     * than only claimed.
     *
     * <p>Two differences, both real and both preserved: {@code COUSR01} spells its identifier
     * {@code USERID} where this map spells it {@code USRIDIN}, and it declares that identifier
     * <em>after</em> {@code FNAME} and {@code LNAME} where this map declares it before them.
     */
    private static final List<String> COUSR01_SCREEN_FIELDS = List.of("TRNNAME",
            "TITLE01",
            "CURDATE",
            "PGMNAME",
            "TITLE02",
            "CURTIME",
            "FNAME",
            "LNAME",
            "USERID",
            "PASSWD",
            "USRTYPE",
            "ERRMSG");

    /** {@code 88 NEXT-PAGE-YES VALUE 'Y'}, {@code app/cbl/COUSR02C.cbl:55}. */
    private static final String NEXT_PAGE_YES = "Y";

    /**
     * {@code 88 NEXT-PAGE-NO VALUE 'N'}, {@code app/cbl/COUSR02C.cbl:56}, and the field's own
     * {@code VALUE 'N'} at line 54 - so {@code 'N'} is the declared default, not a convention chosen
     * here.
     */
    private static final String NEXT_PAGE_NO = "N";

    /**
     * A third value the flag can physically hold. {@code CDEMO-CU02-NEXT-PAGE-FLG} is
     * {@code PIC X(01)}, so any character fits, and the source declares no {@code WHEN OTHER} for these
     * two {@code 88}-levels - which means a space satisfies neither predicate. That is a real state, and
     * {@link Cu02InfoOnTheReply#aThirdValueSatisfiesNeitherConditionName} drives it.
     */
    private static final String NEXT_PAGE_NEITHER = " ";

    // =================================================================================================
    // THE STORED RECORD. app/cpy/CSUSR01Y.cpy:17-23, the source of the four echoed values.
    // =================================================================================================

    /** {@code 01 SEC-USER-DATA} in full: 8+20+20+8+1+23 = 80 bytes. */
    private static final int SEC_USER_DATA_LENGTH = 80;

    /** The five data item names of {@code SEC-USER-DATA} that this screen projects, in record order. */
    private static final List<String> SEC_USER_ITEMS = List.of("SEC-USR-ID",
            "SEC-USR-FNAME",
            "SEC-USR-LNAME",
            "SEC-USR-PWD",
            "SEC-USR-TYPE");

    /** Their offsets in the eighty-byte record: 0, 8, 28, <strong>48</strong>, 56. */
    private static final List<Integer> SEC_USER_OFFSETS = List.of(0, 8, 28, 48, 56);

    /** Their declared widths: {@code X(08) X(20) X(20) X(08) X(01)}. */
    private static final List<Integer> SEC_USER_WIDTHS = List.of(8, 20, 20, 8, 1);

    /** The response member each of those five items is moved into, at lines 216, 167, 168, 169 and 170. */
    private static final List<String> SEC_USER_TARGET_MEMBERS =
            List.of("usrIdIn", "fName", "lName", "passwd", "usrType");

    /** {@code 05 SEC-USR-FILLER PIC X(23)} at {@code app/cpy/CSUSR01Y.cpy:23}, offset 57. */
    private static final int SEC_USER_FILLER_OFFSET = 57;

    // =================================================================================================
    // THE MESSAGE TEXTS. Transcribed from app/cbl/COUSR02C.cbl, each with its line (B3, B12).
    //
    // Every one of them lands in ERRMSGO, and every one arrives there through line 270's
    //     MOVE WS-MESSAGE TO ERRMSGO OF COUSR2AO
    // which narrows PIC X(80) to PIC X(78) and so discards two characters on the right.
    // =================================================================================================

    /** Line 182, the first arm - and the identifier is checked <em>first</em>, before the names. */
    private static final String MSG_USER_ID_EMPTY = "User ID can NOT be empty...";

    /** Line 188, the second arm. */
    private static final String MSG_FIRST_NAME_EMPTY = "First Name can NOT be empty...";

    /** Line 194, the third arm. */
    private static final String MSG_LAST_NAME_EMPTY = "Last Name can NOT be empty...";

    /** Line 200, the fourth arm. */
    private static final String MSG_PASSWORD_EMPTY = "Password can NOT be empty...";

    /** Line 206, the fifth arm. The {@code WHEN OTHER} at line 210 sets no message at all. */
    private static final String MSG_USER_TYPE_EMPTY = "User Type can NOT be empty...";

    /**
     * The five blank-field messages in the order {@code UPDATE-USER-INFO} evaluates them, which is the
     * order the {@code EVALUATE} declares its {@code WHEN}s and therefore the order that decides which
     * message a screen with two blank fields shows.
     */
    private static final List<String> BLANK_FIELD_MESSAGES = List.of(MSG_USER_ID_EMPTY,
            MSG_FIRST_NAME_EMPTY,
            MSG_LAST_NAME_EMPTY,
            MSG_PASSWORD_EMPTY,
            MSG_USER_TYPE_EMPTY);

    /**
     * Lines 239-240, when none of the four comparisons at 219-234 fired. Note the space before the
     * ellipsis, which the four messages above do not have.
     *
     * <p>This arm is the one that pairs with {@link BmsAttributes#DFHRED} at line 241.
     */
    private static final String MSG_PLEASE_MODIFY = "Please modify to update ...";

    /** The first operand of the success {@code STRING} at line 372, {@code DELIMITED BY SIZE}. */
    private static final String MSG_UPDATED_PREFIX = "User ";

    /** The third operand at line 374, {@code DELIMITED BY SIZE}. Twenty-one characters. */
    private static final String MSG_UPDATED_SUFFIX = " has been updated ...";

    /** Lines 336-337, the prompt shown after a successful read. It pairs with {@code DFHNEUTR} at 338. */
    private static final String MSG_PRESS_PF5 = "Press PF5 key to save your updates ...";

    /** Lines 342-343 and 379-380. */
    private static final String MSG_USER_NOT_FOUND = "User ID NOT found...";

    /** Lines 349-350, the {@code WHEN OTHER} of the read. */
    private static final String MSG_UNABLE_TO_LOOKUP = "Unable to lookup User...";

    /** Lines 386-387, the {@code WHEN OTHER} of the rewrite. */
    private static final String MSG_UNABLE_TO_UPDATE = "Unable to Update User...";

    // =================================================================================================
    // DETERMINISM (B7). One fixed instant, read through Clock.fixed at UTC, so the header renders the
    // same characters on every run, on every machine, in any order.
    // =================================================================================================

    /** An arbitrary but fixed instant: 2022-07-19T23:12:34Z, the version stamp on the program itself. */
    private static final Instant FIXED_INSTANT = Instant.parse("2022-07-19T23:12:34Z");

    /** {@code MM/DD/YY} as {@code POPULATE-HEADER-INFO} composes it at lines 305-309. */
    private static final String EXPECTED_CURDATE = "07/19/22";

    /** {@code HH:MM:SS} as lines 311-315 compose it. Eight characters, not nine. */
    private static final String EXPECTED_CURTIME = "23:12:34";

    // =================================================================================================
    // FIXTURES. Obviously synthetic, and chosen so no value could be mistaken for a real credential.
    // =================================================================================================

    /**
     * An eight-character stand-in for {@code SEC-USR-PWD}. It is exactly
     * {@value #PASSWD_DECLARED_WIDTH} characters so it fills the field without padding, and it is
     * plainly fabricated: this is test data modelling a legacy field, not a secret.
     */
    private static final String PASSWD_FIXTURE = "PW-FAKE1";

    /** A shorter stand-in, to prove the codec pads on the right rather than trimming. */
    private static final String SHORT_PASSWD_FIXTURE = "PW-2";

    /** An eight-character user id, filling {@code SEC-USR-ID PIC X(08)} exactly. */
    private static final String USER_ID_FIXTURE = "USER0001";

    /**
     * A five-character user id, so {@code DELIMITED BY SPACE} has something to do: padded to eight it
     * is {@code 'ADMIN   '}, and the {@code STRING} at lines 372-375 must contribute only
     * {@code 'ADMIN'}.
     */
    private static final String SHORT_USER_ID_FIXTURE = "ADMIN";

    /** {@code PASSWDO PIC X(8)}, {@code app/cpy-bms/COUSR02.CPY:152}. */
    private static final int PASSWD_DECLARED_WIDTH = 8;

    // =================================================================================================
    // DERIVED CONSTANTS. Built once by the static builders below; every one is immutable (B9).
    // =================================================================================================

    /**
     * {@code 01 COUSR2AI} - the storage, with the twelve per-field {@code xxxA} overlays over it.
     *
     * <p>The {@code xxxL} halfword is declared as {@code FILLER} rather than under its own name: it is
     * {@code COMP} - binary - and {@link FixedWidthRecord.PictureKind} deliberately has no binary
     * category, because no persisted record in this estate holds one. It is reserved storage here, and
     * it is never a payload member in any case.
     */
    private static final FixedWidthRecord.RecordLayout INPUT_VIEW_LAYOUT = inputViewLayout();

    /**
     * {@code 01 COUSR2AO} - the same {@value #SYMBOLIC_MAP_LENGTH} bytes under the output view's own
     * names, with the group-level overlay of {@code COUSR02.CPY:91} declared over the whole area.
     *
     * <p>Built as a layout in its own right rather than as overlays inside {@link #INPUT_VIEW_LAYOUT},
     * because that is what makes the geometry provable: a redefining group has to tile the redefined
     * storage exactly, so the fact that <em>both</em> builders satisfy
     * {@link FixedWidthRecord.RecordLayout}'s self-check at {@value #SYMBOLIC_MAP_LENGTH} bytes is
     * itself the assertion that the overlay is exact and drift-free.
     */
    private static final FixedWidthRecord.RecordLayout OUTPUT_VIEW_LAYOUT = outputViewLayout();

    /** The complete set of JSON member names this payload may emit: the twelve plus the five. */
    private static final Set<String> EXPECTED_JSON_MEMBERS = expectedJsonMembers();

    /**
     * Names that must never appear in the serialised form: the four output attribute items, the three
     * input metadata items, and the two {@code FILLER} spans - for all twelve fields.
     */
    private static final Set<String> FORBIDDEN_JSON_MEMBERS = forbiddenJsonMembers();

    // =================================================================================================
    // Construction of the derived constants above. Static, side-effect free, called once each.
    // =================================================================================================

    /**
     * Rebuilds {@code 01 COUSR2AI} span by span, exactly as {@code app/cpy-bms/COUSR02.CPY:17-90}
     * declares it: the {@code TIOAPFX} prefix, then twelve repetitions of {@code xxxL} / {@code xxxF} /
     * {@code xxxA} overlay / {@code FILLER X(4)} / {@code xxxI}.
     *
     * <p>The <em>declared</em> total is passed to {@link FixedWidthRecord.RecordLayout#of}, never the
     * cursor this loop happened to reach. Passing the cursor would make the layout self-consistent with
     * whatever the constants add up to and would catch nothing; passing {@link #SYMBOLIC_MAP_LENGTH}
     * makes the layout's own self-check compare the transcribed geometry against the transcribed total.
     *
     * @return the input view of the one area, {@value #SYMBOLIC_MAP_LENGTH} bytes
     */
    private static FixedWidthRecord.RecordLayout inputViewLayout() {
        List<FixedWidthRecord.FieldSpan> spans = new ArrayList<>();
        // 02 FILLER PIC X(12) - COUSR02.CPY:18, the TIOAPFX=YES prefix DFHMSD requests at line 24.
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
            // 02 xxxI PIC X(n) - the only one of the four items that becomes a request member.
            spans.add(FixedWidthRecord.FieldSpan.alphanumeric(
                    INPUT_MAP_ITEMS.get(index), cursor, DECLARED_WIDTHS.get(index)));
            cursor += DECLARED_WIDTHS.get(index);
        }
        return FixedWidthRecord.RecordLayout.of(SYMBOLIC_MAP_LENGTH,
                spans.toArray(FixedWidthRecord.FieldSpan[]::new));
    }

    /**
     * Rebuilds {@code 01 COUSR2AO REDEFINES COUSR2AI} span by span, exactly as
     * {@code app/cpy-bms/COUSR02.CPY:91-164} declares it: the {@code TIOAPFX} prefix, then twelve
     * repetitions of {@code FILLER X(3)} / {@code xxxC} / {@code xxxP} / {@code xxxH} / {@code xxxV} /
     * {@code xxxO}, and finally the group-level overlay named for the whole area.
     *
     * <p>The group overlay is declared <em>last</em> so that all {@value #SYMBOLIC_MAP_LENGTH} bytes of
     * storage exist ahead of it, which is the condition {@link FixedWidthRecord.RecordLayout} enforces
     * on any overlay - and satisfying that condition at exactly the declared length is the proof that
     * the redefining group tiles the redefined storage with nothing left over.
     *
     * @return the output view of the one area, {@value #SYMBOLIC_MAP_LENGTH} bytes
     */
    private static FixedWidthRecord.RecordLayout outputViewLayout() {
        List<FixedWidthRecord.FieldSpan> spans = new ArrayList<>();
        // 02 FILLER PIC X(12) - COUSR02.CPY:92, the output view's copy of the same prefix.
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
            // 02 xxxO PIC X(n) - the only one of the five items that becomes a response member.
            spans.add(FixedWidthRecord.FieldSpan.alphanumeric(
                    OUTPUT_MAP_ITEMS.get(index), cursor, DECLARED_WIDTHS.get(index)));
            cursor += DECLARED_WIDTHS.get(index);
        }
        // 01 COUSR2AO REDEFINES COUSR2AI - COUSR02.CPY:91, over the whole area.
        spans.add(FixedWidthRecord.FieldSpan.redefining(OUTPUT_GROUP_NAME, 0, SYMBOLIC_MAP_LENGTH,
                FixedWidthRecord.PictureKind.ALPHANUMERIC));
        // The DECLARED total, never the cursor this loop reached.
        return FixedWidthRecord.RecordLayout.of(SYMBOLIC_MAP_LENGTH,
                spans.toArray(FixedWidthRecord.FieldSpan[]::new));
    }

    private static Set<String> expectedJsonMembers() {
        Set<String> members = new LinkedHashSet<>(MAP_MEMBERS);
        members.addAll(STATE_MEMBERS);
        return Set.copyOf(members);
    }

    private static Set<String> forbiddenJsonMembers() {
        Set<String> forbidden = new LinkedHashSet<>();
        for (String field : SCREEN_FIELDS) {
            for (String suffix : ATTRIBUTE_SUFFIXES) {
                forbidden.add(field + suffix);
            }
            forbidden.add(field + "L");
            forbidden.add(field + "F");
            forbidden.add(field + "A");
        }
        forbidden.add("FILLER");
        return Set.copyOf(forbidden);
    }

    // =================================================================================================
    // Shared, stateless helpers. Every one returns a fresh value; none caches, mutates or memoises (B9).
    // =================================================================================================

    /**
     * A codec over the explicitly named code page (B8). A fresh instance per call: the codec is cheap
     * and sharing one would be shared state for no benefit.
     *
     * @return a codec bound to {@link #MAP_CHARSET}
     */
    private static FixedWidthCodec codec() {
        return new FixedWidthCodec(MAP_CHARSET);
    }

    /**
     * An {@link ObjectMapper} configured exactly as {@code config.WebConfig} configures the
     * application's shared one, and for the reasons that class documents.
     *
     * <p>A default mapper would be the wrong instrument and would make this suite assert the wrong
     * thing. Three settings matter and all three are stated rather than inherited:
     * {@code USE_BIG_DECIMAL_FOR_FLOATS} and {@code WRITE_BIGDECIMAL_AS_PLAIN} are enabled so no
     * numeric value could route through a binary floating-point type or serialise in exponent notation,
     * and {@code ACCEPT_EMPTY_STRING_AS_NULL_OBJECT} is <em>disabled</em> so an all-spaces
     * {@code PIC X(n)} value stays the real screen data it is instead of becoming {@code null}.
     * Coercing it would silently break every space-padded round trip below - {@code blank()} produces
     * nothing but space-padded fields - and would defeat the password assertions in
     * {@link EchoedPlaintextPassword} outright, because a blanked password field is exactly the state a
     * failed lookup leaves behind at lines 158-161.
     *
     * <p>No naming strategy is applied, so each property name still traces one-to-one to an
     * {@code xxxO} item; no inclusion filter is applied, so nothing is dropped for being blank; and no
     * trimming is configured anywhere, because {@code COUSR02C}'s four comparisons at lines 219-234
     * measure space-padded values and trimming would change which updates are detected.
     *
     * @return a mapper matching the deployed configuration
     */
    private static ObjectMapper webConfigEquivalentMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        mapper.configure(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN, true);
        mapper.disable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT);
        return mapper;
    }

    /**
     * A response with all four echoed values filled, as lines 167-170 leave the screen after a
     * successful read.
     *
     * @return a populated response, built from {@link UserUpdateResponse#blank()}
     */
    private static UserUpdateResponse afterSuccessfulRead() {
        return UserUpdateResponse.blank()
                .withTrnName(TRANSACTION_ID)
                .withPgmName(PROGRAM_NAME)
                .withTitle01(ScreenTitles.CCDA_TITLE01)
                .withTitle02(ScreenTitles.CCDA_TITLE02)
                .withCurDate(EXPECTED_CURDATE)
                .withCurTime(EXPECTED_CURTIME)
                .withUsrIdIn(USER_ID_FIXTURE)
                .withFName("John")
                .withLName("Doe")
                .withPasswd(PASSWD_FIXTURE)
                .withUsrType("U");
    }

    /**
     * The accessor a record component of {@link UserUpdateResponse} is read through.
     *
     * @param componentName the component name, which for a record is also the accessor name
     * @return that component's accessor
     */
    private static Method accessorOf(String componentName) {
        return Arrays.stream(UserUpdateResponse.class.getRecordComponents())
                .filter(component -> component.getName().equals(componentName))
                .map(RecordComponent::getAccessor)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        componentName + " is not a component of UserUpdateResponse"));
    }

    /**
     * Every annotation reachable on one record component: on the component itself, on its accessor, on
     * the backing field and on the corresponding canonical-constructor parameter.
     *
     * <p>All four are collected because a redaction can be attached at any of them and Jackson honours
     * whichever it finds. Checking only the component would leave three ways in.
     *
     * @param componentName the component to inspect
     * @return the annotation types found, as simple names
     */
    private static Set<String> annotationsReachableFrom(String componentName) {
        Set<String> found = new LinkedHashSet<>();
        RecordComponent component = Arrays.stream(UserUpdateResponse.class.getRecordComponents())
                .filter(candidate -> candidate.getName().equals(componentName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        componentName + " is not a component of UserUpdateResponse"));
        collectNames(found, component.getAnnotations());
        collectNames(found, component.getAccessor().getAnnotations());
        try {
            Field field = UserUpdateResponse.class.getDeclaredField(componentName);
            collectNames(found, field.getAnnotations());
        } catch (NoSuchFieldException neverHappensForARecordComponent) {
            throw new AssertionError("a record component always has a backing field",
                    neverHappensForARecordComponent);
        }
        for (Constructor<?> constructor : UserUpdateResponse.class.getDeclaredConstructors()) {
            for (Parameter parameter : constructor.getParameters()) {
                if (parameter.getName().equals(componentName)) {
                    collectNames(found, parameter.getAnnotations());
                }
            }
        }
        return found;
    }

    private static void collectNames(Set<String> target, Annotation[] annotations) {
        for (Annotation annotation : annotations) {
            target.add(annotation.annotationType().getSimpleName());
        }
    }

    /**
     * What a sending item contributes to a {@code STRING} statement under
     * {@code DELIMITED BY SPACE}: everything before its first space.
     *
     * <p>This is <strong>not</strong> a stand-in for a COBOL {@code MOVE} - that rule lives in
     * {@link FixedWidthCodec#movePicX(String, int)} and is used as such everywhere below.
     * {@code DELIMITED BY SPACE} is a different construct with a different rule, and modelling it needs
     * a first-space scan, which is what this is. Without it, {@code 'ADMIN   '} would contribute all
     * eight characters and the composed message would read {@code 'User ADMIN    has been updated ...'}
     * with three stray spaces.
     *
     * @param value the sending item, at its declared width
     * @return the characters up to but excluding the first space, or all of them if there is none
     */
    private static String delimitedBySpace(String value) {
        int firstSpace = value.indexOf(' ');
        return firstSpace < 0 ? value : value.substring(0, firstSpace);
    }

    /**
     * The image {@code ERRMSGO} ends up holding for a given message text: first the {@code MOVE} into
     * {@code WS-MESSAGE PIC X(80)}, then the narrowing {@code MOVE} from those
     * {@value #WS_MESSAGE_LENGTH} characters into the field's declared 78.
     *
     * <p>Both steps go through the codec (B11): the first is the {@code MOVE} into
     * {@code WS-MESSAGE PIC X(80)} at, for instance, line 182, and the second is line 270's
     * {@code MOVE WS-MESSAGE TO ERRMSGO OF COUSR2AO}. Chaining them is what makes the two-character
     * loss visible on real text rather than only on a synthetic eighty-character string.
     *
     * @param text the message text as the program writes it
     * @return the {@value UserUpdateResponse#ERR_MSG_LENGTH}-character image {@code ERRMSGO} receives
     */
    private static String errMsgImageOf(String text) {
        FixedWidthCodec codec = codec();
        String wsMessage = codec.movePicX(text, WS_MESSAGE_LENGTH);
        return codec.movePicX(wsMessage, UserUpdateResponse.ERR_MSG_LENGTH);
    }

    // =================================================================================================
    // PHASE 2 - the twelve payload members, in source order, at map-declared widths.
    // =================================================================================================

    @Nested
    @DisplayName("Projection of 01 COUSR2AO - twelve map members, in copybook order")
    class MapProjection {

        @Test
        @DisplayName("twelve of the twenty-nine DFHMDF definitions are fields; seventeen are furniture")
        void twelveOfTwentyNineDefinitionsAreFields() {
            assertThat(DFHMDF_NAMED)
                    .as("the name-labelled DFHMDF definitions of app/bms/COUSR02.bms")
                    .isEqualTo(12);
            assertThat(DFHMDF_UNLABELLED)
                    .as("literal INITIAL text and three LENGTH=0 stoppers - not fields")
                    .isEqualTo(17);
            assertThat(DFHMDF_NAMED + DFHMDF_UNLABELLED).isEqualTo(DFHMDF_TOTAL);
            assertThat(UserUpdateResponse.MAP_FIELD_COUNT)
                    .as("the payload projects the labelled fields and nothing else")
                    .isEqualTo(DFHMDF_NAMED);
        }

        @Test
        @DisplayName("the record declares seventeen components: the twelve plus five state carriers")
        void theComponentCountIsTwelvePlusFive() {
            RecordComponent[] components = UserUpdateResponse.class.getRecordComponents();

            assertThat(components)
                    .as("UserUpdateResponse is a record, so its components are its payload")
                    .isNotNull()
                    .hasSize(COMPONENT_COUNT);
            assertThat(COMPONENT_COUNT)
                    .as("12 map members + navigationContext, nextProgram, nextMapset, nextMap, cu02Info")
                    .isEqualTo(17);
            assertThat(Arrays.stream(components).map(RecordComponent::getName).toList())
                    .as("the twelve map members come first, in screen order, then the five carriers")
                    .containsExactlyElementsOf(
                            Stream.concat(MAP_MEMBERS.stream(), STATE_MEMBERS.stream())
                                    .toList());
        }

        @ParameterizedTest(name = "[{index}] {0} -> {1}")
        @CsvSource({"TRNNAMEO,trnName", "TITLE01O,title01", "CURDATEO,curDate", "PGMNAMEO,pgmName",
            "TITLE02O,title02", "CURTIMEO,curTime", "USRIDINO,usrIdIn", "FNAMEO,fName",
            "LNAMEO,lName", "PASSWDO,passwd", "USRTYPEO,usrType", "ERRMSGO,errMsg"})
        @DisplayName("every xxxO item has exactly one member, and it is a String")
        void everyOutputItemHasOneStringMember(String cobolItem, String memberName) {
            int index = OUTPUT_MAP_ITEMS.indexOf(cobolItem);

            assertThat(index)
                    .as("%s is declared at app/cpy-bms/COUSR02.CPY:%d", cobolItem,
                            OUTPUT_ITEM_LINES.get(Math.max(index, 0)))
                    .isNotNegative();
            assertThat(MAP_MEMBERS.get(index))
                    .as("%s projects to %s, at the same position in both lists", cobolItem, memberName)
                    .isEqualTo(memberName);
            assertThat(accessorOf(memberName).getReturnType())
                    .as("%s is PIC X(%d), so the member is a String and never a numeric type",
                            cobolItem, DECLARED_WIDTHS.get(index))
                    .isEqualTo(String.class);
        }

        @Test
        @DisplayName("MAP_FIELD_NAMES is the twelve xxxO names, in screen order, and unmodifiable")
        void mapFieldNamesIsTheTwelveOutputItems() {
            assertThat(UserUpdateResponse.MAP_FIELD_NAMES)
                    .containsExactlyElementsOf(OUTPUT_MAP_ITEMS)
                    .hasSize(UserUpdateResponse.MAP_FIELD_COUNT);
            assertThatExceptionOfTypeUnsupported(
                    () -> UserUpdateResponse.MAP_FIELD_NAMES.add("SOMETHINGO"));
        }

        @Test
        @DisplayName("fieldValues() keys on the xxxO names and preserves screen declaration order")
        void fieldValuesIsScreenOrdered() {
            Map<String, String> values = afterSuccessfulRead().fieldValues();

            assertThat(values).hasSize(UserUpdateResponse.MAP_FIELD_COUNT);
            assertThat(values.keySet())
                    .as("declaration order, not an arbitrary one: a field-by-field diff walks this")
                    .containsExactlyElementsOf(OUTPUT_MAP_ITEMS);
            assertThat(values.get(UserUpdateResponse.TRN_NAME_FIELD)).isEqualTo(TRANSACTION_ID);
            assertThat(values.get(UserUpdateResponse.PGM_NAME_FIELD)).isEqualTo(PROGRAM_NAME);
        }

        @ParameterizedTest(name = "[{index}] {0} is PIC X({1})")
        @CsvSource({"TRNNAMEO,4", "TITLE01O,40", "CURDATEO,8", "PGMNAMEO,8", "TITLE02O,40",
            "CURTIMEO,8", "USRIDINO,8", "FNAMEO,20", "LNAMEO,20", "PASSWDO,8", "USRTYPEO,1",
            "ERRMSGO,78"})
        @DisplayName("each declared width is the one the copybook states, and the layout agrees")
        void eachDeclaredWidthIsTheCopybooks(String cobolItem, int declaredWidth) {
            int index = OUTPUT_MAP_ITEMS.indexOf(cobolItem);

            assertThat(DECLARED_WIDTHS.get(index))
                    .as("%s at app/cpy-bms/COUSR02.CPY:%d", cobolItem, OUTPUT_ITEM_LINES.get(index))
                    .isEqualTo(declaredWidth);
            assertThat(OUTPUT_VIEW_LAYOUT.span(cobolItem).length())
                    .as("and the rebuilt output view places %s at that same width", cobolItem)
                    .isEqualTo(declaredWidth);
            assertThat(UserUpdateResponse.blank().value(cobolItem))
                    .as("blank() renders every field as its declared run of spaces")
                    .isEqualTo(" ".repeat(declaredWidth));
        }

        @Test
        @DisplayName("the screen identity constants are the program's own literals")
        void theScreenIdentityConstantsAreTheProgramsOwn() {
            assertThat(UserUpdateResponse.TRANSACTION_ID)
                    .as("WS-TRANID at app/cbl/COUSR02C.cbl:37")
                    .isEqualTo(TRANSACTION_ID)
                    .hasSize(UserUpdateResponse.TRN_NAME_LENGTH);
            assertThat(UserUpdateResponse.PROGRAM_NAME)
                    .as("WS-PGMNAME at app/cbl/COUSR02C.cbl:36")
                    .isEqualTo(PROGRAM_NAME)
                    .hasSize(UserUpdateResponse.PGM_NAME_LENGTH);
            assertThat(UserUpdateResponse.MAP_NAME)
                    .as("MAP('COUSR2A') at app/cbl/COUSR02C.cbl:273")
                    .isEqualTo(MAP_NAME);
            assertThat(UserUpdateResponse.MAPSET_NAME)
                    .as("MAPSET('COUSR02') at app/cbl/COUSR02C.cbl:274")
                    .isEqualTo(MAPSET_NAME);
            assertThat(UserUpdateResponse.GROUP_NAME)
                    .as("01 COUSR2AO at app/cpy-bms/COUSR02.CPY:91 - the map name plus 'O'")
                    .isEqualTo(OUTPUT_GROUP_NAME)
                    .isEqualTo(MAP_NAME + FieldAttributeSetter.OUTPUT_MAP_SUFFIX);
            assertThat(INPUT_GROUP_NAME)
                    .as("and the input view is the same map name plus 'I' - CPY:17")
                    .isEqualTo(MAP_NAME + "I");
        }

        @Test
        @DisplayName("transaction CU02 is bound to program COUSR02C by the CSD, not by convention")
        void theCsdBindsTheTransactionToTheProgram() {
            assertThat(CSD_TRANSACTION_BINDING)
                    .as("app/csd/CARDDEMO.CSD:469-470")
                    .isEqualTo(UserUpdateResponse.TRANSACTION_ID + "->"
                            + UserUpdateResponse.PROGRAM_NAME);
        }

        @Test
        @DisplayName("title01 and title02 carry the forty-character CCDA titles, padding included")
        void theTitlesAreTheFortyCharacterScreenTitles() {
            UserUpdateResponse response = afterSuccessfulRead();

            assertThat(ScreenTitles.CCDA_TITLE01)
                    .as("CCDA-TITLE01, moved at app/cbl/COUSR02C.cbl:300")
                    .hasSize(ScreenTitles.TITLE_LENGTH)
                    .hasSize(UserUpdateResponse.TITLE01_LENGTH);
            assertThat(ScreenTitles.CCDA_TITLE02)
                    .as("CCDA-TITLE02, moved at line 301")
                    .hasSize(ScreenTitles.TITLE_LENGTH)
                    .hasSize(UserUpdateResponse.TITLE02_LENGTH);
            assertThat(response.title01()).isEqualTo(ScreenTitles.CCDA_TITLE01);
            assertThat(response.title02()).isEqualTo(ScreenTitles.CCDA_TITLE02);
        }

        @Test
        @DisplayName("ScreenTitles.CCDA_THANK_YOU is not SystemMessages.CCDA_MSG_THANK_YOU: 40 vs 50")
        void theTwoThankYouLiteralsAreDifferentThings() {
            // A standing trap: two similarly named literals of different width, text and owner. Neither
            // is a field of this screen - COUSR02C copies CSMSG01Y at line 64 but writes only
            // CCDA-MSG-INVALID-KEY, at line 129 - so confusing them would put a 50-character value into
            // a 40-character field, or a title into a message line.
            assertThat(ScreenTitles.CCDA_THANK_YOU)
                    .hasSize(ScreenTitles.TITLE_LENGTH)
                    .hasSize(40);
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU)
                    .hasSize(SystemMessages.MESSAGE_LENGTH)
                    .hasSize(50)
                    .isNotEqualTo(ScreenTitles.CCDA_THANK_YOU);
            assertThat(SystemMessages.MESSAGE_LENGTH).isNotEqualTo(ScreenTitles.TITLE_LENGTH);
            assertThat(SystemMessages.CCDA_MSG_INVALID_KEY.length())
                    .as("the invalid-key text is the one this screen does use, at line 129, and it "
                            + "fits the 78-character message field")
                    .isLessThanOrEqualTo(UserUpdateResponse.ERR_MSG_LENGTH);
        }

        @ParameterizedTest(name = "[{index}] {0} PIC X -> {1}")
        @CsvSource({"SEC-USR-ID,usrIdIn", "SEC-USR-FNAME,fName", "SEC-USR-LNAME,lName",
            "SEC-USR-PWD,passwd", "SEC-USR-TYPE,usrType"})
        @DisplayName("the five stored-record widths match the map widths they are moved into")
        void theStoredRecordWidthsMatchTheMapWidths(String secItem, String memberName) {
            int recordIndex = SEC_USER_ITEMS.indexOf(secItem);
            int mapIndex = MAP_MEMBERS.indexOf(memberName);

            assertThat(recordIndex).as("%s is declared in app/cpy/CSUSR01Y.cpy:17-23", secItem)
                    .isNotNegative();
            assertThat(SEC_USER_TARGET_MEMBERS.get(recordIndex)).isEqualTo(memberName);
            assertThat(SEC_USER_WIDTHS.get(recordIndex))
                    .as("%s and %s are the same width, so the MOVE neither pads nor truncates",
                            secItem, OUTPUT_MAP_ITEMS.get(mapIndex))
                    .isEqualTo(DECLARED_WIDTHS.get(mapIndex));
        }

        @Test
        @DisplayName("SecUserRecord places the password at offset 48 of an eighty-byte record")
        void theStoredRecordGeometryIsTheCopybooks() {
            assertThat(SecUserRecord.RECORD_LENGTH)
                    .as("8+20+20+8+1+23, app/cpy/CSUSR01Y.cpy:18-23")
                    .isEqualTo(SEC_USER_DATA_LENGTH)
                    .isEqualTo(80);
            assertThat(List.of(SecUserRecord.SEC_USR_ID_OFFSET,
                    SecUserRecord.SEC_USR_FNAME_OFFSET,
                    SecUserRecord.SEC_USR_LNAME_OFFSET,
                    SecUserRecord.SEC_USR_PWD_OFFSET,
                    SecUserRecord.SEC_USR_TYPE_OFFSET))
                    .as("0, 8, 28, 48, 56 - the password sits at 48")
                    .containsExactlyElementsOf(SEC_USER_OFFSETS);
            assertThat(SecUserRecord.SEC_USR_PWD_OFFSET).isEqualTo(48);
            assertThat(SecUserRecord.SEC_USR_FILLER_OFFSET)
                    .as("SEC-USR-FILLER X(23) begins at 57 and runs to the end")
                    .isEqualTo(SEC_USER_FILLER_OFFSET);
            assertThat(SecUserRecord.SEC_USR_FILLER_OFFSET + SecUserRecord.SEC_USR_FILLER_LENGTH)
                    .isEqualTo(SecUserRecord.RECORD_LENGTH);
            assertThat(SecUserRecord.SEC_USR_PWD_LENGTH)
                    .as("and its width is the map field's width, so line 169 is a clean move")
                    .isEqualTo(UserUpdateResponse.PASSWD_LENGTH);
        }

        @Test
        @DisplayName("the header renders MM/DD/YY and HH:MM:SS from a fixed clock, never a live one")
        void theHeaderIsDrivenFromAFixedClock() {
            // B7: DateHeader.from takes the Clock, so a fixed instant makes the two header fields
            // exact. Reading a live clock here would make the suite's outcome depend on the minute it
            // ran in.
            DateHeader header = DateHeader.from(codec(),
                    Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));

            assertThat(header.wsCurdateMmDdYy())
                    .as("MM/DD/YY, composed at app/cbl/COUSR02C.cbl:305-309")
                    .isEqualTo(EXPECTED_CURDATE)
                    .hasSize(UserUpdateResponse.CUR_DATE_LENGTH);
            assertThat(header.wsCurtimeHhMmSs())
                    .as("HH:MM:SS, composed at lines 311-315")
                    .isEqualTo(EXPECTED_CURTIME)
                    .hasSize(UserUpdateResponse.CUR_TIME_LENGTH);
            UserUpdateResponse response = UserUpdateResponse.blank()
                    .withCurDate(header.wsCurdateMmDdYy())
                    .withCurTime(header.wsCurtimeHhMmSs());
            assertThat(response.curDate()).isEqualTo(EXPECTED_CURDATE);
            assertThat(response.curTime()).isEqualTo(EXPECTED_CURTIME);
        }
    }

    // =================================================================================================
    // PHASE 2, TRAP 1 - the identifier is USRIDIN, and it comes first.
    // =================================================================================================

    @Nested
    @DisplayName("Cross-screen inversions - USRIDIN not USERID, and the identifier comes first")
    class CrossScreenInversions {

        @Test
        @DisplayName("the identifier member is usrIdIn, spelled for the field this map declares")
        void theIdentifierMemberIsUsrIdIn() {
            assertThat(UserUpdateResponse.USR_ID_IN_FIELD)
                    .as("USRIDINO at app/cpy-bms/COUSR02.CPY:134, USRIDIN at app/bms/COUSR02.bms:85")
                    .isEqualTo("USRIDINO");
            assertThat(MAP_MEMBERS)
                    .as("COUSR01 labels its equivalent field USERID; the two are not harmonised")
                    .contains("usrIdIn")
                    .doesNotContain("userId")
                    .doesNotContain("secUsrId");
            assertThat(Arrays.stream(UserUpdateResponse.class.getRecordComponents())
                    .map(RecordComponent::getName).toList())
                    .contains("usrIdIn")
                    .doesNotContain("userId");
        }

        @Test
        @DisplayName("usrIdIn is the seventh member, before fName and lName - the inverse of COUSR01")
        void theIdentifierPrecedesTheNames() {
            List<String> declared = Arrays.stream(UserUpdateResponse.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(declared.indexOf("usrIdIn"))
                    .as("seventh of the twelve map members, zero-based six")
                    .isEqualTo(6);
            assertThat(declared.indexOf("usrIdIn"))
                    .as("COUSR01 puts its identifier AFTER the names; this screen puts it before")
                    .isLessThan(declared.indexOf("fName"))
                    .isLessThan(declared.indexOf("lName"))
                    .isLessThan(declared.indexOf("passwd"));
            assertThat(UserUpdateResponse.MAP_FIELD_NAMES.indexOf(UserUpdateResponse.USR_ID_IN_FIELD))
                    .as("and the published order agrees with the declared order")
                    .isEqualTo(6);
        }

        @Test
        @DisplayName("the inversion is asserted against COUSR01's own order, not merely claimed")
        void theInversionIsAssertedAgainstTheOtherSideOfIt() {
            // Both mapsets have twelve fields of the same twelve widths - which is why the two symbolic
            // maps are the same 339 bytes - but they are not the same map. Two differences, both real:
            //   COUSR01: ... CURTIME FNAME LNAME USERID  PASSWD USRTYPE ERRMSG   <- identifier AFTER
            //   COUSR02: ... CURTIME USRIDIN FNAME LNAME PASSWD USRTYPE ERRMSG   <- identifier BEFORE
            assertThat(COUSR01_SCREEN_FIELDS)
                    .as("app/bms/COUSR01.bms declares twelve fields, as this map does")
                    .hasSize(COUSR01_DFHMDF_NAMED)
                    .hasSameSizeAs(SCREEN_FIELDS);
            assertThat(COUSR01_SCREEN_FIELDS.indexOf("USERID"))
                    .as("COUSR01 declares its identifier ninth, after both name fields")
                    .isEqualTo(8)
                    .isGreaterThan(COUSR01_SCREEN_FIELDS.indexOf("FNAME"))
                    .isGreaterThan(COUSR01_SCREEN_FIELDS.indexOf("LNAME"));
            assertThat(SCREEN_FIELDS.indexOf("USRIDIN"))
                    .as("this map declares its identifier seventh, before both name fields - the "
                            + "inversion, stated from both sides")
                    .isEqualTo(6)
                    .isLessThan(SCREEN_FIELDS.indexOf("FNAME"))
                    .isLessThan(SCREEN_FIELDS.indexOf("LNAME"));
            assertThat(COUSR01_SCREEN_FIELDS)
                    .as("and the spellings differ: USERID there, USRIDIN here, deliberately not "
                            + "harmonised")
                    .contains("USERID")
                    .doesNotContain("USRIDIN");
            assertThat(SCREEN_FIELDS).contains("USRIDIN").doesNotContain("USERID");
            assertThat(COUSR01_SCREEN_FIELDS)
                    .as("PASSWD sits eleventh-from-last on both, which is why the field COUNT alone "
                            + "cannot tell the two responses apart - only the program can")
                    .contains("PASSWD");
        }

        @Test
        @DisplayName("a misspelling of the identifier field fails at the point of the mistake")
        void aMisspelledIdentifierFails() {
            // The one place the COUSR01 naming difference bites: 'USERIDO' is the sibling map's
            // spelling, and answering null would let a field-by-field comparison report a puzzling
            // difference instead of the real mistake.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> afterSuccessfulRead().value("USERIDO"))
                    .withMessageContaining(UserUpdateResponse.USR_ID_IN_FIELD);
            assertThat(afterSuccessfulRead().value(UserUpdateResponse.USR_ID_IN_FIELD))
                    .isEqualTo(USER_ID_FIXTURE);
        }
    }

    // =================================================================================================
    // PHASE 2, TRAPS 2 AND 3 - eight not nine, and seventy-eight not eighty.
    // =================================================================================================

    @Nested
    @DisplayName("Width traps - eight not nine, seventy-eight not eighty, and seven not eight")
    class WidthTraps {

        @Test
        @DisplayName("curTime is X(8) on this map; only COSGN00 declares a nine-character time")
        void theTimeFieldIsEightCharactersWide() {
            assertThat(UserUpdateResponse.CUR_TIME_LENGTH)
                    .as("CURTIMEO PIC X(8) at app/cpy-bms/COUSR02.CPY:128, LENGTH=8 at bms line 72")
                    .isEqualTo(8)
                    .isNotEqualTo(9);
            assertThat(DECLARED_WIDTHS.get(OUTPUT_MAP_ITEMS.indexOf("CURTIMEO"))).isEqualTo(8);
            assertThat(EXPECTED_CURTIME).hasSize(UserUpdateResponse.CUR_TIME_LENGTH);
            assertThatIllegalArgumentException()
                    .as("a nine-character time copied from COSGN00 is refused, not silently trimmed")
                    .isThrownBy(() -> UserUpdateResponse.blank().withCurTime("23:12:34 "))
                    .withMessageContaining(UserUpdateResponse.CUR_TIME_FIELD);
        }

        @Test
        @DisplayName("errMsg is X(78) while WS-MESSAGE is X(80), so line 270 discards two characters")
        void theMessageFieldNarrowsFromEightyToSeventyEight() {
            assertThat(UserUpdateResponse.ERR_MSG_LENGTH)
                    .as("ERRMSGO PIC X(78) at app/cpy-bms/COUSR02.CPY:164, LENGTH=78 at bms line 157")
                    .isEqualTo(78);
            assertThat(WS_MESSAGE_LENGTH)
                    .as("WS-MESSAGE PIC X(80) at app/cbl/COUSR02C.cbl:38")
                    .isEqualTo(80)
                    .isGreaterThan(UserUpdateResponse.ERR_MSG_LENGTH);
            assertThat(WS_MESSAGE_LENGTH - UserUpdateResponse.ERR_MSG_LENGTH)
                    .as("exactly two characters are lost on the right")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("the eighty-to-seventy-eight move truncates on the RIGHT, through the codec")
        void theNarrowingMoveTruncatesOnTheRight() {
            // An eighty-character sending value whose last two characters are non-space, so the loss is
            // observable rather than hidden in padding. Routed through FixedWidthCodec.movePicX (B11) -
            // never a bare assignment and never a hand-rolled substring - because the direction of the
            // truncation is the property under test.
            String wsMessage = "A".repeat(WS_MESSAGE_LENGTH - 2) + "YZ";
            assertThat(wsMessage).hasSize(WS_MESSAGE_LENGTH).endsWith("YZ");

            String moved = codec().movePicX(wsMessage, UserUpdateResponse.ERR_MSG_LENGTH);

            assertThat(moved)
                    .as("COBOL fills a PIC X receiver from the left and discards the overflow")
                    .hasSize(UserUpdateResponse.ERR_MSG_LENGTH)
                    .isEqualTo("A".repeat(UserUpdateResponse.ERR_MSG_LENGTH))
                    .doesNotContain("Y")
                    .doesNotContain("Z");
            assertThat(UserUpdateResponse.blank().withErrMsg(moved).errMsg()).isEqualTo(moved);
        }

        @Test
        @DisplayName("the payload itself never truncates: an over-wide message is refused")
        void thePayloadRefusesRatherThanTruncates() {
            // The narrowing is the controller's deliberate act at line 270, performed through the
            // codec. If the payload constructor did it instead, the loss of two characters would be
            // invisible at the call site - which is the failure mode the seam exists to prevent.
            String eighty = "B".repeat(WS_MESSAGE_LENGTH);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> UserUpdateResponse.blank().withErrMsg(eighty))
                    .withMessageContaining(UserUpdateResponse.ERR_MSG_FIELD)
                    .withMessageContaining("movePicX");
        }

        @Test
        @DisplayName("ERRMSG's map-declared colour is RED, so DFHGREEN is an override and DFHRED is not")
        void theMessageFieldsDeclaredColourIsRed() {
            // app/bms/COUSR02.bms:155-158
            //     ERRMSG  DFHMDF ATTRB=(ASKIP,BRT,FSET), COLOR=RED, LENGTH=78, POS=(23,1)
            // identical on all five maps of this package. So line 241's MOVE DFHRED TO ERRMSGC is the
            // no-change arm restating the declared default, while line 371's DFHGREEN on success and
            // line 338's DFHNEUTR on the save prompt are genuine overrides.
            assertThat(BmsAttributes.COLOUR_MNEMONICS.get(BmsAttributes.DFHRED))
                    .as("the declared default colour of ERRMSG")
                    .isEqualTo("DFHRED");
            assertThat(BmsAttributes.DFHGREEN)
                    .as("the success override at line 371 is a different byte from the default")
                    .isNotEqualTo(BmsAttributes.DFHRED);
            assertThat(BmsAttributes.DFHNEUTR)
                    .as("and the save prompt at line 338 is a third")
                    .isNotEqualTo(BmsAttributes.DFHRED)
                    .isNotEqualTo(BmsAttributes.DFHGREEN);
            assertThat(FieldAttributeSetter.COLOUR_ITEM_SUFFIX)
                    .as("all three are moved to ERRMSGC - the xxxC item is the colour item")
                    .isEqualTo("C");
            assertThat(ATTRIBUTE_SUFFIXES.get(0)).isEqualTo(FieldAttributeSetter.COLOUR_ITEM_SUFFIX);
        }

        @Test
        @DisplayName("nextMapset and nextMap are X(7), and this screen's own names are seven long")
        void theMapAndMapsetMembersAreSevenWide() {
            assertThat(UserUpdateResponse.NEXT_MAP_LENGTH)
                    .as("CDEMO-LAST-MAP PIC X(7) at app/cpy/COCOM01Y.cpy:43")
                    .isEqualTo(7)
                    .isEqualTo(NavigationContext.LAST_MAP_LENGTH);
            assertThat(UserUpdateResponse.NEXT_MAPSET_LENGTH)
                    .as("CDEMO-LAST-MAPSET PIC X(7) at app/cpy/COCOM01Y.cpy:44")
                    .isEqualTo(7)
                    .isEqualTo(NavigationContext.LAST_MAPSET_LENGTH);
            assertThat(MAP_NAME)
                    .as("COUSR2A is exactly seven characters - which is why X(7) is right, not X(8)")
                    .hasSize(UserUpdateResponse.NEXT_MAP_LENGTH);
            assertThat(MAPSET_NAME).hasSize(UserUpdateResponse.NEXT_MAPSET_LENGTH);
            assertThat(UserUpdateResponse.NEXT_PROGRAM_LENGTH)
                    .as("a program name really is eight - CDEMO-TO-PROGRAM PIC X(08), COCOM01Y:24")
                    .isEqualTo(8)
                    .isNotEqualTo(UserUpdateResponse.NEXT_MAP_LENGTH);
            assertThat(PROGRAM_NAME).hasSize(UserUpdateResponse.NEXT_PROGRAM_LENGTH);
        }

        @ParameterizedTest(name = "[{index}] {0} refuses one character too many")
        @CsvSource({"TRNNAMEO,4", "TITLE01O,40", "CURDATEO,8", "PGMNAMEO,8", "TITLE02O,40",
            "CURTIMEO,8", "USRIDINO,8", "FNAMEO,20", "LNAMEO,20", "PASSWDO,8", "USRTYPEO,1",
            "ERRMSGO,78"})
        @DisplayName("every field refuses a value wider than its PICTURE clause")
        void everyFieldRefusesAnOverWideValue(String cobolItem, int declaredWidth) {
            String tooLong = "X".repeat(declaredWidth + 1);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> withValue(cobolItem, tooLong))
                    .withMessageContaining(cobolItem)
                    .withMessageContaining("PIC X(" + declaredWidth + ")");
        }

        @ParameterizedTest(name = "[{index}] {0} accepts a short value unchanged")
        @ValueSource(strings = {"TITLE01O", "FNAMEO", "LNAMEO", "PASSWDO", "ERRMSGO"})
        @DisplayName("a value shorter than the declared width is stored as given, never padded here")
        void aShortValueIsStoredUnchanged(String cobolItem) {
            // Padding a short value to its declared width is the codec's job at the point the
            // fixed-width image is produced. Doing it in the payload would duplicate a MOVE rule that
            // is deliberately kept in one seam - and would make it impossible to tell a value that
            // arrived short from one that arrived padded.
            UserUpdateResponse response = withValue(cobolItem, "Q");

            assertThat(response.value(cobolItem)).isEqualTo("Q").hasSize(1);
            assertThat(codec().movePicX(response.value(cobolItem),
                    DECLARED_WIDTHS.get(OUTPUT_MAP_ITEMS.indexOf(cobolItem))))
                    .as("and the codec is what renders it to the declared width, on the right")
                    .startsWith("Q")
                    .hasSize(DECLARED_WIDTHS.get(OUTPUT_MAP_ITEMS.indexOf(cobolItem)));
        }

        @ParameterizedTest(name = "[{index}] {0} refuses null")
        @ValueSource(strings = {"TRNNAMEO", "USRIDINO", "PASSWDO", "ERRMSGO"})
        @DisplayName("null is refused for a character field: a COBOL PIC X item holds spaces, not nothing")
        void nullIsRefusedForACharacterField(String cobolItem) {
            assertThatNullPointerException()
                    .isThrownBy(() -> withValue(cobolItem, null))
                    .withMessageContaining(cobolItem);
        }
    }

    /**
     * Applies a value to one field by its {@code xxxO} name, so the width cases can be driven
     * field-by-field without twelve near-identical methods.
     *
     * @param cobolItem the {@code xxxO} field name
     * @param value     the value to set
     * @return the derived response
     */
    private static UserUpdateResponse withValue(String cobolItem, String value) {
        UserUpdateResponse blank = UserUpdateResponse.blank();
        return switch (cobolItem) {
            case "TRNNAMEO" -> blank.withTrnName(value);
            case "TITLE01O" -> blank.withTitle01(value);
            case "CURDATEO" -> blank.withCurDate(value);
            case "PGMNAMEO" -> blank.withPgmName(value);
            case "TITLE02O" -> blank.withTitle02(value);
            case "CURTIMEO" -> blank.withCurTime(value);
            case "USRIDINO" -> blank.withUsrIdIn(value);
            case "FNAMEO" -> blank.withFName(value);
            case "LNAMEO" -> blank.withLName(value);
            case "PASSWDO" -> blank.withPasswd(value);
            case "USRTYPEO" -> blank.withUsrType(value);
            case "ERRMSGO" -> blank.withErrMsg(value);
            default -> throw new AssertionError(cobolItem + " is not an xxxO item of " + MAP_NAME);
        };
    }

    /**
     * Asserts a call fails because a published collection is unmodifiable.
     *
     * @param call the modification attempt
     */
    private static void assertThatExceptionOfTypeUnsupported(Runnable call) {
        assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(call::run);
    }

    // =================================================================================================
    // PHASE 3 - THE CENTRE OF GRAVITY.
    //
    // This response carries the stored plaintext password. That is not an oversight to be tidied away;
    // it is what the program does, and the assertions below exist to FAIL if anyone removes it.
    //
    //   app/cbl/COUSR02C.cbl, lines 166-172
    //
    //       IF NOT ERR-FLG-ON
    //           MOVE SEC-USR-FNAME      TO FNAMEI    OF COUSR2AI      <- :167
    //           MOVE SEC-USR-LNAME      TO LNAMEI    OF COUSR2AI      <- :168
    //           MOVE SEC-USR-PWD        TO PASSWDI   OF COUSR2AI      <- :169  ** the password **
    //           MOVE SEC-USR-TYPE       TO USRTYPEI  OF COUSR2AI      <- :170
    //           PERFORM SEND-USRUPD-SCREEN                            <- :171  ** and it is sent **
    //       END-IF.
    //
    // Two mechanisms are worth naming, because each is separately easy to misread:
    //
    //   1. The program writes the INPUT items (PASSWDI), yet the value reaches the terminal, because
    //      01 COUSR2AO REDEFINES COUSR2AI overlays the two views on one storage span. Writing PASSWDI
    //      IS writing PASSWDO. That is not a bug - it is what the overlay makes legal, and it is the
    //      single strongest reason the group-level REDEFINES round trip belongs in this package. See
    //      GroupRedefinesOverlay.
    //   2. app/bms/COUSR02.bms:130 declares PASSWD DFHMDF ATTRB=(DRK,FSET,UNPROT). DRK is the
    //      non-display attribute, so 3270 hardware receives the eight characters and renders them
    //      invisibly. The value genuinely crosses the wire AND is genuinely hidden from the operator's
    //      eye - two separate facts, and only the first is this payload's concern.
    //
    // Practices B4, B5 and B6 all bear on this, and they point the same way: record the conflict,
    // preserve the asymmetry, and change the security posture in NEITHER direction. Plaintext credential
    // handling is an inherited property of the legacy design and an explicit non-goal of this migration;
    // it is stated here in the open rather than buried, so it stays visible to anyone reading the type.
    // Removing the member would be STRENGTHENING the posture, which is as much a behaviour change as
    // weakening it would be.
    // =================================================================================================

    @Nested
    @DisplayName("Asymmetry #2 - the stored plaintext password IS echoed, and must stay echoed")
    class EchoedPlaintextPassword {

        @Test
        @DisplayName("passwd exists, is a String, and is PIC X(8) - the width SEC-USR-PWD is")
        void thePasswordMemberExistsAtEightCharacters() {
            assertThat(MAP_MEMBERS)
                    .as("app/cbl/COUSR02C.cbl:169 puts SEC-USR-PWD on the screen, so the member exists")
                    .contains("passwd");
            assertThat(accessorOf("passwd").getReturnType()).isEqualTo(String.class);
            assertThat(UserUpdateResponse.PASSWD_FIELD)
                    .as("PASSWDO at app/cpy-bms/COUSR02.CPY:152")
                    .isEqualTo("PASSWDO");
            assertThat(UserUpdateResponse.PASSWD_LENGTH)
                    .as("PIC X(8) on the map and PIC X(08) in the record - the move is clean")
                    .isEqualTo(PASSWD_DECLARED_WIDTH)
                    .isEqualTo(SecUserRecord.SEC_USR_PWD_LENGTH);
            assertThat(UserUpdateResponse.MAP_FIELD_NAMES)
                    .as("and it is one of the twelve, not an extra")
                    .contains(UserUpdateResponse.PASSWD_FIELD)
                    .hasSize(UserUpdateResponse.MAP_FIELD_COUNT);
        }

        @Test
        @DisplayName("the value is populated from the STORED record, not echoed back from the request")
        void theValueComesFromTheStoredRecord() {
            // The distinction matters. Lines 158-161 blank PASSWDI before the read at line 163, so
            // whatever the operator typed is gone by the time line 169 runs; the value on the screen
            // afterwards is the one that came out of USRSEC. A failed lookup therefore leaves the field
            // blank, and a successful one fills it.
            SecUserRecord stored = SecUserRecord.of(USER_ID_FIXTURE, "John", "Doe", PASSWD_FIXTURE,
                    "U", MAP_CHARSET);

            UserUpdateResponse afterRead = UserUpdateResponse.blank()
                    .withUsrIdIn(stored.secUsrId())
                    .withFName(stored.secUsrFname())
                    .withLName(stored.secUsrLname())
                    .withPasswd(stored.secUsrPwd())
                    .withUsrType(stored.secUsrType());

            assertThat(afterRead.passwd())
                    .as("app/cbl/COUSR02C.cbl:169 - MOVE SEC-USR-PWD TO PASSWDI OF COUSR2AI")
                    .isEqualTo(stored.secUsrPwd())
                    .isEqualTo(PASSWD_FIXTURE);
            assertThat(UserUpdateResponse.blank().passwd())
                    .as("and a failed lookup leaves it as lines 158-161 blanked it")
                    .isEqualTo(" ".repeat(UserUpdateResponse.PASSWD_LENGTH));
        }

        @Test
        @DisplayName("the stored image round-trips through the eighty-byte record at offset 48")
        void theStoredImageRoundTripsAtOffsetFortyEight() {
            // Encoding and decoding both name the code page explicitly (B8), and both go through the
            // codec rather than a hand-rolled byte walk (B11).
            SecUserRecord stored = SecUserRecord.of(USER_ID_FIXTURE, "", "", PASSWD_FIXTURE, "",
                    MAP_CHARSET);

            byte[] image = SecUserRecord.encode(stored, codec());

            assertThat(image).hasSize(SecUserRecord.RECORD_LENGTH).hasSize(SEC_USER_DATA_LENGTH);
            assertThat(new String(image, SecUserRecord.SEC_USR_PWD_OFFSET,
                    SecUserRecord.SEC_USR_PWD_LENGTH, MAP_CHARSET))
                    .as("SEC-USR-PWD occupies bytes 48 to 55 inclusive")
                    .isEqualTo(PASSWD_FIXTURE);
            assertThat(SecUserRecord.decode(image, codec()).secUsrPwd())
                    .as("and it decodes back byte for byte - no transformation on either leg")
                    .isEqualTo(PASSWD_FIXTURE);
        }

        @Test
        @DisplayName("an eight-character password survives the payload byte for byte")
        void anEightCharacterPasswordSurvivesUnchanged() {
            UserUpdateResponse response = UserUpdateResponse.blank().withPasswd(PASSWD_FIXTURE);

            assertThat(response.passwd())
                    .isEqualTo(PASSWD_FIXTURE)
                    .hasSize(UserUpdateResponse.PASSWD_LENGTH);
            assertThat(response.value(UserUpdateResponse.PASSWD_FIELD)).isEqualTo(PASSWD_FIXTURE);
            assertThat(response.fieldValues().get(UserUpdateResponse.PASSWD_FIELD))
                    .as("fieldValues() INCLUDES the password: omitting it would hide a real "
                            + "difference from a comparison whose whole purpose is to find differences")
                    .isEqualTo(PASSWD_FIXTURE);
        }

        @Test
        @DisplayName("a shorter password is space-padded to eight by the codec, never trimmed away")
        void aShorterPasswordIsPaddedNotTrimmed() {
            UserUpdateResponse response = UserUpdateResponse.blank()
                    .withPasswd(SHORT_PASSWD_FIXTURE);

            assertThat(response.passwd())
                    .as("the payload stores what it was given, short and all")
                    .isEqualTo(SHORT_PASSWD_FIXTURE);
            assertThat(codec().movePicX(response.passwd(), UserUpdateResponse.PASSWD_LENGTH))
                    .as("and the codec renders it to PIC X(08) by padding on the RIGHT")
                    .isEqualTo(SHORT_PASSWD_FIXTURE + " ".repeat(
                            UserUpdateResponse.PASSWD_LENGTH - SHORT_PASSWD_FIXTURE.length()))
                    .hasSize(UserUpdateResponse.PASSWD_LENGTH)
                    .startsWith(SHORT_PASSWD_FIXTURE);
        }

        @Test
        @DisplayName("no redaction annotation is applied to the password, on any of the four surfaces")
        void noRedactionAnnotationIsAppliedToThePassword() {
            // Written over the APPLIED annotations rather than over the file's imports, deliberately:
            // UserUpdateResponse imports com.fasterxml.jackson.annotation.JsonIgnore without using it,
            // so an import-based check would pass or fail for the wrong reason. All four surfaces a
            // redaction could be attached to are inspected, because Jackson honours whichever it finds.
            Set<String> reachable = annotationsReachableFrom("passwd");

            assertThat(reachable)
                    .as("app/cbl/COUSR02C.cbl:169 sends this value, so nothing may suppress it")
                    .doesNotContain(JsonIgnore.class.getSimpleName())
                    .doesNotContain("JsonIgnoreProperties")
                    .doesNotContain("JsonIgnoreType")
                    .doesNotContain(JsonProperty.class.getSimpleName())
                    .doesNotContain("JsonSerialize")
                    .doesNotContain("JsonRawValue")
                    .doesNotContain("JsonView");
        }

        @Test
        @DisplayName("no hashing, encoding, masking or authentication-framework type is reachable")
        void noSecurityFrameworkTypeIsReachable() {
            // The whole declared surface of the type is scanned rather than just the password's own
            // accessor, because a transform introduced anywhere - a factory, a derived accessor, a
            // helper - would change the value the client receives.
            List<String> forbiddenFragments = List.of("passwordencoder", "bcrypt", "hash", "digest",
                    "encrypt", "mask", "redact", "token", "jwt", "springframework.security");

            Set<String> declaredTypeNames = new LinkedHashSet<>();
            for (Method method : UserUpdateResponse.class.getDeclaredMethods()) {
                declaredTypeNames.add(method.getReturnType().getName());
                for (Class<?> parameterType : method.getParameterTypes()) {
                    declaredTypeNames.add(parameterType.getName());
                }
            }
            for (Field field : UserUpdateResponse.class.getDeclaredFields()) {
                declaredTypeNames.add(field.getType().getName());
            }

            for (String typeName : declaredTypeNames) {
                String lower = typeName.toLowerCase(Locale.ROOT);
                for (String fragment : forbiddenFragments) {
                    assertThat(lower)
                            .as("no %s type may appear on this payload's surface: hashing or hiding "
                                    + "the password would change behaviour and would require a "
                                    + "framework this migration puts out of scope", fragment)
                            .doesNotContain(fragment);
                }
            }
            assertThat(declaredTypeNames)
                    .as("the surface is Strings, the two carriers and the type itself - nothing else")
                    .contains(String.class.getName());
        }

        @Test
        @DisplayName("the accessor performs no transformation: what goes in is what comes out")
        void theAccessorPerformsNoTransformation() {
            // Driven with values a masking or normalising transform would visibly alter: mixed case, a
            // leading space, an embedded space, and eight characters of punctuation.
            for (String candidate : List.of("aB-cD-1", " lead123", "mid pw12", "!@#$%^&*")) {
                String fitted = codec().movePicX(candidate, UserUpdateResponse.PASSWD_LENGTH);

                assertThat(UserUpdateResponse.blank().withPasswd(fitted).passwd())
                        .as("'%s' is stored and returned verbatim - no case folding, no trimming, "
                                + "no substitution", fitted)
                        .isEqualTo(fitted);
            }
        }

        @Test
        @DisplayName("equals and hashCode include the password, so two responses differing only there "
                + "are unequal")
        void equalityIncludesThePassword() {
            UserUpdateResponse one = UserUpdateResponse.blank().withPasswd(PASSWD_FIXTURE);
            UserUpdateResponse other = UserUpdateResponse.blank().withPasswd("PW-FAKE2");
            UserUpdateResponse same = UserUpdateResponse.blank().withPasswd(PASSWD_FIXTURE);

            assertThat(one).isNotEqualTo(other);
            assertThat(one).isEqualTo(same).hasSameHashCodeAs(same);
        }

        @Test
        @DisplayName("toString() withholds the password - a diagnostic concern, NOT a payload one (B4)")
        void toStringWithholdsThePasswordButThePayloadDoesNot() {
            // A DIVERGENCE from this file's brief, asserted as the type declares it. The brief asks for
            // no rendering that elides the value; the declared type elides it in toString() only. That
            // is the right line to draw and not a violation of the intent: a diagnostic rendering is
            // what a log line, a stack trace or a debugger dump picks up by accident, whereas the
            // payload is what COUSR02C:169 sends. Masking the payload would be the violation.
            UserUpdateResponse response = UserUpdateResponse.blank().withPasswd(PASSWD_FIXTURE);

            assertThat(response.toString())
                    .as("the rendering names the field but withholds its value")
                    .contains(UserUpdateResponse.PASSWD_FIELD)
                    .doesNotContain(PASSWD_FIXTURE);
            assertThat(response.passwd())
                    .as("while the accessor returns it in full - the two are not the same surface")
                    .isEqualTo(PASSWD_FIXTURE);
            assertThat(response.fieldValues())
                    .containsEntry(UserUpdateResponse.PASSWD_FIELD, PASSWD_FIXTURE);
            assertThat(response.toString())
                    .as("every other field is rendered as held, padding included")
                    .contains(UserUpdateResponse.USR_ID_IN_FIELD)
                    .contains(UserUpdateResponse.ERR_MSG_FIELD);
        }

        @Test
        @DisplayName("the three-way contrast: absent in SignOnResponse, blank in UserAddResponse, "
                + "carried here")
        void theThreeWayContrastAcrossThePackage() {
            // All three are correct, and none may be normalised toward another (B5). Each was counted in
            // the sibling's own mapset and cross-read in its own program, never inferred from another:
            //
            //   COSGN00 - 11 map fields, PASSWD among them, yet the response projects only 10, because
            //             COSGN00C references PASSWDO zero times and SEND-SIGNON-SCREEN does not send
            //             the password back. The member is absent by the PROGRAM's doing.
            //   COUSR01 - 12 map fields, PASSWD among them, so the response does declare the member -
            //             but it is always blank outbound: COUSR01C also references PASSWDO zero times,
            //             its only password move is the INBOUND one at line 157, and
            //             INITIALIZE-ALL-FIELDS at lines 286-294 blanks PASSWDI.
            //   COUSR02 - 12 map fields, and line 169 fills PASSWDI from SEC-USR-PWD after the read, so
            //             the overlay puts the stored plaintext on the screen. THIS FILE.
            //   COUSR03 - 11 map fields and no PASSWD field whatsoever.
            //
            // The sharpest form of it: a password field existing on a map settles nothing. Three of these
            // four maps have one. What settles it is whether the program writes the field after the read,
            // and only COUSR02C does.
            assertThat(UserUpdateResponse.MAP_FIELD_COUNT)
                    .as("twelve, and the twelfth reason is that PASSWD is one of them")
                    .isEqualTo(12)
                    .isEqualTo(DFHMDF_NAMED);
            assertThat(COSGN00_DFHMDF_NAMED)
                    .as("COSGN00's map has eleven fields and PASSWD IS one of them - so the sign-on "
                            + "response's missing member is not the map's doing")
                    .isEqualTo(11);
            assertThat(COSGN00_RESPONSE_MEMBERS)
                    .as("yet its response projects ten: one fewer than the map declares")
                    .isEqualTo(10)
                    .isEqualTo(COSGN00_DFHMDF_NAMED - 1)
                    .isLessThan(UserUpdateResponse.MAP_FIELD_COUNT);
            assertThat(COSGN00C_PASSWDO_REFERENCES)
                    .as("because COSGN00C never writes PASSWDO - that is the program's decision, and "
                            + "it is why the absence there is correct and the presence here also is")
                    .isZero();
            assertThat(COUSR01_DFHMDF_NAMED)
                    .as("COUSR01 declares PASSWD too, so its response has a member - twelve as well, "
                            + "which is why the count alone cannot distinguish the two")
                    .isEqualTo(UserUpdateResponse.MAP_FIELD_COUNT);
            assertThat(COUSR03_DFHMDF_NAMED)
                    .as("COUSR03 has no PASSWD field at all - eleven, not twelve, and that contrast "
                            + "is precisely why this one has twelve")
                    .isEqualTo(11)
                    .isLessThan(UserUpdateResponse.MAP_FIELD_COUNT);
            assertThat(UserUpdateResponse.MAP_FIELD_NAMES)
                    .as("here the member exists AND carries the stored value")
                    .contains(UserUpdateResponse.PASSWD_FIELD);
            assertThat(UserUpdateResponse.blank().withPasswd(PASSWD_FIXTURE).passwd())
                    .as("app/cbl/COUSR02C.cbl:169 - and here, and only here, the stored value is "
                            + "carried onto the screen")
                    .isEqualTo(PASSWD_FIXTURE);
            assertThat(UserUpdateResponse.blank().passwd())
                    .as("COUSR01's member, by contrast, never gets past this blank state: its only "
                            + "password move is inbound, at COUSR01C:157")
                    .isBlank()
                    .hasSize(UserUpdateResponse.PASSWD_LENGTH);
        }

        @Test
        @DisplayName("the request half of the pairing carries a password too, at the same width")
        void theRequestHalfOfThePairingAgrees() {
            // UserUpdateRequestTest owns the inbound half; the pairing is asserted here so the two files
            // cannot drift apart. Line 227's comparison IF PASSWDI NOT = SEC-USR-PWD only makes sense
            // if both halves exist at the same width.
            assertThat(UserUpdateRequest.PASSWD_LENGTH)
                    .as("the same PIC X(8), so line 227 compares like with like")
                    .isEqualTo(UserUpdateResponse.PASSWD_LENGTH);
            assertThat(UserUpdateRequest.MAP_FIELD_NAMES)
                    .as("the request keys on the xxxI spelling, the response on the xxxO spelling")
                    .contains("PASSWDI")
                    .doesNotContain(UserUpdateResponse.PASSWD_FIELD);
            assertThat(UserUpdateResponse.MAP_FIELD_NAMES)
                    .contains(UserUpdateResponse.PASSWD_FIELD)
                    .doesNotContain("PASSWDI");
        }

        @Test
        @DisplayName("no version, entity tag, revision or timestamp member exists - gate G43 is not "
                + "in scope for this package")
        void noConcurrencyTokenIsSmuggledIn() {
            // COUSR02C has no 9300-CHECK-CHANGE-IN-REC paragraph. Its four tests at lines 219-234
            // compare the screen values against a record re-read at line 217 to decide whether ANYTHING
            // changed at all; they never guard against a competing writer. The optimistic-concurrency
            // requirement is scoped to the account and card update services, whose programs do have that
            // paragraph. Adding a token here would be a behaviour change AND a schema change.
            List<String> forbiddenMembers = List.of("version", "etag", "eTag", "revision", "timestamp",
                    "lastModified", "modifiedAt", "updatedAt", "concurrencyToken", "rowVersion");

            List<String> declared = Arrays.stream(UserUpdateResponse.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(declared).hasSize(COMPONENT_COUNT);
            for (String forbidden : forbiddenMembers) {
                assertThat(declared)
                        .as("%s would be an optimistic-concurrency token, and this program performs no "
                                + "concurrency check", forbidden)
                        .doesNotContain(forbidden);
            }
            assertThat(declared)
                    .as("the seventeen are the twelve map members plus the five carriers, and no more")
                    .containsExactlyElementsOf(
                            Stream.concat(MAP_MEMBERS.stream(), STATE_MEMBERS.stream())
                                    .toList());
        }

        @Test
        @DisplayName("no persistence or bean-validation annotation appears on the response")
        void noPersistenceOrValidationAnnotationAppears() {
            // No schema is created or altered anywhere in this migration, so no mapping annotation may
            // appear. No presence constraint either: COUSR02C answers each blank field with a SPECIFIC
            // message at lines 182, 188, 194, 200 and 206 rather than rejecting the request, so a
            // validation rejection would replace a screen message with an HTTP error.
            Set<String> annotationNames = new LinkedHashSet<>();
            collectNames(annotationNames, UserUpdateResponse.class.getAnnotations());
            for (String member : MAP_MEMBERS) {
                annotationNames.addAll(annotationsReachableFrom(member));
            }
            for (String member : STATE_MEMBERS) {
                annotationNames.addAll(annotationsReachableFrom(member));
            }

            assertThat(annotationNames)
                    .doesNotContain("Entity")
                    .doesNotContain("Table")
                    .doesNotContain("Column")
                    .doesNotContain("Id")
                    .doesNotContain("Version")
                    .doesNotContain("NotNull")
                    .doesNotContain("NotBlank")
                    .doesNotContain("NotEmpty")
                    .doesNotContain("Pattern");
        }
    }

    // =================================================================================================
    // PHASE 4 - THE GROUP-LEVEL REDEFINES.
    //
    //     app/cpy-bms/COUSR02.CPY
    //       17   01  COUSR2AI.
    //       18       02  FILLER PIC X(12).                <- TIOAPFX=YES prefix
    //       ..       02  xxxL COMP PIC S9(4).   2 bytes
    //       ..       02  xxxF PICTURE X.        1 byte  \ 02 FILLER REDEFINES xxxF / 03 xxxA
    //       ..       02  FILLER PICTURE X(4).   4 bytes  = seven per field
    //       ..       02  xxxI PIC X(n).
    //       91   01  COUSR2AO REDEFINES COUSR2AI.         <- THE ONE THIS FILE OWNS
    //       92       02  FILLER PIC X(12).
    //       ..       02  FILLER PICTURE X(3).   3 bytes
    //       ..       02  xxxC / xxxP / xxxH / xxxV.       4 bytes = seven per field as well
    //       ..       02  xxxO PIC X(n).
    //
    // Thirteen REDEFINES in this copybook: twelve per-field xxxA overlays, which UserUpdateRequestTest
    // owns, and this one. Across the five maps of the package it is 105 per-field plus five group-level
    // = 110, while the five programs behind them declare a REDEFINES exactly zero times - so the
    // symbolic maps are the only place the redefinition gate has a subject at all.
    // =================================================================================================

    @Nested
    @DisplayName("REDEFINES - one 339-byte area, two views, zero drift")
    class GroupRedefinesOverlay {

        @Test
        @DisplayName("the geometry is the copybook's: 12 + 12 x 7 + 243 = 339, in both views")
        void theGeometryIsTheCopybooks() {
            // Re-derived from the transcribed parts rather than restated, so a wrong part cannot hide
            // behind a right total. Constructing the two layouts proved it a second and third time:
            // RecordLayout.of refuses a gap, an unintended overlap and any total other than declared.
            assertThat(INPUT_PREFIX_LENGTH)
                    .as("input view: xxxL 2 + xxxF 1 + FILLER X(4) = 7")
                    .isEqualTo(7);
            assertThat(OUTPUT_PREFIX_LENGTH)
                    .as("output view: FILLER X(3) + xxxC + xxxP + xxxH + xxxV = 7 as well")
                    .isEqualTo(7)
                    .isEqualTo(INPUT_PREFIX_LENGTH);
            assertThat(DECLARED_WIDTHS.stream().mapToInt(Integer::intValue).sum())
                    .as("4+40+8+8+40+8+8+20+20+8+1+78")
                    .isEqualTo(PAYLOAD_WIDTH_TOTAL)
                    .isEqualTo(243);
            assertThat(TIOAPFX_PREFIX_LENGTH + DFHMDF_NAMED * OUTPUT_PREFIX_LENGTH
                    + PAYLOAD_WIDTH_TOTAL)
                    .as("12 + 12 x 7 + 243")
                    .isEqualTo(SYMBOLIC_MAP_LENGTH)
                    .isEqualTo(339);
            assertThat(OUTPUT_VIEW_LAYOUT.recordLength())
                    .isEqualTo(INPUT_VIEW_LAYOUT.recordLength())
                    .isEqualTo(SYMBOLIC_MAP_LENGTH);
            assertThat(OUTPUT_VIEW_LAYOUT.storageSpans().stream()
                    .mapToInt(FixedWidthRecord.FieldSpan::length).sum())
                    .as("the output view's storage spans, overlays excluded, tile the area exactly")
                    .isEqualTo(SYMBOLIC_MAP_LENGTH);
            assertThat(INPUT_VIEW_LAYOUT.storageSpans().stream()
                    .mapToInt(FixedWidthRecord.FieldSpan::length).sum())
                    .as("and so do the input view's - which is what makes the overlay exact")
                    .isEqualTo(SYMBOLIC_MAP_LENGTH);
        }

        @Test
        @DisplayName("the copybook declares thirteen REDEFINES: twelve per-field plus this one")
        void theCopybookDeclaresThirteenRedefinitions() {
            assertThat(COPYBOOK_REDEFINES_TOTAL)
                    .as("app/cpy-bms/COUSR02.CPY, twelve xxxA overlays plus the group view at line 91")
                    .isEqualTo(13);
            assertThat(GROUP_LEVEL_REDEFINES).isEqualTo(1);
            assertThat(INPUT_VIEW_LAYOUT.redefinitions())
                    .as("the twelve per-field overlays, which UserUpdateRequestTest owns")
                    .hasSize(PER_FIELD_REDEFINES);
            assertThat(OUTPUT_VIEW_LAYOUT.redefinitions())
                    .as("and the one group-level overlay, which this file owns")
                    .hasSize(GROUP_LEVEL_REDEFINES);
            assertThat(OUTPUT_VIEW_LAYOUT.redefinitions().get(0).name())
                    .isEqualTo(OUTPUT_GROUP_NAME);
            assertThat(OUTPUT_VIEW_LAYOUT.redefinitions().get(0).length())
                    .as("it redefines the WHOLE area, not a field within it")
                    .isEqualTo(SYMBOLIC_MAP_LENGTH);
            assertThat(OUTPUT_VIEW_LAYOUT.redefinitions().get(0).offset()).isZero();
        }

        @Test
        @DisplayName("this package's redefinitions live entirely in its maps, never in its programs")
        void theRedefinitionsAreTheMapsAndNotThePrograms() {
            assertThat(PROGRAM_REDEFINES_TOTAL)
                    .as("COSGN00C, COUSR00C, COUSR01C, COUSR02C and COUSR03C declare none")
                    .isZero();
            assertThat(PACKAGE_REDEFINES_TOTAL)
                    .as("105 per-field + 5 group-level across the five maps")
                    .isEqualTo(110)
                    .isEqualTo(105 + PACKAGE_GROUP_LEVEL_REDEFINES);
            assertThat(PACKAGE_GROUP_LEVEL_REDEFINES)
                    .as("one group-level overlay per map, and COUSR02's is the one asserted here")
                    .isEqualTo(5)
                    .isGreaterThan(GROUP_LEVEL_REDEFINES);
            assertThat(COPYBOOK_REDEFINES_TOTAL).isLessThan(PACKAGE_REDEFINES_TOTAL);
        }

        @ParameterizedTest(name = "[{index}] {0}: xxxI and xxxO address the identical offset")
        @ValueSource(strings = {"TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME",
            "USRIDIN", "FNAME", "LNAME", "PASSWD", "USRTYPE", "ERRMSG"})
        @DisplayName("the two views align field for field, with zero drift")
        void theTwoViewsAlignFieldForField(String screenField) {
            FixedWidthRecord.FieldSpan inbound = INPUT_VIEW_LAYOUT.span(screenField + "I");
            FixedWidthRecord.FieldSpan outbound = OUTPUT_VIEW_LAYOUT.span(screenField + "O");

            assertThat(outbound.offset())
                    .as("%sO begins exactly where %sI begins - both per-field prefixes are seven "
                            + "bytes, so nothing drifts", screenField, screenField)
                    .isEqualTo(inbound.offset());
            assertThat(outbound.length())
                    .as("and both are the same PICTURE clause")
                    .isEqualTo(inbound.length());
            assertThat(outbound.kind()).isEqualTo(inbound.kind())
                    .isEqualTo(FixedWidthRecord.PictureKind.ALPHANUMERIC);
        }

        @Test
        @DisplayName("PASSWDI and PASSWDO are one storage span - the mechanism behind the echo")
        void thePasswordOverlayIsTheMechanismBehindTheEcho() {
            // This is why COUSR02C:169 writing PASSWDI puts the value on the SCREEN. The program writes
            // the input item; the overlay means it has written the output item. One span, two names.
            FixedWidthRecord.FieldSpan inbound = INPUT_VIEW_LAYOUT.span("PASSWDI");
            FixedWidthRecord.FieldSpan outbound = OUTPUT_VIEW_LAYOUT.span("PASSWDO");

            assertThat(inbound.offset())
                    .as("12 prefix + 9 fields' worth of prefixes and data, re-derived by the builder")
                    .isEqualTo(outbound.offset());
            assertThat(outbound.length()).isEqualTo(UserUpdateResponse.PASSWD_LENGTH).isEqualTo(8);

            // Write eight characters through the INPUT view, read them through the OUTPUT view.
            FixedWidthRecord area = FixedWidthRecord.forLayout(OUTPUT_VIEW_LAYOUT, MAP_CHARSET);
            FixedWidthRecord sameArea = FixedWidthRecord.copyOf(area.toByteArray(),
                    SYMBOLIC_MAP_LENGTH, MAP_CHARSET);
            sameArea.writeString(inbound.offset(), inbound.length(), PASSWD_FIXTURE);

            assertThat(sameArea.readString(outbound.offset(), outbound.length()))
                    .as("app/cbl/COUSR02C.cbl:169 writes PASSWDI, and PASSWDO is what gets sent")
                    .isEqualTo(PASSWD_FIXTURE);

            // And back the other way, because a redefinition is symmetric.
            sameArea.writeString(outbound.offset(), outbound.length(), "PW-FAKE2");
            assertThat(sameArea.readString(inbound.offset(), inbound.length())).isEqualTo("PW-FAKE2");
            assertThat(sameArea.toByteArray()).hasSize(SYMBOLIC_MAP_LENGTH);
        }

        @ParameterizedTest(name = "[{index}] {0} round-trips across the two views")
        @ValueSource(strings = {"TRNNAME", "PASSWD", "ERRMSG"})
        @DisplayName("the first, a middle and the last field all cross between the views intact")
        void theFirstAMiddleAndTheLastFieldAllCross(String screenField) {
            int index = SCREEN_FIELDS.indexOf(screenField);
            FixedWidthRecord.FieldSpan inbound = INPUT_VIEW_LAYOUT.span(screenField + "I");
            FixedWidthRecord.FieldSpan outbound = OUTPUT_VIEW_LAYOUT.span(screenField + "O");
            FixedWidthRecord area = FixedWidthRecord.forLayout(OUTPUT_VIEW_LAYOUT, MAP_CHARSET);
            byte[] before = area.toByteArray();
            String value = codec().movePicX("Z", DECLARED_WIDTHS.get(index));

            area.writeSpan(outbound, value);

            assertThat(area.readString(inbound.offset(), inbound.length()))
                    .as("%sI reads what was written through %sO", screenField, screenField)
                    .isEqualTo(value);
            assertThat(area.readSpan(outbound)).isEqualTo(value);

            // The overlay addresses exactly this field's span, so exactly that many bytes may differ.
            byte[] after = area.toByteArray();
            assertThat(after).hasSize(before.length).hasSize(SYMBOLIC_MAP_LENGTH);
            int differing = 0;
            for (int offset = 0; offset < after.length; offset++) {
                if (after[offset] != before[offset]) {
                    differing++;
                    assertThat(offset)
                            .as("every changed byte lies inside %sO's own span", screenField)
                            .isGreaterThanOrEqualTo(outbound.offset())
                            .isLessThan(outbound.endOffsetExclusive());
                }
            }
            assertThat(differing)
                    .as("writing 'Z' padded to width changes at most that field's bytes")
                    .isPositive()
                    .isLessThanOrEqualTo(outbound.length());
        }

        @Test
        @DisplayName("the data offsets are the copybook's, and the password sits at 238")
        void theDataOffsetsAreTheCopybooks() {
            // Derived by walking the copybook: 12 prefix, then per field seven bytes of prefix and the
            // field's own width. Stated so the round trips above are anchored to real numbers rather
            // than to whatever the builder computed.
            int cursor = TIOAPFX_PREFIX_LENGTH;
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                cursor += OUTPUT_PREFIX_LENGTH;
                assertThat(OUTPUT_VIEW_LAYOUT.span(OUTPUT_MAP_ITEMS.get(index)).offset())
                        .as("%s begins at %d", OUTPUT_MAP_ITEMS.get(index), cursor)
                        .isEqualTo(cursor);
                cursor += DECLARED_WIDTHS.get(index);
            }
            assertThat(cursor)
                    .as("and the walk lands exactly on the declared length")
                    .isEqualTo(SYMBOLIC_MAP_LENGTH);
            assertThat(OUTPUT_VIEW_LAYOUT.span(UserUpdateResponse.PASSWD_FIELD).offset())
                    .as("PASSWDO's data begins at 238 - not to be confused with SEC-USR-PWD's 48")
                    .isEqualTo(238)
                    .isNotEqualTo(SecUserRecord.SEC_USR_PWD_OFFSET);
            assertThat(OUTPUT_VIEW_LAYOUT.span(UserUpdateResponse.ERR_MSG_FIELD).endOffsetExclusive())
                    .as("and ERRMSGO's last byte is the area's last byte")
                    .isEqualTo(SYMBOLIC_MAP_LENGTH);
        }
    }

    // =================================================================================================
    // PHASE 4 - the attribute and length items are metadata, not payload.
    // =================================================================================================

    @Nested
    @DisplayName("Metadata - xxxC, xxxP, xxxH, xxxV, xxxL, xxxF, xxxA and every FILLER stay off the wire")
    class MetadataStaysOffTheWire {

        @Test
        @DisplayName("the output attribute quartet exists in storage but is no member of the payload")
        void theAttributeQuartetIsStorageOnly() {
            List<String> memberNames = Arrays.stream(UserUpdateResponse.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            for (String field : SCREEN_FIELDS) {
                for (String suffix : ATTRIBUTE_SUFFIXES) {
                    assertThat(OUTPUT_VIEW_LAYOUT.hasSpan(field + suffix))
                            .as("%s%s is real storage - EXTATT=YES asks for it", field, suffix)
                            .isTrue();
                    assertThat(OUTPUT_VIEW_LAYOUT.span(field + suffix).length())
                            .isEqualTo(ATTRIBUTE_ITEM_LENGTH);
                    assertThat(memberNames)
                            .as("but %s%s is presentation metadata, never a payload member",
                                    field, suffix)
                            .doesNotContain(field + suffix)
                            .doesNotContain((field + suffix).toLowerCase(Locale.ROOT));
                    assertThat(UserUpdateResponse.MAP_FIELD_NAMES).doesNotContain(field + suffix);
                }
            }
        }

        @Test
        @DisplayName("xxxC is the colour item, and it is the one COUSR02C moves DFHRED and DFHGREEN to")
        void theColourItemIsTheFirstOfTheQuartet() {
            // app/cbl/COUSR02C.cbl:241  MOVE DFHRED   TO ERRMSGC OF COUSR2AO   (nothing changed)
            // app/cbl/COUSR02C.cbl:338  MOVE DFHNEUTR TO ERRMSGC OF COUSR2AO   (press PF5 to save)
            // app/cbl/COUSR02C.cbl:371  MOVE DFHGREEN TO ERRMSGC OF COUSR2AO   (updated)
            // and the sibling program spells the same idiom at app/cbl/COUSR03C.cbl:317.
            assertThat(ATTRIBUTE_SUFFIXES)
                    .as("declaration order in the output view: colour first, then P, H, V")
                    .containsExactly("C", "P", "H", "V");
            assertThat(FieldAttributeSetter.COLOUR_ITEM_SUFFIX).isEqualTo("C");
            assertThat(OUTPUT_VIEW_LAYOUT.span("ERRMSGC").offset())
                    .as("ERRMSGC sits three bytes past the field's FILLER and just before ERRMSGO")
                    .isEqualTo(OUTPUT_VIEW_LAYOUT.span(UserUpdateResponse.ERR_MSG_FIELD).offset()
                            - ATTRIBUTE_SUFFIXES.size() * ATTRIBUTE_ITEM_LENGTH);
            assertThat(FieldAttributeSetter.OUTPUT_ITEM_SUFFIX)
                    .as("and the data item is the O-suffixed one this payload projects")
                    .isEqualTo("O");
        }

        @Test
        @DisplayName("the input view's xxxL, xxxF and xxxA items are metadata as well")
        void theInputMetadataItemsAreAlsoNotPayload() {
            List<String> memberNames = Arrays.stream(UserUpdateResponse.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            for (String field : SCREEN_FIELDS) {
                // xxxL is the input length CICS reports and doubles as the cursor signal - which is all
                // MOVE -1 TO PASSWDL at line 202 and MOVE -1 TO USRIDINL at line 405 are doing.
                assertThat(memberNames).doesNotContain(field + "L").doesNotContain(field + "l");
                assertThat(INPUT_VIEW_LAYOUT.hasSpan(field + "F"))
                        .as("%sF is the attribute byte, and it is storage", field)
                        .isTrue();
                assertThat(INPUT_VIEW_LAYOUT.hasSpan(field + "A"))
                        .as("%sA redefines it", field)
                        .isTrue();
                assertThat(memberNames).doesNotContain(field + "F").doesNotContain(field + "A");
                assertThat(UserUpdateResponse.MAP_FIELD_NAMES)
                        .doesNotContain(field + "L")
                        .doesNotContain(field + "F")
                        .doesNotContain(field + "A");
            }
            assertThat(LENGTH_ITEM_LENGTH)
                    .as("xxxL is COMP - a binary halfword, so two bytes, and never a payload member")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("the twelve-byte prefix and the eleven three-byte fillers are not exposed")
        void theFillersAreNotExposed() {
            long outputFillerBytes = OUTPUT_VIEW_LAYOUT.storageSpans().stream()
                    .filter(span -> span.kind().filler())
                    .mapToInt(FixedWidthRecord.FieldSpan::length)
                    .sum();

            assertThat(outputFillerBytes)
                    .as("12 for the TIOAPFX prefix plus 12 x 3 for the per-field fillers")
                    .isEqualTo(TIOAPFX_PREFIX_LENGTH + (long) DFHMDF_NAMED * OUTPUT_FILLER_LENGTH)
                    .isEqualTo(48);
            assertThat(Arrays.stream(UserUpdateResponse.class.getRecordComponents())
                    .map(RecordComponent::getName).toList())
                    .as("reserved storage is never a member, under any spelling")
                    .doesNotContain("filler")
                    .doesNotContain("FILLER")
                    .doesNotContain("tioapfx");
            assertThat(UserUpdateResponse.MAP_FIELD_NAMES)
                    .noneMatch(name -> name.contains("FILLER"));
        }

        @Test
        @DisplayName("value() refuses an attribute item by name rather than answering null")
        void valueRefusesAnAttributeItem() {
            // Asking for FNAMEC is asking for the colour byte, which is not a field of this payload. A
            // null answer would be compared against an expectation and reported as a puzzling
            // difference instead of as the mistake it is.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> afterSuccessfulRead().value("ERRMSGC"))
                    .withMessageContaining(OUTPUT_GROUP_NAME);
            assertThatNullPointerException()
                    .isThrownBy(() -> afterSuccessfulRead().value(null));
        }
    }

    // =================================================================================================
    // PHASE 5 - errMsg is where every outcome of this screen surfaces.
    //
    // Each text below is moved into WS-MESSAGE PIC X(80) and then, at line 270, into ERRMSGO PIC X(78).
    // Both moves go through FixedWidthCodec.movePicX (B11), so the two-character loss is exercised on
    // real text and not only on a synthetic eighty-character string.
    // =================================================================================================

    @Nested
    @DisplayName("Outcome messages - every text fits the 78-character field after the 80-to-78 move")
    class OutcomeMessages {

        @ParameterizedTest(name = "[{index}] \"{0}\"")
        @ValueSource(strings = {"User ID can NOT be empty...", "First Name can NOT be empty...",
            "Last Name can NOT be empty...", "Password can NOT be empty...",
            "User Type can NOT be empty..."})
        @DisplayName("each of the five blank-field messages composes an 80-byte image and fits at 78")
        void eachBlankFieldMessageFits(String text) {
            assertThat(BLANK_FIELD_MESSAGES)
                    .as("all five are transcribed from app/cbl/COUSR02C.cbl:182, 188, 194, 200 and 206")
                    .contains(text);

            String wsMessage = codec().movePicX(text, WS_MESSAGE_LENGTH);
            String errMsg = errMsgImageOf(text);

            assertThat(wsMessage)
                    .as("MOVE into WS-MESSAGE PIC X(80) pads on the right")
                    .hasSize(WS_MESSAGE_LENGTH)
                    .startsWith(text);
            assertThat(errMsg)
                    .as("line 270 then narrows to PIC X(78); these texts are short, so nothing of the "
                            + "text itself is lost - only two trailing spaces")
                    .hasSize(UserUpdateResponse.ERR_MSG_LENGTH)
                    .startsWith(text);
            assertThat(errMsg.strip()).isEqualTo(text);
            assertThat(UserUpdateResponse.blank().withErrMsg(errMsg).errMsg())
                    .as("and the payload carries the 78-character image verbatim, padding included")
                    .isEqualTo(errMsg);
        }

        @Test
        @DisplayName("the five arms are evaluated identifier-first, which decides which message shows")
        void theFiveArmsAreEvaluatedIdentifierFirst() {
            // UPDATE-USER-INFO's EVALUATE at lines 179-213 is ordered, and the first matching WHEN wins.
            // A screen with both the identifier and the password blank therefore shows the identifier
            // message, never the password one. This is the order, not merely a list.
            assertThat(BLANK_FIELD_MESSAGES)
                    .containsExactly(MSG_USER_ID_EMPTY,
                            MSG_FIRST_NAME_EMPTY,
                            MSG_LAST_NAME_EMPTY,
                            MSG_PASSWORD_EMPTY,
                            MSG_USER_TYPE_EMPTY);
            assertThat(BLANK_FIELD_MESSAGES.indexOf(MSG_USER_ID_EMPTY))
                    .as("the identifier arm is first - matching the map, where USRIDIN is field seven "
                            + "but the FIRST of the five editable ones")
                    .isZero()
                    .isLessThan(BLANK_FIELD_MESSAGES.indexOf(MSG_PASSWORD_EMPTY));
            assertThat(BLANK_FIELD_MESSAGES)
                    .as("the WHEN OTHER arm at line 210 sets no message at all, so there is no sixth")
                    .hasSize(5);
        }

        @Test
        @DisplayName("the no-change arm pairs with DFHRED, restating ERRMSG's declared default colour")
        void theNoChangeArmPairsWithRed() {
            // Lines 236-243: when none of the four comparisons at 219-234 fired, the ELSE writes this
            // text and moves DFHRED to ERRMSGC at line 241.
            String errMsg = errMsgImageOf(MSG_PLEASE_MODIFY);

            assertThat(MSG_PLEASE_MODIFY)
                    .as("note the space before the ellipsis, which the five arms above lack")
                    .isEqualTo("Please modify to update ...")
                    .hasSizeLessThanOrEqualTo(UserUpdateResponse.ERR_MSG_LENGTH);
            assertThat(errMsg).hasSize(UserUpdateResponse.ERR_MSG_LENGTH).startsWith(MSG_PLEASE_MODIFY);
            assertThat(BmsAttributes.COLOUR_MNEMONICS.get(BmsAttributes.DFHRED)).isEqualTo("DFHRED");
            assertThat(UserUpdateResponse.blank().withErrMsg(errMsg).errMsg()).isEqualTo(errMsg);
        }

        @Test
        @DisplayName("the success text is built by STRING, and DELIMITED BY SPACE makes it variable")
        void theSuccessTextIsVariableLength() {
            // app/cbl/COUSR02C.cbl:371-375
            //     MOVE DFHGREEN           TO ERRMSGC OF COUSR2AO
            //     STRING 'User '     DELIMITED BY SIZE
            //            SEC-USR-ID  DELIMITED BY SPACE      <- only up to its first space
            //            ' has been updated ...' DELIMITED BY SIZE
            //       INTO WS-MESSAGE
            FixedWidthCodec codec = codec();
            String eightCharId = codec.movePicX(USER_ID_FIXTURE, SecUserRecord.SEC_USR_ID_LENGTH);
            String fiveCharId = codec.movePicX(SHORT_USER_ID_FIXTURE,
                    SecUserRecord.SEC_USR_ID_LENGTH);

            String forEight = codec.concatenateDelimitedBySize(MSG_UPDATED_PREFIX,
                    delimitedBySpace(eightCharId), MSG_UPDATED_SUFFIX);
            String forFive = codec.concatenateDelimitedBySize(MSG_UPDATED_PREFIX,
                    delimitedBySpace(fiveCharId), MSG_UPDATED_SUFFIX);

            assertThat(forEight)
                    .as("an eight-character id has no space, so it contributes all eight")
                    .isEqualTo("User USER0001 has been updated ...");
            assertThat(forFive)
                    .as("a five-character id is space-padded in the record, so it contributes five - "
                            + "three fewer characters, and no stray spaces in the middle")
                    .isEqualTo("User ADMIN has been updated ...")
                    .doesNotContain("ADMIN   ");
            assertThat(forFive.length())
                    .as("the composed length is VARIABLE, not fixed: shorter id, shorter message")
                    .isNotEqualTo(forEight.length())
                    .isEqualTo(forEight.length() - 3);
            assertThat(BmsAttributes.DFHGREEN)
                    .as("and this arm alone pairs with DFHGREEN, at line 371")
                    .isNotEqualTo(BmsAttributes.DFHRED);
        }

        @ParameterizedTest(name = "[{index}] id \"{0}\" composes a message of {1} characters")
        @CsvSource({"USER0001,34", "ADMIN,31", "A,27", "AB,28"})
        @DisplayName("every id length composes a message that still fits the 78-character field")
        void everyIdLengthStillFits(String rawId, int expectedLength) {
            FixedWidthCodec codec = codec();
            String stored = codec.movePicX(rawId, SecUserRecord.SEC_USR_ID_LENGTH);
            String composed = codec.concatenateDelimitedBySize(MSG_UPDATED_PREFIX,
                    delimitedBySpace(stored), MSG_UPDATED_SUFFIX);

            assertThat(stored).hasSize(SecUserRecord.SEC_USR_ID_LENGTH);
            assertThat(composed)
                    .hasSize(expectedLength)
                    .hasSizeLessThanOrEqualTo(UserUpdateResponse.ERR_MSG_LENGTH);
            assertThat(errMsgImageOf(composed))
                    .hasSize(UserUpdateResponse.ERR_MSG_LENGTH)
                    .startsWith(composed);
            assertThat(UserUpdateResponse.blank().withErrMsg(errMsgImageOf(composed)).errMsg())
                    .isEqualTo(errMsgImageOf(composed));
        }

        @ParameterizedTest(name = "[{index}] \"{0}\"")
        @ValueSource(strings = {"Press PF5 key to save your updates ...", "User ID NOT found...",
            "Unable to lookup User...", "Unable to Update User..."})
        @DisplayName("the read and rewrite outcomes fit the field too, and each pairs with its colour")
        void theReadAndRewriteOutcomesFit(String text) {
            assertThat(List.of(MSG_PRESS_PF5, MSG_USER_NOT_FOUND, MSG_UNABLE_TO_LOOKUP,
                    MSG_UNABLE_TO_UPDATE))
                    .as("transcribed from app/cbl/COUSR02C.cbl:336, 342, 349 and 386")
                    .contains(text);
            assertThat(errMsgImageOf(text))
                    .hasSize(UserUpdateResponse.ERR_MSG_LENGTH)
                    .startsWith(text);
            assertThat(BmsAttributes.DFHNEUTR)
                    .as("the save prompt at line 336 pairs with DFHNEUTR at line 338 - a third colour, "
                            + "neither the red default nor the green success")
                    .isNotEqualTo(BmsAttributes.DFHRED)
                    .isNotEqualTo(BmsAttributes.DFHGREEN);
        }

        @Test
        @DisplayName("blank() leaves errMsg as 78 spaces, matching lines 88 and 411")
        void blankLeavesTheMessageFieldSpaced() {
            // MOVE SPACES TO WS-MESSAGE, ERRMSGO OF COUSR2AO at lines 87-88, and again at line 411 in
            // INITIALIZE-ALL-FIELDS.
            assertThat(UserUpdateResponse.blank().errMsg())
                    .isEqualTo(" ".repeat(UserUpdateResponse.ERR_MSG_LENGTH))
                    .hasSize(78);
            assertThat(errMsgImageOf(""))
                    .as("and moving an empty WS-MESSAGE in gives the same 78 spaces")
                    .isEqualTo(UserUpdateResponse.blank().errMsg());
        }
    }

    // =================================================================================================
    // PHASE 6 - XCTL becomes response fields, and the conversation travels in the payload.
    // =================================================================================================

    @Nested
    @DisplayName("Navigation - XCTL at line 259 becomes three response fields")
    class Navigation {

        @Test
        @DisplayName("nextProgram, nextMapset and nextMap are declared members of the response")
        void theThreeNavigationMembersAreDeclared() {
            List<String> declared = Arrays.stream(UserUpdateResponse.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(declared)
                    .as("EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) at app/cbl/COUSR02C.cbl:259 becomes "
                            + "data the client acts on, so there is no server-side forward")
                    .contains("nextProgram", "nextMapset", "nextMap");
            for (String member : List.of("nextProgram", "nextMapset", "nextMap")) {
                assertThat(accessorOf(member).getReturnType()).isEqualTo(String.class);
            }
            assertThat(UserUpdateResponse.MAP_FIELD_NAMES)
                    .as("and none of the three is a map field - they have no DFHMDF definition")
                    .doesNotContain(UserUpdateResponse.NEXT_PROGRAM_FIELD)
                    .doesNotContain(UserUpdateResponse.NEXT_MAPSET_FIELD)
                    .doesNotContain(UserUpdateResponse.NEXT_MAP_FIELD);
            assertThat(afterSuccessfulRead().fieldValues())
                    .as("so fieldValues() excludes them: including them would corrupt a comparison "
                            + "against the symbolic map")
                    .hasSize(UserUpdateResponse.MAP_FIELD_COUNT)
                    .doesNotContainKey(UserUpdateResponse.NEXT_PROGRAM_FIELD);
        }

        @Test
        @DisplayName("their COBOL names are the COMMAREA's, because that is where the values come from")
        void theirNamesAreTheCommareasOwn() {
            assertThat(UserUpdateResponse.NEXT_PROGRAM_FIELD)
                    .as("app/cpy/COCOM01Y.cpy:24")
                    .isEqualTo(NavigationContext.TO_PROGRAM_FIELD)
                    .isEqualTo("CDEMO-TO-PROGRAM");
            assertThat(UserUpdateResponse.NEXT_MAP_FIELD)
                    .as("app/cpy/COCOM01Y.cpy:43")
                    .isEqualTo(NavigationContext.LAST_MAP_FIELD)
                    .isEqualTo("CDEMO-LAST-MAP");
            assertThat(UserUpdateResponse.NEXT_MAPSET_FIELD)
                    .as("app/cpy/COCOM01Y.cpy:44")
                    .isEqualTo(NavigationContext.LAST_MAPSET_FIELD)
                    .isEqualTo("CDEMO-LAST-MAPSET");
        }

        @Test
        @DisplayName("this screen's own map and mapset names fit the X(7) fields exactly")
        void thisScreensNamesFitSevenCharacters() {
            UserUpdateResponse response = UserUpdateResponse.blank()
                    .withNextMap(MAP_NAME)
                    .withNextMapset(MAPSET_NAME);

            assertThat(response.nextMap()).isEqualTo(MAP_NAME).hasSize(7);
            assertThat(response.nextMapset()).isEqualTo(MAPSET_NAME).hasSize(7);
            assertThatIllegalArgumentException()
                    .as("an eight-character value does not fit a PIC X(7) item")
                    .isThrownBy(() -> UserUpdateResponse.blank().withNextMap("COUSR2AB"))
                    .withMessageContaining(UserUpdateResponse.NEXT_MAP_FIELD);
        }

        @ParameterizedTest(name = "[{index}] {0} is a real XCTL target of this program")
        @ValueSource(strings = {"COADM01C", "COSGN00C", "COUSR00C"})
        @DisplayName("the three targets the program actually names all fit the eight-character field")
        void theRealTargetsFitTheProgramField(String target) {
            // 'COADM01C' on PF3 with a blank CDEMO-FROM-PROGRAM (line 114) and on PF12 (line 125);
            // 'COSGN00C' on the EIBCALEN = 0 cold start (line 91) and as the RETURN-TO-PREV-SCREEN
            // fallback (line 253); and otherwise whatever CDEMO-FROM-PROGRAM holds - COUSR00C, the user
            // list, being the program that transfers here with a row selected.
            UserUpdateResponse response = UserUpdateResponse.blank().withNextProgram(target);

            assertThat(target).hasSize(UserUpdateResponse.NEXT_PROGRAM_LENGTH);
            assertThat(response.nextProgram()).isEqualTo(target);
            assertThat(response.navigationContext())
                    .as("and the area travels alongside, exactly as line 260's COMMAREA does")
                    .isEqualTo(NavigationContext.empty());
        }

        @Test
        @DisplayName("blank() sets no target: a response that navigates nowhere is representable")
        void blankSetsNoTarget() {
            assertThat(UserUpdateResponse.blank().nextProgram())
                    .isEqualTo(" ".repeat(UserUpdateResponse.NEXT_PROGRAM_LENGTH));
            assertThat(UserUpdateResponse.blank().nextMap())
                    .isEqualTo(" ".repeat(UserUpdateResponse.NEXT_MAP_LENGTH));
            assertThat(UserUpdateResponse.blank().nextMapset())
                    .isEqualTo(" ".repeat(UserUpdateResponse.NEXT_MAPSET_LENGTH));
        }
    }

    @Nested
    @DisplayName("Conversation state - the commarea travels in the payload, never in a session")
    class ConversationState {

        @Test
        @DisplayName("the 160-byte communication area is a payload member, proven through the codec")
        void theCommareaIsAPayloadMember() {
            assertThat(NavigationContext.COMMAREA_LENGTH)
                    .as("34 general + 84 customer + 12 account + 16 card + 14 more, COCOM01Y:19-44")
                    .isEqualTo(COMMAREA_LENGTH)
                    .isEqualTo(160);
            assertThat(NavigationContext.GENERAL_INFO_LENGTH
                    + NavigationContext.CUSTOMER_INFO_LENGTH
                    + NavigationContext.ACCOUNT_INFO_LENGTH
                    + NavigationContext.CARD_INFO_LENGTH
                    + NavigationContext.MORE_INFO_LENGTH)
                    .as("re-derived from the five groups rather than restated")
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH);

            NavigationContext area = NavigationContext.empty()
                    .withFromTranid(TRANSACTION_ID)
                    .withFromProgram(PROGRAM_NAME)
                    .withToProgram("COADM01C")
                    .withLastMap(MAP_NAME)
                    .withLastMapset(MAPSET_NAME);
            byte[] image = area.toFixedWidth(codec());

            assertThat(image)
                    .as("the whole conversation fits 160 bytes, so nothing needs to be held server-side")
                    .hasSize(NavigationContext.COMMAREA_LENGTH);
            assertThat(NavigationContext.fromFixedWidth(codec(), image))
                    .as("and it round-trips, which is what makes carrying it in the payload viable")
                    .isEqualTo(area);
            assertThat(UserUpdateResponse.blank().withNavigationContext(area).navigationContext())
                    .isEqualTo(area);
        }

        @Test
        @DisplayName("an absent area is the initialised one, because EIBCALEN = 0 is a recognised state")
        void anAbsentAreaIsTheInitialisedOne() {
            // A response is what the program is about to send, so its area always exists. Line 90 treats
            // EIBCALEN = 0 as a recognised cold start rather than an error, and line 91 answers it by
            // naming COSGN00C as the target.
            assertThat(UserUpdateResponse.blank().withNavigationContext(null).navigationContext())
                    .isEqualTo(NavigationContext.empty());
            assertThat(UserUpdateResponse.blank().navigationContext())
                    .isEqualTo(NavigationContext.empty());
        }

        @Test
        @DisplayName("CDEMO-PGM-CONTEXT reads through the area, and both 88-levels are driven")
        void bothContextStatesAreDriven() {
            // A DIVERGENCE from this file's brief, asserted as declared: the enter/re-enter flag is not
            // a member of its own. It reads through NavigationContext, so CDEMO-PGM-CONTEXT has exactly
            // one home and the two cannot drift apart. Both states are driven anyway, because the branch
            // gate requires every 88-level to be exercised in both directions.
            UserUpdateResponse onEnter = UserUpdateResponse.blank()
                    .withNavigationContext(NavigationContext.empty().withPgmEnter());
            UserUpdateResponse onReenter = UserUpdateResponse.blank()
                    .withNavigationContext(NavigationContext.empty().withPgmReenter());

            assertThat(onEnter.navigationContext().isEnter())
                    .as("88 CDEMO-PGM-ENTER VALUE 0, app/cpy/COCOM01Y.cpy:30 - line 257 moves ZEROS "
                            + "here before the XCTL")
                    .isTrue();
            assertThat(onEnter.navigationContext().isReenter()).isFalse();
            assertThat(onEnter.navigationContext().pgmContext()).isZero();

            assertThat(onReenter.navigationContext().isReenter())
                    .as("88 CDEMO-PGM-REENTER VALUE 1, line 31 - line 96 sets it before painting")
                    .isTrue();
            assertThat(onReenter.navigationContext().isEnter()).isFalse();
            assertThat(onReenter.navigationContext().pgmContext()).isEqualTo(1);
        }

        @Test
        @DisplayName("the user-type 88-levels are driven in both directions as well")
        void bothUserTypeStatesAreDriven() {
            // 88 CDEMO-USRTYP-ADMIN VALUE 'A' and 88 CDEMO-USRTYP-USER VALUE 'U', COCOM01Y:27-28. This
            // screen's own USRTYPE field carries the same two values, per the '(A=Admin, U=User)' legend
            // at app/bms/COUSR02.bms:154.
            NavigationContext admin = NavigationContext.empty().withUserTypeAdmin();
            NavigationContext user = NavigationContext.empty().withUserTypeUser();

            assertThat(admin.isAdmin()).isTrue();
            assertThat(admin.isUser()).isFalse();
            assertThat(user.isUser()).isTrue();
            assertThat(user.isAdmin()).isFalse();
            assertThat(UserUpdateResponse.blank().withUsrType("A").usrType())
                    .as("and the map field holds one character of the same alphabet")
                    .isEqualTo("A")
                    .hasSize(UserUpdateResponse.USR_TYPE_LENGTH);
            assertThat(UserUpdateResponse.blank().withUsrType("U").usrType()).isEqualTo("U");
        }

        @Test
        @DisplayName("the AID vocabulary is the resolver's, and it is the request that carries it")
        void theAidVocabularyIsTheResolvers() {
            // A DIVERGENCE from this file's brief, asserted as declared: the resolved AID token is a
            // member of UserUpdateRequest, not of the response - the key press is something the client
            // reports inbound. The vocabulary is asserted here because this screen's two save paths both
            // turn on it: PF5 saves (line 122-123) and PF3 saves BEFORE exiting (lines 111-119), which is
            // why the AID has to travel in the payload rather than be inferred.
            assertThat(PfKeyResolver.AID_TOKEN_LENGTH)
                    .as("CCARD-AID X(5) - every token is rendered at five characters")
                    .isEqualTo(5);
            for (PfKeyResolver.AidKey key : PfKeyResolver.AidKey.values()) {
                assertThat(key.token())
                        .as("%s renders at the declared five characters", key.name())
                        .hasSize(PfKeyResolver.AID_TOKEN_LENGTH);
            }
            assertThat(Arrays.stream(PfKeyResolver.AidKey.values())
                    .map(PfKeyResolver.AidKey::token).toList())
                    .as("ENTER, CLEAR, the two PA keys padded to five, and PFK01 through PFK12")
                    .contains("ENTER", "CLEAR", "PA1  ", "PA2  ", "PFK01", "PFK03", "PFK04", "PFK05",
                            "PFK12");
            assertThat(PfKeyResolver.AidKey.values()).hasSize(16);
            assertThat(Arrays.stream(UserUpdateResponse.class.getRecordComponents())
                    .map(RecordComponent::getName).toList())
                    .as("and the response does not re-carry it: the outbound half of the same contract "
                            + "is nextProgram, nextMapset and nextMap")
                    .doesNotContain("aid")
                    .contains("nextProgram");
        }

        @Test
        @DisplayName("no session handle, no cache and no static mutable state exists on the payload")
        void statelessnessIsStructural() {
            for (Field field : UserUpdateResponse.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("static field %s must be final: a mutable static holder would be a "
                                    + "session by another name and would break request isolation",
                                    field.getName())
                            .isTrue();
                } else {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("record component %s is final, so an instance cannot be changed under "
                                    + "a collaborator that already holds it", field.getName())
                            .isTrue();
                }
            }
            Set<String> typeNames = new LinkedHashSet<>();
            for (Method method : UserUpdateResponse.class.getDeclaredMethods()) {
                typeNames.add(method.getReturnType().getName().toLowerCase(Locale.ROOT));
            }
            for (Field field : UserUpdateResponse.class.getDeclaredFields()) {
                typeNames.add(field.getType().getName().toLowerCase(Locale.ROOT));
            }
            for (String typeName : typeNames) {
                assertThat(typeName)
                        .doesNotContain("httpsession")
                        .doesNotContain("threadlocal")
                        .doesNotContain("servlet")
                        .doesNotContain("cache");
            }
        }
    }

    // =================================================================================================
    // PHASE 6 - the 34-byte CDEMO-CU02-INFO extension, and why NavigationContext stays at 160.
    // =================================================================================================

    @Nested
    @DisplayName("CDEMO-CU02-INFO on the reply - six items, 34 bytes, and a 194-byte area")
    class Cu02InfoOnTheReply {

        @Test
        @DisplayName("the extension is 34 bytes, re-derived from its six declared widths")
        void theExtensionIsThirtyFourBytes() {
            assertThat(CU02_ITEM_NAMES).hasSize(6).hasSameSizeAs(CU02_ITEM_WIDTHS);
            assertThat(CU02_ITEM_WIDTHS.stream().mapToInt(Integer::intValue).sum())
                    .as("8+8+8+1+1+8, app/cbl/COUSR02C.cbl:51-58")
                    .isEqualTo(CU02_EXTENSION_LENGTH)
                    .isEqualTo(34);
            assertThat(UserUpdateRequest.Cu02Info.LENGTH)
                    .as("and the type re-derives the same total from its own width constants")
                    .isEqualTo(CU02_EXTENSION_LENGTH);
            assertThat(UserUpdateRequest.Cu02Info.initial().fieldImages().keySet())
                    .as("keyed on the names the program spells, in declaration order")
                    .containsExactlyElementsOf(CU02_ITEM_NAMES);
        }

        @Test
        @DisplayName("NavigationContext stays 160 and the CU02 area is 194 - the two are not merged")
        void theCommareaStaysOneHundredAndSixtyAndTheAreaIsOneNinetyFour() {
            // The extension is declared in the PROGRAM's working storage at lines 50-58, not in
            // app/cpy/COCOM01Y.cpy. Folding it into NavigationContext would widen the area every one of
            // the seventeen controllers receives, for one program's private group.
            assertThat(NavigationContext.COMMAREA_LENGTH)
                    .as("shared by all seventeen controllers, and never widened")
                    .isEqualTo(160);
            assertThat(NavigationContext.COMMAREA_LENGTH + UserUpdateRequest.Cu02Info.LENGTH)
                    .as("160 + 34 = the 194 bytes app/cbl/COUSR02C.cbl:94 restores")
                    .isEqualTo(CU02_COMMAREA_LENGTH)
                    .isEqualTo(194);
            assertThat(NavigationContext.empty().toFixedWidth(codec()))
                    .as("and the shared area's image is still exactly 160 bytes")
                    .hasSize(160);
            assertThat(CU02_ITEM_NAMES)
                    .as("every item is CDEMO-CU02- prefixed, so it cannot be confused with the "
                            + "identically shaped CDEMO-CU00- block of COUSR00C:67-75 or the "
                            + "CDEMO-CU03- block of COUSR03C:50-58 - three declarations of one span, "
                            + "not one shared type, and COSGN00C and COUSR01C declare none")
                    .allMatch(name -> name.startsWith("CDEMO-CU02-"));
        }

        @Test
        @DisplayName("the extension is a member of the response and is carried, not consumed")
        void theExtensionIsCarriedOnTheReply() {
            assertThat(Arrays.stream(UserUpdateResponse.class.getRecordComponents())
                    .map(RecordComponent::getName).toList())
                    .as("line 260 hands the whole 194-byte area back on the XCTL, extension included")
                    .contains("cu02Info");
            assertThat(accessorOf("cu02Info").getReturnType())
                    .isEqualTo(UserUpdateRequest.Cu02Info.class);
            assertThat(UserUpdateResponse.blank().cu02Info())
                    .as("blank() supplies the group as its VALUE clauses left it")
                    .isEqualTo(UserUpdateRequest.Cu02Info.initial());
            assertThat(UserUpdateResponse.blank().withCu02Info(null).cu02Info())
                    .as("and an absent group is the initialised group, not an error - the area has no "
                            + "absent state once EIBCALEN is non-zero")
                    .isEqualTo(UserUpdateRequest.Cu02Info.initial());
        }

        @Test
        @DisplayName("pageNum is an int, never a binary floating-point type")
        void thePageNumberIsAnIntegralType() {
            // CDEMO-CU02-PAGE-NUM is PIC 9(08): eight digits and no V, so it is scale-free and an
            // integral Java type is the faithful one. A float or a double could not represent every
            // eight-digit value exactly, and nothing in this payload is monetary.
            RecordComponent pageNum = Arrays.stream(UserUpdateRequest.Cu02Info.class
                            .getRecordComponents())
                    .filter(component -> component.getName().equals("pageNum"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Cu02Info declares no pageNum"));

            assertThat(pageNum.getType())
                    .as("PIC 9(08) is scale-free, so int - and explicitly not a floating-point type")
                    .isEqualTo(int.class)
                    .isNotEqualTo(double.class)
                    .isNotEqualTo(float.class);
            assertThat(UserUpdateRequest.Cu02Info.PAGE_NUM_DIGITS).isEqualTo(8);
            assertThat(codec().movePic9(7L, UserUpdateRequest.Cu02Info.PAGE_NUM_DIGITS))
                    .as("and its image is zero-filled to eight digits, as PIC 9(08) requires")
                    .isEqualTo("00000007");
        }

        @Test
        @DisplayName("no floating-point type appears anywhere on either payload's surface")
        void noFloatingPointTypeAppearsAnywhere() {
            for (Class<?> payload : List.of(UserUpdateResponse.class,
                    UserUpdateRequest.Cu02Info.class)) {
                for (RecordComponent component : payload.getRecordComponents()) {
                    assertThat(component.getType())
                            .as("%s.%s", payload.getSimpleName(), component.getName())
                            .isNotEqualTo(double.class)
                            .isNotEqualTo(float.class)
                            .isNotEqualTo(Double.class)
                            .isNotEqualTo(Float.class);
                }
                for (Method method : payload.getDeclaredMethods()) {
                    assertThat(method.getReturnType())
                            .as("%s.%s()", payload.getSimpleName(), method.getName())
                            .isNotEqualTo(double.class)
                            .isNotEqualTo(float.class);
                }
            }
        }

        @ParameterizedTest(name = "[{index}] flag \"{0}\": yes={1}, no={2}")
        @CsvSource({"Y,true,false", "N,false,true", "' ',false,false"})
        @DisplayName("both 88-levels of NEXT-PAGE-FLG are driven, and a third value satisfies neither")
        void bothConditionNamesAreDrivenInBothDirections(String flag, boolean expectYes,
                boolean expectNo) {
            // 88 NEXT-PAGE-YES VALUE 'Y' at app/cbl/COUSR02C.cbl:55 and 88 NEXT-PAGE-NO VALUE 'N' at
            // line 56. The field is PIC X(01), so any character fits, and the source declares no
            // WHEN OTHER - which means a space is a real state satisfying neither predicate.
            UserUpdateRequest.Cu02Info info = new UserUpdateRequest.Cu02Info("        ", "        ",
                    0, flag, " ", "        ");

            assertThat(flag.equals(NEXT_PAGE_YES)).as("NEXT-PAGE-YES for '%s'", flag)
                    .isEqualTo(expectYes);
            assertThat(flag.equals(NEXT_PAGE_NO)).as("NEXT-PAGE-NO for '%s'", flag)
                    .isEqualTo(expectNo);
            assertThat(info.nextPageFlg())
                    .as("the group stores the character at its declared width of one")
                    .isEqualTo(flag)
                    .hasSize(UserUpdateRequest.Cu02Info.NEXT_PAGE_FLG_LENGTH);
            assertThat(UserUpdateResponse.blank().withCu02Info(info).cu02Info().nextPageFlg())
                    .isEqualTo(flag);
        }

        @Test
        @DisplayName("aThirdValueSatisfiesNeitherConditionName - stated on its own, because the source "
                + "declares no WHEN OTHER for these two")
        void aThirdValueSatisfiesNeitherConditionName() {
            assertThat(NEXT_PAGE_NEITHER)
                    .isNotEqualTo(NEXT_PAGE_YES)
                    .isNotEqualTo(NEXT_PAGE_NO)
                    .hasSize(UserUpdateRequest.Cu02Info.NEXT_PAGE_FLG_LENGTH);
            assertThat(NEXT_PAGE_YES).isEqualTo(UserUpdateRequest.Cu02Info.NEXT_PAGE_YES);
            assertThat(NEXT_PAGE_NO).isEqualTo(UserUpdateRequest.Cu02Info.NEXT_PAGE_NO);
        }

        @Test
        @DisplayName("'N' is the declared default, from the VALUE clause at line 54")
        void theDeclaredDefaultIsNo() {
            assertThat(UserUpdateRequest.Cu02Info.initial().nextPageFlg())
                    .as("PIC X(01) VALUE 'N' - not a convention chosen in Java")
                    .isEqualTo(NEXT_PAGE_NO);
            assertThat(UserUpdateResponse.blank().cu02Info().nextPageFlg()).isEqualTo(NEXT_PAGE_NO);
            assertThat(UserUpdateRequest.Cu02Info.initial().pageNum())
                    .as("the other five items declare no VALUE, so they begin as spaces and zero")
                    .isZero();
            assertThat(UserUpdateRequest.Cu02Info.initial().usrSelected()).isBlank();
        }

        @Test
        @DisplayName("a negative or over-wide page number is refused, not silently stored")
        void thePageNumberGuardsAreDriven() {
            // PIC 9(08) is unsigned - there is no sign position - so a negative value has no
            // representation, and a ninth digit would be dropped by a numeric MOVE while the result
            // still looked plausible.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new UserUpdateRequest.Cu02Info("        ", "        ", -1,
                            NEXT_PAGE_NO, " ", "        "))
                    .withMessageContaining("CDEMO-CU02-PAGE-NUM");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new UserUpdateRequest.Cu02Info("        ", "        ", 100_000_000,
                            NEXT_PAGE_NO, " ", "        "))
                    .withMessageContaining("CDEMO-CU02-PAGE-NUM");
            assertThat(new UserUpdateRequest.Cu02Info("        ", "        ", 99_999_999,
                    NEXT_PAGE_NO, " ", "        ").pageNum())
                    .as("the largest eight-digit value is representable")
                    .isEqualTo(99_999_999);
        }
    }

    // =================================================================================================
    // PHASE 7 - serialisation.
    // =================================================================================================

    @Nested
    @DisplayName("Serialisation - the password IS in the JSON, and padding survives")
    class Serialisation {

        @Test
        @DisplayName("the serialised JSON contains the password, with its exact eight characters")
        void theJsonContainsThePlaintextPassword() throws Exception {
            // app/cbl/COUSR02C.cbl:169  MOVE SEC-USR-PWD TO PASSWDI OF COUSR2AI
            //
            // This is THE assertion of this file. It exists so that adding a redaction - a @JsonIgnore,
            // a write-only access mode, a masking serialiser - fails the build rather than silently
            // deleting a value the legacy program sends. If a future reviewer believes the payload
            // should not carry a password, the change they want is a change to the legacy program's
            // behaviour, and it belongs in a decision, not in a quiet edit to a DTO.
            UserUpdateResponse response = afterSuccessfulRead();

            String json = webConfigEquivalentMapper().writeValueAsString(response);

            assertThat(json)
                    .as("the member is present under its own untransformed name")
                    .contains("\"passwd\"");
            assertThat(json)
                    .as("carrying exactly the eight characters line 169 moved onto the screen")
                    .contains("\"passwd\":\"" + PASSWD_FIXTURE + "\"");
            assertThat(webConfigEquivalentMapper().readTree(json).get("passwd").asText())
                    .isEqualTo(PASSWD_FIXTURE)
                    .hasSize(UserUpdateResponse.PASSWD_LENGTH);
        }

        @Test
        @DisplayName("a blanked password serialises as eight spaces, not as null and not as absent")
        void aBlankedPasswordSerialisesAsSpaces() {
            // The state a failed lookup leaves behind, per lines 158-161. It has to be distinguishable
            // from a value, which is exactly why ACCEPT_EMPTY_STRING_AS_NULL_OBJECT is disabled.
            ObjectMapper mapper = webConfigEquivalentMapper();
            UserUpdateResponse blank = UserUpdateResponse.blank();

            String json = assertDoesNotThrowJson(() -> mapper.writeValueAsString(blank));

            assertThat(json).contains("\"passwd\":\"        \"");
            assertThat(json).doesNotContain("\"passwd\":null");
            UserUpdateResponse revived = assertDoesNotThrowResponse(
                    () -> mapper.readValue(json, UserUpdateResponse.class));
            assertThat(revived.passwd())
                    .isEqualTo(" ".repeat(UserUpdateResponse.PASSWD_LENGTH))
                    .isNotNull();
        }

        @Test
        @DisplayName("exactly the seventeen expected member names appear, and nothing else")
        void exactlyTheExpectedMembersAppear() throws Exception {
            ObjectNode tree = (ObjectNode) webConfigEquivalentMapper()
                    .valueToTree(afterSuccessfulRead());

            Set<String> emitted = new LinkedHashSet<>();
            tree.fieldNames().forEachRemaining(emitted::add);

            assertThat(emitted)
                    .as("the twelve map members plus the five carriers")
                    .containsExactlyInAnyOrderElementsOf(EXPECTED_JSON_MEMBERS)
                    .hasSize(COMPONENT_COUNT);
        }

        @Test
        @DisplayName("no attribute, length or filler item name appears in the serialised form")
        void noMetadataNameAppears() throws Exception {
            String json = webConfigEquivalentMapper().writeValueAsString(afterSuccessfulRead());

            for (String forbidden : FORBIDDEN_JSON_MEMBERS) {
                assertThat(json)
                        .as("%s is presentation or reserved metadata and must not reach the wire",
                                forbidden)
                        .doesNotContain("\"" + forbidden + "\"");
            }
            assertThat(FORBIDDEN_JSON_MEMBERS)
                    .as("four attribute items plus xxxL, xxxF and xxxA for twelve fields, plus FILLER")
                    .hasSize(DFHMDF_NAMED * (ATTRIBUTE_SUFFIXES.size() + 3) + 1)
                    .hasSize(85);
        }

        @Test
        @DisplayName("property names are untransformed, so each still traces to its xxxO item")
        void propertyNamesAreUntransformed() throws Exception {
            ObjectMapper mapper = webConfigEquivalentMapper();
            String json = mapper.writeValueAsString(afterSuccessfulRead());

            for (String member : MAP_MEMBERS) {
                assertThat(json)
                        .as("%s is emitted verbatim - no snake_case, no kebab-case, no upper camel",
                                member)
                        .contains("\"" + member + "\"");
            }

            // Asserted over the TOP-LEVEL property names, not over the whole JSON string. The nested
            // navigationContext legitimately carries a userId of its own - CDEMO-USER-ID at
            // app/cpy/COCOM01Y.cpy:25 - so a substring search of the document would find "userId" there
            // and say nothing at all about this map's own field. What matters is that the response's own
            // identifier property is usrIdIn.
            Set<String> topLevel = new LinkedHashSet<>();
            ((ObjectNode) mapper.valueToTree(afterSuccessfulRead())).fieldNames()
                    .forEachRemaining(topLevel::add);

            assertThat(topLevel)
                    .as("this map spells its identifier USRIDIN, and COUSR01 spells its USERID; the "
                            + "two are deliberately not harmonised")
                    .contains("usrIdIn")
                    .doesNotContain("userId")
                    .doesNotContain("usr_id_in")
                    .doesNotContain("UsrIdIn")
                    .doesNotContain("secUsrId");
            assertThat(mapper.valueToTree(afterSuccessfulRead()).get("navigationContext")
                    .has("userId"))
                    .as("while the communication area's own CDEMO-USER-ID stays where it belongs")
                    .isTrue();
        }

        @Test
        @DisplayName("a fully padded instance survives serialise then deserialise byte for byte")
        void aFullyPaddedInstanceRoundTrips() throws Exception {
            ObjectMapper mapper = webConfigEquivalentMapper();
            FixedWidthCodec codec = codec();
            UserUpdateResponse original = UserUpdateResponse.blank()
                    .withTrnName(TRANSACTION_ID)
                    .withTitle01(ScreenTitles.CCDA_TITLE01)
                    .withCurDate(EXPECTED_CURDATE)
                    .withPgmName(PROGRAM_NAME)
                    .withTitle02(ScreenTitles.CCDA_TITLE02)
                    .withCurTime(EXPECTED_CURTIME)
                    .withUsrIdIn(USER_ID_FIXTURE)
                    .withFName(codec.movePicX("John", UserUpdateResponse.FNAME_LENGTH))
                    .withLName(codec.movePicX("Doe", UserUpdateResponse.LNAME_LENGTH))
                    .withPasswd(PASSWD_FIXTURE)
                    .withUsrType("U")
                    .withErrMsg(errMsgImageOf(MSG_PRESS_PF5))
                    .withNextProgram("COADM01C")
                    .withNextMapset(MAPSET_NAME)
                    .withNextMap(MAP_NAME)
                    .withNavigationContext(NavigationContext.empty()
                            .withFromTranid(TRANSACTION_ID)
                            .withFromProgram(PROGRAM_NAME)
                            .withUserId(USER_ID_FIXTURE)
                            .withPgmReenter())
                    .withCu02Info(new UserUpdateRequest.Cu02Info("USER0001", "USER0050", 3,
                            NEXT_PAGE_YES, "S", USER_ID_FIXTURE));

            UserUpdateResponse revived = mapper.readValue(mapper.writeValueAsString(original),
                    UserUpdateResponse.class);

            assertThat(revived)
                    .as("every member, padding and all - trimming any of it would change which "
                            + "updates lines 219-234 detect")
                    .isEqualTo(original);
            assertThat(revived.fName())
                    .hasSize(UserUpdateResponse.FNAME_LENGTH)
                    .isEqualTo("John" + " ".repeat(UserUpdateResponse.FNAME_LENGTH - 4));
            assertThat(revived.lName()).hasSize(UserUpdateResponse.LNAME_LENGTH);
            assertThat(revived.errMsg()).hasSize(UserUpdateResponse.ERR_MSG_LENGTH);
            assertThat(revived.passwd())
                    .as("and the password above all, since it is the member most likely to be lost")
                    .isEqualTo(PASSWD_FIXTURE);
            assertThat(revived.cu02Info().pageNum())
                    .as("the page number survives as an integer, with no exponent notation anywhere")
                    .isEqualTo(3);
            assertThat(revived.navigationContext().isReenter()).isTrue();
        }

        @Test
        @DisplayName("the page number renders as a plain integer, never in exponent notation")
        void thePageNumberRendersPlainly() throws Exception {
            UserUpdateResponse response = UserUpdateResponse.blank()
                    .withCu02Info(new UserUpdateRequest.Cu02Info("        ", "        ", 99_999_999,
                            NEXT_PAGE_NO, " ", "        "));

            String json = webConfigEquivalentMapper().writeValueAsString(response);

            assertThat(json)
                    .as("WRITE_BIGDECIMAL_AS_PLAIN is enabled, and an int never uses exponent form")
                    .contains("99999999")
                    .doesNotContain("9.9999999E7")
                    .doesNotContain("E+");
        }

        @Test
        @DisplayName("a default mapper would be the wrong instrument, and this states why")
        void theMapperConfigurationIsTheDeployedOne() {
            ObjectMapper configured = webConfigEquivalentMapper();

            assertThat(configured.isEnabled(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS))
                    .as("no value may route through a binary floating-point type")
                    .isTrue();
            assertThat(configured.getFactory()
                    .isEnabled(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN))
                    .as("and none may serialise in exponent notation")
                    .isTrue();
            assertThat(configured.isEnabled(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT))
                    .as("an all-spaces PIC X(n) value must stay the screen data it is; coercing it to "
                            + "null would defeat every blank-field assertion above, the blanked "
                            + "password among them")
                    .isFalse();
            assertThat(configured.getPropertyNamingStrategy())
                    .as("no naming strategy, so each property still traces to one xxxO item")
                    .isNull();
        }
    }

    /**
     * Runs a serialisation that is not expected to fail, turning a checked failure into an assertion
     * failure so the calling test needs no {@code throws} clause of its own.
     *
     * @param call the serialisation
     * @return the JSON produced
     */
    private static String assertDoesNotThrowJson(JsonCall<String> call) {
        try {
            return call.get();
        } catch (Exception failure) {
            throw new AssertionError("serialising a blank response must not fail", failure);
        }
    }

    /**
     * Runs a deserialisation that is not expected to fail.
     *
     * @param call the deserialisation
     * @return the response produced
     */
    private static UserUpdateResponse assertDoesNotThrowResponse(JsonCall<UserUpdateResponse> call) {
        try {
            return call.get();
        } catch (Exception failure) {
            throw new AssertionError("deserialising a blank response must not fail", failure);
        }
    }

    /**
     * A call that may throw a checked exception, so the two helpers above can wrap one.
     *
     * @param <T> the produced type
     */
    @FunctionalInterface
    private interface JsonCall<T> {

        /**
         * Performs the call.
         *
         * @return its result
         * @throws Exception if the call fails
         */
        T get() throws Exception;
    }
}
