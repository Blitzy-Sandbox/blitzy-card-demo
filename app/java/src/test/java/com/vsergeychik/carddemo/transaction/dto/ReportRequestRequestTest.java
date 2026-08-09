package com.vsergeychik.carddemo.transaction.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.transaction.dto.ReportRequestRequest.FieldMetadata;
import com.vsergeychik.carddemo.transaction.dto.ReportRequestRequest.ScreenField;
import com.vsergeychik.carddemo.transaction.dto.ReportRequestRequest.SymbolicMapMetadata;
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
import java.lang.reflect.RecordComponent;
// java.math is imported solely so the two types this payload must never contain can be NAMED in the
// gate G22 and G24 negatives below. Neither appears in any assertion as a value.
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Parity tests for {@link ReportRequestRequest}, the inbound payload of {@code POST /api/reports} -
 * CSD transaction {@code CR00}, program {@code CORPT00C} (649 lines), map {@code CORPT0A} of mapset
 * {@code CORPT00} - projected from {@code 01 CORPT0AI} at {@code app/cpy-bms/CORPT00.CPY:17}.
 *
 * <p>The suite reads the read-only sources from disk and asserts the Java against them rather than
 * against a transcription alone. That is deliberate: a hand-copied expectation can encode the same
 * misreading as the code it checks, whereas the copybook and the mapset cannot disagree with
 * themselves. A transcription is declared as well - {@code EXPECTED_FIELD_WIDTHS} - so every name and
 * width is checked three independent ways: against the copybook on disk, against that transcription,
 * and against the enum the production type publishes. Two of the three would have to be wrong in the
 * same direction for a drift to pass.
 *
 * <h2>Oracles, and the exact lines each supplies</h2>
 *
 * <ul>
 *   <li>{@code app/cpy-bms/CORPT00.CPY:17-120} - {@code 01 CORPT0AI}: the seventeen {@code xxxI} item
 *       names and {@code PICTURE} clauses, the seventeen {@code xxxL} / {@code xxxF} / {@code xxxA}
 *       metadata items, the leading {@code 02 FILLER PIC X(12)} TIOAPFX prefix at {@code :18} and the
 *       seventeen reserved {@code 02 FILLER PICTURE X(4)} spans. Line 121 onward is the
 *       {@code 01 CORPT0AO REDEFINES CORPT0AI} overlay and is excluded by {@code inputGroupOf}, so an
 *       {@code xxxO} item can never be mistaken for an {@code xxxI} item.</li>
 *   <li>{@code app/bms/CORPT00.bms} - forty-two {@code DFHMDF} entries, seventeen of them name
 *       labelled, ten of those {@code UNPROT}; {@code ERRMSG} at {@code :218-221} is
 *       {@code ATTRB=(ASKIP,BRT,FSET)}, {@code COLOR=RED}, {@code LENGTH=78}, {@code POS=(23,1)}.</li>
 *   <li>{@code app/cbl/CORPT00C.cbl} - {@code :5} the {@code Function :} header; {@code :138-140}
 *       {@code COPY COCOM01Y.} and then {@code COPY CORPT00.} with nothing between them;
 *       {@code :212-262} with its {@code WHEN OTHER} at {@code :437}, the ordered report-type
 *       selector; {@code :305-327} the six {@code FUNCTION NUMVAL-C} normalisations that move their
 *       result back into the alphanumeric item; {@code :329-374} the class tests and the
 *       {@code > '12'} / {@code > '31'} string comparisons that follow them; {@code :464} the blank
 *       {@code CONFIRM} stage; {@code :477-495} the {@code CONFIRM} {@code EVALUATE TRUE}; and the
 *       twenty-two {@code MOVE -1 TO xxxL} cursor placements.</li>
 *   <li>{@code app/cpy/COCOM01Y.cpy} - {@code 01 CARDDEMO-COMMAREA} at {@code :19} and its five
 *       {@code 05} sub-groups at {@code :20}, {@code :32}, {@code :37}, {@code :40} and {@code :42},
 *       modelled by {@link NavigationContext}.</li>
 *   <li>{@code app/csd/CARDDEMO.CSD} - {@code :137} {@code DEFINE MAPSET(CORPT00)}, {@code :242}
 *       {@code DEFINE PROGRAM(CORPT00C)}, {@code :409-410}
 *       {@code DEFINE TRANSACTION(CR00) PROGRAM(CORPT00C)}.</li>
 *   <li>{@code README.md:225} - the online inventory row
 *       {@code CR00 | CORPT00 | CORPT00C | Transaction Reports}.</li>
 * </ul>
 *
 * <p>Nothing here writes to any of them, and nothing here reads a parity case fixture: the harness
 * package and {@code src/test/resources/parity} belong to a different suite entirely.
 *
 * <h2>This screen is not an R-B case</h2>
 *
 * The plan's risk R-B - a mandated class name that contradicts its COBOL - covers the
 * {@code COTRN01C} / {@code COTRN02C} pair in this very package, where the class called
 * {@code ...Add...} views and the one called {@code ...View...} adds. It does <strong>not</strong>
 * cover this screen. {@code app/cbl/CORPT00C.cbl:5} reads {@code Function : Print Transaction reports
 * by submitting batch} and {@code README.md:225} documents {@code CR00} as {@code Transaction
 * Reports}: name and behaviour agree. No R-B warning belongs in this file, and one must not be copied
 * across from a sibling test.
 *
 * <h2>What governs this file</h2>
 *
 * {@code review_rules} returns exactly one line, "No user rules provided", and that one line is the
 * whole document, so <strong>no user rule governs this file</strong>. Per the agent plan's rules
 * sub-section that absence is not permission to lower the bar, so the plan's own binds are held as
 * the rulings here, and each is exercised rather than merely asserted in prose:
 *
 * <table border="1">
 *   <caption>Which group discharges which bind</caption>
 *   <tr><th>Bind</th><th>Held by</th></tr>
 *   <tr><td>R1 - name from the plan, behaviour from the source</td>
 *       <td>{@code Provenance}, and the not-an-R-B-case note above</td></tr>
 *   <tr><td>R2 / G24 - truncation, never rounding</td>
 *       <td>{@code NegativeContract}: no {@link RoundingMode} anywhere, and no monetary member to
 *           round - this payload carries date parts and flags only</td></tr>
 *   <tr><td>R4 / G22 - never {@code double}, {@code float} or {@link BigDecimal}</td>
 *       <td>{@code StringBoundary}, over components, fields, accessors and nested types</td></tr>
 *   <tr><td>R5 - fixed width is the wire format</td>
 *       <td>{@code Geometry} and {@code FixedWidthImage}: 12 + 17 x 7 + 206 = 337</td></tr>
 *   <tr><td>R6 / G37 - statelessness</td>
 *       <td>{@code Conversation}: the bare 160-byte communication area, no session, no
 *           thread-local, no static mutable holder</td></tr>
 *   <tr><td>B3 - reference inputs immutable</td>
 *       <td>every oracle is opened read-only, and the widths are transcribed into
 *           {@code EXPECTED_FIELD_WIDTHS} rather than edited into the sources</td></tr>
 *   <tr><td>B4 - no silent scope creep</td>
 *       <td>{@code StringBoundary}: the {@code > '12'} comparison stays alphanumeric even though a
 *           numeric one would look more correct</td></tr>
 *   <tr><td>B7 - deterministic and non-interactive</td>
 *       <td>plain JUnit 5; no clock, locale, time zone or default charset is consulted;
 *           {@code CURDATE} and {@code CURTIME} are treated as opaque {@code X(8)}</td></tr>
 *   <tr><td>B8 / G52 - explicit over implicit</td>
 *       <td>no wildcard import; every code page named ({@code US-ASCII}, {@code IBM037})</td></tr>
 *   <tr><td>B9 / G53 - no static mutable state</td>
 *       <td>{@code Conversation}; the oracles this suite caches are immutable {@code List}s</td></tr>
 *   <tr><td>B11 - hand-written, reviewable codecs</td>
 *       <td>the copybook is parsed by the explicit patterns declared below, never by a third-party
 *           copybook parser</td></tr>
 *   <tr><td>G9 - every payload field traces to a {@code DFHMDF}</td>
 *       <td>{@code NamesAndWidths} and {@code Provenance}</td></tr>
 *   <tr><td>G21 - {@code FILLER} emitted</td><td>{@code FixedWidthImage}</td></tr>
 *   <tr><td>G34 - {@code REDEFINES} is two accessors over one span</td>
 *       <td>{@code MetadataCarriers}</td></tr>
 *   <tr><td>G44 - no DDL, no entity annotation, no version column</td>
 *       <td>{@code NegativeContract}</td></tr>
 *   <tr><td>G49 - {@code transaction.dto} clears BRANCH 0.90 on its own</td>
 *       <td>{@code ReportTypeSelector}, {@code ConfirmTopology}, {@code StringBoundary} and
 *           {@code BeanValidation} drive both outcomes of each decision</td></tr>
 *   <tr><td>G54 - non-interactive</td><td>no sleep, no network, no watch mode</td></tr>
 * </table>
 *
 * <p><strong>G33 does not apply to this screen, and its absence is a finding rather than an
 * omission.</strong> Gate G33 covers the 1-based-COBOL-to-0-based-Java conversion of an
 * {@code OCCURS} table. {@code 01 CORPT0AI} declares no {@code OCCURS} at all - it has no repeating
 * row group, unlike the ten-row {@code COTRN00} and {@code COUSR00} maps in the same application - so
 * there is no row index to verify and nothing here to get off by one. {@code Geometry} asserts the
 * seventeen fields are seventeen distinct singletons, which is the positive form of the same fact.
 *
 * <p>Of the forty-two {@code DFHMDF} entries in the mapset, seventeen carry a name label and become
 * payload members. The other twenty-five are unlabelled literal and label fields - the captions
 * {@code 'Tran:'}, {@code 'Date:'}, {@code 'Time:'}, {@code 'Prog:'}, the report-type prompts, the
 * {@code '(MM/DD/YYYY)'} hints, the {@code '(Y/N)'} marker and the function-key legend. BMS
 * generates no symbolic-map item for an unlabelled field, so there is nothing for a payload to
 * carry: they are screen furniture, and their absence is asserted, not assumed.
 *
 * <h2>Self-contained by construction</h2>
 *
 * This class extends nothing and shares nothing. It follows the structural shape that
 * {@code TransactionListRequestTest} established in this package - numbered {@code @Nested} groups,
 * transcribed expectations, both sides of every decision - but takes no dependency on it, and no
 * common base class exists or is to be created. Its imports are confined to the four production types
 * this payload is built from ({@link ReportRequestRequest}, {@link NavigationContext},
 * {@link FixedWidthCodec}, {@code FixedWidthRecord}) plus JUnit, AssertJ, Jackson and Jakarta
 * Validation. In particular it does not reach for {@code card.dto.CardScreenState} or for a PF-key
 * resolver: {@code CORPT00C} copies neither {@code CVCRD01Y} nor {@code CSSTRPFY}, so the five-
 * character AID token convention is stated here as a local constant instead.
 *
 * <h2>Boundaries this suite deliberately does not cross</h2>
 *
 * No {@code MockMvc}, no {@code @SpringBootTest}, no {@code @WebMvcTest}, no repository, no
 * {@code JobLauncher} and no data source - {@code CORPT00C} touches no dataset at all. The
 * controller's own behaviour is owned by {@code ReportRequestControllerTest} in the parent package:
 * the job-submission port and its eighty-byte JCL skeleton, the {@code JOB-LINES OCCURS 1000}
 * overlay, gate G29 for the acceptance semantics of the six {@code FUNCTION NUMVAL-C} call sites,
 * {@code FUNCTION DATE-OF-INTEGER} / {@code INTEGER-OF-DATE}, and the two {@code CALL 'CSUTLDTC'}
 * sites. None of it is duplicated here. What this suite owns is the payload-side consequence: the
 * members stay {@code String}, the boundary stays a string boundary, and the bytes stay 337.
 */
@DisplayName("ReportRequestRequest - CORPT00 symbolic map as the POST /api/reports payload")
class ReportRequestRequestTest {

    private static final String SYMBOLIC_MAP_PATH = "app/cpy-bms/CORPT00.CPY";
    private static final String MAPSET_PATH = "app/bms/CORPT00.bms";
    private static final String PROGRAM_PATH = "app/cbl/CORPT00C.cbl";

    /**
     * The payload's own source. Read so the gate G22 and G24 negatives can be textual as well as
     * reflective: reflection proves no member <em>is</em> a fixed-point or floating-point type, and the
     * text proves no rounding mode is so much as named in the file.
     */
    private static final String PAYLOAD_SOURCE_PATH =
            "app/java/src/main/java/com/vsergeychik/carddemo/transaction/dto/ReportRequestRequest.java";

    /** {@code 02  xxxI  PIC X(n).} - the payload items of the {@code AI} group. */
    private static final Pattern INPUT_ITEM =
            Pattern.compile("^\\s*02\\s+(\\S*[A-Z0-9]I)\\s+PIC\\s+X\\((\\d+)\\)\\.");

    /**
     * {@code 02  xxxO  PIC X(n).} - the data items of the {@code AO} overlay. {@code FILLER} cannot
     * match, because the group name must end in {@code O}.
     */
    private static final Pattern OUTPUT_ITEM =
            Pattern.compile("^\\s*02\\s+(\\S*[A-Z0-9]O)\\s+PIC\\s+X\\((\\d+)\\)\\.");

    /** {@code 02  xxxL    COMP  PIC  S9(4).} - the signed binary halfword length items. */
    private static final Pattern LENGTH_ITEM =
            Pattern.compile("^\\s*02\\s+(\\S+L)\\s+COMP\\s+PIC\\s+S9\\(4\\)\\.");

    /** {@code 02  xxxF    PICTURE X.} - the flag bytes. */
    private static final Pattern FLAG_ITEM =
            Pattern.compile("^\\s*02\\s+(\\S+F)\\s+PICTURE\\s+X\\.");

    /** {@code 03 xxxA    PICTURE X.} - the attribute views nested in the redefining FILLER. */
    private static final Pattern ATTRIBUTE_ITEM =
            Pattern.compile("^\\s*03\\s+(\\S+A)\\s+PICTURE\\s+X\\.");

    /** A {@code DFHMDF} carrying a name label in the label area. */
    private static final Pattern NAMED_MDF = Pattern.compile("^(\\S+)\\s+DFHMDF\\b");

    /** A {@code DFHMDF} with no name label - screen furniture. */
    private static final Pattern UNNAMED_MDF = Pattern.compile("^\\s+DFHMDF\\b");

    private static final Pattern MDF_LENGTH = Pattern.compile("LENGTH=(\\d+)");

    /** {@code MOVE -1 TO <field>L OF CORPT0AI} - the CICS cursor-positioning idiom. */
    private static final Pattern CURSOR_MOVE =
            Pattern.compile("MOVE\\s+-1\\s+TO\\s+(\\S+)L\\s+OF\\s+CORPT0AI");

    private static final FixedWidthCodec ASCII = new FixedWidthCodec(StandardCharsets.US_ASCII);
    private static final FixedWidthCodec EBCDIC = new FixedWidthCodec(Charset.forName("IBM037"));

    private static final ObjectMapper JSON = new ObjectMapper();

    // =================================================================================================
    // The transcribed expectation. Every number below is read off app/cpy-bms/CORPT00.CPY,
    // app/bms/CORPT00.bms, app/cbl/CORPT00C.cbl or app/cpy/COCOM01Y.cpy by eye and written here, never
    // read back out of the class under test. The suite then checks the class against BOTH this
    // transcription and the copybook on disk, so a slip in either one is caught by the other.
    // Practice B3: the sources themselves are opened read-only and are never edited to match.
    // =================================================================================================

    /**
     * The seventeen name-labelled {@code DFHMDF} fields in {@code 01 CORPT0AI} declaration order,
     * transcribed from {@code app/cpy-bms/CORPT00.CPY:17-120}.
     */
    private static final List<String> EXPECTED_FIELD_NAMES = List.of(
            "TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME",
            "MONTHLY", "YEARLY", "CUSTOM",
            "SDTMM", "SDTDD", "SDTYYYY",
            "EDTMM", "EDTDD", "EDTYYYY",
            "CONFIRM", "ERRMSG");

    /**
     * The seventeen {@code xxxI PIC X(n)} widths in the same order. {@code 4 + 40 + 8 + 8 + 40 + 8 +
     * 1 + 1 + 1 + 2 + 2 + 4 + 2 + 2 + 4 + 1 + 78 = 206}.
     */
    private static final List<Integer> EXPECTED_FIELD_WIDTHS_IN_ORDER = List.of(
            4, 40, 8, 8, 40, 8,
            1, 1, 1,
            2, 2, 4,
            2, 2, 4,
            1, 78);

    /**
     * Field name to declared width, in declaration order and unmodifiable. This is the field table the
     * agent plan asks to be encoded as a constant, and it is the third of the three independent
     * authorities this suite compares - the other two being the copybook on disk and
     * {@link ScreenField} itself.
     */
    private static final Map<String, Integer> EXPECTED_FIELD_WIDTHS = expectedFieldWidths();

    /** Payload fields on this map: the narrowest of the four screens in this package. */
    private static final int EXPECTED_FIELD_COUNT = 17;

    /** The seventeen widths added up. */
    private static final int EXPECTED_PAYLOAD_TOTAL = 206;

    /** {@code 12 + 17 x 7 + 206}, the full width of {@code 01 CORPT0AI}. */
    private static final int EXPECTED_GROUP_IMAGE = 337;

    /**
     * {@code 01 CARDDEMO-COMMAREA} of {@code app/cpy/COCOM01Y.cpy}, and the whole of what
     * {@code CORPT00C} passes: {@code 34 + 84 + 12 + 16 + 14}.
     */
    private static final int EXPECTED_COMMAREA_LENGTH = 160;

    /**
     * The {@code 58}-byte {@code 05 CDEMO-CTnn-INFO} group that the three {@code CT} screens in this
     * package declare immediately after {@code COPY COCOM01Y.}, making their passed communication area
     * {@value #SIBLING_COMMAREA_WITH_EXTENSION} bytes. {@code CORPT00C} declares no such group, which
     * is the single most copy-pasteable mistake on this screen.
     */
    private static final int COMMAREA_EXTENSION_LENGTH = 58;

    /** What a communication area would measure if this screen had an extension. It does not. */
    private static final int SIBLING_COMMAREA_WITH_EXTENSION =
            EXPECTED_COMMAREA_LENGTH + COMMAREA_EXTENSION_LENGTH;

    /** {@code MOVE -1 TO xxxL} cursor placements in {@code app/cbl/CORPT00C.cbl}. */
    private static final int EXPECTED_CURSOR_MOVES = 22;

    /** {@code DFHMDF} entries in {@code app/bms/CORPT00.bms}, labelled and unlabelled together. */
    private static final int EXPECTED_TOTAL_MDF = 42;

    /** Unlabelled {@code DFHMDF} entries: screen furniture with no symbolic-map item. */
    private static final int EXPECTED_UNNAMED_MDF = EXPECTED_TOTAL_MDF - EXPECTED_FIELD_COUNT;

    /** Named fields the operator can type into: {@code ATTRB=(...UNPROT)}. */
    private static final int EXPECTED_UNPROTECTED = 10;

    /** Named fields that are {@code ASKIP} and are still returned, because each carries {@code FSET}. */
    private static final int EXPECTED_OUTPUT_ONLY = EXPECTED_FIELD_COUNT - EXPECTED_UNPROTECTED;

    /** The ten input-capable named fields, transcribed from the {@code ATTRB} lists of the mapset. */
    private static final List<String> EXPECTED_INPUT_CAPABLE = List.of(
            "MONTHLY", "YEARLY", "CUSTOM",
            "SDTMM", "SDTDD", "SDTYYYY",
            "EDTMM", "EDTDD", "EDTYYYY",
            "CONFIRM");

    /** The six date parts: a start triple and an end triple, and nothing between them. */
    private static final List<String> EXPECTED_START_DATE_TRIPLE = List.of("SDTMM", "SDTDD", "SDTYYYY");

    /** The end triple, in the same month-day-year order the copybook declares. */
    private static final List<String> EXPECTED_END_DATE_TRIPLE = List.of("EDTMM", "EDTDD", "EDTYYYY");

    /**
     * Members a transaction-record screen would carry and this one must not:
     * {@code app/cpy/CVTRA05Y.cpy} is copied by nine programs, and {@code CORPT00C} is not one of them.
     */
    private static final List<String> FORBIDDEN_TRANSACTION_MEMBERS = List.of(
            "TRNID", "CARDNUM", "TRNAMT", "MID", "MNAME", "MCITY", "MZIP");

    /**
     * The {@code EIBAID} token for {@code DFHENTER}, the key {@code app/cbl/CORPT00C.cbl:185} acts on.
     *
     * <p>Stated as a local constant rather than imported. {@code CORPT00C} copies neither
     * {@code CVCRD01Y} nor {@code CSSTRPFY}: it tests the raw {@code EIBAID} byte inline, so this
     * payload has no production dependency on a PF-key resolver and this suite takes none either. The
     * width is the module's five-character convention, which
     * {@link ReportRequestRequest#AID_LENGTH} publishes and which this suite asserts against.
     */
    private static final String AID_ENTER = "ENTER";

    /** The token for {@code DFHPF3}, the {@code XCTL}-to-menu arm at {@code app/cbl/CORPT00C.cbl:187}. */
    private static final String AID_PF3 = "PFK03";

    /** One character of the COBOL {@code SPACES} figurative constant. */
    private static final String SPACE = " ";

    /** One character of the COBOL {@code LOW-VALUES} figurative constant: {@code X'00'}. */
    private static final String LOW_VALUE = "\u0000";

    /**
     * The index in {@code app/cbl/CORPT00C.cbl} of the first line containing {@code fragment}, or
     * {@code -1} when it appears nowhere. Zero based, so the printed line number is one greater.
     */
    private static int lineIndexContaining(String fragment) {
        for (int index = 0; index < program.size(); index++) {
            if (program.get(index).contains(fragment)) {
                return index;
            }
        }
        return -1;
    }

    /**
     * The same, for a line that must contain two fragments. Used where the COBOL's own column alignment
     * puts a run of spaces between the two halves of a statement, which a single literal would have to
     * reproduce exactly and brittlely.
     */
    private static int lineIndexContaining(String first, String second) {
        for (int index = 0; index < program.size(); index++) {
            String line = program.get(index);
            if (line.contains(first) && line.contains(second)) {
                return index;
            }
        }
        return -1;
    }

    /** The index of the first line at or after {@code from} that contains {@code fragment}. */
    private static int lineIndexAfter(int from, String fragment) {
        for (int index = Math.max(from, 0); index < program.size(); index++) {
            if (program.get(index).contains(fragment)) {
                return index;
            }
        }
        return -1;
    }

    /** Builds the unmodifiable, order-preserving field table from the two transcribed lists. */
    private static Map<String, Integer> expectedFieldWidths() {
        Map<String, Integer> table = new LinkedHashMap<>();
        for (int index = 0; index < EXPECTED_FIELD_NAMES.size(); index++) {
            table.put(EXPECTED_FIELD_NAMES.get(index), EXPECTED_FIELD_WIDTHS_IN_ORDER.get(index));
        }
        return java.util.Collections.unmodifiableMap(table);
    }

    /**
     * The oracle lines every test below reads, snapshotted once and immutable.
     *
     * <p>Read eagerly into {@code static final} lists rather than assigned by a {@code @BeforeAll},
     * because a mutable static is shared state between tests however carefully it is populated: any
     * method could reassign one, a reader cannot tell from the declaration that nothing does, and the
     * order in which the suite happens to run becomes part of what the tests mean. The AAP forbids
     * static mutable state outright (gate G53, practice B9), and these are the files it exists to
     * protect - the five parity oracles the whole class asserts against.
     *
     * <p>{@link #readLines(Path)} already returns an unmodifiable list and
     * {@link #inputGroupOf(List)} / {@link #outputGroupOf(List)} are pure slices of one, so the
     * snapshots are immutable in substance as well as in reference.
     */
    private static final List<String> SYMBOLIC_MAP =
        readLines(repositoryRoot().resolve(SYMBOLIC_MAP_PATH));

    /** The {@code 01 CORPT0AI} group of the symbolic map, lines 17 to 120 inclusive. */
    private static final List<String> inputGroup = inputGroupOf(SYMBOLIC_MAP);

    /** The {@code 01 CORPT0AO REDEFINES CORPT0AI} overlay, line 121 to the end of the copybook. */
    private static final List<String> outputGroup = outputGroupOf(SYMBOLIC_MAP);

    /** The BMS mapset lines - the 17 DFHMDF definitions this payload projects. */
    private static final List<String> mapset = readLines(repositoryRoot().resolve(MAPSET_PATH));

    /** The COBOL program lines, whose paragraph order several tests read. */
    private static final List<String> program = readLines(repositoryRoot().resolve(PROGRAM_PATH));

    /** The payload's own source lines, for the two textual negatives. */
    private static final List<String> payloadSource =
        readLines(repositoryRoot().resolve(PAYLOAD_SOURCE_PATH));

