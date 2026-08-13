package com.vsergeychik.carddemo.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Byte-exactness tests for {@link ScreenTitles}, the Java form of the COBOL copybook
 * {@code app/cpy/COTTL01Y.cpy} group item {@code 01 CCDA-SCREEN-TITLE}.
 */
@DisplayName("ScreenTitles - the byte-exact COTTL01Y screen title literals")
class ScreenTitlesTest {
    private static final int EXPECTED_TITLE_LENGTH = 40;

    private static final String EXPECTED_CCDA_TITLE01 =
            "      AWS Mainframe Modernization       ";

    private static final String EXPECTED_CCDA_TITLE02 =
            "              CardDemo                  ";

    private static final String EXPECTED_CCDA_THANK_YOU =
            "Thank you for using CCDA application... ";

    private static final String COMMENTED_OUT_TITLE02_DECOY =
            "  Credit Card Demo Application (CCDA)   ";

    private static final String PRINTABLE_ASCII_ONLY = "[\\x20-\\x7E]*";

    private static final List<String> EXPECTED_CONSTANT_NAMES =
            List.of("CCDA_TITLE01", "CCDA_TITLE02", "CCDA_THANK_YOU");

    static Stream<Arguments> screenTitleConstants() {
        return Arrays.stream(ScreenTitles.class.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .filter(field -> field.getType() == String.class)
                .filter(field -> Modifier.isPublic(field.getModifiers()))
                .filter(field -> Modifier.isStatic(field.getModifiers()))
                .map(field -> Arguments.of(field.getName(), readStringConstant(field)));
    }

    private static String readStringConstant(Field field) {
        try {
            return (String) field.get(null);
        } catch (IllegalAccessException cannotRead) {
            throw new AssertionError(
                    "ScreenTitles." + field.getName() + " must be readable as a public constant",
                    cannotRead);
        }
    }

    private static Field declaredField(String name) {
        try {
            return ScreenTitles.class.getDeclaredField(name);
        } catch (NoSuchFieldException missing) {
            throw new AssertionError("ScreenTitles must declare the constant " + name, missing);
        }
    }

    private static int leadingSpaceCount(String value) {
        return value.length() - value.stripLeading().length();
    }

    private static int trailingSpaceCount(String value) {
        return value.length() - value.stripTrailing().length();
    }

    @Test
    @DisplayName("the reflected source finds exactly the three PIC X(40) items of 01 CCDA-SCREEN-TITLE")
    void screenTitleConstantsAreExactlyTheThreeCopybookItems() {
        assertThat(screenTitleConstants().map(argument -> argument.get()[0]))
                .containsExactlyInAnyOrderElementsOf(EXPECTED_CONSTANT_NAMES);
    }

    @ParameterizedTest(name = "ScreenTitles.{0} is TITLE_LENGTH characters")
    @MethodSource("screenTitleConstants")
    @DisplayName("every screen title is exactly TITLE_LENGTH characters")
    void everyScreenTitleIsExactlyTitleLengthCharacters(String name, String value) {
        assertThat(value)
                .as("ScreenTitles.%s must be exactly TITLE_LENGTH characters", name)
                .hasSize(ScreenTitles.TITLE_LENGTH)
                .hasSize(EXPECTED_TITLE_LENGTH);
    }

    @ParameterizedTest(name = "ScreenTitles.{0} is printable US-ASCII only")
    @MethodSource("screenTitleConstants")
    @DisplayName("no screen title smuggles in a tab or any other non-printable character")
    void everyScreenTitleContainsOnlyPrintableAscii(String name, String value) {
        assertThat(value)
                .as("ScreenTitles.%s must contain only printable US-ASCII characters", name)
                .matches(PRINTABLE_ASCII_ONLY);
    }

    @ParameterizedTest(name = "ScreenTitles.{0} keeps its fixed-width padding")
    @MethodSource("screenTitleConstants")
    @DisplayName("no screen title has been trimmed, stripped or whitespace-normalised")
    void noScreenTitleHasLostItsPadding(String name, String value) {
        assertThat(value.strip().length())
                .as("ScreenTitles.%s must keep its padding: the padding IS the field", name)
                .isLessThan(EXPECTED_TITLE_LENGTH);
        assertThat(value)
                .as("ScreenTitles.%s must not equal its own stripped form", name)
                .isNotEqualTo(value.strip());
    }

    @ParameterizedTest(name = "ScreenTitles.{0} is padded with spaces, not other characters")
    @MethodSource("screenTitleConstants")
    @DisplayName("leading text plus trailing padding accounts for all 40 characters")
    void everyScreenTitleAccountsForAllFortyCharacters(String name, String value) {
        int leading = leadingSpaceCount(value);
        int trailing = trailingSpaceCount(value);
        int text = value.strip().length();

        assertThat(leading + text + trailing)
                .as("ScreenTitles.%s: %d leading + %d text + %d trailing must account for all 40",
                        name, leading, text, trailing)
                .isEqualTo(EXPECTED_TITLE_LENGTH);
    }

    @Nested
    @DisplayName("TITLE_LENGTH - the PIC X(40) width")
    class DeclaredWidth {
        @Test
        @DisplayName("is 40, the width every 05-level item of 01 CCDA-SCREEN-TITLE declares")
        void titleLengthIsForty() {
            assertThat(ScreenTitles.TITLE_LENGTH).isEqualTo(EXPECTED_TITLE_LENGTH);
        }

        @Test
        @DisplayName("is the width of every constant in the class, so callers need no magic number")
        void titleLengthDescribesEveryConstant() {
            assertThat(ScreenTitles.CCDA_TITLE01).hasSize(ScreenTitles.TITLE_LENGTH);
            assertThat(ScreenTitles.CCDA_TITLE02).hasSize(ScreenTitles.TITLE_LENGTH);
            assertThat(ScreenTitles.CCDA_THANK_YOU).hasSize(ScreenTitles.TITLE_LENGTH);
        }
    }

    @Nested
    @DisplayName("CCDA_TITLE01 - '      AWS Mainframe Modernization       '")
    class CcdaTitle01 {
        @Test
        @DisplayName("is exactly 40 characters")
        void isExactlyFortyCharacters() {
            assertThat(ScreenTitles.CCDA_TITLE01).hasSize(EXPECTED_TITLE_LENGTH);
        }

        @Test
        @DisplayName("equals the copybook literal character for character")
        void equalsTheCopybookLiteral() {
            assertThat(ScreenTitles.CCDA_TITLE01).isEqualTo(EXPECTED_CCDA_TITLE01);
        }

        @Test
        @DisplayName("carries exactly six leading spaces - not five, not seven")
        void hasExactlySixLeadingSpaces() {
            assertThat(leadingSpaceCount(ScreenTitles.CCDA_TITLE01)).isEqualTo(6);
            assertThat(ScreenTitles.CCDA_TITLE01).startsWith(" ".repeat(6));
            assertThat(ScreenTitles.CCDA_TITLE01).doesNotStartWith(" ".repeat(7));
        }

        @Test
        @DisplayName("carries exactly seven trailing spaces - not six, not eight")
        void hasExactlySevenTrailingSpaces() {
            assertThat(trailingSpaceCount(ScreenTitles.CCDA_TITLE01)).isEqualTo(7);
            assertThat(ScreenTitles.CCDA_TITLE01).endsWith(" ".repeat(7));
            assertThat(ScreenTitles.CCDA_TITLE01).doesNotEndWith(" ".repeat(8));
        }

        @Test
        @DisplayName("is 6 leading + 27 of text + 7 trailing, and those account for all 40")
        void paddingAndTextAccountForTheDeclaredWidth() {
            assertThat(ScreenTitles.CCDA_TITLE01.strip()).isEqualTo("AWS Mainframe Modernization");
            assertThat(ScreenTitles.CCDA_TITLE01.strip()).hasSize(27);
            assertThat(6 + 27 + 7).isEqualTo(EXPECTED_TITLE_LENGTH);
        }

        @Test
        @DisplayName("has not been trimmed or stripped - the surrounding spaces are the field")
        void isNotTrimmedOrStripped() {
            assertThat(ScreenTitles.CCDA_TITLE01).isNotEqualTo(ScreenTitles.CCDA_TITLE01.trim());
            assertThat(ScreenTitles.CCDA_TITLE01).isNotEqualTo(ScreenTitles.CCDA_TITLE01.strip());
            assertThat(ScreenTitles.CCDA_TITLE01)
                    .isNotEqualTo(ScreenTitles.CCDA_TITLE01.stripLeading());
            assertThat(ScreenTitles.CCDA_TITLE01)
                    .isNotEqualTo(ScreenTitles.CCDA_TITLE01.stripTrailing());
        }

        @Test
        @DisplayName("contains only printable US-ASCII, so no invisible character can hide in it")
        void containsOnlyPrintableAscii() {
            assertThat(ScreenTitles.CCDA_TITLE01).matches(PRINTABLE_ASCII_ONLY);
        }
    }

    @Nested
    @DisplayName("CCDA_TITLE02 - '              CardDemo                  '")
    class CcdaTitle02 {
        @Test
        @DisplayName("is exactly 40 characters")
        void isExactlyFortyCharacters() {
            assertThat(ScreenTitles.CCDA_TITLE02).hasSize(EXPECTED_TITLE_LENGTH);
        }

        @Test
        @DisplayName("equals the LIVE line 22 literal character for character")
        void equalsTheLiveCopybookLiteral() {
            assertThat(ScreenTitles.CCDA_TITLE02).isEqualTo(EXPECTED_CCDA_TITLE02);
        }

        @Test
        @DisplayName("carries exactly fourteen leading spaces - not thirteen, not fifteen")
        void hasExactlyFourteenLeadingSpaces() {
            assertThat(leadingSpaceCount(ScreenTitles.CCDA_TITLE02)).isEqualTo(14);
            assertThat(ScreenTitles.CCDA_TITLE02).startsWith(" ".repeat(14));
            assertThat(ScreenTitles.CCDA_TITLE02).doesNotStartWith(" ".repeat(15));
        }

        @Test
        @DisplayName("carries exactly eighteen trailing spaces - not seventeen, not nineteen")
        void hasExactlyEighteenTrailingSpaces() {
            assertThat(trailingSpaceCount(ScreenTitles.CCDA_TITLE02)).isEqualTo(18);
            assertThat(ScreenTitles.CCDA_TITLE02).endsWith(" ".repeat(18));
            assertThat(ScreenTitles.CCDA_TITLE02).doesNotEndWith(" ".repeat(19));
        }

        @Test
        @DisplayName("is 14 leading + 8 of text + 18 trailing, and those account for all 40")
        void paddingAndTextAccountForTheDeclaredWidth() {
            assertThat(ScreenTitles.CCDA_TITLE02.strip()).isEqualTo("CardDemo");
            assertThat(ScreenTitles.CCDA_TITLE02.strip()).hasSize(8);
            assertThat(14 + 8 + 18).isEqualTo(EXPECTED_TITLE_LENGTH);
        }

        @Test
        @DisplayName("has not been trimmed or stripped - the surrounding spaces are the field")
        void isNotTrimmedOrStripped() {
            assertThat(ScreenTitles.CCDA_TITLE02).isNotEqualTo(ScreenTitles.CCDA_TITLE02.trim());
            assertThat(ScreenTitles.CCDA_TITLE02).isNotEqualTo(ScreenTitles.CCDA_TITLE02.strip());
            assertThat(ScreenTitles.CCDA_TITLE02)
                    .isNotEqualTo(ScreenTitles.CCDA_TITLE02.stripLeading());
            assertThat(ScreenTitles.CCDA_TITLE02)
                    .isNotEqualTo(ScreenTitles.CCDA_TITLE02.stripTrailing());
        }

        @Test
        @DisplayName("contains only printable US-ASCII, so no invisible character can hide in it")
        void containsOnlyPrintableAscii() {
            assertThat(ScreenTitles.CCDA_TITLE02).matches(PRINTABLE_ASCII_ONLY);
        }
    }

    @Nested
    @DisplayName("CCDA_THANK_YOU - 'Thank you for using CCDA application... '")
    class CcdaThankYou {
        @Test
        @DisplayName("is exactly 40 characters")
        void isExactlyFortyCharacters() {
            assertThat(ScreenTitles.CCDA_THANK_YOU).hasSize(EXPECTED_TITLE_LENGTH);
        }

        @Test
        @DisplayName("equals the copybook literal character for character")
        void equalsTheCopybookLiteral() {
            assertThat(ScreenTitles.CCDA_THANK_YOU).isEqualTo(EXPECTED_CCDA_THANK_YOU);
        }

        @Test
        @DisplayName("has no leading space at all - unlike the two heading lines it is left-aligned")
        void hasNoLeadingSpace() {
            assertThat(leadingSpaceCount(ScreenTitles.CCDA_THANK_YOU)).isZero();
            assertThat(ScreenTitles.CCDA_THANK_YOU).doesNotStartWith(" ");
            assertThat(ScreenTitles.CCDA_THANK_YOU).startsWith("Thank");
        }

        @Test
        @DisplayName("carries exactly one trailing space - not zero, not two")
        void hasExactlyOneTrailingSpace() {
            assertThat(trailingSpaceCount(ScreenTitles.CCDA_THANK_YOU)).isEqualTo(1);
            assertThat(ScreenTitles.CCDA_THANK_YOU).endsWith(" ");
            assertThat(ScreenTitles.CCDA_THANK_YOU).doesNotEndWith("  ");
        }

        @Test
        @DisplayName("is 0 leading + 39 of text + 1 trailing, and those account for all 40")
        void paddingAndTextAccountForTheDeclaredWidth() {
            assertThat(ScreenTitles.CCDA_THANK_YOU.strip())
                    .isEqualTo("Thank you for using CCDA application...");
            assertThat(ScreenTitles.CCDA_THANK_YOU.strip()).hasSize(39);
            assertThat(0 + 39 + 1).isEqualTo(EXPECTED_TITLE_LENGTH);
        }

        @Test
        @DisplayName("keeps all three full stops - they are text, not an elision in this source")
        void keepsItsThreeFullStops() {
            assertThat(ScreenTitles.CCDA_THANK_YOU).contains("application...");
            assertThat(ScreenTitles.CCDA_THANK_YOU.strip()).endsWith("...");
        }

        @Test
        @DisplayName("has not been trimmed or stripped - the trailing space is the fortieth byte")
        void isNotTrimmedOrStripped() {
            assertThat(ScreenTitles.CCDA_THANK_YOU).isNotEqualTo(ScreenTitles.CCDA_THANK_YOU.trim());
            assertThat(ScreenTitles.CCDA_THANK_YOU)
                    .isNotEqualTo(ScreenTitles.CCDA_THANK_YOU.strip());
            assertThat(ScreenTitles.CCDA_THANK_YOU)
                    .isNotEqualTo(ScreenTitles.CCDA_THANK_YOU.stripTrailing());
            assertThat(ScreenTitles.CCDA_THANK_YOU)
                    .isEqualTo(ScreenTitles.CCDA_THANK_YOU.stripLeading());
        }

        @Test
        @DisplayName("contains only printable US-ASCII, so no invisible character can hide in it")
        void containsOnlyPrintableAscii() {
            assertThat(ScreenTitles.CCDA_THANK_YOU).matches(PRINTABLE_ASCII_ONLY);
        }
    }

    @Nested
    @DisplayName("the commented-out line 21 alternative must never be used")
    class CommentedOutAlternative {
        @Test
        @DisplayName("CCDA_TITLE02 holds the LIVE line 22 value")
        void title02HoldsTheLiveValue() {
            assertThat(ScreenTitles.CCDA_TITLE02).isEqualTo(EXPECTED_CCDA_TITLE02);
            assertThat(ScreenTitles.CCDA_TITLE02.strip()).isEqualTo("CardDemo");
        }

        @Test
        @DisplayName("CCDA_TITLE02 is NOT the commented-out line 21 alternative")
        void title02IsNotTheCommentedOutAlternative() {
            assertThat(ScreenTitles.CCDA_TITLE02).isNotEqualTo(COMMENTED_OUT_TITLE02_DECOY);
            assertThat(ScreenTitles.CCDA_TITLE02).doesNotContain("Credit Card Demo Application");
            assertThat(ScreenTitles.CCDA_TITLE02).doesNotContain("(CCDA)");
        }

        @Test
        @DisplayName("the line 21 wording is not the value of ANY constant in the class")
        void theCommentedOutWordingIsNoConstantsValue() {
            assertThat(List.of(
                    ScreenTitles.CCDA_TITLE01,
                    ScreenTitles.CCDA_TITLE02,
                    ScreenTitles.CCDA_THANK_YOU))
                    .doesNotContain(COMMENTED_OUT_TITLE02_DECOY);
        }

        @ParameterizedTest(name = "ScreenTitles.{0} is not the commented-out wording")
        @MethodSource("com.vsergeychik.carddemo.common.ScreenTitlesTest#screenTitleConstants")
        @DisplayName("no declared constant - present or future - carries the line 21 wording")
        void noDeclaredConstantCarriesTheCommentedOutWording(String name, String value) {
            assertThat(value)
                    .as("ScreenTitles.%s must not carry the commented-out line 21 wording", name)
                    .isNotEqualTo(COMMENTED_OUT_TITLE02_DECOY);
        }

        @Test
        @DisplayName("the line 21 wording is itself exactly 40 characters, so width cannot catch it")
        void theCommentedOutWordingIsAlsoFortyCharacters() {
            assertThat(COMMENTED_OUT_TITLE02_DECOY).hasSize(EXPECTED_TITLE_LENGTH);
            assertThat(COMMENTED_OUT_TITLE02_DECOY).hasSameSizeAs(EXPECTED_CCDA_TITLE02);
        }

        @Test
        @DisplayName("the two differ in padding as well as text - 2/3 spaces against 14/18")
        void theLiveValueAndTheAlternativeHaveDifferentPadding() {
            assertThat(leadingSpaceCount(COMMENTED_OUT_TITLE02_DECOY)).isEqualTo(2);
            assertThat(trailingSpaceCount(COMMENTED_OUT_TITLE02_DECOY)).isEqualTo(3);
            assertThat(leadingSpaceCount(ScreenTitles.CCDA_TITLE02)).isEqualTo(14);
            assertThat(trailingSpaceCount(ScreenTitles.CCDA_TITLE02)).isEqualTo(18);
            assertThat(leadingSpaceCount(ScreenTitles.CCDA_TITLE02))
                    .isNotEqualTo(leadingSpaceCount(COMMENTED_OUT_TITLE02_DECOY));
            assertThat(trailingSpaceCount(ScreenTitles.CCDA_TITLE02))
                    .isNotEqualTo(trailingSpaceCount(COMMENTED_OUT_TITLE02_DECOY));
        }

        @Test
        @DisplayName("the two wordings are not blended - the live value has no trace of the other")
        void theTwoWordingsAreNotBlended() {
            assertThat(ScreenTitles.CCDA_TITLE02.strip()).isEqualTo("CardDemo");
            assertThat(ScreenTitles.CCDA_TITLE02).doesNotContain("Credit");
            assertThat(ScreenTitles.CCDA_TITLE02).doesNotContain("Application");
            assertThat(COMMENTED_OUT_TITLE02_DECOY).doesNotContain("CardDemo");
        }
    }

    @Nested
    @DisplayName("CCDA_THANK_YOU is the PIC X(40) CCDA message, not the PIC X(50) CardDemo one")
    class ThankYouIsNotTheSystemMessagesThankYou {
        @Test
        @DisplayName("is 40 characters, the COTTL01Y width - never 50, the CSMSG01Y width")
        void isFortyCharactersNotFifty() {
            assertThat(ScreenTitles.CCDA_THANK_YOU).hasSize(EXPECTED_TITLE_LENGTH);
            assertThat(ScreenTitles.CCDA_THANK_YOU.length()).isNotEqualTo(50);
        }

        @Test
        @DisplayName("names the CCDA application")
        void namesTheCcdaApplication() {
            assertThat(ScreenTitles.CCDA_THANK_YOU).contains("CCDA");
            assertThat(ScreenTitles.CCDA_THANK_YOU).isEqualTo(EXPECTED_CCDA_THANK_YOU);
        }

        @Test
        @DisplayName("does NOT name the CardDemo application - that wording belongs to CSMSG01Y")
        void doesNotNameTheCardDemoApplication() {
            assertThat(ScreenTitles.CCDA_THANK_YOU).doesNotContain("CardDemo");
        }

        @Test
        @DisplayName("is not the CSMSG01Y wording at either its source or its padded width")
        void isNotTheCsmsg01yWording() {
            assertThat(ScreenTitles.CCDA_THANK_YOU)
                    .isNotEqualTo("Thank you for using CardDemo application...      ");
            assertThat(ScreenTitles.CCDA_THANK_YOU)
                    .isNotEqualTo("Thank you for using CardDemo application...       ");
        }

        @Test
        @DisplayName("shares only the opening words with the other message, and diverges at the name")
        void divergesFromTheOtherMessageAtTheApplicationName() {
            assertThat(ScreenTitles.CCDA_THANK_YOU).startsWith("Thank you for using ");
            assertThat(ScreenTitles.CCDA_THANK_YOU.strip()).endsWith("application...");
            assertThat(ScreenTitles.CCDA_THANK_YOU.substring("Thank you for using ".length()))
                    .startsWith("CCDA");
        }

        @Test
        @DisplayName("no ScreenTitles constant carries the CardDemo thank-you wording")
        void noScreenTitlesConstantCarriesTheOtherWording() {
            assertThat(List.of(
                    ScreenTitles.CCDA_TITLE01,
                    ScreenTitles.CCDA_TITLE02,
                    ScreenTitles.CCDA_THANK_YOU))
                    .noneMatch(title -> title.contains("Thank you for using CardDemo"));
        }
    }

    @Nested
    @DisplayName("class shape - public static final constants on a non-instantiable holder")
    class ClassShapeAndImmutability {
        @ParameterizedTest(name = "ScreenTitles.{0} is public static final")
        @MethodSource("com.vsergeychik.carddemo.common.ScreenTitlesTest#screenTitleConstants")
        @DisplayName("every screen title constant is public, static and final")
        void everyScreenTitleConstantIsPublicStaticFinal(String name, String value) {
            Field field = declaredField(name);
            int modifiers = field.getModifiers();

            assertThat(Modifier.isPublic(modifiers))
                    .as("ScreenTitles.%s must be public - all 17 controllers paint from it", name)
                    .isTrue();
            assertThat(Modifier.isStatic(modifiers))
                    .as("ScreenTitles.%s must be static - it is a copybook constant, not per-instance",
                            name)
                    .isTrue();
            assertThat(Modifier.isFinal(modifiers))
                    .as("ScreenTitles.%s must be final - a repointable heading is shared mutable state",
                            name)
                    .isTrue();
            assertThat(value).isEqualTo(readStringConstant(field));
        }

        @ParameterizedTest(name = "ScreenTitles.{0} is declared as a String")
        @MethodSource("com.vsergeychik.carddemo.common.ScreenTitlesTest#screenTitleConstants")
        @DisplayName("every screen title is a String, and so immutable by construction")
        void everyScreenTitleIsAnImmutableString(String name, String value) {
            assertThat(declaredField(name).getType())
                    .as("ScreenTitles.%s must be declared as a String", name)
                    .isEqualTo(String.class);
            assertThat(value).isInstanceOf(String.class);
        }

        @Test
        @DisplayName("TITLE_LENGTH is a public static final int")
        void titleLengthIsAPublicStaticFinalInt() {
            Field field = declaredField("TITLE_LENGTH");
            int modifiers = field.getModifiers();

            assertThat(field.getType()).isEqualTo(int.class);
            assertThat(Modifier.isPublic(modifiers)).isTrue();
            assertThat(Modifier.isStatic(modifiers)).isTrue();
            assertThat(Modifier.isFinal(modifiers)).isTrue();
        }

        @Test
        @DisplayName("declares exactly the four expected constants and nothing else public")
        void declaresExactlyTheFourExpectedConstants() {
            assertThat(Arrays.stream(ScreenTitles.class.getDeclaredFields())
                    .filter(field -> !field.isSynthetic())
                    .filter(field -> Modifier.isPublic(field.getModifiers()))
                    .map(Field::getName))
                    .containsExactlyInAnyOrder(
                            "TITLE_LENGTH", "CCDA_TITLE01", "CCDA_TITLE02", "CCDA_THANK_YOU");
        }

        @Test
        @DisplayName("holds no mutable state - every non-synthetic field is static and final")
        void holdsNoMutableState() {
            assertThat(Arrays.stream(ScreenTitles.class.getDeclaredFields())
                    .filter(field -> !field.isSynthetic())
                    .toList())
                    .allSatisfy(field -> {
                        assertThat(Modifier.isStatic(field.getModifiers()))
                                .as("ScreenTitles.%s must be static", field.getName())
                                .isTrue();
                        assertThat(Modifier.isFinal(field.getModifiers()))
                                .as("ScreenTitles.%s must be final", field.getName())
                                .isTrue();
                    });
        }

        @Test
        @DisplayName("the class is final, so the constants cannot be shadowed by a subclass")
        void theClassIsFinal() {
            assertThat(Modifier.isFinal(ScreenTitles.class.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("declares exactly one constructor, and it is private")
        void declaresOnePrivateConstructor() {
            Constructor<?>[] constructors = ScreenTitles.class.getDeclaredConstructors();

            assertThat(constructors).hasSize(1);
            assertThat(Modifier.isPrivate(constructors[0].getModifiers()))
                    .as("the sole constructor of a constants holder must be private")
                    .isTrue();
            assertThat(constructors[0].getParameterCount()).isZero();
        }

        @Test
        @DisplayName("refuses reflective instantiation, so it can never be misused as an object")
        void refusesReflectiveInstantiation() throws NoSuchMethodException {
            Constructor<?> constructor = ScreenTitles.class.getDeclaredConstructor();
            constructor.setAccessible(true);

            assertThatExceptionOfType(InvocationTargetException.class)
                    .isThrownBy(constructor::newInstance)
                    .withCauseInstanceOf(AssertionError.class);
        }

        @Test
        @DisplayName("has no methods of its own, so there is no behaviour to diverge from the copybook")
        void exposesNoMethods() {
            assertThat(Arrays.stream(ScreenTitles.class.getDeclaredMethods())
                    .filter(method -> !method.isSynthetic())
                    .toList())
                    .isEmpty();
        }
    }
}
