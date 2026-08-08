package com.vsergeychik.carddemo.transaction.model;

import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link TranTypeRecord}, the {@code app/cpy/CVTRA03Y.cpy} transaction-type record.
 *
 * <h2>What is being proved, and against what</h2>
 * The copybook is 11 lines long and declares three items totalling 60 bytes. Everything asserted here
 * is derived from that copybook and from three independent corroborating sources rather than from the
 * implementation:
 * <ul>
 *   <li>{@code app/cbl/CBTRN03C.cbl:73-75} splits the same record as {@code FD-TRAN-TYPE PIC X(02)}
 *       plus {@code FD-TRAN-DATA PIC X(58)}, which totals 60 and independently fixes the key at the
 *       leading 2 bytes;</li>
 *   <li>{@code app/jcl/TRANREPT.jcl:69-70} binds {@code TRANTYPE} as a keyed input to
 *       {@code CBTRN03C}, and the dataset binding declares {@code record-length: 60};</li>
 *   <li>{@code src/test/resources/fixtures/trantype.txt} is a byte-identical copy of
 *       {@code app/data/ASCII/trantype.txt}, and every one of its seven 60-byte rows is decoded below
 *       at the copybook's own offsets.</li>
 * </ul>
 * The expectations are <strong>statically derived</strong>. The COBOL cannot be executed in this
 * environment, so no expectation here was captured from a live run; each is instead traceable to a
 * {@code PICTURE} clause, a {@code MOVE} statement or a measured fixture byte, and the source of each
 * is named in the test that asserts it.
 *
 * <h2>Conventions</h2>
 * Plain JUnit 5 with AssertJ and no Spring context, because nothing in the class under test needs one.
 * Both code pages are named explicitly and neither is ever defaulted: {@code US-ASCII} for the text
 * fixtures and {@code IBM037} for the EBCDIC datasets. There is no wildcard import, not even a static
 * one, and no mutable static state - only {@code static final} constants and locally built values.
 */
@DisplayName("TranTypeRecord - CVTRA03Y TRAN-TYPE-RECORD, 60 bytes, keyed on TRAN-TYPE X(02)")
class TranTypeRecordTest {

    /** The text fixtures are ASCII; named explicitly rather than defaulted. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** The EBCDIC code page of the binary datasets, used to prove the charset is honoured. */
    private static final Charset EBCDIC = Charset.forName("IBM037");

    /** The shipped fixture, on the test classpath, byte-identical to {@code app/data/ASCII}. */
    private static final String FIXTURE = "/fixtures/trantype.txt";

    /** The seven type codes in the shipped data, in file order. */
    private static final List<String> FIXTURE_KEYS =
            List.of("01", "02", "03", "04", "05", "06", "07");

    /** The seven descriptions in the shipped data, before their padding to 50 bytes. */
    private static final List<String> FIXTURE_DESCRIPTIONS =
            List.of("Purchase", "Payment", "Credit", "Authorization", "Refund", "Reversal",
                    "Adjustment");

    /** The measured content of every fixture row's trailing {@code FILLER X(08)}: eight zeros. */
    private static final String FIXTURE_FILLER = "00000000";

    /**
     * Reads the shipped fixture as 60-character rows, exactly as stored and with nothing trimmed.
     *
     * @return the seven rows in file order
     */
    private static List<String> fixtureRows() {
        List<String> rows = new ArrayList<>();
        try (InputStream stream = TranTypeRecordTest.class.getResourceAsStream(FIXTURE)) {
            assertThat(stream).as("fixture %s must be on the test classpath", FIXTURE).isNotNull();
            String content = new String(stream.readAllBytes(), ASCII);
            for (String line : content.split("\n", -1)) {
                if (!line.isEmpty()) {
                    rows.add(line);
                }
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("Unable to read the TRANTYPE fixture " + FIXTURE, failure);
        }
        return rows;
    }

    /**
     * Pads a value to a width with spaces on the right, which is how COBOL fills a {@code PIC X}
     * receiver. Written out here rather than borrowed from the codec so the expected images are
     * independent of the code that produces them.
     *
     * @param value the value
     * @param width the receiver width
     * @return the padded image
     */
    private static String padded(String value, int width) {
        return value + " ".repeat(width - value.length());
    }

    @Nested
    @DisplayName("Declared geometry - gates G19 and G21")
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
        }

