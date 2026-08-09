package com.vsergeychik.carddemo.transaction.model;

import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.nio.charset.Charset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Verifies {@link DalyTranRecord} against {@code app/cpy/CVTRA06Y.cpy} and against the behaviour its two
 * consuming COBOL programs depend on - {@code app/cbl/CBTRN02C.cbl}, the {@code POSTTRAN} poster, and
 * {@code app/cbl/CBTRN01C.cbl}, the orphan daily poster.
 *
 * <p>The two fixture rows embedded below are transcribed field by field from
 * {@code app/data/ASCII/dailytran.txt} and each was verified character-for-character against the real
 * file. They are embedded rather than loaded from the classpath so that this test is hermetic: it asserts
 * the copybook contract, not the availability of a fixture resource.
 *
 * <p>Both code pages are always named explicitly. Nothing here relies on a platform default, because the
 * zoned sign overpunch characters map to different bytes under different code pages.
 */
@DisplayName("DalyTranRecord - CVTRA06Y, 350 bytes")
class DalyTranRecordTest {

    private static final Charset ASCII = Charset.forName("US-ASCII");
    private static final Charset EBCDIC = Charset.forName("IBM037");

    /**
     * Row 1 of {@code app/data/ASCII/dailytran.txt}, assembled from its fourteen field images so each one
     * is visible and auditable. A positive amount, {@code 'G'} overpunch.
     */
    private static final String ROW_1 =
            "0000000000683580"                            // DALYTRAN-ID            X(16)
            + "01"                                        // DALYTRAN-TYPE-CD       X(02)
            + "0001"                                      // DALYTRAN-CAT-CD        9(04)
            + "POS TERM  "                                // DALYTRAN-SOURCE        X(10)
            + "Purchase at Abshire-Lowe" + " ".repeat(76) // DALYTRAN-DESC          X(100)
            + "0000005047G"                               // DALYTRAN-AMT   S9(09)V99 = 504.77
            + "800000000"                                 // DALYTRAN-MERCHANT-ID   9(09)
            + "Abshire-Lowe" + " ".repeat(38)             // DALYTRAN-MERCHANT-NAME X(50)
            + "North Enoshaven" + " ".repeat(35)          // DALYTRAN-MERCHANT-CITY X(50)
            + "72112     "                                // DALYTRAN-MERCHANT-ZIP  X(10)
            + "4859452612877065"                          // DALYTRAN-CARD-NUM      X(16)
            + "2022-06-10 19:27:53.000000"                // DALYTRAN-ORIG-TS       X(26)
            + " ".repeat(26)                              // DALYTRAN-PROC-TS       X(26), unprocessed
            + " ".repeat(20);                             // FILLER                 X(20)

    /**
     * Row 2 of the same fixture. A returned item, and one of the six rows whose amount carries a
     * <code>'&#125;'</code> overpunch - negative with a final digit of zero, which is {@code -919.00} and
     * emphatically not a zero value.
     */
    private static final String ROW_2 =
            "0000000001774260"                            // DALYTRAN-ID            X(16)
            + "03"                                        // DALYTRAN-TYPE-CD       X(02)
            + "0001"                                      // DALYTRAN-CAT-CD        9(04)
            + "OPERATOR  "                                // DALYTRAN-SOURCE        X(10)
            + "Return item at Nitzsche, Nicolas and Lowe"
            + " ".repeat(59)                              // DALYTRAN-DESC          X(100)
            + "0000009190}"                               // DALYTRAN-AMT  S9(09)V99 = -919.00
            + "800000000"                                 // DALYTRAN-MERCHANT-ID   9(09)
            + "Nitzsche, Nicolas and Lowe" + " ".repeat(24) // DALYTRAN-MERCHANT-NAME X(50)
            + "Fidelshire" + " ".repeat(40)               // DALYTRAN-MERCHANT-CITY X(50)
            + "53378     "                                // DALYTRAN-MERCHANT-ZIP  X(10)
            + "0927987108636232"                          // DALYTRAN-CARD-NUM      X(16)
            + "2022-06-10 19:27:53.000000"                // DALYTRAN-ORIG-TS       X(26)
            + " ".repeat(26)                              // DALYTRAN-PROC-TS       X(26)
            + " ".repeat(20);                             // FILLER                 X(20)

