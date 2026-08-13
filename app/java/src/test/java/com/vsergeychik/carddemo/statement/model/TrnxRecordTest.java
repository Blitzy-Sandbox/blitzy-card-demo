package com.vsergeychik.carddemo.statement.model;

import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link TrnxRecord}, the 350-byte {@code TRNX-RECORD} of {@code app/cpy/COSTM01.CPY}.
 */
@DisplayName("TrnxRecord - COSTM01 TRNX-RECORD, 350 bytes, 32-byte composite key")
class TrnxRecordTest {
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    private static final Charset EBCDIC = Charset.forName("IBM037");

    private static final String FIXTURE_TRAN_ID = "0000000000683580";
    private static final String FIXTURE_TYPE_CD = "01";
    private static final String FIXTURE_CAT_CD = "0001";
    private static final String FIXTURE_SOURCE = "POS TERM";
    private static final String FIXTURE_DESC = "Purchase at Abshire-Lowe";
    private static final String FIXTURE_AMT_IMAGE = "0000005047G";
    private static final String FIXTURE_MERCHANT_ID = "800000000";
    private static final String FIXTURE_MERCHANT_NAME = "Abshire-Lowe";
    private static final String FIXTURE_MERCHANT_CITY = "North Enoshaven";
    private static final String FIXTURE_MERCHANT_ZIP = "72112";
    private static final String FIXTURE_CARD_NUM = "4859452612877065";
    private static final String FIXTURE_ORIG_TS = "2022-06-10 19:27:53.000000";

    private static final String FULL_PROC_TS = "2022-07-18 04:11:09.123456";

    private static String picX(String value, int width) {
        String truncated = value.length() > width ? value.substring(0, width) : value;
        return truncated + " ".repeat(width - truncated.length());
    }

    private static String pic9(String digits, int width) {
        return "0".repeat(width - digits.length()) + digits;
    }

    private static String tranRecordImage(String procTs) {
        String image = picX(FIXTURE_TRAN_ID, 16)
                + picX(FIXTURE_TYPE_CD, 2)
                + pic9(FIXTURE_CAT_CD, 4)
                + picX(FIXTURE_SOURCE, 10)
                + picX(FIXTURE_DESC, 100)
                + FIXTURE_AMT_IMAGE
                + pic9(FIXTURE_MERCHANT_ID, 9)
                + picX(FIXTURE_MERCHANT_NAME, 50)
                + picX(FIXTURE_MERCHANT_CITY, 50)
                + picX(FIXTURE_MERCHANT_ZIP, 10)
                + picX(FIXTURE_CARD_NUM, 16)
                + picX(FIXTURE_ORIG_TS, 26)
                + picX(procTs, 26)
                + " ".repeat(20);
        assertThat(image).as("CVTRA05Y TRAN-RECORD is documented as RECLN = 350").hasSize(350);
        return image;
    }

    private static String applyCreastmtOutrec(String tranRecord) {
        char[] out = new char[TrnxRecord.RECORD_LENGTH];
        Arrays.fill(out, ' ');
        tranRecord.getChars(262, 278, out, 0);
        tranRecord.getChars(0, 262, out, 16);
        tranRecord.getChars(278, 328, out, 278);
        return new String(out);
    }

    private static byte[] filled(char character, int length, Charset charset) {
        return String.valueOf(character).repeat(length).getBytes(charset);
    }

    private static final String COMPOSED_AMT_IMAGE = "0000012345E";

    private static String composedTrnxImage() {
        return "AAAAAAAAAAAAAAAA"
                + "BBBBBBBBBBBBBBBB"
                + "CC"
                + "0042"
                + "EEEEEEEEEE"
                + "F".repeat(100)
                + COMPOSED_AMT_IMAGE
                + "000123456"
                + "H".repeat(50)
                + "I".repeat(50)
                + "JJJJJJJJJJ"
                + "K".repeat(26)
                + "L".repeat(26)
                + " ".repeat(20);
    }

    private static TrnxRecord recordWithAmountImage(String image, Charset charset) {
        char[] span = new char[TrnxRecord.RECORD_LENGTH];
        Arrays.fill(span, ' ');
        image.getChars(0, image.length(), span, TrnxRecord.TRNX_AMT_OFFSET);
        return TrnxRecord.wrap(new String(span).getBytes(charset), charset);
    }

    private static TrnxRecord populated(Charset charset) {
        TrnxRecord record = TrnxRecord.newRecord(charset);
        record.writeTrnxCardNum(FIXTURE_CARD_NUM);
        record.writeTrnxId(FIXTURE_TRAN_ID);
        record.writeTrnxTypeCd(FIXTURE_TYPE_CD);
        record.writeTrnxCatCd(1);
        record.writeTrnxSource(FIXTURE_SOURCE);
        record.writeTrnxDesc(FIXTURE_DESC);
        record.writeTrnxAmt(new BigDecimal("504.77"));
        record.writeTrnxMerchantId(800000000);
        record.writeTrnxMerchantName(FIXTURE_MERCHANT_NAME);
        record.writeTrnxMerchantCity(FIXTURE_MERCHANT_CITY);
        record.writeTrnxMerchantZip(FIXTURE_MERCHANT_ZIP);
        record.writeTrnxOrigTs(FIXTURE_ORIG_TS);
        record.writeTrnxProcTs(FULL_PROC_TS);
        return record;
    }

    @Nested
    @DisplayName("Geometry - the copybook's own arithmetic (gate G19)")
    class Geometry {
        @Test
        @DisplayName("the declared record length is 350, as RECORDSIZE(350 350) and the 16+16+318 FD split both confirm")
        void recordLengthIs350() {
            assertThat(TrnxRecord.RECORD_LENGTH).isEqualTo(350);
            assertThat(TrnxRecord.layout().recordLength()).isEqualTo(350);
        }

