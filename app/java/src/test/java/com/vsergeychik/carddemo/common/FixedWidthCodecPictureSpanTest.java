package com.vsergeychik.carddemo.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link FixedWidthCodec} - the layer that gives a positioned byte span its
 * {@code PICTURE} meaning.
 *
 * <p>The three rules this class exists to get right, and that these tests pin down, are: an
 * alphanumeric {@code MOVE} truncates on the <em>right</em>; a numeric {@code MOVE} truncates on the
 * <em>left</em>; and a signed zoned field carries its sign as an overpunch in its trailing byte,
 * consuming no byte of its own. Each is asserted in both directions, because a helper that got the
 * direction wrong would still produce a plausible-looking record.
 *
 * <p>Every test names its charset explicitly. Nothing here consults a platform default, and the
 * EBCDIC cases exist precisely to prove that the pad bytes follow the code page rather than a
 * hard-coded {@code 0x20}.
 *
 * <p>{@code review_rules} returns exactly one line - "No user rules provided." - so no user rule
 * governs this file.
 */
@DisplayName("FixedWidthCodec - PICTURE semantics over a positioned byte span")
class FixedWidthCodecPictureSpanTest {

    /** The authoritative code page for this migration's fixtures. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** The EBCDIC code page of the binary reference datasets. */
    private static final Charset EBCDIC = Charset.forName("IBM037");

    /** The codec under test, over the ASCII code page. */
    private final FixedWidthCodec codec = new FixedWidthCodec(ASCII);

    /**
     * A three-span layout exercising all three {@link PictureKind}s that carry data, plus a
     * {@code FILLER}: {@code NAME X(5)}, {@code COUNT 9(3)}, {@code AMOUNT S9(3)V99} and a
     * two-byte {@code FILLER}.
     *
     * @return the layout, 15 bytes in total
     */
    private static RecordLayout mixedLayout() {
        return RecordLayout.of(15,
                FieldSpan.alphanumeric("NAME", 0, 5),
                FieldSpan.unsignedNumeric("COUNT", 5, 3),
                FieldSpan.signedScaled("AMOUNT", 8, 3, 2),
                FieldSpan.filler(13, 2));
    }

    @Nested
    @DisplayName("Construction names its charset and rejects a multi-byte one")
    class Construction {

        @Test
        @DisplayName("A single-byte charset is accepted and exposed")
        void aSingleByteCharsetIsAccepted() {
            assertThat(new FixedWidthCodec(ASCII).charset()).isSameAs(ASCII);
            assertThat(new FixedWidthCodec(EBCDIC).charset()).isEqualTo(EBCDIC);
        }

