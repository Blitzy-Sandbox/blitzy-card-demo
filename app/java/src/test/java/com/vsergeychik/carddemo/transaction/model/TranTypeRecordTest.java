package com.vsergeychik.carddemo.transaction.model;

import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link TranTypeRecord}, the {@code TRANTYPE} transaction-type record that
 * {@code app/cpy/CVTRA03Y.cpy} declares in eleven lines and 60 bytes.
 */
@DisplayName("TranTypeRecord - CVTRA03Y TRAN-TYPE-RECORD, 60 bytes, keyed on TRAN-TYPE X(02)")
class TranTypeRecordTest {
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    private static final Charset EBCDIC = Charset.forName("IBM037");

    private static final String ROW_01 =
            "01Purchase                                          00000000";

    private static final String ROW_02 =
            "02Payment                                           00000000";

    private static final String ROW_03 =
            "03Credit                                            00000000";

    private static final String ROW_04 =
            "04Authorization                                     00000000";

    private static final String ROW_05 =
            "05Refund                                            00000000";

    private static final String ROW_06 =
            "06Reversal                                          00000000";

    private static final String ROW_07 =
            "07Adjustment                                        00000000";

    private static final List<String> FIXTURE_ROWS =
            List.of(ROW_01, ROW_02, ROW_03, ROW_04, ROW_05, ROW_06, ROW_07);

    private static final List<String> FIXTURE_KEYS =
            List.of("01", "02", "03", "04", "05", "06", "07");

    private static final List<String> FIXTURE_DESCRIPTIONS =
            List.of("Purchase", "Payment", "Credit", "Authorization", "Refund", "Reversal",
                    "Adjustment");

    private static final String FIXTURE_FILLER = "00000000";

    private static final int LONGEST_SHIPPED_DESCRIPTION = 13;

    private static String fixtureRow(int index) {
        return FIXTURE_ROWS.get(index);
    }

    private static String padded(String value, int width) {
        return value + " ".repeat(width - value.length());
    }

    private static void assertSpanAt(byte[] image, int offset, String expected, String what) {
        byte[] expectedBytes = expected.getBytes(ASCII);
        for (int i = 0; i < expectedBytes.length; i++) {
            assertThat(image[offset + i])
                    .as("%s byte at absolute offset %d", what, offset + i)
                    .isEqualTo(expectedBytes[i]);
        }
    }

    @Nested
    @DisplayName("The embedded fixture is a faithful transcription of app/data/ASCII/trantype.txt")
    class EmbeddedFixtureProvenance {
        @Test
        @DisplayName("seven rows are embedded, and every one is exactly 60 characters")
        void sevenRowsOfSixtyCharacters() {
            assertThat(FIXTURE_ROWS).hasSize(7);
            assertThat(FIXTURE_KEYS).hasSize(7);
            assertThat(FIXTURE_DESCRIPTIONS).hasSize(7);
            assertThat(FIXTURE_ROWS).allSatisfy(row ->
                    assertThat(row).hasSize(TranTypeRecord.RECORD_LENGTH));
            assertThat(7 * TranTypeRecord.RECORD_LENGTH + 7).isEqualTo(427);
        }

        @DisplayName("each row literal re-derives from its key, its description and its FILLER")
        @ParameterizedTest(name = "row {0} = \"{1}\" + \"{2}\" padded to 50 + 8 zeros")
        @CsvSource({
                "0, 01, Purchase",
                "1, 02, Payment",
                "2, 03, Credit",
                "3, 04, Authorization",
                "4, 05, Refund",
                "5, 06, Reversal",
                "6, 07, Adjustment"
        })
        void eachRowLiteralIsTranscribedCorrectly(int index, String key, String description) {
            String composed = key + padded(description, TranTypeRecord.TRAN_TYPE_DESC_LENGTH)
                    + FIXTURE_FILLER;

            assertThat(fixtureRow(index)).isEqualTo(composed);
            assertThat(FIXTURE_KEYS.get(index)).isEqualTo(key);
            assertThat(FIXTURE_DESCRIPTIONS.get(index)).isEqualTo(description);
        }

        @Test
        @DisplayName("the padding is on the right only, as a PIC X field is padded")
        void thePaddingIsOnTheRight() {
            for (int index = 0; index < FIXTURE_ROWS.size(); index++) {
                String description = FIXTURE_DESCRIPTIONS.get(index);
                byte[] image = fixtureRow(index).getBytes(ASCII);

                assertSpanAt(image, TranTypeRecord.TRAN_TYPE_DESC_OFFSET, description,
                        "TRAN-TYPE-DESC");
                for (int i = TranTypeRecord.TRAN_TYPE_DESC_OFFSET + description.length();
                        i < TranTypeRecord.FILLER_OFFSET; i++) {
                    assertThat(image[i])
                            .as("TRAN-TYPE-DESC pad byte at absolute offset %d", i)
                            .isEqualTo((byte) ' ');
                }
            }
        }

        @Test
        @DisplayName("no shipped description reaches the 15-byte report receiver's width")
        void noShippedDescriptionExercisesTheTruncation() {
            for (String description : FIXTURE_DESCRIPTIONS) {
                assertThat(description.length()).isLessThanOrEqualTo(LONGEST_SHIPPED_DESCRIPTION);
                assertThat(description.length()).isLessThan(TranTypeRecord.REPORT_TYPE_DESC_LENGTH);
            }
            assertThat(FIXTURE_DESCRIPTIONS)
                    .anySatisfy(longest ->
                            assertThat(longest.length()).isEqualTo(LONGEST_SHIPPED_DESCRIPTION));
        }

        @Test
        @DisplayName("every row's reserved span is eight ASCII zeros, at absolute offset 52")
        void everyRowsReservedSpanIsZeros() {
            for (String row : FIXTURE_ROWS) {
                assertSpanAt(row.getBytes(ASCII), TranTypeRecord.FILLER_OFFSET, FIXTURE_FILLER,
                        "FILLER");
            }
            assertThat(FIXTURE_FILLER).hasSize(TranTypeRecord.FILLER_LENGTH);
        }
    }

