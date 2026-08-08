package com.vsergeychik.carddemo.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link BmsAttributes}, the reproduction of the IBM-supplied {@code DFHBMSCA} and
 * {@code DFHATTR} copybooks.
 *
 * <p>Those two copybooks are referenced by seventeen and two COBOL programs respectively but are
 * absent from this repository, so their constants could not be diffed against any in-repository
 * source. That makes the decode helpers the only mechanical check available on the byte values, and
 * every one of them is exercised here from both sides.
 *
 * <p>The 3270 field-attribute byte encodes protection, numeric-shift, intensity and the
 * modified-data tag in separate bit positions, so each predicate is driven with an attribute that
 * sets its bit and one that does not.
 */
@DisplayName("BmsAttributes - DFHBMSCA and DFHATTR attribute, colour and highlight constants")
class BmsAttributesContractTest {

    @Nested
    @DisplayName("Unsigned widening and hex rendering")
    class UnsignedAndHex {

        @Test
        @DisplayName("unsigned() widens a negative byte to its 0-255 code point")
        void unsignedWidensNegativeBytes() {
            // 0xF0 is -16 as a Java byte; the attribute's identity is the unsigned 240.
            assertThat(BmsAttributes.unsigned(BmsAttributes.DFHBMASK)).isEqualTo(0xF0).isEqualTo(240);
            assertThat(BmsAttributes.unsigned((byte) 0x00)).isZero();
            assertThat(BmsAttributes.unsigned((byte) 0xFF)).isEqualTo(255);
        }

        @Test
        @DisplayName("toHex() renders the COBOL X'nn' literal form, upper case and zero padded")
        void toHexUsesCobolLiteralForm() {
            assertThat(BmsAttributes.toHex(BmsAttributes.DFHBMASK)).isEqualTo("X'F0'");
            assertThat(BmsAttributes.toHex((byte) 0x00)).isEqualTo("X'00'");
            assertThat(BmsAttributes.toHex((byte) 0x0A)).isEqualTo("X'0A'");
        }
    }

    @Nested
    @DisplayName("Mnemonic lookup - a known byte names itself, an unknown byte renders as hex")
    class MnemonicLookup {

        @Test
        @DisplayName("a known field attribute resolves to its DFHBMSCA mnemonic")
        void knownFieldAttributeResolves() {
            assertThat(BmsAttributes.fieldAttributeMnemonic(BmsAttributes.DFHBMPRO))
                    .isEqualTo("DFHBMPRO");
            assertThat(BmsAttributes.fieldAttributeMnemonic(BmsAttributes.DFHBMASB))
                    .isEqualTo("DFHBMASB");
            assertThat(BmsAttributes.fieldAttributeMnemonic(BmsAttributes.DFHUNIMD))
                    .isEqualTo("DFHUNIMD");
        }

        @Test
        @DisplayName("an unknown field attribute falls back to the hex literal, never to null")
        void unknownFieldAttributeFallsBackToHex() {
            assertThat(BmsAttributes.fieldAttributeMnemonic((byte) 0x7E)).isEqualTo("X'7E'");
        }

        @Test
        @DisplayName("a known colour resolves to its mnemonic")
        void knownColourResolves() {
            assertThat(BmsAttributes.colourMnemonic(BmsAttributes.DFHRED)).isEqualTo("DFHRED");
            assertThat(BmsAttributes.colourMnemonic(BmsAttributes.DFHDFCOL)).isEqualTo("DFHDFCOL");
            assertThat(BmsAttributes.colourMnemonic(BmsAttributes.DFHNEUTR)).isEqualTo("DFHNEUTR");
        }

        @Test
        @DisplayName("an unknown colour falls back to the hex literal")
        void unknownColourFallsBackToHex() {
            assertThat(BmsAttributes.colourMnemonic((byte) 0xAB)).isEqualTo("X'AB'");
        }

