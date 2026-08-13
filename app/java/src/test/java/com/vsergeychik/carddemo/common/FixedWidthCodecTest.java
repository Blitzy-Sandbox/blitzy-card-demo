package com.vsergeychik.carddemo.common;

import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link FixedWidthCodec}, the layer that puts COBOL {@code PICTURE} semantics on a
 * fixed-width byte span and the module's only implementation of a cross-width {@code MOVE}.
 */
@DisplayName("FixedWidthCodec - COBOL PICTURE semantics, and the only cross-width MOVE in the module")
class FixedWidthCodecTest {
    private static final Charset ASCII = Charset.forName("US-ASCII");

    private static final Charset EBCDIC = Charset.forName("IBM037");

    private static final String FIRST_ACCOUNT_BALANCE_IMAGE = "00000001940{";

    private static final BigDecimal FIRST_ACCOUNT_BALANCE = new BigDecimal("194.00");

    private static final String CARD_XREF_FIXTURE_ROW = "050002445376574000000005000000000050";

    private static final int CARD_XREF_DECLARED_WIDTH = 50;

    private static final int CARD_XREF_FIXTURE_WIDTH = 36;

    private static final String DISCLOSURE_GROUP_FIXTURE_ROW =
            "A00000000001000100150{0000000000000000000000000000";

    private static final String TRAN_CAT_BAL_FIXTURE_ROW =
            "000000000010100010000000000{0000000000000000000000";

    private static final int TRAN_CAT_KEY_WIDTH = 17;

    private static final int DIS_GROUP_KEY_WIDTH = 16;

    private final FixedWidthCodec codec = new FixedWidthCodec(ASCII);

    private final FixedWidthCodec ebcdicCodec = new FixedWidthCodec(EBCDIC);

    private static String padRight(String value, int width) {
        if (value.length() > width) {
            throw new IllegalArgumentException("Transcription error: '" + value + "' is "
                    + value.length() + " characters, wider than the declared " + width);
        }
        return value + " ".repeat(width - value.length());
    }

    private static String runOf(char character, int count) {
        return String.valueOf(character).repeat(count);
    }

    private static int countDifferences(byte[] left, byte[] right) {
        if (left.length != right.length) {
            throw new IllegalArgumentException("Cannot compare a " + left.length + "-byte record "
                    + "against a " + right.length + "-byte one");
        }
        int differences = 0;
        for (int index = 0; index < left.length; index++) {
            if (left[index] != right[index]) {
                differences++;
            }
        }
        return differences;
    }

    private static RecordLayout accountRecordLayout() {
        return RecordLayout.of(300,
                FieldSpan.unsignedNumeric("ACCT-ID", 0, 11),
                FieldSpan.alphanumeric("ACCT-ACTIVE-STATUS", 11, 1),
                FieldSpan.signedScaled("ACCT-CURR-BAL", 12, 10, 2),
                FieldSpan.signedScaled("ACCT-CREDIT-LIMIT", 24, 10, 2),
                FieldSpan.signedScaled("ACCT-CASH-CREDIT-LIMIT", 36, 10, 2),
                FieldSpan.alphanumeric("ACCT-OPEN-DATE", 48, 10),
                FieldSpan.alphanumeric("ACCT-EXPIRAION-DATE", 58, 10),
                FieldSpan.alphanumeric("ACCT-REISSUE-DATE", 68, 10),
                FieldSpan.signedScaled("ACCT-CURR-CYC-CREDIT", 78, 10, 2),
                FieldSpan.signedScaled("ACCT-CURR-CYC-DEBIT", 90, 10, 2),
                FieldSpan.alphanumeric("ACCT-ADDR-ZIP", 102, 10),
                FieldSpan.alphanumeric("ACCT-GROUP-ID", 112, 10),
                FieldSpan.filler(122, 178));
    }

    private static RecordLayout cardRecordLayout() {
        return RecordLayout.of(150,
                FieldSpan.alphanumeric("CARD-NUM", 0, 16),
                FieldSpan.unsignedNumeric("CARD-ACCT-ID", 16, 11),
                FieldSpan.unsignedNumeric("CARD-CVV-CD", 27, 3),
                FieldSpan.alphanumeric("CARD-EMBOSSED-NAME", 30, 50),
                FieldSpan.alphanumeric("CARD-EXPIRAION-DATE", 80, 10),
                FieldSpan.alphanumeric("CARD-ACTIVE-STATUS", 90, 1),
                FieldSpan.filler(91, 59));
    }

    private static RecordLayout cardXrefLayout() {
        return RecordLayout.of(CARD_XREF_DECLARED_WIDTH,
                FieldSpan.alphanumeric("XREF-CARD-NUM", 0, 16),
                FieldSpan.unsignedNumeric("XREF-CUST-ID", 16, 9),
                FieldSpan.unsignedNumeric("XREF-ACCT-ID", 25, 11),
                FieldSpan.filler(36, 14));
    }

    private static RecordLayout cardXrefFixtureLayout() {
        return RecordLayout.of(CARD_XREF_FIXTURE_WIDTH,
                FieldSpan.alphanumeric("XREF-CARD-NUM", 0, 16),
                FieldSpan.unsignedNumeric("XREF-CUST-ID", 16, 9),
                FieldSpan.unsignedNumeric("XREF-ACCT-ID", 25, 11));
    }

    private static RecordLayout customerRecordLayout() {
        return RecordLayout.of(500,
                FieldSpan.unsignedNumeric("CUST-ID", 0, 9),
                FieldSpan.alphanumeric("CUST-FIRST-NAME", 9, 25),
                FieldSpan.alphanumeric("CUST-MIDDLE-NAME", 34, 25),
                FieldSpan.alphanumeric("CUST-LAST-NAME", 59, 25),
                FieldSpan.alphanumeric("CUST-ADDR-LINE-1", 84, 50),
                FieldSpan.alphanumeric("CUST-ADDR-LINE-2", 134, 50),
                FieldSpan.alphanumeric("CUST-ADDR-LINE-3", 184, 50),
                FieldSpan.alphanumeric("CUST-ADDR-STATE-CD", 234, 2),
                FieldSpan.alphanumeric("CUST-ADDR-COUNTRY-CD", 236, 3),
                FieldSpan.alphanumeric("CUST-ADDR-ZIP", 239, 10),
                FieldSpan.alphanumeric("CUST-PHONE-NUM-1", 249, 15),
                FieldSpan.alphanumeric("CUST-PHONE-NUM-2", 264, 15),
                FieldSpan.unsignedNumeric("CUST-SSN", 279, 9),
                FieldSpan.alphanumeric("CUST-GOVT-ISSUED-ID", 288, 20),
                FieldSpan.alphanumeric("CUST-DOB-YYYY-MM-DD", 308, 10),
                FieldSpan.alphanumeric("CUST-EFT-ACCOUNT-ID", 318, 10),
                FieldSpan.alphanumeric("CUST-PRI-CARD-HOLDER-IND", 328, 1),
                FieldSpan.unsignedNumeric("CUST-FICO-CREDIT-SCORE", 329, 3),
                FieldSpan.filler(332, 168));
    }

    private static RecordLayout tranRecordLayout() {
        return RecordLayout.of(350,
                FieldSpan.alphanumeric("TRAN-ID", 0, 16),
                FieldSpan.alphanumeric("TRAN-TYPE-CD", 16, 2),
                FieldSpan.unsignedNumeric("TRAN-CAT-CD", 18, 4),
                FieldSpan.alphanumeric("TRAN-SOURCE", 22, 10),
                FieldSpan.alphanumeric("TRAN-DESC", 32, 100),
                FieldSpan.signedScaled("TRAN-AMT", 132, 9, 2),
                FieldSpan.unsignedNumeric("TRAN-MERCHANT-ID", 143, 9),
                FieldSpan.alphanumeric("TRAN-MERCHANT-NAME", 152, 50),
                FieldSpan.alphanumeric("TRAN-MERCHANT-CITY", 202, 50),
                FieldSpan.alphanumeric("TRAN-MERCHANT-ZIP", 252, 10),
                FieldSpan.alphanumeric("TRAN-CARD-NUM", 262, 16),
                FieldSpan.alphanumeric("TRAN-ORIG-TS", 278, 26),
                FieldSpan.alphanumeric("TRAN-PROC-TS", 304, 26),
                FieldSpan.filler(330, 20));
    }

    private static RecordLayout dalyTranRecordLayout() {
        return RecordLayout.of(350,
                FieldSpan.alphanumeric("DALYTRAN-ID", 0, 16),
                FieldSpan.alphanumeric("DALYTRAN-TYPE-CD", 16, 2),
                FieldSpan.unsignedNumeric("DALYTRAN-CAT-CD", 18, 4),
                FieldSpan.alphanumeric("DALYTRAN-SOURCE", 22, 10),
                FieldSpan.alphanumeric("DALYTRAN-DESC", 32, 100),
                FieldSpan.signedScaled("DALYTRAN-AMT", 132, 9, 2),
                FieldSpan.unsignedNumeric("DALYTRAN-MERCHANT-ID", 143, 9),
                FieldSpan.alphanumeric("DALYTRAN-MERCHANT-NAME", 152, 50),
                FieldSpan.alphanumeric("DALYTRAN-MERCHANT-CITY", 202, 50),
                FieldSpan.alphanumeric("DALYTRAN-MERCHANT-ZIP", 252, 10),
                FieldSpan.alphanumeric("DALYTRAN-CARD-NUM", 262, 16),
                FieldSpan.alphanumeric("DALYTRAN-ORIG-TS", 278, 26),
                FieldSpan.alphanumeric("DALYTRAN-PROC-TS", 304, 26),
                FieldSpan.filler(330, 20));
    }

    private static RecordLayout tranCatBalLayout() {
        return RecordLayout.of(50,
                FieldSpan.unsignedNumeric("TRANCAT-ACCT-ID", 0, 11),
                FieldSpan.alphanumeric("TRANCAT-TYPE-CD", 11, 2),
                FieldSpan.unsignedNumeric("TRANCAT-CD", 13, 4),
                FieldSpan.signedScaled("TRAN-CAT-BAL", TRAN_CAT_KEY_WIDTH, 9, 2),
                FieldSpan.filler(28, 22));
    }

    private static RecordLayout disclosureGroupLayout() {
        return RecordLayout.of(50,
                FieldSpan.alphanumeric("DIS-ACCT-GROUP-ID", 0, 10),
                FieldSpan.alphanumeric("DIS-TRAN-TYPE-CD", 10, 2),
                FieldSpan.unsignedNumeric("DIS-TRAN-CAT-CD", 12, 4),
                FieldSpan.signedScaled("DIS-INT-RATE", DIS_GROUP_KEY_WIDTH, 4, 2),
                FieldSpan.filler(22, 28));
    }

    private static RecordLayout tranTypeLayout() {
        return RecordLayout.of(60,
                FieldSpan.alphanumeric("TRAN-TYPE", 0, 2),
                FieldSpan.alphanumeric("TRAN-TYPE-DESC", 2, 50),
                FieldSpan.filler(52, 8));
    }

    private static RecordLayout tranCategoryLayout() {
        return RecordLayout.of(60,
                FieldSpan.alphanumeric("TRAN-TYPE-CD", 0, 2),
                FieldSpan.unsignedNumeric("TRAN-CAT-CD", 2, 4),
                FieldSpan.alphanumeric("TRAN-CAT-TYPE-DESC", 6, 50),
                FieldSpan.filler(56, 4));
    }

    private static RecordLayout curdateMmDdYyLayout() {
        return RecordLayout.of(8,
                FieldSpan.unsignedNumeric("WS-CURDATE-MM", 0, 2),
                FieldSpan.filler(2, 1, "/"),
                FieldSpan.unsignedNumeric("WS-CURDATE-DD", 3, 2),
                FieldSpan.filler(5, 1, "/"),
                FieldSpan.unsignedNumeric("WS-CURDATE-YY", 6, 2));
    }

    private static RecordLayout timestampFractionLayout() {
        return RecordLayout.of(9,
                FieldSpan.unsignedNumeric("WS-TIMESTAMP-TM-SS", 0, 2),
                FieldSpan.filler(2, 1, "."),
                FieldSpan.unsignedNumeric("WS-TIMESTAMP-TM-MS6", 3, 6));
    }