        @Test
        @DisplayName("the fourteen elementary spans sum to exactly 350, FILLER included")
        void storageSpansSumToRecordLength() {
            List<FieldSpan> storage = TrnxRecord.layout().storageSpans();

            assertThat(storage).hasSize(14);
            assertThat(storage.stream().mapToInt(FieldSpan::length).sum())
                    .as("dropping FILLER X(20) would give 330; a sign byte on TRNX-AMT would give 351")
                    .isEqualTo(TrnxRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("the spans are contiguous from offset 0 with no gap and no overlap")
        void storageSpansAreContiguousFromZero() {
            int cursor = 0;
            for (FieldSpan span : TrnxRecord.layout().storageSpans()) {
                assertThat(span.offset()).as("offset of %s", span.name()).isEqualTo(cursor);
                cursor = span.endOffsetExclusive();
            }
            assertThat(cursor).isEqualTo(TrnxRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("TRNX-KEY is 32 bytes at offset 0, matching KEYS(32 0) in the IDCAMS define")
        void keyGroupIs32BytesAtOffsetZero() {
            assertThat(TrnxRecord.TRNX_KEY_OFFSET).isZero();
            assertThat(TrnxRecord.TRNX_KEY_LENGTH).isEqualTo(32);
            assertThat(TrnxRecord.TRNX_KEY.offset()).isZero();
            assertThat(TrnxRecord.TRNX_KEY.length()).isEqualTo(32);
            assertThat(TrnxRecord.TRNX_KEY.name()).isEqualTo("TRNX-KEY");

            assertThat(TrnxRecord.TRNX_CARD_NUM_LENGTH + TrnxRecord.TRNX_ID_LENGTH)
                    .as("the key is TRNX-CARD-NUM X(16) followed by TRNX-ID X(16)")
                    .isEqualTo(TrnxRecord.TRNX_KEY_LENGTH);
        }

        @Test
        @DisplayName("TRNX-REST is 318 bytes at offset 32, matching FD-ACCT-DATA X(318) and WS-TRAN-REST X(318)")
        void restGroupIs318BytesAtOffset32() {
            assertThat(TrnxRecord.TRNX_REST_OFFSET).isEqualTo(32);
            assertThat(TrnxRecord.TRNX_REST_LENGTH).isEqualTo(318);
            assertThat(TrnxRecord.TRNX_REST.offset()).isEqualTo(32);
            assertThat(TrnxRecord.TRNX_REST.length()).isEqualTo(318);
            assertThat(TrnxRecord.TRNX_REST.name()).isEqualTo("TRNX-REST");

            assertThat(TrnxRecord.TRNX_KEY_LENGTH + TrnxRecord.TRNX_REST_LENGTH)
                    .isEqualTo(TrnxRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("the two group items are REDEFINES overlays and contribute nothing to the total")
        void groupItemsAreOverlays() {
            assertThat(TrnxRecord.layout().redefinitions())
                    .containsExactly(TrnxRecord.TRNX_KEY, TrnxRecord.TRNX_REST);
            assertThat(TrnxRecord.TRNX_KEY.redefinition()).isTrue();
            assertThat(TrnxRecord.TRNX_REST.redefinition()).isTrue();
            assertThat(TrnxRecord.layout().storageSpans())
                    .doesNotContain(TrnxRecord.TRNX_KEY, TrnxRecord.TRNX_REST);
        }

        @ParameterizedTest(name = "{0} at offset {1}, length {2}")
        @CsvSource({
                "TRNX-CARD-NUM,        0,  16",
                "TRNX-ID,             16,  16",
                "TRNX-TYPE-CD,        32,   2",
                "TRNX-CAT-CD,         34,   4",
                "TRNX-SOURCE,         38,  10",
                "TRNX-DESC,           48, 100",
                "TRNX-AMT,           148,  11",
                "TRNX-MERCHANT-ID,   159,   9",
                "TRNX-MERCHANT-NAME, 168,  50",
                "TRNX-MERCHANT-CITY, 218,  50",
                "TRNX-MERCHANT-ZIP,  268,  10",
                "TRNX-ORIG-TS,       278,  26",
                "TRNX-PROC-TS,       304,  26",
                "FILLER,             330,  20"
        })
        @DisplayName("every field sits at its copybook offset and width")
        void everyFieldSitsAtItsCopybookOffset(String name, int offset, int length) {
            List<FieldSpan> matching = TrnxRecord.layout().storageSpans().stream()
                    .filter(span -> span.name().equals(name) && span.offset() == offset)
                    .toList();

            assertThat(matching).as("span named %s at offset %d", name, offset).hasSize(1);
            assertThat(matching.get(0).length()).isEqualTo(length);
        }

        @Test
        @DisplayName("TRNX-AMT is 11 bytes because S9(09)V99 reserves no sign byte")
        void amountOccupiesNineIntegerPlusTwoFractionBytes() {
            assertThat(TrnxRecord.TRNX_AMT_INTEGER_DIGITS).isEqualTo(9);
            assertThat(TrnxRecord.TRNX_AMT_SCALE).isEqualTo(2).isEqualTo(CobolDecimal.MONETARY_SCALE);
            assertThat(TrnxRecord.TRNX_AMT_LENGTH).isEqualTo(11);
            assertThat(TrnxRecord.TRNX_AMT.length())
                    .as("reserving a sign byte would make the record 351 and fail the layout check")
                    .isEqualTo(11);
            assertThat(TrnxRecord.TRNX_AMT.offset()).isEqualTo(148);
        }

        @Test
        @DisplayName("FILLER X(20) is the last declared span and reaches exactly byte 350")
        void fillerIsTheLastSpanAndClosesTheRecord() {
            List<FieldSpan> storage = TrnxRecord.layout().storageSpans();
            FieldSpan last = storage.get(storage.size() - 1);

            assertThat(last.name()).isEqualTo("FILLER");
            assertThat(last.offset()).isEqualTo(TrnxRecord.FILLER_OFFSET).isEqualTo(330);
            assertThat(last.length()).isEqualTo(TrnxRecord.FILLER_LENGTH).isEqualTo(20);
            assertThat(last.endOffsetExclusive()).isEqualTo(TrnxRecord.RECORD_LENGTH);
            assertThat(last.kind().filler())
                    .as("FILLER is declared with the FILLER kind, so it space-fills and stays non-referable")
                    .isTrue();
            assertThat(last.hasInitialValue())
                    .as("this FILLER declares no VALUE, unlike the '/' separators of CSDAT01Y")
                    .isFalse();
        }

        @Test
        @DisplayName("a layout that drops the trailing FILLER is rejected, not silently 330 bytes (gate G21)")
        void aLayoutMissingTheTrailingFillerIsRejected() {
            List<FieldSpan> withoutFiller = new ArrayList<>(TrnxRecord.layout().storageSpans());
            withoutFiller.removeIf(span -> span.name().equals("FILLER"));

            assertThat(withoutFiller.stream().mapToInt(FieldSpan::length).sum())
                    .as("this is the 330 a dropped FILLER would silently produce")
                    .isEqualTo(330);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new RecordLayout(TrnxRecord.RECORD_LENGTH, withoutFiller))
                    .withMessageContaining("330")
                    .withMessageContaining("FILLER");
        }

        @Test
        @DisplayName("the descriptor table is immutable and cannot be written through (gate G53)")
        void descriptorTableIsImmutable() {
            List<FieldSpan> spans = TrnxRecord.fieldSpans();

            assertThat(spans).hasSize(16).isSameAs(TrnxRecord.fieldSpans());
            assertThat(spans.subList(0, 14)).isEqualTo(TrnxRecord.layout().storageSpans());

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> spans.add(TrnxRecord.TRNX_AMT));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> TrnxRecord.layout().spans().clear());
        }
    }

    @Nested
    @DisplayName("Allocation and initialisation")
    class Allocation {
        @ParameterizedTest(name = "under {0}")
        @ValueSource(strings = {"US-ASCII", "IBM037"})
        @DisplayName("a new record is exactly 350 bytes with the declared charset")
        void newRecordIsDeclaredWidth(String charsetName) {
            Charset charset = Charset.forName(charsetName);

            TrnxRecord record = TrnxRecord.newRecord(charset);

            assertThat(record.recordLength()).isEqualTo(350);
            assertThat(record.charset()).isEqualTo(charset);
            assertThat(record.encode()).hasSize(350);
            assertThat(record.recordImage()).hasSize(350);
        }

        @ParameterizedTest(name = "under {0}")
        @ValueSource(strings = {"US-ASCII", "IBM037"})
        @DisplayName("unsigned spans initialise to zeros, a signed span to a signed zero, text to spaces")
        void initialisationFollowsCobolConvention(String charsetName) {
            TrnxRecord record = TrnxRecord.newRecord(Charset.forName(charsetName));

            assertThat(record.readRawImage(TrnxRecord.TRNX_CAT_CD)).isEqualTo("0000");
            assertThat(record.readRawImage(TrnxRecord.TRNX_MERCHANT_ID)).isEqualTo("000000000");
            assertThat(record.readTrnxAmtImage())
                    .as("TRNX-AMT is S9(09)V99, so its zero carries a positive-zero overpunch in the "
                            + "trailing byte - the form every signed field in app/data/ASCII is "
                            + "stored in - and the image is still exactly 11 characters")
                    .isEqualTo("0000000000{");
            assertThat(record.readTrnxCardNum()).isEqualTo(" ".repeat(16));
            assertThat(record.readFiller()).isEqualTo(" ".repeat(20));
        }

        @Test
        @DisplayName("an initialised record's numeric fields are readable without failing on blank digits")
        void initialisedNumericFieldsAreReadable() {
            TrnxRecord record = TrnxRecord.newRecord(ASCII);

            assertThat(record.readTrnxCatCd()).isZero();
            assertThat(record.readTrnxMerchantId()).isZero();
            assertThat(record.readTrnxAmt()).isEqualByComparingTo("0.00");
            assertThat(record.readTrnxAmt().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("a null charset is rejected rather than defaulted to the platform")
        void nullCharsetIsRejected() {
            assertThatNullPointerException()
                    .isThrownBy(() -> TrnxRecord.newRecord(null))
                    .withMessageContaining("charset");
            assertThatNullPointerException()
                    .isThrownBy(() -> TrnxRecord.decode(new byte[350], null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TrnxRecord.wrap(new byte[350], null));
        }
    }

    @Nested
    @DisplayName("decode - the COBOL alphanumeric group MOVE into a 350-byte receiver")
    class Decoding {
        @ParameterizedTest(name = "under {0}")
        @ValueSource(strings = {"US-ASCII", "IBM037"})
        @DisplayName("an oversized 1000-byte span keeps only its leading 350 bytes, per MOVE WS-M03B-FLDT TO TRNX-RECORD")
        void oversizedSpanIsTruncatedOnTheRight(String charsetName) {
            Charset charset = Charset.forName(charsetName);
            byte[] expected = populated(charset).encode();

            byte[] fldt = filled('#', 1000, charset);
            System.arraycopy(expected, 0, fldt, 0, TrnxRecord.RECORD_LENGTH);

            TrnxRecord decoded = TrnxRecord.decode(fldt, charset);

            assertThat(decoded.encode()).hasSize(350).isEqualTo(expected);
            assertThat(decoded.recordImage())
                    .as("not one of the 650 bytes past byte 350 may appear anywhere in the record")
                    .doesNotContain("#");
            assertThat(new String(decoded.encode(), charset)).doesNotContain("#");
            assertThat(decoded.readTrnxCardNum()).isEqualTo(picX(FIXTURE_CARD_NUM, 16));
            assertThat(decoded.readTrnxId()).isEqualTo(FIXTURE_TRAN_ID);
            assertThat(decoded.readTrnxAmt()).isEqualByComparingTo("504.77");
            assertThat(decoded.readFiller())
                    .as("the trailing FILLER is the span the overflow would land in first")
                    .isEqualTo(" ".repeat(20));
        }

        @Test
        @DisplayName("bytes beyond 350 are discarded, not shifted in from the right")
        void bytesBeyondTheReceiverAreDiscarded() {
            byte[] source = filled('X', 400, ASCII);
            for (int i = 350; i < 400; i++) {
                source[i] = (byte) 'Z';
            }

            TrnxRecord decoded = TrnxRecord.decode(source, ASCII);

            assertThat(decoded.recordImage())
                    .as("a PIC X receiver fills from the left and drops the overflow")
                    .isEqualTo("X".repeat(350))
                    .doesNotContain("Z");
        }

        @Test
        @DisplayName("a span of exactly 350 bytes is taken as it stands")
        void exactWidthSpanIsTakenAsIs() {
            byte[] source = populated(ASCII).encode();

            assertThat(TrnxRecord.decode(source, ASCII).encode()).isEqualTo(source);
        }

        @ParameterizedTest(name = "a {0}-byte span")
        @ValueSource(ints = {349, 350, 351})
        @DisplayName("the receiver's own width is the boundary: one byte short pads, one byte over is ignored")
        void bothSidesOfTheWidthBoundaryAreAccepted(int width) {
            byte[] source = filled('W', width, ASCII);
            if (width > TrnxRecord.RECORD_LENGTH) {
                source[TrnxRecord.RECORD_LENGTH] = (byte) '!';
            }

            TrnxRecord decoded = TrnxRecord.decode(source, ASCII);

            assertThat(decoded.encode()).hasSize(350);
            assertThat(decoded.recordImage())
                    .as("the 351st byte belongs to no field of a 350-byte record")
                    .doesNotContain("!")
                    .isEqualTo("W".repeat(Math.min(width, TrnxRecord.RECORD_LENGTH))
                            + " ".repeat(Math.max(0, TrnxRecord.RECORD_LENGTH - width)));
        }

        @Test
        @DisplayName("a short span lands at the left and is padded on the right with the charset's space byte")
        void shortSpanIsPaddedOnTheRight() {
            TrnxRecord decoded = TrnxRecord.decode("ABC".getBytes(ASCII), ASCII);

            assertThat(decoded.encode()).hasSize(350);
            assertThat(decoded.readTrnxCardNum()).isEqualTo("ABC" + " ".repeat(13));
            assertThat(decoded.readFiller()).isEqualTo(" ".repeat(20));
        }

        @Test
        @DisplayName("a short span under IBM037 is padded with 0x40, not a hard-coded ASCII 0x20")
        void shortSpanUsesTheCharsetsOwnPadByte() {
            TrnxRecord decoded = TrnxRecord.decode("A".getBytes(EBCDIC), EBCDIC);

            byte[] image = decoded.encode();

            assertThat(image[0]).isEqualTo("A".getBytes(EBCDIC)[0]);
            assertThat(image[349]).isEqualTo((byte) 0x40);
            assertThat(decoded.readFiller()).isEqualTo(" ".repeat(20));
        }

        @Test
        @DisplayName("an empty span is rejected, since a sending field has at least one byte")
        void emptySpanIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TrnxRecord.decode(new byte[0], ASCII))
                    .withMessageContaining("empty")
                    .withMessageContaining("newRecord");
        }

        @Test
        @DisplayName("a null span is rejected")
        void nullSpanIsRejected() {
            assertThatNullPointerException()
                    .isThrownBy(() -> TrnxRecord.decode(null, ASCII));
        }

        @Test
        @DisplayName("the source array is never modified")
        void sourceArrayIsNotModified() {
            byte[] source = filled('Q', 1000, ASCII);
            byte[] before = source.clone();

            TrnxRecord decoded = TrnxRecord.decode(source, ASCII);
            decoded.writeTrnxCardNum("MUTATED");

            assertThat(source).isEqualTo(before);
        }
    }

    @Nested
    @DisplayName("wrap - the strict, exact-width entry point")
    class Wrapping {
        @Test
        @DisplayName("exactly 350 bytes are accepted and copied defensively")
        void exactWidthIsAccepted() {
            byte[] image = populated(ASCII).encode();

            TrnxRecord wrapped = TrnxRecord.wrap(image, ASCII);
            image[0] = (byte) 'Z';

            assertThat(wrapped.readTrnxCardNum())
                    .as("the record holds a copy, so mutating the caller's array cannot reach it")
                    .isEqualTo(FIXTURE_CARD_NUM);
        }

        @ParameterizedTest(name = "{0} bytes")
        @ValueSource(ints = {1, 330, 349, 351, 1000})
        @DisplayName("any width other than 350 is rejected rather than silently adjusted")
        void anyOtherWidthIsRejected(int width) {
            byte[] wrongWidth = filled(' ', width, ASCII);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TrnxRecord.wrap(wrongWidth, ASCII))
                    .withMessageContaining("350");
        }

        @Test
        @DisplayName("a null span is rejected")
        void nullSpanIsRejected() {
            assertThatNullPointerException().isThrownBy(() -> TrnxRecord.wrap(null, ASCII));
        }
    }

    @Nested
    @DisplayName("Serialisation - round trip byte identity (gate G21)")
    class Serialisation {
        @ParameterizedTest(name = "under {0}")
        @ValueSource(strings = {"US-ASCII", "IBM037"})
        @DisplayName("decode then encode returns a byte-for-byte identical 350-byte image, FILLER included")
        void roundTripIsByteIdentical(String charsetName) {
            Charset charset = Charset.forName(charsetName);
            byte[] original = populated(charset).encode();

            byte[] roundTripped = TrnxRecord.decode(original, charset).encode();

            assertThat(roundTripped)
                    .as("350 and not 330: the trailing FILLER X(20) is emitted")
                    .hasSize(TrnxRecord.RECORD_LENGTH)
                    .isEqualTo(original);
        }

        @Test
        @DisplayName("encode returns a fresh array, so mutating it cannot reach the record")
        void encodeReturnsADefensiveCopy() {
            TrnxRecord record = populated(ASCII);

            byte[] first = record.encode();
            Arrays.fill(first, (byte) 'Z');

            assertThat(record.encode()).isNotEqualTo(first);
            assertThat(record.readTrnxCardNum()).isEqualTo(FIXTURE_CARD_NUM);
        }

        @Test
        @DisplayName("encode(Charset) returns the same bytes when the code page matches")
        void encodeWithMatchingCharsetReturnsTheSameBytes() {
            TrnxRecord record = populated(EBCDIC);

            assertThat(record.encode(EBCDIC)).isEqualTo(record.encode());
        }

        @Test
        @DisplayName("encode(Charset) refuses a different code page instead of silently transcoding")
        void encodeWithMismatchedCharsetIsRejected() {
            TrnxRecord record = populated(ASCII);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> record.encode(EBCDIC))
                    .withMessageContaining("US-ASCII")
                    .withMessageContaining("IBM037");
        }

        @Test
        @DisplayName("encode(Charset) rejects a null code page")
        void encodeWithNullCharsetIsRejected() {
            TrnxRecord record = populated(ASCII);

            assertThatNullPointerException().isThrownBy(() -> record.encode(null));
        }

        @Test
        @DisplayName("recordImage is the untrimmed 350-character view of the same bytes")
        void recordImageMatchesTheEncodedBytes() {
            TrnxRecord record = populated(ASCII);

            assertThat(record.recordImage())
                    .hasSize(350)
                    .isEqualTo(new String(record.encode(), ASCII));
        }
    }

    @Nested
    @DisplayName("A hand-composed 350-byte image in COSTM01 field order (gates G19, G21)")
    class HandComposedImage {
        @Test
        @DisplayName("the composed image is itself exactly 350 characters, checked before it is used")
        void theComposedFixtureIsWellFormed() {
            String image = composedTrnxImage();

            assertThat(image).hasSize(350).hasSize(TrnxRecord.RECORD_LENGTH);
            assertThat(COMPOSED_AMT_IMAGE).hasSize(11).hasSize(TrnxRecord.TRNX_AMT_LENGTH);
        }

        @ParameterizedTest(name = "under {0}")
        @ValueSource(strings = {"US-ASCII", "IBM037"})
        @DisplayName("all fourteen spans decode at their copybook offsets, FILLER included")
        void everySpanDecodesAtItsCopybookOffset(String charsetName) {
            Charset charset = Charset.forName(charsetName);

            TrnxRecord record = TrnxRecord.wrap(composedTrnxImage().getBytes(charset), charset);

            assertThat(record.readTrnxCardNum()).isEqualTo("A".repeat(16));
            assertThat(record.readTrnxId()).isEqualTo("B".repeat(16));
            assertThat(record.readTrnxTypeCd()).isEqualTo("CC");
            assertThat(record.readTrnxCatCd()).isEqualTo(42);
            assertThat(record.readTrnxSource()).isEqualTo("E".repeat(10));
            assertThat(record.readTrnxDesc()).isEqualTo("F".repeat(100));
            assertThat(record.readTrnxAmt()).isEqualByComparingTo("1234.55");
            assertThat(record.readTrnxAmtImage()).isEqualTo(COMPOSED_AMT_IMAGE);
            assertThat(record.readTrnxMerchantId()).isEqualTo(123456);
            assertThat(record.readTrnxMerchantName()).isEqualTo("H".repeat(50));
            assertThat(record.readTrnxMerchantCity()).isEqualTo("I".repeat(50));
            assertThat(record.readTrnxMerchantZip()).isEqualTo("J".repeat(10));
            assertThat(record.readTrnxOrigTs()).isEqualTo("K".repeat(26));
            assertThat(record.readTrnxProcTs()).isEqualTo("L".repeat(26));
            assertThat(record.readFiller()).isEqualTo(" ".repeat(20));
        }

        @ParameterizedTest(name = "under {0}")
        @ValueSource(strings = {"US-ASCII", "IBM037"})
        @DisplayName("the composed image re-encodes byte for byte, and the group views cover it exactly")
        void theComposedImageReEncodesByteForByte(String charsetName) {
            Charset charset = Charset.forName(charsetName);
            String image = composedTrnxImage();

            TrnxRecord record = TrnxRecord.wrap(image.getBytes(charset), charset);

            assertThat(record.encode()).hasSize(350).isEqualTo(image.getBytes(charset));
            assertThat(record.encode(charset)).isEqualTo(image.getBytes(charset));
            assertThat(record.recordImage()).hasSize(350).isEqualTo(image);
            assertThat(record.readTrnxKey()).isEqualTo(image.substring(0, 32));
            assertThat(record.readTrnxRest()).isEqualTo(image.substring(32, 350));
            assertThat(record.readTrnxKey() + record.readTrnxRest()).isEqualTo(image);
        }

        @Test
        @DisplayName("the same logical record is field-identical whether its bytes were made in IBM037 or US-ASCII")
        void theSameRecordIsFieldIdenticalUnderEitherCodePage() {
            String image = composedTrnxImage();

            TrnxRecord ascii = TrnxRecord.wrap(image.getBytes(ASCII), ASCII);
            TrnxRecord ebcdic = TrnxRecord.wrap(image.getBytes(EBCDIC), EBCDIC);

            assertThat(ascii.encode()).isNotEqualTo(ebcdic.encode());
            assertThat(ascii.readTrnxCardNum()).isEqualTo(ebcdic.readTrnxCardNum());
            assertThat(ascii.readTrnxDesc()).isEqualTo(ebcdic.readTrnxDesc());
            assertThat(ascii.readTrnxCatCd()).isEqualTo(ebcdic.readTrnxCatCd());
            assertThat(ascii.readTrnxMerchantId()).isEqualTo(ebcdic.readTrnxMerchantId());
            assertThat(ascii.readTrnxAmt()).isEqualByComparingTo(ebcdic.readTrnxAmt());
            assertThat(ascii.readTrnxAmt().scale()).isEqualTo(ebcdic.readTrnxAmt().scale()).isEqualTo(2);
            assertThat(ascii.recordImage()).isEqualTo(ebcdic.recordImage()).isEqualTo(image);
        }
    }

    @Nested
    @DisplayName("Byte-index boundaries - 1-based COBOL positions against 0-based Java indexes (gate G33)")
    class ByteIndexBoundaries {
        @Test
        @DisplayName("the first byte is index 0 and belongs to TRNX-CARD-NUM, COBOL position 1")
        void theFirstByteIsIndexZero() {
            TrnxRecord record = TrnxRecord.newRecord(ASCII);
            record.writeTrnxCardNum("Z000000000000009");

            assertThat(TrnxRecord.TRNX_CARD_NUM_OFFSET).isZero();
            assertThat(record.recordImage().charAt(0))
                    .as("COBOL byte position 1 is Java index 0")
                    .isEqualTo('Z');
            assertThat(record.encode()[0]).isEqualTo((byte) 'Z');
            assertThat(record.readRawImage(TrnxRecord.TRNX_CARD_NUM).charAt(0)).isEqualTo('Z');
            assertThat(record.readTrnxKey().charAt(0)).isEqualTo('Z');
        }

        @Test
        @DisplayName("the last byte is index 349 and is the final byte of FILLER, COBOL position 350")
        void theLastByteIsIndexThreeFortyNine() {
            String image = composedTrnxImage().substring(0, 349) + "@";
            assertThat(image).hasSize(350);

            TrnxRecord record = TrnxRecord.wrap(image.getBytes(ASCII), ASCII);

            assertThat(TrnxRecord.FILLER_OFFSET + TrnxRecord.FILLER_LENGTH - 1)
                    .as("the last addressable index is one below the record length")
                    .isEqualTo(349)
                    .isEqualTo(TrnxRecord.RECORD_LENGTH - 1);
            assertThat(record.recordImage().charAt(349)).isEqualTo('@');
            assertThat(record.encode()[349]).isEqualTo((byte) '@');
            assertThat(record.readFiller())
                    .hasSize(20)
                    .isEqualTo(" ".repeat(19) + "@");
            assertThat(record.readTrnxRest().charAt(TrnxRecord.TRNX_REST_LENGTH - 1))
                    .as("the group view ends on the same byte the record does")
                    .isEqualTo('@');
        }

        @Test
        @DisplayName("byte 350 lies outside the record: neither storage nor an overlay may reach it")
        void byteThreeFiftyIsOutsideTheRecord() {
            assertThat(TrnxRecord.newRecord(ASCII).recordImage()).hasSize(350);
            assertThat(TrnxRecord.newRecord(ASCII).encode()).hasSize(350);

            List<FieldSpan> spans = new ArrayList<>(TrnxRecord.layout().storageSpans());
            spans.add(FieldSpan.alphanumeric("BEYOND-THE-RECORD", 350, 1));

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new RecordLayout(TrnxRecord.RECORD_LENGTH, spans))
                    .withMessageContaining("351")
                    .withMessageContaining("350");

            List<FieldSpan> overreaching = new ArrayList<>(TrnxRecord.layout().spans());
            overreaching.add(FieldSpan.redefining("KEY-AND-ONE-BYTE-TOO-MANY", 349, 2,
                    TrnxRecord.TRNX_KEY.kind()));

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new RecordLayout(TrnxRecord.RECORD_LENGTH, overreaching))
                    .withMessageContaining("351");
        }
    }

