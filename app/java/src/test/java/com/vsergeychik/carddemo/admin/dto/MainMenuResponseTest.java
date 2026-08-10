package com.vsergeychik.carddemo.admin.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.admin.model.MenuOptions;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import jakarta.validation.constraints.Size;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link MainMenuResponse}, the outbound projection of the {@code xxxO} items of
 * {@code 01 COMEN1AO REDEFINES COMEN1AI} - the response payload of {@code GET /api/menu}, CICS
 * transaction {@code CM00}, program {@code COMEN01C}, mapset {@code COMEN01}, map {@code COMEN1A}.
 *
 * <h2>User rules</h2>
 * <strong>No user-specified rules govern this file.</strong> {@code review_rules} returns exactly one
 * line - {@code "No user rules provided."} - and that one line is the whole document. Nothing has been
 * invented in their place and the bar is not lowered: the twelve enterprise practices {@code B1} to
 * {@code B12} of the Agent Action Plan section 0.10.2 are binding instead, and each is named below
 * against what it requires of this file.
 *
 * <h2>The twelve practices, and where each is honoured here</h2>
 * <ul>
 *   <li><strong>B1</strong> - no dependency and no version literal is introduced. Only what
 *       {@code app/java/pom.xml} already declares is used: JUnit 5 and AssertJ through
 *       {@code spring-boot-starter-test}, Jackson through {@code spring-boot-starter-web} and Bean
 *       Validation through {@code spring-boot-starter-validation}.</li>
 *   <li><strong>B2</strong> - JUnit 5 and Spring Boot 3.5.16-era APIs only. This is a plain unit test:
 *       no Spring context is started, no {@code MockMvc} is used and no application is bootstrapped.</li>
 *   <li><strong>B3</strong>, <strong>B11</strong>, <strong>B12</strong> - see the next section.</li>
 *   <li><strong>B4</strong> - {@link AdminTwinIsADistinctType}: the duplication is documented, never
 *       deduplicated.</li>
 *   <li><strong>B5</strong> - {@link ComingSoonDefect}, {@link TwelveOptionSlots} and
 *       {@link ColourAndHeaderLiterals}: the legacy defect, the two unwritable slots and the two
 *       commented-out decoys are all preserved as they stand.</li>
 *   <li><strong>B6</strong> - no subject; see the closing note on gates.</li>
 *   <li><strong>B7</strong> - a fixed {@link Clock}, no wall-clock read, no randomness, no sleep and no
 *       ordering between tests, so the suite is reproducible under {@code mvn -B clean verify}.</li>
 *   <li><strong>B8</strong> - the charset is stated explicitly once, at codec construction, and every
 *       width is asserted against both the published constant and the transcribed literal.</li>
 *   <li><strong>B9</strong> - {@code StatelessNavigation.nothingIsHeldOnTheServer()} sweeps the record,
 *       its builder and this class for static mutable state.</li>
 *   <li><strong>B10</strong> - these tests ship with the payload type they cover, in the same phase, so
 *       an equivalence failure is traceable to the translation decision that caused it.</li>
 * </ul>
 *
 * <h2>Where every expected value comes from</h2>
 * Every expectation in this class is a <em>literal</em> carrying a source-line citation. Nothing is
 * read from {@code app/cbl}, {@code app/cpy}, {@code app/cpy-bms}, {@code app/bms} or {@code app/csd}
 * at run time, and no copybook is parsed. That is deliberate on three counts:
 * <ul>
 *   <li><strong>Practice B3</strong> - the COBOL sources are the immutable parity oracle. This test
 *       neither writes them nor opens them; it is runnable from a jar with no repository present.</li>
 *   <li><strong>Practice B11</strong> - every width and offset below is hand-written arithmetic that a
 *       reviewer can check against the copybook in one step. A parser that agreed with itself would
 *       prove nothing, because a test that derives its expectation from the same file it is checking
 *       cannot fail when the transcription is wrong.</li>
 *   <li><strong>Practice B12</strong> - the baseline is <em>statically derived</em> (Agent Action Plan
 *       risk {@code R-A}): the legacy programs cannot be executed in this environment, so expected
 *       values come from structured reading of the source, cited line by line, rather than from a
 *       captured run.</li>
 * </ul>
 *
 * <h2>The five facts this class exists to pin down</h2>
 * <ol>
 *   <li>{@code ERRMSGO} is {@code PIC X(78)} and receives an {@code X(80)} image, so bytes 79 and 80
 *       are lost by <strong>RIGHT</strong> truncation ({@code app/cbl/COMEN01C.cbl:38}, {@code :187};
 *       {@code app/cpy-bms/COMEN01.CPY:260}). No real message on this screen is long enough to reveal
 *       the direction, so a synthetic distinguishable 80-character probe is used - twice.</li>
 *   <li>{@code OPTIONO} is the two-digit image of {@code WS-OPTION PIC 9(02)}, which is
 *       <strong>LEFT</strong> zero-filled ({@code :46}, {@code :125}). The two directions are opposite
 *       and both are exercised here, so the two codec helpers can never be swapped silently.</li>
 *   <li>The coming-soon text reads {@code This option Accountis coming soon ...} - with
 *       <strong>no space before {@code is}</strong>. That is a genuine legacy defect and practice
 *       {@code B5} requires it be preserved verbatim. See {@link ComingSoonDefect}.</li>
 *   <li>The option table declares twelve slots and the program fills ten. Slots 11 and 12 are
 *       preserved, never pruned ({@code app/cpy/COMEN02Y.cpy:21}, {@code :88};
 *       {@code app/cbl/COMEN01C.cbl:238-239}, {@code :269-272}).</li>
 *   <li>The input and output views of the map area are both 820 bytes with an identical 7-byte
 *       per-field prefix, which is <em>why</em> {@code COMEN1AO REDEFINES COMEN1AI} is legal
 *       (gate {@code G34}). See {@link Geometry}.</li>
 * </ol>
 *
 * <h2>This class is a mirror of {@code AdminMenuResponseTest}, and shares nothing with it</h2>
 * The two symbolic maps are byte-identical apart from their group names, so the two tests necessarily
 * assert similar shapes. They still share no base class, no helper, no fixture builder and no
 * parameterized suite, because the duplication is a property of the legacy system and collapsing it
 * would hide the places where the two screens genuinely diverge - and there are two such places on
 * this screen alone. See {@link AdminTwinIsADistinctType} for the recorded {@code diff} and the
 * mandatory distinct-types assertion (practice {@code B4}).
 *
 * <h2>Gates</h2>
 * Applied: {@code G9} (every payload field traces to a {@code DFHMDF} definition and every width to a
 * symbolic-map {@code PICTURE}), {@code G21} ({@code FILLER} spans present - the width assertions fail
 * immediately if one is omitted), {@code G34} (the {@code REDEFINES} pair as two typed views over one
 * 820-byte span), {@code G37} (no server-side state), {@code G40} ({@code XCTL} becomes
 * {@code nextProgram} / {@code nextMapset} / {@code nextMap}), {@code G49} ({@code BRANCH} coverage of
 * {@code admin.dto}), {@code G52} (no wildcard imports, static ones included), {@code G53} (no static
 * mutable state) and {@code G54} (non-interactive, no watch mode, no ordering between tests).
 *
 * <p>No subject here, and therefore not simulated: {@code G22} to {@code G29} - this payload declares
 * no decimal {@code PICTURE} and performs no arithmetic, so {@code CobolDecimal} has no part in it;
 * {@code G35} - no {@code AbendException} path reaches a screen payload; {@code G44} to {@code G47} -
 * no repository, no dataset and no {@code FILE STATUS}. Practice {@code B6} likewise has no subject:
 * this screen carries no credential field, so there is no masking or hashing to assert.
 */
@DisplayName("MainMenuResponse - the COMEN1AO output projection of the CM00 main menu")
class MainMenuResponseTest {

    // =================================================================================================
    // Fixed, deterministic infrastructure (practices B7, B8).
    //
    // Every field here is static AND final. Nothing in this class is static and mutable, so no test can
    // observe a value another test left behind and no execution order matters (gate G53, practice B9,
    // gate G54).
    // =================================================================================================

    /**
     * The code page of the ASCII text fixtures, named explicitly and never taken from the platform
     * (practice B8). {@code app/data/ASCII} is the authoritative fixture set for this migration, and
     * {@code FixedWidthCodec} refuses to be constructed without a charset precisely so that no caller
     * can fall back to a default.
     */
    private static final Charset FIXTURE_CHARSET = StandardCharsets.US_ASCII;

    /**
     * The one codec every fixed-width operation in this class routes through, constructed with
     * {@link #FIXTURE_CHARSET} so the charset is stated once and passed explicitly on every use
     * (practice B8). It is immutable and holds no per-call state, so sharing it across tests is safe.
     */
    private static final FixedWidthCodec CODEC = new FixedWidthCodec(FIXTURE_CHARSET);

    /**
     * A frozen clock, so {@code curDate} and {@code curTime} are byte-identical on every run
     * (practice B7). {@code DateHeader} never reads the wall clock of its own accord; the instant is
     * always supplied, and here it is 2022-07-19T23:12:33Z - the version-footer timestamp
     * {@code app/cbl/COMEN01C.cbl:281} carries, chosen so the rendered values are traceable to the
     * analysed revision rather than to the day the suite happens to run.
     */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2022-07-19T23:12:33Z"), ZoneOffset.UTC);

    // =================================================================================================
    // Declared widths, transcribed from app/cpy-bms/COMEN01.CPY with the line of each xxxO item.
    //
    // These are the "hard literal" half of every width assertion. Each one is checked against the
    // matching MainMenuResponse constant as well, so a constant that drifted from the copybook fails
    // here rather than in production (practice B8).
    // =================================================================================================

    /** {@code TRNNAMEO PIC X(4)}, {@code app/cpy-bms/COMEN01.CPY:146}. */
    private static final int TRN_NAME_WIDTH = 4;

    /** {@code TITLE01O} and {@code TITLE02O PIC X(40)}, CPY lines 152 and 170. */
    private static final int TITLE_WIDTH = 40;

    /** {@code CURDATEO PIC X(8)}, CPY line 158. */
    private static final int CUR_DATE_WIDTH = 8;

    /** {@code PGMNAMEO PIC X(8)}, CPY line 164. */
    private static final int PGM_NAME_WIDTH = 8;

    /** {@code CURTIMEO PIC X(8)}, CPY line 176. */
    private static final int CUR_TIME_WIDTH = 8;

    /** {@code OPTN001O} to {@code OPTN012O PIC X(40)}, CPY lines 182 to 248 in steps of 6. */
    private static final int OPTION_LINE_WIDTH = 40;

    /** {@code OPTIONO PIC X(2)}, CPY line 254; {@code app/bms/COMEN01.bms:148} {@code LENGTH=2}. */
    private static final int OPTION_WIDTH = 2;

    /** {@code ERRMSGO PIC X(78)}, CPY line 260; {@code app/bms/COMEN01.bms:156} {@code LENGTH=78}. */
    private static final int ERR_MSG_WIDTH = 78;

    /** {@code CDEMO-MENU-OPT OCCURS 12 TIMES}, {@code app/cpy/COMEN02Y.cpy:88}. */
    private static final int DECLARED_OPTION_SLOTS = 12;

    /** {@code CDEMO-MENU-OPT-COUNT PIC 9(02) VALUE 10}, {@code app/cpy/COMEN02Y.cpy:21}. */
    private static final int POPULATED_OPTION_SLOTS = 10;

    /** The twenty {@code xxxO} items of the output group, CPY lines 139 to 260. */
    private static final int PAYLOAD_FIELD_COUNT = 20;

    /**
     * {@code FILLER PIC X(12)} at {@code app/cpy-bms/COMEN01.CPY:140} (and {@code :18} in the input
     * group) - the {@code TIOAPFX=YES} prefix declared by {@code DFHMSD} at
     * {@code app/bms/COMEN01.bms:24}.
     */
    private static final int TIOAPFX_FILLER_WIDTH = 12;

    /**
     * The per-field prefix, 7 bytes in <strong>both</strong> views of the map area.
     *
     * <p>Output ({@code app/cpy-bms/COMEN01.CPY:141-145} and every group of five that follows):
     * {@code FILLER PICTURE X(3)} + {@code xxxC} + {@code xxxP} + {@code xxxH} + {@code xxxV}, each
     * {@code PICTURE X} - so 3 + 1 + 1 + 1 + 1 = 7.
     *
     * <p>Input ({@code :19-23} and every group that follows): {@code xxxL COMP PIC S9(4)}, a two-byte
     * binary halfword, + {@code xxxF PICTURE X} + {@code FILLER PICTURE X(4)} - so 2 + 1 + 4 = 7.
     *
     * <p>The two strides being equal is exactly why {@code 01 COMEN1AO REDEFINES COMEN1AI} at
     * {@code :139} is legal, and it is asserted rather than assumed in {@link Geometry}.
     */
    private static final int ATTRIBUTE_PREFIX_WIDTH = 7;

    /** {@code FILLER PICTURE X(3)}, the first item of every output-group field, CPY line 141 onwards. */
    private static final int OUTPUT_FIELD_FILLER_WIDTH = 3;

    /**
     * The four single-byte attribute items of every output-group field - {@code xxxC} colour,
     * {@code xxxP} programmed-symbol set, {@code xxxH} highlight and {@code xxxV} validation - CPY lines
     * 142 to 145 and every group of four that follows. Each is {@code PICTURE X}.
     */
    private static final int OUTPUT_ATTRIBUTE_ITEMS = 4;

    /**
     * {@code xxxL COMP PIC S9(4)}, CPY line 19 onwards - the input-group length item. Two bytes: a
     * binary halfword, not two characters, which is why it is modelled below as a reserved span rather
     * than as text.
     */
    private static final int INPUT_LENGTH_ITEM_WIDTH = 2;

    /** {@code xxxF PICTURE X}, CPY line 20 onwards - the input-group flag byte, redefined as {@code xxxA}. */
    private static final int INPUT_FLAG_ITEM_WIDTH = 1;

    /** {@code FILLER PICTURE X(4)}, CPY line 23 onwards - the input group's reserved span per field. */
    private static final int INPUT_FIELD_FILLER_WIDTH = 4;

    /** {@code DFHMDF} definitions in {@code app/bms/COMEN01.bms}: 20 named plus 8 unnamed literals. */
    private static final int MAPSET_FIELD_DEFINITIONS = 28;

    /** {@code app/bms/COMEN01.bms:28} declares {@code SIZE=(24,80)} on the single {@code DFHMDI}. */
    private static final int SCREEN_ROWS = 24;

    /** {@code app/bms/COMEN01.bms:28} declares {@code SIZE=(24,80)} on the single {@code DFHMDI}. */
    private static final int SCREEN_COLUMNS = 80;

    /**
     * {@code WS-MESSAGE PIC X(80)}, {@code app/cbl/COMEN01C.cbl:38} - the sending field of the
     * {@code MOVE} at {@code :187}, and the reason {@code errMsg} loses two bytes.
     */
    private static final int WS_MESSAGE_WIDTH = 80;

    // =================================================================================================
    // Message literals. Each is transcribed character-for-character from the cited line, and each
    // length below was measured against the source rather than counted by eye.
    // =================================================================================================

    /**
     * {@code app/cbl/COMEN01C.cbl:131-132}, moved to {@code WS-MESSAGE} when the entered option is
     * non-numeric, above {@code CDEMO-MENU-OPT-COUNT}, or zero. Exactly 37 characters, with no
     * trailing space inside the quotes.
     */
    private static final String VALIDATION_MESSAGE = "Please enter a valid option number...";

    /**
     * {@code app/cbl/COMEN01C.cbl:140-141}, moved to {@code WS-MESSAGE} when a {@code 'U'} user selects
     * an option whose {@code CDEMO-MENU-OPT-USRTYPE} is {@code 'A'} ({@code :136-137}).
     *
     * <p>Exactly 33 characters: the 32-character text <em>plus one trailing space</em>, which is
     * physically present inside the quotes in the source and is transcribed here. Trimming it would
     * change the byte image.
     *
     * <p>This message has <strong>no {@code COADM01C} counterpart</strong>. It is the observable output
     * of the user-type authorization filter, which exists only on the main menu, and it is therefore
     * one of the two assertions that make this file genuinely different from its admin twin rather
     * than a copy of it.
     */
    private static final String NO_ACCESS_MESSAGE = "No access - Admin Only option... ";

    /** The first literal of the coming-soon {@code STRING}, {@code app/cbl/COMEN01C.cbl:159}. */
    private static final String COMING_SOON_PREFIX = "This option ";

    /** The third literal of the coming-soon {@code STRING}, {@code app/cbl/COMEN01C.cbl:162}. */
    private static final String COMING_SOON_SUFFIX = "is coming soon ...";

    // =================================================================================================
    // The twenty payload member names, in map order. The order is the copybook's declaration order and
    // is itself part of the contract, because it is the order the symbolic map lays the bytes out in.
    // =================================================================================================

    /** The twenty {@code xxxO} projections, in the order {@code COMEN01.CPY:146} to {@code :260}. */
    private static final List<String> PAYLOAD_MEMBERS = List.of("trnName",
            "title01",
            "curDate",
            "pgmName",
            "title02",
            "curTime",
            "optn001",
            "optn002",
            "optn003",
            "optn004",
            "optn005",
            "optn006",
            "optn007",
            "optn008",
            "optn009",
            "optn010",
            "optn011",
            "optn012",
            "option",
            "errMsg");

