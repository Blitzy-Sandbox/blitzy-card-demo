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
 *
 * <h2>Why a constants class earns a test of this depth</h2>
 *
 * {@code COPY COTTL01Y.} appears in all 17 CICS online programs, and every one of them paints its
 * two heading lines straight out of this group. A single space lost or gained in any of these three
 * literals therefore propagates into every screen response and fails every online parity
 * comparison at once - and it fails it in the least visible way possible, because the rendered text
 * still reads correctly to a human. There is no arithmetic here to get wrong and no branch to
 * exercise: the whole risk surface of this class is transcription accuracy, so that is what this
 * test measures, exhaustively and from more than one direction.
 *
 * <h2>How these expectations were derived</h2>
 *
 * The COBOL cannot be executed in this environment, so no value here was captured from a running
 * program. Each expectation was measured character-by-character from the copybook and is
 * transcribed into this source with a {@code // source: app/cpy/COTTL01Y.cpy:Lnn} citation naming
 * the exact line it came from. The copybook is a read-only parity oracle: this test never reads it
 * at run time, never opens a file at all, and never writes anywhere under {@code app/}. The literals
 * below are the whole input.
 *
 * <h2>The one failure mode a reviewer would not catch</h2>
 *
 * The copybook declares {@code CCDA-TITLE02} on line 20 and gives it a value on line 22. Line 21
 * sits between the two and reads:
 *
 * <pre>
 *       *     '  Credit Card Demo Application (CCDA)   '.
 * </pre>
 *
 * Column 7 of that line holds an asterisk, which makes it a COBOL comment - an abandoned earlier
 * wording, not a value. It measures <strong>exactly 40 characters, the same as the live value</strong>,
 * so a width check cannot tell the two apart; only the comment marker can. Picking it would compile,
 * pass every length assertion, look entirely plausible in review, and silently corrupt the heading
 * line of all 17 screens. {@link CommentedOutAlternative} therefore asserts the live line 22 value
 * positively <em>and</em> asserts the line 21 wording negatively. Those negative assertions are the
 * most important lines in this file.
 *
 * <h2>Scope</h2>
 *
 * A plain JUnit 5 unit test: no Spring context, no mocks, no fixtures, no I/O. It exercises exactly
 * one class and deliberately does not reference {@code SystemMessages}, whose own thank-you message
 * is a different field of a different width from a different copybook - see
 * {@link ThankYouIsNotTheSystemMessagesThankYou}.
 */
@DisplayName("ScreenTitles - the byte-exact COTTL01Y screen title literals")
class ScreenTitlesTest {

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // Expectations, transcribed from the copybook and held independently of the class under test.
    //
    // These are deliberately NOT derived from ScreenTitles: a test that computed its expectation
    // from the value it is checking would assert nothing at all. Every literal below was measured
    // character-by-character from app/cpy/COTTL01Y.cpy and is exactly 40 characters long, leading
    // and trailing spaces included. The padding is part of the field, not decoration around it.
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    /** The width every item in {@code 01 CCDA-SCREEN-TITLE} declares: {@code PIC X(40)}. */
    private static final int EXPECTED_TITLE_LENGTH = 40; // source: app/cpy/COTTL01Y.cpy:L18,L20,L23

    /** 6 leading spaces + 27 characters of text + 7 trailing spaces = 40. */
    // source: app/cpy/COTTL01Y.cpy:L18-L19  (05 CCDA-TITLE01 PIC X(40) VALUE ...)
    private static final String EXPECTED_CCDA_TITLE01 =
            "      AWS Mainframe Modernization       ";

    /** 14 leading spaces + 8 characters of text + 18 trailing spaces = 40. */
    // source: app/cpy/COTTL01Y.cpy:L20 (declaration) with its live value on L22 - NOT L21
    private static final String EXPECTED_CCDA_TITLE02 =
            "              CardDemo                  ";

    /** 39 characters of text + 1 trailing space = 40. */
    // source: app/cpy/COTTL01Y.cpy:L23-L24  (05 CCDA-THANK-YOU PIC X(40) VALUE ...)
    private static final String EXPECTED_CCDA_THANK_YOU =
            "Thank you for using CCDA application... ";

    /**
     * The abandoned wording for {@code CCDA-TITLE02} that the copybook carries as a COMMENT.
     *
     * <p>This string is <strong>never</strong> a correct value for anything. It is declared here for
     * one purpose only: so that the tests below can assert it is absent. It is 2 leading spaces + 35
     * characters of text + 3 trailing spaces = 40 - the same width as the live value, which is
     * precisely why a length check cannot catch a substitution.</p>
     */
    // source: app/cpy/COTTL01Y.cpy:L21 - COMMENT LINE, asterisk in column 7, deliberately unused
    private static final String COMMENTED_OUT_TITLE02_DECOY =
            "  Credit Card Demo Application (CCDA)   ";

    /**
     * Matches a string built entirely from printable US-ASCII ({@code 0x20}-{@code 0x7E}).
     *
     * <p>Two reasons this matters. First, a stray tab or non-breaking space would be invisible in a
     * diff yet would change the field bytes - the copybook {@code CUSTREC.cpy} in this very
     * repository contains literal tabs, so the hazard is real here rather than theoretical. Second,
     * it establishes that the only whitespace character present is the ASCII space, which is what
     * makes {@link #leadingSpaceCount(String)} and {@link #trailingSpaceCount(String)} - built on
     * {@code stripLeading}/{@code stripTrailing}, which strip any Unicode whitespace - exact space
     * counters for these particular values.</p>
     */
    private static final String PRINTABLE_ASCII_ONLY = "[\\x20-\\x7E]*";

    /** The {@code String} constants {@link ScreenTitles} declares today, in copybook order. */
    private static final List<String> EXPECTED_CONSTANT_NAMES =
            List.of("CCDA_TITLE01", "CCDA_TITLE02", "CCDA_THANK_YOU");

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // Reflection helpers and the parameterised-test source.
    //
    // Every member here is either `static final` data or a stateless `static` function. There is no
    // static mutable state anywhere in this file: JUnit requires a @MethodSource factory to be
    // static, and a pure factory that returns a fresh Stream on each call holds no state.
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Every public {@code String} constant {@link ScreenTitles} declares, discovered by reflection
     * rather than listed by hand.
     *
     * <p>Discovery is the point. The group invariants driven from this source apply to whatever the
     * class actually declares, so a fourth title added later is picked up automatically and cannot
     * slip in at the wrong width or carrying the commented-out wording.
     * {@code screenTitleConstantsAreExactlyTheThreeCopybookItems()} pins the current membership so
     * this source can never pass vacuously.</p>
     *
     * <p>The filters are deliberately belt-and-braces. Restricting to non-synthetic fields keeps a
     * coverage-instrumentation probe field out of the results; restricting to {@code public} fields
     * does so again independently, since such probes are private. Both filters are cheap, and
     * together they mean these assertions behave identically under a plain test run and under the
     * instrumented {@code verify} run that gates the build.</p>
     *
     * @return one {@link Arguments} pair of (field name, field value) per declared constant
     */
    static Stream<Arguments> screenTitleConstants() {
        return Arrays.stream(ScreenTitles.class.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .filter(field -> field.getType() == String.class)
                .filter(field -> Modifier.isPublic(field.getModifiers()))
                .filter(field -> Modifier.isStatic(field.getModifiers()))
                .map(field -> Arguments.of(field.getName(), readStringConstant(field)));
    }

    /**
     * Reads a {@code public static final String} constant off {@link ScreenTitles}.
     *
     * @param field the field to read; must be a public static {@code String} field
     * @return the constant's value
     * @throws AssertionError if the field is not readable, which would itself be a defect in the
     *         class under test rather than in this test
     */
    private static String readStringConstant(Field field) {
        try {
            return (String) field.get(null);
        } catch (IllegalAccessException cannotRead) {
            throw new AssertionError(
                    "ScreenTitles." + field.getName() + " must be readable as a public constant",
                    cannotRead);
        }
    }

    /**
     * Looks up a declared field of {@link ScreenTitles} by name.
     *
     * @param name the exact field name
     * @return the reflected field
     * @throws AssertionError if the class does not declare it, which means the constant this test
     *         binds to has been renamed or removed
     */
    private static Field declaredField(String name) {
        try {
            return ScreenTitles.class.getDeclaredField(name);
        } catch (NoSuchFieldException missing) {
            throw new AssertionError("ScreenTitles must declare the constant " + name, missing);
        }
    }

    /**
     * Counts the leading spaces of a value.
     *
     * @param value the value to measure
     * @return the number of leading space characters
     */
    private static int leadingSpaceCount(String value) {
        return value.length() - value.stripLeading().length();
    }

    /**
     * Counts the trailing spaces of a value.
     *
     * @param value the value to measure
     * @return the number of trailing space characters
     */
    private static int trailingSpaceCount(String value) {
        return value.length() - value.stripTrailing().length();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // Group invariants - driven by reflection over whatever the class declares.
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the reflected source finds exactly the three PIC X(40) items of 01 CCDA-SCREEN-TITLE")
    void screenTitleConstantsAreExactlyTheThreeCopybookItems() {
        // Guards the parameterised tests below against passing vacuously, and pins today's
        // membership: the copybook declares three 05-level items under 01 CCDA-SCREEN-TITLE, so
        // three is the correct count and a fourth would have to be justified against the copybook.
        assertThat(screenTitleConstants().map(argument -> argument.get()[0]))
                .containsExactlyInAnyOrderElementsOf(EXPECTED_CONSTANT_NAMES);
    }

    @ParameterizedTest(name = "ScreenTitles.{0} is TITLE_LENGTH characters")
    @MethodSource("screenTitleConstants")
    @DisplayName("every screen title is exactly TITLE_LENGTH characters")
    void everyScreenTitleIsExactlyTitleLengthCharacters(String name, String value) {
        // The invariant of the whole group: PIC X(40) at the source of every MOVE and PIC X(40) at
        // its destination (TITLE01I / TITLE02I in all 17 symbolic maps). Asserted against the
        // class's own TITLE_LENGTH so the two can never drift apart, and separately against the
        // independently transcribed 40 so TITLE_LENGTH itself cannot be quietly changed.
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
        // Every one of the three literals carries at least a trailing space, so for all of them the
        // stripped form is strictly shorter than the stored form. If any constant were ever
        // "tidied" with trim(), strip() or a text block, this is where it would show up.
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

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // The declared width.
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("TITLE_LENGTH - the PIC X(40) width")
    class DeclaredWidth {

        @Test
        @DisplayName("is 40, the width every 05-level item of 01 CCDA-SCREEN-TITLE declares")
        void titleLengthIsForty() {
            // source: app/cpy/COTTL01Y.cpy:L18, L20, L23 - all three items are PIC X(40).
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

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // CCDA-TITLE01 - the upper heading line of every online screen.
    //
    // Length and value are asserted in SEPARATE tests on purpose. A single combined assertion that
    // failed would only say "not equal", leaving the reader to work out whether the text is wrong or
    // the padding is. Split, the failure names the broken property directly.
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("CCDA_TITLE01 - '      AWS Mainframe Modernization       '")
    class CcdaTitle01 {

        @Test
        @DisplayName("is exactly 40 characters")
        void isExactlyFortyCharacters() {
            // source: app/cpy/COTTL01Y.cpy:L18 - 05 CCDA-TITLE01 PIC X(40)
            assertThat(ScreenTitles.CCDA_TITLE01).hasSize(EXPECTED_TITLE_LENGTH);
        }

        @Test
        @DisplayName("equals the copybook literal character for character")
        void equalsTheCopybookLiteral() {
            // source: app/cpy/COTTL01Y.cpy:L19 - the VALUE clause, transcribed verbatim
            assertThat(ScreenTitles.CCDA_TITLE01).isEqualTo(EXPECTED_CCDA_TITLE01);
        }

        @Test
        @DisplayName("carries exactly six leading spaces - not five, not seven")
        void hasExactlySixLeadingSpaces() {
            // Pinned two independent ways: by counting, and by a prefix test that also rules out one
            // space too many. Either alone could be satisfied by a value that is still wrong.
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
            // The cross-check the transcription itself was verified against: if any of the three
            // numbers is wrong the sum stops being 40, so no single count can drift unnoticed.
            assertThat(ScreenTitles.CCDA_TITLE01.strip()).isEqualTo("AWS Mainframe Modernization");
            assertThat(ScreenTitles.CCDA_TITLE01.strip()).hasSize(27);
            assertThat(6 + 27 + 7).isEqualTo(EXPECTED_TITLE_LENGTH);
        }

        @Test
        @DisplayName("has not been trimmed or stripped - the surrounding spaces are the field")
        void isNotTrimmedOrStripped() {
            // This constant has space on BOTH sides, so trim() and strip() both shorten it. A value
            // that survived either call unchanged would mean the padding had already been lost.
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

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // CCDA-TITLE02 - the lower heading line of every online screen. THE HIGH-RISK CONSTANT: its
    // value sits on line 22, one line below a commented-out alternative of identical width. See
    // CommentedOutAlternative below for the negative assertions that guard the substitution.
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("CCDA_TITLE02 - '              CardDemo                  '")
    class CcdaTitle02 {

        @Test
        @DisplayName("is exactly 40 characters")
        void isExactlyFortyCharacters() {
            // source: app/cpy/COTTL01Y.cpy:L20 - 05 CCDA-TITLE02 PIC X(40)
            assertThat(ScreenTitles.CCDA_TITLE02).hasSize(EXPECTED_TITLE_LENGTH);
        }

        @Test
        @DisplayName("equals the LIVE line 22 literal character for character")
        void equalsTheLiveCopybookLiteral() {
            // source: app/cpy/COTTL01Y.cpy:L22 - the live VALUE. Line 21 is a comment; see
            // CommentedOutAlternative.
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

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // CCDA-THANK-YOU - the sign-off line. The only one of the three with no leading space.
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("CCDA_THANK_YOU - 'Thank you for using CCDA application... '")
    class CcdaThankYou {

        @Test
        @DisplayName("is exactly 40 characters")
        void isExactlyFortyCharacters() {
            // source: app/cpy/COTTL01Y.cpy:L23 - 05 CCDA-THANK-YOU PIC X(40)
            assertThat(ScreenTitles.CCDA_THANK_YOU).hasSize(EXPECTED_TITLE_LENGTH);
        }

        @Test
        @DisplayName("equals the copybook literal character for character")
        void equalsTheCopybookLiteral() {
            // source: app/cpy/COTTL01Y.cpy:L24 - the VALUE clause, transcribed verbatim
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
            // The single trailing space is the easiest character in the whole class to lose, and the
            // hardest to notice: without it the value is 39 characters and no longer PIC X(40).
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
            // Guards a transcription that "tidies" the ellipsis into a single character or drops it.
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
            // stripLeading() is a no-op here: there is nothing to strip on the left, which is itself
            // part of this constant's shape and is asserted rather than left implicit.
            assertThat(ScreenTitles.CCDA_THANK_YOU)
                    .isEqualTo(ScreenTitles.CCDA_THANK_YOU.stripLeading());
        }

        @Test
        @DisplayName("contains only printable US-ASCII, so no invisible character can hide in it")
        void containsOnlyPrintableAscii() {
            assertThat(ScreenTitles.CCDA_THANK_YOU).matches(PRINTABLE_ASCII_ONLY);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // THE COMMENTED-OUT ALTERNATIVE - the one substitution review would not catch.
    //
    // app/cpy/COTTL01Y.cpy lines 20 to 22 read:
    //
    //          05 CCDA-TITLE02    PIC X(40) VALUE          <- L20, the declaration
    //   *     '  Credit Card Demo Application (CCDA)   '.  <- L21, a COMMENT: '*' in column 7
    //         '              CardDemo                  '.  <- L22, the LIVE value
    //
    // The comment on line 21 is an abandoned earlier wording sitting directly between the
    // declaration and its real value, which is the worst possible place for it. It is exactly 40
    // characters long, so it satisfies the PIC X(40) width perfectly and EVERY length assertion in
    // this file would pass if it were used by mistake. Nothing about its content gives it away
    // either: it names the same application and reads perfectly well on a screen. The ONLY thing
    // that marks it inactive is the asterisk in column 7 of the source line - a single character,
    // in a file this migration never reads at run time.
    //
    // Choosing it would compile, measure 40, pass review, and corrupt the lower heading line of all
    // 17 online screens - failing every online parity comparison for a reason no stack trace would
    // point at. The negative assertions below are therefore the most valuable in this file, and they
    // exist precisely because no positive length or format check can do their job.
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("the commented-out line 21 alternative must never be used")
    class CommentedOutAlternative {

        @Test
        @DisplayName("CCDA_TITLE02 holds the LIVE line 22 value")
        void title02HoldsTheLiveValue() {
            // source: app/cpy/COTTL01Y.cpy:L22
            assertThat(ScreenTitles.CCDA_TITLE02).isEqualTo(EXPECTED_CCDA_TITLE02);
            assertThat(ScreenTitles.CCDA_TITLE02.strip()).isEqualTo("CardDemo");
        }

        @Test
        @DisplayName("CCDA_TITLE02 is NOT the commented-out line 21 alternative")
        void title02IsNotTheCommentedOutAlternative() {
            // THE assertion this whole nested class exists for.
            // source: app/cpy/COTTL01Y.cpy:L21 - a comment, never a value.
            assertThat(ScreenTitles.CCDA_TITLE02).isNotEqualTo(COMMENTED_OUT_TITLE02_DECOY);
            assertThat(ScreenTitles.CCDA_TITLE02).doesNotContain("Credit Card Demo Application");
            assertThat(ScreenTitles.CCDA_TITLE02).doesNotContain("(CCDA)");
        }

        @Test
        @DisplayName("the line 21 wording is not the value of ANY constant in the class")
        void theCommentedOutWordingIsNoConstantsValue() {
            // Widened past CCDA_TITLE02 on purpose: the abandoned wording must not surface anywhere
            // in the class, not merely in the field it was originally written for.
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
            // Reflection-driven, so this guard automatically extends to any constant added later.
            assertThat(value)
                    .as("ScreenTitles.%s must not carry the commented-out line 21 wording", name)
                    .isNotEqualTo(COMMENTED_OUT_TITLE02_DECOY);
        }

        @Test
        @DisplayName("the line 21 wording is itself exactly 40 characters, so width cannot catch it")
        void theCommentedOutWordingIsAlsoFortyCharacters() {
            // This is the crux, stated as an executable fact rather than a comment: the decoy has
            // the SAME width as the live value, so every length, padding-sum and PIC X(40) check in
            // this file is blind to the substitution. That is why the isNotEqualTo assertions above
            // are not redundant with the width assertions elsewhere - they are the only thing
            // standing between a plausible-looking mistake and 17 corrupted screens.
            assertThat(COMMENTED_OUT_TITLE02_DECOY).hasSize(EXPECTED_TITLE_LENGTH);
            assertThat(COMMENTED_OUT_TITLE02_DECOY).hasSameSizeAs(EXPECTED_CCDA_TITLE02);
        }

        @Test
        @DisplayName("the two differ in padding as well as text - 2/3 spaces against 14/18")
        void theLiveValueAndTheAlternativeHaveDifferentPadding() {
            // A second, independent discriminator. Even if the visible text were somehow matched,
            // the padding profiles differ, so the live value is identifiable by shape alone.
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
            // Guards the "helpful merge": someone reconciling the comment with the live value and
            // producing something that is neither. The live value is exactly the word CardDemo
            // between its padding, and nothing else.
            assertThat(ScreenTitles.CCDA_TITLE02.strip()).isEqualTo("CardDemo");
            assertThat(ScreenTitles.CCDA_TITLE02).doesNotContain("Credit");
            assertThat(ScreenTitles.CCDA_TITLE02).doesNotContain("Application");
            assertThat(COMMENTED_OUT_TITLE02_DECOY).doesNotContain("CardDemo");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // TWO DIFFERENT "THANK YOU" MESSAGES - never substitute one for the other.
    //
    // The codebase contains two thank-you strings. They look alike enough to be swapped by anyone
    // reading quickly, and they are not interchangeable in any respect:
    //
    //   CCDA-THANK-YOU        app/cpy/COTTL01Y.cpy:L23-24   PIC X(40)
    //                         'Thank you for using CCDA application... '
    //                         -> this class, ScreenTitles.CCDA_THANK_YOU
    //                         The source literal is ALREADY 40 characters, so PIC X(40) adds no
    //                         padding: what the copybook quotes is what the field holds.
    //
    //   CCDA-MSG-THANK-YOU    app/cpy/CSMSG01Y.cpy:L18-19   PIC X(50)
    //                         'Thank you for using CardDemo application...      '
    //                         -> the sibling class SystemMessages, NOT this one
    //                         Its source literal is 49 characters, so PIC X(50) pads it with one
    //                         further space to reach the declared 50.
    //
    // Three independent differences, then: the application NAME in the text (CCDA against
    // CardDemo), the declared WIDTH (40 against 50), and the owning COPYBOOK (COTTL01Y against
    // CSMSG01Y) - plus a fourth, whether the source literal already fills its field.
    //
    // Substituting either for the other yields output that reads perfectly to a human and fails
    // field-for-field comparison on both length and content. The assertions below pin the identity
    // of THIS constant so a future reader cannot quietly reach for the other one.
    //
    // This group deliberately asserts nothing ABOUT SystemMessages and does not import or name it in
    // code. Coupling a ScreenTitles unit test to a second class would mean a change to that class
    // could fail this one, which is exactly the confusion this boundary exists to prevent. The
    // distinction is documented here and enforced from this side only; the other side belongs to
    // SystemMessages' own test.
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("CCDA_THANK_YOU is the PIC X(40) CCDA message, not the PIC X(50) CardDemo one")
    class ThankYouIsNotTheSystemMessagesThankYou {

        @Test
        @DisplayName("is 40 characters, the COTTL01Y width - never 50, the CSMSG01Y width")
        void isFortyCharactersNotFifty() {
            // source: app/cpy/COTTL01Y.cpy:L23 - PIC X(40).
            // The other message is PIC X(50) in app/cpy/CSMSG01Y.cpy:L18; 50 is asserted here as a
            // literal rather than by referencing SystemMessages, keeping this a single-class test.
            assertThat(ScreenTitles.CCDA_THANK_YOU).hasSize(EXPECTED_TITLE_LENGTH);
            assertThat(ScreenTitles.CCDA_THANK_YOU.length()).isNotEqualTo(50);
        }

        @Test
        @DisplayName("names the CCDA application")
        void namesTheCcdaApplication() {
            // source: app/cpy/COTTL01Y.cpy:L24
            assertThat(ScreenTitles.CCDA_THANK_YOU).contains("CCDA");
            assertThat(ScreenTitles.CCDA_THANK_YOU).isEqualTo(EXPECTED_CCDA_THANK_YOU);
        }

        @Test
        @DisplayName("does NOT name the CardDemo application - that wording belongs to CSMSG01Y")
        void doesNotNameTheCardDemoApplication() {
            // The single most useful discriminator between the two messages, and the one that
            // catches a copy-paste from the wrong copybook even when the width happens to match.
            assertThat(ScreenTitles.CCDA_THANK_YOU).doesNotContain("CardDemo");
        }

        @Test
        @DisplayName("is not the CSMSG01Y wording at either its source or its padded width")
        void isNotTheCsmsg01yWording() {
            // Both forms of the other message are ruled out: the 49-character source literal as the
            // copybook quotes it, and the 50-character value PIC X(50) actually stores. Neither is
            // read from a file - both are written out here so the comparison is explicit.
            assertThat(ScreenTitles.CCDA_THANK_YOU)
                    .isNotEqualTo("Thank you for using CardDemo application...      ");
            assertThat(ScreenTitles.CCDA_THANK_YOU)
                    .isNotEqualTo("Thank you for using CardDemo application...       ");
        }

        @Test
        @DisplayName("shares only the opening words with the other message, and diverges at the name")
        void divergesFromTheOtherMessageAtTheApplicationName() {
            // Both messages open identically, which is precisely why they get confused. Asserting
            // the shared prefix AND the divergence documents the confusion instead of hiding it.
            assertThat(ScreenTitles.CCDA_THANK_YOU).startsWith("Thank you for using ");
            assertThat(ScreenTitles.CCDA_THANK_YOU.strip()).endsWith("application...");
            assertThat(ScreenTitles.CCDA_THANK_YOU.substring("Thank you for using ".length()))
                    .startsWith("CCDA");
        }

        @Test
        @DisplayName("no ScreenTitles constant carries the CardDemo thank-you wording")
        void noScreenTitlesConstantCarriesTheOtherWording() {
            // CCDA_TITLE02 legitimately contains the word CardDemo - it IS the CardDemo heading -
            // so the discriminator here is the full other MESSAGE, not the bare word. Checking all
            // three constants keeps the guard honest without producing a false positive on TITLE02.
            assertThat(List.of(
                    ScreenTitles.CCDA_TITLE01,
                    ScreenTitles.CCDA_TITLE02,
                    ScreenTitles.CCDA_THANK_YOU))
                    .noneMatch(title -> title.contains("Thank you for using CardDemo"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // CLASS SHAPE AND IMMUTABILITY.
    //
    // Everything above asserts the VALUES. This group asserts the GUARANTEES that keep those values
    // trustworthy for the lifetime of the process. Three screen headings shared by 17 controllers are
    // the definition of shared state: if one request could alter what another request paints, the
    // resulting parity failures would be intermittent and effectively undebuggable.
    //
    // Immutability here is structural rather than defensive. Each constant is a String - immutable by
    // construction, so no copy and no unmodifiable wrapper is needed - held in a `static final` field,
    // so the reference cannot be repointed either. The assertions below check that shape reflectively,
    // so a later refactor that dropped `final`, widened a constant into a mutable holder, or changed a
    // declared type fails HERE rather than surfacing as a corrupted screen much later.
    //
    // This group asserts only what the class already guarantees today. It does not push a design onto
    // it: the class is final with a single private throwing constructor, and that is what is checked.
    // A coverage-instrumentation probe field would be private, and is excluded from the field sweep by
    // the same public/non-synthetic filters the @MethodSource factory uses, so these assertions behave
    // identically under a plain test run and under the instrumented run that gates the build.
    // ─────────────────────────────────────────────────────────────────────────────────────────────

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
            // The value handed to this test came from that same field, so a mismatch would mean the
            // reflective sweep and the constant had drifted apart.
            assertThat(value).isEqualTo(readStringConstant(field));
        }

        @ParameterizedTest(name = "ScreenTitles.{0} is declared as a String")
        @MethodSource("com.vsergeychik.carddemo.common.ScreenTitlesTest#screenTitleConstants")
        @DisplayName("every screen title is a String, and so immutable by construction")
        void everyScreenTitleIsAnImmutableString(String name, String value) {
            // Immutability is asserted by TYPE, not by attempting a mutation: String offers no
            // mutator, so `static final String` is already the strongest guarantee available and
            // needs no defensive copy. A char[], StringBuilder or array constant would not be, which
            // is exactly what this assertion is watching for.
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
            // Pins the public surface. Anything new appearing here has to be justified against the
            // copybook, which declares one group item and three PIC X(40) elementary items.
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
            // The G53 assertion, applied to the class under test rather than merely to this test:
            // no instance field, and no reassignable static field, can exist on it.
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

            // Asserting the guarantee the class actually makes: the private constructor throws
            // rather than merely being inaccessible, so even reflection cannot produce an instance.
            // Reflection reports the thrown AssertionError wrapped in InvocationTargetException.
            assertThatExceptionOfType(InvocationTargetException.class)
                    .isThrownBy(constructor::newInstance)
                    .withCauseInstanceOf(AssertionError.class);
        }

        @Test
        @DisplayName("has no methods of its own, so there is no behaviour to diverge from the copybook")
        void exposesNoMethods() {
            // A copybook is data. Any method appearing on its Java form would be logic that the
            // copybook does not contain, and would belong in a service instead.
            assertThat(Arrays.stream(ScreenTitles.class.getDeclaredMethods())
                    .filter(method -> !method.isSynthetic())
                    .toList())
                    .isEmpty();
        }
    }
}
