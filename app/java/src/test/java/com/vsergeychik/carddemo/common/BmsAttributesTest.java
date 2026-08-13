package com.vsergeychik.carddemo.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tests for {@link BmsAttributes}, the Java stand-in for the IBM-supplied {@code DFHBMSCA} and
 * {@code DFHATTR} copybooks.
 */
@DisplayName("BmsAttributes - DFHBMSCA and DFHATTR screen attribute constants")
class BmsAttributesTest {
    private static final List<String> FIELD_ATTRIBUTE_NAMES = List.of(
            "DFHBMUNP", "DFHBMFSE", "DFHBMBRY", "DFHUNIMD", "DFHBMDAR", "DFHUNNOD",
            "DFHBMUNN", "DFHUNNUM", "DFHUNNUB", "DFHUNINT", "DFHUNNON", "DFHBMPRO",
            "DFHBMPRF", "DFHPROTI", "DFHPROTN", "DFHBMASK", "DFHBMASF", "DFHBMASB");

    private static final List<String> COLOUR_NAMES = List.of(
            "DFHDFCOL", "DFHBLUE", "DFHRED", "DFHPINK", "DFHGREEN", "DFHTURQ", "DFHYELLO",
            "DFHNEUTR");

    private static final List<String> HIGHLIGHT_NAMES = List.of(
            "DFHDFHI", "DFHBLINK", "DFHREVRS", "DFHUNDLN");

    private static final List<String> CONSUMED_COLOUR_NAMES =
            List.of("DFHRED", "DFHGREEN", "DFHNEUTR", "DFHDFCOL");

    private static final List<String> CONSUMED_FIELD_ATTRIBUTE_NAMES =
            List.of("DFHBMFSE", "DFHBMPRF", "DFHBMPRO", "DFHBMDAR", "DFHBMASB", "DFHBMBRY");

    private static Map<String, Byte> declaredAttributeBytes() {
        Map<String, Byte> constants = new LinkedHashMap<>();
        for (Field field : BmsAttributes.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (field.getType() == byte.class
                    && Modifier.isPublic(modifiers)
                    && Modifier.isStatic(modifiers)
                    && Modifier.isFinal(modifiers)) {
                try {
                    constants.put(field.getName(), field.getByte(null));
                } catch (IllegalAccessException cannotRead) {
                    throw new AssertionError(
                            "public static final byte " + field.getName() + " is unreadable",
                            cannotRead);
                }
            }
        }
        return constants;
    }

    private static byte attributeByte(String mnemonic) {
        Map<String, Byte> declared = declaredAttributeBytes();
        assertThat(declared)
                .as("BmsAttributes must declare %s; declared constants are %s",
                        mnemonic, declared.keySet())
                .containsKey(mnemonic);
        return declared.get(mnemonic);
    }

    private static List<String> expectedMnemonics() {
        List<String> roster = new ArrayList<>(FIELD_ATTRIBUTE_NAMES);
        roster.addAll(COLOUR_NAMES);
        roster.addAll(HIGHLIGHT_NAMES);
        return roster;
    }

    private static Field declaredField(String mnemonic) {
        try {
            return BmsAttributes.class.getDeclaredField(mnemonic);
        } catch (NoSuchFieldException notDeclared) {
            throw new AssertionError("BmsAttributes must declare " + mnemonic, notDeclared);
        }
    }

    private static Map<String, List<String>> collisionsWithin(List<String> names) {
        Map<Byte, List<String>> byValue = new LinkedHashMap<>();
        for (String name : names) {
            byValue.computeIfAbsent(attributeByte(name), value -> new ArrayList<>()).add(name);
        }

        Map<String, List<String>> collisions = new LinkedHashMap<>();
        byValue.forEach((value, sharing) -> {
            if (sharing.size() > 1) {
                collisions.put(BmsAttributes.toHex(value), sharing);
            }
        });
        return collisions;
    }

    private static String render(List<String> names) {
        Set<String> rendered = new TreeSet<>();
        for (String name : names) {
            rendered.add(name + "=" + BmsAttributes.toHex(attributeByte(name)));
        }
        return String.join(", ", rendered);
    }

