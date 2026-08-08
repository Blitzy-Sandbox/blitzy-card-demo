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
 *
 * <h2>Provenance, stated plainly (risk R-D)</h2>
 * <strong>Neither copybook exists in this repository.</strong> {@code ls app/cpy/DFH*} fails
 * outright; {@code app/cpy} holds the twenty-eight application copybooks only, and
 * {@code DFHBMSCA}, {@code DFHATTR} and {@code DFHAID} ship with IBM CICS itself. Consequently
 * <strong>every byte value asserted in this file was transcribed from the IBM CICS documentation
 * the class under test cites in its own Javadoc, and from no file in this checkout.</strong> This
 * suite therefore does <em>not</em> - and must never be read as if it did - verify any value
 * against a repository oracle. The gap is recorded as <strong>risk R-D</strong> in the migration
 * plan, and the value table in {@code ValueProvenance} below is the auditable record of exactly
 * what was taken from IBM.
 *
 * <p>What the repository <em>can</em> verify is which mnemonics the COBOL references, and how
 * often. Those counts were re-derived here directly from the source, on comment-stripped text
 * (a COBOL line is a comment when column 7 holds {@code *} or {@code /}), and they are quoted
 * beside the assertions they justify so that the reason each constant exists stays legible:
 *
 * <pre>
 *   DFHRED   30  (+1 in app/cpy/CSSETATY.cpy)   DFHBMPRO   6      DFHBMASB   4
 *   DFHBMFSE 23                                 DFHBMDAR   6      DFHBMBRY   2
 *   DFHBMPRF 11                                 DFHNEUTR   5      DFHUNIMD   0
 *   DFHGREEN  8                                 DFHDFCOL   6
 * </pre>
 *
 * <p>Two counting notes, so the figures are reproducible rather than merely quoted.
 * {@code DFHRED} occurs 31 times in {@code app/cbl} before comments are stripped: the extra site is
 * a commented-out {@code MOVE DFHRED TO ACSTTUSC OF CACTUPAO} at {@code app/cbl/COACTUPC.cbl:L3201},
 * whose live replacement is the {@code CSSETATY} copy immediately below it at {@code L3208}. And
 * {@code COPY DFHBMSCA.} is live in all seventeen online programs, whereas {@code DFHATTR} is named
 * by exactly two - {@code app/cbl/COSGN00C.cbl:L59} and {@code app/cbl/COUSR01C.cbl:L57} - where the
 * statement reads {@code *COPY DFHATTR.} and is therefore <em>commented out</em> in both. Those two
 * are references, not compile-time includes. The class merges both copybooks as the migration plan
 * directs; this note keeps the distinction visible instead of overstating it.
 *
 * <h2>What this suite actually proves</h2>
 * With no value oracle available, three families of assertion carry real weight:
 * <ol>
 *   <li><strong>Completeness against measured usage.</strong> Every mnemonic the COBOL references
 *       is defined - including {@code DFHGREEN}, which the migration plan's illustrative list omits
 *       while eight programs use it, and {@code DFHUNIMD}, which the plan names explicitly while no
 *       program uses it at all. Both must be present, for opposite reasons.</li>
 *   <li><strong>Distinctness within each attribute plane.</strong> A colour colliding with another
 *       colour, or one field attribute with another, would silently paint the wrong thing and
 *       nothing else in the build would catch it.</li>
 *   <li><strong>Encoding.</strong> The copybook writes {@code DFHRED} as the character {@code '2'},
 *       which is the right byte only in EBCDIC. A Java {@code char} literal would have produced
 *       {@code 0x32} instead of {@code 0xF2} and quietly sent the wrong byte to the terminal, so the
 *       tests below pin the EBCDIC value <em>and</em> assert it differs from the Java literal.</li>
 * </ol>
 *
 * <h2>Why global uniqueness is deliberately NOT asserted</h2>
 * The 3270 data stream carries these bytes in three independent planes - basic field attribute,
 * extended colour and extended highlighting - and the planes deliberately share values, because a
 * terminal interprets a byte according to the plane it arrived in. {@code DFHBMASF},
 * {@code DFHBLUE} and {@code DFHBLINK} are all {@code 0xF1}, and that is correct. Asserting that all
 * thirty constants are pairwise distinct would assert something false about the hardware, so this
 * suite asserts uniqueness <em>within</em> each plane, asserts that the mnemonics the COBOL actually
 * moves into a symbolic map cannot be confused across planes, and pins the architectural collisions
 * explicitly so that a later attempt to "fix" them fails loudly.
 */
