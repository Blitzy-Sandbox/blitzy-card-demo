package com.vsergeychik.carddemo.transaction.model;

import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.ZonedSign;
import com.vsergeychik.carddemo.transaction.model.TranCatBalRecord.TranCatKey;
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
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Verifies {@link TranCatBalRecord} - the single Java type for {@code app/cpy/CVTRA01Y.cpy}, whose own
 * header comment reads "Data-structure for transaction category balance (RECLN = 50)".
 */
@DisplayName("TranCatBalRecord - CVTRA01Y TRAN-CAT-BAL-RECORD, 50 bytes, 17-byte composite key")
class TranCatBalRecordTest {
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    private static final Charset EBCDIC = Charset.forName("IBM037");

    private static final String FIXTURE_TYPE_CD = "01";

    private static final String FIXTURE_CAT_CD_IMAGE = "0001";

    private static final int FIXTURE_CAT_CD = 1;

    private static final String FIXTURE_BALANCE_IMAGE = "0000000000{";

    private static final String FIXTURE_FILLER_IMAGE = "0".repeat(22);

    private static final String ROW_1 =
            "00000000001"
            + FIXTURE_TYPE_CD
            + FIXTURE_CAT_CD_IMAGE
            + FIXTURE_BALANCE_IMAGE
            + FIXTURE_FILLER_IMAGE;

    private static final String ROW_4 =
            "00000000004"
            + FIXTURE_TYPE_CD
            + FIXTURE_CAT_CD_IMAGE
            + FIXTURE_BALANCE_IMAGE
            + FIXTURE_FILLER_IMAGE;

    private static final String ROW_50 =
            "00000000050"
            + FIXTURE_TYPE_CD
            + FIXTURE_CAT_CD_IMAGE
            + FIXTURE_BALANCE_IMAGE
            + FIXTURE_FILLER_IMAGE;

    private static final String ROW_WITH_STALE_BALANCE =
            "00000000009"
            + "AB"
            + "0007"
            + "0000001234E"
            + FIXTURE_FILLER_IMAGE;

    private static TranCatBalRecord decoded(String row) {
        return TranCatBalRecord.decode(row, ASCII);
    }

    private static String recordImage(String acctId, String typeCd, String catCd, String balance,
                                     String filler) {
        assertThat(acctId).as("TRANCAT-ACCT-ID PIC 9(11)").hasSize(11);
        assertThat(typeCd).as("TRANCAT-TYPE-CD PIC X(02)").hasSize(2);
        assertThat(catCd).as("TRANCAT-CD PIC 9(04)").hasSize(4);
        assertThat(balance).as("TRAN-CAT-BAL PIC S9(09)V99 is 9 + 2 = 11 bytes").hasSize(11);
        assertThat(filler).as("FILLER PIC X(22)").hasSize(22);
        return acctId + typeCd + catCd + balance + filler;
    }

    @Nested
    @DisplayName("Declared geometry - the copybook's own arithmetic (gates G19 and G21)")
    class DeclaredGeometry {
        @Test
        @DisplayName("RECLN = 50, and the five declared spans account for every one of those bytes")
        void theRecordIsFiftyBytesAndEveryByteIsAccountedFor() {
            assertThat(TranCatBalRecord.TRANCAT_ACCT_ID_LENGTH
                    + TranCatBalRecord.TRANCAT_TYPE_CD_LENGTH
                    + TranCatBalRecord.TRANCAT_CD_LENGTH
                    + TranCatBalRecord.TRAN_CAT_BAL_LENGTH
                    + TranCatBalRecord.FILLER_LENGTH)
                    .as("CVTRA01Y: 11 + 2 + 4 + 11 + 22")
                    .isEqualTo(50);
            assertThat(TranCatBalRecord.RECORD_LENGTH).isEqualTo(50);
            assertThat(TranCatBalRecord.LAYOUT.recordLength()).isEqualTo(50);
            assertThat(TranCatBalRecord.LAYOUT.spans()).hasSize(5);
            assertThat(TranCatBalRecord.verifyGeometry()).isEqualTo(50);
        }

        @Test
        @DisplayName("TRAN-CAT-KEY is 17 bytes over three items, and TRAN-CAT-BAL begins where it ends")
        void theKeyIsSeventeenBytesOverThreeItems() {
            assertThat(TranCatBalRecord.TRANCAT_ACCT_ID_LENGTH
                    + TranCatBalRecord.TRANCAT_TYPE_CD_LENGTH
                    + TranCatBalRecord.TRANCAT_CD_LENGTH)
                    .as("CVTRA01Y TRAN-CAT-KEY: 11 + 2 + 4")
                    .isEqualTo(17);
            assertThat(TranCatBalRecord.TRAN_CAT_KEY_OFFSET).isZero();
            assertThat(TranCatBalRecord.TRAN_CAT_KEY_LENGTH).isEqualTo(17);
            assertThat(TranCatBalRecord.TRAN_CAT_KEY_LAYOUT.recordLength()).isEqualTo(17);
            assertThat(TranCatBalRecord.TRAN_CAT_KEY_LAYOUT.spans()).hasSize(3);
            assertThat(TranCatBalRecord.TRAN_CAT_BAL_OFFSET)
                    .isEqualTo(TranCatBalRecord.TRAN_CAT_KEY_OFFSET
                            + TranCatBalRecord.TRAN_CAT_KEY_LENGTH);
        }

        @Test
        @DisplayName("every offset is the running sum of the lengths that precede it")
        void everyOffsetIsTheRunningSumOfThePrecedingLengths() {
            assertThat(TranCatBalRecord.TRANCAT_ACCT_ID_OFFSET).as("first item").isZero();
            assertThat(TranCatBalRecord.TRANCAT_TYPE_CD_OFFSET).as("0 + 11").isEqualTo(11);
            assertThat(TranCatBalRecord.TRANCAT_CD_OFFSET).as("11 + 2").isEqualTo(13);
            assertThat(TranCatBalRecord.TRAN_CAT_BAL_OFFSET).as("13 + 4").isEqualTo(17);
            assertThat(TranCatBalRecord.FILLER_OFFSET).as("17 + 11").isEqualTo(28);
            assertThat(TranCatBalRecord.FILLER_SPAN.endOffsetExclusive())
                    .as("28 + 22")
                    .isEqualTo(TranCatBalRecord.RECORD_LENGTH);
            assertThat(TranCatBalRecord.TRANCAT_ACCT_ID_SPAN.offset()).isZero();
            assertThat(TranCatBalRecord.TRANCAT_ACCT_ID_SPAN.length()).isEqualTo(11);
            assertThat(TranCatBalRecord.TRANCAT_TYPE_CD_SPAN.offset()).isEqualTo(11);
            assertThat(TranCatBalRecord.TRANCAT_TYPE_CD_SPAN.length()).isEqualTo(2);
            assertThat(TranCatBalRecord.TRANCAT_CD_SPAN.offset()).isEqualTo(13);
            assertThat(TranCatBalRecord.TRANCAT_CD_SPAN.length()).isEqualTo(4);
            assertThat(TranCatBalRecord.TRAN_CAT_BAL_SPAN.offset()).isEqualTo(17);
            assertThat(TranCatBalRecord.FILLER_SPAN.offset()).isEqualTo(28);
            assertThat(TranCatBalRecord.FILLER_SPAN.length()).isEqualTo(22);
        }

