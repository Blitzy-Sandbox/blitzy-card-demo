package com.vsergeychik.carddemo.user.model;

import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;

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
import java.lang.reflect.RecordComponent;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link SecUserRecord}, the one Java type for copybook {@code app/cpy/CSUSR01Y.cpy} and the
 * 80-byte {@code USRSEC} security-user record.
 */
class SecUserRecordTest {
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    private static final Charset EBCDIC = Charset.forName("IBM037");

    private static final byte ASCII_SPACE = (byte) 0x20;

    private static final byte EBCDIC_SPACE = (byte) 0x40;

    private static final byte LOW_VALUE = (byte) 0x00;

    private static final byte HIGH_VALUE = (byte) 0xFF;

    private static final int RECORD_WIDTH = 80;

    private static final int CARD_WIDTH = 57;

    private static final int FILLER_WIDTH = 23;

    private static final String SEED_PASSWORD = "PASSWORD";

    private static final List<String> SEED_CARDS = List.of(
            "ADMIN001MARGARET            GOLD                PASSWORDA",
            "ADMIN002RUSSELL             RUSSELL             PASSWORDA",
            "ADMIN003RAYMOND             WHITMORE            PASSWORDA",
            "ADMIN004EMMANUEL            CASGRAIN            PASSWORDA",
            "ADMIN005GRANVILLE           LACHAPELLE          PASSWORDA",
            "USER0001LAWRENCE            THOMAS              PASSWORDU",
            "USER0002AJITH               KUMAR               PASSWORDU",
            "USER0003LAURITZ             ALME                PASSWORDU",
            "USER0004AVERARDO            MAZZI               PASSWORDU",
            "USER0005LEE                 TING                PASSWORDU");

    private static String pad(String value, int width) {
        return value + " ".repeat(width - value.length());
    }

    private static String spaces(int count) {
        return " ".repeat(count);
    }

    private static String recordImage(String card) {
        return card + spaces(FILLER_WIDTH);
    }

    private static byte[] recordBytes(String card, Charset charset) {
        return recordImage(card).getBytes(charset);
    }

    private static byte[] repeatByte(byte value, int count) {
        byte[] bytes = new byte[count];
        Arrays.fill(bytes, value);
        return bytes;
    }

    private static SecUserRecord seedRecord(int index, Charset charset) {
        return SecUserRecord.decode(recordBytes(SEED_CARDS.get(index), charset), charset);
    }

    private static SecUserRecord fieldSetTo(String cobolName, String value) {
        FixedWidthCodec codec = new FixedWidthCodec(ASCII);
        return switch (cobolName) {
            case "SEC-USR-ID" -> SecUserRecord.of(value, "", "", "", "", "", codec);
            case "SEC-USR-FNAME" -> SecUserRecord.of("", value, "", "", "", "", codec);
            case "SEC-USR-LNAME" -> SecUserRecord.of("", "", value, "", "", "", codec);
            case "SEC-USR-PWD" -> SecUserRecord.of("", "", "", value, "", "", codec);
            case "SEC-USR-TYPE" -> SecUserRecord.of("", "", "", "", value, "", codec);
            case "SEC-USR-FILLER" -> SecUserRecord.of("", "", "", "", "", value, codec);
            default -> throw new IllegalArgumentException(
                    "'" + cobolName + "' is not one of the six CSUSR01Y.cpy:18-23 field names");
        };
    }

    private static byte[] sentinelKeyRow(byte sentinel, Charset charset) {
        byte[] row = recordBytes(SEED_CARDS.get(0), charset);
        for (int index = 0; index < 8; index++) {
            row[index] = sentinel;
        }
        return row;
    }