    @Nested
    @DisplayName("Completeness - every mnemonic the COBOL references is defined")
    class Completeness {
        @ParameterizedTest(name = "{0} is defined - {1} occurrence(s) in app/cbl")
        @CsvSource({
            "DFHRED, 30",
            "DFHGREEN, 8",
            "DFHDFCOL, 6",
            "DFHNEUTR, 5",
            "DFHBMFSE, 23",
            "DFHBMPRF, 11",
            "DFHBMPRO, 6",
            "DFHBMDAR, 6",
            "DFHBMASB, 4",
            "DFHBMBRY, 2",
        })
        @DisplayName("each consumed mnemonic is declared")
        void everyConsumedMnemonicIsDeclared(String mnemonic, int occurrences) {
            assertThat(declaredAttributeBytes())
                    .as("%s is referenced %d time(s) in app/cbl and must be declared",
                            mnemonic, occurrences)
                    .containsKey(mnemonic);
        }

        @Test
        @DisplayName("DFHGREEN is declared, though the plan's illustrative list omits it")
        void dfhGreenIsDeclaredDespiteBeingAbsentFromThePlansList() {
            assertThat(declaredAttributeBytes())
                    .as("DFHGREEN has 8 occurrences in app/cbl - the success signal, and the exact "
                            + "counterpart of DFHRED")
                    .containsKey("DFHGREEN");
        }

        @Test
        @DisplayName("DFHUNIMD is declared even though it has zero consumers")
        void dfhUnimdIsDeclaredDespiteHavingNoConsumer() {
            assertThat(declaredAttributeBytes())
                    .as("DFHUNIMD has 0 occurrences anywhere in this repository, is mandated by the "
                            + "migration plan, and must not be removed")
                    .containsKey("DFHUNIMD");
        }

        @Test
        @DisplayName("DFHRED is declared - the byte CSSETATY moves onto an invalid field")
        void dfhRedIsDeclaredAsTheErrorHighlightColour() {
            assertThat(declaredAttributeBytes())
                    .as("DFHRED: 30 occurrences in app/cbl plus 1 in app/cpy/CSSETATY.cpy, and the "
                            + "dependency of every error-highlight path in the online layer")
                    .containsKey("DFHRED");
        }

        @Test
        @DisplayName("exactly 30 attribute bytes are declared - 18 + 8 + 4")
        void declaresExactlyThirtyAttributeBytes() {
            Map<String, Byte> declared = declaredAttributeBytes();

            assertThat(declared)
                    .as("18 basic field attributes + 8 colours + 4 highlighting values; declared: %s",
                            declared.keySet())
                    .hasSize(30);
            assertThat(FIELD_ATTRIBUTE_NAMES.size()
                            + COLOUR_NAMES.size()
                            + HIGHLIGHT_NAMES.size())
                    .as("the three expected plane rosters must themselves sum to 30")
                    .isEqualTo(30);
        }

        @Test
        @DisplayName("the declared names are exactly the three plane rosters, and nothing else")
        void declaredNamesAreExactlyTheThreePlaneRosters() {
            Set<String> expected = new TreeSet<>(FIELD_ATTRIBUTE_NAMES);
            expected.addAll(COLOUR_NAMES);
            expected.addAll(HIGHLIGHT_NAMES);

            assertThat(declaredAttributeBytes().keySet())
                    .as("an addition or removal must be deliberate and documented, never incidental")
                    .containsExactlyInAnyOrderElementsOf(expected);
        }
    }

    @Nested
    @DisplayName("Plane grouping - the colour/attribute partition is structural, not incidental")
    class PlaneGrouping {
        @Test
        @DisplayName("each plane's mnemonic map contains exactly that plane's constants")
        void eachPlaneMapContainsExactlyItsOwnRoster() {
            assertThat(BmsAttributes.FIELD_ATTRIBUTE_MNEMONICS.values())
                    .as("Section 1 - basic field attribute bytes, moved into the xxxA input item")
                    .containsExactlyInAnyOrderElementsOf(FIELD_ATTRIBUTE_NAMES);
            assertThat(BmsAttributes.COLOUR_MNEMONICS.values())
                    .as("Section 2 - extended colour values, moved into the xxxC output item")
                    .containsExactlyInAnyOrderElementsOf(COLOUR_NAMES);
            assertThat(BmsAttributes.HIGHLIGHT_MNEMONICS.values())
                    .as("Section 3 - extended highlighting values, unused by these programs")
                    .containsExactlyInAnyOrderElementsOf(HIGHLIGHT_NAMES);
        }

