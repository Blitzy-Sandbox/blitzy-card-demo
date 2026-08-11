package com.vsergeychik.carddemo.transaction.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldHighlight;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldValidationState;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.transaction.dto.TransactionViewResponse.Ct02Info;
import com.vsergeychik.carddemo.transaction.dto.TransactionViewResponse.FieldMetadata;
import com.vsergeychik.carddemo.transaction.dto.TransactionViewResponse.FieldSpans;
import com.vsergeychik.carddemo.transaction.dto.TransactionViewResponse.ScreenField;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
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

/**
 * Parity tests for {@link TransactionViewResponse}, the {@code xxxO} projection of
 * {@code 01 COTRN2AO REDEFINES COTRN2AI} in {@code app/cpy-bms/COTRN02.CPY}.
 *
 * <p>Every expected value here is transcribed from the <strong>copybook, the mapset and the
 * program</strong>, never read back from the class under test, so this suite compares the
 * implementation against its source rather than against itself. A change to either side that moved a
 * width, renamed an item or altered a literal fails here.
 *
 * <p>The suite is organised around the properties that determine the implementation, so a failure
 * names a translation decision rather than merely a value:
 *
 * <ol>
 *   <li>The 21 declared widths sum to <strong>396</strong> and the group image is
 *       <strong>555</strong> bytes.</li>
 *   <li>The field set is the <strong>add</strong> screen's - risk <strong>R-B</strong> - so
 *       {@code CONFIRMO} is present and {@code TRNIDINO} and {@code TRNIDO} are absent.</li>
 *   <li>{@code PIC X} moves pad and truncate on the right and never trim, and survive a JSON round
 *       trip untrimmed.</li>
 *   <li>The {@code CSSETATY} highlight is reachable in {@code REENTER} and unreachable in
 *       {@code ENTER} - gate <strong>G38</strong>.</li>
 *   <li>Card, account and merchant identifiers are carried unmasked - gate <strong>G41</strong>.</li>
 *   <li>The 58-byte cursor's two {@code 88}-levels are reachable in both states - gate
 *       <strong>G50</strong>.</li>
 * </ol>
 *
 * <h2>Risk R-B: this type is named "View" and renders the <em>Add</em> map</h2>
 * The build prompt mandates the name {@code TransactionView...} for the type paired with
 * {@code COTRN02C}. Three independent sources show that program <strong>adds</strong> a transaction
 * rather than viewing one:
 *
 * <ol>
 *   <li>{@code app/cbl/COTRN02C.cbl:5} - {@code Function : Add a new Transaction to TRANSACT file}.</li>
 *   <li>{@code README.md:224} - {@code | CT02 | COTRN02 | COTRN02C | Transaction Add |}.</li>
 *   <li>The map's own shape in {@code app/bms/COTRN02.bms} - {@code ACTIDIN 11},
 *       {@code CARDNIN 16} and {@code CONFIRM 1} are present, {@code TRNIDIN} and {@code TRNID} are
 *       absent, and <strong>14 of the 21</strong> labelled fields are {@code UNPROT}. A view screen
 *       does not need fourteen input fields or a confirmation prompt.</li>
 * </ol>
 *
 * <p>Rule <strong>R1</strong> governs the outcome: <em>the name comes from the prompt, the behaviour
 * comes from the source</em>. So the class name is kept verbatim, the field set is taken from
 * {@code app/cpy-bms/COTRN02.CPY}, and the divergence is <strong>documented here rather than
 * corrected</strong> - practice <strong>B4</strong> forbids silently fixing a conflict. No
 * {@code TRNIDINO} and no {@code TRNIDO} are added; {@code TransactionAddResponse} in this same
 * package is the inverse case, being named "Add" while rendering {@code COTRN01}, the view map.
 * {@link SourceOracle} asserts all three pieces of evidence directly from the sources, so the
 * divergence is a tested fact and not a comment that could rot.
 *
 * <h2>The sources this suite treats as the oracle</h2>
 * Every width, name, literal and count below is read from, or transcribed from, these files. Not one
 * of them is written to - practice <strong>B3</strong>, gate <strong>G5</strong>:
 *
 * <ul>
 *   <li>{@code app/cpy-bms/COTRN02.CPY} - {@code 01 COTRN2AI.} at line 17 and
 *       {@code 01 COTRN2AO REDEFINES COTRN2AI.} at <strong>line 145</strong>; the 21 {@code xxxI} and
 *       21 {@code xxxO} items and their widths.</li>
 *   <li>{@code app/bms/COTRN02.bms} - {@code DFHMSD} mapset {@code COTRN02}, {@code DFHMDI} map
 *       {@code COTRN2A} at {@code SIZE=(24,80)}, and 61 {@code DFHMDF} definitions of which
 *       {@code ERRMSG} at <strong>line 293</strong> declares
 *       {@code ATTRB=(ASKIP,BRT,FSET), COLOR=RED, LENGTH=78, POS=(23,1)}.</li>
 *   <li>{@code app/cbl/COTRN02C.cbl} - {@code :5} the function header, {@code :53}
 *       {@code WS-TRAN-AMT PIC +99999999.99}, {@code :72-80} the 58-byte {@code CDEMO-CT02-INFO}
 *       extension, and {@code :509} the single {@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM)}.</li>
 *   <li>{@code app/cpy/CSSETATY.cpy} - the highlight rule, whose four outcomes {@link HighlightMatrix}
 *       drives across all 14 input fields.</li>
 *   <li>{@code app/cpy/COCOM01Y.cpy}, {@code COTTL01Y.cpy}, {@code CSDAT01Y.cpy},
 *       {@code CSMSG01Y.cpy} - the 160-byte commarea, the {@code X(40)} titles, the date and time
 *       header and the {@code X(50)} messages.</li>
 *   <li>{@code app/cpy/CVTRA05Y.cpy} - the 350-byte {@code TRAN-RECORD} whose fields are the sending
 *       operands of every cross-width {@code MOVE} into this map.</li>
 *   <li>{@code app/csd/CARDDEMO.CSD} - {@code MAPSET(COTRN02)} at line 153,
 *       {@code PROGRAM(COTRN02C)} at line 271 and {@code TRANSACTION(CT02)} at line 439.</li>
 *   <li>{@code README.md:224} - the transaction inventory row quoted above.</li>
 * </ul>
 *
 * <h2>Counts reconciled, so an absence reads as a finding</h2>
 * <ul>
 *   <li>{@code app/bms/COTRN02.bms} declares <strong>61</strong> {@code DFHMDF} fields, of which
 *       <strong>21</strong> carry a name and <strong>40</strong> do not. The 40 unnamed ones are the
 *       screen's static captions and separators - {@code 'Tran:'}, {@code 'Account Number :'},
 *       {@code '(Y/N)'} and the like. They have no symbolic-map item, so they can carry no payload
 *       field, and gate <strong>G9</strong> is satisfied by the 21 named ones alone.</li>
 *   <li>The group image is <strong>555</strong> bytes here against <strong>575</strong> for
 *       {@code COTRN01}, even though both maps declare 21 fields: the two field <em>sets</em> differ,
 *       so the payload totals differ - 396 here against 416 there. Equal field counts are not equal
 *       geometry, which is why the total is asserted rather than assumed.</li>
 *   <li>Gate <strong>G33</strong> - {@code OCCURS} indexing - has <strong>no subject on this map</strong>.
 *       {@code COTRN02} declares no table: the add screen shows one transaction being composed, not a
 *       page of rows, so there is no one-based-to-zero-based conversion to verify.
 *       {@link SourceOracle#noOccursTableExistsOnThisMap()} asserts the absence, so this reads as a
 *       finding rather than an omission.</li>
 * </ul>
 *
 * <h2>The rules position, and what governs instead</h2>
 * {@code review_rules} returns exactly one line, "No user rules provided", and that single line is
 * the whole document - so <strong>no user rule governs this file</strong>. Per the Agent Action Plan's
 * section 0.10 that absence is not permission to lower the bar, so this suite is held to the plan's
 * own binds: <strong>R1</strong> (name from the prompt, behaviour from the source),
 * <strong>R5</strong> (fixed width is the wire format), <strong>R6</strong> and gate
 * <strong>G37</strong> (statelessness), <strong>R4</strong> and gate <strong>G22</strong> (never
 * {@code double} or {@code float}), <strong>R2</strong> and gate <strong>G24</strong> (truncation, not
 * rounding), <strong>B3</strong> (reference inputs immutable), <strong>B4</strong> (no silent scope
 * creep), <strong>B7</strong> (deterministic and non-interactive - hence the fixed {@link Clock}),
 * <strong>B8</strong> and gate <strong>G52</strong> (explicit over implicit, no wildcard imports),
 * <strong>B9</strong> and gate <strong>G53</strong> (no static mutable state) and
 * <strong>B11</strong> (hand-written, reviewable codecs rather than an opaque parser).
 * {@link Negatives} turns each of those into an assertion.
 *
 * <h2>Scope: what this suite deliberately does not do</h2>
 * It is <strong>self-contained</strong>. It follows the structural shape established by
 * {@code TransactionListResponseTest} but depends on nothing in it: there is no shared base class and
 * none is introduced. It exercises the payload record only - there is no {@code MockMvc}, no
 * {@code @SpringBootTest} and no {@code @WebMvcTest}, because {@code TransactionViewControllerTest}
 * owns the controller behaviour ({@code STARTBR}/{@code READPREV}/{@code ENDBR}/{@code WRITE}, the two
 * {@code CSUTLDTC} calls and the {@code FUNCTION NUMVAL} acceptance). It touches no repository, no
 * {@code JobLauncher} and no datasource, imports nothing from
 * {@code com.vsergeychik.carddemo.parity} and reads nothing under
 * {@code src/test/resources/parity}, and does not reach for {@code card.dto.CardScreenState} -
 * {@code COTRN02C} does not copy {@code CVCRD01Y}.
 */
@DisplayName("TransactionViewResponse - COTRN02 COTRN2AO output projection (CT02 / COTRN02C)")
class TransactionViewResponseTest {

    /** The code page of the authoritative fixtures under {@code app/data/ASCII}. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /**
     * The 21 labelled fields with the widths transcribed from {@code app/cpy-bms/COTRN02.CPY} lines
     * 152 to 272, in declaration order. Independently equal to the {@code DFHMDF LENGTH=} values in
     * {@code app/bms/COTRN02.bms}.
     */
    private static Map<String, Integer> copybookWidths() {
        Map<String, Integer> widths = new LinkedHashMap<>();
        widths.put("TRNNAMEO", 4);
        widths.put("TITLE01O", 40);
        widths.put("CURDATEO", 8);
        widths.put("PGMNAMEO", 8);
        widths.put("TITLE02O", 40);
        widths.put("CURTIMEO", 8);
        widths.put("ACTIDINO", 11);
        widths.put("CARDNINO", 16);
        widths.put("TTYPCDO", 2);
        widths.put("TCATCDO", 4);
        widths.put("TRNSRCO", 10);
        widths.put("TDESCO", 60);
        widths.put("TRNAMTO", 12);
        widths.put("TORIGDTO", 10);
        widths.put("TPROCDTO", 10);
        widths.put("MIDO", 9);
        widths.put("MNAMEO", 30);
        widths.put("MCITYO", 25);
        widths.put("MZIPO", 10);
        widths.put("CONFIRMO", 1);
        widths.put("ERRMSGO", 78);
        return widths;
    }

    /** A response with every payload item set to a distinguishable value at its declared width. */
    private static TransactionViewResponse populated() {
        TransactionViewResponse response = new TransactionViewResponse();
        for (ScreenField field : ScreenField.values()) {
            response.setOutputItem(field, filled(field));
        }
        response.setNextProgram("COMEN01C");
        response.setNavigationContext(NavigationContext.empty().withToProgram("COMEN01C"));
        Ct02Info cursor = new Ct02Info();
        cursor.setTrnidFirst("0000000000000001");
        cursor.setTrnidLast("0000000000000010");
        cursor.setPageNum(3);
        cursor.setNextPageYes();
        cursor.setTrnSelFlg("S");
        cursor.setTrnSelected("0000000000000007");
        response.setCt02Info(cursor);
        for (ScreenField field : ScreenField.values()) {
            response.getMetadata(field).setColour(BmsAttributes.DFHGREEN);
            response.getMetadata(field).setProgrammedSymbols((byte) 'p');
            response.getMetadata(field).setHighlight(BmsAttributes.DFHBLINK);
            response.getMetadata(field).setValidation((byte) 'v');
        }
        return response;
    }

    /** A repeatable value of exactly the field's declared width, distinct per field. */
    private static String filled(ScreenField field) {
        String stem = field.label() + "-";
        StringBuilder text = new StringBuilder(field.width());
        while (text.length() < field.width()) {
            text.append(stem);
        }
        return text.substring(0, field.width());
    }

    /**
     * The EBCDIC code page of the datasets under {@code app/data/EBCDIC}. Named explicitly so no
     * assertion here can depend on the platform default (practice <strong>B8</strong>).
     */
    private static final Charset EBCDIC = Charset.forName("IBM037");

    // =============================================================================================
    // Paths to the read-only sources. Every one of these is READ and none is written (practice B3,
    // gate G5). Surefire runs with the Maven module directory - app/java - as its working directory,
    // so ".." reaches app/ and the sources sit one level above this module.
    // =============================================================================================

    /** {@code app/cpy-bms/COTRN02.CPY} - the authoritative symbolic map, {@code AI} and {@code AO}. */
    private static final Path COPYBOOK = Paths.get("..", "cpy-bms", "COTRN02.CPY");

    /** {@code app/bms/COTRN02.bms} - the authoritative {@code DFHMDF} field definitions. */
    private static final Path MAPSET = Paths.get("..", "bms", "COTRN02.bms");

    /** {@code app/cbl/COTRN02C.cbl} - the authoritative behaviour, 783 lines. */
    private static final Path PROGRAM = Paths.get("..", "cbl", "COTRN02C.cbl");

    /** {@code app/cpy/CVTRA05Y.cpy} - the 350-byte {@code TRAN-RECORD}, the sending operands. */
    private static final Path TRAN_COPYBOOK = Paths.get("..", "cpy", "CVTRA05Y.cpy");

    /** {@code app/csd/CARDDEMO.CSD} - the transaction, program and mapset definitions. */
    private static final Path CSD = Paths.get("..", "csd", "CARDDEMO.CSD");

    /** {@code README.md} - the transaction inventory, two levels above this module. */
    private static final Path README = Paths.get("..", "..", "README.md");

    /** The class under test, for the source-level checks no reflection can express. */
    private static final Path SOURCE = Paths.get("src", "main", "java", "com", "vsergeychik",
            "carddemo", "transaction", "dto", "TransactionViewResponse.java");

    /** This suite's own source, so gate <strong>G52</strong> is asserted of the test too. */
    private static final Path TEST_SOURCE = Paths.get("src", "test", "java", "com", "vsergeychik",
            "carddemo", "transaction", "dto", "TransactionViewResponseTest.java");

    // =============================================================================================
    // Determinism (practice B7). DateHeader never calls now() of its own; it takes a Clock. Every
    // header assertion in this suite therefore states its instant AND its zone outright, so the same
    // run on a host in any time zone renders the same eight bytes into CURDATEO and CURTIMEO.
    // =============================================================================================

    /** The zone the fixed clock is read in - stated, never inherited from the platform. */
    private static final ZoneId FIXED_ZONE = ZoneId.of("UTC");

    /** {@code 2022-07-18T04:05:06Z}: the release date carried by the sources' own version footer. */
    private static final Instant FIXED_INSTANT = Instant.parse("2022-07-18T04:05:06Z");

