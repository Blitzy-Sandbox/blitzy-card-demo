package com.vsergeychik.carddemo.account.model;

import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link DisclosureGroupRecord}, the Java form of {@code app/cpy/CVTRA02Y.cpy} - the
 * disclosure group record, exactly 50 bytes, consumed by exactly one class: the interest calculator
 * translated from {@code app/cbl/CBACT04C.cbl}.
 */
@DisplayName("DisclosureGroupRecord - CVTRA02Y DIS-GROUP-RECORD, 50 bytes, 16-byte key, rate at 16")
class DisclosureGroupRecordTest {
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    private static final Charset EBCDIC = Charset.forName("IBM037");

    private static final String FIXTURE = "/fixtures/discgrp.txt";

    private static final int FIXTURE_ROW_COUNT = 51;

    private static final int FIXTURE_ROW_WIDTH = 50;

    private static final String GROUP_ID_A = "A000000000";

    private static final String GROUP_ID_DEFAULT = "DEFAULT   ";

    private static final String GROUP_ID_ZEROAPR = "ZEROAPR   ";

    private static final int ROWS_PER_GROUP_ID = 17;

    private static final String FIXTURE_FILLER = "0".repeat(DisclosureGroupRecord.FILLER_LENGTH);

    private static final String ROW_1 = GROUP_ID_A + "01" + "0001" + "00150{" + FIXTURE_FILLER;

    private static final String ROW_1_RATE_IMAGE = "00150{";

    private static final String ROW_1_RATE_IMAGE_UNDER_A_17_BYTE_KEY = "0150{0";

    private static final String RATE_ZERO_IMAGE = "00000{";

    private static final int ROWS_WITH_A_ZERO_RATE = 30;

    private static final int ROWS_WITH_A_15_PERCENT_RATE = 15;

    private static final int ROWS_WITH_A_25_PERCENT_RATE = 6;

    private static List<String> fixtureRows() {
        List<String> rows = new ArrayList<>();
        try (InputStream stream = DisclosureGroupRecordTest.class.getResourceAsStream(FIXTURE)) {
            assertThat(stream).as("fixture %s must be on the test classpath", FIXTURE).isNotNull();
            String content = new String(stream.readAllBytes(), ASCII);
            for (String line : content.split("\n", -1)) {
                if (!line.isEmpty()) {
                    rows.add(line);
                }
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("Unable to read the DISCGRP fixture " + FIXTURE, failure);
        }
        return rows;
    }

    private static DisclosureGroupRecord row1() {
        return DisclosureGroupRecord.decode(ROW_1, ASCII);
    }

    private static String row(String groupId, String typeCd, String catCd, String rate,
            String filler) {
        String image = groupId + typeCd + catCd + rate + filler;
        assertThat(image).as("a hand-built row must be exactly the declared record width")
                .hasSize(DisclosureGroupRecord.RECORD_LENGTH);
        return image;
    }

    private static DisclosureGroupRecord withRateImage(String rateImage) {
        return DisclosureGroupRecord.decode(
                row(GROUP_ID_A, "01", "0001", rateImage, FIXTURE_FILLER), ASCII);
    }

    @Nested
    @DisplayName("Declared geometry - the copybook's own arithmetic, gates G19 and G21")
    class DeclaredGeometry {
        @Test
        @DisplayName("The record is 50 bytes, and the five declared items sum to exactly that")
        void theRecordIsFiftyBytes() {
            assertThat(DisclosureGroupRecord.RECORD_LENGTH).isEqualTo(50);
            assertThat(DisclosureGroupRecord.layout().recordLength()).isEqualTo(50);
            assertThat(new DisclosureGroupRecord(ASCII).recordLength()).isEqualTo(50);

            int handSum = DisclosureGroupRecord.DIS_ACCT_GROUP_ID_LENGTH
                    + DisclosureGroupRecord.DIS_TRAN_TYPE_CD_LENGTH
                    + DisclosureGroupRecord.DIS_TRAN_CAT_CD_LENGTH
                    + DisclosureGroupRecord.DIS_INT_RATE_LENGTH
                    + DisclosureGroupRecord.FILLER_LENGTH;
            assertThat(handSum).isEqualTo(DisclosureGroupRecord.RECORD_LENGTH);

            assertThat(new DisclosureGroupRecord(ASCII).encode())
                    .hasSize(DisclosureGroupRecord.RECORD_LENGTH);
            assertThat(new DisclosureGroupRecord(ASCII).encodeToString())
                    .hasSize(DisclosureGroupRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("DIS-GROUP-KEY spans bytes 0 to 15 and is exactly 16 bytes")
        void theKeyIsSixteenBytesStartingAtZero() {
            assertThat(DisclosureGroupRecord.DIS_GROUP_KEY_OFFSET).isZero();
            assertThat(DisclosureGroupRecord.DIS_GROUP_KEY_LENGTH).isEqualTo(16);
            assertThat(DisclosureGroupRecord.DIS_ACCT_GROUP_ID_LENGTH
                    + DisclosureGroupRecord.DIS_TRAN_TYPE_CD_LENGTH
                    + DisclosureGroupRecord.DIS_TRAN_CAT_CD_LENGTH).isEqualTo(16);

            FieldSpan key = DisclosureGroupRecord.layout().span("DIS-GROUP-KEY");
            assertThat(key.offset()).isZero();
            assertThat(key.length()).isEqualTo(16);
            assertThat(key.endOffsetExclusive()).isEqualTo(16);

            assertThat(row1().disGroupKey()).hasSize(16).isEqualTo("A000000000" + "01" + "0001");
            assertThat(row1().disGroupKeyBytes()).hasSize(16);
        }

        @Test
        @DisplayName("DIS-INT-RATE starts at byte 16 - the single offset a 17-byte key gets wrong")
        void theRateStartsAtByteSixteen() {
            assertThat(DisclosureGroupRecord.DIS_INT_RATE_OFFSET).isEqualTo(16);
            assertThat(DisclosureGroupRecord.layout().span("DIS-INT-RATE").offset()).isEqualTo(16);
            assertThat(DisclosureGroupRecord.DIS_INT_RATE_OFFSET)
                    .as("the rate begins immediately after the 16-byte key")
                    .isEqualTo(DisclosureGroupRecord.DIS_GROUP_KEY_LENGTH);

            assertThat(ROW_1.substring(16, 22)).isEqualTo(ROW_1_RATE_IMAGE);
            assertThat(row1().disIntRateImage()).isEqualTo(ROW_1_RATE_IMAGE);
        }

        @Test
        @DisplayName("Every item sits at its copybook offset, contiguously from byte 0")
        void everyItemSitsAtItsCopybookOffset() {
            assertThat(DisclosureGroupRecord.DIS_ACCT_GROUP_ID_OFFSET).isZero();
            assertThat(DisclosureGroupRecord.DIS_TRAN_TYPE_CD_OFFSET).isEqualTo(10);
            assertThat(DisclosureGroupRecord.DIS_TRAN_CAT_CD_OFFSET).isEqualTo(12);
            assertThat(DisclosureGroupRecord.DIS_INT_RATE_OFFSET).isEqualTo(16);
            assertThat(DisclosureGroupRecord.FILLER_OFFSET).isEqualTo(22);

            assertThat(DisclosureGroupRecord.DIS_TRAN_TYPE_CD_OFFSET)
                    .isEqualTo(DisclosureGroupRecord.DIS_ACCT_GROUP_ID_OFFSET
                            + DisclosureGroupRecord.DIS_ACCT_GROUP_ID_LENGTH);
            assertThat(DisclosureGroupRecord.DIS_TRAN_CAT_CD_OFFSET)
                    .isEqualTo(DisclosureGroupRecord.DIS_TRAN_TYPE_CD_OFFSET
                            + DisclosureGroupRecord.DIS_TRAN_TYPE_CD_LENGTH);
            assertThat(DisclosureGroupRecord.DIS_INT_RATE_OFFSET)
                    .isEqualTo(DisclosureGroupRecord.DIS_TRAN_CAT_CD_OFFSET
                            + DisclosureGroupRecord.DIS_TRAN_CAT_CD_LENGTH);
            assertThat(DisclosureGroupRecord.FILLER_OFFSET)
                    .isEqualTo(DisclosureGroupRecord.DIS_INT_RATE_OFFSET
                            + DisclosureGroupRecord.DIS_INT_RATE_LENGTH);
            assertThat(DisclosureGroupRecord.FILLER_OFFSET
                    + DisclosureGroupRecord.FILLER_LENGTH)
                    .isEqualTo(DisclosureGroupRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("FILLER X(28) occupies bytes 22 to 49 and is a first-class span - gate G21")
        void fillerIsADeclaredSpanNotAnImplicitGap() {
            assertThat(DisclosureGroupRecord.FILLER_OFFSET).isEqualTo(22);
            assertThat(DisclosureGroupRecord.FILLER_LENGTH).isEqualTo(28);
            assertThat(DisclosureGroupRecord.FILLER_NAME).isEqualTo("FILLER");

            assertThat(DisclosureGroupRecord.FILLER_SPAN.offset()).isEqualTo(22);
            assertThat(DisclosureGroupRecord.FILLER_SPAN.length()).isEqualTo(28);
            assertThat(DisclosureGroupRecord.FILLER_SPAN.kind()).isEqualTo(PictureKind.FILLER);
            assertThat(DisclosureGroupRecord.FILLER_SPAN.kind().filler()).isTrue();
            assertThat(DisclosureGroupRecord.FILLER_SPAN.redefinition()).isFalse();

            assertThat(DisclosureGroupRecord.layout().storageSpans())
                    .as("FILLER occupies storage and is therefore one of the storage spans")
                    .contains(DisclosureGroupRecord.FILLER_SPAN);
            assertThat(row1().filler()).hasSize(28);
            assertThat(row1().fillerBytes()).hasSize(28);
        }

        @Test
        @DisplayName("FILLER is not referable, exactly as in COBOL")
        void fillerIsNotReferableByName() {
            assertThat(DisclosureGroupRecord.layout().hasSpan("FILLER")).isFalse();
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> DisclosureGroupRecord.layout().span("FILLER"))
                    .withMessageContaining("FILLER is not referable");
        }

        @Test
        @DisplayName("The sign of S9(04)V99 is overpunched: 6 bytes, never 7")
        void signIsOverpunched() {
            assertThat(DisclosureGroupRecord.DIS_INT_RATE_INTEGER_DIGITS).isEqualTo(4);
            assertThat(DisclosureGroupRecord.DIS_INT_RATE_SCALE).isEqualTo(2);
            assertThat(DisclosureGroupRecord.DIS_INT_RATE_LENGTH).isEqualTo(6);
            assertThat(DisclosureGroupRecord.DIS_INT_RATE_LENGTH)
                    .isEqualTo(DisclosureGroupRecord.DIS_INT_RATE_INTEGER_DIGITS
                            + DisclosureGroupRecord.DIS_INT_RATE_SCALE);

            DisclosureGroupRecord negative = new DisclosureGroupRecord(ASCII);
            negative.disIntRate(new BigDecimal("-15.00"));
            assertThat(negative.disIntRateImage()).hasSize(6).isEqualTo("00150}");
            assertThat(negative.encode()).hasSize(DisclosureGroupRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("The layout declares 5 storage spans and 1 overlay, in copybook order")
        void layoutDeclaresFiveStorageSpansAndOneOverlay() {
            RecordLayout layout = DisclosureGroupRecord.layout();

            assertThat(layout.spans()).hasSize(6);
            assertThat(layout.storageSpans())
                    .containsExactly(DisclosureGroupRecord.DIS_ACCT_GROUP_ID_SPAN,
                            DisclosureGroupRecord.DIS_TRAN_TYPE_CD_SPAN,
                            DisclosureGroupRecord.DIS_TRAN_CAT_CD_SPAN,
                            DisclosureGroupRecord.DIS_INT_RATE_SPAN,
                            DisclosureGroupRecord.FILLER_SPAN);
            assertThat(layout.redefinitions())
                    .as("DIS-GROUP-KEY is a group overlay over its own three members")
                    .containsExactly(DisclosureGroupRecord.DIS_GROUP_KEY_SPAN);

            int storageWidth = 0;
            for (FieldSpan span : layout.storageSpans()) {
                storageWidth += span.length();
            }
            assertThat(storageWidth).isEqualTo(DisclosureGroupRecord.RECORD_LENGTH);
        }

        @ParameterizedTest
        @CsvSource({
            "DIS-ACCT-GROUP-ID,  0, 10, ALPHANUMERIC",
            "DIS-TRAN-TYPE-CD,  10,  2, ALPHANUMERIC",
            "DIS-TRAN-CAT-CD,   12,  4, UNSIGNED_NUMERIC",
            "DIS-GROUP-KEY,      0, 16, ALPHANUMERIC",
            "DIS-INT-RATE,      16,  6, SIGNED_SCALED"
        })
        @DisplayName("Every referable span is addressable by its verbatim copybook name")
        void everyReferableSpanIsAddressableByName(String name, int offset, int length,
                PictureKind kind) {
            assertThat(DisclosureGroupRecord.layout().hasSpan(name)).isTrue();

            FieldSpan span = DisclosureGroupRecord.layout().span(name);
            assertThat(span.name()).isEqualTo(name);
            assertThat(span.offset()).isEqualTo(offset);
            assertThat(span.length()).isEqualTo(length);
            assertThat(span.kind()).isEqualTo(kind);
            assertThat(span.hasInitialValue())
                    .as("CVTRA02Y declares no VALUE clause on any item")
                    .isFalse();
        }

        @Test
        @DisplayName("Names are case-sensitive and no foreign item name resolves")
        void namesAreCaseSensitiveAndForeignNamesDoNotResolve() {
            assertThat(DisclosureGroupRecord.layout().hasSpan("dis-int-rate")).isFalse();
            assertThat(DisclosureGroupRecord.layout().hasSpan("TRAN-CAT-BAL")).isFalse();
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> DisclosureGroupRecord.layout().span("TRAN-CAT-BAL"))
                    .withMessageContaining("declares no field named 'TRAN-CAT-BAL'");
        }

        @Test
        @DisplayName("The width self-check accepts the real layout and rejects a dropped FILLER")
        void layoutRejectsADroppedTrailingFiller() {
            assertThat(RecordLayout.of(DisclosureGroupRecord.RECORD_LENGTH,
                    DisclosureGroupRecord.DIS_ACCT_GROUP_ID_SPAN,
                    DisclosureGroupRecord.DIS_TRAN_TYPE_CD_SPAN,
                    DisclosureGroupRecord.DIS_TRAN_CAT_CD_SPAN,
                    DisclosureGroupRecord.DIS_GROUP_KEY_SPAN,
                    DisclosureGroupRecord.DIS_INT_RATE_SPAN,
                    DisclosureGroupRecord.FILLER_SPAN).recordLength()).isEqualTo(50);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(DisclosureGroupRecord.RECORD_LENGTH,
                            DisclosureGroupRecord.DIS_ACCT_GROUP_ID_SPAN,
                            DisclosureGroupRecord.DIS_TRAN_TYPE_CD_SPAN,
                            DisclosureGroupRecord.DIS_TRAN_CAT_CD_SPAN,
                            DisclosureGroupRecord.DIS_INT_RATE_SPAN))
                    .withMessageContaining("28 byte(s) short");
        }

        @Test
        @DisplayName("The width self-check rejects a phantom sign byte on DIS-INT-RATE")
        void layoutRejectsAPhantomSignByte() {
            FieldSpan rateWithSignByte = FieldSpan.signedScaled("DIS-INT-RATE",
                    DisclosureGroupRecord.DIS_INT_RATE_OFFSET,
                    DisclosureGroupRecord.DIS_INT_RATE_INTEGER_DIGITS + 1,
                    DisclosureGroupRecord.DIS_INT_RATE_SCALE);
            assertThat(rateWithSignByte.length()).isEqualTo(7);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(DisclosureGroupRecord.RECORD_LENGTH,
                            DisclosureGroupRecord.DIS_ACCT_GROUP_ID_SPAN,
                            DisclosureGroupRecord.DIS_TRAN_TYPE_CD_SPAN,
                            DisclosureGroupRecord.DIS_TRAN_CAT_CD_SPAN,
                            rateWithSignByte,
                            FieldSpan.filler(23, DisclosureGroupRecord.FILLER_LENGTH)))
                    .withMessageContaining("1 byte(s) too long");
        }

        @Test
        @DisplayName("A row that is not exactly 50 bytes is rejected, never padded or truncated")
        void aRowOfTheWrongWidthIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> DisclosureGroupRecord.decode(ROW_1.substring(0, 49), ASCII))
                    .withMessageContaining("must match its declared width exactly");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> DisclosureGroupRecord.decode(ROW_1 + " ", ASCII))
                    .withMessageContaining("must match its declared width exactly");
        }

        @Test
        @DisplayName("The charset is an explicit parameter and is never defaulted")
        void theCharsetIsAlwaysAnExplicitParameter() {
            assertThat(new DisclosureGroupRecord(ASCII).charset()).isEqualTo(ASCII);
            assertThat(new DisclosureGroupRecord(EBCDIC).charset()).isEqualTo(EBCDIC);
            assertThat(DisclosureGroupRecord.decode(ROW_1, ASCII).charset()).isEqualTo(ASCII);

            assertThatNullPointerException()
                    .isThrownBy(() -> new DisclosureGroupRecord(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> DisclosureGroupRecord.decode(ROW_1, null));
            assertThatNullPointerException()
                    .isThrownBy(() -> DisclosureGroupRecord.decode((String) null, ASCII));
            assertThatNullPointerException()
                    .isThrownBy(() -> DisclosureGroupRecord.decode((byte[]) null, ASCII));

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new DisclosureGroupRecord(StandardCharsets.UTF_16))
                    .withMessageContaining("must encode to exactly one byte");
        }
    }

