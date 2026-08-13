package com.vsergeychik.carddemo.statement.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tests for {@link Stm03CustomerRecord}, the single Java type for {@code app/cpy/CUSTREC.cpy}
 * ({@code 01 CUSTOMER-RECORD}, 500 bytes, nine-byte key) as {@code CBSTM03A} and {@code CBSTM03B} use it.
 */
@DisplayName("Stm03CustomerRecord - CUSTOMER-RECORD of CUSTREC.cpy, 500 bytes, 19 spans")
class Stm03CustomerRecordTest {
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    private static final Charset EBCDIC = Charset.forName("IBM037");

    private static final String ROW1_CUST_ID = "000000001";

    private static final String ROW1_FIRST_NAME = "Immanuel" + " ".repeat(17);

    private static final String ROW1_MIDDLE_NAME = "Madeline" + " ".repeat(17);

    private static final String ROW1_LAST_NAME = "Kessler" + " ".repeat(18);

    private static final String ROW1_ADDR_LINE_1 = "618 Deshaun Route" + " ".repeat(33);

    private static final String ROW1_ADDR_LINE_2 = "Apt. 802" + " ".repeat(42);

    private static final String ROW1_ADDR_LINE_3 = "Altenwerthshire" + " ".repeat(35);

    private static final String ROW1_STATE_CD = "NC";

    private static final String ROW1_COUNTRY_CD = "USA";

    private static final String ROW1_ZIP = "12546" + " ".repeat(5);

    private static final String ROW1_PHONE_1 = "(908)119-8310" + " ".repeat(2);

    private static final String ROW1_PHONE_2 = "(373)693-8684" + " ".repeat(2);

    private static final String ROW1_SSN = "020973888";

    private static final String ROW1_GOVT_ID = "00000000000049368437";

    private static final String ROW1_DOB = "1961-06-08";

    private static final String ROW1_EFT_ACCOUNT_ID = "0053581756";

    private static final String ROW1_PRI_CARD_HOLDER_IND = "Y";

    private static final String ROW1_FICO = "274";

    private static final String ROW1_FILLER = " ".repeat(168);

    private static final String ROW1_IMAGE = ROW1_CUST_ID
            + ROW1_FIRST_NAME
            + ROW1_MIDDLE_NAME
            + ROW1_LAST_NAME
            + ROW1_ADDR_LINE_1
            + ROW1_ADDR_LINE_2
            + ROW1_ADDR_LINE_3
            + ROW1_STATE_CD
            + ROW1_COUNTRY_CD
            + ROW1_ZIP
            + ROW1_PHONE_1
            + ROW1_PHONE_2
            + ROW1_SSN
            + ROW1_GOVT_ID
            + ROW1_DOB
            + ROW1_EFT_ACCOUNT_ID
            + ROW1_PRI_CARD_HOLDER_IND
            + ROW1_FICO
            + ROW1_FILLER;

    private static Stm03CustomerRecord row1() {
        return new Stm03CustomerRecord(ROW1_CUST_ID,
                ROW1_FIRST_NAME,
                ROW1_MIDDLE_NAME,
                ROW1_LAST_NAME,
                ROW1_ADDR_LINE_1,
                ROW1_ADDR_LINE_2,
                ROW1_ADDR_LINE_3,
                ROW1_STATE_CD,
                ROW1_COUNTRY_CD,
                ROW1_ZIP,
                ROW1_PHONE_1,
                ROW1_PHONE_2,
                ROW1_SSN,
                ROW1_GOVT_ID,
                ROW1_DOB,
                ROW1_EFT_ACCOUNT_ID,
                ROW1_PRI_CARD_HOLDER_IND,
                ROW1_FICO);
    }

    private static Stm03CustomerRecord row1Unpadded() {
        return new Stm03CustomerRecord("1",
                "Immanuel",
                "Madeline",
                "Kessler",
                "618 Deshaun Route",
                "Apt. 802",
                "Altenwerthshire",
                "NC",
                "USA",
                "12546",
                "(908)119-8310",
                "(373)693-8684",
                "20973888",
                "00000000000049368437",
                "1961-06-08",
                "0053581756",
                "Y",
                "274");
    }

    private static void assertSpan(FieldSpan span, String name, int offset, int length,
                                   PictureKind kind) {
        assertThat(span.name()).as("COBOL item name").isEqualTo(name);
        assertThat(span.offset()).as("%s offset", name).isEqualTo(offset);
        assertThat(span.length()).as("%s declared width", name).isEqualTo(length);
        assertThat(span.kind()).as("%s PICTURE category", name).isEqualTo(kind);
        assertThat(span.endOffsetExclusive())
                .as("%s ends where the next item begins", name)
                .isEqualTo(offset + length);
    }

    @Nested
    @DisplayName("1. Geometry - nineteen spans totalling 500 bytes, transcribed from CUSTREC.cpy")
    class Geometry {
        @Test
        @DisplayName("this test class's own transcription of fixture row 1 is 500 bytes")
        void theAssembledRowIsFiveHundredBytes() {
            assertThat(ROW1_CUST_ID).hasSize(9);
            assertThat(ROW1_FIRST_NAME).hasSize(25);
            assertThat(ROW1_MIDDLE_NAME).hasSize(25);
            assertThat(ROW1_LAST_NAME).hasSize(25);
            assertThat(ROW1_ADDR_LINE_1).hasSize(50);
            assertThat(ROW1_ADDR_LINE_2).hasSize(50);
            assertThat(ROW1_ADDR_LINE_3).hasSize(50);
            assertThat(ROW1_STATE_CD).hasSize(2);
            assertThat(ROW1_COUNTRY_CD).hasSize(3);
            assertThat(ROW1_ZIP).hasSize(10);
            assertThat(ROW1_PHONE_1).hasSize(15);
            assertThat(ROW1_PHONE_2).hasSize(15);
            assertThat(ROW1_SSN).hasSize(9);
            assertThat(ROW1_GOVT_ID).hasSize(20);
            assertThat(ROW1_DOB).hasSize(10);
            assertThat(ROW1_EFT_ACCOUNT_ID).hasSize(10);
            assertThat(ROW1_PRI_CARD_HOLDER_IND).hasSize(1);
            assertThat(ROW1_FICO).hasSize(3);
            assertThat(ROW1_FILLER).hasSize(168);
            assertThat(ROW1_IMAGE)
                    .as("app/data/ASCII/custdata.txt row 1, reassembled span by span")
                    .hasSize(500);
        }

        @Test
        @DisplayName("the declared record length is 500 and the layout holds all nineteen spans")
        void theDeclaredRecordLengthIsFiveHundred() {
            assertThat(Stm03CustomerRecord.RECORD_LENGTH)
                    .as("CUSTREC.cpy line 2 states RECLN 500, and CBSTM03B's FD split is 9 + 491")
                    .isEqualTo(500);
            assertThat(Stm03CustomerRecord.LAYOUT.recordLength()).isEqualTo(500);
            assertThat(Stm03CustomerRecord.LAYOUT.spans())
                    .as("18 referable items plus the trailing FILLER")
                    .hasSize(19);
            assertThat(Stm03CustomerRecord.LAYOUT.redefinitions()).isEmpty();
            assertThat(Stm03CustomerRecord.LAYOUT.storageSpans()).hasSize(19);
        }

        @Test
        @DisplayName("the CUSTFILE KSDS key is the leading nine bytes, which is CUST-ID")
        void theKeyIsTheLeadingNineBytes() {
            assertThat(Stm03CustomerRecord.KEY_OFFSET).isEqualTo(0);
            assertThat(Stm03CustomerRecord.KEY_LENGTH).isEqualTo(9);
            assertThat(Stm03CustomerRecord.KEY_OFFSET).isEqualTo(Stm03CustomerRecord.CUST_ID_OFFSET);
            assertThat(Stm03CustomerRecord.KEY_LENGTH).isEqualTo(Stm03CustomerRecord.CUST_ID_LENGTH);
        }

        @Test
        @DisplayName("spans 1 to 4: CUST-ID and the three name fields")
        void spansOneToFour() {
            assertThat(Stm03CustomerRecord.CUST_ID_OFFSET).isEqualTo(0);
            assertThat(Stm03CustomerRecord.CUST_ID_LENGTH).isEqualTo(9);
            assertSpan(Stm03CustomerRecord.CUST_ID, "CUST-ID", 0, 9, PictureKind.UNSIGNED_NUMERIC);

            assertThat(Stm03CustomerRecord.CUST_FIRST_NAME_OFFSET).isEqualTo(9);
            assertThat(Stm03CustomerRecord.CUST_FIRST_NAME_LENGTH).isEqualTo(25);
            assertSpan(Stm03CustomerRecord.CUST_FIRST_NAME, "CUST-FIRST-NAME", 9, 25,
                    PictureKind.ALPHANUMERIC);

            assertThat(Stm03CustomerRecord.CUST_MIDDLE_NAME_OFFSET).isEqualTo(34);
            assertThat(Stm03CustomerRecord.CUST_MIDDLE_NAME_LENGTH).isEqualTo(25);
            assertSpan(Stm03CustomerRecord.CUST_MIDDLE_NAME, "CUST-MIDDLE-NAME", 34, 25,
                    PictureKind.ALPHANUMERIC);

            assertThat(Stm03CustomerRecord.CUST_LAST_NAME_OFFSET).isEqualTo(59);
            assertThat(Stm03CustomerRecord.CUST_LAST_NAME_LENGTH).isEqualTo(25);
            assertSpan(Stm03CustomerRecord.CUST_LAST_NAME, "CUST-LAST-NAME", 59, 25,
                    PictureKind.ALPHANUMERIC);
        }

