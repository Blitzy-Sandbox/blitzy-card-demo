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
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.user.dto.UserListRequest.UserListRow;
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
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Unit tests for {@link UserListRequest} - the inbound payload of {@code GET /api/users}, CICS
 * transaction {@code CU00}, program {@code app/cbl/COUSR00C.cbl}, map {@code COUSR0A} of mapset
 * {@code COUSR00}.
 *
 * <p>This is the widest payload in the {@code user.dto} package: {@value #DFHMDF_NAMED} screen
 * fields, and the only one of the five {@code user} screens that also carries a
 * {@value UserListRequest#CU00_INFO_LENGTH}-byte communication-area extension of its own. The
 * subject here is the <em>payload type</em>. Nothing in this file starts a Spring context, builds a
 * {@code MockMvc}, or touches a controller, a service or a repository: the HTTP projection, the
 * paging arithmetic and the selection routing belong to {@code UserMenuControllerTest}, and
 * re-asserting them here would make two suites fail for one cause.
 *
 * <h2>Project rules</h2>
 *
 * {@code review_rules} reports <strong>"No user rules provided."</strong> - that single line is the
 * entire document. No rule is invented here, and the absence of rules is <em>not</em> treated as
 * licence to assert less. The binding constraints are the enterprise best-practice substitutes
 * {@code B1}-{@code B12} recorded in the plan, each named below with the one thing it requires of
 * this file. The plan holds the full text of every practice; only the ruling is restated.
 *
 * <ul>
 *   <li><strong>B1</strong> - imports are confined to the JDK, JUnit Jupiter, AssertJ,
 *       {@code jakarta.validation} and the Jackson already on the test classpath, plus the module's
 *       own {@code common}, {@code user.model} and {@code user.dto} types. No new coordinate, and
 *       nothing from the plan's exclusion list. Mockito is on the classpath and deliberately unused:
 *       a payload record has no collaborator to stand in for.</li>
 *   <li><strong>B2</strong> - JUnit 5 Jupiter API only.</li>
 *   <li><strong>B3</strong> - the reference tree is neither written nor <em>read</em>. Every
 *       expectation below is a {@code private static final} constant carrying the file and line it
 *       was transcribed from, so this suite is hermetic and independent of the working directory.
 *       Sibling suites in this package instead parse the copybook and mapset at run time from the
 *       classpath; that difference is recorded, not reconciled - both prove the same contract, and
 *       this file's own ruling is the hermetic one.</li>
 *   <li><strong>B4</strong> - conflicts are documented rather than resolved. Four are relevant here
 *       and each has its own case below: the internal display table whose widths differ from the
 *       map's ({@link RowWidthsComeFromCsusr01y}), the {@code DFHBMSCA} copybook that
 *       {@code COUSR00C.cbl:84} includes without referencing a single one of its constants, the
 *       constructor-versus-annotation split for over-width values
 *       ({@link ValidationConstraints}), and the divergences between this file's brief and the type
 *       as declared, listed at the end of these notes.</li>
 *   <li><strong>B5</strong> - nothing is asserted into or out of existence for symmetry with a
 *       sibling payload. The {@value #DFHMDF_NAMED}-field census and the
 *       {@value UserListRequest#CU00_INFO_LENGTH}-byte extension are asserted exactly as the source
 *       declares them, and no member is expected merely because the narrower {@code CU01},
 *       {@code CU02} and {@code CU03} payloads have one.</li>
 *   <li><strong>B6</strong> - the security posture is neither weakened nor strengthened. This screen
 *       has no password field at all, which {@link SecurityPosture} asserts rather than assumes; no
 *       encoder, token or Spring Security type is introduced.</li>
 *   <li><strong>B7</strong> - nothing here reads a wall clock, draws a random value or depends on
 *       another case having run. The one time-derived expectation is driven from
 *       {@link Clock#fixed(Instant, java.time.ZoneId)}.</li>
 *   <li><strong>B8</strong> - every codec call names its {@link Charset} explicitly; no overload
 *       that omits it is used and no platform default is relied on. Every import is written out
 *       individually - there is no wildcard import in this file - and no dataset name appears in
 *       it.</li>
 *   <li><strong>B9</strong> - every field of this class is {@code static final} and immutable. With
 *       {@value #DFHMDF_NAMED} members the pull toward caching one populated instance in a mutable
 *       static field is real and is refused: each case builds its own, and JUnit's default
 *       per-method lifecycle does the isolating.</li>
 *   <li><strong>B10</strong> - this suite ships in the same phase as the type it measures, so a
 *       drift from the mapset is traceable to the decision that caused it.</li>
 *   <li><strong>B11</strong> - fixed-width, padding and truncation work goes through
 *       {@link FixedWidthCodec} and {@link FixedWidthRecord}. No third-party copybook parser is
 *       used, no assertion substitutes {@link String#substring(int, int)} for a COBOL {@code MOVE},
 *       and the zero-filled page-number image is produced by the codec rather than by
 *       {@code String.format}.</li>
 *   <li><strong>B12</strong> - the environmental limit is stated rather than absorbed. See the
 *       provenance note immediately below.</li>
 * </ul>
 *
 * <h2>Provenance of every expected value (B12)</h2>
 *
 * COBOL cannot be executed in this environment - eight independently verified blockers are recorded
 * in the plan as risk {@code R-A}, among them a disabled indexed-file handler, absent Language
 * Environment services and the absence of any CICS emulator. Every expectation below is therefore
 * <strong>statically derived</strong> by reading the source, not captured from a run. The lines used
 * are:
 *
 * <ul>
 *   <li>{@code app/cpy-bms/COUSR00.CPY} - {@code 01 COUSR0AI.} at line 17, its
 *       {@code 02 FILLER PIC X(12)} {@code TIOAPFX} prefix at line 18, the
 *       {@value #DFHMDF_NAMED} {@code xxxI} items at the lines transcribed into
 *       {@link #COPYBOOK_LINES}, and {@code 01 COUSR0AO REDEFINES COUSR0AI} at line 373. The file
 *       holds 60 {@code REDEFINES} in total: {@value #DFHMDF_NAMED} per-field overlays, which are
 *       this file's subject, and that one group-level overlay, which is
 *       {@code UserListResponseTest}'s.</li>
 *   <li>{@code app/bms/COUSR00.bms} - {@code COUSR00 DFHMSD CTRL=(ALARM,FREEKB)} at line 19,
 *       {@code COUSR0A DFHMDI COLUMN=1} at line 26, {@code SIZE=(24,80)} at line 28,
 *       {@code DFHMSD TYPE=FINAL} at line 459, and {@value #DFHMDF_TOTAL} {@code DFHMDF} definitions
 *       of which exactly {@value #DFHMDF_NAMED} are name-labelled.</li>
 *   <li>{@code app/cbl/COUSR00C.cbl} - {@code WS-PGMNAME VALUE 'COUSR00C'} at 36,
 *       {@code WS-TRANID VALUE 'CU00'} at 37, {@code WS-MESSAGE PIC X(80)} at 38,
 *       {@code 01 WS-USER-DATA} at 56 with {@code 02 USER-REC OCCURS 10 TIMES} at 57,
 *       {@code COPY COCOM01Y} at 66 followed by {@code 05 CDEMO-CU00-INFO} at 67-75 with its two
 *       {@code 88}-levels at 72 and 73, the cold-start test at 110, the ENTER-to-REENTER transition
 *       at 115-117, {@code EVALUATE EIBAID} at 122-137, the ten selection arms at 151-183, the
 *       abbreviated combined relation at 187-188, the routing targets at 192 and 202, the
 *       message-not-rejection arm at 210, the blank-search tests at 218, 239 and 262, the four
 *       expressions of ten at 57, 293, 300, 347 and 352, the page-number renderings at 327 and 376,
 *       the message narrowing at 526, the map and mapset names at 530-531, and
 *       {@code POPULATE-HEADER-INFO} at 562-581.</li>
 *   <li>{@code app/cpy/COCOM01Y.cpy} - {@code 01 CARDDEMO-COMMAREA.} at 19,
 *       {@code CDEMO-PGM-CONTEXT PIC 9(01)} at 29 with {@code 88 CDEMO-PGM-ENTER VALUE 0} at 30 and
 *       {@code 88 CDEMO-PGM-REENTER VALUE 1} at 31, and {@code CDEMO-LAST-MAP} and
 *       {@code CDEMO-LAST-MAPSET} both {@code PIC X(7)} at 43-44.</li>
 *   <li>{@code app/cpy/CSUSR01Y.cpy} - {@code 01 SEC-USER-DATA.} at 17 and its six items at
 *       18-23.</li>
 *   <li>{@code app/csd/CARDDEMO.CSD} - {@code DEFINE TRANSACTION(CU00) GROUP(CARDDEMO)} at 449 and
 *       {@code PROGRAM(COUSR00C)} at 450.</li>
 * </ul>
 *
 * <h2>Where the declared type diverges from this file's brief (B4)</h2>
 *
 * The declared members are ground truth. Three divergences are asserted as declared and explained
 * where they arise, rather than being edited into the main class:
 *
 * <ol>
 *   <li>The canonical constructor <strong>normalises</strong> an absent value to the field's
 *       declared width in spaces and <strong>rejects</strong> a value wider than the field with
 *       {@link IllegalArgumentException}. An over-width {@code @Size} violation is therefore
 *       unreachable through construction, so the annotation is driven through
 *       {@link Validator#validateValue(Class, String, Object, Class[])} - see
 *       {@link ValidationConstraints}. The sibling {@link SignOnRequest} carries its components
 *       verbatim instead; that split is recorded, not harmonised.</li>
 *   <li>The ten rows are modelled as one {@code @JsonIgnore}d {@code List<UserListRow>} with fifty
 *       {@code @JsonProperty} accessors projecting it onto the wire, rather than as fifty separate
 *       record components. The wire form is what the brief specifies and is asserted in
 *       {@link JsonRoundTrip}; the internal shape is asserted as declared.</li>
 *   <li>{@code cdemoCu00PageNum} is an {@code int}, and the eight-character {@code PAGENUM} image is
 *       a separate {@code String} member rather than a derived accessor, so the link between them is
 *       asserted through the codec in {@link Cu00CommareaExtension} rather than read off the
 *       type.</li>
 * </ol>
 *
 * @see UserListRequest
 * @see SignOnRequestTest
 */
class UserListRequestTest {

    // =================================================================================================
    // THE CODE PAGE. Named once and passed explicitly into every codec construction below (B8).
    //
    // US-ASCII, not IBM037: the nine authoritative fixtures under app/data/ASCII are text, and this
    // suite measures widths, offsets and MOVE outcomes rather than reading a dataset. Naming it is the
    // point - no assertion here can quietly acquire the platform default.
    // =================================================================================================

    private static final Charset MAP_CHARSET = StandardCharsets.US_ASCII;

    // =================================================================================================
    // SCREEN IDENTITY. Transcribed literals, each with the line it came from (B3, B12).
    // =================================================================================================

    /** {@code app/bms/COUSR00.bms:26} - {@code COUSR0A DFHMDI}. */
    private static final String MAP_NAME = "COUSR0A";

    /** {@code app/bms/COUSR00.bms:19} - {@code COUSR00 DFHMSD}. */
    private static final String MAPSET_NAME = "COUSR00";

    /** {@code app/cbl/COUSR00C.cbl:37}, corroborated by {@code app/csd/CARDDEMO.CSD:449}. */
    private static final String TRANSACTION_ID = "CU00";

    /** {@code app/cbl/COUSR00C.cbl:36}, corroborated by {@code app/csd/CARDDEMO.CSD:450}. */
    private static final String PROGRAM_NAME = "COUSR00C";

    /** {@code app/bms/COUSR00.bms:28} - {@code SIZE=(24,80)}. */
    private static final int SCREEN_ROWS = 24;

    /** {@code app/bms/COUSR00.bms:28} - {@code SIZE=(24,80)}. */
    private static final int SCREEN_COLUMNS = 80;

    /** Every {@code DFHMDF} in {@code app/bms/COUSR00.bms}, labelled and unlabelled alike. */
    private static final int DFHMDF_TOTAL = 89;

    /**
     * The name-labelled {@code DFHMDF} definitions - the payload fields. The other
     * {@code 89 - 59 = 30} are literal {@code INITIAL} furniture: the {@code 'Tran:'},
     * {@code 'Date:'}, {@code 'Prog:'} and {@code 'Time:'} captions, the {@code 'List Users'}
     * heading, {@code 'Page:'}, {@code 'Search User ID:'}, the column headings and the function-key
     * legend. Furniture is not a field and is not projected onto the payload.
     */
    private static final int DFHMDF_NAMED = 59;

    /** {@code 89 - 59}: the unlabelled literals, asserted so the split is proved, not assumed. */
    private static final int DFHMDF_LITERALS = 30;

    // =================================================================================================
    // THE FIFTY-NINE SCREEN FIELDS, in the copybook's declaration order. Four parallel transcriptions
    // sharing one index, so each can be checked against its own authority: the Java member names
    // against the record's own projection, the item names and widths against app/cpy-bms/COUSR00.CPY,
    // and the widths again against the LENGTH= operands of app/bms/COUSR00.bms. The two authorities
    // agree field for field, which is why every width below is over-determined.
    //
    // Note the numbering, which is the copybook's and not a tidied version of it: the selection column
    // carries FOUR digits (SEL0001 .. SEL0010) while the other four row items carry TWO (USRID01,
    // FNAME01, LNAME01, UTYPE01). Getting that wrong produces a label no DFHMDF carries.
    // =================================================================================================

    private static final List<String> SCREEN_FIELDS = List.of(
            "TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02",
            "CURTIME", "PAGENUM", "USRIDIN", "SEL0001", "USRID01",
            "FNAME01", "LNAME01", "UTYPE01", "SEL0002", "USRID02",
            "FNAME02", "LNAME02", "UTYPE02", "SEL0003", "USRID03",
            "FNAME03", "LNAME03", "UTYPE03", "SEL0004", "USRID04",
            "FNAME04", "LNAME04", "UTYPE04", "SEL0005", "USRID05",
            "FNAME05", "LNAME05", "UTYPE05", "SEL0006", "USRID06",
            "FNAME06", "LNAME06", "UTYPE06", "SEL0007", "USRID07",
            "FNAME07", "LNAME07", "UTYPE07", "SEL0008", "USRID08",
            "FNAME08", "LNAME08", "UTYPE08", "SEL0009", "USRID09",
            "FNAME09", "LNAME09", "UTYPE09", "SEL0010", "USRID10",
            "FNAME10", "LNAME10", "UTYPE10", "ERRMSG");

    private static final List<String> SYMBOLIC_MAP_ITEMS = List.of(
            "TRNNAMEI", "TITLE01I", "CURDATEI", "PGMNAMEI", "TITLE02I",
            "CURTIMEI", "PAGENUMI", "USRIDINI", "SEL0001I", "USRID01I",
            "FNAME01I", "LNAME01I", "UTYPE01I", "SEL0002I", "USRID02I",
            "FNAME02I", "LNAME02I", "UTYPE02I", "SEL0003I", "USRID03I",
            "FNAME03I", "LNAME03I", "UTYPE03I", "SEL0004I", "USRID04I",
            "FNAME04I", "LNAME04I", "UTYPE04I", "SEL0005I", "USRID05I",
            "FNAME05I", "LNAME05I", "UTYPE05I", "SEL0006I", "USRID06I",
            "FNAME06I", "LNAME06I", "UTYPE06I", "SEL0007I", "USRID07I",
            "FNAME07I", "LNAME07I", "UTYPE07I", "SEL0008I", "USRID08I",
            "FNAME08I", "LNAME08I", "UTYPE08I", "SEL0009I", "USRID09I",
            "FNAME09I", "LNAME09I", "UTYPE09I", "SEL0010I", "USRID10I",
            "FNAME10I", "LNAME10I", "UTYPE10I", "ERRMSGI");

    private static final List<String> MAP_MEMBERS = List.of(
            "trnName", "title01", "curDate", "pgmName", "title02", "curTime",
            "pageNum", "usrIdIn", "sel0001", "usrId01", "fname01", "lname01",
            "utype01", "sel0002", "usrId02", "fname02", "lname02", "utype02",
            "sel0003", "usrId03", "fname03", "lname03", "utype03", "sel0004",
            "usrId04", "fname04", "lname04", "utype04", "sel0005", "usrId05",
            "fname05", "lname05", "utype05", "sel0006", "usrId06", "fname06",
            "lname06", "utype06", "sel0007", "usrId07", "fname07", "lname07",
            "utype07", "sel0008", "usrId08", "fname08", "lname08", "utype08",
            "sel0009", "usrId09", "fname09", "lname09", "utype09", "sel0010",
            "usrId10", "fname10", "lname10", "utype10", "errMsg");

    private static final List<Integer> DECLARED_WIDTHS = List.of(
            4, 40, 8, 8, 40, 8, 8, 8,
            1, 8, 20, 20, 1,
            1, 8, 20, 20, 1,
            1, 8, 20, 20, 1,
            1, 8, 20, 20, 1,
            1, 8, 20, 20, 1,
            1, 8, 20, 20, 1,
            1, 8, 20, 20, 1,
            1, 8, 20, 20, 1,
            1, 8, 20, 20, 1,
            1, 8, 20, 20, 1,
            78);

    private static final List<Integer> COPYBOOK_LINES = List.of(
            24, 30, 36, 42, 48, 54, 60, 66,
            72, 78, 84, 90, 96,
            102, 108, 114, 120, 126,
            132, 138, 144, 150, 156,
            162, 168, 174, 180, 186,
            192, 198, 204, 210, 216,
            222, 228, 234, 240, 246,
            252, 258, 264, 270, 276,
            282, 288, 294, 300, 306,
            312, 318, 324, 330, 336,
            342, 348, 354, 360, 366,
            372);

    /**
     * The same {@value #DFHMDF_NAMED} widths as the type itself publishes them, so a drift between the
     * transcription above and the constants the production code uses fails as a difference rather than
     * hiding behind a shared literal.
     */
    private static final List<Integer> PUBLISHED_WIDTHS = publishedWidths();

    // =================================================================================================
    // THE FOUR ROW-ITEM WIDTHS AND WHERE THEY COME FROM.
    //
    // app/cpy/CSUSR01Y.cpy:18-22 - SEC-USR-ID X(08), SEC-USR-FNAME X(20), SEC-USR-LNAME X(20),
    // SEC-USR-TYPE X(01) - are what POPULATE-USER-DATA moves onto the screen. They are NOT the widths
    // of COUSR00C's own display table; see RowWidthsComeFromCsusr01y for that trap.
    // =================================================================================================

    /** {@code app/cpy/CSUSR01Y.cpy:18} - {@code SEC-USR-ID PIC X(08)}. */
    private static final int SEC_USR_ID_LENGTH = 8;

    /** {@code app/cpy/CSUSR01Y.cpy:19} - {@code SEC-USR-FNAME PIC X(20)}. */
    private static final int SEC_USR_FNAME_LENGTH = 20;

    /** {@code app/cpy/CSUSR01Y.cpy:20} - {@code SEC-USR-LNAME PIC X(20)}. */
    private static final int SEC_USR_LNAME_LENGTH = 20;

    /** {@code app/cpy/CSUSR01Y.cpy:21} - {@code SEC-USR-PWD PIC X(08)}, never projected here. */
    private static final int SEC_USR_PWD_LENGTH = 8;

    /** {@code app/cpy/CSUSR01Y.cpy:22} - {@code SEC-USR-TYPE PIC X(01)}. */
    private static final int SEC_USR_TYPE_LENGTH = 1;

    /** {@code app/cpy/CSUSR01Y.cpy:23} - the named {@code SEC-USR-FILLER X(23)}. */
    private static final int SEC_USR_FILLER_LENGTH = 23;

    /** {@code 8 + 20 + 20 + 8 + 1 + 23}: the whole {@code SEC-USER-DATA} record. */
    private static final int SEC_USER_DATA_LENGTH = 80;

    // =================================================================================================
    // COUSR00C's INTERNAL DISPLAY TABLE - app/cbl/COUSR00C.cbl:56-64.
    //
    //   01 WS-USER-DATA.
    //     02 USER-REC OCCURS 10 TIMES.
    //       05 USER-SEL  X(01)   05 FILLER X(02)   05 USER-ID   X(08)   05 FILLER X(02)
    //       05 USER-NAME X(25)   05 FILLER X(02)   05 USER-TYPE X(08)
    //
    // 48 bytes per row. It is working storage, not a map projection, and its 25 and 8 are NOT the map's
    // 20 and 1. Transcribed so the difference can be asserted rather than accidentally adopted (B4).
    // =================================================================================================

    private static final int WS_USER_SEL_LENGTH = 1;

    private static final int WS_USER_ID_LENGTH = 8;

    private static final int WS_USER_NAME_LENGTH = 25;

    private static final int WS_USER_TYPE_LENGTH = 8;

    private static final int WS_USER_REC_FILLER_LENGTH = 2;

    private static final int WS_USER_REC_FILLER_COUNT = 3;

    private static final int WS_USER_REC_LENGTH = 48;

    // =================================================================================================
    // BYTE GEOMETRY OF 01 COUSR0AI.
    //
    // Per field the input view declares xxxL (COMP PIC S9(4), a two-byte halfword), xxxF (PICTURE X),
    // the 03 xxxA overlay over that same single byte, and FILLER PICTURE X(4) - seven bytes of prefix -
    // before the xxxI item itself. The output view's prefix is FILLER X(3) plus xxxC, xxxP, xxxH and
    // xxxV, also seven bytes, which is exactly what lets 01 COUSR0AO REDEFINES COUSR0AI overlay the
    // input view field for field.
    //
    // SYMBOLIC_MAP_LAYOUT is built from these numbers, and RecordLayout refuses a layout whose spans
    // do not tile its declared length exactly - so the arithmetic below is proved at class
    // initialisation rather than merely asserted.
    // =================================================================================================

    /** {@code app/cpy-bms/COUSR00.CPY:18} - {@code 02 FILLER PIC X(12)}, the {@code TIOAPFX} prefix. */
    private static final int TIOAPFX_PREFIX_LENGTH = 12;

    /** {@code 02 xxxL COMP PIC S9(4)} - a binary halfword. */
    private static final int LENGTH_ITEM_LENGTH = 2;

    /** {@code 02 xxxF PICTURE X}, and the {@code 03 xxxA} overlay over the same byte. */
    private static final int ATTRIBUTE_ITEM_LENGTH = 1;

    /** {@code 02 FILLER PICTURE X(4)}, between the attribute byte and the data item. */
    private static final int ATTRIBUTE_FILLER_LENGTH = 4;

    /** {@code 2 + 1 + 4}: the per-field prefix, identical in the input and the output view. */
    private static final int FIELD_PREFIX_LENGTH =
            LENGTH_ITEM_LENGTH + ATTRIBUTE_ITEM_LENGTH + ATTRIBUTE_FILLER_LENGTH;

    /** {@code 124} header and paging {@code + 500} rows {@code + 78} trailer. */
    private static final int PAYLOAD_WIDTH_TOTAL = 702;

    /** {@code 12 + 59 x 7 + 702}: the whole {@code 01 COUSR0AI} group. */
    private static final int SYMBOLIC_MAP_LENGTH = 1127;

    /** The {@code xxxI} line minus the {@code 02 FILLER REDEFINES xxxF} line, in the copybook. */
    private static final int REDEFINES_LINE_OFFSET = -3;

    /** The {@code xxxI} line minus the {@code 03 xxxA PICTURE X} line, in the copybook. */
    private static final int ATTRIBUTE_VIEW_LINE_OFFSET = -2;

    /** {@code app/cpy-bms/COUSR00.CPY:373} - the one group-level overlay, not this file's subject. */
    private static final int GROUP_REDEFINES_LINE = 373;

    /** {@value #DFHMDF_NAMED} per-field overlays plus the one group-level overlay. */
    private static final int COPYBOOK_REDEFINES_TOTAL = 60;

    /** {@code 02 xxxF PICTURE X} - the flag byte CICS sets. */
    private static final String FLAG_ITEM_SUFFIX = "F";

    /** {@code 03 xxxA PICTURE X} - the attribute view of that same byte. */
    private static final String ATTRIBUTE_ITEM_SUFFIX = "A";

    /** {@code 02 xxxL COMP PIC S9(4)} - the input length CICS reports, and the cursor carrier. */
    private static final String LENGTH_ITEM_SUFFIX = "L";

    /** {@code 02 xxxO} - the output item, which belongs to the response payload. */
    private static final String OUTPUT_ITEM_SUFFIX = "O";

    private static final FixedWidthRecord.RecordLayout SYMBOLIC_MAP_LAYOUT = symbolicMapLayout();

    // =================================================================================================
    // ROW GEOMETRY, EXPRESSED AS AN OCCURS TABLE.
    //
    // The fifty row items are five members repeated ten times, and app/cbl/COUSR00C.cbl walks them with
    // a one-based subscript. Modelling the fifty screen fields as one 500-byte OCCURS 10 table is what
    // lets FixedWidthRecord.occursElementOffsetOneBased carry the 1-to-0 conversion - the single most
    // common defect in a migration of this kind - instead of a subtraction written inline.
    // =================================================================================================

    /** {@code 1 + 8 + 20 + 20 + 1}: one row of the map's five repeating items. */
    private static final int MAP_ROW_PAYLOAD_LENGTH = 50;

    /** {@code app/cbl/COUSR00C.cbl:57} - {@code 02 USER-REC OCCURS 10 TIMES}. */
    private static final int OCCURS_COUNT = 10;

    /** The first COBOL subscript. There is no subscript zero. */
    private static final int FIRST_SUBSCRIPT = 1;

    /** The last COBOL subscript, and the bound every loop in the program is written against. */
    private static final int LAST_SUBSCRIPT = 10;

    /** {@code app/cbl/COUSR00C.cbl:300} - {@code PERFORM UNTIL WS-IDX >= 11}. */
    private static final int EXCLUSIVE_FORWARD_BOUND = 11;

    /** {@code app/cbl/COUSR00C.cbl:354} - {@code PERFORM UNTIL WS-IDX <= 0}. */
    private static final int EXCLUSIVE_BACKWARD_BOUND = 0;

    /**
     * The five independent expressions of ten in {@code app/cbl/COUSR00C.cbl}: the {@code OCCURS}
     * clause at 57, the two forward and backward initialising loops at 293 and 347, the forward
     * page loop's exclusive bound at 300, and the backward page's seed at 352. Page size is
     * behaviour restated five times in the source, not a setting.
     */
    private static final List<Integer> PAGE_SIZE_EVIDENCE_LINES = List.of(57, 293, 300, 347, 352);

    // =================================================================================================
    // THE 34-BYTE CDEMO-CU00-INFO EXTENSION - app/cbl/COUSR00C.cbl:67-75.
    // =================================================================================================

    private static final int CU00_USRID_FIRST_LENGTH = 8;

    private static final int CU00_USRID_LAST_LENGTH = 8;

    private static final int CU00_PAGE_NUM_DIGITS = 8;

    private static final int CU00_NEXT_PAGE_FLG_LENGTH = 1;

    private static final int CU00_USR_SEL_FLG_LENGTH = 1;

    private static final int CU00_USR_SELECTED_LENGTH = 8;

    /** {@code 8 + 8 + 8 + 1 + 1 + 8}. */
    private static final int CU00_INFO_LENGTH = 34;

    /** {@code app/cpy/COCOM01Y.cpy:19-44} - the shared communication area, unchanged for CU00. */
    private static final int COMMAREA_LENGTH = 160;

    /** {@code 160 + 34}: what {@code EIBCALEN} reports for this transaction. */
    private static final int CU00_COMMAREA_LENGTH = 194;

    /** {@code app/cbl/COUSR00C.cbl:71} - the {@code VALUE 'N'} clause on the next-page flag. */
    private static final String NEXT_PAGE_FLG_DEFAULT = "N";

    /** {@code app/cbl/COUSR00C.cbl:72} - {@code 88 NEXT-PAGE-YES VALUE 'Y'}. */
    private static final String NEXT_PAGE_YES_VALUE = "Y";

    /** {@code app/cbl/COUSR00C.cbl:73} - {@code 88 NEXT-PAGE-NO VALUE 'N'}. */
    private static final String NEXT_PAGE_NO_VALUE = "N";

    /** {@code app/cbl/COUSR00C.cbl:190-192} - the update arm of the selection {@code EVALUATE}. */
    private static final String USR_SEL_UPDATE_VALUE = "U";

    /** {@code app/cbl/COUSR00C.cbl:200-202} - the delete arm. */
    private static final String USR_SEL_DELETE_VALUE = "D";

    /** The largest value {@code PIC 9(08)} can hold. */
    private static final int PAGE_NUM_MAX = 99_999_999;

    // =================================================================================================
    // THE COMMUNICATION AREA'S OWN CONTEXT FLAG - app/cpy/COCOM01Y.cpy:29-31.
    // =================================================================================================

    /** {@code 88 CDEMO-PGM-ENTER VALUE 0} - paint the screen. */
    private static final int PGM_CONTEXT_ENTER = 0;

    /** {@code 88 CDEMO-PGM-REENTER VALUE 1} - validate what was typed. */
    private static final int PGM_CONTEXT_REENTER = 1;

    /** {@code app/cbl/COUSR00C.cbl:125-126} - {@code WHEN DFHPF3} routes to the administrator menu. */
    private static final String PF3_TARGET_PROGRAM = "COADM01C";

    /** {@code app/cbl/COUSR00C.cbl:38} - {@code 05 WS-MESSAGE PIC X(80) VALUE SPACES}. */
    private static final int WS_MESSAGE_LENGTH = 80;

    /** {@code app/cpy-bms/COUSR00.CPY:372} - {@code 02 ERRMSGI PIC X(78)}. */
    private static final int ERRMSG_LENGTH = 78;

    /** {@code 80 - 78}: what a {@code MOVE WS-MESSAGE TO ERRMSG} discards on the right. */
    private static final int ERRMSG_TRUNCATED_CHARACTERS = 2;

    // =================================================================================================
    // DETERMINISTIC TIME (B7). The instant is the version footer's own timestamp, so the expected
    // renderings below are readable rather than arbitrary.
    // =================================================================================================

    private static final Instant FIXED_INSTANT = Instant.parse("2022-07-19T23:15:57Z");

    /** {@code app/cbl/COUSR00C.cbl:575} - {@code MOVE WS-CURDATE-MM-DD-YY TO CURDATEO}. */
    private static final String FIXED_CURDATE = "07/19/22";

    /** {@code app/cbl/COUSR00C.cbl:581} - {@code MOVE WS-CURTIME-HH-MM-SS TO CURTIMEO}. */
    private static final String FIXED_CURTIME = "23:15:57";

    /**
     * {@code app/cpy-bms/COSGN00.CPY:54} declares {@code CURTIMEI PIC X(9)} because that map's
     * {@code INITIAL} literal is {@code 'Ahh:mm:ss'}. This map's is eight. The two are the package's
     * most conflatable pair of widths, so the sign-on width is transcribed here purely to be asserted
     * different.
     */
    private static final int COSGN00_CURTIME_LENGTH = 9;

    /** The names that are not screen fields: the conversation, carried in the payload. */
    private static final List<String> STATE_MEMBERS = List.of("navigationContext", "aid");

    /**
     * The wire carries {@code 8} header and paging names, the {@code 50} numbered row names, the
     * {@code ERRMSG} name, the {@code 6} {@code CDEMO-CU00-*} members and the {@code 2} state
     * members: {@code 8 + 50 + 1 + 6 + 2 = 67}. The {@code List<UserListRow>} that holds the rows
     * internally is suppressed, so no generic member name ever reaches a client.
     */
    private static final int WIRE_MEMBER_COUNT = 67;

    /** The record's own component count: {@code 8 + rows + errMsg + 6 + 2}. */
    private static final int COMPONENT_COUNT = 18;

    private static final Set<String> EXPECTED_JSON_MEMBERS = expectedJsonMembers();

    // =================================================================================================
    // Construction of the constants above. Static, side-effect free, and called exactly once each.
    // =================================================================================================

    /**
     * Reads the {@value #DFHMDF_NAMED} widths back off the type, in the copybook's order.
     *
     * <p>Read from the class rather than restated, so that comparing this against
     * {@link #DECLARED_WIDTHS} compares two independent transcriptions instead of one literal with
     * itself.
     */
    private static List<Integer> publishedWidths() {
        List<Integer> widths = new ArrayList<>(DFHMDF_NAMED);
        widths.add(UserListRequest.TRNNAME_LENGTH);
        widths.add(UserListRequest.TITLE01_LENGTH);
        widths.add(UserListRequest.CURDATE_LENGTH);
        widths.add(UserListRequest.PGMNAME_LENGTH);
        widths.add(UserListRequest.TITLE02_LENGTH);
        widths.add(UserListRequest.CURTIME_LENGTH);
        widths.add(UserListRequest.PAGENUM_LENGTH);
        widths.add(UserListRequest.USRIDIN_LENGTH);
        for (int rowNumber = FIRST_SUBSCRIPT; rowNumber <= LAST_SUBSCRIPT; rowNumber++) {
            widths.add(UserListRequest.SEL_LENGTH);
            widths.add(UserListRequest.USRID_LENGTH);
            widths.add(UserListRequest.FNAME_LENGTH);
            widths.add(UserListRequest.LNAME_LENGTH);
            widths.add(UserListRequest.UTYPE_LENGTH);
        }
        widths.add(UserListRequest.ERRMSG_LENGTH);
        return List.copyOf(widths);
    }

    /**
     * Builds the byte geometry of {@code 01 COUSR0AI} span by span: the {@code TIOAPFX} prefix, then
     * for each of the {@value #DFHMDF_NAMED} fields its length halfword, its attribute byte, the
     * {@code 03 xxxA} overlay over that same byte, the four filler bytes and the data item.
     *
     * <p>The DECLARED length is passed to {@link FixedWidthRecord.RecordLayout#of}, never the cursor
     * this loop happened to reach. Passing the cursor would make the layout self-consistent with
     * whatever the constants above sum to and would catch nothing; passing
     * {@value #SYMBOLIC_MAP_LENGTH} makes the layout's own self-check compare the transcribed
     * geometry against the transcribed total, so one wrong constant fails class initialisation
     * instead of quietly shifting every offset after it.
     */
    private static FixedWidthRecord.RecordLayout symbolicMapLayout() {
        List<FixedWidthRecord.FieldSpan> spans = new ArrayList<>();
        // 02 FILLER PIC X(12) - app/cpy-bms/COUSR00.CPY:18.
        spans.add(FixedWidthRecord.FieldSpan.filler(0, TIOAPFX_PREFIX_LENGTH));
        int cursor = TIOAPFX_PREFIX_LENGTH;
        for (int index = 0; index < DFHMDF_NAMED; index++) {
            String field = SCREEN_FIELDS.get(index);
            // 02 xxxL COMP PIC S9(4) - a binary halfword, modelled as reserved storage.
            spans.add(FixedWidthRecord.FieldSpan.filler(cursor, LENGTH_ITEM_LENGTH));
            cursor += LENGTH_ITEM_LENGTH;
            // 02 xxxF PICTURE X, and 02 FILLER REDEFINES xxxF / 03 xxxA PICTURE X over the same byte.
            FixedWidthRecord.FieldSpan flag = FixedWidthRecord.FieldSpan.alphanumeric(
                    field + FLAG_ITEM_SUFFIX, cursor, ATTRIBUTE_ITEM_LENGTH);
            spans.add(flag);
            spans.add(flag.redefinedAs(field + ATTRIBUTE_ITEM_SUFFIX,
                    FixedWidthRecord.PictureKind.ALPHANUMERIC));
            cursor += ATTRIBUTE_ITEM_LENGTH;
            // 02 FILLER PICTURE X(4).
            spans.add(FixedWidthRecord.FieldSpan.filler(cursor, ATTRIBUTE_FILLER_LENGTH));
            cursor += ATTRIBUTE_FILLER_LENGTH;
            // 02 xxxI PIC X(n).
            spans.add(FixedWidthRecord.FieldSpan.alphanumeric(
                    SYMBOLIC_MAP_ITEMS.get(index), cursor, DECLARED_WIDTHS.get(index)));
            cursor += DECLARED_WIDTHS.get(index);
        }
        return FixedWidthRecord.RecordLayout.of(SYMBOLIC_MAP_LENGTH,
                spans.toArray(FixedWidthRecord.FieldSpan[]::new));
    }

    /** The {@value #WIRE_MEMBER_COUNT} names a serialised request must carry, and no others. */
    private static Set<String> expectedJsonMembers() {
        Set<String> members = new LinkedHashSet<>(MAP_MEMBERS);
        members.add("cdemoCu00UsrIdFirst");
        members.add("cdemoCu00UsrIdLast");
        members.add("cdemoCu00PageNum");
        members.add("cdemoCu00NextPageFlg");
        members.add("cdemoCu00UsrSelFlg");
        members.add("cdemoCu00UsrSelected");
        members.addAll(STATE_MEMBERS);
        return Set.copyOf(members);
    }

    // =================================================================================================
    // Shared, stateless helpers. Every one returns a fresh value; none caches, memoises or mutates
    // (B9). A codec is cheap and immutable, so a new one per call is simpler than a shared field and
    // removes any question of cross-case interference.
    // =================================================================================================

    private static FixedWidthCodec codec() {
        return new FixedWidthCodec(MAP_CHARSET);
    }

    /**
     * An {@link ObjectMapper} configured exactly as {@code config.WebConfig}'s Jackson customiser
     * configures the application's.
     *
     * <p>Built here rather than injected, because loading the Spring context to obtain a mapper would
     * turn a payload unit test into an integration test. The configuration matters for a reason
     * specific to this migration: a mapper left at its defaults trims nothing but <em>does</em> coerce
     * an empty string to {@code null}, and a space-padded {@code PIC X(n)} value round-trips only if
     * that coercion is off. {@code USE_BIG_DECIMAL_FOR_FLOATS} and {@code WRITE_BIGDECIMAL_AS_PLAIN}
     * are irrelevant to this all-character payload and are set anyway, so that what is asserted here
     * is the mapper the application actually uses.
     */
    private static ObjectMapper webConfigEquivalentMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        mapper.configure(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN, true);
        mapper.disable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT);
        return mapper;
    }

    /** The blank first-entry state: {@link UserListRequest#empty()} with nothing typed. */
    private static UserListRequest blankRequest() {
        return UserListRequest.empty();
    }

    /**
     * A fully populated request: the header as {@code POPULATE-HEADER-INFO} paints it, all ten rows
     * filled to their declared widths, the paging context on its second page with a further page
     * available, and a selection typed on the first row.
     */
    private static UserListRequest populatedRequest() {
        FixedWidthCodec codec = codec();
        DateHeader header = DateHeader.from(codec, Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
        UserListRequest request = UserListRequest.empty()
                .withTrnName(TRANSACTION_ID)
                .withTitle01(ScreenTitles.CCDA_TITLE01)
                .withCurDate(header.wsCurdateMmDdYy())
                .withPgmName(PROGRAM_NAME)
                .withTitle02(ScreenTitles.CCDA_TITLE02)
                .withCurTime(header.wsCurtimeHhMmSs())
                .withPageNum(codec.movePic9(2L, UserListRequest.PAGENUM_LENGTH))
                .withUsrIdIn(codec.movePicX("USER0001", UserListRequest.USRIDIN_LENGTH))
                .withErrMsg(codec.movePicX(SystemMessages.CCDA_MSG_INVALID_KEY,
                        UserListRequest.ERRMSG_LENGTH))
                .withCdemoCu00UsrIdFirst(codec.movePicX("USER0001", CU00_USRID_FIRST_LENGTH))
                .withCdemoCu00UsrIdLast(codec.movePicX("USER0010", CU00_USRID_LAST_LENGTH))
                .withCdemoCu00PageNum(2)
                .withNextPageYes()
                .withCdemoCu00UsrSelFlg(USR_SEL_UPDATE_VALUE)
                .withCdemoCu00UsrSelected(codec.movePicX("USER0001", CU00_USR_SELECTED_LENGTH))
                .withNavigationContext(NavigationContext.empty()
                        .withFromTranid(TRANSACTION_ID)
                        .withFromProgram(PROGRAM_NAME)
                        .withUserId("ADMIN001")
                        .withUserTypeAdmin()
                        .withLastMap(MAP_NAME)
                        .withLastMapset(MAPSET_NAME)
                        .withPgmReenter())
                .withAid(PfKeyResolver.AidKey.PFK08.token());
        for (int rowNumber = FIRST_SUBSCRIPT; rowNumber <= LAST_SUBSCRIPT; rowNumber++) {
            request = request.withRow(rowNumber, rowFor(codec, rowNumber));
        }
        return request.withRow(FIRST_SUBSCRIPT,
                request.row(FIRST_SUBSCRIPT).withSel(USR_SEL_UPDATE_VALUE));
    }

    /**
     * One row, every field padded to the declared width by the codec so the wire form is exactly what
     * a {@code SEND MAP} would put on the screen.
     */
    private static UserListRow rowFor(FixedWidthCodec codec, int rowNumber) {
        String suffix = codec.movePic9((long) rowNumber, UserListRequest.ROW_FIELD_DIGITS);
        return new UserListRow(
                codec.movePicX("", UserListRequest.SEL_LENGTH),
                codec.movePicX("USER00" + suffix, UserListRequest.USRID_LENGTH),
                codec.movePicX("First" + suffix, UserListRequest.FNAME_LENGTH),
                codec.movePicX("Last" + suffix, UserListRequest.LNAME_LENGTH),
                rowNumber == FIRST_SUBSCRIPT ? NavigationContext.USER_TYPE_ADMIN
                        : NavigationContext.USER_TYPE_USER);
    }

    /** The {@value #DFHMDF_NAMED} field values of a request, in the copybook's order. */
    private static List<String> mapValuesOf(UserListRequest request) {
        return List.copyOf(request.mapFields().values());
    }

    private static Set<ConstraintViolation<UserListRequest>> validate(UserListRequest request) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            return factory.getValidator().validate(request);
        }
    }

    /**
     * Validates one value against the constraints declared for one member, without an instance.
     *
     * <p>{@link Validator#validateValue(Class, String, Object, Class[])} is used deliberately. This
     * type's canonical constructor rejects an over-width value outright, so there is no way to build
     * an instance that violates a {@code @Size} maximum - which is a stronger guarantee, not a gap,
     * but it does mean the annotation itself has to be exercised without one.
     */
    private static Set<ConstraintViolation<UserListRequest>> validateValue(String member,
            Object value) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            return factory.getValidator().validateValue(UserListRequest.class, member, value);
        }
    }

    /**
     * The same, for one component of the nested row record.
     *
     * <p>The fifty numbered row members are accessors over the row list rather than components of the
     * enclosing record, so their width constraint is declared on {@link UserListRow} and has to be
     * validated against that type. Handing a character value to the enclosing record's {@code rows}
     * property instead would be a type error, not a width violation.
     */
    private static Set<ConstraintViolation<UserListRow>> validateRowValue(String component,
            Object value) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            return factory.getValidator().validateValue(UserListRow.class, component, value);
        }
    }

    /** Whether one of the {@value #DFHMDF_NAMED} members is a component of the enclosing record. */
    private static boolean isRecordComponent(String member) {
        return Arrays.stream(UserListRequest.class.getRecordComponents())
                .map(RecordComponent::getName)
                .anyMatch(member::equals);
    }

    private static String serialise(UserListRequest request) {
        try {
            return webConfigEquivalentMapper().writeValueAsString(request);
        } catch (IOException failure) {
            throw new UncheckedIOException("Serialising a UserListRequest must not fail", failure);
        }
    }

    private static UserListRequest deserialise(String json) {
        try {
            return webConfigEquivalentMapper().readValue(json, UserListRequest.class);
        } catch (IOException failure) {
            throw new UncheckedIOException("Deserialising a UserListRequest must not fail", failure);
        }
    }

    private static Map<String, Object> jsonTreeOf(UserListRequest request) {
        try {
            return webConfigEquivalentMapper().readValue(serialise(request),
                    new TypeReference<Map<String, Object>>() { });
        } catch (IOException failure) {
            throw new UncheckedIOException("Reading a serialised UserListRequest must not fail",
                    failure);
        }
    }

    /** The {@value #DFHMDF_NAMED} screen fields, one per parameterised case. */
    private static List<String> screenFields() {
        return SCREEN_FIELDS;
    }

    /** The {@value #DFHMDF_NAMED} Java member names paired with their declared widths. */
    private static List<Arguments> membersAndWidths() {
        List<Arguments> cases = new ArrayList<>(DFHMDF_NAMED);
        for (int index = 0; index < DFHMDF_NAMED; index++) {
            cases.add(Arguments.of(MAP_MEMBERS.get(index), DECLARED_WIDTHS.get(index)));
        }
        return List.copyOf(cases);
    }


    // =================================================================================================
    // 1. THE PROJECTION OF 01 COUSR0AI (gate G9).
    //
    // Fifty-nine screen fields, in the copybook's own order, each traceable to one name-labelled
    // DFHMDF definition and to one xxxI item whose PICTURE gives its width.
    // =================================================================================================

    @Nested
    @DisplayName("Projection of 01 COUSR0AI - fifty-nine screen fields, in copybook order")
    class MapProjection {

        @Test
        @DisplayName("the four transcriptions are the same length, and that length is fifty-nine")
        void theTranscriptionsAgreeOnTheFieldCount() {
            assertThat(SCREEN_FIELDS).hasSize(DFHMDF_NAMED);
            assertThat(SYMBOLIC_MAP_ITEMS).hasSize(DFHMDF_NAMED);
            assertThat(MAP_MEMBERS).hasSize(DFHMDF_NAMED);
            assertThat(DECLARED_WIDTHS).hasSize(DFHMDF_NAMED);
            assertThat(COPYBOOK_LINES).hasSize(DFHMDF_NAMED);
            assertThat(UserListRequest.MAP_FIELD_COUNT)
                    .as("8 header and paging + 10 rows x 5 + 1 trailer")
                    .isEqualTo(UserListRequest.HEADER_FIELD_COUNT
                            + UserListRequest.ROW_COUNT * UserListRequest.ROW_FIELD_COUNT
                            + UserListRequest.TRAILER_FIELD_COUNT)
                    .isEqualTo(DFHMDF_NAMED);
        }

        @Test
        @DisplayName("eighty-nine DFHMDF definitions, of which fifty-nine are fields and thirty are not")
        void theMapsetSplitsIntoFieldsAndFurniture() {
            // app/bms/COUSR00.bms carries 89 DFHMDF definitions. Only a name-labelled one is a field:
            // the other 30 are INITIAL literals - the captions, the 'List Users' heading, 'Page:',
            // 'Search User ID:', the column headings and the function-key legend. Counting all 89 as
            // payload fields would invent 30 members no client can ever send.
            assertThat(DFHMDF_NAMED + DFHMDF_LITERALS).isEqualTo(DFHMDF_TOTAL);
            assertThat(UserListRequest.MAP_FIELD_COUNT).isEqualTo(DFHMDF_NAMED);
            assertThat(SCREEN_ROWS * SCREEN_COLUMNS)
                    .as("SIZE=(24,80) at app/bms/COUSR00.bms:28 - one 1920-character screen")
                    .isEqualTo(1920);
        }

        @Test
        @DisplayName("eighteen record components: the header, the row table, the trailer, CU00, the state")
        void componentCensus() {
            RecordComponent[] components = UserListRequest.class.getRecordComponents();
            assertThat(components)
                    .as("8 header and paging + rows + errMsg + 6 CDEMO-CU00-* + navigationContext "
                            + "and aid")
                    .hasSize(COMPONENT_COUNT);

            assertThat(Arrays.stream(components).map(RecordComponent::getName).toList())
                    .containsExactly("trnName", "title01", "curDate", "pgmName", "title02",
                            "curTime", "pageNum", "usrIdIn", "rows", "errMsg",
                            "cdemoCu00UsrIdFirst", "cdemoCu00UsrIdLast", "cdemoCu00PageNum",
                            "cdemoCu00NextPageFlg", "cdemoCu00UsrSelFlg", "cdemoCu00UsrSelected",
                            "navigationContext", "aid");
        }

        @Test
        @DisplayName("mapFieldNames() lists the fifty-nine screen fields in the copybook's order")
        void theFieldNamesAreTheCopybooksInTheCopybooksOrder() {
            assertThat(UserListRequest.mapFieldNames())
                    .as("every payload field traces to one name-labelled DFHMDF, in declaration order")
                    .containsExactlyElementsOf(SCREEN_FIELDS);
        }

        @Test
        @DisplayName("mapFields() projects one value per screen field, in that same order")
        void theFieldViewIsOrderedAndComplete() {
            Map<String, String> fields = populatedRequest().mapFields();
            assertThat(fields).hasSize(DFHMDF_NAMED);
            assertThat(fields.keySet()).containsExactlyElementsOf(SCREEN_FIELDS);
            assertThat(mapValuesOf(populatedRequest())).hasSize(DFHMDF_NAMED);
        }

        @ParameterizedTest(name = "{0} is {1} character(s) wide")
        @MethodSource(
                "com.vsergeychik.carddemo.user.dto.UserListRequestTest#membersAndWidths")
        @DisplayName("each member is exactly as wide as its xxxI PICTURE clause")
        void eachMemberIsAsWideAsItsPictureClause(String member, int declaredWidth) {
            int index = MAP_MEMBERS.indexOf(member);
            assertThat(index).as("%s must be one of the fifty-nine map members", member)
                    .isNotNegative();

            assertThat(PUBLISHED_WIDTHS.get(index))
                    .as("%s is %s PIC X(%d) at app/cpy-bms/COUSR00.CPY:%d", member,
                            SYMBOLIC_MAP_ITEMS.get(index), declaredWidth, COPYBOOK_LINES.get(index))
                    .isEqualTo(declaredWidth);

            // And the width the type publishes is the width the geometry was built from, so the two
            // transcriptions and the layout all agree.
            assertThat(SYMBOLIC_MAP_LAYOUT.span(SYMBOLIC_MAP_ITEMS.get(index)).length())
                    .isEqualTo(declaredWidth);
        }

        @Test
        @DisplayName("the sum of the declared widths is 124 header + 500 rows + 78 trailer")
        void theWidthsSumAsTheCopybookLaysThemOut() {
            int header = DECLARED_WIDTHS.subList(0, UserListRequest.HEADER_FIELD_COUNT).stream()
                    .mapToInt(Integer::intValue).sum();
            int rows = DECLARED_WIDTHS.subList(UserListRequest.HEADER_FIELD_COUNT,
                            DFHMDF_NAMED - UserListRequest.TRAILER_FIELD_COUNT).stream()
                    .mapToInt(Integer::intValue).sum();
            int trailer = DECLARED_WIDTHS.get(DFHMDF_NAMED - 1);

            assertThat(header).as("4 + 40 + 8 + 8 + 40 + 8 + 8 + 8").isEqualTo(124);
            assertThat(rows).as("ten rows of 1 + 8 + 20 + 20 + 1")
                    .isEqualTo(UserListRequest.ROW_COUNT * MAP_ROW_PAYLOAD_LENGTH)
                    .isEqualTo(500);
            assertThat(trailer).isEqualTo(ERRMSG_LENGTH);
            assertThat(header + rows + trailer).isEqualTo(PAYLOAD_WIDTH_TOTAL);
        }

        @Test
        @DisplayName("the copybook item lines advance by a constant six-line stride")
        void theCopybookStrideIsConstant() {
            // Each field occupies six copybook lines: xxxL, xxxF, the FILLER REDEFINES, the 03 xxxA,
            // the FILLER X(4) and the xxxI item. The stride being constant is what makes the
            // transcribed line numbers checkable rather than merely plausible.
            for (int index = 1; index < DFHMDF_NAMED; index++) {
                assertThat(COPYBOOK_LINES.get(index) - COPYBOOK_LINES.get(index - 1))
                        .as("%s follows %s six lines later", SYMBOLIC_MAP_ITEMS.get(index),
                                SYMBOLIC_MAP_ITEMS.get(index - 1))
                        .isEqualTo(6);
            }
            assertThat(COPYBOOK_LINES.get(0)).as("TRNNAMEI is the first item").isEqualTo(24);
            assertThat(COPYBOOK_LINES.get(DFHMDF_NAMED - 1))
                    .as("ERRMSGI is the last item of 01 COUSR0AI, on the line immediately before "
                            + "01 COUSR0AO REDEFINES COUSR0AI")
                    .isEqualTo(GROUP_REDEFINES_LINE - 1)
                    .isEqualTo(372);
        }

        @Test
        @DisplayName("the identity is CU00 / COUSR00C / COUSR0A / COUSR00, from three sources")
        void theScreenIdentityIsTheSourcesOwn() {
            assertThat(UserListRequest.TRANID)
                    .as("WS-TRANID at app/cbl/COUSR00C.cbl:37, and DEFINE TRANSACTION(CU00) at "
                            + "app/csd/CARDDEMO.CSD:449")
                    .isEqualTo(TRANSACTION_ID)
                    .hasSize(UserListRequest.TRNNAME_LENGTH);
            assertThat(UserListRequest.PROGRAM)
                    .as("WS-PGMNAME at app/cbl/COUSR00C.cbl:36, and PROGRAM(COUSR00C) at "
                            + "app/csd/CARDDEMO.CSD:450")
                    .isEqualTo(PROGRAM_NAME)
                    .hasSize(UserListRequest.PGMNAME_LENGTH);
            assertThat(UserListRequest.MAP)
                    .as("MAP('COUSR0A') at app/cbl/COUSR00C.cbl:530")
                    .isEqualTo(MAP_NAME);
            assertThat(UserListRequest.MAPSET)
                    .as("MAPSET('COUSR00') at app/cbl/COUSR00C.cbl:531")
                    .isEqualTo(MAPSET_NAME);

            // Both names fit the fields they are moved into at lines 568 and 569, which is why the
            // widths are asserted alongside the literals rather than separately.
            assertThat(NavigationContext.LAST_MAP_LENGTH)
                    .as("CDEMO-LAST-MAP is PIC X(7), so COUSR0A fits exactly and COUSR00C would not")
                    .isEqualTo(MAP_NAME.length())
                    .isEqualTo(7);
            assertThat(NavigationContext.LAST_MAPSET_LENGTH).isEqualTo(MAPSET_NAME.length());
        }

        @Test
        @DisplayName("the search field is USRIDIN, not USERID - COUSR01 is the odd one out")
        void theSearchFieldIsNamedUsridin() {
            // COUSR00, COUSR02 and COUSR03 all label this field USRIDIN; only COUSR01 labels its own
            // USERID. Reading across the package and assuming one name is how a field-for-field diff
            // starts comparing the wrong two things.
            assertThat(UserListRequest.USRIDIN_FIELD).isEqualTo("USRIDIN");
            assertThat(SCREEN_FIELDS).contains("USRIDIN").doesNotContain("USERID");
            assertThat(SYMBOLIC_MAP_ITEMS).contains("USRIDINI").doesNotContain("USERIDI");
            assertThat(MAP_MEMBERS).contains("usrIdIn").doesNotContain("userId");
            assertThat(UserListRequest.USRIDIN_LENGTH)
                    .as("USRIDINI PIC X(8) at app/cpy-bms/COUSR00.CPY:66, matching SEC-USR-ID X(08)")
                    .isEqualTo(SEC_USR_ID_LENGTH);
        }

        @ParameterizedTest(name = "row {0}: SEL has four digits, the other four have two")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
        @DisplayName("the selection column is numbered SEL0001..SEL0010, the rest 01..10")
        void theRowLabelsCarryTheCopybooksNumbering(int rowNumber) {
            String twoDigits = codec().movePic9((long) rowNumber, UserListRequest.ROW_FIELD_DIGITS);
            String fourDigits = codec().movePic9((long) rowNumber, UserListRequest.SEL_FIELD_DIGITS);

            assertThat(UserListRequest.selFieldName(rowNumber))
                    .as("the copybook spells it SEL000n, with four digits")
                    .isEqualTo(UserListRequest.SEL_FIELD_STEM + fourDigits)
                    .hasSize(UserListRequest.SEL_FIELD_STEM.length()
                            + UserListRequest.SEL_FIELD_DIGITS);
            assertThat(UserListRequest.usrIdFieldName(rowNumber))
                    .isEqualTo(UserListRequest.USRID_FIELD_STEM + twoDigits);
            assertThat(UserListRequest.fnameFieldName(rowNumber))
                    .isEqualTo(UserListRequest.FNAME_FIELD_STEM + twoDigits);
            assertThat(UserListRequest.lnameFieldName(rowNumber))
                    .isEqualTo(UserListRequest.LNAME_FIELD_STEM + twoDigits);
            assertThat(UserListRequest.utypeFieldName(rowNumber))
                    .isEqualTo(UserListRequest.UTYPE_FIELD_STEM + twoDigits);

            // And each of the five is a field the copybook actually declares.
            assertThat(SCREEN_FIELDS).contains(UserListRequest.selFieldName(rowNumber),
                    UserListRequest.usrIdFieldName(rowNumber),
                    UserListRequest.fnameFieldName(rowNumber),
                    UserListRequest.lnameFieldName(rowNumber),
                    UserListRequest.utypeFieldName(rowNumber));
        }

        @Test
        @DisplayName("the four-digit and two-digit label widths are two different constants")
        void theTwoLabelWidthsAreDistinct() {
            assertThat(UserListRequest.SEL_FIELD_DIGITS).isEqualTo(4);
            assertThat(UserListRequest.ROW_FIELD_DIGITS).isEqualTo(2);
            assertThat(UserListRequest.SEL_FIELD_DIGITS)
                    .as("collapsing these two into one constant renames thirty of the fifty row "
                            + "fields and is a silent G9 violation")
                    .isNotEqualTo(UserListRequest.ROW_FIELD_DIGITS);
        }
    }

    // =================================================================================================
    // 2. THE WIDTH TRAPS.
    //
    // Three widths on this screen are easy to get wrong by reading a sibling instead of this map, and
    // one MOVE narrows a value on its way to the screen. Each has its own case.
    // =================================================================================================

    @Nested
    @DisplayName("Width traps - the four places a plausible reading is the wrong reading")
    class WidthTraps {

        @Test
        @DisplayName("CURTIME is X(8) here and X(9) on COSGN00")
        void curTimeIsEightNotNine() {
            // app/cpy-bms/COUSR00.CPY:54 declares CURTIMEI PIC X(8); app/cpy-bms/COSGN00.CPY:54
            // declares X(9), because that map's INITIAL literal is 'Ahh:mm:ss' and this one's is not.
            // The value moved in is WS-CURTIME-HH-MM-SS at app/cbl/COUSR00C.cbl:581 - eight characters,
            // hh:mm:ss - so eight is the faithful width.
            assertThat(UserListRequest.CURTIME_LENGTH).isEqualTo(8);
            assertThat(UserListRequest.CURTIME_LENGTH).isNotEqualTo(COSGN00_CURTIME_LENGTH);
            assertThat(DECLARED_WIDTHS.get(SCREEN_FIELDS.indexOf("CURTIME"))).isEqualTo(8);

            DateHeader header = DateHeader.from(codec(), Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
            assertThat(header.wsCurtimeHhMmSs())
                    .as("hh:mm:ss fills the field exactly, with no room for a leading attribute byte")
                    .isEqualTo(FIXED_CURTIME)
                    .hasSize(UserListRequest.CURTIME_LENGTH);
            assertThat(blankRequest().withCurTime(header.wsCurtimeHhMmSs()).curTime())
                    .isEqualTo(FIXED_CURTIME);
        }

        @Test
        @DisplayName("CURDATE is X(8) and carries mm/dd/yy")
        void curDateIsEightAndCarriesTheTwoDigitYear() {
            // app/cbl/COUSR00C.cbl:571-575 builds WS-CURDATE-MM-DD-YY from the month, the day and
            // WS-CURDATE-YEAR(3:2) - the last two digits of the year - and moves the result to
            // CURDATEO. A four-digit year would need ten characters and does not fit.
            DateHeader header = DateHeader.from(codec(), Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
            assertThat(header.wsCurdateMmDdYy())
                    .isEqualTo(FIXED_CURDATE)
                    .hasSize(UserListRequest.CURDATE_LENGTH);
            assertThat(UserListRequest.CURDATE_LENGTH).isEqualTo(8);
            assertThat(blankRequest().withCurDate(header.wsCurdateMmDdYy()).curDate())
                    .isEqualTo(FIXED_CURDATE);
        }

        @Test
        @DisplayName("ERRMSG is X(78) while WS-MESSAGE is X(80), so the MOVE discards two characters")
        void theMessageMoveTruncatesOnTheRight() {
            // app/cbl/COUSR00C.cbl:38 declares 05 WS-MESSAGE PIC X(80); line 526 moves it to ERRMSGO,
            // which app/cpy-bms/COUSR00.CPY:372 declares PIC X(78). A COBOL alphanumeric MOVE fills the
            // receiver from the left and discards the overflow, so the two right-hand characters are
            // lost. That is the dominant parity risk in this migration, and it is driven here through
            // the codec - never a bare assignment and never substring.
            assertThat(WS_MESSAGE_LENGTH - UserListRequest.ERRMSG_LENGTH)
                    .isEqualTo(ERRMSG_TRUNCATED_CHARACTERS);

            String wsMessage = "X".repeat(UserListRequest.ERRMSG_LENGTH) + "YZ";
            assertThat(wsMessage).hasSize(WS_MESSAGE_LENGTH);

            String moved = codec().movePicX(wsMessage, UserListRequest.ERRMSG_LENGTH);
            assertThat(moved)
                    .as("the surviving characters are the leading 78")
                    .hasSize(UserListRequest.ERRMSG_LENGTH)
                    .isEqualTo("X".repeat(UserListRequest.ERRMSG_LENGTH))
                    .doesNotContain("Y")
                    .doesNotContain("Z");
            assertThat(blankRequest().withErrMsg(moved).errMsg()).isEqualTo(moved);

            // The narrowing has to be asked for. Handing the full 80 characters to the payload is
            // rejected rather than silently shortened, so a lost value is never invisible.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> blankRequest().withErrMsg(wsMessage))
                    .withMessageContaining(UserListRequest.ERRMSG_FIELD)
                    .withMessageContaining("movePicX");
        }

        @Test
        @DisplayName("a shorter message is space-padded to 78, not left short")
        void aShorterMessageIsPaddedToTheFieldWidth() {
            // CCDA-MSG-INVALID-KEY is the message app/cbl/COUSR00C.cbl:135 moves into WS-MESSAGE on the
            // WHEN OTHER arm of EVALUATE EIBAID. It is fifty characters and fits, so the MOVE pads.
            assertThat(SystemMessages.CCDA_MSG_INVALID_KEY)
                    .hasSize(SystemMessages.MESSAGE_LENGTH)
                    .hasSize(50);
            String padded = codec().movePicX(SystemMessages.CCDA_MSG_INVALID_KEY,
                    UserListRequest.ERRMSG_LENGTH);
            // Stated as the whole expected image rather than by inspecting a slice of it, so the
            // padding is asserted in full and no substring stands in for the MOVE rule.
            assertThat(padded)
                    .hasSize(UserListRequest.ERRMSG_LENGTH)
                    .isEqualTo(SystemMessages.CCDA_MSG_INVALID_KEY
                            + " ".repeat(UserListRequest.ERRMSG_LENGTH
                                    - SystemMessages.MESSAGE_LENGTH));
        }

        @Test
        @DisplayName("TITLE01 and TITLE02 are X(40), and each title literal is exactly 40 characters")
        void theTitlesFillTheirFieldsExactly() {
            // app/cbl/COUSR00C.cbl:566-567 move CCDA-TITLE01 and CCDA-TITLE02 into the two title
            // fields. Both literals are declared X(40) including their padding, so the MOVE neither
            // pads nor truncates - which is why the padding is part of the expected value.
            assertThat(ScreenTitles.TITLE_LENGTH).isEqualTo(UserListRequest.TITLE01_LENGTH)
                    .isEqualTo(UserListRequest.TITLE02_LENGTH).isEqualTo(40);
            assertThat(ScreenTitles.CCDA_TITLE01).hasSize(UserListRequest.TITLE01_LENGTH);
            assertThat(ScreenTitles.CCDA_TITLE02).hasSize(UserListRequest.TITLE02_LENGTH);

            UserListRequest request = blankRequest()
                    .withTitle01(ScreenTitles.CCDA_TITLE01)
                    .withTitle02(ScreenTitles.CCDA_TITLE02);
            assertThat(request.title01()).isEqualTo(ScreenTitles.CCDA_TITLE01);
            assertThat(request.title02()).isEqualTo(ScreenTitles.CCDA_TITLE02);
            assertThat(codec().movePicX(ScreenTitles.CCDA_TITLE01, UserListRequest.TITLE01_LENGTH))
                    .as("a MOVE at equal width is the identity")
                    .isEqualTo(ScreenTitles.CCDA_TITLE01);
        }

        @Test
        @DisplayName("the two thank-you literals are different texts of different widths in different owners")
        void theThankYouLiteralsAreNotInterchangeable() {
            // ScreenTitles.CCDA_THANK_YOU comes from app/cpy/COTTL01Y.cpy and is X(40);
            // SystemMessages.CCDA_MSG_THANK_YOU comes from app/cpy/CSMSG01Y.cpy and is X(50). Different
            // copybooks, different widths, different words. Substituting one for the other produces a
            // value that looks right in a log and differs byte for byte in a diff.
            assertThat(ScreenTitles.CCDA_THANK_YOU).hasSize(ScreenTitles.TITLE_LENGTH).hasSize(40);
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU)
                    .hasSize(SystemMessages.MESSAGE_LENGTH).hasSize(50);
            assertThat(ScreenTitles.CCDA_THANK_YOU.length())
                    .isNotEqualTo(SystemMessages.CCDA_MSG_THANK_YOU.length());
            assertThat(ScreenTitles.CCDA_THANK_YOU.trim())
                    .isNotEqualTo(SystemMessages.CCDA_MSG_THANK_YOU.trim());

            // Both fit the 78-character message field, so width alone would not catch the mistake -
            // only the text would.
            assertThat(SystemMessages.MESSAGE_LENGTH)
                    .isLessThanOrEqualTo(UserListRequest.ERRMSG_LENGTH);
        }
    }


    // =================================================================================================
    // 3. TEN ROWS IS BEHAVIOUR, NOT CONFIGURATION (gates G39, G33).
    //
    // The mapset declares ten row groups and app/cbl/COUSR00C.cbl restates ten five times over. A page
    // size that could be changed would change what a client observes, which a like-for-like migration
    // may not do. The one-based-to-zero-based conversion is asserted at BOTH ends of the table, because
    // OCCURS indexing is the single most common defect in a migration of this kind.
    // =================================================================================================

    @Nested
    @DisplayName("Page size ten - fixed by the map and by COUSR00C's loop bounds")
    class PageSizeIsBehaviour {

        @Test
        @DisplayName("exactly ten rows of five fields, and fifty of the fifty-nine fields are rows")
        void theTableIsTenRowsOfFive() {
            assertThat(UserListRequest.ROW_COUNT).isEqualTo(OCCURS_COUNT).isEqualTo(10);
            assertThat(UserListRequest.ROW_FIELD_COUNT).isEqualTo(5);
            assertThat(UserListRequest.ROW_COUNT * UserListRequest.ROW_FIELD_COUNT).isEqualTo(50);
            assertThat(blankRequest().rows()).hasSize(UserListRequest.ROW_COUNT);
            assertThat(UserListRequest.blankRows()).hasSize(UserListRequest.ROW_COUNT);
            assertThat(populatedRequest().rows()).hasSize(UserListRequest.ROW_COUNT);
            assertThat(UserListRow.class.getRecordComponents())
                    .as("SEL X(1), USRID X(8), FNAME X(20), LNAME X(20), UTYPE X(1)")
                    .hasSize(UserListRequest.ROW_FIELD_COUNT);
        }

        @Test
        @DisplayName("ten is stated five times in the source, so it is a property and not a setting")
        void tenIsRestatedFiveTimesInTheSource() {
            // 57  02 USER-REC OCCURS 10 TIMES
            // 293 PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > 10   (forward page, blank first)
            // 300 PERFORM UNTIL WS-IDX >= 11 OR USER-SEC-EOF OR ERR-FLG-ON
            // 347 PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > 10   (backward page, blank first)
            // 352 MOVE 10 TO WS-IDX                                      (backward page fills 10..1)
            assertThat(PAGE_SIZE_EVIDENCE_LINES).hasSize(5).containsExactly(57, 293, 300, 347, 352);
            assertThat(EXCLUSIVE_FORWARD_BOUND)
                    .as("the forward loop stops when WS-IDX reaches 11, having filled 1..10")
                    .isEqualTo(LAST_SUBSCRIPT + 1);
            assertThat(EXCLUSIVE_BACKWARD_BOUND)
                    .as("the backward loop stops when WS-IDX reaches 0, having filled 10..1")
                    .isEqualTo(FIRST_SUBSCRIPT - 1);
        }

        @Test
        @DisplayName("no page-size setter, constructor parameter or configuration key exists")
        void thePageSizeCannotBeChanged() {
            // Two searches, both negative: no mutator whose name suggests a page size, and no record
            // component that would let one be passed in. ROW_COUNT is a public constant because the
            // number is part of the contract, and a constant is not a setting.
            List<String> methodNames = Arrays.stream(UserListRequest.class.getDeclaredMethods())
                    .map(Method::getName).toList();
            assertThat(methodNames)
                    .as("a page size that could be set would make ten a configurable value")
                    .doesNotContain("setPageSize", "withPageSize", "setRowCount", "withRowCount",
                            "pageSize", "rowCount");

            assertThat(Arrays.stream(UserListRequest.class.getRecordComponents())
                    .map(RecordComponent::getName).toList())
                    .doesNotContain("pageSize", "rowCount", "rowsPerPage");

            // pageNum and cdemoCu00PageNum are which page, not how big a page is. Neither is a size.
            assertThat(MAP_MEMBERS).contains("pageNum");
            assertThat(UserListRequest.PAGENUM_LENGTH)
                    .as("PAGENUMI PIC X(8) is the page number's image, not a page size")
                    .isEqualTo(CU00_PAGE_NUM_DIGITS);
        }

        @ParameterizedTest(name = "COBOL subscript {0} is Java index {1}")
        @CsvSource({"1, 0", "2, 1", "3, 2", "4, 3", "5, 4", "6, 5", "7, 6", "8, 7", "9, 8", "10, 9"})
        @DisplayName("row(n) addresses rows().get(n - 1), at every subscript in the table")
        void theOneBasedSubscriptMapsToTheZeroBasedIndex(int subscript, int index) {
            UserListRequest request = populatedRequest();

            assertThat(request.row(subscript))
                    .as("WS-IDX %d is list index %d", subscript, index)
                    .isSameAs(request.rows().get(index));

            // The same conversion, done by the codec's own named helper over the 500-byte row table,
            // so the arithmetic is proved in the layer that owns it rather than only in the DTO.
            int rowTableOffset = SYMBOLIC_MAP_LAYOUT.span(SYMBOLIC_MAP_ITEMS.get(
                    UserListRequest.HEADER_FIELD_COUNT)).offset() - FIELD_PREFIX_LENGTH;
            int elementOffset = FixedWidthRecord.occursElementOffsetOneBased(rowTableOffset,
                    MAP_ROW_PAYLOAD_LENGTH + UserListRequest.ROW_FIELD_COUNT * FIELD_PREFIX_LENGTH,
                    OCCURS_COUNT, subscript);
            assertThat(elementOffset)
                    .as("element %d starts %d whole rows past the table's base", subscript, index)
                    .isEqualTo(rowTableOffset
                            + index * (MAP_ROW_PAYLOAD_LENGTH
                                    + UserListRequest.ROW_FIELD_COUNT * FIELD_PREFIX_LENGTH));
        }

        @Test
        @DisplayName("the first row is index 0 and the last row is index 9, both proved by value")
        void theFirstAndLastElementsAreBothAsserted() {
            UserListRequest request = populatedRequest();

            assertThat(request.row(FIRST_SUBSCRIPT).usrId())
                    .as("WS-IDX 1 writes USRID01I, which is list index 0")
                    .isEqualTo(request.rows().get(0).usrId())
                    .isEqualTo(request.usrId01());
            assertThat(request.row(LAST_SUBSCRIPT).usrId())
                    .as("WS-IDX 10 writes USRID10I, which is list index 9")
                    .isEqualTo(request.rows().get(UserListRequest.ROW_COUNT - 1).usrId())
                    .isEqualTo(request.usrId10());

            // And they are genuinely different rows, so an off-by-one could not pass by coincidence.
            assertThat(request.row(FIRST_SUBSCRIPT).usrId())
                    .isNotEqualTo(request.row(LAST_SUBSCRIPT).usrId());
        }

        @ParameterizedTest(name = "subscript {0} is rejected")
        @ValueSource(ints = {-1, 0, 11, 12, Integer.MIN_VALUE, Integer.MAX_VALUE})
        @DisplayName("a subscript outside 1..10 is rejected, never clamped to the nearest row")
        void anOutOfRangeSubscriptIsRejected(int subscript) {
            UserListRequest request = populatedRequest();

            // Clamping is the dangerous alternative: subscript 11 would silently return row 10 and a
            // paging defect would look like correct output. The message names the one-based convention
            // so the caller can see which counting scheme it broke.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> request.row(subscript))
                    .withMessageContaining("between 1 and " + UserListRequest.ROW_COUNT);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> request.withRow(subscript, UserListRow.blank()));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> UserListRequest.selFieldName(subscript));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> UserListRequest.usrIdFieldName(subscript));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> UserListRequest.fnameFieldName(subscript));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> UserListRequest.lnameFieldName(subscript));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> UserListRequest.utypeFieldName(subscript));
        }

        @Test
        @DisplayName("the codec's own OCCURS helper refuses subscript 0 and subscript 11 too")
        void theCodecRefusesTheSameOutOfRangeSubscripts() {
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> FixedWidthRecord.occursElementOffsetOneBased(0,
                            MAP_ROW_PAYLOAD_LENGTH, OCCURS_COUNT, EXCLUSIVE_BACKWARD_BOUND))
                    .withMessageContaining("1..");
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> FixedWidthRecord.occursElementOffsetOneBased(0,
                            MAP_ROW_PAYLOAD_LENGTH, OCCURS_COUNT, EXCLUSIVE_FORWARD_BOUND));

            assertThat(FixedWidthRecord.occursElementOffsetOneBased(0, MAP_ROW_PAYLOAD_LENGTH,
                    OCCURS_COUNT, FIRST_SUBSCRIPT))
                    .as("subscript 1 is the base offset itself")
                    .isZero();
            assertThat(FixedWidthRecord.occursElementOffsetOneBased(0, MAP_ROW_PAYLOAD_LENGTH,
                    OCCURS_COUNT, LAST_SUBSCRIPT))
                    .as("subscript 10 is nine whole rows in")
                    .isEqualTo((OCCURS_COUNT - 1) * MAP_ROW_PAYLOAD_LENGTH);
        }

        @Test
        @DisplayName("a row list of any size other than ten is refused, not padded or truncated")
        void onlyTenRowsAreAccepted() {
            List<UserListRow> nine = new ArrayList<>(UserListRequest.blankRows());
            nine.remove(0);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> blankRequest().withRows(nine))
                    .withMessageContaining("exactly " + UserListRequest.ROW_COUNT)
                    .withMessageContaining("not a configurable page size");

            List<UserListRow> eleven = new ArrayList<>(UserListRequest.blankRows());
            eleven.add(UserListRow.blank());
            assertThatIllegalArgumentException().isThrownBy(() -> blankRequest().withRows(eleven));

            assertThatIllegalArgumentException().isThrownBy(() -> blankRequest().withRows(List.of()));

            // Absent is the one accepted alternative, and it becomes ten blank rows rather than none:
            // INITIALIZE-USER-DATA blanks all ten before every page.
            assertThat(blankRequest().withRows(null).rows()).hasSize(UserListRequest.ROW_COUNT);
        }

        @Test
        @DisplayName("replacing one row leaves the other nine untouched")
        void replacingOneRowIsLocal() {
            UserListRequest before = populatedRequest();
            UserListRow replacement = UserListRow.blank().withUsrId("REPLACED");
            UserListRequest after = before.withRow(5, replacement);

            assertThat(after.row(5)).isEqualTo(replacement);
            for (int rowNumber = FIRST_SUBSCRIPT; rowNumber <= LAST_SUBSCRIPT; rowNumber++) {
                if (rowNumber != 5) {
                    assertThat(after.row(rowNumber))
                            .as("row %d must be untouched", rowNumber)
                            .isEqualTo(before.row(rowNumber));
                }
            }
            assertThat(before.row(5))
                    .as("the type is immutable, so the original still holds its own row")
                    .isNotEqualTo(replacement);
        }
    }

    // =================================================================================================
    // 4. THE 34-BYTE CDEMO-CU00-INFO EXTENSION (gates G22, G50).
    //
    // app/cbl/COUSR00C.cbl:66 copies the shared 160-byte communication area and lines 67-75 then extend
    // it, inside this program only, with six fields of its own. The extension belongs to this payload;
    // the shared area must stay 160 bytes for all seventeen online programs.
    // =================================================================================================

    @Nested
    @DisplayName("The CU00 paging context - 160 + 34 = 194 bytes")
    class Cu00CommareaExtension {

        @Test
        @DisplayName("all six CDEMO-CU00-* members live on this payload")
        void theSixExtensionMembersAreCarriedHere() {
            assertThat(Arrays.stream(UserListRequest.class.getRecordComponents())
                    .map(RecordComponent::getName).toList())
                    .containsSubsequence("cdemoCu00UsrIdFirst", "cdemoCu00UsrIdLast",
                            "cdemoCu00PageNum", "cdemoCu00NextPageFlg", "cdemoCu00UsrSelFlg",
                            "cdemoCu00UsrSelected");

            assertThat(UserListRequest.CU00_USRID_FIRST_FIELD).isEqualTo("CDEMO-CU00-USRID-FIRST");
            assertThat(UserListRequest.CU00_USRID_LAST_FIELD).isEqualTo("CDEMO-CU00-USRID-LAST");
            assertThat(UserListRequest.CU00_PAGE_NUM_FIELD).isEqualTo("CDEMO-CU00-PAGE-NUM");
            assertThat(UserListRequest.CU00_NEXT_PAGE_FLG_FIELD)
                    .isEqualTo("CDEMO-CU00-NEXT-PAGE-FLG");
            assertThat(UserListRequest.CU00_USR_SEL_FLG_FIELD).isEqualTo("CDEMO-CU00-USR-SEL-FLG");
            assertThat(UserListRequest.CU00_USR_SELECTED_FIELD).isEqualTo("CDEMO-CU00-USR-SELECTED");
        }

        @Test
        @DisplayName("the extension is 34 bytes: 8 + 8 + 8 + 1 + 1 + 8")
        void theExtensionIsThirtyFourBytes() {
            assertThat(UserListRequest.CU00_USRID_FIRST_LENGTH).isEqualTo(CU00_USRID_FIRST_LENGTH);
            assertThat(UserListRequest.CU00_USRID_LAST_LENGTH).isEqualTo(CU00_USRID_LAST_LENGTH);
            assertThat(UserListRequest.CU00_PAGE_NUM_LENGTH).isEqualTo(CU00_PAGE_NUM_DIGITS);
            assertThat(UserListRequest.CU00_NEXT_PAGE_FLG_LENGTH)
                    .isEqualTo(CU00_NEXT_PAGE_FLG_LENGTH);
            assertThat(UserListRequest.CU00_USR_SEL_FLG_LENGTH).isEqualTo(CU00_USR_SEL_FLG_LENGTH);
            assertThat(UserListRequest.CU00_USR_SELECTED_LENGTH).isEqualTo(CU00_USR_SELECTED_LENGTH);

            int sum = CU00_USRID_FIRST_LENGTH + CU00_USRID_LAST_LENGTH + CU00_PAGE_NUM_DIGITS
                    + CU00_NEXT_PAGE_FLG_LENGTH + CU00_USR_SEL_FLG_LENGTH
                    + CU00_USR_SELECTED_LENGTH;
            assertThat(sum).isEqualTo(CU00_INFO_LENGTH).isEqualTo(34);
            assertThat(UserListRequest.CU00_INFO_LENGTH).isEqualTo(CU00_INFO_LENGTH);

            // Proved through the codec at the declared code page, so the arithmetic is bytes and not
            // characters: the images the six members carry tile 34 bytes exactly.
            FixedWidthCodec codec = codec();
            String image = codec.movePicX("USER0001", CU00_USRID_FIRST_LENGTH)
                    + codec.movePicX("USER0010", CU00_USRID_LAST_LENGTH)
                    + codec.movePic9(1L, CU00_PAGE_NUM_DIGITS)
                    + codec.movePicX(NEXT_PAGE_YES_VALUE, CU00_NEXT_PAGE_FLG_LENGTH)
                    + codec.movePicX(USR_SEL_UPDATE_VALUE, CU00_USR_SEL_FLG_LENGTH)
                    + codec.movePicX("USER0001", CU00_USR_SELECTED_LENGTH);
            assertThat(codec.encodeImage(image, UserListRequest.CU00_PAGE_NUM_FIELD))
                    .hasSize(CU00_INFO_LENGTH);
        }

        @Test
        @DisplayName("the shared communication area stays 160 bytes, and 160 + 34 = 194")
        void theSharedAreaIsNotExtended() {
            // app/cpy/COCOM01Y.cpy:19-44 - general 34 + customer 84 + account 12 + card 16 + more 14.
            // CDEMO-LAST-MAP and CDEMO-LAST-MAPSET are both PIC X(7), not X(8): reading them as eight
            // is the classic way this total comes out at 162.
            assertThat(NavigationContext.GENERAL_INFO_LENGTH).isEqualTo(34);
            assertThat(NavigationContext.CUSTOMER_INFO_LENGTH).isEqualTo(84);
            assertThat(NavigationContext.ACCOUNT_INFO_LENGTH).isEqualTo(12);
            assertThat(NavigationContext.CARD_INFO_LENGTH).isEqualTo(16);
            assertThat(NavigationContext.MORE_INFO_LENGTH)
                    .as("CDEMO-LAST-MAP X(7) + CDEMO-LAST-MAPSET X(7)")
                    .isEqualTo(NavigationContext.LAST_MAP_LENGTH
                            + NavigationContext.LAST_MAPSET_LENGTH)
                    .isEqualTo(14);
            assertThat(NavigationContext.GENERAL_INFO_LENGTH
                    + NavigationContext.CUSTOMER_INFO_LENGTH
                    + NavigationContext.ACCOUNT_INFO_LENGTH
                    + NavigationContext.CARD_INFO_LENGTH
                    + NavigationContext.MORE_INFO_LENGTH)
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH)
                    .isEqualTo(COMMAREA_LENGTH);

            // Proved in bytes: the shared layout tiles exactly 160 and nothing has been appended to it.
            assertThat(NavigationContext.LAYOUT.recordLength()).isEqualTo(COMMAREA_LENGTH);
            assertThat(FixedWidthRecord.forLayout(NavigationContext.LAYOUT, MAP_CHARSET)
                    .toByteArray()).hasSize(COMMAREA_LENGTH);

            assertThat(COMMAREA_LENGTH + CU00_INFO_LENGTH)
                    .isEqualTo(CU00_COMMAREA_LENGTH).isEqualTo(194);
            assertThat(UserListRequest.CU00_COMMAREA_LENGTH).isEqualTo(CU00_COMMAREA_LENGTH);
            assertThat(populatedRequest().commareaLength())
                    .as("EIBCALEN for transaction CU00")
                    .isEqualTo(CU00_COMMAREA_LENGTH);
        }

        @Test
        @DisplayName("the extension is NOT folded into NavigationContext, which stays screen-agnostic")
        void theExtensionIsNotInTheSharedType() {
            // Three sibling programs declare their own extension block under three different prefixes -
            // CDEMO-CU00-* here, CDEMO-CU02-* at COUSR02C:50-58 and CDEMO-CU03-* at COUSR03C:50-58 -
            // while COSGN00C and COUSR01C declare none. They are three distinct blocks, so folding any
            // of them into the shared type would put one screen's fields on the other sixteen.
            List<String> sharedComponents = Arrays.stream(
                            NavigationContext.class.getRecordComponents())
                    .map(RecordComponent::getName).toList();
            assertThat(sharedComponents)
                    .doesNotContain("cdemoCu00UsrIdFirst", "cdemoCu00UsrIdLast", "cdemoCu00PageNum",
                            "cdemoCu00NextPageFlg", "cdemoCu00UsrSelFlg", "cdemoCu00UsrSelected");
            assertThat(sharedComponents.stream().filter(name -> name.contains("Cu0")).toList())
                    .as("no screen-specific member of any kind belongs to the shared area")
                    .isEmpty();
            assertThat(NavigationContext.LAYOUT.hasSpan(UserListRequest.CU00_PAGE_NUM_FIELD))
                    .isFalse();
        }

        @Test
        @DisplayName("the page number is an int, and no floating-point type appears anywhere")
        void thePageNumberIsIntegral() {
            // PIC 9(08) is scale-free, so there is nothing to round and no fraction to lose. A double
            // would also stop being able to represent every eight-digit value exactly once past 2^53,
            // which is precisely the class of silent difference the parity gate exists to catch.
            RecordComponent pageNum = Arrays.stream(UserListRequest.class.getRecordComponents())
                    .filter(component -> "cdemoCu00PageNum".equals(component.getName()))
                    .findFirst()
                    .orElseThrow();
            assertThat(pageNum.getType()).isEqualTo(int.class);

            for (RecordComponent component : UserListRequest.class.getRecordComponents()) {
                assertThat(component.getType())
                        .as("%s must not be a floating-point type", component.getName())
                        .isNotIn(double.class, float.class, Double.class, Float.class);
            }
            for (RecordComponent component : UserListRow.class.getRecordComponents()) {
                assertThat(component.getType())
                        .isNotIn(double.class, float.class, Double.class, Float.class);
            }
            for (Method method : UserListRequest.class.getDeclaredMethods()) {
                assertThat(method.getReturnType())
                        .as("%s must not return a floating-point type", method.getName())
                        .isNotIn(double.class, float.class, Double.class, Float.class);
            }
        }

        @ParameterizedTest(name = "page number {0} renders as \"{1}\"")
        @CsvSource({"0, 00000000", "1, 00000001", "2, 00000002", "10, 00000010",
            "99999999, 99999999"})
        @DisplayName("the numeric page number renders as the zero-filled eight-character image")
        void thePageNumberRendersZeroFilled(int pageNumber, String expectedImage) {
            // app/cbl/COUSR00C.cbl:327 and :376 both execute
            //     MOVE CDEMO-CU00-PAGE-NUM TO PAGENUMI OF COUSR0AI
            // which is a PIC 9(08) to PIC X(08) move. A numeric sending field is moved with its
            // leading zeros intact, so page one paints as 00000001 and not as "1" or as "       1".
            // The rendering goes through the codec's PIC 9 helper, never String.format: the codec is
            // the single auditable seam for every fixed-width move in this module.
            String image = codec().movePic9((long) pageNumber, UserListRequest.PAGENUM_LENGTH);
            assertThat(image).isEqualTo(expectedImage).hasSize(UserListRequest.PAGENUM_LENGTH);

            UserListRequest request = blankRequest()
                    .withCdemoCu00PageNum(pageNumber)
                    .withPageNum(image);
            assertThat(request.cdemoCu00PageNum()).isEqualTo(pageNumber);
            assertThat(request.pageNum())
                    .as("the two members are one value in two representations")
                    .isEqualTo(expectedImage);
            assertThat(codec().decodePic9AsInt(request.pageNum()))
                    .as("and the image decodes back to the number it was rendered from")
                    .isEqualTo(request.cdemoCu00PageNum());
        }

        @Test
        @DisplayName("the page number's bounds are PIC 9(08)'s own: zero is valid, negative is not")
        void thePageNumberIsBoundedByItsPictureClause() {
            assertThat(UserListRequest.CU00_PAGE_NUM_INITIAL)
                    .as("app/cbl/COUSR00C.cbl:227 zeroes the field before the first forward page")
                    .isZero();
            assertThat(UserListRequest.CU00_PAGE_NUM_MAX).isEqualTo(PAGE_NUM_MAX);
            assertThat(blankRequest().cdemoCu00PageNum()).isEqualTo(UserListRequest
                    .CU00_PAGE_NUM_INITIAL);
            assertThat(blankRequest().withCdemoCu00PageNum(PAGE_NUM_MAX).cdemoCu00PageNum())
                    .isEqualTo(PAGE_NUM_MAX);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> blankRequest().withCdemoCu00PageNum(-1))
                    .withMessageContaining("unsigned");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> blankRequest().withCdemoCu00PageNum(PAGE_NUM_MAX + 1))
                    .withMessageContaining("more than " + CU00_PAGE_NUM_DIGITS + " digits");
        }

        @Test
        @DisplayName("NEXT-PAGE-FLG defaults to 'N', as its VALUE clause declares")
        void theNextPageFlagDefaultsToNo() {
            // app/cbl/COUSR00C.cbl:71 declares PIC X(01) VALUE 'N', and line 102 restates it with
            // SET NEXT-PAGE-NO TO TRUE before anything else runs. The neighbouring
            // CDEMO-CU00-USR-SEL-FLG has no VALUE clause and starts blank; that asymmetry is the
            // source's and is preserved.
            UserListRequest blank = blankRequest();
            assertThat(blank.cdemoCu00NextPageFlg()).isEqualTo(NEXT_PAGE_FLG_DEFAULT);
            assertThat(blank.nextPageNo()).isTrue();
            assertThat(blank.nextPageYes()).isFalse();
            assertThat(blank.cdemoCu00UsrSelFlg())
                    .as("declared without a VALUE clause, so blank rather than 'N'")
                    .isEqualTo(" ")
                    .hasSize(CU00_USR_SEL_FLG_LENGTH);
        }

        @ParameterizedTest(name = "flag \"{0}\": NEXT-PAGE-YES={1}, NEXT-PAGE-NO={2}")
        @CsvSource({"Y, true, false", "N, false, true", "' ', false, false", "y, false, false",
            "n, false, false", "X, false, false"})
        @DisplayName("both 88-levels are driven in both readings, and a third value satisfies neither")
        void bothConditionNamesAreDrivenBothWays(String flag, boolean yes, boolean no) {
            // app/cbl/COUSR00C.cbl:72-73 declare exactly two condition names over one byte and no
            // WHEN OTHER, so a byte that is neither 'Y' nor 'N' makes both tests false. The lower-case
            // cases matter because the neighbouring selection flag IS case-insensitive - the source
            // spells out WHEN 'U' WHEN 'u' at lines 190-191 - and these two are not.
            UserListRequest request = blankRequest().withCdemoCu00NextPageFlg(flag);

            assertThat(request.nextPageYes()).as("NEXT-PAGE-YES for '%s'", flag).isEqualTo(yes);
            assertThat(request.nextPageNo()).as("NEXT-PAGE-NO for '%s'", flag).isEqualTo(no);
            assertThat(yes && no).as("the two conditions are mutually exclusive").isFalse();
            assertThat(request.cdemoCu00NextPageFlg())
                    .hasSize(UserListRequest.CU00_NEXT_PAGE_FLG_LENGTH);
        }

        @Test
        @DisplayName("the two convenience setters set exactly the two declared 88-level values")
        void theConvenienceSettersUseTheDeclaredValues() {
            assertThat(UserListRequest.NEXT_PAGE_YES).isEqualTo(NEXT_PAGE_YES_VALUE);
            assertThat(UserListRequest.NEXT_PAGE_NO).isEqualTo(NEXT_PAGE_NO_VALUE);

            UserListRequest yes = blankRequest().withNextPageYes();
            assertThat(yes.cdemoCu00NextPageFlg()).isEqualTo(NEXT_PAGE_YES_VALUE);
            assertThat(yes.nextPageYes()).isTrue();
            assertThat(yes.nextPageNo()).isFalse();

            UserListRequest no = yes.withNextPageNo();
            assertThat(no.cdemoCu00NextPageFlg()).isEqualTo(NEXT_PAGE_NO_VALUE);
            assertThat(no.nextPageNo()).isTrue();
            assertThat(no.nextPageYes()).isFalse();
        }

        @Test
        @DisplayName("the selection flag and the selected id are both carried, at 1 and 8 characters")
        void theSelectionPairIsCarried() {
            // app/cbl/COUSR00C.cbl:187-188 tests BOTH fields before routing - an abbreviated combined
            // relation condition, NOT = SPACES AND LOW-VALUES meaning neither spaces nor low values -
            // so a payload that carried only one of them could not express the condition at all. The
            // routing itself, 'U'/'u' to COUSR02C at 192 and 'D'/'d' to COUSR03C at 202, is asserted on
            // the response side; only the carriage is this file's subject.
            UserListRequest request = blankRequest()
                    .withCdemoCu00UsrSelFlg(USR_SEL_UPDATE_VALUE)
                    .withCdemoCu00UsrSelected(codec().movePicX("USER0001",
                            CU00_USR_SELECTED_LENGTH));

            assertThat(request.cdemoCu00UsrSelFlg()).hasSize(CU00_USR_SEL_FLG_LENGTH)
                    .isEqualTo(USR_SEL_UPDATE_VALUE);
            assertThat(request.cdemoCu00UsrSelected()).hasSize(CU00_USR_SELECTED_LENGTH)
                    .isEqualTo("USER0001");
            assertThat(request.usrSelUpdate()).isTrue();
            assertThat(request.usrSelDelete()).isFalse();

            UserListRequest delete = request.withCdemoCu00UsrSelFlg(USR_SEL_DELETE_VALUE);
            assertThat(delete.usrSelDelete()).isTrue();
            assertThat(delete.usrSelUpdate()).isFalse();

            // Lower case reaches the same arm, because the source spells both cases out.
            assertThat(request.withCdemoCu00UsrSelFlg("u").usrSelUpdate()).isTrue();
            assertThat(request.withCdemoCu00UsrSelFlg("d").usrSelDelete()).isTrue();

            // And anything else is neither, which is the WHEN OTHER arm at line 210 - a message, not a
            // rejection.
            UserListRequest neither = request.withCdemoCu00UsrSelFlg("X");
            assertThat(neither.usrSelUpdate()).isFalse();
            assertThat(neither.usrSelDelete()).isFalse();
            assertThat(blankRequest().usrSelUpdate()).isFalse();
            assertThat(blankRequest().usrSelDelete()).isFalse();
        }

        @Test
        @DisplayName("the first and last user ids of the page are both carried, at eight characters")
        void thePageBoundaryIdsAreCarried() {
            // app/cbl/COUSR00C.cbl:239 and :262 test CDEMO-CU00-USRID-FIRST and -LAST for spaces or low
            // values to decide whether a page-backward or page-forward browse starts at the file's end
            // or at a remembered key. Both are eight characters, exactly SEC-USR-ID's width.
            UserListRequest request = blankRequest()
                    .withCdemoCu00UsrIdFirst(codec().movePicX("USER0001", CU00_USRID_FIRST_LENGTH))
                    .withCdemoCu00UsrIdLast(codec().movePicX("USER0010", CU00_USRID_LAST_LENGTH));

            assertThat(request.cdemoCu00UsrIdFirst()).isEqualTo("USER0001")
                    .hasSize(SEC_USR_ID_LENGTH);
            assertThat(request.cdemoCu00UsrIdLast()).isEqualTo("USER0010")
                    .hasSize(SEC_USR_ID_LENGTH);
            assertThat(blankRequest().cdemoCu00UsrIdFirst())
                    .as("blank is meaningful: it starts the browse at the beginning of the file")
                    .isEqualTo(" ".repeat(CU00_USRID_FIRST_LENGTH));
            assertThat(blankRequest().cdemoCu00UsrIdLast())
                    .isEqualTo(" ".repeat(CU00_USRID_LAST_LENGTH));
        }
    }


    // =================================================================================================
    // 5. THE ROW WIDTHS COME FROM CSUSR01Y, NOT FROM WS-USER-DATA.
    //
    // Two structures in this program describe a row of users, and they do not agree. The map projects
    // SEC-USR-FNAME X(20), SEC-USR-LNAME X(20) and SEC-USR-TYPE X(01); COUSR00C's own working-storage
    // display table declares USER-NAME X(25) and USER-TYPE X(08). Adopting the display table's widths
    // produces a DTO that looks entirely plausible and differs from the screen by five characters in
    // one field and seven in another. The difference is documented here, not harmonised (B4).
    // =================================================================================================

    @Nested
    @DisplayName("Row widths - from CSUSR01Y and the map, never from WS-USER-DATA")
    class RowWidthsComeFromCsusr01y {

        @Test
        @DisplayName("the four row data widths are SEC-USR-ID, -FNAME, -LNAME and -TYPE")
        void theRowWidthsAreTheSecurityRecordsOwn() {
            // app/cpy/CSUSR01Y.cpy:18-22, moved onto the screen by POPULATE-USER-DATA.
            assertThat(UserListRequest.USRID_LENGTH).isEqualTo(SEC_USR_ID_LENGTH).isEqualTo(8);
            assertThat(UserListRequest.FNAME_LENGTH).isEqualTo(SEC_USR_FNAME_LENGTH).isEqualTo(20);
            assertThat(UserListRequest.LNAME_LENGTH).isEqualTo(SEC_USR_LNAME_LENGTH).isEqualTo(20);
            assertThat(UserListRequest.UTYPE_LENGTH).isEqualTo(SEC_USR_TYPE_LENGTH).isEqualTo(1);

            // SEL is the user's own input column and has no counterpart in the record at all.
            assertThat(UserListRequest.SEL_LENGTH).isEqualTo(1);
        }

        @Test
        @DisplayName("those widths match SecUserRecord's declared offsets and its 80-byte total")
        void theWidthsMatchTheRecordType() {
            assertThat(SecUserRecord.RECORD_LENGTH).isEqualTo(SEC_USER_DATA_LENGTH).isEqualTo(80);
            assertThat(SecUserRecord.SEC_USR_ID_OFFSET).isZero();
            assertThat(SecUserRecord.SEC_USR_FNAME_OFFSET).isEqualTo(8);
            assertThat(SecUserRecord.SEC_USR_LNAME_OFFSET).isEqualTo(28);
            assertThat(SecUserRecord.SEC_USR_PWD_OFFSET).isEqualTo(48);
            assertThat(SecUserRecord.SEC_USR_TYPE_OFFSET).isEqualTo(56);
            assertThat(SecUserRecord.SEC_USR_FILLER_OFFSET).isEqualTo(57);

            assertThat(SecUserRecord.SEC_USR_ID_LENGTH).isEqualTo(UserListRequest.USRID_LENGTH);
            assertThat(SecUserRecord.SEC_USR_FNAME_LENGTH).isEqualTo(UserListRequest.FNAME_LENGTH);
            assertThat(SecUserRecord.SEC_USR_LNAME_LENGTH).isEqualTo(UserListRequest.LNAME_LENGTH);
            assertThat(SecUserRecord.SEC_USR_TYPE_LENGTH).isEqualTo(UserListRequest.UTYPE_LENGTH);

            assertThat(SEC_USR_ID_LENGTH + SEC_USR_FNAME_LENGTH + SEC_USR_LNAME_LENGTH
                    + SEC_USR_PWD_LENGTH + SEC_USR_TYPE_LENGTH + SEC_USR_FILLER_LENGTH)
                    .isEqualTo(SEC_USER_DATA_LENGTH);

            // Encoded at the declared code page, the record is 80 bytes - so the offsets above are byte
            // offsets and not character positions that happen to coincide.
            assertThat(SecUserRecord.encode(SecUserRecord.of("USER0001", "First", "Last",
                    "NOTAREAL", NavigationContext.USER_TYPE_USER, MAP_CHARSET), MAP_CHARSET))
                    .hasSize(SecUserRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("only four of the record's six items reach the screen")
        void twoRecordItemsAreNotProjected() {
            // SEC-USR-PWD X(08) and the named SEC-USR-FILLER X(23) are read from USRSEC and never
            // painted: 8 + 20 + 20 + 1 = 49 of the record's 80 bytes appear on a row, and 8 + 23 = 31
            // do not.
            int projected = SEC_USR_ID_LENGTH + SEC_USR_FNAME_LENGTH + SEC_USR_LNAME_LENGTH
                    + SEC_USR_TYPE_LENGTH;
            assertThat(projected).isEqualTo(49);
            assertThat(SEC_USR_PWD_LENGTH + SEC_USR_FILLER_LENGTH).isEqualTo(31);
            assertThat(projected + SEC_USR_PWD_LENGTH + SEC_USR_FILLER_LENGTH)
                    .isEqualTo(SEC_USER_DATA_LENGTH);

            assertThat(SecUserRecord.blank().fieldImages().keySet())
                    .as("the record owns six items")
                    .containsExactly(SecUserRecord.FIELD_SEC_USR_ID, SecUserRecord.FIELD_SEC_USR_FNAME,
                            SecUserRecord.FIELD_SEC_USR_LNAME, SecUserRecord.FIELD_SEC_USR_PWD,
                            SecUserRecord.FIELD_SEC_USR_TYPE, SecUserRecord.FIELD_SEC_USR_FILLER);

            // One row of the map carries four of them plus the selection column: 1 + 8 + 20 + 20 + 1.
            assertThat(UserListRequest.SEL_LENGTH + projected).isEqualTo(MAP_ROW_PAYLOAD_LENGTH);
        }

        @Test
        @DisplayName("the DTO follows the map's 20/20/1, not the display table's 25/8")
        void theDisplayTableWidthsAreNotAdopted() {
            // app/cbl/COUSR00C.cbl:56-64 declares, in WORKING-STORAGE:
            //     01 WS-USER-DATA.
            //       02 USER-REC OCCURS 10 TIMES.
            //         05 USER-SEL X(01)  05 FILLER X(02)  05 USER-ID X(08)  05 FILLER X(02)
            //         05 USER-NAME X(25) 05 FILLER X(02)  05 USER-TYPE X(08)
            // That is a formatting buffer with three two-byte gutters, 48 bytes per row, and its
            // USER-NAME and USER-TYPE widths are 25 and 8. The map's are 20, 20 and 1. Neither
            // structure is wrong; they describe different things, and the payload projects the map.
            assertThat(WS_USER_SEL_LENGTH + WS_USER_REC_FILLER_COUNT * WS_USER_REC_FILLER_LENGTH
                    + WS_USER_ID_LENGTH + WS_USER_NAME_LENGTH + WS_USER_TYPE_LENGTH)
                    .as("1 + 3 x 2 + 8 + 25 + 8")
                    .isEqualTo(WS_USER_REC_LENGTH)
                    .isEqualTo(48);

            assertThat(UserListRequest.FNAME_LENGTH)
                    .as("FNAME is 20, not the display table's 25")
                    .isNotEqualTo(WS_USER_NAME_LENGTH);
            assertThat(UserListRequest.LNAME_LENGTH).isNotEqualTo(WS_USER_NAME_LENGTH);
            assertThat(UserListRequest.UTYPE_LENGTH)
                    .as("UTYPE is 1, not the display table's 8")
                    .isNotEqualTo(WS_USER_TYPE_LENGTH);

            // The row widths only coincide where the two structures genuinely agree.
            assertThat(UserListRequest.USRID_LENGTH).isEqualTo(WS_USER_ID_LENGTH);
            assertThat(UserListRequest.SEL_LENGTH).isEqualTo(WS_USER_SEL_LENGTH);

            // And the two row widths are different totals, so the mistake is measurable in bytes.
            assertThat(MAP_ROW_PAYLOAD_LENGTH).isEqualTo(50).isNotEqualTo(WS_USER_REC_LENGTH);
        }

        @Test
        @DisplayName("a row is rejected at 21 characters and accepted at 20")
        void aRowFieldWiderThanTheMapIsRejected() {
            // The boundary that separates the map from the display table: a 25-character name has no
            // representation in a 20-character field, so it is refused rather than shortened.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> UserListRow.blank()
                            .withFname("X".repeat(UserListRequest.FNAME_LENGTH + 1)))
                    .withMessageContaining(UserListRequest.FNAME_FIELD_STEM);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> UserListRow.blank().withFname("X".repeat(WS_USER_NAME_LENGTH)));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> UserListRow.blank().withUtype("X".repeat(WS_USER_TYPE_LENGTH)));

            assertThat(UserListRow.blank().withFname("X".repeat(UserListRequest.FNAME_LENGTH))
                    .fname()).hasSize(UserListRequest.FNAME_LENGTH);

            // Shortening is available, and has to be asked for by name.
            assertThat(codec().movePicX("X".repeat(WS_USER_NAME_LENGTH),
                    UserListRequest.FNAME_LENGTH)).hasSize(UserListRequest.FNAME_LENGTH);
        }

        @Test
        @DisplayName("a blank row is five fields of spaces, one per declared width")
        void aBlankRowIsSpacesNotNulls() {
            // INITIALIZE-USER-DATA blanks USRIDnnI, FNAMEnnI, LNAMEnnI and UTYPEnnI before every page,
            // so that a short final page leaves no stale row on the screen. There is no null in a COBOL
            // record, so absent is normalised to blank rather than carried as null.
            UserListRow blank = UserListRow.blank();
            assertThat(blank.sel()).isEqualTo(" ");
            assertThat(blank.usrId()).isEqualTo(" ".repeat(UserListRequest.USRID_LENGTH));
            assertThat(blank.fname()).isEqualTo(" ".repeat(UserListRequest.FNAME_LENGTH));
            assertThat(blank.lname()).isEqualTo(" ".repeat(UserListRequest.LNAME_LENGTH));
            assertThat(blank.utype()).isEqualTo(" ".repeat(UserListRequest.UTYPE_LENGTH));

            assertThat(new UserListRow(null, null, null, null, null))
                    .as("a null in every position becomes the blank row, never a null-bearing one")
                    .isEqualTo(blank);
            assertThat(UserListRequest.blankRows()).allSatisfy(row -> assertThat(row)
                    .isEqualTo(blank));
        }
    }

    // =================================================================================================
    // 6. THE CONVERSATION TRAVELS IN THE PAYLOAD, NEVER IN A SESSION (gates G37, G50).
    //
    // CICS is pseudo-conversational: COUSR00C returns after painting the screen and is re-entered from
    // the top on the next key press, so the only state that survives is what it handed back in the
    // communication area. The Java form keeps that shape - the area, the resolved key indication and
    // the ENTER-or-REENTER flag are payload members - which is what makes the endpoint stateless.
    // =================================================================================================

    @Nested
    @DisplayName("Conversation state - the commarea, the AID and the context flag are payload members")
    class ConversationState {

        @Test
        @DisplayName("the communication area is a member, so no session is needed to reconstruct it")
        void theCommareaIsCarried() {
            assertThat(Arrays.stream(UserListRequest.class.getRecordComponents())
                    .filter(component -> "navigationContext".equals(component.getName()))
                    .map(RecordComponent::getType).toList())
                    .containsExactly(NavigationContext.class);

            UserListRequest request = populatedRequest();
            assertThat(request.hasNavigationContext()).isTrue();
            assertThat(request.navigationContext()).isNotNull();
            assertThat(request.commareaLength()).isEqualTo(CU00_COMMAREA_LENGTH);

            // No member and no method refers to a session, a cache or a conversation store: if one did,
            // two clients sharing a server would be able to observe each other's paging.
            List<String> names = new ArrayList<>(Arrays.stream(
                            UserListRequest.class.getRecordComponents())
                    .map(RecordComponent::getName).toList());
            Arrays.stream(UserListRequest.class.getDeclaredMethods()).map(Method::getName)
                    .forEach(names::add);
            assertThat(names).noneMatch(name -> name.toLowerCase(Locale.ROOT)
                    .contains("session"));
        }

        @Test
        @DisplayName("an absent communication area stays absent, because EIBCALEN = 0 is a real state")
        void anAbsentCommareaIsNotInvented() {
            // app/cbl/COUSR00C.cbl:110 tests IF EIBCALEN = 0 before anything else and returns to the
            // sign-on screen. A freshly initialised area is not that state - it has a length - so
            // substituting one for the other would make the cold-start branch unreachable.
            UserListRequest cold = populatedRequest().withoutNavigationContext();
            assertThat(cold.navigationContext()).isNull();
            assertThat(cold.hasNavigationContext()).isFalse();
            assertThat(cold.commareaLength())
                    .as("EIBCALEN = 0 - the cold start")
                    .isZero();

            // And the rest of the payload is untouched by the distinction.
            assertThat(cold.mapFields()).isEqualTo(populatedRequest().mapFields());
        }

        @Test
        @DisplayName("the AID travels as a five-character token, not as a raw EIBAID byte")
        void theAidTravelsAsAToken() {
            // A raw EIBAID byte is a code-page-dependent value: X'F7' is PF7 in EBCDIC and '7' in ASCII,
            // so putting the byte itself on the wire would make the payload's meaning depend on the code
            // page of whoever read it. The resolved token does not.
            assertThat(UserListRequest.AID_LENGTH).isEqualTo(PfKeyResolver.AID_TOKEN_LENGTH)
                    .isEqualTo(5);
            assertThat(UserListRequest.AID_FIELD).isEqualTo("EIBAID");

            assertThat(Arrays.stream(UserListRequest.class.getRecordComponents())
                    .filter(component -> "aid".equals(component.getName()))
                    .map(RecordComponent::getType).toList())
                    .as("a String token, never a byte")
                    .containsExactly(String.class);

            UserListRequest request = blankRequest()
                    .withAid(PfKeyResolver.AidKey.ENTER.token());
            assertThat(request.aid()).isEqualTo("ENTER").hasSize(UserListRequest.AID_LENGTH);
        }

        @ParameterizedTest(name = "{0} resolves to the token {1}")
        @CsvSource({"ENTER, ENTER", "PFK03, PFK03", "PFK07, PFK07", "PFK08, PFK08",
            "CLEAR, CLEAR"})
        @DisplayName("every key this screen reacts to has a five-character token the payload can hold")
        void theKeysThisScreenReactsToAllFit(String key, String expectedToken) {
            PfKeyResolver.AidKey aidKey = PfKeyResolver.AidKey.valueOf(key);
            assertThat(aidKey.token()).isEqualTo(expectedToken)
                    .hasSize(PfKeyResolver.AID_TOKEN_LENGTH);
            assertThat(blankRequest().withAid(aidKey.token()).aid()).isEqualTo(expectedToken);
        }

        @Test
        @DisplayName("the four AID bytes COUSR00C names all resolve, and each token fits the member")
        void theFourAidBytesNamedBySourceResolve() {
            // app/cbl/COUSR00C.cbl:122-131 is an EVALUATE EIBAID over DFHENTER, DFHPF3, DFHPF7 and
            // DFHPF8; lines 288 and 342 test the same constants inline. Those four, and no others, are
            // the keys this screen distinguishes - everything else falls to the WHEN OTHER arm at 132.
            assertThat(PfKeyResolver.resolve(CicsAid.DFHENTER))
                    .contains(PfKeyResolver.AidKey.ENTER);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF3)).contains(PfKeyResolver.AidKey.PFK03);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF7)).contains(PfKeyResolver.AidKey.PFK07);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF8)).contains(PfKeyResolver.AidKey.PFK08);

            assertThat(PfKeyResolver.isEnter(CicsAid.DFHENTER)).isTrue();
            assertThat(PfKeyResolver.isPf3(CicsAid.DFHPF3)).isTrue();
            assertThat(PfKeyResolver.isPf7(CicsAid.DFHPF7)).isTrue();
            assertThat(PfKeyResolver.isPf8(CicsAid.DFHPF8)).isTrue();

            for (PfKeyResolver.AidKey key : PfKeyResolver.AidKey.values()) {
                assertThat(key.token())
                        .as("%s must fit the aid member", key.name())
                        .hasSize(UserListRequest.AID_LENGTH);
                assertThat(blankRequest().withAid(key.token()).aid()).isEqualTo(key.token());
            }
        }

        @ParameterizedTest(name = "the high function key at EIBAID {0} still fits the member")
        @ValueSource(ints = {0xC1, 0xC7, 0x4C})
        @DisplayName("a key from the upper twelve folds onto a five-character token too")
        void theUpperFunctionKeysAlsoFitTheMember(int eibAid) {
            // A 3270 keyboard has twenty-four function keys and DFHPF13 through DFHPF24 send their own
            // AID bytes - 0xC1 for PF13, 0xC7 for PF19 and 0x4C for PF24 among them. The resolver folds
            // them back onto PFK01 through PFK12, which is what guarantees that NO key any terminal can
            // send produces a token wider than the five characters this member declares. COUSR00C
            // ignores all twelve - they fall to the WHEN OTHER arm at line 132 and get the invalid-key
            // message - but the payload still has to be able to carry what was pressed in order to say
            // so.
            Optional<PfKeyResolver.AidKey> resolved = PfKeyResolver.resolve((byte) eibAid);
            assertThat(resolved).isPresent();
            assertThat(resolved.orElseThrow().token())
                    .hasSize(UserListRequest.AID_LENGTH)
                    .startsWith("PFK");
            assertThat(blankRequest().withAid(resolved.orElseThrow().token()).aid())
                    .hasSize(UserListRequest.AID_LENGTH);
        }

        @Test
        @DisplayName("the twelve upper keys fold onto the twelve lower tokens, one for one")
        void theUpperTwelveFoldOntoTheLowerTwelve() {
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF13))
                    .as("PF13 reports as PF1, so the token set stays twelve wide")
                    .isEqualTo(PfKeyResolver.resolve(CicsAid.DFHPF1));
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF19))
                    .isEqualTo(PfKeyResolver.resolve(CicsAid.DFHPF7));
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF24))
                    .isEqualTo(PfKeyResolver.resolve(CicsAid.DFHPF12));

            // PF19 and PF7 folding together is worth naming: PF7 is this screen's page-backward key at
            // line 128, so the fold is not cosmetic - it decides which paragraph runs.
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF19))
                    .contains(PfKeyResolver.AidKey.PFK07);
            assertThat(PfKeyResolver.AidKey.values())
                    .as("twelve function-key tokens plus ENTER, CLEAR, PA1 and PA2")
                    .hasSize(16);
        }

        @Test
        @DisplayName("a byte that is no AID at all resolves to nothing, and the payload stays blank")
        void anUnrecognisedAidResolvesToNothing() {
            // The no-match outcome is explicit rather than a default key, because guessing ENTER here
            // would silently run PROCESS-ENTER-KEY for a key the terminal never sent.
            Optional<PfKeyResolver.AidKey> resolved = PfKeyResolver.resolve((byte) 0x00);
            assertThat(resolved).isEmpty();

            String token = resolved.map(PfKeyResolver.AidKey::token)
                    .orElse(" ".repeat(UserListRequest.AID_LENGTH));
            assertThat(blankRequest().withAid(token).aid())
                    .hasSize(UserListRequest.AID_LENGTH)
                    .isBlank();
            assertThat(blankRequest().aid())
                    .as("a blank AID is the first-entry state, before any key has been pressed")
                    .isEqualTo(" ".repeat(UserListRequest.AID_LENGTH));
        }

        @Test
        @DisplayName("an AID token wider than five characters is rejected by name")
        void anOverWideAidTokenIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> blankRequest().withAid("PFK013"))
                    .withMessageContaining(UserListRequest.AID_FIELD);
        }

        @Test
        @DisplayName("both ENTER and REENTER are driven, and they are the only two states")
        void bothContextStatesAreDriven() {
            // app/cpy/COCOM01Y.cpy:29-31 declares CDEMO-PGM-CONTEXT PIC 9(01) with 88 CDEMO-PGM-ENTER
            // VALUE 0 and 88 CDEMO-PGM-REENTER VALUE 1. app/cbl/COUSR00C.cbl:115-117 reads it: on first
            // entry it sets REENTER, blanks the map and paints; on re-entry it receives the map and
            // dispatches on the key. Both paths have to be expressible from the payload alone.
            assertThat(NavigationContext.PGM_CONTEXT_ENTER).isEqualTo(PGM_CONTEXT_ENTER).isZero();
            assertThat(NavigationContext.PGM_CONTEXT_REENTER).isEqualTo(PGM_CONTEXT_REENTER)
                    .isEqualTo(1);

            UserListRequest enter = blankRequest()
                    .withNavigationContext(NavigationContext.empty().withPgmEnter());
            assertThat(enter.navigationContext().isEnter()).isTrue();
            assertThat(enter.navigationContext().isReenter()).isFalse();
            assertThat(enter.navigationContext().pgmContext()).isEqualTo(PGM_CONTEXT_ENTER);

            UserListRequest reenter = blankRequest()
                    .withNavigationContext(NavigationContext.empty().withPgmReenter());
            assertThat(reenter.navigationContext().isReenter()).isTrue();
            assertThat(reenter.navigationContext().isEnter()).isFalse();
            assertThat(reenter.navigationContext().pgmContext()).isEqualTo(PGM_CONTEXT_REENTER);
        }

        @Test
        @DisplayName("the commarea carries the user type PF3 routing depends on")
        void theCommareaCarriesTheUserType() {
            // app/cbl/COUSR00C.cbl:125-127 routes PF3 to COADM01C - this list is reached from the
            // administrator menu - and the user type it was reached with travels in the area rather than
            // being looked up again.
            assertThat(NavigationContext.USER_TYPE_ADMIN).isEqualTo("A");
            assertThat(NavigationContext.USER_TYPE_USER).isEqualTo("U");
            assertThat(PF3_TARGET_PROGRAM).hasSize(UserListRequest.PGMNAME_LENGTH);

            NavigationContext admin = populatedRequest().navigationContext();
            assertThat(admin.isAdmin()).isTrue();
            assertThat(admin.isUser()).isFalse();
            assertThat(admin.fromTranid()).isEqualTo(TRANSACTION_ID);
            assertThat(admin.fromProgram()).isEqualTo(PROGRAM_NAME);
            assertThat(admin.lastMap()).isEqualTo(MAP_NAME);
            assertThat(admin.lastMapset()).isEqualTo(MAPSET_NAME);

            NavigationContext regular = NavigationContext.empty().withUserTypeUser();
            assertThat(regular.isUser()).isTrue();
            assertThat(regular.isAdmin()).isFalse();
        }
    }

    // =================================================================================================
    // 7. THE FIFTY-NINE xxxA REDEFINES xxxF OVERLAYS (gate G34).
    //
    // app/cpy-bms/COUSR00.CPY holds 60 REDEFINES: fifty-nine per-field overlays, each
    //     02 FILLER REDEFINES xxxF.
    //       03 xxxA PICTURE X.
    // and one group-level 01 COUSR0AO REDEFINES COUSR0AI at line 373. The fifty-nine are this file's
    // subject; the group-level one is UserListResponseTest's. This single map supplies the majority of
    // the package's REDEFINES, and the five COBOL programs behind the package contain none at all, so
    // this is where the two-accessors-over-one-span rule is proved.
    // =================================================================================================

    @Nested
    @DisplayName("REDEFINES overlays - fifty-nine attribute views over fifty-nine flag bytes")
    class RedefinesOverlays {

        @Test
        @DisplayName("the geometry tiles 1127 bytes exactly: 12 + 59 x 7 + 702")
        void theGeometryIsTheCopybooks() {
            // Constructing SYMBOLIC_MAP_LAYOUT already proved this - RecordLayout refuses a gap, an
            // unintended overlap and any total other than its declared length - so this case states the
            // arithmetic a reader needs rather than discovering it.
            assertThat(FIELD_PREFIX_LENGTH)
                    .as("xxxL 2 + xxxF 1 + FILLER X(4) = 7, and the output view's FILLER X(3) plus "
                            + "xxxC, xxxP, xxxH and xxxV is also 7, which is what lets COUSR0AO "
                            + "overlay COUSR0AI field for field")
                    .isEqualTo(7);
            assertThat(DECLARED_WIDTHS.stream().mapToInt(Integer::intValue).sum())
                    .isEqualTo(PAYLOAD_WIDTH_TOTAL);
            assertThat(TIOAPFX_PREFIX_LENGTH + DFHMDF_NAMED * FIELD_PREFIX_LENGTH
                    + PAYLOAD_WIDTH_TOTAL).isEqualTo(SYMBOLIC_MAP_LENGTH);
            assertThat(SYMBOLIC_MAP_LAYOUT.recordLength()).isEqualTo(SYMBOLIC_MAP_LENGTH);
            assertThat(SYMBOLIC_MAP_LAYOUT.storageSpans().stream()
                    .mapToInt(FixedWidthRecord.FieldSpan::length).sum())
                    .as("the storage spans, overlays excluded, sum to the record length")
                    .isEqualTo(SYMBOLIC_MAP_LENGTH);
        }

        @Test
        @DisplayName("fifty-nine per-field overlays are modelled; the group-level one is not")
        void onlyThePerFieldOverlaysAreModelled() {
            assertThat(SYMBOLIC_MAP_LAYOUT.redefinitions())
                    .as("one overlay per field, and the 60th - the group-level COUSR0AO - belongs to "
                            + "the response payload")
                    .hasSize(DFHMDF_NAMED);
            assertThat(DFHMDF_NAMED + 1).isEqualTo(COPYBOOK_REDEFINES_TOTAL).isEqualTo(60);
            assertThat(GROUP_REDEFINES_LINE)
                    .as("01 COUSR0AO REDEFINES COUSR0AI, one line after the last xxxI item")
                    .isEqualTo(COPYBOOK_LINES.get(DFHMDF_NAMED - 1) + 1);
            assertThat(SYMBOLIC_MAP_LAYOUT.hasSpan("COUSR0AO")).isFalse();
        }

        @ParameterizedTest(name = "{0}A redefines {0}F")
        @MethodSource("com.vsergeychik.carddemo.user.dto.UserListRequestTest#screenFields")
        @DisplayName("the flag view and the attribute view describe one and the same byte")
        void theTwoViewsShareOneByte(String screenField) {
            FixedWidthRecord.FieldSpan flag =
                    SYMBOLIC_MAP_LAYOUT.span(screenField + FLAG_ITEM_SUFFIX);
            FixedWidthRecord.FieldSpan attribute =
                    SYMBOLIC_MAP_LAYOUT.span(screenField + ATTRIBUTE_ITEM_SUFFIX);

            assertThat(attribute.offset())
                    .as("%sA starts where %sF starts", screenField, screenField)
                    .isEqualTo(flag.offset());
            assertThat(attribute.length())
                    .as("both are PICTURE X - one byte, and not a copy of one byte")
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

            // The overlay sits three copybook lines above the data item and the attribute view two, so
            // each pair's provenance is checkable line by line.
            int itemLine = COPYBOOK_LINES.get(SCREEN_FIELDS.indexOf(screenField));
            assertThat(itemLine + REDEFINES_LINE_OFFSET)
                    .as("02 FILLER REDEFINES %sF", screenField)
                    .isEqualTo(itemLine - 3);
            assertThat(itemLine + ATTRIBUTE_VIEW_LINE_OFFSET)
                    .as("03 %sA PICTURE X", screenField)
                    .isEqualTo(itemLine - 2);
        }

        @ParameterizedTest(name = "{0}: a write through either view is read by the other")
        @MethodSource("com.vsergeychik.carddemo.user.dto.UserListRequestTest#screenFields")
        @DisplayName("each overlay round-trips in both directions and touches nothing else")
        void theOverlayRoundTripsBothWays(String screenField) {
            FixedWidthRecord record = FixedWidthRecord.forLayout(SYMBOLIC_MAP_LAYOUT, MAP_CHARSET);
            FixedWidthRecord.FieldSpan flag =
                    SYMBOLIC_MAP_LAYOUT.span(screenField + FLAG_ITEM_SUFFIX);
            FixedWidthRecord.FieldSpan attribute =
                    SYMBOLIC_MAP_LAYOUT.span(screenField + ATTRIBUTE_ITEM_SUFFIX);
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
        @DisplayName("writing all fifty-nine attributes leaves every data item and filler byte alone")
        void attributeWritesDoNotDisturbTheData() {
            FixedWidthRecord record = FixedWidthRecord.forLayout(SYMBOLIC_MAP_LAYOUT, MAP_CHARSET);
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                record.writeSpan(SYMBOLIC_MAP_LAYOUT.span(SYMBOLIC_MAP_ITEMS.get(index)),
                        "V".repeat(DECLARED_WIDTHS.get(index)));
            }
            for (String screenField : SCREEN_FIELDS) {
                record.writeSpan(SYMBOLIC_MAP_LAYOUT.span(screenField + ATTRIBUTE_ITEM_SUFFIX), "R");
            }
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                assertThat(record.readSpan(SYMBOLIC_MAP_LAYOUT.span(SYMBOLIC_MAP_ITEMS.get(index))))
                        .as("%s must be untouched by the attribute writes",
                                SYMBOLIC_MAP_ITEMS.get(index))
                        .isEqualTo("V".repeat(DECLARED_WIDTHS.get(index)));
                assertThat(record.readSpan(
                        SYMBOLIC_MAP_LAYOUT.span(SCREEN_FIELDS.get(index) + FLAG_ITEM_SUFFIX)))
                        .as("and each flag byte still reads what the overlay wrote")
                        .isEqualTo("R");
            }
            assertThat(record.toByteArray()).hasSize(SYMBOLIC_MAP_LENGTH);
        }

        @Test
        @DisplayName("every data item sits seven bytes after the previous one ends")
        void theDataItemsAreSeparatedByTheirPrefixes() {
            int previousEnd = TIOAPFX_PREFIX_LENGTH;
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                FixedWidthRecord.FieldSpan item =
                        SYMBOLIC_MAP_LAYOUT.span(SYMBOLIC_MAP_ITEMS.get(index));
                assertThat(item.offset())
                        .as("%s starts seven bytes past the end of the previous item",
                                SYMBOLIC_MAP_ITEMS.get(index))
                        .isEqualTo(previousEnd + FIELD_PREFIX_LENGTH);
                assertThat(item.length()).isEqualTo(DECLARED_WIDTHS.get(index));
                previousEnd = item.endOffsetExclusive();
            }
            assertThat(previousEnd)
                    .as("ERRMSGI ends at the end of 01 COUSR0AI")
                    .isEqualTo(SYMBOLIC_MAP_LENGTH);
        }
    }


    // =================================================================================================
    // 8. METADATA IS NOT PAYLOAD.
    //
    // Each field's symbolic-map group holds four items and only one of them is data. xxxL is the input
    // length CICS reports, and doubles as the cursor carrier - COUSR00C moves -1 into USRIDINL at lines
    // 108, 136 and 214 to put the cursor there. xxxF is the flag byte and xxxA its attribute view. None
    // of the three is something a client sends or reads, so none of them is a payload member.
    // =================================================================================================

    @Nested
    @DisplayName("Metadata - the length, flag and attribute items never reach the wire")
    class MetadataIsNotPayload {

        @Test
        @DisplayName("no xxxL, xxxF or xxxA name appears among the record's components")
        void theMetadataItemsAreNotMembers() {
            List<String> components = Arrays.stream(UserListRequest.class.getRecordComponents())
                    .map(RecordComponent::getName).toList();
            for (String screenField : SCREEN_FIELDS) {
                String stem = screenField.toLowerCase(Locale.ROOT);
                assertThat(components)
                        .as("%s%s is metadata, not a member", screenField, LENGTH_ITEM_SUFFIX)
                        .doesNotContain(stem + LENGTH_ITEM_SUFFIX.toLowerCase(Locale.ROOT),
                                stem + FLAG_ITEM_SUFFIX.toLowerCase(Locale.ROOT),
                                stem + ATTRIBUTE_ITEM_SUFFIX.toLowerCase(Locale.ROOT),
                                stem + OUTPUT_ITEM_SUFFIX.toLowerCase(Locale.ROOT));
            }
        }

        @Test
        @DisplayName("no xxxL, xxxF or xxxA name appears in the serialised form either")
        void theMetadataItemsAreNotSerialised() {
            Set<String> wireNames = jsonTreeOf(populatedRequest()).keySet();
            for (String screenField : SCREEN_FIELDS) {
                for (String suffix : List.of(LENGTH_ITEM_SUFFIX, FLAG_ITEM_SUFFIX,
                        ATTRIBUTE_ITEM_SUFFIX, OUTPUT_ITEM_SUFFIX)) {
                    assertThat(wireNames)
                            .doesNotContain(screenField + suffix,
                                    screenField.toLowerCase(Locale.ROOT) + suffix);
                }
            }
            assertThat(wireNames).doesNotContain("USRIDINL", "usrIdInL", "ERRMSGF", "ERRMSGA");
        }

        @Test
        @DisplayName("the TIOAPFX prefix and the per-field fillers are storage, never members")
        void theFillersAreNotMembers() {
            // app/cpy-bms/COUSR00.CPY:18 declares 02 FILLER PIC X(12) - the TIOAPFX=YES prefix - and
            // each field group carries a further 02 FILLER PICTURE X(4). Together that is
            // 12 + 59 x 4 = 248 bytes of reserved storage. They MUST be present in the record image, or
            // every offset after them is wrong, and they must NOT be payload members, because no client
            // has anything to put in them.
            assertThat(TIOAPFX_PREFIX_LENGTH + DFHMDF_NAMED * ATTRIBUTE_FILLER_LENGTH)
                    .isEqualTo(248);

            List<FixedWidthRecord.FieldSpan> fillers = SYMBOLIC_MAP_LAYOUT.storageSpans().stream()
                    .filter(span -> span.kind().filler())
                    .toList();
            assertThat(fillers)
                    .as("the TIOAPFX prefix, and per field one length halfword and one X(4) filler")
                    .hasSize(1 + DFHMDF_NAMED * 2);
            assertThat(fillers.stream().mapToInt(FixedWidthRecord.FieldSpan::length).sum())
                    .as("12 + 59 x 4 filler bytes + 59 x 2 length-item bytes")
                    .isEqualTo(TIOAPFX_PREFIX_LENGTH + DFHMDF_NAMED * ATTRIBUTE_FILLER_LENGTH
                            + DFHMDF_NAMED * LENGTH_ITEM_LENGTH);

            Set<String> wireNames = jsonTreeOf(populatedRequest()).keySet();
            assertThat(wireNames).noneMatch(name -> name.toLowerCase(Locale.ROOT)
                    .contains("filler"));
            assertThat(wireNames).noneMatch(name -> name.toLowerCase(Locale.ROOT)
                    .contains("tioapfx"));
        }

        @Test
        @DisplayName("the row list itself is suppressed, so only the fifty numbered names travel")
        void theRowListIsNotAWireMember() {
            // The ten rows are held internally as one List<UserListRow> because that is what the
            // program's PERFORM VARYING walks, but a client sends and receives fifty individually named
            // fields, because a field name is the only thing tying a payload field back to a screen
            // field.
            Set<String> wireNames = jsonTreeOf(populatedRequest()).keySet();
            assertThat(wireNames).doesNotContain("rows");
            assertThat(wireNames).contains("sel0001", "usrId01", "fname01", "lname01", "utype01",
                    "sel0010", "usrId10", "fname10", "lname10", "utype10");
            assertThat(Arrays.stream(UserListRequest.class.getRecordComponents())
                    .map(RecordComponent::getName).toList())
                    .as("internally it is one component, on the wire it is fifty names")
                    .contains("rows");
        }

        @Test
        @DisplayName("no attribute or colour member exists, because this screen sets none")
        void noAttributeMemberExists() {
            // app/cbl/COUSR00C.cbl:84 includes DFHBMSCA, yet the program references not one of its
            // constants: a search across the whole file finds DFHENTER, DFHPF3, DFHPF7 and DFHPF8 from
            // DFHAID and nothing else. It never copies DFHATTR and never copies CSSETATY either, so it
            // performs no field highlighting at all - unlike COACTUPC, which includes CSSETATY at
            // thirty-nine sites. The include is therefore present and unused, and that is recorded here
            // rather than reconciled (B4): the faithful payload carries no attribute member, and
            // inventing one to match a sibling screen would put a field on the wire that no DFHMDF and
            // no MOVE in this program can account for.
            List<String> names = new ArrayList<>(Arrays.stream(
                            UserListRequest.class.getRecordComponents())
                    .map(RecordComponent::getName).toList());
            Arrays.stream(UserListRow.class.getRecordComponents()).map(RecordComponent::getName)
                    .forEach(names::add);
            names.addAll(jsonTreeOf(populatedRequest()).keySet());

            assertThat(names).noneMatch(name -> {
                String lower = name.toLowerCase(Locale.ROOT);
                return lower.contains("attrib") || lower.contains("colour") || lower.contains("color")
                        || lower.contains("highlight") || lower.contains("dfh");
            });
            assertThat(UserListRequest.mapFieldNames())
                    .as("the fifty-nine field labels are field names, never attribute names")
                    .noneMatch(field -> field.endsWith(ATTRIBUTE_ITEM_SUFFIX + ATTRIBUTE_ITEM_SUFFIX)
                            || field.startsWith("DFH"));
        }

        @Test
        @DisplayName("the derived predicates are not properties, so none of them reaches the wire")
        void theDerivedPredicatesAreNotProperties() {
            // nextPageYes(), nextPageNo(), usrSelUpdate(), usrSelDelete(), hasNavigationContext() and
            // commareaLength() all read a stored member. Emitting them as well would let a payload
            // assert something that contradicts the member it travels with.
            Set<String> wireNames = jsonTreeOf(populatedRequest()).keySet();
            assertThat(wireNames).doesNotContain("nextPageYes", "nextPageNo", "usrSelUpdate",
                    "usrSelDelete", "hasNavigationContext", "commareaLength", "mapFields",
                    "mapFieldNames");
        }
    }

    // =================================================================================================
    // 9. VALIDATION - WIDTH MAXIMA ONLY, NEVER A MANDATORY FIELD.
    //
    // This program answers an invalid selection with a message and repaints: the WHEN OTHER arm at
    // app/cbl/COUSR00C.cbl:210 moves 'Invalid selection. Valid values are U and D' into WS-MESSAGE and
    // sends the screen. A blank or invalid field therefore has to produce a 200 carrying that message,
    // never a 400 - so a @NotBlank anywhere in this payload would replace one observable behaviour with
    // a different one, which a like-for-like migration may not do.
    // =================================================================================================

    @Nested
    @DisplayName("Validation - @Size maxima only, and blank is always valid")
    class ValidationConstraints {

        @Test
        @DisplayName("no member carries @NotBlank, @NotNull, @NotEmpty or @Pattern")
        void noMemberIsMandatory() {
            for (RecordComponent component : UserListRequest.class.getRecordComponents()) {
                for (Annotation annotation : component.getAccessor().getAnnotations()) {
                    assertThat(annotation.annotationType().getName())
                            .as("%s carries a constraint COUSR00C does not perform",
                                    component.getName())
                            .doesNotContain("NotBlank")
                            .doesNotContain("NotNull")
                            .doesNotContain("NotEmpty")
                            .doesNotContain("Pattern")
                            .doesNotContain("Email")
                            .doesNotContain("Positive")
                            .doesNotContain("Min")
                            .doesNotContain("Max");
                }
            }
            for (RecordComponent component : UserListRow.class.getRecordComponents()) {
                for (Annotation annotation : component.getAccessor().getAnnotations()) {
                    assertThat(annotation.annotationType().getName())
                            .as("row member %s must be width-constrained and nothing more",
                                    component.getName())
                            .doesNotContain("NotBlank")
                            .doesNotContain("NotNull")
                            .doesNotContain("Pattern");
                }
            }
        }

        @Test
        @DisplayName("the character members are size-constrained; the int and the commarea are not")
        void theConstraintCensusMatchesTheMembers() {
            int sized = 0;
            for (RecordComponent component : UserListRequest.class.getRecordComponents()) {
                if (component.getAccessor().getAnnotation(Size.class) != null) {
                    sized++;
                }
            }
            assertThat(sized)
                    .as("8 header and paging + rows + errMsg + 5 character CDEMO-CU00-* members + aid")
                    .isEqualTo(16);

            assertThat(Arrays.stream(UserListRequest.class.getRecordComponents())
                    .filter(component -> component.getAccessor().getAnnotation(Size.class) == null)
                    .map(RecordComponent::getName).toList())
                    .as("an int is bounded by its PICTURE clause and the commarea validates itself")
                    .containsExactly("cdemoCu00PageNum", "navigationContext");
        }

        @ParameterizedTest(name = "{0} is @Size(max = {1})")
        @MethodSource(
                "com.vsergeychik.carddemo.user.dto.UserListRequestTest#membersAndWidths")
        @DisplayName("every @Size maximum equals its xxxI PICTURE length")
        void eachSizeMaximumEqualsTheDeclaredWidth(String member, int declaredWidth) {
            // The fifty row members are not record components - they are accessors over the row list -
            // so the constraint lives on the row's own component. Both routes are checked, which is why
            // this case handles the two shapes rather than assuming one.
            int index = MAP_MEMBERS.indexOf(member);
            assertThat(index).isNotNegative();

            Size size = sizeOf(member);
            assertThat(size).as("%s must be width-constrained", member).isNotNull();
            assertThat(size.max())
                    .as("%s is %s PIC X(%d)", member, SYMBOLIC_MAP_ITEMS.get(index), declaredWidth)
                    .isEqualTo(declaredWidth);
        }

        /**
         * The {@code @Size} constraint governing one member, whether it is declared on this record or on
         * the nested row record the member projects.
         */
        private Size sizeOf(String member) {
            for (RecordComponent component : UserListRequest.class.getRecordComponents()) {
                if (component.getName().equals(member)) {
                    return component.getAccessor().getAnnotation(Size.class);
                }
            }
            String rowComponent = rowComponentFor(member);
            for (RecordComponent component : UserListRow.class.getRecordComponents()) {
                if (component.getName().equals(rowComponent)) {
                    return component.getAccessor().getAnnotation(Size.class);
                }
            }
            throw new AssertionError("No member or row component matches " + member);
        }

        /** Maps {@code fname07} onto the row component {@code fname}, and so on for the other four. */
        private String rowComponentFor(String member) {
            if (member.startsWith("sel")) {
                return "sel";
            }
            if (member.startsWith("usrId") && !"usrIdIn".equals(member)) {
                return "usrId";
            }
            if (member.startsWith("fname")) {
                return "fname";
            }
            if (member.startsWith("lname")) {
                return "lname";
            }
            if (member.startsWith("utype")) {
                return "utype";
            }
            return member;
        }

        @Test
        @DisplayName("the row list is constrained to exactly ten, and is cascaded into")
        void theRowListIsConstrainedToTen() {
            RecordComponent rows = Arrays.stream(UserListRequest.class.getRecordComponents())
                    .filter(component -> "rows".equals(component.getName()))
                    .findFirst()
                    .orElseThrow();
            Size size = rows.getAccessor().getAnnotation(Size.class);
            assertThat(size).isNotNull();
            assertThat(size.min()).isEqualTo(UserListRequest.ROW_COUNT);
            assertThat(size.max()).isEqualTo(UserListRequest.ROW_COUNT);

            assertThat(Arrays.stream(rows.getAccessor().getAnnotations())
                    .map(annotation -> annotation.annotationType().getSimpleName()).toList())
                    .as("@Valid cascades into each row, so a row's own widths are validated too")
                    .contains("Valid");
        }

        @Test
        @DisplayName("a wholly blank request is valid, because the program answers blanks with text")
        void aBlankRequestIsValid() {
            assertThat(validate(blankRequest()))
                    .as("a 400 here would replace 'Invalid selection. Valid values are U and D' with "
                            + "a different observable behaviour")
                    .isEmpty();
            assertThat(validate(populatedRequest())).isEmpty();
        }

        @Test
        @DisplayName("a null in every position is valid too, so nothing precedes the program's own test")
        void aNullInEveryPositionIsValid() {
            // app/cbl/COUSR00C.cbl:218, :239 and :262 all test for SPACES OR LOW-VALUES, so the service
            // has to see what the client actually sent. The canonical constructor normalises an absent
            // value to blank rather than rejecting it, which is what keeps those branches reachable.
            UserListRequest allNull = new UserListRequest(null, null, null, null, null, null, null,
                    null, null, null, null, null, UserListRequest.CU00_PAGE_NUM_INITIAL, null, null,
                    null, null, null);
            assertThat(validate(allNull)).isEmpty();
            assertThat(allNull.trnName()).isEqualTo(" ".repeat(UserListRequest.TRNNAME_LENGTH));
            assertThat(allNull.rows()).hasSize(UserListRequest.ROW_COUNT);
            assertThat(allNull.hasNavigationContext())
                    .as("the commarea is the one member that stays absent when it is absent")
                    .isFalse();
        }

        @ParameterizedTest(name = "{0} refuses {1} + 1 characters")
        @MethodSource(
                "com.vsergeychik.carddemo.user.dto.UserListRequestTest#membersAndWidths")
        @DisplayName("one character over the declared width is exactly one violation, on that member")
        void oneCharacterTooManyIsOneViolation(String member, int declaredWidth) {
            // Driven through validateValue rather than by building an instance, and deliberately so:
            // this type's canonical constructor rejects an over-width value outright, so no instance
            // that violates a @Size maximum can exist. That is a stronger guarantee than a violation
            // would be - the value never enters the object graph at all - but it does mean the
            // annotation has to be exercised on its own. The sibling SignOnRequest carries its
            // components verbatim and is therefore tested the other way round; the split is recorded
            // rather than harmonised.
            String overWide = "X".repeat(declaredWidth + 1);
            String exactWidth = "X".repeat(declaredWidth);

            if (isRecordComponent(member)) {
                Set<ConstraintViolation<UserListRequest>> violations =
                        validateValue(member, overWide);
                assertThat(violations).as("%s is a component of this record", member).hasSize(1);
                assertThat(violations.iterator().next().getPropertyPath()).hasToString(member);
                assertThat(validateValue(member, exactWidth))
                        .as("exactly the declared width is accepted, so the boundary is inclusive")
                        .isEmpty();
                assertThat(validateValue(member, null))
                        .as("@Size is satisfied by null, which is what lets it bound a width without "
                                + "making a field mandatory")
                        .isEmpty();
            } else {
                // One of the fifty numbered row members: the constraint is declared on UserListRow.
                String component = rowComponentFor(member);
                Set<ConstraintViolation<UserListRow>> violations =
                        validateRowValue(component, overWide);
                assertThat(violations)
                        .as("%s projects the row component %s", member, component)
                        .hasSize(1);
                assertThat(violations.iterator().next().getPropertyPath()).hasToString(component);
                assertThat(validateRowValue(component, exactWidth)).isEmpty();
                assertThat(validateRowValue(component, null)).isEmpty();
            }

            // Whatever the member's shape, the constructor refuses the over-wide value and names the
            // field and its width in the message.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> assign(member, overWide))
                    .withMessageContaining("PIC X(" + declaredWidth + ")");
        }

        /** Puts a value into one of the fifty-nine members, by the route the type provides. */
        private void assign(String member, String value) {
            int index = MAP_MEMBERS.indexOf(member);
            UserListRequest request = blankRequest();
            switch (member) {
                case "trnName" -> request.withTrnName(value);
                case "title01" -> request.withTitle01(value);
                case "curDate" -> request.withCurDate(value);
                case "pgmName" -> request.withPgmName(value);
                case "title02" -> request.withTitle02(value);
                case "curTime" -> request.withCurTime(value);
                case "pageNum" -> request.withPageNum(value);
                case "usrIdIn" -> request.withUsrIdIn(value);
                case "errMsg" -> request.withErrMsg(value);
                default -> assignRowMember(request, index, value);
            }
        }

        /** Puts a value into one of the fifty numbered row members. */
        private void assignRowMember(UserListRequest request, int index, String value) {
            int rowNumber = (index - UserListRequest.HEADER_FIELD_COUNT)
                    / UserListRequest.ROW_FIELD_COUNT + 1;
            int within = (index - UserListRequest.HEADER_FIELD_COUNT)
                    % UserListRequest.ROW_FIELD_COUNT;
            UserListRow row = request.row(rowNumber);
            switch (within) {
                case 0 -> row.withSel(value);
                case 1 -> row.withUsrId(value);
                case 2 -> row.withFname(value);
                case 3 -> row.withLname(value);
                default -> row.withUtype(value);
            }
        }

        @Test
        @DisplayName("a row list of the wrong size is one violation on rows, and refused outright too")
        void theRowCountIsBothConstrainedAndEnforced() {
            assertThat(validateValue("rows", List.of(UserListRow.blank())))
                    .as("@Size(min = 10, max = 10) on the row list")
                    .hasSize(1);
            assertThat(validateValue("rows", UserListRequest.blankRows())).isEmpty();
            assertThat(validateValue("rows", null))
                    .as("absent is normalised to ten blank rows, so null cannot violate the bound")
                    .isEmpty();
        }

        @Test
        @DisplayName("the aid member is width-constrained at five, like the token it carries")
        void theAidMemberIsConstrained() {
            RecordComponent aid = Arrays.stream(UserListRequest.class.getRecordComponents())
                    .filter(component -> "aid".equals(component.getName()))
                    .findFirst()
                    .orElseThrow();
            assertThat(aid.getAccessor().getAnnotation(Size.class).max())
                    .isEqualTo(UserListRequest.AID_LENGTH);
            assertThat(validateValue("aid", "PFK013")).hasSize(1);
            assertThat(validateValue("aid", PfKeyResolver.AidKey.PFK12.token())).isEmpty();
        }

        @Test
        @DisplayName("the five character CDEMO-CU00-* members are constrained at their own widths")
        void theExtensionMembersAreConstrained() {
            assertThat(validateValue("cdemoCu00UsrIdFirst", "X".repeat(CU00_USRID_FIRST_LENGTH + 1)))
                    .hasSize(1);
            assertThat(validateValue("cdemoCu00UsrIdLast", "X".repeat(CU00_USRID_LAST_LENGTH + 1)))
                    .hasSize(1);
            assertThat(validateValue("cdemoCu00NextPageFlg", "YY")).hasSize(1);
            assertThat(validateValue("cdemoCu00UsrSelFlg", "UU")).hasSize(1);
            assertThat(validateValue("cdemoCu00UsrSelected", "X".repeat(
                    CU00_USR_SELECTED_LENGTH + 1))).hasSize(1);

            assertThat(validateValue("cdemoCu00UsrIdFirst", "X".repeat(CU00_USRID_FIRST_LENGTH)))
                    .isEmpty();
            assertThat(validateValue("cdemoCu00NextPageFlg", NEXT_PAGE_YES_VALUE)).isEmpty();
            assertThat(validateValue("cdemoCu00UsrSelFlg", USR_SEL_DELETE_VALUE)).isEmpty();
        }

        @Test
        @DisplayName("the page number has no annotation, because its PICTURE clause is the bound")
        void thePageNumberIsBoundedWithoutAnAnnotation() {
            RecordComponent pageNum = Arrays.stream(UserListRequest.class.getRecordComponents())
                    .filter(component -> "cdemoCu00PageNum".equals(component.getName()))
                    .findFirst()
                    .orElseThrow();
            assertThat(pageNum.getAccessor().getAnnotations())
                    .as("neither @Min nor @Max: PIC 9(08) is unsigned and eight digits wide, and the "
                            + "constructor enforces exactly that")
                    .isEmpty();
            assertThat(validate(blankRequest().withCdemoCu00PageNum(PAGE_NUM_MAX))).isEmpty();
        }
    }


    // =================================================================================================
    // 10. SERIALISATION.
    //
    // A PIC X(n) value is space-padded to its declared width and is not trimmed on read unless the COBOL
    // trims. The mapper used here is configured as config.WebConfig configures the application's, for
    // exactly that reason: a mapper left at its defaults coerces an empty string to null, and a
    // space-padded field round-trips only if that coercion is off.
    // =================================================================================================

    @Nested
    @DisplayName("Serialisation - sixty-seven untransformed names, and padding that survives")
    class JsonRoundTrip {

        @Test
        @DisplayName("the wire carries exactly the sixty-seven expected names, and no others")
        void theWireNamesAreExactlyTheExpectedOnes() {
            Set<String> wireNames = jsonTreeOf(populatedRequest()).keySet();
            assertThat(wireNames).hasSize(WIRE_MEMBER_COUNT).isEqualTo(EXPECTED_JSON_MEMBERS);
            assertThat(EXPECTED_JSON_MEMBERS).hasSize(WIRE_MEMBER_COUNT);
            assertThat(wireNames).containsAll(MAP_MEMBERS);
        }

        @Test
        @DisplayName("the names are untransformed - camel case, exactly as declared")
        void theNamesAreNotTransformed() {
            // A naming strategy that emitted snake_case would rename all fifty-nine fields at once, and
            // a field-for-field diff would then compare nothing to nothing.
            Set<String> wireNames = jsonTreeOf(populatedRequest()).keySet();
            assertThat(wireNames).contains("trnName", "curDate", "pgmName", "usrIdIn", "pageNum",
                    "errMsg", "cdemoCu00UsrIdFirst", "cdemoCu00NextPageFlg", "navigationContext");
            assertThat(wireNames).doesNotContain("trn_name", "cur_date", "pgm_name", "usr_id_in",
                    "page_num", "err_msg", "cdemo_cu00_usrid_first", "TRNNAME");
        }

        @Test
        @DisplayName("the fifty numbered row names are all present, in copybook order")
        void theFiftyRowNamesAreAllPresent() {
            List<String> wireOrder = List.copyOf(jsonTreeOf(populatedRequest()).keySet());
            assertThat(wireOrder.subList(0, DFHMDF_NAMED - 1))
                    .as("the header and the fifty row names come first, in the copybook's order")
                    .containsExactlyElementsOf(MAP_MEMBERS.subList(0, DFHMDF_NAMED - 1));
            assertThat(wireOrder.get(DFHMDF_NAMED - 1))
                    .as("errMsg closes the map fields, as ERRMSGI closes 01 COUSR0AI")
                    .isEqualTo("errMsg");
            assertThat(wireOrder.subList(DFHMDF_NAMED, WIRE_MEMBER_COUNT))
                    .as("then the CU00 extension, then the conversation")
                    .containsExactly("cdemoCu00UsrIdFirst", "cdemoCu00UsrIdLast", "cdemoCu00PageNum",
                            "cdemoCu00NextPageFlg", "cdemoCu00UsrSelFlg", "cdemoCu00UsrSelected",
                            "navigationContext", "aid");
        }

        @Test
        @DisplayName("a fully populated ten-row request survives the round trip byte for byte")
        void aFullyPopulatedRequestRoundTrips() {
            UserListRequest before = populatedRequest();
            UserListRequest after = deserialise(serialise(before));

            assertThat(after).isEqualTo(before);
            assertThat(after.mapFields())
                    .as("all fifty-nine screen fields, value for value")
                    .isEqualTo(before.mapFields());
            for (int rowNumber = FIRST_SUBSCRIPT; rowNumber <= LAST_SUBSCRIPT; rowNumber++) {
                assertThat(after.row(rowNumber))
                        .as("row %d must survive the projection onto fifty names and back", rowNumber)
                        .isEqualTo(before.row(rowNumber));
            }
            assertThat(after.navigationContext()).isEqualTo(before.navigationContext());
            assertThat(after.cdemoCu00PageNum()).isEqualTo(before.cdemoCu00PageNum());
            assertThat(after.nextPageYes()).isEqualTo(before.nextPageYes());
            assertThat(after.aid()).isEqualTo(before.aid());
        }

        @Test
        @DisplayName("space padding survives, and is neither trimmed nor coerced to null")
        void paddingSurvivesTheRoundTrip() {
            FixedWidthCodec codec = codec();
            UserListRequest before = blankRequest()
                    .withUsrIdIn(codec.movePicX("AB", UserListRequest.USRIDIN_LENGTH))
                    .withErrMsg(codec.movePicX("", UserListRequest.ERRMSG_LENGTH))
                    .withPageNum(codec.movePic9(1L, UserListRequest.PAGENUM_LENGTH));
            UserListRequest after = deserialise(serialise(before));

            assertThat(after.usrIdIn())
                    .as("a two-character id padded to eight stays eight characters")
                    .isEqualTo("AB      ")
                    .hasSize(UserListRequest.USRIDIN_LENGTH);
            assertThat(after.errMsg())
                    .as("seventy-eight spaces are seventy-eight spaces, not an empty string and not "
                            + "null - a default mapper would coerce this one to null")
                    .isEqualTo(" ".repeat(UserListRequest.ERRMSG_LENGTH))
                    .isNotNull()
                    .isNotEmpty();
            assertThat(after.pageNum()).isEqualTo("00000001");
            assertThat(after).isEqualTo(before);
        }

        @Test
        @DisplayName("a blank request round-trips too, so first entry is expressible on the wire")
        void aBlankRequestRoundTrips() {
            UserListRequest before = blankRequest();
            UserListRequest after = deserialise(serialise(before));

            assertThat(after).isEqualTo(before);
            assertThat(after.rows()).hasSize(UserListRequest.ROW_COUNT)
                    .allSatisfy(row -> assertThat(row).isEqualTo(UserListRow.blank()));
            assertThat(after.cdemoCu00NextPageFlg()).isEqualTo(NEXT_PAGE_FLG_DEFAULT);
            assertThat(after.cdemoCu00PageNum()).isEqualTo(UserListRequest.CU00_PAGE_NUM_INITIAL);
        }

        @Test
        @DisplayName("an absent commarea round-trips as absent, not as an empty one")
        void anAbsentCommareaRoundTrips() {
            UserListRequest before = populatedRequest().withoutNavigationContext();
            UserListRequest after = deserialise(serialise(before));

            assertThat(after.hasNavigationContext()).isFalse();
            assertThat(after.navigationContext()).isNull();
            assertThat(after.commareaLength()).isZero();
            assertThat(after).isEqualTo(before);
            assertThat(jsonTreeOf(before))
                    .as("the name is still emitted, carrying null - omitting it would make absent and "
                            + "unset indistinguishable")
                    .containsKey("navigationContext");
        }

        @Test
        @DisplayName("the page number is emitted as a plain integer, never in exponent form")
        void thePageNumberIsEmittedAsAnInteger() {
            String json = serialise(blankRequest().withCdemoCu00PageNum(PAGE_NUM_MAX));
            assertThat(json).contains("\"cdemoCu00PageNum\":" + PAGE_NUM_MAX);
            assertThat(json).doesNotContain("9.9999999E7").doesNotContain("E+");
            assertThat(deserialise(json).cdemoCu00PageNum()).isEqualTo(PAGE_NUM_MAX);
        }

        @Test
        @DisplayName("an over-wide value on the wire is refused during deserialisation, not truncated")
        void anOverWideWireValueIsRefused() {
            // The constructor's rule applies to a wire body as much as to a Java caller, so a client
            // cannot smuggle a 79-character message past the screen's width by sending JSON.
            String json = serialise(blankRequest()).replace("\"trnName\":\"    \"",
                    "\"trnName\":\"TOOLONG\"");
            assertThatExceptionOfType(Exception.class)
                    .isThrownBy(() -> deserialise(json))
                    .withStackTraceContaining(UserListRequest.TRNNAME_FIELD);
        }
    }

    // =================================================================================================
    // 11. SECURITY POSTURE (gate G41).
    //
    // The posture is neither weakened nor strengthened. This screen has no password field at all, and
    // nothing here introduces an encoder, a token or a Spring Security type. USRSEC's own plaintext
    // SEC-USR-PWD X(08) stays where the copybook puts it - on SecUserRecord, unprojected - because
    // hashing it would change observable behaviour and is a decision for the migration's owner, not for
    // a payload type.
    // =================================================================================================

    @Nested
    @DisplayName("Security posture - no password on this screen, and nothing added")
    class SecurityPosture {

        @Test
        @DisplayName("no PASSWD field exists on this map, so none is projected")
        void thereIsNoPasswordField() {
            // app/bms/COUSR00.bms contains no PASSWD label and app/cpy-bms/COUSR00.CPY no PASSWDI item:
            // this screen lists users and never authenticates one. COSGN00 is the only map in the
            // package that carries a password field.
            assertThat(SCREEN_FIELDS).doesNotContain("PASSWD", "PASSWORD", "PWD");
            assertThat(SYMBOLIC_MAP_ITEMS).doesNotContain("PASSWDI", "PASSWORDI");
            assertThat(MAP_MEMBERS).doesNotContain("passwd", "password", "pwd");
            assertThat(UserListRequest.mapFieldNames()).doesNotContain("PASSWD");

            assertThat(Arrays.stream(UserListRequest.class.getRecordComponents())
                    .map(RecordComponent::getName).toList())
                    .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("pass")
                            || name.toLowerCase(Locale.ROOT).contains("pwd")
                            || name.toLowerCase(Locale.ROOT).contains("secret"));
            assertThat(Arrays.stream(UserListRow.class.getRecordComponents())
                    .map(RecordComponent::getName).toList())
                    .as("a row carries the id, the two names and the type - never the password")
                    .containsExactly("sel", "usrId", "fname", "lname", "utype");
            assertThat(jsonTreeOf(populatedRequest()).keySet())
                    .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("pass"));
        }

        @Test
        @DisplayName("the record's own password item is 8 bytes and is simply not projected")
        void theRecordsPasswordIsNotProjected() {
            // SEC-USR-PWD X(08) at app/cpy/CSUSR01Y.cpy:21 is read from USRSEC by every one of the five
            // user programs and painted by none of them. Its width is asserted here so that its absence
            // from the payload is a documented decision rather than an oversight.
            assertThat(SecUserRecord.SEC_USR_PWD_LENGTH).isEqualTo(SEC_USR_PWD_LENGTH).isEqualTo(8);
            assertThat(SecUserRecord.FIELD_SEC_USR_PWD).isEqualTo("SEC-USR-PWD");
            assertThat(UserListRequest.mapFieldNames())
                    .doesNotContain(SecUserRecord.FIELD_SEC_USR_PWD);

            // The row projects four of the record's six items, and the password is not among them.
            SecUserRecord record = SecUserRecord.of("USER0001", "First", "Last", "NOTAREAL",
                    NavigationContext.USER_TYPE_USER, MAP_CHARSET);
            UserListRow row = new UserListRow(" ", record.secUsrId(), record.secUsrFname(),
                    record.secUsrLname(), record.secUsrType());
            assertThat(row.usrId()).isEqualTo(record.secUsrId());
            assertThat(row.fname()).isEqualTo(record.secUsrFname());
            assertThat(row.lname()).isEqualTo(record.secUsrLname());
            assertThat(row.utype()).isEqualTo(record.secUsrType());
            assertThat(List.of(row.sel(), row.usrId(), row.fname(), row.lname(), row.utype()))
                    .doesNotContain(record.secUsrPwd());
        }

        @Test
        @DisplayName("no encoder, token or security type is introduced by this payload")
        void nothingSecurityShapedIsIntroduced() {
            // The migration excludes Spring Security, so introducing an encoder or a token here would
            // both change behaviour and add a dependency outside the closed set.
            List<String> names = new ArrayList<>();
            Arrays.stream(UserListRequest.class.getDeclaredMethods()).map(Method::getName)
                    .forEach(names::add);
            Arrays.stream(UserListRequest.class.getRecordComponents()).map(RecordComponent::getName)
                    .forEach(names::add);
            Arrays.stream(UserListRequest.class.getDeclaredFields())
                    .map(Field::getName).forEach(names::add);

            assertThat(names).noneMatch(name -> {
                String lower = name.toLowerCase(Locale.ROOT);
                return lower.contains("encoder") || lower.contains("bcrypt") || lower.contains("jwt")
                        || lower.contains("token") && !lower.contains("aid")
                        || lower.contains("authenticat") || lower.contains("credential");
            });

            for (RecordComponent component : UserListRequest.class.getRecordComponents()) {
                assertThat(component.getType().getName())
                        .as("%s must not be a Spring Security type", component.getName())
                        .doesNotContain("springframework.security");
            }
        }

        @Test
        @DisplayName("a row's diagnostic form does not reproduce the names it carries")
        void theRowsDiagnosticFormDoesNotLeakNames() {
            // The first and last names on this screen are customer-adjacent personal data, and a value
            // that appears in a log by default is a value nobody chose to log. The id and the type are
            // shown because they are what a paging defect is diagnosed from.
            UserListRow row = UserListRow.blank()
                    .withUsrId("USER0001")
                    .withFname("Reginald")
                    .withLname("Fotheringay")
                    .withUtype(NavigationContext.USER_TYPE_USER);

            assertThat(row.toString())
                    .contains("USER0001")
                    .doesNotContain("Reginald")
                    .doesNotContain("Fotheringay");
            assertThat(row.fname()).startsWith("Reginald");
            assertThat(row.lname()).startsWith("Fotheringay");
        }

        @Test
        @DisplayName("this suite holds no mutable static state of its own")
        void thisSuiteHoldsNoMutableStaticState() {
            // B9, asserted rather than asserted-to: with fifty-nine members the pull toward caching one
            // populated instance in a static field is real, and a shared mutable instance would make one
            // case's outcome depend on another's having run.
            for (Field field : UserListRequestTest.class.getDeclaredFields()) {
                if (field.isSynthetic()) {
                    continue;
                }
                assertThat(Modifier.isStatic(field.getModifiers()))
                        .as("%s must be static", field.getName())
                        .isTrue();
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("%s must be final", field.getName())
                        .isTrue();
            }
        }
    }

}
