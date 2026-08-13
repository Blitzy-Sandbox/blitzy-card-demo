package com.vsergeychik.carddemo.card.model;

import com.vsergeychik.carddemo.common.DiagnosticText;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link CardXrefRecord}, the one Java type for {@code app/cpy/CVACT03Y.cpy}'s
 * {@code 01 CARD-XREF-RECORD} - exactly 50 bytes, and the most widely shared cross-reference layout in the
 * system.
 */
@DisplayName("CardXrefRecord - CVACT03Y CARD-XREF-RECORD, 50 bytes, 12 consumers")
class CardXrefRecordTest {
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    private static final String EBCDIC_NAME = "IBM037";

    private static final Charset EBCDIC = requireEbcdicCharset();

    private static final String FIXTURE = "/fixtures/cardxref.txt";

    private static final int FIXTURE_ROW_WIDTH = 36;

    private static final int FIXTURE_ROW_COUNT = 50;

    private static final String FIXTURE_ROW_1 = "050002445376574000000005000000000050";

    private static final String FIXTURE_ROW_2 = "068358619817151600000002700000000027";

    private static final String FIXTURE_ROW_3 = "092387719324733000000000200000000002";

    private static final String ROW_1_CARD_NUM = "0500024453765740";

    private static final int ROW_1_CUST_ID = 50;

    private static final long ROW_1_ACCT_ID = 50L;

    private static final String FOURTEEN_SPACES = "              ";

    private final FixedWidthCodec asciiCodec = new FixedWidthCodec(ASCII);

    private final FixedWidthCodec ebcdicCodec = new FixedWidthCodec(EBCDIC);

    private static Charset requireEbcdicCharset() {
        if (!Charset.isSupported(EBCDIC_NAME)) {
            throw new IllegalStateException("Charset " + EBCDIC_NAME + " is required to verify that "
                    + "the code page is honoured as a parameter rather than defaulted. It ships in "
                    + "the JDK's jdk.charsets module and is present in OpenJDK 21.0.11, the verified "
                    + "toolchain for this module, so its absence means the run is on a cut-down "
                    + "runtime: install a full JDK rather than skipping this verification");
        }
        return Charset.forName(EBCDIC_NAME);
    }

    private static CardXrefRecord sample() {
        return new CardXrefRecord(ROW_1_CARD_NUM, ROW_1_CUST_ID, ROW_1_ACCT_ID);
    }