    private static Set<Class<?>> declaredTypesOf(Class<?> type) {
        Set<Class<?>> types = new LinkedHashSet<>();
        for (Field field : type.getDeclaredFields()) {
            types.add(field.getType());
        }
        for (Method method : type.getDeclaredMethods()) {
            types.add(method.getReturnType());
            types.addAll(Arrays.asList(method.getParameterTypes()));
        }
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            types.addAll(Arrays.asList(constructor.getParameterTypes()));
        }
        RecordComponent[] components = type.getRecordComponents();
        if (components != null) {
            for (RecordComponent component : components) {
                types.add(component.getType());
            }
        }
        return types;
    }

    @Nested
    @DisplayName("Layout and constants, transcribed from CSUSR01Y.cpy:17-23")
    class LayoutAndConstants {
        @Test
        @DisplayName("RECORD_LENGTH is the literal 80, and equals the sum of the six field widths")
        void recordLength_is80_andEqualsTheSumOfTheSixDeclaredWidths() {
            assertThat(SecUserRecord.RECORD_LENGTH).isEqualTo(80);

            assertThat(8 + 20 + 20 + 8 + 1 + 23).isEqualTo(80);

            assertThat(SecUserRecord.SEC_USR_ID_LENGTH
                    + SecUserRecord.SEC_USR_FNAME_LENGTH
                    + SecUserRecord.SEC_USR_LNAME_LENGTH
                    + SecUserRecord.SEC_USR_PWD_LENGTH
                    + SecUserRecord.SEC_USR_TYPE_LENGTH
                    + SecUserRecord.SEC_USR_FILLER_LENGTH)
                    .as("the six published widths must themselves total the published record length")
                    .isEqualTo(SecUserRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("all twelve published offset and width constants are the copybook literals")
        void perFieldOffsetsAndLengths_areTheCopybookLiterals() {
            assertThat(SecUserRecord.SEC_USR_ID_OFFSET).isEqualTo(0);
            assertThat(SecUserRecord.SEC_USR_ID_LENGTH).isEqualTo(8);
            assertThat(SecUserRecord.SEC_USR_FNAME_OFFSET).isEqualTo(8);
            assertThat(SecUserRecord.SEC_USR_FNAME_LENGTH).isEqualTo(20);
            assertThat(SecUserRecord.SEC_USR_LNAME_OFFSET).isEqualTo(28);
            assertThat(SecUserRecord.SEC_USR_LNAME_LENGTH).isEqualTo(20);
            assertThat(SecUserRecord.SEC_USR_PWD_OFFSET).isEqualTo(48);
            assertThat(SecUserRecord.SEC_USR_PWD_LENGTH).isEqualTo(8);
            assertThat(SecUserRecord.SEC_USR_TYPE_OFFSET).isEqualTo(56);
            assertThat(SecUserRecord.SEC_USR_TYPE_LENGTH).isEqualTo(1);
            assertThat(SecUserRecord.SEC_USR_FILLER_OFFSET).isEqualTo(57);
            assertThat(SecUserRecord.SEC_USR_FILLER_LENGTH).isEqualTo(23);
        }

        @Test
        @DisplayName("KEY_OFFSET is 0 and KEY_LENGTH is 8, per KEYS(8,0), RKP 0 and KEYLEN 8")
        void keyGeometry_is8BytesAtOffset0_asFourWitnessesAgree() {
            assertThat(SecUserRecord.KEY_OFFSET).isEqualTo(0);
            assertThat(SecUserRecord.KEY_LENGTH).isEqualTo(8);

            assertThat(SecUserRecord.KEY_OFFSET).isEqualTo(SecUserRecord.SEC_USR_ID_OFFSET);
            assertThat(SecUserRecord.KEY_LENGTH).isEqualTo(SecUserRecord.SEC_USR_ID_LENGTH);
        }

        @Test
        @DisplayName("COBOL names are verbatim, group/field spelling asymmetry included")
        void cobolNames_arePreservedVerbatim_includingTheGroupFieldAsymmetry() {
            assertThat(SecUserRecord.GROUP_NAME).isEqualTo("SEC-USER-DATA");
            assertThat(SecUserRecord.FIELD_SEC_USR_ID).isEqualTo("SEC-USR-ID");
            assertThat(SecUserRecord.FIELD_SEC_USR_FNAME).isEqualTo("SEC-USR-FNAME");
            assertThat(SecUserRecord.FIELD_SEC_USR_LNAME).isEqualTo("SEC-USR-LNAME");
            assertThat(SecUserRecord.FIELD_SEC_USR_PWD).isEqualTo("SEC-USR-PWD");
            assertThat(SecUserRecord.FIELD_SEC_USR_TYPE).isEqualTo("SEC-USR-TYPE");
            assertThat(SecUserRecord.FIELD_SEC_USR_FILLER).isEqualTo("SEC-USR-FILLER");

            assertThat(SecUserRecord.GROUP_NAME).contains("USER").doesNotContain("SEC-USR-");
            assertThat(List.of(SecUserRecord.FIELD_SEC_USR_ID,
                            SecUserRecord.FIELD_SEC_USR_FNAME,
                            SecUserRecord.FIELD_SEC_USR_LNAME,
                            SecUserRecord.FIELD_SEC_USR_PWD,
                            SecUserRecord.FIELD_SEC_USR_TYPE,
                            SecUserRecord.FIELD_SEC_USR_FILLER))
                    .allSatisfy(name -> assertThat(name).startsWith("SEC-USR-"));
        }

        @Test
        @DisplayName("one fixed width only, despite the CSD's RECORDFORMAT(V)")
        void recordWidth_isASingleFixed80_notAVariableLength() {
            assertThat(SecUserRecord.RECORD_LENGTH).isEqualTo(RECORD_WIDTH);
            assertThat(SecUserRecord.LAYOUT.recordLength()).isEqualTo(RECORD_WIDTH);

            assertThat(SecUserRecord.encode(SecUserRecord.blank(), ASCII)).hasSize(RECORD_WIDTH);
            assertThat(SecUserRecord.encode(
                    SecUserRecord.of("ADMIN001", "MARGARET", "GOLD", SEED_PASSWORD, "A", ASCII),
                    ASCII)).hasSize(RECORD_WIDTH);
            assertThat(SecUserRecord.encode(
                    SecUserRecord.of("", "", "", "", "", ASCII), ASCII)).hasSize(RECORD_WIDTH);
        }
    }

    @Nested
    @DisplayName("The field-descriptor table")
    class Descriptors {
        @Test
        @DisplayName("there are exactly six descriptors, in copybook declaration order")
        void descriptorTable_hasSixEntriesInCopybookOrder() {
            assertThat(SecUserRecord.SPANS).hasSize(6);
            assertThat(SecUserRecord.SPANS.stream().map(FieldSpan::name))
                    .containsExactly("SEC-USR-ID", "SEC-USR-FNAME", "SEC-USR-LNAME",
                            "SEC-USR-PWD", "SEC-USR-TYPE", "SEC-USR-FILLER");

            assertThat(SecUserRecord.LAYOUT.storageSpans()).hasSize(6);
            assertThat(SecUserRecord.LAYOUT.redefinitions())
                    .as("CSUSR01Y declares no REDEFINES, so gate G34 has no subject in this package")
                    .isEmpty();
        }

        @ParameterizedTest(name = "{0} at offset {1}, {2} byte(s)")
        @CsvSource({
                "SEC-USR-ID,0,8",
                "SEC-USR-FNAME,8,20",
                "SEC-USR-LNAME,28,20",
                "SEC-USR-PWD,48,8",
                "SEC-USR-TYPE,56,1",
                "SEC-USR-FILLER,57,23"
        })
        @DisplayName("every descriptor sits where the copybook puts it and is PIC X")
        void everyDescriptor_isAlphanumericAtItsCopybookPosition(String cobolName,
                                                                 int offset,
                                                                 int length) {
            FieldSpan span = SecUserRecord.LAYOUT.span(cobolName);

            assertThat(span.name()).isEqualTo(cobolName);
            assertThat(span.offset()).isEqualTo(offset);
            assertThat(span.length()).isEqualTo(length);
            assertThat(span.endOffsetExclusive()).isEqualTo(offset + length);

            assertThat(span.kind()).isEqualTo(PictureKind.ALPHANUMERIC);
            assertThat(span.kind().numericDisplay()).isFalse();
            assertThat(span.kind().leftJustified())
                    .as("PIC X is left justified and pads on the right")
                    .isTrue();
            assertThat(span.redefinition()).isFalse();
            assertThat(span.hasInitialValue())
                    .as("CSUSR01Y declares no VALUE clause on any item")
                    .isFalse();
        }

        @Test
        @DisplayName("descriptors are contiguous from 0, never overlap, and end at exactly 80")
        void descriptors_areContiguousAndNonOverlapping_andEndAt80() {
            List<FieldSpan> spans = SecUserRecord.LAYOUT.storageSpans();

            assertThat(spans.get(0).offset()).as("the first span starts at offset 0").isZero();
            for (int index = 1; index < spans.size(); index++) {
                FieldSpan previous = spans.get(index - 1);
                FieldSpan current = spans.get(index);
                assertThat(current.offset())
                        .as("%s must begin where %s ends", current.name(), previous.name())
                        .isEqualTo(previous.offset() + previous.length());
            }

            FieldSpan last = spans.get(spans.size() - 1);
            assertThat(last.name()).isEqualTo("SEC-USR-FILLER");
            assertThat(last.offset() + last.length()).isEqualTo(SecUserRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("SEC-USR-FILLER is a resolvable named span, not an anonymous gap")
        void secUsrFiller_isANamedSpan_notAnAnonymousFiller() {
            FieldSpan filler = SecUserRecord.LAYOUT.span("SEC-USR-FILLER");

            assertThat(filler).isEqualTo(SecUserRecord.SPAN_SEC_USR_FILLER);
            assertThat(filler.kind()).isEqualTo(PictureKind.ALPHANUMERIC);
            assertThat(filler.kind().filler())
                    .as("declared as a named alphanumeric item so it stays referable by name")
                    .isFalse();
            assertThat(SecUserRecord.LAYOUT.hasSpan("SEC-USR-FILLER")).isTrue();
            assertThat(SecUserRecord.blank().image("SEC-USR-FILLER")).hasSize(23);
        }

        @Test
        @DisplayName("the descriptor table refuses mutation and is unchanged by the attempt")
        void descriptorTable_isImmutable_andUnchangedByAFailedMutation() {
            List<FieldSpan> before = List.copyOf(SecUserRecord.SPANS);

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> SecUserRecord.SPANS.add(SecUserRecord.SPAN_SEC_USR_ID));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> SecUserRecord.SPANS.remove(0));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> SecUserRecord.SPANS.set(0, SecUserRecord.SPAN_SEC_USR_PWD));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(SecUserRecord.SPANS::clear);

            assertThat(SecUserRecord.SPANS)
                    .as("a refused mutation must not have altered the table")
                    .containsExactlyElementsOf(before)
                    .hasSize(6);
        }

        @Test
        @DisplayName("the field-image map is complete, ordered, and refuses mutation")
        void fieldImages_areOrderedCompleteAndImmutable() {
            SecUserRecord record = seedRecord(0, ASCII);
            Map<String, String> images = record.fieldImages();

            assertThat(images).hasSize(6);
            assertThat(images.keySet()).containsExactly("SEC-USR-ID", "SEC-USR-FNAME",
                    "SEC-USR-LNAME", "SEC-USR-PWD", "SEC-USR-TYPE", "SEC-USR-FILLER");

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> images.put("SEC-USR-ID", "HACKED  "));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> images.remove("SEC-USR-PWD"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(images::clear);

            assertThat(record.fieldImages())
                    .as("a refused mutation must not have altered the record")
                    .containsEntry("SEC-USR-ID", "ADMIN001")
                    .containsEntry("SEC-USR-PWD", SEED_PASSWORD);
        }

        @Test
        @DisplayName("a known descriptor name resolves and an unknown one does not")
        void descriptorLookup_resolvesKnownNames_andRejectsUnknownOnes() {
            for (String known : List.of("SEC-USR-ID", "SEC-USR-FNAME", "SEC-USR-LNAME",
                    "SEC-USR-PWD", "SEC-USR-TYPE", "SEC-USR-FILLER")) {
                assertThat(SecUserRecord.LAYOUT.hasSpan(known)).as(known).isTrue();
                assertThat(SecUserRecord.LAYOUT.span(known).name()).isEqualTo(known);
            }

            for (String unknown : List.of("SEC-USER-FNAME", "SEC_USR_ID", "sec-usr-id",
                    "SEC-USER-DATA", "FILLER", "")) {
                assertThat(SecUserRecord.LAYOUT.hasSpan(unknown)).as(unknown).isFalse();
                assertThatIllegalArgumentException()
                        .as(unknown)
                        .isThrownBy(() -> SecUserRecord.LAYOUT.span(unknown));
            }
        }
    }

    @Nested
    @DisplayName("PIC X semantics: padded on write, truncated on the right, never trimmed on read")
    class PictureXSemantics {
        @ParameterizedTest(name = "{0} pads to {1}")
        @CsvSource({
                "SEC-USR-ID,8",
                "SEC-USR-FNAME,20",
                "SEC-USR-LNAME,20",
                "SEC-USR-PWD,8",
                "SEC-USR-TYPE,1",
                "SEC-USR-FILLER,23"
        })
        @DisplayName("a short value is space-padded on the right to its declared width")
        void shortValue_isPaddedOnTheRight(String cobolName, int declaredWidth) {
            SecUserRecord record = fieldSetTo(cobolName, "Q");
            String image = record.image(cobolName);

            assertThat(image).hasSize(declaredWidth);
            assertThat(image).startsWith("Q");
            assertThat(image.substring(1)).isEqualTo(spaces(declaredWidth - 1));
        }

        @Test
        @DisplayName("writing SEC-USR-FNAME leaves bytes 0-7 and 28-79 byte-identical")
        void writingOneField_leavesEveryNeighbouringByteUntouched() {
            SecUserRecord before = SecUserRecord.of("ADMIN001", "MARGARET", "GOLD", SEED_PASSWORD,
                    "A", ASCII);
            SecUserRecord after = SecUserRecord.of("ADMIN001", "MARGARETHE", "GOLD", SEED_PASSWORD,
                    "A", ASCII);

            byte[] first = SecUserRecord.encode(before, ASCII);
            byte[] second = SecUserRecord.encode(after, ASCII);
            assertThat(first).hasSize(80);
            assertThat(second).hasSize(80);

            assertThat(Arrays.copyOfRange(second, 0, 8))
                    .as("bytes 0-7 belong to SEC-USR-ID and must not move")
                    .isEqualTo(Arrays.copyOfRange(first, 0, 8));
            assertThat(Arrays.copyOfRange(second, 28, 80))
                    .as("bytes 28-79 belong to the four following items and must not move")
                    .isEqualTo(Arrays.copyOfRange(first, 28, 80));
            assertThat(Arrays.copyOfRange(second, 8, 28))
                    .isNotEqualTo(Arrays.copyOfRange(first, 8, 28));
        }

        @Test
        @DisplayName("an over-long value loses its RIGHTMOST characters, as PIC X does")
        void overLongValue_losesItsRightmostCharacters() {
            SecUserRecord record = SecUserRecord.of(
                    "ADMIN001X",
                    "ABCDEFGHIJKLMNOPQRSTUV",
                    "abcdefghijklmnopqrstuv",
                    "PASSWORD9",
                    "AU",
                    ASCII);

            assertThat(record.secUsrId()).isEqualTo("ADMIN001");
            assertThat(record.secUsrFname()).isEqualTo("ABCDEFGHIJKLMNOPQRST");
            assertThat(record.secUsrLname()).isEqualTo("abcdefghijklmnopqrst");
            assertThat(record.secUsrPwd()).isEqualTo("PASSWORD");
            assertThat(record.secUsrType()).isEqualTo("A");

            assertThat(record.secUsrFname()).startsWith("A").doesNotContain("U", "V");
            assertThat(record.secUsrId()).doesNotEndWith("X");
        }

        @Test
        @DisplayName("an exact-width value is neither padded nor truncated")
        void exactWidthValue_isNeitherPaddedNorTruncated() {
            SecUserRecord record = SecUserRecord.of("ADMIN001", pad("MARGARET", 20), pad("GOLD", 20),
                    SEED_PASSWORD, "A", ASCII);

            assertThat(record.secUsrId()).isEqualTo("ADMIN001").hasSize(8);
            assertThat(record.secUsrFname()).isEqualTo(pad("MARGARET", 20)).hasSize(20);
            assertThat(record.secUsrLname()).isEqualTo(pad("GOLD", 20)).hasSize(20);
            assertThat(record.secUsrPwd()).isEqualTo(SEED_PASSWORD).hasSize(8);
            assertThat(record.secUsrType()).isEqualTo("A").hasSize(1);
        }

        @Test
        @DisplayName("decode retains padding at the full declared width and never trims")
        void decode_retainsPadding_andNeverTrims() {
            SecUserRecord admin = seedRecord(0, ASCII);

            assertThat(admin.secUsrId()).isEqualTo("ADMIN001").hasSize(8);
            assertThat(admin.secUsrFname()).isEqualTo("MARGARET" + spaces(12)).hasSize(20);
            assertThat(admin.secUsrLname()).isEqualTo("GOLD" + spaces(16)).hasSize(20);
            assertThat(admin.secUsrPwd()).isEqualTo(SEED_PASSWORD).hasSize(8);
            assertThat(admin.secUsrType()).isEqualTo("A").hasSize(1);
            assertThat(admin.secUsrFiller()).isEqualTo(spaces(23)).hasSize(23);

            assertThat(admin.secUsrFname()).isNotEqualTo("MARGARET");
            assertThat(admin.secUsrLname()).isNotEqualTo("GOLD");
            assertThat(admin.secUsrFname().trim()).isEqualTo("MARGARET");
        }

        @Test
        @DisplayName("an equal-width screen field compares equal, which the change tests rely on")
        void equalWidthScreenField_comparesEqual_soChangeDetectionHolds() {
            SecUserRecord stored = seedRecord(0, ASCII);

            String unchangedScreenField = pad("MARGARET", 20);
            String changedScreenField = pad("MARGARETHE", 20);
            assertThat(unchangedScreenField).hasSize(20);
            assertThat(changedScreenField).hasSize(20);

            assertThat(stored.secUsrFname())
                    .as("unchanged must compare EQUAL, or a spurious update is written")
                    .isEqualTo(unchangedScreenField);
            assertThat(stored.secUsrFname())
                    .as("genuinely changed must compare UNEQUAL, or a real update is lost")
                    .isNotEqualTo(changedScreenField);

            assertThat(stored.secUsrFname().trim()).isNotEqualTo(unchangedScreenField);
        }

        @Test
        @DisplayName("key() equals the encoded record's leading eight bytes at offset 0")
        void key_equalsTheEncodedLeadingBytes() {
            for (int index = 0; index < SEED_CARDS.size(); index++) {
                SecUserRecord record = seedRecord(index, EBCDIC);
                byte[] encoded = SecUserRecord.encode(record, EBCDIC);

                String leading = new String(Arrays.copyOfRange(encoded, SecUserRecord.KEY_OFFSET,
                        SecUserRecord.KEY_OFFSET + SecUserRecord.KEY_LENGTH), EBCDIC);

                assertThat(record.key())
                        .as("row %d", index)
                        .isEqualTo(record.secUsrId())
                        .isEqualTo(leading)
                        .hasSize(8);
            }
        }

        @Test
        @DisplayName("mixed case is stored verbatim - no upper-casing, lower-casing or trimming")
        void noNormalisation_isApplied() {
            SecUserRecord record = SecUserRecord.of("user0001", "  lawrence", "thoMAS  ",
                    "pAsSw0rd", "u", ASCII);

            assertThat(record.secUsrId()).isEqualTo("user0001");
            assertThat(record.secUsrFname()).isEqualTo(pad("  lawrence", 20));
            assertThat(record.secUsrLname()).isEqualTo(pad("thoMAS  ", 20));
            assertThat(record.secUsrPwd()).isEqualTo("pAsSw0rd");
            assertThat(record.secUsrType()).isEqualTo("u");

            assertThat(record.secUsrFname()).startsWith("  lawrence");
            assertThat(record.secUsrId()).isNotEqualTo("USER0001");
            assertThat(record.secUsrType()).isNotEqualTo("U");
        }

        @ParameterizedTest(name = "SEC-USR-TYPE = ''{0}''")
        @ValueSource(strings = {"A", "U", " ", "X", "a", "u", "1", "-"})
        @DisplayName("SEC-USR-TYPE carries any single byte without validating it")
        void secUsrType_isAPassiveSingleByteCarrier(String type) {
            SecUserRecord record = SecUserRecord.of("USER0001", "LAWRENCE", "THOMAS", SEED_PASSWORD,
                    type, ASCII);

            assertThat(record.secUsrType()).isEqualTo(type).hasSize(1);
            assertThat(SecUserRecord.decode(SecUserRecord.encode(record, ASCII), ASCII).secUsrType())
                    .isEqualTo(type);
        }

        @Test
        @DisplayName("decode(encode(r)) equals r, and encode(decode(b)) equals b, field by field")
        void roundTrip_holdsBothWays_withNonSymmetricValues() {
            SecUserRecord original = new SecUserRecord(
                    "IDIDIDID",
                    "FNFNFNFNFNFNFNFNFNFN",
                    "LNLNLNLNLNLNLNLNLNLN",
                    "PWPWPWPW",
                    "T",
                    "FILLERFILLERFILLERFILLE");

            byte[] encoded = SecUserRecord.encode(original, ASCII);
            assertThat(encoded).hasSize(80);

            SecUserRecord decoded = SecUserRecord.decode(encoded, ASCII);
            assertThat(decoded).isEqualTo(original);
            assertThat(decoded.secUsrId()).isEqualTo("IDIDIDID");
            assertThat(decoded.secUsrFname()).isEqualTo("FNFNFNFNFNFNFNFNFNFN");
            assertThat(decoded.secUsrLname()).isEqualTo("LNLNLNLNLNLNLNLNLNLN");
            assertThat(decoded.secUsrPwd()).isEqualTo("PWPWPWPW");
            assertThat(decoded.secUsrType()).isEqualTo("T");
            assertThat(decoded.secUsrFiller()).isEqualTo("FILLERFILLERFILLERFILLE");

            assertThat(SecUserRecord.encode(decoded, ASCII))
                    .as("encode(decode(b)) must be byte-for-byte b")
                    .isEqualTo(encoded);

            String image = new String(encoded, ASCII);
            assertThat(image.substring(0, 8)).isEqualTo("IDIDIDID");
            assertThat(image.substring(8, 28)).isEqualTo("FNFNFNFNFNFNFNFNFNFN");
            assertThat(image.substring(28, 48)).isEqualTo("LNLNLNLNLNLNLNLNLNLN");
            assertThat(image.substring(48, 56)).isEqualTo("PWPWPWPW");
            assertThat(image.substring(56, 57)).isEqualTo("T");
            assertThat(image.substring(57, 80)).isEqualTo("FILLERFILLERFILLERFILLE");
        }

        @Test
        @DisplayName("the codec-taking overloads agree with the charset-taking ones")
        void codecOverloads_agreeWithCharsetOverloads() {
            FixedWidthCodec codec = new FixedWidthCodec(EBCDIC);
            byte[] row = recordBytes(SEED_CARDS.get(3), EBCDIC);

            SecUserRecord viaCodec = SecUserRecord.decode(row, codec);
            SecUserRecord viaCharset = SecUserRecord.decode(row, EBCDIC);
            assertThat(viaCodec).isEqualTo(viaCharset);

            assertThat(SecUserRecord.encode(viaCodec, codec))
                    .isEqualTo(SecUserRecord.encode(viaCharset, EBCDIC))
                    .isEqualTo(row);
            assertThat(codec.charset()).isEqualTo(EBCDIC);
        }
    }

    @Nested
    @DisplayName("The trailing SEC-USR-FILLER span, which must never be dropped")
    class TrailingSpan {
        @Test
        @DisplayName("encode is exactly 80 bytes for populated, blank and empty-valued records")
        void encode_isExactly80Bytes_forEveryRecordShape() {
            SecUserRecord populated = SecUserRecord.of("ADMIN005", "GRANVILLE", "LACHAPELLE",
                    SEED_PASSWORD, "A", ASCII);
            SecUserRecord blank = SecUserRecord.blank();
            SecUserRecord empty = SecUserRecord.of("", "", "", "", "", ASCII);

            assertThat(SecUserRecord.encode(populated, ASCII)).hasSize(80);
            assertThat(SecUserRecord.encode(blank, ASCII)).hasSize(80);
            assertThat(SecUserRecord.encode(empty, ASCII)).hasSize(80);
            assertThat(SecUserRecord.encode(populated, EBCDIC)).hasSize(80);
            assertThat(SecUserRecord.encode(blank, EBCDIC)).hasSize(80);
            assertThat(SecUserRecord.encode(empty, EBCDIC)).hasSize(80);

            assertThat(blank.secUsrId()).isEqualTo(spaces(8));
            assertThat(blank.secUsrFname()).isEqualTo(spaces(20));
            assertThat(blank.secUsrLname()).isEqualTo(spaces(20));
            assertThat(blank.secUsrPwd()).isEqualTo(spaces(8));
            assertThat(blank.secUsrType()).isEqualTo(spaces(1));
            assertThat(blank.secUsrFiller()).isEqualTo(spaces(23));
        }

        @Test
        @DisplayName("secUsrFiller is space-filled, not zero-filled, so the record stays 80 bytes")
        void secUsrFiller_isSpaceFilled_soRecordStays80Bytes() {
            byte[] ascii = SecUserRecord.encode(
                    SecUserRecord.of("USER0001", "LAWRENCE", "THOMAS", SEED_PASSWORD, "U", ASCII),
                    ASCII);

            assertThat(ascii).hasSize(80);
            for (int index = 57; index < 80; index++) {
                assertThat(ascii[index]).as("byte %d of the trailing span", index)
                        .isEqualTo(ASCII_SPACE)
                        .isNotEqualTo(LOW_VALUE);
            }
            assertThat(Arrays.copyOfRange(ascii, 57, 80))
                    .isEqualTo(repeatByte(ASCII_SPACE, 23))
                    .isNotEqualTo(repeatByte(LOW_VALUE, 23));

            byte[] ebcdic = SecUserRecord.encode(
                    SecUserRecord.of("USER0001", "LAWRENCE", "THOMAS", SEED_PASSWORD, "U", EBCDIC),
                    EBCDIC);
            assertThat(Arrays.copyOfRange(ebcdic, 57, 80))
                    .isEqualTo(repeatByte(EBCDIC_SPACE, 23))
                    .isNotEqualTo(repeatByte(LOW_VALUE, 23));
        }

        @Test
        @DisplayName("57 + 23 = 80, so dropping the filler would leave a 57-byte record")
        void theTrailingSpanIsNotDroppable_because57Plus23Is80() {
            assertThat(SecUserRecord.RECORD_LENGTH).isEqualTo(80);
            assertThat(57 + 23).isEqualTo(80);
            assertThat(SecUserRecord.SEC_USR_FILLER_OFFSET
                    + SecUserRecord.SEC_USR_FILLER_LENGTH).isEqualTo(80);

            assertThat(CARD_WIDTH).isEqualTo(57);
            assertThat(SEED_CARDS.get(0)).hasSize(57);
            assertThat(SecUserRecord.RECORD_LENGTH - SecUserRecord.SEC_USR_FILLER_LENGTH)
                    .as("the record minus its filler is precisely the seed-card width")
                    .isEqualTo(CARD_WIDTH);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> SecUserRecord.decode(SEED_CARDS.get(0).getBytes(ASCII), ASCII));
        }

        @Test
        @DisplayName("secUsrFiller is a real accessor that round-trips a value written into it")
        void secUsrFiller_isARealAccessor_thatRoundTripsAValue() {
            FixedWidthCodec codec = new FixedWidthCodec(ASCII);
            SecUserRecord record = SecUserRecord.of("USER0005", "LEE", "TING", SEED_PASSWORD, "U",
                    "TRAILER-BYTES-KEPT-AS-I", codec);

            assertThat(record.secUsrFiller()).isEqualTo("TRAILER-BYTES-KEPT-AS-I").hasSize(23);
            assertThat(record.image("SEC-USR-FILLER")).isEqualTo("TRAILER-BYTES-KEPT-AS-I");

            byte[] encoded = SecUserRecord.encode(record, ASCII);
            assertThat(encoded).hasSize(80);
            assertThat(new String(encoded, ASCII).substring(57))
                    .isEqualTo("TRAILER-BYTES-KEPT-AS-I");
            assertThat(SecUserRecord.decode(encoded, ASCII).secUsrFiller())
                    .isEqualTo("TRAILER-BYTES-KEPT-AS-I");

            assertThat(record).isNotEqualTo(SecUserRecord.of("USER0005", "LEE", "TING",
                    SEED_PASSWORD, "U", ASCII));
        }
    }

    @Nested
    @DisplayName("The key span as a raw-byte sentinel: LOW-VALUES and HIGH-VALUES")
    class KeySpanSentinels {
        @ParameterizedTest(name = "under {0}")
        @ValueSource(strings = {"US-ASCII", "IBM037"})
        @DisplayName("keySpan round-trips LOW-VALUES, for browse-from-first")
        void keySpan_roundTripsLowValues_forBrowseFromFirst(String charsetName) {
            Charset charset = Charset.forName(charsetName);
            byte[] row = sentinelKeyRow(LOW_VALUE, charset);

            SecUserRecord decoded = SecUserRecord.decode(row, charset);
            byte[] reencoded = SecUserRecord.encode(decoded, charset);

            assertThat(reencoded).hasSize(80).isEqualTo(row);
            assertThat(Arrays.copyOfRange(reencoded, 0, 8))
                    .as("the eight key bytes must still be LOW-VALUES")
                    .isEqualTo(repeatByte(LOW_VALUE, 8));

            assertThat(decoded.secUsrId()).hasSize(8);
            assertThat(decoded.key()).isEqualTo(decoded.secUsrId()).hasSize(8);
            assertThat(decoded.image("SEC-USR-ID")).isEqualTo(decoded.secUsrId());
        }

        @Test
        @DisplayName("keySpan round-trips HIGH-VALUES, for position-at-last")
        void keySpan_roundTripsHighValues_forPositionAtLast() {
            byte[] row = sentinelKeyRow(HIGH_VALUE, EBCDIC);

            SecUserRecord decoded = SecUserRecord.decode(row, EBCDIC);
            byte[] reencoded = SecUserRecord.encode(decoded, EBCDIC);

            assertThat(reencoded).hasSize(80).isEqualTo(row);
            assertThat(Arrays.copyOfRange(reencoded, 0, 8))
                    .as("the eight key bytes must still be HIGH-VALUES")
                    .isEqualTo(repeatByte(HIGH_VALUE, 8));
            assertThat(decoded.secUsrId()).hasSize(8);
            assertThat(decoded.key()).hasSize(8);
        }

        @Test
        @DisplayName("neither sentinel is coerced to spaces, trimmed away, or emptied")
        void keySentinels_areNotCoercedTrimmedOrEmptied() {
            SecUserRecord low = SecUserRecord.decode(sentinelKeyRow(LOW_VALUE, EBCDIC), EBCDIC);
            SecUserRecord high = SecUserRecord.decode(sentinelKeyRow(HIGH_VALUE, EBCDIC), EBCDIC);

            for (SecUserRecord sentinel : List.of(low, high)) {
                assertThat(sentinel.secUsrId())
                        .hasSize(8)
                        .isNotEmpty()
                        .isNotEqualTo(spaces(8));
                assertThat(sentinel.key()).hasSize(8).isNotEqualTo(spaces(8));
            }

            assertThat(low.secUsrId()).isNotEqualTo(high.secUsrId());
            assertThat(low.secUsrId()).isNotEqualTo(SecUserRecord.blank().secUsrId());
            assertThat(high.secUsrId()).isNotEqualTo(SecUserRecord.blank().secUsrId());

            assertThat(Arrays.copyOfRange(SecUserRecord.encode(low, EBCDIC), 0, 8))
                    .isEqualTo(repeatByte(LOW_VALUE, 8));
            assertThat(Arrays.copyOfRange(SecUserRecord.encode(high, EBCDIC), 0, 8))
                    .isEqualTo(repeatByte(HIGH_VALUE, 8));
        }

        @Test
        @DisplayName("a sentinel key leaves the other five fields intact")
        void sentinelKey_leavesTheRemainingFieldsIntact() {
            byte[] row = sentinelKeyRow(LOW_VALUE, ASCII);
            SecUserRecord decoded = SecUserRecord.decode(row, ASCII);

            assertThat(decoded.secUsrFname()).isEqualTo(pad("MARGARET", 20));
            assertThat(decoded.secUsrLname()).isEqualTo(pad("GOLD", 20));
            assertThat(decoded.secUsrPwd()).isEqualTo(SEED_PASSWORD);
            assertThat(decoded.secUsrType()).isEqualTo("A");
            assertThat(decoded.secUsrFiller()).isEqualTo(spaces(23));
        }
    }

    @Nested
    @DisplayName("The plaintext password, preserved exactly as the legacy design has it")
    class PlaintextPassword {
        @ParameterizedTest(name = "password ''{0}''")
        @ValueSource(strings = {"PASSWORD", "S3cr3tPW", "aB!@1234", "        ", "x"})
        @DisplayName("the password round-trips in clear text in the eight bytes at offset 48")
        void password_roundTripsInClearTextAtOffset48(String password) {
            SecUserRecord record = SecUserRecord.of("USER0001", "LAWRENCE", "THOMAS", password, "U",
                    ASCII);
            byte[] encoded = SecUserRecord.encode(record, ASCII);

            String expected = pad(password, 8);
            assertThat(Arrays.copyOfRange(encoded, 48, 56))
                    .as("stored bytes must equal the input characters, unaltered")
                    .isEqualTo(expected.getBytes(ASCII));
            assertThat(record.secUsrPwd()).isEqualTo(expected);
            assertThat(record.image("SEC-USR-PWD")).isEqualTo(expected);
            assertThat(SecUserRecord.decode(encoded, ASCII).secUsrPwd()).isEqualTo(expected);
        }

        @Test
        @DisplayName("the seed password exactly fills PIC X(08) - no padding, no truncation")
        void seedPassword_exactlyFillsPicX08() {
            assertThat(SEED_PASSWORD).hasSize(8);
            assertThat(SecUserRecord.SEC_USR_PWD_LENGTH).isEqualTo(8);

            SecUserRecord record = seedRecord(0, ASCII);
            assertThat(record.secUsrPwd())
                    .isEqualTo(SEED_PASSWORD)
                    .hasSize(8)
                    .doesNotContain(" ");
        }

        @Test
        @DisplayName("no hashing, digest, salt or encoder step exists in the model path")
        void modelPath_containsNoHashingDigestOrEncoderStep() {
            List<String> forbidden = List.of("digest", "bcrypt", "scrypt", "pbkdf", "salt",
                    "encrypt", "cipher", "hmac", "sha1", "sha256", "md5", "obfuscat", "passwordencoder",
                    "hash");

            List<String> memberNames = new ArrayList<>();
            for (Method method : SecUserRecord.class.getDeclaredMethods()) {
                if (!"hashCode".equals(method.getName())) {
                    memberNames.add(method.getName());
                }
            }
            for (Field field : SecUserRecord.class.getDeclaredFields()) {
                memberNames.add(field.getName());
            }

            assertThat(memberNames).isNotEmpty();
            for (String name : memberNames) {
                String lowered = name.toLowerCase(Locale.ROOT);
                for (String token : forbidden) {
                    assertThat(lowered)
                            .as("member '%s' must not suggest a credential transformation", name)
                            .doesNotContain(token);
                }
            }

            for (Class<?> type : declaredTypesOf(SecUserRecord.class)) {
                assertThat(type.getName().toLowerCase(Locale.ROOT))
                        .doesNotContain("messagedigest")
                        .doesNotContain("passwordencoder")
                        .doesNotContain("crypto");
            }
        }

        @Test
        @DisplayName("no Spring Security or BCrypt type is on the classpath at all")
        void modelPath_referencesNoSpringSecurityType() {
            for (String excluded : List.of(
                    "org.springframework.security.crypto.password.PasswordEncoder",
                    "org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder",
                    "org.springframework.security.core.userdetails.UserDetails",
                    "at.favre.lib.crypto.bcrypt.BCrypt")) {
                assertThatExceptionOfType(ClassNotFoundException.class)
                        .as("%s must not be on the classpath", excluded)
                        .isThrownBy(() -> Class.forName(excluded));
            }

            for (Class<?> type : declaredTypesOf(SecUserRecord.class)) {
                assertThat(type.getName()).doesNotStartWith("org.springframework.security");
            }
        }

        @Test
        @DisplayName("the password stays reachable for the COSGN00C plaintext comparison")
        void password_isReachableForThePlaintextComparison() {
            SecUserRecord stored = seedRecord(0, ASCII);

            String suppliedByTheOperator = pad("PASSWORD", 8);
            assertThat(stored.secUsrPwd()).isEqualTo(suppliedByTheOperator);
            assertThat(stored.secUsrPwd()).isNotEqualTo(pad("WRONGPWD", 8));

            assertThat(stored.fieldImages()).containsEntry("SEC-USR-PWD", SEED_PASSWORD);
        }
    }

    @Nested
    @DisplayName("The ten seeded USRSEC rows from DUSRSECJ.jcl:35-44")
    class SeedData {
        @Test
        @DisplayName("there are ten cards and every one is exactly 57 characters")
        void allTenCards_areExactly57Characters() {
            assertThat(SEED_CARDS).hasSize(10);
            assertThat(SEED_CARDS).doesNotHaveDuplicates();
            for (int index = 0; index < SEED_CARDS.size(); index++) {
                assertThat(SEED_CARDS.get(index)).as("card %d", index).hasSize(CARD_WIDTH);
            }
            assertThat(SEED_CARDS).hasSize(10);
        }

        @ParameterizedTest(name = "[{0}] {1} {2} {3} type {4}")
        @CsvSource({
                "0,ADMIN001,MARGARET,GOLD,A",
                "1,ADMIN002,RUSSELL,RUSSELL,A",
                "2,ADMIN003,RAYMOND,WHITMORE,A",
                "3,ADMIN004,EMMANUEL,CASGRAIN,A",
                "4,ADMIN005,GRANVILLE,LACHAPELLE,A",
                "5,USER0001,LAWRENCE,THOMAS,U",
                "6,USER0002,AJITH,KUMAR,U",
                "7,USER0003,LAURITZ,ALME,U",
                "8,USER0004,AVERARDO,MAZZI,U",
                "9,USER0005,LEE,TING,U"
        })
        @DisplayName("every seeded row decodes field for field, with a space-filled trailer")
        void everySeedRow_decodesFieldForField(int index, String id, String firstName,
                                               String lastName, String type) {
            SecUserRecord record = seedRecord(index, ASCII);

            assertThat(record.secUsrId()).isEqualTo(id).hasSize(8);
            assertThat(record.secUsrFname()).isEqualTo(pad(firstName, 20)).hasSize(20);
            assertThat(record.secUsrLname()).isEqualTo(pad(lastName, 20)).hasSize(20);
            assertThat(record.secUsrPwd()).isEqualTo(SEED_PASSWORD).hasSize(8);
            assertThat(record.secUsrType()).isEqualTo(type).hasSize(1);
            assertThat(record.secUsrFiller()).isEqualTo(spaces(23)).hasSize(23);

            assertThat(record.key()).isEqualTo(id);
            assertThat(record.image("SEC-USR-ID")).isEqualTo(id);
            assertThat(record.image("SEC-USR-TYPE")).isEqualTo(type);
        }

        @ParameterizedTest(name = "row {0}")
        @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9})
        @DisplayName("encode reproduces the 80-byte image: 57 source bytes then 23 spaces")
        void everySeedRow_reEncodesToTheSame80ByteImage(int index) {
            String card = SEED_CARDS.get(index);

            for (Charset charset : List.of(ASCII, EBCDIC)) {
                byte[] row = recordBytes(card, charset);
                assertThat(row).hasSize(80);

                byte[] reencoded = SecUserRecord.encode(SecUserRecord.decode(row, charset), charset);
                assertThat(reencoded).hasSize(80).isEqualTo(row);

                assertThat(Arrays.copyOfRange(reencoded, 0, CARD_WIDTH))
                        .isEqualTo(card.getBytes(charset));
                assertThat(Arrays.copyOfRange(reencoded, CARD_WIDTH, 80))
                        .isEqualTo(spaces(23).getBytes(charset));
            }
        }

        @Test
        @DisplayName("the ten rows split five admin 'A' and five user 'U'")
        void seedTypes_splitFiveAdminAndFiveUser() {
            List<String> types = new ArrayList<>();
            List<String> ids = new ArrayList<>();
            for (int index = 0; index < SEED_CARDS.size(); index++) {
                SecUserRecord record = seedRecord(index, ASCII);
                types.add(record.secUsrType());
                ids.add(record.secUsrId());
            }

            assertThat(types).containsExactly("A", "A", "A", "A", "A", "U", "U", "U", "U", "U");
            assertThat(types).filteredOn("A"::equals).hasSize(5);
            assertThat(types).filteredOn("U"::equals).hasSize(5);
            assertThat(ids).containsExactly("ADMIN001", "ADMIN002", "ADMIN003", "ADMIN004",
                    "ADMIN005", "USER0001", "USER0002", "USER0003", "USER0004", "USER0005");
            assertThat(ids).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("ADMIN005's GRANVILLE and LACHAPELLE pad correctly inside their X(20) fields")
        void longestNames_padCorrectlyInsideTheirX20Fields() {
            SecUserRecord record = seedRecord(4, ASCII);

            assertThat(record.secUsrId()).isEqualTo("ADMIN005");
            assertThat("GRANVILLE").hasSize(9);
            assertThat(record.secUsrFname()).isEqualTo("GRANVILLE" + spaces(11)).hasSize(20);
            assertThat("LACHAPELLE").hasSize(10);
            assertThat(record.secUsrLname()).isEqualTo("LACHAPELLE" + spaces(10)).hasSize(20);
        }

        @Test
        @DisplayName("USER0005's LEE and TING pad correctly inside their X(20) fields")
        void shortestNames_padCorrectlyInsideTheirX20Fields() {
            SecUserRecord record = seedRecord(9, ASCII);

            assertThat(record.secUsrId()).isEqualTo("USER0005");
            assertThat("LEE").hasSize(3);
            assertThat(record.secUsrFname()).isEqualTo("LEE" + spaces(17)).hasSize(20);
            assertThat("TING").hasSize(4);
            assertThat(record.secUsrLname()).isEqualTo("TING" + spaces(16)).hasSize(20);
        }

        @Test
        @DisplayName("ADMIN001 and USER0001 match README.md:157-158, password included")
        void readmeCorroboratesTheFirstAdminAndTheFirstUser() {
            SecUserRecord admin = seedRecord(0, ASCII);
            SecUserRecord user = seedRecord(5, ASCII);

            assertThat(admin.secUsrId()).isEqualTo("ADMIN001");
            assertThat(admin.secUsrPwd()).isEqualTo("PASSWORD");
            assertThat(admin.secUsrType()).isEqualTo("A");

            assertThat(user.secUsrId()).isEqualTo("USER0001");
            assertThat(user.secUsrPwd()).isEqualTo("PASSWORD");
            assertThat(user.secUsrType()).isEqualTo("U");
        }

        @Test
        @DisplayName("a 57-byte card is widened to 80 by the codec's explicit padding step")
        void aShortCard_isWidenedByTheExplicitPaddingStep() {
            FixedWidthCodec codec = new FixedWidthCodec(ASCII);
            byte[] card = SEED_CARDS.get(0).getBytes(ASCII);
            assertThat(card).hasSize(57);

            byte[] widened = codec.padToDeclaredWidth(card, SecUserRecord.RECORD_LENGTH);
            assertThat(widened).hasSize(80).isEqualTo(recordBytes(SEED_CARDS.get(0), ASCII));
            assertThat(SecUserRecord.decode(widened, ASCII)).isEqualTo(seedRecord(0, ASCII));
        }
    }

    @Nested
    @DisplayName("Charset handling: always explicit, never the platform default")
    class Charsets {
        @Test
        @DisplayName("one record yields different bytes per code page but identical decoded fields")
        void oneRecord_yieldsDifferentBytesPerCodePage_butIdenticalFields() {
            SecUserRecord record = SecUserRecord.of("ADMIN001", "MARGARET", "GOLD", SEED_PASSWORD,
                    "A", ASCII);

            byte[] asAscii = SecUserRecord.encode(record, ASCII);
            byte[] asEbcdic = SecUserRecord.encode(record, EBCDIC);

            assertThat(asAscii).hasSize(80);
            assertThat(asEbcdic).hasSize(80);
            assertThat(asEbcdic)
                    .as("the code page must actually change the bytes")
                    .isNotEqualTo(asAscii);

            assertThat(asAscii[0]).isEqualTo((byte) 0x41);
            assertThat(asEbcdic[0]).isEqualTo((byte) 0xC1);

            SecUserRecord fromAscii = SecUserRecord.decode(asAscii, ASCII);
            SecUserRecord fromEbcdic = SecUserRecord.decode(asEbcdic, EBCDIC);
            assertThat(fromEbcdic).isEqualTo(fromAscii).isEqualTo(record);
            assertThat(fromEbcdic.fieldImages()).isEqualTo(fromAscii.fieldImages());
        }

        @Test
        @DisplayName("the pad byte is 0x40 under IBM037 and 0x20 under US-ASCII")
        void padByte_is0x40UnderIbm037_and0x20UnderUsAscii() {
            SecUserRecord shortValues = SecUserRecord.of("AB", "CD", "EF", "GH", "I", ASCII);

            byte[] ascii = SecUserRecord.encode(shortValues, ASCII);
            byte[] ebcdic = SecUserRecord.encode(shortValues, EBCDIC);

            assertThat(ascii[2]).isEqualTo(ASCII_SPACE).isEqualTo((byte) 0x20);
            assertThat(ebcdic[2]).isEqualTo(EBCDIC_SPACE).isEqualTo((byte) 0x40);

            assertThat(Arrays.copyOfRange(ascii, 57, 80)).isEqualTo(repeatByte((byte) 0x20, 23));
            assertThat(Arrays.copyOfRange(ebcdic, 57, 80)).isEqualTo(repeatByte((byte) 0x40, 23));

            assertThat(ASCII_SPACE).isNotEqualTo(EBCDIC_SPACE);
        }

        @Test
        @DisplayName("a multi-byte code page is refused, since offsets are absolute byte positions")
        void multiByteCodePage_isRefused() {
            for (String multiByte : List.of("UTF-16", "UTF-16BE", "UTF-32")) {
                Charset charset = Charset.forName(multiByte);
                assertThatIllegalArgumentException()
                        .as(multiByte)
                        .isThrownBy(() -> SecUserRecord.encode(SecUserRecord.blank(), charset));
                assertThatIllegalArgumentException()
                        .as(multiByte)
                        .isThrownBy(() -> SecUserRecord.decode(new byte[80], charset));
            }
        }

        @Test
        @DisplayName("results do not depend on the JVM's default charset")
        void results_areIndependentOfTheJvmDefaultCharset() {
            SecUserRecord record = seedRecord(0, ASCII);
            byte[] first = SecUserRecord.encode(record, ASCII);
            byte[] second = SecUserRecord.encode(record, ASCII);

            assertThat(second).isEqualTo(first);
            assertThat(first[0]).isEqualTo((byte) 0x41);
            assertThat(new String(first, ASCII).substring(0, 8)).isEqualTo("ADMIN001");

            assertThat(ASCII.name()).isEqualTo("US-ASCII");
            assertThat(EBCDIC.name()).isEqualTo("IBM037");
        }
    }

    @Nested
    @DisplayName("Validation branches: failing loudly rather than adjusting silently")
    class ValidationBranches {
        @Test
        @DisplayName("decode accepts exactly 80 bytes")
        void decode_acceptsExactly80Bytes() {
            byte[] eighty = recordBytes(SEED_CARDS.get(0), ASCII);
            assertThat(eighty).hasSize(80);

            assertThat(SecUserRecord.decode(eighty, ASCII).secUsrId()).isEqualTo("ADMIN001");
            assertThat(SecUserRecord.decode(new byte[80], EBCDIC).secUsrId()).hasSize(8);
        }

        @ParameterizedTest(name = "{0} byte(s) is rejected")
        @ValueSource(ints = {0, 1, 36, 50, 56, 57, 79, 81, 100, 160})
        @DisplayName("decode rejects every byte count that is not exactly 80")
        void decode_rejectsAnyOtherLength(int length) {
            byte[] wrongWidth = repeatByte(EBCDIC_SPACE, length);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> SecUserRecord.decode(wrongWidth, EBCDIC))
                    .withMessageContaining(String.valueOf(length))
                    .withMessageContaining("80");
        }

        @Test
        @DisplayName("a 57-byte row names SEC-USR-FILLER and the widening step in its message")
        void shortRow_failureMessageIsDiagnostic() {
            byte[] fiftySeven = SEED_CARDS.get(0).getBytes(ASCII);
            assertThat(fiftySeven).hasSize(57);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> SecUserRecord.decode(fiftySeven, ASCII))
                    .withMessageContaining("SEC-USR-FILLER")
                    .withMessageContaining("57")
                    .withMessageContaining("80");
        }

        @Test
        @DisplayName("null is rejected on every entry point, never silently accepted")
        void nullArguments_areRejectedEverywhere() {
            FixedWidthCodec codec = new FixedWidthCodec(ASCII);

            assertThatNullPointerException()
                    .isThrownBy(() -> SecUserRecord.decode(null, ASCII));
            assertThatNullPointerException()
                    .isThrownBy(() -> SecUserRecord.decode(null, codec));
            assertThatNullPointerException()
                    .isThrownBy(() -> SecUserRecord.encode(null, ASCII));
            assertThatNullPointerException()
                    .isThrownBy(() -> SecUserRecord.encode(null, codec));

            assertThatNullPointerException()
                    .as("a null codec cannot carry a code page")
                    .isThrownBy(() -> SecUserRecord.decode(new byte[80], (FixedWidthCodec) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> SecUserRecord.encode(SecUserRecord.blank(),
                            (FixedWidthCodec) null));
            assertThatNullPointerException()
                    .as("a null charset is never defaulted to the platform's")
                    .isThrownBy(() -> SecUserRecord.encode(SecUserRecord.blank(), (Charset) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> SecUserRecord.decode(new byte[80], (Charset) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> SecUserRecord.of("USER0001", "A", "B", "P", "U",
                            "F", (FixedWidthCodec) null));
        }

        @ParameterizedTest(name = "a null in position {0} names {1}")
        @CsvSource({
                "0,SEC-USR-ID",
                "1,SEC-USR-FNAME",
                "2,SEC-USR-LNAME",
                "3,SEC-USR-PWD",
                "4,SEC-USR-TYPE",
                "5,SEC-USR-FILLER"
        })
        @DisplayName("of(...) rejects a null sending value naming the receiving field")
        void of_rejectsNullSendingValues(int position, String expectedField) {
            String[] values = {"USER0001", "LAWRENCE", "THOMAS", SEED_PASSWORD, "U", ""};
            values[position] = null;
            FixedWidthCodec codec = new FixedWidthCodec(ASCII);

            assertThatNullPointerException()
                    .isThrownBy(() -> SecUserRecord.of(values[0], values[1], values[2], values[3],
                            values[4], values[5], codec))
                    .withMessageContaining(expectedField);
        }

        @ParameterizedTest(name = "widths {0}/{1}/{2}/{3}/{4}/{5} are rejected")
        @CsvSource({
                "7,20,20,8,1,23",
                "9,20,20,8,1,23",
                "8,19,20,8,1,23",
                "8,21,20,8,1,23",
                "8,20,19,8,1,23",
                "8,20,21,8,1,23",
                "8,20,20,7,1,23",
                "8,20,20,9,1,23",
                "8,20,20,8,0,23",
                "8,20,20,8,2,23",
                "8,20,20,8,1,22",
                "8,20,20,8,1,24"
        })
        @DisplayName("the constructor rejects any component that is not exactly its declared width")
        void constructor_rejectsWrongWidths(int id, int firstName, int lastName, int password,
                                            int type, int filler) {
            assertThatIllegalArgumentException().isThrownBy(() -> new SecUserRecord(
                    spaces(id), spaces(firstName), spaces(lastName),
                    spaces(password), spaces(type), spaces(filler)));
        }

        @Test
        @DisplayName("the constructor accepts all six components at exactly their declared widths")
        void constructor_acceptsExactWidths() {
            SecUserRecord record = new SecUserRecord(spaces(8), spaces(20), spaces(20), spaces(8),
                    spaces(1), spaces(23));

            assertThat(record).isEqualTo(SecUserRecord.blank());
            assertThat(SecUserRecord.encode(record, ASCII)).hasSize(80);
        }

        @Test
        @DisplayName("a rejected width names the field, its declared size and what it got")
        void wrongWidth_failureMessageIsDiagnostic() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new SecUserRecord("ADMIN001", "GOLD", spaces(20), spaces(8),
                            "A", spaces(23)))
                    .withMessageContaining("SEC-USR-FNAME")
                    .withMessageContaining("20")
                    .withMessageContaining("4");
        }

        @Test
        @DisplayName("a null component is rejected naming the field it belongs to")
        void constructor_rejectsNullComponents() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new SecUserRecord(null, spaces(20), spaces(20), spaces(8), "A",
                            spaces(23)))
                    .withMessageContaining("SEC-USR-ID");
            assertThatNullPointerException()
                    .isThrownBy(() -> new SecUserRecord(spaces(8), spaces(20), spaces(20), spaces(8),
                            "A", null))
                    .withMessageContaining("SEC-USR-FILLER");
        }

        @ParameterizedTest(name = "{0} resolves")
        @ValueSource(strings = {"SEC-USR-ID", "SEC-USR-FNAME", "SEC-USR-LNAME", "SEC-USR-PWD",
                "SEC-USR-TYPE", "SEC-USR-FILLER"})
        @DisplayName("image resolves every one of the six COBOL field names")
        void image_resolvesEveryField(String cobolName) {
            SecUserRecord record = seedRecord(0, ASCII);

            assertThat(record.image(cobolName))
                    .isNotNull()
                    .isEqualTo(record.fieldImages().get(cobolName));
            assertThat(record.image(cobolName))
                    .hasSize(SecUserRecord.LAYOUT.span(cobolName).length());
        }

        @ParameterizedTest(name = "''{0}'' is rejected")
        @ValueSource(strings = {"SEC-USER-FNAME", "sec-usr-fname", "FILLER", "SEC_USR_ID",
                "SEC-USER-DATA", "SEC-USR-ID ", "", " "})
        @DisplayName("image rejects an unknown or misspelled name, never answering null")
        void image_rejectsUnknownNames(String unknown) {
            SecUserRecord record = SecUserRecord.blank();

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> record.image(unknown))
                    .withMessageContaining("SEC-USER-DATA");
        }

        @Test
        @DisplayName("image rejects a null field name")
        void image_rejectsNull() {
            SecUserRecord record = SecUserRecord.blank();

            assertThatNullPointerException().isThrownBy(() -> record.image(null));
        }

        @Test
        @DisplayName("identity covers all six components, plus the null and wrong-type paths")
        void identity_coversAllSixComponentsAndTheNullAndTypePaths() {
            FixedWidthCodec codec = new FixedWidthCodec(ASCII);
            SecUserRecord base = SecUserRecord.of("USER0001", "LAWRENCE", "THOMAS", SEED_PASSWORD,
                    "U", "", codec);

            assertThat(base).isEqualTo(base);
            assertThat(base).isEqualTo(SecUserRecord.of("USER0001", "LAWRENCE", "THOMAS",
                    SEED_PASSWORD, "U", "", codec));
            assertThat(base).hasSameHashCodeAs(SecUserRecord.of("USER0001", "LAWRENCE", "THOMAS",
                    SEED_PASSWORD, "U", "", codec));

            assertThat(base).isNotEqualTo(SecUserRecord.of("USER0002", "LAWRENCE", "THOMAS",
                    SEED_PASSWORD, "U", "", codec));
            assertThat(base).isNotEqualTo(SecUserRecord.of("USER0001", "LAURITZ", "THOMAS",
                    SEED_PASSWORD, "U", "", codec));
            assertThat(base).isNotEqualTo(SecUserRecord.of("USER0001", "LAWRENCE", "ALME",
                    SEED_PASSWORD, "U", "", codec));
            assertThat(base).as("the password is part of the record's identity")
                    .isNotEqualTo(SecUserRecord.of("USER0001", "LAWRENCE", "THOMAS", "OTHERPWD",
                            "U", "", codec));
            assertThat(base).isNotEqualTo(SecUserRecord.of("USER0001", "LAWRENCE", "THOMAS",
                    SEED_PASSWORD, "A", "", codec));
            assertThat(base).as("the filler is part of the record's identity")
                    .isNotEqualTo(SecUserRecord.of("USER0001", "LAWRENCE", "THOMAS", SEED_PASSWORD,
                            "U", "X".repeat(23), codec));

            assertThat(base).isNotEqualTo(null);
            assertThat(base).isNotEqualTo("SEC-USER-DATA");
        }

        @Test
        @DisplayName("the rendering withholds the password while the stored bytes stay reachable")
        void rendering_withholdsThePassword_withoutAlteringStoredBytes() {
            SecUserRecord record = seedRecord(0, ASCII);
            String rendered = record.toString();

            assertThat(rendered).doesNotContain(SEED_PASSWORD);
            assertThat(rendered).contains("SEC-USER-DATA");
            assertThat(rendered.lines()).as("a rendering is a single line").hasSize(1);

            assertThat(record.secUsrPwd()).isEqualTo(SEED_PASSWORD);
            assertThat(record.image("SEC-USR-PWD")).isEqualTo(SEED_PASSWORD);
            assertThat(record.fieldImages()).containsEntry("SEC-USR-PWD", SEED_PASSWORD);

            assertThat(SecUserRecord.of("USER0001", "LAWRENCE", "THOMAS", "Zq7!vX2b", "U", ASCII)
                    .toString()).doesNotContain("Zq7!vX2b");
        }

        @Test
        @DisplayName("the rendering withholds both names by shape while the key stays legible")
        void rendering_withholdsBothNames_butKeepsTheKeyLegible() {
            SecUserRecord record = SecUserRecord.of("USER0002", "ZQXVOLYA", "TREMBLAY-OKONKWO",
                    SEED_PASSWORD, "U", ASCII);
            String rendered = record.toString();

            assertThat(rendered).doesNotContain("ZQXVOLYA").doesNotContain("TREMBLAY-OKONKWO");
            assertThat(rendered).contains("USER0002");

            assertThat(record.secUsrFname()).isEqualTo(pad("ZQXVOLYA", 20));
            assertThat(record.secUsrLname()).isEqualTo(pad("TREMBLAY-OKONKWO", 20));
            assertThat(record.image("SEC-USR-FNAME")).isEqualTo(pad("ZQXVOLYA", 20));
            assertThat(record.image("SEC-USR-LNAME")).isEqualTo(pad("TREMBLAY-OKONKWO", 20));
        }

        @Test
        @DisplayName("a control character in a rendered span cannot forge a second log line")
        void rendering_cannotForgeASecondLogLine() {
            SecUserRecord record = SecUserRecord.of("A\r\nFAKE", "ANNA", "SMITH", SEED_PASSWORD,
                    "\n", ASCII);
            String rendered = record.toString();

            assertThat(rendered).doesNotContain("\r").doesNotContain("\n");
            assertThat(rendered.lines()).hasSize(1);

            assertThat(record.secUsrId()).isEqualTo("A\r\nFAKE ").hasSize(8);
            assertThat(record.secUsrType()).isEqualTo("\n").hasSize(1);
            assertThat(SecUserRecord.encode(record, ASCII)).hasSize(80);
        }
    }

    @Nested
    @DisplayName("Structural guarantees, and the exclusions recorded with their reasons")
    class StructuralGuarantees {
        @Test
        @DisplayName("G22 - no double or float appears in the model's surface")
        void noDoubleOrFloat_appearsInTheModelsSurface() {
            Set<Class<?>> forbidden = Set.of(double.class, float.class, Double.class, Float.class,
                    double[].class, float[].class);

            Set<Class<?>> declared = declaredTypesOf(SecUserRecord.class);
            assertThat(declared).isNotEmpty();
            assertThat(declared).doesNotContainAnyElementsOf(forbidden);

            RecordComponent[] components = SecUserRecord.class.getRecordComponents();
            assertThat(components).hasSize(6);
            for (RecordComponent component : components) {
                assertThat(component.getType()).isEqualTo(String.class);
            }
        }

        @Test
        @DisplayName("G23/G24 - no numeric field exists, so the decimal seam is correctly absent")
        void noNumericField_soTheDecimalSeamIsAbsent() {
            for (FieldSpan span : SecUserRecord.LAYOUT.storageSpans()) {
                assertThat(span.kind()).isEqualTo(PictureKind.ALPHANUMERIC);
                assertThat(span.kind().numericDisplay()).isFalse();
            }

            for (Class<?> type : declaredTypesOf(SecUserRecord.class)) {
                assertThat(type.getName())
                        .isNotEqualTo("java.math.BigDecimal")
                        .doesNotContain("CobolDecimal");
            }
        }

        @Test
        @DisplayName("G33/G34 - the layout has no OCCURS table and no REDEFINES overlay")
        void layoutHasNoOccursTableAndNoRedefinesOverlay() {
            assertThat(SecUserRecord.LAYOUT.redefinitions()).isEmpty();
            for (FieldSpan span : SecUserRecord.LAYOUT.storageSpans()) {
                assertThat(span.redefinition()).as(span.name()).isFalse();
            }

            assertThat(SecUserRecord.LAYOUT.storageSpans()).hasSize(6);
            assertThat(SecUserRecord.LAYOUT.storageSpans().stream().map(FieldSpan::name))
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("G44 - no persistence annotation, entity mapping or version column exists")
        void noPersistenceArtefact_isDeclaredOnTheType() {
            List<Annotation> annotations = new ArrayList<>();
            annotations.addAll(Arrays.asList(SecUserRecord.class.getAnnotations()));
            for (Field field : SecUserRecord.class.getDeclaredFields()) {
                annotations.addAll(Arrays.asList(field.getAnnotations()));
            }
            for (Method method : SecUserRecord.class.getDeclaredMethods()) {
                annotations.addAll(Arrays.asList(method.getAnnotations()));
            }
            for (Constructor<?> constructor : SecUserRecord.class.getDeclaredConstructors()) {
                annotations.addAll(Arrays.asList(constructor.getAnnotations()));
            }
            RecordComponent[] components = SecUserRecord.class.getRecordComponents();
            for (RecordComponent component : components) {
                annotations.addAll(Arrays.asList(component.getAnnotations()));
            }

            for (Annotation annotation : annotations) {
                String name = annotation.annotationType().getName();
                assertThat(name)
                        .as("annotation %s must not be a persistence mapping", name)
                        .doesNotContain("persistence")
                        .doesNotContain("hibernate")
                        .doesNotContain("javax.persistence")
                        .doesNotContain("jakarta.persistence");
            }

            for (RecordComponent component : components) {
                assertThat(component.getName().toLowerCase(Locale.ROOT)).doesNotContain("version");
            }
            assertThatExceptionOfType(ClassNotFoundException.class)
                    .isThrownBy(() -> Class.forName("jakarta.persistence.Entity"));
        }

        @Test
        @DisplayName("G46 - no dataset-name literal appears in the model's constants")
        void noDatasetNameLiteral_appearsInTheModelsConstants() throws IllegalAccessException {
            String highLevelQualifiers = "AWS" + ".M2.";
            String clusterSuffix = "VSAM" + ".KSDS";

            int inspected = 0;
            for (Field field : SecUserRecord.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                    field.setAccessible(true);
                    String value = (String) field.get(null);
                    assertThat(value)
                            .as("constant %s must not embed a dataset name", field.getName())
                            .doesNotContain(highLevelQualifiers)
                            .doesNotContain(clusterSuffix);
                    inspected++;
                }
            }
            assertThat(inspected)
                    .as("the seven COBOL name constants must actually have been inspected")
                    .isGreaterThanOrEqualTo(7);
        }

        @Test
        @DisplayName("G53 - every static field is final and every instance field is final")
        void noMutableStaticState_exists() {
            for (Field field : SecUserRecord.class.getDeclaredFields()) {
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("field %s must be final", field.getName())
                        .isTrue();
            }

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> SecUserRecord.SPANS.add(SecUserRecord.SPAN_SEC_USR_ID));
            assertThat(SecUserRecord.SPANS).hasSize(6);
        }

        @Test
        @DisplayName("B9 - two instances share no state, and each field-image map is fresh")
        void twoInstances_shareNothing() {
            SecUserRecord first = seedRecord(0, ASCII);
            SecUserRecord second = seedRecord(5, ASCII);

            assertThat(first).isNotSameAs(second).isNotEqualTo(second);
            assertThat(first.secUsrId()).isEqualTo("ADMIN001");
            assertThat(second.secUsrId()).isEqualTo("USER0001");

            Map<String, String> firstImages = first.fieldImages();
            assertThat(first.fieldImages()).isNotSameAs(firstImages);
            assertThat(second.fieldImages()).isNotSameAs(firstImages);
            assertThat(second.fieldImages()).isNotEqualTo(firstImages);

            assertThat(first.fieldImages()).isEqualTo(firstImages);
            assertThat(SecUserRecord.encode(first, ASCII))
                    .isEqualTo(recordBytes(SEED_CARDS.get(0), ASCII));
            assertThat(SecUserRecord.encode(second, ASCII))
                    .isEqualTo(recordBytes(SEED_CARDS.get(5), ASCII));
        }

        @Test
        @DisplayName("G8 - one type per copybook: six components, six descriptors, six images")
        void oneTypePerCopybook_withSixItemsThroughout() {
            assertThat(SecUserRecord.class.isRecord()).isTrue();
            assertThat(SecUserRecord.class.getRecordComponents()).hasSize(6);
            assertThat(SecUserRecord.SPANS).hasSize(6);
            assertThat(SecUserRecord.blank().fieldImages()).hasSize(6);

            assertThat(Arrays.stream(SecUserRecord.class.getRecordComponents())
                    .map(RecordComponent::getName))
                    .containsExactly("secUsrId", "secUsrFname", "secUsrLname", "secUsrPwd",
                            "secUsrType", "secUsrFiller");
        }
    }
}
