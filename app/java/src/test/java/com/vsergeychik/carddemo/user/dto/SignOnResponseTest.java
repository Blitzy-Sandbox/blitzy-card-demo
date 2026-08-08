package com.vsergeychik.carddemo.user.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.common.NavigationContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link SignOnResponse}, the outbound payload of {@code POST /api/signon}.
 *
 * <p>Plain JUnit 5: no Spring context, no {@code MockMvc} and no {@code JobLauncher}, because every
 * decision in the class under test is reachable directly.
 *
 * <p>The distinguishing property of this suite is that the expectations are <strong>not</strong>
 * restated from the implementation. They are read at run time out of the reference sources, which are
 * the only oracle available for a like-for-like migration:
 *
 * <ul>
 *   <li>{@code app/cpy-bms/COSGN00.CPY} - the {@code xxxO} items of {@code 01 COSGN0AO} supply every
 *       field name and every width, and the {@code xxxL}, {@code xxxF}, {@code xxxA}, {@code xxxC},
 *       {@code xxxP}, {@code xxxH} and {@code xxxV} items supply the list of names that must
 *       <em>never</em> reach the wire;</li>
 *   <li>{@code app/bms/COSGN00.bms} - the name-labelled {@code DFHMDF} definitions and their
 *       {@code LENGTH=} operands, which cross-check the copybook independently;</li>
 *   <li>{@code app/cbl/COSGN00C.cbl} - which {@code xxxO} items the program actually writes, the two
 *       {@code XCTL} targets and their order, and the literals {@code 'CC00'}, {@code 'COSGN00C'},
 *       {@code 'COSGN0A'}, {@code 'COSGN00'} and {@code WS-MESSAGE PIC X(80)}.</li>
 * </ul>
 *
 * So a test here fails if the payload drifts from the mapset <em>or</em> if a constant drifts from the
 * program - not merely if someone edits a number in two places consistently. In particular the absence
 * of the password member is proved from {@code COSGN00C} itself rather than asserted as a preference.
 */
@DisplayName("SignOnResponse - the COSGN00C / COSGN0AO sign-on payload")
class SignOnResponseTest {

    /** The nine {@code xxxO} items {@code COSGN00C} is verified to write. Order is irrelevant. */
    private static final Set<String> WRITTEN_ITEMS = Set.of(
            "TRNNAMEO", "TITLE01O", "CURDATEO", "PGMNAMEO", "TITLE02O",
            "CURTIMEO", "APPLIDO", "SYSIDO", "ERRMSGO");

    /** The five members that carry the navigation contract rather than a screen field. */
    private static final List<String> NAVIGATION_COMPONENTS = List.of(
            "role", "nextProgram", "nextMapset", "nextMap", "navigationContext");

    /**
     * The fourteen character components, each exactly at its declared width, in component order.
     * {@code A14:07:31} is nine characters on purpose - it is the shape of {@code CURTIME}'s own
     * {@code INITIAL='Ahh:mm:ss'} in the mapset.
     */
    private static String[] widthExactComponents() {
        return new String[] {"CC00", "A".repeat(40), "08/22/22", "COSGN00C", "B".repeat(40),
                "A14:07:31", "CICSAPPL", "CICS    ", "ADMIN001", "X".repeat(78),
                "A", "COADM01C", "COSGN00", "COSGN0A"};
    }

    /** Builds a response from the fourteen character components plus an empty communication area. */
    private static SignOnResponse ofCharacterComponents(String[] values) {
        return new SignOnResponse(values[0], values[1], values[2], values[3], values[4], values[5],
                values[6], values[7], values[8], values[9], values[10], values[11], values[12],
                values[13], NavigationContext.empty());
    }

    /** A response with every field distinguishable, so a mix-up cannot pass unnoticed. */
    private static SignOnResponse populated() {
        return new SignOnResponse("CC00",
                "AA".repeat(20),
                "08/22/22",
                "COSGN00C",
                "BB".repeat(20),
                "14:07:31",
                "CICSAPPL",
                "CICS    ",
                "ADMIN001",
                "Wrong Password. Try again ...",
                "A",
                "COADM01C",
                "COSGN00",
                "COSGN0A",
                NavigationContext.empty());
    }

    // =================================================================================================
    // Reference-source readers. Each walks up from the working directory so the suite does not depend on
    // where the build was launched from, exactly as the sibling model tests do.
    // =================================================================================================

