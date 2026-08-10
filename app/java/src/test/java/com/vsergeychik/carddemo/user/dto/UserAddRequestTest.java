package com.vsergeychik.carddemo.user.dto;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.user.model.SecUserRecord;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Size;
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
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Unit tests for {@link UserAddRequest} - the inbound payload of {@code POST /api/users}, CICS
 * transaction {@code CU01}, program {@code app/cbl/COUSR01C.cbl}, map {@code COUSR1A} of mapset
 * {@code COUSR01}: the Add User screen.
 *
 * <p>Three subjects define this file, and each is a mistake that a plausible-looking translation makes
 * silently. The identifier field on this map is spelled {@code USERID} and not the {@code USRIDIN} of
 * its sibling user screens. The input fields are ordered <em>names before identifier</em>, which is the
 * opposite of Update User and Delete User and is what decides which message a multi-blank request
 * produces. And the ordered five-message blank-field chain of {@code PROCESS-ENTER-KEY} is the reason
 * no presence constraint may exist on any member of this type.
 *
 * <h2>Project rules</h2>
 *
 * {@code review_rules} reports <strong>"No user rules provided."</strong> - that one line is the whole
 * document, and there is nothing further to page through. No rule is therefore invented here, and the
 * absence of rules is emphatically <em>not</em> treated as licence to assert less. The binding
 * constraints are the enterprise best-practice substitutes {@code B1}-{@code B12} recorded in the
 * plan, each named below with the single thing it requires of this file. The plan holds the full text
 * of every practice; only the ruling is restated.
 *
 * <ul>
 *   <li><strong>B1</strong> - imports are confined to the JDK, JUnit Jupiter, AssertJ,
 *       {@code jakarta.validation} and the Jackson already on the test classpath. No new coordinate is
 *       introduced and nothing is drawn from the exclusion list. Mockito is available and deliberately
 *       unused: this payload has no collaborator to stand in for, so a mock would only obscure that
 *       every decision here is reachable by construction.</li>
 *   <li><strong>B2</strong> - the JUnit 5 Jupiter API only, even where a later line is published.</li>
 *   <li><strong>B3</strong> - the reference tree is neither written nor <em>read</em>. Every
 *       expectation below is a {@code private static final} constant carrying the file and line it was
 *       transcribed from, so this suite is hermetic and cannot depend on which directory the build was
 *       launched from. This retires a divergence the plan recorded: an earlier form of this file
 *       parsed {@code app/cpy-bms/COUSR01.CPY} and {@code app/bms/COUSR01.bms} at run time by walking
 *       up from the working directory, and {@code SignOnRequestTest} notes that difference under its
 *       own B3 ruling. Both forms prove the same contract; the hermetic one is this file's ruling and
 *       is now what it does.</li>
 *   <li><strong>B4</strong> - conflicts are documented rather than resolved. Three are relevant, and
 *       all three are set out under "Conflicts recorded, not reconciled" below.</li>
 *   <li><strong>B5</strong> - no member is asserted into or out of existence for symmetry with a
 *       sibling payload. The screen-field census is exactly twelve: nothing is added to match
 *       {@code COUSR02} or {@code COUSR03}, and nothing is removed. Where the declared type differs
 *       from a sibling - {@link UserAddRequest} publishes {@link UserAddRequest#pgmEnter()} and
 *       {@link UserAddRequest#pgmReenter()} where {@code SignOnRequest} publishes differently named
 *       predicates and a commarea-length accessor - the type is asserted as declared rather than
 *       nudged toward its neighbour.</li>
 *   <li><strong>B6</strong> - the security posture is neither weakened nor strengthened. The password
 *       stays a plaintext {@code PIC X(08)} member; see {@link SecurityPosture}.</li>
 *   <li><strong>B7</strong> - nothing here reads a wall clock, draws a random value or depends on
 *       another test having run. The two time-derived expectations are driven from
 *       {@link Clock#fixed(Instant, java.time.ZoneId)} through {@link DateHeader}, which is the seam
 *       that exists precisely because {@code config.WebConfig} publishes the module's single
 *       {@link Clock} bean rather than letting a caller reach for the system clock directly.</li>
 *   <li><strong>B8</strong> - every codec call names its {@link Charset} explicitly; no overload that
 *       omits it is used and no platform default is relied on. Every import is written out
 *       individually - there is no wildcard import in this file - and no dataset name appears in
 *       it.</li>
 *   <li><strong>B9</strong> - every field of this class is {@code static final} and immutable. No
 *       state is shared between test methods; JUnit's per-method instance lifecycle does the
 *       isolating, and each case that writes bytes takes its own fresh record.</li>
 *   <li><strong>B10</strong> - this suite is a deliverable in its own right, shipped alongside the
 *       type it measures rather than added afterwards. That is what makes a drift from the mapset
 *       traceable to the decision that caused it instead of surfacing later as an unexplained
 *       difference in a parallel run.</li>
 *   <li><strong>B11</strong> - fixed-width and truncation work goes through {@link FixedWidthCodec}
 *       and {@link FixedWidthRecord}. No third-party copybook parser is used, and no assertion
 *       substitutes {@link String#substring(int, int)} for a COBOL {@code MOVE}.</li>
 *   <li><strong>B12</strong> - the environmental limit is stated rather than absorbed. See the
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
 *   <li>{@code app/cpy-bms/COUSR01.CPY} - {@code 01 COUSR1AI} at line 17, the twelve-byte
 *       {@code TIOAPFX} filler at line 18, the twelve {@code xxxI} items at lines 24, 30, 36, 42, 48,
 *       54, 60, 66, 72, 78, 84 and 90, the twelve per-field {@code REDEFINES} at lines 21, 27, 33, 39,
 *       45, 51, 57, 63, 69, 75, 81 and 87, and the group-level
 *       {@code 01 COUSR1AO REDEFINES COUSR1AI} at line 91.</li>
 *   <li>{@code app/bms/COUSR01.bms} - twenty-eight {@code DFHMDF} definitions of which the twelve
 *       name-labelled ones sit at lines 34, 38, 47, 57, 61, 70, 84, 97, 111, 126, 141 and 151, with
 *       their {@code LENGTH=} operands at lines 36, 40, 49, 59, 63, 72, 87, 100, 114, 129, 144 and
 *       153.</li>
 *   <li>{@code app/cbl/COUSR01C.cbl} - {@code WS-PGMNAME} at 36, {@code WS-TRANID} at 37,
 *       {@code WS-MESSAGE PIC X(80)} at 38, the disabled {@code DFHATTR} include at 57, the
 *       {@code EIBCALEN} test at 78, the re-enter test at 83, the {@code EIBAID} evaluation at 90-103,
 *       {@code PROCESS-ENTER-KEY} at 115 with its {@code EVALUATE TRUE} at 117-151, the five plain
 *       {@code MOVE}s at 154-158, the two commented-out {@code MOVE}s at 172-173, the narrowing
 *       {@code MOVE} into {@code ERRMSGO} at 188, the map and mapset at 191-192, the title moves at
 *       218-219 and the date and time composition at 223-233.</li>
 *   <li>{@code app/cpy/CSUSR01Y.cpy} - {@code 01 SEC-USER-DATA} at 17 and its six items at 18-23.</li>
 *   <li>{@code app/cpy/COCOM01Y.cpy} - {@code CARDDEMO-COMMAREA} at 19-44, the program-context
 *       condition names at 29-31 and the two {@code PIC X(7)} map names at 43-44.</li>
 *   <li>{@code app/csd/CARDDEMO.CSD} - {@code DEFINE PROGRAM(COUSR01C)} at 285 and
 *       {@code DEFINE TRANSACTION(CU01) ... PROGRAM(COUSR01C)} at 459-460.</li>
 * </ul>
 *
 * <h2>Conflicts recorded, not reconciled (B4)</h2>
 *
 * <ul>
 *   <li><strong>A referenced-but-disabled copybook.</strong> {@code COUSR01C.cbl:55-56} copy
 *       {@code DFHAID} and {@code DFHBMSCA}, but line 57 reads {@code *COPY DFHATTR.} - commented out.
 *       The identical disabled include appears at {@code COSGN00C.cbl:59}. Both are recorded and left
 *       exactly as they stand: neither is reconciled with the programs that do copy {@code DFHATTR},
 *       and nothing in this file asserts that a {@code DFHATTR}-only constant is in use on this
 *       screen. Consistently with that, this suite does not touch {@code common.BmsAttributes} at
 *       all.</li>
 *   <li><strong>Two commented-out statements.</strong> {@code COUSR01C.cbl:172-173} read
 *       {@code *    MOVE WS-USER-ID   TO CDEMO-USER-ID} and
 *       {@code *    MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE}, inside {@code RETURN-TO-PREV-SCREEN}. They
 *       are disabled, so the transfer at {@code COUSR01C:175-178} leaves {@code CDEMO-USER-ID} and
 *       {@code CDEMO-USER-TYPE} untouched. That is documented here and asserted nowhere: this file
 *       does not claim the commented behaviour happens, does not claim it should, and does not test
 *       the transfer at all - {@code user.UserAddControllerTest} owns it.</li>
 *   <li><strong>Three declared members that diverge from this file's brief.</strong> The brief
 *       describes an enter-versus-re-enter flag as a payload member, a commarea-length accessor and an
 *       eight-character password mask. The declared type does none of those three: the context flag
 *       lives inside the communication area and is read through {@link UserAddRequest#pgmEnter()} and
 *       {@link UserAddRequest#pgmReenter()} rather than duplicated beside it, there is no
 *       commarea-length accessor, and {@link UserAddRequest#PASSWORD_MASK} is
 *       {@link SensitiveDiagnostics#REDACTED}, which withholds the length as well as the value. The
 *       declared members are ground truth: each is asserted as declared and the divergence is
 *       commented at the case that meets it, rather than the main class being edited to match a
 *       description of it.</li>
 * </ul>
 *
 * <h2>Scope: this file tests the type, not the program</h2>
 *
 * A plain JUnit 5 suite over a value type. No Spring context, no {@code @SpringBootTest}, no
 * {@code @WebMvcTest}, no {@code MockMvc}, no controller, no service and no repository appears here.
 * {@code user.UserAddControllerTest} already owns the HTTP projection, the five message texts, the
 * cursor placement, the {@code USRSEC} write and its {@code DUPKEY}/{@code DUPREC} outcome; restating
 * any of that here would be duplication that reports one defect twice and drifts independently. The
 * subject is {@link UserAddRequest} itself.
 *
 * <p>The package matters for coverage. The module's JaCoCo configuration declares a {@code BRANCH}
 * ratio rule at both {@code BUNDLE} and {@code PACKAGE} level, so {@code user}, {@code user.model} and
 * {@code user.dto} are each measured on their own and no package can hide behind a better-covered
 * neighbour. {@code user.dto} needs its own direct instruments, and it has two branching surfaces of
 * its own: the two read-through context predicates, and the width constraints.
 *
 * <p>Two things are deliberately absent for the same reason. <strong>No optimistic-concurrency
 * assertion appears anywhere in this file</strong>: the re-read-and-compare check lives in paragraph
 * {@code 9300-CHECK-CHANGE-IN-REC}, which exists in {@code app/cbl/COCRDUPC.cbl} and in none of the
 * five user programs - a verified count of zero in {@code COSGN00C}, {@code COUSR00C},
 * {@code COUSR01C}, {@code COUSR02C} and {@code COUSR03C} - so no version column, no ETag and no
 * conflict-detection expectation belongs here. And <strong>the twelve map members are not the whole
 * payload</strong>: the two conversation-state members join them, so the serialised form names
 * fourteen properties rather than twelve. That is the declared shape and the shape the stateless
 * design requires; see {@link ConversationState}.
 *
 * <h2>Note on normalisation, which this type must not perform</h2>
 *
 * {@code COUSR01C.cbl:154-158} executes five <strong>plain</strong> {@code MOVE}s into
 * {@code SEC-USR-ID}, {@code SEC-USR-FNAME}, {@code SEC-USR-LNAME}, {@code SEC-USR-PWD} and
 * {@code SEC-USR-TYPE}, with no {@code FUNCTION UPPER-CASE} anywhere in the program. The sign-on
 * program is deliberately different: {@code COSGN00C.cbl:132-137} upper-cases both the identifier and
 * the password before comparing. That asymmetry between two programs on the same file is real, is
 * preserved, and belongs to the two services. Nothing here asserts case folding, and nothing here
 * should ever be changed to.
 */
@DisplayName("UserAddRequest - the COUSR01 (CU01) Add User inbound payload")
class UserAddRequestTest {

    // =================================================================================================
    // TRANSCRIBED CONSTANTS (B3, B12).
    //
    // Every value below was read from the reference tree once, by hand, and is carried here with the
    // file and line it came from. Nothing in this file opens a file at run time.
    // =================================================================================================

    /**
     * The code page of the symbolic map and of every codec call in this file, named explicitly (B8).
     *
     * <p>{@code app/data/ASCII} is the authoritative fixture form for this migration, so the ASCII
     * code page is the right one for a screen-contract test. It is never the platform default: a
     * default charset would make these assertions pass or fail according to the locale of the machine
     * that ran them.
     */
    private static final Charset MAP_CHARSET = StandardCharsets.US_ASCII;

    /** {@code MAP('COUSR1A')} of {@code app/cbl/COUSR01C.cbl:191} and {@code :204}. */
    private static final String MAP_NAME = "COUSR1A";

    /** {@code MAPSET('COUSR01')} of {@code app/cbl/COUSR01C.cbl:192} and {@code :205}. */
    private static final String MAPSET_NAME = "COUSR01";

    /**
     * {@code WS-TRANID PIC X(04) VALUE 'CU01'} of {@code app/cbl/COUSR01C.cbl:37}.
     *
     * <p>Corroborated independently by {@code app/csd/CARDDEMO.CSD:459-460},
     * {@code DEFINE TRANSACTION(CU01) ... PROGRAM(COUSR01C)}.
     */
    private static final String TRANSACTION_ID = "CU01";

    /**
     * {@code WS-PGMNAME PIC X(08) VALUE 'COUSR01C'} of {@code app/cbl/COUSR01C.cbl:36}.
     *
     * <p>Corroborated by {@code app/csd/CARDDEMO.CSD:285}, {@code DEFINE PROGRAM(COUSR01C)}.
     */
    private static final String PROGRAM_NAME = "COUSR01C";

    /**
     * Total {@code DFHMDF} definitions in {@code app/bms/COUSR01.bms}.
     *
     * <p>Twenty-eight, of which only {@value #DFHMDF_NAMED} carry a name label in column one. The
     * other sixteen are literal {@code INITIAL} screen furniture - the {@code 'Tran:'},
     * {@code 'Date:'}, {@code 'Prog:'} and {@code 'Time:'} captions, the {@code 'Add User'} heading,
     * the {@code 'First Name:'}, {@code 'Last Name:'}, {@code 'User ID:'}, {@code 'Password:'} and
     * {@code 'User Type:'} prompts, the {@code '(8 Char)'} and {@code '(A=Admin, U=User)'} hints and
     * the function-key legend. A field with no label has no symbolic-map item and therefore cannot be
     * a payload member: the terminal paints it and the program never reads it.
     */
    private static final int DFHMDF_TOTAL = 28;

    /** Name-labelled {@code DFHMDF} definitions, and so the number of payload members: twelve. */
    private static final int DFHMDF_NAMED = 12;

    /**
     * The name-labelled {@code DFHMDF} field names of {@code app/bms/COUSR01.bms}, in mapset order.
     *
     * <p>Lines 34, 38, 47, 57, 61, 70, 84, 97, 111, 126, 141 and 151. This is the screen's own order,
     * top to bottom, and it is the order the symbolic map repeats.
     */
    private static final List<String> SCREEN_FIELDS = List.of(
            "TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME",
            "FNAME", "LNAME", "USERID", "PASSWD", "USRTYPE", "ERRMSG");

    /**
     * The {@code xxxI} item names of {@code 01 COUSR1AI} in {@code app/cpy-bms/COUSR01.CPY}, in
     * copybook order - lines 24, 30, 36, 42, 48, 54, 60, 66, 72, 78, 84 and 90.
     *
     * <p>Each is its screen field's name with {@code I} appended, which is the BMS convention and the
     * reason the two lists above and below stay in step.
     */
    private static final List<String> SYMBOLIC_MAP_ITEMS = List.of(
            "TRNNAMEI", "TITLE01I", "CURDATEI", "PGMNAMEI", "TITLE02I", "CURTIMEI",
            "FNAMEI", "LNAMEI", "USERIDI", "PASSWDI", "USRTYPEI", "ERRMSGI");

    /** The twelve record component names that project the map, in declaration order. */
    private static final List<String> MAP_MEMBERS = List.of(
            "trnName", "title01", "curDate", "pgmName", "title02", "curTime",
            "fName", "lName", "userId", "passwd", "usrType", "errMsg");

    /**
     * The two conversation-state components, which follow the twelve map members.
     *
     * <p>These are the sole deliberate exception to "every member traces to a {@code DFHMDF}": they
     * carry what CICS kept between two invocations of a pseudo-conversational program, and they are
     * what makes the Java endpoint stateless.
     */
    private static final List<String> STATE_MEMBERS = List.of("navigationContext", "aid");

    /** Fourteen components: the twelve map members then the two state members. */
    private static final int COMPONENT_COUNT = DFHMDF_NAMED + 2;

    /**
     * The twelve declared widths, in symbolic-map order.
     *
     * <p>Every width is stated <strong>twice</strong> in the reference tree and the two statements
     * agree: the {@code xxxI} {@code PICTURE} clause in {@code app/cpy-bms/COUSR01.CPY} and the
     * {@code LENGTH=} operand of the matching {@code DFHMDF} in {@code app/bms/COUSR01.bms}. That
     * independent corroboration is what makes these numbers transcribed rather than assumed, and both
     * sources are asserted separately below.
     */
    private static final List<Integer> DECLARED_WIDTHS =
            List.of(4, 40, 8, 8, 40, 8, 20, 20, 8, 8, 1, 78);

    /** {@code app/cpy-bms/COUSR01.CPY} line numbers of the twelve {@code xxxI} items, in order. */
    private static final List<Integer> COPYBOOK_LINES =
            List.of(24, 30, 36, 42, 48, 54, 60, 66, 72, 78, 84, 90);

    /** {@code app/bms/COUSR01.bms} line numbers of the twelve name-labelled {@code DFHMDF}s. */
    private static final List<Integer> MAPSET_LINES =
            List.of(34, 38, 47, 57, 61, 70, 84, 97, 111, 126, 141, 151);

    /** {@code app/bms/COUSR01.bms} line numbers of the matching {@code LENGTH=} operands. */
    private static final List<Integer> MAPSET_LENGTH_LINES =
            List.of(36, 40, 49, 59, 63, 72, 87, 100, 114, 129, 144, 153);

    /**
     * {@code app/cpy-bms/COUSR01.CPY} line numbers of the twelve per-field {@code REDEFINES}.
     *
     * <p>Each is a {@code 02 FILLER REDEFINES xxxF.} whose {@code 03 xxxA PICTURE X.} follows on the
     * next line. There is a thirteenth {@code REDEFINES} in the copybook - the group-level
     * {@code 01 COUSR1AO REDEFINES COUSR1AI} at line 91, which overlays the whole input view with the
     * output view. That one is not this file's subject: it belongs to {@code UserAddResponseTest},
     * because it is the response projection. Twelve here, thirteen in the copybook, and the difference
     * is deliberate.
     */
    private static final List<Integer> REDEFINES_LINES =
            List.of(21, 27, 33, 39, 45, 51, 57, 63, 69, 75, 81, 87);

    /** The group-level {@code REDEFINES} line, recorded so the twelve-versus-thirteen split is exact. */
    private static final int GROUP_REDEFINES_LINE = 91;

    // =================================================================================================
    // SYMBOLIC-MAP GEOMETRY. app/cpy-bms/COUSR01.CPY declares one five-item group per field, so the
    // whole input view is 12 + 12 x 7 + 243 bytes. Each constant is a copybook literal, not arithmetic.
    // =================================================================================================

    /** {@code 02 FILLER PIC X(12)} at {@code app/cpy-bms/COUSR01.CPY:18} - the {@code TIOAPFX} prefix. */
    private static final int TIOAPFX_PREFIX_LENGTH = 12;

    /** {@code 02 xxxL COMP PIC S9(4)} - a binary halfword, two bytes, never a payload member. */
    private static final int LENGTH_ITEM_LENGTH = 2;

    /** {@code 02 xxxF PICTURE X} - the attribute and flag byte, one byte. */
    private static final int ATTRIBUTE_ITEM_LENGTH = 1;

    /** {@code 02 FILLER PICTURE X(4)} - the reserved span between the flag byte and the data. */
    private static final int ATTRIBUTE_FILLER_LENGTH = 4;

    /**
     * The per-field prefix ahead of every {@code xxxI} item: {@code 2 + 1 + 4 = 7} bytes.
     *
     * <p>Seven is also the prefix of the output view - {@code FILLER PICTURE X(3)} plus the four
     * attribute bytes {@code xxxC}, {@code xxxP}, {@code xxxH} and {@code xxxV} - which is exactly
     * what lets {@code 01 COUSR1AO} overlay {@code 01 COUSR1AI} field for field at line 91.
     */
    private static final int FIELD_PREFIX_LENGTH =
            LENGTH_ITEM_LENGTH + ATTRIBUTE_ITEM_LENGTH + ATTRIBUTE_FILLER_LENGTH;

    /** {@code 4 + 40 + 8 + 8 + 40 + 8 + 20 + 20 + 8 + 8 + 1 + 78} - the twelve data widths. */
    private static final int PAYLOAD_WIDTH_TOTAL = 243;

    /** {@code 12 + 12 x 7 + 243} - the whole of {@code 01 COUSR1AI}. */
    private static final int SYMBOLIC_MAP_LENGTH = 339;

    // =================================================================================================
    // THE USRSEC RECORD THE FIVE INPUT FIELDS POPULATE - app/cpy/CSUSR01Y.cpy:17-23.
    // =================================================================================================

    /** {@code 05 SEC-USR-ID PIC X(08)} at {@code app/cpy/CSUSR01Y.cpy:18}. */
    private static final int SEC_USR_ID_LENGTH = 8;

    /** {@code 05 SEC-USR-FNAME PIC X(20)} at {@code app/cpy/CSUSR01Y.cpy:19}. */
    private static final int SEC_USR_FNAME_LENGTH = 20;

    /** {@code 05 SEC-USR-LNAME PIC X(20)} at {@code app/cpy/CSUSR01Y.cpy:20}. */
    private static final int SEC_USR_LNAME_LENGTH = 20;

    /** {@code 05 SEC-USR-PWD PIC X(08)} at {@code app/cpy/CSUSR01Y.cpy:21} - plaintext, deliberately. */
    private static final int SEC_USR_PWD_LENGTH = 8;

    /** {@code 05 SEC-USR-TYPE PIC X(01)} at {@code app/cpy/CSUSR01Y.cpy:22}. */
    private static final int SEC_USR_TYPE_LENGTH = 1;

    /** {@code 05 SEC-USR-FILLER PIC X(23)} at {@code app/cpy/CSUSR01Y.cpy:23} - part of the 80. */
    private static final int SEC_USR_FILLER_LENGTH = 23;

    /** {@code 01 SEC-USER-DATA} totals eighty bytes: {@code 8 + 20 + 20 + 8 + 1 + 23}. */
    private static final int SEC_USER_DATA_LENGTH = 80;

    // =================================================================================================
    // THE PROGRAM'S OWN MESSAGE FIELD, AND THE ORDERED BLANK-FIELD CHAIN IT CARRIES.
    // =================================================================================================

    /**
     * {@code 05 WS-MESSAGE PIC X(80) VALUE SPACES} at {@code app/cbl/COUSR01C.cbl:38}.
     *
     * <p>Two characters wider than the {@code ERRMSG} field it is moved into at
     * {@code app/cbl/COUSR01C.cbl:188}, which is the whole of the fourth width trap.
     */
    private static final int WS_MESSAGE_LENGTH = 80;

    /**
     * The five arms of {@code PROCESS-ENTER-KEY}, in the order {@code EVALUATE TRUE} evaluates them.
     *
     * <p>{@code app/cbl/COUSR01C.cbl:115} opens the paragraph, {@code :117} opens the
     * {@code EVALUATE TRUE}, and the arms test - in this order - {@code FNAMEI} at 118,
     * {@code LNAMEI} at 124, {@code USERIDI} at 130, {@code PASSWDI} at 136 and {@code USRTYPEI} at
     * 142, with {@code WHEN OTHER} at 148. The first matching arm wins, which is why the order is
     * behaviour and not presentation.
     */
    private static final List<String> BLANK_CHAIN_ITEMS =
            List.of("FNAMEI", "LNAMEI", "USERIDI", "PASSWDI", "USRTYPEI");

    /**
     * The five message texts, byte for byte, from {@code app/cbl/COUSR01C.cbl:120}, {@code :126},
     * {@code :132}, {@code :138} and {@code :144}.
     *
     * <p>They are transcribed here as evidence for the one assertion this file makes about them - that
     * each is short enough to travel in {@code ERRMSG} intact, so no arm of the chain loses its message
     * to the 80-to-78 narrowing. Producing them is the controller's work and
     * {@code user.UserAddControllerTest} asserts that; this file does not re-assert it.
     */
    private static final List<String> BLANK_CHAIN_MESSAGES = List.of(
            "First Name can NOT be empty...",
            "Last Name can NOT be empty...",
            "User ID can NOT be empty...",
            "Password can NOT be empty...",
            "User Type can NOT be empty...");

    // =================================================================================================
    // DERIVED FIXTURES. Built once, immutable, side-effect free.
    // =================================================================================================

    /**
     * The copybook's geometry as a validated layout, and the subject of {@link RedefinesOverlays}.
     *
     * <p>Declares <strong>every</strong> byte of {@code 01 COUSR1AI} in copybook order: the
     * {@code TIOAPFX} prefix, and per field the {@code xxxL} halfword, the {@code xxxF} flag byte, the
     * {@code xxxA} overlay over that same byte, the four-byte filler and the {@code xxxI} item.
     * Constructing it is itself the geometry assertion - {@link FixedWidthRecord.RecordLayout} refuses
     * a gap, an unintended overlap, an overlay reaching past declared storage, a repeated referable
     * name and any total other than {@value #SYMBOLIC_MAP_LENGTH}.
     *
     * <p>The {@code xxxL} halfword is declared as {@code FILLER} rather than under its own name on
     * purpose. It is {@code COMP} - binary - and {@link FixedWidthRecord.PictureKind} deliberately has
     * no binary kind, because no persisted record in this estate holds one; describing two bytes of
     * halfword as character or as zoned digits would be a misdescription. Here it is reserved storage,
     * its name is asserted from {@link #SYMBOLIC_MAP_ITEMS}' siblings instead, and it is in any case
     * never a payload member - which is the point {@link MetadataIsNotPayload} makes.
     *
     * <p>Immutable, so publishing it as a constant introduces no shared mutable state (B9):
     * {@link FixedWidthRecord.RecordLayout} is a record over an unmodifiable span list, and every case
     * that writes bytes allocates its own {@link FixedWidthRecord} from it.
     */
    private static final FixedWidthRecord.RecordLayout SYMBOLIC_MAP_LAYOUT = symbolicMapLayout();

    /**
     * A fixed instant, so the two time-derived expectations in this suite are exact (B7).
     *
     * <p>The value is the timestamp in the version footer of {@code app/cbl/COUSR01C.cbl:298}, which
     * makes it traceable rather than arbitrary. Read through
     * {@link Clock#fixed(Instant, java.time.ZoneId)} at {@link ZoneOffset#UTC}, so {@code CURDATE}
     * renders {@code 07/19/22} and {@code CURTIME} renders {@code 23:12:34} on every run, on every
     * machine, in any order.
     */
    private static final Instant FIXED_INSTANT = Instant.parse("2022-07-19T23:12:34Z");

    /** {@code MM/DD/YY} for {@link #FIXED_INSTANT} - what {@code COUSR01C.cbl:227} would move. */
    private static final String FIXED_CURDATE = "07/19/22";

    /** {@code HH:MM:SS} for {@link #FIXED_INSTANT} - what {@code COUSR01C.cbl:233} would move. */
    private static final String FIXED_CURTIME = "23:12:34";

    /**
     * The complete set of JSON member names this payload may emit: the twelve map members and the two
     * state members, and nothing else.
     */
    private static final Set<String> EXPECTED_JSON_MEMBERS = expectedJsonMembers();

    // =================================================================================================
    // Construction of the constants above. Static, side-effect free, called once each.
    // =================================================================================================

    private static FixedWidthRecord.RecordLayout symbolicMapLayout() {
        List<FixedWidthRecord.FieldSpan> spans = new ArrayList<>();
        // 02 FILLER PIC X(12) - COUSR01.CPY:18.
        spans.add(FixedWidthRecord.FieldSpan.filler(0, TIOAPFX_PREFIX_LENGTH));
        int cursor = TIOAPFX_PREFIX_LENGTH;
        for (int index = 0; index < DFHMDF_NAMED; index++) {
            String field = SCREEN_FIELDS.get(index);
            // 02 xxxL COMP PIC S9(4) - a binary halfword, declared as reserved storage.
            spans.add(FixedWidthRecord.FieldSpan.filler(cursor, LENGTH_ITEM_LENGTH));
            cursor += LENGTH_ITEM_LENGTH;
            // 02 xxxF PICTURE X, then 02 FILLER REDEFINES xxxF / 03 xxxA PICTURE X over that byte.
            FixedWidthRecord.FieldSpan flag = FixedWidthRecord.FieldSpan.alphanumeric(
                    field + "F", cursor, ATTRIBUTE_ITEM_LENGTH);
            spans.add(flag);
            spans.add(flag.redefinedAs(field + "A", FixedWidthRecord.PictureKind.ALPHANUMERIC));
            cursor += ATTRIBUTE_ITEM_LENGTH;
            // 02 FILLER PICTURE X(4).
            spans.add(FixedWidthRecord.FieldSpan.filler(cursor, ATTRIBUTE_FILLER_LENGTH));
            cursor += ATTRIBUTE_FILLER_LENGTH;
            // 02 xxxI PIC X(n).
            spans.add(FixedWidthRecord.FieldSpan.alphanumeric(
                    SYMBOLIC_MAP_ITEMS.get(index), cursor, DECLARED_WIDTHS.get(index)));
            cursor += DECLARED_WIDTHS.get(index);
        }
        // The DECLARED total is passed, never the cursor this loop happened to reach. Passing the
        // cursor would make the layout self-consistent with whatever the constants above sum to and
        // would catch nothing; passing SYMBOLIC_MAP_LENGTH makes RecordLayout compare the transcribed
        // geometry against the transcribed total, so one wrong constant fails class initialisation
        // instead of quietly shifting every offset after it.
        return FixedWidthRecord.RecordLayout.of(SYMBOLIC_MAP_LENGTH,
                spans.toArray(FixedWidthRecord.FieldSpan[]::new));
    }

    private static Set<String> expectedJsonMembers() {
        Set<String> members = new LinkedHashSet<>(MAP_MEMBERS);
        members.addAll(STATE_MEMBERS);
        return Set.copyOf(members);
    }

    // =================================================================================================
    // Shared, stateless helpers. Every one returns a fresh value; none caches, mutates or memoises.
    // =================================================================================================

    /**
     * A codec over the explicitly named code page (B8). A fresh instance per call: the codec is cheap
     * and sharing one would be shared state for no benefit.
     */
    private static FixedWidthCodec codec() {
        return new FixedWidthCodec(MAP_CHARSET);
    }

    /**
     * An {@link ObjectMapper} configured exactly as {@code config.WebConfig} configures the
     * application's shared one, and for the reasons that class documents.
     *
     * <p>A default mapper would be the wrong instrument here and would make this suite assert the
     * wrong thing. Four settings matter and all four are stated rather than inherited.
     * {@code USE_BIG_DECIMAL_FOR_FLOATS} and {@code WRITE_BIGDECIMAL_AS_PLAIN} are enabled so no value
     * derived from a {@code PIC S9(p)V99} clause could route through a {@code double} or serialise in
     * exponent notation - this screen has no numeric field at all, and the settings are still stated,
     * because the mapper is the module's and not this file's. {@code FAIL_ON_TRAILING_TOKENS} is
     * enabled so a malformed body is refused rather than half-read. And, decisively for this suite,
     * {@code ACCEPT_EMPTY_STRING_AS_NULL_OBJECT} is <strong>disabled</strong> so an empty or
     * all-spaces {@code PIC X(n)} value stays the real screen data it is instead of becoming
     * {@code null}: the blank-value cases below turn on that distinction, and a default mapper would
     * quietly defeat them.
     *
     * <p>No naming strategy is applied, so every property name still traces 1:1 to an {@code xxxI}
     * item. No inclusion filter is applied, so an absent member is emitted rather than dropped. No
     * trimming converter is registered, so trailing padding survives.
     */
    private static ObjectMapper webConfigEquivalentMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        mapper.configure(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN, true);
        mapper.disable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT);
        return mapper;
    }

    /** Builds a request from the twelve map values in component order, plus the two state members. */
    private static UserAddRequest requestOf(List<String> mapValues, NavigationContext context,
            String aid) {
        return new UserAddRequest(mapValues.get(0), mapValues.get(1), mapValues.get(2),
                mapValues.get(3), mapValues.get(4), mapValues.get(5), mapValues.get(6),
                mapValues.get(7), mapValues.get(8), mapValues.get(9), mapValues.get(10),
                mapValues.get(11), context, aid);
    }

    /** The twelve map members of a request, in component order. Permits {@code null} entries. */
    private static List<String> mapValuesOf(UserAddRequest request) {
        return Arrays.asList(request.trnName(), request.title01(), request.curDate(),
                request.pgmName(), request.title02(), request.curTime(), request.fName(),
                request.lName(), request.userId(), request.passwd(), request.usrType(),
                request.errMsg());
    }

    /** Twelve empty strings - the payload a client sends having keyed nothing at all. */
    private static List<String> blankMapValues() {
        List<String> values = new ArrayList<>(DFHMDF_NAMED);
        for (int index = 0; index < DFHMDF_NAMED; index++) {
            values.add("");
        }
        return values;
    }

    /** Twelve {@code null}s - distinct from blank, and equally something the payload must carry. */
    private static List<String> nullMapValues() {
        return Arrays.asList(new String[DFHMDF_NAMED]);
    }

    /** Twelve values each padded to its own declared width - the image of a fully painted screen. */
    private static List<String> paddedMapValues() {
        List<String> values = new ArrayList<>(DFHMDF_NAMED);
        for (int index = 0; index < DFHMDF_NAMED; index++) {
            values.add(" ".repeat(DECLARED_WIDTHS.get(index)));
        }
        return values;
    }

    /** Replaces one member of a value list by its component name, returning the modified list. */
    private static List<String> withMember(List<String> values, String member, String value) {
        List<String> copy = new ArrayList<>(values);
        copy.set(MAP_MEMBERS.indexOf(member), value);
        return copy;
    }

    /**
     * Twelve values, each exactly its own declared width, so an instance built from them is a faithful
     * image of a painted screen rather than a convenient shorthand.
     *
     * <p>The six header values are what {@code POPULATE-HEADER-INFO} at
     * {@code app/cbl/COUSR01C.cbl:214-233} would have placed there, and the five input values are as a
     * user would have keyed them. Every one goes through {@link FixedWidthCodec#movePicX(String, int)}
     * with an explicit code page (B8, B11) rather than being hand-padded, so the fixture cannot state a
     * width the codec would not produce.
     */
    private static List<String> populatedMapValues() {
        FixedWidthCodec codec = codec();
        return Arrays.asList(
                TRANSACTION_ID,
                ScreenTitles.CCDA_TITLE01,
                FIXED_CURDATE,
                PROGRAM_NAME,
                ScreenTitles.CCDA_TITLE02,
                FIXED_CURTIME,
                codec.movePicX("JANE", UserAddRequest.FNAME_LENGTH),
                codec.movePicX("DOE", UserAddRequest.LNAME_LENGTH),
                "NEWUSR01",
                "NOTAREAL",
                NavigationContext.USER_TYPE_USER,
                codec.movePicX(BLANK_CHAIN_MESSAGES.get(0), UserAddRequest.ERRMSG_LENGTH));
    }

    /**
     * A fully populated request: {@link #populatedMapValues()} carried with a re-entered communication
     * area that names this screen, and the resolved {@code ENTER} token.
     */
    private static UserAddRequest populatedRequest() {
        return requestOf(populatedMapValues(),
                NavigationContext.empty()
                        .withFromTranid(TRANSACTION_ID)
                        .withFromProgram(PROGRAM_NAME)
                        .withLastMap(MAP_NAME)
                        .withLastMapset(MAPSET_NAME)
                        .withPgmReenter(),
                PfKeyResolver.AidKey.ENTER.token());
    }

    /** Runs Bean Validation over one instance, closing the factory it opened. */
    private static Set<ConstraintViolation<UserAddRequest>> validate(UserAddRequest request) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(request);
        }
    }

    /** Serialises through the configured mapper, translating the checked failure. */
    private static String serialise(UserAddRequest request) {
        try {
            return webConfigEquivalentMapper().writeValueAsString(request);
        } catch (IOException failure) {
            throw new UncheckedIOException("Serialising a UserAddRequest must not fail", failure);
        }
    }

    /** Deserialises through the configured mapper, translating the checked failure. */
    private static UserAddRequest deserialise(String json) {
        try {
            return webConfigEquivalentMapper().readValue(json, UserAddRequest.class);
        } catch (IOException failure) {
            throw new UncheckedIOException("Deserialising a UserAddRequest must not fail", failure);
        }
    }

    /** The member names actually present in a serialised payload. */
    private static Set<String> jsonMembersOf(UserAddRequest request) {
        try {
            Map<String, Object> tree = webConfigEquivalentMapper()
                    .readValue(serialise(request), new TypeReference<Map<String, Object>>() { });
            return tree.keySet();
        } catch (IOException failure) {
            throw new UncheckedIOException("Reading a serialised UserAddRequest must not fail",
                    failure);
        }
    }

    /** The record's component names, in declaration order. */
    private static List<String> componentNames() {
        List<String> names = new ArrayList<>(COMPONENT_COUNT);
        for (RecordComponent component : UserAddRequest.class.getRecordComponents()) {
            names.add(component.getName());
        }
        return names;
    }

    /**
     * Every type name, annotation name and method name reachable from this record's own declaration.
     *
     * <p>Used by the "nothing of this kind is reachable" cases: a payload contract can be inspected
     * exhaustively, because a record's whole surface is its components, its component annotations and
     * its declared methods. If a forbidden concern were present it would have to appear in one of
     * those, so scanning all three is a complete check rather than a sampled one.
     */
    private static List<String> reachableTypeNames() {
        List<String> reachable = new ArrayList<>();
        for (RecordComponent component : UserAddRequest.class.getRecordComponents()) {
            reachable.add(component.getType().getName());
            reachable.add(component.getName());
            for (Annotation annotation : component.getAccessor().getAnnotations()) {
                reachable.add(annotation.annotationType().getName());
            }
        }
        for (Method method : UserAddRequest.class.getDeclaredMethods()) {
            reachable.add(method.getReturnType().getName());
            reachable.add(method.getName());
            for (Class<?> parameter : method.getParameterTypes()) {
                reachable.add(parameter.getName());
            }
        }
        for (Field field : UserAddRequest.class.getDeclaredFields()) {
            reachable.add(field.getType().getName());
            reachable.add(field.getName());
        }
        return reachable;
    }

    /** The twelve published width constants, in symbolic-map order, read from the class. */
    private static List<Integer> publishedWidths() {
        return List.of(UserAddRequest.TRNNAME_LENGTH, UserAddRequest.TITLE01_LENGTH,
                UserAddRequest.CURDATE_LENGTH, UserAddRequest.PGMNAME_LENGTH,
                UserAddRequest.TITLE02_LENGTH, UserAddRequest.CURTIME_LENGTH,
                UserAddRequest.FNAME_LENGTH, UserAddRequest.LNAME_LENGTH,
                UserAddRequest.USERID_LENGTH, UserAddRequest.PASSWD_LENGTH,
                UserAddRequest.USRTYPE_LENGTH, UserAddRequest.ERRMSG_LENGTH);
    }

    // =================================================================================================
    // 1. THE PROJECTION OF 01 COUSR1AI.
    //
    // Twelve map members and two state members, in the copybook's own order, each map member traceable
    // to one name-labelled DFHMDF definition and to one xxxI PICTURE clause (gate G9).
    // =================================================================================================

    @Nested
    @DisplayName("Projection of 01 COUSR1AI - twelve map members, in copybook order")
    class MapProjection {

        @Test
        @DisplayName("fourteen components: the twelve map members then the two state members")
        void componentCensus() {
            assertThat(UserAddRequest.class.isRecord())
                    .as("an immutable projection of a screen contract, not a mutable bean")
                    .isTrue();
            assertThat(UserAddRequest.class.getRecordComponents()).hasSize(COMPONENT_COUNT);

            List<String> expected = new ArrayList<>(MAP_MEMBERS);
            expected.addAll(STATE_MEMBERS);
            assertThat(componentNames())
                    .as("declaration order is the copybook's order, so the class can be eye-diffed "
                            + "against app/cpy-bms/COUSR01.CPY a line at a time")
                    .isEqualTo(expected);
            assertThat(UserAddRequest.MAP_FIELD_NAMES)
                    .as("one entry per name-labelled DFHMDF of app/bms/COUSR01.bms")
                    .isEqualTo(SYMBOLIC_MAP_ITEMS)
                    .hasSize(DFHMDF_NAMED);
        }

        @Test
        @DisplayName("every map member is a String, because every xxxI item is PIC X(n)")
        void everyMapMemberIsCharacter() {
            RecordComponent[] components = UserAddRequest.class.getRecordComponents();
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                assertThat(components[index].getType())
                        .as("%s projects %s PIC X(%d)", components[index].getName(),
                                SYMBOLIC_MAP_ITEMS.get(index), DECLARED_WIDTHS.get(index))
                        .isEqualTo(String.class);
            }
            // There is no numeric field anywhere on this screen, so no member may be a floating-point
            // type - which is gate G22 read at this scale. The commarea does hold numeric items, and
            // they are int and long there, never double or float.
            for (RecordComponent component : components) {
                assertThat(component.getType())
                        .as("%s must never be a binary floating-point type", component.getName())
                        .isNotEqualTo(double.class).isNotEqualTo(Double.class)
                        .isNotEqualTo(float.class).isNotEqualTo(Float.class);
            }
            assertThat(components[DFHMDF_NAMED].getType())
                    .as("the communication area is the one shared type, never a copy of its fields")
                    .isEqualTo(NavigationContext.class);
            assertThat(components[DFHMDF_NAMED + 1].getType())
                    .as("the AID travels as a resolved token, so a String")
                    .isEqualTo(String.class);
        }

        @Test
        @DisplayName("the mapset declares 28 DFHMDF fields, so 16 stay unexposed screen furniture")
        void sixteenDefinitionsAreLiteralFurniture() {
            assertThat(DFHMDF_TOTAL)
                    .as("app/bms/COUSR01.bms - every DFHMDF, labelled or not")
                    .isEqualTo(28);
            assertThat(DFHMDF_NAMED)
                    .as("of those, the ones carrying a name label in column one")
                    .isEqualTo(12);
            assertThat(DFHMDF_TOTAL - DFHMDF_NAMED)
                    .as("captions, prompts, the '(8 Char)' and '(A=Admin, U=User)' hints and the "
                            + "function-key legend; the terminal paints them and the program never "
                            + "reads them, so none can be a payload member")
                    .isEqualTo(16);
            assertThat(SCREEN_FIELDS).hasSize(DFHMDF_NAMED);
            assertThat(MAPSET_LINES).hasSize(DFHMDF_NAMED);
            assertThat(UserAddRequest.MAP_FIELD_NAMES).hasSize(DFHMDF_NAMED);
        }

        @ParameterizedTest(name = "{0} is {1} wide at COUSR01.CPY:{2}")
        @CsvSource({
            "TRNNAMEI,   4, 24", "TITLE01I, 40, 30", "CURDATEI,  8, 36", "PGMNAMEI,  8, 42",
            "TITLE02I,  40, 48", "CURTIMEI,  8, 54", "FNAMEI,   20, 60", "LNAMEI,   20, 66",
            "USERIDI,    8, 72", "PASSWDI,   8, 78", "USRTYPEI,  1, 84", "ERRMSGI,  78, 90",
        })
        @DisplayName("each published width is the width its xxxI PICTURE clause declares")
        void publishedWidthMatchesTheCopybook(String item, int width, int copybookLine) {
            int position = UserAddRequest.MAP_FIELD_NAMES.indexOf(item);
            assertThat(position).as("%s must be a payload field", item).isNotNegative();
            assertThat(COPYBOOK_LINES.get(position))
                    .as("%s is declared at app/cpy-bms/COUSR01.CPY:%d", item, copybookLine)
                    .isEqualTo(copybookLine);
            assertThat(DECLARED_WIDTHS.get(position)).isEqualTo(width);
            assertThat(publishedWidths().get(position))
                    .as("the class must publish the copybook's width, not a rounded or shared one")
                    .isEqualTo(width);
        }

        @ParameterizedTest(name = "DFHMDF {0} LENGTH={1} at COUSR01.bms:{2}")
        @CsvSource({
            "TRNNAME,  4,  36", "TITLE01, 40,  40", "CURDATE,  8,  49", "PGMNAME,  8,  59",
            "TITLE02, 40,  63", "CURTIME,  8,  72", "FNAME,   20,  87", "LNAME,   20, 100",
            "USERID,   8, 114", "PASSWD,   8, 129", "USRTYPE,  1, 144", "ERRMSG,  78, 153",
        })
        @DisplayName("the mapset's LENGTH operand agrees, independently of the copybook")
        void publishedWidthMatchesTheMapset(String screenField, int length, int lengthLine) {
            int position = SCREEN_FIELDS.indexOf(screenField);
            assertThat(position).as("%s must be name-labelled", screenField).isNotNegative();
            assertThat(MAPSET_LENGTH_LINES.get(position))
                    .as("%s declares LENGTH at app/bms/COUSR01.bms:%d", screenField, lengthLine)
                    .isEqualTo(lengthLine);
            // Two independent statements of the same width, from two different files, and they agree.
            // This is what makes the numbers in this suite transcribed rather than assumed.
            assertThat(publishedWidths().get(position)).isEqualTo(length)
                    .isEqualTo(DECLARED_WIDTHS.get(position));
        }

        @Test
        @DisplayName("every member traces to one screen field, and its xxxI item is that field plus I")
        void everyMemberTracesToAScreenField() {
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                String screenField = SCREEN_FIELDS.get(index);
                assertThat(UserAddRequest.MAP_FIELD_NAMES.get(index))
                        .as("DFHMDF %s at app/bms/COUSR01.bms:%d backs the symbolic-map item",
                                screenField, MAPSET_LINES.get(index))
                        .isEqualTo(screenField + "I")
                        .isEqualTo(SYMBOLIC_MAP_ITEMS.get(index));
                assertThat(MAP_MEMBERS.get(index).toUpperCase(Locale.ROOT))
                        .as("the component name is the field name in Java casing")
                        .isEqualTo(screenField);
            }
        }

        @Test
        @DisplayName("the field-name constants are the copybook's spellings, one per member")
        void theFieldNameConstantsAreTheCopybooks() {
            assertThat(UserAddRequest.TRNNAME_FIELD).isEqualTo("TRNNAMEI");
            assertThat(UserAddRequest.TITLE01_FIELD).isEqualTo("TITLE01I");
            assertThat(UserAddRequest.CURDATE_FIELD).isEqualTo("CURDATEI");
            assertThat(UserAddRequest.PGMNAME_FIELD).isEqualTo("PGMNAMEI");
            assertThat(UserAddRequest.TITLE02_FIELD).isEqualTo("TITLE02I");
            assertThat(UserAddRequest.CURTIME_FIELD).isEqualTo("CURTIMEI");
            assertThat(UserAddRequest.FNAME_FIELD).isEqualTo("FNAMEI");
            assertThat(UserAddRequest.LNAME_FIELD).isEqualTo("LNAMEI");
            assertThat(UserAddRequest.USERID_FIELD).isEqualTo("USERIDI");
            assertThat(UserAddRequest.PASSWD_FIELD).isEqualTo("PASSWDI");
            assertThat(UserAddRequest.USRTYPE_FIELD).isEqualTo("USRTYPEI");
            assertThat(UserAddRequest.ERRMSG_FIELD).isEqualTo("ERRMSGI");
        }

        @Test
        @DisplayName("the screen identity literals are byte-exact and fit the commarea's PIC X(7)")
        void screenIdentityLiteralsAreByteExact() {
            assertThat(UserAddRequest.TRANSACTION_ID)
                    .as("WS-TRANID VALUE 'CU01' at app/cbl/COUSR01C.cbl:37, corroborated by "
                            + "app/csd/CARDDEMO.CSD:459")
                    .isEqualTo(TRANSACTION_ID)
                    .hasSize(UserAddRequest.TRNNAME_LENGTH);
            assertThat(UserAddRequest.PROGRAM_NAME)
                    .as("WS-PGMNAME VALUE 'COUSR01C' at app/cbl/COUSR01C.cbl:36, corroborated by "
                            + "app/csd/CARDDEMO.CSD:285 and :460")
                    .isEqualTo(PROGRAM_NAME)
                    .hasSize(UserAddRequest.PGMNAME_LENGTH);
            assertThat(UserAddRequest.MAP_NAME)
                    .as("MAP('COUSR1A') at app/cbl/COUSR01C.cbl:191")
                    .isEqualTo(MAP_NAME)
                    .hasSize(NavigationContext.LAST_MAP_LENGTH);
            assertThat(UserAddRequest.MAPSET_NAME)
                    .as("MAPSET('COUSR01') at app/cbl/COUSR01C.cbl:192")
                    .isEqualTo(MAPSET_NAME)
                    .hasSize(NavigationContext.LAST_MAPSET_LENGTH);
            assertThat(UserAddRequest.AID_LENGTH)
                    .as("CCARD-AID is PIC X(5) and the resolver produces exactly that width")
                    .isEqualTo(PfKeyResolver.AID_TOKEN_LENGTH);
        }

        @Test
        @DisplayName("declares no static mutable state, and its name list is unmodifiable")
        void declaresNoStaticMutableState() {
            for (Field field : UserAddRequest.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("static field %s must be final (G53)", field.getName())
                            .isTrue();
                }
            }
            // Final is necessary but not sufficient: a final reference to a mutable list would still be
            // shared mutable state, so the list itself is asserted unmodifiable.
            assertThat(UserAddRequest.MAP_FIELD_NAMES).isUnmodifiable();
        }
    }

    // =================================================================================================
    // 2. THE FOUR TRAPS THIS SCREEN SETS.
    //
    // Each is a difference from a sibling user screen that a translation would erase by "harmonising"
    // it, and each erasure would be invisible to anything but a field-by-field diff.
    // =================================================================================================

    @Nested
    @DisplayName("Cross-screen traps - USERID, names-before-id, curTime 8, errMsg 78")
    class CrossScreenTraps {

        @Test
        @DisplayName("TRAP 1: the identifier item is USERIDI, never the USRIDINI of COUSR00/02/03")
        void theIdentifierItemIsUseridAndNotUsridin() {
            // app/cpy-bms/COUSR01.CPY:72 declares 02 USERIDI PIC X(8). Its three sibling user screens
            // - app/cpy-bms/COUSR00.CPY, COUSR02.CPY and COUSR03.CPY - all declare USRIDINI instead.
            // Renaming this one for consistency with them would rewrite a screen contract silently, and
            // a diff keyed on item names would then never see it. (COSGN00.CPY happens to use USERIDI
            // too; the contrast that matters here is with the three COUSR0n screens.)
            assertThat(UserAddRequest.USERID_FIELD)
                    .as("app/cpy-bms/COUSR01.CPY:72")
                    .isEqualTo("USERIDI");
            assertThat(UserAddRequest.MAP_FIELD_NAMES)
                    .contains("USERIDI")
                    .doesNotContain("USRIDINI", "USRIDIN");
            assertThat(SCREEN_FIELDS)
                    .as("the DFHMDF at app/bms/COUSR01.bms:111 is labelled USERID")
                    .contains("USERID")
                    .doesNotContain("USRIDIN");
            assertThat(componentNames())
                    .as("and the component is userId, not usrIdIn")
                    .contains("userId")
                    .doesNotContain("usrIdIn", "usrIdin");
        }

        @Test
        @DisplayName("TRAP 2: the input fields run names first, then identifier, password and type")
        void theInputFieldOrderPutsTheNamesFirst() {
            // This ordering is behaviour, not layout. COUSR01C.cbl:117 opens an EVALUATE TRUE whose
            // FIRST matching arm wins, and the arms are tested in exactly the order the fields appear:
            // FNAMEI at :118, LNAMEI at :124, USERIDI at :130, PASSWDI at :136, USRTYPEI at :142. The
            // Update User and Delete User screens put the identifier first, so an identically blank
            // request produces a DIFFERENT message on those screens than on this one. Reordering these
            // members to match a sibling would change which message a user sees.
            List<String> inputItems = UserAddRequest.MAP_FIELD_NAMES.subList(6, 11);
            assertThat(inputItems)
                    .containsExactly("FNAMEI", "LNAMEI", "USERIDI", "PASSWDI", "USRTYPEI")
                    .isEqualTo(BLANK_CHAIN_ITEMS);
            assertThat(componentNames().subList(6, 11))
                    .containsExactly("fName", "lName", "userId", "passwd", "usrType");

            // The identifier is strictly after both names, on this screen and only on this screen.
            assertThat(UserAddRequest.MAP_FIELD_NAMES.indexOf("USERIDI"))
                    .as("the identifier follows both names, which is what makes First Name arm one")
                    .isGreaterThan(UserAddRequest.MAP_FIELD_NAMES.indexOf("FNAMEI"))
                    .isGreaterThan(UserAddRequest.MAP_FIELD_NAMES.indexOf("LNAMEI"));
            assertThat(BLANK_CHAIN_ITEMS.get(0))
                    .as("arm one, at app/cbl/COUSR01C.cbl:118")
                    .isEqualTo("FNAMEI");
        }

        @Test
        @DisplayName("TRAP 2 consequence: with two fields blank, the earlier field decides the outcome")
        void theEarlierBlankFieldDecidesTheOutcome() {
            // The DTO is a passive carrier and selects nothing - the EVALUATE lives in the controller,
            // and user.UserAddControllerTest asserts which message comes out. What is asserted here is
            // the property that makes the controller's selection DETERMINISTIC: the payload states both
            // blanks distinctly and in a fixed order, so the first-match rule has something stable to
            // match on.
            UserAddRequest bothBlank = requestOf(
                    withMember(withMember(populatedMapValues(), "fName", " ".repeat(
                            UserAddRequest.FNAME_LENGTH)), "userId", " ".repeat(
                            UserAddRequest.USERID_LENGTH)),
                    NavigationContext.empty().withPgmReenter(),
                    PfKeyResolver.AidKey.ENTER.token());

            assertThat(bothBlank.fName()).isBlank().hasSize(UserAddRequest.FNAME_LENGTH);
            assertThat(bothBlank.userId()).isBlank().hasSize(UserAddRequest.USERID_LENGTH);
            assertThat(bothBlank.lName())
                    .as("the field between them is populated, so arm two cannot be the winner")
                    .isNotBlank();

            // Arm one is First Name because FNAMEI precedes USERIDI, and both blanks survive the wire
            // so the controller sees the same pair the client sent.
            UserAddRequest restored = deserialise(serialise(bothBlank));
            assertThat(restored.fName()).isEqualTo(bothBlank.fName());
            assertThat(restored.userId()).isEqualTo(bothBlank.userId());
            assertThat(BLANK_CHAIN_ITEMS.indexOf("FNAMEI"))
                    .isLessThan(BLANK_CHAIN_ITEMS.indexOf("USERIDI"));
        }

        @Test
        @DisplayName("TRAP 3: curTime is eight characters here, not the nine of COSGN00")
        void curTimeIsEightNotNine() {
            // app/cpy-bms/COUSR01.CPY:54 declares 02 CURTIMEI PIC X(8) and app/bms/COUSR01.bms:72
            // declares LENGTH=8. app/cpy-bms/COSGN00.CPY:54 - the same line number in the sign-on map -
            // declares PIC X(9). Only the sign-on screen is nine, and its extra character must not be
            // carried across.
            assertThat(UserAddRequest.CURTIME_LENGTH).isEqualTo(8);
            assertThat(UserAddRequest.CURTIME_LENGTH)
                    .as("eight is the same width as curDate on this screen; on COSGN00 it is one more")
                    .isEqualTo(UserAddRequest.CURDATE_LENGTH);
            assertThat("hh:mm:ss")
                    .as("HH:MM:SS is eight characters, which is exactly what this field holds")
                    .hasSize(UserAddRequest.CURTIME_LENGTH);
        }

        @Test
        @DisplayName("TRAP 3 driven: the eight-character header time fills curTime with no padding")
        void theHeaderTimeFillsCurTimeExactly() {
            // B7: a fixed clock, so this expectation is exact on every machine and in any order.
            DateHeader header = DateHeader.from(codec(), Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
            assertThat(header.wsCurtimeHhMmSs())
                    .as("WS-CURTIME-HH-MM-SS is eight characters, composed at COUSR01C.cbl:229-233")
                    .isEqualTo(FIXED_CURTIME)
                    .hasSize(DateHeader.WS_CURTIME_HH_MM_SS_LENGTH);

            String moved = codec().movePicX(header.wsCurtimeHhMmSs(), UserAddRequest.CURTIME_LENGTH);
            assertThat(moved)
                    .as("eight into eight is neither padded nor truncated; on COSGN00's nine-wide "
                            + "field the same move would pad one space on the right")
                    .isEqualTo(FIXED_CURTIME)
                    .hasSize(UserAddRequest.CURTIME_LENGTH)
                    .doesNotEndWith(" ");
        }

        @Test
        @DisplayName("TRAP 3 companion: the header date fills curDate exactly, also without padding")
        void theHeaderDateFillsCurDateExactly() {
            DateHeader header = DateHeader.from(codec(), Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
            assertThat(header.wsCurdateMmDdYy())
                    .as("WS-CURDATE-MM-DD-YY, composed at COUSR01C.cbl:223-227 and moved at :227")
                    .isEqualTo(FIXED_CURDATE)
                    .hasSize(DateHeader.WS_CURDATE_MM_DD_YY_LENGTH);
            assertThat(codec().movePicX(header.wsCurdateMmDdYy(), UserAddRequest.CURDATE_LENGTH))
                    .isEqualTo(FIXED_CURDATE)
                    .hasSize(UserAddRequest.CURDATE_LENGTH);
        }

        @Test
        @DisplayName("TRAP 4: errMsg is 78 while WS-MESSAGE is 80, so the move discards two characters")
        void errMsgNarrowsTheEightyByteMessage() {
            assertThat(WS_MESSAGE_LENGTH)
                    .as("app/cbl/COUSR01C.cbl:38 declares 05 WS-MESSAGE PIC X(80) VALUE SPACES")
                    .isEqualTo(UserAddRequest.ERRMSG_LENGTH + 2);
            assertThat(UserAddRequest.ERRMSG_LENGTH)
                    .as("app/cpy-bms/COUSR01.CPY:90 and app/bms/COUSR01.bms:153 both say 78")
                    .isEqualTo(78);

            // An 80-character sending field whose LAST TWO characters are NOT spaces, so the loss is
            // observable instead of hidden in padding. The plan names MOVE the dominant parity risk in
            // this migration, with 2,795 occurrences; this is that risk in its smallest form.
            String message = "A".repeat(WS_MESSAGE_LENGTH - 2) + "YZ";
            assertThat(message).hasSize(WS_MESSAGE_LENGTH);

            // B11: the narrowing goes through the codec, never through a bare assignment and never
            // through substring. movePicX is the alphanumeric MOVE rule, and it is the rule that
            // decides WHICH two characters are lost.
            String moved = codec().movePicX(message, UserAddRequest.ERRMSG_LENGTH);
            assertThat(moved)
                    .as("COUSR01C.cbl:188 moves WS-MESSAGE into ERRMSGO, a narrower receiver; a COBOL "
                            + "alphanumeric MOVE fills from the left and discards the overflow")
                    .hasSize(UserAddRequest.ERRMSG_LENGTH)
                    .isEqualTo("A".repeat(WS_MESSAGE_LENGTH - 2))
                    .doesNotContain("Y")
                    .doesNotContain("Z");
        }

        @Test
        @DisplayName("TRAP 4 direction: a PIC X move truncates on the RIGHT, not the left")
        void theMoveTruncatesOnTheRight() {
            // Getting the direction wrong is the silent failure mode: keeping the trailing characters
            // is the NUMERIC move rule, and applying it to a PIC X field would produce a plausible
            // value of the right width and the wrong content.
            assertThat(codec().movePicX("ABCDEF", UserAddRequest.TRNNAME_LENGTH))
                    .as("a PIC X receiver keeps the leading characters")
                    .isEqualTo("ABCD");
            assertThat(codec().movePicX("AU", UserAddRequest.USRTYPE_LENGTH))
                    .as("a one-byte receiver keeps the first byte, so 'AU' becomes 'A'")
                    .isEqualTo("A");
        }

        @Test
        @DisplayName("TRAP 4 corollary: no message the blank chain produces is long enough to be cut")
        void noBlankChainMessageIsTruncated() {
            // The narrowing at :188 is real but harmless for these five texts, and it matters that it
            // is: if any of them were over 78 characters the user would see a clipped sentence. Each is
            // padded to the field width, unchanged, and comes back identical.
            assertThat(BLANK_CHAIN_MESSAGES).hasSize(BLANK_CHAIN_ITEMS.size()).hasSize(5);
            for (int index = 0; index < BLANK_CHAIN_MESSAGES.size(); index++) {
                String message = BLANK_CHAIN_MESSAGES.get(index);
                assertThat(message.length())
                        .as("arm %d's message must fit ERRMSG intact", index + 1)
                        .isLessThanOrEqualTo(UserAddRequest.ERRMSG_LENGTH);
                String moved = codec().movePicX(message, UserAddRequest.ERRMSG_LENGTH);
                assertThat(moved)
                        .hasSize(UserAddRequest.ERRMSG_LENGTH)
                        .startsWith(message);
                assertThat(moved.substring(message.length()))
                        .as("the remainder of a PIC X receiver is spaces, not nulls and not zeros")
                        .isEqualTo(" ".repeat(UserAddRequest.ERRMSG_LENGTH - message.length()));
                assertThat(moved.strip()).isEqualTo(message);
            }
        }

        @Test
        @DisplayName("the two titles are forty characters and carry the screen titles byte for byte")
        void titlesAreFortyCharacters() {
            assertThat(UserAddRequest.TITLE01_LENGTH)
                    .isEqualTo(ScreenTitles.TITLE_LENGTH).isEqualTo(40);
            assertThat(UserAddRequest.TITLE02_LENGTH)
                    .isEqualTo(ScreenTitles.TITLE_LENGTH).isEqualTo(40);

            // COUSR01C.cbl:218-219 move CCDA-TITLE01 and CCDA-TITLE02 into the output view. Both
            // literals are exactly forty characters INCLUDING their leading and trailing spaces, so
            // they fill the field without the move padding or truncating anything.
            assertThat(ScreenTitles.CCDA_TITLE01)
                    .hasSize(UserAddRequest.TITLE01_LENGTH)
                    .contains("AWS Mainframe Modernization")
                    .startsWith(" ")
                    .endsWith(" ");
            assertThat(ScreenTitles.CCDA_TITLE02)
                    .hasSize(UserAddRequest.TITLE02_LENGTH)
                    .contains("CardDemo")
                    .startsWith(" ")
                    .endsWith(" ");
            assertThat(codec().movePicX(ScreenTitles.CCDA_TITLE01, UserAddRequest.TITLE01_LENGTH))
                    .isEqualTo(ScreenTitles.CCDA_TITLE01);
        }

        @Test
        @DisplayName("the two thank-you literals are different texts of different widths")
        void theThankYouLiteralsAreNotInterchangeable() {
            // A trap worth naming because the constants read alike. ScreenTitles.CCDA_THANK_YOU is the
            // PIC X(40) line from app/cpy/COTTL01Y.cpy naming the CCDA application, and it fits a title
            // field. SystemMessages.CCDA_MSG_THANK_YOU is the PIC X(50) message from
            // app/cpy/CSMSG01Y.cpy naming the CardDemo application, and it does not. Different text,
            // different width, different copybook, different owner. Neither may substitute for the
            // other, and neither is what this screen shows: COUSR01C never moves either one.
            assertThat(ScreenTitles.CCDA_THANK_YOU).hasSize(ScreenTitles.TITLE_LENGTH).hasSize(40);
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU)
                    .hasSize(SystemMessages.MESSAGE_LENGTH).hasSize(50);
            assertThat(ScreenTitles.CCDA_THANK_YOU)
                    .isNotEqualTo(SystemMessages.CCDA_MSG_THANK_YOU);
            assertThat(ScreenTitles.CCDA_THANK_YOU.strip())
                    .isNotEqualTo(SystemMessages.CCDA_MSG_THANK_YOU.strip());
            assertThat(SystemMessages.MESSAGE_LENGTH)
                    .as("a PIC X(50) message does not fit this screen's PIC X(40) title field")
                    .isGreaterThan(UserAddRequest.TITLE01_LENGTH);
        }

        @Test
        @DisplayName("the five input widths are the USRSEC record's own, field for field")
        void theFiveInputWidthsMatchTheSecurityRecord() {
            // COUSR01C.cbl:154-158 moves each input field into its USRSEC counterpart. Every one of the
            // five is width-for-width, so not one of those moves pads or truncates - which is why the
            // program needs no editing logic at all between the screen and the record.
            assertThat(UserAddRequest.FNAME_LENGTH)
                    .as("FNAMEI -> SEC-USR-FNAME at COUSR01C.cbl:155")
                    .isEqualTo(SEC_USR_FNAME_LENGTH)
                    .isEqualTo(SecUserRecord.SEC_USR_FNAME_LENGTH);
            assertThat(UserAddRequest.LNAME_LENGTH)
                    .as("LNAMEI -> SEC-USR-LNAME at COUSR01C.cbl:156")
                    .isEqualTo(SEC_USR_LNAME_LENGTH)
                    .isEqualTo(SecUserRecord.SEC_USR_LNAME_LENGTH);
            assertThat(UserAddRequest.USERID_LENGTH)
                    .as("USERIDI -> SEC-USR-ID at COUSR01C.cbl:154, and the USRSEC key")
                    .isEqualTo(SEC_USR_ID_LENGTH)
                    .isEqualTo(SecUserRecord.SEC_USR_ID_LENGTH)
                    .isEqualTo(SecUserRecord.KEY_LENGTH);
            assertThat(UserAddRequest.PASSWD_LENGTH)
                    .as("PASSWDI -> SEC-USR-PWD at COUSR01C.cbl:157")
                    .isEqualTo(SEC_USR_PWD_LENGTH)
                    .isEqualTo(SecUserRecord.SEC_USR_PWD_LENGTH);
            assertThat(UserAddRequest.USRTYPE_LENGTH)
                    .as("USRTYPEI -> SEC-USR-TYPE at COUSR01C.cbl:158")
                    .isEqualTo(SEC_USR_TYPE_LENGTH)
                    .isEqualTo(SecUserRecord.SEC_USR_TYPE_LENGTH);
        }

        @Test
        @DisplayName("the USRSEC offsets and its 80-byte total corroborate those five widths")
        void theSecurityRecordGeometryCorroboratesTheWidths() {
            // app/cpy/CSUSR01Y.cpy:17-23. The offsets follow from the widths, so asserting both is
            // asserting that the record has no hidden gap and that the trailing FILLER is present -
            // omit the FILLER and the total is 57, not 80, and every consumer of the record breaks.
            assertThat(SecUserRecord.SEC_USR_ID_OFFSET).isZero();
            assertThat(SecUserRecord.SEC_USR_FNAME_OFFSET).isEqualTo(8);
            assertThat(SecUserRecord.SEC_USR_LNAME_OFFSET).isEqualTo(28);
            assertThat(SecUserRecord.SEC_USR_PWD_OFFSET).isEqualTo(48);
            assertThat(SecUserRecord.SEC_USR_TYPE_OFFSET).isEqualTo(56);
            assertThat(SecUserRecord.SEC_USR_FILLER_OFFSET).isEqualTo(57);
            assertThat(SecUserRecord.SEC_USR_FILLER_LENGTH)
                    .as("05 SEC-USR-FILLER PIC X(23) at app/cpy/CSUSR01Y.cpy:23")
                    .isEqualTo(SEC_USR_FILLER_LENGTH);
            assertThat(SecUserRecord.RECORD_LENGTH)
                    .as("8 + 20 + 20 + 8 + 1 + 23")
                    .isEqualTo(SEC_USER_DATA_LENGTH)
                    .isEqualTo(SEC_USR_ID_LENGTH + SEC_USR_FNAME_LENGTH + SEC_USR_LNAME_LENGTH
                            + SEC_USR_PWD_LENGTH + SEC_USR_TYPE_LENGTH + SEC_USR_FILLER_LENGTH);
            assertThat(SecUserRecord.KEY_OFFSET)
                    .as("RIDFLD(SEC-USR-ID) at COUSR01C.cbl:244 keys on the leading eight bytes")
                    .isZero();
        }

        @Test
        @DisplayName("this screen's user type is one byte, and admin and user are its two values")
        void theUserTypeIsOneByte() {
            // app/bms/COUSR01.bms:148 paints the legend '(A=Admin, U=User)' beside a LENGTH=1 field.
            // The legend names the two values the application acts on, but COUSR01C applies no check to
            // the byte at all - the MOVE at :158 stores whatever was keyed. No @Pattern is therefore
            // asserted here, and none may be added.
            assertThat(UserAddRequest.USRTYPE_LENGTH).isEqualTo(1);
            assertThat(NavigationContext.USER_TYPE_ADMIN)
                    .as("88 CDEMO-USRTYP-ADMIN VALUE 'A' at app/cpy/COCOM01Y.cpy:27")
                    .isEqualTo("A").hasSize(UserAddRequest.USRTYPE_LENGTH);
            assertThat(NavigationContext.USER_TYPE_USER)
                    .as("88 CDEMO-USRTYP-USER VALUE 'U' at app/cpy/COCOM01Y.cpy:28")
                    .isEqualTo("U").hasSize(UserAddRequest.USRTYPE_LENGTH);

            // A third value is representable, because the program accepts one.
            UserAddRequest odd = requestOf(withMember(populatedMapValues(), "usrType", "X"),
                    NavigationContext.empty(), "");
            assertThat(odd.usrType()).isEqualTo("X");
            assertThat(validate(odd))
                    .as("the program stores an unexpected type rather than rejecting it, so no "
                            + "constraint may reject it here either")
                    .isEmpty();
        }
    }


    // =================================================================================================
    // 3. METADATA IS NOT PAYLOAD.
    //
    // The symbolic map declares five items per field. Only the xxxI item is data; the xxxL halfword,
    // the xxxF flag byte, the xxxA overlay over it and the two FILLER spans are terminal mechanics, and
    // none of them may reach the wire.
    // =================================================================================================

    @Nested
    @DisplayName("Metadata - xxxL, xxxF, xxxA and every FILLER stay off the wire")
    class MetadataIsNotPayload {

        @Test
        @DisplayName("the serialised payload carries exactly the fourteen expected member names")
        void theWireCarriesOnlyTheDeclaredMembers() {
            assertThat(jsonMembersOf(populatedRequest()))
                    .containsExactlyInAnyOrderElementsOf(EXPECTED_JSON_MEMBERS)
                    .hasSize(COMPONENT_COUNT);
        }

        @ParameterizedTest(name = "{0}L, {0}F and {0}A are absent")
        @ValueSource(strings = {"TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME",
            "FNAME", "LNAME", "USERID", "PASSWD", "USRTYPE", "ERRMSG"})
        @DisplayName("no length, flag or attribute item appears under any spelling")
        void noMetadataItemIsAMember(String screenField) {
            String json = serialise(populatedRequest());
            List<String> componentNames = componentNames();
            // The output view's own attribute items are checked too: xxxC carries the colour -
            // COUSR01C.cbl:254 does MOVE DFHGREEN TO ERRMSGC OF COUSR1AO to turn a success message
            // green - and xxxP, xxxH and xxxV are the remaining attribute bytes. All of them belong to
            // common.BmsAttributes and common.FieldAttributeSetter, never to a request payload.
            for (String suffix : List.of("L", "F", "A", "C", "P", "H", "V")) {
                String item = screenField + suffix;
                assertThat(json)
                        .as("%s is terminal mechanics and must not reach the wire", item)
                        .doesNotContain("\"" + item + "\"");
                for (String component : componentNames) {
                    assertThat(component.toUpperCase(Locale.ROOT))
                            .as("no component may project %s", item)
                            .isNotEqualTo(item);
                }
            }
            // The xxxI item, by contrast, is exactly what a member does project.
            assertThat(UserAddRequest.MAP_FIELD_NAMES).contains(screenField + "I");
        }

        @Test
        @DisplayName("the xxxL items are the cursor carrier, which is why they are not data")
        void theLengthItemsAreTheCursorCarrier() {
            // COUSR01C uses xxxL to steer the terminal, never as a value: MOVE -1 TO FNAMEL at :86,
            // :100, :122, :149, :272 and :289, to LNAMEL at :128, to USERIDL at :134 and :265, to
            // PASSWDL at :140 and to USRTYPEL at :146. A negative length is meaningless as data and is
            // the CICS convention for "put the cursor here", so exposing it as a payload field would
            // put a presentation instruction into a data contract.
            assertThat(LENGTH_ITEM_LENGTH)
                    .as("COMP PIC S9(4) is a binary halfword - two bytes, not a character field")
                    .isEqualTo(2);
            String json = serialise(populatedRequest());
            for (String screenField : SCREEN_FIELDS) {
                assertThat(json).doesNotContain(screenField + "L");
            }
            assertThat(componentNames())
                    .doesNotContain("fNameL", "lNameL", "userIdL", "passwdL", "usrTypeL");
        }

        @Test
        @DisplayName("neither the TIOAPFX prefix nor any per-field filler is exposed")
        void noFillerIsExposed() {
            assertThat(TIOAPFX_PREFIX_LENGTH)
                    .as("02 FILLER PIC X(12) at app/cpy-bms/COUSR01.CPY:18")
                    .isEqualTo(12);
            assertThat(ATTRIBUTE_FILLER_LENGTH)
                    .as("02 FILLER PICTURE X(4), once per field")
                    .isEqualTo(4);

            // A FILLER is real, positioned storage - the layout in section 4 declares every one of them
            // and would refuse to tile 339 bytes without them - but it is not referable in COBOL and is
            // not a field. It must therefore appear in the codec and never in the payload.
            String json = serialise(populatedRequest());
            assertThat(json)
                    .doesNotContain("FILLER").doesNotContain("filler")
                    .doesNotContain("TIOAPFX").doesNotContain("tioapfx");
            for (String component : componentNames()) {
                assertThat(component.toLowerCase(Locale.ROOT))
                        .as("%s must not project reserved storage", component)
                        .doesNotContain("filler").doesNotContain("tioapfx").doesNotContain("reserved");
            }
        }

        @Test
        @DisplayName("the derived context predicates are withheld, so a payload cannot contradict itself")
        void theDerivedPredicatesAreWithheld() throws NoSuchMethodException {
            // pgmEnter() and pgmReenter() read THROUGH the communication area rather than duplicating
            // its byte. If either were serialised, a client could send a payload whose flag disagreed
            // with its own commarea, and the canonical constructor could not accept the result back -
            // a round trip would fail outright.
            Set<String> members = jsonMembersOf(populatedRequest());
            assertThat(members).doesNotContain("pgmEnter", "pgmReenter", "pgmContext");
            assertThat(componentNames()).doesNotContain("pgmEnter", "pgmReenter", "pgmContext");

            // Deliberately not bean getters: a getX or isX accessor would be discovered as a property.
            Method enter = UserAddRequest.class.getDeclaredMethod("pgmEnter");
            Method reenter = UserAddRequest.class.getDeclaredMethod("pgmReenter");
            for (Method predicate : List.of(enter, reenter)) {
                assertThat(predicate.getName())
                        .as("%s must not be shaped like a bean getter", predicate.getName())
                        .doesNotStartWith("get").doesNotStartWith("is");
                assertThat(predicate.getReturnType()).isEqualTo(boolean.class);
                assertThat(predicate.getParameterCount()).isZero();
                assertThat(Modifier.isStatic(predicate.getModifiers())).isFalse();
            }
        }
    }

    // =================================================================================================
    // 4. THE TWELVE PER-FIELD REDEFINES OVERLAYS (gate G34).
    //
    // app/cpy-bms/COUSR01.CPY declares thirteen REDEFINES: twelve per-field overlays of the shape
    // 02 FILLER REDEFINES xxxF. / 03 xxxA PICTURE X., and one group-level
    // 01 COUSR1AO REDEFINES COUSR1AI at line 91. The twelve are this file's subject; the group-level
    // one is the response projection and belongs to UserAddResponseTest.
    //
    // This matters for coverage as well as parity: the five programs behind this package contain ZERO
    // REDEFINES of their own, so the symbolic maps are the only place G34 has a subject at all.
    // =================================================================================================

    @Nested
    @DisplayName("REDEFINES - twelve attribute overlays, each over one shared byte")
    class RedefinesOverlays {

        @Test
        @DisplayName("the layout tiles 339 bytes exactly: 12 + 12 x 7 + 243")
        void theGeometryIsTheCopybooks() {
            // Constructing SYMBOLIC_MAP_LAYOUT already proved this - RecordLayout refuses a gap, an
            // unintended overlap, an overlay past declared storage and any total other than its
            // declared length - so this case states the arithmetic a reader needs rather than
            // discovering it.
            assertThat(FIELD_PREFIX_LENGTH)
                    .as("xxxL 2 + xxxF 1 + FILLER X(4) = 7, and the output view's prefix is also 7, "
                            + "which is what lets COUSR1AO overlay COUSR1AI field for field at line 91")
                    .isEqualTo(7);
            assertThat(DECLARED_WIDTHS.stream().mapToInt(Integer::intValue).sum())
                    .isEqualTo(PAYLOAD_WIDTH_TOTAL);
            assertThat(TIOAPFX_PREFIX_LENGTH + DFHMDF_NAMED * FIELD_PREFIX_LENGTH
                    + PAYLOAD_WIDTH_TOTAL).isEqualTo(SYMBOLIC_MAP_LENGTH);
            assertThat(SYMBOLIC_MAP_LAYOUT.recordLength()).isEqualTo(SYMBOLIC_MAP_LENGTH);
            assertThat(SYMBOLIC_MAP_LAYOUT.storageSpans().stream()
                    .mapToInt(FixedWidthRecord.FieldSpan::length).sum())
                    .as("the storage spans, overlays excluded, sum to the record length; an omitted "
                            + "FILLER would make this short and every later offset wrong")
                    .isEqualTo(SYMBOLIC_MAP_LENGTH);
        }

        @Test
        @DisplayName("twelve per-field overlays are modelled, and the thirteenth is not this file's")
        void twelveOverlaysAreModelled() {
            assertThat(SYMBOLIC_MAP_LAYOUT.redefinitions())
                    .as("one 02 FILLER REDEFINES xxxF per field, at COUSR01.CPY lines %s",
                            REDEFINES_LINES)
                    .hasSize(DFHMDF_NAMED);
            assertThat(REDEFINES_LINES).hasSize(DFHMDF_NAMED);
            assertThat(REDEFINES_LINES)
                    .as("each REDEFINES sits two lines above its own xxxI item")
                    .containsExactly(21, 27, 33, 39, 45, 51, 57, 63, 69, 75, 81, 87);
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                assertThat(COPYBOOK_LINES.get(index) - REDEFINES_LINES.get(index))
                        .as("the group is REDEFINES, xxxA, FILLER, xxxI - three lines apart")
                        .isEqualTo(3);
            }
            assertThat(GROUP_REDEFINES_LINE)
                    .as("01 COUSR1AO REDEFINES COUSR1AI - the output view, and UserAddResponseTest's "
                            + "subject rather than this file's")
                    .isEqualTo(91)
                    .isGreaterThan(COPYBOOK_LINES.get(DFHMDF_NAMED - 1));
            assertThat(REDEFINES_LINES.size() + 1)
                    .as("twelve per-field plus one group-level is the copybook's thirteen")
                    .isEqualTo(13);
        }

        @ParameterizedTest(name = "{0}A redefines {0}F")
        @ValueSource(strings = {"TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME",
            "FNAME", "LNAME", "USERID", "PASSWD", "USRTYPE", "ERRMSG"})
        @DisplayName("the flag view and the attribute view describe one and the same byte")
        void theTwoViewsShareOneByte(String screenField) {
            FixedWidthRecord.FieldSpan flag = SYMBOLIC_MAP_LAYOUT.span(screenField + "F");
            FixedWidthRecord.FieldSpan attribute = SYMBOLIC_MAP_LAYOUT.span(screenField + "A");

            assertThat(attribute.offset())
                    .as("%sA starts where %sF starts", screenField, screenField)
                    .isEqualTo(flag.offset());
            assertThat(attribute.length())
                    .as("both are PICTURE X - one byte, not a copy of one byte")
                    .isEqualTo(ATTRIBUTE_ITEM_LENGTH)
                    .isEqualTo(flag.length());
            assertThat(attribute.redefinition())
                    .as("%sA is declared through 02 FILLER REDEFINES %sF", screenField, screenField)
                    .isTrue();
            assertThat(flag.redefinition())
                    .as("%sF is the storage; only the overlay is a redefinition", screenField)
                    .isFalse();
            assertThat(attribute.kind()).isEqualTo(flag.kind())
                    .isEqualTo(FixedWidthRecord.PictureKind.ALPHANUMERIC);
            assertThat(attribute.endOffsetExclusive()).isEqualTo(flag.endOffsetExclusive());
        }

        @ParameterizedTest(name = "{0}: a write through either view is read by the other")
        @ValueSource(strings = {"TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME",
            "FNAME", "LNAME", "USERID", "PASSWD", "USRTYPE", "ERRMSG"})
        @DisplayName("the overlay round-trips in both directions, and touches nothing else")
        void theOverlayRoundTripsBothWays(String screenField) {
            // B11 and B8: a real record over the transcribed layout, allocated with an explicitly named
            // code page. Each parameter gets its own record, so no case can observe another's writes.
            FixedWidthRecord record = FixedWidthRecord.forLayout(SYMBOLIC_MAP_LAYOUT, MAP_CHARSET);
            FixedWidthRecord.FieldSpan flag = SYMBOLIC_MAP_LAYOUT.span(screenField + "F");
            FixedWidthRecord.FieldSpan attribute = SYMBOLIC_MAP_LAYOUT.span(screenField + "A");
            byte[] before = record.toByteArray();

            // Write through the flag view, read the identical byte through the attribute view.
            record.writeSpan(flag, "A");
            assertThat(record.readSpan(attribute)).isEqualTo("A");
            assertThat(record.readSpanBytes(attribute)).isEqualTo(record.readSpanBytes(flag));

            // And back the other way: one storage byte, two names for it.
            record.writeSpan(attribute, "Z");
            assertThat(record.readSpan(flag)).isEqualTo("Z");
            assertThat(record.readSpanBytes(flag)).isEqualTo(record.readSpanBytes(attribute));

            // The overlay addresses exactly one byte, so exactly one byte of the image may differ.
            byte[] after = record.toByteArray();
            assertThat(after).hasSize(before.length).hasSize(SYMBOLIC_MAP_LENGTH);
            int differing = 0;
            for (int offset = 0; offset < after.length; offset++) {
                if (after[offset] != before[offset]) {
                    differing++;
                    assertThat(offset)
                            .as("only the shared attribute byte may change")
                            .isEqualTo(flag.offset());
                }
            }
            assertThat(differing).isEqualTo(ATTRIBUTE_ITEM_LENGTH);
        }

        @Test
        @DisplayName("writing an attribute leaves every xxxI item and every FILLER byte alone")
        void anAttributeWriteDoesNotDisturbTheData() {
            FixedWidthRecord record = FixedWidthRecord.forLayout(SYMBOLIC_MAP_LAYOUT, MAP_CHARSET);
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                record.writeSpan(SYMBOLIC_MAP_LAYOUT.span(SYMBOLIC_MAP_ITEMS.get(index)),
                        "V".repeat(DECLARED_WIDTHS.get(index)));
            }
            for (String screenField : SCREEN_FIELDS) {
                record.writeSpan(SYMBOLIC_MAP_LAYOUT.span(screenField + "A"), "R");
            }
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                assertThat(record.readSpan(SYMBOLIC_MAP_LAYOUT.span(SYMBOLIC_MAP_ITEMS.get(index))))
                        .as("%s must be untouched by the attribute writes",
                                SYMBOLIC_MAP_ITEMS.get(index))
                        .isEqualTo("V".repeat(DECLARED_WIDTHS.get(index)));
                assertThat(record.readSpan(SYMBOLIC_MAP_LAYOUT.span(SCREEN_FIELDS.get(index) + "F")))
                        .as("and each flag byte still reads what the overlay wrote")
                        .isEqualTo("R");
            }
            assertThat(record.toByteArray()).hasSize(SYMBOLIC_MAP_LENGTH);
        }

        @Test
        @DisplayName("the xxxI data spans sit at the offsets the copybook's own tiling produces")
        void theDataSpansSitWhereTheCopybookPutsThem() {
            // The first field's data begins after the TIOAPFX prefix and its own seven-byte prefix, and
            // every later field follows its predecessor's data immediately. Asserting the walk here
            // means a single mistranscribed width is caught at the field it belongs to rather than as an
            // opaque total mismatch.
            int cursor = TIOAPFX_PREFIX_LENGTH;
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                FixedWidthRecord.FieldSpan data =
                        SYMBOLIC_MAP_LAYOUT.span(SYMBOLIC_MAP_ITEMS.get(index));
                FixedWidthRecord.FieldSpan flag =
                        SYMBOLIC_MAP_LAYOUT.span(SCREEN_FIELDS.get(index) + "F");
                assertThat(flag.offset())
                        .as("%sF follows the xxxL halfword", SCREEN_FIELDS.get(index))
                        .isEqualTo(cursor + LENGTH_ITEM_LENGTH);
                assertThat(data.offset())
                        .as("%s begins after its seven-byte prefix", SYMBOLIC_MAP_ITEMS.get(index))
                        .isEqualTo(cursor + FIELD_PREFIX_LENGTH);
                assertThat(data.length()).isEqualTo(DECLARED_WIDTHS.get(index));
                cursor += FIELD_PREFIX_LENGTH + DECLARED_WIDTHS.get(index);
            }
            assertThat(cursor)
                    .as("the walk ends exactly at the declared record length")
                    .isEqualTo(SYMBOLIC_MAP_LENGTH);
            assertThat(SYMBOLIC_MAP_LAYOUT.hasSpan("ERRMSGI")).isTrue();
            assertThat(SYMBOLIC_MAP_LAYOUT.hasSpan("USRIDINI"))
                    .as("the sibling screens' identifier item has no place in this map")
                    .isFalse();
        }
    }


    // =================================================================================================
    // 5. CONVERSATION STATE TRAVELS IN THE PAYLOAD, NEVER IN A SESSION (gate G37, rule R6).
    //
    // CICS is pseudo-conversational: COUSR01C.cbl:107-110 issues EXEC CICS RETURN with a COMMAREA and
    // the program ends, then is re-entered from MAIN-PARA on the next key press. The only state that
    // survives is what it handed back. The Java form keeps that shape - the communication area and the
    // resolved key indication are payload members - which is what makes the endpoint stateless.
    // =================================================================================================

    @Nested
    @DisplayName("Conversation state - the commarea and the AID travel in the payload")
    class ConversationState {

        @Test
        @DisplayName("the communication area is a payload member, so no session is ever needed")
        void theCommareaIsAPayloadMember() {
            assertThat(componentNames()).contains("navigationContext");
            assertThat(jsonMembersOf(populatedRequest())).contains("navigationContext");
            assertThat(populatedRequest().navigationContext()).isNotNull();

            // Nothing in this type reaches for server-side state. A session attribute, a thread local
            // or a static cache would each reintroduce the affinity that pseudo-conversational design
            // exists to avoid, and any of them would break the moment a second instance served the
            // follow-up call.
            List<String> forbidden = List.of("HttpSession", "SessionAttribute", "SessionScope",
                    "ThreadLocal", "Cache", "ServletRequest", "HttpServlet");
            for (String name : reachableTypeNames()) {
                for (String marker : forbidden) {
                    assertThat(name)
                            .as("%s suggests server-side state, which rule R6 forbids", name)
                            .doesNotContain(marker);
                }
            }
            for (Field field : UserAddRequest.class.getDeclaredFields()) {
                assertThat(Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(
                        field.getModifiers()))
                        .as("%s must not be a mutable static holder", field.getName())
                        .isFalse();
            }
        }

        @Test
        @DisplayName("the commarea is exactly 160 bytes, and the sections account for all of them")
        void theCommareaIsOneHundredAndSixtyBytes() {
            assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(160);
            // app/cpy/COCOM01Y.cpy:19-44, section by section.
            assertThat(NavigationContext.GENERAL_INFO_LENGTH)
                    .as("lines 20-31: 4 + 8 + 4 + 8 + 8 + 1 + 1")
                    .isEqualTo(34);
            assertThat(NavigationContext.CUSTOMER_INFO_LENGTH)
                    .as("lines 32-36: 9 + 25 + 25 + 25")
                    .isEqualTo(84);
            assertThat(NavigationContext.ACCOUNT_INFO_LENGTH)
                    .as("lines 37-39: 11 + 1")
                    .isEqualTo(12);
            assertThat(NavigationContext.CARD_INFO_LENGTH)
                    .as("lines 40-41: 16")
                    .isEqualTo(16);
            assertThat(NavigationContext.MORE_INFO_LENGTH)
                    .as("lines 42-44: 7 + 7, and both are PIC X(7) rather than X(8)")
                    .isEqualTo(14);
            assertThat(NavigationContext.GENERAL_INFO_LENGTH + NavigationContext.CUSTOMER_INFO_LENGTH
                    + NavigationContext.ACCOUNT_INFO_LENGTH + NavigationContext.CARD_INFO_LENGTH
                    + NavigationContext.MORE_INFO_LENGTH)
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH);

            // Proved THROUGH the codec with an explicitly named code page (B8, B11), not by trusting
            // the constant: the image the carried area produces is 160 bytes wide.
            byte[] image = populatedRequest().navigationContext().toFixedWidth(codec());
            assertThat(image).hasSize(NavigationContext.COMMAREA_LENGTH);

            // And the image is lossless, which is what lets the area survive a full conversation turn.
            assertThat(NavigationContext.fromFixedWidth(codec(), image))
                    .isEqualTo(populatedRequest().navigationContext());
        }

        @Test
        @DisplayName("the last map and mapset are seven characters, which this screen's names fit")
        void theMapNamesAreSevenCharacters() {
            assertThat(NavigationContext.LAST_MAP_LENGTH)
                    .as("app/cpy/COCOM01Y.cpy:43 declares CDEMO-LAST-MAP PIC X(7), not X(8)")
                    .isEqualTo(7);
            assertThat(NavigationContext.LAST_MAPSET_LENGTH)
                    .as("app/cpy/COCOM01Y.cpy:44 declares CDEMO-LAST-MAPSET PIC X(7)")
                    .isEqualTo(7);

            NavigationContext context = populatedRequest().navigationContext();
            assertThat(context.lastMap()).isEqualTo(MAP_NAME).hasSize(7);
            assertThat(context.lastMapset()).isEqualTo(MAPSET_NAME).hasSize(7);

            // The area models 160 bytes of storage and cannot be widened to hold an eighth character.
            // An over-long value is refused at construction rather than silently shortened, which is
            // what keeps the image a lossless projection rather than a lossy one.
            assertThatIllegalArgumentException()
                    .as("an eight-character map name has no representation in PIC X(7)")
                    .isThrownBy(() -> NavigationContext.empty().withLastMap("COUSR1AX"));
        }

        @Test
        @DisplayName("the AID arrives already resolved to its five-character token")
        void theAidIsCarriedAsAResolvedToken() {
            assertThat(UserAddRequest.AID_LENGTH)
                    .as("CCARD-AID is PIC X(5), and the resolver produces exactly that width")
                    .isEqualTo(PfKeyResolver.AID_TOKEN_LENGTH)
                    .isEqualTo(5);

            for (PfKeyResolver.AidKey key : PfKeyResolver.AidKey.values()) {
                assertThat(key.token())
                        .as("%s must be a full-width token; a trimmed one would be the wrong width",
                                key.name())
                        .hasSize(UserAddRequest.AID_LENGTH);
            }
            // The two trailing spaces on PA1 and PA2 are part of the copybook literal, not incidental
            // whitespace, and trimming them would produce a three-character value in a PIC X(5) field.
            assertThat(PfKeyResolver.AidKey.PA1.token()).isEqualTo("PA1  ");
            assertThat(PfKeyResolver.AidKey.PA2.token()).isEqualTo("PA2  ");
            assertThat(PfKeyResolver.AidKey.ENTER.token()).isEqualTo("ENTER");
            assertThat(PfKeyResolver.AidKey.CLEAR.token()).isEqualTo("CLEAR");

            // What travels is the token, never a raw EIBAID byte the server would have to interpret.
            // COUSR01C.cbl:90-103 evaluates EIBAID inline - this program does not copy CSSTRPFY - and
            // names DFHENTER at :91, DFHPF3 at :93 and DFHPF4 at :96, with WHEN OTHER at :98. The
            // resolution happens before the payload is built, so the request states which key was
            // pressed rather than which byte arrived.
            assertThat(PfKeyResolver.resolve(CicsAid.DFHENTER))
                    .contains(PfKeyResolver.AidKey.ENTER);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF3))
                    .contains(PfKeyResolver.AidKey.PFK03);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF4))
                    .contains(PfKeyResolver.AidKey.PFK04);

            UserAddRequest request = populatedRequest();
            assertThat(request.aid())
                    .isEqualTo(PfKeyResolver.AidKey.ENTER.token())
                    .hasSize(UserAddRequest.AID_LENGTH);
            assertThat(deserialise(serialise(request)).aid()).isEqualTo(request.aid());

            // No component is byte-typed, so no unresolved EIBAID can reach the server at all.
            for (RecordComponent component : UserAddRequest.class.getRecordComponents()) {
                assertThat(component.getType())
                        .as("%s must not carry a raw AID byte", component.getName())
                        .isNotEqualTo(byte.class)
                        .isNotEqualTo(Byte.class)
                        .isNotEqualTo(byte[].class);
            }
        }

        @Test
        @DisplayName("PF13-PF24 fold onto PFK01-PFK12, and an unrecognised byte resolves to nothing")
        void theResolverFoldsTheHighFunctionKeysAndReportsNoMatch() {
            // The fold is the copybook's own: CSSTRPFY sets the same CCARD-AID literal for PF1 and PF13,
            // PF2 and PF14, and so on. A payload therefore cannot distinguish the two, and must not
            // try to.
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF3))
                    .isEqualTo(PfKeyResolver.resolve(CicsAid.DFHPF15))
                    .contains(PfKeyResolver.AidKey.PFK03);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF12))
                    .isEqualTo(PfKeyResolver.resolve(CicsAid.DFHPF24))
                    .contains(PfKeyResolver.AidKey.PFK12);

            // An unmatched byte resolves to nothing rather than to a default. On the mainframe the
            // previous key's token would still be standing, which is why substituting ENTER here would
            // be an invention. A payload can carry that "nothing" as an empty token, and COUSR01C:98-102
            // answers it with the invalid-key message - which the controller's own suite asserts.
            Optional<PfKeyResolver.AidKey> unmatched = PfKeyResolver.resolve((byte) 0x00);
            assertThat(unmatched).isEmpty();
            UserAddRequest noKey = requestOf(populatedMapValues(), NavigationContext.empty(),
                    unmatched.map(PfKeyResolver.AidKey::token).orElse(""));
            assertThat(noKey.aid()).isEmpty();
            assertThat(validate(noKey))
                    .as("an absent token is a legitimate state, not a rejectable one")
                    .isEmpty();
        }

        @ParameterizedTest(name = "context {0}: pgmEnter={1}, pgmReenter={2}")
        @CsvSource({
            "0, true,  false",
            "1, false, true",
            "2, false, false",
            "9, false, false",
        })
        @DisplayName("both 88-level states are driven, in both directions, plus a digit that is neither")
        void bothContextStatesAreDriven(int pgmContext, boolean enter, boolean reenter) {
            // app/cpy/COCOM01Y.cpy:29 declares CDEMO-PGM-CONTEXT PIC 9(01), :30 declares
            // 88 CDEMO-PGM-ENTER VALUE 0 and :31 declares 88 CDEMO-PGM-REENTER VALUE 1. Both states are
            // driven here, in both directions (gate G50), and so is a digit that satisfies neither: the
            // field is PIC 9(01) and may hold any digit, so the two condition names are NOT complements.
            // Defining either as the other's negation would invent a state the copybook never
            // describes. The behaviour the re-enter state gates - COUSR01C:83 tests
            // IF NOT CDEMO-PGM-REENTER and either paints or validates - belongs to the controller.
            UserAddRequest request = requestOf(blankMapValues(),
                    NavigationContext.empty().withPgmContext(pgmContext), "");
            assertThat(request.pgmEnter()).isEqualTo(enter);
            assertThat(request.pgmReenter()).isEqualTo(reenter);

            // The flag has exactly one home: the read-through agrees with the area itself, always.
            assertThat(request.pgmEnter()).isEqualTo(request.navigationContext().isEnter());
            assertThat(request.pgmReenter()).isEqualTo(request.navigationContext().isReenter());
            assertThat(request.navigationContext().pgmContext()).isEqualTo(pgmContext);
        }

        @Test
        @DisplayName("the named context values are the copybook's, and the state survives JSON")
        void theContextStateSurvivesTheRoundTrip() {
            assertThat(NavigationContext.PGM_CONTEXT_ENTER).isZero();
            assertThat(NavigationContext.PGM_CONTEXT_REENTER).isEqualTo(1);

            UserAddRequest entered = requestOf(blankMapValues(),
                    NavigationContext.empty().withPgmEnter(), "");
            UserAddRequest reentered = requestOf(blankMapValues(),
                    NavigationContext.empty().withPgmReenter(), "");

            UserAddRequest restoredEnter = deserialise(serialise(entered));
            assertThat(restoredEnter.pgmEnter()).isTrue();
            assertThat(restoredEnter.pgmReenter()).isFalse();

            UserAddRequest restoredReenter = deserialise(serialise(reentered));
            assertThat(restoredReenter.pgmReenter()).isTrue();
            assertThat(restoredReenter.pgmEnter()).isFalse();
            assertThat(restoredReenter.navigationContext())
                    .isEqualTo(reentered.navigationContext());
        }

        @Test
        @DisplayName("an absent communication area is representable - the EIBCALEN = 0 case")
        void anAbsentCommunicationAreaIsRepresentable() {
            // COUSR01C.cbl:78 tests IF EIBCALEN = 0 and transfers to COSGN00C without touching the
            // dataset, so a MISSING area is a legitimate, handled input. The canonical constructor is
            // the implicit one and stores every component exactly as given, null included, which is what
            // lets a controller reproduce that branch. Both predicates are false with no area: with no
            // CDEMO-PGM-CONTEXT byte there is no context to assert. This is the third branch of each
            // read-through predicate and it is driven here.
            UserAddRequest absent = requestOf(blankMapValues(), null, "");
            assertThat(absent.navigationContext()).isNull();
            assertThat(absent.pgmEnter()).isFalse();
            assertThat(absent.pgmReenter()).isFalse();
            assertThat(validate(absent))
                    .as("no constraint may reject the cold-start payload")
                    .isEmpty();

            UserAddRequest restored = deserialise(serialise(absent));
            assertThat(restored.navigationContext()).isNull();
            assertThat(restored).isEqualTo(absent);
        }

        @Test
        @DisplayName("no CDEMO-CU0n-INFO extension block is carried, because COUSR01C declares none")
        void noExtensionBlockIsCarried() {
            // Verified against the source: `grep -c 'CDEMO-CU0[0-9]-INFO' app/cbl/COUSR01C.cbl` returns
            // 0. COUSR01C and COSGN00C are the only two of the five user programs with no extension;
            // COUSR00C:67-75, COUSR02C:50-58 and COUSR03C:50-58 each declare a 34-byte one for their
            // paging state. Adding a block here "for consistency" would put a field on the wire that the
            // Add User program never sees, and is exactly what B5 forbids: this screen has no list to
            // page, so it has no paging state.
            assertThat(UserAddRequest.class.getRecordComponents()).hasSize(COMPONENT_COUNT);
            for (RecordComponent component : UserAddRequest.class.getRecordComponents()) {
                assertThat(component.getName().toLowerCase(Locale.ROOT))
                        .as("%s must not be an extension block", component.getName())
                        .doesNotContain("cu00").doesNotContain("cu01").doesNotContain("cu02")
                        .doesNotContain("cu03").doesNotContain("extension").doesNotContain("page");
            }
            // The area carried is the plain 160 bytes, with nothing appended behind it.
            assertThat(populatedRequest().navigationContext().toFixedWidth(codec()))
                    .hasSize(NavigationContext.COMMAREA_LENGTH);
            assertThat(jsonMembersOf(populatedRequest()))
                    .doesNotContain("cdemoCu01Info", "pageNum", "selectedRow");
        }
    }

    // =================================================================================================
    // 6. VALIDATION IS BOUNDED BY WHAT THE PROGRAM DOES.
    //
    // Width constraints only. COUSR01C.cbl:117-151 is a single EVALUATE TRUE whose first matching arm
    // wins, and it ACCEPTS a blank value on all five input fields, answering each with its own message
    // and re-painting the screen:
    //
    //   arm 1  :118 FNAMEI   -> :120 'First Name can NOT be empty...'  then :122 MOVE -1 TO FNAMEL
    //   arm 2  :124 LNAMEI   -> :126 'Last Name can NOT be empty...'   then :128 MOVE -1 TO LNAMEL
    //   arm 3  :130 USERIDI  -> :132 'User ID can NOT be empty...'     then :134 MOVE -1 TO USERIDL
    //   arm 4  :136 PASSWDI  -> :138 'Password can NOT be empty...'    then :140 MOVE -1 TO PASSWDL
    //   arm 5  :142 USRTYPEI -> :144 'User Type can NOT be empty...'   then :146 MOVE -1 TO USRTYPEL
    //   other  :148          -> no message; :149 MOVE -1 TO FNAMEL then :150 CONTINUE
    //
    // Every blank field therefore produces a SCREEN WITH A MESSAGE, never a framework rejection. A
    // @NotBlank or @NotNull anywhere on this type would turn each of those five answers into a rejection
    // before the controller ever ran - losing the message, losing the ordering and losing the cursor
    // placement - which is a changed business rule and forbidden.
    // =================================================================================================

    @Nested
    @DisplayName("Validation - @Size maxima only, never a presence or format constraint")
    class ValidationConstraints {

        @Test
        @DisplayName("carries thirteen @Size constraints and no other constraint at all")
        void carriesOnlySizeConstraints() {
            int sized = 0;
            for (RecordComponent component : UserAddRequest.class.getRecordComponents()) {
                for (Annotation annotation : component.getAccessor().getAnnotations()) {
                    String type = annotation.annotationType().getName();
                    assertThat(type)
                            .as("%s carries a constraint the program does not perform",
                                    component.getName())
                            .doesNotContain("NotBlank")
                            .doesNotContain("NotNull")
                            .doesNotContain("NotEmpty")
                            .doesNotContain("Pattern")
                            .doesNotContain("Email")
                            .doesNotContain("Digits")
                            .doesNotContain("Positive")
                            .doesNotContain("AssertTrue");
                    if (annotation instanceof Size) {
                        sized++;
                    }
                }
            }
            assertThat(sized)
                    .as("the twelve screen fields plus the AID token; the commarea is a typed member "
                            + "and carries none")
                    .isEqualTo(DFHMDF_NAMED + 1);
        }

        @Test
        @DisplayName("the communication area carries no constraint, because its own type enforces its own")
        void theCommareaCarriesNoConstraint() {
            RecordComponent commarea =
                    UserAddRequest.class.getRecordComponents()[DFHMDF_NAMED];
            assertThat(commarea.getName()).isEqualTo("navigationContext");
            assertThat(commarea.getAccessor().getAnnotations())
                    .as("the 160-byte geometry is NavigationContext's own invariant, refused at "
                            + "construction rather than reported as a violation")
                    .isEmpty();
        }

        @ParameterizedTest(name = "{0} is @Size(max = {1})")
        @CsvSource({
            "trnName,  4", "title01, 40", "curDate,  8", "pgmName,  8",
            "title02, 40", "curTime,  8", "fName,   20", "lName,   20",
            "userId,   8", "passwd,   8", "usrType,  1", "errMsg,  78",
        })
        @DisplayName("each @Size(max) is the width its symbolic-map item declares")
        void eachSizeMaximumEqualsTheDeclaredWidth(String member, int width) {
            int position = MAP_MEMBERS.indexOf(member);
            assertThat(position).as("%s must be a map member", member).isNotNegative();

            RecordComponent component = UserAddRequest.class.getRecordComponents()[position];
            Size size = component.getAccessor().getAnnotation(Size.class);
            assertThat(size)
                    .as("%s must be width-constrained, because %s is PIC X(%d)", member,
                            SYMBOLIC_MAP_ITEMS.get(position), width)
                    .isNotNull();
            assertThat(size.max()).isEqualTo(width).isEqualTo(DECLARED_WIDTHS.get(position));
            assertThat(size.min())
                    .as("a minimum would be a presence constraint by another name")
                    .isZero();
        }

        @Test
        @DisplayName("the AID token is width-constrained to five as well")
        void theAidTokenIsWidthConstrained() {
            RecordComponent aid = UserAddRequest.class.getRecordComponents()[DFHMDF_NAMED + 1];
            assertThat(aid.getName()).isEqualTo("aid");
            Size size = aid.getAccessor().getAnnotation(Size.class);
            assertThat(size).isNotNull();
            assertThat(size.max()).isEqualTo(UserAddRequest.AID_LENGTH)
                    .isEqualTo(PfKeyResolver.AID_TOKEN_LENGTH);
            assertThat(size.min()).isZero();
        }

        @Test
        @DisplayName("a wholly blank instance is valid, because the program answers blanks with text")
        void aBlankInstanceIsValid() {
            // The proof that no presence constraint exists: twelve empty strings, no commarea, no AID,
            // and Bean Validation has nothing to say. Every one of the five arms above must be reachable
            // by the controller, and it can only be reached if the payload arrives.
            assertThat(validate(requestOf(blankMapValues(), null, ""))).isEmpty();
            assertThat(validate(requestOf(blankMapValues(), NavigationContext.empty(), ""))).isEmpty();
        }

        @Test
        @DisplayName("a null in every position is valid too, so nothing precedes the program's own test")
        void aNullInEveryPositionIsValid() {
            UserAddRequest nulls = requestOf(nullMapValues(), null, null);
            assertThat(validate(nulls)).isEmpty();
            for (String value : mapValuesOf(nulls)) {
                assertThat(value).isNull();
            }
            assertThat(nulls.aid()).isNull();
            assertThat(nulls.navigationContext()).isNull();
        }

        @ParameterizedTest(name = "{0} at {1} + 1 characters is exactly one violation")
        @CsvSource({
            "trnName,  4", "title01, 40", "curDate,  8", "pgmName,  8",
            "title02, 40", "curTime,  8", "fName,   20", "lName,   20",
            "userId,   8", "passwd,   8", "usrType,  1", "errMsg,  78",
        })
        @DisplayName("one character over the declared width is exactly one violation, on that member")
        void oneCharacterTooManyIsOneViolation(String member, int width) {
            // Parameterised across all twelve so each maximum is proved WIRED rather than merely
            // declared: a @Size that names the wrong field, or a width copied from a sibling screen,
            // fails here on the member it belongs to instead of hiding behind a passing neighbour.
            List<String> values = withMember(blankMapValues(), member, "X".repeat(width + 1));
            Set<ConstraintViolation<UserAddRequest>> violations =
                    validate(requestOf(values, NavigationContext.empty(), ""));

            assertThat(violations).hasSize(1);
            ConstraintViolation<UserAddRequest> violation = violations.iterator().next();
            assertThat(violation.getPropertyPath()).hasToString(member);
            assertThat(violation.getConstraintDescriptor().getAnnotation())
                    .isInstanceOf(Size.class);

            // Exactly the declared width is accepted, so the boundary is at the width and not before it.
            assertThat(validate(requestOf(withMember(blankMapValues(), member, "X".repeat(width)),
                    NavigationContext.empty(), ""))).isEmpty();
        }

        @Test
        @DisplayName("an over-wide AID token is refused as well, and two failures report as two")
        void twoOverWideValuesReportAsTwoViolations() {
            Set<ConstraintViolation<UserAddRequest>> aidOnly = validate(
                    requestOf(blankMapValues(), NavigationContext.empty(), "ENTER!"));
            assertThat(aidOnly).hasSize(1);
            assertThat(aidOnly.iterator().next().getPropertyPath()).hasToString("aid");

            assertThat(validate(requestOf(
                    withMember(blankMapValues(), "usrType", "AU"),
                    NavigationContext.empty(), "ENTER!")))
                    .as("each constraint reports independently; neither masks the other")
                    .hasSize(2);
        }

        @Test
        @DisplayName("SPACES and LOW-VALUES are both blank to the program, and both are carried intact")
        void spacesAndLowValuesAreBothCarried() {
            // The predicate the COBOL actually writes is `= SPACES OR LOW-VALUES` - COUSR01C.cbl:118,
            // :124, :130, :136 and :142 all test both. The two are distinct byte patterns and reach the
            // program by different routes: a user who types nothing leaves SPACES, and
            // COUSR01C.cbl:85 does MOVE LOW-VALUES TO COUSR1AO on first entry. A payload must therefore
            // be able to state each one AS ITSELF, neither trimmed away nor coerced to absent, which is
            // precisely why config.WebConfig disables empty-string-to-null coercion and registers no
            // trimming converter.
            String spaces = " ".repeat(UserAddRequest.FNAME_LENGTH);
            String lowValues = "\u0000".repeat(UserAddRequest.FNAME_LENGTH);
            assertThat(spaces).isNotEqualTo(lowValues).hasSameSizeAs(lowValues);

            UserAddRequest spaced = requestOf(withMember(blankMapValues(), "fName", spaces),
                    NavigationContext.empty(), "");
            UserAddRequest lowValued = requestOf(withMember(blankMapValues(), "fName", lowValues),
                    NavigationContext.empty(), "");

            assertThat(validate(spaced)).as("a run of spaces fills the field exactly").isEmpty();
            assertThat(validate(lowValued)).as("and so does a run of LOW-VALUES").isEmpty();

            assertThat(spaced.fName()).isEqualTo(spaces).isNotNull().isBlank()
                    .hasSize(UserAddRequest.FNAME_LENGTH);
            assertThat(lowValued.fName()).isEqualTo(lowValues).isNotNull()
                    .hasSize(UserAddRequest.FNAME_LENGTH);
            assertThat(spaced).isNotEqualTo(lowValued);

            // Both survive the wire distinctly. Were either coerced, the controller could no longer tell
            // a first-entry screen from a screen a user cleared, and arm one would fire on the wrong
            // input.
            assertThat(deserialise(serialise(spaced)).fName()).isEqualTo(spaces);
            assertThat(deserialise(serialise(lowValued)).fName()).isEqualTo(lowValues);
            assertThat(deserialise(serialise(spaced))).isNotEqualTo(deserialise(serialise(lowValued)));
        }

        @Test
        @DisplayName("an empty value is distinct from a space-filled one and from an absent one")
        void emptyBlankAndAbsentAreThreeDistinctStates() {
            // A three-way distinction the type must preserve, because CICS reports them differently:
            // no input at all, a field cleared to spaces, and a member the client omitted entirely.
            UserAddRequest empty = requestOf(withMember(blankMapValues(), "userId", ""),
                    NavigationContext.empty(), "");
            UserAddRequest padded = requestOf(withMember(blankMapValues(), "userId",
                    " ".repeat(UserAddRequest.USERID_LENGTH)), NavigationContext.empty(), "");
            UserAddRequest absent = requestOf(withMember(blankMapValues(), "userId", null),
                    NavigationContext.empty(), "");

            assertThat(empty.userId()).isEmpty();
            assertThat(padded.userId()).isBlank().hasSize(UserAddRequest.USERID_LENGTH);
            assertThat(absent.userId()).isNull();
            assertThat(empty).isNotEqualTo(padded).isNotEqualTo(absent);
            assertThat(padded).isNotEqualTo(absent);
            for (UserAddRequest request : List.of(empty, padded, absent)) {
                assertThat(validate(request))
                        .as("all three are legitimate inputs the program handles")
                        .isEmpty();
            }
        }
    }


    // =================================================================================================
    // 7. SERIALISATION.
    //
    // A PIC X(n) value is space-padded to its declared width and is not trimmed on read unless the COBOL
    // trims. The mapper used here is configured as config.WebConfig configures the application's, for
    // exactly that reason - see webConfigEquivalentMapper.
    // =================================================================================================

    @Nested
    @DisplayName("Serialisation - padding survives, names are untransformed")
    class JsonRoundTrip {

        @Test
        @DisplayName("the mapper this suite uses carries the settings the module configures")
        void theMapperMatchesTheModuleConfiguration() {
            ObjectMapper mapper = webConfigEquivalentMapper();
            assertThat(mapper.getDeserializationConfig()
                    .isEnabled(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS))
                    .as("no value derived from a PIC S9(p)V99 clause may route through a double")
                    .isTrue();
            assertThat(mapper.getFactory()
                    .isEnabled(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN))
                    .as("a scale-2 amount serialises as 100.00, never as 1.0E+2")
                    .isTrue();
            assertThat(mapper.getDeserializationConfig()
                    .isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS))
                    .as("a malformed body is refused outright rather than half-read")
                    .isTrue();
            assertThat(mapper.getDeserializationConfig()
                    .isEnabled(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT))
                    .as("an empty or all-spaces PIC X(n) value is real screen data, not an absent one; "
                            + "a default mapper would make the blank-value cases above assert the "
                            + "wrong thing")
                    .isFalse();
        }

        @Test
        @DisplayName("the property names are the component names, untransformed")
        void thePropertyNamesAreUntransformed() {
            // A record's components are its JSON properties, and no naming strategy is applied, so each
            // property still traces 1:1 to an xxxI item. A snake_case or kebab-case strategy would break
            // that trace and would silently rename twelve screen fields.
            Set<String> members = jsonMembersOf(populatedRequest());
            assertThat(members).containsAll(MAP_MEMBERS).containsAll(STATE_MEMBERS);
            for (String member : members) {
                assertThat(member)
                        .as("%s must be a plain camelCase component name", member)
                        .doesNotContain("_").doesNotContain("-")
                        .isEqualTo(member.trim());
            }
        }

        @Test
        @DisplayName("a space-padded payload survives serialise then deserialise byte for byte")
        void spacePaddingSurvivesTheRoundTrip() {
            // The brief's own fixture: twenty-character space-padded names, an eight-character password
            // and a seventy-eight space message - the state COUSR01C.cbl:75-76 leaves the message in when
            // there is nothing to report.
            List<String> values = withMember(withMember(withMember(withMember(blankMapValues(),
                    "fName", codec().movePicX("JANE", UserAddRequest.FNAME_LENGTH)),
                    "lName", codec().movePicX("DOE", UserAddRequest.LNAME_LENGTH)),
                    "passwd", codec().movePicX("PWD7CHR", UserAddRequest.PASSWD_LENGTH)),
                    "errMsg", " ".repeat(UserAddRequest.ERRMSG_LENGTH));
            values = withMember(values, "title01", ScreenTitles.CCDA_TITLE01);
            UserAddRequest original = requestOf(values,
                    NavigationContext.empty().withPgmReenter(),
                    PfKeyResolver.AidKey.ENTER.token());

            UserAddRequest restored = deserialise(serialise(original));
            assertThat(restored).isEqualTo(original);
            assertThat(restored.hashCode()).isEqualTo(original.hashCode());

            assertThat(restored.fName())
                    .isEqualTo("JANE" + " ".repeat(UserAddRequest.FNAME_LENGTH - 4))
                    .hasSize(UserAddRequest.FNAME_LENGTH)
                    .endsWith(" ");
            assertThat(restored.lName())
                    .isEqualTo("DOE" + " ".repeat(UserAddRequest.LNAME_LENGTH - 3))
                    .hasSize(UserAddRequest.LNAME_LENGTH)
                    .endsWith(" ");
            assertThat(restored.passwd())
                    .as("the eight bytes SEC-USR-PWD receives must arrive unaltered")
                    .isEqualTo("PWD7CHR ")
                    .hasSize(UserAddRequest.PASSWD_LENGTH);
            assertThat(restored.errMsg())
                    .as("78 spaces must come back as 78 spaces, neither trimmed nor nulled")
                    .isEqualTo(" ".repeat(UserAddRequest.ERRMSG_LENGTH))
                    .hasSize(UserAddRequest.ERRMSG_LENGTH);
            assertThat(restored.title01())
                    .isEqualTo(ScreenTitles.CCDA_TITLE01)
                    .hasSize(UserAddRequest.TITLE01_LENGTH)
                    .endsWith(" ");
        }

        @Test
        @DisplayName("a fully painted screen round-trips with every member at its declared width")
        void aFullyPaintedScreenRoundTrips() {
            UserAddRequest original = requestOf(paddedMapValues(),
                    NavigationContext.empty().withPgmEnter(), "");
            UserAddRequest restored = deserialise(serialise(original));
            assertThat(restored).isEqualTo(original);

            List<String> values = mapValuesOf(restored);
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                assertThat(values.get(index))
                        .as("%s must come back at its declared width", MAP_MEMBERS.get(index))
                        .hasSize(DECLARED_WIDTHS.get(index))
                        .isBlank();
            }
            assertThat(jsonMembersOf(restored)).hasSize(COMPONENT_COUNT);
        }

        @Test
        @DisplayName("an empty value stays empty and is not coerced to absent")
        void anEmptyValueIsNotCoercedToNull() {
            UserAddRequest restored = deserialise(serialise(
                    requestOf(blankMapValues(), NavigationContext.empty(), "")));
            for (String value : mapValuesOf(restored)) {
                assertThat(value).isNotNull().isEmpty();
            }
            assertThat(restored.aid()).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("an absent member deserialises as absent rather than as a default")
        void anAbsentMemberDeserialisesAsAbsent() {
            // A client that omits a member is stating "no value", and the type must carry that as null
            // rather than substituting an empty string: COUSR01C's blank test distinguishes neither, but
            // the parity differ compares field images and a substituted default would be a fabricated
            // one.
            UserAddRequest restored = deserialise("{\"trnName\":\"CU01\"}");
            assertThat(restored.trnName()).isEqualTo(TRANSACTION_ID);
            assertThat(restored.fName()).isNull();
            assertThat(restored.userId()).isNull();
            assertThat(restored.navigationContext()).isNull();
            assertThat(restored.aid()).isNull();
            assertThat(restored.pgmEnter()).isFalse();
            assertThat(restored.pgmReenter()).isFalse();
        }

        @Test
        @DisplayName("the emitted JSON names exactly the fourteen members, in no fewer and no more")
        void theEmittedJsonNamesExactlyTheFourteenMembers() {
            String json = serialise(populatedRequest());
            for (String member : EXPECTED_JSON_MEMBERS) {
                assertThat(json).contains("\"" + member + "\"");
            }
            assertThat(jsonMembersOf(populatedRequest()))
                    .hasSize(COMPONENT_COUNT)
                    .containsExactlyInAnyOrderElementsOf(EXPECTED_JSON_MEMBERS);
            // No dataset name reaches the wire either (gate G46): the payload names screen fields, and
            // where the data lives is configuration.
            assertThat(json).doesNotContain("AWS.M2.CARDDEMO").doesNotContain("VSAM")
                    .doesNotContain("USRSEC");
        }
    }

    // =================================================================================================
    // 8. SECURITY POSTURE (gate G41, practice B6).
    //
    // Neither weakened nor strengthened. COSGN00C.cbl:223 compares SEC-USR-PWD against what was keyed,
    // in the clear, and COUSR01C.cbl:157 stores what was keyed into SEC-USR-PWD PIC X(08), in the clear.
    // Introducing a hash would change the outcome of that comparison and would require a framework this
    // migration excludes; removing the field would break the write. Plaintext credential handling is an
    // inherited property of the legacy design and an explicit non-goal of this migration, recorded here
    // so the characteristic stays visible rather than buried in generated code.
    // =================================================================================================

    @Nested
    @DisplayName("Security posture - plaintext by parity, withheld from diagnostics")
    class SecurityPosture {

        @Test
        @DisplayName("passwd is a plaintext String of the width CSUSR01Y declares")
        void thePasswordIsAPlaintextStringOfEight() {
            RecordComponent passwd =
                    UserAddRequest.class.getRecordComponents()[MAP_MEMBERS.indexOf("passwd")];
            assertThat(passwd.getName()).isEqualTo("passwd");
            assertThat(passwd.getType())
                    .as("a String, not a char[], not a wrapper type and not an encoded form")
                    .isEqualTo(String.class);
            assertThat(UserAddRequest.PASSWD_LENGTH)
                    .as("SEC-USR-PWD PIC X(08) at app/cpy/CSUSR01Y.cpy:21")
                    .isEqualTo(SEC_USR_PWD_LENGTH);
        }

        @Test
        @DisplayName("an eight-character password survives the round trip byte for byte")
        void anEightCharacterPasswordSurvivesTheRoundTrip() {
            String keyed = "P4dNotRl".substring(0, UserAddRequest.PASSWD_LENGTH);
            UserAddRequest original = requestOf(withMember(blankMapValues(), "passwd", keyed),
                    NavigationContext.empty(), "");

            UserAddRequest restored = deserialise(serialise(original));
            assertThat(restored.passwd())
                    .as("the value the program stores must arrive unaltered; any transformation here "
                            + "would change what COUSR01C.cbl:157 writes into USRSEC")
                    .isEqualTo(keyed)
                    .isEqualTo(original.passwd())
                    .hasSize(UserAddRequest.PASSWD_LENGTH);
            assertThat(restored).isEqualTo(original);
        }

        @Test
        @DisplayName("no hashing, encoder, token or security framework type is reachable")
        void noSecurityFrameworkTypeIsReachable() {
            List<String> forbidden = List.of("PasswordEncoder", "BCrypt", "MessageDigest", "Hash",
                    "org.springframework.security", "jwt", "Jwt", "Cipher", "SecretKey", "Base64",
                    "Encrypt", "Digest");
            for (String name : reachableTypeNames()) {
                for (String marker : forbidden) {
                    assertThat(name)
                            .as("%s suggests %s; strengthening the comparison would change observable "
                                    + "behaviour and is out of scope", name, marker)
                            .doesNotContain(marker);
                }
            }
        }

        @Test
        @DisplayName("the DRK attribute on PASSWD is presentation masking, never storage masking")
        void theDarkAttributeIsPresentationOnly() {
            // app/bms/COUSR01.bms:126 declares PASSWD with ATTRB=(DRK,FSET,UNPROT). DRK is terminal
            // non-display: it stops the 3270 rendering the characters as they are typed. It says nothing
            // about how the value is stored, transmitted or compared, and mistaking it for hashing would
            // be a security claim the legacy design never made.
            String keyed = "PLAINTXT";
            UserAddRequest request = requestOf(withMember(blankMapValues(), "passwd", keyed),
                    NavigationContext.empty(), "");
            assertThat(request.passwd())
                    .as("the payload carries the keyed characters, unmasked and uncounted")
                    .isEqualTo(keyed)
                    .hasSize(UserAddRequest.PASSWD_LENGTH);
            assertThat(serialise(request))
                    .as("and they are on the wire, because that is what the program stores")
                    .contains(keyed);
        }

        @Test
        @DisplayName("the payload does not upper-case, hash or otherwise normalise what it carries")
        void thePayloadIsAPassiveCarrier() {
            // COUSR01C.cbl:154-158 executes five PLAIN MOVEs with no FUNCTION UPPER-CASE anywhere in the
            // program. COSGN00C.cbl:132-137 does upper-case both the identifier and the password before
            // comparing, and that asymmetry between two programs reading the same file is real. It
            // belongs to the two services; the payload must not anticipate it, or a service could no
            // longer distinguish what was keyed from what was folded.
            UserAddRequest request = requestOf(withMember(withMember(withMember(blankMapValues(),
                    "userId", "newusr01"), "passwd", "lowerpwd"), "fName", "jane"),
                    NavigationContext.empty(), "");

            assertThat(request.userId()).isEqualTo("newusr01")
                    .isNotEqualTo("newusr01".toUpperCase(Locale.ROOT));
            assertThat(request.passwd()).isEqualTo("lowerpwd")
                    .isNotEqualTo("lowerpwd".toUpperCase(Locale.ROOT));
            assertThat(request.fName()).isEqualTo("jane")
                    .as("nor is a name padded, trimmed or title-cased on the way in")
                    .hasSize(4);
            assertThat(deserialise(serialise(request)).userId()).isEqualTo("newusr01");
            assertThat(deserialise(serialise(request)).passwd()).isEqualTo("lowerpwd");
        }
    }

    // =================================================================================================
    // 9. DIAGNOSTICS.
    //
    // Carrying the credential in the clear is required for parity; broadcasting it into a log line, an
    // exception message or a debugger view is not, and the two concerns separate cleanly.
    // =================================================================================================

    @Nested
    @DisplayName("Diagnostics - the password is withheld unconditionally")
    class Diagnostics {

        @Test
        @DisplayName("toString withholds the password and reports the two names by length only")
        void toStringWithholdsThePassword() {
            UserAddRequest request = populatedRequest();
            String rendered = request.toString();

            assertThat(rendered)
                    .startsWith("UserAddRequest[")
                    .endsWith("]")
                    .doesNotContain(request.passwd())
                    .contains("passwd=" + UserAddRequest.PASSWORD_MASK)
                    .contains("trnName=" + TRANSACTION_ID)
                    .contains("pgmName=" + PROGRAM_NAME)
                    .contains("userId=NEWUSR01")
                    .contains("aid=" + PfKeyResolver.AidKey.ENTER.token());

            // A given name and a family name are personal data with no safely revealable part, so the
            // rendering reports their shape and nothing else. This is a declared property of the type
            // and is asserted as declared.
            assertThat(rendered)
                    .doesNotContain("JANE")
                    .doesNotContain("DOE")
                    .contains(SensitiveDiagnostics.describeText(request.fName()))
                    .contains(SensitiveDiagnostics.describeText(request.lName()));
        }

        @Test
        @DisplayName("the mask is unconditional, so an absent password is masked too")
        void theMaskIsUnconditional() {
            // Unconditional matters: a mask applied only when a value is present would leak, by its own
            // absence, the fact that no password was keyed - and would put a branch into a diagnostic.
            assertThat(requestOf(nullMapValues(), null, null).toString())
                    .contains("passwd=" + UserAddRequest.PASSWORD_MASK)
                    .contains("navigationContext=null");
            assertThat(requestOf(blankMapValues(), NavigationContext.empty(), "").toString())
                    .contains("passwd=" + UserAddRequest.PASSWORD_MASK);

            // The declared mask is the module's single marker and withholds the LENGTH as well as the
            // value. This diverges from the brief's description of an eight-character mask, and the
            // declared form is what is asserted: a fixed-width mask would state how many characters were
            // keyed, which is itself information about a credential.
            assertThat(UserAddRequest.PASSWORD_MASK)
                    .isEqualTo(SensitiveDiagnostics.REDACTED)
                    .doesNotContain("*")
                    .isNotEqualTo("*".repeat(UserAddRequest.PASSWD_LENGTH));
        }

        @Test
        @DisplayName("equals and hashCode still consider the real password")
        void equalsStillConsidersTheRealPassword() {
            UserAddRequest one = requestOf(withMember(blankMapValues(), "passwd", "PWDFAKE1"),
                    NavigationContext.empty(), "");
            UserAddRequest other = requestOf(withMember(blankMapValues(), "passwd", "PWDFAKE2"),
                    NavigationContext.empty(), "");
            UserAddRequest sameAsOne = requestOf(withMember(blankMapValues(), "passwd", "PWDFAKE1"),
                    NavigationContext.empty(), "");

            // The masking override changes a diagnostic and nothing else: equals and hashCode remain the
            // generated component-wise implementations, so two requests differing only in password are
            // still different values even though they render identically.
            assertThat(one).isEqualTo(sameAsOne).isNotEqualTo(other);
            assertThat(one.hashCode()).isEqualTo(sameAsOne.hashCode());
            assertThat(one).isNotEqualTo(null).isNotEqualTo("not a request");
            assertThat(one.toString()).isEqualTo(other.toString());
        }

        @Test
        @DisplayName("every component is accounted for in the rendering, so nothing is silently dropped")
        void everyComponentIsAccountedForInTheRendering() {
            // A diagnostic that omitted a member would be misleading in a different way from one that
            // over-reported: a reviewer could not tell whether the field was empty or unprinted. Every
            // component name appears, whatever its value policy.
            String rendered = populatedRequest().toString();
            for (String component : componentNames()) {
                assertThat(rendered)
                        .as("%s must appear in the rendering", component)
                        .contains(component + "=");
            }
        }
    }

}
