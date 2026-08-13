package com.vsergeychik.carddemo.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Unit tests for {@link CicsAid}, the Java reproduction of the IBM-supplied CICS {@code DFHAID} copybook.
 */
@DisplayName("CicsAid - DFHAID attention identifiers, reproduced from IBM CICS documentation")
class CicsAidTest {
    private static final int DECLARED_AID_COUNT = 36;

    private static final int PF_KEY_COUNT = 24;

    private static final int REFERENCED_COUNT = 28;

    private static final int UNCONSUMED_COUNT = 8;

    private static final String EXPECTED_CODE_PAGE = "IBM037";

    private static final String MNEMONIC_PREFIX = "DFH";

    private static final String PF_PREFIX = "DFHPF";

    /**
     * One row of the audit table: a {@code DFHAID} mnemonic, the byte IBM publishes for it, the character
     * literal the copybook declares it with, and how many times this repository's legacy COBOL references
     * it.
     *
     * @param mnemonic the constant name, which must match the field name on {@link CicsAid} exactly
     * @param aidByte the AID code as an unsigned value in {@code 0x00}-{@code 0xFF}, held as an {@code int}
     *     so the table can be written in hexadecimal without a cast on every row
     * @param copybookLiteral the character the IBM {@code DFHAID} copybook uses to express that byte, which
     *     encodes back to {@code aidByte} under {@link CicsAid#AID_CODE_PAGE}
     * @param legacyReferences how many times the legacy COBOL references the mnemonic
     */
    private record DocumentedAid(String mnemonic, int aidByte, char copybookLiteral,
            int legacyReferences) {
        byte expectedByte() {
            return (byte) aidByte;
        }
    }

    private static final List<DocumentedAid> DOCUMENTED_AIDS = List.of(
            new DocumentedAid("DFHNULL", 0x40, ' ', 0),
            new DocumentedAid("DFHENTER", 0x7D, '\'', 17),
            new DocumentedAid("DFHCLEAR", 0x6D, '_', 1),
            new DocumentedAid("DFHCLRP", 0x6A, '\u00A6', 0),
            new DocumentedAid("DFHPEN", 0x7E, '=', 0),
            new DocumentedAid("DFHOPID", 0xE6, 'W', 0),
            new DocumentedAid("DFHMSRE", 0xE7, 'X', 0),
            new DocumentedAid("DFHSTRF", 0x88, 'h', 0),
            new DocumentedAid("DFHTRIG", 0x7F, '"', 0),
            new DocumentedAid("DFHPA1", 0x6C, '%', 1),
            new DocumentedAid("DFHPA2", 0x6E, '>', 1),
            new DocumentedAid("DFHPA3", 0x6B, ',', 0),
            new DocumentedAid("DFHPF1", 0xF1, '1', 1),
            new DocumentedAid("DFHPF2", 0xF2, '2', 1),
            new DocumentedAid("DFHPF3", 0xF3, '3', 15),
            new DocumentedAid("DFHPF4", 0xF4, '4', 7),
            new DocumentedAid("DFHPF5", 0xF5, '5', 5),
            new DocumentedAid("DFHPF6", 0xF6, '6', 1),
            new DocumentedAid("DFHPF7", 0xF7, '7', 5),
            new DocumentedAid("DFHPF8", 0xF8, '8', 5),
            new DocumentedAid("DFHPF9", 0xF9, '9', 1),
            new DocumentedAid("DFHPF10", 0x7A, ':', 1),
            new DocumentedAid("DFHPF11", 0x7B, '#', 1),
            new DocumentedAid("DFHPF12", 0x7C, '@', 3),
            new DocumentedAid("DFHPF13", 0xC1, 'A', 1),
            new DocumentedAid("DFHPF14", 0xC2, 'B', 1),
            new DocumentedAid("DFHPF15", 0xC3, 'C', 1),
            new DocumentedAid("DFHPF16", 0xC4, 'D', 1),
            new DocumentedAid("DFHPF17", 0xC5, 'E', 1),
            new DocumentedAid("DFHPF18", 0xC6, 'F', 1),
            new DocumentedAid("DFHPF19", 0xC7, 'G', 1),
            new DocumentedAid("DFHPF20", 0xC8, 'H', 1),
            new DocumentedAid("DFHPF21", 0xC9, 'I', 1),
            new DocumentedAid("DFHPF22", 0x4A, '\u00A2', 1),
            new DocumentedAid("DFHPF23", 0x4B, '.', 1),
            new DocumentedAid("DFHPF24", 0x4C, '<', 1));