    private static RecordLayout outputLineLayout(String name, int width) {
        return RecordLayout.of(width, FieldSpan.alphanumeric(name, 0, width));
    }

    private static RecordLayout layoutFor(String copybook) {
        return switch (copybook) {
            case "CVACT01Y" -> accountRecordLayout();
            case "CVACT02Y" -> cardRecordLayout();
            case "CVACT03Y" -> cardXrefLayout();
            case "CVCUS01Y" -> customerRecordLayout();
            case "CVTRA01Y" -> tranCatBalLayout();
            case "CVTRA02Y" -> disclosureGroupLayout();
            case "CVTRA03Y" -> tranTypeLayout();
            case "CVTRA04Y" -> tranCategoryLayout();
            case "CVTRA05Y" -> tranRecordLayout();
            case "CVTRA06Y" -> dalyTranRecordLayout();
            default -> throw new IllegalArgumentException("No layout is transcribed for copybook '"
                    + copybook + "'; transcribe it beside the others before naming it in a row");
        };
    }

    private static String firstAccountFixtureRow() {
        String row = "00000000001"
                + "Y"
                + FIRST_ACCOUNT_BALANCE_IMAGE
                + "00000020200{"
                + "00000010200{"
                + "2014-11-20"
                + "2025-05-20"
                + "2025-05-20"
                + "00000000000{"
                + "00000000000{"
                + "A000000000"
                + runOf(' ', 10)
                + runOf(' ', 178);
        if (row.length() != 300) {
            throw new IllegalStateException("Transcription of acctdata.txt record 1 is "
                    + row.length() + " bytes, not the measured 300");
        }
        return row;
    }

    @Nested
    @DisplayName("The charset is a mandatory argument and is honoured on both paths")
    class CharsetContract {
        @Test
        @DisplayName("IBM037 is present in this JDK, and its absence fails the run rather than skipping")
        void ibm037IsAvailableInThisJdk() {
            assertThat(Charset.isSupported("IBM037"))
                    .as("IBM037 is required to read the EBCDIC datasets under app/data/EBCDIC and "
                            + "must never be silently skipped")
                    .isTrue();
            assertThat(EBCDIC.name()).isEqualTo("IBM037");
            assertThat(ASCII.name()).isEqualTo("US-ASCII");
        }

