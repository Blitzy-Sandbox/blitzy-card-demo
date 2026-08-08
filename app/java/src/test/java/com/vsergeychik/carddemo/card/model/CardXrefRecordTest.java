package com.vsergeychik.carddemo.card.model;

import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link CardXrefRecord}, the Java type for {@code app/cpy/CVACT03Y.cpy}.
 *
 * <p>Plain JUnit 5 with no Spring context: the class under test is a value type with no collaborators
 * beyond the two fixed-width classes, so every decision in it is reachable directly. Both charsets are
 * named explicitly throughout - {@code US-ASCII} for the text fixtures and {@code IBM037} for the
 * EBCDIC datasets - and the platform default is never relied upon anywhere.
 *
 * <h2>The expected values are the copybook's and the fixture's, not the implementation's</h2>
 * Every offset, length and total asserted below is transcribed by hand from
 * {@code app/cpy/CVACT03Y.cpy}:
 * <pre>
 *    01 CARD-XREF-RECORD.
 *        05  XREF-CARD-NUM                     PIC X(16).
 *        05  XREF-CUST-ID                      PIC 9(09).
 *        05  XREF-ACCT-ID                      PIC 9(11).
 *        05  FILLER                            PIC X(14).
 * </pre>
 * and the decoded field values are read from the real fixture row rather than restated from the class
 * under test, so these assertions are an independent check on the transcription rather than a
 * tautology.
 *
 * <h2>Acceptance gates enforced here directly</h2>
 * <ul>
 *   <li><strong>G19</strong> - a serialised record is byte-identical in width to its copybook
 *       declaration: 50 bytes for the cross-reference record.</li>
 *   <li><strong>G21</strong> - the {@code FILLER} span is present and space-filled in every
 *       serialised record.</li>
 *   <li><strong>G16</strong> / risk <strong>R-F</strong> - the 36-byte fixture row is widened by the
 *       shared normaliser before comparison, and a short row is rejected by the model type itself.</li>
 *   <li><strong>G8</strong> - one Java type per copybook, so the field geometry asserted here is the
 *       only definition of it in the module.</li>
 * </ul>
 */
@DisplayName("CardXrefRecord - CVACT03Y card cross-reference record, 50 bytes")
class CardXrefRecordTest {

    /** The code page of the text fixtures under {@code app/data/ASCII}, named explicitly. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** The code page of the EBCDIC datasets under {@code app/data/EBCDIC}, named explicitly. */
    private static final Charset EBCDIC = Charset.forName("IBM037");

    /**
     * The first row of {@code app/data/ASCII/cardxref.txt}, exactly as the fixture holds it - 36
     * bytes, because the fixture omits the trailing {@code FILLER X(14)}.
     */
    private static final String FIXTURE_ROW_1 = "050002445376574000000005000000000050";

    /** The three field values the first fixture row encodes, read off the row by hand. */
    private static final String ROW_1_CARD_NUM = "0500024453765740";
    private static final int ROW_1_CUST_ID = 50;
    private static final long ROW_1_ACCT_ID = 50L;

    private final FixedWidthCodec asciiCodec = new FixedWidthCodec(ASCII);
    private final FixedWidthCodec ebcdicCodec = new FixedWidthCodec(EBCDIC);

    /** A representative record used wherever the particular values do not matter. */
    private static CardXrefRecord sample() {
        return new CardXrefRecord(ROW_1_CARD_NUM, ROW_1_CUST_ID, ROW_1_ACCT_ID);
    }

    /**
     * Reads every row of the fixture from the test classpath, as text. The rows are returned exactly as
     * stored, un-widened, so a test that needs the declared width has to normalise deliberately.
     */
    private static List<String> fixtureRows() {
        List<String> rows = new ArrayList<>();
        try (InputStream stream =
                     CardXrefRecordTest.class.getClassLoader().getResourceAsStream("fixtures/cardxref.txt")) {
            assertThat(stream).as("fixtures/cardxref.txt must be on the test classpath").isNotNull();
            String all = new String(stream.readAllBytes(), ASCII);
            for (String row : all.split("\n")) {
                String trimmedOfLineEnd = row.endsWith("\r") ? row.substring(0, row.length() - 1) : row;
                if (!trimmedOfLineEnd.isEmpty()) {
                    rows.add(trimmedOfLineEnd);
                }
            }
        } catch (IOException problem) {
            throw new UncheckedIOException("Could not read fixtures/cardxref.txt", problem);
        }
        return rows;
    }

