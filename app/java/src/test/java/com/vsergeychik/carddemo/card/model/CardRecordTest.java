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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link CardRecord}, the one Java type for {@code app/cpy/CVACT02Y.cpy}'s
 * {@code 01 CARD-RECORD}: a fixed-width value of exactly 150 bytes copied by six COBOL programs
 * ({@code CBACT02C}, {@code CBTRN01C}, {@code COACTVWC}, {@code COCRDLIC}, {@code COCRDSLC} and
 * {@code COCRDUPC}).
 */
@DisplayName("CardRecord - CVACT02Y card record, 150 bytes")
class CardRecordTest {
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    private static final Charset EBCDIC = Charset.forName("IBM037");

    private static final String FIXTURE_RESOURCE = "fixtures/carddata.txt";

    private static final int FIXTURE_ROW_COUNT = 50;

    private static final String ROW_1_CARD_NUM = "0500024453765740";

    private static final long ROW_1_ACCT_ID = 50L;

    private static final int ROW_1_CVV = 747;

    private static final String ROW_1_NAME = "Aniya Von";

    private static final String ROW_1_EXPIRY = "2023-03-09";

    private static final String ROW_1_STATUS = "Y";

    private static final String ROW_2_CARD_NUM = "0683586198171516";

    private static final String ROW_3_CARD_NUM = "0923877193247330";

    private static final String SPACE = " ";

    private static final byte ASCII_SPACE_BYTE = 0x20;

    private static final byte EBCDIC_SPACE_BYTE = 0x40;

    private final FixedWidthCodec asciiCodec = new FixedWidthCodec(ASCII);

    private final FixedWidthCodec ebcdicCodec = new FixedWidthCodec(EBCDIC);

    private static CardRecord row1() {
        return new CardRecord(ROW_1_CARD_NUM, ROW_1_ACCT_ID, ROW_1_CVV, ROW_1_NAME, ROW_1_EXPIRY,
                ROW_1_STATUS);
    }

    private static String expectedPadded(String value, int width) {
        return value + SPACE.repeat(width - value.length());
    }

    private static List<String> fixtureRows() {
        List<String> rows = new ArrayList<>();
        try (InputStream stream =
                     CardRecordTest.class.getClassLoader().getResourceAsStream(FIXTURE_RESOURCE)) {
            assertThat(stream)
                    .as("The fixture must be on the test classpath at '%s'. It is derived from "
                            + "app/data/ASCII/carddata.txt, which is never read directly.",
                            FIXTURE_RESOURCE)
                    .isNotNull();
            for (String row : new String(stream.readAllBytes(), ASCII).split("\n")) {
                String withoutLineEnd = row.endsWith("\r") ? row.substring(0, row.length() - 1) : row;
                if (!withoutLineEnd.isEmpty()) {
                    rows.add(withoutLineEnd);
                }
            }
        } catch (IOException problem) {
            throw new UncheckedIOException("Could not read the classpath fixture "
                    + FIXTURE_RESOURCE, problem);
        }
        return rows;
    }