    @Nested
    @DisplayName("The key is 16 bytes, not 17 - CVTRA02Y against CVTRA01Y")
    class SixteenByteKeyNotSeventeen {
        @Test
        @DisplayName("A CVTRA01Y-shaped record also totals 50, so a width check is blind to the slip")
        void aSeventeenByteKeyedRecordAlsoTotalsFifty() {
            RecordLayout tranCatBalShaped = RecordLayout.of(50,
                    FieldSpan.unsignedNumeric("TRANCAT-ACCT-ID", 0, 11),
                    FieldSpan.alphanumeric("TRANCAT-TYPE-CD", 11, 2),
                    FieldSpan.unsignedNumeric("TRANCAT-CD", 13, 4),
                    FieldSpan.redefining("TRAN-CAT-KEY", 0, 17, PictureKind.ALPHANUMERIC),
                    FieldSpan.signedScaled("TRAN-CAT-BAL", 17, 9, 2),
                    FieldSpan.filler(28, 22));

            assertThat(tranCatBalShaped.recordLength())
                    .as("both copybooks declare RECLN = 50")
                    .isEqualTo(DisclosureGroupRecord.layout().recordLength());
            assertThat(tranCatBalShaped.span("TRAN-CAT-KEY").length())
                    .as("CVTRA01Y's key genuinely is 17 bytes")
                    .isEqualTo(17);
            assertThat(tranCatBalShaped.span("TRAN-CAT-BAL").offset())
                    .as("and its balance therefore starts at 17, not 16")
                    .isEqualTo(17);

            assertThat(DisclosureGroupRecord.layout().span("DIS-INT-RATE").offset()).isEqualTo(16);
        }

        @Test
        @DisplayName("A 17-byte DIS-GROUP-KEY overlay is rejected at layout construction")
        void aSeventeenByteKeyOverlayIsRejected() {
            FieldSpan seventeenByteKey = FieldSpan.redefining("DIS-GROUP-KEY",
                    DisclosureGroupRecord.DIS_GROUP_KEY_OFFSET, 17, PictureKind.ALPHANUMERIC);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(DisclosureGroupRecord.RECORD_LENGTH,
                            DisclosureGroupRecord.DIS_ACCT_GROUP_ID_SPAN,
                            DisclosureGroupRecord.DIS_TRAN_TYPE_CD_SPAN,
                            DisclosureGroupRecord.DIS_TRAN_CAT_CD_SPAN,
                            seventeenByteKey,
                            DisclosureGroupRecord.DIS_INT_RATE_SPAN,
                            DisclosureGroupRecord.FILLER_SPAN))
                    .withMessageContaining("reaches byte 17 but only 16 byte(s) of storage");
        }

