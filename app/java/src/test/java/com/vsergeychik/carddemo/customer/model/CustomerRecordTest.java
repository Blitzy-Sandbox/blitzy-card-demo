package com.vsergeychik.carddemo.customer.model;

import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * The sole test class for {@link CustomerRecord} - the 500-byte {@code CUSTOMER-RECORD} that
 * {@code app/cpy/CVCUS01Y.cpy} declares, and the one Java type in the migration that models it.
 */
@DisplayName("CustomerRecord - the 500-byte CUSTOMER-RECORD of CVCUS01Y")
class CustomerRecordTest {
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    private static final Charset EBCDIC = Charset.forName("IBM037");

    private static final byte ASCII_SPACE_BYTE = 0x20;

    private static final byte EBCDIC_SPACE_BYTE = 0x40;

    private static final int DECLARED_RECORD_LENGTH = 500;

    private static final int DECLARED_SPAN_COUNT = 19;

    private static final int REFERABLE_FIELD_COUNT = 18;

    private static final int PRIMARY_KEY_LENGTH = 9;

    private static final List<String> SPAN_NAMES = List.of(
            "CUST-ID",
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
            "CUST-DOB-YYYY-MM-DD",
            "CUST-EFT-ACCOUNT-ID",
            "CUST-PRI-CARD-HOLDER-IND",
            "CUST-FICO-CREDIT-SCORE",
            "FILLER");

    private static final List<Integer> SPAN_LENGTHS = List.of(
            9, 25, 25, 25, 50, 50, 50, 2, 3, 10, 15, 15, 9, 20, 10, 10, 1, 3, 168);

    private static final List<Integer> SPAN_OFFSETS = List.of(
            0, 9, 34, 59, 84, 134, 184, 234, 236, 239, 249, 264, 279, 288, 308, 318, 328, 329, 332);

    private static final int FILLER_OFFSET = 332;

    private static final int FILLER_LENGTH = 168;

    private static final String FIXTURE_RESOURCE = "fixtures/custdata.txt";

    private static final int FIXTURE_ROW_COUNT = 50;

    private static final String ROW_1_CUST_ID_IMAGE = "000000001";

    private static final int ROW_1_CUST_ID = 1;

    private static final String ROW_1_FIRST_NAME = "Immanuel";

    private static final String ROW_1_MIDDLE_NAME = "Madeline";

    private static final String ROW_1_LAST_NAME = "Kessler";

    private static final String ROW_1_ADDR_LINE_1 = "618 Deshaun Route";

    private static final String ROW_1_ADDR_LINE_2 = "Apt. 802";

    private static final String ROW_1_ADDR_LINE_3 = "Altenwerthshire";

    private static final String ROW_1_STATE_CD = "NC";

    private static final String ROW_1_COUNTRY_CD = "USA";

    private static final String ROW_1_ZIP = "12546";

    private static final String ROW_1_PHONE_1 = "(908)119-8310";

    private static final String ROW_1_PHONE_2 = "(373)693-8684";

    private static final String ROW_1_SSN_IMAGE = "020973888";

    private static final int ROW_1_SSN = 20973888;

    private static final String ROW_1_GOVT_ISSUED_ID = "00000000000049368437";

    private static final String ROW_1_DOB = "1961-06-08";

    private static final String ROW_1_EFT_ACCOUNT_ID = "0053581756";

    private static final String ROW_1_PRI_CARD_HOLDER_IND = "Y";

    private static final int ROW_1_FICO = 274;

    private static final String ROW_2_CUST_ID_IMAGE = "000000002";

    private static final String ROW_2_FIRST_NAME = "Enrico";

    private static final String ROW_2_MIDDLE_NAME = "April";

    private static final String ROW_2_LAST_NAME = "Rosenbaum";

    private static final String ROW_2_ADDR_LINE_1 = "4917 Myrna Flats";

    private static final String ROW_2_STATE_CD = "IN";

    private static final String ROW_2_ZIP = "22770";

    private static final String ROW_2_SSN_IMAGE = "587518382";

    private static final String ROW_2_DOB = "1961-10-08";

    private static final String ROW_2_EFT_ACCOUNT_ID = "0069194009";

    private static final int ROW_2_FICO = 268;

    private static final String ROW_26_FICO_IMAGE = "001";

    private static final int ROW_26_FICO = 1;

    private static final String ROW_26_CUST_ID_IMAGE = "000000026";

    private static final String ROW_26_FIRST_NAME = "Marjory";

    private static final String ROW_26_ADDR_LINE_1 = "30161 Bogan Canyon";

    private static final String ROW_50_CUST_ID_IMAGE = "000000050";

    private static final String ROW_50_FIRST_NAME = "Aniya";

    private static final String ROW_50_MIDDLE_NAME = "Alba";

    private static final String ROW_50_LAST_NAME = "Von";

    private static final String ROW_50_ADDR_LINE_1 = "1588 Nienow Cape";

    private static final String ROW_50_STATE_CD = "OR";

    private static final String ROW_50_ZIP = "04257";

    private static final String ROW_50_SSN_IMAGE = "931248469";

    private static final String ROW_50_DOB = "1960-12-01";

    private static final String ROW_50_EFT_ACCOUNT_ID = "0074883577";

    private static final int ROW_50_FICO = 623;

    private static final String ROW_3_ZIP = "19852-6716";

    private static final String ROW_5_ZIP = "02251-1698";

    private static final String ROW_44_ZIP = "05704-0501";

    private static final String ROW_24_SSN_IMAGE = "017590544";

    private final FixedWidthCodec asciiCodec = new FixedWidthCodec(ASCII);

    private final FixedWidthCodec ebcdicCodec = new FixedWidthCodec(EBCDIC);

    private static String padded(String value, int width) {
        return value + " ".repeat(width - value.length());
    }

    private static String spaces(int width) {
        return " ".repeat(width);
    }

    private static List<String> fixtureRows() {
        List<String> rows = new ArrayList<>();
        try (InputStream stream =
                     CustomerRecordTest.class.getClassLoader().getResourceAsStream(FIXTURE_RESOURCE)) {
            assertThat(stream)
                    .as("The fixture must be on the test classpath at '%s'. It is derived from "
                            + "app/data/ASCII/custdata.txt, which is read-only and never opened by "
                            + "path.", FIXTURE_RESOURCE)
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
        return List.copyOf(rows);
    }

    private static String fixtureRow(int recordNumber) {
        List<String> rows = fixtureRows();
        assertThat(rows)
                .as("row %d was requested, so the fixture must hold at least that many rows",
                        recordNumber)
                .hasSizeGreaterThanOrEqualTo(recordNumber);
        return rows.get(recordNumber - 1);
    }

    private static List<Arguments> everyFixtureRow() {
        List<String> rows = fixtureRows();
        List<Arguments> arguments = new ArrayList<>(rows.size());
        for (int index = 0; index < rows.size(); index++) {
            arguments.add(Arguments.of(index + 1, rows.get(index)));
        }
        return arguments;
    }

    private static String synthesisedImage(String priCardHolderInd) {
        return "000000042"
                + padded("Alice", 25)
                + padded("B", 25)
                + padded("Smith", 25)
                + padded("1 High Street", 50)
                + spaces(50)
                + spaces(50)
                + "WA"
                + "USA"
                + padded("99999", 10)
                + padded("2065550100", 15)
                + spaces(15)
                + "020973888"
                + spaces(20)
                + "1970-01-01"
                + spaces(10)
                + priCardHolderInd
                + "720"
                + spaces(FILLER_LENGTH);
    }

    private static CustomerRecord row1AsBuilt() {
        CustomerRecord record = new CustomerRecord();
        record.setCustId(ROW_1_CUST_ID);
        record.setCustFirstName(ROW_1_FIRST_NAME);
        record.setCustMiddleName(ROW_1_MIDDLE_NAME);
        record.setCustLastName(ROW_1_LAST_NAME);
        record.setCustAddrLine1(ROW_1_ADDR_LINE_1);
        record.setCustAddrLine2(ROW_1_ADDR_LINE_2);
        record.setCustAddrLine3(ROW_1_ADDR_LINE_3);
        record.setCustAddrStateCd(ROW_1_STATE_CD);
        record.setCustAddrCountryCd(ROW_1_COUNTRY_CD);
        record.setCustAddrZip(ROW_1_ZIP);
        record.setCustPhoneNum1(ROW_1_PHONE_1);
        record.setCustPhoneNum2(ROW_1_PHONE_2);
        record.setCustSsn(ROW_1_SSN);
        record.setCustGovtIssuedId(ROW_1_GOVT_ISSUED_ID);
        record.setCustDobYyyyMmDd(ROW_1_DOB);
        record.setCustEftAccountId(ROW_1_EFT_ACCOUNT_ID);
        record.setCustPriCardHolderInd(ROW_1_PRI_CARD_HOLDER_IND);
        record.setCustFicoCreditScore(ROW_1_FICO);
        return record;
    }

    private static boolean isFloatingPoint(Class<?> type) {
        return double.class.equals(type)
                || float.class.equals(type)
                || Double.class.equals(type)
                || Float.class.equals(type);
    }

    private static boolean isForbiddenNumericType(Class<?> type) {
        return isFloatingPoint(type)
                || "java.math.BigDecimal".equals(type.getName())
                || "java.math.BigInteger".equals(type.getName())
                || "java.math.RoundingMode".equals(type.getName());
    }

    @Nested
    @DisplayName("Declared geometry - the 19 spans of CVCUS01Y (G8, G19)")
    class DeclaredGeometry {
        @Test
        @DisplayName("The transcribed lengths sum to 500, matching CVCUS01Y's own RECLN 500 header")
        void theTranscribedLengthsSumToFiveHundred() {
            int total = 0;
            for (int length : SPAN_LENGTHS) {
                total += length;
            }

            assertThat(SPAN_LENGTHS)
                    .as("CVCUS01Y declares 19 items at the 05 level, FILLER included")
                    .hasSize(DECLARED_SPAN_COUNT);
            assertThat(total)
                    .as("9+25+25+25+50+50+50+2+3+10+15+15+9+20+10+10+1+3+168 must be 500 - the width "
                            + "the copybook header states, CBCUS01C's 9(09)+X(491) split confirms and "
                            + "CUSTFILE.jcl's RECORDSIZE(500 500) fixes")
                    .isEqualTo(DECLARED_RECORD_LENGTH);
        }

        @Test
        @DisplayName("The transcribed offsets are the running total of the transcribed lengths")
        void theTranscribedOffsetsAreSelfConsistent() {
            assertThat(SPAN_OFFSETS).hasSize(DECLARED_SPAN_COUNT);

            int cursor = 0;
            for (int index = 0; index < DECLARED_SPAN_COUNT; index++) {
                assertThat(SPAN_OFFSETS.get(index))
                        .as("span %d (%s) begins where span %d ends", index + 1,
                                SPAN_NAMES.get(index), index)
                        .isEqualTo(cursor);
                cursor += SPAN_LENGTHS.get(index);
            }

            assertThat(cursor)
                    .as("the last span, FILLER X(168) at offset 332, must end exactly at byte 500")
                    .isEqualTo(DECLARED_RECORD_LENGTH);
        }

        @Test
        @DisplayName("The declared record length constant is 500")
        void theDeclaredRecordLengthIsFiveHundred() {
            assertThat(CustomerRecord.RECORD_LENGTH).isEqualTo(DECLARED_RECORD_LENGTH);
            assertThat(CustomerRecord.LAYOUT.recordLength()).isEqualTo(DECLARED_RECORD_LENGTH);
        }

        @Test
        @DisplayName("The layout declares 19 spans, in copybook order, under their copybook names")
        void theLayoutDeclaresNineteenSpansInCopybookOrder() {
            List<FieldSpan> spans = CustomerRecord.LAYOUT.spans();

            assertThat(spans)
                    .as("18 named items plus the trailing FILLER")
                    .hasSize(DECLARED_SPAN_COUNT)
                    .extracting(FieldSpan::name)
                    .containsExactlyElementsOf(SPAN_NAMES);
        }

        @ParameterizedTest(name = "[{0}] {1} at [{2}, +{3})")
        @CsvSource({
            "0,  CUST-ID,                    0,   9",
            "1,  CUST-FIRST-NAME,            9,   25",
            "2,  CUST-MIDDLE-NAME,           34,  25",
            "3,  CUST-LAST-NAME,             59,  25",
            "4,  CUST-ADDR-LINE-1,           84,  50",
            "5,  CUST-ADDR-LINE-2,           134, 50",
            "6,  CUST-ADDR-LINE-3,           184, 50",
            "7,  CUST-ADDR-STATE-CD,         234, 2",
            "8,  CUST-ADDR-COUNTRY-CD,       236, 3",
            "9,  CUST-ADDR-ZIP,              239, 10",
            "10, CUST-PHONE-NUM-1,           249, 15",
            "11, CUST-PHONE-NUM-2,           264, 15",
            "12, CUST-SSN,                   279, 9",
            "13, CUST-GOVT-ISSUED-ID,        288, 20",
            "14, CUST-DOB-YYYY-MM-DD,        308, 10",
            "15, CUST-EFT-ACCOUNT-ID,        318, 10",
            "16, CUST-PRI-CARD-HOLDER-IND,   328, 1",
            "17, CUST-FICO-CREDIT-SCORE,     329, 3",
            "18, FILLER,                     332, 168",
        })
        @DisplayName("Each span sits at its copybook offset with its copybook width")
        void eachSpanSitsAtItsCopybookOffset(int index, String name, int offset, int length) {
            FieldSpan span = CustomerRecord.LAYOUT.spans().get(index);

            assertThat(span.name()).isEqualTo(name);
            assertThat(span.offset()).isEqualTo(offset);
            assertThat(span.length()).isEqualTo(length);
            assertThat(span.endOffsetExclusive()).isEqualTo(offset + length);
            assertThat(span.redefinition())
                    .as("CVCUS01Y declares no REDEFINES, so no span is an overlay")
                    .isFalse();
            assertThat(span.hasInitialValue())
                    .as("CVCUS01Y declares no VALUE clause, so no span carries a literal")
                    .isFalse();
        }

        @Test
        @DisplayName("Each public FieldSpan constant carries the copybook's name, offset and width")
        void eachPublicSpanConstantMatchesTheCopybook() {
            assertSpan(CustomerRecord.CUST_ID, "CUST-ID", 0, 9);
            assertSpan(CustomerRecord.CUST_FIRST_NAME, "CUST-FIRST-NAME", 9, 25);
            assertSpan(CustomerRecord.CUST_MIDDLE_NAME, "CUST-MIDDLE-NAME", 34, 25);
            assertSpan(CustomerRecord.CUST_LAST_NAME, "CUST-LAST-NAME", 59, 25);
            assertSpan(CustomerRecord.CUST_ADDR_LINE_1, "CUST-ADDR-LINE-1", 84, 50);
            assertSpan(CustomerRecord.CUST_ADDR_LINE_2, "CUST-ADDR-LINE-2", 134, 50);
            assertSpan(CustomerRecord.CUST_ADDR_LINE_3, "CUST-ADDR-LINE-3", 184, 50);
            assertSpan(CustomerRecord.CUST_ADDR_STATE_CD, "CUST-ADDR-STATE-CD", 234, 2);
            assertSpan(CustomerRecord.CUST_ADDR_COUNTRY_CD, "CUST-ADDR-COUNTRY-CD", 236, 3);
            assertSpan(CustomerRecord.CUST_ADDR_ZIP, "CUST-ADDR-ZIP", 239, 10);
            assertSpan(CustomerRecord.CUST_PHONE_NUM_1, "CUST-PHONE-NUM-1", 249, 15);
            assertSpan(CustomerRecord.CUST_PHONE_NUM_2, "CUST-PHONE-NUM-2", 264, 15);
            assertSpan(CustomerRecord.CUST_SSN, "CUST-SSN", 279, 9);
            assertSpan(CustomerRecord.CUST_GOVT_ISSUED_ID, "CUST-GOVT-ISSUED-ID", 288, 20);
            assertSpan(CustomerRecord.CUST_DOB_YYYY_MM_DD, "CUST-DOB-YYYY-MM-DD", 308, 10);
            assertSpan(CustomerRecord.CUST_EFT_ACCOUNT_ID, "CUST-EFT-ACCOUNT-ID", 318, 10);
            assertSpan(CustomerRecord.CUST_PRI_CARD_HOLDER_IND, "CUST-PRI-CARD-HOLDER-IND", 328, 1);
            assertSpan(CustomerRecord.CUST_FICO_CREDIT_SCORE, "CUST-FICO-CREDIT-SCORE", 329, 3);
            assertSpan(CustomerRecord.FILLER, "FILLER", FILLER_OFFSET, FILLER_LENGTH);
        }

        private void assertSpan(FieldSpan span, String name, int offset, int length) {
            assertThat(span.name()).isEqualTo(name);
            assertThat(span.offset()).as("%s offset", name).isEqualTo(offset);
            assertThat(span.length()).as("%s length", name).isEqualTo(length);
        }

        @ParameterizedTest(name = "{0} is {1}")
        @CsvSource({
            "CUST-ID,                    UNSIGNED_NUMERIC",
            "CUST-FIRST-NAME,            ALPHANUMERIC",
            "CUST-MIDDLE-NAME,           ALPHANUMERIC",
            "CUST-LAST-NAME,             ALPHANUMERIC",
            "CUST-ADDR-LINE-1,           ALPHANUMERIC",
            "CUST-ADDR-LINE-2,           ALPHANUMERIC",
            "CUST-ADDR-LINE-3,           ALPHANUMERIC",
            "CUST-ADDR-STATE-CD,         ALPHANUMERIC",
            "CUST-ADDR-COUNTRY-CD,       ALPHANUMERIC",
            "CUST-ADDR-ZIP,              ALPHANUMERIC",
            "CUST-PHONE-NUM-1,           ALPHANUMERIC",
            "CUST-PHONE-NUM-2,           ALPHANUMERIC",
            "CUST-SSN,                   UNSIGNED_NUMERIC",
            "CUST-GOVT-ISSUED-ID,        ALPHANUMERIC",
            "CUST-DOB-YYYY-MM-DD,        ALPHANUMERIC",
            "CUST-EFT-ACCOUNT-ID,        ALPHANUMERIC",
            "CUST-PRI-CARD-HOLDER-IND,   ALPHANUMERIC",
            "CUST-FICO-CREDIT-SCORE,     UNSIGNED_NUMERIC",
        })
        @DisplayName("Each span carries the picture category its PICTURE clause declares")
        void eachSpanCarriesItsPictureCategory(String name, PictureKind kind) {
            FieldSpan span = CustomerRecord.LAYOUT.span(name);

            assertThat(span.kind()).isEqualTo(kind);
            assertThat(span.kind().filler()).isFalse();
            assertThat(span.kind().numericDisplay())
                    .as("%s is numeric DISPLAY only when its PICTURE says 9", name)
                    .isEqualTo(kind == PictureKind.UNSIGNED_NUMERIC);
            assertThat(span.kind().leftJustified())
                    .as("%s is left justified only when its PICTURE says X", name)
                    .isEqualTo(kind == PictureKind.ALPHANUMERIC);
        }

        @Test
        @DisplayName("Exactly three spans are numeric, and none is signed or scaled")
        void exactlyThreeSpansAreNumeric() {
            List<String> numeric = new ArrayList<>();
            List<String> signedOrScaled = new ArrayList<>();
            for (FieldSpan span : CustomerRecord.LAYOUT.spans()) {
                if (span.kind() == PictureKind.UNSIGNED_NUMERIC) {
                    numeric.add(span.name());
                }
                if (span.kind() == PictureKind.SIGNED_SCALED) {
                    signedOrScaled.add(span.name());
                }
            }

            assertThat(numeric)
                    .as("CVCUS01Y's complete numeric census: 9(09) twice and 9(03) once")
                    .containsExactly("CUST-ID", "CUST-SSN", "CUST-FICO-CREDIT-SCORE");
            assertThat(signedOrScaled)
                    .as("CVCUS01Y declares no S9 and no V-scaled picture anywhere, which is why this "
                            + "is the one persisted model with no monetary field at all")
                    .isEmpty();
        }

        @Test
        @DisplayName("Exactly one span is FILLER and it is the last")
        void exactlyOneSpanIsFillerAndItIsLast() {
            List<FieldSpan> spans = CustomerRecord.LAYOUT.spans();
            List<String> fillerPositions = new ArrayList<>();
            for (int index = 0; index < spans.size(); index++) {
                if (spans.get(index).kind().filler()) {
                    fillerPositions.add(String.valueOf(index));
                }
            }

            assertThat(fillerPositions)
                    .as("CVCUS01Y's only FILLER is its 19th and final item")
                    .containsExactly(String.valueOf(DECLARED_SPAN_COUNT - 1));
            assertThat(spans.get(DECLARED_SPAN_COUNT - 1).name()).isEqualTo("FILLER");
        }

        @Test
        @DisplayName("The spans are contiguous from 0 with no gap and no overlap, ending at 500")
        void theSpansAreContiguousFromZeroToFiveHundred() {
            int cursor = 0;
            for (FieldSpan span : CustomerRecord.LAYOUT.storageSpans()) {
                assertThat(span.offset())
                        .as("%s must begin exactly where the preceding span ended", span.name())
                        .isEqualTo(cursor);
                cursor = span.endOffsetExclusive();
            }

            assertThat(cursor).isEqualTo(DECLARED_RECORD_LENGTH);
            assertThat(CustomerRecord.LAYOUT.storageSpans()).hasSize(DECLARED_SPAN_COUNT);
            assertThat(CustomerRecord.LAYOUT.redefinitions())
                    .as("CVCUS01Y declares no REDEFINES overlay")
                    .isEmpty();
        }

        @Test
        @DisplayName("All 18 referable names resolve by name; FILLER does not, being non-referable")
        void everyReferableNameResolvesAndFillerDoesNot() {
            for (String name : SPAN_NAMES) {
                if ("FILLER".equals(name)) {
                    continue;
                }
                assertThat(CustomerRecord.LAYOUT.hasSpan(name)).as("%s is referable", name).isTrue();
                assertThat(CustomerRecord.LAYOUT.span(name).name()).isEqualTo(name);
            }

            assertThat(CustomerRecord.LAYOUT.hasSpan("FILLER"))
                    .as("FILLER is not a referable COBOL name, so it is reachable as a span in the "
                            + "layout but never by name")
                    .isFalse();
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CustomerRecord.LAYOUT.span("FILLER"))
                    .withMessageContaining("FILLER");
        }

        @ParameterizedTest(name = "''{0}'' is not a CVCUS01Y field")
        @ValueSource(strings = {
            "cust-id",
            "CUST_ID",
            "CUSTID",
            "CUST-DOB-YYYYMMDD",
            "CUST-EXPIRAION-DATE",
            "ACCT-ID",
            "CUST-ADDR-LINE-4",
            "",
        })
        @DisplayName("A name CVCUS01Y does not declare does not resolve, case included")
        void anUndeclaredNameDoesNotResolve(String unknown) {
            assertThat(CustomerRecord.LAYOUT.hasSpan(unknown)).isFalse();
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CustomerRecord.LAYOUT.span(unknown));
        }