    private static final List<String> CSSTRPFY_SYMBOLS = Stream.concat(
            Stream.of("DFHENTER", "DFHCLEAR", "DFHPA1", "DFHPA2"),
            IntStream.rangeClosed(1, PF_KEY_COUNT).mapToObj(number -> PF_PREFIX + number))
            .toList();

    private static final Map<String, Integer> INLINE_REFERENCE_COUNTS = Map.of(
            "DFHENTER", 16,
            "DFHPF3", 14,
            "DFHPF4", 6,
            "DFHPF5", 4,
            "DFHPF7", 4,
            "DFHPF8", 4,
            "DFHPF12", 2);

    private static final Set<String> UNCONSUMED_MNEMONICS = Set.of(
            "DFHNULL", "DFHCLRP", "DFHPEN", "DFHOPID", "DFHMSRE", "DFHSTRF", "DFHTRIG", "DFHPA3");

    private static List<Field> realDeclaredFields() {
        return Arrays.stream(CicsAid.class.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .filter(field -> !field.getName().startsWith("$"))
                .sorted(Comparator.comparing(Field::getName))
                .toList();
    }

    private static List<Field> aidFields() {
        return realDeclaredFields().stream()
                .filter(field -> field.getName().startsWith(MNEMONIC_PREFIX))
                .toList();
    }

    private static byte aidValue(String mnemonic) {
        try {
            return CicsAid.class.getDeclaredField(mnemonic).getByte(null);
        } catch (ReflectiveOperationException | IllegalArgumentException cause) {
            throw new AssertionError("CicsAid does not declare a byte constant named " + mnemonic
                    + ", which the legacy COBOL in this repository references; every consumer of "
                    + "that mnemonic will fail to compile", cause);
        }
    }

    private static List<Byte> pfValues(int firstKey, int lastKey) {
        return IntStream.rangeClosed(firstKey, lastKey)
                .mapToObj(number -> aidValue(PF_PREFIX + number))
                .toList();
    }

    private static Charset declaredCodePage() {
        return Charset.forName(CicsAid.AID_CODE_PAGE);
    }

    static Stream<Arguments> documentedAids() {
        return DOCUMENTED_AIDS.stream()
                .map(aid -> Arguments.of(aid.mnemonic(), aid.expectedByte(), aid.copybookLiteral()));
    }

    static Stream<String> csstrpfySymbols() {
        return CSSTRPFY_SYMBOLS.stream();
    }

    static Stream<Arguments> inlineReferencedSymbols() {
        return INLINE_REFERENCE_COUNTS.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> Arguments.of(entry.getKey(), entry.getValue()));
    }

    @Nested
    @DisplayName("Completeness - every mnemonic the COBOL references is declared (G11)")
    class Completeness {
        @ParameterizedTest(name = "CSSTRPFY branches on {0}")
        @MethodSource("com.vsergeychik.carddemo.common.CicsAidTest#csstrpfySymbols")
        @DisplayName("every mnemonic CSSTRPFY's EVALUATE branches on is declared")
        void everySymbolCsstrpfyBranchesOnIsDeclared(String mnemonic) {
            byte value = aidValue(mnemonic);

            assertThat(CicsAid.mnemonicsByAid())
                    .as("%s is a WHEN branch of YYYY-STORE-PFKEY in app/cpy/CSSTRPFY.cpy and must "
                            + "resolve back to its own mnemonic", mnemonic)
                    .containsEntry(value, mnemonic);
        }

        @Test
        @DisplayName("CSSTRPFY's consumer list is the expected twenty-eight mnemonics")
        void theCsstrpfyConsumerListIsTwentyEightMnemonics() {
            assertThat(CSSTRPFY_SYMBOLS)
                    .as("YYYY-STORE-PFKEY has one WHEN branch per mnemonic")
                    .hasSize(REFERENCED_COUNT)
                    .doesNotHaveDuplicates()
                    .contains("DFHENTER", "DFHCLEAR", "DFHPA1", "DFHPA2")
                    .doesNotContain("DFHPA3");
        }