    @Nested
    @DisplayName("Declared geometry - the copybook's own arithmetic (G19)")
    class DeclaredGeometry {

        @Test
        @DisplayName("RECLN 50: the four spans sum to exactly the declared record length")
        void spansSumToRecordLength() {
            int sum = CardXrefRecord.LAYOUT.storageSpans().stream()
                    .mapToInt(FieldSpan::length)
                    .sum();

            assertThat(CardXrefRecord.RECORD_LENGTH).isEqualTo(50);
            assertThat(CardXrefRecord.LAYOUT.recordLength()).isEqualTo(50);
            assertThat(sum).isEqualTo(CardXrefRecord.RECORD_LENGTH);
            // 16 + 9 + 11 + 14, spelled out so a reader can check it against the copybook by eye.
            assertThat(sum).isEqualTo(16 + 9 + 11 + 14);
        }

        @Test
        @DisplayName("Four storage spans, in copybook order, and no REDEFINES overlay")
        void declaresFourStorageSpansAndNoOverlay() {
            assertThat(CardXrefRecord.LAYOUT.storageSpans())
                    .containsExactly(CardXrefRecord.XREF_CARD_NUM,
                            CardXrefRecord.XREF_CUST_ID,
                            CardXrefRecord.XREF_ACCT_ID,
                            CardXrefRecord.FILLER);
            // CVACT03Y declares no REDEFINES, so none may be invented here.
            assertThat(CardXrefRecord.LAYOUT.redefinitions()).isEmpty();
        }

        @ParameterizedTest(name = "{0} at [{1}, {2}) as {3}")
        @CsvSource({
                "XREF-CARD-NUM,  0, 16, ALPHANUMERIC",
                "XREF-CUST-ID,  16, 25, UNSIGNED_NUMERIC",
                "XREF-ACCT-ID,  25, 36, UNSIGNED_NUMERIC"
        })
        @DisplayName("Every referable span sits at its copybook offset with its copybook picture")
        void referableSpansMatchTheCopybook(String name, int offset, int endExclusive, PictureKind kind) {
            FieldSpan span = CardXrefRecord.LAYOUT.span(name);

            assertThat(span.offset()).isEqualTo(offset);
            assertThat(span.endOffsetExclusive()).isEqualTo(endExclusive);
            assertThat(span.length()).isEqualTo(endExclusive - offset);
            assertThat(span.kind()).isEqualTo(kind);
            assertThat(span.hasInitialValue()).isFalse();
            assertThat(span.redefinition()).isFalse();
        }

        @Test
        @DisplayName("The trailing FILLER is a first-class span at [36, 50) carrying no VALUE")
        void fillerIsAFirstClassSpan() {
            assertThat(CardXrefRecord.FILLER_OFFSET).isEqualTo(36);
            assertThat(CardXrefRecord.FILLER_LENGTH).isEqualTo(14);
            assertThat(CardXrefRecord.FILLER.kind()).isEqualTo(PictureKind.FILLER);
            assertThat(CardXrefRecord.FILLER.kind().filler()).isTrue();
            assertThat(CardXrefRecord.FILLER.offset()).isEqualTo(CardXrefRecord.FILLER_OFFSET);
            assertThat(CardXrefRecord.FILLER.endOffsetExclusive())
                    .isEqualTo(CardXrefRecord.RECORD_LENGTH);
            // No VALUE in the copybook, so the FILLER's content is the charset's space byte.
            assertThat(CardXrefRecord.FILLER.hasInitialValue()).isFalse();
            // FILLER is not a referable COBOL name.
            assertThat(CardXrefRecord.LAYOUT.hasSpan("FILLER")).isFalse();
        }

