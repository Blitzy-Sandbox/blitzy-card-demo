package com.vsergeychik.carddemo.transaction.model;

import com.vsergeychik.carddemo.common.DiagnosticText;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.nio.charset.Charset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Verifies {@link TranRecord} against {@code app/cpy/CVTRA05Y.cpy} and against the behaviour its nine
 * consuming COBOL programs depend on.
 *
 * <p>The two fixture rows embedded below are transcribed field by field from
 * {@code app/data/ASCII/dailytran.txt} - the 350-byte {@code CVTRA06Y} twin of this layout - and each
 * was verified character-for-character against the real file. They are embedded rather than loaded from
 * the classpath so that this test is hermetic: it asserts the copybook contract, not the availability of
 * a fixture resource.
 *
 * <p>Both code pages are always named explicitly. Nothing here relies on a platform default, because
 * the zoned sign overpunch bytes differ between code pages and a default-charset test would pass or
 * fail according to the machine it ran on.
 */
@DisplayName("TranRecord - CVTRA05Y, 350 bytes")
class TranRecordTest {

    private static final Charset ASCII = Charset.forName("US-ASCII");
    private static final Charset EBCDIC = Charset.forName("IBM037");

    /**
     * Row 1 of {@code app/data/ASCII/dailytran.txt}, assembled from its fourteen field images so each
     * one is visible and auditable. A positive amount, {@code 'G'} overpunch.
     */
    private static final String ROW_1 =
            "0000000000683580"                            // TRAN-ID            X(16)
            + "01"                                        // TRAN-TYPE-CD       X(02)
            + "0001"                                      // TRAN-CAT-CD        9(04)
            + "POS TERM  "                                // TRAN-SOURCE        X(10)
            + "Purchase at Abshire-Lowe" + " ".repeat(76) // TRAN-DESC          X(100)
            + "0000005047G"                               // TRAN-AMT           S9(09)V99 = 504.77
            + "800000000"                                 // TRAN-MERCHANT-ID   9(09)
            + "Abshire-Lowe" + " ".repeat(38)             // TRAN-MERCHANT-NAME X(50)
            + "North Enoshaven" + " ".repeat(35)          // TRAN-MERCHANT-CITY X(50)
            + "72112     "                                // TRAN-MERCHANT-ZIP  X(10)
            + "4859452612877065"                          // TRAN-CARD-NUM      X(16)
            + "2022-06-10 19:27:53.000000"                // TRAN-ORIG-TS       X(26)
            + " ".repeat(26)                              // TRAN-PROC-TS       X(26), unprocessed
            + " ".repeat(20);                             // FILLER             X(20)

    /**
     * Row 7 of the same fixture. A negative amount carrying a {@code 'P'} overpunch, which is how the
     * fixture stores a returned item.
     */
    private static final String ROW_7 =
            "0000000016259484"                            // TRAN-ID            X(16)
            + "03"                                        // TRAN-TYPE-CD       X(02)
            + "0001"                                      // TRAN-CAT-CD        9(04)
            + "OPERATOR  "                                // TRAN-SOURCE        X(10)
            + "Return item at Sipes Inc" + " ".repeat(76) // TRAN-DESC          X(100)
            + "0000000567P"                               // TRAN-AMT           S9(09)V99 = -56.77
            + "800000000"                                 // TRAN-MERCHANT-ID   9(09)
            + "Sipes Inc" + " ".repeat(41)                // TRAN-MERCHANT-NAME X(50)
            + "Emilioside" + " ".repeat(40)               // TRAN-MERCHANT-CITY X(50)
            + "93329     "                                // TRAN-MERCHANT-ZIP  X(10)
            + "4011500891777367"                          // TRAN-CARD-NUM      X(16)
            + "2022-06-10 19:27:53.000000"                // TRAN-ORIG-TS       X(26)
            + " ".repeat(26)                              // TRAN-PROC-TS       X(26)
            + " ".repeat(20);                             // FILLER             X(20)

    @Nested
    @DisplayName("declared geometry")
    class DeclaredGeometry {

        @Test
        @DisplayName("the record is the 350 bytes the copybook's RECLN comment declares")
        void recordLengthIs350() {
            assertThat(TranRecord.RECORD_LENGTH).isEqualTo(350);
            assertThat(TranRecord.LAYOUT.recordLength()).isEqualTo(350);
        }

        @Test
        @DisplayName("the fourteen declared spans sum to exactly 350 with no gap and no overlap")
        void declaredSpansAccountForEveryByte() {
            assertThat(TranRecord.sumOfDeclaredSpanLengths()).isEqualTo(TranRecord.RECORD_LENGTH);
            assertThat(TranRecord.LAYOUT.storageSpans()).hasSize(14);
            assertThat(TranRecord.LAYOUT.redefinitions()).isEmpty();

            int cursor = 0;
            for (FieldSpan span : TranRecord.LAYOUT.storageSpans()) {
                assertThat(span.offset()).as("contiguity at %s", span.name()).isEqualTo(cursor);
                cursor += span.length();
            }
            assertThat(cursor).isEqualTo(TranRecord.RECORD_LENGTH);
        }

        @ParameterizedTest(name = "{0} at 0-based {1}, {2} bytes")
        @CsvSource({
                "TRAN-ID,              0,  16",
                "TRAN-TYPE-CD,        16,   2",
                "TRAN-CAT-CD,         18,   4",
                "TRAN-SOURCE,         22,  10",
                "TRAN-DESC,           32, 100",
                "TRAN-AMT,           132,  11",
                "TRAN-MERCHANT-ID,   143,   9",
                "TRAN-MERCHANT-NAME, 152,  50",
                "TRAN-MERCHANT-CITY, 202,  50",
                "TRAN-MERCHANT-ZIP,  252,  10",
                "TRAN-CARD-NUM,      262,  16",
                "TRAN-ORIG-TS,       278,  26",
                "TRAN-PROC-TS,       304,  26",
        })
        @DisplayName("every referable span sits where CVTRA05Y puts it")
        void everySpanMatchesTheCopybook(String name, int offset, int length) {
            FieldSpan span = TranRecord.LAYOUT.span(name);
            assertThat(span.offset()).isEqualTo(offset);
            assertThat(span.length()).isEqualTo(length);
        }