    @Nested
    @DisplayName("declared geometry")
    class DeclaredGeometry {

        @Test
        @DisplayName("the record is the 350 bytes the copybook's RECLN comment declares")
        void recordLengthIs350() {
            assertThat(DalyTranRecord.RECORD_LENGTH).isEqualTo(350);
            assertThat(DalyTranRecord.LAYOUT.recordLength()).isEqualTo(350);
        }

        @Test
        @DisplayName("the fourteen declared spans sum to exactly 350 with no gap and no overlap")
        void spansAreContiguousAndSumTo350() {
            assertThat(DalyTranRecord.LAYOUT.storageSpans()).hasSize(14);
            assertThat(DalyTranRecord.sumOfDeclaredSpanLengths()).isEqualTo(350);
            int next = 0;
            for (FieldSpan span : DalyTranRecord.LAYOUT.storageSpans()) {
                assertThat(span.offset()).as("%s starts where the previous span ends", span.name())
                        .isEqualTo(next);
                next += span.length();
            }
            assertThat(next).isEqualTo(DalyTranRecord.RECORD_LENGTH);
        }

        @ParameterizedTest(name = "{0} at 0-based {1} for {2} bytes")
        @DisplayName("every span sits where CVTRA06Y puts it, under its copybook name verbatim")
        @CsvSource({
            "DALYTRAN-ID,0,16",
            "DALYTRAN-TYPE-CD,16,2",
            "DALYTRAN-CAT-CD,18,4",
            "DALYTRAN-SOURCE,22,10",
            "DALYTRAN-DESC,32,100",
            "DALYTRAN-AMT,132,11",
            "DALYTRAN-MERCHANT-ID,143,9",
            "DALYTRAN-MERCHANT-NAME,152,50",
            "DALYTRAN-MERCHANT-CITY,202,50",
            "DALYTRAN-MERCHANT-ZIP,252,10",
            "DALYTRAN-CARD-NUM,262,16",
            "DALYTRAN-ORIG-TS,278,26",
            "DALYTRAN-PROC-TS,304,26"})
        void everySpanSitsWhereTheCopybookPutsIt(String name, int offset, int length) {
            assertThat(DalyTranRecord.LAYOUT.hasSpan(name)).isTrue();
            FieldSpan span = DalyTranRecord.LAYOUT.span(name);
            assertThat(span.offset()).isEqualTo(offset);
            assertThat(span.length()).isEqualTo(length);
        }

        @Test
        @DisplayName("the trailing span is named FILLER, without the DALYTRAN- prefix the others carry")
        void theFillerSpanKeepsTheCopybooksOwnUnprefixedName() {
            assertThat(DalyTranRecord.FILLER.name()).isEqualTo("FILLER");
            assertThat(DalyTranRecord.LAYOUT.hasSpan("DALYTRAN-FILLER")).isFalse();
            assertThat(DalyTranRecord.FILLER_OFFSET).isEqualTo(330);
            assertThat(DalyTranRecord.FILLER_LENGTH).isEqualTo(20);
        }

        @Test
        @DisplayName("FILLER occupies its twenty bytes yet stays unreferable by name, as in COBOL")
        void fillerIsPresentInStorageButNotReferableByName() {
            // Present as a first-class span: without it the fourteen spans could not sum to 350.
            assertThat(DalyTranRecord.LAYOUT.storageSpans()).contains(DalyTranRecord.FILLER);
            assertThat(DalyTranRecord.FILLER.offset()).isEqualTo(330);
            assertThat(DalyTranRecord.FILLER.length()).isEqualTo(20);
            assertThat(new DalyTranRecord(ASCII).filler()).isEqualTo(" ".repeat(20));
            assertThat(DalyTranRecord.decode(ROW_1, ASCII).filler()).isEqualTo(" ".repeat(20));

            // But not addressable by name, because FILLER is not a referable COBOL item.
            assertThat(DalyTranRecord.LAYOUT.hasSpan("FILLER")).isFalse();
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> DalyTranRecord.LAYOUT.span("FILLER"));
        }