        @Test
        @DisplayName("offsets are the copybook's 1-based positions converted to 0-based")
        void offsetsMatchTheCopybook() {
            assertThat(TranTypeRecord.TRAN_TYPE_OFFSET).isZero();
            assertThat(TranTypeRecord.TRAN_TYPE_DESC_OFFSET).isEqualTo(2);
            assertThat(TranTypeRecord.FILLER_OFFSET).isEqualTo(52);

            assertThat(TranTypeRecord.TRAN_TYPE.offset()).isZero();
            assertThat(TranTypeRecord.TRAN_TYPE.length()).isEqualTo(2);
            assertThat(TranTypeRecord.TRAN_TYPE_DESC.offset()).isEqualTo(2);
            assertThat(TranTypeRecord.TRAN_TYPE_DESC.length()).isEqualTo(50);
            assertThat(TranTypeRecord.FILLER.offset()).isEqualTo(52);
            assertThat(TranTypeRecord.FILLER.length()).isEqualTo(8);
            assertThat(TranTypeRecord.FILLER.endOffsetExclusive())
                    .isEqualTo(TranTypeRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("field names are the copybook's own: TRAN-TYPE, not TRAN-TYPE-CD")
        void fieldNamesAreVerbatim() {
            assertThat(TranTypeRecord.TRAN_TYPE_FIELD).isEqualTo("TRAN-TYPE");
            assertThat(TranTypeRecord.TRAN_TYPE_DESC_FIELD).isEqualTo("TRAN-TYPE-DESC");
            assertThat(TranTypeRecord.TRAN_TYPE.name()).isEqualTo("TRAN-TYPE");
            assertThat(TranTypeRecord.TRAN_TYPE_DESC.name()).isEqualTo("TRAN-TYPE-DESC");
            // CVTRA04Y and CVTRA05Y call their two-byte code TRAN-TYPE-CD; this copybook does not,
            // and harmonising the two would rename a field the parity differ compares by name.
            assertThat(TranTypeRecord.TRAN_TYPE_FIELD).isNotEqualTo("TRAN-TYPE-CD");
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
        @DisplayName("gate G33 is vacuous here: no OCCURS, no REDEFINES, nothing to index")
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
        }

        @Test
        @DisplayName("the report receiver is CVTRA07Y's PIC X(15)")
        void theReportReceiverIsFifteen() {
            assertThat(TranTypeRecord.REPORT_TYPE_DESC_LENGTH).isEqualTo(15);
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
        @DisplayName("the width self-check requires a layout")
        void aNullLayoutIsRejected() {
            assertThatNullPointerException()
                    .isThrownBy(() -> TranTypeRecord.verifyDeclaredWidth(null))
                    .withMessageContaining("CVTRA03Y");
        }
    }

    @Nested
    @DisplayName("The seven shipped TRANTYPE records, decoded at the copybook's offsets")
    class ShippedFixture {

        @Test
        @DisplayName("the fixture holds exactly 7 rows of exactly 60 bytes")
        void theFixtureIsSevenSixtyByteRows() {
            List<String> rows = fixtureRows();
            assertThat(rows).hasSize(7);
            assertThat(rows).allSatisfy(row -> assertThat(row).hasSize(60));
        }

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
            TranTypeRecord record = TranTypeRecord.decode(fixtureRows().get(index), ASCII);

            assertThat(record.tranType()).isEqualTo(key);
            assertThat(record.tranTypeKey()).isEqualTo(key);
            assertThat(record.tranTypeDesc()).isEqualTo(padded(description, 50));
            assertThat(record.filler()).isEqualTo(FIXTURE_FILLER);
            assertThat(record.recordLength()).isEqualTo(60);
            assertThat(record.charset()).isEqualTo(ASCII);
        }

        @Test
        @DisplayName("every row re-encodes to exactly 60 bytes, byte-identical to the stored row")
        void everyRowRoundTripsByteForByte() {
            List<String> rows = fixtureRows();
            for (int index = 0; index < rows.size(); index++) {
                String row = rows.get(index);
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
            }
        }

        @Test
        @DisplayName("the reserved span carries the fixture's eight zeros, not spaces")
        void theReservedSpanIsCarriedVerbatim() {
            TranTypeRecord stored = TranTypeRecord.decode(fixtureRows().get(0), ASCII);

            assertThat(stored.filler()).isEqualTo(FIXTURE_FILLER).isNotEqualTo(" ".repeat(8));
            assertThat(stored.fillerBytes()).hasSize(TranTypeRecord.FILLER_LENGTH);
            assertThat(stored.fillerBytes()).isEqualTo(FIXTURE_FILLER.getBytes(ASCII));
            // Rebuilding the same field values from nothing would space-fill the span instead, which
            // is why a record read from the dataset is never rebuilt from its field values.
            assertThat(TranTypeRecord.of("01", "Purchase", ASCII).filler()).isEqualTo(" ".repeat(8));
        }

        @Test
        @DisplayName("the byte and text decode paths agree on every row")
        void bothDecodePathsAgree() {
            FixedWidthCodec codec = new FixedWidthCodec(ASCII);
            for (String row : fixtureRows()) {
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
            for (String row : fixtureRows()) {
                TranTypeRecord record = TranTypeRecord.decode(row, ASCII);
                keys.add(record.tranTypeKey());
                descriptions.add(record.tranTypeDesc().stripTrailing());
            }
            assertThat(keys).containsExactlyElementsOf(FIXTURE_KEYS);
            assertThat(descriptions).containsExactlyElementsOf(FIXTURE_DESCRIPTIONS);
        }
    }

    @Nested
    @DisplayName("TRAN-TYPE-DESC is decoded untrimmed - the CBTRN03C:366 X(50) to X(15) move")
    class UntrimmedDescription {

        @Test
        @DisplayName("the decoded description is exactly 50 characters, padding included")
        void theDescriptionIsFiftyCharacters() {
            for (String row : fixtureRows()) {
                TranTypeRecord record = TranTypeRecord.decode(row, ASCII);
                assertThat(record.tranTypeDesc()).hasSize(TranTypeRecord.TRAN_TYPE_DESC_LENGTH);
                assertThat(record.tranTypeDescBytes()).hasSize(TranTypeRecord.TRAN_TYPE_DESC_LENGTH);
            }
        }

        @DisplayName("the report shows the first 15 characters, right-truncated as COBOL truncates")
        @ParameterizedTest(name = "TRAN-TYPE {0} reports \"{1}\"")
        // ignoreLeadingAndTrailingWhitespace must be off: the trailing spaces below are the point of
        // the case. A PIC X(15) receiver is space-padded, so "Purchase" reaches the report as
        // "Purchase" followed by seven spaces, and a trimmed expectation would assert the opposite.
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
            TranTypeRecord record = TranTypeRecord.decode(fixtureRows().get(index), ASCII);

            String moved = record.tranTypeDescMovedTo(TranTypeRecord.REPORT_TYPE_DESC_LENGTH);
            assertThat(moved).hasSize(15).isEqualTo(reportImage);
            assertThat(moved).isEqualTo(record.tranTypeDesc().substring(0, 15));
        }

        @Test
        @DisplayName("a description longer than the receiver loses its tail, never its head")
        void anOverLongDescriptionTruncatesOnTheRight() {
            // 61 characters, deliberately longer than both the 50-byte field and the 15-byte
            // receiver, so both truncations are visible in one case.
            String tooLong = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789X";
            TranTypeRecord record = TranTypeRecord.of("99", tooLong, ASCII);

            assertThat(record.tranTypeDesc()).isEqualTo(tooLong.substring(0, 50));
            assertThat(record.tranTypeDescMovedTo(15)).isEqualTo(tooLong.substring(0, 15));
            assertThat(record.toByteArray()).hasSize(TranTypeRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("a receiver wider than the field pads on the right, it does not overflow")
        void aWiderReceiverIsPadded() {
            TranTypeRecord record = TranTypeRecord.decode(fixtureRows().get(3), ASCII);

            assertThat(record.tranTypeDescMovedTo(60))
                    .hasSize(60)
                    .isEqualTo(padded("Authorization", 60));
            assertThat(record.tranTypeDescMovedTo(TranTypeRecord.TRAN_TYPE_DESC_LENGTH))
                    .isEqualTo(record.tranTypeDesc());
        }

        @Test
        @DisplayName("a receiver of one character is legal; a receiver of none is not")
        void theReceiverWidthIsValidated() {
            TranTypeRecord record = TranTypeRecord.decode(fixtureRows().get(0), ASCII);

            assertThat(record.tranTypeDescMovedTo(1)).isEqualTo("P");
            assertThatIllegalArgumentException().isThrownBy(() -> record.tranTypeDescMovedTo(0));
        }
    }

    @Nested
    @DisplayName("TRAN-TYPE is a 2-character String, never a number")
    class KeyIsCharacterData {

        @Test
        @DisplayName("\"01\" stays \"01\" and never collapses to \"1\"")
        void theLeadingZeroSurvives() {
            TranTypeRecord record = TranTypeRecord.decode(fixtureRows().get(0), ASCII);

            assertThat(record.tranType()).isEqualTo("01").isNotEqualTo("1").isNotEqualTo(" 1");
            assertThat(record.tranTypeKey()).isEqualTo(record.tranType());
            assertThat(record.tranTypeKey()).hasSize(TranTypeRecord.TRAN_TYPE_KEY_LENGTH);
            assertThat(record.tranTypeBytes()).isEqualTo("01".getBytes(ASCII));
        }

        @Test
        @DisplayName("a non-numeric key is carried as readily as a numeric-looking one")
        void aNonNumericKeyIsCarried() {
            // PIC X(02) is character data: nothing here may assume the two bytes are digits.
            TranTypeRecord record = TranTypeRecord.of("AB", "Not a number", ASCII);

            assertThat(record.tranType()).isEqualTo("AB");
            assertThat(record.toByteArray()).hasSize(TranTypeRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("a short key is padded on the right, as a PIC X MOVE pads")
        void aShortKeyIsRightPadded() {
            assertThat(TranTypeRecord.of("1", "One", ASCII).tranType()).isEqualTo("1 ");
            assertThat(TranTypeRecord.of("", "Blank", ASCII).tranType()).isEqualTo("  ");
        }

        @Test
        @DisplayName("an over-long key is truncated on the right, as a PIC X MOVE truncates")
        void anOverLongKeyIsTruncatedOnTheRight() {
            assertThat(TranTypeRecord.of("0123", "Four", ASCII).tranType()).isEqualTo("01");
        }
    }

    @Nested
    @DisplayName("Construction from field values, and the INITIALIZE state")
    class Construction {

        @Test
        @DisplayName("a built record is exactly 60 bytes with a space-filled FILLER")
        void aBuiltRecordIsSixtyBytes() {
            TranTypeRecord record = TranTypeRecord.of("04", "Authorization", ASCII);

            assertThat(record.toByteArray()).hasSize(TranTypeRecord.RECORD_LENGTH);
            assertThat(record.image()).hasSize(TranTypeRecord.RECORD_LENGTH);
            assertThat(record.tranType()).isEqualTo("04");
            assertThat(record.tranTypeDesc()).isEqualTo(padded("Authorization", 50));
            assertThat(record.filler()).isEqualTo(" ".repeat(TranTypeRecord.FILLER_LENGTH));
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
        @DisplayName("a built record round trips through decode unchanged")
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
            // The pad byte is the code page's own space: 0x20 under ASCII, 0x40 under EBCDIC.
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
            TranTypeRecord record = TranTypeRecord.decode(fixtureRows().get(0), ASCII);

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
        @DisplayName("equality is over all 60 bytes and the code page")
        void equalityCoversTheWholeImage() {
            TranTypeRecord stored = TranTypeRecord.decode(fixtureRows().get(0), ASCII);
            TranTypeRecord same = TranTypeRecord.decode(fixtureRows().get(0), ASCII);
            TranTypeRecord otherRow = TranTypeRecord.decode(fixtureRows().get(1), ASCII);
            TranTypeRecord rebuilt = TranTypeRecord.of("01", "Purchase", ASCII);

            assertThat(stored).isEqualTo(stored);
            assertThat(stored).isEqualTo(same).hasSameHashCodeAs(same);
            assertThat(stored).isNotEqualTo(otherRow);
            // Same two field values, different reserved bytes: zeros on disk, spaces when rebuilt.
            assertThat(stored.tranType()).isEqualTo(rebuilt.tranType());
            assertThat(stored.tranTypeDesc()).isEqualTo(rebuilt.tranTypeDesc());
            assertThat(stored).isNotEqualTo(rebuilt);
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
            // ISO-8859-1 and US-ASCII encode this record's characters to exactly the same bytes, so
            // this is the one case where the images coincide and only the declared code page differs.
            // They must still compare unequal: the charset is how the bytes are to be read, and two
            // records that would be read differently are not the same record.
            TranTypeRecord ascii = TranTypeRecord.of("01", "Purchase", ASCII);
            TranTypeRecord latin1 = TranTypeRecord.of("01", "Purchase", StandardCharsets.ISO_8859_1);

            assertThat(latin1.toByteArray()).isEqualTo(ascii.toByteArray());
            assertThat(ascii).isNotEqualTo(latin1);
            assertThat(latin1).isNotEqualTo(ascii);
        }

        @Test
        @DisplayName("toString names the record, its key, its description, its FILLER and its charset")
        void toStringIsDiagnostic() {
            TranTypeRecord record = TranTypeRecord.decode(fixtureRows().get(3), ASCII);

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
    @DisplayName("The CBTRN03C lookup contract this record has to satisfy")
    class ConsumerContract {

        @Test
        @DisplayName("CBTRN03C:189 - the key arrives as a 2-character String from the transaction")
        void theKeyTypeAlignsWithTheLookup() {
            // MOVE TRAN-TYPE-CD OF TRAN-RECORD TO FD-TRAN-TYPE, then 1500-B-LOOKUP-TRANTYPE. The
            // sending field is PIC X(02) and so is the key, so the lookup key is two characters.
            String keyFromTransaction = "04";
            TranTypeRecord found = fixtureRows().stream()
                    .map(row -> TranTypeRecord.decode(row, ASCII))
                    .filter(record -> record.tranTypeKey().equals(keyFromTransaction))
                    .findFirst()
                    .orElseThrow();

            assertThat(found.tranTypeKey()).hasSize(2).isEqualTo(keyFromTransaction);
            assertThat(found.tranTypeDesc()).isEqualTo(padded("Authorization", 50));
        }

        @Test
        @DisplayName("CBTRN03C:366 - the report field is the description's first 15 characters")
        void theReportFieldIsTheNarrowedDescription() {
            TranTypeRecord found = TranTypeRecord.decode(fixtureRows().get(3), ASCII);

            String reportField = found.tranTypeDescMovedTo(TranTypeRecord.REPORT_TYPE_DESC_LENGTH);
            assertThat(reportField).isEqualTo("Authorization  ").hasSize(15);
        }

        @Test
        @DisplayName("a key absent from the fixture simply is not found - no I/O concern lives here")
        void anAbsentKeyIsNotThisTypesConcern() {
            // CBTRN03C handles a missing key with DISPLAY, IO-STATUS 23 and an abend; that belongs to
            // the repository and the report job, so this record type has nothing to say about it and
            // exposes no status, no exception and no not-found sentinel of its own.
            boolean present = fixtureRows().stream()
                    .map(row -> TranTypeRecord.decode(row, ASCII))
                    .anyMatch(record -> record.tranTypeKey().equals("99"));

            assertThat(present).isFalse();
        }
    }
}