        @Test
        @DisplayName("the five spans tile the record contiguously, with no gap and no overlap")
        void theFiveSpansTileTheRecordContiguously() {
            List<FieldSpan> spans = TranCatBalRecord.LAYOUT.spans();
            assertThat(spans).hasSize(5);
            assertThat(spans.stream().map(FieldSpan::name).toList()).containsExactly(
                    TranCatBalRecord.TRANCAT_ACCT_ID_NAME,
                    TranCatBalRecord.TRANCAT_TYPE_CD_NAME,
                    TranCatBalRecord.TRANCAT_CD_NAME,
                    TranCatBalRecord.TRAN_CAT_BAL_NAME,
                    TranCatBalRecord.FILLER_SPAN.name());

            int expectedOffset = 0;
            for (FieldSpan span : spans) {
                assertThat(span.offset())
                        .as("span %s begins where the previous one ended", span.name())
                        .isEqualTo(expectedOffset);
                expectedOffset += span.length();
            }
            assertThat(expectedOffset)
                    .as("11 + 2 + 4 + 11 + 22")
                    .isEqualTo(TranCatBalRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("TRAN-CAT-BAL occupies 9 + 2 = 11 bytes, because nothing in app/cpy is packed")
        void theBalanceSpanIsElevenBytesBecauseNothingIsPacked() {
            assertThat(TranCatBalRecord.TRAN_CAT_BAL_INTEGER_DIGITS).isEqualTo(9);
            assertThat(TranCatBalRecord.TRAN_CAT_BAL_SCALE).isEqualTo(2);
            assertThat(TranCatBalRecord.TRAN_CAT_BAL_INTEGER_DIGITS
                    + TranCatBalRecord.TRAN_CAT_BAL_SCALE)
                    .as("PIC S9(09)V99 is 9 + 2 bytes, with no byte reserved for the sign")
                    .isEqualTo(TranCatBalRecord.TRAN_CAT_BAL_LENGTH);
            assertThat(TranCatBalRecord.TRAN_CAT_BAL_LENGTH).isEqualTo(11);
            assertThat(TranCatBalRecord.TRAN_CAT_BAL_SPAN.length()).isEqualTo(11);
            assertThat(TranCatBalRecord.TRAN_CAT_BAL_LENGTH)
                    .as("zoned DISPLAY, not COMP-3")
                    .isNotEqualTo(6);
            assertThat(TranCatBalRecord.TRAN_CAT_BAL_SCALE)
                    .isEqualTo(CobolDecimal.MONETARY_SCALE);
        }

        @Test
        @DisplayName("the trailing FILLER X(22) is a declared span, not an inferred gap (G21)")
        void theFillerIsADeclaredSpanNotAnInferredGap() {
            assertThat(TranCatBalRecord.FILLER_SPAN.kind()).isEqualTo(PictureKind.FILLER);
            assertThat(TranCatBalRecord.FILLER_SPAN.kind().filler()).isTrue();
            assertThat(TranCatBalRecord.LAYOUT.spans()).contains(TranCatBalRecord.FILLER_SPAN);
            assertThat(TranCatBalRecord.RECORD_LENGTH - TranCatBalRecord.FILLER_LENGTH)
                    .as("50 - 22 leaves the four named items")
                    .isEqualTo(28);
        }

        @Test
        @DisplayName("each span's kind matches its PICTURE category, which fixes its pad and justification")
        void eachSpanKindMatchesItsPictureCategory() {
            assertThat(TranCatBalRecord.TRANCAT_ACCT_ID_SPAN.kind())
                    .isEqualTo(PictureKind.UNSIGNED_NUMERIC);
            assertThat(TranCatBalRecord.TRANCAT_ACCT_ID_SPAN.kind().numericDisplay()).isTrue();
            assertThat(TranCatBalRecord.TRANCAT_CD_SPAN.kind())
                    .isEqualTo(PictureKind.UNSIGNED_NUMERIC);
            assertThat(TranCatBalRecord.TRANCAT_CD_SPAN.kind().numericDisplay()).isTrue();
            assertThat(TranCatBalRecord.TRANCAT_TYPE_CD_SPAN.kind())
                    .isEqualTo(PictureKind.ALPHANUMERIC);
            assertThat(TranCatBalRecord.TRANCAT_TYPE_CD_SPAN.kind().leftJustified()).isTrue();
            assertThat(TranCatBalRecord.TRANCAT_TYPE_CD_SPAN.kind().numericDisplay()).isFalse();
            assertThat(TranCatBalRecord.TRAN_CAT_BAL_SPAN.kind())
                    .isEqualTo(PictureKind.SIGNED_SCALED);
            assertThat(TranCatBalRecord.TRAN_CAT_BAL_SPAN.kind().numericDisplay()).isTrue();
            assertThat(TranCatBalRecord.LAYOUT.redefinitions()).isEmpty();
            assertThat(TranCatBalRecord.LAYOUT.storageSpans()).hasSize(5);
        }

        @Test
        @DisplayName("the copybook, record and item names are carried verbatim for the parity differ")
        void theCopybookVocabularyIsVerbatim() {
            assertThat(TranCatBalRecord.COPYBOOK).isEqualTo("CVTRA01Y");
            assertThat(TranCatBalRecord.RECORD_NAME).isEqualTo("TRAN-CAT-BAL-RECORD");
            assertThat(TranCatBalRecord.DD_NAME).isEqualTo("TCATBALF");
            assertThat(TranCatBalRecord.TRAN_CAT_KEY_NAME).isEqualTo("TRAN-CAT-KEY");
            assertThat(TranCatBalRecord.TRANCAT_ACCT_ID_NAME).isEqualTo("TRANCAT-ACCT-ID");
            assertThat(TranCatBalRecord.TRANCAT_TYPE_CD_NAME).isEqualTo("TRANCAT-TYPE-CD");
            assertThat(TranCatBalRecord.TRANCAT_CD_NAME).isEqualTo("TRANCAT-CD");
            assertThat(TranCatBalRecord.TRAN_CAT_BAL_NAME).isEqualTo("TRAN-CAT-BAL");
            assertThat(TranCatBalRecord.TRANCAT_ACCT_ID_SPAN.name())
                    .isEqualTo(TranCatBalRecord.TRANCAT_ACCT_ID_NAME);
            assertThat(TranCatBalRecord.LAYOUT.hasSpan(TranCatBalRecord.TRAN_CAT_BAL_NAME)).isTrue();
            assertThat(TranCatBalRecord.LAYOUT.span(TranCatBalRecord.TRAN_CAT_BAL_NAME))
                    .isEqualTo(TranCatBalRecord.TRAN_CAT_BAL_SPAN);
        }

        @Test
        @DisplayName("verifyRecordLength accepts agreement and rejects a shortfall and an excess alike")
        void verifyRecordLengthAcceptsAgreementAndRejectsDisagreement() {
            assertThat(TranCatBalRecord.verifyRecordLength(50, 50)).isEqualTo(50);
            assertThatIllegalStateException()
                    .isThrownBy(() -> TranCatBalRecord.verifyRecordLength(50, 49))
                    .withMessageContaining("CVTRA01Y")
                    .withMessageContaining("FILLER");
            assertThatIllegalStateException()
                    .isThrownBy(() -> TranCatBalRecord.verifyRecordLength(50, 51))
                    .withMessageContaining("overpunched");
        }

        @Test
        @DisplayName("verifyKeyGeometry accepts 17 and rejects the 16 that CVTRA02Y would supply")
        void verifyKeyGeometryAcceptsSeventeenAndRejectsSixteen() {
            assertThat(TranCatBalRecord.verifyKeyGeometry(17, 17)).isEqualTo(17);
            assertThatIllegalStateException()
                    .isThrownBy(() -> TranCatBalRecord.verifyKeyGeometry(16, 17))
                    .withMessageContaining("CVTRA02Y");
        }
    }

    @Nested
    @DisplayName("Collision defence - two layouts that are invisible to a width check (practice B4)")
    class CollisionDefence {
        @Test
        @DisplayName("Collision 1: CVTRA02Y also totals 50, so only the field geometry separates them")
        void theCvtra02yLayoutIsRejectedGeometryByGeometry() {
            int cvtra02yTotal = 10 + 2 + 4 + 6 + 28;
            assertThat(cvtra02yTotal)
                    .as("both copybooks declare RECLN = 50, so the total discriminates nothing")
                    .isEqualTo(TranCatBalRecord.RECORD_LENGTH);

            assertThat(TranCatBalRecord.TRAN_CAT_KEY_LENGTH)
                    .as("TRAN-CAT-KEY is 17, never DIS-GROUP-KEY's 16")
                    .isEqualTo(17)
                    .isNotEqualTo(16);
            assertThat(TranCatBalRecord.TRAN_CAT_BAL_OFFSET)
                    .as("the signed amount starts at 17, never at DIS-INT-RATE's 16")
                    .isEqualTo(17)
                    .isNotEqualTo(16);
            assertThat(TranCatBalRecord.TRAN_CAT_BAL_LENGTH)
                    .as("S9(09)V99 is 11 bytes, never S9(04)V99's 6")
                    .isEqualTo(11)
                    .isNotEqualTo(6);
            assertThat(TranCatBalRecord.FILLER_OFFSET)
                    .as("FILLER starts at 28, never at CVTRA02Y's 22")
                    .isEqualTo(28)
                    .isNotEqualTo(22);
            assertThat(TranCatBalRecord.FILLER_LENGTH)
                    .as("FILLER is 22 bytes, never CVTRA02Y's 28 - the pair is transposed, which is "
                            + "exactly how a transcription slip survives review")
                    .isEqualTo(22)
                    .isNotEqualTo(28);
            assertThat(TranCatBalRecord.TRANCAT_ACCT_ID_LENGTH)
                    .as("TRANCAT-ACCT-ID is 11 bytes, never DIS-ACCT-GROUP-ID's 10")
                    .isEqualTo(11)
                    .isNotEqualTo(10);
            assertThat(TranCatBalRecord.TRANCAT_ACCT_ID_SPAN.kind().numericDisplay())
                    .as("TRANCAT-ACCT-ID PIC 9(11) is numeric; DIS-ACCT-GROUP-ID PIC X(10) is not")
                    .isTrue();

            TranCatBalRecord record = decoded(ROW_1);
            assertThat(record.tranCatBalImage())
                    .as("this record's signed span is 11 characters wide")
                    .hasSize(11);
            assertThat(record.tranCatKeyImage())
                    .as("and its key is 17, so a 16-byte read would take a balance digit into the key")
                    .hasSize(17);
        }

        @Test
        @DisplayName("Collision 2: CVTRA04Y names a 6-byte group TRAN-CAT-KEY, and both names stay")
        void theCvtra04yGroupOfTheSameNameIsSixBytesNotSeventeen() {
            int cvtra04yKeyLength = 2 + 4;
            assertThat(cvtra04yKeyLength).as("CVTRA04Y TRAN-CAT-KEY: 2 + 4").isEqualTo(6);
            assertThat(TranCatBalRecord.TRAN_CAT_KEY_NAME)
                    .as("the name is carried verbatim and is never disambiguated, because the parity "
                            + "differ compares by name and a rename would hide a real difference")
                    .isEqualTo("TRAN-CAT-KEY");
            assertThat(TranCatBalRecord.TRAN_CAT_KEY_LENGTH)
                    .as("this TRAN-CAT-KEY is 17 bytes, never CVTRA04Y's 6")
                    .isEqualTo(17)
                    .isNotEqualTo(cvtra04yKeyLength);
            assertThat(TranCatBalRecord.TRAN_CAT_KEY_LENGTH - cvtra04yKeyLength)
                    .as("17 - 6 is exactly TRANCAT-ACCT-ID PIC 9(11)")
                    .isEqualTo(TranCatBalRecord.TRANCAT_ACCT_ID_LENGTH);
            assertThat(new TranCatKey(1L, "01", 1).image(ASCII)).hasSize(17);
        }
    }

    @Nested
    @DisplayName("TRAN-CAT-KEY as a view over the record's own first 17 bytes (gate G34)")
    class CompositeKey {
        @Test
        @DisplayName("the key image is its three items concatenated, each by its own PICTURE's rule")
        void theKeyImageIsTheConcatenationOfItsThreeItems() {
            String expected = "00000000001"
                    + FIXTURE_TYPE_CD
                    + FIXTURE_CAT_CD_IMAGE;
            assertThat(expected).as("11 + 2 + 4 = 17").hasSize(17);

            assertThat(new TranCatKey(1L, FIXTURE_TYPE_CD, FIXTURE_CAT_CD).image(ASCII))
                    .isEqualTo(expected);
            assertThat(decoded(ROW_1).tranCatKeyImage()).isEqualTo(expected);
            assertThat(new TranCatKey(1L, FIXTURE_TYPE_CD, FIXTURE_CAT_CD).toByteArray(ASCII))
                    .isEqualTo(expected.getBytes(ASCII));
        }

        @Test
        @DisplayName("the key inside a record is literally its bytes 0 through 16, not a copy of a copy")
        void theKeyIsTheRecordsFirstSeventeenBytes() {
            TranCatBalRecord record = decoded(ROW_1);
            byte[] whole = record.encode();
            byte[] keyBytes = record.tranCatKeyBytes();
            assertThat(keyBytes).hasSize(17);
            for (int offset = TranCatBalRecord.TRAN_CAT_KEY_OFFSET;
                    offset < TranCatBalRecord.TRAN_CAT_KEY_LENGTH; offset++) {
                assertThat(keyBytes[offset])
                        .as("byte at 0-based offset %d", offset)
                        .isEqualTo(whole[offset]);
            }
            assertThat(record.tranCatKey().image(ASCII)).isEqualTo(record.tranCatKeyImage());
            assertThat(record.tranCatKey())
                    .isEqualTo(new TranCatKey(1L, FIXTURE_TYPE_CD, FIXTURE_CAT_CD));
        }

        @Test
        @DisplayName("the key stops short of TRAN-CAT-BAL: offset 17 is outside it")
        void theKeyStopsShortOfTheBalance() {
            assertThat(TranCatBalRecord.TRAN_CAT_KEY_OFFSET + TranCatBalRecord.TRAN_CAT_KEY_LENGTH)
                    .isEqualTo(TranCatBalRecord.TRAN_CAT_BAL_OFFSET);

            TranCatBalRecord record = decoded(ROW_1);
            String keyBefore = record.tranCatKeyImage();
            record.tranCatBal(new BigDecimal("4321.99"));
            assertThat(record.tranCatKeyImage())
                    .as("a balance store lies wholly beyond the key")
                    .isEqualTo(keyBefore);
            assertThat(record.tranCatKey())
                    .isEqualTo(new TranCatKey(1L, FIXTURE_TYPE_CD, FIXTURE_CAT_CD));
            assertThat(record.tranCatKeyImage()).doesNotContain(record.tranCatBalImage());
        }

        @Test
        @DisplayName("mutating an item moves the key image with it - the first direction of the view")
        void mutatingAnItemMovesTheKeyImage() {
            TranCatBalRecord record = decoded(ROW_1);
            record.trancatCd(2);
            assertThat(record.tranCatKeyImage())
                    .isEqualTo("00000000001" + FIXTURE_TYPE_CD + "0002");
            record.trancatAcctId(50L);
            assertThat(record.tranCatKeyImage())
                    .isEqualTo("00000000050" + FIXTURE_TYPE_CD + "0002");
            record.trancatTypeCd("AB");
            assertThat(record.tranCatKeyImage()).isEqualTo("00000000050" + "AB" + "0002");
            assertThat(record.tranCatKey()).isEqualTo(new TranCatKey(50L, "AB", 2));
            assertThat(record.tranCatBalImage()).isEqualTo(FIXTURE_BALANCE_IMAGE);
            assertThat(record.fillerImage()).isEqualTo(FIXTURE_FILLER_IMAGE);
        }

        @Test
        @DisplayName("writing a key image moves the three items with it - the second direction")
        void writingAKeyImageMovesTheThreeItems() {
            String keyImage = "00000000042" + "07" + "0009";
            TranCatKey key = TranCatKey.decode(keyImage, ASCII);
            assertThat(key.trancatAcctId()).isEqualTo(42L);
            assertThat(key.trancatTypeCd()).isEqualTo("07");
            assertThat(key.trancatCd()).isEqualTo(9);

            TranCatBalRecord record = decoded(ROW_1).tranCatKey(key);
            assertThat(record.trancatAcctId()).isEqualTo(42L);
            assertThat(record.trancatTypeCd()).isEqualTo("07");
            assertThat(record.trancatCd()).isEqualTo(9);
            assertThat(record.tranCatKeyImage()).isEqualTo(keyImage);
            assertThat(TranCatKey.decode(record.tranCatKeyBytes(), ASCII)).isEqualTo(key);
        }

        @ParameterizedTest(name = "TRANCAT-TYPE-CD X(02): \"{0}\" stores as \"{1}\"")
        @DisplayName("PIC X pads and truncates on the RIGHT - the opposite end from PIC 9")
        @CsvSource(delimiter = '|', ignoreLeadingAndTrailingWhitespace = false, value = {
            "01|01",
            "1|1 ",
            "0123|01",
            "AB|AB",
            "  |  "
        })
        void thePicXComponentPadsAndTruncatesOnTheRight(String supplied, String stored) {
            TranCatBalRecord record = decoded(ROW_1).trancatTypeCd(supplied);
            assertThat(record.trancatTypeCd()).isEqualTo(stored);
            assertThat(record.trancatTypeCd()).hasSize(2);
            assertThat(record.rawImage().substring(11, 13)).isEqualTo(stored);
            assertThat(record.rawImage()).hasSize(50);
        }

        @Test
        @DisplayName("PIC 9 zero-fills and truncates on the LEFT, keeping the low-order digits")
        void thePic9ComponentsZeroFillAndTruncateOnTheLeft() {
            TranCatBalRecord record = decoded(ROW_1).trancatAcctId(7L);
            assertThat(record.trancatAcctIdImage()).isEqualTo("00000000007");
            assertThat(record.trancatAcctId()).isEqualTo(7L);
            assertThat(record.rawImage().substring(0, 11)).isEqualTo("00000000007");

            record.trancatAcctId(123456789012L);
            assertThat(record.trancatAcctIdImage())
                    .as("the low-order 11 digits survive")
                    .isEqualTo("23456789012")
                    .isNotEqualTo("12345678901");
            assertThat(record.trancatAcctId()).isEqualTo(23456789012L);

            record.trancatCd(12345);
            assertThat(record.trancatCdImage())
                    .as("the low-order 4 digits survive")
                    .isEqualTo("2345")
                    .isNotEqualTo("1234");
            assertThat(record.trancatCd()).isEqualTo(2345);

            assertThat(record.trancatAcctIdImage()).hasSize(11);
            assertThat(record.trancatCdImage()).hasSize(4);
            assertThat(record.rawImage()).hasSize(50);
        }

        @Test
        @DisplayName("TRANCAT-TYPE-CD decodes untrimmed, so a padded value keeps its trailing space")
        void theTypeCodeDecodesUntrimmed() {
            assertThat(decoded(ROW_1).trancatTypeCd()).isEqualTo("01");
            TranCatBalRecord record = decoded(ROW_1).trancatTypeCd("1");
            assertThat(record.trancatTypeCd()).isEqualTo("1 ").isNotEqualTo("1");
            assertThat(record.trancatTypeCd().charAt(1)).isEqualTo(' ');
        }

        @Test
        @DisplayName("the key round-trips under EBCDIC too, on different bytes but the same values")
        void theKeyRoundTripsUnderEbcdicToo() {
            TranCatKey key = new TranCatKey(1L, FIXTURE_TYPE_CD, FIXTURE_CAT_CD);
            byte[] ascii = key.toByteArray(ASCII);
            byte[] ebcdic = key.toByteArray(EBCDIC);
            assertThat(ascii).hasSize(17);
            assertThat(ebcdic).hasSize(17);
            assertThat(ebcdic).isNotEqualTo(ascii);
            assertThat(TranCatKey.decode(ebcdic, EBCDIC)).isEqualTo(key);
            assertThat(key.image(EBCDIC)).isEqualTo(key.image(ASCII));
        }

        @Test
        @DisplayName("null is rejected wherever a value is structurally required")
        void nullIsRejectedWhereAValueIsStructurallyRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new TranCatKey(1L, null, 1))
                    .withMessageContaining("TRANCAT-TYPE-CD");
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCatKey.decode((String) null, ASCII));
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCatKey.decode("00000000001010001", null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCatKey.decode((byte[]) null, ASCII));
            assertThatNullPointerException()
                    .isThrownBy(() -> decoded(ROW_1).tranCatKey(null))
                    .withMessageContaining("TRAN-CAT-KEY");
            assertThatNullPointerException()
                    .isThrownBy(() -> decoded(ROW_1).trancatTypeCd(null));
        }