        @Test
        @DisplayName("spans 5 to 10: the three address lines, state, country and zip")
        void spansFiveToTen() {
            assertThat(Stm03CustomerRecord.CUST_ADDR_LINE_1_OFFSET).isEqualTo(84);
            assertThat(Stm03CustomerRecord.CUST_ADDR_LINE_1_LENGTH).isEqualTo(50);
            assertSpan(Stm03CustomerRecord.CUST_ADDR_LINE_1, "CUST-ADDR-LINE-1", 84, 50,
                    PictureKind.ALPHANUMERIC);

            assertThat(Stm03CustomerRecord.CUST_ADDR_LINE_2_OFFSET).isEqualTo(134);
            assertThat(Stm03CustomerRecord.CUST_ADDR_LINE_2_LENGTH).isEqualTo(50);
            assertSpan(Stm03CustomerRecord.CUST_ADDR_LINE_2, "CUST-ADDR-LINE-2", 134, 50,
                    PictureKind.ALPHANUMERIC);

            assertThat(Stm03CustomerRecord.CUST_ADDR_LINE_3_OFFSET).isEqualTo(184);
            assertThat(Stm03CustomerRecord.CUST_ADDR_LINE_3_LENGTH).isEqualTo(50);
            assertSpan(Stm03CustomerRecord.CUST_ADDR_LINE_3, "CUST-ADDR-LINE-3", 184, 50,
                    PictureKind.ALPHANUMERIC);

            assertThat(Stm03CustomerRecord.CUST_ADDR_STATE_CD_OFFSET).isEqualTo(234);
            assertThat(Stm03CustomerRecord.CUST_ADDR_STATE_CD_LENGTH).isEqualTo(2);
            assertSpan(Stm03CustomerRecord.CUST_ADDR_STATE_CD, "CUST-ADDR-STATE-CD", 234, 2,
                    PictureKind.ALPHANUMERIC);

            assertThat(Stm03CustomerRecord.CUST_ADDR_COUNTRY_CD_OFFSET).isEqualTo(236);
            assertThat(Stm03CustomerRecord.CUST_ADDR_COUNTRY_CD_LENGTH).isEqualTo(3);
            assertSpan(Stm03CustomerRecord.CUST_ADDR_COUNTRY_CD, "CUST-ADDR-COUNTRY-CD", 236, 3,
                    PictureKind.ALPHANUMERIC);

            assertThat(Stm03CustomerRecord.CUST_ADDR_ZIP_OFFSET).isEqualTo(239);
            assertThat(Stm03CustomerRecord.CUST_ADDR_ZIP_LENGTH).isEqualTo(10);
            assertSpan(Stm03CustomerRecord.CUST_ADDR_ZIP, "CUST-ADDR-ZIP", 239, 10,
                    PictureKind.ALPHANUMERIC);
        }

        @Test
        @DisplayName("spans 11 to 14: the two telephone numbers, the SSN and the government identifier")
        void spansElevenToFourteen() {
            assertThat(Stm03CustomerRecord.CUST_PHONE_NUM_1_OFFSET).isEqualTo(249);
            assertThat(Stm03CustomerRecord.CUST_PHONE_NUM_1_LENGTH).isEqualTo(15);
            assertSpan(Stm03CustomerRecord.CUST_PHONE_NUM_1, "CUST-PHONE-NUM-1", 249, 15,
                    PictureKind.ALPHANUMERIC);

            assertThat(Stm03CustomerRecord.CUST_PHONE_NUM_2_OFFSET).isEqualTo(264);
            assertThat(Stm03CustomerRecord.CUST_PHONE_NUM_2_LENGTH).isEqualTo(15);
            assertSpan(Stm03CustomerRecord.CUST_PHONE_NUM_2, "CUST-PHONE-NUM-2", 264, 15,
                    PictureKind.ALPHANUMERIC);

            assertThat(Stm03CustomerRecord.CUST_SSN_OFFSET).isEqualTo(279);
            assertThat(Stm03CustomerRecord.CUST_SSN_LENGTH).isEqualTo(9);
            assertSpan(Stm03CustomerRecord.CUST_SSN, "CUST-SSN", 279, 9,
                    PictureKind.UNSIGNED_NUMERIC);

            assertThat(Stm03CustomerRecord.CUST_GOVT_ISSUED_ID_OFFSET).isEqualTo(288);
            assertThat(Stm03CustomerRecord.CUST_GOVT_ISSUED_ID_LENGTH).isEqualTo(20);
            assertSpan(Stm03CustomerRecord.CUST_GOVT_ISSUED_ID, "CUST-GOVT-ISSUED-ID", 288, 20,
                    PictureKind.ALPHANUMERIC);
        }

        @Test
        @DisplayName("spans 15 to 18: date of birth, EFT account, card-holder indicator and FICO")
        void spansFifteenToEighteen() {
            assertThat(Stm03CustomerRecord.CUST_DOB_YYYYMMDD_OFFSET).isEqualTo(308);
            assertThat(Stm03CustomerRecord.CUST_DOB_YYYYMMDD_LENGTH).isEqualTo(10);
            assertSpan(Stm03CustomerRecord.CUST_DOB_YYYYMMDD, "CUST-DOB-YYYYMMDD", 308, 10,
                    PictureKind.ALPHANUMERIC);

            assertThat(Stm03CustomerRecord.CUST_EFT_ACCOUNT_ID_OFFSET).isEqualTo(318);
            assertThat(Stm03CustomerRecord.CUST_EFT_ACCOUNT_ID_LENGTH).isEqualTo(10);
            assertSpan(Stm03CustomerRecord.CUST_EFT_ACCOUNT_ID, "CUST-EFT-ACCOUNT-ID", 318, 10,
                    PictureKind.ALPHANUMERIC);

            assertThat(Stm03CustomerRecord.CUST_PRI_CARD_HOLDER_IND_OFFSET).isEqualTo(328);
            assertThat(Stm03CustomerRecord.CUST_PRI_CARD_HOLDER_IND_LENGTH).isEqualTo(1);
            assertSpan(Stm03CustomerRecord.CUST_PRI_CARD_HOLDER_IND, "CUST-PRI-CARD-HOLDER-IND", 328,
                    1, PictureKind.ALPHANUMERIC);

            assertThat(Stm03CustomerRecord.CUST_FICO_CREDIT_SCORE_OFFSET).isEqualTo(329);
            assertThat(Stm03CustomerRecord.CUST_FICO_CREDIT_SCORE_LENGTH).isEqualTo(3);
            assertSpan(Stm03CustomerRecord.CUST_FICO_CREDIT_SCORE, "CUST-FICO-CREDIT-SCORE", 329, 3,
                    PictureKind.UNSIGNED_NUMERIC);
        }

        @Test
        @DisplayName("span 19: FILLER PIC X(168) at offset 332, a declared span and not an implied gap")
        void spanNineteenIsFiller() {
            assertThat(Stm03CustomerRecord.FILLER_OFFSET).isEqualTo(332);
            assertThat(Stm03CustomerRecord.FILLER_LENGTH).isEqualTo(168);
            assertSpan(Stm03CustomerRecord.FILLER, "FILLER", 332, 168, PictureKind.FILLER);
            assertThat(Stm03CustomerRecord.FILLER.kind().filler()).isTrue();
            assertThat(Stm03CustomerRecord.LAYOUT.spans())
                    .as("FILLER is an entry in the layout, positioned and length-bearing")
                    .contains(Stm03CustomerRecord.FILLER);
            assertThat(Stm03CustomerRecord.LAYOUT.spans().get(18))
                    .as("and it is the last entry, closing the record at byte 500")
                    .isEqualTo(Stm03CustomerRecord.FILLER);
            assertThat(Stm03CustomerRecord.FILLER.hasInitialValue()).isFalse();
        }

        @Test
        @DisplayName("the nineteen declared widths sum to exactly 500")
        void theNineteenWidthsSumToFiveHundred() {
            int sum = 9 + 25 + 25 + 25 + 50 + 50 + 50 + 2 + 3 + 10 + 15 + 15 + 9 + 20 + 10 + 10 + 1
                    + 3 + 168;
            assertThat(sum).isEqualTo(500);

            int declared = 0;
            for (FieldSpan span : Stm03CustomerRecord.LAYOUT.storageSpans()) {
                declared += span.length();
            }
            assertThat(declared)
                    .as("the type's own spans must account for every one of the 500 bytes")
                    .isEqualTo(500);
        }

