package com.vsergeychik.carddemo.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

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
 * Tests for {@link FixedWidthCodec}, the module's single implementation of COBOL {@code PICTURE}
 * semantics over a fixed-width record area.
 *
 * <h2>Why this class earns the most thorough test in the package</h2>
 * It is the one place a cross-width {@code MOVE} is implemented and the one place a zoned
 * {@code DISPLAY} field becomes a decimal value. The legacy estate contains <strong>2,795
 * {@code MOVE} statements</strong> against 94 named arithmetic statements, so {@code MOVE} - not
 * arithmetic - is the dominant source of silent divergence in this migration, and every one of those
 * moves routes through here. A defect in this class is invisible at each of its thousands of call
 * sites and shows up only as a byte difference in a serialised record.
 *
 * <p>Three asymmetries carry essentially all of the risk, and each is asserted from both directions
 * rather than only in its happy case:
 * <ul>
 *   <li><strong>Direction of truncation.</strong> {@code PIC X} pads and truncates on the
 *       <em>right</em>; {@code PIC 9} pads and truncates on the <em>left</em>. Java's assignment
 *       operator does neither, so getting one backwards is both easy and undetectable.</li>
 *   <li><strong>The sign occupies no character position.</strong> {@code PIC S9(p)V(s)} is exactly
 *       {@code p + s} wide with the sign overpunched into the trailing byte. Reserving a byte for it
 *       shifts every subsequent field.</li>
 *   <li><strong>A {@code FILLER} emits its declared {@code VALUE}, and a pad only otherwise.</strong>
 *       Blanket space-filling would blank every date and time separator in the system.</li>
 * </ul>
 *
 * <h2>How the expectations were derived</h2>
 * The COBOL cannot be executed here, so nothing was captured from a running program. The overpunch
 * expectations are the byte images the class's own documentation records as measured from
 * {@code app/data/ASCII/acctdata.txt} and {@code dailytran.txt}; the width and truncation
 * expectations come from the copybooks and from cited program lines. Those sources are a read-only
 * parity oracle - this test opens no file and writes nowhere.
 */
@DisplayName("FixedWidthCodec - COBOL PICTURE semantics over a fixed-width area")
class FixedWidthCodecMoveAsymmetryTest {

    private static final Charset ASCII = StandardCharsets.US_ASCII;
    private static final Charset EBCDIC = Charset.forName("IBM037");

    private static final FixedWidthCodec CODEC = new FixedWidthCodec(ASCII);

    /**
     * A three-field layout wide enough to exercise a character span, an unsigned numeric span and a
     * signed scaled span side by side: {@code PIC X(4)} then {@code PIC 9(4)} then
     * {@code PIC S9(3)V99}, which is 5 characters and not 6 because the sign takes no position.
     */
    private static final FieldSpan TEXT = FieldSpan.alphanumeric("TEXT", 0, 4);
    private static final FieldSpan NUMBER = FieldSpan.unsignedNumeric("NUMBER", 4, 4);
    private static final FieldSpan AMOUNT = FieldSpan.signedScaled("AMOUNT", 8, 3, 2);
    private static final RecordLayout LAYOUT = RecordLayout.of(13, TEXT, NUMBER, AMOUNT);

    private static FixedWidthRecord newRecord() {
        return CODEC.newRecord(LAYOUT);
    }

    // =================================================================================================

    @Nested
    @DisplayName("Construction - the charset is mandatory and must be single-byte for the repertoire")
    class Construction {

        @Test
        @DisplayName("both mainframe code pages are accepted")
        void bothCodePagesAccepted() {
            assertThat(new FixedWidthCodec(ASCII).charset()).isEqualTo(ASCII);
            assertThat(new FixedWidthCodec(EBCDIC).charset()).isEqualTo(EBCDIC);
        }