    private static Path referenceFile(String repoRelativePath) {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            Path reference = candidate.resolve(repoRelativePath);
            if (Files.isRegularFile(reference)) {
                return reference;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("The reference source " + repoRelativePath
                + " was not found above " + Path.of("").toAbsolutePath()
                + "; it is the parity oracle for this payload");
    }

    private static List<String> referenceLines(String repoRelativePath) {
        Path reference = referenceFile(repoRelativePath);
        try {
            return Files.readAllLines(reference, StandardCharsets.ISO_8859_1);
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not read " + reference, failure);
        }
    }

    /**
     * The {@code xxxO} items of {@code 01 COSGN0AO}, in declaration order, mapped to the width their
     * {@code PICTURE} clause declares.
     *
     * <p>{@code PIC\s+X} deliberately does not match {@code PICTURE X}, which is how the single-byte
     * attribute items and the {@code FILLER} spans are excluded: the value items are the only ones the
     * copybook spells with the short {@code PIC} form.
     */
    private static Map<String, Integer> outputItemWidths() {
        Pattern item = Pattern.compile("^\\s*02\\s+([A-Z0-9]+O)\\s+PIC\\s+X\\((\\d+)\\)\\.");
        Map<String, Integer> widths = new LinkedHashMap<>();
        boolean inOutputGroup = false;
        for (String line : referenceLines("app/cpy-bms/COSGN00.CPY")) {
            if (line.contains("01  COSGN0AO")) {
                inOutputGroup = true;
            }
            if (!inOutputGroup) {
                continue;
            }
            Matcher matcher = item.matcher(line);
            if (matcher.find()) {
                widths.put(matcher.group(1), Integer.valueOf(matcher.group(2)));
            }
        }
        return widths;
    }

    /**
     * Every data name declared in {@code app/cpy-bms/COSGN00.CPY} at level 02 or 03 that is not a value
     * item and not {@code FILLER} - that is, the whole metadata surface: {@code xxxL} lengths,
     * {@code xxxF} flags, {@code xxxA} attribute aliases and the {@code xxxC}, {@code xxxP},
     * {@code xxxH} and {@code xxxV} attribute bytes.
     */
    private static Set<String> metadataItems() {
        Pattern declaration = Pattern.compile("^\\s*0[23]\\s+([A-Z0-9]+)\\s");
        Set<String> metadata = new LinkedHashSet<>();
        for (String line : referenceLines("app/cpy-bms/COSGN00.CPY")) {
            Matcher matcher = declaration.matcher(line);
            if (matcher.find()) {
                String name = matcher.group(1);
                if (!"FILLER".equals(name) && !name.endsWith("O") && !name.endsWith("I")) {
                    metadata.add(name);
                }
            }
        }
        return metadata;
    }

    /**
     * The name-labelled {@code DFHMDF} fields of {@code app/bms/COSGN00.bms}, in declaration order,
     * mapped to their {@code LENGTH=} operand.
     *
     * <p>An unnamed {@code DFHMDF} clears the current label, so a literal field's {@code LENGTH} is
     * never attributed to the named field above it.
     */
    private static Map<String, Integer> namedScreenFieldLengths() {
        Pattern named = Pattern.compile("^([A-Z0-9]+)\\s+DFHMDF");
        Pattern unnamed = Pattern.compile("^\\s+DFHMDF");
        Pattern length = Pattern.compile("LENGTH=(\\d+)");
        Map<String, Integer> lengths = new LinkedHashMap<>();
        String current = null;
        for (String line : referenceLines("app/bms/COSGN00.bms")) {
            Matcher namedMatcher = named.matcher(line);
            if (namedMatcher.find()) {
                current = namedMatcher.group(1);
                continue;
            }
            if (unnamed.matcher(line).find()) {
                current = null;
                continue;
            }
            Matcher lengthMatcher = length.matcher(line);
            if (current != null && lengthMatcher.find()) {
                lengths.put(current, Integer.valueOf(lengthMatcher.group(1)));
                current = null;
            }
        }
        return lengths;
    }

    /** Total {@code DFHMDF} definitions in the mapset, named and unnamed alike. */
    private static long totalScreenFieldCount() {
        return referenceLines("app/bms/COSGN00.bms").stream()
                .filter(line -> line.contains("DFHMDF"))
                .count();
    }

    /**
     * The {@code xxxO} items {@code app/cbl/COSGN00C.cbl} writes, collected from every
     * {@code ... OF COSGN0AO} reference. This is the {@code grep -n 'OF COSGN0AO'} evidence, executed.
     */
    private static Set<String> itemsWrittenByProgram() {
        Pattern reference = Pattern.compile("([A-Z0-9]+)\\s+OF\\s+COSGN0AO");
        Set<String> written = new LinkedHashSet<>();
        for (String line : referenceLines("app/cbl/COSGN00C.cbl")) {
            Matcher matcher = reference.matcher(line);
            while (matcher.find()) {
                written.add(matcher.group(1));
            }
        }
        return written;
    }

    /** The record's component names, in declaration order. */
    private static List<String> componentNames() {
        return Arrays.stream(SignOnResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    /** Every key of the serialised payload, nested keys included, flattened and lower-cased. */
    private static Set<String> jsonKeys(SignOnResponse response) {
        Set<String> keys = new LinkedHashSet<>();
        collectKeys(asMap(response), keys);
        return keys;
    }

    private static Map<String, Object> asMap(SignOnResponse response) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            return mapper.readValue(mapper.writeValueAsString(response),
                    new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not serialise the payload", failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static void collectKeys(Map<String, Object> node, Set<String> into) {
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            into.add(entry.getKey().toLowerCase(java.util.Locale.ROOT));
            if (entry.getValue() instanceof Map<?, ?> nested) {
                collectKeys((Map<String, Object>) nested, into);
            }
        }
    }

    @Nested
    @DisplayName("Projection of 01 COSGN0AO, checked against the mapset itself")
    class Projection {

        @Test
        @DisplayName("the payload carries ten map-derived members and five navigation members")
        void componentCensus() {
            List<String> components = componentNames();

            assertThat(components).hasSize(SignOnResponse.MAP_FIELD_COUNT
                    + NAVIGATION_COMPONENTS.size());
            assertThat(components.subList(0, SignOnResponse.MAP_FIELD_COUNT)).containsExactly(
                    "trnName", "title01", "curDate", "pgmName", "title02",
                    "curTime", "applId", "sysId", "userId", "errMsg");
            assertThat(components.subList(SignOnResponse.MAP_FIELD_COUNT, components.size()))
                    .containsExactlyElementsOf(NAVIGATION_COMPONENTS);
        }

        @Test
        @DisplayName("every map-derived member is a String, because every xxxO item is PIC X(n)")
        void everyMapMemberIsCharacter() {
            RecordComponent[] components = SignOnResponse.class.getRecordComponents();

            for (int index = 0; index < SignOnResponse.MAP_FIELD_COUNT; index++) {
                assertThat(components[index].getType())
                        .as("%s is PIC X(n)", components[index].getName())
                        .isEqualTo(String.class);
            }
        }

        @Test
        @DisplayName("MAP_FIELDS is exactly the copybook's xxxO items, in order, less PASSWDO")
        void mapFieldsMatchTheCopybook() {
            List<String> declared = List.copyOf(outputItemWidths().keySet());

            assertThat(declared).hasSize(SignOnResponse.MAPSET_NAMED_FIELD_COUNT);
            assertThat(SignOnResponse.MAP_FIELDS).containsExactlyElementsOf(
                    declared.stream().filter(item -> !SignOnResponse.OMITTED_ITEM.equals(item))
                            .toList());
        }

        @Test
        @DisplayName("MAPSET_NAMED_FIELDS is exactly the named DFHMDF labels, in mapset order")
        void namedFieldsMatchTheMapset() {
            assertThat(SignOnResponse.MAPSET_NAMED_FIELDS)
                    .containsExactlyElementsOf(namedScreenFieldLengths().keySet());
        }

        @Test
        @DisplayName("the three published counts are the mapset's real counts: 37, 11 and 10")
        void countsAreTheMapsetsOwn() {
            assertThat(totalScreenFieldCount()).isEqualTo(SignOnResponse.MAPSET_FIELD_COUNT);
            assertThat(SignOnResponse.MAPSET_NAMED_FIELDS)
                    .hasSize(SignOnResponse.MAPSET_NAMED_FIELD_COUNT);
            assertThat(SignOnResponse.MAP_FIELDS).hasSize(SignOnResponse.MAP_FIELD_COUNT);
            assertThat(SignOnResponse.MAP_FIELD_COUNT)
                    .isEqualTo(SignOnResponse.MAPSET_NAMED_FIELD_COUNT - 1);
        }

        @Test
        @DisplayName("every map-derived member traces to a name-labelled DFHMDF field")
        void everyMemberTracesToAScreenField() {
            Set<String> screenFields = namedScreenFieldLengths().keySet();

            for (String item : SignOnResponse.MAP_FIELDS) {
                assertThat(screenFields)
                        .as("%s must trace to a named DFHMDF field", item)
                        .contains(item.substring(0, item.length() - 1));
            }
        }

        @Test
        @DisplayName("the field-name constants are the copybook's spelling, suffix included")
        void fieldNameConstantsAreVerbatim() {
            assertThat(outputItemWidths().keySet()).containsAll(SignOnResponse.MAP_FIELDS);
            assertThat(SignOnResponse.MAP_FIELDS).allSatisfy(item ->
                    assertThat(item).endsWith("O"));
        }

        @Test
        @DisplayName("the published field lists are immutable, so no caller can edit the census")
        void publishedListsAreImmutable() {
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> SignOnResponse.MAP_FIELDS.add("SNEAKYO"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> SignOnResponse.MAPSET_NAMED_FIELDS.add("SNEAKY"));
        }
    }

    @Nested
    @DisplayName("Widths, taken from the PICTURE clauses and cross-checked against LENGTH=")
    class Widths {

        @ParameterizedTest(name = "{0} is PIC X({1})")
        @CsvSource({
            "TRNNAMEO,  4",
            "TITLE01O, 40",
            "CURDATEO,  8",
            "PGMNAMEO,  8",
            "TITLE02O, 40",
            "CURTIMEO,  9",
            "APPLIDO,   8",
            "SYSIDO,    8",
            "USERIDO,   8",
            "ERRMSGO,  78"
        })
        @DisplayName("the copybook declares the width this payload declares")
        void copybookAgreesWithTheConstant(String item, int expected) {
            assertThat(outputItemWidths()).containsEntry(item, expected);
            assertThat(constantWidthOf(item)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "{0} LENGTH={1}")
        @CsvSource({
            "TRNNAME,  4",
            "TITLE01, 40",
            "CURDATE,  8",
            "PGMNAME,  8",
            "TITLE02, 40",
            "CURTIME,  9",
            "APPLID,   8",
            "SYSID,    8",
            "USERID,   8",
            "ERRMSG,  78"
        })
        @DisplayName("the mapset's LENGTH operand agrees independently")
        void mapsetAgreesWithTheConstant(String screenField, int expected) {
            assertThat(namedScreenFieldLengths()).containsEntry(screenField, expected);
            assertThat(constantWidthOf(screenField + "O")).isEqualTo(expected);
        }

        @Test
        @DisplayName("curTime is nine characters, not the eight of COUSR00 through COUSR03")
        void curTimeIsNine() {
            assertThat(SignOnResponse.CURTIME_LENGTH).isEqualTo(9);
            assertThat(outputItemWidths()).containsEntry("CURTIMEO", 9);
            assertThat(namedScreenFieldLengths()).containsEntry("CURTIME", 9);
            assertThat(SignOnResponse.CURTIME_LENGTH)
                    .as("one wider than every other field of this header")
                    .isEqualTo(SignOnResponse.CURDATE_LENGTH + 1);
        }

        @Test
        @DisplayName("errMsg is 78 although WS-MESSAGE is PIC X(80), so the caller must narrow it")
        void errMsgIsNarrowerThanTheMessageItCarries() {
            Pattern declaration = Pattern.compile("WS-MESSAGE\\s+PIC\\s+X\\((\\d+)\\)");
            int workingStorageWidth = referenceLines("app/cbl/COSGN00C.cbl").stream()
                    .map(declaration::matcher)
                    .filter(Matcher::find)
                    .map(matcher -> Integer.valueOf(matcher.group(1)))
                    .findFirst()
                    .orElseThrow();

            assertThat(workingStorageWidth).isEqualTo(80);
            assertThat(SignOnResponse.ERRMSG_LENGTH).isEqualTo(78).isLessThan(workingStorageWidth);
        }

        @Test
        @DisplayName("the navigation widths are NavigationContext's own, not restated literals")
        void navigationWidthsDelegate() {
            assertThat(SignOnResponse.ROLE_LENGTH)
                    .isEqualTo(NavigationContext.USER_TYPE_LENGTH).isEqualTo(1);
            assertThat(SignOnResponse.NEXT_PROGRAM_LENGTH)
                    .isEqualTo(NavigationContext.TO_PROGRAM_LENGTH).isEqualTo(8);
            assertThat(SignOnResponse.NEXT_MAPSET_LENGTH)
                    .isEqualTo(NavigationContext.LAST_MAPSET_LENGTH).isEqualTo(7);
            assertThat(SignOnResponse.NEXT_MAP_LENGTH)
                    .isEqualTo(NavigationContext.LAST_MAP_LENGTH).isEqualTo(7);
        }

        @Test
        @DisplayName("the map and mapset names are seven characters, which is why X(7) is correct")
        void mapNamesAreSevenCharacters() {
            assertThat(SignOnResponse.MAP_NAME).hasSize(SignOnResponse.NEXT_MAP_LENGTH);
            assertThat(SignOnResponse.MAPSET_NAME).hasSize(SignOnResponse.NEXT_MAPSET_LENGTH);
            assertThat(SignOnResponse.MAP_NAME + "O").isEqualTo("COSGN0AO");
            assertThat(SignOnResponse.MAP_NAME + "I").isEqualTo("COSGN0AI");
        }

        private int constantWidthOf(String item) {
            return switch (item) {
                case "TRNNAMEO" -> SignOnResponse.TRNNAME_LENGTH;
                case "TITLE01O" -> SignOnResponse.TITLE01_LENGTH;
                case "CURDATEO" -> SignOnResponse.CURDATE_LENGTH;
                case "PGMNAMEO" -> SignOnResponse.PGMNAME_LENGTH;
                case "TITLE02O" -> SignOnResponse.TITLE02_LENGTH;
                case "CURTIMEO" -> SignOnResponse.CURTIME_LENGTH;
                case "APPLIDO" -> SignOnResponse.APPLID_LENGTH;
                case "SYSIDO" -> SignOnResponse.SYSID_LENGTH;
                case "USERIDO" -> SignOnResponse.USERID_LENGTH;
                case "ERRMSGO" -> SignOnResponse.ERRMSG_LENGTH;
                default -> throw new IllegalArgumentException("Unmapped item " + item);
            };
        }
    }

    @Nested
    @DisplayName("The eleventh named field: PASSWD is absent, and the program proves why")
    class PasswordOmission {

        @Test
        @DisplayName("PASSWD is a real named screen field, so the omission is a real decision")
        void passwordIsARealScreenField() {
            assertThat(namedScreenFieldLengths()).containsKey(SignOnResponse.OMITTED_FIELD);
            assertThat(outputItemWidths()).containsKey(SignOnResponse.OMITTED_ITEM);
            assertThat(SignOnResponse.MAPSET_NAMED_FIELDS)
                    .contains(SignOnResponse.OMITTED_FIELD);
        }

        @Test
        @DisplayName("COSGN00C writes nine xxxO items and PASSWDO is not among them")
        void theProgramNeverWritesThePassword() {
            Set<String> written = itemsWrittenByProgram();

            assertThat(written).hasSize(9).isEqualTo(WRITTEN_ITEMS);
            assertThat(written).doesNotContain(SignOnResponse.OMITTED_ITEM);
            assertThat(SignOnResponse.MAP_FIELDS).containsAll(written);
        }

        @Test
        @DisplayName("USERIDO is likewise never written, yet stays declared: nothing is tidied")
        void theProgramNeverWritesTheUserIdEither() {
            assertThat(itemsWrittenByProgram()).doesNotContain("USERIDO");
            assertThat(SignOnResponse.MAP_FIELDS).contains(SignOnResponse.USERID_FIELD);
            assertThat(componentNames()).contains("userId");
        }

        @Test
        @DisplayName("no component, accessor or constant introduces a password")
        void noPasswordAnywhereInTheApi() {
            assertThat(componentNames()).noneMatch(SignOnResponseTest::looksLikeAPassword);
            assertThat(Arrays.stream(SignOnResponse.class.getDeclaredMethods())
                    .map(java.lang.reflect.Method::getName).toList())
                    .noneMatch(SignOnResponseTest::looksLikeAPassword);
            assertThat(Arrays.stream(SignOnResponse.class.getDeclaredFields())
                    .map(Field::getName).toList())
                    .noneMatch(SignOnResponseTest::looksLikeAPassword);
        }

        @Test
        @DisplayName("the serialised payload has no key naming a password")
        void noPasswordKeyOnTheWire() {
            assertThat(jsonKeys(populated())).noneMatch(SignOnResponseTest::looksLikeAPassword);
            assertThat(jsonKeys(SignOnResponse.empty()))
                    .noneMatch(SignOnResponseTest::looksLikeAPassword);
        }

        @Test
        @DisplayName("no security type is referenced: authentication stays plaintext against USRSEC")
        void noSecurityFrameworkIsIntroduced() {
            List<String> referencedTypes = new ArrayList<>();
            Arrays.stream(SignOnResponse.class.getRecordComponents())
                    .map(component -> component.getType().getName())
                    .forEach(referencedTypes::add);
            Arrays.stream(SignOnResponse.class.getDeclaredMethods())
                    .forEach(method -> {
                        referencedTypes.add(method.getReturnType().getName());
                        Arrays.stream(method.getParameterTypes())
                                .map(Class::getName)
                                .forEach(referencedTypes::add);
                    });

            assertThat(referencedTypes).allSatisfy(name -> assertThat(name)
                    .doesNotContain("springframework.security")
                    .doesNotContain("crypto")
                    .doesNotContain("jwt")
                    .doesNotContain("Principal"));
        }
    }

    @Nested
    @DisplayName("Symbolic-map metadata never reaches the wire")
    class MetadataExclusion {

        @Test
        @DisplayName("the JSON keys are exactly the fifteen component names, nothing more")
        void keysAreExactlyTheComponents() {
            Map<String, Object> payload = asMap(populated());

            assertThat(payload.keySet()).containsExactlyElementsOf(componentNames());
        }

        @Test
        @DisplayName("no key is an xxxL, xxxF, xxxA, xxxC, xxxP, xxxH or xxxV item")
        void noMetadataItemBecomesAKey() {
            Set<String> metadata = metadataItems();
            Set<String> keys = jsonKeys(populated());

            assertThat(metadata)
                    .as("the copybook's metadata surface must have been found")
                    .isNotEmpty();
            assertThat(metadata).allSatisfy(item -> assertThat(keys)
                    .as("%s is presentation metadata, never payload", item)
                    .doesNotContain(item.toLowerCase(java.util.Locale.ROOT)));
        }

        @ParameterizedTest(name = "no {0} item on the wire")
        @ValueSource(strings = {"L", "F", "A", "C", "P", "H", "V"})
        @DisplayName("each metadata suffix over each screen-field stem is absent")
        void noMetadataSuffixBecomesAKey(String suffix) {
            Set<String> keys = jsonKeys(populated());

            for (String screenField : SignOnResponse.MAPSET_NAMED_FIELDS) {
                assertThat(keys)
                        .doesNotContain((screenField + suffix).toLowerCase(java.util.Locale.ROOT));
            }
        }

        @Test
        @DisplayName("no filler is exposed, neither the TIOAPFX span nor the three-byte spans")
        void noFillerIsExposed() {
            assertThat(jsonKeys(populated())).noneMatch(key -> key.contains("filler"));
        }
    }

    @Nested
    @DisplayName("Serialisation: space padding survives a round trip untouched")
    class Serialisation {

        @Test
        @DisplayName("a populated payload round trips to an equal value")
        void populatedRoundTrips() throws IOException {
            ObjectMapper mapper = new ObjectMapper();
            SignOnResponse original = populated();

            String json = mapper.writeValueAsString(original);

            assertThat(mapper.readValue(json, SignOnResponse.class)).isEqualTo(original);
        }

        @Test
        @DisplayName("trailing spaces are neither trimmed nor coerced to null")
        void trailingSpacesSurvive() throws IOException {
            ObjectMapper mapper = new ObjectMapper();
            SignOnResponse original = SignOnResponse.empty()
                    .withErrMsg("Wrong Password. Try again ...");

            SignOnResponse restored = mapper.readValue(mapper.writeValueAsString(original),
                    SignOnResponse.class);

            assertThat(restored.trnName()).isEqualTo(" ".repeat(SignOnResponse.TRNNAME_LENGTH));
            assertThat(restored.title01()).isEqualTo(" ".repeat(SignOnResponse.TITLE01_LENGTH));
            assertThat(restored.role()).isEqualTo(" ");
            assertThat(restored.errMsg()).isEqualTo("Wrong Password. Try again ...");
            assertThat(restored).isEqualTo(original);
        }

        @Test
        @DisplayName("the communication area travels nested and round trips with it")
        void navigationContextRoundTrips() throws IOException {
            ObjectMapper mapper = new ObjectMapper();
            NavigationContext context = NavigationContext.empty()
                    .withFromTranid(SignOnResponse.TRANID)
                    .withFromProgram(SignOnResponse.PROGRAM_NAME)
                    .withUserId("ADMIN001")
                    .withUserType(SignOnResponse.ROLE_ADMIN)
                    .withPgmEnter();
            SignOnResponse original = SignOnResponse.empty().withNavigation(
                    SignOnResponse.ROLE_ADMIN,
                    SignOnResponse.resolveNextProgram(SignOnResponse.ROLE_ADMIN),
                    SignOnResponse.MAPSET_NAME, SignOnResponse.MAP_NAME, context);

            SignOnResponse restored = mapper.readValue(mapper.writeValueAsString(original),
                    SignOnResponse.class);

            assertThat(restored.navigationContext()).isEqualTo(context);
            assertThat(restored.navigationContext().isAdmin()).isTrue();
            assertThat(restored.navigationContext().isEnter()).isTrue();
            assertThat(restored).isEqualTo(original);
        }
    }

    @Nested
    @DisplayName("empty(): the screen before POPULATE-HEADER-INFO has run")
    class Empty {

        @Test
        @DisplayName("every character member is spaces of its own declared width")
        void everyMemberIsSpaces() {
            SignOnResponse blank = SignOnResponse.empty();

            assertThat(blank.trnName()).isEqualTo(" ".repeat(SignOnResponse.TRNNAME_LENGTH));
            assertThat(blank.title01()).isEqualTo(" ".repeat(SignOnResponse.TITLE01_LENGTH));
            assertThat(blank.curDate()).isEqualTo(" ".repeat(SignOnResponse.CURDATE_LENGTH));
            assertThat(blank.pgmName()).isEqualTo(" ".repeat(SignOnResponse.PGMNAME_LENGTH));
            assertThat(blank.title02()).isEqualTo(" ".repeat(SignOnResponse.TITLE02_LENGTH));
            assertThat(blank.curTime()).isEqualTo(" ".repeat(SignOnResponse.CURTIME_LENGTH));
            assertThat(blank.applId()).isEqualTo(" ".repeat(SignOnResponse.APPLID_LENGTH));
            assertThat(blank.sysId()).isEqualTo(" ".repeat(SignOnResponse.SYSID_LENGTH));
            assertThat(blank.userId()).isEqualTo(" ".repeat(SignOnResponse.USERID_LENGTH));
            assertThat(blank.errMsg()).isEqualTo(" ".repeat(SignOnResponse.ERRMSG_LENGTH));
            assertThat(blank.role()).isEqualTo(" ".repeat(SignOnResponse.ROLE_LENGTH));
            assertThat(blank.nextProgram())
                    .isEqualTo(" ".repeat(SignOnResponse.NEXT_PROGRAM_LENGTH));
            assertThat(blank.nextMapset()).isEqualTo(" ".repeat(SignOnResponse.NEXT_MAPSET_LENGTH));
            assertThat(blank.nextMap()).isEqualTo(" ".repeat(SignOnResponse.NEXT_MAP_LENGTH));
        }

        @Test
        @DisplayName("no role is implied before sign-on and no XCTL target is named")
        void noRoleAndNoTargetBeforeSignOn() {
            SignOnResponse blank = SignOnResponse.empty();

            assertThat(SignOnResponse.isAdminRole(blank.role())).isFalse();
            assertThat(blank.nextProgram().isBlank()).isTrue();
            assertThat(blank.navigationContext()).isEqualTo(NavigationContext.empty());
            assertThat(blank.navigationContext().isAdmin()).isFalse();
            assertThat(blank.navigationContext().isUser()).isFalse();
        }

        @Test
        @DisplayName("the communication area starts in CDEMO-PGM-ENTER state")
        void startsInEnterState() {
            assertThat(SignOnResponse.empty().navigationContext().isEnter()).isTrue();
            assertThat(SignOnResponse.empty().navigationContext().pgmContext())
                    .isEqualTo(NavigationContext.PGM_CONTEXT_ENTER);
        }
    }

    @Nested
    @DisplayName("Role routing, transcribed from COSGN00C lines 230-240")
    class RoleRouting {

        @ParameterizedTest(name = "role [{0}] routes to {1}")
        @CsvSource({
            "A,   COADM01C",
            "U,   COMEN01C",
            "' ', COMEN01C",
            "X,   COMEN01C",
            "a,   COMEN01C",
            "'',  COMEN01C"
        })
        @DisplayName("only 'A' reaches the administrator menu; the ELSE takes everything else")
        void theElseTakesEverythingThatIsNotAdmin(String role, String expected) {
            assertThat(SignOnResponse.resolveNextProgram(role)).isEqualTo(expected);
        }

        @Test
        @DisplayName("null routes to the ELSE target rather than failing")
        void nullRoutesToTheElseTarget() {
            assertThat(SignOnResponse.resolveNextProgram(null))
                    .isEqualTo(SignOnResponse.NEXT_PROGRAM_USER);
            assertThat(SignOnResponse.isAdminRole(null)).isFalse();
        }

        @ParameterizedTest(name = "isAdminRole([{0}]) is false")
        @ValueSource(strings = {"U", " ", "X", "a", "", "AA"})
        @DisplayName("the administrator condition is an exact match on 'A', not a negation")
        void adminConditionIsAnExactMatch(String role) {
            assertThat(SignOnResponse.isAdminRole(role)).isFalse();
            assertThat(SignOnResponse.resolveNextProgram(role))
                    .isEqualTo(SignOnResponse.NEXT_PROGRAM_USER);
        }

        @Test
        @DisplayName("'A' satisfies the administrator condition")
        void adminIsRecognised() {
            assertThat(SignOnResponse.isAdminRole(SignOnResponse.ROLE_ADMIN)).isTrue();
            assertThat(SignOnResponse.resolveNextProgram(SignOnResponse.ROLE_ADMIN))
                    .isEqualTo(SignOnResponse.NEXT_PROGRAM_ADMIN);
        }

        @Test
        @DisplayName("'U' is not a routing condition: it lands on the ELSE like any other byte")
        void regularUserIsNotTested() {
            assertThat(SignOnResponse.resolveNextProgram(SignOnResponse.ROLE_USER))
                    .isEqualTo(SignOnResponse.resolveNextProgram(" "))
                    .isEqualTo(SignOnResponse.resolveNextProgram("?"))
                    .isEqualTo(SignOnResponse.NEXT_PROGRAM_USER);
        }

        @Test
        @DisplayName("the two targets are the program's own XCTL literals, in the source's order")
        void targetsAreTheProgramsOwnLiterals() {
            Pattern transfer = Pattern.compile("PROGRAM\\s*\\('([A-Z0-9]+)'\\)");
            List<String> literals = referenceLines("app/cbl/COSGN00C.cbl").stream()
                    .map(transfer::matcher)
                    .filter(Matcher::find)
                    .map(matcher -> matcher.group(1))
                    .toList();

            assertThat(literals).containsExactly(SignOnResponse.NEXT_PROGRAM_ADMIN,
                    SignOnResponse.NEXT_PROGRAM_USER);
        }

        @Test
        @DisplayName("the role bytes are NavigationContext's, so all seventeen screens agree")
        void roleBytesDelegate() {
            assertThat(SignOnResponse.ROLE_ADMIN)
                    .isEqualTo(NavigationContext.USER_TYPE_ADMIN).isEqualTo("A");
            assertThat(SignOnResponse.ROLE_USER)
                    .isEqualTo(NavigationContext.USER_TYPE_USER).isEqualTo("U");
        }

        @Test
        @DisplayName("the transaction and program literals are the program's own")
        void transactionAndProgramLiteralsAreTheProgramsOwn() {
            List<String> source = referenceLines("app/cbl/COSGN00C.cbl");

            assertThat(source).anyMatch(line ->
                    line.contains("WS-TRANID") && line.contains("'" + SignOnResponse.TRANID + "'"));
            assertThat(source).anyMatch(line -> line.contains("WS-PGMNAME")
                    && line.contains("'" + SignOnResponse.PROGRAM_NAME + "'"));
            assertThat(source).anyMatch(line ->
                    line.contains("MAP('" + SignOnResponse.MAP_NAME + "')"));
            assertThat(source).anyMatch(line ->
                    line.contains("MAPSET('" + SignOnResponse.MAPSET_NAME + "')"));
            assertThat(SignOnResponse.TRANID).hasSize(SignOnResponse.TRNNAME_LENGTH);
            assertThat(SignOnResponse.PROGRAM_NAME).hasSize(SignOnResponse.PGMNAME_LENGTH);
        }
    }

    @Nested
    @DisplayName("The canonical constructor enforces every declared width")
    class WidthEnforcement {

        @Test
        @DisplayName("a value exactly at its declared width is accepted")
        void exactWidthIsAccepted() {
            SignOnResponse response = ofCharacterComponents(widthExactComponents());

            assertThat(response.curTime()).hasSize(SignOnResponse.CURTIME_LENGTH);
            assertThat(response.errMsg()).hasSize(SignOnResponse.ERRMSG_LENGTH);
            assertThat(response.role()).hasSize(SignOnResponse.ROLE_LENGTH);
        }

        @Test
        @DisplayName("a shorter value is accepted, mirroring a MOVE into a wider PIC X receiver")
        void shorterIsAccepted() {
            SignOnResponse response = SignOnResponse.empty().withErrMsg("Short");

            assertThat(response.errMsg()).isEqualTo("Short");
        }

        @ParameterizedTest(name = "component {0} ({1}) rejects null")
        @CsvSource({
            "0,  TRNNAMEO",
            "1,  TITLE01O",
            "2,  CURDATEO",
            "3,  PGMNAMEO",
            "4,  TITLE02O",
            "5,  CURTIMEO",
            "6,  APPLIDO",
            "7,  SYSIDO",
            "8,  USERIDO",
            "9,  ERRMSGO",
            "10, CDEMO-USER-TYPE",
            "11, CDEMO-TO-PROGRAM",
            "12, CDEMO-LAST-MAPSET",
            "13, CDEMO-LAST-MAP"
        })
        @DisplayName("null is rejected and the failure names the item, because COBOL has no null")
        void nullIsRejected(int index, String field) {
            String[] values = widthExactComponents();
            values[index] = null;

            assertThatNullPointerException()
                    .isThrownBy(() -> ofCharacterComponents(values))
                    .withMessageContaining(field)
                    .withMessageContaining("spaces");
        }

        @ParameterizedTest(name = "component {0} ({1}) rejects {2} + 1 characters")
        @CsvSource({
            "0,  TRNNAMEO,           4",
            "1,  TITLE01O,          40",
            "2,  CURDATEO,           8",
            "3,  PGMNAMEO,           8",
            "4,  TITLE02O,          40",
            "5,  CURTIMEO,           9",
            "6,  APPLIDO,            8",
            "7,  SYSIDO,             8",
            "8,  USERIDO,            8",
            "9,  ERRMSGO,           78",
            "10, CDEMO-USER-TYPE,    1",
            "11, CDEMO-TO-PROGRAM,   8",
            "12, CDEMO-LAST-MAPSET,  7",
            "13, CDEMO-LAST-MAP,     7"
        })
        @DisplayName("an over-wide value is rejected, naming the item, the width and the length")
        void overWideIsRejected(int index, String field, int width) {
            String[] values = widthExactComponents();
            values[index] = "Z".repeat(width + 1);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ofCharacterComponents(values))
                    .withMessageContaining(field)
                    .withMessageContaining("PIC X(" + width + ")")
                    .withMessageContaining(String.valueOf(width + 1))
                    .withMessageContaining("movePicX");
        }

        @Test
        @DisplayName("an 80-character WS-MESSAGE is refused rather than clipped to 78")
        void theEightyToSeventyEightNarrowingIsNotImplicit() {
            String eighty = "M".repeat(80);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> SignOnResponse.empty().withErrMsg(eighty))
                    .withMessageContaining(SignOnResponse.ERRMSG_FIELD)
                    .withMessageContaining("movePicX");
            assertThat(SignOnResponse.empty()
                    .withErrMsg(eighty.substring(0, SignOnResponse.ERRMSG_LENGTH)).errMsg())
                    .hasSize(SignOnResponse.ERRMSG_LENGTH);
        }

        @Test
        @DisplayName("the communication area is required, because there is no session to fall back on")
        void navigationContextIsRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new SignOnResponse("CC00", "", "", "COSGN00C", "", "", "", "",
                            "", "", "A", "COADM01C", "COSGN00", "COSGN0A", null))
                    .withMessageContaining("navigationContext");
        }
    }

    @Nested
    @DisplayName("Immutable replacement, one method per writing paragraph")
    class ImmutableReplacement {

        @Test
        @DisplayName("withHeader writes exactly the eight fields POPULATE-HEADER-INFO writes")
        void withHeaderWritesTheEightHeaderFields() {
            SignOnResponse before = SignOnResponse.empty().withErrMsg("Keep me");

            SignOnResponse after = before.withHeader(SignOnResponse.TRANID, "Title one", "08/22/22",
                    SignOnResponse.PROGRAM_NAME, "Title two", "14:07:31", "CICSAPPL", "CICS");

            assertThat(after.trnName()).isEqualTo(SignOnResponse.TRANID);
            assertThat(after.title01()).isEqualTo("Title one");
            assertThat(after.curDate()).isEqualTo("08/22/22");
            assertThat(after.pgmName()).isEqualTo(SignOnResponse.PROGRAM_NAME);
            assertThat(after.title02()).isEqualTo("Title two");
            assertThat(after.curTime()).isEqualTo("14:07:31");
            assertThat(after.applId()).isEqualTo("CICSAPPL");
            assertThat(after.sysId()).isEqualTo("CICS");
            assertThat(after.userId()).as("USERIDO is not written by that paragraph")
                    .isEqualTo(before.userId());
            assertThat(after.errMsg()).as("ERRMSGO is written by the caller, at line 149")
                    .isEqualTo("Keep me");
            assertThat(after.role()).isEqualTo(before.role());
            assertThat(after.navigationContext()).isEqualTo(before.navigationContext());
        }

        @Test
        @DisplayName("withErrMsg writes only the error line")
        void withErrMsgWritesOnlyTheErrorLine() {
            SignOnResponse before = populated();

            SignOnResponse after = before.withErrMsg("User not found. Try again ...");

            assertThat(after.errMsg()).isEqualTo("User not found. Try again ...");
            assertThat(after).usingRecursiveComparison().ignoringFields("errMsg")
                    .isEqualTo(before);
        }

        @Test
        @DisplayName("withNavigation writes only the five navigation members")
        void withNavigationWritesOnlyTheNavigationMembers() {
            SignOnResponse before = populated();
            NavigationContext context = NavigationContext.empty().withUserId("USER0001");

            SignOnResponse after = before.withNavigation(SignOnResponse.ROLE_USER,
                    SignOnResponse.NEXT_PROGRAM_USER, "COMEN00", "COMEN0A", context);

            assertThat(after.role()).isEqualTo(SignOnResponse.ROLE_USER);
            assertThat(after.nextProgram()).isEqualTo(SignOnResponse.NEXT_PROGRAM_USER);
            assertThat(after.nextMapset()).isEqualTo("COMEN00");
            assertThat(after.nextMap()).isEqualTo("COMEN0A");
            assertThat(after.navigationContext()).isEqualTo(context);
            assertThat(after).usingRecursiveComparison()
                    .ignoringFields("role", "nextProgram", "nextMapset", "nextMap",
                            "navigationContext")
                    .isEqualTo(before);
        }

        @Test
        @DisplayName("every replacement leaves the original untouched")
        void replacementIsNonDestructive() {
            SignOnResponse original = populated();

            original.withErrMsg("Something else");
            original.withHeader("XX00", "x", "01/01/70", "XXXXXXXX", "y", "00:00:00", "X", "X");
            original.withNavigation("U", "COMEN01C", "COMEN00", "COMEN0A",
                    NavigationContext.empty());

            assertThat(original).isEqualTo(populated());
        }

        @Test
        @DisplayName("a role that disagrees with the target is constructible, because the service decides")
        void aMismatchedPairIsConstructible() {
            SignOnResponse mismatched = SignOnResponse.empty().withNavigation(
                    SignOnResponse.ROLE_ADMIN, SignOnResponse.NEXT_PROGRAM_USER,
                    SignOnResponse.MAPSET_NAME, SignOnResponse.MAP_NAME, NavigationContext.empty());

            assertThat(mismatched.role()).isEqualTo(SignOnResponse.ROLE_ADMIN);
            assertThat(mismatched.nextProgram()).isEqualTo(SignOnResponse.NEXT_PROGRAM_USER);
            assertThat(SignOnResponse.resolveNextProgram(mismatched.role()))
                    .as("the payload carries what it was given; it does not recompute it")
                    .isNotEqualTo(mismatched.nextProgram());
        }
    }

    @Nested
    @DisplayName("Statelessness: no session, no static state, no derived instance accessor")
    class Statelessness {

        @Test
        @DisplayName("no declared field is static and non-final")
        void noStaticMutableState() {
            for (Field field : SignOnResponse.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("static field %s must be final", field.getName())
                            .isTrue();
                }
            }
        }

        @Test
        @DisplayName("every instance field is final, so the payload cannot change after construction")
        void everyInstanceFieldIsFinal() {
            for (Field field : SignOnResponse.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("instance field %s must be final", field.getName())
                            .isTrue();
                }
            }
        }

        @Test
        @DisplayName("the role test and the target lookup are static, so neither becomes a JSON property")
        void derivedHelpersAreStatic() throws NoSuchMethodException {
            assertThat(Modifier.isStatic(SignOnResponse.class
                    .getMethod("isAdminRole", String.class).getModifiers())).isTrue();
            assertThat(Modifier.isStatic(SignOnResponse.class
                    .getMethod("resolveNextProgram", String.class).getModifiers())).isTrue();
            assertThat(asMap(populated()).keySet())
                    .doesNotContain("adminRole", "adminrole", "nextProgramFor");
        }

        @Test
        @DisplayName("no accessor takes no argument except the fifteen component accessors")
        void onlyComponentAccessorsAreArgumentFree() {
            List<String> argumentFreeInstanceMethods =
                    Arrays.stream(SignOnResponse.class.getDeclaredMethods())
                            .filter(method -> !Modifier.isStatic(method.getModifiers()))
                            .filter(method -> method.getParameterCount() == 0)
                            .map(java.lang.reflect.Method::getName)
                            .filter(name -> !List.of("hashCode", "toString").contains(name))
                            .toList();

            assertThat(argumentFreeInstanceMethods)
                    .containsExactlyInAnyOrderElementsOf(componentNames());
        }

        @Test
        @DisplayName("the record references no session or thread-local carrier")
        void noSessionCarrierIsReferenced() {
            List<String> types = Arrays.stream(SignOnResponse.class.getRecordComponents())
                    .map(component -> component.getType().getName())
                    .toList();

            assertThat(types).allSatisfy(name -> assertThat(name)
                    .doesNotContain("HttpSession")
                    .doesNotContain("ThreadLocal")
                    .doesNotContain("HttpServlet"));
        }
    }

    /** Whether a name would put a credential on the wire, however it is spelled. */
    private static boolean looksLikeAPassword(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("passwd") || lower.contains("password") || lower.contains("pwd");
    }
}