        @Test
        @DisplayName("exactly twenty-four PF-key constants exist, numbered 1 to 24 with no gap")
        void exactlyTwentyFourPfKeyConstantsExist() {
            List<Integer> declaredPfNumbers = aidFields().stream()
                    .map(Field::getName)
                    .filter(name -> name.matches("DFHPF\\d+"))
                    .map(name -> Integer.valueOf(name.substring(PF_PREFIX.length())))
                    .sorted()
                    .toList();

            assertThat(declaredPfNumbers)
                    .as("CSSTRPFY branches on all twenty-four PF keys, so all twenty-four must "
                            + "exist")
                    .hasSize(PF_KEY_COUNT)
                    .containsExactlyElementsOf(
                            IntStream.rangeClosed(1, PF_KEY_COUNT).boxed().toList());
        }

        @Test
        @DisplayName("DFHPA3 is declared even though nothing in the repository consumes it (B5)")
        void dfhpa3IsDeclaredDespiteHavingNoConsumer() {
            assertThat(aidFields())
                    .as("DFHPA3 is mandated by the plan and must not be pruned as dead code")
                    .anyMatch(field -> "DFHPA3".equals(field.getName()));

            assertThat(UNCONSUMED_MNEMONICS).contains("DFHPA3");
            assertThat(CSSTRPFY_SYMBOLS).doesNotContain("DFHPA3");
        }

        @Test
        @DisplayName("all eight unconsumed mnemonics are declared, none pruned as dead code (B5)")
        void everyUnconsumedMnemonicIsStillDeclared() {
            List<String> declaredNames = aidFields().stream().map(Field::getName).toList();

            assertThat(declaredNames)
                    .as("the seven copybook-completeness members plus DFHPA3 are retained under B5")
                    .containsAll(UNCONSUMED_MNEMONICS);
            assertThat(UNCONSUMED_MNEMONICS).hasSize(UNCONSUMED_COUNT);
        }

        @ParameterizedTest(name = "{0} - {1} inline EIBAID site(s) in app/cbl")
        @MethodSource("com.vsergeychik.carddemo.common.CicsAidTest#inlineReferencedSymbols")
        @DisplayName("every mnemonic a controller tests inline against EIBAID is declared")
        void everySymbolTestedInlineByAControllerIsDeclared(String mnemonic, int inlineSites) {
            byte value = aidValue(mnemonic);

            assertThat(CicsAid.mnemonicsByAid())
                    .as("%s is compared to EIBAID at %d inline site(s) in app/cbl by controllers "
                            + "that do not copy CSSTRPFY", mnemonic, inlineSites)
                    .containsEntry(value, mnemonic);
            assertThat(value)
                    .as("%s is an actively used key and must not collapse onto the DFHNULL "
                            + "sentinel", mnemonic)
                    .isNotEqualTo(CicsAid.DFHNULL);
        }

        @Test
        @DisplayName("the declared set is exactly the thirty-six documented mnemonics")
        void theDeclaredSetIsExactlyTheDocumentedSet() {
            List<String> declaredNames = aidFields().stream().map(Field::getName).toList();
            List<String> documentedNames = DOCUMENTED_AIDS.stream()
                    .map(DocumentedAid::mnemonic)
                    .toList();

            assertThat(declaredNames).hasSize(DECLARED_AID_COUNT).doesNotHaveDuplicates();
            assertThat(documentedNames).hasSize(DECLARED_AID_COUNT).doesNotHaveDuplicates();
            assertThat(declaredNames)
                    .as("the audit table and the class must describe the same member set; a "
                            + "constant added or removed without updating the table is a defect "
                            + "in one of the two")
                    .containsExactlyInAnyOrderElementsOf(documentedNames);
        }

        @Test
        @DisplayName("the referenced and unconsumed partitions reconcile to the declared total")
        void theReferencedAndUnconsumedPartitionsReconcile() {
            List<String> referenced = DOCUMENTED_AIDS.stream()
                    .filter(aid -> aid.legacyReferences() > 0)
                    .map(DocumentedAid::mnemonic)
                    .toList();
            List<String> unconsumed = DOCUMENTED_AIDS.stream()
                    .filter(aid -> aid.legacyReferences() == 0)
                    .map(DocumentedAid::mnemonic)
                    .toList();

            assertThat(referenced)
                    .as("the referenced set must be exactly CSSTRPFY's consumer list")
                    .hasSize(REFERENCED_COUNT)
                    .containsExactlyInAnyOrderElementsOf(CSSTRPFY_SYMBOLS);
            assertThat(unconsumed)
                    .as("the unconsumed set must be exactly the eight retained under B5")
                    .hasSize(UNCONSUMED_COUNT)
                    .containsExactlyInAnyOrderElementsOf(UNCONSUMED_MNEMONICS);
            assertThat(referenced.size() + unconsumed.size()).isEqualTo(DECLARED_AID_COUNT);
        }