    @Nested
    @DisplayName("Declared geometry - the copybook's 2 + 50 + 8 = 60, gates G19 and G21")
    class DeclaredGeometry {
        @Test
        @DisplayName("the copybook's RECLN = 60 is the record length, and the layout proves it")
        void recordLengthIsSixty() {
            assertThat(TranTypeRecord.RECORD_LENGTH).isEqualTo(60);
            assertThat(TranTypeRecord.verifyDeclaredWidth()).isEqualTo(60);
            assertThat(TranTypeRecord.layout().recordLength()).isEqualTo(60);
        }

        @Test
        @DisplayName("2 + 50 + 8 = 60: the three declared lengths sum to the declared width")
        void theThreeLengthsSumToSixty() {
            assertThat(TranTypeRecord.TRAN_TYPE_LENGTH
                    + TranTypeRecord.TRAN_TYPE_DESC_LENGTH
                    + TranTypeRecord.FILLER_LENGTH)
                    .isEqualTo(TranTypeRecord.RECORD_LENGTH);
            assertThat(TranTypeRecord.TRAN_TYPE_OFFSET).isZero();
            assertThat(TranTypeRecord.TRAN_TYPE_DESC_OFFSET)
                    .isEqualTo(TranTypeRecord.TRAN_TYPE_OFFSET + TranTypeRecord.TRAN_TYPE_LENGTH);
            assertThat(TranTypeRecord.FILLER_OFFSET)
                    .isEqualTo(TranTypeRecord.TRAN_TYPE_DESC_OFFSET
                            + TranTypeRecord.TRAN_TYPE_DESC_LENGTH);
            assertThat(TranTypeRecord.FILLER_OFFSET + TranTypeRecord.FILLER_LENGTH)
                    .isEqualTo(TranTypeRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("offsets are the copybook's 1-based positions converted to 0-based")
        void offsetsMatchTheCopybook() {
            assertThat(TranTypeRecord.TRAN_TYPE_OFFSET).isZero();
            assertThat(TranTypeRecord.TRAN_TYPE_DESC_OFFSET).isEqualTo(2);
            assertThat(TranTypeRecord.FILLER_OFFSET).isEqualTo(52);

            assertThat(TranTypeRecord.TRAN_TYPE.offset()).isZero();
            assertThat(TranTypeRecord.TRAN_TYPE.length()).isEqualTo(2);
            assertThat(TranTypeRecord.TRAN_TYPE.endOffsetExclusive()).isEqualTo(2);
            assertThat(TranTypeRecord.TRAN_TYPE_DESC.offset()).isEqualTo(2);
            assertThat(TranTypeRecord.TRAN_TYPE_DESC.length()).isEqualTo(50);
            assertThat(TranTypeRecord.TRAN_TYPE_DESC.endOffsetExclusive()).isEqualTo(52);
            assertThat(TranTypeRecord.FILLER.offset()).isEqualTo(52);
            assertThat(TranTypeRecord.FILLER.length()).isEqualTo(8);
            assertThat(TranTypeRecord.FILLER.endOffsetExclusive())
                    .isEqualTo(TranTypeRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("field names are the copybook's own: TRAN-TYPE, not CVTRA04Y's TRAN-TYPE-CD")
        void fieldNamesAreVerbatim() {
            assertThat(TranTypeRecord.TRAN_TYPE_FIELD).isEqualTo("TRAN-TYPE");
            assertThat(TranTypeRecord.TRAN_TYPE_DESC_FIELD).isEqualTo("TRAN-TYPE-DESC");
            assertThat(TranTypeRecord.TRAN_TYPE.name()).isEqualTo("TRAN-TYPE");
            assertThat(TranTypeRecord.TRAN_TYPE_DESC.name()).isEqualTo("TRAN-TYPE-DESC");
            assertThat(TranTypeRecord.TRAN_TYPE_FIELD).isNotEqualTo("TRAN-TYPE-CD");
            assertThat(TranTypeRecord.TRAN_TYPE.name()).doesNotEndWith("-CD");
        }

        @Test
        @DisplayName("gate G21: FILLER X(08) is a declared span, so the layout accounts for all 60")
        void fillerIsAFirstClassSpan() {
            assertThat(TranTypeRecord.FILLER.name()).isEqualTo("FILLER");
            assertThat(TranTypeRecord.FILLER.kind().filler()).isTrue();
            assertThat(TranTypeRecord.layout().storageSpans())
                    .containsExactly(TranTypeRecord.TRAN_TYPE, TranTypeRecord.TRAN_TYPE_DESC,
                            TranTypeRecord.FILLER);
            assertThat(TranTypeRecord.layout().hasSpan("FILLER")).isFalse();
        }

        @Test
        @DisplayName("gate G21 in the negative: dropping the FILLER leaves 52 and is rejected")
        void droppingTheFillerIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(TranTypeRecord.RECORD_LENGTH,
                            TranTypeRecord.TRAN_TYPE, TranTypeRecord.TRAN_TYPE_DESC))
                    .withMessageContaining("52")
                    .withMessageContaining("60");
        }

        @Test
        @DisplayName("no OCCURS table and no REDEFINES overlay: CVTRA03Y declares neither")
        void thereIsNoTableAndNoOverlay() {
            assertThat(TranTypeRecord.layout().redefinitions()).isEmpty();
            assertThat(TranTypeRecord.layout().spans()).hasSize(3);
        }

        @Test
        @DisplayName("the KSDS key is the leading 2 bytes, as CBTRN03C:42 declares")
        void theKeyIsTheLeadingTwoBytes() {
            assertThat(TranTypeRecord.TRAN_TYPE_KEY_LENGTH).isEqualTo(2);
            assertThat(TranTypeRecord.TRAN_TYPE_KEY_LENGTH)
                    .isEqualTo(TranTypeRecord.TRAN_TYPE_LENGTH);
            assertThat(TranTypeRecord.TRAN_TYPE.offset()).isZero();
            assertThat(TranTypeRecord.TRAN_TYPE.endOffsetExclusive())
                    .isEqualTo(TranTypeRecord.TRAN_TYPE_KEY_LENGTH);
        }

        @Test
        @DisplayName("the report receiver is CVTRA07Y:22's PIC X(15), narrower than the field")
        void theReportReceiverIsFifteen() {
            assertThat(TranTypeRecord.REPORT_TYPE_DESC_LENGTH).isEqualTo(15);
            assertThat(TranTypeRecord.REPORT_TYPE_DESC_LENGTH)
                    .isLessThan(TranTypeRecord.TRAN_TYPE_DESC_LENGTH);
        }

        @Test
        @DisplayName("the width self-check rejects a layout that is internally valid but too short")
        void aShortLayoutIsRejected() {
            RecordLayout fifty = RecordLayout.of(50, FieldSpan.alphanumeric("SOMETHING-ELSE", 0, 50));
            assertThatIllegalStateException()
                    .isThrownBy(() -> TranTypeRecord.verifyDeclaredWidth(fifty))
                    .withMessageContaining("50")
                    .withMessageContaining("short");
        }

        @Test
        @DisplayName("the width self-check rejects a layout that is too long")
        void anOverLongLayoutIsRejected() {
            RecordLayout seventy = RecordLayout.of(70, FieldSpan.alphanumeric("TOO-WIDE", 0, 70));
            assertThatIllegalStateException()
                    .isThrownBy(() -> TranTypeRecord.verifyDeclaredWidth(seventy))
                    .withMessageContaining("70")
                    .withMessageContaining("too many");
        }

        @Test
        @DisplayName("the width self-check requires a layout, and its own layout passes it")
        void aNullLayoutIsRejected() {
            assertThatNullPointerException()
                    .isThrownBy(() -> TranTypeRecord.verifyDeclaredWidth(null))
                    .withMessageContaining("CVTRA03Y");
            assertThat(TranTypeRecord.verifyDeclaredWidth(TranTypeRecord.LAYOUT))
                    .isEqualTo(TranTypeRecord.RECORD_LENGTH);
        }
    }

    @Nested
    @DisplayName("The seven shipped TRANTYPE records, decoded at the copybook's offsets")
    class ShippedFixture {
        @DisplayName("each row decodes to its documented key, description and reserved bytes")
        @ParameterizedTest(name = "row {0}: TRAN-TYPE {1} = {2}")
        @CsvSource({
                "0, 01, Purchase",
                "1, 02, Payment",
                "2, 03, Credit",
                "3, 04, Authorization",
                "4, 05, Refund",
                "5, 06, Reversal",
                "6, 07, Adjustment"
        })
        void eachFixtureRowDecodesAsDocumented(int index, String key, String description) {
            TranTypeRecord record = TranTypeRecord.decode(fixtureRow(index), ASCII);

            assertThat(record.tranType()).isEqualTo(key);
            assertThat(record.tranTypeKey()).isEqualTo(key);
            assertThat(record.tranTypeDesc())
                    .isEqualTo(padded(description, TranTypeRecord.TRAN_TYPE_DESC_LENGTH));
            assertThat(record.filler()).isEqualTo(FIXTURE_FILLER);
            assertThat(record.recordLength()).isEqualTo(TranTypeRecord.RECORD_LENGTH);
            assertThat(record.charset()).isEqualTo(ASCII);
        }

        @DisplayName("each row's three spans sit at absolute offsets 0, 2 and 52")
        @ParameterizedTest(name = "row {0}: key at 0, description at 2, FILLER at 52")
        @CsvSource({
                "0, 01, Purchase",
                "1, 02, Payment",
                "2, 03, Credit",
                "3, 04, Authorization",
                "4, 05, Refund",
                "5, 06, Reversal",
                "6, 07, Adjustment"
        })
        void eachRowsSpansSitAtTheirAbsoluteOffsets(int index, String key, String description) {
            byte[] image = TranTypeRecord.decode(fixtureRow(index), ASCII).toByteArray();

            assertThat(image).hasSize(TranTypeRecord.RECORD_LENGTH);
            assertSpanAt(image, TranTypeRecord.TRAN_TYPE_OFFSET, key, "TRAN-TYPE");
            assertSpanAt(image, TranTypeRecord.TRAN_TYPE_DESC_OFFSET,
                    padded(description, TranTypeRecord.TRAN_TYPE_DESC_LENGTH), "TRAN-TYPE-DESC");
            assertSpanAt(image, TranTypeRecord.FILLER_OFFSET, FIXTURE_FILLER, "FILLER");
        }

        @Test
        @DisplayName("every row re-encodes to exactly 60 bytes, byte-identical to the stored row")
        void everyRowRoundTripsByteForByte() {
            for (int index = 0; index < FIXTURE_ROWS.size(); index++) {
                String row = fixtureRow(index);
                TranTypeRecord record = TranTypeRecord.decode(row, ASCII);

                assertThat(record.toByteArray())
                        .as("row %d re-encodes to its declared width", index)
                        .hasSize(TranTypeRecord.RECORD_LENGTH);
                assertThat(record.toByteArray())
                        .as("row %d round trips byte for byte, reserved bytes included", index)
                        .isEqualTo(row.getBytes(ASCII));
                assertThat(record.image())
                        .as("row %d reproduces its 60-character image", index)
                        .isEqualTo(row);
                assertThat(record.image()).hasSize(TranTypeRecord.RECORD_LENGTH);
            }
        }

        @Test
        @DisplayName("the byte and text decode paths agree on every row")
        void bothDecodePathsAgree() {
            FixedWidthCodec codec = new FixedWidthCodec(ASCII);
            for (String row : FIXTURE_ROWS) {
                assertThat(TranTypeRecord.decode(row, ASCII))
                        .isEqualTo(TranTypeRecord.decode(row.getBytes(ASCII), codec))
                        .isEqualTo(TranTypeRecord.decode(row, codec))
                        .isEqualTo(TranTypeRecord.decode(row.getBytes(ASCII), ASCII));
            }
        }

        @Test
        @DisplayName("the seven keys and descriptions are exactly those documented, in file order")
        void theSevenRowsAreThoseDocumented() {
            List<String> keys = new ArrayList<>();
            List<String> descriptions = new ArrayList<>();
            List<String> paddedDescriptions = new ArrayList<>();
            for (String row : FIXTURE_ROWS) {
                TranTypeRecord record = TranTypeRecord.decode(row, ASCII);
                keys.add(record.tranTypeKey());
                descriptions.add(record.tranTypeDesc());
            }
            for (String description : FIXTURE_DESCRIPTIONS) {
                paddedDescriptions.add(padded(description, TranTypeRecord.TRAN_TYPE_DESC_LENGTH));
            }

            assertThat(keys).containsExactlyElementsOf(FIXTURE_KEYS);
            assertThat(descriptions).containsExactlyElementsOf(paddedDescriptions);
        }
    }

    @Nested
    @DisplayName("TRAN-TYPE-DESC is decoded untrimmed - the CBTRN03C:366 X(50) to X(15) move")
    class UntrimmedDescription {
        @Test
        @DisplayName("the decoded description is exactly 50 characters, padding included")
        void theDescriptionIsFiftyCharacters() {
            for (String row : FIXTURE_ROWS) {
                TranTypeRecord record = TranTypeRecord.decode(row, ASCII);
                assertThat(record.tranTypeDesc()).hasSize(TranTypeRecord.TRAN_TYPE_DESC_LENGTH);
                assertThat(record.tranTypeDescBytes()).hasSize(TranTypeRecord.TRAN_TYPE_DESC_LENGTH);
            }
        }

        @Test
        @DisplayName("row 1 reads back as \"Purchase\" followed by 42 spaces, not as \"Purchase\"")
        void rowOneKeepsItsFortyTwoTrailingSpaces() {
            TranTypeRecord record = TranTypeRecord.decode(fixtureRow(0), ASCII);

            assertThat(record.tranTypeDesc()).isEqualTo("Purchase" + " ".repeat(42));
            assertThat(record.tranTypeDesc()).hasSize(50).isNotEqualTo("Purchase");
            assertThat("Purchase".length() + 42).isEqualTo(TranTypeRecord.TRAN_TYPE_DESC_LENGTH);
        }

        @DisplayName("the report shows the first 15 characters, right-truncated as COBOL truncates")
        @ParameterizedTest(name = "TRAN-TYPE {0} reports \"{1}\"")
        @CsvSource(delimiter = '|', ignoreLeadingAndTrailingWhitespace = false, value = {
                "0|Purchase       ",
                "1|Payment        ",
                "2|Credit         ",
                "3|Authorization  ",
                "4|Refund         ",
                "5|Reversal       ",
                "6|Adjustment     "
        })
        void theReportImageIsTheFirstFifteenCharacters(int index, String reportImage) {
            TranTypeRecord record = TranTypeRecord.decode(fixtureRow(index), ASCII);

            String moved = record.tranTypeDescMovedTo(TranTypeRecord.REPORT_TYPE_DESC_LENGTH);
            assertThat(moved).hasSize(TranTypeRecord.REPORT_TYPE_DESC_LENGTH).isEqualTo(reportImage);
            assertThat(moved).isEqualTo(record.tranTypeDesc()
                    .substring(0, TranTypeRecord.REPORT_TYPE_DESC_LENGTH));
        }

        @Test
        @DisplayName("a description longer than the receiver loses its tail, never its head")
        void anOverLongDescriptionTruncatesOnTheRight() {
            String synthetic = "Purchase authorization reversal, partial";
            assertThat(synthetic).hasSize(40);
            assertThat(synthetic.length())
                    .isGreaterThan(TranTypeRecord.REPORT_TYPE_DESC_LENGTH)
                    .isLessThan(TranTypeRecord.TRAN_TYPE_DESC_LENGTH);

            TranTypeRecord record = TranTypeRecord.of("08", synthetic, ASCII);

            assertThat(record.tranTypeDesc())
                    .isEqualTo(padded(synthetic, TranTypeRecord.TRAN_TYPE_DESC_LENGTH));
            String moved = record.tranTypeDescMovedTo(TranTypeRecord.REPORT_TYPE_DESC_LENGTH);
            assertThat(moved).hasSize(15).isEqualTo("Purchase author");
            assertThat(moved).isEqualTo(synthetic.substring(0, 15));
            assertThat(moved).doesNotContain("reversal").doesNotContain("partial");
            assertThat(record.toByteArray()).hasSize(TranTypeRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("a value longer than the 50-byte field itself is truncated on the right too")
        void aValueLongerThanTheFieldIsTruncated() {
            String tooLong = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789X";
            assertThat(tooLong).hasSize(63);
            TranTypeRecord record = TranTypeRecord.of("99", tooLong, ASCII);

            assertThat(record.tranTypeDesc())
                    .isEqualTo(tooLong.substring(0, TranTypeRecord.TRAN_TYPE_DESC_LENGTH));
            assertThat(record.tranTypeDescMovedTo(TranTypeRecord.REPORT_TYPE_DESC_LENGTH))
                    .isEqualTo(tooLong.substring(0, TranTypeRecord.REPORT_TYPE_DESC_LENGTH));
            assertThat(record.toByteArray()).hasSize(TranTypeRecord.RECORD_LENGTH);
            assertSpanAt(record.toByteArray(), TranTypeRecord.TRAN_TYPE_DESC_OFFSET,
                    tooLong.substring(0, 50), "TRAN-TYPE-DESC");
        }

        @Test
        @DisplayName("a short value written into X(50) is right-space-padded - the pad branch")
        void aShortValueIsRightPadded() {
            TranTypeRecord record = TranTypeRecord.of("03", "Credit", ASCII);
            byte[] image = record.toByteArray();

            assertThat(record.tranTypeDesc()).isEqualTo(padded("Credit", 50));
            assertSpanAt(image, TranTypeRecord.TRAN_TYPE_DESC_OFFSET, "Credit", "TRAN-TYPE-DESC");
            for (int i = TranTypeRecord.TRAN_TYPE_DESC_OFFSET + "Credit".length();
                    i < TranTypeRecord.FILLER_OFFSET; i++) {
                assertThat(image[i]).as("pad byte at absolute offset %d", i).isEqualTo((byte) ' ');
            }
        }

        @Test
        @DisplayName("a receiver wider than the field pads on the right, it does not overflow")
        void aWiderReceiverIsPadded() {
            TranTypeRecord record = TranTypeRecord.decode(fixtureRow(3), ASCII);

            assertThat(record.tranTypeDescMovedTo(60))
                    .hasSize(60)
                    .isEqualTo(padded("Authorization", 60));
            assertThat(record.tranTypeDescMovedTo(TranTypeRecord.TRAN_TYPE_DESC_LENGTH))
                    .isEqualTo(record.tranTypeDesc());
        }

        @Test
        @DisplayName("a receiver of one character is legal; a receiver of none is not")
        void theReceiverWidthIsValidated() {
            TranTypeRecord record = TranTypeRecord.decode(fixtureRow(0), ASCII);

            assertThat(record.tranTypeDescMovedTo(1)).isEqualTo("P");
            assertThatIllegalArgumentException().isThrownBy(() -> record.tranTypeDescMovedTo(0));
            assertThatIllegalArgumentException().isThrownBy(() -> record.tranTypeDescMovedTo(-1));
        }
    }

    @Nested
    @DisplayName("TRAN-TYPE is a 2-character String, never a number")
    class KeyIsCharacterData {
        @Test
        @DisplayName("the accessor's static type is String, so no leading zero can be lost")
        void theAccessorTypeIsString() throws NoSuchMethodException {
            assertThat(TranTypeRecord.class.getMethod("tranType").getReturnType())
                    .isEqualTo(String.class);
            assertThat(TranTypeRecord.class.getMethod("tranTypeKey").getReturnType())
                    .isEqualTo(String.class);
            assertThat(TranTypeRecord.class.getMethod("tranTypeDesc").getReturnType())
                    .isEqualTo(String.class);
        }

        @Test
        @DisplayName("\"01\" stays \"01\" and never collapses to \"1\"")
        void theLeadingZeroSurvives() {
            TranTypeRecord record = TranTypeRecord.decode(fixtureRow(0), ASCII);

            assertThat(record.tranType()).isEqualTo("01").isNotEqualTo("1").isNotEqualTo(" 1");
            assertThat(record.tranTypeKey()).isEqualTo(record.tranType());
            assertThat(record.tranTypeKey()).hasSize(TranTypeRecord.TRAN_TYPE_KEY_LENGTH);
            assertThat(record.tranTypeBytes()).isEqualTo("01".getBytes(ASCII));
            assertThat(record.toByteArray()[0]).isEqualTo((byte) '0');
            assertThat(record.toByteArray()[1]).isEqualTo((byte) '1');
        }

        @Test
        @DisplayName("the key round trips: set 07, re-encode, read back 07 with both bytes intact")
        void theKeyRoundTrips() {
            TranTypeRecord built = TranTypeRecord.of("07", "Adjustment", ASCII);
            TranTypeRecord reread = TranTypeRecord.decode(built.toByteArray(), ASCII);

            assertThat(reread.tranType()).isEqualTo("07");
            assertThat(reread.tranTypeKey()).isEqualTo("07");
            assertSpanAt(reread.toByteArray(), TranTypeRecord.TRAN_TYPE_OFFSET, "07", "TRAN-TYPE");
            assertThat(reread).isEqualTo(built);
        }

        @Test
        @DisplayName("a non-numeric key is carried as readily as a numeric-looking one")
        void aNonNumericKeyIsCarried() {
            TranTypeRecord record = TranTypeRecord.of("AB", "Not a number", ASCII);

            assertThat(record.tranType()).isEqualTo("AB");
            assertSpanAt(record.toByteArray(), TranTypeRecord.TRAN_TYPE_OFFSET, "AB", "TRAN-TYPE");
            assertThat(record.toByteArray()).hasSize(TranTypeRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("a short key is padded on the right, as a PIC X MOVE pads - the pad branch")
        void aShortKeyIsRightPadded() {
            assertThat(TranTypeRecord.of("1", "One", ASCII).tranType()).isEqualTo("1 ");
            assertThat(TranTypeRecord.of("", "Blank", ASCII).tranType()).isEqualTo("  ");
            assertSpanAt(TranTypeRecord.of("1", "One", ASCII).toByteArray(),
                    TranTypeRecord.TRAN_TYPE_OFFSET, "1 ", "TRAN-TYPE");
        }

        @Test
        @DisplayName("an over-long key is truncated on the right - the truncate branch")
        void anOverLongKeyIsTruncatedOnTheRight() {
            TranTypeRecord record = TranTypeRecord.of("0123", "Four", ASCII);

            assertThat(record.tranType()).isEqualTo("01");
            assertSpanAt(record.toByteArray(), TranTypeRecord.TRAN_TYPE_OFFSET, "01", "TRAN-TYPE");
            assertThat(record.tranTypeDesc()).isEqualTo(padded("Four", 50));
            assertThat(record.toByteArray()).hasSize(TranTypeRecord.RECORD_LENGTH);
        }
    }

    @Nested
    @DisplayName("Construction from field values, and the INITIALIZE state")
    class Construction {
        @Test
        @DisplayName("a built record is exactly 60 bytes with every span accounted for")
        void aBuiltRecordIsSixtyBytes() {
            TranTypeRecord record = TranTypeRecord.of("04", "Authorization", ASCII);

            assertThat(record.toByteArray()).hasSize(TranTypeRecord.RECORD_LENGTH);
            assertThat(record.image()).hasSize(TranTypeRecord.RECORD_LENGTH);
            assertThat(record.recordLength()).isEqualTo(TranTypeRecord.RECORD_LENGTH);
            assertThat(record.tranType()).isEqualTo("04");
            assertThat(record.tranTypeDesc()).isEqualTo(padded("Authorization", 50));
        }

        @Test
        @DisplayName("an empty record is 60 spaces, which is what INITIALIZE leaves")
        void anEmptyRecordIsAllSpaces() {
            assertThat(TranTypeRecord.empty(ASCII).image()).isEqualTo(" ".repeat(60));
            assertThat(TranTypeRecord.empty(new FixedWidthCodec(ASCII)))
                    .isEqualTo(TranTypeRecord.empty(ASCII));
            assertThat(TranTypeRecord.empty(ASCII)).isEqualTo(TranTypeRecord.of("", "", ASCII));
        }

        @Test
        @DisplayName("a built record round trips through both decode forms unchanged")
        void aBuiltRecordRoundTrips() {
            TranTypeRecord built = TranTypeRecord.of("07", "Adjustment", ASCII);

            assertThat(TranTypeRecord.decode(built.toByteArray(), ASCII)).isEqualTo(built);
            assertThat(TranTypeRecord.decode(built.image(), ASCII)).isEqualTo(built);
        }

        @Test
        @DisplayName("both field values are required; SPACES must be moved explicitly")
        void theFieldValuesAreRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> TranTypeRecord.of(null, "Purchase", ASCII))
                    .withMessageContaining("TRAN-TYPE");
            assertThatNullPointerException()
                    .isThrownBy(() -> TranTypeRecord.of("01", null, ASCII))
                    .withMessageContaining("TRAN-TYPE-DESC");
        }

        @Test
        @DisplayName("a codec is required, and so is a charset")
        void theEncodingIsRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> TranTypeRecord.of("01", "Purchase", (FixedWidthCodec) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TranTypeRecord.of("01", "Purchase", (Charset) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TranTypeRecord.empty((Charset) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TranTypeRecord.empty((FixedWidthCodec) null));
        }
    }

    @Nested
    @DisplayName("The FILLER read/write asymmetry - both sides, and why they do not conflict")
    class FillerAsymmetry {
        @Test
        @DisplayName("write path, gate G21: a freshly built record's FILLER at 52 is eight spaces")
        void aFreshlyBuiltRecordSpaceFillsTheReservedSpan() {
            TranTypeRecord built = TranTypeRecord.of("01", "Purchase", ASCII);

            assertThat(built.filler()).isEqualTo(" ".repeat(TranTypeRecord.FILLER_LENGTH));
            assertThat(built.fillerBytes()).hasSize(TranTypeRecord.FILLER_LENGTH);
            assertSpanAt(built.toByteArray(), TranTypeRecord.FILLER_OFFSET,
                    " ".repeat(TranTypeRecord.FILLER_LENGTH), "FILLER");
            assertThat(built.toByteArray()).hasSize(TranTypeRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("write path: an empty record's FILLER is spaces too, not zeros")
        void anEmptyRecordSpaceFillsTheReservedSpan() {
            assertThat(TranTypeRecord.empty(ASCII).filler())
                    .isEqualTo(" ".repeat(TranTypeRecord.FILLER_LENGTH))
                    .isNotEqualTo(FIXTURE_FILLER);
        }

        @Test
        @DisplayName("read path: a decoded row's FILLER keeps the fixture's eight zeros verbatim")
        void aDecodedRowCarriesTheStoredReservedBytes() {
            for (int index = 0; index < FIXTURE_ROWS.size(); index++) {
                TranTypeRecord stored = TranTypeRecord.decode(fixtureRow(index), ASCII);

                assertThat(stored.filler())
                        .as("row %d reserved span", index)
                        .isEqualTo(FIXTURE_FILLER)
                        .isNotEqualTo(" ".repeat(TranTypeRecord.FILLER_LENGTH));
                assertThat(stored.fillerBytes()).isEqualTo(FIXTURE_FILLER.getBytes(ASCII));
                assertSpanAt(stored.toByteArray(), TranTypeRecord.FILLER_OFFSET, FIXTURE_FILLER,
                        "FILLER");
            }
        }

        @Test
        @DisplayName("read path: re-encoding a decoded row is lossless, zeros included")
        void reEncodingADecodedRowIsLossless() {
            for (String row : FIXTURE_ROWS) {
                TranTypeRecord stored = TranTypeRecord.decode(row, ASCII);

                assertThat(stored.toByteArray()).isEqualTo(row.getBytes(ASCII));
                assertThat(TranTypeRecord.decode(stored.toByteArray(), ASCII)).isEqualTo(stored);
            }
        }

        @Test
        @DisplayName("the two paths therefore differ, and the difference is visible in equality")
        void theTwoPathsAreDistinguishable() {
            TranTypeRecord stored = TranTypeRecord.decode(fixtureRow(0), ASCII);
            TranTypeRecord rebuilt = TranTypeRecord.of("01", "Purchase", ASCII);

            assertThat(rebuilt.tranType()).isEqualTo(stored.tranType());
            assertThat(rebuilt.tranTypeDesc()).isEqualTo(stored.tranTypeDesc());
            assertThat(rebuilt.filler()).isNotEqualTo(stored.filler());
            assertThat(rebuilt).isNotEqualTo(stored);
        }
    }

    @Nested
    @DisplayName("Decoding rejects anything but the copybook's width")
    class WidthEnforcement {
        @Test
        @DisplayName("a short row is rejected rather than padded")
        void aShortRowIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TranTypeRecord.decode(new byte[59], ASCII))
                    .withMessageContaining("59")
                    .withMessageContaining("60");
        }

        @Test
        @DisplayName("an over-long row is rejected rather than truncated")
        void anOverLongRowIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TranTypeRecord.decode(new byte[61], ASCII))
                    .withMessageContaining("61");
        }

        @Test
        @DisplayName("a short row image is rejected, and the message names the copybook")
        void aShortImageIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TranTypeRecord.decode("01Purchase", ASCII))
                    .withMessageContaining("CVTRA03Y")
                    .withMessageContaining("60");
        }

        @Test
        @DisplayName("an exactly 60-byte row is accepted, boundary included")
        void anExactRowIsAccepted() {
            assertThat(TranTypeRecord.decode(new byte[60], ASCII).recordLength()).isEqualTo(60);
            assertThat(TranTypeRecord.decode(" ".repeat(60), ASCII).image())
                    .isEqualTo(" ".repeat(60));
        }

        @Test
        @DisplayName("null bytes, null image, null codec and null charset are all rejected")
        void nullsAreRejected() {
            FixedWidthCodec codec = new FixedWidthCodec(ASCII);
            assertThatNullPointerException()
                    .isThrownBy(() -> TranTypeRecord.decode((byte[]) null, codec));
            assertThatNullPointerException()
                    .isThrownBy(() -> TranTypeRecord.decode((String) null, codec));
            assertThatNullPointerException()
                    .isThrownBy(() -> TranTypeRecord.decode(new byte[60], (FixedWidthCodec) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TranTypeRecord.decode("x".repeat(60), (FixedWidthCodec) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TranTypeRecord.decode(new byte[60], (Charset) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TranTypeRecord.decode("x".repeat(60), (Charset) null));
        }
    }

    @Nested
    @DisplayName("Encoding is the caller's explicit choice, never the platform's")
    class ExplicitEncoding {
        @Test
        @DisplayName("the same field values encode differently under EBCDIC and ASCII")
        void thePadBytesFollowTheCodePage() {
            TranTypeRecord ascii = TranTypeRecord.of("04", "Authorization", ASCII);
            TranTypeRecord ebcdic = TranTypeRecord.of("04", "Authorization", EBCDIC);

            assertThat(ascii.toByteArray()).hasSize(60);
            assertThat(ebcdic.toByteArray()).hasSize(60);
            assertThat(ebcdic.toByteArray()).isNotEqualTo(ascii.toByteArray());
            assertThat(ascii.toByteArray()[59]).isEqualTo((byte) 0x20);
            assertThat(ebcdic.toByteArray()[59]).isEqualTo((byte) 0x40);
        }

        @Test
        @DisplayName("the field values survive the EBCDIC round trip unchanged")
        void ebcdicRoundTrips() {
            TranTypeRecord ebcdic = TranTypeRecord.of("06", "Reversal", EBCDIC);

            assertThat(ebcdic.charset()).isEqualTo(EBCDIC);
            assertThat(ebcdic.tranType()).isEqualTo("06");
            assertThat(ebcdic.tranTypeDesc()).isEqualTo(padded("Reversal", 50));
            assertThat(TranTypeRecord.decode(ebcdic.toByteArray(), EBCDIC)).isEqualTo(ebcdic);
        }

        @Test
        @DisplayName("a multi-byte code page is refused, because offsets are absolute byte positions")
        void aMultiByteCodePageIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TranTypeRecord.empty(StandardCharsets.UTF_16));
        }
    }

    @Nested
    @DisplayName("Immutability, equality and diagnostics")
    class ValueSemantics {
        @Test
        @DisplayName("no accessor hands out the backing array")
        void theBackingArrayIsNeverExposed() {
            TranTypeRecord record = TranTypeRecord.decode(fixtureRow(0), ASCII);

            byte[] first = record.toByteArray();
            first[0] = (byte) '9';
            assertThat(record.tranType()).isEqualTo("01");
            assertThat(record.toByteArray()).isNotSameAs(first);
            assertThat(record.toByteArray()[0]).isEqualTo((byte) '0');

            byte[] descriptionBytes = record.tranTypeDescBytes();
            descriptionBytes[0] = (byte) '!';
            assertThat(record.tranTypeDesc()).isEqualTo(padded("Purchase", 50));

            byte[] reserved = record.fillerBytes();
            reserved[0] = (byte) '!';
            assertThat(record.filler()).isEqualTo(FIXTURE_FILLER);

            byte[] keyBytes = record.tranTypeBytes();
            keyBytes[0] = (byte) '!';
            assertThat(record.tranType()).isEqualTo("01");
        }

        @Test
        @DisplayName("equality is reflexive, and over all 60 bytes")
        void equalityCoversTheWholeImage() {
            TranTypeRecord stored = TranTypeRecord.decode(fixtureRow(0), ASCII);
            TranTypeRecord same = TranTypeRecord.decode(fixtureRow(0), ASCII);
            TranTypeRecord otherRow = TranTypeRecord.decode(fixtureRow(1), ASCII);

            assertThat(stored.equals(stored)).isTrue();
            assertThat(stored).isEqualTo(same).hasSameHashCodeAs(same);
            assertThat(stored).isNotEqualTo(otherRow);
        }

        @Test
        @DisplayName("a record is never equal to null or to another type")
        void equalityRejectsForeignTypes() {
            TranTypeRecord record = TranTypeRecord.of("01", "Purchase", ASCII);

            assertThat(record.equals(null)).isFalse();
            assertThat(record.equals("01Purchase")).isFalse();
            assertThat(record).isNotEqualTo(TranTypeRecord.layout());
        }

        @Test
        @DisplayName("two records differing only in code page are not equal")
        void theCodePageIsPartOfIdentity() {
            TranTypeRecord ascii = TranTypeRecord.of("01", "Purchase", ASCII);
            TranTypeRecord ebcdic = TranTypeRecord.of("01", "Purchase", EBCDIC);

            assertThat(ascii).isNotEqualTo(ebcdic);
            assertThat(ascii.hashCode()).isNotEqualTo(ebcdic.hashCode());
        }

        @Test
        @DisplayName("identical bytes under different code pages are still not equal")
        void identicalBytesUnderDifferentCodePagesAreNotEqual() {
            TranTypeRecord ascii = TranTypeRecord.of("01", "Purchase", ASCII);
            TranTypeRecord latin1 = TranTypeRecord.of("01", "Purchase", StandardCharsets.ISO_8859_1);

            assertThat(latin1.toByteArray()).isEqualTo(ascii.toByteArray());
            assertThat(ascii).isNotEqualTo(latin1);
            assertThat(latin1).isNotEqualTo(ascii);
        }

        @Test
        @DisplayName("toString names the record, its key, its description, its FILLER and its charset")
        void toStringIsDiagnostic() {
            TranTypeRecord record = TranTypeRecord.decode(fixtureRow(3), ASCII);

            assertThat(record.toString())
                    .startsWith("TRAN-TYPE-RECORD[")
                    .contains("TRAN-TYPE=04")
                    .contains("TRAN-TYPE-DESC='Authorization'")
                    .contains("FILLER='" + FIXTURE_FILLER + "'")
                    .contains("60 bytes")
                    .contains("US-ASCII");
        }

        @Test
        @DisplayName("the layout handed out is the validated one and cannot be mutated")
        void theLayoutIsImmutable() {
            List<FieldSpan> spans = TranTypeRecord.layout().spans();

            assertThat(spans).hasSize(3);
            assertThat(TranTypeRecord.layout()).isEqualTo(TranTypeRecord.LAYOUT);
            assertThat(TranTypeRecord.layout().span("TRAN-TYPE"))
                    .isEqualTo(TranTypeRecord.TRAN_TYPE);
            assertThat(TranTypeRecord.layout().span("TRAN-TYPE-DESC"))
                    .isEqualTo(TranTypeRecord.TRAN_TYPE_DESC);
        }
    }

    @Nested
    @DisplayName("The shape of the type itself - what CVTRA03Y's absence of a numeric item implies")
    class TypeShape {
        @Test
        @DisplayName("no accessor returns a BigDecimal, because CVTRA03Y declares no numeric item")
        void noAccessorReturnsADecimal() {
            for (Method method : TranTypeRecord.class.getDeclaredMethods()) {
                assertThat(method.getReturnType())
                        .as("return type of %s", method.getName())
                        .isNotEqualTo(java.math.BigDecimal.class);
            }
            for (Field field : TranTypeRecord.class.getDeclaredFields()) {
                assertThat(field.getType())
                        .as("type of field %s", field.getName())
                        .isNotEqualTo(java.math.BigDecimal.class);
            }
        }

        @Test
        @DisplayName("no span is a signed-decimal span, because the copybook declares none")
        void noSpanIsSignedOrScaled() {
            for (FieldSpan span : TranTypeRecord.layout().spans()) {
                assertThat(span.kind())
                        .as("PICTURE kind of %s", span.name())
                        .isIn(PictureKind.ALPHANUMERIC, PictureKind.FILLER);
                assertThat(span.kind().numericDisplay())
                        .as("%s must not be a zoned DISPLAY numeric span", span.name())
                        .isFalse();
                assertThat(span.kind())
                        .as("%s must not be signed and scaled", span.name())
                        .isNotEqualTo(PictureKind.SIGNED_SCALED);
            }
            assertThat(TranTypeRecord.TRAN_TYPE.kind()).isEqualTo(PictureKind.ALPHANUMERIC);
            assertThat(TranTypeRecord.TRAN_TYPE_DESC.kind()).isEqualTo(PictureKind.ALPHANUMERIC);
            assertThat(TranTypeRecord.FILLER.kind()).isEqualTo(PictureKind.FILLER);
        }

        @Test
        @DisplayName("no primitive floating-point type appears anywhere on the type's surface")
        void noFloatingPointAppearsAnywhere() {
            for (Method method : TranTypeRecord.class.getDeclaredMethods()) {
                assertThat(method.getReturnType().getName())
                        .as("return type of %s", method.getName())
                        .isNotIn("double", "float");
                for (Class<?> parameter : method.getParameterTypes()) {
                    assertThat(parameter.getName())
                            .as("parameter of %s", method.getName())
                            .isNotIn("double", "float");
                }
            }
            for (Field field : TranTypeRecord.class.getDeclaredFields()) {
                assertThat(field.getType().getName())
                        .as("type of field %s", field.getName())
                        .isNotIn("double", "float");
            }
        }

        @Test
        @DisplayName("gate G44: no persistence annotation, no entity, no table, no identifier")
        void thereIsNoPersistenceMapping() {
            List<String> annotations = new ArrayList<>();
            for (Annotation annotation : TranTypeRecord.class.getAnnotations()) {
                annotations.add(annotation.annotationType().getName());
            }
            for (Field field : TranTypeRecord.class.getDeclaredFields()) {
                for (Annotation annotation : field.getAnnotations()) {
                    annotations.add(annotation.annotationType().getName());
                }
            }
            for (Method method : TranTypeRecord.class.getDeclaredMethods()) {
                for (Annotation annotation : method.getAnnotations()) {
                    annotations.add(annotation.annotationType().getName());
                }
            }
            for (Constructor<?> constructor : TranTypeRecord.class.getDeclaredConstructors()) {
                for (Annotation annotation : constructor.getAnnotations()) {
                    annotations.add(annotation.annotationType().getName());
                }
            }

            assertThat(annotations).allSatisfy(name -> {
                assertThat(name).doesNotStartWith("jakarta.persistence.");
                assertThat(name).doesNotStartWith("javax.persistence.");
                assertThat(name).doesNotStartWith("org.springframework.");
            });
        }

        @Test
        @DisplayName("gate G44: there is no version column, and no optimistic-locking artefact")
        void thereIsNoVersionArtefact() {
            for (Method method : TranTypeRecord.class.getDeclaredMethods()) {
                assertThat(method.getName().toLowerCase(Locale.ROOT))
                        .as("method name")
                        .doesNotContain("version");
            }
            for (Field field : TranTypeRecord.class.getDeclaredFields()) {
                assertThat(field.getName().toLowerCase(Locale.ROOT))
                        .as("field name")
                        .doesNotContain("version");
            }
        }

        @Test
        @DisplayName("the type is a final, mutator-free value with no mutable static state")
        void theTypeIsAnImmutableValue() {
            assertThat(Modifier.isFinal(TranTypeRecord.class.getModifiers())).isTrue();
            for (Field field : TranTypeRecord.class.getDeclaredFields()) {
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("field %s must be final", field.getName())
                        .isTrue();
            }
            for (Method method : TranTypeRecord.class.getDeclaredMethods()) {
                assertThat(method.getName()).doesNotStartWith("set");
                assertThat(method.getName()).doesNotStartWith("with");
            }
        }
    }

    @Nested
    @DisplayName("The CBTRN03C lookup contract this record has to satisfy")
    class ConsumerContract {
        @Test
        @DisplayName("CBTRN03C:189 - the key arrives as a 2-character String from the transaction")
        void theKeyTypeAlignsWithTheLookup() {
            String keyFromTransaction = "04";
            TranTypeRecord found = null;
            for (String row : FIXTURE_ROWS) {
                TranTypeRecord candidate = TranTypeRecord.decode(row, ASCII);
                if (candidate.tranTypeKey().equals(keyFromTransaction)) {
                    found = candidate;
                }
            }

            assertThat(found).isNotNull();
            assertThat(found.tranTypeKey())
                    .hasSize(TranTypeRecord.TRAN_TYPE_KEY_LENGTH)
                    .isEqualTo(keyFromTransaction);
            assertThat(found.tranTypeDesc()).isEqualTo(padded("Authorization", 50));
        }

        @Test
        @DisplayName("CBTRN03C:366 - the report field is the description's first 15 characters")
        void theReportFieldIsTheNarrowedDescription() {
            TranTypeRecord found = TranTypeRecord.decode(fixtureRow(3), ASCII);

            String reportField = found.tranTypeDescMovedTo(TranTypeRecord.REPORT_TYPE_DESC_LENGTH);
            assertThat(reportField)
                    .isEqualTo("Authorization  ")
                    .hasSize(TranTypeRecord.REPORT_TYPE_DESC_LENGTH);
        }

        @Test
        @DisplayName("a key absent from the fixture simply is not found - no I/O concern lives here")
        void anAbsentKeyIsNotThisTypesConcern() {
            boolean present = false;
            for (String row : FIXTURE_ROWS) {
                if (TranTypeRecord.decode(row, ASCII).tranTypeKey().equals("99")) {
                    present = true;
                }
            }

            assertThat(present).isFalse();
        }
    }
}
