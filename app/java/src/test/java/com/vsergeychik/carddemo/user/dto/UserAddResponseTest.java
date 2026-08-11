package com.vsergeychik.carddemo.user.dto;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.user.model.SecUserRecord;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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

/**
 * Unit tests for {@link UserAddResponse} - the outbound payload of {@code POST /api/users}, CICS
 * transaction {@code CU01}, program {@code app/cbl/COUSR01C.cbl}, map {@code COUSR1A} of mapset
 * {@code COUSR01}: the screen the Add User transaction paints.
 *
 * <p>Two subjects define this file, and each is a place where a plausible-looking translation goes
 * wrong without anything failing.
 *
 * <p><strong>The group-level overlay.</strong> {@code app/cpy-bms/COUSR01.CPY:91} declares
 * {@code 01 COUSR1AO REDEFINES COUSR1AI.} - the output view of this screen is not a second buffer, it
 * is the <em>same storage</em> read through a second set of names. Both views spend exactly seven bytes
 * of control items before each data item, so every {@code xxxO} item sits at the identical offset as
 * its {@code xxxI} partner and the two align with zero drift. That is what {@link SymbolicMapOverlay}
 * proves, by writing through one view and reading back through the other.
 *
 * <p><strong>The status of {@code passwd}.</strong> It is declared, and it never carries a stored
 * password. {@code PASSWD} is one of this mapset's twelve name-labelled fields so the member must
 * exist, but {@code grep -c 'PASSWDO' app/cbl/COUSR01C.cbl} returns <strong>zero</strong>: the program
 * never names the output item at all. The only values that ever reach that offset are the
 * {@code LOW-VALUES} of {@code COUSR01C.cbl:85} and the spaces of {@code COUSR01C.cbl:293}, and the
 * second of those writes {@code PASSWDI} - which lands on the same bytes precisely because of the
 * overlay above. See {@link PasswordDeclaredButNeverEchoed}.
 *
 * <h2>Project rules</h2>
 *
 * {@code review_rules} reports <strong>"No user rules provided."</strong> - that single line is the
 * entire document and there is nothing further to page through. No rule is invented here, and the
 * absence of rules is emphatically <em>not</em> treated as licence to assert less. The binding
 * constraints are the enterprise best-practice substitutes {@code B1}-{@code B12} recorded in the
 * migration plan. Each is named below with the one thing it requires of this file; the plan holds the
 * full text of every practice and only the ruling is restated here.
 *
 * <ul>
 *   <li><strong>B1</strong> - imports are confined to the JDK, JUnit Jupiter, AssertJ and the Jackson
 *       already on the test classpath. No coordinate is introduced and nothing is drawn from the
 *       plan's exclusion list. Mockito is available and deliberately unused: a payload type has no
 *       collaborator to stand in for, and a mock would only obscure that every case here is reachable
 *       by construction. Every internal import is a type this payload's own declaration reaches -
 *       {@link SensitiveDiagnostics} is here because {@link UserAddResponse#toString()} is written in
 *       terms of it, so asserting the redaction against that type's own constant is the only way to
 *       assert it without restating a value owned elsewhere as a literal.</li>
 *   <li><strong>B2</strong> - the JUnit 5 Jupiter API only, even where a later line is published.</li>
 *   <li><strong>B3</strong> - the reference tree is neither written nor <em>read</em>. Every
 *       expectation below is a {@code private static final} constant carrying the file and line it was
 *       transcribed from, so this suite is hermetic and cannot depend on the directory the build was
 *       launched from.</li>
 *   <li><strong>B4</strong> - conflicts are documented, never reconciled. Four are relevant and all
 *       four are set out under "Conflicts recorded, not reconciled" below.</li>
 *   <li><strong>B5</strong> - no member is asserted into or out of existence for symmetry with a
 *       sibling payload. The screen-field census here is exactly twelve, {@code passwd} included, and
 *       the three different password treatments in this package are all correct as they stand.</li>
 *   <li><strong>B6</strong> - the security posture is neither weakened nor strengthened. The password
 *       member stays a plaintext {@code PIC X(08)} {@code String}; see {@link SecurityPosture}.</li>
 *   <li><strong>B7</strong> - nothing reads a wall clock, draws a random value or depends on another
 *       test having run. The two time-derived expectations come from
 *       {@link Clock#fixed(Instant, java.time.ZoneId)} through {@link DateHeader}, which is the seam
 *       that exists because {@code config.WebConfig} publishes the module's single {@link Clock} bean
 *       instead of letting a caller reach for the system clock.</li>
 *   <li><strong>B8</strong> - every codec call names its {@link Charset} explicitly and no overload
 *       that omits it is used, so no platform default is ever relied on. Every import is written out
 *       individually: there is no wildcard import in this file. No dataset name appears here.</li>
 *   <li><strong>B9</strong> - every field of this class is {@code static final} and deeply immutable.
 *       No state is shared between test methods: JUnit's per-method instance lifecycle does the
 *       isolating, and every case that writes bytes takes a fresh record of its own.</li>
 *   <li><strong>B10</strong> - this suite ships with the type it measures rather than being added
 *       afterwards, which is what makes a drift from the mapset traceable to the decision that caused
 *       it instead of surfacing later as an unexplained difference.</li>
 *   <li><strong>B11</strong> - fixed-width and truncation work goes through {@link FixedWidthCodec}
 *       and {@link FixedWidthRecord}. No third-party copybook parser is used, and no assertion
 *       substitutes {@link String#substring(int, int)} for a COBOL {@code MOVE}.</li>
 *   <li><strong>B12</strong> - the environmental limit is stated rather than absorbed; see the
 *       provenance note immediately below.</li>
 * </ul>
 *
 * <h2>Provenance of every expected value (B12)</h2>
 *
 * COBOL cannot be executed in this environment - the plan records eight independently verified
 * blockers as risk {@code R-A}, among them a disabled indexed-file handler, absent Language
 * Environment services and the absence of any CICS emulator. Every expectation below is therefore
 * <strong>statically derived</strong> by reading the source, never captured from a run. The lines used
 * are:
 *
 * <ul>
 *   <li>{@code app/cpy-bms/COUSR01.CPY} - {@code 01 COUSR1AI} at 17 with its {@code TIOAPFX} filler at
 *       18; the twelve {@code xxxI} items at 24, 30, 36, 42, 48, 54, 60, 66, 72, 78, 84 and 90;
 *       {@code 01 COUSR1AO REDEFINES COUSR1AI} at 91 with its own {@code TIOAPFX} filler at 92; and
 *       the twelve {@code xxxO} items at 98, 104, 110, 116, 122, 128, 134, 140, 146, 152, 158 and
 *       164.</li>
 *   <li>{@code app/bms/COUSR01.bms} - the mapset at 19, the map at 26 with {@code SIZE=(24,80)} at 28,
 *       and twenty-eight {@code DFHMDF} definitions of which the twelve name-labelled ones sit at 34,
 *       38, 47, 57, 61, 70, 84, 97, 111, 126, 141 and 151.</li>
 *   <li>{@code app/cbl/COUSR01C.cbl} - {@code WS-PGMNAME} at 36, {@code WS-TRANID} at 37,
 *       {@code WS-MESSAGE PIC X(80)} at 38, the disabled {@code DFHATTR} include at 57, the
 *       {@code ERRMSGO} blanking at 75-76, the no-commarea target at 79, the first-entry
 *       {@code MOVE LOW-VALUES TO COUSR1AO} at 85, the {@code EIBAID} evaluation at 90-103,
 *       {@code PROCESS-ENTER-KEY} at 115 with its {@code EVALUATE TRUE} at 117-151,
 *       {@code RETURN-TO-PREV-SCREEN} at 165 with the blank-target default at 167-168, the two enabled
 *       moves at 170-171, the two commented-out moves at 172-173, the context reset at 174, the
 *       transfer at 175-178, the narrowing {@code MOVE} into {@code ERRMSGO} at 188, the map and
 *       mapset operands at 191-192, {@code FROM(COUSR1AO)} at 193, the header moves at 218-221 and
 *       227 and 233, the write at 240-248, its three outcomes at 250-274 including
 *       {@code MOVE DFHGREEN TO ERRMSGC} at 254 and the success {@code STRING} at 255-258, and
 *       {@code INITIALIZE-ALL-FIELDS} at 287-295.</li>
 *   <li>{@code app/cpy/CSUSR01Y.cpy} - {@code 01 SEC-USER-DATA} at 17 and its six items at 18-23.</li>
 *   <li>{@code app/cpy/COCOM01Y.cpy} - {@code 01 CARDDEMO-COMMAREA} at 19-44, the user-type condition
 *       names at 27-28, the program-context item and its condition names at 29-31, and the two
 *       {@code PIC X(7)} map names at 43-44.</li>
 *   <li>{@code app/csd/CARDDEMO.CSD} - {@code DEFINE PROGRAM(COUSR01C)} at 285 and
 *       {@code DEFINE TRANSACTION(CU01) ... PROGRAM(COUSR01C)} at 459-460.</li>
 * </ul>
 *
 * <h2>Conflicts recorded, not reconciled (B4)</h2>
 *
 * <ul>
 *   <li><strong>A referenced-but-disabled copybook.</strong> {@code COUSR01C.cbl:55-56} copy
 *       {@code DFHAID} and {@code DFHBMSCA}; line 57 reads {@code *COPY DFHATTR.} - commented out.
 *       Recorded and left as it stands. Nothing here asserts that a {@code DFHATTR}-only constant is
 *       in use on this screen. Where this file does reach for {@code common.BmsAttributes} it uses
 *       {@link BmsAttributes#DFHRED} and {@link BmsAttributes#DFHGREEN}, which are
 *       <em>{@code DFHBMSCA}</em> constants supplied by the include at line 56 that <em>is</em> active
 *       - {@code COUSR01C.cbl:254} names {@code DFHGREEN} directly - so no disabled include is being
 *       relied on.</li>
 *   <li><strong>Two commented-out statements.</strong> {@code COUSR01C.cbl:172-173} read
 *       {@code *    MOVE WS-USER-ID   TO CDEMO-USER-ID} and
 *       {@code *    MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE}, inside {@code RETURN-TO-PREV-SCREEN}. They
 *       are disabled, so the transfer at 175-178 leaves {@code CDEMO-USER-ID} and
 *       {@code CDEMO-USER-TYPE} exactly as they arrived. This file asserts that <em>absence</em> and
 *       never the commented behaviour; it does not claim the two moves happen, and it does not claim
 *       they should. The contrast is real: {@code app/cbl/COSGN00C.cbl:226-227} does set both.</li>
 *   <li><strong>Two declared members the brief for this file describes differently.</strong> The brief
 *       expects a resolved AID token and an enter-versus-re-enter flag as members of this response.
 *       The declared type has neither: the AID is an <em>inbound</em> concern and lives on
 *       {@link UserAddRequest#aid()}, and the context flag lives inside the communication area, read
 *       through {@link NavigationContext#isEnter()} and {@link NavigationContext#isReenter()} rather
 *       than duplicated beside it. The declared members are ground truth. Both facts are asserted as
 *       declared in {@link StatelessNavigation}, with the divergence commented at the case that meets
 *       it, rather than the main class being edited to match a description of it.</li>
 *   <li><strong>A line-number drift in the brief.</strong> The brief cites
 *       {@code INITIALIZE-ALL-FIELDS} at {@code L286-294}. The paragraph label is at 287 and its
 *       statements run 289-295, with {@code PASSWDI} at 293. The verified numbers are used
 *       throughout.</li>
 * </ul>
 *
 * <h2>Scope: this file tests the type, not the program</h2>
 *
 * A plain JUnit 5 suite over a value type. No Spring context, no {@code @SpringBootTest}, no
 * {@code @WebMvcTest}, no {@code MockMvc}, no controller, no service and no repository appears here.
 * {@code user.UserAddControllerTest} already owns the HTTP projection, the ordered message chain and
 * the three outcomes of the {@code USRSEC} write; restating any of that here would report one defect
 * twice and let the two copies drift apart. The subject is {@link UserAddResponse} itself: what it
 * declares, what width each member carries, and what survives a round trip.
 *
 * <p>The package matters for coverage. The module's JaCoCo configuration declares its {@code BRANCH}
 * ratio rule at {@code PACKAGE} as well as {@code BUNDLE} granularity, so {@code user},
 * {@code user.model} and {@code user.dto} are each measured on their own and none can hide behind a
 * better-covered neighbour. {@code user.dto} therefore needs its own direct instruments.
 *
 * <p>Two things are absent for the same reason. <strong>No optimistic-concurrency assertion appears
 * anywhere in this file</strong>: paragraph {@code 9300-CHECK-CHANGE-IN-REC} exists in
 * {@code app/cbl/COACTUPC.cbl} and {@code app/cbl/COCRDUPC.cbl} and in none of the five user
 * programs, so no version column, no ETag and no conflict-detection expectation belongs in
 * {@code user.dto}. And <strong>no {@code CDEMO-CU0n-INFO} extension block appears</strong>:
 * {@code grep -c 'CDEMO-CU0[0-9]-INFO' app/cbl/COUSR01C.cbl} returns zero, so adding a paging block
 * for consistency with a sibling screen is exactly what B5 forbids.
 */
@DisplayName("UserAddResponse - the COUSR01 (CU01) Add User outbound payload")
class UserAddResponseTest {

    // =================================================================================================
    // 0. THE TRANSCRIBED CONTRACT.
    //
    // Every constant below is a literal read off a read-only source file and carries the line it came
    // from. Nothing here is computed from the type under test, because a test that derives its
    // expectation from its subject asserts only that the subject equals itself.
    // =================================================================================================

    /** The explicitly named code page for every codec call in this file (B8). */
    private static final Charset MAP_CHARSET = StandardCharsets.US_ASCII;

    /** Count of name-labelled {@code DFHMDF} fields, hence of payload members. */
    private static final int DFHMDF_NAMED = 12;

    /**
     * Every {@code DFHMDF} definition in {@code app/bms/COUSR01.bms}, labelled or not:
     * {@code grep -c 'DFHMDF' app/bms/COUSR01.bms} returns 28. Sixteen of them carry no label and
     * therefore have no symbolic-map item and no payload member - they are the {@code INITIAL} screen
     * furniture ({@code 'Tran:'}, {@code 'Date:'}, {@code 'Prog:'}, {@code 'Time:'},
     * {@code 'Add User'}, {@code 'First Name:'}, {@code 'Last Name:'}, {@code 'User ID:'},
     * {@code '(8 Char)'} twice, {@code 'Password:'}, {@code 'User Type: '},
     * {@code '(A=Admin, U=User)'}, the function-key legend of line 159) plus the two {@code LENGTH=0}
     * spacers at lines 89 and 102.
     */
    private static final int DFHMDF_TOTAL = 28;

    /**
     * The twelve name-labelled {@code DFHMDF} labels of {@code app/bms/COUSR01.bms}, in map order -
     * what {@code grep -E '^[A-Z0-9]+ +DFHMDF' | awk '{print $1}'} prints.
     */
    private static final List<String> DFHMDF_LABELS = List.of("TRNNAME",
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

    /** The twelve {@code xxxI} items of {@code 01 COUSR1AI}, in copybook order. */
    private static final List<String> XXXI_ITEMS = List.of("TRNNAMEI",
            "TITLE01I",
            "CURDATEI",
            "PGMNAMEI",
            "TITLE02I",
            "CURTIMEI",
            "FNAMEI",
            "LNAMEI",
            "USERIDI",
            "PASSWDI",
            "USRTYPEI",
            "ERRMSGI");

    /** The twelve {@code xxxO} items of {@code 01 COUSR1AO}, in copybook order. */
    private static final List<String> XXXO_ITEMS = List.of("TRNNAMEO",
            "TITLE01O",
            "CURDATEO",
            "PGMNAMEO",
            "TITLE02O",
            "CURTIMEO",
            "FNAMEO",
            "LNAMEO",
            "USERIDO",
            "PASSWDO",
            "USRTYPEO",
            "ERRMSGO");

    /** The twelve Java members that project those items, in record-component order. */
    private static final List<String> MAP_MEMBERS = List.of("trnName",
            "title01",
            "curDate",
            "pgmName",
            "title02",
            "curTime",
            "fName",
            "lName",
            "userId",
            "passwd",
            "usrType",
            "errMsg");

    /**
     * The declared {@code PICTURE} width of each of the twelve items, in the same order. Read off the
     * {@code xxxO} clauses at {@code COUSR01.CPY} lines 98, 104, 110, 116, 122, 128, 134, 140, 146,
     * 152, 158 and 164, and equal field for field to the {@code DFHMDF LENGTH=} operands.
     */
    private static final List<Integer> DECLARED_WIDTHS = List.of(4, 40, 8, 8, 40, 8, 20, 20, 8, 8, 1, 78);

    /** The four members carrying the stateless navigation contract that gates G37 and G40 require. */
    private static final List<String> NAV_MEMBERS =
            List.of("navigationContext", "nextProgram", "nextMapset", "nextMap");

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
        return MAP_MEMBERS.contains(member) ? member.toLowerCase(Locale.ROOT) : member;
    }

    /**
     * {@link #wireNameOf(String)} over a list, preserving order.
     *
     * @param members the Java member names
     * @return their JSON property names
     */
    private static List<String> wireNamesOf(List<String> members) {
        return members.stream().map(UserAddResponseTest::wireNameOf).toList();
    }

    // -------------------------------------------------------------------------------------------------
    // The two seven-byte control prefixes. These are the reason the overlay balances, so each component
    // is named separately rather than collapsed into the total.
    // -------------------------------------------------------------------------------------------------

    /** {@code 02 FILLER PIC X(12)} - the {@code TIOAPFX=YES} prefix, {@code COUSR01.CPY:18} and :92. */
    private static final int TIOAPFX_PREFIX_LENGTH = 12;

    /** {@code 02 xxxL COMP PIC S9(4)} - a binary halfword on the input side. */
    private static final int LENGTH_ITEM_LENGTH = 2;