        @Test
        @DisplayName("every span begins exactly where the previous one ended, from 0 through to 500")
        void everySpanBeginsWhereThePreviousOneEnded() {
            assertThat(0 + 9).isEqualTo(9);
            assertThat(9 + 25).isEqualTo(34);
            assertThat(34 + 25).isEqualTo(59);
            assertThat(59 + 25).isEqualTo(84);
            assertThat(84 + 50).isEqualTo(134);
            assertThat(134 + 50).isEqualTo(184);
            assertThat(184 + 50).isEqualTo(234);
            assertThat(234 + 2).isEqualTo(236);
            assertThat(236 + 3).isEqualTo(239);
            assertThat(239 + 10).isEqualTo(249);
            assertThat(249 + 15).isEqualTo(264);
            assertThat(264 + 15).isEqualTo(279);
            assertThat(279 + 9).isEqualTo(288);
            assertThat(288 + 20).isEqualTo(308);
            assertThat(308 + 10).isEqualTo(318);
            assertThat(318 + 10).isEqualTo(328);
            assertThat(328 + 1).isEqualTo(329);
            assertThat(329 + 3).isEqualTo(332);
            assertThat(332 + 168)
                    .as("the trailing FILLER closes the record exactly at its declared width")
                    .isEqualTo(500);

            List<FieldSpan> spans = Stm03CustomerRecord.LAYOUT.storageSpans();
            int expectedOffset = 0;
            for (FieldSpan span : spans) {
                assertThat(span.offset())
                        .as("%s must begin at %d", span.name(), expectedOffset)
                        .isEqualTo(expectedOffset);
                expectedOffset += span.length();
            }
            assertThat(expectedOffset).isEqualTo(500);
        }

        @Test
        @DisplayName("dropping FILLER from the layout fails immediately, naming the 168-byte shortfall")
        void droppingFillerFailsImmediately() {
            FieldSpan[] withoutFiller = Stm03CustomerRecord.LAYOUT.storageSpans()
                    .subList(0, 18)
                    .toArray(new FieldSpan[0]);
            assertThat(withoutFiller).hasSize(18);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(500, withoutFiller))
                    .withMessageContainingAll("332", "500", "168", "FILLER");

