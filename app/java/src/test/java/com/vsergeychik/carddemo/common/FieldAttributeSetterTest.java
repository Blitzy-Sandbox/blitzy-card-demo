package com.vsergeychik.carddemo.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldHighlight;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldValidationState;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Parity tests for {@link FieldAttributeSetter}, the Java form of {@code app/cpy/CSSETATY.cpy}.
 *
 * <p>The class under test is small, so these tests are <em>exhaustive</em> rather than
 * representative: every one of the eight rows of the copybook's truth table is asserted explicitly,
 * both through the two-flag entry point and through the three-state enum. That matters more here than
 * the line count suggests, because the rule is expanded thirty-nine times in
 * {@code app/cbl/COACTUPC.cbl} and a single wrong row would be visible on every screen that program
 * paints.
 *
 * <p>Two assertions are deliberately adversarial. First, the blank-field-on-first-entry rows are
 * asserted to change <strong>nothing</strong>, because that is the outcome a flattened
 * re-implementation would get wrong (gate G38). Second, the two move targets are asserted to be
 * non-interchangeable: a test that still passed with the colour byte and the asterisk swapped would
 * not be testing the thing that actually breaks.
 */
@DisplayName("FieldAttributeSetter - the CSSETATY REENTER-only error highlight")
class FieldAttributeSetterTest {

    /** The {@code (SCRNVAR2)} value of the first live site, {@code app/cbl/COACTUPC.cbl:L3210}. */
    private static final String ACCOUNT_STATUS_FIELD = "ACSTTUS";

    /** The {@code (MAPNAME3)} value shared by all thirty-nine sites, seven characters wide. */
    private static final String ACCOUNT_UPDATE_MAP = "CACTUPA";

    @Nested
    @DisplayName("The truth table - CSSETATY.cpy:L18-L27, all eight rows")
    class TruthTable {

        /**
         * Drives every combination of the three inputs through the two-flag entry point, including
         * the two rows where both {@code 88}-levels are reported set.
         *
         * @param notOk           whether {@code FLG-<field>-NOT-OK} holds
         * @param blank           whether {@code FLG-<field>-BLANK} holds
         * @param reenter         whether {@code CDEMO-PGM-REENTER} holds
         * @param expectColour    whether {@code DFHRED} must reach the {@code ...C} item
         * @param expectAsterisk  whether {@code '*'} must reach the {@code ...O} item
         */
        @ParameterizedTest(name = "NOT-OK={0} BLANK={1} REENTER={2} -> colour={3} asterisk={4}")
        @CsvSource({
            // notOk, blank, reenter, colour, asterisk
            "false, false, false, false, false",
            "false, false, true,  false, false",
            "true,  false, false, false, false",
            "true,  false, true,  true,  false",
            "false, true,  false, false, false",
            "false, true,  true,  true,  true",
            "true,  true,  false, false, false",
            "true,  true,  true,  true,  true",
        })
        void reproducesEveryRowFromTheTwoFlags(boolean notOk, boolean blank, boolean reenter,
                boolean expectColour, boolean expectAsterisk) {

            FieldHighlight highlight = FieldAttributeSetter.resolveFromFlags(notOk, blank, reenter,
                    ACCOUNT_STATUS_FIELD, ACCOUNT_UPDATE_MAP);

            assertThat(highlight.colourItemAssigned()).as("colour item").isEqualTo(expectColour);
            assertThat(highlight.outputItemAssigned()).as("output item").isEqualTo(expectAsterisk);
            assertThat(highlight.untouched()).isEqualTo(!expectColour);
        }

        /** The same eight rows through the identity-free overload, which must decide identically. */
        @ParameterizedTest(name = "NOT-OK={0} BLANK={1} REENTER={2} -> colour={3} asterisk={4}")
        @CsvSource({
            "false, false, false, false, false",
            "false, false, true,  false, false",
            "true,  false, false, false, false",
            "true,  false, true,  true,  false",
            "false, true,  false, false, false",
            "false, true,  true,  true,  true",
            "true,  true,  false, false, false",
            "true,  true,  true,  true,  true",
        })
        void theIdentityFreeOverloadDecidesIdentically(boolean notOk, boolean blank, boolean reenter,
                boolean expectColour, boolean expectAsterisk) {

            FieldHighlight highlight = FieldAttributeSetter.resolveFromFlags(notOk, blank, reenter);

            assertThat(highlight.colourItemAssigned()).isEqualTo(expectColour);
            assertThat(highlight.outputItemAssigned()).isEqualTo(expectAsterisk);
            assertThat(highlight.screenFieldPrefix()).isEmpty();
            assertThat(highlight.mapName()).isEmpty();
        }