    /** {@code 02 xxxF PICTURE X} - one byte, which {@code 03 xxxA} then redefines without widening. */
    private static final int FLAG_ITEM_LENGTH = 1;

    /** {@code 02 FILLER PICTURE X(4)} - the input side's reserved tail before {@code xxxI}. */
    private static final int INPUT_RESERVED_LENGTH = 4;

    /** {@code 02 FILLER PICTURE X(3)} - the output side's reserved head before {@code xxxC}. */
    private static final int OUTPUT_RESERVED_LENGTH = 3;

    /** {@code xxxC}, {@code xxxP}, {@code xxxH}, {@code xxxV} - one byte each, {@code EXTATT=YES}. */
    private static final int ATTRIBUTE_ITEM_LENGTH = 1;

    /** The four single-byte attribute items the output view declares per field. */
    private static final int ATTRIBUTE_ITEM_COUNT = 4;

    /**
     * Bytes of control items before each data item, <strong>identical on both sides</strong>: the input
     * view spends {@code 2 + 1 + 4} and the output view spends {@code 3 + 1 + 1 + 1 + 1}. This one
     * equality is what makes {@code 01 COUSR1AO REDEFINES COUSR1AI} align field for field.
     */
    private static final int FIELD_PREFIX_LENGTH = 7;

    /** Sum of the twelve declared widths: {@code 4+40+8+8+40+8+20+20+8+8+1+78}. */
    private static final int PAYLOAD_WIDTH_TOTAL = 243;

    /**
     * The width of the symbolic map in either view:
     * {@code 12 + 12 x 7 + 243}. Derived by hand from {@code app/cpy-bms/COUSR01.CPY} and asserted
     * against the sum of the transcribed parts in {@link SymbolicMapOverlay}, so a single mistyped
     * width fails rather than silently shifting every offset after it.
     */
    private static final int SYMBOLIC_MAP_LENGTH = 339;

    // -------------------------------------------------------------------------------------------------
    // Screen identity and the header literals, transcribed from WORKING-STORAGE and the EXEC operands.
    // -------------------------------------------------------------------------------------------------

    /** {@code WS-TRANID PIC X(04) VALUE 'CU01'}, {@code COUSR01C.cbl:37}. */
    private static final String WS_TRANID = "CU01";

    /** {@code WS-PGMNAME PIC X(08) VALUE 'COUSR01C'}, {@code COUSR01C.cbl:36}. */
    private static final String WS_PGMNAME = "COUSR01C";

    /** {@code MAP('COUSR1A')}, {@code COUSR01C.cbl:191} and :204; {@code COUSR1A DFHMDI} at bms:26. */
    private static final String MAP = "COUSR1A";

    /** {@code MAPSET('COUSR01')}, {@code COUSR01C.cbl:192} and :205; {@code COUSR01 DFHMSD} at bms:19. */
    private static final String MAPSET = "COUSR01";

    /** {@code WS-MESSAGE PIC X(80)}, {@code COUSR01C.cbl:38} - the sender narrowed at line 188. */
    private static final int WS_MESSAGE_LENGTH = 80;

    /** {@code MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM}, {@code COUSR01C.cbl:79} and :168. */
    private static final String SIGN_ON_PROGRAM = "COSGN00C";

    /** {@code MOVE 'COADM01C' TO CDEMO-TO-PROGRAM} on {@code DFHPF3}, {@code COUSR01C.cbl:94}. */
    private static final String ADMIN_MENU_PROGRAM = "COADM01C";

    // -------------------------------------------------------------------------------------------------
    // The five widths CSUSR01Y declares for the data this screen collects, app/cpy/CSUSR01Y.cpy:18-22.
    // -------------------------------------------------------------------------------------------------

    /** {@code SEC-USR-ID PIC X(08)}, line 18 - declared first in the record, ninth on the screen. */
    private static final int SEC_USR_ID_LENGTH = 8;

    /** {@code SEC-USR-FNAME PIC X(20)}, line 19. */
    private static final int SEC_USR_FNAME_LENGTH = 20;

    /** {@code SEC-USR-LNAME PIC X(20)}, line 20. */
    private static final int SEC_USR_LNAME_LENGTH = 20;

    /** {@code SEC-USR-PWD PIC X(08)}, line 21 - plaintext, by parity. */
    private static final int SEC_USR_PWD_LENGTH = 8;

    /** {@code SEC-USR-TYPE PIC X(01)}, line 22 - {@code 'A'} or {@code 'U'}. */
    private static final int SEC_USR_TYPE_LENGTH = 1;

    // -------------------------------------------------------------------------------------------------
    // Every message text this screen can display, transcribed verbatim including the trailing dots.
    // The five blank-field texts are in the EVALUATE order of COUSR01C.cbl:117-151, which is the order
    // that decides which one a request with several blanks produces.
    // -------------------------------------------------------------------------------------------------

    /** Predicate at {@code COUSR01C.cbl:118}, message at 120-121. */
    private static final String MSG_FIRST_NAME_EMPTY = "First Name can NOT be empty...";

    /** Predicate at {@code COUSR01C.cbl:124}, message at 126-127. */
    private static final String MSG_LAST_NAME_EMPTY = "Last Name can NOT be empty...";

    /** Predicate at {@code COUSR01C.cbl:130}, message at 132-133. */
    private static final String MSG_USER_ID_EMPTY = "User ID can NOT be empty...";

    /** Predicate at {@code COUSR01C.cbl:136}, message at 138-139. */
    private static final String MSG_PASSWORD_EMPTY = "Password can NOT be empty...";

    /** Predicate at {@code COUSR01C.cbl:142}, message at 144-145. */
    private static final String MSG_USER_TYPE_EMPTY = "User Type can NOT be empty...";

    /** Shared {@code DFHRESP(DUPKEY)} / {@code DFHRESP(DUPREC)} arm, {@code COUSR01C.cbl:260-264}. */
    private static final String MSG_DUPLICATE = "User ID already exist...";

    /** The {@code WHEN OTHER} arm of the write, {@code COUSR01C.cbl:267-271}. */
    private static final String MSG_UNABLE_TO_ADD = "Unable to Add User...";

    /** First operand of the success {@code STRING}, {@code DELIMITED BY SIZE}, line 255. */
    private static final String SUCCESS_PREFIX = "User ";

    /** Third operand of the success {@code STRING}, {@code DELIMITED BY SIZE}, line 257. */
    private static final String SUCCESS_SUFFIX = " has been added ...";

    /** The five blank-field messages in {@code EVALUATE} order - the order is the contract. */
    private static final List<String> ORDERED_BLANK_FIELD_MESSAGES = List.of(MSG_FIRST_NAME_EMPTY,
            MSG_LAST_NAME_EMPTY,
            MSG_USER_ID_EMPTY,
            MSG_PASSWORD_EMPTY,
            MSG_USER_TYPE_EMPTY);

    // -------------------------------------------------------------------------------------------------
    // Spellings that must never appear where they would signal a change of behaviour.
    // -------------------------------------------------------------------------------------------------

    /** Credential spellings that must not appear in a diagnostic rendering of this payload. */
    private static final List<String> CREDENTIAL_MARKERS =
            List.of("PasswordEncoder", "BCrypt", "MessageDigest", "Hash", "Digest", "Cipher",
                    "SecretKey", "Encrypt", "Jwt", "org.springframework.security");

    /** Security types whose mere presence on the classpath would contradict the plan's exclusions. */
    private static final List<String> EXCLUDED_SECURITY_TYPES =
            List.of("org.springframework.security.crypto.password.PasswordEncoder",
                    "org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder",
                    "org.springframework.security.core.Authentication");

    /** The three input-side control suffixes: length item, flag byte, attribute view of the flag. */
    private static final List<String> INPUT_CONTROL_SUFFIXES = List.of("L", "F", "A");

    /** The four output-side {@code EXTATT=YES} control suffixes: colour, symbols, highlight, validation. */
    private static final List<String> OUTPUT_CONTROL_SUFFIXES = List.of("C", "P", "H", "V");

    /**
     * Named, addressable items per field in the combined layout of section 1: {@code xxxF},
     * {@code xxxA} and {@code xxxI} on the input side, {@code xxxC}, {@code xxxP}, {@code xxxH},
     * {@code xxxV} and {@code xxxO} on the output side. {@code xxxL} is not among them - it is a
     * {@code COMP} binary halfword and is modelled as reserved storage rather than mis-transcribed as a
     * zoned display field.
     */
    private static final int NAMED_ITEMS_PER_FIELD = 8;

    // =================================================================================================
    // 1. THE SYMBOLIC MAP AS ONE LAYOUT CARRYING BOTH VIEWS.
    //
    // 01 COUSR1AI is the storage; 01 COUSR1AO REDEFINES it. Modelling both in a single RecordLayout is
    // the whole point: FixedWidthRecord.RecordLayout refuses a gap, refuses an unintended overlap, and
    // refuses an overlay that reaches past the storage declared ahead of it, so class initialisation
    // itself proves the geometry before any @Test runs. Two independent layouts would prove nothing
    // about how they relate.
    //
    // The output-side attribute items are declared as overlays too, which surfaces a fact easy to state
    // wrongly: the two seven-byte prefixes have the same LENGTH but not the same SHAPE. Input spends
    // 2 + 1 + 4; output spends 3 + 1 + 1 + 1 + 1. So xxxF sits at prefix+2 while xxxC sits at
    // prefix+3 - the attribute items do NOT correspond to each other. Only the data items align, and
    // they align exactly.
    // =================================================================================================

    /**
     * Both views of the {@value #SYMBOLIC_MAP_LENGTH}-byte symbolic map, built once.
     *
     * <p>Storage spans, in declaration order: the {@code TIOAPFX} filler, then per field the
     * {@code xxxL} halfword as reserved storage, the {@code xxxF} flag byte with {@code xxxA} overlaid
     * on it, the four-byte input filler, and the {@code xxxI} data item. Overlay spans, appended after
     * every byte of storage has been declared: per field the four attribute items of the output view
     * and the {@code xxxO} data item, each at the offset the copybook puts it at.
     */
    private static final FixedWidthRecord.RecordLayout SYMBOLIC_MAP_LAYOUT = symbolicMapLayout();

    /**
     * The absolute offset of each data item, index for index with {@link #DECLARED_WIDTHS} - and the
     * same for both views, which is the property under test. Computed by the same walk that builds the
     * layout, then checked against transcribed literals in {@link SymbolicMapOverlay}.
     */
    private static final List<Integer> DATA_OFFSETS = dataOffsets();

    private static FixedWidthRecord.RecordLayout symbolicMapLayout() {
        List<FixedWidthRecord.FieldSpan> spans = new ArrayList<>();
        // 02 FILLER PIC X(12) - COUSR01.CPY:18 on the input side, :92 on the output side. One span,
        // because both views declare the same twelve bytes at offset 0.
        spans.add(FixedWidthRecord.FieldSpan.filler(0, TIOAPFX_PREFIX_LENGTH));
        int cursor = TIOAPFX_PREFIX_LENGTH;
        List<FixedWidthRecord.FieldSpan> overlays = new ArrayList<>();
        for (int index = 0; index < DFHMDF_NAMED; index++) {
            String label = DFHMDF_LABELS.get(index);
            int width = DECLARED_WIDTHS.get(index);
            int prefix = cursor;

            // ---- 01 COUSR1AI: the storage ------------------------------------------------------
            // 02 xxxL COMP PIC S9(4). A binary halfword, so it is declared as reserved storage rather
            // than as a numeric span: nothing in this file reads it, and modelling a COMP field as
            // zoned display would be a transcription error dressed up as coverage.
            spans.add(FixedWidthRecord.FieldSpan.filler(prefix, LENGTH_ITEM_LENGTH));
            // 02 xxxF PICTURE X, then 02 FILLER REDEFINES xxxF / 03 xxxA PICTURE X over that one byte.
            FixedWidthRecord.FieldSpan flag = FixedWidthRecord.FieldSpan.alphanumeric(
                    label + "F", prefix + LENGTH_ITEM_LENGTH, FLAG_ITEM_LENGTH);
            spans.add(flag);
            spans.add(flag.redefinedAs(label + "A", FixedWidthRecord.PictureKind.ALPHANUMERIC));
            // 02 FILLER PICTURE X(4).
            spans.add(FixedWidthRecord.FieldSpan.filler(
                    prefix + LENGTH_ITEM_LENGTH + FLAG_ITEM_LENGTH, INPUT_RESERVED_LENGTH));
            // 02 xxxI PIC X(n).
            int dataOffset = prefix + FIELD_PREFIX_LENGTH;
            spans.add(FixedWidthRecord.FieldSpan.alphanumeric(
                    XXXI_ITEMS.get(index), dataOffset, width));

            // ---- 01 COUSR1AO REDEFINES COUSR1AI: the overlay ------------------------------------
            // The three-byte output filler is the same storage the input view's xxxL and xxxF occupy,
            // so it is not declared again - declaring it would be a second claim on bytes already
            // accounted for. The four attribute items and the data item are declared as overlays.
            for (int attribute = 0; attribute < ATTRIBUTE_ITEM_COUNT; attribute++) {
                overlays.add(FixedWidthRecord.FieldSpan.redefining(
                        label + OUTPUT_CONTROL_SUFFIXES.get(attribute),
                        prefix + OUTPUT_RESERVED_LENGTH + attribute,
                        ATTRIBUTE_ITEM_LENGTH,
                        FixedWidthRecord.PictureKind.ALPHANUMERIC));
            }
            // 02 xxxO PIC X(n) - at the identical offset as xxxI, which is the contract being proved.
            overlays.add(FixedWidthRecord.FieldSpan.redefining(
                    XXXO_ITEMS.get(index), dataOffset, width,
                    FixedWidthRecord.PictureKind.ALPHANUMERIC));

            cursor = dataOffset + width;
        }
        spans.addAll(overlays);
        // The DECLARED total is passed, never the cursor this walk happened to reach. Passing the
        // cursor would make the layout self-consistent with whatever the constants above sum to and
        // would catch nothing; passing SYMBOLIC_MAP_LENGTH makes RecordLayout compare the transcribed
        // geometry against the transcribed total.
        return FixedWidthRecord.RecordLayout.of(SYMBOLIC_MAP_LENGTH,
                spans.toArray(FixedWidthRecord.FieldSpan[]::new));
    }

    private static List<Integer> dataOffsets() {
        List<Integer> offsets = new ArrayList<>(DFHMDF_NAMED);
        int cursor = TIOAPFX_PREFIX_LENGTH;
        for (int index = 0; index < DFHMDF_NAMED; index++) {
            offsets.add(cursor + FIELD_PREFIX_LENGTH);
            cursor += FIELD_PREFIX_LENGTH + DECLARED_WIDTHS.get(index);
        }
        return List.copyOf(offsets);
    }

    // =================================================================================================
    // 2. SHARED, STATELESS HELPERS. Every one returns a fresh value; none caches, mutates or memoises.
    // =================================================================================================

    /**
     * A codec over the explicitly named code page (B8). A fresh instance per call: the codec is cheap,
     * and holding one in a static field would be shared state for no benefit (B9).
     */
    private static FixedWidthCodec codec() {
        return new FixedWidthCodec(MAP_CHARSET);
    }

    /** {@code n} spaces - the COBOL {@code SPACES} figurative constant at a declared width. */
    private static String spaces(int width) {
        return " ".repeat(width);
    }

    /**
     * {@code n} bytes of {@code LOW-VALUES}. COBOL's {@code LOW-VALUES} is the lowest character in the
     * collating sequence, which for a single-byte code page is {@code X'00'}. The predicate at
     * {@code COUSR01C.cbl:167} accepts this form <em>as well as</em> {@code SPACES}, and the two are
     * different byte images - which is exactly why the source names both.
     */
    private static String lowValues(int width) {
        return "\u0000".repeat(width);
    }