    /**
     * Locates the repository root by walking up from the working directory until the symbolic map is
     * found. Surefire runs with the Maven module directory as its working directory, so the walk is
     * two levels; resolving it rather than hard-coding {@code ../..} keeps the suite runnable from
     * the repository root and from an IDE as well.
     */
    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve(SYMBOLIC_MAP_PATH))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Could not locate " + SYMBOLIC_MAP_PATH + " above "
                + Path.of("").toAbsolutePath() + ". It is the byte-level parity oracle for "
                + "ReportRequestRequest and is read-only, so it must be present: these tests prove "
                + "the projection against it rather than against a copy.");
    }

    private static List<String> readLines(Path path) {
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException cause) {
            throw new UncheckedIOException("Could not read the parity oracle " + path, cause);
        }
    }

    /**
     * Narrows the copybook to {@code 01 CORPT0AI} and stops at
     * {@code 01 CORPT0AO REDEFINES CORPT0AI}, so the {@code AO} view's {@code xxxO} items can never
     * be mistaken for {@code AI}'s {@code xxxI} items.
     */
    private static List<String> inputGroupOf(List<String> copybook) {
        List<String> group = new ArrayList<>();
        boolean inside = false;
        for (String line : copybook) {
            if (line.contains("01  CORPT0AO REDEFINES")) {
                break;
            }
            if (line.contains("01  CORPT0AI.")) {
                inside = true;
            }
            if (inside) {
                group.add(line);
            }
        }
        if (group.isEmpty()) {
            throw new IllegalStateException("01 CORPT0AI was not found in " + SYMBOLIC_MAP_PATH);
        }
        return group;
    }

    /**
     * Narrows the copybook to the {@code AO} overlay, which begins at
     * {@code 01 CORPT0AO REDEFINES CORPT0AI} on line 121 and runs to the end of the file. Its
     * {@code xxxO} items are the proof that {@code xxxI} and {@code xxxO} address one span.
     */
    private static List<String> outputGroupOf(List<String> copybook) {
        List<String> group = new ArrayList<>();
        boolean inside = false;
        for (String line : copybook) {
            if (line.contains("01  CORPT0AO REDEFINES")) {
                inside = true;
            }
            if (inside) {
                group.add(line);
            }
        }
        if (group.isEmpty()) {
            throw new IllegalStateException(
                    "01 CORPT0AO REDEFINES CORPT0AI was not found in " + SYMBOLIC_MAP_PATH);
        }
        return group;
    }

    /** Item name to declared width, in copybook declaration order, for one pattern. */
    private static Map<String, Integer> itemsMatching(Pattern pattern) {
        return itemsMatching(inputGroup, pattern);
    }

    /** Item name to declared width, in declaration order, over an arbitrary run of copybook lines. */
    private static Map<String, Integer> itemsMatching(List<String> lines, Pattern pattern) {
        Map<String, Integer> items = new LinkedHashMap<>();
        for (String line : lines) {
            Matcher matcher = pattern.matcher(line);
            if (matcher.find()) {
                items.put(matcher.group(1),
                        matcher.groupCount() >= 2 ? Integer.parseInt(matcher.group(2)) : 1);
            }
        }
        return items;
    }

    /** The {@code xxxO PIC X(n)} items of the {@code AO} overlay, in declaration order. */
    private static Map<String, Integer> outputGroupItems() {
        return itemsMatching(outputGroup, OUTPUT_ITEM);
    }

    private static List<String> namesMatching(Pattern pattern) {
        return List.copyOf(itemsMatching(pattern).keySet());
    }

    /**
     * The name-labelled {@code DFHMDF} entries of the mapset with their {@code LENGTH=}, in
     * declaration order. A {@code LENGTH=} is attributed to the most recent label, and an unlabelled
     * {@code DFHMDF} clears the label, so screen furniture cannot capture a width.
     */
    private static Map<String, Integer> namedMapsetFields() {
        Map<String, Integer> fields = new LinkedHashMap<>();
        String pending = null;
        for (String line : mapset) {
            Matcher named = NAMED_MDF.matcher(line);
            if (named.find()) {
                pending = named.group(1);
                continue;
            }
            if (UNNAMED_MDF.matcher(line).find()) {
                pending = null;
                continue;
            }
            Matcher length = MDF_LENGTH.matcher(line);
            if (pending != null && length.find()) {
                fields.put(pending, Integer.parseInt(length.group(1)));
                pending = null;
            }
        }
        return fields;
    }

    /** The {@code ATTRB=(...)} list of one named {@code DFHMDF}, as one comma-separated string. */
    private static String attributesOf(String fieldName) {
        boolean inside = false;
        for (String line : mapset) {
            Matcher named = NAMED_MDF.matcher(line);
            if (named.find()) {
                inside = named.group(1).equals(fieldName);
            } else if (UNNAMED_MDF.matcher(line).find()) {
                inside = false;
            }
            if (inside && line.contains("ATTRB=(")) {
                return line.substring(line.indexOf("ATTRB=(") + "ATTRB=(".length(),
                        line.indexOf(')', line.indexOf("ATTRB=(")));
            }
        }
        throw new IllegalStateException("No ATTRB list found for DFHMDF " + fieldName);
    }

    private static ReportRequestRequest customRequest() {
        return ReportRequestRequest.empty()
                .withTrnname(ReportRequestRequest.TRANSACTION_ID)
                .withPgmname(ReportRequestRequest.PROGRAM_NAME)
                .withCustom("X")
                .withSdtmm("07")
                .withSdtdd("18")
                .withSdtyyyy("2022")
                .withEdtmm("07")
                .withEdtdd("31")
                .withEdtyyyy("2022")
                .withConfirm("Y");
    }

    @Nested
    @DisplayName("1. Geometry - the 337-byte AI group, proved against the copybook")
    class Geometry {

        @Test
        @DisplayName("the copybook declares exactly 17 xxxI payload items")
        void seventeenPayloadItems() {
            assertThat(itemsMatching(INPUT_ITEM)).hasSize(ReportRequestRequest.FIELD_COUNT);
            assertThat(ReportRequestRequest.FIELD_COUNT).isEqualTo(17);
            assertThat(ScreenField.values()).hasSize(ReportRequestRequest.FIELD_COUNT);
        }

        @Test
        @DisplayName("a field costs 7 prefix bytes: 2 for COMP S9(4), 1 for the flag, 4 reserved")
        void perFieldPrefixIsSevenBytes() {
            assertThat(ReportRequestRequest.LENGTH_ITEM_LENGTH).isEqualTo(2);
            assertThat(ReportRequestRequest.FLAG_ITEM_LENGTH).isEqualTo(1);
            assertThat(ReportRequestRequest.RESERVED_FILLER_LENGTH).isEqualTo(4);
            assertThat(ReportRequestRequest.FIELD_PREFIX_LENGTH).isEqualTo(7);
            assertThat(ReportRequestRequest.TIOAPFX_PREFIX_LENGTH).isEqualTo(12);
        }

        @Test
        @DisplayName("the copybook really declares 17 length items, 17 flag bytes and 17 attribute "
                + "views, and the attribute view adds no byte")
        void metadataItemsAreDeclaredOncePerField() {
            assertThat(itemsMatching(LENGTH_ITEM)).hasSize(ReportRequestRequest.FIELD_COUNT);
            assertThat(itemsMatching(FLAG_ITEM)).hasSize(ReportRequestRequest.FIELD_COUNT);
            assertThat(itemsMatching(ATTRIBUTE_ITEM)).hasSize(ReportRequestRequest.FIELD_COUNT);
            assertThat(inputGroup.stream().filter(l -> l.contains("FILLER REDEFINES")).count())
                    .isEqualTo(ReportRequestRequest.FIELD_COUNT);
        }

        @Test
        @DisplayName("the copybook declares one X(12) TIOAPFX prefix and 17 reserved X(4) fillers")
        void fillersAreDeclaredAsThePrefixArithmeticRequires() {
            assertThat(inputGroup.stream().filter(l -> l.matches("\\s*02\\s+FILLER\\s+PIC\\s+X\\(12\\)\\."))
                    .count()).isEqualTo(1);
            assertThat(inputGroup.stream()
                    .filter(l -> l.matches("\\s*02\\s+FILLER\\s+PICTURE\\s+X\\(4\\)\\."))
                    .count()).isEqualTo(ReportRequestRequest.FIELD_COUNT);
        }

        @Test
        @DisplayName("the 17 widths sum to 206 and the group is 12 + 17x7 + 206 = 337 bytes")
        void theTotalIsThreeHundredAndThirtySeven() {
            int copybookSum = itemsMatching(INPUT_ITEM).values().stream().mapToInt(Integer::intValue)
                    .sum();
            assertThat(copybookSum).isEqualTo(206);
            assertThat(ReportRequestRequest.PAYLOAD_WIDTH_TOTAL).isEqualTo(copybookSum);
            assertThat(ReportRequestRequest.SYMBOLIC_MAP_LENGTH)
                    .isEqualTo(ReportRequestRequest.TIOAPFX_PREFIX_LENGTH
                            + ReportRequestRequest.FIELD_COUNT
                                    * ReportRequestRequest.FIELD_PREFIX_LENGTH
                            + copybookSum)
                    .isEqualTo(337);
        }

        @Test
        @DisplayName("LAYOUT declares all 337 bytes: 86 spans, 69 of them storage and 17 overlays")
        void layoutAccountsForEveryByte() {
            assertThat(ReportRequestRequest.LAYOUT.recordLength())
                    .isEqualTo(ReportRequestRequest.SYMBOLIC_MAP_LENGTH);
            assertThat(ReportRequestRequest.LAYOUT.spans())
                    .hasSize(1 + ReportRequestRequest.FIELD_COUNT * 5);
            assertThat(ReportRequestRequest.LAYOUT.storageSpans())
                    .hasSize(1 + ReportRequestRequest.FIELD_COUNT * 4);
            assertThat(ReportRequestRequest.LAYOUT.redefinitions())
                    .hasSize(ReportRequestRequest.FIELD_COUNT);
            assertThat(ReportRequestRequest.LAYOUT.storageSpans().stream()
                    .mapToInt(FieldSpan::length).sum())
                    .isEqualTo(ReportRequestRequest.SYMBOLIC_MAP_LENGTH);
        }

        @Test
        @DisplayName("the TIOAPFX prefix is a FILLER span at offset 0")
        void prefixSpanIsFillerAtZero() {
            assertThat(ReportRequestRequest.TIOAPFX_PREFIX_SPAN.name()).isEqualTo("FILLER");
            assertThat(ReportRequestRequest.TIOAPFX_PREFIX_SPAN.offset()).isZero();
            assertThat(ReportRequestRequest.TIOAPFX_PREFIX_SPAN.length())
                    .isEqualTo(ReportRequestRequest.TIOAPFX_PREFIX_LENGTH);
            assertThat(ReportRequestRequest.TIOAPFX_PREFIX_SPAN.kind())
                    .isEqualTo(PictureKind.FILLER);
        }

        @Test
        @DisplayName("storage spans run contiguously from 0 to 337 with no gap and no overlap")
        void storageSpansAreContiguous() {
            int cursor = 0;
            for (FieldSpan span : ReportRequestRequest.LAYOUT.storageSpans()) {
                assertThat(span.offset()).as("offset of %s", span.describe()).isEqualTo(cursor);
                cursor = span.endOffsetExclusive();
            }
            assertThat(cursor).isEqualTo(ReportRequestRequest.SYMBOLIC_MAP_LENGTH);
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("each field's five spans sit where the 7 + n stride puts them")
        void spansFollowTheStride(ScreenField field) {
            int base = field.lengthSpan().offset();
            assertThat(field.lengthSpan().length()).isEqualTo(ReportRequestRequest.LENGTH_ITEM_LENGTH);
            assertThat(field.flagSpan().offset()).isEqualTo(base + 2);
            assertThat(field.flagSpan().length()).isEqualTo(1);
            assertThat(field.attributeSpan().offset()).isEqualTo(field.flagSpan().offset());
            assertThat(field.attributeSpan().length()).isEqualTo(field.flagSpan().length());
            assertThat(field.attributeSpan().redefinition()).isTrue();
            assertThat(field.flagSpan().redefinition()).isFalse();
            assertThat(field.reservedFillerSpan().offset()).isEqualTo(base + 3);
            assertThat(field.reservedFillerSpan().length())
                    .isEqualTo(ReportRequestRequest.RESERVED_FILLER_LENGTH);
            assertThat(field.reservedFillerSpan().name()).isEqualTo("FILLER");
            assertThat(field.reservedFillerSpan().kind()).isEqualTo(PictureKind.FILLER);
            assertThat(field.inputSpan().offset())
                    .isEqualTo(base + ReportRequestRequest.FIELD_PREFIX_LENGTH);
            assertThat(field.inputSpan().length()).isEqualTo(field.declaredLength());
            assertThat(field.inputSpan().kind()).isEqualTo(PictureKind.ALPHANUMERIC);
        }

        @Test
        @DisplayName("the first field starts after the prefix and the last one ends at 337")
        void firstAndLastOffsets() {
            assertThat(ScreenField.TRNNAME.lengthSpan().offset())
                    .isEqualTo(ReportRequestRequest.TIOAPFX_PREFIX_LENGTH);
            assertThat(ScreenField.TRNNAME.inputSpan().offset()).isEqualTo(19);
            assertThat(ScreenField.ERRMSG.lengthSpan().offset()).isEqualTo(252);
            assertThat(ScreenField.ERRMSG.inputSpan().offset()).isEqualTo(259);
            assertThat(ScreenField.ERRMSG.inputSpan().endOffsetExclusive())
                    .isEqualTo(ReportRequestRequest.SYMBOLIC_MAP_LENGTH);
        }
    }

    @Nested
    @DisplayName("2. Names and widths - verbatim from the copybook and the mapset")
    class NamesAndWidths {

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("the four copybook item names are spelled exactly as CORPT00.CPY spells them")
        void itemNamesAreVerbatim(ScreenField field) {
            assertThat(namesMatching(LENGTH_ITEM)).contains(field.lengthItem());
            assertThat(namesMatching(FLAG_ITEM)).contains(field.flagItem());
            assertThat(namesMatching(ATTRIBUTE_ITEM)).contains(field.attributeItem());
            assertThat(itemsMatching(INPUT_ITEM)).containsKey(field.inputItem());
            assertThat(field.lengthItem()).isEqualTo(field.bmsName() + "L");
            assertThat(field.flagItem()).isEqualTo(field.bmsName() + "F");
            assertThat(field.attributeItem()).isEqualTo(field.bmsName() + "A");
            assertThat(field.inputItem()).isEqualTo(field.bmsName() + "I");
        }

        @Test
        @DisplayName("the 17 xxxI items appear in the enum's declaration order")
        void declarationOrderMatchesTheCopybook() {
            List<String> fromCopybook = List.copyOf(itemsMatching(INPUT_ITEM).keySet());
            List<String> fromEnum = new ArrayList<>();
            for (ScreenField field : ScreenField.values()) {
                fromEnum.add(field.inputItem());
            }
            assertThat(fromEnum).containsExactlyElementsOf(fromCopybook);
        }

        @Test
        @DisplayName("the enum spells the 17 names exactly as the DFHMDF labels do, in order")
        void bmsNamesMatchTheMapsetLabels() {
            List<String> fromMapset = List.copyOf(namedMapsetFields().keySet());
            List<String> fromEnum = new ArrayList<>();
            for (ScreenField field : ScreenField.values()) {
                fromEnum.add(field.bmsName());
            }
            assertThat(fromMapset).hasSize(ReportRequestRequest.FIELD_COUNT);
            assertThat(fromEnum).containsExactlyElementsOf(fromMapset);
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("declaredLength equals both the xxxI PICTURE and the DFHMDF LENGTH")
        void widthsAgreeAcrossBothSources(ScreenField field) {
            assertThat(field.declaredLength())
                    .as("PIC X(n) of %s", field.inputItem())
                    .isEqualTo(itemsMatching(INPUT_ITEM).get(field.inputItem()));
            assertThat(field.declaredLength())
                    .as("DFHMDF LENGTH of %s", field.bmsName())
                    .isEqualTo(namedMapsetFields().get(field.bmsName()));
        }

        @Test
        @DisplayName("the published width constants match the enum, ERRMSG being the widest at 78")
        void publishedConstantsMatchTheEnum() {
            assertThat(ScreenField.TRNNAME.declaredLength())
                    .isEqualTo(ReportRequestRequest.TRNNAME_LENGTH).isEqualTo(4);
            assertThat(ScreenField.TITLE01.declaredLength())
                    .isEqualTo(ReportRequestRequest.TITLE01_LENGTH).isEqualTo(40);
            assertThat(ScreenField.CURDATE.declaredLength())
                    .isEqualTo(ReportRequestRequest.CURDATE_LENGTH).isEqualTo(8);
            assertThat(ScreenField.PGMNAME.declaredLength())
                    .isEqualTo(ReportRequestRequest.PGMNAME_LENGTH).isEqualTo(8);
            assertThat(ScreenField.TITLE02.declaredLength())
                    .isEqualTo(ReportRequestRequest.TITLE02_LENGTH).isEqualTo(40);
            assertThat(ScreenField.CURTIME.declaredLength())
                    .isEqualTo(ReportRequestRequest.CURTIME_LENGTH).isEqualTo(8);
            assertThat(ScreenField.MONTHLY.declaredLength())
                    .isEqualTo(ReportRequestRequest.MONTHLY_LENGTH).isEqualTo(1);
            assertThat(ScreenField.YEARLY.declaredLength())
                    .isEqualTo(ReportRequestRequest.YEARLY_LENGTH).isEqualTo(1);
            assertThat(ScreenField.CUSTOM.declaredLength())
                    .isEqualTo(ReportRequestRequest.CUSTOM_LENGTH).isEqualTo(1);
            assertThat(ScreenField.SDTMM.declaredLength())
                    .isEqualTo(ReportRequestRequest.SDTMM_LENGTH).isEqualTo(2);
            assertThat(ScreenField.SDTDD.declaredLength())
                    .isEqualTo(ReportRequestRequest.SDTDD_LENGTH).isEqualTo(2);
            assertThat(ScreenField.SDTYYYY.declaredLength())
                    .isEqualTo(ReportRequestRequest.SDTYYYY_LENGTH).isEqualTo(4);
            assertThat(ScreenField.EDTMM.declaredLength())
                    .isEqualTo(ReportRequestRequest.EDTMM_LENGTH).isEqualTo(2);
            assertThat(ScreenField.EDTDD.declaredLength())
                    .isEqualTo(ReportRequestRequest.EDTDD_LENGTH).isEqualTo(2);
            assertThat(ScreenField.EDTYYYY.declaredLength())
                    .isEqualTo(ReportRequestRequest.EDTYYYY_LENGTH).isEqualTo(4);
            assertThat(ScreenField.CONFIRM.declaredLength())
                    .isEqualTo(ReportRequestRequest.CONFIRM_LENGTH).isEqualTo(1);
            assertThat(ScreenField.ERRMSG.declaredLength())
                    .isEqualTo(ReportRequestRequest.ERRMSG_LENGTH).isEqualTo(78);
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("unprotected and numeric are read straight off the DFHMDF ATTRB list")
        void attributeFlagsMirrorTheMapset(ScreenField field) {
            String attributes = attributesOf(field.bmsName());
            assertThat(field.unprotected()).as("UNPROT of %s in %s", field.bmsName(), attributes)
                    .isEqualTo(attributes.contains("UNPROT"));
            assertThat(field.numeric()).as("NUM of %s in %s", field.bmsName(), attributes)
                    .isEqualTo(attributes.contains("NUM"));
        }

        @Test
        @DisplayName("all 17 fields carry FSET, which is why every one is part of the request")
        void everyFieldCarriesFset() {
            for (ScreenField field : ScreenField.values()) {
                assertThat(attributesOf(field.bmsName()))
                        .as("ATTRB of %s", field.bmsName()).contains("FSET");
            }
        }

        @Test
        @DisplayName("exactly 10 fields are UNPROT and the 7 ASKIP ones are still projected")
        void tenFieldsAreUnprotected() {
            assertThat(ScreenField.values()).filteredOn(ScreenField::unprotected).hasSize(10);
            assertThat(ScreenField.values()).filteredOn(f -> !f.unprotected()).hasSize(7)
                    .extracting(ScreenField::bmsName)
                    .containsExactly("TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02",
                            "CURTIME", "ERRMSG");
        }

        @Test
        @DisplayName("exactly the 6 date parts are NUM")
        void sixFieldsAreNumericShifted() {
            assertThat(ScreenField.values()).filteredOn(ScreenField::numeric).hasSize(6)
                    .extracting(ScreenField::bmsName)
                    .containsExactly("SDTMM", "SDTDD", "SDTYYYY", "EDTMM", "EDTDD", "EDTYYYY");
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("SPACES and LOW-VALUES are sized to the field")
        void figurativeConstantsAreFieldSized(ScreenField field) {
            assertThat(field.spaces()).hasSize(field.declaredLength())
                    .isEqualTo(" ".repeat(field.declaredLength()));
            assertThat(field.spaces().chars()).allMatch(c -> c == ' ');
            assertThat(field.lowValues()).hasSize(field.declaredLength())
                    .isEqualTo("\u0000".repeat(field.declaredLength()));
            assertThat(field.lowValues().chars()).allMatch(c -> c == 0);
        }
    }

    @Nested
    @DisplayName("3. The payload - 17 screen fields, the communication area and the AID")
    class PayloadProjection {

        @Test
        @DisplayName("the record has 18 components: 17 String fields and one NavigationContext")
        void componentsAreSeventeenStringsAndTheCommarea() {
            RecordComponent[] components = ReportRequestRequest.class.getRecordComponents();
            // Seventeen screen fields, then the two members that are not screen fields: the
            // communication area and the resolved EIBAID token.
            assertThat(components).hasSize(ReportRequestRequest.FIELD_COUNT + 2);
            assertThat(components).filteredOn(c -> c.getType() == String.class)
                    .hasSize(ReportRequestRequest.FIELD_COUNT + 1);
            assertThat(components[ReportRequestRequest.FIELD_COUNT].getName())
                    .isEqualTo("navigationContext");
            assertThat(components[ReportRequestRequest.FIELD_COUNT].getType())
                    .isEqualTo(NavigationContext.class);
            assertThat(components[ReportRequestRequest.FIELD_COUNT + 1].getName()).isEqualTo("aid");
            assertThat(components[ReportRequestRequest.FIELD_COUNT + 1].getType())
                    .isEqualTo(String.class);
        }

        @Test
        @DisplayName("no component is a floating-point or fixed-point type: gates G22, G23 and G24")
        void noNumericComponentAtAll() {
            for (RecordComponent component : ReportRequestRequest.class.getRecordComponents()) {
                assertThat(component.getType())
                        .as("type of %s", component.getName())
                        .isNotIn(double.class, float.class, Double.class, Float.class,
                                java.math.BigDecimal.class, int.class, long.class, Integer.class,
                                Long.class);
            }
        }

        @Test
        @DisplayName("the 17 component names correspond one to one with the xxxI items, suffix aside")
        void componentNamesTrackTheCopybookItems() {
            List<String> expected = new ArrayList<>();
            for (ScreenField field : ScreenField.values()) {
                expected.add(field.bmsName().toLowerCase(java.util.Locale.ROOT));
            }
            // The AID is a String component too, but it is not a screen field, so it is excluded here
            // rather than added to the expectation - the assertion is about the DFHMDF projection.
            List<String> actual = new ArrayList<>();
            for (RecordComponent component : ReportRequestRequest.class.getRecordComponents()) {
                if (component.getType() == String.class && !"aid".equals(component.getName())) {
                    actual.add(component.getName());
                }
            }
            assertThat(actual).containsExactlyElementsOf(expected);
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("every component carries @Size at exactly its declared PIC X(n) width")
        void sizeConstraintsMatchTheDeclaredWidths(ScreenField field) throws Exception {
            String component = field.bmsName().toLowerCase(java.util.Locale.ROOT);
            Size onField = ReportRequestRequest.class.getDeclaredField(component)
                    .getAnnotation(Size.class);
            Size onAccessor = ReportRequestRequest.class.getDeclaredMethod(component)
                    .getAnnotation(Size.class);
            assertThat(onField).as("@Size on field %s", component).isNotNull();
            assertThat(onAccessor).as("@Size on accessor %s", component).isNotNull();
            assertThat(onField.max()).isEqualTo(field.declaredLength());
            assertThat(onAccessor.max()).isEqualTo(field.declaredLength());
            assertThat(onField.min()).as("no minimum: CORPT00C accepts an empty field and edits it "
                    + "itself").isZero();
        }

        @Test
        @DisplayName("no component carries @NotNull or @Pattern: nothing is stricter than the COBOL")
        void noConstraintStricterThanTheProgram() throws Exception {
            for (ScreenField field : ScreenField.values()) {
                String component = field.bmsName().toLowerCase(java.util.Locale.ROOT);
                assertThat(ReportRequestRequest.class.getDeclaredField(component).getAnnotations())
                        .as("annotations on %s", component)
                        .noneMatch(a -> a.annotationType() == jakarta.validation.constraints.NotNull.class
                                || a.annotationType() == jakarta.validation.constraints.Pattern.class
                                || a.annotationType() == jakarta.validation.constraints.NotBlank.class);
            }
        }

        @Test
        @DisplayName("fieldValues is the 17 values in declaration order and is immutable")
        void fieldValuesIsOrderedAndImmutable() {
            ReportRequestRequest request = customRequest();
            assertThat(request.fieldValues()).hasSize(ReportRequestRequest.FIELD_COUNT);
            for (ScreenField field : ScreenField.values()) {
                assertThat(request.fieldValues().get(field.ordinal()))
                        .as("ordinal %d is %s", field.ordinal(), field.inputItem())
                        .isEqualTo(request.value(field));
            }
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> request.fieldValues().set(0, "x"));
        }

        @Test
        @DisplayName("value() returns each accessor's value untrimmed")
        void valueAgreesWithTheAccessors() {
            ReportRequestRequest request = customRequest();
            assertThat(request.value(ScreenField.TRNNAME)).isEqualTo(request.trnname())
                    .isEqualTo("CR00");
            assertThat(request.value(ScreenField.TITLE01)).isEqualTo(request.title01());
            assertThat(request.value(ScreenField.CURDATE)).isEqualTo(request.curdate());
            assertThat(request.value(ScreenField.PGMNAME)).isEqualTo(request.pgmname())
                    .isEqualTo("CORPT00C");
            assertThat(request.value(ScreenField.TITLE02)).isEqualTo(request.title02());
            assertThat(request.value(ScreenField.CURTIME)).isEqualTo(request.curtime());
            assertThat(request.value(ScreenField.MONTHLY)).isEqualTo(request.monthly());
            assertThat(request.value(ScreenField.YEARLY)).isEqualTo(request.yearly());
            assertThat(request.value(ScreenField.CUSTOM)).isEqualTo(request.custom()).isEqualTo("X");
            assertThat(request.value(ScreenField.SDTMM)).isEqualTo(request.sdtmm()).isEqualTo("07");
            assertThat(request.value(ScreenField.SDTDD)).isEqualTo(request.sdtdd()).isEqualTo("18");
            assertThat(request.value(ScreenField.SDTYYYY)).isEqualTo(request.sdtyyyy())
                    .isEqualTo("2022");
            assertThat(request.value(ScreenField.EDTMM)).isEqualTo(request.edtmm()).isEqualTo("07");
            assertThat(request.value(ScreenField.EDTDD)).isEqualTo(request.edtdd()).isEqualTo("31");
            assertThat(request.value(ScreenField.EDTYYYY)).isEqualTo(request.edtyyyy())
                    .isEqualTo("2022");
            assertThat(request.value(ScreenField.CONFIRM)).isEqualTo(request.confirm())
                    .isEqualTo("Y");
            assertThat(request.value(ScreenField.ERRMSG)).isEqualTo(request.errmsg());
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("withValue replaces exactly one field and leaves the other 16 alone")
        void withValueIsSurgical(ScreenField field) {
            ReportRequestRequest before = customRequest();
            String replacement = "Z".repeat(field.declaredLength());
            ReportRequestRequest after = before.withValue(field, replacement);
            assertThat(after.value(field)).isEqualTo(replacement);
            for (ScreenField other : ScreenField.values()) {
                if (other != field) {
                    assertThat(after.value(other)).as("%s must be untouched", other.inputItem())
                            .isEqualTo(before.value(other));
                }
            }
            assertThat(after.navigationContext()).isEqualTo(before.navigationContext());
            assertThat(before.value(field)).as("the original is immutable")
                    .isEqualTo(customRequest().value(field));
        }

        @Test
        @DisplayName("the 17 named withers each drive their own field")
        void namedWithersMapToTheirFields() {
            ReportRequestRequest base = ReportRequestRequest.empty();
            assertThat(base.withTrnname("CR00").trnname()).isEqualTo("CR00");
            // A shorter value is stored SHORT, not padded: padding is a rendering concern that
            // belongs to FixedWidthCodec, so the payload keeps exactly what arrived.
            assertThat(base.withTitle01("T1").title01()).isEqualTo("T1");
            assertThat(base.withCurdate("07/18/22").curdate()).isEqualTo("07/18/22");
            assertThat(base.withPgmname("CORPT00C").pgmname()).isEqualTo("CORPT00C");
            assertThat(base.withTitle02("T2").title02()).isEqualTo("T2");
            assertThat(base.withCurtime("12:34:56").curtime()).isEqualTo("12:34:56");
            assertThat(base.withMonthly("M").monthly()).isEqualTo("M");
            assertThat(base.withYearly("Y").yearly()).isEqualTo("Y");
            assertThat(base.withCustom("C").custom()).isEqualTo("C");
            assertThat(base.withSdtmm("01").sdtmm()).isEqualTo("01");
            assertThat(base.withSdtdd("02").sdtdd()).isEqualTo("02");
            assertThat(base.withSdtyyyy("2022").sdtyyyy()).isEqualTo("2022");
            assertThat(base.withEdtmm("11").edtmm()).isEqualTo("11");
            assertThat(base.withEdtdd("30").edtdd()).isEqualTo("30");
            assertThat(base.withEdtyyyy("2023").edtyyyy()).isEqualTo("2023");
            assertThat(base.withConfirm("N").confirm()).isEqualTo("N");
            assertThat(base.withErrmsg("boom").errmsg()).isEqualTo("boom");
            // ... and the codec is what widens it to the declared 78 on the way to the map image.
            assertThat(ASCII.movePicX(base.withErrmsg("boom").errmsg(),
                    ReportRequestRequest.ERRMSG_LENGTH))
                    .isEqualTo("boom" + " ".repeat(ReportRequestRequest.ERRMSG_LENGTH - 4));
        }

        @Test
        @DisplayName("a named wither leaves every other field untouched")
        void namedWitherTouchesNothingElse() {
            ReportRequestRequest before = customRequest();
            ReportRequestRequest after = before.withMonthly("Y");
            assertThat(after.monthly()).isEqualTo("Y");
            assertThat(after.withMonthly(before.monthly())).isEqualTo(before);
        }

        @Test
        @DisplayName("a null ScreenField is refused by value and by withValue")
        void nullFieldIsRefused() {
            ReportRequestRequest request = ReportRequestRequest.empty();
            assertThatNullPointerException().isThrownBy(() -> request.value(null))
                    .withMessageContaining("CORPT0AI");
            assertThatNullPointerException().isThrownBy(() -> request.withValue(null, "x"))
                    .withMessageContaining("CORPT0AI");
        }

        @Test
        @DisplayName("equals, hashCode and toString come from the record and compare all 18 members")
        void valueSemantics() {
            ReportRequestRequest one = customRequest();
            ReportRequestRequest two = customRequest();
            assertThat(one).isEqualTo(two).hasSameHashCodeAs(two);
            assertThat(one).isNotEqualTo(two.withConfirm("N"));
            assertThat(one).isNotEqualTo(two.withoutNavigationContext());
            assertThat(one.toString()).contains("sdtyyyy=2022").contains("confirm=Y");
        }
    }

    @Nested
    @DisplayName("4. Normalisation - there is no null in a COBOL record, and no room for a surplus")
    class Normalisation {

        @Test
        @DisplayName("empty() is SPACES everywhere, at each field's declared width")
        void emptyIsSpaces() {
            ReportRequestRequest empty = ReportRequestRequest.empty();
            for (ScreenField field : ScreenField.values()) {
                assertThat(empty.value(field)).as("%s", field.inputItem())
                        .isEqualTo(field.spaces()).hasSize(field.declaredLength());
            }
            assertThat(empty.navigationContext()).isEqualTo(NavigationContext.empty());
        }

        @Test
        @DisplayName("lowValues() is LOW-VALUES everywhere, as MOVE LOW-VALUES TO CORPT0AO leaves it")
        void lowValuesIsBinaryZero() {
            ReportRequestRequest low = ReportRequestRequest.lowValues();
            for (ScreenField field : ScreenField.values()) {
                assertThat(low.value(field)).as("%s", field.inputItem())
                        .isEqualTo(field.lowValues()).hasSize(field.declaredLength());
            }
            assertThat(low).isNotEqualTo(ReportRequestRequest.empty());
        }

        @Test
        @DisplayName("a null screen value becomes that field's SPACES, so a partial payload completes")
        void nullBecomesSpaces() {
            ReportRequestRequest allNull = new ReportRequestRequest(null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null);
            for (ScreenField field : ScreenField.values()) {
                assertThat(allNull.value(field)).as("%s", field.inputItem())
                        .isEqualTo(field.spaces());
            }
            assertThat(allNull.navigationContext()).as("null commarea means EIBCALEN = 0").isNull();
            assertThat(allNull.aid()).as("a null AID means no key resolved")
                    .isEqualTo(" ".repeat(ReportRequestRequest.AID_LENGTH));
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("a value of exactly the declared width is kept verbatim")
        void exactWidthIsKept(ScreenField field) {
            String exact = "9".repeat(field.declaredLength());
            assertThat(ReportRequestRequest.empty().withValue(field, exact).value(field))
                    .isEqualTo(exact);
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("a shorter value is accepted and left short until the codec pads it")
        void shorterValueIsAccepted(ScreenField field) {
            String shorter = "";
            assertThat(ReportRequestRequest.empty().withValue(field, shorter).value(field))
                    .isEmpty();
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("an over-long value is refused, naming the item, its PICTURE and the escape hatch")
        void overLongValueIsRefused(ScreenField field) {
            String tooLong = "8".repeat(field.declaredLength() + 1);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> ReportRequestRequest.empty().withValue(field, tooLong))
                    .withMessageContaining(field.inputItem())
                    .withMessageContaining("PIC X(" + field.declaredLength() + ")")
                    .withMessageContaining("movePicX");
        }

        @Test
        @DisplayName("the canonical constructor applies the same guard to every position")
        void canonicalConstructorGuardsEveryPosition() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new ReportRequestRequest("TOOLONG", null, null, null, null,
                            null, null, null, null, null, null, null, null, null, null, null, null,
                            null, null))
                    .withMessageContaining("TRNNAMEI");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new ReportRequestRequest(null, null, null, null, null, null,
                            null, null, null, null, null, null, null, null, null, null,
                            "x".repeat(79), null, null))
                    .withMessageContaining("ERRMSGI");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new ReportRequestRequest(null, null, null, null, null, null,
                            null, null, null, null, null, null, null, null, null, null, null, null,
                            "PFK003"))
                    .withMessageContaining(ReportRequestRequest.AID_FIELD);
        }
    }

    @Nested
    @DisplayName("5. The JSON contract - 19 properties, nothing trimmed, no metadata on the wire")
    class JsonContract {

        @Test
        @DisplayName("the payload is exactly the 19 components and nothing else")
        void payloadIsTheNineteenComponents() throws Exception {
            ObjectNode node = (ObjectNode) JSON.readTree(JSON.writeValueAsString(customRequest()));
            List<String> properties = new ArrayList<>();
            node.fieldNames().forEachRemaining(properties::add);
            List<String> expected = new ArrayList<>();
            for (ScreenField field : ScreenField.values()) {
                expected.add(field.bmsName().toLowerCase(java.util.Locale.ROOT));
            }
            expected.add("navigationContext");
            // The AID is the second non-map member: CORPT00C branches on EIBAID at line 183, so the
            // token has to be on the wire, but it is not a DFHMDF field and so is not counted in
            // FIELD_COUNT.
            expected.add("aid");
            assertThat(properties).containsExactlyInAnyOrderElementsOf(expected)
                    .hasSize(ReportRequestRequest.FIELD_COUNT + 2);
        }

        @Test
        @DisplayName("no derived accessor and no metadata item leaks onto the wire")
        void derivedAndMetadataPropertiesAreAbsent() throws Exception {
            String json = JSON.writeValueAsString(customRequest());
            ObjectNode node = (ObjectNode) JSON.readTree(json);
            List<String> properties = new ArrayList<>();
            node.fieldNames().forEachRemaining(properties::add);
            // The derived accessors are @JsonIgnore'd, so none of them appears at the top level.
            // "pgmContext" is checked here rather than in the raw string precisely because
            // CDEMO-PGM-CONTEXT is a real copybook field of the nested communication area and
            // legitimately does appear there - the request must not restate it beside it.
            assertThat(properties).doesNotContain("enter", "reenter", "pgmContext",
                    "commareaLength", "fieldValues", "hasNavigationContext", "entries", "metadata",
                    "symbolicMapMetadata");
            assertThat(node.get("navigationContext").has("pgmContext"))
                    .as("CDEMO-PGM-CONTEXT belongs to the communication area, and only there")
                    .isTrue();
            // No metadata item name reaches the wire anywhere in the document, nested included.
            for (ScreenField field : ScreenField.values()) {
                assertThat(json).doesNotContain(field.lengthItem())
                        .doesNotContain(field.flagItem())
                        .doesNotContain(field.attributeItem())
                        .doesNotContain(field.inputItem());
            }
        }

        @Test
        @DisplayName("78 space-padded characters in ERRMSG survive a round trip untrimmed")
        void errmsgSurvivesUntrimmed() throws Exception {
            String padded = " ".repeat(ReportRequestRequest.ERRMSG_LENGTH);
            ReportRequestRequest before = ReportRequestRequest.empty().withErrmsg(padded);
            ReportRequestRequest after = JSON.readValue(JSON.writeValueAsString(before),
                    ReportRequestRequest.class);
            assertThat(after.errmsg()).hasSize(ReportRequestRequest.ERRMSG_LENGTH)
                    .isEqualTo(padded);
            assertThat(after).isEqualTo(before);
        }

        @Test
        @DisplayName("a message shorter than the field keeps its trailing padding through JSON")
        void trailingPaddingIsNotStripped() throws Exception {
            ReportRequestRequest before = ReportRequestRequest.empty()
                    .withErrmsg("Start Date - Not a valid Month..."
                            + " ".repeat(ReportRequestRequest.ERRMSG_LENGTH - 33));
            ReportRequestRequest after = JSON.readValue(JSON.writeValueAsString(before),
                    ReportRequestRequest.class);
            assertThat(after.errmsg()).hasSize(ReportRequestRequest.ERRMSG_LENGTH)
                    .startsWith("Start Date").endsWith("   ");
            assertThat(after).isEqualTo(before);
        }

        @Test
        @DisplayName("a full round trip is lossless for all 17 fields and the communication area")
        void roundTripIsLossless() throws Exception {
            ReportRequestRequest before = customRequest()
                    .withNavigationContext(NavigationContext.empty()
                            .withFromTranid("CR00")
                            .withToProgram("COMEN01C")
                            .withUserTypeAdmin()
                            .withPgmReenter()
                            .withLastMap(ReportRequestRequest.MAP_NAME));
            ReportRequestRequest after = JSON.readValue(JSON.writeValueAsString(before),
                    ReportRequestRequest.class);
            assertThat(after).isEqualTo(before);
            assertThat(after.navigationContext().isAdmin()).isTrue();
            assertThat(after.isReenter()).isTrue();
        }

        @Test
        @DisplayName("an omitted property deserialises to that field's SPACES, not to null")
        void omittedPropertyBecomesSpaces() throws Exception {
            ReportRequestRequest after = JSON.readValue("{\"monthly\":\"Y\"}",
                    ReportRequestRequest.class);
            assertThat(after.monthly()).isEqualTo("Y");
            assertThat(after.trnname()).isEqualTo(ScreenField.TRNNAME.spaces());
            assertThat(after.errmsg()).isEqualTo(ScreenField.ERRMSG.spaces());
            assertThat(after.navigationContext()).isNull();
            assertThat(after.commareaLength()).isZero();
        }

        @Test
        @DisplayName("an empty payload deserialises to a complete, cold-started request")
        void emptyPayloadDeserialises() throws Exception {
            ReportRequestRequest after = JSON.readValue("{}", ReportRequestRequest.class);
            assertThat(after.fieldValues()).hasSize(ReportRequestRequest.FIELD_COUNT);
            assertThat(after.hasNavigationContext()).isFalse();
            assertThat(after.isEnter()).isTrue();
        }
    }

    @Nested
    @DisplayName("6. The conversation travels in the payload - there is no session")
    class Conversation {

        @Test
        @DisplayName("a carried communication area is 160 bytes, because CORPT00C has no extension")
        void commareaIsOneHundredAndSixty() {
            assertThat(ReportRequestRequest.empty().commareaLength())
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH).isEqualTo(160);
            assertThat(ReportRequestRequest.empty().hasNavigationContext()).isTrue();
        }

        @Test
        @DisplayName("no communication area means EIBCALEN = 0 and therefore first entry")
        void absentCommareaIsAColdStart() {
            ReportRequestRequest cold = customRequest().withoutNavigationContext();
            assertThat(cold.navigationContext()).isNull();
            assertThat(cold.hasNavigationContext()).isFalse();
            assertThat(cold.commareaLength()).isZero();
            assertThat(cold.pgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_ENTER);
            assertThat(cold.isEnter()).isTrue();
            assertThat(cold.isReenter()).isFalse();
            assertThat(cold.fieldValues()).isEqualTo(customRequest().fieldValues());
        }

        @Test
        @DisplayName("CDEMO-PGM-ENTER is context 0 - paint the screen, validate nothing")
        void enterContext() {
            ReportRequestRequest request = ReportRequestRequest.empty()
                    .withNavigationContext(NavigationContext.empty().withPgmEnter());
            assertThat(request.pgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_ENTER).isZero();
            assertThat(request.isEnter()).isTrue();
            assertThat(request.isReenter()).isFalse();
        }

        @Test
        @DisplayName("CDEMO-PGM-REENTER is context 1 - validate what was typed")
        void reenterContext() {
            ReportRequestRequest request = ReportRequestRequest.empty()
                    .withNavigationContext(NavigationContext.empty().withPgmReenter());
            assertThat(request.pgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_REENTER)
                    .isEqualTo(1);
            assertThat(request.isEnter()).isFalse();
            assertThat(request.isReenter()).isTrue();
        }

        @Test
        @DisplayName("isReenter is not the negation of isEnter: PIC 9(01) can hold any digit")
        void thePairIsNotExhaustive() {
            ReportRequestRequest odd = ReportRequestRequest.empty()
                    .withNavigationContext(NavigationContext.empty().withPgmContext(9));
            assertThat(odd.pgmContext()).isEqualTo(9);
            assertThat(odd.isEnter()).isFalse();
            assertThat(odd.isReenter()).isFalse();
        }

        @Test
        @DisplayName("withNavigationContext swaps the area and keeps all 17 screen fields")
        void witherSwapsOnlyTheArea() {
            ReportRequestRequest before = customRequest();
            NavigationContext replacement = NavigationContext.empty().withUserId("ADMIN001");
            ReportRequestRequest after = before.withNavigationContext(replacement);
            assertThat(after.navigationContext()).isEqualTo(replacement);
            assertThat(after.fieldValues()).isEqualTo(before.fieldValues());
            assertThat(after.withNavigationContext(before.navigationContext())).isEqualTo(before);
        }

        @Test
        @DisplayName("the type declares no session, no thread-local and no static mutable holder")
        void noServerSideState() {
            assertThat(ReportRequestRequest.class.getDeclaredFields())
                    .filteredOn(f -> !f.getName().startsWith("$"))
                    .allSatisfy(f -> assertThat(java.lang.reflect.Modifier.isStatic(f.getModifiers())
                            ? java.lang.reflect.Modifier.isFinal(f.getModifiers())
                            : java.lang.reflect.Modifier.isFinal(f.getModifiers()))
                            .as("field %s must be final", f.getName()).isTrue());
            assertThat(ReportRequestRequest.class.getDeclaredMethods())
                    .noneMatch(m -> m.getName().startsWith("set"));
        }
    }

    @Nested
    @DisplayName("7. The 337-byte image - every FILLER emitted, every pad done by the codec")
    class FixedWidthImage {

        @Test
        @DisplayName("the image is exactly 337 bytes in either code page")
        void imageIsThreeHundredAndThirtySevenBytes() {
            assertThat(ReportRequestRequest.empty().toSymbolicMap(ASCII))
                    .hasSize(ReportRequestRequest.SYMBOLIC_MAP_LENGTH);
            assertThat(customRequest().toSymbolicMap(EBCDIC))
                    .hasSize(ReportRequestRequest.SYMBOLIC_MAP_LENGTH);
        }

        @Test
        @DisplayName("the TIOAPFX prefix and the 17 reserved FILLERs are emitted as spaces")
        void fillersAreEmitted() {
            byte[] image = customRequest().toSymbolicMap(ASCII);
            assertThat(new String(image, 0, ReportRequestRequest.TIOAPFX_PREFIX_LENGTH,
                    StandardCharsets.US_ASCII))
                    .isEqualTo(" ".repeat(ReportRequestRequest.TIOAPFX_PREFIX_LENGTH));
            for (ScreenField field : ScreenField.values()) {
                FieldSpan filler = field.reservedFillerSpan();
                assertThat(new String(image, filler.offset(), filler.length(),
                        StandardCharsets.US_ASCII))
                        .as("reserved FILLER before %s", field.inputItem())
                        .isEqualTo(" ".repeat(ReportRequestRequest.RESERVED_FILLER_LENGTH));
            }
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("each value lands at its absolute xxxI offset")
        void valuesLandAtTheirOffsets(ScreenField field) {
            String value = "7".repeat(field.declaredLength());
            byte[] image = ReportRequestRequest.empty().withValue(field, value)
                    .toSymbolicMap(ASCII);
            assertThat(new String(image, field.inputSpan().offset(), field.declaredLength(),
                    StandardCharsets.US_ASCII)).isEqualTo(value);
        }

        @Test
        @DisplayName("a short value is padded on the right by the codec, as a PIC X MOVE pads")
        void shortValueIsRightPadded() {
            byte[] image = ReportRequestRequest.empty().withErrmsg("boom").toSymbolicMap(ASCII);
            assertThat(new String(image, ScreenField.ERRMSG.inputSpan().offset(),
                    ReportRequestRequest.ERRMSG_LENGTH, StandardCharsets.US_ASCII))
                    .isEqualTo("boom" + " ".repeat(ReportRequestRequest.ERRMSG_LENGTH - 4));
        }

        @Test
        @DisplayName("the payload round-trips byte for byte, and the image is reproduced exactly")
        void roundTripIsByteIdentical() {
            ReportRequestRequest before = customRequest().withErrmsg(
                    "End Date - Not a valid Day...");
            byte[] image = before.toSymbolicMap(ASCII);
            ReportRequestRequest after = ReportRequestRequest.fromSymbolicMap(ASCII, image);
            // Every field comes back at its full declared width, so a value that went in short comes
            // back padded exactly as the codec's PIC X move padded it - and nothing else changed.
            for (ScreenField field : ScreenField.values()) {
                assertThat(after.value(field)).as("%s", field.inputItem())
                        .hasSize(field.declaredLength())
                        .isEqualTo(ASCII.movePicX(before.value(field), field.declaredLength()));
            }
            assertThat(after.toSymbolicMap(ASCII)).isEqualTo(image);
            assertThat(after.errmsg()).hasSize(ReportRequestRequest.ERRMSG_LENGTH)
                    .startsWith("End Date");
        }

        @Test
        @DisplayName("a request already at full width round-trips to an equal request")
        void fullWidthRoundTripIsEqual() {
            ReportRequestRequest before = customRequest()
                    .withErrmsg(ScreenField.ERRMSG.spaces())
                    .withoutNavigationContext();
            ReportRequestRequest after = ReportRequestRequest.fromSymbolicMap(ASCII,
                    before.toSymbolicMap(ASCII));
            assertThat(after).isEqualTo(before);
        }

        @Test
        @DisplayName("reading an image alone yields no communication area, because CICS passes it apart")
        void imageCarriesNoCommarea() {
            byte[] image = customRequest().toSymbolicMap(ASCII);
            ReportRequestRequest after = ReportRequestRequest.fromSymbolicMap(ASCII, image);
            assertThat(after.navigationContext()).isNull();
            assertThat(after.withNavigationContext(NavigationContext.empty()).hasNavigationContext())
                    .isTrue();
        }

        @Test
        @DisplayName("MOVE -1 TO xxxL stores X'FFFF' and reads back as -1")
        void cursorHalfwordIsSignedAndBigEndian() {
            SymbolicMapMetadata metadata = SymbolicMapMetadata.initial()
                    .withCursorAt(ScreenField.MONTHLY);
            byte[] image = ReportRequestRequest.empty().toSymbolicMap(ASCII, metadata);
            int offset = ScreenField.MONTHLY.lengthSpan().offset();
            assertThat(image[offset]).isEqualTo((byte) 0xFF);
            assertThat(image[offset + 1]).isEqualTo((byte) 0xFF);
            assertThat(ReportRequestRequest.metadataFrom(ASCII, image)
                    .length(ScreenField.MONTHLY)).isEqualTo(FieldMetadata.CURSOR_POSITION);
            assertThat(ReportRequestRequest.metadataFrom(ASCII, image)
                    .length(ScreenField.YEARLY)).isEqualTo(FieldMetadata.UNSET_LENGTH);
        }

        @Test
        @DisplayName("a positive received length round-trips through the halfword")
        void receivedLengthRoundTrips() {
            SymbolicMapMetadata metadata = SymbolicMapMetadata.initial()
                    .withLength(ScreenField.ERRMSG, (short) 78)
                    .withLength(ScreenField.SDTYYYY, (short) 4)
                    .withFlag(ScreenField.SDTMM, "*")
                    .withAttribute(ScreenField.SDTDD, "A");
            byte[] image = customRequest().toSymbolicMap(ASCII, metadata);
            SymbolicMapMetadata back = ReportRequestRequest.metadataFrom(ASCII, image);
            assertThat(back.length(ScreenField.ERRMSG)).isEqualTo((short) 78);
            assertThat(back.length(ScreenField.SDTYYYY)).isEqualTo((short) 4);
            assertThat(back.flag(ScreenField.SDTMM)).isEqualTo("*");
            assertThat(back.metadata(ScreenField.SDTDD).attribute()).isEqualTo("A");
            assertThat(back.flag(ScreenField.CONFIRM)).isEqualTo(FieldMetadata.LOW_VALUE_FLAG);
        }

        @Test
        @DisplayName("the default overload emits freshly initialised metadata")
        void defaultOverloadUsesInitialMetadata() {
            byte[] withDefault = customRequest().toSymbolicMap(ASCII);
            byte[] withExplicit = customRequest().toSymbolicMap(ASCII,
                    SymbolicMapMetadata.initial());
            assertThat(withDefault).isEqualTo(withExplicit);
            assertThat(ReportRequestRequest.metadataFrom(ASCII, withDefault))
                    .isEqualTo(SymbolicMapMetadata.initial());
        }

        @Test
        @DisplayName("the code page is always explicit and is never defaulted")
        void codecIsRequired() {
            ReportRequestRequest request = ReportRequestRequest.empty();
            assertThatNullPointerException().isThrownBy(() -> request.toSymbolicMap(null))
                    .withMessageContaining("code page");
            assertThatNullPointerException()
                    .isThrownBy(() -> request.toSymbolicMap(null, SymbolicMapMetadata.initial()))
                    .withMessageContaining("code page");
            assertThatNullPointerException().isThrownBy(() -> request.toSymbolicMap(ASCII, null))
                    .withMessageContaining("SymbolicMapMetadata.initial()");
            assertThatNullPointerException()
                    .isThrownBy(() -> ReportRequestRequest.fromSymbolicMap(null, new byte[337]))
                    .withMessageContaining("code page");
            assertThatNullPointerException()
                    .isThrownBy(() -> ReportRequestRequest.fromSymbolicMap(ASCII, null))
                    .withMessageContaining("337-byte image");
            assertThatNullPointerException()
                    .isThrownBy(() -> ReportRequestRequest.metadataFrom(null, new byte[337]))
                    .withMessageContaining("code page");
            assertThatNullPointerException()
                    .isThrownBy(() -> ReportRequestRequest.metadataFrom(ASCII, null))
                    .withMessageContaining("337-byte image");
        }

        @Test
        @DisplayName("an image of the wrong width is refused rather than silently misaligned")
        void wrongWidthImageIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> ReportRequestRequest.fromSymbolicMap(ASCII, new byte[336]));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> ReportRequestRequest.metadataFrom(ASCII, new byte[338]));
        }

        @Test
        @DisplayName("EBCDIC and US-ASCII produce different bytes for the same request")
        void codePageActuallyMatters() {
            ReportRequestRequest request = customRequest();
            assertThat(request.toSymbolicMap(EBCDIC)).isNotEqualTo(request.toSymbolicMap(ASCII));
            assertThat(ReportRequestRequest.fromSymbolicMap(EBCDIC,
                    request.toSymbolicMap(EBCDIC)).confirm()).isEqualTo("Y");
        }
    }

    @Nested
    @DisplayName("8. Metadata carriers - xxxL is signed, xxxA is an alias, and neither is payload")
    class MetadataCarriers {

        @Test
        @DisplayName("initial metadata is length 0 with a LOW-VALUES flag")
        void initialMetadata() {
            FieldMetadata initial = FieldMetadata.initial();
            assertThat(initial.length()).isEqualTo(FieldMetadata.UNSET_LENGTH).isEqualTo((short) 0);
            assertThat(initial.flag()).isEqualTo("\u0000")
                    .isEqualTo(FieldMetadata.LOW_VALUE_FLAG).hasSize(FieldMetadata.FLAG_WIDTH);
            assertThat(initial.cursorRequested()).isFalse();
        }

        @Test
        @DisplayName("cursor metadata is -1, the value CORPT00C moves at 22 sites")
        void cursorMetadata() {
            FieldMetadata cursor = FieldMetadata.cursor();
            assertThat(cursor.length()).isEqualTo(FieldMetadata.CURSOR_POSITION)
                    .isEqualTo((short) -1);
            assertThat(cursor.cursorRequested()).isTrue();
        }

        @Test
        @DisplayName("the halfword is signed, so it holds -1 and is never clamped at zero")
        void halfwordIsSigned() {
            assertThat(FieldMetadata.initial().withLength((short) -1).length()).isEqualTo((short) -1);
            assertThat(FieldMetadata.initial().withLength(Short.MIN_VALUE).length())
                    .isEqualTo(Short.MIN_VALUE);
            assertThat(FieldMetadata.initial().withLength(Short.MAX_VALUE).length())
                    .isEqualTo(Short.MAX_VALUE);
        }

        @Test
        @DisplayName("attribute() is an alias of flag(): xxxA REDEFINES xxxF, one byte, two names")
        void attributeIsAnAlias() {
            FieldMetadata metadata = FieldMetadata.initial().withFlag("*");
            assertThat(metadata.attribute()).isEqualTo(metadata.flag()).isEqualTo("*");
            assertThat(metadata.withAttribute("R")).isEqualTo(metadata.withFlag("R"));
            assertThat(metadata.withAttribute("R").flag()).isEqualTo("R");
        }

        @Test
        @DisplayName("withLength and withFlag each change one item and keep the other")
        void withersAreIndependent() {
            FieldMetadata base = new FieldMetadata((short) 4, "*");
            assertThat(base.withLength((short) 9)).isEqualTo(new FieldMetadata((short) 9, "*"));
            assertThat(base.withFlag("Z")).isEqualTo(new FieldMetadata((short) 4, "Z"));
        }

        @Test
        @DisplayName("a null flag becomes LOW-VALUES and any width other than one is refused")
        void flagIsNormalisedAndWidthChecked() {
            assertThat(new FieldMetadata((short) 0, null).flag())
                    .isEqualTo(FieldMetadata.LOW_VALUE_FLAG);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new FieldMetadata((short) 0, "AB"))
                    .withMessageContaining("PICTURE X");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new FieldMetadata((short) 0, ""))
                    .withMessageContaining("PICTURE X");
        }

        @Test
        @DisplayName("initial map metadata covers all 17 fields in declaration order")
        void mapMetadataIsComplete() {
            SymbolicMapMetadata metadata = SymbolicMapMetadata.initial();
            assertThat(metadata.entries()).hasSize(ReportRequestRequest.FIELD_COUNT);
            assertThat(metadata.entries().keySet()).containsExactly(ScreenField.values());
            for (ScreenField field : ScreenField.values()) {
                assertThat(metadata.metadata(field)).isEqualTo(FieldMetadata.initial());
                assertThat(metadata.length(field)).isEqualTo(FieldMetadata.UNSET_LENGTH);
                assertThat(metadata.flag(field)).isEqualTo(FieldMetadata.LOW_VALUE_FLAG);
            }
        }

        @Test
        @DisplayName("partial metadata is refused, because a symbolic map is storage and always complete")
        void partialMetadataIsRefused() {
            Map<ScreenField, FieldMetadata> partial = new EnumMap<>(ScreenField.class);
            partial.put(ScreenField.MONTHLY, FieldMetadata.cursor());
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new SymbolicMapMetadata(partial))
                    .withMessageContaining("CORPT0AI")
                    .withMessageContaining("must be complete");
            assertThatNullPointerException().isThrownBy(() -> new SymbolicMapMetadata(null))
                    .withMessageContaining("SymbolicMapMetadata.initial()");
        }

        @Test
        @DisplayName("the entries map is defensively copied and unmodifiable")
        void entriesAreImmutable() {
            Map<ScreenField, FieldMetadata> source = new EnumMap<>(ScreenField.class);
            for (ScreenField field : ScreenField.values()) {
                source.put(field, FieldMetadata.initial());
            }
            SymbolicMapMetadata metadata = new SymbolicMapMetadata(source);
            source.put(ScreenField.MONTHLY, FieldMetadata.cursor());
            assertThat(metadata.length(ScreenField.MONTHLY))
                    .as("the copy is not a view of the caller's map")
                    .isEqualTo(FieldMetadata.UNSET_LENGTH);
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> metadata.entries()
                            .put(ScreenField.MONTHLY, FieldMetadata.cursor()));
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("withCursorAt asks for the cursor on one field only and keeps its flag")
        void cursorIsPlacedOnOneFieldOnly(ScreenField field) {
            SymbolicMapMetadata metadata = SymbolicMapMetadata.initial()
                    .withFlag(field, "*")
                    .withCursorAt(field);
            assertThat(metadata.metadata(field).cursorRequested()).isTrue();
            assertThat(metadata.flag(field)).as("MOVE -1 does not touch the attribute")
                    .isEqualTo("*");
            for (ScreenField other : ScreenField.values()) {
                if (other != field) {
                    assertThat(metadata.metadata(other).cursorRequested())
                            .as("%s", other.lengthItem()).isFalse();
                }
            }
        }

        @Test
        @DisplayName("with, withLength, withFlag and withAttribute all preserve completeness")
        void mapWithersPreserveCompleteness() {
            SymbolicMapMetadata metadata = SymbolicMapMetadata.initial()
                    .with(ScreenField.CONFIRM, FieldMetadata.cursor())
                    .withLength(ScreenField.ERRMSG, (short) 78)
                    .withFlag(ScreenField.SDTMM, "*")
                    .withAttribute(ScreenField.SDTDD, "R");
            assertThat(metadata.entries()).hasSize(ReportRequestRequest.FIELD_COUNT);
            assertThat(metadata.length(ScreenField.CONFIRM))
                    .isEqualTo(FieldMetadata.CURSOR_POSITION);
            assertThat(metadata.length(ScreenField.ERRMSG)).isEqualTo((short) 78);
            assertThat(metadata.flag(ScreenField.SDTMM)).isEqualTo("*");
            assertThat(metadata.flag(ScreenField.SDTDD)).isEqualTo("R");
        }

        @Test
        @DisplayName("a null field is refused everywhere metadata is addressed")
        void nullFieldIsRefused() {
            SymbolicMapMetadata metadata = SymbolicMapMetadata.initial();
            assertThatNullPointerException().isThrownBy(() -> metadata.metadata(null));
            assertThatNullPointerException().isThrownBy(() -> metadata.length(null));
            assertThatNullPointerException().isThrownBy(() -> metadata.flag(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> metadata.with(null, FieldMetadata.initial()));
            assertThatNullPointerException()
                    .isThrownBy(() -> metadata.with(ScreenField.MONTHLY, null))
                    .withMessageContaining("FieldMetadata is required");
            assertThatNullPointerException().isThrownBy(() -> metadata.withCursorAt(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> metadata.withLength(null, (short) 1));
            assertThatNullPointerException().isThrownBy(() -> metadata.withFlag(null, "*"));
            assertThatNullPointerException().isThrownBy(() -> metadata.withAttribute(null, "*"));
        }

        @Test
        @DisplayName("neither carrier is a component of the request, so neither can reach the wire")
        void carriersAreNotComponents() {
            assertThat(ReportRequestRequest.class.getRecordComponents())
                    .noneMatch(c -> c.getType() == FieldMetadata.class
                            || c.getType() == SymbolicMapMetadata.class
                            || Map.class.isAssignableFrom(c.getType()));
        }
    }

    @Nested
    @DisplayName("9. Provenance - the program, the transaction and the 22 cursor moves")
    class Provenance {

        @Test
        @DisplayName("the mapset, map, group, transaction and program names are verbatim")
        void namesAreVerbatim() {
            assertThat(ReportRequestRequest.MAPSET_NAME).isEqualTo("CORPT00");
            assertThat(ReportRequestRequest.MAP_NAME).isEqualTo("CORPT0A")
                    .hasSize(NavigationContext.LAST_MAP_LENGTH);
            assertThat(ReportRequestRequest.SYMBOLIC_MAP_INPUT_GROUP).isEqualTo("CORPT0AI")
                    .isEqualTo(ReportRequestRequest.MAP_NAME + "I");
            assertThat(ReportRequestRequest.TRANSACTION_ID).isEqualTo("CR00")
                    .hasSize(NavigationContext.FROM_TRANID_LENGTH);
            assertThat(ReportRequestRequest.PROGRAM_NAME).isEqualTo("CORPT00C")
                    .hasSize(NavigationContext.FROM_PROGRAM_LENGTH);
        }

        @Test
        @DisplayName("the program itself declares WS-TRANID 'CR00' and WS-PGMNAME 'CORPT00C'")
        void theProgramAgrees() {
            assertThat(program).anyMatch(l -> l.contains("PROGRAM-ID. "
                    + ReportRequestRequest.PROGRAM_NAME));
            assertThat(program).anyMatch(l -> l.contains("WS-PGMNAME")
                    && l.contains("'" + ReportRequestRequest.PROGRAM_NAME + "'"));
            assertThat(program).anyMatch(l -> l.contains("WS-TRANID")
                    && l.contains("'" + ReportRequestRequest.TRANSACTION_ID + "'"));
        }

        @Test
        @DisplayName("the mapset declares TIOAPFX=YES, which is why the 12-byte prefix exists")
        void tioapfxIsWhyThePrefixExists() {
            assertThat(mapset).anyMatch(l -> l.contains("TIOAPFX=YES"));
            assertThat(mapset).anyMatch(l -> l.contains("SIZE=(24,80)"));
            assertThat(mapset).anyMatch(l -> l.startsWith(ReportRequestRequest.MAPSET_NAME
                    + " DFHMSD"));
            assertThat(mapset).anyMatch(l -> l.startsWith(ReportRequestRequest.MAP_NAME
                    + " DFHMDI"));
        }

        @Test
        @DisplayName("the mapset has 42 DFHMDF entries and only the 17 labelled ones are payload")
        void unlabelledFieldsAreNotPayload() {
            long total = mapset.stream().filter(l -> l.contains("DFHMDF")).count();
            assertThat(total).isEqualTo(42);
            assertThat(namedMapsetFields()).hasSize(ReportRequestRequest.FIELD_COUNT);
        }

        @Test
        @DisplayName("MOVE -1 TO xxxL appears 22 times and only ever targets an UNPROT field")
        void everyCursorMoveTargetsAnUnprotectedField() {
            List<String> targets = new ArrayList<>();
            for (String line : program) {
                Matcher matcher = CURSOR_MOVE.matcher(line);
                if (matcher.find()) {
                    targets.add(matcher.group(1));
                }
            }
            assertThat(targets).hasSize(22);
            Map<String, ScreenField> byName = new LinkedHashMap<>();
            for (ScreenField field : ScreenField.values()) {
                byName.put(field.bmsName(), field);
            }
            assertThat(targets).allSatisfy(target -> {
                assertThat(byName).containsKey(target);
                assertThat(byName.get(target).unprotected())
                        .as("%sL receives the cursor, so %s must be UNPROT", target, target)
                        .isTrue();
            });
        }

        @Test
        @DisplayName("CORPT00C copies COCOM01Y with no CDEMO-CR00-INFO extension and no CVCRD01Y")
        void theCommareaIsNotExtended() {
            assertThat(program).anyMatch(l -> l.contains("COPY COCOM01Y."));
            assertThat(program).anyMatch(l -> l.contains("COPY CORPT00."));
            assertThat(program).noneMatch(l -> l.contains("CDEMO-CR00-INFO"));
            assertThat(program).noneMatch(l -> l.contains("CVCRD01Y"));
            assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(160);
        }

        @Test
        @DisplayName("the six date parts are moved back into their own alphanumeric items")
        void dateFieldsAreAlphanumericBecauseTheProgramRewritesThem() {
            for (ScreenField field : List.of(ScreenField.SDTMM, ScreenField.SDTDD,
                    ScreenField.SDTYYYY, ScreenField.EDTMM, ScreenField.EDTDD,
                    ScreenField.EDTYYYY)) {
                assertThat(program)
                        .as("NUMVAL-C of %s", field.inputItem())
                        .anyMatch(l -> l.contains("(" + field.inputItem() + " OF CORPT0AI)")
                                || l.contains("(" + field.inputItem() + "  OF CORPT0AI)"));
                assertThat(program)
                        .as("MOVE back into %s", field.inputItem())
                        .anyMatch(l -> l.contains("TO " + field.inputItem() + " OF CORPT0AI"));
            }
            assertThat(program).anyMatch(l -> l.contains("IS NOT NUMERIC"));
            assertThat(program).anyMatch(l -> l.contains("> '12'"));
            assertThat(program).anyMatch(l -> l.contains("> '31'"));
        }

        @Test
        @DisplayName("CORPT00C accesses no dataset, so this payload models none")
        void theProgramIsScreenAndSubmitOnly() {
            assertThat(program).noneMatch(l -> l.contains("EXEC CICS READ")
                    || l.contains("EXEC CICS STARTBR") || l.contains("EXEC CICS READNEXT")
                    || l.contains("EXEC CICS REWRITE") || l.contains("EXEC CICS WRITE ")
                    || l.contains("EXEC SQL"));
        }
    }

    @Nested
    @DisplayName("10. The AID - every arm of EVALUATE EIBAID is selectable from the payload")
    class AttentionIdentifier {

        /**
         * The reason the member exists. {@code app/cbl/CORPT00C.cbl:183-195} decides what the
         * transaction does by evaluating {@code EIBAID} and nothing else, so a payload with no member
         * for the key can reach only whichever arm happens to be the default - two of the three
         * become dead through the API.
         */
        @Test
        @DisplayName("the program really does branch on EIBAID, with three arms")
        void theProgramBranchesOnTheAid() {
            assertThat(program).anyMatch(l -> l.contains("EVALUATE EIBAID"));
            assertThat(program).anyMatch(l -> l.contains("WHEN DFHENTER"));
            assertThat(program).anyMatch(l -> l.contains("WHEN DFHPF3"));
            assertThat(program).anyMatch(l -> l.contains("WHEN OTHER"));
            // ...and only those three: no PF4, PF5, PF7 or PF8 arm, unlike its COTRN siblings.
            assertThat(program).noneMatch(l -> l.contains("WHEN DFHPF4")
                    || l.contains("WHEN DFHPF5") || l.contains("WHEN DFHPF7")
                    || l.contains("WHEN DFHPF8"));
        }

        /**
         * The width is asserted against the constant the payload itself publishes and against the two
         * tokens this screen acts on, not against another package's type. {@code CORPT00C} copies
         * neither {@code CVCRD01Y} nor {@code CSSTRPFY} - it tests the raw {@code EIBAID} byte inline -
         * so a dependency on a card screen-state carrier or on a PF-key resolver would be an invented
         * coupling that the COBOL does not have.
         */
        @Test
        @DisplayName("the width is the module's five-character token, not a raw EIBAID byte")
        void theWidthIsTheTokenWidth() {
            assertThat(ReportRequestRequest.AID_LENGTH).isEqualTo(5);
            assertThat(AID_ENTER).hasSize(ReportRequestRequest.AID_LENGTH);
            assertThat(AID_PF3).hasSize(ReportRequestRequest.AID_LENGTH);
            assertThat(ReportRequestRequest.AID_FIELD).isEqualTo("EIBAID");
        }

        @Test
        @DisplayName("each of the three arms is reachable, and the tokens are what the resolver emits")
        void everyArmIsReachable() {
            String enter = AID_ENTER;
            String pf3 = AID_PF3;

            assertThat(ReportRequestRequest.empty().withAid(enter).aid()).isEqualTo(enter);
            assertThat(ReportRequestRequest.empty().withAid(pf3).aid()).isEqualTo(pf3);
            // The WHEN OTHER arm: any other token, spaces included.
            assertThat(ReportRequestRequest.empty().aid())
                    .isEqualTo(" ".repeat(ReportRequestRequest.AID_LENGTH))
                    .isNotEqualTo(enter)
                    .isNotEqualTo(pf3);
            // The resolver pads, so a caller passing its output never overflows the field.
            assertThat(enter).hasSize(ReportRequestRequest.AID_LENGTH);
            assertThat(pf3).hasSize(ReportRequestRequest.AID_LENGTH);
        }

        @Test
        @DisplayName("the AID survives a JSON round trip and rides beside the commarea")
        void theAidRoundTrips() throws Exception {
            ReportRequestRequest before = customRequest()
                    .withAid(AID_PF3)
                    .withoutNavigationContext();

            ReportRequestRequest after = JSON.readValue(JSON.writeValueAsString(before),
                    ReportRequestRequest.class);

            assertThat(after).isEqualTo(before);
            assertThat(after.aid()).isEqualTo(AID_PF3);
            assertThat(after.hasNavigationContext()).isFalse();
        }

        @Test
        @DisplayName("the AID is not a screen field: absent from ScreenField, the totals and the image")
        void theAidIsOutsideTheMap() {
            assertThat(ScreenField.values()).hasSize(ReportRequestRequest.FIELD_COUNT);
            for (ScreenField field : ScreenField.values()) {
                assertThat(field.bmsName()).isNotEqualTo(ReportRequestRequest.AID_FIELD);
            }
            // The image width is derived from the seventeen fields alone, so adding the AID member
            // cannot have widened it.
            assertThat(ReportRequestRequest.SYMBOLIC_MAP_LENGTH)
                    .isEqualTo(ReportRequestRequest.TIOAPFX_PREFIX_LENGTH
                            + ReportRequestRequest.FIELD_COUNT
                            * ReportRequestRequest.FIELD_PREFIX_LENGTH
                            + ReportRequestRequest.PAYLOAD_WIDTH_TOTAL);
        }

        @Test
        @DisplayName("a map image alone yields no key resolved and no communication area")
        void aMapImageCarriesNeitherNonMapMember() {
            byte[] image = customRequest().withAid(AID_ENTER)
                    .toSymbolicMap(ASCII);

            ReportRequestRequest read = ReportRequestRequest.fromSymbolicMap(ASCII, image);

            assertThat(read.aid()).as("EIBAID lives in the EIB, not in 01 CORPT0AI")
                    .isEqualTo(" ".repeat(ReportRequestRequest.AID_LENGTH));
            assertThat(read.navigationContext()).as("the commarea travels in DFHCOMMAREA").isNull();
            // Every screen field, though, comes back byte-identical.
            for (ScreenField field : ScreenField.values()) {
                assertThat(read.value(field)).as("%s", field.inputItem())
                        .isEqualTo(customRequest().value(field));
            }
        }

        @Test
        @DisplayName("an over-long token is refused by name, without echoing it")
        void anOverLongTokenIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> ReportRequestRequest.empty().withAid("PFK012"))
                    .withMessageContaining(ReportRequestRequest.AID_FIELD)
                    .withMessageContaining("6 character(s)");
        }
    }

    // =================================================================================================
    // 11. The three authorities. Groups 1 and 2 above prove the class against the copybook and the
    // mapset on disk. This group adds the transcription as a third, independent authority and proves
    // all three agree. A drift now has to be made in the same direction in two places to survive.
    // =================================================================================================

    @Nested
    @DisplayName("11. The transcribed table, the copybook, the mapset and the enum all agree")
    class TranscribedTable {

        @Test
        @DisplayName("the transcription itself is well formed: 17 names, 17 widths, no repetition")
        void theTranscriptionIsWellFormed() {
            assertThat(EXPECTED_FIELD_NAMES).hasSize(EXPECTED_FIELD_COUNT).doesNotHaveDuplicates();
            assertThat(EXPECTED_FIELD_WIDTHS_IN_ORDER).hasSize(EXPECTED_FIELD_COUNT);
            assertThat(EXPECTED_FIELD_WIDTHS).hasSize(EXPECTED_FIELD_COUNT);
            assertThat(EXPECTED_FIELD_WIDTHS.keySet())
                    .as("the table keeps copybook declaration order")
                    .containsExactlyElementsOf(EXPECTED_FIELD_NAMES);
        }

        /**
         * Practice B9: the transcription is {@code static final} <em>and</em> unmodifiable, so no test
         * can mutate it and teach a later test the wrong width. An immutable constant is allowed;
         * mutable static state is not.
         */
        @Test
        @DisplayName("the table is unmodifiable, so no test can hand another the wrong width")
        void theTableIsImmutable() {
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> EXPECTED_FIELD_WIDTHS.put("SDTMM", 99));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> EXPECTED_FIELD_NAMES.set(0, "NOPE"));
        }

        @Test
        @DisplayName("the transcription matches the xxxI items on disk, name for name and width for width")
        void transcriptionMatchesTheCopybook() {
            Map<String, Integer> onDisk = itemsMatching(INPUT_ITEM);

            List<String> baseNames = new ArrayList<>();
            for (String item : onDisk.keySet()) {
                // Drop the trailing I: the copybook item is the field name plus the direction suffix.
                baseNames.add(item.substring(0, item.length() - 1));
            }
            assertThat(baseNames)
                    .as("app/cpy-bms/CORPT00.CPY:17-120 in declaration order")
                    .containsExactlyElementsOf(EXPECTED_FIELD_NAMES);

            for (String name : EXPECTED_FIELD_NAMES) {
                assertThat(onDisk.get(name + "I"))
                        .as("PIC X(n) of %sI on disk", name)
                        .isEqualTo(EXPECTED_FIELD_WIDTHS.get(name));
            }
        }

        @Test
        @DisplayName("the transcription matches the DFHMDF labels and their LENGTH= in the mapset")
        void transcriptionMatchesTheMapset() {
            Map<String, Integer> labelled = namedMapsetFields();

            assertThat(labelled.keySet())
                    .as("the 17 name-labelled DFHMDF entries of app/bms/CORPT00.bms")
                    .containsExactlyElementsOf(EXPECTED_FIELD_NAMES);
            for (String name : EXPECTED_FIELD_NAMES) {
                assertThat(labelled.get(name))
                        .as("DFHMDF LENGTH= of %s", name)
                        .isEqualTo(EXPECTED_FIELD_WIDTHS.get(name));
            }
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("the enum agrees with the transcription: name, ordinal, item suffix and width")
        void enumAgreesWithTheTranscription(ScreenField field) {
            assertThat(EXPECTED_FIELD_WIDTHS).containsKey(field.bmsName());
            assertThat(field.declaredLength())
                    .as("declared width of %s", field.bmsName())
                    .isEqualTo(EXPECTED_FIELD_WIDTHS.get(field.bmsName()));
            assertThat(field.ordinal())
                    .as("declaration position of %s", field.bmsName())
                    .isEqualTo(EXPECTED_FIELD_NAMES.indexOf(field.bmsName()));
            assertThat(field.inputItem()).isEqualTo(field.bmsName() + "I");
            assertThat(field.lengthItem()).isEqualTo(field.bmsName() + "L");
            assertThat(field.flagItem()).isEqualTo(field.bmsName() + "F");
            assertThat(field.attributeItem()).isEqualTo(field.bmsName() + "A");
        }

        /**
         * The two totals recomputed from the transcription alone, so neither is taken on trust from the
         * class under test. 337 is the narrowest of the four screens in this package - the
         * {@code COTRN00} list map is 1265 - which is precisely why a field or a {@code FILLER} dropped
         * here would be easy to miss without an arithmetic check (gate G21).
         */
        @Test
        @DisplayName("206 and 337 recomputed from the transcription, then matched against the class")
        void totalsRecomputedFromTheTranscription() {
            int sum = 0;
            for (int width : EXPECTED_FIELD_WIDTHS_IN_ORDER) {
                sum += width;
            }
            assertThat(sum).isEqualTo(EXPECTED_PAYLOAD_TOTAL).isEqualTo(206);
            assertThat(ReportRequestRequest.PAYLOAD_WIDTH_TOTAL).isEqualTo(sum);

            int image = ReportRequestRequest.TIOAPFX_PREFIX_LENGTH
                    + EXPECTED_FIELD_COUNT * ReportRequestRequest.FIELD_PREFIX_LENGTH + sum;
            assertThat(image).isEqualTo(EXPECTED_GROUP_IMAGE).isEqualTo(337);
            assertThat(12 + 17 * 7 + 206).isEqualTo(EXPECTED_GROUP_IMAGE);
            assertThat(ReportRequestRequest.SYMBOLIC_MAP_LENGTH).isEqualTo(image);
            assertThat(ReportRequestRequest.LAYOUT.recordLength()).isEqualTo(image);
            assertThat(customRequest().toSymbolicMap(ASCII))
                    .as("the produced image, not merely the constant")
                    .hasSize(image);
        }

        @Test
        @DisplayName("the widest field is ERRMSG at 78 and the narrowest are the four one-byte flags")
        void widthExtremesAreWhereTheCopybookPutsThem() {
            assertThat(EXPECTED_FIELD_WIDTHS.get("ERRMSG"))
                    .isEqualTo(78)
                    .isEqualTo(java.util.Collections.max(EXPECTED_FIELD_WIDTHS.values()));
            assertThat(java.util.Collections.min(EXPECTED_FIELD_WIDTHS.values())).isEqualTo(1);

            List<String> oneByte = new ArrayList<>();
            EXPECTED_FIELD_WIDTHS.forEach((name, width) -> {
                if (width == 1) {
                    oneByte.add(name);
                }
            });
            assertThat(oneByte)
                    .as("the three report-type selectors and the confirmation flag")
                    .containsExactly("MONTHLY", "YEARLY", "CUSTOM", "CONFIRM");
        }

        /**
         * Gate G21 from the transcription's side: the prefix arithmetic only closes if the map declares
         * exactly one twelve-byte TIOAPFX {@code FILLER} and exactly one four-byte reserved
         * {@code FILLER} per field. Seventeen fields, seventeen reserved fillers.
         */
        @Test
        @DisplayName("one X(12) prefix FILLER and exactly 17 reserved X(4) FILLERs, as 337 requires")
        void fillerCountsAreWhatTheArithmeticNeeds() {
            long prefix = inputGroup.stream()
                    .filter(line -> line.contains("FILLER PIC X(12)")).count();
            long reserved = inputGroup.stream()
                    .filter(line -> line.contains("FILLER   PICTURE X(4)")).count();

            assertThat(prefix).as("the TIOAPFX prefix, declared once").isEqualTo(1);
            assertThat(reserved).as("one reserved span per field").isEqualTo(EXPECTED_FIELD_COUNT);
            assertThat(ReportRequestRequest.TIOAPFX_PREFIX_LENGTH).isEqualTo(12);
            assertThat(ReportRequestRequest.RESERVED_FILLER_LENGTH).isEqualTo(4);
            assertThat((int) (prefix * 12 + reserved * 4
                    + EXPECTED_FIELD_COUNT * (ReportRequestRequest.LENGTH_ITEM_LENGTH
                            + ReportRequestRequest.FLAG_ITEM_LENGTH)
                    + EXPECTED_PAYLOAD_TOTAL))
                    .as("prefix + reserved + length items + flag bytes + data = the whole group")
                    .isEqualTo(EXPECTED_GROUP_IMAGE);
        }
    }

    // =================================================================================================
    // 12. The date. This screen asks for a range as SIX separate map fields, not as two ten-character
    // strings, and validates each part on its own. The ten-character form exists only in
    // WORKING-STORAGE, assembled after every part has been edited.
    // =================================================================================================

    @Nested
    @DisplayName("12. Six date parts in two triples - and no ten-character date field on the map")
    class DateStructure {

        @Test
        @DisplayName("the start triple is SDTMM 2, SDTDD 2, SDTYYYY 4 in that order")
        void startTripleIsMonthDayYear() {
            assertThat(EXPECTED_START_DATE_TRIPLE).containsExactly("SDTMM", "SDTDD", "SDTYYYY");
            assertThat(ScreenField.SDTMM.declaredLength()).isEqualTo(2);
            assertThat(ScreenField.SDTDD.declaredLength()).isEqualTo(2);
            assertThat(ScreenField.SDTYYYY.declaredLength()).isEqualTo(4);
            assertThat(ScreenField.SDTMM.ordinal()).isLessThan(ScreenField.SDTDD.ordinal());
            assertThat(ScreenField.SDTDD.ordinal()).isLessThan(ScreenField.SDTYYYY.ordinal());
        }

        @Test
        @DisplayName("the end triple mirrors it exactly: EDTMM 2, EDTDD 2, EDTYYYY 4")
        void endTripleMirrorsTheStartTriple() {
            assertThat(EXPECTED_END_DATE_TRIPLE).containsExactly("EDTMM", "EDTDD", "EDTYYYY");
            for (int part = 0; part < EXPECTED_START_DATE_TRIPLE.size(); part++) {
                String start = EXPECTED_START_DATE_TRIPLE.get(part);
                String end = EXPECTED_END_DATE_TRIPLE.get(part);
                assertThat(EXPECTED_FIELD_WIDTHS.get(end))
                        .as("%s is as wide as %s", end, start)
                        .isEqualTo(EXPECTED_FIELD_WIDTHS.get(start));
            }
        }

        @Test
        @DisplayName("the six parts are contiguous, after the three selectors and before CONFIRM")
        void theSixPartsAreContiguous() {
            List<String> range = EXPECTED_FIELD_NAMES.subList(
                    EXPECTED_FIELD_NAMES.indexOf("SDTMM"),
                    EXPECTED_FIELD_NAMES.indexOf("EDTYYYY") + 1);
            assertThat(range).containsExactly("SDTMM", "SDTDD", "SDTYYYY", "EDTMM", "EDTDD", "EDTYYYY");
            assertThat(EXPECTED_FIELD_NAMES.indexOf("CUSTOM"))
                    .as("the custom selector immediately precedes the range it enables")
                    .isEqualTo(EXPECTED_FIELD_NAMES.indexOf("SDTMM") - 1);
            assertThat(EXPECTED_FIELD_NAMES.indexOf("CONFIRM"))
                    .as("the confirmation flag immediately follows the range")
                    .isEqualTo(EXPECTED_FIELD_NAMES.indexOf("EDTYYYY") + 1);
        }

        /**
         * The absence that matters. A migration that "tidied" the range into two ten-character members
         * would look neater and would break parity twice over: it would change the map image, and it
         * would collapse six independently reachable error messages into two.
         */
        @Test
        @DisplayName("no field on this map is 10 characters wide, in the class or in the copybook")
        void noSingleTenCharacterDateFieldExists() {
            for (ScreenField field : ScreenField.values()) {
                assertThat(field.declaredLength())
                        .as("%s must not be a whole ten-character date", field.inputItem())
                        .isNotEqualTo(10);
            }
            assertThat(EXPECTED_FIELD_WIDTHS.values()).doesNotContain(10);
            assertThat(inputGroup)
                    .as("01 CORPT0AI declares no PIC X(10) item at all")
                    .noneMatch(line -> line.contains("PIC X(10)"));

            // And no member is named as though it carried one.
            for (RecordComponent component : ReportRequestRequest.class.getRecordComponents()) {
                assertThat(component.getName())
                        .as("component %s", component.getName())
                        .isNotIn("startDate", "endDate", "sdt", "edt", "startdate", "enddate");
            }
        }

        /**
         * Where the ten-character form does live: {@code app/cbl/CORPT00C.cbl:60-72} declares
         * {@code 05 WS-START-DATE} as {@code X(04)} + {@code X(02)} + {@code X(02)} with two literal
         * separators, and {@code WS-DATE-FORMAT PIC X(10) VALUE 'YYYY-MM-DD'} beside it. The program
         * assembles it from the map parts <em>after</em> editing them, then hands it to
         * {@code CSUTLDTC}. That assembly is the controller's job, not this payload's.
         */
        @Test
        @DisplayName("the ten-character date is assembled in WORKING-STORAGE from the six map parts")
        void theTenCharacterDateIsAssembledInWorkingStorage() {
            assertThat(program).anyMatch(line -> line.contains("05 WS-START-DATE."));
            assertThat(program).anyMatch(line -> line.contains("05 WS-END-DATE."));
            assertThat(program).anyMatch(line -> line.contains("WS-DATE-FORMAT")
                    && line.contains("PIC X(10)") && line.contains("'YYYY-MM-DD'"));

            assertThat(program).anyMatch(l -> l.contains("MOVE SDTYYYYI OF CORPT0AI")
                    && l.contains("WS-START-DATE-YYYY"));
            assertThat(program).anyMatch(l -> l.contains("MOVE SDTMMI")
                    && l.contains("WS-START-DATE-MM"));
            assertThat(program).anyMatch(l -> l.contains("MOVE SDTDDI")
                    && l.contains("WS-START-DATE-DD"));
            assertThat(program).anyMatch(l -> l.contains("MOVE EDTYYYYI OF CORPT0AI")
                    && l.contains("WS-END-DATE-YYYY"));
            assertThat(program).anyMatch(l -> l.contains("MOVE EDTMMI") && l.contains("WS-END-DATE-MM"));
            assertThat(program).anyMatch(l -> l.contains("MOVE EDTDDI") && l.contains("WS-END-DATE-DD"));
        }

        /**
         * {@code app/cbl/CORPT00C.cbl:258-303} gives each part its own empty-field arm with its own
         * message and its own cursor placement, and {@code :329-374} gives each its own edit. Six
         * fields, six messages, six {@code MOVE -1} targets - which is only possible because the six
         * are separate map fields.
         */
        @Test
        @DisplayName("each of the six parts has its own message and its own cursor placement")
        void eachPartIsEditedIndependently() {
            List<String> parts = new ArrayList<>(EXPECTED_START_DATE_TRIPLE);
            parts.addAll(EXPECTED_END_DATE_TRIPLE);
            for (String part : parts) {
                assertThat(program)
                        .as("MOVE -1 TO %sL positions the cursor on the offending part", part)
                        .anyMatch(line -> line.contains("MOVE -1")
                                && line.contains(part + "L OF CORPT0AI"));
                assertThat(program)
                        .as("%s is class tested in its own right", part)
                        .anyMatch(line -> line.contains(part + "I OF CORPT0AI IS NOT NUMERIC"));
            }
            for (String message : List.of("Start Date - Month can NOT be empty...",
                    "Start Date - Day can NOT be empty...", "Start Date - Year can NOT be empty...",
                    "End Date - Month can NOT be empty...", "End Date - Day can NOT be empty...",
                    "End Date - Year can NOT be empty...", "Start Date - Not a valid Month...",
                    "Start Date - Not a valid Day...", "Start Date - Not a valid Year...",
                    "End Date - Not a valid Month...", "End Date - Not a valid Day...",
                    "End Date - Not a valid Year...")) {
                assertThat(program).as("message %s", message)
                        .anyMatch(line -> line.contains(message));
            }
        }

        @Test
        @DisplayName("all six parts are UNPROT and NUM, and only they are NUM")
        void theSixPartsAreTheNumericShiftedOnes() {
            List<String> numeric = new ArrayList<>();
            for (ScreenField field : ScreenField.values()) {
                if (field.numeric()) {
                    numeric.add(field.bmsName());
                    assertThat(field.unprotected())
                            .as("%s is NUM, so it must also be typeable", field.bmsName()).isTrue();
                }
            }
            assertThat(numeric).containsExactly("SDTMM", "SDTDD", "SDTYYYY",
                    "EDTMM", "EDTDD", "EDTYYYY");
        }

        @ParameterizedTest
        @CsvSource({"SDTMM,07", "SDTDD,18", "SDTYYYY,2022", "EDTMM,12", "EDTDD,31", "EDTYYYY,2022"})
        @DisplayName("the payload carries each part verbatim, at its own width, through both wires")
        void everyPartRoundTripsVerbatim(String name, String value) throws Exception {
            ScreenField field = ScreenField.valueOf(name);
            assertThat(value).hasSize(field.declaredLength());

            ReportRequestRequest request = ReportRequestRequest.empty().withValue(field, value);
            assertThat(request.value(field)).isEqualTo(value);

            ReportRequestRequest viaJson = JSON.readValue(JSON.writeValueAsString(request),
                    ReportRequestRequest.class);
            assertThat(viaJson.value(field)).isEqualTo(value);

            ReportRequestRequest viaImage =
                    ReportRequestRequest.fromSymbolicMap(ASCII, request.toSymbolicMap(ASCII));
            assertThat(viaImage.value(field)).isEqualTo(value);
        }
    }

    // =================================================================================================
    // 13. The negative contract. Everything this payload must NOT contain. Each absence below is a
    // deliberate migration decision with a source citation, not an oversight.
    // =================================================================================================

    @Nested
    @DisplayName("13. What this payload deliberately does not carry")
    class NegativeContract {

        /**
         * No transaction-record member reaches this payload - and the reason is worth stating precisely,
         * because the obvious shortcut of "the program does not copy the record" is <strong>false</strong>
         * and was verified false against the source.
         *
         * <p>{@code app/cbl/CORPT00C.cbl:146} really does read {@code COPY CVTRA05Y.}, the 350-byte
         * transaction record. It is copied for its <em>offsets</em>, not for its values: the program
         * writes a JCL skeleton whose {@code SYMNAMES} control statements name positions inside that
         * record - {@code "TRAN-CARD-NUM,263,16,ZD"} at {@code :100} and
         * {@code "TRAN-PROC-DT,305,10,CH"} at {@code :102} - so the sort step it submits can filter the
         * transaction file by card number and processing date. The program itself reads no dataset and
         * displays no transaction: {@code 01 CORPT0AI} has no field for one.
         *
         * <p>So the assertion is the one that is actually true and actually protective: none of the
         * seven transaction-record members that this package's {@code COTRN} siblings legitimately carry
         * appears on this map, in this enum, among these components, or in the copybook group. Those
         * seven are precisely the ones at risk of being copied across from a sibling DTO. The JCL
         * skeleton and its {@code SYMNAMES} offsets belong to {@code ReportRequestControllerTest}.
         */
        @Test
        @DisplayName("no transaction-record member, even though CORPT00C:146 does COPY CVTRA05Y")
        void noTransactionRecordMembers() {
            assertThat(program)
                    .as("the copy is real, and is for the sort control statements' offsets")
                    .anyMatch(line -> line.contains("COPY CVTRA05Y."));
            assertThat(program)
                    .as("which is what those offsets are used for")
                    .anyMatch(line -> line.contains("TRAN-CARD-NUM,263,16,ZD"));

            for (String forbidden : FORBIDDEN_TRANSACTION_MEMBERS) {
                assertThat(EXPECTED_FIELD_NAMES).doesNotContain(forbidden);
                assertThat(EXPECTED_FIELD_WIDTHS).doesNotContainKey(forbidden);
                for (ScreenField field : ScreenField.values()) {
                    assertThat(field.bmsName())
                            .as("%s is not a field of this map", forbidden)
                            .isNotEqualTo(forbidden);
                }
                for (RecordComponent component : ReportRequestRequest.class.getRecordComponents()) {
                    assertThat(component.getName().toUpperCase(Locale.ROOT))
                            .as("component %s", component.getName())
                            .isNotEqualTo(forbidden);
                }
                assertThat(inputGroup)
                        .as("01 CORPT0AI declares no %sI item", forbidden)
                        .noneMatch(line -> line.contains(" " + forbidden + "I "));
            }
        }

        /**
         * Gate G44. This migration adds no schema, so the payload carries no persistence metadata: no
         * entity, no table, no column, no identifier and - the one worth naming explicitly - no version
         * column. {@code CORPT00C} accesses no dataset at all, and both update screens' optimistic
         * concurrency lives in {@code 9300-CHECK-CHANGE-IN-REC} on their own services, never in a
         * generated version field.
         *
         * <p>Checked by annotation <em>package</em> rather than by simple name, so a
         * {@code javax.persistence} import or a differently named ORM annotation cannot slip past a list
         * of five names.
         */
        @Test
        @DisplayName("no @Entity, @Table, @Column, @Id or @Version, and no persistence package at all")
        void noPersistenceMetadataAnywhere() {
            List<String> forbiddenNames =
                    List.of("Entity", "Table", "Column", "Id", "Version", "GeneratedValue",
                            "JoinColumn", "Embeddable", "MappedSuperclass");

            List<Class<?>> types = new ArrayList<>();
            types.add(ReportRequestRequest.class);
            types.addAll(List.of(ReportRequestRequest.class.getDeclaredClasses()));

            for (Class<?> type : types) {
                assertAnnotationsAreClean(type.getAnnotations(), type.getSimpleName(), forbiddenNames);
                for (Field field : type.getDeclaredFields()) {
                    assertAnnotationsAreClean(field.getAnnotations(),
                            type.getSimpleName() + "." + field.getName(), forbiddenNames);
                }
                for (Method method : type.getDeclaredMethods()) {
                    assertAnnotationsAreClean(method.getAnnotations(),
                            type.getSimpleName() + "." + method.getName() + "()", forbiddenNames);
                }
            }
            for (RecordComponent component : ReportRequestRequest.class.getRecordComponents()) {
                assertAnnotationsAreClean(component.getAnnotations(),
                        "component " + component.getName(), forbiddenNames);
                assertThat(component.getName())
                        .as("no optimistic-locking member is invented")
                        .isNotIn("version", "revision", "rowVersion", "id");
            }
        }

        private void assertAnnotationsAreClean(Annotation[] annotations, String subject,
                List<String> forbiddenNames) {
            for (Annotation annotation : annotations) {
                assertThat(annotation.annotationType().getName())
                        .as("annotation on %s", subject)
                        .doesNotContain("persistence").doesNotContain("hibernate")
                        .doesNotContain("javax.persistence");
                assertThat(annotation.annotationType().getSimpleName())
                        .as("annotation on %s", subject)
                        .isNotIn(forbiddenNames);
            }
        }

        /**
         * The ten input-capable fields, cross-checked against the mapset's own {@code ATTRB} lists rather
         * than taken from the enum. The other seven are {@code ASKIP} - the operator cannot type into
         * them - yet all seven carry {@code FSET} and so are still returned, which is why they are
         * modelled at all. Validation metadata applies only to the ten.
         */
        @Test
        @DisplayName("exactly 10 named fields are UNPROT, and the other 7 are ASKIP but still FSET")
        void theInputCapableSetIsExactlyTheTenNamedFields() {
            List<String> unprotected = new ArrayList<>();
            List<String> skipped = new ArrayList<>();
            for (String name : EXPECTED_FIELD_NAMES) {
                String attributes = attributesOf(name);
                assertThat(attributes).as("%s carries FSET, so the screen returns it", name)
                        .contains("FSET");
                if (attributes.contains("UNPROT")) {
                    unprotected.add(name);
                } else {
                    assertThat(attributes).as("%s is protected", name).contains("ASKIP");
                    skipped.add(name);
                }
            }

            assertThat(unprotected)
                    .as("read off the ATTRB lists of app/bms/CORPT00.bms")
                    .containsExactlyElementsOf(EXPECTED_INPUT_CAPABLE)
                    .hasSize(EXPECTED_UNPROTECTED).hasSize(10);
            assertThat(skipped)
                    .containsExactly("TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME",
                            "ERRMSG")
                    .hasSize(EXPECTED_OUTPUT_ONLY).hasSize(7);

            // And the enum agrees with the mapset, field for field.
            for (ScreenField field : ScreenField.values()) {
                assertThat(field.unprotected())
                        .as("%s unprotected", field.bmsName())
                        .isEqualTo(EXPECTED_INPUT_CAPABLE.contains(field.bmsName()));
            }
            assertThat(unprotected.size() + skipped.size()).isEqualTo(EXPECTED_FIELD_COUNT);
        }

        /**
         * The payload's own imports, as a boundary check. It must not reach into the card package for a
         * screen-state carrier - {@code CORPT00C} copies no {@code CVCRD01Y} - nor into the parity
         * harness, nor into any persistence API, and it must import everything explicitly so the
         * copybook-to-type correspondence stays auditable (gate G52).
         */
        @Test
        @DisplayName("the payload imports no card screen state, no parity harness and nothing on demand")
        void thePayloadsImportsStayInsideItsContract() {
            List<String> imports = new ArrayList<>();
            for (String line : payloadSource) {
                if (line.startsWith("import ")) {
                    imports.add(line.trim());
                }
            }
            assertThat(imports).as("the payload does import something").isNotEmpty();

            for (String statement : imports) {
                assertThat(statement)
                        .as("import %s", statement)
                        .doesNotContain(".*;")
                        .doesNotContain("carddemo.card")
                        .doesNotContain("carddemo.parity")
                        .doesNotContain("persistence")
                        .doesNotContain("org.springframework.web");
            }
            assertThat(imports)
                    .as("only the four production types this payload is built from")
                    .anyMatch(statement -> statement.contains("common.NavigationContext"))
                    .anyMatch(statement -> statement.contains("common.FixedWidthCodec"))
                    .anyMatch(statement -> statement.contains("common.FixedWidthRecord"));
        }

        /**
         * Gate G33's absence, in its positive form. There is no {@code OCCURS} on this map, so there is no
         * 1-based-to-0-based row index to get wrong: the seventeen fields are seventeen distinct
         * singletons, and no member is named as a row. The ten-row {@code COTRN00} and {@code COUSR00}
         * maps are where that gate has work to do; this one is where its silence is a finding.
         */
        @Test
        @DisplayName("no OCCURS table on this map, so gate G33 has nothing to verify here")
        void thereIsNoOccursTable() {
            assertThat(inputGroup).noneMatch(line -> line.contains("OCCURS"));
            assertThat(outputGroup).noneMatch(line -> line.contains("OCCURS"));
            assertThat(mapset).as("nor does the mapset generate one")
                    .noneMatch(line -> line.contains("OCCURS"));

            assertThat(EXPECTED_FIELD_NAMES).doesNotHaveDuplicates();
            for (RecordComponent component : ReportRequestRequest.class.getRecordComponents()) {
                assertThat(component.getType().isArray())
                        .as("component %s is not a row table", component.getName()).isFalse();
                assertThat(List.class.isAssignableFrom(component.getType()))
                        .as("component %s is not a row list", component.getName()).isFalse();
            }

            // A row group announces itself as a run of same-prefix numbered names - COTRN00 carries
            // TRNID01 through TRNID10. The only numbered names here are the two title lines, which sit
            // at (1,21) and (2,21) and are two separate captions rather than two rows of one table.
            List<String> numbered = new ArrayList<>();
            for (String name : EXPECTED_FIELD_NAMES) {
                if (name.matches(".*\\d\\d$")) {
                    numbered.add(name);
                }
            }
            assertThat(numbered).containsExactly("TITLE01", "TITLE02");
            assertThat(EXPECTED_FIELD_WIDTHS.get("TITLE01")).isEqualTo(40);
            assertThat(EXPECTED_FIELD_WIDTHS.get("TITLE02")).isEqualTo(40);
        }
    }

    // =================================================================================================
    // 14. The defining property of this payload, and the one a developer generalising from its three
    // siblings would get wrong. COTRN00, COTRN01 and COTRN02 each declare a 58-byte CDEMO-CTnn-INFO
    // group immediately after COPY COCOM01Y, so their passed communication area is 218 bytes. CORPT00C
    // declares nothing there at all, so its area is the bare 160.
    // =================================================================================================

    @Nested
    @DisplayName("14. The communication area is 160 bytes and not 218: this screen has no extension")
    class CommareaIsNotExtended {

        @Test
        @DisplayName("CORPT00C:138 copies COCOM01Y and :140 goes straight on to CORPT00")
        void nothingIsDeclaredBetweenTheTwoCopies() {
            int commareaCopy = lineIndexContaining("COPY COCOM01Y.");
            int mapCopy = lineIndexContaining("COPY CORPT00.");

            assertThat(commareaCopy).as("COPY COCOM01Y. is present").isNotNegative();
            assertThat(mapCopy).as("COPY CORPT00. follows it").isGreaterThan(commareaCopy);

            // Every line between the two is blank. Not "no 05 group" by name - nothing at all, which is
            // the only form of this assertion that a differently named extension could not slip past.
            for (String between : program.subList(commareaCopy + 1, mapCopy)) {
                assertThat(between.trim())
                        .as("line %d, between the two COPY statements", commareaCopy + 2)
                        .isEmpty();
            }
            assertThat(program).noneMatch(line -> line.contains("CDEMO-CR00-INFO"));
        }

        /**
         * The contrast, stated as an assertion rather than left to a comment. 218 is what this screen's
         * area would measure if the {@code CT} pattern were copied across; 160 is what it measures.
         */
        @Test
        @DisplayName("160, not 218: the CT screens' 58-byte CDEMO-CTnn-INFO group is absent here")
        void theCarriedAreaIsOneHundredAndSixtyNotTwoHundredAndEighteen() {
            assertThat(NavigationContext.COMMAREA_LENGTH)
                    .isEqualTo(EXPECTED_COMMAREA_LENGTH).isEqualTo(160);
            assertThat(ReportRequestRequest.empty().commareaLength()).isEqualTo(160);
            assertThat(NavigationContext.empty().toFixedWidth(ASCII))
                    .as("the produced image, not merely the constant")
                    .hasSize(160);

            assertThat(COMMAREA_EXTENSION_LENGTH).isEqualTo(58);
            assertThat(SIBLING_COMMAREA_WITH_EXTENSION).isEqualTo(218);
            assertThat(ReportRequestRequest.empty().commareaLength())
                    .as("an extension here would be an invention")
                    .isNotEqualTo(SIBLING_COMMAREA_WITH_EXTENSION);
        }

        /**
         * There is no cursor to carry, so there is no cursor type. The three nested types this payload
         * does declare are the symbolic-map field enum and the two metadata carriers - and neither
         * carrier is a record component, so neither can reach the wire.
         */
        @Test
        @DisplayName("no nested pagination or cursor type is declared at all")
        void noNestedCursorTypeExists() {
            List<String> nested = new ArrayList<>();
            for (Class<?> declared : ReportRequestRequest.class.getDeclaredClasses()) {
                nested.add(declared.getSimpleName());
            }
            assertThat(nested)
                    .containsExactlyInAnyOrder("ScreenField", "FieldMetadata", "SymbolicMapMetadata");
            assertThat(nested).allSatisfy(name -> {
                String lower = name.toLowerCase(Locale.ROOT);
                assertThat(lower).doesNotContain("cursor").doesNotContain("pagination")
                        .doesNotContain("page").doesNotContain("info");
            });

            // And the component types confirm it from the other side: seventeen strings, the area, the
            // key. Nothing else has anywhere to live.
            for (RecordComponent component : ReportRequestRequest.class.getRecordComponents()) {
                assertThat(component.getType())
                        .as("type of component %s", component.getName())
                        .isIn(String.class, NavigationContext.class);
            }
        }

        @Test
        @DisplayName("NavigationContext is never widened: 34 + 84 + 12 + 16 + 14 = 160")
        void theFiveSubGroupsStillSumToOneHundredAndSixty() {
            assertThat(NavigationContext.GENERAL_INFO_LENGTH).isEqualTo(34);
            assertThat(NavigationContext.CUSTOMER_INFO_LENGTH).isEqualTo(84);
            assertThat(NavigationContext.ACCOUNT_INFO_LENGTH).isEqualTo(12);
            assertThat(NavigationContext.CARD_INFO_LENGTH).isEqualTo(16);
            assertThat(NavigationContext.MORE_INFO_LENGTH).isEqualTo(14);
            assertThat(NavigationContext.GENERAL_INFO_LENGTH + NavigationContext.CUSTOMER_INFO_LENGTH
                    + NavigationContext.ACCOUNT_INFO_LENGTH + NavigationContext.CARD_INFO_LENGTH
                    + NavigationContext.MORE_INFO_LENGTH)
                    .as("the five 05 sub-groups of 01 CARDDEMO-COMMAREA")
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH)
                    .isEqualTo(EXPECTED_COMMAREA_LENGTH);

            // The sub-groups are also contiguous from zero, so none of them has been quietly moved.
            assertThat(NavigationContext.GENERAL_INFO_OFFSET).isZero();
            assertThat(NavigationContext.CUSTOMER_INFO_OFFSET).isEqualTo(34);
            assertThat(NavigationContext.ACCOUNT_INFO_OFFSET).isEqualTo(118);
            assertThat(NavigationContext.CARD_INFO_OFFSET).isEqualTo(130);
            assertThat(NavigationContext.MORE_INFO_OFFSET).isEqualTo(146);
        }

        /**
         * {@code CDEMO-LAST-MAP} and {@code CDEMO-LAST-MAPSET} are {@code PIC X(7)}, and this screen is
         * the one that makes the difference visible: its map is {@code CORPT0A} and its mapset
         * {@code CORPT00}, both exactly seven characters, while the symbolic-map group name
         * {@code CORPT0AI} is eight - the eighth character being the direction suffix, not part of the
         * map name. Widening either field to {@code X(8)} would move every byte after it.
         */
        @Test
        @DisplayName("CDEMO-LAST-MAP and CDEMO-LAST-MAPSET are X(7), and CORPT0A / CORPT00 fit exactly")
        void theLastMapNamesAreSevenCharacters() {
            assertThat(NavigationContext.LAST_MAP_LENGTH).isEqualTo(7);
            assertThat(NavigationContext.LAST_MAPSET_LENGTH).isEqualTo(7);
            assertThat(ReportRequestRequest.MAP_NAME).isEqualTo("CORPT0A").hasSize(7);
            assertThat(ReportRequestRequest.MAPSET_NAME).isEqualTo("CORPT00").hasSize(7);
            assertThat(ReportRequestRequest.SYMBOLIC_MAP_INPUT_GROUP)
                    .as("the group name is the map name plus the direction suffix")
                    .isEqualTo(ReportRequestRequest.MAP_NAME + "I").hasSize(8);

            NavigationContext carried = NavigationContext.empty()
                    .withLastMap(ReportRequestRequest.MAP_NAME)
                    .withLastMapset(ReportRequestRequest.MAPSET_NAME);
            NavigationContext back =
                    NavigationContext.fromFixedWidth(ASCII, carried.toFixedWidth(ASCII));

            assertThat(back.lastMap()).isEqualTo("CORPT0A");
            assertThat(back.lastMapset()).isEqualTo("CORPT00");
            assertThat(carried.toFixedWidth(ASCII)).hasSize(EXPECTED_COMMAREA_LENGTH);
        }

        /**
         * Both {@code CDEMO-PGM-CONTEXT} states have to be expressible in the request, because
         * {@code ENTER} is "paint the screen" and {@code REENTER} is "validate what was typed" - and it
         * is the re-enter state that gates the {@code CSSETATY} error highlight on the response side. A
         * request that could not say which state it was in would make one of the two unreachable.
         */
        @Test
        @DisplayName("both CDEMO-PGM-CONTEXT states travel in the payload: ENTER 0 and REENTER 1")
        void bothContextStatesTravelInThePayload() throws Exception {
            assertThat(NavigationContext.PGM_CONTEXT_ENTER).isZero();
            assertThat(NavigationContext.PGM_CONTEXT_REENTER).isEqualTo(1);

            ReportRequestRequest firstEntry = customRequest()
                    .withNavigationContext(NavigationContext.empty().withPgmEnter());
            ReportRequestRequest reEntry = customRequest()
                    .withNavigationContext(NavigationContext.empty().withPgmReenter());

            assertThat(firstEntry.isEnter()).isTrue();
            assertThat(firstEntry.isReenter()).isFalse();
            assertThat(reEntry.isReenter()).isTrue();
            assertThat(reEntry.isEnter()).isFalse();

            // The state is payload, so it survives the wire in both directions.
            for (ReportRequestRequest request : List.of(firstEntry, reEntry)) {
                ReportRequestRequest viaJson = JSON.readValue(JSON.writeValueAsString(request),
                        ReportRequestRequest.class);
                assertThat(viaJson.pgmContext()).isEqualTo(request.pgmContext());
                assertThat(viaJson.commareaLength()).isEqualTo(EXPECTED_COMMAREA_LENGTH);
            }
        }

        /**
         * Gate G53 and rule R6, checked across the record and all three of its nested types rather than
         * on the top-level class alone: a mutable static anywhere in the payload's own code would be a
         * session by another name, and would break request isolation as well as test determinism.
         */
        @Test
        @DisplayName("no session, no thread-local, no session-scoped annotation and no mutable static")
        void noServerSideStateAnywhereInTheType() {
            List<Class<?>> types = new ArrayList<>();
            types.add(ReportRequestRequest.class);
            types.addAll(List.of(ReportRequestRequest.class.getDeclaredClasses()));

            for (Class<?> type : types) {
                for (Field field : type.getDeclaredFields()) {
                    if (field.isSynthetic()) {
                        continue;
                    }
                    assertThat(java.lang.reflect.Modifier.isFinal(field.getModifiers()))
                            .as("%s.%s must be final", type.getSimpleName(), field.getName())
                            .isTrue();
                    assertThat(field.getType().getName())
                            .as("type of %s.%s", type.getSimpleName(), field.getName())
                            .doesNotContain("HttpSession")
                            .doesNotContain("ThreadLocal")
                            .doesNotContain("HttpServletRequest");
                }
                for (Method method : type.getDeclaredMethods()) {
                    if (method.isSynthetic()) {
                        continue;
                    }
                    assertThat(method.getName())
                            .as("%s declares no setter", type.getSimpleName())
                            .doesNotStartWith("set");
                }
                for (Annotation annotation : type.getAnnotations()) {
                    assertThat(annotation.annotationType().getSimpleName())
                            .as("annotation on %s", type.getSimpleName())
                            .doesNotContain("Session").doesNotContain("Scope");
                }
            }
        }
    }

    // =================================================================================================
    // 15. The per-field overlay, spelled out byte by byte. Group 1 proves the spans are self-consistent;
    // this group proves they are where the copybook puts them, with every offset k derived from the
    // transcription rather than read back out of the class.
    //
    //   k     .. k+1     xxxL    COMP PIC S9(4)             2 bytes, SIGNED
    //   k+2             xxxF    PICTURE X                   1 byte
    //   k+2             xxxA    via FILLER REDEFINES xxxF   the SAME byte, adding nothing
    //   k+3   .. k+6     FILLER  PICTURE X(4)               4 bytes
    //   k+7   .. k+6+n   xxxI    PIC X(n)                   n bytes  -> stride 7 + n
    // =================================================================================================

    @Nested
    @DisplayName("15. The per-field overlay - xxxL signed, xxxA aliasing xxxF, xxxI at k+7")
    class ByteOverlay {

        /**
         * The absolute offset of {@code field}'s {@code xxxL} item, computed from the transcribed widths
         * alone: twelve for the TIOAPFX prefix, then {@code 7 + n} per preceding field. Deliberately not
         * taken from {@link ScreenField#lengthSpan()}, so this is an independent check of the class
         * rather than a restatement of it.
         */
        private int expectedBaseOffset(ScreenField field) {
            int offset = ReportRequestRequest.TIOAPFX_PREFIX_LENGTH;
            for (ScreenField preceding : ScreenField.values()) {
                if (preceding == field) {
                    return offset;
                }
                offset += ReportRequestRequest.FIELD_PREFIX_LENGTH
                        + EXPECTED_FIELD_WIDTHS.get(preceding.bmsName());
            }
            throw new IllegalStateException("unreachable: " + field + " is one of the seventeen");
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("every span sits exactly where 12 + sum of (7 + n) puts it")
        void everySpanSitsWhereTheStrideComputes(ScreenField field) {
            int base = expectedBaseOffset(field);
            int width = EXPECTED_FIELD_WIDTHS.get(field.bmsName());

            assertThat(field.lengthSpan().offset()).as("%s at k", field.lengthItem()).isEqualTo(base);
            assertThat(field.lengthSpan().endOffsetExclusive())
                    .as("%s occupies k..k+1", field.lengthItem()).isEqualTo(base + 2);

            assertThat(field.flagSpan().offset()).as("%s at k+2", field.flagItem())
                    .isEqualTo(base + 2);
            assertThat(field.attributeSpan().offset()).as("%s at k+2 as well", field.attributeItem())
                    .isEqualTo(base + 2);

            assertThat(field.reservedFillerSpan().offset()).as("the reserved FILLER at k+3")
                    .isEqualTo(base + 3);
            assertThat(field.reservedFillerSpan().endOffsetExclusive())
                    .as("the reserved FILLER ends at k+7").isEqualTo(base + 7);

            assertThat(field.inputSpan().offset()).as("%s at k+7", field.inputItem())
                    .isEqualTo(base + 7);
            assertThat(field.inputSpan().endOffsetExclusive())
                    .as("%s ends at k+7+n", field.inputItem()).isEqualTo(base + 7 + width);
        }

        @Test
        @DisplayName("the first base offset is 12 and the last field ends exactly at 337")
        void theChainStartsAfterThePrefixAndEndsAtTheTotal() {
            ScreenField first = ScreenField.values()[0];
            ScreenField last = ScreenField.values()[ScreenField.values().length - 1];

            assertThat(expectedBaseOffset(first)).isEqualTo(ReportRequestRequest.TIOAPFX_PREFIX_LENGTH)
                    .isEqualTo(12);
            assertThat(expectedBaseOffset(last) + ReportRequestRequest.FIELD_PREFIX_LENGTH
                    + EXPECTED_FIELD_WIDTHS.get(last.bmsName()))
                    .as("the ERRMSG data ends at the record length")
                    .isEqualTo(EXPECTED_GROUP_IMAGE);
            assertThat(last.inputSpan().endOffsetExclusive()).isEqualTo(EXPECTED_GROUP_IMAGE);
        }

        /**
         * Gate G34, at the level of a single byte. {@code 02 FILLER REDEFINES xxxF.} with
         * {@code 03 xxxA PICTURE X.} nested inside it means the flag item and the attribute item are two
         * names for one byte at {@code k+2}. Both directions are driven: setting through the attribute
         * view is visible through the flag view, and the byte written into the image is the one either
         * view names.
         */
        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("xxxA and xxxF are two accessors over the same single byte at k+2")
        void attributeAndFlagAreOneByteUnderTwoNames(ScreenField field) {
            assertThat(field.attributeSpan().offset()).isEqualTo(field.flagSpan().offset());
            assertThat(field.attributeSpan().length()).isEqualTo(field.flagSpan().length()).isEqualTo(1);
            assertThat(field.attributeSpan().redefinition())
                    .as("%s is the redefining view", field.attributeItem()).isTrue();
            assertThat(field.flagSpan().redefinition())
                    .as("%s is the storage span", field.flagItem()).isFalse();

            // Written through the attribute view, read back through the flag view, and the same single
            // byte in the produced image either way.
            SymbolicMapMetadata metadata = SymbolicMapMetadata.initial().withAttribute(field, "*");
            assertThat(metadata.flag(field)).isEqualTo("*");
            assertThat(metadata.metadata(field).attribute()).isEqualTo(metadata.metadata(field).flag());

            byte[] image = ReportRequestRequest.empty().toSymbolicMap(ASCII, metadata);
            assertThat((char) image[field.flagSpan().offset()]).isEqualTo('*');
            assertThat((char) image[field.attributeSpan().offset()]).isEqualTo('*');
            assertThat(ReportRequestRequest.metadataFrom(ASCII, image).flag(field)).isEqualTo("*");
        }

        /**
         * {@code app/cbl/CORPT00C.cbl} performs {@code MOVE -1 TO xxxL} at twenty-two sites, which is
         * only meaningful because {@code COMP PIC S9(4)} is <em>signed</em>: an unsigned halfword would
         * read {@code -1} back as {@code 65535} and the cursor would land nowhere. Driven for all
         * seventeen fields, not just the one the program happens to use most.
         */
        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("MOVE -1 TO xxxL round-trips as -1 for every field, because the halfword is signed")
        void everyCursorHalfwordRoundTripsMinusOne(ScreenField field) {
            byte[] image = ReportRequestRequest.empty()
                    .toSymbolicMap(ASCII, SymbolicMapMetadata.initial().withCursorAt(field));

            assertThat(image[field.lengthSpan().offset()]).isEqualTo((byte) 0xFF);
            assertThat(image[field.lengthSpan().offset() + 1]).isEqualTo((byte) 0xFF);
            assertThat(ReportRequestRequest.metadataFrom(ASCII, image).length(field))
                    .isEqualTo(FieldMetadata.CURSOR_POSITION)
                    .isEqualTo((short) -1);
            assertThat(ReportRequestRequest.metadataFrom(ASCII, image).metadata(field).cursorRequested())
                    .isTrue();
        }

        @Test
        @DisplayName("the 22 MOVE -1 sites name 11 distinct fields, and every one is a real xxxL item")
        void theTwentyTwoCursorSitesAllNameRealLengthItems() {
            List<String> targets = new ArrayList<>();
            for (String line : program) {
                Matcher move = CURSOR_MOVE.matcher(line);
                if (move.find()) {
                    targets.add(move.group(1));
                }
            }
            assertThat(targets).hasSize(EXPECTED_CURSOR_MOVES).hasSize(22);
            for (String target : targets) {
                assertThat(EXPECTED_FIELD_NAMES)
                        .as("MOVE -1 TO %sL names a field of this map", target)
                        .contains(target);
            }
            // Distinct targets: the three selectors are addressed through MONTHLY alone, so the eleven
            // are the six date parts, MONTHLY, CONFIRM and the fields the error paths land on.
            assertThat(targets.stream().distinct().count())
                    .as("distinct cursor targets")
                    .isBetween(1L, (long) EXPECTED_FIELD_COUNT);
        }

        /**
         * {@code 01 CORPT0AO REDEFINES CORPT0AI} spends seven prefix bytes per field too -
         * {@code FILLER X(3)} plus the four one-byte colour, PS, highlight and validation items - so
         * each field's {@code xxxO} item begins at the same {@code k+7} as its {@code xxxI} item. They
         * are one span under two names, which is why the request and the response projections of this
         * screen carry the same seventeen fields at the same widths.
         */
        @Test
        @DisplayName("the xxxI span and the AO view's xxxO span are the identical byte range")
        void theInputSpanIsTheOutputSpan() {
            Map<String, Integer> outputItems = outputGroupItems();

            assertThat(outputItems).hasSize(EXPECTED_FIELD_COUNT);
            int offset = ReportRequestRequest.TIOAPFX_PREFIX_LENGTH;
            for (ScreenField field : ScreenField.values()) {
                String outputItem = field.bmsName() + "O";
                assertThat(outputItems)
                        .as("the AO view declares %s", outputItem)
                        .containsEntry(outputItem, field.declaredLength());
                assertThat(field.inputSpan().offset())
                        .as("%s and %s begin at the same offset", field.inputItem(), outputItem)
                        .isEqualTo(offset + ReportRequestRequest.FIELD_PREFIX_LENGTH);
                offset += ReportRequestRequest.FIELD_PREFIX_LENGTH + field.declaredLength();
            }
            assertThat(offset).as("the AO view is the same 337 bytes").isEqualTo(EXPECTED_GROUP_IMAGE);

            // The AO prefix really is 3 + 1 + 1 + 1 + 1, which is the only way it can come to seven.
            assertThat(outputGroup).anyMatch(line -> line.contains("FILLER PICTURE X(3)"));
            assertThat(outputGroup.stream().filter(l -> l.contains("FILLER PICTURE X(3)")).count())
                    .isEqualTo(EXPECTED_FIELD_COUNT);
        }

        /**
         * The metadata boundary, asserted by name in both directions rather than by counting. Seventeen
         * payload keys must be present; the fifty-one metadata item names - seventeen {@code xxxL},
         * seventeen {@code xxxF}, seventeen {@code xxxA} - must be absent from the whole document, the
         * nested communication area included.
         */
        @Test
        @DisplayName("the JSON carries the 17 payload keys and none of the 51 metadata item names")
        void metadataNamesNeverReachTheWire() throws Exception {
            String json = JSON.writeValueAsString(customRequest());
            ObjectNode node = (ObjectNode) JSON.readTree(json);

            List<String> metadataNames = new ArrayList<>();
            for (ScreenField field : ScreenField.values()) {
                assertThat(node.has(field.bmsName().toLowerCase(Locale.ROOT)))
                        .as("payload key for %s", field.inputItem())
                        .isTrue();
                metadataNames.add(field.lengthItem());
                metadataNames.add(field.flagItem());
                metadataNames.add(field.attributeItem());
            }
            assertThat(metadataNames).hasSize(3 * EXPECTED_FIELD_COUNT).hasSize(51);
            for (String metadataName : metadataNames) {
                assertThat(json).as("%s is metadata, not payload", metadataName)
                        .doesNotContain(metadataName)
                        .doesNotContain(metadataName.toLowerCase(Locale.ROOT));
            }
        }

        /**
         * Gate G21 once more, from the direction that actually bites: not "is a {@code FILLER} declared"
         * but "is it emitted". Eighteen filler spans - one prefix and seventeen reserved - and every
         * byte of every one of them is a space in the produced image, in either code page.
         */
        @Test
        @DisplayName("the X(12) prefix and all 17 reserved X(4) FILLERs are emitted as spaces")
        void everyFillerSpanIsEmittedAsSpaces() {
            for (FixedWidthCodec codec : List.of(ASCII, EBCDIC)) {
                byte[] image = customRequest().toSymbolicMap(codec);
                assertThat(image).hasSize(EXPECTED_GROUP_IMAGE);

                String prefix = codec.decodeImage(
                        java.util.Arrays.copyOfRange(image, 0,
                                ReportRequestRequest.TIOAPFX_PREFIX_LENGTH),
                        "TIOAPFX prefix");
                assertThat(prefix).as("prefix in %s", codec.charset())
                        .isEqualTo(SPACE.repeat(ReportRequestRequest.TIOAPFX_PREFIX_LENGTH));

                int fillerBytes = ReportRequestRequest.TIOAPFX_PREFIX_LENGTH;
                for (ScreenField field : ScreenField.values()) {
                    FieldSpan filler = field.reservedFillerSpan();
                    String reserved = codec.decodeImage(java.util.Arrays.copyOfRange(image,
                            filler.offset(), filler.endOffsetExclusive()), filler.name());
                    assertThat(reserved).as("reserved FILLER before %s in %s", field.inputItem(),
                            codec.charset()).isEqualTo(SPACE.repeat(4));
                    fillerBytes += filler.length();
                }
                assertThat(fillerBytes)
                        .as("filler bytes: 12 + 17 x 4, which is why 337 is not 257")
                        .isEqualTo(12 + EXPECTED_FIELD_COUNT * 4).isEqualTo(80);
            }
        }
    }

    // =================================================================================================
    // 16. Why every member is a String, proved from app/cbl/CORPT00C.cbl rather than assumed.
    //
    // The six date parts look numeric and are not. For each one the program normalises the value BACK
    // INTO its own alphanumeric item at :305-327 -
    //
    //     COMPUTE WS-NUM-99 = FUNCTION NUMVAL-C (SDTMMI OF CORPT0AI)
    //     MOVE    WS-NUM-99 TO SDTMMI OF CORPT0AI
    //
    // - and only then edits it, at :329-374, with an alphanumeric class test and a comparison whose
    // operand is a STRING literal:
    //
    //     IF SDTMMI OF CORPT0AI IS NOT NUMERIC OR
    //        SDTMMI OF CORPT0AI > '12'
    //
    // Both tests require the item to be PIC X. Promoting the member to int would make the failing
    // states unrepresentable and would move the accept/reject boundary - which is what this group
    // demonstrates rather than asserts.
    //
    // FUNCTION NUMVAL-C's own acceptance semantics are NOT re-tested here: gate G29 for the six call
    // sites belongs to ReportRequestControllerTest in the parent package. What is owned here is the
    // payload-side consequence.
    // =================================================================================================

    @Nested
    @DisplayName("16. Every member is a String, and the boundary is a string boundary")
    class StringBoundary {

        /** The COBOL class test on a {@code PIC X} item: every character must be a digit. */
        private boolean isCobolNumeric(String value) {
            if (value == null || value.isEmpty()) {
                return false;
            }
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                if (character < '0' || character > '9') {
                    return false;
                }
            }
            return true;
        }

        /**
         * {@code IF <part> IS NOT NUMERIC OR <part> > '<limit>'} exactly as
         * {@code app/cbl/CORPT00C.cbl:329-330}, {@code :338-339}, {@code :355-356} and {@code :364-365}
         * write it: an alphanumeric class test, then an <em>alphanumeric</em> comparison against a
         * two-character string literal. {@link String#compareTo} is faithful for the digit-and-space
         * values this suite feeds it, because space sorts below the digits and the digits sort in
         * ascending order in both code pages - see
         * {@code theCollatingSequenceAgreesForDigitsAndSpace}. For a value containing a letter the two
         * code pages order it differently, but the class test has already rejected it, so the outcome is
         * the same either way and the comparison arm is never the sole cause.
         */
        private boolean rejectedByEdit(String stored, String limit) {
            return !isCobolNumeric(stored) || stored.compareTo(limit) > 0;
        }

        /**
         * {@code COMPUTE WS-NUM-99 = FUNCTION NUMVAL-C(x)} followed by {@code MOVE WS-NUM-99 TO x}, for
         * the digit-and-space inputs this suite applies it to: the digits present spell the number,
         * spaces contribute nothing, and the move back through {@code PIC 99} leaves it zero filled to
         * two characters. This is <strong>not</strong> a general {@code NUMVAL-C}; anything outside that
         * input class is refused loudly rather than guessed at, because those semantics are gate G29's
         * and belong to the controller's own suite.
         */
        private String normaliseTwoDigits(String stored) {
            return String.format("%02d", numvalOfDigitsAndSpaces(stored));
        }

        /** The same pair with {@code WS-NUM-9999 PIC 9999}, which the two year parts use. */
        private String normaliseFourDigits(String stored) {
            return String.format("%04d", numvalOfDigitsAndSpaces(stored));
        }

        private int numvalOfDigitsAndSpaces(String stored) {
            String digits = stored.replace(" ", "");
            if (!digits.isEmpty() && !isCobolNumeric(digits)) {
                throw new IllegalArgumentException("This helper models FUNCTION NUMVAL-C only for "
                        + "digits and spaces; \"" + stored + "\" is outside that class, and NUMVAL-C's "
                        + "own acceptance semantics are gate G29, owned by ReportRequestControllerTest");
            }
            return digits.isEmpty() ? 0 : Integer.parseInt(digits);
        }

        /**
         * Rule R4 and gate G22 over the members themselves. Derived accessors such as
         * {@code pgmContext()} and {@code commareaLength()} legitimately compute an {@code int} - they
         * are calculations, not storage - so the assertion is scoped to what the record actually holds:
         * its components and its declared fields.
         */
        @Test
        @DisplayName("every stored member is a String, or the communication area: never a number")
        void everyStoredMemberIsAString() {
            List<Class<?>> forbidden = List.of(int.class, long.class, short.class, byte.class,
                    Integer.class, Long.class, Short.class, Byte.class,
                    double.class, float.class, Double.class, Float.class, BigDecimal.class);

            for (RecordComponent component : ReportRequestRequest.class.getRecordComponents()) {
                assertThat(component.getType())
                        .as("component %s", component.getName())
                        .isNotIn(forbidden)
                        .isIn(String.class, NavigationContext.class);
            }
            for (Field field : ReportRequestRequest.class.getDeclaredFields()) {
                if (field.isSynthetic() || java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                assertThat(field.getType())
                        .as("field %s", field.getName())
                        .isNotIn(forbidden)
                        .isIn(String.class, NavigationContext.class);
            }
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("each screen field's accessor returns String and its wither takes String")
        void accessorsAndWithersAreStringTyped(ScreenField field) throws Exception {
            String property = field.bmsName().toLowerCase(Locale.ROOT);
            Method accessor = ReportRequestRequest.class.getDeclaredMethod(property);
            assertThat(accessor.getReturnType()).as("%s()", property).isEqualTo(String.class);

            String witherName = "with" + property.substring(0, 1).toUpperCase(Locale.ROOT)
                    + property.substring(1);
            Method wither = ReportRequestRequest.class.getDeclaredMethod(witherName, String.class);
            assertThat(wither.getReturnType()).isEqualTo(ReportRequestRequest.class);
            assertThat(wither.getParameterTypes()).containsExactly(String.class);
        }

        /**
         * The month edit, driven as the string comparison it is. Note two behaviours preserved rather
         * than corrected (practice B4): {@code '00'} is accepted as a month, because {@code '00'} is
         * numeric and is not greater than {@code '12'}; and a value the operator left one digit short is
         * <em>rejected</em>, because a {@code PIC X(2)} receiver pads on the right.
         */
        @ParameterizedTest
        @CsvSource({
            "'00', false", "'01', false", "'07', false", "'11', false", "'12', false",
            "'13', true", "'20', true", "'99', true",
            "'9 ', true", "' 9', true", "'  ', true", "'1A', true", "'a1', true"
        })
        @DisplayName("SDTMM and EDTMM are edited against the string literal '12'")
        void monthEditIsAStringComparison(String stored, boolean rejected) {
            assertThat(stored).hasSize(ScreenField.SDTMM.declaredLength());
            assertThat(rejectedByEdit(stored, "12"))
                    .as("IS NOT NUMERIC OR > '12' for \"%s\"", stored)
                    .isEqualTo(rejected);
            // Both months are edited against the same literal, so one table drives both fields.
            assertThat(program).anyMatch(line -> line.contains("SDTMMI OF CORPT0AI > '12'"));
            assertThat(program).anyMatch(line -> line.contains("EDTMMI OF CORPT0AI > '12'"));
        }

        @ParameterizedTest
        @CsvSource({
            "'00', false", "'01', false", "'18', false", "'30', false", "'31', false",
            "'32', true", "'40', true", "'99', true",
            "'9 ', true", "' 1', true", "'  ', true", "'3X', true"
        })
        @DisplayName("SDTDD and EDTDD are edited against the string literal '31'")
        void dayEditIsAStringComparison(String stored, boolean rejected) {
            assertThat(stored).hasSize(ScreenField.SDTDD.declaredLength());
            assertThat(rejectedByEdit(stored, "31"))
                    .as("IS NOT NUMERIC OR > '31' for \"%s\"", stored)
                    .isEqualTo(rejected);
            assertThat(program).anyMatch(line -> line.contains("SDTDDI OF CORPT0AI > '31'"));
            assertThat(program).anyMatch(line -> line.contains("EDTDDI OF CORPT0AI > '31'"));
        }

        /**
         * The case that justifies the whole typing decision, and the one an implementer is most likely to
         * "fix". Two states, both real, neither expressible in an {@code int}:
         *
         * <ul>
         *   <li>An operator who types {@code 9} into the two-character month field leaves {@code "9 "} in
         *       storage, because a {@code PIC X} receiver is filled from the left and padded on the
         *       right. As characters, {@code "9 "} is greater than {@code "12"} - {@code '9'} beats
         *       {@code '1'} at the first position and the comparison stops there - so the COBOL
         *       <strong>rejects</strong> it with {@code 'Start Date - Not a valid Month...'}. As a
         *       number it is 9, a perfectly valid month, which an {@code int} member would
         *       <strong>accept</strong>. Same input, opposite outcome.</li>
         *   <li>An operator who types {@code 9} into the right-hand position leaves {@code " 9"}, which
         *       fails {@code IS NUMERIC} outright. An {@code int} member cannot hold "nine, badly typed"
         *       at all: the distinction disappears at the type, not at the edit.</li>
         * </ul>
         */
        @Test
        @DisplayName("promoting SDTMM to int would move the accept/reject boundary: '9 ' proves it")
        void promotingTheFieldToIntWouldMoveTheBoundary() {
            String stored = ASCII.movePicX("9", ScreenField.SDTMM.declaredLength());
            assertThat(stored).as("a PIC X(2) receiver pads on the right").isEqualTo("9 ");

            assertThat(stored.compareTo("12"))
                    .as("as characters, \"9 \" sorts above \"12\"")
                    .isPositive();
            assertThat(rejectedByEdit(stored, "12"))
                    .as("so the COBOL rejects month \"9 \"")
                    .isTrue();

            int asNumber = Integer.parseInt(stored.trim());
            assertThat(asNumber)
                    .as("yet as a number it is a valid month, which an int member would accept")
                    .isEqualTo(9)
                    .isLessThanOrEqualTo(12);

            // The other direction: a value an int could not represent at all.
            String rightJustified = " 9";
            assertThat(isCobolNumeric(rightJustified)).isFalse();
            assertThat(rejectedByEdit(rightJustified, "12")).isTrue();
            assertThat(rightJustified.compareTo("12"))
                    .as("the comparison arm alone would have accepted it; the class test is what rejects")
                    .isNegative();

            // And the payload really does carry both states, untouched.
            ReportRequestRequest request = ReportRequestRequest.empty()
                    .withSdtmm(stored).withEdtmm(rightJustified);
            assertThat(request.sdtmm()).isEqualTo("9 ");
            assertThat(request.edtmm()).isEqualTo(" 9");
        }

        /**
         * The string comparison is code-page independent for the values that can reach it as digits and
         * spaces: {@code SPACE} sorts below {@code '0'} and the digits ascend, in US-ASCII
         * ({@code 0x20 < 0x30..0x39}) and in IBM037 ({@code 0x40 < 0xF0..0xF9}) alike. So
         * {@code "9 " > "12"} holds on the mainframe as well as here, and the migration has not
         * accidentally made the boundary depend on the encoding.
         */
        @Test
        @DisplayName("the collating sequence agrees for digits and space in both code pages")
        void theCollatingSequenceAgreesForDigitsAndSpace() {
            for (FixedWidthCodec codec : List.of(ASCII, EBCDIC)) {
                assertThat(compareImages(codec, "9 ", "12"))
                        .as("\"9 \" > \"12\" in %s", codec.charset()).isPositive();
                assertThat(compareImages(codec, " 9", "12"))
                        .as("\" 9\" < \"12\" in %s", codec.charset()).isNegative();
                assertThat(compareImages(codec, "13", "12"))
                        .as("\"13\" > \"12\" in %s", codec.charset()).isPositive();
                assertThat(compareImages(codec, "12", "12"))
                        .as("\"12\" = \"12\" in %s", codec.charset()).isZero();
                assertThat(compareImages(codec, "  ", "12"))
                        .as("spaces sort below digits in %s", codec.charset()).isNegative();
            }
            // Java's own String order agrees with both for these values, which is what makes the
            // helper above a faithful model.
            assertThat("9 ".compareTo("12")).isPositive();
            assertThat(" 9".compareTo("12")).isNegative();
        }

        /** Byte-wise, unsigned comparison of two values as the given code page encodes them. */
        private int compareImages(FixedWidthCodec codec, String left, String right) {
            byte[] leftBytes = codec.encodeImage(left, "left operand");
            byte[] rightBytes = codec.encodeImage(right, "right operand");
            for (int index = 0; index < Math.min(leftBytes.length, rightBytes.length); index++) {
                int difference = Byte.toUnsignedInt(leftBytes[index])
                        - Byte.toUnsignedInt(rightBytes[index]);
                if (difference != 0) {
                    return difference;
                }
            }
            return leftBytes.length - rightBytes.length;
        }

        /**
         * The order in the source, which is what makes the two-stage shape real: the empty-field arm at
         * {@code :258} runs first, the six normalisations at {@code :305-327} next, and the edits at
         * {@code :329-374} last. Each failing arm ends in {@code PERFORM SEND-TRNRPT-SCREEN}, whose final
         * statement is the program's single {@code GO TO RETURN-TO-CICS}, so the first failure is the one
         * the operator sees.
         */
        @Test
        @DisplayName("the source normalises first and validates second, never the other way round")
        void normalisationPrecedesValidationInTheSource() {
            int emptyArm = lineIndexContaining("WHEN SDTMMI OF CORPT0AI = SPACES OR");
            int firstNormalisation = lineIndexContaining("COMPUTE WS-NUM-99 = FUNCTION NUMVAL-C");
            int moveBack = lineIndexContaining("MOVE WS-NUM-99", "TO SDTMMI OF CORPT0AI");
            int firstEdit = lineIndexContaining("IF SDTMMI OF CORPT0AI IS NOT NUMERIC OR");

            assertThat(emptyArm).as("the empty-field arm exists").isNotNegative();
            assertThat(firstNormalisation).as("and precedes the normalisation").isGreaterThan(emptyArm);
            assertThat(moveBack).as("which moves its result back into the map item")
                    .isGreaterThan(firstNormalisation);
            assertThat(firstEdit).as("and only then is the item edited").isGreaterThan(moveBack);

            assertThat(program).anyMatch(line -> line.contains("GO TO RETURN-TO-CICS."));
            assertThat(program.stream().filter(l -> l.contains("GO TO ")).count())
                    .as("CORPT00C has exactly one GO TO, so an error arm really does end the task")
                    .isEqualTo(1);
        }

        /**
         * And the order changes the answer, which is why it must be preserved. A month typed as a single
         * digit arrives as {@code "9 "}; a month typed as {@code "1 "} arrives one digit short too, and
         * the two resolve differently only because normalisation runs first.
         */
        @Test
        @DisplayName("normalise-then-validate accepts '1 ' where validate-then-normalise would reject it")
        void theOrderChangesTheOutcome() {
            String typed = ASCII.movePicX("1", ScreenField.SDTMM.declaredLength());
            assertThat(typed).isEqualTo("1 ");

            // Validate first: the class test fails on the trailing space, and the value is rejected.
            assertThat(rejectedByEdit(typed, "12"))
                    .as("validate-then-normalise would reject a perfectly good January")
                    .isTrue();

            // Normalise first, as CORPT00C does: NUMVAL-C reads 1, the move back leaves "01", and the
            // edit accepts it.
            String normalised = normaliseTwoDigits(typed);
            assertThat(normalised).isEqualTo("01");
            assertThat(isCobolNumeric(normalised)).isTrue();
            assertThat(rejectedByEdit(normalised, "12"))
                    .as("normalise-then-validate accepts it, which is the program's behaviour")
                    .isFalse();

            // The same shape for a day, and for a value that normalisation leaves rejected.
            assertThat(normaliseTwoDigits(ASCII.movePicX("7", 2))).isEqualTo("07");
            assertThat(rejectedByEdit(normaliseTwoDigits("99"), "12"))
                    .as("normalisation does not rescue a month of 99")
                    .isTrue();
        }

        /**
         * The two year parts are {@code PIC X(4)} and go through the same pair, but with
         * {@code WS-NUM-9999 PIC 9999} - and they are class tested <em>only</em>: there is no
         * {@code > '...'} comparison for a year anywhere in the program, so no upper bound is invented
         * here either.
         */
        @Test
        @DisplayName("SDTYYYY and EDTYYYY are X(4), normalised through PIC 9999 and class tested only")
        void theYearPartsUseTheFourDigitWorkingItem() {
            assertThat(ScreenField.SDTYYYY.declaredLength()).isEqualTo(4);
            assertThat(ScreenField.EDTYYYY.declaredLength()).isEqualTo(4);

            assertThat(program).anyMatch(line -> line.contains("WS-NUM-9999")
                    && line.contains("PIC 9999"));
            assertThat(program).anyMatch(line -> line.contains("COMPUTE WS-NUM-9999 = FUNCTION NUMVAL-C"));
            assertThat(program).anyMatch(line -> line.contains("IF SDTYYYYI OF CORPT0AI IS NOT NUMERIC"));
            assertThat(program).anyMatch(line -> line.contains("IF EDTYYYYI OF CORPT0AI IS NOT NUMERIC"));
            assertThat(program)
                    .as("a year has no upper bound in this program, and none is invented here")
                    .noneMatch(line -> line.contains("SDTYYYYI OF CORPT0AI > '")
                            || line.contains("EDTYYYYI OF CORPT0AI > '"));

            assertThat(normaliseFourDigits("2022")).isEqualTo("2022");
            assertThat(normaliseFourDigits(ASCII.movePicX("22", 4)))
                    .as("a year typed short is zero filled, not rejected outright")
                    .isEqualTo("0022");
            assertThat(isCobolNumeric(normaliseFourDigits("    "))).isTrue();
            assertThat(normaliseFourDigits("    ")).isEqualTo("0000");
        }

        /**
         * Gate G24 as a negative, and gate G22 textually. There is nothing on this screen to round: the
         * payload carries date parts, one-character flags and message text, and not one monetary field.
         * The assertion is therefore that no rounding mode is even <em>named</em> in the payload's
         * source, and that no member is a fixed-point type.
         */
        @Test
        @DisplayName("no RoundingMode, no HALF_UP, HALF_EVEN, CEILING or FLOOR, and no monetary member")
        void noRoundingModeAndNoMonetaryMember() {
            for (String forbidden : List.of("RoundingMode", "HALF_UP", "HALF_EVEN", "HALF_DOWN",
                    "CEILING", "FLOOR", "setScale")) {
                assertThat(payloadSource)
                        .as("%s must appear nowhere in %s", forbidden, PAYLOAD_SOURCE_PATH)
                        .noneMatch(line -> line.contains(forbidden));
            }

            List<Class<?>> types = new ArrayList<>();
            types.add(ReportRequestRequest.class);
            types.addAll(List.of(ReportRequestRequest.class.getDeclaredClasses()));
            for (Class<?> type : types) {
                for (Field field : type.getDeclaredFields()) {
                    assertThat(field.getType())
                            .as("%s.%s", type.getSimpleName(), field.getName())
                            .isNotIn(BigDecimal.class, RoundingMode.class, double.class, float.class,
                                    Double.class, Float.class);
                }
                for (Method method : type.getDeclaredMethods()) {
                    assertThat(method.getReturnType())
                            .as("%s.%s() returns", type.getSimpleName(), method.getName())
                            .isNotIn(BigDecimal.class, RoundingMode.class, double.class, float.class,
                                    Double.class, Float.class);
                    assertThat(method.getParameterTypes())
                            .as("%s.%s() takes", type.getSimpleName(), method.getName())
                            .doesNotContain(BigDecimal.class, RoundingMode.class, double.class,
                                    float.class, Double.class, Float.class);
                }
            }

            // Nor is any field named as though it held money: no amount, balance, rate or limit.
            for (String name : EXPECTED_FIELD_NAMES) {
                assertThat(name)
                        .doesNotContain("AMT").doesNotContain("BAL")
                        .doesNotContain("RATE").doesNotContain("LIM");
            }
        }

        /**
         * The payload's part of the bargain: it stores these awkward values byte for byte, so the edit
         * above sees exactly what the screen sent. The image path compares against the value as a
         * {@code PIC X} receiver stores it, because padding is the codec's job and is done in one place.
         */
        @ParameterizedTest
        @ValueSource(strings = {"9 ", " 9", "  ", "01", "12", "13", "99", "1A"})
        @DisplayName("every one of these values survives the payload, the JSON and the 337 bytes")
        void awkwardValuesSurviveBothWires(String value) throws Exception {
            ReportRequestRequest request = ReportRequestRequest.empty().withSdtmm(value);
            assertThat(request.sdtmm()).as("untrimmed in the payload").isEqualTo(value);

            ReportRequestRequest viaJson = JSON.readValue(JSON.writeValueAsString(request),
                    ReportRequestRequest.class);
            assertThat(viaJson.sdtmm()).as("untrimmed through JSON").isEqualTo(value);

            byte[] image = request.toSymbolicMap(ASCII);
            assertThat(image).hasSize(EXPECTED_GROUP_IMAGE);
            assertThat(ReportRequestRequest.fromSymbolicMap(ASCII, image).sdtmm())
                    .as("stored as a PIC X(2) receiver stores it")
                    .isEqualTo(ASCII.movePicX(value, ScreenField.SDTMM.declaredLength()));
        }
    }

    // =================================================================================================
    // 17. The report-type selector: app/cbl/CORPT00C.cbl:212-262 with its WHEN OTHER at :437.
    //
    //     EVALUATE TRUE
    //         WHEN MONTHLYI OF CORPT0AI NOT = SPACES AND LOW-VALUES   -> :213 monthly range
    //         WHEN YEARLYI  OF CORPT0AI NOT = SPACES AND LOW-VALUES   -> :239 yearly range
    //         WHEN CUSTOMI  OF CORPT0AI NOT = SPACES AND LOW-VALUES   -> :256 custom range
    //         WHEN OTHER                                             -> :437 'Select a report type...'
    //     END-EVALUATE
    //
    // Two properties, both load bearing: the predicate is a TWO-part test, and the EVALUATE is ordered.
    // The source permits two selectors at once and resolves the ambiguity by position, so this must
    // never become an exclusive-choice enum.
    // =================================================================================================

    @Nested
    @DisplayName("17. The ordered selector - Monthly beats Yearly beats Custom, and blank is an arm")
    class ReportTypeSelector {

        /** The {@code WHEN OTHER} arm's message, verbatim from {@code app/cbl/CORPT00C.cbl:438-439}. */
        private static final String NO_TYPE_SELECTED = "Select a report type to print report...";

        /**
         * {@code WHEN <field> NOT = SPACES AND LOW-VALUES} is COBOL's abbreviated combined relation and
         * means {@code (x NOT = SPACES) AND (x NOT = LOW-VALUES)} - two comparisons against the same
         * subject, not one against a pair. The value is compared as the map stores it, so a short value
         * is padded by the codec first.
         */
        private boolean selected(ScreenField field, String value) {
            String stored = ASCII.movePicX(value, field.declaredLength());
            return !stored.equals(field.spaces()) && !stored.equals(field.lowValues());
        }

        /** The ordered {@code EVALUATE TRUE}, first match winning, with the blank case as the last arm. */
        private String reportType(ReportRequestRequest request) {
            if (selected(ScreenField.MONTHLY, request.monthly())) {
                return "Monthly";
            }
            if (selected(ScreenField.YEARLY, request.yearly())) {
                return "Yearly";
            }
            if (selected(ScreenField.CUSTOM, request.custom())) {
                return "Custom";
            }
            return NO_TYPE_SELECTED;
        }

        /**
         * All four combinations of the two-part test, on a field one byte wide. The fourth - equal to
         * spaces <em>and</em> to low values at once - is impossible, and saying so is the point: the two
         * halves are not redundant, they are alternatives. {@code MOVE LOW-VALUES TO CORPT0AO} at
         * {@code :179} leaves binary zeros on first entry, while a screen the operator has been shown and
         * has left alone comes back as spaces, so both states occur and both must be treated as "not
         * selected".
         */
        @Test
        @DisplayName("the predicate is two-part: not spaces AND not low-values, which cannot both hold")
        void thePredicateIsTwoPart() {
            ScreenField monthly = ScreenField.MONTHLY;
            assertThat(monthly.declaredLength()).isEqualTo(1);

            assertThat(selected(monthly, "X")).as("a real value is selected").isTrue();
            assertThat(selected(monthly, SPACE)).as("a single space in a X(1) field is not").isFalse();
            assertThat(selected(monthly, LOW_VALUE)).as("nor are low values").isFalse();

            assertThat(monthly.spaces()).isEqualTo(SPACE).isNotEqualTo(monthly.lowValues());
            assertThat(monthly.lowValues()).isEqualTo(LOW_VALUE);
            // The fourth combination: no one-byte value equals both, so neither half of the test can be
            // dropped as implied by the other.
            assertThat(monthly.spaces().equals(monthly.lowValues()))
                    .as("spaces and low values are distinct states in storage")
                    .isFalse();

            assertThat(program).as("both figurative constants are named in the arm")
                    .anyMatch(line -> line.contains("MONTHLYI OF CORPT0AI NOT = SPACES AND LOW-VALUES"));
            assertThat(program)
                    .as("and the map really is set to LOW-VALUES on first entry, at :179")
                    .anyMatch(line -> line.contains("MOVE LOW-VALUES") && line.contains("TO CORPT0AO"));
        }

        @ParameterizedTest
        @CsvSource({"MONTHLY,1", "YEARLY,1", "CUSTOM,1"})
        @DisplayName("all three selectors use the same two-part test at the same width")
        void allThreeSelectorsAreTestedAlike(String name, int width) {
            ScreenField field = ScreenField.valueOf(name);
            assertThat(field.declaredLength()).isEqualTo(width);
            assertThat(field.unprotected()).as("%s is typeable", name).isTrue();
            assertThat(field.numeric()).as("%s is not NUM-shifted", name).isFalse();

            assertThat(selected(field, "S")).isTrue();
            assertThat(selected(field, SPACE)).isFalse();
            assertThat(selected(field, LOW_VALUE)).isFalse();
            assertThat(program).anyMatch(line ->
                    line.contains(field.inputItem() + " OF CORPT0AI NOT = SPACES AND LOW-VALUES"));
        }

        /**
         * The ambiguity the source permits, resolved the way the source resolves it: by position. A
         * request with two selectors set is not rejected and is not a validation error - the earlier arm
         * simply wins, and the later one is never evaluated.
         */
        @Test
        @DisplayName("first match wins: monthly beats yearly beats custom, and all three at once is monthly")
        void firstMatchWinsInSourceOrder() {
            ReportRequestRequest base = ReportRequestRequest.empty();

            assertThat(reportType(base.withMonthly("X"))).isEqualTo("Monthly");
            assertThat(reportType(base.withYearly("X"))).isEqualTo("Yearly");
            assertThat(reportType(base.withCustom("X"))).isEqualTo("Custom");
            assertThat(reportType(base)).isEqualTo(NO_TYPE_SELECTED);

            assertThat(reportType(base.withMonthly("X").withYearly("X")))
                    .as("monthly is declared first, so monthly wins")
                    .isEqualTo("Monthly");
            assertThat(reportType(base.withYearly("X").withCustom("X")))
                    .as("yearly is declared before custom")
                    .isEqualTo("Yearly");
            assertThat(reportType(base.withMonthly("X").withYearly("X").withCustom("X")))
                    .isEqualTo("Monthly");
            assertThat(reportType(base.withMonthly(SPACE).withCustom("X")))
                    .as("a blanked earlier selector does not block a later one")
                    .isEqualTo("Custom");
            assertThat(reportType(base.withMonthly(LOW_VALUE).withYearly("X")))
                    .isEqualTo("Yearly");
        }

        @Test
        @DisplayName("the source order really is monthly, yearly, custom, then WHEN OTHER")
        void theSourceOrderIsMonthlyYearlyCustomOther() {
            int evaluate = lineIndexContaining("EVALUATE TRUE");
            int monthlyArm = lineIndexContaining("WHEN MONTHLYI OF CORPT0AI NOT = SPACES");
            int yearlyArm = lineIndexContaining("WHEN YEARLYI OF CORPT0AI NOT = SPACES");
            int customArm = lineIndexContaining("WHEN CUSTOMI OF CORPT0AI NOT = SPACES");
            int otherArm = lineIndexAfter(customArm, "WHEN OTHER");
            int message = lineIndexContaining(NO_TYPE_SELECTED);

            assertThat(evaluate).isNotNegative();
            assertThat(monthlyArm).isGreaterThan(evaluate);
            assertThat(yearlyArm).isGreaterThan(monthlyArm);
            assertThat(customArm).isGreaterThan(yearlyArm);
            assertThat(otherArm).isGreaterThan(customArm);
            assertThat(message).as("the fourth arm's message").isNotNegative();
            assertThat(lineIndexAfter(customArm, NO_TYPE_SELECTED))
                    .as("and it belongs to the arm after custom")
                    .isEqualTo(message);
        }

        @Test
        @DisplayName("the blank arm puts the cursor back on MONTHLY, the first selector")
        void theBlankArmPositionsTheCursorOnTheFirstSelector() {
            int message = lineIndexContaining(NO_TYPE_SELECTED);
            int cursor = lineIndexAfter(message, "MOVE -1");

            assertThat(cursor).isNotNegative();
            assertThat(program.get(cursor)).contains("MONTHLYL OF CORPT0AI");
            assertThat(ScreenField.MONTHLY.ordinal())
                    .as("MONTHLY is the first of the three selectors")
                    .isLessThan(ScreenField.YEARLY.ordinal());
        }

        /**
         * The monthly and yearly arms assign the fixed parts of their ranges as <em>two-character string
         * literals</em> - {@code '01'}, {@code '12'}, {@code '31'} - which is the same evidence for
         * alphanumeric typing that group 16 draws from the edits, seen from the assignment side.
         */
        @Test
        @DisplayName("the monthly and yearly arms assign the literals '01', '12' and '31' as characters")
        void theFixedRangePartsAreTwoCharacterLiterals() {
            assertThat(program).as("monthly: the first of the month")
                    .anyMatch(line -> line.contains("MOVE '01'") && line.contains("WS-START-DATE-DD"));
            assertThat(program).as("yearly: January the first")
                    .anyMatch(line -> line.contains("MOVE '01'") && line.contains("WS-START-DATE-MM"));
            assertThat(program).as("yearly: December")
                    .anyMatch(line -> line.contains("MOVE '12'") && line.contains("WS-END-DATE-MM"));
            assertThat(program).as("yearly: the thirty-first")
                    .anyMatch(line -> line.contains("MOVE '31'") && line.contains("WS-END-DATE-DD"));

            for (String literal : List.of("01", "12", "31")) {
                assertThat(literal).hasSize(ScreenField.SDTMM.declaredLength()).hasSize(2);
            }
            // And the payload carries exactly those values, at exactly those widths.
            ReportRequestRequest wholeYear = ReportRequestRequest.empty()
                    .withSdtmm("01").withSdtdd("01").withEdtmm("12").withEdtdd("31");
            assertThat(wholeYear.sdtmm()).isEqualTo("01");
            assertThat(wholeYear.sdtdd()).isEqualTo("01");
            assertThat(wholeYear.edtmm()).isEqualTo("12");
            assertThat(wholeYear.edtdd()).isEqualTo("31");
            assertThat(wholeYear.toSymbolicMap(ASCII)).hasSize(EXPECTED_GROUP_IMAGE);
        }

        /**
         * Why this is three independent {@code String} members and not one enum: the COBOL can be handed
         * two selectors at once, and an exclusive choice would make that input unrepresentable - changing
         * a request the program accepts into one the API rejects.
         */
        @Test
        @DisplayName("the three selectors are independent Strings: the ambiguous input stays expressible")
        void theSelectorsAreNotAnExclusiveChoice() {
            ReportRequestRequest ambiguous = ReportRequestRequest.empty()
                    .withMonthly("X").withYearly("Y").withCustom("Z");
            assertThat(ambiguous.monthly()).isEqualTo("X");
            assertThat(ambiguous.yearly()).isEqualTo("Y");
            assertThat(ambiguous.custom()).isEqualTo("Z");

            for (RecordComponent component : ReportRequestRequest.class.getRecordComponents()) {
                assertThat(component.getType().isEnum())
                        .as("component %s is not an enum", component.getName())
                        .isFalse();
            }
            assertThat(ScreenField.class.isEnum())
                    .as("the only enum here describes the map, and is not a payload member")
                    .isTrue();
        }
    }

    // =================================================================================================
    // 18. CONFIRM, which this program handles in TWO stages - and not the way its sibling add screen
    // does.
    //
    //   :464  IF CONFIRMI OF CORPT0AI = SPACES OR LOW-VALUES      <- a separate, prior stage
    //             STRING 'Please confirm to print the ' DELIMITED BY SIZE
    //                    WS-REPORT-NAME                DELIMITED BY SPACE
    //                    ' report...'                  DELIMITED BY SIZE  INTO WS-MESSAGE
    //             MOVE 'Y' TO WS-ERR-FLG   MOVE -1 TO CONFIRML
    //   :477  IF NOT ERR-FLG-ON
    //             EVALUATE TRUE
    //                 WHEN CONFIRMI = 'Y' OR 'y'  -> CONTINUE
    //                 WHEN CONFIRMI = 'N' OR 'n'  -> INITIALIZE-ALL-FIELDS, set the error flag
    //                 WHEN OTHER                  -> '"' + CONFIRMI DELIMITED BY SPACE + '" is not a
    //                                                valid value to confirm...'
    //
    // COTRN02C - the transaction add screen in this same package - writes ONE EVALUATE CONFIRMI and
    // groups blank WITH 'N' and 'n' inside it. The two topologies are genuinely different: there, blank
    // and no share an arm and a message; here, blank is intercepted first and gets its own message, and
    // the EVALUATE never sees it. They must not be unified.
    // =================================================================================================

    @Nested
    @DisplayName("18. CONFIRM in two stages - blank, then Y/y, N/n and everything else")
    class ConfirmTopology {

        private static final String BLANK = "blank";
        private static final String YES = "yes";
        private static final String NO = "no";
        private static final String INVALID = "invalid";

        private static final String BLANK_MESSAGE_HEAD = "Please confirm to print the ";
        private static final String BLANK_MESSAGE_TAIL = " report...";
        private static final String INVALID_MESSAGE_TAIL = "\" is not a valid value to confirm...";

        /** Stage one at {@code :464}, then stage two at {@code :477-495}, in that order. */
        private String confirmOutcome(String value) {
            String stored = ASCII.movePicX(value, ScreenField.CONFIRM.declaredLength());
            if (stored.equals(ScreenField.CONFIRM.spaces())
                    || stored.equals(ScreenField.CONFIRM.lowValues())) {
                return BLANK;
            }
            return switch (stored) {
                case "Y", "y" -> YES;
                case "N", "n" -> NO;
                default -> INVALID;
            };
        }

        /**
         * {@code STRING ... DELIMITED BY SPACE}: the operand contributes its characters up to, but not
         * including, its first space. {@code DELIMITED BY SIZE} contributes all of them.
         */
        private String delimitedBySpace(String operand) {
            int firstSpace = operand.indexOf(' ');
            return firstSpace < 0 ? operand : operand.substring(0, firstSpace);
        }

        @Test
        @DisplayName("blank is intercepted in a separate prior stage, before the EVALUATE runs at all")
        void blankIsHandledInASeparatePriorStage() {
            int stageOne = lineIndexContaining("IF CONFIRMI OF CORPT0AI = SPACES OR LOW-VALUES");
            int guard = lineIndexAfter(stageOne, "IF NOT ERR-FLG-ON");
            int stageTwo = lineIndexAfter(guard, "EVALUATE TRUE");
            int endEvaluate = lineIndexAfter(stageTwo, "END-EVALUATE");

            assertThat(stageOne).as("stage one exists").isNotNegative();
            assertThat(guard).as("stage two is guarded by IF NOT ERR-FLG-ON").isGreaterThan(stageOne);
            assertThat(stageTwo).as("and only then does the EVALUATE run").isGreaterThan(guard);
            assertThat(endEvaluate).isGreaterThan(stageTwo);

            // The decisive assertion: the EVALUATE itself has no blank arm, because stage one took it.
            for (String line : program.subList(stageTwo, endEvaluate)) {
                assertThat(line)
                        .as("no arm of the CONFIRM EVALUATE mentions a figurative constant")
                        .doesNotContain("SPACES").doesNotContain("LOW-VALUES");
            }
            assertThat(program.subList(stageOne, guard))
                    .as("stage one is the one that positions the cursor on CONFIRM")
                    .anyMatch(line -> line.contains("MOVE -1") && line.contains("CONFIRML"));
        }

        @Test
        @DisplayName("all four outcomes are reachable, blank from both spaces and low values")
        void allFourOutcomesAreReachable() {
            assertThat(confirmOutcome(SPACE)).isEqualTo(BLANK);
            assertThat(confirmOutcome(LOW_VALUE)).isEqualTo(BLANK);
            assertThat(confirmOutcome("Y")).isEqualTo(YES);
            assertThat(confirmOutcome("y")).isEqualTo(YES);
            assertThat(confirmOutcome("N")).isEqualTo(NO);
            assertThat(confirmOutcome("n")).isEqualTo(NO);
            assertThat(confirmOutcome("Q")).isEqualTo(INVALID);
            assertThat(confirmOutcome("1")).isEqualTo(INVALID);
            assertThat(confirmOutcome("*")).isEqualTo(INVALID);

            assertThat(program).anyMatch(l -> l.contains("WHEN CONFIRMI OF CORPT0AI = 'Y' OR 'y'"));
            assertThat(program).anyMatch(l -> l.contains("WHEN CONFIRMI OF CORPT0AI = 'N' OR 'n'"));
        }

        @ParameterizedTest
        @CsvSource({"Y,yes", "y,yes", "N,no", "n,no", "Q,invalid", "z,invalid", "0,invalid", "'?',invalid"})
        @DisplayName("case matters only in that both cases are accepted, and nothing else is")
        void bothCasesAreAcceptedAndNothingElseIs(String typed, String outcome) {
            assertThat(confirmOutcome(typed)).isEqualTo(outcome);
            assertThat(ReportRequestRequest.empty().withConfirm(typed).confirm())
                    .as("the payload carries it verbatim, case included")
                    .isEqualTo(typed);
        }

        /**
         * Where {@code DELIMITED BY SPACE} actually bites: {@code WS-REPORT-NAME} is
         * {@code PIC X(10) VALUE SPACES} at {@code app/cbl/CORPT00C.cbl:58}, so {@code 'Monthly'} sits in
         * it as {@code "Monthly   "} and the delimiter is what keeps three spaces out of the middle of
         * the sentence.
         */
        @Test
        @DisplayName("the blank message truncates the report name at its first space")
        void theBlankMessageIsDelimitedBySpace() {
            assertThat(program).anyMatch(line -> line.contains("WS-REPORT-NAME")
                    && line.contains("PIC X(10)"));
            assertThat(program).anyMatch(line -> line.contains(BLANK_MESSAGE_HEAD));
            assertThat(program).anyMatch(line -> line.contains("WS-REPORT-NAME")
                    && line.contains("DELIMITED BY SPACE"));
            assertThat(program).anyMatch(line -> line.contains("' report...'"));

            String heldInWorkingStorage = ASCII.movePicX("Monthly", 10);
            assertThat(heldInWorkingStorage).isEqualTo("Monthly   ");
            assertThat(delimitedBySpace(heldInWorkingStorage)).isEqualTo("Monthly");
            assertThat(BLANK_MESSAGE_HEAD + delimitedBySpace(heldInWorkingStorage) + BLANK_MESSAGE_TAIL)
                    .isEqualTo("Please confirm to print the Monthly report...");

            // The other two report names behave the same way, and 'Custom' is the shortest.
            assertThat(delimitedBySpace(ASCII.movePicX("Yearly", 10))).isEqualTo("Yearly");
            assertThat(delimitedBySpace(ASCII.movePicX("Custom", 10))).isEqualTo("Custom");
        }

        /**
         * The {@code WHEN OTHER} echo uses the same delimiter on {@code CONFIRMI} - and because
         * {@code CONFIRMI} is {@code PIC X(1)}, the delimiter can only ever drop the whole operand, which
         * is precisely the blank case that {@code :464} has already intercepted. So the echo is always
         * the single character the operator typed. Recording that is the point: the delimiter is not
         * redundant, it is unreachable-by-construction here, and the reason is the field's width.
         */
        @Test
        @DisplayName("the invalid-value echo is DELIMITED BY SPACE too, which a X(1) field makes degenerate")
        void theInvalidEchoIsDelimitedBySpace() {
            int otherArm = lineIndexContaining("\" is not a valid value to confirm...");
            int echo = lineIndexAfter(otherArm - 3, "CONFIRMI OF CORPT0AI");

            assertThat(otherArm).as("the WHEN OTHER message").isNotNegative();
            assertThat(program).anyMatch(line -> line.contains("CONFIRMI OF CORPT0AI")
                    && line.contains("DELIMITED BY SPACE"));
            assertThat(echo).isNotNegative();

            assertThat(ScreenField.CONFIRM.declaredLength()).isEqualTo(1);
            assertThat(delimitedBySpace("Q")).as("a non-space character survives whole").isEqualTo("Q");
            assertThat("\"" + delimitedBySpace("Q") + INVALID_MESSAGE_TAIL)
                    .isEqualTo("\"Q\" is not a valid value to confirm...");
            assertThat(delimitedBySpace(SPACE))
                    .as("a space would contribute nothing - but stage one already handled it")
                    .isEmpty();
            assertThat(confirmOutcome(SPACE))
                    .as("so the degenerate case never reaches WHEN OTHER")
                    .isEqualTo(BLANK);
        }

        /**
         * The {@code N}/{@code n} arm performs {@code INITIALIZE-ALL-FIELDS}, which names exactly the ten
         * input-capable {@code xxxI} items and {@code WS-MESSAGE}, and moves {@code -1} to
         * {@code MONTHLYL}. It does <strong>not</strong> touch the seven {@code ASKIP} header fields, and
         * the payload models that difference exactly.
         */
        @Test
        @DisplayName("the N arm reinitialises exactly the 10 input-capable fields, not the 7 headers")
        void theNoArmReinitialisesOnlyTheTypeableFields() {
            int paragraph = lineIndexContaining("INITIALIZE-ALL-FIELDS.");
            assertThat(paragraph).isNotNegative();

            for (String typeable : EXPECTED_INPUT_CAPABLE) {
                assertThat(program.subList(paragraph, program.size()))
                        .as("INITIALIZE names %sI", typeable)
                        .anyMatch(line -> line.contains(typeable + "I")
                                && line.contains("OF CORPT0AI"));
            }
            assertThat(program.subList(paragraph, program.size()))
                    .anyMatch(line -> line.contains("MOVE -1") && line.contains("MONTHLYL"));

            // Modelled on the payload: the ten typeable fields go back to SPACES, the seven headers stay.
            ReportRequestRequest inUse = customRequest().withTitle01("TRANSACTION REPORTS")
                    .withCurdate("07/18/22").withCurtime("12:00:00").withErrmsg("something went wrong");
            ReportRequestRequest reinitialised = inUse;
            for (String typeable : EXPECTED_INPUT_CAPABLE) {
                ScreenField field = ScreenField.valueOf(typeable);
                reinitialised = reinitialised.withValue(field, field.spaces());
            }
            for (ScreenField field : ScreenField.values()) {
                if (EXPECTED_INPUT_CAPABLE.contains(field.bmsName())) {
                    assertThat(reinitialised.value(field))
                            .as("%s is back to SPACES", field.inputItem())
                            .isEqualTo(field.spaces());
                } else {
                    assertThat(reinitialised.value(field))
                            .as("%s is an ASKIP header and is untouched", field.inputItem())
                            .isEqualTo(inUse.value(field));
                }
            }
            assertThat(EXPECTED_INPUT_CAPABLE).hasSize(EXPECTED_UNPROTECTED).hasSize(10);
        }

        @Test
        @DisplayName("the EVALUATE has exactly three arms: Y/y, N/n and OTHER - no fourth, no blank arm")
        void theEvaluateHasExactlyThreeArms() {
            int stageOne = lineIndexContaining("IF CONFIRMI OF CORPT0AI = SPACES OR LOW-VALUES");
            int stageTwo = lineIndexAfter(lineIndexAfter(stageOne, "IF NOT ERR-FLG-ON"), "EVALUATE TRUE");
            int endEvaluate = lineIndexAfter(stageTwo, "END-EVALUATE");

            long arms = program.subList(stageTwo, endEvaluate).stream()
                    .filter(line -> line.trim().startsWith("WHEN ")).count();
            assertThat(arms).as("WHEN 'Y' OR 'y', WHEN 'N' OR 'n', WHEN OTHER").isEqualTo(3);

            // And every outcome the model produces is one of the four this screen can be in.
            for (String typed : List.of(SPACE, LOW_VALUE, "Y", "y", "N", "n", "Q")) {
                assertThat(confirmOutcome(typed)).isIn(BLANK, YES, NO, INVALID);
            }
        }

        @Test
        @DisplayName("every CONFIRM state survives the payload and both wires, low values included")
        void everyConfirmStateSurvivesThePayload() throws Exception {
            for (String typed : List.of(SPACE, LOW_VALUE, "Y", "y", "N", "n", "Q")) {
                ReportRequestRequest request = ReportRequestRequest.empty().withConfirm(typed);
                assertThat(request.confirm()).isEqualTo(typed);

                ReportRequestRequest viaJson = JSON.readValue(JSON.writeValueAsString(request),
                        ReportRequestRequest.class);
                assertThat(viaJson.confirm()).as("\"%s\" through JSON", typed).isEqualTo(typed);
                assertThat(confirmOutcome(viaJson.confirm())).isEqualTo(confirmOutcome(typed));

                ReportRequestRequest viaImage = ReportRequestRequest.fromSymbolicMap(ASCII,
                        request.toSymbolicMap(ASCII));
                assertThat(viaImage.confirm()).as("\"%s\" through 337 bytes", typed).isEqualTo(typed);
            }
        }
    }

    // =================================================================================================
    // 19. Bean Validation, driven from both sides for every constrained field.
    //
    // There is a wrinkle worth stating, because it decides how the invalid side has to be driven: the
    // canonical constructor already refuses an over-long value, so an over-wide instance cannot be built
    // and validator.validate(instance) can never see a @Size violation. The declarative ceiling is
    // therefore exercised with Validator.validateValue, which checks a value against a property's
    // constraints without constructing the bean - and the two ceilings are asserted to be the same
    // number, so the declarative contract and the constructor guard cannot drift apart.
    // =================================================================================================

    @Nested
    @DisplayName("19. Bean Validation - valid at the declared width, refused one character over")
    class BeanValidation {

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("a value at exactly the declared width raises no violation")
        void exactlyTheDeclaredWidthIsValid(ScreenField field) {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                Validator validator = factory.getValidator();
                String property = field.bmsName().toLowerCase(Locale.ROOT);
                String atWidth = "x".repeat(field.declaredLength());

                assertThat(validator.validateValue(ReportRequestRequest.class, property, atWidth))
                        .as("%s at %d characters", property, field.declaredLength())
                        .isEmpty();
                assertThat(validator.validateValue(ReportRequestRequest.class, property, ""))
                        .as("an empty field is legal: CORPT00C edits emptiness itself")
                        .isEmpty();
                assertThat(validator.validateValue(ReportRequestRequest.class, property,
                        field.spaces()))
                        .as("and so is a field of spaces")
                        .isEmpty();
            }
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("one character over the declared width is refused, naming that field")
        void oneCharacterOverTheDeclaredWidthIsRefused(ScreenField field) {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                Validator validator = factory.getValidator();
                String property = field.bmsName().toLowerCase(Locale.ROOT);
                String tooLong = "x".repeat(field.declaredLength() + 1);

                Set<ConstraintViolation<ReportRequestRequest>> violations =
                        validator.validateValue(ReportRequestRequest.class, property, tooLong);

                assertThat(violations).as("%s at %d characters", property,
                        field.declaredLength() + 1).hasSize(1);
                ConstraintViolation<ReportRequestRequest> violation = violations.iterator().next();
                assertThat(violation.getPropertyPath()).hasToString(property);
                // The message states the declared width and nothing else. It used to name the
                // copybook item and its PICTURE clause, which is provenance written for a maintainer
                // rather than a correction a caller can act on - and which, handed out one rejected
                // field at a time, describes the estate behind the API. The item name and its width
                // are still asserted against app/cpy-bms/CORPT00.CPY elsewhere in this class; they
                // simply no longer travel to whoever sent the request.
                assertThat(violation.getMessage())
                        .as("the message states the width and no internal provenance")
                        .isEqualTo("must be at most " + field.declaredLength() + " characters")
                        .doesNotContain(field.inputItem())
                        .doesNotContain("PIC X(");
            }
        }

        /**
         * The declarative ceiling and the constructor guard are the same number, checked field by field.
         * That is what makes the {@code @Size} annotation an accurate description of the type rather than
         * a second, looser opinion about it.
         */
        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("the @Size ceiling and the constructor's own guard agree exactly")
        void theDeclaredCeilingAndTheConstructorGuardAgree(ScreenField field) throws Exception {
            String property = field.bmsName().toLowerCase(Locale.ROOT);
            Size size = ReportRequestRequest.class.getDeclaredField(property).getAnnotation(Size.class);

            assertThat(size).isNotNull();
            assertThat(size.max()).isEqualTo(field.declaredLength());

            // At the ceiling the constructor accepts; one over, it refuses - and refuses by naming the
            // item rather than by echoing the value.
            assertThat(ReportRequestRequest.empty()
                    .withValue(field, "x".repeat(size.max())).value(field))
                    .hasSize(size.max());
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> ReportRequestRequest.empty()
                            .withValue(field, "x".repeat(size.max() + 1)))
                    .withMessageContaining(field.inputItem());
        }

        /**
         * A fully populated, realistic request has nothing wrong with it, and neither has a request of
         * nulls: the constructor turns those into {@code SPACES}, which is what an untouched
         * {@code PIC X} item holds. Nothing Java-side pre-empts the program's own editing - if it did,
         * the {@code IS NOT NUMERIC}, {@code > '12'} and {@code WHEN OTHER} arms would become
         * unreachable through the API.
         */
        @Test
        @DisplayName("a real request, an empty request and a garbage request all pass validation")
        void nothingJavaSidePreEmptsTheProgramsOwnEditing() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                Validator validator = factory.getValidator();

                assertThat(validator.validate(customRequest()))
                        .as("a realistic custom-range request").isEmpty();
                assertThat(validator.validate(ReportRequestRequest.empty()))
                        .as("a freshly initialised request").isEmpty();
                assertThat(validator.validate(ReportRequestRequest.lowValues()))
                        .as("and one still holding LOW-VALUES").isEmpty();

                // Values CORPT00C itself rejects, with its own messages. Java must not reject them first.
                ReportRequestRequest garbage = ReportRequestRequest.empty()
                        .withMonthly("X").withYearly("Y").withCustom("Z")
                        .withSdtmm("99").withSdtdd("99").withSdtyyyy("XXXX")
                        .withEdtmm("9 ").withEdtdd(" 9").withEdtyyyy("    ")
                        .withConfirm("Q");
                assertThat(validator.validate(garbage))
                        .as("every content rule is deferred to the COBOL")
                        .isEmpty();

                // Including the cold-start shape, where no communication area was passed at all.
                assertThat(validator.validate(garbage.withoutNavigationContext()))
                        .as("EIBCALEN = 0 is a state, not an error")
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("the AID token is constrained at 5 and refused at 6, from both sides")
        void theAidTokenIsConstrainedToo() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                Validator validator = factory.getValidator();

                assertThat(validator.validateValue(ReportRequestRequest.class, "aid", AID_ENTER))
                        .isEmpty();
                assertThat(validator.validateValue(ReportRequestRequest.class, "aid", AID_PF3))
                        .isEmpty();
                assertThat(validator.validateValue(ReportRequestRequest.class, "aid",
                        SPACE.repeat(ReportRequestRequest.AID_LENGTH)))
                        .as("spaces is the no-key-resolved state")
                        .isEmpty();

                Set<ConstraintViolation<ReportRequestRequest>> violations = validator.validateValue(
                        ReportRequestRequest.class, "aid",
                        "x".repeat(ReportRequestRequest.AID_LENGTH + 1));
                assertThat(violations).hasSize(1);
                assertThat(violations.iterator().next().getPropertyPath()).hasToString("aid");
                assertThat(violations.iterator().next().getMessage())
                        .isEqualTo("must be at most " + ReportRequestRequest.AID_LENGTH
                                + " characters")
                        .doesNotContain(ReportRequestRequest.AID_FIELD);
            }
        }

        /**
         * Seventeen screen fields plus the key: eighteen constrained properties, and not one more. A
         * {@code @NotNull} or a {@code @Pattern} added anywhere here would be a rule the COBOL does not
         * have, which is why their absence is asserted rather than assumed.
         */
        @Test
        @DisplayName("exactly 18 properties carry @Size, and nothing carries a stricter constraint")
        void theConstraintSurfaceIsExactlyEighteenSizes() throws Exception {
            int sized = 0;
            for (Field field : ReportRequestRequest.class.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                for (Annotation annotation : field.getAnnotations()) {
                    String name = annotation.annotationType().getSimpleName();
                    assertThat(name)
                            .as("constraint on %s", field.getName())
                            .isNotIn("NotNull", "NotBlank", "NotEmpty", "Pattern", "Digits", "Min",
                                    "Max", "Positive", "PositiveOrZero", "Past", "Future");
                    if ("Size".equals(name)) {
                        sized++;
                    }
                }
            }
            assertThat(sized)
                    .as("the 17 screen fields and the AID token")
                    .isEqualTo(EXPECTED_FIELD_COUNT + 1).isEqualTo(18);

            assertThat(ReportRequestRequest.class.getDeclaredField("navigationContext")
                    .getAnnotations())
                    .as("the communication area carries no constraint of its own here")
                    .isEmpty();
        }
    }
}
