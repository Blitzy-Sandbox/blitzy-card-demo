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
 * Unit tests for {@link SignOnRequest} - the inbound payload of {@code POST /api/signon}, CICS
 * transaction {@code CC00}, program {@code app/cbl/COSGN00C.cbl}, map {@code COSGN0A} of mapset
 * {@code COSGN00}.
 *
 * <h2>Project rules</h2>
 *
 * {@code review_rules} reports <strong>"No user rules provided."</strong> - that single line is the
 * whole document. No rule is therefore invented here, and the absence of rules is <em>not</em> treated
 * as licence to assert less: the binding constraints are the enterprise best-practice substitutes
 * {@code B1}-{@code B12} recorded in the plan, each named below with the one thing it requires of this
 * file. The plan holds the full text of each practice; only the ruling is restated.
 *
 * <ul>
 *   <li><strong>B1</strong> - imports are confined to the JDK, JUnit Jupiter, AssertJ,
 *       {@code jakarta.validation} and the Jackson already on the test classpath. No new coordinate,
 *       and nothing from the exclusion list. Mockito is available and deliberately unused: this
 *       payload has no collaborator to stand in for.</li>
 *   <li><strong>B2</strong> - JUnit 5 Jupiter API only, even where a later line is published.</li>
 *   <li><strong>B3</strong> - the reference tree is neither written nor <em>read</em>. Every
 *       expectation below is a {@code private static final} constant carrying the file and line it was
 *       transcribed from, which keeps this suite hermetic and independent of the working directory.
 *       Three sibling suites - {@code SignOnResponseTest}, {@code UserAddRequestTest} and
 *       {@code UserListResponseTest} - instead parse the copybook and mapset at run time by walking up
 *       from the current directory. That difference is recorded, not reconciled: both prove the same
 *       contract, and this file's own ruling is the hermetic one.</li>
 *   <li><strong>B4</strong> - conflicts are documented rather than resolved. Three are relevant here.
 *       <br><em>First:</em> {@code app/cbl/COSGN00C.cbl:57-58} copy {@code DFHAID} and
 *       {@code DFHBMSCA}, but line 59 reads {@code *COPY DFHATTR.} - commented out. The copybook is
 *       <strong>referenced but disabled</strong> in this program. That is recorded and left exactly
 *       as it stands: it is not reconciled with the two programs that do copy it, and no
 *       {@code DFHATTR}-only constant is asserted to be in use on this screen. Consistent with that,
 *       nothing in this file touches {@code common.BmsAttributes} at all.
 *       <br><em>Second:</em> the three declared members that diverge from this file's brief, set out
 *       at the end of these notes.
 *       <br><em>Third:</em> the hermetic-versus-runtime-read split from three sibling suites,
 *       described under B3.</li>
 *   <li><strong>B5</strong> - no member is asserted into or out of existence for symmetry with a
 *       sibling payload. {@link SignOnRequest} publishes no {@code MAP_FIELD_NAMES} list even though
 *       {@link SignOnResponse} and {@link UserAddRequest} do, and this suite asserts the type as
 *       declared rather than nudging it toward its siblings.</li>
 *   <li><strong>B6</strong> - the security posture is neither weakened nor strengthened. The password
 *       stays a plaintext {@code X(8)} member; see {@link SecurityPosture}.</li>
 *   <li><strong>B7</strong> - nothing here reads a wall clock, draws a random value or depends on
 *       another test having run. The one time-derived expectation is driven from
 *       {@link Clock#fixed(Instant, java.time.ZoneId)}.</li>
 *   <li><strong>B8</strong> - every codec call names its {@link Charset} explicitly; no overload that
 *       omits it is used and no platform default is relied on. Every import is written out
 *       individually: there is no wildcard import in this file. No dataset name appears in it
 *       either.</li>
 *   <li><strong>B9</strong> - every field of this class is {@code static final} and immutable. State is
 *       never shared between test methods; JUnit's default per-method lifecycle does the isolating.</li>
 *   <li><strong>B10</strong> - this suite is a deliverable in its own right, shipped in the same phase
 *       as the type it measures rather than added afterwards. That is what makes a drift from the
 *       mapset traceable to the decision that caused it instead of surfacing later as an unexplained
 *       difference.</li>
 *   <li><strong>B11</strong> - fixed-width and truncation work goes through
 *       {@link FixedWidthCodec} and {@link FixedWidthRecord}. No third-party copybook parser is
 *       used, and no assertion substitutes {@link String#substring(int, int)} for a COBOL
 *       {@code MOVE}.</li>
 *   <li><strong>B12</strong> - the environmental limit is stated rather than absorbed. See the
 *       provenance note immediately below.</li>
 * </ul>
 *
 * <h2>Provenance of every expected value (B12)</h2>
 *
 * COBOL cannot be executed in this environment - eight independently verified blockers are recorded in
 * the plan as risk {@code R-A}, among them a disabled indexed-file handler, absent Language
 * Environment services and the absence of any CICS emulator. Every expectation below is therefore
 * <strong>statically derived</strong> by reading the source, not captured from a run. The specific
 * lines used are:
 *
 * <ul>
 *   <li>{@code app/cpy-bms/COSGN00.CPY:17-84} - the group {@code 01 COSGN0AI}, its 12-byte
 *       {@code TIOAPFX} prefix at line 18, the eleven {@code xxxI} items at lines 24, 30, 36, 42, 48,
 *       54, 60, 66, 72, 78 and 84 with the widths this file declares, and the eleven per-field
 *       {@code REDEFINES} overlays at lines 21, 27, 33, 39, 45, 51, 57, 63, 69, 75 and 81. The
 *       group-level {@code 01 COSGN0AO REDEFINES COSGN0AI} at line 85 belongs to
 *       {@code SignOnResponseTest} and is not asserted here.</li>
 *   <li>{@code app/bms/COSGN00.bms} - {@code DFHMSD} at lines 19-25, {@code COSGN0A DFHMDI} at 26-28
 *       with {@code SIZE=(24,80)}, and the eleven name-labelled {@code DFHMDF} definitions at lines
 *       34, 38, 47, 57, 61, 70, 80, 89, 156, 175 and 197 with their {@code LENGTH=} operands.</li>
 *   <li>{@code app/cbl/COSGN00C.cbl} - {@code WS-PGMNAME} at 36, {@code WS-TRANID} at 37,
 *       {@code WS-MESSAGE PIC X(80)} at 38, the commented-out copy at 59, {@code MAP}/{@code MAPSET}
 *       at 111-112, the blank-field chain at 117-130, the narrowing move at 149, the date and time
 *       moves at 190 and 196, the two {@code EXEC CICS ASSIGN} statements at 198-200 and 202-204, and
 *       the plaintext comparison at 223.</li>
 *   <li>{@code app/cpy/COCOM01Y.cpy:19-44} - the 160-byte {@code CARDDEMO-COMMAREA} and its
 *       {@code 88}-level condition names.</li>
 *   <li>{@code app/cpy/CSUSR01Y.cpy:18} and {@code :21} - {@code SEC-USR-ID} and
 *       {@code SEC-USR-PWD}, both {@code PIC X(08)}.</li>
 *   <li>{@code app/csd/CARDDEMO.CSD:378-379} - {@code TRANSACTION(CC00)} bound to
 *       {@code PROGRAM(COSGN00C)}.</li>
 * </ul>
 *
 * <h2>Scope: this is a plain-object suite</h2>
 *
 * No Spring context, no {@code @SpringBootTest}, no {@code @WebMvcTest}, no {@code MockMvc}, no
 * {@code JobLauncher}, and no reference to a controller, service or repository. {@link SignOnRequest}
 * is a value type, so every decision it makes is reachable by construction. The HTTP surface and the
 * sign-on decision belong to {@code user.SignOnControllerTest} and {@code user.SignOnServiceTest};
 * restating them here would be duplication, not thoroughness. Two behaviours in particular are
 * <em>deliberately</em> not asserted here because they are not this type's:
 *
 * <ul>
 *   <li>the {@code FUNCTION UPPER-CASE} normalisation applied to both the identifier and the password
 *       at {@code COSGN00C.cbl:132-137}, which is the service's;</li>
 *   <li>the role routing at {@code COSGN00C.cbl:230-240}, which becomes a field on the
 *       <em>response</em>.</li>
 * </ul>
 *
 * <p>The narrower complement is also true. {@code UserScreenStateContractTest} already owns the
 * cold-start cases - that an absent communication area stays absent, that neither context predicate
 * then holds, and that a present area reports {@code EIBCALEN} 160. This suite is the
 * <strong>field-contract</strong> suite: the eleven-member projection, the widths, the metadata that
 * must never reach the wire, the {@code REDEFINES} overlays, the constraint set and the round trip. It
 * re-drives both context states only because the branch gate requires every {@code 88}-level to be
 * exercised in both directions by an instrument of its own.
 *
 * <h2>Why this package is measured on its own</h2>
 *
 * The coverage gate applies a {@code BRANCH} minimum at package granularity as well as at bundle
 * granularity, so {@code user}, {@code user.model} and {@code user.dto} are three separately measured
 * packages and no one of them can shelter behind another. {@code user.dto} is not branch-free: the
 * constraint set, the two read-through context predicates and the presence predicate all branch, so
 * the package needs instruments that address the payload types directly. That is what this file is.
 *
 * <h2>Three divergences between this file's brief and the declared type (B4)</h2>
 *
 * The declared members are ground truth. Where the brief and the class disagree, the class is asserted
 * as it stands and the disagreement is recorded here rather than edited away:
 *
 * <ol>
 *   <li><strong>The enter/re-enter flag is not a member of its own.</strong> The brief describes it as
 *       a payload member. {@link SignOnRequest} instead reads through to
 *       {@link NavigationContext#isEnter()} and {@link NavigationContext#isReenter()}, so
 *       {@code CDEMO-PGM-CONTEXT} has exactly one home and the two cannot drift apart. The
 *       read-through is what is asserted, in both states, in {@link ConversationState}.</li>
 *   <li><strong>The {@code xxxF} and {@code xxxA} items are not members either</strong>, which is
 *       precisely what {@link MetadataIsNotPayload} proves. The overlay pairs therefore cannot be
 *       round-tripped through the payload, and are instead round-tripped through the storage they
 *       actually describe - a {@link FixedWidthRecord.RecordLayout} built here from the copybook's own
 *       geometry. That is the same property the gate asks for, two typed accessors over one backing
 *       span, asserted against the real byte instead of against an invented member.</li>
 *   <li><strong>No {@code MAP_FIELD_NAMES} list is published.</strong> Its siblings publish one; this
 *       type publishes {@link SignOnRequest#MAP_FIELD_COUNT} only. The asymmetry is preserved.</li>
 * </ol>
 */
@DisplayName("SignOnRequest - the COSGN00 (CC00) sign-on inbound payload")
class SignOnRequestTest {

    // =================================================================================================
    // THE CODE PAGE. Named once, passed explicitly into every codec construction below (B8).
    //
    // US-ASCII, not IBM037: the nine authoritative fixtures under app/data/ASCII are text, and this
    // suite measures widths and offsets rather than reading a dataset. The point of naming it is that
    // no assertion here can quietly acquire the platform default.
    // =================================================================================================

    /** The explicitly named code page for every fixed-width operation in this suite. */
    private static final Charset MAP_CHARSET = StandardCharsets.US_ASCII;

    // =================================================================================================
    // SCREEN IDENTITY. Transcribed literals, each with the line it came from (B3, B12).
    // =================================================================================================

    /** {@code MAP('COSGN0A')}, {@code app/cbl/COSGN00C.cbl:111}; {@code DFHMDI} label, bms line 26. */
    private static final String MAP_NAME = "COSGN0A";

    /** {@code MAPSET('COSGN00')}, {@code app/cbl/COSGN00C.cbl:112}; {@code DFHMSD} label, bms line 19. */
    private static final String MAPSET_NAME = "COSGN00";

    /**
     * {@code WS-TRANID PIC X(04) VALUE 'CC00'}, {@code app/cbl/COSGN00C.cbl:37}, and independently
     * {@code DEFINE TRANSACTION(CC00)} at {@code app/csd/CARDDEMO.CSD:378}.
     */
    private static final String TRANSACTION_ID = "CC00";

    /**
     * {@code WS-PGMNAME PIC X(08) VALUE 'COSGN00C'}, {@code app/cbl/COSGN00C.cbl:36}, and
     * independently {@code PROGRAM(COSGN00C)} at {@code app/csd/CARDDEMO.CSD:379}.
     */
    private static final String PROGRAM_NAME = "COSGN00C";

    /** {@code SIZE=(24,80)} rows, {@code app/bms/COSGN00.bms:28}. */
    private static final int SCREEN_ROWS = 24;

    /** {@code SIZE=(24,80)} columns, {@code app/bms/COSGN00.bms:28}. */
    private static final int SCREEN_COLUMNS = 80;

    /**
     * Every {@code DFHMDF} definition in {@code app/bms/COSGN00.bms}, labelled or not.
     *
     * <p>{@value #DFHMDF_TOTAL} definitions exist; only {@value #DFHMDF_NAMED} carry a name label. The
     * remaining 26 are static screen furniture - the {@code 'Tran :'}, {@code 'Date :'},
     * {@code 'User ID     :'} and {@code '(8 Char)'} captions, the two zero-length positioning fields
     * at {@code POS=(19,52)} and {@code POS=(20,52)}, the dark one-byte field at {@code POS=(20,61)}
     * and the {@code 'ENTER=Sign-on  F3=Exit'} footer - and they are <strong>not</strong> payload
     * members. Stating the split is what stops a reviewer reading an unlabelled literal as a missing
     * field.
     */
    private static final int DFHMDF_TOTAL = 37;

    /** The name-labelled {@code DFHMDF} definitions, which are exactly the payload's map members. */
    private static final int DFHMDF_NAMED = 11;

    // =================================================================================================
    // THE ELEVEN MAP MEMBERS, in the copybook's declaration order. Four parallel lists, one index.
    //
    // They are separate lists rather than one list of tuples so that each can be compared against its
    // own authority: the member names against the record's components, the item names and widths
    // against app/cpy-bms/COSGN00.CPY, and the screen-field labels and widths against the LENGTH=
    // operands of app/bms/COSGN00.bms. The two authorities agree field for field, which is why the
    // widths below are transcribed twice and asserted against both.
    // =================================================================================================

    /** The record component names, in {@code 01 COSGN0AI} declaration order. */
    private static final List<String> MAP_MEMBERS = List.of(
            "trnName", "title01", "curDate", "pgmName", "title02", "curTime",
            "applId", "sysId", "userId", "passwd", "errMsg");

    /** The {@code xxxI} item names, {@code app/cpy-bms/COSGN00.CPY} lines 24 through 84. */
    private static final List<String> SYMBOLIC_MAP_ITEMS = List.of(
            "TRNNAMEI", "TITLE01I", "CURDATEI", "PGMNAMEI", "TITLE02I", "CURTIMEI",
            "APPLIDI", "SYSIDI", "USERIDI", "PASSWDI", "ERRMSGI");

    /** The name-labelled {@code DFHMDF} labels, {@code app/bms/COSGN00.bms} in mapset order. */
    private static final List<String> SCREEN_FIELDS = List.of(
            "TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME",
            "APPLID", "SYSID", "USERID", "PASSWD", "ERRMSG");

    /**
     * The declared widths, from the {@code xxxI} {@code PICTURE} clauses and independently from the
     * {@code LENGTH=} operands: {@code 4, 40, 8, 8, 40, 9, 8, 8, 8, 8, 78}.
     *
     * <p>The sixth entry is <strong>9</strong>, not 8. See {@link WidthTraps}.
     */
    private static final List<Integer> DECLARED_WIDTHS = List.of(4, 40, 8, 8, 40, 9, 8, 8, 8, 8, 78);

    /**
     * The widths the type under test actually publishes, in component order.
     *
     * <p>Read from the class rather than restated, so that comparing this against
     * {@link #DECLARED_WIDTHS} compares the implementation with the copybook instead of comparing two
     * copies of the same typed-in number.
     */
    private static final List<Integer> PUBLISHED_WIDTHS = List.of(
            SignOnRequest.TRNNAME_LENGTH,
            SignOnRequest.TITLE01_LENGTH,
            SignOnRequest.CURDATE_LENGTH,
            SignOnRequest.PGMNAME_LENGTH,
            SignOnRequest.TITLE02_LENGTH,
            SignOnRequest.CURTIME_LENGTH,
            SignOnRequest.APPLID_LENGTH,
            SignOnRequest.SYSID_LENGTH,
            SignOnRequest.USERID_LENGTH,
            SignOnRequest.PASSWD_LENGTH,
            SignOnRequest.ERRMSG_LENGTH);

    /** {@code 05 SEC-USR-ID PIC X(08)}, {@code app/cpy/CSUSR01Y.cpy:18}. */
    private static final int SEC_USR_ID_LENGTH = 8;

    /** {@code 05 SEC-USR-PWD PIC X(08)}, {@code app/cpy/CSUSR01Y.cpy:21} - compared in plaintext. */
    private static final int SEC_USR_PWD_LENGTH = 8;

    /**
     * {@code CURTIME DFHMDF ... INITIAL='Ahh:mm:ss'}, {@code app/bms/COSGN00.bms:74}.
     *
     * <p>Nine characters, which is the mapset's own independent corroboration that
     * {@code CURTIMEI PIC X(9)} is not a copybook typo.
     */
    private static final String CURTIME_INITIAL = "Ahh:mm:ss";

    /** {@code CURDATE DFHMDF ... INITIAL='mm/dd/yy'}, {@code app/bms/COSGN00.bms:51} - eight. */
    private static final String CURDATE_INITIAL = "mm/dd/yy";

    /** The {@code app/cpy-bms/COSGN00.CPY} line declaring each {@code xxxI} item. */
    private static final List<Integer> COPYBOOK_LINES = List.of(24, 30, 36, 42, 48, 54, 60, 66, 72,
            78, 84);

    /** The {@code app/bms/COSGN00.bms} line labelling each {@code DFHMDF}. */
    private static final List<Integer> MAPSET_LINES = List.of(34, 38, 47, 57, 61, 70, 80, 89, 156,
            175, 197);

    /**
     * The per-field {@code REDEFINES} lines in {@code app/cpy-bms/COSGN00.CPY}: 21, 27, 33, 39, 45,
     * 51, 57, 63, 69, 75, 81.
     *
     * <p>The copybook holds twelve {@code REDEFINES} in all. These eleven are the per-field
     * {@code 02 FILLER REDEFINES xxxF} overlays and are this suite's subject; the twelfth, the
     * group-level {@code 01 COSGN0AO REDEFINES COSGN0AI} at line 85, is
     * {@code SignOnResponseTest}'s.
     */
    private static final List<Integer> REDEFINES_LINES = List.of(21, 27, 33, 39, 45, 51, 57, 63, 69,
            75, 81);

    /** The two members with no {@code DFHMDF} behind them, in component order. */
    private static final List<String> STATE_MEMBERS = List.of("navigationContext", "aid");

    /** {@value #COMPONENT_COUNT} components: {@value #DFHMDF_NAMED} map members plus the two above. */
    private static final int COMPONENT_COUNT = 13;

    // =================================================================================================
    // BYTE GEOMETRY OF 01 COSGN0AI.
    //
    // Transcribed from app/cpy-bms/COSGN00.CPY and then re-derived below, so the arithmetic is proved
    // rather than asserted: SYMBOLIC_MAP_LAYOUT is built from these numbers and RecordLayout's own
    // self-check refuses a layout whose spans do not tile its declared length exactly. If any figure
    // here were wrong, the layout would fail to construct and every case in this file would fail.
    //
    // Per field the input view declares xxxL (COMP PIC S9(4), a 2-byte halfword), xxxF (PICTURE X),
    // the 03 xxxA overlay over that same byte, and FILLER PICTURE X(4) - a 7-byte prefix - before the
    // xxxI item itself. The output view's prefix is FILLER X(3) plus xxxC, xxxP, xxxH and xxxV, also 7
    // bytes, which is what lets 01 COSGN0AO REDEFINES COSGN0AI overlay field for field.
    // =================================================================================================

    /** {@code 02 FILLER PIC X(12)}, {@code app/cpy-bms/COSGN00.CPY:18} - the {@code TIOAPFX} prefix. */
    private static final int TIOAPFX_PREFIX_LENGTH = 12;

    /** {@code xxxL COMP PIC S9(4)} - a binary halfword, two bytes. */
    private static final int LENGTH_ITEM_LENGTH = 2;

    /** {@code xxxF PICTURE X} and its {@code 03 xxxA PICTURE X} overlay - one byte, shared. */
    private static final int ATTRIBUTE_ITEM_LENGTH = 1;

    /** {@code 02 FILLER PICTURE X(4)} - the reserved span between {@code xxxF} and {@code xxxI}. */
    private static final int ATTRIBUTE_FILLER_LENGTH = 4;

    /** {@value #FIELD_PREFIX_LENGTH} bytes ahead of every {@code xxxI} item: 2 + 1 + 4. */
    private static final int FIELD_PREFIX_LENGTH =
            LENGTH_ITEM_LENGTH + ATTRIBUTE_ITEM_LENGTH + ATTRIBUTE_FILLER_LENGTH;

    /** {@value #PAYLOAD_WIDTH_TOTAL} bytes of {@code xxxI} data: 4+40+8+8+40+9+8+8+8+8+78. */
    private static final int PAYLOAD_WIDTH_TOTAL = 219;

    /**
     * {@value #SYMBOLIC_MAP_LENGTH} bytes in the group: {@value #TIOAPFX_PREFIX_LENGTH} +
     * {@value #DFHMDF_NAMED} x {@value #FIELD_PREFIX_LENGTH} + {@value #PAYLOAD_WIDTH_TOTAL}.
     *
     * <p>The same figure holds for {@code 01 COSGN0AO}, because its per-field prefix is also seven
     * bytes. The overlay is therefore exact rather than approximate.
     */
    private static final int SYMBOLIC_MAP_LENGTH = 308;

    /**
     * {@code 05 WS-MESSAGE PIC X(80) VALUE SPACES}, {@code app/cbl/COSGN00C.cbl:38}.
     *
     * <p>Two characters wider than the {@code ERRMSGI PIC X(78)} it is moved into at
     * {@code app/cbl/COSGN00C.cbl:149}. See {@link WidthTraps#errMsgNarrowsTheEightyByteMessage()}.
     */
    private static final int WS_MESSAGE_LENGTH = 80;

    /**
     * The copybook's geometry as a validated layout, and the subject of {@link RedefinesOverlays}.
     *
     * <p>Built once from the constants above, in copybook declaration order, declaring
     * <strong>every</strong> byte: the {@code TIOAPFX} prefix, and per field the {@code xxxL}
     * halfword, the {@code xxxF} flag byte, the {@code xxxA} overlay over that byte, the four-byte
     * filler and the {@code xxxI} item. Constructing it is itself the geometry assertion -
     * {@link FixedWidthRecord.RecordLayout} rejects a gap, an unintended overlap, an overlay reaching
     * past declared storage, a repeated referable name, and any total other than
     * {@value #SYMBOLIC_MAP_LENGTH}.
     *
     * <p>The {@code xxxL} halfword is declared as {@code FILLER} rather than under its own name on
     * purpose. It is {@code COMP} - binary - and {@link FixedWidthRecord.PictureKind} deliberately has
     * no binary kind, because no persisted record in this estate holds one; declaring it as character
     * or as zoned {@code DISPLAY} digits would misdescribe two bytes of halfword. It is reserved
     * storage here, its name is asserted from {@link #SYMBOLIC_MAP_ITEMS} instead, and it is in any
     * case never a payload member - which is the very point {@link MetadataIsNotPayload} makes.
     *
     * <p>Immutable: {@link FixedWidthRecord.RecordLayout} is a record over an unmodifiable span list,
     * so publishing it as a constant introduces no shared mutable state (B9). Each test that writes
     * bytes takes its own fresh {@link FixedWidthRecord} from it.
     */
    private static final FixedWidthRecord.RecordLayout SYMBOLIC_MAP_LAYOUT = symbolicMapLayout();

    /**
     * A fixed instant, so the one time-derived expectation in this suite is exact (B7).
     *
     * <p>The value is the timestamp in the version footer of {@code app/cbl/COSGN00C.cbl:259}, which
     * makes it traceable rather than arbitrary. Read through {@link Clock#fixed(Instant,
     * java.time.ZoneId)} at {@link ZoneOffset#UTC}, so {@code CURDATE} renders {@code 07/19/22} and
     * {@code CURTIME} renders {@code 23:12:33} on every run, on every machine, in any order.
     */
    private static final Instant FIXED_INSTANT = Instant.parse("2022-07-19T23:12:33Z");

    /** {@code MM/DD/YY} for {@link #FIXED_INSTANT} - what {@code COSGN00C.cbl:190} would move. */
    private static final String FIXED_CURDATE = "07/19/22";

    /** {@code HH:MM:SS} for {@link #FIXED_INSTANT} - what {@code COSGN00C.cbl:196} would move. */
    private static final String FIXED_CURTIME = "23:12:33";

    /**
     * The complete set of JSON member names this payload may emit: the eleven map members and the two
     * state members, and nothing else.
     */
    private static final Set<String> EXPECTED_JSON_MEMBERS = expectedJsonMembers();

    // =================================================================================================
    // Construction of the constants above. Static, side-effect free and called once each.
    // =================================================================================================

    private static FixedWidthRecord.RecordLayout symbolicMapLayout() {
        List<FixedWidthRecord.FieldSpan> spans = new ArrayList<>();
        // 02 FILLER PIC X(12) - COSGN00.CPY:18.
        spans.add(FixedWidthRecord.FieldSpan.filler(0, TIOAPFX_PREFIX_LENGTH));
        int cursor = TIOAPFX_PREFIX_LENGTH;
        for (int index = 0; index < DFHMDF_NAMED; index++) {
            String field = SCREEN_FIELDS.get(index);
            // 02 xxxL COMP PIC S9(4) - a binary halfword, declared as reserved storage.
            spans.add(FixedWidthRecord.FieldSpan.filler(cursor, LENGTH_ITEM_LENGTH));
            cursor += LENGTH_ITEM_LENGTH;
            // 02 xxxF PICTURE X, and 02 FILLER REDEFINES xxxF / 03 xxxA PICTURE X over the same byte.
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
        // The DECLARED length is the constant, never the cursor this loop happened to reach. Passing
        // the cursor would make the layout self-consistent with whatever the constants above add up to
        // and would catch nothing; passing SYMBOLIC_MAP_LENGTH makes RecordLayout's own self-check
        // compare the transcribed geometry against the transcribed total, so a single wrong constant
        // fails class initialisation instead of quietly shifting every offset after it.
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
     * A codec over the explicitly named code page (B8). A fresh instance per call, because the codec
     * is cheap and sharing one would be shared state for no benefit.
     */
    private static FixedWidthCodec codec() {
        return new FixedWidthCodec(MAP_CHARSET);
    }

    /**
     * An {@link ObjectMapper} configured exactly as {@code config.WebConfig} configures the
     * application's shared one, and for the reasons that class documents.
     *
     * <p>A default mapper would be the wrong instrument here and would make this suite assert the
     * wrong thing. Three settings matter and all three are stated rather than inherited:
     * {@code USE_BIG_DECIMAL_FOR_FLOATS} and {@code WRITE_BIGDECIMAL_AS_PLAIN} are enabled so a
     * scale-2 monetary value could never route through a {@code double} or serialise in exponent
     * notation, and {@code ACCEPT_EMPTY_STRING_AS_NULL_OBJECT} is <em>disabled</em> so an empty or
     * all-spaces {@code PIC X(n)} value stays the real screen data it is instead of becoming
     * {@code null}. No naming strategy is applied, so each property name still traces 1:1 to an
     * {@code xxxI} item; no inclusion filter is applied, so a {@code null} member is emitted rather
     * than dropped; and no trimming converter is registered, so trailing padding survives.
     */
    private static ObjectMapper webConfigEquivalentMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        mapper.configure(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN, true);
        mapper.disable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT);
        return mapper;
    }

    /** Builds a request from the eleven map values in component order, plus the two state members. */
    private static SignOnRequest requestOf(List<String> mapValues, NavigationContext context,
            String aid) {
        return new SignOnRequest(mapValues.get(0), mapValues.get(1), mapValues.get(2),
                mapValues.get(3), mapValues.get(4), mapValues.get(5), mapValues.get(6),
                mapValues.get(7), mapValues.get(8), mapValues.get(9), mapValues.get(10),
                context, aid);
    }

    /** The eleven map members of a request, in component order. Permits {@code null} entries. */
    private static List<String> mapValuesOf(SignOnRequest request) {
        return Arrays.asList(request.trnName(), request.title01(), request.curDate(),
                request.pgmName(), request.title02(), request.curTime(), request.applId(),
                request.sysId(), request.userId(), request.passwd(), request.errMsg());
    }

    /** Eleven empty strings - the payload a client sends having keyed nothing at all. */
    private static List<String> blankMapValues() {
        List<String> values = new ArrayList<>(DFHMDF_NAMED);
        for (int index = 0; index < DFHMDF_NAMED; index++) {
            values.add("");
        }
        return values;
    }

    /** Eleven {@code null}s - distinct from blank, and equally something the payload must carry. */
    private static List<String> nullMapValues() {
        return Arrays.asList(new String[DFHMDF_NAMED]);
    }

    /**
     * A fully populated request: every map member exactly its declared width, so the instance is a
     * faithful image of a painted screen rather than a convenient shorthand.
     */
    private static SignOnRequest populatedRequest() {
        List<String> values = Arrays.asList(
                TRANSACTION_ID,
                ScreenTitles.CCDA_TITLE01,
                FIXED_CURDATE,
                PROGRAM_NAME,
                ScreenTitles.CCDA_TITLE02,
                codec().movePicX(FIXED_CURTIME, SignOnRequest.CURTIME_LENGTH),
                "CICSAWS1",
                "AWS1",
                "ADMIN001",
                "NOTAREAL",
                codec().movePicX("Please enter User ID ...", SignOnRequest.ERRMSG_LENGTH));
        return requestOf(values,
                NavigationContext.empty()
                        .withFromTranid(TRANSACTION_ID)
                        .withFromProgram(PROGRAM_NAME)
                        .withUserId("ADMIN001")
                        .withUserTypeAdmin()
                        .withLastMap(MAP_NAME)
                        .withLastMapset(MAPSET_NAME)
                        .withPgmEnter(),
                PfKeyResolver.AidKey.ENTER.token());
    }

    /** Runs Bean Validation over one instance, closing the factory it opened. */
    private static Set<ConstraintViolation<SignOnRequest>> validate(SignOnRequest request) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(request);
        }
    }

    /** Serialises through the configured mapper, translating the checked failure. */
    private static String serialise(SignOnRequest request) {
        try {
            return webConfigEquivalentMapper().writeValueAsString(request);
        } catch (IOException failure) {
            throw new UncheckedIOException("Serialising a SignOnRequest must not fail", failure);
        }
    }

    /** Deserialises through the configured mapper, translating the checked failure. */
    private static SignOnRequest deserialise(String json) {
        try {
            return webConfigEquivalentMapper().readValue(json, SignOnRequest.class);
        } catch (IOException failure) {
            throw new UncheckedIOException("Deserialising a SignOnRequest must not fail", failure);
        }
    }

    /** The member names actually present in a serialised payload. */
    private static Set<String> jsonMembersOf(SignOnRequest request) {
        try {
            Map<String, Object> tree = webConfigEquivalentMapper()
                    .readValue(serialise(request), new TypeReference<Map<String, Object>>() { });
            return tree.keySet();
        } catch (IOException failure) {
            throw new UncheckedIOException("Reading a serialised SignOnRequest must not fail",
                    failure);
        }
    }

    // =================================================================================================
    // 1. THE PROJECTION OF 01 COSGN0AI.
    //
    // Eleven map members and two state members, in the copybook's own order, each traceable to one
    // name-labelled DFHMDF definition.
    // =================================================================================================

    @Nested
    @DisplayName("Projection of 01 COSGN0AI - eleven map members, in copybook order")
    class MapProjection {

        @Test
        @DisplayName("thirteen components: the eleven map members then the two state members")
        void componentCensus() {
            RecordComponent[] components = SignOnRequest.class.getRecordComponents();
            assertThat(components)
                    .as("eleven name-labelled DFHMDF fields plus navigationContext and aid")
                    .hasSize(COMPONENT_COUNT);

            List<String> names = Arrays.stream(components).map(RecordComponent::getName).toList();
            assertThat(names.subList(0, DFHMDF_NAMED))
                    .as("the map members must appear in 01 COSGN0AI declaration order")
                    .containsExactlyElementsOf(MAP_MEMBERS);
            assertThat(names.subList(DFHMDF_NAMED, COMPONENT_COUNT))
                    .as("the two members with no DFHMDF behind them come last")
                    .containsExactlyElementsOf(STATE_MEMBERS);
        }

        @Test
        @DisplayName("every map member is a String, because every xxxI item is PIC X(n)")
        void everyMapMemberIsCharacter() {
            RecordComponent[] components = SignOnRequest.class.getRecordComponents();
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                assertThat(components[index].getType())
                        .as("%s projects %s, which is PIC X(%d)", MAP_MEMBERS.get(index),
                                SYMBOLIC_MAP_ITEMS.get(index), DECLARED_WIDTHS.get(index))
                        .isEqualTo(String.class);
            }
            assertThat(components[DFHMDF_NAMED].getType())
                    .as("the communication area travels as itself, not as a flattened string")
                    .isEqualTo(NavigationContext.class);
            assertThat(components[DFHMDF_NAMED + 1].getType())
                    .as("the AID token is the five-character CCARD-AID literal")
                    .isEqualTo(String.class);
        }

        @Test
        @DisplayName("MAP_FIELD_COUNT is the mapset's own count, and 26 definitions stay unexposed")
        void countsAreTheMapsetsOwn() {
            assertThat(SignOnRequest.MAP_FIELD_COUNT).isEqualTo(DFHMDF_NAMED);
            assertThat(MAP_MEMBERS).hasSize(SignOnRequest.MAP_FIELD_COUNT);
            assertThat(SYMBOLIC_MAP_ITEMS).hasSize(SignOnRequest.MAP_FIELD_COUNT);
            assertThat(SCREEN_FIELDS).hasSize(SignOnRequest.MAP_FIELD_COUNT);
            assertThat(DECLARED_WIDTHS).hasSize(SignOnRequest.MAP_FIELD_COUNT);
            assertThat(PUBLISHED_WIDTHS).hasSize(SignOnRequest.MAP_FIELD_COUNT);
            assertThat(COPYBOOK_LINES).hasSize(SignOnRequest.MAP_FIELD_COUNT);
            assertThat(MAPSET_LINES).hasSize(SignOnRequest.MAP_FIELD_COUNT);
            assertThat(REDEFINES_LINES).hasSize(SignOnRequest.MAP_FIELD_COUNT);
            assertThat(DFHMDF_TOTAL - DFHMDF_NAMED)
                    .as("the unlabelled DFHMDF definitions are screen furniture, not missing fields")
                    .isEqualTo(26);
        }

        @ParameterizedTest(name = "{0} PIC X({1}) at COSGN00.CPY:{2}")
        @CsvSource({
            "TRNNAMEI,  4, 24",
            "TITLE01I, 40, 30",
            "CURDATEI,  8, 36",
            "PGMNAMEI,  8, 42",
            "TITLE02I, 40, 48",
            "CURTIMEI,  9, 54",
            "APPLIDI,   8, 60",
            "SYSIDI,    8, 66",
            "USERIDI,   8, 72",
            "PASSWDI,   8, 78",
            "ERRMSGI,  78, 84",
        })
        @DisplayName("each published width is the width its xxxI PICTURE clause declares")
        void publishedWidthMatchesTheCopybook(String item, int width, int copybookLine) {
            int index = SYMBOLIC_MAP_ITEMS.indexOf(item);
            assertThat(index).as("%s must be one of the eleven xxxI items", item).isNotNegative();
            assertThat(COPYBOOK_LINES.get(index))
                    .as("%s is declared at COSGN00.CPY:%d", item, copybookLine)
                    .isEqualTo(copybookLine);
            assertThat(DECLARED_WIDTHS.get(index)).isEqualTo(width);
            assertThat(PUBLISHED_WIDTHS.get(index))
                    .as("%s declares PIC X(%d), so %s must publish %d", item, width,
                            MAP_MEMBERS.get(index), width)
                    .isEqualTo(width);
        }

        @ParameterizedTest(name = "{0} LENGTH={1} at COSGN00.bms:{2}")
        @CsvSource({
            "TRNNAME,  4,  34",
            "TITLE01, 40,  38",
            "CURDATE,  8,  47",
            "PGMNAME,  8,  57",
            "TITLE02, 40,  61",
            "CURTIME,  9,  70",
            "APPLID,   8,  80",
            "SYSID,    8,  89",
            "USERID,   8, 156",
            "PASSWD,   8, 175",
            "ERRMSG,  78, 197",
        })
        @DisplayName("the mapset's LENGTH operand agrees independently of the copybook")
        void publishedWidthMatchesTheMapset(String screenField, int length, int mapsetLine) {
            int index = SCREEN_FIELDS.indexOf(screenField);
            assertThat(index).as("%s must be a name-labelled DFHMDF", screenField).isNotNegative();
            assertThat(MAPSET_LINES.get(index))
                    .as("%s is labelled at COSGN00.bms:%d", screenField, mapsetLine)
                    .isEqualTo(mapsetLine);
            assertThat(PUBLISHED_WIDTHS.get(index))
                    .as("two authorities, one width: %s LENGTH=%d", screenField, length)
                    .isEqualTo(length);
        }

        @Test
        @DisplayName("every member traces to one screen field, and its xxxI item is that field plus I")
        void everyMemberTracesToAScreenField() {
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                String screenField = SCREEN_FIELDS.get(index);
                assertThat(SYMBOLIC_MAP_ITEMS.get(index))
                        .as("the symbolic map suffixes the DFHMDF label with I for the input view")
                        .isEqualTo(screenField + "I");
                // Every member name is the DFHMDF label in Java casing - no renaming, no expansion
                // and no abbreviation anywhere in the eleven, so the correspondence stays mechanical.
                assertThat(MAP_MEMBERS.get(index).toUpperCase(Locale.ROOT))
                        .as("%s is the Java spelling of %s", MAP_MEMBERS.get(index), screenField)
                        .isEqualTo(screenField);
            }
        }

        @Test
        @DisplayName("the screen is 24 by 80 and its map and mapset names are seven characters")
        void screenIdentity() {
            assertThat(SCREEN_ROWS).isEqualTo(24);
            assertThat(SCREEN_COLUMNS).isEqualTo(80);
            assertThat(MAP_NAME)
                    .as("CDEMO-LAST-MAP is PIC X(7), and COSGN0A is exactly seven characters")
                    .hasSize(NavigationContext.LAST_MAP_LENGTH);
            assertThat(MAPSET_NAME)
                    .as("CDEMO-LAST-MAPSET is PIC X(7), and COSGN00 is exactly seven characters")
                    .hasSize(NavigationContext.LAST_MAPSET_LENGTH);
            assertThat(TRANSACTION_ID).hasSize(SignOnRequest.TRNNAME_LENGTH);
            assertThat(PROGRAM_NAME).hasSize(SignOnRequest.PGMNAME_LENGTH);
        }

        @Test
        @DisplayName("declares no static mutable state, and publishes counts rather than a name list")
        void declaresNoStaticMutableState() {
            for (Field field : SignOnRequest.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("%s is a static field and must be final", field.getName())
                        .isTrue();
                assertThat(field.getType().isPrimitive() || field.getType() == String.class)
                        .as("%s is a static %s; a collection or array constant would be shared "
                                + "mutable state, and this type publishes MAP_FIELD_COUNT instead "
                                + "of a name list", field.getName(), field.getType().getName())
                        .isTrue();
            }
        }
    }

    // =================================================================================================
    // 2. THE WIDTHS THAT LOOK WRONG AND ARE NOT.
    //
    // Each trap gets its own case, because each is a place a plausible "tidy up" would silently change
    // observable output.
    // =================================================================================================

    @Nested
    @DisplayName("Width traps - nine, not eight; seventy-eight, not eighty")
    class WidthTraps {

        @Test
        @DisplayName("curTime is nine characters, and only this screen's is")
        void curTimeIsNineNotEight() {
            assertThat(SignOnRequest.CURTIME_LENGTH)
                    .as("COSGN00.CPY:54 declares CURTIMEI PIC X(9); COUSR00 through COUSR03 all "
                            + "declare X(8), and harmonising this one to eight would shorten the "
                            + "rendered time on the only screen that shows nine")
                    .isEqualTo(9);
            assertThat(CURTIME_INITIAL)
                    .as("COSGN00.bms:74 corroborates with LENGTH=9 and a nine-character INITIAL")
                    .hasSize(SignOnRequest.CURTIME_LENGTH);
            assertThat(SignOnRequest.CURTIME_LENGTH)
                    .as("nine is one more than the eight of every sibling user screen")
                    .isEqualTo(SignOnRequest.CURDATE_LENGTH + 1);
        }

        @Test
        @DisplayName("the eight-character header time pads on the right into the nine-wide field")
        void theHeaderTimeIsPaddedIntoTheNineWideField() {
            DateHeader header = DateHeader.from(codec(), Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
            assertThat(header.wsCurtimeHhMmSs())
                    .as("WS-CURTIME-HH-MM-SS is eight characters, HH:MM:SS")
                    .isEqualTo(FIXED_CURTIME)
                    .hasSize(DateHeader.WS_CURTIME_HH_MM_SS_LENGTH);

            String moved = codec().movePicX(header.wsCurtimeHhMmSs(), SignOnRequest.CURTIME_LENGTH);
            assertThat(moved)
                    .as("COSGN00C.cbl:196 moves eight characters into a nine-character receiver, so "
                            + "COBOL pads one space on the right")
                    .isEqualTo(FIXED_CURTIME + " ")
                    .hasSize(SignOnRequest.CURTIME_LENGTH);
            assertThat(moved.charAt(SignOnRequest.CURTIME_LENGTH - 1)).isEqualTo(' ');
        }

        @Test
        @DisplayName("the eight-character header date fills curDate exactly, with no padding")
        void theHeaderDateFillsCurDateExactly() {
            DateHeader header = DateHeader.from(codec(), Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
            assertThat(header.wsCurdateMmDdYy()).isEqualTo(FIXED_CURDATE);
            assertThat(CURDATE_INITIAL).hasSize(SignOnRequest.CURDATE_LENGTH);
            assertThat(codec().movePicX(header.wsCurdateMmDdYy(), SignOnRequest.CURDATE_LENGTH))
                    .as("eight into eight is neither padded nor truncated")
                    .isEqualTo(FIXED_CURDATE)
                    .hasSize(SignOnRequest.CURDATE_LENGTH);
        }

        @Test
        @DisplayName("errMsg is 78 while WS-MESSAGE is 80, so the move discards two characters")
        void errMsgNarrowsTheEightyByteMessage() {
            assertThat(WS_MESSAGE_LENGTH)
                    .as("COSGN00C.cbl:38 declares WS-MESSAGE PIC X(80)")
                    .isEqualTo(SignOnRequest.ERRMSG_LENGTH + 2);

            // An 80-character sending field whose last two characters are NOT spaces, so the loss is
            // observable rather than hidden in padding.
            String message = "A".repeat(WS_MESSAGE_LENGTH - 2) + "YZ";
            assertThat(message).hasSize(WS_MESSAGE_LENGTH);

            String moved = codec().movePicX(message, SignOnRequest.ERRMSG_LENGTH);
            assertThat(moved)
                    .as("COSGN00C.cbl:149 moves WS-MESSAGE into a PIC X(78) receiver; a COBOL "
                            + "alphanumeric MOVE fills from the left and discards the overflow")
                    .hasSize(SignOnRequest.ERRMSG_LENGTH)
                    .isEqualTo("A".repeat(WS_MESSAGE_LENGTH - 2))
                    .doesNotContain("Y")
                    .doesNotContain("Z");
        }

        @Test
        @DisplayName("a short message is padded on the right, never left-aligned by accident")
        void errMsgPadsAShortMessage() {
            // COSGN00C.cbl:120 - the message a blank identifier produces.
            String message = "Please enter User ID ...";
            String moved = codec().movePicX(message, SignOnRequest.ERRMSG_LENGTH);
            assertThat(moved).hasSize(SignOnRequest.ERRMSG_LENGTH).startsWith(message);
            assertThat(moved.substring(message.length()))
                    .as("the remainder of a PIC X receiver is spaces, not nulls and not zeros")
                    .isEqualTo(" ".repeat(SignOnRequest.ERRMSG_LENGTH - message.length()));
        }

        @Test
        @DisplayName("the narrowing instrument truncates on the right, which is the COBOL direction")
        void theMoveTruncatesOnTheRight() {
            assertThat(codec().movePicX("ABCDEF", SignOnRequest.TRNNAME_LENGTH))
                    .as("a PIC X receiver keeps the leading characters; keeping the trailing ones "
                            + "would be the numeric MOVE rule and the wrong one here")
                    .isEqualTo("ABCD");
        }

        @Test
        @DisplayName("applId and sysId are eight, and exist on this screen alone")
        void applIdAndSysIdAreEight() {
            assertThat(SignOnRequest.APPLID_LENGTH).isEqualTo(8);
            assertThat(SignOnRequest.SYSID_LENGTH).isEqualTo(8);
            // COSGN00C.cbl:198-200 and :202-204 populate these with two separate EXEC CICS ASSIGN
            // statements, and both write into the OUTPUT view - APPLIDO and SYSIDO of COSGN0AO. On the
            // request side they are therefore echo fields: whatever a previous response carried comes
            // back, and no user keys them. They are declared here because the symbolic map declares
            // them, not because the request needs them filled in.
            SignOnRequest echoed = populatedRequest();
            assertThat(echoed.applId()).hasSize(SignOnRequest.APPLID_LENGTH);
            assertThat(deserialise(serialise(echoed)).applId()).isEqualTo(echoed.applId());
            assertThat(deserialise(serialise(echoed)).sysId()).isEqualTo(echoed.sysId());
        }

        @Test
        @DisplayName("title01 and title02 are forty and carry the screen titles byte for byte")
        void titlesAreFortyCharacters() {
            assertThat(SignOnRequest.TITLE01_LENGTH).isEqualTo(ScreenTitles.TITLE_LENGTH);
            assertThat(SignOnRequest.TITLE02_LENGTH).isEqualTo(ScreenTitles.TITLE_LENGTH);
            assertThat(ScreenTitles.CCDA_TITLE01).hasSize(SignOnRequest.TITLE01_LENGTH);
            assertThat(ScreenTitles.CCDA_TITLE02).hasSize(SignOnRequest.TITLE02_LENGTH);

            SignOnRequest request = populatedRequest();
            assertThat(request.title01())
                    .as("COSGN00C.cbl:181 moves CCDA-TITLE01 into the title field")
                    .isEqualTo(ScreenTitles.CCDA_TITLE01);
            assertThat(request.title02())
                    .as("COSGN00C.cbl:182 moves CCDA-TITLE02")
                    .isEqualTo(ScreenTitles.CCDA_TITLE02);
        }

        @Test
        @DisplayName("the two thank-you literals are different texts of different widths")
        void theThankYouLiteralsAreNotInterchangeable() {
            // A standing trap in this estate: COTTL01Y's CCDA-THANK-YOU is PIC X(40) and names the
            // CCDA application, while CSMSG01Y's CCDA-MSG-THANK-YOU is PIC X(50) and names the
            // CardDemo application. COSGN00C.cbl:89 moves the X(50) one into WS-MESSAGE on PF3.
            // Substituting either for the other changes both the text and the width.
            assertThat(ScreenTitles.CCDA_THANK_YOU).hasSize(ScreenTitles.TITLE_LENGTH);
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU).hasSize(SystemMessages.MESSAGE_LENGTH);
            assertThat(SystemMessages.MESSAGE_LENGTH).isNotEqualTo(ScreenTitles.TITLE_LENGTH);
            assertThat(ScreenTitles.CCDA_THANK_YOU.trim())
                    .isNotEqualTo(SystemMessages.CCDA_MSG_THANK_YOU.trim());
        }

        @Test
        @DisplayName("userId and passwd are the widths of the USRSEC record they are compared against")
        void theCredentialWidthsMatchTheSecurityRecord() {
            assertThat(SignOnRequest.USERID_LENGTH)
                    .as("CSUSR01Y.cpy:18 declares SEC-USR-ID PIC X(08), which is what makes the "
                            + "keyed value usable directly as the USRSEC key at COSGN00C.cbl:215-216")
                    .isEqualTo(SEC_USR_ID_LENGTH);
            assertThat(SignOnRequest.PASSWD_LENGTH)
                    .as("CSUSR01Y.cpy:21 declares SEC-USR-PWD PIC X(08)")
                    .isEqualTo(SEC_USR_PWD_LENGTH);
        }
    }

    // =================================================================================================
    // 3. THE METADATA THAT MUST NOT REACH THE WIRE.
    //
    // The symbolic map wraps every xxxI item in items that describe it rather than carry it. They are
    // validation and highlight metadata and belong to the controller, so none of them may be a payload
    // member: publishing them would let a client assert its own screen attributes and its own reported
    // input length.
    // =================================================================================================

    @Nested
    @DisplayName("Metadata - xxxL, xxxF, xxxA and every FILLER stay off the wire")
    class MetadataIsNotPayload {

        @Test
        @DisplayName("the serialised payload carries exactly the thirteen expected member names")
        void theWireCarriesOnlyTheDeclaredMembers() {
            assertThat(jsonMembersOf(populatedRequest()))
                    .containsExactlyInAnyOrderElementsOf(EXPECTED_JSON_MEMBERS)
                    .hasSize(COMPONENT_COUNT);
        }

        @ParameterizedTest(name = "{0}L, {0}F and {0}A are absent")
        @ValueSource(strings = {"TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME",
            "APPLID", "SYSID", "USERID", "PASSWD", "ERRMSG"})
        @DisplayName("no length, flag or attribute item appears under any spelling")
        void noMetadataItemIsAMember(String screenField) {
            Set<String> members = jsonMembersOf(populatedRequest());
            int index = SCREEN_FIELDS.indexOf(screenField);
            String member = MAP_MEMBERS.get(index);

            for (String suffix : List.of("L", "F", "A")) {
                // Both the copybook spelling and the Java spelling a well-meaning author might reach
                // for, so the case cannot be satisfied by a rename.
                String copybookSpelling = screenField + suffix;
                String javaSpelling = member + suffix;
                assertThat(members)
                        .as("%s is metadata: %s is the reported input length, %s the attribute and "
                                + "flag byte, %s its REDEFINES view", copybookSpelling,
                                screenField + "L", screenField + "F", screenField + "A")
                        .doesNotContain(copybookSpelling, javaSpelling,
                                copybookSpelling.toLowerCase(Locale.ROOT),
                                javaSpelling.toLowerCase(Locale.ROOT));
            }
        }

        @Test
        @DisplayName("neither the TIOAPFX prefix nor any per-field filler is exposed")
        void noFillerIsAMember() {
            Set<String> members = jsonMembersOf(populatedRequest());
            assertThat(members).noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("filler"));
            assertThat(members).noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("tioapfx"));
            assertThat(members).noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("prefix"));
            // The reserved storage is real - 12 bytes at COSGN00.CPY:18 and 4 bytes before every xxxI
            // item - and the layout below emits every one of those bytes. It is simply not payload.
            assertThat(TIOAPFX_PREFIX_LENGTH + DFHMDF_NAMED * ATTRIBUTE_FILLER_LENGTH)
                    .as("56 bytes of declared filler in the group, none of it a member")
                    .isEqualTo(56);
        }

        @Test
        @DisplayName("the derived predicates are withheld too, so a payload cannot contradict itself")
        void theDerivedPredicatesAreWithheld() throws NoSuchMethodException {
            // MOVE -1 TO PASSWDL at COSGN00C.cbl:126 and :244, and MOVE -1 TO USERIDL at :121 and
            // :250, are the cursor-positioning use of an xxxL item. That is exactly why an xxxL may be
            // modelled as metadata but must never be published: a client that could set it would be
            // choosing where the cursor lands. The same reasoning applies to anything derived from a
            // member already on the wire.
            Method presence = SignOnRequest.class.getMethod("hasNavigationContext");
            Method length = SignOnRequest.class.getMethod("commareaLength");
            assertThat(annotationNames(presence))
                    .as("a presence flag beside the member it describes could disagree with it")
                    .anyMatch(name -> name.endsWith("JsonIgnore"));
            assertThat(annotationNames(length))
                    .as("EIBCALEN is derived from the member, not carried alongside it")
                    .anyMatch(name -> name.endsWith("JsonIgnore"));

            Set<String> members = jsonMembersOf(populatedRequest());
            assertThat(members).doesNotContain("hasNavigationContext", "commareaLength",
                    "inEnterState", "inReenterState", "enterState", "reenterState");
        }

        @Test
        @DisplayName("the context predicates are not bean getters, so they never become properties")
        void theContextPredicatesAreNotBeanGetters() throws NoSuchMethodException {
            // inEnterState and inReenterState carry no @JsonIgnore and need none: Jackson only treats
            // an is-prefixed or get-prefixed no-argument method as a property, and neither name is.
            // Emitting them would put properties on the wire that the canonical constructor cannot
            // accept back, which would break the round trip asserted in JsonRoundTrip.
            for (String name : List.of("inEnterState", "inReenterState")) {
                Method predicate = SignOnRequest.class.getMethod(name);
                assertThat(predicate.getReturnType()).isEqualTo(boolean.class);
                assertThat(name).doesNotStartWith("is").doesNotStartWith("get");
            }
        }

        private List<String> annotationNames(Method method) {
            List<String> names = new ArrayList<>();
            for (Annotation annotation : method.getAnnotations()) {
                names.add(annotation.annotationType().getName());
            }
            return names;
        }
    }

    // =================================================================================================
    // 4. THE ELEVEN xxxA REDEFINES xxxF OVERLAYS.
    //
    // COSGN00.CPY declares twelve REDEFINES. Eleven are the per-field overlays at lines 21, 27, 33, 39,
    // 45, 51, 57, 63, 69, 75 and 81, each an 03 xxxA PICTURE X over the 02 xxxF byte; those are this
    // suite's subject. The twelfth is the group-level 01 COSGN0AO REDEFINES COSGN0AI at line 85 and
    // belongs to SignOnResponseTest.
    //
    // Neither xxxF nor xxxA is a payload member - section 3 proves that - so the pair cannot be
    // round-tripped through the DTO. It is round-tripped instead through the storage the copybook
    // actually describes: SYMBOLIC_MAP_LAYOUT, built from the copybook's own geometry. That is the
    // property the gate asks for, two typed accessors over one backing span, asserted against the real
    // byte rather than against a member invented to host it.
    //
    // The five COSGN00 and COUSR0n maps hold all 110 REDEFINES in this subtree and the five programs
    // hold none, so this is the only place in the user tree where the property has a subject at all.
    // =================================================================================================

    @Nested
    @DisplayName("REDEFINES - eleven attribute overlays, each over one shared byte")
    class RedefinesOverlays {

        @Test
        @DisplayName("the layout tiles 308 bytes exactly: 12 + 11 x 7 + 219")
        void theGeometryIsTheCopybooks() {
            // Constructing SYMBOLIC_MAP_LAYOUT already proved this - RecordLayout refuses a gap, an
            // unintended overlap and any total other than its declared length - so this case states
            // the arithmetic a reader needs rather than discovering it.
            assertThat(FIELD_PREFIX_LENGTH)
                    .as("xxxL 2 + xxxF 1 + FILLER X(4) = 7, and the output view's prefix is also 7, "
                            + "which is what lets COSGN0AO overlay COSGN0AI field for field")
                    .isEqualTo(7);
            assertThat(DECLARED_WIDTHS.stream().mapToInt(Integer::intValue).sum())
                    .isEqualTo(PAYLOAD_WIDTH_TOTAL);
            assertThat(TIOAPFX_PREFIX_LENGTH + DFHMDF_NAMED * FIELD_PREFIX_LENGTH
                    + PAYLOAD_WIDTH_TOTAL).isEqualTo(SYMBOLIC_MAP_LENGTH);
            assertThat(SYMBOLIC_MAP_LAYOUT.recordLength()).isEqualTo(SYMBOLIC_MAP_LENGTH);
            assertThat(SYMBOLIC_MAP_LAYOUT.redefinitions())
                    .as("eleven per-field overlays; the group-level one is not modelled here")
                    .hasSize(DFHMDF_NAMED);
            assertThat(SYMBOLIC_MAP_LAYOUT.storageSpans().stream()
                    .mapToInt(FixedWidthRecord.FieldSpan::length).sum())
                    .as("the storage spans, overlays excluded, sum to the record length")
                    .isEqualTo(SYMBOLIC_MAP_LENGTH);
        }

        @ParameterizedTest(name = "{0}A redefines {0}F")
        @ValueSource(strings = {"TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME",
            "APPLID", "SYSID", "USERID", "PASSWD", "ERRMSG"})
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
        }

        @ParameterizedTest(name = "{0}: a write through either view is read by the other")
        @ValueSource(strings = {"TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME",
            "APPLID", "SYSID", "USERID", "PASSWD", "ERRMSG"})
        @DisplayName("the overlay round-trips in both directions, and touches nothing else")
        void theOverlayRoundTripsBothWays(String screenField) {
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
    }

    // =================================================================================================
    // 5. CONVERSATION STATE TRAVELS IN THE PAYLOAD, NEVER IN A SESSION.
    //
    // CICS is pseudo-conversational: COSGN00C ends after painting the screen and is re-entered from the
    // beginning on the next key press, so the only state that survives is what it handed back. The
    // Java form keeps that shape - the communication area and the resolved key indication are payload
    // members - which is what makes the endpoint stateless.
    // =================================================================================================

    @Nested
    @DisplayName("Conversation state - the commarea and the AID travel in the payload")
    class ConversationState {

        @Test
        @DisplayName("the communication area is a payload member, so no session is ever needed")
        void theCommareaIsAPayloadMember() {
            assertThat(jsonMembersOf(populatedRequest())).contains("navigationContext");
            assertThat(populatedRequest().navigationContext()).isNotNull();

            // Nothing that could reintroduce server-side state is reachable from this payload: no
            // servlet session handle, no session-scoped attribute, no thread-local carrier and no
            // framework type at all.
            for (RecordComponent component : SignOnRequest.class.getRecordComponents()) {
                String type = component.getType().getName();
                assertThat(type)
                        .as("%s is typed %s", component.getName(), type)
                        .doesNotContain("jakarta.servlet")
                        .doesNotContain("javax.servlet")
                        .doesNotContain("HttpSession")
                        .doesNotContain("ThreadLocal")
                        .doesNotContain("org.springframework");
            }
        }

        @Test
        @DisplayName("the commarea is exactly 160 bytes, and the sections account for all of them")
        void theCommareaIsOneHundredAndSixtyBytes() {
            assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(160);
            // COCOM01Y.cpy:19-44, section by section.
            assertThat(NavigationContext.GENERAL_INFO_LENGTH)
                    .as("4 + 8 + 4 + 8 + 8 + 1 + 1")
                    .isEqualTo(34);
            assertThat(NavigationContext.CUSTOMER_INFO_LENGTH).as("9 + 25 + 25 + 25").isEqualTo(84);
            assertThat(NavigationContext.ACCOUNT_INFO_LENGTH).as("11 + 1").isEqualTo(12);
            assertThat(NavigationContext.CARD_INFO_LENGTH).isEqualTo(16);
            assertThat(NavigationContext.MORE_INFO_LENGTH).as("7 + 7").isEqualTo(14);
            assertThat(NavigationContext.GENERAL_INFO_LENGTH + NavigationContext.CUSTOMER_INFO_LENGTH
                    + NavigationContext.ACCOUNT_INFO_LENGTH + NavigationContext.CARD_INFO_LENGTH
                    + NavigationContext.MORE_INFO_LENGTH)
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH);

            // Proved through the codec with an explicitly named code page, not by trusting the
            // constant: the image a request's commarea produces is 160 bytes wide.
            byte[] image = populatedRequest().navigationContext().toFixedWidth(codec());
            assertThat(image).hasSize(NavigationContext.COMMAREA_LENGTH);
            assertThat(populatedRequest().commareaLength())
                    .as("EIBCALEN for a request that carries an area")
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH);
        }

        @Test
        @DisplayName("the last map and mapset are seven characters, which the real names corroborate")
        void theMapNamesAreSevenCharacters() {
            assertThat(NavigationContext.LAST_MAP_LENGTH)
                    .as("COCOM01Y.cpy:43 declares CDEMO-LAST-MAP PIC X(7), not X(8)")
                    .isEqualTo(7);
            assertThat(NavigationContext.LAST_MAPSET_LENGTH)
                    .as("COCOM01Y.cpy:44 declares CDEMO-LAST-MAPSET PIC X(7)")
                    .isEqualTo(7);

            NavigationContext context = populatedRequest().navigationContext();
            assertThat(context.lastMap()).isEqualTo(MAP_NAME).hasSize(7);
            assertThat(context.lastMapset()).isEqualTo(MAPSET_NAME).hasSize(7);

            // The area models 160 bytes of storage, so it cannot be widened to hold an eighth
            // character. An over-long value is refused at construction rather than silently shortened,
            // which is what keeps the image a lossless projection.
            assertThatIllegalArgumentException()
                    .as("an eight-character map name has no representation in PIC X(7)")
                    .isThrownBy(() -> NavigationContext.empty().withLastMap("COSGN0AX"));
        }

        @Test
        @DisplayName("the AID arrives already resolved to its five-character token")
        void theAidIsCarriedAsAResolvedToken() {
            assertThat(SignOnRequest.AID_LENGTH)
                    .as("CCARD-AID is PIC X(5), and the resolver produces exactly that width")
                    .isEqualTo(PfKeyResolver.AID_TOKEN_LENGTH)
                    .isEqualTo(5);

            for (PfKeyResolver.AidKey key : PfKeyResolver.AidKey.values()) {
                assertThat(key.token())
                        .as("%s must be a full-width token; a trimmed one would be the wrong width",
                                key.name())
                        .hasSize(SignOnRequest.AID_LENGTH);
            }
            // The two-space padding on PA1 and PA2 is part of the literal, not incidental whitespace.
            assertThat(PfKeyResolver.AidKey.PA1.token()).isEqualTo("PA1  ");
            assertThat(PfKeyResolver.AidKey.PA2.token()).isEqualTo("PA2  ");

            // What travels is the token, never a raw EIBAID byte that the server would have to
            // interpret. COSGN00C.cbl:85-95 evaluates EIBAID inline against DFHENTER and DFHPF3; the
            // resolution happens before the payload is built, so the request states which key was
            // pressed rather than which byte arrived. The two keys that program tests resolve to the
            // tokens below, and it is the tokens the payload carries.
            assertThat(PfKeyResolver.resolve(CicsAid.DFHENTER))
                    .contains(PfKeyResolver.AidKey.ENTER);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF3))
                    .contains(PfKeyResolver.AidKey.PFK03);

            SignOnRequest request = populatedRequest();
            assertThat(request.aid())
                    .isEqualTo(PfKeyResolver.AidKey.ENTER.token())
                    .hasSize(SignOnRequest.AID_LENGTH);
            assertThat(deserialise(serialise(request)).aid()).isEqualTo(request.aid());

            // And no component is byte-typed, so no unresolved EIBAID can reach the server at all.
            for (RecordComponent component : SignOnRequest.class.getRecordComponents()) {
                assertThat(component.getType())
                        .as("%s must not carry a raw AID byte", component.getName())
                        .isNotEqualTo(byte.class)
                        .isNotEqualTo(Byte.class)
                        .isNotEqualTo(byte[].class);
            }
        }

        @ParameterizedTest(name = "context {0}: enter={1}, reenter={2}")
        @CsvSource({
            "0, true,  false",
            "1, false, true",
            "2, false, false",
            "9, false, false",
        })
        @DisplayName("both 88-level states are driven, in both directions, plus a digit that is neither")
        void bothContextStatesAreDriven(int pgmContext, boolean enter, boolean reenter) {
            // COCOM01Y.cpy:30 declares 88 CDEMO-PGM-ENTER VALUE 0 and :31 declares
            // 88 CDEMO-PGM-REENTER VALUE 1. CDEMO-PGM-CONTEXT is PIC 9(01) and may hold any digit, so
            // the two condition names are not complements: for 2 or 9 both are correctly false, and
            // defining either as the other's negation would invent a state the copybook never
            // describes. The highlight behaviour the re-enter state gates belongs to the controller.
            SignOnRequest request = requestOf(blankMapValues(),
                    NavigationContext.empty().withPgmContext(pgmContext), "");
            assertThat(request.inEnterState()).isEqualTo(enter);
            assertThat(request.inReenterState()).isEqualTo(reenter);
            assertThat(request.hasNavigationContext()).isTrue();

            // The flag has exactly one home: the read-through agrees with the area itself.
            assertThat(request.inEnterState())
                    .isEqualTo(request.navigationContext().isEnter());
            assertThat(request.inReenterState())
                    .isEqualTo(request.navigationContext().isReenter());
            assertThat(request.navigationContext().pgmContext()).isEqualTo(pgmContext);
        }

        @Test
        @DisplayName("the named context values are the copybook's, and the enter state survives JSON")
        void theContextStateSurvivesTheRoundTrip() {
            assertThat(NavigationContext.PGM_CONTEXT_ENTER).isEqualTo(0);
            assertThat(NavigationContext.PGM_CONTEXT_REENTER).isEqualTo(1);

            SignOnRequest reentered = requestOf(blankMapValues(),
                    NavigationContext.empty().withPgmReenter(), "");
            SignOnRequest restored = deserialise(serialise(reentered));
            assertThat(restored.inReenterState()).isTrue();
            assertThat(restored.inEnterState()).isFalse();
            assertThat(restored.navigationContext()).isEqualTo(reentered.navigationContext());
        }

        @Test
        @DisplayName("no CDEMO-CU0n-INFO extension block is carried, because COSGN00C declares none")
        void noCustomerInfoExtensionBlockIsCarried() {
            // Verified against the source: `grep -c 'CDEMO-CU0[0-9]-INFO' app/cbl/COSGN00C.cbl`
            // returns 0. Only COUSR00C, COUSR02C and COUSR03C declare such a block, so adding one here
            // would put a field on the wire that the sign-on program never sees. COSGN00C.cbl:100-101
            // passes CARDDEMO-COMMAREA and its own LENGTH OF, with nothing appended.
            assertThat(SignOnRequest.class.getRecordComponents()).hasSize(COMPONENT_COUNT);
            for (RecordComponent component : SignOnRequest.class.getRecordComponents()) {
                assertThat(component.getName().toLowerCase(Locale.ROOT))
                        .as("%s must not be an extension block", component.getName())
                        .doesNotContain("cu00").doesNotContain("cu01").doesNotContain("cu02")
                        .doesNotContain("cu03").doesNotContain("extension");
            }
            assertThat(populatedRequest().commareaLength())
                    .as("EIBCALEN is the commarea's own width, with no extension behind it")
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH);
        }
    }

    // =================================================================================================
    // 6. VALIDATION IS BOUNDED BY WHAT THE PROGRAM DOES.
    //
    // Width constraints only. COSGN00C.cbl:117-130 ACCEPTS a blank field and answers it with a specific
    // message - 'Please enter User ID ...' at :120 and 'Please enter Password ...' at :125 - each arm
    // setting the error flag, positioning the cursor with MOVE -1 TO xxxL and re-sending the screen. A
    // blank field must therefore produce a screen with a message, never a framework rejection, so no
    // presence constraint may exist on any member.
    // =================================================================================================

    @Nested
    @DisplayName("Validation - @Size maxima only, never a presence or format constraint")
    class ValidationConstraints {

        @Test
        @DisplayName("carries twelve @Size constraints and no other constraint at all")
        void carriesOnlySizeConstraints() {
            int sized = 0;
            for (RecordComponent component : SignOnRequest.class.getRecordComponents()) {
                for (Annotation annotation : component.getAccessor().getAnnotations()) {
                    assertThat(annotation.annotationType().getName())
                            .as("%s carries a constraint COSGN00C does not perform",
                                    component.getName())
                            .doesNotContain("NotBlank")
                            .doesNotContain("NotNull")
                            .doesNotContain("NotEmpty")
                            .doesNotContain("Pattern")
                            .doesNotContain("Email");
                    if (annotation instanceof Size) {
                        sized++;
                    }
                }
            }
            assertThat(sized)
                    .as("the eleven screen fields plus the AID token; the commarea validates itself "
                            + "at construction and needs no annotation")
                    .isEqualTo(DFHMDF_NAMED + 1);
        }

        @Test
        @DisplayName("each @Size(max) is the width its symbolic-map item declares")
        void eachSizeMaximumEqualsTheDeclaredWidth() {
            RecordComponent[] components = SignOnRequest.class.getRecordComponents();
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                Size size = components[index].getAccessor().getAnnotation(Size.class);
                assertThat(size)
                        .as("%s must be width-constrained", components[index].getName())
                        .isNotNull();
                assertThat(size.max())
                        .as("%s is %s PIC X(%d)", components[index].getName(),
                                SYMBOLIC_MAP_ITEMS.get(index), DECLARED_WIDTHS.get(index))
                        .isEqualTo(DECLARED_WIDTHS.get(index));
            }
            assertThat(components[DFHMDF_NAMED])
                    .as("the commarea is not size-constrained; it validates every component itself")
                    .extracting(component -> component.getAccessor().getAnnotation(Size.class))
                    .isNull();
            assertThat(components[DFHMDF_NAMED + 1].getAccessor().getAnnotation(Size.class).max())
                    .isEqualTo(SignOnRequest.AID_LENGTH);
        }

        @Test
        @DisplayName("a wholly blank instance is valid, because the program answers blanks with text")
        void aBlankInstanceIsValid() {
            assertThat(validate(requestOf(blankMapValues(), NavigationContext.empty(), "")))
                    .as("a 400 here would replace 'Please enter User ID ...' with a different "
                            + "observable behaviour, which a like-for-like migration may not do")
                    .isEmpty();
        }

        @Test
        @DisplayName("a null in every position is valid too, so nothing precedes the program's own test")
        void aNullInEveryPositionIsValid() {
            // COSGN00C.cbl:118 and :123 test for SPACES OR LOW-VALUES, so the service has to see what
            // the client actually sent. @Size is satisfied by null, which is what lets it constrain
            // width without ever making a field mandatory.
            assertThat(validate(requestOf(nullMapValues(), null, null))).isEmpty();
        }

        @ParameterizedTest(name = "{0} refuses {1} + 1 characters")
        @CsvSource({
            "trnName,  4",
            "title01, 40",
            "curDate,  8",
            "pgmName,  8",
            "title02, 40",
            "curTime,  9",
            "applId,   8",
            "sysId,    8",
            "userId,   8",
            "passwd,   8",
            "errMsg,  78",
        })
        @DisplayName("one character over the declared width is exactly one violation, on that member")
        void oneCharacterTooManyIsOneViolation(String member, int width) {
            int index = MAP_MEMBERS.indexOf(member);
            assertThat(index).as("%s must be one of the eleven map members", member).isNotNegative();
            assertThat(width).isEqualTo(PUBLISHED_WIDTHS.get(index));

            List<String> values = blankMapValues();
            values.set(index, "X".repeat(width + 1));
            Set<ConstraintViolation<SignOnRequest>> violations =
                    validate(requestOf(values, NavigationContext.empty(), ""));

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath()).hasToString(member);

            // And exactly the declared width is accepted, so the boundary is inclusive.
            values.set(index, "X".repeat(width));
            assertThat(validate(requestOf(values, NavigationContext.empty(), ""))).isEmpty();
        }

        @Test
        @DisplayName("an over-wide AID token is refused as well, and two failures report as two")
        void theAidTokenIsConstrainedToo() {
            Set<ConstraintViolation<SignOnRequest>> aidOnly =
                    validate(requestOf(blankMapValues(), NavigationContext.empty(), "ENTER!"));
            assertThat(aidOnly).hasSize(1);
            assertThat(aidOnly.iterator().next().getPropertyPath()).hasToString("aid");

            List<String> values = blankMapValues();
            values.set(MAP_MEMBERS.indexOf("passwd"), "OVERWIDEVALUE");
            assertThat(validate(requestOf(values, NavigationContext.empty(), "ENTER!"))).hasSize(2);
        }
    }

    // =================================================================================================
    // 7. SERIALISATION.
    //
    // A PIC X(n) value is space-padded to its declared width and is not trimmed on read unless the
    // COBOL trims. The mapper used here is configured as the module's web configuration configures the
    // application's, for exactly that reason - see webConfigEquivalentMapper.
    // =================================================================================================

    @Nested
    @DisplayName("Serialisation - padding survives, names are untransformed")
    class JsonRoundTrip {

        @Test
        @DisplayName("the mapper this suite uses carries the three settings the module configures")
        void theMapperMatchesTheModuleConfiguration() {
            ObjectMapper mapper = webConfigEquivalentMapper();
            assertThat(mapper.getDeserializationConfig()
                    .isEnabled(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS))
                    .as("a value derived from a PIC S9(p)V99 clause must never route through a double")
                    .isTrue();
            assertThat(mapper.getFactory()
                    .isEnabled(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN))
                    .as("a scale-2 amount serialises as 100.00, never as 1.0E+2")
                    .isTrue();
            assertThat(mapper.getDeserializationConfig()
                    .isEnabled(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT))
                    .as("an empty or all-spaces PIC X(n) value is real screen data, not an absent one; "
                            + "a default mapper would make this suite assert the wrong thing")
                    .isFalse();
        }

        @Test
        @DisplayName("a space-padded payload survives serialise then deserialise byte for byte")
        void spacePaddingSurvivesTheRoundTrip() {
            List<String> values = blankMapValues();
            // A 40-character title with real trailing spaces, and an errMsg of 78 spaces - the state
            // COSGN00C.cbl:77-78 leaves the message in when there is nothing to report.
            values.set(MAP_MEMBERS.indexOf("title01"), ScreenTitles.CCDA_TITLE01);
            values.set(MAP_MEMBERS.indexOf("errMsg"), " ".repeat(SignOnRequest.ERRMSG_LENGTH));
            values.set(MAP_MEMBERS.indexOf("curTime"),
                    codec().movePicX(FIXED_CURTIME, SignOnRequest.CURTIME_LENGTH));
            SignOnRequest original = requestOf(values, NavigationContext.empty(), "");

            SignOnRequest restored = deserialise(serialise(original));
            assertThat(restored).isEqualTo(original);
            assertThat(restored.title01())
                    .isEqualTo(ScreenTitles.CCDA_TITLE01)
                    .hasSize(SignOnRequest.TITLE01_LENGTH)
                    .endsWith(" ");
            assertThat(restored.errMsg())
                    .as("78 spaces must come back as 78 spaces, neither trimmed nor nulled")
                    .isEqualTo(" ".repeat(SignOnRequest.ERRMSG_LENGTH))
                    .hasSize(SignOnRequest.ERRMSG_LENGTH);
            assertThat(restored.curTime())
                    .hasSize(SignOnRequest.CURTIME_LENGTH)
                    .endsWith(" ");
        }

        @Test
        @DisplayName("an empty value stays empty and is not coerced to absent")
        void anEmptyValueIsNotCoercedToNull() {
            SignOnRequest restored = deserialise(serialise(
                    requestOf(blankMapValues(), NavigationContext.empty(), "")));
            for (String value : mapValuesOf(restored)) {
                assertThat(value).isNotNull().isEmpty();
            }
            assertThat(restored.aid()).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("the member names are the component names, untransformed")
        void theMemberNamesAreUntransformed() {
            String json = serialise(populatedRequest());
            for (String member : MAP_MEMBERS) {
                assertThat(json)
                        .as("no naming strategy may rename %s, or the 1:1 trace to its xxxI item "
                                + "is lost", member)
                        .contains("\"" + member + "\"");
            }
            for (String member : STATE_MEMBERS) {
                assertThat(json).contains("\"" + member + "\"");
            }
            // Neither snake case nor the copybook's upper case appears.
            assertThat(json).doesNotContain("trn_name").doesNotContain("TRNNAMEI")
                    .doesNotContain("cur_time").doesNotContain("err_msg");
        }

        @Test
        @DisplayName("an absent member is emitted rather than dropped, and reads back absent")
        void absentMembersAreEmitted() {
            SignOnRequest nulls = requestOf(nullMapValues(), null, null);
            assertThat(jsonMembersOf(nulls))
                    .as("no inclusion filter may drop a member; a missing name is not the same "
                            + "statement as a null one")
                    .containsExactlyInAnyOrderElementsOf(EXPECTED_JSON_MEMBERS);

            SignOnRequest restored = deserialise(serialise(nulls));
            assertThat(restored).isEqualTo(nulls);
            assertThat(restored.hasNavigationContext()).isFalse();
            assertThat(restored.commareaLength()).isEqualTo(0);
            for (String value : mapValuesOf(restored)) {
                assertThat(value).isNull();
            }
        }
    }

    // =================================================================================================
    // 8. THE SECURITY POSTURE IS UNCHANGED - NEITHER WEAKENED NOR STRENGTHENED (B6).
    //
    // COSGN00C.cbl:223 compares SEC-USR-PWD against WS-USER-PWD directly: `IF SEC-USR-PWD =
    // WS-USER-PWD`. Hashing the value would change observable behaviour and would require a security
    // framework that is out of scope; removing the member would delete a real screen input. Plaintext
    // credentials are an inherited property of the legacy design and an explicit non-goal of this
    // migration, documented here so the characteristic stays visible rather than buried.
    // =================================================================================================

    @Nested
    @DisplayName("Security posture - plaintext as the program compares it, and no more")
    class SecurityPosture {

        @Test
        @DisplayName("passwd is a plaintext String of the width CSUSR01Y declares")
        void thePasswordIsAPlaintextStringOfEight() {
            RecordComponent passwd = SignOnRequest.class.getRecordComponents()[
                    MAP_MEMBERS.indexOf("passwd")];
            assertThat(passwd.getName()).isEqualTo("passwd");
            assertThat(passwd.getType())
                    .as("a String, not a char[], not a wrapper type and not an encoded form")
                    .isEqualTo(String.class);
            assertThat(SignOnRequest.PASSWD_LENGTH).isEqualTo(SEC_USR_PWD_LENGTH);
        }

        @Test
        @DisplayName("an eight-character password survives the round trip byte for byte")
        void anEightCharacterPasswordSurvivesTheRoundTrip() {
            String keyed = "P@d7chr";
            List<String> values = blankMapValues();
            values.set(MAP_MEMBERS.indexOf("passwd"),
                    codec().movePicX(keyed, SignOnRequest.PASSWD_LENGTH));
            SignOnRequest original = requestOf(values, NavigationContext.empty(), "");

            SignOnRequest restored = deserialise(serialise(original));
            assertThat(restored.passwd())
                    .as("the value the program compares must arrive unaltered; any transformation "
                            + "here would change the outcome of COSGN00C.cbl:223")
                    .isEqualTo(original.passwd())
                    .hasSize(SignOnRequest.PASSWD_LENGTH)
                    .startsWith(keyed);
            assertThat(restored).isEqualTo(original);
        }

        @Test
        @DisplayName("the payload does not upper-case, hash or otherwise normalise what it carries")
        void thePayloadIsAPassiveCarrier() {
            // COSGN00C.cbl:132-137 applies FUNCTION UPPER-CASE to both USERIDI and PASSWDI before
            // comparing. That normalisation belongs to the service and is asserted in its own suite;
            // the payload must not anticipate it, or the service could no longer distinguish what was
            // keyed from what was folded.
            List<String> values = blankMapValues();
            values.set(MAP_MEMBERS.indexOf("userId"), "admin001");
            values.set(MAP_MEMBERS.indexOf("passwd"), "lowerpwd");
            SignOnRequest request = requestOf(values, NavigationContext.empty(), "");

            assertThat(request.userId()).isEqualTo("admin001")
                    .isNotEqualTo("admin001".toUpperCase(Locale.ROOT));
            assertThat(request.passwd()).isEqualTo("lowerpwd")
                    .isNotEqualTo("lowerpwd".toUpperCase(Locale.ROOT));
            assertThat(deserialise(serialise(request)).userId()).isEqualTo("admin001");
        }

        @Test
        @DisplayName("no hashing, encoder, token or security framework type is reachable")
        void noSecurityFrameworkTypeIsReachable() {
            List<String> forbidden = List.of("PasswordEncoder", "BCrypt", "MessageDigest", "Hash",
                    "org.springframework.security", "jwt", "Jwt", "Cipher", "SecretKey", "Base64");

            List<String> reachable = new ArrayList<>();
            for (RecordComponent component : SignOnRequest.class.getRecordComponents()) {
                reachable.add(component.getType().getName());
                for (Annotation annotation : component.getAccessor().getAnnotations()) {
                    reachable.add(annotation.annotationType().getName());
                }
            }
            for (Method method : SignOnRequest.class.getDeclaredMethods()) {
                reachable.add(method.getReturnType().getName());
                reachable.add(method.getName());
                for (Class<?> parameter : method.getParameterTypes()) {
                    reachable.add(parameter.getName());
                }
            }

            for (String name : reachable) {
                for (String marker : forbidden) {
                    assertThat(name)
                            .as("%s suggests %s; strengthening the comparison would change "
                                    + "observable behaviour and is out of scope", name, marker)
                            .doesNotContain(marker);
                }
            }
        }

        @Test
        @DisplayName("the DRK attribute on PASSWD is presentation masking, never storage masking")
        void theDarkAttributeIsPresentationOnly() {
            // app/bms/COSGN00.bms:175 declares PASSWD with ATTRB=(DRK,FSET,UNPROT) and
            // INITIAL='________'. DRK is terminal non-display: it stops the 3270 rendering the
            // characters. It says nothing about how the value is stored, transmitted or compared, and
            // mistaking it for hashing would be a security claim the legacy design never made.
            String keyed = "PLAINTXT";
            List<String> values = blankMapValues();
            values.set(MAP_MEMBERS.indexOf("passwd"), keyed);
            SignOnRequest request = requestOf(values, NavigationContext.empty(), "");

            assertThat(request.passwd())
                    .as("the payload carries the keyed characters, unmasked and uncounted")
                    .isEqualTo(keyed);
            assertThat(serialise(request))
                    .as("and they are on the wire, because that is what the program compares")
                    .contains(keyed);
        }

        @Test
        @DisplayName("the diagnostic rendering withholds the password and nothing else")
        void theDiagnosticRenderingWithholdsOnlyThePassword() {
            // Carrying the credential in the clear is required for parity; broadcasting it into a log
            // line, an exception message or a debugger view is not, and the two concerns separate
            // cleanly. Every other component is still reported, because a diagnostic that hid
            // everything would be useless.
            SignOnRequest request = populatedRequest();
            String rendered = request.toString();

            assertThat(rendered).doesNotContain(request.passwd());
            assertThat(rendered).contains("passwd=");
            assertThat(rendered)
                    .contains(request.trnName())
                    .contains(request.pgmName())
                    .contains(request.userId())
                    .contains(request.applId())
                    .contains(request.sysId());
        }

        @Test
        @DisplayName("equals and hashCode still include the password, because they disclose nothing")
        void valueSemanticsIncludeThePassword() {
            List<String> first = blankMapValues();
            first.set(MAP_MEMBERS.indexOf("passwd"), "NOTREAL1");
            List<String> second = blankMapValues();
            second.set(MAP_MEMBERS.indexOf("passwd"), "NOTREAL2");

            SignOnRequest one = requestOf(first, NavigationContext.empty(), "");
            SignOnRequest other = requestOf(second, NavigationContext.empty(), "");
            assertThat(one).isNotEqualTo(other);
            assertThat(one).isEqualTo(requestOf(first, NavigationContext.empty(), ""));
            assertThat(one).hasSameHashCodeAs(requestOf(first, NavigationContext.empty(), ""));
        }
    }
}