    @Nested
    @DisplayName("TRNX-AMT - zoned sign overpunch and truncation (gates G23, G24)")
    class Amount {
        @ParameterizedTest(name = "{0} stores as the 11-byte image {1}")
        @CsvSource({
                "504.77,        0000005047G",
                "-919.00,       0000009190}",
                "0.00,          0000000000{",
                "-0.01,         0000000000J",
                "0.01,          0000000000A",
                "1.00,          0000000010{",
                "-1.00,         0000000010}",
                "999999999.99,  9999999999I",
                "-999999999.99, 9999999999R"
        })
        @DisplayName("the sign is overpunched into the trailing byte and reserves no byte of its own")
        void signIsOverpunchedIntoTheTrailingByte(String amount, String expectedImage) {
            TrnxRecord record = TrnxRecord.newRecord(ASCII);

            record.writeTrnxAmt(new BigDecimal(amount));

            assertThat(record.readTrnxAmtImage())
                    .as("%s must occupy exactly 11 bytes whatever its sign", amount)
                    .hasSize(TrnxRecord.TRNX_AMT_LENGTH)
                    .isEqualTo(expectedImage);
            assertThat(record.readTrnxAmt()).isEqualByComparingTo(amount);
            assertThat(record.encode()).hasSize(350);
        }