        @Test
        @DisplayName("Field names are carried verbatim from the copybook")
        void fieldNamesAreVerbatim() {
            assertThat(CardXrefRecord.XREF_CARD_NUM_NAME).isEqualTo("XREF-CARD-NUM");
            assertThat(CardXrefRecord.XREF_CUST_ID_NAME).isEqualTo("XREF-CUST-ID");
            assertThat(CardXrefRecord.XREF_ACCT_ID_NAME).isEqualTo("XREF-ACCT-ID");
            assertThat(CardXrefRecord.XREF_CARD_NUM.name()).isEqualTo(CardXrefRecord.XREF_CARD_NUM_NAME);
            assertThat(CardXrefRecord.XREF_CUST_ID.name()).isEqualTo(CardXrefRecord.XREF_CUST_ID_NAME);
            assertThat(CardXrefRecord.XREF_ACCT_ID.name()).isEqualTo(CardXrefRecord.XREF_ACCT_ID_NAME);
        }

        @Test
        @DisplayName("The picture bounds are derived from the declared digit counts")
        void pictureBoundsFollowTheDigitCounts() {
            assertThat(CardXrefRecord.XREF_CUST_ID_MAX_VALUE).isEqualTo(999_999_999);
            assertThat(CardXrefRecord.XREF_ACCT_ID_MAX_VALUE).isEqualTo(99_999_999_999L);
            // PIC 9(11) genuinely exceeds the int range, which is why the account id is a long.
            assertThat(CardXrefRecord.XREF_ACCT_ID_MAX_VALUE).isGreaterThan(Integer.MAX_VALUE);
        }
    }

    @Nested
    @DisplayName("The two keys sit at two different offsets - the CVACT02Y confusion guard")
    class KeyOffsets {

        @Test
        @DisplayName("The base CCXREF key is XREF-CARD-NUM, the 16 bytes at [0, 16)")
        void baseKeyIsTheCardNumberAtOffsetZero() {
            byte[] image = sample().encode(ASCII);

            assertThat(CardXrefRecord.XREF_CARD_NUM_OFFSET).isZero();
            assertThat(CardXrefRecord.XREF_CARD_NUM_LENGTH).isEqualTo(16);
            assertThat(new String(image, 0, 16, ASCII)).isEqualTo(ROW_1_CARD_NUM);
            assertThat(sample().cardNumberKey()).isEqualTo(ROW_1_CARD_NUM);
            assertThat(sample().cardNumberKey()).isEqualTo(sample().xrefCardNum());
        }

        @Test
        @DisplayName("The CXACAIX alternate-index key is XREF-ACCT-ID, the 11 bytes at [25, 36) - not [16, 27)")
        void alternateIndexKeyIsTheAccountIdAtOffsetTwentyFive() {
            // A record whose three fields are all distinguishable, so a wrong offset cannot pass.
            CardXrefRecord record = new CardXrefRecord("1111222233334444", 555_555_555, 77_777_777_777L);
            byte[] image = record.encode(ASCII);

            assertThat(CardXrefRecord.XREF_ACCT_ID_OFFSET).isEqualTo(25);
            assertThat(CardXrefRecord.XREF_ACCT_ID_LENGTH).isEqualTo(11);
            assertThat(new String(image, 25, 11, ASCII)).isEqualTo("77777777777");
            assertThat(record.accountIdAlternateIndexKey()).isEqualTo(77_777_777_777L);
            assertThat(record.accountIdAlternateIndexKey()).isEqualTo(record.xrefAcctId());

            // Offset 16 is where CVACT02Y's CardRecord keeps ITS account id. Here it is the customer
            // id, and reading the account id from there would silently return the wrong field.
            assertThat(new String(image, 16, 9, ASCII)).isEqualTo("555555555");
            assertThat(new String(image, 16, 11, ASCII)).isNotEqualTo("77777777777");
        }

        @Test
        @DisplayName("The two keys are different fields and are not interchangeable")
        void theTwoKeysAreDistinct() {
            CardXrefRecord record = new CardXrefRecord("0000000000000050", 50, 50L);

            // Both denote 50, yet the base key is a 16-character image and the alternate key a number:
            // the picture, not the value, is what makes them different kinds of key.
            assertThat(record.cardNumberKey()).isEqualTo("0000000000000050").hasSize(16);
            assertThat(record.accountIdAlternateIndexKey()).isEqualTo(50L);
        }
    }

