package com.vsergeychik.carddemo.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link BmsAttributes}, the Java reproduction of the IBM-supplied {@code DFHBMSCA} and
 * {@code DFHATTR} copybooks, neither of which is present in this repository.
 */
@DisplayName("BmsAttributes - DFHBMSCA and DFHATTR reproduced as EBCDIC bytes")
class BmsAttributesSemanticsTest {
    @Nested
    @DisplayName("Byte values - EBCDIC, never Unicode char literals")
    class ByteValues {
        @Test
        @DisplayName("the extended colour plane carries the architected 3270 colour codes")
        void theColourPlaneCarriesTheArchitectedCodes() {
            assertThat(BmsAttributes.DFHDFCOL).isEqualTo((byte) 0x00);
            assertThat(BmsAttributes.DFHBLUE).isEqualTo((byte) 0xF1);
            assertThat(BmsAttributes.DFHRED).isEqualTo((byte) 0xF2);
            assertThat(BmsAttributes.DFHPINK).isEqualTo((byte) 0xF3);
            assertThat(BmsAttributes.DFHGREEN).isEqualTo((byte) 0xF4);
            assertThat(BmsAttributes.DFHTURQ).isEqualTo((byte) 0xF5);
            assertThat(BmsAttributes.DFHYELLO).isEqualTo((byte) 0xF6);
            assertThat(BmsAttributes.DFHNEUTR).isEqualTo((byte) 0xF7);
        }

        @Test
        @DisplayName("DFHRED is the EBCDIC 0xF2 and NOT the Unicode digit two")
        void redIsEbcdicNotUnicode() {
            assertThat(BmsAttributes.DFHRED).isNotEqualTo((byte) '2');
            assertThat(BmsAttributes.unsigned(BmsAttributes.DFHRED)).isEqualTo(0xF2).isEqualTo(242);
        }

        @Test
        @DisplayName("the extended highlighting plane carries blink, reverse and underscore")
        void theHighlightPlaneCarriesItsCodes() {
            assertThat(BmsAttributes.DFHDFHI).isEqualTo((byte) 0x00);
            assertThat(BmsAttributes.DFHBLINK).isEqualTo((byte) 0xF1);
            assertThat(BmsAttributes.DFHREVRS).isEqualTo((byte) 0xF2);
            assertThat(BmsAttributes.DFHUNDLN).isEqualTo((byte) 0xF4);
        }

        @Test
        @DisplayName("the planes deliberately share byte values, so uniqueness holds only within one")
        void thePlanesOverlapByDesign() {
            assertThat(BmsAttributes.DFHBMASF)
                    .isEqualTo(BmsAttributes.DFHBLUE)
                    .isEqualTo(BmsAttributes.DFHBLINK)
                    .isEqualTo((byte) 0xF1);

            assertThat(BmsAttributes.COLOUR_MNEMONICS.values())
                    .doesNotHaveDuplicates();
            assertThat(BmsAttributes.HIGHLIGHT_MNEMONICS.values())
                    .doesNotHaveDuplicates();
            assertThat(BmsAttributes.FIELD_ATTRIBUTE_MNEMONICS.values())
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("unsigned() gives a 0-255 view of bytes that read back negative")
        void unsignedWidensCorrectly() {
            assertThat(BmsAttributes.DFHBMASB).isNegative();
            assertThat(BmsAttributes.unsigned(BmsAttributes.DFHBMASB)).isEqualTo(0xF8);
            assertThat(BmsAttributes.unsigned(BmsAttributes.DFHBMUNP)).isEqualTo(0x40);
            assertThat(BmsAttributes.unsigned((byte) 0x00)).isZero();
        }

        @Test
        @DisplayName("toHex() renders the COBOL X'nn' form, zero-padded")
        void toHexRendersTheCobolForm() {
            assertThat(BmsAttributes.toHex(BmsAttributes.DFHRED)).isEqualTo("X'F2'");
            assertThat(BmsAttributes.toHex(BmsAttributes.DFHBMUNP)).isEqualTo("X'40'");
            assertThat(BmsAttributes.toHex((byte) 0x00)).isEqualTo("X'00'");
            assertThat(BmsAttributes.toHex((byte) 0x0A)).isEqualTo("X'0A'");
        }
    }

    @Nested
    @DisplayName("Mnemonic lookups - a hit names the constant, a miss falls back to hex")
    class MnemonicLookups {
        @Test
        @DisplayName("a known byte resolves to its DFH mnemonic in each plane")
        void knownBytesResolveToTheirMnemonics() {
            assertThat(BmsAttributes.fieldAttributeMnemonic(BmsAttributes.DFHBMUNP))
                    .isEqualTo("DFHBMUNP");
            assertThat(BmsAttributes.colourMnemonic(BmsAttributes.DFHRED)).isEqualTo("DFHRED");
            assertThat(BmsAttributes.highlightMnemonic(BmsAttributes.DFHBLINK))
                    .isEqualTo("DFHBLINK");
        }

        @Test
        @DisplayName("an unknown byte falls back to the hex rendering rather than to null")
        void unknownBytesFallBackToHex() {
            byte unmapped = (byte) 0x7B;

            assertThat(BmsAttributes.fieldAttributeMnemonic(unmapped)).isEqualTo("X'7B'");
            assertThat(BmsAttributes.colourMnemonic(unmapped)).isEqualTo("X'7B'");
            assertThat(BmsAttributes.highlightMnemonic(unmapped)).isEqualTo("X'7B'");
        }