    private static List<Arguments> everyFixtureRow() {
        List<String> rows = fixtureRows();
        List<Arguments> arguments = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            arguments.add(Arguments.of(index + 1, rows.get(index)));
        }
        return arguments;
    }

    @Nested
    @DisplayName("Declared geometry - the seven spans of CVACT02Y (G8, G19)")
    class DeclaredGeometry {
        @Test
        @DisplayName("RECLN 150: the seven declared widths sum to the declared record length")
        void widthsSumToRecordLength() {
            int sum = CardRecord.CARD_NUM_LENGTH
                    + CardRecord.CARD_ACCT_ID_LENGTH
                    + CardRecord.CARD_CVV_CD_LENGTH
                    + CardRecord.CARD_EMBOSSED_NAME_LENGTH
                    + CardRecord.CARD_EXPIRAION_DATE_LENGTH
                    + CardRecord.CARD_ACTIVE_STATUS_LENGTH
                    + CardRecord.FILLER_LENGTH;

            assertThat(CardRecord.RECORD_LENGTH)
                    .as("app/cpy/CVACT02Y.cpy:2 declares (RECLN 150)")
                    .isEqualTo(150);
            assertThat(sum)
                    .as("16 + 11 + 3 + 50 + 10 + 1 + 59 must equal the declared record length")
                    .isEqualTo(CardRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("A freshly serialised image is exactly 150 bytes")
        void serialisedImageIsExactlyTheDeclaredWidth() {
            assertThat(row1().encode(ASCII))
                    .as("every CARD-RECORD image is the full declared width, FILLER included")
                    .hasSize(CardRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("Seven storage spans, in copybook declaration order, with no REDEFINES overlay")
        void sevenStorageSpansInCopybookOrder() {
            assertThat(CardRecord.LAYOUT.storageSpans())
                    .as("CVACT02Y declares seven 05 items on lines 5 to 11")
                    .hasSize(7)
                    .extracting(FieldSpan::name)
                    .containsExactly("CARD-NUM",
                            "CARD-ACCT-ID",
                            "CARD-CVV-CD",
                            "CARD-EMBOSSED-NAME",
                            "CARD-EXPIRAION-DATE",
                            "CARD-ACTIVE-STATUS",
                            "FILLER");

            assertThat(CardRecord.LAYOUT.redefinitions())
                    .as("CVACT02Y declares no REDEFINES; the overlays in COCRDSLC and COCRDUPC "
                            + "belong to those programs' working storage")
                    .isEmpty();
        }

        @ParameterizedTest(name = "[{0}] {1} occupies [{2}, {2}+{3})")
        @CsvSource({
                "0, CARD-NUM,             0,  16",
                "1, CARD-ACCT-ID,        16,  11",
                "2, CARD-CVV-CD,         27,   3",
                "3, CARD-EMBOSSED-NAME,  30,  50",
                "4, CARD-EXPIRAION-DATE, 80,  10",
                "5, CARD-ACTIVE-STATUS,  90,   1",
                "6, FILLER,              91,  59"
        })
        @DisplayName("Each span sits at its copybook offset with its copybook width")
        void eachSpanSitsAtItsCopybookOffset(int declarationIndex, String fieldName, int offset,
                                             int length) {
            FieldSpan span = CardRecord.LAYOUT.storageSpans().get(declarationIndex);

            assertThat(span.name())
                    .as("span %d of CVACT02Y must be %s", declarationIndex, fieldName)
                    .isEqualTo(fieldName);
            assertThat(span.offset())
                    .as("%s must begin at absolute 0-based offset %d", fieldName, offset)
                    .isEqualTo(offset);
            assertThat(span.length())
                    .as("%s must be %d byte(s) wide", fieldName, length)
                    .isEqualTo(length);
            assertThat(span.endOffsetExclusive())
                    .as("%s must end at %d", fieldName, offset + length)
                    .isEqualTo(offset + length);
        }

        @Test
        @DisplayName("The spans are contiguous from 0 with no gap and no overlap, ending at 150")
        void spansAreContiguousWithNoGapAndNoOverlap() {
            List<FieldSpan> spans = CardRecord.LAYOUT.storageSpans();

            assertThat(spans.get(0).offset())
                    .as("the first span must begin at offset 0")
                    .isZero();

            for (int index = 0; index < spans.size() - 1; index++) {
                FieldSpan current = spans.get(index);
                FieldSpan next = spans.get(index + 1);
                assertThat(next.offset())
                        .as("%s ends at %d, so %s must begin there - a gap or an overlap between "
                                        + "these two spans shifts every following byte",
                                current.name(), current.endOffsetExclusive(), next.name())
                        .isEqualTo(current.endOffsetExclusive());
            }

            FieldSpan last = spans.get(spans.size() - 1);
            assertThat(last.endOffsetExclusive())
                    .as("the trailing %s must end exactly at the declared record length",
                            last.name())
                    .isEqualTo(CardRecord.RECORD_LENGTH);
        }

        @ParameterizedTest(name = "[{0}] {1} is {2}")
        @CsvSource({
                "0, CARD-NUM,            ALPHANUMERIC",
                "1, CARD-ACCT-ID,        UNSIGNED_NUMERIC",
                "2, CARD-CVV-CD,         UNSIGNED_NUMERIC",
                "3, CARD-EMBOSSED-NAME,  ALPHANUMERIC",
                "4, CARD-EXPIRAION-DATE, ALPHANUMERIC",
                "5, CARD-ACTIVE-STATUS,  ALPHANUMERIC",
                "6, FILLER,              FILLER"
        })
        @DisplayName("Each span carries the picture category its PICTURE clause declares")
        void eachSpanCarriesItsPictureCategory(int declarationIndex, String fieldName,
                                               PictureKind kind) {
            FieldSpan span = CardRecord.LAYOUT.storageSpans().get(declarationIndex);

            assertThat(span.name()).isEqualTo(fieldName);
            assertThat(span.kind())
                    .as("%s is declared %s in app/cpy/CVACT02Y.cpy", fieldName, kind)
                    .isEqualTo(kind);
        }

        @Test
        @DisplayName("FILLER is not a referable COBOL name, so it cannot be looked up by name")
        void fillerIsNotReferableByName() {
            assertThat(CardRecord.LAYOUT.hasSpan("FILLER"))
                    .as("FILLER is unreferenceable in COBOL and must not resolve by name")
                    .isFalse();
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CardRecord.LAYOUT.span("FILLER"));
            assertThat(CardRecord.LAYOUT.storageSpans())
                    .as("and yet it is declared, occupying the last 59 bytes of the record")
                    .extracting(FieldSpan::name)
                    .contains("FILLER");
        }

        @Test
        @DisplayName("A name that CVACT02Y does not declare does not resolve, case included")
        void anUndeclaredNameDoesNotResolve() {
            assertThat(CardRecord.LAYOUT.hasSpan("CARD-EXPIRATION-DATE"))
                    .as("the correctly-spelled name is NOT what the copybook declares; see the "
                            + "MisspellingIsTheContract group")
                    .isFalse();
            assertThat(CardRecord.LAYOUT.hasSpan("card-num"))
                    .as("copybook names are matched case-sensitively")
                    .isFalse();
        }

        @Test
        @DisplayName("CARD-NUM is alphanumeric, so it is left justified and space padded")
        void cardNumIsLeftJustifiedAndSpacePadded() {
            assertThat(CardRecord.CARD_NUM.kind().leftJustified())
                    .as("PIC X fills from the left")
                    .isTrue();
            assertThat(CardRecord.CARD_ACCT_ID.kind().numericDisplay())
                    .as("PIC 9 is zoned DISPLAY and right justified")
                    .isTrue();
            assertThat(CardRecord.CARD_ACCT_ID.kind().leftJustified())
                    .as("a numeric receiver is not left justified")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("The layout self-check accepts CVACT02Y and rejects every way of breaking it")
    class LayoutSelfCheck {
        @Test
        @DisplayName("The passing side: the real CVACT02Y layout builds and declares 150 bytes")
        void theRealLayoutPassesTheSelfCheck() {
            assertThatCode(() -> RecordLayout.of(CardRecord.RECORD_LENGTH,
                    CardRecord.CARD_NUM,
                    CardRecord.CARD_ACCT_ID,
                    CardRecord.CARD_CVV_CD,
                    CardRecord.CARD_EMBOSSED_NAME,
                    CardRecord.CARD_EXPIRAION_DATE,
                    CardRecord.CARD_ACTIVE_STATUS,
                    CardRecord.FILLER))
                    .as("the seven copybook spans describe exactly 150 contiguous bytes")
                    .doesNotThrowAnyException();

            assertThat(CardRecord.LAYOUT.recordLength()).isEqualTo(CardRecord.RECORD_LENGTH);
            assertThat(CardRecord.LAYOUT.spans()).hasSize(7);
        }

        @Test
        @DisplayName("Dropping the trailing FILLER is rejected - the G21 tripwire, 59 bytes short")
        void droppingTheTrailingFillerIsRejected() {
            assertThatIllegalArgumentException()
                    .as("a layout without FILLER describes 91 bytes, not 150, and must not build")
                    .isThrownBy(() -> RecordLayout.of(CardRecord.RECORD_LENGTH,
                            CardRecord.CARD_NUM,
                            CardRecord.CARD_ACCT_ID,
                            CardRecord.CARD_CVV_CD,
                            CardRecord.CARD_EMBOSSED_NAME,
                            CardRecord.CARD_EXPIRAION_DATE,
                            CardRecord.CARD_ACTIVE_STATUS))
                    .withMessageContaining("91")
                    .withMessageContaining("150");
        }

        @Test
        @DisplayName("A gap between two spans is rejected, naming the span it precedes")
        void aGapBetweenSpansIsRejected() {
            FieldSpan displaced = FieldSpan.unsignedNumeric("CARD-ACCT-ID", 17,
                    CardRecord.CARD_ACCT_ID_LENGTH);

            assertThatIllegalArgumentException()
                    .as("every byte must be declared, so an unnamed gap must be rejected")
                    .isThrownBy(() -> RecordLayout.of(CardRecord.RECORD_LENGTH,
                            CardRecord.CARD_NUM,
                            displaced))
                    .withMessageContaining("gap")
                    .withMessageContaining("CARD-ACCT-ID");
        }

        @Test
        @DisplayName("An overlap between two spans is rejected, naming the overlapping span")
        void anOverlapBetweenSpansIsRejected() {
            FieldSpan overlapping = FieldSpan.unsignedNumeric("CARD-ACCT-ID", 15,
                    CardRecord.CARD_ACCT_ID_LENGTH);

            assertThatIllegalArgumentException()
                    .as("an unintended overlap must be rejected rather than silently aliasing bytes")
                    .isThrownBy(() -> RecordLayout.of(CardRecord.RECORD_LENGTH,
                            CardRecord.CARD_NUM,
                            overlapping))
                    .withMessageContaining("overlap")
                    .withMessageContaining("CARD-ACCT-ID");
        }

        @Test
        @DisplayName("A layout wider than its declared record length is rejected")
        void aLayoutWiderThanTheRecordIsRejected() {
            FieldSpan oversizedFiller = FieldSpan.filler(CardRecord.FILLER_OFFSET,
                    CardRecord.FILLER_LENGTH + 1);

            assertThatIllegalArgumentException()
                    .as("151 declared bytes cannot describe a 150-byte record")
                    .isThrownBy(() -> RecordLayout.of(CardRecord.RECORD_LENGTH,
                            CardRecord.CARD_NUM,
                            CardRecord.CARD_ACCT_ID,
                            CardRecord.CARD_CVV_CD,
                            CardRecord.CARD_EMBOSSED_NAME,
                            CardRecord.CARD_EXPIRAION_DATE,
                            CardRecord.CARD_ACTIVE_STATUS,
                            oversizedFiller))
                    .withMessageContaining("151");
        }

        @ParameterizedTest(name = "{0} bytes is not a CARD-RECORD")
        @ValueSource(ints = {0, 1, 36, 91, 149, 151, 300})
        @DisplayName("Wrapping or decoding any width other than 150 is rejected")
        void anyWidthOtherThanTheDeclaredOneIsRejected(int wrongWidth) {
            byte[] wrong = new byte[wrongWidth];

            assertThatIllegalArgumentException()
                    .as("a fixed-width record has exactly one legal width")
                    .isThrownBy(() -> CardRecord.decode(wrong, ASCII))
                    .withMessageContaining(String.valueOf(CardRecord.RECORD_LENGTH));
        }

        @Test
        @DisplayName("A FieldSpan of zero width is rejected, so no span can vanish silently")
        void aZeroWidthSpanIsRejected() {
            assertThatIllegalArgumentException()
                    .as("every copybook item occupies at least one byte")
                    .isThrownBy(() -> FieldSpan.alphanumeric("CARD-NUM", 0, 0));
        }
    }

    @Nested
    @DisplayName("Two keys over one dataset - CARDDAT base and CARDAIX alternate index")
    class KeyOffsets {
        @Test
        @DisplayName("The CARDDAT base KSDS key is CARD-NUM, the 16 bytes at [0, 16)")
        void baseKeyIsCardNumAtOffsetZero() {
            FieldSpan key = CardRecord.cardDatPrimaryKeySpan();

            assertThat(key.name()).isEqualTo("CARD-NUM");
            assertThat(key.offset())
                    .as("the CARDDAT base key begins at the first byte of the record")
                    .isZero();
            assertThat(key.length())
                    .as("app/csd/CARDDEMO.CSD:25 keys CARDDAT on the 16-byte card number")
                    .isEqualTo(16);
            assertThat(key.kind())
                    .as("the key is PIC X, so it is compared as bytes and keeps its leading zero")
                    .isEqualTo(PictureKind.ALPHANUMERIC);
        }

        @Test
        @DisplayName("The CARDAIX alternate-index key is CARD-ACCT-ID, the 11 bytes at [16, 27)")
        void alternateKeyIsAcctIdAtOffsetSixteen() {
            FieldSpan key = CardRecord.cardAixAlternateKeySpan();

            assertThat(key.name()).isEqualTo("CARD-ACCT-ID");
            assertThat(key.offset())
                    .as("CVACT02Y puts the account id at offset 16. The near-identical account id "
                            + "of CVACT03Y sits at offset 25 of a different record, and confusing "
                            + "the two compiles cleanly while reading the wrong bytes")
                    .isEqualTo(16);
            assertThat(key.length())
                    .as("app/csd/CARDDEMO.CSD:13 defines CARDAIX as a PATH over the CARDDAT base, "
                            + "so it reaches this same 150-byte record by an 11-digit account id")
                    .isEqualTo(11);
        }

        @Test
        @DisplayName("The two keys are different spans and cannot be transposed")
        void theTwoKeysAreDistinct() {
            FieldSpan base = CardRecord.cardDatPrimaryKeySpan();
            FieldSpan alternate = CardRecord.cardAixAlternateKeySpan();

            assertThat(base).isNotEqualTo(alternate);
            assertThat(base.name()).isNotEqualTo(alternate.name());
            assertThat(base.offset()).isNotEqualTo(alternate.offset());
            assertThat(base.endOffsetExclusive())
                    .as("the base key ends exactly where the alternate key begins")
                    .isEqualTo(alternate.offset());
        }

        @Test
        @DisplayName("Both key spans are addressable by name through the layout")
        void bothKeysAreAddressableThroughTheLayout() {
            assertThat(CardRecord.LAYOUT.hasSpan("CARD-NUM")).isTrue();
            assertThat(CardRecord.LAYOUT.hasSpan("CARD-ACCT-ID")).isTrue();
            assertThat(CardRecord.LAYOUT.span("CARD-NUM"))
                    .isEqualTo(CardRecord.cardDatPrimaryKeySpan());
            assertThat(CardRecord.LAYOUT.span("CARD-ACCT-ID"))
                    .isEqualTo(CardRecord.cardAixAlternateKeySpan());
        }

        @Test
        @DisplayName("The key images are the stored digit strings, not the parsed values")
        void keyImagesAreTheStoredDigits() {
            CardRecord record = row1();

            assertThat(record.cardAcctIdImage(asciiCodec))
                    .as("VSAM keys on bytes, so account id 50 is the eleven digits the fixture "
                            + "stores at [16, 27)")
                    .isEqualTo("00000000050");
            assertThat(record.cardCvvCdImage(asciiCodec))
                    .as("CVV 747 occupies its full three digits")
                    .isEqualTo("747");
        }
    }

    @Nested
    @DisplayName("PIC X MOVE parity - pad on the right, truncate on the right")
    class MovePicXParity {
        @Test
        @DisplayName("A short CARD-EMBOSSED-NAME is right-padded with spaces to its full 50 bytes")
        void shortNameIsRightPaddedToFiftyBytes() {
            CardRecord record = row1();

            assertThat(record.cardEmbossedName())
                    .as("'Aniya Von' is 9 characters, so 41 spaces follow it; the padding is data "
                            + "and is never trimmed, because the parity differ compares it")
                    .isEqualTo(expectedPadded(ROW_1_NAME, CardRecord.CARD_EMBOSSED_NAME_LENGTH))
                    .hasSize(CardRecord.CARD_EMBOSSED_NAME_LENGTH)
                    .startsWith(ROW_1_NAME);
        }

        @Test
        @DisplayName("The padding bytes are ASCII spaces (0x20), never NULs (0x00)")
        void paddingBytesAreSpacesNotNuls() {
            byte[] image = row1().encode(ASCII);
            int paddingBegin = CardRecord.CARD_EMBOSSED_NAME_OFFSET + ROW_1_NAME.length();
            int paddingEnd = CardRecord.CARD_EMBOSSED_NAME_OFFSET
                    + CardRecord.CARD_EMBOSSED_NAME_LENGTH;

            for (int offset = paddingBegin; offset < paddingEnd; offset++) {
                assertThat(image[offset])
                        .as("byte %d of CARD-EMBOSSED-NAME's padding must be an ASCII space 0x20, "
                                + "not 0x00 - a NUL here would corrupt every fixed-width consumer",
                                offset)
                        .isEqualTo(ASCII_SPACE_BYTE);
            }
        }

        @Test
        @DisplayName("An over-wide name keeps its FIRST 50 characters - truncation is on the right")
        void overWideNameKeepsItsLeadingCharacters() {
            String sixty = "HEAD-Aniya-Von-Cardholder-Embossed-Name-Padding-XY" + "-DISCARDED";
            assertThat(sixty).hasSize(60);
            String leading50 = sixty.substring(0, CardRecord.CARD_EMBOSSED_NAME_LENGTH);
            String trailing50 = sixty.substring(sixty.length() - CardRecord.CARD_EMBOSSED_NAME_LENGTH);
            assertThat(leading50)
                    .as("the two ends must differ for this test to have any power")
                    .isNotEqualTo(trailing50);

            CardRecord record = CardRecord.moving(ROW_1_CARD_NUM, ROW_1_ACCT_ID, ROW_1_CVV, sixty,
                    ROW_1_EXPIRY, ROW_1_STATUS, asciiCodec);

            assertThat(record.cardEmbossedName())
                    .as("a PIC X receiver fills from the left, so the surviving characters are the "
                            + "leading 50 and the trailing 10 are discarded")
                    .isEqualTo(leading50)
                    .hasSize(CardRecord.CARD_EMBOSSED_NAME_LENGTH)
                    .doesNotContain("DISCARDED");
            assertThat(record.cardEmbossedName())
                    .as("keeping the trailing characters instead would be the PIC 9 rule applied to "
                            + "a PIC X field - the exact defect this asserts against")
                    .isNotEqualTo(trailing50);
        }

        @Test
        @DisplayName("A name of exactly 50 characters is stored unchanged")
        void exactlyWideNameIsStoredUnchanged() {
            String exactly = "X".repeat(CardRecord.CARD_EMBOSSED_NAME_LENGTH);

            assertThat(new CardRecord(ROW_1_CARD_NUM, ROW_1_ACCT_ID, ROW_1_CVV, exactly,
                    ROW_1_EXPIRY, ROW_1_STATUS).cardEmbossedName())
                    .as("a value already at its declared width is neither padded nor truncated")
                    .isEqualTo(exactly);
        }

        @Test
        @DisplayName("An empty PIC X value denotes SPACES and still occupies its full width")
        void emptyValueDenotesSpaces() {
            CardRecord initialised = CardRecord.initialised();

            assertThat(initialised.cardNum())
                    .as("COBOL INITIALIZE space-fills an alphanumeric field; the span keeps its "
                            + "declared 16 bytes")
                    .isEqualTo(SPACE.repeat(CardRecord.CARD_NUM_LENGTH));
            assertThat(initialised.cardEmbossedName())
                    .isEqualTo(SPACE.repeat(CardRecord.CARD_EMBOSSED_NAME_LENGTH));
            assertThat(initialised.cardExpiraionDate())
                    .isEqualTo(SPACE.repeat(CardRecord.CARD_EXPIRAION_DATE_LENGTH));
            assertThat(initialised.cardActiveStatus()).isEqualTo(SPACE);
            assertThat(initialised.cardAcctId())
                    .as("COBOL INITIALIZE zeroes a numeric field")
                    .isZero();
            assertThat(initialised.cardCvvCd()).isZero();
            assertThat(initialised.encode(ASCII))
                    .as("an initialised record is still the full declared width")
                    .hasSize(CardRecord.RECORD_LENGTH);
        }

        @ParameterizedTest(name = "movePicX(\"{0}\", 10) -> \"{1}\"")
        @CsvSource(quoteCharacter = '\'', ignoreLeadingAndTrailingWhitespace = false, value = {
                "'ABC','ABC       '",
                "'ABCDEFGHIJ','ABCDEFGHIJ'",
                "'ABCDEFGHIJKLMNO','ABCDEFGHIJ'",
                "'','          '",
                "'2023-03-09','2023-03-09'"
        })
        @DisplayName("movePicX pads and truncates on the right at every width")
        void movePicXPadsAndTruncatesOnTheRight(String source, String expected) {
            assertThat(asciiCodec.movePicX(source, CardRecord.CARD_EXPIRAION_DATE_LENGTH))
                    .as("a PIC X(10) receiver holds exactly ten characters, filled from the left")
                    .isEqualTo(expected)
                    .hasSize(CardRecord.CARD_EXPIRAION_DATE_LENGTH);
        }
    }

    @Nested
    @DisplayName("PIC 9 MOVE parity - zero-fill on the left, truncate on the left")
    class MovePic9Parity {
        @Test
        @DisplayName("CARD-ACCT-ID 50 is written 00000000050 - eleven digits, zero-filled left")
        void acctIdIsZeroFilledOnTheLeft() {
            byte[] image = row1().encode(ASCII);
            String stored = new String(image, CardRecord.CARD_ACCT_ID_OFFSET,
                    CardRecord.CARD_ACCT_ID_LENGTH, ASCII);

            assertThat(stored)
                    .as("the fixture stores account id 50 at [16, 27) as eleven zoned digits")
                    .isEqualTo("00000000050");
        }

        @Test
        @DisplayName("CARD-CVV-CD 28 is written 028 - measured on fixture row 3, not guessed")
        void cvvIsZeroFilledOnTheLeft() {
            CardRecord record = new CardRecord(ROW_3_CARD_NUM, 2L, 28, "Enrico Rosenbaum",
                    "2024-08-11", ROW_1_STATUS);
            byte[] image = record.encode(ASCII);
            String stored = new String(image, CardRecord.CARD_CVV_CD_OFFSET,
                    CardRecord.CARD_CVV_CD_LENGTH, ASCII);

            assertThat(stored)
                    .as("row 3 of app/data/ASCII/carddata.txt carries exactly 028 at [27, 30), so "
                            + "the left zero-fill is observed behaviour rather than an assumption")
                    .isEqualTo("028");
        }

        @Test
        @DisplayName("An over-wide CARD-ACCT-ID keeps its RIGHTMOST 11 digits")
        void overWideAcctIdKeepsItsLowOrderDigits() {
            long thirteenDigits = 1_234_567_890_123L;
            assertThat(String.valueOf(thirteenDigits)).hasSize(13);

            CardRecord record = CardRecord.moving(ROW_1_CARD_NUM, thirteenDigits, ROW_1_CVV,
                    ROW_1_NAME, ROW_1_EXPIRY, ROW_1_STATUS, asciiCodec);

            assertThat(record.cardAcctId())
                    .as("a numeric receiver keeps the LOW-order digits, so 1234567890123 stores as "
                            + "34567890123; keeping the leading digits would be the classic defect")
                    .isEqualTo(34_567_890_123L);
            assertThat(record.cardAcctIdImage(asciiCodec))
                    .as("the stored image is exactly eleven digits")
                    .isEqualTo("34567890123")
                    .hasSize(CardRecord.CARD_ACCT_ID_LENGTH);
        }

        @Test
        @DisplayName("An over-wide CARD-CVV-CD keeps its RIGHTMOST 3 digits")
        void overWideCvvKeepsItsLowOrderDigits() {
            CardRecord record = CardRecord.moving(ROW_1_CARD_NUM, ROW_1_ACCT_ID, 1234, ROW_1_NAME,
                    ROW_1_EXPIRY, ROW_1_STATUS, asciiCodec);

            assertThat(record.cardCvvCd())
                    .as("PIC 9(03) keeps the low-order three digits of 1234")
                    .isEqualTo(234);
        }

        @ParameterizedTest(name = "movePic9({0}, 11) -> {1}")
        @CsvSource({
                "50,            00000000050",
                "0,             00000000000",
                "12345678901,   12345678901",
                "1234567890123, 34567890123"
        })
        @DisplayName("movePic9 zero-fills and truncates on the left at every width")
        void movePic9ZeroFillsAndTruncatesOnTheLeft(long source, String expected) {
            assertThat(asciiCodec.movePic9(source, CardRecord.CARD_ACCT_ID_LENGTH))
                    .as("a PIC 9(11) receiver holds exactly eleven digits, aligned on the right")
                    .isEqualTo(expected)
                    .hasSize(CardRecord.CARD_ACCT_ID_LENGTH);
        }

        @Test
        @DisplayName("The two truncation directions genuinely differ - the asymmetry, side by side")
        void theTwoTruncationDirectionsAreOpposite() {
            String fifteenChars = "123456789012345";

            assertThat(asciiCodec.movePicX(fifteenChars, CardRecord.CARD_ACCT_ID_LENGTH))
                    .as("PIC X keeps the LEADING eleven characters of 123456789012345")
                    .isEqualTo("12345678901");
            assertThat(asciiCodec.movePic9(fifteenChars, CardRecord.CARD_ACCT_ID_LENGTH))
                    .as("PIC 9 keeps the TRAILING eleven digits - the opposite end of the same value")
                    .isEqualTo("56789012345");
            assertThat(asciiCodec.movePicX(fifteenChars, CardRecord.CARD_ACCT_ID_LENGTH))
                    .as("the two rules must not coincide, or neither is being tested")
                    .isNotEqualTo(asciiCodec.movePic9(fifteenChars,
                            CardRecord.CARD_ACCT_ID_LENGTH));
        }
    }

    @Nested
    @DisplayName("CARD-NUM is alphanumeric - leading zeros are data (G22)")
    class CardNumIsAlphanumeric {
        @ParameterizedTest(name = "{0} survives a round trip with its leading zero")
        @ValueSource(strings = {"0500024453765740", "0683586198171516", "0923877193247330",
                "0000000000000001"})
        @DisplayName("A leading zero survives encode and decode intact")
        void leadingZeroSurvivesTheRoundTrip(String cardNumber) {
            CardRecord original = new CardRecord(cardNumber, ROW_1_ACCT_ID, ROW_1_CVV, ROW_1_NAME,
                    ROW_1_EXPIRY, ROW_1_STATUS);

            CardRecord decoded = CardRecord.decode(original.encode(ASCII), ASCII);

            assertThat(decoded.cardNum())
                    .as("holding CARD-NUM as a long or a BigInteger would silently drop the leading "
                            + "zero and break every keyed read against CARDDAT")
                    .isEqualTo(cardNumber)
                    .startsWith("0")
                    .hasSize(CardRecord.CARD_NUM_LENGTH);
        }

        @Test
        @DisplayName("The stored CARD-NUM span is 16 bytes and holds the digits verbatim")
        void storedSpanIsSixteenBytesVerbatim() {
            byte[] image = row1().encode(ASCII);
            String stored = new String(image, CardRecord.CARD_NUM_OFFSET,
                    CardRecord.CARD_NUM_LENGTH, ASCII);

            assertThat(stored).isEqualTo(ROW_1_CARD_NUM).hasSize(16);
        }

        @Test
        @DisplayName("The cardNum accessor returns String, never a numeric type")
        void cardNumAccessorIsNotNumeric() throws NoSuchMethodException {
            Method accessor = CardRecord.class.getDeclaredMethod("cardNum");

            assertThat(accessor.getReturnType())
                    .as("CVACT02Y:5 declares CARD-NUM PIC X(16). Any numeric return type here - "
                            + "long, int, BigInteger, BigDecimal - would be a silent data-loss bug")
                    .isEqualTo(String.class);
            assertThat(Number.class.isAssignableFrom(accessor.getReturnType()))
                    .as("the accessor's static type must not be a Number at all")
                    .isFalse();
        }

        @Test
        @DisplayName("The numeric components are the scale-free integers their PICTUREs declare")
        void numericComponentsAreScaleFreeIntegers() throws NoSuchMethodException {
            assertThat(CardRecord.class.getDeclaredMethod("cardAcctId").getReturnType())
                    .as("PIC 9(11) is unsigned and scale-free, so a long carries it exactly")
                    .isEqualTo(long.class);
            assertThat(CardRecord.class.getDeclaredMethod("cardCvvCd").getReturnType())
                    .as("PIC 9(03) fits an int")
                    .isEqualTo(int.class);
        }
    }

    @Nested
    @DisplayName("CARD-EXPIRAION-DATE is misspelled in the copybook, and stays misspelled (B5)")
    class MisspellingIsTheContract {
        @Test
        @DisplayName("The span name is CARD-EXPIRAION-DATE, exactly as CVACT02Y:9 spells it")
        void spanNameCarriesTheCopybookMisspelling() {
            assertThat(CardRecord.CARD_EXPIRAION_DATE.name())
                    .as("the copybook spelling is the contract; EXPIRAION is not a typo in this test")
                    .isEqualTo("CARD-EXPIRAION-DATE")
                    .doesNotContain("EXPIRATION");
        }

        @Test
        @DisplayName("The accessor is named cardExpiraionDate, matching the copybook")
        void accessorCarriesTheCopybookMisspelling() {
            assertThatCode(() -> CardRecord.class.getDeclaredMethod("cardExpiraionDate"))
                    .as("the component must keep the copybook's spelling")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("No member anywhere on CardRecord uses the corrected spelling EXPIRATION")
        void noMemberUsesTheCorrectedSpelling() {
            List<String> offenders = new ArrayList<>();
            for (Method method : CardRecord.class.getDeclaredMethods()) {
                if (method.getName().toUpperCase().contains("EXPIRATION")) {
                    offenders.add("method " + method.getName());
                }
            }
            for (Field field : CardRecord.class.getDeclaredFields()) {
                if (field.getName().toUpperCase().contains("EXPIRATION")) {
                    offenders.add("field " + field.getName());
                }
            }
            for (RecordComponent component : CardRecord.class.getRecordComponents()) {
                if (component.getName().toUpperCase().contains("EXPIRATION")) {
                    offenders.add("component " + component.getName());
                }
            }

            assertThat(offenders)
                    .as("a member spelled EXPIRATION would mean the copybook field had been renamed, "
                            + "which breaks field-for-field diffing. Restore EXPIRAION instead.")
                    .isEmpty();
        }

        @Test
        @DisplayName("The misspelled name is what the diagnostic rendering prints")
        void diagnosticRenderingUsesTheCopybookName() {
            assertThat(row1().toString())
                    .as("a failing parity case has to read as the copybook reads")
                    .contains("CARD-EXPIRAION-DATE")
                    .doesNotContain("EXPIRATION");
        }
    }

    @Nested
    @DisplayName("Expiry slices - 1-based reference modification, and no validation whatsoever")
    class ExpirySlices {
        @Test
        @DisplayName("(1:4), (6:2) and (9:2) translate to [0,4), [5,7) and [8,10)")
        void sliceIndicesTranslateFromOneBasedReferenceModification() {
            assertThat(CardRecord.EXPIRAION_YEAR_BEGIN_INDEX).isZero();
            assertThat(CardRecord.EXPIRAION_YEAR_END_INDEX).isEqualTo(4);
            assertThat(CardRecord.EXPIRAION_MONTH_BEGIN_INDEX)
                    .as("COCRDUPC:1363 slices (6:2); 1-based position 6 is 0-based index 5")
                    .isEqualTo(5);
            assertThat(CardRecord.EXPIRAION_MONTH_END_INDEX).isEqualTo(7);
            assertThat(CardRecord.EXPIRAION_DAY_BEGIN_INDEX)
                    .as("COCRDUPC:1365 slices (9:2); 1-based position 9 is 0-based index 8")
                    .isEqualTo(8);
            assertThat(CardRecord.EXPIRAION_DAY_END_INDEX)
                    .as("the day slice ends at the span's full declared width")
                    .isEqualTo(CardRecord.CARD_EXPIRAION_DATE_LENGTH);
        }

        @Test
        @DisplayName("2023-03-09 slices to 2023, 03 and 09 - fixture row 1")
        void fixtureRowOneSlicesToItsThreeParts() {
            CardRecord record = row1();

            assertThat(record.cardExpiraionDateYear()).isEqualTo("2023");
            assertThat(record.cardExpiraionDateMonth()).isEqualTo("03");
            assertThat(record.cardExpiraionDateDay()).isEqualTo("09");
        }

        @Test
        @DisplayName("The separators sit at 0-based indices 4 and 7 of the raw span")
        void separatorsSitAtIndicesFourAndSeven() {
            byte[] image = row1().encode(ASCII);
            String rawSpan = new String(image, CardRecord.CARD_EXPIRAION_DATE_OFFSET,
                    CardRecord.CARD_EXPIRAION_DATE_LENGTH, ASCII);

            assertThat(rawSpan).isEqualTo(ROW_1_EXPIRY).hasSize(10);
            assertThat(rawSpan.charAt(4))
                    .as("1-based position 5 of YYYY-MM-DD is the first separator")
                    .isEqualTo('-');
            assertThat(rawSpan.charAt(7))
                    .as("1-based position 8 of YYYY-MM-DD is the second separator")
                    .isEqualTo('-');
        }

        @Test
        @DisplayName("The separators never surface as data in any slice")
        void separatorsNeverSurfaceInASlice() {
            CardRecord record = row1();

            assertThat(record.cardExpiraionDateYear()).doesNotContain("-");
            assertThat(record.cardExpiraionDateMonth()).doesNotContain("-");
            assertThat(record.cardExpiraionDateDay()).doesNotContain("-");
        }

        @ParameterizedTest(name = "\"{0}\" slices to \"{1}\"/\"{2}\"/\"{3}\" without throwing")
        @CsvSource(quoteCharacter = '\'', ignoreLeadingAndTrailingWhitespace = false, value = {
                "'ABCDEFGHIJ','ABCD','FG','IJ'",
                "'2023/03/09','2023','03','09'",
                "'0000000000','0000','00','00'",
                "'9999-99-99','9999','99','99'",
                "'2023-13-45','2023','13','45'",
                "'..........','....','..','..'"
        })
        @DisplayName("A non-date span slices without throwing - COBOL does not validate here")
        void aNonDateSpanSlicesWithoutThrowing(String stored, String expectedYear,
                                               String expectedMonth, String expectedDay) {
            CardRecord record = new CardRecord(ROW_1_CARD_NUM, ROW_1_ACCT_ID, ROW_1_CVV, ROW_1_NAME,
                    stored, ROW_1_STATUS);

            assertThat(record.cardExpiraionDateYear()).isEqualTo(expectedYear);
            assertThat(record.cardExpiraionDateMonth()).isEqualTo(expectedMonth);
            assertThat(record.cardExpiraionDateDay()).isEqualTo(expectedDay);
            assertThat(record.cardExpiraionDate())
                    .as("the raw span is stored verbatim, unparsed and unvalidated")
                    .isEqualTo(stored);
        }

        @Test
        @DisplayName("An all-spaces expiry span slices to spaces rather than throwing")
        void anAllSpacesSpanSlicesToSpaces() {
            CardRecord record = CardRecord.initialised();

            assertThat(record.cardExpiraionDate())
                    .isEqualTo(SPACE.repeat(CardRecord.CARD_EXPIRAION_DATE_LENGTH));
            assertThat(record.cardExpiraionDateYear()).isEqualTo("    ");
            assertThat(record.cardExpiraionDateMonth()).isEqualTo("  ");
            assertThat(record.cardExpiraionDateDay()).isEqualTo("  ");
        }

        @Test
        @DisplayName("A short expiry value is right-space-padded to 10 first, then sliced")
        void aShortValueIsPaddedThenSliced() {
            CardRecord record = new CardRecord(ROW_1_CARD_NUM, ROW_1_ACCT_ID, ROW_1_CVV, ROW_1_NAME,
                    "2023-03", ROW_1_STATUS);

            assertThat(record.cardExpiraionDate())
                    .isEqualTo("2023-03   ")
                    .hasSize(CardRecord.CARD_EXPIRAION_DATE_LENGTH);
            assertThat(record.cardExpiraionDateYear()).isEqualTo("2023");
            assertThat(record.cardExpiraionDateMonth()).isEqualTo("03");
            assertThat(record.cardExpiraionDateDay())
                    .as("the day slice reads the padding, and must not throw")
                    .isEqualTo("  ");
        }

        @Test
        @DisplayName("An empty expiry span still yields three total slices")
        void anEmptySpanStillYieldsThreeSlices() {
            CardRecord record = new CardRecord(ROW_1_CARD_NUM, ROW_1_ACCT_ID, ROW_1_CVV, ROW_1_NAME,
                    "", ROW_1_STATUS);

            assertThatCode(() -> {
                record.cardExpiraionDateYear();
                record.cardExpiraionDateMonth();
                record.cardExpiraionDateDay();
            })
                    .as("padding to the declared width is what makes the slices total")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("The slices survive a full encode and decode cycle")
        void slicesSurviveTheRoundTrip() {
            CardRecord decoded = CardRecord.decode(row1().encode(ASCII), ASCII);

            assertThat(decoded.cardExpiraionDateYear()).isEqualTo("2023");
            assertThat(decoded.cardExpiraionDateMonth()).isEqualTo("03");
            assertThat(decoded.cardExpiraionDateDay()).isEqualTo("09");
        }
    }

    @Nested
    @DisplayName("CARD-ACTIVE-STATUS X(01) - one byte, carried verbatim, never widened")
    class ActiveStatus {
        @ParameterizedTest(name = "status \"{0}\" round-trips as one byte")
        @CsvSource(quoteCharacter = '\'', ignoreLeadingAndTrailingWhitespace = false, value = {
                "'Y'", "'N'", "' '", "'0'", "'1'", "'A'"
        })
        @DisplayName("Y, N, a space and anything else all round-trip as a single byte")
        void everyStatusValueRoundTrips(String status) {
            CardRecord record = new CardRecord(ROW_1_CARD_NUM, ROW_1_ACCT_ID, ROW_1_CVV, ROW_1_NAME,
                    ROW_1_EXPIRY, status);

            CardRecord decoded = CardRecord.decode(record.encode(ASCII), ASCII);

            assertThat(decoded.cardActiveStatus())
                    .as("PIC X(01) carries its byte verbatim; it is never converted to a boolean or "
                            + "an enum, because its byte value is part of the record contract")
                    .isEqualTo(status)
                    .hasSize(CardRecord.CARD_ACTIVE_STATUS_LENGTH);
        }

        @Test
        @DisplayName("The not-active status the fixture cannot supply is still fully supported")
        void theNotActiveStatusIsSupported() {
            CardRecord inactive = row1().withCardActiveStatus("N");

            assertThat(inactive.cardActiveStatus()).isEqualTo("N");
            assertThat(inactive.encode(ASCII)[CardRecord.CARD_ACTIVE_STATUS_OFFSET])
                    .as("byte 90 must hold the N")
                    .isEqualTo((byte) 'N');
            assertThat(inactive)
                    .as("changing only the status must produce a different record")
                    .isNotEqualTo(row1());
        }

        @Test
        @DisplayName("A multi-character status is rejected rather than silently keeping one byte")
        void aMultiCharacterStatusIsRejected() {
            assertThatIllegalArgumentException()
                    .as("the canonical constructor never truncates; the direction has to be chosen "
                            + "deliberately through moving(...)")
                    .isThrownBy(() -> row1().withCardActiveStatus("YN"));
        }

        @Test
        @DisplayName("Under COBOL MOVE semantics a multi-character status keeps its FIRST byte")
        void underMoveSemanticsAMultiCharacterStatusKeepsItsFirstByte() {
            CardRecord record = CardRecord.moving(ROW_1_CARD_NUM, ROW_1_ACCT_ID, ROW_1_CVV,
                    ROW_1_NAME, ROW_1_EXPIRY, "YN", asciiCodec);

            assertThat(record.cardActiveStatus())
                    .as("a PIC X(01) receiver fills from the left, so the Y survives and the N is "
                            + "discarded")
                    .isEqualTo("Y");
        }
    }

    @Nested
    @DisplayName("FILLER X(59) is emitted and space-filled on every encode (G21)")
    class FillerSpan {
        @Test
        @DisplayName("The FILLER span is declared at [91, 150) as a first-class FILLER descriptor")
        void fillerIsAFirstClassSpan() {
            assertThat(CardRecord.FILLER.name()).isEqualTo("FILLER");
            assertThat(CardRecord.FILLER.offset()).isEqualTo(91);
            assertThat(CardRecord.FILLER.length()).isEqualTo(59);
            assertThat(CardRecord.FILLER.endOffsetExclusive())
                    .as("the reserved span runs to the end of the record")
                    .isEqualTo(CardRecord.RECORD_LENGTH);
            assertThat(CardRecord.FILLER.kind().filler())
                    .as("it is a FILLER, not an ordinary alphanumeric field")
                    .isTrue();
            assertThat(CardRecord.FILLER.hasInitialValue())
                    .as("CVACT02Y declares no VALUE on the FILLER, so it is space-filled")
                    .isFalse();
        }

        @Test
        @DisplayName("Bytes [91, 150) are 59 ASCII spaces - the explicit slice, not just the width")
        void fillerIsFiftyNineAsciiSpaces() {
            byte[] image = row1().encode(ASCII);
            String filler = new String(image, CardRecord.FILLER_OFFSET, CardRecord.FILLER_LENGTH,
                    ASCII);

            assertThat(filler)
                    .as("every row of app/data/ASCII/carddata.txt ends with 59 spaces")
                    .isEqualTo(SPACE.repeat(CardRecord.FILLER_LENGTH))
                    .hasSize(59);
        }

        @Test
        @DisplayName("Every FILLER byte is 0x20 individually, never 0x00")
        void everyFillerByteIsASpaceNotANul() {
            byte[] image = row1().encode(ASCII);

            for (int offset = CardRecord.FILLER_OFFSET; offset < CardRecord.RECORD_LENGTH; offset++) {
                assertThat(image[offset])
                        .as("FILLER byte at offset %d must be an ASCII space 0x20. A 0x00 here is "
                                + "the classic 'allocated but never initialised' defect and would "
                                + "corrupt every downstream fixed-width reader.", offset)
                        .isEqualTo(ASCII_SPACE_BYTE)
                        .isNotEqualTo((byte) 0x00);
            }
        }

        @Test
        @DisplayName("The FILLER is space-filled in EBCDIC too - 0x40, not 0x20 and not 0x00")
        void fillerIsSpaceFilledInEbcdicToo() {
            byte[] image = row1().encode(EBCDIC);

            assertThat(image).hasSize(CardRecord.RECORD_LENGTH);
            for (int offset = CardRecord.FILLER_OFFSET; offset < CardRecord.RECORD_LENGTH; offset++) {
                assertThat(image[offset])
                        .as("the EBCDIC space is 0x40; the pad byte follows the code page, not the "
                                + "platform", offset)
                        .isEqualTo(EBCDIC_SPACE_BYTE);
            }
        }

        @Test
        @DisplayName("An initialised record still emits its FILLER")
        void anInitialisedRecordStillEmitsItsFiller() {
            byte[] image = CardRecord.initialised().encode(ASCII);

            assertThat(image).hasSize(CardRecord.RECORD_LENGTH);
            assertThat(new String(image, CardRecord.FILLER_OFFSET, CardRecord.FILLER_LENGTH, ASCII))
                    .isEqualTo(SPACE.repeat(CardRecord.FILLER_LENGTH));
        }

        @Test
        @DisplayName("The record area exposes the FILLER span as addressable bytes")
        void theRecordAreaExposesTheFillerSpan() {
            FixedWidthRecord area = row1().toFixedWidthRecord(asciiCodec);

            assertThat(area.recordLength()).isEqualTo(CardRecord.RECORD_LENGTH);
            assertThat(area.readSpan(CardRecord.FILLER))
                    .isEqualTo(SPACE.repeat(CardRecord.FILLER_LENGTH));
            assertThat(area.readSpanBytes(CardRecord.FILLER)).hasSize(CardRecord.FILLER_LENGTH);
            assertThat(area.spacePadByte())
                    .as("the ASCII space is the pad byte under US-ASCII")
                    .isEqualTo(ASCII_SPACE_BYTE);
        }
    }

    @Nested
    @DisplayName("The real fixture - 50 rows of 150 bytes from the test classpath (G16)")
    class RealFixture {
        @Test
        @DisplayName("The fixture resource is present on the test classpath")
        void theFixtureResourceIsPresent() {
            assertThat(CardRecordTest.class.getClassLoader().getResource(FIXTURE_RESOURCE))
                    .as("The card fixture must be on the test classpath at '%s'. It is derived from "
                            + "app/data/ASCII/carddata.txt, which this test never reads directly.",
                            FIXTURE_RESOURCE)
                    .isNotNull();
        }

        @Test
        @DisplayName("The fixture holds exactly 50 rows, each exactly 150 bytes")
        void theFixtureHasFiftyRowsOfExactlyOneHundredAndFiftyBytes() {
            List<String> rows = fixtureRows();

            assertThat(rows)
                    .as("app/data/ASCII/carddata.txt holds 50 card records")
                    .hasSize(FIXTURE_ROW_COUNT);
            for (int index = 0; index < rows.size(); index++) {
                assertThat(rows.get(index).getBytes(ASCII))
                        .as("row %d must be exactly %d bytes. Unlike cardxref, the card fixture "
                                        + "already carries its trailing FILLER, so no widening is "
                                        + "needed here.", index + 1, CardRecord.RECORD_LENGTH)
                        .hasSize(CardRecord.RECORD_LENGTH);
            }
        }

        @Test
        @DisplayName("Fixture row 1 decodes to all six fields plus a 59-space FILLER")
        void rowOneDecodesFieldByField() {
            CardRecord record = CardRecord.decodeImage(fixtureRows().get(0), ASCII);

            assertThat(record.cardNum()).isEqualTo(ROW_1_CARD_NUM);
            assertThat(record.cardAcctId()).isEqualTo(ROW_1_ACCT_ID);
            assertThat(record.cardCvvCd()).isEqualTo(ROW_1_CVV);
            assertThat(record.cardEmbossedName())
                    .as("'Aniya Von' followed by 41 spaces - the padding is not trimmed")
                    .isEqualTo(expectedPadded(ROW_1_NAME, CardRecord.CARD_EMBOSSED_NAME_LENGTH));
            assertThat(record.cardExpiraionDate()).isEqualTo(ROW_1_EXPIRY);
            assertThat(record.cardActiveStatus()).isEqualTo(ROW_1_STATUS);
            assertThat(new String(record.encode(ASCII), CardRecord.FILLER_OFFSET,
                    CardRecord.FILLER_LENGTH, ASCII))
                    .isEqualTo(SPACE.repeat(CardRecord.FILLER_LENGTH));
        }

        @Test
        @DisplayName("Fixture row 2 decodes to Ward Jones, account 27, CVV 567, expiry 2025-07-13")
        void rowTwoDecodesFieldByField() {
            CardRecord record = CardRecord.decodeImage(fixtureRows().get(1), ASCII);

            assertThat(record.cardNum()).isEqualTo(ROW_2_CARD_NUM);
            assertThat(record.cardAcctId()).isEqualTo(27L);
            assertThat(record.cardCvvCd()).isEqualTo(567);
            assertThat(record.cardEmbossedName())
                    .isEqualTo(expectedPadded("Ward Jones", CardRecord.CARD_EMBOSSED_NAME_LENGTH));
            assertThat(record.cardExpiraionDate()).isEqualTo("2025-07-13");
            assertThat(record.cardActiveStatus()).isEqualTo("Y");
        }

        @Test
        @DisplayName("Fixture row 3 decodes CVV 028 to 28 - the stored left zero-fill, read back")
        void rowThreeDecodesFieldByField() {
            String row = fixtureRows().get(2);
            CardRecord record = CardRecord.decodeImage(row, ASCII);

            assertThat(record.cardNum()).isEqualTo(ROW_3_CARD_NUM);
            assertThat(record.cardAcctId()).isEqualTo(2L);
            assertThat(record.cardCvvCd())
                    .as("the span holds the three characters 028, which denote the value 28")
                    .isEqualTo(28);
            assertThat(row.substring(CardRecord.CARD_CVV_CD_OFFSET,
                    CardRecord.CARD_CVV_CD_OFFSET + CardRecord.CARD_CVV_CD_LENGTH))
                    .as("the stored image keeps its leading zero even though the value is 28")
                    .isEqualTo("028");
            assertThat(record.cardEmbossedName()).isEqualTo(
                    expectedPadded("Enrico Rosenbaum", CardRecord.CARD_EMBOSSED_NAME_LENGTH));
            assertThat(record.cardExpiraionDate()).isEqualTo("2024-08-11");
        }

        @ParameterizedTest(name = "row {0} re-encodes byte-identically")
        @MethodSource("com.vsergeychik.carddemo.card.model.CardRecordTest#everyFixtureRow")
        @DisplayName("Every one of the 50 rows survives decode then encode byte-identically")
        void everyRowRoundTripsByteIdentically(int rowNumber, String row) {
            byte[] stored = row.getBytes(ASCII);

            byte[] reEncoded = CardRecord.decode(stored, ASCII).encode(ASCII);

            assertThat(reEncoded)
                    .as("row %d must re-encode to exactly the bytes it was decoded from", rowNumber)
                    .hasSize(CardRecord.RECORD_LENGTH)
                    .isEqualTo(stored);
        }

        @Test
        @DisplayName("Every row carries CARD-ACTIVE-STATUS 'Y' - why 'N' has to be synthesised")
        void everyFixtureRowIsActive() {
            for (String row : fixtureRows()) {
                assertThat(CardRecord.decodeImage(row, ASCII).cardActiveStatus())
                        .as("all fifty rows are active, which is exactly why the ActiveStatus group "
                                + "synthesises 'N' and a space instead of relying on the fixture")
                        .isEqualTo("Y");
            }
        }

        @Test
        @DisplayName("Every row ends with 59 spaces, confirming the FILLER in the real data")
        void everyFixtureRowEndsWithFiftyNineSpaces() {
            for (String row : fixtureRows()) {
                assertThat(row.substring(CardRecord.FILLER_OFFSET))
                        .as("the authoritative data itself space-fills the reserved span")
                        .isEqualTo(SPACE.repeat(CardRecord.FILLER_LENGTH));
            }
        }

        @Test
        @DisplayName("Exactly 5 of the 50 rows carry a leading zero - the measured evidence")
        void fiveFixtureRowsCarryALeadingZero() {
            int leadingZeroRows = 0;
            for (String row : fixtureRows()) {
                String cardNumber = CardRecord.decodeImage(row, ASCII).cardNum();
                assertThat(cardNumber)
                        .as("every CARD-NUM occupies its full declared 16 characters")
                        .hasSize(CardRecord.CARD_NUM_LENGTH);
                if (cardNumber.startsWith("0")) {
                    leadingZeroRows++;
                }
            }

            assertThat(leadingZeroRows)
                    .as("Re-measured from app/data/ASCII/carddata.txt: five of the fifty card "
                            + "numbers begin with a zero. That is the whole argument for CARD-NUM "
                            + "being a String - a numeric type would silently renumber one row in "
                            + "ten and break every keyed read against CARDDAT for it.")
                    .isEqualTo(5);
        }

        @Test
        @DisplayName("encodeToImage reproduces the stored row image character for character")
        void encodeToImageReproducesTheStoredRow() {
            String row = fixtureRows().get(0);

            assertThat(CardRecord.decodeImage(row, ASCII).encodeToImage(ASCII))
                    .as("the character image and the byte image must never disagree about width")
                    .isEqualTo(row)
                    .hasSize(CardRecord.RECORD_LENGTH);
        }
    }

    @Nested
    @DisplayName("The code page is always named and always honoured, never a platform default")
    class CharsetIsExplicit {
        @Test
        @DisplayName("IBM037 is available in this JDK, asserted rather than assumed away")
        void ibm037IsAvailable() {
            assertThat(Charset.isSupported("IBM037"))
                    .as("IBM037 ships in the JDK's jdk.charsets module and is required to encode the "
                            + "EBCDIC datasets under app/data/EBCDIC")
                    .isTrue();
            assertThat(EBCDIC.name()).isEqualTo("IBM037");
        }

        @Test
        @DisplayName("The same record encodes to DIFFERENT bytes under US-ASCII and IBM037")
        void theTwoCodePagesProduceDifferentImages() {
            CardRecord record = row1();

            byte[] ascii = record.encode(ASCII);
            byte[] ebcdic = record.encode(EBCDIC);

            assertThat(ascii).hasSize(CardRecord.RECORD_LENGTH);
            assertThat(ebcdic).hasSize(CardRecord.RECORD_LENGTH);
            assertThat(ebcdic)
                    .as("if the charset parameter were ignored, or a platform default used, these "
                            + "two images would be identical and this assertion would collapse")
                    .isNotEqualTo(ascii);
            assertThat(ascii[CardRecord.CARD_ACTIVE_STATUS_OFFSET])
                    .as("'Y' is 0x59 in ASCII")
                    .isEqualTo((byte) 0x59);
            assertThat(ebcdic[CardRecord.CARD_ACTIVE_STATUS_OFFSET])
                    .as("'Y' is 0xE8 in IBM037")
                    .isEqualTo((byte) 0xE8);
        }

        @Test
        @DisplayName("Each image is internally self-consistent under its own code page")
        void eachImageIsSelfConsistentUnderItsOwnCodePage() {
            CardRecord record = row1();

            CardRecord viaAscii = CardRecord.decode(record.encode(ASCII), ASCII);
            CardRecord viaEbcdic = CardRecord.decode(record.encode(EBCDIC), EBCDIC);

            assertThat(viaAscii)
                    .as("US-ASCII in, US-ASCII out, and every field back exactly as it went in")
                    .isEqualTo(record);
            assertThat(viaEbcdic)
                    .as("IBM037 in, IBM037 out - the same field values from entirely different bytes")
                    .isEqualTo(record);
            assertThat(viaEbcdic)
                    .as("the two code pages agree on the field values even though their bytes differ")
                    .isEqualTo(viaAscii);
        }

        @Test
        @DisplayName("Decoding an EBCDIC image as US-ASCII does not silently yield the right fields")
        void decodingAnEbcdicImageAsAsciiDoesNotSucceedSilently() {
            byte[] ebcdicImage = row1().encode(EBCDIC);

            assertThatExceptionOfType(IllegalStateException.class)
                    .as("a code-page mismatch must fail loudly, never decode to a plausible value")
                    .isThrownBy(() -> CardRecord.decode(ebcdicImage, ASCII))
                    .withMessageContaining("not valid code page US-ASCII data");
        }

        @Test
        @DisplayName("A null charset or codec is refused at every byte-boundary entry point")
        void nullCodePagesAreRefused() {
            CardRecord record = row1();
            byte[] image = record.encode(ASCII);

            assertThatNullPointerException().isThrownBy(() -> record.encode((Charset) null));
            assertThatNullPointerException().isThrownBy(() -> record.encode((FixedWidthCodec) null));
            assertThatNullPointerException().isThrownBy(() -> record.encodeToImage(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> record.toFixedWidthRecord(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> CardRecord.decode(image, (FixedWidthCodec) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> CardRecord.decodeImage("x", null));
        }

        @Test
        @DisplayName("The codec-taking overloads agree with the charset-taking ones")
        void codecAndCharsetOverloadsAgree() {
            CardRecord record = row1();

            assertThat(record.encode(asciiCodec)).isEqualTo(record.encode(ASCII));
            assertThat(record.encode(ebcdicCodec)).isEqualTo(record.encode(EBCDIC));
            assertThat(CardRecord.decode(record.encode(ASCII), asciiCodec)).isEqualTo(record);
            assertThat(asciiCodec.charset()).isEqualTo(ASCII);
            assertThat(ebcdicCodec.charset()).isEqualTo(EBCDIC);
        }
    }

    @Nested
    @DisplayName("Construction bounds - every PICTURE bound enforced, both sides of every branch")
    class ConstructionBounds {
        @ParameterizedTest(name = "character component {0} rejects an over-wide value")
        @ValueSource(ints = {0, 1, 2, 3})
        @DisplayName("An over-wide PIC X value is rejected, never silently truncated")
        void anOverWidePicXValueIsRejected(int componentIndex) {
            assertThatIllegalArgumentException()
                    .as("the canonical constructor never truncates: COBOL truncates a PIC X receiver "
                            + "on the right and a PIC 9 receiver on the left, so the direction has "
                            + "to be chosen deliberately through moving(...)")
                    .isThrownBy(() -> overFillCharacterComponent(componentIndex))
                    .withMessageContaining("moving");
        }

        private CardRecord overFillCharacterComponent(int componentIndex) {
            return switch (componentIndex) {
                case 0 -> new CardRecord("X".repeat(CardRecord.CARD_NUM_LENGTH + 1), ROW_1_ACCT_ID,
                        ROW_1_CVV, ROW_1_NAME, ROW_1_EXPIRY, ROW_1_STATUS);
                case 1 -> new CardRecord(ROW_1_CARD_NUM, ROW_1_ACCT_ID, ROW_1_CVV,
                        "X".repeat(CardRecord.CARD_EMBOSSED_NAME_LENGTH + 1), ROW_1_EXPIRY,
                        ROW_1_STATUS);
                case 2 -> new CardRecord(ROW_1_CARD_NUM, ROW_1_ACCT_ID, ROW_1_CVV, ROW_1_NAME,
                        "X".repeat(CardRecord.CARD_EXPIRAION_DATE_LENGTH + 1), ROW_1_STATUS);
                default -> new CardRecord(ROW_1_CARD_NUM, ROW_1_ACCT_ID, ROW_1_CVV, ROW_1_NAME,
                        ROW_1_EXPIRY, "X".repeat(CardRecord.CARD_ACTIVE_STATUS_LENGTH + 1));
            };
        }

        @Test
        @DisplayName("A PIC X value of exactly its declared width is accepted unchanged")
        void anExactlyWidePicXValueIsAcceptedUnchanged() {
            CardRecord record = new CardRecord(
                    "1".repeat(CardRecord.CARD_NUM_LENGTH),
                    ROW_1_ACCT_ID,
                    ROW_1_CVV,
                    "N".repeat(CardRecord.CARD_EMBOSSED_NAME_LENGTH),
                    "2023-03-09",
                    "Y");

            assertThat(record.cardNum()).isEqualTo("1".repeat(CardRecord.CARD_NUM_LENGTH));
            assertThat(record.cardEmbossedName())
                    .isEqualTo("N".repeat(CardRecord.CARD_EMBOSSED_NAME_LENGTH));
            assertThat(record.cardExpiraionDate()).isEqualTo("2023-03-09");
            assertThat(record.cardActiveStatus()).isEqualTo("Y");
        }

        @Test
        @DisplayName("A PIC X value shorter than its width is padded, not rejected")
        void aShorterPicXValueIsPadded() {
            CardRecord record = new CardRecord("0500", ROW_1_ACCT_ID, ROW_1_CVV, "Jo", "2023",
                    ROW_1_STATUS);

            assertThat(record.cardNum())
                    .isEqualTo(expectedPadded("0500", CardRecord.CARD_NUM_LENGTH));
            assertThat(record.cardEmbossedName())
                    .isEqualTo(expectedPadded("Jo", CardRecord.CARD_EMBOSSED_NAME_LENGTH));
            assertThat(record.cardExpiraionDate())
                    .isEqualTo(expectedPadded("2023", CardRecord.CARD_EXPIRAION_DATE_LENGTH));
        }

        @Test
        @DisplayName("A null character component is rejected, naming the empty-string alternative")
        void aNullCharacterComponentIsRejected() {
            assertThatNullPointerException()
                    .as("COBOL has no null; blanking a field is MOVE SPACES, i.e. an empty string")
                    .isThrownBy(() -> new CardRecord(null, ROW_1_ACCT_ID, ROW_1_CVV, ROW_1_NAME,
                            ROW_1_EXPIRY, ROW_1_STATUS));
            assertThatNullPointerException()
                    .isThrownBy(() -> new CardRecord(ROW_1_CARD_NUM, ROW_1_ACCT_ID, ROW_1_CVV, null,
                            ROW_1_EXPIRY, ROW_1_STATUS));
            assertThatNullPointerException()
                    .isThrownBy(() -> new CardRecord(ROW_1_CARD_NUM, ROW_1_ACCT_ID, ROW_1_CVV,
                            ROW_1_NAME, null, ROW_1_STATUS));
            assertThatNullPointerException()
                    .isThrownBy(() -> new CardRecord(ROW_1_CARD_NUM, ROW_1_ACCT_ID, ROW_1_CVV,
                            ROW_1_NAME, ROW_1_EXPIRY, null));
        }

        @ParameterizedTest(name = "account id {0} is rejected - PIC 9 is unsigned")
        @ValueSource(longs = {-1L, -50L, -100_000_000_000L, Long.MIN_VALUE})
        @DisplayName("A negative CARD-ACCT-ID is rejected - PIC 9 has no sign position")
        void aNegativeAcctIdIsRejected(long negative) {
            assertThatIllegalArgumentException()
                    .as("CVACT02Y:6 declares PIC 9(11), an unsigned picture")
                    .isThrownBy(() -> new CardRecord(ROW_1_CARD_NUM, negative, ROW_1_CVV, ROW_1_NAME,
                            ROW_1_EXPIRY, ROW_1_STATUS))
                    .withMessageContaining("unsigned");
        }

        @Test
        @DisplayName("A negative CARD-CVV-CD is rejected for the same reason")
        void aNegativeCvvIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new CardRecord(ROW_1_CARD_NUM, ROW_1_ACCT_ID, -1, ROW_1_NAME,
                            ROW_1_EXPIRY, ROW_1_STATUS))
                    .withMessageContaining("unsigned");
        }

        @Test
        @DisplayName("A CARD-ACCT-ID with more than 11 digits is rejected")
        void anOverWideAcctIdIsRejected() {
            assertThatIllegalArgumentException()
                    .as("PIC 9(11) holds eleven digits, so the value must be below 100000000000")
                    .isThrownBy(() -> new CardRecord(ROW_1_CARD_NUM,
                            CardRecord.CARD_ACCT_ID_EXCLUSIVE_LIMIT, ROW_1_CVV, ROW_1_NAME,
                            ROW_1_EXPIRY, ROW_1_STATUS))
                    .withMessageContaining("11")
                    .withMessageContaining("moving");
        }

        @Test
        @DisplayName("A CARD-CVV-CD with more than 3 digits is rejected")
        void anOverWideCvvIsRejected() {
            assertThatIllegalArgumentException()
                    .as("PIC 9(03) holds three digits, so the value must be below 1000")
                    .isThrownBy(() -> new CardRecord(ROW_1_CARD_NUM, ROW_1_ACCT_ID,
                            CardRecord.CARD_CVV_CD_EXCLUSIVE_LIMIT, ROW_1_NAME, ROW_1_EXPIRY,
                            ROW_1_STATUS))
                    .withMessageContaining("3");
        }

        @ParameterizedTest(name = "account id {0} and CVV {1} are accepted")
        @CsvSource({
                "0,           0",
                "1,           1",
                "50,        747",
                "99999999999, 999"
        })
        @DisplayName("Numeric values inside their declared digit counts are accepted")
        void inRangeNumericValuesAreAccepted(long acctId, int cvv) {
            CardRecord record = new CardRecord(ROW_1_CARD_NUM, acctId, cvv, ROW_1_NAME, ROW_1_EXPIRY,
                    ROW_1_STATUS);

            assertThat(record.cardAcctId()).isEqualTo(acctId);
            assertThat(record.cardCvvCd()).isEqualTo(cvv);
            assertThat(record.cardAcctIdImage(asciiCodec))
                    .hasSize(CardRecord.CARD_ACCT_ID_LENGTH);
        }

        @Test
        @DisplayName("The largest representable values round-trip through their full-width spans")
        void largestRepresentableValuesRoundTrip() {
            CardRecord record = new CardRecord(ROW_1_CARD_NUM,
                    CardRecord.CARD_ACCT_ID_EXCLUSIVE_LIMIT - 1,
                    CardRecord.CARD_CVV_CD_EXCLUSIVE_LIMIT - 1,
                    ROW_1_NAME, ROW_1_EXPIRY, ROW_1_STATUS);

            CardRecord decoded = CardRecord.decode(record.encode(ASCII), ASCII);

            assertThat(decoded.cardAcctId()).isEqualTo(99_999_999_999L);
            assertThat(decoded.cardCvvCd()).isEqualTo(999);
            assertThat(decoded.cardAcctIdImage(asciiCodec)).isEqualTo("99999999999");
        }

        @Test
        @DisplayName("moving(...) refuses a null codec rather than silently choosing a code page")
        void movingRefusesANullCodec() {
            assertThatNullPointerException()
                    .isThrownBy(() -> CardRecord.moving(ROW_1_CARD_NUM, ROW_1_ACCT_ID, ROW_1_CVV,
                            ROW_1_NAME, ROW_1_EXPIRY, ROW_1_STATUS, null));
        }

        @Test
        @DisplayName("moving(...) still refuses a negative numeric - truncation is not sign coercion")
        void movingStillRefusesANegativeNumeric() {
            assertThatIllegalArgumentException()
                    .as("PIC 9 truncates width, but it has no sign position to truncate into")
                    .isThrownBy(() -> CardRecord.moving(ROW_1_CARD_NUM, -1L, ROW_1_CVV, ROW_1_NAME,
                            ROW_1_EXPIRY, ROW_1_STATUS, asciiCodec));
        }

        @Test
        @DisplayName("A non-digit in a stored PIC 9 span fails loudly, never decodes as zero")
        void aNonDigitInANumericSpanFailsLoudly() {
            char[] image = row1().encodeToImage(ASCII).toCharArray();
            image[CardRecord.CARD_ACCT_ID_OFFSET] = 'X';
            String corrupted = new String(image);

            assertThatIllegalArgumentException()
                    .as("silently yielding zero would hide a misaligned row behind a plausible value")
                    .isThrownBy(() -> CardRecord.decodeImage(corrupted, ASCII))
                    .withMessageContaining("CARD-ACCT-ID");
        }
    }

    @Nested
    @DisplayName("Value semantics - what 9300-CHECK-CHANGE-IN-REC and the parity differ rely on")
    class ValueSemantics {
        @Test
        @DisplayName("Two records with the same six components are equal and share a hash code")
        void identicalRecordsAreEqual() {
            CardRecord first = row1();
            CardRecord second = row1();

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
            assertThat(second).isEqualTo(first);
            assertThat(first).isEqualTo(first);
        }

        @Test
        @DisplayName("Padding is part of equality: a padded value equals its unpadded original")
        void paddingIsNormalisedBeforeComparison() {
            CardRecord unpadded = row1();
            CardRecord explicitlyPadded = new CardRecord(ROW_1_CARD_NUM, ROW_1_ACCT_ID, ROW_1_CVV,
                    expectedPadded(ROW_1_NAME, CardRecord.CARD_EMBOSSED_NAME_LENGTH), ROW_1_EXPIRY,
                    ROW_1_STATUS);

            assertThat(explicitlyPadded)
                    .as("the constructor pads on the way in, so both forms denote the same record")
                    .isEqualTo(unpadded)
                    .hasSameHashCodeAs(unpadded);
        }

        @ParameterizedTest(name = "differing only in component {0} makes the records unequal")
        @ValueSource(ints = {0, 1, 2, 3, 4, 5})
        @DisplayName("Differing in any single component breaks equality")
        void differingInAnySingleComponentBreaksEquality(int componentIndex) {
            CardRecord baseline = row1();

            CardRecord varied = switch (componentIndex) {
                case 0 -> baseline.withCardNum(ROW_2_CARD_NUM);
                case 1 -> baseline.withCardAcctId(ROW_1_ACCT_ID + 1);
                case 2 -> baseline.withCardCvvCd(ROW_1_CVV + 1);
                case 3 -> baseline.withCardEmbossedName("Ward Jones");
                case 4 -> baseline.withCardExpiraionDate("2025-07-13");
                default -> baseline.withCardActiveStatus("N");
            };

            assertThat(varied)
                    .as("component %d must participate in equality, or a real change would be "
                            + "invisible to 9300-CHECK-CHANGE-IN-REC", componentIndex)
                    .isNotEqualTo(baseline);
            assertThat(varied.encode(ASCII))
                    .as("and the stored image must differ too")
                    .isNotEqualTo(baseline.encode(ASCII));
        }

        @Test
        @DisplayName("A record equals neither null nor an instance of another class")
        void aRecordEqualsNeitherNullNorAnotherType() {
            CardRecord record = row1();

            assertThat(record).isNotEqualTo(null);
            assertThat(record).isNotEqualTo(ROW_1_CARD_NUM);
            assertThat(record).isNotEqualTo(CardRecord.initialised());
            assertThat(record.equals(new Object()))
                    .as("an object of a different class is never equal")
                    .isFalse();
        }

        @Test
        @DisplayName("Every copy-with accessor changes only its own component")
        void copyWithAccessorsChangeOnlyTheirOwnComponent() {
            CardRecord baseline = row1();

            assertThat(baseline.withCardNum(ROW_2_CARD_NUM).cardAcctId())
                    .isEqualTo(baseline.cardAcctId());
            assertThat(baseline.withCardAcctId(99L).cardNum()).isEqualTo(baseline.cardNum());
            assertThat(baseline.withCardCvvCd(1).cardEmbossedName())
                    .isEqualTo(baseline.cardEmbossedName());
            assertThat(baseline.withCardEmbossedName("Ward Jones").cardExpiraionDate())
                    .isEqualTo(baseline.cardExpiraionDate());
            assertThat(baseline.withCardExpiraionDate("2025-07-13").cardActiveStatus())
                    .isEqualTo(baseline.cardActiveStatus());
            assertThat(baseline.withCardActiveStatus("N").cardCvvCd())
                    .isEqualTo(baseline.cardCvvCd());
        }

        @Test
        @DisplayName("A copy-with accessor leaves the original untouched")
        void copyWithLeavesTheOriginalUntouched() {
            CardRecord baseline = row1();

            CardRecord copy = baseline.withCardActiveStatus("N");

            assertThat(baseline.cardActiveStatus())
                    .as("the record is immutable, so the original cannot have changed")
                    .isEqualTo("Y");
            assertThat(copy.cardActiveStatus()).isEqualTo("N");
        }

        @Test
        @DisplayName("The diagnostic rendering names all six copybook fields and the reserved span")
        void theDiagnosticRenderingNamesEveryField() {
            String rendering = row1().toString();

            assertThat(rendering)
                    .as("a failing parity case has to be readable without a debugger")
                    .isNotNull()
                    .contains("CARD-RECORD")
                    .contains("150")
                    .contains("CARD-NUM")
                    .contains("CARD-ACCT-ID")
                    .contains("CARD-CVV-CD")
                    .contains("CARD-EMBOSSED-NAME")
                    .contains("CARD-EXPIRAION-DATE")
                    .contains("CARD-ACTIVE-STATUS")
                    .contains("FILLER");
            assertThat(rendering)
                    .as("the PAN, the account key, the CVV and the embossed name are all withheld: "
                            + "rendered together they are a usable card credential (CWE-532)")
                    .doesNotContain(ROW_1_CARD_NUM)
                    .contains("CARD-NUM='" + "*".repeat(CardRecord.CARD_NUM_LENGTH - 4)
                            + ROW_1_CARD_NUM.substring(ROW_1_CARD_NUM.length() - 4) + "'")
                    .doesNotContain(ROW_1_NAME)
                    .contains("CARD-CVV-CD=[redacted]");
        }

        @Test
        @DisplayName("No stored byte can forge a second log line out of this rendering")
        void theDiagnosticRenderingCannotBeUsedToForgeALogLine() {
            CardRecord forged = new CardRecord("411111111111\r\nOK", 1L, 123, "A\rB",
                    "2025-\n1-01", "\n");

            String rendering = forged.toString();
            assertThat(rendering)
                    .doesNotContain("\r")
                    .doesNotContain("\n")
                    .contains("X'0D'", "X'0A'");
            assertThat(rendering.lines())
                    .as("a rendering documented as safe to log must occupy exactly one line")
                    .hasSize(1);
            assertThat(forged.cardNum()).isEqualTo("411111111111\r\nOK");
            assertThat(forged.cardExpiraionDate()).isEqualTo("2025-\n1-01");
            assertThat(forged.cardActiveStatus()).isEqualTo("\n");
        }
    }

    @Nested
    @DisplayName("Structural guards - no floating point, no persistence mapping (G22, G44)")
    class StructuralGuards {
        @Test
        @DisplayName("No declared field is double, float, Double or Float (G22)")
        void noDeclaredFieldIsFloatingPoint() {
            List<String> offenders = new ArrayList<>();
            for (Field field : CardRecord.class.getDeclaredFields()) {
                if (isFloatingPoint(field.getType())) {
                    offenders.add(field.getName() + " : " + field.getType().getName());
                }
            }

            assertThat(offenders)
                    .as("CVACT02Y declares zero signed or V-scaled pictures, so nothing here is even "
                            + "a candidate for a decimal type - let alone a binary floating-point one, "
                            + "which cannot represent a decimal fraction exactly")
                    .isEmpty();
        }

        @Test
        @DisplayName("No accessor or method returns double, float, Double or Float (G22)")
        void noMethodReturnsFloatingPoint() {
            List<String> offenders = new ArrayList<>();
            for (Method method : CardRecord.class.getDeclaredMethods()) {
                if (isFloatingPoint(method.getReturnType())) {
                    offenders.add(method.getName() + " -> " + method.getReturnType().getName());
                }
            }

            assertThat(offenders)
                    .as("no accessor may hand a monetary or identifier value out as floating point")
                    .isEmpty();
        }

        @Test
        @DisplayName("No record component is a floating-point or arbitrary-precision decimal type")
        void noRecordComponentIsADecimalType() {
            List<String> offenders = new ArrayList<>();
            for (RecordComponent component : CardRecord.class.getRecordComponents()) {
                Class<?> type = component.getType();
                if (isFloatingPoint(type) || "java.math.BigDecimal".equals(type.getName())) {
                    offenders.add(component.getName() + " : " + type.getName());
                }
            }

            assertThat(offenders)
                    .as("this copybook has no scaled decimal at all, so the module's fixed-point "
                            + "helper is deliberately absent from this type's dependency graph. A "
                            + "BigDecimal here would signal that a scale had been invented.")
                    .isEmpty();
            assertThat(CardRecord.class.getRecordComponents())
                    .as("CVACT02Y declares six referable fields; FILLER is not one of them")
                    .hasSize(6)
                    .extracting(RecordComponent::getName)
                    .containsExactly("cardNum", "cardAcctId", "cardCvvCd", "cardEmbossedName",
                            "cardExpiraionDate", "cardActiveStatus");
        }

        private boolean isFloatingPoint(Class<?> type) {
            return double.class.equals(type)
                    || float.class.equals(type)
                    || Double.class.equals(type)
                    || Float.class.equals(type);
        }

        @Test
        @DisplayName("The type carries no persistence annotation of any kind (G44)")
        void theTypeCarriesNoPersistenceAnnotation() {
            List<String> offenders = new ArrayList<>();
            collectPersistenceAnnotations(CardRecord.class.getAnnotations(), "the class", offenders);
            for (Field field : CardRecord.class.getDeclaredFields()) {
                collectPersistenceAnnotations(field.getAnnotations(), "field " + field.getName(),
                        offenders);
            }
            for (Method method : CardRecord.class.getDeclaredMethods()) {
                collectPersistenceAnnotations(method.getAnnotations(), "method " + method.getName(),
                        offenders);
            }

            assertThat(offenders)
                    .as("the migration reaches the existing datasets over JDBC with no schema change, "
                            + "so there is no entity mapping, no table, no column and no DDL anywhere")
                    .isEmpty();
        }

        private void collectPersistenceAnnotations(Annotation[] annotations, String location,
                                                   List<String> offenders) {
            for (Annotation annotation : annotations) {
                String name = annotation.annotationType().getName();
                if (name.startsWith("jakarta.persistence.")
                        || name.startsWith("javax.persistence.")) {
                    offenders.add(location + " carries " + name);
                }
            }
        }

        @Test
        @DisplayName("No optimistic-lock or version column field exists (G44)")
        void noVersionColumnFieldExists() {
            List<String> offenders = new ArrayList<>();
            for (Field field : CardRecord.class.getDeclaredFields()) {
                String name = field.getName().toLowerCase();
                if (name.contains("version") || name.contains("optlock")
                        || name.contains("rowversion")) {
                    offenders.add(field.getName());
                }
            }

            assertThat(offenders)
                    .as("COCRDUPC's 9300-CHECK-CHANGE-IN-REC does optimistic concurrency by re-reading "
                            + "and comparing the record field by field. Adding a version column would "
                            + "be a schema change, which is forbidden.")
                    .isEmpty();
        }

        @Test
        @DisplayName("Every static member of CardRecord is final, so there is no mutable state")
        void everyStaticMemberIsFinal() {
            List<String> offenders = new ArrayList<>();
            for (Field field : CardRecord.class.getDeclaredFields()) {
                boolean isStatic = Modifier.isStatic(field.getModifiers());
                boolean isFinal = Modifier.isFinal(field.getModifiers());
                if (isStatic && !isFinal) {
                    offenders.add(field.getName());
                }
            }

            assertThat(offenders)
                    .as("COBOL WORKING-STORAGE must never become static Java state: it would break "
                            + "request isolation and make tests order-dependent")
                    .isEmpty();
        }

        @Test
        @DisplayName("The record is immutable - every instance field is private and final")
        void everyInstanceFieldIsPrivateAndFinal() {
            for (Field field : CardRecord.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("instance field %s must be final", field.getName())
                            .isTrue();
                    assertThat(Modifier.isPrivate(field.getModifiers()))
                            .as("instance field %s must be private", field.getName())
                            .isTrue();
                }
            }
        }

        @Test
        @DisplayName("The declared record length is the single source of truth for the width")
        void theDeclaredRecordLengthIsTheSingleSourceOfTruth() {
            assertThat(CardRecord.RECORD_LENGTH)
                    .isEqualTo(CardRecord.LAYOUT.recordLength())
                    .isEqualTo(row1().encode(ASCII).length)
                    .isEqualTo(row1().encode(EBCDIC).length)
                    .isEqualTo(row1().encodeToImage(ASCII).length())
                    .isEqualTo(row1().toFixedWidthRecord(asciiCodec).recordLength())
                    .isEqualTo(150);
        }
    }
}