        /**
         * Drives all six state-by-context combinations through the enum entry point.
         *
         * @param state          the validation state
         * @param reenter        whether {@code CDEMO-PGM-REENTER} holds
         * @param expectColour   whether {@code DFHRED} must reach the {@code ...C} item
         * @param expectAsterisk whether {@code '*'} must reach the {@code ...O} item
         */
        @ParameterizedTest(name = "{0} REENTER={1} -> colour={2} asterisk={3}")
        @CsvSource({
            "OK,     false, false, false",
            "OK,     true,  false, false",
            "NOT_OK, false, false, false",
            "NOT_OK, true,  true,  false",
            "BLANK,  false, false, false",
            "BLANK,  true,  true,  true",
        })
        void reproducesEveryRowFromTheEnum(FieldValidationState state, boolean reenter,
                boolean expectColour, boolean expectAsterisk) {

            FieldHighlight highlight = FieldAttributeSetter.resolve(state, reenter,
                    ACCOUNT_STATUS_FIELD, ACCOUNT_UPDATE_MAP);

            assertThat(highlight.colourItemAssigned()).isEqualTo(expectColour);
            assertThat(highlight.outputItemAssigned()).isEqualTo(expectAsterisk);
        }

        /** The identity-free enum overload must agree with the four-argument form exactly. */
        @ParameterizedTest
        @EnumSource(FieldValidationState.class)
        void theIdentityFreeEnumOverloadDecidesIdentically(FieldValidationState state) {
            for (boolean reenter : new boolean[] {false, true}) {
                FieldHighlight unnamed = FieldAttributeSetter.resolve(state, reenter);
                FieldHighlight named = FieldAttributeSetter.resolve(state, reenter,
                        ACCOUNT_STATUS_FIELD, ACCOUNT_UPDATE_MAP);

                assertThat(unnamed.colourItemAssigned()).isEqualTo(named.colourItemAssigned());
                assertThat(unnamed.outputItemAssigned()).isEqualTo(named.outputItemAssigned());
            }
        }
    }

    @Nested
    @DisplayName("Gate G38 - the highlight applies ONLY in REENTER state")
    class ReenterGate {

        /**
         * On first entry nothing is touched, whatever the field's validation state. This is the row
         * a flattened translation gets wrong: the asterisk is nested inside the outer
         * {@code IF} at {@code CSSETATY.cpy:L20}, so re-entry gates it too.
         *
         * @param state the validation state to drive
         */
        @ParameterizedTest
        @EnumSource(FieldValidationState.class)
        void firstEntryNeverHighlightsAnything(FieldValidationState state) {
            FieldHighlight highlight = FieldAttributeSetter.resolve(state, false,
                    ACCOUNT_STATUS_FIELD, ACCOUNT_UPDATE_MAP);

            assertThat(highlight.untouched()).isTrue();
            assertThat(highlight.colourItemAssigned()).isFalse();
            assertThat(highlight.outputItemAssigned()).isFalse();
        }

        @Test
        @DisplayName("a BLANK field on first entry gets no asterisk - the row most easily got wrong")
        void blankOnFirstEntryGetsNoAsterisk() {
            FieldHighlight highlight = FieldAttributeSetter.resolve(FieldValidationState.BLANK, false,
                    ACCOUNT_STATUS_FIELD, ACCOUNT_UPDATE_MAP);

            assertThat(highlight.outputItemAssigned()).isFalse();
            assertThat(highlight.colourItemAssigned()).isFalse();
        }

        @Test
        @DisplayName("a valid field on re-entry is left alone")
        void validFieldOnReentryIsLeftAlone() {
            FieldHighlight highlight = FieldAttributeSetter.resolve(FieldValidationState.OK, true,
                    ACCOUNT_STATUS_FIELD, ACCOUNT_UPDATE_MAP);

            assertThat(highlight.untouched()).isTrue();
        }
    }