    /** The names of the record's components, in declaration order. */
    private static List<String> componentNames() {
        return Arrays.stream(UserAddResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    /** The twelve map-derived members of a response, in component order. */
    private static List<String> mapValuesOf(UserAddResponse response) {
        return Arrays.asList(response.trnName(), response.title01(), response.curDate(),
                response.pgmName(), response.title02(), response.curTime(), response.fName(),
                response.lName(), response.userId(), response.passwd(), response.usrType(),
                response.errMsg());
    }

    /** Twelve values, one per member, each blank at its own declared width. */
    private static List<String> blankMapValues() {
        List<String> values = new ArrayList<>(DFHMDF_NAMED);
        for (int index = 0; index < DFHMDF_NAMED; index++) {
            values.add(spaces(DECLARED_WIDTHS.get(index)));
        }
        return values;
    }

    /** The same twelve values with one member replaced, so a case states only what it changes. */
    private static List<String> withMember(List<String> values, String member, String value) {
        List<String> replaced = new ArrayList<>(values);
        replaced.set(MAP_MEMBERS.indexOf(member), value);
        return replaced;
    }

    /** Builds a response from the twelve map values in component order, plus the navigation contract. */
    private static UserAddResponse responseOf(List<String> mapValues, NavigationContext context,
            String nextProgram, String nextMapset, String nextMap) {
        return new UserAddResponse(mapValues.get(0), mapValues.get(1), mapValues.get(2),
                mapValues.get(3), mapValues.get(4), mapValues.get(5), mapValues.get(6),
                mapValues.get(7), mapValues.get(8), mapValues.get(9), mapValues.get(10),
                mapValues.get(11), context, nextProgram, nextMapset, nextMap);
    }

    /** A blank response carrying an empty commarea and this screen's own map and mapset. */
    private static UserAddResponse blankResponse() {
        return responseOf(blankMapValues(), NavigationContext.empty(), spaces(8), MAPSET, MAP);
    }

    /**
     * The screen as {@code SEND-USRADD-SCREEN} leaves it: the header of
     * {@code POPULATE-HEADER-INFO} at {@code COUSR01C.cbl:214-233}, the five data fields blank as
     * {@code INITIALIZE-ALL-FIELDS} leaves them at 289-295, and one message narrowed into
     * {@code ERRMSGO} by line 188.
     */
    private static UserAddResponse sentScreen(String wsMessage) {
        DateHeader header = header();
        List<String> values = blankMapValues();
        values = withMember(values, "trnName", WS_TRANID);
        values = withMember(values, "title01", ScreenTitles.CCDA_TITLE01);
        values = withMember(values, "curDate", header.wsCurdateMmDdYy());
        values = withMember(values, "pgmName", WS_PGMNAME);
        values = withMember(values, "title02", ScreenTitles.CCDA_TITLE02);
        values = withMember(values, "curTime", header.wsCurtimeHhMmSs());
        values = withMember(values, "errMsg", narrowToErrMsg(wsMessage));
        return responseOf(values, NavigationContext.empty(), spaces(8), MAPSET, MAP);
    }

    /**
     * The date and time header this screen paints, driven from a fixed instant (B7). The instant is the
     * one stamped in the version footer of {@code app/bms/COUSR01.bms:163}, so the expected images are
     * traceable to the sources rather than invented.
     */
    private static DateHeader header() {
        return DateHeader.from(codec(),
                Clock.fixed(Instant.parse("2022-08-22T17:02:44Z"), ZoneOffset.UTC));
    }

    /**
     * {@code MOVE <text> TO WS-MESSAGE} - the {@value #WS_MESSAGE_LENGTH}-byte image every one of this
     * screen's messages occupies before it reaches the map. A COBOL alphanumeric {@code MOVE} into a
     * wider receiver pads on the right, and {@link FixedWidthCodec#movePicX(String, int)} is where that
     * rule lives (B11).
     */
    private static String wsMessageImage(String text) {
        return codec().movePicX(text, WS_MESSAGE_LENGTH);
    }

    /**
     * {@code MOVE WS-MESSAGE TO ERRMSGO OF COUSR1AO} - {@code COUSR01C.cbl:188}, the narrowing from
     * {@value #WS_MESSAGE_LENGTH} to {@value UserAddResponse#ERR_MSG_LENGTH}. Two characters are lost
     * from the right, because that is what a COBOL alphanumeric {@code MOVE} into a narrower receiver
     * does. Routed through the codec rather than a substring so the direction of the loss is a
     * reviewable decision (B11).
     */
    private static String narrowToErrMsg(String text) {
        return codec().movePicX(wsMessageImage(text), UserAddResponse.ERR_MSG_LENGTH);
    }

    /**
     * The success text of {@code COUSR01C.cbl:255-258}:
     * {@code STRING 'User ' DELIMITED BY SIZE / SEC-USR-ID DELIMITED BY SPACE / ' has been added ...'
     * DELIMITED BY SIZE INTO WS-MESSAGE}.
     *
     * <p>{@code DELIMITED BY SPACE} stops the middle operand at its first space, so an identifier
     * shorter than {@value #SEC_USR_ID_LENGTH} characters contributes only its own characters and the
     * composed text is <em>variable</em> in length. The two {@code DELIMITED BY SIZE} operands
     * contribute their full declared width, and the concatenation itself goes through the codec.
     *
     * <p>The one {@link String#substring(int, int)} below is <em>not</em> a stand-in for a COBOL
     * {@code MOVE} - practice B11 forbids that and every {@code MOVE} in this file goes through
     * {@link FixedWidthCodec#movePicX(String, int)}. It implements the {@code DELIMITED BY SPACE}
     * delimiter of the {@code STRING} verb, which is a different operation with no codec equivalent:
     * taking the sending characters up to, but not including, the first space.
     */
    private static String composedSuccessMessage(String secUsrId) {
        int firstSpace = secUsrId.indexOf(' ');
        String delimitedBySpace = firstSpace < 0 ? secUsrId : secUsrId.substring(0, firstSpace);
        return codec().concatenateDelimitedBySize(SUCCESS_PREFIX, delimitedBySpace, SUCCESS_SUFFIX);
    }

    /**
     * An {@link ObjectMapper} configured exactly as {@code config.WebConfig} configures the shared one:
     * property names untransformed, {@code USE_BIG_DECIMAL_FOR_FLOATS} and
     * {@code WRITE_BIGDECIMAL_AS_PLAIN} on, {@code FAIL_ON_TRAILING_TOKENS} on, and
     * {@code ACCEPT_EMPTY_STRING_AS_NULL_OBJECT} off.
     *
     * <p>A default mapper would silently break this payload. Space padding is meaning here, not
     * whitespace: a mapper that trimmed, that coerced an all-space value to {@code null}, or that
     * excluded an empty one would turn the eight spaces of {@link UserAddResponse#passwd()}'s only
     * legitimate value into something the screen never showed - and would defeat the blank-password case
     * in {@link PasswordDeclaredButNeverEchoed} without failing anything else.
     */
    private static ObjectMapper webConfigEquivalentMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        mapper.configure(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN, true);
        mapper.disable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT);
        return mapper;
    }

    private static String serialise(UserAddResponse response) {
        try {
            return webConfigEquivalentMapper().writeValueAsString(response);
        } catch (IOException failure) {
            throw new UncheckedIOException("Serialising a UserAddResponse must not fail", failure);
        }
    }

    private static UserAddResponse deserialise(String json) {
        try {
            return webConfigEquivalentMapper().readValue(json, UserAddResponse.class);
        } catch (IOException failure) {
            throw new UncheckedIOException("Deserialising a UserAddResponse must not fail", failure);
        }
    }

    private static Map<String, Object> jsonKeys(UserAddResponse response) {
        try {
            return webConfigEquivalentMapper()
                    .readValue(serialise(response), new TypeReference<Map<String, Object>>() { });
        } catch (IOException failure) {
            throw new UncheckedIOException("Reading a serialised UserAddResponse must not fail",
                    failure);
        }
    }

    /** The sixteen property names the wire form must carry: the twelve map members plus the four nav. */
    private static Set<String> expectedJsonMembers() {
        Set<String> members = new LinkedHashSet<>(wireNamesOf(MAP_MEMBERS));
        members.addAll(NAV_MEMBERS);
        return Set.copyOf(members);
    }

    /**
     * Every type name reachable from the declaration of {@link UserAddResponse}: its components, its
     * public methods' returns and parameters, and its declared fields. Enough to show that no hashing,
     * encoding or security type has been introduced anywhere in the type's surface.
     */
    private static Set<String> reachableTypeNames() {
        Set<String> names = new LinkedHashSet<>();
        for (RecordComponent component : UserAddResponse.class.getRecordComponents()) {
            names.add(component.getType().getName());
        }
        for (Method method : UserAddResponse.class.getDeclaredMethods()) {
            names.add(method.getReturnType().getName());
            for (Class<?> parameter : method.getParameterTypes()) {
                names.add(parameter.getName());
            }
        }
        for (Field field : UserAddResponse.class.getDeclaredFields()) {
            names.add(field.getType().getName());
        }
        return Set.copyOf(names);
    }

    // =================================================================================================
    // 3. THE MAP PROJECTION (gate G9).
    //
    // Three independent counts have to agree on twelve: the name-labelled DFHMDF definitions, the xxxO
    // items of the output view, and the map-derived record components. If any one of them drifts, the
    // field-for-field diff that the whole migration is gated on stops being able to see a difference.
    // =================================================================================================

    @Nested
    @DisplayName("Map projection - twelve members, in COUSR1AO order, at declared widths")
    class MapProjection {

        @Test
        @DisplayName("28 DFHMDF definitions, 12 of them labelled, and 12 payload members")
        void theTwentyEightVersusTwelveSplit() {
            // app/bms/COUSR01.bms declares 28 fields; 16 carry no label, so they have no symbolic-map
            // item and cannot become payload members. An unlabelled DFHMDF is screen furniture: the
            // 'Tran:' and 'Date:' captions, the 'Add User' banner, the two '(8 Char)' hints, the
            // '(A=Admin, U=User)' legend, the function-key line, and the two LENGTH=0 spacers.
            assertThat(DFHMDF_TOTAL - DFHMDF_LABELS.size())
                    .as("16 of the 28 DFHMDF definitions are unlabelled screen literals")
                    .isEqualTo(16);
            assertThat(DFHMDF_LABELS).hasSize(DFHMDF_NAMED);
            assertThat(XXXI_ITEMS).hasSize(DFHMDF_NAMED);
            assertThat(XXXO_ITEMS).hasSize(DFHMDF_NAMED);
            assertThat(MAP_MEMBERS).hasSize(DFHMDF_NAMED);
            assertThat(DECLARED_WIDTHS).hasSize(DFHMDF_NAMED);
            assertThat(UserAddResponse.MAP_DERIVED_FIELD_COUNT).isEqualTo(DFHMDF_NAMED);
        }

        @Test
        @DisplayName("the record declares 16 components: 12 map-derived then 4 navigation")
        void componentCountAndOrder() {
            assertThat(componentNames())
                    .as("twelve map-derived members and the four the stateless contract adds")
                    .hasSize(DFHMDF_NAMED + NAV_MEMBERS.size());
            assertThat(componentNames().subList(0, DFHMDF_NAMED))
                    .as("the map-derived members come first, in COUSR1AO declaration order")
                    .containsExactlyElementsOf(MAP_MEMBERS);
            assertThat(componentNames().subList(DFHMDF_NAMED, componentNames().size()))
                    .as("then the navigation contract, in its declared order")
                    .containsExactlyElementsOf(NAV_MEMBERS);
        }

        @Test
        @DisplayName("each member's xxxO item is its DFHMDF label with the output suffix appended")
        void everyMemberTracesToADfhmdfDefinition() {
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                String label = DFHMDF_LABELS.get(index);
                assertThat(XXXO_ITEMS.get(index))
                        .as("the output item of DFHMDF %s", label)
                        .isEqualTo(label + FieldAttributeSetter.OUTPUT_ITEM_SUFFIX);
                assertThat(XXXI_ITEMS.get(index))
                        .as("and its input partner differs only in the final letter")
                        .isEqualTo(label + "I");
            }
            assertThat(FieldAttributeSetter.OUTPUT_ITEM_SUFFIX)
                    .as("common.FieldAttributeSetter names the suffix the copybook uses")
                    .isEqualTo("O");
        }

        @Test
        @DisplayName("the type publishes the same twelve item names, labels and widths")
        void thePublishedListsMatchTheTranscription() {
            assertThat(UserAddResponse.MAP_DERIVED_FIELD_NAMES)
                    .as("the xxxO item names, verbatim as COUSR01.CPY spells them")
                    .containsExactlyElementsOf(XXXO_ITEMS);
            assertThat(UserAddResponse.DFHMDF_FIELD_NAMES)
                    .as("the name-labelled DFHMDF labels, in mapset order")
                    .containsExactlyElementsOf(DFHMDF_LABELS);
            assertThat(UserAddResponse.MAP_DERIVED_FIELD_LENGTHS)
                    .as("and the declared PICTURE widths, index for index")
                    .containsExactlyElementsOf(DECLARED_WIDTHS);
        }

        @ParameterizedTest(name = "[{index}] {0} is PIC X({1})")
        @CsvSource({"TRNNAMEO,4", "TITLE01O,40", "CURDATEO,8", "PGMNAMEO,8", "TITLE02O,40",
                    "CURTIMEO,8", "FNAMEO,20", "LNAMEO,20", "USERIDO,8", "PASSWDO,8", "USRTYPEO,1",
                    "ERRMSGO,78"})
        @DisplayName("every declared width is the one its PICTURE clause states")
        void eachDeclaredWidth(String item, int width) {
            int index = XXXO_ITEMS.indexOf(item);
            assertThat(index).as("%s is one of the twelve output items", item).isNotNegative();
            assertThat(DECLARED_WIDTHS.get(index)).isEqualTo(width);
            assertThat(UserAddResponse.MAP_DERIVED_FIELD_LENGTHS.get(index))
                    .as("the type publishes the same width for %s", item)
                    .isEqualTo(width);
        }