        @Test
        @DisplayName("the named offset and length constants agree with the span descriptors")
        void constantsAgreeWithDescriptors() {
            assertThat(TranRecord.TRAN_ID.offset()).isEqualTo(TranRecord.TRAN_ID_OFFSET);
            assertThat(TranRecord.TRAN_ID.length()).isEqualTo(TranRecord.TRAN_ID_LENGTH);
            assertThat(TranRecord.TRAN_CARD_NUM.offset()).isEqualTo(TranRecord.TRAN_CARD_NUM_OFFSET);
            assertThat(TranRecord.TRAN_CARD_NUM.length()).isEqualTo(TranRecord.TRAN_CARD_NUM_LENGTH);
            assertThat(TranRecord.FILLER.offset()).isEqualTo(TranRecord.FILLER_OFFSET);
            assertThat(TranRecord.FILLER.length()).isEqualTo(TranRecord.FILLER_LENGTH);
            assertThat(TranRecord.FILLER.kind().filler()).isTrue();
            assertThat(TranRecord.FILLER.name()).isEqualTo("FILLER");
        }

        @Test
        @DisplayName("TRAN-AMT occupies p+s = 11 bytes, the sign being overpunched not stored")
        void signedSpanIsElevenBytes() {
            assertThat(TranRecord.TRAN_AMT_INTEGER_DIGITS).isEqualTo(9);
            assertThat(TranRecord.TRAN_AMT_SCALE).isEqualTo(2);
            assertThat(TranRecord.TRAN_AMT_LENGTH)
                    .isEqualTo(TranRecord.TRAN_AMT_INTEGER_DIGITS + TranRecord.TRAN_AMT_SCALE)
                    .isEqualTo(11);
            assertThat(TranRecord.TRAN_AMT.length()).isEqualTo(11);
        }

        @Test
        @DisplayName("the monetary scale comes from CobolDecimal, so the policy lives in one place")
        void monetaryScaleIsCentralised() {
            assertThat(TranRecord.TRAN_AMT_SCALE).isEqualTo(CobolDecimal.MONETARY_SCALE);
        }

        @Test
        @DisplayName("TRAN-PROC-DT is the first ten bytes of TRAN-PROC-TS, as TRANREPT.jcl:42 declares")
        void procDateSharesProcTimestampOffset() {
            assertThat(TranRecord.TRAN_PROC_DT_OFFSET).isEqualTo(TranRecord.TRAN_PROC_TS_OFFSET);
            assertThat(TranRecord.TRAN_PROC_DT_LENGTH).isEqualTo(10);
            // TRANREPT.jcl declares TRAN-PROC-DT,305,10 - 1-based. 305 - 1 == 304.
            assertThat(TranRecord.TRAN_PROC_DT_OFFSET).isEqualTo(305 - 1);
        }

        @Test
        @DisplayName("the TRANSACT key is TRAN-ID in full, as CBTRN02C's FD split confirms")
        void keyLengthIsSixteen() {
            assertThat(TranRecord.TRAN_ID_KEY_LENGTH)
                    .isEqualTo(TranRecord.TRAN_ID_LENGTH)
                    .isEqualTo(16);
        }

        @Test
        @DisplayName("TRAN-CARD-NUM sits at 1-based 263, as TRANREPT.jcl:41 declares")
        void cardNumberOffsetMatchesTheSortSymbol() {
            assertThat(TranRecord.TRAN_CARD_NUM_OFFSET).isEqualTo(263 - 1);
            assertThat(TranRecord.TRAN_CARD_NUM_LENGTH).isEqualTo(16);
        }
    }

    @Nested
    @DisplayName("an initialised area")
    class InitialisedArea {

        @Test
        @DisplayName("is 350 bytes: text blank, unsigned numerics zero-filled, TRAN-AMT a signed zero")
        void newRecordIsInitialisedPerPicture() {
            TranRecord record = new TranRecord(ASCII);

            assertThat(record.rawImage()).hasSize(350);
            assertThat(record.tranId()).isEqualTo(" ".repeat(16));
            assertThat(record.tranDesc()).isEqualTo(" ".repeat(100));
            assertThat(record.tranCatCdImage()).isEqualTo("0000");
            assertThat(record.tranMerchantIdImage()).isEqualTo("000000000");
            assertThat(record.tranAmtImage())
                    .as("TRAN-AMT is S9(09)V99: ten zoned zeros and a positive-zero overpunch, which "
                            + "is how a zero-valued signed field is stored in app/data/ASCII")
                    .isEqualTo("0".repeat(10) + "{");
            assertThat(record.tranAmt()).isEqualByComparingTo("0.00");
            assertThat(record.filler()).isEqualTo(" ".repeat(20));
            assertThat(record.charset()).isEqualTo(ASCII);
        }

        @Test
        @DisplayName("initialises identically under EBCDIC, using that code page's own pad bytes")
        void newRecordHonoursTheNamedCodePage() {
            TranRecord record = new TranRecord(EBCDIC);

            assertThat(record.charset()).isEqualTo(EBCDIC);
            assertThat(record.tranCatCdImage()).isEqualTo("0000");
            assertThat(record.filler()).isEqualTo(" ".repeat(20));
            // EBCDIC space is 0x40, not 0x20 - proof the pad byte came from the named charset.
            assertThat(record.rawImage()[TranRecord.FILLER_OFFSET]).isEqualTo((byte) 0x40);
        }
    }

    @Nested
    @DisplayName("decoding a fixture row")
    class FixtureDecode {

        @Test
        @DisplayName("both embedded rows are exactly 350 characters")
        void embeddedRowsAreWellFormed() {
            assertThat(ROW_1).hasSize(350);
            assertThat(ROW_7).hasSize(350);
        }