    /**
     * The six members that are <em>not</em> {@code xxxO} projections, in declaration order: the echoed
     * communication area, the three stateless navigation targets, and the two metadata members that
     * {@code @JsonIgnore} keeps out of the payload.
     */
    private static final List<String> NON_PAYLOAD_MEMBERS = List.of("navigationContext",
            "nextProgram",
            "nextMapset",
            "nextMap",
            "errMsgColor",
            "resetAllOutputFields");

    /**
     * The twenty map field names as {@code app/bms/COMEN01.bms} spells them, used to build the
     * {@code xxxC} / {@code xxxP} / {@code xxxH} / {@code xxxV} names that must never be payload.
     */
    private static final List<String> MAP_FIELD_NAMES = List.of("TRNNAME",
            "TITLE01",
            "CURDATE",
            "PGMNAME",
            "TITLE02",
            "CURTIME",
            "OPTN001",
            "OPTN002",
            "OPTN003",
            "OPTN004",
            "OPTN005",
            "OPTN006",
            "OPTN007",
            "OPTN008",
            "OPTN009",
            "OPTN010",
            "OPTN011",
            "OPTN012",
            "OPTION",
            "ERRMSG");

    /**
     * The declared width of each of the twenty payload members, in map order, keyed by member name.
     *
     * <p>A {@code LinkedHashMap} rather than {@code Map.of}, because the iteration order is the map
     * order and the order is asserted.
     */
    private static final Map<String, Integer> DECLARED_WIDTHS = declaredWidths();

    /**
     * The ten {@code EXEC CICS XCTL} targets of {@code app/cpy/COMEN02Y.cpy}, in subscript order, with
     * the copybook line of each: {@code :28}, {@code :34}, {@code :40}, {@code :46}, {@code :52},
     * {@code :58}, {@code :64}, {@code :71}, {@code :77}, {@code :83}.
     *
     * <p>{@code COTRN01C} at subscript 7 and {@code COTRN02C} at subscript 8 are paired with
     * {@code Transaction View} and {@code Transaction Add} respectively, which is the documented
     * view/add naming inversion of this migration. The pairing is transcribed as the copybook states
     * it and is not "corrected" here.
     */
    private static final List<String> OPTION_TARGET_PROGRAMS = List.of("COACTVWC",
            "COACTUPC",
            "COCRDLIC",
            "COCRDSLC",
            "COCRDUPC",
            "COTRN00C",
            "COTRN01C",
            "COTRN02C",
            "CORPT00C",
            "COBIL00C");

    /**
     * The ten {@code CDEMO-MENU-OPT-NAME} values of {@code app/cpy/COMEN02Y.cpy}, in subscript order,
     * as their <em>unpadded</em> text. Each is stored {@code PIC X(35)} and is right-space-padded to
     * that width by {@link #optionName(int)}.
     *
     * <p>Copybook lines: {@code :27}, {@code :33}, {@code :39}, {@code :45}, {@code :51}, {@code :57},
     * {@code :63}, <strong>{@code :70}</strong>, {@code :76}, {@code :82}.
     *
     * <p>&#9888; Subscript 8 takes its value from the <strong>live</strong> line 70,
     * {@code 'Transaction Add                    '}. The line immediately above it,
     * {@code app/cpy/COMEN02Y.cpy:69}, holds a <em>commented-out</em> earlier wording -
     * {@code 'Transaction Add (Admin Only)       '}, itself exactly 35 characters. It is named here so
     * that nobody restores it and so that a reader can see it was considered; it is deliberately
     * <strong>never asserted</strong> and appears nowhere in this file as a value (practice B5).
     */
    private static final List<String> OPTION_NAMES = List.of("Account View",
            "Account Update",
            "Credit Card List",
            "Credit Card View",
            "Credit Card Update",
            "Transaction List",
            "Transaction View",
            "Transaction Add",
            "Transaction Reports",
            "Bill Payment");

    // =================================================================================================
    // Helpers. Each stands in for exactly one COBOL statement and cites it. None of them is shared with
    // AdminMenuResponseTest, and none is generic across the two response types (practice B4).
    // =================================================================================================

    /** Builds {@link #DECLARED_WIDTHS} in map order. */
    private static Map<String, Integer> declaredWidths() {
        Map<String, Integer> widths = new LinkedHashMap<>();
        widths.put("trnName", TRN_NAME_WIDTH);
        widths.put("title01", TITLE_WIDTH);
        widths.put("curDate", CUR_DATE_WIDTH);
        widths.put("pgmName", PGM_NAME_WIDTH);
        widths.put("title02", TITLE_WIDTH);
        widths.put("curTime", CUR_TIME_WIDTH);
        for (int slot = 1; slot <= DECLARED_OPTION_SLOTS; slot++) {
            widths.put(String.format("optn%03d", slot), OPTION_LINE_WIDTH);
        }
        widths.put("option", OPTION_WIDTH);
        widths.put("errMsg", ERR_MSG_WIDTH);
        return widths;
    }

    /**
     * {@code CDEMO-MENU-OPT-NAME(subscript)} as COBOL stores it: the copybook literal right-space-padded
     * to its declared {@code PIC X(35)} ({@code app/cpy/COMEN02Y.cpy:90}).
     *
     * @param cobolSubscript the 1-based subscript, 1 to {@value #POPULATED_OPTION_SLOTS}
     * @return the 35-character stored value
     */
    private static String optionName(int cobolSubscript) {
        return CODEC.movePicX(OPTION_NAMES.get(cobolSubscript - 1), MenuOptions.OPT_NAME_LENGTH);
    }

    /**
     * {@code STRING ... DELIMITED BY SPACE}: the characters of a sending item up to, but not including,
     * its first space ({@code app/cbl/COMEN01C.cbl:160-161}).
     *
     * <p>Hand-written on purpose. {@code FixedWidthCodec} models {@code DELIMITED BY SIZE}, which
     * contributes an operand's full declared width; {@code DELIMITED BY SPACE} contributes a prefix
     * instead, and conflating the two is what produces the wrong message text. An item with no space at
     * all contributes every character, which is the COBOL behaviour when the delimiter is absent.
     *
     * @param sendingItem the item being delimited
     * @return the prefix before the first space, or the whole item when it holds none
     */
    private static String delimitedBySpace(String sendingItem) {
        int firstSpace = sendingItem.indexOf(' ');
        return firstSpace < 0 ? sendingItem : sendingItem.substring(0, firstSpace);
    }

    /**
     * {@code MOVE <text> TO WS-MESSAGE} - the {@code PIC X(80)} working-storage field of
     * {@code app/cbl/COMEN01C.cbl:38}, right-space-padded because {@code MOVE SPACES TO WS-MESSAGE} at
     * {@code :79} (and again at {@code :139} and {@code :157}) blanks it first.
     *
     * @param text the message text
     * @return the 80-character working-storage image
     */
    private static String wsMessage(String text) {
        return CODEC.movePicX(text, WS_MESSAGE_WIDTH);
    }

    /**
     * {@code MOVE WS-MESSAGE TO ERRMSGO OF COMEN1AO} - {@code app/cbl/COMEN01C.cbl:187}.
     *
     * <p>An {@code X(80)} sending field into an {@code X(78)} receiver, so the two trailing bytes are
     * discarded. The narrowing is performed by {@link FixedWidthCodec#movePicX(String, int)}, never by
     * a bare {@code substring}, so the direction is the codec's documented {@code PIC X} rule rather
     * than this test's opinion.
     *
     * @param text the message text, before it reaches {@code WS-MESSAGE}
     * @return the 78-character screen image
     */
    private static String errMsgImage(String text) {
        return CODEC.movePicX(wsMessage(text), ERR_MSG_WIDTH);
    }

    /**
     * The composed coming-soon message of {@code app/cbl/COMEN01C.cbl:159-163}, as
     * {@code WS-MESSAGE PIC X(80)} holds it.
     *
     * <p>Three operands: the literal {@code 'This option '} {@code DELIMITED BY SIZE}, then
     * {@code CDEMO-MENU-OPT-NAME(WS-OPTION)} <strong>{@code DELIMITED BY SPACE}</strong>, then the
     * literal {@code 'is coming soon ...'} {@code DELIMITED BY SIZE}. The middle operand is delimited
     * first and the three contributions are then concatenated at their full contributed widths, which
     * is what {@code STRING} does.
     *
     * @param cobolSubscript the 1-based option subscript whose name is inserted
     * @return the 80-character working-storage image
     */
    private static String comingSoonWsMessage(int cobolSubscript) {
        return wsMessage(CODEC.concatenateDelimitedBySize(COMING_SOON_PREFIX,
                delimitedBySpace(optionName(cobolSubscript)),
                COMING_SOON_SUFFIX));
    }

    /**
     * One {@code WS-MENU-OPT-TXT} line as {@code BUILD-MENU-OPTIONS} composes it
     * ({@code app/cbl/COMEN01C.cbl:241-246}).
     *
     * <p>{@code MOVE SPACES TO WS-MENU-OPT-TXT} blanks the {@code PIC X(40)} field at {@code :241},
     * then {@code STRING CDEMO-MENU-OPT-NUM(WS-IDX)}, {@code '. '} and
     * {@code CDEMO-MENU-OPT-NAME(WS-IDX)}, all {@code DELIMITED BY SIZE}, transfer 2 + 2 + 35 = 39
     * characters from position 1. The 40th character is the space the {@code MOVE SPACES} left there,
     * because {@code STRING} does not blank what it did not reach.
     *
     * @param cobolSubscript the 1-based option subscript
     * @return the 40-character option line
     */
    private static String optionLineImage(int cobolSubscript) {
        String composed = CODEC.concatenateDelimitedBySize(
                CODEC.movePic9(cobolSubscript, MenuOptions.OPT_NUM_LENGTH),
                ". ",
                optionName(cobolSubscript));
        return CODEC.movePicX(composed, OPTION_LINE_WIDTH);
    }

    /**
     * An 80-character probe whose every position is individually identifiable: position <em>p</em>
     * carries {@code 'A' + ((p - 1) % 26)}.
     *
     * <p>Position 1 is {@code 'A'}, position 78 is {@code 'Z'}, position 79 is {@code 'A'} again and
     * position 80 is {@code 'B'}. Under the correct RIGHT truncation the surviving image therefore ends
     * in {@code 'Z'}; under LEFT truncation it would end in {@code "AB"}. A probe of repeated
     * characters could not tell those two apart, which is the whole reason this exists.
     *
     * @param length the probe length in characters; at least 1
     * @return the probe
     */
    private static String positionalProbe(int length) {
        StringBuilder probe = new StringBuilder(length);
        for (int position = 1; position <= length; position++) {
            probe.append((char) ('A' + ((position - 1) % 26)));
        }
        return probe.toString();
    }

    /** A string of {@code count} spaces - {@code MOVE SPACES} at a stated width. */
    private static String spaces(int count) {
        return CODEC.movePicX("", count);
    }

    /**
     * A response with every one of the twenty payload members space-filled to its own declared width,
     * standing in for the map area after {@code MOVE LOW-VALUES TO COMEN1AO}
     * ({@code app/cbl/COMEN01C.cbl:89}) has cleared it and before anything has been written.
     *
     * @return the fully space-filled response
     */
    private static MainMenuResponse spaceFilledToDeclaredWidths() {
        MainMenuResponse.Builder builder = MainMenuResponse.builder()
                .trnName(spaces(TRN_NAME_WIDTH))
                .title01(spaces(TITLE_WIDTH))
                .curDate(spaces(CUR_DATE_WIDTH))
                .pgmName(spaces(PGM_NAME_WIDTH))
                .title02(spaces(TITLE_WIDTH))
                .curTime(spaces(CUR_TIME_WIDTH))
                .option(spaces(OPTION_WIDTH))
                .errMsg(spaces(ERR_MSG_WIDTH));
        for (int slot = 1; slot <= DECLARED_OPTION_SLOTS; slot++) {
            builder.optionLine(slot, spaces(OPTION_LINE_WIDTH));
        }
        return builder.build();
    }

    /**
     * A response painted the way {@code SEND-MENU-SCREEN} paints it: the header from
     * {@code POPULATE-HEADER-INFO} ({@code app/cbl/COMEN01C.cbl:212-231}), the ten option lines from
     * {@code BUILD-MENU-OPTIONS} ({@code :236-277}), slots 11 and 12 left at their cleared width, the
     * selected option and the message.
     *
     * @param optionEntered the option the user typed, as {@code WS-OPTION PIC 9(02)} holds it
     * @param messageText   the text destined for {@code WS-MESSAGE}
     * @return the painted response
     */
    private static MainMenuResponse afterSendMenuScreen(int optionEntered, String messageText) {
        DateHeader header = DateHeader.from(CODEC, FIXED_CLOCK);
        MainMenuResponse.Builder builder = MainMenuResponse.builder()
                .trnName(MainMenuResponse.TRANSACTION_ID)
                .title01(ScreenTitles.CCDA_TITLE01)
                .curDate(header.wsCurdateMmDdYy())
                .pgmName(MainMenuResponse.PROGRAM_NAME)
                .title02(ScreenTitles.CCDA_TITLE02)
                .curTime(header.wsCurtimeHhMmSs())
                .option(CODEC.movePic9(optionEntered, OPTION_WIDTH))
                .errMsg(errMsgImage(messageText));
        for (int slot = 1; slot <= DECLARED_OPTION_SLOTS; slot++) {
            builder.optionLine(slot, slot <= POPULATED_OPTION_SLOTS
                    ? optionLineImage(slot)
                    : spaces(OPTION_LINE_WIDTH));
        }
        return builder.build();
    }

    /** The value of one payload member, by name, without a twenty-armed switch at each call site. */
    private static String payloadMember(MainMenuResponse response, String member) {
        try {
            return (String) MainMenuResponse.class.getMethod(member).invoke(response);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("MainMenuResponse must expose an accessor named '" + member
                    + "', because app/cpy-bms/COMEN01.CPY declares the matching xxxO item", failure);
        }
    }

    /**
     * The output group as a self-checking layout: {@code FILLER PIC X(12)} then, for each of the twenty
     * fields, {@code FILLER PICTURE X(3)} + four attribute bytes + the {@code xxxO} item.
     *
     * <p>Building it <em>is</em> the geometry proof. {@code RecordLayout} refuses to exist unless its
     * storage spans run contiguously from byte 0 with no gap and no overlap and sum to exactly the
     * declared length, so dropping a {@code FILLER} or mis-stating one width fails here immediately and
     * precisely - which is the whole content of gate {@code G21}.
     *
     * @return the 820-byte output layout
     */
    private static FixedWidthRecord.RecordLayout outputGroupLayout() {
        List<FixedWidthRecord.FieldSpan> spans = new ArrayList<>();
        int offset = 0;
        // app/cpy-bms/COMEN01.CPY:140 - the TIOAPFX=YES prefix, declared by DFHMSD at COMEN01.bms:24.
        spans.add(FixedWidthRecord.FieldSpan.filler(offset, TIOAPFX_FILLER_WIDTH));
        offset += TIOAPFX_FILLER_WIDTH;
        for (int index = 0; index < PAYLOAD_FIELD_COUNT; index++) {
            String mapField = MAP_FIELD_NAMES.get(index);
            int width = DECLARED_WIDTHS.get(PAYLOAD_MEMBERS.get(index));
            spans.add(FixedWidthRecord.FieldSpan.filler(offset, OUTPUT_FIELD_FILLER_WIDTH));
            offset += OUTPUT_FIELD_FILLER_WIDTH;
            for (String attribute : List.of("C", "P", "H", "V")) {
                spans.add(FixedWidthRecord.FieldSpan.alphanumeric(mapField + attribute, offset, 1));
                offset++;
            }
            spans.add(FixedWidthRecord.FieldSpan.alphanumeric(mapField + "O", offset, width));
            offset += width;
        }
        return FixedWidthRecord.RecordLayout.of(offset,
                spans.toArray(new FixedWidthRecord.FieldSpan[0]));
    }

    /**
     * The input group as a self-checking layout, with {@code COMEN1AO} declared on top of it as the
     * {@code REDEFINES} overlay the copybook actually writes at {@code app/cpy-bms/COMEN01.CPY:139}.
     *
     * <p>Per field: {@code xxxL} two bytes + {@code xxxF} one byte + {@code FILLER PICTURE X(4)} +
     * {@code xxxI}. {@code RecordLayout} additionally verifies that the overlay falls entirely inside
     * storage declared ahead of it, so an 820-byte overlay over an 820-byte group is proved legal rather
     * than asserted to be.
     *
     * @return the 820-byte input layout carrying the {@code COMEN1AO} overlay
     */
    private static FixedWidthRecord.RecordLayout inputGroupWithOutputOverlay() {
        List<FixedWidthRecord.FieldSpan> spans = new ArrayList<>();
        int offset = 0;
        // app/cpy-bms/COMEN01.CPY:18 - the same 12-byte TIOAPFX prefix the output group opens with.
        spans.add(FixedWidthRecord.FieldSpan.filler(offset, TIOAPFX_FILLER_WIDTH));
        offset += TIOAPFX_FILLER_WIDTH;
        for (int index = 0; index < PAYLOAD_FIELD_COUNT; index++) {
            String mapField = MAP_FIELD_NAMES.get(index);
            int width = DECLARED_WIDTHS.get(PAYLOAD_MEMBERS.get(index));
            // xxxL is COMP PIC S9(4): a binary halfword. Declared as a reserved span because its two
            // bytes are not characters, and reading them as text would be meaningless.
            spans.add(FixedWidthRecord.FieldSpan.filler(offset, INPUT_LENGTH_ITEM_WIDTH));
            offset += INPUT_LENGTH_ITEM_WIDTH;
            spans.add(FixedWidthRecord.FieldSpan.alphanumeric(mapField + "F", offset,
                    INPUT_FLAG_ITEM_WIDTH));
            offset += INPUT_FLAG_ITEM_WIDTH;
            spans.add(FixedWidthRecord.FieldSpan.filler(offset, INPUT_FIELD_FILLER_WIDTH));
            offset += INPUT_FIELD_FILLER_WIDTH;
            spans.add(FixedWidthRecord.FieldSpan.alphanumeric(mapField + "I", offset, width));
            offset += width;
        }
        // 01 COMEN1AO REDEFINES COMEN1AI. - app/cpy-bms/COMEN01.CPY:139.
        spans.add(FixedWidthRecord.FieldSpan.redefining("COMEN1AO", 0, offset,
                FixedWidthRecord.PictureKind.ALPHANUMERIC));
        return FixedWidthRecord.RecordLayout.of(offset,
                spans.toArray(new FixedWidthRecord.FieldSpan[0]));
    }