        @ParameterizedTest(name = "{0} truncates to {1}")
        @CsvSource({
                "1.239,    1.23",
                "-1.239,   -1.23",
                "1.999,    1.99",
                "-1.999,   -1.99",
                "0.005,    0.00",
                "-0.005,   0.00",
                "504.7777, 504.77"
        })
        @DisplayName("excess fractional digits truncate toward zero and never round, because ROUNDED appears nowhere")
        void excessFractionDigitsTruncateTowardZero(String given, String expected) {
            TrnxRecord record = TrnxRecord.newRecord(ASCII);

            record.writeTrnxAmt(new BigDecimal(given));

            assertThat(record.readTrnxAmt())
                    .as("RoundingMode.DOWN, never HALF_UP or HALF_EVEN")
                    .isEqualByComparingTo(expected);
        }

        @Test
        @DisplayName("truncation is genuinely DOWN, differing from HALF_UP and HALF_EVEN on the same input")
        void truncationDiffersFromRounding() {
            BigDecimal given = new BigDecimal("1.239");
            TrnxRecord record = TrnxRecord.newRecord(ASCII);

            record.writeTrnxAmt(given);

            assertThat(record.readTrnxAmt())
                    .isEqualByComparingTo(given.setScale(2, RoundingMode.DOWN))
                    .isNotEqualByComparingTo(given.setScale(2, RoundingMode.HALF_UP))
                    .isNotEqualByComparingTo(given.setScale(2, RoundingMode.HALF_EVEN));
            assertThat(CobolDecimal.COBOL_ROUNDING).isEqualTo(RoundingMode.DOWN);
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"504.77", "-919.00", "0.00", "0", "1", "-1", "504.7", "1.239"})
        @DisplayName("every decoded amount carries scale exactly 2, so zero is 0.00 and not BigDecimal.ZERO")
        void everyDecodedAmountCarriesScaleTwo(String amount) {
            TrnxRecord record = TrnxRecord.newRecord(ASCII);

            record.writeTrnxAmt(new BigDecimal(amount));
            BigDecimal read = record.readTrnxAmt();

            assertThat(read.scale()).isEqualTo(TrnxRecord.TRNX_AMT_SCALE).isEqualTo(2);
        }

        @Test
        @DisplayName("zero renders as the scale-2 zero, unequal to BigDecimal.ZERO under equals but equal under compareTo")
        void zeroIsScaleTwoZero() {
            TrnxRecord record = TrnxRecord.newRecord(ASCII);

            record.writeTrnxAmt(BigDecimal.ZERO);
            BigDecimal read = record.readTrnxAmt();

            assertThat(read).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(read).isEqualTo(new BigDecimal("0.00"));
            assertThat(read.equals(BigDecimal.ZERO))
                    .as("equals is scale sensitive, which is exactly why compareTo is mandated")
                    .isFalse();
            assertThat(read).isEqualByComparingTo(CobolDecimal.monetaryZero());
        }

        @Test
        @DisplayName("an oversized value keeps its low-order digits and its sign, since ON SIZE ERROR appears nowhere")
        void oversizedValueKeepsLowOrderDigitsAndSign() {
            TrnxRecord record = TrnxRecord.newRecord(ASCII);

            record.writeTrnxAmt(new BigDecimal("1234567890.99"));

            assertThat(record.readTrnxAmtImage()).hasSize(11);
            assertThat(record.readTrnxAmt()).isEqualByComparingTo("234567890.99");
        }

        @Test
        @DisplayName("a null amount is rejected")
        void nullAmountIsRejected() {
            TrnxRecord record = TrnxRecord.newRecord(ASCII);

            assertThatNullPointerException().isThrownBy(() -> record.writeTrnxAmt(null));
        }

        @Test
        @DisplayName("the amount round trips through the raw span under both code pages")
        void amountRoundTripsUnderBothCodePages() {
            for (Charset charset : List.of(ASCII, EBCDIC)) {
                TrnxRecord record = TrnxRecord.newRecord(charset);
                record.writeTrnxAmt(new BigDecimal("-919.00"));

                TrnxRecord reread = TrnxRecord.wrap(record.encode(), charset);

                assertThat(reread.readTrnxAmtImage()).isEqualTo("0000009190}");
                assertThat(reread.readTrnxAmt()).isEqualByComparingTo("-919.00");
            }
        }

        @ParameterizedTest(name = "the stored image {0} denotes {1}")
        @CsvSource({
                "0000012345E,   1234.55",
                "0000012345N,  -1234.55",
                "00000123455,   1234.55",
                "0000000000{,   0.00",
                "0000000000},   0.00",
                "0000000001A,   0.11",
                "99999999999,   999999999.99",
                "9999999999R,  -999999999.99"
        })
        @DisplayName("every zoned image the dataset can hold decodes to its value at scale exactly 2")
        void storedImagesDecodeToTheirValues(String image, String expected) {
            assertThat(image)
                    .as("a PIC S9(09)V99 span is 9 + 2 characters, the sign taking none of its own")
                    .hasSize(TrnxRecord.TRNX_AMT_LENGTH);

            TrnxRecord record = recordWithAmountImage(image, ASCII);

            assertThat(record.readTrnxAmt()).isEqualByComparingTo(expected);
            assertThat(record.readTrnxAmt().scale())
                    .as("scale is part of the value: 1234.5 and 1234.55 are not the same stored field")
                    .isEqualTo(2)
                    .isEqualTo(CobolDecimal.MONETARY_SCALE)
                    .isEqualTo(TrnxRecord.TRNX_AMT_SCALE);
            assertThat(record.readTrnxAmtImage())
                    .as("decoding interprets the span and never rewrites it")
                    .isEqualTo(image);
        }

