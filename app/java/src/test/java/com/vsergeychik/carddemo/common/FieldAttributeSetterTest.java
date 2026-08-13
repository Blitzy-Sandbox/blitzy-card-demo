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
 */
@DisplayName("FieldAttributeSetter - the CSSETATY REENTER-only error highlight")
class FieldAttributeSetterTest {
    private static final String ACCOUNT_STATUS_FIELD = "ACSTTUS";

    private static final String ACCOUNT_NUMBER_FIELD = "ACSTNUM";

    private static final String ACCOUNT_UPDATE_MAP = "CACTUPA";

    private static final class SymbolicMapField {
        private static final byte SENTINEL_COLOUR = (byte) 0xA7;

        private static final String SENTINEL_TEXT = "PRESET";

        private byte colourItem = SENTINEL_COLOUR;
        private String outputItem = SENTINEL_TEXT;

        void apply(FieldHighlight highlight) {
            if (highlight.colourItemAssigned()) {
                colourItem = highlight.colourItemValue();
            }
            if (highlight.outputItemAssigned()) {
                outputItem = highlight.outputItemValue();
            }
        }

        byte colourItem() {
            return colourItem;
        }

        String outputItem() {
            return outputItem;
        }

        boolean colourItemUntouched() {
            return colourItem == SENTINEL_COLOUR;
        }

        boolean outputItemUntouched() {
            return SENTINEL_TEXT.equals(outputItem);
        }
    }

    @Nested
    @DisplayName("The truth table - CSSETATY.cpy:L18-L27, all eight rows")
    class TruthTable {
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
        void reproducesEveryRowFromTheTwoFlags(boolean notOk, boolean blank, boolean reenter,
                boolean expectColour, boolean expectAsterisk) {
            FieldHighlight highlight = FieldAttributeSetter.resolveFromFlags(notOk, blank, reenter,
                    ACCOUNT_STATUS_FIELD, ACCOUNT_UPDATE_MAP);

            assertThat(highlight.colourItemAssigned()).as("colour item").isEqualTo(expectColour);
            assertThat(highlight.outputItemAssigned()).as("output item").isEqualTo(expectAsterisk);
            assertThat(highlight.untouched()).isEqualTo(!expectColour);
        }

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

    @Nested
    @DisplayName("Applied to a symbolic map - the sentinel must survive every no-op row")
    class AppliedToASymbolicMap {
        @ParameterizedTest(name = "{0} REENTER={1} -> colourMoved={2} asteriskMoved={3}")
        @CsvSource({
            "OK,     false, false, false",
            "OK,     true,  false, false",
            "NOT_OK, false, false, false",
            "NOT_OK, true,  true,  false",
            "BLANK,  false, false, false",
            "BLANK,  true,  true,  true",
        })
        void theSeededValuesSurviveExactlyWhereTheCopybookMovesNothing(FieldValidationState state,
                boolean reenter, boolean expectColour, boolean expectAsterisk) {
            SymbolicMapField field = new SymbolicMapField();
            field.apply(FieldAttributeSetter.resolve(state, reenter, ACCOUNT_STATUS_FIELD,
                    ACCOUNT_UPDATE_MAP));

            assertThat(field.colourItemUntouched()).as("colour item survived").isEqualTo(!expectColour);
            assertThat(field.outputItemUntouched()).as("data item survived").isEqualTo(!expectAsterisk);

            if (expectColour) {
                assertThat(field.colourItem()).isEqualTo(BmsAttributes.DFHRED);
            }
            if (expectAsterisk) {
                assertThat(field.outputItem()).isEqualTo(FieldAttributeSetter.ASTERISK);
            }
        }

        @ParameterizedTest(name = "{0} on first entry changes nothing at all")
        @CsvSource({"NOT_OK", "BLANK"})
        @DisplayName("G38: a validation failure outside REENTER leaves both items byte-identical")
        void aValidationFailureOnFirstEntryChangesNothingAtAll(FieldValidationState state) {
            SymbolicMapField field = new SymbolicMapField();

            field.apply(FieldAttributeSetter.resolve(state, false, ACCOUNT_STATUS_FIELD,
                    ACCOUNT_UPDATE_MAP));

            assertThat(field.colourItemUntouched()).as("colour item must not be written at all")
                    .isTrue();
            assertThat(field.outputItemUntouched()).as("data item must not be written at all").isTrue();
        }