    @Nested
    @DisplayName("The two move targets are one letter apart and NOT interchangeable")
    class MoveTargets {

        @Test
        @DisplayName("DFHRED goes to the colour item as the EBCDIC byte 0xF2")
        void colourValueIsTheRedAttributeByte() {
            FieldHighlight highlight = FieldAttributeSetter.resolve(FieldValidationState.NOT_OK, true,
                    ACCOUNT_STATUS_FIELD, ACCOUNT_UPDATE_MAP);

            assertThat(highlight.colourItemValue()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(highlight.colourItemValue()).isEqualTo((byte) 0xF2);
            assertThat(BmsAttributes.unsigned(highlight.colourItemValue())).isEqualTo(0xF2);
        }

        @Test
        @DisplayName("the asterisk goes to the output item as one character of text")
        void outputValueIsASingleAsterisk() {
            FieldHighlight highlight = FieldAttributeSetter.resolve(FieldValidationState.BLANK, true,
                    ACCOUNT_STATUS_FIELD, ACCOUNT_UPDATE_MAP);

            assertThat(highlight.outputItemValue()).isEqualTo(FieldAttributeSetter.ASTERISK);
            assertThat(highlight.outputItemValue()).isEqualTo("*").hasSize(1);
        }

        /**
         * The adversarial assertion: had the two moves been transposed, the colour item would carry
         * the ASCII asterisk {@code 0x2A} instead of the extended-colour code {@code 0xF2}, and the
         * output item would carry the red attribute instead of a printable marker. Both are checked
         * to differ, so this test cannot pass with the targets swapped.
         */
        @Test
        @DisplayName("the colour byte and the asterisk cannot be swapped without failing")
        void theTwoValuesAreNotInterchangeable() {
            FieldHighlight highlight = FieldAttributeSetter.resolve(FieldValidationState.BLANK, true,
                    ACCOUNT_STATUS_FIELD, ACCOUNT_UPDATE_MAP);

            assertThat(highlight.colourItemValue()).isNotEqualTo((byte) '*');
            assertThat(highlight.outputItemValue())
                    .isNotEqualTo(String.valueOf((char) BmsAttributes.unsigned(BmsAttributes.DFHRED)));
            assertThat(FieldAttributeSetter.COLOUR_ITEM_SUFFIX)
                    .isNotEqualTo(FieldAttributeSetter.OUTPUT_ITEM_SUFFIX);
        }

        @Test
        @DisplayName("asking for a value that is not being moved is refused, not guessed")
        void unassignedValuesAreRefused() {
            FieldHighlight untouched = FieldAttributeSetter.resolve(FieldValidationState.OK, true,
                    ACCOUNT_STATUS_FIELD, ACCOUNT_UPDATE_MAP);
            FieldHighlight colourOnly = FieldAttributeSetter.resolve(FieldValidationState.NOT_OK,
                    true, ACCOUNT_STATUS_FIELD, ACCOUNT_UPDATE_MAP);

            assertThatIllegalStateException().isThrownBy(untouched::colourItemValue)
                    .withMessageContaining("colour item is not assigned");
            assertThatIllegalStateException().isThrownBy(untouched::outputItemValue)
                    .withMessageContaining("output item is not assigned");
            assertThatIllegalStateException().isThrownBy(colourOnly::outputItemValue);
            assertThat(colourOnly.colourItemValue()).isEqualTo(BmsAttributes.DFHRED);
        }

        /**
         * The suffix constants reproduce the copybook's own qualification, including the coincidence
         * that the data-item suffix and the map-direction suffix are the same letter.
         */
        @Test
        @DisplayName("the suffix constants match CSSETATY.cpy:L22 and L25")
        void suffixConstantsMatchTheCopybook() {
            assertThat(FieldAttributeSetter.COLOUR_ITEM_SUFFIX).isEqualTo("C");
            assertThat(FieldAttributeSetter.OUTPUT_ITEM_SUFFIX).isEqualTo("O");
            assertThat(FieldAttributeSetter.OUTPUT_MAP_SUFFIX)
                    .isEqualTo(FieldAttributeSetter.OUTPUT_ITEM_SUFFIX);
        }
    }