            assertThat(500 - Stm03CustomerRecord.FILLER_OFFSET)
                    .as("the eighteen referable items reach byte 332, so FILLER supplies the rest")
                    .isEqualTo(168);
        }
    }

    @Nested
    @DisplayName("2. FILLER is emitted as 168 spaces, always")
    class Filler {
        @Test
        @DisplayName("an initialised record blanks FILLER and zero-fills only the numeric spans")
        void anInitialisedRecordBlanksFiller() {
            Stm03CustomerRecord blank = Stm03CustomerRecord.blank(ASCII);

            assertThat(blank.groupImage(ASCII)).hasSize(500);
            assertThat(blank.spanImage(Stm03CustomerRecord.FILLER, ASCII))
                    .as("FILLER carries no VALUE clause, so it initialises to spaces")
                    .isEqualTo(" ".repeat(168));
            assertThat(blank.custId()).isEqualTo("000000000");
            assertThat(blank.custSsn()).isEqualTo("000000000");
            assertThat(blank.custFicoCreditScore()).isEqualTo("000");
            assertThat(blank.custFirstName()).isEqualTo(" ".repeat(25));
            assertThat(blank.custDobYyyymmdd()).isEqualTo(" ".repeat(10));
        }

        @Test
        @DisplayName("FILLER is still 168 spaces after every one of the eighteen fields is populated")
        void fillerSurvivesEveryFieldBeingWritten() {
            String filler = row1().spanImage(Stm03CustomerRecord.FILLER, ASCII);

            assertThat(filler).hasSize(168).isEqualTo(" ".repeat(168));
        }

        @Test
        @DisplayName("bytes 332 to 499 of the encoded image are spaces under both code pages")
        void theTrailingBytesOfTheImageAreSpaces() {
            assertThat(new String(row1().encode(ASCII), ASCII).substring(332))
                    .hasSize(168)
                    .isEqualTo(" ".repeat(168));
            assertThat(new String(row1().encode(EBCDIC), EBCDIC).substring(332))
                    .as("the same 168 spaces, this time as EBCDIC 0x40 bytes")
                    .hasSize(168)
                    .isEqualTo(" ".repeat(168));
        }

        @Test
        @DisplayName("FILLER is readable through its descriptor but is not a comparable field")
        void fillerIsReadableButNotComparable() {
            Stm03CustomerRecord record = row1();

            assertThat(record.spanBytes(Stm03CustomerRecord.FILLER, ASCII)).hasSize(168);
            assertThat(record.fieldImages(ASCII))
                    .as("eighteen referable items, FILLER excluded")
                    .hasSize(18)
                    .doesNotContainKey("FILLER");
        }
    }

    @Nested
    @DisplayName("3. Type hygiene - what this record deliberately is not")
    class TypeHygiene {
        @Test
        @DisplayName("no persistence annotation anywhere: this is a copybook record, not an entity")
        void noPersistenceAnnotationAnywhere() {
            List<String> forbidden = List.of("Entity", "Table", "Id", "Column", "GeneratedValue",
                    "Version", "Embeddable", "MappedSuperclass");
            List<String> found = new ArrayList<>();

            for (Annotation annotation : Stm03CustomerRecord.class.getAnnotations()) {
                if (forbidden.contains(annotation.annotationType().getSimpleName())) {
                    found.add("class: " + annotation.annotationType().getName());
                }
            }
            for (Field field : Stm03CustomerRecord.class.getDeclaredFields()) {
                for (Annotation annotation : field.getAnnotations()) {
                    if (forbidden.contains(annotation.annotationType().getSimpleName())) {
                        found.add(field.getName() + ": " + annotation.annotationType().getName());
                    }
                }
            }
            for (Method method : Stm03CustomerRecord.class.getDeclaredMethods()) {
                for (Annotation annotation : method.getAnnotations()) {
                    if (forbidden.contains(annotation.annotationType().getSimpleName())) {
                        found.add(method.getName() + ": " + annotation.annotationType().getName());
                    }
                }
            }

            assertThat(found)
                    .as("no DDL, no ORM mapping and no version column belong on a VSAM record")
                    .isEmpty();
        }

        @Test
        @DisplayName("no double and no float: every numeric here is an exact zoned DISPLAY integer")
        void noBinaryFloatingPointAnywhere() {
            List<Class<?>> banned = List.of(double.class, float.class, Double.class, Float.class);
            List<String> offenders = new ArrayList<>();

            for (Field field : Stm03CustomerRecord.class.getDeclaredFields()) {
                if (banned.contains(field.getType())) {
                    offenders.add("field " + field.getName() + " is " + field.getType().getName());
                }
            }
            for (Method method : Stm03CustomerRecord.class.getDeclaredMethods()) {
                if (banned.contains(method.getReturnType())) {
                    offenders.add("method " + method.getName() + " returns "
                            + method.getReturnType().getName());
                }
                for (Class<?> parameter : method.getParameterTypes()) {
                    if (banned.contains(parameter)) {
                        offenders.add("method " + method.getName() + " takes "
                                + parameter.getName());
                    }
                }
            }

            assertThat(offenders).isEmpty();
        }

        @Test
        @DisplayName("nothing scaled and nothing rounded: no BigDecimal, no RoundingMode, no decimal helper")
        void noScaledArithmeticAnywhere() {
            List<String> bannedTypeNames = List.of("java.math.BigDecimal", "java.math.RoundingMode",
                    "com.vsergeychik.carddemo.common.CobolDecimal");
            List<String> offenders = new ArrayList<>();

            for (Field field : Stm03CustomerRecord.class.getDeclaredFields()) {
                if (bannedTypeNames.contains(field.getType().getName())) {
                    offenders.add("field " + field.getName());
                }
            }
            for (Method method : Stm03CustomerRecord.class.getDeclaredMethods()) {
                if (bannedTypeNames.contains(method.getReturnType().getName())) {
                    offenders.add("method " + method.getName() + " returns "
                            + method.getReturnType().getName());
                }
                for (Class<?> parameter : method.getParameterTypes()) {
                    if (bannedTypeNames.contains(parameter.getName())) {
                        offenders.add("method " + method.getName() + " takes " + parameter.getName());
                    }
                }
            }

            assertThat(offenders)
                    .as("no signed and no V-scaled picture exists in CUSTREC.cpy, so none of these "
                            + "types has anything to do here")
                    .isEmpty();
        }

        @Test
        @DisplayName("every static field is final: a record area is per-instance state, never shared")
        void everyStaticFieldIsFinal() {
            List<String> mutable = new ArrayList<>();
            for (Field field : Stm03CustomerRecord.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        && !Modifier.isFinal(field.getModifiers())) {
                    mutable.add(field.getName());
                }
            }

            assertThat(mutable).isEmpty();
        }

        @Test
        @DisplayName("the three numeric views are int, because nine digits fit an int")
        void theNumericViewsAreInt() throws NoSuchMethodException {
            assertThat(Stm03CustomerRecord.class.getMethod("custIdValue", Charset.class)
                    .getReturnType()).isEqualTo(int.class);
            assertThat(Stm03CustomerRecord.class.getMethod("custSsnValue", Charset.class)
                    .getReturnType()).isEqualTo(int.class);
            assertThat(Stm03CustomerRecord.class.getMethod("custFicoCreditScoreValue", Charset.class)
                    .getReturnType()).isEqualTo(int.class);
            assertThat(999_999_999).isLessThan(Integer.MAX_VALUE);
        }

        @Test
        @DisplayName("the diagnostic rendering names all eighteen fields and discloses no identity")
        void theDiagnosticRenderingWithholdsTheIdentity() {
            String rendered = row1().toString();

            assertThat(rendered).contains("CUST-DOB-YYYYMMDD", "CUST-SSN", "CUST-GOVT-ISSUED-ID");
            assertThat(rendered)
                    .as("the SSN, the government identifier and the EFT account are not rendered")
                    .doesNotContain(ROW1_SSN, ROW1_GOVT_ID, ROW1_EFT_ACCOUNT_ID);
            assertThat(rendered)
                    .as("nor is the customer's name or date of birth")
                    .doesNotContain("Immanuel", "Kessler", ROW1_DOB);
            assertThat(row1().fieldImages(ASCII))
                    .as("but the values themselves are still reachable by name")
                    .containsEntry("CUST-SSN", ROW1_SSN);
        }
    }

    @Nested
    @DisplayName("4. Fixture row 1 - all nineteen spans, decoded and re-encoded byte-identically")
    class FixtureRowOne {
        @Test
        @DisplayName("decoding row 1 recovers every one of the eighteen referable fields")
        void decodingRowOneRecoversEveryField() {
            Stm03CustomerRecord record = Stm03CustomerRecord.decode(ROW1_IMAGE, ASCII);

            assertThat(record.custId()).isEqualTo("000000001");
            assertThat(record.custFirstName()).isEqualTo("Immanuel" + " ".repeat(17));
            assertThat(record.custMiddleName()).isEqualTo("Madeline" + " ".repeat(17));
            assertThat(record.custLastName()).isEqualTo("Kessler" + " ".repeat(18));
            assertThat(record.custAddrLine1()).isEqualTo("618 Deshaun Route" + " ".repeat(33));
            assertThat(record.custAddrLine2()).isEqualTo("Apt. 802" + " ".repeat(42));
            assertThat(record.custAddrLine3()).isEqualTo("Altenwerthshire" + " ".repeat(35));
            assertThat(record.custAddrStateCd()).isEqualTo("NC");
            assertThat(record.custAddrCountryCd()).isEqualTo("USA");
            assertThat(record.custAddrZip()).isEqualTo("12546" + " ".repeat(5));
            assertThat(record.custPhoneNum1()).isEqualTo("(908)119-8310" + " ".repeat(2));
            assertThat(record.custPhoneNum2()).isEqualTo("(373)693-8684" + " ".repeat(2));
            assertThat(record.custSsn()).isEqualTo("020973888");
            assertThat(record.custGovtIssuedId()).isEqualTo("00000000000049368437");
            assertThat(record.custDobYyyymmdd())
                    .as("the field name is un-hyphenated but the ten stored bytes are hyphenated, and "
                            + "both are preserved exactly as they are")
                    .isEqualTo("1961-06-08");
            assertThat(record.custEftAccountId()).isEqualTo("0053581756");
            assertThat(record.custPriCardHolderInd()).isEqualTo("Y");
            assertThat(record.custFicoCreditScore()).isEqualTo("274");
        }

        @Test
        @DisplayName("span 19 decodes too: FILLER is 168 spaces in the real fixture row")
        void theFillerSpanDecodesAsSpaces() {
            Stm03CustomerRecord record = Stm03CustomerRecord.decode(ROW1_IMAGE, ASCII);

            assertThat(record.spanImage(Stm03CustomerRecord.FILLER, ASCII))
                    .hasSize(168)
                    .isEqualTo(" ".repeat(168));
        }

        @Test
        @DisplayName("decode then encode is byte-identical to the fixture row")
        void decodeThenEncodeIsByteIdentical() {
            byte[] expected = ROW1_IMAGE.getBytes(ASCII);
            assertThat(expected).as("the transcription is 500 bytes under US-ASCII").hasSize(500);

            byte[] actual = Stm03CustomerRecord.decode(ROW1_IMAGE, ASCII).encode(ASCII);

            assertThat(actual).hasSize(500).isEqualTo(expected);
        }

        @Test
        @DisplayName("the group image is the same 500 characters, read in one piece")
        void theGroupImageIsTheSameFiveHundredCharacters() {
            assertThat(Stm03CustomerRecord.decode(ROW1_IMAGE, ASCII).groupImage(ASCII))
                    .hasSize(500)
                    .isEqualTo(ROW1_IMAGE);
            assertThat(row1().groupImage(ASCII)).isEqualTo(ROW1_IMAGE);
        }

        @Test
        @DisplayName("the same row round-trips through IBM037, so no platform default can leak in")
        void theSameRowRoundTripsThroughEbcdic() {
            byte[] ebcdic = row1().encode(EBCDIC);
            assertThat(ebcdic).hasSize(500);

            Stm03CustomerRecord recovered = Stm03CustomerRecord.decode(ebcdic, EBCDIC);

            assertThat(recovered).isEqualTo(row1());
            assertThat(recovered.custDobYyyymmdd()).isEqualTo("1961-06-08");
            assertThat(recovered.custSsn()).isEqualTo("020973888");
            assertThat(recovered.spanImage(Stm03CustomerRecord.FILLER, EBCDIC))
                    .isEqualTo(" ".repeat(168));
            assertThat(ebcdic).isNotEqualTo(row1().encode(ASCII));
            assertThat(ebcdic[0])
                    .as("EBCDIC digit zero is 0xF0, ASCII digit zero is 0x30")
                    .isEqualTo((byte) 0xF0);
            assertThat(row1().encode(ASCII)[0]).isEqualTo((byte) 0x30);
        }

        @Test
        @DisplayName("the field map is keyed by the copybook's own eighteen names")
        void theFieldMapIsKeyedByCopybookNames() {
            Map<String, String> images = Stm03CustomerRecord.decode(ROW1_IMAGE, ASCII)
                    .fieldImages(ASCII);

            assertThat(images).hasSize(18);
            assertThat(images.keySet()).containsExactly("CUST-ID",
                    "CUST-FIRST-NAME",
                    "CUST-MIDDLE-NAME",
                    "CUST-LAST-NAME",
                    "CUST-ADDR-LINE-1",
                    "CUST-ADDR-LINE-2",
                    "CUST-ADDR-LINE-3",
                    "CUST-ADDR-STATE-CD",
                    "CUST-ADDR-COUNTRY-CD",
                    "CUST-ADDR-ZIP",
                    "CUST-PHONE-NUM-1",
                    "CUST-PHONE-NUM-2",
                    "CUST-SSN",
                    "CUST-GOVT-ISSUED-ID",
                    "CUST-DOB-YYYYMMDD",
                    "CUST-EFT-ACCOUNT-ID",
                    "CUST-PRI-CARD-HOLDER-IND",
                    "CUST-FICO-CREDIT-SCORE");
            assertThat(images).containsEntry("CUST-FICO-CREDIT-SCORE", "274");
        }

        @Test
        @DisplayName("writing the fields at their natural widths reaches the identical 500 bytes")
        void naturalWidthsReachTheIdenticalBytes() {
            assertThat(row1Unpadded()).isEqualTo(row1());
            assertThat(row1Unpadded()).hasSameHashCodeAs(row1());
            assertThat(row1Unpadded().encode(ASCII)).isEqualTo(ROW1_IMAGE.getBytes(ASCII));
            assertThat(row1Unpadded().groupImage(ASCII)).isEqualTo(ROW1_IMAGE);
        }
    }

    @Nested
    @DisplayName("5. Spans are returned untrimmed - trimming belongs to the consumer")
    class UntrimmedSpans {
        @Test
        @DisplayName("every PIC X accessor returns its full declared width, padding included")
        void everyAlphanumericAccessorReturnsItsFullWidth() {
            Stm03CustomerRecord record = Stm03CustomerRecord.decode(ROW1_IMAGE, ASCII);

            assertThat(record.custFirstName()).hasSize(25).isEqualTo("Immanuel" + " ".repeat(17));
            assertThat(record.custMiddleName()).hasSize(25).isEqualTo("Madeline" + " ".repeat(17));
            assertThat(record.custLastName()).hasSize(25).isEqualTo("Kessler" + " ".repeat(18));
            assertThat(record.custAddrLine1()).hasSize(50);
            assertThat(record.custAddrLine2()).hasSize(50);
            assertThat(record.custAddrLine3()).hasSize(50);
            assertThat(record.custAddrStateCd()).hasSize(2);
            assertThat(record.custAddrCountryCd()).hasSize(3);
            assertThat(record.custAddrZip()).hasSize(10).isEqualTo("12546" + " ".repeat(5));
            assertThat(record.custPhoneNum1()).hasSize(15);
            assertThat(record.custPhoneNum2()).hasSize(15);
            assertThat(record.custGovtIssuedId()).hasSize(20);
            assertThat(record.custDobYyyymmdd()).hasSize(10);
            assertThat(record.custEftAccountId()).hasSize(10);
            assertThat(record.custPriCardHolderInd()).hasSize(1);
        }

        @Test
        @DisplayName("the three PIC 9 accessors also return their full declared width")
        void everyNumericAccessorReturnsItsFullWidth() {
            Stm03CustomerRecord record = Stm03CustomerRecord.decode(ROW1_IMAGE, ASCII);

            assertThat(record.custId()).hasSize(9);
            assertThat(record.custSsn()).hasSize(9);
            assertThat(record.custFicoCreditScore()).hasSize(3);
        }

        @Test
        @DisplayName("a trailing-space name is not silently equal to its trimmed form")
        void aPaddedNameIsNotItsTrimmedForm() {
            String first = Stm03CustomerRecord.decode(ROW1_IMAGE, ASCII).custFirstName();

            assertThat(first).isNotEqualTo("Immanuel");
            assertThat(first.strip())
                    .as("the consumer trims, exactly as STRING ... DELIMITED BY ' ' does")
                    .isEqualTo("Immanuel");
        }
    }

    @Nested
    @DisplayName("6. The two MOVE directions - PIC X pads and truncates right, PIC 9 left")
    class MoveDirections {
        @Test
        @DisplayName("a short PIC X value is right-space-padded to its declared width")
        void aShortAlphanumericIsRightPadded() {
            assertThat(row1Unpadded().custFirstName()).isEqualTo("Immanuel" + " ".repeat(17));
            assertThat(row1Unpadded().custLastName()).isEqualTo("Kessler" + " ".repeat(18));
            assertThat(row1Unpadded().custAddrZip()).isEqualTo("12546" + " ".repeat(5));
            assertThat(row1Unpadded().custAddrStateCd()).isEqualTo("NC");
            assertThat(row1Unpadded().custAddrCountryCd()).isEqualTo("USA");
        }

        @Test
        @DisplayName("an over-wide PIC X value is truncated on the RIGHT")
        void anOverWideAlphanumericIsTruncatedRight() {
            String survivingFifty = "618 Deshaun Route, Building 12, Suite 400, Altenwe";
            String discardedTen = "rthshire, ";
            assertThat(survivingFifty).hasSize(50);
            assertThat(discardedTen).hasSize(10);
            String sixtyCharacters = survivingFifty + discardedTen;
            assertThat(sixtyCharacters).hasSize(60);

            Stm03CustomerRecord record = new Stm03CustomerRecord(ROW1_CUST_ID,
                    ROW1_FIRST_NAME, ROW1_MIDDLE_NAME, ROW1_LAST_NAME,
                    sixtyCharacters,
                    ROW1_ADDR_LINE_2, ROW1_ADDR_LINE_3,
                    "North Carolina", "United States",
                    ROW1_ZIP, ROW1_PHONE_1, ROW1_PHONE_2, ROW1_SSN, ROW1_GOVT_ID, ROW1_DOB,
                    ROW1_EFT_ACCOUNT_ID, ROW1_PRI_CARD_HOLDER_IND, ROW1_FICO);

            assertThat(record.custAddrLine1())
                    .as("the leading fifty survive; the ten that follow are discarded on the right")
                    .hasSize(50)
                    .isEqualTo(survivingFifty)
                    .doesNotContain(discardedTen);
            assertThat(record.custAddrStateCd())
                    .as("PIC X(02) keeps the first two characters of 'North Carolina'")
                    .isEqualTo("No");
            assertThat(record.custAddrCountryCd())
                    .as("PIC X(03) keeps the first three characters of 'United States'")
                    .isEqualTo("Uni");
        }

        @ParameterizedTest(name = "CUST-PRI-CARD-HOLDER-IND receives [{0}] and holds [{1}]")
        @DisplayName("PIC X(01) round-trips one character, blanks an empty value and keeps the first")
        @CsvSource(nullValues = "NIL", value = {
            "Y, Y",
            "N, N",
            "'', ' '",
            "YN, Y",
            "'  ', ' '",
        })
        void theSingleCharacterIndicator(String supplied, String expected) {
            Stm03CustomerRecord record = new Stm03CustomerRecord(ROW1_CUST_ID,
                    ROW1_FIRST_NAME, ROW1_MIDDLE_NAME, ROW1_LAST_NAME, ROW1_ADDR_LINE_1,
                    ROW1_ADDR_LINE_2, ROW1_ADDR_LINE_3, ROW1_STATE_CD, ROW1_COUNTRY_CD, ROW1_ZIP,
                    ROW1_PHONE_1, ROW1_PHONE_2, ROW1_SSN, ROW1_GOVT_ID, ROW1_DOB,
                    ROW1_EFT_ACCOUNT_ID,
                    supplied,
                    ROW1_FICO);

            assertThat(record.custPriCardHolderInd()).hasSize(1).isEqualTo(expected);
        }

        @ParameterizedTest(name = "CUST-ID receives [{0}] and holds [{1}]")
        @DisplayName("PIC 9(09) is zero-filled on the LEFT and truncated on the LEFT")
        @CsvSource({
            "1, 000000001",
            "42, 000000042",
            "20973888, 020973888",
            "999999999, 999999999",
            "1234567890, 234567890",
            "10000000001, 000000001",
        })
        void theNineDigitKey(String supplied, String expected) {
            Stm03CustomerRecord record = new Stm03CustomerRecord(supplied,
                    ROW1_FIRST_NAME, ROW1_MIDDLE_NAME, ROW1_LAST_NAME, ROW1_ADDR_LINE_1,
                    ROW1_ADDR_LINE_2, ROW1_ADDR_LINE_3, ROW1_STATE_CD, ROW1_COUNTRY_CD, ROW1_ZIP,
                    ROW1_PHONE_1, ROW1_PHONE_2, ROW1_SSN, ROW1_GOVT_ID, ROW1_DOB,
                    ROW1_EFT_ACCOUNT_ID, ROW1_PRI_CARD_HOLDER_IND, ROW1_FICO);

            assertThat(record.custId()).hasSize(9).isEqualTo(expected);
        }

        @ParameterizedTest(name = "CUST-FICO-CREDIT-SCORE receives [{0}] and holds [{1}]")
        @DisplayName("PIC 9(03) follows the same left-hand rules in three bytes")
        @CsvSource({
            "274, 274",
            "7, 007",
            "0, 000",
            "1234, 234",
            "999, 999",
        })
        void theThreeDigitScore(String supplied, String expected) {
            Stm03CustomerRecord record = new Stm03CustomerRecord(ROW1_CUST_ID,
                    ROW1_FIRST_NAME, ROW1_MIDDLE_NAME, ROW1_LAST_NAME, ROW1_ADDR_LINE_1,
                    ROW1_ADDR_LINE_2, ROW1_ADDR_LINE_3, ROW1_STATE_CD, ROW1_COUNTRY_CD, ROW1_ZIP,
                    ROW1_PHONE_1, ROW1_PHONE_2, ROW1_SSN, ROW1_GOVT_ID, ROW1_DOB,
                    ROW1_EFT_ACCOUNT_ID, ROW1_PRI_CARD_HOLDER_IND,
                    supplied);

            assertThat(record.custFicoCreditScore()).hasSize(3).isEqualTo(expected);
        }

        @Test
        @DisplayName("the SSN keeps the leading zero the fixture stores, and the int view drops it")
        void theSsnKeepsItsLeadingZero() {
            Stm03CustomerRecord record = row1Unpadded();

            assertThat(record.custSsn()).isEqualTo("020973888");
            assertThat(record.custSsnValue(ASCII)).isEqualTo(20973888);
            assertThat(record.custIdValue(ASCII)).isEqualTo(1);
            assertThat(record.custFicoCreditScoreValue(ASCII)).isEqualTo(274);
        }

        @Test
        @DisplayName("a null field image is refused, naming the field and what to pass instead")
        void aNullFieldImageIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new Stm03CustomerRecord(null,
                            ROW1_FIRST_NAME, ROW1_MIDDLE_NAME, ROW1_LAST_NAME, ROW1_ADDR_LINE_1,
                            ROW1_ADDR_LINE_2, ROW1_ADDR_LINE_3, ROW1_STATE_CD, ROW1_COUNTRY_CD,
                            ROW1_ZIP, ROW1_PHONE_1, ROW1_PHONE_2, ROW1_SSN, ROW1_GOVT_ID, ROW1_DOB,
                            ROW1_EFT_ACCOUNT_ID, ROW1_PRI_CARD_HOLDER_IND, ROW1_FICO))
                    .withMessageContainingAll("CUST-ID", "spaces or an empty string");
            assertThatNullPointerException()
                    .isThrownBy(() -> new Stm03CustomerRecord(ROW1_CUST_ID,
                            ROW1_FIRST_NAME, ROW1_MIDDLE_NAME, ROW1_LAST_NAME, ROW1_ADDR_LINE_1,
                            ROW1_ADDR_LINE_2, ROW1_ADDR_LINE_3, ROW1_STATE_CD, ROW1_COUNTRY_CD,
                            ROW1_ZIP, ROW1_PHONE_1, ROW1_PHONE_2, ROW1_SSN, ROW1_GOVT_ID, null,
                            ROW1_EFT_ACCOUNT_ID, ROW1_PRI_CARD_HOLDER_IND, ROW1_FICO))
                    .withMessageContaining("CUST-DOB-YYYYMMDD");
            assertThatNullPointerException()
                    .isThrownBy(() -> new Stm03CustomerRecord(ROW1_CUST_ID,
                            ROW1_FIRST_NAME, ROW1_MIDDLE_NAME, ROW1_LAST_NAME, ROW1_ADDR_LINE_1,
                            ROW1_ADDR_LINE_2, ROW1_ADDR_LINE_3, ROW1_STATE_CD, ROW1_COUNTRY_CD,
                            ROW1_ZIP, ROW1_PHONE_1, ROW1_PHONE_2, ROW1_SSN, ROW1_GOVT_ID, ROW1_DOB,
                            ROW1_EFT_ACCOUNT_ID, ROW1_PRI_CARD_HOLDER_IND, null))
                    .withMessageContaining("CUST-FICO-CREDIT-SCORE");
        }
    }

    @Nested
    @DisplayName("7. One span, two views - the equivalence that stands in for REDEFINES here")
    class SpanAndFieldEquivalence {
        @Test
        @DisplayName("the nine-byte key span and the CUST-ID field are two views of the same bytes")
        void theKeySpanAndTheFieldAreOneStorage() {
            Stm03CustomerRecord record = row1();

            assertThat(record.spanImage(Stm03CustomerRecord.CUST_ID, ASCII))
                    .isEqualTo(record.custId())
                    .isEqualTo("000000001");
            assertThat(record.groupImage(ASCII).substring(Stm03CustomerRecord.KEY_OFFSET,
                            Stm03CustomerRecord.KEY_OFFSET + Stm03CustomerRecord.KEY_LENGTH))
                    .isEqualTo("000000001");
            String reKeyed = "000000042" + ROW1_IMAGE.substring(9);
            assertThat(reKeyed).hasSize(500);

            Stm03CustomerRecord rekeyed = Stm03CustomerRecord.decode(reKeyed, ASCII);

            assertThat(rekeyed.custId()).isEqualTo("000000042");
            assertThat(rekeyed.custIdValue(ASCII)).isEqualTo(42);
            assertThat(rekeyed.spanImage(Stm03CustomerRecord.CUST_ID, ASCII))
                    .isEqualTo("000000042");
            assertThat(rekeyed.custLastName())
                    .as("and nothing outside the key moved")
                    .isEqualTo("Kessler" + " ".repeat(18));
        }

        @Test
        @DisplayName("a sub-span over offsets 9 to 83 round-trips and is visible through three fields")
        void aSubSpanOverTheThreeNamesRoundTrips() {
            assertThat(9 + 25 + 25 + 25).isEqualTo(84);

            String extracted = row1().groupImage(ASCII).substring(9, 84);

            assertThat(extracted).hasSize(75);
            assertThat(extracted)
                    .isEqualTo(ROW1_FIRST_NAME + ROW1_MIDDLE_NAME + ROW1_LAST_NAME);

            String replacement = "Grace" + " ".repeat(20)
                    + "Brewster" + " ".repeat(17)
                    + "Hopper" + " ".repeat(19);
            assertThat(replacement).hasSize(75);

            String replaced = ROW1_IMAGE.substring(0, 9) + replacement + ROW1_IMAGE.substring(84);
            assertThat(replaced).hasSize(500);

            Stm03CustomerRecord renamed = Stm03CustomerRecord.decode(replaced, ASCII);

            assertThat(renamed.custFirstName()).isEqualTo("Grace" + " ".repeat(20));
            assertThat(renamed.custMiddleName()).isEqualTo("Brewster" + " ".repeat(17));
            assertThat(renamed.custLastName()).isEqualTo("Hopper" + " ".repeat(19));
            assertThat(renamed.encode(ASCII)).isEqualTo(replaced.getBytes(ASCII));
            assertThat(renamed.custId()).isEqualTo("000000001");
            assertThat(renamed.custAddrLine1()).isEqualTo(ROW1_ADDR_LINE_1);
        }

        @Test
        @DisplayName("every one of the eighteen fields agrees with its own span, both ways")
        void everyFieldAgreesWithItsSpan() {
            Stm03CustomerRecord record = row1();
            Map<String, String> images = record.fieldImages(ASCII);

            assertThat(images).containsEntry("CUST-ID", record.custId());
            assertThat(images).containsEntry("CUST-FIRST-NAME", record.custFirstName());
            assertThat(images).containsEntry("CUST-MIDDLE-NAME", record.custMiddleName());
            assertThat(images).containsEntry("CUST-LAST-NAME", record.custLastName());
            assertThat(images).containsEntry("CUST-ADDR-LINE-1", record.custAddrLine1());
            assertThat(images).containsEntry("CUST-ADDR-LINE-2", record.custAddrLine2());
            assertThat(images).containsEntry("CUST-ADDR-LINE-3", record.custAddrLine3());
            assertThat(images).containsEntry("CUST-ADDR-STATE-CD", record.custAddrStateCd());
            assertThat(images).containsEntry("CUST-ADDR-COUNTRY-CD", record.custAddrCountryCd());
            assertThat(images).containsEntry("CUST-ADDR-ZIP", record.custAddrZip());
            assertThat(images).containsEntry("CUST-PHONE-NUM-1", record.custPhoneNum1());
            assertThat(images).containsEntry("CUST-PHONE-NUM-2", record.custPhoneNum2());
            assertThat(images).containsEntry("CUST-SSN", record.custSsn());
            assertThat(images).containsEntry("CUST-GOVT-ISSUED-ID", record.custGovtIssuedId());
            assertThat(images).containsEntry("CUST-DOB-YYYYMMDD", record.custDobYyyymmdd());
            assertThat(images).containsEntry("CUST-EFT-ACCOUNT-ID", record.custEftAccountId());
            assertThat(images)
                    .containsEntry("CUST-PRI-CARD-HOLDER-IND", record.custPriCardHolderInd());
            assertThat(images).containsEntry("CUST-FICO-CREDIT-SCORE", record.custFicoCreditScore());

            assertThat(record.spanImage(Stm03CustomerRecord.CUST_DOB_YYYYMMDD, ASCII))
                    .isEqualTo(record.custDobYyyymmdd());
            assertThat(record.spanBytes(Stm03CustomerRecord.CUST_SSN, ASCII))
                    .isEqualTo(record.custSsn().getBytes(ASCII));
        }
    }

    @Nested
    @DisplayName("8. Boundaries - the first byte, the last byte, and the seam at 332")
    class Boundaries {
        @Test
        @DisplayName("the first byte is offset 0 and is the leading digit of CUST-ID")
        void theFirstByteIsOffsetZero() {
            String image = row1().groupImage(ASCII);

            assertThat(image.charAt(0)).isEqualTo('0');
            assertThat(image.substring(0, 1)).isEqualTo("0");
            assertThat(Stm03CustomerRecord.CUST_ID_OFFSET).isZero();
            assertThat(row1().spanImage(Stm03CustomerRecord.CUST_ID, ASCII).charAt(0))
                    .isEqualTo(image.charAt(0));

            String nineHundred = "900000001" + ROW1_IMAGE.substring(9);
            assertThat(Stm03CustomerRecord.decode(nineHundred, ASCII).groupImage(ASCII).charAt(0))
                    .isEqualTo('9');
        }

        @Test
        @DisplayName("the last byte is offset 499 and is the final byte of FILLER")
        void theLastByteIsOffsetFourNineNine() {
            String image = row1().groupImage(ASCII);

            assertThat(image).hasSize(500);
            assertThat(image.charAt(499)).isEqualTo(' ');
            assertThat(image.substring(499)).isEqualTo(" ");
            assertThat(Stm03CustomerRecord.FILLER_OFFSET + Stm03CustomerRecord.FILLER_LENGTH - 1)
                    .isEqualTo(499);
            String sentinelTail = ROW1_IMAGE.substring(0, 499) + "#";
            assertThat(sentinelTail).hasSize(500);
            assertThat(Stm03CustomerRecord.decode(sentinelTail, ASCII).groupImage(ASCII).charAt(499))
                    .isEqualTo(' ');
            assertThat(Stm03CustomerRecord.decode(sentinelTail, ASCII)
                    .spanImage(Stm03CustomerRecord.FILLER, ASCII))
                    .as("FILLER is not a component, so it is re-emitted as the spaces the copybook "
                            + "declares it to hold rather than carrying the sentinel back out")
                    .isEqualTo(" ".repeat(168));
        }

        @Test
        @DisplayName("offset 500 is past the end and is refused")
        void offsetFiveHundredIsPastTheEnd() {
            String image = row1().groupImage(ASCII);

            assertThatExceptionOfType(StringIndexOutOfBoundsException.class)
                    .isThrownBy(() -> image.charAt(500));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(500,
                            FieldSpan.alphanumeric("CUST-OVERRUN", 500, 1)));
        }

        @Test
        @DisplayName("the seam at 332: writing FICO leaves byte 332 alone and FILLER leaves 331 alone")
        void theSeamAtThreeThirtyTwo() {
            assertThat(Stm03CustomerRecord.CUST_FICO_CREDIT_SCORE_OFFSET
                    + Stm03CustomerRecord.CUST_FICO_CREDIT_SCORE_LENGTH).isEqualTo(332);
            assertThat(Stm03CustomerRecord.FILLER_OFFSET).isEqualTo(332);

            Stm03CustomerRecord withNines = new Stm03CustomerRecord(ROW1_CUST_ID,
                    ROW1_FIRST_NAME, ROW1_MIDDLE_NAME, ROW1_LAST_NAME, ROW1_ADDR_LINE_1,
                    ROW1_ADDR_LINE_2, ROW1_ADDR_LINE_3, ROW1_STATE_CD, ROW1_COUNTRY_CD, ROW1_ZIP,
                    ROW1_PHONE_1, ROW1_PHONE_2, ROW1_SSN, ROW1_GOVT_ID, ROW1_DOB,
                    ROW1_EFT_ACCOUNT_ID, ROW1_PRI_CARD_HOLDER_IND, "999");
            String image = withNines.groupImage(ASCII);

            assertThat(image.substring(329, 332))
                    .as("the score fills its own three bytes")
                    .isEqualTo("999");
            assertThat(image.charAt(331)).as("byte 331 is the last byte of the score").isEqualTo('9');
            assertThat(image.charAt(332))
                    .as("byte 332 is the first byte of FILLER and the score must not reach it")
                    .isEqualTo(' ');
            assertThat(image.charAt(328))
                    .as("byte 328 is CUST-PRI-CARD-HOLDER-IND and the score must not reach back into it")
                    .isEqualTo('Y');
            assertThat(withNines.spanImage(Stm03CustomerRecord.FILLER, ASCII))
                    .isEqualTo(" ".repeat(168));
            assertThat(withNines.custPriCardHolderInd()).isEqualTo("Y");
        }

        @Test
        @DisplayName("a span descriptor rejects a zero or negative width at declaration time")
        void aSpanRejectsAnImpossibleWidth() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FieldSpan.alphanumeric("CUST-EMPTY", 0, 0));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FieldSpan.alphanumeric("CUST-NEGATIVE", -1, 9));
        }
    }

    @Nested
    @DisplayName("9. The CUSTREC divergence - one field name, and the whole reason this type exists")
    class TheCustrecDivergence {
        @Test
        @DisplayName("the date-of-birth span is named CUST-DOB-YYYYMMDD, without hyphens")
        void theDobSpanCarriesTheCustrecName() {
            assertThat(Stm03CustomerRecord.CUST_DOB_YYYYMMDD.name())
                    .as("CUSTREC.cpy line 19 spells it without hyphens")
                    .isEqualTo("CUST-DOB-YYYYMMDD");
            assertThat(row1().fieldImages(ASCII))
                    .as("and the field map, which is what parity diffing keys on, agrees")
                    .containsKey("CUST-DOB-YYYYMMDD");
        }

        @Test
        @DisplayName("and it is NOT named CUST-DOB-YYYY-MM-DD, which is CVCUS01Y's spelling")
        void theDobSpanIsNotTheCvcus01yName() {
            assertThat(Stm03CustomerRecord.CUST_DOB_YYYYMMDD.name())
                    .isNotEqualTo("CUST-DOB-YYYY-MM-DD");
            assertThat(row1().fieldImages(ASCII).keySet())
                    .doesNotContain("CUST-DOB-YYYY-MM-DD");
            assertThat(row1().toString())
                    .as("even the diagnostic rendering must not print the other copybook's spelling")
                    .doesNotContain("CUST-DOB-YYYY-MM-DD");
        }

        @Test
        @DisplayName("the span sits at offset 308 for 10 bytes, exactly as it does in the twin copybook")
        void theDobSpanGeometryIsIdenticalToTheTwin() {
            assertThat(Stm03CustomerRecord.CUST_DOB_YYYYMMDD.offset()).isEqualTo(308);
            assertThat(Stm03CustomerRecord.CUST_DOB_YYYYMMDD.length()).isEqualTo(10);
            assertThat(Stm03CustomerRecord.CUST_DOB_YYYYMMDD.kind())
                    .as("PIC X(10) - character data, not a date type")
                    .isEqualTo(PictureKind.ALPHANUMERIC);
            assertThat(Stm03CustomerRecord.CUST_DOB_YYYYMMDD_OFFSET).isEqualTo(308);
            assertThat(Stm03CustomerRecord.CUST_DOB_YYYYMMDD_LENGTH).isEqualTo(10);
        }

        @Test
        @DisplayName("the record component is named custDobYyyymmdd, so a rename cannot slip through")
        void theRecordComponentCarriesTheCustrecName() {
            List<String> componentNames = new ArrayList<>();
            for (RecordComponent component : Stm03CustomerRecord.class.getRecordComponents()) {
                componentNames.add(component.getName());
            }

            assertThat(componentNames)
                    .hasSize(18)
                    .contains("custDobYyyymmdd")
                    .doesNotContain("custDobYyyyMmDd");
            assertThat(componentNames)
                    .as("the eighteen referable items in copybook order; FILLER is not a component")
                    .containsExactly("custId",
                            "custFirstName",
                            "custMiddleName",
                            "custLastName",
                            "custAddrLine1",
                            "custAddrLine2",
                            "custAddrLine3",
                            "custAddrStateCd",
                            "custAddrCountryCd",
                            "custAddrZip",
                            "custPhoneNum1",
                            "custPhoneNum2",
                            "custSsn",
                            "custGovtIssuedId",
                            "custDobYyyymmdd",
                            "custEftAccountId",
                            "custPriCardHolderInd",
                            "custFicoCreditScore");
        }

        @Test
        @DisplayName("the ten stored bytes stay hyphenated even though the field name is not")
        void theStoredValueStaysHyphenated() {
            assertThat(row1().custDobYyyymmdd()).isEqualTo("1961-06-08").hasSize(10);
            assertThat(row1().custDobYyyymmdd()).contains("-").isNotEqualTo("19610608");
            assertThat(row1().spanImage(Stm03CustomerRecord.CUST_DOB_YYYYMMDD, ASCII))
                    .isEqualTo("1961-06-08");
            assertThat(row1().groupImage(ASCII).substring(308, 318)).isEqualTo("1961-06-08");
        }

        @Test
        @DisplayName("the group name collides with the twin's, and that is expected, not a defect")
        void theGroupNameCollisionIsExpected() {
            assertThat(Stm03CustomerRecord.LAYOUT.recordLength()).isEqualTo(500);
            assertThat(Stm03CustomerRecord.LAYOUT.spans()).hasSize(19);

            List<String> referableNames = new ArrayList<>();
            for (FieldSpan span : Stm03CustomerRecord.LAYOUT.spans()) {
                if (!span.kind().filler()) {
                    referableNames.add(span.name());
                }
            }

            assertThat(referableNames).hasSize(18);
            assertThat(referableNames).containsExactly("CUST-ID",
                    "CUST-FIRST-NAME",
                    "CUST-MIDDLE-NAME",
                    "CUST-LAST-NAME",
                    "CUST-ADDR-LINE-1",
                    "CUST-ADDR-LINE-2",
                    "CUST-ADDR-LINE-3",
                    "CUST-ADDR-STATE-CD",
                    "CUST-ADDR-COUNTRY-CD",
                    "CUST-ADDR-ZIP",
                    "CUST-PHONE-NUM-1",
                    "CUST-PHONE-NUM-2",
                    "CUST-SSN",
                    "CUST-GOVT-ISSUED-ID",
                    "CUST-DOB-YYYYMMDD",
                    "CUST-EFT-ACCOUNT-ID",
                    "CUST-PRI-CARD-HOLDER-IND",
                    "CUST-FICO-CREDIT-SCORE");
            assertThat(referableNames)
                    .as("seventeen of these eighteen names are also CVCUS01Y's; the eighteenth is the "
                            + "divergence")
                    .doesNotContain("CUST-DOB-YYYY-MM-DD");
        }
    }

    @Nested
    @DisplayName("10. The 1000-byte arrival - decode takes the leading 500 and nothing else")
    class TheThousandByteArrival {
        @Test
        @DisplayName("a 1000-byte span decodes to the leading 500, and the tail 500 vanish")
        void aThousandByteSpanKeepsOnlyTheLeadingFiveHundred() {
            String sentinelTail = "#".repeat(500);
            String thousandBytes = ROW1_IMAGE + sentinelTail;
            assertThat(thousandBytes).hasSize(1000);

            Stm03CustomerRecord record = Stm03CustomerRecord.decode(thousandBytes, ASCII);

            assertThat(record).isEqualTo(Stm03CustomerRecord.decode(ROW1_IMAGE, ASCII));
            assertThat(record.custId()).isEqualTo("000000001");
            assertThat(record.custFicoCreditScore()).isEqualTo("274");
            assertThat(record.custDobYyyymmdd()).isEqualTo("1961-06-08");
            assertThat(record.groupImage(ASCII)).hasSize(500).doesNotContain("#");
            assertThat(new String(record.encode(ASCII), ASCII)).hasSize(500).doesNotContain("#");
            assertThat(record.spanImage(Stm03CustomerRecord.FILLER, ASCII))
                    .isEqualTo(" ".repeat(168));
        }

        @Test
        @DisplayName("the same 1000-byte arrival works from bytes, under both code pages")
        void theSameArrivalWorksFromBytes() {
            String thousandBytes = ROW1_IMAGE + "#".repeat(500);

            byte[] ascii = thousandBytes.getBytes(ASCII);
            assertThat(ascii).hasSize(1000);
            assertThat(Stm03CustomerRecord.decode(ascii, ASCII).encode(ASCII))
                    .hasSize(500)
                    .isEqualTo(ROW1_IMAGE.getBytes(ASCII));

            byte[] ebcdic = thousandBytes.getBytes(EBCDIC);
            assertThat(ebcdic).hasSize(1000);
            assertThat(Stm03CustomerRecord.decode(ebcdic, EBCDIC).encode(EBCDIC))
                    .hasSize(500)
                    .isEqualTo(ROW1_IMAGE.getBytes(EBCDIC));
        }

        @Test
        @DisplayName("an exactly-500-byte span is the boundary case and passes through untouched")
        void anExactlyFiveHundredByteSpanPassesThrough() {
            assertThat(ROW1_IMAGE).hasSize(500);

            Stm03CustomerRecord record = Stm03CustomerRecord.decode(ROW1_IMAGE, ASCII);

            assertThat(record.groupImage(ASCII)).isEqualTo(ROW1_IMAGE);
            assertThat(record.encode(ASCII)).isEqualTo(ROW1_IMAGE.getBytes(ASCII));
        }

        @Test
        @DisplayName("a 501-byte span loses exactly its last byte")
        void aFiveHundredAndOneByteSpanLosesItsLastByte() {
            String oneTooMany = ROW1_IMAGE + "#";
            assertThat(oneTooMany).hasSize(501);

            Stm03CustomerRecord record = Stm03CustomerRecord.decode(oneTooMany, ASCII);

            assertThat(record.groupImage(ASCII)).hasSize(500).isEqualTo(ROW1_IMAGE);
            assertThat(record.groupImage(ASCII)).doesNotContain("#");
            assertThat(record).isEqualTo(Stm03CustomerRecord.decode(ROW1_IMAGE, ASCII));
        }

        @Test
        @DisplayName("a 499-byte span is right-space-padded to 500, which is the declared contract")
        void aFourHundredAndNinetyNineByteSpanIsPadded() {
            String oneTooFew = ROW1_IMAGE.substring(0, 499);
            assertThat(oneTooFew).hasSize(499);

            Stm03CustomerRecord record = Stm03CustomerRecord.decode(oneTooFew, ASCII);

            assertThat(record.groupImage(ASCII))
                    .hasSize(500)
                    .as("the missing byte is supplied as a space, restoring the full FILLER")
                    .isEqualTo(ROW1_IMAGE);
            assertThat(record.spanImage(Stm03CustomerRecord.FILLER, ASCII))
                    .isEqualTo(" ".repeat(168));
        }

        @Test
        @DisplayName("a nine-byte span carrying only a key is padded out to a whole blank record")
        void aKeyOnlySpanIsPaddedToAWholeRecord() {
            Stm03CustomerRecord record = Stm03CustomerRecord.decode("000000001", ASCII);

            assertThat(record.custId()).isEqualTo("000000001");
            assertThat(record.custFirstName()).isEqualTo(" ".repeat(25));
            assertThat(record.custDobYyyymmdd()).isEqualTo(" ".repeat(10));
            assertThat(record.encode(ASCII)).hasSize(500);
            assertThat(record.groupImage(ASCII))
                    .isEqualTo("000000001" + " ".repeat(491));
        }

        @Test
        @DisplayName("an empty span decodes to a wholly blank 500-byte record")
        void anEmptySpanDecodesToABlankRecord() {
            Stm03CustomerRecord fromEmptyString = Stm03CustomerRecord.decode("", ASCII);
            Stm03CustomerRecord fromEmptyBytes = Stm03CustomerRecord.decode(new byte[0], ASCII);

            assertThat(fromEmptyString.groupImage(ASCII)).isEqualTo(" ".repeat(500));
            assertThat(fromEmptyBytes.groupImage(ASCII)).isEqualTo(" ".repeat(500));
            assertThat(fromEmptyString).isEqualTo(fromEmptyBytes);
            assertThat(fromEmptyString.encode(ASCII)).hasSize(500);
        }

        @Test
        @DisplayName("a null span is refused, and the message points at blank(Charset) instead")
        void aNullSpanIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> Stm03CustomerRecord.decode((String) null, ASCII))
                    .withMessageContaining("blank(Charset)");
            assertThatNullPointerException()
                    .isThrownBy(() -> Stm03CustomerRecord.decode((byte[]) null, ASCII))
                    .withMessageContaining("CUSTOMER-RECORD");
            assertThatNullPointerException()
                    .as("the code page is never derived from the platform, so it cannot be omitted")
                    .isThrownBy(() -> Stm03CustomerRecord.decode(new byte[500], null));
            assertThatNullPointerException()
                    .isThrownBy(() -> Stm03CustomerRecord.blank(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> row1().spanImage(null, ASCII))
                    .withMessageContaining("CUST_DOB_YYYYMMDD");
            assertThatNullPointerException()
                    .isThrownBy(() -> row1().spanBytes(null, ASCII));
        }
    }

    @Nested
    @DisplayName("11. The group-move tolerance - bytes the elementary pictures never saw")
    class GroupMoveTolerance {
        @Test
        @DisplayName("a 500-space image constructs, because a group MOVE can produce exactly that")
        void anAllSpacesImageConstructs() {
            Stm03CustomerRecord blankByGroupMove = Stm03CustomerRecord.decode(" ".repeat(500), ASCII);

            assertThat(blankByGroupMove.custId()).isEqualTo(" ".repeat(9));
            assertThat(blankByGroupMove.custSsn()).isEqualTo(" ".repeat(9));
            assertThat(blankByGroupMove.custFicoCreditScore()).isEqualTo("   ");
            assertThat(blankByGroupMove.custFirstName()).isEqualTo(" ".repeat(25));
        }

        @Test
        @DisplayName("and it round-trips to the very same 500 bytes, so reading one is not a one-way door")
        void anAllSpacesImageRoundTrips() {
            byte[] spaces = " ".repeat(500).getBytes(ASCII);

            assertThat(Stm03CustomerRecord.decode(spaces, ASCII).encode(ASCII)).isEqualTo(spaces);
        }

        @Test
        @DisplayName("the three numeric views still refuse a blank span, naming the field")
        void theNumericViewsRefuseABlankSpan() {
            Stm03CustomerRecord blankByGroupMove = Stm03CustomerRecord.decode(" ".repeat(500), ASCII);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> blankByGroupMove.custIdValue(ASCII));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> blankByGroupMove.custSsnValue(ASCII));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> blankByGroupMove.custFicoCreditScoreValue(ASCII));
        }

        @Test
        @DisplayName("an initialised record's numeric views read zero, because its spans hold digits")
        void anInitialisedRecordsNumericViewsReadZero() {
            Stm03CustomerRecord blank = Stm03CustomerRecord.blank(ASCII);

            assertThat(blank.custIdValue(ASCII)).isZero();
            assertThat(blank.custSsnValue(ASCII)).isZero();
            assertThat(blank.custFicoCreditScoreValue(ASCII)).isZero();
        }

        @Test
        @DisplayName("an empty numeric image blanks rather than zero-filling, and the two stay distinct")
        void anEmptyNumericImageBlanksRatherThanZeroFilling() {
            Stm03CustomerRecord empty = new Stm03CustomerRecord("",
                    "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "");

            assertThat(empty.custId()).isEqualTo(" ".repeat(9));
            assertThat(empty.custSsn()).isEqualTo(" ".repeat(9));
            assertThat(empty.custFicoCreditScore()).isEqualTo("   ");
            assertThat(empty.groupImage(ASCII)).isEqualTo(" ".repeat(500));
            assertThatIllegalArgumentException().isThrownBy(() -> empty.custIdValue(ASCII));
            assertThat(Stm03CustomerRecord.decode("000000000" + " ".repeat(491), ASCII)
                    .custIdValue(ASCII)).isZero();
        }

        @Test
        @DisplayName("a partly numeric span is received as bytes, not silently renumbered")
        void aPartlyNumericSpanIsReceivedAsBytes() {
            Stm03CustomerRecord record = new Stm03CustomerRecord("12 45678X",
                    ROW1_FIRST_NAME, ROW1_MIDDLE_NAME, ROW1_LAST_NAME, ROW1_ADDR_LINE_1,
                    ROW1_ADDR_LINE_2, ROW1_ADDR_LINE_3, ROW1_STATE_CD, ROW1_COUNTRY_CD, ROW1_ZIP,
                    ROW1_PHONE_1, ROW1_PHONE_2, ROW1_SSN, ROW1_GOVT_ID, ROW1_DOB,
                    ROW1_EFT_ACCOUNT_ID, ROW1_PRI_CARD_HOLDER_IND, ROW1_FICO);

            assertThat(record.custId()).isEqualTo("12 45678X").hasSize(9);
            assertThat(record.groupImage(ASCII).substring(0, 9)).isEqualTo("12 45678X");
            assertThatIllegalArgumentException().isThrownBy(() -> record.custIdValue(ASCII));
            assertThat(record.custSsnValue(ASCII))
                    .as("and the neighbouring numeric field is unaffected by it")
                    .isEqualTo(20973888);
        }

        @Test
        @DisplayName("an over-wide non-digit numeric image truncates on the RIGHT, as PIC X would")
        void anOverWideNonDigitNumericImageTruncatesRight() {
            Stm03CustomerRecord record = new Stm03CustomerRecord("12 45678XYZ",
                    ROW1_FIRST_NAME, ROW1_MIDDLE_NAME, ROW1_LAST_NAME, ROW1_ADDR_LINE_1,
                    ROW1_ADDR_LINE_2, ROW1_ADDR_LINE_3, ROW1_STATE_CD, ROW1_COUNTRY_CD, ROW1_ZIP,
                    ROW1_PHONE_1, ROW1_PHONE_2, ROW1_SSN, ROW1_GOVT_ID, ROW1_DOB,
                    ROW1_EFT_ACCOUNT_ID, ROW1_PRI_CARD_HOLDER_IND, ROW1_FICO);

            assertThat(record.custId())
                    .as("eleven characters, none of them a digit run, so the leading nine survive")
                    .isEqualTo("12 45678X")
                    .hasSize(9);
        }
    }
}
