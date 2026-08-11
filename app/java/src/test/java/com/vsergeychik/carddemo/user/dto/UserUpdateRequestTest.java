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
 * Unit tests for {@link UserUpdateRequest} - the inbound payload of
 * {@code PUT /api/users/{userId}}, CICS transaction {@code CU02}, program
 * {@code app/cbl/COUSR02C.cbl}, map {@code COUSR2A} of mapset {@code COUSR02}.
 *
 * <p>Three properties define this screen and separate it from every sibling in the package, and each
 * has an instrument of its own below:
 *
 * <ol>
 *   <li>the identifier is spelled {@code USRIDIN} and is declared <em>before</em> the two name
 *       fields - the exact inverse of {@code COUSR01}, which spells it {@code USERID} and declares
 *       it after them;</li>
 *   <li>the program appends a 34-byte {@code CDEMO-CU02-INFO} group to the communication area,
 *       widening the area it hands back from 160 bytes to 194;</li>
 *   <li>four members, and exactly four, are what the change-detection block compares against the
 *       stored record.</li>
 * </ol>
 *
 * <h2>Project rules</h2>
 *
 * {@code review_rules} reports <strong>"No user rules provided."</strong> - that single line is the
 * whole document, confirmed by reading it to the end. No rule is invented here, and the absence of
 * rules is <em>not</em> treated as licence to assert less. The binding constraints are instead the
 * enterprise best-practice substitutes {@code B1}-{@code B12} recorded in the plan, each named below
 * with the one thing it requires of this file. The plan holds the full text of each practice; only
 * the ruling is restated.
 *
 * <ul>
 *   <li><strong>B1</strong> - imports are confined to the JDK, JUnit Jupiter, AssertJ,
 *       {@code jakarta.validation} and the Jackson already on the test classpath. No new coordinate
 *       and nothing from the plan's exclusion list. Mockito is available and deliberately unused:
 *       this payload has no collaborator to stand in for.</li>
 *   <li><strong>B2</strong> - JUnit 5 Jupiter API only, even where a later line is published.</li>
 *   <li><strong>B3</strong> - the reference tree is neither written nor <em>read</em>. Every
 *       expectation below is a {@code private static final} constant carrying the file and line it
 *       was transcribed from, so this suite is hermetic and cannot be affected by the working
 *       directory a run happens to start in.</li>
 *   <li><strong>B4</strong> - conflicts are documented rather than resolved. Two are inherent to
 *       this screen and are recorded rather than harmonised: the field order and identifier name are
 *       the inverse of {@code COUSR01}'s, and this is the only screen in the package whose
 *       <em>response</em> carries a password as well as its request. A third set - the divergences
 *       between this file's brief and the declared type - is listed at the end of these notes.</li>
 *   <li><strong>B5</strong> - the map-derived member count is exactly twelve. Nothing is asserted
 *       into existence for symmetry with {@code COUSR03}, which has eleven because it declares no
 *       {@code PASSWD} field, and nothing is asserted out of existence either. The paging members of
 *       {@link UserUpdateRequest.Cu02Info} are carried on a screen that does not page, and that
 *       oddity is preserved rather than pruned.</li>
 *   <li><strong>B6</strong> - the security posture is neither weakened nor strengthened. The
 *       password stays a plaintext {@code X(8)} member; see {@link SecurityPosture}.</li>
 *   <li><strong>B7</strong> - nothing here reads a wall clock, draws a random value or depends on
 *       another test having run. The two time-derived expectations are driven from
 *       {@link Clock#fixed(Instant, java.time.ZoneId)}.</li>
 *   <li><strong>B8</strong> - every codec call names its {@link Charset} explicitly; no overload that
 *       omits one is used and no platform default is relied on. Every import is written out
 *       individually - there is no wildcard import in this file - and no dataset name appears in
 *       it.</li>
 *   <li><strong>B9</strong> - every field of this class is {@code static final} and immutable. No
 *       state is shared between test methods; JUnit's default per-method lifecycle does the
 *       isolating.</li>
 *   <li><strong>B10</strong> - this suite ships in the same phase as the type it measures rather
 *       than being added afterwards, which is what makes a drift from the mapset traceable to the
 *       decision that caused it.</li>
 *   <li><strong>B11</strong> - fixed-width and truncation work goes through {@link FixedWidthCodec}
 *       and {@link FixedWidthRecord}. No third-party copybook parser is used, and no assertion
 *       substitutes {@link String#substring(int, int)} for a COBOL {@code MOVE}.</li>
 *   <li><strong>B12</strong> - the environmental limit is stated rather than absorbed. See the
 *       provenance note immediately below.</li>
 * </ul>
 *
 * <h2>Provenance of every expected value (B12)</h2>
 *
 * COBOL cannot be executed in this environment - eight independently verified blockers are recorded
 * in the plan as risk {@code R-A}, among them a disabled indexed-file handler, absent Language
 * Environment services and the absence of any CICS emulator. Every expectation below is therefore
 * <strong>statically derived</strong> by reading the source, never captured from a run. The lines
 * used are:
 *
 * <ul>
 *   <li>{@code app/cpy-bms/COUSR02.CPY:17-90} - the group {@code 01 COUSR2AI}, its 12-byte
 *       {@code TIOAPFX} prefix at line 18, the twelve {@code xxxI} items at lines 24, 30, 36, 42,
 *       48, 54, 60, 66, 72, 78, 84 and 90 with the widths this file declares, and the twelve
 *       per-field {@code REDEFINES} overlays at lines 21, 27, 33, 39, 45, 51, 57, 63, 69, 75, 81 and
 *       87. The group-level {@code 01 COUSR2AO REDEFINES COUSR2AI} at line 91 belongs to
 *       {@code UserUpdateResponseTest} and is not asserted here.</li>
 *   <li>{@code app/bms/COUSR02.bms} - {@code COUSR02 DFHMSD} at line 19, {@code COUSR2A DFHMDI} at
 *       lines 26-28 with {@code SIZE=(24,80)}, and the twelve name-labelled {@code DFHMDF}
 *       definitions at lines 34, 38, 47, 57, 61, 70, 85, 103, 116, 130, 145 and 155 with their
 *       {@code LENGTH=} operands. {@code PASSWD} carries {@code ATTRB=(DRK,FSET,UNPROT)} at line
 *       130.</li>
 *   <li>{@code app/cbl/COUSR02C.cbl} - {@code WS-PGMNAME} at 36, {@code WS-TRANID} at 37,
 *       {@code WS-MESSAGE PIC X(80)} at 38, {@code COPY COCOM01Y} at 49 and the
 *       {@code 05 CDEMO-CU02-INFO} group at 50-58 with its two {@code 88}-levels at 55-56, the
 *       {@code EIBCALEN} guard at 90-94, the entry test at 95, the ordered blank-field chain at
 *       179-213, the key move at 216, the four independent change tests at 219-234, the unmodified
 *       arm at 236-243, the narrowing move at 270, {@code MAP}/{@code MAPSET} at 273-274 and 286-287,
 *       the header moves at 300-315 and {@code INITIALIZE-ALL-FIELDS} at 403-411.</li>
 *   <li>{@code app/cpy/CSUSR01Y.cpy:17-23} - {@code SEC-USER-DATA}, whose five data items are the
 *       widths five members of this payload carry.</li>
 *   <li>{@code app/cpy/COCOM01Y.cpy:19-44} - the 160-byte {@code CARDDEMO-COMMAREA}, its
 *       {@code CDEMO-PGM-CONTEXT PIC 9(01)} at 29 with {@code 88 CDEMO-PGM-ENTER VALUE 0} at 30 and
 *       {@code 88 CDEMO-PGM-REENTER VALUE 1} at 31, and {@code CDEMO-LAST-MAP} and
 *       {@code CDEMO-LAST-MAPSET} at 43-44, both {@code PIC X(7)}.</li>
 *   <li>{@code app/csd/CARDDEMO.CSD:469-470} - {@code DEFINE TRANSACTION(CU02) GROUP(CARDDEMO)}
 *       bound to {@code PROGRAM(COUSR02C)}.</li>
 *   <li>The three sibling mapsets this file contrasts against, read once and inlined:
 *       {@code app/cpy-bms/COUSR01.CPY:60-72} and {@code app/bms/COUSR01.bms:84-111} for the
 *       names-before-identifier order and the {@code USERIDI} spelling,
 *       {@code app/bms/COUSR03.bms:85-140} for the eleven-field {@code USRIDIN} screen with no
 *       password, and {@code app/cpy-bms/COSGN00.CPY:54} for the only {@code CURTIMEI PIC X(9)} in
 *       the application.</li>
 * </ul>
 *
 * <h2>Scope: this is a plain-object suite</h2>
 *
 * No Spring context, no {@code @SpringBootTest}, no {@code @WebMvcTest}, no {@code MockMvc}, no
 * {@code JobLauncher}, and no reference to a controller, service or repository.
 * {@link UserUpdateRequest} is a value type, so every decision it makes is reachable by
 * construction. {@code user.UserUpdateControllerTest} already owns the HTTP projection, the ordered
 * guard chain's message selection, the PF3-saves-before-exit behaviour, the four change-detection
 * outcomes and the {@code readForUpdate}/{@code rewrite} sequence; restating any of them here would
 * be duplication rather than thoroughness. What this file owns is the <strong>field
 * contract</strong>: the twelve-member projection, the widths, the metadata that must never reach
 * the wire, the {@code REDEFINES} overlays, the constraint set, the extension group and the round
 * trip.
 *
 * <h2>Why this package is measured on its own</h2>
 *
 * The coverage gate applies a {@code BRANCH} minimum at package granularity as well as at bundle
 * granularity, so {@code user}, {@code user.model} and {@code user.dto} are three separately
 * measured packages and none of them can shelter behind another. {@code user.dto} is not
 * branch-free: the constraint set, the presence and context predicates, the extension group's
 * normalisation and range checks, and the {@code 'Y'}/{@code 'N'} paging flag all branch. This file
 * is the instrument that addresses those branches directly.
 *
 * <h2>Seven divergences between this file's brief and the declared type (B4)</h2>
 *
 * The declared members are ground truth. Where the brief and the class disagree, the class is
 * asserted as it stands and the disagreement is recorded here rather than edited away:
 *
 * <ol>
 *   <li><strong>Fifteen components, not twelve.</strong> The brief counts the twelve map members.
 *       The record declares those twelve plus {@link UserUpdateRequest#navigationContext()},
 *       {@link UserUpdateRequest#aid()} and {@link UserUpdateRequest#cu02Info()} - the three
 *       explicitly mandated exceptions, which are conversation state rather than screen fields.</li>
 *   <li><strong>The extension member spellings are the COBOL abbreviations.</strong> The brief
 *       names them {@code pageNumber}, {@code nextPageFlag} and {@code usrSelFlag}; the declared
 *       record spells them {@link UserUpdateRequest.Cu02Info#pageNum()},
 *       {@link UserUpdateRequest.Cu02Info#nextPageFlg()} and
 *       {@link UserUpdateRequest.Cu02Info#usrSelFlg()}, matching
 *       {@code CDEMO-CU02-PAGE-NUM}, {@code -NEXT-PAGE-FLG} and {@code -USR-SEL-FLG}. The declared
 *       names are what is asserted.</li>
 *   <li><strong>The enter/re-enter flag is not a member of its own.</strong> The brief describes it
 *       as a payload member. The type instead reads through to {@link NavigationContext#isEnter()}
 *       and {@link NavigationContext#isReenter()}, so {@code CDEMO-PGM-CONTEXT} has exactly one home
 *       and two copies cannot drift apart. The read-through is what is asserted, in both states, in
 *       {@link ConversationState}.</li>
 *   <li><strong>The {@code xxxF} and {@code xxxA} items are not members either</strong>, which is
 *       precisely what {@link MetadataStaysOffTheWire} proves. The twelve overlay pairs are
 *       therefore round-tripped through the storage they actually describe - a
 *       {@link FixedWidthRecord.RecordLayout} built here from the copybook's own geometry - which is
 *       the same property the gate asks for, two typed accessors over one backing span, asserted
 *       against the real byte instead of against an invented member.</li>
 *   <li><strong>{@code UserUpdateRequest.MAP_FIELD_COUNT}'s own note says "fourteen components in
 *       total"</strong> while the record declares fifteen; the extension group was the last
 *       component added. The constant itself, twelve, is correct and is what the contract depends
 *       on. Recorded, not corrected - editing prose in the type under test is outside this file's
 *       scope.</li>
 *   <li><strong>The 80-to-78 narrowing is not performed by this type.</strong> The brief asks for it
 *       to be driven through {@link FixedWidthCodec#movePicX(String, int)}, and that is exactly what
 *       {@link WidthTraps#theEightyByteMessageLosesItsLastTwoCharacters()} does. The type declares
 *       the receiving width only; the move itself belongs to the controller, so that the direction
 *       of the loss stays visible at the one place it happens.</li>
 *   <li><strong>{@link UserUpdateRequest.Cu02Info} publishes no {@code 88}-level predicate.</strong>
 *       The brief speaks of {@code NEXT-PAGE-YES} and {@code NEXT-PAGE-NO} as predicates; the record
 *       publishes the two literals and the caller compares. Both conditions are still driven in both
 *       directions, plus a value that satisfies neither, exactly as the gate requires.</li>
 * </ol>
 */
@DisplayName("UserUpdateRequest - the COUSR02 (CU02) update-user inbound payload")
class UserUpdateRequestTest {

    // =================================================================================================
    // TRANSCRIBED EXPECTATIONS (B3, B12).
    //
    // Every constant below was read off a named line of the reference tree and written out here. None
    // is computed from the type under test, because a constant derived from the subject would agree
    // with it by construction and prove nothing. Nothing in this file opens a file at run time.
    // =================================================================================================

    /**
     * The code page every fixed-width operation in this file names explicitly (B8).
     *
     * <p>{@code US-ASCII} rather than {@code IBM037}, because the authoritative fixtures in
     * {@code app/data/ASCII} are ASCII and because it is the code page
     * {@code UserUpdateRequest.Cu02Info} renders its own items through. Naming it is the point: the
     * platform default is the pitfall the plan calls out by name.
     */
    private static final Charset MAP_CHARSET = StandardCharsets.US_ASCII;

    /** {@code COUSR2A DFHMDI}, {@code app/bms/COUSR02.bms:26}; {@code MAP('COUSR2A')} at cbl 273. */
    private static final String MAP_NAME = "COUSR2A";

    /** {@code COUSR02 DFHMSD}, {@code app/bms/COUSR02.bms:19}; {@code MAPSET('COUSR02')} at cbl 274. */
    private static final String MAPSET_NAME = "COUSR02";

    /** The input view of the symbolic map: {@code 01 COUSR2AI}, {@code COUSR02.CPY:17}. */
    private static final String SYMBOLIC_MAP_INPUT = "COUSR2AI";

    /**
     * The output view: {@code 01 COUSR2AO REDEFINES COUSR2AI}, {@code COUSR02.CPY:91}.
     *
     * <p>Named here only so the group-level redefinition can be asserted <em>absent</em> from this
     * payload. It is {@code UserUpdateResponse}'s projection, not this one's.
     */
    private static final String SYMBOLIC_MAP_OUTPUT = "COUSR2AO";

    /** {@code WS-TRANID PIC X(04) VALUE 'CU02'}, {@code COUSR02C.cbl:37}; CSD line 469. */
    private static final String TRANSACTION_ID = "CU02";

    /** {@code WS-PGMNAME PIC X(08) VALUE 'COUSR02C'}, {@code COUSR02C.cbl:36}; CSD line 470. */
    private static final String PROGRAM_NAME = "COUSR02C";

    /** {@code SIZE=(24,80)} rows, {@code app/bms/COUSR02.bms:28}. */
    private static final int SCREEN_ROWS = 24;

    /** {@code SIZE=(24,80)} columns, {@code app/bms/COUSR02.bms:28}. */
    private static final int SCREEN_COLUMNS = 80;

    /**
     * Every {@code DFHMDF} definition in the mapset: <strong>twenty-nine</strong>.
     *
     * <p>Seventeen of them carry no name and therefore have no symbolic-map item at all - they are
     * the literal furniture {@code 'Tran:'}, {@code 'Date:'}, {@code 'Prog:'}, {@code 'Time:'},
     * {@code 'Update User'}, {@code 'Enter User ID:'}, the rule of asterisks, {@code 'First Name:'},
     * {@code 'Last Name:'}, {@code 'Password:'}, {@code '(8 Char)'}, {@code 'User Type: '},
     * {@code '(A=Admin, U=User)'}, the three zero-length skip stops and the key legend. A definition
     * with no name cannot be sent or received, so none of the seventeen is a payload member.
     */
    private static final int DFHMDF_TOTAL = 29;

    /** The name-labelled {@code DFHMDF} definitions, and so the payload members: twelve. */
    private static final int DFHMDF_NAMED = 12;

    /**
     * The twelve Java member names in the map's own declaration order.
     *
     * <p>Note position seven. {@code usrIdIn} precedes {@code fName} and {@code lName}; on
     * {@code COUSR01} the identifier is ninth, after both. This list follows this mapset.
     */
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
        return members.stream().map(UserUpdateRequestTest::wireNameOf).toList();
    }

    /** The twelve {@code xxxI} items of {@code 01 COUSR2AI}, in declaration order. */
    private static final List<String> SYMBOLIC_MAP_ITEMS = List.of("TRNNAMEI",
            "TITLE01I",
            "CURDATEI",
            "PGMNAMEI",
            "TITLE02I",
            "CURTIMEI",
            "USRIDINI",
            "FNAMEI",
            "LNAMEI",
            "PASSWDI",
            "USRTYPEI",
            "ERRMSGI");

    /** The twelve name-labelled {@code DFHMDF} names - each {@code xxxI} item less its suffix. */
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
     * The twelve widths, transcribed from the {@code xxxI} {@code PICTURE} clauses.
     *
     * <p>{@code CURTIME} is eight here, not the nine of {@code COSGN00}, and {@code ERRMSG} is
     * seventy-eight although the message that fills it is eighty.
     */
    private static final List<Integer> DECLARED_WIDTHS =
            List.of(4, 40, 8, 8, 40, 8, 8, 20, 20, 8, 1, 78);

    /** The same twelve widths as the type publishes them, for a two-source comparison. */
    private static final List<Integer> PUBLISHED_WIDTHS = List.of(UserUpdateRequest.TRNNAME_LENGTH,
            UserUpdateRequest.TITLE01_LENGTH,
            UserUpdateRequest.CURDATE_LENGTH,
            UserUpdateRequest.PGMNAME_LENGTH,
            UserUpdateRequest.TITLE02_LENGTH,
            UserUpdateRequest.CURTIME_LENGTH,
            UserUpdateRequest.USRIDIN_LENGTH,
            UserUpdateRequest.FNAME_LENGTH,
            UserUpdateRequest.LNAME_LENGTH,
            UserUpdateRequest.PASSWD_LENGTH,
            UserUpdateRequest.USRTYPE_LENGTH,
            UserUpdateRequest.ERRMSG_LENGTH);

    /** {@code app/cpy-bms/COUSR02.CPY} lines the twelve {@code xxxI} items are declared on. */
    private static final List<Integer> COPYBOOK_LINES =
            List.of(24, 30, 36, 42, 48, 54, 60, 66, 72, 78, 84, 90);

    /** {@code app/bms/COUSR02.bms} lines the twelve name-labelled {@code DFHMDF}s begin on. */
    private static final List<Integer> MAPSET_LINES =
            List.of(34, 38, 47, 57, 61, 70, 85, 103, 116, 130, 145, 155);

    /**
     * {@code app/cpy-bms/COUSR02.CPY} lines the twelve per-field {@code REDEFINES} overlays begin on.
     *
     * <p>Twelve, not thirteen: the group-level {@code 01 COUSR2AO REDEFINES COUSR2AI} at line 91 is
     * the response's and is deliberately excluded.
     */
    private static final List<Integer> PER_FIELD_REDEFINES_LINES =
            List.of(21, 27, 33, 39, 45, 51, 57, 63, 69, 75, 81, 87);

    /** Every {@code REDEFINES} in the copybook: the twelve per-field overlays plus the group view. */
    private static final int COPYBOOK_REDEFINES_TOTAL = 13;

    /**
     * Every {@code REDEFINES} in the five symbolic maps this package projects: <strong>110</strong>.
     *
     * <p>{@code COSGN00} 12, {@code COUSR00} 60, {@code COUSR01} 13, {@code COUSR02} 13 and
     * {@code COUSR03} 12. The five programs themselves declare <strong>none</strong> - a
     * {@code REDEFINES} count of zero in each of {@code COSGN00C}, {@code COUSR00C},
     * {@code COUSR01C}, {@code COUSR02C} and {@code COUSR03C} - so the symbolic maps are the only
     * place the redefinition gate has a subject in this package at all. That is why the overlay cases
     * in {@link RedefinesOverlays} exist here rather than in a program-level suite.
     */
    private static final int PACKAGE_REDEFINES_TOTAL = 110;

    /** {@code COUSR00}'s share of that total: sixty, one per field of a ten-row list screen. */
    private static final int COUSR00_REDEFINES_TOTAL = 60;

    /**
     * The three components that are not screen fields, in declaration order.
     *
     * <p>The explicitly mandated exception: CICS supplied all three from the communication area and
     * the exec interface block rather than from the map, so without them the program's branches are
     * unreachable.
     */
    private static final List<String> STATE_MEMBERS =
            List.of("navigationContext", "aid", "cu02Info");

    /** Twelve map members plus the three state carriers. */
    private static final int COMPONENT_COUNT = DFHMDF_NAMED + 3;

    /** {@code 02 FILLER PIC X(12)} at {@code COUSR02.CPY:18} - the {@code TIOAPFX=YES} prefix. */
    private static final int TIOAPFX_PREFIX_LENGTH = 12;

    /** {@code 02 xxxL COMP PIC S9(4)} - a binary halfword, two bytes. */
    private static final int LENGTH_ITEM_LENGTH = 2;

    /** {@code 02 xxxF PICTURE X} and its {@code xxxA} overlay - one byte, shared. */
    private static final int ATTRIBUTE_ITEM_LENGTH = 1;

    /** {@code 02 FILLER PICTURE X(4)} between each attribute byte and its data item. */
    private static final int ATTRIBUTE_FILLER_LENGTH = 4;

    /** Per field: {@code xxxL} 2 + {@code xxxF} 1 + {@code FILLER X(4)} = seven bytes. */
    private static final int FIELD_PREFIX_LENGTH =
            LENGTH_ITEM_LENGTH + ATTRIBUTE_ITEM_LENGTH + ATTRIBUTE_FILLER_LENGTH;

    /** The twelve declared widths added together: 4+40+8+8+40+8+8+20+20+8+1+78. */
    private static final int PAYLOAD_WIDTH_TOTAL = 243;

    /** {@code 01 COUSR2AI} in full: 12 + 12 x 7 + 243 = 339 bytes. */
    private static final int SYMBOLIC_MAP_LENGTH = 339;

    /** {@code 05 WS-MESSAGE PIC X(80) VALUE SPACES}, {@code COUSR02C.cbl:38}. */
    private static final int WS_MESSAGE_LENGTH = 80;

    /** {@code 05 SEC-USR-ID PIC X(08)}, {@code app/cpy/CSUSR01Y.cpy:18}. */
    private static final int SEC_USR_ID_LENGTH = 8;

    /** {@code 05 SEC-USR-FNAME PIC X(20)}, {@code app/cpy/CSUSR01Y.cpy:19}. */
    private static final int SEC_USR_FNAME_LENGTH = 20;

    /** {@code 05 SEC-USR-LNAME PIC X(20)}, {@code app/cpy/CSUSR01Y.cpy:20}. */
    private static final int SEC_USR_LNAME_LENGTH = 20;

    /** {@code 05 SEC-USR-PWD PIC X(08)}, {@code app/cpy/CSUSR01Y.cpy:21}. Plaintext. */
    private static final int SEC_USR_PWD_LENGTH = 8;

    /** {@code 05 SEC-USR-TYPE PIC X(01)}, {@code app/cpy/CSUSR01Y.cpy:22}. */
    private static final int SEC_USR_TYPE_LENGTH = 1;

    /**
     * The four members {@code UPDATE-USER-INFO} compares against the stored record, in source order.
     *
     * <p>{@code COUSR02C.cbl:219}, {@code :223}, {@code :227} and {@code :231} - four separate
     * {@code IF ... NOT = ... END-IF} statements, each independently setting
     * {@code USR-MODIFIED-YES}. {@code usrIdIn} is deliberately absent: line 216 moves it into
     * {@code SEC-USR-ID} to re-read the record, so it is the key and is never compared for change.
     */
    private static final List<String> CHANGE_DETECTED_MEMBERS =
            List.of("fName", "lName", "passwd", "usrType");

    /** The widths of those four, from {@code CSUSR01Y}: 20, 20, 8, 1. */
    private static final List<Integer> CHANGE_DETECTED_WIDTHS = List.of(SEC_USR_FNAME_LENGTH,
            SEC_USR_LNAME_LENGTH, SEC_USR_PWD_LENGTH, SEC_USR_TYPE_LENGTH);

    /**
     * The five members the blank-field chain guards, in the order {@code COUSR02C} tests them.
     *
     * <p>{@code app/cbl/COUSR02C.cbl:180}, {@code :186}, {@code :192}, {@code :198}, {@code :204}.
     * The identifier is <strong>first</strong>. On {@code COUSR01} the same chain tests the names
     * first, which is why a screen blank in both the identifier and the first name produces a
     * different message on each of the two programs.
     */
    private static final List<String> BLANK_GUARD_ORDER =
            List.of("usrIdIn", "fName", "lName", "passwd", "usrType");

    /** The five message texts, byte for byte, from the same five {@code WHEN} arms. */
    private static final List<String> BLANK_GUARD_MESSAGES =
            List.of("User ID can NOT be empty...",
                    "First Name can NOT be empty...",
                    "Last Name can NOT be empty...",
                    "Password can NOT be empty...",
                    "User Type can NOT be empty...");

    /**
     * {@code COUSR01}'s field order, for the contrast only: names before the identifier.
     *
     * <p>{@code app/cpy-bms/COUSR01.CPY:60}, {@code :66} and {@code :72} declare {@code FNAMEI},
     * {@code LNAMEI} then {@code USERIDI}; {@code app/bms/COUSR01.bms:84}, {@code :97} and
     * {@code :111} declare {@code FNAME}, {@code LNAME} then {@code USERID}. Both the order and the
     * spelling differ from this screen, and neither is harmonised.
     */
    private static final List<String> COUSR01_ITEM_ORDER = List.of("TRNNAMEI",
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

    /** {@code COUSR01}'s identifier item: {@code USERIDI}, {@code app/cpy-bms/COUSR01.CPY:72}. */
    private static final String COUSR01_ID_ITEM = "USERIDI";

    /** {@code COSGN00}'s time width: nine, {@code app/cpy-bms/COSGN00.CPY:54}. The only nine. */
    private static final int COSGN00_CURTIME_LENGTH = 9;

    /** {@code COUSR03} declares eleven named fields, having no {@code PASSWD}. */
    private static final int COUSR03_DFHMDF_NAMED = 11;

    /** The six items of {@code 05 CDEMO-CU02-INFO}, {@code COUSR02C.cbl:51-58}, in order. */
    private static final List<String> CU02_ITEM_NAMES = List.of("CDEMO-CU02-USRID-FIRST",
            "CDEMO-CU02-USRID-LAST",
            "CDEMO-CU02-PAGE-NUM",
            "CDEMO-CU02-NEXT-PAGE-FLG",
            "CDEMO-CU02-USR-SEL-FLG",
            "CDEMO-CU02-USR-SELECTED");

    /** The six declared widths: {@code X(08)}, {@code X(08)}, {@code 9(08)}, {@code X(01)} twice, {@code X(08)}. */
    private static final List<Integer> CU02_ITEM_WIDTHS = List.of(8, 8, 8, 1, 1, 8);

    /** 8 + 8 + 8 + 1 + 1 + 8 = thirty-four bytes. */
    private static final int CU02_INFO_LENGTH = 34;

    /** The 160-byte {@code CARDDEMO-COMMAREA} of {@code app/cpy/COCOM01Y.cpy:19-44}. */
    private static final int COMMAREA_LENGTH = 160;

    /** What {@code COUSR02C.cbl:94} restores and {@code :137} hands back: 160 + 34 = 194 bytes. */
    private static final int CU02_COMMAREA_LENGTH = COMMAREA_LENGTH + CU02_INFO_LENGTH;

    /** {@code 88 NEXT-PAGE-YES VALUE 'Y'}, {@code COUSR02C.cbl:55}. */
    private static final String NEXT_PAGE_YES = "Y";

    /** {@code 88 NEXT-PAGE-NO VALUE 'N'}, {@code COUSR02C.cbl:56}, and the field's own {@code VALUE}. */
    private static final String NEXT_PAGE_NO = "N";

    /** One space: a {@code PIC X(01)} value that satisfies neither {@code 88}-level. */
    private static final String NEITHER_PAGE_FLAG = " ";

    /**
     * A fixed instant, so the two time-derived expectations in this suite are exact (B7).
     *
     * <p>The value is the timestamp in the version footer of {@code app/cbl/COUSR02C.cbl:413}, which
     * makes it traceable rather than arbitrary. Read through
     * {@link Clock#fixed(Instant, java.time.ZoneId)} at {@link ZoneOffset#UTC}, so the header renders
     * identically on every run, on every machine, in any order.
     */
    private static final Instant FIXED_INSTANT = Instant.parse("2022-07-19T23:12:34Z");

    /** {@code WS-CURDATE-MM-DD-YY} for {@link #FIXED_INSTANT} - what cbl 309 would move. */
    private static final String FIXED_CURDATE = "07/19/22";

    /** {@code WS-CURTIME-HH-MM-SS} for {@link #FIXED_INSTANT} - what cbl 315 would move. */
    private static final String FIXED_CURTIME = "23:12:34";

    /** An eight-character stand-in for a keyed password. Not a credential: no system accepts it. */
    private static final String NOT_A_REAL_PASSWORD = "NOTAREAL";

    /** A second stand-in, so value semantics can be asserted without repeating the first. */
    private static final String OTHER_NOT_A_REAL_PASSWORD = "ALSOFAKE";

    /**
     * The layout of {@code 01 COUSR2AI}, rebuilt from the copybook's own geometry.
     *
     * <p>The {@code xxxF} and {@code xxxA} items are metadata and deliberately absent from the
     * payload, so the {@code REDEFINES} property has to be asserted against the storage it actually
     * describes rather than against a member. {@link FixedWidthRecord.RecordLayout} refuses a gap, an
     * unintended overlap and any total other than its declared length, so merely constructing this
     * field proves the transcribed geometry closes - and it does so at class initialisation, which
     * makes a single wrong constant a class-initialisation failure rather than a quietly shifted
     * offset. Immutable, so publishing it introduces no shared mutable state (B9).
     */
    private static final FixedWidthRecord.RecordLayout SYMBOLIC_MAP_LAYOUT = symbolicMapLayout();

    /** The complete set of JSON member names this payload may emit: the twelve plus the three. */
    private static final Set<String> EXPECTED_JSON_MEMBERS = expectedJsonMembers();

    // =================================================================================================
    // Construction of the two derived constants above. Static, side-effect free, called once each.
    // =================================================================================================

    /**
     * Rebuilds {@code 01 COUSR2AI} span by span, exactly as {@code app/cpy-bms/COUSR02.CPY:17-90}
     * declares it: the {@code TIOAPFX} prefix, then twelve repetitions of
     * {@code xxxL} / {@code xxxF} / {@code xxxA} overlay / {@code FILLER X(4)} / {@code xxxI}.
     *
     * <p>The declared total is passed to {@link FixedWidthRecord.RecordLayout#of}, never the cursor
     * this loop happened to reach. Passing the cursor would make the layout self-consistent with
     * whatever the constants add up to and would catch nothing; passing {@link #SYMBOLIC_MAP_LENGTH}
     * makes the layout's own self-check compare the transcribed geometry against the transcribed
     * total.
     */
    private static FixedWidthRecord.RecordLayout symbolicMapLayout() {
        List<FixedWidthRecord.FieldSpan> spans = new ArrayList<>();
        // 02 FILLER PIC X(12) - COUSR02.CPY:18, the TIOAPFX=YES prefix DFHMSD requests at line 19.
        spans.add(FixedWidthRecord.FieldSpan.filler(0, TIOAPFX_PREFIX_LENGTH));
        int cursor = TIOAPFX_PREFIX_LENGTH;
        for (int index = 0; index < DFHMDF_NAMED; index++) {
            String field = SCREEN_FIELDS.get(index);
            // 02 xxxL COMP PIC S9(4) - a binary halfword. Metadata, so it is reserved storage here.
            spans.add(FixedWidthRecord.FieldSpan.filler(cursor, LENGTH_ITEM_LENGTH));
            cursor += LENGTH_ITEM_LENGTH;
            // 02 xxxF PICTURE X, then 02 FILLER REDEFINES xxxF / 03 xxxA PICTURE X over that one byte.
            FixedWidthRecord.FieldSpan flag = FixedWidthRecord.FieldSpan.alphanumeric(
                    field + "F", cursor, ATTRIBUTE_ITEM_LENGTH);
            spans.add(flag);
            spans.add(flag.redefinedAs(field + "A", FixedWidthRecord.PictureKind.ALPHANUMERIC));
            cursor += ATTRIBUTE_ITEM_LENGTH;
            // 02 FILLER PICTURE X(4).
            spans.add(FixedWidthRecord.FieldSpan.filler(cursor, ATTRIBUTE_FILLER_LENGTH));
            cursor += ATTRIBUTE_FILLER_LENGTH;
            // 02 xxxI PIC X(n) - the only item of the four that becomes a payload member.
            spans.add(FixedWidthRecord.FieldSpan.alphanumeric(
                    SYMBOLIC_MAP_ITEMS.get(index), cursor, DECLARED_WIDTHS.get(index)));
            cursor += DECLARED_WIDTHS.get(index);
        }
        return FixedWidthRecord.RecordLayout.of(SYMBOLIC_MAP_LENGTH,
                spans.toArray(FixedWidthRecord.FieldSpan[]::new));
    }

    private static Set<String> expectedJsonMembers() {
        Set<String> members = new LinkedHashSet<>(wireNamesOf(MAP_MEMBERS));
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
     * <p>A default mapper would be the wrong instrument and would make this suite assert the wrong
     * thing. Three settings matter and all three are stated rather than inherited:
     * {@code USE_BIG_DECIMAL_FOR_FLOATS} and {@code WRITE_BIGDECIMAL_AS_PLAIN} are enabled so no
     * numeric value could route through a binary floating-point type or serialise in exponent
     * notation, and {@code ACCEPT_EMPTY_STRING_AS_NULL_OBJECT} is <em>disabled</em> so an empty or
     * all-spaces {@code PIC X(n)} value stays the real screen data it is instead of becoming
     * {@code null}. Coercing it would silently defeat every blank-value assertion in
     * {@link ValidationConstraints}, because the guards at {@code COUSR02C.cbl:180-204} treat
     * {@code SPACES} and {@code LOW-VALUES} as one condition and this payload has to be able to carry
     * both. No naming strategy is applied, so each property name still traces one-to-one to an
     * {@code xxxI} item; no inclusion filter is applied, so an absent member is emitted rather than
     * dropped; and no trimming converter is registered, so trailing padding survives - which it must,
     * because the change tests at {@code COUSR02C.cbl:219-234} compare against space-padded stored
     * values.
     */
    private static ObjectMapper webConfigEquivalentMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        mapper.configure(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN, true);
        mapper.disable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT);
        return mapper;
    }

    /** Builds a request from the twelve map values in component order, plus the three state members. */
    private static UserUpdateRequest requestOf(List<String> mapValues, NavigationContext context,
            String aid, UserUpdateRequest.Cu02Info cu02Info) {
        return new UserUpdateRequest(mapValues.get(0), mapValues.get(1), mapValues.get(2),
                mapValues.get(3), mapValues.get(4), mapValues.get(5), mapValues.get(6),
                mapValues.get(7), mapValues.get(8), mapValues.get(9), mapValues.get(10),
                mapValues.get(11), context, aid, cu02Info);
    }

    /** The twelve map members of a request, in component order. Permits {@code null} entries. */
    private static List<String> mapValuesOf(UserUpdateRequest request) {
        return Arrays.asList(request.trnName(), request.title01(), request.curDate(),
                request.pgmName(), request.title02(), request.curTime(), request.usrIdIn(),
                request.fName(), request.lName(), request.passwd(), request.usrType(),
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

    /**
     * Twelve {@code null}s - the {@code LOW-VALUES} shape, distinct from the {@code SPACES} shape and
     * equally something the guards at {@code COUSR02C.cbl:180-204} treat as blank.
     */
    private static List<String> nullMapValues() {
        return Arrays.asList(new String[DFHMDF_NAMED]);
    }

    /**
     * Twelve runs of spaces, each exactly its declared width - the {@code MOVE SPACES} shape
     * {@code INITIALIZE-ALL-FIELDS} produces at {@code COUSR02C.cbl:406-411}.
     */
    private static List<String> spaceFilledMapValues() {
        List<String> values = new ArrayList<>(DFHMDF_NAMED);
        for (int index = 0; index < DFHMDF_NAMED; index++) {
            values.add(codec().movePicX("", DECLARED_WIDTHS.get(index)));
        }
        return values;
    }

    /**
     * A fully populated request: every map member exactly its declared width, so the instance is a
     * faithful image of a painted screen rather than a convenient shorthand.
     *
     * <p>The header values come from {@link #header()} and therefore from the fixed clock (B7). The
     * password is {@link #NOT_A_REAL_PASSWORD}: eight characters, which is what makes the width
     * assertions meaningful, and not a credential any system accepts.
     */
    private static UserUpdateRequest populatedRequest() {
        return requestOf(populatedMapValues(),
                populatedContext(),
                PfKeyResolver.AidKey.PFK05.token(),
                populatedCu02Info());
    }

    /** The twelve map values {@link #populatedRequest()} carries, each at its declared width. */
    private static List<String> populatedMapValues() {
        return Arrays.asList(TRANSACTION_ID,
                ScreenTitles.CCDA_TITLE01,
                header().wsCurdateMmDdYy(),
                PROGRAM_NAME,
                ScreenTitles.CCDA_TITLE02,
                header().wsCurtimeHhMmSs(),
                "USER0001",
                codec().movePicX("LAWRENCE", UserUpdateRequest.FNAME_LENGTH),
                codec().movePicX("THOMAS", UserUpdateRequest.LNAME_LENGTH),
                NOT_A_REAL_PASSWORD,
                UserUpdateRequest.USER_TYPE_USER,
                codec().movePicX("", UserUpdateRequest.ERRMSG_LENGTH));
    }

    /** The communication area a re-entry carries: the previous screen handed it back. */
    private static NavigationContext populatedContext() {
        return NavigationContext.empty()
                .withFromTranid("CU00")
                .withFromProgram("COUSR00C")
                .withToTranid(TRANSACTION_ID)
                .withToProgram(PROGRAM_NAME)
                .withUserId("ADMIN001")
                .withUserTypeAdmin()
                .withLastMap(MAP_NAME)
                .withLastMapset(MAPSET_NAME)
                .withPgmReenter();
    }

    /**
     * The extension group as {@code COUSR00C} would have filled it before transferring here: a
     * selected user identifier, a page number and the paging flag in its declared {@code 'N'} state.
     */
    private static UserUpdateRequest.Cu02Info populatedCu02Info() {
        return new UserUpdateRequest.Cu02Info("USER0001", "USER0050", 1, NEXT_PAGE_NO, "U",
                "USER0001");
    }

    /**
     * The date and time header, captured from the fixed clock so the two header values are exact and
     * reproducible (B7).
     */
    private static DateHeader header() {
        return DateHeader.from(codec(), Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
    }

    /** Runs Bean Validation over one instance, closing the factory it opened. */
    private static Set<ConstraintViolation<UserUpdateRequest>> validate(UserUpdateRequest request) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(request);
        }
    }

    /** Serialises through the configured mapper, translating the checked failure. */
    private static String serialise(UserUpdateRequest request) {
        try {
            return webConfigEquivalentMapper().writeValueAsString(request);
        } catch (IOException failure) {
            throw new UncheckedIOException("Serialising a UserUpdateRequest must not fail", failure);
        }
    }

    /** Deserialises through the configured mapper, translating the checked failure. */
    private static UserUpdateRequest deserialise(String json) {
        try {
            return webConfigEquivalentMapper().readValue(json, UserUpdateRequest.class);
        } catch (IOException failure) {
            throw new UncheckedIOException("Deserialising a UserUpdateRequest must not fail", failure);
        }
    }

    /** The two lists joined, so a component census can be stated as one expected sequence. */
    private static List<String> concat(List<String> first, List<String> second) {
        List<String> joined = new ArrayList<>(first.size() + second.size());
        joined.addAll(first);
        joined.addAll(second);
        return List.copyOf(joined);
    }

    /**
     * A copy of {@link #populatedRequest()} with one map member replaced.
     *
     * <p>Named rather than positional so a case reads as the screen field it is about, and so a
     * member added to the record cannot silently shift an index.
     */
    private static UserUpdateRequest withMapValue(String member, String value) {
        int index = MAP_MEMBERS.indexOf(member);
        if (index < 0) {
            throw new IllegalArgumentException(member + " is not one of the twelve map members "
                    + MAP_MEMBERS + "; the twelve come from the xxxI items of COUSR02.CPY:17-90");
        }
        List<String> values = new ArrayList<>(populatedMapValues());
        values.set(index, value);
        return requestOf(values, populatedContext(), PfKeyResolver.AidKey.PFK05.token(),
                populatedCu02Info());
    }

    /** The twelve {@code xxxI} names the type publishes, read one constant at a time. */
    private static List<String> publishedFieldNames() {
        return List.of(UserUpdateRequest.TRNNAME_FIELD,
                UserUpdateRequest.TITLE01_FIELD,
                UserUpdateRequest.CURDATE_FIELD,
                UserUpdateRequest.PGMNAME_FIELD,
                UserUpdateRequest.TITLE02_FIELD,
                UserUpdateRequest.CURTIME_FIELD,
                UserUpdateRequest.USRIDIN_FIELD,
                UserUpdateRequest.FNAME_FIELD,
                UserUpdateRequest.LNAME_FIELD,
                UserUpdateRequest.PASSWD_FIELD,
                UserUpdateRequest.USRTYPE_FIELD,
                UserUpdateRequest.ERRMSG_FIELD);
    }

    /** The declared type of one component of a record, by name. */
    private static Class<?> componentType(Class<?> recordType, String component) {
        for (RecordComponent candidate : recordType.getRecordComponents()) {
            if (candidate.getName().equals(component)) {
                return candidate.getType();
            }
        }
        throw new IllegalArgumentException(recordType.getSimpleName() + " declares no component "
                + component);
    }

    /**
     * Every type this payload exposes: its own component types, the extension group's, and the return
     * and parameter types of every method either declares.
     *
     * <p>Used by the floating-point sweep (gate G22) and by the security-framework sweep (gate G41),
     * both of which have to reason about what is reachable rather than only about what is declared.
     */
    private static List<Class<?>> reachableTypes() {
        List<Class<?>> types = new ArrayList<>();
        for (Class<?> owner : List.of(UserUpdateRequest.class, UserUpdateRequest.Cu02Info.class)) {
            for (RecordComponent component : owner.getRecordComponents()) {
                types.add(component.getType());
            }
            for (Method method : owner.getDeclaredMethods()) {
                types.add(method.getReturnType());
                types.addAll(Arrays.asList(method.getParameterTypes()));
            }
            for (Field field : owner.getDeclaredFields()) {
                types.add(field.getType());
            }
        }
        return List.copyOf(types);
    }

    /** The member names actually present in a serialised payload. */
    private static Set<String> jsonMembersOf(UserUpdateRequest request) {
        try {
            Map<String, Object> tree = webConfigEquivalentMapper()
                    .readValue(serialise(request), new TypeReference<Map<String, Object>>() { });
            return tree.keySet();
        } catch (IOException failure) {
            throw new UncheckedIOException("Reading a serialised UserUpdateRequest must not fail",
                    failure);
        }
    }

    // =================================================================================================
    // 1. THE PROJECTION OF 01 COUSR2AI.
    //
    // Twelve map members and three state carriers, in the copybook's own order, each map member
    // traceable to one name-labelled DFHMDF definition (gate G9).
    // =================================================================================================

    @Nested
    @DisplayName("Projection of 01 COUSR2AI - twelve map members, in copybook order")
    class MapProjection {

        @Test
        @DisplayName("fifteen components: the twelve map members then the three state carriers")
        void componentCensus() {
            List<String> declared = Arrays.stream(UserUpdateRequest.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(declared)
                    .as("the twelve xxxI items of COUSR02.CPY:17-90 followed by the communication "
                            + "area, the attention identifier and the CDEMO-CU02-INFO extension")
                    .hasSize(COMPONENT_COUNT)
                    .containsExactlyElementsOf(concat(MAP_MEMBERS, STATE_MEMBERS));
            assertThat(declared.subList(0, DFHMDF_NAMED))
                    .as("the map members come first and in the map's own order")
                    .containsExactlyElementsOf(MAP_MEMBERS);
        }

        @Test
        @DisplayName("every map member is a String, because every xxxI item is PIC X(n)")
        void everyMapMemberIsCharacter() {
            RecordComponent[] components = UserUpdateRequest.class.getRecordComponents();
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                assertThat(components[index].getType())
                        .as("%s projects %s PIC X(%d)", MAP_MEMBERS.get(index),
                                SYMBOLIC_MAP_ITEMS.get(index), DECLARED_WIDTHS.get(index))
                        .isEqualTo(String.class);
            }
            assertThat(components[DFHMDF_NAMED].getType()).isEqualTo(NavigationContext.class);
            assertThat(components[DFHMDF_NAMED + 1].getType()).isEqualTo(String.class);
            assertThat(components[DFHMDF_NAMED + 2].getType())
                    .isEqualTo(UserUpdateRequest.Cu02Info.class);
        }

        @Test
        @DisplayName("no component is a binary floating-point type, at any depth (gate G22)")
        void noComponentIsFloatingPoint() {
            // PIC 9(08) is scale-free and PIC X(n) is characters, so nothing on this payload has any
            // business being a double or a float. CDEMO-CU02-PAGE-NUM is the one numeric item and it
            // is checked explicitly, because an int that later becomes a double is exactly the defect
            // the gate exists to catch.
            for (Class<?> type : reachableTypes()) {
                assertThat(type)
                        .isNotEqualTo(double.class)
                        .isNotEqualTo(float.class)
                        .isNotEqualTo(Double.class)
                        .isNotEqualTo(Float.class);
            }
            assertThat(componentType(UserUpdateRequest.Cu02Info.class, "pageNum"))
                    .as("CDEMO-CU02-PAGE-NUM PIC 9(08) is scale-free, so it is an integral type")
                    .isEqualTo(int.class);
        }

        @Test
        @DisplayName("MAP_FIELD_COUNT is the mapset's own count, and 17 definitions stay unexposed")
        void countsAreTheMapsetsOwn() {
            assertThat(UserUpdateRequest.MAP_FIELD_COUNT)
                    .as("twelve name-labelled DFHMDF definitions, twelve xxxI items, twelve xxxO items")
                    .isEqualTo(DFHMDF_NAMED)
                    .isEqualTo(MAP_MEMBERS.size())
                    .isEqualTo(SYMBOLIC_MAP_ITEMS.size());
            assertThat(DFHMDF_TOTAL - DFHMDF_NAMED)
                    .as("the seventeen unnamed definitions are literal furniture: no name, no "
                            + "symbolic-map item, and so no payload member")
                    .isEqualTo(17);
            assertThat(UserUpdateRequest.MAP_FIELD_COUNT)
                    .as("and COUSR03 has eleven because it declares no PASSWD; the difference is "
                            + "preserved, not averaged (B5)")
                    .isEqualTo(COUSR03_DFHMDF_NAMED + 1);
        }

        @Test
        @DisplayName("MAP_FIELD_NAMES is the twelve xxxI items in order, and is unmodifiable")
        void theFieldNameListIsTheCopybooks() {
            assertThat(UserUpdateRequest.MAP_FIELD_NAMES)
                    .containsExactlyElementsOf(SYMBOLIC_MAP_ITEMS);
            assertThat(UserUpdateRequest.MAP_FIELD_NAMES.get(6))
                    .as("seventh: the identifier, before the two names")
                    .isEqualTo("USRIDINI");
            assertThat(UserUpdateRequest.MAP_FIELD_NAMES.getClass().getName())
                    .as("List.of produces an immutable list, so the constant is not mutable state")
                    .contains("ImmutableCollections");
        }

        @ParameterizedTest(name = "{0} PIC X({1}) at COUSR02.CPY:{2}")
        @CsvSource({
            "TRNNAMEI,  4, 24",
            "TITLE01I, 40, 30",
            "CURDATEI,  8, 36",
            "PGMNAMEI,  8, 42",
            "TITLE02I, 40, 48",
            "CURTIMEI,  8, 54",
            "USRIDINI,  8, 60",
            "FNAMEI,   20, 66",
            "LNAMEI,   20, 72",
            "PASSWDI,   8, 78",
            "USRTYPEI,  1, 84",
            "ERRMSGI,  78, 90"})
        @DisplayName("each published width is the width its xxxI PICTURE clause declares")
        void publishedWidthMatchesTheCopybook(String item, int width, int copybookLine) {
            int index = SYMBOLIC_MAP_ITEMS.indexOf(item);

            assertThat(index).as("%s must be one of the twelve items", item).isNotNegative();
            assertThat(COPYBOOK_LINES.get(index))
                    .as("%s is declared on COUSR02.CPY:%d", item, copybookLine)
                    .isEqualTo(copybookLine);
            assertThat(PUBLISHED_WIDTHS.get(index))
                    .as("%s PIC X(%d) - the width the type publishes must be the copybook's",
                            item, width)
                    .isEqualTo(width)
                    .isEqualTo(DECLARED_WIDTHS.get(index));
        }

        @ParameterizedTest(name = "{0} LENGTH={1} at COUSR02.bms:{2}")
        @CsvSource({
            "TRNNAME,  4,  34",
            "TITLE01, 40,  38",
            "CURDATE,  8,  47",
            "PGMNAME,  8,  57",
            "TITLE02, 40,  61",
            "CURTIME,  8,  70",
            "USRIDIN,  8,  85",
            "FNAME,   20, 103",
            "LNAME,   20, 116",
            "PASSWD,   8, 130",
            "USRTYPE,  1, 145",
            "ERRMSG,  78, 155"})
        @DisplayName("the mapset's LENGTH operand agrees independently of the copybook")
        void publishedWidthMatchesTheMapset(String screenField, int length, int mapsetLine) {
            int index = SCREEN_FIELDS.indexOf(screenField);

            assertThat(index).as("%s must be one of the twelve named fields", screenField)
                    .isNotNegative();
            assertThat(MAPSET_LINES.get(index))
                    .as("%s DFHMDF begins on COUSR02.bms:%d", screenField, mapsetLine)
                    .isEqualTo(mapsetLine);
            assertThat(PUBLISHED_WIDTHS.get(index))
                    .as("two independent sources, one width: LENGTH=%d and PIC X(%d)", length, length)
                    .isEqualTo(length);
        }

        @Test
        @DisplayName("every member traces to one screen field, and its xxxI item is that field plus I")
        void everyMemberTracesToAScreenField() {
            assertThat(SCREEN_FIELDS).hasSize(DFHMDF_NAMED);
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                assertThat(SYMBOLIC_MAP_ITEMS.get(index))
                        .as("the symbolic-map item is the DFHMDF name with the input suffix")
                        .isEqualTo(SCREEN_FIELDS.get(index) + "I");
            }
            assertThat(publishedFieldNames())
                    .as("the type publishes the same twelve item names, in the same order")
                    .containsExactlyElementsOf(SYMBOLIC_MAP_ITEMS);
        }

        @Test
        @DisplayName("the screen identity constants match WS-TRANID, WS-PGMNAME, DFHMDI and DFHMSD")
        void screenIdentity() {
            assertThat(UserUpdateRequest.TRANSACTION_ID)
                    .as("WS-TRANID at COUSR02C.cbl:37, and TRANSACTION(CU02) at CARDDEMO.CSD:469")
                    .isEqualTo(TRANSACTION_ID)
                    .hasSize(UserUpdateRequest.TRNNAME_LENGTH);
            assertThat(UserUpdateRequest.PROGRAM_NAME)
                    .as("WS-PGMNAME at COUSR02C.cbl:36, and PROGRAM(COUSR02C) at CARDDEMO.CSD:470")
                    .isEqualTo(PROGRAM_NAME)
                    .hasSize(UserUpdateRequest.PGMNAME_LENGTH);
            assertThat(UserUpdateRequest.MAP_NAME).isEqualTo(MAP_NAME)
                    .hasSize(NavigationContext.LAST_MAP_LENGTH);
            assertThat(UserUpdateRequest.MAPSET_NAME).isEqualTo(MAPSET_NAME)
                    .hasSize(NavigationContext.LAST_MAPSET_LENGTH);
            assertThat(UserUpdateRequest.SYMBOLIC_MAP_INPUT)
                    .as("INTO(COUSR2AI) at COUSR02C.cbl:288")
                    .isEqualTo(SYMBOLIC_MAP_INPUT);
            assertThat(SCREEN_ROWS * SCREEN_COLUMNS)
                    .as("SIZE=(24,80) at COUSR02.bms:28 - one 3270 screen, 1920 positions")
                    .isEqualTo(1920);
        }

        @Test
        @DisplayName("the user-type literals are COCOM01Y's, and neither is enforced as a format")
        void theUserTypeLiterals() {
            assertThat(UserUpdateRequest.USER_TYPE_ADMIN)
                    .as("88 CDEMO-USRTYP-ADMIN VALUE 'A', COCOM01Y.cpy:27")
                    .isEqualTo(NavigationContext.USER_TYPE_ADMIN)
                    .hasSize(UserUpdateRequest.USRTYPE_LENGTH);
            assertThat(UserUpdateRequest.USER_TYPE_USER)
                    .as("88 CDEMO-USRTYP-USER VALUE 'U', COCOM01Y.cpy:28")
                    .isEqualTo(NavigationContext.USER_TYPE_USER)
                    .hasSize(UserUpdateRequest.USRTYPE_LENGTH);

            // COUSR02C tests USRTYPEI for blankness at :204 and for change at :231 and nowhere
            // compares it with either literal, so a third value is carried, not rejected.
            UserUpdateRequest odd = withMapValue("usrType", "Z");
            assertThat(odd.usrType()).isEqualTo("Z");
            assertThat(validate(odd)).isEmpty();
        }

        @Test
        @DisplayName("declares no static mutable state (gate G53)")
        void declaresNoStaticMutableState() {
            for (Class<?> type : List.of(UserUpdateRequest.class, UserUpdateRequest.Cu02Info.class,
                    UserUpdateRequestTest.class)) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) {
                        assertThat(Modifier.isFinal(field.getModifiers()))
                                .as("%s.%s is static and must therefore be final: COBOL "
                                        + "WORKING-STORAGE must never become a shared Java field",
                                        type.getSimpleName(), field.getName())
                                .isTrue();
                    }
                }
            }
        }
    }

    // =================================================================================================
    // 2. THE CROSS-SCREEN INVERSIONS.
    //
    // COUSR01 and COUSR02 look like the same screen and are not. A reviewer who assumes they are
    // symmetrical introduces a silent G9 violation, so each difference gets a named case of its own.
    // =================================================================================================

    @Nested
    @DisplayName("Cross-screen inversions - USRIDIN not USERID, and the identifier comes first")
    class CrossScreenInversions {

        @Test
        @DisplayName("the identifier member is named for USRIDIN, and not for COUSR01's USERID")
        void theIdentifierIsNamedUsrIdIn() {
            assertThat(UserUpdateRequest.USRIDIN_FIELD)
                    .as("COUSR02.CPY:60 declares USRIDINI; COUSR01.CPY:72 declares USERIDI. Two "
                            + "spellings of one concept, and this screen uses the first")
                    .isEqualTo("USRIDINI")
                    .isNotEqualTo(COUSR01_ID_ITEM);
            assertThat(MAP_MEMBERS)
                    .as("the Java member follows the mapset: usrIdIn, never userId")
                    .contains("usrIdIn")
                    .doesNotContain("userId");
            assertThat(Arrays.stream(UserUpdateRequest.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList())
                    .contains("usrIdIn")
                    .doesNotContain("userId", "usrId", "userID");
            assertThat(UserUpdateRequest.MAP_FIELD_NAMES)
                    .doesNotContain(COUSR01_ID_ITEM);
        }

        @Test
        @DisplayName("the identifier is declared BEFORE the names, the inverse of COUSR01")
        void theIdentifierPrecedesTheNames() {
            int identifier = MAP_MEMBERS.indexOf("usrIdIn");
            int firstName = MAP_MEMBERS.indexOf("fName");
            int lastName = MAP_MEMBERS.indexOf("lName");

            assertThat(identifier)
                    .as("USRIDINI is seventh on COUSR02.CPY, at line 60")
                    .isEqualTo(6);
            assertThat(identifier).isLessThan(firstName).isLessThan(lastName);
            assertThat(firstName).isLessThan(lastName);

            // And the sibling, for contrast: FNAMEI, LNAMEI, then USERIDI.
            assertThat(COUSR01_ITEM_ORDER.indexOf(COUSR01_ID_ITEM))
                    .as("COUSR01 puts its identifier ninth, after both names")
                    .isEqualTo(8)
                    .isGreaterThan(COUSR01_ITEM_ORDER.indexOf("FNAMEI"))
                    .isGreaterThan(COUSR01_ITEM_ORDER.indexOf("LNAMEI"));
            assertThat(SYMBOLIC_MAP_ITEMS)
                    .as("the two orders genuinely differ; neither is harmonised (B4)")
                    .isNotEqualTo(COUSR01_ITEM_ORDER);
        }

        @Test
        @DisplayName("that order is what makes the blank-field message deterministic")
        void theOrderDecidesWhichBlankMessageIsProduced() {
            // COUSR02C.cbl:179-213 is an EVALUATE TRUE: the FIRST matching WHEN wins and the rest are
            // never evaluated. Its arms are ordered USRIDINI, FNAMEI, LNAMEI, PASSWDI, USRTYPEI, so a
            // screen blank in both the identifier and the first name yields the User ID message.
            // COUSR01C's arms are ordered the other way round and would yield the First Name message
            // from the same payload. The selection itself is UserUpdateControllerTest's subject; what
            // this case pins is the field order that makes it decidable at all.
            assertThat(BLANK_GUARD_ORDER)
                    .as("the five guarded members, in the order the EVALUATE tests them")
                    .containsExactly("usrIdIn", "fName", "lName", "passwd", "usrType");
            assertThat(BLANK_GUARD_ORDER.get(0))
                    .as("arm one is the identifier, so it decides a multi-blank payload")
                    .isEqualTo("usrIdIn");
            for (int arm = 1; arm < BLANK_GUARD_ORDER.size(); arm++) {
                assertThat(MAP_MEMBERS.indexOf(BLANK_GUARD_ORDER.get(arm)))
                        .as("guard %d, %s, follows the identifier in the map as well as in the chain",
                                arm + 1, BLANK_GUARD_ORDER.get(arm))
                        .isGreaterThan(MAP_MEMBERS.indexOf(BLANK_GUARD_ORDER.get(0)));
            }

            // A payload blank in both is carried as-is: the type decides nothing and rejects nothing.
            UserUpdateRequest bothBlank = requestOf(blankMapValues(), populatedContext(),
                    PfKeyResolver.AidKey.PFK05.token(), null);
            assertThat(bothBlank.usrIdIn()).isEmpty();
            assertThat(bothBlank.fName()).isEmpty();
            assertThat(validate(bothBlank))
                    .as("both blanks are valid input; the program answers them with text, not a 400")
                    .isEmpty();
        }

        @Test
        @DisplayName("the five blank-guard messages are carried byte for byte by the 78-wide field")
        void theBlankGuardMessagesFitTheMessageField() {
            assertThat(BLANK_GUARD_MESSAGES).hasSameSizeAs(BLANK_GUARD_ORDER);
            for (String message : BLANK_GUARD_MESSAGES) {
                assertThat(message.length())
                        .as("'%s' must fit WS-MESSAGE PIC X(80) and survive the move to ERRMSG X(78)",
                                message)
                        .isLessThanOrEqualTo(UserUpdateRequest.ERRMSG_LENGTH);
                UserUpdateRequest carried = withMapValue("errMsg",
                        codec().movePicX(message, UserUpdateRequest.ERRMSG_LENGTH));
                assertThat(carried.errMsg())
                        .hasSize(UserUpdateRequest.ERRMSG_LENGTH)
                        .startsWith(message);
                assertThat(validate(carried)).isEmpty();
            }
        }

        @Test
        @DisplayName("this screen has a password field and COUSR03 does not; both facts are preserved")
        void thePasswordFieldExistsHereAndNotOnTheDeleteScreen() {
            assertThat(SCREEN_FIELDS)
                    .as("PASSWD DFHMDF at COUSR02.bms:130")
                    .contains("PASSWD");
            assertThat(MAP_MEMBERS).contains("passwd");
            assertThat(DFHMDF_NAMED - COUSR03_DFHMDF_NAMED)
                    .as("the one field COUSR03 does not declare is exactly PASSWD")
                    .isEqualTo(1);
        }
    }

    // =================================================================================================
    // 3. WIDTH TRAPS.
    //
    // Two widths on this screen are not what a sibling would lead you to expect, and one of them loses
    // data on every send. MOVE is the dominant parity risk in this migration, so the narrowing is
    // driven through the codec rather than described.
    // =================================================================================================

    @Nested
    @DisplayName("Width traps - eight not nine, and seventy-eight not eighty")
    class WidthTraps {

        @Test
        @DisplayName("curTime is eight characters here; only COSGN00 widens its time field to nine")
        void curTimeIsEightNotNine() {
            assertThat(UserUpdateRequest.CURTIME_LENGTH)
                    .as("CURTIMEI PIC X(8) at COUSR02.CPY:54, CURTIME LENGTH=8 at COUSR02.bms:70")
                    .isEqualTo(8)
                    .isNotEqualTo(COSGN00_CURTIME_LENGTH);
            assertThat(COSGN00_CURTIME_LENGTH)
                    .as("COSGN00.CPY:54 declares CURTIMEI PIC X(9) - the only nine in the application")
                    .isEqualTo(9);
            assertThat(header().wsCurtimeHhMmSs())
                    .as("WS-CURTIME-HH-MM-SS is hh:mm:ss, eight characters, and fills the field "
                            + "exactly with no padding")
                    .isEqualTo(FIXED_CURTIME)
                    .hasSize(UserUpdateRequest.CURTIME_LENGTH)
                    .hasSize(DateHeader.WS_CURTIME_HH_MM_SS_LENGTH);
        }

        @Test
        @DisplayName("curDate is eight characters and the header date fills it exactly")
        void curDateIsEightAndFillsExactly() {
            assertThat(UserUpdateRequest.CURDATE_LENGTH)
                    .as("CURDATEI PIC X(8) at COUSR02.CPY:36")
                    .isEqualTo(DateHeader.WS_CURDATE_MM_DD_YY_LENGTH);
            assertThat(header().wsCurdateMmDdYy())
                    .as("WS-CURDATE-MM-DD-YY as COUSR02C.cbl:305-309 assembles it, from the fixed "
                            + "clock so the expectation is exact (B7)")
                    .isEqualTo(FIXED_CURDATE)
                    .hasSize(UserUpdateRequest.CURDATE_LENGTH);

            UserUpdateRequest painted = populatedRequest();
            assertThat(painted.curDate()).isEqualTo(FIXED_CURDATE);
            assertThat(painted.curTime()).isEqualTo(FIXED_CURTIME);
        }

        @Test
        @DisplayName("errMsg is 78 while WS-MESSAGE is 80, so the move discards two characters")
        void theEightyByteMessageLosesItsLastTwoCharacters() {
            // COUSR02C.cbl:38 declares WS-MESSAGE PIC X(80); :270 does
            //     MOVE WS-MESSAGE TO ERRMSGO OF COUSR2AO
            // and ERRMSG is X(78), so COBOL fills the receiver from the left and discards the two
            // characters that do not fit. The loss is driven here through the alphanumeric MOVE rule
            // (B11) rather than being described, and the sending value's last two characters are
            // deliberately non-space so their disappearance is observable.
            String head = codec().movePicX("Please modify to update ...",
                    UserUpdateRequest.ERRMSG_LENGTH);
            String eighty = head + "#!";

            assertThat(head).hasSize(UserUpdateRequest.ERRMSG_LENGTH);
            assertThat(eighty)
                    .as("an eighty-character WS-MESSAGE whose final two characters are not spaces")
                    .hasSize(WS_MESSAGE_LENGTH)
                    .endsWith("#!");

            String narrowed = codec().movePicX(eighty, UserUpdateRequest.ERRMSG_LENGTH);

            assertThat(narrowed)
                    .as("the receiving width is 78, so exactly the leading 78 characters survive")
                    .hasSize(UserUpdateRequest.ERRMSG_LENGTH)
                    .isEqualTo(head)
                    .startsWith("Please modify to update ...")
                    .doesNotContain("#")
                    .doesNotContain("!");
            assertThat(WS_MESSAGE_LENGTH - UserUpdateRequest.ERRMSG_LENGTH)
                    .as("two characters, on every send, unconditionally")
                    .isEqualTo(2);

            // And the payload carries the narrowed image without narrowing it again.
            assertThat(withMapValue("errMsg", narrowed).errMsg())
                    .isEqualTo(narrowed)
                    .hasSize(UserUpdateRequest.ERRMSG_LENGTH);
        }

        @Test
        @DisplayName("truncation is on the RIGHT, which is the direction a PIC X receiver uses")
        void theMoveTruncatesOnTheRight() {
            // Keeping the trailing characters instead of the leading ones is the classic defect: it
            // looks plausible and produces a different byte in every diff. PIC 9 is the opposite
            // direction, and both are asserted so the asymmetry is on the record.
            assertThat(codec().movePicX("ABCDEF", UserUpdateRequest.TRNNAME_LENGTH))
                    .isEqualTo("ABCD")
                    .isNotEqualTo("CDEF");
            assertThat(codec().movePic9(123456789L,
                    UserUpdateRequest.Cu02Info.PAGE_NUM_DIGITS))
                    .as("a numeric receiver is aligned on its implied point, so the LOW-order digits "
                            + "survive - the opposite rule, on the same screen")
                    .isEqualTo("23456789");
        }

        @Test
        @DisplayName("a value shorter than its field is padded on the right, never left-aligned wrongly")
        void aShortValueIsPaddedOnTheRight() {
            String padded = codec().movePicX("THOMAS", UserUpdateRequest.LNAME_LENGTH);

            assertThat(padded)
                    .hasSize(UserUpdateRequest.LNAME_LENGTH)
                    .startsWith("THOMAS")
                    .isEqualTo("THOMAS" + " ".repeat(UserUpdateRequest.LNAME_LENGTH - 6));
            assertThat(withMapValue("lName", padded).lName())
                    .as("and the payload keeps the padding: the change test at COUSR02C.cbl:223 "
                            + "compares against a space-padded SEC-USR-LNAME")
                    .isEqualTo(padded);
        }

        @Test
        @DisplayName("title01 and title02 are forty and carry the screen titles byte for byte")
        void theTitlesAreFortyCharacters() {
            assertThat(UserUpdateRequest.TITLE01_LENGTH)
                    .isEqualTo(ScreenTitles.TITLE_LENGTH)
                    .isEqualTo(UserUpdateRequest.TITLE02_LENGTH);
            assertThat(ScreenTitles.CCDA_TITLE01)
                    .as("MOVE CCDA-TITLE01 TO TITLE01O at COUSR02C.cbl:300")
                    .hasSize(UserUpdateRequest.TITLE01_LENGTH);
            assertThat(ScreenTitles.CCDA_TITLE02)
                    .as("MOVE CCDA-TITLE02 TO TITLE02O at COUSR02C.cbl:301")
                    .hasSize(UserUpdateRequest.TITLE02_LENGTH);

            UserUpdateRequest painted = populatedRequest();
            assertThat(painted.title01()).isEqualTo(ScreenTitles.CCDA_TITLE01);
            assertThat(painted.title02()).isEqualTo(ScreenTitles.CCDA_TITLE02);
        }

        @Test
        @DisplayName("the two thank-you literals are different texts of different widths")
        void theThankYouLiteralsAreNotInterchangeable() {
            // A trap worth naming: ScreenTitles.CCDA_THANK_YOU is a 40-character title-line literal
            // from COTTL01Y and SystemMessages.CCDA_MSG_THANK_YOU is a 50-character message literal
            // from CSMSG01Y. Different text, different width, different owner. Substituting one for
            // the other would put the wrong bytes in a 40-wide title field or the wrong bytes in a
            // message, and neither would look obviously wrong.
            assertThat(ScreenTitles.CCDA_THANK_YOU)
                    .hasSize(ScreenTitles.TITLE_LENGTH)
                    .hasSize(UserUpdateRequest.TITLE01_LENGTH)
                    .isNotEqualTo(SystemMessages.CCDA_MSG_THANK_YOU);
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU)
                    .hasSize(SystemMessages.MESSAGE_LENGTH);
            assertThat(SystemMessages.MESSAGE_LENGTH)
                    .as("fifty, not forty, and neither is the 78 of ERRMSG")
                    .isNotEqualTo(ScreenTitles.TITLE_LENGTH)
                    .isNotEqualTo(UserUpdateRequest.ERRMSG_LENGTH);
        }

        @Test
        @DisplayName("the five data widths are the SEC-USER-DATA widths CSUSR01Y declares")
        void theDataWidthsMatchTheSecurityRecord() {
            assertThat(UserUpdateRequest.USRIDIN_LENGTH)
                    .as("SEC-USR-ID PIC X(08) at CSUSR01Y.cpy:18, moved at COUSR02C.cbl:162 and :216")
                    .isEqualTo(SEC_USR_ID_LENGTH)
                    .isEqualTo(SecUserRecord.SEC_USR_ID_LENGTH)
                    .isEqualTo(SecUserRecord.KEY_LENGTH);
            assertThat(UserUpdateRequest.FNAME_LENGTH)
                    .as("SEC-USR-FNAME PIC X(20) at CSUSR01Y.cpy:19, compared at :219")
                    .isEqualTo(SEC_USR_FNAME_LENGTH)
                    .isEqualTo(SecUserRecord.SEC_USR_FNAME_LENGTH);
            assertThat(UserUpdateRequest.LNAME_LENGTH)
                    .as("SEC-USR-LNAME PIC X(20) at CSUSR01Y.cpy:20, compared at :223")
                    .isEqualTo(SEC_USR_LNAME_LENGTH)
                    .isEqualTo(SecUserRecord.SEC_USR_LNAME_LENGTH);
            assertThat(UserUpdateRequest.PASSWD_LENGTH)
                    .as("SEC-USR-PWD PIC X(08) at CSUSR01Y.cpy:21, compared at :227")
                    .isEqualTo(SEC_USR_PWD_LENGTH)
                    .isEqualTo(SecUserRecord.SEC_USR_PWD_LENGTH);
            assertThat(UserUpdateRequest.USRTYPE_LENGTH)
                    .as("SEC-USR-TYPE PIC X(01) at CSUSR01Y.cpy:22, compared at :231")
                    .isEqualTo(SEC_USR_TYPE_LENGTH)
                    .isEqualTo(SecUserRecord.SEC_USR_TYPE_LENGTH);
        }

        @Test
        @DisplayName("the record those five reach is 80 bytes at offsets 0, 8, 28, 48, 56 and 57")
        void theSecurityRecordGeometryIsUnchanged() {
            assertThat(SecUserRecord.RECORD_LENGTH)
                    .as("SEC-USER-DATA closes at 80 bytes: 8 + 20 + 20 + 8 + 1 + 23")
                    .isEqualTo(80);
            assertThat(List.of(SecUserRecord.SEC_USR_ID_OFFSET,
                    SecUserRecord.SEC_USR_FNAME_OFFSET,
                    SecUserRecord.SEC_USR_LNAME_OFFSET,
                    SecUserRecord.SEC_USR_PWD_OFFSET,
                    SecUserRecord.SEC_USR_TYPE_OFFSET,
                    SecUserRecord.SEC_USR_FILLER_OFFSET))
                    .as("the offsets the five payload members are compared at, FILLER included")
                    .containsExactly(0, 8, 28, 48, 56, 57);
            assertThat(SecUserRecord.SEC_USR_FILLER_OFFSET + SecUserRecord.SEC_USR_FILLER_LENGTH)
                    .as("FILLER X(23) is emitted, or every downstream offset is wrong")
                    .isEqualTo(SecUserRecord.RECORD_LENGTH);

            // A payload value at its declared width needs neither padding nor truncation to become the
            // stored field, which is what makes the comparison at :227 a plain equality of equals.
            byte[] stored = SecUserRecord.encode(SecUserRecord.of("USER0001",
                    codec().movePicX("LAWRENCE", SEC_USR_FNAME_LENGTH),
                    codec().movePicX("THOMAS", SEC_USR_LNAME_LENGTH),
                    NOT_A_REAL_PASSWORD,
                    UserUpdateRequest.USER_TYPE_USER,
                    MAP_CHARSET), MAP_CHARSET);
            assertThat(stored).hasSize(SecUserRecord.RECORD_LENGTH);
            assertThat(SecUserRecord.decode(stored, MAP_CHARSET).secUsrPwd())
                    .as("and it round-trips byte for byte, plaintext included")
                    .isEqualTo(NOT_A_REAL_PASSWORD)
                    .hasSize(UserUpdateRequest.PASSWD_LENGTH);
        }
    }

    // =================================================================================================
    // 4. METADATA IS NOT PAYLOAD.
    //
    // Each screen field contributes four items to the symbolic map and only one of them is data. xxxL
    // is the length CICS reports and the cursor carrier - COUSR02C.cbl:184, :190, :196, :202 and :208
    // each MOVE -1 into one - xxxF is the flag byte and xxxA is its attribute view. None of the three
    // is a payload member, and neither is any FILLER.
    // =================================================================================================

    @Nested
    @DisplayName("Metadata - xxxL, xxxF, xxxA and every FILLER stay off the wire")
    class MetadataStaysOffTheWire {

        @Test
        @DisplayName("the serialised payload carries exactly the fifteen expected member names")
        void theWireCarriesOnlyTheDeclaredMembers() {
            assertThat(jsonMembersOf(populatedRequest()))
                    .containsExactlyInAnyOrderElementsOf(EXPECTED_JSON_MEMBERS)
                    .hasSize(COMPONENT_COUNT);
        }

        @ParameterizedTest(name = "{0}L, {0}F and {0}A are absent")
        @ValueSource(strings = {"TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME",
            "USRIDIN", "FNAME", "LNAME", "PASSWD", "USRTYPE", "ERRMSG"})
        @DisplayName("no length, flag or attribute item appears under any spelling")
        void noMetadataItemIsAMember(String screenField) {
            String payload = serialise(populatedRequest());
            Set<String> members = jsonMembersOf(populatedRequest());

            for (String suffix : List.of("L", "F", "A")) {
                String item = screenField + suffix;
                assertThat(payload)
                        .as("%s is metadata: %s", item, suffix.equals("L")
                                ? "the length CICS reports, and the cursor carrier"
                                : "an attribute byte the terminal reads")
                        .doesNotContain(item);
                assertThat(members).doesNotContain(item, item.toLowerCase(Locale.ROOT));
            }
        }

        @ParameterizedTest(name = "the output view's {0} items are absent")
        @ValueSource(strings = {"C", "P", "H", "V", "O"})
        @DisplayName("nothing from the group-level COUSR2AO redefinition reaches this payload")
        void noOutputViewItemIsAMember(String suffix) {
            // COUSR02.CPY:91 declares 01 COUSR2AO REDEFINES COUSR2AI, whose per-field items are
            // xxxC colour, xxxP programmed symbols, xxxH highlight, xxxV validation and xxxO data.
            // They are UserUpdateResponse's projection, not this one's - which is also why the
            // group-level REDEFINES is excluded from SYMBOLIC_MAP_LAYOUT.
            String payload = serialise(populatedRequest());
            for (String screenField : SCREEN_FIELDS) {
                assertThat(payload).doesNotContain(screenField + suffix);
            }
            assertThat(payload).doesNotContain(SYMBOLIC_MAP_OUTPUT);
            assertThat(COPYBOOK_REDEFINES_TOTAL - PER_FIELD_REDEFINES_LINES.size())
                    .as("thirteen REDEFINES in the copybook, twelve of them per-field; the "
                            + "thirteenth is the group view at line 91")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("neither the TIOAPFX prefix nor any per-field filler is exposed")
        void noFillerIsAMember() {
            Set<String> members = jsonMembersOf(populatedRequest());

            assertThat(members).noneSatisfy(member ->
                    assertThat(member.toLowerCase(Locale.ROOT)).contains("filler"));
            assertThat(serialise(populatedRequest())).doesNotContain("FILLER", "TIOAPFX");
            assertThat(TIOAPFX_PREFIX_LENGTH + DFHMDF_NAMED * ATTRIBUTE_FILLER_LENGTH)
                    .as("sixty bytes of the symbolic map are filler, and none of them is a member")
                    .isEqualTo(60);
        }

        @Test
        @DisplayName("the read-through predicates are withheld, so a payload cannot contradict itself")
        void theDerivedPredicatesAreWithheld() throws NoSuchMethodException {
            Set<String> members = jsonMembersOf(populatedRequest());

            assertThat(members).doesNotContain("hasNavigationContext", "contextIsEnter",
                    "contextIsReenter", "navigationContextPresent", "enter", "reenter");
            for (String name : List.of("hasNavigationContext", "contextIsEnter", "contextIsReenter")) {
                Method method = UserUpdateRequest.class.getDeclaredMethod(name);
                assertThat(method.getReturnType()).isEqualTo(boolean.class);
                assertThat(name)
                        .as("%s is deliberately not bean-accessor shaped: a record method named "
                                + "getXxx or isXxx would be collected as an extra JSON property, "
                                + "putting a value on the wire the canonical constructor cannot "
                                + "accept back", name)
                        .doesNotStartWith("get")
                        .doesNotStartWith("is");
            }
        }

        @Test
        @DisplayName("the error highlight is metadata too, and applies only in the re-enter state")
        void theErrorHighlightIsMetadataAndReenterOnly() {
            // app/cpy/CSSETATY.cpy moves DFHRED and an asterisk onto a failed field, but only when
            // the program is in REENTER state. That is a presentation decision about the xxxC and
            // xxxO items of the OUTPUT view, so nothing about it belongs on this payload - which is
            // why the assertion below is that the highlight exists, is driven by the context flag,
            // and never appears as a member.
            FieldAttributeSetter.FieldHighlight onFirstEntry =
                    FieldAttributeSetter.resolveFromFlags(true, true, false);
            FieldAttributeSetter.FieldHighlight onReentry =
                    FieldAttributeSetter.resolveFromFlags(true, true, true);

            assertThat(onFirstEntry.untouched())
                    .as("first entry paints no error, because nothing has been keyed yet")
                    .isTrue();
            assertThat(onReentry.untouched()).isFalse();
            assertThat(onReentry.colourItemValue())
                    .as("DFHRED, from the absent-but-reproduced DFHBMSCA")
                    .isEqualTo(BmsAttributes.DFHRED);
            assertThat(onReentry.outputItemValue()).isEqualTo(FieldAttributeSetter.ASTERISK);

            Set<String> members = jsonMembersOf(populatedRequest());
            assertThat(members).doesNotContain("highlight", "attribute", "colour", "color");
        }
    }

    // =================================================================================================
    // 5. VALIDATION STOPS AT LENGTH.
    //
    // COUSR02C answers a blank field with a specific message and re-sends the screen; it never refuses
    // the interaction. A presence constraint here would replace five distinct messages with one generic
    // failure and would skip the MOVE -1 cursor placement that accompanies each, so no such constraint
    // may exist on any member.
    // =================================================================================================

    @Nested
    @DisplayName("Validation - @Size maxima only, never a presence or format constraint")
    class ValidationConstraints {

        @Test
        @DisplayName("carries thirteen @Size constraints and no other constraint at all")
        void carriesOnlySizeConstraints() {
            int sized = 0;
            for (RecordComponent component : UserUpdateRequest.class.getRecordComponents()) {
                for (Annotation annotation : component.getAccessor().getAnnotations()) {
                    assertThat(annotation.annotationType().getName())
                            .as("%s carries a constraint COUSR02C does not perform. Every blank "
                                    + "field yields a screen with a message - a 200 - and never a "
                                    + "400: see COUSR02C.cbl:180, :186, :192, :198 and :204",
                                    component.getName())
                            .doesNotContain("NotBlank")
                            .doesNotContain("NotNull")
                            .doesNotContain("NotEmpty")
                            .doesNotContain("Pattern")
                            .doesNotContain("Email")
                            .doesNotContain("Digits")
                            .doesNotContain("AssertTrue");
                    if (annotation instanceof Size) {
                        sized++;
                    }
                }
            }
            assertThat(sized)
                    .as("the twelve screen fields plus the AID token. The communication area and the "
                            + "extension group validate themselves at construction and need no "
                            + "annotation")
                    .isEqualTo(DFHMDF_NAMED + 1);
        }

        @Test
        @DisplayName("each @Size(max) is the width its symbolic-map item declares")
        void eachSizeMaximumEqualsTheDeclaredWidth() {
            RecordComponent[] components = UserUpdateRequest.class.getRecordComponents();
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                Size size = components[index].getAccessor().getAnnotation(Size.class);

                assertThat(size)
                        .as("%s must be width-constrained", components[index].getName())
                        .isNotNull();
                assertThat(size.max())
                        .as("%s projects %s PIC X(%d)", components[index].getName(),
                                SYMBOLIC_MAP_ITEMS.get(index), DECLARED_WIDTHS.get(index))
                        .isEqualTo(DECLARED_WIDTHS.get(index));
                assertThat(size.min())
                        .as("no lower bound: a shorter value is what a partly keyed screen sends")
                        .isZero();
            }
            assertThat(components[DFHMDF_NAMED].getAccessor().getAnnotation(Size.class))
                    .as("the communication area is a typed object, not a character field")
                    .isNull();
            assertThat(components[DFHMDF_NAMED + 1].getAccessor().getAnnotation(Size.class).max())
                    .as("the AID token is CCARD-AID PIC X(5) wide")
                    .isEqualTo(UserUpdateRequest.AID_LENGTH)
                    .isEqualTo(PfKeyResolver.AID_TOKEN_LENGTH);
            assertThat(components[DFHMDF_NAMED + 2].getAccessor().getAnnotation(Size.class))
                    .as("the extension group enforces its own widths in its constructor")
                    .isNull();
        }

        @Test
        @DisplayName("a wholly blank instance is valid, because the program answers blanks with text")
        void aBlankInstanceIsValid() {
            UserUpdateRequest blank = requestOf(blankMapValues(), NavigationContext.empty(), "", null);

            assertThat(validate(blank))
                    .as("five guards answer a blank field with a message and re-send; none rejects")
                    .isEmpty();
            assertThat(mapValuesOf(blank)).allSatisfy(value -> assertThat(value).isEmpty());
        }

        @Test
        @DisplayName("a null in every position is valid too, so nothing precedes the program's own test")
        void aNullInEveryPositionIsValid() {
            UserUpdateRequest absent = requestOf(nullMapValues(), null, null, null);

            assertThat(validate(absent)).isEmpty();
            assertThat(mapValuesOf(absent)).allSatisfy(value -> assertThat(value).isNull());
            assertThat(absent.navigationContext())
                    .as("EIBCALEN = 0 is a handled input state, COUSR02C.cbl:90-92")
                    .isNull();
            assertThat(absent.aid()).isNull();
            assertThat(absent.cu02Info())
                    .as("the one component that is normalised rather than carried: a PIC X group has "
                            + "no absent state, so null becomes the VALUE-clause image")
                    .isEqualTo(UserUpdateRequest.Cu02Info.initial());
        }

        @ParameterizedTest(name = "{0} accepts {1} characters and refuses {2}")
        @CsvSource({
            "trnName,  4,  5",
            "title01, 40, 41",
            "curDate,  8,  9",
            "pgmName,  8,  9",
            "title02, 40, 41",
            "curTime,  8,  9",
            "usrIdIn,  8,  9",
            "fName,   20, 21",
            "lName,   20, 21",
            "passwd,   8,  9",
            "usrType,  1,  2",
            "errMsg,  78, 79"})
        @DisplayName("one character over the declared width is exactly one violation, on that member")
        void oneCharacterTooManyIsOneViolation(String member, int width, int overWidth) {
            assertThat(validate(withMapValue(member, "V".repeat(width))))
                    .as("%s accepts exactly its declared width", member)
                    .isEmpty();

            Set<ConstraintViolation<UserUpdateRequest>> violations =
                    validate(withMapValue(member, "V".repeat(overWidth)));

            assertThat(violations)
                    .as("%s at %d characters is one violation and nothing else", member, overWidth)
                    .hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath().toString())
                    .isEqualTo(member);
            assertThat(violations.iterator().next().getConstraintDescriptor().getAnnotation())
                    .isInstanceOf(Size.class);
        }

        @Test
        @DisplayName("an over-wide AID token is refused as well, and two failures report as two")
        void theAidTokenIsConstrainedToo() {
            UserUpdateRequest overWideAid = requestOf(populatedMapValues(), populatedContext(),
                    "PFK011", populatedCu02Info());

            assertThat(validate(overWideAid)).hasSize(1);
            assertThat(validate(overWideAid).iterator().next().getPropertyPath().toString())
                    .isEqualTo("aid");

            List<String> values = new ArrayList<>(populatedMapValues());
            values.set(MAP_MEMBERS.indexOf("usrType"), "AU");
            assertThat(validate(requestOf(values, populatedContext(), "PFK011",
                    populatedCu02Info())))
                    .as("two independent over-widths are two violations, not one aggregated failure")
                    .hasSize(2);
        }

        @Test
        @DisplayName("SPACES and LOW-VALUES are both carried, distinctly, and neither is coerced")
        void spacesAndLowValuesAreBothCarried() {
            // COUSR02C's five guards all read `= SPACES OR LOW-VALUES` - one condition over two
            // representations. A field the terminal never transmitted arrives as LOW-VALUES, which
            // null models; one transmitted empty arrives as SPACES, which a run of spaces models. The
            // program treats them identically, so this payload must carry both and reject neither -
            // and must not silently turn one into the other.
            UserUpdateRequest spaces = requestOf(spaceFilledMapValues(), NavigationContext.empty(),
                    PfKeyResolver.AidKey.ENTER.token(), null);
            UserUpdateRequest lowValues = requestOf(nullMapValues(), NavigationContext.empty(),
                    PfKeyResolver.AidKey.ENTER.token(), null);

            assertThat(validate(spaces)).isEmpty();
            assertThat(validate(lowValues)).isEmpty();
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                assertThat(mapValuesOf(spaces).get(index))
                        .as("%s arrives as its declared width in spaces, untrimmed and not null",
                                MAP_MEMBERS.get(index))
                        .isNotNull()
                        .hasSize(DECLARED_WIDTHS.get(index))
                        .isBlank();
                assertThat(mapValuesOf(lowValues).get(index))
                        .as("%s arrives absent, which is a different state", MAP_MEMBERS.get(index))
                        .isNull();
            }
            assertThat(spaces).isNotEqualTo(lowValues);

            // A LOW-VALUES field is genuinely low values, not spaces: a NUL-filled value of the
            // declared width is carried verbatim as well, because the terminal can send one.
            String nulFilled = "\u0000".repeat(UserUpdateRequest.USRIDIN_LENGTH);
            UserUpdateRequest nulKeyed = withMapValue("usrIdIn", nulFilled);
            assertThat(nulKeyed.usrIdIn())
                    .isEqualTo(nulFilled)
                    .hasSize(UserUpdateRequest.USRIDIN_LENGTH)
                    .isNotEqualTo(codec().movePicX("", UserUpdateRequest.USRIDIN_LENGTH));
            assertThat(validate(nulKeyed)).isEmpty();
        }

        @Test
        @DisplayName("the constructor validates nothing, so @Size at the boundary stays reachable")
        void theConstructorIsTotal() {
            // Throwing from the canonical constructor would abort deserialisation before Bean
            // Validation ever ran, which would make every @Size above unreachable and would replace a
            // well-formed constraint violation with a deserialisation failure. The one thing the
            // constructor does is substitute the extension group's initial image for a null.
            List<String> tooWide = new ArrayList<>(populatedMapValues());
            tooWide.set(MAP_MEMBERS.indexOf("trnName"), "OVERWIDE");

            UserUpdateRequest accepted = requestOf(tooWide, null, "OVERLONGAID", null);

            assertThat(accepted.trnName()).isEqualTo("OVERWIDE");
            assertThat(accepted.aid()).isEqualTo("OVERLONGAID");
            assertThat(accepted.cu02Info()).isEqualTo(UserUpdateRequest.Cu02Info.initial());
            assertThat(validate(accepted))
                    .as("the boundary reports both over-widths as constraint violations instead")
                    .hasSize(2);
        }
    }

    // =================================================================================================
    // 6. THE CHANGE-DETECTION SURFACE.
    //
    // COUSR02C.cbl:219-234 is four SEPARATE IF ... NOT = ... END-IF statements, each independently
    // setting USR-MODIFIED-YES. It is not an EVALUATE chain and not one combined condition, so any
    // subset of the four may fire and each of the four values has to be individually carried and
    // individually comparable. Which outcome each subset produces is UserUpdateControllerTest's
    // subject; that the four are separable at all is this file's.
    // =================================================================================================

    @Nested
    @DisplayName("Change detection - four independent comparisons, and no concurrency token")
    class ChangeDetectionSurface {

        @Test
        @DisplayName("exactly four members are compared, at the SEC-USER-DATA widths 20, 20, 8 and 1")
        void theFourComparedMembers() {
            assertThat(CHANGE_DETECTED_MEMBERS)
                    .as("FNAMEI at :219, LNAMEI at :223, PASSWDI at :227, USRTYPEI at :231")
                    .containsExactly("fName", "lName", "passwd", "usrType")
                    .allSatisfy(member -> assertThat(MAP_MEMBERS).contains(member));
            for (int index = 0; index < CHANGE_DETECTED_MEMBERS.size(); index++) {
                String member = CHANGE_DETECTED_MEMBERS.get(index);
                assertThat(PUBLISHED_WIDTHS.get(MAP_MEMBERS.indexOf(member)))
                        .as("%s is compared against a field of its own width, so the comparison is a "
                                + "plain equality of equal widths", member)
                        .isEqualTo(CHANGE_DETECTED_WIDTHS.get(index));
                assertThat(componentType(UserUpdateRequest.class, member))
                        .as("%s must be individually addressable, not folded into a collection",
                                member)
                        .isEqualTo(String.class);
            }
        }

        @Test
        @DisplayName("the identifier is the key, not an editable field, so it is not among the four")
        void theIdentifierIsTheKeyAndNotCompared() {
            assertThat(CHANGE_DETECTED_MEMBERS)
                    .as("COUSR02C.cbl:216 moves USRIDINI into SEC-USR-ID to re-read the record; it is "
                            + "never compared for change")
                    .doesNotContain("usrIdIn");
            assertThat(UserUpdateRequest.USRIDIN_LENGTH)
                    .as("and it is exactly the USRSEC key width, so the move needs no adjustment")
                    .isEqualTo(SecUserRecord.KEY_LENGTH);
            assertThat(MAP_MEMBERS.indexOf("usrIdIn"))
                    .as("it precedes all four, which is also the order the blank chain guards them in")
                    .isLessThan(MAP_MEMBERS.indexOf(CHANGE_DETECTED_MEMBERS.get(0)));
        }

        @Test
        @DisplayName("the four are independent: every subset of them is separately observable")
        void theFourComparisonsAreIndependentNotAChain() {
            // Sixteen payloads, one per subset of the four fields, each differing from the stored
            // record in exactly the members of its subset. If the four had been collapsed into a
            // single "changes" value, a first-match chain or a bit set, the subsets could not be told
            // apart - and modelling them as a chain would be a behaviour change, because COBOL runs
            // all four tests and performs the MOVE that follows each one that fires.
            SecUserRecord stored = SecUserRecord.of("USER0001", "LAWRENCE", "THOMAS",
                    NOT_A_REAL_PASSWORD, UserUpdateRequest.USER_TYPE_USER, MAP_CHARSET);

            for (int subset = 0; subset < 16; subset++) {
                List<String> values = new ArrayList<>(populatedMapValues());
                List<String> expectedDifferences = new ArrayList<>();
                if ((subset & 1) != 0) {
                    values.set(MAP_MEMBERS.indexOf("fName"),
                            codec().movePicX("LAWRENCIA", UserUpdateRequest.FNAME_LENGTH));
                    expectedDifferences.add("fName");
                }
                if ((subset & 2) != 0) {
                    values.set(MAP_MEMBERS.indexOf("lName"),
                            codec().movePicX("THOMSON", UserUpdateRequest.LNAME_LENGTH));
                    expectedDifferences.add("lName");
                }
                if ((subset & 4) != 0) {
                    values.set(MAP_MEMBERS.indexOf("passwd"), OTHER_NOT_A_REAL_PASSWORD);
                    expectedDifferences.add("passwd");
                }
                if ((subset & 8) != 0) {
                    values.set(MAP_MEMBERS.indexOf("usrType"), UserUpdateRequest.USER_TYPE_ADMIN);
                    expectedDifferences.add("usrType");
                }

                UserUpdateRequest keyed = requestOf(values, populatedContext(),
                        PfKeyResolver.AidKey.PFK05.token(), populatedCu02Info());
                List<String> observed = new ArrayList<>();
                if (!keyed.fName().equals(stored.secUsrFname())) {
                    observed.add("fName");
                }
                if (!keyed.lName().equals(stored.secUsrLname())) {
                    observed.add("lName");
                }
                if (!keyed.passwd().equals(stored.secUsrPwd())) {
                    observed.add("passwd");
                }
                if (!keyed.usrType().equals(stored.secUsrType())) {
                    observed.add("usrType");
                }

                assertThat(observed)
                        .as("subset %d: each of the four comparisons stands alone", subset)
                        .containsExactlyElementsOf(expectedDifferences);
                assertThat(validate(keyed))
                        .as("subset %d is a valid payload whatever it changed", subset)
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("the comparison is untrimmed, so trailing padding is significant")
        void theComparisonIsUntrimmed() {
            // SEC-USR-FNAME is X(20) and the screen field is X(20), so the stored value is
            // space-padded and the keyed value has to be too. A payload that trimmed would compare
            // "THOMAS" against "THOMAS              " and mark an unchanged record modified on every
            // save, which is a behaviour change with no error to show for it.
            SecUserRecord stored = SecUserRecord.of("USER0001", "LAWRENCE", "THOMAS",
                    NOT_A_REAL_PASSWORD, UserUpdateRequest.USER_TYPE_USER, MAP_CHARSET);

            UserUpdateRequest padded = withMapValue("lName",
                    codec().movePicX("THOMAS", UserUpdateRequest.LNAME_LENGTH));
            UserUpdateRequest trimmed = withMapValue("lName", "THOMAS");

            assertThat(padded.lName())
                    .hasSize(UserUpdateRequest.LNAME_LENGTH)
                    .isEqualTo(stored.secUsrLname());
            assertThat(trimmed.lName())
                    .as("a trimmed value is a different value, and the payload keeps it as sent")
                    .isEqualTo("THOMAS")
                    .isNotEqualTo(stored.secUsrLname());
        }

        @Test
        @DisplayName("no version, entity tag, revision or timestamp member exists (gate G43 is N/A)")
        void noConcurrencyTokenExists() {
            // COUSR02C has NO 9300-CHECK-CHANGE-IN-REC paragraph. Its update sequence is a keyed
            // READ ... UPDATE at :322-331 followed by REWRITE at :360-366, and the four comparisons at
            // :219-234 are change detection - they decide whether a rewrite is needed at all and
            // produce 'Please modify to update ...' at :239 when it is not. They are not a stale-read
            // check: they compare the screen against the freshly re-read record, not the freshly
            // re-read record against the one the screen was painted from.
            //
            // Gate G43 is therefore scoped to AccountUpdateService and CardUpdateService, whose
            // programs COACTUPC and COCRDUPC do contain that paragraph. It does not apply to this
            // package, and adding a version column here would additionally be a schema change, which
            // the plan forbids outright. This case exists so no later reader "adds the missing
            // optimistic concurrency".
            List<String> forbidden = List.of("version", "etag", "revision", "timestamp", "updatedat",
                    "lastmodified", "sequence", "generation", "rowversion", "concurrency");

            List<String> names = new ArrayList<>();
            for (Class<?> owner : List.of(UserUpdateRequest.class,
                    UserUpdateRequest.Cu02Info.class)) {
                for (RecordComponent component : owner.getRecordComponents()) {
                    names.add(component.getName());
                }
                for (Method method : owner.getDeclaredMethods()) {
                    names.add(method.getName());
                }
            }

            for (String name : names) {
                for (String marker : forbidden) {
                    assertThat(name.toLowerCase(Locale.ROOT))
                            .as("%s suggests a concurrency token; COUSR02C has none", name)
                            .doesNotContain(marker);
                }
            }
            assertThat(jsonMembersOf(populatedRequest())).hasSize(COMPONENT_COUNT);
        }
    }

    // =================================================================================================
    // 7. CDEMO-CU02-INFO - THE 34-BYTE EXTENSION.
    //
    // COUSR02C.cbl:50-58 appends six items to CARDDEMO-COMMAREA, widening the area line 94 restores
    // and line 137 hands back from 160 bytes to 194. The group is NOT in app/cpy/COCOM01Y.cpy: it
    // belongs to this program alone, which is why it is a member of this payload rather than a widening
    // of NavigationContext - that type is shared by all seventeen controllers and must stay at 160.
    // =================================================================================================

    @Nested
    @DisplayName("CDEMO-CU02-INFO - six items, 34 bytes, and a 194-byte communication area")
    class Cu02InfoExtension {

        @Test
        @DisplayName("the six items are declared with the COBOL names COUSR02C.cbl:51-58 spells")
        void theSixItemsAreDeclared() {
            List<String> declared =
                    Arrays.stream(UserUpdateRequest.Cu02Info.class.getRecordComponents())
                            .map(RecordComponent::getName)
                            .toList();

            assertThat(declared)
                    .as("the declared spellings are the COBOL abbreviations, not expanded English: "
                            + "pageNum for -PAGE-NUM, nextPageFlg for -NEXT-PAGE-FLG, usrSelFlg for "
                            + "-USR-SEL-FLG")
                    .containsExactly("usridFirst", "usridLast", "pageNum", "nextPageFlg",
                            "usrSelFlg", "usrSelected");
            assertThat(UserUpdateRequest.Cu02Info.initial().fieldImages().keySet())
                    .as("and each maps to the CDEMO-CU02-prefixed item name, in declaration order")
                    .containsExactlyElementsOf(CU02_ITEM_NAMES);
            assertThat(CU02_ITEM_NAMES)
                    .allSatisfy(name -> assertThat(name).startsWith("CDEMO-CU02-"));
        }

        @Test
        @DisplayName("the group is 34 bytes: 8 + 8 + 8 + 1 + 1 + 8")
        void theGroupIsThirtyFourBytes() {
            assertThat(List.of(UserUpdateRequest.Cu02Info.USRID_FIRST_LENGTH,
                    UserUpdateRequest.Cu02Info.USRID_LAST_LENGTH,
                    UserUpdateRequest.Cu02Info.PAGE_NUM_DIGITS,
                    UserUpdateRequest.Cu02Info.NEXT_PAGE_FLG_LENGTH,
                    UserUpdateRequest.Cu02Info.USR_SEL_FLG_LENGTH,
                    UserUpdateRequest.Cu02Info.USR_SELECTED_LENGTH))
                    .containsExactlyElementsOf(CU02_ITEM_WIDTHS);
            assertThat(UserUpdateRequest.Cu02Info.LENGTH)
                    .isEqualTo(CU02_INFO_LENGTH)
                    .isEqualTo(CU02_ITEM_WIDTHS.stream().mapToInt(Integer::intValue).sum());

            // The image the group occupies is those 34 characters, and no more.
            String image = String.join("", populatedCu02Info().fieldImages().values());
            assertThat(image).hasSize(CU02_INFO_LENGTH);
            assertThat(codec().encodeImage(image, "CDEMO-CU02-INFO"))
                    .as("encoded through the named code page (B8), it is 34 bytes")
                    .hasSize(CU02_INFO_LENGTH);
        }

        @Test
        @DisplayName("NavigationContext stays exactly 160 bytes, and the sections account for all of them")
        void theCommareaIsNotWidened() {
            assertThat(NavigationContext.COMMAREA_LENGTH)
                    .as("app/cpy/COCOM01Y.cpy:19-44 - shared by all seventeen controllers, so "
                            + "widening it for one program's private group would change the area "
                            + "every other program receives")
                    .isEqualTo(COMMAREA_LENGTH);
            assertThat(NavigationContext.GENERAL_INFO_LENGTH
                    + NavigationContext.CUSTOMER_INFO_LENGTH
                    + NavigationContext.ACCOUNT_INFO_LENGTH
                    + NavigationContext.CARD_INFO_LENGTH
                    + NavigationContext.MORE_INFO_LENGTH)
                    .as("34 general + 84 customer + 12 account + 16 card + 14 more = 160")
                    .isEqualTo(COMMAREA_LENGTH);
            assertThat(NavigationContext.MORE_INFO_LENGTH)
                    .as("CDEMO-LAST-MAP and CDEMO-LAST-MAPSET are PIC X(7) each, not X(8)")
                    .isEqualTo(NavigationContext.LAST_MAP_LENGTH
                            + NavigationContext.LAST_MAPSET_LENGTH)
                    .isEqualTo(14);
            assertThat(NavigationContext.empty().toFixedWidth(codec()))
                    .as("proved through the codec rather than asserted from the constant alone")
                    .hasSize(COMMAREA_LENGTH);

            assertThat(componentType(UserUpdateRequest.class, "cu02Info"))
                    .as("the extension is a member of its own, never folded into the shared area")
                    .isEqualTo(UserUpdateRequest.Cu02Info.class);
            assertThat(Arrays.stream(NavigationContext.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList())
                    .as("and nothing CDEMO-CU02-shaped has leaked into the shared type")
                    .doesNotContain("pageNum", "nextPageFlg", "usrSelFlg", "usrSelected",
                            "usridFirst", "usridLast");
        }

        @Test
        @DisplayName("160 plus 34 is the 194-byte area line 94 restores and line 137 returns")
        void theAreaThisProgramCarriesIsOneHundredAndNinetyFour() {
            byte[] commarea = populatedContext().toFixedWidth(codec());
            byte[] extension = codec().encodeImage(
                    String.join("", populatedCu02Info().fieldImages().values()), "CDEMO-CU02-INFO");

            assertThat(commarea).hasSize(COMMAREA_LENGTH);
            assertThat(extension).hasSize(CU02_INFO_LENGTH);
            assertThat(commarea.length + extension.length)
                    .as("EXEC CICS RETURN TRANSID(CU02) COMMAREA(CARDDEMO-COMMAREA) at "
                            + "COUSR02C.cbl:135-138 hands back all 194")
                    .isEqualTo(CU02_COMMAREA_LENGTH)
                    .isEqualTo(194);
        }

        @Test
        @DisplayName("initial() is what the VALUE clauses leave: spaces, zero, and 'N'")
        void initialIsTheValueClauseState() {
            UserUpdateRequest.Cu02Info initial = UserUpdateRequest.Cu02Info.initial();

            assertThat(initial.usridFirst())
                    .hasSize(UserUpdateRequest.Cu02Info.USRID_FIRST_LENGTH).isBlank();
            assertThat(initial.usridLast())
                    .hasSize(UserUpdateRequest.Cu02Info.USRID_LAST_LENGTH).isBlank();
            assertThat(initial.pageNum())
                    .as("CDEMO-CU02-PAGE-NUM declares no VALUE, so a cold start sees zero")
                    .isZero();
            assertThat(initial.nextPageFlg())
                    .as("VALUE 'N' at COUSR02C.cbl:54 - not a convention chosen here")
                    .isEqualTo(NEXT_PAGE_NO)
                    .hasSize(UserUpdateRequest.Cu02Info.NEXT_PAGE_FLG_LENGTH);
            assertThat(initial.usrSelFlg())
                    .hasSize(UserUpdateRequest.Cu02Info.USR_SEL_FLG_LENGTH).isBlank();
            assertThat(initial.usrSelected())
                    .hasSize(UserUpdateRequest.Cu02Info.USR_SELECTED_LENGTH).isBlank();
            assertThat(initial.fieldImages().get("CDEMO-CU02-PAGE-NUM"))
                    .as("and the group's image renders it as PIC 9(08), zero-filled")
                    .isEqualTo("00000000");
            assertThat(initial).isEqualTo(UserUpdateRequest.Cu02Info.initial());
        }

        @Test
        @DisplayName("a null group becomes initial(), because a PIC X group has no absent state")
        void aNullGroupIsNormalised() {
            assertThat(requestOf(populatedMapValues(), populatedContext(), "ENTER", null).cu02Info())
                    .isEqualTo(UserUpdateRequest.Cu02Info.initial());
            assertThat(requestOf(populatedMapValues(), populatedContext(), "ENTER",
                    populatedCu02Info()).cu02Info())
                    .as("and a supplied group is carried through untouched")
                    .isEqualTo(populatedCu02Info());
        }

        @Test
        @DisplayName("an item that was not supplied becomes its declared width in spaces, not null")
        void anAbsentItemBecomesSpaces() {
            UserUpdateRequest.Cu02Info sparse =
                    new UserUpdateRequest.Cu02Info(null, null, 0, null, null, null);

            assertThat(sparse.usridFirst())
                    .hasSize(UserUpdateRequest.Cu02Info.USRID_FIRST_LENGTH).isBlank();
            assertThat(sparse.usridLast())
                    .hasSize(UserUpdateRequest.Cu02Info.USRID_LAST_LENGTH).isBlank();
            assertThat(sparse.nextPageFlg())
                    .as("a null flag is one space, which satisfies neither 88-level")
                    .isEqualTo(NEITHER_PAGE_FLAG);
            assertThat(sparse.usrSelFlg()).isEqualTo(NEITHER_PAGE_FLAG);
            assertThat(sparse.usrSelected())
                    .hasSize(UserUpdateRequest.Cu02Info.USR_SELECTED_LENGTH).isBlank();
            assertThat(String.join("", sparse.fieldImages().values()))
                    .hasSize(CU02_INFO_LENGTH);
        }

        @Test
        @DisplayName("an over-wide item is truncated on the right, as a PIC X MOVE is (B11)")
        void anOverWideItemIsTruncatedOnTheRight() {
            UserUpdateRequest.Cu02Info wide = new UserUpdateRequest.Cu02Info("USER00010",
                    "USER00509", 0, "YES", "UPD", "USER00019");

            assertThat(wide.usridFirst())
                    .hasSize(UserUpdateRequest.Cu02Info.USRID_FIRST_LENGTH)
                    .isEqualTo("USER0001");
            assertThat(wide.usridLast()).isEqualTo("USER0050");
            assertThat(wide.nextPageFlg())
                    .as("'YES' into a PIC X(01) receiver keeps the leading character")
                    .isEqualTo(NEXT_PAGE_YES);
            assertThat(wide.usrSelFlg()).isEqualTo("U");
            assertThat(wide.usrSelected()).isEqualTo("USER0001");
            assertThat(String.join("", wide.fieldImages().values())).hasSize(CU02_INFO_LENGTH);
        }

        @Test
        @DisplayName("CDEMO-CU02-PAGE-NUM is PIC 9(08): a negative page number has no representation")
        void aNegativePageNumberIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new UserUpdateRequest.Cu02Info("", "", -1, NEXT_PAGE_NO, "", ""))
                    .withMessageContaining("CDEMO-CU02-PAGE-NUM")
                    .withMessageContaining("unsigned");
        }

        @Test
        @DisplayName("a page number needing more than eight digits is refused, not silently truncated")
        void anOverWidePageNumberIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new UserUpdateRequest.Cu02Info("", "", 100_000_000,
                            NEXT_PAGE_NO, "", ""))
                    .withMessageContaining("CDEMO-CU02-PAGE-NUM");
            assertThat(new UserUpdateRequest.Cu02Info("", "", 99_999_999, NEXT_PAGE_NO, "", "")
                    .fieldImages().get("CDEMO-CU02-PAGE-NUM"))
                    .as("the widest value the picture can hold is accepted and fills all eight digits")
                    .isEqualTo("99999999");
        }

        @ParameterizedTest(name = "page {0} renders as {1}")
        @CsvSource({"0, 00000000", "1, 00000001", "7, 00000007", "10, 00000010",
            "12345678, 12345678"})
        @DisplayName("the page number is integral and zero-filled to eight digits, never floating point")
        void thePageNumberIsIntegralAndZeroFilled(int page, String image) {
            UserUpdateRequest.Cu02Info info =
                    new UserUpdateRequest.Cu02Info("", "", page, NEXT_PAGE_NO, "", "");

            assertThat(info.pageNum()).isEqualTo(page);
            assertThat(info.fieldImages().get("CDEMO-CU02-PAGE-NUM"))
                    .isEqualTo(image)
                    .hasSize(UserUpdateRequest.Cu02Info.PAGE_NUM_DIGITS);
            assertThat(componentType(UserUpdateRequest.Cu02Info.class, "pageNum"))
                    .as("gate G22: PIC 9(08) is scale-free, so it is an int and never a double")
                    .isEqualTo(int.class);
        }

        @ParameterizedTest(name = "flag \"{0}\": yes={1}, no={2}")
        @CsvSource({"Y, true, false", "N, false, true", "' ', false, false", "A, false, false"})
        @DisplayName("both 88-levels are driven in both directions, plus a value that is neither")
        void bothPagingStatesAreDriven(String flag, boolean yes, boolean no) {
            // 88 NEXT-PAGE-YES VALUE 'Y' at COUSR02C.cbl:55 and 88 NEXT-PAGE-NO VALUE 'N' at :56.
            // The source declares no third condition over CDEMO-CU02-NEXT-PAGE-FLG, so a value that
            // is neither satisfies neither - the two are not each other's negation, and a reader who
            // treats "not YES" as "NO" has changed the meaning of a blank flag.
            UserUpdateRequest.Cu02Info info =
                    new UserUpdateRequest.Cu02Info("", "", 0, flag, "", "");

            assertThat(UserUpdateRequest.Cu02Info.NEXT_PAGE_YES.equals(info.nextPageFlg()))
                    .as("NEXT-PAGE-YES for %s", flag)
                    .isEqualTo(yes);
            assertThat(UserUpdateRequest.Cu02Info.NEXT_PAGE_NO.equals(info.nextPageFlg()))
                    .as("NEXT-PAGE-NO for %s", flag)
                    .isEqualTo(no);
            assertThat(yes && no)
                    .as("no value satisfies both conditions")
                    .isFalse();
            assertThat(info.nextPageFlg())
                    .hasSize(UserUpdateRequest.Cu02Info.NEXT_PAGE_FLG_LENGTH);
        }

        @Test
        @DisplayName("the two literals are the copybook's, and 'N' is also the field's own VALUE")
        void thePagingLiteralsAreTheSources() {
            assertThat(UserUpdateRequest.Cu02Info.NEXT_PAGE_YES).isEqualTo(NEXT_PAGE_YES);
            assertThat(UserUpdateRequest.Cu02Info.NEXT_PAGE_NO).isEqualTo(NEXT_PAGE_NO);
            assertThat(UserUpdateRequest.Cu02Info.initial().nextPageFlg())
                    .as("VALUE 'N' is the declared default, so a cold start reads NEXT-PAGE-NO")
                    .isEqualTo(UserUpdateRequest.Cu02Info.NEXT_PAGE_NO);
            assertThat(NEXT_PAGE_YES).isNotEqualTo(NEXT_PAGE_NO);
        }

        @Test
        @DisplayName("the selection flag and the selected identifier are carried, paging members and all")
        void theSelectionItemsAreCarried() {
            // This is the one item the program reads: COUSR02C.cbl:99-100 tests
            // CDEMO-CU02-USR-SELECTED for SPACES AND LOW-VALUES and :101-102 moves it into USRIDINI.
            // The other five are restored at :94 and handed back unchanged, which is behaviour: the
            // area COUSR00C filled - its own CDEMO-CU00-INFO over the same 34 bytes - has to survive
            // the round trip.
            //
            // The hand-over is explicit in the list program. COUSR00C.cbl:187-188 guards on both
            // CDEMO-CU00-USR-SEL-FLG and CDEMO-CU00-USR-SELECTED being non-blank, :189-191 evaluates
            // the flag and accepts either 'U' or 'u', and :192-199 moves 'COUSR02C' into
            // CDEMO-TO-PROGRAM and transfers with COMMAREA(CARDDEMO-COMMAREA) - the area that carries
            // these 34 bytes. That is why an update screen carries paging members it never pages with:
            // they belong to the list screen that filled them. The oddity is preserved, not pruned
            // (B5).
            UserUpdateRequest.Cu02Info handedOver = populatedCu02Info();

            assertThat(handedOver.usrSelFlg())
                    .hasSize(UserUpdateRequest.Cu02Info.USR_SEL_FLG_LENGTH)
                    .isEqualTo("U");
            assertThat(handedOver.usrSelected())
                    .as("the row COUSR00C marked, which becomes USRIDINI at :101-102")
                    .isEqualTo("USER0001")
                    .hasSize(UserUpdateRequest.Cu02Info.USR_SELECTED_LENGTH)
                    .isNotBlank();
            assertThat(handedOver.usridFirst()).isEqualTo("USER0001");
            assertThat(handedOver.usridLast()).isEqualTo("USER0050");
            assertThat(handedOver.pageNum()).isEqualTo(1);

            // A blank selection is the other state of that test, and is equally carried.
            assertThat(UserUpdateRequest.Cu02Info.initial().usrSelected()).isBlank();
        }

        @Test
        @DisplayName("the group is CDEMO-CU02 prefixed, so it is not the CU00 or CU03 group")
        void theGroupIsThisProgramsOwn() {
            // COUSR00C.cbl:67-75 declares CDEMO-CU00-INFO and COUSR03C.cbl:50-58 declares
            // CDEMO-CU03-INFO over the same 34 bytes with the same six shapes. They are three
            // separate declarations of one span, not one shared type, which is why each program models
            // its own and why the names differ program by program. COSGN00C and COUSR01C declare no
            // such group at all.
            assertThat(UserUpdateRequest.Cu02Info.class.getSimpleName()).isEqualTo("Cu02Info");
            assertThat(UserUpdateRequest.Cu02Info.class.getEnclosingClass())
                    .as("declared on the payload that owns it, not on a shared type")
                    .isEqualTo(UserUpdateRequest.class);
            assertThat(CU02_ITEM_NAMES)
                    .allSatisfy(name -> assertThat(name)
                            .startsWith("CDEMO-CU02-")
                            .doesNotContain("CDEMO-CU00-")
                            .doesNotContain("CDEMO-CU03-"));
            assertThat(UserUpdateRequest.Cu02Info.LENGTH)
                    .as("the shapes are identical, which is why only the prefix distinguishes them")
                    .isEqualTo(CU02_INFO_LENGTH);
        }
    }

    // =================================================================================================
    // 8. CONVERSATION STATE TRAVELS IN THE PAYLOAD, NEVER IN A SESSION (gate G37, rule R6).
    //
    // CICS is pseudo-conversational: COUSR02C ends after painting a screen and is re-entered from
    // MAIN-PARA on the next key press, so the only state that survives a turn is what it handed back.
    // The Java form keeps that shape exactly - the communication area, the resolved key indication and
    // the extension group are payload members - which is what makes the endpoint stateless.
    // =================================================================================================

    @Nested
    @DisplayName("Conversation state - the commarea, the AID and the extension travel in the payload")
    class ConversationState {

        @Test
        @DisplayName("all three state carriers are payload members, so no session is ever needed")
        void theStateCarriersArePayloadMembers() {
            assertThat(jsonMembersOf(populatedRequest()))
                    .containsAll(STATE_MEMBERS);
            assertThat(populatedRequest().navigationContext()).isNotNull();
            assertThat(populatedRequest().aid()).isNotNull();
            assertThat(populatedRequest().cu02Info()).isNotNull();
        }

        @Test
        @DisplayName("no session, thread-local or static cache mechanism is reachable")
        void noServerSideStateMechanismIsReachable() {
            List<String> forbidden = List.of("HttpSession", "Session", "ThreadLocal", "Cache",
                    "RequestContextHolder", "ServletRequest", "Cookie", "Scope");

            for (Class<?> type : reachableTypes()) {
                for (String marker : forbidden) {
                    assertThat(type.getName())
                            .as("%s suggests %s; a static holder would be a session by another name "
                                    + "and would break request isolation", type.getName(), marker)
                            .doesNotContain(marker);
                }
            }
        }

        @Test
        @DisplayName("the communication area is carried through untouched, never widened or re-modelled")
        void theContextIsPassedThrough() {
            NavigationContext context = populatedContext();
            UserUpdateRequest request = requestOf(populatedMapValues(), context,
                    PfKeyResolver.AidKey.PFK05.token(), populatedCu02Info());

            assertThat(request.navigationContext())
                    .as("the same instance, not a copy and not a widened variant")
                    .isSameAs(context);
            assertThat(request.navigationContext().fromProgram())
                    .as("CDEMO-FROM-PROGRAM is what PF3 echoes as its target at COUSR02C.cbl:116-117")
                    .isEqualTo(codec().movePicX("COUSR00C", NavigationContext.FROM_PROGRAM_LENGTH));
            assertThat(request.navigationContext().isAdmin()).isTrue();
            assertThat(request.navigationContext().lastMap()).isEqualTo(MAP_NAME);
            assertThat(request.navigationContext().lastMapset()).isEqualTo(MAPSET_NAME);
            assertThat(request.navigationContext().toFixedWidth(codec()))
                    .hasSize(NavigationContext.COMMAREA_LENGTH);
        }

        @Test
        @DisplayName("an absent communication area is a handled state, not an error")
        void anAbsentContextIsTheColdStart() {
            UserUpdateRequest cold = requestOf(nullMapValues(), null,
                    PfKeyResolver.AidKey.ENTER.token(), null);

            assertThat(cold.hasNavigationContext())
                    .as("EIBCALEN = 0 at COUSR02C.cbl:90, which transfers to COSGN00C")
                    .isFalse();
            assertThat(cold.contextIsEnter()).isFalse();
            assertThat(cold.contextIsReenter()).isFalse();
            assertThat(populatedRequest().hasNavigationContext()).isTrue();
        }

        @ParameterizedTest(name = "context {0}: enter={1}, reenter={2}")
        @CsvSource({"0, true, false", "1, false, true", "2, false, false", "9, false, false"})
        @DisplayName("both 88-level states are driven, in both directions, plus digits that are neither")
        void bothContextStatesAreDriven(int pgmContext, boolean enter, boolean reenter) {
            // 88 CDEMO-PGM-ENTER VALUE 0 and 88 CDEMO-PGM-REENTER VALUE 1 over
            // CDEMO-PGM-CONTEXT PIC 9(01), COCOM01Y.cpy:29-31. The field can hold any digit, so the
            // two conditions are not each other's negation - which matters, because
            // COUSR02C.cbl:95 is written as IF NOT CDEMO-PGM-REENTER and takes its first-entry arm
            // for a context of 9 while neither 88-level holds.
            UserUpdateRequest request = requestOf(populatedMapValues(),
                    NavigationContext.empty().withPgmContext(pgmContext),
                    PfKeyResolver.AidKey.ENTER.token(), populatedCu02Info());

            assertThat(request.contextIsEnter()).as("CDEMO-PGM-ENTER for %d", pgmContext)
                    .isEqualTo(enter);
            assertThat(request.contextIsReenter()).as("CDEMO-PGM-REENTER for %d", pgmContext)
                    .isEqualTo(reenter);
            assertThat(request.hasNavigationContext()).isTrue();
            assertThat(!request.contextIsReenter())
                    .as("the first-entry arm at :95 is NOT CDEMO-PGM-REENTER, which is true for %d",
                            pgmContext)
                    .isEqualTo(!reenter);
        }

        @Test
        @DisplayName("the named context constants are the copybook's own values")
        void theContextConstantsAreTheCopybooks() {
            assertThat(NavigationContext.PGM_CONTEXT_ENTER)
                    .as("88 CDEMO-PGM-ENTER VALUE 0, COCOM01Y.cpy:30")
                    .isZero();
            assertThat(NavigationContext.PGM_CONTEXT_REENTER)
                    .as("88 CDEMO-PGM-REENTER VALUE 1, COCOM01Y.cpy:31")
                    .isEqualTo(1);
            assertThat(requestOf(populatedMapValues(), NavigationContext.empty().withPgmEnter(),
                    "ENTER", null).contextIsEnter()).isTrue();
            assertThat(requestOf(populatedMapValues(), NavigationContext.empty().withPgmReenter(),
                    "ENTER", null).contextIsReenter()).isTrue();
        }

        @ParameterizedTest(name = "aid = \"{0}\"")
        @ValueSource(strings = {"ENTER", "CLEAR", "PA1  ", "PA2  ", "PFK03", "PFK04", "PFK05",
            "PFK12"})
        @DisplayName("the AID arrives as a resolved five-character token, carried verbatim")
        void theAidIsCarriedAsAResolvedToken(String token) {
            // COUSR02C.cbl:108-131 dispatches on EIBAID: ENTER fetches the user, PF5 saves, PF3 saves
            // and then exits, PF4 clears, PF12 cancels and anything else is an invalid key. Both of
            // this screen's saving paths therefore turn on the AID, which is why it has to travel in
            // the payload - the paths themselves are UserUpdateControllerTest's subject. It is carried
            // as the resolved token rather than as a raw EIBAID byte, so the wire form stays a plain
            // string of the width CCARD-AID PIC X(5) declares.
            UserUpdateRequest request = requestOf(populatedMapValues(), populatedContext(), token,
                    populatedCu02Info());

            assertThat(request.aid())
                    .as("trailing spaces included: CCARD-AID-PA1 is VALUE 'PA1  '")
                    .isEqualTo(token)
                    .hasSize(PfKeyResolver.AID_TOKEN_LENGTH);
            assertThat(componentType(UserUpdateRequest.class, "aid"))
                    .as("a token, never a byte")
                    .isEqualTo(String.class);
            assertThat(validate(request)).isEmpty();
            assertThat(serialise(request)).contains("\"aid\":\"" + token + "\"");
        }

        @ParameterizedTest(name = "EIBAID {0} resolves to {1}")
        @CsvSource({"ENTER, ENTER", "PF3, PFK03", "PF4, PFK04", "PF5, PFK05", "PF12, PFK12",
            "PF17, PFK05"})
        @DisplayName("every key this program dispatches on has a resolver token to travel as")
        void everyDispatchedKeyResolves(String key, String expectedToken) {
            byte eibAid = switch (key) {
                case "ENTER" -> CicsAid.DFHENTER;
                case "PF3" -> CicsAid.DFHPF3;
                case "PF4" -> CicsAid.DFHPF4;
                case "PF5" -> CicsAid.DFHPF5;
                case "PF12" -> CicsAid.DFHPF12;
                case "PF17" -> CicsAid.DFHPF17;
                default -> throw new IllegalArgumentException("Unhandled key " + key);
            };

            Optional<PfKeyResolver.AidKey> resolved = PfKeyResolver.resolve(eibAid);

            assertThat(resolved).isPresent();
            assertThat(resolved.orElseThrow().token())
                    .as("PF17 folds onto PFK05 exactly as CSSTRPFY.cpy does, so a 3270 that sends the "
                            + "high range still reaches the save path")
                    .isEqualTo(expectedToken);
            assertThat(requestOf(populatedMapValues(), populatedContext(),
                    resolved.orElseThrow().token(), populatedCu02Info()).aid())
                    .isEqualTo(expectedToken);
        }

        @Test
        @DisplayName("an unresolvable key is an empty result, and the payload can still carry nothing")
        void anUnresolvableKeyIsCarriedAsAbsent() {
            // WHEN OTHER at COUSR02C.cbl:127-130 answers an unmapped key with CCDA-MSG-INVALID-KEY.
            // The resolver reports the absence as an empty Optional rather than as a seventeenth
            // token, and the payload carries a null AID for it, which is a state the constructor
            // accepts.
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPA3))
                    .as("PA3 is not among the sixteen conditions CSSTRPFY declares")
                    .isEmpty();

            UserUpdateRequest request = requestOf(populatedMapValues(), populatedContext(), null,
                    populatedCu02Info());
            assertThat(request.aid()).isNull();
            assertThat(validate(request)).isEmpty();
            assertThat(SystemMessages.CCDA_MSG_INVALID_KEY)
                    .as("and the message that answers it fits the 78-wide field")
                    .hasSize(SystemMessages.MESSAGE_LENGTH);
        }
    }

    // =================================================================================================
    // 9. THE TWELVE REDEFINES OVERLAYS (gate G34).
    //
    // COUSR02.CPY declares thirteen REDEFINES: twelve per-field overlays, each
    // "02 FILLER REDEFINES xxxF / 03 xxxA PICTURE X", plus the group-level COUSR2AO at line 91. The
    // twelve are this file's subject. Because xxxF and xxxA are metadata and deliberately not payload
    // members, the property is asserted against the storage they describe - two typed accessors over
    // one backing span - rather than against an invented member.
    // =================================================================================================

    @Nested
    @DisplayName("REDEFINES - twelve attribute overlays, each over one shared byte")
    class RedefinesOverlays {

        @Test
        @DisplayName("the layout tiles 339 bytes exactly: 12 + 12 x 7 + 243")
        void theGeometryIsTheCopybooks() {
            // Constructing SYMBOLIC_MAP_LAYOUT already proved this - RecordLayout refuses a gap, an
            // unintended overlap and any total other than its declared length - so this case states
            // the arithmetic a reader needs rather than discovering it.
            assertThat(FIELD_PREFIX_LENGTH)
                    .as("xxxL 2 + xxxF 1 + FILLER X(4) = 7 per field")
                    .isEqualTo(7);
            assertThat(DECLARED_WIDTHS.stream().mapToInt(Integer::intValue).sum())
                    .isEqualTo(PAYLOAD_WIDTH_TOTAL);
            assertThat(TIOAPFX_PREFIX_LENGTH + DFHMDF_NAMED * FIELD_PREFIX_LENGTH
                    + PAYLOAD_WIDTH_TOTAL)
                    .isEqualTo(SYMBOLIC_MAP_LENGTH);
            assertThat(SYMBOLIC_MAP_LAYOUT.recordLength()).isEqualTo(SYMBOLIC_MAP_LENGTH);
            assertThat(SYMBOLIC_MAP_LAYOUT.redefinitions())
                    .as("twelve per-field overlays; the group-level one at line 91 is not modelled")
                    .hasSize(DFHMDF_NAMED);
            assertThat(PER_FIELD_REDEFINES_LINES).hasSize(DFHMDF_NAMED);
            assertThat(SYMBOLIC_MAP_LAYOUT.storageSpans().stream()
                    .mapToInt(FixedWidthRecord.FieldSpan::length).sum())
                    .as("the storage spans, overlays excluded, sum to the record length")
                    .isEqualTo(SYMBOLIC_MAP_LENGTH);
        }

        @Test
        @DisplayName("this package's redefinitions live entirely in its maps, never in its programs")
        void theRedefinitionsAreTheMapsAndNotThePrograms() {
            // 12 + 60 + 13 + 13 + 12 = 110 across COSGN00, COUSR00, COUSR01, COUSR02 and COUSR03,
            // while COSGN00C, COUSR00C, COUSR01C, COUSR02C and COUSR03C declare a REDEFINES between
            // them exactly zero times. So the symbolic maps are the only place the redefinition gate
            // has a subject in this package, and COUSR02's thirteen are 13 of that 110.
            assertThat(COPYBOOK_REDEFINES_TOTAL)
                    .as("COUSR02.CPY: twelve per-field overlays plus the group view at line 91")
                    .isEqualTo(PER_FIELD_REDEFINES_LINES.size() + 1);
            assertThat(PACKAGE_REDEFINES_TOTAL)
                    .as("and the five maps of this package add up to 110")
                    .isEqualTo(12 + COUSR00_REDEFINES_TOTAL + 13 + COPYBOOK_REDEFINES_TOTAL + 12)
                    .isGreaterThan(COPYBOOK_REDEFINES_TOTAL);
            assertThat(SYMBOLIC_MAP_LAYOUT.redefinitions())
                    .as("of which this suite models the twelve that belong to the input view")
                    .hasSize(COPYBOOK_REDEFINES_TOTAL - 1);
        }

        @ParameterizedTest(name = "{0}A redefines {0}F")
        @ValueSource(strings = {"TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME",
            "USRIDIN", "FNAME", "LNAME", "PASSWD", "USRTYPE", "ERRMSG"})
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
            "USRIDIN", "FNAME", "LNAME", "PASSWD", "USRTYPE", "ERRMSG"})
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

        @Test
        @DisplayName("a real attribute value goes through the overlay, and the data is still readable")
        void aRealAttributeValueGoesThroughTheOverlay() {
            // DFHBMDAR is the mnemonic PASSWD's ATTRB=(DRK,...) corresponds to, and DFHRED is what
            // CSSETATY moves onto a failed field. Both are single bytes of the absent-but-reproduced
            // DFHBMSCA and DFHATTR copybooks, and both are written through the xxxA view - which is
            // exactly why xxxA exists and why neither is a payload member.
            FixedWidthRecord record = FixedWidthRecord.forLayout(SYMBOLIC_MAP_LAYOUT, MAP_CHARSET);
            FixedWidthRecord.FieldSpan errorFlag = SYMBOLIC_MAP_LAYOUT.span("ERRMSGF");
            FixedWidthRecord.FieldSpan errorAttribute = SYMBOLIC_MAP_LAYOUT.span("ERRMSGA");

            record.writeSpan(SYMBOLIC_MAP_LAYOUT.span("ERRMSGI"),
                    codec().movePicX("Please modify to update ...", UserUpdateRequest.ERRMSG_LENGTH));
            record.writeSpanBytes(errorAttribute, new byte[] {BmsAttributes.DFHRED});

            assertThat(record.readSpanBytes(errorFlag))
                    .as("read back through the other view of the same byte")
                    .isEqualTo(new byte[] {BmsAttributes.DFHRED});
            assertThat(record.readSpan(SYMBOLIC_MAP_LAYOUT.span("ERRMSGI")))
                    .as("and the message itself is unharmed")
                    .startsWith("Please modify to update ...")
                    .hasSize(UserUpdateRequest.ERRMSG_LENGTH);
            assertThat(BmsAttributes.isNonDisplay(BmsAttributes.DFHBMDAR))
                    .as("DRK is non-display, which is a terminal rendering property")
                    .isTrue();
            assertThat(record.toByteArray()).hasSize(SYMBOLIC_MAP_LENGTH);
        }
    }

    // =================================================================================================
    // 10. SERIALISATION.
    //
    // Names are the member names, untransformed; nothing is renamed, nothing is excluded when null or
    // empty, and no value is trimmed. Trimming would be actively wrong: the change tests at
    // COUSR02C.cbl:219-234 compare a screen value against a space-padded stored value, so trailing
    // spaces are semantically significant and a round trip has to preserve them byte for byte.
    // =================================================================================================

    @Nested
    @DisplayName("Serialisation - padding survives, names are untransformed")
    class Serialisation {

        @Test
        @DisplayName("the mapper this suite uses carries the three settings the module configures")
        void theMapperMatchesTheModuleConfiguration() {
            ObjectMapper mapper = webConfigEquivalentMapper();

            assertThat(mapper.isEnabled(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)).isTrue();
            assertThat(mapper.getFactory().isEnabled(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN))
                    .isTrue();
            assertThat(mapper.isEnabled(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT))
                    .as("a default mapper would coerce an empty PIC X(n) value to null and silently "
                            + "break every blank-value assertion in this suite")
                    .isFalse();
            assertThat(mapper.getSerializationConfig().getPropertyNamingStrategy())
                    .as("no naming strategy, so each property still traces 1:1 to an xxxI item")
                    .isNull();
        }

        @Test
        @DisplayName("the member names are the component names, untransformed")
        void theMemberNamesAreUntransformed() {
            String payload = serialise(populatedRequest());

            for (String member : wireNamesOf(MAP_MEMBERS)) {
                assertThat(payload)
                        .as("%s appears as its xxxI item in lower case: not snake_case, not upper "
                                + "case, not renamed by a strategy", member)
                        .contains("\"" + member + "\":");
            }
            for (String member : STATE_MEMBERS) {
                assertThat(payload).contains("\"" + member + "\":");
            }
            assertThat(jsonMembersOf(populatedRequest()))
                    .containsExactlyInAnyOrderElementsOf(EXPECTED_JSON_MEMBERS);
        }

        @Test
        @DisplayName("a space-padded payload survives serialise then deserialise byte for byte")
        void spacePaddingSurvivesTheRoundTrip() {
            UserUpdateRequest original = new UserUpdateRequest(TRANSACTION_ID,
                    ScreenTitles.CCDA_TITLE01,
                    FIXED_CURDATE,
                    PROGRAM_NAME,
                    ScreenTitles.CCDA_TITLE02,
                    FIXED_CURTIME,
                    "USER    ",
                    codec().movePicX("LAWRENCE", UserUpdateRequest.FNAME_LENGTH),
                    codec().movePicX("THOMAS", UserUpdateRequest.LNAME_LENGTH),
                    NOT_A_REAL_PASSWORD,
                    UserUpdateRequest.USER_TYPE_USER,
                    codec().movePicX("", UserUpdateRequest.ERRMSG_LENGTH),
                    populatedContext(),
                    PfKeyResolver.AidKey.PA1.token(),
                    populatedCu02Info());

            UserUpdateRequest back = deserialise(serialise(original));

            assertThat(back).isEqualTo(original);
            assertThat(back.usrIdIn())
                    .as("an identifier keyed short of its field keeps the spaces the terminal sent")
                    .isEqualTo("USER    ")
                    .hasSize(UserUpdateRequest.USRIDIN_LENGTH);
            assertThat(back.fName())
                    .isEqualTo(original.fName())
                    .hasSize(UserUpdateRequest.FNAME_LENGTH);
            assertThat(back.lName()).hasSize(UserUpdateRequest.LNAME_LENGTH);
            assertThat(back.passwd())
                    .as("eight characters of plaintext, unchanged in either direction")
                    .isEqualTo(NOT_A_REAL_PASSWORD)
                    .hasSize(UserUpdateRequest.PASSWD_LENGTH);
            assertThat(back.errMsg())
                    .as("a blank message line is 78 spaces, not an empty string and not absent")
                    .hasSize(UserUpdateRequest.ERRMSG_LENGTH)
                    .isBlank();
            assertThat(back.aid())
                    .as("PA1 really does carry two trailing spaces in CCARD-AID PIC X(5)")
                    .isEqualTo("PA1  ")
                    .hasSize(PfKeyResolver.AID_TOKEN_LENGTH);
        }

        @Test
        @DisplayName("an empty value stays empty and an absent one stays absent; the two stay distinct")
        void emptyAndAbsentBothSurvive() {
            List<String> values = new ArrayList<>(blankMapValues());
            values.set(MAP_MEMBERS.indexOf("curDate"), null);
            values.set(MAP_MEMBERS.indexOf("lName"), null);

            UserUpdateRequest back = deserialise(serialise(
                    requestOf(values, NavigationContext.empty(), "ENTER", null)));

            assertThat(back.usrIdIn())
                    .as("SPACES survives as an empty string rather than being coerced to null")
                    .isNotNull()
                    .isEmpty();
            assertThat(back.curDate())
                    .as("LOW-VALUES survives as absent - a field the terminal never transmitted")
                    .isNull();
            assertThat(back.lName()).isNull();
            assertThat(jsonMembersOf(requestOf(values, NavigationContext.empty(), "ENTER", null)))
                    .as("an absent member is emitted rather than dropped, so the shape is stable")
                    .containsExactlyInAnyOrderElementsOf(EXPECTED_JSON_MEMBERS);
        }

        @Test
        @DisplayName("an absent communication area and AID round-trip as absent")
        void absentStateCarriersRoundTrip() {
            UserUpdateRequest cold = requestOf(nullMapValues(), null, null, null);

            UserUpdateRequest back = deserialise(serialise(cold));

            assertThat(back).isEqualTo(cold);
            assertThat(back.navigationContext()).isNull();
            assertThat(back.aid()).isNull();
            assertThat(back.hasNavigationContext()).isFalse();
            assertThat(back.cu02Info())
                    .as("and the extension is still normalised on the way back in")
                    .isEqualTo(UserUpdateRequest.Cu02Info.initial());
            assertThat(serialise(cold)).contains("\"navigationContext\":null", "\"aid\":null");
        }

        @Test
        @DisplayName("the communication area round-trips as a nested object, not as a string")
        void theNestedContextRoundTrips() {
            UserUpdateRequest original = populatedRequest();

            UserUpdateRequest back = deserialise(serialise(original));

            assertThat(back.navigationContext()).isEqualTo(original.navigationContext());
            assertThat(back.contextIsReenter()).isTrue();
            assertThat(serialise(original))
                    .as("a nested object, so the 160-byte area keeps its named items")
                    .contains("\"navigationContext\":{");
            assertThat(back.navigationContext().toFixedWidth(codec()))
                    .isEqualTo(original.navigationContext().toFixedWidth(codec()));
        }

        @Test
        @DisplayName("the extension round-trips with its six items and an integral page number")
        void theExtensionRoundTrips() {
            UserUpdateRequest original = populatedRequest();

            String payload = serialise(original);
            UserUpdateRequest back = deserialise(payload);

            assertThat(payload).contains("\"cu02Info\":{");
            for (String item : List.of("usridFirst", "usridLast", "pageNum", "nextPageFlg",
                    "usrSelFlg", "usrSelected")) {
                assertThat(payload).contains("\"" + item + "\":");
            }
            assertThat(payload)
                    .as("the page number is a JSON number, unquoted and with no exponent")
                    .contains("\"pageNum\":1");
            assertThat(back.cu02Info()).isEqualTo(original.cu02Info());
            assertThat(back.cu02Info().pageNum()).isEqualTo(1);
            assertThat(String.join("", back.cu02Info().fieldImages().values()))
                    .hasSize(CU02_INFO_LENGTH);
        }
    }

    // =================================================================================================
    // 11. SECURITY POSTURE (gate G41, practice B6).
    //
    // COUSR02C.cbl:227 compares PASSWDI against SEC-USR-PWD PIC X(08) byte for byte, :228 stores it
    // verbatim and :169 moves the stored value straight back onto the screen. Plaintext credential
    // handling is an inherited property of the legacy design and an explicit non-goal of this
    // migration: a digest would change observable behaviour and would pull in a framework that is out
    // of scope. It is documented here so it stays visible instead of being buried in generated code.
    // =================================================================================================

    @Nested
    @DisplayName("Security posture - plaintext as the program compares it, and no more")
    class SecurityPosture {

        @Test
        @DisplayName("passwd is a plaintext String of the width CSUSR01Y declares")
        void thePasswordIsAPlaintextStringOfEight() {
            assertThat(componentType(UserUpdateRequest.class, "passwd"))
                    .as("characters, not a digest, not a byte array, not an opaque credential type")
                    .isEqualTo(String.class);
            assertThat(UserUpdateRequest.PASSWD_LENGTH)
                    .isEqualTo(SEC_USR_PWD_LENGTH)
                    .isEqualTo(SecUserRecord.SEC_USR_PWD_LENGTH);
            assertThat(UserUpdateRequest.PASSWD_FIELD).isEqualTo("PASSWDI");
            assertThat(withMapValue("passwd", NOT_A_REAL_PASSWORD).passwd())
                    .isEqualTo(NOT_A_REAL_PASSWORD)
                    .hasSize(UserUpdateRequest.PASSWD_LENGTH);
        }

        @Test
        @DisplayName("an eight-character password survives the round trip byte for byte")
        void anEightCharacterPasswordSurvivesTheRoundTrip() {
            UserUpdateRequest original = withMapValue("passwd", NOT_A_REAL_PASSWORD);

            UserUpdateRequest back = deserialise(serialise(original));

            assertThat(back.passwd())
                    .as("if the value did not round trip, the comparison at COUSR02C.cbl:227 and the "
                            + "echo at :169 would both change behaviour")
                    .isEqualTo(NOT_A_REAL_PASSWORD)
                    .hasSize(UserUpdateRequest.PASSWD_LENGTH);
            assertThat(serialise(original)).contains(NOT_A_REAL_PASSWORD);
            assertThat(back).isEqualTo(original);
        }

        @Test
        @DisplayName("the payload does not hash, encode, upper-case or otherwise normalise the value")
        void thePayloadIsAPassiveCarrier() {
            // COSGN00C.cbl:132-137 upper-cases both the identifier and the password before comparing;
            // COUSR02C does no such thing - it compares what the screen sent. A payload that folded
            // case here would make two different keyed values compare alike.
            String mixedCase = "aBcDeFgH";

            UserUpdateRequest request = withMapValue("passwd", mixedCase);

            assertThat(request.passwd()).isEqualTo(mixedCase);
            assertThat(deserialise(serialise(request)).passwd()).isEqualTo(mixedCase);
            assertThat(withMapValue("usrIdIn", "user0001").usrIdIn())
                    .as("nor is the identifier folded: COUSR02C.cbl:216 moves it as keyed")
                    .isEqualTo("user0001");
        }

        @Test
        @DisplayName("no hashing, encoder, token or security framework type is reachable")
        void noSecurityFrameworkTypeIsReachable() {
            List<String> forbidden = List.of("PasswordEncoder", "BCrypt", "MessageDigest", "Hash",
                    "org.springframework.security", "jwt", "Jwt", "Cipher", "SecretKey", "Base64",
                    "Authentication", "Principal");

            List<String> reachable = new ArrayList<>();
            for (Class<?> owner : List.of(UserUpdateRequest.class,
                    UserUpdateRequest.Cu02Info.class)) {
                for (RecordComponent component : owner.getRecordComponents()) {
                    reachable.add(component.getType().getName());
                    for (Annotation annotation : component.getAccessor().getAnnotations()) {
                        reachable.add(annotation.annotationType().getName());
                    }
                }
                for (Method method : owner.getDeclaredMethods()) {
                    reachable.add(method.getReturnType().getName());
                    reachable.add(method.getName());
                    for (Class<?> parameter : method.getParameterTypes()) {
                        reachable.add(parameter.getName());
                    }
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
            // app/bms/COUSR02.bms:130 declares PASSWD as ATTRB=(DRK,FSET,UNPROT) with
            // HILIGHT=UNDERLINE. DRK is terminal non-display: it stops the 3270 rendering the
            // characters. It says nothing about how the value is stored, transmitted or compared, and
            // mistaking it for hashing would be a security claim the legacy design never made.
            assertThat(BmsAttributes.isNonDisplay(BmsAttributes.DFHBMDAR))
                    .as("non-display, which is what DRK means")
                    .isTrue();
            assertThat(BmsAttributes.isProtected(BmsAttributes.DFHBMDAR))
                    .as("and still unprotected, because the operator has to be able to type into it")
                    .isFalse();

            UserUpdateRequest keyed = withMapValue("passwd", NOT_A_REAL_PASSWORD);
            assertThat(keyed.passwd())
                    .as("the payload carries the keyed characters, unmasked and uncounted")
                    .isEqualTo(NOT_A_REAL_PASSWORD);
            assertThat(serialise(keyed))
                    .as("and they are on the wire, because that is what the program compares")
                    .contains(NOT_A_REAL_PASSWORD);
        }

        @Test
        @DisplayName("the credential is bidirectional on this screen: :227 compares it and :169 echoes it")
        void theCredentialTravelsInBothDirections() {
            // Worth recording, because it is the property that pairs this file with its response twin.
            // COUSR02C.cbl:169 executes
            //     MOVE SEC-USR-PWD TO PASSWDI OF COUSR2AI
            // which places the STORED plaintext back on the screen, so the response half of this
            // screen carries a password as well - unlike the sign-on screen, whose response carries
            // none because COSGN00C never echoes what it compared. UserUpdateResponseTest asserts that
            // half; this case asserts the half that is this file's, and the note keeps the two
            // consistent. The assertion is deliberately confined to this payload rather than reaching
            // into a sibling type, so this suite depends only on what it was given.
            //
            // The consequence for THIS payload is that the same field is both an input the operator
            // keys and an output the program paints, which is why it must round trip byte for byte in
            // both directions and why no transformation may be applied in either.
            assertThat(MAP_MEMBERS)
                    .as("PASSWDI is a member of the INPUT view 01 COUSR2AI, COUSR02.CPY:78, so the "
                            + "operator keys it")
                    .contains("passwd");
            assertThat(UserUpdateRequest.PASSWD_FIELD).isEqualTo("PASSWDI");
            assertThat(SYMBOLIC_MAP_ITEMS.get(MAP_MEMBERS.indexOf("passwd")))
                    .as("and the output view COUSR2AO at COUSR02.CPY:91 redefines that same span, "
                            + "which is how :169 writes the stored value back over it")
                    .isEqualTo(UserUpdateRequest.PASSWD_FIELD);

            UserUpdateRequest echoed = withMapValue("passwd", NOT_A_REAL_PASSWORD);
            assertThat(deserialise(serialise(echoed)).passwd())
                    .as("the value the program echoes has to survive being sent back in unchanged")
                    .isEqualTo(NOT_A_REAL_PASSWORD)
                    .hasSize(UserUpdateRequest.PASSWD_LENGTH);
        }

        @Test
        @DisplayName("the diagnostic rendering withholds the password and the two names")
        void theDiagnosticRenderingWithholdsTheSensitiveFields() {
            // Carrying the credential in the clear is required for parity; broadcasting it into a log
            // line, an exception message or a debugger view is not, and the two concerns separate
            // cleanly. Every non-personal component is still reported, because a diagnostic that hid
            // everything would be useless.
            UserUpdateRequest request = populatedRequest();
            String rendered = request.toString();

            assertThat(rendered)
                    .as("the keyed characters must not reach a log line")
                    .doesNotContain(NOT_A_REAL_PASSWORD)
                    .contains("passwd=");
            assertThat(rendered)
                    .as("names are personal data, so the content is dropped and only the shape kept")
                    .doesNotContain(request.fName().strip())
                    .doesNotContain(request.lName().strip())
                    .contains("fName=")
                    .contains("lName=");
            assertThat(rendered)
                    .as("and everything a parity failure has to be diagnosed from stays legible")
                    .contains(TRANSACTION_ID)
                    .contains(PROGRAM_NAME)
                    .contains(FIXED_CURDATE)
                    .contains(FIXED_CURTIME)
                    .contains("usrIdIn=USER0001")
                    .startsWith("UserUpdateRequest[")
                    .endsWith("]");
        }

        @Test
        @DisplayName("the password rendering reveals neither the value nor its length")
        void theRenderedPasswordRevealsNothing() {
            // The strongest available statement of the policy, and one that needs no reference to the
            // marker itself: two payloads that differ ONLY in their password - and by length as well as
            // by content - must render identically. If either the value or its length leaked, the two
            // renderings would differ. A payload with a third, absent password must then differ from
            // both, because presence is not content and is diagnostically necessary.
            String eight = withMapValue("passwd", NOT_A_REAL_PASSWORD).toString();
            String alsoEight = withMapValue("passwd", OTHER_NOT_A_REAL_PASSWORD).toString();
            String two = withMapValue("passwd", "AB").toString();

            assertThat(alsoEight)
                    .as("different content, same rendering")
                    .isEqualTo(eight);
            assertThat(two)
                    .as("different length, still the same rendering - no length is disclosed either")
                    .isEqualTo(eight);
            assertThat(eight)
                    .doesNotContain(NOT_A_REAL_PASSWORD)
                    .doesNotContain(OTHER_NOT_A_REAL_PASSWORD);
        }

        @Test
        @DisplayName("a name's shape is reported, so equal lengths render alike and unequal ones do not")
        void theRenderedNamesDiscloseShapeOnly() {
            // The names are free-text personal data: the content is dropped entirely and only the shape
            // is reported, because a name has no useful prefix to reveal. So two different names of the
            // same width render identically, while a name of a different width does not - which is the
            // one thing the diagnostic does disclose, deliberately, since a width mismatch is exactly
            // what a fixed-width parity failure looks like.
            String lawrence = withMapValue("fName",
                    codec().movePicX("LAWRENCE", UserUpdateRequest.FNAME_LENGTH)).toString();
            String hermione = withMapValue("fName",
                    codec().movePicX("HERMIONE", UserUpdateRequest.FNAME_LENGTH)).toString();
            String short5 = withMapValue("fName", "SHORT").toString();

            assertThat(hermione)
                    .as("two twenty-character names are indistinguishable in the rendering")
                    .isEqualTo(lawrence);
            assertThat(short5)
                    .as("a five-character name is not, because the shape differs")
                    .isNotEqualTo(lawrence);
            assertThat(lawrence).doesNotContain("LAWRENCE").doesNotContain("HERMIONE");
        }

        @Test
        @DisplayName("an absent password is reported as absent, because presence is not content")
        void anAbsentPasswordIsReportedAsAbsent() {
            // null models the LOW-VALUES a field the terminal never transmitted arrives as, and
            // telling that apart from a transmitted value is what makes the guard at
            // COUSR02C.cbl:198 and the change test at :227 traceable. Presence is disclosed;
            // content never is.
            String absent = withMapValue("passwd", null).toString();

            assertThat(absent).contains("passwd=null");
            assertThat(absent)
                    .as("and absent is distinguishable from present, which is the whole point")
                    .isNotEqualTo(withMapValue("passwd", NOT_A_REAL_PASSWORD).toString());
            assertThat(withMapValue("passwd", null).passwd()).isNull();
        }

        @Test
        @DisplayName("equals and hashCode still include the password, because they disclose nothing")
        void valueSemanticsIncludeThePassword() {
            UserUpdateRequest one = withMapValue("passwd", NOT_A_REAL_PASSWORD);
            UserUpdateRequest other = withMapValue("passwd", OTHER_NOT_A_REAL_PASSWORD);

            assertThat(one).isNotEqualTo(other);
            assertThat(one).isEqualTo(withMapValue("passwd", NOT_A_REAL_PASSWORD));
            assertThat(one).hasSameHashCodeAs(withMapValue("passwd", NOT_A_REAL_PASSWORD));
            assertThat(one.cu02Info()).isEqualTo(other.cu02Info());
        }
    }
}