    @Nested
    @DisplayName("Item naming - the (SCRNVAR2) and (MAPNAME3) tokens")
    class ItemNaming {

        /**
         * Every {@code (SCRNVAR2)} value taken from a real {@code COPY CSSETATY REPLACING} site in
         * {@code app/cbl/COACTUPC.cbl} must resolve to its two symbolic-map items.
         *
         * @param prefix the field prefix as the COBOL substitutes it
         */
        @ParameterizedTest
        @ValueSource(strings = {"ACSTTUS", "OPNYEAR", "OPNMON", "OPNDAY", "ACRDLIM", "EXPYEAR",
            "EXPMON", "EXPDAY", "ACSHLIM", "RISYEAR", "RISMON"})
        void realSitesResolveToTheirColourAndOutputItems(String prefix) {
            FieldHighlight highlight = FieldAttributeSetter.resolve(FieldValidationState.BLANK, true,
                    prefix, ACCOUNT_UPDATE_MAP);

            assertThat(highlight.colourItemName()).isEqualTo(prefix + "C");
            assertThat(highlight.outputItemName()).isEqualTo(prefix + "O");
            assertThat(highlight.outputMapGroupName()).isEqualTo("CACTUPAO");
        }

        @Test
        @DisplayName("the seven-character map name yields an eight-character output group")
        void mapNameGainsTheDirectionSuffix() {
            FieldHighlight highlight = FieldAttributeSetter.resolve(FieldValidationState.NOT_OK, true,
                    ACCOUNT_STATUS_FIELD, ACCOUNT_UPDATE_MAP);

            assertThat(ACCOUNT_UPDATE_MAP).hasSize(7);
            assertThat(highlight.outputMapGroupName()).isEqualTo("CACTUPAO").hasSize(8);
        }

        @Test
        @DisplayName("an absent identity yields empty names rather than a bare suffix")
        void absentIdentityYieldsEmptyNames() {
            FieldHighlight highlight = FieldAttributeSetter.resolve(FieldValidationState.BLANK, true);

            assertThat(highlight.colourItemName()).isEmpty();
            assertThat(highlight.outputItemName()).isEmpty();
            assertThat(highlight.outputMapGroupName()).isEmpty();
        }

        @Test
        @DisplayName("a supplied field prefix with an absent map name is handled independently")
        void fieldPrefixAndMapNameAreIndependent() {
            FieldHighlight fieldOnly = FieldAttributeSetter.resolve(FieldValidationState.BLANK, true,
                    ACCOUNT_STATUS_FIELD, "");
            FieldHighlight mapOnly = FieldAttributeSetter.resolve(FieldValidationState.BLANK, true,
                    "", ACCOUNT_UPDATE_MAP);

            assertThat(fieldOnly.colourItemName()).isEqualTo("ACSTTUSC");
            assertThat(fieldOnly.outputMapGroupName()).isEmpty();
            assertThat(mapOnly.colourItemName()).isEmpty();
            assertThat(mapOnly.outputMapGroupName()).isEqualTo("CACTUPAO");
        }
    }

    @Nested
    @DisplayName("FieldValidationState - the (TESTVAR1) vocabulary")
    class ValidationState {

        @Test
        @DisplayName("exactly three states, each mapping to one 88-level outcome")
        void thePredicatesIdentifyExactlyOneStateEach() {
            assertThat(FieldValidationState.values()).hasSize(3);

            assertThat(FieldValidationState.OK.notOk()).isFalse();
            assertThat(FieldValidationState.OK.blank()).isFalse();
            assertThat(FieldValidationState.NOT_OK.notOk()).isTrue();
            assertThat(FieldValidationState.NOT_OK.blank()).isFalse();
            assertThat(FieldValidationState.BLANK.notOk()).isFalse();
            assertThat(FieldValidationState.BLANK.blank()).isTrue();
        }

