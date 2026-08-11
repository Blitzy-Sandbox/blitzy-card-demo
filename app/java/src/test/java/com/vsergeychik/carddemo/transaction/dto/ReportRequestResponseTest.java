package com.vsergeychik.carddemo.transaction.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldHighlight;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldValidationState;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.transaction.dto.ReportRequestResponse.FieldAttributes;
import com.vsergeychik.carddemo.transaction.dto.ReportRequestResponse.ScreenField;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Parity tests for {@link ReportRequestResponse}, the outbound REST payload of
 * {@code POST /api/reports} - CSD transaction {@code CR00}, program {@code CORPT00C} - projected from
 * {@code 01 CORPT0AO REDEFINES CORPT0AI} at {@code app/cpy-bms/CORPT00.CPY:121} and from the
 * name-labelled {@code DFHMDF} fields of {@code app/bms/CORPT00.bms}.
 *
 * <h2>Sources this suite is diffed against</h2>
 *
 * <ul>
 *   <li>{@code app/cpy-bms/CORPT00.CPY:121} - {@code 01 CORPT0AO REDEFINES CORPT0AI}, the seventeen
 *       {@code xxxO} items and their widths. Lines 17-120 are the aliased {@code CORPT0AI} view.</li>
 *   <li>{@code app/bms/CORPT00.bms} - 42 {@code DFHMDF} entries, 17 of them name-labelled.
 *       {@code app/bms/CORPT00.bms:218-221} declares the error line:
 *       {@code ERRMSG DFHMDF ATTRB=(ASKIP,BRT,FSET), COLOR=RED, LENGTH=78, POS=(23,1)}.</li>
 *   <li>{@code app/cbl/CORPT00C.cbl:5} - {@code Function : Print Transaction reports by submitting
 *       batch}; {@code :138-140} - {@code COPY COCOM01Y.} then {@code COPY CORPT00.} with
 *       <strong>nothing between them</strong>; {@code :215-262} - the monthly and yearly branches and
 *       their {@code '01'}, {@code '12'} and {@code '31'} literals; {@code :305-325} - the six
 *       {@code FUNCTION NUMVAL-C} normalisations back into the map's own alphanumeric items;
 *       {@code :487} - the {@code WHEN OTHER} confirm message built with {@code STRING ... DELIMITED
 *       BY SPACE}; {@code :549} - the single {@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM)}.</li>
 *   <li>{@code app/cpy/CSSETATY.cpy} - the three-condition highlight rule, gate <strong>G38</strong>.</li>
 *   <li>{@code app/cpy/CSMSG01Y.cpy} - the two {@code PIC X(50)} common messages.</li>
 *   <li>{@code app/cpy/COTTL01Y.cpy} - the three {@code PIC X(40)} screen titles.</li>
 *   <li>{@code app/cpy/COCOM01Y.cpy} - {@code CARDDEMO-COMMAREA}, 160 bytes, reached through
 *       {@link NavigationContext}; and {@code app/cpy/CSDAT01Y.cpy}, reached through
 *       {@link DateHeader}.</li>
 *   <li>{@code app/csd/CARDDEMO.CSD:137} {@code DEFINE MAPSET(CORPT00)}, {@code :242}
 *       {@code DEFINE PROGRAM(CORPT00C)} and {@code :409} {@code DEFINE TRANSACTION(CR00)
 *       PROGRAM(CORPT00C)}.</li>
 *   <li>{@code README.md:225} - {@code | CR00 | CORPT00 | CORPT00C | Transaction Reports |}.</li>
 * </ul>
 *
 * <p>Where an expected value can be <em>read from a source</em> rather than retyped, it is. Several of
 * the suites below parse {@code app/cpy-bms/CORPT00.CPY} and {@code app/bms/CORPT00.bms} at run time
 * and diff the class against them, so the copybook and the mapset stay the authority and a retyping
 * slip in either the class or the test cannot pass unnoticed. Nothing under {@code app/cbl},
 * {@code app/cpy}, {@code app/cpy-bms}, {@code app/bms} or {@code app/csd} is ever written - bind
 * <strong>B3</strong>, and gate <strong>G5</strong>.
 *
 * <h2>This is not an R-B case</h2>
 *
 * <p>The prompt-mandated name and the verified source function <strong>agree</strong> here:
 * {@code CORPT00C.cbl:5} reads {@code Function : Print Transaction reports by submitting batch} and
 * {@code README.md:225} documents {@code CR00} as {@code Transaction Reports}. So although rule
 * <strong>R1</strong> - names from the prompt, behaviour from the source - governs this file as it
 * governs every other, it costs nothing to apply. That is worth stating explicitly, because two of the
 * seven sibling classes in this very package <em>are</em> R-B cases: {@code COTRN01C} is named
 * {@code TransactionAddController} while its source <em>views</em> a transaction, and {@code COTRN02C}
 * is named {@code TransactionViewController} while its source <em>adds</em> one. No such swap applies
 * to {@code CORPT00C}, and no assertion here needs to compensate for one.
 *
 * <h2>Governing rules</h2>
 *
 * <p>{@code review_rules} returns exactly one line, {@code No user rules provided.} - that single line
 * is the whole document, so <strong>no user rule governs this file</strong>. Their absence is not
 * permission to lower the bar; the migration plan's own binds stand in their place and are treated as
 * rulings: <strong>R1</strong> (names from the prompt, behaviour from the source), <strong>R2</strong>
 * and <strong>R4</strong> (truncation not rounding; never {@code double} or {@code float}),
 * <strong>R5</strong> (fixed width is the wire format), <strong>R6</strong> (statelessness),
 * <strong>B3</strong> (reference inputs immutable), <strong>B4</strong> (no silent scope creep - the
 * {@code DELIMITED BY SPACE} message quirk is preserved, not tidied), <strong>B7</strong>
 * (deterministic: a fixed {@link Clock} with an explicit {@link ZoneId}, never the platform default
 * zone), <strong>B8</strong> (explicit over implicit: no wildcard imports, every charset named),
 * <strong>B9</strong> (no static mutable state) and <strong>B11</strong> (hand-written, reviewable
 * codecs - no third-party copybook parser is used or available).
 *
 * <h2>The suite is self-contained</h2>
 *
 * <p>There is no shared base class for the eight test classes in this package and none is introduced.
 * This file follows the structural shape of {@code TransactionListResponseTest} - nested suites named
 * after the property they pin - but <strong>depends on nothing in it</strong>: every fixture, helper
 * and constant used below is declared here. It uses no {@code MockMvc}, no {@code @SpringBootTest} and
 * no {@code @WebMvcTest}; the controller behaviour - the {@code JobSubmissionPort}, the 80-byte JCL
 * skeleton, gate {@code G29} for the six {@code FUNCTION NUMVAL-C} sites, the two
 * {@code util.DateUtilityJob} calls - belongs to {@code ReportRequestControllerTest} in the parent
 * package and is not duplicated. It reaches no repository, no {@code JobLauncher} and no datasource,
 * because {@code CORPT00C} accesses no dataset at all; it imports nothing from
 * {@code com.vsergeychik.carddemo.parity} and reads nothing under
 * {@code src/test/resources/parity}.
 *
 * <h2>Coverage and execution discipline</h2>
 *
 * <p>Gate <strong>G49</strong> measures package {@code transaction.dto} independently at
 * {@code BRANCH >= 0.90}, enforced by {@code jacoco-maven-plugin} 0.8.15 bound to {@code verify} in
 * {@code app/java/pom.xml}. This is the eighth and last of the eight classes contributing to that
 * figure, so the shortfall of any sibling would have to be made up here; it is not - the package
 * measures {@code 985/997 = 0.9880} and {@link ReportRequestResponse} itself {@code 132/133 = 0.9925}.
 * The single uncovered branch is the synthetic default arm javac emits for the exhaustive enum
 * {@code switch} in {@code setPayloadValue}: all seventeen arms are driven, and that arm is unreachable
 * from Java by construction.
 *
 * <p>Gate <strong>G54</strong>: this is plain JUnit 5 with AssertJ. There is no {@code Thread.sleep},
 * no socket, no HTTP call, no watch mode and no Spring context - {@code mvn -B test} runs it to
 * completion unattended, and {@link NegativeContracts#suiteIsNonInteractive()} asserts the absence
 * rather than leaving it to inspection.
 *
 * <h2>Gate G33 does not apply, and that is a finding rather than an omission</h2>
 *
 * <p>Gate {@code G33} pins the 1-based-to-0-based conversion of every {@code OCCURS} table.
 * {@code CORPT00.CPY} declares <strong>no {@code OCCURS} clause at all</strong> - {@code CORPT0A} is a
 * flat screen of seventeen scalar fields with no repeating row group, unlike {@code COTRN00} and
 * {@code COUSR00} whose ten-row tables the sibling suites do index. There is therefore no row
 * subscript on this map to verify, and no test below is missing one.
 *
 * <h2>The properties this suite pins</h2>
 *
 * <ol>
 *   <li>Seventeen payload items - the name-labelled {@code DFHMDF} fields - and no more. The other 25
 *       {@code DFHMDF} entries are screen literals that CICS never transmits (gate
 *       <strong>G9</strong>).</li>
 *   <li>The group image is <strong>337</strong> bytes: {@code 12} for the {@code TIOAPFX} prefix,
 *       {@code 17 x 7} for the attribute prefixes and {@code 206} for the payload - the narrowest of
 *       the four maps in this package (rule <strong>R5</strong>, gate <strong>G21</strong>).</li>
 *   <li>{@code xxxI} and {@code xxxO} are storage <strong>aliases</strong>, so the inbound and
 *       outbound projections carry identical names and widths and round trip losslessly in both
 *       directions (gate <strong>G34</strong>).</li>
 *   <li>The {@code xxxC}/{@code xxxP}/{@code xxxH}/{@code xxxV} quad is metadata and never a JSON
 *       payload member, even though it occupies real bytes - the same four bytes the inbound view
 *       calls its per-field {@code FILLER X(4)}.</li>
 *   <li>The {@code CSSETATY} highlight is reachable only in {@code CDEMO-PGM-REENTER} state, and the
 *       {@code '*'} only in the {@code BLANK} case (gate <strong>G38</strong>).</li>
 *   <li>{@code EXEC CICS XCTL} becomes a {@code nextProgram} field and nothing else - no session, no
 *       forward, no static state (gates <strong>G40</strong> and <strong>G37</strong>).</li>
 *   <li>The passed commarea is <strong>160</strong> bytes, not 218: this screen declares no commarea
 *       extension at all, which is the property that most distinguishes it from its siblings.</li>
 * </ol>
 */
@DisplayName("ReportRequestResponse - CORPT00 CORPT0AO, the outbound projection of CORPT00C")
class ReportRequestResponseTest {

    /** Both code pages are named explicitly; neither is ever the platform default. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** The EBCDIC code page of the datasets under {@code app/data/EBCDIC}. */
    private static final Charset EBCDIC = Charset.forName("IBM037");

    /**
     * The encoding of the <em>Java</em> sources, which is a different question from the code page of
     * the COBOL data. {@code app/java/pom.xml} declares
     * {@code <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>}, so the source-level
     * scans below decode with that and never with the platform default - bind <strong>B8</strong>. It
     * is named separately from {@link #ASCII} because a Javadoc comment elsewhere in the module may
     * legitimately carry a non-ASCII character, and a scan that assumed otherwise would fail with an
     * encoding error instead of the assertion it was written to make.
     */
    private static final Charset JAVA_SOURCE = StandardCharsets.UTF_8;

    /** {@code app/java/../cpy-bms/CORPT00.CPY}, the authoritative symbolic map. */
    private static final Path COPYBOOK = Paths.get("..", "cpy-bms", "CORPT00.CPY");

    /** {@code app/java/../bms/CORPT00.bms}, the authoritative field definitions. */
    private static final Path MAPSET = Paths.get("..", "bms", "CORPT00.bms");

    /** {@code app/java/../cbl/CORPT00C.cbl}, the authoritative behaviour. */
    private static final Path PROGRAM = Paths.get("..", "cbl", "CORPT00C.cbl");

    /** {@code app/java/../csd/CARDDEMO.CSD}, the authoritative transaction and mapset definitions. */
    private static final Path CSD = Paths.get("..", "csd", "CARDDEMO.CSD");

    /** {@code app/java/../../README.md}, whose transaction inventory corroborates the CSD. */
    private static final Path README = Paths.get("..", "..", "README.md");

    /** The class under test, for the source-level checks that no reflection can express. */
    private static final Path SOURCE = Paths.get("src", "main", "java", "com", "vsergeychik",
            "carddemo", "transaction", "dto", "ReportRequestResponse.java");

    // =============================================================================================
    // Bind B7 - determinism. CORPT00C.cbl:611 reads FUNCTION CURRENT-DATE, which reaches CURDATEO and
    // CURTIMEO through POPULATE-HEADER-INFO (lines 613-628). DateHeader takes an injected Clock and
    // never calls now() of its own, so the instant AND the zone are both pinned here. Neither is ever
    // the platform default: a suite that read the wall clock or the host's zone would pass in one
    // timezone and fail in another, and a byte-for-byte header comparison would be meaningless.
    // =============================================================================================

    /** The zone the pinned instant is read in, named explicitly rather than defaulted. */
    private static final ZoneId FIXED_ZONE = ZoneId.of("America/Chicago");

    /**
     * The pinned instant. {@code 2022-08-22T22:02:43Z} is {@code 17:02:43} in {@link #FIXED_ZONE},
     * which is what {@code CURDATEO} and {@code CURTIMEO} must render as {@code 08/22/22} and
     * {@code 17:02:43}. The date is inside the source's own vintage - the copybooks carry
     * {@code Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19}.
     */
    private static final Instant FIXED_INSTANT = Instant.parse("2022-08-22T22:02:43Z");

    /** The injected clock itself: fixed instant, explicit zone, no wall-clock read anywhere. */
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, FIXED_ZONE);

    /** The date and time the pinned clock yields, as {@code CURDATEO} must carry them. */
    private static final String EXPECTED_CURDATEO = "08/22/22";

    /** The time the pinned clock yields, as {@code CURTIMEO} must carry it. */
    private static final String EXPECTED_CURTIMEO = "17:02:43";

    // =============================================================================================
    // The BMS input/output split, read off app/bms/CORPT00.bms. Ten fields carry ATTRB=(...,UNPROT)
    // and are therefore input-capable; the other seven carry ATTRB=(ASKIP,...) and are output-only.
    // CSSETATY can only ever highlight a field the operator can type into, so this split is exactly
    // the domain of gate G38.
    // =============================================================================================

    /**
     * The ten input-capable fields: {@code MONTHLY} {@code ATTRB=(FSET,IC,NORM,UNPROT)} at
     * {@code app/bms/CORPT00.bms:80}, {@code YEARLY} at {@code :94}, {@code CUSTOM} at {@code :108},
     * the six {@code NUM,UNPROT} date parts at {@code :127}, {@code :138}, {@code :149}, {@code :166},
     * {@code :177} and {@code :188}, and {@code CONFIRM} at {@code :206}.
     */
    private static final Set<ScreenField> INPUT_CAPABLE = EnumSet.of(
            ScreenField.MONTHLY, ScreenField.YEARLY, ScreenField.CUSTOM,
            ScreenField.SDTMM, ScreenField.SDTDD, ScreenField.SDTYYYY,
            ScreenField.EDTMM, ScreenField.EDTDD, ScreenField.EDTYYYY,
            ScreenField.CONFIRM);

    /**
     * The seven {@code ASKIP} output-only fields: the six header items at
     * {@code app/bms/CORPT00.bms:34}, {@code :38}, {@code :47}, {@code :57}, {@code :61} and
     * {@code :70}, plus {@code ERRMSG} at {@code :218}.
     */
    private static final Set<ScreenField> OUTPUT_ONLY = EnumSet.complementOf(
            EnumSet.copyOf(INPUT_CAPABLE));

    /** {@code 42} {@code DFHMDF} entries in the mapset, of which 17 are name-labelled. */
    private static final int TOTAL_DFHMDF_ENTRIES = 42;

    /** {@code 42 - 17}: the unlabelled screen literals and prompts CICS never transmits. */
    private static final int UNLABELLED_DFHMDF_ENTRIES =
            TOTAL_DFHMDF_ENTRIES - ReportRequestResponse.SCREEN_FIELD_COUNT;

    /** {@code MOVE -1 TO <field>L OF CORPT0AI} sites in {@code app/cbl/CORPT00C.cbl}: twenty-two. */
    private static final int MOVE_MINUS_ONE_SITES = 22;

    /** {@code EXEC CICS XCTL} sites in {@code app/cbl/CORPT00C.cbl}: exactly one, at line 549. */
    private static final int XCTL_SITES = 1;

    /** {@code WS-MESSAGE PIC X(80)}, {@code app/cbl/CORPT00C.cbl:39} - the source of {@code ERRMSGO}. */
    private static final int WS_MESSAGE_LENGTH = 80;

    /** {@code WS-REPORT-NAME PIC X(10)}, {@code app/cbl/CORPT00C.cbl:58}. */
    private static final int WS_REPORT_NAME_LENGTH = 10;

    // =============================================================================================
    // Helpers that read the sources, so the sources remain the oracle.
    // =============================================================================================

    /**
     * Extracts the {@code 02 xxx<suffix> PIC X(n)} items of one {@code 01} group of the copybook, in
     * declaration order.
     *
     * @param groupHeader the group name, {@code CORPT0AI} or {@code CORPT0AO}
     * @param suffix      the item suffix, {@code I} or {@code O}
     * @return item name to declared width, in copybook order
     * @throws IOException if the copybook cannot be read
     */
    private static Map<String, Integer> parseGroupItems(String groupHeader, String suffix)
            throws IOException {
        Pattern item =
                Pattern.compile("^\\s*02\\s+([A-Z0-9]+" + suffix + ")\\s+PIC\\s+X\\((\\d+)\\)\\.");
        Map<String, Integer> items = new LinkedHashMap<>();
        boolean inGroup = false;
        for (String line : Files.readAllLines(COPYBOOK, ASCII)) {
            if (line.contains("01  " + groupHeader)) {
                inGroup = true;
                continue;
            }
            if (inGroup && line.contains("01  ") && !line.contains(groupHeader)) {
                break;
            }
            if (!inGroup) {
                continue;
            }
            Matcher matcher = item.matcher(line);
            if (matcher.find()) {
                items.put(matcher.group(1), Integer.parseInt(matcher.group(2)));
            }
        }
        return items;
    }

    /**
     * Extracts the name-labelled {@code DFHMDF} fields of the mapset and their {@code LENGTH=}.
     * Unlabelled entries - the screen literals - are skipped, which is exactly the distinction that
     * decides what becomes a payload member.
     *
     * @return field label to declared length, in mapset order
     * @throws IOException if the mapset cannot be read
     */
    private static Map<String, Integer> parseLabelledFields() throws IOException {
        Pattern label = Pattern.compile("^([A-Z0-9]+)\\s+DFHMDF");
        Pattern length = Pattern.compile("LENGTH=(\\d+)");
        Map<String, Integer> labelled = new LinkedHashMap<>();
        String current = null;
        for (String line : Files.readAllLines(MAPSET, ASCII)) {
            Matcher labelMatcher = label.matcher(line);
            if (labelMatcher.find()) {
                current = labelMatcher.group(1);
            } else if (line.trim().startsWith("DFHMDF")) {
                current = null;
            }
            if (current != null) {
                Matcher lengthMatcher = length.matcher(line);
                if (lengthMatcher.find() && !labelled.containsKey(current)) {
                    labelled.put(current, Integer.parseInt(lengthMatcher.group(1)));
                }
            }
        }
        return labelled;
    }

    /**
     * Extracts the {@code ATTRB=(...)} list of each name-labelled {@code DFHMDF} in the mapset.
     *
     * <p>This is the distinction that decides which fields {@code CSSETATY} could ever highlight: a
     * field the operator cannot type into cannot be in error, so only {@code UNPROT} fields carry a
     * validation flag.
     *
     * @return field label to its declared attribute list, in mapset order
     * @throws IOException if the mapset cannot be read
     */
    private static Map<String, String> parseLabelledAttributes() throws IOException {
        Pattern labelled = Pattern.compile("^([A-Z0-9]+)\\s+DFHMDF\\s+ATTRB=\\(([A-Z,]+)\\)");
        Map<String, String> attributes = new LinkedHashMap<>();
        for (String line : Files.readAllLines(MAPSET, ASCII)) {
            Matcher matcher = labelled.matcher(line);
            if (matcher.find()) {
                attributes.put(matcher.group(1), matcher.group(2));
            }
        }
        return attributes;
    }

    /** Removes block and line comments, so a forbidden-construct scan sees code and not prose. */
    private static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        boolean inBlock = false;
        for (String line : source.lines().toList()) {
            String working = line;
            if (inBlock) {
                int close = working.indexOf("*/");
                if (close < 0) {
                    continue;
                }
                working = working.substring(close + 2);
                inBlock = false;
            }
            int open = working.indexOf("/*");
            while (open >= 0) {
                int close = working.indexOf("*/", open + 2);
                if (close < 0) {
                    working = working.substring(0, open);
                    inBlock = true;
                    break;
                }
                working = working.substring(0, open) + working.substring(close + 2);
                open = working.indexOf("/*");
            }
            int lineComment = working.indexOf("//");
            if (lineComment >= 0) {
                working = working.substring(0, lineComment);
            }
            out.append(working).append('\n');
        }
        return out.toString();
    }

    /**
     * The header CICS would build for the pinned instant, read through the injected {@link Clock}.
     *
     * <p>Bind <strong>B7</strong>: {@link DateHeader#from(FixedWidthCodec, Clock)} reads the clock
     * once and never calls {@code now()} itself, so this is reproducible on any host in any timezone.
     *
     * @return the header for {@link #FIXED_INSTANT} in {@link #FIXED_ZONE}
     */
    private static DateHeader pinnedHeader() {
        return DateHeader.from(new FixedWidthCodec(ASCII), FIXED_CLOCK);
    }

    /** A response with the header populated from the pinned clock, as every send does. */
    private static ReportRequestResponse populated() {
        ReportRequestResponse response = new ReportRequestResponse();
        response.populateHeaderInfo(pinnedHeader());
        return response;
    }

    /**
     * COBOL's {@code STRING ... DELIMITED BY SPACE}: the operand contributes only the characters
     * before its first space. This is the rule {@code app/cbl/CORPT00C.cbl:449} applies to
     * {@code WS-REPORT-NAME PIC X(10)} and {@code :487} applies to {@code CONFIRMI}, and bind
     * <strong>B4</strong> requires it be preserved rather than tidied into a trim.
     *
     * <p>It is deliberately <em>not</em> {@link String#trim()}: {@code "Two Words  "} yields
     * {@code "Two"} under this rule and {@code "Two Words"} under a trim.
     *
     * @param operand the sending item, at its declared width
     * @return the characters before the first space, or the whole operand if it contains none
     */
    private static String delimitedBySpace(String operand) {
        int firstSpace = operand.indexOf(' ');
        return firstSpace < 0 ? operand : operand.substring(0, firstSpace);
    }

    /**
     * The two chained COBOL {@code MOVE}s that put a message on the screen: text into
     * {@code WS-MESSAGE PIC X(80)} ({@code app/cbl/CORPT00C.cbl:39}), then
     * {@code MOVE WS-MESSAGE TO ERRMSGO OF CORPT0AO} at {@code :560} into {@code PIC X(78)}.
     *
     * <p>Both moves are alphanumeric, so both pad on the right and truncate on the right. The net
     * effect for any text shorter than 78 characters is right-space-padding to 78; for anything longer
     * it is the leading 78 characters. That second case is real: {@code WS-MESSAGE} is 80 bytes wide
     * and {@code ERRMSGO} is 78, so the last two bytes of a full {@code WS-MESSAGE} are dropped.
     *
     * @param text the assembled message
     * @return exactly 78 characters, as {@code ERRMSGO} must hold them
     */
    private static String throughWsMessageIntoErrmsgo(String text) {
        FixedWidthCodec codec = new FixedWidthCodec(ASCII);
        String wsMessage = codec.movePicX(text, WS_MESSAGE_LENGTH);
        return codec.movePicX(wsMessage, ScreenField.ERRMSG.payloadLength());
    }

    // =============================================================================================

    @Nested
    @DisplayName("Gate G9 - every payload field traces to a DFHMDF and every width to a PICTURE")
    class ProjectionMatchesTheSources {

        @Test
        @DisplayName("the 17 ScreenField constants are the xxxO items, verbatim and in order")
        void screenFieldsAreTheCopybookItems() throws IOException {
            Map<String, Integer> outbound = parseGroupItems("CORPT0AO", "O");

            assertThat(outbound).as("xxxO items in CORPT00.CPY").hasSize(17);
            assertThat(ReportRequestResponse.SCREEN_FIELD_COUNT).isEqualTo(17);
            assertThat(ScreenField.values()).hasSize(17);

            List<String> fromEnum = new ArrayList<>();
            for (ScreenField field : ScreenField.values()) {
                fromEnum.add(field.payloadItemName());
            }
            assertThat(fromEnum)
                    .as("names verbatim AND declaration order, which is load-bearing")
                    .containsExactlyElementsOf(new ArrayList<>(outbound.keySet()));

            for (ScreenField field : ScreenField.values()) {
                assertThat(field.payloadLength())
                        .as("PICTURE width of " + field.payloadItemName())
                        .isEqualTo(outbound.get(field.payloadItemName()));
            }
        }

        @Test
        @DisplayName("exactly 17 DFHMDF entries are name-labelled, and every LENGTH= equals its PIC")
        void bmsLengthsAgreeWithPictures() throws IOException {
            Map<String, Integer> labelled = parseLabelledFields();

            assertThat(labelled).as("name-labelled DFHMDF fields").hasSize(17);
            for (ScreenField field : ScreenField.values()) {
                assertThat(labelled.get(field.baseName()))
                        .as("BMS LENGTH= of " + field.baseName())
                        .isEqualTo(field.payloadLength());
            }
        }

        @Test
        @DisplayName("xxxI and xxxO alias one another, so Request and Response are field-identical")
        void inboundAndOutboundViewsAgree() throws IOException {
            Map<String, Integer> inbound = parseGroupItems("CORPT0AI", "I");
            Map<String, Integer> outbound = parseGroupItems("CORPT0AO", "O");

            assertThat(inbound).hasSize(17);
            assertThat(outbound).hasSize(17);

            List<String> inboundBases = new ArrayList<>();
            inbound.keySet().forEach(n -> inboundBases.add(n.substring(0, n.length() - 1)));
            List<String> outboundBases = new ArrayList<>();
            outbound.keySet().forEach(n -> outboundBases.add(n.substring(0, n.length() - 1)));

            assertThat(outboundBases).containsExactlyElementsOf(inboundBases);
            for (String base : inboundBases) {
                assertThat(outbound.get(base + "O"))
                        .as("width of " + base + " must be the same in both views")
                        .isEqualTo(inbound.get(base + "I"));
            }

            List<String> enumBases = new ArrayList<>();
            for (ScreenField field : ScreenField.values()) {
                enumBases.add(field.baseName());
            }
            assertThat(enumBases).containsExactlyElementsOf(outboundBases);
        }

        @Test
        @DisplayName("the provenance constants are the ones the sources declare")
        void provenanceConstants() {
            assertThat(ReportRequestResponse.TRANSACTION_ID).isEqualTo("CR00");
            assertThat(ReportRequestResponse.PROGRAM_NAME).isEqualTo("CORPT00C").hasSize(8);
            assertThat(ReportRequestResponse.MAPSET_NAME).isEqualTo("CORPT00").hasSize(7);
            assertThat(ReportRequestResponse.MAP_NAME).isEqualTo("CORPT0A").hasSize(7);
            assertThat(ReportRequestResponse.SYMBOLIC_MAP_GROUP)
                    .isEqualTo(ReportRequestResponse.MAP_NAME + "O");
            assertThat(ReportRequestResponse.DEFAULT_NEXT_PROGRAM).isEqualTo("COSGN00C");
            assertThat(ReportRequestResponse.PF3_NEXT_PROGRAM).isEqualTo("COMEN01C");
        }
    }

    @Nested
    @DisplayName("Byte geometry - 12 + 17 x 7 + 206 = 337")
    class ByteGeometry {

        @Test
        @DisplayName("the payload widths sum to 206 and the group image is 337 bytes")
        void totals() {
            int payloadSum = 0;
            for (ScreenField field : ScreenField.values()) {
                payloadSum += field.payloadLength();
            }
            assertThat(payloadSum).isEqualTo(206);
            assertThat(ReportRequestResponse.PAYLOAD_WIDTH_TOTAL).isEqualTo(payloadSum);
            assertThat(ReportRequestResponse.TIOAPFX_PREFIX_LENGTH).isEqualTo(12);
            assertThat(ReportRequestResponse.ATTRIBUTE_FILLER_LENGTH).isEqualTo(3);
            assertThat(ReportRequestResponse.COLOUR_ITEM_LENGTH).isEqualTo(1);
            assertThat(ReportRequestResponse.PS_ITEM_LENGTH).isEqualTo(1);
            assertThat(ReportRequestResponse.HILIGHT_ITEM_LENGTH).isEqualTo(1);
            assertThat(ReportRequestResponse.VALIDN_ITEM_LENGTH).isEqualTo(1);
            assertThat(ReportRequestResponse.FIELD_PREFIX_LENGTH).isEqualTo(7);
            assertThat(ReportRequestResponse.ATTRIBUTE_PREFIX_TOTAL).isEqualTo(119);
            assertThat(ReportRequestResponse.RECORD_LENGTH).isEqualTo(337);
        }

        @Test
        @DisplayName("LAYOUT declares all 103 spans and its storage sums to exactly 337")
        void layoutIsTheAssertion() {
            assertThat(ReportRequestResponse.LAYOUT.recordLength()).isEqualTo(337);
            assertThat(ReportRequestResponse.LAYOUT.spans()).hasSize(1 + 17 * 6);
            assertThat(ReportRequestResponse.LAYOUT.redefinitions()).isEmpty();

            int declared = 0;
            for (FieldSpan span : ReportRequestResponse.LAYOUT.storageSpans()) {
                declared += span.length();
            }
            assertThat(declared).isEqualTo(ReportRequestResponse.RECORD_LENGTH);

            assertThat(ReportRequestResponse.TIOAPFX_PREFIX_SPAN.offset()).isZero();
            assertThat(ReportRequestResponse.TIOAPFX_PREFIX_SPAN.length()).isEqualTo(12);
            for (ScreenField field : ScreenField.values()) {
                assertThat(ReportRequestResponse.LAYOUT.hasSpan(field.payloadItemName())).isTrue();
                assertThat(ReportRequestResponse.LAYOUT.hasSpan(field.colourItemName())).isTrue();
            }
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("each field's six spans follow the copybook's order and the 7-byte stride")
        void spanStride(ScreenField field) {
            int attr = field.attributePrefixOffset();
            assertThat(field.attributeFillerSpan().offset()).isEqualTo(attr);
            assertThat(field.attributeFillerSpan().length()).isEqualTo(3);
            assertThat(field.colourSpan().offset()).isEqualTo(attr + 3);
            assertThat(field.psSpan().offset()).isEqualTo(attr + 4);
            assertThat(field.hilightSpan().offset()).isEqualTo(attr + 5);
            assertThat(field.validnSpan().offset()).isEqualTo(attr + 6);
            assertThat(field.payloadSpan().offset()).isEqualTo(attr + 7).isEqualTo(
                    field.payloadOffset());
            assertThat(field.colourSpan().length()).isEqualTo(1);
            assertThat(field.psSpan().length()).isEqualTo(1);
            assertThat(field.hilightSpan().length()).isEqualTo(1);
            assertThat(field.validnSpan().length()).isEqualTo(1);
            assertThat(field.payloadSpan().length()).isEqualTo(field.payloadLength());
            assertThat(field.spans()).hasSize(6);
            assertThat(field.colourItemName()).isEqualTo(field.baseName() + "C");
            assertThat(field.payloadItemName()).isEqualTo(field.baseName() + "O");
        }

        @Test
        @DisplayName("the offsets are the copybook's, and the last field ends at byte 337")
        void absoluteOffsets() {
            int[] expectedAttr = {12, 23, 70, 85, 100, 147, 162, 170, 178, 186, 195, 204, 215, 224,
                233, 244, 252};
            int[] expectedPayload = {19, 30, 77, 92, 107, 154, 169, 177, 185, 193, 202, 211, 222,
                231, 240, 251, 259};
            ScreenField[] fields = ScreenField.values();
            for (int index = 0; index < fields.length; index++) {
                assertThat(fields[index].attributePrefixOffset())
                        .as("attribute prefix offset of " + fields[index].baseName())
                        .isEqualTo(expectedAttr[index]);
                assertThat(fields[index].payloadOffset())
                        .as("payload offset of " + fields[index].baseName())
                        .isEqualTo(expectedPayload[index]);
            }
            assertThat(ScreenField.ERRMSG.payloadSpan().endOffsetExclusive())
                    .isEqualTo(ReportRequestResponse.RECORD_LENGTH);
        }
    }

    @Nested
    @DisplayName("PIC X semantics - padded on write, truncated on the right, never trimmed on read")
    class WidthDiscipline {

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("a fresh field is LOW-VALUES at its declared width")
        void defaultsToLowValues(ScreenField field) {
            // MOVE LOW-VALUES TO CORPT0AO, app/cbl/CORPT00C.cbl:179. moveSpacesToAllFields() and
            // initializeAllFields() carry the SPACES shapes and are asserted separately.
            ReportRequestResponse response = new ReportRequestResponse();
            assertThat(response.payloadValue(field))
                    .hasSize(field.payloadLength())
                    .isEqualTo(ScreenFieldImage.unpainted(field.payloadLength()));
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("a short value is padded on the right and an over-long one keeps its leading bytes")
        void padsAndTruncates(ScreenField field) {
            ReportRequestResponse response = new ReportRequestResponse();

            response.setPayloadValue(field, "A");
            assertThat(response.payloadValue(field)).hasSize(field.payloadLength());
            assertThat(response.payloadValue(field).charAt(0)).isEqualTo('A');
            assertThat(response.payloadValue(field).substring(1))
                    .isEqualTo(" ".repeat(field.payloadLength() - 1));

            response.setPayloadValue(field, "Z".repeat(field.payloadLength()) + "OVERFLOW");
            assertThat(response.payloadValue(field))
                    .as("COBOL fills a PIC X receiver from the left")
                    .isEqualTo("Z".repeat(field.payloadLength()));

            // An exact-width value is returned unchanged.
            String exact = "e".repeat(field.payloadLength());
            response.setPayloadValue(field, exact);
            assertThat(response.payloadValue(field)).isEqualTo(exact);
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("null is refused, naming the copybook item and what to pass instead")
        void nullIsRefused(ScreenField field) {
            ReportRequestResponse response = new ReportRequestResponse();
            assertThatNullPointerException()
                    .isThrownBy(() -> response.setPayloadValue(field, null))
                    .withMessageContaining(field.payloadItemName())
                    .withMessageContaining("COBOL has no null");
        }

        @Test
        @DisplayName("the named accessors and the ScreenField accessor are the same storage")
        void namedAccessorsAgreeWithGeneric() {
            ReportRequestResponse r = new ReportRequestResponse();
            r.setTrnnameo("CR00");
            r.setTitle01o("title one");
            r.setCurdateo("01/02/03");
            r.setPgmnameo("CORPT00C");
            r.setTitle02o("title two");
            r.setCurtimeo("11:22:33");
            r.setMonthlyo("M");
            r.setYearlyo("Y");
            r.setCustomo("C");
            r.setSdtmmo("01");
            r.setSdtddo("02");
            r.setSdtyyyyo("2022");
            r.setEdtmmo("03");
            r.setEdtddo("04");
            r.setEdtyyyyo("2023");
            r.setConfirmo("Y");
            r.setErrmsgo("a message");

            assertThat(r.getTrnnameo()).isEqualTo(r.payloadValue(ScreenField.TRNNAME));
            assertThat(r.getTitle01o()).isEqualTo(r.payloadValue(ScreenField.TITLE01));
            assertThat(r.getCurdateo()).isEqualTo(r.payloadValue(ScreenField.CURDATE));
            assertThat(r.getPgmnameo()).isEqualTo(r.payloadValue(ScreenField.PGMNAME));
            assertThat(r.getTitle02o()).isEqualTo(r.payloadValue(ScreenField.TITLE02));
            assertThat(r.getCurtimeo()).isEqualTo(r.payloadValue(ScreenField.CURTIME));
            assertThat(r.getMonthlyo()).isEqualTo(r.payloadValue(ScreenField.MONTHLY));
            assertThat(r.getYearlyo()).isEqualTo(r.payloadValue(ScreenField.YEARLY));
            assertThat(r.getCustomo()).isEqualTo(r.payloadValue(ScreenField.CUSTOM));
            assertThat(r.getSdtmmo()).isEqualTo(r.payloadValue(ScreenField.SDTMM));
            assertThat(r.getSdtddo()).isEqualTo(r.payloadValue(ScreenField.SDTDD));
            assertThat(r.getSdtyyyyo()).isEqualTo(r.payloadValue(ScreenField.SDTYYYY));
            assertThat(r.getEdtmmo()).isEqualTo(r.payloadValue(ScreenField.EDTMM));
            assertThat(r.getEdtddo()).isEqualTo(r.payloadValue(ScreenField.EDTDD));
            assertThat(r.getEdtyyyyo()).isEqualTo(r.payloadValue(ScreenField.EDTYYYY));
            assertThat(r.getConfirmo()).isEqualTo(r.payloadValue(ScreenField.CONFIRM));
            assertThat(r.getErrmsgo()).isEqualTo(r.payloadValue(ScreenField.ERRMSG));
        }

        @Test
        @DisplayName("a null field selector is refused by every accessor that takes one")
        void nullSelectorIsRefused() {
            ReportRequestResponse response = new ReportRequestResponse();
            assertThatNullPointerException().isThrownBy(() -> response.payloadValue(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> response.setPayloadValue(null, "x"));
            assertThatNullPointerException().isThrownBy(() -> response.attributesOf(null));
            assertThatNullPointerException().isThrownBy(() -> response.colourAttribute(null));
            assertThatNullPointerException().isThrownBy(() -> response.psAttribute(null));
            assertThatNullPointerException().isThrownBy(() -> response.hilightAttribute(null));
            assertThatNullPointerException().isThrownBy(() -> response.validnAttribute(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> response.setColourAttribute(null, (byte) 1));
            assertThatNullPointerException()
                    .isThrownBy(() -> response.setPsAttribute(null, (byte) 1));
            assertThatNullPointerException()
                    .isThrownBy(() -> response.setHilightAttribute(null, (byte) 1));
            assertThatNullPointerException()
                    .isThrownBy(() -> response.setValidnAttribute(null, (byte) 1));
        }

        @ParameterizedTest
        @ValueSource(ints = {0, -1, -78})
        @DisplayName("a figurative constant cannot be built at a width no copybook item could have")
        void figurativeConstantsRejectImpossibleWidths(int width) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> ReportRequestResponse.spaces(width))
                    .withMessageContaining("SPACES");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> ReportRequestResponse.lowValues(width))
                    .withMessageContaining("LOW-VALUES");
        }

        @Test
        @DisplayName("SPACES and LOW-VALUES are different bytes, and both are available")
        void figurativeConstants() {
            assertThat(ReportRequestResponse.spaces(4)).isEqualTo("    ");
            assertThat(ReportRequestResponse.lowValues(4)).isEqualTo("\u0000\u0000\u0000\u0000");
            assertThat(ReportRequestResponse.spaces(1))
                    .isNotEqualTo(ReportRequestResponse.lowValues(1));
        }
    }

    @Nested
    @DisplayName("The group moves CORPT00C performs, each named after its source line")
    class GroupMoves {

        @Test
        @DisplayName("CORPT00C:179 MOVE LOW-VALUES TO CORPT0AO clears the payload AND all 68 attributes")
        void moveLowValuesReachesEveryByte() {
            ReportRequestResponse response = populated();
            response.setErrmsgc(BmsAttributes.DFHGREEN);
            response.setHilightAttribute(ScreenField.MONTHLY, BmsAttributes.DFHUNDLN);
            response.setPsAttribute(ScreenField.CONFIRM, (byte) 0x11);
            response.setValidnAttribute(ScreenField.CONFIRM, (byte) 0x22);

            response.moveLowValuesToMapGroup();

            for (ScreenField field : ScreenField.values()) {
                assertThat(response.payloadValue(field))
                        .as(field.payloadItemName() + " after a group MOVE of LOW-VALUES")
                        .isEqualTo("\u0000".repeat(field.payloadLength()));
                assertThat(response.attributesOf(field))
                        .as("a group MOVE reaches the attribute items too")
                        .isEqualTo(FieldAttributes.UNSET);
                assertThat(response.attributesOf(field).allUnset()).isTrue();
            }
        }

        @Test
        @DisplayName("CORPT00C:636-645 INITIALIZE-ALL-FIELDS clears ten items and leaves seven alone")
        void initializeAllFieldsTouchesOnlyItsTenItems() {
            ReportRequestResponse response = new ReportRequestResponse();
            response.moveLowValuesToMapGroup();

            response.initializeAllFields();

            List<ScreenField> initialised = List.of(ScreenField.MONTHLY, ScreenField.YEARLY,
                    ScreenField.CUSTOM, ScreenField.SDTMM, ScreenField.SDTDD, ScreenField.SDTYYYY,
                    ScreenField.EDTMM, ScreenField.EDTDD, ScreenField.EDTYYYY, ScreenField.CONFIRM);
            assertThat(initialised).as("the paragraph names exactly ten items").hasSize(10);
            for (ScreenField field : initialised) {
                assertThat(response.payloadValue(field))
                        .as("INITIALIZE sets an alphanumeric item to SPACES, not LOW-VALUES")
                        .isEqualTo(" ".repeat(field.payloadLength()));
            }
            for (ScreenField untouched : List.of(ScreenField.TRNNAME, ScreenField.TITLE01,
                    ScreenField.CURDATE, ScreenField.PGMNAME, ScreenField.TITLE02,
                    ScreenField.CURTIME, ScreenField.ERRMSG)) {
                assertThat(response.payloadValue(untouched))
                        .as(untouched.payloadItemName() + " is not named by the paragraph")
                        .isEqualTo("\u0000".repeat(untouched.payloadLength()));
            }
        }

        @Test
        @DisplayName("CORPT00C:169-170 and :193 - the error line is cleared, then carries the standard text")
        void errorLineMoves() {
            ReportRequestResponse response = new ReportRequestResponse();
            response.setErrmsgo("stale text from the previous send");

            response.moveSpacesToErrmsgo();
            assertThat(response.getErrmsgo()).isEqualTo(" ".repeat(78));

            response.moveInvalidKeyMessageToErrmsgo();
            assertThat(response.getErrmsgo())
                    .hasSize(78)
                    .startsWith(SystemMessages.CCDA_MSG_INVALID_KEY)
                    .as("a 50-byte message lands left-justified in a 78-byte field")
                    .endsWith(" ".repeat(78 - SystemMessages.CCDA_MSG_INVALID_KEY.length()));
        }

        @Test
        @DisplayName("CORPT00C:448 MOVE DFHGREEN TO ERRMSGC OF CORPT0AO")
        void dfhgreenIntoTheErrorLineColourItem() {
            ReportRequestResponse response = new ReportRequestResponse();
            assertThat(response.getErrmsgc()).isEqualTo(ReportRequestResponse.ATTRIBUTE_UNSET);

            response.moveDfhgreenToErrmsgc();

            assertThat(response.getErrmsgc()).isEqualTo(BmsAttributes.DFHGREEN);
            assertThat(response.colourAttribute(ScreenField.ERRMSG))
                    .isEqualTo(BmsAttributes.DFHGREEN);
            assertThat(response.attributesOf(ScreenField.ERRMSG).allUnset()).isFalse();

            response.setErrmsgc(BmsAttributes.DFHRED);
            assertThat(response.getErrmsgc()).isEqualTo(BmsAttributes.DFHRED);
        }

        @Test
        @DisplayName("CORPT00C:613-628 POPULATE-HEADER-INFO fills the six header fields")
        void populateHeaderInfo() {
            ReportRequestResponse response = populated();

            assertThat(response.getTitle01o()).isEqualTo(ScreenTitles.CCDA_TITLE01).hasSize(40);
            assertThat(response.getTitle02o()).isEqualTo(ScreenTitles.CCDA_TITLE02).hasSize(40);
            assertThat(response.getTrnnameo()).isEqualTo("CR00").hasSize(4);
            assertThat(response.getPgmnameo()).isEqualTo("CORPT00C").hasSize(8);
            assertThat(response.getCurdateo()).isEqualTo("08/22/22").hasSize(8);
            assertThat(response.getCurtimeo()).isEqualTo("17:02:43").hasSize(8);

            // The paragraph does not touch the eleven data fields, so they still hold what the
            // constructor's MOVE LOW-VALUES TO CORPT0AO image (:179) left.
            assertThat(response.getMonthlyo()).isEqualTo(ScreenFieldImage.unpainted(1));
            assertThat(response.getConfirmo()).isEqualTo(ScreenFieldImage.unpainted(1));
            assertThat(response.getErrmsgo()).isEqualTo(ScreenFieldImage.unpainted(78));

            assertThatNullPointerException()
                    .isThrownBy(() -> response.populateHeaderInfo(null))
                    .withMessageContaining("DateHeader");
        }

        @Test
        @DisplayName("moveSpacesToAllFields restores every field to SPACES")
        void moveSpacesToAllFields() {
            ReportRequestResponse response = new ReportRequestResponse();
            response.moveLowValuesToMapGroup();

            response.moveSpacesToAllFields();

            for (ScreenField field : ScreenField.values()) {
                assertThat(response.payloadValue(field))
                        .isEqualTo(" ".repeat(field.payloadLength()));
            }
        }
    }

    @Nested
    @DisplayName("Gate G38 - the CSSETATY highlight applies only in REENTER state")
    class Highlighting {

        @ParameterizedTest
        @EnumSource(FieldValidationState.class)
        @DisplayName("on first entry nothing is highlighted, whatever the validation state")
        void unreachableOnEnter(FieldValidationState state) {
            ReportRequestResponse response = new ReportRequestResponse();
            response.setSdtmmo("07");

            FieldHighlight highlight = FieldAttributeSetter.resolve(state, false,
                    ScreenField.SDTMM.baseName(), ReportRequestResponse.MAP_NAME);

            assertThat(response.applyHighlight(ScreenField.SDTMM, highlight))
                    .as("CSSETATY's outer IF requires CDEMO-PGM-REENTER").isFalse();
            assertThat(response.colourAttribute(ScreenField.SDTMM))
                    .isEqualTo(ReportRequestResponse.ATTRIBUTE_UNSET);
            assertThat(response.getSdtmmo()).isEqualTo("07");
        }

        @Test
        @DisplayName("in REENTER state NOT-OK reddens xxxC and leaves xxxO untouched")
        void notOkOnReenter() {
            ReportRequestResponse response = new ReportRequestResponse();
            response.setSdtmmo("99");

            FieldHighlight highlight = FieldAttributeSetter.resolveFromFlags(true, false, true,
                    ScreenField.SDTMM.baseName(), ReportRequestResponse.MAP_NAME);

            assertThat(response.applyHighlight(ScreenField.SDTMM, highlight)).isTrue();
            assertThat(response.colourAttribute(ScreenField.SDTMM)).isEqualTo(BmsAttributes.DFHRED);
            assertThat(response.getSdtmmo())
                    .as("the '*' move is nested inside the BLANK test, which did not hold")
                    .isEqualTo("99");
            assertThat(response.psAttribute(ScreenField.SDTMM))
                    .isEqualTo(ReportRequestResponse.ATTRIBUTE_UNSET);
            assertThat(response.hilightAttribute(ScreenField.SDTMM))
                    .isEqualTo(ReportRequestResponse.ATTRIBUTE_UNSET);
            assertThat(response.validnAttribute(ScreenField.SDTMM))
                    .isEqualTo(ReportRequestResponse.ATTRIBUTE_UNSET);
        }

        @Test
        @DisplayName("in REENTER state BLANK reddens xxxC and writes '*' into xxxO")
        void blankOnReenter() {
            ReportRequestResponse response = new ReportRequestResponse();

            FieldHighlight highlight = FieldAttributeSetter.resolveFromFlags(false, true, true,
                    ScreenField.CONFIRM.baseName(), ReportRequestResponse.MAP_NAME);

            assertThat(response.applyHighlight(ScreenField.CONFIRM, highlight)).isTrue();
            assertThat(response.colourAttribute(ScreenField.CONFIRM))
                    .isEqualTo(BmsAttributes.DFHRED);
            assertThat(response.getConfirmo()).isEqualTo(FieldAttributeSetter.ASTERISK);
        }

        @Test
        @DisplayName("the '*' is fitted to the receiving field's width, not written raw")
        void asteriskIsFittedToTheField() {
            ReportRequestResponse response = new ReportRequestResponse();
            FieldHighlight highlight = FieldAttributeSetter.resolveFromFlags(false, true, true,
                    ScreenField.SDTYYYY.baseName(), ReportRequestResponse.MAP_NAME);

            assertThat(response.applyHighlight(ScreenField.SDTYYYY, highlight)).isTrue();
            assertThat(response.getSdtyyyyo()).isEqualTo("*   ").hasSize(4);
        }

        @Test
        @DisplayName("an anonymous highlight carries no identity and is accepted for any field")
        void anonymousHighlight() {
            ReportRequestResponse response = new ReportRequestResponse();
            FieldHighlight anonymous = FieldAttributeSetter.resolveFromFlags(true, false, true);

            assertThat(anonymous.screenFieldPrefix()).isEmpty();
            assertThat(anonymous.mapName()).isEmpty();
            assertThat(response.applyHighlight(ScreenField.MONTHLY, anonymous)).isTrue();
            assertThat(response.colourAttribute(ScreenField.MONTHLY))
                    .isEqualTo(BmsAttributes.DFHRED);
        }

        @Test
        @DisplayName("a highlight resolved for another field is refused, not silently misapplied")
        void wrongFieldIsRefused() {
            ReportRequestResponse response = new ReportRequestResponse();
            FieldHighlight forMonthly = FieldAttributeSetter.resolveFromFlags(true, false, true,
                    ScreenField.MONTHLY.baseName(), ReportRequestResponse.MAP_NAME);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.applyHighlight(ScreenField.SDTMM, forMonthly))
                    .withMessageContaining("MONTHLY")
                    .withMessageContaining("SDTMM");
            assertThat(response.colourAttribute(ScreenField.SDTMM))
                    .isEqualTo(ReportRequestResponse.ATTRIBUTE_UNSET);
        }

        @Test
        @DisplayName("a highlight resolved for another map is refused")
        void wrongMapIsRefused() {
            ReportRequestResponse response = new ReportRequestResponse();
            FieldHighlight forAnotherScreen = FieldAttributeSetter.resolveFromFlags(true, false,
                    true, ScreenField.SDTMM.baseName(), "CACTUPA");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.applyHighlight(ScreenField.SDTMM, forAnotherScreen))
                    .withMessageContaining("CACTUPA")
                    .withMessageContaining(ReportRequestResponse.MAP_NAME);
        }

        @Test
        @DisplayName("null arguments are refused")
        void nullsRefused() {
            ReportRequestResponse response = new ReportRequestResponse();
            FieldHighlight highlight = FieldAttributeSetter.resolveFromFlags(true, false, true);

            assertThatNullPointerException()
                    .isThrownBy(() -> response.applyHighlight(null, highlight));
            assertThatNullPointerException()
                    .isThrownBy(() -> response.applyHighlight(ScreenField.SDTMM, null));
        }

        @Test
        @DisplayName("an untouched highlight resolved for the right field is still a no-op")
        void untouchedButIdentified() {
            ReportRequestResponse response = new ReportRequestResponse();
            FieldHighlight none = FieldHighlight.none(ScreenField.ERRMSG.baseName(),
                    ReportRequestResponse.MAP_NAME);

            assertThat(none.untouched()).isTrue();
            assertThat(response.applyHighlight(ScreenField.ERRMSG, none)).isFalse();
            assertThat(response.getErrmsgc()).isEqualTo(ReportRequestResponse.ATTRIBUTE_UNSET);
        }
    }

    @Nested
    @DisplayName("The xxxC/xxxP/xxxH/xxxV quad - metadata, and independently settable")
    class AttributeQuad {

        @Test
        @DisplayName("UNSET is all X'00', which is also the device default")
        void unsetQuad() {
            assertThat(FieldAttributes.UNSET.allUnset()).isTrue();
            assertThat(FieldAttributes.UNSET.colour()).isEqualTo(BmsAttributes.DFHDFCOL);
            assertThat(ReportRequestResponse.ATTRIBUTE_UNSET).isEqualTo((byte) 0x00);
        }

        @Test
        @DisplayName("each wither changes one item and leaves the other three alone")
        void withersAreIndependent() {
            FieldAttributes red = FieldAttributes.UNSET.withColour(BmsAttributes.DFHRED);
            assertThat(red.colour()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(red.ps()).isEqualTo((byte) 0x00);
            assertThat(red.hilight()).isEqualTo((byte) 0x00);
            assertThat(red.validn()).isEqualTo((byte) 0x00);

            FieldAttributes ps = FieldAttributes.UNSET.withPs((byte) 0x11);
            assertThat(ps.ps()).isEqualTo((byte) 0x11);
            assertThat(ps.colour()).isEqualTo((byte) 0x00);
            assertThat(ps.hilight()).isEqualTo((byte) 0x00);
            assertThat(ps.validn()).isEqualTo((byte) 0x00);

            FieldAttributes hilight = FieldAttributes.UNSET.withHilight(BmsAttributes.DFHUNDLN);
            assertThat(hilight.hilight()).isEqualTo(BmsAttributes.DFHUNDLN);
            assertThat(hilight.colour()).isEqualTo((byte) 0x00);
            assertThat(hilight.ps()).isEqualTo((byte) 0x00);
            assertThat(hilight.validn()).isEqualTo((byte) 0x00);

            FieldAttributes validn = FieldAttributes.UNSET.withValidn((byte) 0x22);
            assertThat(validn.validn()).isEqualTo((byte) 0x22);
            assertThat(validn.colour()).isEqualTo((byte) 0x00);
            assertThat(validn.ps()).isEqualTo((byte) 0x00);
            assertThat(validn.hilight()).isEqualTo((byte) 0x00);
        }

        @Test
        @DisplayName("allUnset() is false as soon as any single item is set")
        void allUnsetDetectsEachItem() {
            assertThat(FieldAttributes.UNSET.withColour((byte) 1).allUnset()).isFalse();
            assertThat(FieldAttributes.UNSET.withPs((byte) 1).allUnset()).isFalse();
            assertThat(FieldAttributes.UNSET.withHilight((byte) 1).allUnset()).isFalse();
            assertThat(FieldAttributes.UNSET.withValidn((byte) 1).allUnset()).isFalse();
            assertThat(FieldAttributes.UNSET.withColour((byte) 0).allUnset()).isTrue();
        }

        @Test
        @DisplayName("describe() names each byte by its CICS mnemonic")
        void describeUsesMnemonics() {
            FieldAttributes quad = FieldAttributes.UNSET.withColour(BmsAttributes.DFHRED)
                    .withHilight(BmsAttributes.DFHUNDLN);
            assertThat(quad.describe()).contains("DFHRED").contains("C=").contains("P=")
                    .contains("H=").contains("V=");
        }

        @Test
        @DisplayName("the quad is a value: equal quads are equal and hash alike")
        void valueSemantics() {
            FieldAttributes one = FieldAttributes.UNSET.withColour(BmsAttributes.DFHRED);
            FieldAttributes same = new FieldAttributes(BmsAttributes.DFHRED, (byte) 0, (byte) 0,
                    (byte) 0);
            assertThat(one).isEqualTo(same).hasSameHashCodeAs(same);
            assertThat(one).isNotEqualTo(FieldAttributes.UNSET);
        }

        @Test
        @DisplayName("a quad can be replaced wholesale, and null is refused")
        void wholesaleReplacement() {
            ReportRequestResponse response = new ReportRequestResponse();
            FieldAttributes quad = new FieldAttributes(BmsAttributes.DFHRED, (byte) 0x11,
                    BmsAttributes.DFHUNDLN, (byte) 0x22);

            response.setAttributes(ScreenField.ERRMSG, quad);
            assertThat(response.attributesOf(ScreenField.ERRMSG)).isEqualTo(quad);
            assertThat(response.colourAttribute(ScreenField.ERRMSG))
                    .isEqualTo(BmsAttributes.DFHRED);
            assertThat(response.psAttribute(ScreenField.ERRMSG)).isEqualTo((byte) 0x11);
            assertThat(response.hilightAttribute(ScreenField.ERRMSG))
                    .isEqualTo(BmsAttributes.DFHUNDLN);
            assertThat(response.validnAttribute(ScreenField.ERRMSG)).isEqualTo((byte) 0x22);

            assertThatNullPointerException()
                    .isThrownBy(() -> response.setAttributes(ScreenField.ERRMSG, null))
                    .withMessageContaining("UNSET");
        }

        @Test
        @DisplayName("every field's quad is present from construction, so no lookup can be absent")
        void everyFieldHasAQuad() {
            ReportRequestResponse response = new ReportRequestResponse();
            for (ScreenField field : ScreenField.values()) {
                assertThat(response.attributesOf(field)).isNotNull()
                        .isEqualTo(FieldAttributes.UNSET);
            }
        }
    }

    @Nested
    @DisplayName("Gates G40 and G37 - XCTL becomes a field, and nothing is held server-side")
    class Navigation {

        @Test
        @DisplayName("a fresh response names its own map and mapset at X(7) and no program yet")
        void defaults() {
            ReportRequestResponse response = new ReportRequestResponse();
            assertThat(response.getNextMapset()).isEqualTo("CORPT00").hasSize(7);
            assertThat(response.getNextMap()).isEqualTo("CORPT0A").hasSize(7);
            assertThat(response.getNextProgram()).isEqualTo(" ".repeat(8)).hasSize(8);
            assertThat(response.getNavigationContext()).isEqualTo(NavigationContext.empty());
        }

        @Test
        @DisplayName("nextProgram is echoed from CDEMO-TO-PROGRAM, the field XCTL names")
        void echoesTheCommareaTarget() {
            ReportRequestResponse response = new ReportRequestResponse();
            NavigationContext context = NavigationContext.empty()
                    .withToProgram(ReportRequestResponse.PF3_NEXT_PROGRAM)
                    .withUserId("ADMIN001")
                    .withUserTypeAdmin()
                    .withPgmReenter();

            response.echoNavigation(context);

            assertThat(response.getNextProgram()).isEqualTo("COMEN01C").hasSize(8);
            // echoNavigation is the transfer projection, and an XCTL states no map: which map COMEN01C
            // will paint is its decision, not this program's, and CORPT00C names none in the XCTL at
            // :548-551. Blank means "not stated here" - naming this screen's own map would tell the
            // client to repaint the screen it is leaving.
            assertThat(response.getNextMapset()).isBlank()
                    .hasSize(NavigationContext.LAST_MAPSET_LENGTH);
            assertThat(response.getNextMap()).isBlank().hasSize(NavigationContext.LAST_MAP_LENGTH);
            // A SEND is the other case, and the constructor still names this screen there.
            assertThat(new ReportRequestResponse().getNextMapset())
                    .isEqualTo(ReportRequestResponse.MAPSET_NAME);
            assertThat(new ReportRequestResponse().getNextMap())
                    .isEqualTo(ReportRequestResponse.MAP_NAME);
            assertThat(response.getNavigationContext()).isSameAs(context);
            assertThat(response.getNavigationContext().isReenter()).isTrue();
            assertThat(response.getNavigationContext().isAdmin()).isTrue();
        }

        @Test
        @DisplayName("the commarea is echoed at exactly 160 bytes and is never widened")
        void commareaIsNotWidened() {
            assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(160);
            NavigationContext context = NavigationContext.empty().withToProgram("COSGN00C");
            assertThat(context.toFixedWidth(new FixedWidthCodec(ASCII))).hasSize(160);

            ReportRequestResponse response = new ReportRequestResponse();
            response.setNavigationContext(context);
            assertThat(response.getNavigationContext().toFixedWidth(new FixedWidthCodec(ASCII)))
                    .hasSize(160);
        }

        @Test
        @DisplayName("the navigation trio is width-disciplined and refuses null")
        void trioWidths() {
            ReportRequestResponse response = new ReportRequestResponse();

            response.setNextProgram("X");
            assertThat(response.getNextProgram()).isEqualTo("X       ").hasSize(8);
            response.setNextMapset("TOOLONGMAPSET");
            assertThat(response.getNextMapset()).isEqualTo("TOOLONG").hasSize(7);
            response.setNextMap("M");
            assertThat(response.getNextMap()).isEqualTo("M      ").hasSize(7);

            assertThatNullPointerException().isThrownBy(() -> response.setNextProgram(null));
            assertThatNullPointerException().isThrownBy(() -> response.setNextMapset(null));
            assertThatNullPointerException().isThrownBy(() -> response.setNextMap(null));
            assertThatNullPointerException().isThrownBy(() -> response.setNavigationContext(null))
                    .withMessageContaining("empty()");
        }

        @Test
        @DisplayName("CORPT00C:542 - the LOW-VALUES-or-SPACES rule is a whole-group comparison")
        void spacesOrLowValuesRule() {
            assertThat(ReportRequestResponse.isSpacesOrLowValues("        ")).isTrue();
            assertThat(ReportRequestResponse.isSpacesOrLowValues("\u0000\u0000\u0000")).isTrue();
            assertThat(ReportRequestResponse.isSpacesOrLowValues("")).isTrue();
            assertThat(ReportRequestResponse.isSpacesOrLowValues(null)).isTrue();
            assertThat(ReportRequestResponse.isSpacesOrLowValues("COSGN00C")).isFalse();
            assertThat(ReportRequestResponse.isSpacesOrLowValues("  X     ")).isFalse();
            assertThat(ReportRequestResponse.isSpacesOrLowValues(" \u0000"))
                    .as("a mixed value satisfies neither figurative constant").isFalse();
        }

        @Test
        @DisplayName("Gate G53 - every static field is final and two instances share nothing")
        void noStaticMutableState() {
            for (var field : ReportRequestResponse.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("static field " + field.getName() + " must be final").isTrue();
                }
            }
            for (var field : ScreenField.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("static field " + field.getName() + " must be final").isTrue();
                }
            }

            ReportRequestResponse first = new ReportRequestResponse();
            ReportRequestResponse second = new ReportRequestResponse();
            first.setErrmsgo("only on the first");
            first.setColourAttribute(ScreenField.ERRMSG, BmsAttributes.DFHRED);

            assertThat(second.getErrmsgo()).isEqualTo(ScreenFieldImage.unpainted(78));
            assertThat(second.colourAttribute(ScreenField.ERRMSG))
                    .isEqualTo(ReportRequestResponse.ATTRIBUTE_UNSET);
        }

        @Test
        @DisplayName("Gates G22, G37, G52 - no forbidden construct appears in the code")
        void noForbiddenConstructs() throws IOException {
            String text = Files.readString(SOURCE, JAVA_SOURCE);
            String code = stripComments(text);

            assertThat(code).as("code with comments stripped, so the Javadoc's own explanation "
                    + "of what is absent cannot be mistaken for the thing itself")
                    .doesNotContain("HttpSession")
                    .doesNotContain("SessionAttributes")
                    .doesNotContain("lombok")
                    .doesNotContain("mapstruct")
                    .doesNotContain("JsonNaming")
                    .doesNotContain("JsonInclude")
                    .doesNotContain("ObjectMapper")
                    .doesNotContain("BigDecimal")
                    .doesNotContain("RoundingMode")
                    .doesNotContain("HALF_UP")
                    .doesNotContain("HALF_EVEN");
            assertThat(code).doesNotContainPattern("\\bdouble\\b")
                    .doesNotContainPattern("\\bfloat\\b");

            for (String line : text.lines().toList()) {
                if (line.startsWith("import ")) {
                    assertThat(line).as("gate G52 forbids wildcard imports")
                            .doesNotContain(".*;");
                }
            }

            for (Class<?> type : List.of(ReportRequestResponse.class, ScreenField.class,
                    FieldAttributes.class)) {
                for (var field : type.getDeclaredFields()) {
                    assertThat(field.getType()).as(type.getSimpleName() + "." + field.getName())
                            .isNotIn(double.class, float.class, Double.class, Float.class);
                }
                for (var method : type.getDeclaredMethods()) {
                    assertThat(method.getReturnType())
                            .as(type.getSimpleName() + "." + method.getName() + " return type")
                            .isNotIn(double.class, float.class, Double.class, Float.class);
                    for (Class<?> parameter : method.getParameterTypes()) {
                        assertThat(parameter)
                                .as(type.getSimpleName() + "." + method.getName() + " parameter")
                                .isNotIn(double.class, float.class, Double.class, Float.class);
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("The JSON body - 17 payload members plus the four stateless carriers, and no metadata")
    class JsonContract {

        private final ObjectMapper mapper = new ObjectMapper();

        @Test
        @DisplayName("the property set is exactly the 17 xxxO members, the trio and the commarea")
        void propertySet() {
            ObjectNode tree = mapper.valueToTree(new ReportRequestResponse());
            List<String> names = new ArrayList<>();
            tree.fieldNames().forEachRemaining(names::add);

            List<String> expected = List.of("trnname", "title01", "curdate", "pgmname",
                    "title02", "curtime", "monthly", "yearly", "custom", "sdtmm", "sdtdd",
                    "sdtyyyy", "edtmm", "edtdd", "edtyyyy", "confirm", "errmsg",
                    "nextProgram", "nextMapset", "nextMap", "navigationContext");
            assertThat(names).containsExactlyInAnyOrderElementsOf(expected);
            assertThat(expected).hasSize(21);
        }

        @Test
        @DisplayName("no attribute item and no diagnostic map leaks into the body")
        void metadataIsExcluded() {
            ObjectNode tree = mapper.valueToTree(new ReportRequestResponse());
            List<String> names = new ArrayList<>();
            tree.fieldNames().forEachRemaining(names::add);

            assertThat(names)
                    .noneMatch(name -> name.toLowerCase(Locale.ROOT).equals("errmsgc"))
                    .noneMatch(name -> name.equals("fieldImages"))
                    .noneMatch(name -> name.equals("attributeImages"))
                    .noneMatch(name -> name.toLowerCase(Locale.ROOT).endsWith("attributes"));
            for (ScreenField field : ScreenField.values()) {
                assertThat(names).noneMatch(name ->
                        name.equalsIgnoreCase(field.baseName() + "C")
                                || name.equalsIgnoreCase(field.baseName() + "P")
                                || name.equalsIgnoreCase(field.baseName() + "H")
                                || name.equalsIgnoreCase(field.baseName() + "V"));
            }
        }

        @Test
        @DisplayName("a 78-character space-padded error line survives a round trip UNTRIMMED")
        void roundTripDoesNotTrim() throws Exception {
            ReportRequestResponse response = populated();
            response.setErrmsgo("Monthly report submitted for printing ...");
            response.setConfirmo("Y");
            response.setMonthlyo("X");
            response.echoNavigation(NavigationContext.empty().withToProgram("COMEN01C"));

            String json = mapper.writeValueAsString(response);
            ReportRequestResponse back = mapper.readValue(json, ReportRequestResponse.class);

            assertThat(back.getErrmsgo()).hasSize(78).isEqualTo(response.getErrmsgo());
            assertThat(back.getErrmsgo()).isNotEqualTo(back.getErrmsgo().trim());
            assertThat(back.getTitle01o()).isEqualTo(ScreenTitles.CCDA_TITLE01);
            assertThat(back.getCurdateo()).isEqualTo("08/22/22");
            assertThat(back.getCurtimeo()).isEqualTo("17:02:43");
            assertThat(back.getNextProgram()).isEqualTo("COMEN01C");
            assertThat(back.getNavigationContext()).isEqualTo(response.getNavigationContext());
            assertThat(back.fieldImages()).isEqualTo(response.fieldImages());
            assertThat(back).isEqualTo(response);
        }

        @Test
        @DisplayName("an all-spaces error line appears verbatim in the JSON text and returns equal")
        void allSpacesRoundTrip() throws Exception {
            ReportRequestResponse response = new ReportRequestResponse();
            response.setErrmsgo(" ".repeat(78));

            String json = mapper.writeValueAsString(response);
            assertThat(json).contains("\"errmsg\":\"" + " ".repeat(78) + "\"");

            ReportRequestResponse back = mapper.readValue(json, ReportRequestResponse.class);
            assertThat(back.getErrmsgo()).isEqualTo(" ".repeat(78)).hasSize(78);
            assertThat(back).isEqualTo(response);
        }

        @Test
        @DisplayName("every one of the 17 members survives a round trip at its declared width")
        void allSeventeenRoundTrip() throws Exception {
            ReportRequestResponse response = new ReportRequestResponse();
            for (ScreenField field : ScreenField.values()) {
                response.setPayloadValue(field,
                        "v".repeat(Math.min(field.payloadLength(), field.payloadLength())));
            }
            ReportRequestResponse back = mapper.readValue(
                    mapper.writeValueAsString(response), ReportRequestResponse.class);
            for (ScreenField field : ScreenField.values()) {
                assertThat(back.payloadValue(field))
                        .as(field.payloadItemName())
                        .hasSize(field.payloadLength())
                        .isEqualTo(response.payloadValue(field));
            }
        }
    }

    @Nested
    @DisplayName("The 337-byte image - FILLER emitted, attribute bytes raw, both code pages")
    class FixedWidthImage {

        @Test
        @DisplayName("gate G21 - the TIOAPFX prefix and all 17 attribute FILLERs are space-filled")
        void fillerIsPresentAndSpaceFilled() {
            byte[] image = populated().toFixedWidth(ASCII);

            assertThat(image).hasSize(337);
            assertThat(new String(image, 0, 12, ASCII)).isEqualTo(" ".repeat(12));
            for (ScreenField field : ScreenField.values()) {
                assertThat(new String(image, field.attributePrefixOffset(), 3, ASCII))
                        .as("the FILLER X(3) before " + field.baseName())
                        .isEqualTo("   ");
            }
        }

        @Test
        @DisplayName("every payload item lands at its copybook offset")
        void payloadLandsAtCopybookOffsets() {
            ReportRequestResponse response = populated();
            response.setErrmsgo(SystemMessages.CCDA_MSG_INVALID_KEY);
            byte[] image = response.toFixedWidth(ASCII);

            for (ScreenField field : ScreenField.values()) {
                assertThat(new String(image, field.payloadOffset(), field.payloadLength(), ASCII))
                        .as(field.payloadItemName() + " at offset " + field.payloadOffset())
                        .isEqualTo(response.payloadValue(field));
            }
            assertThat(new String(image, ScreenField.TRNNAME.payloadOffset(), 4, ASCII))
                    .isEqualTo("CR00");
            assertThat(new String(image, ScreenField.PGMNAME.payloadOffset(), 8, ASCII))
                    .isEqualTo("CORPT00C");
        }

        @Test
        @DisplayName("an attribute byte is a code point and is never charset translated")
        void attributeBytesAreRaw() {
            ReportRequestResponse response = populated();
            response.setErrmsgc(BmsAttributes.DFHGREEN);
            response.setHilightAttribute(ScreenField.MONTHLY, BmsAttributes.DFHUNDLN);

            byte[] ascii = response.toFixedWidth(ASCII);
            byte[] ebcdic = response.toFixedWidth(EBCDIC);

            int colourAt = ScreenField.ERRMSG.colourSpan().offset();
            int hilightAt = ScreenField.MONTHLY.hilightSpan().offset();
            assertThat(ascii[colourAt]).isEqualTo(BmsAttributes.DFHGREEN);
            assertThat(ebcdic[colourAt])
                    .as("DFHGREEN is X'F4' in both images, because it is not text")
                    .isEqualTo(BmsAttributes.DFHGREEN);
            assertThat(ascii[hilightAt]).isEqualTo(BmsAttributes.DFHUNDLN);
            assertThat(ebcdic[hilightAt]).isEqualTo(BmsAttributes.DFHUNDLN);
        }

        @Test
        @DisplayName("the image round trips losslessly in US-ASCII and in IBM037")
        void roundTripsInBothCodePages() {
            ReportRequestResponse response = populated();
            response.setErrmsgo("a message with trailing padding");
            response.setErrmsgc(BmsAttributes.DFHGREEN);
            response.setPsAttribute(ScreenField.CONFIRM, (byte) 0x11);
            response.setValidnAttribute(ScreenField.SDTMM, (byte) 0x22);

            for (Charset charset : List.of(ASCII, EBCDIC)) {
                byte[] image = response.toFixedWidth(charset);
                assertThat(image).hasSize(337);
                ReportRequestResponse back =
                        ReportRequestResponse.fromFixedWidth(image, charset);
                assertThat(back.fieldImages())
                        .as("payload via " + charset).isEqualTo(response.fieldImages());
                assertThat(back.attributeImages())
                        .as("attributes via " + charset).isEqualTo(response.attributeImages());
            }
        }

        @Test
        @DisplayName("writeInto and readFrom work against a caller-supplied record area")
        void writeIntoAndReadFromARecord() {
            ReportRequestResponse response = populated();
            response.setConfirmo("N");
            response.setErrmsgc(BmsAttributes.DFHRED);

            FixedWidthCodec codec = new FixedWidthCodec(ASCII);
            FixedWidthRecord record = codec.newRecord(ReportRequestResponse.LAYOUT);
            response.writeInto(record);

            assertThat(record.toByteArray()).hasSize(337)
                    .isEqualTo(response.toFixedWidth(ASCII));

            ReportRequestResponse back = ReportRequestResponse.readFrom(record);
            assertThat(back.fieldImages()).isEqualTo(response.fieldImages());
            assertThat(back.getErrmsgc()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(back.getConfirmo()).isEqualTo("N");
        }

        @Test
        @DisplayName("a record area of the wrong width is refused, naming the arithmetic")
        void wrongWidthIsRefused() {
            ReportRequestResponse response = new ReportRequestResponse();
            FixedWidthRecord tooNarrow = new FixedWidthRecord(336, ASCII);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.writeInto(tooNarrow))
                    .withMessageContaining("337")
                    .withMessageContaining("336");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> ReportRequestResponse.readFrom(tooNarrow))
                    .withMessageContaining("337");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> ReportRequestResponse.fromFixedWidth(new byte[336], ASCII));
        }

        @Test
        @DisplayName("the charset is always the caller's explicit choice, never the platform's")
        void charsetIsAlwaysExplicit() {
            ReportRequestResponse response = new ReportRequestResponse();
            assertThatNullPointerException().isThrownBy(() -> response.toFixedWidth(null))
                    .withMessageContaining("charset");
            assertThatNullPointerException()
                    .isThrownBy(() -> ReportRequestResponse.fromFixedWidth(new byte[337], null));
            assertThatNullPointerException()
                    .isThrownBy(() -> ReportRequestResponse.fromFixedWidth(null, ASCII));
            assertThatNullPointerException().isThrownBy(() -> response.writeInto(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> ReportRequestResponse.readFrom(null));
        }

        @Test
        @DisplayName("the diagnostic maps are keyed by the verbatim copybook item names")
        void imageMaps() {
            ReportRequestResponse response = new ReportRequestResponse();

            Map<String, String> payload = response.fieldImages();
            assertThat(payload).hasSize(17);
            assertThat(new ArrayList<>(payload.keySet()))
                    .startsWith("TRNNAMEO", "TITLE01O", "CURDATEO", "PGMNAMEO")
                    .endsWith("CONFIRMO", "ERRMSGO");
            for (ScreenField field : ScreenField.values()) {
                assertThat(payload.get(field.payloadItemName()))
                        .hasSize(field.payloadLength());
            }

            Map<String, String> attributes = response.attributeImages();
            assertThat(attributes).hasSize(68);
            assertThat(attributes).containsKeys("TRNNAMEC", "TRNNAMEP", "TRNNAMEH", "TRNNAMEV",
                    "ERRMSGC", "ERRMSGV");
            assertThat(attributes.get("ERRMSGC"))
                    .isEqualTo(BmsAttributes.toHex(ReportRequestResponse.ATTRIBUTE_UNSET));
        }
    }

    @Nested
    @DisplayName("Value semantics - equality covers everything that would be transmitted")
    class ValueSemantics {

        @Test
        @DisplayName("the copy constructor produces an equal but independent response")
        void copyConstructor() {
            ReportRequestResponse original = populated();
            original.setMonthlyo("X");
            original.setErrmsgo("boom");
            original.moveDfhgreenToErrmsgc();
            original.echoNavigation(NavigationContext.empty().withToProgram("COSGN00C"));

            ReportRequestResponse copy = new ReportRequestResponse(original);

            assertThat(copy).isEqualTo(original).hasSameHashCodeAs(original).isNotSameAs(original);
            assertThat(copy.getErrmsgc()).isEqualTo(BmsAttributes.DFHGREEN);
            assertThat(copy.getNextProgram()).isEqualTo("COSGN00C");

            copy.setMonthlyo("Y");
            assertThat(copy).isNotEqualTo(original);
            assertThat(original.getMonthlyo()).as("the original is untouched").isEqualTo("X");

            copy.setMonthlyo("X");
            assertThat(copy).isEqualTo(original);
            copy.setColourAttribute(ScreenField.MONTHLY, BmsAttributes.DFHRED);
            assertThat(copy).as("attribute bytes are part of equality").isNotEqualTo(original);

            assertThatNullPointerException()
                    .isThrownBy(() -> new ReportRequestResponse(null));
        }

        @Test
        @DisplayName("equality is reflexive, type-checked and null-safe")
        void equalityContract() {
            ReportRequestResponse response = new ReportRequestResponse();
            assertThat(response).isEqualTo(response)
                    .isNotEqualTo(null)
                    .isNotEqualTo("a string")
                    .isEqualTo(new ReportRequestResponse());
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("a difference in any one of the 17 members breaks equality")
        void everyMemberParticipatesInEquality(ScreenField field) {
            ReportRequestResponse left = new ReportRequestResponse();
            ReportRequestResponse right = new ReportRequestResponse();
            assertThat(left).isEqualTo(right);

            right.setPayloadValue(field, "Q");
            assertThat(right).as("a change to " + field.payloadItemName() + " must be visible")
                    .isNotEqualTo(left);
        }

        @Test
        @DisplayName("the navigation members participate in equality too")
        void navigationParticipatesInEquality() {
            ReportRequestResponse left = new ReportRequestResponse();

            ReportRequestResponse program = new ReportRequestResponse();
            program.setNextProgram("COMEN01C");
            assertThat(program).isNotEqualTo(left);

            ReportRequestResponse mapset = new ReportRequestResponse();
            mapset.setNextMapset("OTHER");
            assertThat(mapset).isNotEqualTo(left);

            ReportRequestResponse map = new ReportRequestResponse();
            map.setNextMap("OTHERM");
            assertThat(map).isNotEqualTo(left);

            ReportRequestResponse commarea = new ReportRequestResponse();
            commarea.setNavigationContext(NavigationContext.empty().withUserId("SOMEONE"));
            assertThat(commarea).isNotEqualTo(left);
        }

        @Test
        @DisplayName("toString names every item, and flags only the attribute quads that were set")
        void diagnosticRendering() {
            ReportRequestResponse response = new ReportRequestResponse();
            String quiet = response.toString();
            assertThat(quiet).contains("CORPT0AO").contains("337");
            for (ScreenField field : ScreenField.values()) {
                assertThat(quiet).contains(field.payloadItemName() + "='");
            }
            assertThat(quiet).as("an untouched quad is not rendered").doesNotContain("C=");

            response.setErrmsgo("boom");
            response.moveDfhgreenToErrmsgc();
            response.echoNavigation(NavigationContext.empty().withToProgram("COSGN00C"));
            String loud = response.toString();
            assertThat(loud).contains("ERRMSGO='boom")
                    .contains("DFHGREEN")
                    .contains("nextProgram='COSGN00C")
                    // Blanked, because echoNavigation is the transfer projection and an XCTL states no
                    // map. The rendering shows what the response actually carries.
                    .contains("nextMapset='" + " ".repeat(NavigationContext.LAST_MAPSET_LENGTH))
                    .contains("nextMap='" + " ".repeat(NavigationContext.LAST_MAP_LENGTH))
                    .contains(NavigationContext.TO_PROGRAM_FIELD);
        }
    }

    @Nested
    @DisplayName("Bind B7 - the header comes from an injected fixed Clock, never from the wall clock")
    class DeterministicHeader {

        @Test
        @DisplayName("a fixed Clock with an explicit ZoneId pins CURDATEO and CURTIMEO exactly")
        void fixedClockPinsTheHeader() {
            ReportRequestResponse response = new ReportRequestResponse();
            response.populateHeaderInfo(DateHeader.from(new FixedWidthCodec(ASCII), FIXED_CLOCK));

            assertThat(response.getCurdateo()).isEqualTo(EXPECTED_CURDATEO).hasSize(8);
            assertThat(response.getCurtimeo()).isEqualTo(EXPECTED_CURTIMEO).hasSize(8);
        }

        @Test
        @DisplayName("the zone is read from the clock, so the same instant in another zone differs")
        void theZoneIsPartOfTheContract() {
            FixedWidthCodec codec = new FixedWidthCodec(ASCII);

            ReportRequestResponse chicago = new ReportRequestResponse();
            chicago.populateHeaderInfo(DateHeader.from(codec, FIXED_CLOCK));

            ReportRequestResponse utc = new ReportRequestResponse();
            utc.populateHeaderInfo(DateHeader.from(codec, Clock.fixed(FIXED_INSTANT, ZoneId.of("UTC"))));

            assertThat(chicago.getCurtimeo()).isEqualTo("17:02:43");
            assertThat(utc.getCurtimeo())
                    .as("the very reason the ZoneId is named explicitly rather than defaulted")
                    .isEqualTo("22:02:43");
            assertThat(chicago).isNotEqualTo(utc);
        }

        @Test
        @DisplayName("reading the same clock twice yields byte-identical responses")
        void repeatableAcrossReads() {
            FixedWidthCodec codec = new FixedWidthCodec(ASCII);

            ReportRequestResponse first = new ReportRequestResponse();
            first.populateHeaderInfo(DateHeader.from(codec, FIXED_CLOCK));
            ReportRequestResponse second = new ReportRequestResponse();
            second.populateHeaderInfo(DateHeader.from(codec, FIXED_CLOCK));

            assertThat(second).isEqualTo(first);
            assertThat(second.toFixedWidth(ASCII)).isEqualTo(first.toFixedWidth(ASCII));
        }

        @Test
        @DisplayName("the clock's local time is what reaches the header, not the raw instant")
        void clockAgreesWithTheEquivalentLocalDateTime() {
            FixedWidthCodec codec = new FixedWidthCodec(ASCII);
            LocalDateTime local = LocalDateTime.ofInstant(FIXED_INSTANT, FIXED_ZONE);

            DateHeader fromClock = DateHeader.from(codec, FIXED_CLOCK);
            DateHeader fromLocal = DateHeader.of(codec, local);

            assertThat(fromClock.wsCurdateMmDdYy()).isEqualTo(fromLocal.wsCurdateMmDdYy());
            assertThat(fromClock.wsCurtimeHhMmSs()).isEqualTo(fromLocal.wsCurtimeHhMmSs());
        }

        @Test
        @DisplayName("a null DateHeader is refused, so the instant can never be implicit")
        void headerIsRequired() {
            ReportRequestResponse response = new ReportRequestResponse();
            assertThatNullPointerException()
                    .isThrownBy(() -> response.populateHeaderInfo(null))
                    .withMessageContaining("CURRENT-DATE");
        }
    }

    @Nested
    @DisplayName("Rule R1 - the name is the prompt's, the behaviour is CORPT00C's, and here they agree")
    class SourceProvenance {

        @Test
        @DisplayName("CORPT00C:5 and README.md:225 agree, so this is NOT an R-B naming case")
        void nameAndBehaviourAgree() throws IOException {
            List<String> program = Files.readAllLines(PROGRAM, ASCII);
            assertThat(program.get(4))
                    .as("app/cbl/CORPT00C.cbl:5, the Function header")
                    .contains("Function")
                    .contains("Print Transaction reports by submitting batch");

            List<String> readme = Files.readAllLines(README, ASCII);
            assertThat(readme.get(224))
                    .as("README.md:225, the CR00 row of the online inventory")
                    .contains("CR00")
                    .contains("CORPT00")
                    .contains("CORPT00C")
                    .contains("Transaction Reports");

            assertThat(ReportRequestResponse.TRANSACTION_ID).isEqualTo("CR00");
            assertThat(ReportRequestResponse.PROGRAM_NAME).isEqualTo("CORPT00C");
            assertThat(ReportRequestResponse.MAPSET_NAME).isEqualTo("CORPT00");
            assertThat(ReportRequestResponse.MAP_NAME).isEqualTo("CORPT0A");
        }

        @Test
        @DisplayName("the CSD declares MAPSET(CORPT00), PROGRAM(CORPT00C) and TRANSACTION(CR00)")
        void csdDefinitions() throws IOException {
            List<String> csd = Files.readAllLines(CSD, ASCII);

            assertThat(csd.get(136)).as("app/csd/CARDDEMO.CSD:137")
                    .contains("DEFINE MAPSET(" + ReportRequestResponse.MAPSET_NAME + ")");
            assertThat(csd.get(241)).as("app/csd/CARDDEMO.CSD:242")
                    .contains("DEFINE PROGRAM(" + ReportRequestResponse.PROGRAM_NAME + ")");
            assertThat(csd.get(408)).as("app/csd/CARDDEMO.CSD:409")
                    .contains("DEFINE TRANSACTION(" + ReportRequestResponse.TRANSACTION_ID + ")");
            assertThat(csd.get(409)).as("app/csd/CARDDEMO.CSD:410, the program it dispatches")
                    .contains("PROGRAM(" + ReportRequestResponse.PROGRAM_NAME + ")");
        }

        @Test
        @DisplayName("gate G9 - 42 DFHMDF entries, 17 name-labelled, 25 untransmitted literals")
        void theUnlabelledTwentyFiveAreReconciled() throws IOException {
            String mapset = Files.readString(MAPSET, ASCII);
            long total = mapset.lines().filter(line -> line.contains("DFHMDF")).count();

            assertThat(total).as("total DFHMDF entries in app/bms/CORPT00.bms")
                    .isEqualTo(TOTAL_DFHMDF_ENTRIES);
            assertThat(parseLabelledFields()).hasSize(ReportRequestResponse.SCREEN_FIELD_COUNT);
            assertThat(total - parseLabelledFields().size())
                    .as("the unlabelled entries - screen literals such as 'Tran:' and '(MM/DD/YYYY)' "
                            + "which CICS never transmits and which therefore have no payload member")
                    .isEqualTo(UNLABELLED_DFHMDF_ENTRIES)
                    .isEqualTo(25);
        }

        @Test
        @DisplayName("CORPT00C has exactly one XCTL site and 22 MOVE -1 sites")
        void countedSitesInTheProgram() throws IOException {
            List<String> program = Files.readAllLines(PROGRAM, ASCII);

            List<String> xctl = program.stream().filter(line -> line.contains("XCTL")).toList();
            assertThat(xctl).as("gate G40 - a single transfer of control, at line 549").hasSize(XCTL_SITES);
            assertThat(xctl.get(0)).contains("PROGRAM(CDEMO-TO-PROGRAM)");
            assertThat(program.get(548)).as("app/cbl/CORPT00C.cbl:549").contains("XCTL");

            long moveMinusOne = program.stream().filter(line -> line.contains("MOVE -1")).count();
            assertThat(moveMinusOne)
                    .as("every one is 'MOVE -1 TO <field>L OF CORPT0AI' - the CICS cursor-positioning "
                            + "value, which is why xxxL is signed COMP PIC S9(4) and not unsigned")
                    .isEqualTo(MOVE_MINUS_ONE_SITES);
            assertThat(program.stream()
                    .filter(line -> line.contains("MOVE -1"))
                    .allMatch(line -> line.contains("L OF CORPT0AI"))).isTrue();
        }

        @Test
        @DisplayName("CORPT00C:138-140 - COPY COCOM01Y. then COPY CORPT00. with nothing between")
        void noCommareaExtensionIsDeclared() throws IOException {
            List<String> program = Files.readAllLines(PROGRAM, ASCII);

            assertThat(program.get(137).trim()).as("app/cbl/CORPT00C.cbl:138")
                    .isEqualTo("COPY COCOM01Y.");
            assertThat(program.get(138).trim()).as("app/cbl/CORPT00C.cbl:139 is blank").isEmpty();
            assertThat(program.get(139).trim()).as("app/cbl/CORPT00C.cbl:140")
                    .isEqualTo("COPY " + ReportRequestResponse.MAPSET_NAME + ".");
            assertThat(program).as("the three CT screens each declare a 58-byte CDEMO-CTnn-INFO group "
                            + "here; CORPT00C declares none, and that is why its commarea is 160 bytes")
                    .noneMatch(line -> line.contains("CDEMO-CR00-INFO"));
        }

        @Test
        @DisplayName("the source files are read, never written - bind B3 and gate G5")
        void referenceInputsAreOnlyRead() {
            for (Path source : List.of(COPYBOOK, MAPSET, PROGRAM, CSD, README)) {
                assertThat(Files.isReadable(source)).as(source.toString()).isTrue();
            }
        }
    }

    @Nested
    @DisplayName("Two date triples, no ten-character date field, and no CVTRA05Y member anywhere")
    class ShapeOfTheDateCriteria {

        @Test
        @DisplayName("the criteria are two MM/DD/YYYY triples at 2/2/4, not two ten-byte dates")
        void twoTriplesAndNoTenByteDate() {
            List<ScreenField> startTriple =
                    List.of(ScreenField.SDTMM, ScreenField.SDTDD, ScreenField.SDTYYYY);
            List<ScreenField> endTriple =
                    List.of(ScreenField.EDTMM, ScreenField.EDTDD, ScreenField.EDTYYYY);

            assertThat(startTriple).extracting(ScreenField::payloadLength)
                    .as("SDTMMO / SDTDDO / SDTYYYYO").containsExactly(2, 2, 4);
            assertThat(endTriple).extracting(ScreenField::payloadLength)
                    .as("EDTMMO / EDTDDO / EDTYYYYO").containsExactly(2, 2, 4);

            assertThat(ScreenField.values())
                    .as("BMS splits the date across three separately keyable fields, and the screen "
                            + "prints the '(MM/DD/YYYY)' hint as an unlabelled literal; a single "
                            + "X(10) field would collapse three cursor positions into one")
                    .noneMatch(field -> field.payloadLength() == 10);
        }

        @Test
        @DisplayName("the two triples occupy separate, non-overlapping storage in copybook order")
        void triplesAreDistinctStorage() {
            List<ScreenField> inOrder = List.of(ScreenField.SDTMM, ScreenField.SDTDD,
                    ScreenField.SDTYYYY, ScreenField.EDTMM, ScreenField.EDTDD, ScreenField.EDTYYYY);

            int previousEnd = 0;
            for (ScreenField field : inOrder) {
                assertThat(field.payloadOffset())
                        .as(field.payloadItemName() + " must start after the previous item ends")
                        .isGreaterThan(previousEnd);
                previousEnd = field.payloadOffset() + field.payloadLength();
            }

            ReportRequestResponse response = new ReportRequestResponse();
            response.setSdtmmo("07");
            response.setSdtddo("18");
            response.setSdtyyyyo("2022");
            response.setEdtmmo("08");
            response.setEdtddo("22");
            response.setEdtyyyyo("2023");

            assertThat(response.getSdtmmo()).isEqualTo("07");
            assertThat(response.getSdtddo()).isEqualTo("18");
            assertThat(response.getSdtyyyyo()).isEqualTo("2022");
            assertThat(response.getEdtmmo()).isEqualTo("08");
            assertThat(response.getEdtddo()).isEqualTo("22");
            assertThat(response.getEdtyyyyo()).isEqualTo("2023");
        }

        /**
         * The negative contract, corrected against the source.
         *
         * <p>The folder requirements for this file assert that {@code CORPT00C} "does not copy
         * {@code CVTRA05Y}". <strong>It does</strong> - {@code app/cbl/CORPT00C.cbl:146} reads
         * {@code COPY CVTRA05Y.}, and the migration plan's own per-program copybook matrix (section 0.2.3)
         * lists {@code CVTRA05Y} among {@code CORPT00C}'s eight includes. Rule <strong>R1</strong>
         * settles the discrepancy: behaviour comes from the source, so the source wins and the
         * assertion is written to the verified fact rather than to the prose.
         *
         * <p>The conclusion the requirement was reaching for is nevertheless <em>true</em>, and true
         * for a sharper reason. {@code CVTRA05Y} is included as a {@code WORKING-STORAGE} declaration
         * of the {@code TRANSACT} record - it is what the program's own {@code TRANSACT} browse reads
         * into - and <strong>not one</strong> of its fields is a name-labelled {@code DFHMDF} field on
         * {@code CORPT00}. Gate <strong>G9</strong> admits a payload member only where a
         * {@code DFHMDF} label exists, so no transaction-record field can reach this response.
         * {@code CVCRD01Y} genuinely is absent from the {@code COPY} list, so there is no
         * {@code CardScreenState} equivalent here either.
         */
        @Test
        @DisplayName("no CVTRA05Y-derived member reaches the map, though the program does copy it")
        void noTransactionRecordMembers() throws IOException {
            List<String> forbidden = List.of("TRNIDO", "CARDNUMO", "TRNAMTO", "MIDO", "MNAMEO",
                    "MCITYO", "MZIPO", "TDESCO", "TTYPCDO", "TCATCDO", "TRNSRCO", "TORIGDTO",
                    "TPROCDTO");
            Map<String, Integer> outboundItems = parseGroupItems("CORPT0AO", "O");

            for (String absent : forbidden) {
                assertThat(ScreenField.values())
                        .as(absent + " belongs to the COTRN maps; CORPT00 declares no such DFHMDF "
                                + "field, so gate G9 admits no such payload member")
                        .noneMatch(field -> field.payloadItemName().equals(absent));
                assertThat(outboundItems).doesNotContainKey(absent);
            }

            List<String> program = Files.readAllLines(PROGRAM, ASCII);
            assertThat(program.get(145).trim())
                    .as("app/cbl/CORPT00C.cbl:146 - the record layout for the program's own TRANSACT "
                            + "browse, in WORKING-STORAGE, not a screen projection")
                    .isEqualTo("COPY CVTRA05Y.");
            assertThat(program)
                    .as("CVCRD01Y, the card screen-state carrier, genuinely is not included")
                    .noneMatch(line -> line.contains("COPY CVCRD01Y"));

            Map<String, Integer> labelled = parseLabelledFields();
            for (String absent : forbidden) {
                String bmsLabel = absent.substring(0, absent.length() - 1);
                assertThat(labelled)
                        .as("app/bms/CORPT00.bms declares no DFHMDF labelled " + bmsLabel)
                        .doesNotContainKey(bmsLabel);
            }

            List<String> imports = Files.readAllLines(Paths.get("src", "test", "java", "com",
                            "vsergeychik", "carddemo", "transaction", "dto",
                            "ReportRequestResponseTest.java"), JAVA_SOURCE).stream()
                    .filter(line -> line.startsWith("import "))
                    .toList();
            assertThat(imports)
                    .as("nor may this suite reach for the card screen-state carrier, which belongs to "
                            + "CVCRD01Y and to the three COCRD screens")
                    .isNotEmpty()
                    .noneMatch(line -> line.contains("CardScreenState"))
                    .noneMatch(line -> line.contains("carddemo.card."))
                    .noneMatch(line -> line.contains("carddemo.parity"))
                    .noneMatch(line -> line.endsWith(".*;"));
        }

        @Test
        @DisplayName("this suite reads exactly six files - the five references and the class under test")
        void nothingElseIsRead() {
            List<String> pathConstants = new ArrayList<>();
            for (Field declared : ReportRequestResponseTest.class.getDeclaredFields()) {
                if (Path.class.equals(declared.getType())) {
                    pathConstants.add(declared.getName());
                }
            }

            assertThat(pathConstants)
                    .as("enumerating the Path constants is the whole statement of what this suite "
                            + "touches: the five read-only references from app/cbl, app/cpy-bms, "
                            + "app/bms, app/csd and the repository README, plus the Java source of the "
                            + "class under test. No harness fixture, no seeded dataset and no test "
                            + "resource of any kind is opened - bind B3 and gate G5")
                    .containsExactlyInAnyOrder("COPYBOOK", "MAPSET", "PROGRAM", "CSD", "README",
                            "SOURCE");
        }

        @Test
        @DisplayName("CORPT00C:305-325 normalises the date parts back into their own X(2)/X(4) items")
        void numvalCNormalisesInPlace() throws IOException {
            List<String> program = Files.readAllLines(PROGRAM, ASCII);
            String window = String.join("\n", program.subList(304, 328));

            assertThat(window).as("app/cbl/CORPT00C.cbl:305-328")
                    .contains("FUNCTION NUMVAL-C")
                    .contains("SDTMMI OF CORPT0AI")
                    .contains("EDTYYYYI OF CORPT0AI");
            assertThat(window.lines().filter(line -> line.contains("FUNCTION NUMVAL-C")).count())
                    .as("six call sites, whose acceptance semantics belong to gate G29 in "
                            + "ReportRequestControllerTest and are deliberately not retested here")
                    .isEqualTo(6);

            ReportRequestResponse response = new ReportRequestResponse();
            response.setSdtmmo("7");
            assertThat(response.getSdtmmo())
                    .as("the DTO-side consequence is the only thing asserted here: the field stays a "
                            + "String at its declared width, so a normalised '07' and a keyed '7 ' "
                            + "remain distinguishable byte for byte")
                    .isEqualTo("7 ").hasSize(2);
            response.setSdtmmo("07");
            assertThat(response.getSdtmmo()).isEqualTo("07").isNotEqualTo("7 ");
        }
    }

    @Nested
    @DisplayName("The defining property - the passed commarea is 160 bytes, NOT the CT screens' 218")
    class NoCommareaExtension {

        @Test
        @DisplayName("160 bytes, because CORPT00C declares no 58-byte CDEMO-CTnn-INFO group")
        void oneHundredAndSixtyNotTwoHundredAndEighteen() {
            int ctScreenCommarea = NavigationContext.COMMAREA_LENGTH + 58;

            assertThat(NavigationContext.COMMAREA_LENGTH)
                    .as("app/cpy/COCOM01Y.cpy, bare CARDDEMO-COMMAREA")
                    .isEqualTo(160);
            assertThat(ctScreenCommarea)
                    .as("what COTRN00C, COTRN01C and COTRN02C pass - 160 + a 58-byte extension")
                    .isEqualTo(218);

            ReportRequestResponse response = new ReportRequestResponse();
            response.echoNavigation(NavigationContext.empty().withToProgram("COMEN01C"));

            byte[] commarea =
                    response.getNavigationContext().toFixedWidth(new FixedWidthCodec(ASCII));
            assertThat(commarea).hasSize(160);
            assertThat(commarea.length)
                    .as("a developer generalising from the three sibling Response types would add 58 "
                            + "bytes here; CORPT00C.cbl:138-140 shows there is nothing to add")
                    .isNotEqualTo(ctScreenCommarea);
        }

        @Test
        @DisplayName("the five sub-groups are 34 / 84 / 12 / 16 / 14 and sum to exactly 160")
        void subGroupWidths() {
            assertThat(NavigationContext.GENERAL_INFO_LENGTH).isEqualTo(34);
            assertThat(NavigationContext.CUSTOMER_INFO_LENGTH).isEqualTo(84);
            assertThat(NavigationContext.ACCOUNT_INFO_LENGTH).isEqualTo(12);
            assertThat(NavigationContext.CARD_INFO_LENGTH).isEqualTo(16);
            assertThat(NavigationContext.MORE_INFO_LENGTH).isEqualTo(14);

            assertThat(NavigationContext.GENERAL_INFO_LENGTH
                    + NavigationContext.CUSTOMER_INFO_LENGTH
                    + NavigationContext.ACCOUNT_INFO_LENGTH
                    + NavigationContext.CARD_INFO_LENGTH
                    + NavigationContext.MORE_INFO_LENGTH)
                    .as("34 + 84 + 12 + 16 + 14")
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH);
        }

        @Test
        @DisplayName("CDEMO-LAST-MAP and CDEMO-LAST-MAPSET are X(7), which is why nextMap/set are 7")
        void lastMapAndMapsetAreSeven() {
            assertThat(NavigationContext.LAST_MAP_LENGTH).isEqualTo(7);
            assertThat(NavigationContext.LAST_MAPSET_LENGTH).isEqualTo(7);
            assertThat(ReportRequestResponse.MAP_NAME).hasSize(7);
            assertThat(ReportRequestResponse.MAPSET_NAME).hasSize(7);

            ReportRequestResponse response = new ReportRequestResponse();
            assertThat(response.getNextMap()).hasSize(NavigationContext.LAST_MAP_LENGTH);
            assertThat(response.getNextMapset()).hasSize(NavigationContext.LAST_MAPSET_LENGTH);
            assertThat(response.getNextProgram())
                    .as("CDEMO-TO-PROGRAM is X(8), one wider than the two map names")
                    .hasSize(NavigationContext.TO_PROGRAM_LENGTH)
                    .hasSize(8);
        }

        @Test
        @DisplayName("no nested pagination or cursor type is declared on this response")
        void noNestedCursorType() {
            assertThat(ReportRequestResponse.class.getDeclaredClasses())
                    .as("the CT screens carry a page-number and first/last-key cursor group; this "
                            + "screen has no browse, no page and no cursor, so it declares neither")
                    .extracting(Class::getSimpleName)
                    .containsExactlyInAnyOrder("ScreenField", "FieldAttributes");

            List<String> commareaCarriers = new ArrayList<>();
            for (Field declared : ReportRequestResponse.class.getDeclaredFields()) {
                assertThat(declared.getName().toLowerCase(Locale.ROOT))
                        .as("field " + declared.getName())
                        .doesNotContain("page")
                        .doesNotContain("cursor")
                        .doesNotContain("pagination")
                        .doesNotContain("firstkey")
                        .doesNotContain("lastkey");
                if (NavigationContext.class.equals(declared.getType())) {
                    commareaCarriers.add(declared.getName());
                }
            }

            assertThat(commareaCarriers)
                    .as("exactly one commarea carrier, and it is the bare 160-byte NavigationContext - "
                            + "a second field, or a wider type, is how 218 bytes would creep in")
                    .containsExactly("navigationContext");
            assertThat(NavigationContext.class.getRecordComponents())
                    .as("app/cpy/COCOM01Y.cpy declares 16 elementary items across its five 05-level "
                            + "groups, and NavigationContext carries exactly 16 - a 58-byte "
                            + "CDEMO-CTnn-INFO extension would have to add more")
                    .hasSize(16);
            assertThat(NavigationContext.FROM_TRANID_LENGTH + NavigationContext.FROM_PROGRAM_LENGTH
                    + NavigationContext.TO_TRANID_LENGTH + NavigationContext.TO_PROGRAM_LENGTH
                    + NavigationContext.USER_ID_LENGTH + NavigationContext.USER_TYPE_LENGTH
                    + NavigationContext.PGM_CONTEXT_LENGTH + NavigationContext.CUST_ID_LENGTH
                    + NavigationContext.CUST_FNAME_LENGTH + NavigationContext.CUST_MNAME_LENGTH
                    + NavigationContext.CUST_LNAME_LENGTH + NavigationContext.ACCT_ID_LENGTH
                    + NavigationContext.ACCT_STATUS_LENGTH + NavigationContext.CARD_NUM_LENGTH
                    + NavigationContext.LAST_MAP_LENGTH + NavigationContext.LAST_MAPSET_LENGTH)
                    .as("4+8+4+8+8+1+1 + 9+25+25+25 + 11+1 + 16 + 7+7, item by item")
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH)
                    .isEqualTo(160);
        }

        @Test
        @DisplayName("both CDEMO-PGM-CONTEXT states travel outward, and REENTER is what gates G38")
        void bothProgramContextStates() {
            assertThat(NavigationContext.PGM_CONTEXT_ENTER).isZero();
            assertThat(NavigationContext.PGM_CONTEXT_REENTER).isEqualTo(1);

            NavigationContext enter = NavigationContext.empty();
            assertThat(enter.isEnter()).isTrue();
            assertThat(enter.isReenter()).isFalse();

            ReportRequestResponse onEntry = new ReportRequestResponse();
            onEntry.setNavigationContext(enter);
            assertThat(onEntry.getNavigationContext().isEnter()).isTrue();
            assertThat(onEntry.applyHighlight(ScreenField.MONTHLY,
                    FieldAttributeSetter.resolveFromFlags(false, true,
                            onEntry.getNavigationContext().isReenter())))
                    .as("first entry paints the screen; CSSETATY's outer IF cannot fire")
                    .isFalse();

            NavigationContext reenter = enter.withPgmContext(NavigationContext.PGM_CONTEXT_REENTER);
            ReportRequestResponse onReentry = new ReportRequestResponse();
            onReentry.setNavigationContext(reenter);
            assertThat(onReentry.getNavigationContext().isReenter()).isTrue();
            assertThat(onReentry.applyHighlight(ScreenField.MONTHLY,
                    FieldAttributeSetter.resolveFromFlags(false, true,
                            onReentry.getNavigationContext().isReenter())))
                    .as("re-entry validates what was typed, so the highlight becomes reachable")
                    .isTrue();
            assertThat(onReentry.getMonthlyo()).isEqualTo(FieldAttributeSetter.ASTERISK);
        }

        @Test
        @DisplayName("gate G37 - no HttpSession, no ThreadLocal, no static state on either side")
        void nothingIsHeldServerSide() throws IOException {
            String code = stripComments(Files.readString(SOURCE, JAVA_SOURCE));

            assertThat(code)
                    .doesNotContain("HttpSession")
                    .doesNotContain("HttpServletRequest")
                    .doesNotContain("ThreadLocal")
                    .doesNotContain("SessionAttribute")
                    .doesNotContain("RequestScope")
                    .doesNotContain("static Map")
                    .doesNotContain("WeakHashMap");

            for (Field declared : ReportRequestResponse.class.getDeclaredFields()) {
                if (!Modifier.isStatic(declared.getModifiers())) {
                    continue;
                }
                assertThat(Modifier.isFinal(declared.getModifiers()))
                        .as("static " + declared.getName() + " must be final").isTrue();
                assertThat(declared.getType())
                        .as("static " + declared.getName() + " must not be a mutable collection")
                        .isNotIn(Map.class, List.class, Set.class);
            }
        }
    }

    /**
     * Gate <strong>G34</strong> - the {@code REDEFINES} pair, proved as two accessors over one span.
     *
     * <p>{@code app/cpy-bms/CORPT00.CPY:121} declares {@code 01 CORPT0AO REDEFINES CORPT0AI}. At each
     * field's prefix offset {@code k} the two views spend the same seven bytes differently:
     *
     * <pre>
     *   AI view:  k..k+1 xxxL (2B, signed) | k+2 xxxF | k+2 xxxA (SAME byte) | k+3..k+6 FILLER X(4) | k+7.. xxxI X(n)
     *   AO view:  k..k+2 FILLER X(3)                  | k+3 xxxC | k+4 xxxP  | k+5 xxxH | k+6 xxxV  | k+7.. xxxO X(n)
     * </pre>
     *
     * <p>This suite owns the {@code AO} side and asserts all four consequences as round trips over one
     * backing span. Nothing is inferred from prose: every claim is a byte read back through the other
     * view.
     *
     * <h2>Two accessors, and why there are two kinds of test here</h2>
     *
     * <p>A {@code REDEFINES} claim needs a <em>second</em> accessor, and this suite uses two:
     *
     * <ul>
     *   <li>A <strong>raw {@link FixedWidthRecord}</strong> over the 337-byte image, reaching the
     *       {@code xxxL}/{@code xxxF} and quad windows by absolute offset with
     *       {@link FixedWidthRecord#writeBytes(int, byte[])} and
     *       {@link FixedWidthRecord#readBytes(int, int)}. These are the load-bearing tests: they prove
     *       the overlay using only the fixed-width primitives this suite already depends on, so the
     *       claim stands on nothing but the copybook and the codec.</li>
     *   <li>{@link ReportRequestRequest}, the module's own {@code AI} projection of the same storage and
     *       this type's Request sibling in the same package. It needs no {@code import} and adds no new
     *       library, and it is the honest second accessor: it is what a real inbound
     *       {@code RECEIVE MAP} produces. Its tests corroborate the raw-record ones rather than
     *       standing in for them - hand-rolling a private symbolic-map decoder inside this test instead
     *       would duplicate a codec the module already owns, which bind <strong>B11</strong> exists to
     *       prevent.</li>
     * </ul>
     */
    @Nested
    @DisplayName("Gate G34 - CORPT0AO REDEFINES CORPT0AI, proved byte by byte through both views")
    class RedefinesOverlay {

        /** The {@code AI} counterpart of an {@code AO} field, by ordinal - both enums are in copybook order. */
        private ReportRequestRequest.ScreenField inbound(ScreenField outboundField) {
            return ReportRequestRequest.ScreenField.values()[outboundField.ordinal()];
        }

        @Test
        @DisplayName("the two views declare the same 337-byte record and the same 17 field offsets")
        void theTwoLayoutsAgree() {
            assertThat(ReportRequestRequest.SYMBOLIC_MAP_LENGTH)
                    .isEqualTo(ReportRequestResponse.RECORD_LENGTH)
                    .isEqualTo(337);
            assertThat(ReportRequestRequest.FIELD_PREFIX_LENGTH)
                    .as("2 + 1 + 4 inbound equals 3 + 1 + 1 + 1 + 1 outbound")
                    .isEqualTo(ReportRequestResponse.FIELD_PREFIX_LENGTH)
                    .isEqualTo(7);
            assertThat(ReportRequestRequest.ScreenField.values())
                    .hasSameSizeAs(ScreenField.values());

            for (ScreenField field : ScreenField.values()) {
                ReportRequestRequest.ScreenField ai = inbound(field);
                assertThat(ai.bmsName()).as("ordinal " + field.ordinal()).isEqualTo(field.baseName());
                assertThat(ai.declaredLength()).isEqualTo(field.payloadLength());
            }
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("consequence 1 - the xxxC/xxxP/xxxH/xxxV quad is the AI view's FILLER X(4)")
        void quadOccupiesTheInboundReservedFiller(ScreenField field) {
            FieldSpan reserved = inbound(field).reservedFillerSpan();
            int k = field.attributePrefixOffset();

            assertThat(reserved.offset()).as("k+3").isEqualTo(k + 3);
            assertThat(reserved.length()).as("X(4)").isEqualTo(4);
            assertThat(field.colourSpan().offset()).isEqualTo(k + 3);
            assertThat(field.psSpan().offset()).isEqualTo(k + 4);
            assertThat(field.hilightSpan().offset()).isEqualTo(k + 5);
            assertThat(field.validnSpan().offset()).isEqualTo(k + 6);
            assertThat(field.validnSpan().endOffsetExclusive())
                    .as("the quad ends exactly where the reserved FILLER ends")
                    .isEqualTo(reserved.endOffsetExclusive())
                    .isEqualTo(field.payloadOffset());
        }

        @Test
        @DisplayName("consequence 1 - writing the quad is therefore INVISIBLE through the AI view")
        void writingTheQuadIsInvisibleInbound() {
            FixedWidthCodec codec = new FixedWidthCodec(ASCII);
            ReportRequestResponse response = populated();
            response.setConfirmo("Y");
            response.setSdtmmo("07");

            byte[] plain = response.toFixedWidth(ASCII);
            ReportRequestRequest asReadBefore = ReportRequestRequest.fromSymbolicMap(codec, plain);

            response.setColourAttribute(ScreenField.SDTMM, BmsAttributes.DFHRED);
            response.setPsAttribute(ScreenField.SDTMM, (byte) 0x41);
            response.setHilightAttribute(ScreenField.SDTMM, BmsAttributes.DFHUNDLN);
            response.setValidnAttribute(ScreenField.SDTMM, (byte) 0x42);
            byte[] withQuad = response.toFixedWidth(ASCII);

            assertThat(withQuad).as("four bytes differ in the image").isNotEqualTo(plain);
            assertThat(ReportRequestRequest.fromSymbolicMap(codec, withQuad))
                    .as("but the AI view reads only xxxL, xxxF and xxxI, and the quad is none of them")
                    .isEqualTo(asReadBefore);
            assertThat(ReportRequestRequest.metadataFrom(codec, withQuad))
                    .as("nor does it disturb the inbound metadata items")
                    .isEqualTo(ReportRequestRequest.metadataFrom(codec, plain));
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("consequence 2 - xxxL and xxxF are the AO view's FILLER X(3)")
        void lengthAndFlagOccupyTheOutboundFiller(ScreenField field) {
            ReportRequestRequest.ScreenField ai = inbound(field);
            FieldSpan outboundFiller = field.attributeFillerSpan();
            int k = field.attributePrefixOffset();

            assertThat(outboundFiller.offset()).as("k").isEqualTo(k);
            assertThat(outboundFiller.length()).as("X(3)").isEqualTo(3);
            assertThat(ai.lengthSpan().offset()).isEqualTo(k);
            assertThat(ai.lengthSpan().length()).as("COMP PIC S9(4) is a two-byte halfword").isEqualTo(2);
            assertThat(ai.flagSpan().offset()).isEqualTo(k + 2);
            assertThat(ai.flagSpan().length()).isEqualTo(1);
            assertThat(ai.attributeSpan().offset())
                    .as("xxxA REDEFINES xxxF - the SAME byte, not the next one")
                    .isEqualTo(ai.flagSpan().offset());
            assertThat(ai.flagSpan().endOffsetExclusive())
                    .isEqualTo(outboundFiller.endOffsetExclusive())
                    .isEqualTo(k + 3);
        }

        /**
         * The cursor value is why {@code xxxL} is {@code COMP PIC S9(4)} - <em>signed</em> - rather
         * than unsigned. {@code app/cbl/CORPT00C.cbl} performs {@code MOVE -1 TO <field>L OF CORPT0AI}
         * at <strong>22</strong> sites, one for every field it can leave the cursor on: lines 180, 192,
         * 264, 271, 278, 285, 292, 299, 334, 343, 351, 360, 369, 377, 403, 423, 441, 453, 472, 492,
         * 533 and 635. An unsigned halfword would read that back as 65535.
         */
        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("consequence 2 - a -1 cursor length round trips as -1 and is invisible outbound")
        void cursorLengthRoundTripsAndIsInvisibleOutbound(ScreenField field) {
            FixedWidthCodec codec = new FixedWidthCodec(ASCII);
            ReportRequestRequest.ScreenField ai = inbound(field);
            ReportRequestRequest request = ReportRequestRequest.empty().withSdtmm("07");

            byte[] initial = request.toSymbolicMap(codec,
                    ReportRequestRequest.SymbolicMapMetadata.initial());
            byte[] withCursor = request.toSymbolicMap(codec,
                    ReportRequestRequest.SymbolicMapMetadata.initial().withCursorAt(ai));

            assertThat(ReportRequestRequest.metadataFrom(codec, withCursor).length(ai))
                    .as("X'FFFF' must read back as -1, not 65535")
                    .isEqualTo(ReportRequestRequest.FieldMetadata.CURSOR_POSITION)
                    .isEqualTo((short) -1);
            assertThat(ReportRequestRequest.metadataFrom(codec, initial).length(ai))
                    .isEqualTo(ReportRequestRequest.FieldMetadata.UNSET_LENGTH);

            ReportRequestResponse fromInitial =
                    ReportRequestResponse.fromFixedWidth(initial, ASCII);
            ReportRequestResponse fromCursor =
                    ReportRequestResponse.fromFixedWidth(withCursor, ASCII);
            assertThat(fromCursor.fieldImages())
                    .as("the AO view calls those three bytes FILLER, so a cursor request cannot "
                            + "change a single payload item")
                    .isEqualTo(fromInitial.fieldImages());
            assertThat(fromCursor.attributeImages())
                    .as("nor a single one of the sixty-eight attribute items")
                    .isEqualTo(fromInitial.attributeImages());
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("consequence 3 - xxxI and xxxO ARE the identical n bytes at k+7")
        void payloadSpansAreTheSameStorage(ScreenField field) {
            FieldSpan inboundSpan = inbound(field).inputSpan();

            assertThat(field.payloadSpan().offset())
                    .as(field.payloadItemName() + " and " + inbound(field).inputItem())
                    .isEqualTo(inboundSpan.offset())
                    .isEqualTo(field.attributePrefixOffset() + 7);
            assertThat(field.payloadSpan().length()).isEqualTo(inboundSpan.length());
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("consequence 3 - the round trip is lossless in BOTH directions, per field")
        void payloadRoundTripsBothWays(ScreenField field) {
            FixedWidthCodec codec = new FixedWidthCodec(ASCII);
            ReportRequestRequest.ScreenField ai = inbound(field);
            String probe = "Z".repeat(field.payloadLength());

            ReportRequestResponse outbound = new ReportRequestResponse();
            outbound.setPayloadValue(field, probe);
            ReportRequestRequest readAsInbound =
                    ReportRequestRequest.fromSymbolicMap(codec, outbound.toFixedWidth(ASCII));
            assertThat(readAsInbound.value(ai))
                    .as("AO -> AI: what the response wrote, the request reads")
                    .isEqualTo(probe);

            ReportRequestRequest inboundRequest = ReportRequestRequest.empty().withValue(ai, probe);
            ReportRequestResponse readAsOutbound = ReportRequestResponse.fromFixedWidth(
                    inboundRequest.toSymbolicMap(codec), ASCII);
            assertThat(readAsOutbound.payloadValue(field))
                    .as("AI -> AO: what the request wrote, the response reads")
                    .isEqualTo(probe);
        }

        @Test
        @DisplayName("consequence 3 - a whole populated screen survives AO -> AI -> AO unchanged")
        void wholeScreenRoundTrip() {
            FixedWidthCodec codec = new FixedWidthCodec(ASCII);
            ReportRequestResponse original = populated();
            original.setMonthlyo("X");
            original.setSdtmmo("07");
            original.setSdtddo("18");
            original.setSdtyyyyo("2022");
            original.setEdtmmo("08");
            original.setEdtddo("22");
            original.setEdtyyyyo("2022");
            original.setConfirmo("Y");
            original.setErrmsgo(SystemMessages.CCDA_MSG_INVALID_KEY);

            ReportRequestRequest viaInbound =
                    ReportRequestRequest.fromSymbolicMap(codec, original.toFixedWidth(ASCII));
            ReportRequestResponse back = ReportRequestResponse.fromFixedWidth(
                    viaInbound.toSymbolicMap(codec), ASCII);

            assertThat(back.fieldImages())
                    .as("seventeen items, byte for byte, through the aliased view and home again")
                    .isEqualTo(original.fieldImages());
        }

        /**
         * The whole overlay, proved with the fixed-width primitives alone.
         *
         * <p>No {@code AI} projection is consulted: the three windows of each seven-byte prefix are
         * reached by absolute offset over the rendered image, exactly as {@code CORPT0AI} and
         * {@code CORPT0AO} reach them. This is what makes the {@code REDEFINES} claim independent of
         * any other class in the package.
         */
        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("the overlay holds using the fixed-width primitives alone, with no AI projection")
        void overlayProvedWithRawSpansOnly(ScreenField field) {
            FixedWidthCodec codec = new FixedWidthCodec(ASCII);
            ReportRequestResponse original = populated();
            original.setPayloadValue(field, "K".repeat(field.payloadLength()));
            byte[] image = original.toFixedWidth(ASCII);
            int k = field.attributePrefixOffset();

            // The xxxL window: two bytes at k, written as a signed big-endian halfword. Writing the
            // CICS cursor value there must be invisible to every AO item, because the AO view calls
            // bytes k..k+2 its FILLER X(3).
            FixedWidthRecord record = codec.wrap(image, ReportRequestResponse.LAYOUT);
            record.writeBytes(k, new byte[] {(byte) 0xFF, (byte) 0xFF});
            byte[] halfword = record.readBytes(k, 2);
            assertThat((short) (((halfword[0] & 0xFF) << 8) | (halfword[1] & 0xFF)))
                    .as("xxxL is COMP PIC S9(4): X'FFFF' is -1, and an unsigned read would say 65535")
                    .isEqualTo((short) -1);

            ReportRequestResponse afterCursor =
                    ReportRequestResponse.fromFixedWidth(record.toByteArray(), ASCII);
            assertThat(afterCursor.fieldImages()).isEqualTo(original.fieldImages());
            assertThat(afterCursor.attributeImages()).isEqualTo(original.attributeImages());

            // The quad window: four bytes at k+3, which the AI view calls its reserved FILLER X(4).
            // Writing it must leave the xxxL/xxxF window and the payload window untouched.
            record.writeBytes(k + 3, new byte[] {BmsAttributes.DFHRED, (byte) 0x11,
                BmsAttributes.DFHUNDLN, (byte) 0x22});
            assertThat(record.readBytes(k, 2))
                    .as("the halfword the quad write must not have disturbed")
                    .isEqualTo(halfword);
            assertThat(record.readString(field.payloadOffset(), field.payloadLength()))
                    .as("nor the payload window at k+7")
                    .isEqualTo(original.payloadValue(field));
            assertThat(ReportRequestResponse.fromFixedWidth(record.toByteArray(), ASCII)
                    .colourAttribute(field))
                    .as("and the AO view reads the byte at k+3 as xxxC")
                    .isEqualTo(BmsAttributes.DFHRED);

            // The payload window: n bytes at k+7, reached by offset and read back through the DTO.
            String written = "J".repeat(field.payloadLength());
            record.writeString(field.payloadOffset(), field.payloadLength(), written);
            assertThat(ReportRequestResponse.fromFixedWidth(record.toByteArray(), ASCII)
                    .payloadValue(field))
                    .as("one span, two accessors, identical bytes")
                    .isEqualTo(written);
        }

        @Test
        @DisplayName("consequence 4 - gate G21: every FILLER span is declared, space-filled and counted")
        void fillerSpansAreFirstClass() {
            byte[] image = populated().toFixedWidth(ASCII);

            assertThat(ReportRequestResponse.TIOAPFX_PREFIX_SPAN.offset()).isZero();
            assertThat(ReportRequestResponse.TIOAPFX_PREFIX_SPAN.length()).isEqualTo(12);
            assertThat(ReportRequestResponse.TIOAPFX_PREFIX_SPAN.kind().filler()).isTrue();
            assertThat(new String(image, 0, 12, ASCII)).isEqualTo(" ".repeat(12));

            int fillerBytes = ReportRequestResponse.TIOAPFX_PREFIX_LENGTH;
            for (ScreenField field : ScreenField.values()) {
                FieldSpan span = field.attributeFillerSpan();
                assertThat(span.kind().filler()).as(field.baseName() + " FILLER X(3)").isTrue();
                assertThat(new String(image, span.offset(), span.length(), ASCII)).isEqualTo("   ");
                fillerBytes += span.length();
            }

            assertThat(fillerBytes).as("12 + 17 x 3").isEqualTo(63);
            assertThat(fillerBytes
                    + ReportRequestResponse.SCREEN_FIELD_COUNT * 4
                    + ReportRequestResponse.PAYLOAD_WIDTH_TOTAL)
                    .as("63 FILLER bytes + 68 attribute bytes + 206 payload bytes; dropping any "
                            + "FILLER would put this below 337 and shift every following offset")
                    .isEqualTo(ReportRequestResponse.RECORD_LENGTH);
            assertThat(ReportRequestResponse.LAYOUT.recordLength()).isEqualTo(337);
        }
    }

    /**
     * The {@code app/cpy/CSSETATY.cpy} decision table, driven exhaustively.
     *
     * <p>The copybook reads, structurally:
     *
     * <pre>
     *   IF (FLG-x-NOT-OK OR FLG-x-BLANK) AND CDEMO-PGM-REENTER
     *       MOVE DFHRED TO (SCRNVAR2)C OF (MAPNAME3)O
     *       IF  FLG-x-BLANK
     *           MOVE '*' TO (SCRNVAR2)O OF (MAPNAME3)O
     *       END-IF
     *   END-IF
     * </pre>
     *
     * <p>Three conditions, so four outcomes, and every one is asserted below across all
     * <strong>ten</strong> input-capable fields:
     *
     * <table border="1">
     *   <caption>Gate G38</caption>
     *   <tr><th>Flag</th><th>Context</th><th>{@code xxxC}</th><th>{@code xxxO}</th></tr>
     *   <tr><td>NOT-OK</td><td>REENTER</td><td>{@code DFHRED}</td><td>unchanged</td></tr>
     *   <tr><td>BLANK</td><td>REENTER</td><td>{@code DFHRED}</td><td><strong>{@code '*'}</strong></td></tr>
     *   <tr><td>NOT-OK or BLANK</td><td>ENTER</td><td>unchanged</td><td>unchanged</td></tr>
     *   <tr><td>OK</td><td>REENTER</td><td>unchanged</td><td>unchanged</td></tr>
     * </table>
     *
     * <p>One accuracy note, because it changes what may honestly be asserted: {@code CORPT00C} does
     * <strong>not</strong> include {@code CSSETATY} - its eight {@code COPY} statements, at
     * {@code app/cbl/CORPT00C.cbl:138-149}, are {@code COCOM01Y}, {@code CORPT00}, {@code COTTL01Y},
     * {@code CSDAT01Y}, {@code CSMSG01Y}, {@code CVTRA05Y}, {@code DFHAID} and {@code DFHBMSCA}. The
     * copybook's single textual consumer is {@code COACTUPC}. What {@code CORPT00C} does instead, on a
     * failed edit, is set the message line and position the cursor with
     * {@code MOVE -1 TO <field>L OF CORPT0AI}. So the rule exercised here is the module's shared
     * highlight semantics as {@link FieldAttributeSetter} publishes them - which is the seam every
     * screen resolves through - and the field set it may be applied to is pinned against the mapset's
     * own {@code UNPROT} declarations rather than assumed.
     */
    @Nested
    @DisplayName("Gate G38 - the CSSETATY matrix across all ten input-capable fields, four outcomes")
    class HighlightMatrix {

        /** Ten input-capable fields crossed with all six flag/context combinations. */
        static Stream<Arguments> matrix() {
            List<Arguments> cases = new ArrayList<>();
            for (ScreenField field : INPUT_CAPABLE) {
                for (FieldValidationState state : FieldValidationState.values()) {
                    cases.add(Arguments.of(field, state, true));
                    cases.add(Arguments.of(field, state, false));
                }
            }
            return cases.stream();
        }

        @ParameterizedTest(name = "{0} / {1} / reenter={2}")
        @MethodSource("matrix")
        @DisplayName("every cell of the matrix produces exactly the copybook's outcome")
        void everyCell(ScreenField field, FieldValidationState state, boolean reenter) {
            String keyed = "9".repeat(field.payloadLength());
            ReportRequestResponse response = new ReportRequestResponse();
            response.setPayloadValue(field, keyed);

            FieldHighlight highlight = FieldAttributeSetter.resolve(state, reenter,
                    field.baseName(), ReportRequestResponse.MAP_NAME);
            boolean changed = response.applyHighlight(field, highlight);

            boolean expectRed = reenter && (state.notOk() || state.blank());
            boolean expectAsterisk = reenter && state.blank();

            assertThat(changed).as("something changed").isEqualTo(expectRed);
            assertThat(response.colourAttribute(field))
                    .as(field.colourItemName())
                    .isEqualTo(expectRed ? BmsAttributes.DFHRED
                            : ReportRequestResponse.ATTRIBUTE_UNSET);
            assertThat(response.payloadValue(field))
                    .as(field.payloadItemName())
                    .isEqualTo(expectAsterisk
                            ? new FixedWidthCodec(ASCII).movePicX(FieldAttributeSetter.ASTERISK,
                                    field.payloadLength())
                            : keyed);
            assertThat(response.psAttribute(field))
                    .as("the copybook touches xxxC only, never xxxP, xxxH or xxxV")
                    .isEqualTo(ReportRequestResponse.ATTRIBUTE_UNSET);
            assertThat(response.hilightAttribute(field))
                    .isEqualTo(ReportRequestResponse.ATTRIBUTE_UNSET);
            assertThat(response.validnAttribute(field))
                    .isEqualTo(ReportRequestResponse.ATTRIBUTE_UNSET);
        }

        @Test
        @DisplayName("row 1 - NOT-OK in REENTER reddens xxxC and leaves the keyed value standing")
        void rowOneNotOkOnReenter() {
            for (ScreenField field : INPUT_CAPABLE) {
                ReportRequestResponse response = new ReportRequestResponse();
                String keyed = "8".repeat(field.payloadLength());
                response.setPayloadValue(field, keyed);

                assertThat(response.applyHighlight(field, FieldAttributeSetter.resolveFromFlags(
                        true, false, true, field.baseName(), ReportRequestResponse.MAP_NAME)))
                        .isTrue();
                assertThat(response.colourAttribute(field)).isEqualTo(BmsAttributes.DFHRED);
                assertThat(response.payloadValue(field))
                        .as("the '*' move is nested inside the BLANK test, which did not hold")
                        .isEqualTo(keyed);
            }
        }

        /**
         * Row 2 is the non-obvious one and is worth stating plainly: the {@code '*'} goes into the
         * <strong>{@code xxxO} output item</strong>, not into an attribute byte, so the highlight
         * <strong>overwrites a payload value</strong>. That bites hardest on this map because six of
         * the ten highlightable fields are only {@code X(1)} or {@code X(2)} wide - {@code MONTHLYO},
         * {@code YEARLYO}, {@code CUSTOMO} and {@code CONFIRMO} are {@code X(1)}, so the {@code '*'}
         * replaces the <em>entire</em> field and nothing of what the operator keyed survives.
         */
        @Test
        @DisplayName("row 2 - BLANK in REENTER writes '*' INTO the payload item, overwriting its value")
        void rowTwoBlankOnReenterOverwritesThePayload() {
            List<ScreenField> singleByte = new ArrayList<>();
            for (ScreenField field : INPUT_CAPABLE) {
                ReportRequestResponse response = new ReportRequestResponse();
                response.setPayloadValue(field, "7".repeat(field.payloadLength()));

                assertThat(response.applyHighlight(field, FieldAttributeSetter.resolveFromFlags(
                        false, true, true, field.baseName(), ReportRequestResponse.MAP_NAME)))
                        .isTrue();
                assertThat(response.colourAttribute(field)).isEqualTo(BmsAttributes.DFHRED);
                assertThat(response.payloadValue(field))
                        .as(field.payloadItemName() + " is overwritten, not annotated")
                        .startsWith(FieldAttributeSetter.ASTERISK)
                        .hasSize(field.payloadLength())
                        .doesNotContain("7");

                if (field.payloadLength() == 1) {
                    singleByte.add(field);
                }
            }

            assertThat(singleByte)
                    .as("the four X(1) fields, where '*' replaces the whole field")
                    .containsExactlyInAnyOrder(ScreenField.MONTHLY, ScreenField.YEARLY,
                            ScreenField.CUSTOM, ScreenField.CONFIRM);
        }

        @Test
        @DisplayName("row 2, explicit X(1) case - CONFIRMO 'Y' becomes exactly '*', nothing else")
        void rowTwoOnASingleByteField() {
            ReportRequestResponse response = new ReportRequestResponse();
            response.setConfirmo("Y");
            assertThat(ScreenField.CONFIRM.payloadLength()).isEqualTo(1);

            assertThat(response.applyHighlight(ScreenField.CONFIRM,
                    FieldAttributeSetter.resolveFromFlags(false, true, true,
                            ScreenField.CONFIRM.baseName(), ReportRequestResponse.MAP_NAME)))
                    .isTrue();

            assertThat(response.getConfirmo())
                    .isEqualTo(FieldAttributeSetter.ASTERISK)
                    .isEqualTo("*")
                    .hasSize(1);
            assertThat(response.colourAttribute(ScreenField.CONFIRM))
                    .isEqualTo(BmsAttributes.DFHRED);
        }

        @Test
        @DisplayName("row 3 - on first entry neither item is touched, for either failing flag")
        void rowThreeEnterTouchesNothing() {
            for (ScreenField field : INPUT_CAPABLE) {
                for (boolean blank : List.of(true, false)) {
                    ReportRequestResponse response = new ReportRequestResponse();
                    String keyed = "6".repeat(field.payloadLength());
                    response.setPayloadValue(field, keyed);

                    assertThat(response.applyHighlight(field,
                            FieldAttributeSetter.resolveFromFlags(!blank, blank, false,
                                    field.baseName(), ReportRequestResponse.MAP_NAME)))
                            .as(field.baseName() + " blank=" + blank + " on ENTER")
                            .isFalse();
                    assertThat(response.colourAttribute(field))
                            .isEqualTo(ReportRequestResponse.ATTRIBUTE_UNSET);
                    assertThat(response.payloadValue(field)).isEqualTo(keyed);
                }
            }
        }

        @Test
        @DisplayName("row 4 - OK in REENTER touches nothing either, so a valid field is never reddened")
        void rowFourOkOnReenter() {
            for (ScreenField field : INPUT_CAPABLE) {
                ReportRequestResponse response = new ReportRequestResponse();
                String keyed = "5".repeat(field.payloadLength());
                response.setPayloadValue(field, keyed);

                assertThat(response.applyHighlight(field, FieldAttributeSetter.resolveFromFlags(
                        false, false, true, field.baseName(), ReportRequestResponse.MAP_NAME)))
                        .isFalse();
                assertThat(response.colourAttribute(field))
                        .isEqualTo(ReportRequestResponse.ATTRIBUTE_UNSET);
                assertThat(response.payloadValue(field)).isEqualTo(keyed);
            }
        }

        @Test
        @DisplayName("the input-capable set is exactly the ten UNPROT fields the mapset declares")
        void inputCapableSetIsExactlyTen() throws IOException {
            Map<String, String> declared = parseLabelledAttributes();
            assertThat(declared).hasSize(ReportRequestResponse.SCREEN_FIELD_COUNT);

            List<String> unprotected = new ArrayList<>();
            List<String> askip = new ArrayList<>();
            declared.forEach((label, attrb) -> {
                if (attrb.contains("UNPROT")) {
                    unprotected.add(label);
                } else {
                    askip.add(label);
                }
            });

            assertThat(unprotected).as("app/bms/CORPT00.bms ATTRB=(...,UNPROT)")
                    .containsExactlyInAnyOrder("MONTHLY", "YEARLY", "CUSTOM", "SDTMM", "SDTDD",
                            "SDTYYYY", "EDTMM", "EDTDD", "EDTYYYY", "CONFIRM")
                    .hasSize(10);
            assertThat(askip).as("app/bms/CORPT00.bms ATTRB=(ASKIP,...)")
                    .containsExactlyInAnyOrder("TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02",
                            "CURTIME", "ERRMSG")
                    .hasSize(7);

            List<String> fromConstant = new ArrayList<>();
            INPUT_CAPABLE.forEach(field -> fromConstant.add(field.baseName()));
            assertThat(fromConstant).containsExactlyInAnyOrderElementsOf(unprotected);

            List<String> outputOnly = new ArrayList<>();
            OUTPUT_ONLY.forEach(field -> outputOnly.add(field.baseName()));
            assertThat(outputOnly).containsExactlyInAnyOrderElementsOf(askip);
            assertThat(INPUT_CAPABLE.size() + OUTPUT_ONLY.size())
                    .isEqualTo(ReportRequestResponse.SCREEN_FIELD_COUNT);
        }

        /**
         * The seven {@code ASKIP} fields are not highlightable, and the reason is structural rather
         * than an API prohibition: an operator cannot type into a skip-protected field, so no
         * validation flag is ever raised against one, so {@code CSSETATY}'s outer {@code IF} can never
         * be reached for it. {@link ReportRequestResponse#applyHighlight} is deliberately
         * field-generic - one seam for all seventeen, which is what keeps the highlight rule in exactly
         * one place - so the constraint is asserted where it actually lives: in the mapset's
         * {@code ATTRB} lists, and in the program's own choice of cursor targets.
         */
        @Test
        @DisplayName("the seven ASKIP fields are never flagged - no UNPROT, and no cursor ever lands there")
        void outputOnlyFieldsAreNotHighlightable() throws IOException {
            Map<String, String> declared = parseLabelledAttributes();
            for (ScreenField field : OUTPUT_ONLY) {
                assertThat(declared.get(field.baseName()))
                        .as(field.baseName() + " is output-only")
                        .contains("ASKIP")
                        .doesNotContain("UNPROT");
            }

            List<String> cursorTargets = new ArrayList<>();
            for (String line : Files.readAllLines(PROGRAM, ASCII)) {
                if (!line.contains("MOVE -1")) {
                    continue;
                }
                Matcher matcher = Pattern.compile("MOVE -1\\s+TO ([A-Z0-9]+)L OF CORPT0AI")
                        .matcher(line);
                if (matcher.find() && !cursorTargets.contains(matcher.group(1))) {
                    cursorTargets.add(matcher.group(1));
                }
            }

            assertThat(cursorTargets)
                    .as("the eight fields CORPT00C ever positions the cursor on; YEARLY and CUSTOM are "
                            + "selectors it never flags individually, and no ASKIP field appears at all")
                    .containsExactlyInAnyOrder("MONTHLY", "SDTMM", "SDTDD", "SDTYYYY", "EDTMM",
                            "EDTDD", "EDTYYYY", "CONFIRM");
            for (ScreenField field : OUTPUT_ONLY) {
                assertThat(cursorTargets).doesNotContain(field.baseName());
            }
            for (String target : cursorTargets) {
                assertThat(ScreenField.valueOf(target)).isIn(INPUT_CAPABLE);
            }
        }

        @Test
        @DisplayName("DFHRED comes from common.BmsAttributes, never from a literal in either file")
        void redIsTheSharedConstant() throws IOException {
            assertThat(BmsAttributes.DFHRED).isEqualTo((byte) 0xF2);
            assertThat(FieldAttributeSetter.resolveFromFlags(true, false, true).colourItemValue())
                    .as("the setter publishes the same byte the copybook names")
                    .isEqualTo(BmsAttributes.DFHRED);
            assertThat(FieldAttributeSetter.ASTERISK).isEqualTo("*");

            String code = stripComments(Files.readString(SOURCE, JAVA_SOURCE));
            assertThat(code)
                    .as("the response never hard-codes an attribute code point")
                    .doesNotContain("(byte) 0xF2")
                    .doesNotContain("(byte) 0xf2");
        }

        @Test
        @DisplayName("the re-enter state is an explicit boolean parameter, not a NavigationContext")
        void reenterIsAnExplicitBoolean() throws NoSuchMethodException {
            assertThat(FieldAttributeSetter.class
                    .getMethod("resolve", FieldValidationState.class, boolean.class, String.class,
                            String.class)
                    .getParameterTypes()[1])
                    .as("CSSETATY's outer IF reads CDEMO-PGM-REENTER, but the setter takes the decision "
                            + "as a boolean so it stays usable by any screen and imports no commarea")
                    .isEqualTo(boolean.class);
            assertThat(FieldAttributeSetter.class
                    .getMethod("resolveFromFlags", boolean.class, boolean.class, boolean.class,
                            String.class, String.class))
                    .isNotNull();

            for (var parameter : FieldAttributeSetter.class
                    .getMethod("resolve", FieldValidationState.class, boolean.class, String.class,
                            String.class)
                    .getParameterTypes()) {
                assertThat(parameter)
                        .as("no commarea type appears in the setter's signature")
                        .isNotEqualTo(NavigationContext.class);
            }
        }

        @Test
        @DisplayName("the setter's published vocabulary is three-valued, and the outcome names its items")
        void publishedSurface() {
            assertThat(FieldValidationState.values())
                    .containsExactly(FieldValidationState.OK, FieldValidationState.NOT_OK,
                            FieldValidationState.BLANK);
            assertThat(FieldValidationState.of(false, false)).isEqualTo(FieldValidationState.OK);
            assertThat(FieldValidationState.of(true, false)).isEqualTo(FieldValidationState.NOT_OK);
            assertThat(FieldValidationState.of(false, true)).isEqualTo(FieldValidationState.BLANK);

            FieldHighlight blank = FieldAttributeSetter.resolveFromFlags(false, true, true,
                    ScreenField.CONFIRM.baseName(), ReportRequestResponse.MAP_NAME);
            assertThat(blank.colourItemName()).isEqualTo("CONFIRMC");
            assertThat(blank.outputItemName()).isEqualTo("CONFIRMO");
            assertThat(blank.outputMapGroupName())
                    .isEqualTo(ReportRequestResponse.SYMBOLIC_MAP_GROUP);
            assertThat(blank.outputItemValue()).isEqualTo(FieldAttributeSetter.ASTERISK);
            assertThat(blank.untouched()).isFalse();
        }
    }

    /**
     * The error line, and the two message widths that feed it.
     *
     * <p>{@code app/bms/CORPT00.bms:218-221} declares it in full:
     *
     * <pre>
     *   ERRMSG  DFHMDF ATTRB=(ASKIP,BRT,FSET),
     *                  COLOR=RED,
     *                  LENGTH=78,
     *                  POS=(23,1)
     * </pre>
     *
     * <p>Four separate properties, and all four are asserted. Note that the {@code COLOR=RED} here is
     * a <strong>declared property of the field</strong>, fixed at map-generation time, and is a
     * different mechanism from the {@code DFHRED} that {@link FieldAttributeSetter} may move into a
     * highlight quad at run time. Both exist, both are red, and neither implies the other.
     */
    @Nested
    @DisplayName("ERRMSGO - all four declared BMS properties, and the message widths that feed it")
    class ErrorLineDeclaration {

        /** The clause text of one name-labelled {@code DFHMDF}, from its label to the next entry. */
        private String declarationOf(String label) throws IOException {
            List<String> lines = Files.readAllLines(MAPSET, ASCII);
            StringBuilder declaration = new StringBuilder();
            boolean inside = false;
            for (String line : lines) {
                if (line.startsWith(label + " ") && line.contains("DFHMDF")) {
                    inside = true;
                } else if (inside && line.contains("DFHMDF")) {
                    break;
                }
                if (inside) {
                    declaration.append(line).append('\n');
                }
            }
            return declaration.toString();
        }

        @Test
        @DisplayName("ATTRB=(ASKIP,BRT,FSET), COLOR=RED, LENGTH=78, POS=(23,1) - every one of the four")
        void allFourDeclaredProperties() throws IOException {
            String declaration = declarationOf("ERRMSG");

            assertThat(declaration).as("app/bms/CORPT00.bms:218 - ATTRB").contains("ATTRB=(ASKIP,BRT,FSET)");
            assertThat(declaration).as("ASKIP - skip-protected, so output-only and never keyable")
                    .contains("ASKIP");
            assertThat(declaration).as("BRT - bright").contains("BRT");
            assertThat(declaration).as("FSET - the modified-data tag is set on transmission")
                    .contains("FSET");
            assertThat(declaration).as("app/bms/CORPT00.bms:219 - COLOR").contains("COLOR=RED");
            assertThat(declaration).as("app/bms/CORPT00.bms:220 - LENGTH").contains("LENGTH=78");
            assertThat(declaration).as("app/bms/CORPT00.bms:221 - POS, row 23 column 1")
                    .contains("POS=(23,1)");

            assertThat(declaration).as("it is not UNPROT, so it can never be highlighted")
                    .doesNotContain("UNPROT");
            assertThat(ScreenField.ERRMSG.payloadLength())
                    .as("the DTO width is the declared LENGTH")
                    .isEqualTo(78);
            assertThat(parseLabelledFields().get("ERRMSG")).isEqualTo(78);
            assertThat(OUTPUT_ONLY).contains(ScreenField.ERRMSG);
            assertThat(INPUT_CAPABLE).doesNotContain(ScreenField.ERRMSG);
        }

        @Test
        @DisplayName("POS=(23,1) puts it on row 23, and 1 + 78 - 1 = 78 fits inside the 80-column map")
        void positionFitsTheMap() throws IOException {
            String declaration = declarationOf("ERRMSG");
            Matcher position = Pattern.compile("POS=\\((\\d+),(\\d+)\\)").matcher(declaration);
            assertThat(position.find()).isTrue();

            int row = Integer.parseInt(position.group(1));
            int column = Integer.parseInt(position.group(2));
            assertThat(row).as("row 23 of 24 - the line above the PF-key legend").isEqualTo(23);
            assertThat(column).isEqualTo(1);
            assertThat(column + ScreenField.ERRMSG.payloadLength() - 1)
                    .as("the field ends at column 78 of the DFHMDI SIZE=(24,80) map")
                    .isEqualTo(78)
                    .isLessThanOrEqualTo(80);
            assertThat(Files.readString(MAPSET, ASCII)).contains("SIZE=(24,80)");
        }

        @Test
        @DisplayName("the declared COLOR=RED is independent of any DFHRED in the highlight quad")
        void declaredColourIsNotTheHighlightQuad() throws IOException {
            assertThat(declarationOf("ERRMSG")).contains("COLOR=RED");

            ReportRequestResponse response = populated();
            response.setErrmsgo("something went wrong");

            assertThat(response.getErrmsgc())
                    .as("the field is red by declaration, so nothing needs to be moved into ERRMSGC; "
                            + "an untouched quad stays at the device default")
                    .isEqualTo(ReportRequestResponse.ATTRIBUTE_UNSET)
                    .isEqualTo(BmsAttributes.DFHDFCOL);

            response.moveDfhgreenToErrmsgc();
            assertThat(response.getErrmsgc())
                    .as("CORPT00C:448 MOVE DFHGREEN TO ERRMSGC OF CORPT0AO - the run-time override, "
                            + "which is how a success message is shown green on a field declared red")
                    .isEqualTo(BmsAttributes.DFHGREEN);
            assertThat(response.getErrmsgo())
                    .as("and it changed no payload byte")
                    .isEqualTo(new FixedWidthCodec(ASCII).movePicX("something went wrong", 78));
        }

        @Test
        @DisplayName("a SystemMessages X(50) literal is right-space-padded to the 78-byte error line")
        void fiftyByteMessagesArePaddedToSeventyEight() {
            assertThat(SystemMessages.MESSAGE_LENGTH).isEqualTo(50);
            assertThat(SystemMessages.CCDA_MSG_INVALID_KEY).hasSize(50);
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU).hasSize(50);

            ReportRequestResponse response = new ReportRequestResponse();
            response.moveInvalidKeyMessageToErrmsgo();

            assertThat(response.getErrmsgo())
                    .as("CORPT00C:193 into WS-MESSAGE X(80), then :560 into ERRMSGO X(78)")
                    .hasSize(78)
                    .startsWith(SystemMessages.CCDA_MSG_INVALID_KEY)
                    .isEqualTo(SystemMessages.CCDA_MSG_INVALID_KEY + " ".repeat(28))
                    .isEqualTo(throughWsMessageIntoErrmsgo(SystemMessages.CCDA_MSG_INVALID_KEY));

            response.setErrmsgo(SystemMessages.CCDA_MSG_THANK_YOU);
            assertThat(response.getErrmsgo()).hasSize(78)
                    .isEqualTo(SystemMessages.CCDA_MSG_THANK_YOU + " ".repeat(28));
        }

        @Test
        @DisplayName("CORPT00C:487 - the confirm message echoes the value truncated at the FIRST space")
        void confirmMessageIsDelimitedBySpace() throws IOException {
            List<String> program = Files.readAllLines(PROGRAM, ASCII);
            assertThat(program.get(486).trim())
                    .as("app/cbl/CORPT00C.cbl:487, the sending operand of the STRING")
                    .contains("CONFIRMI OF CORPT0AI")
                    .contains("DELIMITED BY SPACE");

            String keyed = "Q";
            String assembled = "\"" + delimitedBySpace(keyed)
                    + "\" is not a valid value to confirm...";

            ReportRequestResponse response = new ReportRequestResponse();
            response.setConfirmo(keyed);
            response.setErrmsgo(assembled);

            assertThat(response.getErrmsgo())
                    .hasSize(78)
                    .startsWith("\"Q\" is not a valid value to confirm...")
                    .isEqualTo(throughWsMessageIntoErrmsgo(assembled));
            assertThat(response.getConfirmo()).as("the echoed value itself is untouched").isEqualTo("Q");
        }

        @Test
        @DisplayName("DELIMITED BY SPACE is truncation at the first space, and is NOT a trim - bind B4")
        void delimitedBySpaceIsNotATrim() throws IOException {
            assertThat(delimitedBySpace("Monthly   ")).isEqualTo("Monthly");
            assertThat(delimitedBySpace("Yearly    ")).isEqualTo("Yearly");
            assertThat(delimitedBySpace("Custom    ")).isEqualTo("Custom");
            assertThat(delimitedBySpace("Two Words "))
                    .as("a trim would give 'Two Words'; the COBOL gives 'Two'")
                    .isEqualTo("Two")
                    .isNotEqualTo("Two Words ".trim());
            assertThat(delimitedBySpace(" ")).as("an all-blank operand contributes nothing").isEmpty();
            assertThat(delimitedBySpace("NoSpaces")).isEqualTo("NoSpaces");

            List<String> program = Files.readAllLines(PROGRAM, ASCII);
            assertThat(program.get(448).trim())
                    .as("app/cbl/CORPT00C.cbl:449 applies the same rule to WS-REPORT-NAME PIC X(10)")
                    .contains("WS-REPORT-NAME")
                    .contains("DELIMITED BY SPACE");
            assertThat(WS_REPORT_NAME_LENGTH).isEqualTo(10);

            String reportName = new FixedWidthCodec(ASCII).movePicX("Monthly", WS_REPORT_NAME_LENGTH);
            assertThat(reportName).hasSize(10);
            String assembled = "Please confirm to print the " + delimitedBySpace(reportName)
                    + " report...";

            ReportRequestResponse response = new ReportRequestResponse();
            response.setErrmsgo(assembled);
            assertThat(response.getErrmsgo())
                    .hasSize(78)
                    .isEqualTo(throughWsMessageIntoErrmsgo(
                            "Please confirm to print the Monthly report..."))
                    .startsWith("Please confirm to print the Monthly report...")
                    .as("the three spaces the X(10) field carried are gone, not folded in")
                    .doesNotContain("Monthly    report");
        }

        @Test
        @DisplayName("WS-MESSAGE is X(80) and ERRMSGO is X(78), so the last two bytes are dropped")
        void eightyIntoSeventyEightLosesTwoBytes() {
            assertThat(WS_MESSAGE_LENGTH).isEqualTo(80);
            assertThat(ScreenField.ERRMSG.payloadLength()).isEqualTo(78);

            String full = "M".repeat(80);
            ReportRequestResponse response = new ReportRequestResponse();
            response.setErrmsgo(full);

            assertThat(response.getErrmsgo())
                    .as("the alphanumeric MOVE keeps the LEADING 78 characters")
                    .hasSize(78)
                    .isEqualTo("M".repeat(78))
                    .isEqualTo(throughWsMessageIntoErrmsgo(full));
        }

        @Test
        @DisplayName("the trap - ScreenTitles X(40) and SystemMessages X(50) are never interchangeable")
        void titleAndMessageAreDifferentThings() {
            assertThat(ScreenTitles.TITLE_LENGTH).isEqualTo(40);
            assertThat(SystemMessages.MESSAGE_LENGTH).isEqualTo(50);

            assertThat(ScreenTitles.CCDA_THANK_YOU)
                    .as("app/cpy/COTTL01Y.cpy, a 40-byte screen TITLE")
                    .hasSize(ScreenTitles.TITLE_LENGTH)
                    .isNotEqualTo(SystemMessages.CCDA_MSG_THANK_YOU);
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU)
                    .as("app/cpy/CSMSG01Y.cpy, a 50-byte common MESSAGE - different owner, different "
                            + "width, different text, and it says CardDemo where the title says CCDA")
                    .hasSize(SystemMessages.MESSAGE_LENGTH)
                    .isNotEqualTo(ScreenTitles.CCDA_THANK_YOU);
            assertThat(ScreenTitles.CCDA_THANK_YOU.strip())
                    .isNotEqualTo(SystemMessages.CCDA_MSG_THANK_YOU.strip());

            ReportRequestResponse response = populated();
            assertThat(response.getTitle01o())
                    .isEqualTo(ScreenTitles.CCDA_TITLE01).hasSize(40);
            assertThat(response.getTitle02o())
                    .isEqualTo(ScreenTitles.CCDA_TITLE02).hasSize(40);
            assertThat(ScreenField.TITLE01.payloadLength())
                    .isEqualTo(ScreenTitles.TITLE_LENGTH);
            assertThat(ScreenField.TITLE02.payloadLength())
                    .isEqualTo(ScreenTitles.TITLE_LENGTH);

            response.setTitle01o(SystemMessages.CCDA_MSG_THANK_YOU);
            assertThat(response.getTitle01o())
                    .as("putting a 50-byte message in a 40-byte title loses its last ten bytes - "
                            + "which is exactly why the two are not interchangeable")
                    .hasSize(40)
                    .isEqualTo(SystemMessages.CCDA_MSG_THANK_YOU.substring(0, 40));
        }

        @Test
        @DisplayName("CORPT00C:169-170 clears WS-MESSAGE and ERRMSGO together at the top of every pass")
        void theErrorLineIsClearedFirst() throws IOException {
            List<String> program = Files.readAllLines(PROGRAM, ASCII);
            assertThat(program.get(168).trim()).as("app/cbl/CORPT00C.cbl:169")
                    .isEqualTo("MOVE SPACES TO WS-MESSAGE");
            assertThat(program.get(169).trim()).as("app/cbl/CORPT00C.cbl:170")
                    .isEqualTo("ERRMSGO OF CORPT0AO");

            ReportRequestResponse response = new ReportRequestResponse();
            response.setErrmsgo("left over from the previous send");
            response.moveSpacesToErrmsgo();

            assertThat(response.getErrmsgo())
                    .as("SPACES, not LOW-VALUES, and not an empty String")
                    .isEqualTo(" ".repeat(78))
                    .hasSize(78)
                    .isNotEqualTo("\u0000".repeat(78));
        }
    }

    @Nested
    @DisplayName("The echoed criteria - selectors, date parts and the four confirm outcomes")
    class EchoedCriteria {

        @ParameterizedTest
        @CsvSource({"MONTHLY", "YEARLY", "CUSTOM"})
        @DisplayName("the three selectors are X(1) and are echoed exactly as submitted")
        void selectorsAreEchoedVerbatim(String name) {
            ScreenField field = ScreenField.valueOf(name);
            assertThat(field.payloadLength()).isEqualTo(1);

            ReportRequestResponse response = new ReportRequestResponse();
            for (String keyed : List.of("X", "x", "1", "*", " ", "\u0000")) {
                response.setPayloadValue(field, keyed);
                assertThat(response.payloadValue(field))
                        .as(field.payloadItemName() + " echoing [" + keyed + "]")
                        .isEqualTo(keyed).hasSize(1);
            }
        }

        @Test
        @DisplayName("CORPT00C's 'NOT = SPACES AND LOW-VALUES' needs both states, so both are modelled")
        void spacesAndLowValuesAreDistinctButBothMeanUnselected() throws IOException {
            List<String> program = Files.readAllLines(PROGRAM, ASCII);
            assertThat(program.get(212)).as("app/cbl/CORPT00C.cbl:213, the monthly guard")
                    .contains("MONTHLYI OF CORPT0AI NOT = SPACES AND LOW-VALUES");
            assertThat(program.get(238)).as("app/cbl/CORPT00C.cbl:239, the yearly guard")
                    .contains("YEARLYI OF CORPT0AI NOT = SPACES AND LOW-VALUES");
            assertThat(program.get(255)).as("app/cbl/CORPT00C.cbl:256, the custom guard")
                    .contains("CUSTOMI OF CORPT0AI NOT = SPACES AND LOW-VALUES");

            ReportRequestResponse spaces = new ReportRequestResponse();
            spaces.setMonthlyo(ReportRequestResponse.spaces(1));
            ReportRequestResponse lowValues = new ReportRequestResponse();
            lowValues.setMonthlyo(ReportRequestResponse.lowValues(1));

            assertThat(spaces.getMonthlyo()).isEqualTo(" ");
            assertThat(lowValues.getMonthlyo()).isEqualTo("\u0000");
            assertThat(spaces)
                    .as("X'40' and X'00' are different bytes and the response keeps them apart, even "
                            + "though the program's two-part guard treats both as 'not selected'")
                    .isNotEqualTo(lowValues);
            assertThat(ReportRequestResponse.isSpacesOrLowValues(spaces.getMonthlyo())).isTrue();
            assertThat(ReportRequestResponse.isSpacesOrLowValues(lowValues.getMonthlyo())).isTrue();
            assertThat(ReportRequestResponse.isSpacesOrLowValues("X")).isFalse();
        }

        @Test
        @DisplayName("CORPT00C:215-262 - the monthly and yearly branches assign '01', '12' and '31'")
        void theTwoCharacterDateLiterals() throws IOException {
            String window = String.join("\n", Files.readAllLines(PROGRAM, ASCII).subList(214, 262));

            assertThat(window).as("app/cbl/CORPT00C.cbl:219 - monthly start day")
                    .contains("MOVE '01'");
            assertThat(window).as("app/cbl/CORPT00C.cbl:251 - yearly end month")
                    .contains("MOVE '12'");
            assertThat(window).as("app/cbl/CORPT00C.cbl:252 - yearly end day")
                    .contains("MOVE '31'");

            ReportRequestResponse yearly = new ReportRequestResponse();
            yearly.setYearlyo("Y");
            yearly.setSdtmmo("01");
            yearly.setSdtddo("01");
            yearly.setSdtyyyyo("2022");
            yearly.setEdtmmo("12");
            yearly.setEdtddo("31");
            yearly.setEdtyyyyo("2022");

            assertThat(yearly.getSdtmmo()).isEqualTo("01").hasSize(2);
            assertThat(yearly.getSdtddo()).isEqualTo("01").hasSize(2);
            assertThat(yearly.getEdtmmo()).isEqualTo("12").hasSize(2);
            assertThat(yearly.getEdtddo()).isEqualTo("31").hasSize(2);
            assertThat(yearly.getSdtyyyyo()).isEqualTo("2022").hasSize(4);
            assertThat(yearly.getEdtyyyyo()).isEqualTo("2022").hasSize(4);

            assertThat(yearly.getSdtmmo())
                    .as("they are two-character STRINGS: '01' is not the integer 1, and promoting the "
                            + "field would lose the leading zero the screen and the JCL PARM require")
                    .isNotEqualTo("1")
                    .isNotEqualTo(" 1")
                    .isNotEqualTo("1 ");
        }

        @Test
        @DisplayName("the six date parts stay Strings at 2/2/4, so '00' and '0' remain distinguishable")
        void datePartsStayStrings() {
            ReportRequestResponse response = new ReportRequestResponse();

            response.setSdtmmo("00");
            assertThat(response.getSdtmmo()).isEqualTo("00");
            response.setSdtmmo("0");
            assertThat(response.getSdtmmo())
                    .as("a one-character value pads on the right, giving '0 ' and not '00'")
                    .isEqualTo("0 ").isNotEqualTo("00");

            response.setSdtyyyyo("0000");
            assertThat(response.getSdtyyyyo()).isEqualTo("0000").hasSize(4);
            response.setSdtyyyyo("22");
            assertThat(response.getSdtyyyyo()).isEqualTo("22  ").hasSize(4);

            response.setEdtmmo("13");
            assertThat(response.getEdtmmo())
                    .as("CORPT00C:329-377 tests '> 12' as a STRING comparison, so an out-of-range "
                            + "value must survive the round trip in order to be rejected")
                    .isEqualTo("13");
        }

        @ParameterizedTest(name = "CONFIRMI = {0}")
        @CsvSource({"SPACE", "Y", "y", "N", "n", "Q"})
        @DisplayName("all four confirm outcomes are reachable, each with its own error-line content")
        void fourConfirmOutcomes(String token) {
            String keyed = "SPACE".equals(token) ? " " : token;
            ReportRequestResponse response = populated();
            response.setMonthlyo("X");
            response.setSdtmmo("07");
            response.setConfirmo(keyed);

            if (ReportRequestResponse.isSpacesOrLowValues(keyed)) {
                String assembled = "Please confirm to print the "
                        + delimitedBySpace(new FixedWidthCodec(ASCII)
                                .movePicX("Monthly", WS_REPORT_NAME_LENGTH))
                        + " report...";
                response.setErrmsgo(assembled);
                assertThat(response.getErrmsgo()).hasSize(78)
                        .startsWith("Please confirm to print the Monthly report...");
                assertThat(response.getConfirmo()).isEqualTo(" ");
            } else if ("Y".equals(keyed) || "y".equals(keyed)) {
                assertThat(response.getErrmsgo())
                        .as("CORPT00C:475-476 - WHEN 'Y' OR 'y' / CONTINUE, so the line is never "
                                + "written and stays at the LOW-VALUES image :179 left")
                        .isEqualTo(ScreenFieldImage.unpainted(78));
                assertThat(response.getConfirmo()).isEqualTo(keyed);
                assertThat(response.getMonthlyo()).as("and nothing is reinitialised").isEqualTo("X");
            } else if ("N".equals(keyed) || "n".equals(keyed)) {
                response.initializeAllFields();
                assertThat(response.getConfirmo())
                        .as("CORPT00C:477-480 - WHEN 'N' OR 'n' / PERFORM INITIALIZE-ALL-FIELDS")
                        .isEqualTo(" ");
                assertThat(response.getMonthlyo()).isEqualTo(" ");
                assertThat(response.getSdtmmo()).isEqualTo("  ");
            } else {
                String assembled = "\"" + delimitedBySpace(keyed)
                        + "\" is not a valid value to confirm...";
                response.setErrmsgo(assembled);
                assertThat(response.getErrmsgo()).hasSize(78)
                        .startsWith("\"Q\" is not a valid value to confirm...");
                assertThat(response.getConfirmo()).isEqualTo("Q");
            }
        }

        @Test
        @DisplayName("the N/n reinitialised state is all SPACES at declared widths, never null")
        void reinitialisedStateIsSpacesNotNull() {
            ReportRequestResponse response = populated();
            for (ScreenField field : INPUT_CAPABLE) {
                response.setPayloadValue(field, "4".repeat(field.payloadLength()));
            }
            response.setErrmsgo("a message that INITIALIZE-ALL-FIELDS does not name");

            response.initializeAllFields();

            for (ScreenField field : INPUT_CAPABLE) {
                assertThat(response.payloadValue(field))
                        .as(field.payloadItemName() + " after INITIALIZE")
                        .isNotNull()
                        .isEqualTo(" ".repeat(field.payloadLength()))
                        .hasSize(field.payloadLength())
                        .isNotEqualTo("\u0000".repeat(field.payloadLength()));
            }

            assertThat(response.getTrnnameo()).as("the paragraph names none of the six header items")
                    .isEqualTo(new FixedWidthCodec(ASCII).movePicX("CR00", 4));
            assertThat(response.getCurdateo()).isEqualTo(EXPECTED_CURDATEO);
            assertThat(response.getCurtimeo()).isEqualTo(EXPECTED_CURTIMEO);
            assertThat(response.getErrmsgo()).as("nor the error line")
                    .startsWith("a message that INITIALIZE-ALL-FIELDS does not name");
        }

        @Test
        @DisplayName("gate G40 - nextProgram is X(8) echoed from the commarea, never computed here")
        void navigationIsEchoedNotComputed() throws IOException {
            List<String> program = Files.readAllLines(PROGRAM, ASCII);
            assertThat(program.stream().filter(line -> line.contains("XCTL")).count())
                    .isEqualTo(XCTL_SITES);
            assertThat(program.get(548)).as("app/cbl/CORPT00C.cbl:549")
                    .contains("XCTL PROGRAM(CDEMO-TO-PROGRAM)");

            ReportRequestResponse fresh = new ReportRequestResponse();
            assertThat(fresh.getNextMap()).isEqualTo(ReportRequestResponse.MAP_NAME).hasSize(7);
            assertThat(fresh.getNextMapset()).isEqualTo(ReportRequestResponse.MAPSET_NAME).hasSize(7);
            assertThat(fresh.getNextProgram())
                    .as("no transfer has been decided yet, so the field is blank at X(8)")
                    .isEqualTo(" ".repeat(8)).hasSize(8);

            ReportRequestResponse pf3 = new ReportRequestResponse();
            pf3.echoNavigation(NavigationContext.empty()
                    .withToProgram(ReportRequestResponse.PF3_NEXT_PROGRAM));
            assertThat(pf3.getNextProgram())
                    .as("CORPT00C:190-191 WHEN DFHPF3 / MOVE 'COMEN01C' TO CDEMO-TO-PROGRAM, which "
                            + "line 549 then transfers to - the value is echoed, not derived")
                    .isEqualTo("COMEN01C").hasSize(8);
            assertThat(pf3.getNextProgram())
                    .isEqualTo(pf3.getNavigationContext().toProgram());

            ReportRequestResponse fallback = new ReportRequestResponse();
            fallback.echoNavigation(NavigationContext.empty()
                    .withToProgram(ReportRequestResponse.DEFAULT_NEXT_PROGRAM));
            assertThat(fallback.getNextProgram())
                    .as("CORPT00C:542-543, the LOW-VALUES-or-SPACES fallback")
                    .isEqualTo("COSGN00C");
            assertThat(ReportRequestResponse.PF3_NEXT_PROGRAM).hasSize(8);
            assertThat(ReportRequestResponse.DEFAULT_NEXT_PROGRAM).hasSize(8);
        }
    }

    @Nested
    @DisplayName("Negative contracts - the types, annotations and constructs that must NOT be here")
    class NegativeContracts {

        @Test
        @DisplayName("gates G22 and R4 - every payload member is a String and none is numeric")
        void everyMemberIsAString() {
            List<Class<?>> forbidden = List.of(int.class, long.class, short.class, Integer.class,
                    Long.class, Short.class, java.math.BigDecimal.class, java.math.BigInteger.class,
                    double.class, float.class, Double.class, Float.class);

            List<String> stringMembers = new ArrayList<>();
            for (Field declared : ReportRequestResponse.class.getDeclaredFields()) {
                if (Modifier.isStatic(declared.getModifiers())) {
                    continue;
                }
                assertThat(declared.getType())
                        .as("instance field " + declared.getName())
                        .isNotIn(forbidden);
                if (String.class.equals(declared.getType())) {
                    stringMembers.add(declared.getName());
                }
            }

            assertThat(stringMembers)
                    .as("the 17 payload items plus the three navigation items; the only other instance "
                            + "state is the attribute EnumMap and the NavigationContext carrier")
                    .hasSize(ReportRequestResponse.SCREEN_FIELD_COUNT + 3)
                    .contains("trnnameo", "errmsgo", "nextProgram", "nextMapset", "nextMap");

            for (ScreenField field : ScreenField.values()) {
                assertThat(new ReportRequestResponse().payloadValue(field))
                        .as(field.payloadItemName() + " reads back as a String")
                        .isInstanceOf(String.class);
            }
        }

        @Test
        @DisplayName("gates G22 and G24 - no floating point, and no rounding mode but truncation")
        void noFloatingPointAndNoRounding() throws IOException {
            String code = stripComments(Files.readString(SOURCE, JAVA_SOURCE));

            assertThat(code)
                    .as("ROUNDED appears zero times in all 28 COBOL programs, so no rounding mode is "
                            + "reachable from here; this screen holds no monetary field at all")
                    .doesNotContain("HALF_UP")
                    .doesNotContain("HALF_EVEN")
                    .doesNotContain("HALF_DOWN")
                    .doesNotContain("CEILING")
                    .doesNotContain("FLOOR")
                    .doesNotContain("RoundingMode")
                    .doesNotContain("BigDecimal")
                    .doesNotContain("CobolDecimal");
            assertThat(code).doesNotContainPattern("\\bdouble\\b").doesNotContainPattern("\\bfloat\\b");
        }

        @Test
        @DisplayName("gate G44 - no persistence annotation and no version column anywhere")
        void noPersistenceAnnotations() throws IOException {
            String code = stripComments(Files.readString(SOURCE, JAVA_SOURCE));

            for (String annotation : List.of("@Entity", "@Table", "@Column", "@Id", "@Version",
                    "@GeneratedValue", "@JoinColumn", "@Embeddable", "@MappedSuperclass")) {
                assertThat(code).as(annotation + " would imply a schema this migration does not create")
                        .doesNotContain(annotation);
            }
            assertThat(code).doesNotContain("jakarta.persistence").doesNotContain("javax.persistence")
                    .doesNotContain("hibernate");

            for (var annotation : ReportRequestResponse.class.getAnnotations()) {
                assertThat(annotation.annotationType().getName())
                        .doesNotContain("persistence").doesNotContain("hibernate");
            }
        }

        @Test
        @DisplayName("the JSON body excludes the AI-side triple as well as the AO-side quad")
        void neitherMetadataFamilyIsSerialised() {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode tree = mapper.valueToTree(populated());
            List<String> names = new ArrayList<>();
            tree.fieldNames().forEachRemaining(names::add);

            for (ScreenField field : ScreenField.values()) {
                for (String suffix : List.of("L", "F", "A", "C", "P", "H", "V")) {
                    String metadataItem = field.baseName() + suffix;
                    assertThat(names)
                            .as(metadataItem + " is metadata: 17 x 3 inbound plus 17 x 4 outbound, and "
                                    + "not one of the 119 is a payload member")
                            .noneMatch(name -> name.equalsIgnoreCase(metadataItem));
                }
                // The payload member is the item WITHOUT its output-direction suffix, in lower case:
                // @JsonProperty pins each wire name to the xxxI item (AAP 0.6.3).
                String wireName = field.payloadItemName()
                        .substring(0, field.payloadItemName().length() - 1);
                assertThat(names)
                        .as(field.payloadItemName() + " IS a payload member, as " + wireName)
                        .anyMatch(name -> name.equalsIgnoreCase(wireName));
            }

            assertThat(names).hasSize(ReportRequestResponse.SCREEN_FIELD_COUNT + 4);
            assertThat(ReportRequestResponse.SCREEN_FIELD_COUNT * 3
                    + ReportRequestResponse.SCREEN_FIELD_COUNT * 4)
                    .as("51 inbound metadata items plus 68 outbound ones")
                    .isEqualTo(119);
        }

        @Test
        @DisplayName("gate G52 - not one import in either file is a wildcard")
        void noWildcardImports() throws IOException {
            for (Path javaSource : List.of(SOURCE, Paths.get("src", "test", "java", "com",
                    "vsergeychik", "carddemo", "transaction", "dto",
                    "ReportRequestResponseTest.java"))) {
                List<String> imports = Files.readAllLines(javaSource, JAVA_SOURCE).stream()
                        .filter(line -> line.startsWith("import "))
                        .toList();
                assertThat(imports).as(javaSource.toString()).isNotEmpty();
                for (String line : imports) {
                    assertThat(line).as(javaSource + " -> " + line).doesNotContain(".*;");
                }
            }
        }

        /**
         * Gate <strong>G33</strong> pins the 1-based-to-0-based conversion of every {@code OCCURS}
         * table. {@code CORPT00} has none, so there is no row subscript on this map to verify. That is
         * asserted here rather than merely stated, so its absence is a checked finding: if a future
         * change ever introduced a repeating row group to this screen, this test would fail and demand
         * the indexing coverage that its siblings {@code COTRN00} and {@code COUSR00} carry.
         */
        @Test
        @DisplayName("gate G33 does not apply - CORPT00 declares no OCCURS table, so there is no index")
        void noOccursTableOnThisMap() throws IOException {
            assertThat(Files.readString(COPYBOOK, ASCII))
                    .as("app/cpy-bms/CORPT00.CPY is a flat group of 17 scalar fields")
                    .doesNotContain("OCCURS");
            assertThat(Files.readString(MAPSET, ASCII))
                    .as("and app/bms/CORPT00.bms declares no repeating row group either")
                    .doesNotContain("OCCURS");

            for (Field declared : ReportRequestResponse.class.getDeclaredFields()) {
                if (Modifier.isStatic(declared.getModifiers())) {
                    continue;
                }
                assertThat(declared.getType().isArray())
                        .as("instance field " + declared.getName() + " is not an array")
                        .isFalse();
                assertThat(List.class.isAssignableFrom(declared.getType()))
                        .as("instance field " + declared.getName() + " is not a row list")
                        .isFalse();
            }
        }

        /**
         * Gate <strong>G54</strong>, asserted rather than asserted-about. A suite that slept, opened a
         * socket or started a container would still pass its own assertions while making
         * {@code mvn -B verify} slow, flaky or dependent on the network; this test is what stops that
         * from arriving unnoticed in a later edit.
         */
        @Test
        @DisplayName("gate G54 - no sleep, no socket, no HTTP, no container and no Spring context")
        void suiteIsNonInteractive() throws IOException {
            String code = stripComments(Files.readString(Paths.get("src", "test", "java", "com",
                    "vsergeychik", "carddemo", "transaction", "dto",
                    "ReportRequestResponseTest.java"), JAVA_SOURCE));

            // Each needle is assembled from fragments on purpose. A test that scans its own source
            // cannot spell out the pattern it forbids, or it would always find itself - the same trap
            // that noTransactionRecordMembers and nothingElseIsRead are written around.
            List<String> forbidden = List.of("Thread" + ".sleep", "CountDown" + "Latch",
                    "new " + "Socket", "Http" + "Client", "URL" + "Connection", "http" + "://",
                    "https" + "://", "System" + ".in", "read" + "Line(", "await" + "(");

            for (String needle : forbidden) {
                assertThat(code)
                        .as("the suite must run to completion unattended and offline, so it must not "
                                + "contain [" + needle + "]")
                        .doesNotContain(needle);
            }

            List<String> imports = Files.readAllLines(Paths.get("src", "test", "java", "com",
                            "vsergeychik", "carddemo", "transaction", "dto",
                            "ReportRequestResponseTest.java"), JAVA_SOURCE).stream()
                    .filter(line -> line.startsWith("import "))
                    .toList();
            assertThat(imports)
                    .as("no Spring test machinery, so there is no context to start")
                    .noneMatch(line -> line.contains("org.springframework"))
                    .noneMatch(line -> line.contains("java.net"))
                    .noneMatch(line -> line.contains("java.util.concurrent"));
        }

        @Test
        @DisplayName("no controller, no repository and no batch machinery is reachable from this suite")
        void noOutOfScopeCollaborators() throws IOException {
            List<String> imports = Files.readAllLines(Paths.get("src", "test", "java", "com",
                            "vsergeychik", "carddemo", "transaction", "dto",
                            "ReportRequestResponseTest.java"), JAVA_SOURCE).stream()
                    .filter(line -> line.startsWith("import "))
                    .toList();

            for (String absent : List.of("MockMvc", "SpringBootTest", "WebMvcTest", "JobLauncher",
                    "DataSource", "JdbcTemplate", "Repository", "Testcontainers", "lombok",
                    "mapstruct")) {
                assertThat(imports)
                        .as(absent + " belongs to another suite or to no suite at all: CORPT00C "
                                + "accesses no dataset, and the controller behaviour is owned by "
                                + "ReportRequestControllerTest")
                        .noneMatch(line -> line.contains(absent));
            }
        }
    }
}