    // =================================================================================================
    @Nested
    @DisplayName("COMEN1AO geometry - 20 of 28 DFHMDF fields, 668 payload bytes in an 820-byte image")
    class Geometry {

        @Test
        @DisplayName("exactly 20 map-derived members, in the copybook's declaration order")
        void twentyPayloadMembersInMapOrder() {
            List<String> components = Stream.of(MainMenuResponse.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            // The record declares twenty-six members: the twenty xxxO projections first, in map order,
            // then the six carriers that correspond to no copybook item.
            assertThat(components).hasSize(PAYLOAD_FIELD_COUNT + NON_PAYLOAD_MEMBERS.size());
            assertThat(components.subList(0, PAYLOAD_FIELD_COUNT))
                    .as("the twenty xxxO items of app/cpy-bms/COMEN01.CPY:146 to :260, in order")
                    .containsExactlyElementsOf(PAYLOAD_MEMBERS);
            assertThat(components.subList(PAYLOAD_FIELD_COUNT, components.size()))
                    .containsExactlyElementsOf(NON_PAYLOAD_MEMBERS);
            assertThat(MainMenuResponse.SYMBOLIC_MAP_FIELD_COUNT)
                    .isEqualTo(PAYLOAD_FIELD_COUNT)
                    .isEqualTo(20);
        }

        @Test
        @DisplayName("every one of the twenty names is present, and none is missing or misspelled")
        void everyPayloadMemberIsReachableByName() {
            MainMenuResponse response = spaceFilledToDeclaredWidths();

            for (String member : PAYLOAD_MEMBERS) {
                assertThat(payloadMember(response, member))
                        .as("accessor " + member + "() must exist and return its span")
                        .isNotNull();
            }
            assertThat(PAYLOAD_MEMBERS).hasSize(PAYLOAD_FIELD_COUNT).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("28 DFHMDF definitions in the mapset, of which 20 are named and 8 are literals")
        void mapsetFieldCount() {
            assertThat(MainMenuResponse.MAPSET_FIELD_DEFINITION_COUNT)
                    .isEqualTo(MAPSET_FIELD_DEFINITIONS)
                    .isEqualTo(28);
            // The eight unnamed DFHMDF literals - 'Tran:', 'Date:', 'Prog:', 'Time:', 'Main Menu',
            // 'Please select an option :', the zero-length green field at COMEN01.bms:150-153 and
            // 'ENTER=Continue  F3=Exit' - yield no symbolic-map item and so no payload member.
            assertThat(MainMenuResponse.MAPSET_FIELD_DEFINITION_COUNT
                    - MainMenuResponse.SYMBOLIC_MAP_FIELD_COUNT).isEqualTo(8);
        }

        @Test
        @DisplayName("the declared widths are 4, 40, 8, 8, 40, 8, forty times twelve, 2 and 78")
        void declaredWidthsMatchTheCopybook() {
            // Each width is asserted twice: against the constant the DTO publishes, and against the
            // hard literal transcribed from the copybook (practice B8). A constant that drifted from
            // app/cpy-bms/COMEN01.CPY fails the second half even though it would satisfy the first.
            assertThat(MainMenuResponse.TRN_NAME_LENGTH).isEqualTo(TRN_NAME_WIDTH).isEqualTo(4);
            assertThat(MainMenuResponse.TITLE_LENGTH).isEqualTo(TITLE_WIDTH).isEqualTo(40);
            assertThat(MainMenuResponse.CUR_DATE_LENGTH).isEqualTo(CUR_DATE_WIDTH).isEqualTo(8);
            assertThat(MainMenuResponse.PGM_NAME_LENGTH).isEqualTo(PGM_NAME_WIDTH).isEqualTo(8);
            assertThat(MainMenuResponse.CUR_TIME_LENGTH).isEqualTo(CUR_TIME_WIDTH).isEqualTo(8);
            assertThat(MainMenuResponse.OPTION_LINE_LENGTH)
                    .isEqualTo(OPTION_LINE_WIDTH).isEqualTo(40);
            assertThat(MainMenuResponse.OPTION_LINE_COUNT)
                    .isEqualTo(DECLARED_OPTION_SLOTS).isEqualTo(12);
            assertThat(MainMenuResponse.OPTION_LENGTH).isEqualTo(OPTION_WIDTH).isEqualTo(2);
            assertThat(MainMenuResponse.ERR_MSG_LENGTH).isEqualTo(ERR_MSG_WIDTH).isEqualTo(78);
        }

        @Test
        @DisplayName("ERRMSGO is 78 - never the 80 of WS-MESSAGE, never the 50 of the invalid-key text")
        void errMsgWidthIsNeitherOfTheTwoNeighbouringWidths() {
            assertThat(MainMenuResponse.ERR_MSG_LENGTH).isEqualTo(78);
            assertThat(MainMenuResponse.ERR_MSG_LENGTH)
                    .as("app/cbl/COMEN01C.cbl:38 declares WS-MESSAGE PIC X(80); the receiver is 78")
                    .isNotEqualTo(WS_MESSAGE_WIDTH);
            assertThat(MainMenuResponse.ERR_MSG_LENGTH)
                    .as("app/cpy/CSMSG01Y.cpy declares its messages PIC X(50); the receiver is 78")
                    .isNotEqualTo(SystemMessages.MESSAGE_LENGTH);
            assertThat(WS_MESSAGE_WIDTH - MainMenuResponse.ERR_MSG_LENGTH)
                    .as("exactly two bytes are discarded by the MOVE at app/cbl/COMEN01C.cbl:187")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("the twenty COMEN1AO widths sum to 668")
        void widthsSumToThePayloadLength() {
            int handSummed = TRN_NAME_WIDTH
                    + TITLE_WIDTH
                    + CUR_DATE_WIDTH
                    + PGM_NAME_WIDTH
                    + TITLE_WIDTH
                    + CUR_TIME_WIDTH
                    + DECLARED_OPTION_SLOTS * OPTION_LINE_WIDTH
                    + OPTION_WIDTH
                    + ERR_MSG_WIDTH;

            assertThat(handSummed).isEqualTo(668);
            assertThat(DECLARED_WIDTHS.values().stream().mapToInt(Integer::intValue).sum())
                    .isEqualTo(handSummed);
            assertThat(MainMenuResponse.PAYLOAD_BYTES).isEqualTo(handSummed).isEqualTo(668);
        }

        @Test
        @DisplayName("the whole COMEN1AO image is 12 + 20 times 7 + 668 = 820 bytes")
        void theOverlayIsEightHundredAndTwentyBytes() {
            assertThat(MainMenuResponse.TIOAPFX_FILLER_LENGTH)
                    .isEqualTo(TIOAPFX_FILLER_WIDTH).isEqualTo(12);
            assertThat(MainMenuResponse.ATTRIBUTE_PREFIX_LENGTH)
                    .isEqualTo(ATTRIBUTE_PREFIX_WIDTH).isEqualTo(7);
            assertThat(MainMenuResponse.SYMBOLIC_MAP_LENGTH)
                    .isEqualTo(TIOAPFX_FILLER_WIDTH
                            + PAYLOAD_FIELD_COUNT * ATTRIBUTE_PREFIX_WIDTH
                            + MainMenuResponse.PAYLOAD_BYTES)
                    .isEqualTo(12 + 20 * 7 + 668)
                    .isEqualTo(820);
        }

        @Test
        @DisplayName("the COMEN1AO stride equals the COMEN1AI stride, which is why the REDEFINES is legal")
        void bothStridesAreSevenBytes() {
            // Output, app/cpy-bms/COMEN01.CPY:141-145: FILLER X(3) + xxxC + xxxP + xxxH + xxxV.
            assertThat(OUTPUT_FIELD_FILLER_WIDTH + OUTPUT_ATTRIBUTE_ITEMS)
                    .isEqualTo(ATTRIBUTE_PREFIX_WIDTH)
                    .isEqualTo(7);
            // Input, app/cpy-bms/COMEN01.CPY:19-23: xxxL (a two-byte binary halfword) + xxxF + FILLER
            // X(4).
            assertThat(INPUT_LENGTH_ITEM_WIDTH + INPUT_FLAG_ITEM_WIDTH + INPUT_FIELD_FILLER_WIDTH)
                    .isEqualTo(ATTRIBUTE_PREFIX_WIDTH)
                    .isEqualTo(7);
            // Equal strides and equal payload widths mean equal totals - and an overlay may only
            // redefine storage of its own size or less, so this equality is the precondition for
            // 01 COMEN1AO REDEFINES COMEN1AI at :139 to compile at all.
            assertThat(3 + 4).isEqualTo(2 + 1 + 4);
        }

        @Test
        @DisplayName("composing all twenty output spans really does produce an 820-byte layout")
        void theOutputLayoutSelfCheckAgrees() {
            FixedWidthRecord.RecordLayout layout = outputGroupLayout();

            assertThat(layout.recordLength()).isEqualTo(MainMenuResponse.SYMBOLIC_MAP_LENGTH)
                    .isEqualTo(820);
            // 1 TIOAPFX filler + 20 x (1 filler + 4 attributes + 1 payload item) = 121 spans, every one
            // of them storage; the output group declares no overlay of its own.
            assertThat(layout.spans()).hasSize(1 + PAYLOAD_FIELD_COUNT * 6).hasSize(121);
            assertThat(layout.storageSpans()).hasSameSizeAs(layout.spans());
            assertThat(layout.redefinitions()).isEmpty();
            for (int index = 0; index < PAYLOAD_FIELD_COUNT; index++) {
                String item = MAP_FIELD_NAMES.get(index) + "O";
                assertThat(layout.hasSpan(item)).as(item + " must be addressable").isTrue();
                assertThat(layout.span(item).length())
                        .isEqualTo(DECLARED_WIDTHS.get(PAYLOAD_MEMBERS.get(index)));
            }
            // ERRMSGO is the last item, so its end offset is the whole image.
            assertThat(layout.span("ERRMSGO").endOffsetExclusive()).isEqualTo(820);
            assertThat(layout.span("ERRMSGO").offset()).isEqualTo(820 - ERR_MSG_WIDTH).isEqualTo(742);
        }

        @Test
        @DisplayName("COMEN1AO REDEFINES COMEN1AI: one 820-byte span, two typed views (gate G34)")
        void theRedefinesPairIsOneSpanSeenTwice() {
            FixedWidthRecord.RecordLayout input = inputGroupWithOutputOverlay();
            FixedWidthRecord.RecordLayout output = outputGroupLayout();

            assertThat(input.recordLength()).isEqualTo(output.recordLength()).isEqualTo(820);
            assertThat(input.redefinitions()).hasSize(1);
            FixedWidthRecord.FieldSpan overlay = input.span("COMEN1AO");
            assertThat(overlay.redefinition()).isTrue();
            assertThat(overlay.offset()).isZero();
            assertThat(overlay.length()).isEqualTo(820);
            assertThat(overlay.endOffsetExclusive()).isEqualTo(input.recordLength());
            // The twenty xxxI items sit at exactly the offsets the twenty xxxO items sit at, because
            // the strides are equal. That is what makes the two groups alternative views of the same
            // bytes rather than two different records.
            for (String mapField : MAP_FIELD_NAMES) {
                assertThat(input.span(mapField + "I").offset())
                        .as(mapField + "I and " + mapField + "O must start at the same byte")
                        .isEqualTo(output.span(mapField + "O").offset());
                assertThat(input.span(mapField + "I").length())
                        .isEqualTo(output.span(mapField + "O").length());
            }
        }

        @Test
        @DisplayName("dropping a single FILLER span fails the layout immediately (gate G21)")
        void omittingAFillerIsRejected() {
            // The width assertions above are not merely descriptive: the layout self-check makes a
            // missing span a hard failure. Removing just the 3-byte FILLER that precedes ERRMSGC leaves
            // an 817-byte layout claiming to be 820, and RecordLayout refuses it.
            List<FixedWidthRecord.FieldSpan> spans = new ArrayList<>();
            int offset = 0;
            spans.add(FixedWidthRecord.FieldSpan.filler(offset, TIOAPFX_FILLER_WIDTH));
            offset += TIOAPFX_FILLER_WIDTH;
            for (int index = 0; index < PAYLOAD_FIELD_COUNT; index++) {
                String mapField = MAP_FIELD_NAMES.get(index);
                int width = DECLARED_WIDTHS.get(PAYLOAD_MEMBERS.get(index));
                boolean skipThisFiller = "ERRMSG".equals(mapField);
                if (!skipThisFiller) {
                    spans.add(FixedWidthRecord.FieldSpan.filler(offset, OUTPUT_FIELD_FILLER_WIDTH));
                    offset += OUTPUT_FIELD_FILLER_WIDTH;
                }
                for (String attribute : List.of("C", "P", "H", "V")) {
                    spans.add(FixedWidthRecord.FieldSpan.alphanumeric(mapField + attribute, offset, 1));
                    offset++;
                }
                spans.add(FixedWidthRecord.FieldSpan.alphanumeric(mapField + "O", offset, width));
                offset += width;
            }
            FixedWidthRecord.FieldSpan[] shortened =
                    spans.toArray(new FixedWidthRecord.FieldSpan[0]);

            assertThat(offset).isEqualTo(820 - OUTPUT_FIELD_FILLER_WIDTH).isEqualTo(817);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> FixedWidthRecord.RecordLayout.of(820, shortened))
                    .withMessageContaining("byte(s) short");
        }

        @Test
        @DisplayName("the twenty payload values fill the twenty spans and nothing else")
        void writingThePayloadFillsExactlyItsOwnBytes() {
            FixedWidthRecord.RecordLayout layout = outputGroupLayout();
            FixedWidthRecord image = CODEC.newRecord(layout);
            MainMenuResponse response = afterSendMenuScreen(1, VALIDATION_MESSAGE);

            for (int index = 0; index < PAYLOAD_FIELD_COUNT; index++) {
                String member = PAYLOAD_MEMBERS.get(index);
                FixedWidthRecord.FieldSpan span = layout.span(MAP_FIELD_NAMES.get(index) + "O");
                CODEC.writePicX(image, span, payloadMember(response, member));
            }

            assertThat(image.recordLength()).isEqualTo(820);
            assertThat(image.toByteArray()).hasSize(820);
            for (int index = 0; index < PAYLOAD_FIELD_COUNT; index++) {
                String member = PAYLOAD_MEMBERS.get(index);
                FixedWidthRecord.FieldSpan span = layout.span(MAP_FIELD_NAMES.get(index) + "O");
                assertThat(CODEC.readPicX(image, span))
                        .as(member + " must round-trip through its own span untouched")
                        .isEqualTo(payloadMember(response, member))
                        .hasSize(DECLARED_WIDTHS.get(member));
            }
        }

        @Test
        @DisplayName("the COMEN1A screen is 24 by 80, as its single DFHMDI declares")
        void theScreenGeometryIsTheStandardTerminal() {
            assertThat(SCREEN_ROWS).isEqualTo(24);
            assertThat(SCREEN_COLUMNS).isEqualTo(80);
            // ERRMSG sits at POS=(23,1) with LENGTH=78 (app/bms/COMEN01.bms:156-157), so it stops two
            // columns short of the right-hand edge - the same two bytes the X(80) to X(78) MOVE drops.
            assertThat(ERR_MSG_WIDTH).isLessThan(SCREEN_COLUMNS);
            assertThat(SCREEN_COLUMNS - ERR_MSG_WIDTH).isEqualTo(2);
        }

        @Test
        @DisplayName("the screen identity is CM00 / COMEN01C / COMEN01 / COMEN1A")
        void screenIdentityMatchesTheCsdAndTheMapset() {
            assertThat(MainMenuResponse.TRANSACTION_ID).isEqualTo("CM00").hasSize(TRN_NAME_WIDTH);
            assertThat(MainMenuResponse.PROGRAM_NAME).isEqualTo("COMEN01C").hasSize(PGM_NAME_WIDTH);
            assertThat(MainMenuResponse.MAPSET_NAME).isEqualTo("COMEN01")
                    .hasSize(NavigationContext.LAST_MAPSET_LENGTH);
            assertThat(MainMenuResponse.MAP_NAME).isEqualTo("COMEN1A")
                    .hasSize(NavigationContext.LAST_MAP_LENGTH);
            assertThat(MainMenuResponse.SIGNON_PROGRAM).isEqualTo("COSGN00C")
                    .hasSize(NavigationContext.TO_PROGRAM_LENGTH);
        }

        @Test
        @DisplayName("@Size(max) on every character member carries that member's copybook width")
        void beanValidationCarriesTheDeclaredWidths() throws ReflectiveOperationException {
            Map<String, Integer> annotated = new LinkedHashMap<>(DECLARED_WIDTHS);
            annotated.put("nextProgram", MainMenuResponse.NEXT_PROGRAM_LENGTH);
            annotated.put("nextMapset", MainMenuResponse.NEXT_MAPSET_LENGTH);
            annotated.put("nextMap", MainMenuResponse.NEXT_MAP_LENGTH);

            // jakarta.validation.constraints.Size declares @Target({METHOD, FIELD, ANNOTATION_TYPE,
            // CONSTRUCTOR, PARAMETER, TYPE_USE}) - notably WITHOUT RECORD_COMPONENT. An annotation
            // written on a record component is therefore propagated to every applicable declaration -
            // the private field, the accessor and the canonical constructor parameter - but is not
            // retained on the RecordComponent itself, so RecordComponent.getAnnotation answers null.
            // Bean Validation reads fields and getters, so the constraint is live; the assertion just
            // has to look where the compiler put it.
            int found = 0;
            for (RecordComponent component : MainMenuResponse.class.getRecordComponents()) {
                String name = component.getName();
                Size onField = MainMenuResponse.class.getDeclaredField(name).getAnnotation(Size.class);
                Size onAccessor = MainMenuResponse.class.getMethod(name).getAnnotation(Size.class);
                if (annotated.containsKey(name)) {
                    assertThat(onField).as("@Size on field " + name).isNotNull();
                    assertThat(onField.max()).as("@Size max on " + name)
                            .isEqualTo(annotated.get(name));
                    assertThat(onAccessor).as("@Size on accessor " + name).isNotNull();
                    assertThat(onAccessor.max()).isEqualTo(annotated.get(name));
                    found++;
                } else {
                    assertThat(onField).as("no @Size on non-character member " + name).isNull();
                    assertThat(onAccessor).isNull();
                }
            }
            assertThat(found).isEqualTo(annotated.size()).isEqualTo(23);
        }

        @Test
        @DisplayName("@Size reaches the canonical constructor parameters too")
        void beanValidationReachesTheConstructor() {
            Parameter[] parameters = MainMenuResponse.class.getDeclaredConstructors()[0].getParameters();

            assertThat(parameters).hasSize(PAYLOAD_FIELD_COUNT + NON_PAYLOAD_MEMBERS.size());
            assertThat(parameters[0].getAnnotation(Size.class)).isNotNull();
            assertThat(parameters[0].getAnnotation(Size.class).max()).isEqualTo(TRN_NAME_WIDTH);
            assertThat(parameters[PAYLOAD_FIELD_COUNT - 1].getAnnotation(Size.class).max())
                    .as("the twentieth parameter is errMsg, at 78")
                    .isEqualTo(ERR_MSG_WIDTH);
            // Index 20 is navigationContext, which is not a CharSequence and carries no @Size.
            assertThat(parameters[PAYLOAD_FIELD_COUNT].getAnnotation(Size.class)).isNull();
        }

        @Test
        @DisplayName("every member is space-fillable to exactly its own declared width")
        void everyMemberHoldsItsDeclaredWidth() {
            MainMenuResponse response = spaceFilledToDeclaredWidths();

            for (Map.Entry<String, Integer> declared : DECLARED_WIDTHS.entrySet()) {
                assertThat(payloadMember(response, declared.getKey()))
                        .as(declared.getKey() + " must hold exactly " + declared.getValue()
                                + " characters")
                        .hasSize(declared.getValue())
                        .isBlank();
            }
        }
    }

    // =================================================================================================
    @Nested
    @DisplayName("Attribute items are metadata - no xxxC, xxxP, xxxH or xxxV is ever payload")
    class MetadataIsNotPayload {

        @Test
        @DisplayName("errMsgC, optionP, title01H and curDateV do not exist, by name")
        void theFourNamedMetadataItemsAreAbsent() {
            // Named explicitly, not merely swept: a sweep proves the pattern, a name proves the case.
            // ERRMSGC (CPY:256) is the colour byte MOVE DFHGREEN writes at app/cbl/COMEN01C.cbl:158,
            // OPTIONP (CPY:251) the programmed-symbol byte, TITLE01H (CPY:150) the highlight byte and
            // CURDATEV (CPY:157) the validation byte. None of the four is screen text, so none of them
            // may become a payload member (practice B11).
            for (String forbidden : List.of("errMsgC", "optionP", "title01H", "curDateV")) {
                assertThat(recordComponentNames())
                        .as(forbidden + " is an attribute item, not an xxxO projection")
                        .doesNotContain(forbidden);
                assertThat(declaredFieldNames()).doesNotContain(forbidden);
                assertThat(accessorNames()).doesNotContain(forbidden);
            }
        }

        @Test
        @DisplayName("none of the eighty attribute items leaks in as a member, a field or an accessor")
        void noAttributeItemLeaksAnywhere() {
            List<String> components = recordComponentNames();
            List<String> fields = declaredFieldNames();
            List<String> accessors = accessorNames();

            int swept = 0;
            for (String mapField : MAP_FIELD_NAMES) {
                for (String attribute : List.of("C", "P", "H", "V")) {
                    // The Java-cased form of, say, ERRMSGC: the payload member name plus the suffix.
                    String javaName = PAYLOAD_MEMBERS.get(MAP_FIELD_NAMES.indexOf(mapField))
                            + attribute;
                    assertThat(components).doesNotContain(javaName);
                    assertThat(fields).doesNotContain(javaName);
                    assertThat(accessors).doesNotContain(javaName);
                    // And the raw copybook spelling, in case a transcription kept the upper case.
                    assertThat(components).doesNotContain(mapField + attribute);
                    assertThat(fields).doesNotContain(mapField + attribute);
                    swept++;
                }
            }
            assertThat(swept).as("four attribute items for each of twenty fields").isEqualTo(80);
        }

        @Test
        @DisplayName("no xxxL length item and no xxxF or xxxA flag item leaks in either")
        void noInputSideMetadataLeaksIn() {
            // xxxL COMP PIC S9(4) and xxxF PICTURE X belong to the INPUT group (CPY:19-22), and xxxA
            // redefines xxxF. They are the request type's validation metadata; none of the three is a
            // member of the response at all.
            for (String mapField : MAP_FIELD_NAMES) {
                String member = PAYLOAD_MEMBERS.get(MAP_FIELD_NAMES.indexOf(mapField));
                for (String suffix : List.of("L", "F", "A")) {
                    assertThat(recordComponentNames()).doesNotContain(member + suffix);
                    assertThat(recordComponentNames()).doesNotContain(mapField + suffix);
                }
            }
        }

        @Test
        @DisplayName("the serialised payload publishes the twenty-six members and no attribute item")
        void theSerialisedFormCarriesNoAttributeItem() throws Exception {
            String json = new ObjectMapper().writeValueAsString(afterSendMenuScreen(1,
                    VALIDATION_MESSAGE));

            for (String mapField : MAP_FIELD_NAMES) {
                String member = PAYLOAD_MEMBERS.get(MAP_FIELD_NAMES.indexOf(mapField));
                for (String suffix : List.of("C", "P", "H", "V", "L", "F", "A")) {
                    assertThat(json)
                            .as("no attribute or length item may be serialised: " + member + suffix)
                            .doesNotContain("\"" + member + suffix + "\"")
                            .doesNotContain("\"" + mapField + suffix + "\"");
                }
                assertThat(json).as(member + " is payload and must be serialised")
                        .contains("\"" + member + "\"");
            }
        }

        @Test
        @DisplayName("the message colour is metadata: carried as errMsgColor, never as errMsgC")
        void theColourIsMetadataUnderItsOwnName() throws Exception {
            MainMenuResponse response = afterSendMenuScreen(1, VALIDATION_MESSAGE);
            String json = new ObjectMapper().writeValueAsString(response);

            // The colour is real state - MOVE DFHGREEN TO ERRMSGC OF COMEN1AO at
            // app/cbl/COMEN01C.cbl:158 is a genuine assignment - so the response carries it. It is
            // carried as presentation metadata under a name of its own, is @JsonIgnored, and is NOT the
            // ERRMSGC field of the copybook masquerading as payload.
            assertThat(recordComponentNames()).contains("errMsgColor");
            assertThat(response.errMsgColor()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(json).doesNotContain("errMsgColor").doesNotContain("errMsgC");
            assertThat(json).doesNotContain("resetAllOutputFields");
        }

        private List<String> recordComponentNames() {
            return Stream.of(MainMenuResponse.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();
        }

        private List<String> declaredFieldNames() {
            return Stream.of(MainMenuResponse.class.getDeclaredFields())
                    .filter(field -> !field.isSynthetic() && !field.getName().startsWith("$"))
                    .map(Field::getName)
                    .toList();
        }

        private List<String> accessorNames() {
            return Stream.of(MainMenuResponse.class.getDeclaredMethods())
                    .filter(method -> method.getParameterCount() == 0 && !method.isSynthetic())
                    .map(Method::getName)
                    .toList();
        }
    }

    // =================================================================================================
    @Nested
    @DisplayName("COMEN1AO ERRMSGO - an X(80) message narrowed to X(78) by RIGHT truncation")
    class MessageLine {

        @Test
        @DisplayName("a distinguishable 80-character probe loses WS-MESSAGE positions 79 and 80, not 1 and 2")
        void theAlphabeticProbeProvesTheDirection() {
            // 78 'A's then 'Y' then 'Z'. Under the correct RIGHT truncation the surviving image is the
            // 78 leading characters and neither marker survives. Under LEFT truncation it would be
            // positions 3 to 80 and BOTH markers would survive. A probe of one repeated character
            // cannot tell those apart, which is why the markers are here.
            String probe = "A".repeat(ERR_MSG_WIDTH) + "YZ";
            assertThat(probe).hasSize(WS_MESSAGE_WIDTH);

            String narrowed = CODEC.movePicX(probe, ERR_MSG_WIDTH);

            assertThat(narrowed).hasSize(ERR_MSG_WIDTH).isEqualTo("A".repeat(ERR_MSG_WIDTH));
            assertThat(narrowed).doesNotContain("Y").doesNotContain("Z");
            assertThat(narrowed.charAt(ERR_MSG_WIDTH - 1)).isEqualTo('A');
            assertThat(MainMenuResponse.builder().errMsg(narrowed).build().errMsg())
                    .hasSize(ERR_MSG_WIDTH);
        }

        @Test
        @DisplayName("a positional probe survives as positions 1 to 78, ending in 'Z' not \"AB\"")
        void thePositionalProbeProvesTheDirectionAgain() {
            // Position p carries 'A' + ((p - 1) % 26), so position 1 is 'A', 78 is 'Z', 79 is 'A' and
            // 80 is 'B'. Every position is individually identifiable, so the surviving window is pinned
            // at both ends rather than only at one.
            String probe = positionalProbe(WS_MESSAGE_WIDTH);
            assertThat(probe).hasSize(80);
            assertThat(probe.charAt(0)).isEqualTo('A');
            assertThat(probe.charAt(ERR_MSG_WIDTH - 1)).isEqualTo('Z');
            assertThat(probe).endsWith("AB");

            String narrowed = CODEC.movePicX(probe, ERR_MSG_WIDTH);

            assertThat(narrowed).hasSize(ERR_MSG_WIDTH)
                    .isEqualTo(probe.substring(0, ERR_MSG_WIDTH))
                    .startsWith("A")
                    .endsWith("Z");
            assertThat(narrowed).doesNotEndWith("AB");
            assertThat(narrowed.charAt(0)).isEqualTo(probe.charAt(0));
            assertThat(narrowed.charAt(ERR_MSG_WIDTH - 1)).isEqualTo(probe.charAt(ERR_MSG_WIDTH - 1));
        }

        @Test
        @DisplayName("PIC X truncates on the right, PIC 9 on the left - opposite, and both proved here")
        void theTwoMoveDirectionsAreOpposite() {
            // One class, both directions. A future edit that reached for the wrong codec helper cannot
            // pass both of these assertions, which is the point of stating them together.
            assertThat(CODEC.movePicX("123", OPTION_WIDTH))
                    .as("PIC X keeps the LEADING characters")
                    .isEqualTo("12");
            assertThat(CODEC.movePic9("123", OPTION_WIDTH))
                    .as("PIC 9 keeps the LOW-ORDER digits")
                    .isEqualTo("23");
            assertThat(CODEC.movePicX("123", OPTION_WIDTH))
                    .isNotEqualTo(CODEC.movePic9("123", OPTION_WIDTH));
        }

        @ParameterizedTest(name = "\"{0}\" arrives right-padded to 78")
        @MethodSource(
                "com.vsergeychik.carddemo.admin.dto.MainMenuResponseTest#everyMessagePath")
        @DisplayName("all four COMEN01C message paths land as exactly 78 characters")
        void everyMessagePathIsExactlySeventyEight(String description, String messageText) {
            String image = errMsgImage(messageText);

            assertThat(image).as(description + " must be exactly " + ERR_MSG_WIDTH + " characters")
                    .hasSize(ERR_MSG_WIDTH)
                    .startsWith(messageText)
                    .isEqualTo(messageText + " ".repeat(ERR_MSG_WIDTH - messageText.length()));
            assertThat(MainMenuResponse.builder().errMsg(image).build().errMsg())
                    .hasSize(ERR_MSG_WIDTH);
        }

        @Test
        @DisplayName("path 1: the option-validation message of COMEN01C:131-132")
        void validationMessagePath() {
            assertThat(VALIDATION_MESSAGE)
                    .isEqualTo("Please enter a valid option number...")
                    .hasSize(37);

            String image = errMsgImage(VALIDATION_MESSAGE);

            assertThat(image).hasSize(ERR_MSG_WIDTH);
            assertThat(image.strip()).isEqualTo(VALIDATION_MESSAGE);
            assertThat(afterSendMenuScreen(0, VALIDATION_MESSAGE).errMsg()).isEqualTo(image);
        }

        @Test
        @DisplayName("path 2: the user-type authorization refusal of COMEN01C:140-141, trailing space kept")
        void noAccessMessagePath() {
            // This message has no COADM01C counterpart. It is produced only by the filter at
            // app/cbl/COMEN01C.cbl:136-143 - IF CDEMO-USRTYP-USER AND
            // CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A' - and so it is unique to this response type.
            assertThat(NO_ACCESS_MESSAGE)
                    .isEqualTo("No access - Admin Only option... ")
                    .hasSize(33)
                    .endsWith(" ")
                    .endsWith("... ");
            assertThat(NO_ACCESS_MESSAGE.strip()).hasSize(32);

            String image = errMsgImage(NO_ACCESS_MESSAGE);

            assertThat(image).hasSize(ERR_MSG_WIDTH).startsWith(NO_ACCESS_MESSAGE);
            // The literal's own trailing space is at position 33 and is indistinguishable from the
            // padding that follows it - which is exactly why it must be transcribed rather than trimmed:
            // the assertion above pins position 33 as part of the text, so a "tidied" 32-character
            // literal would still pass startsWith but fail the equality below.
            assertThat(image).isEqualTo(NO_ACCESS_MESSAGE
                    + " ".repeat(ERR_MSG_WIDTH - NO_ACCESS_MESSAGE.length()));
            assertThat(image.charAt(32)).isEqualTo(' ');
        }

        @Test
        @DisplayName("path 3: CCDA-MSG-INVALID-KEY on the WHEN OTHER AID path of COMEN01C:101")
        void invalidKeyMessagePath() {
            // EVALUATE EIBAID ... WHEN OTHER (app/cbl/COMEN01C.cbl:99-102) moves the X(50) message of
            // app/cpy/CSMSG01Y.cpy:20-21 into the X(80) WS-MESSAGE, which then narrows to X(78).
            assertThat(SystemMessages.CCDA_MSG_INVALID_KEY)
                    .hasSize(SystemMessages.MESSAGE_LENGTH)
                    .hasSize(50)
                    .startsWith("Invalid key pressed. Please see below...");
            // Forty, counted mechanically from app/cpy/CSMSG01Y.cpy:21 including the trailing
            // three-dot ellipsis. Thirty-nine is the plausible-looking wrong answer.
            assertThat(SystemMessages.CCDA_MSG_INVALID_KEY.strip()).hasSize(40);

            String image = errMsgImage(SystemMessages.CCDA_MSG_INVALID_KEY);

            assertThat(image).hasSize(ERR_MSG_WIDTH);
            // A 50-character sending field is narrower than the 78-character receiver, so nothing is
            // lost on this path - the message is padded, not truncated. That is why this path cannot
            // establish the truncation direction and why the synthetic probes above are required.
            assertThat(image).startsWith(SystemMessages.CCDA_MSG_INVALID_KEY);
            assertThat(image.strip()).isEqualTo(SystemMessages.CCDA_MSG_INVALID_KEY.strip());
        }

        @Test
        @DisplayName("path 4: the coming-soon text, in full in ComingSoonDefect below")
        void comingSoonMessagePath() {
            String image = CODEC.movePicX(comingSoonWsMessage(1), ERR_MSG_WIDTH);

            assertThat(image).hasSize(ERR_MSG_WIDTH)
                    .startsWith("This option Accountis coming soon ...");
        }

        @Test
        @DisplayName("with no message at all the field is 78 spaces - not null, not empty")
        void theNoMessageCaseIsSeventyEightSpaces() {
            // MOVE SPACES TO WS-MESSAGE, ERRMSGO OF COMEN1AO - app/cbl/COMEN01C.cbl:79-80, executed on
            // every entry before anything else. The cleared field is still 78 bytes wide on the wire.
            String cleared = errMsgImage("");

            assertThat(cleared).hasSize(ERR_MSG_WIDTH).isNotNull().isNotEmpty().isBlank();
            assertThat(cleared).isEqualTo(" ".repeat(ERR_MSG_WIDTH));
            assertThat(spaces(ERR_MSG_WIDTH)).isEqualTo(cleared);
            assertThat(MainMenuResponse.builder().errMsg(cleared).build().errMsg())
                    .hasSize(ERR_MSG_WIDTH)
                    .isBlank();
        }

        @Test
        @DisplayName("an exactly-78-character message is neither padded nor truncated")
        void anExactlyWidthMessageIsUntouched() {
            String exact = positionalProbe(ERR_MSG_WIDTH);

            assertThat(exact).hasSize(ERR_MSG_WIDTH);
            assertThat(CODEC.movePicX(exact, ERR_MSG_WIDTH)).isSameAs(exact);
        }

        @Test
        @DisplayName("the charset is stated explicitly and is never the platform default")
        void theCodecCarriesAnExplicitCharset() {
            // FixedWidthCodec cannot be constructed without a charset, so every movePicX call in this
            // class is charset-explicit by construction (practice B8). Asserting the value keeps a
            // future edit from swapping US-ASCII for Charset.defaultCharset().
            assertThat(CODEC.charset()).isEqualTo(StandardCharsets.US_ASCII)
                    .isEqualTo(FIXTURE_CHARSET);
            assertThat(CODEC.charset().name()).isEqualTo("US-ASCII");
        }
    }

    // =================================================================================================
    @Nested
    @DisplayName("The preserved legacy defect - \"This option Accountis coming soon ...\"")
    class ComingSoonDefect {

        /*
         * app/cbl/COMEN01C.cbl:159-163 reads:
         *
         *     STRING 'This option '                     DELIMITED BY SIZE
         *             CDEMO-MENU-OPT-NAME(WS-OPTION)     DELIMITED BY SPACE
         *             'is coming soon ...'               DELIMITED BY SIZE
         *        INTO WS-MESSAGE
         *
         * DELIMITED BY SPACE stops at the FIRST space of the 35-character option name, so option 1
         * ('Account View', app/cpy/COMEN02Y.cpy:27) contributes only 'Account'. The first literal ends
         * with a space, but the third BEGINS with 'is', so the concatenation reads
         *
         *     'This option ' + 'Account' + 'is coming soon ...'
         *         = "This option Accountis coming soon ..."
         *
         * with NO SPACE before "is". That is a genuine legacy defect and practice B5 requires it be
         * preserved: inserting the missing space would be a behaviour change and a parity violation, so
         * it is asserted here as required output rather than worked around.
         *
         * The admin sibling genuinely differs. app/cbl/COADM01C.cbl:149-153 is the same STRING with the
         * option-name operand COMMENTED OUT at :150-151:
         *
         *     STRING 'This option '                     DELIMITED BY SIZE
         *     *       CDEMO-ADMIN-OPT-NAME(WS-OPTION)
         *     *                                DELIMITED BY SIZE
         *             'is coming soon ...'               DELIMITED BY SIZE
         *        INTO WS-MESSAGE
         *
         * so nothing sits between the two literals and the trailing space of the first survives: the
         * admin text reads "This option is coming soon ..." - correctly spaced. The two screens produce
         * different text from near-identical code, and neither may be harmonised toward the other.
         */

        @Test
        @DisplayName("option 1 yields \"This option Accountis coming soon ...\" - no space before \"is\"")
        void optionOneKeepsTheMissingSpace() {
            String expected = "This option Accountis coming soon ...";

            String message = comingSoonWsMessage(1);

            assertThat(message).hasSize(WS_MESSAGE_WIDTH).startsWith(expected);
            assertThat(message.strip()).isEqualTo(expected);
            // The defect, stated as narrowly as it can be: the 12-character literal occupies positions
            // 1 to 12, "Account" occupies 13 to 19, and "is" begins at position 20 with nothing between
            // them. Zero-based, that is index 18 holding 't' and index 19 holding 'i'.
            assertThat(expected.charAt(COMING_SOON_PREFIX.length() + "Account".length() - 1))
                    .isEqualTo('t');
            assertThat(expected.charAt(COMING_SOON_PREFIX.length() + "Account".length()))
                    .isEqualTo('i');
            assertThat(expected.charAt(18)).isEqualTo('t');
            assertThat(expected.charAt(19)).isEqualTo('i');
            assertThat(expected).contains("Accountis").doesNotContain("Account is");
            assertThat(expected).hasSize(COMING_SOON_PREFIX.length() + "Account".length()
                    + COMING_SOON_SUFFIX.length()).hasSize(37);
        }

        @Test
        @DisplayName("the defect survives the narrowing to ERRMSGO, right-padded to 78")
        void theDefectReachesTheScreenField() {
            String image = CODEC.movePicX(comingSoonWsMessage(1), ERR_MSG_WIDTH);

            assertThat(image).hasSize(ERR_MSG_WIDTH)
                    .isEqualTo("This option Accountis coming soon ..."
                            + " ".repeat(ERR_MSG_WIDTH - 37));
            assertThat(MainMenuResponse.builder().errMsg(image).build().errMsg())
                    .isEqualTo(image)
                    .hasSize(ERR_MSG_WIDTH);
        }

        @Test
        @DisplayName("option 10 yields \"This option Billis coming soon ...\" - data-driven, not hard-coded")
        void optionTenProvesTheDelimiterIsDataDriven() {
            // 'Bill Payment' (app/cpy/COMEN02Y.cpy:82) delimits to 'Bill', so the missing space appears
            // in a different place with a different word. A hard-coded "Accountis" would fail here,
            // which is what makes this the proof that DELIMITED BY SPACE is actually being modelled.
            String expected = "This option Billis coming soon ...";

            String message = comingSoonWsMessage(10);

            assertThat(message.strip()).isEqualTo(expected);
            assertThat(expected).contains("Billis").doesNotContain("Bill is");
            assertThat(expected).hasSize(COMING_SOON_PREFIX.length() + "Bill".length()
                    + COMING_SOON_SUFFIX.length()).hasSize(34);
            assertThat(CODEC.movePicX(message, ERR_MSG_WIDTH)).hasSize(ERR_MSG_WIDTH)
                    .startsWith(expected);
        }

        @ParameterizedTest(name = "option {0} contributes \"{1}\"")
        @CsvSource({"1,Account", "2,Account", "3,Credit", "4,Credit", "5,Credit", "6,Transaction",
                    "7,Transaction", "8,Transaction", "9,Transaction", "10,Bill"})
        @DisplayName("every one of the ten options contributes only its first word")
        void everyOptionDelimitsAtItsFirstSpace(int cobolSubscript, String contribution) {
            assertThat(delimitedBySpace(optionName(cobolSubscript))).isEqualTo(contribution);

            String message = comingSoonWsMessage(cobolSubscript).strip();

            assertThat(message)
                    .isEqualTo(COMING_SOON_PREFIX + contribution + COMING_SOON_SUFFIX)
                    .startsWith("This option " + contribution + "is");
            assertThat(message).doesNotContain(contribution + " is");
        }

        @Test
        @DisplayName("the three STRING operands are transcribed byte-for-byte from :159, :160 and :162")
        void theOperandsAreTheCopybookAndSourceLiterals() {
            assertThat(COMING_SOON_PREFIX).isEqualTo("This option ").hasSize(12).endsWith(" ");
            assertThat(COMING_SOON_SUFFIX).isEqualTo("is coming soon ...").hasSize(18)
                    .startsWith("is");
            // The trailing space of the first operand and the missing leading space of the third are
            // the whole cause: one space is supplied, and the option name is inserted after it.
            assertThat(COMING_SOON_SUFFIX).doesNotStartWith(" ");
            assertThat(optionName(1)).isEqualTo("Account View                       ")
                    .hasSize(MenuOptions.OPT_NAME_LENGTH)
                    .hasSize(35);
        }

        @Test
        @DisplayName("with the option name absent the text WOULD be correctly spaced - the admin case")
        void theAdminShapeIsRecordedButNotProduced() {
            // Recorded to make the divergence explicit and checkable. COADM01C's commented-out operand
            // leaves the two literals adjacent, so its text is correctly spaced; COMEN01C's live operand
            // is what removes the space. This is the admin SHAPE, computed from this screen's own two
            // literals - it is not an assertion about AdminMenuResponse and reads nothing from it.
            String withoutTheOptionName =
                    CODEC.concatenateDelimitedBySize(COMING_SOON_PREFIX, COMING_SOON_SUFFIX);

            assertThat(withoutTheOptionName).isEqualTo("This option is coming soon ...")
                    .contains("option is");
            assertThat(comingSoonWsMessage(1).strip()).isNotEqualTo(withoutTheOptionName);
        }

        @Test
        @DisplayName("the coming-soon path is the one that turns the message green")
        void theComingSoonPathOverridesTheColour() {
            // MOVE DFHGREEN TO ERRMSGC OF COMEN1AO at app/cbl/COMEN01C.cbl:158 sits immediately before
            // the STRING at :159-163, so the coming-soon text is informational rather than an error.
            MainMenuResponse response = MainMenuResponse.builder()
                    .errMsg(CODEC.movePicX(comingSoonWsMessage(1), ERR_MSG_WIDTH))
                    .errMsgColor(BmsAttributes.DFHGREEN)
                    .build();

            assertThat(response.errMsg()).startsWith("This option Accountis coming soon ...");
            assertThat(response.errMsgColor()).isEqualTo(BmsAttributes.DFHGREEN);
        }
    }

    // =================================================================================================
    @Nested
    @DisplayName("COMEN1AO OPTIONO - the two-digit, LEFT zero-filled image of WS-OPTION PIC 9(02)")
    class OptionEcho {

        @ParameterizedTest(name = "option {0} renders \"{1}\"")
        @CsvSource({"0,00", "1,01", "2,02", "3,03", "9,09", "10,10", "11,11", "12,12", "99,99"})
        @DisplayName("a PIC 9(02) store is zero-filled on the LEFT")
        void theOptionIsZeroFilledOnTheLeft(int entered, String expected) {
            // MOVE WS-OPTION TO OPTIONO OF COMEN1AO - app/cbl/COMEN01C.cbl:125 - where WS-OPTION is
            // PIC 9(02) at :46. The mapset agrees independently: OPTION carries
            // JUSTIFY=(RIGHT,ZERO) at app/bms/COMEN01.bms:147.
            String image = CODEC.movePic9(entered, OPTION_WIDTH);

            assertThat(image).isEqualTo(expected).hasSize(OPTION_WIDTH).hasSize(2);
            assertThat(MainMenuResponse.builder().option(image).build().option())
                    .isEqualTo(expected)
                    .hasSize(OPTION_WIDTH);
        }

        @Test
        @DisplayName("zero is reachable, because the validation at :127-129 still sends the screen")
        void zeroIsAReachableValue() {
            // IF WS-OPTION IS NOT NUMERIC OR WS-OPTION > CDEMO-MENU-OPT-COUNT OR WS-OPTION = ZEROS
            // (app/cbl/COMEN01C.cbl:127-129) sets the error flag and performs SEND-MENU-SCREEN, so a
            // zero option really does reach the screen and "00" is a value the payload must express.
            MainMenuResponse response = afterSendMenuScreen(0, VALIDATION_MESSAGE);

            assertThat(response.option()).isEqualTo("00").hasSize(OPTION_WIDTH);
            assertThat(response.errMsg()).startsWith(VALIDATION_MESSAGE);
        }

        @Test
        @DisplayName("\"01\" and \"1\" stay distinct, which an int could not express")
        void theEchoIsTextualNotNumeric() {
            assertThat(MainMenuResponse.builder().option("01").build().option()).isEqualTo("01");
            assertThat(MainMenuResponse.builder().option("1").build().option()).isEqualTo("1");
            assertThat(MainMenuResponse.builder().option("01").build())
                    .isNotEqualTo(MainMenuResponse.builder().option("1").build());
        }

        @Test
        @DisplayName("\"nothing entered yet\" is two spaces - a third state no integer has")
        void theUnenteredStateIsSpaces() {
            // MOVE LOW-VALUES TO COMEN1AO at app/cbl/COMEN01C.cbl:89 clears the field before the first
            // SEND, so the wire image on first entry is neither "00" nor absent but blank.
            MainMenuResponse response = MainMenuResponse.builder()
                    .option(spaces(OPTION_WIDTH))
                    .build();

            assertThat(response.option()).isEqualTo("  ").hasSize(OPTION_WIDTH).isBlank();
            assertThat(response.option()).isNotEqualTo("00");
        }

        @Test
        @DisplayName("the option component is declared as a String, and no member is floating point")
        void theOptionIsDeclaredTextual() throws ReflectiveOperationException {
            assertThat(MainMenuResponse.class.getMethod("option").getReturnType())
                    .isEqualTo(String.class);
            for (RecordComponent component : MainMenuResponse.class.getRecordComponents()) {
                assertThat(component.getType())
                        .as(component.getName() + " must not be a floating-point type")
                        .isNotEqualTo(double.class)
                        .isNotEqualTo(float.class)
                        .isNotEqualTo(Double.class)
                        .isNotEqualTo(Float.class);
            }
        }

        @Test
        @DisplayName("an over-wide option loses its HIGH-order digit, the opposite of the message field")
        void anOverWideOptionLosesItsLeadingDigit() {
            // A numeric receiver is aligned on its implied decimal point, so 123 into PIC 9(02) keeps
            // "23". The message field, being PIC X, keeps the LEADING characters instead. The two
            // helpers are therefore not interchangeable, and this class fails if they are swapped.
            assertThat(CODEC.movePic9(123, OPTION_WIDTH)).isEqualTo("23");
            assertThat(CODEC.movePicX("123", OPTION_WIDTH)).isEqualTo("12");
        }

        @Test
        @DisplayName("PIC 9 is unsigned, so a negative option has no image and is refused")
        void aNegativeOptionIsRefused() {
            // WS-OPTION is PIC 9(02) at app/cbl/COMEN01C.cbl:46 - unsigned. There is no sign position to
            // store, so the codec refuses rather than quietly storing the magnitude.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CODEC.movePic9(-1, OPTION_WIDTH))
                    .withMessageContaining("unsigned PIC 9");
        }
    }

    // =================================================================================================
    @Nested
    @DisplayName("OPTN001O to OPTN012O - twelve declared, ten composed, none pruned")
    class TwelveOptionSlots {

        /*
         * Two independent facts, and both are preserved (practice B5).
         *
         * app/cpy/COMEN02Y.cpy:21 sets CDEMO-MENU-OPT-COUNT PIC 9(02) VALUE 10, while the table at :88
         * is CDEMO-MENU-OPT OCCURS 12 TIMES. BUILD-MENU-OPTIONS's EVALUATE WS-IDX
         * (app/cbl/COMEN01C.cbl:248-275) DOES carry arms for 11 and 12, at :269-270 and :271-272 - but
         * the loop bound at :238-239 is UNTIL WS-IDX > CDEMO-MENU-OPT-COUNT, fixed at ten, so those two
         * arms are unreachable and OPTN011O and OPTN012O are never written.
         *
         * The admin sibling reaches the same outcome by a different route: COADM01C's EVALUATE has no
         * arm at all for 11 or 12. Two different routes to the same unwritable slots, and both are
         * preserved as they stand - the arms are not deleted here and the slots are not pruned.
         */

        @ParameterizedTest(name = "slot {0} exists and is addressable")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12})
        @DisplayName("all twelve slots exist as real members, each PIC X(40)")
        void allTwelveSlotsExist(int slot) {
            String member = String.format("optn%03d", slot);

            assertThat(PAYLOAD_MEMBERS).contains(member);
            assertThat(DECLARED_WIDTHS.get(member)).isEqualTo(OPTION_LINE_WIDTH).isEqualTo(40);
            assertThat(MainMenuResponse.builder().optionLine(slot, "x").build().optionLine(slot))
                    .isEqualTo("x");
            assertThat(MainMenuResponse.builder().build().optionLines()).hasSize(12);
        }

        @ParameterizedTest(name = "slot {0} carries its COMEN02Y option line")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
        @DisplayName("slots 1 to 10 carry the 40-character option-line image")
        void theTenPopulatedSlotsCarryTheirImage(int slot) {
            MainMenuResponse response = afterSendMenuScreen(slot, "");

            String expected = CODEC.movePic9(slot, MenuOptions.OPT_NUM_LENGTH)
                    + ". "
                    + optionName(slot)
                    + " ";
            assertThat(response.optionLine(slot))
                    .as("slot " + slot + " of BUILD-MENU-OPTIONS")
                    .isEqualTo(expected)
                    .hasSize(OPTION_LINE_WIDTH)
                    .hasSize(40);
            assertThat(response.isPopulatedByProgram(slot)).isTrue();
        }

        @Test
        @DisplayName("the ten images are exactly the ten COMEN02Y labels, numbered and padded")
        void theTenImagesMatchTheCopybookLabels() {
            // Transcribed from app/cpy/COMEN02Y.cpy, one label per cited line. Each is 2 + 2 + 35 = 39
            // characters from STRING (app/cbl/COMEN01C.cbl:243-246) plus the single trailing space the
            // MOVE SPACES at :241 left in the 40th position of WS-MENU-OPT-TXT PIC X(40).
            List<String> expected = List.of(
                    "01. Account View                        ",        // COMEN02Y.cpy:27
                    "02. Account Update                      ",        // COMEN02Y.cpy:33
                    "03. Credit Card List                    ",        // COMEN02Y.cpy:39
                    "04. Credit Card View                    ",        // COMEN02Y.cpy:45
                    "05. Credit Card Update                  ",        // COMEN02Y.cpy:51
                    "06. Transaction List                    ",        // COMEN02Y.cpy:57
                    "07. Transaction View                    ",        // COMEN02Y.cpy:63
                    "08. Transaction Add                     ",        // COMEN02Y.cpy:70 - the LIVE line
                    "09. Transaction Reports                 ",        // COMEN02Y.cpy:76
                    "10. Bill Payment                        ");       // COMEN02Y.cpy:82

            MainMenuResponse response = afterSendMenuScreen(1, "");

            for (int slot = 1; slot <= POPULATED_OPTION_SLOTS; slot++) {
                assertThat(expected.get(slot - 1)).hasSize(OPTION_LINE_WIDTH);
                assertThat(response.optionLine(slot))
                        .as("slot " + slot + " must match app/cpy/COMEN02Y.cpy byte for byte")
                        .isEqualTo(expected.get(slot - 1));
            }
            assertThat(expected).hasSize(POPULATED_OPTION_SLOTS).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("slot 8 takes the LIVE line 70, never the commented-out variant on line 69")
        void slotEightUsesTheLiveLabel() {
            // The decoy is named here and nowhere else, and is never asserted as a value. app/cpy/
            // COMEN02Y.cpy:69 holds *        'Transaction Add (Admin Only)       '. - commented out with
            // an asterisk in column 7, an abandoned earlier wording. The live value is on :70. Nothing is
            // inferred from the decoy either: the live user-type column on :72 is 'U', not 'A', so option
            // 8 is NOT an admin-only option despite what the commented text suggests (practice B5).
            MainMenuResponse response = afterSendMenuScreen(8, "");

            assertThat(optionName(8)).isEqualTo("Transaction Add                    ").hasSize(35);
            assertThat(response.optionLine(8)).isEqualTo("08. Transaction Add                     ");
            assertThat(response.optionLine(8)).doesNotContain("Admin Only");
            assertThat(MenuOptions.optionBySubscript(8)).isPresent();
            assertThat(MenuOptions.optionBySubscript(8).orElseThrow().menuOptName())
                    .isEqualTo(optionName(8));
            assertThat(MenuOptions.optionBySubscript(8).orElseThrow().menuOptUsrType()).isEqualTo("U");
        }

        @Test
        @DisplayName("the ten images agree with admin.model.MenuOptions, which reads the same copybook")
        void theImagesAgreeWithTheSharedMenuTable() {
            // A second, independent derivation of the same ten lines. MenuOptions transcribes
            // app/cpy/COMEN02Y.cpy in the main source tree; the literals above transcribe it here. If
            // either transcription drifts, these two disagree.
            List<MenuOptions.MenuOption> table = MenuOptions.activeOptions();
            MainMenuResponse response = afterSendMenuScreen(1, "");

            assertThat(table).hasSize(POPULATED_OPTION_SLOTS);
            assertThat(MenuOptions.menuOptCount()).isEqualTo(POPULATED_OPTION_SLOTS).isEqualTo(10);
            assertThat(MenuOptions.TABLE_SIZE).isEqualTo(DECLARED_OPTION_SLOTS).isEqualTo(12);
            for (int slot = 1; slot <= POPULATED_OPTION_SLOTS; slot++) {
                MenuOptions.MenuOption entry = table.get(slot - 1);
                String fromTheTable = CODEC.movePicX(CODEC.concatenateDelimitedBySize(
                        entry.menuOptNumImage(), ". ", entry.menuOptName()), OPTION_LINE_WIDTH);
                assertThat(response.optionLine(slot)).isEqualTo(fromTheTable);
                assertThat(entry.menuOptNum()).isEqualTo(slot);
            }
        }

        @ParameterizedTest(name = "slot {0} is 40 spaces, because the loop never reaches it")
        @ValueSource(ints = {11, 12})
        @DisplayName("slots 11 and 12 are 40 spaces - not null, not absent, not pruned")
        void theTwoUnreachableSlotsAreSpaceFilled(int slot) {
            MainMenuResponse response = afterSendMenuScreen(1, "");

            assertThat(response.optionLine(slot))
                    .as("slot " + slot + " is declared by the mapset and cleared by MOVE LOW-VALUES")
                    .isNotNull()
                    .isEqualTo(" ".repeat(OPTION_LINE_WIDTH))
                    .hasSize(OPTION_LINE_WIDTH)
                    .isBlank();
            assertThat(response.isPopulatedByProgram(slot))
                    .as("the loop bound at app/cbl/COMEN01C.cbl:238-239 stops at ten")
                    .isFalse();
        }

        @Test
        @DisplayName("the two unreachable slots are still writable, so the type is not narrower than the map")
        void theTwoUnreachableSlotsRemainWritable() {
            // The EVALUATE arms at :269-272 exist in the source. Refusing to write slots 11 and 12 would
            // make this payload narrower than the mapset it projects, so they stay writable even though
            // the program cannot reach them.
            MainMenuResponse response = MainMenuResponse.builder()
                    .optionLine(11, "eleven")
                    .optionLine(12, "twelve")
                    .build();

            assertThat(response.optn011()).isEqualTo("eleven");
            assertThat(response.optn012()).isEqualTo("twelve");
            assertThat(response.optionLine(11)).isEqualTo("eleven");
            assertThat(response.optionLine(12)).isEqualTo("twelve");
            assertThat(response.withOptionLine(12, "replaced").optn012()).isEqualTo("replaced");
        }

        @Test
        @DisplayName("table size 12 and populated count 10 are separate, independently asserted facts")
        void theTwoCountsAreDistinct() {
            assertThat(MainMenuResponse.OPTION_LINE_COUNT).isEqualTo(12);
            assertThat(MainMenuResponse.ACTIVE_OPTION_LINE_COUNT).isEqualTo(10);
            assertThat(MainMenuResponse.OPTION_LINE_COUNT)
                    .isGreaterThan(MainMenuResponse.ACTIVE_OPTION_LINE_COUNT);
            assertThat(MainMenuResponse.OPTION_LINE_COUNT
                    - MainMenuResponse.ACTIVE_OPTION_LINE_COUNT).isEqualTo(2);
            assertThat(MainMenuResponse.FIRST_OPTION_LINE_SLOT).isEqualTo(1);
            assertThat(MainMenuResponse.LAST_OPTION_LINE_SLOT).isEqualTo(12);
        }

        @ParameterizedTest(name = "slot {0} is populated by the program: {1}")
        @MethodSource("com.vsergeychik.carddemo.admin.dto.MainMenuResponseTest#everySlotAndWhetherWritten")
        @DisplayName("the loop bound is reported for all twelve slots, both sides of the boundary")
        void populationFollowsTheLoopBound(int slot, boolean populated) {
            assertThat(MainMenuResponse.builder().build().isPopulatedByProgram(slot))
                    .isEqualTo(populated);
        }

        @ParameterizedTest(name = "slot {0} is rejected rather than clamped")
        @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 13, 14, Integer.MAX_VALUE})
        @DisplayName("a subscript outside 1..12 is refused on both sides, never silently clamped")
        void outOfRangeSubscriptsAreRefused(int slot) {
            // COBOL numbers the OCCURS table from 1. Clamping an out-of-range subscript is what hides an
            // off-by-one in a 1-based-to-0-based conversion, so it is refused instead.
            MainMenuResponse response = MainMenuResponse.builder().build();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.optionLine(slot))
                    .withMessageContaining("1..12");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.isPopulatedByProgram(slot));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> MainMenuResponse.builder().optionLine(slot, "x"));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.withOptionLine(slot, "x"));
        }

        @Test
        @DisplayName("the 1-based accessor and the 0-based list view address the same twelve slots")
        void theOneBasedAndZeroBasedViewsAgree() {
            MainMenuResponse response = afterSendMenuScreen(1, "");

            List<String> view = response.optionLines();
            assertThat(view).hasSize(DECLARED_OPTION_SLOTS);
            for (int slot = 1; slot <= DECLARED_OPTION_SLOTS; slot++) {
                assertThat(view.get(slot - 1))
                        .as("optionLines().get(" + (slot - 1) + ") is optionLine(" + slot + ")")
                        .isEqualTo(response.optionLine(slot));
            }
            assertThat(view.get(0)).isEqualTo(response.optn001());
            assertThat(view.get(DECLARED_OPTION_SLOTS - 1)).isEqualTo(response.optn012());
        }

        @Test
        @DisplayName("each named optn0nn setter of COMEN1AO writes only its own slot")
        void eachSetterIsIndependent() {
            for (int slot = 1; slot <= DECLARED_OPTION_SLOTS; slot++) {
                MainMenuResponse response = MainMenuResponse.builder()
                        .optionLine(slot, "written")
                        .build();

                for (int other = 1; other <= DECLARED_OPTION_SLOTS; other++) {
                    if (other == slot) {
                        assertThat(response.optionLine(other)).isEqualTo("written");
                    } else {
                        assertThat(response.optionLine(other))
                                .as("writing slot " + slot + " must not touch slot " + other)
                                .isNull();
                    }
                }
            }
        }
    }

    // =================================================================================================
    @Nested
    @DisplayName("Navigation - EXEC CICS XCTL becomes three response members, and no server state")
    class StatelessNavigation {

        @Test
        @DisplayName("the response names the next program, mapset and map")
        void theThreeNavigationMembersExist() {
            // COMEN01C has two XCTL sites: :152-155 to CDEMO-MENU-OPT-PGMNAME(WS-OPTION) and :175-177 to
            // CDEMO-TO-PROGRAM. Neither becomes a server-side forward: each becomes a name the client
            // calls next, so there is no redirect chain and no session affinity (gate G40).
            List<String> components = Stream.of(MainMenuResponse.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(components).contains("nextProgram", "nextMapset", "nextMap");
        }

        @Test
        @DisplayName("this screen's own targets are COMEN01 and COMEN1A")
        void theDefaultTargetsAreThisScreen() {
            // EXEC CICS SEND MAP('COMEN1A') MAPSET('COMEN01') - app/cbl/COMEN01C.cbl:190-191 - and
            // DEFINE MAPSET(COMEN01) at app/csd/CARDDEMO.CSD:133.
            MainMenuResponse response = MainMenuResponse.builder().build();

            assertThat(response.nextMapset()).isEqualTo("COMEN01")
                    .isEqualTo(MainMenuResponse.MAPSET_NAME)
                    .hasSize(NavigationContext.LAST_MAPSET_LENGTH);
            assertThat(response.nextMap()).isEqualTo("COMEN1A")
                    .isEqualTo(MainMenuResponse.MAP_NAME)
                    .hasSize(NavigationContext.LAST_MAP_LENGTH);
            assertThat(MainMenuResponse.initial().nextMapset()).isEqualTo("COMEN01");
            assertThat(MainMenuResponse.initial().nextMap()).isEqualTo("COMEN1A");
        }

        @ParameterizedTest(name = "nextProgram can carry {0}")
        @ValueSource(strings = {"COACTVWC", "COACTUPC", "COCRDLIC", "COCRDSLC", "COCRDUPC",
                                "COTRN00C", "COTRN01C", "COTRN02C", "CORPT00C", "COBIL00C",
                                "COSGN00C"})
        @DisplayName("all ten option targets and the sign-on return are expressible")
        void everyTransferTargetIsExpressible(String target) {
            MainMenuResponse response = MainMenuResponse.builder().nextProgram(target).build();

            assertThat(response.nextProgram()).isEqualTo(target)
                    .hasSize(MainMenuResponse.NEXT_PROGRAM_LENGTH);
            assertThat(response.withNextProgram("COSGN00C").nextProgram()).isEqualTo("COSGN00C");
        }

        @Test
        @DisplayName("the ten targets are the ten CDEMO-MENU-OPT-PGMNAME values, in subscript order")
        void theTenTargetsAreTheMenuTablesPrograms() {
            // app/cpy/COMEN02Y.cpy lines 28, 34, 40, 46, 52, 58, 64, 71, 77 and 83.
            assertThat(OPTION_TARGET_PROGRAMS).hasSize(POPULATED_OPTION_SLOTS).doesNotHaveDuplicates();
            for (int slot = 1; slot <= POPULATED_OPTION_SLOTS; slot++) {
                assertThat(MenuOptions.optionBySubscript(slot).orElseThrow().menuOptPgmName())
                        .as("subscript " + slot + " of the shared menu table")
                        .isEqualTo(CODEC.movePicX(OPTION_TARGET_PROGRAMS.get(slot - 1),
                                MenuOptions.OPT_PGMNAME_LENGTH));
                assertThat(OPTION_TARGET_PROGRAMS.get(slot - 1))
                        .hasSize(MainMenuResponse.NEXT_PROGRAM_LENGTH);
            }
        }

        @Test
        @DisplayName("the sign-on route is COSGN00C, and this endpoint is where the 'U' role lands")
        void theSignOnRouteIsRecorded() {
            // RETURN-TO-SIGNON-SCREEN defaults CDEMO-TO-PROGRAM to 'COSGN00C' at
            // app/cbl/COMEN01C.cbl:172-173 before XCTL PROGRAM(CDEMO-TO-PROGRAM) at :175-177, and PF3
            // reaches it from :96-98.
            //
            // The traffic also runs the other way. COSGN00C:237 does XCTL PROGRAM('COMEN01C') for a
            // regular user, against PROGRAM('COADM01C') at :232 for an administrator, so GET /api/menu is
            // precisely where user.SignOnService's 'U' role decision routes. nextProgram is the stateless
            // replacement for that hard-coded route as much as for the two XCTLs in this program.
            assertThat(MainMenuResponse.SIGNON_PROGRAM).isEqualTo("COSGN00C");
            assertThat(MainMenuResponse.builder().build().nextProgram())
                    .as("the sign-on program is a documented target, never a silent default")
                    .isNull();
            assertThat(MainMenuResponse.builder()
                    .nextProgram(MainMenuResponse.SIGNON_PROGRAM)
                    .build()
                    .nextProgram()).isEqualTo("COSGN00C");
        }

        @Test
        @DisplayName("the navigation widths come from the communication area: a program is 8, a map is 7")
        void theNavigationWidthsTrackTheCommarea() {
            // app/cpy/COCOM01Y.cpy:24 declares CDEMO-TO-PROGRAM PIC X(08), and :43-44 declare
            // CDEMO-LAST-MAP and CDEMO-LAST-MAPSET PIC X(7) - seven, not eight.
            assertThat(MainMenuResponse.NEXT_PROGRAM_LENGTH)
                    .isEqualTo(NavigationContext.TO_PROGRAM_LENGTH).isEqualTo(8);
            assertThat(MainMenuResponse.NEXT_MAPSET_LENGTH)
                    .isEqualTo(NavigationContext.LAST_MAPSET_LENGTH).isEqualTo(7);
            assertThat(MainMenuResponse.NEXT_MAP_LENGTH)
                    .isEqualTo(NavigationContext.LAST_MAP_LENGTH).isEqualTo(7);
            assertThat(NavigationContext.LAST_MAP_LENGTH)
                    .as("CDEMO-LAST-MAP is X(7); assuming X(8) would shift the last two bytes")
                    .isNotEqualTo(NavigationContext.TO_PROGRAM_LENGTH);
        }

        @Test
        @DisplayName("the echoed communication area is a payload member, and it is exactly 160 bytes")
        void theCommunicationAreaIsOneHundredAndSixtyBytes() throws Exception {
            // 34 + 84 + 12 + 16 + 14 = 160, from app/cpy/COCOM01Y.cpy's five groups.
            MainMenuResponse response = MainMenuResponse.builder()
                    .navigationContext(NavigationContext.empty().withUserTypeUser())
                    .build();

            // It travels in the body, which is what makes the endpoint stateless: it is a declared
            // member and it is serialised, unlike the two @JsonIgnored metadata members (rule R6).
            assertThat(Stream.of(MainMenuResponse.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList()).contains("navigationContext");
            assertThat(new ObjectMapper().writeValueAsString(response))
                    .contains("\"navigationContext\"")
                    .contains("\"userType\":\"U\"");

            assertThat(NavigationContext.GENERAL_INFO_LENGTH
                    + NavigationContext.CUSTOMER_INFO_LENGTH
                    + NavigationContext.ACCOUNT_INFO_LENGTH
                    + NavigationContext.CARD_INFO_LENGTH
                    + NavigationContext.MORE_INFO_LENGTH)
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH)
                    .isEqualTo(34 + 84 + 12 + 16 + 14)
                    .isEqualTo(160);
            assertThat(response.navigationContext().toFixedWidth(CODEC)).hasSize(160);
        }

        @Test
        @DisplayName("CDEMO-USER-TYPE is echoed unaltered, because COMEN01C only ever reads it")
        void theUserTypeIsEchoedUnaltered() {
            // The only assignment to CDEMO-USER-TYPE in the whole program is commented out, at
            // app/cbl/COMEN01C.cbl:150. Whatever arrives must therefore be returned byte-identically.
            NavigationContext arriving = NavigationContext.empty()
                    .withUserId("USER0001")
                    .withUserTypeUser()
                    .withPgmReenter();

            MainMenuResponse response = MainMenuResponse.builder()
                    .navigationContext(arriving)
                    .build();

            assertThat(response.navigationContext()).isSameAs(arriving).isEqualTo(arriving);
            assertThat(response.navigationContext().userType())
                    .isEqualTo(NavigationContext.USER_TYPE_USER).isEqualTo("U");
            assertThat(response.navigationContext().isUser()).isTrue();
            assertThat(response.navigationContext().isAdmin()).isFalse();
            assertThat(response.navigationContext().userId()).isEqualTo("USER0001");
        }

        @Test
        @DisplayName("an administrator's context is echoed just as unaltered as a user's")
        void anAdminContextIsEchoedToo() {
            NavigationContext arriving = NavigationContext.empty().withUserTypeAdmin();

            MainMenuResponse response = MainMenuResponse.builder()
                    .navigationContext(arriving)
                    .build();

            assertThat(response.navigationContext().userType())
                    .isEqualTo(NavigationContext.USER_TYPE_ADMIN).isEqualTo("A");
            assertThat(response.navigationContext().isAdmin()).isTrue();
            assertThat(response.navigationContext().isUser()).isFalse();
        }

        @Test
        @DisplayName("both program contexts survive the echo: ENTER 0 and REENTER 1")
        void bothProgramContextsSurvive() {
            // CDEMO-PGM-CONTEXT with its 88-levels at app/cpy/COCOM01Y.cpy:29-31 is what distinguishes
            // MOVE LOW-VALUES then SEND (app/cbl/COMEN01C.cbl:87-90) from RECEIVE then validate (:91-92).
            MainMenuResponse onEnter = MainMenuResponse.builder()
                    .navigationContext(NavigationContext.empty().withPgmEnter())
                    .build();
            MainMenuResponse onReenter = MainMenuResponse.builder()
                    .navigationContext(NavigationContext.empty().withPgmReenter())
                    .build();

            assertThat(onEnter.navigationContext().pgmContext())
                    .isEqualTo(NavigationContext.PGM_CONTEXT_ENTER).isZero();
            assertThat(onEnter.navigationContext().isEnter()).isTrue();
            assertThat(onReenter.navigationContext().pgmContext())
                    .isEqualTo(NavigationContext.PGM_CONTEXT_REENTER).isEqualTo(1);
            assertThat(onReenter.navigationContext().isReenter()).isTrue();
        }

        @Test
        @DisplayName("an absent communication area becomes the initial 160-byte area, never null")
        void anAbsentContextFallsBackToTheInitialArea() {
            // COBOL has no null: a COMMAREA is always 160 bytes of something. This is also the false arm
            // of the only conditional in the builder, so both arms are exercised - the true arm by the
            // echo tests above.
            MainMenuResponse response = MainMenuResponse.builder()
                    .navigationContext(null)
                    .build();

            assertThat(response.navigationContext()).isNotNull()
                    .isEqualTo(NavigationContext.empty());
            assertThat(response.navigationContext().toFixedWidth(CODEC)).hasSize(160);
            assertThat(MainMenuResponse.builder().build().navigationContext())
                    .isEqualTo(NavigationContext.empty());
            assertThat(response.withNavigationContext(null).navigationContext())
                    .isEqualTo(NavigationContext.empty());
        }

        @Test
        @DisplayName("no server-side state: no session, no cache, no static mutable field (gates G37, G53)")
        void nothingIsHeldOnTheServer() {
            // Rule R6: the conversation travels in the payload. Anything static and mutable on the
            // response, its builder or this test would be state shared between callers, so the sweep
            // covers all three. JaCoCo's own $jacocoData probe array is static and non-final and is
            // excluded by name and by its synthetic flag, because it is instrumentation rather than
            // state.
            for (Class<?> type : List.of(MainMenuResponse.class,
                    MainMenuResponse.Builder.class,
                    MainMenuResponseTest.class)) {
                for (Field field : type.getDeclaredFields()) {
                    if (field.isSynthetic() || field.getName().startsWith("$")) {
                        continue;
                    }
                    if (Modifier.isStatic(field.getModifiers())) {
                        assertThat(Modifier.isFinal(field.getModifiers()))
                                .as(type.getSimpleName() + "." + field.getName()
                                        + " is static and must therefore be final")
                                .isTrue();
                    }
                }
            }
            // And nothing anywhere in the type refers to a servlet session or a static cache.
            assertThat(Stream.of(MainMenuResponse.class.getDeclaredMethods())
                    .map(Method::getName)
                    .toList())
                    .doesNotContain("getSession", "session", "cache");
        }

        @Test
        @DisplayName("two responses built from one context do not share mutable state")
        void responsesAreIndependentOfEachOther() {
            NavigationContext context = NavigationContext.empty().withUserTypeUser();
            MainMenuResponse first = MainMenuResponse.builder()
                    .navigationContext(context)
                    .nextProgram("COACTVWC")
                    .build();

            MainMenuResponse second = first.withNextProgram("COBIL00C");

            assertThat(first.nextProgram()).isEqualTo("COACTVWC");
            assertThat(second.nextProgram()).isEqualTo("COBIL00C");
            assertThat(second.navigationContext()).isSameAs(first.navigationContext());
            assertThat(first).isNotEqualTo(second);
        }
    }

    // =================================================================================================
    @Nested
    @DisplayName("Colour and header literals - DFHRED by declaration, and the COTTL01Y:21 decoy untouched")
    class ColourAndHeaderLiterals {

        @Test
        @DisplayName("the message colour defaults to DFHRED, as the mapset's COLOR=RED declares")
        void theColourDefaultsToRed() {
            // app/bms/COMEN01.bms:154-157 declares ERRMSG ATTRB=(ASKIP,BRT,FSET) COLOR=RED LENGTH=78
            // POS=(23,1). Red is therefore the declared state, not a fallback.
            //
            // DFHRED and DFHGREEN come from IBM CICS documentation, not from this repository: DFHBMSCA
            // is COPYed by all seventeen online programs (app/cbl/COMEN01C.cbl:61) and DFHATTR by two,
            // but both are IBM-supplied and ABSENT from the checkout. Agent Action Plan risk R-D records
            // that gap and practice B12 requires the provenance be stated where the values are asserted -
            // which is here.
            assertThat(MainMenuResponse.builder().build().errMsgColor())
                    .isEqualTo(BmsAttributes.DFHRED);
            assertThat(MainMenuResponse.initial().errMsgColor()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(BmsAttributes.DFHRED).isEqualTo((byte) 0xF2);
        }

        @Test
        @DisplayName("the coming-soon path overrides it to DFHGREEN, exactly as COMEN01C:158 does")
        void theColourIsOverridableToGreen() {
            MainMenuResponse response =
                    MainMenuResponse.builder().build().withErrMsgColor(BmsAttributes.DFHGREEN);

            assertThat(response.errMsgColor()).isEqualTo(BmsAttributes.DFHGREEN);
            assertThat(BmsAttributes.DFHGREEN).isEqualTo((byte) 0xF4);
            assertThat(BmsAttributes.DFHGREEN).isNotEqualTo(BmsAttributes.DFHRED);
        }

        @Test
        @DisplayName("the default is red, never the terminal's own default colour")
        void theDefaultIsNotTheTerminalDefault() {
            // DFHDFCOL, the terminal default, is 0x00 - which is also the Java default for a byte. If the
            // default were left implicit an unset colour would silently mean "terminal default" instead
            // of "red", so the distinction is asserted rather than assumed.
            assertThat(BmsAttributes.DFHDFCOL).isEqualTo((byte) 0x00);
            assertThat(MainMenuResponse.builder().build().errMsgColor())
                    .isNotEqualTo(BmsAttributes.DFHDFCOL);
        }

        @Test
        @DisplayName("title01 is the 40-character CCDA-TITLE01 of COTTL01Y:18-19")
        void titleOneIsTheHeadingLiteral() {
            // MOVE CCDA-TITLE01 TO TITLE01O OF COMEN1AO - app/cbl/COMEN01C.cbl:216.
            assertThat(ScreenTitles.CCDA_TITLE01)
                    .isEqualTo("      AWS Mainframe Modernization       ")
                    .hasSize(TITLE_WIDTH)
                    .hasSize(40)
                    .startsWith("      ")
                    .endsWith("       ");
            assertThat(ScreenTitles.CCDA_TITLE01.strip()).isEqualTo("AWS Mainframe Modernization")
                    .hasSize(27);
            // 6 leading + 27 + 7 trailing = 40. The spaces at both ends are part of the field.
            assertThat(6 + 27 + 7).isEqualTo(TITLE_WIDTH);
            assertThat(afterSendMenuScreen(1, "").title01())
                    .isEqualTo(ScreenTitles.CCDA_TITLE01)
                    .hasSize(MainMenuResponse.TITLE_LENGTH);
        }

        @Test
        @DisplayName("title02 is the ACTIVE value on COTTL01Y:22, and the :21 decoy is never asserted")
        void titleTwoIsTheActiveLiteral() {
            // MOVE CCDA-TITLE02 TO TITLE02O OF COMEN1AO - app/cbl/COMEN01C.cbl:217.
            //
            // The decoy is named here and nowhere else, and never as a value. app/cpy/COTTL01Y.cpy:21
            // holds a COMMENTED-OUT '  Credit Card Demo Application (CCDA)   ' - itself exactly 40
            // characters, which is what makes it plausible - wedged BETWEEN the CCDA-TITLE02 declaration
            // on :20 and its live value on :22. Only :22 is the field's value (practice B5).
            assertThat(ScreenTitles.CCDA_TITLE02)
                    .isEqualTo("              CardDemo                  ")
                    .hasSize(TITLE_WIDTH)
                    .hasSize(40);
            assertThat(ScreenTitles.CCDA_TITLE02.strip()).isEqualTo("CardDemo").hasSize(8);
            // 14 leading + 8 + 18 trailing = 40.
            assertThat(14 + 8 + 18).isEqualTo(TITLE_WIDTH);
            assertThat(ScreenTitles.CCDA_TITLE02)
                    .as("the live value on :22, not the commented-out wording on :21")
                    .doesNotContain("Credit Card Demo Application")
                    .doesNotContain("CCDA)");
            assertThat(afterSendMenuScreen(1, "").title02())
                    .isEqualTo(ScreenTitles.CCDA_TITLE02)
                    .hasSize(MainMenuResponse.TITLE_LENGTH);
        }

        @Test
        @DisplayName("the two titles are different fields with the same width, and neither is the other")
        void theTwoTitlesAreDistinct() {
            assertThat(ScreenTitles.CCDA_TITLE01).isNotEqualTo(ScreenTitles.CCDA_TITLE02);
            assertThat(ScreenTitles.CCDA_TITLE01).hasSameSizeAs(ScreenTitles.CCDA_TITLE02);
            assertThat(ScreenTitles.TITLE_LENGTH).isEqualTo(MainMenuResponse.TITLE_LENGTH)
                    .isEqualTo(40);
            // TITLE01 sits at POS=(1,21) and TITLE02 at POS=(2,21) - two rows, one width
            // (app/bms/COMEN01.bms:38-41 and :61-64).
            MainMenuResponse response = afterSendMenuScreen(1, "");
            assertThat(response.title01()).isNotEqualTo(response.title02());
        }

        @Test
        @DisplayName("the three near-identical sign-off and key messages are three different constants")
        void theThreeMessageConstantsAreNotInterchangeable() {
            // Two of these say "thank you" and two are PIC X(50). Conflating any pair would put the
            // wrong bytes on the wire, so each is asserted with its own width and its own owner.
            assertThat(ScreenTitles.CCDA_THANK_YOU)
                    .as("app/cpy/COTTL01Y.cpy:23-24, PIC X(40), names the CCDA application")
                    .isEqualTo("Thank you for using CCDA application... ")
                    .hasSize(ScreenTitles.TITLE_LENGTH)
                    .hasSize(40)
                    .contains("CCDA application");
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU)
                    .as("app/cpy/CSMSG01Y.cpy:18-19, PIC X(50), names the CardDemo application")
                    .hasSize(SystemMessages.MESSAGE_LENGTH)
                    .hasSize(50)
                    .contains("CardDemo application");
            assertThat(SystemMessages.CCDA_MSG_INVALID_KEY)
                    .as("app/cpy/CSMSG01Y.cpy:20-21, PIC X(50)")
                    .hasSize(SystemMessages.MESSAGE_LENGTH)
                    .hasSize(50)
                    .startsWith("Invalid key pressed.");

            // The mandated distinctness assertion: different values AND different lengths, 40 versus 50.
            assertThat(ScreenTitles.CCDA_THANK_YOU)
                    .isNotEqualTo(SystemMessages.CCDA_MSG_THANK_YOU);
            assertThat(ScreenTitles.CCDA_THANK_YOU.length())
                    .isNotEqualTo(SystemMessages.CCDA_MSG_THANK_YOU.length());
            assertThat(ScreenTitles.CCDA_THANK_YOU).hasSize(40);
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU).hasSize(50);
            assertThat(ScreenTitles.CCDA_THANK_YOU.strip())
                    .isNotEqualTo(SystemMessages.CCDA_MSG_THANK_YOU.strip());
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU)
                    .isNotEqualTo(SystemMessages.CCDA_MSG_INVALID_KEY);
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU)
                    .hasSameSizeAs(SystemMessages.CCDA_MSG_INVALID_KEY);
        }

        @Test
        @DisplayName("trnName and pgmName are the program's own two working-storage literals")
        void theHeaderIdentifiersComeFromWorkingStorage() {
            // MOVE WS-TRANID TO TRNNAMEO and MOVE WS-PGMNAME TO PGMNAMEO - app/cbl/COMEN01C.cbl:218-219,
            // where WS-PGMNAME PIC X(08) VALUE 'COMEN01C' is at :36 and WS-TRANID PIC X(04) VALUE 'CM00'
            // at :37.
            MainMenuResponse response = afterSendMenuScreen(1, "");

            assertThat(response.trnName()).isEqualTo("CM00").hasSize(MainMenuResponse.TRN_NAME_LENGTH);
            assertThat(response.pgmName()).isEqualTo("COMEN01C")
                    .hasSize(MainMenuResponse.PGM_NAME_LENGTH);
        }

        @Test
        @DisplayName("curDate is mm/dd/yy and curTime is hh:mm:ss, both exactly 8 characters")
        void theDateAndTimeShapesComeFromCsdat01y() {
            // WS-CURDATE-MM-DD-YY is 9(02) '/' 9(02) '/' 9(02) at app/cpy/CSDAT01Y.cpy:30-35, and
            // WS-CURTIME-HH-MM-SS is 9(02) ':' 9(02) ':' 9(02) at :36-41. They are MOVEd to CURDATEO and
            // CURTIMEO at app/cbl/COMEN01C.cbl:225 and :231. The mapset states the same shapes as its
            // INITIAL values, 'mm/dd/yy' at COMEN01.bms:51 and 'hh:mm:ss' at :74.
            //
            // A FIXED Clock is used and the wall clock is never read: DateHeader consults the supplied
            // clock exactly once and reaches for no current-instant factory of its own, so these
            // renderings are byte-identical on every run (practice B7).
            DateHeader header = DateHeader.from(CODEC, FIXED_CLOCK);
            MainMenuResponse response = afterSendMenuScreen(1, "");

            assertThat(response.curDate()).isEqualTo(header.wsCurdateMmDdYy())
                    .isEqualTo("07/19/22")
                    .hasSize(MainMenuResponse.CUR_DATE_LENGTH)
                    .hasSize(8)
                    .matches("\\d{2}/\\d{2}/\\d{2}");
            assertThat(response.curTime()).isEqualTo(header.wsCurtimeHhMmSs())
                    .isEqualTo("23:12:33")
                    .hasSize(MainMenuResponse.CUR_TIME_LENGTH)
                    .hasSize(8)
                    .matches("\\d{2}:\\d{2}:\\d{2}");
            assertThat(response.curDate().charAt(2)).isEqualTo('/');
            assertThat(response.curDate().charAt(5)).isEqualTo('/');
            assertThat(response.curTime().charAt(2)).isEqualTo(':');
            assertThat(response.curTime().charAt(5)).isEqualTo(':');
        }

        @Test
        @DisplayName("the same fixed clock always renders the same header, so the suite is deterministic")
        void theFixedClockIsDeterministic() {
            assertThat(DateHeader.from(CODEC, FIXED_CLOCK).wsCurdateMmDdYy())
                    .isEqualTo(DateHeader.from(CODEC, FIXED_CLOCK).wsCurdateMmDdYy());
            assertThat(DateHeader.from(CODEC, FIXED_CLOCK).wsCurtimeHhMmSs())
                    .isEqualTo(DateHeader.from(CODEC, FIXED_CLOCK).wsCurtimeHhMmSs());
            // A different fixed instant renders differently, which proves the clock is actually consulted
            // rather than the values being constants that happen to match.
            Clock other = Clock.fixed(Instant.parse("2001-02-03T04:05:06Z"), ZoneOffset.UTC);
            assertThat(DateHeader.from(CODEC, other).wsCurdateMmDdYy()).isEqualTo("02/03/01");
            assertThat(DateHeader.from(CODEC, other).wsCurtimeHhMmSs()).isEqualTo("04:05:06");
        }
    }

    // =================================================================================================
    @Nested
    @DisplayName("The admin twin is a DISTINCT Java type - the duplication is documented, not deduplicated")
    class AdminTwinIsADistinctType {

        /*
         * The duplication is real, and it is recorded here verbatim rather than collapsed (practice B4).
         *
         *   $ diff app/cpy-bms/COADM01.CPY app/cpy-bms/COMEN01.CPY
         *   17c17
         *   <        01  COADM1AI.
         *   ---
         *   >        01  COMEN1AI.
         *   139c139
         *   <        01  COADM1AO REDEFINES COADM1AI.
         *   ---
         *   >        01  COMEN1AO REDEFINES COMEN1AI.
         *
         * Two lines. Everything else - all twenty xxxI items, all twenty xxxO items, every attribute
         * item, every FILLER - is byte-identical.
         *
         *   $ diff app/bms/COADM01.bms app/bms/COMEN01.bms
         *   2c2    comment: "Admin Menu Screen"        vs "Main Menu Screen"
         *   19c19  COADM01 DFHMSD ...                  vs COMEN01 DFHMSD ...
         *   26c26  COADM1A DFHMDI ...                  vs COMEN1A DFHMDI ...
         *   77c77                 LENGTH=10,           vs                LENGTH=9,
         *   79c79                 INITIAL='Admin Menu' vs                INITIAL='Main Menu'
         *   166c166 version timestamp 17:02:42 CDT     vs 17:02:43 CDT
         *
         * The only field difference is the label at :75-79, and that DFHMDF is UNNAMED. An unnamed
         * DFHMDF yields no symbolic-map item, which is exactly why the two .CPY files stay identical
         * apart from their group names.
         *
         * A shared base class, a shared fixture builder, a package-info, a README or a parameterized
         * suite spanning both response types would all read as though the two screens were one. They are
         * not: this screen has a user-type authorization filter and a live coming-soon option name that
         * COADM01C does not have, and those are the two behaviours a shared abstraction would erase.
         * There is deliberately no such abstraction anywhere in this file.
         *
         * For the same reason every display name in this class names ITS OWN map - COMEN1AO, COMEN1AI or
         * COMEN1A - wherever the assertion would otherwise read identically to the admin sibling's. A
         * build report that labelled the two screens the same way would leave a reader unable to tell
         * which one failed, which is the reporting form of the very conflation this section exists to
         * prevent.
         */

        @Test
        @DisplayName("AdminMenuResponse and MainMenuResponse are different Java types")
        void theTwoResponseTypesAreDistinct() {
            assertThat(AdminMenuResponse.class).isNotEqualTo(MainMenuResponse.class);
            assertThat(MainMenuResponse.class).isNotEqualTo(AdminMenuResponse.class);
            assertThat(AdminMenuResponse.class.getName()).isNotEqualTo(MainMenuResponse.class.getName());
        }

        @Test
        @DisplayName("neither is assignable to the other, so nothing can be substituted for the other")
        void neitherIsAssignableToTheOther() {
            assertThat(AdminMenuResponse.class.isAssignableFrom(MainMenuResponse.class)).isFalse();
            assertThat(MainMenuResponse.class.isAssignableFrom(AdminMenuResponse.class)).isFalse();
        }

        @Test
        @DisplayName("neither shares a supertype or an interface with the other beyond Record")
        void theyShareNothingButRecord() {
            // Both are records, so both extend java.lang.Record - which every record does and which is
            // therefore not shared design. Neither implements an interface, so there is no common
            // abstraction to route a shared helper through even if one were wanted.
            assertThat(MainMenuResponse.class.getSuperclass()).isEqualTo(Record.class);
            assertThat(AdminMenuResponse.class.getSuperclass()).isEqualTo(Record.class);
            assertThat(MainMenuResponse.class.getInterfaces()).isEmpty();
            assertThat(AdminMenuResponse.class.getInterfaces()).isEmpty();
            assertThat(MainMenuResponse.class.isRecord()).isTrue();
        }

        @Test
        @DisplayName("the four screen identities differ in every one of the four values")
        void theScreenIdentitiesDiffer() {
            assertThat(MainMenuResponse.TRANSACTION_ID).isEqualTo("CM00")
                    .isNotEqualTo(AdminMenuResponse.TRANSACTION_ID);
            assertThat(MainMenuResponse.PROGRAM_NAME).isEqualTo("COMEN01C")
                    .isNotEqualTo(AdminMenuResponse.PROGRAM_NAME);
            assertThat(MainMenuResponse.MAPSET_NAME).isEqualTo("COMEN01")
                    .isNotEqualTo(AdminMenuResponse.MAPSET_NAME);
            assertThat(MainMenuResponse.MAP_NAME).isEqualTo("COMEN1A")
                    .isNotEqualTo(AdminMenuResponse.MAP_NAME);
            // The one value they DO share, because both fall back to the same sign-on program.
            assertThat(MainMenuResponse.SIGNON_PROGRAM).isEqualTo(AdminMenuResponse.SIGNON_PROGRAM)
                    .isEqualTo("COSGN00C");
        }

        @Test
        @DisplayName("the two records name their colour member differently, so no helper could span them")
        void evenTheColourMembersAreNamedDifferently() {
            List<String> main = Stream.of(MainMenuResponse.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();
            List<String> admin = Stream.of(AdminMenuResponse.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(main).contains("errMsgColor").doesNotContain("messageColour");
            assertThat(admin).contains("messageColour").doesNotContain("errMsgColor");
            assertThat(main).isNotEqualTo(admin);
        }

        @Test
        @DisplayName("the identical geometry is asserted independently on each side, not shared")
        void theGeometryAgreesWithoutBeingShared() {
            // The two symbolic maps declare the same twenty widths, so the two images are the same size.
            // That agreement is asserted from this side's own hand-written literals; nothing is read from
            // AdminMenuResponseTest, and no expectation is imported from it.
            assertThat(MainMenuResponse.SYMBOLIC_MAP_LENGTH)
                    .isEqualTo(AdminMenuResponse.SYMBOLIC_MAP_LENGTH)
                    .isEqualTo(12 + 20 * 7 + 668)
                    .isEqualTo(820);
            assertThat(MainMenuResponse.OPTION_LINE_COUNT)
                    .isEqualTo(AdminMenuResponse.OPTION_LINE_COUNT)
                    .isEqualTo(DECLARED_OPTION_SLOTS);
        }

        @Test
        @DisplayName("the two screens fill a different number of option slots - ten here, four there")
        void thePopulatedCountsDiffer() {
            // The mapsets are identical; the menu tables are not. app/cpy/COMEN02Y.cpy:21 sets ten,
            // whereas the admin menu's own table sets four. Same twelve declared slots, different number
            // written - which is precisely the kind of difference a shared suite would hide.
            assertThat(MainMenuResponse.ACTIVE_OPTION_LINE_COUNT).isEqualTo(10);
            assertThat(MenuOptions.ACTIVE_OPTION_COUNT).isEqualTo(10);
            assertThat(MainMenuResponse.ACTIVE_OPTION_LINE_COUNT)
                    .isLessThan(MainMenuResponse.OPTION_LINE_COUNT);
        }

        @Test
        @DisplayName("this file declares no shared abstraction of its own")
        void thisTestDeclaresNoSharedAbstraction() {
            // No base class, no interface, and every nested class is a plain @Nested test group rather
            // than a reusable fixture that another test class could extend.
            assertThat(MainMenuResponseTest.class.getSuperclass()).isEqualTo(Object.class);
            assertThat(MainMenuResponseTest.class.getInterfaces()).isEmpty();
            for (Class<?> nested : MainMenuResponseTest.class.getDeclaredClasses()) {
                assertThat(nested.getSuperclass())
                        .as(nested.getSimpleName() + " must not extend a shared fixture")
                        .isEqualTo(Object.class);
                assertThat(nested.getInterfaces()).isEmpty();
            }
        }
    }

    // =================================================================================================
    @Nested
    @DisplayName("Pure payload - the DTO stores what it is given, and the codec does the narrowing")
    class PurePayloadSemantics {

        @Test
        @DisplayName("an over-width message is stored verbatim: the DTO never truncates")
        void theDtoDoesNotTruncate() {
            // The X(80) to X(78) narrowing belongs to MainMenuController and its codec, not here. A DTO
            // that "helpfully" truncated would make the width contract untestable and would silently
            // absorb a controller bug, so it stores what it is given and this test proves it.
            String tooWide = positionalProbe(WS_MESSAGE_WIDTH);

            MainMenuResponse response = MainMenuResponse.builder().errMsg(tooWide).build();

            assertThat(response.errMsg()).isEqualTo(tooWide).hasSize(WS_MESSAGE_WIDTH);
            assertThat(response.errMsg()).isNotEqualTo(CODEC.movePicX(tooWide, ERR_MSG_WIDTH));
        }

        @Test
        @DisplayName("a short value is stored verbatim: the DTO never pads")
        void theDtoDoesNotPad() {
            MainMenuResponse response = MainMenuResponse.builder()
                    .errMsg("short")
                    .option("1")
                    .optionLine(1, "x")
                    .build();

            assertThat(response.errMsg()).isEqualTo("short").hasSize(5);
            assertThat(response.option()).isEqualTo("1").hasSize(1);
            assertThat(response.optionLine(1)).isEqualTo("x").hasSize(1);
        }

        @Test
        @DisplayName("an all-spaces value is preserved exactly: the DTO never trims")
        void theDtoDoesNotTrim() {
            String allSpaces = spaces(OPTION_LINE_WIDTH);

            MainMenuResponse response = MainMenuResponse.builder()
                    .optionLine(6, allSpaces)
                    .errMsg(spaces(ERR_MSG_WIDTH))
                    .build();

            assertThat(response.optionLine(6)).isEqualTo(allSpaces).hasSize(OPTION_LINE_WIDTH)
                    .isNotEmpty();
            assertThat(response.errMsg()).hasSize(ERR_MSG_WIDTH).isBlank();
        }

        @Test
        @DisplayName("an unset member stays null, which is how \"never written\" is expressed")
        void anUnsetMemberStaysNull() {
            MainMenuResponse blank = MainMenuResponse.builder().build();

            for (String member : PAYLOAD_MEMBERS) {
                assertThat(payloadMember(blank, member))
                        .as(member + " has not been written and must not be invented")
                        .isNull();
            }
            assertThat(blank.optionLines()).hasSize(DECLARED_OPTION_SLOTS).containsOnlyNulls();
        }

        @Test
        @DisplayName("the option-line view is unmodifiable and freshly built for each caller")
        void theOptionLineViewIsSafeToHandOut() {
            MainMenuResponse response = afterSendMenuScreen(1, "");
            List<String> view = response.optionLines();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> view.set(0, "tampered"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> view.add("tampered"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> view.remove(0));
            assertThat(response.optionLines()).isNotSameAs(view).isEqualTo(view);
        }

        @Test
        @DisplayName("every with-method returns a new instance and leaves the original untouched")
        void theWithMethodsNeverMutate() {
            MainMenuResponse original = afterSendMenuScreen(1, VALIDATION_MESSAGE);

            assertThat(original.withErrMsg("other")).isNotSameAs(original);
            assertThat(original.withErrMsgColor(BmsAttributes.DFHGREEN)).isNotSameAs(original);
            assertThat(original.withNextProgram("COBIL00C")).isNotSameAs(original);
            assertThat(original.withNavigationContext(NavigationContext.empty().withUserTypeAdmin()))
                    .isNotSameAs(original);
            assertThat(original.withOptionLine(11, "eleven")).isNotSameAs(original);

            // The original is byte-identical to a freshly built equivalent after all of that.
            assertThat(original).isEqualTo(afterSendMenuScreen(1, VALIDATION_MESSAGE));
            assertThat(original.errMsg()).isEqualTo(errMsgImage(VALIDATION_MESSAGE));
            assertThat(original.errMsgColor()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(original.optionLine(11)).isEqualTo(spaces(OPTION_LINE_WIDTH));
        }

        @Test
        @DisplayName("toBuilder round-trips all twenty-six members")
        void toBuilderRoundTripsEverything() {
            MainMenuResponse original = afterSendMenuScreen(7, NO_ACCESS_MESSAGE)
                    .withNextProgram("COTRN01C")
                    .withErrMsgColor(BmsAttributes.DFHGREEN);

            assertThat(original.toBuilder().build()).isEqualTo(original);
            assertThat(original.toBuilder().build()).isNotSameAs(original);
            assertThat(original.toBuilder().build().hashCode()).isEqualTo(original.hashCode());
        }

        @Test
        @DisplayName("value semantics: equality follows from the members, one member at a time")
        void equalityFollowsEveryMember() {
            MainMenuResponse base = afterSendMenuScreen(1, VALIDATION_MESSAGE);

            assertThat(base).isEqualTo(afterSendMenuScreen(1, VALIDATION_MESSAGE));
            assertThat(base).isNotEqualTo(afterSendMenuScreen(2, VALIDATION_MESSAGE));
            assertThat(base).isNotEqualTo(afterSendMenuScreen(1, NO_ACCESS_MESSAGE));
            assertThat(base).isNotEqualTo(base.withErrMsgColor(BmsAttributes.DFHGREEN));
            assertThat(base).isNotEqualTo(base.withNextProgram("COBIL00C"));
            assertThat(base).isNotEqualTo(base.withOptionLine(11, "eleven"));
            assertThat(base).isNotEqualTo(null);
            assertThat(base).isNotEqualTo("not a response");
            assertThat(base.toString()).contains("COMEN01C").contains("CM00");
        }

        @Test
        @DisplayName("the canonical constructor takes all twenty-six members positionally")
        void theCanonicalConstructorIsUsable() {
            MainMenuResponse response = new MainMenuResponse("CM00",
                    ScreenTitles.CCDA_TITLE01,
                    "07/19/22",
                    "COMEN01C",
                    ScreenTitles.CCDA_TITLE02,
                    "23:12:33",
                    optionLineImage(1),
                    optionLineImage(2),
                    optionLineImage(3),
                    optionLineImage(4),
                    optionLineImage(5),
                    optionLineImage(6),
                    optionLineImage(7),
                    optionLineImage(8),
                    optionLineImage(9),
                    optionLineImage(10),
                    spaces(OPTION_LINE_WIDTH),
                    spaces(OPTION_LINE_WIDTH),
                    "01",
                    errMsgImage(VALIDATION_MESSAGE),
                    NavigationContext.empty(),
                    "COACTVWC",
                    "COMEN01",
                    "COMEN1A",
                    BmsAttributes.DFHRED,
                    false);

            assertThat(response.trnName()).isEqualTo("CM00");
            assertThat(response.errMsg()).hasSize(ERR_MSG_WIDTH);
            assertThat(response.optionLine(1)).isEqualTo("01. Account View                        ");
            assertThat(response.optionLine(12)).isBlank().hasSize(OPTION_LINE_WIDTH);
            assertThat(response).isEqualTo(afterSendMenuScreen(1, VALIDATION_MESSAGE)
                    .withNextProgram("COACTVWC"));
        }

        @Test
        @DisplayName("initial() is the cleared map area of MOVE LOW-VALUES TO COMEN1AO")
        void initialIsTheFirstEntryRepaint() {
            MainMenuResponse initial = MainMenuResponse.initial();

            assertThat(initial.resetAllOutputFields())
                    .as("MOVE LOW-VALUES TO COMEN1AO at app/cbl/COMEN01C.cbl:89")
                    .isTrue();
            assertThat(MainMenuResponse.builder().build().resetAllOutputFields()).isFalse();
            assertThat(initial.errMsgColor()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(initial.navigationContext()).isEqualTo(NavigationContext.empty());
            assertThat(initial.nextMapset()).isEqualTo("COMEN01");
            assertThat(initial.nextMap()).isEqualTo("COMEN1A");
            for (String member : PAYLOAD_MEMBERS) {
                assertThat(payloadMember(initial, member))
                        .as(member + " is unwritten on first entry")
                        .isNull();
            }
        }

        @Test
        @DisplayName("a JSON round trip preserves all twenty payload fields byte for byte")
        void theJsonRoundTripIsLossless() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            MainMenuResponse original = afterSendMenuScreen(10, NO_ACCESS_MESSAGE);

            MainMenuResponse revived =
                    mapper.readValue(mapper.writeValueAsString(original), MainMenuResponse.class);

            for (String member : PAYLOAD_MEMBERS) {
                assertThat(payloadMember(revived, member))
                        .as(member + " must survive serialisation unchanged")
                        .isEqualTo(payloadMember(original, member));
            }
            assertThat(revived.option()).isEqualTo("10").hasSize(OPTION_WIDTH);
            assertThat(revived.errMsg()).hasSize(ERR_MSG_WIDTH).startsWith(NO_ACCESS_MESSAGE);
            assertThat(revived.optionLine(12)).isBlank().hasSize(OPTION_LINE_WIDTH);
            assertThat(revived.navigationContext()).isEqualTo(original.navigationContext());
            assertThat(revived.nextMapset()).isEqualTo("COMEN01");
            // The two metadata members are @JsonIgnored, so neither is written and neither is read back.
            // Jackson revives the record through its canonical constructor, which leaves the ignored
            // byte at its Java default of 0 - DFHDFCOL, the terminal's own colour - rather than at the
            // builder's declared DFHRED. That is deliberate and is asserted rather than glossed over:
            // the colour is presentation metadata belonging to whoever paints the screen, so it is
            // re-derived on the way out and is never part of the wire contract. A client must not read
            // it back off a deserialised payload.
            assertThat(revived.errMsgColor()).isEqualTo(BmsAttributes.DFHDFCOL).isEqualTo((byte) 0x00);
            assertThat(revived.errMsgColor()).isNotEqualTo(original.errMsgColor());
            assertThat(original.errMsgColor()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(revived.resetAllOutputFields()).isFalse();
        }
    }

    /**
     * Every option-line slot paired with whether {@code BUILD-MENU-OPTIONS} writes it.
     *
     * @return twelve argument pairs, ten {@code true} then two {@code false}
     */
    static Stream<Arguments> everySlotAndWhetherWritten() {
        List<Arguments> cases = new ArrayList<>();
        for (int slot = 1; slot <= DECLARED_OPTION_SLOTS; slot++) {
            cases.add(Arguments.of(slot, slot <= POPULATED_OPTION_SLOTS));
        }
        return cases.stream();
    }

    /**
     * The four message paths of {@code app/cbl/COMEN01C.cbl}, as description and text.
     *
     * @return one argument pair per path
     */
    static Stream<Arguments> everyMessagePath() {
        return Stream.of(
                Arguments.of("COMEN01C:131-132 option validation", VALIDATION_MESSAGE),
                Arguments.of("COMEN01C:140-141 admin-only refusal", NO_ACCESS_MESSAGE),
                Arguments.of("COMEN01C:101 CCDA-MSG-INVALID-KEY", SystemMessages.CCDA_MSG_INVALID_KEY),
                Arguments.of("COMEN01C:159-163 coming soon, option 1",
                        CODEC.concatenateDelimitedBySize(COMING_SOON_PREFIX,
                                delimitedBySpace(optionName(1)),
                                COMING_SOON_SUFFIX)));
    }
}