        @Test
        @DisplayName("a key image of the wrong width is rejected rather than silently widened")
        void aKeyImageOfTheWrongWidthIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TranCatKey.decode("0000000000101000", ASCII))
                    .withMessageContaining("17");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TranCatKey.decode("000000000010100010", ASCII));
        }

        @Test
        @DisplayName("a negative key component has no representation in PIC 9 and is refused")
        void aNegativeKeyComponentIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> decoded(ROW_1).trancatAcctId(-1L));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> decoded(ROW_1).trancatCd(-1));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new TranCatKey(-1L, FIXTURE_TYPE_CD, 1).image(ASCII));
        }
    }

    @Nested
    @DisplayName("The shipped TCATBALF rows, decoded at the copybook's absolute offsets")
    class ShippedFixtureRows {
        @Test
        @DisplayName("row 1 is 50 bytes and decodes field for field, with nothing trimmed")
        void rowOneIsFiftyBytesAndDecodesFieldForField() {
            assertThat(ROW_1).as("app/data/ASCII/tcatbal.txt row 1").hasSize(50);
            TranCatBalRecord record = decoded(ROW_1);

            assertThat(record.recordLength()).isEqualTo(50);
            assertThat(record.trancatAcctId()).isEqualTo(1L);
            assertThat(record.trancatAcctIdImage()).isEqualTo("00000000001");
            assertThat(record.trancatTypeCd()).isEqualTo("01");
            assertThat(record.trancatCd()).isEqualTo(1);
            assertThat(record.trancatCdImage()).isEqualTo("0001");
            assertThat(record.tranCatBalImage()).isEqualTo(FIXTURE_BALANCE_IMAGE);
            assertThat(record.fillerImage()).isEqualTo(FIXTURE_FILLER_IMAGE);
            assertThat(record.fillerImage()).hasSize(22);

            String raw = record.rawImage();
            assertThat(raw).isEqualTo(ROW_1).hasSize(50);
            assertThat(raw.substring(0, 11)).isEqualTo("00000000001");
            assertThat(raw.substring(11, 13)).isEqualTo("01");
            assertThat(raw.substring(13, 17)).isEqualTo("0001");
            assertThat(raw.substring(17, 28)).isEqualTo(FIXTURE_BALANCE_IMAGE);
            assertThat(raw.substring(28, 50)).isEqualTo(FIXTURE_FILLER_IMAGE);
        }

        @Test
        @DisplayName("the shipped balance 0000000000{ is +0.00 at scale 2, not an unset field")
        void theShippedBalanceIsPositiveZeroAtScaleTwo() {
            TranCatBalRecord record = decoded(ROW_1);
            BigDecimal balance = record.tranCatBal();

            assertThat(balance.scale()).as("PIC S9(09)V99 has scale exactly 2").isEqualTo(2);
            assertThat(balance).isEqualTo(new BigDecimal("0.00"));
            assertThat(balance.signum()).isZero();
            assertThat(record.tranCatBalIsZero()).isTrue();

            char trailing = FIXTURE_BALANCE_IMAGE.charAt(FIXTURE_BALANCE_IMAGE.length() - 1);
            assertThat(trailing).isEqualTo(ZonedSign.POSITIVE_ZERO).isEqualTo('{');
            assertThat(ZonedSign.digitOf(trailing)).isZero();
            assertThat(ZonedSign.isNegative(trailing)).isFalse();
            assertThat(FIXTURE_BALANCE_IMAGE.substring(0, 10)).isEqualTo("0000000000");
        }

        @Test
        @DisplayName("rows 1, 4 and 50 differ only in the account, so the account is the varying key part")
        void theRowsDifferOnlyInTheAccountIdentifier() {
            assertThat(decoded(ROW_1).trancatAcctId()).isEqualTo(1L);
            assertThat(decoded(ROW_4).trancatAcctId()).isEqualTo(4L);
            assertThat(decoded(ROW_50).trancatAcctId()).isEqualTo(50L);

            for (String row : List.of(ROW_1, ROW_4, ROW_50)) {
                TranCatBalRecord record = decoded(row);
                assertThat(row).hasSize(50);
                assertThat(record.trancatTypeCd()).isEqualTo(FIXTURE_TYPE_CD);
                assertThat(record.trancatCd()).isEqualTo(FIXTURE_CAT_CD);
                assertThat(record.tranCatBalImage()).isEqualTo(FIXTURE_BALANCE_IMAGE);
                assertThat(record.fillerImage()).isEqualTo(FIXTURE_FILLER_IMAGE);
            }
            assertThat(decoded(ROW_1).tranCatKey()).isNotEqualTo(decoded(ROW_4).tranCatKey());
        }

        @Test
        @DisplayName("a decoded row re-serialises byte for byte, its zero-filled FILLER included")
        void aDecodedRowReserialisesByteForByte() {
            TranCatBalRecord record = decoded(ROW_1);
            assertThat(record.encode()).isEqualTo(ROW_1.getBytes(ASCII));
            assertThat(record.encode()).hasSize(50);
            assertThat(record.rawImage()).isEqualTo(ROW_1);
            assertThat(record.fillerImage())
                    .as("still the shipped zeros, not spaces")
                    .isEqualTo(FIXTURE_FILLER_IMAGE)
                    .doesNotContain(" ");
        }

        @Test
        @DisplayName("FILLER survives the read-modify-rewrite cycle that CBTRN02C:527-528 performs")
        void theFillerSurvivesARewrite() {
            TranCatBalRecord record = decoded(ROW_1).addToTranCatBal(new BigDecimal("10.00"));

            assertThat(record.tranCatBalImage()).isEqualTo("0000000100{");
            assertThat(record.fillerImage()).isEqualTo(FIXTURE_FILLER_IMAGE);
            assertThat(record.tranCatKeyImage()).isEqualTo(decoded(ROW_1).tranCatKeyImage());
            assertThat(record.rawImage()).isEqualTo(recordImage("00000000001", FIXTURE_TYPE_CD,
                    FIXTURE_CAT_CD_IMAGE, "0000000100{", FIXTURE_FILLER_IMAGE));
        }

        @Test
        @DisplayName("fieldImages names the four items in copybook order and excludes FILLER")
        void fieldImagesNamesTheFourItemsInOrderAndExcludesFiller() {
            Map<String, String> images = decoded(ROW_1).fieldImages();
            assertThat(images.keySet()).containsExactly(
                    TranCatBalRecord.TRANCAT_ACCT_ID_NAME,
                    TranCatBalRecord.TRANCAT_TYPE_CD_NAME,
                    TranCatBalRecord.TRANCAT_CD_NAME,
                    TranCatBalRecord.TRAN_CAT_BAL_NAME);
            assertThat(images).hasSize(4);
            assertThat(images).containsEntry(TranCatBalRecord.TRANCAT_ACCT_ID_NAME, "00000000001");
            assertThat(images).containsEntry(TranCatBalRecord.TRANCAT_TYPE_CD_NAME, FIXTURE_TYPE_CD);
            assertThat(images).containsEntry(TranCatBalRecord.TRANCAT_CD_NAME, FIXTURE_CAT_CD_IMAGE);
            assertThat(images).containsEntry(TranCatBalRecord.TRAN_CAT_BAL_NAME,
                    FIXTURE_BALANCE_IMAGE);
        }

        @Test
        @DisplayName("the field image map is unmodifiable, so a caller cannot rewrite the record through it")
        void theFieldImageMapIsUnmodifiable() {
            Map<String, String> images = decoded(ROW_1).fieldImages();
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> images.put(TranCatBalRecord.TRAN_CAT_BAL_NAME, "0000000001A"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> images.remove(TranCatBalRecord.TRANCAT_CD_NAME));
        }

        @Test
        @DisplayName("every byte array handed out is a copy, so the 50-byte area cannot be reached")
        void everyByteArrayHandedOutIsACopy() {
            TranCatBalRecord record = decoded(ROW_1);

            byte[] whole = record.encode();
            whole[TranCatBalRecord.TRAN_CAT_BAL_OFFSET] = (byte) '9';
            byte[] key = record.tranCatKeyBytes();
            key[0] = (byte) '9';
            byte[] filler = record.fillerBytes();
            filler[0] = (byte) ' ';

            assertThat(record.rawImage()).isEqualTo(ROW_1);
            assertThat(record.fillerImage()).isEqualTo(FIXTURE_FILLER_IMAGE);
            assertThat(record.fillerBytes()).hasSize(22);
        }

        @Test
        @DisplayName("a row of the wrong width is rejected rather than padded or clipped")
        void aRowOfTheWrongWidthIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TranCatBalRecord.decode(ROW_1.substring(0, 49), ASCII))
                    .withMessageContaining("50");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TranCatBalRecord.decode(ROW_1 + "0", ASCII));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TranCatBalRecord.decode(new byte[36], ASCII));
        }

        @Test
        @DisplayName("null is rejected at every entry point that needs a value")
        void nullIsRejectedAtEveryEntryPoint() {
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCatBalRecord.decode((String) null, ASCII));
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCatBalRecord.decode(ROW_1, null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCatBalRecord.decode((byte[]) null, ASCII));
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCatBalRecord.newInstance(null));
        }
    }

    @Nested
    @DisplayName("The zoned sign overpunch - both halves of the alphabet (gate G50)")
    class SignOverpunch {
        @ParameterizedTest(name = "{0} decodes to {1}")
        @DisplayName("a trailing glyph from either half decodes to its signed value at scale 2")
        @CsvSource({
            "0000000000{, 0.00",
            "0000000000A, 0.01",
            "0000000000G, 0.07",
            "0000000000I, 0.09",
            "0000000000}, 0.00",
            "0000000000J, -0.01",
            "0000000000R, -0.09",
            "0000123456C, 12345.63",
            "0000123456L, -12345.63",
            "9999999999I, 999999999.99"
        })
        void aTrailingGlyphFromEitherHalfDecodes(String balanceImage, String expected) {
            assertThat(balanceImage).as("PIC S9(09)V99 is 11 bytes").hasSize(11);
            TranCatBalRecord record = decoded(recordImage("00000000001", FIXTURE_TYPE_CD,
                    FIXTURE_CAT_CD_IMAGE, balanceImage, FIXTURE_FILLER_IMAGE));

            BigDecimal balance = record.tranCatBal();
            assertThat(balance.scale()).as("the declared scale is always reported").isEqualTo(2);
            assertThat(balance).isEqualByComparingTo(new BigDecimal(expected));
            assertThat(record.tranCatBalImage()).isEqualTo(balanceImage);
            assertThat(record.tranCatKeyImage()).hasSize(17);
            assertThat(record.fillerImage()).isEqualTo(FIXTURE_FILLER_IMAGE);
        }

        @ParameterizedTest(name = "{0} stores as {1}")
        @DisplayName("a stored value carries the overpunch for its sign into the trailing byte")
        @CsvSource({
            "0.00, 0000000000{",
            "0.01, 0000000000A",
            "0.07, 0000000000G",
            "0.09, 0000000000I",
            "-0.01, 0000000000J",
            "-0.09, 0000000000R",
            "-0.10, 0000000001}",
            "12345.63, 0000123456C",
            "-12345.63, 0000123456L",
            "999999999.99, 9999999999I",
            "-999999999.99, 9999999999R"
        })
        void aStoredValueCarriesTheOverpunchForItsSign(String value, String expectedImage) {
            TranCatBalRecord record = decoded(ROW_1).tranCatBal(new BigDecimal(value));
            assertThat(record.tranCatBalImage()).isEqualTo(expectedImage).hasSize(11);
            assertThat(record.tranCatBal()).isEqualByComparingTo(new BigDecimal(value));
            assertThat(record.tranCatBal().scale()).isEqualTo(2);
            assertThat(record.tranCatKeyImage()).isEqualTo(decoded(ROW_1).tranCatKeyImage());
            assertThat(record.fillerImage()).isEqualTo(FIXTURE_FILLER_IMAGE);
        }

        @Test
        @DisplayName("+0.00 encodes as 0000000000{ and never as eleven plain digits")
        void positiveZeroEncodesAsTheBraceNeverAsAPlainDigit() {
            TranCatBalRecord record = decoded(ROW_1).tranCatBal(new BigDecimal("0.00"));
            assertThat(record.tranCatBalImage())
                    .isEqualTo("0000000000{")
                    .isNotEqualTo("00000000000");
            assertThat(record.tranCatBalImage().charAt(10)).isEqualTo(ZonedSign.POSITIVE_ZERO);
            assertThat(record.encode()).isEqualTo(ROW_1.getBytes(ASCII));
        }

        @Test
        @DisplayName("a negative value whose low-order digit is zero keeps the negative-zero glyph")
        void aNegativeValueWithAZeroLowOrderDigitKeepsItsGlyph() {
            TranCatBalRecord record = decoded(ROW_1).tranCatBal(new BigDecimal("-0.10"));
            assertThat(record.tranCatBalImage()).isEqualTo("0000000001}");
            assertThat(record.tranCatBalImage().charAt(10)).isEqualTo(ZonedSign.NEGATIVE_ZERO);
            assertThat(record.tranCatBal()).isEqualByComparingTo(new BigDecimal("-0.10"));
            assertThat(record.tranCatBal().signum()).isNegative();
            assertThat(record.tranCatBalIsZero()).isFalse();
        }

        @Test
        @DisplayName("the two overpunch alphabets are the zoned tables, C-zone and D-zone")
        void theTwoOverpunchAlphabetsAreTheZonedTables() {
            assertThat(ZonedSign.POSITIVE_DIGITS).isEqualTo("{ABCDEFGHI").hasSize(10);
            assertThat(ZonedSign.NEGATIVE_DIGITS).isEqualTo("}JKLMNOPQR").hasSize(10);
            assertThat(ZonedSign.POSITIVE_ZERO).isEqualTo('{');
            assertThat(ZonedSign.NEGATIVE_ZERO).isEqualTo('}');
            assertThat(ZonedSign.overpunch(0, false)).isEqualTo('{');
            assertThat(ZonedSign.overpunch(1, false)).isEqualTo('A');
            assertThat(ZonedSign.overpunch(7, false)).isEqualTo('G');
            assertThat(ZonedSign.overpunch(9, false)).isEqualTo('I');
            assertThat(ZonedSign.overpunch(0, true)).isEqualTo('}');
            assertThat(ZonedSign.overpunch(1, true)).isEqualTo('J');
            assertThat(ZonedSign.overpunch(9, true)).isEqualTo('R');
            assertThat(ZonedSign.digitOf('G')).isEqualTo(7);
            assertThat(ZonedSign.digitOf('P')).isEqualTo(7);
            assertThat(ZonedSign.isNegative('P')).isTrue();
            assertThat(ZonedSign.isNegative('G')).isFalse();
        }

        @Test
        @DisplayName("a balance span that is neither digits nor an overpunch is refused, not guessed at")
        void anUnrecognisedBalanceSpanIsRefused() {
            assertThatIllegalArgumentException().isThrownBy(() -> decoded(recordImage("00000000001",
                    FIXTURE_TYPE_CD, FIXTURE_CAT_CD_IMAGE, "0000000000*", FIXTURE_FILLER_IMAGE))
                    .tranCatBal());
            assertThatIllegalArgumentException().isThrownBy(() -> decoded(recordImage("00000000001",
                    FIXTURE_TYPE_CD, FIXTURE_CAT_CD_IMAGE, "00000X0000{", FIXTURE_FILLER_IMAGE))
                    .tranCatBal());
            assertThatIllegalArgumentException().isThrownBy(() -> decoded(recordImage("00000000001",
                    FIXTURE_TYPE_CD, FIXTURE_CAT_CD_IMAGE, " ".repeat(11), FIXTURE_FILLER_IMAGE))
                    .tranCatBal());
        }
    }

    @Nested
    @DisplayName("Numeric parity - truncation, never rounding (gates G22, G23, G24, G25)")
    class NumericParity {
        @Test
        @DisplayName("the balance handed out is a scale-2 BigDecimal, fit to feed the interest formula")
        void theBalanceHandedOutIsAScaleTwoBigDecimal() {
            BigDecimal shipped = decoded(ROW_1).tranCatBal();
            assertThat(shipped).isInstanceOf(BigDecimal.class);
            assertThat(shipped.scale()).isEqualTo(2);
            assertThat(shipped.scale()).isEqualTo(CobolDecimal.MONETARY_SCALE);

            for (String value : List.of("0", "0.0", "0.00", "1", "1.5", "-1.5", "123456789")) {
                BigDecimal stored = decoded(ROW_1).tranCatBal(new BigDecimal(value)).tranCatBal();
                assertThat(stored.scale())
                        .as("a %s-scaled sender still lands at scale 2", value)
                        .isEqualTo(2);
            }

            assertThat(CobolDecimal.COBOL_ROUNDING).isEqualTo(RoundingMode.DOWN);
        }

        @Test
        @DisplayName("an over-precise store truncates toward zero for a positive and a negative value")
        void anOverPreciseStoreTruncatesTowardZero() {
            TranCatBalRecord positive = decoded(ROW_1).tranCatBal(new BigDecimal("1.239"));
            assertThat(positive.tranCatBal()).isEqualByComparingTo(new BigDecimal("1.23"));
            assertThat(positive.tranCatBal()).isNotEqualByComparingTo(new BigDecimal("1.24"));
            assertThat(positive.tranCatBal().scale()).isEqualTo(2);
            assertThat(positive.tranCatBalImage()).isEqualTo("0000000012C");

            TranCatBalRecord negative = decoded(ROW_1).tranCatBal(new BigDecimal("-1.239"));
            assertThat(negative.tranCatBal()).isEqualByComparingTo(new BigDecimal("-1.23"));
            assertThat(negative.tranCatBal()).isNotEqualByComparingTo(new BigDecimal("-1.24"));
            assertThat(negative.tranCatBal().scale()).isEqualTo(2);
            assertThat(negative.tranCatBalImage()).isEqualTo("0000000012L");

            assertThat(decoded(ROW_1).tranCatBal(new BigDecimal("2.50")).tranCatBalImage())
                    .isEqualTo("0000000025{");
        }

        @Test
        @DisplayName("a sender of smaller scale is padded up, which is exact and never truncates")
        void aSenderOfSmallerScaleIsPaddedUp() {
            TranCatBalRecord record = decoded(ROW_1).tranCatBal(new BigDecimal("7"));
            assertThat(record.tranCatBal()).isEqualByComparingTo(new BigDecimal("7.00"));
            assertThat(record.tranCatBal().scale()).isEqualTo(2);
            assertThat(record.tranCatBalImage()).isEqualTo("0000000070{");
        }

        @Test
        @DisplayName("integer digits beyond the ninth are discarded silently, as COBOL discards them")
        void integerDigitsBeyondTheNinthAreDiscardedSilently() {
            TranCatBalRecord positive = decoded(ROW_1).tranCatBal(new BigDecimal("12345678901.23"));
            assertThat(positive.tranCatBal()).isEqualByComparingTo(new BigDecimal("345678901.23"));
            assertThat(positive.tranCatBalImage()).isEqualTo("3456789012C");

            TranCatBalRecord negative = decoded(ROW_1).tranCatBal(new BigDecimal("-12345678901.23"));
            assertThat(negative.tranCatBal()).isEqualByComparingTo(new BigDecimal("-345678901.23"));
            assertThat(negative.tranCatBalImage()).isEqualTo("3456789012L");

            assertThat(decoded(ROW_1).tranCatBal(new BigDecimal("999999999.99")).tranCatBal())
                    .isEqualByComparingTo(new BigDecimal("999999999.99"));
        }

        @Test
        @DisplayName("ADD ... TO TRAN-CAT-BAL accumulates at scale 2 and subtracts for a negative addend")
        void addAccumulatesAtScaleTwo() {
            TranCatBalRecord record = decoded(ROW_1);
            assertThat(record.tranCatBal()).isEqualByComparingTo(BigDecimal.ZERO);

            record.addToTranCatBal(new BigDecimal("12.34"));
            assertThat(record.tranCatBal()).isEqualByComparingTo(new BigDecimal("12.34"));
            assertThat(record.tranCatBalImage()).isEqualTo("0000000123D");

            record.addToTranCatBal(new BigDecimal("12.34"));
            assertThat(record.tranCatBal()).isEqualByComparingTo(new BigDecimal("24.68"));
            assertThat(record.tranCatBalImage()).isEqualTo("0000000246H");

            record.addToTranCatBal(new BigDecimal("-30.00"));
            assertThat(record.tranCatBal()).isEqualByComparingTo(new BigDecimal("-5.32"));
            assertThat(record.tranCatBal().scale()).isEqualTo(2);
            assertThat(record.tranCatBal().signum()).isNegative();
            assertThat(record.tranCatBalImage()).isEqualTo("0000000053K");

            record.addToTranCatBal(new BigDecimal("0.005"));
            assertThat(record.tranCatBal()).isEqualByComparingTo(new BigDecimal("-5.31"));
        }

        @Test
        @DisplayName("ADD requires an addend, because COBOL has no null to add")
        void addRequiresAnAddend() {
            assertThatNullPointerException()
                    .isThrownBy(() -> decoded(ROW_1).addToTranCatBal(null))
                    .withMessageContaining("ADD");
            assertThatNullPointerException()
                    .isThrownBy(() -> decoded(ROW_1).tranCatBal(null));
        }

        @Test
        @DisplayName("tranCatBalIsZero answers by sign, so 0.00 and a truncated 0.001 are both zero")
        void zeroIsDecidedBySignNotByScale() {
            assertThat(decoded(ROW_1).tranCatBalIsZero()).isTrue();
            assertThat(decoded(ROW_1).tranCatBal(BigDecimal.ZERO).tranCatBalIsZero()).isTrue();
            assertThat(decoded(ROW_1).tranCatBal(new BigDecimal("0.001")).tranCatBalIsZero()).isTrue();
            assertThat(decoded(ROW_1).tranCatBal(new BigDecimal("0.01")).tranCatBalIsZero()).isFalse();
            assertThat(decoded(ROW_1).tranCatBal(new BigDecimal("-0.01")).tranCatBalIsZero()).isFalse();
        }
    }

    @Nested
    @DisplayName("INITIALIZE - reproduced from CBTRN02C:503-509, FILLER included (practice B5)")
    class InitializeSemantics {
        @Test
        @DisplayName("the balance is zeroed to scale 2, so the following ADD cannot accumulate onto stale data")
        void theBalanceIsZeroedSoTheFollowingAddStartsFromZero() {
            TranCatBalRecord record = decoded(ROW_WITH_STALE_BALANCE);
            assertThat(record.tranCatBal())
                    .as("the stale value the create path must discard")
                    .isEqualByComparingTo(new BigDecimal("123.45"));

            record.initialize();
            assertThat(record.tranCatBal()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(record.tranCatBal().scale())
                    .as("zero at the declared scale, not a scale-0 zero")
                    .isEqualTo(2);
            assertThat(record.tranCatBalImage()).isEqualTo(FIXTURE_BALANCE_IMAGE);
            assertThat(record.tranCatBalIsZero()).isTrue();

            record.addToTranCatBal(new BigDecimal("75.50"));
            assertThat(record.tranCatBal()).isEqualByComparingTo(new BigDecimal("75.50"));
            assertThat(record.tranCatBal()).isNotEqualByComparingTo(new BigDecimal("198.95"));
            assertThat(record.tranCatBalImage()).isEqualTo("0000000755{");
        }

        @Test
        @DisplayName("FILLER is left exactly as it was, which is preserved behaviour and not a defect")
        void theFillerIsLeftExactlyAsItWas() {
            TranCatBalRecord record = decoded(ROW_1);
            assertThat(record.fillerImage()).isEqualTo(FIXTURE_FILLER_IMAGE);

            record.initialize();

            assertThat(record.fillerImage())
                    .as("still the twenty-two ASCII zeros the row was read with")
                    .isEqualTo(FIXTURE_FILLER_IMAGE)
                    .isNotEqualTo(" ".repeat(22));
            byte[] image = record.encode();
            byte[] zero = "0".getBytes(ASCII);
            for (int offset = TranCatBalRecord.FILLER_OFFSET;
                    offset < TranCatBalRecord.RECORD_LENGTH; offset++) {
                assertThat(image[offset])
                        .as("FILLER byte at 0-based offset %d is still an ASCII zero", offset)
                        .isEqualTo(zero[0]);
            }
            assertThat(record.encode()).hasSize(50);
        }

        @Test
        @DisplayName("numeric items go to ZERO and the alphanumeric item goes to SPACES")
        void numericItemsGoToZeroAndTheAlphanumericItemToSpaces() {
            TranCatBalRecord record = decoded(ROW_WITH_STALE_BALANCE);
            assertThat(record.trancatAcctId()).isEqualTo(9L);
            assertThat(record.trancatTypeCd()).isEqualTo("AB");
            assertThat(record.trancatCd()).isEqualTo(7);

            record.initialize();

            assertThat(record.trancatAcctId()).isZero();
            assertThat(record.trancatAcctIdImage()).isEqualTo("00000000000");
            assertThat(record.trancatCd()).isZero();
            assertThat(record.trancatCdImage()).isEqualTo("0000");
            assertThat(record.trancatTypeCd()).isEqualTo("  ").hasSize(2);
            assertThat(record.rawImage()).isEqualTo(recordImage("00000000000", "  ", "0000",
                    FIXTURE_BALANCE_IMAGE, FIXTURE_FILLER_IMAGE));
        }

        @Test
        @DisplayName("initialize returns this and is idempotent, so a create path chains as COBOL sequences it")
        void initializeReturnsThisAndIsIdempotent() {
            TranCatBalRecord record = decoded(ROW_WITH_STALE_BALANCE);
            assertThat(record.initialize()).isSameAs(record);
            String once = record.rawImage();
            assertThat(record.initialize().rawImage()).isEqualTo(once);
            assertThat(record.trancatAcctId(1L)).isSameAs(record);
            assertThat(record.trancatTypeCd("01")).isSameAs(record);
            assertThat(record.trancatCd(1)).isSameAs(record);
            assertThat(record.tranCatBal(BigDecimal.ZERO)).isSameAs(record);
            assertThat(record.addToTranCatBal(BigDecimal.ONE)).isSameAs(record);
            assertThat(record.tranCatKey(new TranCatKey(1L, "01", 1))).isSameAs(record);
        }

        @Test
        @DisplayName("the whole create-on-miss paragraph reproduces statement for statement")
        void theWholeCreateOnMissParagraphReproducesStatementForStatement() {
            long xrefAcctId = 42L;
            String dalytranTypeCd = "05";
            int dalytranCatCd = 3;
            BigDecimal dalytranAmt = new BigDecimal("250.75");

            TranCatBalRecord created = decoded(ROW_WITH_STALE_BALANCE)
                    .initialize()
                    .trancatAcctId(xrefAcctId)
                    .trancatTypeCd(dalytranTypeCd)
                    .trancatCd(dalytranCatCd)
                    .addToTranCatBal(dalytranAmt);

            assertThat(created.rawImage()).isEqualTo(recordImage("00000000042", "05", "0003",
                    "0000002507E", FIXTURE_FILLER_IMAGE));
            assertThat(created.encode()).hasSize(50);
            assertThat(created.tranCatBal()).isEqualByComparingTo(dalytranAmt);
            assertThat(created.tranCatKey()).isEqualTo(new TranCatKey(42L, "05", 3));
            assertThat(created.fillerImage()).isEqualTo(FIXTURE_FILLER_IMAGE);
        }
    }

    @Nested
    @DisplayName("A freshly allocated record - the write path from nothing (gates G19, G21)")
    class FreshlyAllocatedRecord {
        @Test
        @DisplayName("FILLER X(22) at offset 28 is space-filled, which does NOT contradict the read path")
        void theFillerIsSpaceFilledOnAFreshRecord() {
            TranCatBalRecord fresh = TranCatBalRecord.newInstance(ASCII);
            assertThat(fresh.fillerImage()).isEqualTo(" ".repeat(22)).hasSize(22);

            byte[] image = fresh.encode();
            byte[] space = " ".getBytes(ASCII);
            for (int offset = TranCatBalRecord.FILLER_OFFSET;
                    offset < TranCatBalRecord.RECORD_LENGTH; offset++) {
                assertThat(image[offset])
                        .as("FILLER byte at 0-based offset %d is a space", offset)
                        .isEqualTo(space[0]);
            }
            assertThat(image).hasSize(50);
        }

        @Test
        @DisplayName("the four named items are initialised and the record is exactly 50 bytes")
        void theFourNamedItemsAreInitialised() {
            TranCatBalRecord fresh = TranCatBalRecord.newInstance(ASCII);
            assertThat(fresh.recordLength()).isEqualTo(50);
            assertThat(fresh.trancatAcctId()).isZero();
            assertThat(fresh.trancatAcctIdImage()).isEqualTo("00000000000");
            assertThat(fresh.trancatTypeCd()).isEqualTo("  ");
            assertThat(fresh.trancatCd()).isZero();
            assertThat(fresh.trancatCdImage()).isEqualTo("0000");
            assertThat(fresh.tranCatBal()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(fresh.tranCatBal().scale()).isEqualTo(2);
            assertThat(fresh.tranCatBalImage()).isEqualTo(FIXTURE_BALANCE_IMAGE);
            assertThat(fresh.rawImage()).isEqualTo(recordImage("00000000000", "  ", "0000",
                    "0000000000{", " ".repeat(22)));
        }

        @Test
        @DisplayName("the whole 50-byte image is available, which is what DISPLAY of the record needs")
        void theWholeFiftyByteImageIsAvailable() {
            assertThat(TranCatBalRecord.newInstance(ASCII).encode()).hasSize(50);
            assertThat(TranCatBalRecord.newInstance(ASCII).rawImage()).hasSize(50);

            TranCatBalRecord record = decoded(ROW_1);
            assertThat(record.encode()).hasSize(50).isEqualTo(ROW_1.getBytes(ASCII));
            assertThat(record.rawImage()).hasSize(50).isEqualTo(ROW_1);
        }

        @Test
        @DisplayName("the code page is whatever was supplied and is never a platform default")
        void theCodePageIsWhateverWasSupplied() {
            assertThat(TranCatBalRecord.newInstance(ASCII).charset()).isEqualTo(ASCII);
            assertThat(TranCatBalRecord.newInstance(EBCDIC).charset()).isEqualTo(EBCDIC);
            assertThat(decoded(ROW_1).charset()).isEqualTo(ASCII);

            byte[] asciiImage = TranCatBalRecord.newInstance(ASCII).encode();
            byte[] ebcdicImage = TranCatBalRecord.newInstance(EBCDIC).encode();
            assertThat(ebcdicImage).hasSize(50);
            assertThat(ebcdicImage).isNotEqualTo(asciiImage);
        }
    }

    @Nested
    @DisplayName("Value semantics - the comparison the dataset itself makes")
    class ValueSemantics {
        @Test
        @DisplayName("two records over the same 50 bytes and the same code page are equal")
        void identicalBytesAndCodePageAreEqual() {
            TranCatBalRecord first = decoded(ROW_1);
            TranCatBalRecord second = decoded(ROW_1);
            assertThat(first).isEqualTo(second);
            assertThat(first).hasSameHashCodeAs(second);
        }

        @Test
        @DisplayName("a record equals itself without comparing a byte")
        void aRecordEqualsItself() {
            TranCatBalRecord record = decoded(ROW_1);
            assertThat(record.equals(record)).isTrue();
        }

        @Test
        @DisplayName("nothing that is not a TranCatBalRecord is ever equal to one")
        void anythingElseIsNeverEqual() {
            TranCatBalRecord record = decoded(ROW_1);
            assertThat(record).isNotEqualTo(null);
            assertThat(record).isNotEqualTo(ROW_1);
            assertThat(record).isNotEqualTo(record.tranCatKey());
        }

        @Test
        @DisplayName("different bytes are not equal, and a FILLER difference is a difference")
        void differentBytesAreNotEqual() {
            assertThat(decoded(ROW_1)).isNotEqualTo(decoded(ROW_4));

            TranCatBalRecord spaceFilled = decoded(recordImage("00000000001", FIXTURE_TYPE_CD,
                    FIXTURE_CAT_CD_IMAGE, FIXTURE_BALANCE_IMAGE, " ".repeat(22)));
            assertThat(spaceFilled).isNotEqualTo(decoded(ROW_1));
            assertThat(spaceFilled.tranCatKey()).isEqualTo(decoded(ROW_1).tranCatKey());
            assertThat(spaceFilled.tranCatBalImage()).isEqualTo(decoded(ROW_1).tranCatBalImage());
        }

        @Test
        @DisplayName("the same bytes under a different code page are not the same record")
        void theSameBytesUnderADifferentCodePageAreNotEqual() {
            byte[] bytes = ROW_1.getBytes(ASCII);
            assertThat(TranCatBalRecord.decode(bytes, ASCII))
                    .isNotEqualTo(TranCatBalRecord.decode(bytes, EBCDIC));
        }

        @Test
        @DisplayName("the key value type compares by its three components")
        void theKeyComparesByItsThreeComponents() {
            TranCatKey key = new TranCatKey(1L, "01", 1);
            assertThat(key).isEqualTo(new TranCatKey(1L, "01", 1));
            assertThat(key).hasSameHashCodeAs(new TranCatKey(1L, "01", 1));
            assertThat(key).isNotEqualTo(new TranCatKey(2L, "01", 1));
            assertThat(key).isNotEqualTo(new TranCatKey(1L, "02", 1));
            assertThat(key).isNotEqualTo(new TranCatKey(1L, "01", 2));
        }
    }

    @Nested
    @DisplayName("Diagnostic rendering - what it withholds while staying diagnosable")
    class DiagnosticRendering {
        @Test
        @DisplayName("the account identifier is masked and the balance is withheld entirely")
        void theAccountIsMaskedAndTheBalanceWithheld() {
            String rendered = decoded(ROW_1).toString();
            assertThat(rendered).startsWith(TranCatBalRecord.RECORD_NAME + "[");
            assertThat(rendered).endsWith("]");
            assertThat(rendered).contains(TranCatBalRecord.TRANCAT_ACCT_ID_NAME);
            assertThat(rendered).contains(TranCatBalRecord.TRANCAT_TYPE_CD_NAME);
            assertThat(rendered).contains(TranCatBalRecord.TRANCAT_CD_NAME);
            assertThat(rendered).contains(TranCatBalRecord.TRAN_CAT_BAL_NAME);
            assertThat(rendered).doesNotContain(FIXTURE_BALANCE_IMAGE);
            assertThat(rendered).doesNotContain("00000000001");
            assertThat(rendered).contains(FIXTURE_TYPE_CD);
            assertThat(rendered).contains(FIXTURE_CAT_CD_IMAGE);
        }

        @Test
        @DisplayName("the rendering is one line and is deterministic, so it cannot forge a log entry")
        void theRenderingIsOneLineAndDeterministic() {
            String rendered = decoded(ROW_1).toString();
            assertThat(rendered).doesNotContain("\n").doesNotContain("\r");
            assertThat(rendered).isEqualTo(decoded(ROW_1).toString());
            String withBalance = decoded(ROW_1).tranCatBal(new BigDecimal("1234.56")).toString();
            assertThat(withBalance)
                    .doesNotContain("1234.56")
                    .doesNotContain("123456")
                    .doesNotContain("0000012345F");
            assertThat(withBalance).contains(TranCatBalRecord.TRAN_CAT_BAL_NAME);
        }
    }

    @Nested
    @DisplayName("Structural guards - properties of the type itself (gates G22, G44, G52, G53)")
    class StructuralGuards {
        private static List<Field> declaredFields(Class<?> type) {
            List<Field> fields = new ArrayList<>();
            for (Field field : type.getDeclaredFields()) {
                if (!field.isSynthetic() && !field.getName().startsWith("$")) {
                    fields.add(field);
                }
            }
            assertThat(fields).as("the guard must find real fields in %s to vouch for", type.getName())
                    .isNotEmpty();
            return fields;
        }

        @Test
        @DisplayName("neither double nor float appears in any field, parameter or return type (R4, G22)")
        void noBinaryFloatingPointAnywhere() {
            for (Class<?> type : List.of(TranCatBalRecord.class, TranCatKey.class)) {
                for (Field field : declaredFields(type)) {
                    assertThat(field.getType())
                            .as("field %s.%s", type.getSimpleName(), field.getName())
                            .isNotIn(double.class, float.class, Double.class, Float.class);
                }
                for (Method method : type.getDeclaredMethods()) {
                    if (method.isSynthetic() || method.getName().startsWith("$")) {
                        continue;
                    }
                    assertThat(method.getReturnType())
                            .as("return type of %s.%s", type.getSimpleName(), method.getName())
                            .isNotIn(double.class, float.class, Double.class, Float.class);
                    assertThat(method.getParameterTypes())
                            .as("parameters of %s.%s", type.getSimpleName(), method.getName())
                            .doesNotContain(double.class, float.class, Double.class, Float.class);
                }
            }
        }

        @Test
        @DisplayName("no persistence mapping of any kind is declared on the type (G44)")
        void noPersistenceMappingOfAnyKind() {
            assertThat(TranCatBalRecord.class.getAnnotations()).isEmpty();
            assertThat(TranCatKey.class.getAnnotations()).isEmpty();

            List<Annotation> found = new ArrayList<>();
            for (Class<?> type : List.of(TranCatBalRecord.class, TranCatKey.class)) {
                found.addAll(List.of(type.getAnnotations()));
                for (Field field : declaredFields(type)) {
                    found.addAll(List.of(field.getAnnotations()));
                }
                for (Method method : type.getDeclaredMethods()) {
                    found.addAll(List.of(method.getAnnotations()));
                }
                for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                    found.addAll(List.of(constructor.getAnnotations()));
                }
            }
            for (Annotation annotation : found) {
                String name = annotation.annotationType().getName();
                assertThat(name)
                        .as("annotation %s must not be a persistence or framework mapping", name)
                        .doesNotStartWith("jakarta.persistence.")
                        .doesNotStartWith("javax.persistence.")
                        .doesNotStartWith("org.springframework.");
            }
        }

        @Test
        @DisplayName("no mutable static state, so instances are independent (G53)")
        void noMutableStaticState() {
            for (Class<?> type : List.of(TranCatBalRecord.class, TranCatKey.class)) {
                for (Field field : declaredFields(type)) {
                    if (Modifier.isStatic(field.getModifiers())) {
                        assertThat(Modifier.isFinal(field.getModifiers()))
                                .as("static field %s.%s must be final", type.getSimpleName(),
                                        field.getName())
                                .isTrue();
                    }
                }
            }
        }

        @Test
        @DisplayName("the 50-byte area is private and final, and every constructor states its code page")
        void theRecordAreaIsPrivateAndFinal() {
            for (Field field : declaredFields(TranCatBalRecord.class)) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isPrivate(field.getModifiers()))
                            .as("instance field %s must be private", field.getName())
                            .isTrue();
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("instance field %s must be final", field.getName())
                            .isTrue();
                }
            }
            for (Constructor<?> constructor : TranCatBalRecord.class.getDeclaredConstructors()) {
                assertThat(Modifier.isPrivate(constructor.getModifiers()))
                        .as("constructor %s must be private", constructor)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("this test class holds no mutable static state either (practice B9)")
        void thisTestClassHoldsNoMutableStaticStateEither() {
            for (Field field : declaredFields(TranCatBalRecordTest.class)) {
                assertThat(Modifier.isStatic(field.getModifiers()))
                        .as("field %s must be static, as a shared fixture constant", field.getName())
                        .isTrue();
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("field %s must be final", field.getName())
                        .isTrue();
                assertThat(field.getType())
                        .as("field %s must be of an immutable type", field.getName())
                        .isIn(String.class, int.class, Charset.class);
            }
        }
    }
}