        @Test
        @DisplayName("every declared constant is classified into exactly one plane")
        void everyDeclaredConstantIsClassifiedIntoExactlyOnePlane() {
            Set<String> classified = new TreeSet<>(BmsAttributes.FIELD_ATTRIBUTE_MNEMONICS.values());
            classified.addAll(BmsAttributes.COLOUR_MNEMONICS.values());
            classified.addAll(BmsAttributes.HIGHLIGHT_MNEMONICS.values());

            assertThat(classified)
                    .as("the union of the three mnemonic maps must be the full declared set")
                    .containsExactlyInAnyOrderElementsOf(declaredAttributeBytes().keySet());
            assertThat(BmsAttributes.FIELD_ATTRIBUTE_MNEMONICS.values())
                    .as("no mnemonic may appear in two planes' maps - the colour plane")
                    .doesNotContainAnyElementsOf(BmsAttributes.COLOUR_MNEMONICS.values());
            assertThat(BmsAttributes.FIELD_ATTRIBUTE_MNEMONICS.values())
                    .as("no mnemonic may appear in two planes' maps - the highlighting plane")
                    .doesNotContainAnyElementsOf(BmsAttributes.HIGHLIGHT_MNEMONICS.values());
            assertThat(BmsAttributes.COLOUR_MNEMONICS.values())
                    .as("no mnemonic may appear in both the colour and highlighting planes")
                    .doesNotContainAnyElementsOf(BmsAttributes.HIGHLIGHT_MNEMONICS.values());
        }

        @Test
        @DisplayName("each plane's map is keyed by as many distinct bytes as it has constants")
        void eachPlaneMapIsKeyedByOneByteApiece() {
            assertThat(BmsAttributes.FIELD_ATTRIBUTE_MNEMONICS).hasSize(FIELD_ATTRIBUTE_NAMES.size());
            assertThat(BmsAttributes.COLOUR_MNEMONICS).hasSize(COLOUR_NAMES.size());
            assertThat(BmsAttributes.HIGHLIGHT_MNEMONICS).hasSize(HIGHLIGHT_NAMES.size());
        }
    }

    @Nested
    @DisplayName("Distinctness - within each plane, and across the planes the COBOL actually uses")
    class Distinctness {
        @Test
        @DisplayName("the 18 basic field attribute bytes are pairwise distinct")
        void fieldAttributeBytesArePairwiseDistinct() {
            assertThat(collisionsWithin(FIELD_ATTRIBUTE_NAMES))
                    .as("two Section 1 mnemonics share a byte, which the 3270 architecture does not "
                            + "permit within the basic attribute plane")
                    .isEmpty();
        }

        @Test
        @DisplayName("the 8 extended colour bytes are pairwise distinct")
        void colourBytesArePairwiseDistinct() {
            assertThat(collisionsWithin(COLOUR_NAMES))
                    .as("two Section 2 colours share a byte, so one colour would render as another")
                    .isEmpty();
        }

        @Test
        @DisplayName("the 4 extended highlighting bytes are pairwise distinct")
        void highlightBytesArePairwiseDistinct() {
            assertThat(collisionsWithin(HIGHLIGHT_NAMES))
                    .as("two Section 3 highlighting values share a byte; only one highlight may be in "
                            + "force at a time, so these are alternatives and must differ")
                    .isEmpty();
        }

        @Test
        @DisplayName("DFHRED differs from DFHGREEN - error must never render as success")
        void dfhRedDiffersFromDfhGreen() {
            assertThat(BmsAttributes.DFHRED)
                    .as("DFHRED %s must differ from DFHGREEN %s",
                            BmsAttributes.toHex(BmsAttributes.DFHRED),
                            BmsAttributes.toHex(BmsAttributes.DFHGREEN))
                    .isNotEqualTo(BmsAttributes.DFHGREEN);
        }

        @Test
        @DisplayName("the four consumed colours, and the six consumed field attributes, are distinct")
        void theConsumedMnemonicsAreDistinctWithinTheirPlanes() {
            assertThat(collisionsWithin(CONSUMED_COLOUR_NAMES))
                    .as("DFHRED (error), DFHGREEN (success), DFHNEUTR (informational) and DFHDFCOL "
                            + "(reset) each carry a distinct meaning and must carry a distinct byte")
                    .isEmpty();
            assertThat(collisionsWithin(CONSUMED_FIELD_ATTRIBUTE_NAMES))
                    .as("the six consumed field attributes select six different field treatments")
                    .isEmpty();
        }

        @Test
        @DisplayName("no consumed colour equals any consumed field attribute")
        void consumedColoursNeverEqualConsumedFieldAttributes() {
            for (String colour : CONSUMED_COLOUR_NAMES) {
                for (String attribute : CONSUMED_FIELD_ATTRIBUTE_NAMES) {
                    assertThat(attributeByte(colour))
                            .as("consumed colour %s must differ from consumed field attribute %s; "
                                            + "colours: %s / attributes: %s",
                                    colour, attribute,
                                    render(CONSUMED_COLOUR_NAMES),
                                    render(CONSUMED_FIELD_ATTRIBUTE_NAMES))
                            .isNotEqualTo(attributeByte(attribute));
                }
            }
        }