        @Test
        @DisplayName("no mnemonic is claimed both to be referenced and to be unconsumed")
        void theTwoPartitionsDoNotOverlap() {
            assertThat(CSSTRPFY_SYMBOLS)
                    .as("a mnemonic cannot be both a CSSTRPFY branch and unreferenced")
                    .doesNotContainAnyElementsOf(UNCONSUMED_MNEMONICS);
            assertThat(INLINE_REFERENCE_COUNTS.keySet())
                    .as("every inline-tested mnemonic is also a CSSTRPFY branch")
                    .allSatisfy(mnemonic -> assertThat(CSSTRPFY_SYMBOLS).contains(mnemonic));
        }
    }

    @Nested
    @DisplayName("Distinctness - no two AIDs share a byte, so no key routes into another's branch")
    class Distinctness {
        @Test
        @DisplayName("all thirty-six AID values are pairwise distinct")
        void everyAidValueIsPairwiseDistinct() throws ReflectiveOperationException {
            List<Field> fields = aidFields();
            Map<Byte, String> firstOwnerOfValue = new HashMap<>();
            List<String> collisions = new ArrayList<>();

            for (Field field : fields) {
                byte value = field.getByte(null);
                String incumbent = firstOwnerOfValue.putIfAbsent(value, field.getName());
                if (incumbent != null) {
                    collisions.add(String.format("%s and %s both equal 0x%02X",
                            incumbent, field.getName(), value));
                }
            }

            assertThat(collisions)
                    .as("CSSTRPFY's ordered EVALUATE would silently route one key into another "
                            + "key's branch if two AID constants shared a byte")
                    .isEmpty();
            assertThat(firstOwnerOfValue)
                    .as("thirty-six distinct mnemonics must occupy thirty-six distinct bytes")
                    .hasSize(fields.size())
                    .hasSize(DECLARED_AID_COUNT);
        }

        @Test
        @DisplayName("ENTER and CLEAR differ, and neither collides with any PF key")
        void enterAndClearAreDistinctAndNeitherIsAPfKey() {
            assertThat(CicsAid.DFHENTER)
                    .as("ENTER and CLEAR are opposite outcomes on every screen")
                    .isNotEqualTo(CicsAid.DFHCLEAR);

            List<Byte> allPfValues = pfValues(1, PF_KEY_COUNT);
            assertThat(allPfValues)
                    .as("ENTER must not be mistaken for a function key")
                    .doesNotContain(CicsAid.DFHENTER);
            assertThat(allPfValues)
                    .as("CLEAR must not be mistaken for a function key")
                    .doesNotContain(CicsAid.DFHCLEAR);
        }

        @Test
        @DisplayName("PF1-PF12 are disjoint from PF13-PF24, so the resolver's fold is not vacuous")
        void theLowPfRangeIsDisjointFromTheHighPfRange() {
            List<Byte> lowRange = pfValues(1, 12);
            List<Byte> highRange = pfValues(13, PF_KEY_COUNT);

            assertThat(lowRange).hasSize(12).doesNotHaveDuplicates();
            assertThat(highRange).hasSize(12).doesNotHaveDuplicates();
            assertThat(lowRange)
                    .as("CSSTRPFY folds PF13-PF24 onto PFK01-PFK12; the fold maps distinct AIDs "
                            + "onto shared outcomes, and a shared AID would make the twelve high "
                            + "branches unreachable")
                    .doesNotContainAnyElementsOf(highRange);
        }

        @Test
        @DisplayName("PA1, PA2 and PA3 differ from each other and from every other constant")
        void theProgramAccessKeysAreDistinctFromEachOtherAndFromEverythingElse()
                throws ReflectiveOperationException {
            assertThat(List.of(CicsAid.DFHPA1, CicsAid.DFHPA2, CicsAid.DFHPA3))
                    .as("the three program access keys are three different keys")
                    .doesNotHaveDuplicates();

            for (Field field : aidFields()) {
                if (field.getName().startsWith("DFHPA")) {
                    continue;
                }
                assertThat(field.getByte(null))
                        .as("%s must not collide with a program access key", field.getName())
                        .isNotEqualTo(CicsAid.DFHPA1)
                        .isNotEqualTo(CicsAid.DFHPA2)
                        .isNotEqualTo(CicsAid.DFHPA3);
            }
        }

