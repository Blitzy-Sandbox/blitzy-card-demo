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
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Parity tests for {@link ReportRequestRequest}, the inbound payload of {@code POST /api/reports}
 * projected from {@code 01 CORPT0AI} of {@code app/cpy-bms/CORPT00.CPY}.
 *
 * <p>The suite reads the read-only sources from disk and asserts the Java against them, rather than
 * against a transcription. That is deliberate: a hand-copied expectation can encode the same
 * misreading as the code it checks, whereas the copybook and the mapset cannot disagree with
 * themselves. Four oracles are used:
 *
 * <ol>
 *   <li>{@code app/cpy-bms/CORPT00.CPY} - the seventeen {@code xxxI} item names and
 *       {@code PICTURE} clauses, the {@code xxxL} / {@code xxxF} / {@code xxxA} metadata items and
 *       every {@code FILLER};</li>
 *   <li>{@code app/bms/CORPT00.bms} - the seventeen name-labelled {@code DFHMDF} entries, their
 *       {@code LENGTH=} and their {@code ATTRB} lists;</li>
 *   <li>{@code app/cbl/CORPT00C.cbl} - the twenty-two cursor moves, the numeric-typing evidence and
 *       the absence of a communication-area extension;</li>
 *   <li>{@code app/cpy/COCOM01Y.cpy} - by way of {@link NavigationContext}, the 160-byte
 *       communication area.</li>
 * </ol>
 *
 * <p>Nothing here writes to any of them.
 */
@DisplayName("ReportRequestRequest - CORPT00 symbolic map as the POST /api/reports payload")
class ReportRequestRequestTest {

    private static final String SYMBOLIC_MAP_PATH = "app/cpy-bms/CORPT00.CPY";
    private static final String MAPSET_PATH = "app/bms/CORPT00.bms";
    private static final String PROGRAM_PATH = "app/cbl/CORPT00C.cbl";

    /** {@code 02  xxxI  PIC X(n).} - the payload items of the {@code AI} group. */
    private static final Pattern INPUT_ITEM =
            Pattern.compile("^\\s*02\\s+(\\S*[A-Z0-9]I)\\s+PIC\\s+X\\((\\d+)\\)\\.");

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

    /** The {@code 01 CORPT0AI} group of the symbolic map, lines 17 to 120 inclusive. */
    private static List<String> inputGroup;

    private static List<String> mapset;
    private static List<String> program;

    @BeforeAll
    static void readOracles() {
        Path root = repositoryRoot();
        inputGroup = inputGroupOf(readLines(root.resolve(SYMBOLIC_MAP_PATH)));
        mapset = readLines(root.resolve(MAPSET_PATH));
        program = readLines(root.resolve(PROGRAM_PATH));
    }

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

    /** Item name to declared width, in copybook declaration order, for one pattern. */
    private static Map<String, Integer> itemsMatching(Pattern pattern) {
        Map<String, Integer> items = new LinkedHashMap<>();
        for (String line : inputGroup) {
            Matcher matcher = pattern.matcher(line);
            if (matcher.find()) {
                items.put(matcher.group(1),
                        matcher.groupCount() >= 2 ? Integer.parseInt(matcher.group(2)) : 1);
            }
        }
        return items;
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
    @DisplayName("3. The payload - 17 screen fields plus the communication area, all String")
    class PayloadProjection {

        @Test
        @DisplayName("the record has 18 components: 17 String fields and one NavigationContext")
        void componentsAreSeventeenStringsAndTheCommarea() {
            RecordComponent[] components = ReportRequestRequest.class.getRecordComponents();
            assertThat(components).hasSize(ReportRequestRequest.FIELD_COUNT + 1);
            assertThat(components).filteredOn(c -> c.getType() == String.class)
                    .hasSize(ReportRequestRequest.FIELD_COUNT);
            assertThat(components[ReportRequestRequest.FIELD_COUNT].getName())
                    .isEqualTo("navigationContext");
            assertThat(components[ReportRequestRequest.FIELD_COUNT].getType())
                    .isEqualTo(NavigationContext.class);
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
            List<String> actual = new ArrayList<>();
            for (RecordComponent component : ReportRequestRequest.class.getRecordComponents()) {
                if (component.getType() == String.class) {
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
                    null, null, null, null, null, null, null, null, null, null, null, null, null);
            for (ScreenField field : ScreenField.values()) {
                assertThat(allNull.value(field)).as("%s", field.inputItem())
                        .isEqualTo(field.spaces());
            }
            assertThat(allNull.navigationContext()).as("null commarea means EIBCALEN = 0").isNull();
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
                            null))
                    .withMessageContaining("TRNNAMEI");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new ReportRequestRequest(null, null, null, null, null, null,
                            null, null, null, null, null, null, null, null, null, null,
                            "x".repeat(79), null))
                    .withMessageContaining("ERRMSGI");
        }
    }

    @Nested
    @DisplayName("5. The JSON contract - 18 properties, nothing trimmed, no metadata on the wire")
    class JsonContract {

        @Test
        @DisplayName("the payload is exactly the 18 components and nothing else")
        void payloadIsTheEighteenComponents() throws Exception {
            ObjectNode node = (ObjectNode) JSON.readTree(JSON.writeValueAsString(customRequest()));
            List<String> properties = new ArrayList<>();
            node.fieldNames().forEachRemaining(properties::add);
            List<String> expected = new ArrayList<>();
            for (ScreenField field : ScreenField.values()) {
                expected.add(field.bmsName().toLowerCase(java.util.Locale.ROOT));
            }
            expected.add("navigationContext");
            assertThat(properties).containsExactlyInAnyOrderElementsOf(expected)
                    .hasSize(ReportRequestRequest.FIELD_COUNT + 1);
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
}