        @Test
        @DisplayName("DFHUNIMD is unique across all three planes")
        void dfhUnimdIsUniqueAcrossEveryPlane() {
            Map<String, Byte> declared = declaredAttributeBytes();
            byte unimd = attributeByte("DFHUNIMD");

            declared.forEach((name, value) -> {
                if (!"DFHUNIMD".equals(name)) {
                    assertThat(value)
                            .as("DFHUNIMD %s must not collide with %s",
                                    BmsAttributes.toHex(unimd), name)
                            .isNotEqualTo(unimd);
                }
            });
        }

        @Test
        @DisplayName("the architectural cross-plane collisions are intentional and pinned")
        void architecturalCrossPlaneCollisionsAreIntentional() {
            assertThat(BmsAttributes.DFHBMASF)
                    .as("0xF1 is autoskip+MDT, blue and blink, depending on the plane")
                    .isEqualTo(BmsAttributes.DFHBLUE)
                    .isEqualTo(BmsAttributes.DFHBLINK);
            assertThat(BmsAttributes.DFHRED)
                    .as("0xF2 is red in the colour plane and reverse video in the highlighting plane")
                    .isEqualTo(BmsAttributes.DFHREVRS);
            assertThat(BmsAttributes.DFHGREEN)
                    .as("0xF4 is green in the colour plane and underscore in the highlighting plane")
                    .isEqualTo(BmsAttributes.DFHUNDLN);
            assertThat(BmsAttributes.DFHDFCOL)
                    .as("0x00 means \"use the default\" in both the colour and highlighting planes")
                    .isEqualTo(BmsAttributes.DFHDFHI);
        }
    }

    @Nested
    @DisplayName("Shape and encoding - one EBCDIC byte apiece, never a Java char literal")
    class ShapeAndEncoding {
        @Test
        @DisplayName("every constant is declared as a byte, not a char and not a String")
        void everyConstantIsDeclaredAsAByte() {
            for (String mnemonic : expectedMnemonics()) {
                Field field = declaredField(mnemonic);

                assertThat(field.getType())
                        .as("%s must be a byte so that no charset conversion can ever be involved",
                                mnemonic)
                        .isEqualTo(byte.class);
            }
        }

        @ParameterizedTest(name = "{0} occupies a single byte")
        @CsvSource({
            "DFHBMUNP", "DFHBMFSE", "DFHBMBRY", "DFHUNIMD", "DFHBMDAR", "DFHUNNOD",
            "DFHBMUNN", "DFHUNNUM", "DFHUNNUB", "DFHUNINT", "DFHUNNON", "DFHBMPRO",
            "DFHBMPRF", "DFHPROTI", "DFHPROTN", "DFHBMASK", "DFHBMASF", "DFHBMASB",
            "DFHDFCOL", "DFHBLUE", "DFHRED", "DFHPINK", "DFHGREEN", "DFHTURQ", "DFHYELLO",
            "DFHNEUTR", "DFHDFHI", "DFHBLINK", "DFHREVRS", "DFHUNDLN",
        })
        @DisplayName("each constant is a single byte, and unsigned() reads it as 0-255")
        void everyConstantIsASingleByte(String mnemonic) {
            int unsigned = BmsAttributes.unsigned(attributeByte(mnemonic));

            assertThat(unsigned)
                    .as("%s must be one byte, readable as an unsigned 0-255 value", mnemonic)
                    .isBetween(0, 255);
            assertThat(BmsAttributes.toHex(attributeByte(mnemonic)))
                    .as("%s must render as the copybook's two-digit X'hh' notation", mnemonic)
                    .matches("X'[0-9A-F]{2}'");
        }

        @ParameterizedTest(name = "{0} is the EBCDIC byte, not the Java literal ''{1}''")
        @CsvSource({
            "DFHRED, 2",
            "DFHGREEN, 4",
            "DFHNEUTR, 7",
            "DFHBMASB, 8",
            "DFHBMFSE, A",
            "DFHBMPRF, /",
        })
        @DisplayName("the EBCDIC byte is used, never the Java char literal of the same graphic")
        void ebcdicBytesAreUsedNotJavaCharLiterals(String mnemonic, String graphic) {
            byte declared = attributeByte(mnemonic);
            byte javaLiteral = (byte) graphic.charAt(0);

            assertThat(declared)
                    .as("%s is %s in EBCDIC; the Java literal '%s' would be %s and would send the "
                                    + "wrong byte to the terminal",
                            mnemonic, BmsAttributes.toHex(declared), graphic,
                            BmsAttributes.toHex(javaLiteral))
                    .isNotEqualTo(javaLiteral);
        }