        @Test
        @DisplayName("a known highlight resolves to its mnemonic")
        void knownHighlightResolves() {
            assertThat(BmsAttributes.highlightMnemonic(BmsAttributes.DFHBLINK)).isEqualTo("DFHBLINK");
            assertThat(BmsAttributes.highlightMnemonic(BmsAttributes.DFHREVRS)).isEqualTo("DFHREVRS");
            assertThat(BmsAttributes.highlightMnemonic(BmsAttributes.DFHUNDLN)).isEqualTo("DFHUNDLN");
            assertThat(BmsAttributes.highlightMnemonic(BmsAttributes.DFHDFHI)).isEqualTo("DFHDFHI");
        }

        @Test
        @DisplayName("an unknown highlight falls back to the hex literal")
        void unknownHighlightFallsBackToHex() {
            assertThat(BmsAttributes.highlightMnemonic((byte) 0x33)).isEqualTo("X'33'");
        }

        @Test
        @DisplayName("the three mnemonic maps have their documented sizes and are immutable")
        void mnemonicMapsAreImmutableAndComplete() {
            assertThat(BmsAttributes.FIELD_ATTRIBUTE_MNEMONICS).hasSize(18);
            assertThat(BmsAttributes.COLOUR_MNEMONICS).hasSize(8);
            assertThat(BmsAttributes.HIGHLIGHT_MNEMONICS).hasSize(4);
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> BmsAttributes.FIELD_ATTRIBUTE_MNEMONICS.put((byte) 1, "x"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> BmsAttributes.COLOUR_MNEMONICS.put((byte) 1, "x"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> BmsAttributes.HIGHLIGHT_MNEMONICS.put((byte) 1, "x"));
        }

        @ParameterizedTest(name = "every field-attribute constant round-trips its own mnemonic")
        @ValueSource(bytes = {(byte) 0x40, (byte) 0xC1, (byte) 0xC8, (byte) 0xC9, (byte) 0x4C,
                (byte) 0x4D, (byte) 0x50, (byte) 0xD1, (byte) 0xD8, (byte) 0xD9, (byte) 0x5D,
                (byte) 0x60, (byte) 0x61, (byte) 0xE8, (byte) 0x6C, (byte) 0xF0, (byte) 0xF1,
                (byte) 0xF8})
        @DisplayName("each of the 18 mapped field attributes returns a DFH mnemonic, not hex")
        void everyMappedFieldAttributeReturnsAMnemonic(byte attribute) {
            assertThat(BmsAttributes.fieldAttributeMnemonic(attribute))
                    .startsWith("DFH")
                    .doesNotStartWith("X'");
        }
    }

    @Nested
    @DisplayName("Attribute bit decoding - each predicate driven from both sides")
    class BitDecoding {

        @Test
        @DisplayName("isProtected is true for the protected attributes and false for unprotected")
        void isProtected() {
            assertThat(BmsAttributes.isProtected(BmsAttributes.DFHBMPRO)).isTrue();
            assertThat(BmsAttributes.isProtected(BmsAttributes.DFHBMASK)).isTrue();
            assertThat(BmsAttributes.isProtected(BmsAttributes.DFHBMUNP)).isFalse();
            assertThat(BmsAttributes.isProtected(BmsAttributes.DFHBMFSE)).isFalse();
        }

        @Test
        @DisplayName("isNumeric is true for numeric-shift attributes and false otherwise")
        void isNumeric() {
            assertThat(BmsAttributes.isNumeric(BmsAttributes.DFHBMUNN)).isTrue();
            assertThat(BmsAttributes.isNumeric(BmsAttributes.DFHBMASK)).isTrue();
            assertThat(BmsAttributes.isNumeric(BmsAttributes.DFHBMUNP)).isFalse();
            assertThat(BmsAttributes.isNumeric(BmsAttributes.DFHBMPRO)).isFalse();
        }