@DisplayName("BmsAttributes - DFHBMSCA and DFHATTR screen attribute constants")
class BmsAttributesTest {

    /**
     * The eighteen Section 1 basic field attribute mnemonics, in the order the class declares them.
     * The COBOL moves these into a symbolic map's attribute item {@code xxxA}, the {@code REDEFINES}
     * of {@code xxxF}, on the <em>input</em> map. That <em>placement</em> - not the byte value, which
     * the repository cannot supply - was read directly from
     * {@code app/cbl/COCRDLIC.cbl:L761} ({@code MOVE DFHBMFSE TO CRDSEL1A OF CCRDLIAI}) and
     * {@code app/cbl/COACTUPC.cbl:L3568} ({@code MOVE DFHBMDAR TO INFOMSGA OF CACTUPAI}).
     */
    private static final List<String> FIELD_ATTRIBUTE_NAMES = List.of(
            "DFHBMUNP", "DFHBMFSE", "DFHBMBRY", "DFHUNIMD", "DFHBMDAR", "DFHUNNOD",
            "DFHBMUNN", "DFHUNNUM", "DFHUNNUB", "DFHUNINT", "DFHUNNON", "DFHBMPRO",
            "DFHBMPRF", "DFHPROTI", "DFHPROTN", "DFHBMASK", "DFHBMASF", "DFHBMASB");

    /**
     * The eight Section 2 extended colour mnemonics. The COBOL moves these into a colour item
     * {@code xxxC} on the <em>output</em> map - again a <em>placement</em> read directly from
     * {@code app/cbl/COCRDLIC.cbl:L756} ({@code MOVE DFHRED TO CRDSEL1C OF CCRDLIAO}) and
     * {@code app/cpy/CSSETATY.cpy:L21-L22}, never a byte value.
     */
    private static final List<String> COLOUR_NAMES = List.of(
            "DFHDFCOL", "DFHBLUE", "DFHRED", "DFHPINK", "DFHGREEN", "DFHTURQ", "DFHYELLO",
            "DFHNEUTR");

    /** The four Section 3 extended highlighting mnemonics. No program in this codebase sets them. */
    private static final List<String> HIGHLIGHT_NAMES = List.of(
            "DFHDFHI", "DFHBLINK", "DFHREVRS", "DFHUNDLN");

    /**
     * The ten mnemonics the COBOL actually consumes, split by plane. These are the only constants
     * whose confusion across planes could change what a screen renders, which is why the cross-plane
     * distinctness assertion is scoped to exactly this set.
     */
    private static final List<String> CONSUMED_COLOUR_NAMES =
            List.of("DFHRED", "DFHGREEN", "DFHNEUTR", "DFHDFCOL");

    /** The consumed half of Section 1: six mnemonics, 52 occurrences between them. */
    private static final List<String> CONSUMED_FIELD_ATTRIBUTE_NAMES =
            List.of("DFHBMFSE", "DFHBMPRF", "DFHBMPRO", "DFHBMDAR", "DFHBMASB", "DFHBMBRY");

    /**
     * Reflects over {@link BmsAttributes} and returns every {@code public static final byte} it
     * declares, keyed by name, in declaration order.
     *
     * <p>Reflection rather than a hand-written list, so that a constant added to or removed from the
     * class is detected instead of silently ignored. The private bit masks are excluded by the
     * {@code public} filter, and the three mnemonic maps by the {@code byte} filter.
     *
     * @return an insertion-ordered map from mnemonic to attribute byte
     */
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