        @Test
        @DisplayName("A null charset is rejected: the code page is never derived from the platform")
        void aNullCharsetIsRejected() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new FixedWidthCodec(null));
        }

        @Test
        @DisplayName("A multi-byte charset is rejected: absolute offsets need one byte per character")
        void aMultiByteCharsetIsRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new FixedWidthCodec(StandardCharsets.UTF_16));
        }
    }

    @Nested
    @DisplayName("PIC X(n) - left justified, padded and truncated on the RIGHT")
    class Alphanumeric {

        @ParameterizedTest
        @CsvSource({
            "ABCD,   4, ABCD",
            "AB,     4, 'AB  '",
            "'',     4, '    '",
            "ABCDEF, 4, ABCD"
        })
        @DisplayName("An alphanumeric MOVE pads and truncates on the right")
        void anAlphanumericMovePadsAndTruncatesOnTheRight(final String source,
                                                          final int targetLength,
                                                          final String expected) {
            assertThat(this.outer().movePicX(source, targetLength)).isEqualTo(expected);
        }

        /**
         * The enclosing test's codec.
         *
         * @return the codec under test
         */
        private FixedWidthCodec outer() {
            return FixedWidthCodecPictureSpanTest.this.codec;
        }

        @Test
        @DisplayName("A null source and a non-positive width are rejected")
        void invalidArgumentsAreRejected() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> outer().movePicX(null, 4));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> outer().movePicX("A", 0));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> outer().movePicX("A", -1));
        }

        @Test
        @DisplayName("writePicX truncates rather than rejecting, and readPicX does not trim")
        void writePicXTruncatesAndReadPicXDoesNotTrim() {
            FieldSpan name = FieldSpan.alphanumeric("NAME", 0, 5);
            FixedWidthRecord record = new FixedWidthRecord(5, ASCII);

            outer().writePicX(record, name, "ABCDEFG");
            assertThat(outer().readPicX(record, name)).isEqualTo("ABCDE");

            outer().writePicX(record, name, "AB");
            assertThat(outer().readPicX(record, name)).isEqualTo("AB   ");
        }

        @Test
        @DisplayName("readPicXTrimmed removes trailing spaces only, never a leading one")
        void readPicXTrimmedRemovesTrailingSpacesOnly() {
            FieldSpan name = FieldSpan.alphanumeric("NAME", 0, 6);
            FixedWidthRecord record = new FixedWidthRecord(6, ASCII);

            outer().writePicX(record, name, " AB");
            assertThat(outer().readPicXTrimmed(record, name)).isEqualTo(" AB");

            outer().writePicX(record, name, "");
            assertThat(outer().readPicXTrimmed(record, name)).isEmpty();

            outer().writePicX(record, name, "ABCDEF");
            assertThat(outer().readPicXTrimmed(record, name)).isEqualTo("ABCDEF");
        }

        @Test
        @DisplayName("Null arguments to the span-level operations are rejected")
        void nullSpanArgumentsAreRejected() {
            FieldSpan name = FieldSpan.alphanumeric("NAME", 0, 5);
            FixedWidthRecord record = new FixedWidthRecord(5, ASCII);

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> outer().writePicX(null, name, "A"));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> outer().writePicX(record, null, "A"));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> outer().readPicX(null, name));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> outer().readPicX(record, null));
        }
    }

    @Nested
    @DisplayName("PIC 9(n) - right justified, zero padded and truncated on the LEFT")
    class UnsignedNumeric {

        @ParameterizedTest
        @CsvSource({
            "1234, 4, 1234",
            "12,   4, 0012",
            "9,    4, 0009",
            "56789, 4, 6789"
        })
        @DisplayName("A numeric MOVE pads and truncates on the left")
        void aNumericMovePadsAndTruncatesOnTheLeft(final String source, final int targetLength,
                                                   final String expected) {
            assertThat(FixedWidthCodecPictureSpanTest.this.codec.movePic9(source, targetLength))
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("A long source is zero padded to width")
        void aLongSourceIsZeroPadded() {
            assertThat(FixedWidthCodecPictureSpanTest.this.codec.movePic9(1L, 6)).isEqualTo("000001");
            assertThat(FixedWidthCodecPictureSpanTest.this.codec.movePic9(123456L, 6)).isEqualTo("123456");
            assertThat(FixedWidthCodecPictureSpanTest.this.codec.movePic9(1234567L, 6)).isEqualTo("234567");
        }

        @Test
        @DisplayName("A negative long is refused: PIC 9 has no sign position")
        void aNegativeLongIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec.movePic9(-1L, 6))
                    .withMessageContaining("unsigned PIC 9");
        }

        @Test
        @DisplayName("A non-digit source is refused, naming the offending character position")
        void aNonDigitSourceIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec.movePic9("12A4", 4))
                    .withMessageContaining("character 3");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec.movePic9("12 4", 4))
                    .withMessageContaining("character 3");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec.movePic9("-123", 4))
                    .withMessageContaining("character 1");
        }

        @Test
        @DisplayName("An empty source is refused: a zoned field holds at least one digit")
        void anEmptySourceIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec.movePic9("", 4))
                    .withMessageContaining("empty value");
        }

        @Test
        @DisplayName("A null source and a non-positive width are rejected")
        void invalidArgumentsAreRejected() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec.movePic9(null, 4));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec.movePic9("1", 0));
        }

        @Test
        @DisplayName("A span round-trips through writePic9 and readPic9")
        void aSpanRoundTrips() {
            FieldSpan count = FieldSpan.unsignedNumeric("COUNT", 0, 5);
            FixedWidthRecord record = new FixedWidthRecord(5, ASCII);

            FixedWidthCodecPictureSpanTest.this.codec.writePic9(record, count, 42L);
            assertThat(FixedWidthCodecPictureSpanTest.this.codec.readPic9(record, count)).isEqualTo(42L);
            assertThat(FixedWidthCodecPictureSpanTest.this.codec.readPic9AsInt(record, count)).isEqualTo(42);

            FixedWidthCodecPictureSpanTest.this.codec.writePic9(record, count, "00007");
            assertThat(FixedWidthCodecPictureSpanTest.this.codec.readPic9(record, count)).isEqualTo(7L);
        }

        @Test
        @DisplayName("decodePic9AsInt refuses a value above Integer.MAX_VALUE")
        void decodePic9AsIntRefusesAnOversizedValue() {
            assertThat(FixedWidthCodecPictureSpanTest.this.codec.decodePic9AsInt("0000002147483647"))
                    .isEqualTo(Integer.MAX_VALUE);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec
                            .decodePic9AsInt("0000002147483648"))
                    .withMessageContaining("Integer.MAX_VALUE");
        }

        @Test
        @DisplayName("decodePic9 refuses an image too wide for a long")
        void decodePic9RefusesAnImageTooWideForALong() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec
                            .decodePic9("9".repeat(20)))
                    .withMessageContaining("does not fit a long");
        }

        @Test
        @DisplayName("Null arguments to the span-level operations are rejected")
        void nullSpanArgumentsAreRejected() {
            FieldSpan count = FieldSpan.unsignedNumeric("COUNT", 0, 5);
            FixedWidthRecord record = new FixedWidthRecord(5, ASCII);

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec.writePic9(null, count, 1L));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec.writePic9(record, null, 1L));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec.writePic9(null, count, "1"));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec.writePic9(record, null, "1"));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec.readPic9(null, count));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec.readPic9(record, null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec.readPic9AsInt(null, count));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec.readPic9AsInt(record, null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec.decodePic9(null));
        }
    }

    @Nested
    @DisplayName("PIC S9(p)V(s) - the sign is an overpunch in the trailing byte")
    class SignedScaled {

        @ParameterizedTest
        @CsvSource({
            "0.00,     '00000{'",
            "1.00,     '00010{'",
            "1.05,     '00010E'",
            "-1.05,    '00010N'",
            "-0.01,    '00000J'",
            "999.99,   '09999I'",
            "-999.99,  '09999R'",
            // Exactly p + s significant digits, so no leading zero pad is added at all.
            "9999.99,  '99999I'",
            "-9999.99, '99999R'"
        })
        @DisplayName("Encoding places the sign as an overpunch and never adds a byte for it")
        void encodingOverpunchesTheTrailingByte(final String value, final String expected) {
            assertThat(FixedWidthCodecPictureSpanTest.this.codec
                    .encodeSignedScaled(new BigDecimal(value), 4, 2))
                    .isEqualTo(expected);
            assertThat(expected).hasSize(6);
        }

        @Test
        @DisplayName("Excess fractional digits are truncated, never rounded")
        void excessFractionalDigitsAreTruncated() {
            assertThat(FixedWidthCodecPictureSpanTest.this.codec
                    .encodeSignedScaled(new BigDecimal("1.999"), 4, 2)).isEqualTo("00019I");
            assertThat(FixedWidthCodecPictureSpanTest.this.codec
                    .encodeSignedScaled(new BigDecimal("-1.999"), 4, 2)).isEqualTo("00019R");
        }

        @Test
        @DisplayName("Invalid digit counts and a null value are rejected")
        void invalidDigitCountsAreRejected() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec
                            .encodeSignedScaled(null, 4, 2));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec
                            .encodeSignedScaled(BigDecimal.ONE, 0, 2))
                    .withMessageContaining("p of at least 1");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec
                            .encodeSignedScaled(BigDecimal.ONE, 4, -1))
                    .withMessageContaining("must not be negative");
        }

        @ParameterizedTest
        @CsvSource({
            "'00000{', 2, 0.00",
            "'00010{', 2, 1.00",
            "'00010E', 2, 1.05",
            "'00010N', 2, -1.05",
            "'000005', 2, 0.05",
            "'99999R', 2, -9999.99"
        })
        @DisplayName("Decoding reads the overpunch back, sign and low-order digit together")
        void decodingReadsTheOverpunchBack(final String image, final int scale,
                                           final String expected) {
            assertThat(FixedWidthCodecPictureSpanTest.this.codec.decodeSignedScaled(image, scale))
                    .isEqualByComparingTo(new BigDecimal(expected));
        }

        @Test
        @DisplayName("A single-character image is legal: it carries the digit and the sign")
        void aSingleCharacterImageIsLegal() {
            assertThat(FixedWidthCodecPictureSpanTest.this.codec.decodeSignedScaled("{", 0))
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(FixedWidthCodecPictureSpanTest.this.codec.decodeSignedScaled("J", 0))
                    .isEqualByComparingTo(new BigDecimal("-1"));
            assertThat(FixedWidthCodecPictureSpanTest.this.codec.decodeSignedScaled("5", 0))
                    .isEqualByComparingTo(new BigDecimal("5"));
        }

        @Test
        @DisplayName("Every positive and negative overpunch character decodes to its digit")
        void everyOverpunchCharacterDecodes() {
            String positive = "{ABCDEFGHI";
            String negative = "}JKLMNOPQR";
            for (int digit = 0; digit <= 9; digit++) {
                assertThat(FixedWidthCodecPictureSpanTest.this.codec
                        .decodeSignedScaled(String.valueOf(positive.charAt(digit)), 0))
                        .isEqualByComparingTo(BigDecimal.valueOf(digit));
                assertThat(FixedWidthCodecPictureSpanTest.this.codec
                        .decodeSignedScaled(String.valueOf(negative.charAt(digit)), 0))
                        .isEqualByComparingTo(BigDecimal.valueOf(-digit));
            }
        }

        @Test
        @DisplayName("A null, empty, negatively scaled or over-scaled image is rejected")
        void invalidImagesAreRejected() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec.decodeSignedScaled(null, 2));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec.decodeSignedScaled("", 2))
                    .withMessageContaining("at least one character");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec
                            .decodeSignedScaled("0000{", -1))
                    .withMessageContaining("is negative");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec
                            .decodeSignedScaled("0000{", 9))
                    .withMessageContaining("exceeds the 5-character image");
        }

        @Test
        @DisplayName("A non-digit leading character is rejected")
        void aNonDigitLeadingCharacterIsRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec
                            .decodeSignedScaled("0A00{", 2))
                    .withMessageContaining("character 2");
        }

        @Test
        @DisplayName("A trailing character that is neither a digit nor an overpunch is rejected")
        void anInvalidTrailingCharacterIsRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec
                            .decodeSignedScaled("0000*", 2))
                    .withMessageContaining("neither a digit nor a sign overpunch");
        }

        @Test
        @DisplayName("A signed span round-trips, occupying exactly p + s bytes")
        void aSignedSpanRoundTrips() {
            FieldSpan amount = FieldSpan.signedScaled("AMOUNT", 0, 10, 2);
            assertThat(amount.length()).isEqualTo(12);
            FixedWidthRecord record = new FixedWidthRecord(12, ASCII);

            FixedWidthCodecPictureSpanTest.this.codec.writeMonetary(record, amount,
                    new BigDecimal("-1234.56"));
            assertThat(FixedWidthCodecPictureSpanTest.this.codec.readMonetary(record, amount))
                    .isEqualByComparingTo(new BigDecimal("-1234.56"));

            FixedWidthCodecPictureSpanTest.this.codec.writeSignedScaled(record, amount,
                    new BigDecimal("7.00"), 2);
            assertThat(FixedWidthCodecPictureSpanTest.this.codec.readSignedScaled(record, amount, 2))
                    .isEqualByComparingTo(new BigDecimal("7.00"));
        }

        @Test
        @DisplayName("A scale that leaves no integer digit position is rejected")
        void anImpossibleScaleIsRejected() {
            FieldSpan amount = FieldSpan.signedScaled("AMOUNT", 0, 2, 2);
            FixedWidthRecord record = new FixedWidthRecord(4, ASCII);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec
                            .readSignedScaled(record, amount, 4))
                    .withMessageContaining("integer digit position");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec
                            .readSignedScaled(record, amount, -1))
                    .withMessageContaining("is negative");
        }

        @Test
        @DisplayName("Null arguments to the span-level operations are rejected")
        void nullSpanArgumentsAreRejected() {
            FieldSpan amount = FieldSpan.signedScaled("AMOUNT", 0, 3, 2);
            FixedWidthRecord record = new FixedWidthRecord(5, ASCII);

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec
                            .writeSignedScaled(null, amount, BigDecimal.ONE, 2));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec
                            .writeSignedScaled(record, null, BigDecimal.ONE, 2));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec
                            .readSignedScaled(null, amount, 2));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec
                            .readSignedScaled(record, null, 2));
        }
    }

    @Nested
    @DisplayName("A declared VALUE is emitted; otherwise the kind's pad byte is")
    class DeclaredValues {

        @Test
        @DisplayName("A FILLER carrying a literal emits the literal, not spaces")
        void aFillerWithALiteralEmitsIt() {
            FieldSpan separator = FieldSpan.filler(0, 1, "/");
            FixedWidthRecord record = new FixedWidthRecord(1, ASCII);

            FixedWidthCodecPictureSpanTest.this.codec.writeDeclaredValue(record, separator);

            assertThat(record.readSpan(separator)).isEqualTo("/");
        }

        @Test
        @DisplayName("A span with no literal gets its kind's pad byte")
        void aSpanWithNoLiteralGetsItsPadByte() {
            FixedWidthRecord record = new FixedWidthRecord(6, ASCII);
            FieldSpan text = FieldSpan.alphanumeric("TEXT", 0, 3);
            FieldSpan digits = FieldSpan.unsignedNumeric("DIGITS", 3, 3);
            record.fill(0, 6, (byte) 'Z');

            FixedWidthCodecPictureSpanTest.this.codec.writeDeclaredValue(record, text);
            FixedWidthCodecPictureSpanTest.this.codec.writeDeclaredValue(record, digits);

            assertThat(record.readSpan(text)).isEqualTo("   ");
            assertThat(record.readSpan(digits)).isEqualTo("000");
        }

        @Test
        @DisplayName("Null arguments are rejected")
        void nullArgumentsAreRejected() {
            FieldSpan text = FieldSpan.alphanumeric("TEXT", 0, 3);
            FixedWidthRecord record = new FixedWidthRecord(3, ASCII);

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec
                            .writeDeclaredValue(null, text));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec
                            .writeDeclaredValue(record, null));
        }
    }

    @Nested
    @DisplayName("Padding a short row - the cardxref 36-to-50 case (gate G16)")
    class Padding {

        @Test
        @DisplayName("A short byte row is widened with the code page's own space byte")
        void aShortByteRowIsWidened() {
            byte[] row = "AB".getBytes(ASCII);

            assertThat(FixedWidthCodecPictureSpanTest.this.codec.padToDeclaredWidth(row, 5))
                    .isEqualTo("AB   ".getBytes(ASCII));
            assertThat(new FixedWidthCodec(EBCDIC)
                    .padToDeclaredWidth("AB".getBytes(EBCDIC), 5))
                    .isEqualTo("AB   ".getBytes(EBCDIC));
        }

        @Test
        @DisplayName("An exactly-wide byte row is returned unchanged")
        void anExactlyWideByteRowIsUnchanged() {
            byte[] row = "ABCDE".getBytes(ASCII);

            assertThat(FixedWidthCodecPictureSpanTest.this.codec.padToDeclaredWidth(row, 5))
                    .isEqualTo(row);
        }

        @Test
        @DisplayName("An over-long byte row is refused: widening never truncates")
        void anOverLongByteRowIsRefused() {
            byte[] row = "ABCDEF".getBytes(ASCII);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec.padToDeclaredWidth(row, 5))
                    .withMessageContaining("never truncates");
        }

        @Test
        @DisplayName("The character form behaves identically")
        void theCharacterFormBehavesIdentically() {
            assertThat(FixedWidthCodecPictureSpanTest.this.codec.padToDeclaredWidth("AB", 5))
                    .isEqualTo("AB   ");
            assertThat(FixedWidthCodecPictureSpanTest.this.codec.padToDeclaredWidth("ABCDE", 5))
                    .isEqualTo("ABCDE");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec
                            .padToDeclaredWidth("ABCDEF", 5))
                    .withMessageContaining("never truncates");
        }

        @ParameterizedTest
        @ValueSource(ints = {0, -1})
        @DisplayName("A non-positive declared width is rejected in both forms")
        void aNonPositiveWidthIsRejected(final int width) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec
                            .padToDeclaredWidth(new byte[0], width))
                    .withMessageContaining("at least one character position");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec
                            .padToDeclaredWidth("", width));
        }

        @Test
        @DisplayName("Null rows are rejected")
        void nullRowsAreRejected() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec
                            .padToDeclaredWidth((byte[]) null, 5));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec
                            .padToDeclaredWidth((String) null, 5));
        }
    }

    @Nested
    @DisplayName("STRING ... DELIMITED BY SIZE")
    class StringDelimitedBySize {

        @Test
        @DisplayName("Every operand contributes its full declared width, padding included")
        void everyOperandContributesItsFullWidth() {
            assertThat(FixedWidthCodecPictureSpanTest.this.codec
                    .concatenateDelimitedBySize("2022071800", "000001"))
                    .isEqualTo("2022071800000001");
            assertThat(FixedWidthCodecPictureSpanTest.this.codec.concatenateDelimitedBySize("A  ", "B"))
                    .isEqualTo("A  B");
            assertThat(FixedWidthCodecPictureSpanTest.this.codec.concatenateDelimitedBySize("only"))
                    .isEqualTo("only");
        }

        @Test
        @DisplayName("No operand, a null array and a null operand are all rejected")
        void invalidOperandsAreRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec.concatenateDelimitedBySize())
                    .withMessageContaining("at least one");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec
                            .concatenateDelimitedBySize((String[]) null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec
                            .concatenateDelimitedBySize("A", null))
                    .withMessageContaining("Sending item 2 of 2");
        }

        @Test
        @DisplayName("INTO overlays at the span start and leaves the remainder untouched")
        void intoOverlaysAndLeavesTheRemainder() {
            FieldSpan description = FieldSpan.alphanumeric("DESC", 0, 10);
            FixedWidthRecord record = new FixedWidthRecord(10, ASCII);
            record.fill(0, 10, (byte) 'Z');

            FixedWidthCodecPictureSpanTest.this.codec.stringIntoDelimitedBySize(record, description,
                    "Int. ", "a/c");

            assertThat(record.readSpan(description)).isEqualTo("Int. a/cZZ");
        }

        @Test
        @DisplayName("An over-wide concatenation stops at the span's last character")
        void anOverWideConcatenationStopsAtTheSpanEnd() {
            FieldSpan narrow = FieldSpan.alphanumeric("NARROW", 0, 4);
            FixedWidthRecord record = new FixedWidthRecord(4, ASCII);

            FixedWidthCodecPictureSpanTest.this.codec.stringIntoDelimitedBySize(record, narrow, "ABCDEFGH");

            assertThat(record.readSpan(narrow)).isEqualTo("ABCD");
        }

        @Test
        @DisplayName("An entirely empty transfer writes nothing at all")
        void anEmptyTransferWritesNothing() {
            FieldSpan span = FieldSpan.alphanumeric("SPAN", 0, 4);
            FixedWidthRecord record = new FixedWidthRecord(4, ASCII);
            record.fill(0, 4, (byte) 'Z');

            FixedWidthCodecPictureSpanTest.this.codec.stringIntoDelimitedBySize(record, span, "");

            assertThat(record.readSpan(span)).isEqualTo("ZZZZ");
        }

        @Test
        @DisplayName("Null record and field arguments are rejected")
        void nullArgumentsAreRejected() {
            FieldSpan span = FieldSpan.alphanumeric("SPAN", 0, 4);
            FixedWidthRecord record = new FixedWidthRecord(4, ASCII);

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec
                            .stringIntoDelimitedBySize(null, span, "A"));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec
                            .stringIntoDelimitedBySize(record, null, "A"));
        }
    }

    @Nested
    @DisplayName("Whole-record serialise, rewrite and deserialise")
    class WholeRecord {

        @Test
        @DisplayName("newRecord initialises every span from the layout, FILLER included")
        void newRecordInitialisesEverySpan() {
            FixedWidthRecord record =
                    FixedWidthCodecPictureSpanTest.this.codec.newRecord(mixedLayout());

            assertThat(record.recordLength()).isEqualTo(15);
            assertThat(new String(record.toByteArray(), ASCII))
                    .isEqualTo("     " + "000" + "00000" + "  ");
        }

        @Test
        @DisplayName("A record serialises with the move rule of each span's kind")
        void aRecordSerialisesWithEachKindsMoveRule() {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("NAME", "AB");
            values.put("COUNT", "7");
            values.put("AMOUNT", "12345");

            byte[] record =
                    FixedWidthCodecPictureSpanTest.this.codec.serialise(mixedLayout(), values);

            assertThat(record).hasSize(15);
            assertThat(new String(record, ASCII)).isEqualTo("AB   " + "007" + "12345" + "  ");
        }

        @Test
        @DisplayName("A partially populated record keeps its initialised spans")
        void aPartiallyPopulatedRecordKeepsItsInitialisedSpans() {
            byte[] record = FixedWidthCodecPictureSpanTest.this.codec.serialise(mixedLayout(),
                    Map.of("NAME", "XY"));

            assertThat(new String(record, ASCII)).isEqualTo("XY   " + "000" + "00000" + "  ");
        }

        @Test
        @DisplayName("A rewrite overlays named spans onto existing bytes")
        void aRewriteOverlaysNamedSpans() {
            byte[] existing = FixedWidthCodecPictureSpanTest.this.codec.serialise(mixedLayout(),
                    Map.of("NAME", "OLD", "COUNT", "999"));

            byte[] rewritten = FixedWidthCodecPictureSpanTest.this.codec.serialise(mixedLayout(),
                    Map.of("NAME", "NEW"), existing);

            assertThat(new String(rewritten, ASCII))
                    .isEqualTo("NEW  " + "999" + "00000" + "  ");
        }

        @Test
        @DisplayName("Deserialise returns every named span, untrimmed, and omits FILLER")
        void deserialiseReturnsEveryNamedSpan() {
            byte[] record = FixedWidthCodecPictureSpanTest.this.codec.serialise(mixedLayout(),
                    Map.of("NAME", "AB", "COUNT", "7", "AMOUNT", "1234{"));

            Map<String, String> images =
                    FixedWidthCodecPictureSpanTest.this.codec.deserialise(mixedLayout(), record);

            assertThat(images).containsOnlyKeys("NAME", "COUNT", "AMOUNT");
            assertThat(images.get("NAME")).isEqualTo("AB   ");
            assertThat(images.get("COUNT")).isEqualTo("007");
            assertThat(images.get("AMOUNT")).isEqualTo("1234{");
        }

        @Test
        @DisplayName("An unknown field name is rejected rather than silently ignored")
        void anUnknownFieldNameIsRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec.serialise(mixedLayout(),
                            Map.of("NOSUCHFIELD", "X")));
        }

        @Test
        @DisplayName("A null map, key or image is rejected")
        void nullMapEntriesAreRejected() {
            Map<String, String> withNullImage = new LinkedHashMap<>();
            withNullImage.put("NAME", null);
            Map<String, String> withNullKey = new LinkedHashMap<>();
            withNullKey.put(null, "X");

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec
                            .serialise(mixedLayout(), null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec
                            .serialise(mixedLayout(), withNullImage))
                    .withMessageContaining("null image");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec
                            .serialise(mixedLayout(), withNullKey));
        }

        @Test
        @DisplayName("A null layout is rejected by every whole-record operation")
        void aNullLayoutIsRejected() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec
                            .serialise(null, Map.of()));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec
                            .serialise(null, Map.of(), new byte[15]));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec
                            .deserialise(null, new byte[15]));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec.newRecord(null));
        }
    }

    @Nested
    @DisplayName("Wrapping stored bytes")
    class Wrapping {

        @Test
        @DisplayName("Bytes of exactly the declared width wrap")
        void bytesOfTheDeclaredWidthWrap() {
            byte[] stored = FixedWidthCodecPictureSpanTest.this.codec.serialise(mixedLayout(), Map.of());

            FixedWidthRecord record =
                    FixedWidthCodecPictureSpanTest.this.codec.wrap(stored, mixedLayout());

            assertThat(record.recordLength()).isEqualTo(15);
            assertThat(record.toByteArray()).isEqualTo(stored);
        }

        @Test
        @DisplayName("A short row is refused, with the pad helper named in the diagnostic")
        void aShortRowIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec
                            .wrap(new byte[14], mixedLayout()))
                    .withMessageContaining("padToDeclaredWidth");
        }

        @Test
        @DisplayName("A long row is refused too")
        void aLongRowIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec
                            .wrap(new byte[16], mixedLayout()))
                    .withMessageContaining("must match its declared width exactly");
        }

        @Test
        @DisplayName("Null arguments are rejected")
        void nullArgumentsAreRejected() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec
                            .wrap(null, mixedLayout()));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec
                            .wrap(new byte[15], null));
        }
    }

    @Nested
    @DisplayName("A signed image supplied as text is padded and validated, never truncated")
    class SignedImagePadding {

        @Test
        @DisplayName("A short signed image is zero padded on the left")
        void aShortSignedImageIsZeroPadded() {
            byte[] record = FixedWidthCodecPictureSpanTest.this.codec.serialise(mixedLayout(),
                    Map.of("AMOUNT", "5{"));

            assertThat(new String(record, ASCII).substring(8, 13)).isEqualTo("0005{");
        }

        @Test
        @DisplayName("An exactly-wide signed image is used as it stands")
        void anExactlyWideSignedImageIsUsedAsIsStands() {
            byte[] record = FixedWidthCodecPictureSpanTest.this.codec.serialise(mixedLayout(),
                    Map.of("AMOUNT", "1234R"));

            assertThat(new String(record, ASCII).substring(8, 13)).isEqualTo("1234R");
        }

        @Test
        @DisplayName("An over-wide signed image is refused rather than truncated")
        void anOverWideSignedImageIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec.serialise(mixedLayout(),
                            Map.of("AMOUNT", "1234567{")))
                    .withMessageContaining("encodeSignedScaled");
        }

        @Test
        @DisplayName("An empty signed image is refused")
        void anEmptySignedImageIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec.serialise(mixedLayout(),
                            Map.of("AMOUNT", "")))
                    .withMessageContaining("empty image");
        }

        @Test
        @DisplayName("A malformed signed image is refused by the validating decode")
        void aMalformedSignedImageIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> FixedWidthCodecPictureSpanTest.this.codec.serialise(mixedLayout(),
                            Map.of("AMOUNT", "12*4{")));
        }
    }

    @Nested
    @DisplayName("The charset is honoured, not assumed")
    class CharsetIsHonoured {

        @Test
        @DisplayName("The same layout yields different pad bytes under the two code pages")
        void theSameLayoutYieldsDifferentPadBytes() {
            byte[] ascii = new FixedWidthCodec(ASCII).serialise(mixedLayout(), Map.of());
            byte[] ebcdic = new FixedWidthCodec(EBCDIC).serialise(mixedLayout(), Map.of());

            assertThat(ascii[0]).isEqualTo((byte) 0x20);
            assertThat(ebcdic[0]).isEqualTo((byte) 0x40);
            assertThat(ascii[5]).isEqualTo((byte) 0x30);
            assertThat(ebcdic[5]).isEqualTo((byte) 0xF0);
        }

        @Test
        @DisplayName("A record built under one code page round-trips under the same one")
        void aRecordRoundTripsUnderItsOwnCodePage() {
            FixedWidthCodec ebcdicCodec = new FixedWidthCodec(EBCDIC);
            byte[] record = ebcdicCodec.serialise(mixedLayout(), Map.of("NAME", "AB"));

            assertThat(ebcdicCodec.deserialise(mixedLayout(), record).get("NAME"))
                    .isEqualTo("AB   ");
        }
    }
}