        @Test
        @DisplayName("DALYTRAN-AMT occupies p+s = 11 bytes, the sign being overpunched not stored")
        void amountOccupiesElevenBytes() {
            assertThat(DalyTranRecord.DALYTRAN_AMT_LENGTH).isEqualTo(11);
            assertThat(DalyTranRecord.DALYTRAN_AMT_INTEGER_DIGITS
                    + DalyTranRecord.DALYTRAN_AMT_SCALE).isEqualTo(11);
            assertThat(DalyTranRecord.DALYTRAN_AMT.length()).isEqualTo(11);
        }

        @Test
        @DisplayName("the monetary scale comes from CobolDecimal, so the policy lives in one place")
        void monetaryScaleIsCentralised() {
            assertThat(DalyTranRecord.DALYTRAN_AMT_SCALE).isEqualTo(CobolDecimal.MONETARY_SCALE)
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("the ORIG-DT slice is the first ten bytes of DALYTRAN-ORIG-TS, per CBTRN02C:414")
        void origDateSliceSharesTheTimestampsOffset() {
            assertThat(DalyTranRecord.DALYTRAN_ORIG_DT_OFFSET)
                    .isEqualTo(DalyTranRecord.DALYTRAN_ORIG_TS_OFFSET);
            assertThat(DalyTranRecord.DALYTRAN_ORIG_DT_LENGTH).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("an initialised area")
    class InitialisedArea {

        @Test
        @DisplayName("is 350 bytes: text blank, unsigned numerics zero-filled, the amount a signed zero")
        void initialisesEverySpan() {
            DalyTranRecord record = new DalyTranRecord(ASCII);
            assertThat(record.encode(ASCII)).hasSize(350);
            assertThat(record.dalytranId()).isEqualTo(" ".repeat(16));
            assertThat(record.dalytranCatCdImage()).isEqualTo("0000");
            assertThat(record.dalytranMerchantIdImage()).isEqualTo("000000000");
            assertThat(record.dalytranAmt()).isEqualByComparingTo(CobolDecimal.monetaryZero());
            assertThat(record.hasZeroDalytranAmt()).isTrue();
            assertThat(record.filler()).isEqualTo(" ".repeat(20));
            assertThat(record.charset()).isEqualTo(ASCII);
        }

        @Test
        @DisplayName("initialises identically under EBCDIC, using that code page's own pad bytes")
        void initialisesUnderEbcdicToo() {
            DalyTranRecord record = new DalyTranRecord(EBCDIC);
            assertThat(record.encode(EBCDIC)).hasSize(350);
            assertThat(record.dalytranProcTs()).isEqualTo(" ".repeat(26));
            assertThat(record.dalytranCatCd()).isZero();
            assertThat(record.charset()).isEqualTo(EBCDIC);
        }

        @Test
        @DisplayName("a charset is never assumed - there is no platform default here")
        void aCharsetIsAlwaysRequired() {
            assertThatNullPointerException().isThrownBy(() -> new DalyTranRecord(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> DalyTranRecord.decode(ROW_1.getBytes(ASCII), null));
            assertThatNullPointerException().isThrownBy(() -> DalyTranRecord.decode(ROW_1, null));
        }
    }

    @Nested
    @DisplayName("decoding a fixture row")
    class FixtureDecode {

        @Test
        @DisplayName("both embedded rows are exactly 350 characters")
        void embeddedRowsAreWellFormed() {
            assertThat(ROW_1).hasSize(350);
            assertThat(ROW_2).hasSize(350);
        }

        @Test
        @DisplayName("row 1 decodes field for field through the copybook offsets")
        void rowOneDecodesFieldForField() {
            DalyTranRecord record = DalyTranRecord.decode(ROW_1, ASCII);
            assertThat(record.dalytranId()).isEqualTo("0000000000683580");
            assertThat(record.dalytranTypeCd()).isEqualTo("01");
            assertThat(record.dalytranCatCd()).isEqualTo(1);
            assertThat(record.dalytranCatCdImage()).isEqualTo("0001");
            assertThat(record.dalytranSource()).isEqualTo("POS TERM  ");
            assertThat(record.dalytranDesc())
                    .isEqualTo("Purchase at Abshire-Lowe" + " ".repeat(76));
            assertThat(record.dalytranAmtImage()).isEqualTo("0000005047G");
            assertThat(record.dalytranAmt()).isEqualByComparingTo(new BigDecimal("504.77"));
            assertThat(record.dalytranMerchantId()).isEqualTo(800000000);
            assertThat(record.dalytranMerchantIdImage()).isEqualTo("800000000");
            assertThat(record.dalytranMerchantName()).isEqualTo("Abshire-Lowe" + " ".repeat(38));
            assertThat(record.dalytranMerchantCity()).isEqualTo("North Enoshaven" + " ".repeat(35));
            assertThat(record.dalytranMerchantZip()).isEqualTo("72112     ");
            assertThat(record.dalytranCardNum()).isEqualTo("4859452612877065");
            assertThat(record.dalytranOrigTs()).isEqualTo("2022-06-10 19:27:53.000000");
            assertThat(record.dalytranProcTs()).isEqualTo(" ".repeat(26));
            assertThat(record.filler()).isEqualTo(" ".repeat(20));
        }

        @Test
        @DisplayName("row 2 decodes a negative amount from its '}' overpunch, and it is not zero")
        void rowTwoDecodesANegativeAmount() {
            DalyTranRecord record = DalyTranRecord.decode(ROW_2, ASCII);
            assertThat(record.dalytranAmtImage()).isEqualTo("0000009190}");
            assertThat(record.dalytranAmt()).isEqualByComparingTo(new BigDecimal("-919.00"));
            assertThat(record.dalytranAmt().signum()).isNegative();
            assertThat(record.hasZeroDalytranAmt()).isFalse();
            assertThat(record.dalytranSource()).isEqualTo("OPERATOR  ");
            assertThat(record.dalytranTypeCd()).isEqualTo("03");
        }

        @Test
        @DisplayName("the amount is decoded at scale exactly 2, never as a binary float")
        void amountIsDecodedAtScaleTwo() {
            assertThat(DalyTranRecord.decode(ROW_1, ASCII).dalytranAmt().scale()).isEqualTo(2);
            assertThat(DalyTranRecord.decode(ROW_2, ASCII).dalytranAmt().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("the (1:10) slice of DALYTRAN-ORIG-TS is the date CBTRN02C:414 compares")
        void origDateSliceIsTheFirstTenCharacters() {
            DalyTranRecord record = DalyTranRecord.decode(ROW_1, ASCII);
            assertThat(record.dalytranOrigDt()).isEqualTo("2022-06-10").hasSize(10);
            assertThat(record.dalytranOrigDt()).isEqualTo(record.dalytranOrigTs().substring(0, 10));
        }

        @Test
        @DisplayName("displayImage reproduces DISPLAY DALYTRAN-RECORD exactly, per CBTRN01C:168")
        void displayImageReproducesTheWholeGroupItem() {
            assertThat(DalyTranRecord.decode(ROW_1, ASCII).displayImage())
                    .hasSize(350)
                    .isEqualTo(ROW_1);
        }

        @Test
        @DisplayName("a row of the wrong width is rejected rather than decoded at shifted offsets")
        void aShortRowIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> DalyTranRecord.decode(new byte[349], ASCII));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> DalyTranRecord.decode(ROW_1.substring(1), ASCII));
            assertThatNullPointerException()
                    .isThrownBy(() -> DalyTranRecord.decode((byte[]) null, ASCII));
            assertThatNullPointerException()
                    .isThrownBy(() -> DalyTranRecord.decode((String) null, ASCII));
        }
    }

    @Nested
    @DisplayName("verbatim image access - the rejects copy and the SYSOUT line")
    class VerbatimImageAccess {

        @Test
        @DisplayName("rawImage is the stored 350 bytes, which is what CBTRN02C:447 copies")
        void rawImageIsByteVerbatim() {
            byte[] stored = ROW_2.getBytes(ASCII);
            DalyTranRecord record = DalyTranRecord.decode(stored, ASCII);
            assertThat(record.rawImage()).hasSize(350).isEqualTo(stored);
            assertThat(record.encode(ASCII)).isEqualTo(stored);
        }

        @Test
        @DisplayName("rawImage hands out a copy, so a caller cannot reach the backing area")
        void rawImageIsDefensivelyCopied() {
            DalyTranRecord record = DalyTranRecord.decode(ROW_1, ASCII);
            byte[] first = record.rawImage();
            first[0] = (byte) '9';
            assertThat(record.rawImage()[0]).isEqualTo((byte) '0');
            assertThat(record.dalytranId()).isEqualTo("0000000000683580");
        }

        @Test
        @DisplayName("copy duplicates the bytes rather than rebuilding from decoded values")
        void copyIsByteVerbatim() {
            DalyTranRecord original = DalyTranRecord.decode(ROW_2, ASCII);
            DalyTranRecord duplicate = original.copy();
            assertThat(duplicate.rawImage()).isEqualTo(original.rawImage());
            assertThat(duplicate).isEqualTo(original);
            duplicate.moveDalytranSource("CHANGED   ");
            assertThat(original.dalytranSource()).isEqualTo("OPERATOR  ");
            assertThat(duplicate).isNotEqualTo(original);
        }

        @Test
        @DisplayName("transcoding to another code page preserves the sign overpunch character")
        void encodingToAnotherCodePagePreservesTheSignByte() {
            DalyTranRecord record = DalyTranRecord.decode(ROW_2, ASCII);
            byte[] transcoded = record.encode(EBCDIC);
            assertThat(transcoded).hasSize(350).isNotEqualTo(record.rawImage());
            DalyTranRecord roundTripped = DalyTranRecord.decode(transcoded, EBCDIC);
            assertThat(roundTripped.dalytranAmtImage()).isEqualTo("0000009190}");
            assertThat(roundTripped.dalytranAmt()).isEqualByComparingTo(new BigDecimal("-919.00"));
            assertThat(roundTripped.dalytranCardNum()).isEqualTo("0927987108636232");
            assertThat(roundTripped.displayImage()).isEqualTo(ROW_2);
        }

        @Test
        @DisplayName("encode requires an explicit charset")
        void encodeRequiresACharset() {
            DalyTranRecord record = DalyTranRecord.decode(ROW_1, ASCII);
            assertThatNullPointerException().isThrownBy(() -> record.encode(null));
        }

        @Test
        @DisplayName("a raw span is readable for every declared field, at its full stored width")
        void rawSpanReadsEveryDeclaredField() {
            DalyTranRecord record = DalyTranRecord.decode(ROW_1, ASCII);
            for (FieldSpan span : DalyTranRecord.LAYOUT.storageSpans()) {
                assertThat(record.rawSpan(span)).as(span.name()).hasSize(span.length());
                assertThat(record.rawSpanBytes(span)).as(span.name()).hasSize(span.length());
            }
            assertThat(record.rawSpan(DalyTranRecord.DALYTRAN_AMT)).isEqualTo("0000005047G");
        }

        @Test
        @DisplayName("a span descriptor from another copybook is rejected, not silently honoured")
        void aForeignSpanIsRejected() {
            DalyTranRecord record = DalyTranRecord.decode(ROW_1, ASCII);
            FieldSpan foreign = FieldSpan.alphanumeric("TRAN-ID", 0, 16);
            assertThatIllegalArgumentException().isThrownBy(() -> record.rawSpan(foreign));
            assertThatIllegalArgumentException().isThrownBy(() -> record.rawSpanBytes(foreign));
            assertThatNullPointerException().isThrownBy(() -> record.rawSpan(null));
            assertThatNullPointerException().isThrownBy(() -> record.rawSpanBytes(null));
        }
    }

    @Nested
    @DisplayName("the negative-zero re-encode hazard")
    class NegativeZeroHazard {

        @Test
        @DisplayName("a '}' carrying a real amount survives even a numeric round-trip")
        void anOrdinaryNegativeSurvivesANumericRoundTrip() {
            DalyTranRecord stored = DalyTranRecord.decode(ROW_2, ASCII);
            DalyTranRecord rebuilt = new DalyTranRecord(ASCII);
            rebuilt.moveDalytranAmt(stored.dalytranAmt());
            assertThat(rebuilt.dalytranAmtImage()).isEqualTo("0000009190}");
        }

        @Test
        @DisplayName("a whole-value negative zero survives the raw path and only the raw path")
        void aWholeValueNegativeZeroNeedsTheRawPath() {
            DalyTranRecord record = new DalyTranRecord(ASCII);
            record.writeDalytranAmtImage("0000000000}");

            // The stored byte is preserved by every path that moves bytes.
            assertThat(record.dalytranAmtImage()).isEqualTo("0000000000}");
            assertThat(record.copy().dalytranAmtImage()).isEqualTo("0000000000}");
            assertThat(record.rawImage()[142]).isEqualTo((byte) '}');
            assertThat(record.encode(ASCII)[142]).isEqualTo((byte) '}');
            assertThat(record.displayImage().charAt(142)).isEqualTo('}');

            // Its value is zero, because BigDecimal has no signed zero...
            assertThat(record.dalytranAmt()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(record.hasZeroDalytranAmt()).isTrue();

            // ...so a numeric round-trip normalises the sign byte. This is the documented hazard.
            DalyTranRecord viaValue = new DalyTranRecord(ASCII);
            viaValue.moveDalytranAmt(record.dalytranAmt());
            assertThat(viaValue.dalytranAmtImage()).isEqualTo("0000000000{");
            assertThat(viaValue).isNotEqualTo(record);
        }

        @Test
        @DisplayName("an amount image is stored at exactly its declared width, never padded")
        void anAmountImageMustBeExactlyEleven() {
            DalyTranRecord record = new DalyTranRecord(ASCII);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> record.writeDalytranAmtImage("0000000000"));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> record.writeDalytranAmtImage("0000000000{0"));
            assertThatNullPointerException()
                    .isThrownBy(() -> record.writeDalytranAmtImage(null));
        }

        @ParameterizedTest(name = "{0} decodes to {1}")
        @DisplayName("every zoned overpunch class decodes with the right sign and magnitude")
        @CsvSource({
            "0000003250{,325.00",
            "0000005047G,504.77",
            "0000009190},-919.00",
            "0000008351J,-835.11",
            "0000000000{,0.00",
            "00000000001,0.01"})
        void everyOverpunchClassDecodes(String image, String expected) {
            DalyTranRecord record = new DalyTranRecord(ASCII);
            record.writeDalytranAmtImage(image);
            assertThat(record.dalytranAmt()).isEqualByComparingTo(new BigDecimal(expected));
            assertThat(record.dalytranAmtImage()).isEqualTo(image);
        }
    }

    @Nested
    @DisplayName("COBOL MOVE semantics on the write path")
    class MoveSemantics {

        @Test
        @DisplayName("a PIC X receiver is space-padded and truncated on the RIGHT")
        void picXPadsAndTruncatesOnTheRight() {
            DalyTranRecord record = new DalyTranRecord(ASCII);
            record.moveDalytranSource("POS TERM");
            assertThat(record.dalytranSource()).isEqualTo("POS TERM  ");
            record.moveDalytranSource("ABCDEFGHIJKL");
            assertThat(record.dalytranSource()).isEqualTo("ABCDEFGHIJ");
        }

        @Test
        @DisplayName("a PIC 9 receiver is zero-filled on the LEFT - '05' stores 0005, never 0500")
        void picNinePadsOnTheLeft() {
            DalyTranRecord record = new DalyTranRecord(ASCII);
            record.moveDalytranCatCd("05");
            assertThat(record.dalytranCatCdImage()).isEqualTo("0005");
            record.moveDalytranCatCd(1);
            assertThat(record.dalytranCatCd()).isEqualTo(1);
            record.moveDalytranMerchantId(0L);
            assertThat(record.dalytranMerchantIdImage()).isEqualTo("000000000");
            record.moveDalytranMerchantId("800000000");
            assertThat(record.dalytranMerchantId()).isEqualTo(800000000);
        }

        @Test
        @DisplayName("every character field has a MOVE path, and together they rebuild row 1 exactly")
        void everyCharacterFieldHasAMovePath() {
            DalyTranRecord record = new DalyTranRecord(ASCII);
            record.moveDalytranId("0000000000683580");
            record.moveDalytranTypeCd("01");
            record.moveDalytranCatCd("0001");
            record.moveDalytranSource("POS TERM  ");
            record.moveDalytranDesc("Purchase at Abshire-Lowe");
            record.writeDalytranAmtImage("0000005047G");
            record.moveDalytranMerchantId("800000000");
            record.moveDalytranMerchantName("Abshire-Lowe");
            record.moveDalytranMerchantCity("North Enoshaven");
            record.moveDalytranMerchantZip("72112");
            record.moveDalytranCardNum("4859452612877065");
            record.moveDalytranOrigTs("2022-06-10 19:27:53.000000");
            record.moveDalytranProcTs("");
            assertThat(record.displayImage()).isEqualTo(ROW_1);
            assertThat(record).isEqualTo(DalyTranRecord.decode(ROW_1, ASCII));
        }

        @Test
        @DisplayName("the amount truncates excess fraction digits DOWN, because ROUNDED is never used")
        void amountTruncatesRatherThanRounds() {
            DalyTranRecord record = new DalyTranRecord(ASCII);
            record.moveDalytranAmt(new BigDecimal("1.239"));
            assertThat(record.dalytranAmt()).isEqualByComparingTo(new BigDecimal("1.23"));
            record.moveDalytranAmt(new BigDecimal("1.999"));
            assertThat(record.dalytranAmt()).isEqualByComparingTo(new BigDecimal("1.99"));
            record.moveDalytranAmt(new BigDecimal("-1.239"));
            assertThat(record.dalytranAmt()).isEqualByComparingTo(new BigDecimal("-1.23"));
            assertThatNullPointerException().isThrownBy(() -> record.moveDalytranAmt(null));
        }
    }

    @Nested
    @DisplayName("identity and diagnostics")
    class IdentityAndDiagnostics {

        @Test
        @DisplayName("equality is by bytes and charset, so equal values are not equal records")
        void equalityIsByBytes() {
            DalyTranRecord one = DalyTranRecord.decode(ROW_1, ASCII);
            DalyTranRecord same = DalyTranRecord.decode(ROW_1, ASCII);
            assertThat(one).isEqualTo(one).isEqualTo(same).hasSameHashCodeAs(same);
            assertThat(one).isNotEqualTo(DalyTranRecord.decode(ROW_2, ASCII));
            assertThat(one).isNotEqualTo(null).isNotEqualTo("not a record");
            assertThat(one).isNotEqualTo(DalyTranRecord.decode(ROW_1, EBCDIC));

            DalyTranRecord positiveZero = new DalyTranRecord(ASCII);
            positiveZero.writeDalytranAmtImage("0000000000{");
            DalyTranRecord negativeZero = new DalyTranRecord(ASCII);
            negativeZero.writeDalytranAmtImage("0000000000}");
            assertThat(positiveZero.dalytranAmt())
                    .isEqualByComparingTo(negativeZero.dalytranAmt());
            assertThat(positiveZero).isNotEqualTo(negativeZero);
        }

        @Test
        @DisplayName("the rendering names every field, masks the PAN, and stays on one line")
        void toStringNamesEveryFieldAndMasksThePan() {
            String rendered = DalyTranRecord.decode(ROW_1, ASCII).toString();
            assertThat(rendered)
                    .contains("DALYTRAN-ID='0000000000683580'")
                    .contains("DALYTRAN-TYPE-CD='01'")
                    .contains("DALYTRAN-CAT-CD='0001'")
                    .contains("DALYTRAN-SOURCE='POS TERM  '")
                    .contains("DALYTRAN-AMT='0000005047G'=504.77")
                    .contains("DALYTRAN-MERCHANT-ID='800000000'")
                    .contains("DALYTRAN-MERCHANT-CITY='North Enoshaven")
                    .contains("DALYTRAN-ORIG-TS='2022-06-10 19:27:53.000000'")
                    .contains("FILLER.length=20");
            assertThat(rendered.lines()).hasSize(1);
            // Masked in the Java-only diagnostic, at the field's full stored width.
            assertThat(rendered).contains("DALYTRAN-CARD-NUM='************7065'")
                    .doesNotContain("4859452612877065");
        }

        @Test
        @DisplayName("the byte-exact paths still carry every digit, so parity is untouched")
        void theByteExactPathsAreNeverMasked() {
            DalyTranRecord record = DalyTranRecord.decode(ROW_1, ASCII);
            assertThat(record.dalytranCardNum()).isEqualTo("4859452612877065");
            assertThat(record.rawSpan(DalyTranRecord.DALYTRAN_CARD_NUM))
                    .isEqualTo("4859452612877065");
            assertThat(record.displayImage()).contains("4859452612877065");
            assertThat(new String(record.rawImage(), ASCII)).contains("4859452612877065");
            assertThat(new String(record.encode(ASCII), ASCII)).contains("4859452612877065");
        }
    }
}
