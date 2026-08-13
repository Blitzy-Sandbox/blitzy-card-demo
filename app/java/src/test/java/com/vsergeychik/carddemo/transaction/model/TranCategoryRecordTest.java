package com.vsergeychik.carddemo.transaction.model;

import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link TranCategoryRecord}, the 60-byte {@code TRANCATG} transaction-category record
 * declared by {@code app/cpy/CVTRA04Y.cpy} with a 6-byte composite key.
 */
@DisplayName("TranCategoryRecord - CVTRA04Y TRAN-CAT-RECORD, 60 bytes, 6-byte composite key")
class TranCategoryRecordTest {
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    private static final Charset EBCDIC = Charset.forName("IBM037");

    private static final int DECLARED_RECORD_LENGTH = 60;

    private static final int DECLARED_KEY_LENGTH = 6;

    private static final int DECLARED_DESC_LENGTH = 50;

    private static final int REPORT_CAT_DESC_LENGTH = 29;

    private static final int NAMESAKE_ACCT_ID_LENGTH = 11;

    private static final int NAMESAKE_KEY_LENGTH = 17;

    private static final int CVTRA05Y_TYPE_CD_OFFSET = 16;

    private static final int CVTRA05Y_CAT_CD_OFFSET = 18;

    private static final String FIXTURE_FILLER = "0000";

    private static final String BLANK_FILLER = "    ";

    private static final String ROW_01 = "010001Regular Sales Draft                               0000";

    private static final String ROW_02 = "010002Regular Cash Advance                              0000";

    private static final String ROW_03 = "010003Convenience Check Debit                           0000";

    private static final String ROW_04 = "010004ATM Cash Advance                                  0000";

    private static final String ROW_05 = "010005Interest Amount                                   0000";

    private static final String ROW_06 = "020001Cash payment                                      0000";

    private static final String ROW_07 = "020002Electronic payment                                0000";

    private static final String ROW_08 = "020003Check payment                                     0000";

    private static final String ROW_09 = "030001Credit to Account                                 0000";

    private static final String ROW_10 = "030002Credit to Purchase balance                        0000";

    private static final String ROW_11 = "030003Credit to Cash balance                            0000";

    private static final String ROW_12 = "040001Zero dollar authorization                         0000";

    private static final String ROW_13 = "040002Online purchase authorization                     0000";

    private static final String ROW_14 = "040003Travel booking authorization                      0000";

    private static final String ROW_15 = "050001Refund credit                                     0000";

    private static final String ROW_16 = "060001Fraud reversal                                    0000";

    private static final String ROW_17 = "060002Non-fraud reversal                                0000";

    private static final String ROW_18 = "070001Sales draft credit adjustment                     0000";

    private static final String SYNTHETIC_LONG_DESC = "Provisional credit adjustment reversal notice";

    private static final String SYNTHETIC_SURVIVING_29 = "Provisional credit adjustment";

    private static final String SYNTHETIC_DISCARDED_TAIL = " reversal notice";

    private static final String SYNTHETIC_THIRTY_DESC = "Provisional credit adjustments";

    private static List<String> fixtureRows() {
        return List.of(ROW_01, ROW_02, ROW_03, ROW_04, ROW_05, ROW_06, ROW_07, ROW_08, ROW_09,
                ROW_10, ROW_11, ROW_12, ROW_13, ROW_14, ROW_15, ROW_16, ROW_17, ROW_18);
    }

    private static String fixtureRow(int rowNumber) {
        return fixtureRows().get(rowNumber - 1);
    }

    private static TranCategoryRecord decodedRow(int rowNumber) {
        return TranCategoryRecord.decode(fixtureRow(rowNumber).getBytes(ASCII), ASCII);
    }

    private static String padded(String value, int width) {
        return value + " ".repeat(width - value.length());
    }

    private static String zoned(int value, int width) {
        String digits = Integer.toString(value);
        return "0".repeat(width - digits.length()) + digits;
    }

    private static String movedIntoReportField(String storedDescription) {
        return new FixedWidthCodec(ASCII).movePicX(storedDescription, REPORT_CAT_DESC_LENGTH);
    }

    @Nested
    @DisplayName("Declared geometry - CVTRA04Y's own arithmetic, 2+4+50+4 = 60")
    class DeclaredGeometry {
        @Test
        @DisplayName("the record is 60 bytes, and the declared spans add up to it")
        void theRecordIsSixtyBytes() {
            assertThat(TranCategoryRecord.RECORD_LENGTH).isEqualTo(DECLARED_RECORD_LENGTH);
            assertThat(TranCategoryRecord.LAYOUT.recordLength()).isEqualTo(DECLARED_RECORD_LENGTH);
            assertThat(TranCategoryRecord.sumOfDeclaredSpanWidths()).isEqualTo(DECLARED_RECORD_LENGTH);

            assertThat(TranCategoryRecord.TRAN_TYPE_CD_LENGTH
                    + TranCategoryRecord.TRAN_CAT_CD_LENGTH
                    + TranCategoryRecord.TRAN_CAT_TYPE_DESC_LENGTH
                    + TranCategoryRecord.FILLER_LENGTH).isEqualTo(DECLARED_RECORD_LENGTH);

            assertThat(TranCategoryRecord.TRAN_CAT_KEY_LENGTH
                    + TranCategoryRecord.TRAN_CAT_TYPE_DESC_LENGTH
                    + TranCategoryRecord.FILLER_LENGTH).isEqualTo(DECLARED_RECORD_LENGTH);
        }

        @Test
        @DisplayName("every span sits at the copybook's offset: 0, 2, 6 and 56")
        void everySpanSitsAtItsCopybookOffset() {
            assertThat(TranCategoryRecord.TRAN_TYPE_CD_OFFSET).isZero();
            assertThat(TranCategoryRecord.TRAN_CAT_CD_OFFSET)
                    .isEqualTo(TranCategoryRecord.TRAN_TYPE_CD_OFFSET
                            + TranCategoryRecord.TRAN_TYPE_CD_LENGTH)
                    .isEqualTo(2);
            assertThat(TranCategoryRecord.TRAN_CAT_TYPE_DESC_OFFSET)
                    .isEqualTo(TranCategoryRecord.TRAN_CAT_CD_OFFSET
                            + TranCategoryRecord.TRAN_CAT_CD_LENGTH)
                    .isEqualTo(6);
            assertThat(TranCategoryRecord.FILLER_OFFSET)
                    .isEqualTo(TranCategoryRecord.TRAN_CAT_TYPE_DESC_OFFSET
                            + TranCategoryRecord.TRAN_CAT_TYPE_DESC_LENGTH)
                    .isEqualTo(56);
            assertThat(TranCategoryRecord.FILLER_OFFSET + TranCategoryRecord.FILLER_LENGTH)
                    .as("the FILLER ends on the record's last byte, leaving nothing over")
                    .isEqualTo(DECLARED_RECORD_LENGTH);
        }