        @Test
        @DisplayName("the sentinel is distinct from every real key")
        void theNullSentinelIsDistinctFromEveryRealKey() throws ReflectiveOperationException {
            for (Field field : aidFields()) {
                if ("DFHNULL".equals(field.getName())) {
                    continue;
                }
                assertThat(field.getByte(null))
                        .as("%s must be distinguishable from the DFHNULL sentinel",
                                field.getName())
                        .isNotEqualTo(CicsAid.DFHNULL);
            }
        }
    }

    @Nested
    @DisplayName("Shape and encoding - one byte each, in an explicitly named code page")
    class ShapeAndEncoding {
        @Test
        @DisplayName("every AID constant is declared as a primitive byte, so it is one byte wide")
        void everyAidConstantIsDeclaredAsAPrimitiveByte() {
            List<Field> fields = aidFields();

            assertThat(fields).hasSize(DECLARED_AID_COUNT);
            assertThat(fields).allSatisfy(field -> assertThat(field.getType())
                    .as("%s must be a primitive byte: a char literal would silently carry a "
                            + "Unicode code point instead of the EBCDIC AID byte", field.getName())
                    .isEqualTo(byte.class));
        }

        @Test
        @DisplayName("the code page is named explicitly, resolves, and is IBM037")
        void theCodePageIsNamedExplicitlyAndResolves() {
            assertThat(CicsAid.AID_CODE_PAGE)
                    .as("the EBCDIC code page must be named on the class, not left to a comment")
                    .isEqualTo(EXPECTED_CODE_PAGE);
            assertThat(Charset.isSupported(CicsAid.AID_CODE_PAGE))
                    .as("a named code page that the JDK cannot resolve is not a usable declaration")
                    .isTrue();
            assertThat(declaredCodePage().name()).isEqualTo(EXPECTED_CODE_PAGE);
        }

        @Test
        @DisplayName("the code page declaration is load-bearing, not decorative")
        void theCodePageDeclarationIsLoadBearing() {
            assertThat("'".getBytes(declaredCodePage()))
                    .as("under the declared code page the ENTER literal is the declared AID byte")
                    .containsExactly(CicsAid.DFHENTER);
            assertThat("'".getBytes(StandardCharsets.US_ASCII))
                    .as("the same literal is a different byte under ASCII, which is the trap")
                    .containsExactly((byte) 0x27);
            assertThat(CicsAid.DFHENTER)
                    .as("DFHENTER must be the EBCDIC apostrophe 0x7D, never the ASCII 0x27")
                    .isEqualTo((byte) 0x7D)
                    .isNotEqualTo((byte) 0x27);
        }