        @Test
        @DisplayName("a null charset is rejected: a code page is never derived from the platform")
        void nullCharsetRejected() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new FixedWidthCodec(null))
                    .withMessageContaining("charset is required");
        }

        @ParameterizedTest(name = "{0} is rejected because a digit does not encode to one byte")
        @ValueSource(strings = {"UTF-16", "UTF-16BE", "UTF-16LE", "UTF-32"})
        @DisplayName("a multi-byte charset is rejected, because n digits must occupy n bytes")
        void multiByteCharsetRejected(String charsetName) {
            Charset multiByte = Charset.forName(charsetName);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new FixedWidthCodec(multiByte))
                    .withMessageContaining("byte(s)")
                    .withMessageContaining("exactly one byte");
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Rule 1a - PIC X pads and truncates on the RIGHT")
    class Alphanumeric {

        @ParameterizedTest(name = "movePicX({0}, {1}) is {2}")
        @CsvSource({
            "ABCD,4,ABCD",
            "AB,4,'AB  '",
            "ABCDEF,4,ABCD",
            "'',4,'    '",
            "A,1,A",
        })
        @DisplayName("short values pad right with spaces; long values lose their RIGHTMOST characters")
        void movePicX(String source, int width, String expected) {
            assertThat(CODEC.movePicX(source, width)).isEqualTo(expected).hasSize(width);
        }

        @Test
        @DisplayName("the classic error is excluded: ABCDEF into PIC X(4) is ABCD, never CDEF")
        void notTheLeftTruncation() {
            assertThat(CODEC.movePicX("ABCDEF", 4)).isEqualTo("ABCD").isNotEqualTo("CDEF");
        }

        @Test
        @DisplayName("a null source and a non-positive width are both rejected")
        void guards() {
            assertThatNullPointerException()
                    .isThrownBy(() -> CODEC.movePicX(null, 4))
                    .withMessageContaining("sending value is required");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CODEC.movePicX("A", 0))
                    .withMessageContaining("at least one character position");
        }

        @Test
        @DisplayName("write then read returns the padded image untrimmed")
        void writeThenReadUntrimmed() {
            FixedWidthRecord record = newRecord();

            CODEC.writePicX(record, TEXT, "AB");

            assertThat(CODEC.readPicX(record, TEXT)).isEqualTo("AB  ").hasSize(4);
            assertThat(CODEC.readPicXTrimmed(record, TEXT)).isEqualTo("AB");
        }

        @ParameterizedTest(name = "readPicXTrimmed of {0} is {1}")
        @CsvSource({
            "ABCD,ABCD",
            "'AB  ',AB",
            "'A   ',A",
            "'    ',''",
            "'  CD','  CD'",
        })
        @DisplayName("trimming removes trailing spaces only, never leading ones")
        void trimmingIsTrailingOnly(String stored, String expected) {
            FixedWidthRecord record = newRecord();
            record.writeSpan(TEXT, stored);

            assertThat(CODEC.readPicXTrimmed(record, TEXT)).isEqualTo(expected);
        }

        @Test
        @DisplayName("writePicX and readPicX reject a null record or descriptor")
        void spanGuards() {
            FixedWidthRecord record = newRecord();

            assertThatNullPointerException().isThrownBy(() -> CODEC.writePicX(null, TEXT, "A"));
            assertThatNullPointerException().isThrownBy(() -> CODEC.writePicX(record, null, "A"));
            assertThatNullPointerException().isThrownBy(() -> CODEC.readPicX(null, TEXT));
            assertThatNullPointerException().isThrownBy(() -> CODEC.readPicX(record, null));
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Rule 1b - PIC 9 pads and truncates on the LEFT")
    class UnsignedNumeric {

        @ParameterizedTest(name = "movePic9({0}, {1}) is {2}")
        @CsvSource({
            "'05',4,0005",
            "1234,4,1234",
            "123456,4,3456",
            "7,4,0007",
            "9,1,9",
        })
        @DisplayName("short values zero-fill left; long values lose their HIGH-order digits")
        void movePic9FromString(String source, int width, String expected) {
            assertThat(CODEC.movePic9(source, width)).isEqualTo(expected).hasSize(width);
        }

        @Test
        @DisplayName("MOVE '05' TO TRAN-CAT-CD gives 0005, the direction proven by CBACT04C:483")
        void theCitedCase() {
            // app/cbl/CBACT04C.cbl:483 moves the literal '05' into a PIC 9(04) receiver declared at
            // app/cpy/CVTRA05Y.cpy:7. Right-padding would give 0500 - the classic error.
            assertThat(CODEC.movePic9("05", 4)).isEqualTo("0005").isNotEqualTo("0500");
            assertThat(CODEC.movePic9("123456", 4)).isEqualTo("3456").isNotEqualTo("1234");
        }

        @ParameterizedTest(name = "movePic9({0}L, {1}) is {2}")
        @CsvSource({
            "0,4,0000",
            "7,4,0007",
            "1234,4,1234",
            "99999,4,9999",
        })
        @DisplayName("the integral overload applies the same rule")
        void movePic9FromLong(long source, int width, String expected) {
            assertThat(CODEC.movePic9(source, width)).isEqualTo(expected);
        }

        @Test
        @DisplayName("a negative value has no unsigned representation and is rejected")
        void negativeRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CODEC.movePic9(-1L, 4))
                    .withMessageContaining("PIC 9 has no sign position");
        }

        @Test
        @DisplayName("an empty or non-digit source is rejected rather than read as zero")
        void nonDigitRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CODEC.movePic9("", 4))
                    .withMessageContaining("empty value");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CODEC.movePic9("12A4", 4))
                    .withMessageContaining("character 3 is 'A'");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CODEC.movePic9("12 4", 4))
                    .withMessageContaining("only the digits 0 to 9");
            assertThatNullPointerException().isThrownBy(() -> CODEC.movePic9(null, 4));
            assertThatIllegalArgumentException().isThrownBy(() -> CODEC.movePic9("1", 0));
        }

        @Test
        @DisplayName("decode returns the value the digits denote, as long and as int")
        void decode() {
            assertThat(CODEC.decodePic9("0005")).isEqualTo(5L);
            assertThat(CODEC.decodePic9AsInt("0005")).isEqualTo(5);
            assertThat(CODEC.decodePic9AsInt("2147483647")).isEqualTo(Integer.MAX_VALUE);
            assertThat(CODEC.decodePic9("000000000000000001")).isEqualTo(1L);
        }

        @Test
        @DisplayName("a value beyond int is rejected by the int overload but not by the long one")
        void intOverflowRejected() {
            assertThat(CODEC.decodePic9("2147483648")).isEqualTo(2147483648L);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CODEC.decodePic9AsInt("2147483648"))
                    .withMessageContaining("exceeds Integer.MAX_VALUE");
        }

        @Test
        @DisplayName("a value beyond long is rejected rather than silently wrapped")
        void longOverflowRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CODEC.decodePic9("9".repeat(20)));
            assertThatNullPointerException().isThrownBy(() -> CODEC.decodePic9(null));
        }

        @Test
        @DisplayName("write and read a numeric span, from a value and from a digit string")
        void writeAndReadSpans() {
            FixedWidthRecord record = newRecord();

            CODEC.writePic9(record, NUMBER, 42L);
            assertThat(record.readSpan(NUMBER)).isEqualTo("0042");
            assertThat(CODEC.readPic9(record, NUMBER)).isEqualTo(42L);
            assertThat(CODEC.readPic9AsInt(record, NUMBER)).isEqualTo(42);

            CODEC.writePic9(record, NUMBER, "07");
            assertThat(record.readSpan(NUMBER)).isEqualTo("0007");

            assertThatNullPointerException().isThrownBy(() -> CODEC.writePic9(null, NUMBER, 1L));
            assertThatNullPointerException().isThrownBy(() -> CODEC.writePic9(record, null, 1L));
            assertThatNullPointerException().isThrownBy(() -> CODEC.writePic9(null, NUMBER, "1"));
            assertThatNullPointerException().isThrownBy(() -> CODEC.writePic9(record, null, "1"));
            assertThatNullPointerException().isThrownBy(() -> CODEC.readPic9(null, NUMBER));
            assertThatNullPointerException().isThrownBy(() -> CODEC.readPic9(record, null));
            assertThatNullPointerException().isThrownBy(() -> CODEC.readPic9AsInt(null, NUMBER));
            assertThatNullPointerException().isThrownBy(() -> CODEC.readPic9AsInt(record, null));
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Rule 2 - the sign is overpunched into the trailing byte and takes no position")
    class SignedScaled {

        @ParameterizedTest(name = "{0} as S9({1})V({2}) encodes to {3}")
        @CsvSource({
            // The three images the class documents as measured from the ASCII fixtures.
            "194.00,10,2,00000001940{",
            "504.77,9,2,0000005047G",
            "-919.00,9,2,'0000009190}'",
            // Positive and negative zero, and both alphabets at the trailing digit position. Note
            // every image below is exactly p + s wide: the overpunch REPLACES the trailing digit
            // rather than being appended to it, which is the whole point of Rule 2. Expecting a
            // p + s + 1 image here is the error this parameter list exists to exclude.
            "0.00,3,2,'0000{'",
            "-0.00,3,2,'0000{'",
            "0.01,3,2,0000A",
            "-0.01,3,2,0000J",
            "0.09,3,2,0000I",
            "-0.09,3,2,0000R",
            "1.23,3,2,0012C",
            "-1.23,3,2,0012L",
            "999.99,3,2,9999I",
            "-999.99,3,2,9999R",
        })
        @DisplayName("the width is p + s and the trailing character carries digit and sign together")
        void encode(String value, int integerDigits, int fractionDigits, String expected) {
            String image = CODEC.encodeSignedScaled(new BigDecimal(value), integerDigits,
                    fractionDigits);

            assertThat(image).isEqualTo(expected).hasSize(integerDigits + fractionDigits);
        }

        @Test
        @DisplayName("the three measured fixture images encode and decode exactly")
        void measuredFixtureImages() {
            // The class's own documentation records these as measured from app/data/ASCII.
            assertThat(CODEC.encodeSignedScaled(new BigDecimal("194.00"), 10, 2))
                    .isEqualTo("00000001940{");
            assertThat(CODEC.encodeSignedScaled(new BigDecimal("504.77"), 9, 2))
                    .isEqualTo("0000005047G");
            assertThat(CODEC.encodeSignedScaled(new BigDecimal("-919.00"), 9, 2))
                    .isEqualTo("0000009190}");

            assertThat(CODEC.decodeSignedScaled("00000001940{", 2))
                    .isEqualByComparingTo(new BigDecimal("194.00"));
            assertThat(CODEC.decodeSignedScaled("0000005047G", 2))
                    .isEqualByComparingTo(new BigDecimal("504.77"));
            assertThat(CODEC.decodeSignedScaled("0000009190}", 2))
                    .isEqualByComparingTo(new BigDecimal("-919.00"));
        }

        @ParameterizedTest(name = "the positive overpunch for trailing digit {0} is {1}")
        @CsvSource({"0,{", "1,A", "2,B", "3,C", "4,D", "5,E", "6,F", "7,G", "8,H", "9,I"})
        @DisplayName("the complete positive overpunch alphabet, zone C")
        void positiveAlphabet(int digit, char expected) {
            String image = CODEC.encodeSignedScaled(new BigDecimal("0.0" + digit), 3, 2);

            assertThat(image).endsWith(String.valueOf(expected));
            assertThat(CODEC.decodeSignedScaled(image, 2))
                    .isEqualByComparingTo(new BigDecimal("0.0" + digit));
        }

        @ParameterizedTest(name = "the negative overpunch for trailing digit {0} is {1}")
        @CsvSource({"1,J", "2,K", "3,L", "4,M", "5,N", "6,O", "7,P", "8,Q", "9,R"})
        @DisplayName("the complete negative overpunch alphabet, zone D")
        void negativeAlphabet(int digit, char expected) {
            String image = CODEC.encodeSignedScaled(new BigDecimal("-0.0" + digit), 3, 2);

            assertThat(image).endsWith(String.valueOf(expected));
            assertThat(CODEC.decodeSignedScaled(image, 2))
                    .isEqualByComparingTo(new BigDecimal("-0.0" + digit));
        }

        @Test
        @DisplayName("a trailing plain digit is the unsigned zone F form and reads as positive")
        void zoneFReadsPositive() {
            // The zone-F twin of "0000009190}" must be the SAME width - 11 characters for
            // S9(09)V99 - because the overpunch replaced a digit rather than adding a position.
            assertThat(CODEC.decodeSignedScaled("00000091900", 2))
                    .isEqualByComparingTo(new BigDecimal("919.00"));
            assertThat(CODEC.decodeSignedScaled("0000009190", 2))
                    .isEqualByComparingTo(new BigDecimal("91.90"));
            assertThat(CODEC.decodeSignedScaled("5", 0)).isEqualByComparingTo(new BigDecimal("5"));
        }

        @Test
        @DisplayName("a decoded value reports exactly its declared scale")
        void declaredScaleIsExact() {
            assertThat(CODEC.decodeSignedScaled("00000{", 2).scale()).isEqualTo(2);
            assertThat(CODEC.decodeSignedScaled("00000{", 0).scale()).isZero();
            assertThat(CODEC.decodeSignedScaled("000123C", 4).scale()).isEqualTo(4);
        }

        @Test
        @DisplayName("an excess fraction is truncated toward zero, never rounded")
        void truncatesNeverRounds() {
            // ROUNDED appears zero times in all 28 programs, so 1.999 into V99 is 1.99, not 2.00.
            assertThat(CODEC.encodeSignedScaled(new BigDecimal("1.999"), 3, 2)).isEqualTo("0019I");
            assertThat(CODEC.encodeSignedScaled(new BigDecimal("-1.999"), 3, 2)).isEqualTo("0019R");
            // Rounding would carry into the next cent and give 0020{ / 0020} instead.
            assertThat(CODEC.encodeSignedScaled(new BigDecimal("1.999"), 3, 2)).isNotEqualTo("0020{");
        }

        @Test
        @DisplayName("an over-wide integer part loses its HIGH-order digits, without an exception")
        void integerOverflowTruncates() {
            // COBOL reports this loss only under ON SIZE ERROR, and no program in the estate uses it.
            assertThat(CODEC.encodeSignedScaled(new BigDecimal("1234.56"), 3, 2)).isEqualTo("2345F");
            // The high-order 1 is lost, not the low-order digits: 234.56, never 123.45.
            assertThat(CODEC.decodeSignedScaled(
                    CODEC.encodeSignedScaled(new BigDecimal("1234.56"), 3, 2), 2))
                    .isEqualByComparingTo(new BigDecimal("234.56"));
        }

        @Test
        @DisplayName("encode rejects a null value, p below 1 and a negative s")
        void encodeGuards() {
            assertThatNullPointerException()
                    .isThrownBy(() -> CODEC.encodeSignedScaled(null, 3, 2))
                    .withMessageContaining("value is required");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CODEC.encodeSignedScaled(BigDecimal.ZERO, 0, 2))
                    .withMessageContaining("requires p of at least 1");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CODEC.encodeSignedScaled(BigDecimal.ZERO, 3, -1))
                    .withMessageContaining("s must not be negative");
        }

        @Test
        @DisplayName("decode rejects null, empty, a negative scale and a scale wider than the image")
        void decodeGuards() {
            assertThatNullPointerException()
                    .isThrownBy(() -> CODEC.decodeSignedScaled(null, 2))
                    .withMessageContaining("image is required");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CODEC.decodeSignedScaled("", 2))
                    .withMessageContaining("at least one character");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CODEC.decodeSignedScaled("00123C", -1))
                    .withMessageContaining("is negative");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CODEC.decodeSignedScaled("123C", 5))
                    .withMessageContaining("exceeds the 4-character image");
        }

        @Test
        @DisplayName("decode rejects a non-digit in the leading digits and an unknown trailing byte")
        void decodeRejectsMalformed() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CODEC.decodeSignedScaled("00A23C", 2))
                    .withMessageContaining("signed zoned field");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CODEC.decodeSignedScaled("00123%", 2))
                    .withMessageContaining("neither a digit nor a sign overpunch");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CODEC.decodeSignedScaled("     ", 2))
                    .withMessageContaining("signed zoned field");
        }

        @Test
        @DisplayName("a signed span is p + s wide, with no byte reserved for the sign")
        void spanWidthHasNoSignByte() {
            assertThat(AMOUNT.length()).isEqualTo(5);
            assertThat(LAYOUT.recordLength()).isEqualTo(13);
        }

        @Test
        @DisplayName("write and read a signed span, at an explicit scale and at the monetary scale")
        void writeAndReadSignedSpans() {
            FixedWidthRecord record = newRecord();

            CODEC.writeSignedScaled(record, AMOUNT, new BigDecimal("-12.34"), 2);
            assertThat(record.readSpan(AMOUNT)).isEqualTo("0123M");
            assertThat(CODEC.readSignedScaled(record, AMOUNT, 2))
                    .isEqualByComparingTo(new BigDecimal("-12.34"));

            CODEC.writeMonetary(record, AMOUNT, new BigDecimal("56.78"));
            assertThat(CODEC.readMonetary(record, AMOUNT))
                    .isEqualByComparingTo(new BigDecimal("56.78"));

            assertThatNullPointerException()
                    .isThrownBy(() -> CODEC.writeSignedScaled(null, AMOUNT, BigDecimal.ZERO, 2));
            assertThatNullPointerException()
                    .isThrownBy(() -> CODEC.writeSignedScaled(record, null, BigDecimal.ZERO, 2));
            assertThatNullPointerException()
                    .isThrownBy(() -> CODEC.readSignedScaled(null, AMOUNT, 2));
            assertThatNullPointerException()
                    .isThrownBy(() -> CODEC.readSignedScaled(record, null, 2));
        }

        @Test
        @DisplayName("a scale that leaves no integer position is rejected against the descriptor")
        void scaleMustLeaveAnIntegerPosition() {
            FixedWidthRecord record = newRecord();

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CODEC.writeSignedScaled(record, AMOUNT, BigDecimal.ONE, 5))
                    .withMessageContaining("leaves 0 integer digit position(s)");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CODEC.readSignedScaled(record, AMOUNT, 6))
                    .withMessageContaining("integer digit position(s)");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CODEC.writeSignedScaled(record, AMOUNT, BigDecimal.ONE, -1))
                    .withMessageContaining("is negative");
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Rule 5 - a FILLER emits its declared VALUE, and a pad only otherwise")
    class DeclaredValues {

        @Test
        @DisplayName("a FILLER carrying a literal emits the literal - the CSDAT01Y separator case")
        void literalFillerEmitsItsLiteral() {
            FieldSpan slash = FieldSpan.filler(0, 1, "/");
            FixedWidthRecord record = new FixedWidthRecord(1, ASCII);

            CODEC.writeDeclaredValue(record, slash);

            assertThat(record.readSpan(slash)).isEqualTo("/");
        }

        @Test
        @DisplayName("a FILLER carrying no literal emits its kind's pad byte")
        void plainFillerEmitsThePad() {
            FieldSpan reserved = FieldSpan.filler(0, 3);
            FixedWidthRecord record = new FixedWidthRecord(3, ASCII);
            record.writeSpan(reserved, "XYZ");

            CODEC.writeDeclaredValue(record, reserved);

            assertThat(record.readSpan(reserved)).isEqualTo("   ");
        }

        @Test
        @DisplayName("a numeric span carrying no literal initialises to zeros, not spaces")
        void numericPadIsZero() {
            FieldSpan digits = FieldSpan.unsignedNumeric("DIGITS", 0, 4);
            FixedWidthRecord record = new FixedWidthRecord(4, ASCII);

            CODEC.writeDeclaredValue(record, digits);

            assertThat(record.readSpan(digits)).isEqualTo("0000");
        }

        @Test
        @DisplayName("writeDeclaredValue rejects a null record or descriptor")
        void guards() {
            FixedWidthRecord record = new FixedWidthRecord(1, ASCII);

            assertThatNullPointerException()
                    .isThrownBy(() -> CODEC.writeDeclaredValue(null, FieldSpan.filler(0, 1, "/")));
            assertThatNullPointerException()
                    .isThrownBy(() -> CODEC.writeDeclaredValue(record, null));
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Rule 6 - widening a short row to its declared width, and never truncating")
    class PadToDeclaredWidth {

        @Test
        @DisplayName("a short byte row is right-padded with spaces - the cardxref 36-to-50 case")
        void shortByteRowIsWidened() {
            byte[] short36 = "X".repeat(36).getBytes(ASCII);

            byte[] widened = CODEC.padToDeclaredWidth(short36, 50);

            assertThat(widened).hasSize(50);
            assertThat(new String(widened, ASCII)).isEqualTo("X".repeat(36) + " ".repeat(14));
        }

        @Test
        @DisplayName("a row already at its declared width is returned unchanged")
        void exactRowUnchanged() {
            byte[] exact = "Y".repeat(50).getBytes(ASCII);

            assertThat(CODEC.padToDeclaredWidth(exact, 50)).hasSize(50)
                    .containsExactly(exact);
            assertThat(CODEC.padToDeclaredWidth("Y".repeat(50), 50)).isEqualTo("Y".repeat(50));
        }

        @Test
        @DisplayName("a short string row is right-padded - the USRSEC 57-to-80 case")
        void shortStringRowIsWidened() {
            assertThat(CODEC.padToDeclaredWidth("Z".repeat(57), 80))
                    .hasSize(80)
                    .isEqualTo("Z".repeat(57) + " ".repeat(23));
        }

        @Test
        @DisplayName("an over-long row is REJECTED, not truncated, in both overloads")
        void overLongRowRejected() {
            byte[] tooWide = "A".repeat(51).getBytes(ASCII);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CODEC.padToDeclaredWidth(tooWide, 50))
                    .withMessageContaining("only widens a short row and never truncates");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CODEC.padToDeclaredWidth("A".repeat(51), 50))
                    .withMessageContaining("never truncates");
        }

        @Test
        @DisplayName("a null row and a non-positive width are rejected in both overloads")
        void guards() {
            assertThatNullPointerException()
                    .isThrownBy(() -> CODEC.padToDeclaredWidth((byte[]) null, 50));
            assertThatNullPointerException()
                    .isThrownBy(() -> CODEC.padToDeclaredWidth((String) null, 50));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CODEC.padToDeclaredWidth(new byte[1], 0));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CODEC.padToDeclaredWidth("A", 0));
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("STRING ... DELIMITED BY SIZE - every operand contributes its full width")
    class StringDelimitedBySize {

        @Test
        @DisplayName("operands are concatenated at their full declared widths, in order")
        void concatenates() {
            assertThat(CODEC.concatenateDelimitedBySize("2022071800", "000001"))
                    .isEqualTo("2022071800000001");
            assertThat(CODEC.concatenateDelimitedBySize("A")).isEqualTo("A");
            assertThat(CODEC.concatenateDelimitedBySize("A", "", "B")).isEqualTo("AB");
        }

        @Test
        @DisplayName("no operand, a null array and a null operand are all rejected")
        void guards() {
            assertThatIllegalArgumentException()
                    .isThrownBy(CODEC::concatenateDelimitedBySize)
                    .withMessageContaining("at least one sending item");
            assertThatNullPointerException()
                    .isThrownBy(() -> CODEC.concatenateDelimitedBySize((String[]) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> CODEC.concatenateDelimitedBySize("A", null))
                    .withMessageContaining("Sending item 2 of 2");
        }

        @Test
        @DisplayName("STRING ... INTO fills from the left and leaves the remainder untouched")
        void stringIntoFillsFromTheLeft() {
            FixedWidthRecord record = newRecord();
            record.writeSpan(TEXT, "....");

            CODEC.stringIntoDelimitedBySize(record, TEXT, "AB");

            // COBOL's STRING leaves the receiving positions beyond the transferred data as they were.
            assertThat(record.readSpan(TEXT)).isEqualTo("AB..");
        }

        @Test
        @DisplayName("a concatenation wider than the receiver transfers only what fits")
        void overflowTransfersWhatFits() {
            FixedWidthRecord record = newRecord();

            CODEC.stringIntoDelimitedBySize(record, TEXT, "ABCDEFGH");

            assertThat(record.readSpan(TEXT)).isEqualTo("ABCD");
        }

        @Test
        @DisplayName("an empty concatenation transfers nothing and leaves the span as it was")
        void emptyTransfersNothing() {
            FixedWidthRecord record = newRecord();
            record.writeSpan(TEXT, "WXYZ");

            CODEC.stringIntoDelimitedBySize(record, TEXT, "");

            assertThat(record.readSpan(TEXT)).isEqualTo("WXYZ");
        }

        @Test
        @DisplayName("STRING ... INTO rejects a null record or receiving descriptor")
        void intoGuards() {
            FixedWidthRecord record = newRecord();

            assertThatNullPointerException()
                    .isThrownBy(() -> CODEC.stringIntoDelimitedBySize(null, TEXT, "A"));
            assertThatNullPointerException()
                    .isThrownBy(() -> CODEC.stringIntoDelimitedBySize(record, null, "A"));
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Whole-record serialise and deserialise")
    class WholeRecord {

        private Map<String, String> images() {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("TEXT", "AB");
            values.put("NUMBER", "7");
            values.put("AMOUNT", CODEC.encodeSignedScaled(new BigDecimal("-12.34"), 3, 2));
            return values;
        }

        @Test
        @DisplayName("each image is written with the move rule for its span's kind")
        void kindAwareWriting() {
            byte[] record = CODEC.serialise(LAYOUT, images());

            assertThat(record).hasSize(13);
            // TEXT pads right, NUMBER pads left, AMOUNT keeps its overpunch untouched.
            assertThat(new String(record, ASCII)).isEqualTo("AB  " + "0007" + "0123M");
        }

        @Test
        @DisplayName("a name absent from the map keeps its initialised content")
        void absentNamesKeepTheirInitialisedContent() {
            byte[] record = CODEC.serialise(LAYOUT, new LinkedHashMap<>());

            assertThat(new String(record, ASCII)).isEqualTo("    " + "0000" + "00000");
        }

        @Test
        @DisplayName("a short signed image is zero-filled on the left, preserving its overpunch")
        void shortSignedImageIsPadded() {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("AMOUNT", "1A");

            byte[] record = CODEC.serialise(LAYOUT, values);

            assertThat(new String(record, ASCII).substring(8)).isEqualTo("0001A");
        }

        @Test
        @DisplayName("an over-wide signed image is rejected rather than truncated")
        void overWideSignedImageRejected() {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("AMOUNT", "0000123C");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CODEC.serialise(LAYOUT, values))
                    .withMessageContaining("rather than truncating an image");
        }

        @Test
        @DisplayName("an empty signed image is rejected")
        void emptySignedImageRejected() {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("AMOUNT", "");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CODEC.serialise(LAYOUT, values))
                    .withMessageContaining("empty image cannot be stored");
        }

        @Test
        @DisplayName("a malformed signed image is rejected before it reaches the record")
        void malformedSignedImageRejected() {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("AMOUNT", "0012%");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CODEC.serialise(LAYOUT, values))
                    .withMessageContaining("neither a digit nor a sign overpunch");
        }

        @Test
        @DisplayName("an unknown field name is rejected rather than ignored")
        void unknownNameRejected() {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("NO-SUCH-FIELD", "X");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CODEC.serialise(LAYOUT, values))
                    .withMessageContaining("declares no field named 'NO-SUCH-FIELD'");
        }

        @Test
        @DisplayName("serialise rejects a null layout, a null map and a null image")
        void serialiseGuards() {
            Map<String, String> nullImage = new LinkedHashMap<>();
            nullImage.put("TEXT", null);

            assertThatNullPointerException().isThrownBy(() -> CODEC.serialise(null, images()));
            assertThatNullPointerException().isThrownBy(() -> CODEC.serialise(LAYOUT, null));
            assertThatNullPointerException()
                    .isThrownBy(() -> CODEC.serialise(LAYOUT, nullImage))
                    .withMessageContaining("has a null image");
        }

        @Test
        @DisplayName("the rewrite overload carries untouched bytes across, FILLER included")
        void rewritePreservesUntouchedBytes() {
            // A tcatbal-shaped case: a reserved span holding ZEROS rather than the spaces a rebuild
            // from the layout would emit. Rewriting must carry those bytes across unchanged.
            FieldSpan value = FieldSpan.unsignedNumeric("VALUE", 0, 2);
            FieldSpan reserved = FieldSpan.filler(2, 3);
            RecordLayout layout = RecordLayout.of(5, value, reserved);
            byte[] stored = "42000".getBytes(ASCII);

            Map<String, String> update = new LinkedHashMap<>();
            update.put("VALUE", "7");

            assertThat(new String(CODEC.serialise(layout, update, stored), ASCII)).isEqualTo("07000");
            // Rebuilding from the layout instead would silently replace those zeros with spaces.
            assertThat(new String(CODEC.serialise(layout, update), ASCII)).isEqualTo("07   ");
        }

        @Test
        @DisplayName("a round trip through deserialise then the rewrite overload is byte-identical")
        void roundTripIsByteIdentical() {
            byte[] original = CODEC.serialise(LAYOUT, images());

            Map<String, String> read = CODEC.deserialise(LAYOUT, original);

            assertThat(CODEC.serialise(LAYOUT, read, original)).containsExactly(original);
            assertThat(read).containsExactly(Map.entry("TEXT", "AB  "),
                    Map.entry("NUMBER", "0007"),
                    Map.entry("AMOUNT", "0123M"));
        }

        @Test
        @DisplayName("deserialise omits FILLER but includes REDEFINES overlays")
        void deserialiseOmitsFillerAndKeepsOverlays() {
            FieldSpan text = FieldSpan.alphanumeric("TEXT", 0, 4);
            RecordLayout withBoth = RecordLayout.of(5, text, FieldSpan.filler(4, 1, "/"),
                    text.redefinedAs("TEXT-N", PictureKind.UNSIGNED_NUMERIC));

            Map<String, String> images = CODEC.deserialise(withBoth, "1234/".getBytes(ASCII));

            assertThat(images).doesNotContainKey("FILLER");
            assertThat(images).containsExactly(Map.entry("TEXT", "1234"),
                    Map.entry("TEXT-N", "1234"));
        }

        @Test
        @DisplayName("a byte count that differs from the declared record length is rejected")
        void wrongWidthRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CODEC.deserialise(LAYOUT, new byte[12]))
                    .withMessageContaining("must match its declared width exactly");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CODEC.wrap(new byte[14], LAYOUT))
                    .withMessageContaining("padToDeclaredWidth");
        }

        @Test
        @DisplayName("newRecord, wrap and deserialise reject their null arguments")
        void guards() {
            assertThatNullPointerException().isThrownBy(() -> CODEC.newRecord(null));
            assertThatNullPointerException().isThrownBy(() -> CODEC.wrap(null, LAYOUT));
            assertThatNullPointerException().isThrownBy(() -> CODEC.wrap(new byte[13], null));
            assertThatNullPointerException().isThrownBy(() -> CODEC.deserialise(null, new byte[13]));
            assertThatNullPointerException().isThrownBy(() -> CODEC.deserialise(LAYOUT, null));
        }

        @Test
        @DisplayName("wrap copies its bytes, so a later change to the caller's array is not seen")
        void wrapIsDefensive() {
            byte[] stored = CODEC.serialise(LAYOUT, images());
            FixedWidthRecord wrapped = CODEC.wrap(stored, LAYOUT);

            stored[0] = (byte) 'Z';

            assertThat(wrapped.readSpan(TEXT)).isEqualTo("AB  ");
        }

        @Test
        @DisplayName("a record initialised by the codec is in the layout's declared initial state")
        void newRecordIsInitialised() {
            assertThat(new String(newRecord().toByteArray(), ASCII)).isEqualTo("    000000000");
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Code page independence of the character layer")
    class CodePages {

        @Test
        @DisplayName("the same values give the same characters in both code pages, different bytes")
        void sameCharactersDifferentBytes() {
            FixedWidthCodec ebcdic = new FixedWidthCodec(EBCDIC);
            Map<String, String> values = new LinkedHashMap<>();
            values.put("TEXT", "AB");
            values.put("NUMBER", "7");
            values.put("AMOUNT", "0123M");

            byte[] asciiBytes = CODEC.serialise(LAYOUT, values);
            byte[] ebcdicBytes = ebcdic.serialise(LAYOUT, values);

            assertThat(ebcdicBytes).hasSameSizeAs(asciiBytes).isNotEqualTo(asciiBytes);
            assertThat(new String(ebcdicBytes, EBCDIC)).isEqualTo(new String(asciiBytes, ASCII));
            assertThat(ebcdic.deserialise(LAYOUT, ebcdicBytes))
                    .isEqualTo(CODEC.deserialise(LAYOUT, asciiBytes));
        }

        @Test
        @DisplayName("the overpunch alphabet is the same characters in both code pages")
        void overpunchIsCodePageIndependent() {
            FixedWidthCodec ebcdic = new FixedWidthCodec(EBCDIC);

            assertThat(ebcdic.encodeSignedScaled(new BigDecimal("-919.00"), 9, 2))
                    .isEqualTo(CODEC.encodeSignedScaled(new BigDecimal("-919.00"), 9, 2))
                    .isEqualTo("0000009190}");
        }
    }
}