        /**
         * The two-flag collapse, including the both-set case the source data model cannot actually
         * produce but the entry point must still answer for.
         *
         * @param notOk    whether {@code FLG-<field>-NOT-OK} holds
         * @param blank    whether {@code FLG-<field>-BLANK} holds
         * @param expected the state the pair collapses to
         */
        @ParameterizedTest(name = "of(notOk={0}, blank={1}) -> {2}")
        @CsvSource({
            "false, false, OK",
            "true,  false, NOT_OK",
            "false, true,  BLANK",
            "true,  true,  BLANK",
        })
        void blankDominatesWhenBothFlagsAreSet(boolean notOk, boolean blank,
                FieldValidationState expected) {
            assertThat(FieldValidationState.of(notOk, blank)).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("FieldHighlight - immutability and the nesting invariant")
    class Outcome {

        /**
         * The asterisk move is nested inside the colour move at {@code CSSETATY.cpy:L23-L26}, so an
         * output item assigned without a colour item is unreachable in the source. Constructing it
         * must fail, which is what makes a flattened re-implementation loud instead of silent.
         */
        @Test
        @DisplayName("an output item without a colour item is rejected")
        void theNestingInvariantIsEnforced() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new FieldHighlight(false, true, ACCOUNT_STATUS_FIELD,
                            ACCOUNT_UPDATE_MAP))
                    .withMessageContaining("nests");

            assertThat(new FieldHighlight(true, true, ACCOUNT_STATUS_FIELD, ACCOUNT_UPDATE_MAP)
                    .outputItemAssigned()).isTrue();
            assertThat(new FieldHighlight(true, false, ACCOUNT_STATUS_FIELD, ACCOUNT_UPDATE_MAP)
                    .colourItemAssigned()).isTrue();
            assertThat(FieldHighlight.none(ACCOUNT_STATUS_FIELD, ACCOUNT_UPDATE_MAP).untouched())
                    .isTrue();
        }

        @Test
        @DisplayName("the identity strings are required")
        void identityStringsMayNotBeNull() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new FieldHighlight(false, false, null, ACCOUNT_UPDATE_MAP));
            assertThatNullPointerException()
                    .isThrownBy(() -> new FieldHighlight(false, false, ACCOUNT_STATUS_FIELD, null));
            assertThatNullPointerException()
                    .isThrownBy(() -> FieldHighlight.none(null, ACCOUNT_UPDATE_MAP));
        }

        @Test
        @DisplayName("the decision is pure: repeated calls return equal, immutable outcomes")
        void theDecisionIsPure() {
            FieldHighlight first = FieldAttributeSetter.resolve(FieldValidationState.BLANK, true,
                    ACCOUNT_STATUS_FIELD, ACCOUNT_UPDATE_MAP);
            FieldHighlight second = FieldAttributeSetter.resolve(FieldValidationState.BLANK, true,
                    ACCOUNT_STATUS_FIELD, ACCOUNT_UPDATE_MAP);

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
            assertThat(first.colourItemValue()).isEqualTo(second.colourItemValue());
            assertThat(first.outputItemValue()).isEqualTo(second.outputItemValue());
        }

        @Test
        @DisplayName("outcomes that differ in either decision are not equal")
        void differentDecisionsAreNotEqual() {
            FieldHighlight colourOnly = FieldAttributeSetter.resolve(FieldValidationState.NOT_OK,
                    true, ACCOUNT_STATUS_FIELD, ACCOUNT_UPDATE_MAP);
            FieldHighlight withAsterisk = FieldAttributeSetter.resolve(FieldValidationState.BLANK,
                    true, ACCOUNT_STATUS_FIELD, ACCOUNT_UPDATE_MAP);

            assertThat(colourOnly).isNotEqualTo(withAsterisk);
            assertThat(colourOnly)
                    .isNotEqualTo(FieldHighlight.none(ACCOUNT_STATUS_FIELD, ACCOUNT_UPDATE_MAP));
        }

        @Test
        @DisplayName("the outcome type exposes no mutator and holds only final fields")
        void theOutcomeTypeIsImmutable() {
            for (Method method : FieldHighlight.class.getDeclaredMethods()) {
                assertThat(method.getName()).as("mutator found: %s", method.getName())
                        .doesNotStartWith("set");
            }
            for (Field field : FieldHighlight.class.getDeclaredFields()) {
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("non-final field: %s", field.getName()).isTrue();
            }
        }