    @Nested
    @DisplayName("Encoding - always the full 50-byte image, FILLER included (G19, G21)")
    class Encoding {

        @Test
        @DisplayName("A serialised record is exactly 50 bytes under both code pages")
        void serialisedWidthIsFifty() {
            assertThat(sample().encode(ASCII)).hasSize(CardXrefRecord.RECORD_LENGTH);
            assertThat(sample().encode(EBCDIC)).hasSize(CardXrefRecord.RECORD_LENGTH);
            assertThat(sample().encode(asciiCodec)).hasSize(CardXrefRecord.RECORD_LENGTH);
            assertThat(sample().toFixedWidthRecord(ebcdicCodec).recordLength())
                    .isEqualTo(CardXrefRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("Bytes [36, 50) are fourteen spaces - the FILLER is emitted, never dropped")
        void fillerIsEmittedAsFourteenSpaces() {
            byte[] image = sample().encode(ASCII);

            String filler = new String(image, CardXrefRecord.FILLER_OFFSET,
                    CardXrefRecord.FILLER_LENGTH, ASCII);
            assertThat(filler).isEqualTo("              ").hasSize(14);
            assertThat(filler.chars()).allMatch(character -> character == ' ');
        }

        @Test
        @DisplayName("The FILLER is space-filled in the EBCDIC code page too - 0x40, not 0x20")
        void fillerUsesTheCodePagesOwnSpaceByte() {
            byte[] ascii = sample().encode(ASCII);
            byte[] ebcdic = sample().encode(EBCDIC);

            for (int offset = CardXrefRecord.FILLER_OFFSET;
                 offset < CardXrefRecord.RECORD_LENGTH;
                 offset++) {
                assertThat(ascii[offset]).as("ASCII space at %d", offset).isEqualTo((byte) 0x20);
                assertThat(ebcdic[offset]).as("EBCDIC space at %d", offset).isEqualTo((byte) 0x40);
            }
        }

        @Test
        @DisplayName("PIC 9 spans are zero-filled on the left: account 50 is written 00000000050")
        void numericSpansAreLeftZeroFilled() {
            byte[] image = new CardXrefRecord(ROW_1_CARD_NUM, 50, 50L).encode(ASCII);

            assertThat(new String(image, 16, 9, ASCII)).isEqualTo("000000050");
            assertThat(new String(image, 25, 11, ASCII)).isEqualTo("00000000050");
        }

        @Test
        @DisplayName("A PIC X span shorter than its picture is space-padded on the right")
        void shortAlphanumericIsRightPadded() {
            byte[] image = new CardXrefRecord("ABC", 1, 2L).encode(ASCII);

            assertThat(new String(image, 0, 16, ASCII)).isEqualTo("ABC             ");
            assertThat(image).hasSize(CardXrefRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("An empty PIC X span denotes SPACES and still occupies its full 16 bytes")
        void emptyAlphanumericIsAllSpaces() {
            byte[] image = new CardXrefRecord("", 0, 0L).encode(ASCII);

            assertThat(new String(image, ASCII))
                    .isEqualTo(" ".repeat(16) + "000000000" + "00000000000" + " ".repeat(14));
            assertThat(image).hasSize(CardXrefRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("The complete image of the first fixture row, byte for byte")
        void completeImageOfTheFirstFixtureRow() {
            String image = new String(sample().encode(ASCII), ASCII);

            assertThat(image).isEqualTo(FIXTURE_ROW_1 + " ".repeat(14));
            assertThat(image).hasSize(CardXrefRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("toFixedWidthRecord hands back a span addressable by the layout's descriptors")
        void toFixedWidthRecordIsAddressableBySpan() {
            FixedWidthRecord record = sample().toFixedWidthRecord(asciiCodec);

            assertThat(record.charset()).isEqualTo(ASCII);
            assertThat(record.readSpan(CardXrefRecord.XREF_CARD_NUM)).isEqualTo(ROW_1_CARD_NUM);
            assertThat(record.readSpan(CardXrefRecord.XREF_CUST_ID)).isEqualTo("000000050");
            assertThat(record.readSpan(CardXrefRecord.XREF_ACCT_ID)).isEqualTo("00000000050");
            assertThat(record.readSpan(CardXrefRecord.FILLER)).isEqualTo(" ".repeat(14));
        }
    }

    @Nested
    @DisplayName("Decoding - untrimmed PIC X, digits-only PIC 9, declared width only")
    class Decoding {

        @Test
        @DisplayName("A synthetic 50-byte record decodes to its three named fields")
        void decodesASyntheticRecord() {
            byte[] stored = (FIXTURE_ROW_1 + " ".repeat(14)).getBytes(ASCII);

            CardXrefRecord decoded = CardXrefRecord.decode(stored, ASCII);

            assertThat(decoded.xrefCardNum()).isEqualTo(ROW_1_CARD_NUM);
            assertThat(decoded.xrefCustId()).isEqualTo(ROW_1_CUST_ID);
            assertThat(decoded.xrefAcctId()).isEqualTo(ROW_1_ACCT_ID);
        }

        @Test
        @DisplayName("PIC X decode does not trim: trailing padding is part of the field's value")
        void alphanumericDecodeDoesNotTrim() {
            byte[] stored = ("ABC" + " ".repeat(13) + "000000001" + "00000000002" + " ".repeat(14))
                    .getBytes(ASCII);

            CardXrefRecord decoded = CardXrefRecord.decode(stored, ASCII);

            assertThat(decoded.xrefCardNum()).isEqualTo("ABC             ").hasSize(16);
            assertThat(decoded.cardNumberKey()).hasSize(CardXrefRecord.XREF_CARD_NUM_LENGTH);
        }

        @Test
        @DisplayName("The codec-taking overloads decode identically to the charset-taking ones")
        void codecAndCharsetOverloadsAgree() {
            byte[] stored = (FIXTURE_ROW_1 + " ".repeat(14)).getBytes(ASCII);
            FixedWidthRecord wrapped = asciiCodec.wrap(stored, CardXrefRecord.LAYOUT);

            CardXrefRecord viaCharset = CardXrefRecord.decode(stored, ASCII);
            CardXrefRecord viaCodec = CardXrefRecord.decode(stored, asciiCodec);
            CardXrefRecord viaSpan = CardXrefRecord.decodeSpan(wrapped, asciiCodec);

            assertThat(viaCodec).isEqualTo(viaCharset);
            assertThat(viaSpan).isEqualTo(viaCharset);
        }

        @Test
        @DisplayName("An EBCDIC-encoded record decodes under IBM037")
        void decodesEbcdic() {
            byte[] stored = sample().encode(EBCDIC);

            assertThat(CardXrefRecord.decode(stored, EBCDIC)).isEqualTo(sample());
            assertThat(CardXrefRecord.decode(stored, ebcdicCodec)).isEqualTo(sample());
        }

        @ParameterizedTest(name = "a row of {0} byte(s) is rejected")
        @ValueSource(ints = {36, 35, 49, 51, 1})
        @DisplayName("Any width other than 50 is rejected, the 36-byte fixture row included (R-F)")
        void rejectsAnyWidthOtherThanFifty(int width) {
            byte[] wrongWidth = new byte[width];
            java.util.Arrays.fill(wrongWidth, (byte) '0');

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CardXrefRecord.decode(wrongWidth, ASCII));
        }

        @Test
        @DisplayName("A 36-byte span rejected by the model names the shared normaliser in its message")
        void rejectingAShortSpanPointsAtTheNormaliser() {
            FixedWidthRecord tooNarrow = new FixedWidthRecord(CardXrefRecord.FILLER_OFFSET, ASCII);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CardXrefRecord.decodeSpan(tooNarrow, asciiCodec))
                    .withMessageContaining("padToDeclaredWidth")
                    .withMessageContaining("50");
        }

        @Test
        @DisplayName("A non-digit in a PIC 9 span fails loudly rather than decoding as zero")
        void rejectsNonDigitsInNumericSpans() {
            byte[] custIdCorrupt =
                    (ROW_1_CARD_NUM + "0000X0050" + "00000000050" + " ".repeat(14)).getBytes(ASCII);
            byte[] acctIdCorrupt =
                    (ROW_1_CARD_NUM + "000000050" + "000000000Z0" + " ".repeat(14)).getBytes(ASCII);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CardXrefRecord.decode(custIdCorrupt, ASCII));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CardXrefRecord.decode(acctIdCorrupt, ASCII));
        }

        @Test
        @DisplayName("Nothing may be decoded from, or into, an unstated code page")
        void nullArgumentsAreRejected() {
            byte[] stored = sample().encode(ASCII);
            FixedWidthRecord wrapped = asciiCodec.wrap(stored, CardXrefRecord.LAYOUT);

            // The casts on the second argument pick between the Charset and the codec overload for a
            // literal null; the first argument needs none, because decodeSpan is named rather than
            // overloaded on byte[].
            assertThatNullPointerException().isThrownBy(() -> CardXrefRecord.decode(stored, (Charset) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> CardXrefRecord.decode(stored, (FixedWidthCodec) null));
            assertThatNullPointerException().isThrownBy(() -> CardXrefRecord.decode(null, asciiCodec));
            assertThatNullPointerException()
                    .isThrownBy(() -> CardXrefRecord.decodeSpan(null, asciiCodec));
            assertThatNullPointerException().isThrownBy(() -> CardXrefRecord.decodeSpan(wrapped, null));
            assertThatNullPointerException().isThrownBy(() -> sample().encode((Charset) null));
            assertThatNullPointerException().isThrownBy(() -> sample().encode((FixedWidthCodec) null));
            assertThatNullPointerException().isThrownBy(() -> sample().toFixedWidthRecord(null));
        }
    }

    @Nested
    @DisplayName("Round trip - decode then encode must be byte-identical")
    class RoundTrip {

        @Test
        @DisplayName("A synthetic record survives decode then encode unchanged")
        void syntheticRecordRoundTrips() {
            byte[] stored = (FIXTURE_ROW_1 + " ".repeat(14)).getBytes(ASCII);

            byte[] reEncoded = CardXrefRecord.decode(stored, ASCII).encode(ASCII);

            assertThat(reEncoded).isEqualTo(stored);
        }

        @Test
        @DisplayName("The leading zero of XREF-CARD-NUM survives a decode-encode round trip")
        void leadingZeroSurvivesTheRoundTrip() {
            byte[] stored = (FIXTURE_ROW_1 + " ".repeat(14)).getBytes(ASCII);

            CardXrefRecord decoded = CardXrefRecord.decode(stored, ASCII);

            assertThat(decoded.xrefCardNum()).startsWith("0").isEqualTo(ROW_1_CARD_NUM);
            String reDecoded = CardXrefRecord.decode(decoded.encode(ASCII), ASCII).xrefCardNum();
            assertThat(reDecoded).startsWith("0").isEqualTo(ROW_1_CARD_NUM);
            // The proof this guards: a numeric round trip would have lost the leading zero.
            assertThat(Long.toString(Long.parseLong(ROW_1_CARD_NUM))).doesNotStartWith("0");
        }

        @Test
        @DisplayName("Every record round trips through IBM037 as well as US-ASCII")
        void roundTripsUnderBothCodePages() {
            CardXrefRecord record = new CardXrefRecord("0500024453765740", 999_999_999, 99_999_999_999L);

            assertThat(CardXrefRecord.decode(record.encode(EBCDIC), EBCDIC)).isEqualTo(record);
            assertThat(CardXrefRecord.decode(record.encode(ASCII), ASCII)).isEqualTo(record);
            // The two code pages really do produce different bytes for the same record.
            assertThat(record.encode(EBCDIC)).isNotEqualTo(record.encode(ASCII));
        }
    }

    @Nested
    @DisplayName("The real fixture - app/data/ASCII/cardxref.txt (G16, risk R-F)")
    class RealFixture {

        @Test
        @DisplayName("The fixture is 50 rows of 36 bytes, confirming the omitted trailing FILLER")
        void fixtureIsThirtySixBytesWide() {
            List<String> rows = fixtureRows();

            assertThat(rows).hasSize(50);
            assertThat(rows).allSatisfy(row ->
                    assertThat(row).hasSize(CardXrefRecord.FILLER_OFFSET));
            assertThat(rows.get(0)).isEqualTo(FIXTURE_ROW_1);
            // 36 is exactly the declared width less the trailing FILLER X(14).
            assertThat(CardXrefRecord.RECORD_LENGTH - CardXrefRecord.FILLER_LENGTH)
                    .isEqualTo(CardXrefRecord.FILLER_OFFSET);
        }

        @Test
        @DisplayName("Row 1, after the shared 36-to-50 right pad, decodes to 0500024453765740 / 50 / 50")
        void firstFixtureRowDecodesAsExpected() {
            byte[] raw = fixtureRows().get(0).getBytes(ASCII);
            assertThat(raw).hasSize(36);

            // The normaliser lives on the codec, not on the model type: one home for the rule.
            byte[] widened = asciiCodec.padToDeclaredWidth(raw, CardXrefRecord.RECORD_LENGTH);
            CardXrefRecord decoded = CardXrefRecord.decode(widened, asciiCodec);

            assertThat(widened).hasSize(CardXrefRecord.RECORD_LENGTH);
            assertThat(decoded.xrefCardNum()).isEqualTo("0500024453765740");
            assertThat(decoded.xrefCustId()).isEqualTo(50);
            assertThat(decoded.xrefAcctId()).isEqualTo(50L);
            // And the widened row is exactly what the model re-emits.
            assertThat(decoded.encode(asciiCodec)).isEqualTo(widened);
        }

        @Test
        @DisplayName("All 50 fixture rows widen, decode and re-encode byte-identically")
        void everyFixtureRowRoundTrips() {
            for (String row : fixtureRows()) {
                byte[] widened =
                        asciiCodec.padToDeclaredWidth(row.getBytes(ASCII), CardXrefRecord.RECORD_LENGTH);

                CardXrefRecord decoded = CardXrefRecord.decode(widened, asciiCodec);

                assertThat(decoded.encode(asciiCodec)).as("round trip of row %s", row)
                        .isEqualTo(widened);
                assertThat(decoded.cardNumberKey()).as("base key of row %s", row)
                        .isEqualTo(row.substring(0, 16));
                assertThat(decoded.accountIdAlternateIndexKey()).as("alternate key of row %s", row)
                        .isEqualTo(Long.parseLong(row.substring(25, 36)));
                assertThat(decoded.xrefCustId()).as("customer id of row %s", row)
                        .isEqualTo(Integer.parseInt(row.substring(16, 25)));
            }
        }
    }

    @Nested
    @DisplayName("Construction - every PICTURE bound enforced at the boundary")
    class Construction {

        @Test
        @DisplayName("The three fields are held exactly as supplied")
        void holdsTheSuppliedFields() {
            CardXrefRecord record = new CardXrefRecord("0500024453765740", 50, 50L);

            assertThat(record.xrefCardNum()).isEqualTo("0500024453765740");
            assertThat(record.xrefCustId()).isEqualTo(50);
            assertThat(record.xrefAcctId()).isEqualTo(50L);
        }

        @Test
        @DisplayName("A card number of exactly 16 characters is accepted; 17 is rejected")
        void enforcesTheAlphanumericWidth() {
            assertThat(new CardXrefRecord("1234567890123456", 0, 0L).xrefCardNum()).hasSize(16);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new CardXrefRecord("12345678901234567", 0, 0L))
                    .withMessageContaining("PIC X(16)")
                    .withMessageContaining("movePicX");
        }

        @Test
        @DisplayName("A null card number is rejected: PIC X(16) has no null, only SPACES")
        void rejectsANullCardNumber() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new CardXrefRecord(null, 0, 0L))
                    .withMessageContaining("XREF-CARD-NUM");
        }

        @ParameterizedTest(name = "customer id {0} is rejected")
        @ValueSource(ints = {-1, Integer.MIN_VALUE, 1_000_000_000, Integer.MAX_VALUE})
        @DisplayName("A customer id outside PIC 9(09) is rejected, negatives included")
        void rejectsAnUnstorableCustomerId(int custId) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new CardXrefRecord(ROW_1_CARD_NUM, custId, 0L))
                    .withMessageContaining("XREF-CUST-ID");
        }