        @Test
        @DisplayName("the public surface is 30 bytes plus 3 maps - no char and no String constant")
        void thePublicSurfaceIsBytesAndMapsOnly() {
            int publicBytes = 0;
            int publicMaps = 0;

            for (Field field : BmsAttributes.class.getDeclaredFields()) {
                if (field.isSynthetic() || !Modifier.isPublic(field.getModifiers())) {
                    continue;
                }
                assertThat(field.getType())
                        .as("public field %s must not be a char - a char literal holds a Unicode code "
                                + "point, not the EBCDIC byte the terminal needs", field.getName())
                        .isNotEqualTo(char.class);
                assertThat(field.getType())
                        .as("public field %s must not be a String - turning one into bytes needs a "
                                + "charset, and an unnamed charset is the platform default",
                                field.getName())
                        .isNotEqualTo(String.class);

                if (field.getType() == byte.class) {
                    publicBytes++;
                } else if (Map.class.isAssignableFrom(field.getType())) {
                    publicMaps++;
                } else {
                    throw new AssertionError("unexpected public member "
                            + field.getName() + " of type " + field.getType().getName());
                }
            }

            assertThat(publicBytes).as("30 attribute bytes").isEqualTo(30);
            assertThat(publicMaps).as("3 diagnostic mnemonic maps, one per plane").isEqualTo(3);
        }

        @Test
        @DisplayName("the class never touches a charset, so no platform default can leak in")
        void theClassNeverConsultsACharset() {
            for (Field field : BmsAttributes.class.getDeclaredFields()) {
                assertThat(field.getType())
                        .as("field %s must not be charset-typed", field.getName())
                        .isNotEqualTo(Charset.class);
            }
            for (Method method : BmsAttributes.class.getDeclaredMethods()) {
                assertThat(method.getReturnType())
                        .as("method %s must not return a charset", method.getName())
                        .isNotEqualTo(Charset.class);
                assertThat(method.getParameterTypes())
                        .as("method %s must not take a charset parameter", method.getName())
                        .doesNotContain(Charset.class);
            }
        }
    }

    @Nested
    @DisplayName("Value provenance - transcribed from IBM CICS documentation, not from this repository")
    class ValueProvenance {
        @ParameterizedTest(name = "{0} = {1}")
        @CsvSource({
            "DFHBMUNP, 0x40",
            "DFHBMFSE, 0xC1",
            "DFHBMBRY, 0xC8",
            "DFHUNIMD, 0xC9",
            "DFHBMDAR, 0x4C",
            "DFHUNNOD, 0x4D",
            "DFHBMUNN, 0x50",
            "DFHUNNUM, 0xD1",
            "DFHUNNUB, 0xD8",
            "DFHUNINT, 0xD9",
            "DFHUNNON, 0x5D",
            "DFHBMPRO, 0x60",
            "DFHBMPRF, 0x61",
            "DFHPROTI, 0xE8",
            "DFHPROTN, 0x6C",
            "DFHBMASK, 0xF0",
            "DFHBMASF, 0xF1",
            "DFHBMASB, 0xF8",
            "DFHDFCOL, 0x00",
            "DFHBLUE, 0xF1",
            "DFHRED, 0xF2",
            "DFHPINK, 0xF3",
            "DFHGREEN, 0xF4",
            "DFHTURQ, 0xF5",
            "DFHYELLO, 0xF6",
            "DFHNEUTR, 0xF7",
            "DFHDFHI, 0x00",
            "DFHBLINK, 0xF1",
            "DFHREVRS, 0xF2",
            "DFHUNDLN, 0xF4",
        })
        @DisplayName("each constant holds the byte IBM's documentation assigns to that mnemonic")
        void eachConstantHoldsItsDocumentedByte(String mnemonic, String documentedHex) {
            byte expected = (byte) Integer.decode(documentedHex).intValue();

            assertThat(attributeByte(mnemonic))
                    .as("%s is documented by IBM as %s", mnemonic, documentedHex)
                    .isEqualTo(expected);
            assertThat(BmsAttributes.toHex(attributeByte(mnemonic)))
                    .as("%s must render as the same notation the documentation uses", mnemonic)
                    .isEqualTo("X'" + documentedHex.substring(2) + "'");
        }
    }