        @Test
        @DisplayName("every member is a String: a screen field is characters, never a typed value")
        void everyMapMemberIsAString() {
            RecordComponent[] components = UserAddResponse.class.getRecordComponents();
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                assertThat(components[index].getType())
                        .as("%s projects PIC X(%d) and so is a String",
                                MAP_MEMBERS.get(index), DECLARED_WIDTHS.get(index))
                        .isEqualTo(String.class);
            }
            // Gate G22: no double and no float anywhere. There is no numeric member at all on this
            // screen - every field of COUSR01 is PIC X - so the gate is met by construction here, and
            // the assertion records that rather than leaving it to be assumed.
            for (RecordComponent component : components) {
                assertThat(component.getType())
                        .as("%s must not be a binary floating-point type", component.getName())
                        .isNotEqualTo(double.class)
                        .isNotEqualTo(float.class)
                        .isNotEqualTo(Double.class)
                        .isNotEqualTo(Float.class);
            }
        }

        @Test
        @DisplayName("the screen identity is CU01 / COUSR01C / COUSR1A of COUSR01")
        void theScreenIdentity() {
            assertThat(UserAddResponse.TRANSACTION_ID)
                    .as("WS-TRANID at COUSR01C.cbl:37, and TRANSACTION(CU01) at CARDDEMO.CSD:459")
                    .isEqualTo(WS_TRANID)
                    .hasSize(UserAddResponse.TRN_NAME_LENGTH);
            assertThat(UserAddResponse.PROGRAM_NAME)
                    .as("WS-PGMNAME at COUSR01C.cbl:36, and PROGRAM(COUSR01C) at CARDDEMO.CSD:460")
                    .isEqualTo(WS_PGMNAME)
                    .hasSize(UserAddResponse.PGM_NAME_LENGTH);
            assertThat(UserAddResponse.MAP_NAME)
                    .as("MAP('COUSR1A') at COUSR01C.cbl:191")
                    .isEqualTo(MAP);
            assertThat(UserAddResponse.MAPSET_NAME)
                    .as("MAPSET('COUSR01') at COUSR01C.cbl:192")
                    .isEqualTo(MAPSET);
            // The map name is seven characters, not eight, because the symbolic-map group names are
            // formed by appending a direction letter: COUSR1A + I and COUSR1A + O. The eighth character
            // belongs to that suffix, which is also why CDEMO-LAST-MAP is PIC X(7).
            assertThat(MAP + FieldAttributeSetter.OUTPUT_MAP_SUFFIX).isEqualTo("COUSR1AO");
            assertThat(MAP.length()).isEqualTo(NavigationContext.LAST_MAP_LENGTH).isEqualTo(7);
            assertThat(MAPSET.length()).isEqualTo(NavigationContext.LAST_MAPSET_LENGTH).isEqualTo(7);
        }

        @Test
        @DisplayName("a response carries what it was handed: no padding, no trimming, no validation")
        void thePayloadIsAPassiveCarrier() {
            // The declared type has no compact constructor and no annotation. That is deliberate: the
            // 80-to-78 narrowing at COUSR01C.cbl:188 is real behaviour and belongs to the controller,
            // which performs it explicitly through the codec. A second, silent truncation here would
            // hide the first, and a presence constraint would replace a message the user is meant to
            // read - one of the five texts of COUSR01C.cbl:117-151 - with a rejection they are not.
            String shorter = "Jo";
            String longer = "A".repeat(UserAddResponse.F_NAME_LENGTH + 5);
            UserAddResponse response = responseOf(
                    withMember(withMember(blankMapValues(), "fName", shorter), "lName", longer),
                    NavigationContext.empty(), spaces(8), MAPSET, MAP);
            assertThat(response.fName())
                    .as("a short value is neither padded to PIC X(20) nor rejected")
                    .isEqualTo(shorter)
                    .hasSize(2);
            assertThat(response.lName())
                    .as("and an over-wide value is neither truncated nor rejected by the payload")
                    .isEqualTo(longer)
                    .hasSize(UserAddResponse.L_NAME_LENGTH + 5);
        }

        @Test
        @DisplayName("no jakarta.validation constraint is declared on a response member")
        void noValidationConstraintIsDeclared() {
            for (RecordComponent component : UserAddResponse.class.getRecordComponents()) {
                for (Annotation annotation : component.getAnnotations()) {
                    assertThat(annotation.annotationType().getName())
                            .as("%s carries %s; a response is not validated, and a presence "
                                    + "constraint here would change observable behaviour",
                                    component.getName(), annotation.annotationType().getName())
                            .doesNotStartWith("jakarta.validation");
                }
                for (Annotation annotation : component.getAccessor().getAnnotations()) {
                    assertThat(annotation.annotationType().getName())
                            .as("the accessor for %s carries %s", component.getName(),
                                    annotation.annotationType().getName())
                            .doesNotStartWith("jakarta.validation");
                }
            }
        }
    }

    // =================================================================================================
    // 4. CROSS-SCREEN TRAPS.
    //
    // Four details of this screen differ from a neighbouring screen that looks almost identical. Each
    // one is a place where copying a sibling's shape would compile, pass a naive test and produce a
    // wrong byte image.
    // =================================================================================================

    @Nested
    @DisplayName("Cross-screen traps - USERID, names before id, curTime 8, errMsg 78")
    class CrossScreenTraps {

        @Test
        @DisplayName("the identity member is userId, from USERID - not usrIdIn, from USRIDIN")
        void theIdentityMemberIsNamedForUserid() {
            // app/bms/COUSR01.bms:111 labels the field USERID, so the symbolic map spells its items
            // USERIDL, USERIDF, USERIDA, USERIDI and USERIDO. The user list, user update and user
            // delete mapsets label the same logical field USRIDIN and their payloads spell the member
            // usrIdIn. Both spellings are correct for their own screen; neither is harmonised.
            assertThat(componentNames().get(MAP_MEMBERS.indexOf("userId")))
                    .isEqualTo("userId")
                    .isNotEqualTo("usrIdIn");
            assertThat(UserAddResponse.USER_ID_FIELD)
                    .as("COUSR01.CPY:146 declares USERIDO, not USRIDINO")
                    .isEqualTo("USERIDO")
                    .isNotEqualTo("USRIDINO");
            assertThat(DFHMDF_LABELS.get(MAP_MEMBERS.indexOf("userId")))
                    .isEqualTo("USERID")
                    .isNotEqualTo("USRIDIN");
        }

        @Test
        @DisplayName("the names come BEFORE the identifier, which is the inverse of COUSR02/COUSR03")
        void namesComeBeforeTheIdentifier() {
            // app/bms/COUSR01.bms places FNAME at POS=(8,18) and LNAME at (8,56) on line 8, then
            // USERID at (11,15) and PASSWD at (11,55) on line 11, then USRTYPE at (14,17). The update
            // and delete screens put their identifier field first. The order is not cosmetic: it is the
            // order of the EVALUATE at COUSR01C.cbl:117-151, so it decides which of the five messages a
            // request with several blank fields produces.
            int fName = MAP_MEMBERS.indexOf("fName");
            int lName = MAP_MEMBERS.indexOf("lName");
            int userId = MAP_MEMBERS.indexOf("userId");
            int passwd = MAP_MEMBERS.indexOf("passwd");
            int usrType = MAP_MEMBERS.indexOf("usrType");
            assertThat(fName).isLessThan(lName);
            assertThat(lName)
                    .as("both names precede the identifier on this screen")
                    .isLessThan(userId);
            assertThat(userId).isLessThan(passwd);
            assertThat(passwd).isLessThan(usrType);
            assertThat(MAP_MEMBERS.subList(fName, usrType + 1))
                    .as("the five collected fields, in this screen's own order")
                    .containsExactly("fName", "lName", "userId", "passwd", "usrType");
            assertThat(componentNames().subList(fName, usrType + 1))
                    .as("and the record declares them in exactly that order")
                    .containsExactly("fName", "lName", "userId", "passwd", "usrType");
        }

        @Test
        @DisplayName("curTime is PIC X(8) here, not the PIC X(9) of the sign-on screen")
        void curTimeIsEightNotNine() {
            // app/bms/COUSR01.bms:70-74 declares CURTIME LENGTH=8 with INITIAL='hh:mm:ss', and
            // COUSR01.CPY:128 declares CURTIMEO PIC X(8). The sign-on mapset is the one screen in the
            // application whose time field is nine characters wide; borrowing that width here would
            // shift every offset after it by one byte.
            assertThat(UserAddResponse.CUR_TIME_LENGTH).isEqualTo(8).isNotEqualTo(9);
            assertThat(DECLARED_WIDTHS.get(MAP_MEMBERS.indexOf("curTime"))).isEqualTo(8);
            assertThat(UserAddResponse.CUR_DATE_LENGTH)
                    .as("CURDATE is eight as well - mm/dd/yy, bms:47-51")
                    .isEqualTo(8);
            assertThat(header().wsCurtimeHhMmSs())
                    .as("hh:mm:ss occupies exactly the declared width")
                    .hasSize(UserAddResponse.CUR_TIME_LENGTH);
        }

        @Test
        @DisplayName("errMsg is PIC X(78) while WS-MESSAGE is PIC X(80): two characters are lost")
        void errMsgIsSeventyEightAndTheMoveTruncatesOnTheRight() {
            // COUSR01C.cbl:38 declares WS-MESSAGE PIC X(80); COUSR01.CPY:164 declares ERRMSGO PIC
            // X(78); line 188 moves the first into the second. A COBOL alphanumeric MOVE fills the
            // receiver from the left and discards the overflow, so the last two characters go. The plan
            // names MOVE the dominant parity risk of this codebase, which is why this is asserted
            // through FixedWidthCodec.movePicX rather than a substring (B11).
            assertThat(UserAddResponse.ERR_MSG_LENGTH).isEqualTo(78).isNotEqualTo(80);
            assertThat(UserAddResponse.WS_MESSAGE_LENGTH).isEqualTo(WS_MESSAGE_LENGTH).isEqualTo(80);
            assertThat(UserAddResponse.WS_MESSAGE_LENGTH - UserAddResponse.ERR_MSG_LENGTH)
                    .as("two characters, every time a message fills the sender")
                    .isEqualTo(2);

            // An 80-character sender whose final two characters are NOT spaces, so the loss is visible.
            String full = "X".repeat(UserAddResponse.ERR_MSG_LENGTH) + "YZ";
            assertThat(full).hasSize(WS_MESSAGE_LENGTH);
            String narrowed = codec().movePicX(full, UserAddResponse.ERR_MSG_LENGTH);
            assertThat(narrowed)
                    .as("the surviving characters are the leading 78")
                    .hasSize(UserAddResponse.ERR_MSG_LENGTH)
                    .isEqualTo("X".repeat(UserAddResponse.ERR_MSG_LENGTH))
                    .doesNotContain("Y")
                    .doesNotContain("Z");

            UserAddResponse response = responseOf(withMember(blankMapValues(), "errMsg", narrowed),
                    NavigationContext.empty(), spaces(8), MAPSET, MAP);
            assertThat(response.errMsg())
                    .as("and the payload carries the narrowed image, unchanged")
                    .isEqualTo(narrowed)
                    .hasSize(UserAddResponse.ERR_MSG_LENGTH);
            assertThat(deserialise(serialise(response)).errMsg()).isEqualTo(narrowed);
        }

        @Test
        @DisplayName("a message shorter than 78 loses only spaces, so the same MOVE is lossless")
        void aShortMessageLosesNothingVisible() {
            // The same narrowing, driven by a real text: MSG_DUPLICATE is 24 characters, so the two
            // characters dropped from the 80-byte image are both spaces and the visible text survives
            // whole. Both outcomes of the one rule are exercised, which is what keeps the rule honest.
            assertThat(MSG_DUPLICATE.length()).isLessThan(UserAddResponse.ERR_MSG_LENGTH);
            String image = wsMessageImage(MSG_DUPLICATE);
            assertThat(image).hasSize(WS_MESSAGE_LENGTH).startsWith(MSG_DUPLICATE);
            assertThat(image.substring(UserAddResponse.ERR_MSG_LENGTH))
                    .as("the two characters line 188 discards")
                    .isEqualTo("  ");
            assertThat(narrowToErrMsg(MSG_DUPLICATE))
                    .hasSize(UserAddResponse.ERR_MSG_LENGTH)
                    .isEqualTo(codec().movePicX(MSG_DUPLICATE, UserAddResponse.ERR_MSG_LENGTH))
                    .startsWith(MSG_DUPLICATE);
        }

        @Test
        @DisplayName("errMsg's map-declared colour is RED; DFHGREEN at line 254 is an override")
        void redIsTheDeclaredColourAndGreenIsTheOverride() {
            // app/bms/COUSR01.bms:151-154 declares ERRMSG DFHMDF ATTRB=(ASKIP,BRT,FSET) COLOR=RED
            // LENGTH=78 POS=(23,1) - identical on all five mapsets of this package. So red is what the
            // map paints unless the program says otherwise, and COUSR01C.cbl:254 says otherwise on
            // exactly one path: MOVE DFHGREEN TO ERRMSGC OF COUSR1AO, on the successful write.
            //
            // Both constants come from DFHBMSCA, which COUSR01C.cbl:56 copies. The DFHATTR include at
            // line 57 is commented out and nothing here depends on it (B4).
            assertThat(BmsAttributes.colourMnemonic(BmsAttributes.DFHRED)).isEqualTo("DFHRED");
            assertThat(BmsAttributes.colourMnemonic(BmsAttributes.DFHGREEN)).isEqualTo("DFHGREEN");
            assertThat(BmsAttributes.DFHGREEN)
                    .as("the override is a different byte from the declared colour")
                    .isNotEqualTo(BmsAttributes.DFHRED);
            // The colour is carried by ERRMSGC, an attribute item of the output view - never by the
            // errMsg payload member, which carries only the text.
            assertThat(DFHMDF_LABELS.get(MAP_MEMBERS.indexOf("errMsg"))
                    + FieldAttributeSetter.COLOUR_ITEM_SUFFIX)
                    .as("COUSR01C.cbl:254 names ERRMSGC, which COUSR01.CPY:160 declares")
                    .isEqualTo("ERRMSGC");
            assertThat(componentNames())
                    .as("and no colour item is a member of this payload")
                    .doesNotContain("errMsgC", "errMsgColour", "errMsgColor");
        }
    }

    // =================================================================================================
    // 5. THE HEADER LITERALS.
    //
    // POPULATE-HEADER-INFO at COUSR01C.cbl:214-233 writes six of the twelve fields on every send. Two
    // are title literals whose byte images must match exactly, two are the transaction and program
    // names, and two are derived from the clock - which is why a fixed Clock is the only honest way to
    // assert them (B7).
    // =================================================================================================

    @Nested
    @DisplayName("Header literals - titles at PIC X(40), identity, and a fixed clock")
    class HeaderLiterals {

        @Test
        @DisplayName("title01 and title02 are the CCDA titles, each exactly 40 characters")
        void theTitlesAreTheScreenTitleLiterals() {
            // COUSR01C.cbl:218-219 move CCDA-TITLE01 and CCDA-TITLE02 into TITLE01O and TITLE02O, both
            // PIC X(40). The literals include their padding: the text is centred by the spaces around
            // it, so trimming either one would change the rendered screen.
            assertThat(ScreenTitles.CCDA_TITLE01)
                    .hasSize(UserAddResponse.TITLE01_LENGTH)
                    .hasSize(ScreenTitles.TITLE_LENGTH)
                    .contains("AWS Mainframe Modernization")
                    .startsWith(" ")
                    .endsWith(" ");
            assertThat(ScreenTitles.CCDA_TITLE02)
                    .hasSize(UserAddResponse.TITLE02_LENGTH)
                    .contains("CardDemo")
                    .startsWith(" ")
                    .endsWith(" ");
            UserAddResponse response = sentScreen(spaces(WS_MESSAGE_LENGTH));
            assertThat(response.title01()).isEqualTo(ScreenTitles.CCDA_TITLE01);
            assertThat(response.title02()).isEqualTo(ScreenTitles.CCDA_TITLE02);
            assertThat(deserialise(serialise(response)).title01())
                    .as("the padding survives the wire, or the screen is no longer centred")
                    .isEqualTo(ScreenTitles.CCDA_TITLE01);
        }

        @Test
        @DisplayName("CCDA_THANK_YOU and CCDA_MSG_THANK_YOU are different things, and neither is used here")
        void theTwoThankYouLiteralsAreNotInterchangeable() {
            // A trap worth stating plainly: ScreenTitles.CCDA_THANK_YOU is a PIC X(40) title from
            // COTTL01Y and SystemMessages.CCDA_MSG_THANK_YOU is a PIC X(50) message from CSMSG01Y.
            // Different text, different width, different owner, different copybook.
            assertThat(ScreenTitles.CCDA_THANK_YOU).hasSize(ScreenTitles.TITLE_LENGTH).hasSize(40);
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU)
                    .hasSize(SystemMessages.MESSAGE_LENGTH)
                    .hasSize(50);
            assertThat(ScreenTitles.CCDA_THANK_YOU.strip())
                    .isNotEqualTo(SystemMessages.CCDA_MSG_THANK_YOU.strip());
            // Neither reaches this screen: grep -n 'CCDA' app/cbl/COUSR01C.cbl returns exactly three
            // hits - CCDA-MSG-INVALID-KEY at 101 and the two titles at 218-219. No thank-you text is
            // referenced by this program at all.
            assertThat(mapValuesOf(sentScreen(spaces(WS_MESSAGE_LENGTH))))
                    .doesNotContain(ScreenTitles.CCDA_THANK_YOU)
                    .doesNotContain(SystemMessages.CCDA_MSG_THANK_YOU);
        }

        @Test
        @DisplayName("the invalid-key message is the one system message this screen does use")
        void theInvalidKeyMessageFitsErrMsg() {
            // COUSR01C.cbl:98-102 is the WHEN OTHER arm of the EIBAID evaluation: any key other than
            // ENTER, PF3 or PF4 moves CCDA-MSG-INVALID-KEY into WS-MESSAGE and re-sends the screen. The
            // message is PIC X(50) and the receiver is PIC X(78), so it fits with room to spare - the
            // narrowing at line 188 discards two spaces and nothing else.
            assertThat(SystemMessages.CCDA_MSG_INVALID_KEY)
                    .hasSize(SystemMessages.MESSAGE_LENGTH)
                    .contains("Invalid key pressed");
            assertThat(SystemMessages.MESSAGE_LENGTH).isLessThan(UserAddResponse.ERR_MSG_LENGTH);
            String errMsg = narrowToErrMsg(SystemMessages.CCDA_MSG_INVALID_KEY);
            assertThat(errMsg)
                    .hasSize(UserAddResponse.ERR_MSG_LENGTH)
                    .startsWith(SystemMessages.CCDA_MSG_INVALID_KEY);
            assertThat(sentScreen(SystemMessages.CCDA_MSG_INVALID_KEY).errMsg()).isEqualTo(errMsg);
        }

        @Test
        @DisplayName("trnName and pgmName are the working-storage literals, at their declared widths")
        void theTransactionAndProgramNames() {
            UserAddResponse response = sentScreen(spaces(WS_MESSAGE_LENGTH));
            assertThat(response.trnName())
                    .as("COUSR01C.cbl:220 moves WS-TRANID into TRNNAMEO")
                    .isEqualTo(WS_TRANID)
                    .hasSize(UserAddResponse.TRN_NAME_LENGTH);
            assertThat(response.pgmName())
                    .as("COUSR01C.cbl:221 moves WS-PGMNAME into PGMNAMEO")
                    .isEqualTo(WS_PGMNAME)
                    .hasSize(UserAddResponse.PGM_NAME_LENGTH);
        }

        @Test
        @DisplayName("curDate and curTime come from a fixed clock, never from now()")
        void theDateAndTimeAreDrivenFromAFixedClock() {
            // COUSR01C.cbl:216 executes MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA, then lines
            // 223-233 compose mm/dd/yy and hh:mm:ss and move them into CURDATEO and CURTIMEO. Reading
            // a wall clock in a test would make the expected image unassertable, so DateHeader takes a
            // Clock and this case passes a fixed one - the seam config.WebConfig's single Clock bean
            // exists to serve (B7).
            UserAddResponse response = sentScreen(spaces(WS_MESSAGE_LENGTH));
            assertThat(response.curDate())
                    .as("mm/dd/yy for the instant stamped in app/bms/COUSR01.bms:163")
                    .isEqualTo("08/22/22")
                    .hasSize(UserAddResponse.CUR_DATE_LENGTH);
            assertThat(response.curTime())
                    .as("hh:mm:ss for the same instant")
                    .isEqualTo("17:02:44")
                    .hasSize(UserAddResponse.CUR_TIME_LENGTH);
            assertThat(sentScreen(spaces(WS_MESSAGE_LENGTH)).curDate())
                    .as("and the same fixed clock yields the same image every time it is read")
                    .isEqualTo(response.curDate());
        }
    }

    // =================================================================================================
    // 6. THE STORED WIDTHS.
    //
    // Five of the twelve screen fields carry data that is written to USRSEC. Screen width and stored
    // width must agree field for field, or the MOVEs at COUSR01C.cbl:154-158 lose characters that the
    // screen accepted.
    // =================================================================================================

    @Nested
    @DisplayName("Stored widths - the five collected fields against CSUSR01Y and SecUserRecord")
    class StoredWidths {

        @ParameterizedTest(name = "[{index}] {0} on the screen is {1} bytes in SEC-USER-DATA")
        @CsvSource({"fName,20", "lName,20", "userId,8", "passwd,8", "usrType,1"})
        @DisplayName("each collected field is the same width on the screen as in the record")
        void screenWidthEqualsStoredWidth(String member, int storedWidth) {
            assertThat(DECLARED_WIDTHS.get(MAP_MEMBERS.indexOf(member)))
                    .as("%s: the DFHMDF LENGTH and the CSUSR01Y PICTURE must agree", member)
                    .isEqualTo(storedWidth);
        }

        @Test
        @DisplayName("the five CSUSR01Y widths are transcribed exactly, in the record's own order")
        void theCopybookWidths() {
            // app/cpy/CSUSR01Y.cpy:18-23 declares 01 SEC-USER-DATA in this order: SEC-USR-ID X(08),
            // SEC-USR-FNAME X(20), SEC-USR-LNAME X(20), SEC-USR-PWD X(08), SEC-USR-TYPE X(01),
            // SEC-USR-FILLER X(23). Note the identifier is declared FIRST in the record but appears
            // NINTH on the screen: the record order and the map order are different orders, and neither
            // one is derived from the other.
            assertThat(SEC_USR_ID_LENGTH).isEqualTo(SecUserRecord.SEC_USR_ID_LENGTH);
            assertThat(SEC_USR_FNAME_LENGTH).isEqualTo(SecUserRecord.SEC_USR_FNAME_LENGTH);
            assertThat(SEC_USR_LNAME_LENGTH).isEqualTo(SecUserRecord.SEC_USR_LNAME_LENGTH);
            assertThat(SEC_USR_PWD_LENGTH).isEqualTo(SecUserRecord.SEC_USR_PWD_LENGTH);
            assertThat(SEC_USR_TYPE_LENGTH).isEqualTo(SecUserRecord.SEC_USR_TYPE_LENGTH);
            assertThat(SEC_USR_ID_LENGTH + SEC_USR_FNAME_LENGTH + SEC_USR_LNAME_LENGTH
                    + SEC_USR_PWD_LENGTH + SEC_USR_TYPE_LENGTH
                    + SecUserRecord.SEC_USR_FILLER_LENGTH)
                    .as("the six items account for every byte of the 80-byte record")
                    .isEqualTo(SecUserRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("the stored offsets are 0, 8, 28, 48, 56 and 57")
        void theStoredOffsets() {
            assertThat(SecUserRecord.SEC_USR_ID_OFFSET).isZero();
            assertThat(SecUserRecord.SEC_USR_FNAME_OFFSET).isEqualTo(8);
            assertThat(SecUserRecord.SEC_USR_LNAME_OFFSET).isEqualTo(28);
            assertThat(SecUserRecord.SEC_USR_PWD_OFFSET).isEqualTo(48);
            assertThat(SecUserRecord.SEC_USR_TYPE_OFFSET).isEqualTo(56);
            assertThat(SecUserRecord.SEC_USR_FILLER_OFFSET).isEqualTo(57);
            assertThat(SecUserRecord.RECORD_LENGTH).isEqualTo(80);
            // The key is the identifier, which is why the write at COUSR01C.cbl:244-245 passes
            // RIDFLD(SEC-USR-ID) with KEYLENGTH(LENGTH OF SEC-USR-ID).
            assertThat(SecUserRecord.KEY_OFFSET).isEqualTo(SecUserRecord.SEC_USR_ID_OFFSET);
            assertThat(SecUserRecord.KEY_LENGTH)
                    .isEqualTo(SEC_USR_ID_LENGTH)
                    .isEqualTo(UserAddResponse.USER_ID_LENGTH);
        }

        @Test
        @DisplayName("what the screen collects survives an encode/decode at the named code page")
        void theCollectedValuesRoundTripThroughTheRecord() {
            // A response is not what gets written - COUSR01C.cbl:154-158 moves the INPUT items into
            // SEC-USER-DATA - but the widths have to line up in both directions, because the screen is
            // also how a stored value would be shown. Encoding at an explicitly named charset (B8)
            // proves the five widths are mutually consistent rather than merely equal as integers.
            SecUserRecord record = SecUserRecord.of("NEWUSR01", "Jane", "Roe", "PLAINTXT", "U",
                    MAP_CHARSET);
            byte[] image = SecUserRecord.encode(record, MAP_CHARSET);
            assertThat(image).hasSize(SecUserRecord.RECORD_LENGTH);
            SecUserRecord restored = SecUserRecord.decode(image, MAP_CHARSET);
            assertThat(restored.image(SecUserRecord.FIELD_SEC_USR_ID))
                    .hasSize(UserAddResponse.USER_ID_LENGTH);
            assertThat(restored.image(SecUserRecord.FIELD_SEC_USR_FNAME))
                    .hasSize(UserAddResponse.F_NAME_LENGTH);
            assertThat(restored.image(SecUserRecord.FIELD_SEC_USR_LNAME))
                    .hasSize(UserAddResponse.L_NAME_LENGTH);
            assertThat(restored.image(SecUserRecord.FIELD_SEC_USR_PWD))
                    .hasSize(UserAddResponse.PASSWD_LENGTH);
            assertThat(restored.image(SecUserRecord.FIELD_SEC_USR_TYPE))
                    .hasSize(UserAddResponse.USR_TYPE_LENGTH);
        }
    }


    // =================================================================================================
    // 7. THE PASSWORD: DECLARED, AND NEVER CARRYING A STORED VALUE (practices B4, B5, B6; gate G41).
    //
    // This is the subtlety most likely to be mishandled, so the whole picture is stated before anything
    // is asserted.
    //
    // The member MUST exist. PASSWD is one of COUSR01's twelve name-labelled DFHMDF fields
    // (app/bms/COUSR01.bms:126) and PASSWDO is one of the twelve xxxO items (COUSR01.CPY:152).
    // Dropping it would break the 12-of-12 projection and violate gate G9.
    //
    // The member NEVER carries a stored password. grep -n 'PASSWD' app/cbl/COUSR01C.cbl returns exactly
    // four hits and not one of them writes an outbound password:
    //
    //   L136  WHEN PASSWDI OF COUSR1AI = SPACES OR LOW-VALUES    the blank test, reading input
    //   L140  MOVE -1 TO PASSWDL OF COUSR1AI                     a cursor signal, on a length item
    //   L157  MOVE PASSWDI OF COUSR1AI TO SEC-USR-PWD            input INTO the record being written
    //   L293  MOVE SPACES TO ... PASSWDI OF COUSR1AI ...          INITIALIZE-ALL-FIELDS blanks it
    //
    // And grep -c 'PASSWDO' app/cbl/COUSR01C.cbl returns ZERO: the program never names the output item.
    // So the only values that ever reach that offset are the LOW-VALUES of line 85 and the spaces of
    // line 293 - and line 293 is an outbound blank even though it names the INPUT item, because
    // 01 COUSR1AO REDEFINES COUSR1AI puts PASSWDI and PASSWDO on the same bytes. Section 8 proves that.
    //
    // THREE SCREENS, THREE TREATMENTS, ALL THREE CORRECT. Do not normalise them:
    //
    //   1. SignOnResponse    - 10 map members, NO password member. COSGN00C references PASSWDI and
    //                          PASSWDL at exactly four sites (COSGN00C.cbl:123, 126, 135, 244) and
    //                          never PASSWDO; line 135 additionally upper-cases what it reads.
    //   2. UserAddResponse   - THIS type. 12 map members, password DECLARED BUT BLANK ON OUTPUT.
    //   3. UserUpdateResponse - 12 map members, password PRESENT AND CARRYING THE STORED PLAINTEXT:
    //                          COUSR02C.cbl:169 executes MOVE SEC-USR-PWD TO PASSWDI OF COUSR2AI.
    //
    // (A fourth, for completeness: UserDeleteResponse has 11 map members and no password field at all -
    // grep -c 'PASSWD' app/cbl/COUSR03C.cbl returns zero.)
    //
    // This file asserts case 2 and states the others. It deliberately does not reach for a sibling
    // payload type: describing them here and asserting only this one keeps each type's contract owned
    // by exactly one suite.
    // =================================================================================================

    @Nested
    @DisplayName("The password - declared at PIC X(8), and only ever spaces on the way out")
    class PasswordDeclaredButNeverEchoed {

        @Test
        @DisplayName("passwd exists, is the tenth member, and is PIC X(8)")
        void thePasswordMemberExists() {
            assertThat(MAP_MEMBERS.indexOf("passwd"))
                    .as("tenth of the twelve, between userId and usrType")
                    .isEqualTo(9);
            assertThat(componentNames()).contains("passwd");
            assertThat(UserAddResponse.PASSWD_FIELD).isEqualTo("PASSWDO");
            assertThat(UserAddResponse.PASSWD_LENGTH)
                    .as("PASSWDO PIC X(8), matching SEC-USR-PWD PIC X(08) at CSUSR01Y.cpy:21")
                    .isEqualTo(8)
                    .isEqualTo(SEC_USR_PWD_LENGTH);
            assertThat(UserAddResponse.MAP_DERIVED_FIELD_NAMES)
                    .as("omitting it would make the projection 11 of 12 and break gate G9")
                    .contains("PASSWDO")
                    .hasSize(DFHMDF_NAMED);
        }

        @Test
        @DisplayName("the value it carries is spaces, not a password read back from USRSEC")
        void theValueCarriedIsBlank() {
            // INITIALIZE-ALL-FIELDS at COUSR01C.cbl:289-295 moves SPACES into the five collected
            // fields, and the successful-write arm at line 252 performs it before composing the success
            // message. So the screen a user sees after adding a user carries eight spaces here - not
            // the password they keyed, and not the password now stored.
            UserAddResponse response = sentScreen(composedSuccessMessage("NEWUSR01"));
            assertThat(response.passwd())
                    .as("eight spaces, exactly as INITIALIZE-ALL-FIELDS leaves the field")
                    .isEqualTo(spaces(UserAddResponse.PASSWD_LENGTH))
                    .isBlank()
                    .hasSize(UserAddResponse.PASSWD_LENGTH);
            assertThat(response.errMsg())
                    .as("while the message line does report the addition")
                    .contains("has been added");
        }

        @Test
        @DisplayName("eight spaces survive the round trip: not trimmed, not null, not omitted")
        void blankSurvivesTheRoundTrip() {
            // This is the case a default ObjectMapper breaks. Trimming would give "", empty-string
            // coercion would give null, and a non-null inclusion rule would drop the property
            // altogether - and every one of those is a value the screen never showed.
            UserAddResponse response = sentScreen(spaces(WS_MESSAGE_LENGTH));
            String json = serialise(response);
            assertThat(json)
                    .as("the property is present and carries its eight spaces verbatim")
                    .contains("\"passwd\":\"" + spaces(UserAddResponse.PASSWD_LENGTH) + "\"");
            assertThat(jsonKeys(response)).containsKey("passwd");

            UserAddResponse restored = deserialise(json);
            assertThat(restored.passwd())
                    .isNotNull()
                    .isNotEmpty()
                    .isEqualTo(spaces(UserAddResponse.PASSWD_LENGTH))
                    .hasSize(UserAddResponse.PASSWD_LENGTH);
            assertThat(restored).isEqualTo(response);
        }

        @Test
        @DisplayName("LOW-VALUES and SPACES are different byte images, and line 85 writes the first")
        void lowValuesAndSpacesAreNotTheSameImage() {
            // COUSR01C.cbl:85 executes MOVE LOW-VALUES TO COUSR1AO on first entry - the whole 339-byte
            // group, this field included. Line 293 later writes spaces. Both leave the field carrying
            // no password, but they are NOT the same bytes, which is why the blank predicate at line
            // 167 has to name both forms.
            String low = lowValues(UserAddResponse.PASSWD_LENGTH);
            String blank = spaces(UserAddResponse.PASSWD_LENGTH);
            assertThat(low).hasSize(UserAddResponse.PASSWD_LENGTH).isNotEqualTo(blank);
            assertThat(low.getBytes(MAP_CHARSET)).containsOnly((byte) 0x00);
            assertThat(blank.getBytes(MAP_CHARSET)).containsOnly((byte) 0x20);
            assertThat(low.isBlank())
                    .as("a NUL is not whitespace to Java, so isBlank() is not the COBOL predicate")
                    .isFalse();

            UserAddResponse response = responseOf(withMember(blankMapValues(), "passwd", low),
                    NavigationContext.empty(), spaces(8), MAPSET, MAP);
            assertThat(response.passwd())
                    .as("the payload carries whichever blank form it was given, unaltered")
                    .isEqualTo(low)
                    .isNotEqualTo(blank);
        }

        @Test
        @DisplayName("if a value ever is placed here it is carried verbatim - no hash, no mask, no fold")
        void aValuePlacedHereIsCarriedVerbatim() {
            // The member is a passive carrier. That matters for parity even though this program never
            // populates it: were it to be populated - as COUSR02C.cbl:169 does on the update screen -
            // the value would have to arrive exactly as stored. COUSR01C.cbl:154-158 performs five
            // PLAIN MOVEs with no FUNCTION UPPER-CASE anywhere in the program, while COSGN00C.cbl:135
            // does upper-case. That asymmetry between two programs on the same file is real and belongs
            // to the services; a payload that folded case would erase it.
            String keyed = "plaintxt";
            UserAddResponse response = responseOf(withMember(blankMapValues(), "passwd", keyed),
                    NavigationContext.empty(), spaces(8), MAPSET, MAP);
            assertThat(response.passwd())
                    .isEqualTo(keyed)
                    .isNotEqualTo(keyed.toUpperCase(Locale.ROOT))
                    .hasSize(UserAddResponse.PASSWD_LENGTH);
            assertThat(deserialise(serialise(response)).passwd()).isEqualTo(keyed);
        }

        @Test
        @DisplayName("the DRK attribute on PASSWD is terminal non-display, never storage masking")
        void theDarkAttributeIsPresentationOnly() {
            // app/bms/COUSR01.bms:126 declares PASSWD ATTRB=(DRK,FSET,UNPROT). DRK stops the 3270
            // rendering the characters as they are keyed; it says nothing about how the value is
            // stored, transmitted or compared. Mistaking it for hashing would be a security claim the
            // legacy design never made.
            assertThat(BmsAttributes.isNonDisplay(BmsAttributes.DFHBMDAR))
                    .as("DFHBMDAR is the unprotected non-display attribute - DRK")
                    .isTrue();
            assertThat(BmsAttributes.isNonDisplay(BmsAttributes.DFHBMUNP))
                    .as("while a plain unprotected field displays what it holds")
                    .isFalse();
            // Both sides of the same predicate, and neither one changes the payload: the response
            // carries no attribute member at all.
            assertThat(componentNames()).doesNotContain("passwdA", "passwdF", "passwdC", "passwdL");
        }
    }

    // =================================================================================================
    // 8. THE GROUP-LEVEL REDEFINES OVERLAY (gate G34).
    //
    // COUSR01.CPY:91 declares 01 COUSR1AO REDEFINES COUSR1AI. That is ONE of the thirteen REDEFINES in
    // this copybook - grep -c REDEFINES app/cpy-bms/COUSR01.CPY returns 13 - and the other twelve are
    // the per-field xxxA overlays that UserAddRequestTest asserts. Across this package's five mapsets
    // the split is 105 per-field plus 5 group-level, which is the verified total of 110. The five .cbl
    // programs contain no REDEFINES at all, so user.dto is the only place gate G34 has a subject.
    //
    // THE ARITHMETIC, derived from the copybook rather than copied from a brief:
    //
    //   input  view: 02 FILLER PIC X(12), then per field xxxL(2) + xxxF(1) + FILLER X(4) = 7, then xxxI
    //   output view: 02 FILLER PIC X(12), then per field FILLER X(3) + xxxC + xxxP + xxxH + xxxV = 7,
    //                then xxxO
    //
    // Both prefixes are seven bytes, so every data item sits at the same offset in both views and the
    // two align with ZERO drift. Data widths sum to 4+40+8+8+40+8+20+20+8+8+1+78 = 243, so the total is
    // 12 + 12x7 + 243 = 339 bytes in either view. Every part of that derivation is asserted below
    // against the transcribed constants, so one mistyped width fails rather than shifting an offset.
    //
    // The prefixes are equal in LENGTH but not in SHAPE: xxxF sits at prefix+2 and xxxC at prefix+3, so
    // the attribute items do NOT overlay each other. Only the data items do.
    // =================================================================================================

    @Nested
    @DisplayName("The COUSR1AO overlay - 339 bytes, one storage, two views")
    class SymbolicMapOverlay {

        @Test
        @DisplayName("the two seven-byte prefixes are built from different items of the same size")
        void bothPrefixesAreSevenBytes() {
            assertThat(LENGTH_ITEM_LENGTH + FLAG_ITEM_LENGTH + INPUT_RESERVED_LENGTH)
                    .as("input side: xxxL(2) + xxxF(1) + FILLER X(4)")
                    .isEqualTo(FIELD_PREFIX_LENGTH);
            assertThat(OUTPUT_RESERVED_LENGTH + ATTRIBUTE_ITEM_COUNT * ATTRIBUTE_ITEM_LENGTH)
                    .as("output side: FILLER X(3) + xxxC + xxxP + xxxH + xxxV")
                    .isEqualTo(FIELD_PREFIX_LENGTH);
            assertThat(FIELD_PREFIX_LENGTH)
                    .as("this single equality is what makes the group-level REDEFINES align")
                    .isEqualTo(7);
            // The per-field xxxA overlay adds no bytes: 02 FILLER REDEFINES xxxF / 03 xxxA PICTURE X
            // re-describes the one byte xxxF already occupies.
            assertThat(SYMBOLIC_MAP_LAYOUT.span("TRNNAMEA").offset())
                    .isEqualTo(SYMBOLIC_MAP_LAYOUT.span("TRNNAMEF").offset());
            assertThat(SYMBOLIC_MAP_LAYOUT.span("TRNNAMEA").length())
                    .isEqualTo(FLAG_ITEM_LENGTH);
        }

        @Test
        @DisplayName("the declared widths sum to 243 and the map is 339 bytes in both views")
        void theTotalWidth() {
            assertThat(DECLARED_WIDTHS.stream().mapToInt(Integer::intValue).sum())
                    .as("4+40+8+8+40+8+20+20+8+8+1+78")
                    .isEqualTo(PAYLOAD_WIDTH_TOTAL)
                    .isEqualTo(243);
            assertThat(TIOAPFX_PREFIX_LENGTH + DFHMDF_NAMED * FIELD_PREFIX_LENGTH
                    + PAYLOAD_WIDTH_TOTAL)
                    .as("12 + 12x7 + 243")
                    .isEqualTo(SYMBOLIC_MAP_LENGTH)
                    .isEqualTo(339);
            // Constructing SYMBOLIC_MAP_LAYOUT already proved the geometry: RecordLayout refuses a gap,
            // refuses an unintended overlap, refuses a duplicate name and refuses a declared total that
            // disagrees with the spans. Asserting the length here records what that construction means.
            assertThat(SYMBOLIC_MAP_LAYOUT.recordLength()).isEqualTo(SYMBOLIC_MAP_LENGTH);
            assertThat(SYMBOLIC_MAP_LAYOUT.storageSpans().stream()
                    .mapToInt(FixedWidthRecord.FieldSpan::length)
                    .sum())
                    .as("the storage spans of 01 COUSR1AI account for every byte exactly once")
                    .isEqualTo(SYMBOLIC_MAP_LENGTH);
        }

        @Test
        @DisplayName("every xxxO item sits at the identical offset as its xxxI partner")
        void thetwoViewsAlignFieldForField() {
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                FixedWidthRecord.FieldSpan input = SYMBOLIC_MAP_LAYOUT.span(XXXI_ITEMS.get(index));
                FixedWidthRecord.FieldSpan output = SYMBOLIC_MAP_LAYOUT.span(XXXO_ITEMS.get(index));
                assertThat(output.offset())
                        .as("%s and %s must describe the same bytes",
                                XXXI_ITEMS.get(index), XXXO_ITEMS.get(index))
                        .isEqualTo(input.offset())
                        .isEqualTo(DATA_OFFSETS.get(index));
                assertThat(output.length())
                        .as("and the same width")
                        .isEqualTo(input.length())
                        .isEqualTo(DECLARED_WIDTHS.get(index));
                assertThat(output.redefinition())
                        .as("the output item is the overlay; the input item is the storage")
                        .isTrue();
                assertThat(input.redefinition()).isFalse();
            }
        }

        @Test
        @DisplayName("the derived data offsets are 19, 30, 77, 92, 107, 154, 169, 196, 223, 238, 253, 261")
        void theDerivedOffsets() {
            // Transcribed independently by walking the copybook, then compared with the walk the layout
            // performs. Two derivations of the same numbers: if either is wrong they disagree.
            assertThat(DATA_OFFSETS)
                    .containsExactly(19, 30, 77, 92, 107, 154, 169, 196, 223, 238, 253, 261);
            assertThat(DATA_OFFSETS.get(0))
                    .as("the first data item begins after the 12-byte prefix and one 7-byte control set")
                    .isEqualTo(TIOAPFX_PREFIX_LENGTH + FIELD_PREFIX_LENGTH);
            int last = DFHMDF_NAMED - 1;
            assertThat(DATA_OFFSETS.get(last) + DECLARED_WIDTHS.get(last))
                    .as("and ERRMSGO's 78 bytes run to the very end of the map")
                    .isEqualTo(SYMBOLIC_MAP_LENGTH);
        }

        @Test
        @DisplayName("the prefixes are the same size but not the same shape: xxxF is at +2, xxxC at +3")
        void theAttributeItemsDoNotOverlayEachOther() {
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                String label = DFHMDF_LABELS.get(index);
                int prefix = DATA_OFFSETS.get(index) - FIELD_PREFIX_LENGTH;
                assertThat(SYMBOLIC_MAP_LAYOUT.span(label + "F").offset())
                        .as("%sF follows the two-byte length item", label)
                        .isEqualTo(prefix + LENGTH_ITEM_LENGTH);
                assertThat(SYMBOLIC_MAP_LAYOUT.span(label + "C").offset())
                        .as("%sC follows the three-byte output filler", label)
                        .isEqualTo(prefix + OUTPUT_RESERVED_LENGTH);
                assertThat(SYMBOLIC_MAP_LAYOUT.span(label + "C").offset())
                        .as("so the colour byte and the flag byte are different bytes: only the DATA "
                                + "items correspond across the two views")
                        .isNotEqualTo(SYMBOLIC_MAP_LAYOUT.span(label + "F").offset());
                for (int attribute = 0; attribute < ATTRIBUTE_ITEM_COUNT; attribute++) {
                    FixedWidthRecord.FieldSpan span = SYMBOLIC_MAP_LAYOUT.span(
                            label + OUTPUT_CONTROL_SUFFIXES.get(attribute));
                    assertThat(span.length()).isEqualTo(ATTRIBUTE_ITEM_LENGTH);
                    assertThat(span.offset())
                            .isEqualTo(prefix + OUTPUT_RESERVED_LENGTH + attribute);
                    assertThat(span.endOffsetExclusive())
                            .as("every attribute item stays inside the seven-byte prefix")
                            .isLessThanOrEqualTo(DATA_OFFSETS.get(index));
                }
            }
        }

        @ParameterizedTest(name = "[{index}] written through {0}, read back through {1}")
        @CsvSource({"TRNNAMEI,TRNNAMEO,CU01", "USERIDI,USERIDO,NEWUSR01",
                    "PASSWDI,PASSWDO,plaintxt", "ERRMSGI,ERRMSGO,User ID already exist..."})
        @DisplayName("a value written through one view reads back through the other")
        void aValueWrittenThroughOneViewReadsBackThroughTheOther(String inputItem, String outputItem,
                String value) {
            // The genuine round trip - one record area, two sets of names. This is what makes line 293
            // an OUTBOUND blank even though it names PASSWDI, and it is the whole reason the group-level
            // REDEFINES exists.
            FixedWidthCodec codec = codec();
            byte[] image = codec.serialise(SYMBOLIC_MAP_LAYOUT, Map.of(inputItem, value));
            assertThat(image).hasSize(SYMBOLIC_MAP_LENGTH);

            Map<String, String> images = codec.deserialise(SYMBOLIC_MAP_LAYOUT, image);
            FixedWidthRecord.FieldSpan span = SYMBOLIC_MAP_LAYOUT.span(outputItem);
            String expected = codec.movePicX(value, span.length());
            assertThat(images.get(outputItem))
                    .as("%s reads the bytes %s wrote", outputItem, inputItem)
                    .isEqualTo(expected)
                    .isEqualTo(images.get(inputItem));

            // And back the other way: write through the OUTPUT view, read through the INPUT view.
            byte[] reverse = codec.serialise(SYMBOLIC_MAP_LAYOUT, Map.of(outputItem, value));
            assertThat(codec.deserialise(SYMBOLIC_MAP_LAYOUT, reverse).get(inputItem))
                    .as("%s reads the bytes %s wrote", inputItem, outputItem)
                    .isEqualTo(expected);
            assertThat(reverse)
                    .as("both directions produce the identical 339-byte image")
                    .isEqualTo(image);
        }

        @Test
        @DisplayName("writing one field disturbs no other, so the overlay is not a shared buffer bug")
        void writingOneFieldDisturbsNoOther() {
            FixedWidthCodec codec = codec();
            byte[] image = codec.serialise(SYMBOLIC_MAP_LAYOUT, Map.of("USERIDO", "NEWUSR01"));
            Map<String, String> images = codec.deserialise(SYMBOLIC_MAP_LAYOUT, image);
            assertThat(images.get("USERIDO")).isEqualTo("NEWUSR01");
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                if ("USERIDO".equals(XXXO_ITEMS.get(index))) {
                    continue;
                }
                assertThat(images.get(XXXO_ITEMS.get(index)))
                        .as("%s was not written, so it holds its initialised spaces",
                                XXXO_ITEMS.get(index))
                        .isEqualTo(spaces(DECLARED_WIDTHS.get(index)));
            }
        }

        @Test
        @DisplayName("no filler is exposed: not the 12-byte prefix, not the per-field reserved spans")
        void noFillerIsExposed() {
            // FixedWidthCodec.deserialise skips FILLER by design, so the image map names only real
            // items. The payload does the same: no member, accessor or JSON key mentions a filler or the
            // TIOAPFX prefix. Those bytes are reserved storage in a 3270 buffer, not information.
            Map<String, String> images = codec().deserialise(SYMBOLIC_MAP_LAYOUT,
                    codec().serialise(SYMBOLIC_MAP_LAYOUT, Map.of()));
            assertThat(images.keySet())
                    .as("FILLER is storage, never a named field")
                    .doesNotContain("FILLER")
                    .hasSize(DFHMDF_NAMED * NAMED_ITEMS_PER_FIELD);
            assertThat(SYMBOLIC_MAP_LAYOUT.hasSpan("FILLER"))
                    .as("and it is not addressable by name either")
                    .isFalse();
            for (String name : componentNames()) {
                assertThat(name.toUpperCase(Locale.ROOT))
                        .doesNotContain("FILLER")
                        .doesNotContain("TIOAPFX");
            }
            assertThat(jsonKeys(blankResponse()).keySet())
                    .allSatisfy(key -> assertThat(key.toUpperCase(Locale.ROOT))
                            .doesNotContain("FILLER")
                            .doesNotContain("TIOAPFX"));
        }

        @Test
        @DisplayName("the control items are metadata: none of xxxL/xxxF/xxxA/xxxC/xxxP/xxxH/xxxV is a member")
        void theControlItemsAreMetadataNotPayload() {
            // Each of the twelve fields has seven single-purpose siblings beside its data item, and not
            // one of them is payload. xxxC is the colour item and the target of
            // MOVE DFHGREEN TO ERRMSGC OF COUSR1AO at COUSR01C.cbl:254 - which common.FieldAttributeSetter
            // exists to own, naming the same suffix this copybook uses. xxxH is the HILIGHT=UNDERLINE of
            // the five input fields. xxxL is an input length and a cursor signal - MOVE -1 TO FNAMEL
            // appears at lines 86, 100, 122, 149, 272 and 289 - and a cursor position is not a value.
            assertThat(FieldAttributeSetter.COLOUR_ITEM_SUFFIX).isEqualTo("C");
            assertThat(OUTPUT_CONTROL_SUFFIXES).containsExactly("C", "P", "H", "V");
            assertThat(INPUT_CONTROL_SUFFIXES).containsExactly("L", "F", "A");

            Set<String> jsonMembers = jsonKeys(blankResponse()).keySet();
            List<String> allSuffixes = new ArrayList<>(INPUT_CONTROL_SUFFIXES);
            allSuffixes.addAll(OUTPUT_CONTROL_SUFFIXES);
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                String member = MAP_MEMBERS.get(index);
                for (String suffix : allSuffixes) {
                    assertThat(componentNames())
                            .as("%s%s is a control item, not a member", member, suffix)
                            .doesNotContain(member + suffix);
                    assertThat(jsonMembers)
                            .as("%s%s must not reach the wire", member, suffix)
                            .doesNotContain(member + suffix);
                    assertThat(jsonMembers)
                            .doesNotContain(DFHMDF_LABELS.get(index) + suffix);
                }
            }
        }
    }


    // =================================================================================================
    // 9. errMsg AS THE LANDING PLACE FOR EVERY OUTCOME.
    //
    // Eight different texts can reach this one field, and every one of them arrives the same way:
    // MOVE <text> TO WS-MESSAGE, then MOVE WS-MESSAGE TO ERRMSGO at line 188. So each is asserted twice
    // - once as the 80-byte sender image and once as the 78-byte receiver - which exercises the
    // narrowing on real text rather than only on a synthetic string.
    //
    // Which text appears is the controller's decision and user.UserAddControllerTest owns it. What this
    // file owns is that each text fits the declared field, survives the round trip, and produces the
    // byte image the program would have produced.
    // =================================================================================================

    @Nested
    @DisplayName("Outcome messages - eight texts, one PIC X(78) field, one narrowing rule")
    class OutcomeMessages {

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {"First Name can NOT be empty...",
                               "Last Name can NOT be empty...",
                               "User ID can NOT be empty...",
                               "Password can NOT be empty...",
                               "User Type can NOT be empty...",
                               "User ID already exist...",
                               "Unable to Add User..."})
        @DisplayName("every message fits PIC X(78) and round-trips at the declared width")
        void everyMessageFitsAndRoundTrips(String text) {
            String sender = wsMessageImage(text);
            assertThat(sender)
                    .as("the WS-MESSAGE image is always 80 bytes, space-padded on the right")
                    .hasSize(WS_MESSAGE_LENGTH)
                    .startsWith(text);

            String receiver = narrowToErrMsg(text);
            assertThat(receiver)
                    .as("and the ERRMSGO image is always 78")
                    .hasSize(UserAddResponse.ERR_MSG_LENGTH)
                    .startsWith(text);
            // An independent cross-check of the codec's result, not a substitute for it (B11): the MOVE
            // itself is performed by FixedWidthCodec.movePicX inside narrowToErrMsg, and this line
            // confirms from the other direction that the surviving characters are the LEADING ones.
            assertThat(receiver)
                    .as("the leading 78 characters of the sender survive, and the trailing two do not")
                    .isEqualTo(sender.substring(0, UserAddResponse.ERR_MSG_LENGTH));

            UserAddResponse response = sentScreen(text);
            assertThat(response.errMsg()).isEqualTo(receiver);
            assertThat(deserialise(serialise(response)).errMsg())
                    .as("including its trailing padding, which is part of the rendered line")
                    .isEqualTo(receiver);
        }

        @Test
        @DisplayName("the five blank-field messages keep their EVALUATE order, first match winning")
        void theFiveBlankFieldMessagesAreOrdered() {
            // COUSR01C.cbl:117-151 is an EVALUATE TRUE: the arms are tested in source order and the
            // first true one wins, so a request with several blank fields produces the message for the
            // EARLIEST blank field and no other. That order follows the screen order - first name, last
            // name, user id, password, user type - which is this screen's own order and the inverse of
            // the update and delete screens.
            assertThat(ORDERED_BLANK_FIELD_MESSAGES)
                    .containsExactly(MSG_FIRST_NAME_EMPTY,
                            MSG_LAST_NAME_EMPTY,
                            MSG_USER_ID_EMPTY,
                            MSG_PASSWORD_EMPTY,
                            MSG_USER_TYPE_EMPTY);
            List<String> collectedMembers = List.of("fName", "lName", "userId", "passwd", "usrType");
            for (int index = 0; index < collectedMembers.size(); index++) {
                int position = MAP_MEMBERS.indexOf(collectedMembers.get(index));
                assertThat(position)
                        .as("the arm for %s tests the field at member position %d",
                                collectedMembers.get(index), position)
                        .isEqualTo(MAP_MEMBERS.indexOf("fName") + index);
            }
            // Each is distinct: five arms, five texts, no two the same.
            assertThat(ORDERED_BLANK_FIELD_MESSAGES).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("the WHEN OTHER arm sets no message at all, so errMsg stays blank")
        void theWhenOtherArmSetsNoMessage() {
            // COUSR01C.cbl:148-150 is the WHEN OTHER of PROCESS-ENTER-KEY: it moves -1 into FNAMEL to
            // place the cursor and then CONTINUE. It moves NO text into WS-MESSAGE, and WS-MESSAGE was
            // blanked at line 75 on entry. So the no-blank-fields path leaves the message line empty and
            // proceeds to the write - it does not report anything.
            UserAddResponse response = sentScreen(spaces(WS_MESSAGE_LENGTH));
            assertThat(response.errMsg())
                    .as("78 spaces: an empty message line, not a null and not an empty string")
                    .isEqualTo(spaces(UserAddResponse.ERR_MSG_LENGTH))
                    .isBlank()
                    .hasSize(UserAddResponse.ERR_MSG_LENGTH);
            assertThat(deserialise(serialise(response)).errMsg())
                    .isEqualTo(spaces(UserAddResponse.ERR_MSG_LENGTH));
        }

        @ParameterizedTest(name = "[{index}] the identifier {0} composes a {1}-character message")
        @CsvSource({"NEWUSR01,32", "AB,26", "A,25", "USER0001,32"})
        @DisplayName("the success text is variable in length, because DELIMITED BY SPACE stops early")
        void theSuccessTextIsVariableInLength(String keyedId, int composedLength) {
            // COUSR01C.cbl:255-258 composes the text with
            //   STRING 'User '              DELIMITED BY SIZE
            //          SEC-USR-ID           DELIMITED BY SPACE
            //          ' has been added ...' DELIMITED BY SIZE
            //     INTO WS-MESSAGE
            // DELIMITED BY SPACE stops the middle operand at its first space, so an identifier shorter
            // than eight characters contributes only its own characters - it does NOT pad the message to
            // eight. The composed length therefore varies with the identifier, which is the opposite of
            // what a fixed-width intuition would predict.
            //
            // The keyed identifier is padded to PIC X(08) here exactly as COUSR01C.cbl:154 pads it -
            // MOVE USERIDI OF COUSR1AI TO SEC-USR-ID - rather than being carried as padded text through
            // @CsvSource, which would not preserve the trailing spaces the case depends on.
            String secUsrId = codec().movePicX(keyedId, SEC_USR_ID_LENGTH);
            assertThat(secUsrId).hasSize(SEC_USR_ID_LENGTH).startsWith(keyedId);
            String composed = composedSuccessMessage(secUsrId);
            assertThat(composed)
                    .hasSize(composedLength)
                    .startsWith(SUCCESS_PREFIX)
                    .endsWith(SUCCESS_SUFFIX)
                    .doesNotContain("  ");
            assertThat(SUCCESS_PREFIX.length() + secUsrId.strip().length() + SUCCESS_SUFFIX.length())
                    .as("prefix + the identifier up to its first space + suffix")
                    .isEqualTo(composedLength);
        }

        @Test
        @DisplayName("STRING does not pad, which is why line 253 blanks WS-MESSAGE first")
        void theStringVerbDoesNotPadItsReceiver() {
            // A COBOL STRING overlays the receiving item from the left and leaves the remainder as it
            // was - unlike MOVE, it does not space-fill the tail. COUSR01C.cbl:253 therefore executes
            // MOVE SPACES TO WS-MESSAGE immediately before the STRING at 255-258. Without that line the
            // success text would be followed by whatever the previous message left behind. The 80-byte
            // image asserted here is the composition WITH that preceding blank, which is what the
            // program does.
            String composed = composedSuccessMessage("NEWUSR01");
            String image = wsMessageImage(composed);
            assertThat(image)
                    .hasSize(WS_MESSAGE_LENGTH)
                    .startsWith(composed)
                    .isEqualTo(composed + spaces(WS_MESSAGE_LENGTH - composed.length()));
            assertThat(image.substring(composed.length()))
                    .as("the tail is spaces because line 253 put them there, not because STRING did")
                    .isBlank();
            assertThat(narrowToErrMsg(composed))
                    .hasSize(UserAddResponse.ERR_MSG_LENGTH)
                    .isEqualTo(codec().movePicX(composed, UserAddResponse.ERR_MSG_LENGTH));
        }

        @Test
        @DisplayName("the duplicate-key and fallback texts are the write's other two outcomes")
        void theWriteOutcomeTexts() {
            // COUSR01C.cbl:250-274 evaluates WS-RESP-CD into three arms: NORMAL composes the success
            // text; DUPKEY and DUPREC SHARE one arm and produce 'User ID already exist...'; WHEN OTHER
            // produces 'Unable to Add User...'. Two response codes reaching one arm is deliberate in the
            // source and is recorded here rather than split into two texts.
            assertThat(MSG_DUPLICATE).isEqualTo("User ID already exist...");
            assertThat(MSG_UNABLE_TO_ADD).isEqualTo("Unable to Add User...");
            assertThat(sentScreen(MSG_DUPLICATE).errMsg())
                    .hasSize(UserAddResponse.ERR_MSG_LENGTH)
                    .startsWith(MSG_DUPLICATE);
            assertThat(sentScreen(MSG_UNABLE_TO_ADD).errMsg())
                    .hasSize(UserAddResponse.ERR_MSG_LENGTH)
                    .startsWith(MSG_UNABLE_TO_ADD);
            // The success arm additionally blanks the five collected fields before composing its text -
            // INITIALIZE-ALL-FIELDS at line 252 - which is why a successful add leaves an empty form.
            UserAddResponse success = sentScreen(composedSuccessMessage("NEWUSR01"));
            assertThat(List.of(success.fName(), success.lName(), success.userId(), success.passwd(),
                    success.usrType()))
                    .as("all five blank, at their own declared widths")
                    .allSatisfy(value -> assertThat(value).isBlank());
        }
    }

    // =================================================================================================
    // 10. STATELESS NAVIGATION (gates G37 and G40, rule R6).
    //
    // CICS is pseudo-conversational: COUSR01C paints a screen, ends with
    // EXEC CICS RETURN TRANSID(WS-TRANID) COMMAREA(CARDDEMO-COMMAREA) at lines 107-110, and is
    // re-entered from the top when the user presses a key. The only state that survives is what it
    // handed back. So the migration carries that same state in the payload and keeps the endpoint
    // stateless - no HttpSession, no server-side cache, no session affinity.
    //
    // Two members the brief for this file expects are NOT declared here, and both are correct as
    // declared (B4):
    //
    //   * the resolved AID token is an INBOUND concern - which key the user pressed - and lives on
    //     UserAddRequest.aid(). A response does not report a keystroke back to the client that made it.
    //   * the enter-versus-re-enter flag lives INSIDE the communication area as CDEMO-PGM-CONTEXT and is
    //     read through NavigationContext.isEnter() and isReenter(), rather than duplicated beside it.
    //
    // Both facts are asserted below.
    // =================================================================================================

    @Nested
    @DisplayName("Stateless navigation - XCTL becomes three fields and the commarea travels")
    class StatelessNavigation {

        @Test
        @DisplayName("nextProgram, nextMapset and nextMap replace EXEC CICS XCTL")
        void theTransferBecomesThreeResponseFields() {
            // COUSR01C.cbl:175-178 executes EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM)
            // COMMAREA(CARDDEMO-COMMAREA). There is no server-side forward in the migration: the
            // response names the target and the client issues the follow-up call.
            assertThat(componentNames()).contains("nextProgram", "nextMapset", "nextMap");
            assertThat(UserAddResponse.NEXT_PROGRAM_LENGTH)
                    .as("CDEMO-TO-PROGRAM PIC X(08) at COCOM01Y.cpy:24")
                    .isEqualTo(NavigationContext.TO_PROGRAM_LENGTH)
                    .isEqualTo(8);
            assertThat(UserAddResponse.NEXT_MAP_LENGTH)
                    .as("CDEMO-LAST-MAP PIC X(7) at COCOM01Y.cpy:43 - seven, not eight")
                    .isEqualTo(NavigationContext.LAST_MAP_LENGTH)
                    .isEqualTo(7);
            assertThat(UserAddResponse.NEXT_MAPSET_LENGTH)
                    .as("CDEMO-LAST-MAPSET PIC X(7) at COCOM01Y.cpy:44")
                    .isEqualTo(NavigationContext.LAST_MAPSET_LENGTH)
                    .isEqualTo(7);
        }

        @Test
        @DisplayName("this screen's own map and mapset fit the PIC X(7) carriers exactly")
        void theMapNamesFitTheSevenByteCarriers() {
            UserAddResponse response = blankResponse();
            assertThat(response.nextMap())
                    .isEqualTo(MAP)
                    .hasSize(UserAddResponse.NEXT_MAP_LENGTH);
            assertThat(response.nextMapset())
                    .isEqualTo(MAPSET)
                    .hasSize(UserAddResponse.NEXT_MAPSET_LENGTH);
            // Seven characters is not a coincidence and not spare room: an eighth would not fit, and
            // NavigationContext refuses it rather than silently shortening it.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> NavigationContext.empty().withLastMap(MAP + "X"))
                    .withMessageContaining(NavigationContext.LAST_MAP_FIELD);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> NavigationContext.empty().withLastMapset(MAPSET + "X"))
                    .withMessageContaining(NavigationContext.LAST_MAPSET_FIELD);
        }

        @ParameterizedTest(name = "[{index}] a {0} target resolves to {1}")
        @CsvSource({"SPACES,COSGN00C",
                    "LOW_VALUES,COSGN00C",
                    "PF3_TARGET,COADM01C",
                    "MENU_TARGET,COMEN01C"})
        @DisplayName("a blank target defaults to COSGN00C; a populated one is preserved")
        void theBlankTargetDefault(String form, String expected) {
            // COUSR01C.cbl:167-169 reads
            //     IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES
            //         MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM
            //     END-IF
            // so a caller that supplied no return target gets the sign-on program, and a caller that
            // supplied one keeps it - PF3 supplies 'COADM01C' at line 94, and the no-commarea path
            // supplies 'COSGN00C' outright at line 79. BOTH branches are driven here, and the blank
            // branch is driven in BOTH of its forms, because the source predicate names both (G50).
            //
            // The target is built here rather than passed as a CSV value: a value of eight spaces cannot
            // travel through @CsvSource intact, and a case that silently lost its padding would be
            // asserting something other than what it claims.
            String target = switch (form) {
                case "SPACES" -> spaces(NavigationContext.TO_PROGRAM_LENGTH);
                case "LOW_VALUES" -> lowValues(NavigationContext.TO_PROGRAM_LENGTH);
                case "PF3_TARGET" -> ADMIN_MENU_PROGRAM;
                case "MENU_TARGET" -> "COMEN01C";
                default -> throw new IllegalArgumentException("Unhandled target form " + form);
            };
            String resolved = resolveTransferTarget(target);
            assertThat(resolved).isEqualTo(expected);

            UserAddResponse response = responseOf(blankMapValues(), NavigationContext.empty(),
                    resolved, MAPSET, MAP);
            assertThat(response.nextProgram())
                    .as("the payload carries the resolved target verbatim")
                    .isEqualTo(expected)
                    .hasSize(UserAddResponse.NEXT_PROGRAM_LENGTH);
            assertThat(deserialise(serialise(response)).nextProgram()).isEqualTo(expected);
        }

        @Test
        @DisplayName("the two blank forms are different bytes, which is why the source names both")
        void theTwoBlankFormsAreDistinct() {
            String blank = spaces(NavigationContext.TO_PROGRAM_LENGTH);
            String low = lowValues(NavigationContext.TO_PROGRAM_LENGTH);
            assertThat(blank).isNotEqualTo(low).hasSameSizeAs(low);
            assertThat(resolveTransferTarget(blank)).isEqualTo(SIGN_ON_PROGRAM);
            assertThat(resolveTransferTarget(low))
                    .as("an all-LOW-VALUES target takes the default just as an all-spaces one does")
                    .isEqualTo(SIGN_ON_PROGRAM);
            assertThat(resolveTransferTarget(ADMIN_MENU_PROGRAM))
                    .as("and a populated one is left exactly as the caller set it")
                    .isEqualTo(ADMIN_MENU_PROGRAM);
        }

        @Test
        @DisplayName("the outbound context carries CU01, COUSR01C and a zeroed program context")
        void theOutboundContextFields() {
            // COUSR01C.cbl:170-171 and 174, the three ENABLED statements of RETURN-TO-PREV-SCREEN:
            //     MOVE WS-TRANID  TO CDEMO-FROM-TRANID
            //     MOVE WS-PGMNAME TO CDEMO-FROM-PROGRAM
            //     MOVE ZEROS      TO CDEMO-PGM-CONTEXT
            NavigationContext outbound = returnToPrevScreenContext(NavigationContext.empty()
                    .withToProgram(ADMIN_MENU_PROGRAM)
                    .withPgmReenter());
            assertThat(outbound.fromTranid())
                    .isEqualTo(WS_TRANID)
                    .hasSize(NavigationContext.FROM_TRANID_LENGTH);
            assertThat(outbound.fromProgram())
                    .isEqualTo(WS_PGMNAME)
                    .hasSize(NavigationContext.FROM_PROGRAM_LENGTH);
            assertThat(outbound.pgmContext())
                    .as("MOVE ZEROS leaves the next program to paint its screen for the first time")
                    .isEqualTo(NavigationContext.PGM_CONTEXT_ENTER)
                    .isZero();
            assertThat(outbound.isEnter()).isTrue();
            assertThat(outbound.isReenter()).isFalse();
            assertThat(outbound.toProgram())
                    .as("the target the caller set is untouched by these three moves")
                    .isEqualTo(ADMIN_MENU_PROGRAM);
        }

        @Test
        @DisplayName("CDEMO-USER-ID and CDEMO-USER-TYPE are NOT populated: lines 172-173 are commented out")
        void theUserIdentityIsNotPopulated() {
            // This is the assertion the two disabled statements make necessary, and asserting the
            // ABSENCE matters as much as asserting a presence would (B4, B5). COUSR01C.cbl:172-173 read
            //     *    MOVE WS-USER-ID   TO CDEMO-USER-ID
            //     *    MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE
            // Both are commented out, so the transfer at 175-178 leaves both fields exactly as they
            // arrived. COSGN00C.cbl:226-227 does set both, so this is a real difference between two
            // programs - not an omission to be tidied up.
            NavigationContext outbound = returnToPrevScreenContext(NavigationContext.empty());
            assertThat(outbound.userId())
                    .as("nothing on this path writes an identifier into the commarea")
                    .isEqualTo(spaces(NavigationContext.USER_ID_LENGTH))
                    .isBlank();
            assertThat(outbound.userType())
                    .as("and nothing writes a user type either")
                    .isEqualTo(spaces(NavigationContext.USER_TYPE_LENGTH))
                    .isBlank();
            assertThat(outbound.isAdmin()).isFalse();
            assertThat(outbound.isUser()).isFalse();

            // An identity that was already in the commarea is carried through unchanged - which is the
            // same statement seen from the other side: this program neither sets it nor clears it.
            NavigationContext carried = returnToPrevScreenContext(NavigationContext.empty()
                    .withUserId("ADMIN001")
                    .withUserTypeAdmin());
            assertThat(carried.userId()).isEqualTo("ADMIN001");
            assertThat(carried.userType()).isEqualTo(NavigationContext.USER_TYPE_ADMIN);
            assertThat(carried.isAdmin()).isTrue();
            assertThat(carried.isUser()).isFalse();
        }

        @Test
        @DisplayName("the 160-byte commarea travels in the payload, so no session is needed")
        void theCommareaTravelsInThePayload() {
            // app/cpy/COCOM01Y.cpy:19-44 declares 01 CARDDEMO-COMMAREA as 34 + 84 + 12 + 16 + 14 = 160
            // bytes. Carrying it as a payload member is what makes the endpoint stateless: the whole
            // conversation state goes out with the response and comes back with the next request.
            assertThat(NavigationContext.GENERAL_INFO_LENGTH
                    + NavigationContext.CUSTOMER_INFO_LENGTH
                    + NavigationContext.ACCOUNT_INFO_LENGTH
                    + NavigationContext.CARD_INFO_LENGTH
                    + NavigationContext.MORE_INFO_LENGTH)
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH)
                    .isEqualTo(160);

            NavigationContext context = returnToPrevScreenContext(NavigationContext.empty()
                    .withToProgram(ADMIN_MENU_PROGRAM)
                    .withLastMap(MAP)
                    .withLastMapset(MAPSET));
            FixedWidthCodec codec = codec();
            byte[] image = context.toFixedWidth(codec);
            assertThat(image)
                    .as("and it is exactly 160 bytes on the wire, at the named code page (B8)")
                    .hasSize(NavigationContext.COMMAREA_LENGTH);
            assertThat(NavigationContext.fromFixedWidth(codec, image))
                    .as("byte-for-byte recoverable, which is what replacing a session requires")
                    .isEqualTo(context);

            UserAddResponse response = responseOf(blankMapValues(), context, ADMIN_MENU_PROGRAM,
                    MAPSET, MAP);
            assertThat(response.navigationContext()).isEqualTo(context);
            assertThat(deserialise(serialise(response)).navigationContext())
                    .as("and it survives the JSON round trip too")
                    .isEqualTo(context);
            assertThat(jsonKeys(response)).containsKey("navigationContext");
        }

        @Test
        @DisplayName("the ENTER and REENTER states are both reachable through the commarea")
        void bothProgramContextStatesAreDriven() {
            // COCOM01Y.cpy:29-31 declares CDEMO-PGM-CONTEXT PIC 9(01) with 88 CDEMO-PGM-ENTER VALUE 0
            // and 88 CDEMO-PGM-REENTER VALUE 1. COUSR01C.cbl:83-84 tests it and sets it: a first entry
            // paints the screen, a re-entry validates what was typed. Both states are driven here (G50),
            // through the commarea rather than through a member of this response - the declared type has
            // no separate flag, and duplicating one beside the commarea would create two sources of
            // truth for one byte.
            NavigationContext enter = NavigationContext.empty();
            assertThat(enter.pgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_ENTER).isZero();
            assertThat(enter.isEnter()).isTrue();
            assertThat(enter.isReenter()).isFalse();

            NavigationContext reenter = enter.withPgmReenter();
            assertThat(reenter.pgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_REENTER)
                    .isEqualTo(1);
            assertThat(reenter.isReenter()).isTrue();
            assertThat(reenter.isEnter()).isFalse();

            for (NavigationContext state : List.of(enter, reenter)) {
                UserAddResponse response = responseOf(blankMapValues(), state, SIGN_ON_PROGRAM,
                        MAPSET, MAP);
                assertThat(deserialise(serialise(response)).navigationContext().pgmContext())
                        .as("the context byte crosses the wire in both states")
                        .isEqualTo(state.pgmContext());
            }
            assertThat(componentNames())
                    .as("and there is no second flag beside the commarea")
                    .doesNotContain("pgmContext", "reenter", "enter", "pgmEnter", "pgmReenter");
        }

        @Test
        @DisplayName("the AID is an inbound concern: this response declares no aid member")
        void theAidTravelsOnTheRequestNotTheResponse() {
            // COUSR01C.cbl:90-103 evaluates EIBAID - which key the user pressed - and that is inbound
            // information. The declared response has no aid member, and it should not: a response does
            // not report a keystroke back to the client that produced it. The resolved token travels on
            // UserAddRequest.aid(), five characters wide, which is where common.PfKeyResolver's output
            // is carried. Documented rather than reconciled (B4).
            assertThat(componentNames()).doesNotContain("aid", "aidToken", "eibAid");
            assertThat(UserAddRequest.AID_LENGTH)
                    .as("the inbound carrier is PIC X(5), matching the resolver's token width")
                    .isEqualTo(PfKeyResolver.AID_TOKEN_LENGTH)
                    .isEqualTo(5);
            for (PfKeyResolver.AidKey key : PfKeyResolver.AidKey.values()) {
                assertThat(key.token())
                        .as("%s renders as a five-character token", key)
                        .hasSize(PfKeyResolver.AID_TOKEN_LENGTH);
            }
            // The three keys this screen acts on, resolved from the DFHAID constants the program copies
            // at line 55. Everything else falls to the WHEN OTHER arm at lines 98-102.
            Optional<PfKeyResolver.AidKey> enter = PfKeyResolver.resolve(CicsAid.DFHENTER);
            Optional<PfKeyResolver.AidKey> pf3 = PfKeyResolver.resolve(CicsAid.DFHPF3);
            Optional<PfKeyResolver.AidKey> pf4 = PfKeyResolver.resolve(CicsAid.DFHPF4);
            assertThat(enter).contains(PfKeyResolver.AidKey.ENTER);
            assertThat(pf3).contains(PfKeyResolver.AidKey.PFK03);
            assertThat(pf4).contains(PfKeyResolver.AidKey.PFK04);
            assertThat(PfKeyResolver.isEnter(CicsAid.DFHENTER)).isTrue();
            assertThat(PfKeyResolver.isPf3(CicsAid.DFHPF3)).isTrue();
            assertThat(PfKeyResolver.isPf3(CicsAid.DFHENTER)).isFalse();
        }

        @Test
        @DisplayName("no CDEMO-CU0n-INFO extension block: this screen declares none")
        void noCommareaExtensionBlock() {
            // grep -c 'CDEMO-CU0[0-9]-INFO' app/cbl/COUSR01C.cbl returns 0. The user list, user update
            // and user delete programs each declare one - a paging and selection block appended to the
            // commarea - and this one does not, because it pages nothing and selects nothing. Adding a
            // block for consistency with a sibling screen is exactly what B5 forbids.
            assertThat(componentNames())
                    .hasSize(DFHMDF_NAMED + NAV_MEMBERS.size())
                    .doesNotContain("cu01Info", "cu00Info", "cu02Info", "cu03Info");
            for (String name : componentNames()) {
                assertThat(name.toLowerCase(Locale.ROOT))
                        .as("%s must not be a commarea extension block", name)
                        .doesNotContain("cu0");
            }
            assertThat(jsonKeys(blankResponse()).keySet())
                    .containsExactlyInAnyOrderElementsOf(expectedJsonMembers());
        }

        @Test
        @DisplayName("no session, cache or thread-bound storage is reachable from this type")
        void nothingHoldsServerSideState() {
            // Statelessness here is structural, not a matter of trust: a record has no place to put
            // server-side state, and nothing in the declared surface names a type that could.
            for (String name : reachableTypeNames()) {
                assertThat(name.toLowerCase(Locale.ROOT))
                        .as("%s must not be a session, cache or thread-local carrier", name)
                        .doesNotContain("session")
                        .doesNotContain("httpservlet")
                        .doesNotContain("threadlocal")
                        .doesNotContain("cache");
            }
            assertThat(UserAddResponse.class.isRecord())
                    .as("an immutable record cannot change underneath a caller that holds it")
                    .isTrue();
        }
    }

    /**
     * The blank-target rule of {@code app/cbl/COUSR01C.cbl:167-169}, transcribed so both of its branches
     * can be driven from this suite.
     *
     * <p>This is a transcription, not a re-implementation of the program: {@code user.UserAddController}
     * owns the decision and {@code user.UserAddControllerTest} owns testing that it is taken. What this
     * file needs is the two outcomes, so that it can prove the payload carries either one unaltered and
     * that the two blank <em>forms</em> the source names are genuinely treated alike.
     */
    private static String resolveTransferTarget(String cdemoToProgram) {
        boolean blank = cdemoToProgram.isEmpty()
                || cdemoToProgram.chars().allMatch(character -> character == ' ' || character == 0);
        return blank ? SIGN_ON_PROGRAM : cdemoToProgram;
    }

    /**
     * The three <em>enabled</em> statements of {@code RETURN-TO-PREV-SCREEN} at
     * {@code app/cbl/COUSR01C.cbl:170-174}: the from-transaction, the from-program and the zeroed
     * program context. Lines 172 and 173 are commented out in the source and are therefore absent here -
     * that absence is the point, and {@link StatelessNavigation#theUserIdentityIsNotPopulated()} asserts
     * it.
     */
    private static NavigationContext returnToPrevScreenContext(NavigationContext inbound) {
        return inbound.withFromTranid(WS_TRANID)
                .withFromProgram(WS_PGMNAME)
                .withPgmContext(NavigationContext.PGM_CONTEXT_ENTER);
    }


    // =================================================================================================
    // 11. JSON SERIALISATION.
    //
    // Space padding is meaning on a fixed-width screen, not whitespace to be tidied. config.WebConfig
    // owns the policy that keeps it - names untransformed, nothing trimmed, no empty-to-null coercion,
    // no null-or-empty exclusion - and this section asserts the policy holds for this payload rather
    // than assuming a default mapper would do.
    // =================================================================================================

    @Nested
    @DisplayName("JSON - sixteen properties, padding intact, metadata absent")
    class JsonRoundTrip {

        @Test
        @DisplayName("the mapper this suite uses is configured as config.WebConfig configures the shared one")
        void theMapperMirrorsTheApplicationPolicy() {
            ObjectMapper mapper = webConfigEquivalentMapper();
            assertThat(mapper.getDeserializationConfig()
                    .isEnabled(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS))
                    .isTrue();
            assertThat(mapper.getDeserializationConfig()
                    .isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS))
                    .isTrue();
            assertThat(mapper.getFactory().isEnabled(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN))
                    .isTrue();
            assertThat(mapper.getDeserializationConfig()
                    .isEnabled(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT))
                    .as("an all-space PIC X field must never arrive as null")
                    .isFalse();
            assertThat(mapper.getSerializationConfig().getPropertyNamingStrategy())
                    .as("no naming strategy: the property names are the member names")
                    .isNull();
        }

        @Test
        @DisplayName("the wire form names exactly the twelve map members and the four navigation members")
        void theWireFormNamesSixteenProperties() {
            Map<String, Object> properties = jsonKeys(sentScreen(MSG_DUPLICATE));
            assertThat(properties.keySet())
                    .containsExactlyInAnyOrderElementsOf(expectedJsonMembers())
                    .hasSize(DFHMDF_NAMED + NAV_MEMBERS.size());
            assertThat(properties.keySet())
                    .as("the member names are carried through untransformed - no snake_case, no kebab")
                    .allSatisfy(key -> assertThat(key).doesNotContain("_").doesNotContain("-"));
        }

        @Test
        @DisplayName("a fully padded instance survives serialise then deserialise byte for byte")
        void aPaddedInstanceSurvivesTheRoundTrip() {
            List<String> values = blankMapValues();
            values = withMember(values, "trnName", WS_TRANID);
            values = withMember(values, "title01", ScreenTitles.CCDA_TITLE01);
            values = withMember(values, "curDate", "08/22/22");
            values = withMember(values, "pgmName", WS_PGMNAME);
            values = withMember(values, "title02", ScreenTitles.CCDA_TITLE02);
            values = withMember(values, "curTime", "17:02:44");
            values = withMember(values, "fName",
                    codec().movePicX("Johnathan", UserAddResponse.F_NAME_LENGTH));
            values = withMember(values, "lName",
                    codec().movePicX("Rutherford", UserAddResponse.L_NAME_LENGTH));
            values = withMember(values, "userId", "NEWUSR01");
            values = withMember(values, "usrType", NavigationContext.USER_TYPE_USER);
            values = withMember(values, "errMsg", narrowToErrMsg(composedSuccessMessage("NEWUSR01")));

            UserAddResponse original = responseOf(values,
                    returnToPrevScreenContext(NavigationContext.empty().withLastMap(MAP)),
                    SIGN_ON_PROGRAM, MAPSET, MAP);
            UserAddResponse restored = deserialise(serialise(original));

            assertThat(restored).isEqualTo(original);
            assertThat(restored.hashCode()).isEqualTo(original.hashCode());
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                assertThat(mapValuesOf(restored).get(index))
                        .as("%s survives at its declared width", MAP_MEMBERS.get(index))
                        .isEqualTo(mapValuesOf(original).get(index));
            }
            assertThat(restored.fName())
                    .as("twenty characters, nine of them significant and eleven of them padding")
                    .hasSize(UserAddResponse.F_NAME_LENGTH)
                    .startsWith("Johnathan")
                    .endsWith(" ");
            assertThat(restored.passwd())
                    .as("and the blank password is still eight spaces")
                    .isEqualTo(spaces(UserAddResponse.PASSWD_LENGTH));
        }

        @Test
        @DisplayName("a null member is carried as null rather than invented into spaces")
        void aNullMemberIsCarriedAsNull() {
            // The declared type has no compact constructor, so it does not reject null and does not
            // substitute spaces for one. That is the right behaviour for a payload whose job is to carry
            // what the controller composed: inventing a value here would hide a controller that failed
            // to compose one. Driving the null path also covers the branch a serialiser takes for an
            // absent property, which is reachable from no other case in this file.
            UserAddResponse response = responseOf(Arrays.asList(null, null, null, null, null, null,
                    null, null, null, null, null, null), NavigationContext.empty(), null, null, null);
            assertThat(response.trnName()).isNull();
            assertThat(response.passwd()).isNull();
            assertThat(response.nextProgram()).isNull();

            UserAddResponse restored = deserialise(serialise(response));
            assertThat(restored).isEqualTo(response);
            assertThat(restored.errMsg())
                    .as("null is not coerced to an empty string on the way back either")
                    .isNull();
        }

        @Test
        @DisplayName("trailing content is refused, so a truncated or doubled body cannot pass silently")
        void trailingTokensAreRefused() {
            // FAIL_ON_TRAILING_TOKENS is one of the features config.WebConfig enables. Asserting it here
            // records why: a body with a second document appended would otherwise deserialise to the
            // first and discard the rest without complaint.
            String doubled = serialise(blankResponse()) + serialise(blankResponse());
            assertThatExceptionOfType(UncheckedIOException.class)
                    .isThrownBy(() -> deserialise(doubled));
        }
    }

    // =================================================================================================
    // 12. SECURITY POSTURE (practice B6, gate G41).
    //
    // Authentication in this application compares SEC-USR-PWD in plaintext, exactly as COSGN00C does.
    // Introducing hashing would change observable behaviour and would need a framework the plan
    // excludes, so the posture is preserved and documented rather than quietly improved. The plaintext
    // handling of credentials is an inherited property of the legacy design and an explicit non-goal of
    // this migration; it is asserted here so that it stays visible.
    // =================================================================================================

    @Nested
    @DisplayName("Security posture - plaintext by parity, and nothing stronger smuggled in")
    class SecurityPosture {

        @Test
        @DisplayName("passwd is a plaintext String at the width CSUSR01Y declares")
        void thePasswordIsAPlaintextStringOfEight() {
            RecordComponent[] components = UserAddResponse.class.getRecordComponents();
            RecordComponent component = components[MAP_MEMBERS.indexOf("passwd")];
            assertThat(component.getName()).isEqualTo("passwd");
            assertThat(component.getType())
                    .as("a String - not a char[], not a wrapper, not an encoded form")
                    .isEqualTo(String.class);
            assertThat(UserAddResponse.PASSWD_LENGTH).isEqualTo(SEC_USR_PWD_LENGTH);
        }

        @Test
        @DisplayName("no hashing, encoding, cipher or security-framework type is reachable")
        void noSecurityFrameworkTypeIsReachable() {
            for (String name : reachableTypeNames()) {
                for (String marker : CREDENTIAL_MARKERS) {
                    assertThat(name)
                            .as("%s suggests %s; strengthening the comparison would change observable "
                                    + "behaviour and is out of scope", name, marker)
                            .doesNotContain(marker);
                }
            }
        }

        @ParameterizedTest(name = "[{index}] {0} is absent from the classpath")
        @ValueSource(strings = {"org.springframework.security.crypto.password.PasswordEncoder",
                               "org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder",
                               "org.springframework.security.core.Authentication"})
        @DisplayName("Spring Security is not on the classpath at all, so it cannot be reached")
        void springSecurityIsNotOnTheClasspath(String type) {
            // The plan's dependency set is closed and excludes spring-boot-starter-security. This is the
            // strongest available statement of gate G41: not merely that this type avoids hashing, but
            // that no hashing framework exists to be reached for.
            assertThat(EXCLUDED_SECURITY_TYPES).contains(type);
            assertThatExceptionOfType(ClassNotFoundException.class)
                    .isThrownBy(() -> Class.forName(type));
        }

        @Test
        @DisplayName("the record declares no field of its own beyond its components and constants")
        void noMutableStateIsDeclared() {
            for (Field field : UserAddResponse.class.getDeclaredFields()) {
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("%s must be final", field.getName())
                        .isTrue();
                if (!Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isPrivate(field.getModifiers()))
                            .as("the component field %s must be private final", field.getName())
                            .isTrue();
                }
            }
            // And this suite itself holds no mutable static state (B9): every field of the test class is
            // static final, and every collection it holds is immutable.
            for (Field field : UserAddResponseTest.class.getDeclaredFields()) {
                assertThat(Modifier.isStatic(field.getModifiers()) && Modifier.isFinal(
                        field.getModifiers()))
                        .as("%s in the test class must be static final", field.getName())
                        .isTrue();
            }
        }
    }

    // =================================================================================================
    // 13. DIAGNOSTICS.
    //
    // Carrying the credential in the clear is required for parity. Broadcasting it into a log line, an
    // exception message or a debugger view is not, and the two concerns separate cleanly.
    // =================================================================================================

    @Nested
    @DisplayName("Diagnostics - the credential is withheld, the identifier is not")
    class Diagnostics {

        @Test
        @DisplayName("toString withholds the password unconditionally, value and length alike")
        void toStringWithholdsThePassword() {
            String keyed = "plaintxt";
            UserAddResponse response = responseOf(withMember(blankMapValues(), "passwd", keyed),
                    NavigationContext.empty(), SIGN_ON_PROGRAM, MAPSET, MAP);
            assertThat(response.toString())
                    .as("the redaction marker stands in for the value")
                    .contains(SensitiveDiagnostics.REDACTED)
                    .doesNotContain(keyed)
                    .doesNotContain(keyed.toUpperCase(Locale.ROOT));
            assertThat(response.passwd())
                    .as("while the payload itself still carries it, because parity requires that")
                    .isEqualTo(keyed);
        }

        @Test
        @DisplayName("the two names are described by length, and the identifier is shown in full")
        void toStringDescribesNamesAndShowsTheIdentifier() {
            // A first and last name are personal data, so they are rendered as a length rather than as
            // text. A user identifier is not: it is the key of the record, it appears in the success
            // message the screen itself displays, and withholding it would make a diagnostic useless.
            String fName = codec().movePicX("Johnathan", UserAddResponse.F_NAME_LENGTH);
            UserAddResponse response = responseOf(
                    withMember(withMember(blankMapValues(), "fName", fName), "userId", "NEWUSR01"),
                    NavigationContext.empty(), SIGN_ON_PROGRAM, MAPSET, MAP);
            assertThat(response.toString())
                    .contains(SensitiveDiagnostics.describeText(fName))
                    .doesNotContain("Johnathan")
                    .contains("NEWUSR01")
                    .startsWith("UserAddResponse[");
        }

        @Test
        @DisplayName("a blank name is described as blank, not as an eight-character secret")
        void toStringDescribesABlankName() {
            // The other branch of the same policy: a field holding only spaces is reported as blank
            // rather than as a length, so a reader can tell an empty field from a withheld one.
            UserAddResponse response = blankResponse();
            assertThat(response.toString())
                    .contains(SensitiveDiagnostics.describeText(
                            spaces(UserAddResponse.F_NAME_LENGTH)))
                    .contains(SensitiveDiagnostics.REDACTED);
            assertThat(SensitiveDiagnostics.describeText(spaces(UserAddResponse.F_NAME_LENGTH)))
                    .as("the blank marker carries no length, so it cannot leak one")
                    .doesNotContain(String.valueOf(UserAddResponse.F_NAME_LENGTH));
        }

        @Test
        @DisplayName("a null name is described as absent rather than rendered as \"null\" text")
        void toStringDescribesAnAbsentName() {
            UserAddResponse response = responseOf(withMember(blankMapValues(), "fName", null),
                    NavigationContext.empty(), SIGN_ON_PROGRAM, MAPSET, MAP);
            assertThat(response.toString())
                    .contains(SensitiveDiagnostics.describeText(null))
                    .contains(SensitiveDiagnostics.REDACTED);
        }
    }

    // =================================================================================================
    // 14. IMMUTABLE COPIES.
    //
    // Sixteen withXxx methods, one per component. Each must replace exactly one value and carry the
    // other fifteen through untouched - a copy method that dropped a member would be an invisible way to
    // lose a screen field.
    // =================================================================================================

    @Nested
    @DisplayName("Immutable copies - one member replaced, fifteen carried through")
    class ImmutableCopies {

        @Test
        @DisplayName("each of the twelve map members has a copy method that changes only itself")
        void eachMapMemberHasAnIsolatedCopyMethod() {
            UserAddResponse base = sentScreen(MSG_DUPLICATE);
            List<UserAddResponse> copies = List.of(base.withTrnName("CU99"),
                    base.withTitle01("changed title one"),
                    base.withCurDate("01/02/03"),
                    base.withPgmName("COUSR99C"),
                    base.withTitle02("changed title two"),
                    base.withCurTime("04:05:06"),
                    base.withFName("Changed"),
                    base.withLName("Altered"),
                    base.withUserId("OTHER001"),
                    base.withPasswd("changed1"),
                    base.withUsrType(NavigationContext.USER_TYPE_ADMIN),
                    base.withErrMsg(narrowToErrMsg(MSG_UNABLE_TO_ADD)));
            assertThat(copies).hasSize(DFHMDF_NAMED);
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                UserAddResponse copy = copies.get(index);
                assertThat(copy)
                        .as("%s must actually change", MAP_MEMBERS.get(index))
                        .isNotEqualTo(base);
                for (int other = 0; other < DFHMDF_NAMED; other++) {
                    if (other == index) {
                        assertThat(mapValuesOf(copy).get(other))
                                .as("%s is the member that changed", MAP_MEMBERS.get(other))
                                .isNotEqualTo(mapValuesOf(base).get(other));
                    } else {
                        assertThat(mapValuesOf(copy).get(other))
                                .as("%s must be carried through untouched", MAP_MEMBERS.get(other))
                                .isEqualTo(mapValuesOf(base).get(other));
                    }
                }
                assertThat(copy.navigationContext()).isEqualTo(base.navigationContext());
                assertThat(copy.nextProgram()).isEqualTo(base.nextProgram());
                assertThat(copy.nextMapset()).isEqualTo(base.nextMapset());
                assertThat(copy.nextMap()).isEqualTo(base.nextMap());
            }
        }

        @Test
        @DisplayName("the four navigation members have copy methods that leave the screen alone")
        void theNavigationCopyMethods() {
            UserAddResponse base = sentScreen(MSG_DUPLICATE);
            NavigationContext other = returnToPrevScreenContext(NavigationContext.empty()
                    .withToProgram(ADMIN_MENU_PROGRAM));

            assertThat(base.withNavigationContext(other).navigationContext()).isEqualTo(other);
            assertThat(base.withNextProgram(ADMIN_MENU_PROGRAM).nextProgram())
                    .isEqualTo(ADMIN_MENU_PROGRAM);
            assertThat(base.withNextMapset("COADM01").nextMapset()).isEqualTo("COADM01");
            assertThat(base.withNextMap("COADM1A").nextMap()).isEqualTo("COADM1A");

            for (UserAddResponse copy : List.of(base.withNavigationContext(other),
                    base.withNextProgram(ADMIN_MENU_PROGRAM),
                    base.withNextMapset("COADM01"),
                    base.withNextMap("COADM1A"))) {
                assertThat(mapValuesOf(copy))
                        .as("no navigation copy touches a screen field")
                        .isEqualTo(mapValuesOf(base));
            }
        }

        @Test
        @DisplayName("a copy that changes nothing equals the original, and equality is by value")
        void anIdempotentCopyEqualsTheOriginal() {
            UserAddResponse base = sentScreen(MSG_UNABLE_TO_ADD);
            assertThat(base.withTrnName(base.trnName()))
                    .isEqualTo(base)
                    .hasSameHashCodeAs(base);
            assertThat(base.withNavigationContext(base.navigationContext())).isEqualTo(base);
            assertThat(base)
                    .as("a record's equality is componentwise, so two independent builds match")
                    .isEqualTo(sentScreen(MSG_UNABLE_TO_ADD))
                    .isNotEqualTo(sentScreen(MSG_DUPLICATE))
                    .isNotEqualTo(null)
                    .isNotEqualTo("UserAddResponse");
        }

        @Test
        @DisplayName("there is one copy method per component, and no other public mutator")
        void thereIsOneCopyMethodPerComponent() {
            List<String> copyMethods = Arrays.stream(UserAddResponse.class.getDeclaredMethods())
                    .filter(method -> Modifier.isPublic(method.getModifiers()))
                    .map(Method::getName)
                    .filter(name -> name.startsWith("with"))
                    .sorted()
                    .toList();
            assertThat(copyMethods)
                    .as("sixteen components, sixteen copy methods")
                    .hasSize(DFHMDF_NAMED + NAV_MEMBERS.size());
            for (String component : componentNames()) {
                String expected = "with" + Character.toUpperCase(component.charAt(0))
                        + component.substring(1);
                assertThat(copyMethods)
                        .as("%s must have a copy method", component)
                        .contains(expected);
            }
            // No setter, and no method that could mutate in place.
            assertThat(Arrays.stream(UserAddResponse.class.getDeclaredMethods())
                    .map(Method::getName)
                    .filter(name -> name.startsWith("set"))
                    .toList())
                    .as("a fixed-width payload has no setter")
                    .isEmpty();
        }
    }
}