        @Test
        @DisplayName("a byte mapped in one plane is not borrowed by another")
        void planesDoNotBorrowEachOthersNames() {
            assertThat(BmsAttributes.colourMnemonic(BmsAttributes.DFHRED)).isEqualTo("DFHRED");
            assertThat(BmsAttributes.highlightMnemonic(BmsAttributes.DFHRED)).isEqualTo("DFHREVRS");
            assertThat(BmsAttributes.fieldAttributeMnemonic(BmsAttributes.DFHRED))
                    .isEqualTo("X'F2'");
        }
    }

    @Nested
    @DisplayName("Basic field-attribute bit tests")
    class BitTests {
        @Test
        @DisplayName("isProtected() reads bit 0x20")
        void isProtectedReadsItsBit() {
            assertThat(BmsAttributes.isProtected(BmsAttributes.DFHBMPRO)).isTrue();
            assertThat(BmsAttributes.isProtected(BmsAttributes.DFHBMASK)).isTrue();
            assertThat(BmsAttributes.isProtected(BmsAttributes.DFHBMUNP)).isFalse();
        }

        @Test
        @DisplayName("isNumeric() reads bit 0x10")
        void isNumericReadsItsBit() {
            assertThat(BmsAttributes.isNumeric(BmsAttributes.DFHBMUNN)).isTrue();
            assertThat(BmsAttributes.isNumeric(BmsAttributes.DFHBMASK)).isTrue();
            assertThat(BmsAttributes.isNumeric(BmsAttributes.DFHBMPRO)).isFalse();
        }

        @Test
        @DisplayName("isAutoskip() requires both bits, and each half can veto alone")
        void isAutoskipRequiresBothBits() {
            assertThat(BmsAttributes.isAutoskip(BmsAttributes.DFHBMASK)).isTrue();
            assertThat(BmsAttributes.isAutoskip(BmsAttributes.DFHBMPRO)).isFalse();
            assertThat(BmsAttributes.isAutoskip(BmsAttributes.DFHBMUNN)).isFalse();
            assertThat(BmsAttributes.isAutoskip(BmsAttributes.DFHBMUNP)).isFalse();
        }

        @Test
        @DisplayName("isIntensified() matches display bits 0x08 exactly, not merely overlapping")
        void isIntensifiedMatchesItsDisplayBits() {
            assertThat(BmsAttributes.isIntensified(BmsAttributes.DFHBMASB)).isTrue();
            assertThat(BmsAttributes.isIntensified(BmsAttributes.DFHBMBRY)).isTrue();
            assertThat(BmsAttributes.isIntensified(BmsAttributes.DFHBMUNP)).isFalse();
            assertThat(BmsAttributes.isIntensified(BmsAttributes.DFHBMDAR)).isFalse();
        }

        @Test
        @DisplayName("isNonDisplay() matches display bits 0x0C exactly")
        void isNonDisplayMatchesItsDisplayBits() {
            assertThat(BmsAttributes.isNonDisplay(BmsAttributes.DFHBMDAR)).isTrue();
            assertThat(BmsAttributes.isNonDisplay(BmsAttributes.DFHUNNOD)).isTrue();
            assertThat(BmsAttributes.isNonDisplay(BmsAttributes.DFHBMUNP)).isFalse();
            assertThat(BmsAttributes.isNonDisplay(BmsAttributes.DFHBMASB)).isFalse();
        }

        @Test
        @DisplayName("isModifiedDataTagSet() reads bit 0x01")
        void isModifiedDataTagSetReadsItsBit() {
            assertThat(BmsAttributes.isModifiedDataTagSet(BmsAttributes.DFHBMASF)).isTrue();
            assertThat(BmsAttributes.isModifiedDataTagSet(BmsAttributes.DFHUNNOD)).isTrue();
            assertThat(BmsAttributes.isModifiedDataTagSet(BmsAttributes.DFHBMASK)).isFalse();
            assertThat(BmsAttributes.isModifiedDataTagSet(BmsAttributes.DFHBMUNP)).isFalse();
        }
    }

    @Nested
    @DisplayName("Shape - a constant holder with no state and no instances")
    class Shape {
        @Test
        @DisplayName("every declared field is static and final")
        void everyFieldIsStaticAndFinal() {
            for (Field field : BmsAttributes.class.getDeclaredFields()) {
                assertThat(Modifier.isStatic(field.getModifiers()))
                        .as("non-static field: %s", field.getName()).isTrue();
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("non-final field: %s", field.getName()).isTrue();
            }
        }

        @Test
        @DisplayName("the mnemonic maps are unmodifiable")
        void theMnemonicMapsAreUnmodifiable() {
            assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(
                    () -> BmsAttributes.COLOUR_MNEMONICS.put((byte) 0x7B, "NOPE"));
            assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(
                    () -> BmsAttributes.HIGHLIGHT_MNEMONICS.clear());
            assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(
                    () -> BmsAttributes.FIELD_ATTRIBUTE_MNEMONICS.remove(BmsAttributes.DFHBMUNP));
        }

        @Test
        @DisplayName("it refuses instantiation, including reflectively")
        void itRefusesInstantiation() throws ReflectiveOperationException {
            assertThat(Modifier.isFinal(BmsAttributes.class.getModifiers())).isTrue();

            Constructor<BmsAttributes> constructor =
                    BmsAttributes.class.getDeclaredConstructor();
            assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue();
            constructor.setAccessible(true);

            assertThatExceptionOfType(InvocationTargetException.class)
                    .isThrownBy(constructor::newInstance)
                    .withCauseInstanceOf(AssertionError.class);
        }
    }
}