        @Test
        @DisplayName("Fixture row 1 read with a 17-byte key yields the corrupted span 0150{0")
        void aSeventeenByteKeyCorruptsTheRateSpanOfRealData() {
            assertThat(ROW_1.substring(0, 17)).isEqualTo("A0000000000100010");
            assertThat(ROW_1.substring(17, 23)).isEqualTo(ROW_1_RATE_IMAGE_UNDER_A_17_BYTE_KEY);

            assertThat(ROW_1.substring(0, 16)).isEqualTo("A000000000010001");
            assertThat(ROW_1.substring(16, 22)).isEqualTo(ROW_1_RATE_IMAGE);

            DisclosureGroupRecord record = row1();
            assertThat(record.disIntRateImage())
                    .isEqualTo(ROW_1_RATE_IMAGE)
                    .isNotEqualTo(ROW_1_RATE_IMAGE_UNDER_A_17_BYTE_KEY);
            assertThat(record.disGroupKey())
                    .hasSize(16)
                    .isNotEqualTo(ROW_1.substring(0, 17));
        }

        @Test
        @DisplayName("The corrupted span is not merely a different value - it will not decode at all")
        void theCorruptedSpanIsNotEvenADecodableZonedImage() {
            FixedWidthCodec codec = new FixedWidthCodec(ASCII);

            assertThat(codec.decodeSignedScaled(ROW_1_RATE_IMAGE,
                    DisclosureGroupRecord.DIS_INT_RATE_SCALE))
                    .isEqualByComparingTo(new BigDecimal("15.00"));

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> codec.decodeSignedScaled(
                            ROW_1_RATE_IMAGE_UNDER_A_17_BYTE_KEY,
                            DisclosureGroupRecord.DIS_INT_RATE_SCALE))
                    .withMessageContaining("holds only the digits 0 to 9");
        }

        @Test
        @DisplayName("The rate decodes to a clean APR from offset 16 on every fixture row")
        void everyFixtureRowYieldsACleanAprAtOffsetSixteen() {
            Set<BigDecimal> ratesAtSixteen = new LinkedHashSet<>();
            for (String row : fixtureRows()) {
                ratesAtSixteen.add(DisclosureGroupRecord.decode(row, ASCII).disIntRate());
            }

            assertThat(ratesAtSixteen).containsExactlyInAnyOrder(
                    new BigDecimal("0.00"), new BigDecimal("15.00"), new BigDecimal("25.00"));
        }
    }

    @Nested
    @DisplayName("Field typing - what must and must not be modelled as a number")
    class FieldTyping {
        @Test
        @DisplayName("DIS-ACCT-GROUP-ID X(10) pads on the right and truncates on the right")
        void groupIdIsAlphanumericPaddedAndTruncatedOnTheRight() {
            DisclosureGroupRecord record = new DisclosureGroupRecord(ASCII);

            record.disAcctGroupId("AB");
            assertThat(record.disAcctGroupId()).isEqualTo("AB        ").hasSize(10);

            record.disAcctGroupId("ABCDEFGHIJKL");
            assertThat(record.disAcctGroupId())
                    .as("right truncation: the leading 10 survive, so never 'CDEFGHIJKL'")
                    .isEqualTo("ABCDEFGHIJ");

            record.disAcctGroupId("");
            assertThat(record.disAcctGroupId()).isEqualTo(" ".repeat(10));

            record.disAcctGroupId(GROUP_ID_A);
            assertThat(record.disAcctGroupId()).isEqualTo(GROUP_ID_A);

            assertThatNullPointerException().isThrownBy(() -> record.disAcctGroupId(null));
        }

        @Test
        @DisplayName("Reads are never trimmed - the padding of DEFAULT and ZEROAPR is data")
        void readsAreNeverTrimmed() {
            assertThat(GROUP_ID_DEFAULT).hasSize(10).isNotEqualTo("DEFAULT");
            assertThat(GROUP_ID_ZEROAPR).hasSize(10).isNotEqualTo("ZEROAPR");

            DisclosureGroupRecord record = DisclosureGroupRecord.decode(
                    row(GROUP_ID_DEFAULT, "01", "0001", RATE_ZERO_IMAGE, FIXTURE_FILLER), ASCII);
            assertThat(record.disAcctGroupId()).isEqualTo(GROUP_ID_DEFAULT).endsWith("   ");
        }

        @Test
        @DisplayName("DIS-TRAN-TYPE-CD is a String, not a number, despite reading 01 to 07")
        void tranTypeCodeIsAlphanumericNotNumeric() throws NoSuchMethodException {
            Method accessor = DisclosureGroupRecord.class.getMethod("disTranTypeCd");
            assertThat(accessor.getReturnType()).isEqualTo(String.class);

            assertThat(DisclosureGroupRecord.DIS_TRAN_TYPE_CD_SPAN.kind())
                    .isEqualTo(PictureKind.ALPHANUMERIC);
            assertThat(DisclosureGroupRecord.DIS_TRAN_TYPE_CD_SPAN.kind().numericDisplay()).isFalse();
            assertThat(DisclosureGroupRecord.DIS_TRAN_TYPE_CD_SPAN.kind().leftJustified()).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"01", "02", "03", "04", "05", "06", "07"})
        @DisplayName("Every measured type code keeps its leading zero across decode and re-encode")
        void everyTypeCodeSurvivesTheRoundTripWithItsLeadingZero(String typeCd) {
            String image = row(GROUP_ID_A, typeCd, "0001", RATE_ZERO_IMAGE, FIXTURE_FILLER);
            DisclosureGroupRecord record = DisclosureGroupRecord.decode(image, ASCII);

            assertThat(record.disTranTypeCd()).isEqualTo(typeCd).hasSize(2).startsWith("0");
            assertThat(record.encodeToString()).isEqualTo(image);
            assertThat(record.encodeToString().substring(10, 12)).isEqualTo(typeCd);
        }

        @Test
        @DisplayName("An over-long type code truncates on the right, to two characters")
        void anOverLongTypeCodeTruncatesOnTheRight() {
            DisclosureGroupRecord record = new DisclosureGroupRecord(ASCII);

            record.disTranTypeCd("XYZ");
            assertThat(record.disTranTypeCd()).isEqualTo("XY");

            record.disTranTypeCd("1");
            assertThat(record.disTranTypeCd()).isEqualTo("1 ");

            assertThatNullPointerException().isThrownBy(() -> record.disTranTypeCd(null));
        }

        @ParameterizedTest
        @CsvSource({"1, 0001", "2, 0002", "3, 0003", "4, 0004", "0, 0000", "9999, 9999"})
        @DisplayName("DIS-TRAN-CAT-CD 9(04) zero-fills on the LEFT")
        void categoryCodeZeroFillsOnTheLeft(int value, String expectedImage) {
            DisclosureGroupRecord record = new DisclosureGroupRecord(ASCII);
            record.disTranCatCd(value);

            assertThat(record.disTranCatCdImage()).isEqualTo(expectedImage).hasSize(4);
            assertThat(record.disTranCatCd()).isEqualTo(value);
            assertThat(record.encodeToString().substring(12, 16)).isEqualTo(expectedImage);
        }

        @Test
        @DisplayName("An oversized category code truncates on the LEFT and does not throw")
        void anOversizedCategoryCodeTruncatesOnTheLeft() {
            DisclosureGroupRecord record = new DisclosureGroupRecord(ASCII);

            record.disTranCatCd(12_345);
            assertThat(record.disTranCatCdImage()).isEqualTo("2345").isNotEqualTo("1234");
            assertThat(record.disTranCatCd()).isEqualTo(2345);

            record.disTranCatCd("987654");
            assertThat(record.disTranCatCdImage()).isEqualTo("7654");
            assertThat(record.disTranCatCd()).isEqualTo(7654);
        }

        @Test
        @DisplayName("The digit-string overload is the MOVE '05' shape, and rejects non-digits")
        void theDigitStringOverloadIsTheMoveLiteralShape() {
            DisclosureGroupRecord record = new DisclosureGroupRecord(ASCII);

            record.disTranCatCd("5");
            assertThat(record.disTranCatCdImage()).isEqualTo("0005");

            record.disTranCatCd("0001");
            assertThat(record.disTranCatCd()).isEqualTo(1);

            assertThatIllegalArgumentException().isThrownBy(() -> record.disTranCatCd("00A1"))
                    .withMessageContaining("holds only the digits 0 to 9");
            assertThatIllegalArgumentException().isThrownBy(() -> record.disTranCatCd(""))
                    .withMessageContaining("at least one digit");
            assertThatIllegalArgumentException().isThrownBy(() -> record.disTranCatCd("00 1"));
            assertThatNullPointerException().isThrownBy(() -> record.disTranCatCd((String) null));
        }

        @Test
        @DisplayName("PIC 9 has no sign position, so a negative category code is rejected")
        void aNegativeCategoryCodeIsRejected() {
            DisclosureGroupRecord record = new DisclosureGroupRecord(ASCII);

            assertThatIllegalArgumentException().isThrownBy(() -> record.disTranCatCd(-1))
                    .withMessageContaining("PIC 9 has no sign position");
            assertThatIllegalArgumentException().isThrownBy(() -> record.disTranCatCd(Integer.MIN_VALUE));
        }

        @Test
        @DisplayName("A misaligned category span fails loudly rather than yielding a plausible number")
        void aMisalignedCategorySpanFailsLoudly() {
            DisclosureGroupRecord broken = DisclosureGroupRecord.decode(
                    row(GROUP_ID_A, "01", "00-1", RATE_ZERO_IMAGE, FIXTURE_FILLER), ASCII);

            assertThat(broken.disTranCatCdImage()).isEqualTo("00-1");
            assertThatIllegalArgumentException().isThrownBy(broken::disTranCatCd)
                    .withMessageContaining("holds only the digits 0 to 9");
        }

        @Test
        @DisplayName("DIS-INT-RATE is a BigDecimal of scale exactly 2, with 4 integer digits")
        void theRateIsABigDecimalOfScaleTwo() throws NoSuchMethodException {
            Method accessor = DisclosureGroupRecord.class.getMethod("disIntRate");
            assertThat(accessor.getReturnType()).isEqualTo(BigDecimal.class);

            assertThat(DisclosureGroupRecord.DIS_INT_RATE_SPAN.kind())
                    .isEqualTo(PictureKind.SIGNED_SCALED);
            assertThat(DisclosureGroupRecord.DIS_INT_RATE_SPAN.kind().numericDisplay()).isTrue();
            assertThat(DisclosureGroupRecord.DIS_INT_RATE_SPAN.kind().leftJustified()).isFalse();

            assertThat(row1().disIntRate())
                    .isEqualTo(new BigDecimal("15.00"))
                    .hasScaleOf(DisclosureGroupRecord.DIS_INT_RATE_SCALE);

            DisclosureGroupRecord widest = new DisclosureGroupRecord(ASCII);
            widest.disIntRate(new BigDecimal("9999.99"));
            assertThat(widest.disIntRate()).isEqualTo(new BigDecimal("9999.99"));
            assertThat(widest.disIntRateImage()).hasSize(6);
        }
    }

    @Nested
    @DisplayName("The zero predicate - CBACT04C:L214 IF DIS-INT-RATE NOT = 0")
    class ZeroPredicate {
        @Test
        @DisplayName("A rate of 0.00 is zero by value, and that is why compareTo is required")
        void zeroIsDetectedByValueNotByEquals() {
            DisclosureGroupRecord record = withRateImage(RATE_ZERO_IMAGE);
            BigDecimal rate = record.disIntRate();

            assertThat(rate).hasScaleOf(2).isEqualTo(new BigDecimal("0.00"));

            assertThat(rate).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(rate.compareTo(BigDecimal.ZERO)).isZero();

            assertThat(rate.equals(BigDecimal.ZERO))
                    .as("BigDecimal.equals compares scale as well as value, so 0.00 != ZERO")
                    .isFalse();

            assertThat(record.disIntRateIsZero()).isTrue();
            assertThat(record.disIntRateIsNotZero()).isFalse();
        }

        @Test
        @DisplayName("A freshly initialised record reads 0.00 at scale 2 and is zero")
        void aFreshRecordIsZero() {
            DisclosureGroupRecord fresh = new DisclosureGroupRecord(ASCII);

            assertThat(fresh.disIntRateImage()).isEqualTo("00000{");
            assertThat(fresh.disIntRate()).hasScaleOf(2).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(fresh.disIntRateIsZero()).isTrue();
            assertThat(fresh.disIntRateIsNotZero()).isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {"0.01", "-0.01", "15.00", "25.00", "-15.00", "9999.99", "-9999.99"})
        @DisplayName("Any non-zero rate, positive or negative, takes the interest branch")
        void anyNonZeroRateIsNotZero(String value) {
            DisclosureGroupRecord record = new DisclosureGroupRecord(ASCII);
            record.disIntRate(new BigDecimal(value));

            assertThat(record.disIntRateIsZero()).isFalse();
            assertThat(record.disIntRateIsNotZero()).isTrue();
            assertThat(record.disIntRate()).hasScaleOf(2);
        }

        @ParameterizedTest
        @ValueSource(strings = {"0", "0.0", "0.00", "-0.00", "0.000"})
        @DisplayName("Zero at any sending scale stores as 0.00 and is still zero by value")
        void zeroAtAnySendingScaleIsStillZero(String value) {
            DisclosureGroupRecord record = new DisclosureGroupRecord(ASCII);
            record.disIntRate(new BigDecimal(value));

            assertThat(record.disIntRate()).hasScaleOf(2).isEqualTo(new BigDecimal("0.00"));
            assertThat(record.disIntRateImage()).isEqualTo(RATE_ZERO_IMAGE);
            assertThat(record.disIntRateIsZero()).isTrue();
            assertThat(record.disIntRateIsNotZero()).isFalse();
        }

        @Test
        @DisplayName("The predicate is exactly the negation of its counterpart, both ways")
        void theTwoPredicatesAreExactComplements() {
            DisclosureGroupRecord zero = withRateImage(RATE_ZERO_IMAGE);
            DisclosureGroupRecord nonZero = withRateImage(ROW_1_RATE_IMAGE);

            assertThat(zero.disIntRateIsZero()).isNotEqualTo(zero.disIntRateIsNotZero());
            assertThat(nonZero.disIntRateIsZero()).isNotEqualTo(nonZero.disIntRateIsNotZero());

            assertThat(zero.disIntRateIsZero()).isTrue();
            assertThat(nonZero.disIntRateIsNotZero()).isTrue();
        }

        @Test
        @DisplayName("The fixture exercises both branches from real data: 30 zero, 21 non-zero rows")
        void bothBranchesAreReachableFromRealFixtureData() {
            int zeroRows = 0;
            int nonZeroRows = 0;
            for (String row : fixtureRows()) {
                DisclosureGroupRecord record = DisclosureGroupRecord.decode(row, ASCII);
                if (record.disIntRateIsZero()) {
                    zeroRows++;
                    assertThat(record.disIntRate()).isEqualByComparingTo(BigDecimal.ZERO);
                } else {
                    nonZeroRows++;
                    assertThat(record.disIntRate()).isGreaterThan(BigDecimal.ZERO);
                }
            }

            assertThat(zeroRows).isEqualTo(ROWS_WITH_A_ZERO_RATE);
            assertThat(nonZeroRows)
                    .isEqualTo(ROWS_WITH_A_15_PERCENT_RATE + ROWS_WITH_A_25_PERCENT_RATE);
            assertThat(zeroRows + nonZeroRows).isEqualTo(FIXTURE_ROW_COUNT);
            assertThat(zeroRows).as("the zero branch is the majority path").isGreaterThan(nonZeroRows);
        }
    }

    @Nested
    @DisplayName("Fixture round trip - all 51 rows of discgrp.txt, gates G19 and G21")
    class FixtureRoundTrip {
        @Test
        @DisplayName("The fixture is 51 rows of exactly 50 bytes, needing no normalisation")
        void theFixtureMatchesTheCopybookExactly() {
            List<String> rows = fixtureRows();

            assertThat(rows).hasSize(FIXTURE_ROW_COUNT);
            for (int index = 0; index < rows.size(); index++) {
                assertThat(rows.get(index))
                        .as("row %d must be exactly the declared record width", index + 1)
                        .hasSize(FIXTURE_ROW_WIDTH);
                assertThat(rows.get(index).getBytes(ASCII))
                        .as("row %d must encode to %d single-byte characters", index + 1,
                                FIXTURE_ROW_WIDTH)
                        .hasSize(DisclosureGroupRecord.RECORD_LENGTH);
            }
        }

        @Test
        @DisplayName("Every row decodes at the copybook offsets and re-encodes byte-identically")
        void everyRowRoundTripsByteForByte() {
            List<String> rows = fixtureRows();

            for (int index = 0; index < rows.size(); index++) {
                String stored = rows.get(index);
                int rowNumber = index + 1;
                DisclosureGroupRecord record = DisclosureGroupRecord.decode(stored, ASCII);

                assertThat(record.disAcctGroupId())
                        .as("row %d DIS-ACCT-GROUP-ID at 0..9", rowNumber)
                        .isEqualTo(stored.substring(0, 10));
                assertThat(record.disTranTypeCd())
                        .as("row %d DIS-TRAN-TYPE-CD at 10..11", rowNumber)
                        .isEqualTo(stored.substring(10, 12));
                assertThat(record.disTranCatCdImage())
                        .as("row %d DIS-TRAN-CAT-CD at 12..15", rowNumber)
                        .isEqualTo(stored.substring(12, 16));
                assertThat(record.disIntRateImage())
                        .as("row %d DIS-INT-RATE at 16..21", rowNumber)
                        .isEqualTo(stored.substring(16, 22));
                assertThat(record.filler())
                        .as("row %d FILLER at 22..49", rowNumber)
                        .isEqualTo(stored.substring(22, 50));
                assertThat(record.disGroupKey())
                        .as("row %d DIS-GROUP-KEY at 0..15", rowNumber)
                        .isEqualTo(stored.substring(0, 16));

                assertThat(record.encodeToString())
                        .as("row %d must re-encode byte-identically", rowNumber)
                        .isEqualTo(stored);
                assertThat(record.encode())
                        .as("row %d bytes must be identical", rowNumber)
                        .isEqualTo(stored.getBytes(ASCII));

                assertThat(record.disIntRate())
                        .as("row %d DIS-INT-RATE scale", rowNumber)
                        .hasScaleOf(DisclosureGroupRecord.DIS_INT_RATE_SCALE);
            }
        }

        @Test
        @DisplayName("Row 1 is [A000000000][01][0001][00150{][28 zeros] with a rate of 15.00")
        void rowOneDecodesFieldForField() {
            List<String> rows = fixtureRows();
            String stored = rows.get(0);

            assertThat(stored).as("the shipped fixture's first row").isEqualTo(ROW_1);

            DisclosureGroupRecord record = DisclosureGroupRecord.decode(stored, ASCII);
            assertThat(record.disAcctGroupId()).isEqualTo(GROUP_ID_A);
            assertThat(record.disTranTypeCd()).isEqualTo("01");
            assertThat(record.disTranCatCdImage()).isEqualTo("0001");
            assertThat(record.disTranCatCd()).isEqualTo(1);
            assertThat(record.disIntRateImage()).isEqualTo(ROW_1_RATE_IMAGE);
            assertThat(record.disIntRate())
                    .isEqualTo(new BigDecimal("15.00"))
                    .hasScaleOf(2);
            assertThat(record.filler()).isEqualTo(FIXTURE_FILLER);
            assertThat(record.disGroupKey()).isEqualTo("A000000000" + "01" + "0001");
            assertThat(record.disIntRateIsNotZero()).isTrue();
        }

        @Test
        @DisplayName("The fixture holds exactly three group ids, 17 rows each, DEFAULT on lines 18-34")
        void theThreeGroupIdsOccupySeventeenRowsEach() {
            List<String> rows = fixtureRows();
            int aRows = 0;
            int defaultRows = 0;
            int zeroAprRows = 0;

            for (int index = 0; index < rows.size(); index++) {
                String groupId = DisclosureGroupRecord.decode(rows.get(index), ASCII)
                        .disAcctGroupId();
                int lineNumber = index + 1;

                if (GROUP_ID_A.equals(groupId)) {
                    aRows++;
                    assertThat(lineNumber).as("A000000000 rows are lines 1-17").isBetween(1, 17);
                } else if (GROUP_ID_DEFAULT.equals(groupId)) {
                    defaultRows++;
                    assertThat(lineNumber).as("DEFAULT rows are lines 18-34").isBetween(18, 34);
                } else {
                    zeroAprRows++;
                    assertThat(groupId).isEqualTo(GROUP_ID_ZEROAPR);
                    assertThat(lineNumber).as("ZEROAPR rows are lines 35-51").isBetween(35, 51);
                }
            }

            assertThat(aRows).isEqualTo(ROWS_PER_GROUP_ID);
            assertThat(defaultRows).isEqualTo(ROWS_PER_GROUP_ID);
            assertThat(zeroAprRows).isEqualTo(ROWS_PER_GROUP_ID);
            assertThat(aRows + defaultRows + zeroAprRows).isEqualTo(FIXTURE_ROW_COUNT);
        }

        @Test
        @DisplayName("The fixture's rate images are 30 zero, 15 at 15.00 and 6 at 25.00")
        void theRateDistributionIsAsMeasured() {
            List<String> rows = fixtureRows();
            int zero = 0;
            int fifteen = 0;
            int twentyFive = 0;

            for (String row : rows) {
                String image = DisclosureGroupRecord.decode(row, ASCII).disIntRateImage();
                if (RATE_ZERO_IMAGE.equals(image)) {
                    zero++;
                } else if ("00150{".equals(image)) {
                    fifteen++;
                } else {
                    assertThat(image).isEqualTo("00250{");
                    twentyFive++;
                }
            }

            assertThat(zero).isEqualTo(ROWS_WITH_A_ZERO_RATE);
            assertThat(fifteen).isEqualTo(ROWS_WITH_A_15_PERCENT_RATE);
            assertThat(twentyFive).isEqualTo(ROWS_WITH_A_25_PERCENT_RATE);

            for (String row : rows) {
                assertThat(row.charAt(21))
                        .as("every fixture rate ends in the +0 overpunch")
                        .isEqualTo('{');
            }
        }

        @Test
        @DisplayName("The category codes in the fixture are 0001 to 0004 and the type codes 01 to 07")
        void theKeySubFieldsMatchTheMeasuredFixtureValues() {
            Set<String> typeCodes = new LinkedHashSet<>();
            Set<String> categoryCodes = new LinkedHashSet<>();

            for (String row : fixtureRows()) {
                DisclosureGroupRecord record = DisclosureGroupRecord.decode(row, ASCII);
                typeCodes.add(record.disTranTypeCd());
                categoryCodes.add(record.disTranCatCdImage());
            }

            assertThat(typeCodes)
                    .containsExactlyInAnyOrder("01", "02", "03", "04", "05", "06", "07");
            assertThat(categoryCodes)
                    .containsExactlyInAnyOrder("0001", "0002", "0003", "0004");
        }

        @Test
        @DisplayName("The same stored image decoded under either code page yields the same items")
        void theCodePageIsHonouredAndIsNotPartOfIdentity() {
            DisclosureGroupRecord ascii = DisclosureGroupRecord.decode(ROW_1, ASCII);
            DisclosureGroupRecord ebcdic = DisclosureGroupRecord.decode(ROW_1.getBytes(EBCDIC),
                    EBCDIC);

            assertThat(ebcdic.disAcctGroupId()).isEqualTo(GROUP_ID_A);
            assertThat(ebcdic.disTranTypeCd()).isEqualTo("01");
            assertThat(ebcdic.disTranCatCd()).isEqualTo(1);
            assertThat(ebcdic.disIntRate()).isEqualTo(new BigDecimal("15.00"));
            assertThat(ebcdic.filler()).isEqualTo(FIXTURE_FILLER);

            assertThat(ascii).isEqualTo(ebcdic).hasSameHashCodeAs(ebcdic);
            assertThat(ascii.encode())
                    .as("the items are equal but the stored bytes are not")
                    .isNotEqualTo(ebcdic.encode());
            assertThat(ebcdic.encodeToString()).isEqualTo(ROW_1);
        }
    }

    @Nested
    @DisplayName("The 'DEFAULT' literal and FILLER - gate G21, and a documented discrepancy")
    class DefaultLiteralAndFiller {
        @Test
        @DisplayName("MOVE 'DEFAULT' into X(10) stores DEFAULT plus exactly three spaces")
        void theSevenCharacterDefaultLiteralIsRightPaddedToTen() {
            assertThat(DisclosureGroupRecord.DEFAULT_ACCT_GROUP_ID).isEqualTo("DEFAULT").hasSize(7);

            DisclosureGroupRecord record = new DisclosureGroupRecord(ASCII);
            record.disAcctGroupId(DisclosureGroupRecord.DEFAULT_ACCT_GROUP_ID);

            assertThat(record.disAcctGroupId())
                    .isEqualTo("DEFAULT   ")
                    .hasSize(DisclosureGroupRecord.DIS_ACCT_GROUP_ID_LENGTH)
                    .isEqualTo(DisclosureGroupRecord.DEFAULT_ACCT_GROUP_ID + "   ");

            assertThat(new FixedWidthCodec(ASCII).movePicX(
                    DisclosureGroupRecord.DEFAULT_ACCT_GROUP_ID,
                    DisclosureGroupRecord.DIS_ACCT_GROUP_ID_LENGTH))
                    .isEqualTo("DEFAULT   ");
        }

        @Test
        @DisplayName("The padded literal matches the bytes the fixture actually stores on lines 18-34")
        void thePaddedLiteralMatchesTheFixtureBytes() {
            DisclosureGroupRecord constructed = new DisclosureGroupRecord(ASCII);
            constructed.disAcctGroupId(DisclosureGroupRecord.DEFAULT_ACCT_GROUP_ID);

            String storedDefaultRow = fixtureRows().get(17);
            DisclosureGroupRecord stored = DisclosureGroupRecord.decode(storedDefaultRow, ASCII);

            assertThat(stored.disAcctGroupId())
                    .isEqualTo(constructed.disAcctGroupId())
                    .isEqualTo(GROUP_ID_DEFAULT);
            assertThat(storedDefaultRow.substring(0, 10)).isEqualTo("DEFAULT   ");
        }

        @Test
        @DisplayName("Decode preserves the fixture's 28 FILLER zeros verbatim, and they round-trip")
        void decodePreservesTheFixturesFillerBytesVerbatim() {
            assertThat(FIXTURE_FILLER).isEqualTo("0000000000000000000000000000").hasSize(28);

            for (String row : fixtureRows()) {
                DisclosureGroupRecord record = DisclosureGroupRecord.decode(row, ASCII);

                assertThat(record.filler())
                        .isEqualTo(FIXTURE_FILLER)
                        .doesNotContain(" ");
                assertThat(record.fillerBytes()).isEqualTo(FIXTURE_FILLER.getBytes(ASCII));
                assertThat(record.encodeToString().substring(22)).isEqualTo(FIXTURE_FILLER);
                assertThat(record.encodeToString()).isEqualTo(row);
            }
        }

        @Test
        @DisplayName("Fresh construction emits 28 FILLER spaces - the COBOL default, gate G21")
        void freshConstructionEmitsTwentyEightFillerSpaces() {
            DisclosureGroupRecord fresh = new DisclosureGroupRecord(ASCII);

            assertThat(fresh.filler()).isEqualTo(" ".repeat(28)).hasSize(28);
            assertThat(fresh.fillerBytes())
                    .hasSize(28)
                    .containsOnly(" ".getBytes(ASCII)[0]);
            assertThat(fresh.encodeToString().substring(22)).isEqualTo(" ".repeat(28));

            assertThat(fresh.filler())
                    .as("a fresh record's FILLER differs from a stored row's, by design")
                    .isNotEqualTo(FIXTURE_FILLER);
        }

        @Test
        @DisplayName("An all-default record is 50 bytes: 12 spaces, 0000, 00000{, then 28 spaces")
        void anAllDefaultRecordIsFullyInitialised() {
            DisclosureGroupRecord fresh = new DisclosureGroupRecord(ASCII);

            assertThat(fresh.encodeToString())
                    .isEqualTo(" ".repeat(10) + " ".repeat(2) + "0000" + "00000{" + " ".repeat(28))
                    .hasSize(DisclosureGroupRecord.RECORD_LENGTH);
            assertThat(fresh.disAcctGroupId()).isEqualTo(" ".repeat(10));
            assertThat(fresh.disTranTypeCd()).isEqualTo("  ");
            assertThat(fresh.disTranCatCd()).isZero();
            assertThat(fresh.disIntRate()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(fresh.filler()).isEqualTo(" ".repeat(28));
        }

        @Test
        @DisplayName("Fully populating a fresh record reproduces a fixture row apart from FILLER")
        void aPopulatedFreshRecordDiffersFromAFixtureRowOnlyInFiller() {
            DisclosureGroupRecord built = new DisclosureGroupRecord(ASCII);
            built.disAcctGroupId(GROUP_ID_A);
            built.disTranTypeCd("01");
            built.disTranCatCd(1);
            built.disIntRate(new BigDecimal("15.00"));

            assertThat(built.encodeToString())
                    .isEqualTo(ROW_1.substring(0, 22) + " ".repeat(28));
            assertThat(built.encodeToString().substring(0, 22))
                    .as("the four data items are byte-identical to the fixture row")
                    .isEqualTo(ROW_1.substring(0, 22));
            assertThat(built).isNotEqualTo(row1());
        }
    }

    @Nested
    @DisplayName("Numeric parity - gates G22, G23, G24 and G25")
    class NumericParity {
        @Test
        @DisplayName("G22 - no field, return type or parameter is double, float, Double or Float")
        void noBinaryFloatingPointTypeAppearsAnywhereOnTheType() {
            Set<Class<?>> forbidden = Set.of(double.class, float.class, Double.class, Float.class);

            for (Field field : DisclosureGroupRecord.class.getDeclaredFields()) {
                assertThat(forbidden)
                        .as("field %s must not be a binary floating-point type", field.getName())
                        .doesNotContain(field.getType());
            }
            for (Method method : DisclosureGroupRecord.class.getDeclaredMethods()) {
                assertThat(forbidden)
                        .as("method %s must not return a binary floating-point type", method.getName())
                        .doesNotContain(method.getReturnType());
                for (Class<?> parameter : method.getParameterTypes()) {
                    assertThat(forbidden)
                            .as("method %s must not accept a binary floating-point type",
                                    method.getName())
                            .doesNotContain(parameter);
                }
            }

            assertThat(DisclosureGroupRecord.class.getDeclaredFields()).isNotEmpty();
            assertThat(DisclosureGroupRecord.class.getDeclaredMethods()).isNotEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"0.00", "15.00", "25.00", "-15.00", "0.01", "9999.99", "-9999.99"})
        @DisplayName("G23 - the rate always reports scale exactly 2, the zero rate included")
        void theRateAlwaysReportsScaleExactlyTwo(String value) {
            DisclosureGroupRecord record = new DisclosureGroupRecord(ASCII);
            record.disIntRate(new BigDecimal(value));

            assertThat(record.disIntRate()).hasScaleOf(2);
            assertThat(record.disIntRate().scale()).isEqualTo(DisclosureGroupRecord.DIS_INT_RATE_SCALE);
            assertThat(record.disIntRateImage()).hasSize(DisclosureGroupRecord.DIS_INT_RATE_LENGTH);

            DisclosureGroupRecord reread = DisclosureGroupRecord.decode(record.encode(), ASCII);
            assertThat(reread.disIntRate()).hasScaleOf(2).isEqualTo(record.disIntRate());
        }

        @Test
        @DisplayName("G23 - a zero rate reports scale 2, not scale 0")
        void aZeroRateReportsScaleTwoNotZero() {
            DisclosureGroupRecord stored = withRateImage(RATE_ZERO_IMAGE);
            DisclosureGroupRecord fresh = new DisclosureGroupRecord(ASCII);

            assertThat(stored.disIntRate()).hasScaleOf(2).isEqualTo(new BigDecimal("0.00"));
            assertThat(fresh.disIntRate()).hasScaleOf(2).isEqualTo(new BigDecimal("0.00"));
            assertThat(stored.disIntRate().scale()).isNotZero();
            assertThat(BigDecimal.ZERO.scale())
                    .as("which is exactly why BigDecimal.ZERO is not an acceptable stand-in")
                    .isZero();
        }

        @Test
        @DisplayName("G24 - the one rounding mode in the system is DOWN, because ROUNDED is never used")
        void theOnlyRoundingModeIsDown() {
            assertThat(CobolDecimal.COBOL_ROUNDING).isEqualTo(RoundingMode.DOWN);
            assertThat(CobolDecimal.COBOL_ROUNDING.name()).isEqualTo("DOWN");
            assertThat(CobolDecimal.MONETARY_SCALE)
                    .isEqualTo(DisclosureGroupRecord.DIS_INT_RATE_SCALE)
                    .isEqualTo(2);
        }

        @ParameterizedTest
        @CsvSource({
            "15.999,      15.99,          16.00",
            "0.125,       0.12,           0.13",
            "0.129,       0.12,           0.13",
            "25.005,      25.00,          25.01",
            "-15.999,    -15.99,         -16.00",
            "-0.125,      -0.12,          -0.13"
        })
        @DisplayName("G24 - excess fraction digits are truncated toward zero, never rounded")
        void excessFractionDigitsAreTruncatedTowardZero(String sent, String storedDown,
                String ifItRounded) {
            DisclosureGroupRecord record = new DisclosureGroupRecord(ASCII);
            record.disIntRate(new BigDecimal(sent));

            assertThat(record.disIntRate())
                    .as("%s must truncate to %s", sent, storedDown)
                    .isEqualTo(new BigDecimal(storedDown))
                    .hasScaleOf(2);
            assertThat(record.disIntRate())
                    .as("%s must NOT round to %s", sent, ifItRounded)
                    .isNotEqualTo(new BigDecimal(ifItRounded));
            assertThat(new BigDecimal(storedDown))
                    .as("the case is only meaningful if the two answers differ")
                    .isNotEqualByComparingTo(new BigDecimal(ifItRounded));
        }

        @ParameterizedTest
        @CsvSource({
            "123456.78,   3456.78,   34567H",
            "-123456.78, -3456.78,   34567Q",
            "10000.00,    0.00,      00000{",
            "-10000.00,   0.00,      00000}",
            "99999.99,    9999.99,   99999I"
        })
        @DisplayName("G24 - integer digits beyond four are discarded on the LEFT, and nothing throws")
        void excessIntegerDigitsAreDiscardedOnTheLeft(String sent, String stored, String image) {
            DisclosureGroupRecord record = new DisclosureGroupRecord(ASCII);
            record.disIntRate(new BigDecimal(sent));

            assertThat(record.disIntRate()).isEqualByComparingTo(new BigDecimal(stored)).hasScaleOf(2);
            assertThat(record.disIntRateImage()).isEqualTo(image).hasSize(6);
            assertThat(record.encode()).hasSize(DisclosureGroupRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("G24 - the receiver's own truncation matches CobolDecimal.storeAtPicture exactly")
        void theReceiverTruncatesExactlyAsStoreAtPictureDoes() {
            for (String sent : List.of("15.999", "123456.789", "-123456.789", "0.005", "9999.994")) {
                DisclosureGroupRecord record = new DisclosureGroupRecord(ASCII);
                record.disIntRate(new BigDecimal(sent));

                assertThat(record.disIntRate())
                        .as("stored %s", sent)
                        .isEqualByComparingTo(CobolDecimal.storeAtPicture(new BigDecimal(sent),
                                DisclosureGroupRecord.DIS_INT_RATE_INTEGER_DIGITS,
                                DisclosureGroupRecord.DIS_INT_RATE_SCALE));
            }
        }

        @Test
        @DisplayName("G25 - the rate this record supplies is read from offset 16 at scale 2")
        void theRateFeedsTheInterestFormulaAtTheRightOffsetAndScale() {
            DisclosureGroupRecord fixtureRow = row1();
            BigDecimal rate = fixtureRow.disIntRate();

            assertThat(fixtureRow.disIntRateImage())
                    .as("read from bytes 16..21 of a real fixture row")
                    .isEqualTo(ROW_1.substring(16, 22));
            assertThat(rate).isEqualTo(new BigDecimal("15.00")).hasScaleOf(2);

            BigDecimal balance = new BigDecimal("99.99");
            assertThat(CobolDecimal.monthlyInterest(balance, rate))
                    .isEqualTo(new BigDecimal("1.24"))
                    .isNotEqualTo(new BigDecimal("1.25"))
                    .hasScaleOf(2);

            DisclosureGroupRecord zeroRateRow = withRateImage(RATE_ZERO_IMAGE);
            assertThat(zeroRateRow.disIntRateIsZero()).isTrue();
            assertThat(CobolDecimal.monthlyInterest(balance, zeroRateRow.disIntRate()))
                    .as("which is why L214 guards the computation rather than relying on the result")
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("G25 - DIS-INT-RATE is an annual percentage, so 15.00 means 15% APR")
        void theRateIsAnAnnualPercentage() {
            assertThat(CobolDecimal.MONTHLY_INTEREST_DIVISOR).isEqualTo(1200L);

            DisclosureGroupRecord record = withRateImage(ROW_1_RATE_IMAGE);
            assertThat(CobolDecimal.monthlyInterest(new BigDecimal("1000.00"), record.disIntRate()))
                    .isEqualByComparingTo(new BigDecimal("12.50"));
        }

        @Test
        @DisplayName("The rate accessor rejects null rather than storing a default")
        void theRateAccessorRejectsNull() {
            DisclosureGroupRecord record = new DisclosureGroupRecord(ASCII);
            assertThatNullPointerException().isThrownBy(() -> record.disIntRate(null));
        }
    }

    @Nested
    @DisplayName("Sign overpunch - the branches no fixture row reaches")
    class SignOverpunch {
        @ParameterizedTest
        @CsvSource({
            "00150{,     15.00,         zone C positive digit 0",
            "00150A,     15.01,         zone C positive digit 1",
            "00150E,     15.05,         zone C positive digit 5",
            "00150I,     15.09,         zone C positive digit 9",
            "00150},    -15.00,         zone D negative digit 0",
            "00150J,    -15.01,         zone D negative digit 1",
            "00150N,    -15.05,         zone D negative digit 5",
            "00150R,    -15.09,         zone D negative digit 9",
            "001500,     15.00,         zone F unsigned, taken as positive"
        })
        @DisplayName("Every overpunch zone decodes to the right signed value at scale 2")
        void everyOverpunchZoneDecodesCorrectly(String image, String expected, String zone) {
            DisclosureGroupRecord record = withRateImage(image);

            assertThat(record.disIntRate())
                    .as("%s is %s", image, zone)
                    .isEqualTo(new BigDecimal(expected))
                    .hasScaleOf(2);
            assertThat(record.disIntRateImage()).isEqualTo(image).hasSize(6);

            assertThat(record.encodeToString())
                    .isEqualTo(row(GROUP_ID_A, "01", "0001", image, FIXTURE_FILLER));
        }

        @ParameterizedTest
        @CsvSource({
            "15.00,   00150{",
            "15.01,   00150A",
            "15.09,   00150I",
            "-15.00,  00150}",
            "-15.01,  00150J",
            "-15.09,  00150R",
            "0.01,    00000A",
            "-0.01,   00000J",
            "0.00,    00000{"
        })
        @DisplayName("Every signed value encodes to its overpunched image in exactly 6 bytes")
        void everySignedValueEncodesToItsOverpunchedImage(String value, String expectedImage) {
            DisclosureGroupRecord record = new DisclosureGroupRecord(ASCII);
            record.disIntRate(new BigDecimal(value));

            assertThat(record.disIntRateImage())
                    .isEqualTo(expectedImage)
                    .as("the sign never occupies a byte of its own")
                    .hasSize(DisclosureGroupRecord.DIS_INT_RATE_LENGTH);
            assertThat(record.encode()).hasSize(DisclosureGroupRecord.RECORD_LENGTH);
        }

        @ParameterizedTest
        @ValueSource(strings = {"15.00", "-15.00", "15.01", "-15.01", "0.01", "-0.01", "0.00",
            "9999.99", "-9999.99"})
        @DisplayName("Store then read then store again is stable for both signs")
        void storeAndReadAreStableForBothSigns(String value) {
            DisclosureGroupRecord first = new DisclosureGroupRecord(ASCII);
            first.disIntRate(new BigDecimal(value));

            DisclosureGroupRecord second = DisclosureGroupRecord.decode(first.encode(), ASCII);
            assertThat(second.disIntRate()).isEqualTo(first.disIntRate()).hasScaleOf(2);
            assertThat(second.disIntRateImage()).isEqualTo(first.disIntRateImage());

            DisclosureGroupRecord third = new DisclosureGroupRecord(ASCII);
            third.disIntRate(second.disIntRate());
            assertThat(third.disIntRateImage()).isEqualTo(first.disIntRateImage());
            assertThat(Arrays.equals(third.encode(), first.encode()))
                    .as("a re-store of a decoded value reproduces the same bytes")
                    .isTrue();
        }

        @Test
        @DisplayName("A negative rate is non-zero and truncates toward zero, not toward minus infinity")
        void aNegativeRateTruncatesTowardZero() {
            DisclosureGroupRecord record = new DisclosureGroupRecord(ASCII);
            record.disIntRate(new BigDecimal("-15.999"));

            assertThat(record.disIntRate())
                    .isEqualTo(new BigDecimal("-15.99"))
                    .isNotEqualTo(new BigDecimal("-16.00"));
            assertThat(record.disIntRate()).isLessThan(BigDecimal.ZERO);
            assertThat(record.disIntRateIsZero()).isFalse();
            assertThat(record.disIntRateIsNotZero()).isTrue();
        }

        @Test
        @DisplayName("An unrecognised trailing character fails loudly rather than decoding to a number")
        void anUnrecognisedTrailingCharacterIsRejected() {
            DisclosureGroupRecord broken = withRateImage("00150*");

            assertThat(broken.disIntRateImage()).isEqualTo("00150*");
            assertThatIllegalArgumentException().isThrownBy(broken::disIntRate)
                    .withMessageContaining("neither a digit nor a sign overpunch character");
            assertThatIllegalArgumentException().isThrownBy(broken::disIntRateIsZero);
        }

        @Test
        @DisplayName("A non-digit in a leading position is rejected too")
        void aNonDigitInALeadingPositionIsRejected() {
            DisclosureGroupRecord broken = withRateImage("0 150{");

            assertThatIllegalArgumentException().isThrownBy(broken::disIntRate)
                    .withMessageContaining("holds only the digits 0 to 9");
        }
    }

    @Nested
    @DisplayName("The DIS-GROUP-KEY overlay - gate G34, two accessors over one span")
    class RedefinesOverlay {
        @Test
        @DisplayName("The key overlay and its three members address exactly the same bytes")
        void theOverlayAndItsMembersShareOneBackingSpan() {
            FieldSpan key = DisclosureGroupRecord.DIS_GROUP_KEY_SPAN;

            assertThat(key.redefinition()).as("a group item is an overlay, not extra storage").isTrue();
            assertThat(key.offset()).isEqualTo(DisclosureGroupRecord.DIS_ACCT_GROUP_ID_SPAN.offset());
            assertThat(key.endOffsetExclusive())
                    .isEqualTo(DisclosureGroupRecord.DIS_TRAN_CAT_CD_SPAN.endOffsetExclusive());

            DisclosureGroupRecord record = row1();
            assertThat(record.disGroupKey())
                    .isEqualTo(record.disAcctGroupId() + record.disTranTypeCd()
                            + record.disTranCatCdImage());
        }

        @Test
        @DisplayName("Writing through a member is observed through the overlay, and the row bytes")
        void aWriteThroughAMemberIsSeenThroughTheOverlay() {
            DisclosureGroupRecord record = row1();
            assertThat(record.disGroupKey()).isEqualTo("A000000000" + "01" + "0001");

            record.disAcctGroupId(DisclosureGroupRecord.DEFAULT_ACCT_GROUP_ID);
            assertThat(record.disGroupKey())
                    .as("the overlay sees the padded literal immediately")
                    .isEqualTo("DEFAULT   " + "01" + "0001");

            record.disTranTypeCd("07");
            assertThat(record.disGroupKey()).isEqualTo("DEFAULT   " + "07" + "0001");

            record.disTranCatCd(4);
            assertThat(record.disGroupKey()).isEqualTo("DEFAULT   " + "07" + "0004");

            assertThat(record.encodeToString().substring(0, 16)).isEqualTo(record.disGroupKey());
            assertThat(new String(record.disGroupKeyBytes(), ASCII)).isEqualTo(record.disGroupKey());
        }

        @Test
        @DisplayName("Reading the overlay never disturbs its members, and the round trip is exact")
        void readingTheOverlayIsNonDestructive() {
            DisclosureGroupRecord record = row1();

            String beforeKey = record.disGroupKey();
            byte[] beforeBytes = record.disGroupKeyBytes();

            for (int repeat = 0; repeat < 3; repeat++) {
                assertThat(record.disGroupKey()).isEqualTo(beforeKey);
                assertThat(record.disGroupKeyBytes()).isEqualTo(beforeBytes);
                assertThat(record.disAcctGroupId()).isEqualTo(GROUP_ID_A);
                assertThat(record.disTranTypeCd()).isEqualTo("01");
                assertThat(record.disTranCatCd()).isEqualTo(1);
            }
            assertThat(record.encodeToString()).isEqualTo(ROW_1);
        }

        @Test
        @DisplayName("Every exposed byte array is a copy, so no caller can mutate the record")
        void everyExposedByteArrayIsACopy() {
            DisclosureGroupRecord record = row1();

            byte[] keyBytes = record.disGroupKeyBytes();
            byte[] fillerBytes = record.fillerBytes();
            byte[] whole = record.encode();
            assertThat(keyBytes).isNotSameAs(record.disGroupKeyBytes());
            assertThat(fillerBytes).isNotSameAs(record.fillerBytes());
            assertThat(whole).isNotSameAs(record.encode());

            Arrays.fill(keyBytes, (byte) 'Z');
            Arrays.fill(fillerBytes, (byte) 'Z');
            Arrays.fill(whole, (byte) 'Z');

            assertThat(record.disGroupKey()).isEqualTo("A000000000" + "01" + "0001");
            assertThat(record.filler()).isEqualTo(FIXTURE_FILLER);
            assertThat(record.encodeToString()).isEqualTo(ROW_1);
        }

        @Test
        @DisplayName("Decode copies its input, so mutating the caller's array cannot alter the record")
        void decodeCopiesItsInput() {
            byte[] source = ROW_1.getBytes(ASCII);
            DisclosureGroupRecord record = DisclosureGroupRecord.decode(source, ASCII);

            Arrays.fill(source, (byte) 'Z');

            assertThat(record.encodeToString()).isEqualTo(ROW_1);
            assertThat(record.disIntRate()).isEqualTo(new BigDecimal("15.00"));
        }

        @Test
        @DisplayName("A full key round trip: build from parts, read the whole, decode it back")
        void aFullKeyRoundTripThroughBothViews() {
            DisclosureGroupRecord built = new DisclosureGroupRecord(ASCII);
            built.disAcctGroupId(GROUP_ID_A);
            built.disTranTypeCd("01");
            built.disTranCatCd(1);
            built.disIntRate(new BigDecimal("15.00"));

            String key = built.disGroupKey();
            assertThat(key).hasSize(16).isEqualTo(ROW_1.substring(0, 16));

            DisclosureGroupRecord reread = DisclosureGroupRecord.decode(built.encode(), ASCII);
            assertThat(reread.disGroupKey()).isEqualTo(key);
            assertThat(reread.disGroupKeyBytes()).isEqualTo(built.disGroupKeyBytes());
            assertThat(reread.disAcctGroupId()).isEqualTo(GROUP_ID_A);
            assertThat(reread.disTranTypeCd()).isEqualTo("01");
            assertThat(reread.disTranCatCd()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Value semantics - equals, hashCode and toString")
    class ValueSemantics {
        @Test
        @DisplayName("Equal items mean equal records, and equal hash codes")
        void equalItemsMeanEqualRecords() {
            DisclosureGroupRecord one = row1();
            DisclosureGroupRecord other = row1();

            assertThat(one).isEqualTo(other).hasSameHashCodeAs(other);
            assertThat(other).isEqualTo(one);
            assertThat(one).isEqualTo(one);
            assertThat(one.hashCode()).isEqualTo(one.hashCode());
        }

        @Test
        @DisplayName("A difference in the X(10) item alone breaks equality")
        void aDifferenceInTheAlphanumericItemBreaksEquality() {
            DisclosureGroupRecord base = row1();
            DisclosureGroupRecord changed = row1();
            changed.disAcctGroupId(DisclosureGroupRecord.DEFAULT_ACCT_GROUP_ID);

            assertThat(changed).isNotEqualTo(base);
            assertThat(changed.disTranTypeCd()).isEqualTo(base.disTranTypeCd());
            assertThat(changed.disIntRate()).isEqualTo(base.disIntRate());
        }

        @Test
        @DisplayName("A difference in the X(02) item alone breaks equality, leading zero included")
        void aDifferenceInTheTypeCodeBreaksEquality() {
            DisclosureGroupRecord base = row1();
            DisclosureGroupRecord changed = row1();
            changed.disTranTypeCd("02");

            assertThat(changed).isNotEqualTo(base);

            DisclosureGroupRecord unpadded = row1();
            unpadded.disTranTypeCd("1");
            assertThat(unpadded.disTranTypeCd()).isEqualTo("1 ");
            assertThat(unpadded).isNotEqualTo(base);
        }

        @Test
        @DisplayName("A difference in the 9(04) item alone breaks equality")
        void aDifferenceInTheNumericItemBreaksEquality() {
            DisclosureGroupRecord base = row1();
            DisclosureGroupRecord changed = row1();
            changed.disTranCatCd(2);

            assertThat(changed).isNotEqualTo(base);
            assertThat(changed.disTranCatCdImage()).isEqualTo("0002");
        }

        @Test
        @DisplayName("A difference in the S9(04)V99 item alone breaks equality")
        void aDifferenceInTheScaledItemBreaksEquality() {
            DisclosureGroupRecord base = row1();
            DisclosureGroupRecord changed = row1();
            changed.disIntRate(new BigDecimal("25.00"));

            assertThat(changed).isNotEqualTo(base);

            DisclosureGroupRecord negated = row1();
            negated.disIntRate(new BigDecimal("-15.00"));
            assertThat(negated).isNotEqualTo(base);
        }

        @Test
        @DisplayName("A difference in FILLER alone breaks equality - no declared byte is excluded")
        void aDifferenceInFillerBreaksEquality() {
            DisclosureGroupRecord stored = row1();
            DisclosureGroupRecord fresh = new DisclosureGroupRecord(ASCII);
            fresh.disAcctGroupId(GROUP_ID_A);
            fresh.disTranTypeCd("01");
            fresh.disTranCatCd(1);
            fresh.disIntRate(new BigDecimal("15.00"));

            assertThat(fresh.filler()).isEqualTo(" ".repeat(28));
            assertThat(stored.filler()).isEqualTo(FIXTURE_FILLER);
            assertThat(fresh)
                    .as("the four data items match; only FILLER differs, and that is enough")
                    .isNotEqualTo(stored);
        }

        @Test
        @DisplayName("The BigDecimal scale trap: 15.0 and 15.00 are not equal, yet both store the same")
        void differentSendingScalesNormaliseToOneStoredValue() {
            assertThat(new BigDecimal("15.0").equals(new BigDecimal("15.00"))).isFalse();
            assertThat(new BigDecimal("15.0")).isEqualByComparingTo(new BigDecimal("15.00"));

            DisclosureGroupRecord oneDecimal = row1();
            DisclosureGroupRecord twoDecimals = row1();
            oneDecimal.disIntRate(new BigDecimal("15.0"));
            twoDecimals.disIntRate(new BigDecimal("15.00"));

            assertThat(oneDecimal.disIntRate()).isEqualTo(twoDecimals.disIntRate()).hasScaleOf(2);
            assertThat(oneDecimal).isEqualTo(twoDecimals).hasSameHashCodeAs(twoDecimals);
            assertThat(oneDecimal.disIntRateImage()).isEqualTo(twoDecimals.disIntRateImage());
            assertThat(oneDecimal.encode()).isEqualTo(twoDecimals.encode());
        }

        @Test
        @DisplayName("Item equality is not byte equality, and the unsigned zoned form proves it")
        void itemEqualityIsNotByteEquality() {
            DisclosureGroupRecord unsigned = withRateImage("001500");
            DisclosureGroupRecord overpunched = withRateImage(ROW_1_RATE_IMAGE);

            assertThat(unsigned).isEqualTo(overpunched).hasSameHashCodeAs(overpunched);
            assertThat(unsigned.encode())
                    .as("equal items, different bytes")
                    .isNotEqualTo(overpunched.encode());
            assertThat(unsigned.disIntRateImage()).isNotEqualTo(overpunched.disIntRateImage());
        }

        @Test
        @DisplayName("A record never equals null, a String, or an unrelated type")
        void aRecordNeverEqualsNullOrAnotherType() {
            DisclosureGroupRecord record = row1();

            assertThat(record.equals(null)).isFalse();
            assertThat(record.equals(ROW_1)).isFalse();
            assertThat(record.equals(record.disIntRate())).isFalse();
            assertThat(record).isNotEqualTo(new Object());
        }

        @Test
        @DisplayName("toString names every item as the copybook spells it and hides no padding")
        void toStringSpeaksTheCopybooksVocabulary() {
            String rendered = row1().toString();

            assertThat(rendered)
                    .startsWith("DisclosureGroupRecord[")
                    .contains("DIS-ACCT-GROUP-ID='A000000000'")
                    .contains("DIS-TRAN-TYPE-CD='01'")
                    .contains("DIS-TRAN-CAT-CD=0001 (1)")
                    .contains("DIS-INT-RATE=15.00 (image '00150{')")
                    .contains("FILLER='" + FIXTURE_FILLER + "'")
                    .contains("charset=US-ASCII")
                    .endsWith("]");

            assertThat(rendered).isEqualTo(row1().toString());

            DisclosureGroupRecord padded = row1();
            padded.disAcctGroupId(DisclosureGroupRecord.DEFAULT_ACCT_GROUP_ID);
            assertThat(padded.toString()).contains("DIS-ACCT-GROUP-ID='DEFAULT   '");
        }
    }

    @Nested
    @DisplayName("Schema and state integrity - gates G44 and G53")
    class SchemaIntegrity {
        private static final Set<String> FORBIDDEN_ANNOTATIONS =
                Set.of("Entity", "Table", "Id", "Column", "Version", "GeneratedValue", "Embeddable",
                        "MappedSuperclass", "JoinColumn", "SequenceGenerator");

        private static final Set<String> DDL_KEYWORDS =
                Set.of("CREATE TABLE", "ALTER TABLE", "DROP TABLE", "CREATE INDEX", "PRIMARY KEY",
                        "FOREIGN KEY", "INSERT INTO", "SELECT ", "UPDATE ", "DELETE FROM");

        @Test
        @DisplayName("G44 - the type carries no persistence-mapping annotation at any level")
        void theTypeCarriesNoPersistenceMapping() {
            assertThat(DisclosureGroupRecord.class.getAnnotations())
                    .as("a copybook model is not a mapped entity")
                    .isEmpty();

            for (Field field : DisclosureGroupRecord.class.getDeclaredFields()) {
                for (Annotation annotation : field.getAnnotations()) {
                    assertThat(FORBIDDEN_ANNOTATIONS)
                            .as("field %s carries @%s", field.getName(),
                                    annotation.annotationType().getSimpleName())
                            .doesNotContain(annotation.annotationType().getSimpleName());
                }
            }
            for (Method method : DisclosureGroupRecord.class.getDeclaredMethods()) {
                for (Annotation annotation : method.getAnnotations()) {
                    assertThat(FORBIDDEN_ANNOTATIONS)
                            .as("method %s carries @%s", method.getName(),
                                    annotation.annotationType().getSimpleName())
                            .doesNotContain(annotation.annotationType().getSimpleName());
                }
            }
        }

        @Test
        @DisplayName("G44 - no field implies a row version, and no constant holds a DDL statement")
        void noRowVersionAndNoEmbeddedDdl() throws IllegalAccessException {
            for (Field field : DisclosureGroupRecord.class.getDeclaredFields()) {
                assertThat(field.getName().toLowerCase(Locale.ROOT))
                        .as("no field may imply a row version or a schema concept")
                        .doesNotContain("version")
                        .doesNotContain("sequence")
                        .doesNotContain("tablename");
            }

            for (Field field : DisclosureGroupRecord.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                    field.setAccessible(true);
                    String value = (String) field.get(null);
                    assertThat(value).as("constant %s must not hold SQL", field.getName()).isNotNull();
                    String upper = value.toUpperCase(Locale.ROOT);
                    for (String keyword : DDL_KEYWORDS) {
                        assertThat(upper)
                                .as("constant %s must not hold DDL or DML", field.getName())
                                .doesNotContain(keyword);
                    }
                }
            }
        }

        @Test
        @DisplayName("G44 - no dataset name is hard-coded into the model")
        void noDatasetNameIsHardCodedIntoTheModel() throws IllegalAccessException {
            for (Field field : DisclosureGroupRecord.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                    field.setAccessible(true);
                    assertThat((String) field.get(null))
                            .as("constant %s must not name a dataset", field.getName())
                            .doesNotContain("AWS.M2.CARDDEMO")
                            .doesNotContain("VSAM");
                }
            }
        }

        @Test
        @DisplayName("G53 - every static field is final and of a deeply immutable type")
        void everyStaticFieldIsFinalAndImmutable() {
            Set<Class<?>> immutableTypes = Set.of(int.class, long.class, boolean.class, char.class,
                    short.class, byte.class, String.class, FieldSpan.class, RecordLayout.class,
                    BigDecimal.class, RoundingMode.class, PictureKind.class, Charset.class);
            int staticFields = 0;

            for (Field field : DisclosureGroupRecord.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                staticFields++;
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("static field %s must be final", field.getName())
                        .isTrue();
                assertThat(field.getType().isArray())
                        .as("static field %s must not be an array - arrays are always mutable",
                                field.getName())
                        .isFalse();
                assertThat(immutableTypes)
                        .as("static field %s is of mutable type %s", field.getName(),
                                field.getType().getName())
                        .contains(field.getType());
            }

            assertThat(staticFields).as("the geometry constants and the layout").isPositive();
        }

        @Test
        @DisplayName("G53 - the instance fields are final too, and the byte area never escapes")
        void theInstanceFieldsAreFinalAndTheAreaNeverEscapes() {
            for (Field field : DisclosureGroupRecord.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("instance field %s must be final", field.getName())
                        .isTrue();
                assertThat(Modifier.isPublic(field.getModifiers()))
                        .as("instance field %s must not be public", field.getName())
                        .isFalse();
                assertThat(field.getType().isArray())
                        .as("instance field %s must not expose a raw array", field.getName())
                        .isFalse();
            }

            DisclosureGroupRecord one = row1();
            DisclosureGroupRecord other = row1();
            one.disIntRate(new BigDecimal("25.00"));
            assertThat(other.disIntRate()).isEqualTo(new BigDecimal("15.00"));
        }

        @Test
        @DisplayName("G53 - this test class itself holds no mutable static state")
        void theTestClassItselfHoldsNoMutableStaticState() {
            Set<Class<?>> immutableTypes = Set.of(int.class, String.class, Charset.class);

            for (Field field : DisclosureGroupRecordTest.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("static test field %s must be final", field.getName())
                        .isTrue();
                assertThat(immutableTypes)
                        .as("static test field %s is of mutable type %s", field.getName(),
                                field.getType().getName())
                        .contains(field.getType());
            }

            assertThat(fixtureRows()).isNotSameAs(fixtureRows()).isEqualTo(fixtureRows());
        }

        @Test
        @DisplayName("G53 - no nested test class holds mutable static state either")
        void noNestedTestClassHoldsMutableStaticState() {
            int inspected = 0;

            for (Class<?> nested : DisclosureGroupRecordTest.class.getDeclaredClasses()) {
                for (Field field : nested.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    inspected++;
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("static field %s.%s must be final", nested.getSimpleName(),
                                    field.getName())
                            .isTrue();
                    assertThat(field.getType().isArray())
                            .as("static field %s.%s must not be an array", nested.getSimpleName(),
                                    field.getName())
                            .isFalse();
                }
            }

            assertThat(inspected)
                    .as("FORBIDDEN_ANNOTATIONS and DDL_KEYWORDS are the ones being checked")
                    .isGreaterThanOrEqualTo(2);
            assertThat(FORBIDDEN_ANNOTATIONS).isUnmodifiable();
            assertThat(DDL_KEYWORDS).isUnmodifiable();
        }

        @Test
        @DisplayName("G8 - exactly one Java type models CVTRA02Y, and it is final")
        void exactlyOneFinalTypeModelsTheCopybook() {
            assertThat(Modifier.isFinal(DisclosureGroupRecord.class.getModifiers()))
                    .as("a copybook model has no subtype: the layout is the layout")
                    .isTrue();
            assertThat(DisclosureGroupRecord.class.getSuperclass()).isEqualTo(Object.class);
            assertThat(DisclosureGroupRecord.class.getInterfaces()).isEmpty();
            assertThat(DisclosureGroupRecord.class.getPackageName())
                    .isEqualTo("com.vsergeychik.carddemo.account.model");
        }
    }
}