        @Test
        @DisplayName("the four spans are declared in copybook order, contiguously, with no overlay")
        void theFourSpansAreDeclaredInCopybookOrder() {
            List<FieldSpan> spans = TranCategoryRecord.LAYOUT.storageSpans();

            assertThat(spans).containsExactly(
                    TranCategoryRecord.TRAN_TYPE_CD,
                    TranCategoryRecord.TRAN_CAT_CD,
                    TranCategoryRecord.TRAN_CAT_TYPE_DESC,
                    TranCategoryRecord.FILLER);
            assertThat(TranCategoryRecord.LAYOUT.redefinitions()).isEmpty();

            int cursor = 0;
            for (FieldSpan span : spans) {
                assertThat(span.offset()).as("span %s must begin at byte %d", span.name(), cursor)
                        .isEqualTo(cursor);
                cursor = span.endOffsetExclusive();
            }
            assertThat(cursor).isEqualTo(DECLARED_RECORD_LENGTH);
        }

        @Test
        @DisplayName("dropping the trailing FILLER X(04) fails immediately, it does not shorten quietly")
        void droppingTheFillerFailsImmediately() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(DECLARED_RECORD_LENGTH,
                            TranCategoryRecord.TRAN_TYPE_CD,
                            TranCategoryRecord.TRAN_CAT_CD,
                            TranCategoryRecord.TRAN_CAT_TYPE_DESC))
                    .withMessageContaining("declares 56 byte(s)")
                    .withMessageContaining("4 byte(s) short")
                    .withMessageContaining("dropped trailing FILLER");
        }

        @Test
        @DisplayName("the FILLER is a declared span carrying no VALUE, positioned at byte 56")
        void theFillerIsADeclaredSpan() {
            assertThat(TranCategoryRecord.FILLER.offset()).isEqualTo(56);
            assertThat(TranCategoryRecord.FILLER.length()).isEqualTo(4);
            assertThat(TranCategoryRecord.FILLER.hasInitialValue())
                    .as("CVTRA04Y's FILLER X(04) declares no VALUE clause")
                    .isFalse();
            assertThat(TranCategoryRecord.FILLER.redefinition()).isFalse();
        }

        @Test
        @DisplayName("verifyDeclaredGeometry passes on the real constants, and is safe to repeat")
        void verifyDeclaredGeometryPasses() {
            TranCategoryRecord.verifyDeclaredGeometry();
            TranCategoryRecord.verifyDeclaredGeometry();
            assertThat(TranCategoryRecord.sumOfDeclaredSpanWidths()).isEqualTo(DECLARED_RECORD_LENGTH);
        }

        @Test
        @DisplayName("a key width that contradicts its two items is reported, with both widths")
        void aKeyWidthThatContradictsItsItemsIsReported() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> TranCategoryRecord.verifyGeometry(2, 4, 7, 7,
                            DECLARED_RECORD_LENGTH))
                    .withMessageContaining("declared as 7 byte(s)")
                    .withMessageContaining("occupy 6")
                    .withMessageContaining("sub-span over exactly those two items");
        }

        @Test
        @DisplayName("a 17-byte key width is reported as the CVTRA01Y conflation it almost certainly is")
        void aSeventeenByteKeyIsReportedAsTheConflation() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> TranCategoryRecord.verifyGeometry(13, 4, NAMESAKE_KEY_LENGTH,
                            NAMESAKE_KEY_LENGTH, DECLARED_RECORD_LENGTH))
                    .withMessageContaining("BEWARE THE NAMESAKE")
                    .withMessageContaining("CVTRA01Y")
                    .withMessageContaining("TRANCAT-ACCT-ID 9(11)")
                    .withMessageContaining("the two records have been conflated");
        }

        @Test
        @DisplayName("a description offset that is not the key width is reported, naming 17")
        void aDescriptionOffsetThatIsNotTheKeyWidthIsReported() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> TranCategoryRecord.verifyGeometry(2, 4, DECLARED_KEY_LENGTH,
                            NAMESAKE_KEY_LENGTH, DECLARED_RECORD_LENGTH))
                    .withMessageContaining("must begin at offset 6")
                    .withMessageContaining("declared at offset 17")
                    .withMessageContaining("An offset of 17 here");
        }

        @Test
        @DisplayName("a total of 56 rather than 60 is reported, and names the dropped FILLER")
        void aTotalThatIsNotSixtyIsReported() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> TranCategoryRecord.verifyGeometry(2, 4, DECLARED_KEY_LENGTH,
                            DECLARED_KEY_LENGTH, DECLARED_RECORD_LENGTH - 4))
                    .withMessageContaining("RECLN = 60")
                    .withMessageContaining("sum to 56")
                    .withMessageContaining("FILLER 4");
        }
    }

    @Nested
    @DisplayName("Name collision defence - three names shared with three other copybooks")
    class NameCollisionDefence {
        @Test
        @DisplayName("Collision 1: this TRAN-CAT-KEY is SIX bytes, not CVTRA01Y's seventeen")
        void collisionOneTheKeyIsSixBytesNotSeventeen() {
            assertThat(TranCategoryRecord.TRAN_CAT_KEY_NAME)
                    .as("the colliding name is carried verbatim, never disambiguated")
                    .isEqualTo("TRAN-CAT-KEY");
            assertThat(TranCategoryRecord.TRAN_CAT_KEY_LENGTH)
                    .isEqualTo(DECLARED_KEY_LENGTH)
                    .isNotEqualTo(NAMESAKE_KEY_LENGTH);
            assertThat(TranCategoryRecord.TRAN_CAT_KEY_LENGTH + NAMESAKE_ACCT_ID_LENGTH)
                    .isEqualTo(NAMESAKE_KEY_LENGTH);
        }

        @Test
        @DisplayName("Collision 1: the key is TWO items, and neither of them is an account id")
        void collisionOneTheKeyHasTwoItemsAndNoAccountId() {
            List<FieldSpan> keyItems = new ArrayList<>();
            for (FieldSpan span : TranCategoryRecord.LAYOUT.storageSpans()) {
                if (span.endOffsetExclusive() <= TranCategoryRecord.TRAN_CAT_KEY_OFFSET
                        + TranCategoryRecord.TRAN_CAT_KEY_LENGTH) {
                    keyItems.add(span);
                }
            }
            assertThat(keyItems)
                    .as("CVTRA04Y's TRAN-CAT-KEY is TRAN-TYPE-CD plus TRAN-CAT-CD and nothing else")
                    .containsExactly(TranCategoryRecord.TRAN_TYPE_CD, TranCategoryRecord.TRAN_CAT_CD)
                    .hasSize(2);

            for (FieldSpan span : TranCategoryRecord.LAYOUT.storageSpans()) {
                assertThat(span.name())
                        .as("no span of CVTRA04Y names an account identifier")
                        .doesNotContain("ACCT")
                        .doesNotStartWith("TRANCAT-");
            }
        }

        @Test
        @DisplayName("Collision 2: TRAN-TYPE-CD is at 0 and TRAN-CAT-CD at 2, not CVTRA05Y's 16 and 18")
        void collisionTwoTheItemNamesAreSharedWithCvtra05y() {
            assertThat(TranCategoryRecord.TRAN_TYPE_CD.name()).isEqualTo("TRAN-TYPE-CD");
            assertThat(TranCategoryRecord.TRAN_CAT_CD.name()).isEqualTo("TRAN-CAT-CD");

            assertThat(TranCategoryRecord.TRAN_TYPE_CD.offset())
                    .as("in CVTRA04Y this item opens the record; in CVTRA05Y it sits after TRAN-ID")
                    .isZero()
                    .isNotEqualTo(CVTRA05Y_TYPE_CD_OFFSET);
            assertThat(TranCategoryRecord.TRAN_CAT_CD.offset())
                    .isEqualTo(2)
                    .isNotEqualTo(CVTRA05Y_CAT_CD_OFFSET);

            assertThat(TranCategoryRecord.TRAN_TYPE_CD.length()).isEqualTo(2);
            assertThat(TranCategoryRecord.TRAN_CAT_CD.length()).isEqualTo(4);
        }

        @Test
        @DisplayName("Collision 3: this item is TRAN-TYPE-CD, whereas CVTRA03Y's is TRAN-TYPE")
        void collisionThreeCvtra03yDropsTheCdSuffix() {
            assertThat(TranCategoryRecord.TRAN_TYPE_CD.name())
                    .isEqualTo("TRAN-TYPE-CD")
                    .endsWith("-CD")
                    .isNotEqualTo("TRAN-TYPE");
            assertThat(TranCategoryRecord.TRAN_CAT_TYPE_DESC.name())
                    .isEqualTo("TRAN-CAT-TYPE-DESC")
                    .isNotEqualTo("TRAN-TYPE-DESC");

            assertThat(TranCategoryRecord.FILLER_OFFSET).isEqualTo(56).isNotEqualTo(52);
            assertThat(TranCategoryRecord.FILLER_LENGTH).isEqualTo(4).isNotEqualTo(8);
        }
    }

    @Nested
    @DisplayName("The TRAN-CAT-KEY sub-span - one set of six bytes, two ways of reading it")
    class CompositeKey {
        @Test
        @DisplayName("the key occupies bytes 0 to 5 and stops before the description at byte 6")
        void theKeyOccupiesTheFirstSixBytes() {
            assertThat(TranCategoryRecord.TRAN_CAT_KEY_OFFSET).isZero();
            assertThat(TranCategoryRecord.TRAN_CAT_KEY_LENGTH).isEqualTo(DECLARED_KEY_LENGTH);
            assertThat(TranCategoryRecord.TRAN_CAT_KEY_OFFSET + TranCategoryRecord.TRAN_CAT_KEY_LENGTH)
                    .as("byte 6 is the first byte beyond the key")
                    .isEqualTo(TranCategoryRecord.TRAN_CAT_TYPE_DESC_OFFSET);
            assertThat(TranCategoryRecord.TRAN_CAT_TYPE_DESC.offset())
                    .isGreaterThanOrEqualTo(TranCategoryRecord.TRAN_CAT_KEY_OFFSET
                            + TranCategoryRecord.TRAN_CAT_KEY_LENGTH);
            assertThat(TranCategoryRecord.FILLER.offset())
                    .isGreaterThan(TranCategoryRecord.TRAN_CAT_KEY_LENGTH);
        }

        @Test
        @DisplayName("the key image is the two item images concatenated, not a literal of its own")
        void theKeyImageIsTheTwoItemsConcatenated() {
            TranCategoryRecord record = decodedRow(1);

            String composed = record.fieldImage(TranCategoryRecord.TRAN_TYPE_CD)
                    + record.fieldImage(TranCategoryRecord.TRAN_CAT_CD);
            assertThat(composed).hasSize(DECLARED_KEY_LENGTH);
            assertThat(record.tranCatKeyImage()).isEqualTo(composed);
            assertThat(record.tranCatKeyBytes()).isEqualTo(composed.getBytes(ASCII));

            assertThat(TranCategoryRecord.tranCatKeyImage(record.tranTypeCd(), record.tranCatCd(),
                    ASCII)).isEqualTo(composed);
            assertThat(TranCategoryRecord.tranCatKeyBytes(record.tranTypeCd(), record.tranCatCd(),
                    ASCII)).isEqualTo(composed.getBytes(ASCII));
        }

        @Test
        @DisplayName("changing TRAN-CAT-CD changes the key image, because they are the same bytes")
        void changingAnItemChangesTheKeyImage() {
            TranCategoryRecord original = decodedRow(1);
            assertThat(original.tranCatKeyImage()).isEqualTo("010001");

            FixedWidthRecord area = original.toRecordArea();
            area.writeSpan(TranCategoryRecord.TRAN_CAT_CD, "0009");
            TranCategoryRecord mutated = TranCategoryRecord.decode(area.toByteArray(), ASCII);

            assertThat(mutated.tranCatCd()).isEqualTo(9);
            assertThat(mutated.tranCatKeyImage()).isEqualTo("010009")
                    .isNotEqualTo(original.tranCatKeyImage());
            assertThat(mutated.tranTypeCd()).isEqualTo(original.tranTypeCd());
            assertThat(mutated.tranCatTypeDesc()).isEqualTo(original.tranCatTypeDesc());
            assertThat(mutated.filler()).isEqualTo(original.filler());
            assertThat(original.tranCatKeyImage()).isEqualTo("010001");
            assertThat(original.tranCatCd()).isEqualTo(1);
        }

        @Test
        @DisplayName("writing the six key bytes rewrites both items, and nothing beyond byte 6")
        void writingTheKeyRewritesBothItems() {
            TranCategoryRecord original = decodedRow(1);

            FixedWidthRecord area = original.toRecordArea();
            area.writeString(TranCategoryRecord.TRAN_CAT_KEY_OFFSET,
                    TranCategoryRecord.TRAN_CAT_KEY_LENGTH, "070001");
            TranCategoryRecord rekeyed = TranCategoryRecord.decode(area.toByteArray(), ASCII);

            assertThat(rekeyed.tranTypeCd()).isEqualTo("07");
            assertThat(rekeyed.tranCatCd()).isEqualTo(1);
            assertThat(rekeyed.tranCatKeyImage()).isEqualTo("070001");
            assertThat(rekeyed.fieldImage(TranCategoryRecord.TRAN_TYPE_CD)).isEqualTo("07");
            assertThat(rekeyed.fieldImage(TranCategoryRecord.TRAN_CAT_CD)).isEqualTo("0001");
            assertThat(rekeyed.tranCatTypeDesc()).isEqualTo(original.tranCatTypeDesc());
            assertThat(rekeyed.filler()).isEqualTo(FIXTURE_FILLER);
        }

        @Test
        @DisplayName("every fixture key is six printable bytes, as CBTRN03C:507 DISPLAYs it")
        void everyKeyIsSixPrintableBytes() {
            for (int rowNumber = 1; rowNumber <= 18; rowNumber++) {
                byte[] key = decodedRow(rowNumber).tranCatKeyBytes();
                assertThat(key).hasSize(DECLARED_KEY_LENGTH);
                for (byte value : key) {
                    assertThat(value)
                            .as("key byte of row %d must be printable ASCII", rowNumber)
                            .isBetween((byte) 0x20, (byte) 0x7E);
                }
            }
        }

        @Test
        @DisplayName("every key in the fixture is distinct, which a 6-byte KSDS key requires")
        void everyKeyIsDistinct() {
            List<String> keys = new ArrayList<>();
            for (int rowNumber = 1; rowNumber <= 18; rowNumber++) {
                keys.add(decodedRow(rowNumber).tranCatKeyImage());
            }
            assertThat(keys).hasSize(18).doesNotHaveDuplicates();
        }
    }

    @Nested
    @DisplayName("The shipped TRANCATG fixture - all 18 rows, decoded at the copybook's offsets")
    class ShippedFixture {
        @Test
        @DisplayName("all 18 embedded rows are exactly 60 characters, so none needs widening")
        void everyEmbeddedRowIsSixtyCharacters() {
            List<String> rows = fixtureRows();
            assertThat(rows).hasSize(18);
            assertThat(rows).allSatisfy(row -> assertThat(row).hasSize(DECLARED_RECORD_LENGTH));
            assertThat(rows).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("each embedded row segments where CVTRA04Y says it does, at 0, 2, 6 and 56")
        void theEmbeddedRowsSegmentAtTheCopybookOffsets() {
            for (String row : fixtureRows()) {
                String typeCd = row.substring(0, 2);
                String catCd = row.substring(2, 6);
                String description = row.substring(6, 56);
                String filler = row.substring(56, 60);

                for (int column = 0; column < DECLARED_KEY_LENGTH; column++) {
                    assertThat(row.charAt(column))
                            .as("byte %d of row's key must be an ASCII digit", column)
                            .isBetween('0', '9');
                }
                assertThat(typeCd).hasSize(TranCategoryRecord.TRAN_TYPE_CD_LENGTH);
                assertThat(catCd).hasSize(TranCategoryRecord.TRAN_CAT_CD_LENGTH);
                assertThat(description).hasSize(DECLARED_DESC_LENGTH);
                assertThat(filler).isEqualTo(FIXTURE_FILLER);
                assertThat(description).doesNotStartWith(" ");
                assertThat(description.charAt(DECLARED_DESC_LENGTH - 1))
                        .as("byte 56 of every row is padding, no description reaches the full 50")
                        .isEqualTo(' ');
                assertThat(typeCd + catCd + description + filler).isEqualTo(row);
            }
        }

        @ParameterizedTest(name = "row {0}: TRAN-TYPE-CD {1}, TRAN-CAT-CD {2}, {3}")
        @DisplayName("every one of the 18 rows decodes field by field, description padded to 50")
        @CsvSource(delimiter = '|', value = {
            " 1 | 01 | 1 | Regular Sales Draft",
            " 2 | 01 | 2 | Regular Cash Advance",
            " 3 | 01 | 3 | Convenience Check Debit",
            " 4 | 01 | 4 | ATM Cash Advance",
            " 5 | 01 | 5 | Interest Amount",
            " 6 | 02 | 1 | Cash payment",
            " 7 | 02 | 2 | Electronic payment",
            " 8 | 02 | 3 | Check payment",
            " 9 | 03 | 1 | Credit to Account",
            "10 | 03 | 2 | Credit to Purchase balance",
            "11 | 03 | 3 | Credit to Cash balance",
            "12 | 04 | 1 | Zero dollar authorization",
            "13 | 04 | 2 | Online purchase authorization",
            "14 | 04 | 3 | Travel booking authorization",
            "15 | 05 | 1 | Refund credit",
            "16 | 06 | 1 | Fraud reversal",
            "17 | 06 | 2 | Non-fraud reversal",
            "18 | 07 | 1 | Sales draft credit adjustment",
        })
        void everyRowDecodesFieldByField(int rowNumber, String typeCd, int catCd, String description) {
            TranCategoryRecord record = decodedRow(rowNumber);

            assertThat(record.tranTypeCd()).isEqualTo(typeCd).hasSize(2);
            assertThat(record.tranCatCd()).isEqualTo(catCd);
            assertThat(record.tranCatTypeDesc())
                    .isEqualTo(padded(description, DECLARED_DESC_LENGTH))
                    .hasSize(DECLARED_DESC_LENGTH)
                    .startsWith(description);
            assertThat(record.filler()).isEqualTo(FIXTURE_FILLER);

            assertThat(record.fieldImage(TranCategoryRecord.TRAN_TYPE_CD)).isEqualTo(typeCd);
            assertThat(record.fieldImage(TranCategoryRecord.TRAN_CAT_CD))
                    .isEqualTo(zoned(catCd, TranCategoryRecord.TRAN_CAT_CD_LENGTH));
            assertThat(record.tranCatKeyImage())
                    .isEqualTo(typeCd + zoned(catCd, TranCategoryRecord.TRAN_CAT_CD_LENGTH))
                    .hasSize(DECLARED_KEY_LENGTH);
            assertThat(record.toImage()).isEqualTo(fixtureRow(rowNumber));
            assertThat(record.toByteArray()).hasSize(DECLARED_RECORD_LENGTH);
        }

        @Test
        @DisplayName("row 1's description keeps all 31 of its trailing spaces on read")
        void rowOneDescriptionIsUntrimmed() {
            TranCategoryRecord record = decodedRow(1);
            assertThat(record.tranCatTypeDesc())
                    .isEqualTo("Regular Sales Draft" + " ".repeat(31))
                    .hasSize(DECLARED_DESC_LENGTH);
            assertThat("Regular Sales Draft".length() + 31).isEqualTo(DECLARED_DESC_LENGTH);
            assertThat(record.charset()).isEqualTo(ASCII);
        }

        @Test
        @DisplayName("named spans read back at their offsets, and every array handed out is a copy")
        void namedSpansReadBackAndArraysAreCopies() {
            TranCategoryRecord record = decodedRow(1);

            assertThat(record.fieldBytes(TranCategoryRecord.TRAN_TYPE_CD))
                    .isEqualTo("01".getBytes(ASCII));
            assertThat(record.fieldBytes(TranCategoryRecord.FILLER))
                    .isEqualTo(FIXTURE_FILLER.getBytes(ASCII));
            assertThat(record.fieldImage(TranCategoryRecord.TRAN_CAT_TYPE_DESC))
                    .isEqualTo(padded("Regular Sales Draft", DECLARED_DESC_LENGTH));

            byte[] whole = record.toByteArray();
            whole[0] = (byte) '9';
            assertThat(record.tranTypeCd()).isEqualTo("01");
            byte[] filler = record.fillerBytes();
            filler[0] = (byte) '9';
            assertThat(record.filler()).isEqualTo(FIXTURE_FILLER);
            byte[] keyBytes = record.tranCatKeyBytes();
            keyBytes[0] = (byte) '9';
            assertThat(record.tranCatKeyImage()).isEqualTo("010001");
            FixedWidthRecord area = record.toRecordArea();
            area.writeSpan(TranCategoryRecord.TRAN_TYPE_CD, "99");
            assertThat(record.tranTypeCd()).isEqualTo("01");
            assertThat(area.readSpan(TranCategoryRecord.TRAN_TYPE_CD)).isEqualTo("99");
            assertThat(area.recordLength()).isEqualTo(DECLARED_RECORD_LENGTH);
        }

        @Test
        @DisplayName("the two widest descriptions in the fixture are exactly 29 characters")
        void theTwoWidestDescriptionsAreExactlyTwentyNine() {
            int widest = 0;
            List<String> widestDescriptions = new ArrayList<>();
            for (int rowNumber = 1; rowNumber <= 18; rowNumber++) {
                String stored = decodedRow(rowNumber).tranCatTypeDesc();
                int textLength = DECLARED_DESC_LENGTH;
                while (textLength > 0 && stored.charAt(textLength - 1) == ' ') {
                    textLength--;
                }
                assertThat(textLength).isLessThanOrEqualTo(REPORT_CAT_DESC_LENGTH);
                if (textLength > widest) {
                    widest = textLength;
                    widestDescriptions.clear();
                }
                if (textLength == widest) {
                    widestDescriptions.add(stored.substring(0, textLength));
                }
            }
            assertThat(widest).isEqualTo(REPORT_CAT_DESC_LENGTH);
            assertThat(widestDescriptions).containsExactly(
                    "Online purchase authorization",
                    "Sales draft credit adjustment");
        }
    }

    @Nested
    @DisplayName("COBOL MOVE semantics - PIC X truncates right, PIC 9 truncates left")
    class MoveSemantics {
        @Test
        @DisplayName("X(50) into X(29): an exactly-29 description survives the report move intact")
        void anExactlyTwentyNineDescriptionSurvivesIntact() {
            assertThat(movedIntoReportField(decodedRow(13).tranCatTypeDesc()))
                    .isEqualTo("Online purchase authorization")
                    .hasSize(REPORT_CAT_DESC_LENGTH)
                    .doesNotEndWith(" ");
            assertThat(movedIntoReportField(decodedRow(18).tranCatTypeDesc()))
                    .isEqualTo("Sales draft credit adjustment")
                    .hasSize(REPORT_CAT_DESC_LENGTH)
                    .doesNotEndWith(" ");
        }

        @Test
        @DisplayName("X(50) into X(29): a short description arrives padded, losing no character")
        void aShortDescriptionArrivesPadded() {
            assertThat(movedIntoReportField(decodedRow(1).tranCatTypeDesc()))
                    .isEqualTo(padded("Regular Sales Draft", REPORT_CAT_DESC_LENGTH))
                    .hasSize(REPORT_CAT_DESC_LENGTH);
            assertThat(movedIntoReportField(decodedRow(6).tranCatTypeDesc()))
                    .isEqualTo(padded("Cash payment", REPORT_CAT_DESC_LENGTH))
                    .hasSize(REPORT_CAT_DESC_LENGTH);
        }

        @Test
        @DisplayName("X(50) into X(29): a 45-character description loses its trailing 16 characters")
        void anOverLongDescriptionLosesItsTrailingCharacters() {
            assertThat(SYNTHETIC_LONG_DESC).hasSize(45);
            assertThat(SYNTHETIC_SURVIVING_29).hasSize(REPORT_CAT_DESC_LENGTH);
            assertThat(SYNTHETIC_DISCARDED_TAIL).hasSize(16);
            assertThat(SYNTHETIC_SURVIVING_29 + SYNTHETIC_DISCARDED_TAIL).isEqualTo(SYNTHETIC_LONG_DESC);

            TranCategoryRecord built = TranCategoryRecord.of("09", 42, SYNTHETIC_LONG_DESC, ASCII);
            assertThat(built.tranCatTypeDesc())
                    .isEqualTo(padded(SYNTHETIC_LONG_DESC, DECLARED_DESC_LENGTH))
                    .hasSize(DECLARED_DESC_LENGTH);

            String moved = movedIntoReportField(built.tranCatTypeDesc());
            assertThat(moved)
                    .isEqualTo(SYNTHETIC_SURVIVING_29)
                    .hasSize(REPORT_CAT_DESC_LENGTH)
                    .isNotEqualTo(SYNTHETIC_LONG_DESC);
            assertThat(built.tranCatTypeDesc()).contains(SYNTHETIC_DISCARDED_TAIL);
            assertThat(moved).doesNotContain(SYNTHETIC_DISCARDED_TAIL);
            assertThat(SYNTHETIC_LONG_DESC).startsWith(moved);
        }

        @Test
        @DisplayName("X(50) into X(29): 30 characters lose exactly one, which fixes the boundary at 29")
        void thirtyCharactersLoseExactlyOne() {
            assertThat(SYNTHETIC_THIRTY_DESC).hasSize(REPORT_CAT_DESC_LENGTH + 1);

            TranCategoryRecord built = TranCategoryRecord.of("09", 43, SYNTHETIC_THIRTY_DESC, ASCII);
            String moved = movedIntoReportField(built.tranCatTypeDesc());

            assertThat(moved).hasSize(REPORT_CAT_DESC_LENGTH)
                    .isEqualTo(SYNTHETIC_THIRTY_DESC.substring(0, REPORT_CAT_DESC_LENGTH))
                    .isEqualTo(SYNTHETIC_SURVIVING_29);
            assertThat(SYNTHETIC_THIRTY_DESC).isEqualTo(moved + "s");
        }

        @Test
        @DisplayName("writing X(50): a short description is space-padded on the right to all 50 bytes")
        void aShortDescriptionIsPaddedToFifty() {
            TranCategoryRecord built = TranCategoryRecord.of("01", 1, "Refund credit", ASCII);

            assertThat(built.tranCatTypeDesc())
                    .isEqualTo("Refund credit" + " ".repeat(37))
                    .hasSize(DECLARED_DESC_LENGTH);
            assertThat(built.fieldImage(TranCategoryRecord.TRAN_CAT_TYPE_DESC))
                    .isEqualTo(padded("Refund credit", DECLARED_DESC_LENGTH));
            assertThat(built.toImage().substring(TranCategoryRecord.TRAN_CAT_TYPE_DESC_OFFSET,
                    TranCategoryRecord.FILLER_OFFSET))
                    .isEqualTo(padded("Refund credit", DECLARED_DESC_LENGTH));
            assertThat(TranCategoryRecord.of("01", 1, "", ASCII).tranCatTypeDesc())
                    .isEqualTo(" ".repeat(DECLARED_DESC_LENGTH));
        }

        @Test
        @DisplayName("writing X(50): a 51-character description is truncated on the right to 50")
        void anOverWideDescriptionIsTruncatedToFifty() {
            String tooLong = "D".repeat(DECLARED_DESC_LENGTH) + "X";
            assertThat(tooLong).hasSize(51);

            TranCategoryRecord built = TranCategoryRecord.of("01", 1, tooLong, ASCII);
            assertThat(built.tranCatTypeDesc())
                    .isEqualTo("D".repeat(DECLARED_DESC_LENGTH))
                    .hasSize(DECLARED_DESC_LENGTH)
                    .doesNotContain("X");
            assertThat(built.toByteArray()).hasSize(DECLARED_RECORD_LENGTH);
        }

        @Test
        @DisplayName("writing X(02): a one-character type code is space-padded on the right to two")
        void aShortTypeCodeIsPaddedToTwo() {
            assertThat(TranCategoryRecord.of("1", 1, "x", ASCII).tranTypeCd()).isEqualTo("1 ");
            assertThat(TranCategoryRecord.tranCatKeyImage("1", 1, ASCII)).isEqualTo("1 0001");
            assertThat(TranCategoryRecord.tranCatKeyImage("", 0, ASCII))
                    .isEqualTo("  0000")
                    .hasSize(DECLARED_KEY_LENGTH);
        }

        @Test
        @DisplayName("writing X(02): a three-character type code is truncated on the right to two")
        void anOverWideTypeCodeIsTruncatedToTwo() {
            assertThat(TranCategoryRecord.of("012", 1, "x", ASCII).tranTypeCd())
                    .isEqualTo("01")
                    .isNotEqualTo("12");
            assertThat(TranCategoryRecord.tranCatKeyImage("012", 1, ASCII)).isEqualTo("010001");
            assertThat(TranCategoryRecord.tranCatKeyBytes("012", 1, ASCII))
                    .isEqualTo("010001".getBytes(ASCII));
        }

        @Test
        @DisplayName("writing 9(04): a one-digit category code is zero-filled on the LEFT to four")
        void aShortCategoryCodeIsZeroFilledOnTheLeft() {
            TranCategoryRecord built = TranCategoryRecord.of("01", 1, "x", ASCII);
            assertThat(built.fieldImage(TranCategoryRecord.TRAN_CAT_CD)).isEqualTo("0001");
            assertThat(built.tranCatKeyImage()).isEqualTo("010001");
            assertThat(TranCategoryRecord.tranCatKeyImage("01", 0, ASCII)).isEqualTo("010000");
            assertThat(TranCategoryRecord.tranCatKeyImage("01", 42, ASCII)).isEqualTo("010042");
            assertThat(TranCategoryRecord.tranCatKeyImage("01", 9999, ASCII)).isEqualTo("019999");
        }

        @Test
        @DisplayName("writing 9(04): 12345 keeps its LOW-order four digits, so 2345 and never 1234")
        void anOverWideCategoryCodeKeepsItsLowOrderDigits() {
            assertThat(TranCategoryRecord.tranCatKeyImage("01", 12345, ASCII))
                    .isEqualTo("012345")
                    .isNotEqualTo("011234");
            assertThat(TranCategoryRecord.of("01", 12345, "x", ASCII))
                    .extracting(TranCategoryRecord::tranCatCd)
                    .isEqualTo(2345);
            assertThat(TranCategoryRecord.of("01", 10000, "x", ASCII).tranCatCd())
                    .as("10000 loses its leading 1 and reads back as 0")
                    .isZero();
        }

        @Test
        @DisplayName("a negative category code has no PIC 9(04) representation and is rejected")
        void aNegativeCategoryCodeIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TranCategoryRecord.of("01", -1, "x", ASCII))
                    .withMessageContaining("PIC 9(04)")
                    .withMessageContaining("unsigned picture with no sign position");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TranCategoryRecord.tranCatKeyImage("01", -1, ASCII));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TranCategoryRecord.tranCatKeyBytes("01", -1, ASCII));
        }
    }

    @Nested
    @DisplayName("The FILLER read and write asymmetry - spaces when built, zeros when read")
    class FillerAsymmetry {
        @Test
        @DisplayName("a freshly built record space-fills FILLER X(04) at byte 56")
        void aFreshlyBuiltRecordSpaceFillsTheFiller() {
            TranCategoryRecord built = TranCategoryRecord.of("01", 1, "Regular Sales Draft", ASCII);

            assertThat(built.filler()).isEqualTo(BLANK_FILLER).hasSize(4);
            assertThat(built.fillerBytes()).isEqualTo(BLANK_FILLER.getBytes(ASCII));
            assertThat(built.fieldImage(TranCategoryRecord.FILLER)).isEqualTo(BLANK_FILLER);
            assertThat(built.toImage().substring(TranCategoryRecord.FILLER_OFFSET))
                    .isEqualTo(BLANK_FILLER);
            assertThat(built.toByteArray()).hasSize(DECLARED_RECORD_LENGTH);
            assertThat(built.toImage())
                    .isEqualTo("01" + "0001" + padded("Regular Sales Draft", DECLARED_DESC_LENGTH)
                            + BLANK_FILLER)
                    .hasSize(DECLARED_RECORD_LENGTH);
        }

        @Test
        @DisplayName("a decoded row keeps its four ASCII zeros and re-encodes byte for byte")
        void aDecodedRowKeepsItsZeroFiller() {
            for (int rowNumber = 1; rowNumber <= 18; rowNumber++) {
                String row = fixtureRow(rowNumber);
                TranCategoryRecord record = decodedRow(rowNumber);

                assertThat(record.filler())
                        .as("row %d stores ASCII zeros in its FILLER, not spaces", rowNumber)
                        .isEqualTo(FIXTURE_FILLER)
                        .isNotEqualTo(BLANK_FILLER);
                assertThat(record.toByteArray()).isEqualTo(row.getBytes(ASCII));
                assertThat(record.toImage()).isEqualTo(row);
                assertThat(record.toByteArray()).hasSize(DECLARED_RECORD_LENGTH);
            }
        }

        @Test
        @DisplayName("the two paths differ only in the FILLER, which is exactly the point")
        void theTwoPathsDifferOnlyInTheFiller() {
            TranCategoryRecord fromDataset = decodedRow(1);
            TranCategoryRecord built = TranCategoryRecord.of("01", 1, "Regular Sales Draft", ASCII);

            assertThat(built.tranCatKeyImage()).isEqualTo(fromDataset.tranCatKeyImage());
            assertThat(built.tranTypeCd()).isEqualTo(fromDataset.tranTypeCd());
            assertThat(built.tranCatCd()).isEqualTo(fromDataset.tranCatCd());
            assertThat(built.tranCatTypeDesc()).isEqualTo(fromDataset.tranCatTypeDesc());
            assertThat(built.filler()).isNotEqualTo(fromDataset.filler());
            assertThat(built.toImage()).isNotEqualTo(fromDataset.toImage());
            assertThat(built.toImage().substring(0, TranCategoryRecord.FILLER_OFFSET))
                    .isEqualTo(fromDataset.toImage().substring(0, TranCategoryRecord.FILLER_OFFSET));
        }
    }

    @Nested
    @DisplayName("Decoding guards - a malformed row is reported, never quietly accepted")
    class DecodingGuards {
        @Test
        @DisplayName("a row of the wrong width is rejected rather than padded or clipped")
        void aWrongWidthRowIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TranCategoryRecord.decode(new byte[59], ASCII))
                    .withMessageContaining("59 byte(s)")
                    .withMessageContaining("exactly 60");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TranCategoryRecord.decode(new byte[61], ASCII))
                    .withMessageContaining("61 byte(s)");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TranCategoryRecord.decode(new byte[0], ASCII));
        }

        @Test
        @DisplayName("a TRAN-CAT-CD that does not hold four digits is rejected, not silently zeroed")
        void aNonNumericCategoryCodeIsRejected() {
            String corrupt = "01" + "ABCD"
                    + padded("Regular Sales Draft", DECLARED_DESC_LENGTH) + FIXTURE_FILLER;
            assertThat(corrupt).hasSize(DECLARED_RECORD_LENGTH);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TranCategoryRecord.decode(corrupt.getBytes(ASCII), ASCII));

            String blankKey = "  " + "    "
                    + padded("Regular Sales Draft", DECLARED_DESC_LENGTH) + FIXTURE_FILLER;
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TranCategoryRecord.decode(blankKey.getBytes(ASCII), ASCII));
        }

        @Test
        @DisplayName("null is rejected at every entry point that requires a value")
        void nullIsRejected() {
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCategoryRecord.decode(null, ASCII))
                    .withMessageContaining("record bytes are required");
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCategoryRecord.decode(new byte[DECLARED_RECORD_LENGTH], null))
                    .withMessageContaining("never a platform default");
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCategoryRecord.of(null, 1, "x", ASCII))
                    .withMessageContaining("TRAN-TYPE-CD");
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCategoryRecord.of("01", 1, null, ASCII))
                    .withMessageContaining("TRAN-CAT-TYPE-DESC");
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCategoryRecord.of("01", 1, "x", null))
                    .withMessageContaining("never derived from the platform");
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCategoryRecord.tranCatKeyImage(null, 1, ASCII));
            assertThatNullPointerException()
                    .isThrownBy(() -> TranCategoryRecord.tranCatKeyImage("01", 1, null));
            assertThatNullPointerException()
                    .isThrownBy(() -> decodedRow(1).fieldImage(null))
                    .withMessageContaining("field descriptor is required");
            assertThatNullPointerException()
                    .isThrownBy(() -> decodedRow(1).fieldBytes(null))
                    .withMessageContaining("field descriptor is required");
        }

        @Test
        @DisplayName("the code page is honoured: the same characters, different bytes, same values")
        void theCodePageIsHonoured() {
            TranCategoryRecord ascii = decodedRow(1);
            TranCategoryRecord ebcdic = TranCategoryRecord.decode(fixtureRow(1).getBytes(EBCDIC),
                    EBCDIC);

            assertThat(ebcdic.charset()).isEqualTo(EBCDIC);
            assertThat(ebcdic.toByteArray()).isNotEqualTo(ascii.toByteArray());
            assertThat(ebcdic.tranTypeCd()).isEqualTo(ascii.tranTypeCd());
            assertThat(ebcdic.tranCatCd()).isEqualTo(ascii.tranCatCd());
            assertThat(ebcdic.tranCatTypeDesc()).isEqualTo(ascii.tranCatTypeDesc());
            assertThat(ebcdic.tranCatKeyImage()).isEqualTo(ascii.tranCatKeyImage());
            assertThat(ebcdic.toImage()).isEqualTo(ascii.toImage());
        }

        @Test
        @DisplayName("ASCII bytes read as EBCDIC are not digits, and the row is refused")
        void aRowThatWillNotDecodeIsRejected() {
            byte[] asciiRow = fixtureRow(1).getBytes(ASCII);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TranCategoryRecord.decode(asciiRow, EBCDIC))
                    .withMessageContaining("is not a digit");
        }
    }

    @Nested
    @DisplayName("Value semantics and diagnostic rendering")
    class ValueSemanticsAndRendering {
        @Test
        @DisplayName("two records over the same bytes and the same code page are equal")
        void identicalBytesAndCodePageAreEqual() {
            byte[] row = fixtureRow(1).getBytes(ASCII);
            assertThat(TranCategoryRecord.decode(row, ASCII))
                    .isEqualTo(TranCategoryRecord.decode(row, ASCII))
                    .hasSameHashCodeAs(TranCategoryRecord.decode(row, ASCII));
        }

        @Test
        @DisplayName("a record equals itself without comparing a single byte")
        void aRecordEqualsItself() {
            TranCategoryRecord record = decodedRow(1);
            assertThat(record.equals(record)).isTrue();
        }

        @Test
        @DisplayName("nothing that is not a TranCategoryRecord is ever equal to one")
        void anotherTypeIsNeverEqual() {
            TranCategoryRecord record = decodedRow(1);
            assertThat(record.equals(null)).isFalse();
            assertThat(record.equals("010001")).isFalse();
            assertThat(record.equals(record.toImage())).isFalse();
            assertThat(record.equals(Integer.valueOf(1))).isFalse();
        }

        @Test
        @DisplayName("two rows with different keys are unequal, and hash differently")
        void differentRowsAreUnequal() {
            TranCategoryRecord first = decodedRow(1);
            TranCategoryRecord second = decodedRow(2);
            assertThat(first).isNotEqualTo(second);
            assertThat(first.hashCode()).isNotEqualTo(second.hashCode());
        }

        @Test
        @DisplayName("identical bytes under a different code page are NOT the same record")
        void aDifferentCodePageIsNotEqual() {
            byte[] row = fixtureRow(1).getBytes(ASCII);
            TranCategoryRecord underAscii = TranCategoryRecord.decode(row, ASCII);
            TranCategoryRecord underLatin1 = TranCategoryRecord.decode(row,
                    StandardCharsets.ISO_8859_1);

            assertThat(underAscii.toByteArray()).isEqualTo(underLatin1.toByteArray());
            assertThat(underAscii.tranCatKeyImage()).isEqualTo(underLatin1.tranCatKeyImage());
            assertThat(underAscii).isNotEqualTo(underLatin1);
            assertThat(underLatin1).isNotEqualTo(underAscii);
        }

        @Test
        @DisplayName("a difference confined to the FILLER is never mistaken for equality")
        void aFillerDifferenceIsNotEqual() {
            byte[] fromDataset = fixtureRow(1).getBytes(ASCII);
            byte[] blanked = fixtureRow(1).getBytes(ASCII);
            for (int index = TranCategoryRecord.FILLER_OFFSET;
                    index < TranCategoryRecord.RECORD_LENGTH; index++) {
                blanked[index] = (byte) ' ';
            }
            TranCategoryRecord stored = TranCategoryRecord.decode(fromDataset, ASCII);
            TranCategoryRecord reblanked = TranCategoryRecord.decode(blanked, ASCII);

            assertThat(stored.tranCatKeyImage()).isEqualTo(reblanked.tranCatKeyImage());
            assertThat(stored.tranCatTypeDesc()).isEqualTo(reblanked.tranCatTypeDesc());
            assertThat(stored.tranCatCd()).isEqualTo(reblanked.tranCatCd());
            assertThat(stored).isNotEqualTo(reblanked);
        }

        @Test
        @DisplayName("the rendering names every COBOL item and keeps the padding visible")
        void theRenderingNamesEveryItem() {
            String rendered = decodedRow(1).toString();
            assertThat(rendered)
                    .startsWith("TranCategoryRecord[")
                    .contains(TranCategoryRecord.TRAN_CAT_KEY_NAME + "='010001'")
                    .contains("TRAN-TYPE-CD='01'")
                    .contains("TRAN-CAT-CD=1")
                    .contains("TRAN-CAT-TYPE-DESC='"
                            + padded("Regular Sales Draft", DECLARED_DESC_LENGTH) + "'")
                    .contains("FILLER='" + FIXTURE_FILLER + "'")
                    .contains("charset=US-ASCII")
                    .endsWith("]");
            assertThat(rendered).isEqualTo(decodedRow(1).toString()).doesNotContain("@");
        }

        @Test
        @DisplayName("both whole-record accessors report exactly 60, in bytes and in characters")
        void bothWholeRecordAccessorsReportSixty() {
            for (int rowNumber = 1; rowNumber <= 18; rowNumber++) {
                TranCategoryRecord record = decodedRow(rowNumber);
                assertThat(record.toByteArray()).hasSize(DECLARED_RECORD_LENGTH);
                assertThat(record.toImage()).hasSize(DECLARED_RECORD_LENGTH);
                assertThat(record.toRecordArea().recordLength()).isEqualTo(DECLARED_RECORD_LENGTH);
            }
            TranCategoryRecord built = TranCategoryRecord.of("01", 1, "x", ASCII);
            assertThat(built.toByteArray()).hasSize(DECLARED_RECORD_LENGTH);
            assertThat(built.toImage()).hasSize(DECLARED_RECORD_LENGTH);
        }
    }

    @Nested
    @DisplayName("Structural guards - what this type may never declare")
    class StructuralGuards {
        private static final String BIG_DECIMAL = "java.math.BigDecimal";

        private static List<Field> declaredFields() {
            List<Field> fields = new ArrayList<>();
            for (Field field : TranCategoryRecord.class.getDeclaredFields()) {
                if (!field.isSynthetic() && !field.getName().startsWith("$")) {
                    fields.add(field);
                }
            }
            assertThat(fields).as("the guard must find real fields to vouch for").isNotEmpty();
            return fields;
        }

        private static List<Method> declaredMethods() {
            List<Method> methods = new ArrayList<>();
            for (Method method : TranCategoryRecord.class.getDeclaredMethods()) {
                if (!method.isSynthetic() && !method.getName().startsWith("$")) {
                    methods.add(method);
                }
            }
            assertThat(methods).as("the guard must find real methods to vouch for").isNotEmpty();
            return methods;
        }

        @Test
        @DisplayName("no field, parameter or return type is double or float")
        void noBinaryFloatingPointAnywhere() {
            for (Field field : declaredFields()) {
                assertThat(field.getType())
                        .as("field %s must not be a binary floating-point type", field.getName())
                        .isNotIn(double.class, float.class, Double.class, Float.class);
            }
            for (Method method : declaredMethods()) {
                assertThat(method.getReturnType())
                        .as("method %s must not return a binary floating-point type", method.getName())
                        .isNotIn(double.class, float.class, Double.class, Float.class);
                assertThat(method.getParameterTypes())
                        .doesNotContain(double.class, float.class, Double.class, Float.class);
            }
        }

        @Test
        @DisplayName("no accessor returns an arbitrary-precision decimal, because CVTRA04Y has none")
        void noArbitraryPrecisionDecimalAccessor() {
            for (Field field : declaredFields()) {
                assertThat(field.getType().getName())
                        .as("field %s must not be an arbitrary-precision decimal", field.getName())
                        .isNotEqualTo(BIG_DECIMAL);
            }
            for (Method method : declaredMethods()) {
                assertThat(method.getReturnType().getName())
                        .as("accessor %s must not return an arbitrary-precision decimal",
                                method.getName())
                        .isNotEqualTo(BIG_DECIMAL);
                for (Class<?> parameter : method.getParameterTypes()) {
                    assertThat(parameter.getName()).isNotEqualTo(BIG_DECIMAL);
                }
            }
            for (FieldSpan span : TranCategoryRecord.LAYOUT.storageSpans()) {
                assertThat(span.kind())
                        .as("span %s must not be a signed scaled span", span.name())
                        .isNotEqualTo(PictureKind.SIGNED_SCALED)
                        .isIn(PictureKind.ALPHANUMERIC, PictureKind.UNSIGNED_NUMERIC,
                                PictureKind.FILLER);
            }
        }

        @Test
        @DisplayName("no persistence or framework mapping is declared anywhere on the type")
        void noPersistenceMapping() {
            List<String> annotationTypes = new ArrayList<>();
            for (Annotation annotation : TranCategoryRecord.class.getAnnotations()) {
                annotationTypes.add(annotation.annotationType().getName());
            }
            for (Field field : declaredFields()) {
                for (Annotation annotation : field.getAnnotations()) {
                    annotationTypes.add(annotation.annotationType().getName());
                }
            }
            for (Method method : declaredMethods()) {
                for (Annotation annotation : method.getAnnotations()) {
                    if (!Override.class.equals(annotation.annotationType())) {
                        annotationTypes.add(annotation.annotationType().getName());
                    }
                }
            }

            assertThat(annotationTypes)
                    .as("a record model carries no framework annotation of any kind")
                    .isEmpty();
            assertThat(annotationTypes).noneMatch(name -> name.startsWith("jakarta.persistence.")
                    || name.startsWith("javax.persistence.")
                    || name.startsWith("org.springframework."));
        }

        @Test
        @DisplayName("every field is final, and every instance field is private")
        void everyFieldIsFinalAndInstanceFieldsArePrivate() {
            for (Field field : declaredFields()) {
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("field %s must be final", field.getName())
                        .isTrue();
                if (!Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isPrivate(field.getModifiers()))
                            .as("instance field %s must be private", field.getName())
                            .isTrue();
                }
            }
        }

        @Test
        @DisplayName("the type is final, so no subclass can weaken its byte contract")
        void theTypeIsFinal() {
            assertThat(Modifier.isFinal(TranCategoryRecord.class.getModifiers())).isTrue();
            assertThat(TranCategoryRecord.class.getPackageName())
                    .isEqualTo("com.vsergeychik.carddemo.transaction.model");
        }
    }
}