        @Test
        @DisplayName("row 1 decodes field for field through the copybook offsets")
        void rowOneDecodesFieldForField() {
            TranRecord record = TranRecord.decode(ROW_1, ASCII);

            assertThat(record.tranId()).isEqualTo("0000000000683580");
            assertThat(record.tranTypeCd()).isEqualTo("01");
            assertThat(record.tranCatCd()).isEqualTo(1);
            assertThat(record.tranCatCdImage()).isEqualTo("0001");
            assertThat(record.tranSource()).isEqualTo("POS TERM  ");
            assertThat(record.tranDesc())
                    .startsWith("Purchase at Abshire-Lowe")
                    .hasSize(100);
            assertThat(record.tranAmtImage()).isEqualTo("0000005047G");
            assertThat(record.tranAmt()).isEqualByComparingTo("504.77");
            assertThat(record.tranMerchantId()).isEqualTo(800000000);
            assertThat(record.tranMerchantIdImage()).isEqualTo("800000000");
            assertThat(record.tranMerchantName()).startsWith("Abshire-Lowe").hasSize(50);
            assertThat(record.tranMerchantCity()).startsWith("North Enoshaven").hasSize(50);
            assertThat(record.tranMerchantZip()).isEqualTo("72112     ");
            assertThat(record.tranCardNum()).isEqualTo("4859452612877065");
            assertThat(record.tranOrigTs()).isEqualTo("2022-06-10 19:27:53.000000");
            assertThat(record.tranProcTs()).isEqualTo(" ".repeat(26));
            assertThat(record.filler()).isEqualTo(" ".repeat(20));
        }

        @Test
        @DisplayName("row 7 decodes a negative amount from its J-to-R overpunch")
        void rowSevenDecodesANegativeAmount() {
            TranRecord record = TranRecord.decode(ROW_7, ASCII);

            assertThat(record.tranAmtImage()).isEqualTo("0000000567P");
            assertThat(record.tranAmt()).isEqualByComparingTo("-56.77");
            assertThat(record.tranAmt().signum()).isNegative();
            assertThat(record.tranSource()).isEqualTo("OPERATOR  ");
            assertThat(record.tranCardNum()).isEqualTo("4011500891777367");
        }

        @Test
        @DisplayName("TRAN-AMT is decoded at scale exactly 2, never as a binary float")
        void amountCarriesTheDeclaredScale() {
            BigDecimal amount = TranRecord.decode(ROW_1, ASCII).tranAmt();

            assertThat(amount.scale()).isEqualTo(2);
            assertThat(amount).isEqualTo(new BigDecimal("504.77"));
        }

        @Test
        @DisplayName("character fields keep their significant trailing spaces")
        void characterFieldsAreNotTrimmed() {
            TranRecord record = TranRecord.decode(ROW_1, ASCII);

            // TRAN-SOURCE really is "POS TERM" followed by two spaces in the fixture; trimming it
            // would change both the record width on write-back and the report column alignment.
            assertThat(record.tranSource()).isEqualTo("POS TERM  ").endsWith("  ");
            assertThat(record.tranMerchantZip()).endsWith("     ");
            assertThat(record.tranDesc()).endsWith(" ");
        }

        @Test
        @DisplayName("the FILLER span is present, twenty bytes wide and space-filled")
        void fillerIsPresentAndFilled() {
            TranRecord record = TranRecord.decode(ROW_1, ASCII);

            assertThat(record.filler()).isEqualTo(" ".repeat(20)).hasSize(20);
            assertThat(record.rawSpanBytes(TranRecord.FILLER)).hasSize(20);
            // If FILLER were omitted the record would be 330 bytes and LAYOUT could not have been built.
            assertThat(record.rawImage()).hasSize(350);
        }

        @Test
        @DisplayName("TRAN-PROC-DT is the (1:10) reference-modified slice of TRAN-PROC-TS")
        void procDateIsTheFirstTenCharacters() {
            TranRecord record = TranRecord.decode(ROW_1, ASCII);
            record.moveTranProcTs("2022-07-18 00:00:00.000000");

            assertThat(record.tranProcDt())
                    .isEqualTo("2022-07-18")
                    .isEqualTo(record.tranProcTs().substring(0, 10))
                    .hasSize(10);
        }

        @Test
        @DisplayName("displayImage reproduces DISPLAY TRAN-RECORD exactly")
        void displayImageIsTheWholeRecordAsText() {
            TranRecord record = TranRecord.decode(ROW_1, ASCII);

            assertThat(record.displayImage()).hasSize(350).isEqualTo(ROW_1);
        }