    /**
     * Resolves one mnemonic to its declared byte, failing with a legible message when the class does
     * not declare it at all.
     *
     * @param mnemonic the copybook mnemonic, for example {@code DFHRED}
     * @return the byte the class declares under that name
     */
    private static byte attributeByte(String mnemonic) {
        Map<String, Byte> declared = declaredAttributeBytes();
        assertThat(declared)
                .as("BmsAttributes must declare %s; declared constants are %s",
                        mnemonic, declared.keySet())
                .containsKey(mnemonic);
        return declared.get(mnemonic);
    }

    /**
     * The thirty mnemonics this suite expects, as one roster.
     *
     * <p>Used wherever a property has to be asserted about the <em>expected</em> constants rather than
     * about whatever the class happens to declare. Reflecting over the class and then asserting a
     * property the reflection filter already selected for would be a tautology: a constant declared as
     * a {@code char}, or one that lost its {@code final}, would simply drop out of
     * {@link #declaredAttributeBytes()} and never be examined.
     *
     * @return every expected mnemonic, attribute plane first
     */
    private static List<String> expectedMnemonics() {
        List<String> roster = new ArrayList<>(FIELD_ATTRIBUTE_NAMES);
        roster.addAll(COLOUR_NAMES);
        roster.addAll(HIGHLIGHT_NAMES);
        return roster;
    }

    /**
     * Looks up one declared field by mnemonic, so that its modifiers and declared type can be
     * inspected as well as its value.
     *
     * @param mnemonic the copybook mnemonic
     * @return the reflected field
     */
    private static Field declaredField(String mnemonic) {
        try {
            return BmsAttributes.class.getDeclaredField(mnemonic);
        } catch (NoSuchFieldException notDeclared) {
            throw new AssertionError("BmsAttributes must declare " + mnemonic, notDeclared);
        }
    }

    /**
     * Groups a roster of mnemonics by their byte value and returns only the groups that share one -
     * that is, the collisions. An empty result means the roster is pairwise distinct.
     *
     * <p>Returning the colliding names rather than a boolean is deliberate: a bare "expected 18 but
     * was 17" tells a reviewer that something collided but not what, and the two mnemonics involved
     * are the whole diagnosis.
     *
     * @param names the mnemonics to check, normally one plane's roster
     * @return a map from {@code X'hh'} rendering to the mnemonics sharing that byte; empty when none do
     */
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

    /**
     * Renders a mnemonic-to-byte selection as {@code NAME=X'hh'} text, for failure messages that
     * name the offending pair rather than leaving a signed decimal to be decoded by hand.
     *
     * @param names the mnemonics to render
     * @return a sorted, human-readable rendering of each name and its byte
     */
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