        @Test
        @DisplayName("A null name is refused rather than treated as no match")
        void aNullNameIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> CustomerRecord.LAYOUT.hasSpan(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> CustomerRecord.LAYOUT.span(null));
        }

        @Test
        @DisplayName("No name is declared twice, so no two fields can alias one another")
        void noNameIsDeclaredTwice() {
            Set<String> seen = new LinkedHashSet<>();
            List<String> duplicates = new ArrayList<>();
            for (FieldSpan span : CustomerRecord.LAYOUT.spans()) {
                if ("FILLER".equals(span.name())) {
                    continue;
                }
                if (!seen.add(span.name())) {
                    duplicates.add(span.name());
                }
            }

            assertThat(duplicates).isEmpty();
            assertThat(seen).hasSize(REFERABLE_FIELD_COUNT);
        }

        @Test
        @DisplayName("CUST-ID is the nine-byte key at offset 0 - KEYS(9 0), not DEFCUST's KEYS(10 0)")
        void custIdIsTheNineByteKeyAtOffsetZero() {
            FieldSpan key = CustomerRecord.LAYOUT.span("CUST-ID");

            assertThat(key.offset()).as("KEYS(9 0) - the second operand is the key's offset").isZero();
            assertThat(key.length())
                    .as("KEYS(9 0) - the first operand is the key's length, and it is 9, never 10")
                    .isEqualTo(PRIMARY_KEY_LENGTH);
            assertThat(key.kind()).isEqualTo(PictureKind.UNSIGNED_NUMERIC);
        }

        @Test
        @DisplayName("A freshly encoded record is exactly 500 bytes under either code page")
        void aFreshlyEncodedRecordIsExactlyFiveHundredBytes() {
            assertThat(new CustomerRecord().encode(ASCII)).hasSize(DECLARED_RECORD_LENGTH);
            assertThat(new CustomerRecord().encode(EBCDIC)).hasSize(DECLARED_RECORD_LENGTH);
            assertThat(row1AsBuilt().encode(ASCII)).hasSize(DECLARED_RECORD_LENGTH);
            assertThat(row1AsBuilt().encode(asciiCodec)).hasSize(DECLARED_RECORD_LENGTH);
            assertThat(row1AsBuilt().recordImage(ASCII)).hasSize(DECLARED_RECORD_LENGTH);
            assertThat(row1AsBuilt().recordImage(asciiCodec)).hasSize(DECLARED_RECORD_LENGTH);
        }

        @Test
        @DisplayName("CBCUS01C's FD split of 9 + 491 agrees with the copybook's own arithmetic")
        void theProgramsFdSplitAgreesWithTheCopybook() {
            int keyWidth = CustomerRecord.LAYOUT.span("CUST-ID").length();
            int dataWidth = DECLARED_RECORD_LENGTH - keyWidth;

            assertThat(keyWidth).isEqualTo(9);
            assertThat(dataWidth).as("FD-CUST-DATA PIC X(491)").isEqualTo(491);
        }
    }

    @Nested
    @DisplayName("The layout self-check accepts CVCUS01Y and rejects every way of breaking it")
    class LayoutSelfCheck {
        @Test
        @DisplayName("The passing side: the real 19-span layout builds and declares 500 bytes")
        void theRealLayoutPassesTheSelfCheck() {
            assertThatCode(() -> RecordLayout.of(CustomerRecord.RECORD_LENGTH,
                    CustomerRecord.CUST_ID,
                    CustomerRecord.CUST_FIRST_NAME,
                    CustomerRecord.CUST_MIDDLE_NAME,
                    CustomerRecord.CUST_LAST_NAME,
                    CustomerRecord.CUST_ADDR_LINE_1,
                    CustomerRecord.CUST_ADDR_LINE_2,
                    CustomerRecord.CUST_ADDR_LINE_3,
                    CustomerRecord.CUST_ADDR_STATE_CD,
                    CustomerRecord.CUST_ADDR_COUNTRY_CD,
                    CustomerRecord.CUST_ADDR_ZIP,
                    CustomerRecord.CUST_PHONE_NUM_1,
                    CustomerRecord.CUST_PHONE_NUM_2,
                    CustomerRecord.CUST_SSN,
                    CustomerRecord.CUST_GOVT_ISSUED_ID,
                    CustomerRecord.CUST_DOB_YYYY_MM_DD,
                    CustomerRecord.CUST_EFT_ACCOUNT_ID,
                    CustomerRecord.CUST_PRI_CARD_HOLDER_IND,
                    CustomerRecord.CUST_FICO_CREDIT_SCORE,
                    CustomerRecord.FILLER))
                    .as("the 19 copybook spans describe exactly 500 contiguous bytes")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Dropping the trailing FILLER is rejected - the G21 tripwire, 168 bytes short")
        void droppingTheTrailingFillerIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(CustomerRecord.RECORD_LENGTH,
                            CustomerRecord.CUST_ID,
                            CustomerRecord.CUST_FIRST_NAME,
                            CustomerRecord.CUST_MIDDLE_NAME,
                            CustomerRecord.CUST_LAST_NAME,
                            CustomerRecord.CUST_ADDR_LINE_1,
                            CustomerRecord.CUST_ADDR_LINE_2,
                            CustomerRecord.CUST_ADDR_LINE_3,
                            CustomerRecord.CUST_ADDR_STATE_CD,
                            CustomerRecord.CUST_ADDR_COUNTRY_CD,
                            CustomerRecord.CUST_ADDR_ZIP,
                            CustomerRecord.CUST_PHONE_NUM_1,
                            CustomerRecord.CUST_PHONE_NUM_2,
                            CustomerRecord.CUST_SSN,
                            CustomerRecord.CUST_GOVT_ISSUED_ID,
                            CustomerRecord.CUST_DOB_YYYY_MM_DD,
                            CustomerRecord.CUST_EFT_ACCOUNT_ID,
                            CustomerRecord.CUST_PRI_CARD_HOLDER_IND,
                            CustomerRecord.CUST_FICO_CREDIT_SCORE))
                    .withMessageContaining(String.valueOf(FILLER_OFFSET))
                    .withMessageContaining(String.valueOf(DECLARED_RECORD_LENGTH))
                    .withMessageContaining(String.valueOf(FILLER_LENGTH));
        }

        @Test
        @DisplayName("A gap between two spans is rejected, naming the span it precedes")
        void aGapBetweenSpansIsRejected() {
            FieldSpan displaced = FieldSpan.alphanumeric("CUST-FIRST-NAME", 10, 25);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(CustomerRecord.RECORD_LENGTH,
                            CustomerRecord.CUST_ID,
                            displaced))
                    .withMessageContaining("gap")
                    .withMessageContaining("CUST-FIRST-NAME");
        }

        @Test
        @DisplayName("An overlap between two spans is rejected, naming the overlapping span")
        void anOverlapBetweenSpansIsRejected() {
            FieldSpan overlapping = FieldSpan.alphanumeric("CUST-FIRST-NAME", 8, 25);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(CustomerRecord.RECORD_LENGTH,
                            CustomerRecord.CUST_ID,
                            overlapping))
                    .withMessageContaining("overlap")
                    .withMessageContaining("CUST-FIRST-NAME");
        }

        @Test
        @DisplayName("A layout one byte wider than 500 is rejected")
        void aLayoutWiderThanTheRecordIsRejected() {
            FieldSpan oversizedFiller = FieldSpan.filler(FILLER_OFFSET, FILLER_LENGTH + 1);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(CustomerRecord.RECORD_LENGTH,
                            CustomerRecord.CUST_ID,
                            CustomerRecord.CUST_FIRST_NAME,
                            CustomerRecord.CUST_MIDDLE_NAME,
                            CustomerRecord.CUST_LAST_NAME,
                            CustomerRecord.CUST_ADDR_LINE_1,
                            CustomerRecord.CUST_ADDR_LINE_2,
                            CustomerRecord.CUST_ADDR_LINE_3,
                            CustomerRecord.CUST_ADDR_STATE_CD,
                            CustomerRecord.CUST_ADDR_COUNTRY_CD,
                            CustomerRecord.CUST_ADDR_ZIP,
                            CustomerRecord.CUST_PHONE_NUM_1,
                            CustomerRecord.CUST_PHONE_NUM_2,
                            CustomerRecord.CUST_SSN,
                            CustomerRecord.CUST_GOVT_ISSUED_ID,
                            CustomerRecord.CUST_DOB_YYYY_MM_DD,
                            CustomerRecord.CUST_EFT_ACCOUNT_ID,
                            CustomerRecord.CUST_PRI_CARD_HOLDER_IND,
                            CustomerRecord.CUST_FICO_CREDIT_SCORE,
                            oversizedFiller))
                    .withMessageContaining(String.valueOf(DECLARED_RECORD_LENGTH + 1));
        }

        @Test
        @DisplayName("Declaring a referable name twice is rejected, so no field can alias another")
        void declaringANameTwiceIsRejected() {
            FieldSpan secondCustId = FieldSpan.unsignedNumeric("CUST-ID", 9, 9);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(CustomerRecord.RECORD_LENGTH,
                            CustomerRecord.CUST_ID,
                            secondCustId))
                    .withMessageContaining("CUST-ID")
                    .withMessageContaining("more than once");
        }

        @Test
        @DisplayName("A span of zero width is rejected, so no copybook item can vanish silently")
        void aZeroWidthSpanIsRejected() {
            assertThatIllegalArgumentException()
                    .as("CUST-PRI-CARD-HOLDER-IND is the narrowest item in CVCUS01Y at one byte; "
                            + "nothing may be narrower")
                    .isThrownBy(() -> FieldSpan.alphanumeric("CUST-PRI-CARD-HOLDER-IND", 328, 0));
        }

        @ParameterizedTest(name = "{0} bytes is not a CUSTOMER-RECORD")
        @ValueSource(ints = {0, 1, 36, 80, 300, 332, 499, 501, 1000})
        @DisplayName("Decoding any width other than 500 is rejected, naming the declared width")
        void anyWidthOtherThanFiveHundredIsRejected(int wrongWidth) {
            byte[] wrong = new byte[wrongWidth];

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CustomerRecord.decode(wrong, ASCII))
                    .withMessageContaining(String.valueOf(DECLARED_RECORD_LENGTH));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CustomerRecord.decode(wrong, asciiCodec))
                    .withMessageContaining(String.valueOf(DECLARED_RECORD_LENGTH));
        }

        @Test
        @DisplayName("A short or long text image is refused, so a truncated row cannot be loaded")
        void aWrongLengthTextImageIsRefused() {
            String correct = synthesisedImage(ROW_1_PRI_CARD_HOLDER_IND);
            assertThat(correct)
                    .as("the synthesised image must itself be exactly 500 characters, or every "
                            + "assertion built on it would be measuring the wrong thing")
                    .hasSize(DECLARED_RECORD_LENGTH);

            String tooShort = correct.substring(0, DECLARED_RECORD_LENGTH - 1);
            String tooLong = correct + " ";

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CustomerRecord.decode(tooShort, ASCII));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CustomerRecord.decode(tooLong, ASCII));
            assertThatCode(() -> CustomerRecord.decode(correct, ASCII))
                    .as("the accepting side, alongside the two rejections")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("A correct 500-byte image is accepted - the passing side of the same guard")
        void aCorrectImageIsAccepted() {
            byte[] correct = synthesisedImage(ROW_1_PRI_CARD_HOLDER_IND).getBytes(ASCII);

            assertThat(correct).hasSize(DECLARED_RECORD_LENGTH);
            assertThatCode(() -> CustomerRecord.decode(correct, ASCII)).doesNotThrowAnyException();
            assertThatCode(() -> CustomerRecord.decode(correct, asciiCodec)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("CUST-DOB-YYYY-MM-DD - the hyphens are the field name, not formatting")
    class DateOfBirthFieldName {
        @Test
        @DisplayName("The span is named CUST-DOB-YYYY-MM-DD, at offset 308 for 10 bytes")
        void theSpanIsNamedWithHyphens() {
            FieldSpan dob = CustomerRecord.LAYOUT.span("CUST-DOB-YYYY-MM-DD");

            assertThat(dob.name()).isEqualTo("CUST-DOB-YYYY-MM-DD");
            assertThat(dob.offset()).isEqualTo(308);
            assertThat(dob.length()).isEqualTo(10);
            assertThat(dob.kind())
                    .as("PIC X(10): ten characters of text, which is why it is never parsed")
                    .isEqualTo(PictureKind.ALPHANUMERIC);
        }

        @Test
        @DisplayName("No span is named CUST-DOB-YYYYMMDD - that spelling belongs to another layout")
        void noSpanUsesTheUnhyphenatedSpelling() {
            assertThat(CustomerRecord.LAYOUT.hasSpan("CUST-DOB-YYYYMMDD")).isFalse();
            assertThat(CustomerRecord.LAYOUT.spans())
                    .extracting(FieldSpan::name)
                    .doesNotContain("CUST-DOB-YYYYMMDD")
                    .contains("CUST-DOB-YYYY-MM-DD");
        }

        @Test
        @DisplayName("No member of CustomerRecord uses the unhyphenated spelling either")
        void noMemberUsesTheUnhyphenatedSpelling() {
            List<String> offenders = new ArrayList<>();
            for (Field field : CustomerRecord.class.getDeclaredFields()) {
                if (field.getName().contains("Yyyymmdd") || field.getName().contains("YYYYMMDD")) {
                    offenders.add("field " + field.getName());
                }
            }
            for (Method method : CustomerRecord.class.getDeclaredMethods()) {
                if (method.getName().contains("Yyyymmdd") || method.getName().contains("YYYYMMDD")) {
                    offenders.add("method " + method.getName());
                }
            }

            assertThat(offenders)
                    .as("a rename here would still compile and would silently make a real difference "
                            + "invisible to field-for-field diffing")
                    .isEmpty();
            assertThatCode(() -> CustomerRecord.class.getMethod("getCustDobYyyyMmDd"))
                    .as("the accessor keeps the hyphenated field's capitalisation")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("The stored value keeps its hyphens at 1-based positions 5 and 8")
        void theStoredValueKeepsItsHyphens() {
            CustomerRecord record = CustomerRecord.decode(fixtureRow(1), ASCII);

            String dob = record.getCustDobYyyyMmDd();
            assertThat(dob).isEqualTo(ROW_1_DOB).hasSize(10);
            assertThat(dob.charAt(4)).as("the year-month separator").isEqualTo('-');
            assertThat(dob.charAt(7)).as("the month-day separator").isEqualTo('-');
        }

        @Test
        @DisplayName("(1:4) of 1961-06-08 is 1961 - the COACTUPC:3857 slice")
        void theYearSliceIsTheFirstFourCharacters() {
            String dob = CustomerRecord.decode(fixtureRow(1), ASCII).getCustDobYyyyMmDd();

            assertThat(dob.substring(0, 4)).isEqualTo("1961");
            assertThat(dob.substring(5, 7)).as("(6:2), the month").isEqualTo("06");
            assertThat(dob.substring(8, 10)).as("(9:2), the day").isEqualTo("08");
        }

        @ParameterizedTest(name = "row {0}: {1} slices to {2}/{3}/{4}")
        @CsvSource({
            "1,  1961-06-08, 1961, 06, 08",
            "2,  1961-10-08, 1961, 10, 08",
            "26, 1990-03-17, 1990, 03, 17",
            "50, 1960-12-01, 1960, 12, 01",
        })
        @DisplayName("The year, month and day slices hold across measured rows")
        void theSlicesHoldAcrossRows(int recordNumber, String expectedDob, String year, String month,
                                     String day) {
            String dob = CustomerRecord.decode(fixtureRow(recordNumber), ASCII).getCustDobYyyyMmDd();

            assertThat(dob).isEqualTo(expectedDob);
            assertThat(dob.substring(0, 4)).isEqualTo(year);
            assertThat(dob.substring(5, 7)).isEqualTo(month);
            assertThat(dob.substring(8, 10)).isEqualTo(day);
        }

        @Test
        @DisplayName("The value survives encode and decode unchanged, as characters")
        void theValueSurvivesTheRoundTripAsCharacters() {
            CustomerRecord decoded = CustomerRecord.decode(fixtureRow(1), ASCII);

            CustomerRecord reDecoded = CustomerRecord.decode(decoded.encode(ASCII), ASCII);

            assertThat(reDecoded.getCustDobYyyyMmDd()).isEqualTo(ROW_1_DOB);
            assertThat(new String(reDecoded.encode(ASCII), ASCII).substring(308, 318))
                    .as("the stored span, sliced at its own offsets")
                    .isEqualTo(ROW_1_DOB);
        }

        @ParameterizedTest(name = "''{0}'' is carried verbatim")
        @ValueSource(strings = {
            "1961-06-08",
            "0000-00-00",
            "9999-99-99",
            "1961/06/08",
            "19610608  ",
            "          ",
            "not a date",
        })
        @DisplayName("Any ten characters are carried verbatim - the span is text, never a date")
        void anyTenCharactersAreCarriedVerbatim(String stored) {
            CustomerRecord record = new CustomerRecord();

            record.setCustDobYyyyMmDd(stored);

            assertThat(record.getCustDobYyyyMmDd()).isEqualTo(stored).hasSize(10);
            assertThat(new String(record.encode(ASCII), ASCII).substring(308, 318)).isEqualTo(stored);
        }

        @Test
        @DisplayName("A short date-of-birth value is right-space-padded, never zero-filled")
        void aShortValueIsRightSpacePadded() {
            CustomerRecord record = new CustomerRecord();

            record.setCustDobYyyyMmDd("1961");

            assertThat(record.getCustDobYyyyMmDd())
                    .as("PIC X pads on the right with spaces; only PIC 9 fills on the left with zeros")
                    .isEqualTo(padded("1961", 10));
        }
    }

    @Nested
    @DisplayName("CUST-SSN's character image - what COACTVWC slices, and why the value will not do")
    class SocialSecurityNumberImage {
        @Test
        @DisplayName("Row 1 stores 020973888, whose leading zero the int value cannot carry")
        void theStoredImageKeepsItsLeadingZero() {
            CustomerRecord record = CustomerRecord.decode(fixtureRow(1), ASCII);

            assertThat(record.getCustSsn()).isEqualTo(ROW_1_SSN);
            assertThat(record.custSsnImage(ASCII)).isEqualTo(ROW_1_SSN_IMAGE).hasSize(9);
            assertThat(String.valueOf(record.getCustSsn()))
                    .as("String.valueOf gives eight characters where the span is nine - the whole "
                            + "reason a separate image accessor exists")
                    .hasSize(8)
                    .isNotEqualTo(record.custSsnImage(ASCII));
        }

        @Test
        @DisplayName("(1:3), (4:2) and (6:4) of 020973888 are 020, 97 and 3888")
        void theThreeSlicesAreTakenOverTheImage() {
            String image = CustomerRecord.decode(fixtureRow(1), ASCII).custSsnImage(ASCII);

            assertThat(image.substring(0, 3)).as("(1:3)").isEqualTo("020");
            assertThat(image.substring(3, 5)).as("(4:2)").isEqualTo("97");
            assertThat(image.substring(5, 9)).as("(6:4)").isEqualTo("3888");
        }

        @Test
        @DisplayName("The composed screen value is 020-97-3888, eleven characters")
        void theComposedScreenValueIsElevenCharacters() {
            String image = CustomerRecord.decode(fixtureRow(1), ASCII).custSsnImage(ASCII);

            String composed = asciiCodec.concatenateDelimitedBySize(
                    image.substring(0, 3), "-", image.substring(3, 5), "-", image.substring(5, 9));

            assertThat(composed).isEqualTo("020-97-3888").hasSize(11);
        }

        @Test
        @DisplayName("Slicing the numeric value instead would give 209-73-888 - the silent defect")
        void slicingTheValueWouldGiveTheWrongAnswer() {
            CustomerRecord record = CustomerRecord.decode(fixtureRow(1), ASCII);
            String naive = String.valueOf(record.getCustSsn());

            String wrong = naive.substring(0, 3) + "-" + naive.substring(3, 5) + "-"
                    + naive.substring(5);

            assertThat(wrong)
                    .as("recorded so the divergence is visible rather than described: no type system "
                            + "catches this, and the resulting screen value is plausible")
                    .isEqualTo("209-73-888");
            assertThat(wrong).isNotEqualTo("020-97-3888");
        }

        @ParameterizedTest(name = "row {0}: {1} is the value {2}")
        @CsvSource({
            "1,  020973888, 20973888",
            "15, 033922034, 33922034",
            "24, 017590544, 17590544",
            "29, 015027332, 15027332",
            "40, 054960660, 54960660",
            "47, 029222192, 29222192",
        })
        @DisplayName("Every leading-zero SSN in the fixture images at nine digits")
        void everyLeadingZeroSsnImagesAtNineDigits(int recordNumber, String image, int value) {
            CustomerRecord record = CustomerRecord.decode(fixtureRow(recordNumber), ASCII);

            assertThat(record.getCustSsn()).isEqualTo(value);
            assertThat(record.custSsnImage(ASCII)).isEqualTo(image).hasSize(9);
            assertThat(new String(record.encode(ASCII), ASCII).substring(279, 288)).isEqualTo(image);
        }

        @Test
        @DisplayName("Row 24's (6:4) slice is 0544, itself leading-zero bearing")
        void aSliceCanItselfBeLeadingZeroBearing() {
            String image = CustomerRecord.decode(fixtureRow(24), ASCII).custSsnImage(ASCII);

            assertThat(image).isEqualTo(ROW_24_SSN_IMAGE);
            assertThat(image.substring(5, 9))
                    .as("a slice of an image is still characters, so its own leading zero survives too")
                    .isEqualTo("0544");
        }

        @Test
        @DisplayName("A row without a leading zero images identically to its value")
        void aRowWithoutALeadingZeroImagesAsItsValue() {
            CustomerRecord record = CustomerRecord.decode(fixtureRow(2), ASCII);

            assertThat(record.custSsnImage(ASCII)).isEqualTo(ROW_2_SSN_IMAGE);
            assertThat(String.valueOf(record.getCustSsn())).isEqualTo(ROW_2_SSN_IMAGE);
        }

        @Test
        @DisplayName("The image is the same under either code page, and a null charset is refused")
        void theImageIsCodePageIndependentAndGuarded() {
            CustomerRecord record = CustomerRecord.decode(fixtureRow(1), ASCII);

            assertThat(record.custSsnImage(EBCDIC))
                    .as("the image is characters, so it is the same string under either code page - "
                            + "only its BYTES differ, and those are asserted where encoding is")
                    .isEqualTo(record.custSsnImage(ASCII));
            assertThat(record.custSsnImage(ebcdicCodec)).isEqualTo(ROW_1_SSN_IMAGE);
            assertThatNullPointerException().isThrownBy(() -> record.custSsnImage((Charset) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> record.custSsnImage((FixedWidthCodec) null));
        }
    }

    @Nested
    @DisplayName("PIC X parity - right-padded, right-truncated, never trimmed")
    class AlphanumericReceiver {
        @Test
        @DisplayName("A fresh record holds every PIC X field as its declared width in SPACES")
        void aFreshRecordHoldsSpacesAtEveryDeclaredWidth() {
            CustomerRecord record = new CustomerRecord();

            assertThat(record.getCustFirstName()).isEqualTo(spaces(25));
            assertThat(record.getCustMiddleName()).isEqualTo(spaces(25));
            assertThat(record.getCustLastName()).isEqualTo(spaces(25));
            assertThat(record.getCustAddrLine1()).isEqualTo(spaces(50));
            assertThat(record.getCustAddrLine2()).isEqualTo(spaces(50));
            assertThat(record.getCustAddrLine3()).isEqualTo(spaces(50));
            assertThat(record.getCustAddrStateCd()).isEqualTo(spaces(2));
            assertThat(record.getCustAddrCountryCd()).isEqualTo(spaces(3));
            assertThat(record.getCustAddrZip()).isEqualTo(spaces(10));
            assertThat(record.getCustPhoneNum1()).isEqualTo(spaces(15));
            assertThat(record.getCustPhoneNum2()).isEqualTo(spaces(15));
            assertThat(record.getCustGovtIssuedId()).isEqualTo(spaces(20));
            assertThat(record.getCustDobYyyyMmDd()).isEqualTo(spaces(10));
            assertThat(record.getCustEftAccountId()).isEqualTo(spaces(10));
            assertThat(record.getCustPriCardHolderInd()).isEqualTo(spaces(1));
        }

        @Test
        @DisplayName("A short value is right-space-padded, and the getter reports the padded field")
        void aShortValueIsPaddedOnEntry() {
            CustomerRecord record = new CustomerRecord();

            record.setCustFirstName("Alice");

            assertThat(record.getCustFirstName()).isEqualTo(padded("Alice", 25)).hasSize(25);
        }

        @Test
        @DisplayName("An over-wide value keeps its FIRST characters - truncation is on the right")
        void anOverWideValueIsTruncatedOnTheRight() {
            CustomerRecord record = new CustomerRecord();

            record.setCustFirstName("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789");

            assertThat(record.getCustFirstName())
                    .as("a PIC X receiver fills from the left, so the excess is dropped from the right")
                    .isEqualTo("ABCDEFGHIJKLMNOPQRSTUVWXY")
                    .hasSize(25);
        }

        @Test
        @DisplayName("A value of exactly its declared width is stored unchanged")
        void anExactWidthValueIsStoredUnchanged() {
            CustomerRecord record = new CustomerRecord();
            String exactly25 = "ABCDEFGHIJKLMNOPQRSTUVWXY";

            record.setCustFirstName(exactly25);

            assertThat(record.getCustFirstName()).isEqualTo(exactly25).hasSize(25);
        }

        @Test
        @DisplayName("An empty string and a run of spaces are indistinguishable, as in COBOL")
        void anEmptyStringAndSpacesAreIndistinguishable() {
            CustomerRecord fromEmpty = new CustomerRecord();
            CustomerRecord fromSpaces = new CustomerRecord();

            fromEmpty.setCustAddrStateCd("");
            fromSpaces.setCustAddrStateCd("  ");

            assertThat(fromEmpty.getCustAddrStateCd()).isEqualTo(fromSpaces.getCustAddrStateCd());
            assertThat(fromEmpty).isEqualTo(fromSpaces);
        }

        @Test
        @DisplayName("The getter, equals, hashCode and encode all observe the SAME state")
        void everyObservationAgrees() {
            CustomerRecord overWide = new CustomerRecord();
            overWide.setCustFirstName("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123");
            CustomerRecord atWidth = new CustomerRecord();
            atWidth.setCustFirstName("ABCDEFGHIJKLMNOPQRSTUVWXY");

            assertThat(overWide.getCustFirstName()).isEqualTo(atWidth.getCustFirstName());
            assertThat(overWide).isEqualTo(atWidth);
            assertThat(overWide.hashCode()).isEqualTo(atWidth.hashCode());
            assertThat(overWide.encode(ASCII)).isEqualTo(atWidth.encode(ASCII));
        }

        @ParameterizedTest(name = "{0} PIC X({1})")
        @CsvSource({
            "CUST-FIRST-NAME,            25",
            "CUST-MIDDLE-NAME,           25",
            "CUST-LAST-NAME,             25",
            "CUST-ADDR-LINE-1,           50",
            "CUST-ADDR-LINE-2,           50",
            "CUST-ADDR-LINE-3,           50",
            "CUST-ADDR-STATE-CD,         2",
            "CUST-ADDR-COUNTRY-CD,       3",
            "CUST-ADDR-ZIP,              10",
            "CUST-PHONE-NUM-1,           15",
            "CUST-PHONE-NUM-2,           15",
            "CUST-GOVT-ISSUED-ID,        20",
            "CUST-DOB-YYYY-MM-DD,        10",
            "CUST-EFT-ACCOUNT-ID,        10",
            "CUST-PRI-CARD-HOLDER-IND,   1",
        })
        @DisplayName("Every PIC X field pads and truncates at its own declared width")
        void everyAlphanumericFieldPadsAndTruncatesAtItsWidth(String field, int width) {
            assertThat(CustomerRecord.LAYOUT.span(field).length())
                    .as("the declared width transcribed above must be the declared width")
                    .isEqualTo(width);

            int offset = CustomerRecord.LAYOUT.span(field).offset();
            String tooLong = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
            String shortValue = "A";

            CustomerRecord shortly = new CustomerRecord();
            setAlphanumeric(shortly, field, shortValue);
            CustomerRecord overWide = new CustomerRecord();
            setAlphanumeric(overWide, field, tooLong);

            assertThat(new String(shortly.encode(ASCII), ASCII).substring(offset, offset + width))
                    .as("%s pads a one-character value on the right", field)
                    .isEqualTo(padded(shortValue.substring(0, Math.min(shortValue.length(), width)),
                            width));
            assertThat(new String(overWide.encode(ASCII), ASCII).substring(offset, offset + width))
                    .as("%s truncates on the right at its declared width", field)
                    .isEqualTo(tooLong.substring(0, width));
        }

        private void setAlphanumeric(CustomerRecord record, String field, String value) {
            switch (field) {
                case "CUST-FIRST-NAME" -> record.setCustFirstName(value);
                case "CUST-MIDDLE-NAME" -> record.setCustMiddleName(value);
                case "CUST-LAST-NAME" -> record.setCustLastName(value);
                case "CUST-ADDR-LINE-1" -> record.setCustAddrLine1(value);
                case "CUST-ADDR-LINE-2" -> record.setCustAddrLine2(value);
                case "CUST-ADDR-LINE-3" -> record.setCustAddrLine3(value);
                case "CUST-ADDR-STATE-CD" -> record.setCustAddrStateCd(value);
                case "CUST-ADDR-COUNTRY-CD" -> record.setCustAddrCountryCd(value);
                case "CUST-ADDR-ZIP" -> record.setCustAddrZip(value);
                case "CUST-PHONE-NUM-1" -> record.setCustPhoneNum1(value);
                case "CUST-PHONE-NUM-2" -> record.setCustPhoneNum2(value);
                case "CUST-GOVT-ISSUED-ID" -> record.setCustGovtIssuedId(value);
                case "CUST-DOB-YYYY-MM-DD" -> record.setCustDobYyyyMmDd(value);
                case "CUST-EFT-ACCOUNT-ID" -> record.setCustEftAccountId(value);
                case "CUST-PRI-CARD-HOLDER-IND" -> record.setCustPriCardHolderInd(value);
                default -> throw new IllegalArgumentException("Not a PIC X field of CVCUS01Y: "
                        + field);
            }
        }

        @ParameterizedTest(name = "{0} refuses null")
        @CsvSource({
            "CUST-FIRST-NAME,            25",
            "CUST-MIDDLE-NAME,           25",
            "CUST-LAST-NAME,             25",
            "CUST-ADDR-LINE-1,           50",
            "CUST-ADDR-LINE-2,           50",
            "CUST-ADDR-LINE-3,           50",
            "CUST-ADDR-STATE-CD,         2",
            "CUST-ADDR-COUNTRY-CD,       3",
            "CUST-ADDR-ZIP,              10",
            "CUST-PHONE-NUM-1,           15",
            "CUST-PHONE-NUM-2,           15",
            "CUST-GOVT-ISSUED-ID,        20",
            "CUST-DOB-YYYY-MM-DD,        10",
            "CUST-EFT-ACCOUNT-ID,        10",
            "CUST-PRI-CARD-HOLDER-IND,   1",
        })
        @DisplayName("null is refused by every PIC X setter, naming the field and the alternative")
        void nullIsRefusedByEveryAlphanumericSetter(String field, int width) {
            CustomerRecord record = new CustomerRecord();

            assertThatNullPointerException()
                    .isThrownBy(() -> setAlphanumeric(record, field, null))
                    .withMessageContaining(field)
                    .withMessageContaining("COBOL has no null")
                    .withMessageContaining("PIC X(" + width + ")");
        }

        @Test
        @DisplayName("CUST-ADDR-ZIP carries both a padded zip and a full ZIP+4 in the same span")
        void theZipSpanCarriesTwoContentWidths() {
            CustomerRecord shortZip = CustomerRecord.decode(fixtureRow(1), ASCII);
            CustomerRecord fullZip = CustomerRecord.decode(fixtureRow(3), ASCII);

            assertThat(shortZip.getCustAddrZip())
                    .as("the trailing spaces are part of the field and must survive the read")
                    .isEqualTo(padded(ROW_1_ZIP, 10))
                    .hasSize(10);
            assertThat(fullZip.getCustAddrZip()).isEqualTo(ROW_3_ZIP).hasSize(10);
            assertThat(new String(shortZip.encode(ASCII), ASCII).substring(239, 249))
                    .as("and must survive the write, or the row would shrink")
                    .isEqualTo(padded(ROW_1_ZIP, 10));
            assertThat(new String(fullZip.encode(ASCII), ASCII).substring(239, 249))
                    .isEqualTo(ROW_3_ZIP);
        }

        @ParameterizedTest(name = "row {0} zip content is ''{1}''")
        @CsvSource({
            "1,  12546",
            "2,  22770",
            "3,  19852-6716",
            "5,  02251-1698",
            "44, 05704-0501",
            "50, 04257",
        })
        @DisplayName("Both zip shapes round-trip out of the same PIC X(10) span")
        void bothZipShapesRoundTrip(int recordNumber, String content) {
            String stored = padded(content, 10);
            assertThat(stored)
                    .as("each composed expectation must itself be ten characters")
                    .hasSize(10);
            CustomerRecord record = CustomerRecord.decode(fixtureRow(recordNumber), ASCII);

            assertThat(record.getCustAddrZip()).isEqualTo(stored);
            assertThat(new String(record.encode(ASCII), ASCII).substring(239, 249)).isEqualTo(stored);
        }

        @Test
        @DisplayName("All-digit PIC X fields stay Strings and keep their leading zeros")
        void allDigitAlphanumericFieldsStayStrings() throws NoSuchMethodException {
            CustomerRecord record = CustomerRecord.decode(fixtureRow(1), ASCII);

            assertThat(record.getCustGovtIssuedId()).isEqualTo(ROW_1_GOVT_ISSUED_ID).hasSize(20);
            assertThat(record.getCustEftAccountId()).isEqualTo(ROW_1_EFT_ACCOUNT_ID).hasSize(10);
            assertThat(CustomerRecord.class.getMethod("getCustGovtIssuedId").getReturnType())
                    .isEqualTo(String.class);
            assertThat(CustomerRecord.class.getMethod("getCustEftAccountId").getReturnType())
                    .isEqualTo(String.class);
            assertThat(CustomerRecord.class.getMethod("getCustAddrZip").getReturnType())
                    .isEqualTo(String.class);
            assertThat(CustomerRecord.LAYOUT.span("CUST-GOVT-ISSUED-ID").kind())
                    .isEqualTo(PictureKind.ALPHANUMERIC);
            assertThat(CustomerRecord.LAYOUT.span("CUST-EFT-ACCOUNT-ID").kind())
                    .isEqualTo(PictureKind.ALPHANUMERIC);
        }

        @Test
        @DisplayName("The indicator the fixture cannot supply - 'N' - is fully supported")
        void theIndicatorValueTheFixtureLacksIsSupported() {
            CustomerRecord notPrimary = CustomerRecord.decode(synthesisedImage("N"), ASCII);

            assertThat(notPrimary.getCustPriCardHolderInd()).isEqualTo("N");
            assertThat(new String(notPrimary.encode(ASCII), ASCII).substring(328, 329)).isEqualTo("N");

            CustomerRecord blankIndicator = CustomerRecord.decode(synthesisedImage(" "), ASCII);
            assertThat(blankIndicator.getCustPriCardHolderInd()).isEqualTo(" ");
        }

        @Test
        @DisplayName("A blank named field - which no fixture row holds - round-trips as spaces")
        void aBlankNamedFieldRoundTrips() {
            CustomerRecord record = CustomerRecord.decode(synthesisedImage("Y"), ASCII);

            assertThat(record.getCustAddrLine2()).isEqualTo(spaces(50));
            assertThat(record.getCustAddrLine3()).isEqualTo(spaces(50));
            assertThat(record.getCustPhoneNum2()).isEqualTo(spaces(15));
            assertThat(record.getCustGovtIssuedId()).isEqualTo(spaces(20));
            assertThat(record.encode(ASCII)).hasSize(DECLARED_RECORD_LENGTH);
        }
    }

    @Nested
    @DisplayName("PIC 9 parity - left zero-fill, left truncation, no sign position")
    class NumericReceiver {
        @Test
        @DisplayName("A fresh record holds zero in all three numeric fields, imaged at full width")
        void aFreshRecordHoldsZeroImagedAtFullWidth() {
            CustomerRecord record = new CustomerRecord();

            assertThat(record.getCustId()).isZero();
            assertThat(record.getCustSsn()).isZero();
            assertThat(record.getCustFicoCreditScore()).isZero();
            assertThat(record.custIdImage(ASCII)).isEqualTo("000000000");
            assertThat(record.custSsnImage(ASCII)).isEqualTo("000000000");
            assertThat(record.custFicoCreditScoreImage(ASCII)).isEqualTo("000");
        }

        @Test
        @DisplayName("CUST-ID 1 images as 000000001 - nine digits, filled on the left")
        void custIdOneImagesAsNineDigits() {
            CustomerRecord record = new CustomerRecord();

            record.setCustId(1);

            assertThat(record.custIdImage(ASCII)).isEqualTo(ROW_1_CUST_ID_IMAGE).hasSize(9);
            assertThat(new String(record.encode(ASCII), ASCII).substring(0, 9))
                    .isEqualTo(ROW_1_CUST_ID_IMAGE);
        }

        @Test
        @DisplayName("FICO 1 images as 001 - the fixture's own row 26, and the best zero-fill proof")
        void ficoOneImagesAsThreeDigits() {
            CustomerRecord fromFixture = CustomerRecord.decode(fixtureRow(26), ASCII);

            assertThat(fromFixture.getCustFicoCreditScore()).isEqualTo(ROW_26_FICO);
            assertThat(fromFixture.custFicoCreditScoreImage(ASCII))
                    .isEqualTo(ROW_26_FICO_IMAGE)
                    .hasSize(3)
                    .isNotEqualTo(String.valueOf(ROW_26_FICO));
            assertThat(new String(fromFixture.encode(ASCII), ASCII).substring(329, 332))
                    .isEqualTo(ROW_26_FICO_IMAGE);
            assertThat(fromFixture.custIdImage(ASCII)).isEqualTo(ROW_26_CUST_ID_IMAGE);
        }

        @ParameterizedTest(name = "row {0}: {1} is the value {2}")
        @CsvSource({
            "8,  051, 51",
            "13, 053, 53",
            "17, 054, 54",
            "26, 001, 1",
            "27, 078, 78",
            "31, 058, 58",
            "42, 044, 44",
        })
        @DisplayName("Every leading-zero FICO score in the fixture images at three digits")
        void everyLeadingZeroFicoImagesAtThreeDigits(int recordNumber, String image, int value) {
            CustomerRecord record = CustomerRecord.decode(fixtureRow(recordNumber), ASCII);

            assertThat(record.getCustFicoCreditScore()).isEqualTo(value);
            assertThat(record.custFicoCreditScoreImage(ASCII)).isEqualTo(image);
            assertThat(new String(record.encode(ASCII), ASCII).substring(329, 332)).isEqualTo(image);
        }

        @ParameterizedTest(name = "setCustId({0}) holds {1}")
        @CsvSource({
            "0,            0",
            "1,            1",
            "999999999,    999999999",
            "1234567890,   234567890",
            "2000000001,   1",
        })
        @DisplayName("An over-wide CUST-ID loses its HIGH-order digits, as a numeric MOVE does")
        void anOverWideValueLosesItsLeadingDigits(int supplied, int held) {
            CustomerRecord record = new CustomerRecord();

            record.setCustId(supplied);

            assertThat(record.getCustId()).isEqualTo(held);
            assertThat(record.custIdImage(ASCII)).hasSize(9);
        }

        @Test
        @DisplayName("An over-wide FICO score keeps its RIGHTMOST three digits")
        void anOverWideFicoKeepsItsRightmostDigits() {
            CustomerRecord record = new CustomerRecord();

            record.setCustFicoCreditScore(1850);

            assertThat(record.getCustFicoCreditScore()).isEqualTo(850);
            assertThat(record.custFicoCreditScoreImage(ASCII)).isEqualTo("850");
        }

        @Test
        @DisplayName("The held value and the encoded image can never disagree")
        void theHeldValueAndTheImageAgree() {
            CustomerRecord record = new CustomerRecord();

            record.setCustSsn(1234567890);

            assertThat(record.getCustSsn()).isEqualTo(234567890);
            assertThat(record.custSsnImage(ASCII)).isEqualTo("234567890");
            assertThat(new String(record.encode(ASCII), ASCII).substring(279, 288))
                    .isEqualTo("234567890");
        }

        @Test
        @DisplayName("The two truncation directions genuinely differ, side by side")
        void theTwoTruncationDirectionsDiffer() {
            CustomerRecord record = new CustomerRecord();

            record.setCustAddrCountryCd("USAX");
            record.setCustFicoCreditScore(7204);

            assertThat(record.getCustAddrCountryCd())
                    .as("PIC X(03) keeps the first three characters")
                    .isEqualTo("USA");
            assertThat(record.getCustFicoCreditScore())
                    .as("PIC 9(03) keeps the last three digits")
                    .isEqualTo(204);
        }

        @ParameterizedTest(name = "{0} is refused")
        @ValueSource(ints = {-1, -9, -999999999, Integer.MIN_VALUE})
        @DisplayName("A negative value is refused - PIC 9 declares no sign position")
        void aNegativeValueIsRefused(int negative) {
            CustomerRecord record = new CustomerRecord();

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> record.setCustId(negative))
                    .withMessageContaining("CUST-ID")
                    .withMessageContaining("no sign position");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> record.setCustSsn(negative))
                    .withMessageContaining("CUST-SSN");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> record.setCustFicoCreditScore(negative))
                    .withMessageContaining("CUST-FICO-CREDIT-SCORE");
        }

        @Test
        @DisplayName("Zero is accepted - the boundary on the accepting side of the same guard")
        void zeroIsAccepted() {
            CustomerRecord record = new CustomerRecord();

            assertThatCode(() -> {
                record.setCustId(0);
                record.setCustSsn(0);
                record.setCustFicoCreditScore(0);
            }).doesNotThrowAnyException();
            assertThat(record.custIdImage(ASCII)).isEqualTo("000000000");
        }

        @Test
        @DisplayName("The widest value each field can hold round-trips through its span")
        void theWidestRepresentableValuesRoundTrip() {
            CustomerRecord record = new CustomerRecord();

            record.setCustId(999999999);
            record.setCustSsn(999999999);
            record.setCustFicoCreditScore(999);

            String image = new String(record.encode(ASCII), ASCII);
            assertThat(image.substring(0, 9)).isEqualTo("999999999");
            assertThat(image.substring(279, 288)).isEqualTo("999999999");
            assertThat(image.substring(329, 332)).isEqualTo("999");
            assertThat(CustomerRecord.decode(image, ASCII)).isEqualTo(record);
        }

        @Test
        @DisplayName("The nine-digit fields are int, not long - AAP rule R4")
        void theNineDigitFieldsAreInt() throws NoSuchMethodException {
            assertThat(CustomerRecord.class.getMethod("getCustId").getReturnType())
                    .isEqualTo(int.class);
            assertThat(CustomerRecord.class.getMethod("getCustSsn").getReturnType())
                    .isEqualTo(int.class);
            assertThat(CustomerRecord.class.getMethod("getCustFicoCreditScore").getReturnType())
                    .isEqualTo(int.class);
        }

        @Test
        @DisplayName("An image carries no grouping separator, so no locale can change it")
        void anImageCarriesNoGroupingSeparator() {
            CustomerRecord record = new CustomerRecord();
            record.setCustId(1234567);
            record.setCustFicoCreditScore(123);

            assertThat(record.custIdImage(ASCII))
                    .isEqualTo("001234567")
                    .doesNotContain(",")
                    .doesNotContain(".")
                    .doesNotContain(" ");
            assertThat(record.custFicoCreditScoreImage(ASCII)).isEqualTo("123");
            assertThat(record.custIdImage(ASCII))
                    .as("and it is identical when the same digits are asked for under a Turkish locale, "
                            + "because nothing here consults a locale at all")
                    .isEqualTo("001234567".toLowerCase(Locale.forLanguageTag("tr")));
        }

        @Test
        @DisplayName("Each image accessor is offered in both a charset and a codec form, which agree")
        void eachImageAccessorIsOfferedInBothForms() {
            CustomerRecord record = CustomerRecord.decode(fixtureRow(1), ASCII);

            assertThat(record.custIdImage(asciiCodec)).isEqualTo(record.custIdImage(ASCII));
            assertThat(record.custSsnImage(asciiCodec)).isEqualTo(record.custSsnImage(ASCII));
            assertThat(record.custFicoCreditScoreImage(asciiCodec))
                    .isEqualTo(record.custFicoCreditScoreImage(ASCII));
            assertThatNullPointerException().isThrownBy(() -> record.custIdImage((Charset) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> record.custIdImage((FixedWidthCodec) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> record.custFicoCreditScoreImage((Charset) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> record.custFicoCreditScoreImage((FixedWidthCodec) null));
        }

        @Test
        @DisplayName("A stored numeric span holding a non-digit fails loudly, never decodes as zero")
        void aNonDigitInANumericSpanFailsLoudly() {
            String corrupted = "0000000O1" + synthesisedImage("Y").substring(9);

            assertThat(corrupted).hasSize(DECLARED_RECORD_LENGTH);
            assertThatIllegalArgumentException()
                    .as("a letter O where a zero belongs is a real dataset defect; decoding it as 1 "
                            + "would post the record under the wrong key")
                    .isThrownBy(() -> CustomerRecord.decode(corrupted, ASCII));
        }
    }

    @Nested
    @DisplayName("FILLER X(168) is emitted and space-filled on every encode (G21)")
    class FillerSpan {
        @Test
        @DisplayName("The FILLER span is declared at [332, 500) as a first-class FILLER descriptor")
        void theFillerSpanIsDeclaredExplicitly() {
            FieldSpan filler = CustomerRecord.FILLER;

            assertThat(filler.name()).isEqualTo("FILLER");
            assertThat(filler.offset()).isEqualTo(FILLER_OFFSET);
            assertThat(filler.length()).isEqualTo(FILLER_LENGTH);
            assertThat(filler.endOffsetExclusive()).isEqualTo(DECLARED_RECORD_LENGTH);
            assertThat(filler.kind().filler())
                    .as("declared as FILLER rather than as an anonymous alphanumeric span, so the "
                            + "layout can initialise it without a value and keep it non-referable")
                    .isTrue();
            assertThat(filler.hasInitialValue())
                    .as("CVCUS01Y declares no VALUE on its FILLER, so it initialises to spaces")
                    .isFalse();
        }

        @Test
        @DisplayName("Bytes [332, 500) of a record built from field values are 168 spaces")
        void theFillerIsSpaceFilledOnTheWritePath() {
            String image = new String(row1AsBuilt().encode(ASCII), ASCII);

            assertThat(image).hasSize(DECLARED_RECORD_LENGTH);
            assertThat(image.substring(FILLER_OFFSET))
                    .isEqualTo(spaces(FILLER_LENGTH))
                    .hasSize(FILLER_LENGTH);
        }

        @Test
        @DisplayName("Bytes [332, 500) of a record decoded from fixture row 1 are 168 spaces")
        void theFillerIsSpaceFilledOnTheReadPath() {
            String image = new String(CustomerRecord.decode(fixtureRow(1), ASCII).encode(ASCII), ASCII);

            assertThat(image.substring(FILLER_OFFSET)).isEqualTo(spaces(FILLER_LENGTH));
        }

        @Test
        @DisplayName("An untouched, freshly constructed record still emits its FILLER")
        void anUntouchedRecordStillEmitsItsFiller() {
            String image = new String(new CustomerRecord().encode(ASCII), ASCII);

            assertThat(image).hasSize(DECLARED_RECORD_LENGTH);
            assertThat(image.substring(FILLER_OFFSET)).isEqualTo(spaces(FILLER_LENGTH));
        }

        @Test
        @DisplayName("Every FILLER byte is 0x20 individually, never 0x00")
        void everyFillerByteIsAnAsciiSpace() {
            byte[] image = row1AsBuilt().encode(ASCII);

            for (int offset = FILLER_OFFSET; offset < DECLARED_RECORD_LENGTH; offset++) {
                assertThat(image[offset])
                        .as("byte %d of the FILLER span", offset)
                        .isEqualTo(ASCII_SPACE_BYTE);
            }
        }

        @Test
        @DisplayName("The FILLER is space-filled under IBM037 too - 0x40, not 0x20 and not 0x00")
        void theFillerIsSpaceFilledInEbcdicToo() {
            byte[] image = row1AsBuilt().encode(EBCDIC);

            assertThat(image).hasSize(DECLARED_RECORD_LENGTH);
            for (int offset = FILLER_OFFSET; offset < DECLARED_RECORD_LENGTH; offset++) {
                assertThat(image[offset])
                        .as("byte %d of the FILLER span under IBM037", offset)
                        .isEqualTo(EBCDIC_SPACE_BYTE);
            }
        }

        @Test
        @DisplayName("The record area exposes the FILLER span as addressable bytes")
        void theRecordAreaExposesTheFillerSpan() {
            FixedWidthRecord area = asciiCodec.wrap(row1AsBuilt().encode(ASCII), CustomerRecord.LAYOUT);

            assertThat(area.recordLength()).isEqualTo(DECLARED_RECORD_LENGTH);
            assertThat(area.readSpan(CustomerRecord.FILLER)).isEqualTo(spaces(FILLER_LENGTH));
            assertThat(area.readSpanBytes(CustomerRecord.FILLER)).hasSize(FILLER_LENGTH);
        }

        @Test
        @DisplayName("The FILLER's 168 bytes are exactly what the width leaves over after 18 fields")
        void theFillerAccountsForEveryRemainingByte() {
            int named = 0;
            for (FieldSpan span : CustomerRecord.LAYOUT.storageSpans()) {
                if (!span.kind().filler()) {
                    named += span.length();
                }
            }

            assertThat(named)
                    .as("the 18 named items of CVCUS01Y occupy 332 bytes")
                    .isEqualTo(FILLER_OFFSET);
            assertThat(DECLARED_RECORD_LENGTH - named)
                    .as("so the FILLER accounts for the remaining 168")
                    .isEqualTo(FILLER_LENGTH);
        }
    }

    @Nested
    @DisplayName("The real fixture - 50 rows of 500 bytes, none needing normalisation")
    class RealFixture {
        @Test
        @DisplayName("The fixture is on the test classpath and holds 50 rows of exactly 500 bytes")
        void theFixtureIsFiftyRowsOfFiveHundredBytes() {
            List<String> rows = fixtureRows();

            assertThat(rows).hasSize(FIXTURE_ROW_COUNT);
            for (int index = 0; index < rows.size(); index++) {
                assertThat(rows.get(index))
                        .as("row %d must be exactly 500 characters - no customer row needs widening",
                                index + 1)
                        .hasSize(DECLARED_RECORD_LENGTH);
            }
        }

        @Test
        @DisplayName("The keys run 000000001 to 000000050, ascending and unique")
        void theKeysAreAscendingAndUnique() {
            List<String> rows = fixtureRows();
            Set<String> keys = new LinkedHashSet<>();
            String previous = null;

            for (String row : rows) {
                String key = row.substring(0, PRIMARY_KEY_LENGTH);
                assertThat(keys.add(key)).as("key %s must appear once", key).isTrue();
                if (previous != null) {
                    assertThat(key.compareTo(previous))
                            .as("a KSDS browse returns ascending keys, so the fixture must be ordered")
                            .isPositive();
                }
                previous = key;
            }

            assertThat(keys).hasSize(FIXTURE_ROW_COUNT);
            assertThat(rows.get(0).substring(0, PRIMARY_KEY_LENGTH)).isEqualTo(ROW_1_CUST_ID_IMAGE);
            assertThat(rows.get(1).substring(0, PRIMARY_KEY_LENGTH)).isEqualTo(ROW_2_CUST_ID_IMAGE);
            assertThat(rows.get(FIXTURE_ROW_COUNT - 1).substring(0, PRIMARY_KEY_LENGTH))
                    .isEqualTo(ROW_50_CUST_ID_IMAGE);
        }

        @Test
        @DisplayName("Row 1 decodes to all 18 named fields, each at its measured value")
        void rowOneDecodesToAllEighteenFields() {
            CustomerRecord record = CustomerRecord.decode(fixtureRow(1), ASCII);

            assertThat(record.getCustId()).isEqualTo(ROW_1_CUST_ID);
            assertThat(record.custIdImage(ASCII)).isEqualTo(ROW_1_CUST_ID_IMAGE);
            assertThat(record.getCustFirstName()).isEqualTo(padded(ROW_1_FIRST_NAME, 25));
            assertThat(record.getCustMiddleName()).isEqualTo(padded(ROW_1_MIDDLE_NAME, 25));
            assertThat(record.getCustLastName()).isEqualTo(padded(ROW_1_LAST_NAME, 25));
            assertThat(record.getCustAddrLine1()).isEqualTo(padded(ROW_1_ADDR_LINE_1, 50));
            assertThat(record.getCustAddrLine2()).isEqualTo(padded(ROW_1_ADDR_LINE_2, 50));
            assertThat(record.getCustAddrLine3()).isEqualTo(padded(ROW_1_ADDR_LINE_3, 50));
            assertThat(record.getCustAddrStateCd()).isEqualTo(ROW_1_STATE_CD);
            assertThat(record.getCustAddrCountryCd()).isEqualTo(ROW_1_COUNTRY_CD);
            assertThat(record.getCustAddrZip()).isEqualTo(padded(ROW_1_ZIP, 10));
            assertThat(record.getCustPhoneNum1()).isEqualTo(padded(ROW_1_PHONE_1, 15));
            assertThat(record.getCustPhoneNum2()).isEqualTo(padded(ROW_1_PHONE_2, 15));
            assertThat(record.getCustSsn()).isEqualTo(ROW_1_SSN);
            assertThat(record.custSsnImage(ASCII)).isEqualTo(ROW_1_SSN_IMAGE);
            assertThat(record.getCustGovtIssuedId()).isEqualTo(ROW_1_GOVT_ISSUED_ID);
            assertThat(record.getCustDobYyyyMmDd()).isEqualTo(ROW_1_DOB);
            assertThat(record.getCustEftAccountId()).isEqualTo(ROW_1_EFT_ACCOUNT_ID);
            assertThat(record.getCustPriCardHolderInd()).isEqualTo(ROW_1_PRI_CARD_HOLDER_IND);
            assertThat(record.getCustFicoCreditScore()).isEqualTo(ROW_1_FICO);
            assertThat(record.custFicoCreditScoreImage(ASCII)).isEqualTo("274");
        }

        @Test
        @DisplayName("A record built from row 1's values equals the decoded one, byte for byte")
        void aBuiltRecordEqualsTheDecodedRow() {
            CustomerRecord decoded = CustomerRecord.decode(fixtureRow(1), ASCII);

            CustomerRecord built = row1AsBuilt();

            assertThat(built)
                    .as("the setters and the decoder must arrive at one state, since both apply the "
                            + "same PICTURE receiver")
                    .isEqualTo(decoded);
            assertThat(built.hashCode()).isEqualTo(decoded.hashCode());
            assertThat(built.encode(ASCII)).isEqualTo(fixtureRow(1).getBytes(ASCII));
        }

        @Test
        @DisplayName("Row 2 decodes to Enrico April Rosenbaum of Indiana")
        void rowTwoDecodes() {
            CustomerRecord record = CustomerRecord.decode(fixtureRow(2), ASCII);

            assertThat(record.custIdImage(ASCII)).isEqualTo(ROW_2_CUST_ID_IMAGE);
            assertThat(record.getCustFirstName()).isEqualTo(padded(ROW_2_FIRST_NAME, 25));
            assertThat(record.getCustMiddleName()).isEqualTo(padded(ROW_2_MIDDLE_NAME, 25));
            assertThat(record.getCustLastName()).isEqualTo(padded(ROW_2_LAST_NAME, 25));
            assertThat(record.getCustAddrLine1()).isEqualTo(padded(ROW_2_ADDR_LINE_1, 50));
            assertThat(record.getCustAddrStateCd()).isEqualTo(ROW_2_STATE_CD);
            assertThat(record.getCustAddrZip()).isEqualTo(padded(ROW_2_ZIP, 10));
            assertThat(record.custSsnImage(ASCII)).isEqualTo(ROW_2_SSN_IMAGE);
            assertThat(record.getCustDobYyyyMmDd()).isEqualTo(ROW_2_DOB);
            assertThat(record.getCustEftAccountId()).isEqualTo(ROW_2_EFT_ACCOUNT_ID);
            assertThat(record.getCustFicoCreditScore()).isEqualTo(ROW_2_FICO);
        }

        @Test
        @DisplayName("Row 26 decodes its FICO 001 to 1 - the stored left zero-fill, read back")
        void rowTwentySixDecodes() {
            CustomerRecord record = CustomerRecord.decode(fixtureRow(26), ASCII);

            assertThat(record.custIdImage(ASCII)).isEqualTo(ROW_26_CUST_ID_IMAGE);
            assertThat(record.getCustFirstName()).isEqualTo(padded(ROW_26_FIRST_NAME, 25));
            assertThat(record.getCustAddrLine1()).isEqualTo(padded(ROW_26_ADDR_LINE_1, 50));
            assertThat(record.getCustFicoCreditScore()).isEqualTo(ROW_26_FICO);
            assertThat(record.custFicoCreditScoreImage(ASCII)).isEqualTo(ROW_26_FICO_IMAGE);
        }

        @Test
        @DisplayName("Row 50, the last, decodes to Aniya Alba Von of Oregon")
        void rowFiftyDecodes() {
            CustomerRecord record = CustomerRecord.decode(fixtureRow(FIXTURE_ROW_COUNT), ASCII);

            assertThat(record.custIdImage(ASCII)).isEqualTo(ROW_50_CUST_ID_IMAGE);
            assertThat(record.getCustFirstName()).isEqualTo(padded(ROW_50_FIRST_NAME, 25));
            assertThat(record.getCustMiddleName()).isEqualTo(padded(ROW_50_MIDDLE_NAME, 25));
            assertThat(record.getCustLastName()).isEqualTo(padded(ROW_50_LAST_NAME, 25));
            assertThat(record.getCustAddrLine1()).isEqualTo(padded(ROW_50_ADDR_LINE_1, 50));
            assertThat(record.getCustAddrStateCd()).isEqualTo(ROW_50_STATE_CD);
            assertThat(record.getCustAddrZip()).isEqualTo(padded(ROW_50_ZIP, 10));
            assertThat(record.custSsnImage(ASCII)).isEqualTo(ROW_50_SSN_IMAGE);
            assertThat(record.getCustDobYyyyMmDd()).isEqualTo(ROW_50_DOB);
            assertThat(record.getCustEftAccountId()).isEqualTo(ROW_50_EFT_ACCOUNT_ID);
            assertThat(record.getCustFicoCreditScore()).isEqualTo(ROW_50_FICO);
        }

        @ParameterizedTest(name = "row {0} round-trips byte-identically")
        @MethodSource("com.vsergeychik.carddemo.customer.model.CustomerRecordTest#everyFixtureRow")
        @DisplayName("Every one of the 50 rows survives decode then encode byte-identically")
        void everyRowRoundTripsByteIdentically(int recordNumber, String row) {
            byte[] stored = row.getBytes(ASCII);
            assertThat(stored).as("row %d", recordNumber).hasSize(DECLARED_RECORD_LENGTH);

            CustomerRecord decoded = CustomerRecord.decode(stored, ASCII);

            assertThat(decoded.encode(ASCII))
                    .as("row %d must re-encode to the very bytes it was read from", recordNumber)
                    .isEqualTo(stored);
            assertThat(decoded.recordImage(ASCII))
                    .as("row %d's group image is the stored row", recordNumber)
                    .isEqualTo(row);
            assertThat(CustomerRecord.decode(decoded.encode(ASCII), ASCII))
                    .as("row %d is stable across a second cycle", recordNumber)
                    .isEqualTo(decoded);
        }

        @Test
        @DisplayName("Every row ends with 168 spaces, confirming the FILLER in the real data")
        void everyRowEndsWithOneHundredSixtyEightSpaces() {
            for (String row : fixtureRows()) {
                assertThat(row.substring(FILLER_OFFSET))
                        .as("the FILLER of row %s", row.substring(0, PRIMARY_KEY_LENGTH))
                        .isEqualTo(spaces(FILLER_LENGTH));
            }
        }

        @Test
        @DisplayName("Every row carries CUST-PRI-CARD-HOLDER-IND 'Y' - why 'N' has to be synthesised")
        void everyRowCarriesTheSameIndicator() {
            Set<String> indicators = new LinkedHashSet<>();
            for (String row : fixtureRows()) {
                indicators.add(row.substring(328, 329));
            }

            assertThat(indicators)
                    .as("the fixture offers no 'N' row, which is a property of the sample data and the "
                            + "reason the 'N' case is built inline instead")
                    .containsExactly(ROW_1_PRI_CARD_HOLDER_IND);
        }

        @Test
        @DisplayName("Every row carries country USA, and 36 distinct state codes appear across the 50")
        void theCountryIsUniformAndTheStatesAreNot() {
            Set<String> countries = new LinkedHashSet<>();
            Set<String> states = new LinkedHashSet<>();
            for (String row : fixtureRows()) {
                states.add(row.substring(234, 236));
                countries.add(row.substring(236, 239));
            }

            assertThat(countries).containsExactly(ROW_1_COUNTRY_CD);
            assertThat(states)
                    .as("re-measured for this test, and worth measuring because a two-byte span read at "
                            + "the wrong offset would still look like plausible codes")
                    .hasSize(36);
        }

        @Test
        @DisplayName("No named field is blank in any row - why the blank case is synthetic")
        void noNamedFieldIsBlankInAnyRow() {
            List<String> blanks = new ArrayList<>();
            for (String row : fixtureRows()) {
                for (FieldSpan span : CustomerRecord.LAYOUT.storageSpans()) {
                    if (span.kind().filler()) {
                        continue;
                    }
                    String value = row.substring(span.offset(), span.endOffsetExclusive());
                    if (value.isBlank()) {
                        blanks.add(row.substring(0, PRIMARY_KEY_LENGTH) + "." + span.name());
                    }
                }
            }

            assertThat(blanks)
                    .as("all 18 named fields are populated on all 50 rows, so a blank-field case can "
                            + "only be built inline")
                    .isEmpty();
        }

        @Test
        @DisplayName("Deserialising a row yields exactly the 18 referable names, FILLER excluded")
        void deserialisingARowYieldsTheEighteenReferableNames() {
            Map<String, String> images =
                    asciiCodec.deserialise(CustomerRecord.LAYOUT, fixtureRow(1).getBytes(ASCII));

            assertThat(images)
                    .as("FILLER is not a referable COBOL name, so it is not a field the differ compares")
                    .hasSize(REFERABLE_FIELD_COUNT)
                    .doesNotContainKey("FILLER");
            assertThat(images.get("CUST-ID")).isEqualTo(ROW_1_CUST_ID_IMAGE);
            assertThat(images.get("CUST-SSN")).isEqualTo(ROW_1_SSN_IMAGE);
            assertThat(images.get("CUST-DOB-YYYY-MM-DD")).isEqualTo(ROW_1_DOB);
            assertThat(images.get("CUST-ADDR-ZIP")).isEqualTo(padded(ROW_1_ZIP, 10));
            assertThat(images.get("CUST-FICO-CREDIT-SCORE")).isEqualTo("274");
        }

        @Test
        @DisplayName("The fixture is pure ASCII, so US-ASCII is both correct and safe for it")
        void theFixtureIsPureAscii() {
            for (String row : fixtureRows()) {
                for (int index = 0; index < row.length(); index++) {
                    char character = row.charAt(index);
                    assertThat((int) character)
                            .as("character %d of row %s", index, row.substring(0, PRIMARY_KEY_LENGTH))
                            .isBetween(0x20, 0x7E);
                }
            }
        }
    }

    @Nested
    @DisplayName("The 500-character group image - the DISPLAY CUSTOMER-RECORD surface")
    class GroupImage {
        @Test
        @DisplayName("The group image is exactly the encoded bytes, read under the same code page")
        void theGroupImageIsTheEncodedBytes() {
            CustomerRecord record = CustomerRecord.decode(fixtureRow(1), ASCII);

            assertThat(record.recordImage(ASCII))
                    .hasSize(DECLARED_RECORD_LENGTH)
                    .isEqualTo(new String(record.encode(ASCII), ASCII))
                    .isEqualTo(fixtureRow(1));
        }

        @Test
        @DisplayName("The group image spans CUST-ID through the last FILLER byte")
        void theGroupImageSpansTheWholeRecord() {
            String image = CustomerRecord.decode(fixtureRow(1), ASCII).recordImage(ASCII);

            assertThat(image.substring(0, 9)).isEqualTo(ROW_1_CUST_ID_IMAGE);
            assertThat(image.substring(329, 332)).isEqualTo("274");
            assertThat(image.substring(FILLER_OFFSET)).isEqualTo(spaces(FILLER_LENGTH));
        }

        @Test
        @DisplayName("Two successive DISPLAYs of one record produce identical text")
        void twoSuccessiveDisplaysAreIdentical() {
            CustomerRecord record = CustomerRecord.decode(fixtureRow(1), ASCII);

            assertThat(record.recordImage(ASCII)).isEqualTo(record.recordImage(ASCII));

            record.setCustFirstName("Changed");

            assertThat(record.recordImage(ASCII))
                    .as("after a setter the image must follow the field, never a cached copy")
                    .isEqualTo(new String(record.encode(ASCII), ASCII))
                    .contains(padded("Changed", 25));
        }

        @Test
        @DisplayName("The charset and codec forms of the group image agree, and both refuse null")
        void bothGroupImageFormsAgreeAndRefuseNull() {
            CustomerRecord record = CustomerRecord.decode(fixtureRow(1), ASCII);

            assertThat(record.recordImage(asciiCodec)).isEqualTo(record.recordImage(ASCII));
            assertThatNullPointerException().isThrownBy(() -> record.recordImage((Charset) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> record.recordImage((FixedWidthCodec) null));
        }

        @Test
        @DisplayName("An untouched record's group image is zeros in the numeric spans, spaces elsewhere")
        void anUntouchedRecordsGroupImage() {
            String image = new CustomerRecord().recordImage(ASCII);

            assertThat(image).hasSize(DECLARED_RECORD_LENGTH);
            assertThat(image.substring(0, 9)).isEqualTo("000000000");
            assertThat(image.substring(9, 279)).isEqualTo(spaces(270));
            assertThat(image.substring(279, 288)).isEqualTo("000000000");
            assertThat(image.substring(288, 328)).isEqualTo(spaces(40));
            assertThat(image.substring(328, 329)).isEqualTo(" ");
            assertThat(image.substring(329, 332)).isEqualTo("000");
            assertThat(image.substring(FILLER_OFFSET)).isEqualTo(spaces(FILLER_LENGTH));
        }
    }

    @Nested
    @DisplayName("The code page is always named and always honoured, never a platform default")
    class CodePageIsAlwaysNamed {
        @Test
        @DisplayName("IBM037 is available in this JDK, asserted rather than assumed")
        void ebcdicIsAvailable() {
            assertThat(Charset.isSupported("IBM037")).isTrue();
            assertThat(EBCDIC.name()).isEqualTo("IBM037");
            assertThat(ASCII.name()).isEqualTo("US-ASCII");
        }

        @Test
        @DisplayName("The same record encodes to DIFFERENT bytes under US-ASCII and IBM037")
        void theSameRecordEncodesDifferentlyUnderEachCodePage() {
            CustomerRecord record = row1AsBuilt();

            byte[] ascii = record.encode(ASCII);
            byte[] ebcdic = record.encode(EBCDIC);

            assertThat(ascii).hasSize(DECLARED_RECORD_LENGTH);
            assertThat(ebcdic).hasSize(DECLARED_RECORD_LENGTH);
            assertThat(ebcdic)
                    .as("if these matched, the charset argument would be decoration")
                    .isNotEqualTo(ascii);
            assertThat(ascii[0]).as("ASCII '0' is 0x30").isEqualTo((byte) 0x30);
            assertThat(ebcdic[0]).as("EBCDIC '0' is 0xF0").isEqualTo((byte) 0xF0);
        }

        @Test
        @DisplayName("Each image is internally self-consistent under its own code page")
        void eachImageIsSelfConsistentUnderItsOwnCodePage() {
            CustomerRecord record = row1AsBuilt();

            assertThat(CustomerRecord.decode(record.encode(ASCII), ASCII)).isEqualTo(record);
            assertThat(CustomerRecord.decode(record.encode(EBCDIC), EBCDIC)).isEqualTo(record);
            assertThat(CustomerRecord.decode(record.encode(ebcdicCodec), ebcdicCodec))
                    .isEqualTo(record);
        }

        @Test
        @DisplayName("Reading an EBCDIC image as US-ASCII does not silently yield the right fields")
        void readingAnEbcdicImageAsAsciiDoesNotSucceedSilently() {
            byte[] ebcdic = row1AsBuilt().encode(EBCDIC);

            assertThatExceptionOfType(IllegalStateException.class)
                    .as("a code-page mix-up must fail rather than decode to plausible-looking values")
                    .isThrownBy(() -> CustomerRecord.decode(ebcdic, ASCII))
                    .withMessageContaining("CUST-ID")
                    .withMessageContaining("US-ASCII");
        }

        @Test
        @DisplayName("A null charset or codec is refused at every byte boundary")
        void aNullCharsetIsRefusedAtEveryBoundary() {
            byte[] stored = fixtureRow(1).getBytes(ASCII);
            CustomerRecord record = CustomerRecord.decode(stored, ASCII);

            assertThatNullPointerException()
                    .isThrownBy(() -> CustomerRecord.decode(stored, (Charset) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> CustomerRecord.decode(stored, (FixedWidthCodec) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> CustomerRecord.decode(fixtureRow(1), (Charset) null));
            assertThatNullPointerException().isThrownBy(() -> record.encode((Charset) null));
            assertThatNullPointerException().isThrownBy(() -> record.encode((FixedWidthCodec) null));
        }

        @Test
        @DisplayName("A null source image is refused, naming what was required")
        void aNullSourceIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> CustomerRecord.decode((byte[]) null, ASCII))
                    .withMessageContaining(String.valueOf(DECLARED_RECORD_LENGTH));
            assertThatNullPointerException()
                    .isThrownBy(() -> CustomerRecord.decode((byte[]) null, asciiCodec))
                    .withMessageContaining(String.valueOf(DECLARED_RECORD_LENGTH));
            assertThatNullPointerException()
                    .isThrownBy(() -> CustomerRecord.decode((String) null, ASCII))
                    .withMessageContaining(String.valueOf(DECLARED_RECORD_LENGTH));
        }

        @Test
        @DisplayName("An empty image is refused as a wrong width, not accepted as an empty record")
        void anEmptyImageIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CustomerRecord.decode(new byte[0], ASCII))
                    .withMessageContaining(String.valueOf(DECLARED_RECORD_LENGTH));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CustomerRecord.decode("", ASCII));
        }

        @Test
        @DisplayName("The decoder defensively copies its source, so a later mutation cannot reach in")
        void theDecoderCopiesItsSource() {
            byte[] stored = fixtureRow(1).getBytes(ASCII);
            CustomerRecord record = CustomerRecord.decode(stored, ASCII);

            stored[0] = (byte) '9';

            assertThat(record.custIdImage(ASCII))
                    .as("a record that aliased its caller's array would change under it")
                    .isEqualTo(ROW_1_CUST_ID_IMAGE);
        }

        @Test
        @DisplayName("The encoder returns a fresh array each time, so a caller cannot corrupt another")
        void theEncoderReturnsAFreshArray() {
            CustomerRecord record = row1AsBuilt();

            byte[] first = record.encode(ASCII);
            byte[] second = record.encode(ASCII);
            first[0] = (byte) '9';

            assertThat(second).isNotSameAs(first);
            assertThat(second[0]).isEqualTo((byte) '0');
        }
    }

    @Nested
    @DisplayName("Value semantics - equality over the 18 named fields, FILLER excluded")
    class ValueSemantics {
        @Test
        @DisplayName("Two records decoded from the same row are equal and share a hash code")
        void twoRecordsFromTheSameRowAreEqual() {
            CustomerRecord first = CustomerRecord.decode(fixtureRow(1), ASCII);
            CustomerRecord second = CustomerRecord.decode(fixtureRow(1), ASCII);

            assertThat(first).isEqualTo(second).isNotSameAs(second);
            assertThat(first.hashCode()).isEqualTo(second.hashCode());
        }

        @Test
        @DisplayName("Equality is reflexive and symmetric")
        void equalityIsReflexiveAndSymmetric() {
            CustomerRecord first = CustomerRecord.decode(fixtureRow(1), ASCII);
            CustomerRecord second = CustomerRecord.decode(fixtureRow(1), ASCII);
            CustomerRecord other = CustomerRecord.decode(fixtureRow(2), ASCII);

            assertThat(first.equals(first)).as("reflexive").isTrue();
            assertThat(first.equals(second)).isTrue();
            assertThat(second.equals(first)).as("symmetric").isTrue();
            assertThat(first.equals(other)).isFalse();
            assertThat(other.equals(first)).as("symmetric in the negative too").isFalse();
        }

        @Test
        @DisplayName("A record equals neither null nor an instance of another type")
        void aRecordEqualsNeitherNullNorAnotherType() {
            CustomerRecord record = CustomerRecord.decode(fixtureRow(1), ASCII);

            assertThat(record.equals(null)).as("never equal to null").isFalse();
            assertThat(record.equals("not a CustomerRecord")).as("never equal to a String").isFalse();
            assertThat(record.equals(Integer.valueOf(1))).isFalse();
            assertThat(record).isNotEqualTo(new Object());
        }

        @Test
        @DisplayName("Hash codes are consistent across repeated calls on one instance")
        void hashCodesAreConsistent() {
            CustomerRecord record = CustomerRecord.decode(fixtureRow(1), ASCII);

            int first = record.hashCode();

            assertThat(record.hashCode()).isEqualTo(first);
            assertThat(record.hashCode()).isEqualTo(first);
        }

        @Test
        @DisplayName("Mutating a field changes the hash - which is why an instance is never a map key")
        void mutatingAFieldChangesTheState() {
            CustomerRecord record = CustomerRecord.decode(fixtureRow(1), ASCII);
            int before = record.hashCode();

            record.setCustFirstName("Changed");

            assertThat(record.hashCode()).isNotEqualTo(before);
            assertThat(record).isNotEqualTo(CustomerRecord.decode(fixtureRow(1), ASCII));
        }

        @ParameterizedTest(name = "a different {0} breaks equality")
        @ValueSource(strings = {
            "CUST-ID",
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
            "CUST-DOB-YYYY-MM-DD",
            "CUST-EFT-ACCOUNT-ID",
            "CUST-PRI-CARD-HOLDER-IND",
            "CUST-FICO-CREDIT-SCORE",
        })
        @DisplayName("Every one of the 18 named fields takes part in equality")
        void everyNamedFieldTakesPartInEquality(String field) {
            CustomerRecord unchanged = CustomerRecord.decode(fixtureRow(1), ASCII);
            CustomerRecord changed = CustomerRecord.decode(fixtureRow(1), ASCII);

            changeOneField(changed, field);

            assertThat(changed)
                    .as("%s must be compared, or a change to it would slip past 9300-CHECK-CHANGE-IN-REC",
                            field)
                    .isNotEqualTo(unchanged);
            assertThat(changed.encode(ASCII))
                    .as("and the change must reach the stored bytes")
                    .isNotEqualTo(unchanged.encode(ASCII));
        }

        private void changeOneField(CustomerRecord record, String field) {
            switch (field) {
                case "CUST-ID" -> record.setCustId(ROW_1_CUST_ID + 1);
                case "CUST-FIRST-NAME" -> record.setCustFirstName("Different");
                case "CUST-MIDDLE-NAME" -> record.setCustMiddleName("Different");
                case "CUST-LAST-NAME" -> record.setCustLastName("Different");
                case "CUST-ADDR-LINE-1" -> record.setCustAddrLine1("Different");
                case "CUST-ADDR-LINE-2" -> record.setCustAddrLine2("Different");
                case "CUST-ADDR-LINE-3" -> record.setCustAddrLine3("Different");
                case "CUST-ADDR-STATE-CD" -> record.setCustAddrStateCd("ZZ");
                case "CUST-ADDR-COUNTRY-CD" -> record.setCustAddrCountryCd("CAN");
                case "CUST-ADDR-ZIP" -> record.setCustAddrZip("99999-9999");
                case "CUST-PHONE-NUM-1" -> record.setCustPhoneNum1("(000)000-0000");
                case "CUST-PHONE-NUM-2" -> record.setCustPhoneNum2("(000)000-0000");
                case "CUST-SSN" -> record.setCustSsn(ROW_1_SSN + 1);
                case "CUST-GOVT-ISSUED-ID" -> record.setCustGovtIssuedId("99999999999999999999");
                case "CUST-DOB-YYYY-MM-DD" -> record.setCustDobYyyyMmDd("2000-01-01");
                case "CUST-EFT-ACCOUNT-ID" -> record.setCustEftAccountId("9999999999");
                case "CUST-PRI-CARD-HOLDER-IND" -> record.setCustPriCardHolderInd("N");
                case "CUST-FICO-CREDIT-SCORE" -> record.setCustFicoCreditScore(ROW_1_FICO + 1);
                default -> throw new IllegalArgumentException("Not a named field of CVCUS01Y: " + field);
            }
        }

        @Test
        @DisplayName("Padding is part of equality: a padded value equals its unpadded original")
        void paddingIsPartOfEquality() {
            CustomerRecord padded = new CustomerRecord();
            CustomerRecord unpadded = new CustomerRecord();

            padded.setCustFirstName(padded("Alice", 25));
            unpadded.setCustFirstName("Alice");

            assertThat(padded)
                    .as("both describe the same 25 stored bytes, so they are the same record")
                    .isEqualTo(unpadded);
            assertThat(padded.hashCode()).isEqualTo(unpadded.hashCode());
        }

        @Test
        @DisplayName("Two records equal on all 18 fields are equal whatever their FILLER came from")
        void fillerTakesNoPartInEquality() {
            CustomerRecord fromFixture = CustomerRecord.decode(fixtureRow(1), ASCII);
            CustomerRecord fromValues = row1AsBuilt();

            assertThat(fromValues).isEqualTo(fromFixture);
            assertThat(new String(fromValues.encode(ASCII), ASCII).substring(FILLER_OFFSET))
                    .isEqualTo(new String(fromFixture.encode(ASCII), ASCII).substring(FILLER_OFFSET));
        }

        @Test
        @DisplayName("A default record equals another default record")
        void twoDefaultRecordsAreEqual() {
            assertThat(new CustomerRecord()).isEqualTo(new CustomerRecord());
            assertThat(new CustomerRecord().hashCode()).isEqualTo(new CustomerRecord().hashCode());
            assertThat(new CustomerRecord()).isNotEqualTo(row1AsBuilt());
        }

        @Test
        @DisplayName("The no-argument constructor validates nothing, because it receives nothing")
        void theConstructorValidatesNothing() {
            assertThat(CustomerRecord.class.getDeclaredConstructors()).hasSize(1);
            assertThatCode(CustomerRecord::new).doesNotThrowAnyException();
            assertThat(new CustomerRecord().encode(ASCII)).hasSize(DECLARED_RECORD_LENGTH);
        }

        @Test
        @DisplayName("The diagnostic rendering names all 18 copybook fields")
        void theDiagnosticRenderingNamesEveryField() {
            String rendering = CustomerRecord.decode(fixtureRow(1), ASCII).toString();

            assertThat(rendering).startsWith("CustomerRecord{").endsWith("}");
            for (String name : SPAN_NAMES) {
                if ("FILLER".equals(name)) {
                    continue;
                }
                assertThat(rendering)
                        .as("%s must be named under its copybook spelling", name)
                        .contains(name + "=");
            }
        }

        @Test
        @DisplayName("The rendering withholds the identity data this record is a dossier of")
        void theRenderingWithholdsTheIdentityData() {
            String rendering = CustomerRecord.decode(fixtureRow(1), ASCII).toString();

            assertThat(rendering)
                    .doesNotContain(ROW_1_SSN_IMAGE)
                    .doesNotContain(String.valueOf(ROW_1_SSN))
                    .doesNotContain(ROW_1_GOVT_ISSUED_ID)
                    .doesNotContain(ROW_1_EFT_ACCOUNT_ID)
                    .doesNotContain(ROW_1_FIRST_NAME)
                    .doesNotContain(ROW_1_LAST_NAME)
                    .doesNotContain(ROW_1_ADDR_LINE_1)
                    .doesNotContain(ROW_1_PHONE_1)
                    .doesNotContain(ROW_1_DOB)
                    .doesNotContain(ROW_1_CUST_ID_IMAGE);
            assertThat(rendering)
                    .as("the fields that identify nobody once the key is masked stay legible, because "
                            + "they are what a validation parity failure is read from")
                    .contains("CUST-ADDR-STATE-CD=[" + ROW_1_STATE_CD + "]")
                    .contains("CUST-ADDR-COUNTRY-CD=[" + ROW_1_COUNTRY_CD + "]")
                    .contains("CUST-PRI-CARD-HOLDER-IND=[" + ROW_1_PRI_CARD_HOLDER_IND + "]")
                    .contains("CUST-FICO-CREDIT-SCORE=" + ROW_1_FICO);
        }

        @Test
        @DisplayName("The byte contract lives in the group image, not in toString")
        void theByteContractLivesInTheGroupImage() {
            CustomerRecord record = CustomerRecord.decode(fixtureRow(1), ASCII);

            assertThat(record.toString())
                    .as("toString is a diagnostic; it is deliberately not 500 characters and is not a "
                            + "serialisation surface")
                    .isNotEqualTo(record.recordImage(ASCII));
            assertThat(record.recordImage(ASCII))
                    .as("recordImage is the byte contract")
                    .hasSize(DECLARED_RECORD_LENGTH);
        }

        @Test
        @DisplayName("A default record renders without throwing, blank fields included")
        void aDefaultRecordRendersWithoutThrowing() {
            String rendering = new CustomerRecord().toString();

            assertThat(rendering).startsWith("CustomerRecord{").contains("CUST-FIRST-NAME=");
            assertThat(rendering).doesNotContain("null");
        }

        @Test
        @DisplayName("The rendering stays on one line, so no stored byte can forge a second log entry")
        void theRenderingStaysOnOneLine() {
            CustomerRecord record = new CustomerRecord();
            record.setCustAddrStateCd("A\n");
            record.setCustAddrCountryCd("A\rB");
            record.setCustPriCardHolderInd("\t");
            record.setCustFirstName("A\r\nB");

            String rendering = record.toString();

            assertThat(rendering)
                    .as("a value reaching a log line unescaped can carry CR or LF and forge an entry")
                    .doesNotContain("\n")
                    .doesNotContain("\r")
                    .doesNotContain("\t");
            assertThat(rendering)
                    .as("each control byte is escaped as a COBOL hex literal rather than dropped, so the "
                            + "field is still rendered in full")
                    .contains("CUST-ADDR-STATE-CD=[AX'0A']")
                    .contains("CUST-ADDR-COUNTRY-CD=[AX'0D'B]")
                    .contains("CUST-PRI-CARD-HOLDER-IND=[X'09']");
        }
    }

    @Nested
    @DisplayName("Structural guards - no floating point, no decimal, no persistence mapping")
    class StructuralGuards {
        @Test
        @DisplayName("No declared field is double, float, BigDecimal or BigInteger (G22, G23)")
        void noDeclaredFieldIsAForbiddenNumericType() {
            List<String> offenders = new ArrayList<>();
            for (Field field : CustomerRecord.class.getDeclaredFields()) {
                if (isForbiddenNumericType(field.getType())) {
                    offenders.add(field.getName() + " : " + field.getType().getName());
                }
            }

            assertThat(offenders)
                    .as("CVCUS01Y declares no signed and no V-scaled picture, so no field here is even a "
                            + "candidate for a decimal type - let alone a binary floating-point one, "
                            + "which cannot represent a decimal fraction exactly")
                    .isEmpty();
        }

        @Test
        @DisplayName("No method returns or accepts a floating-point or decimal type (G22, G23)")
        void noMethodTouchesAForbiddenNumericType() {
            List<String> offenders = new ArrayList<>();
            for (Method method : CustomerRecord.class.getDeclaredMethods()) {
                if (isForbiddenNumericType(method.getReturnType())) {
                    offenders.add(method.getName() + " -> " + method.getReturnType().getName());
                }
                for (Class<?> parameter : method.getParameterTypes()) {
                    if (isForbiddenNumericType(parameter)) {
                        offenders.add(method.getName() + "(" + parameter.getName() + ")");
                    }
                }
            }
            for (Constructor<?> constructor : CustomerRecord.class.getDeclaredConstructors()) {
                for (Class<?> parameter : constructor.getParameterTypes()) {
                    if (isForbiddenNumericType(parameter)) {
                        offenders.add("<init>(" + parameter.getName() + ")");
                    }
                }
            }

            assertThat(offenders)
                    .as("no accessor may hand an identifier or a score out as floating point, and a "
                            + "BigDecimal anywhere on this surface would signal that a scale had been "
                            + "invented where the copybook declares none")
                    .isEmpty();
        }

        @Test
        @DisplayName("No rounding mode is reachable, because there is nothing scaled to round (G24)")
        void noRoundingModeIsReachable() {
            List<String> offenders = new ArrayList<>();
            for (Method method : CustomerRecord.class.getDeclaredMethods()) {
                for (Class<?> parameter : method.getParameterTypes()) {
                    if ("java.math.RoundingMode".equals(parameter.getName())) {
                        offenders.add(method.getName());
                    }
                }
            }

            assertThat(offenders).isEmpty();
            assertThat(CustomerRecord.LAYOUT.spans())
                    .as("and the reason: not one span is SIGNED_SCALED")
                    .noneMatch(span -> span.kind() == PictureKind.SIGNED_SCALED);
        }

        @Test
        @DisplayName("The three numeric accessors are exact integral types, and only those three")
        void theNumericSurfaceIsExactAndMinimal() {
            List<String> numericAccessors = new ArrayList<>();
            for (Method method : CustomerRecord.class.getDeclaredMethods()) {
                Class<?> returned = method.getReturnType();
                if (method.getName().startsWith("get") && returned.isPrimitive()
                        && !boolean.class.equals(returned)) {
                    numericAccessors.add(method.getName() + " -> " + returned.getName());
                }
            }

            assertThat(numericAccessors)
                    .as("exactly the three fields CVCUS01Y declares as PIC 9, each as int")
                    .containsExactlyInAnyOrder(
                            "getCustId -> int",
                            "getCustSsn -> int",
                            "getCustFicoCreditScore -> int");
        }

        @Test
        @DisplayName("The type carries no persistence annotation of any kind (G44)")
        void theTypeCarriesNoPersistenceAnnotation() {
            List<String> offenders = new ArrayList<>();
            collectPersistenceAnnotations(CustomerRecord.class.getAnnotations(), "the class", offenders);
            for (Field field : CustomerRecord.class.getDeclaredFields()) {
                collectPersistenceAnnotations(field.getAnnotations(), "field " + field.getName(),
                        offenders);
            }
            for (Method method : CustomerRecord.class.getDeclaredMethods()) {
                collectPersistenceAnnotations(method.getAnnotations(), "method " + method.getName(),
                        offenders);
            }
            for (Constructor<?> constructor : CustomerRecord.class.getDeclaredConstructors()) {
                collectPersistenceAnnotations(constructor.getAnnotations(), "a constructor", offenders);
            }

            assertThat(offenders)
                    .as("the migration reaches the existing datasets over JDBC with no schema change, so "
                            + "there is no entity mapping, no table, no column and no DDL anywhere")
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
            for (Field field : CustomerRecord.class.getDeclaredFields()) {
                String name = field.getName().toLowerCase(Locale.ROOT);
                if (name.contains("version") || name.contains("optlock")
                        || name.contains("rowversion") || name.contains("timestamp")) {
                    offenders.add(field.getName());
                }
            }

            assertThat(offenders)
                    .as("the update programs' 9300-CHECK-CHANGE-IN-REC does optimistic concurrency by "
                            + "re-reading and comparing field by field, which is what equals() serves. A "
                            + "version column would be a schema change, and schema changes are forbidden.")
                    .isEmpty();
        }

        @Test
        @DisplayName("No dataset name is embedded in the type (G46's local half)")
        void noDatasetNameIsEmbedded() {
            List<String> offenders = new ArrayList<>();
            for (Field field : CustomerRecord.class.getDeclaredFields()) {
                if (!String.class.equals(field.getType()) || !Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                field.setAccessible(true);
                try {
                    Object value = field.get(null);
                    if (value instanceof String text && text.contains("AWS.M2.CARDDEMO")) {
                        offenders.add(field.getName());
                    }
                } catch (IllegalAccessException unreachable) {
                    throw new AssertionError("A static field of the class under test must be readable "
                            + "after setAccessible: " + field.getName(), unreachable);
                }
            }

            assertThat(offenders).isEmpty();
        }

        @Test
        @DisplayName("Every static member is final, so there is no mutable static state (G53)")
        void everyStaticMemberIsFinal() {
            List<String> offenders = new ArrayList<>();
            for (Field field : CustomerRecord.class.getDeclaredFields()) {
                boolean isStatic = Modifier.isStatic(field.getModifiers());
                boolean isFinal = Modifier.isFinal(field.getModifiers());
                if (isStatic && !isFinal) {
                    offenders.add(field.getName());
                }
            }

            assertThat(offenders)
                    .as("COBOL WORKING-STORAGE must never become static Java state: it would break row "
                            + "isolation and make tests order-dependent")
                    .isEmpty();
        }

        @Test
        @DisplayName("Every instance field is private and non-static, so state is strictly per record")
        void everyInstanceFieldIsPrivateAndPerInstance() {
            List<String> instanceFields = new ArrayList<>();
            for (Field field : CustomerRecord.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                instanceFields.add(field.getName());
                assertThat(Modifier.isPrivate(field.getModifiers()))
                        .as("instance field %s must be private", field.getName())
                        .isTrue();
            }

            assertThat(instanceFields)
                    .as("one field per named copybook item; FILLER carries no value and so has none")
                    .hasSize(REFERABLE_FIELD_COUNT);
        }

        @Test
        @DisplayName("Two records never share state, however they were built")
        void twoRecordsNeverShareState() {
            CustomerRecord first = CustomerRecord.decode(fixtureRow(1), ASCII);
            CustomerRecord second = CustomerRecord.decode(fixtureRow(1), ASCII);

            first.setCustFirstName("Changed");

            assertThat(second.getCustFirstName())
                    .as("a shared or static field would let one row's change reach another's")
                    .isEqualTo(padded(ROW_1_FIRST_NAME, 25));
        }

        @Test
        @DisplayName("The type depends on common only - no cycle back through another domain package")
        void theTypeDependsOnCommonOnly() {
            List<String> offenders = new ArrayList<>();
            List<Class<?>> referenced = new ArrayList<>();
            for (Field field : CustomerRecord.class.getDeclaredFields()) {
                referenced.add(field.getType());
            }
            for (Method method : CustomerRecord.class.getDeclaredMethods()) {
                referenced.add(method.getReturnType());
                referenced.addAll(List.of(method.getParameterTypes()));
            }
            for (Class<?> type : referenced) {
                String name = type.getName();
                if (!name.startsWith("com.vsergeychik.carddemo.")) {
                    continue;
                }
                boolean allowed = name.startsWith("com.vsergeychik.carddemo.common.")
                        || name.startsWith("com.vsergeychik.carddemo.customer.model.");
                if (!allowed) {
                    offenders.add(name);
                }
            }

            assertThat(offenders)
                    .as("customer.model depends on common, and on nothing else in this repository")
                    .isEmpty();
        }

        @Test
        @DisplayName("The declared record length is the single source of truth for the width")
        void theDeclaredRecordLengthIsTheSingleSourceOfTruth() {
            CustomerRecord record = row1AsBuilt();

            assertThat(CustomerRecord.RECORD_LENGTH)
                    .isEqualTo(CustomerRecord.LAYOUT.recordLength())
                    .isEqualTo(record.encode(ASCII).length)
                    .isEqualTo(record.encode(EBCDIC).length)
                    .isEqualTo(record.encode(asciiCodec).length)
                    .isEqualTo(record.recordImage(ASCII).length())
                    .isEqualTo(record.recordImage(asciiCodec).length())
                    .isEqualTo(CustomerRecord.FILLER.endOffsetExclusive())
                    .isEqualTo(DECLARED_RECORD_LENGTH);
        }

        @Test
        @DisplayName("The type is final, so no subclass can redefine the layout it publishes")
        void theTypeIsFinal() {
            assertThat(Modifier.isFinal(CustomerRecord.class.getModifiers()))
                    .as("a subclass could not change RECORD_LENGTH but could override an accessor, which "
                            + "would let two different records claim the same copybook")
                    .isTrue();
            assertThat(CustomerRecord.class.getSuperclass()).isEqualTo(Object.class);
            assertThat(CustomerRecord.class.getInterfaces())
                    .as("no marker interface, no serialisation contract, no ORM callback hook")
                    .isEmpty();
        }
    }
}