        @Test
        @DisplayName("describe() renders the COBOL moves the decision stands for")
        void describeRendersTheMoves() {
            assertThat(FieldAttributeSetter
                    .resolve(FieldValidationState.OK, true, ACCOUNT_STATUS_FIELD, ACCOUNT_UPDATE_MAP)
                    .describe()).isEqualTo("no change (CSSETATY outer IF not taken)");

            assertThat(FieldAttributeSetter.resolve(FieldValidationState.NOT_OK, true,
                    ACCOUNT_STATUS_FIELD, ACCOUNT_UPDATE_MAP).describe())
                    .isEqualTo("MOVE DFHRED TO ACSTTUSC OF CACTUPAO");

            assertThat(FieldAttributeSetter.resolve(FieldValidationState.BLANK, true,
                    ACCOUNT_STATUS_FIELD, ACCOUNT_UPDATE_MAP).describe())
                    .isEqualTo("MOVE DFHRED TO ACSTTUSC OF CACTUPAO"
                            + "; MOVE '*' TO ACSTTUSO OF CACTUPAO");
        }

        @Test
        @DisplayName("describe() falls back to the copybook's own tokens when unnamed")
        void describeFallsBackToTheCopybookTokens() {
            assertThat(FieldAttributeSetter.resolve(FieldValidationState.BLANK, true).describe())
                    .isEqualTo("MOVE DFHRED TO (SCRNVAR2)C OF (MAPNAME3)O"
                            + "; MOVE '*' TO (SCRNVAR2)O OF (MAPNAME3)O");

            assertThat(FieldAttributeSetter.resolve(FieldValidationState.NOT_OK, true).describe())
                    .isEqualTo("MOVE DFHRED TO (SCRNVAR2)C OF (MAPNAME3)O");

            assertThat(FieldAttributeSetter
                    .resolve(FieldValidationState.BLANK, true, ACCOUNT_STATUS_FIELD, "").describe())
                    .isEqualTo("MOVE DFHRED TO ACSTTUSC OF (MAPNAME3)O"
                            + "; MOVE '*' TO ACSTTUSO OF (MAPNAME3)O");
        }
    }

    @Nested
    @DisplayName("Argument guards and non-instantiability")
    class Guards {

        @Test
        @DisplayName("every reference argument of the decision is required")
        void referenceArgumentsAreRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> FieldAttributeSetter.resolve(null, true))
                    .withMessageContaining("state");
            assertThatNullPointerException().isThrownBy(() -> FieldAttributeSetter
                    .resolve(null, true, ACCOUNT_STATUS_FIELD, ACCOUNT_UPDATE_MAP));
            assertThatNullPointerException().isThrownBy(() -> FieldAttributeSetter
                    .resolve(FieldValidationState.OK, true, null, ACCOUNT_UPDATE_MAP))
                    .withMessageContaining("screenFieldPrefix");
            assertThatNullPointerException().isThrownBy(() -> FieldAttributeSetter
                    .resolve(FieldValidationState.OK, true, ACCOUNT_STATUS_FIELD, null))
                    .withMessageContaining("mapName");
            assertThatNullPointerException().isThrownBy(() -> FieldAttributeSetter
                    .resolveFromFlags(true, false, true, null, ACCOUNT_UPDATE_MAP));
            assertThatNullPointerException().isThrownBy(() -> FieldAttributeSetter
                    .resolveFromFlags(true, false, true, ACCOUNT_STATUS_FIELD, null));
        }

        @Test
        @DisplayName("the helper is a stateless namespace and refuses instantiation")
        void theHelperIsNotInstantiable() throws ReflectiveOperationException {
            assertThat(Modifier.isFinal(FieldAttributeSetter.class.getModifiers())).isTrue();

            Constructor<FieldAttributeSetter> constructor =
                    FieldAttributeSetter.class.getDeclaredConstructor();
            assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue();
            constructor.setAccessible(true);

            assertThatExceptionOfType(InvocationTargetException.class)
                    .isThrownBy(constructor::newInstance)
                    .withCauseInstanceOf(AssertionError.class);
        }

        @Test
        @DisplayName("no static field of the helper is mutable")
        void noStaticStateIsMutable() {
            for (Field field : FieldAttributeSetter.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("mutable static field: %s", field.getName()).isTrue();
                }
            }
        }

        @Test
        @DisplayName("the asterisk constant is exactly the copybook literal")
        void theAsteriskConstantMatchesTheCopybook() {
            assertThat(FieldAttributeSetter.ASTERISK).isEqualTo("*").hasSize(1);
        }
    }
}