        @Test
        @DisplayName("a short or over-long row is rejected rather than silently reshaping the record")
        void wrongWidthIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TranRecord.decode(ROW_1.substring(0, 349), ASCII));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TranRecord.decode(ROW_1 + " ", ASCII));
        }
    }

    @Nested
    @DisplayName("zoned sign overpunch")
    class SignOverpunch {

        @ParameterizedTest(name = "{0} decodes to {1}")
        @CsvSource({
                // zone C - positive final digit 0 to 9
                "0000000000{, 0.00",
                "0000000000A, 0.01",
                "0000000000E, 0.05",
                "0000000000I, 0.09",
                "0000005047G, 504.77",
                // zone D - negative final digit 0 to 9
                "0000000000}, 0.00",
                "0000000000J, -0.01",
                "0000000000R, -0.09",
                "0000000567P, -56.77",
                "0000009190}, -919.00",
                // zone F - a plain trailing digit is read as positive
                "00000000005, 0.05",
                "00000504770, 5047.70",
        })
        @DisplayName("every legal overpunch character decodes to the value COBOL stores")
        void overpunchDecodesCorrectly(String image, String expected) {
            TranRecord record = new TranRecord(ASCII);
            record.writeTranAmtImage(image);

            assertThat(record.tranAmtImage()).isEqualTo(image);
            assertThat(record.tranAmt()).isEqualByComparingTo(expected);
        }

        @ParameterizedTest(name = "{0} round-trips unchanged")
        @ValueSource(strings = {
                // The six '}' rows of dailytran.txt. Each is an ordinary negative amount whose final
                // digit happens to be zero, so a numeric round-trip is faithful for all of them.
                "0000009190}", "0000002430}", "0000007630}",
                "0000009070}", "0000003720}", "0000004350}",
        })
        @DisplayName("a '}' carrying a non-zero magnitude survives a numeric round-trip")
        void negativeFinalZeroDigitRoundTrips(String image) {
            TranRecord record = new TranRecord(ASCII);
            record.writeTranAmtImage(image);

            BigDecimal decoded = record.tranAmt();
            assertThat(decoded.signum()).isNegative();

            record.moveTranAmt(decoded);
            assertThat(record.tranAmtImage()).isEqualTo(image);
        }
    }

    @Nested
    @DisplayName("the negative-zero re-encode hazard")
    class NegativeZeroHazard {

        private static final String NEGATIVE_ZERO = "0000000000}";
        private static final String POSITIVE_ZERO = "0000000000{";

        @Test
        @DisplayName("a whole-value negative zero decodes to plain zero, BigDecimal having no signed zero")
        void negativeZeroDecodesToZero() {
            TranRecord record = new TranRecord(ASCII);
            record.writeTranAmtImage(NEGATIVE_ZERO);

            assertThat(record.tranAmt()).isEqualByComparingTo("0.00");
            assertThat(record.tranAmt().signum()).isZero();
            assertThat(record.hasZeroTranAmt()).isTrue();
        }

        @Test
        @DisplayName("a numeric round-trip normalises it to a positive zero - the one lossy path")
        void numericRoundTripLosesTheSign() {
            TranRecord record = new TranRecord(ASCII);
            record.writeTranAmtImage(NEGATIVE_ZERO);

            record.moveTranAmt(record.tranAmt());

            assertThat(record.tranAmtImage())
                    .isEqualTo(POSITIVE_ZERO)
                    .isNotEqualTo(NEGATIVE_ZERO);
        }

        @Test
        @DisplayName("the raw-image path copies it byte for byte, which is why it must be used")
        void rawCopyPreservesIt() {
            TranRecord original = TranRecord.decode(ROW_1, ASCII);
            original.writeTranAmtImage(NEGATIVE_ZERO);

            assertThat(original.copy().rawImage()).isEqualTo(original.rawImage());
            assertThat(original.encode(ASCII)).isEqualTo(original.rawImage());
            assertThat(original.copy().tranAmtImage()).isEqualTo(NEGATIVE_ZERO);
            assertThat(TranRecord.decode(original.rawImage(), ASCII).tranAmtImage())
                    .isEqualTo(NEGATIVE_ZERO);
        }

        @Test
        @DisplayName("copying the raw amount span preserves it where a numeric copy would not")
        void spanCopyPreservesItAcrossRecords() {
            TranRecord source = new TranRecord(ASCII);
            source.writeTranAmtImage(NEGATIVE_ZERO);

            TranRecord viaImage = new TranRecord(ASCII);
            viaImage.writeTranAmtImage(source.tranAmtImage());
            assertThat(viaImage.tranAmtImage()).isEqualTo(NEGATIVE_ZERO);

            TranRecord viaValue = new TranRecord(ASCII);
            viaValue.moveTranAmt(source.tranAmt());
            assertThat(viaValue.tranAmtImage()).isEqualTo(POSITIVE_ZERO);
        }

        @Test
        @DisplayName("a positive zero and a negative zero are equal in value but different records")
        void equalValueDifferentRecord() {
            TranRecord positive = new TranRecord(ASCII);
            positive.writeTranAmtImage(POSITIVE_ZERO);
            TranRecord negative = new TranRecord(ASCII);
            negative.writeTranAmtImage(NEGATIVE_ZERO);

            assertThat(positive.tranAmt()).isEqualByComparingTo(negative.tranAmt());
            assertThat(positive.hasZeroTranAmt()).isTrue();
            assertThat(negative.hasZeroTranAmt()).isTrue();
            assertThat(positive).isNotEqualTo(negative);
        }

        @Test
        @DisplayName("hasZeroTranAmt is false for a non-zero amount and uses compareTo not equals")
        void zeroPredicateDistinguishesValueFromScale() {
            TranRecord record = TranRecord.decode(ROW_1, ASCII);

            assertThat(record.hasZeroTranAmt()).isFalse();
            // 0.00 and 0 differ under BigDecimal.equals but not under compareTo; the predicate must
            // agree with COBOL's numeric comparison, so it uses compareTo.
            assertThat(CobolDecimal.monetaryZero()).isNotEqualTo(BigDecimal.ZERO);
            assertThat(CobolDecimal.monetaryZero()).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Nested
    @DisplayName("encode and copy")
    class EncodeAndCopy {

        @Test
        @DisplayName("decode then encode is byte-identical for both fixture rows")
        void roundTripIsByteIdentical() {
            assertThat(TranRecord.decode(ROW_1, ASCII).encode(ASCII))
                    .isEqualTo(ROW_1.getBytes(ASCII));
            assertThat(TranRecord.decode(ROW_7, ASCII).encode(ASCII))
                    .isEqualTo(ROW_7.getBytes(ASCII));
        }

        @Test
        @DisplayName("encoding under the record's own charset returns the backing bytes verbatim")
        void sameCharsetReturnsBackingBytes() {
            TranRecord record = TranRecord.decode(ROW_1, ASCII);

            assertThat(record.encode(ASCII)).isEqualTo(record.rawImage());
        }

        @Test
        @DisplayName("encoding under another charset transcodes yet preserves every field and the sign")
        void differentCharsetTranscodes() {
            TranRecord ascii = TranRecord.decode(ROW_1, ASCII);
            ascii.writeTranAmtImage("0000000000}");

            byte[] ebcdic = ascii.encode(EBCDIC);

            assertThat(ebcdic).hasSize(350).isNotEqualTo(ascii.rawImage());

            TranRecord reread = TranRecord.decode(ebcdic, EBCDIC);
            assertThat(reread.tranId()).isEqualTo(ascii.tranId());
            assertThat(reread.tranCardNum()).isEqualTo(ascii.tranCardNum());
            assertThat(reread.tranDesc()).isEqualTo(ascii.tranDesc());
            assertThat(reread.tranCatCdImage()).isEqualTo(ascii.tranCatCdImage());
            assertThat(reread.filler()).isEqualTo(ascii.filler());
            // The character-level transcode keeps even a negative zero, which a numeric one would not.
            assertThat(reread.tranAmtImage()).isEqualTo("0000000000}");
        }

        @Test
        @DisplayName("rawImage hands out a copy, so a caller cannot reach into the record")
        void rawImageIsDefensivelyCopied() {
            TranRecord record = TranRecord.decode(ROW_1, ASCII);

            byte[] image = record.rawImage();
            image[0] = (byte) 'Z';

            assertThat(record.tranId()).isEqualTo("0000000000683580");
            assertThat(record.rawImage()).isNotEqualTo(image);
        }

        @Test
        @DisplayName("a copy is independent of its original")
        void copyIsIndependent() {
            TranRecord original = TranRecord.decode(ROW_1, ASCII);
            TranRecord duplicate = original.copy();

            assertThat(duplicate).isEqualTo(original);
            assertThat(duplicate.charset()).isEqualTo(original.charset());

            duplicate.moveTranCardNum("9999999999999999");

            assertThat(original.tranCardNum()).isEqualTo("4859452612877065");
            assertThat(duplicate.tranCardNum()).isEqualTo("9999999999999999");
            assertThat(duplicate).isNotEqualTo(original);
        }

        @Test
        @DisplayName("decoding from bytes and from text give the same record")
        void byteAndTextEntryPointsAgree() {
            assertThat(TranRecord.decode(ROW_1.getBytes(ASCII), ASCII))
                    .isEqualTo(TranRecord.decode(ROW_1, ASCII));
        }
    }

    @Nested
    @DisplayName("MOVE semantics")
    class MoveSemantics {

        @Test
        @DisplayName("a PIC X receiver is filled from the left and padded on the right")
        void picXPadsOnTheRight() {
            TranRecord record = new TranRecord(ASCII);
            record.moveTranSource("System");

            assertThat(record.tranSource()).isEqualTo("System    ").hasSize(10);
        }

        @Test
        @DisplayName("a PIC X receiver truncates on the RIGHT, discarding the tail not the head")
        void picXTruncatesOnTheRight() {
            TranRecord record = new TranRecord(ASCII);
            record.moveTranTypeCd("ABCD");

            assertThat(record.tranTypeCd()).isEqualTo("AB").isNotEqualTo("CD");
        }

        @Test
        @DisplayName("a MOVE blanks the whole receiver first, unlike STRING ... INTO")
        void moveBlanksTheReceiver() {
            TranRecord record = new TranRecord(ASCII);
            record.moveTranDesc("X".repeat(100));

            record.moveTranDesc("Int. for a/c 00000000011");

            assertThat(record.tranDesc())
                    .isEqualTo("Int. for a/c 00000000011" + " ".repeat(76))
                    .doesNotContain("X");
        }

        @Test
        @DisplayName("MOVE SPACES blanks the merchant text fields, as CBACT04C:492-494 does")
        void moveSpacesBlanksTextFields() {
            TranRecord record = TranRecord.decode(ROW_1, ASCII);

            record.moveTranMerchantName("");
            record.moveTranMerchantCity("");
            record.moveTranMerchantZip("");

            assertThat(record.tranMerchantName()).isEqualTo(" ".repeat(50));
            assertThat(record.tranMerchantCity()).isEqualTo(" ".repeat(50));
            assertThat(record.tranMerchantZip()).isEqualTo(" ".repeat(10));
        }

        @Test
        @DisplayName("a PIC 9 receiver zero-fills on the left, as MOVE 0 TO TRAN-MERCHANT-ID does")
        void pic9ZeroFillsOnTheLeft() {
            TranRecord record = new TranRecord(ASCII);
            record.moveTranMerchantId(0L);
            assertThat(record.tranMerchantIdImage()).isEqualTo("000000000");

            record.moveTranMerchantId(800000000L);
            assertThat(record.tranMerchantIdImage()).isEqualTo("800000000");
            assertThat(record.tranMerchantId()).isEqualTo(800000000);

            record.moveTranCatCd(5);
            assertThat(record.tranCatCdImage()).isEqualTo("0005");
            assertThat(record.tranCatCd()).isEqualTo(5);
        }

        @Test
        @DisplayName("a PIC 9 receiver truncates on the LEFT, keeping the low-order digits")
        void pic9TruncatesOnTheLeft() {
            TranRecord record = new TranRecord(ASCII);
            record.moveTranCatCd(123456);

            assertThat(record.tranCatCdImage()).isEqualTo("3456").isNotEqualTo("1234");
        }

        @Test
        @DisplayName("MOVE '05' TO TRAN-CAT-CD stores 0005, not 0500 - the IBM alphanumeric-sender rule")
        void alphanumericLiteralIntoNumericReceiverIsDecimalAligned() {
            TranRecord record = new TranRecord(ASCII);

            // CBACT04C.cbl:483. The receiver is PIC 9(04), so IBM Enterprise COBOL treats the
            // alphanumeric sender as an unsigned integer and performs a numeric move: aligned on the
            // implied decimal point and zero-filled on the left.
            record.moveTranCatCd("05");

            assertThat(record.tranCatCdImage()).isEqualTo("0005").isNotEqualTo("0500");
            assertThat(record.tranCatCd()).isEqualTo(5);
        }

        @Test
        @DisplayName("an alphanumeric sender into a PIC 9 receiver must be all digits")
        void alphanumericLiteralMustBeNumeric() {
            TranRecord record = new TranRecord(ASCII);

            assertThatIllegalArgumentException().isThrownBy(() -> record.moveTranCatCd("A5"));
            assertThatIllegalArgumentException().isThrownBy(() -> record.moveTranMerchantId("00 00"));
        }

        @Test
        @DisplayName("an unsigned PIC 9 receiver rejects a negative sender, having no room for a sign")
        void unsignedReceiverRejectsNegative() {
            TranRecord record = new TranRecord(ASCII);

            assertThatIllegalArgumentException().isThrownBy(() -> record.moveTranCatCd(-1));
            assertThatIllegalArgumentException().isThrownBy(() -> record.moveTranMerchantId(-1L));
        }

        @Test
        @DisplayName("an alphanumeric merchant id is zero-filled on the left too")
        void alphanumericMerchantIdIsZeroFilled() {
            TranRecord record = new TranRecord(ASCII);
            record.moveTranMerchantId("42");

            assertThat(record.tranMerchantIdImage()).isEqualTo("000000042");
            assertThat(record.tranMerchantId()).isEqualTo(42);
        }

        @Test
        @DisplayName("TRAN-AMT truncates excess fraction digits toward zero, never rounding")
        void amountTruncatesRatherThanRounds() {
            TranRecord record = new TranRecord(ASCII);

            // ROUNDED appears nowhere in the 28 programs, so the faithful policy is RoundingMode.DOWN.
            // Half-up or half-even would give 1.24 and -1.24 here.
            record.moveTranAmt(new BigDecimal("1.239"));
            assertThat(record.tranAmt()).isEqualByComparingTo("1.23");

            record.moveTranAmt(new BigDecimal("-1.239"));
            assertThat(record.tranAmt()).isEqualByComparingTo("-1.23");

            record.moveTranAmt(new BigDecimal("1.999"));
            assertThat(record.tranAmt()).isEqualByComparingTo("1.99");
        }

        @Test
        @DisplayName("TRAN-AMT is stored at scale 2 with the sign overpunched into the trailing byte")
        void amountIsStoredAsAZonedImage() {
            TranRecord record = new TranRecord(ASCII);

            record.moveTranAmt(new BigDecimal("504.77"));
            assertThat(record.tranAmtImage()).isEqualTo("0000005047G").hasSize(11);

            record.moveTranAmt(new BigDecimal("-56.77"));
            assertThat(record.tranAmtImage()).isEqualTo("0000000567P");

            record.moveTranAmt(new BigDecimal("-919.00"));
            assertThat(record.tranAmtImage()).isEqualTo("0000009190}");

            assertThat(record.tranAmt().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("an equal-scale MOVE, as CBACT04C:490 performs, changes nothing")
        void equalScaleMoveIsLossless() {
            TranRecord record = new TranRecord(ASCII);
            BigDecimal monthlyInterest = new BigDecimal("504.77"); // WS-MONTHLY-INT PIC S9(09)V99

            record.moveTranAmt(monthlyInterest);

            assertThat(record.tranAmt()).isEqualTo(monthlyInterest);
        }

        @Test
        @DisplayName("the card number is moved whole and stored unmasked")
        void cardNumberIsStoredWhole() {
            TranRecord record = new TranRecord(ASCII);
            record.moveTranCardNum("4859452612877065");

            assertThat(record.tranCardNum()).isEqualTo("4859452612877065").hasSize(16);
            assertThat(record.rawSpan(TranRecord.TRAN_CARD_NUM)).isEqualTo("4859452612877065");
        }

        @Test
        @DisplayName("timestamps of exactly 26 characters fill their fields")
        void timestampsFillTheirFields() {
            TranRecord record = new TranRecord(ASCII);
            String db2FormatTs = "2022-07-18 00:00:00.000000"; // DB2-FORMAT-TS PIC X(26)

            record.moveTranOrigTs(db2FormatTs);
            record.moveTranProcTs(db2FormatTs);

            assertThat(record.tranOrigTs()).isEqualTo(db2FormatTs).hasSize(26);
            assertThat(record.tranProcTs()).isEqualTo(db2FormatTs).hasSize(26);
        }

        @Test
        @DisplayName("every MOVE leaves the record exactly 350 bytes")
        void widthIsInvariantUnderEveryMove() {
            TranRecord record = new TranRecord(ASCII);
            record.moveTranId("X".repeat(40));
            record.moveTranDesc("Y".repeat(200));
            record.moveTranMerchantName("Z");
            record.moveTranAmt(new BigDecimal("-1.01"));
            record.moveTranCatCd(9999);

            assertThat(record.rawImage()).hasSize(350);
            assertThat(record.displayImage()).hasSize(350);
        }
    }

    @Nested
    @DisplayName("STRING ... DELIMITED BY SIZE INTO semantics")
    class StringIntoSemantics {

        @Test
        @DisplayName("STRING leaves the tail of the receiver exactly as it was")
        void stringIntoLeavesTheTailUntouched() {
            TranRecord record = new TranRecord(ASCII);
            record.moveTranDesc("X".repeat(100));

            // CBACT04C.cbl:485-489 strings 13 + 11 = 24 characters into TRAN-DESC PIC X(100).
            record.stringIntoTranDesc("Int. for a/c ", "00000000011");

            assertThat(record.tranDesc())
                    .isEqualTo("Int. for a/c 00000000011" + "X".repeat(76))
                    .hasSize(100);
        }

        @Test
        @DisplayName("the same content through MOVE blanks the tail - the two verbs really do differ")
        void moveAndStringIntoDifferObservably() {
            TranRecord viaString = new TranRecord(ASCII);
            viaString.moveTranDesc("X".repeat(100));
            viaString.stringIntoTranDesc("Int. for a/c ", "00000000011");

            TranRecord viaMove = new TranRecord(ASCII);
            viaMove.moveTranDesc("X".repeat(100));
            viaMove.moveTranDesc("Int. for a/c 00000000011");

            assertThat(viaString.tranDesc()).isNotEqualTo(viaMove.tranDesc());
            assertThat(viaString.tranDesc()).endsWith("X");
            assertThat(viaMove.tranDesc()).endsWith(" ");
        }

        @Test
        @DisplayName("operands are concatenated at their full declared size, filling TRAN-ID exactly")
        void operandsAreConcatenatedDelimitedBySize() {
            TranRecord record = new TranRecord(ASCII);

            // CBACT04C.cbl:476-480 - PARM-DATE X(10) followed by WS-TRANID-SUFFIX 9(06).
            record.stringIntoTranId("2022071800", "000001");

            assertThat(record.tranId()).isEqualTo("2022071800000001").hasSize(16);
        }

        @Test
        @DisplayName("content beyond the receiver's width is discarded, there being no ON OVERFLOW")
        void overflowIsDiscarded() {
            TranRecord record = new TranRecord(ASCII);
            record.stringIntoTranTypeCd("ABCDEF");

            assertThat(record.tranTypeCd()).isEqualTo("AB");
        }

        @Test
        @DisplayName("STRING is offered for every character field")
        void everyCharacterFieldHasAStringPath() {
            TranRecord record = new TranRecord(ASCII);

            record.stringIntoTranSource("POS");
            record.stringIntoTranMerchantName("Abshire");
            record.stringIntoTranMerchantCity("North");
            record.stringIntoTranMerchantZip("72112");
            record.stringIntoTranCardNum("4859452612877065");
            record.stringIntoTranOrigTs("2022-06-10");
            record.stringIntoTranProcTs("2022-07-18");

            assertThat(record.tranSource()).isEqualTo("POS       ");
            assertThat(record.tranMerchantName()).startsWith("Abshire").hasSize(50);
            assertThat(record.tranMerchantCity()).startsWith("North").hasSize(50);
            assertThat(record.tranMerchantZip()).isEqualTo("72112     ");
            assertThat(record.tranCardNum()).isEqualTo("4859452612877065");
            assertThat(record.tranOrigTs()).startsWith("2022-06-10").hasSize(26);
            assertThat(record.tranProcDt()).isEqualTo("2022-07-18");
        }

        @Test
        @DisplayName("a STRING with no sending item is rejected")
        void noOperandsIsRejected() {
            TranRecord record = new TranRecord(ASCII);

            assertThatIllegalArgumentException().isThrownBy(record::stringIntoTranDesc);
        }

        @Test
        @DisplayName("CBACT04C's 1300-B-WRITE-TX is reproducible statement for statement")
        void reproducesTheInterestTransactionBuild() {
            TranRecord record = new TranRecord(ASCII);

            record.stringIntoTranId("2022071800", "000001");            // :476-480
            record.moveTranTypeCd("01");                                // :482
            record.moveTranCatCd("05");                                 // :483
            record.moveTranSource("System");                            // :484
            record.stringIntoTranDesc("Int. for a/c ", "00000000011");  // :485-489
            record.moveTranAmt(new BigDecimal("504.77"));               // :490
            record.moveTranMerchantId(0L);                              // :491
            record.moveTranMerchantName("");                            // :492
            record.moveTranMerchantCity("");                            // :493
            record.moveTranMerchantZip("");                             // :494
            record.moveTranCardNum("4859452612877065");                 // :495
            record.moveTranOrigTs("2022-07-18 00:00:00.000000");        // :497
            record.moveTranProcTs("2022-07-18 00:00:00.000000");        // :498

            assertThat(record.tranId()).isEqualTo("2022071800000001");
            assertThat(record.tranTypeCd()).isEqualTo("01");
            assertThat(record.tranCatCdImage()).isEqualTo("0005");
            assertThat(record.tranSource()).isEqualTo("System    ");
            assertThat(record.tranDesc()).isEqualTo("Int. for a/c 00000000011" + " ".repeat(76));
            assertThat(record.tranAmtImage()).isEqualTo("0000005047G");
            assertThat(record.tranMerchantIdImage()).isEqualTo("000000000");
            assertThat(record.tranMerchantName()).isEqualTo(" ".repeat(50));
            assertThat(record.tranCardNum()).isEqualTo("4859452612877065");
            assertThat(record.tranProcDt()).isEqualTo("2022-07-18");
            assertThat(record.filler()).isEqualTo(" ".repeat(20));

            // WRITE FD-TRANFILE-REC FROM TRAN-RECORD at :500 writes exactly 350 bytes.
            assertThat(record.rawImage()).hasSize(350);
            assertThat(record.encode(ASCII)).isEqualTo(record.rawImage());
        }
    }

    @Nested
    @DisplayName("raw span access")
    class RawSpanAccess {

        @Test
        @DisplayName("returns the stored image of any declared span, untrimmed")
        void readsAnyDeclaredSpan() {
            TranRecord record = TranRecord.decode(ROW_1, ASCII);

            assertThat(record.rawSpan(TranRecord.TRAN_AMT)).isEqualTo("0000005047G");
            assertThat(record.rawSpan(TranRecord.TRAN_SOURCE)).isEqualTo("POS TERM  ");
            assertThat(record.rawSpan(TranRecord.FILLER)).isEqualTo(" ".repeat(20));
            assertThat(record.rawSpanBytes(TranRecord.TRAN_CARD_NUM))
                    .isEqualTo("4859452612877065".getBytes(ASCII));
        }

        @Test
        @DisplayName("the raw amount span is what DISPLAY 'TRAN-AMT ' TRAN-AMT writes to SYSOUT")
        void rawAmountSpanReproducesTheSysoutLine() {
            TranRecord record = TranRecord.decode(ROW_1, ASCII);

            // CBTRN03C.cbl:198 - a DISPLAY of a zoned item prints its stored bytes, not a number.
            assertThat("TRAN-AMT " + record.tranAmtImage()).isEqualTo("TRAN-AMT 0000005047G");
            assertThat(record.tranAmtImage()).isEqualTo(record.rawSpan(TranRecord.TRAN_AMT));
        }

        @Test
        @DisplayName("hands out a copy of the span bytes")
        void spanBytesAreCopied() {
            TranRecord record = TranRecord.decode(ROW_1, ASCII);

            byte[] bytes = record.rawSpanBytes(TranRecord.TRAN_ID);
            bytes[0] = (byte) 'Z';

            assertThat(record.tranId()).startsWith("0");
        }

        @Test
        @DisplayName("a descriptor from another copybook is rejected, not silently honoured")
        void foreignDescriptorIsRejected() {
            TranRecord record = TranRecord.decode(ROW_1, ASCII);
            FieldSpan foreign = FieldSpan.alphanumeric("DALYTRAN-ID", 0, 16);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> record.rawSpan(foreign))
                    .withMessageContaining("CVTRA05Y");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> record.rawSpanBytes(foreign));
        }

        @Test
        @DisplayName("a null descriptor is rejected")
        void nullDescriptorIsRejected() {
            TranRecord record = TranRecord.decode(ROW_1, ASCII);

            assertThatNullPointerException().isThrownBy(() -> record.rawSpan(null));
            assertThatNullPointerException().isThrownBy(() -> record.rawSpanBytes(null));
        }
    }

    @Nested
    @DisplayName("argument validation")
    class ArgumentValidation {

        @Test
        @DisplayName("a charset is always required - there is no platform default")
        void charsetIsMandatory() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new TranRecord(null))
                    .withMessageContaining("charset");
            assertThatNullPointerException()
                    .isThrownBy(() -> TranRecord.decode(ROW_1.getBytes(ASCII), null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TranRecord.decode(ROW_1, null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TranRecord.decode(ROW_1, ASCII).encode(null));
        }

        @Test
        @DisplayName("a null image is rejected on both decode entry points")
        void imageIsMandatory() {
            assertThatNullPointerException()
                    .isThrownBy(() -> TranRecord.decode((byte[]) null, ASCII));
            assertThatNullPointerException()
                    .isThrownBy(() -> TranRecord.decode((String) null, ASCII));
        }

        @ParameterizedTest(name = "an amount image of {0} characters is rejected")
        @ValueSource(strings = {"0000005047", "0000005047GG", "", "0"})
        @DisplayName("an amount image must be exactly eleven characters, never padded or truncated")
        void amountImageWidthIsExact(String image) {
            TranRecord record = new TranRecord(ASCII);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> record.writeTranAmtImage(image))
                    .withMessageContaining("TRAN-AMT");
        }

        @Test
        @DisplayName("an eleven-character amount image is accepted")
        void amountImageOfElevenIsAccepted() {
            TranRecord record = new TranRecord(ASCII);

            record.writeTranAmtImage("0000005047G");

            assertThat(record.tranAmtImage()).isEqualTo("0000005047G");
        }

        @Test
        @DisplayName("null values are rejected by the amount writers")
        void amountWritersRejectNull() {
            TranRecord record = new TranRecord(ASCII);

            assertThatNullPointerException().isThrownBy(() -> record.moveTranAmt(null));
            assertThatNullPointerException().isThrownBy(() -> record.writeTranAmtImage(null));
        }

        @Test
        @DisplayName("null values are rejected by the character writers")
        void characterWritersRejectNull() {
            TranRecord record = new TranRecord(ASCII);

            assertThatNullPointerException().isThrownBy(() -> record.moveTranId(null));
            assertThatNullPointerException().isThrownBy(() -> record.moveTranCardNum(null));
            assertThatNullPointerException().isThrownBy(() -> record.moveTranCatCd(null));
        }
    }

    @Nested
    @DisplayName("identity and diagnostics")
    class IdentityAndDiagnostics {

        @Test
        @DisplayName("a record equals itself")
        void isReflexive() {
            TranRecord record = TranRecord.decode(ROW_1, ASCII);

            assertThat(record.equals(record)).isTrue();
        }

        @Test
        @DisplayName("records with the same bytes under the same charset are equal")
        void sameBytesSameCharsetAreEqual() {
            TranRecord first = TranRecord.decode(ROW_1, ASCII);
            TranRecord second = TranRecord.decode(ROW_1, ASCII);

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        }

        @Test
        @DisplayName("records differing in one byte are not equal")
        void differentBytesAreNotEqual() {
            TranRecord first = TranRecord.decode(ROW_1, ASCII);
            TranRecord second = TranRecord.decode(ROW_1, ASCII);
            second.moveTranCatCd(2);

            assertThat(first).isNotEqualTo(second);
        }

        @Test
        @DisplayName("the same characters under different code pages are not the same record")
        void differentCharsetsAreNotEqual() {
            TranRecord ascii = TranRecord.decode(ROW_1, ASCII);
            TranRecord ebcdic = TranRecord.decode(ROW_1, EBCDIC);

            assertThat(ascii).isNotEqualTo(ebcdic);
            assertThat(ascii.rawImage()).isNotEqualTo(ebcdic.rawImage());
        }

        @Test
        @DisplayName("null and a foreign type are not equal to a record")
        void foreignValuesAreNotEqual() {
            TranRecord record = TranRecord.decode(ROW_1, ASCII);

            assertThat(record.equals(null)).isFalse();
            assertThat(record.equals("0000000000683580")).isFalse();
        }

        @Test
        @DisplayName("toString names every field and shows the card number in full, unmasked")
        void toStringIsCompleteAndUnmasked() {
            TranRecord record = TranRecord.decode(ROW_1, ASCII);

            String rendered = record.toString();

            // The structural codes identify the transaction and are disclosed in full.
            assertThat(rendered)
                    .contains("TRAN-ID='0000000000683580'")
                    .contains("TRAN-TYPE-CD='01'")
                    .contains("TRAN-CAT-CD='0001'")
                    .contains("TRAN-SOURCE='POS TERM  '")
                    .contains("TRAN-MERCHANT-ID='800000000'")
                    .contains("TRAN-ORIG-TS='2022-06-10 19:27:53.000000'")
                    .contains("FILLER.length=")
                    .contains("charset=US-ASCII");
            // The merchant fields stay legible - they identify a business, not a cardholder - and the
            // amount is given as both image and value, because that pair is what a numeric parity
            // failure is diagnosed from. Only the PAN is withheld. displayImage() and
            // fieldImages(Charset) still return every digit to a caller that asks by name, so a
            // byte-level comparison is unaffected.
            assertThat(rendered)
                    .doesNotContain("4859452612877065")
                    .contains("TRAN-CARD-NUM='************7065'");
        }
    }
}