        @Test
        @DisplayName("a negative zero decodes to the scale-2 zero, its sign surviving only in the raw image")
        void negativeZeroDecodesToZeroAndKeepsItsSignInTheImage() {
            TrnxRecord negativeZero = recordWithAmountImage("0000000000}", ASCII);
            TrnxRecord positiveZero = recordWithAmountImage("0000000000{", ASCII);

            assertThat(negativeZero.readTrnxAmt()).isEqualByComparingTo(new BigDecimal("0.00"));
            assertThat(negativeZero.readTrnxAmt().scale()).isEqualTo(2);
            assertThat(negativeZero.readTrnxAmt().signum()).isZero();
            assertThat(positiveZero.readTrnxAmt().signum()).isZero();
            assertThat(negativeZero.readTrnxAmt())
                    .isEqualByComparingTo(positiveZero.readTrnxAmt());

            assertThat(negativeZero.readTrnxAmtImage()).isEqualTo("0000000000}");
            assertThat(positiveZero.readTrnxAmtImage()).isEqualTo("0000000000{");
            assertThat(negativeZero.readTrnxAmtImage())
                    .as("the stored bytes differ even though the decoded quantities do not")
                    .isNotEqualTo(positiveZero.readTrnxAmtImage());
        }

        @Test
        @DisplayName("the same overpunch decodes identically from IBM037 bytes and from US-ASCII bytes")
        void theSameOverpunchDecodesIdenticallyUnderBothCodePages() {
            String image = "0000012345E";

            TrnxRecord fromAscii = recordWithAmountImage(image, ASCII);
            TrnxRecord fromEbcdic = recordWithAmountImage(image, EBCDIC);

            byte[] asciiSpan = fromAscii.readRawBytes(TrnxRecord.TRNX_AMT);
            byte[] ebcdicSpan = fromEbcdic.readRawBytes(TrnxRecord.TRNX_AMT);
            assertThat(asciiSpan).hasSize(11).isNotEqualTo(ebcdicSpan);
            assertThat(asciiSpan[10]).isEqualTo((byte) 0x45);
            assertThat(ebcdicSpan[10]).isEqualTo((byte) 0xC5);

            assertThat(fromEbcdic.readTrnxAmt())
                    .as("one quantity, two code pages, no platform default anywhere")
                    .isEqualByComparingTo(fromAscii.readTrnxAmt())
                    .isEqualByComparingTo("1234.55");
            assertThat(fromEbcdic.readTrnxAmt().scale())
                    .isEqualTo(fromAscii.readTrnxAmt().scale())
                    .isEqualTo(2);
            assertThat(fromEbcdic.readTrnxAmtImage()).isEqualTo(fromAscii.readTrnxAmtImage())
                    .isEqualTo(image);

            assertThat(recordWithAmountImage("0000000000{", EBCDIC).readTrnxAmt())
                    .isEqualByComparingTo(recordWithAmountImage("0000000000{", ASCII).readTrnxAmt());
            assertThat(recordWithAmountImage("0000000000}", EBCDIC).readTrnxAmt())
                    .isEqualByComparingTo(recordWithAmountImage("0000000000}", ASCII).readTrnxAmt());
        }

        @Test
        @DisplayName("an unrecognised trailing character is reported, not read as a digit")
        void anUnrecognisedTrailingCharacterIsRejected() {
            TrnxRecord record = recordWithAmountImage("0000012345Z", ASCII);

            assertThatIllegalArgumentException().isThrownBy(record::readTrnxAmt);
            assertThat(record.readTrnxAmtImage())
                    .as("the raw span stays inspectable so the offending row can still be diffed")
                    .isEqualTo("0000012345Z");
        }