    @Nested
    @DisplayName("Immutability - constants only, no mutable state, not instantiable")
    class Immutability {
        @Test
        @DisplayName("every attribute byte is public static final")
        void everyAttributeByteIsPublicStaticFinal() {
            for (String mnemonic : expectedMnemonics()) {
                int modifiers = declaredField(mnemonic).getModifiers();

                assertThat(Modifier.isPublic(modifiers))
                        .as("%s must be public - seventeen controllers consume it", mnemonic)
                        .isTrue();
                assertThat(Modifier.isStatic(modifiers))
                        .as("%s must be static - it is a constant, not instance state", mnemonic)
                        .isTrue();
                assertThat(Modifier.isFinal(modifiers))
                        .as("%s must be final - a reassignable attribute byte is mutable global state",
                                mnemonic)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("the class declares no mutable state - nothing non-final, and no arrays")
        void theClassDeclaresNoMutableState() {
            for (Field field : BmsAttributes.class.getDeclaredFields()) {
                if (field.isSynthetic()) {
                    continue;
                }
                int modifiers = field.getModifiers();

                assertThat(Modifier.isStatic(modifiers))
                        .as("field %s must be static", field.getName())
                        .isTrue();
                assertThat(Modifier.isFinal(modifiers))
                        .as("field %s must be final", field.getName())
                        .isTrue();
                assertThat(field.getType().isArray())
                        .as("field %s must not be an array - a static final array is still mutable",
                                field.getName())
                        .isFalse();
            }
        }

        @Test
        @DisplayName("the mnemonic maps reject mutation")
        void theMnemonicMapsAreUnmodifiable() {
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .as("FIELD_ATTRIBUTE_MNEMONICS must be unmodifiable")
                    .isThrownBy(() -> BmsAttributes.FIELD_ATTRIBUTE_MNEMONICS
                            .put(BmsAttributes.DFHBMUNP, "MUTATED"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .as("COLOUR_MNEMONICS must be unmodifiable")
                    .isThrownBy(() -> BmsAttributes.COLOUR_MNEMONICS
                            .put(BmsAttributes.DFHRED, "MUTATED"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .as("HIGHLIGHT_MNEMONICS must be unmodifiable")
                    .isThrownBy(() -> BmsAttributes.HIGHLIGHT_MNEMONICS
                            .put(BmsAttributes.DFHBLINK, "MUTATED"));
        }

        @Test
        @DisplayName("the class is final")
        void theClassIsFinal() {
            assertThat(Modifier.isFinal(BmsAttributes.class.getModifiers()))
                    .as("a constant holder must not be extensible")
                    .isTrue();
        }

        @Test
        @DisplayName("the class cannot be instantiated, not even reflectively")
        void cannotBeInstantiated() throws ReflectiveOperationException {
            Constructor<BmsAttributes> constructor = BmsAttributes.class.getDeclaredConstructor();
            constructor.setAccessible(true);

            assertThatExceptionOfType(InvocationTargetException.class)
                    .isThrownBy(constructor::newInstance)
                    .withCauseInstanceOf(AssertionError.class);
        }

        @Test
        @DisplayName("the only constructor is private")
        void theOnlyConstructorIsPrivate() {
            Constructor<?>[] constructors = BmsAttributes.class.getDeclaredConstructors();

            assertThat(constructors).hasSize(1);
            assertThat(Modifier.isPrivate(constructors[0].getModifiers()))
                    .as("the constructor must be private so the type is used as a namespace only")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("Bit predicates - the 3270 attribute byte decoded, every branch both ways")
    class BitPredicates {
        @ParameterizedTest(name = "{0}: prot={1} num={2} skip={3} bright={4} dark={5} mdt={6}")
        @CsvSource({
            "DFHBMUNP,     false,   false,    false,       false,      false, false",
            "DFHBMFSE,     false,   false,    false,       false,      false,  true",
            "DFHBMBRY,     false,   false,    false,        true,      false, false",
            "DFHUNIMD,     false,   false,    false,        true,      false,  true",
            "DFHBMDAR,     false,   false,    false,       false,       true, false",
            "DFHUNNOD,     false,   false,    false,       false,       true,  true",
            "DFHBMUNN,     false,    true,    false,       false,      false, false",
            "DFHUNNUM,     false,    true,    false,       false,      false,  true",
            "DFHUNNUB,     false,    true,    false,        true,      false, false",
            "DFHUNINT,     false,    true,    false,        true,      false,  true",
            "DFHUNNON,     false,    true,    false,       false,       true,  true",
            "DFHBMPRO,      true,   false,    false,       false,      false, false",
            "DFHBMPRF,      true,   false,    false,       false,      false,  true",
            "DFHPROTI,      true,   false,    false,        true,      false, false",
            "DFHPROTN,      true,   false,    false,       false,       true, false",
            "DFHBMASK,      true,    true,     true,       false,      false, false",
            "DFHBMASF,      true,    true,     true,       false,      false,  true",
            "DFHBMASB,      true,    true,     true,        true,      false, false",
        })
        @DisplayName("every basic field attribute byte decodes to its documented bit meanings")
        void everyAttributeByteDecodesToItsDocumentedBits(String mnemonic,
                                                         boolean expectedProtected,
                                                         boolean expectedNumeric,
                                                         boolean expectedAutoskip,
                                                         boolean expectedIntensified,
                                                         boolean expectedNonDisplay,
                                                         boolean expectedModifiedDataTag) {
            byte attribute = attributeByte(mnemonic);
            String where = mnemonic + " " + BmsAttributes.toHex(attribute);

            assertThat(BmsAttributes.isProtected(attribute))
                    .as("%s - bit 2, protection against keyboard entry", where)
                    .isEqualTo(expectedProtected);
            assertThat(BmsAttributes.isNumeric(attribute))
                    .as("%s - bit 3, the numeric keyboard shift", where)
                    .isEqualTo(expectedNumeric);
            assertThat(BmsAttributes.isAutoskip(attribute))
                    .as("%s - protected and numeric together, so the cursor jumps past the field",
                            where)
                    .isEqualTo(expectedAutoskip);
            assertThat(BmsAttributes.isIntensified(attribute))
                    .as("%s - bits 4-5 set to binary 10, not merely a single bit test", where)
                    .isEqualTo(expectedIntensified);
            assertThat(BmsAttributes.isNonDisplay(attribute))
                    .as("%s - bits 4-5 set to binary 11", where)
                    .isEqualTo(expectedNonDisplay);
            assertThat(BmsAttributes.isModifiedDataTagSet(attribute))
                    .as("%s - bit 7, whether CICS returns the field untouched", where)
                    .isEqualTo(expectedModifiedDataTag);
        }

        @Test
        @DisplayName("autoskip holds for exactly DFHBMASK, DFHBMASF and DFHBMASB")
        void autoskipIsStrictlyStrongerThanProtection() {
            assertThat(BmsAttributes.isAutoskip(BmsAttributes.DFHBMASK)).isTrue();
            assertThat(BmsAttributes.isAutoskip(BmsAttributes.DFHBMASF)).isTrue();
            assertThat(BmsAttributes.isAutoskip(BmsAttributes.DFHBMASB)).isTrue();

            assertThat(BmsAttributes.isAutoskip(BmsAttributes.DFHBMPRO))
                    .as("DFHBMPRO is protected but not autoskip - the cursor stops in the field")
                    .isFalse();
            assertThat(BmsAttributes.isAutoskip(BmsAttributes.DFHBMPRF))
                    .as("DFHBMPRF is protected but not autoskip")
                    .isFalse();
            assertThat(BmsAttributes.isAutoskip(BmsAttributes.DFHBMFSE))
                    .as("DFHBMFSE is unprotected, so the conjunction short-circuits before the "
                            + "numeric bit is even examined")
                    .isFalse();
        }

        @Test
        @DisplayName("the modified data tag is what separates DFHBMPRO from DFHBMPRF")
        void theModifiedDataTagSeparatesTheProtectedPair() {
            assertThat(BmsAttributes.isModifiedDataTagSet(BmsAttributes.DFHBMPRO)).isFalse();
            assertThat(BmsAttributes.isModifiedDataTagSet(BmsAttributes.DFHBMPRF)).isTrue();
            assertThat(BmsAttributes.isProtected(BmsAttributes.DFHBMPRO))
                    .as("both halves of the pair are protected; only the tag differs")
                    .isEqualTo(BmsAttributes.isProtected(BmsAttributes.DFHBMPRF));

            assertThat(BmsAttributes.isModifiedDataTagSet(BmsAttributes.DFHBMUNP)).isFalse();
            assertThat(BmsAttributes.isModifiedDataTagSet(BmsAttributes.DFHBMFSE)).isTrue();
        }

        @Test
        @DisplayName("non-display and intensified are mutually exclusive on every attribute byte")
        void nonDisplayAndIntensifiedAreMutuallyExclusive() {
            for (String mnemonic : FIELD_ATTRIBUTE_NAMES) {
                byte attribute = attributeByte(mnemonic);

                assertThat(BmsAttributes.isIntensified(attribute)
                                && BmsAttributes.isNonDisplay(attribute))
                        .as("%s cannot be both intensified and non-display - bits 4-5 are one "
                                + "selector with four states", mnemonic)
                        .isFalse();
            }
        }
    }

    @Nested
    @DisplayName("Diagnostics - unsigned, hex rendering and per-plane mnemonic lookup")
    class Diagnostics {
        @ParameterizedTest(name = "unsigned({0}) = {1}")
        @CsvSource({
            "DFHDFCOL, 0",
            "DFHBMUNP, 64",
            "DFHBMDAR, 76",
            "DFHBMPRO, 96",
            "DFHBMFSE, 193",
            "DFHRED, 242",
            "DFHNEUTR, 247",
            "DFHBMASB, 248",
        })
        @DisplayName("unsigned() reads the byte as 0-255 rather than as a negative signed value")
        void unsignedReadsTheByteAsZeroToTwoHundredFiftyFive(String mnemonic, int expected) {
            assertThat(BmsAttributes.unsigned(attributeByte(mnemonic)))
                    .as("%s must read as %d unsigned", mnemonic, expected)
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("toHex renders the copybook's X'hh' notation in upper case")
        void toHexRendersTheCopybookNotation() {
            assertThat(BmsAttributes.toHex(BmsAttributes.DFHBMFSE)).isEqualTo("X'C1'");
            assertThat(BmsAttributes.toHex(BmsAttributes.DFHRED)).isEqualTo("X'F2'");
            assertThat(BmsAttributes.toHex(BmsAttributes.DFHDFCOL))
                    .as("a zero byte must still render two digits, not one")
                    .isEqualTo("X'00'");
            assertThat(BmsAttributes.toHex(BmsAttributes.DFHBMDAR))
                    .as("0x4C is below 0x80 and must not be sign-extended into eight hex digits")
                    .isEqualTo("X'4C'");
        }

        @Test
        @DisplayName("a known byte resolves to its mnemonic in each plane")
        void aKnownByteResolvesToItsMnemonic() {
            assertThat(BmsAttributes.fieldAttributeMnemonic(BmsAttributes.DFHBMFSE))
                    .isEqualTo("DFHBMFSE");
            assertThat(BmsAttributes.colourMnemonic(BmsAttributes.DFHRED)).isEqualTo("DFHRED");
            assertThat(BmsAttributes.highlightMnemonic(BmsAttributes.DFHREVRS))
                    .isEqualTo("DFHREVRS");
        }

        @Test
        @DisplayName("an unrecognised byte falls back to X'hh' in each plane")
        void anUnrecognisedByteFallsBackToHex() {
            assertThat(BmsAttributes.fieldAttributeMnemonic(BmsAttributes.DFHRED))
                    .as("0xF2 is red in the colour plane and an unnamed combination in Section 1")
                    .isEqualTo("X'F2'");
            assertThat(BmsAttributes.colourMnemonic(BmsAttributes.DFHBMFSE))
                    .as("0xC1 is a field attribute, not one of the eight colour codes")
                    .isEqualTo("X'C1'");
            assertThat(BmsAttributes.highlightMnemonic(BmsAttributes.DFHNEUTR))
                    .as("0xF7 is a colour code; only 0x00, 0xF1, 0xF2 and 0xF4 are highlighting")
                    .isEqualTo("X'F7'");
        }

        @Test
        @DisplayName("0xF1 names a different thing in each of the three planes")
        void oneByteNamesADifferentThingInEachPlane() {
            byte shared = BmsAttributes.DFHBMASF;

            assertThat(BmsAttributes.fieldAttributeMnemonic(shared)).isEqualTo("DFHBMASF");
            assertThat(BmsAttributes.colourMnemonic(shared)).isEqualTo("DFHBLUE");
            assertThat(BmsAttributes.highlightMnemonic(shared)).isEqualTo("DFHBLINK");
        }

        @Test
        @DisplayName("every constant resolves to its own mnemonic within its own plane")
        void everyConstantResolvesToItsOwnMnemonic() {
            for (String mnemonic : FIELD_ATTRIBUTE_NAMES) {
                assertThat(BmsAttributes.fieldAttributeMnemonic(attributeByte(mnemonic)))
                        .as("Section 1 lookup of %s", mnemonic)
                        .isEqualTo(mnemonic);
            }
            for (String mnemonic : COLOUR_NAMES) {
                assertThat(BmsAttributes.colourMnemonic(attributeByte(mnemonic)))
                        .as("Section 2 lookup of %s", mnemonic)
                        .isEqualTo(mnemonic);
            }
            for (String mnemonic : HIGHLIGHT_NAMES) {
                assertThat(BmsAttributes.highlightMnemonic(attributeByte(mnemonic)))
                        .as("Section 3 lookup of %s", mnemonic)
                        .isEqualTo(mnemonic);
            }
        }
    }
}