        @Test
        @DisplayName("isAutoskip requires BOTH protected and numeric, so all four cases are driven")
        void isAutoskipRequiresBothBits() {
            // protected AND numeric -> autoskip
            assertThat(BmsAttributes.isAutoskip(BmsAttributes.DFHBMASK)).isTrue();
            // protected, not numeric
            assertThat(BmsAttributes.isAutoskip(BmsAttributes.DFHBMPRO)).isFalse();
            // numeric, not protected
            assertThat(BmsAttributes.isAutoskip(BmsAttributes.DFHBMUNN)).isFalse();
            // neither
            assertThat(BmsAttributes.isAutoskip(BmsAttributes.DFHBMUNP)).isFalse();
        }

        @Test
        @DisplayName("isIntensified is true only for the bright attributes")
        void isIntensified() {
            assertThat(BmsAttributes.isIntensified(BmsAttributes.DFHBMBRY)).isTrue();
            assertThat(BmsAttributes.isIntensified(BmsAttributes.DFHBMASB)).isTrue();
            assertThat(BmsAttributes.isIntensified(BmsAttributes.DFHBMUNP)).isFalse();
            assertThat(BmsAttributes.isIntensified(BmsAttributes.DFHBMDAR)).isFalse();
        }

        @Test
        @DisplayName("isNonDisplay is true only for the dark attributes")
        void isNonDisplay() {
            assertThat(BmsAttributes.isNonDisplay(BmsAttributes.DFHBMDAR)).isTrue();
            assertThat(BmsAttributes.isNonDisplay(BmsAttributes.DFHPROTN)).isTrue();
            assertThat(BmsAttributes.isNonDisplay(BmsAttributes.DFHBMUNP)).isFalse();
            assertThat(BmsAttributes.isNonDisplay(BmsAttributes.DFHBMBRY)).isFalse();
        }

        @Test
        @DisplayName("intensified and non-display are mutually exclusive, as the bit pair requires")
        void intensifiedAndNonDisplayAreMutuallyExclusive() {
            for (byte attribute : BmsAttributes.FIELD_ATTRIBUTE_MNEMONICS.keySet()) {
                assertThat(BmsAttributes.isIntensified(attribute)
                        && BmsAttributes.isNonDisplay(attribute))
                        .as("attribute %s", BmsAttributes.toHex(attribute))
                        .isFalse();
            }
        }

        @Test
        @DisplayName("isModifiedDataTagSet is true for the FSET attributes and false otherwise")
        void isModifiedDataTagSet() {
            assertThat(BmsAttributes.isModifiedDataTagSet(BmsAttributes.DFHBMFSE)).isTrue();
            assertThat(BmsAttributes.isModifiedDataTagSet(BmsAttributes.DFHBMASF)).isTrue();
            assertThat(BmsAttributes.isModifiedDataTagSet(BmsAttributes.DFHBMUNP)).isFalse();
            assertThat(BmsAttributes.isModifiedDataTagSet(BmsAttributes.DFHBMPRO)).isFalse();
        }

        @Test
        @DisplayName("DFHRED is the error-highlight colour CSSETATY moves onto a bad field")
        void dfhRedIsTheErrorColour() {
            // CSSETATY sets DFHRED plus '*' on the offending field in REENTER state, so this specific
            // constant carries behavioural weight across the online programs.
            assertThat(BmsAttributes.DFHRED).isEqualTo((byte) 0xF2);
            assertThat(BmsAttributes.colourMnemonic(BmsAttributes.DFHRED)).isEqualTo("DFHRED");
        }
    }

    @Nested
    @DisplayName("Class shape")
    class ClassShape {

        @Test
        @DisplayName("the holder is not instantiable, including reflectively")
        void notInstantiable() throws ReflectiveOperationException {
            Constructor<BmsAttributes> constructor = BmsAttributes.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            assertThatExceptionOfType(InvocationTargetException.class)
                    .isThrownBy(constructor::newInstance)
                    .withCauseInstanceOf(AssertionError.class);
        }
    }
}