        /**
         * The ten consumed mnemonics, each paired with the occurrence count that justifies it. The
         * count is quoted for auditability; the assertion is that the class declares the constant,
         * because a missing one would fail the seventeen controllers that copy {@code DFHBMSCA}.
         */
        @ParameterizedTest(name = "{0} is defined - {1} occurrence(s) in app/cbl")
        @CsvSource({
            // Colour plane. DFHRED additionally occurs once in app/cpy/CSSETATY.cpy.
            "DFHRED, 30",
            "DFHGREEN, 8",
            "DFHDFCOL, 6",
            "DFHNEUTR, 5",
            // Basic field attribute plane.
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

        /**
         * {@code DFHGREEN} gets its own test because it is the easiest constant in the class to lose:
         * the migration plan's illustrative list names {@code DFHRED}, {@code DFHBMASB},
         * {@code DFHBMPRO} and {@code DFHUNIMD} "and related attribute constants", and stops there -
         * yet eight programs use {@code DFHGREEN}, one apiece in {@code COADM01C}, {@code COBIL00C},
         * {@code COMEN01C}, {@code CORPT00C}, {@code COTRN02C}, {@code COUSR01C}, {@code COUSR02C}
         * and {@code COUSR03C}, to confirm a completed unit of work.
         */
        @Test
        @DisplayName("DFHGREEN is declared, though the plan's illustrative list omits it")
        void dfhGreenIsDeclaredDespiteBeingAbsentFromThePlansList() {
            assertThat(declaredAttributeBytes())
                    .as("DFHGREEN has 8 occurrences in app/cbl - the success signal, and the exact "
                            + "counterpart of DFHRED")
                    .containsKey("DFHGREEN");
        }

        /**
         * {@code DFHUNIMD} is the mirror image of {@code DFHGREEN}: the migration plan names it
         * explicitly and <strong>no program in this repository uses it</strong> - zero occurrences,
         * re-counted here. It must exist and must never be deleted. Unused code carried over from a
         * legacy system is preserved, not tidied away; deciding it was surplus is a judgement this
         * migration is not entitled to make.
         */
        @Test
        @DisplayName("DFHUNIMD is declared even though it has zero consumers")
        void dfhUnimdIsDeclaredDespiteHavingNoConsumer() {
            assertThat(declaredAttributeBytes())
                    .as("DFHUNIMD has 0 occurrences anywhere in this repository, is mandated by the "
                            + "migration plan, and must not be removed")
                    .containsKey("DFHUNIMD");
        }

        /**
         * {@code DFHRED} is the single most important constant in the class. {@code CSSETATY} - the
         * generic error-highlight copybook - moves it into the offending field's colour item whenever
         * the field is flagged not-valid or blank and the program is being re-entered
         * ({@code app/cpy/CSSETATY.cpy:L18-L22}), and {@code COACTUPC} alone expands that copybook 39
         * times. The sibling field-attribute helper, and the online behaviour gate that checks the
         * error highlight applies only on re-entry, both rest on this one byte.
         */
        @Test
        @DisplayName("DFHRED is declared - the byte CSSETATY moves onto an invalid field")
        void dfhRedIsDeclaredAsTheErrorHighlightColour() {
            assertThat(declaredAttributeBytes())
                    .as("DFHRED: 30 occurrences in app/cbl plus 1 in app/cpy/CSSETATY.cpy, and the "
                            + "dependency of every error-highlight path in the online layer")
                    .containsKey("DFHRED");
        }

        /**
         * The exact-count guard. Thirty is not an arbitrary number: it is eighteen basic field
         * attributes plus eight colours plus four highlighting values, and the class Javadoc commits
         * to that set. Pinning the total means a constant quietly added without documentation, or one
         * quietly removed as "unused", fails here rather than drifting in unnoticed.
         */
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

        /**
         * Every expected mnemonic is present, and nothing beyond them. Asserted as an exact-content
         * comparison rather than a series of {@code containsKey} calls so that an undocumented
         * addition is caught as well as an omission.
         */
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

        /**
         * The class exposes one mnemonic map per plane, which is what makes the partition asserted
         * elsewhere in this suite structural rather than a naming convention a future edit could
         * quietly break. Each map must contain exactly its own plane's roster.
         *
         * <p>The map sizes carry a second, subtler guarantee. They are built with
         * {@code Map.ofEntries}, which rejects a duplicate key at class-initialisation time, so a map
         * whose size equals its roster size is itself proof that no two constants in that plane share
         * a byte - the class could not have loaded otherwise.
         */
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

        /**
         * Every declared constant is classified into exactly one plane, and no plane names a constant
         * that does not exist. Without this, a new constant could be added to the class and never
         * appear in any diagnostic map - which is precisely the state in which a parity failure
         * reports a bare signed byte instead of a mnemonic.
         */
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

        /**
         * Each map's key set is its plane's distinct byte values, so the key count must equal the
         * roster size. Stated separately from the value assertion above because it is the property
         * that fails if two constants in one plane are ever given the same byte.
         */
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

        /**
         * Eighteen basic field attributes, eighteen distinct bytes. A collision here would make two
         * different field treatments - say read-only and autoskip - indistinguishable on the wire, and
         * the only symptom would be a screen that behaves subtly wrongly.
         */
        @Test
        @DisplayName("the 18 basic field attribute bytes are pairwise distinct")
        void fieldAttributeBytesArePairwiseDistinct() {
            assertThat(collisionsWithin(FIELD_ATTRIBUTE_NAMES))
                    .as("two Section 1 mnemonics share a byte, which the 3270 architecture does not "
                            + "permit within the basic attribute plane")
                    .isEmpty();
        }

        /** Eight colours, eight distinct bytes: the default plus 3270 colour codes 1 through 7. */
        @Test
        @DisplayName("the 8 extended colour bytes are pairwise distinct")
        void colourBytesArePairwiseDistinct() {
            assertThat(collisionsWithin(COLOUR_NAMES))
                    .as("two Section 2 colours share a byte, so one colour would render as another")
                    .isEmpty();
        }

        /** Four highlighting values, four distinct bytes. */
        @Test
        @DisplayName("the 4 extended highlighting bytes are pairwise distinct")
        void highlightBytesArePairwiseDistinct() {
            assertThat(collisionsWithin(HIGHLIGHT_NAMES))
                    .as("two Section 3 highlighting values share a byte; only one highlight may be in "
                            + "force at a time, so these are alternatives and must differ")
                    .isEmpty();
        }

        /**
         * The single most consequential distinctness assertion in the suite. Red is this
         * application's universal "this field failed validation" signal and green its universal
         * "this completed" signal - {@code CSSETATY} applies the former, and eight programs apply the
         * latter. If the two bytes were ever equal, every successfully validated field would render
         * as an error, and no other check in the build would notice: there is no copybook here to
         * diff the values against.
         */
        @Test
        @DisplayName("DFHRED differs from DFHGREEN - error must never render as success")
        void dfhRedDiffersFromDfhGreen() {
            assertThat(BmsAttributes.DFHRED)
                    .as("DFHRED %s must differ from DFHGREEN %s",
                            BmsAttributes.toHex(BmsAttributes.DFHRED),
                            BmsAttributes.toHex(BmsAttributes.DFHGREEN))
                    .isNotEqualTo(BmsAttributes.DFHGREEN);
        }

        /**
         * The ten consumed mnemonics are mutually distinct within their own planes. Stated separately
         * from the full-plane assertions above because these ten are the ones whose collision would
         * have a visible effect: the other twenty are defined for completeness against IBM's tables
         * and no program moves them anywhere.
         */
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

        /**
         * The cross-plane assertion, scoped to the mnemonics the COBOL actually moves into a symbolic
         * map. Colours land in the colour item {@code xxxC} on the output map and field attributes in
         * the attribute item {@code xxxA} on the input map - different items entirely - so a shared
         * value would allow one to be mistaken for the other during review or debugging. All ten
         * consumed mnemonics are mutually distinct, and that is a property worth locking down.
         *
         * <p>Note carefully what is <em>not</em> claimed: the full thirty are not mutually distinct,
         * and must not be, because the planes share values by architecture. See
         * {@link #architecturalCrossPlaneCollisionsAreIntentional()}.
         */
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

        /**
         * {@code DFHUNIMD} has no consumer, so nothing would exercise it and nothing would notice if
         * it silently duplicated another constant. It is in fact unique across all thirty bytes, and
         * pinning that keeps the one constant nobody uses honest.
         */
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

        /**
         * The four documented cross-plane collisions, asserted as <strong>equalities</strong>. This
         * test exists to fail if someone ever "fixes" them: the byte a 3270 terminal receives is
         * interpreted according to the plane it arrived in, so {@code 0xF1} legitimately means
         * autoskip-with-MDT in the attribute plane, blue in the colour plane and blink in the
         * highlighting plane. Changing any of these to make the class globally unique would break
         * fidelity with the hardware and with the copybooks.
         */
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

        /**
         * Every constant is declared as a {@code byte}. The type is the encoding decision: a
         * {@code char} would hold a Unicode code point, a {@code String} would need a charset to
         * become bytes at all, and either would invite the platform default in through the back door.
         */
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

        /**
         * A BMS attribute is exactly one byte on the wire. Java's {@code byte} is signed, so most of
         * these read back negative; {@link BmsAttributes#unsigned(byte)} is the documented 0-255 view
         * and it must produce a value in that range for every constant.
         *
         * <p>The encoding itself is named in the class's own Javadoc - EBCDIC, with the graphic
         * character recorded for readability only and explicitly never used as the value - rather than
         * being a runtime artefact this test could read. So the assertion made here is the observable
         * consequence: each constant occupies a single byte, and the class never consults a charset to
         * say so. The explicit EBCDIC values are pinned separately, in {@code ValueProvenance}.
         */
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

        /**
         * The trap this class exists to avoid, asserted directly. IBM's listings show each attribute
         * as a printable character because the byte happens to be a displayable EBCDIC graphic:
         * {@code DFHRED} appears as {@code '2'} only because EBCDIC {@code '2'} is {@code 0xF2}, the
         * architected 3270 code for red. Transcribing those listings into Java {@code char} literals
         * would have produced {@code 0x32} - a completely different byte - and the screen would have
         * been quietly wrong with nothing failing. Each pair below asserts the declared byte is
         * <em>not</em> the Java literal for the same graphic.
         */
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

        /**
         * The public surface is exactly thirty attribute bytes plus the three diagnostic maps -
         * nothing else, and in particular <strong>no {@code char} and no {@code String} constant</strong>.
         *
         * <p>This closes the loophole the previous test cannot: {@code everyConstantIsDeclaredAsAByte}
         * proves the thirty expected constants are bytes, but a well-meaning addition such as
         * {@code public static final char DFHRED_GRAPHIC = '2'} would pass it while re-introducing
         * precisely the Unicode-versus-EBCDIC confusion the whole class is built to prevent. The
         * graphic character belongs in the Javadoc, where the class already records it as being for
         * readability only, and never in a constant.
         */
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

        /**
         * The class must never convert between text and bytes, because doing so would introduce a
         * charset - and a charset left unnamed is the platform default. Asserted structurally: no
         * declared field, method return type or method parameter mentions {@link Charset}. Bytes in,
         * bytes out.
         */
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

        /**
         * The auditable record of what was taken from IBM.
         *
         * <p><strong>{@code DFHBMSCA} - copied by all seventeen online programs - and
         * {@code DFHATTR} - named by two, {@code app/cbl/COSGN00C.cbl:L59} and
         * {@code app/cbl/COUSR01C.cbl:L57}, where the statement is commented out - are both absent
         * from this repository.</strong> Every value in the table below therefore comes from the IBM
         * CICS documentation the class under test cites: the {@code DFHBMSCA} standard attribute list
         * with its companion bit-map-of-attributes table, and the 3270 extended colour and extended
         * highlighting code assignments. <strong>Not one of them was read from, or can be checked
         * against, any file in this checkout</strong> - that is the substance of risk R-D, and this
         * test is where the transcription is pinned so a future change to a value has to be
         * deliberate.
         *
         * <p>Read the second column as the byte and the mnemonic as its meaning within its plane.
         */
        @ParameterizedTest(name = "{0} = {1}")
        @CsvSource({
            // -------- Section 1: DFHBMSCA basic field attribute bytes (18) --------
            // Unprotected alphanumeric.
            "DFHBMUNP, 0x40",
            "DFHBMFSE, 0xC1",
            "DFHBMBRY, 0xC8",
            "DFHUNIMD, 0xC9",
            "DFHBMDAR, 0x4C",
            "DFHUNNOD, 0x4D",
            // Unprotected numeric.
            "DFHBMUNN, 0x50",
            "DFHUNNUM, 0xD1",
            "DFHUNNUB, 0xD8",
            "DFHUNINT, 0xD9",
            "DFHUNNON, 0x5D",
            // Protected.
            "DFHBMPRO, 0x60",
            "DFHBMPRF, 0x61",
            "DFHPROTI, 0xE8",
            "DFHPROTN, 0x6C",
            // Autoskip - protected and numeric together.
            "DFHBMASK, 0xF0",
            "DFHBMASF, 0xF1",
            "DFHBMASB, 0xF8",
            // -------- Section 2: extended colour values (8) --------
            "DFHDFCOL, 0x00",
            "DFHBLUE, 0xF1",
            "DFHRED, 0xF2",
            "DFHPINK, 0xF3",
            "DFHGREEN, 0xF4",
            "DFHTURQ, 0xF5",
            "DFHYELLO, 0xF6",
            "DFHNEUTR, 0xF7",
            // -------- Section 3: extended highlighting values (4) --------
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

        /**
         * Every attribute constant is {@code public static final}. Asserted through reflection rather
         * than trusted from reading the class, because a constant that lost its {@code final} would
         * still compile and still pass every value assertion in this suite while being reassignable at
         * run time.
         */
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

        /**
         * No declared field, of any visibility, is non-static or non-final, and none is an array. The
         * array clause matters: a {@code static final} array is still mutable content, so exposing one
         * would give every caller a shared writable buffer - exactly the static mutable state the
         * migration's practices forbid. The private bit masks are covered here too, not just the public
         * constants.
         *
         * <p>Synthetic members are skipped, because they are not part of what the class declares: a
         * compiler can add them (an assertion flag, for instance) and a coverage agent can add a
         * probe array. Under the current toolchain none is present - a probe run with the coverage
         * agent attached listed only the class's own six masks, thirty bytes and three maps - so the
         * guard costs nothing today and keeps the assertion honest if that ever changes.
         */
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

        /**
         * The three diagnostic maps are unmodifiable, so handing one to a caller cannot leak write
         * access to shared state. Each is probed with a mutation attempt rather than merely inspected,
         * because unmodifiability is a behaviour and not a visible type.
         */
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

        /** The type is a namespace for constants, so it is final and cannot be subclassed. */
        @Test
        @DisplayName("the class is final")
        void theClassIsFinal() {
            assertThat(Modifier.isFinal(BmsAttributes.class.getModifiers()))
                    .as("a constant holder must not be extensible")
                    .isTrue();
        }

        /**
         * Not instantiable, not even reflectively: the private constructor throws, so a reflective
         * caller fails loudly instead of getting a useless instance.
         */
        @Test
        @DisplayName("the class cannot be instantiated, not even reflectively")
        void cannotBeInstantiated() throws ReflectiveOperationException {
            Constructor<BmsAttributes> constructor = BmsAttributes.class.getDeclaredConstructor();
            constructor.setAccessible(true);

            assertThatExceptionOfType(InvocationTargetException.class)
                    .isThrownBy(constructor::newInstance)
                    .withCauseInstanceOf(AssertionError.class);
        }

        /** Exactly one constructor, and it is private. */
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

        /**
         * The full truth table for all six predicates across all eighteen Section 1 bytes, derived
         * from the 3270 bit specification the class documents: bit 2 protected, bit 3 numeric, bits
         * 4-5 the display and detectability selector (binary 10 intensified, binary 11 non-display),
         * bit 7 the modified data tag, and autoskip being protected and numeric together.
         *
         * <p>One table rather than six separate tests, because the six predicates are six views of one
         * byte and reading them side by side is how a wrong bit mask becomes obvious. It also drives
         * every predicate branch from both sides - including the short-circuit in the autoskip
         * conjunction, which needs an unprotected byte, a protected non-numeric byte and a protected
         * numeric byte to be fully exercised. Rows 12 and 16 supply the last two.
         */
        @ParameterizedTest(name = "{0}: prot={1} num={2} skip={3} bright={4} dark={5} mdt={6}")
        @CsvSource({
            //         mnemonic, protected, numeric, autoskip, intensified, nonDisplay, mdt
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

        /**
         * Autoskip is strictly stronger than protection, and the three constants that satisfy it are
         * exactly the three the class documents. Stated separately from the table because it is the
         * property a reader is most likely to get wrong: {@code DFHBMPRO} and {@code DFHBMPRF} are
         * protected but <em>not</em> autoskip - the cursor stops in them and keystrokes are simply
         * rejected.
         */
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

        /**
         * The modified data tag is the only difference between the two pairs the class calls out, and
         * the reason these programs can redisplay a screen without losing the values already on it.
         * {@code DFHBMFSE}, at twenty-three occurrences the most used field attribute in the codebase,
         * exists precisely for that.
         */
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

        /**
         * Binary 11 in the display selector is non-display, not "even brighter", so a naive
         * single-bit intensity test would report a hidden field as intensified. The two states are
         * mutually exclusive for every declared attribute byte.
         */
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

        /**
         * The signed-byte trap, made explicit. {@code DFHBMFSE} is {@code 0xC1}, which prints as -63
         * if read as a signed byte; {@link BmsAttributes#unsigned(byte)} is the documented 0-255 view
         * used whenever an attribute is logged or compared against a documented hexadecimal code.
         */
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

        /** The {@code X'hh'} notation both the copybooks and IBM's documentation use. */
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

        /** A byte each plane knows resolves to its own mnemonic. */
        @Test
        @DisplayName("a known byte resolves to its mnemonic in each plane")
        void aKnownByteResolvesToItsMnemonic() {
            assertThat(BmsAttributes.fieldAttributeMnemonic(BmsAttributes.DFHBMFSE))
                    .isEqualTo("DFHBMFSE");
            assertThat(BmsAttributes.colourMnemonic(BmsAttributes.DFHRED)).isEqualTo("DFHRED");
            assertThat(BmsAttributes.highlightMnemonic(BmsAttributes.DFHREVRS))
                    .isEqualTo("DFHREVRS");
        }

        /**
         * An unrecognised byte is a legitimate outcome, not an error: the 3270 architecture permits
         * combinations {@code DFHBMSCA} never named, and a diagnostic helper has to describe them
         * rather than reject them. Each lookup falls back to the hexadecimal rendering.
         *
         * <p>The bytes chosen are real constants from the <em>other</em> planes, which makes the point
         * twice over - {@code 0xF2} is red as a colour and simply an unnamed combination as a field
         * attribute.
         */
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

        /**
         * The plane separation, demonstrated on the one byte that belongs to all three. {@code 0xF1}
         * resolves to a different mnemonic in each plane, which is exactly why the class keeps three
         * maps instead of one and why global uniqueness is neither expected nor asserted.
         */
        @Test
        @DisplayName("0xF1 names a different thing in each of the three planes")
        void oneByteNamesADifferentThingInEachPlane() {
            byte shared = BmsAttributes.DFHBMASF;

            assertThat(BmsAttributes.fieldAttributeMnemonic(shared)).isEqualTo("DFHBMASF");
            assertThat(BmsAttributes.colourMnemonic(shared)).isEqualTo("DFHBLUE");
            assertThat(BmsAttributes.highlightMnemonic(shared)).isEqualTo("DFHBLINK");
        }

        /**
         * Every declared constant resolves to its own name within its own plane. This closes the loop
         * between the value table and the diagnostic maps: a constant whose value was changed without
         * its map entry being updated would surface here as a mismatched mnemonic.
         */
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