        @Test
        @DisplayName("an untouched colour item is not DFHRED, not DFHDFCOL, and the text is not blanked")
        void untouchedIsNotADefaultAndNotABlank() {
            SymbolicMapField field = new SymbolicMapField();

            field.apply(FieldAttributeSetter.resolve(FieldValidationState.NOT_OK, false,
                    ACCOUNT_STATUS_FIELD, ACCOUNT_UPDATE_MAP));

            assertThat(field.colourItem()).isNotEqualTo(BmsAttributes.DFHRED);
            assertThat(field.colourItem())
                    .as("resetting to the default colour is a MOVE, not the no-op CSSETATY performs")
                    .isNotEqualTo(BmsAttributes.DFHDFCOL);
            assertThat(field.outputItem()).isNotEmpty().isNotBlank()
                    .isNotEqualTo(FieldAttributeSetter.ASTERISK);
        }

        @Test
        @DisplayName("NOT-OK under re-entry reddens the field but never overwrites its content")
        void notOkRedensTheFieldWithoutTouchingItsContent() {
            SymbolicMapField field = new SymbolicMapField();

            field.apply(FieldAttributeSetter.resolve(FieldValidationState.NOT_OK, true,
                    ACCOUNT_STATUS_FIELD, ACCOUNT_UPDATE_MAP));

            assertThat(field.colourItem()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(field.colourItemUntouched()).isFalse();
            assertThat(field.outputItemUntouched())
                    .as("the asterisk is guarded by BLANK at CSSETATY.cpy:L23, not by NOT-OK").isTrue();
        }

        @Test
        @DisplayName("BLANK under re-entry writes both items, and the two values stay in their lanes")
        void blankUnderReentryWritesBothItemsToTheirOwnLanes() {
            SymbolicMapField field = new SymbolicMapField();

            field.apply(FieldAttributeSetter.resolve(FieldValidationState.BLANK, true,
                    ACCOUNT_STATUS_FIELD, ACCOUNT_UPDATE_MAP));

            assertThat(field.colourItem()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(field.outputItem()).isEqualTo("*");

            assertThat(field.colourItem()).isNotEqualTo((byte) '*');
            assertThat(field.outputItem())
                    .isNotEqualTo(String.valueOf((char) BmsAttributes.unsigned(BmsAttributes.DFHRED)));
        }
    }

    @Nested
    @DisplayName("The boolean operands of CSSETATY.cpy:L18-L20, one at a time")
    class BooleanOperands {
        @Test
        @DisplayName("operand (i): NOT-OK alone satisfies the OR")
        void notOkAloneSatisfiesTheOr() {
            FieldHighlight highlight = FieldAttributeSetter.resolveFromFlags(true, false, true,
                    ACCOUNT_STATUS_FIELD, ACCOUNT_UPDATE_MAP);

            assertThat(highlight.colourItemAssigned()).isTrue();
            assertThat(highlight.outputItemAssigned())
                    .as("the inner IF tests blankness only").isFalse();
        }

        @Test
        @DisplayName("operand (ii): BLANK alone satisfies the OR, with NOT-OK false")
        void blankAloneSatisfiesTheOrWithNotOkFalse() {
            FieldHighlight highlight = FieldAttributeSetter.resolveFromFlags(false, true, true,
                    ACCOUNT_STATUS_FIELD, ACCOUNT_UPDATE_MAP);

            assertThat(highlight.colourItemAssigned()).isTrue();
            assertThat(highlight.outputItemAssigned()).isTrue();

            assertThat(FieldValidationState.of(false, true)).isEqualTo(FieldValidationState.BLANK);
            assertThat(FieldValidationState.BLANK.notOk()).isFalse();
        }

        @Test
        @DisplayName("both operands false: the OR is false and re-entry is never reached")
        void bothOperandsFalseLeavesTheFieldAlone() {
            FieldHighlight onReentry = FieldAttributeSetter.resolveFromFlags(false, false, true,
                    ACCOUNT_STATUS_FIELD, ACCOUNT_UPDATE_MAP);
            FieldHighlight onFirstEntry = FieldAttributeSetter.resolveFromFlags(false, false, false,
                    ACCOUNT_STATUS_FIELD, ACCOUNT_UPDATE_MAP);

            assertThat(onReentry.untouched()).isTrue();
            assertThat(onFirstEntry.untouched()).isTrue();
            assertThat(onReentry).as("re-entry is irrelevant once the OR is false")
                    .isEqualTo(onFirstEntry);
        }

        @ParameterizedTest(name = "{0}: highlights on re-entry, does nothing on first entry")
        @CsvSource({"NOT_OK", "BLANK"})
        @DisplayName("the AND flips the outcome while the OR is held true")
        void theAndOperandDecidesOnceTheOrIsTrue(FieldValidationState state) {
            FieldHighlight reentered = FieldAttributeSetter.resolve(state, true, ACCOUNT_STATUS_FIELD,
                    ACCOUNT_UPDATE_MAP);
            FieldHighlight firstEntry = FieldAttributeSetter.resolve(state, false,
                    ACCOUNT_STATUS_FIELD, ACCOUNT_UPDATE_MAP);

            assertThat(reentered.untouched()).isFalse();
            assertThat(firstEntry.untouched()).isTrue();
            assertThat(reentered).isNotEqualTo(firstEntry);
        }
    }

    @Nested
    @DisplayName("The decision is field-agnostic - 39 sites, one behaviour")
    class FieldAgnosticism {
        @ParameterizedTest(name = "{0} REENTER={1} decides alike for ACSTTUS and ACSTNUM")
        @CsvSource({
            "OK,     false, false, false",
            "OK,     true,  false, false",
            "NOT_OK, false, false, false",
            "NOT_OK, true,  true,  false",
            "BLANK,  false, false, false",
            "BLANK,  true,  true,  true",
        })
        void twoDifferentFieldsDecideIdentically(FieldValidationState state, boolean reenter,
                boolean expectColour, boolean expectAsterisk) {
            FieldHighlight status = FieldAttributeSetter.resolve(state, reenter, ACCOUNT_STATUS_FIELD,
                    ACCOUNT_UPDATE_MAP);
            FieldHighlight number = FieldAttributeSetter.resolve(state, reenter, ACCOUNT_NUMBER_FIELD,
                    ACCOUNT_UPDATE_MAP);

            assertThat(status.colourItemAssigned()).isEqualTo(expectColour);
            assertThat(number.colourItemAssigned()).isEqualTo(expectColour);
            assertThat(status.outputItemAssigned()).isEqualTo(expectAsterisk);
            assertThat(number.outputItemAssigned()).isEqualTo(expectAsterisk);

            assertThat(number.screenFieldPrefix()).isNotEqualTo(status.screenFieldPrefix());
            assertThat(number.untouched()).isEqualTo(status.untouched());
            assertThat(number.describe().replace(ACCOUNT_NUMBER_FIELD, ACCOUNT_STATUS_FIELD))
                    .isEqualTo(status.describe());
        }

        @Test
        @DisplayName("ACSTNUM resolves to ACSTNUMC and ACSTNUMO, never to ACSTNUML, ...F or ...A")
        void acstnumResolvesToItsColourAndDataItemsOnly() {
            FieldHighlight highlight = FieldAttributeSetter.resolve(FieldValidationState.BLANK, true,
                    ACCOUNT_NUMBER_FIELD, ACCOUNT_UPDATE_MAP);

            assertThat(highlight.colourItemName()).isEqualTo("ACSTNUMC");
            assertThat(highlight.outputItemName()).isEqualTo("ACSTNUMO");
            assertThat(highlight.outputMapGroupName()).isEqualTo("CACTUPAO");

            assertThat(highlight.colourItemName()).isNotIn("ACSTNUML", "ACSTNUMF", "ACSTNUMA",
                    "ACSTNUMI");
            assertThat(highlight.outputItemName()).isNotIn("ACSTNUML", "ACSTNUMF", "ACSTNUMA",
                    "ACSTNUMI");
            assertThat(highlight.describe())
                    .isEqualTo("MOVE DFHRED TO ACSTNUMC OF CACTUPAO; MOVE '*' TO ACSTNUMO OF CACTUPAO");
        }

        @Test
        @DisplayName("interleaving a different row does not disturb the one either side of it")
        void repeatedInvocationsAreIndependent() {
            FieldHighlight first = FieldAttributeSetter.resolve(FieldValidationState.BLANK, true,
                    ACCOUNT_STATUS_FIELD, ACCOUNT_UPDATE_MAP);
            FieldHighlight interleaved = FieldAttributeSetter.resolve(FieldValidationState.OK, true,
                    ACCOUNT_STATUS_FIELD, ACCOUNT_UPDATE_MAP);
            FieldHighlight third = FieldAttributeSetter.resolve(FieldValidationState.BLANK, true,
                    ACCOUNT_STATUS_FIELD, ACCOUNT_UPDATE_MAP);

            assertThat(third).isEqualTo(first).hasSameHashCodeAs(first);
            assertThat(third.describe()).isEqualTo(first.describe());
            assertThat(interleaved.untouched()).as("the interleaved row is genuinely different")
                    .isTrue();

            SymbolicMapField firstMap = new SymbolicMapField();
            SymbolicMapField thirdMap = new SymbolicMapField();
            firstMap.apply(first);
            thirdMap.apply(third);

            assertThat(thirdMap.colourItem()).isEqualTo(firstMap.colourItem());
            assertThat(thirdMap.outputItem()).isEqualTo(firstMap.outputItem());
        }
    }
}