        @Test
        @DisplayName("a null charset is rejected: an encoding is never derived from the platform")
        void nullCharsetIsRejected() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new FixedWidthCodec(null))
                    .withMessageContaining("code page");
        }

        @Test
        @DisplayName("the charset supplied at construction is the one reported and the one used")
        void charsetIsReportedAndPropagated() {
            assertThat(codec.charset()).isEqualTo(ASCII);
            assertThat(ebcdicCodec.charset()).isEqualTo(EBCDIC);
            assertThat(codec.newRecord(cardXrefLayout()).charset()).isEqualTo(ASCII);
            assertThat(ebcdicCodec.newRecord(cardXrefLayout()).charset()).isEqualTo(EBCDIC);
        }

        @Test
        @DisplayName("a multi-byte code page is rejected: a zoned field of n digits occupies n bytes")
        void multiByteCodePageIsRejected() {
            Charset utf16 = Charset.forName("UTF-16");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new FixedWidthCodec(utf16))
                    .withMessageContaining("UTF-16")
                    .withMessageContaining("byte(s)");
        }

        @Test
        @DisplayName("the same value writes different bytes under the two code pages")
        void sameValueWritesDifferentBytesPerCodePage() {
            RecordLayout layout = accountRecordLayout();
            FieldSpan balance = layout.span("ACCT-CURR-BAL");

            FixedWidthRecord asciiRecord = codec.newRecord(layout);
            codec.writeMonetary(asciiRecord, balance, FIRST_ACCOUNT_BALANCE);
            byte[] asciiSpan = Arrays.copyOfRange(asciiRecord.toByteArray(), 12, 24);

            FixedWidthRecord ebcdicRecord = ebcdicCodec.newRecord(layout);
            ebcdicCodec.writeMonetary(ebcdicRecord, balance, FIRST_ACCOUNT_BALANCE);
            byte[] ebcdicSpan = Arrays.copyOfRange(ebcdicRecord.toByteArray(), 12, 24);

            assertThat(asciiSpan).isEqualTo(new byte[] {
                    0x30, 0x30, 0x30, 0x30, 0x30, 0x30, 0x30, 0x31, 0x39, 0x34, 0x30, 0x7B});
            assertThat(ebcdicSpan).isEqualTo(new byte[] {
                    (byte) 0xF0, (byte) 0xF0, (byte) 0xF0, (byte) 0xF0, (byte) 0xF0, (byte) 0xF0,
                    (byte) 0xF0, (byte) 0xF1, (byte) 0xF9, (byte) 0xF4, (byte) 0xF0, (byte) 0xC0});
            assertThat(asciiSpan).isNotEqualTo(ebcdicSpan);

            assertThat(asciiSpan).hasSize(12);
            assertThat(ebcdicSpan).hasSize(12);
            assertThat(codec.readMonetary(asciiRecord, balance)).isEqualTo(FIRST_ACCOUNT_BALANCE);
            assertThat(ebcdicCodec.readMonetary(ebcdicRecord, balance))
                    .isEqualTo(FIRST_ACCOUNT_BALANCE);
        }

        @Test
        @DisplayName("ASCII bytes read under IBM037 are rejected, never silently misread")
        void asciiBytesReadUnderEbcdicAreRejected() {
            RecordLayout layout = accountRecordLayout();
            FieldSpan balance = layout.span("ACCT-CURR-BAL");
            byte[] asciiRow = firstAccountFixtureRow().getBytes(ASCII);

            assertThat(codec.readMonetary(codec.wrap(asciiRow, layout), balance))
                    .isEqualTo(FIRST_ACCOUNT_BALANCE);

            FixedWidthRecord misread = ebcdicCodec.wrap(asciiRow, layout);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ebcdicCodec.readMonetary(misread, balance))
                    .withMessageContaining("DISPLAY");
        }
    }

    @Nested
    @DisplayName("PIC X MOVE - pads and truncates on the RIGHT")
    class AlphanumericMove {
        @Test
        @DisplayName("an over-wide value loses its TAIL, never its head")
        void movePicXTruncatesOnTheRight() {
            assertThat(codec.movePicX("ABCDEFGHIJ", 4)).isEqualTo("ABCD");
            assertThat(codec.movePicX("ABCDEFGHIJ", 4)).isNotEqualTo("GHIJ");
            assertThat(codec.movePicX("ABCDEFGHIJ", 1)).isEqualTo("A");
            assertThat(codec.movePicX("ABCDEFGHIJ", 9)).isEqualTo("ABCDEFGHI");
        }

        @Test
        @DisplayName("a short value is padded with spaces on the right, to the exact declared width")
        void movePicXPadsOnTheRightWithSpaces() {
            String padded = codec.movePicX("AB", 5);
            assertThat(padded).isEqualTo("AB   ");
            assertThat(padded).hasSize(5);
            assertThat(padded.substring(2)).isEqualTo(runOf(' ', 3));
        }

        @Test
        @DisplayName("an exact-width value passes through untouched")
        void movePicXIsPassThroughAtExactWidth() {
            assertThat(codec.movePicX("2014-11-20", 10)).isEqualTo("2014-11-20");
            assertThat(codec.movePicX(runOf(' ', 10), 10)).isEqualTo(runOf(' ', 10));
        }

        @Test
        @DisplayName("an empty value blanks the field to its full width")
        void movePicXFromEmptyYieldsSpaces() {
            assertThat(codec.movePicX("", 3)).isEqualTo("   ");
            assertThat(codec.movePicX("", 1)).isEqualTo(" ");
        }

        @Test
        @DisplayName("a null value and a non-positive width are both rejected")
        void movePicXGuards() {
            assertThatNullPointerException()
                    .isThrownBy(() -> codec.movePicX(null, 4))
                    .withMessageContaining("sending value");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> codec.movePicX("AB", 0))
                    .withMessageContaining("at least one character position");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> codec.movePicX("AB", -1))
                    .withMessageContaining("-1");
        }

        @Test
        @DisplayName("writePicX applies the right-truncating rule inside a span")
        void writePicXTruncatesWithinTheSpan() {
            RecordLayout layout = accountRecordLayout();
            FieldSpan groupId = layout.span("ACCT-GROUP-ID");
            FixedWidthRecord record = codec.newRecord(layout);

            codec.writePicX(record, groupId, "ABCDEFGHIJKL");

            assertThat(codec.readPicX(record, groupId)).isEqualTo("ABCDEFGHIJ");
            assertThat(record.toByteArray()).hasSize(300);
            assertThat(record.readString(102, 10)).isEqualTo(runOf(' ', 10));
        }

        @Test
        @DisplayName("readPicX is untrimmed, and readPicXTrimmed removes trailing spaces only")
        void readPicXTrimmingIsADeliberateChoice() {
            RecordLayout layout = accountRecordLayout();
            FieldSpan groupId = layout.span("ACCT-GROUP-ID");
            FixedWidthRecord record = codec.newRecord(layout);

            codec.writePicX(record, groupId, "AB");
            assertThat(codec.readPicX(record, groupId)).isEqualTo("AB" + runOf(' ', 8));
            assertThat(codec.readPicXTrimmed(record, groupId)).isEqualTo("AB");

            codec.writePicX(record, groupId, "  AB");
            assertThat(codec.readPicXTrimmed(record, groupId)).isEqualTo("  AB");

            codec.writePicX(record, groupId, "ABCDEFGHIJ");
            assertThat(codec.readPicXTrimmed(record, groupId)).isEqualTo("ABCDEFGHIJ");

            codec.writePicX(record, groupId, "");
            assertThat(codec.readPicX(record, groupId)).isEqualTo(runOf(' ', 10));
            assertThat(codec.readPicXTrimmed(record, groupId)).isEmpty();
        }

        @Test
        @DisplayName("the record and field arguments are required on every span operation")
        void spanOperationGuards() {
            RecordLayout layout = accountRecordLayout();
            FieldSpan groupId = layout.span("ACCT-GROUP-ID");
            FixedWidthRecord record = codec.newRecord(layout);

            assertThatNullPointerException()
                    .isThrownBy(() -> codec.writePicX(null, groupId, "AB"));
            assertThatNullPointerException()
                    .isThrownBy(() -> codec.writePicX(record, null, "AB"));
            assertThatNullPointerException()
                    .isThrownBy(() -> codec.readPicX(null, groupId));
            assertThatNullPointerException()
                    .isThrownBy(() -> codec.readPicX(record, null));
            assertThatNullPointerException()
                    .isThrownBy(() -> codec.newRecord(null))
                    .withMessageContaining("record layout");
        }
    }

    @Nested
    @DisplayName("PIC 9 MOVE - pads and truncates on the LEFT, the mirror image of PIC X")
    class NumericMove {
        @Test
        @DisplayName("an over-wide value loses its HIGH-ORDER digits, never its low-order ones")
        void movePic9TruncatesOnTheLeft() {
            assertThat(codec.movePic9("1234567890", 4)).isEqualTo("7890");
            assertThat(codec.movePic9("1234567890", 4)).isNotEqualTo("1234");
            assertThat(codec.movePic9("1234567890", 1)).isEqualTo("0");
            assertThat(codec.movePic9("1234567890", 9)).isEqualTo("234567890");
        }

        @Test
        @DisplayName("a short value is zero-filled on the LEFT, to the exact declared width")
        void movePic9PadsOnTheLeftWithZeros() {
            String padded = codec.movePic9("42", 5);
            assertThat(padded).isEqualTo("00042");
            assertThat(padded).hasSize(5);
            assertThat(padded).isNotEqualTo("42000");
        }

        @Test
        @DisplayName("MOVE '05' TO TRAN-CAT-CD yields 0005 - the source's own proof of the direction")
        void movePic9ReproducesTheLiteralMoveInTheInterestCalculator() {
            assertThat(codec.movePic9("05", 4)).isEqualTo("0005");
            assertThat(codec.movePic9("05", 4)).isNotEqualTo("0500");
            assertThat(codec.movePic9("01", 4)).isEqualTo("0001");
        }

        @Test
        @DisplayName("an exact-width value passes through untouched")
        void movePic9IsPassThroughAtExactWidth() {
            assertThat(codec.movePic9("00000000001", 11)).isEqualTo("00000000001");
            assertThat(codec.movePic9("000000050", 9)).isEqualTo("000000050");
        }

        @Test
        @DisplayName("the integral overload zero-fills and left-truncates identically")
        void movePic9FromAnIntegralValue() {
            assertThat(codec.movePic9(0L, 3)).isEqualTo("000");
            assertThat(codec.movePic9(42L, 5)).isEqualTo("00042");
            assertThat(codec.movePic9(1234567890L, 4)).isEqualTo("7890");
            assertThat(codec.movePic9(1L, 6)).isEqualTo("000001");
            assertThat(codec.movePic9(Long.MAX_VALUE, 19)).isEqualTo("9223372036854775807");
        }

        @Test
        @DisplayName("a negative value has no unsigned representation and is rejected")
        void movePic9RejectsANegativeValue() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> codec.movePic9(-1L, 4))
                    .withMessageContaining("unsigned PIC 9");
        }

        @Test
        @DisplayName("an empty or blank numeric sender is rejected, not quietly read as zero")
        void movePic9RejectsAnEmptyOrBlankSender() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> codec.movePic9("", 3))
                    .withMessageContaining("empty");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> codec.movePic9("   ", 3))
                    .withMessageContaining("digits 0 to 9");
            assertThat(codec.movePic9(0L, 3)).isEqualTo("000");
        }

        @ParameterizedTest(name = "[{0}] is not a valid numeric sender")
        @ValueSource(strings = {"12A4", "1/34", " 123", "12 4", "1.23", "12-4", "abcd", "+123"})
        @DisplayName("any non-digit is rejected, on both sides of the digit range")
        void movePic9RejectsEveryNonDigit(String sender) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> codec.movePic9(sender, 8))
                    .withMessageContaining("digits 0 to 9");
        }

        @Test
        @DisplayName("a null sender and a non-positive width are both rejected")
        void movePic9Guards() {
            assertThatNullPointerException()
                    .isThrownBy(() -> codec.movePic9(null, 4))
                    .withMessageContaining("sending value");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> codec.movePic9("42", 0))
                    .withMessageContaining("numeric receiver");
        }

        @Test
        @DisplayName("decodePic9 reads the measured cross-reference and account identifiers")
        void decodePic9ReadsFixtureIdentifiers() {
            assertThat(codec.decodePic9("000000050")).isEqualTo(50L);
            assertThat(codec.decodePic9("00000000050")).isEqualTo(50L);
            assertThat(codec.decodePic9("00000000001")).isEqualTo(1L);
            assertThat(codec.decodePic9("000000000")).isZero();
            assertThat(codec.decodePic9AsInt("000000050")).isEqualTo(50);
            assertThat(codec.decodePic9AsInt("0001")).isEqualTo(1);
        }

        @Test
        @DisplayName("an 18-digit field fits a long and a 19-digit one is reported, not wrapped")
        void decodePic9BoundsAreReported() {
            assertThat(codec.decodePic9("999999999999999999")).isEqualTo(999_999_999_999_999_999L);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> codec.decodePic9("9999999999999999999"))
                    .withMessageContaining("does not fit a long");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> codec.decodePic9AsInt("0003000000000"))
                    .withMessageContaining("Integer.MAX_VALUE");
        }

        @Test
        @DisplayName("a non-digit image and a null image are both rejected on the read path")
        void decodePic9Guards() {
            assertThatNullPointerException()
                    .isThrownBy(() -> codec.decodePic9(null))
                    .withMessageContaining("image is required");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> codec.decodePic9("0000A0050"))
                    .withMessageContaining("unsigned numeric field");
        }

        @Test
        @DisplayName("writePic9 and readPic9 round-trip a real cross-reference row's identifiers")
        void writeAndReadUnsignedSpans() {
            RecordLayout layout = cardXrefLayout();
            FieldSpan custId = layout.span("XREF-CUST-ID");
            FieldSpan acctId = layout.span("XREF-ACCT-ID");
            FixedWidthRecord record = codec.newRecord(layout);

            codec.writePic9(record, custId, 50L);
            codec.writePic9(record, acctId, "00000000050");

            assertThat(record.readSpan(custId)).isEqualTo("000000050");
            assertThat(record.readSpan(acctId)).isEqualTo("00000000050");
            assertThat(codec.readPic9(record, custId)).isEqualTo(50L);
            assertThat(codec.readPic9AsInt(record, acctId)).isEqualTo(50);

            assertThatNullPointerException().isThrownBy(() -> codec.writePic9(null, custId, 1L));
            assertThatNullPointerException().isThrownBy(() -> codec.writePic9(record, null, 1L));
            assertThatNullPointerException().isThrownBy(() -> codec.writePic9(null, custId, "1"));
            assertThatNullPointerException().isThrownBy(() -> codec.writePic9(record, null, "1"));
            assertThatNullPointerException().isThrownBy(() -> codec.readPic9(null, custId));
            assertThatNullPointerException().isThrownBy(() -> codec.readPic9(record, null));
            assertThatNullPointerException().isThrownBy(() -> codec.readPic9AsInt(null, custId));
            assertThatNullPointerException().isThrownBy(() -> codec.readPic9AsInt(record, null));
        }
    }

    @Nested
    @DisplayName("PIC S9(p)V99 - p + s bytes, sign overpunched into the trailing byte")
    class ZonedSignedDecimal {
        @Test
        @DisplayName("a negative zero survives the round trip, because the sign is held separately")
        void aNegativeZeroSurvivesTheRoundTrip() {
            FixedWidthCodec.SignedZoned negativeZero = codec.decodeSignedZoned("0000000000}", 2);

            assertThat(negativeZero.negative()).isTrue();
            assertThat(negativeZero.zero()).isTrue();
            assertThat(negativeZero.negativeZero()).isTrue();
            assertThat(negativeZero.magnitude()).isEqualByComparingTo("0.00");
            assertThat(negativeZero.magnitude().scale()).isEqualTo(2);
            assertThat(codec.encodeSignedZoned(negativeZero, 9, 2))
                    .as("the stored image is reproduced byte for byte")
                    .isEqualTo("0000000000}");
        }

        @Test
        @DisplayName("a positive zero and a negative zero are distinct images, never interchangeable")
        void positiveAndNegativeZeroAreDistinctImages() {
            FixedWidthCodec.SignedZoned positiveZero =
                    new FixedWidthCodec.SignedZoned(new BigDecimal("0.00"), false);
            FixedWidthCodec.SignedZoned negativeZero =
                    new FixedWidthCodec.SignedZoned(new BigDecimal("0.00"), true);

            assertThat(codec.encodeSignedZoned(positiveZero, 9, 2)).isEqualTo("0000000000{");
            assertThat(codec.encodeSignedZoned(negativeZero, 9, 2)).isEqualTo("0000000000}");
            assertThat(positiveZero).isNotEqualTo(negativeZero);
            assertThat(positiveZero.negativeZero()).isFalse();

            FixedWidthCodec.SignedZoned negativeAmount =
                    new FixedWidthCodec.SignedZoned(new BigDecimal("1.23"), true);
            assertThat(negativeAmount.zero()).isFalse();
            assertThat(negativeAmount.negativeZero())
                    .as("a negative quantity that is not zero is not a negative zero")
                    .isFalse();
            assertThat(negativeAmount.signedValue()).isEqualByComparingTo("-1.23");
        }

        @Test
        @DisplayName("the BigDecimal entry point is magnitude-only, and says so")
        void theBigDecimalEntryPointIsMagnitudeOnly() {
            assertThat(codec.decodeSignedScaled("0000000000}", 2))
                    .as("a BigDecimal has no negative zero, so the sign of a zero is lost here")
                    .isEqualByComparingTo("0.00");
            assertThat(codec.encodeSignedScaled(new BigDecimal("-0.00"), 9, 2))
                    .as("and cannot be recovered on the way back out")
                    .isEqualTo("0000000000{");
            assertThat(codec.encodeSignedScaled(new BigDecimal("-0.01"), 9, 2))
                    .as("a non-zero negative is unaffected: the sign rides the magnitude there, so "
                            + "-0.01 is ten zeros and a 'J' - digit 1, negative")
                    .isEqualTo("0000000000J");
        }

        @Test
        @DisplayName("zone F, zone C and zone D all decode, and each re-encodes to its own zone")
        void everyStoredZoneDecodesAndReEncodesToItself() {
            assertThat(codec.decodeSignedZoned("00000000123", 2).negative()).isFalse();
            assertThat(codec.decodeSignedZoned("0000000012C", 2).negative()).isFalse();
            assertThat(codec.decodeSignedZoned("0000000012L", 2).negative()).isTrue();

            assertThat(codec.decodeSignedZoned("00000000123", 2).magnitude())
                    .as("zone F is the unsigned form: positive by definition, digit 3 in the trailing "
                            + "position")
                    .isEqualByComparingTo("1.23");
            assertThat(codec.encodeSignedZoned(codec.decodeSignedZoned("00000000123", 2), 9, 2))
                    .as("re-encoding normalises the unsigned form to its signed equivalent, which is "
                            + "why the parity differ compares stored images rather than values")
                    .isEqualTo("0000000012C");
            assertThat(codec.encodeSignedZoned(codec.decodeSignedZoned("0000000012L", 2), 9, 2))
                    .isEqualTo("0000000012L");
        }

        @Test
        @DisplayName("a literal expectation honours a leading minus even on an all-zero value")
        void aLiteralExpectationHonoursAnExplicitMinusZero() {
            assertThat(FixedWidthCodec.SignedZoned.ofLiteral("-0.00").negativeZero()).isTrue();
            assertThat(FixedWidthCodec.SignedZoned.ofLiteral(" -0.00 ").negativeZero()).isTrue();
            assertThat(FixedWidthCodec.SignedZoned.ofLiteral("0.00").negativeZero()).isFalse();
            assertThat(FixedWidthCodec.SignedZoned.ofLiteral("-1.23").magnitude())
                    .isEqualByComparingTo("1.23");
            assertThat(FixedWidthCodec.SignedZoned.ofLiteral("-1.23").negative()).isTrue();
            assertThat(FixedWidthCodec.SignedZoned.of(new BigDecimal("-1.23")).negative()).isTrue();
            assertThat(FixedWidthCodec.SignedZoned.of(new BigDecimal("-0.00")).negative())
                    .as("BigDecimal cannot express it, so this factory cannot produce it")
                    .isFalse();
        }

        @Test
        @DisplayName("the sign-bearing form guards its own invariants")
        void theSignBearingFormGuardsItsInvariants() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new FixedWidthCodec.SignedZoned(new BigDecimal("-1.00"), true))
                    .withMessageContaining("is negative");
            assertThatNullPointerException()
                    .isThrownBy(() -> new FixedWidthCodec.SignedZoned(null, false))
                    .withMessageContaining("magnitude is required");
            assertThatNullPointerException()
                    .isThrownBy(() -> FixedWidthCodec.SignedZoned.of(null))
                    .withMessageContaining("value is required");
            assertThatNullPointerException()
                    .isThrownBy(() -> FixedWidthCodec.SignedZoned.ofLiteral(null))
                    .withMessageContaining("literal is required");
            assertThatNullPointerException()
                    .isThrownBy(() -> codec.encodeSignedZoned(null, 9, 2))
                    .withMessageContaining("signed zoned quantity is required");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> codec.encodeSignedZoned(
                            FixedWidthCodec.SignedZoned.ofLiteral("1.23"), 0, 2))
                    .withMessageContaining("requires p of at least 1");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> codec.encodeSignedZoned(
                            FixedWidthCodec.SignedZoned.ofLiteral("1.23"), 9, -1))
                    .withMessageContaining("s must not be negative");
        }

        @Test
        @DisplayName("the sign-bearing span accessors round-trip a negative zero through a record")
        void theSignBearingSpanAccessorsRoundTripANegativeZero() {
            RecordLayout layout = tranCatBalLayout();
            FieldSpan balance = layout.span("TRAN-CAT-BAL");
            FixedWidthRecord record = codec.newRecord(layout);

            assertThat(codec.readSignedZoned(record, balance, 2).negativeZero())
                    .as("an established record holds a positive zero, as the datasets do")
                    .isFalse();

            codec.writeSignedZoned(record, balance,
                    new FixedWidthCodec.SignedZoned(new BigDecimal("0.00"), true), 2);

            assertThat(record.readSpan(balance)).isEqualTo("0000000000}");
            assertThat(codec.readSignedZoned(record, balance, 2).negativeZero()).isTrue();
            assertThat(codec.readMonetary(record, balance))
                    .as("read as a quantity it is simply zero")
                    .isEqualByComparingTo("0.00");
            assertThatNullPointerException()
                    .isThrownBy(() -> codec.readSignedZoned(null, balance, 2))
                    .withMessageContaining("record area is required");
            assertThatNullPointerException()
                    .isThrownBy(() -> codec.readSignedZoned(record, null, 2))
                    .withMessageContaining("field descriptor is required");
            assertThatNullPointerException()
                    .isThrownBy(() -> codec.writeSignedZoned(null, balance,
                            FixedWidthCodec.SignedZoned.ofLiteral("0.00"), 2))
                    .withMessageContaining("record area is required");
            assertThatNullPointerException()
                    .isThrownBy(() -> codec.writeSignedZoned(record, null,
                            FixedWidthCodec.SignedZoned.ofLiteral("0.00"), 2))
                    .withMessageContaining("field descriptor is required");
        }

        @Test
        @DisplayName("a whole record image transcodes strictly through the codec's own seam")
        void aWholeRecordImageTranscodesStrictly() {
            assertThat(codec.encodeImage("AB", "a test image"))
                    .containsExactly((byte) 'A', (byte) 'B');
            assertThat(codec.decodeImage(new byte[]{(byte) 'A', (byte) 'B'}, "a test image"))
                    .isEqualTo("AB");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> codec.encodeImage("\u00e9", "a test image"))
                    .withMessageContaining("cannot represent");
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> codec.decodeImage(new byte[]{(byte) 0x80}, "a test image"))
                    .withMessageContaining("not valid code page US-ASCII data");
            assertThatNullPointerException()
                    .isThrownBy(() -> codec.decodeImage(null, "a test image"))
                    .withMessageContaining("Stored bytes are required");
        }

        @Test
        @DisplayName("the first account record's balance decodes to 194.00 at scale 2")
        void decodesTheFirstAccountRecordBalance() {
            BigDecimal decoded = codec.decodeSignedScaled(FIRST_ACCOUNT_BALANCE_IMAGE,
                    CobolDecimal.MONETARY_SCALE);

            assertThat(decoded).isEqualTo(FIRST_ACCOUNT_BALANCE);
            assertThat(decoded.scale()).isEqualTo(2);
            assertThat(decoded.signum()).isPositive();
        }

        @Test
        @DisplayName("the naive decode of that same image throws - which is why this codec exists")
        void aNaiveDecodeOfTheSameImageFails() {
            assertThatExceptionOfType(NumberFormatException.class)
                    .isThrownBy(() -> new BigDecimal(FIRST_ACCOUNT_BALANCE_IMAGE));
            assertThat(codec.decodeSignedScaled(FIRST_ACCOUNT_BALANCE_IMAGE, 2))
                    .isEqualTo(FIRST_ACCOUNT_BALANCE);
        }

        @Test
        @DisplayName("the round trip is exactly p + s characters wide - 12, never 13")
        void roundTripIsExactlyPPlusSCharactersWide() {
            String encoded = codec.encodeSignedScaled(FIRST_ACCOUNT_BALANCE, 10, 2);

            assertThat(encoded).isEqualTo(FIRST_ACCOUNT_BALANCE_IMAGE);
            assertThat(encoded).hasSize(12);
            assertThat(encoded).hasSize(10 + 2);
        }

        @Test
        @DisplayName("all five S9(10)V99 fields of the first account row round-trip byte for byte")
        void everyMoneyFieldOfTheFirstAccountRowRoundTrips() {
            assertThat(codec.decodeSignedScaled("00000001940{", 2)).isEqualTo(new BigDecimal("194.00"));
            assertThat(codec.decodeSignedScaled("00000020200{", 2)).isEqualTo(new BigDecimal("2020.00"));
            assertThat(codec.decodeSignedScaled("00000010200{", 2)).isEqualTo(new BigDecimal("1020.00"));
            assertThat(codec.decodeSignedScaled("00000000000{", 2)).isEqualTo(new BigDecimal("0.00"));

            assertThat(codec.encodeSignedScaled(new BigDecimal("194.00"), 10, 2)).isEqualTo("00000001940{");
            assertThat(codec.encodeSignedScaled(new BigDecimal("2020.00"), 10, 2)).isEqualTo("00000020200{");
            assertThat(codec.encodeSignedScaled(new BigDecimal("1020.00"), 10, 2)).isEqualTo("00000010200{");
            assertThat(codec.encodeSignedScaled(new BigDecimal("0.00"), 10, 2)).isEqualTo("00000000000{");
        }

        @ParameterizedTest(name = "{0} -> {1} (app/data/ASCII/dailytran.txt row {2})")
        @CsvSource({
                "0000003250{, 325.00, 26",
                "0000004161A, 416.11, 10",
                "0000002502B, 250.22, 12",
                "0000000943C, 94.33, 11",
                "0000000294D, 29.44, 14",
                "0000008295E, 829.55, 13",
                "0000004546F, 454.66, 5",
                "0000005047G, 504.77, 1",
                "0000000678H, 67.88, 3",
                "0000008499I, 849.99, 6",
                "0000009190}, -919.00, 2",
                "0000008351J, -835.11, 65",
                "0000000752K, -75.22, 113",
                "0000002153L, -215.33, 38",
                "0000003584M, -358.44, 53",
                "0000004455N, -445.55, 73",
                "0000009456O, -945.66, 17",
                "0000000567P, -56.77, 7",
                "0000005358Q, -535.88, 9",
                "0000000709R, -70.99, 23"})
        @DisplayName("every overpunch character decodes and re-encodes exactly, from real fixture rows")
        void theWholeOverpunchAlphabetRoundTrips(String image, String expected, int fixtureRow) {
            assertThat(fixtureRow)
                    .as("cited row of app/data/ASCII/dailytran.txt, which holds 300 rows")
                    .isBetween(1, 300);
            assertThat(image).hasSize(11);

            BigDecimal decoded = codec.decodeSignedScaled(image, 2);
            assertThat(decoded).isEqualTo(new BigDecimal(expected));
            assertThat(decoded.scale()).isEqualTo(2);

            assertThat(codec.encodeSignedScaled(decoded, 9, 2)).isEqualTo(image);
        }

        @Test
        @DisplayName("the sign lives ONLY in the trailing character; the other p + s - 1 are digits")
        void theSignOccupiesNoCharacterPositionOfItsOwn() {
            String negative = codec.encodeSignedScaled(new BigDecimal("-70.99"), 9, 2);

            assertThat(negative).isEqualTo("0000000709R");
            assertThat(negative).hasSize(11);
            assertThat(negative).doesNotContain("-");
            assertThat(negative.substring(0, 10)).containsOnlyDigits();
            assertThat(negative.charAt(10)).isEqualTo('R');

            String positive = codec.encodeSignedScaled(new BigDecimal("70.99"), 9, 2);
            assertThat(positive).isEqualTo("0000000709I");
            assertThat(positive.substring(0, 10)).isEqualTo(negative.substring(0, 10));
            assertThat(positive.charAt(10)).isNotEqualTo(negative.charAt(10));
        }

        @Test
        @DisplayName("a negative-zero image decodes to 0.00, and re-encodes positive")
        void negativeZeroDecodesToZeroAndReEncodesPositive() {
            BigDecimal decoded = codec.decodeSignedScaled("0000000000}", 2);

            assertThat(decoded).isEqualTo(new BigDecimal("0.00"));
            assertThat(decoded.signum()).isZero();
            assertThat(codec.encodeSignedScaled(decoded, 9, 2)).isEqualTo("0000000000{");

            assertThat(codec.decodeSignedScaled("0000009190}", 2)).isEqualTo(new BigDecimal("-919.00"));
            assertThat(codec.encodeSignedScaled(new BigDecimal("-919.00"), 9, 2))
                    .isEqualTo("0000009190}");
        }

        @Test
        @DisplayName("a trailing plain digit is the unsigned zone F form and is read as positive")
        void unsignedZoneFTrailingDigitIsAcceptedAsPositive() {
            assertThat(codec.decodeSignedScaled("00000050477", 2)).isEqualTo(new BigDecimal("504.77"));
            assertThat(codec.decodeSignedScaled("00000000000", 2)).isEqualTo(new BigDecimal("0.00"));
            assertThat(codec.encodeSignedScaled(new BigDecimal("504.77"), 9, 2))
                    .isEqualTo("0000005047G");
        }

        @ParameterizedTest(name = "S9({0})V{1} occupies {2} characters")
        @CsvSource({
                "4, 2, 6",
                "9, 2, 11",
                "10, 2, 12"})
        @DisplayName("the encoded width is p + s for every signed form in the codebase")
        void encodedWidthIsAlwaysPPlusS(int integerDigits, int scale, int expectedWidth) {
            assertThat(integerDigits + scale).isEqualTo(expectedWidth);

            BigDecimal value = new BigDecimal("12.34");
            String encoded = codec.encodeSignedScaled(value, integerDigits, scale);
            assertThat(encoded).hasSize(expectedWidth);
            assertThat(codec.decodeSignedScaled(encoded, scale)).isEqualTo(value);

            String negative = codec.encodeSignedScaled(value.negate(), integerDigits, scale);
            assertThat(negative).hasSize(expectedWidth);
            assertThat(codec.decodeSignedScaled(negative, scale)).isEqualTo(value.negate());

            FieldSpan span = FieldSpan.signedScaled("AMOUNT", 0, integerDigits, scale);
            assertThat(span.length()).isEqualTo(expectedWidth);
        }

        @Test
        @DisplayName("the disclosure-group rate is a 6-byte S9(04)V99, decoded from its fixture image")
        void disclosureGroupRateIsSixBytes() {
            assertThat(codec.decodeSignedScaled("00150{", 2)).isEqualTo(new BigDecimal("15.00"));
            assertThat(codec.encodeSignedScaled(new BigDecimal("15.00"), 4, 2)).isEqualTo("00150{");
            assertThat(codec.decodeSignedScaled("00000{", 2)).isEqualTo(new BigDecimal("0.00"));
            assertThat(codec.encodeSignedScaled(new BigDecimal("0.00"), 4, 2)).isEqualTo("00000{");
        }

        @Test
        @DisplayName("truncation is toward zero - not FLOOR, not HALF_UP, not HALF_EVEN")
        void excessFractionIsTruncatedTowardZero() {
            assertThat(CobolDecimal.COBOL_ROUNDING).isEqualTo(RoundingMode.DOWN);

            BigDecimal overPrecise = new BigDecimal("1.239");
            assertThat(codec.encodeSignedScaled(overPrecise, 9, 2)).isEqualTo("0000000012C");
            assertThat(codec.decodeSignedScaled("0000000012C", 2)).isEqualTo(new BigDecimal("1.23"));

            BigDecimal negativeOverPrecise = new BigDecimal("-1.239");
            String encoded = codec.encodeSignedScaled(negativeOverPrecise, 9, 2);
            assertThat(encoded).isEqualTo("0000000012L");
            assertThat(codec.decodeSignedScaled(encoded, 2)).isEqualTo(new BigDecimal("-1.23"));

            String floorWouldGive = codec.encodeSignedScaled(
                    negativeOverPrecise.setScale(2, RoundingMode.FLOOR), 9, 2);
            assertThat(floorWouldGive).isEqualTo("0000000012M");
            assertThat(encoded).isNotEqualTo(floorWouldGive);

            String halfUpWouldGive = codec.encodeSignedScaled(
                    overPrecise.setScale(2, RoundingMode.HALF_UP), 9, 2);
            assertThat(halfUpWouldGive).isEqualTo("0000000012D");
            assertThat(codec.encodeSignedScaled(overPrecise, 9, 2)).isNotEqualTo(halfUpWouldGive);
        }

        @Test
        @DisplayName("every decoded scaled value reports its declared scale, zero included")
        void everyDecodeCarriesItsDeclaredScale() {
            assertThat(codec.decodeSignedScaled("00000000000{", 2).scale()).isEqualTo(2);
            assertThat(codec.decodeSignedScaled("0000005047G", 2).scale()).isEqualTo(2);
            assertThat(codec.decodeSignedScaled("0000000709R", 2).scale()).isEqualTo(2);
            assertThat(codec.decodeSignedScaled("00E", 0)).isEqualTo(new BigDecimal("5"));
            assertThat(codec.decodeSignedScaled("00E", 0).scale()).isZero();
            assertThat(codec.encodeSignedScaled(new BigDecimal("5"), 3, 0)).isEqualTo("00E");
            assertThat(codec.decodeSignedScaled("E", 0)).isEqualTo(new BigDecimal("5"));
            assertThat(codec.decodeSignedScaled("N", 0)).isEqualTo(new BigDecimal("-5"));
        }

        @Test
        @DisplayName("high-order overflow wraps and keeps the sign, and never throws")
        void highOrderOverflowWrapsRatherThanThrowing() {
            BigDecimal tooWide = new BigDecimal("12345678901.23");

            assertThat(codec.encodeSignedScaled(tooWide, 10, 2)).isEqualTo("23456789012C");
            assertThat(codec.encodeSignedScaled(tooWide.negate(), 10, 2)).isEqualTo("23456789012L");
            assertThat(codec.encodeSignedScaled(tooWide, 10, 2)).hasSize(12);
        }

        @Test
        @DisplayName("a value whose digits already fill the field is not padded")
        void aFullWidthValueIsNotPadded() {
            assertThat(codec.encodeSignedScaled(new BigDecimal("2345678901.23"), 10, 2))
                    .isEqualTo("23456789012C");
            assertThat(codec.encodeSignedScaled(FIRST_ACCOUNT_BALANCE, 10, 2))
                    .isEqualTo(FIRST_ACCOUNT_BALANCE_IMAGE);
        }

        @ParameterizedTest(name = "a trailing [{0}] is neither a digit nor a sign overpunch")
        @ValueSource(strings = {"0000000000Z", "0000000000*", "0000000000 ", "0000000000-",
                "0000000000#", "0000000000a", "0000000000S", "0000000000+"})
        @DisplayName("a malformed trailing character is rejected, not guessed at")
        void aMalformedTrailingCharacterIsRejected(String image) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> codec.decodeSignedScaled(image, 2))
                    .withMessageContaining("sign overpunch");
        }

        @Test
        @DisplayName("a non-digit in a digit position is rejected and located by character number")
        void aNonDigitLeadingCharacterIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> codec.decodeSignedScaled("000A000000{", 2))
                    .withMessageContaining("character 4");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> codec.decodeSignedScaled("00000 0000{", 2))
                    .withMessageContaining("DISPLAY");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> codec.decodeSignedScaled("0000000000{{", 2))
                    .withMessageContaining("character 11");
        }

        @Test
        @DisplayName("an empty image, a negative scale and a scale wider than the image are rejected")
        void decodeGuards() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> codec.decodeSignedScaled("", 2))
                    .withMessageContaining("at least one character");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> codec.decodeSignedScaled("00000001940{", -1))
                    .withMessageContaining("negative");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> codec.decodeSignedScaled("00150{", 7))
                    .withMessageContaining("exceeds");
            assertThatNullPointerException()
                    .isThrownBy(() -> codec.decodeSignedScaled(null, 2))
                    .withMessageContaining("image is required");
        }

        @Test
        @DisplayName("encoding rejects p below one, a negative s and a null value")
        void encodeGuards() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> codec.encodeSignedScaled(FIRST_ACCOUNT_BALANCE, 0, 2))
                    .withMessageContaining("p of at least 1");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> codec.encodeSignedScaled(FIRST_ACCOUNT_BALANCE, 10, -1))
                    .withMessageContaining("must not be negative");
            assertThatNullPointerException()
                    .isThrownBy(() -> codec.encodeSignedScaled(null, 10, 2))
                    .withMessageContaining("value is required");
        }

        @Test
        @DisplayName("a span-driven write derives p from the span, so no phantom sign byte can return")
        void spanDrivenWriteDerivesTheIntegerDigitCount() {
            RecordLayout layout = accountRecordLayout();
            FieldSpan balance = layout.span("ACCT-CURR-BAL");
            FixedWidthRecord record = codec.newRecord(layout);

            codec.writeMonetary(record, balance, FIRST_ACCOUNT_BALANCE);
            assertThat(record.readSpan(balance)).isEqualTo(FIRST_ACCOUNT_BALANCE_IMAGE);
            assertThat(codec.readMonetary(record, balance)).isEqualTo(FIRST_ACCOUNT_BALANCE);

            codec.writeSignedScaled(record, balance, new BigDecimal("-919.00"), 2);
            assertThat(record.readSpan(balance)).isEqualTo("00000009190}");
            assertThat(codec.readSignedScaled(record, balance, 2)).isEqualTo(new BigDecimal("-919.00"));
            assertThat(record.toByteArray()).hasSize(300);
        }

        @Test
        @DisplayName("a scale that leaves the span no integer digit position is rejected both ways")
        void spanScaleGuards() {
            RecordLayout layout = disclosureGroupLayout();
            FieldSpan rate = layout.span("DIS-INT-RATE");
            FixedWidthRecord record = codec.newRecord(layout);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> codec.writeSignedScaled(record, rate, new BigDecimal("1.00"), 6))
                    .withMessageContaining("integer digit position");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> codec.readSignedScaled(record, rate, 6))
                    .withMessageContaining("integer digit position");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> codec.writeSignedScaled(record, rate, new BigDecimal("1.00"), -1))
                    .withMessageContaining("negative");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> codec.readSignedScaled(record, rate, -1))
                    .withMessageContaining("negative");
            assertThatNullPointerException()
                    .isThrownBy(() -> codec.writeSignedScaled(null, rate, FIRST_ACCOUNT_BALANCE, 2));
            assertThatNullPointerException()
                    .isThrownBy(() -> codec.writeSignedScaled(record, null, FIRST_ACCOUNT_BALANCE, 2));
            assertThatNullPointerException()
                    .isThrownBy(() -> codec.readSignedScaled(null, rate, 2));
            assertThatNullPointerException()
                    .isThrownBy(() -> codec.readSignedScaled(record, null, 2));
        }
    }

    @Nested
    @DisplayName("FILLER - always emitted, and its declared VALUE beats the pad byte (gate G21)")
    class FillerEmission {
        @Test
        @DisplayName("a FILLER X(n) carrying no VALUE emits n spaces")
        void fillerWithoutADeclaredValueIsSpaceFilled() {
            RecordLayout layout = accountRecordLayout();
            FieldSpan trailingFiller = FieldSpan.filler(122, 178);
            FixedWidthRecord record = codec.newRecord(layout);

            record.fill(122, 178, (byte) 'X');
            assertThat(record.readString(122, 178)).isEqualTo(runOf('X', 178));

            codec.writeDeclaredValue(record, trailingFiller);
            assertThat(record.readString(122, 178)).isEqualTo(runOf(' ', 178));
        }

        @Test
        @DisplayName("a FILLER carrying VALUE '/' emits the slash, and one carrying '.' the full stop")
        void fillerWithADeclaredValueEmitsThatLiteral() {
            RecordLayout curdate = curdateMmDdYyLayout();
            assertThat(codec.newRecord(curdate).readString(0, 8)).isEqualTo("00/00/00");

            RecordLayout fraction = timestampFractionLayout();
            assertThat(codec.newRecord(fraction).readString(0, 9)).isEqualTo("00.000000");

            FixedWidthRecord record = codec.newRecord(curdate);
            record.fill(2, 1, (byte) '?');
            codec.writeDeclaredValue(record, FieldSpan.filler(2, 1, "/"));
            assertThat(record.readString(2, 1)).isEqualTo("/");

            FixedWidthRecord fractionRecord = codec.newRecord(fraction);
            fractionRecord.fill(2, 1, (byte) '?');
            codec.writeDeclaredValue(fractionRecord, FieldSpan.filler(2, 1, "."));
            assertThat(fractionRecord.readString(2, 1)).isEqualTo(".");
        }

        @Test
        @DisplayName("a numeric span with no VALUE takes the zero byte, not the space byte")
        void numericSpanWithoutAValueIsZeroFilled() {
            RecordLayout curdate = curdateMmDdYyLayout();
            FieldSpan month = curdate.span("WS-CURDATE-MM");
            FixedWidthRecord record = codec.newRecord(curdate);

            record.fill(0, 2, (byte) ' ');
            codec.writeDeclaredValue(record, month);

            assertThat(record.readString(0, 2)).isEqualTo("00");
            assertThat(record.padByteFor(PictureKind.UNSIGNED_NUMERIC)).isEqualTo(record.zeroPadByte());
            assertThat(record.padByteFor(PictureKind.FILLER)).isEqualTo(record.spacePadByte());
        }

        @Test
        @DisplayName("the record and field arguments are required to emit a declared VALUE")
        void writeDeclaredValueGuards() {
            RecordLayout layout = accountRecordLayout();
            FieldSpan filler = FieldSpan.filler(122, 178);
            FixedWidthRecord record = codec.newRecord(layout);

            assertThatNullPointerException()
                    .isThrownBy(() -> codec.writeDeclaredValue(null, filler))
                    .withMessageContaining("record area is required");
            assertThatNullPointerException()
                    .isThrownBy(() -> codec.writeDeclaredValue(record, null))
                    .withMessageContaining("field descriptor is required");
        }

        @Test
        @DisplayName("gate G21: the account record's trailing 178 bytes are spaces and the total is 300")
        void accountRecordEmitsItsTrailingFiller() {
            byte[] encoded = codec.serialise(accountRecordLayout(), Map.of());
            String image = new String(encoded, ASCII);

            assertThat(encoded).hasSize(300);
            assertThat(image.substring(122)).isEqualTo(runOf(' ', 178));
            assertThat(image.substring(122)).hasSize(178);
        }

        @Test
        @DisplayName("gate G21: the transaction record's trailing 20 bytes are spaces and the total is 350")
        void tranRecordEmitsItsTrailingFiller() {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("TRAN-ID", "0000000000683580");
            values.put("TRAN-TYPE-CD", "01");
            values.put("TRAN-CAT-CD", "0001");
            values.put("TRAN-SOURCE", "POS TERM  ");
            values.put("TRAN-DESC", "Purchase at Abshire-Lowe");
            values.put("TRAN-AMT", "0000005047G");
            values.put("TRAN-MERCHANT-ID", "800000000");
            values.put("TRAN-MERCHANT-NAME", "Abshire-Lowe");
            values.put("TRAN-MERCHANT-CITY", "North Enoshaven");
            values.put("TRAN-MERCHANT-ZIP", "72112");
            values.put("TRAN-CARD-NUM", "4859452612877065");
            values.put("TRAN-ORIG-TS", "2022-06-10 19:27:53.000000");
            values.put("TRAN-PROC-TS", "");

            byte[] encoded = codec.serialise(tranRecordLayout(), values);
            String image = new String(encoded, ASCII);

            assertThat(encoded).hasSize(350);
            assertThat(image.substring(330)).isEqualTo(runOf(' ', 20));
            assertThat(image.substring(132, 143)).isEqualTo("0000005047G");
            assertThat(image.substring(32, 132)).isEqualTo(padRight("Purchase at Abshire-Lowe", 100));
            assertThat(image.substring(152, 202)).isEqualTo(padRight("Abshire-Lowe", 50));
            assertThat(image.substring(252, 262)).isEqualTo(padRight("72112", 10));
            assertThat(image.substring(304, 330)).isEqualTo(runOf(' ', 26));
        }

        @Test
        @DisplayName("gate G21: dropping the trailing FILLER fails at once and names the shortfall")
        void droppingTheTrailingFillerIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(300,
                            FieldSpan.unsignedNumeric("ACCT-ID", 0, 11),
                            FieldSpan.alphanumeric("ACCT-ACTIVE-STATUS", 11, 1),
                            FieldSpan.signedScaled("ACCT-CURR-BAL", 12, 10, 2),
                            FieldSpan.signedScaled("ACCT-CREDIT-LIMIT", 24, 10, 2),
                            FieldSpan.signedScaled("ACCT-CASH-CREDIT-LIMIT", 36, 10, 2),
                            FieldSpan.alphanumeric("ACCT-OPEN-DATE", 48, 10),
                            FieldSpan.alphanumeric("ACCT-EXPIRAION-DATE", 58, 10),
                            FieldSpan.alphanumeric("ACCT-REISSUE-DATE", 68, 10),
                            FieldSpan.signedScaled("ACCT-CURR-CYC-CREDIT", 78, 10, 2),
                            FieldSpan.signedScaled("ACCT-CURR-CYC-DEBIT", 90, 10, 2),
                            FieldSpan.alphanumeric("ACCT-ADDR-ZIP", 102, 10),
                            FieldSpan.alphanumeric("ACCT-GROUP-ID", 112, 10)))
                    .withMessageContaining("178 byte(s) short");

            byte[] shortRow = firstAccountFixtureRow().substring(0, 122).getBytes(ASCII);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> codec.wrap(shortRow, accountRecordLayout()))
                    .withMessageContaining("padToDeclaredWidth");
        }
    }

    @Nested
    @DisplayName("Record widths - byte-identical to the copybook declaration (gate G19)")
    class RecordWidths {
        @ParameterizedTest(name = "{0} encodes to exactly {1} bytes")
        @CsvSource({
                "CVACT01Y, 300",
                "CVACT02Y, 150",
                "CVACT03Y, 50",
                "CVCUS01Y, 500",
                "CVTRA01Y, 50",
                "CVTRA02Y, 50",
                "CVTRA03Y, 60",
                "CVTRA04Y, 60",
                "CVTRA05Y, 350",
                "CVTRA06Y, 350"})
        @DisplayName("every copybook record encodes to its declared width, under either code page")
        void everyCopybookEncodesToItsDeclaredWidth(String copybook, int declaredWidth) {
            RecordLayout layout = layoutFor(copybook);

            assertThat(layout.recordLength()).isEqualTo(declaredWidth);
            assertThat(codec.serialise(layout, Map.of())).hasSize(declaredWidth);
            assertThat(codec.newRecord(layout).toByteArray()).hasSize(declaredWidth);

            int summed = layout.storageSpans().stream().mapToInt(FieldSpan::length).sum();
            assertThat(summed).isEqualTo(declaredWidth);

            assertThat(ebcdicCodec.serialise(layout, Map.of())).hasSize(declaredWidth);
        }

        @Test
        @DisplayName("the two 50-byte records have keys of 17 and 16 bytes - both asserted, always")
        void theTwoFiftyByteRecordsHaveDifferentKeyWidths() {
            RecordLayout tranCatBal = tranCatBalLayout();
            RecordLayout disGroup = disclosureGroupLayout();

            assertThat(tranCatBal.recordLength()).isEqualTo(50);
            assertThat(disGroup.recordLength()).isEqualTo(50);

            assertThat(tranCatBal.span("TRANCAT-ACCT-ID").length()
                    + tranCatBal.span("TRANCAT-TYPE-CD").length()
                    + tranCatBal.span("TRANCAT-CD").length()).isEqualTo(TRAN_CAT_KEY_WIDTH);
            assertThat(tranCatBal.span("TRAN-CAT-BAL").offset()).isEqualTo(TRAN_CAT_KEY_WIDTH);

            assertThat(disGroup.span("DIS-ACCT-GROUP-ID").length()
                    + disGroup.span("DIS-TRAN-TYPE-CD").length()
                    + disGroup.span("DIS-TRAN-CAT-CD").length()).isEqualTo(DIS_GROUP_KEY_WIDTH);
            assertThat(disGroup.span("DIS-INT-RATE").offset()).isEqualTo(DIS_GROUP_KEY_WIDTH);

            assertThat(DIS_GROUP_KEY_WIDTH).isEqualTo(TRAN_CAT_KEY_WIDTH - 1);
        }

        @Test
        @DisplayName("the real disclosure-group row confirms the 16-byte key by where its overpunch sits")
        void theDisclosureGroupFixtureConfirmsTheSixteenByteKey() {
            assertThat(DISCLOSURE_GROUP_FIXTURE_ROW).hasSize(50);
            assertThat(DISCLOSURE_GROUP_FIXTURE_ROW.charAt(21)).isEqualTo('{');

            RecordLayout layout = disclosureGroupLayout();
            byte[] row = DISCLOSURE_GROUP_FIXTURE_ROW.getBytes(ASCII);
            Map<String, String> images = codec.deserialise(layout, row);

            assertThat(images)
                    .containsEntry("DIS-ACCT-GROUP-ID", "A000000000")
                    .containsEntry("DIS-TRAN-TYPE-CD", "01")
                    .containsEntry("DIS-TRAN-CAT-CD", "0001")
                    .containsEntry("DIS-INT-RATE", "00150{");
            assertThat(images).doesNotContainKey("FILLER").hasSize(4);
            assertThat(DISCLOSURE_GROUP_FIXTURE_ROW.substring(22)).isEqualTo(runOf('0', 28));

            assertThat(codec.readMonetary(codec.wrap(row, layout), layout.span("DIS-INT-RATE")))
                    .isEqualTo(new BigDecimal("15.00"));
        }

        @Test
        @DisplayName("the real transaction-category-balance row confirms the 17-byte key")
        void theTranCatBalFixtureConfirmsTheSeventeenByteKey() {
            assertThat(TRAN_CAT_BAL_FIXTURE_ROW).hasSize(50);
            assertThat(TRAN_CAT_BAL_FIXTURE_ROW.substring(0, TRAN_CAT_KEY_WIDTH))
                    .isEqualTo("00000000001010001");

            RecordLayout layout = tranCatBalLayout();
            byte[] row = TRAN_CAT_BAL_FIXTURE_ROW.getBytes(ASCII);
            Map<String, String> images = codec.deserialise(layout, row);

            assertThat(images)
                    .containsEntry("TRANCAT-ACCT-ID", "00000000001")
                    .containsEntry("TRANCAT-TYPE-CD", "01")
                    .containsEntry("TRANCAT-CD", "0001")
                    .containsEntry("TRAN-CAT-BAL", "0000000000{");
            assertThat(codec.readMonetary(codec.wrap(row, layout), layout.span("TRAN-CAT-BAL")))
                    .isEqualTo(new BigDecimal("0.00"));
            assertThat(TRAN_CAT_BAL_FIXTURE_ROW.substring(28)).isEqualTo(runOf('0', 22));
        }

        @Test
        @DisplayName("a record of the wrong width is rejected on the way in, in both directions")
        void wrapRejectsAnyWidthMismatch() {
            RecordLayout layout = cardXrefLayout();
            byte[] tooShort = CARD_XREF_FIXTURE_ROW.getBytes(ASCII);
            byte[] tooLong = padRight(CARD_XREF_FIXTURE_ROW, 51).getBytes(ASCII);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> codec.wrap(tooShort, layout))
                    .withMessageContaining("36");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> codec.wrap(tooLong, layout))
                    .withMessageContaining("51");
            assertThatNullPointerException().isThrownBy(() -> codec.wrap(null, layout));
            assertThatNullPointerException()
                    .isThrownBy(() -> codec.wrap(tooShort, null));

            byte[] exact = codec.padToDeclaredWidth(tooShort, CARD_XREF_DECLARED_WIDTH);
            assertThat(codec.wrap(exact, layout).recordLength()).isEqualTo(CARD_XREF_DECLARED_WIDTH);
        }
    }

    @Nested
    @DisplayName("Widening a short row - the 36-byte cardxref deviation (gate G16, risk R-F)")
    class ShortRowNormalisation {
        @Test
        @DisplayName("a 36-byte cross-reference row widens to the declared 50 with a 14-space tail")
        void cardXrefRowWidensFromThirtySixToFifty() {
            assertThat(CARD_XREF_FIXTURE_ROW).hasSize(CARD_XREF_FIXTURE_WIDTH);
            assertThat(CARD_XREF_DECLARED_WIDTH - CARD_XREF_FIXTURE_WIDTH).isEqualTo(14);

            byte[] widened = codec.padToDeclaredWidth(CARD_XREF_FIXTURE_ROW.getBytes(ASCII),
                    CARD_XREF_DECLARED_WIDTH);
            String image = new String(widened, ASCII);

            assertThat(widened).hasSize(CARD_XREF_DECLARED_WIDTH);
            assertThat(image.substring(0, CARD_XREF_FIXTURE_WIDTH)).isEqualTo(CARD_XREF_FIXTURE_ROW);
            assertThat(image.substring(CARD_XREF_FIXTURE_WIDTH)).isEqualTo(runOf(' ', 14));
        }

        @Test
        @DisplayName("the three named fields decode identically before and after the widening")
        void theNamedFieldsDecodeIdenticallyEitherSideOfTheWidening() {
            byte[] unpadded = CARD_XREF_FIXTURE_ROW.getBytes(ASCII);
            Map<String, String> before = codec.deserialise(cardXrefFixtureLayout(), unpadded);

            byte[] widened = codec.padToDeclaredWidth(unpadded, CARD_XREF_DECLARED_WIDTH);
            Map<String, String> after = codec.deserialise(cardXrefLayout(), widened);

            assertThat(after).isEqualTo(before);
            assertThat(after.keySet())
                    .containsExactly("XREF-CARD-NUM", "XREF-CUST-ID", "XREF-ACCT-ID");
            assertThat(after)
                    .containsEntry("XREF-CARD-NUM", "0500024453765740")
                    .containsEntry("XREF-CUST-ID", "000000050")
                    .containsEntry("XREF-ACCT-ID", "00000000050");

            RecordLayout declared = cardXrefLayout();
            FixedWidthRecord record = codec.wrap(widened, declared);
            assertThat(codec.readPic9(record, declared.span("XREF-CUST-ID"))).isEqualTo(50L);
            assertThat(codec.readPic9(record, declared.span("XREF-ACCT-ID"))).isEqualTo(50L);
            assertThat(codec.readPicX(record, declared.span("XREF-CARD-NUM")))
                    .isEqualTo("0500024453765740");
        }

        @Test
        @DisplayName("widening is idempotent, and an over-long row is rejected rather than truncated")
        void wideningIsIdempotentAndNeverTruncates() {
            byte[] widened = codec.padToDeclaredWidth(CARD_XREF_FIXTURE_ROW.getBytes(ASCII),
                    CARD_XREF_DECLARED_WIDTH);

            assertThat(codec.padToDeclaredWidth(widened, CARD_XREF_DECLARED_WIDTH)).isEqualTo(widened);

            byte[] tooLong = padRight(CARD_XREF_FIXTURE_ROW, 51).getBytes(ASCII);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> codec.padToDeclaredWidth(tooLong, CARD_XREF_DECLARED_WIDTH))
                    .withMessageContaining("never truncates");
        }

        @Test
        @DisplayName("the character-level overload widens, is idempotent and refuses to truncate too")
        void theCharacterOverloadBehavesIdentically() {
            String widened = codec.padToDeclaredWidth(CARD_XREF_FIXTURE_ROW, CARD_XREF_DECLARED_WIDTH);

            assertThat(widened).hasSize(CARD_XREF_DECLARED_WIDTH);
            assertThat(widened).startsWith(CARD_XREF_FIXTURE_ROW);
            assertThat(widened).endsWith(runOf(' ', 14));
            assertThat(codec.padToDeclaredWidth(widened, CARD_XREF_DECLARED_WIDTH)).isEqualTo(widened);

            String tooLong = padRight(CARD_XREF_FIXTURE_ROW, 51);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> codec.padToDeclaredWidth(tooLong, CARD_XREF_DECLARED_WIDTH))
                    .withMessageContaining("never truncates");
        }

        @Test
        @DisplayName("the same operation serves the USRSEC 57-to-80 widening, the width being a parameter")
        void theSameOperationServesTheOtherMeasuredDeviation() {
            String seedRow = padRight("USER0001", 57);
            String widened = codec.padToDeclaredWidth(seedRow, 80);

            assertThat(widened).hasSize(80);
            assertThat(widened.substring(0, 57)).isEqualTo(seedRow);
            assertThat(widened.substring(57)).isEqualTo(runOf(' ', 23));
        }

        @Test
        @DisplayName("a non-positive declared width and a null row are both rejected, on both overloads")
        void wideningGuards() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> codec.padToDeclaredWidth("row", 0))
                    .withMessageContaining("declared record width");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> codec.padToDeclaredWidth(new byte[] {0x31}, 0))
                    .withMessageContaining("declared record width");
            assertThatNullPointerException()
                    .isThrownBy(() -> codec.padToDeclaredWidth((String) null, 50))
                    .withMessageContaining("row image is required");
            assertThatNullPointerException()
                    .isThrownBy(() -> codec.padToDeclaredWidth((byte[]) null, 50))
                    .withMessageContaining("row is required");
        }
    }

    @Nested
    @DisplayName("Generated output widths - taken from the JCL LRECL declarations (gate G20)")
    class JclDeclaredOutputWidths {
        @ParameterizedTest(name = "{0} records are exactly {1} bytes")
        @CsvSource({
                "DALYREJS, 430",
                "TRANREPT, 133",
                "STMTFILE, 80",
                "HTMLFILE, 100",
                "TRANSACT, 350"})
        @DisplayName("every generated record is exactly its declared LRECL, padded or truncated to it")
        void everyGeneratedOutputMatchesItsDeclaredLrecl(String ddName, int lrecl) {
            String spanName = ddName + "-LINE";
            RecordLayout layout = outputLineLayout(spanName, lrecl);
            String line = "Line written to " + ddName;

            byte[] encoded = codec.serialise(layout, Map.of(spanName, line));
            String image = new String(encoded, ASCII);

            assertThat(encoded).hasSize(lrecl);
            assertThat(image).startsWith(line);
            assertThat(image.substring(line.length())).isEqualTo(runOf(' ', lrecl - line.length()));

            assertThat(codec.movePicX(line, lrecl)).hasSize(lrecl);
            assertThat(codec.movePicX(runOf('#', lrecl + 25), lrecl)).isEqualTo(runOf('#', lrecl));
            assertThat(codec.padToDeclaredWidth(line, lrecl)).hasSize(lrecl);
        }

        @Test
        @DisplayName("HTMLFILE is 100 bytes and not 80: the creating step beats the pre-delete step")
        void htmlFileIsOneHundredBytesNotEighty() {
            RecordLayout html = outputLineLayout("HTML-LINE", 100);

            assertThat(html.recordLength()).isEqualTo(100);
            assertThat(codec.serialise(html, Map.of())).hasSize(100);
            assertThat(codec.padToDeclaredWidth("<html>", 100)).hasSize(100);
            assertThat(codec.padToDeclaredWidth("<html>", 100))
                    .isNotEqualTo(codec.padToDeclaredWidth("<html>", 80));

            assertThat(codec.serialise(outputLineLayout("STMT-LINE", 80), Map.of())).hasSize(80);
        }

        @Test
        @DisplayName("DALYREJS 430 is a 350-byte record plus an 80-byte trailer, exactly as declared")
        void dalyRejsIsARejectRecordPlusAValidationTrailer() {
            RecordLayout reject = RecordLayout.of(430,
                    FieldSpan.alphanumeric("FD-REJECT-RECORD", 0, 350),
                    FieldSpan.alphanumeric("FD-VALIDATION-TRAILER", 350, 80));

            assertThat(reject.recordLength()).isEqualTo(430);
            assertThat(tranRecordLayout().recordLength() + 80).isEqualTo(430);

            byte[] encoded = codec.serialise(reject,
                    Map.of("FD-VALIDATION-TRAILER", "Card not found"));
            String image = new String(encoded, ASCII);

            assertThat(encoded).hasSize(430);
            assertThat(image.substring(0, 350)).isEqualTo(runOf(' ', 350));
            assertThat(image.substring(350)).isEqualTo(padRight("Card not found", 80));
        }

        @Test
        @DisplayName("the TRANSACT output width is the same 350 the transaction copybook declares")
        void transactOutputWidthAgreesWithTheCopybook() {
            assertThat(tranRecordLayout().recordLength()).isEqualTo(350);
            assertThat(codec.serialise(tranRecordLayout(), Map.of())).hasSize(350);
            assertThat(tranRecordLayout().span("TRAN-AMT").length()).isEqualTo(11);
        }
    }

    @Nested
    @DisplayName("Whole-record serialise and deserialise, over real fixture rows")
    class WholeRecordRoundTrip {
        @Test
        @DisplayName("the first account row decomposes into its twelve named images, FILLER excluded")
        void theFirstAccountRowDecomposesIntoItsNamedImages() {
            String row = firstAccountFixtureRow();
            assertThat(row).hasSize(300);

            Map<String, String> images = codec.deserialise(accountRecordLayout(), row.getBytes(ASCII));

            assertThat(images).hasSize(12);
            assertThat(images.keySet()).containsExactly(
                    "ACCT-ID", "ACCT-ACTIVE-STATUS", "ACCT-CURR-BAL", "ACCT-CREDIT-LIMIT",
                    "ACCT-CASH-CREDIT-LIMIT", "ACCT-OPEN-DATE", "ACCT-EXPIRAION-DATE",
                    "ACCT-REISSUE-DATE", "ACCT-CURR-CYC-CREDIT", "ACCT-CURR-CYC-DEBIT",
                    "ACCT-ADDR-ZIP", "ACCT-GROUP-ID");
            assertThat(images)
                    .containsEntry("ACCT-ID", "00000000001")
                    .containsEntry("ACCT-ACTIVE-STATUS", "Y")
                    .containsEntry("ACCT-CURR-BAL", FIRST_ACCOUNT_BALANCE_IMAGE)
                    .containsEntry("ACCT-CREDIT-LIMIT", "00000020200{")
                    .containsEntry("ACCT-CASH-CREDIT-LIMIT", "00000010200{")
                    .containsEntry("ACCT-OPEN-DATE", "2014-11-20")
                    .containsEntry("ACCT-EXPIRAION-DATE", "2025-05-20")
                    .containsEntry("ACCT-REISSUE-DATE", "2025-05-20")
                    .containsEntry("ACCT-CURR-CYC-CREDIT", "00000000000{")
                    .containsEntry("ACCT-CURR-CYC-DEBIT", "00000000000{")
                    .containsEntry("ACCT-ADDR-ZIP", "A000000000")
                    .containsEntry("ACCT-GROUP-ID", runOf(' ', 10));
            assertThat(images).doesNotContainKey("FILLER");

            RecordLayout layout = accountRecordLayout();
            FixedWidthRecord record = codec.wrap(row.getBytes(ASCII), layout);
            assertThat(codec.readMonetary(record, layout.span("ACCT-CURR-BAL")))
                    .isEqualTo(FIRST_ACCOUNT_BALANCE);
            assertThat(codec.readMonetary(record, layout.span("ACCT-CREDIT-LIMIT")))
                    .isEqualTo(new BigDecimal("2020.00"));
            assertThat(codec.readMonetary(record, layout.span("ACCT-CURR-CYC-DEBIT")))
                    .isEqualTo(new BigDecimal("0.00"));
            assertThat(codec.readPic9(record, layout.span("ACCT-ID"))).isEqualTo(1L);
        }

        @Test
        @DisplayName("the misspelled field name is the contract: the corrected spelling is rejected")
        void theMisspelledFieldNameIsTheContract() {
            RecordLayout layout = accountRecordLayout();
            assertThat(layout.hasSpan("ACCT-EXPIRAION-DATE")).isTrue();
            assertThat(layout.hasSpan("ACCT-EXPIRATION-DATE")).isFalse();

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> codec.serialise(layout,
                            Map.of("ACCT-EXPIRATION-DATE", "2025-05-20")))
                    .withMessageContaining("declares no field named");
        }

        @Test
        @DisplayName("decode then re-encode is byte-identical when the stored record is carried through")
        void theRecordPreservingRoundTripIsByteIdentical() {
            assertRoundTripsExactly(accountRecordLayout(), firstAccountFixtureRow());
            assertRoundTripsExactly(tranCatBalLayout(), TRAN_CAT_BAL_FIXTURE_ROW);
            assertRoundTripsExactly(disclosureGroupLayout(), DISCLOSURE_GROUP_FIXTURE_ROW);
        }

        private void assertRoundTripsExactly(RecordLayout layout, String row) {
            byte[] stored = row.getBytes(ASCII);
            byte[] rebuilt = codec.serialise(layout, codec.deserialise(layout, stored), stored);

            assertThat(rebuilt).isEqualTo(stored);
            assertThat(new String(rebuilt, ASCII)).isEqualTo(row);
        }

        @Test
        @DisplayName("rebuilding from the layout alone blanks a zero-filled reserved span, by 28 bytes")
        void rebuildingFromTheLayoutAloneReplacesAZeroFilledReservedSpan() {
            RecordLayout layout = disclosureGroupLayout();
            byte[] stored = DISCLOSURE_GROUP_FIXTURE_ROW.getBytes(ASCII);
            byte[] fromLayoutOnly = codec.serialise(layout, codec.deserialise(layout, stored));
            String image = new String(fromLayoutOnly, ASCII);

            assertThat(fromLayoutOnly).hasSize(50);
            assertThat(image.substring(0, 22)).isEqualTo(DISCLOSURE_GROUP_FIXTURE_ROW.substring(0, 22));
            assertThat(image.substring(22)).isEqualTo(runOf(' ', 28));
            assertThat(fromLayoutOnly).isNotEqualTo(stored);
            assertThat(countDifferences(stored, fromLayoutOnly)).isEqualTo(28);
        }

        @Test
        @DisplayName("an empty map yields the initialised record: zeros for numerics, spaces for text")
        void anEmptyMapYieldsTheInitialisedRecord() {
            RecordLayout layout = accountRecordLayout();
            byte[] initialised = codec.serialise(layout, Map.of());
            String image = new String(initialised, ASCII);

            assertThat(initialised).hasSize(300);
            assertThat(codec.newRecord(layout).toByteArray()).isEqualTo(initialised);
            assertThat(image.substring(0, 11)).isEqualTo(runOf('0', 11));
            assertThat(image.substring(11, 12)).isEqualTo(" ");
            assertThat(image.substring(12, 24)).isEqualTo(runOf('0', 11) + "{");
            assertThat(image.substring(122)).isEqualTo(runOf(' ', 178));

            assertThat(codec.readMonetary(codec.wrap(initialised, layout), layout.span("ACCT-CURR-BAL")))
                    .isEqualTo(new BigDecimal("0.00"));
        }

        @Test
        @DisplayName("each image is written with the move rule for its own span's kind")
        void eachImageIsWrittenWithItsSpanKindsRule() {
            RecordLayout layout = accountRecordLayout();
            Map<String, String> values = new LinkedHashMap<>();
            values.put("ACCT-ID", "1");
            values.put("ACCT-GROUP-ID", "ABCDEFGHIJKL");
            values.put("ACCT-CURR-BAL", "1940{");

            String image = new String(codec.serialise(layout, values), ASCII);

            assertThat(image.substring(0, 11)).isEqualTo("00000000001");
            assertThat(image.substring(112, 122)).isEqualTo("ABCDEFGHIJ");
            assertThat(image.substring(12, 24)).isEqualTo(FIRST_ACCOUNT_BALANCE_IMAGE);
            assertThat(codec.readMonetary(codec.wrap(image.getBytes(ASCII), layout),
                    layout.span("ACCT-CURR-BAL"))).isEqualTo(FIRST_ACCOUNT_BALANCE);
        }

        @Test
        @DisplayName("an over-wide, empty or malformed signed image is rejected, never truncated")
        void signedImageGuards() {
            RecordLayout layout = accountRecordLayout();

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> codec.serialise(layout,
                            Map.of("ACCT-CURR-BAL", "0000000001940{")))
                    .withMessageContaining("encodeSignedScaled");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> codec.serialise(layout, Map.of("ACCT-CURR-BAL", "")))
                    .withMessageContaining("low-order digit");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> codec.serialise(layout,
                            Map.of("ACCT-CURR-BAL", "00000001940Z")))
                    .withMessageContaining("sign overpunch");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> codec.serialise(layout, Map.of("ACCT-ID", "0000000000A")))
                    .withMessageContaining("digits 0 to 9");
        }

        @Test
        @DisplayName("a null map, key, image, layout or record is rejected on every entry point")
        void nullsAreRejectedThroughout() {
            RecordLayout layout = accountRecordLayout();
            Map<String, String> withNullImage = new HashMap<>();
            withNullImage.put("ACCT-OPEN-DATE", null);
            Map<String, String> withNullKey = new HashMap<>();
            withNullKey.put(null, "2014-11-20");

            assertThatNullPointerException()
                    .isThrownBy(() -> codec.serialise(layout, withNullImage))
                    .withMessageContaining("null image");
            assertThatNullPointerException()
                    .isThrownBy(() -> codec.serialise(layout, withNullKey))
                    .withMessageContaining("null field name");
            assertThatNullPointerException()
                    .isThrownBy(() -> codec.serialise(layout, null))
                    .withMessageContaining("field image map is required");
            assertThatNullPointerException()
                    .isThrownBy(() -> codec.serialise(null, Map.of()))
                    .withMessageContaining("record layout is required");
            assertThatNullPointerException()
                    .isThrownBy(() -> codec.serialise(layout, Map.of(), null))
                    .withMessageContaining("Stored bytes are required");
            assertThatNullPointerException()
                    .isThrownBy(() -> codec.deserialise(null, new byte[300]))
                    .withMessageContaining("record layout is required");
            assertThatNullPointerException()
                    .isThrownBy(() -> codec.deserialise(layout, null))
                    .withMessageContaining("Stored bytes are required");
        }
    }

    @Nested
    @DisplayName("STRING ... DELIMITED BY SIZE - every operand contributes its full declared width")
    class StringDelimitedBySize {
        @Test
        @DisplayName("the interest calculator's transaction identifier is built exactly as COBOL builds it")
        void buildsTheInterestTransactionIdentifier() {
            String parmDate = codec.movePicX("2022071800", 10);
            String suffix = codec.movePic9(1L, 6);
            String tranId = codec.concatenateDelimitedBySize(parmDate, suffix);

            assertThat(suffix).isEqualTo("000001");
            assertThat(tranId).isEqualTo("2022071800000001");
            assertThat(tranId).hasSize(16);
            assertThat(tranId).hasSize(tranRecordLayout().span("TRAN-ID").length());
        }

        @Test
        @DisplayName("STRING INTO overlays at the start and leaves the rest of the span untouched")
        void stringIntoLeavesTheRemainderOfTheSpanUnchanged() {
            RecordLayout layout = tranRecordLayout();
            FieldSpan description = layout.span("TRAN-DESC");
            FixedWidthRecord record = codec.newRecord(layout);
            record.fill(description.offset(), description.length(), (byte) 'X');

            codec.stringIntoDelimitedBySize(record, description, "Int. for a/c ", "00000000001");

            String stored = codec.readPicX(record, description);
            assertThat(stored).hasSize(100);
            assertThat(stored.substring(0, 24)).isEqualTo("Int. for a/c 00000000001");
            assertThat(stored.substring(24)).isEqualTo(runOf('X', 76));
        }

        @Test
        @DisplayName("an over-long concatenation stops at the span's last character, with no exception")
        void anOverLongConcatenationStopsAtTheSpanEnd() {
            RecordLayout layout = tranRecordLayout();
            FieldSpan typeCode = layout.span("TRAN-TYPE-CD");
            FixedWidthRecord record = codec.newRecord(layout);

            codec.stringIntoDelimitedBySize(record, typeCode, "0123456789");

            assertThat(codec.readPicX(record, typeCode)).isEqualTo("01");
        }

        @Test
        @DisplayName("an empty operand transfers nothing and leaves the span exactly as it stood")
        void anEmptyOperandTransfersNothing() {
            RecordLayout layout = tranRecordLayout();
            FieldSpan description = layout.span("TRAN-DESC");
            FixedWidthRecord record = codec.newRecord(layout);
            record.fill(description.offset(), description.length(), (byte) 'X');

            codec.stringIntoDelimitedBySize(record, description, "");

            assertThat(codec.readPicX(record, description)).isEqualTo(runOf('X', 100));
        }

        @Test
        @DisplayName("nothing is trimmed: an operand's own padding is part of what it contributes")
        void operandPaddingIsPartOfTheContribution() {
            assertThat(codec.concatenateDelimitedBySize("AB   ", "CD")).isEqualTo("AB   CD");
            assertThat(codec.concatenateDelimitedBySize("System    ")).isEqualTo("System    ");
            assertThat(codec.concatenateDelimitedBySize("A", "B", "C", "D")).isEqualTo("ABCD");
        }

        @Test
        @DisplayName("at least one operand is required, and none of them may be null")
        void stringGuards() {
            RecordLayout layout = tranRecordLayout();
            FieldSpan description = layout.span("TRAN-DESC");
            FixedWidthRecord record = codec.newRecord(layout);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> codec.concatenateDelimitedBySize())
                    .withMessageContaining("at least one sending item");
            assertThatNullPointerException()
                    .isThrownBy(() -> codec.concatenateDelimitedBySize("AB", null))
                    .withMessageContaining("Sending item 2 of 2");
            assertThatNullPointerException()
                    .isThrownBy(() -> codec.concatenateDelimitedBySize((String[]) null));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> codec.stringIntoDelimitedBySize(record, description))
                    .withMessageContaining("at least one sending item");
            assertThatNullPointerException()
                    .isThrownBy(() -> codec.stringIntoDelimitedBySize(null, description, "AB"));
            assertThatNullPointerException()
                    .isThrownBy(() -> codec.stringIntoDelimitedBySize(record, null, "AB"));
        }
    }

    @Nested
    @DisplayName("firstUnrepresentableCodePoint - the question form of encodeImage")
    class FirstUnrepresentableCodePoint {
        private final FixedWidthCodec ascii = new FixedWidthCodec(ASCII);
        private final FixedWidthCodec ebcdic = new FixedWidthCodec(EBCDIC);

        @Test
        @DisplayName("an empty OptionalInt for a value the code page represents in full")
        void emptyForARepresentableValue() {
            assertThat(ascii.firstUnrepresentableCodePoint("SMITH JOHN A")).isEmpty();
        }

        @Test
        @DisplayName("an empty OptionalInt for the empty string, which has no character to judge")
        void emptyForTheEmptyString() {
            assertThat(ascii.firstUnrepresentableCodePoint("")).isEmpty();
        }

        @ParameterizedTest(name = "US-ASCII cannot represent U+{0}")
        @ValueSource(strings = {"00E9", "00D1", "20AC", "4E2D"})
        @DisplayName("reports the code point US-ASCII has no representation for")
        void reportsTheOffendingCodePoint(String hex) {
            int codePoint = Integer.parseInt(hex, 16);

            assertThat(ascii.firstUnrepresentableCodePoint(new String(Character.toChars(codePoint))))
                    .hasValue(codePoint);
        }

        @Test
        @DisplayName("reports the FIRST offender, not the last, so the answer is deterministic")
        void reportsTheFirstOffender() {
            assertThat(ascii.firstUnrepresentableCodePoint("A\u00E9B\u00D1C")).hasValue(0x00E9);
        }

        @Test
        @DisplayName("judges a surrogate pair as the one character it is, naming a code point that is "
                + "actually in the value rather than a lone surrogate")
        void judgesASurrogatePairAsOneCharacter() {
            String emoji = new String(Character.toChars(0x1F600));

            assertThat(emoji).hasSize(2);
            assertThat(ascii.firstUnrepresentableCodePoint("OK" + emoji)).hasValue(0x1F600);
            assertThat(ascii.firstUnrepresentableCodePoint(emoji)).hasValue(0x1F600);
        }

        @Test
        @DisplayName("answers per code page: IBM037 represents an accented Latin letter US-ASCII "
                + "cannot, so the question is only meaningful against a stated code page")
        void answersPerCodePage() {
            assertThat(ebcdic.firstUnrepresentableCodePoint("JOS\u00C9")).isEmpty();
            assertThat(ascii.firstUnrepresentableCodePoint("JOS\u00C9")).hasValue(0x00C9);
        }

        @Test
        @DisplayName("agrees with encodeImage: it is empty exactly when encodeImage would not refuse")
        void agreesWithEncodeImage() {
            for (String value : List.of("PLAIN", "JOS\u00C9", "", "MU\u00D1OZ", "0123456789")) {
                boolean judgedWritable = ascii.firstUnrepresentableCodePoint(value).isEmpty();
                boolean actuallyWritable;
                try {
                    ascii.encodeImage(value, "a test value");
                    actuallyWritable = true;
                } catch (IllegalArgumentException refused) {
                    actuallyWritable = false;
                }
                assertThat(judgedWritable)
                        .as("the question and the write path must agree for %s",
                                value.codePoints().boxed().toList())
                        .isEqualTo(actuallyWritable);
            }
        }

        @Test
        @DisplayName("rejects a null value rather than reporting it as representable")
        void rejectsNull() {
            assertThatNullPointerException()
                    .isThrownBy(() -> ascii.firstUnrepresentableCodePoint(null));
        }
    }

    @Nested
    @DisplayName("The public API shape - nothing bypasses the truncation-direction choice")
    class PublicApiShape {
        @Test
        @DisplayName("the only cross-width move operations are the two that name their direction")
        void onlyDirectionNamedMoveOperationsExist() {
            List<String> moveOperations = new ArrayList<>();
            for (Method method : FixedWidthCodec.class.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers()) && method.getName().startsWith("move")) {
                    moveOperations.add(method.getName());
                }
            }

            assertThat(moveOperations).isNotEmpty().containsOnly("movePicX", "movePic9");
        }

        @ParameterizedTest(name = "there is no public method named [{0}]")
        @ValueSource(strings = {"move", "assign", "set", "put", "copy", "transfer", "store",
                "moveTo", "moveInto", "write", "read"})
        @DisplayName("no direction-agnostic assignment operation is offered at all")
        void noDirectionAgnosticOperationExists(String forbidden) {
            List<String> publicNames = new ArrayList<>();
            for (Method method : FixedWidthCodec.class.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers())) {
                    publicNames.add(method.getName());
                }
            }

            assertThat(publicNames)
                    .as("a method named '%s' would let a caller move across widths without choosing "
                            + "a truncation direction", forbidden)
                    .doesNotContain(forbidden);
        }

        @Test
        @DisplayName("the layer below refuses to truncate, so an implicit cross-width move is impossible")
        void theLayerBelowRefusesToTruncate() {
            RecordLayout layout = accountRecordLayout();
            FieldSpan groupId = layout.span("ACCT-GROUP-ID");
            FixedWidthRecord record = codec.newRecord(layout);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> record.writeSpan(groupId, "ABCDEFGHIJKL"))
                    .withMessageContaining("never truncates");

            assertThat(codec.movePicX("1234567890", 4)).isEqualTo("1234");
            assertThat(codec.movePic9("1234567890", 4)).isEqualTo("7890");

            record.writeSpan(groupId, codec.movePicX("ABCDEFGHIJKL", groupId.length()));
            assertThat(codec.readPicX(record, groupId)).isEqualTo("ABCDEFGHIJ");
        }

        @Test
        @DisplayName("gate G22: no double or float appears anywhere in the codec's signatures")
        void noBinaryFloatingPointInTheApi() {
            for (Method method : FixedWidthCodec.class.getDeclaredMethods()) {
                assertThat(method.getReturnType())
                        .as("return type of %s", method.getName())
                        .isNotIn(double.class, float.class, Double.class, Float.class);
                assertThat(method.getParameterTypes())
                        .as("parameter types of %s", method.getName())
                        .doesNotContain(double.class, float.class, Double.class, Float.class);
            }
        }
    }
}