        @Test
        @DisplayName("a non-digit in a leading position is reported too, since only the last byte carries a sign")
        void aNonDigitBeforeTheTrailingByteIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(recordWithAmountImage("00000A2345E", ASCII)::readTrnxAmt);
            assertThatIllegalArgumentException()
                    .as("an all-blank span is the state a group MOVE of a blank row leaves behind")
                    .isThrownBy(recordWithAmountImage(" ".repeat(11), ASCII)::readTrnxAmt);
        }

        @Test
        @DisplayName("the edit masks of the statement line belong to the writer, not to this record")
        void editedMasksAreNotThisTypesConcern() {
            TrnxRecord record = TrnxRecord.newRecord(ASCII);
            record.writeTrnxAmt(new BigDecimal("-919.00"));

            assertThat(record.readTrnxAmtImage())
                    .doesNotContain(".")
                    .doesNotContain(",")
                    .doesNotContain("-")
                    .doesNotContain("Z")
                    .isEqualTo("0000009190}");
        }
    }

    @Nested
    @DisplayName("Read-through - typed accessors decode the live bytes, never a cached copy")
    class ReadThrough {
        @ParameterizedTest(name = "under {0}")
        @ValueSource(strings = {"US-ASCII", "IBM037"})
        @DisplayName("TRNX-AMT read straight after a wholesale TRNX-REST write reflects the new amount")
        void amountReflectsAWholesaleRestWrite(String charsetName) {
            Charset charset = Charset.forName(charsetName);

            TrnxRecord source = TrnxRecord.newRecord(charset);
            source.writeTrnxAmt(new BigDecimal("42.42"));
            String restImage = source.readTrnxRest();

            TrnxRecord target = TrnxRecord.newRecord(charset);
            target.writeTrnxAmt(new BigDecimal("999.99"));
            assertThat(target.readTrnxAmt()).isEqualByComparingTo("999.99");

            target.writeTrnxRest(restImage);

            assertThat(target.readTrnxAmt())
                    .as("a cached decode would still report 999.99 and corrupt the statement total")
                    .isEqualByComparingTo("42.42");
            assertThat(target.readTrnxAmt().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("TRNX-DESC read straight after a wholesale TRNX-REST write reflects the new description")
        void descriptionReflectsAWholesaleRestWrite() {
            TrnxRecord source = TrnxRecord.newRecord(ASCII);
            source.writeTrnxDesc("GROCERIES");
            String restImage = source.readTrnxRest();

            TrnxRecord target = TrnxRecord.newRecord(ASCII);
            target.writeTrnxDesc("SUPERSEDED");

            target.writeTrnxRest(restImage);

            assertThat(target.readTrnxDesc()).isEqualTo(picX("GROCERIES", 100));
        }

        @Test
        @DisplayName("every field of TRNX-REST is refreshed by one group write, not just the ones read back")
        void everyRestFieldIsRefreshedByOneGroupWrite() {
            TrnxRecord source = populated(ASCII);
            TrnxRecord target = TrnxRecord.newRecord(ASCII);

            target.writeTrnxRest(source.readTrnxRest());

            assertThat(target.readTrnxTypeCd()).isEqualTo(FIXTURE_TYPE_CD);
            assertThat(target.readTrnxCatCd()).isEqualTo(1);
            assertThat(target.readTrnxSource()).isEqualTo(picX(FIXTURE_SOURCE, 10));
            assertThat(target.readTrnxDesc()).isEqualTo(picX(FIXTURE_DESC, 100));
            assertThat(target.readTrnxAmt()).isEqualByComparingTo("504.77");
            assertThat(target.readTrnxMerchantId()).isEqualTo(800000000);
            assertThat(target.readTrnxMerchantName()).isEqualTo(picX(FIXTURE_MERCHANT_NAME, 50));
            assertThat(target.readTrnxMerchantCity()).isEqualTo(picX(FIXTURE_MERCHANT_CITY, 50));
            assertThat(target.readTrnxMerchantZip()).isEqualTo(picX(FIXTURE_MERCHANT_ZIP, 10));
            assertThat(target.readTrnxOrigTs()).isEqualTo(FIXTURE_ORIG_TS);
            assertThat(target.readTrnxProcTs()).isEqualTo(FULL_PROC_TS);
            assertThat(target.readFiller())
                    .as("FILLER is the twelfth span inside TRNX-REST and is carried by the group move "
                            + "like any other, which is why the group is 318 bytes and not 298")
                    .isEqualTo(source.readFiller())
                    .isEqualTo(" ".repeat(20));
        }

        @Test
        @DisplayName("a wholesale TRNX-KEY write is visible through both key components, and the reverse")
        void keyComponentsAndGroupShareStorage() {
            TrnxRecord record = TrnxRecord.newRecord(ASCII);

            record.writeTrnxKey(FIXTURE_CARD_NUM + FIXTURE_TRAN_ID);

            assertThat(record.readTrnxCardNum()).isEqualTo(FIXTURE_CARD_NUM);
            assertThat(record.readTrnxId()).isEqualTo(FIXTURE_TRAN_ID);

            record.writeTrnxCardNum("0000000000000009");
            record.writeTrnxId("0000000000000007");

            assertThat(record.readTrnxKey()).isEqualTo("00000000000000090000000000000007");
        }

        @Test
        @DisplayName("a group write does not disturb storage outside the group")
        void groupWriteDoesNotDisturbNeighbouringStorage() {
            TrnxRecord record = populated(ASCII);
            String keyBefore = record.readTrnxKey();

            record.writeTrnxRest(" ".repeat(TrnxRecord.TRNX_REST_LENGTH));

            assertThat(record.readTrnxKey()).isEqualTo(keyBefore);

            String restBefore = record.readTrnxRest();
            record.writeTrnxKey(" ".repeat(TrnxRecord.TRNX_KEY_LENGTH));
            assertThat(record.readTrnxRest()).isEqualTo(restBefore);
        }

        @Test
        @DisplayName("the whole CBSTM03A sequence in order: write key, move the group, then read three fields back")
        void theCobolSequenceIsReproducedInOrder() {
            TrnxRecord stored = TrnxRecord.newRecord(ASCII);
            stored.writeTrnxDesc("Purchase at Abshire-Lowe");
            stored.writeTrnxAmt(new BigDecimal("504.77"));
            String tableSlot = stored.readTrnxRest();

            TrnxRecord target = TrnxRecord.newRecord(ASCII);

            target.writeTrnxCardNum("4859452612877065");
            target.writeTrnxId("0000000000683580");
            target.writeTrnxRest(tableSlot);

            assertThat(target.readTrnxId()).isEqualTo("0000000000683580");
            assertThat(target.readTrnxDesc()).isEqualTo(picX("Purchase at Abshire-Lowe", 100));
            assertThat(target.readTrnxAmt()).isEqualByComparingTo("504.77");

            assertThat(target.readTrnxAmt())
                    .as("a second read must agree with the first: nothing is consumed and nothing cached")
                    .isEqualByComparingTo("504.77");
            assertThat(target.readTrnxCardNum())
                    .as("the key was written before the group move and must survive it")
                    .isEqualTo("4859452612877065");
        }

        @Test
        @DisplayName("accumulating the amount reproduces ADD TRNX-AMT TO WS-TOTAL-AMT at scale 2, truncating")
        void accumulationMirrorsTheCobolAdd() {
            BigDecimal total = CobolDecimal.monetaryZero();
            assertThat(total.scale()).isEqualTo(2);

            for (String amount : List.of("504.77", "-919.00", "0.01", "1234.559")) {
                TrnxRecord record = TrnxRecord.newRecord(ASCII);
                record.writeTrnxAmt(new BigDecimal(amount));
                total = CobolDecimal.add(total, record.readTrnxAmt(), CobolDecimal.MONETARY_SCALE);
            }

            assertThat(total).isEqualByComparingTo("820.33");
            assertThat(total.scale()).isEqualTo(2).isEqualTo(CobolDecimal.MONETARY_SCALE);
            assertThat(CobolDecimal.COBOL_ROUNDING).isEqualTo(RoundingMode.DOWN);
        }

        @Test
        @DisplayName("writing the same key twice is idempotent, and the key group round trips both ways")
        void repeatedKeyWritesAreIdempotent() {
            TrnxRecord record = populated(ASCII);
            byte[] once = record.encode();

            record.writeTrnxCardNum(FIXTURE_CARD_NUM);
            record.writeTrnxId(FIXTURE_TRAN_ID);

            assertThat(record.encode())
                    .as("re-moving identical bytes into a fixed-width field changes nothing")
                    .isEqualTo(once);

            byte[] keyBytes = record.readTrnxKeyBytes();
            record.writeTrnxKeyBytes(keyBytes);
            record.writeTrnxKeyBytes(keyBytes);

            assertThat(record.encode()).isEqualTo(once);
            assertThat(record.readTrnxCardNum()).isEqualTo(FIXTURE_CARD_NUM);
            assertThat(record.readTrnxId()).isEqualTo(FIXTURE_TRAN_ID);
            assertThat(record.readTrnxKey()).isEqualTo(FIXTURE_CARD_NUM + FIXTURE_TRAN_ID);
        }
    }

    @Nested
    @DisplayName("Wholesale span symmetry - CBSTM03A.CBL:829 followed by :426-427")
    class WholesaleSpans {
        @Test
        @DisplayName("reading TRNX-REST out and writing it back into a fresh record reproduces bytes 32 to 349")
        void restSpanRoundTripsThroughTheTable() {
            TrnxRecord source = populated(ASCII);

            String tableSlot = source.readTrnxRest();
            assertThat(tableSlot)
                    .as("WS-TRAN-REST is PIC X(318)")
                    .hasSize(TrnxRecord.TRNX_REST_LENGTH);

            TrnxRecord target = TrnxRecord.newRecord(ASCII);
            target.writeTrnxRest(tableSlot);

            byte[] sourceImage = source.encode();
            byte[] targetImage = target.encode();

            assertThat(Arrays.copyOfRange(targetImage, 32, 350))
                    .as("bytes 32 to 349 must agree exactly")
                    .isEqualTo(Arrays.copyOfRange(sourceImage, 32, 350));
        }

        @Test
        @DisplayName("the byte paths for both groups require the exact span width")
        void byteLevelGroupWritesRequireExactWidth() {
            TrnxRecord record = TrnxRecord.newRecord(ASCII);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> record.writeTrnxKeyBytes(new byte[31]));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> record.writeTrnxRestBytes(new byte[319]));
            assertThatNullPointerException().isThrownBy(() -> record.writeTrnxKeyBytes(null));
            assertThatNullPointerException().isThrownBy(() -> record.writeTrnxRestBytes(null));
        }

        @Test
        @DisplayName("the byte paths round trip both groups exactly")
        void byteLevelGroupPathsRoundTrip() {
            TrnxRecord source = populated(ASCII);
            TrnxRecord target = TrnxRecord.newRecord(ASCII);

            byte[] keyBytes = source.readTrnxKeyBytes();
            byte[] restBytes = source.readTrnxRestBytes();
            assertThat(keyBytes).hasSize(32);
            assertThat(restBytes).hasSize(318);

            target.writeTrnxKeyBytes(keyBytes);
            target.writeTrnxRestBytes(restBytes);

            assertThat(target.encode()).isEqualTo(source.encode());
        }

        @Test
        @DisplayName("the group byte accessors return defensive copies")
        void groupByteAccessorsReturnCopies() {
            TrnxRecord record = populated(ASCII);

            byte[] rest = record.readTrnxRestBytes();
            Arrays.fill(rest, (byte) 'Z');

            assertThat(record.readTrnxAmt()).isEqualByComparingTo("504.77");
        }

        @Test
        @DisplayName("a short group value is padded on the right and an over-long one truncated on the right")
        void groupStringWritesFollowThePicXRule() {
            TrnxRecord record = TrnxRecord.newRecord(ASCII);

            record.writeTrnxKey("ABC");
            assertThat(record.readTrnxKey()).isEqualTo("ABC" + " ".repeat(29));

            record.writeTrnxKey("A".repeat(40));
            assertThat(record.readTrnxKey())
                    .as("a PIC X receiver keeps the leading characters")
                    .isEqualTo("A".repeat(32));

            assertThatNullPointerException().isThrownBy(() -> record.writeTrnxKey(null));
            assertThatNullPointerException().isThrownBy(() -> record.writeTrnxRest(null));
        }
    }

    @Nested
    @DisplayName("SORT-derived input - CREASTMT.JCL:54 reproduced, not corrected (practice B4)")
    class SortDerivedInput {
        @Test
        @DisplayName("a record reshaped by the legacy OUTREC decodes field for field")
        void sortDerivedRecordDecodesFieldForField() {
            String derived = applyCreastmtOutrec(tranRecordImage(FULL_PROC_TS));

            TrnxRecord record = TrnxRecord.wrap(derived.getBytes(ASCII), ASCII);

            assertThat(record.readTrnxCardNum()).isEqualTo(FIXTURE_CARD_NUM);
            assertThat(record.readTrnxId()).isEqualTo(FIXTURE_TRAN_ID);
            assertThat(record.readTrnxTypeCd()).isEqualTo(FIXTURE_TYPE_CD);
            assertThat(record.readTrnxCatCd()).isEqualTo(1);
            assertThat(record.readTrnxSource()).isEqualTo(picX(FIXTURE_SOURCE, 10));
            assertThat(record.readTrnxDesc()).isEqualTo(picX(FIXTURE_DESC, 100));
            assertThat(record.readTrnxAmtImage()).isEqualTo(FIXTURE_AMT_IMAGE);
            assertThat(record.readTrnxAmt()).isEqualByComparingTo("504.77");
            assertThat(record.readTrnxMerchantId()).isEqualTo(800000000);
            assertThat(record.readTrnxMerchantName()).isEqualTo(picX(FIXTURE_MERCHANT_NAME, 50));
            assertThat(record.readTrnxMerchantCity()).isEqualTo(picX(FIXTURE_MERCHANT_CITY, 50));
            assertThat(record.readTrnxMerchantZip()).isEqualTo(picX(FIXTURE_MERCHANT_ZIP, 10));
            assertThat(record.readTrnxOrigTs()).isEqualTo(FIXTURE_ORIG_TS);
        }

        @Test
        @DisplayName("TRNX-PROC-TS legitimately carries 24 characters and two trailing spaces, and is left alone")
        void processTimestampLosesItsLastTwoCharacters() {
            String derived = applyCreastmtOutrec(tranRecordImage(FULL_PROC_TS));

            TrnxRecord record = TrnxRecord.wrap(derived.getBytes(ASCII), ASCII);
            String procTs = record.readTrnxProcTs();

            assertThat(procTs)
                    .as("the field is still X(26); it is the sort that under-fills it")
                    .hasSize(TrnxRecord.TRNX_PROC_TS_LENGTH);
            assertThat(procTs)
                    .isEqualTo(FULL_PROC_TS.substring(0, TrnxRecord.TRNX_PROC_TS_SORT_DERIVED_LENGTH)
                            + "  ");
            assertThat(procTs.substring(24))
                    .as("OUTREC 279:279,50 copies 50 of the 52 trailing bytes")
                    .isEqualTo("  ");
            assertThat(TrnxRecord.TRNX_PROC_TS_SORT_DERIVED_LENGTH).isEqualTo(24);
            assertThat(TrnxRecord.TRNX_PROC_TS_LENGTH).isEqualTo(26);
        }

        @Test
        @DisplayName("FILLER X(20) arrives entirely blank because output bytes 329 to 350 receive nothing")
        void fillerArrivesBlank() {
            String derived = applyCreastmtOutrec(tranRecordImage(FULL_PROC_TS));

            TrnxRecord record = TrnxRecord.wrap(derived.getBytes(ASCII), ASCII);

            assertThat(record.readFiller()).isEqualTo(" ".repeat(20));
        }

        @Test
        @DisplayName("a SORT-derived record re-encodes byte-identically and is neither normalised nor rejected")
        void sortDerivedRecordReEncodesByteIdentically() {
            byte[] derived = applyCreastmtOutrec(tranRecordImage(FULL_PROC_TS)).getBytes(ASCII);

            assertThat(TrnxRecord.wrap(derived, ASCII).encode()).isEqualTo(derived);
            assertThat(TrnxRecord.decode(derived, ASCII).encode()).isEqualTo(derived);
        }

        @Test
        @DisplayName("the fixture's own blank process timestamp is equally acceptable")
        void blankProcessTimestampIsAccepted() {
            byte[] derived = applyCreastmtOutrec(tranRecordImage("")).getBytes(ASCII);

            TrnxRecord record = TrnxRecord.wrap(derived, ASCII);

            assertThat(record.readTrnxProcTs()).isEqualTo(" ".repeat(26));
            assertThat(record.readTrnxAmt()).isEqualByComparingTo("504.77");
            assertThat(record.encode()).isEqualTo(derived);
        }
    }

    @Nested
    @DisplayName("Field semantics - padding direction, truncation direction and untrimmed reads")
    class FieldSemantics {
        @Test
        @DisplayName("a PIC X read is never trimmed, because the padding is part of the field's value")
        void picXReadsAreNotTrimmed() {
            TrnxRecord record = TrnxRecord.newRecord(ASCII);

            record.writeTrnxDesc("SHORT");
            record.writeTrnxMerchantName("ACME");

            assertThat(record.readTrnxDesc())
                    .hasSize(100)
                    .startsWith("SHORT")
                    .endsWith(" ")
                    .isEqualTo("SHORT" + " ".repeat(95));
            assertThat(record.readTrnxMerchantName()).hasSize(50).isEqualTo("ACME" + " ".repeat(46));
        }

        @ParameterizedTest(name = "{0} is padded on the right to {1} characters")
        @CsvSource({"16, TRNX-CARD-NUM", "16, TRNX-ID", "2, TRNX-TYPE-CD", "10, TRNX-SOURCE"})
        @DisplayName("PIC X fields pad on the right, not the left")
        void picXFieldsPadOnTheRight(int width, String fieldName) {
            TrnxRecord record = TrnxRecord.newRecord(ASCII);
            FieldSpan span = TrnxRecord.layout().span(fieldName);

            record.writeTrnxCardNum("A");
            record.writeTrnxId("A");
            record.writeTrnxTypeCd("A");
            record.writeTrnxSource("A");

            assertThat(record.readRawImage(span))
                    .hasSize(width)
                    .startsWith("A")
                    .isEqualTo("A" + " ".repeat(width - 1));
        }

        @Test
        @DisplayName("an over-long PIC X value loses its trailing characters, keeping the leading ones")
        void picXTruncatesOnTheRight() {
            TrnxRecord record = TrnxRecord.newRecord(ASCII);

            record.writeTrnxTypeCd("ABCD");
            record.writeTrnxMerchantZip("0123456789XYZ");

            assertThat(record.readTrnxTypeCd()).isEqualTo("AB");
            assertThat(record.readTrnxMerchantZip()).isEqualTo("0123456789");
        }

        @ParameterizedTest(name = "TRNX-CAT-CD {0} is stored as {1}")
        @CsvSource({"7, 0007", "0, 0000", "1, 0001", "42, 0042", "9999, 9999"})
        @DisplayName("PIC 9 fields zero-fill on the LEFT, the mirror image of the PIC X rule")
        void pic9FieldsZeroFillOnTheLeft(int value, String expectedImage) {
            TrnxRecord record = TrnxRecord.newRecord(ASCII);

            record.writeTrnxCatCd(value);

            assertThat(record.readRawImage(TrnxRecord.TRNX_CAT_CD)).isEqualTo(expectedImage);
            assertThat(record.readTrnxCatCd()).isEqualTo(value);
        }

        @Test
        @DisplayName("an over-long PIC 9 value keeps its LOW-order digits, since a numeric receiver aligns on the decimal point")
        void pic9TruncatesOnTheLeft() {
            TrnxRecord record = TrnxRecord.newRecord(ASCII);

            record.writeTrnxCatCd(123456);

            assertThat(record.readRawImage(TrnxRecord.TRNX_CAT_CD))
                    .as("keeping the leading digits instead is the classic defect")
                    .isEqualTo("3456");
            assertThat(record.readTrnxCatCd()).isEqualTo(3456);
        }

        @Test
        @DisplayName("TRNX-MERCHANT-ID uses the full nine-digit range as a long")
        void merchantIdUsesTheFullNineDigitRange() {
            TrnxRecord record = TrnxRecord.newRecord(ASCII);

            record.writeTrnxMerchantId(999999999);
            assertThat(record.readTrnxMerchantId()).isEqualTo(999999999);
            assertThat(record.readRawImage(TrnxRecord.TRNX_MERCHANT_ID)).isEqualTo("999999999");

            record.writeTrnxMerchantId(7);
            assertThat(record.readRawImage(TrnxRecord.TRNX_MERCHANT_ID)).isEqualTo("000000007");
        }

        @Test
        @DisplayName("a negative value is refused by an unsigned PIC 9 field rather than stored as its magnitude")
        void unsignedNumericFieldsRefuseNegativeValues() {
            TrnxRecord record = TrnxRecord.newRecord(ASCII);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> record.writeTrnxCatCd(-1))
                    .withMessageContaining("PIC 9");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> record.writeTrnxMerchantId(-1))
                    .withMessageContaining("PIC 9");
        }

        @Test
        @DisplayName("a blank numeric span is reported precisely rather than coerced to a plausible zero")
        void blankNumericSpanIsReportedNotCoerced() {
            TrnxRecord record = TrnxRecord.decode(" ".repeat(350).getBytes(ASCII), ASCII);

            assertThatIllegalArgumentException().isThrownBy(record::readTrnxCatCd);
            assertThatIllegalArgumentException().isThrownBy(record::readTrnxMerchantId);
            assertThatIllegalArgumentException().isThrownBy(record::readTrnxAmt);

            assertThat(record.readRawImage(TrnxRecord.TRNX_CAT_CD))
                    .as("the raw span stays inspectable even when it will not decode")
                    .isEqualTo("    ");
        }

        @ParameterizedTest(name = "card number {0} survives as a String")
        @ValueSource(strings = {
                "0000000000000001",
                "0859452612877065",
                "0000000000000000",
                "4859452612877065"
        })
        @DisplayName("a leading zero in TRNX-CARD-NUM survives decode and encode, because PIC X is never parsed as a number")
        void leadingZerosArePreserved(String cardNumber) {
            TrnxRecord record = TrnxRecord.newRecord(ASCII);

            record.writeTrnxCardNum(cardNumber);
            TrnxRecord reread = TrnxRecord.wrap(record.encode(), ASCII);

            assertThat(reread.readTrnxCardNum()).isEqualTo(cardNumber);
            assertThat(reread.readTrnxKey()).startsWith(cardNumber);
        }

        @Test
        @DisplayName("a leading zero in TRNX-ID and TRNX-MERCHANT-ZIP survives too")
        void leadingZerosSurviveInOtherCharacterFields() {
            TrnxRecord record = TrnxRecord.newRecord(ASCII);

            record.writeTrnxId("0000000000683580");
            record.writeTrnxMerchantZip("07112");

            assertThat(record.readTrnxId()).isEqualTo("0000000000683580");
            assertThat(record.readTrnxMerchantZip()).isEqualTo("07112     ");
        }

        @Test
        @DisplayName("every character field rejects a null value rather than blanking the span")
        void characterFieldsRejectNull() {
            TrnxRecord record = TrnxRecord.newRecord(ASCII);

            assertThatNullPointerException().isThrownBy(() -> record.writeTrnxCardNum(null));
            assertThatNullPointerException().isThrownBy(() -> record.writeTrnxId(null));
            assertThatNullPointerException().isThrownBy(() -> record.writeTrnxTypeCd(null));
            assertThatNullPointerException().isThrownBy(() -> record.writeTrnxSource(null));
            assertThatNullPointerException().isThrownBy(() -> record.writeTrnxDesc(null));
            assertThatNullPointerException().isThrownBy(() -> record.writeTrnxMerchantName(null));
            assertThatNullPointerException().isThrownBy(() -> record.writeTrnxMerchantCity(null));
            assertThatNullPointerException().isThrownBy(() -> record.writeTrnxMerchantZip(null));
            assertThatNullPointerException().isThrownBy(() -> record.writeTrnxOrigTs(null));
            assertThatNullPointerException().isThrownBy(() -> record.writeTrnxProcTs(null));
        }

        @Test
        @DisplayName("all thirteen writable fields round trip together under both code pages")
        void everyWritableFieldRoundTrips() {
            for (Charset charset : List.of(ASCII, EBCDIC)) {
                TrnxRecord written = populated(charset);

                TrnxRecord reread = TrnxRecord.wrap(written.encode(), charset);

                assertThat(reread.readTrnxCardNum()).isEqualTo(FIXTURE_CARD_NUM);
                assertThat(reread.readTrnxId()).isEqualTo(FIXTURE_TRAN_ID);
                assertThat(reread.readTrnxTypeCd()).isEqualTo(FIXTURE_TYPE_CD);
                assertThat(reread.readTrnxCatCd()).isEqualTo(1);
                assertThat(reread.readTrnxSource()).isEqualTo(picX(FIXTURE_SOURCE, 10));
                assertThat(reread.readTrnxDesc()).isEqualTo(picX(FIXTURE_DESC, 100));
                assertThat(reread.readTrnxAmt()).isEqualByComparingTo("504.77");
                assertThat(reread.readTrnxMerchantId()).isEqualTo(800000000);
                assertThat(reread.readTrnxMerchantName()).isEqualTo(picX(FIXTURE_MERCHANT_NAME, 50));
                assertThat(reread.readTrnxMerchantCity()).isEqualTo(picX(FIXTURE_MERCHANT_CITY, 50));
                assertThat(reread.readTrnxMerchantZip()).isEqualTo(picX(FIXTURE_MERCHANT_ZIP, 10));
                assertThat(reread.readTrnxOrigTs()).isEqualTo(FIXTURE_ORIG_TS);
                assertThat(reread.readTrnxProcTs()).isEqualTo(FULL_PROC_TS);
                assertThat(reread.readFiller()).isEqualTo(" ".repeat(20));
            }
        }
    }

    @Nested
    @DisplayName("Raw span access and diagnostics")
    class RawAccessAndDiagnostics {
        @Test
        @DisplayName("every declared span is readable as an untrimmed image and as bytes")
        void everyDeclaredSpanIsReadableRaw() {
            TrnxRecord record = populated(ASCII);

            for (FieldSpan span : TrnxRecord.fieldSpans()) {
                assertThat(record.readRawImage(span))
                        .as("raw image of %s", span.name())
                        .hasSize(span.length());
                assertThat(record.readRawBytes(span))
                        .as("raw bytes of %s", span.name())
                        .hasSize(span.length());
            }
        }

        @Test
        @DisplayName("the raw byte accessor returns a defensive copy")
        void rawByteAccessorReturnsACopy() {
            TrnxRecord record = populated(ASCII);

            byte[] amount = record.readRawBytes(TrnxRecord.TRNX_AMT);
            Arrays.fill(amount, (byte) '0');

            assertThat(record.readTrnxAmtImage()).isEqualTo(FIXTURE_AMT_IMAGE);
        }

        @Test
        @DisplayName("a descriptor from another copybook is rejected instead of reading the wrong offset")
        void foreignDescriptorIsRejected() {
            TrnxRecord record = populated(ASCII);
            FieldSpan foreign = FieldSpan.alphanumeric("ACCT-EXPIRAION-DATE", 0, 10);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> record.readRawImage(foreign))
                    .withMessageContaining("COSTM01");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> record.readRawBytes(foreign))
                    .withMessageContaining("COSTM01");
        }

        @Test
        @DisplayName("a null descriptor is rejected")
        void nullDescriptorIsRejected() {
            TrnxRecord record = populated(ASCII);

            assertThatNullPointerException().isThrownBy(() -> record.readRawImage(null));
            assertThatNullPointerException().isThrownBy(() -> record.readRawBytes(null));
        }

        @Test
        @DisplayName("the raw amount image is the stored zoned form, with no decimal point and the sign still overpunched")
        void rawAmountImageIsTheStoredZonedForm() {
            TrnxRecord record = populated(ASCII);

            assertThat(record.readTrnxAmtImage())
                    .isEqualTo(FIXTURE_AMT_IMAGE)
                    .doesNotContain(".")
                    .isEqualTo(record.readRawImage(TrnxRecord.TRNX_AMT));
        }

        @Test
        @DisplayName("toString names the key, the raw amount and the code page")
        void toStringNamesTheKeyAmountAndCodePage() {
            TrnxRecord record = populated(ASCII);

            assertThat(record.toString())
                    .startsWith("TrnxRecord[")
                    .as("the statement files carry a PAN; it must not reach a log line (CWE-532)")
                    .doesNotContain(FIXTURE_CARD_NUM)
                    .contains("*".repeat(FIXTURE_CARD_NUM.length() - 4)
                            + FIXTURE_CARD_NUM.substring(FIXTURE_CARD_NUM.length() - 4))
                    .contains(FIXTURE_TRAN_ID)
                    .doesNotContain(FIXTURE_AMT_IMAGE)
                    .contains("<omitted>:" + TrnxRecord.TRNX_AMT_LENGTH)
                    .contains("<omitted>:11")
                    .contains("US-ASCII")
                    .endsWith("]");
        }

        @Test
        @DisplayName("toString never throws, even on a record whose numeric spans will not decode")
        void toStringNeverThrowsOnUndecodableSpans() {
            TrnxRecord blank = TrnxRecord.decode(" ".repeat(350).getBytes(ASCII), ASCII);

            assertThat(blank.toString()).contains("TrnxRecord[").contains("US-ASCII");
        }

        @Test
        @DisplayName("instances are independent, so writing one cannot alter another")
        void instancesAreIndependent() {
            byte[] shared = populated(ASCII).encode();

            TrnxRecord first = TrnxRecord.wrap(shared, ASCII);
            TrnxRecord second = TrnxRecord.wrap(shared, ASCII);

            first.writeTrnxAmt(new BigDecimal("1.00"));

            assertThat(second.readTrnxAmt())
                    .as("no mutable static state and no shared array")
                    .isEqualByComparingTo("504.77");
            assertThat(first.readTrnxAmt()).isEqualByComparingTo("1.00");
        }
    }

    @Nested
    @DisplayName("Structural guards - gates G22, G44 and G53 asserted about the type itself")
    class StructuralGuards {
        @Test
        @DisplayName("G22: no double, float, Double or Float in any field, accessor, parameter or constructor")
        void noBinaryFloatingPointAnywhere() {
            List<Class<?>> forbidden = List.of(double.class, float.class, Double.class, Float.class);

            for (Field field : TrnxRecord.class.getDeclaredFields()) {
                assertThat(field.getType())
                        .as("declared field %s", field.getName())
                        .isNotIn(forbidden);
            }
            for (Method method : TrnxRecord.class.getDeclaredMethods()) {
                assertThat(method.getReturnType())
                        .as("return type of %s", method.getName())
                        .isNotIn(forbidden);
                assertThat(method.getParameterTypes())
                        .as("parameter types of %s", method.getName())
                        .doesNotContainAnyElementsOf(forbidden);
            }
            for (Constructor<?> constructor : TrnxRecord.class.getDeclaredConstructors()) {
                assertThat(constructor.getParameterTypes())
                        .as("constructor parameter types")
                        .doesNotContainAnyElementsOf(forbidden);
            }
        }

        @Test
        @DisplayName("the amount is a BigDecimal and the two PIC 9 counters are int, sized by their digit counts")
        void theNumericSurfaceIsExactlyAsThePicturesRequire() throws NoSuchMethodException {
            assertThat(TrnxRecord.class.getMethod("readTrnxAmt").getReturnType())
                    .as("PIC S9(09)V99 is exact decimal, so BigDecimal and nothing else")
                    .isEqualTo(BigDecimal.class);
            assertThat(TrnxRecord.class.getMethod("writeTrnxAmt", BigDecimal.class).getParameterTypes())
                    .as("the store side takes the same exact type")
                    .containsExactly(BigDecimal.class);
            assertThat(TrnxRecord.class.getMethod("readTrnxCatCd").getReturnType())
                    .as("PIC 9(04) is four scale-free digits, which an int holds with room to spare")
                    .isEqualTo(int.class);
            assertThat(TrnxRecord.class.getMethod("readTrnxMerchantId").getReturnType())
                    .as("PIC 9(09) is nine scale-free digits, still inside the int range")
                    .isEqualTo(int.class);
            assertThat(TrnxRecord.class.getMethod("readTrnxAmtImage").getReturnType())
                    .as("the raw zoned image is text, sign overpunch and all")
                    .isEqualTo(String.class);
        }

        @Test
        @DisplayName("G44: no persistence artefact - no entity or table mapping, no generated id, no version column")
        void noPersistenceArtefacts() {
            assertThat(TrnxRecord.class.getAnnotations())
                    .as("a record model carries no annotation at all: no ORM mapping and no Spring "
                            + "stereotype. The dataset is reached by JDBC over the existing VSAM layout, "
                            + "so there is no entity and no table to map to")
                    .isEmpty();
            assertNoPersistenceAnnotation(TrnxRecord.class.getAnnotations(), "TrnxRecord");

            for (Field field : TrnxRecord.class.getDeclaredFields()) {
                assertNoPersistenceAnnotation(field.getAnnotations(), "field " + field.getName());
                assertThat(field.getName().toLowerCase(Locale.ROOT))
                        .as("field %s must not be an optimistic-lock discriminator: COACTUPC and "
                                + "COCRDUPC do the concurrency check by re-reading and comparing the "
                                + "record, and a version column would be the schema change this "
                                + "migration forbids", field.getName())
                        .doesNotContain("version")
                        .doesNotContain("optimistic")
                        .doesNotContain("optlock");
            }
            for (Method method : TrnxRecord.class.getDeclaredMethods()) {
                assertNoPersistenceAnnotation(method.getAnnotations(), "method " + method.getName());
            }
            for (Constructor<?> constructor : TrnxRecord.class.getDeclaredConstructors()) {
                assertNoPersistenceAnnotation(constructor.getAnnotations(), "a constructor");
            }
        }

        @Test
        @DisplayName("G53: every static member is final and immutable, and no static array is exposed")
        void noMutableStaticStateExists() {
            List<String> mutableStatics = new ArrayList<>();
            List<String> staticArrays = new ArrayList<>();
            for (Field field : TrnxRecord.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                if (!Modifier.isFinal(field.getModifiers())) {
                    mutableStatics.add(field.getName());
                }
                if (field.getType().isArray()) {
                    staticArrays.add(field.getName());
                }
            }

            assertThat(mutableStatics)
                    .as("COBOL WORKING-STORAGE must never become a mutable static field: it would break "
                            + "request isolation and make the suite order-dependent")
                    .isEmpty();
            assertThat(staticArrays)
                    .as("a static final array is still mutable through its elements, so the geometry is "
                            + "published as ints and as an immutable descriptor list instead")
                    .isEmpty();

            for (Field field : TrnxRecord.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("instance field %s must be final: the record area and its codec are "
                                    + "established once, at construction", field.getName())
                            .isTrue();
                }
            }

            assertThat(Modifier.isFinal(TrnxRecord.class.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("this test class itself holds no mutable static state either")
        void theTestClassIsEquallyFreeOfMutableStatics() {
            for (Field field : TrnxRecordTest.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("shared expectation %s must be a constant", field.getName())
                            .isTrue();
                    assertThat(field.getType().isArray())
                            .as("%s must not be a shared array: every byte[] is built inside the method "
                                    + "that uses it, so one test cannot perturb another", field.getName())
                            .isFalse();
                }
            }
        }
    }

    private static void assertNoPersistenceAnnotation(Annotation[] annotations, String subject) {
        List<String> forbidden = List.of("Entity", "Table", "Id", "Column", "GeneratedValue",
                "Version", "Embeddable", "MappedSuperclass", "JoinColumn", "SequenceGenerator");
        for (Annotation annotation : annotations) {
            assertThat(annotation.annotationType().getSimpleName())
                    .as("annotation on %s", subject)
                    .isNotIn(forbidden);
        }
    }
}