    private static List<String> fixtureRows() {
        List<String> rows = new ArrayList<>();
        try (InputStream stream = CardXrefRecordTest.class.getResourceAsStream(FIXTURE)) {
            assertThat(stream)
                    .as("the derived fixture must be on the test classpath at %s, copied from "
                            + "app/data/ASCII/cardxref.txt into "
                            + "app/java/src/test/resources/fixtures/cardxref.txt", FIXTURE)
                    .isNotNull();
            String content = new String(stream.readAllBytes(), ASCII);
            for (String line : content.split("\n", -1)) {
                String row = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
                if (!row.isEmpty()) {
                    rows.add(row);
                }
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("Unable to read the CARDXREF fixture " + FIXTURE, failure);
        }
        return rows;
    }

    private static List<Arguments> cardNumberPaddingCases() {
        return List.of(Arguments.of("", " ".repeat(16)),
                Arguments.of("0", "0" + " ".repeat(15)),
                Arguments.of("ABC", "ABC" + " ".repeat(13)),
                Arguments.of("012345678901234", "012345678901234 "),
                Arguments.of("0123456789012345", "0123456789012345"));
    }

    private static List<Arguments> fixtureRowCases() {
        List<Arguments> cases = new ArrayList<>();
        List<String> rows = fixtureRows();
        for (int index = 0; index < rows.size(); index++) {
            cases.add(Arguments.of(index + 1, rows.get(index)));
        }
        return cases;
    }

    private byte[] widenThroughTheSharedNormaliser(String storedRow) {
        return asciiCodec.padToDeclaredWidth(storedRow.getBytes(ASCII), CardXrefRecord.RECORD_LENGTH);
    }

    @Nested
    @DisplayName("Declared geometry - the copybook's own arithmetic (G8, G19)")
    class DeclaredGeometry {
        @Test
        @DisplayName("RECLN 50: the four declared spans sum to exactly the declared record length")
        void spansSumToTheDeclaredRecordLength() {
            int sum = 0;
            for (FieldSpan span : CardXrefRecord.LAYOUT.storageSpans()) {
                sum += span.length();
            }

            assertThat(CardXrefRecord.RECORD_LENGTH)
                    .as("CVACT03Y's header declares RECLN 50")
                    .isEqualTo(50);
            assertThat(CardXrefRecord.LAYOUT.recordLength())
                    .as("the layout's declared length must equal RECORD_LENGTH")
                    .isEqualTo(CardXrefRecord.RECORD_LENGTH);
            assertThat(sum).isEqualTo(16 + 9 + 11 + 14).isEqualTo(50);
        }

        @Test
        @DisplayName("Four storage spans in copybook order, and no REDEFINES overlay")
        void declaresFourStorageSpansAndNoOverlay() {
            assertThat(CardXrefRecord.LAYOUT.storageSpans())
                    .containsExactly(CardXrefRecord.XREF_CARD_NUM,
                            CardXrefRecord.XREF_CUST_ID,
                            CardXrefRecord.XREF_ACCT_ID,
                            CardXrefRecord.FILLER);
            assertThat(CardXrefRecord.LAYOUT.redefinitions()).isEmpty();
        }

        @ParameterizedTest(name = "span {0}: {1} at offset {2}, length {3}, {4}")
        @CsvSource({
                "0, XREF-CARD-NUM,  0, 16, ALPHANUMERIC",
                "1, XREF-CUST-ID,  16,  9, UNSIGNED_NUMERIC",
                "2, XREF-ACCT-ID,  25, 11, UNSIGNED_NUMERIC",
                "3, FILLER,        36, 14, FILLER"
        })
        @DisplayName("Every span sits at its literal copybook offset with its literal copybook width")
        void spanGeometryIsLiteralAndPositional(int index,
                                                String cobolName,
                                                int offset,
                                                int length,
                                                PictureKind kind) {
            FieldSpan span = CardXrefRecord.LAYOUT.storageSpans().get(index);

            assertThat(span.name())
                    .as("span %d of CVACT03Y must be %s, verbatim", index, cobolName)
                    .isEqualTo(cobolName);
            assertThat(span.offset())
                    .as("%s must start at absolute 0-based offset %d", cobolName, offset)
                    .isEqualTo(offset);
            assertThat(span.length())
                    .as("%s at offset %d must be %d byte(s) wide", cobolName, offset, length)
                    .isEqualTo(length);
            assertThat(span.endOffsetExclusive())
                    .as("%s at offset %d must end at %d", cobolName, offset, offset + length)
                    .isEqualTo(offset + length);
            assertThat(span.kind())
                    .as("%s at offset %d carries the %s picture", cobolName, offset, kind)
                    .isEqualTo(kind);
            assertThat(span.hasInitialValue())
                    .as("%s declares no VALUE in the copybook", cobolName)
                    .isFalse();
            assertThat(span.redefinition())
                    .as("%s is storage, not a REDEFINES overlay", cobolName)
                    .isFalse();
        }

        @Test
        @DisplayName("The spans are contiguous from offset 0: no gap, no overlap, closing at 50")
        void spansAreContiguousWithNoGapAndNoOverlap() {
            List<FieldSpan> spans = CardXrefRecord.LAYOUT.storageSpans();

            assertThat(spans).as("CVACT03Y declares four 05-items at L5-L8").hasSize(4);
            assertThat(spans.get(0).offset())
                    .as("%s must open the record at offset 0", spans.get(0).name())
                    .isZero();
            for (int index = 0; index < spans.size() - 1; index++) {
                FieldSpan current = spans.get(index);
                FieldSpan next = spans.get(index + 1);
                int expectedNextOffset = current.offset() + current.length();

                assertThat(next.offset())
                        .as("%s occupies [%d, %d), so %s must start at %d - a gap would leave a byte "
                                        + "undeclared and an overlap would double-count one",
                                current.name(), current.offset(), expectedNextOffset,
                                next.name(), expectedNextOffset)
                        .isEqualTo(expectedNextOffset);
            }
            FieldSpan last = spans.get(spans.size() - 1);
            assertThat(last.offset() + last.length())
                    .as("the trailing %s at offset %d must close the record at %d",
                            last.name(), last.offset(), CardXrefRecord.RECORD_LENGTH)
                    .isEqualTo(CardXrefRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("The published offset and length constants agree with the layout descriptors")
        void publishedConstantsAgreeWithTheDescriptors() {
            assertThat(CardXrefRecord.XREF_CARD_NUM_OFFSET).isZero();
            assertThat(CardXrefRecord.XREF_CARD_NUM_LENGTH).isEqualTo(16);
            assertThat(CardXrefRecord.XREF_CUST_ID_OFFSET).isEqualTo(16);
            assertThat(CardXrefRecord.XREF_CUST_ID_LENGTH).isEqualTo(9);
            assertThat(CardXrefRecord.XREF_ACCT_ID_OFFSET).isEqualTo(25);
            assertThat(CardXrefRecord.XREF_ACCT_ID_LENGTH).isEqualTo(11);
            assertThat(CardXrefRecord.FILLER_OFFSET).isEqualTo(36);
            assertThat(CardXrefRecord.FILLER_LENGTH).isEqualTo(14);

            assertThat(CardXrefRecord.XREF_CARD_NUM.offset())
                    .isEqualTo(CardXrefRecord.XREF_CARD_NUM_OFFSET);
            assertThat(CardXrefRecord.XREF_CARD_NUM.length())
                    .isEqualTo(CardXrefRecord.XREF_CARD_NUM_LENGTH);
            assertThat(CardXrefRecord.XREF_CUST_ID.offset())
                    .isEqualTo(CardXrefRecord.XREF_CUST_ID_OFFSET);
            assertThat(CardXrefRecord.XREF_CUST_ID.length())
                    .isEqualTo(CardXrefRecord.XREF_CUST_ID_LENGTH);
            assertThat(CardXrefRecord.XREF_ACCT_ID.offset())
                    .isEqualTo(CardXrefRecord.XREF_ACCT_ID_OFFSET);
            assertThat(CardXrefRecord.XREF_ACCT_ID.length())
                    .isEqualTo(CardXrefRecord.XREF_ACCT_ID_LENGTH);
            assertThat(CardXrefRecord.FILLER.offset()).isEqualTo(CardXrefRecord.FILLER_OFFSET);
            assertThat(CardXrefRecord.FILLER.length()).isEqualTo(CardXrefRecord.FILLER_LENGTH);
        }

        @Test
        @DisplayName("Field names are carried verbatim from the copybook, hyphens and all")
        void fieldNamesAreVerbatim() {
            assertThat(CardXrefRecord.XREF_CARD_NUM_NAME).isEqualTo("XREF-CARD-NUM");
            assertThat(CardXrefRecord.XREF_CUST_ID_NAME).isEqualTo("XREF-CUST-ID");
            assertThat(CardXrefRecord.XREF_ACCT_ID_NAME).isEqualTo("XREF-ACCT-ID");
            assertThat(CardXrefRecord.XREF_CARD_NUM.name())
                    .isEqualTo(CardXrefRecord.XREF_CARD_NUM_NAME);
            assertThat(CardXrefRecord.XREF_CUST_ID.name())
                    .isEqualTo(CardXrefRecord.XREF_CUST_ID_NAME);
            assertThat(CardXrefRecord.XREF_ACCT_ID.name())
                    .isEqualTo(CardXrefRecord.XREF_ACCT_ID_NAME);
            assertThat(CardXrefRecord.LAYOUT.hasSpan("XREF-CARD-NUM")).isTrue();
            assertThat(CardXrefRecord.LAYOUT.hasSpan("xref-card-num")).isFalse();
            assertThat(CardXrefRecord.LAYOUT.hasSpan("XREF_CARD_NUM")).isFalse();
        }

        @Test
        @DisplayName("The trailing FILLER is a first-class span at [36, 50), not an implicit gap (G21)")
        void fillerIsAFirstClassSpan() {
            assertThat(CardXrefRecord.FILLER.kind()).isEqualTo(PictureKind.FILLER);
            assertThat(CardXrefRecord.FILLER.kind().filler()).isTrue();
            assertThat(CardXrefRecord.FILLER.endOffsetExclusive())
                    .isEqualTo(CardXrefRecord.RECORD_LENGTH);
            assertThat(CardXrefRecord.FILLER.hasInitialValue()).isFalse();
            assertThat(CardXrefRecord.LAYOUT.hasSpan("FILLER")).isFalse();
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CardXrefRecord.LAYOUT.span("FILLER"))
                    .withMessageContaining("FILLER is not referable");
            assertThat(CardXrefRecord.RECORD_LENGTH - CardXrefRecord.FILLER_LENGTH)
                    .isEqualTo(CardXrefRecord.FILLER_OFFSET)
                    .isEqualTo(FIXTURE_ROW_WIDTH);
        }

        @Test
        @DisplayName("The picture bounds are derived from the declared digit counts")
        void pictureBoundsFollowTheDeclaredDigitCounts() {
            assertThat(CardXrefRecord.XREF_CUST_ID_MAX_VALUE)
                    .as("PIC 9(09) holds nine nines")
                    .isEqualTo(999_999_999);
            assertThat(CardXrefRecord.XREF_ACCT_ID_MAX_VALUE)
                    .as("PIC 9(11) holds eleven nines")
                    .isEqualTo(99_999_999_999L);
            assertThat(CardXrefRecord.XREF_ACCT_ID_MAX_VALUE).isGreaterThan(Integer.MAX_VALUE);
        }

        @Test
        @DisplayName("The layout self-check passes for the copybook's own descriptor set")
        void layoutSelfCheckPassesForTheCopybooksDescriptors() {
            RecordLayout reDeclared = RecordLayout.of(CardXrefRecord.RECORD_LENGTH,
                    CardXrefRecord.XREF_CARD_NUM,
                    CardXrefRecord.XREF_CUST_ID,
                    CardXrefRecord.XREF_ACCT_ID,
                    CardXrefRecord.FILLER);

            assertThat(reDeclared).isEqualTo(CardXrefRecord.LAYOUT);
            assertThat(reDeclared.recordLength()).isEqualTo(50);
            assertThat(reDeclared.storageSpans()).hasSize(4);
        }
    }

    @Nested
    @DisplayName("The total-width self-check must also FAIL - a passing check proves nothing alone")
    class LayoutSelfCheckRejections {
        @Test
        @DisplayName("Dropping the trailing FILLER is rejected: 36 declared against a record length of 50")
        void droppingTheTrailingFillerIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(CardXrefRecord.RECORD_LENGTH,
                            CardXrefRecord.XREF_CARD_NUM,
                            CardXrefRecord.XREF_CUST_ID,
                            CardXrefRecord.XREF_ACCT_ID))
                    .withMessageContaining("36")
                    .withMessageContaining("50")
                    .withMessageContaining("FILLER");
        }

        @Test
        @DisplayName("A gap between two spans is rejected: every byte must be declared")
        void aGapBetweenSpansIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(CardXrefRecord.RECORD_LENGTH,
                            CardXrefRecord.XREF_CARD_NUM,
                            CardXrefRecord.XREF_ACCT_ID,
                            CardXrefRecord.FILLER))
                    .withMessageContaining("gap")
                    .withMessageContaining("XREF-ACCT-ID");
        }

        @Test
        @DisplayName("An overlap between two spans is rejected unless declared as a REDEFINES overlay")
        void anOverlapBetweenSpansIsRejected() {
            FieldSpan overlapping = FieldSpan.unsignedNumeric(CardXrefRecord.XREF_CUST_ID_NAME,
                    15, CardXrefRecord.XREF_CUST_ID_LENGTH);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(CardXrefRecord.RECORD_LENGTH,
                            CardXrefRecord.XREF_CARD_NUM,
                            overlapping,
                            CardXrefRecord.XREF_ACCT_ID,
                            CardXrefRecord.FILLER))
                    .withMessageContaining("overlap")
                    .withMessageContaining("XREF-CUST-ID");
        }

        @Test
        @DisplayName("A duplicated referable name is rejected; only FILLER may legitimately repeat")
        void aDuplicatedReferableNameIsRejected() {
            FieldSpan duplicate = FieldSpan.alphanumeric(CardXrefRecord.XREF_CARD_NUM_NAME,
                    CardXrefRecord.XREF_CUST_ID_OFFSET, CardXrefRecord.XREF_CUST_ID_LENGTH);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(CardXrefRecord.RECORD_LENGTH,
                            CardXrefRecord.XREF_CARD_NUM,
                            duplicate,
                            CardXrefRecord.XREF_ACCT_ID,
                            CardXrefRecord.FILLER))
                    .withMessageContaining("XREF-CARD-NUM")
                    .withMessageContaining("more than once");
        }

        @Test
        @DisplayName("An over-wide FILLER is rejected: 51 declared against a record length of 50")
        void anOverWideFillerIsRejected() {
            FieldSpan tooWide = FieldSpan.filler(CardXrefRecord.FILLER_OFFSET,
                    CardXrefRecord.FILLER_LENGTH + 1);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(CardXrefRecord.RECORD_LENGTH,
                            CardXrefRecord.XREF_CARD_NUM,
                            CardXrefRecord.XREF_CUST_ID,
                            CardXrefRecord.XREF_ACCT_ID,
                            tooWide))
                    .withMessageContaining("51")
                    .withMessageContaining("50");
        }
    }

    @Nested
    @DisplayName("Two keys, one 50-byte record - the base KSDS and the alternate index")
    class TwoKeysOneRecord {
        @Test
        @DisplayName("The CCXREF base key is XREF-CARD-NUM: 16 bytes at offset 0")
        void baseKeyIsTheCardNumberAtOffsetZero() {
            CardXrefRecord record = sample();
            byte[] image = record.encode(ASCII);

            assertThat(record.cardNumberKey())
                    .as("the base CCXREF key is the raw PIC X(16) image")
                    .isEqualTo(ROW_1_CARD_NUM)
                    .isEqualTo(record.xrefCardNum())
                    .hasSize(CardXrefRecord.XREF_CARD_NUM_LENGTH);
            assertThat(new String(image, CardXrefRecord.XREF_CARD_NUM_OFFSET,
                    CardXrefRecord.XREF_CARD_NUM_LENGTH, ASCII))
                    .as("the serialised base key occupies bytes [0, 16) of the record")
                    .isEqualTo(record.cardNumberKey());
            assertThat(record.cardNumberKey().getBytes(ASCII))
                    .as("the serialised key image is exactly 16 bytes")
                    .hasSize(16);
        }

        @Test
        @DisplayName("The CXACAIX alternate key is XREF-ACCT-ID: 11 bytes at offset 25, not 16")
        void alternateIndexKeyIsTheAccountIdAtOffsetTwentyFive() {
            CardXrefRecord record = new CardXrefRecord("1111222233334444", 555_555_555, 77_777_777_777L);
            byte[] image = record.encode(ASCII);

            assertThat(record.accountIdAlternateIndexKey())
                    .as("the CXACAIX key is XREF-ACCT-ID")
                    .isEqualTo(77_777_777_777L)
                    .isEqualTo(record.xrefAcctId());
            assertThat(new String(image, CardXrefRecord.XREF_ACCT_ID_OFFSET,
                    CardXrefRecord.XREF_ACCT_ID_LENGTH, ASCII))
                    .as("the serialised alternate key occupies bytes [25, 36)")
                    .isEqualTo("77777777777")
                    .hasSize(11);

            assertThat(new String(image, 16, 9, ASCII)).isEqualTo("555555555");
            assertThat(new String(image, 16, 11, ASCII)).isNotEqualTo("77777777777");
        }

        @Test
        @DisplayName("The alternate key image is 11 bytes left-zero-filled: account 50 is 00000000050")
        void alternateKeyImageIsLeftZeroFilledToElevenBytes() {
            byte[] image = sample().encode(ASCII);

            assertThat(new String(image, CardXrefRecord.XREF_ACCT_ID_OFFSET,
                    CardXrefRecord.XREF_ACCT_ID_LENGTH, ASCII))
                    .as("PIC 9(11) is right justified and zero-filled on the left")
                    .isEqualTo("00000000050")
                    .hasSize(11);
            assertThat(sample().accountIdAlternateIndexKey()).isEqualTo(ROW_1_ACCT_ID);
        }

        @Test
        @DisplayName("XREF-CARD-NUM is a String, never a numeric type: the leading zero is data")
        void cardNumberIsAStringSoLeadingZerosSurvive() throws NoSuchMethodException {
            Class<?> baseKeyType = CardXrefRecord.class.getMethod("cardNumberKey").getReturnType();
            Class<?> cardNumType = CardXrefRecord.class.getMethod("xrefCardNum").getReturnType();

            assertThat(baseKeyType).isEqualTo(String.class).isEqualTo(cardNumType);
            assertThat(baseKeyType.isPrimitive())
                    .as("a primitive card number could not carry a leading zero")
                    .isFalse();
            assertThat(Number.class.isAssignableFrom(baseKeyType))
                    .as("XREF-CARD-NUM is PIC X(16) - alphanumeric, not numeric")
                    .isFalse();

            CardXrefRecord decoded = CardXrefRecord.decode(sample().encode(ASCII), ASCII);
            assertThat(decoded.xrefCardNum()).startsWith("0").isEqualTo(ROW_1_CARD_NUM);
            assertThat(decoded.cardNumberKey()).startsWith("0").isEqualTo(ROW_1_CARD_NUM);
            assertThat(Long.toString(Long.parseLong(ROW_1_CARD_NUM))).doesNotStartWith("0");
        }

        @Test
        @DisplayName("The two keys are different fields of different pictures, not interchangeable")
        void theTwoKeysAreDistinct() throws NoSuchMethodException {
            CardXrefRecord record = new CardXrefRecord("0000000000000050", 50, 50L);

            assertThat(record.cardNumberKey()).isEqualTo("0000000000000050").hasSize(16);
            assertThat(record.accountIdAlternateIndexKey()).isEqualTo(50L);
            assertThat(CardXrefRecord.class.getMethod("accountIdAlternateIndexKey").getReturnType())
                    .as("XREF-ACCT-ID is PIC 9(11), which exceeds the int range")
                    .isEqualTo(long.class);
        }
    }

    @Nested
    @DisplayName("Encoding - always the complete 50-byte image, FILLER included (G19, G21)")
    class Encoding {
        @Test
        @DisplayName("A freshly serialised image is exactly 50 bytes, by every encode route")
        void serialisedWidthIsAlwaysFifty() {
            CardXrefRecord record = sample();

            assertThat(record.encode(ASCII)).hasSize(CardXrefRecord.RECORD_LENGTH);
            assertThat(record.encode(EBCDIC)).hasSize(CardXrefRecord.RECORD_LENGTH);
            assertThat(record.encode(asciiCodec)).hasSize(CardXrefRecord.RECORD_LENGTH);
            assertThat(record.encode(ebcdicCodec)).hasSize(CardXrefRecord.RECORD_LENGTH);
            assertThat(record.toFixedWidthRecord(asciiCodec).recordLength())
                    .isEqualTo(CardXrefRecord.RECORD_LENGTH);
            assertThat(record.toFixedWidthRecord(ebcdicCodec).toByteArray())
                    .hasSize(CardXrefRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("The complete image of fixture row 1, byte for byte: 36 data bytes then 14 spaces")
        void completeImageOfFixtureRowOne() {
            String image = new String(sample().encode(ASCII), ASCII);

            assertThat(image)
                    .as("what CBACT03C's DISPLAY CARD-XREF-RECORD puts on SYSOUT")
                    .isEqualTo(FIXTURE_ROW_1 + FOURTEEN_SPACES)
                    .hasSize(CardXrefRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("G21: bytes 36..49 are fourteen spaces - the FILLER is emitted, never optimised away")
        void fillerIsEmittedAsFourteenSpaces() {
            byte[] image = sample().encode(ASCII);

            assertThat(image)
                    .as("a record missing its FILLER would be %d bytes, not %d",
                            FIXTURE_ROW_WIDTH, CardXrefRecord.RECORD_LENGTH)
                    .hasSize(CardXrefRecord.RECORD_LENGTH);
            assertThat(new String(image, CardXrefRecord.FILLER_OFFSET,
                    CardXrefRecord.FILLER_LENGTH, ASCII))
                    .isEqualTo(FOURTEEN_SPACES)
                    .hasSize(14);
            for (int offset = CardXrefRecord.FILLER_OFFSET;
                 offset < CardXrefRecord.RECORD_LENGTH;
                 offset++) {
                assertThat(image[offset])
                        .as("FILLER byte at offset %d must be the US-ASCII space 0x20, never a NUL",
                                offset)
                        .isEqualTo((byte) 0x20);
            }
        }

        @Test
        @DisplayName("The FILLER uses the code page's own space byte: 0x20 in ASCII, 0x40 in IBM037")
        void fillerUsesTheCodePagesOwnSpaceByte() {
            byte[] ascii = sample().encode(ASCII);
            byte[] ebcdic = sample().encode(EBCDIC);

            for (int offset = CardXrefRecord.FILLER_OFFSET;
                 offset < CardXrefRecord.RECORD_LENGTH;
                 offset++) {
                assertThat(ascii[offset]).as("US-ASCII space at offset %d", offset)
                        .isEqualTo((byte) 0x20);
                assertThat(ebcdic[offset]).as("IBM037 space at offset %d", offset)
                        .isEqualTo((byte) 0x40);
            }
        }

        @ParameterizedTest(name = "XREF-CUST-ID {0} encodes as {1}")
        @CsvSource({
                "0,         000000000",
                "1,         000000001",
                "50,        000000050",
                "27,        000000027",
                "2,         000000002",
                "999999999, 999999999"
        })
        @DisplayName("PIC 9(09) is right justified and zero-filled on the left")
        void customerIdIsLeftZeroFilledToNineDigits(int custId, String expectedImage) {
            byte[] image = new CardXrefRecord(ROW_1_CARD_NUM, custId, 0L).encode(ASCII);

            assertThat(new String(image, CardXrefRecord.XREF_CUST_ID_OFFSET,
                    CardXrefRecord.XREF_CUST_ID_LENGTH, ASCII))
                    .as("XREF-CUST-ID at offset 16")
                    .isEqualTo(expectedImage)
                    .hasSize(9);
        }

        @ParameterizedTest(name = "XREF-ACCT-ID {0} encodes as {1}")
        @CsvSource({
                "0,           00000000000",
                "50,          00000000050",
                "27,          00000000027",
                "2,           00000000002",
                "99999999999, 99999999999"
        })
        @DisplayName("PIC 9(11) is right justified and zero-filled on the left")
        void accountIdIsLeftZeroFilledToElevenDigits(long acctId, String expectedImage) {
            byte[] image = new CardXrefRecord(ROW_1_CARD_NUM, 0, acctId).encode(ASCII);

            assertThat(new String(image, CardXrefRecord.XREF_ACCT_ID_OFFSET,
                    CardXrefRecord.XREF_ACCT_ID_LENGTH, ASCII))
                    .as("XREF-ACCT-ID at offset 25")
                    .isEqualTo(expectedImage)
                    .hasSize(11);
        }

        @ParameterizedTest(name = "card number \"{0}\" pads to \"{1}\"")
        @MethodSource("com.vsergeychik.carddemo.card.model.CardXrefRecordTest#cardNumberPaddingCases")
        @DisplayName("PIC X(16) is left justified and space-padded on the right")
        void cardNumberIsRightSpacePaddedToSixteen(String supplied, String expectedImage) {
            byte[] image = new CardXrefRecord(supplied, 0, 0L).encode(ASCII);

            assertThat(new String(image, CardXrefRecord.XREF_CARD_NUM_OFFSET,
                    CardXrefRecord.XREF_CARD_NUM_LENGTH, ASCII))
                    .as("XREF-CARD-NUM at offset 0")
                    .isEqualTo(expectedImage)
                    .hasSize(16);
            assertThat(image).hasSize(CardXrefRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("An all-spaces card number with zero ids still occupies its full 50 bytes")
        void anEmptyRecordStillOccupiesFiftyBytes() {
            byte[] image = new CardXrefRecord("", 0, 0L).encode(ASCII);

            assertThat(new String(image, ASCII))
                    .isEqualTo(" ".repeat(16) + "000000000" + "00000000000" + FOURTEEN_SPACES)
                    .hasSize(CardXrefRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("toFixedWidthRecord hands back a span addressable by the layout's own descriptors")
        void toFixedWidthRecordIsAddressableBySpan() {
            FixedWidthRecord record = sample().toFixedWidthRecord(asciiCodec);

            assertThat(record.charset())
                    .as("the span carries the code page it was built with, never a default")
                    .isEqualTo(ASCII);
            assertThat(record.recordLength()).isEqualTo(CardXrefRecord.RECORD_LENGTH);
            assertThat(record.readSpan(CardXrefRecord.XREF_CARD_NUM)).isEqualTo(ROW_1_CARD_NUM);
            assertThat(record.readSpan(CardXrefRecord.XREF_CUST_ID)).isEqualTo("000000050");
            assertThat(record.readSpan(CardXrefRecord.XREF_ACCT_ID)).isEqualTo("00000000050");
            assertThat(record.readSpan(CardXrefRecord.FILLER)).isEqualTo(FOURTEEN_SPACES);
            assertThat(record.readSpanBytes(CardXrefRecord.FILLER)).hasSize(14);
        }
    }

    @Nested
    @DisplayName("COBOL MOVE truncation direction - opposite for PIC X and PIC 9")
    class MoveSemantics {
        @Test
        @DisplayName("PIC X(16) truncates on the RIGHT: 20 characters keep the FIRST 16")
        void alphanumericMoveTruncatesOnTheRight() {
            String sending = "01234567890123456789";
            assertThat(sending).hasSize(20);

            String moved = asciiCodec.movePicX(sending, CardXrefRecord.XREF_CARD_NUM_LENGTH);

            assertThat(moved)
                    .as("a PIC X receiver is filled from its leftmost position and discards the rest")
                    .isEqualTo("0123456789012345")
                    .hasSize(16);
            byte[] image = new CardXrefRecord(moved, 0, 0L).encode(ASCII);
            assertThat(new String(image, 0, 16, ASCII)).isEqualTo("0123456789012345");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new CardXrefRecord(sending, 0, 0L))
                    .withMessageContaining("movePicX");
        }

        @Test
        @DisplayName("PIC 9(11) truncates on the LEFT: 13 digits keep the RIGHTMOST 11")
        void accountIdMoveTruncatesOnTheLeft() {
            String moved = asciiCodec.movePic9("1234567890123", CardXrefRecord.XREF_ACCT_ID_LENGTH);

            assertThat(moved)
                    .as("a numeric receiver is aligned on its implied decimal point, so the high-order "
                            + "digits are the ones lost")
                    .isEqualTo("34567890123")
                    .hasSize(11);

            byte[] image = new CardXrefRecord(ROW_1_CARD_NUM, 0, Long.parseLong(moved)).encode(ASCII);
            assertThat(new String(image, CardXrefRecord.XREF_ACCT_ID_OFFSET,
                    CardXrefRecord.XREF_ACCT_ID_LENGTH, ASCII)).isEqualTo("34567890123");
        }

        @Test
        @DisplayName("PIC 9(09) truncates on the LEFT: 11 digits keep the RIGHTMOST 9")
        void customerIdMoveTruncatesOnTheLeft() {
            String moved = asciiCodec.movePic9("12345678901", CardXrefRecord.XREF_CUST_ID_LENGTH);

            assertThat(moved).isEqualTo("345678901").hasSize(9);

            byte[] image = new CardXrefRecord(ROW_1_CARD_NUM, Integer.parseInt(moved), 0L)
                    .encode(ASCII);
            assertThat(new String(image, CardXrefRecord.XREF_CUST_ID_OFFSET,
                    CardXrefRecord.XREF_CUST_ID_LENGTH, ASCII)).isEqualTo("345678901");
        }

        @Test
        @DisplayName("The two move rules genuinely disagree, which is why the caller must choose one")
        void theTwoMoveRulesDisagree() {
            String sending = "1234567890123";

            assertThat(asciiCodec.movePicX(sending, 11)).isEqualTo("12345678901");
            assertThat(asciiCodec.movePic9(sending, 11)).isEqualTo("34567890123");
            assertThat(asciiCodec.movePicX(sending, 11)).isNotEqualTo(asciiCodec.movePic9(sending, 11));
            assertThat(sending).hasSize(13);
            assertThat(sending.length()).isNotEqualTo(11);
        }

        @Test
        @DisplayName("A short numeric move zero-fills on the left, as MOVE '05' TO a PIC 9 field does")
        void aShortNumericMoveZeroFillsOnTheLeft() {
            assertThat(asciiCodec.movePic9("50", CardXrefRecord.XREF_CUST_ID_LENGTH))
                    .isEqualTo("000000050");
            assertThat(asciiCodec.movePic9(ROW_1_ACCT_ID, CardXrefRecord.XREF_ACCT_ID_LENGTH))
                    .isEqualTo("00000000050");
            assertThat(asciiCodec.movePicX("ABC", CardXrefRecord.XREF_CARD_NUM_LENGTH))
                    .isEqualTo("ABC             ");
        }
    }

    @Nested
    @DisplayName("Decoding - the declared width only, PIC X untrimmed, PIC 9 digits only")
    class Decoding {
        @Test
        @DisplayName("A 50-byte record decodes to its three named fields at their copybook offsets")
        void decodesAllThreeFields() {
            byte[] stored = (FIXTURE_ROW_1 + FOURTEEN_SPACES).getBytes(ASCII);

            CardXrefRecord decoded = CardXrefRecord.decode(stored, ASCII);

            assertThat(decoded.xrefCardNum()).isEqualTo(ROW_1_CARD_NUM);
            assertThat(decoded.xrefCustId()).isEqualTo(ROW_1_CUST_ID);
            assertThat(decoded.xrefAcctId()).isEqualTo(ROW_1_ACCT_ID);
        }

        @Test
        @DisplayName("PIC X decode does not trim: the trailing padding is part of the field's value")
        void alphanumericDecodeDoesNotTrim() {
            byte[] stored = ("ABC" + " ".repeat(13) + "000000001" + "00000000002" + FOURTEEN_SPACES)
                    .getBytes(ASCII);

            CardXrefRecord decoded = CardXrefRecord.decode(stored, ASCII);

            assertThat(decoded.xrefCardNum())
                    .isEqualTo("ABC             ")
                    .hasSize(CardXrefRecord.XREF_CARD_NUM_LENGTH);
            assertThat(decoded.cardNumberKey()).hasSize(CardXrefRecord.XREF_CARD_NUM_LENGTH);
            assertThat(decoded.xrefCustId()).isEqualTo(1);
            assertThat(decoded.xrefAcctId()).isEqualTo(2L);
        }

        @Test
        @DisplayName("All three decode entry points agree, so no route can drift from the others")
        void allThreeDecodeEntryPointsAgree() {
            byte[] stored = (FIXTURE_ROW_1 + FOURTEEN_SPACES).getBytes(ASCII);
            FixedWidthRecord wrapped = asciiCodec.wrap(stored, CardXrefRecord.LAYOUT);

            CardXrefRecord viaCharset = CardXrefRecord.decode(stored, ASCII);
            CardXrefRecord viaCodec = CardXrefRecord.decode(stored, asciiCodec);
            CardXrefRecord viaSpan = CardXrefRecord.decodeSpan(wrapped, asciiCodec);

            assertThat(viaCodec).isEqualTo(viaCharset);
            assertThat(viaSpan).isEqualTo(viaCharset);
            assertThat(viaSpan).isEqualTo(sample());
        }

        @ParameterizedTest(name = "a row of {0} byte(s) is rejected")
        @ValueSource(ints = {1, 35, 36, 49, 51, 100})
        @DisplayName("Any width other than 50 is rejected - the 36-byte fixture row included (R-F)")
        void rejectsEveryWidthOtherThanFifty(int width) {
            byte[] wrongWidth = "0".repeat(width).getBytes(ASCII);

            assertThatIllegalArgumentException()
                    .as("a %d-byte row is not a CVACT03Y record", width)
                    .isThrownBy(() -> CardXrefRecord.decode(wrongWidth, ASCII));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CardXrefRecord.decode(wrongWidth, asciiCodec));
        }

        @Test
        @DisplayName("A 36-byte span is rejected by the model and its message names the shared normaliser")
        void aShortSpanIsRejectedAndPointsAtTheNormaliser() {
            FixedWidthRecord tooNarrow = new FixedWidthRecord(FIXTURE_ROW_WIDTH, ASCII);

            assertThat(tooNarrow.recordLength()).isEqualTo(FIXTURE_ROW_WIDTH);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CardXrefRecord.decodeSpan(tooNarrow, asciiCodec))
                    .withMessageContaining("padToDeclaredWidth")
                    .withMessageContaining("50")
                    .withMessageContaining("36");
        }

        @Test
        @DisplayName("A span of exactly 50 bytes passes the width self-check - the other side of it")
        void aFiftyByteSpanPassesTheWidthSelfCheck() {
            FixedWidthRecord exact = asciiCodec.newRecord(CardXrefRecord.LAYOUT);

            assertThat(exact.recordLength()).isEqualTo(CardXrefRecord.RECORD_LENGTH);
            CardXrefRecord decoded = CardXrefRecord.decodeSpan(exact, asciiCodec);
            assertThat(decoded.xrefCardNum()).isEqualTo(" ".repeat(16));
            assertThat(decoded.xrefCustId()).isZero();
            assertThat(decoded.xrefAcctId()).isZero();
        }

        @Test
        @DisplayName("A non-digit in either PIC 9 span fails loudly rather than decoding as zero")
        void rejectsNonDigitsInEitherNumericSpan() {
            byte[] custIdCorrupt =
                    (ROW_1_CARD_NUM + "0000X0050" + "00000000050" + FOURTEEN_SPACES).getBytes(ASCII);
            byte[] acctIdCorrupt =
                    (ROW_1_CARD_NUM + "000000050" + "000000000Z0" + FOURTEEN_SPACES).getBytes(ASCII);
            byte[] spacesInNumeric =
                    (ROW_1_CARD_NUM + "         " + "00000000050" + FOURTEEN_SPACES).getBytes(ASCII);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CardXrefRecord.decode(custIdCorrupt, ASCII));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CardXrefRecord.decode(acctIdCorrupt, ASCII));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CardXrefRecord.decode(spacesInNumeric, ASCII));
        }

        @Test
        @DisplayName("Nothing may be decoded from, or encoded into, an unstated code page")
        void nullArgumentsAreRejectedEverywhere() {
            byte[] stored = sample().encode(ASCII);
            FixedWidthRecord wrapped = asciiCodec.wrap(stored, CardXrefRecord.LAYOUT);

            assertThatNullPointerException()
                    .isThrownBy(() -> CardXrefRecord.decode(stored, (Charset) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> CardXrefRecord.decode(stored, (FixedWidthCodec) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> CardXrefRecord.decode(null, asciiCodec));
            assertThatNullPointerException()
                    .isThrownBy(() -> CardXrefRecord.decodeSpan(null, asciiCodec));
            assertThatNullPointerException()
                    .isThrownBy(() -> CardXrefRecord.decodeSpan(wrapped, null));
            assertThatNullPointerException().isThrownBy(() -> sample().encode((Charset) null));
            assertThatNullPointerException().isThrownBy(() -> sample().encode((FixedWidthCodec) null));
            assertThatNullPointerException().isThrownBy(() -> sample().toFixedWidthRecord(null));
        }
    }

    @Nested
    @DisplayName("Round trip - serialise, decode, re-serialise must be byte-identical")
    class RoundTrip {
        @Test
        @DisplayName("A 50-byte image survives decode then re-encode unchanged, byte for byte")
        void imageSurvivesDecodeThenReEncode() {
            byte[] stored = (FIXTURE_ROW_1 + FOURTEEN_SPACES).getBytes(ASCII);

            byte[] reEncoded = CardXrefRecord.decode(stored, ASCII).encode(ASCII);

            assertThat(reEncoded).isEqualTo(stored).hasSize(CardXrefRecord.RECORD_LENGTH);
            for (int offset = 0; offset < CardXrefRecord.RECORD_LENGTH; offset++) {
                assertThat(reEncoded[offset])
                        .as("byte at offset %d must survive the round trip unchanged", offset)
                        .isEqualTo(stored[offset]);
            }
        }

        @Test
        @DisplayName("The three fields survive field by field, compared individually not structurally")
        void fieldsSurviveFieldByField() {
            CardXrefRecord original = new CardXrefRecord("0683586198171516", 27, 27L);

            CardXrefRecord reDecoded = CardXrefRecord.decode(original.encode(ASCII), ASCII);

            assertThat(reDecoded.xrefCardNum()).isEqualTo(original.xrefCardNum());
            assertThat(reDecoded.xrefCustId()).isEqualTo(original.xrefCustId());
            assertThat(reDecoded.xrefAcctId()).isEqualTo(original.xrefAcctId());
            assertThat(reDecoded.cardNumberKey()).isEqualTo(original.cardNumberKey());
            assertThat(reDecoded.accountIdAlternateIndexKey())
                    .isEqualTo(original.accountIdAlternateIndexKey());
        }

        @Test
        @DisplayName("The widest storable record round trips, proving both pictures are wide enough")
        void theWidestStorableRecordRoundTrips() {
            CardXrefRecord widest = new CardXrefRecord("9999999999999999",
                    CardXrefRecord.XREF_CUST_ID_MAX_VALUE,
                    CardXrefRecord.XREF_ACCT_ID_MAX_VALUE);

            byte[] image = widest.encode(ASCII);

            assertThat(image).hasSize(CardXrefRecord.RECORD_LENGTH);
            assertThat(new String(image, ASCII))
                    .isEqualTo("9999999999999999" + "999999999" + "99999999999" + FOURTEEN_SPACES);
            assertThat(CardXrefRecord.decode(image, ASCII)).isEqualTo(widest);
        }
    }

    @Nested
    @DisplayName("The 36-byte fixture and the shared 36-to-50 right pad (G16, risk R-F)")
    class ShortFixtureRows {
        @Test
        @DisplayName("The fixture really is 50 rows of 36 bytes, so the deviation is measured not assumed")
        void theFixtureIsFiftyRowsOfThirtySixBytes() {
            List<String> rows = fixtureRows();

            assertThat(rows)
                    .as("the CARDXREF fixture holds one row per cross-reference record")
                    .hasSize(FIXTURE_ROW_COUNT);
            for (int index = 0; index < rows.size(); index++) {
                assertThat(rows.get(index))
                        .as("row %d of %s", index + 1, FIXTURE)
                        .hasSize(FIXTURE_ROW_WIDTH);
            }
            assertThat(FIXTURE_ROW_WIDTH)
                    .isEqualTo(CardXrefRecord.RECORD_LENGTH - CardXrefRecord.FILLER_LENGTH)
                    .isEqualTo(CardXrefRecord.FILLER_OFFSET);
            assertThat(rows.get(0)).isEqualTo(FIXTURE_ROW_1);
            assertThat(rows.get(1)).isEqualTo(FIXTURE_ROW_2);
            assertThat(rows.get(2)).isEqualTo(FIXTURE_ROW_3);
        }

        @Test
        @DisplayName("The shared normaliser only APPENDS: bytes 0..35 are untouched, 36..49 are spaces")
        void theNormaliserOnlyAppends() {
            byte[] raw = FIXTURE_ROW_1.getBytes(ASCII);
            assertThat(raw).hasSize(FIXTURE_ROW_WIDTH);

            byte[] widened = asciiCodec.padToDeclaredWidth(raw, CardXrefRecord.RECORD_LENGTH);

            assertThat(widened)
                    .as("the widened row is exactly the copybook's declared width")
                    .hasSize(CardXrefRecord.RECORD_LENGTH);
            for (int offset = 0; offset < FIXTURE_ROW_WIDTH; offset++) {
                assertThat(widened[offset])
                        .as("the correction must be non-destructive: byte at offset %d must be "
                                + "byte-identical to the original 36-byte fixture row", offset)
                        .isEqualTo(raw[offset]);
            }
            for (int offset = FIXTURE_ROW_WIDTH; offset < CardXrefRecord.RECORD_LENGTH; offset++) {
                assertThat(widened[offset])
                        .as("the appended FILLER byte at offset %d must be a space, because a FILLER "
                                + "carrying no VALUE holds spaces", offset)
                        .isEqualTo((byte) 0x20);
            }
            assertThat(new String(widened, ASCII)).isEqualTo(FIXTURE_ROW_1 + FOURTEEN_SPACES);
        }

        @Test
        @DisplayName("A widened row decodes, and the model re-emits exactly the widened bytes")
        void aWidenedRowDecodesAndReEmitsTheSameBytes() {
            byte[] widened = widenThroughTheSharedNormaliser(FIXTURE_ROW_1);

            CardXrefRecord decoded = CardXrefRecord.decode(widened, asciiCodec);

            assertThat(decoded.xrefCardNum()).isEqualTo(ROW_1_CARD_NUM);
            assertThat(decoded.xrefCustId()).isEqualTo(ROW_1_CUST_ID);
            assertThat(decoded.xrefAcctId()).isEqualTo(ROW_1_ACCT_ID);
            assertThat(asciiCodec.readPicX(decoded.toFixedWidthRecord(asciiCodec),
                    CardXrefRecord.FILLER))
                    .as("the FILLER the normaliser appended reads back as fourteen spaces")
                    .isEqualTo(FOURTEEN_SPACES);
            assertThat(decoded.encode(asciiCodec))
                    .isEqualTo(widened)
                    .hasSize(CardXrefRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("An unwidened 36-byte row is rejected - the model never self-heals a short row")
        void anUnwidenedRowIsRejected() {
            byte[] raw = FIXTURE_ROW_1.getBytes(ASCII);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CardXrefRecord.decode(raw, ASCII))
                    .withMessageContaining("36")
                    .withMessageContaining("50")
                    .withMessageContaining("padToDeclaredWidth");
        }

        @Test
        @DisplayName("The normaliser widens only: an over-long row is rejected, never truncated")
        void theNormaliserRefusesToTruncate() {
            byte[] tooLong = (FIXTURE_ROW_1 + FOURTEEN_SPACES + " ").getBytes(ASCII);
            assertThat(tooLong).hasSize(CardXrefRecord.RECORD_LENGTH + 1);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> asciiCodec.padToDeclaredWidth(tooLong,
                            CardXrefRecord.RECORD_LENGTH))
                    .withMessageContaining("never truncates");
        }

        @ParameterizedTest(name = "row {0}: {1}")
        @MethodSource("com.vsergeychik.carddemo.card.model.CardXrefRecordTest#fixtureRowCases")
        @DisplayName("Every one of the 50 fixture rows widens, decodes and re-encodes byte-identically")
        void everyFixtureRowWidensDecodesAndReEncodes(int rowNumber, String storedRow) {
            assertThat(storedRow).as("row %d", rowNumber).hasSize(FIXTURE_ROW_WIDTH);

            byte[] widened = widenThroughTheSharedNormaliser(storedRow);
            CardXrefRecord decoded = CardXrefRecord.decode(widened, asciiCodec);

            assertThat(decoded.xrefCardNum())
                    .as("XREF-CARD-NUM of row %d, bytes [0, 16)", rowNumber)
                    .isEqualTo(storedRow.substring(0, 16));
            assertThat(decoded.xrefCustId())
                    .as("XREF-CUST-ID of row %d, bytes [16, 25)", rowNumber)
                    .isEqualTo(Integer.parseInt(storedRow.substring(16, 25)));
            assertThat(decoded.xrefAcctId())
                    .as("XREF-ACCT-ID of row %d, bytes [25, 36)", rowNumber)
                    .isEqualTo(Long.parseLong(storedRow.substring(25, 36)));
            assertThat(decoded.cardNumberKey())
                    .as("CCXREF base key of row %d", rowNumber)
                    .isEqualTo(storedRow.substring(0, 16));
            assertThat(decoded.accountIdAlternateIndexKey())
                    .as("CXACAIX alternate key of row %d", rowNumber)
                    .isEqualTo(Long.parseLong(storedRow.substring(25, 36)));
            assertThat(decoded.encode(asciiCodec))
                    .as("row %d must re-encode to exactly the widened bytes", rowNumber)
                    .isEqualTo(widened);
        }

        @ParameterizedTest(name = "row {0} decodes to card {1}, customer {2}, account {3}")
        @CsvSource({
                "1, 0500024453765740, 50, 50",
                "2, 0683586198171516, 27, 27",
                "3, 0923877193247330,  2,  2"
        })
        @DisplayName("The first three fixture rows decode to their hand-read field values")
        void theFirstThreeRowsDecodeToTheirHandReadValues(int rowNumber,
                                                          String cardNum,
                                                          int custId,
                                                          long acctId) {
            String storedRow = fixtureRows().get(rowNumber - 1);

            CardXrefRecord decoded =
                    CardXrefRecord.decode(widenThroughTheSharedNormaliser(storedRow), asciiCodec);

            assertThat(decoded.xrefCardNum()).isEqualTo(cardNum).startsWith("0");
            assertThat(decoded.xrefCustId()).isEqualTo(custId);
            assertThat(decoded.xrefAcctId()).isEqualTo(acctId);
            assertThat(decoded).isEqualTo(new CardXrefRecord(cardNum, custId, acctId));
        }
    }

    @Nested
    @DisplayName("The code page is always a parameter, never the platform default")
    class CharsetIsAlwaysAParameter {
        @Test
        @DisplayName("IBM037 is present in the verified toolchain, asserted rather than assumed away")
        void ibm037IsPresent() {
            assertThat(Charset.isSupported(EBCDIC_NAME))
                    .as("%s must be available; it ships in the JDK's jdk.charsets module, so if this "
                            + "fails the runtime is a cut-down JDK and the fix is to install a full "
                            + "one - never to skip this verification", EBCDIC_NAME)
                    .isTrue();
            assertThat(EBCDIC.name()).isEqualTo(EBCDIC_NAME);
            assertThat(ASCII).isEqualTo(StandardCharsets.US_ASCII);
        }

        @Test
        @DisplayName("The same record encodes to DIFFERENT bytes under US-ASCII and under IBM037")
        void theTwoCodePagesProduceDifferentImages() {
            CardXrefRecord record = sample();

            byte[] ascii = record.encode(ASCII);
            byte[] ebcdic = record.encode(EBCDIC);

            assertThat(ascii).isNotEqualTo(ebcdic);
            assertThat(ascii).hasSameSizeAs(ebcdic).hasSize(CardXrefRecord.RECORD_LENGTH);
            assertThat(ascii[0]).as("US-ASCII '0'").isEqualTo((byte) 0x30);
            assertThat(ebcdic[0]).as("IBM037 '0'").isEqualTo((byte) 0xF0);
            assertThat(ascii[CardXrefRecord.FILLER_OFFSET]).as("US-ASCII space").isEqualTo((byte) 0x20);
            assertThat(ebcdic[CardXrefRecord.FILLER_OFFSET]).as("IBM037 space").isEqualTo((byte) 0x40);
        }

        @Test
        @DisplayName("Each image is self-consistent: decoding under its own code page returns the record")
        void eachImageIsSelfConsistentUnderItsOwnCodePage() {
            CardXrefRecord record = new CardXrefRecord("0923877193247330", 2, 2L);

            assertThat(CardXrefRecord.decode(record.encode(ASCII), ASCII)).isEqualTo(record);
            assertThat(CardXrefRecord.decode(record.encode(EBCDIC), EBCDIC)).isEqualTo(record);
            assertThat(CardXrefRecord.decode(record.encode(asciiCodec), asciiCodec)).isEqualTo(record);
            assertThat(CardXrefRecord.decode(record.encode(ebcdicCodec), ebcdicCodec)).isEqualTo(record);
            assertThat(asciiCodec.charset()).isEqualTo(ASCII);
            assertThat(ebcdicCodec.charset()).isEqualTo(EBCDIC);
        }

        @Test
        @DisplayName("No byte-level entry point offers a no-arg overload that could default the code page")
        void everyByteLevelEntryPointDemandsACodePage() {
            List<String> byteLevelOperations =
                    List.of("encode", "decode", "decodeSpan", "toFixedWidthRecord");

            int inspected = 0;
            for (Method method : CardXrefRecord.class.getDeclaredMethods()) {
                if (!byteLevelOperations.contains(method.getName()) || method.isSynthetic()) {
                    continue;
                }
                inspected++;
                boolean statesTheCodePage = false;
                for (Class<?> parameterType : method.getParameterTypes()) {
                    if (parameterType.equals(Charset.class)
                            || parameterType.equals(FixedWidthCodec.class)) {
                        statesTheCodePage = true;
                    }
                }
                assertThat(statesTheCodePage)
                        .as("%s must take a Charset or a FixedWidthCodec: a no-arg overload would be "
                                        + "an invitation to fall back on the platform default",
                                method.getName())
                        .isTrue();
            }
            assertThat(inspected)
                    .as("encode x2, decode x2, decodeSpan and toFixedWidthRecord")
                    .isEqualTo(6);
        }
    }

    @Nested
    @DisplayName("Construction - every PICTURE bound enforced at the boundary, both sides driven")
    class Construction {
        @Test
        @DisplayName("The three fields are held exactly as supplied, with no normalisation")
        void holdsTheSuppliedFieldsVerbatim() {
            CardXrefRecord record = new CardXrefRecord(ROW_1_CARD_NUM, ROW_1_CUST_ID, ROW_1_ACCT_ID);

            assertThat(record.xrefCardNum()).isEqualTo(ROW_1_CARD_NUM);
            assertThat(record.xrefCustId()).isEqualTo(ROW_1_CUST_ID);
            assertThat(record.xrefAcctId()).isEqualTo(ROW_1_ACCT_ID);
            assertThat(new CardXrefRecord("  ABC  ", 0, 0L).xrefCardNum()).isEqualTo("  ABC  ");
        }

        @ParameterizedTest(name = "a card number of {0} character(s) is accepted")
        @ValueSource(ints = {0, 1, 15, 16})
        @DisplayName("A card number up to and including PIC X(16) is accepted")
        void acceptsACardNumberUpToItsPicture(int length) {
            String supplied = "9".repeat(length);

            CardXrefRecord record = new CardXrefRecord(supplied, 0, 0L);

            assertThat(record.xrefCardNum()).isEqualTo(supplied).hasSize(length);
            assertThat(record.encode(ASCII)).hasSize(CardXrefRecord.RECORD_LENGTH);
        }

        @ParameterizedTest(name = "a card number of {0} character(s) is rejected")
        @ValueSource(ints = {17, 20, 50})
        @DisplayName("A card number wider than PIC X(16) is rejected, and the message names the remedy")
        void rejectsAnOverWideCardNumber(int length) {
            String supplied = "9".repeat(length);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new CardXrefRecord(supplied, 0, 0L))
                    .withMessageContaining("XREF-CARD-NUM")
                    .withMessageContaining("PIC X(16)")
                    .withMessageContaining("movePicX");
        }

        @Test
        @DisplayName("A null card number is rejected: PIC X(16) has no null, only SPACES")
        void rejectsANullCardNumber() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new CardXrefRecord(null, 0, 0L))
                    .withMessageContaining("XREF-CARD-NUM");
            assertThat(new CardXrefRecord("", 0, 0L).xrefCardNum()).isEmpty();
        }

        @ParameterizedTest(name = "customer id {0} is rejected")
        @ValueSource(ints = {-1, -50, Integer.MIN_VALUE, 1_000_000_000, Integer.MAX_VALUE})
        @DisplayName("A customer id outside PIC 9(09) is rejected - negatives have nowhere to put a sign")
        void rejectsAnUnstorableCustomerId(int custId) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new CardXrefRecord(ROW_1_CARD_NUM, custId, 0L))
                    .withMessageContaining("XREF-CUST-ID");
        }

        @ParameterizedTest(name = "account id {0} is rejected")
        @ValueSource(longs = {-1L, -50L, Long.MIN_VALUE, 100_000_000_000L, Long.MAX_VALUE})
        @DisplayName("An account id outside PIC 9(11) is rejected - negatives included")
        void rejectsAnUnstorableAccountId(long acctId) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new CardXrefRecord(ROW_1_CARD_NUM, 0, acctId))
                    .withMessageContaining("XREF-ACCT-ID");
        }

        @ParameterizedTest(name = "customer id {0} is accepted")
        @ValueSource(ints = {0, 1, 50, 999_999_998, 999_999_999})
        @DisplayName("Every value a PIC 9(09) can hold is accepted, boundaries included")
        void acceptsEveryStorableCustomerId(int custId) {
            assertThat(new CardXrefRecord(ROW_1_CARD_NUM, custId, 0L).xrefCustId()).isEqualTo(custId);
        }

        @ParameterizedTest(name = "account id {0} is accepted")
        @ValueSource(longs = {0L, 1L, 50L, 99_999_999_998L, 99_999_999_999L})
        @DisplayName("Every value a PIC 9(11) can hold is accepted, boundaries included")
        void acceptsEveryStorableAccountId(long acctId) {
            assertThat(new CardXrefRecord(ROW_1_CARD_NUM, 0, acctId).xrefAcctId()).isEqualTo(acctId);
        }
    }

    @Nested
    @DisplayName("Value semantics - what the parity differ and 9300-CHECK-CHANGE-IN-REC both rely on")
    class ValueSemantics {
        @Test
        @DisplayName("An instance equals itself, and equals a separately built instance of equal fields")
        void equalFieldsMeanEqualRecords() {
            CardXrefRecord one = new CardXrefRecord(ROW_1_CARD_NUM, ROW_1_CUST_ID, ROW_1_ACCT_ID);
            CardXrefRecord other = new CardXrefRecord(ROW_1_CARD_NUM, ROW_1_CUST_ID, ROW_1_ACCT_ID);

            assertThat(one.equals(one)).as("reflexive: the identity short circuit").isTrue();
            assertThat(one).isEqualTo(other).hasSameHashCodeAs(other);
            assertThat(other).as("symmetric").isEqualTo(one);
            assertThat(one.hashCode()).isEqualTo(one.hashCode()).isEqualTo(other.hashCode());
        }

        @Test
        @DisplayName("A difference in XREF-CARD-NUM alone breaks equality")
        void aCardNumberDifferenceBreaksEquality() {
            CardXrefRecord base = new CardXrefRecord(ROW_1_CARD_NUM, ROW_1_CUST_ID, ROW_1_ACCT_ID);

            assertThat(base)
                    .isNotEqualTo(new CardXrefRecord("0500024453765741", ROW_1_CUST_ID, ROW_1_ACCT_ID));
        }

        @Test
        @DisplayName("A difference in XREF-CUST-ID alone breaks equality")
        void aCustomerIdDifferenceBreaksEquality() {
            CardXrefRecord base = new CardXrefRecord(ROW_1_CARD_NUM, ROW_1_CUST_ID, ROW_1_ACCT_ID);

            assertThat(base).isNotEqualTo(new CardXrefRecord(ROW_1_CARD_NUM, 51, ROW_1_ACCT_ID));
        }

        @Test
        @DisplayName("A difference in XREF-ACCT-ID alone breaks equality")
        void anAccountIdDifferenceBreaksEquality() {
            CardXrefRecord base = new CardXrefRecord(ROW_1_CARD_NUM, ROW_1_CUST_ID, ROW_1_ACCT_ID);

            assertThat(base).isNotEqualTo(new CardXrefRecord(ROW_1_CARD_NUM, ROW_1_CUST_ID, 51L));
        }

        @Test
        @DisplayName("A record never equals null, a String, or an unrelated type")
        void neverEqualsNullOrAnUnrelatedType() {
            CardXrefRecord record = sample();

            assertThat(record.equals(null)).isFalse();
            assertThat(record.equals(ROW_1_CARD_NUM)).isFalse();
            assertThat(record.equals(Integer.valueOf(ROW_1_CUST_ID))).isFalse();
            assertThat(record).isNotEqualTo(new Object());
        }

        @Test
        @DisplayName("Padding participates in equality, because padding is data in a PIC X field")
        void paddingParticipatesInEquality() {
            assertThat(new CardXrefRecord("ABC", 1, 1L))
                    .isNotEqualTo(new CardXrefRecord("ABC             ", 1, 1L));
            assertThat(new CardXrefRecord("", 1, 1L))
                    .isNotEqualTo(new CardXrefRecord(" ".repeat(16), 1, 1L));
        }

        @Test
        @DisplayName("toString names every field by its copybook name and is deterministic")
        void toStringSpeaksTheCopybooksVocabulary() {
            String rendered = new CardXrefRecord("ABC", 50, 50L).toString();

            assertThat(rendered)
                    .isNotNull()
                    .startsWith("CARD-XREF-RECORD[")
                    .contains(CardXrefRecord.XREF_CARD_NUM_NAME + "='***'")
                    .contains(CardXrefRecord.XREF_CUST_ID_NAME + "=*****0050")
                    .contains(CardXrefRecord.XREF_ACCT_ID_NAME + "=*******0050")
                    .endsWith("]");
            assertThat(rendered).doesNotContain("ABC");
            assertThat(rendered).isEqualTo(new CardXrefRecord("ABC", 50, 50L).toString());
            assertThat(sample().toString()).isNotNull().doesNotContain("@");
        }

        @Test
        @DisplayName("a control character in the retained suffix cannot forge a second log line")
        void theRetainedSuffixCannotForgeALogLine() {
            String rendered = new CardXrefRecord("411111111111\r\nOK", 50, 50L).toString();

            assertThat(rendered)
                    .doesNotContain("\r")
                    .doesNotContain("\n")
                    .contains(CardXrefRecord.XREF_CARD_NUM_NAME + "='" + "*".repeat(12)
                            + "X'0D'X'0A'OK'");
            assertThat(rendered.lines())
                    .as("this rendering is documented as safe to log, which means one line")
                    .hasSize(1);
            assertThat(new CardXrefRecord("411111111111\r\nOK", 50, 50L).xrefCardNum())
                    .isEqualTo("411111111111\r\nOK");
        }
    }

    @Nested
    @DisplayName("Structural guards - gates G8, G22, G44 and G53 asserted about the type itself")
    class StructuralGuards {
        @Test
        @DisplayName("G22: no double, float, Double or Float in any field, accessor or constructor")
        void noBinaryFloatingPointAnywhere() {
            for (Field field : CardXrefRecord.class.getDeclaredFields()) {
                assertThat(field.getType())
                        .as("declared field %s", field.getName())
                        .isNotIn(double.class, float.class, Double.class, Float.class);
            }
            for (Method method : CardXrefRecord.class.getDeclaredMethods()) {
                assertThat(method.getReturnType())
                        .as("return type of %s", method.getName())
                        .isNotIn(double.class, float.class, Double.class, Float.class);
                assertThat(method.getParameterTypes())
                        .as("parameter types of %s", method.getName())
                        .doesNotContain(double.class, float.class, Double.class, Float.class);
            }
            for (Constructor<?> constructor : CardXrefRecord.class.getDeclaredConstructors()) {
                assertThat(constructor.getParameterTypes())
                        .as("constructor parameter types")
                        .doesNotContain(double.class, float.class, Double.class, Float.class);
            }
        }

        @Test
        @DisplayName("The two numeric fields are an int and a long, sized by their declared digit counts")
        void theNumericFieldsAreIntegralAndCorrectlySized() throws NoSuchMethodException {
            assertThat(CardXrefRecord.class.getMethod("xrefCustId").getReturnType())
                    .as("PIC 9(09) fits an int")
                    .isEqualTo(int.class);
            assertThat(CardXrefRecord.class.getMethod("xrefAcctId").getReturnType())
                    .as("PIC 9(11) exceeds the int range, so it must be a long")
                    .isEqualTo(long.class);
        }

        @Test
        @DisplayName("G44: no persistence artefact - no entity annotation and no version field")
        void noPersistenceArtefacts() {
            assertThat(CardXrefRecord.class.getAnnotations())
                    .as("a plain value type carries no annotation at all: no entity mapping, no table, "
                            + "no index, and no Spring stereotype")
                    .isEmpty();
            assertNoPersistenceAnnotation(CardXrefRecord.class.getAnnotations());
            for (Field field : CardXrefRecord.class.getDeclaredFields()) {
                assertNoPersistenceAnnotation(field.getAnnotations());
                String lowerCased = field.getName().toLowerCase(Locale.ROOT);
                assertThat(lowerCased)
                        .as("field %s must not be an optimistic-lock discriminator: the concurrency "
                                        + "check is the COBOL's own field-by-field re-read, and adding a "
                                        + "version column would be a schema change", field.getName())
                        .doesNotContain("version")
                        .doesNotContain("optimistic")
                        .doesNotContain("optlock");
            }
            for (Method method : CardXrefRecord.class.getDeclaredMethods()) {
                assertNoPersistenceAnnotation(method.getAnnotations());
            }
            for (Constructor<?> constructor : CardXrefRecord.class.getDeclaredConstructors()) {
                assertNoPersistenceAnnotation(constructor.getAnnotations());
            }
        }

        @Test
        @DisplayName("G8/G53: the only static members are the immutable layout constants")
        void onlyImmutableStaticConstantsAreDeclared() {
            List<String> mutableStatics = new ArrayList<>();
            for (Field field : CardXrefRecord.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers())) {
                    mutableStatics.add(field.getName());
                }
            }

            assertThat(mutableStatics)
                    .as("COBOL WORKING-STORAGE must never become a mutable static field: it would break "
                            + "request isolation and make every test order-dependent")
                    .isEmpty();
            for (Field field : CardXrefRecord.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("instance field %s must be final", field.getName())
                            .isTrue();
                }
            }
            assertThat(Modifier.isFinal(CardXrefRecord.class.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("The layout is published as an immutable descriptor list the parity differ can read")
        void theLayoutIsPublishedImmutably() {
            List<FieldSpan> spans = CardXrefRecord.LAYOUT.spans();

            assertThat(spans).hasSize(4);
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> spans.add(CardXrefRecord.FILLER));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> CardXrefRecord.LAYOUT.storageSpans().clear());
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CardXrefRecord.LAYOUT.span("NOT-A-CVACT03Y-FIELD"))
                    .withMessageContaining("declares no field named");
        }

        private void assertNoPersistenceAnnotation(Annotation[] annotations) {
            for (Annotation annotation : annotations) {
                assertThat(annotation.annotationType().getName())
                        .as("annotation %s", annotation.annotationType().getName())
                        .doesNotStartWith("jakarta.persistence")
                        .doesNotStartWith("javax.persistence");
            }
        }
    }
}
