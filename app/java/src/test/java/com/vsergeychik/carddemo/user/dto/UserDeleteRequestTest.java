package com.vsergeychik.carddemo.user.dto;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.annotation.JsonProperty;
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
 * Unit tests for {@link UserDeleteRequest} - the inbound payload of
 * {@code DELETE /api/users/{userId}}, CICS transaction {@code CU03}, program
 * {@code app/cbl/COUSR03C.cbl}, map {@code COUSR3A} of mapset {@code COUSR03}.
 *
 * <h2>The one thing this file exists to prove</h2>
 *
 * <strong>This screen has no password field at all.</strong> That single absence is the whole reason
 * the payload has <strong>eleven</strong> map-derived members where its sibling {@code COUSR02}
 * delete-user's-twin update screen has twelve, and it is asserted here from three independent
 * directions so that a later reader cannot mistake it for an omission and "restore the missing
 * field":
 *
 * <ol>
 *   <li>{@code grep -c 'PASSWD' app/cbl/COUSR03C.cbl} returns <strong>0</strong>. The program never
 *       names one.</li>
 *   <li>{@code grep -c 'PASSWD' app/bms/COUSR03.bms} returns <strong>0</strong>. The mapset never
 *       defines one, so no such field was ever painted on the 3270.</li>
 *   <li>The symbolic map's tail is shifted up by exactly one field, and therefore by exactly six
 *       lines. {@code app/cpy-bms/COUSR03.CPY} puts {@code USRTYPEI} at line <strong>78</strong> and
 *       {@code ERRMSGI} at line <strong>84</strong>; {@code app/cpy-bms/COUSR02.CPY} puts those same
 *       two items at <strong>84</strong> and <strong>90</strong>, with {@code PASSWDI PIC X(8)} at
 *       78 in between. Six lines is one {@code xxxL}/{@code xxxF}/{@code REDEFINES}/{@code xxxA}/
 *       {@code FILLER}/{@code xxxI} group. That shift is the mechanical proof of 11 against 12.</li>
 * </ol>
 *
 * Preserving that asymmetry is practice {@code B5} and gate {@code G9}. Adding a twelfth member for
 * symmetry with {@code COUSR02} would put a field on the wire that the delete program cannot see, and
 * it would be a behaviour change in a migration whose sole contract is that behaviour does not change.
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
 *       payload is a value carrier with no collaborator to stand in for.</li>
 *   <li><strong>B2</strong> - JUnit 5 Jupiter API only.</li>
 *   <li><strong>B3</strong> - the reference tree is neither written nor <em>read</em>. Every
 *       expectation is a {@code private static final} constant carrying the file and line it was
 *       transcribed from, so this suite is hermetic and independent of the working directory. Three
 *       sibling suites in this package - {@code SignOnResponseTest}, {@code UserAddRequestTest} and
 *       {@code UserListResponseTest} - instead parse the copybook and mapset at run time. That
 *       difference is recorded, not reconciled: both prove the same contract, and this file's ruling
 *       is the hermetic one.</li>
 *   <li><strong>B4</strong> - conflicts are documented rather than resolved. Two are relevant.
 *       <br><em>First:</em> {@code app/cbl/COUSR03C.cbl:45-47} declares
 *       {@code 05 WS-USR-MODIFIED PIC X(01) VALUE 'N'} with {@code 88 USR-MODIFIED-YES VALUE 'Y'} and
 *       {@code 88 USR-MODIFIED-NO VALUE 'N'}. It is copied verbatim from {@code COUSR02C}, where the
 *       update flow needs it, and in <strong>this</strong> program it is never {@code SET}, never
 *       moved to and never tested - a delete has nothing to compare a modification against. It is
 *       <strong>dead storage</strong>. Its existence is recorded here and it is deliberately
 *       <em>not</em> modelled on the DTO: modelling it would invent a payload member the program
 *       cannot observe, and deleting the record of it would lose a fact about the source. See
 *       {@link VestigialAndDeclaredState#theVestigialModifiedFlagIsNotModelled()}.
 *       <br><em>Second:</em> the declared-versus-brief divergences listed at the end of these notes.
 *       </li>
 *   <li><strong>B5</strong> - asymmetries are preserved, never smoothed. The member count is eleven
 *       because the screen has eleven fields; the extension block carries paging members this screen
 *       never pages with; and no member is asserted into existence for symmetry with a sibling.</li>
 *   <li><strong>B6</strong> - the security posture is neither weakened nor strengthened. With no
 *       password member there is nothing to hash even were hashing permitted, and
 *       {@link SecurityPosture} asserts that no encoder, digest, token or Spring Security type is
 *       reachable from this type at all.</li>
 *   <li><strong>B7</strong> - nothing here reads a wall clock, draws a random value or depends on
 *       another test having run. The time-derived expectations are driven from
 *       {@link Clock#fixed(Instant, java.time.ZoneId)}.</li>
 *   <li><strong>B8</strong> - every codec call names its {@link Charset} explicitly; no overload that
 *       omits it is used and no platform default is relied on. Every import is written out
 *       individually - there is no wildcard import in this file - and no dataset name appears in
 *       it.</li>
 *   <li><strong>B9</strong> - every field of this class is {@code static final} and immutable. No
 *       state is shared between test methods; JUnit's default per-method lifecycle does the
 *       isolating.</li>
 *   <li><strong>B10</strong> - this suite ships in the same phase as the type it measures rather than
 *       being added afterwards, which is what keeps a drift from the mapset traceable to the decision
 *       that caused it.</li>
 *   <li><strong>B11</strong> - fixed-width and truncation work goes through {@link FixedWidthCodec}
 *       and {@link FixedWidthRecord}. No third-party copybook parser is used, and no assertion
 *       substitutes {@link String#substring(int, int)} for a COBOL {@code MOVE}.</li>
 *   <li><strong>B12</strong> - the environmental limit is stated rather than absorbed. See the
 *       provenance note immediately below.</li>
 * </ul>
 *
 * <h2>Provenance of every expected value (B12)</h2>
 *
 * COBOL cannot be executed in this environment - eight independently verified blockers are recorded in
 * the plan as risk {@code R-A}, among them a disabled indexed-file handler, absent Language
 * Environment services and the absence of any CICS emulator. Every expectation below is therefore
 * <strong>statically derived</strong> by reading the source, never captured from a run. The lines used
 * are:
 *
 * <ul>
 *   <li>{@code app/cpy-bms/COUSR03.CPY:17-84} - the group {@code 01 COUSR3AI}, its 12-byte
 *       {@code TIOAPFX} prefix at line 18, the eleven {@code xxxI} items at lines 24, 30, 36, 42, 48,
 *       54, 60, 66, 72, 78 and 84 with the widths this file declares, and the eleven per-field
 *       {@code REDEFINES} overlays at lines 21, 27, 33, 39, 45, 51, 57, 63, 69, 75 and 81. The
 *       twelfth {@code REDEFINES} - the group-level {@code 01 COUSR3AO REDEFINES COUSR3AI} at line
 *       85 - belongs to {@code UserDeleteResponseTest} and is not asserted here.</li>
 *   <li>{@code app/bms/COUSR03.bms} - {@code COUSR03 DFHMSD} at lines 19-25 and
 *       {@code COUSR3A DFHMDI} at 26-28 with {@code SIZE=(24,80)}; 26 {@code DFHMDF} definitions of
 *       which the eleven name-labelled ones sit at lines 34, 38, 47, 57, 61, 70, 85, 103, 116, 130
 *       and 140, carrying the {@code LENGTH=} operands this file cross-checks.</li>
 *   <li>{@code app/cbl/COUSR03C.cbl} - {@code WS-PGMNAME} at 36, {@code WS-TRANID} at 37,
 *       {@code WS-MESSAGE PIC X(80)} at 38, the vestigial {@code WS-USR-MODIFIED} at 45-47,
 *       {@code COPY COCOM01Y.} at 49 and the {@code 05 CDEMO-CU03-INFO} extension at 50-58; the
 *       {@code EIBCALEN = 0} cold start at 90-92, the commarea restore at 94, the context flip at
 *       95-96, the {@code MOVE LOW-VALUES TO COUSR3AO} at 97 and the pre-selected id at 99-102; the
 *       {@code EVALUATE EIBAID} at 108-130; the single blank-id arm of {@code PROCESS-ENTER-KEY} at
 *       142-153; the display-only blank-and-refill at 157-159 and 165-167 either side of the lookup
 *       at 161; the single blank-id arm of {@code DELETE-USER-INFO} at 174-186; the
 *       {@code MOVE WS-MESSAGE TO ERRMSGO} at 217; {@code MAP('COUSR3A')} and
 *       {@code MAPSET('COUSR03')} at 220-221; the header fill at 247-250; and
 *       {@code INITIALIZE-ALL-FIELDS} at 349-356.</li>
 *   <li>{@code app/cpy/CSUSR01Y.cpy:17-23} - the {@code SEC-USER-DATA} widths the four data fields on
 *       this screen are moved to and from, including the {@code SEC-USR-PWD PIC X(08)} the screen does
 *       <em>not</em> project.</li>
 *   <li>{@code app/cpy/COCOM01Y.cpy:19-44} - the 160-byte {@code CARDDEMO-COMMAREA}, and
 *       {@code CDEMO-PGM-CONTEXT} with {@code 88 CDEMO-PGM-ENTER VALUE 0} and
 *       {@code 88 CDEMO-PGM-REENTER VALUE 1} at 29-31.</li>
 *   <li>{@code app/csd/CARDDEMO.CSD:479-480} - {@code DEFINE TRANSACTION(CU03) GROUP(CARDDEMO)}
 *       and {@code PROGRAM(COUSR03C)}, corroborating the two identity constants.</li>
 * </ul>
 *
 * <h2>Acceptance gates enforced directly here</h2>
 *
 * <ul>
 *   <li><strong>G9</strong> - every payload member traces to one name-labelled {@code DFHMDF} field.
 *       The count is asserted to be exactly eleven, so a twelfth - which could only be an invented
 *       password - fails the build.</li>
 *   <li><strong>G22</strong> - no member is {@code double} or {@code float}, {@code pageNum}
 *       included: {@code CDEMO-CU03-PAGE-NUM PIC 9(08)} is scale-free.</li>
 *   <li><strong>G34</strong> - the eleven {@code xxxA REDEFINES xxxF} overlays are two typed
 *       accessors over one backing byte, round-tripped in both directions.</li>
 *   <li><strong>G37</strong> - conversation state travels in the payload: the commarea, the resolved
 *       AID token and the extension block are members, so no session is ever needed.</li>
 *   <li><strong>G41</strong> - no hashing, no token, no filter chain. Here that is trivially so,
 *       because there is no credential on this screen to protect or to weaken.</li>
 *   <li><strong>G46</strong> - no dataset name is <em>used</em> anywhere here. The single occurrence
 *       of the {@code AWS.M2.CARDDEMO} prefix is the argument of a negative assertion in
 *       {@link VestigialAndDeclaredState#noDatasetNameIsExposed()}, which proves the payload never
 *       carries one; dataset names belong in {@code application.yml}.</li>
 *   <li><strong>G49</strong> - JaCoCo's {@code BRANCH} rule is enforced per <em>package</em> as well
 *       as per bundle, so {@code user}, {@code user.model} and {@code user.dto} are each measured on
 *       their own. Every decision this type makes is driven from here: both canonical-constructor
 *       normalisations, both {@code pageNum} rejections, both short-circuits in each context
 *       predicate, and both states of the {@code 88}-level flags.</li>
 *   <li><strong>G50</strong> - both states of every {@code 88}-level condition name in scope:
 *       {@code NEXT-PAGE-YES}/{@code NEXT-PAGE-NO} and {@code CDEMO-PGM-ENTER}/
 *       {@code CDEMO-PGM-REENTER}, each in its true and its false reading, plus a value that
 *       satisfies neither.</li>
 *   <li><strong>G52</strong> - no wildcard import. <strong>G53</strong> - no mutable static.
 *       <strong>G54</strong> - no watch mode, no ordering dependence, no wall clock.</li>
 * </ul>
 *
 * <h2>Deliberately out of scope for this file</h2>
 *
 * <ul>
 *   <li><strong>The HTTP projection.</strong> This is a plain-object suite: no Spring context, no
 *       {@code @WebMvcTest}, no {@code MockMvc}, no controller, service or repository.
 *       {@code UserDeleteControllerTest} owns the request-to-response mapping, the keyless
 *       confirm-then-delete path, the preserved defects and the {@code PF3}-does-not-save contrast.
 *       Re-testing them here would duplicate the assertion and split its ownership.</li>
 *   <li><strong>Gate G43.</strong> Optimistic concurrency has no subject in this package:
 *       {@code COUSR03C} has no {@code 9300-CHECK-CHANGE-IN-REC} paragraph, so no version column, no
 *       ETag and no re-read-before-write assertion belongs anywhere in {@code user.dto}.</li>
 * </ul>
 *
 * <h2>Where the declared type diverges from this suite's brief (B4)</h2>
 *
 * <ul>
 *   <li>{@link UserDeleteRequest.Cu03Info} publishes {@code NEXT_PAGE_YES} and {@code NEXT_PAGE_NO}
 *       as constants but declares <em>no</em> {@code nextPageYes()} / {@code nextPageNo()} predicate
 *       methods. The two {@code 88}-level states are therefore driven against those constants rather
 *       than through accessors that do not exist. What is declared is asserted; the divergence is
 *       recorded here rather than fixed in the main type.</li>
 *   <li>{@link UserDeleteRequest#toString()} renders thirteen of the fourteen components and omits
 *       {@code cu03Info}. That is the type as declared, so {@link SecurityPosture} asserts the
 *       rendering it actually produces.</li>
 * </ul>
 *
 * @see UserDeleteRequest
 * @see SignOnRequestTest for the assertion idiom and the shared header-field vocabulary this file
 *      reuses
 */
@DisplayName("UserDeleteRequest - the CU03 delete-user payload, and the password it does not have")
class UserDeleteRequestTest {

    // =================================================================================================
    // THE SCREEN, TRANSCRIBED. Every constant below names the file and line it came from, and nothing
    // in this file reads those files at run time (B3). Where a number can be re-derived from the
    // others it is, so a single wrong transcription fails class initialisation rather than passing
    // quietly.
    // =================================================================================================

    /**
     * The code page every codec call in this file names explicitly (B8).
     *
     * <p>{@code US-ASCII} is the estate's ASCII fixture encoding, and it is stated rather than
     * inherited from the platform: relying on a default is precisely the pitfall the plan calls out
     * for mainframe data, and a single-byte code page is what makes an offset in a copybook and an
     * index into a Java string the same number.
     */
    private static final Charset MAP_CHARSET = StandardCharsets.US_ASCII;

    /** The eleven map-derived record components, in {@code 01 COUSR3AI} declaration order. */
    private static final List<String> MAP_MEMBERS = List.of("trnName", "title01", "curDate",
            "pgmName", "title02", "curTime", "usrIdIn", "fName", "lName", "usrType", "errMsg");

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
        return members.stream().map(UserDeleteRequestTest::wireNameOf).toList();
    }

    /**
     * The {@code xxxI} items those members project, {@code app/cpy-bms/COUSR03.CPY} lines 24, 30, 36,
     * 42, 48, 54, 60, 66, 72, 78 and 84.
     *
     * <p>The {@code I} suffix is kept: it is the item's real name, it distinguishes the input view
     * from the {@code xxxO} output view of {@code 01 COUSR3AO}, and a field-by-field parity comparison
     * keys on it.
     */
    private static final List<String> SYMBOLIC_MAP_ITEMS = List.of("TRNNAMEI", "TITLE01I",
            "CURDATEI", "PGMNAMEI", "TITLE02I", "CURTIMEI", "USRIDINI", "FNAMEI", "LNAMEI",
            "USRTYPEI", "ERRMSGI");

    /**
     * The name-labelled {@code DFHMDF} fields of {@code app/bms/COUSR03.bms} - the {@code xxxI} names
     * with the suffix removed, which is also the stem the {@code xxxL}, {@code xxxF} and {@code xxxA}
     * metadata items are formed from.
     */
    private static final List<String> SCREEN_FIELDS = List.of("TRNNAME", "TITLE01", "CURDATE",
            "PGMNAME", "TITLE02", "CURTIME", "USRIDIN", "FNAME", "LNAME", "USRTYPE", "ERRMSG");

    /** The {@code xxxI} {@code PICTURE} widths, in the same order. */
    private static final List<Integer> DECLARED_WIDTHS =
            List.of(4, 40, 8, 8, 40, 8, 8, 20, 20, 1, 78);

    /**
     * The {@code LENGTH=} operand of each name-labelled {@code DFHMDF}, transcribed independently from
     * {@code app/bms/COUSR03.bms} so the mapset corroborates the copybook rather than echoing it.
     */
    private static final List<Integer> MAPSET_LENGTHS =
            List.of(4, 40, 8, 8, 40, 8, 8, 20, 20, 1, 78);

    /** The width constants {@link UserDeleteRequest} publishes, read back in component order. */
    private static final List<Integer> PUBLISHED_WIDTHS = List.of(
            UserDeleteRequest.TRNNAME_LENGTH,
            UserDeleteRequest.TITLE01_LENGTH,
            UserDeleteRequest.CURDATE_LENGTH,
            UserDeleteRequest.PGMNAME_LENGTH,
            UserDeleteRequest.TITLE02_LENGTH,
            UserDeleteRequest.CURTIME_LENGTH,
            UserDeleteRequest.USRIDIN_LENGTH,
            UserDeleteRequest.FNAME_LENGTH,
            UserDeleteRequest.LNAME_LENGTH,
            UserDeleteRequest.USRTYPE_LENGTH,
            UserDeleteRequest.ERRMSG_LENGTH);

    /** The {@code xxxI} field-name constants {@link UserDeleteRequest} publishes, in the same order. */
    private static final List<String> PUBLISHED_ITEM_NAMES = List.of(
            UserDeleteRequest.TRNNAME_FIELD,
            UserDeleteRequest.TITLE01_FIELD,
            UserDeleteRequest.CURDATE_FIELD,
            UserDeleteRequest.PGMNAME_FIELD,
            UserDeleteRequest.TITLE02_FIELD,
            UserDeleteRequest.CURTIME_FIELD,
            UserDeleteRequest.USRIDIN_FIELD,
            UserDeleteRequest.FNAME_FIELD,
            UserDeleteRequest.LNAME_FIELD,
            UserDeleteRequest.USRTYPE_FIELD,
            UserDeleteRequest.ERRMSG_FIELD);

    /** The {@code xxxI} declaration lines in {@code app/cpy-bms/COUSR03.CPY}. */
    private static final List<Integer> COPYBOOK_LINES =
            List.of(24, 30, 36, 42, 48, 54, 60, 66, 72, 78, 84);

    /** The name-labelled {@code DFHMDF} declaration lines in {@code app/bms/COUSR03.bms}. */
    private static final List<Integer> MAPSET_LINES =
            List.of(34, 38, 47, 57, 61, 70, 85, 103, 116, 130, 140);

    /**
     * The per-field {@code REDEFINES} lines in {@code app/cpy-bms/COUSR03.CPY}: 21, 27, 33, 39, 45,
     * 51, 57, 63, 69, 75 and 81.
     *
     * <p>The copybook holds twelve {@code REDEFINES}. These eleven are the
     * {@code 02 FILLER REDEFINES xxxF} overlays and are this suite's subject; the twelfth, the
     * group-level {@code 01 COUSR3AO REDEFINES COUSR3AI} at line 85, is
     * {@code UserDeleteResponseTest}'s. Twelve is also {@code COSGN00}'s count, for the same reason -
     * both screens carry eleven fields - which is a satisfying independent cross-check on both.
     */
    private static final List<Integer> REDEFINES_LINES =
            List.of(21, 27, 33, 39, 45, 51, 57, 63, 69, 75, 81);

    /**
     * The corresponding {@code xxxI} lines in {@code app/cpy-bms/COUSR02.CPY}: identical for the first
     * nine items, then <strong>six lines later</strong> for {@code USRTYPEI} (84, not 78) and
     * {@code ERRMSGI} (90, not 84), because {@code PASSWDI PIC X(8)} occupies line 78 there.
     *
     * <p>This list exists solely to make the twelve-against-eleven difference mechanical rather than
     * asserted. See {@link AbsentPasswordField#theTailIsShiftedUpByExactlyOneField()}.
     */
    private static final List<Integer> COUSR02_COPYBOOK_LINES =
            List.of(24, 30, 36, 42, 48, 54, 60, 66, 72, 84, 90);

    /** {@code app/cpy-bms/COUSR02.CPY:78} - {@code 02 PASSWDI PIC X(8)}, the item this screen lacks. */
    private static final int COUSR02_PASSWD_LINE = 78;

    /** {@code COUSR02} projects twelve map fields; this screen projects eleven. */
    private static final int COUSR02_MAP_FIELD_COUNT = 12;

    /** Total {@code DFHMDF} definitions in {@code app/bms/COUSR03.bms}, labelled and unlabelled. */
    private static final int DFHMDF_TOTAL = 26;

    /** Of those, the count carrying a name label - and therefore the payload member count. */
    private static final int DFHMDF_NAMED = 11;

    /**
     * The three members with no {@code DFHMDF} behind them, in component order: the communication
     * area, the resolved AID token and this program's own commarea extension.
     */
    private static final List<String> STATE_MEMBERS =
            List.of("navigationContext", "aid", "cu03Info");

    /** {@value #COMPONENT_COUNT} components: {@value #DFHMDF_NAMED} map members plus the three above. */
    private static final int COMPONENT_COUNT = 14;

    /** The six items of {@code 05 CDEMO-CU03-INFO}, as {@code Cu03Info} names them. */
    private static final List<String> EXTENSION_MEMBERS = List.of("usridFirst", "usridLast",
            "pageNum", "nextPageFlg", "usrSelFlg", "usrSelected");

    /** The same six items as {@code app/cbl/COUSR03C.cbl:51-58} spells them, in declaration order. */
    private static final List<String> EXTENSION_ITEM_NAMES = List.of("CDEMO-CU03-USRID-FIRST",
            "CDEMO-CU03-USRID-LAST", "CDEMO-CU03-PAGE-NUM", "CDEMO-CU03-NEXT-PAGE-FLG",
            "CDEMO-CU03-USR-SEL-FLG", "CDEMO-CU03-USR-SELECTED");

    /** Their declared widths: 8 + 8 + 8 + 1 + 1 + 8. */
    private static final List<Integer> EXTENSION_WIDTHS = List.of(8, 8, 8, 1, 1, 8);

    /** {@value #EXTENSION_LENGTH} bytes - what widens the passed communication area to 194. */
    private static final int EXTENSION_LENGTH = 34;

    /**
     * {@value #CU03_COMMAREA_LENGTH} bytes - {@code CARDDEMO-COMMAREA} plus
     * {@code 05 CDEMO-CU03-INFO}, the area {@code app/cbl/COUSR03C.cbl:94} restores and 136 returns.
     */
    private static final int CU03_COMMAREA_LENGTH =
            NavigationContext.COMMAREA_LENGTH + EXTENSION_LENGTH;

    // =================================================================================================
    // BYTE GEOMETRY OF 01 COUSR3AI.
    //
    // Per field the input view declares xxxL (COMP PIC S9(4), a 2-byte halfword), xxxF (PICTURE X),
    // the 03 xxxA overlay over that same byte, and FILLER PICTURE X(4) - seven bytes - ahead of the
    // xxxI item itself. The output view's prefix is FILLER X(3) plus xxxC, xxxP, xxxH and xxxV, also
    // seven, which is what lets 01 COUSR3AO REDEFINES COUSR3AI overlay field for field.
    // =================================================================================================

    /** {@code 02 FILLER PIC X(12)}, {@code app/cpy-bms/COUSR03.CPY:18} - the {@code TIOAPFX} prefix. */
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

    /** {@value #PAYLOAD_WIDTH_TOTAL} bytes of {@code xxxI} data: 4+40+8+8+40+8+8+20+20+1+78. */
    private static final int PAYLOAD_WIDTH_TOTAL = 235;

    /**
     * {@value #SYMBOLIC_MAP_LENGTH} bytes in the group: {@value #TIOAPFX_PREFIX_LENGTH} +
     * {@value #DFHMDF_NAMED} x {@value #FIELD_PREFIX_LENGTH} + {@value #PAYLOAD_WIDTH_TOTAL}.
     */
    private static final int SYMBOLIC_MAP_LENGTH = 324;

    /**
     * {@code 05 WS-MESSAGE PIC X(80) VALUE SPACES}, {@code app/cbl/COUSR03C.cbl:38}.
     *
     * <p>Two characters wider than the {@code ERRMSGI PIC X(78)} it is moved into at
     * {@code app/cbl/COUSR03C.cbl:217}, so the move truncates on the right. Driven in
     * {@link WidthTraps#theEightyByteMessageLosesItsLastTwoCharacters()}.
     */
    private static final int WS_MESSAGE_LENGTH = 80;

    /** The exact text {@code app/cbl/COUSR03C.cbl:179-180} moves into {@code WS-MESSAGE}. */
    private static final String BLANK_USER_ID_MESSAGE = "User ID can NOT be empty...";

    /**
     * The copybook's geometry as a validated layout, and the subject of {@link RedefinesOverlays}.
     *
     * <p>Constructing it <em>is</em> the geometry assertion: {@link FixedWidthRecord.RecordLayout}
     * rejects a gap, an unintended overlap, an overlay reaching past declared storage, a repeated
     * referable name, and any total other than {@value #SYMBOLIC_MAP_LENGTH}. A single wrong constant
     * above therefore fails class initialisation instead of quietly shifting every later offset.
     *
     * <p>The {@code xxxL} halfword is declared as {@code FILLER} rather than under its own name
     * deliberately. It is {@code COMP} - binary - and {@link FixedWidthRecord.PictureKind} has no
     * binary category, because no persisted record in this estate holds one; describing two bytes of
     * halfword as characters or as zoned digits would misdescribe them. It is reserved storage here,
     * its name is asserted from {@link #metadataNamesOf(String)} instead, and it is never a payload
     * member in any case - which is the point {@link MetadataIsNotPayload} makes.
     *
     * <p>Immutable: {@link FixedWidthRecord.RecordLayout} is a record over an unmodifiable span list,
     * so publishing it as a constant introduces no shared mutable state (B9). Each test that writes
     * bytes takes its own fresh {@link FixedWidthRecord} from it.
     */
    private static final FixedWidthRecord.RecordLayout SYMBOLIC_MAP_LAYOUT = symbolicMapLayout();

    /**
     * A fixed instant, so every time-derived expectation is exact on every run and every machine (B7).
     *
     * <p>The value is the timestamp in the version footer at {@code app/cbl/COUSR03C.cbl:358},
     * {@code 2022-07-19 23:12:35}, which makes it traceable rather than arbitrary. Read through
     * {@link Clock#fixed(Instant, java.time.ZoneId)} at {@link ZoneOffset#UTC}.
     */
    private static final Instant FIXED_INSTANT = Instant.parse("2022-07-19T23:12:35Z");

    /** {@code MM/DD/YY} for {@link #FIXED_INSTANT} - the shape {@code CURDATE} receives. */
    private static final String FIXED_CURDATE = "07/19/22";

    /** {@code HH:MM:SS} for {@link #FIXED_INSTANT} - the shape {@code CURTIME} receives. */
    private static final String FIXED_CURTIME = "23:12:35";

    /**
     * The complete set of JSON member names this payload may emit: the eleven map members and the
     * three state members, and nothing else.
     */
    private static final Set<String> EXPECTED_JSON_MEMBERS = expectedJsonMembers();

    /**
     * Type-name fragments that would betray a credential mechanism this migration must not introduce.
     *
     * <p>Scanned reflectively over the whole declared surface of the type in
     * {@link SecurityPosture#noSecurityFrameworkTypeIsReachable()}. On this screen the point is
     * stronger than on the sign-on screen: there is no credential here at all, so any of these
     * appearing would be an invention rather than a hardening.
     */
    private static final List<String> FORBIDDEN_SECURITY_MARKERS = List.of("PasswordEncoder",
            "BCrypt", "MessageDigest", "org.springframework.security", "Jwt", "Cipher", "SecretKey");

    /** Member-name fragments that would mean a password had been added to this screen. */
    private static final List<String> FORBIDDEN_CREDENTIAL_NAMES = List.of("passwd", "password",
            "pwd", "secret", "credential", "token");

    // =================================================================================================
    // Construction of the constants above. Static, side-effect free, called once each.
    // =================================================================================================

    private static FixedWidthRecord.RecordLayout symbolicMapLayout() {
        List<FixedWidthRecord.FieldSpan> spans = new ArrayList<>();
        // 02 FILLER PIC X(12) - COUSR03.CPY:18, the TIOAPFX=YES prefix.
        spans.add(FixedWidthRecord.FieldSpan.filler(0, TIOAPFX_PREFIX_LENGTH));
        int cursor = TIOAPFX_PREFIX_LENGTH;
        for (int index = 0; index < DFHMDF_NAMED; index++) {
            String field = SCREEN_FIELDS.get(index);
            // 02 xxxL COMP PIC S9(4) - a binary halfword, declared as reserved storage.
            spans.add(FixedWidthRecord.FieldSpan.filler(cursor, LENGTH_ITEM_LENGTH));
            cursor += LENGTH_ITEM_LENGTH;
            // 02 xxxF PICTURE X, and 02 FILLER REDEFINES xxxF / 03 xxxA PICTURE X over that one byte.
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
        // The DECLARED total is passed, never the cursor this loop happened to reach: passing the
        // cursor would only prove the layout consistent with itself and would catch nothing.
        return FixedWidthRecord.RecordLayout.of(SYMBOLIC_MAP_LENGTH,
                spans.toArray(FixedWidthRecord.FieldSpan[]::new));
    }

    private static Set<String> expectedJsonMembers() {
        Set<String> members = new LinkedHashSet<>(wireNamesOf(MAP_MEMBERS));
        members.addAll(STATE_MEMBERS);
        return Set.copyOf(members);
    }

    /** The four metadata item names a screen field contributes, none of which is ever payload. */
    private static List<String> metadataNamesOf(String screenField) {
        return List.of(screenField + "L", screenField + "F", screenField + "A", screenField + "O");
    }

    // =================================================================================================
    // Shared, stateless helpers. Every one returns a fresh value; none caches, mutates or memoises
    // (B9), so no test can observe the effect of another having run (B7).
    // =================================================================================================

    /**
     * A codec over the explicitly named code page (B8). A fresh instance per call, because the codec
     * is cheap and immutable and sharing one would be shared state for no benefit.
     */
    private static FixedWidthCodec codec() {
        return new FixedWidthCodec(MAP_CHARSET);
    }

    /**
     * An {@link ObjectMapper} configured exactly as {@code config.WebConfig} configures the
     * application's shared one, and for the reasons that class documents.
     *
     * <p>A default mapper would be the wrong instrument here and would make this suite assert the
     * wrong thing. Four settings matter and all four are stated rather than inherited:
     * {@code USE_BIG_DECIMAL_FOR_FLOATS} and {@code WRITE_BIGDECIMAL_AS_PLAIN} are enabled so no
     * numeric value could route through a binary floating-point type or serialise in exponent
     * notation; {@code FAIL_ON_TRAILING_TOKENS} is enabled so a body either binds whole or is refused;
     * and {@code ACCEPT_EMPTY_STRING_AS_NULL_OBJECT} is <em>disabled</em> so an empty or all-spaces
     * {@code PIC X(n)} value stays the real screen data it is instead of becoming {@code null}. That
     * last one is what makes the blank-value cases in {@link ValidationConstraints} and
     * {@link SpacesAndLowValues} mean anything at all: under a default mapper an empty string would be
     * silently nulled and the distinction the COBOL draws at
     * {@code app/cbl/COUSR03C.cbl:145} and {@code :177} - {@code = SPACES OR LOW-VALUES} - would be
     * unobservable.
     *
     * <p>No naming strategy is applied, so each property name still traces 1:1 to an {@code xxxI}
     * item; no inclusion filter is applied, so a {@code null} member is emitted rather than dropped;
     * and no trimming converter is registered, so trailing padding survives the wire.
     */
    private static ObjectMapper webConfigEquivalentMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        mapper.configure(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN, true);
        mapper.disable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT);
        return mapper;
    }

    /** Serialises through the configured mapper, translating the checked failure. */
    private static String serialise(UserDeleteRequest request) {
        try {
            return webConfigEquivalentMapper().writeValueAsString(request);
        } catch (IOException failure) {
            throw new UncheckedIOException("Serialising a UserDeleteRequest must not fail", failure);
        }
    }

    /** Deserialises through the configured mapper, translating the checked failure. */
    private static UserDeleteRequest deserialise(String json) {
        try {
            return webConfigEquivalentMapper().readValue(json, UserDeleteRequest.class);
        } catch (IOException failure) {
            throw new UncheckedIOException("Deserialising a UserDeleteRequest must not fail",
                    failure);
        }
    }

    /** The member names actually present in a serialised payload. */
    private static Set<String> jsonMembersOf(UserDeleteRequest request) {
        try {
            Map<String, Object> tree = webConfigEquivalentMapper()
                    .readValue(serialise(request), new TypeReference<Map<String, Object>>() { });
            return tree.keySet();
        } catch (IOException failure) {
            throw new UncheckedIOException("Reading a serialised UserDeleteRequest must not fail",
                    failure);
        }
    }

    /** Builds a request from the eleven map values in component order, plus the three state members. */
    private static UserDeleteRequest requestOf(List<String> mapValues, NavigationContext context,
            String aid, UserDeleteRequest.Cu03Info extension) {
        return new UserDeleteRequest(mapValues.get(0), mapValues.get(1), mapValues.get(2),
                mapValues.get(3), mapValues.get(4), mapValues.get(5), mapValues.get(6),
                mapValues.get(7), mapValues.get(8), mapValues.get(9), mapValues.get(10),
                context, aid, extension);
    }

    /** The same, with the extension left at the state a cold start sees. */
    private static UserDeleteRequest requestOf(List<String> mapValues, NavigationContext context,
            String aid) {
        return requestOf(mapValues, context, aid, UserDeleteRequest.Cu03Info.initial());
    }

    /** The eleven map members of a request, in component order. Permits {@code null} entries. */
    private static List<String> mapValuesOf(UserDeleteRequest request) {
        return Arrays.asList(request.trnName(), request.title01(), request.curDate(),
                request.pgmName(), request.title02(), request.curTime(), request.usrIdIn(),
                request.fName(), request.lName(), request.usrType(), request.errMsg());
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

    /** Eleven values, each a run of spaces at that member's declared width - COBOL's {@code SPACES}. */
    private static List<String> spaceFilledMapValues() {
        List<String> values = new ArrayList<>(DFHMDF_NAMED);
        for (int index = 0; index < DFHMDF_NAMED; index++) {
            values.add(spaces(DECLARED_WIDTHS.get(index)));
        }
        return values;
    }

    /**
     * A fully populated request: every map member exactly its declared width, so the instance is a
     * faithful image of a painted screen rather than a convenient shorthand.
     *
     * <p>The header members carry what {@code app/cbl/COUSR03C.cbl:247-250} moves into them and the
     * date and time carry the fixed-clock renderings, so nothing here depends on when the suite runs.
     */
    private static UserDeleteRequest populatedRequest() {
        List<String> values = Arrays.asList(
                UserDeleteRequest.TRANSACTION_ID,
                ScreenTitles.CCDA_TITLE01,
                FIXED_CURDATE,
                UserDeleteRequest.PROGRAM_NAME,
                ScreenTitles.CCDA_TITLE02,
                FIXED_CURTIME,
                "USER0001",
                codec().movePicX("Given", UserDeleteRequest.FNAME_LENGTH),
                codec().movePicX("Family", UserDeleteRequest.LNAME_LENGTH),
                NavigationContext.USER_TYPE_USER,
                spaces(UserDeleteRequest.ERRMSG_LENGTH));
        return requestOf(values, NavigationContext.empty(), PfKeyResolver.AidKey.ENTER.token(),
                UserDeleteRequest.Cu03Info.initial());
    }

    /** A run of {@code width} spaces - COBOL's {@code SPACES} figurative constant, materialised. */
    private static String spaces(int width) {
        return " ".repeat(width);
    }

    /**
     * A run of {@code width} {@code NUL} characters - COBOL's {@code LOW-VALUES}, materialised.
     *
     * <p>{@code LOW-VALUES} is the lowest value in the collating sequence, which for a single-byte
     * code page is {@code X'00'}. {@code app/cbl/COUSR03C.cbl:97} moves it into the whole output map,
     * and lines 145 and 177 test for it <em>alongside</em> {@code SPACES}, so the two are distinct
     * values that the program happens to answer alike. The payload must therefore carry each as
     * itself.
     */
    private static String lowValues(int width) {
        return "\u0000".repeat(width);
    }

    /** A validator built per call, so no factory or validator instance is shared state (B9). */
    private static Set<ConstraintViolation<UserDeleteRequest>> violationsOf(
            UserDeleteRequest request) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(request);
        }
    }

    // =================================================================================================
    // 1. THE PROJECTION OF 01 COUSR3AI.
    //
    // Eleven map members and three state members, in the copybook's own order, each map member
    // traceable to exactly one name-labelled DFHMDF definition (G9).
    // =================================================================================================

    @Nested
    @DisplayName("Projection of 01 COUSR3AI - eleven map members, in copybook order")
    class MapProjection {

        @Test
        @DisplayName("fourteen components: the eleven map members then the three state carriers")
        void componentCensus() {
            RecordComponent[] components = UserDeleteRequest.class.getRecordComponents();
            assertThat(components)
                    .as("eleven name-labelled DFHMDF fields plus navigationContext, aid and cu03Info")
                    .hasSize(COMPONENT_COUNT);

            List<String> names = Arrays.stream(components).map(RecordComponent::getName).toList();
            assertThat(names.subList(0, DFHMDF_NAMED))
                    .as("the map members must appear in 01 COUSR3AI declaration order")
                    .containsExactlyElementsOf(MAP_MEMBERS);
            assertThat(names.subList(DFHMDF_NAMED, COMPONENT_COUNT))
                    .as("the three members with no DFHMDF behind them come last")
                    .containsExactlyElementsOf(STATE_MEMBERS);
        }

        @Test
        @DisplayName("every map member is a String, because every xxxI item is PIC X(n)")
        void everyMapMemberIsCharacter() {
            RecordComponent[] components = UserDeleteRequest.class.getRecordComponents();
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                assertThat(components[index].getType())
                        .as("%s projects %s, which is PIC X(%d) at COUSR03.CPY:%d",
                                MAP_MEMBERS.get(index), SYMBOLIC_MAP_ITEMS.get(index),
                                DECLARED_WIDTHS.get(index), COPYBOOK_LINES.get(index))
                        .isEqualTo(String.class);
            }
            assertThat(components[DFHMDF_NAMED].getType())
                    .as("the communication area travels as itself, not as a flattened string")
                    .isEqualTo(NavigationContext.class);
            assertThat(components[DFHMDF_NAMED + 1].getType())
                    .as("the AID token is the five-character CCARD-AID literal")
                    .isEqualTo(String.class);
            assertThat(components[DFHMDF_NAMED + 2].getType())
                    .as("the CU03 extension travels as its own typed group, not as 34 loose members")
                    .isEqualTo(UserDeleteRequest.Cu03Info.class);
        }

        @Test
        @DisplayName("no member anywhere in the type is double or float, pageNum included")
        void noFloatingPointMemberExists() {
            // Gate G22. Every PIC X item is a String and the one numeric item, CDEMO-CU03-PAGE-NUM
            // PIC 9(08), is scale-free - an unsigned integer picture with no V and no decimal
            // position - so int is the faithful carrier. A binary floating-point type would be wrong
            // twice over: it cannot represent every eight-digit value exactly, and it would admit a
            // fractional page number the picture cannot hold.
            for (RecordComponent component : UserDeleteRequest.class.getRecordComponents()) {
                assertThat(component.getType())
                        .as("%s must not be a binary floating-point type", component.getName())
                        .isNotEqualTo(double.class).isNotEqualTo(Double.class)
                        .isNotEqualTo(float.class).isNotEqualTo(Float.class);
            }
            for (RecordComponent component
                    : UserDeleteRequest.Cu03Info.class.getRecordComponents()) {
                assertThat(component.getType())
                        .as("%s must not be a binary floating-point type", component.getName())
                        .isNotEqualTo(double.class).isNotEqualTo(Double.class)
                        .isNotEqualTo(float.class).isNotEqualTo(Float.class);
            }
            assertThat(UserDeleteRequest.Cu03Info.class.getRecordComponents()[2].getType())
                    .as("CDEMO-CU03-PAGE-NUM PIC 9(08) is a scale-free integer picture")
                    .isEqualTo(int.class);
        }

        @Test
        @DisplayName("MAP_FIELD_COUNT is the mapset's own count, and 15 definitions stay unexposed")
        void countsAreTheMapsetsOwn() {
            assertThat(UserDeleteRequest.MAP_FIELD_COUNT).isEqualTo(DFHMDF_NAMED);
            assertThat(MAP_MEMBERS).hasSize(UserDeleteRequest.MAP_FIELD_COUNT);
            assertThat(SYMBOLIC_MAP_ITEMS).hasSize(UserDeleteRequest.MAP_FIELD_COUNT);
            assertThat(SCREEN_FIELDS).hasSize(UserDeleteRequest.MAP_FIELD_COUNT);
            assertThat(DECLARED_WIDTHS).hasSize(UserDeleteRequest.MAP_FIELD_COUNT);
            assertThat(MAPSET_LENGTHS).hasSize(UserDeleteRequest.MAP_FIELD_COUNT);
            assertThat(PUBLISHED_WIDTHS).hasSize(UserDeleteRequest.MAP_FIELD_COUNT);
            assertThat(PUBLISHED_ITEM_NAMES).hasSize(UserDeleteRequest.MAP_FIELD_COUNT);
            assertThat(COPYBOOK_LINES).hasSize(UserDeleteRequest.MAP_FIELD_COUNT);
            assertThat(MAPSET_LINES).hasSize(UserDeleteRequest.MAP_FIELD_COUNT);
            assertThat(REDEFINES_LINES).hasSize(UserDeleteRequest.MAP_FIELD_COUNT);

            // app/bms/COUSR03.bms holds 26 DFHMDF definitions. The 15 without a name label are static
            // screen furniture - 'Tran:', 'Date:', 'Program:', 'Time:', the field captions and the
            // PF-key legend - and CICS gives an unlabelled field no symbolic-map item at all, so it
            // cannot be a payload member. Stating the split here stops the difference reading as
            // fifteen missing fields.
            assertThat(DFHMDF_TOTAL - DFHMDF_NAMED)
                    .as("26 definitions less the 11 name-labelled ones are screen literals")
                    .isEqualTo(15);
            assertThat(DFHMDF_NAMED).isLessThan(DFHMDF_TOTAL);
        }

        @ParameterizedTest(name = "{0} projects {1}")
        @CsvSource({"trnName,TRNNAMEI", "title01,TITLE01I", "curDate,CURDATEI", "pgmName,PGMNAMEI",
            "title02,TITLE02I", "curTime,CURTIMEI", "usrIdIn,USRIDINI", "fName,FNAMEI",
            "lName,LNAMEI", "usrType,USRTYPEI", "errMsg,ERRMSGI"})
        @DisplayName("each member names the symbolic-map item it projects, input suffix kept")
        void cobolItemNames(String member, String cobolItem) {
            int index = MAP_MEMBERS.indexOf(member);
            assertThat(index).as("%s must be a declared member", member).isNotNegative();
            assertThat(SYMBOLIC_MAP_ITEMS.get(index)).isEqualTo(cobolItem);
            assertThat(PUBLISHED_ITEM_NAMES.get(index))
                    .as("the published constant must spell the item exactly as the copybook does")
                    .isEqualTo(cobolItem);
            assertThat(cobolItem)
                    .as("the I suffix distinguishes the input view from the xxxO output view")
                    .endsWith("I")
                    .startsWith(SCREEN_FIELDS.get(index));
        }

        @Test
        @DisplayName("the screen identity constants match WS-TRANID, WS-PGMNAME, DFHMDI and DFHMSD")
        void screenIdentity() {
            assertThat(UserDeleteRequest.TRANSACTION_ID)
                    .as("WS-TRANID PIC X(04) VALUE 'CU03', COUSR03C.cbl:37, and "
                            + "DEFINE TRANSACTION(CU03) at CARDDEMO.CSD:479")
                    .isEqualTo("CU03")
                    .hasSize(UserDeleteRequest.TRNNAME_LENGTH);
            assertThat(UserDeleteRequest.PROGRAM_NAME)
                    .as("WS-PGMNAME PIC X(08) VALUE 'COUSR03C', COUSR03C.cbl:36, and "
                            + "PROGRAM(COUSR03C) at CARDDEMO.CSD:480")
                    .isEqualTo("COUSR03C")
                    .hasSize(UserDeleteRequest.PGMNAME_LENGTH);
            assertThat(UserDeleteRequest.MAP_NAME)
                    .as("COUSR3A DFHMDI, COUSR03.bms:26, sent at COUSR03C.cbl:220")
                    .isEqualTo("COUSR3A");
            assertThat(UserDeleteRequest.MAPSET_NAME)
                    .as("COUSR03 DFHMSD, COUSR03.bms:19, named at COUSR03C.cbl:221")
                    .isEqualTo("COUSR03");
            assertThat(UserDeleteRequest.MAP_NAME)
                    .as("map and mapset share the COUSR stem but are not the same name, and the "
                            + "symbolic map's group names - COUSR3AI and COUSR3AO - are formed from "
                            + "the MAP name, not the mapset's")
                    .startsWith("COUSR")
                    .isNotEqualTo(UserDeleteRequest.MAPSET_NAME);
            assertThat(UserDeleteRequest.MAPSET_NAME).startsWith("COUSR");
        }

        @Test
        @DisplayName("no static field of this type is mutable")
        void noStaticMutableState() {
            // Gate G53. COBOL WORKING-STORAGE is per-task storage; hoisting it into a static Java
            // field would make it per-JVM and would break both request isolation and test
            // determinism. Every static this type declares must therefore be final.
            for (Field field : UserDeleteRequest.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("static field %s must be final", field.getName())
                            .isTrue();
                }
            }
            for (Field field : UserDeleteRequest.Cu03Info.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("static field %s must be final", field.getName())
                            .isTrue();
                }
            }
        }

        @Test
        @DisplayName("the type is a record, so it is immutable and safe to share and to compare")
        void theTypeIsAValueCarrier() {
            assertThat(UserDeleteRequest.class.isRecord()).isTrue();
            assertThat(UserDeleteRequest.Cu03Info.class.isRecord()).isTrue();

            UserDeleteRequest one = populatedRequest();
            UserDeleteRequest other = populatedRequest();
            assertThat(one).isEqualTo(other).hasSameHashCodeAs(other);
            for (Field field : UserDeleteRequest.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("instance field %s of a record must be final", field.getName())
                            .isTrue();
                }
            }
        }
    }

    // =================================================================================================
    // 2. ASYMMETRY #3 - THERE IS NO PASSWORD FIELD ON THIS SCREEN.
    //
    // The defining subject of this file. COUSR02 (update) carries twelve map fields; COUSR03 (delete)
    // carries eleven, and the difference is exactly PASSWD. A delete needs the key and nothing else:
    // the operator identifies the user, confirms, and the record goes. There is no credential to
    // re-key because nothing is being written.
    //
    // Three independent proofs, so this cannot later be mistaken for an omission:
    //   * grep -c 'PASSWD' app/cbl/COUSR03C.cbl  -> 0   (the program never names one)
    //   * grep -c 'PASSWD' app/bms/COUSR03.bms   -> 0   (the mapset never paints one)
    //   * the COUSR03.CPY tail is six lines earlier than COUSR02.CPY's, which is exactly one
    //     xxxL/xxxF/REDEFINES/xxxA/FILLER/xxxI group.
    // =================================================================================================

    @Nested
    @DisplayName("Asymmetry #3 - the absent password, proved three ways (B5, G9)")
    class AbsentPasswordField {

        @Test
        @DisplayName("no member is named for a credential, under any spelling")
        void noCredentialMemberExists() {
            List<String> declared = new ArrayList<>();
            for (RecordComponent component : UserDeleteRequest.class.getRecordComponents()) {
                declared.add(component.getName().toLowerCase(Locale.ROOT));
            }
            for (RecordComponent component
                    : UserDeleteRequest.Cu03Info.class.getRecordComponents()) {
                declared.add(component.getName().toLowerCase(Locale.ROOT));
            }

            for (String name : declared) {
                for (String forbidden : FORBIDDEN_CREDENTIAL_NAMES) {
                    assertThat(name)
                            .as("%s reads as a credential; COUSR03 declares none, so any such member "
                                    + "would be an invented field the program cannot observe", name)
                            .doesNotContain(forbidden);
                }
            }
            assertThat(declared).hasSize(COMPONENT_COUNT + EXTENSION_MEMBERS.size());
        }

        @Test
        @DisplayName("nothing in the map, the mapset or the symbolic map spells PASSWD")
        void thePasswordExistsNowhereInTheScreenContract() {
            // The evidence, restated so the reasoning survives without re-running the greps:
            //   grep -c 'PASSWD' app/cbl/COUSR03C.cbl   -> 0
            //   grep -c 'PASSWD' app/bms/COUSR03.bms    -> 0
            //   grep -c 'PASSWD' app/cpy-bms/COUSR03.CPY -> 0
            // Three files, three zeroes. The field is absent from the program, from the screen and
            // from the symbolic map, which is every layer at which it could exist.
            for (String item : SYMBOLIC_MAP_ITEMS) {
                assertThat(item).doesNotContain("PASSWD");
            }
            for (String field : SCREEN_FIELDS) {
                assertThat(field).doesNotContain("PASSWD");
            }
            for (String member : PUBLISHED_ITEM_NAMES) {
                assertThat(member).doesNotContain("PASSWD");
            }
            assertThat(SYMBOLIC_MAP_LAYOUT.hasSpan("PASSWDI"))
                    .as("the copybook's own geometry has no such span")
                    .isFalse();
            assertThat(SYMBOLIC_MAP_LAYOUT.hasSpan("PASSWDF")).isFalse();
            assertThat(SYMBOLIC_MAP_LAYOUT.hasSpan("PASSWDA")).isFalse();
        }

        @Test
        @DisplayName("the tail is shifted up by exactly one field, and so by exactly six lines")
        void theTailIsShiftedUpByExactlyOneField() {
            // This is the mechanical proof of eleven against twelve, and it does not depend on
            // reading either copybook at run time: the line numbers are transcribed constants.
            //
            // COUSR03.CPY   ... LNAMEI 72, USRTYPEI 78, ERRMSGI 84
            // COUSR02.CPY   ... LNAMEI 72, PASSWDI 78, USRTYPEI 84, ERRMSGI 90
            //
            // The first nine items agree line for line. From the tenth on, COUSR02 is six lines
            // ahead, and six lines is exactly one field group: xxxL, xxxF, 02 FILLER REDEFINES,
            // 03 xxxA, 02 FILLER X(4) and xxxI.
            assertThat(COPYBOOK_LINES.subList(0, 9))
                    .as("the two copybooks agree exactly up to and including LNAMEI")
                    .containsExactlyElementsOf(COUSR02_COPYBOOK_LINES.subList(0, 9));

            int usrTypeIndex = MAP_MEMBERS.indexOf("usrType");
            int errMsgIndex = MAP_MEMBERS.indexOf("errMsg");
            assertThat(COPYBOOK_LINES.get(usrTypeIndex)).isEqualTo(78);
            assertThat(COPYBOOK_LINES.get(errMsgIndex)).isEqualTo(84);
            assertThat(COUSR02_COPYBOOK_LINES.get(usrTypeIndex)).isEqualTo(84);
            assertThat(COUSR02_COPYBOOK_LINES.get(errMsgIndex)).isEqualTo(90);

            int shift = COUSR02_COPYBOOK_LINES.get(usrTypeIndex) - COPYBOOK_LINES.get(usrTypeIndex);
            assertThat(shift)
                    .as("one field group is six copybook lines: xxxL, xxxF, FILLER REDEFINES, xxxA, "
                            + "FILLER X(4) and xxxI")
                    .isEqualTo(6)
                    .isEqualTo(COUSR02_COPYBOOK_LINES.get(errMsgIndex)
                            - COPYBOOK_LINES.get(errMsgIndex));
            assertThat(COUSR02_PASSWD_LINE)
                    .as("PASSWDI occupies, on COUSR02, the very line USRTYPEI occupies here")
                    .isEqualTo(COPYBOOK_LINES.get(usrTypeIndex));
            assertThat(UserDeleteRequest.MAP_FIELD_COUNT)
                    .as("one field fewer than COUSR02, and that field is the password")
                    .isEqualTo(COUSR02_MAP_FIELD_COUNT - 1);
        }

        @Test
        @DisplayName("the password's structural position holds usrType PIC X(1), not an X(8) field")
        void thePasswordPositionHoldsTheTypeField() {
            // On COUSR02 the password sits between LNAMEI and USRTYPEI. Asserting "no member is eight
            // wide" would be wrong here - usrIdIn is eight, and legitimately so - so the assertion is
            // positional: the member immediately after lName is usrType, one character wide, and the
            // two are adjacent with nothing between them.
            RecordComponent[] components = UserDeleteRequest.class.getRecordComponents();
            int lNameIndex = MAP_MEMBERS.indexOf("lName");
            assertThat(components[lNameIndex].getName()).isEqualTo("lName");
            assertThat(components[lNameIndex + 1].getName())
                    .as("nothing separates the family name from the user type on this screen")
                    .isEqualTo("usrType");
            assertThat(DECLARED_WIDTHS.get(lNameIndex + 1))
                    .as("SEC-USR-TYPE PIC X(01), not SEC-USR-PWD PIC X(08)")
                    .isEqualTo(1)
                    .isNotEqualTo(SecUserRecord.SEC_USR_PWD_LENGTH);
            assertThat(components[lNameIndex + 2].getName())
                    .as("and the error message follows immediately after the type")
                    .isEqualTo("errMsg");

            // The same adjacency in the copybook's byte geometry: USRTYPEI begins seven bytes after
            // LNAMEI ends, which is one field prefix and no intervening data item.
            FixedWidthRecord.FieldSpan lastName = SYMBOLIC_MAP_LAYOUT.span("LNAMEI");
            FixedWidthRecord.FieldSpan userType = SYMBOLIC_MAP_LAYOUT.span("USRTYPEI");
            assertThat(userType.offset() - lastName.endOffsetExclusive())
                    .as("exactly one xxxL/xxxF/FILLER prefix separates them - no hidden field")
                    .isEqualTo(FIELD_PREFIX_LENGTH);
        }

        @Test
        @DisplayName("the geometry is 235 data bytes, not the 243 a twelfth X(8) field would make")
        void theGeometryItselfExcludesAPasswordField() {
            assertThat(DECLARED_WIDTHS.stream().mapToInt(Integer::intValue).sum())
                    .isEqualTo(PAYLOAD_WIDTH_TOTAL)
                    .isEqualTo(235);
            assertThat(SYMBOLIC_MAP_LENGTH)
                    .as("12 + 11 x 7 + 235")
                    .isEqualTo(TIOAPFX_PREFIX_LENGTH + DFHMDF_NAMED * FIELD_PREFIX_LENGTH
                            + PAYLOAD_WIDTH_TOTAL);

            // A twelfth field of the password's width would add its eight data bytes and its own
            // seven-byte prefix: fifteen bytes, taking 324 to 339. The declared group is 324.
            int withAPasswordField = SYMBOLIC_MAP_LENGTH + FIELD_PREFIX_LENGTH
                    + SecUserRecord.SEC_USR_PWD_LENGTH;
            assertThat(withAPasswordField).isEqualTo(339);
            assertThat(UserDeleteRequest.SYMBOLIC_MAP_LENGTH)
                    .as("the type publishes the eleven-field geometry, not the twelve-field one")
                    .isEqualTo(SYMBOLIC_MAP_LENGTH)
                    .isNotEqualTo(withAPasswordField);
        }

        @Test
        @DisplayName("the serialised payload carries no credential key either")
        void theWireFormatCarriesNoCredential() {
            Set<String> members = jsonMembersOf(populatedRequest());
            assertThat(members).containsExactlyInAnyOrderElementsOf(EXPECTED_JSON_MEMBERS);
            for (String member : members) {
                for (String forbidden : FORBIDDEN_CREDENTIAL_NAMES) {
                    assertThat(member.toLowerCase(Locale.ROOT)).doesNotContain(forbidden);
                }
            }
            assertThat(members).hasSize(COMPONENT_COUNT);
        }

        @Test
        @DisplayName("usrIdIn comes FIRST, before the names - COUSR02's order, not COUSR01's")
        void theIdFieldComesFirstAndIsNamedForUsridin() {
            // TRAP 1, two halves.
            //
            // The name: the item is USRIDIN, not USERID. COUSR01 (add) and COSGN00 (sign-on) both call
            // theirs USERID; COUSR02 (update) and COUSR03 (delete) both call theirs USRIDIN. The
            // member is named for the item it projects, so a name-keyed field comparison lines up.
            //
            // The order: here the id is keyed first and the names are then filled in from the record
            // that was found - COUSR03C.cbl:157-159 blanks them, :161 reads the file, :165-167 refills
            // them. On COUSR01 the operator types the names first and the id last. Same three fields,
            // opposite direction of flow, and the declaration order records which.
            assertThat(UserDeleteRequest.USRIDIN_FIELD).isEqualTo("USRIDINI")
                    .isNotEqualTo("USERIDI");
            int idIndex = MAP_MEMBERS.indexOf("usrIdIn");
            assertThat(idIndex)
                    .as("the id is the seventh item - straight after the six header fields")
                    .isEqualTo(6);
            assertThat(idIndex)
                    .as("and it precedes both name fields, which COUSR01 reverses")
                    .isLessThan(MAP_MEMBERS.indexOf("fName"))
                    .isLessThan(MAP_MEMBERS.indexOf("lName"));
            assertThat(SYMBOLIC_MAP_LAYOUT.span("USRIDINI").offset())
                    .isLessThan(SYMBOLIC_MAP_LAYOUT.span("FNAMEI").offset());
        }
    }

    // =================================================================================================
    // 3. WIDTH TRAPS.
    //
    // Every width comes from an xxxI PICTURE clause and is corroborated by the DFHMDF LENGTH= operand.
    // Three of the eleven are traps a plausible transcription gets wrong, and each is driven here
    // against the codec rather than against a substring, so the direction of any truncation is the one
    // COBOL uses (B11).
    // =================================================================================================

    @Nested
    @DisplayName("Width traps - eight not nine, seventy-eight not eighty")
    class WidthTraps {

        @ParameterizedTest(name = "component {0}")
        @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
        @DisplayName("each published width equals its PICTURE clause and its DFHMDF LENGTH")
        void publishedWidthsMatchBothSources(int index) {
            assertThat(PUBLISHED_WIDTHS.get(index))
                    .as("%s is %s PIC X(%d) at COUSR03.CPY:%d, and %s DFHMDF LENGTH=%d at "
                            + "COUSR03.bms:%d", MAP_MEMBERS.get(index),
                            SYMBOLIC_MAP_ITEMS.get(index), DECLARED_WIDTHS.get(index),
                            COPYBOOK_LINES.get(index), SCREEN_FIELDS.get(index),
                            MAPSET_LENGTHS.get(index), MAPSET_LINES.get(index))
                    .isEqualTo(DECLARED_WIDTHS.get(index))
                    .isEqualTo(MAPSET_LENGTHS.get(index));
            assertThat(SYMBOLIC_MAP_LAYOUT.span(SYMBOLIC_MAP_ITEMS.get(index)).length())
                    .as("and the copybook geometry agrees")
                    .isEqualTo(DECLARED_WIDTHS.get(index));
        }

        @Test
        @DisplayName("the eleven widths are 4, 40, 8, 8, 40, 8, 8, 20, 20, 1, 78")
        void declaredWidths() {
            assertThat(PUBLISHED_WIDTHS)
                    .containsExactly(4, 40, 8, 8, 40, 8, 8, 20, 20, 1, 78);
            assertThat(UserDeleteRequest.MAP_FIELDS_WIDTH_TOTAL)
                    .as("the eleven xxxI items occupy 235 bytes between them")
                    .isEqualTo(PAYLOAD_WIDTH_TOTAL);
        }

        @Test
        @DisplayName("TRAP 2 - curTime is 8 here; only COSGN00 widens its time field to 9")
        void curTimeIsEightNotNine() {
            // COUSR03.CPY:54 declares CURTIMEI PIC X(8) and COUSR03.bms:70 corroborates with
            // LENGTH=8 and INITIAL='hh:mm:ss' - eight characters. COSGN00 is the single screen in the
            // estate whose time field is nine wide, and transcribing its nine onto this screen would
            // shift every byte after offset 47 of the symbolic map.
            assertThat(UserDeleteRequest.CURTIME_LENGTH)
                    .as("CURTIMEI PIC X(8), COUSR03.CPY:54")
                    .isEqualTo(8)
                    .isEqualTo(UserDeleteRequest.CURDATE_LENGTH);
            assertThat(UserDeleteRequest.CURTIME_LENGTH)
                    .as("eight, not the nine COSGN00 alone declares")
                    .isNotEqualTo(9);

            // The header renders HH:MM:SS - eight characters - so on this screen the move is exact:
            // no padding on the right, unlike the nine-wide sign-on field.
            DateHeader header = DateHeader.from(codec(), Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
            assertThat(header.wsCurtimeHhMmSs())
                    .isEqualTo(FIXED_CURTIME)
                    .hasSize(DateHeader.WS_CURTIME_HH_MM_SS_LENGTH);
            assertThat(codec().movePicX(header.wsCurtimeHhMmSs(),
                    UserDeleteRequest.CURTIME_LENGTH))
                    .as("eight into eight is neither padded nor truncated")
                    .isEqualTo(FIXED_CURTIME)
                    .hasSize(UserDeleteRequest.CURTIME_LENGTH);
        }

        @Test
        @DisplayName("the header date fills curDate exactly, on a clock that never moves")
        void theHeaderDateFillsCurDateExactly() {
            DateHeader header = DateHeader.from(codec(), Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
            assertThat(header.wsCurdateMmDdYy())
                    .as("MM/DD/YY, the shape COUSR03C.cbl moves into CURDATE after :245")
                    .isEqualTo(FIXED_CURDATE)
                    .hasSize(DateHeader.WS_CURDATE_MM_DD_YY_LENGTH);
            assertThat(codec().movePicX(header.wsCurdateMmDdYy(),
                    UserDeleteRequest.CURDATE_LENGTH))
                    .isEqualTo(FIXED_CURDATE)
                    .hasSize(UserDeleteRequest.CURDATE_LENGTH);
            assertThat(codec().charset())
                    .as("the code page is named on every call, never inherited (B8)")
                    .isEqualTo(MAP_CHARSET);
        }

        @Test
        @DisplayName("TRAP 3 - the 80-byte message loses its last two characters on the way in")
        void theEightyByteMessageLosesItsLastTwoCharacters() {
            // COUSR03C.cbl:38 declares 05 WS-MESSAGE PIC X(80) and :217 does
            // MOVE WS-MESSAGE TO ERRMSGO OF COUSR3AO, whose item is PIC X(78). An alphanumeric MOVE
            // is left-justified and truncates on the RIGHT, so the final two characters are lost.
            // The plan names MOVE the dominant parity risk - 2,795 sites - which is why this goes
            // through the codec's move rule and never through a bare assignment or substring (B11).
            assertThat(WS_MESSAGE_LENGTH)
                    .as("WS-MESSAGE is two characters wider than the item it is moved into")
                    .isEqualTo(UserDeleteRequest.ERRMSG_LENGTH + 2);

            // A sending value whose last two characters are non-space, so the loss is visible rather
            // than hidden in padding.
            String eightyCharacters = "M".repeat(WS_MESSAGE_LENGTH - 2) + "XY";
            assertThat(eightyCharacters).hasSize(WS_MESSAGE_LENGTH);

            String moved = codec().movePicX(eightyCharacters, UserDeleteRequest.ERRMSG_LENGTH);
            assertThat(moved)
                    .as("78 characters survive; XY does not")
                    .hasSize(UserDeleteRequest.ERRMSG_LENGTH)
                    .isEqualTo("M".repeat(WS_MESSAGE_LENGTH - 2))
                    .doesNotContain("X")
                    .doesNotContain("Y");

            // The message the program actually moves is shorter than the field, so that one pads
            // rather than truncates - the other half of the same rule.
            String padded = codec().movePicX(BLANK_USER_ID_MESSAGE,
                    UserDeleteRequest.ERRMSG_LENGTH);
            assertThat(padded)
                    .as("'User ID can NOT be empty...' at COUSR03C.cbl:179 pads on the right")
                    .hasSize(UserDeleteRequest.ERRMSG_LENGTH)
                    .startsWith(BLANK_USER_ID_MESSAGE)
                    .endsWith(" ");
            assertThat(padded.trim()).isEqualTo(BLANK_USER_ID_MESSAGE);

            // And the payload carries the truncated image as-is: the DTO applies no move of its own.
            List<String> values = blankMapValues();
            values.set(MAP_MEMBERS.indexOf("errMsg"), moved);
            assertThat(requestOf(values, NavigationContext.empty(), "").errMsg())
                    .isEqualTo(moved)
                    .hasSize(UserDeleteRequest.ERRMSG_LENGTH);
        }

        @Test
        @DisplayName("the titles are the 40-character CCDA literals, padding included")
        void theTitlesAreTheScreenTitleLiterals() {
            // COUSR03C.cbl:247-248 move CCDA-TITLE01 and CCDA-TITLE02 into TITLE01O and TITLE02O.
            // Both literals are declared 40 characters wide in app/cpy/COTTL01Y.cpy, which is exactly
            // the width of the items receiving them, so the move is byte-for-byte with no padding and
            // no truncation.
            assertThat(UserDeleteRequest.TITLE01_LENGTH).isEqualTo(ScreenTitles.TITLE_LENGTH);
            assertThat(UserDeleteRequest.TITLE02_LENGTH).isEqualTo(ScreenTitles.TITLE_LENGTH);
            assertThat(ScreenTitles.CCDA_TITLE01).hasSize(UserDeleteRequest.TITLE01_LENGTH);
            assertThat(ScreenTitles.CCDA_TITLE02).hasSize(UserDeleteRequest.TITLE02_LENGTH);

            UserDeleteRequest request = populatedRequest();
            assertThat(request.title01()).isEqualTo(ScreenTitles.CCDA_TITLE01)
                    .hasSize(ScreenTitles.TITLE_LENGTH);
            assertThat(request.title02()).isEqualTo(ScreenTitles.CCDA_TITLE02)
                    .hasSize(ScreenTitles.TITLE_LENGTH);
            assertThat(codec().movePicX(ScreenTitles.CCDA_TITLE01,
                    UserDeleteRequest.TITLE01_LENGTH))
                    .as("40 into 40 changes nothing, trailing spaces included")
                    .isEqualTo(ScreenTitles.CCDA_TITLE01);
        }

        @Test
        @DisplayName("CCDA-THANK-YOU and CCDA-MSG-THANK-YOU are different things, and neither is here")
        void theTwoThankYouLiteralsAreNotInterchangeable() {
            // A trap worth naming: two similarly-named literals live in different copybooks, carry
            // different text and are declared at different widths. CCDA-THANK-YOU is a 40-character
            // title in COTTL01Y; CCDA-MSG-THANK-YOU is a 50-character message in CSMSG01Y. Confusing
            // them would put a title into a message field or a message into a title field, and either
            // way the receiving item's width would silently pad or truncate the wrong text.
            assertThat(ScreenTitles.CCDA_THANK_YOU).hasSize(ScreenTitles.TITLE_LENGTH);
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU).hasSize(SystemMessages.MESSAGE_LENGTH);
            assertThat(SystemMessages.MESSAGE_LENGTH)
                    .as("50 against 40 - different widths, different owners")
                    .isNotEqualTo(ScreenTitles.TITLE_LENGTH);
            assertThat(ScreenTitles.CCDA_THANK_YOU.trim())
                    .isNotEqualTo(SystemMessages.CCDA_MSG_THANK_YOU.trim());

            // Neither belongs on this screen. COUSR03C's only two message texts are the blank-id text
            // at :179 and CCDA-MSG-INVALID-KEY at :128, which the WHEN OTHER arm of the AID evaluation
            // uses. That one is 50 characters and would itself truncate into the 78-wide item only if
            // it were longer, which it is not.
            assertThat(SystemMessages.CCDA_MSG_INVALID_KEY)
                    .hasSize(SystemMessages.MESSAGE_LENGTH);
            assertThat(SystemMessages.MESSAGE_LENGTH)
                    .as("50 into 78 pads; it never truncates")
                    .isLessThan(UserDeleteRequest.ERRMSG_LENGTH);
            assertThat(codec().movePicX(SystemMessages.CCDA_MSG_INVALID_KEY,
                    UserDeleteRequest.ERRMSG_LENGTH))
                    .hasSize(UserDeleteRequest.ERRMSG_LENGTH)
                    .startsWith(SystemMessages.CCDA_MSG_INVALID_KEY);
        }

        @Test
        @DisplayName("the AID token is 5, the width of CCARD-AID PIC X(5)")
        void theAidTokenWidth() {
            assertThat(UserDeleteRequest.AID_LENGTH)
                    .isEqualTo(5)
                    .isEqualTo(PfKeyResolver.AID_TOKEN_LENGTH);
            for (PfKeyResolver.AidKey key : PfKeyResolver.AidKey.values()) {
                assertThat(key.token())
                        .as("%s renders at the declared width, padded where the mnemonic is shorter",
                                key.name())
                        .hasSize(UserDeleteRequest.AID_LENGTH);
            }
        }

        @Test
        @DisplayName("the symbolic map closes at 324 bytes, and the two views are the same size")
        void symbolicMapGeometry() {
            assertThat(UserDeleteRequest.TIOAPFX_PREFIX_LENGTH)
                    .as("02 FILLER PIC X(12) at COUSR03.CPY:18, present because TIOAPFX=YES")
                    .isEqualTo(TIOAPFX_PREFIX_LENGTH);
            assertThat(UserDeleteRequest.FIELD_OVERHEAD_LENGTH)
                    .as("xxxL 2 + xxxF 1 + FILLER X(4) = 7 per field on the input view, and "
                            + "FILLER X(3) + xxxC + xxxP + xxxH + xxxV = 7 on the output view")
                    .isEqualTo(FIELD_PREFIX_LENGTH)
                    .isEqualTo(7);
            assertThat(UserDeleteRequest.SYMBOLIC_MAP_LENGTH)
                    .as("12 + 11 x 7 + 235 = 324, the same figure from either view, which is what "
                            + "lets 01 COUSR3AO REDEFINES COUSR3AI overlay field for field")
                    .isEqualTo(SYMBOLIC_MAP_LENGTH)
                    .isEqualTo(SYMBOLIC_MAP_LAYOUT.recordLength());
        }
    }

    // =================================================================================================
    // 4. THE FOUR DATA FIELDS AND THE RECORD BEHIND THEM.
    //
    // Only four of the eleven map fields carry user data: the key the operator types, and the three
    // values read back from SEC-USER-DATA to confirm the right record was found. Their widths are the
    // record's widths, and the record still holds a password the screen does not project.
    // =================================================================================================

    @Nested
    @DisplayName("Projection onto SEC-USER-DATA - four fields of six, and the two left behind")
    class RecordProjection {

        @Test
        @DisplayName("the four projected widths are CSUSR01Y's own")
        void projectedWidthsAreTheRecordsWidths() {
            assertThat(UserDeleteRequest.USRIDIN_LENGTH)
                    .as("SEC-USR-ID PIC X(08), CSUSR01Y.cpy:18, moved to at COUSR03C.cbl:160")
                    .isEqualTo(SecUserRecord.SEC_USR_ID_LENGTH);
            assertThat(UserDeleteRequest.FNAME_LENGTH)
                    .as("SEC-USR-FNAME PIC X(20), CSUSR01Y.cpy:19, moved from at COUSR03C.cbl:165")
                    .isEqualTo(SecUserRecord.SEC_USR_FNAME_LENGTH);
            assertThat(UserDeleteRequest.LNAME_LENGTH)
                    .as("SEC-USR-LNAME PIC X(20), CSUSR01Y.cpy:20, moved from at COUSR03C.cbl:166")
                    .isEqualTo(SecUserRecord.SEC_USR_LNAME_LENGTH);
            assertThat(UserDeleteRequest.USRTYPE_LENGTH)
                    .as("SEC-USR-TYPE PIC X(01), CSUSR01Y.cpy:22, moved from at COUSR03C.cbl:167")
                    .isEqualTo(SecUserRecord.SEC_USR_TYPE_LENGTH);
        }

        @Test
        @DisplayName("the record is 80 bytes at offsets 0, 8, 28, 48, 56 and 57 - unchanged by this screen")
        void theRecordGeometryIsUnchanged() {
            assertThat(SecUserRecord.RECORD_LENGTH).isEqualTo(80);
            assertThat(List.of(SecUserRecord.SEC_USR_ID_OFFSET,
                    SecUserRecord.SEC_USR_FNAME_OFFSET,
                    SecUserRecord.SEC_USR_LNAME_OFFSET,
                    SecUserRecord.SEC_USR_PWD_OFFSET,
                    SecUserRecord.SEC_USR_TYPE_OFFSET,
                    SecUserRecord.SEC_USR_FILLER_OFFSET))
                    .as("CSUSR01Y.cpy:18-23 in declaration order")
                    .containsExactly(0, 8, 28, 48, 56, 57);
            assertThat(SecUserRecord.SEC_USR_ID_LENGTH + SecUserRecord.SEC_USR_FNAME_LENGTH
                    + SecUserRecord.SEC_USR_LNAME_LENGTH + SecUserRecord.SEC_USR_PWD_LENGTH
                    + SecUserRecord.SEC_USR_TYPE_LENGTH + SecUserRecord.SEC_USR_FILLER_LENGTH)
                    .as("8 + 20 + 20 + 8 + 1 + 23 = 80, with no byte unaccounted for")
                    .isEqualTo(SecUserRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("the record still holds a password at offset 48 - it is the SCREEN that omits it")
        void theRecordKeepsThePasswordTheScreenDoesNot() {
            // This is the distinction that matters, and getting it backwards in either direction would
            // be a defect. SEC-USR-PWD PIC X(08) is still there, at offset 48, in every one of the 80
            // bytes this repository's USRSEC records occupy: the delete path reads the whole record at
            // COUSR03C.cbl:161 and deletes the whole record. What is absent is any *screen field*
            // projecting it. Removing it from the record would change the record layout and break
            // every other user program; adding it to the screen would invent a field.
            assertThat(SecUserRecord.SEC_USR_PWD_OFFSET).isEqualTo(48);
            assertThat(SecUserRecord.SEC_USR_PWD_LENGTH).isEqualTo(8);
            assertThat(SecUserRecord.LAYOUT.hasSpan(SecUserRecord.FIELD_SEC_USR_PWD)).isTrue();

            // Round-trip a whole record through the codec at the explicitly named code page, and read
            // the password back out of it: it survives the record entirely, untouched by this screen.
            SecUserRecord record = SecUserRecord.of("USER0001", "Given", "Family", "NOTREAL1",
                    NavigationContext.USER_TYPE_USER, MAP_CHARSET);
            byte[] image = SecUserRecord.encode(record, MAP_CHARSET);
            assertThat(image).hasSize(SecUserRecord.RECORD_LENGTH);
            SecUserRecord restored = SecUserRecord.decode(image, MAP_CHARSET);
            assertThat(restored.image(SecUserRecord.FIELD_SEC_USR_PWD))
                    .as("the record's own field, at its own width, unchanged")
                    .hasSize(SecUserRecord.SEC_USR_PWD_LENGTH)
                    .isEqualTo("NOTREAL1");

            // And none of the eleven screen items projects it.
            assertThat(SYMBOLIC_MAP_ITEMS)
                    .doesNotContain("PASSWDI")
                    .hasSize(UserDeleteRequest.MAP_FIELD_COUNT);
        }

        @Test
        @DisplayName("SEC-USR-FILLER is named storage and is likewise not projected")
        void theNamedFillerIsNotProjected() {
            // CSUSR01Y.cpy:23 declares 05 SEC-USR-FILLER PIC X(23) - a named item rather than an
            // anonymous FILLER, but reserved storage all the same. It occupies the record's last 23
            // bytes and must be emitted, or every offset and the total width would be wrong; it is
            // simply not something the delete screen shows.
            assertThat(SecUserRecord.SEC_USR_FILLER_LENGTH).isEqualTo(23);
            assertThat(SecUserRecord.SEC_USR_FILLER_OFFSET + SecUserRecord.SEC_USR_FILLER_LENGTH)
                    .as("it closes the record at byte 80")
                    .isEqualTo(SecUserRecord.RECORD_LENGTH);
            for (String item : PUBLISHED_ITEM_NAMES) {
                assertThat(item).doesNotContain("FILLER");
            }
            assertThat(SecUserRecord.blank().image(SecUserRecord.FIELD_SEC_USR_FILLER))
                    .as("a blank record still carries the filler, as spaces at its declared width")
                    .isEqualTo(spaces(SecUserRecord.SEC_USR_FILLER_LENGTH));
        }

        @Test
        @DisplayName("the type field carries A or U, and the payload does not police which")
        void theTypeFieldIsCarriedNotValidated() {
            // COUSR03C never validates USRTYPEI: it is filled from the record at :167 for display and
            // is blanked at :159 before the lookup. The payload therefore carries whatever single
            // character arrived, including a space, and asserts nothing about it. CDEMO-USER-TYPE's
            // own 88-levels - 'A' and 'U' - are a commarea concern, not a screen-field constraint.
            for (String type : List.of(NavigationContext.USER_TYPE_ADMIN,
                    NavigationContext.USER_TYPE_USER, " ", "X")) {
                List<String> values = blankMapValues();
                values.set(MAP_MEMBERS.indexOf("usrType"), type);
                UserDeleteRequest request = requestOf(values, NavigationContext.empty(), "");
                assertThat(request.usrType()).isEqualTo(type);
                assertThat(violationsOf(request))
                        .as("a single character always fits PIC X(1), whatever it is")
                        .isEmpty();
            }
        }
    }


    // =================================================================================================
    // 5. VALIDATION IS BOUNDED BY WHAT THE PROGRAM DOES - AND THIS PROGRAM CHECKS ONE FIELD.
    //
    // COUSR01C and COUSR02C each open an EVALUATE TRUE with FIVE blank-field arms. COUSR03C has
    // exactly ONE, and it has it twice - once in PROCESS-ENTER-KEY (paragraph at :142, arm at :145)
    // and once in DELETE-USER-INFO (paragraph at :174, arm at :177):
    //
    //   WHEN USRIDINI OF COUSR3AI = SPACES OR LOW-VALUES     :177
    //       MOVE 'Y' TO WS-ERR-FLG                           :178
    //       MOVE 'User ID can NOT be empty...' TO WS-MESSAGE  :179-180
    //       MOVE -1 TO USRIDINL OF COUSR3AI                   :181
    //       PERFORM SEND-USRDEL-SCREEN                        :182
    //   WHEN OTHER                                            :183
    //       MOVE -1 TO USRIDINL OF COUSR3AI                   :184
    //       CONTINUE                                          :185
    //
    // A blank id is therefore answered with a screen carrying a message - a 200 with text - never with
    // a framework rejection. No presence constraint may exist on any member, or the specific message
    // would be replaced by a generic 400 and the program's answer would be unobservable.
    //
    // fName, lName and usrType get NO blank check at all, and that is not an oversight: they are
    // display-only here, blanked at :157-159, refilled from the record at :165-167 after the lookup at
    // :161. A delete screen validates its key and nothing else. Adding the four arms COUSR02C has
    // would be a behaviour change (B5).
    // =================================================================================================

    @Nested
    @DisplayName("Validation - @Size maxima only, and a one-arm blank check the framework never sees")
    class ValidationConstraints {

        @Test
        @DisplayName("carries twelve @Size constraints and no other constraint at all")
        void carriesOnlySizeConstraints() {
            int sized = 0;
            for (RecordComponent component : UserDeleteRequest.class.getRecordComponents()) {
                for (Annotation annotation : component.getAccessor().getAnnotations()) {
                    assertThat(annotation.annotationType().getName())
                            .as("%s carries a constraint COUSR03C does not perform",
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
                    .as("the eleven map members plus the AID token; the commarea and the extension "
                            + "carry their own widths internally")
                    .isEqualTo(DFHMDF_NAMED + 1);
        }

        @ParameterizedTest(name = "component {0}")
        @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
        @DisplayName("each map member carries @Size(max = n) with n from its PICTURE clause")
        void sizeMirrorsTheDeclaredWidth(int index) {
            RecordComponent component = UserDeleteRequest.class.getRecordComponents()[index];
            Size size = component.getAccessor().getAnnotation(Size.class);
            assertThat(size)
                    .as("%s must bound what it accepts to its field width", MAP_MEMBERS.get(index))
                    .isNotNull();
            assertThat(size.max())
                    .as("%s is PIC X(%d)", SYMBOLIC_MAP_ITEMS.get(index),
                            DECLARED_WIDTHS.get(index))
                    .isEqualTo(DECLARED_WIDTHS.get(index));
            assertThat(size.min())
                    .as("no lower bound: a blank field is valid input on this screen")
                    .isZero();
        }

        @Test
        @DisplayName("the AID is bounded at 5 and the two typed carriers are unconstrained")
        void stateCarrierConstraints() {
            RecordComponent[] components = UserDeleteRequest.class.getRecordComponents();
            assertThat(components[DFHMDF_NAMED].getAccessor().getAnnotation(Size.class))
                    .as("NavigationContext validates its own components; a @Size on the whole group "
                            + "would be meaningless")
                    .isNull();
            assertThat(components[DFHMDF_NAMED + 1].getAccessor().getAnnotation(Size.class).max())
                    .as("CCARD-AID PIC X(5)")
                    .isEqualTo(UserDeleteRequest.AID_LENGTH);
            assertThat(components[DFHMDF_NAMED + 2].getAccessor().getAnnotation(Size.class))
                    .as("Cu03Info renders each item at its declared width in its own constructor")
                    .isNull();
        }

        @Test
        @DisplayName("a fully blank instance produces zero violations - the whole point of section 5")
        void blanknessIsNotAConstraintViolation() {
            assertThat(violationsOf(requestOf(blankMapValues(), NavigationContext.empty(), "")))
                    .as("eleven empty strings must bind, because COUSR03C:177 answers a blank id "
                            + "with a message rather than a rejection")
                    .isEmpty();
            assertThat(violationsOf(requestOf(nullMapValues(), null, null)))
                    .as("and so must eleven nulls plus absent state - the MOVE LOW-VALUES shape")
                    .isEmpty();
            assertThat(violationsOf(requestOf(spaceFilledMapValues(), NavigationContext.empty(),
                    spaces(UserDeleteRequest.AID_LENGTH))))
                    .as("and so must eleven space-filled fields at their declared widths")
                    .isEmpty();
            assertThat(violationsOf(populatedRequest()))
                    .as("a fully painted screen is equally valid")
                    .isEmpty();
        }

        @ParameterizedTest(name = "{0} at width {1} + 1")
        @CsvSource({"trnName,4", "title01,40", "curDate,8", "pgmName,8", "title02,40", "curTime,8",
            "usrIdIn,8", "fName,20", "lName,20", "usrType,1", "errMsg,78"})
        @DisplayName("a value one character wider than its field is reported against that field alone")
        void overWideValuesAreReportedAgainstOneField(String member, int width) {
            int index = MAP_MEMBERS.indexOf(member);
            List<String> values = blankMapValues();
            values.set(index, "W".repeat(width + 1));

            Set<ConstraintViolation<UserDeleteRequest>> violations =
                    violationsOf(requestOf(values, NavigationContext.empty(), ""));
            assertThat(violations)
                    .as("%s accepted %d characters into PIC X(%d)", member, width + 1, width)
                    .hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath().toString())
                    .as("the violation must name the member that overflowed and no other")
                    .isEqualTo(member);
        }

        @Test
        @DisplayName("an over-wide AID token is reported too, and nothing else is")
        void anOverWideAidIsReported() {
            Set<ConstraintViolation<UserDeleteRequest>> violations = violationsOf(
                    requestOf(blankMapValues(), NavigationContext.empty(),
                            "T".repeat(UserDeleteRequest.AID_LENGTH + 1)));
            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("aid");
        }

        @Test
        @DisplayName("a value exactly at its declared width is accepted, so the bound is inclusive")
        void exactWidthValuesAreAccepted() {
            assertThat(violationsOf(requestOf(spaceFilledMapValues(), NavigationContext.empty(),
                    PfKeyResolver.AidKey.PFK05.token())))
                    .as("@Size(max = n) admits exactly n, which is what a painted screen sends")
                    .isEmpty();
        }

        @Test
        @DisplayName("no blank check exists on fName, lName or usrType - they are display-only here")
        void theDisplayOnlyFieldsCarryNoPresenceCheck() {
            // COUSR02C validates first name, last name, user type and password as well as the id,
            // because an update writes them. COUSR03C validates only the id, because a delete writes
            // nothing: :157-159 blank the three display fields, :161 reads the record, :165-167 refill
            // them from what was found. A blank first name here means "no record has been read yet",
            // not "the operator forgot something".
            List<String> values = blankMapValues();
            values.set(MAP_MEMBERS.indexOf("usrIdIn"), "USER0001");
            UserDeleteRequest request = requestOf(values, NavigationContext.empty(), "");

            assertThat(violationsOf(request))
                    .as("an id with three empty display fields is exactly the state COUSR03C:157-160 "
                            + "constructs before it reads the file")
                    .isEmpty();
            assertThat(request.fName()).isEmpty();
            assertThat(request.lName()).isEmpty();
            assertThat(request.usrType()).isEmpty();

            for (String member : List.of("fName", "lName", "usrType")) {
                RecordComponent component = UserDeleteRequest.class
                        .getRecordComponents()[MAP_MEMBERS.indexOf(member)];
                assertThat(component.getAccessor().getAnnotation(Size.class))
                        .as("%s must carry a width bound", member)
                        .isNotNull();
                // No presence check of any kind. @JsonProperty is permitted alongside it, and is not a
                // constraint: it pins the wire name to the xxxI item (AAP 0.6.3) and validates nothing.
                assertThat(component.getAccessor().getAnnotations())
                        .as("%s must carry a width bound and a wire name, and no presence check", member)
                        .allSatisfy(annotation -> assertThat(annotation.annotationType())
                                .isIn(Size.class, JsonProperty.class));
            }
        }

        @Test
        @DisplayName("the blank-id message is the program's own text, unchanged and unabbreviated")
        void theBlankIdMessageIsVerbatim() {
            // Both arms - :147-148 in PROCESS-ENTER-KEY and :179-180 in DELETE-USER-INFO - move the
            // same literal. The ellipsis is three full stops in the source and is part of the text;
            // trimming it would be a visible difference on the screen.
            assertThat(BLANK_USER_ID_MESSAGE)
                    .isEqualTo("User ID can NOT be empty...")
                    .endsWith("...")
                    .hasSizeLessThanOrEqualTo(WS_MESSAGE_LENGTH)
                    .hasSizeLessThanOrEqualTo(UserDeleteRequest.ERRMSG_LENGTH);

            List<String> values = blankMapValues();
            values.set(MAP_MEMBERS.indexOf("errMsg"),
                    codec().movePicX(BLANK_USER_ID_MESSAGE, UserDeleteRequest.ERRMSG_LENGTH));
            UserDeleteRequest request = requestOf(values, NavigationContext.empty(), "");
            assertThat(violationsOf(request))
                    .as("the message at its field width is valid input, since the response echoes it")
                    .isEmpty();
            assertThat(request.errMsg()).startsWith(BLANK_USER_ID_MESSAGE);
        }
    }

    // =================================================================================================
    // 6. SPACES AND LOW-VALUES ARE TWO VALUES, NOT ONE.
    //
    // The COBOL predicate at :145 and :177 reads = SPACES OR LOW-VALUES. Two distinct figurative
    // constants, tested together because the program answers them alike - but they are not the same
    // bytes, and the payload must be able to carry each as itself. That is exactly why
    // config/WebConfig disables ACCEPT_EMPTY_STRING_AS_NULL_OBJECT and registers no trimming
    // converter: under a default mapper an empty field would arrive as null and the distinction would
    // be gone before any code could see it.
    // =================================================================================================

    @Nested
    @DisplayName("SPACES and LOW-VALUES - both carried, neither trimmed, neither nulled")
    class SpacesAndLowValues {

        @Test
        @DisplayName("a space-filled id and a LOW-VALUES id are different values, both valid")
        void theTwoBlankFormsAreDistinct() {
            String spacesId = spaces(UserDeleteRequest.USRIDIN_LENGTH);
            String lowValuesId = lowValues(UserDeleteRequest.USRIDIN_LENGTH);
            assertThat(spacesId).hasSize(UserDeleteRequest.USRIDIN_LENGTH);
            assertThat(lowValuesId).hasSize(UserDeleteRequest.USRIDIN_LENGTH)
                    .isNotEqualTo(spacesId);

            List<String> spacesValues = blankMapValues();
            spacesValues.set(MAP_MEMBERS.indexOf("usrIdIn"), spacesId);
            List<String> lowValues = blankMapValues();
            lowValues.set(MAP_MEMBERS.indexOf("usrIdIn"), lowValuesId);

            UserDeleteRequest spaced = requestOf(spacesValues, NavigationContext.empty(), "");
            UserDeleteRequest nulled = requestOf(lowValues, NavigationContext.empty(), "");

            assertThat(spaced.usrIdIn()).isEqualTo(spacesId);
            assertThat(nulled.usrIdIn()).isEqualTo(lowValuesId);
            assertThat(spaced)
                    .as("the two blank forms are different payloads, as the copybook makes them")
                    .isNotEqualTo(nulled);
            assertThat(violationsOf(spaced)).isEmpty();
            assertThat(violationsOf(nulled)).isEmpty();
        }

        @Test
        @DisplayName("neither form is trimmed, coerced to null or shortened by the round trip")
        void neitherFormIsCoercedByTheWire() {
            String spacesId = spaces(UserDeleteRequest.USRIDIN_LENGTH);
            String lowValuesId = lowValues(UserDeleteRequest.USRIDIN_LENGTH);

            List<String> values = blankMapValues();
            values.set(MAP_MEMBERS.indexOf("usrIdIn"), spacesId);
            values.set(MAP_MEMBERS.indexOf("fName"), lowValuesId + spaces(12));
            UserDeleteRequest request = requestOf(values, NavigationContext.empty(),
                    spaces(UserDeleteRequest.AID_LENGTH));

            UserDeleteRequest restored = deserialise(serialise(request));
            assertThat(restored.usrIdIn())
                    .as("eight spaces stay eight spaces; a trimming mapper would make them empty")
                    .isEqualTo(spacesId)
                    .hasSize(UserDeleteRequest.USRIDIN_LENGTH);
            assertThat(restored.fName())
                    .as("eight NULs followed by twelve spaces survive byte for byte")
                    .isEqualTo(lowValuesId + spaces(12))
                    .hasSize(UserDeleteRequest.FNAME_LENGTH);
            assertThat(restored.aid())
                    .as("and an all-spaces AID is not nulled either")
                    .isEqualTo(spaces(UserDeleteRequest.AID_LENGTH));
            assertThat(restored).isEqualTo(request);
        }

        @Test
        @DisplayName("an empty string is not turned into null, and null is not turned into empty")
        void emptyAndNullStayApart() {
            UserDeleteRequest empties = requestOf(blankMapValues(), NavigationContext.empty(), "");
            UserDeleteRequest nulls = requestOf(nullMapValues(), NavigationContext.empty(), null);

            assertThat(mapValuesOf(empties)).allMatch(""::equals);
            assertThat(mapValuesOf(nulls)).containsOnlyNulls();
            assertThat(empties).isNotEqualTo(nulls);

            UserDeleteRequest restoredEmpties = deserialise(serialise(empties));
            UserDeleteRequest restoredNulls = deserialise(serialise(nulls));
            assertThat(mapValuesOf(restoredEmpties))
                    .as("ACCEPT_EMPTY_STRING_AS_NULL_OBJECT is disabled, so \"\" stays \"\"")
                    .allMatch(""::equals);
            assertThat(mapValuesOf(restoredNulls))
                    .as("and a null member is emitted and read back as null, not as \"\"")
                    .containsOnlyNulls();
            assertThat(restoredEmpties).isEqualTo(empties);
            assertThat(restoredNulls).isEqualTo(nulls);
        }
    }


    // =================================================================================================
    // 7. METADATA IS NOT PAYLOAD, AND THE WIRE FORMAT PROVES IT.
    //
    // Each field in 01 COUSR3AI contributes four items, and only one of them is data:
    //   xxxL  COMP PIC S9(4)  - the length CICS reports for an input field, and the cursor carrier.
    //                           COUSR03C.cbl:181 and :184 both MOVE -1 TO USRIDINL, which is the
    //                           3270 convention for "put the cursor here"; :98 and :151 do the same.
    //                           A negative length is a positioning instruction, not a measurement,
    //                           which is exactly why it cannot be a payload field.
    //   xxxF  PICTURE X       - the flag byte.
    //   xxxA  REDEFINES xxxF  - the same byte viewed as an attribute.
    //   xxxI  PIC X(n)        - the data. This one, and only this one, is payload.
    // The 12-byte TIOAPFX prefix and the per-field FILLER X(4) are reserved storage and are equally
    // absent from the wire.
    // =================================================================================================

    @Nested
    @DisplayName("Metadata - xxxL, xxxF, xxxA and every FILLER stay off the wire")
    class MetadataIsNotPayload {

        @Test
        @DisplayName("the payload is exactly the eleven map members plus the three state members")
        void keySet() {
            assertThat(jsonMembersOf(populatedRequest()))
                    .containsExactlyInAnyOrderElementsOf(EXPECTED_JSON_MEMBERS)
                    .hasSize(COMPONENT_COUNT);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME",
            "USRIDIN", "FNAME", "LNAME", "USRTYPE", "ERRMSG"})
        @DisplayName("no xxxL, xxxF, xxxA or xxxO name reaches the serialised form")
        void noSymbolicMapMetadataReachesTheWire(String screenField) {
            String json = serialise(populatedRequest()).toUpperCase(Locale.ROOT);
            for (String metadata : metadataNamesOf(screenField)) {
                assertThat(json)
                        .as("%s is metadata or the output view, never payload", metadata)
                        .doesNotContain("\"" + metadata + "\"");
            }
            Set<String> members = jsonMembersOf(populatedRequest());
            for (String member : members) {
                assertThat(member.toUpperCase(Locale.ROOT))
                        .as("no member may be named for a metadata item")
                        .isNotIn(metadataNamesOf(screenField));
            }
        }

        @Test
        @DisplayName("neither FILLER span is exposed, though both are declared in the geometry")
        void noFillerIsExposed() {
            // Both fillers must EXIST - omitting either would shift every later offset and change the
            // group's total width - and neither may be exposed. The layout carries them; the payload
            // does not.
            assertThat(SYMBOLIC_MAP_LAYOUT.storageSpans())
                    .as("the TIOAPFX prefix, 11 xxxL halfwords, 11 xxxF bytes, 11 FILLER X(4) spans "
                            + "and 11 xxxI items")
                    .hasSize(1 + DFHMDF_NAMED * 4);
            long fillerSpans = SYMBOLIC_MAP_LAYOUT.storageSpans().stream()
                    .filter(span -> "FILLER".equals(span.name()))
                    .count();
            assertThat(fillerSpans)
                    .as("one 12-byte prefix, plus a 2-byte halfword and a 4-byte gap per field")
                    .isEqualTo(1 + DFHMDF_NAMED * 2L);

            String json = serialise(populatedRequest());
            assertThat(json).doesNotContain("FILLER").doesNotContain("filler")
                    .doesNotContain("TIOAPFX").doesNotContain("tioapfx");
            for (String member : jsonMembersOf(populatedRequest())) {
                assertThat(member.toLowerCase(Locale.ROOT)).doesNotContain("filler");
            }
        }

        @Test
        @DisplayName("the read-through context predicates are not promoted to JSON properties")
        void derivedPredicatesStayOffTheWire() {
            // pgmEnter() and pgmReenter() are public, no-argument and boolean-returning. Named
            // isEnter()/isReenter() Jackson would promote them to "enter" and "reenter" keys that the
            // canonical constructor cannot accept back, and a round trip would fail outright. The
            // COBOL-derived naming avoids that without needing a Jackson annotation on this type.
            assertThat(jsonMembersOf(populatedRequest()))
                    .doesNotContain("pgmEnter").doesNotContain("pgmReenter")
                    .doesNotContain("enter").doesNotContain("reenter");
            assertThat(serialise(populatedRequest()))
                    .doesNotContain("\"pgmEnter\"").doesNotContain("\"pgmReenter\"");
        }

        @Test
        @DisplayName("a painted screen survives serialise then deserialise byte for byte")
        void roundTripPreservesEveryByte() {
            List<String> values = spaceFilledMapValues();
            values.set(MAP_MEMBERS.indexOf("usrIdIn"), "USER0001");
            values.set(MAP_MEMBERS.indexOf("fName"),
                    codec().movePicX("Given", UserDeleteRequest.FNAME_LENGTH));
            values.set(MAP_MEMBERS.indexOf("lName"),
                    codec().movePicX("Family", UserDeleteRequest.LNAME_LENGTH));
            values.set(MAP_MEMBERS.indexOf("usrType"), NavigationContext.USER_TYPE_ADMIN);
            values.set(MAP_MEMBERS.indexOf("errMsg"), spaces(UserDeleteRequest.ERRMSG_LENGTH));

            UserDeleteRequest request = requestOf(values,
                    NavigationContext.empty().withPgmReenter(),
                    PfKeyResolver.AidKey.PFK05.token(),
                    new UserDeleteRequest.Cu03Info("USER0001", "USER0050", 3,
                            UserDeleteRequest.Cu03Info.NEXT_PAGE_YES, "D", "USER0007"));
            UserDeleteRequest restored = deserialise(serialise(request));

            assertThat(mapValuesOf(restored))
                    .as("every one of the eleven members, padding included")
                    .containsExactlyElementsOf(mapValuesOf(request));
            assertThat(restored.fName())
                    .as("twenty characters in, twenty characters out")
                    .hasSize(UserDeleteRequest.FNAME_LENGTH)
                    .isEqualTo("Given" + spaces(UserDeleteRequest.FNAME_LENGTH - 5));
            assertThat(restored.lName())
                    .hasSize(UserDeleteRequest.LNAME_LENGTH)
                    .isEqualTo("Family" + spaces(UserDeleteRequest.LNAME_LENGTH - 6));
            assertThat(restored.errMsg())
                    .as("a 78-space message is 78 spaces, not an empty string")
                    .isEqualTo(spaces(UserDeleteRequest.ERRMSG_LENGTH));
            assertThat(restored.navigationContext()).isEqualTo(request.navigationContext());
            assertThat(restored.cu03Info()).isEqualTo(request.cu03Info());
            assertThat(restored).isEqualTo(request);
        }

        @Test
        @DisplayName("the communication area round-trips as a nested object, not a flattened string")
        void nestedContextRoundTrip() {
            UserDeleteRequest request = requestOf(blankMapValues(),
                    NavigationContext.empty()
                            .withFromTranid(UserDeleteRequest.TRANSACTION_ID)
                            .withFromProgram(UserDeleteRequest.PROGRAM_NAME)
                            .withUserTypeAdmin()
                            .withPgmReenter(),
                    PfKeyResolver.AidKey.ENTER.token());
            String json = serialise(request);
            assertThat(json)
                    .as("the group is an object, so its own members are addressable on the wire")
                    .contains("\"navigationContext\":{")
                    .contains("\"cu03Info\":{");

            UserDeleteRequest restored = deserialise(json);
            assertThat(restored.navigationContext().fromTranid())
                    .isEqualTo(UserDeleteRequest.TRANSACTION_ID);
            assertThat(restored.navigationContext().fromProgram())
                    .isEqualTo(UserDeleteRequest.PROGRAM_NAME);
            assertThat(restored.navigationContext().isAdmin()).isTrue();
            assertThat(restored.pgmReenter()).isTrue();
            assertThat(restored).isEqualTo(request);
        }

        @Test
        @DisplayName("the CSSETATY highlight targets items this inbound payload does not carry")
        void theHighlightTargetsItemsThisPayloadDoesNotCarry() {
            // CSSETATY moves DFHRED into the xxxC colour item and, nested inside that, '*' into the
            // xxxO output item - and only when the program is in REENTER state. Both targets belong to
            // the OUTPUT view, 01 COUSR3AO, so neither is a member of this inbound payload: the request
            // says what was keyed, the response says how to paint it. That division is why the
            // enter-versus-re-enter flag has to travel in the payload while the highlight itself does
            // not.
            //
            // The scenario driven is this screen's own: a BLANK user id on re-entry, which is the
            // single condition COUSR03C:145 and :177 test. CSSETATY nests the '*' move inside the
            // DFHRED move and reaches it only for the blank state, so blank is also the only input
            // that assigns both items - which is why it, and not the generic not-ok state, is the
            // faithful case here.
            FieldAttributeSetter.FieldHighlight highlighted = FieldAttributeSetter.resolveFromFlags(
                    false, true, true, "USRIDIN", UserDeleteRequest.MAP_NAME + "O");
            assertThat(highlighted.colourItemAssigned())
                    .as("a blank field, on re-entry, is coloured")
                    .isTrue();
            assertThat(highlighted.outputItemAssigned())
                    .as("and the blank state is the one that also receives the asterisk")
                    .isTrue();
            assertThat(highlighted.colourItemValue()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(highlighted.outputItemValue()).isEqualTo(FieldAttributeSetter.ASTERISK);

            Set<String> members = jsonMembersOf(populatedRequest());
            assertThat(members)
                    .as("%s and %s are output-view items and are not inbound payload members",
                            highlighted.colourItemName(), highlighted.outputItemName())
                    .doesNotContain(highlighted.colourItemName())
                    .doesNotContain(highlighted.outputItemName());
            assertThat(highlighted.colourItemName())
                    .isEqualTo("USRIDIN" + FieldAttributeSetter.COLOUR_ITEM_SUFFIX);
            assertThat(highlighted.outputItemName())
                    .isEqualTo("USRIDIN" + FieldAttributeSetter.OUTPUT_ITEM_SUFFIX);

            // On first entry the same field in the same state is left alone, which is the other side
            // of the ENTER/REENTER decision this payload carries.
            assertThat(FieldAttributeSetter.resolveFromFlags(false, true, false, "USRIDIN",
                    UserDeleteRequest.MAP_NAME + "O").untouched())
                    .as("COUSR03C paints on first entry at :105 and validates only from :107")
                    .isTrue();
        }

        @Test
        @DisplayName("the extension round-trips under its own six member names")
        void extensionRoundTrip() {
            UserDeleteRequest request = requestOf(blankMapValues(), NavigationContext.empty(), "",
                    new UserDeleteRequest.Cu03Info("USER0001", "USER0050", 12,
                            UserDeleteRequest.Cu03Info.NEXT_PAGE_NO, "S", "USER0031"));
            String json = serialise(request);
            for (String member : EXTENSION_MEMBERS) {
                assertThat(json)
                        .as("%s is a member of the extension group on the wire", member)
                        .contains("\"" + member + "\"");
            }
            assertThat(deserialise(json).cu03Info()).isEqualTo(request.cu03Info());
        }
    }

    // =================================================================================================
    // 8. CDEMO-CU03-INFO - THE 34-BYTE EXTENSION THAT MAKES THE PASSED AREA 194 BYTES.
    //
    // app/cbl/COUSR03C.cbl:49 copies COCOM01Y and lines 50-58 append, at the 05 level, a group of six
    // items belonging to this program alone:
    //
    //   10 CDEMO-CU03-USRID-FIRST     PIC X(08).                                    8
    //   10 CDEMO-CU03-USRID-LAST      PIC X(08).                                    8
    //   10 CDEMO-CU03-PAGE-NUM        PIC 9(08).                                    8
    //   10 CDEMO-CU03-NEXT-PAGE-FLG   PIC X(01) VALUE 'N'.                          1
    //      88 NEXT-PAGE-YES VALUE 'Y'.   88 NEXT-PAGE-NO VALUE 'N'.
    //   10 CDEMO-CU03-USR-SEL-FLG     PIC X(01).                                    1
    //   10 CDEMO-CU03-USR-SELECTED    PIC X(08).                                    8
    //                                                                              --
    //                                                                              34
    //
    // Three programs declare a block of exactly this shape and each prefixes it with its own
    // transaction: CDEMO-CU00-* at COUSR00C:67-75, CDEMO-CU02-* at COUSR02C:50-58 and CDEMO-CU03-* at
    // COUSR03C:50-58. THREE BLOCKS, NOT ONE SHARED TYPE - the names differ, and a name-keyed field
    // comparison would not match across them. COSGN00C and COUSR01C declare none at all:
    // `grep -c 'CDEMO-CU0[0-9]-INFO'` returns 0 for both.
    //
    // A delete screen carrying paging members it never pages with looks like an accident and is not:
    // COUSR00C:200-202 routes 'D' or 'd' to COUSR03C, handing over the selected id in
    // CDEMO-CU00-USR-SELECTED, and the whole block travels with it. Preserved as-is per B5.
    // =================================================================================================

    @Nested
    @DisplayName("CDEMO-CU03-INFO - 34 bytes on this DTO, and 160 + 34 = 194 in the commarea")
    class Cu03InfoExtension {

        @Test
        @DisplayName("the six items are on THIS type, in declaration order, at their declared widths")
        void theSixItemsAreCarriedHere() {
            RecordComponent[] components =
                    UserDeleteRequest.Cu03Info.class.getRecordComponents();
            assertThat(Arrays.stream(components).map(RecordComponent::getName).toList())
                    .as("COUSR03C.cbl:51-58 order, preserved")
                    .containsExactlyElementsOf(EXTENSION_MEMBERS);
            assertThat(List.of(UserDeleteRequest.Cu03Info.USRID_FIRST_LENGTH,
                    UserDeleteRequest.Cu03Info.USRID_LAST_LENGTH,
                    UserDeleteRequest.Cu03Info.PAGE_NUM_DIGITS,
                    UserDeleteRequest.Cu03Info.NEXT_PAGE_FLG_LENGTH,
                    UserDeleteRequest.Cu03Info.USR_SEL_FLG_LENGTH,
                    UserDeleteRequest.Cu03Info.USR_SELECTED_LENGTH))
                    .containsExactlyElementsOf(EXTENSION_WIDTHS);
            assertThat(UserDeleteRequest.Cu03Info.LENGTH)
                    .as("8 + 8 + 8 + 1 + 1 + 8")
                    .isEqualTo(EXTENSION_LENGTH)
                    .isEqualTo(EXTENSION_WIDTHS.stream().mapToInt(Integer::intValue).sum());
            assertThat(UserDeleteRequest.Cu03Info.initial().fieldImages().keySet())
                    .as("keyed by the names COUSR03C spells, hyphens and CU03 prefix intact")
                    .containsExactlyElementsOf(EXTENSION_ITEM_NAMES);
        }

        @Test
        @DisplayName("the commarea stays 160 bytes, proved through the codec at a named code page")
        void theCommareaIsNotWidenedByTheExtension() {
            // COCOM01Y is copied by all seventeen online programs, so its width is not this program's
            // to change: folding these six items into NavigationContext would silently widen the area
            // every other controller passes. The 160 is re-derived from the copybook's five groups and
            // then proved by encoding an actual instance.
            assertThat(NavigationContext.GENERAL_INFO_LENGTH
                    + NavigationContext.CUSTOMER_INFO_LENGTH
                    + NavigationContext.ACCOUNT_INFO_LENGTH
                    + NavigationContext.CARD_INFO_LENGTH
                    + NavigationContext.MORE_INFO_LENGTH)
                    .as("34 + 84 + 12 + 16 + 14, COCOM01Y.cpy:19-44")
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH)
                    .isEqualTo(160);
            assertThat(NavigationContext.MORE_INFO_LENGTH)
                    .as("CDEMO-LAST-MAP and CDEMO-LAST-MAPSET are X(7) each, not X(8) - 14, not 16")
                    .isEqualTo(NavigationContext.LAST_MAP_LENGTH
                            + NavigationContext.LAST_MAPSET_LENGTH)
                    .isEqualTo(14);

            byte[] image = NavigationContext.empty().toFixedWidth(codec());
            assertThat(image)
                    .as("the encoded area is exactly the copybook's width, with the code page named")
                    .hasSize(NavigationContext.COMMAREA_LENGTH);
            assertThat(NavigationContext.fromFixedWidth(codec(), image))
                    .isEqualTo(NavigationContext.empty());

            // And no NavigationContext component is named for this program's extension.
            for (RecordComponent component : NavigationContext.class.getRecordComponents()) {
                assertThat(component.getName().toLowerCase(Locale.ROOT))
                        .as("%s must not be a CU03 item", component.getName())
                        .doesNotContain("cu03").doesNotContain("nextpage")
                        .doesNotContain("selected");
            }
        }

        @Test
        @DisplayName("160 + 34 = 194, the area COUSR03C:94 restores and :136 hands back")
        void theCu03CommareaIsOneHundredAndNinetyFour() {
            assertThat(CU03_COMMAREA_LENGTH)
                    .as("CARDDEMO-COMMAREA plus 05 CDEMO-CU03-INFO")
                    .isEqualTo(194)
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH
                            + UserDeleteRequest.Cu03Info.LENGTH);

            // Proved rather than asserted: encode the commarea through the codec, append an image of
            // the extension built from its own six item images, and measure the result.
            byte[] commarea = NavigationContext.empty().toFixedWidth(codec());
            StringBuilder extension = new StringBuilder();
            UserDeleteRequest.Cu03Info.initial().fieldImages().values().forEach(extension::append);
            byte[] extensionImage = codec().encodeImage(extension.toString(),
                    "CDEMO-CU03-INFO");
            assertThat(extensionImage).hasSize(UserDeleteRequest.Cu03Info.LENGTH);
            assertThat(commarea.length + extensionImage.length).isEqualTo(CU03_COMMAREA_LENGTH);
        }

        @Test
        @DisplayName("the extension is a distinct block from CU00's and CU02's, not a shared type")
        void theBlockIsPrefixedForThisProgramAlone() {
            for (String item : EXTENSION_ITEM_NAMES) {
                assertThat(item)
                        .as("%s must carry this program's own prefix", item)
                        .startsWith("CDEMO-CU03-")
                        .doesNotContain("CU00").doesNotContain("CU02");
            }
            assertThat(UserDeleteRequest.Cu03Info.class.getSimpleName())
                    .as("named for the transaction that owns it")
                    .isEqualTo("Cu03Info");
            assertThat(UserDeleteRequest.Cu03Info.class.getEnclosingClass())
                    .as("declared where its owning payload is, as COUSR03C declares it inline")
                    .isEqualTo(UserDeleteRequest.class);
        }

        @Test
        @DisplayName("pageNum is an int - PIC 9(08) is scale-free, so no BigDecimal and no double")
        void thePageNumberIsAScaleFreeInteger() {
            RecordComponent pageNum =
                    UserDeleteRequest.Cu03Info.class.getRecordComponents()[2];
            assertThat(pageNum.getName()).isEqualTo("pageNum");
            assertThat(pageNum.getType())
                    .as("gate G22: eight digits, no V, no decimal position")
                    .isEqualTo(int.class);
            assertThat(UserDeleteRequest.Cu03Info.PAGE_NUM_DIGITS).isEqualTo(8);

            // Its image is the zero-filled PIC 9(08) form, produced by the codec's numeric move rule
            // rather than by string formatting, so the fill direction is COBOL's.
            assertThat(new UserDeleteRequest.Cu03Info("", "", 7, "N", "", "").fieldImages()
                    .get("CDEMO-CU03-PAGE-NUM"))
                    .as("a numeric MOVE zero-fills on the LEFT")
                    .isEqualTo("00000007")
                    .hasSize(UserDeleteRequest.Cu03Info.PAGE_NUM_DIGITS);
            assertThat(codec().movePic9(7L, UserDeleteRequest.Cu03Info.PAGE_NUM_DIGITS))
                    .isEqualTo("00000007");
        }

        @ParameterizedTest(name = "flag \"{0}\": YES={1} NO={2}")
        @CsvSource({
            "Y, true,  false",
            "N, false, true",
            "' ', false, false",
            "X, false, false",
        })
        @DisplayName("both 88-level states are driven, in both directions, plus values that are neither")
        void bothNextPageStatesAreDriven(String flag, boolean yes, boolean no) {
            // Gate G50. COUSR03C.cbl:55-56 declare 88 NEXT-PAGE-YES VALUE 'Y' and
            // 88 NEXT-PAGE-NO VALUE 'N' over a PIC X(01) item. The two are not complements: the item
            // can hold any character, and the source declares no third condition, so for a space or
            // any other character both conditions are correctly false.
            //
            // DIVERGENCE (B4): Cu03Info publishes NEXT_PAGE_YES and NEXT_PAGE_NO as constants but
            // declares no nextPageYes()/nextPageNo() predicate methods, so the conditions are
            // evaluated against those constants here rather than through accessors that do not exist.
            // What is declared is what is asserted.
            UserDeleteRequest.Cu03Info extension =
                    new UserDeleteRequest.Cu03Info("", "", 0, flag, "", "");

            assertThat(extension.nextPageFlg())
                    .as("the flag is carried at its declared width, whatever it holds")
                    .hasSize(UserDeleteRequest.Cu03Info.NEXT_PAGE_FLG_LENGTH);
            assertThat(UserDeleteRequest.Cu03Info.NEXT_PAGE_YES.equals(extension.nextPageFlg()))
                    .as("88 NEXT-PAGE-YES VALUE 'Y' holds for \"%s\": %s", flag, yes)
                    .isEqualTo(yes);
            assertThat(UserDeleteRequest.Cu03Info.NEXT_PAGE_NO.equals(extension.nextPageFlg()))
                    .as("88 NEXT-PAGE-NO VALUE 'N' holds for \"%s\": %s", flag, no)
                    .isEqualTo(no);
            assertThat(yes && no)
                    .as("no value satisfies both conditions")
                    .isFalse();

            // And the flag survives the wire under either reading.
            UserDeleteRequest request = requestOf(blankMapValues(), NavigationContext.empty(), "",
                    extension);
            assertThat(deserialise(serialise(request)).cu03Info().nextPageFlg())
                    .isEqualTo(extension.nextPageFlg());
        }

        @Test
        @DisplayName("the declared default is 'N', which is the VALUE clause on line 54")
        void theDeclaredDefaultIsNo() {
            UserDeleteRequest.Cu03Info initial = UserDeleteRequest.Cu03Info.initial();
            assertThat(initial.nextPageFlg())
                    .as("PIC X(01) VALUE 'N' - what a cold start sees before :94 overwrites the area")
                    .isEqualTo(UserDeleteRequest.Cu03Info.NEXT_PAGE_NO)
                    .isEqualTo("N");
            assertThat(initial.pageNum())
                    .as("the other five items declare no VALUE, so the number begins at zero")
                    .isZero();
            assertThat(initial.usridFirst())
                    .isEqualTo(spaces(UserDeleteRequest.Cu03Info.USRID_FIRST_LENGTH));
            assertThat(initial.usridLast())
                    .isEqualTo(spaces(UserDeleteRequest.Cu03Info.USRID_LAST_LENGTH));
            assertThat(initial.usrSelFlg())
                    .isEqualTo(spaces(UserDeleteRequest.Cu03Info.USR_SEL_FLG_LENGTH));
            assertThat(initial.usrSelected())
                    .isEqualTo(spaces(UserDeleteRequest.Cu03Info.USR_SELECTED_LENGTH));
            assertThat(UserDeleteRequest.Cu03Info.NEXT_PAGE_YES).isEqualTo("Y");
        }

        @Test
        @DisplayName("an absent extension becomes the cold-start state, never null")
        void anAbsentExtensionIsNormalised() {
            // 05 CDEMO-CU03-INFO is storage inside 01 CARDDEMO-COMMAREA: it has no absent state, so
            // the canonical constructor substitutes the state the VALUE clauses leave. Both arms of
            // that decision are driven - a supplied group is kept, an absent one is replaced - which is
            // what the per-package BRANCH gate measures.
            assertThat(requestOf(blankMapValues(), NavigationContext.empty(), "", null).cu03Info())
                    .isEqualTo(UserDeleteRequest.Cu03Info.initial());

            UserDeleteRequest.Cu03Info supplied = new UserDeleteRequest.Cu03Info("USER0001",
                    "USER0050", 2, UserDeleteRequest.Cu03Info.NEXT_PAGE_YES, "D", "USER0007");
            assertThat(requestOf(blankMapValues(), NavigationContext.empty(), "", supplied)
                    .cu03Info())
                    .as("a supplied group is carried through untouched")
                    .isSameAs(supplied);
        }

        @Test
        @DisplayName("an absent character item becomes its width in spaces, and an over-wide one truncates")
        void theItemsFollowThePicXMoveRule() {
            UserDeleteRequest.Cu03Info absent =
                    new UserDeleteRequest.Cu03Info(null, null, 0, null, null, null);
            assertThat(absent.usridFirst())
                    .as("a PIC X item has no absent state; null becomes its spaces")
                    .isEqualTo(spaces(UserDeleteRequest.Cu03Info.USRID_FIRST_LENGTH));
            assertThat(absent.nextPageFlg()).isEqualTo(" ");
            assertThat(absent.usrSelected())
                    .isEqualTo(spaces(UserDeleteRequest.Cu03Info.USR_SELECTED_LENGTH));

            UserDeleteRequest.Cu03Info wide = new UserDeleteRequest.Cu03Info(
                    "USER00012345", "USER00067890", 0, "YES", "DELETE", "USER00099999");
            assertThat(wide.usridFirst())
                    .as("an alphanumeric MOVE truncates on the RIGHT")
                    .isEqualTo("USER0001")
                    .hasSize(UserDeleteRequest.Cu03Info.USRID_FIRST_LENGTH);
            assertThat(wide.nextPageFlg())
                    .as("'YES' into PIC X(01) keeps the leading character only")
                    .isEqualTo("Y");
            assertThat(wide.usrSelFlg()).isEqualTo("D");
            assertThat(wide.usrSelected()).isEqualTo("USER0009")
                    .hasSize(UserDeleteRequest.Cu03Info.USR_SELECTED_LENGTH);
        }

        @Test
        @DisplayName("a negative page number has no representation in PIC 9(08) and is refused")
        void aNegativePageNumberIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new UserDeleteRequest.Cu03Info("", "", -1, "N", "", ""))
                    .withMessageContaining("PIC 9(8)");
        }

        @Test
        @DisplayName("a page number needing nine digits is refused, not silently truncated")
        void anOverWidePageNumberIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new UserDeleteRequest.Cu03Info("", "", 100_000_000, "N", "",
                            ""))
                    .withMessageContaining("cannot hold");
            assertThat(new UserDeleteRequest.Cu03Info("", "", 99_999_999, "N", "", "").pageNum())
                    .as("the largest value the picture can hold is accepted, so the bound is exact")
                    .isEqualTo(99_999_999);
        }

        @Test
        @DisplayName("usrSelFlg and usrSelected are carried - this is how COUSR00C hands a user over")
        void theSelectionItemsAreCarried() {
            // COUSR00C.cbl:200-202: WHEN 'D' / WHEN 'd' move 'COUSR03C' into CDEMO-TO-PROGRAM and
            // transfer with the whole area, selected id included. That is why a delete screen carries
            // paging members it never pages with: the block is one group and travels whole. Preserved
            // exactly, per B5 - trimming it to the one item this program reads would break the
            // handover.
            UserDeleteRequest.Cu03Info handover = new UserDeleteRequest.Cu03Info("USER0001",
                    "USER0010", 1, UserDeleteRequest.Cu03Info.NEXT_PAGE_YES, "D", "USER0007");
            assertThat(handover.usrSelFlg())
                    .hasSize(UserDeleteRequest.Cu03Info.USR_SEL_FLG_LENGTH)
                    .isEqualTo("D");
            assertThat(handover.usrSelected())
                    .as("COUSR03C.cbl:99-102 moves this into USRIDINI when it is not blank")
                    .hasSize(UserDeleteRequest.Cu03Info.USR_SELECTED_LENGTH)
                    .isEqualTo("USER0007");
            assertThat(handover.fieldImages())
                    .containsEntry("CDEMO-CU03-USR-SEL-FLG", "D")
                    .containsEntry("CDEMO-CU03-USR-SELECTED", "USER0007")
                    .containsEntry("CDEMO-CU03-PAGE-NUM", "00000001")
                    .hasSize(EXTENSION_MEMBERS.size());
        }
    }


    // =================================================================================================
    // 9. CONVERSATION STATE TRAVELS IN THE PAYLOAD, NEVER IN A SESSION (G37, rule R6).
    //
    // CICS is pseudo-conversational: COUSR03C ends at :134-137 with EXEC CICS RETURN TRANSID(WS-TRANID)
    // COMMAREA(CARDDEMO-COMMAREA) and is re-entered from the beginning on the next key press. The only
    // state that survives is what it handed back. Three things therefore travel in the payload and
    // nothing is kept server-side:
    //   * the 160-byte communication area plus this program's 34-byte extension;
    //   * the resolved AID token, because :108-130 branches on EIBAID;
    //   * the enter-versus-re-enter flag, which :95 tests to decide whether to paint or to validate.
    //
    // This screen's flow needs all three: the first entry paints, ENTER looks the user up (:110), and
    // PF5 performs the delete (:121-122). Unlike COUSR02C, whose PF3 saves before leaving, this
    // program's PF3 (:111-118) simply returns - a delete is never committed by walking away. Those
    // behaviours are UserDeleteControllerTest's subject; what is asserted here is that the payload
    // carries what makes them evaluable at all.
    // =================================================================================================

    @Nested
    @DisplayName("Conversation state - the commarea, the AID and the context flag are all payload")
    class ConversationState {

        @Test
        @DisplayName("the communication area is a member, so no session is ever needed")
        void theCommareaIsAPayloadMember() {
            assertThat(jsonMembersOf(populatedRequest())).contains("navigationContext");
            assertThat(populatedRequest().navigationContext()).isNotNull();
            assertThat(UserDeleteRequest.class.getRecordComponents()[DFHMDF_NAMED].getType())
                    .isEqualTo(NavigationContext.class);

            // Nothing in the type's surface refers to a servlet session, a cache or a scope.
            for (Method method : UserDeleteRequest.class.getDeclaredMethods()) {
                assertThat(method.getReturnType().getName())
                        .as("%s must not expose a server-side state holder", method.getName())
                        .doesNotContain("HttpSession").doesNotContain("Cache")
                        .doesNotContain("SessionScope");
            }
        }

        @Test
        @DisplayName("the context is carried through untouched, never widened or re-modelled")
        void theContextIsPassedThrough() {
            NavigationContext context = NavigationContext.empty()
                    .withFromTranid(UserDeleteRequest.TRANSACTION_ID)
                    .withFromProgram(UserDeleteRequest.PROGRAM_NAME)
                    .withToProgram("COADM01C")
                    .withUserId("ADMIN001")
                    .withUserTypeAdmin()
                    .withPgmReenter();
            UserDeleteRequest request = requestOf(blankMapValues(), context,
                    PfKeyResolver.AidKey.PFK03.token());

            assertThat(request.navigationContext())
                    .as("the same instance, not a copy and not a projection")
                    .isSameAs(context);
            assertThat(request.navigationContext().toFixedWidth(codec()))
                    .as("and still exactly 160 bytes, because this payload does not widen it")
                    .hasSize(NavigationContext.COMMAREA_LENGTH);
        }

        @ParameterizedTest(name = "CDEMO-PGM-CONTEXT = {0}: ENTER={1} REENTER={2}")
        @CsvSource({
            "0, true,  false",
            "1, false, true",
            "2, false, false",
            "9, false, false",
        })
        @DisplayName("both 88-level context states are driven, in both directions, plus digits that are neither")
        void bothContextStatesAreDriven(int pgmContext, boolean enter, boolean reenter) {
            // Gate G50. COCOM01Y.cpy:30 declares 88 CDEMO-PGM-ENTER VALUE 0 and :31 declares
            // 88 CDEMO-PGM-REENTER VALUE 1 over CDEMO-PGM-CONTEXT PIC 9(01). The item can hold any
            // digit, so the two conditions are not complements: for 2 or 9 both are correctly false.
            // Writing either predicate as the other's negation would route a cold start down the
            // validation path at :107 instead of painting the screen at :105.
            UserDeleteRequest request = requestOf(blankMapValues(),
                    NavigationContext.empty().withPgmContext(pgmContext), "");

            assertThat(request.pgmEnter()).isEqualTo(enter);
            assertThat(request.pgmReenter()).isEqualTo(reenter);

            // The flag has exactly one home: the read-through agrees with the area itself.
            assertThat(request.pgmEnter()).isEqualTo(request.navigationContext().isEnter());
            assertThat(request.pgmReenter()).isEqualTo(request.navigationContext().isReenter());
            assertThat(request.navigationContext().pgmContext()).isEqualTo(pgmContext);
        }

        @Test
        @DisplayName("without a context neither condition holds - the EIBCALEN = 0 cold start")
        void anAbsentContextSatisfiesNeitherCondition() {
            // COUSR03C.cbl:90-92: IF EIBCALEN = 0 the program moves 'COSGN00C' into CDEMO-TO-PROGRAM
            // and returns to the previous screen without ever reaching the context test at :95. An
            // absent context is that state, and both predicates must be false for it - which is also
            // the second, short-circuiting arm of each predicate, so both branches of both are driven.
            UserDeleteRequest request = requestOf(blankMapValues(), null, null);
            assertThat(request.navigationContext()).isNull();
            assertThat(request.pgmEnter()).isFalse();
            assertThat(request.pgmReenter()).isFalse();
            assertThat(violationsOf(request))
                    .as("an absent context is not a constraint violation; it is a routing condition")
                    .isEmpty();
        }

        @Test
        @DisplayName("the named context values are the copybook's, and the state survives JSON")
        void theContextStateSurvivesTheRoundTrip() {
            assertThat(NavigationContext.PGM_CONTEXT_ENTER).isZero();
            assertThat(NavigationContext.PGM_CONTEXT_REENTER).isEqualTo(1);

            UserDeleteRequest reentered = requestOf(blankMapValues(),
                    NavigationContext.empty().withPgmReenter(), "");
            UserDeleteRequest restored = deserialise(serialise(reentered));
            assertThat(restored.pgmReenter())
                    .as("SET CDEMO-PGM-REENTER TO TRUE at :96 must survive the wire, or the next "
                            + "entry would paint the screen again instead of validating it")
                    .isTrue();
            assertThat(restored.pgmEnter()).isFalse();
            assertThat(restored.navigationContext()).isEqualTo(reentered.navigationContext());
        }

        @ParameterizedTest(name = "aid = \"{0}\"")
        @ValueSource(strings = {"ENTER", "CLEAR", "PA1  ", "PA2  ", "PFK03", "PFK04", "PFK05",
            "PFK12"})
        @DisplayName("the resolved AID token is carried verbatim, trailing spaces included")
        void theAidTokenIsCarriedVerbatim(String token) {
            UserDeleteRequest request =
                    requestOf(blankMapValues(), NavigationContext.empty(), token);
            assertThat(request.aid())
                    .as("a token, not a raw EIBAID byte: the byte is EBCDIC and code-page dependent, "
                            + "the token is not")
                    .isEqualTo(token)
                    .hasSize(UserDeleteRequest.AID_LENGTH);
            assertThat(deserialise(serialise(request)).aid()).isEqualTo(token);
            assertThat(violationsOf(request)).isEmpty();
        }

        @Test
        @DisplayName("the tokens this screen branches on are the ones PfKeyResolver produces")
        void theTokensComeFromTheResolver() {
            // COUSR03C.cbl:108-130 branches on DFHENTER, DFHPF3, DFHPF4, DFHPF5 and DFHPF12, with a
            // WHEN OTHER that answers CCDA-MSG-INVALID-KEY. Each of those five resolves to a token of
            // the declared width, and the token - not the byte - is what travels.
            assertThat(PfKeyResolver.resolve(CicsAid.DFHENTER))
                    .as("WHEN DFHENTER at :109 - PERFORM PROCESS-ENTER-KEY")
                    .contains(PfKeyResolver.AidKey.ENTER);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF3))
                    .as("WHEN DFHPF3 at :111 - return without saving, unlike COUSR02C's PF3")
                    .contains(PfKeyResolver.AidKey.PFK03);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF4))
                    .as("WHEN DFHPF4 at :119 - PERFORM CLEAR-CURRENT-SCREEN")
                    .contains(PfKeyResolver.AidKey.PFK04);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF5))
                    .as("WHEN DFHPF5 at :121 - PERFORM DELETE-USER-INFO, the confirm step")
                    .contains(PfKeyResolver.AidKey.PFK05);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF12))
                    .as("WHEN DFHPF12 at :123 - back to the admin menu")
                    .contains(PfKeyResolver.AidKey.PFK12);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHCLEAR))
                    .as("CLEAR is not a WHEN of this EVALUATE, so it falls to WHEN OTHER at :126 - "
                            + "but the token must still be expressible in the payload")
                    .contains(PfKeyResolver.AidKey.CLEAR);

            for (PfKeyResolver.AidKey key : List.of(PfKeyResolver.AidKey.ENTER,
                    PfKeyResolver.AidKey.PFK03, PfKeyResolver.AidKey.PFK04,
                    PfKeyResolver.AidKey.PFK05, PfKeyResolver.AidKey.PFK12)) {
                UserDeleteRequest request = requestOf(blankMapValues(), NavigationContext.empty(),
                        key.token());
                assertThat(request.aid()).isEqualTo(key.token())
                        .hasSize(UserDeleteRequest.AID_LENGTH);
            }
        }

        @Test
        @DisplayName("an absent or blank AID is carried as itself - no key indication is a state too")
        void anAbsentAidIsCarried() {
            // COUSR03C reaches :108 only on re-entry; on first entry no key has been pressed and
            // EIBAID holds no meaningful indication. The payload must be able to say so, and three
            // forms of "nothing was pressed" are distinguishable: absent, blank at the declared width,
            // and empty. None is coerced into another, because PfKeyResolver has an explicit no-match
            // outcome and collapsing them would hide it.
            assertThat(requestOf(blankMapValues(), NavigationContext.empty(), null).aid()).isNull();
            assertThat(requestOf(blankMapValues(), NavigationContext.empty(),
                    spaces(UserDeleteRequest.AID_LENGTH)).aid())
                    .isEqualTo(spaces(UserDeleteRequest.AID_LENGTH));
            assertThat(requestOf(blankMapValues(), NavigationContext.empty(), "").aid()).isEmpty();
            assertThat(PfKeyResolver.resolve(CicsAid.DFHNULL))
                    .as("DFHNULL is the no-key-pressed indication and resolves to no token")
                    .isEmpty();
        }
    }

    // =================================================================================================
    // 10. THE ELEVEN xxxA REDEFINES xxxF OVERLAYS (G34).
    //
    // COUSR03.CPY declares twelve REDEFINES. Eleven are the per-field overlays at lines 21, 27, 33, 39,
    // 45, 51, 57, 63, 69, 75 and 81, each an 03 xxxA PICTURE X over the 02 xxxF byte; those are this
    // suite's subject. The twelfth is the group-level 01 COUSR3AO REDEFINES COUSR3AI at line 85 and
    // belongs to UserDeleteResponseTest. Twelve is also COSGN00's count - both screens carry eleven
    // fields - which cross-checks both transcriptions independently.
    //
    // Neither xxxF nor xxxA is a payload member - section 7 proves that - so the pair cannot be
    // round-tripped through the DTO. It is round-tripped through the storage the copybook actually
    // describes: SYMBOLIC_MAP_LAYOUT, built from the copybook's own geometry. That is what the gate
    // asks for - two typed accessors over one backing span - asserted against the real byte rather
    // than against a member invented to host it.
    //
    // The five COSGN00 and COUSR0n maps hold all 110 REDEFINES in this subtree (12 + 60 + 13 + 13 + 12)
    // and the five programs hold none, so this package is the only place the property has a subject.
    // =================================================================================================

    @Nested
    @DisplayName("REDEFINES - eleven attribute overlays, each over one shared byte")
    class RedefinesOverlays {

        @Test
        @DisplayName("the layout tiles 324 bytes exactly: 12 + 11 x 7 + 235")
        void theGeometryIsTheCopybooks() {
            // Constructing SYMBOLIC_MAP_LAYOUT already proved this - RecordLayout refuses a gap, an
            // unintended overlap and any total other than its declared length - so this case states
            // the arithmetic a reader needs rather than discovering it.
            assertThat(FIELD_PREFIX_LENGTH)
                    .as("xxxL 2 + xxxF 1 + FILLER X(4) = 7, and the output view's prefix is also 7, "
                            + "which is what lets COUSR3AO overlay COUSR3AI field for field")
                    .isEqualTo(7);
            assertThat(DECLARED_WIDTHS.stream().mapToInt(Integer::intValue).sum())
                    .isEqualTo(PAYLOAD_WIDTH_TOTAL);
            assertThat(TIOAPFX_PREFIX_LENGTH + DFHMDF_NAMED * FIELD_PREFIX_LENGTH
                    + PAYLOAD_WIDTH_TOTAL).isEqualTo(SYMBOLIC_MAP_LENGTH);
            assertThat(SYMBOLIC_MAP_LAYOUT.recordLength()).isEqualTo(SYMBOLIC_MAP_LENGTH);
            assertThat(SYMBOLIC_MAP_LAYOUT.redefinitions())
                    .as("eleven per-field overlays; the group-level one is not modelled here")
                    .hasSize(DFHMDF_NAMED)
                    .hasSize(REDEFINES_LINES.size());
            assertThat(SYMBOLIC_MAP_LAYOUT.storageSpans().stream()
                    .mapToInt(FixedWidthRecord.FieldSpan::length).sum())
                    .as("the storage spans, overlays excluded, sum to the record length")
                    .isEqualTo(SYMBOLIC_MAP_LENGTH);
        }

        @ParameterizedTest(name = "{0}A redefines {0}F")
        @ValueSource(strings = {"TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME",
            "USRIDIN", "FNAME", "LNAME", "USRTYPE", "ERRMSG"})
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
            "USRIDIN", "FNAME", "LNAME", "USRTYPE", "ERRMSG"})
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

            // And back the other way: one storage byte, two names for it. 'Z' stands in for whatever
            // attribute byte CSSETATY would move - DFHRED, DFHBMASB and the rest - because what the
            // gate asks is that the two views address the same storage, not what the value means.
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
        @DisplayName("writing every attribute leaves every xxxI item and every FILLER byte alone")
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
            assertThat(record.charset())
                    .as("the record was built over the named code page, never a default (B8)")
                    .isEqualTo(MAP_CHARSET);
        }

        @ParameterizedTest(name = "{0}A carries a real DFHBMSCA attribute byte")
        @ValueSource(strings = {"TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME",
            "USRIDIN", "FNAME", "LNAME", "USRTYPE", "ERRMSG"})
        @DisplayName("the shared byte holds the attribute values DFHBMSCA actually defines")
        void theOverlayCarriesARealAttributeByte(String screenField) {
            // The previous case proves the two views share storage using printable stand-ins. This one
            // proves it with the values the overlay is actually for: DFHBMSCA's attribute bytes are
            // EBCDIC code points - DFHRED is X'F2', DFHBMASB is X'F8' - and are written and read as
            // BYTES rather than as text, because transcoding them through US-ASCII would be a category
            // error. That is exactly why FixedWidthRecord exposes a byte-level span accessor alongside
            // the text one.
            FixedWidthRecord record = FixedWidthRecord.forLayout(SYMBOLIC_MAP_LAYOUT, MAP_CHARSET);
            FixedWidthRecord.FieldSpan flag = SYMBOLIC_MAP_LAYOUT.span(screenField + "F");
            FixedWidthRecord.FieldSpan attribute = SYMBOLIC_MAP_LAYOUT.span(screenField + "A");

            record.writeSpanBytes(attribute, new byte[] {BmsAttributes.DFHRED});
            assertThat(record.readSpanBytes(flag))
                    .as("the error colour written through the attribute view is the flag byte")
                    .containsExactly(BmsAttributes.DFHRED);

            record.writeSpanBytes(flag, new byte[] {BmsAttributes.DFHBMASB});
            assertThat(record.readSpanBytes(attribute))
                    .as("and an autoskip-bright attribute written through the flag view reads back "
                            + "through the overlay")
                    .containsExactly(BmsAttributes.DFHBMASB);
            assertThat(record.toByteArray()).hasSize(SYMBOLIC_MAP_LENGTH);
        }

        @Test
        @DisplayName("the eleven overlay offsets are the eleven flag offsets, and no other span moved")
        void everyOverlayLandsOnItsOwnFlagByte() {
            List<Integer> flagOffsets = new ArrayList<>();
            List<Integer> overlayOffsets = new ArrayList<>();
            for (String screenField : SCREEN_FIELDS) {
                flagOffsets.add(SYMBOLIC_MAP_LAYOUT.span(screenField + "F").offset());
                overlayOffsets.add(SYMBOLIC_MAP_LAYOUT.span(screenField + "A").offset());
            }
            assertThat(overlayOffsets)
                    .as("each of the eleven overlays sits on its own field's flag byte")
                    .containsExactlyElementsOf(flagOffsets)
                    .doesNotHaveDuplicates()
                    .isSorted();
            assertThat(SYMBOLIC_MAP_LAYOUT.redefinitions().stream()
                    .map(FixedWidthRecord.FieldSpan::offset).toList())
                    .containsExactlyElementsOf(flagOffsets);
        }
    }

    // =================================================================================================
    // 11. SECURITY POSTURE, AND THE VESTIGIAL STORAGE THIS SCREEN INHERITED (G41, B4, B6).
    // =================================================================================================

    @Nested
    @DisplayName("Security posture - nothing to protect, and nothing invented to protect it with")
    class SecurityPosture {

        @Test
        @DisplayName("no hashing, encoder, token or security framework type is reachable")
        void noSecurityFrameworkTypeIsReachable() {
            // On the sign-on screen this assertion guards a real credential against being hardened.
            // Here it guards against something being INVENTED: with no password member there is
            // nothing to hash, so any encoder, digest or token type appearing on this type's surface
            // could only be a feature the COBOL never had. Spring Security is out of scope entirely.
            List<String> reachable = new ArrayList<>();
            for (RecordComponent component : UserDeleteRequest.class.getRecordComponents()) {
                reachable.add(component.getType().getName());
                for (Annotation annotation : component.getAccessor().getAnnotations()) {
                    reachable.add(annotation.annotationType().getName());
                }
            }
            for (Method method : UserDeleteRequest.class.getDeclaredMethods()) {
                reachable.add(method.getReturnType().getName());
                reachable.add(method.getName());
                for (Class<?> parameter : method.getParameterTypes()) {
                    reachable.add(parameter.getName());
                }
            }
            for (Method method : UserDeleteRequest.Cu03Info.class.getDeclaredMethods()) {
                reachable.add(method.getReturnType().getName());
                reachable.add(method.getName());
            }

            for (String name : reachable) {
                for (String marker : FORBIDDEN_SECURITY_MARKERS) {
                    assertThat(name)
                            .as("%s suggests %s, which this screen has no subject for", name, marker)
                            .doesNotContain(marker);
                }
            }
            assertThat(reachable).isNotEmpty();
        }

        @Test
        @DisplayName("the diagnostic rendering withholds the personal name and reports its presence")
        void theDiagnosticRenderingWithholdsThePersonalName() {
            // FNAME and LNAME are a real person's given and family names. Carrying them is required -
            // COUSR03C.cbl:165-166 paints them so the operator can confirm the right record before
            // deleting it - but broadcasting them into a log line is not, and a name has no safely
            // revealable part. Everything else renders as stored, because a screen-flow parity failure
            // has to be readable from the rendering.
            //
            // DIVERGENCE (B4): the declared toString renders thirteen of the fourteen components and
            // omits cu03Info. That is asserted as declared rather than corrected here.
            UserDeleteRequest request = populatedRequest();
            String rendered = request.toString();

            assertThat(rendered).doesNotContain("Given").doesNotContain("Family");
            assertThat(rendered).contains("fName=").contains("lName=");
            assertThat(rendered)
                    .contains(UserDeleteRequest.TRANSACTION_ID)
                    .contains(UserDeleteRequest.PROGRAM_NAME)
                    .contains(request.usrIdIn())
                    .contains("navigationContext=")
                    .contains("aid=");
            assertThat(rendered).startsWith("UserDeleteRequest[").endsWith("]");
        }

        @Test
        @DisplayName("equals and hashCode cover every component, extension included")
        void valueSemanticsCoverEveryComponent() {
            UserDeleteRequest base = populatedRequest();

            for (int index = 0; index < DFHMDF_NAMED; index++) {
                List<String> altered = new ArrayList<>(mapValuesOf(base));
                altered.set(index, "Q".repeat(DECLARED_WIDTHS.get(index)));
                assertThat(requestOf(altered, base.navigationContext(), base.aid(),
                        base.cu03Info()))
                        .as("changing %s must change the value", MAP_MEMBERS.get(index))
                        .isNotEqualTo(base);
            }
            assertThat(requestOf(mapValuesOf(base), NavigationContext.empty().withPgmReenter(),
                    base.aid(), base.cu03Info()))
                    .as("and so must changing the communication area")
                    .isNotEqualTo(base);
            assertThat(requestOf(mapValuesOf(base), base.navigationContext(),
                    PfKeyResolver.AidKey.PFK05.token(), base.cu03Info()))
                    .as("and the AID token")
                    .isNotEqualTo(base);
            assertThat(requestOf(mapValuesOf(base), base.navigationContext(), base.aid(),
                    new UserDeleteRequest.Cu03Info("", "", 1, "N", "", "")))
                    .as("and the extension group")
                    .isNotEqualTo(base);
            assertThat(populatedRequest()).isEqualTo(base).hasSameHashCodeAs(base);
        }
    }

    @Nested
    @DisplayName("Vestigial and declared state - recorded, not modelled (B4, B5)")
    class VestigialAndDeclaredState {

        @Test
        @DisplayName("the vestigial WS-USR-MODIFIED flag is recorded here and modelled nowhere")
        void theVestigialModifiedFlagIsNotModelled() {
            // app/cbl/COUSR03C.cbl:45-47 declares
            //     05 WS-USR-MODIFIED PIC X(01) VALUE 'N'.
            //        88 USR-MODIFIED-YES VALUE 'Y'.
            //        88 USR-MODIFIED-NO  VALUE 'N'.
            // copied verbatim from COUSR02C, where the update flow needs it to decide whether a
            // REWRITE is warranted. In THIS program it is never SET, never moved to and never tested:
            // a delete has no before-and-after to compare. It is dead storage.
            //
            // Both halves of the ruling matter. It is not modelled on the DTO, because a payload
            // member the program cannot observe would be an invented field (B5) and would fail the
            // eleven-plus-three census in section 1. And it is not erased from the record of what the
            // source contains, because that fact is what explains why COUSR02's and COUSR03's
            // WORKING-STORAGE look so alike (B4).
            //
            // Its two 88-levels are therefore deliberately NOT driven anywhere in this suite: G50 asks
            // for both states of every condition name in scope, and a condition name over storage no
            // Java member represents is not in scope. The 88-levels that ARE in scope - NEXT-PAGE-YES,
            // NEXT-PAGE-NO, CDEMO-PGM-ENTER and CDEMO-PGM-REENTER - are each driven in both readings.
            for (RecordComponent component : UserDeleteRequest.class.getRecordComponents()) {
                assertThat(component.getName().toLowerCase(Locale.ROOT))
                        .as("%s must not model WS-USR-MODIFIED", component.getName())
                        .doesNotContain("modified").doesNotContain("dirty").doesNotContain("changed");
            }
            for (RecordComponent component
                    : UserDeleteRequest.Cu03Info.class.getRecordComponents()) {
                assertThat(component.getName().toLowerCase(Locale.ROOT))
                        .doesNotContain("modified");
            }
            assertThat(serialise(populatedRequest()))
                    .doesNotContain("usrModified").doesNotContain("USR-MODIFIED");
        }

        @Test
        @DisplayName("no optimistic-concurrency member exists, because COUSR03C has no such check")
        void noOptimisticConcurrencyMemberExists() {
            // Gate G43 has no subject in this package. COACTUPC and COCRDUPC each carry a
            // 9300-CHECK-CHANGE-IN-REC paragraph that re-reads and compares before rewriting;
            // COUSR03C carries none, and neither does COUSR02C. There is therefore no version column,
            // no ETag and no before-image to assert here, and adding one would be a new feature.
            for (RecordComponent component : UserDeleteRequest.class.getRecordComponents()) {
                assertThat(component.getName().toLowerCase(Locale.ROOT))
                        .as("%s must not be a concurrency token", component.getName())
                        .doesNotContain("version").doesNotContain("etag")
                        .doesNotContain("beforeimage").doesNotContain("revision");
            }
        }

        @Test
        @DisplayName("no dataset name appears in the payload's surface (G46)")
        void noDatasetNameIsExposed() {
            // COUSR03C.cbl:39 declares 05 WS-USRSEC-FILE PIC X(08) VALUE 'USRSEC  ' - the CICS FILE
            // name, resolved from application.yml in the Java module and never a literal in source.
            // The payload carries screen fields, not the name of the file behind them.
            String json = serialise(populatedRequest());
            assertThat(json)
                    .doesNotContain("AWS.M2.CARDDEMO")
                    .doesNotContain("VSAM")
                    .doesNotContain("USRSEC");
            for (String member : jsonMembersOf(populatedRequest())) {
                assertThat(member.toUpperCase(Locale.ROOT)).doesNotContain("USRSEC");
            }
        }
    }

    // =================================================================================================
    // 12. THE BLANK PAYLOAD - INITIALIZE-ALL-FIELDS, app/cbl/COUSR03C.cbl:349-356.
    //
    // The paragraph CLEAR-CURRENT-SCREEN performs on PF4 (:120) and the delete path performs on success
    // (:315). In source it moves SPACES into USRIDINI, FNAMEI, USRTYPEI and WS-MESSAGE and -1 into
    // USRIDINL; empty() extends that to all eleven members so the result is a complete payload rather
    // than a partly populated one. It is the MOVE SPACES shape, NOT the MOVE LOW-VALUES shape of :97.
    // =================================================================================================

    @Nested
    @DisplayName("The blank payload of INITIALIZE-ALL-FIELDS")
    class BlankPayload {

        @ParameterizedTest(name = "{0} is {1} spaces")
        @CsvSource({"trnName,4", "title01,40", "curDate,8", "pgmName,8", "title02,40", "curTime,8",
            "usrIdIn,8", "fName,20", "lName,20", "usrType,1", "errMsg,78"})
        @DisplayName("each member is a run of spaces of exactly its declared width")
        void spaceFilledAtDeclaredWidth(String member, int width) {
            String value = mapValuesOf(UserDeleteRequest.empty()).get(MAP_MEMBERS.indexOf(member));
            assertThat(value)
                    .as("%s must be %d spaces, not an empty string and not null", member, width)
                    .isEqualTo(spaces(width))
                    .hasSize(width);
        }

        @Test
        @DisplayName("no member is null, the AID is five spaces and the state carriers are initial")
        void stateCarriersAreInitialised() {
            UserDeleteRequest blank = UserDeleteRequest.empty();
            assertThat(mapValuesOf(blank)).doesNotContainNull();
            assertThat(blank.aid()).isEqualTo(spaces(UserDeleteRequest.AID_LENGTH));
            assertThat(blank.navigationContext()).isEqualTo(NavigationContext.empty());
            assertThat(blank.cu03Info()).isEqualTo(UserDeleteRequest.Cu03Info.initial());
            assertThat(blank.pgmEnter())
                    .as("an initial commarea is in CDEMO-PGM-ENTER state, so the screen paints")
                    .isTrue();
            assertThat(blank.pgmReenter()).isFalse();
            assertThat(violationsOf(blank))
                    .as("a blank payload is valid input, which is the whole point of it")
                    .isEmpty();
        }

        @Test
        @DisplayName("it is the MOVE SPACES shape, not the MOVE LOW-VALUES shape of line 97")
        void spacesNotLowValues() {
            UserDeleteRequest blank = UserDeleteRequest.empty();
            assertThat(blank.usrIdIn())
                    .isEqualTo(spaces(UserDeleteRequest.USRIDIN_LENGTH))
                    .isNotEqualTo(lowValues(UserDeleteRequest.USRIDIN_LENGTH));
            assertThat(blank).isNotEqualTo(requestOf(nullMapValues(), NavigationContext.empty(),
                    spaces(UserDeleteRequest.AID_LENGTH)));
            assertThat(blank.errMsg())
                    .as("WS-MESSAGE is blanked alongside the fields at :356")
                    .isEqualTo(spaces(UserDeleteRequest.ERRMSG_LENGTH));
        }

        @Test
        @DisplayName("two blank payloads are equal, share a hash and survive the wire unchanged")
        void valueSemanticsAndRoundTrip() {
            assertThat(UserDeleteRequest.empty())
                    .isEqualTo(UserDeleteRequest.empty())
                    .hasSameHashCodeAs(UserDeleteRequest.empty());
            assertThat(deserialise(serialise(UserDeleteRequest.empty())))
                    .as("324 bytes of screen reduced to a payload and back, padding intact")
                    .isEqualTo(UserDeleteRequest.empty());
            assertThat(jsonMembersOf(UserDeleteRequest.empty()))
                    .containsExactlyInAnyOrderElementsOf(EXPECTED_JSON_MEMBERS);
        }
    }
}