        @Test
        @DisplayName("every AID byte decodes to exactly one printable character in that code page")
        void everyAidByteDecodesToExactlyOnePrintableCharacter()
                throws ReflectiveOperationException {
            Charset codePage = declaredCodePage();

            for (Field field : aidFields()) {
                byte value = field.getByte(null);
                String decoded = new String(new byte[] {value}, codePage);

                assertThat(decoded)
                        .as("%s (0x%02X) must decode to exactly one character under %s",
                                field.getName(), value, EXPECTED_CODE_PAGE)
                        .hasSize(1);
                assertThat(decoded.charAt(0))
                        .as("%s (0x%02X) decoded to the Unicode replacement character, so it is "
                                + "not a valid code point in %s", field.getName(), value,
                                EXPECTED_CODE_PAGE)
                        .isNotEqualTo('\uFFFD');
                assertThat(Character.isISOControl(decoded.charAt(0)))
                        .as("%s (0x%02X) decoded to a control character; every DFHAID member is a "
                                + "printable literal", field.getName(), value)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("no AID constant is the zero byte")
        void noAidConstantIsTheZeroByte() throws ReflectiveOperationException {
            for (Field field : aidFields()) {
                assertThat(field.getByte(null))
                        .as("%s must not be the zero byte, which is indistinguishable from "
                                + "uninitialised storage", field.getName())
                        .isNotZero();
            }
        }

        @Test
        @DisplayName("DFHNULL is the EBCDIC space sentinel, not the zero byte")
        void theNullSentinelIsTheEbcdicSpaceRatherThanZero() {
            assertThat(CicsAid.DFHNULL)
                    .as("DFHNULL is the EBCDIC space, 0x40")
                    .isEqualTo((byte) 0x40)
                    .isNotEqualTo((byte) 0x00);
            assertThat(new String(new byte[] {CicsAid.DFHNULL}, declaredCodePage()))
                    .as("0x40 renders as a space under the declared code page")
                    .isEqualTo(" ");
            assertThat(UNCONSUMED_MNEMONICS)
                    .as("DFHNULL has no consumer in this repository and is retained for "
                            + "copybook completeness")
                    .contains("DFHNULL");
        }
    }

    @Nested
    @DisplayName("Value provenance - IBM CICS documentation, audited via the JDK's IBM037 tables")
    class ValueProvenance {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.vsergeychik.carddemo.common.CicsAidTest#documentedAids")
        @DisplayName("each constant equals the AID code IBM publishes for that key")
        void constantEqualsTheAidCodeIbmPublishes(String mnemonic, byte documentedByte,
                char copybookLiteral) {
            assertThat(aidValue(mnemonic))
                    .as("%s: IBM's 3270 attention identifier table gives 0x%02X, which the copybook"
                            + " expresses as the literal '%s'", mnemonic, documentedByte,
                            copybookLiteral)
                    .isEqualTo(documentedByte);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.vsergeychik.carddemo.common.CicsAidTest#documentedAids")
        @DisplayName("each constant agrees with its copybook character literal under IBM037")
        void constantAgreesWithItsCopybookLiteralUnderTheDeclaredCodePage(String mnemonic,
                byte documentedByte, char copybookLiteral) {
            byte[] encodedLiteral = String.valueOf(copybookLiteral).getBytes(declaredCodePage());

            assertThat(encodedLiteral)
                    .as("%s: the copybook literal must occupy exactly one byte under %s, as a "
                            + "PIC X(1) field does", mnemonic, EXPECTED_CODE_PAGE)
                    .hasSize(1);
            assertThat(encodedLiteral[0])
                    .as("%s: encoding the copybook literal through the JDK's %s tables must "
                            + "reproduce the AID code IBM publishes, 0x%02X", mnemonic,
                            EXPECTED_CODE_PAGE, documentedByte)
                    .isEqualTo(documentedByte);
            assertThat(aidValue(mnemonic))
                    .as("%s: the declared constant must equal that independently derived byte",
                            mnemonic)
                    .isEqualTo(encodedLiteral[0]);
        }

        @Test
        @DisplayName("the EBCDIC digit run covers PF1-PF9 and stops there")
        void theDigitRunCoversPf1ThroughPf9AndStops() {
            for (int pfNumber = 1; pfNumber <= 9; pfNumber++) {
                assertThat(aidValue(PF_PREFIX + pfNumber))
                        .as("DFHPF%d is the EBCDIC digit '%d'", pfNumber, pfNumber)
                        .isEqualTo((byte) (0xF0 + pfNumber));
            }
        }

        @Test
        @DisplayName("PF10-PF12 are punctuation, not the 0xFA-0xFC continuation of the digit run")
        void pf10ThroughPf12AreNotTheContinuationOfTheDigitRun() {
            assertThat(aidValue("DFHPF10"))
                    .as("DFHPF10 is the EBCDIC colon 0x7A, not 0xFA")
                    .isEqualTo((byte) 0x7A)
                    .isNotEqualTo((byte) 0xFA);
            assertThat(aidValue("DFHPF11"))
                    .as("DFHPF11 is the EBCDIC number sign 0x7B, not 0xFB")
                    .isEqualTo((byte) 0x7B)
                    .isNotEqualTo((byte) 0xFB);
            assertThat(aidValue("DFHPF12"))
                    .as("DFHPF12 is the EBCDIC commercial-at 0x7C, not 0xFC")
                    .isEqualTo((byte) 0x7C)
                    .isNotEqualTo((byte) 0xFC);
        }

        @Test
        @DisplayName("the capital letter run covers PF13-PF21 as 'A' to 'I' only")
        void theLetterRunCoversPf13ThroughPf21Only() {
            for (int pfNumber = 13; pfNumber <= 21; pfNumber++) {
                char letter = (char) ('A' + pfNumber - 13);
                assertThat(aidValue(PF_PREFIX + pfNumber))
                        .as("DFHPF%d is the EBCDIC capital '%s'", pfNumber, letter)
                        .isEqualTo((byte) (0xC1 + pfNumber - 13));
            }

            assertThat(aidValue("DFHPF21"))
                    .as("the letter run ends at 'I' = 0xC9, because 'J' is 0xD1 and not 0xCA")
                    .isEqualTo((byte) 0xC9);
        }

        @Test
        @DisplayName("PF22-PF24 revert to punctuation and are not letters")
        void pf22ThroughPf24RevertToPunctuation() {
            assertThat(aidValue("DFHPF22"))
                    .as("DFHPF22 is the EBCDIC cent sign 0x4A, and is not the non-letter 0xCA")
                    .isEqualTo((byte) 0x4A)
                    .isNotEqualTo((byte) 0xCA);
            assertThat(aidValue("DFHPF23"))
                    .as("DFHPF23 is the EBCDIC period 0x4B")
                    .isEqualTo((byte) 0x4B);
            assertThat(aidValue("DFHPF24"))
                    .as("DFHPF24 is the EBCDIC less-than sign 0x4C")
                    .isEqualTo((byte) 0x4C);
        }

        @Test
        @DisplayName("the audit table itself is internally consistent")
        void theAuditTableIsInternallyConsistent() {
            assertThat(DOCUMENTED_AIDS).hasSize(DECLARED_AID_COUNT);
            assertThat(DOCUMENTED_AIDS.stream().map(DocumentedAid::mnemonic).toList())
                    .doesNotHaveDuplicates();
            assertThat(DOCUMENTED_AIDS.stream().map(DocumentedAid::expectedByte).toList())
                    .as("two rows claiming the same byte would hide a real collision")
                    .doesNotHaveDuplicates();
            assertThat(DOCUMENTED_AIDS).allSatisfy(aid -> assertThat(aid.aidByte())
                    .as("%s: an AID code is a single unsigned octet", aid.mnemonic())
                    .isBetween(0x01, 0xFF));
        }
    }

    @Nested
    @DisplayName("mnemonicsByAid - the diagnostic lookup")
    class MnemonicLookup {
        @Test
        @DisplayName("the lookup maps every declared constant to its own mnemonic")
        void theLookupMapsEveryDeclaredConstantToItsOwnMnemonic()
                throws ReflectiveOperationException {
            Map<Byte, String> lookup = CicsAid.mnemonicsByAid();

            assertThat(lookup)
                    .as("the lookup must cover the whole AID set, or its duplicate-key guard "
                            + "protects only part of it")
                    .isNotNull()
                    .hasSize(DECLARED_AID_COUNT);

            for (Field field : aidFields()) {
                assertThat(lookup)
                        .as("%s must resolve back to its own name", field.getName())
                        .containsEntry(field.getByte(null), field.getName());
            }
        }

        @Test
        @DisplayName("the lookup is unmodifiable, so callers cannot corrupt shared state")
        void theLookupIsUnmodifiable() {
            Map<Byte, String> lookup = CicsAid.mnemonicsByAid();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .as("put must be rejected")
                    .isThrownBy(() -> lookup.put((byte) 0x01, "INJECTED"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .as("remove must be rejected even for a key that is present")
                    .isThrownBy(() -> lookup.remove(CicsAid.DFHENTER));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .as("clear must be rejected")
                    .isThrownBy(lookup::clear);
        }

        @Test
        @DisplayName("the lookup is the same instance on every call")
        void theLookupIsTheSameInstanceOnEveryCall() {
            assertThat(CicsAid.mnemonicsByAid())
                    .as("an immutable map needs no per-call copy, and callers may retain it")
                    .isSameAs(CicsAid.mnemonicsByAid());
        }

        @Test
        @DisplayName("the lookup carries no built-in fallback for an unrecognised byte")
        void theLookupCarriesNoBuiltInFallback() {
            assertThat(CicsAid.mnemonicsByAid().get((byte) 0x00))
                    .as("an unrecognised byte must map to null rather than to an invented name")
                    .isNull();
            assertThat(CicsAid.mnemonicsByAid().getOrDefault((byte) 0x00, "UNKNOWN"))
                    .as("the caller supplies the default, as the class documents")
                    .isEqualTo("UNKNOWN");
        }
    }

    @Nested
    @DisplayName("Immutability and shape - no mutable static state, not instantiable (G53)")
    class ImmutabilityAndShape {
        @Test
        @DisplayName("every AID constant is public static final")
        void everyAidConstantIsPublicStaticFinal() {
            for (Field field : aidFields()) {
                int modifiers = field.getModifiers();

                assertThat(Modifier.isPublic(modifiers))
                        .as("%s must be public: seventeen online programs reference it",
                                field.getName())
                        .isTrue();
                assertThat(Modifier.isStatic(modifiers))
                        .as("%s must be static: a copybook constant belongs to no instance",
                                field.getName())
                        .isTrue();
                assertThat(Modifier.isFinal(modifiers))
                        .as("%s must be final: a reassignable AID would misroute every key that "
                                + "follows the reassignment", field.getName())
                        .isTrue();
            }
        }

        @Test
        @DisplayName("the class holds no mutable static state and no instance state")
        void theClassHoldsNoMutableStaticStateAndNoInstanceState() {
            for (Field field : realDeclaredFields()) {
                int modifiers = field.getModifiers();

                assertThat(Modifier.isStatic(modifiers))
                        .as("%s is an instance field, but this class has no instances",
                                field.getName())
                        .isTrue();
                assertThat(Modifier.isFinal(modifiers))
                        .as("%s is mutable static state, which gate G53 forbids", field.getName())
                        .isTrue();
                assertThat(field.getType().isArray())
                        .as("%s is an array: final makes the reference constant but leaves the "
                                + "contents writable, so it is still mutable static state",
                                field.getName())
                        .isFalse();
            }
        }

        @Test
        @DisplayName("the declared fields are exactly the mnemonics, the code page and the lookup")
        void theDeclaredFieldsAreExactlyTheExpectedMembers() {
            List<String> nonMnemonicFields = realDeclaredFields().stream()
                    .map(Field::getName)
                    .filter(name -> !name.startsWith(MNEMONIC_PREFIX))
                    .toList();

            assertThat(realDeclaredFields()).hasSize(DECLARED_AID_COUNT + 2);
            assertThat(nonMnemonicFields).containsExactly("AID_CODE_PAGE", "MNEMONICS_BY_AID");
        }

        @Test
        @DisplayName("the class is final and declares exactly one private no-argument constructor")
        void theClassIsFinalWithASinglePrivateConstructor() {
            assertThat(Modifier.isFinal(CicsAid.class.getModifiers()))
                    .as("a constants holder must not be subclassable")
                    .isTrue();

            Constructor<?>[] constructors = CicsAid.class.getDeclaredConstructors();

            assertThat(constructors)
                    .as("exactly one constructor, and it exists only to be unusable")
                    .hasSize(1);
            assertThat(Modifier.isPrivate(constructors[0].getModifiers()))
                    .as("the sole constructor must be private")
                    .isTrue();
            assertThat(constructors[0].getParameterCount()).isZero();
        }

        @Test
        @DisplayName("the class cannot be instantiated, not even reflectively")
        void theClassCannotBeInstantiatedNotEvenReflectively()
                throws ReflectiveOperationException {
            Constructor<CicsAid> constructor = CicsAid.class.getDeclaredConstructor();
            constructor.setAccessible(true);

            assertThatExceptionOfType(InvocationTargetException.class)
                    .as("a private constructor alone is bypassable by reflection; throwing from it "
                            + "is what actually makes the class non-instantiable")
                    .isThrownBy(constructor::newInstance)
                    .withCauseInstanceOf(AssertionError.class);
        }

        @Test
        @DisplayName("the code page constant is a non-blank String, not a bare comment")
        void theCodePageConstantIsANonBlankString() throws ReflectiveOperationException {
            Field codePage = CicsAid.class.getDeclaredField("AID_CODE_PAGE");

            assertThat(codePage.getType()).isEqualTo(String.class);
            assertThat(Modifier.isPublic(codePage.getModifiers())).isTrue();
            assertThat(Modifier.isStatic(codePage.getModifiers())).isTrue();
            assertThat(Modifier.isFinal(codePage.getModifiers())).isTrue();
            assertThat(CicsAid.AID_CODE_PAGE)
                    .as("practice B8 requires the encoding to be named in code, where callers can "
                            + "reference it, not only in prose")
                    .isNotBlank();
        }
    }
}