        @ParameterizedTest(name = "account id {0} is rejected")
        @ValueSource(longs = {-1L, Long.MIN_VALUE, 100_000_000_000L, Long.MAX_VALUE})
        @DisplayName("An account id outside PIC 9(11) is rejected, negatives included")
        void rejectsAnUnstorableAccountId(long acctId) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new CardXrefRecord(ROW_1_CARD_NUM, 0, acctId))
                    .withMessageContaining("XREF-ACCT-ID");
        }

        @Test
        @DisplayName("The largest storable value of each picture is accepted")
        void acceptsTheLargestStorableValues() {
            CardXrefRecord widest = new CardXrefRecord("9999999999999999",
                    CardXrefRecord.XREF_CUST_ID_MAX_VALUE,
                    CardXrefRecord.XREF_ACCT_ID_MAX_VALUE);

            byte[] image = widest.encode(ASCII);
            assertThat(image).hasSize(CardXrefRecord.RECORD_LENGTH);
            assertThat(new String(image, 16, 9, ASCII)).isEqualTo("999999999");
            assertThat(new String(image, 25, 11, ASCII)).isEqualTo("99999999999");
        }

        @Test
        @DisplayName("Zero is storable in both unsigned pictures")
        void acceptsZeroInBothNumericFields() {
            CardXrefRecord zeroes = new CardXrefRecord(ROW_1_CARD_NUM, 0, 0L);

            assertThat(zeroes.xrefCustId()).isZero();
            assertThat(zeroes.xrefAcctId()).isZero();
            assertThat(new String(zeroes.encode(ASCII), 16, 20, ASCII))
                    .isEqualTo("000000000" + "00000000000");
        }
    }

    @Nested
    @DisplayName("Value semantics - what the parity differ and 9300-CHECK-CHANGE-IN-REC rely on")
    class ValueSemantics {

        @Test
        @DisplayName("Equal fields mean equal records, and equal hash codes")
        void equalFieldsMeanEqualRecords() {
            CardXrefRecord one = new CardXrefRecord(ROW_1_CARD_NUM, 50, 50L);
            CardXrefRecord other = new CardXrefRecord(ROW_1_CARD_NUM, 50, 50L);

            assertThat(one).isEqualTo(other).hasSameHashCodeAs(other);
            assertThat(one).isEqualTo(one);
        }

        @Test
        @DisplayName("A difference in any single field breaks equality")
        void anyFieldDifferenceBreaksEquality() {
            CardXrefRecord base = new CardXrefRecord(ROW_1_CARD_NUM, 50, 50L);

            assertThat(base).isNotEqualTo(new CardXrefRecord("0500024453765741", 50, 50L));
            assertThat(base).isNotEqualTo(new CardXrefRecord(ROW_1_CARD_NUM, 51, 50L));
            assertThat(base).isNotEqualTo(new CardXrefRecord(ROW_1_CARD_NUM, 50, 51L));
        }

        @Test
        @DisplayName("A record never equals null or an unrelated type")
        void neverEqualsNullOrAnotherType() {
            CardXrefRecord record = sample();

            assertThat(record.equals(null)).isFalse();
            assertThat(record.equals(ROW_1_CARD_NUM)).isFalse();
            assertThat(record).isNotEqualTo(new Object());
        }

        @Test
        @DisplayName("A padded card number is not equal to its trimmed form - padding is data")
        void paddingParticipatesInEquality() {
            assertThat(new CardXrefRecord("ABC", 1, 1L))
                    .isNotEqualTo(new CardXrefRecord("ABC             ", 1, 1L));
        }

        @Test
        @DisplayName("toString names every field by its copybook name and quotes the padding")
        void toStringSpeaksTheCopybooksVocabulary() {
            String rendered = new CardXrefRecord("ABC", 50, 50L).toString();

            assertThat(rendered)
                    .startsWith("CARD-XREF-RECORD[")
                    .contains("XREF-CARD-NUM='ABC'")
                    .contains("XREF-CUST-ID=50")
                    .contains("XREF-ACCT-ID=50")
                    .endsWith("]");
            // Deterministic: no clock, no identity hash, no locale-dependent formatting.
            assertThat(rendered).isEqualTo(new CardXrefRecord("ABC", 50, 50L).toString());
        }
    }
}