    /** A clock that cannot move, so a header rendered twice is byte-identical twice. */
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, FIXED_ZONE);

    /**
     * The 14 input-capable fields, in copybook order - the {@code UNPROT} set of
     * {@code app/bms/COTRN02.bms} and therefore exactly the fields {@code app/cpy/CSSETATY.cpy} can
     * highlight. Transcribed, then cross-checked against the mapset by
     * {@link SourceOracle#exactly14NamedFieldsAreUnprot()}.
     */
    private static final List<ScreenField> INPUT_FIELDS = List.of(ScreenField.ACTIDIN,
            ScreenField.CARDNIN, ScreenField.TTYPCD, ScreenField.TCATCD, ScreenField.TRNSRC,
            ScreenField.TDESC, ScreenField.TRNAMT, ScreenField.TORIGDT, ScreenField.TPROCDT,
            ScreenField.MID, ScreenField.MNAME, ScreenField.MCITY, ScreenField.MZIP,
            ScreenField.CONFIRM);

    /**
     * The seven output-only fields: the six header items and the error line. Not {@code UNPROT}, so
     * the program never validates them and {@code CSSETATY} is never invoked for them.
     */
    private static final List<ScreenField> OUTPUT_ONLY_FIELDS = List.of(ScreenField.TRNNAME,
            ScreenField.TITLE01, ScreenField.CURDATE, ScreenField.PGMNAME, ScreenField.TITLE02,
            ScreenField.CURTIME, ScreenField.ERRMSG);

    // =============================================================================================
    // Helpers that read the sources, so the sources - not this file's opinion of them - are the
    // oracle. Every read names US-ASCII: all seven files were verified to hold none but ASCII bytes,
    // and naming the code page is the point (practice B8).
    // =============================================================================================

    /**
     * Reads a source file as lines.
     *
     * @param path the file to read; never written
     * @return its lines, in order
     * @throws IOException if the file cannot be read
     */
    private static List<String> lines(Path path) throws IOException {
        return Files.readAllLines(path, ASCII);
    }

    /**
     * Returns one 1-based line of a source file, so a citation such as {@code COTRN02C.cbl:509} can be
     * asserted at exactly the line the documentation names.
     *
     * @param path   the file to read
     * @param number the 1-based line number
     * @return that line, trailing spaces retained
     * @throws IOException if the file cannot be read
     */
    private static String line(Path path, int number) throws IOException {
        List<String> all = lines(path);
        assertThat(all.size()).as("%s has at least %d lines", path, number)
                .isGreaterThanOrEqualTo(number);
        return all.get(number - 1);
    }

    /**
     * Extracts the {@code 02 xxx<suffix> PIC X(n)} items of one {@code 01} group of
     * {@code app/cpy-bms/COTRN02.CPY}, in declaration order.
     *
     * <p>Only {@code PIC X(n)} items are collected, which is precisely the payload set: the
     * {@code xxxL} length items are {@code COMP PIC S9(4)} and every other item is
     * {@code PICTURE X}, so neither can be mistaken for a payload item.
     *
     * @param groupHeader the group name, {@code COTRN2AI} or {@code COTRN2AO}
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
        for (String text : lines(COPYBOOK)) {
            String trimmed = text.strip();
            if (trimmed.startsWith("01 ")) {
                inGroup = trimmed.contains(groupHeader);
                continue;
            }
            if (!inGroup) {
                continue;
            }
            Matcher matched = item.matcher(text);
            if (matched.find()) {
                items.put(matched.group(1), Integer.parseInt(matched.group(2)));
            }
        }
        return items;
    }

    /**
     * Joins {@code app/bms/COTRN02.bms} into whole BMS statements, folding each continuation line -
     * the ones ending in {@code '-'} - into the statement it continues.
     *
     * <p>Necessary because every {@code DFHMDF} in this mapset spans three to five physical lines, so
     * a per-line scan can see a field's {@code ATTRB} without ever seeing its {@code LENGTH}. The
     * continuation's leading spaces are preserved, which keeps adjacent operands from being jammed
     * into one token.
     *
     * @return one string per BMS statement, in file order
     * @throws IOException if the mapset cannot be read
     */
    private static List<String> mapsetStatements() throws IOException {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String raw : lines(MAPSET)) {
            String text = raw.stripTrailing();
            if (text.isEmpty() || text.startsWith("*")) {
                continue;
            }
            boolean continued = text.endsWith("-");
            current.append(continued
                    ? text.substring(0, text.length() - 1).stripTrailing()
                    : text);
            if (!continued) {
                statements.add(current.toString());
                current.setLength(0);
            }
        }
        if (current.length() > 0) {
            statements.add(current.toString());
        }
        return statements;
    }

    /**
     * The name-labelled {@code DFHMDF} statements of the mapset, keyed by label in file order.
     *
     * @return label to whole statement text
     * @throws IOException if the mapset cannot be read
     */
    private static Map<String, String> namedFieldStatements() throws IOException {
        Pattern named = Pattern.compile("^([A-Z0-9]+)\\s+DFHMDF\\b");
        Map<String, String> fields = new LinkedHashMap<>();
        for (String statement : mapsetStatements()) {
            Matcher matched = named.matcher(statement);
            if (matched.find()) {
                fields.put(matched.group(1), statement);
            }
        }
        return fields;
    }

    /**
     * Reads one integer operand out of a BMS statement, for example {@code LENGTH=78}.
     *
     * @param statement the whole statement
     * @param operand   the operand keyword
     * @return its integer value
     */
    private static int bmsInt(String statement, String operand) {
        Matcher matched = Pattern.compile(operand + "=(\\d+)").matcher(statement);
        assertThat(matched.find()).as("%s= in %s", operand, statement).isTrue();
        return Integer.parseInt(matched.group(1));
    }

    /**
     * Counts the occurrences of a literal in a file, so a claim such as "35 {@code MOVE -1} sites" is
     * measured rather than remembered.
     *
     * @param path    the file to read
     * @param literal the literal to count
     * @return the number of lines containing {@code literal}
     * @throws IOException if the file cannot be read
     */
    private static long countLinesContaining(Path path, String literal) throws IOException {
        return lines(path).stream().filter(text -> text.contains(literal)).count();
    }

    /**
     * Strips block and line comments from Java source, so a Javadoc paragraph explaining what is
     * absent from the code cannot be mistaken for the absent thing itself.
     *
     * @param source the Java source text
     * @return the same text with comments removed
     */
    private static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        boolean inBlock = false;
        for (String text : source.lines().toList()) {
            String working = text;
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
     * The name of the generated accessor for one field's payload item, for example
     * {@code getTrnnameo} for {@code TRNNAME}. Used by {@link Negatives} to prove every one of the 21
     * payload accessors returns a {@link String} and never a floating-point type.
     *
     * @param field the screen field
     * @return the getter's name
     */
    private static String payloadGetterName(ScreenField field) {
        String label = field.label();
        return "get" + label.charAt(0) + label.substring(1).toLowerCase(Locale.ROOT) + "o";
    }

    /**
     * Builds the {@code COTRN2AI} view over the same 555 bytes the {@code AO} view occupies.
     *
     * <p>This is the other half of the {@code REDEFINES} pair, and it is declared <em>here</em> rather
     * than taken from the class under test on purpose: {@link RecordLayout} verifies in its own
     * constructor that a layout's storage spans sum to the declared length with no gap and no overlap,
     * so the fact that an independently written {@code AI} view - {@code xxxL} at two bytes,
     * {@code xxxF} at one, {@code FILLER X(4)}, then {@code xxxI} - also lands on exactly 555 bytes is
     * itself the proof that the two views alias byte for byte (gate <strong>G34</strong>).
     *
     * <p>{@code xxxL} is declared as a two-byte span rather than a numeric picture because
     * {@code COMP PIC S9(4)} is binary, not zoned: it is read and written here as raw bytes, which is
     * what lets {@code MOVE -1} round trip as {@code -1}.
     *
     * @return the {@code AI} layout, 85 spans summing to 555
     */
    private static RecordLayout inboundLayout() {
        List<FieldSpan> spans = new ArrayList<>(1 + ScreenField.values().length * 4);
        int offset = 0;
        spans.add(FieldSpan.filler(offset, TransactionViewResponse.TIOAPFX_LENGTH));
        offset += TransactionViewResponse.TIOAPFX_LENGTH;
        for (ScreenField field : ScreenField.values()) {
            spans.add(FieldSpan.alphanumeric(field.label() + "L", offset, LENGTH_ITEM_LENGTH));
            offset += LENGTH_ITEM_LENGTH;
            spans.add(FieldSpan.alphanumeric(field.label() + "F", offset,
                    TransactionViewResponse.ATTRIBUTE_ITEM_LENGTH));
            offset += TransactionViewResponse.ATTRIBUTE_ITEM_LENGTH;
            spans.add(FieldSpan.filler(offset, TransactionViewResponse.ATTRIBUTE_ITEM_COUNT));
            offset += TransactionViewResponse.ATTRIBUTE_ITEM_COUNT;
            spans.add(FieldSpan.alphanumeric(field.label() + "I", offset, field.width()));
            offset += field.width();
        }
        return RecordLayout.of(offset, spans.toArray(new FieldSpan[0]));
    }

    /**
     * The width of one {@code xxxL} item: {@code COMP PIC S9(4)} occupies two bytes of binary storage,
     * which is why the per-field {@code AI} prefix is {@code 2 + 1 + 4} and the {@code AO} prefix is
     * {@code 3 + 1 + 1 + 1 + 1} - both seven.
     */
    private static final int LENGTH_ITEM_LENGTH = 2;

    /**
     * Renders a signed cursor length the way {@code COMP PIC S9(4)} stores it: two bytes, big-endian,
     * two's complement. {@code MOVE -1 TO <field>L} is what {@code COTRN02C} does at all 35 of its
     * validation-failure sites to put the cursor on the offending field.
     *
     * @param value the value to store, for example {@code -1}
     * @return two bytes
     */
    private static byte[] comp2(int value) {
        return new byte[] {(byte) ((value >> 8) & 0xFF), (byte) (value & 0xFF)};
    }

    /**
     * Reads back what {@link #comp2(int)} wrote.
     *
     * @param image two bytes, big-endian, two's complement
     * @return the signed value
     */
    private static int fromComp2(byte[] image) {
        return (short) (((image[0] & 0xFF) << 8) | (image[1] & 0xFF));
    }

    /**
     * The offsets at which two group images differ, so a claim that a write "touched only these bytes"
     * can be asserted exactly rather than approximated by a spot check.
     *
     * @param before the image before the write
     * @param after  the image after it
     * @return the differing offsets, ascending
     */
    private static List<Integer> differingOffsets(byte[] before, byte[] after) {
        assertThat(after).as("a group image never changes length").hasSameSizeAs(before);
        List<Integer> offsets = new ArrayList<>();
        for (int index = 0; index < before.length; index++) {
            if (before[index] != after[index]) {
                offsets.add(index);
            }
        }
        return offsets;
    }

    @Nested
    @DisplayName("Group geometry - 21 fields, 396 payload bytes, a 555-byte image")
    class Geometry {

        @Test
        @DisplayName("the map declares exactly 21 name-labelled fields")
        void fieldCountIs21() {
            assertThat(ScreenField.values()).hasSize(21);
            assertThat(TransactionViewResponse.FIELD_COUNT).isEqualTo(21);
        }

        @Test
        @DisplayName("the 21 declared widths equal the copybook's, name for name and in order")
        void widthsMatchCopybook() {
            Map<String, Integer> expected = copybookWidths();
            Map<String, Integer> actual = new LinkedHashMap<>();
            for (ScreenField field : ScreenField.values()) {
                actual.put(field.outputItemName(), field.width());
            }
            assertThat(actual).containsExactlyEntriesOf(expected);
        }

        @Test
        @DisplayName("ACTIDINO is 11 and CARDNINO is 16 - the pair most easily transposed")
        void accountIs11AndCardIs16() {
            assertThat(TransactionViewResponse.ACTIDINO_LENGTH).isEqualTo(11);
            assertThat(TransactionViewResponse.CARDNINO_LENGTH).isEqualTo(16);
        }

        @Test
        @DisplayName("4+40+8+8+40+8+11+16+2+4+10+60+12+10+10+9+30+25+10+1+78 = 396")
        void payloadWidthsSumTo396() {
            int sum = copybookWidths().values().stream().mapToInt(Integer::intValue).sum();
            assertThat(sum).isEqualTo(396);
            assertThat(TransactionViewResponse.PAYLOAD_LENGTH).isEqualTo(396);
        }

        @Test
        @DisplayName("the AO prefix is 7 bytes per field: FILLER X(3) plus C, P, H and V")
        void perFieldPrefixIsSeven() {
            assertThat(TransactionViewResponse.ATTRIBUTE_PREFIX_FILLER_LENGTH).isEqualTo(3);
            assertThat(TransactionViewResponse.ATTRIBUTE_ITEM_COUNT).isEqualTo(4);
            assertThat(TransactionViewResponse.ATTRIBUTE_ITEM_LENGTH).isEqualTo(1);
            assertThat(TransactionViewResponse.PER_FIELD_PREFIX_LENGTH).isEqualTo(7);
        }

        @Test
        @DisplayName("12 + 21*7 + 396 = 555, and the layout is declared at that length")
        void groupImageIs555() {
            assertThat(TransactionViewResponse.TIOAPFX_LENGTH).isEqualTo(12);
            assertThat(TransactionViewResponse.SYMBOLIC_MAP_LENGTH).isEqualTo(555);
            assertThat(12 + 21 * 7 + 396).isEqualTo(555);
            assertThat(TransactionViewResponse.LAYOUT.recordLength()).isEqualTo(555);
        }

        @Test
        @DisplayName("the layout declares 127 spans: one leading FILLER plus six per field")
        void layoutDeclares127Spans() {
            assertThat(TransactionViewResponse.LAYOUT.spans()).hasSize(1 + 21 * 6);
        }

        @Test
        @DisplayName("the storage spans sum to the record length, so no FILLER was dropped")
        void storageSpansSumToRecordLength() {
            int sum = TransactionViewResponse.LAYOUT.storageSpans().stream()
                    .mapToInt(span -> span.length())
                    .sum();
            assertThat(sum).isEqualTo(555);
        }

        @Test
        @DisplayName("the 22 FILLER spans are present - the leading X(12) and one X(3) per field")
        void fillerSpansArePresent() {
            long fillers = TransactionViewResponse.LAYOUT.spans().stream()
                    .filter(span -> span.kind().filler())
                    .count();
            assertThat(fillers).isEqualTo(22);
            assertThat(TransactionViewResponse.LAYOUT.spans().get(0).length()).isEqualTo(12);
            assertThat(TransactionViewResponse.LAYOUT.spans().get(0).offset()).isZero();
        }

        @Test
        @DisplayName("the payload of each field begins 7 bytes after its own prefix start")
        void offsetsFollowTheSevenByteStride() {
            int offset = 12;
            for (ScreenField field : ScreenField.values()) {
                FieldSpans spans = TransactionViewResponse.FIELD_SPANS.get(field);
                assertThat(spans.colour().offset()).as("%sC", field.label()).isEqualTo(offset + 3);
                assertThat(spans.ps().offset()).as("%sP", field.label()).isEqualTo(offset + 4);
                assertThat(spans.highlight().offset()).as("%sH", field.label())
                        .isEqualTo(offset + 5);
                assertThat(spans.validn().offset()).as("%sV", field.label()).isEqualTo(offset + 6);
                assertThat(spans.output().offset()).as("%sO", field.label()).isEqualTo(offset + 7);
                assertThat(spans.output().length()).isEqualTo(field.width());
                offset += 7 + field.width();
            }
            assertThat(offset).isEqualTo(555);
        }

        @Test
        @DisplayName("the first and last payload items sit at 19 and 477..554")
        void firstAndLastPayloadOffsets() {
            assertThat(TransactionViewResponse.FIELD_SPANS.get(ScreenField.TRNNAME).output()
                    .offset()).isEqualTo(19);
            FieldSpans errmsg = TransactionViewResponse.FIELD_SPANS.get(ScreenField.ERRMSG);
            assertThat(errmsg.output().offset()).isEqualTo(477);
            assertThat(errmsg.output().endOffsetExclusive()).isEqualTo(555);
        }

        @Test
        @DisplayName("every span carries its symbolic-map item name verbatim")
        void spanNamesAreVerbatim() {
            List<String> names = TransactionViewResponse.LAYOUT.spans().stream()
                    .map(span -> span.name())
                    .filter(name -> !"FILLER".equals(name))
                    .toList();
            assertThat(names).hasSize(21 * 5)
                    .contains("TRNAMTC", "TRNAMTP", "TRNAMTH", "TRNAMTV", "TRNAMTO")
                    .contains("ERRMSGO", "CONFIRMO", "ACTIDINO", "CARDNINO", "MIDO");
        }
    }

    @Nested
    @DisplayName("Risk R-B - the field set is the ADD screen's, whatever the class is called")
    class RiskRb {

        @Test
        @DisplayName("CONFIRMO is present: COTRN02 confirms an add, COTRN01 has no such field")
        void confirmIsPresent() {
            assertThat(ScreenField.valueOf("CONFIRM")).isNotNull();
            assertThat(TransactionViewResponse.CONFIRMO_LENGTH).isEqualTo(1);
        }

        @Test
        @DisplayName("there is no TRNIDINO and no TRNIDO - those belong to COTRN01")
        void noTransactionIdField() {
            List<String> outputNames = Stream.of(ScreenField.values())
                    .map(ScreenField::outputItemName)
                    .toList();
            assertThat(outputNames).doesNotContain("TRNIDINO", "TRNIDO", "TRNIDI");
            assertThat(TransactionViewResponse.LAYOUT.hasSpan("TRNIDINO")).isFalse();
            assertThat(TransactionViewResponse.LAYOUT.hasSpan("TRNIDO")).isFalse();
        }

        @Test
        @DisplayName("resolving the label TRNID fails, and says it belongs to COTRN01")
        void trnidLabelIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ScreenField.ofLabel("TRNID"))
                    .withMessageContaining("COTRN01");
        }

        @Test
        @DisplayName("exactly 14 of the 21 fields are UNPROT input, as app/bms/COTRN02.bms declares")
        void fourteenInputFields() {
            long inputs = Stream.of(ScreenField.values()).filter(ScreenField::input).count();
            assertThat(inputs).isEqualTo(14);
        }

        @Test
        @DisplayName("the seven protected fields are the six header items and the error line")
        void sevenProtectedFields() {
            List<String> protectedLabels = Stream.of(ScreenField.values())
                    .filter(field -> !field.input())
                    .map(ScreenField::label)
                    .toList();
            assertThat(protectedLabels).containsExactly("TRNNAME", "TITLE01", "CURDATE", "PGMNAME",
                    "TITLE02", "CURTIME", "ERRMSG");
        }

        @Test
        @DisplayName("the identity constants name CT02, COTRN02C, COTRN02 and COTRN2A")
        void identityConstants() {
            assertThat(TransactionViewResponse.TRANSACTION_ID).isEqualTo("CT02");
            assertThat(TransactionViewResponse.PROGRAM_ID).isEqualTo("COTRN02C");
            assertThat(TransactionViewResponse.MAPSET_NAME).isEqualTo("COTRN02");
            assertThat(TransactionViewResponse.MAP_NAME).isEqualTo("COTRN2A");
        }

        @Test
        @DisplayName("the mapset and map are 7 characters, so the X(7) commarea items fit exactly")
        void mapsetAndMapAreSevenCharacters() {
            assertThat(TransactionViewResponse.MAPSET_NAME)
                    .hasSize(NavigationContext.LAST_MAPSET_LENGTH);
            assertThat(TransactionViewResponse.MAP_NAME)
                    .hasSize(NavigationContext.LAST_MAP_LENGTH);
            assertThat(NavigationContext.LAST_MAP_LENGTH).isEqualTo(7);
            assertThat(NavigationContext.LAST_MAPSET_LENGTH).isEqualTo(7);
        }
    }

    @Nested
    @DisplayName("ScreenField - labels, derived item names and resolution")
    class Fields {

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("the five derived item names are the label plus C, P, H, V and O")
        void derivedItemNames(ScreenField field) {
            assertThat(field.colourItemName()).isEqualTo(field.label() + "C");
            assertThat(field.psItemName()).isEqualTo(field.label() + "P");
            assertThat(field.highlightItemName()).isEqualTo(field.label() + "H");
            assertThat(field.validnItemName()).isEqualTo(field.label() + "V");
            assertThat(field.outputItemName()).isEqualTo(field.label() + "O");
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("every field resolves from its own label, and round trips")
        void resolvesFromOwnLabel(ScreenField field) {
            assertThat(ScreenField.ofLabel(field.label())).isSameAs(field);
        }

        @Test
        @DisplayName("a space-padded label still resolves, because a prefix may arrive padded")
        void paddedLabelResolves() {
            assertThat(ScreenField.ofLabel("TRNAMT   ")).isSameAs(ScreenField.TRNAMT);
        }

        @Test
        @DisplayName("an unknown label is rejected and the message names the legal labels")
        void unknownLabelIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ScreenField.ofLabel("NOSUCH"))
                    .withMessageContaining("COTRN2A")
                    .withMessageContaining("TRNAMT");
        }

        @Test
        @DisplayName("a null label is rejected")
        void nullLabelIsRejected() {
            assertThatNullPointerException().isThrownBy(() -> ScreenField.ofLabel(null));
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("every declared width is at least one byte")
        void widthsArePositive(ScreenField field) {
            assertThat(field.width()).isPositive();
        }
    }

    @Nested
    @DisplayName("PIC X move semantics - pad right, truncate right, never trim")
    class PictureMoves {

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("a fresh response holds LOW-VALUES at every declared width")
        void freshResponseIsUnpainted(ScreenField field) {
            // MOVE LOW-VALUES TO COTRN2AO, app/cbl/COTRN02C.cbl:122. moveSpacesToOutputMap() is the
            // separate MOVE SPACES shape CLEAR-CURRENT-SCREEN needs at :145, and it is asserted there.
            TransactionViewResponse response = new TransactionViewResponse();
            assertThat(response.getOutputItem(field))
                    .hasSize(field.width())
                    .isEqualTo(ScreenFieldImage.unpainted(field.width()));
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("a short value is padded on the right and stays padded on read")
        void shortValueIsPaddedNotTrimmed(ScreenField field) {
            TransactionViewResponse response = new TransactionViewResponse();
            response.setOutputItem(field, "A");
            assertThat(response.getOutputItem(field))
                    .hasSize(field.width())
                    .startsWith("A");
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("an over-long value is truncated on the right, never on the left")
        void longValueIsTruncatedOnTheRight(ScreenField field) {
            TransactionViewResponse response = new TransactionViewResponse();
            String tooLong = "9876543210".repeat(20);
            response.setOutputItem(field, tooLong);
            assertThat(response.getOutputItem(field))
                    .hasSize(field.width())
                    .isEqualTo(tooLong.substring(0, field.width()));
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("null is rejected with a message naming the figurative constants")
        void nullIsRejected(ScreenField field) {
            TransactionViewResponse response = new TransactionViewResponse();
            assertThatNullPointerException()
                    .isThrownBy(() -> response.setOutputItem(field, null))
                    .withMessageContaining("COBOL has no null");
        }

        @Test
        @DisplayName("ERRMSGO takes 78 of WS-MESSAGE's 80 characters, per COTRN02C:520")
        void errmsgTruncatesWsMessage() {
            TransactionViewResponse response = new TransactionViewResponse();
            String wsMessage = "X".repeat(78) + "YZ";
            assertThat(wsMessage).hasSize(80);
            response.setErrmsgo(wsMessage);
            assertThat(response.getErrmsgo()).hasSize(78).isEqualTo("X".repeat(78));
        }

        @Test
        @DisplayName("TDESCO takes 60 of TRAN-DESC's 100 characters, per COTRN02C:486")
        void descriptionTruncatesTo60() {
            TransactionViewResponse response = new TransactionViewResponse();
            response.setTdesco("D".repeat(100));
            assertThat(response.getTdesco()).hasSize(60);
        }

        @Test
        @DisplayName("spaces(n) and lowValues(n) render n characters and differ from each other")
        void figurativeConstants() {
            assertThat(TransactionViewResponse.spaces(5)).isEqualTo("     ");
            assertThat(TransactionViewResponse.lowValues(3)).isEqualTo("\u0000\u0000\u0000");
            assertThat(TransactionViewResponse.spaces(3))
                    .isNotEqualTo(TransactionViewResponse.lowValues(3));
        }

        @ParameterizedTest
        @MethodSource("com.vsergeychik.carddemo.transaction.dto."
                + "TransactionViewResponseTest#nonPositiveWidths")
        @DisplayName("a figurative constant of zero or negative width is rejected")
        void figurativeConstantsRejectNonPositiveWidth(int width) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TransactionViewResponse.spaces(width));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TransactionViewResponse.lowValues(width));
        }

        @Test
        @DisplayName("getOutputItem and setOutputItem reject a null field")
        void genericAccessorsRejectNullField() {
            TransactionViewResponse response = new TransactionViewResponse();
            assertThatNullPointerException().isThrownBy(() -> response.getOutputItem(null));
            assertThatNullPointerException().isThrownBy(() -> response.setOutputItem(null, "x"));
        }

        @Test
        @DisplayName("the named setters and the generic setter address the same 21 items")
        void namedAndGenericAccessorsAgree() {
            TransactionViewResponse named = new TransactionViewResponse();
            named.setTrnnameo("CT02");
            named.setTitle01o("t1");
            named.setCurdateo("01/02/03");
            named.setPgmnameo("COTRN02C");
            named.setTitle02o("t2");
            named.setCurtimeo("04:05:06");
            named.setActidino("00000000099");
            named.setCardnino("4111111111111111");
            named.setTtypcdo("01");
            named.setTcatcdo("0002");
            named.setTrnsrco("POS");
            named.setTdesco("desc");
            named.setTrnamto("+00000001.23");
            named.setTorigdto("2022-07-18");
            named.setTprocdto("2022-07-19");
            named.setMido("123456789");
            named.setMnameo("merchant");
            named.setMcityo("city");
            named.setMzipo("12345");
            named.setConfirmo("Y");
            named.setErrmsgo("msg");

            TransactionViewResponse generic = new TransactionViewResponse();
            generic.setOutputItem(ScreenField.TRNNAME, "CT02");
            generic.setOutputItem(ScreenField.TITLE01, "t1");
            generic.setOutputItem(ScreenField.CURDATE, "01/02/03");
            generic.setOutputItem(ScreenField.PGMNAME, "COTRN02C");
            generic.setOutputItem(ScreenField.TITLE02, "t2");
            generic.setOutputItem(ScreenField.CURTIME, "04:05:06");
            generic.setOutputItem(ScreenField.ACTIDIN, "00000000099");
            generic.setOutputItem(ScreenField.CARDNIN, "4111111111111111");
            generic.setOutputItem(ScreenField.TTYPCD, "01");
            generic.setOutputItem(ScreenField.TCATCD, "0002");
            generic.setOutputItem(ScreenField.TRNSRC, "POS");
            generic.setOutputItem(ScreenField.TDESC, "desc");
            generic.setOutputItem(ScreenField.TRNAMT, "+00000001.23");
            generic.setOutputItem(ScreenField.TORIGDT, "2022-07-18");
            generic.setOutputItem(ScreenField.TPROCDT, "2022-07-19");
            generic.setOutputItem(ScreenField.MID, "123456789");
            generic.setOutputItem(ScreenField.MNAME, "merchant");
            generic.setOutputItem(ScreenField.MCITY, "city");
            generic.setOutputItem(ScreenField.MZIP, "12345");
            generic.setOutputItem(ScreenField.CONFIRM, "Y");
            generic.setOutputItem(ScreenField.ERRMSG, "msg");

            assertThat(generic).isEqualTo(named);
            assertThat(named.getTrnnameo()).isEqualTo(generic.getOutputItem(ScreenField.TRNNAME));
            assertThat(named.getTitle01o()).isEqualTo(generic.getOutputItem(ScreenField.TITLE01));
            assertThat(named.getCurdateo()).isEqualTo(generic.getOutputItem(ScreenField.CURDATE));
            assertThat(named.getPgmnameo()).isEqualTo(generic.getOutputItem(ScreenField.PGMNAME));
            assertThat(named.getTitle02o()).isEqualTo(generic.getOutputItem(ScreenField.TITLE02));
            assertThat(named.getCurtimeo()).isEqualTo(generic.getOutputItem(ScreenField.CURTIME));
            assertThat(named.getActidino()).isEqualTo(generic.getOutputItem(ScreenField.ACTIDIN));
            assertThat(named.getCardnino()).isEqualTo(generic.getOutputItem(ScreenField.CARDNIN));
            assertThat(named.getTtypcdo()).isEqualTo(generic.getOutputItem(ScreenField.TTYPCD));
            assertThat(named.getTcatcdo()).isEqualTo(generic.getOutputItem(ScreenField.TCATCD));
            assertThat(named.getTrnsrco()).isEqualTo(generic.getOutputItem(ScreenField.TRNSRC));
            assertThat(named.getTdesco()).isEqualTo(generic.getOutputItem(ScreenField.TDESC));
            assertThat(named.getTrnamto()).isEqualTo(generic.getOutputItem(ScreenField.TRNAMT));
            assertThat(named.getTorigdto()).isEqualTo(generic.getOutputItem(ScreenField.TORIGDT));
            assertThat(named.getTprocdto()).isEqualTo(generic.getOutputItem(ScreenField.TPROCDT));
            assertThat(named.getMido()).isEqualTo(generic.getOutputItem(ScreenField.MID));
            assertThat(named.getMnameo()).isEqualTo(generic.getOutputItem(ScreenField.MNAME));
            assertThat(named.getMcityo()).isEqualTo(generic.getOutputItem(ScreenField.MCITY));
            assertThat(named.getMzipo()).isEqualTo(generic.getOutputItem(ScreenField.MZIP));
            assertThat(named.getConfirmo()).isEqualTo(generic.getOutputItem(ScreenField.CONFIRM));
            assertThat(named.getErrmsgo()).isEqualTo(generic.getOutputItem(ScreenField.ERRMSG));
        }
    }

    static Stream<Arguments> nonPositiveWidths() {
        return Stream.of(Arguments.of(0), Arguments.of(-1));
    }

    @Nested
    @DisplayName("The edited amount - TRNAMTO is a 12-character mask, not a number")
    class EditedAmount {

        @Test
        @DisplayName("the field is 12 characters: sign, 8 integer digits, a point, 2 fraction digits")
        void maskIsTwelveCharacters() {
            assertThat(TransactionViewResponse.TRNAMTO_LENGTH).isEqualTo(12);
            assertThat("+99999999.99").hasSize(12);
        }

        @Test
        @DisplayName("a well-formed mask is stored verbatim and satisfies COTRN02C:340-343 positionally")
        void wellFormedMaskIsStoredVerbatim() {
            TransactionViewResponse response = new TransactionViewResponse();
            response.setTrnamto("-00001234.56");
            String amount = response.getTrnamto();
            assertThat(amount).isEqualTo("-00001234.56").hasSize(12);
            assertThat(amount.charAt(0)).isIn('-', '+');
            assertThat(amount.substring(1, 9)).containsOnlyDigits();
            assertThat(amount.charAt(9)).isEqualTo('.');
            assertThat(amount.substring(10, 12)).containsOnlyDigits();
        }

        @Test
        @DisplayName("a malformed amount is still stored, so the error path can redisplay it")
        void malformedAmountIsStored() {
            TransactionViewResponse response = new TransactionViewResponse();
            response.setTrnamto("not a number");
            assertThat(response.getTrnamto()).isEqualTo("not a number").hasSize(12);
        }

        @Test
        @DisplayName("the mask holds 8 integer digits while TRAN-AMT holds 9, so a 9-digit amount "
                + "left-truncates exactly as COTRN02C:481-485 does")
        void ninthIntegerDigitIsLeftTruncated() {
            // TRAN-AMT PIC S9(09)V99 can hold 123456789.99; the PIC +99999999.99 mask cannot.
            // COBOL's numeric move drops high-order digits, so the leading 1 is lost.
            TransactionViewResponse response = new TransactionViewResponse();
            response.setTrnamto("+23456789.99");
            assertThat(response.getTrnamto()).hasSize(12);
            assertThat(TransactionViewResponse.TRNAMTO_LENGTH).isEqualTo(12);
            assertThat("123456789.99".length()).isGreaterThan(11);
        }
    }

    @Nested
    @DisplayName("Security posture - identifiers are carried unmasked (gate G41)")
    class SecurityPosture {

        @Test
        @DisplayName("CARDNINO returns all 16 characters of the card number, unredacted")
        void cardNumberIsNotMasked() {
            TransactionViewResponse response = new TransactionViewResponse();
            response.setCardnino("4111111111111111");
            assertThat(response.getCardnino())
                    .isEqualTo("4111111111111111")
                    .doesNotContain("*")
                    .doesNotContain("X");
        }

        @Test
        @DisplayName("ACTIDINO returns all 11 characters of the account id, unredacted")
        void accountIdIsNotMasked() {
            TransactionViewResponse response = new TransactionViewResponse();
            response.setActidino("00000000011");
            assertThat(response.getActidino()).isEqualTo("00000000011");
        }

        @Test
        @DisplayName("MIDO returns all 9 characters of the merchant id, unredacted")
        void merchantIdIsNotMasked() {
            TransactionViewResponse response = new TransactionViewResponse();
            response.setMido("123456789");
            assertThat(response.getMido()).isEqualTo("123456789");
        }

        @Test
        @DisplayName("the three identifiers survive a JSON round trip in clear")
        void identifiersSurviveJsonInClear() throws Exception {
            TransactionViewResponse response = new TransactionViewResponse();
            response.setCardnino("4111111111111111");
            response.setActidino("00000000011");
            response.setMido("123456789");
            String json = new ObjectMapper().writeValueAsString(response);
            assertThat(json)
                    .contains("4111111111111111")
                    .contains("00000000011")
                    .contains("123456789");
        }
    }

    @Nested
    @DisplayName("CSSETATY highlight - reachable in REENTER, unreachable in ENTER (gate G38)")
    class Highlighting {

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("every field starts with all four attribute items at DFHDFCOL")
        void attributesStartAtDefault(ScreenField field) {
            FieldMetadata quad = new TransactionViewResponse().getMetadata(field);
            assertThat(quad.getColour()).isEqualTo(BmsAttributes.DFHDFCOL);
            assertThat(quad.getProgrammedSymbols()).isEqualTo(BmsAttributes.DFHDFCOL);
            assertThat(quad.getHighlight()).isEqualTo(BmsAttributes.DFHDFCOL);
            assertThat(quad.getValidation()).isEqualTo(BmsAttributes.DFHDFCOL);
            assertThat(quad.isDefault()).isTrue();
        }

        @ParameterizedTest
        @EnumSource(value = ScreenField.class, names = {"ACTIDIN", "CARDNIN", "TTYPCD", "TCATCD",
                "TRNSRC", "TDESC", "TRNAMT", "TORIGDT", "TPROCDT", "MID", "MNAME", "MCITY", "MZIP",
                "CONFIRM"})
        @DisplayName("in REENTER a BLANK field takes DFHRED in xxxC and '*' in xxxO")
        void reenterBlankHighlightsAndStars(ScreenField field) {
            TransactionViewResponse response = new TransactionViewResponse();
            FieldHighlight highlight = FieldAttributeSetter.resolve(FieldValidationState.BLANK, true,
                    field.label(), TransactionViewResponse.MAP_NAME);

            assertThat(response.applyHighlight(highlight)).isTrue();

            assertThat(response.getMetadata(field).getColour()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(response.getOutputItem(field)).startsWith("*").hasSize(field.width());
        }

        @ParameterizedTest
        @EnumSource(value = ScreenField.class, names = {"ACTIDIN", "CARDNIN", "TRNAMT", "TDESC",
                "MZIP", "CONFIRM"})
        @DisplayName("in REENTER a NOT-OK field takes DFHRED but NOT the asterisk")
        void reenterNotOkHighlightsWithoutStar(ScreenField field) {
            TransactionViewResponse response = new TransactionViewResponse();
            FieldHighlight highlight = FieldAttributeSetter.resolve(FieldValidationState.NOT_OK,
                    true, field.label(), TransactionViewResponse.MAP_NAME);

            assertThat(response.applyHighlight(highlight)).isTrue();

            assertThat(response.getMetadata(field).getColour()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(response.getOutputItem(field))
                    .isEqualTo(ScreenFieldImage.unpainted(field.width()));
        }

        @ParameterizedTest
        @EnumSource(value = FieldValidationState.class)
        @DisplayName("in ENTER no state highlights anything - the whole rule is unreachable")
        void enterNeverHighlights(FieldValidationState state) {
            TransactionViewResponse response = new TransactionViewResponse();
            TransactionViewResponse untouched = new TransactionViewResponse();
            FieldHighlight highlight = FieldAttributeSetter.resolve(state, false,
                    ScreenField.TRNAMT.label(), TransactionViewResponse.MAP_NAME);

            assertThat(response.applyHighlight(highlight)).isFalse();

            assertThat(response.getMetadata(ScreenField.TRNAMT).getColour())
                    .isEqualTo(BmsAttributes.DFHDFCOL);
            assertThat(response).isEqualTo(untouched);
        }

        @Test
        @DisplayName("in REENTER an OK field is left alone")
        void reenterOkLeavesFieldAlone() {
            TransactionViewResponse response = new TransactionViewResponse();
            FieldHighlight highlight = FieldAttributeSetter.resolve(FieldValidationState.OK, true,
                    ScreenField.TRNAMT.label(), TransactionViewResponse.MAP_NAME);

            assertThat(response.applyHighlight(highlight)).isFalse();
            assertThat(response.getMetadata(ScreenField.TRNAMT).isDefault()).isTrue();
        }

        @Test
        @DisplayName("a highlight naming a field this map does not declare is rejected")
        void highlightForForeignFieldIsRejected() {
            TransactionViewResponse response = new TransactionViewResponse();
            FieldHighlight highlight = FieldAttributeSetter.resolve(FieldValidationState.BLANK, true,
                    "TRNID", TransactionViewResponse.MAP_NAME);
            assertThatIllegalArgumentException().isThrownBy(() -> response.applyHighlight(highlight));
        }

        @Test
        @DisplayName("a null highlight is rejected, and the message points at FieldAttributeSetter")
        void nullHighlightIsRejected() {
            TransactionViewResponse response = new TransactionViewResponse();
            assertThatNullPointerException()
                    .isThrownBy(() -> response.applyHighlight(null))
                    .withMessageContaining("FieldAttributeSetter");
        }

        @Test
        @DisplayName("every one of the 14 input fields has a reachable colour item")
        void everyInputFieldHasAReachableColourItem() {
            TransactionViewResponse response = new TransactionViewResponse();
            List<String> highlighted = new ArrayList<>();
            for (ScreenField field : ScreenField.values()) {
                if (!field.input()) {
                    continue;
                }
                FieldHighlight highlight = FieldAttributeSetter.resolve(FieldValidationState.NOT_OK,
                        true, field.label(), TransactionViewResponse.MAP_NAME);
                response.applyHighlight(highlight);
                if (response.getMetadata(field).getColour() == BmsAttributes.DFHRED) {
                    highlighted.add(field.label());
                }
            }
            assertThat(highlighted).hasSize(14);
        }

        @Test
        @DisplayName("an asterisk without a colour is impossible: CSSETATY nests the '*' inside the "
                + "DFHRED move, and FieldHighlight enforces that invariant at construction")
        void asteriskWithoutColourIsUnreachable() {
            assertThatIllegalArgumentException().isThrownBy(
                    () -> new FieldHighlight(false, true, "TRNAMT",
                            TransactionViewResponse.MAP_NAME));

            // The three reachable combinations, which is why applyHighlight tests each write
            // independently rather than assuming both always happen together.
            assertThat(new FieldHighlight(false, false, "TRNAMT", "COTRN2A").untouched()).isTrue();
            assertThat(new FieldHighlight(true, false, "TRNAMT", "COTRN2A").untouched()).isFalse();
            assertThat(new FieldHighlight(true, true, "TRNAMT", "COTRN2A").untouched()).isFalse();
        }

        @Test
        @DisplayName("resetMetadata clears every highlight on all 21 fields")
        void resetMetadataClearsEverything() {
            TransactionViewResponse response = populated();
            assertThat(response.getMetadata(ScreenField.TRNAMT).isDefault()).isFalse();

            response.resetMetadata();

            for (ScreenField field : ScreenField.values()) {
                assertThat(response.getMetadata(field).isDefault()).as(field.label()).isTrue();
            }
        }

        @Test
        @DisplayName("the metadata map exposes all 21 quads and cannot gain or lose a field")
        void metadataMapIsFixedAndComplete() {
            TransactionViewResponse response = new TransactionViewResponse();
            assertThat(response.getMetadata()).hasSize(21);
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> response.getMetadata().remove(ScreenField.TRNAMT));
        }

        @Test
        @DisplayName("getMetadata rejects a null field")
        void getMetadataRejectsNull() {
            TransactionViewResponse response = new TransactionViewResponse();
            assertThatNullPointerException().isThrownBy(() -> response.getMetadata(null));
        }

        @Test
        @DisplayName("isDefault is false as soon as any one of the four items is set")
        void isDefaultTracksAllFourItems() {
            for (int index = 0; index < 4; index++) {
                FieldMetadata quad = new FieldMetadata();
                switch (index) {
                    case 0 -> quad.setColour(BmsAttributes.DFHRED);
                    case 1 -> quad.setProgrammedSymbols((byte) 1);
                    case 2 -> quad.setHighlight(BmsAttributes.DFHBLINK);
                    default -> quad.setValidation((byte) 1);
                }
                assertThat(quad.isDefault()).as("item %d", index).isFalse();
                quad.reset();
                assertThat(quad.isDefault()).isTrue();
            }
        }

        @Test
        @DisplayName("FieldMetadata has value semantics over all four items")
        void fieldMetadataValueSemantics() {
            FieldMetadata one = new FieldMetadata();
            FieldMetadata two = new FieldMetadata();
            assertThat(one).isEqualTo(two).hasSameHashCodeAs(two);
            assertThat(one).isEqualTo(one).isNotEqualTo(null).isNotEqualTo("text");

            List<Consumer<FieldMetadata>> mutators = List.of(
                    quad -> quad.setColour(BmsAttributes.DFHRED),
                    quad -> quad.setProgrammedSymbols((byte) 7),
                    quad -> quad.setHighlight(BmsAttributes.DFHREVRS),
                    quad -> quad.setValidation((byte) 9));
            for (Consumer<FieldMetadata> mutator : mutators) {
                FieldMetadata mutated = new FieldMetadata();
                mutator.accept(mutated);
                assertThat(mutated).isNotEqualTo(new FieldMetadata());
            }
            assertThat(one.toString()).contains("FieldMetadata").contains("C=");
        }
    }

    @Nested
    @DisplayName("CDEMO-CT02-INFO - the 58-byte cursor and its two 88-levels (gate G50)")
    class Cursor {

        @Test
        @DisplayName("16 + 16 + 8 + 1 + 1 + 16 = 58, and the passed commarea is 160 + 58 = 218")
        void cursorGeometry() {
            assertThat(Ct02Info.TRNID_FIRST_LENGTH).isEqualTo(16);
            assertThat(Ct02Info.TRNID_LAST_LENGTH).isEqualTo(16);
            assertThat(Ct02Info.PAGE_NUM_LENGTH).isEqualTo(8);
            assertThat(Ct02Info.NEXT_PAGE_FLG_LENGTH).isEqualTo(1);
            assertThat(Ct02Info.TRN_SEL_FLG_LENGTH).isEqualTo(1);
            assertThat(Ct02Info.TRN_SELECTED_LENGTH).isEqualTo(16);
            assertThat(Ct02Info.LENGTH).isEqualTo(58);
            assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(160);
            assertThat(Ct02Info.PASSED_COMMAREA_LENGTH).isEqualTo(218);
        }

        @Test
        @DisplayName("a fresh cursor honours VALUE 'N': NEXT-PAGE-NO is true, NEXT-PAGE-YES false")
        void defaultsToNextPageNo() {
            Ct02Info cursor = new Ct02Info();
            assertThat(cursor.getNextPageFlg()).isEqualTo("N");
            assertThat(cursor.isNextPageNo()).isTrue();
            assertThat(cursor.isNextPageYes()).isFalse();
            assertThat(cursor.getTrnidFirst()).hasSize(16).isBlank();
            assertThat(cursor.getTrnidLast()).hasSize(16).isBlank();
            assertThat(cursor.getTrnSelFlg()).hasSize(1).isBlank();
            assertThat(cursor.getTrnSelected()).hasSize(16).isBlank();
            assertThat(cursor.getPageNum()).isZero();
        }

        @Test
        @DisplayName("SET NEXT-PAGE-YES TO TRUE makes YES true and NO false, and back again")
        void bothConditionsAreReachable() {
            Ct02Info cursor = new Ct02Info();

            cursor.setNextPageYes();
            assertThat(cursor.getNextPageFlg()).isEqualTo("Y");
            assertThat(cursor.isNextPageYes()).isTrue();
            assertThat(cursor.isNextPageNo()).isFalse();

            cursor.setNextPageNo();
            assertThat(cursor.getNextPageFlg()).isEqualTo("N");
            assertThat(cursor.isNextPageYes()).isFalse();
            assertThat(cursor.isNextPageNo()).isTrue();
        }

        @Test
        @DisplayName("a third value leaves both 88-levels false, exactly as COBOL would")
        void athirdValueSatisfiesNeitherCondition() {
            Ct02Info cursor = new Ct02Info();
            cursor.setNextPageFlg("X");
            assertThat(cursor.isNextPageYes()).isFalse();
            assertThat(cursor.isNextPageNo()).isFalse();
        }

        @Test
        @DisplayName("the two identifiers pad to 16 and are not trimmed")
        void identifiersPadTo16() {
            Ct02Info cursor = new Ct02Info();
            cursor.setTrnidFirst("1");
            cursor.setTrnidLast("2");
            cursor.setTrnSelected("3");
            cursor.setTrnSelFlg("SS");
            assertThat(cursor.getTrnidFirst()).hasSize(16).startsWith("1");
            assertThat(cursor.getTrnidLast()).hasSize(16).startsWith("2");
            assertThat(cursor.getTrnSelected()).hasSize(16).startsWith("3");
            assertThat(cursor.getTrnSelFlg()).isEqualTo("S");
        }

        @Test
        @DisplayName("CDEMO-CT02-TRN-SELECTED distinguishes SPACES from LOW-VALUES, per COTRN02C:124")
        void selectedDistinguishesSpacesFromLowValues() {
            Ct02Info spaces = new Ct02Info();
            spaces.setTrnSelected(TransactionViewResponse.spaces(16));
            Ct02Info low = new Ct02Info();
            low.setTrnSelected(TransactionViewResponse.lowValues(16));
            assertThat(spaces.getTrnSelected()).isNotEqualTo(low.getTrnSelected());
        }

        @Test
        @DisplayName("PIC 9(08) accepts 0 and 99999999 and rejects -1 and 100000000")
        void pageNumberHonoursItsPicture() {
            Ct02Info cursor = new Ct02Info();
            cursor.setPageNum(0);
            assertThat(cursor.getPageNum()).isZero();
            cursor.setPageNum(99_999_999);
            assertThat(cursor.getPageNum()).isEqualTo(99_999_999);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> cursor.setPageNum(-1))
                    .withMessageContaining("unsigned");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> cursor.setPageNum(100_000_000))
                    .withMessageContaining("PIC 9(08)");
        }

        @Test
        @DisplayName("every cursor setter rejects null")
        void settersRejectNull() {
            Ct02Info cursor = new Ct02Info();
            assertThatNullPointerException().isThrownBy(() -> cursor.setTrnidFirst(null));
            assertThatNullPointerException().isThrownBy(() -> cursor.setTrnidLast(null));
            assertThatNullPointerException().isThrownBy(() -> cursor.setNextPageFlg(null));
            assertThatNullPointerException().isThrownBy(() -> cursor.setTrnSelFlg(null));
            assertThatNullPointerException().isThrownBy(() -> cursor.setTrnSelected(null));
        }

        @Test
        @DisplayName("the cursor has value semantics over all six items")
        void cursorValueSemantics() {
            assertThat(new Ct02Info()).isEqualTo(new Ct02Info())
                    .hasSameHashCodeAs(new Ct02Info());
            Ct02Info one = new Ct02Info();
            assertThat(one).isEqualTo(one).isNotEqualTo(null).isNotEqualTo("text");

            List<Consumer<Ct02Info>> mutators = List.of(
                    cursor -> cursor.setTrnidFirst("a"),
                    cursor -> cursor.setTrnidLast("b"),
                    cursor -> cursor.setPageNum(1),
                    cursor -> cursor.setNextPageYes(),
                    cursor -> cursor.setTrnSelFlg("s"),
                    cursor -> cursor.setTrnSelected("c"));
            for (Consumer<Ct02Info> mutator : mutators) {
                Ct02Info mutated = new Ct02Info();
                mutator.accept(mutated);
                assertThat(mutated).isNotEqualTo(new Ct02Info());
            }
            assertThat(one.toString()).contains("CDEMO-CT02-TRNID-FIRST")
                    .contains("CDEMO-CT02-NEXT-PAGE-FLG");
        }

        @Test
        @DisplayName("the cursor is echoed on the response and can be replaced, but never nulled")
        void cursorIsEchoed() {
            TransactionViewResponse response = new TransactionViewResponse();
            assertThat(response.getCt02Info()).isNotNull();
            assertThat(response.getCt02Info().isNextPageNo()).isTrue();

            Ct02Info replacement = new Ct02Info();
            replacement.setPageNum(5);
            response.setCt02Info(replacement);
            assertThat(response.getCt02Info().getPageNum()).isEqualTo(5);

            assertThatNullPointerException().isThrownBy(() -> response.setCt02Info(null));
        }
    }

    @Nested
    @DisplayName("Statelessness - navigation replaces XCTL (gates G37 and G40)")
    class Navigation {

        @Test
        @DisplayName("the navigation targets default to this screen, and the program to spaces")
        void navigationDefaults() {
            TransactionViewResponse response = new TransactionViewResponse();
            assertThat(response.getNextMapset()).isEqualTo("COTRN02");
            assertThat(response.getNextMap()).isEqualTo("COTRN2A");
            assertThat(response.getNextProgram()).hasSize(8).isBlank();
        }

        @Test
        @DisplayName("nextProgram is X(8) and nextMapset and nextMap are X(7), not X(8)")
        void navigationWidths() {
            TransactionViewResponse response = new TransactionViewResponse();
            response.setNextProgram("A");
            response.setNextMapset("B");
            response.setNextMap("C");
            assertThat(response.getNextProgram()).hasSize(8);
            assertThat(response.getNextMapset()).hasSize(7);
            assertThat(response.getNextMap()).hasSize(7);
        }

        @Test
        @DisplayName("nextProgram carries the COMMAREA-driven XCTL target of COTRN02C:509")
        void nextProgramCarriesXctlTarget() {
            TransactionViewResponse response = new TransactionViewResponse();
            NavigationContext context = NavigationContext.empty().withToProgram("COMEN01C");
            response.setNavigationContext(context);
            response.setNextProgram(context.toProgram());
            assertThat(response.getNextProgram()).isEqualTo("COMEN01C");
        }

        @Test
        @DisplayName("the navigation setters reject null")
        void navigationSettersRejectNull() {
            TransactionViewResponse response = new TransactionViewResponse();
            assertThatNullPointerException().isThrownBy(() -> response.setNextProgram(null));
            assertThatNullPointerException().isThrownBy(() -> response.setNextMapset(null));
            assertThatNullPointerException().isThrownBy(() -> response.setNextMap(null));
            assertThatNullPointerException().isThrownBy(() -> response.setNavigationContext(null));
        }

        @Test
        @DisplayName("the echoed commarea is exactly 160 bytes - the response never widens it")
        void commareaIsNotWidened() {
            TransactionViewResponse response = new TransactionViewResponse();
            FixedWidthCodec codec = new FixedWidthCodec(ASCII);
            assertThat(response.getNavigationContext().toFixedWidth(codec)).hasSize(160);
        }

        @Test
        @DisplayName("ENTER and REENTER both travel in the echoed commarea, not in server state")
        void programContextTravelsInThePayload() {
            TransactionViewResponse response = new TransactionViewResponse();
            response.setNavigationContext(NavigationContext.empty().withPgmEnter());
            assertThat(response.getNavigationContext().isEnter()).isTrue();
            assertThat(response.getNavigationContext().isReenter()).isFalse();

            response.setNavigationContext(NavigationContext.empty().withPgmReenter());
            assertThat(response.getNavigationContext().isReenter()).isTrue();
            assertThat(response.getNavigationContext().isEnter()).isFalse();
        }
    }

    @Nested
    @DisplayName("The COTRN02C send path, reproduced move for move")
    class SendPath {

        @Test
        @DisplayName("populateHeaderInfo reproduces POPULATE-HEADER-INFO at COTRN02C:552-571")
        void populateHeaderInfoSetsSixItems() {
            TransactionViewResponse response = new TransactionViewResponse();
            DateHeader header = DateHeader.of(new FixedWidthCodec(ASCII),
                    LocalDateTime.of(2022, 7, 18, 4, 5, 6));

            response.populateHeaderInfo(header);

            assertThat(response.getTitle01o()).isEqualTo(ScreenTitles.CCDA_TITLE01).hasSize(40);
            assertThat(response.getTitle02o()).isEqualTo(ScreenTitles.CCDA_TITLE02).hasSize(40);
            assertThat(response.getTrnnameo()).isEqualTo("CT02");
            assertThat(response.getPgmnameo()).isEqualTo("COTRN02C");
            assertThat(response.getCurdateo()).isEqualTo("07/18/22").hasSize(8);
            assertThat(response.getCurtimeo()).isEqualTo("04:05:06").hasSize(8);
        }

        @Test
        @DisplayName("populateHeaderInfo rejects null, because COTRN02C:554 captures the date first")
        void populateHeaderInfoRejectsNull() {
            TransactionViewResponse response = new TransactionViewResponse();
            assertThatNullPointerException()
                    .isThrownBy(() -> response.populateHeaderInfo(null))
                    .withMessageContaining("CURRENT-DATE");
        }

        @Test
        @DisplayName("setErrmsgoInvalidKey reproduces COTRN02C:150, padded from X(50) to X(78)")
        void invalidKeyMessage() {
            TransactionViewResponse response = new TransactionViewResponse();
            response.setErrmsgoInvalidKey();
            assertThat(response.getErrmsgo())
                    .hasSize(78)
                    .startsWith(SystemMessages.CCDA_MSG_INVALID_KEY.stripTrailing());
        }

        @Test
        @DisplayName("clearErrmsgo reproduces COTRN02C:112-113")
        void clearErrorLine() {
            TransactionViewResponse response = new TransactionViewResponse();
            response.setErrmsgo("something went wrong");
            response.clearErrmsgo();
            assertThat(response.getErrmsgo()).hasSize(78).isBlank();
        }

        @Test
        @DisplayName("moveLowValuesToOutputMap reproduces COTRN02C:122 across all 21 items and 84 "
                + "attribute items")
        void lowValuesFillsTheWholeGroup() {
            TransactionViewResponse response = populated();

            response.moveLowValuesToOutputMap();

            for (ScreenField field : ScreenField.values()) {
                assertThat(response.getOutputItem(field)).as(field.outputItemName())
                        .hasSize(field.width())
                        .isEqualTo(TransactionViewResponse.lowValues(field.width()));
                assertThat(response.getMetadata(field).isDefault()).as(field.colourItemName())
                        .isTrue();
            }
        }

        @Test
        @DisplayName("LOW-VALUES and SPACES leave the group in genuinely different states")
        void lowValuesDiffersFromSpaces() {
            TransactionViewResponse low = new TransactionViewResponse();
            low.moveLowValuesToOutputMap();
            TransactionViewResponse blank = new TransactionViewResponse();
            blank.moveSpacesToOutputMap();

            assertThat(low).isNotEqualTo(blank);
            assertThat(low.getTrnamto()).isNotEqualTo(blank.getTrnamto());
            assertThat(blank.getTrnamto()).isBlank();
        }

        @Test
        @DisplayName("moveSpacesToOutputMap blanks all 21 items at their declared widths")
        void spacesFillsEveryPayloadItem() {
            TransactionViewResponse response = populated();

            response.moveSpacesToOutputMap();

            for (ScreenField field : ScreenField.values()) {
                assertThat(response.getOutputItem(field)).as(field.outputItemName())
                        .hasSize(field.width())
                        .isBlank();
            }
        }
    }

    @Nested
    @DisplayName("Fixed-width rendering of the 555-byte group image")
    class FixedWidth {

        @Test
        @DisplayName("a rendered image is exactly 555 bytes")
        void imageIs555Bytes() {
            assertThat(populated().toFixedWidth(ASCII)).hasSize(555);
            assertThat(new TransactionViewResponse().toFixedWidth(ASCII)).hasSize(555);
        }

        @Test
        @DisplayName("the round trip is lossless for all 21 payload and 84 attribute items")
        void roundTripIsLossless() {
            TransactionViewResponse original = populated();

            TransactionViewResponse restored =
                    TransactionViewResponse.fromFixedWidth(original.toFixedWidth(ASCII), ASCII);

            for (ScreenField field : ScreenField.values()) {
                assertThat(restored.getOutputItem(field)).as(field.outputItemName())
                        .isEqualTo(original.getOutputItem(field));
                assertThat(restored.getMetadata(field)).as(field.label())
                        .isEqualTo(original.getMetadata(field));
            }
        }

        @Test
        @DisplayName("a DFHRED colour byte survives the round trip, because attributes go as bytes")
        void attributeBytesSurvive() {
            TransactionViewResponse original = new TransactionViewResponse();
            original.getMetadata(ScreenField.TRNAMT).setColour(BmsAttributes.DFHRED);

            TransactionViewResponse restored =
                    TransactionViewResponse.fromFixedWidth(original.toFixedWidth(ASCII), ASCII);

            assertThat(restored.getMetadata(ScreenField.TRNAMT).getColour())
                    .isEqualTo(BmsAttributes.DFHRED);
        }

        @Test
        @DisplayName("a LOW-VALUES group round trips without becoming spaces")
        void lowValuesGroupRoundTrips() {
            TransactionViewResponse original = new TransactionViewResponse();
            original.moveLowValuesToOutputMap();

            TransactionViewResponse restored =
                    TransactionViewResponse.fromFixedWidth(original.toFixedWidth(ASCII), ASCII);

            assertThat(restored.getTrnamto()).isEqualTo(original.getTrnamto());
            assertThat(restored.getTrnamto().charAt(0)).isEqualTo('\u0000');
        }

        @Test
        @DisplayName("trailing spaces survive the round trip untrimmed")
        void trailingSpacesSurvive() {
            TransactionViewResponse original = new TransactionViewResponse();
            original.setErrmsgo("short");
            original.setConfirmo(" ");
            original.setTrnamto("+00000000.00");

            TransactionViewResponse restored =
                    TransactionViewResponse.fromFixedWidth(original.toFixedWidth(ASCII), ASCII);

            assertThat(restored.getErrmsgo()).hasSize(78).isEqualTo(original.getErrmsgo());
            assertThat(restored.getConfirmo()).hasSize(1).isEqualTo(" ");
            assertThat(restored.getTrnamto()).hasSize(12).isEqualTo("+00000000.00");
        }

        @Test
        @DisplayName("writeInto and readFrom address a caller-supplied record")
        void writeIntoAndReadFrom() {
            TransactionViewResponse original = populated();
            FixedWidthRecord record =
                    FixedWidthRecord.forLayout(TransactionViewResponse.LAYOUT, ASCII);

            original.writeInto(record);
            TransactionViewResponse restored = new TransactionViewResponse();
            restored.readFrom(record);

            for (ScreenField field : ScreenField.values()) {
                assertThat(restored.getOutputItem(field)).as(field.outputItemName())
                        .isEqualTo(original.getOutputItem(field));
            }
        }

        @Test
        @DisplayName("an image of the wrong length is rejected, and the message shows the arithmetic")
        void wrongLengthIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TransactionViewResponse.fromFixedWidth(new byte[554], ASCII))
                    .withMessageContaining("555");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TransactionViewResponse.fromFixedWidth(new byte[556], ASCII));
        }

        @Test
        @DisplayName("a record laid out for something else is rejected by writeInto and readFrom")
        void foreignRecordIsRejected() {
            TransactionViewResponse response = new TransactionViewResponse();
            FixedWidthRecord foreign =
                    FixedWidthRecord.forLayout(NavigationContext.LAYOUT, ASCII);
            assertThatIllegalArgumentException().isThrownBy(() -> response.writeInto(foreign));
            assertThatIllegalArgumentException().isThrownBy(() -> response.readFrom(foreign));
        }

        @Test
        @DisplayName("the charset is always supplied by the caller and never defaulted")
        void charsetIsMandatory() {
            TransactionViewResponse response = new TransactionViewResponse();
            assertThatNullPointerException()
                    .isThrownBy(() -> response.toFixedWidth(null))
                    .withMessageContaining("platform default");
            assertThatNullPointerException()
                    .isThrownBy(() -> TransactionViewResponse.fromFixedWidth(new byte[555], null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TransactionViewResponse.fromFixedWidth(null, ASCII));
            assertThatNullPointerException().isThrownBy(() -> response.writeInto(null));
            assertThatNullPointerException().isThrownBy(() -> response.readFrom(null));
        }

        @Test
        @DisplayName("an ASCII image and an EBCDIC image differ in bytes but agree in fields")
        void encodingIsExplicitNotAmbient() {
            TransactionViewResponse original = new TransactionViewResponse();
            original.setErrmsgo("Account ID NOT found...");
            Charset ebcdic = Charset.forName("IBM037");

            byte[] asciiImage = original.toFixedWidth(ASCII);
            byte[] ebcdicImage = original.toFixedWidth(ebcdic);

            assertThat(asciiImage).hasSize(555);
            assertThat(ebcdicImage).hasSize(555).isNotEqualTo(asciiImage);
            assertThat(TransactionViewResponse.fromFixedWidth(ebcdicImage, ebcdic).getErrmsgo())
                    .isEqualTo(original.getErrmsgo());
        }
    }

    @Nested
    @DisplayName("JSON projection - 21 payload members, no metadata, nothing trimmed")
    class Json {

        private final ObjectMapper mapper = new ObjectMapper();

        @Test
        @DisplayName("all 21 payload members are serialised under their xxxO-derived names")
        void allPayloadMembersAreSerialised() throws Exception {
            String json = mapper.writeValueAsString(populated());
            for (ScreenField field : ScreenField.values()) {
                String property = withoutDirectionSuffix(field.outputItemName());
                assertThat(json).as(property).contains("\"" + property + "\"");
            }
        }

        @Test
        @DisplayName("no attribute item is serialised - metadata never reaches the payload")
        void metadataIsNotSerialised() throws Exception {
            TransactionViewResponse response = populated();
            String json = mapper.writeValueAsString(response);
            assertThat(json)
                    .doesNotContain("\"metadata\"")
                    .doesNotContain("trnamtc")
                    .doesNotContain("colour")
                    .doesNotContain("programmedSymbols")
                    .doesNotContain("nextPageYes")
                    .doesNotContain("nextPageNo");
        }

        @Test
        @DisplayName("a full round trip through JSON preserves all 21 payload items untrimmed")
        void jsonRoundTripPreservesPadding() throws Exception {
            TransactionViewResponse original = new TransactionViewResponse();
            original.setErrmsgo("short message");
            original.setTrnamto("+00000012.34");
            original.setConfirmo("Y");
            original.setCardnino("4111111111111111");

            String json = mapper.writeValueAsString(original);
            TransactionViewResponse restored =
                    mapper.readValue(json, TransactionViewResponse.class);

            assertThat(restored.getErrmsgo()).hasSize(78).isEqualTo(original.getErrmsgo());
            assertThat(restored.getTrnamto()).hasSize(12).isEqualTo("+00000012.34");
            assertThat(restored.getConfirmo()).hasSize(1).isEqualTo("Y");
            assertThat(restored.getCardnino()).hasSize(16).isEqualTo("4111111111111111");
            for (ScreenField field : ScreenField.values()) {
                assertThat(restored.getOutputItem(field)).as(field.outputItemName())
                        .isEqualTo(original.getOutputItem(field));
            }
        }

        @Test
        @DisplayName("the navigation trio and the cursor survive a JSON round trip")
        void navigationAndCursorSurviveJson() throws Exception {
            TransactionViewResponse original = new TransactionViewResponse();
            original.setNextProgram("COMEN01C");
            original.getCt02Info().setPageNum(4);
            original.getCt02Info().setNextPageYes();
            original.getCt02Info().setTrnSelected("0000000000000009");

            TransactionViewResponse restored = mapper.readValue(
                    mapper.writeValueAsString(original), TransactionViewResponse.class);

            assertThat(restored.getNextProgram()).isEqualTo("COMEN01C");
            assertThat(restored.getNextMapset()).isEqualTo("COTRN02");
            assertThat(restored.getNextMap()).isEqualTo("COTRN2A");
            assertThat(restored.getCt02Info().getPageNum()).isEqualTo(4);
            assertThat(restored.getCt02Info().isNextPageYes()).isTrue();
            assertThat(restored.getCt02Info().getTrnSelected()).hasSize(16);
        }
    }

    @Nested
    @DisplayName("Value semantics over everything the response carries")
    class ValueSemantics {

        @Test
        @DisplayName("two fresh responses are equal and share a hash code")
        void freshResponsesAreEqual() {
            assertThat(new TransactionViewResponse())
                    .isEqualTo(new TransactionViewResponse())
                    .hasSameHashCodeAs(new TransactionViewResponse());
        }

        @Test
        @DisplayName("a response equals itself and nothing of another type")
        void reflexiveAndTypeSafe() {
            TransactionViewResponse response = new TransactionViewResponse();
            assertThat(response).isEqualTo(response).isNotEqualTo(null).isNotEqualTo("text");
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("changing any one payload item breaks equality")
        void everyPayloadItemParticipatesInEquality(ScreenField field) {
            TransactionViewResponse mutated = new TransactionViewResponse();
            mutated.setOutputItem(field, "Z");
            assertThat(mutated).isNotEqualTo(new TransactionViewResponse());
        }

        @ParameterizedTest
        @MethodSource("com.vsergeychik.carddemo.transaction.dto."
                + "TransactionViewResponseTest#nonPayloadMutators")
        @DisplayName("changing any non-payload item also breaks equality")
        void everyNonPayloadItemParticipatesInEquality(
                String description, Consumer<TransactionViewResponse> mutator) {
            TransactionViewResponse mutated = new TransactionViewResponse();
            mutator.accept(mutated);
            assertThat(mutated).as(description).isNotEqualTo(new TransactionViewResponse());
        }

        @Test
        @DisplayName("toString quotes every payload item so trailing spaces are visible")
        void toStringQuotesEveryItem() {
            String text = new TransactionViewResponse().toString();
            assertThat(text).startsWith("TransactionViewResponse[")
                    .contains("CT02/COTRN02C")
                    .contains("COTRN02.COTRN2A")
                    .contains("nextProgram='")
                    .contains("CDEMO-CT02-TRNID-FIRST");
            for (ScreenField field : ScreenField.values()) {
                assertThat(text).as(field.outputItemName())
                        .contains(field.outputItemName() + "='");
            }
        }
    }

    // =============================================================================================
    // The sources as the oracle. Everything below is read from app/cpy-bms, app/bms, app/cbl,
    // app/cpy, app/csd and README.md - and written to none of them (practice B3, gate G5). These are
    // the assertions that would catch a mistranscription in this very file, which is why they parse
    // the sources instead of restating them.
    // =============================================================================================

    @Nested
    @DisplayName("The sources as oracle - copybook, mapset, program, CSD and README (gates G9, G5)")
    class SourceOracle {

        @Test
        @DisplayName("the AI and AO views declare the same 21 stems, in order, at the same widths")
        void aiAndAoAliasNameForName() throws IOException {
            Map<String, Integer> inbound = parseGroupItems("COTRN2AI", "I");
            Map<String, Integer> outbound = parseGroupItems("COTRN2AO", "O");

            assertThat(inbound).as("02 xxxI PIC X(n) items of 01 COTRN2AI").hasSize(21);
            assertThat(outbound).as("02 xxxO PIC X(n) items of 01 COTRN2AO").hasSize(21);

            List<String> inboundStems = inbound.keySet().stream()
                    .map(name -> name.substring(0, name.length() - 1)).toList();
            List<String> outboundStems = outbound.keySet().stream()
                    .map(name -> name.substring(0, name.length() - 1)).toList();

            assertThat(outboundStems).as("the AO overlay is byte-identical to the AI overlay")
                    .containsExactlyElementsOf(inboundStems);
            assertThat(outbound.values()).containsExactlyElementsOf(inbound.values());
            assertThat(inboundStems)
                    .containsExactlyElementsOf(Stream.of(ScreenField.values())
                            .map(ScreenField::label).toList());
        }

        @Test
        @DisplayName("the copybook's own widths sum to 396, and 12 + 21*7 + 396 = 555")
        void copybookWidthsSumTo396AndTheImageIs555() throws IOException {
            Map<String, Integer> outbound = parseGroupItems("COTRN2AO", "O");

            assertThat(outbound).containsExactlyEntriesOf(copybookWidths());
            int payload = outbound.values().stream().mapToInt(Integer::intValue).sum();
            assertThat(payload).isEqualTo(396)
                    .isEqualTo(TransactionViewResponse.PAYLOAD_LENGTH);
            assertThat(12 + 21 * 7 + payload).isEqualTo(555)
                    .isEqualTo(TransactionViewResponse.SYMBOLIC_MAP_LENGTH);
        }

        @Test
        @DisplayName("app/cpy-bms/COTRN02.CPY:145 is 01 COTRN2AO REDEFINES COTRN2AI")
        void theRedefinesHeaderSitsAtLine145() throws IOException {
            assertThat(line(COPYBOOK, 145).strip())
                    .isEqualTo("01  COTRN2AO REDEFINES COTRN2AI.");
            assertThat(line(COPYBOOK, 17).strip()).isEqualTo("01  COTRN2AI.");
            assertThat(line(COPYBOOK, 146).strip()).as("the TIOAPFX=YES prefix")
                    .isEqualTo("02  FILLER PIC X(12).");
        }

        @Test
        @DisplayName("risk R-B: neither view declares TRNIDIN, TRNIDINO or TRNIDO")
        void noTransactionIdItemExistsInEitherView() throws IOException {
            String copybook = String.join("\n", lines(COPYBOOK));

            assertThat(copybook).as("the id of a new transaction is generated by browsing TRANSACT "
                    + "backward for the highest key, so it is never typed and never echoed into an "
                    + "id field - COTRN01 has TRNIDIN/TRNIDO, this map has neither")
                    .doesNotContain("TRNIDIN")
                    .doesNotContain("TRNIDO")
                    .doesNotContain("TRNIDI");
            assertThat(copybook).as("what COTRN01 does not have, in exchange")
                    .contains("ACTIDINO  PIC X(11)")
                    .contains("CARDNINO  PIC X(16)")
                    .contains("CONFIRMO  PIC X(1)");
        }

        @Test
        @DisplayName("the mapset declares 61 DFHMDF fields: 21 named and 40 unnamed captions")
        void theMapsetDeclares61Fields() throws IOException {
            long total = mapsetStatements().stream()
                    .filter(statement -> statement.contains("DFHMDF")).count();
            Map<String, String> named = namedFieldStatements();

            assertThat(total).isEqualTo(61);
            assertThat(named).hasSize(21);
            assertThat(total - named.size()).as("unnamed literal and caption fields, which have no "
                    + "symbolic-map item and therefore carry no payload field").isEqualTo(40);
            assertThat(named.keySet())
                    .containsExactlyElementsOf(Stream.of(ScreenField.values())
                            .map(ScreenField::label).toList());
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("gate G9 - every payload field traces to a DFHMDF whose LENGTH is its width")
        void everyPayloadFieldTracesToADfhmdf(ScreenField field) throws IOException {
            String statement = namedFieldStatements().get(field.label());

            assertThat(statement).as("DFHMDF for %s", field.label()).isNotNull();
            assertThat(bmsInt(statement, "LENGTH")).as("%s LENGTH=", field.label())
                    .isEqualTo(field.width())
                    .isEqualTo(copybookWidths().get(field.outputItemName()));
        }

        @Test
        @DisplayName("exactly 14 named fields are UNPROT, and they are exactly the input fields")
        void exactly14NamedFieldsAreUnprot() throws IOException {
            Map<String, String> named = namedFieldStatements();
            List<String> unprotected = named.entrySet().stream()
                    .filter(entry -> entry.getValue().contains("UNPROT"))
                    .map(Map.Entry::getKey)
                    .toList();

            assertThat(unprotected).hasSize(14)
                    .containsExactlyElementsOf(INPUT_FIELDS.stream().map(ScreenField::label)
                            .toList());
            for (ScreenField field : ScreenField.values()) {
                assertThat(field.input()).as("%s input()", field.label())
                        .isEqualTo(unprotected.contains(field.label()));
            }
        }

        @Test
        @DisplayName("app/bms/COTRN02.bms:293 declares ERRMSG ASKIP, BRT, FSET, red, 78, at (23,1)")
        void theErrorLineIsOutputOnlyAndRed() throws IOException {
            assertThat(line(MAPSET, 293)).contains("ERRMSG").contains("DFHMDF")
                    .contains("ATTRB=(ASKIP,BRT,FSET)");

            String statement = namedFieldStatements().get("ERRMSG");
            assertThat(statement).contains("COLOR=RED").contains("POS=(23,1)")
                    .doesNotContain("UNPROT");
            assertThat(bmsInt(statement, "LENGTH")).isEqualTo(78)
                    .isEqualTo(TransactionViewResponse.ERRMSGO_LENGTH);

            // ASKIP is the whole point: the error line is written by the program and can never be
            // typed into, so it is output-only and CSSETATY never highlights it.
            assertThat(ScreenField.ERRMSG.input()).isFalse();

            // Its red is declared by the map, not moved by CSSETATY: a fresh response leaves the
            // ERRMSGC attribute item at DFHDFCOL and the field is still red on the screen.
            assertThat(new TransactionViewResponse().getMetadata(ScreenField.ERRMSG).getColour())
                    .isEqualTo(BmsAttributes.DFHDFCOL);
        }

        @Test
        @DisplayName("DFHMSD names mapset COTRN02 and DFHMDI names map COTRN2A at SIZE=(24,80)")
        void theMapsetAndMapAreNamedBySource() throws IOException {
            List<String> statements = mapsetStatements();
            String mapsetDefinition = statements.stream()
                    .filter(statement -> statement.contains("DFHMSD")).findFirst().orElseThrow();
            String mapDefinition = statements.stream()
                    .filter(statement -> statement.contains("DFHMDI")).findFirst().orElseThrow();

            assertThat(mapsetDefinition).startsWith("COTRN02 DFHMSD")
                    .contains("CTRL=(ALARM,FREEKB)").contains("EXTATT=YES").contains("MODE=INOUT")
                    .contains("TIOAPFX=YES");
            assertThat(mapDefinition).startsWith("COTRN2A DFHMDI").contains("SIZE=(24,80)");
            assertThat(TransactionViewResponse.MAPSET_NAME).isEqualTo("COTRN02");
            assertThat(TransactionViewResponse.MAP_NAME).isEqualTo("COTRN2A");
        }

        @Test
        @DisplayName("risk R-B evidence 1 and 2: COTRN02C.cbl:5 and README.md:224 both say ADD")
        void theProgramHeaderAndTheReadmeBothSayAdd() throws IOException {
            assertThat(line(PROGRAM, 5))
                    .contains("Function    : Add a new Transaction to TRANSACT file");
            assertThat(line(README, 224)).contains("CT02").contains("COTRN02")
                    .contains("COTRN02C").contains("Transaction Add");

            // And the type is nonetheless named "View", by rule R1. Documented, not corrected.
            assertThat(TransactionViewResponse.class.getSimpleName())
                    .isEqualTo("TransactionViewResponse");
            assertThat(TransactionViewResponse.PROGRAM_ID).isEqualTo("COTRN02C");
            assertThat(TransactionViewResponse.TRANSACTION_ID).isEqualTo("CT02");
        }

        @Test
        @DisplayName("gate G40 - COTRN02C has exactly one XCTL, at :509, driven by CDEMO-TO-PROGRAM")
        void theProgramHasExactlyOneXctlSite() throws IOException {
            assertThat(countLinesContaining(PROGRAM, "XCTL")).isOne();
            assertThat(line(PROGRAM, 509).strip()).isEqualTo("XCTL PROGRAM(CDEMO-TO-PROGRAM)");
        }

        @Test
        @DisplayName("COTRN02C moves -1 into a cursor length at 35 sites, all of them input fields")
        void the35CursorMovesAllNameInputFields() throws IOException {
            Pattern site = Pattern.compile("MOVE -1\\s+TO\\s+([A-Z0-9]+)L OF COTRN2AI");
            List<String> targets = new ArrayList<>();
            for (String text : lines(PROGRAM)) {
                Matcher matched = site.matcher(text);
                if (matched.find()) {
                    targets.add(matched.group(1));
                }
            }

            assertThat(countLinesContaining(PROGRAM, "MOVE -1")).isEqualTo(35);
            assertThat(targets).hasSize(35);
            assertThat(targets).allSatisfy(label ->
                    assertThat(ScreenField.ofLabel(label).input())
                            .as("%s is UNPROT, so a cursor can be placed on it", label).isTrue());
            assertThat(targets).doesNotContainAnyElementsOf(
                    OUTPUT_ONLY_FIELDS.stream().map(ScreenField::label).toList());
        }

        @Test
        @DisplayName("COTRN02C:72-80 declares the 58-byte extension, so the commarea is 218")
        void theCommareaExtensionSumsTo58() throws IOException {
            Pattern item = Pattern.compile("^\\s*10\\s+(CDEMO-CT02-[A-Z0-9-]+)\\s+PIC\\s+([X9])"
                    + "\\((\\d+)\\)");
            Map<String, Integer> items = new LinkedHashMap<>();
            for (String text : lines(PROGRAM).subList(71, 80)) {
                Matcher matched = item.matcher(text);
                if (matched.find()) {
                    items.put(matched.group(1), Integer.parseInt(matched.group(3)));
                }
            }

            assertThat(items).containsExactly(
                    Map.entry("CDEMO-CT02-TRNID-FIRST", 16),
                    Map.entry("CDEMO-CT02-TRNID-LAST", 16),
                    Map.entry("CDEMO-CT02-PAGE-NUM", 8),
                    Map.entry("CDEMO-CT02-NEXT-PAGE-FLG", 1),
                    Map.entry("CDEMO-CT02-TRN-SEL-FLG", 1),
                    Map.entry("CDEMO-CT02-TRN-SELECTED", 16));
            assertThat(items.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(58)
                    .isEqualTo(Ct02Info.LENGTH);
            assertThat(line(PROGRAM, 71).strip()).isEqualTo("COPY COCOM01Y.");
            assertThat(NavigationContext.COMMAREA_LENGTH + Ct02Info.LENGTH).isEqualTo(218)
                    .isEqualTo(Ct02Info.PASSED_COMMAREA_LENGTH);
        }

        @Test
        @DisplayName("COTRN02C:53 declares PIC +99999999.99, which is 12 characters wide")
        void theEditedAmountPictureIs12Characters() throws IOException {
            String declaration = line(PROGRAM, 53);

            assertThat(declaration).contains("WS-TRAN-AMT").contains("PIC +99999999.99");
            assertThat("+99999999.99").hasSize(12)
                    .hasSize(TransactionViewResponse.TRNAMTO_LENGTH);

            // The program keeps its own numeric copies of the two identifiers, at their own
            // pictures; the MAP items stay alphanumeric, so nothing here becomes numeric.
            assertThat(line(PROGRAM, 55)).contains("WS-ACCT-ID-N").contains("PIC 9(11)");
            assertThat(line(PROGRAM, 56)).contains("WS-CARD-NUM-N").contains("PIC 9(16)");
            assertThat(ScreenField.ACTIDIN.width()).isEqualTo(11);
            assertThat(ScreenField.CARDNIN.width()).isEqualTo(16);
        }

        @Test
        @DisplayName("the CSD defines MAPSET(COTRN02):153, PROGRAM(COTRN02C):271, TRANSACTION(CT02):439")
        void theCsdNamesThisScreenAtTheCitedLines() throws IOException {
            assertThat(line(CSD, 153)).contains("DEFINE MAPSET(COTRN02)").contains("GROUP(CARDDEMO)");
            assertThat(line(CSD, 271)).contains("DEFINE PROGRAM(COTRN02C)")
                    .contains("GROUP(CARDDEMO)");
            assertThat(line(CSD, 439)).contains("DEFINE TRANSACTION(CT02)")
                    .contains("GROUP(CARDDEMO)");
            assertThat(line(CSD, 440)).as("CT02 is wired to COTRN02C by the CSD, not by this type")
                    .contains("PROGRAM(COTRN02C)");
        }

        @Test
        @DisplayName("gate G33 does not apply - COTRN02 declares no OCCURS table to index")
        void noOccursTableExistsOnThisMap() throws IOException {
            assertThat(String.join("\n", lines(COPYBOOK)))
                    .as("the add screen composes one transaction rather than paging a list, so "
                            + "there is no one-based-to-zero-based index conversion to verify")
                    .doesNotContain("OCCURS");
            assertThat(TransactionViewResponse.FIELD_COUNT).isEqualTo(21);
        }

        @Test
        @DisplayName("app/cpy/CVTRA05Y.cpy declares the 350-byte TRAN-RECORD this map is fed from")
        void theTransactionRecordWidthsAreAsTranscribed() throws IOException {
            Map<String, String> pictures = new LinkedHashMap<>();
            Pattern item = Pattern.compile("^\\s*05\\s+([A-Z0-9-]+)\\s+PIC\\s+(\\S+)\\.");
            for (String text : lines(TRAN_COPYBOOK)) {
                Matcher matched = item.matcher(text);
                if (matched.find()) {
                    pictures.put(matched.group(1), matched.group(2));
                }
            }

            assertThat(pictures).contains(
                    Map.entry("TRAN-TYPE-CD", "X(02)"),
                    Map.entry("TRAN-CAT-CD", "9(04)"),
                    Map.entry("TRAN-SOURCE", "X(10)"),
                    Map.entry("TRAN-DESC", "X(100)"),
                    Map.entry("TRAN-AMT", "S9(09)V99"),
                    Map.entry("TRAN-MERCHANT-ID", "9(09)"),
                    Map.entry("TRAN-MERCHANT-NAME", "X(50)"),
                    Map.entry("TRAN-MERCHANT-CITY", "X(50)"),
                    Map.entry("TRAN-MERCHANT-ZIP", "X(10)"),
                    Map.entry("TRAN-CARD-NUM", "X(16)"),
                    Map.entry("TRAN-ORIG-TS", "X(26)"),
                    Map.entry("TRAN-PROC-TS", "X(26)"));
        }
    }

    // =============================================================================================
    // The REDEFINES pair as one span seen two ways (gate G34). These assertions operate on a SINGLE
    // FixedWidthRecord on purpose: a REDEFINES is a claim about shared storage, and the only way to
    // prove shared storage is to write through one view and read through the other over the same
    // bytes. A response -> image -> response round trip would not do it, because toFixedWidth
    // allocates a fresh record whose FILLER spans are re-initialised.
    // =============================================================================================

    @Nested
    @DisplayName("The REDEFINES overlay - one span, two views (gate G34)")
    class RedefinesOverlay {

        /** The {@code COTRN2AI} view, declared independently of the class under test. */
        private final RecordLayout inbound = inboundLayout();

        @Test
        @DisplayName("an independently written AI view lands on the same 555 bytes as the AO view")
        void theTwoViewsHaveIdenticalGeometry() {
            assertThat(inbound.recordLength())
                    .isEqualTo(TransactionViewResponse.SYMBOLIC_MAP_LENGTH).isEqualTo(555);
            assertThat(inbound.spans()).as("one leading FILLER plus L, F, FILLER and I per field")
                    .hasSize(1 + 21 * 4);

            // 2 + 1 + 4 on the AI side and 3 + 1 + 1 + 1 + 1 on the AO side are the same seven bytes.
            assertThat(LENGTH_ITEM_LENGTH + TransactionViewResponse.ATTRIBUTE_ITEM_LENGTH
                    + TransactionViewResponse.ATTRIBUTE_ITEM_COUNT)
                    .isEqualTo(TransactionViewResponse.ATTRIBUTE_PREFIX_FILLER_LENGTH
                            + TransactionViewResponse.ATTRIBUTE_ITEM_COUNT
                            * TransactionViewResponse.ATTRIBUTE_ITEM_LENGTH)
                    .isEqualTo(TransactionViewResponse.PER_FIELD_PREFIX_LENGTH)
                    .isEqualTo(7);
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("xxxI and xxxO are the same n bytes at k+7, and xxxL sits at k")
        void theViewsAgreeOnEveryOffset(ScreenField field) {
            FieldSpans outbound = TransactionViewResponse.FIELD_SPANS.get(field);
            FieldSpan inboundLength = inbound.span(field.label() + "L");
            FieldSpan inboundFlag = inbound.span(field.label() + "F");
            FieldSpan inboundPayload = inbound.span(field.label() + "I");
            int start = inboundLength.offset();

            assertThat(inboundFlag.offset()).as("xxxF sits at k+2").isEqualTo(start + 2);
            assertThat(outbound.colour().offset()).as("xxxC sits at k+3").isEqualTo(start + 3);
            assertThat(outbound.ps().offset()).isEqualTo(start + 4);
            assertThat(outbound.highlight().offset()).isEqualTo(start + 5);
            assertThat(outbound.validn().offset()).isEqualTo(start + 6);
            assertThat(inboundPayload.offset()).as("xxxI and xxxO are the identical span")
                    .isEqualTo(start + 7)
                    .isEqualTo(outbound.output().offset());
            assertThat(inboundPayload.length()).isEqualTo(outbound.output().length())
                    .isEqualTo(field.width());
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("the quad occupies exactly the four bytes the AI view calls FILLER X(4)")
        void theQuadOccupiesTheInboundFiller(ScreenField field) {
            int start = inbound.span(field.label() + "L").offset();
            List<FieldSpan> inboundFillers = inbound.spans().stream()
                    .filter(span -> span.kind().filler())
                    .filter(span -> span.offset() == start + 3)
                    .toList();

            assertThat(inboundFillers).as("the AI view's per-field FILLER X(4) at k+3").hasSize(1);
            assertThat(inboundFillers.get(0).length())
                    .isEqualTo(TransactionViewResponse.ATTRIBUTE_ITEM_COUNT).isEqualTo(4);

            // And the AO view calls the SAME four bytes its four attribute items.
            FieldSpans outbound = TransactionViewResponse.FIELD_SPANS.get(field);
            assertThat(List.of(outbound.colour().offset(), outbound.ps().offset(),
                    outbound.highlight().offset(), outbound.validn().offset()))
                    .containsExactly(start + 3, start + 4, start + 5, start + 6);
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("writing the quad is invisible through the AI view - it lands in its FILLER")
        void writingTheQuadIsInvisibleThroughTheInboundView(ScreenField field) {
            FixedWidthRecord record =
                    FixedWidthRecord.forLayout(TransactionViewResponse.LAYOUT, ASCII);
            TransactionViewResponse response = new TransactionViewResponse();
            response.setOutputItem(field, filled(field));
            response.getMetadata(field).setColour(BmsAttributes.DFHRED);
            response.getMetadata(field).setProgrammedSymbols((byte) 'p');
            response.getMetadata(field).setHighlight(BmsAttributes.DFHBLINK);
            response.getMetadata(field).setValidation((byte) 'v');

            response.writeInto(record);

            int start = inbound.span(field.label() + "L").offset();
            assertThat(record.readBytes(start + 3, 4)).as("the AI view's FILLER X(4) holds the quad")
                    .containsExactly(BmsAttributes.DFHRED, (byte) 'p', BmsAttributes.DFHBLINK,
                            (byte) 'v');
            assertThat(record.readSpan(inbound.span(field.label() + "I")))
                    .as("and the AI view's own payload item is untouched by the quad")
                    .isEqualTo(filled(field));
            assertThat(record.readBytes(start, 3))
                    .as("nothing wrote a cursor length, so the AI view reads spaces at xxxL and xxxF")
                    .containsExactly((byte) ' ', (byte) ' ', (byte) ' ');
        }

        @ParameterizedTest
        @MethodSource("com.vsergeychik.carddemo.transaction.dto."
                + "TransactionViewResponseTest#inputFields")
        @DisplayName("MOVE -1 TO xxxL round trips as -1, and is invisible through the AO view")
        void theCursorLengthRoundTripsAndIsInvisibleOutbound(ScreenField field) {
            // COTRN02C moves -1 into a cursor length at 35 sites - one per input-capable field's
            // validation-failure path, which is the most in this package. COMP PIC S9(4) is signed
            // two-byte binary, so -1 is X'FFFF' and must come back as -1 rather than as 65535.
            FixedWidthRecord record =
                    FixedWidthRecord.forLayout(TransactionViewResponse.LAYOUT, ASCII);
            populated().writeInto(record);
            byte[] imageBefore = record.toByteArray();

            // The group image carries the 105 addressable items and nothing else - navigation, the
            // commarea and the cursor travel in the JSON payload - so the comparison is made between
            // the two projections OF THE IMAGE rather than against the response that produced it.
            TransactionViewResponse projectedBefore =
                    TransactionViewResponse.fromFixedWidth(imageBefore, ASCII);
            int start = inbound.span(field.label() + "L").offset();

            record.writeBytes(start, comp2(-1));

            assertThat(fromComp2(record.readBytes(start, LENGTH_ITEM_LENGTH)))
                    .as("X'FFFF' is -1, not 65535: COMP PIC S9(4) is signed").isEqualTo(-1);
            assertThat(record.readSpanBytes(inbound.span(field.label() + "L")))
                    .containsExactly((byte) 0xFF, (byte) 0xFF);
            assertThat(TransactionViewResponse.fromFixedWidth(record.toByteArray(), ASCII))
                    .as("the AO view calls those bytes FILLER X(3), so all 105 of its items are "
                            + "unchanged")
                    .isEqualTo(projectedBefore);
            assertThat(differingOffsets(imageBefore, record.toByteArray()))
                    .as("a cursor length touches exactly the two bytes at k, both inside the AO "
                            + "view's FILLER X(3)")
                    .containsExactly(start, start + 1);
            assertThat(record.recordLength()).isEqualTo(555);
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("the payload item round trips through both views, in both directions")
        void thePayloadItemAliasesInBothDirections(ScreenField field) {
            FixedWidthRecord record =
                    FixedWidthRecord.forLayout(TransactionViewResponse.LAYOUT, ASCII);
            FieldSpan inboundPayload = inbound.span(field.label() + "I");

            // Outbound then inbound: what the program SENDs through xxxO, it RECEIVEs through xxxI.
            TransactionViewResponse sending = new TransactionViewResponse();
            sending.setOutputItem(field, filled(field));
            sending.writeInto(record);
            assertThat(record.readSpan(inboundPayload)).isEqualTo(filled(field));

            // Inbound then outbound: what arrives in xxxI is what xxxO renders back.
            String received = field.width() == 1 ? "Z" : "Z".repeat(field.width() - 1) + "!";
            record.writeSpan(inboundPayload, received);
            TransactionViewResponse receiving = new TransactionViewResponse();
            receiving.readFrom(record);
            assertThat(receiving.getOutputItem(field)).isEqualTo(received)
                    .hasSize(field.width());
        }

        @Test
        @DisplayName("gate G21 - the leading X(12) and all 21 per-field X(3) FILLERs are spaces")
        void everyFillerSpanIsSpaceFilled() {
            byte[] image = new TransactionViewResponse().toFixedWidth(ASCII);

            assertThat(image).hasSize(555);
            assertThat(new String(image, 0, TransactionViewResponse.TIOAPFX_LENGTH, ASCII))
                    .as("the TIOAPFX=YES prefix of COTRN02.CPY:146").isEqualTo(" ".repeat(12));
            for (ScreenField field : ScreenField.values()) {
                int start = TransactionViewResponse.FIELD_SPANS.get(field).colour().offset() - 3;
                assertThat(new String(image, start,
                        TransactionViewResponse.ATTRIBUTE_PREFIX_FILLER_LENGTH, ASCII))
                        .as("FILLER X(3) before %sC", field.label()).isEqualTo("   ");
            }

            // Dropping any one of the 22 FILLER spans would change this total, which is exactly why
            // they are declared rather than inferred.
            assertThat(TransactionViewResponse.TIOAPFX_LENGTH
                    + 21 * TransactionViewResponse.ATTRIBUTE_PREFIX_FILLER_LENGTH)
                    .isEqualTo(12 + 63).isEqualTo(75);
        }
    }

    // =============================================================================================
    // The CSSETATY highlight matrix (gate G38) - the densest branch surface in this package.
    // =============================================================================================

    /**
     * The four outcomes of {@code app/cpy/CSSETATY.cpy:17-27}, driven across all 14 input-capable
     * fields of this map.
     *
     * <p>An accuracy note, because it would be easy to overclaim: {@code COTRN02C} does
     * <strong>not</strong> {@code COPY CSSETATY} - only {@code COACTUPC} does, at 39 textual sites.
     * What {@code COTRN02C} copies is {@code DFHBMSCA} at {@code :93}, the IBM-supplied attribute
     * constants reproduced in {@link BmsAttributes}, and it drives the rule's gating condition itself:
     * {@code :120-121} latches {@code CDEMO-PGM-REENTER} on re-entry and {@code :507} moves zeros back
     * into {@code CDEMO-PGM-CONTEXT} when it leaves. Gate <strong>G38</strong> is therefore asserted
     * here as a property of this map's 14 input-capable fields - the highlight is reachable in
     * {@code REENTER} and unreachable in {@code ENTER} - with the rule itself living once, in
     * {@link FieldAttributeSetter}.
     */
    @Nested
    @DisplayName("The CSSETATY matrix - 14 input fields x four outcomes (gate G38)")
    class HighlightMatrix {

        @ParameterizedTest(name = "{0}: {1} in {2} -> colour={3}, asterisk={4}")
        @MethodSource("com.vsergeychik.carddemo.transaction.dto."
                + "TransactionViewResponseTest#highlightMatrix")
        @DisplayName("every input field in every state produces exactly the copybook's outcome")
        void theMatrixHoldsForEveryInputField(ScreenField field, FieldValidationState state,
                String context, boolean expectColour, boolean expectAsterisk) {
            boolean reenter = "REENTER".equals(context);
            TransactionViewResponse response = new TransactionViewResponse();
            String typed = filled(field);
            response.setOutputItem(field, typed);

            FieldHighlight highlight = FieldAttributeSetter.resolve(state, reenter, field.label(),
                    TransactionViewResponse.MAP_NAME);
            boolean written = response.applyHighlight(highlight);

            assertThat(written).as("anything written at all").isEqualTo(expectColour);
            assertThat(response.getMetadata(field).getColour()).as("%sC", field.label())
                    .isEqualTo(expectColour ? BmsAttributes.DFHRED : BmsAttributes.DFHDFCOL);
            if (expectAsterisk) {
                assertThat(response.getOutputItem(field)).as("%sO", field.label())
                        .isEqualTo(FieldAttributeSetter.ASTERISK
                                + " ".repeat(field.width() - 1))
                        .hasSize(field.width());
            } else {
                assertThat(response.getOutputItem(field)).as("%sO", field.label())
                        .isEqualTo(typed);
            }

            // Only the named field is affected: the highlight is per field, not per screen.
            for (ScreenField other : ScreenField.values()) {
                if (other != field) {
                    assertThat(response.getMetadata(other).isDefault())
                            .as("%sC untouched", other.label()).isTrue();
                }
            }
        }

        @Test
        @DisplayName("the '*' lands in the OUTPUT item, so a highlight overwrites a payload value - "
                + "and CONFIRMO is X(1), so it replaces the field entirely")
        void theAsteriskOverwritesThePayloadValue() {
            TransactionViewResponse response = new TransactionViewResponse();
            response.setConfirmo("Y");
            assertThat(response.getConfirmo()).isEqualTo("Y");

            response.applyHighlight(FieldAttributeSetter.resolve(FieldValidationState.BLANK, true,
                    ScreenField.CONFIRM.label(), TransactionViewResponse.MAP_NAME));

            assertThat(response.getConfirmo()).as("CONFIRMO is one character wide: the '*' is the "
                            + "whole field, and the 'Y' is gone")
                    .isEqualTo(FieldAttributeSetter.ASTERISK).hasSize(1).isNotEqualTo("Y");
            assertThat(response.getMetadata(ScreenField.CONFIRM).getColour())
                    .isEqualTo(BmsAttributes.DFHRED);

            // The same overwrite on a wide field keeps the declared width, so the group image cannot
            // change size: the '*' is padded to 60 for TDESCO, not written as one loose byte.
            TransactionViewResponse wide = new TransactionViewResponse();
            wide.setTdesco("A DESCRIPTION THE USER TYPED");
            wide.applyHighlight(FieldAttributeSetter.resolve(FieldValidationState.BLANK, true,
                    ScreenField.TDESC.label(), TransactionViewResponse.MAP_NAME));
            assertThat(wide.getTdesco()).hasSize(60).startsWith("*").isEqualTo("*" + " ".repeat(59));
        }

        @ParameterizedTest
        @MethodSource("com.vsergeychik.carddemo.transaction.dto."
                + "TransactionViewResponseTest#outputOnlyFields")
        @DisplayName("the seven output-only fields are not highlight-eligible on this map")
        void theOutputOnlyFieldsAreNotHighlightable(ScreenField field) throws IOException {
            assertThat(field.input()).as("%s is not UNPROT in app/bms/COTRN02.bms", field.label())
                    .isFalse();
            assertThat(namedFieldStatements().get(field.label())).doesNotContain("UNPROT");

            // The program never validates a field it does not accept input for, so it never places a
            // cursor on one either: none of the 35 MOVE -1 sites names any of these seven.
            assertThat(countLinesContaining(PROGRAM,
                    "MOVE -1       TO " + field.label() + "L")).isZero();
        }

        @Test
        @DisplayName("DFHRED comes from BmsAttributes and the '*' from FieldAttributeSetter, never "
                + "from a literal in this suite")
        void theTwoConstantsComeFromTheirOwners() {
            FieldHighlight blank = FieldAttributeSetter.resolve(FieldValidationState.BLANK, true,
                    ScreenField.TRNAMT.label(), TransactionViewResponse.MAP_NAME);

            assertThat(blank.colourItemValue()).isEqualTo(BmsAttributes.DFHRED)
                    .isEqualTo((byte) 0xF2);
            assertThat(blank.outputItemValue()).isEqualTo(FieldAttributeSetter.ASTERISK)
                    .isEqualTo("*");
            assertThat(blank.colourItemName()).isEqualTo("TRNAMTC");
            assertThat(blank.outputItemName()).isEqualTo("TRNAMTO");
            assertThat(blank.outputMapGroupName()).isEqualTo("COTRN2AO");
        }

        @Test
        @DisplayName("COTRN02C owns the REENTER state itself, at :120-121 and :507")
        void theProgramDrivesTheGatingCondition() throws IOException {
            assertThat(line(PROGRAM, 120).strip()).isEqualTo("IF NOT CDEMO-PGM-REENTER");
            assertThat(line(PROGRAM, 121).strip()).isEqualTo("SET CDEMO-PGM-REENTER    TO TRUE");
            assertThat(line(PROGRAM, 507).strip()).isEqualTo("MOVE ZEROS        TO CDEMO-PGM-CONTEXT");
            assertThat(line(PROGRAM, 93).strip()).as("the attribute constants BmsAttributes reproduces")
                    .isEqualTo("COPY DFHBMSCA.");
            assertThat(String.join("\n", lines(PROGRAM)))
                    .as("only COACTUPC copies CSSETATY; this program applies the rule inline")
                    .doesNotContain("CSSETATY");

            // Both CDEMO-PGM-CONTEXT states are expressible, and REENTER is what opens the rule.
            assertThat(NavigationContext.empty().withPgmEnter().isReenter()).isFalse();
            assertThat(NavigationContext.empty().withPgmReenter().isReenter()).isTrue();
            assertThat(NavigationContext.PGM_CONTEXT_ENTER).isZero();
            assertThat(NavigationContext.PGM_CONTEXT_REENTER).isOne();
        }
    }

    // =============================================================================================
    // The header literals and the fixed clock (practices B7 and B8), and the two thank-you literals
    // that are easy to substitute for one another and must never be.
    // =============================================================================================

    @Nested
    @DisplayName("Header literals and a clock that cannot move (practices B7, B8)")
    class HeaderLiteralsAndClock {

        private final FixedWidthCodec codec = new FixedWidthCodec(ASCII);

        @Test
        @DisplayName("a fixed clock renders the identical header twice - the suite cannot flake")
        void aFixedClockIsRepeatable() {
            TransactionViewResponse first = new TransactionViewResponse();
            TransactionViewResponse second = new TransactionViewResponse();

            first.populateHeaderInfo(DateHeader.from(codec, FIXED_CLOCK));
            second.populateHeaderInfo(DateHeader.from(codec, FIXED_CLOCK));

            assertThat(first).isEqualTo(second);
            assertThat(first.getCurdateo()).isEqualTo("07/18/22").hasSize(8);
            assertThat(first.getCurtimeo()).isEqualTo("04:05:06").hasSize(8);
            assertThat(first.toFixedWidth(ASCII)).isEqualTo(second.toFixedWidth(ASCII));
        }

        @ParameterizedTest(name = "{0} -> {1} {2}")
        @CsvSource({
            "UTC,              07/18/22, 04:05:06",
            "America/New_York, 07/18/22, 00:05:06",
            "Asia/Tokyo,       07/18/22, 13:05:06",
        })
        @DisplayName("the zone is stated, never inherited: one instant, three local renderings")
        void theZoneIsAlwaysExplicit(String zone, String expectedDate, String expectedTime) {
            Clock clock = Clock.fixed(FIXED_INSTANT, ZoneId.of(zone.strip()));
            TransactionViewResponse response = new TransactionViewResponse();

            response.populateHeaderInfo(DateHeader.from(codec, clock));

            assertThat(response.getCurdateo()).isEqualTo(expectedDate.strip()).hasSize(8);
            assertThat(response.getCurtimeo()).isEqualTo(expectedTime.strip()).hasSize(8);
        }

        @Test
        @DisplayName("a title is X(40) and a message is X(50): the two thank-yous never substitute")
        void theTwoThankYouLiteralsAreNotInterchangeable() {
            assertThat(ScreenTitles.CCDA_THANK_YOU).hasSize(ScreenTitles.TITLE_LENGTH).hasSize(40);
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU)
                    .hasSize(SystemMessages.MESSAGE_LENGTH).hasSize(50);
            assertThat(ScreenTitles.CCDA_THANK_YOU.stripTrailing())
                    .as("different text, different width, different owning copybook - COTTL01Y "
                            + "against CSMSG01Y")
                    .isNotEqualTo(SystemMessages.CCDA_MSG_THANK_YOU.stripTrailing());

            // The title belongs in TITLE01O/TITLE02O, which are exactly 40 wide, and the message in
            // ERRMSGO, which is 78. Neither field is the other's size.
            assertThat(TransactionViewResponse.TITLE01O_LENGTH)
                    .isEqualTo(TransactionViewResponse.TITLE02O_LENGTH)
                    .isEqualTo(ScreenTitles.TITLE_LENGTH);
            assertThat(TransactionViewResponse.ERRMSGO_LENGTH)
                    .isGreaterThan(SystemMessages.MESSAGE_LENGTH).isEqualTo(78);
        }

        @Test
        @DisplayName("a 50-byte message moved into the 78-byte error line is padded on the right")
        void aMessageIsRightPaddedIntoTheErrorLine() {
            TransactionViewResponse response = new TransactionViewResponse();

            response.setErrmsgo(SystemMessages.CCDA_MSG_THANK_YOU);

            String rendered = response.getErrmsgo();
            assertThat(rendered).hasSize(78);
            assertThat(rendered.substring(0, SystemMessages.MESSAGE_LENGTH))
                    .isEqualTo(SystemMessages.CCDA_MSG_THANK_YOU);
            assertThat(rendered.substring(SystemMessages.MESSAGE_LENGTH))
                    .as("28 trailing spaces, not a truncation and not a trim").isEqualTo(" ".repeat(28));

            // The same holds for the invalid-key message the program sends at COTRN02C:150.
            TransactionViewResponse invalid = new TransactionViewResponse();
            invalid.setErrmsgoInvalidKey();
            assertThat(invalid.getErrmsgo()).hasSize(78)
                    .startsWith(SystemMessages.CCDA_MSG_INVALID_KEY);
        }

        @Test
        @DisplayName("the two titles reach the map at exactly 40 characters each")
        void bothTitlesAreRenderedAtFortyCharacters() {
            TransactionViewResponse response = new TransactionViewResponse();

            response.populateHeaderInfo(DateHeader.from(codec, FIXED_CLOCK));

            assertThat(response.getTitle01o()).isEqualTo(ScreenTitles.CCDA_TITLE01).hasSize(40);
            assertThat(response.getTitle02o()).isEqualTo(ScreenTitles.CCDA_TITLE02).hasSize(40);
            assertThat(response.getTrnnameo()).isEqualTo(TransactionViewResponse.TRANSACTION_ID);
            assertThat(response.getPgmnameo()).isEqualTo(TransactionViewResponse.PROGRAM_ID);
        }
    }

    // =============================================================================================
    // Cross-width MOVEs from the 350-byte TRAN-RECORD into this map (rule R5). Every one of them goes
    // through FixedWidthCodec.movePicX or movePic9, so the DIRECTION of the loss is chosen at the call
    // site instead of being whatever a Java assignment happens to do - which is nothing.
    // =============================================================================================

    @Nested
    @DisplayName("Cross-width MOVEs from CVTRA05Y's TRAN-RECORD (rule R5)")
    class TranRecordMoves {

        private final FixedWidthCodec codec = new FixedWidthCodec(ASCII);

        /**
         * A distinguishable sending value of a given width, so a truncation is visible in the result.
         *
         * @param width the sender's declared width
         * @return a value of exactly {@code width} characters ending in a marker
         */
        private String sending(int width) {
            StringBuilder text = new StringBuilder(width);
            while (text.length() < width - 1) {
                text.append((char) ('A' + text.length() % 26));
            }
            return text.append('#').toString();
        }

        @ParameterizedTest(name = "{0} X({1}) -> {2}O")
        @MethodSource("com.vsergeychik.carddemo.transaction.dto."
                + "TransactionViewResponseTest#alphanumericMoves")
        @DisplayName("a PIC X move keeps the leftmost characters and never the rightmost")
        void alphanumericMovesTruncateOnTheRight(String sender, int senderWidth,
                ScreenField receiver) {
            String value = sending(senderWidth);
            assertThat(value).as(sender).hasSize(senderWidth);

            String moved = codec.movePicX(value, receiver.width());

            assertThat(moved).hasSize(receiver.width())
                    .isEqualTo(value.substring(0, Math.min(senderWidth, receiver.width())));
            if (senderWidth > receiver.width()) {
                assertThat(moved).as("%s discards its tail, never its head", sender)
                        .doesNotEndWith("#");
            }

            TransactionViewResponse response = new TransactionViewResponse();
            response.setOutputItem(receiver, moved);
            assertThat(response.getOutputItem(receiver)).isEqualTo(moved)
                    .hasSize(receiver.width());
        }

        @Test
        @DisplayName("the five genuinely truncating moves: 100->60, 50->30, 50->25, 26->10, 26->10")
        void theFiveTruncatingMovesKeepTheLeadingCharacters() {
            assertThat(codec.movePicX("X".repeat(100), ScreenField.TDESC.width()))
                    .as("TRAN-DESC X(100) into TDESCO X(60)").hasSize(60);
            assertThat(codec.movePicX("MERCHANT NAME THAT IS FIFTY CHARACTERS LONG-PADDED",
                    ScreenField.MNAME.width()))
                    .as("TRAN-MERCHANT-NAME X(50) into MNAMEO X(30)")
                    .isEqualTo("MERCHANT NAME THAT IS FIFTY CH").hasSize(30);
            assertThat(codec.movePicX("A CITY NAME OCCUPYING FIFTY CHARACTERS OF STORAGE..",
                    ScreenField.MCITY.width()))
                    .as("TRAN-MERCHANT-CITY X(50) into MCITYO X(25)")
                    .isEqualTo("A CITY NAME OCCUPYING FIF").hasSize(25);
            assertThat(codec.movePicX("2022-07-18 04.05.06.123456", ScreenField.TORIGDT.width()))
                    .as("TRAN-ORIG-TS X(26) into TORIGDTO X(10) keeps the date, drops the time")
                    .isEqualTo("2022-07-18").hasSize(10);
            assertThat(codec.movePicX("2022-07-19 23.16.01.000000", ScreenField.TPROCDT.width()))
                    .as("TRAN-PROC-TS X(26) into TPROCDTO X(10)")
                    .isEqualTo("2022-07-19").hasSize(10);
        }

        @Test
        @DisplayName("the four exact-width moves are lossless: 16, 10, 2 and 10 characters")
        void theExactWidthMovesAreLossless() {
            assertThat(codec.movePicX("4111111111111111", ScreenField.CARDNIN.width()))
                    .isEqualTo("4111111111111111").hasSize(16);
            assertThat(codec.movePicX("POS       ", ScreenField.TRNSRC.width()))
                    .isEqualTo("POS       ").hasSize(10);
            assertThat(codec.movePicX("01", ScreenField.TTYPCD.width())).isEqualTo("01").hasSize(2);
            assertThat(codec.movePicX("N8L7X3R2P1", ScreenField.MZIP.width()))
                    .isEqualTo("N8L7X3R2P1").hasSize(10);
        }

        @Test
        @DisplayName("a numeric sender zero-fills on the LEFT into an alphanumeric receiver")
        void numericSendersZeroFillOnTheLeft() {
            // TRAN-CAT-CD PIC 9(04) into TCATCDO PIC X(4) and TRAN-MERCHANT-ID PIC 9(09) into MIDO.
            assertThat(codec.movePic9(7L, ScreenField.TCATCD.width())).isEqualTo("0007").hasSize(4);
            assertThat(codec.movePic9(123456L, ScreenField.MID.width()))
                    .isEqualTo("000123456").hasSize(9);

            TransactionViewResponse response = new TransactionViewResponse();
            response.setTcatcdo(codec.movePic9(7L, ScreenField.TCATCD.width()));
            response.setMido(codec.movePic9(123456L, ScreenField.MID.width()));
            assertThat(response.getTcatcdo()).isEqualTo("0007");
            assertThat(response.getMido()).isEqualTo("000123456");
        }

        @Test
        @DisplayName("a short value is padded on the right, so the declared width always holds")
        void shortValuesAreRightSpacePadded() {
            for (ScreenField field : ScreenField.values()) {
                String moved = codec.movePicX("A", field.width());
                assertThat(moved).as(field.outputItemName()).hasSize(field.width())
                        .startsWith("A");
                assertThat(moved.substring(1)).isEqualTo(" ".repeat(field.width() - 1));
            }
        }

        @Test
        @DisplayName("TRAN-AMT is never moved into TRNAMTO directly - it goes through the mask")
        void theAmountIsRenderedThroughTheMaskAndNeverAssigned() {
            // TRAN-AMT PIC S9(09)V99 occupies eleven bytes of zoned storage, sign overpunched into
            // the trailing digit. Moving that image into TRNAMTO would put storage on the screen.
            String digits = codec.movePic9(123450L, 11);
            String zoned = digits.substring(0, 10)
                    + FixedWidthRecord.ZonedSign.overpunch(0, false);
            assertThat(zoned).hasSize(11).endsWith("{");

            String movedRaw = codec.movePicX(zoned, ScreenField.TRNAMT.width());
            assertThat(movedRaw).hasSize(12).isNotEqualTo("+00001234.50");

            // What COTRN02C:53 actually declares is an EDITED field, PIC +99999999.99, and that
            // twelve-character rendering is what the map carries.
            TransactionViewResponse response = new TransactionViewResponse();
            response.setTrnamto("+00001234.50");
            String mask = response.getTrnamto();
            assertThat(mask).hasSize(TransactionViewResponse.TRNAMTO_LENGTH).hasSize(12);
            assertThat(mask.charAt(0)).as("the sign position").isIn('+', '-');
            assertThat(mask.substring(1, 9)).as("eight integer digits").containsOnlyDigits();
            assertThat(mask.charAt(9)).as("the decimal point").isEqualTo('.');
            assertThat(mask.substring(10)).as("two fraction digits").containsOnlyDigits();

            // The mask carries eight integer digits while TRAN-AMT holds nine, so the edited field is
            // narrower than the stored one. That is the source's own choice, preserved unchanged.
            assertThat(9).isGreaterThan(8);
        }
    }

    // =============================================================================================
    // The negatives. Each of these asserts the ABSENCE of something, which is the only way a gate
    // phrased as a prohibition can be tested. The source-text checks strip comments first, so a
    // Javadoc paragraph explaining what is absent cannot be mistaken for the absent thing.
    // =============================================================================================

    @Nested
    @DisplayName("The negatives - what must be absent (gates G22, G24, G37, G44, G52, G53)")
    class Negatives {

        private final ObjectMapper mapper = new ObjectMapper();

        /** The type and the four nested types that together carry everything this response holds. */
        private final List<Class<?>> types = List.of(TransactionViewResponse.class,
                ScreenField.class, FieldMetadata.class, Ct02Info.class, FieldSpans.class);

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("gate G22 - all 21 payload items are String on the way in and on the way out")
        void everyPayloadAccessorIsAString(ScreenField field) throws Exception {
            String getterName = payloadGetterName(field);
            Method getter = TransactionViewResponse.class.getMethod(getterName);
            Method setter = TransactionViewResponse.class
                    .getMethod("set" + getterName.substring(3), String.class);

            assertThat(getter.getReturnType()).as(getterName).isEqualTo(String.class);
            assertThat(setter.getParameterTypes()).as(setter.getName())
                    .containsExactly(String.class);
            assertThat(TransactionViewResponse.class
                    .getMethod("getOutputItem", ScreenField.class).getReturnType())
                    .isEqualTo(String.class);

            // The backing field is a String too, so nothing is converted on the way through: an
            // edited PIC field is characters on the screen and characters in this record.
            assertThat(TransactionViewResponse.class
                    .getDeclaredField(getterName.substring(3, 4).toLowerCase(Locale.ROOT)
                            + getterName.substring(4)).getType())
                    .as("the %s field", field.outputItemName()).isEqualTo(String.class);
        }

        @Test
        @DisplayName("gate G22 - no double, float, Double or Float in any signature or field")
        void noFloatingPointTypeAppearsAnywhere() {
            List<Class<?>> forbidden =
                    List.of(double.class, float.class, Double.class, Float.class);
            for (Class<?> type : types) {
                for (Field field : type.getDeclaredFields()) {
                    assertThat(field.getType()).as("%s.%s", type.getSimpleName(), field.getName())
                            .isNotIn(forbidden);
                }
                for (Method method : type.getDeclaredMethods()) {
                    assertThat(method.getReturnType())
                            .as("%s.%s return", type.getSimpleName(), method.getName())
                            .isNotIn(forbidden);
                    for (Class<?> parameter : method.getParameterTypes()) {
                        assertThat(parameter)
                                .as("%s.%s parameter", type.getSimpleName(), method.getName())
                                .isNotIn(forbidden);
                    }
                }
            }
        }

        @Test
        @DisplayName("gate G53 - every static field is final, and two responses share nothing")
        void thereIsNoStaticMutableState() {
            for (Class<?> type : types) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) {
                        assertThat(Modifier.isFinal(field.getModifiers()))
                                .as("static %s.%s must be final", type.getSimpleName(),
                                        field.getName())
                                .isTrue();
                    }
                }
            }

            TransactionViewResponse first = new TransactionViewResponse();
            TransactionViewResponse second = new TransactionViewResponse();
            first.setErrmsgo("only on the first");
            first.getMetadata(ScreenField.ERRMSG).setColour(BmsAttributes.DFHRED);
            first.getCt02Info().setPageNum(9);

            assertThat(second.getErrmsgo()).isEqualTo(ScreenFieldImage.unpainted(78));
            assertThat(second.getMetadata(ScreenField.ERRMSG).getColour())
                    .isEqualTo(BmsAttributes.DFHDFCOL);
            assertThat(second.getCt02Info().getPageNum()).isZero();
        }

        @Test
        @DisplayName("gate G44 - no persistence annotation on the type or any of its members")
        void noPersistenceAnnotationIsPresent() throws IOException {
            for (Class<?> type : types) {
                assertThat(type.getAnnotations()).allSatisfy(annotation ->
                        assertThat(annotation.annotationType().getName())
                                .doesNotContain("persistence"));
                for (Field field : type.getDeclaredFields()) {
                    assertThat(field.getAnnotations()).allSatisfy(annotation ->
                            assertThat(annotation.annotationType().getName())
                                    .doesNotContain("persistence"));
                }
            }

            String code = stripComments(Files.readString(SOURCE, ASCII));
            assertThat(code).doesNotContain("@Entity").doesNotContain("@Table")
                    .doesNotContain("@Column").doesNotContain("@Id").doesNotContain("@Version")
                    .doesNotContain("jakarta.persistence").doesNotContain("javax.persistence");
        }

        @Test
        @DisplayName("gates G24, G37 - no rounding mode, no session, no ThreadLocal in the code")
        void noForbiddenConstructAppearsInTheCode() throws IOException {
            String code = stripComments(Files.readString(SOURCE, ASCII));

            assertThat(code).as("ROUNDED appears zero times in all 28 programs, so no rounding mode "
                            + "belongs anywhere near this projection")
                    .doesNotContain("RoundingMode").doesNotContain("HALF_UP")
                    .doesNotContain("HALF_EVEN").doesNotContain("CEILING").doesNotContain("FLOOR");
            // Nothing about static factory methods is forbidden - newMetadataMap() is one - so this
            // check names the constructs that would actually hold state between requests, and gate
            // G53's real enforcement is the reflective every-static-field-is-final test above.
            assertThat(code).as("state travels in the payload, never on the server")
                    .doesNotContain("HttpSession").doesNotContain("SessionAttributes")
                    .doesNotContain("ThreadLocal").doesNotContain("@SessionScope")
                    .doesNotContain("lombok").doesNotContain("mapstruct");
            assertThat(code).doesNotContainPattern("\\bdouble\\b")
                    .doesNotContainPattern("\\bfloat\\b");
        }

        @Test
        @DisplayName("gate G52 - neither the class nor this suite uses a wildcard import, and this "
                + "suite imports nothing from the parity harness")
        void everyImportIsExplicit() throws IOException {
            for (Path path : List.of(SOURCE, TEST_SOURCE)) {
                List<String> imports = lines(path).stream()
                        .filter(text -> text.startsWith("import ")).toList();
                assertThat(imports).as("%s declares imports", path).isNotEmpty();
                assertThat(imports).as("%s uses no wildcard import", path)
                        .allSatisfy(text -> assertThat(text).doesNotContain(".*;"));
            }

            List<String> testImports = lines(TEST_SOURCE).stream()
                    .filter(text -> text.startsWith("import ")).toList();
            assertThat(testImports).allSatisfy(text -> assertThat(text)
                    .doesNotContain("carddemo.parity")
                    .doesNotContain("card.dto.CardScreenState")
                    .doesNotContain("MockMvc")
                    .doesNotContain("SpringBootTest")
                    .doesNotContain("WebMvcTest"));
            // The two needles are assembled from fragments on purpose: spelled out as literals they
            // would appear in this file's own source and the assertion would fail against itself.
            String parityFixtures = String.join("/", "src", "test", "resources", "parity");
            String parityCaseType = "Parity" + "Case";
            assertThat(stripComments(Files.readString(TEST_SOURCE, ASCII)))
                    .as("no parity fixture and no parity harness type is reached by this suite")
                    .doesNotContain(parityFixtures).doesNotContain(parityCaseType);
        }

        @Test
        @DisplayName("every file this suite reads is one of the nine declared, read-only sources")
        void theSuiteReadsOnlyTheDeclaredSources() throws Exception {
            List<Path> declared = new ArrayList<>();
            for (Field field : TransactionViewResponseTest.class.getDeclaredFields()) {
                if (field.getType() == Path.class) {
                    field.setAccessible(true);
                    declared.add((Path) field.get(null));
                }
            }

            assertThat(declared).containsExactlyInAnyOrder(COPYBOOK, MAPSET, PROGRAM, TRAN_COPYBOOK,
                    CSD, README, SOURCE, TEST_SOURCE);
            assertThat(declared).allSatisfy(path -> {
                assertThat(Files.isReadable(path)).as("%s is readable", path).isTrue();
                assertThat(Files.isRegularFile(path)).as("%s is a file", path).isTrue();
            });
        }

        @Test
        @DisplayName("the JSON body names the 21 payload items and none of the 147 metadata items")
        void theJsonBodyCarriesNoMetadataName() throws Exception {
            List<String> members = new ArrayList<>();
            mapper.valueToTree(populated()).fieldNames().forEachRemaining(members::add);

            for (ScreenField field : ScreenField.values()) {
                assertThat(members)
                        .as("%s is a payload member", field.outputItemName())
                        .contains(withoutDirectionSuffix(field.outputItemName()));
                assertThat(members).as("%s metadata must not be a payload member", field.label())
                        .doesNotContain(field.label().toLowerCase(Locale.ROOT) + "l",
                                field.label().toLowerCase(Locale.ROOT) + "f",
                                field.label().toLowerCase(Locale.ROOT) + "a",
                                field.label().toLowerCase(Locale.ROOT) + "c",
                                field.label().toLowerCase(Locale.ROOT) + "p",
                                field.label().toLowerCase(Locale.ROOT) + "h",
                                field.label().toLowerCase(Locale.ROOT) + "v");
            }
            assertThat(members).as("the quads are addressable in the image, never in the payload")
                    .doesNotContain("metadata");
        }

        @Test
        @DisplayName("gate G37 - the response carries the whole conversation, and it survives a "
                + "round trip through bytes in two code pages")
        void everyPieceOfStateTravelsInThePayload() {
            TransactionViewResponse response = populated();

            assertThat(response.getNavigationContext().toFixedWidth(new FixedWidthCodec(ASCII)))
                    .as("COCOM01Y is 160 bytes and this response never widens it").hasSize(160);
            assertThat(Ct02Info.PASSED_COMMAREA_LENGTH)
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH + Ct02Info.LENGTH);
            assertThat(TransactionViewResponse.fromFixedWidth(
                    response.toFixedWidth(EBCDIC), EBCDIC).getErrmsgo())
                    .as("the code page is the caller's choice, and neither is the platform default")
                    .isEqualTo(response.getErrmsgo());
            assertThat(response.toFixedWidth(EBCDIC))
                    .as("and the two encodings genuinely differ in bytes")
                    .isNotEqualTo(response.toFixedWidth(ASCII));
        }
    }

    /**
     * The 14 input-capable fields, as a parameter source.
     *
     * @return one argument per {@code UNPROT} field
     */
    static Stream<ScreenField> inputFields() {
        return INPUT_FIELDS.stream();
    }

    /**
     * The seven output-only fields, as a parameter source.
     *
     * @return one argument per protected field
     */
    static Stream<ScreenField> outputOnlyFields() {
        return OUTPUT_ONLY_FIELDS.stream();
    }

    /**
     * The full {@code CSSETATY} decision matrix: the 14 input-capable fields crossed with the state
     * and context combinations that reach each of the copybook's four outcomes.
     *
     * <p>Six combinations per field rather than four, because the {@code ENTER} row of the truth table
     * is reached by <em>both</em> failing flags and the "left alone" row by {@code OK} in either
     * context. Every one of the three conditions at {@code CSSETATY.cpy:18-23} is therefore driven
     * true and false, which is what gate <strong>G49</strong>'s branch counter measures - 84 cases in
     * place of 56 hand-written methods.
     *
     * @return field, validation state, context, whether {@code xxxC} is assigned, whether {@code xxxO}
     *         is overwritten with the asterisk
     */
    static Stream<Arguments> highlightMatrix() {
        List<Arguments> cases = new ArrayList<>(INPUT_FIELDS.size() * 6);
        for (ScreenField field : INPUT_FIELDS) {
            // Outer test passes: NOT-OK under re-entry colours the field and leaves what was typed.
            cases.add(Arguments.of(field, FieldValidationState.NOT_OK, "REENTER", true, false));
            // Outer and inner test pass: BLANK under re-entry colours AND stars the field.
            cases.add(Arguments.of(field, FieldValidationState.BLANK, "REENTER", true, true));
            // Outer test fails on its AND: neither flag matters in ENTER context.
            cases.add(Arguments.of(field, FieldValidationState.NOT_OK, "ENTER", false, false));
            cases.add(Arguments.of(field, FieldValidationState.BLANK, "ENTER", false, false));
            // Outer test fails on its OR: a valid field is never touched, in either context.
            cases.add(Arguments.of(field, FieldValidationState.OK, "REENTER", false, false));
            cases.add(Arguments.of(field, FieldValidationState.OK, "ENTER", false, false));
        }
        return cases.stream();
    }

    /**
     * The cross-width {@code MOVE}s from {@code app/cpy/CVTRA05Y.cpy}'s 350-byte {@code TRAN-RECORD}
     * into this map's payload items, each with the sending field's declared width.
     *
     * @return sending field name, sending width, receiving screen field
     */
    static Stream<Arguments> alphanumericMoves() {
        return Stream.of(
                Arguments.of("TRAN-CARD-NUM", 16, ScreenField.CARDNIN),
                Arguments.of("TRAN-TYPE-CD", 2, ScreenField.TTYPCD),
                Arguments.of("TRAN-SOURCE", 10, ScreenField.TRNSRC),
                Arguments.of("TRAN-DESC", 100, ScreenField.TDESC),
                Arguments.of("TRAN-MERCHANT-NAME", 50, ScreenField.MNAME),
                Arguments.of("TRAN-MERCHANT-CITY", 50, ScreenField.MCITY),
                Arguments.of("TRAN-MERCHANT-ZIP", 10, ScreenField.MZIP),
                Arguments.of("TRAN-ORIG-TS", 26, ScreenField.TORIGDT),
                Arguments.of("TRAN-PROC-TS", 26, ScreenField.TPROCDT));
    }

    static Stream<Arguments> nonPayloadMutators() {
        return Stream.of(
                Arguments.of("nextProgram",
                        (Consumer<TransactionViewResponse>) r -> r.setNextProgram("COMEN01C")),
                Arguments.of("nextMapset",
                        (Consumer<TransactionViewResponse>) r -> r.setNextMapset("COTRN01")),
                Arguments.of("nextMap",
                        (Consumer<TransactionViewResponse>) r -> r.setNextMap("COTRN1A")),
                Arguments.of("navigationContext",
                        (Consumer<TransactionViewResponse>) r -> r.setNavigationContext(
                                NavigationContext.empty().withToProgram("COMEN01C"))),
                Arguments.of("ct02Info",
                        (Consumer<TransactionViewResponse>) r -> r.getCt02Info().setPageNum(2)),
                Arguments.of("metadata",
                        (Consumer<TransactionViewResponse>) r -> r.getMetadata(ScreenField.TRNAMT)
                                .setColour(BmsAttributes.DFHRED)));
    }

    /**
     * A copybook item name rendered as the JSON member it is published under: the item without its
     * output-direction suffix, lower-cased.
     *
     * <p>{@code @JsonProperty} pins every screen field's wire name to its {@code xxxI} item in lower
     * case, which is the one naming rule AAP 0.6.3 states - "payload field names and lengths derive from
     * the xxxI items only". The Java accessor keeps the {@code xxxO} spelling, because that is the map
     * view the type projects; the wire name does not, because the paired request has to accept this
     * response back field for field.
     *
     * @param itemName a symbolic-map item name such as {@code TRNNAMEO} or {@code TRNNAMEI}
     * @return the JSON member name, such as {@code trnname}
     */
    private static String withoutDirectionSuffix(String itemName) {
        return itemName.substring(0, itemName.length() - 1).toLowerCase(Locale.ROOT);
    }
}
