package com.vsergeychik.carddemo.account.model;

import com.vsergeychik.carddemo.common.DiagnosticText;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link AccountRecord}, the single Java type for copybook {@code app/cpy/CVACT01Y.cpy} -
 * {@code 01 ACCOUNT-RECORD}, exactly 300 bytes, the most widely consumed record layout in this migration
 * with eleven COBOL consumers.
 */
@DisplayName("AccountRecord - CVACT01Y account master record, 300 bytes")
class AccountRecordTest {
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    private static final Charset EBCDIC = Charset.forName("IBM037");

    private static final String FIXTURE_RESOURCE = "fixtures/acctdata.txt";

    private static final int EXPECTED_FIXTURE_ROWS = 50;

    private static final String ROW_1_ACCT_ID_IMAGE = "00000000001";

    private static final String ROW_1_ACTIVE_STATUS = "Y";

    private static final String ROW_1_CURR_BAL_RAW = "00000001940{";

    private static final String ROW_1_CREDIT_LIMIT_RAW = "00000020200{";

    private static final String ROW_1_CASH_CREDIT_LIMIT_RAW = "00000010200{";

    private static final String ROW_1_OPEN_DATE = "2014-11-20";

    private static final String ROW_1_EXPIRAION_DATE = "2025-05-20";

    private static final String ROW_1_REISSUE_DATE = "2025-05-20";

    private static final String ROW_1_CURR_CYC_CREDIT_RAW = "00000000000{";

    private static final String ROW_1_CURR_CYC_DEBIT_RAW = "00000000000{";

    private static final String FIXTURE_ADDR_ZIP = "A000000000";

    private static final String FIXTURE_GROUP_ID = "          ";

    private static final String FILLER_SPACES = " ".repeat(AccountRecord.FILLER_LENGTH);

    private static final BigDecimal ROW_1_CURR_BAL = new BigDecimal("194.00");

    private static final BigDecimal ROW_1_CREDIT_LIMIT = new BigDecimal("2020.00");

    private static final BigDecimal ROW_1_CASH_CREDIT_LIMIT = new BigDecimal("1020.00");

    private static final BigDecimal SCALE_2_ZERO = new BigDecimal("0.00");

    private static final List<String> MONETARY_FIELD_NAMES = List.of(
            AccountRecord.ACCT_CURR_BAL_NAME,
            AccountRecord.ACCT_CREDIT_LIMIT_NAME,
            AccountRecord.ACCT_CASH_CREDIT_LIMIT_NAME,
            AccountRecord.ACCT_CURR_CYC_CREDIT_NAME,
            AccountRecord.ACCT_CURR_CYC_DEBIT_NAME);

    private final FixedWidthCodec asciiCodec = new FixedWidthCodec(ASCII);

    private static List<String> fixtureRows() {
        List<String> rows = new ArrayList<>();
        try (InputStream stream =
                     AccountRecordTest.class.getClassLoader().getResourceAsStream(FIXTURE_RESOURCE)) {
            assertThat(stream).as("%s must be on the test classpath", FIXTURE_RESOURCE).isNotNull();
            for (String line : new String(stream.readAllBytes(), ASCII).split("\n")) {
                String row = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
                if (!row.isEmpty()) {
                    rows.add(row);
                }
            }
        } catch (IOException problem) {
            throw new UncheckedIOException("Could not read " + FIXTURE_RESOURCE, problem);
        }
        return rows;
    }

    private static String fixtureRow1() {
        return fixtureRows().get(0);
    }

    private static AccountRecord decodedRow1() {
        return AccountRecord.decode(fixtureRow1(), ASCII);
    }

    private static String image(String acctId,
                                String activeStatus,
                                String currBal,
                                String creditLimit,
                                String cashCreditLimit,
                                String openDate,
                                String expiraionDate,
                                String reissueDate,
                                String currCycCredit,
                                String currCycDebit,
                                String addrZip,
                                String groupId) {
        String assembled = acctId + activeStatus + currBal + creditLimit + cashCreditLimit
                + openDate + expiraionDate + reissueDate + currCycCredit + currCycDebit
                + addrZip + groupId + FILLER_SPACES;
        assertThat(assembled)
                .as("a synthesised image must be exactly the declared record width")
                .hasSize(AccountRecord.RECORD_LENGTH);
        return assembled;
    }

    private static String row1WithBalance(String currBalRaw) {
        return image(ROW_1_ACCT_ID_IMAGE, ROW_1_ACTIVE_STATUS, currBalRaw, ROW_1_CREDIT_LIMIT_RAW,
                ROW_1_CASH_CREDIT_LIMIT_RAW, ROW_1_OPEN_DATE, ROW_1_EXPIRAION_DATE,
                ROW_1_REISSUE_DATE, ROW_1_CURR_CYC_CREDIT_RAW, ROW_1_CURR_CYC_DEBIT_RAW,
                FIXTURE_ADDR_ZIP, FIXTURE_GROUP_ID);
    }

    private static Method[] declaredMethods() {
        return AccountRecord.class.getDeclaredMethods();
    }

    private static List<Field> authoredFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            if (!field.isSynthetic() && !field.getName().startsWith("$")) {
                fields.add(field);
            }
        }
        return fields;
    }

    @Nested
    @DisplayName("Declared geometry - every span at its copybook offset, totalling 300 (G19)")
    class DeclaredGeometry {
        @ParameterizedTest(name = "{0} occupies [{1}, {1}+{2}) as {3}")
        @CsvSource({
            "ACCT-ID,                  0,  11, UNSIGNED_NUMERIC",
            "ACCT-ACTIVE-STATUS,      11,   1, ALPHANUMERIC",
            "ACCT-CURR-BAL,           12,  12, SIGNED_SCALED",
            "ACCT-CREDIT-LIMIT,       24,  12, SIGNED_SCALED",
            "ACCT-CASH-CREDIT-LIMIT,  36,  12, SIGNED_SCALED",
            "ACCT-OPEN-DATE,          48,  10, ALPHANUMERIC",
            "ACCT-EXPIRAION-DATE,     58,  10, ALPHANUMERIC",
            "ACCT-REISSUE-DATE,       68,  10, ALPHANUMERIC",
            "ACCT-CURR-CYC-CREDIT,    78,  12, SIGNED_SCALED",
            "ACCT-CURR-CYC-DEBIT,     90,  12, SIGNED_SCALED",
            "ACCT-ADDR-ZIP,          102,  10, ALPHANUMERIC",
            "ACCT-GROUP-ID,          112,  10, ALPHANUMERIC",
        })
        @DisplayName("Each of the twelve named spans sits where the copybook puts it")
        void namedSpanMatchesItsCopybookDeclaration(String copybookName,
                                                    int expectedOffset,
                                                    int expectedLength,
                                                    String expectedKind) {
            FieldSpan span = AccountRecord.LAYOUT.span(copybookName);

            assertThat(span.name())
                    .as("the copybook item name is carried verbatim")
                    .isEqualTo(copybookName);
            assertThat(span.offset())
                    .as("%s starts at absolute byte %d", copybookName, expectedOffset)
                    .isEqualTo(expectedOffset);
            assertThat(span.length())
                    .as("%s is %d byte(s) wide", copybookName, expectedLength)
                    .isEqualTo(expectedLength);
            assertThat(span.kind().name())
                    .as("%s is declared as %s", copybookName, expectedKind)
                    .isEqualTo(expectedKind);
            assertThat(span.endOffsetExclusive())
                    .as("offset plus length is arithmetic, not a stored value")
                    .isEqualTo(expectedOffset + expectedLength);
            assertThat(span.redefinition())
                    .as("CVACT01Y declares no REDEFINES, so no span is an overlay")
                    .isFalse();
            assertThat(span.hasInitialValue())
                    .as("CVACT01Y declares no VALUE clause on any item")
                    .isFalse();
        }

        @ParameterizedTest(name = "{0} is 12 bytes, not 13")
        @ValueSource(strings = {
            "ACCT-CURR-BAL",
            "ACCT-CREDIT-LIMIT",
            "ACCT-CASH-CREDIT-LIMIT",
            "ACCT-CURR-CYC-CREDIT",
            "ACCT-CURR-CYC-DEBIT",
        })
        @DisplayName("A PIC S9(10)V99 field occupies p+s=12 bytes: the sign is overpunched, not stored")
        void signedMonetaryFieldOccupiesTwelveBytesNotThirteen(String monetaryField) {
            FieldSpan span = AccountRecord.LAYOUT.span(monetaryField);

            assertThat(span.length())
                    .as("%s is p + s = %d + %d bytes with the sign overpunched into the last one",
                            monetaryField,
                            AccountRecord.MONETARY_INTEGER_DIGITS,
                            AccountRecord.MONETARY_SCALE)
                    .isEqualTo(12)
                    .isEqualTo(AccountRecord.MONETARY_INTEGER_DIGITS + AccountRecord.MONETARY_SCALE)
                    .isNotEqualTo(13);
        }

        @Test
        @DisplayName("The five monetary length constants agree with the five declared spans")
        void monetaryLengthConstantsAgreeWithTheSpans() {
            assertThat(MONETARY_FIELD_NAMES)
                    .as("CVACT01Y.cpy:L7-L9 and L13-L14 declare exactly five signed fields")
                    .hasSize(5);

            assertThat(AccountRecord.ACCT_CURR_BAL_LENGTH).isEqualTo(12);
            assertThat(AccountRecord.ACCT_CREDIT_LIMIT_LENGTH).isEqualTo(12);
            assertThat(AccountRecord.ACCT_CASH_CREDIT_LIMIT_LENGTH).isEqualTo(12);
            assertThat(AccountRecord.ACCT_CURR_CYC_CREDIT_LENGTH).isEqualTo(12);
            assertThat(AccountRecord.ACCT_CURR_CYC_DEBIT_LENGTH).isEqualTo(12);

            for (String monetaryField : MONETARY_FIELD_NAMES) {
                assertThat(AccountRecord.LAYOUT.span(monetaryField).kind())
                        .as("%s is signed zoned DISPLAY", monetaryField)
                        .isEqualTo(PictureKind.SIGNED_SCALED);
            }
        }

        @Test
        @DisplayName("ACCT-ADDR-ZIP at 102 precedes ACCT-GROUP-ID at 112 - the order is easy to invert")
        void addrZipPrecedesGroupId() {
            assertThat(AccountRecord.ACCT_ADDR_ZIP_OFFSET).isEqualTo(102);
            assertThat(AccountRecord.ACCT_GROUP_ID_OFFSET).isEqualTo(112);
            assertThat(AccountRecord.ACCT_ADDR_ZIP_OFFSET)
                    .as("L15 comes before L16, so the zip's offset is the lower of the two")
                    .isLessThan(AccountRecord.ACCT_GROUP_ID_OFFSET);
            assertThat(AccountRecord.SPAN_ACCT_ADDR_ZIP.endOffsetExclusive())
                    .as("the two X(10) spans are adjacent with no gap between them")
                    .isEqualTo(AccountRecord.ACCT_GROUP_ID_OFFSET);
        }

        @Test
        @DisplayName("The thirteen declared widths sum to exactly 300 (G19)")
        void declaredWidthsSumToThreeHundred() {
            int total = 0;
            for (FieldSpan span : AccountRecord.LAYOUT.spans()) {
                total += span.length();
            }

            assertThat(AccountRecord.LAYOUT.spans())
                    .as("CVACT01Y.cpy:L5-L17 declares twelve named items plus one FILLER")
                    .hasSize(13);
            assertThat(total)
                    .as("the copybook header at CVACT01Y.cpy:L2 states RECLN 300")
                    .isEqualTo(300)
                    .isEqualTo(AccountRecord.RECORD_LENGTH);
            assertThat(AccountRecord.LAYOUT.recordLength()).isEqualTo(300);
            assertThat(new AccountRecord(ASCII).recordLength()).isEqualTo(300);
        }

        @Test
        @DisplayName("The spans are contiguous from byte 0 with no gap and no overlap")
        void spansAreContiguousFromZero() {
            int cursor = 0;
            for (FieldSpan span : AccountRecord.LAYOUT.spans()) {
                assertThat(span.offset())
                        .as("%s must start exactly where the previous span ended", span.name())
                        .isEqualTo(cursor);
                cursor = span.endOffsetExclusive();
            }
            assertThat(cursor)
                    .as("the last span must end on the record boundary")
                    .isEqualTo(AccountRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("Every span is storage; CVACT01Y declares no REDEFINES overlay")
        void everySpanIsStorage() {
            assertThat(AccountRecord.LAYOUT.storageSpans()).hasSize(13);
            assertThat(AccountRecord.LAYOUT.redefinitions()).isEmpty();
            assertThat(AccountRecord.LAYOUT.hasSpan(AccountRecord.ACCT_ID_NAME)).isTrue();
            assertThat(AccountRecord.LAYOUT.hasSpan("ACCT-NOT-A-FIELD")).isFalse();
        }

        @Test
        @DisplayName("Naming a field the copybook does not declare is rejected, not silently ignored")
        void unknownSpanNameIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> AccountRecord.LAYOUT.span("ACCT-NOT-A-FIELD"))
                    .withMessageContaining("ACCT-NOT-A-FIELD");
        }

        @Test
        @DisplayName("Self-check FAILURE: dropping the trailing FILLER leaves the record 178 short")
        void droppingFillerTripsTheSelfCheck() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(AccountRecord.RECORD_LENGTH,
                            AccountRecord.SPAN_ACCT_ID,
                            AccountRecord.SPAN_ACCT_ACTIVE_STATUS,
                            AccountRecord.SPAN_ACCT_CURR_BAL,
                            AccountRecord.SPAN_ACCT_CREDIT_LIMIT,
                            AccountRecord.SPAN_ACCT_CASH_CREDIT_LIMIT,
                            AccountRecord.SPAN_ACCT_OPEN_DATE,
                            AccountRecord.SPAN_ACCT_EXPIRAION_DATE,
                            AccountRecord.SPAN_ACCT_REISSUE_DATE,
                            AccountRecord.SPAN_ACCT_CURR_CYC_CREDIT,
                            AccountRecord.SPAN_ACCT_CURR_CYC_DEBIT,
                            AccountRecord.SPAN_ACCT_ADDR_ZIP,
                            AccountRecord.SPAN_ACCT_GROUP_ID))
                    .withMessageContaining("122")
                    .withMessageContaining("178 byte(s) short")
                    .withMessageContaining("dropped trailing FILLER");
        }

        @Test
        @DisplayName("Self-check FAILURE: a sign byte per monetary field totals 305 and is rejected")
        void reservingASignByteTripsTheSelfCheck() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(AccountRecord.RECORD_LENGTH,
                            FieldSpan.unsignedNumeric("ACCT-ID", 0, 11),
                            FieldSpan.alphanumeric("ACCT-ACTIVE-STATUS", 11, 1),
                            FieldSpan.signedScaled("ACCT-CURR-BAL", 12, 11, 2),
                            FieldSpan.signedScaled("ACCT-CREDIT-LIMIT", 25, 11, 2),
                            FieldSpan.signedScaled("ACCT-CASH-CREDIT-LIMIT", 38, 11, 2),
                            FieldSpan.alphanumeric("ACCT-OPEN-DATE", 51, 10),
                            FieldSpan.alphanumeric("ACCT-EXPIRAION-DATE", 61, 10),
                            FieldSpan.alphanumeric("ACCT-REISSUE-DATE", 71, 10),
                            FieldSpan.signedScaled("ACCT-CURR-CYC-CREDIT", 81, 11, 2),
                            FieldSpan.signedScaled("ACCT-CURR-CYC-DEBIT", 94, 11, 2),
                            FieldSpan.alphanumeric("ACCT-ADDR-ZIP", 107, 10),
                            FieldSpan.alphanumeric("ACCT-GROUP-ID", 117, 10),
                            FieldSpan.filler(127, AccountRecord.FILLER_LENGTH)))
                    .withMessageContaining("305")
                    .withMessageContaining("5 byte(s) too long")
                    .withMessageContaining("sign byte reserved for a PIC S9 field");
        }

        @Test
        @DisplayName("Self-check FAILURE: an undeclared gap between two spans is rejected")
        void anUndeclaredGapTripsTheSelfCheck() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(AccountRecord.RECORD_LENGTH,
                            AccountRecord.SPAN_ACCT_ID,
                            FieldSpan.filler(12, 288)))
                    .withMessageContaining("gap of 1 byte(s)");
        }

        @Test
        @DisplayName("Self-check FAILURE: two spans claiming the same byte are rejected")
        void anOverlapTripsTheSelfCheck() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(AccountRecord.RECORD_LENGTH,
                            AccountRecord.SPAN_ACCT_ID,
                            FieldSpan.alphanumeric("ACCT-ACTIVE-STATUS", 10, 1),
                            FieldSpan.filler(11, 289)))
                    .withMessageContaining("overlap");
        }

        @Test
        @DisplayName("Self-check FAILURE: declaring one copybook name twice is rejected")
        void aDuplicateNameTripsTheSelfCheck() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(22,
                            FieldSpan.unsignedNumeric("ACCT-ID", 0, 11),
                            FieldSpan.unsignedNumeric("ACCT-ID", 11, 11)))
                    .withMessageContaining("more than once");
        }

        @Test
        @DisplayName("A descriptor cannot be declared at a negative offset or with a zero width")
        void descriptorArithmeticIsGuarded() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FieldSpan.alphanumeric("ACCT-GROUP-ID", -1, 10))
                    .withMessageContaining("negative");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FieldSpan.alphanumeric("ACCT-GROUP-ID", 112, 0))
                    .withMessageContaining("at least 1 byte");
        }
    }

    @Nested
    @DisplayName("The ACCT-EXPIRAION-DATE misspelling is preserved deliberately (AAP I1)")
    class Misspelling {
        private static final String COPYBOOK_SPELLING = "ACCT-EXPIRAION-DATE";

        private static final String CORRECTED_SPELLING = "ACCT-EXPIRATION-DATE";

        @Test
        @DisplayName("The copybook item name is carried verbatim, misspelling included")
        void copybookNameIsVerbatim() {
            assertThat(AccountRecord.ACCT_EXPIRAION_DATE_NAME).isEqualTo(COPYBOOK_SPELLING);
            assertThat(AccountRecord.SPAN_ACCT_EXPIRAION_DATE.name()).isEqualTo(COPYBOOK_SPELLING);
            assertThat(AccountRecord.LAYOUT.hasSpan(COPYBOOK_SPELLING)).isTrue();
        }

        @Test
        @DisplayName("The corrected spelling is absent from the layout")
        void correctedSpellingIsAbsentFromTheLayout() {
            assertThat(AccountRecord.LAYOUT.hasSpan(CORRECTED_SPELLING))
                    .as("no span may carry the corrected spelling %s", CORRECTED_SPELLING)
                    .isFalse();
            assertThat(AccountRecord.LAYOUT.spans())
                    .extracting(FieldSpan::name)
                    .doesNotContain(CORRECTED_SPELLING)
                    .contains(COPYBOOK_SPELLING);
        }

        @ParameterizedTest(name = "{0} exists")
        @ValueSource(strings = {
            "getAcctExpiraionDate",
            "rawAcctExpiraionDate",
            "getAcctExpiraionDateYear",
            "getAcctExpiraionDateMonth",
            "getAcctExpiraionDateDay",
        })
        @DisplayName("Every accessor uses the misspelled stem, checked reflectively so a rename fails")
        void misspelledAccessorsExist(String accessorName) {
            assertThat(declaredMethods())
                    .extracting(Method::getName)
                    .as("%s must exist on AccountRecord with the copybook's spelling", accessorName)
                    .contains(accessorName);
        }

        @Test
        @DisplayName("The misspelled setter exists too, so writes cannot drift to a corrected name")
        void misspelledSetterExists() {
            assertThat(declaredMethods())
                    .extracting(Method::getName)
                    .contains("setAcctExpiraionDate");
        }

        @Test
        @DisplayName("No member anywhere on the type uses the corrected spelling acctExpirationDate")
        void noMemberUsesTheCorrectedSpelling() {
            assertThat(declaredMethods())
                    .extracting(Method::getName)
                    .as("a method named with the corrected spelling would break field-level diffing")
                    .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("expiration"));

            assertThat(authoredFields(AccountRecord.class))
                    .extracting(Field::getName)
                    .as("a constant named with the corrected spelling is equally forbidden")
                    .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("expiration"));
        }

        @Test
        @DisplayName("The misspelled field decodes at bytes [58, 68) from the fixture")
        void misspelledFieldDecodesFromTheFixture() {
            AccountRecord row1 = decodedRow1();

            assertThat(row1.getAcctExpiraionDate()).isEqualTo(ROW_1_EXPIRAION_DATE);
            assertThat(row1.rawAcctExpiraionDate()).isEqualTo(ROW_1_EXPIRAION_DATE);
            assertThat(fixtureRow1().substring(58, 68))
                    .as("sliced straight out of the row with plain index arithmetic (practice B11)")
                    .isEqualTo(ROW_1_EXPIRAION_DATE);
        }
    }

    @Nested
    @DisplayName("The primary key - ACCT-ID at offset 0, eleven bytes wide")
    class PrimaryKey {
        @Test
        @DisplayName("The named key-length constant is 11 and is the width of ACCT-ID")
        void keyLengthConstantIsEleven() {
            assertThat(AccountRecord.KEY_LENGTH)
                    .as("CVACT01Y.cpy:L5 declares ACCT-ID as PIC 9(11)")
                    .isEqualTo(11)
                    .isEqualTo(AccountRecord.ACCT_ID_LENGTH)
                    .isEqualTo(AccountRecord.SPAN_ACCT_ID.length());
            assertThat(AccountRecord.ACCT_ID_OFFSET)
                    .as("the key is the leading field of the record")
                    .isZero();
        }

        @Test
        @DisplayName("The key span of a decoded record is the first eleven bytes of the row")
        void keySpanIsTheFirstElevenBytesOfTheRow() {
            String row = fixtureRow1();
            AccountRecord row1 = AccountRecord.decode(row, ASCII);

            assertThat(row1.keyImage())
                    .as("row 1's key image")
                    .isEqualTo(row.substring(0, AccountRecord.KEY_LENGTH))
                    .isEqualTo(ROW_1_ACCT_ID_IMAGE)
                    .hasSize(AccountRecord.KEY_LENGTH);
            assertThat(row1.rawAcctId())
                    .as("the raw span and the key image are the same eleven bytes")
                    .isEqualTo(row1.keyImage());
            assertThat(row1.getAcctId())
                    .as("decoded as a number, the leading zeros are gone")
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("Every fixture row's key image is the row's own first eleven bytes")
        void everyFixtureRowKeyMatchesItsOwnBytes() {
            List<String> rows = fixtureRows();
            for (int index = 0; index < rows.size(); index++) {
                String row = rows.get(index);
                AccountRecord record = AccountRecord.decode(row, ASCII);
                assertThat(record.keyImage())
                        .as("row %d key image", index + 1)
                        .isEqualTo(row.substring(0, AccountRecord.KEY_LENGTH));
                assertThat(record.getAcctId())
                        .as("row %d account id - the fixture numbers accounts 1 to 50", index + 1)
                        .isEqualTo(index + 1L);
            }
        }

        @Test
        @DisplayName("A key image can be built from an identifier alone, zero-filled to eleven digits")
        void keyImageCanBeBuiltWithoutARecord() {
            assertThat(AccountRecord.keyImage(1L, ASCII)).isEqualTo(ROW_1_ACCT_ID_IMAGE);
            assertThat(AccountRecord.keyImage(50L, ASCII)).isEqualTo("00000000050");
            assertThat(AccountRecord.keyImage(0L, ASCII)).isEqualTo("00000000000");
            assertThat(AccountRecord.keyImage(99999999999L, ASCII)).isEqualTo("99999999999");
        }

        @Test
        @DisplayName("Building a key image without naming a charset is refused, never defaulted")
        void keyImageRequiresAnExplicitCharset() {
            assertThatNullPointerException()
                    .as("the digits become bytes in a specific code page (practice B8)")
                    .isThrownBy(() -> AccountRecord.keyImage(1L, null))
                    .withMessageContaining("charset");
        }

        @Test
        @DisplayName("PIC 9 has no sign position, so a negative identifier is refused")
        void negativeIdentifierIsRefused() {
            AccountRecord record = new AccountRecord(ASCII);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> record.setAcctId(-1L))
                    .withMessageContaining("PIC 9");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> AccountRecord.keyImage(-1L, ASCII))
                    .withMessageContaining("PIC 9");
        }

        @Test
        @DisplayName("A PIC 9 receiver truncates on the LEFT, keeping the low-order digits")
        void picNineTruncatesOnTheLeft() {
            AccountRecord record = new AccountRecord(ASCII);
            record.setAcctId(123456789012L);

            assertThat(record.rawAcctId()).isEqualTo("23456789012");
            assertThat(record.getAcctId()).isEqualTo(23456789012L);
        }
    }

    @Nested
    @DisplayName("Date slices - COBOL reference modification is ONE-based, hyphens excluded")
    class DateSlices {
        @Test
        @DisplayName("The slice constants reproduce (1:4), (6:2) and (9:2) exactly")
        void sliceConstantsMatchTheCobolReferenceModification() {
            assertThat(AccountRecord.YEAR_START).as("the 1 in (1:4)").isEqualTo(1);
            assertThat(AccountRecord.YEAR_LENGTH).as("the 4 in (1:4)").isEqualTo(4);
            assertThat(AccountRecord.MONTH_START).as("the 6 in (6:2), skipping the hyphen").isEqualTo(6);
            assertThat(AccountRecord.MONTH_LENGTH).as("the 2 in (6:2)").isEqualTo(2);
            assertThat(AccountRecord.DAY_START).as("the 9 in (9:2)").isEqualTo(9);
            assertThat(AccountRecord.DAY_LENGTH).as("the 2 in (9:2)").isEqualTo(2);
        }

        @Test
        @DisplayName("ACCT-OPEN-DATE 2014-11-20 slices to 2014, 11 and 20 (fixture row 1)")
        void openDateSlices() {
            AccountRecord row1 = decodedRow1();

            assertThat(row1.getAcctOpenDate()).isEqualTo(ROW_1_OPEN_DATE);
            assertThat(row1.getAcctOpenDateYear()).isEqualTo("2014");
            assertThat(row1.getAcctOpenDateMonth()).isEqualTo("11");
            assertThat(row1.getAcctOpenDateDay()).isEqualTo("20");
        }

        @Test
        @DisplayName("ACCT-EXPIRAION-DATE 2025-05-20 slices to 2025, 05 and 20 (fixture row 1)")
        void expiraionDateSlices() {
            AccountRecord row1 = decodedRow1();

            assertThat(row1.getAcctExpiraionDate()).isEqualTo(ROW_1_EXPIRAION_DATE);
            assertThat(row1.getAcctExpiraionDateYear()).isEqualTo("2025");
            assertThat(row1.getAcctExpiraionDateMonth())
                    .as("a leading zero is a character position, not a number to normalise")
                    .isEqualTo("05");
            assertThat(row1.getAcctExpiraionDateDay()).isEqualTo("20");
        }

        @Test
        @DisplayName("ACCT-REISSUE-DATE 2025-05-20 slices to 2025, 05 and 20 (fixture row 1)")
        void reissueDateSlices() {
            AccountRecord row1 = decodedRow1();

            assertThat(row1.getAcctReissueDate()).isEqualTo(ROW_1_REISSUE_DATE);
            assertThat(row1.getAcctReissueDateYear()).isEqualTo("2025");
            assertThat(row1.getAcctReissueDateMonth()).isEqualTo("05");
            assertThat(row1.getAcctReissueDateDay()).isEqualTo("20");
        }

        @Test
        @DisplayName("No slice of any date ever returns a hyphen - the off-by-one guard")
        void noSliceEverReturnsAHyphen() {
            for (String row : fixtureRows()) {
                AccountRecord record = AccountRecord.decode(row, ASCII);
                List<String> slices = List.of(
                        record.getAcctOpenDateYear(),
                        record.getAcctOpenDateMonth(),
                        record.getAcctOpenDateDay(),
                        record.getAcctExpiraionDateYear(),
                        record.getAcctExpiraionDateMonth(),
                        record.getAcctExpiraionDateDay(),
                        record.getAcctReissueDateYear(),
                        record.getAcctReissueDateMonth(),
                        record.getAcctReissueDateDay());

                assertThat(slices)
                        .as("account %d date slices", record.getAcctId())
                        .allSatisfy(slice -> assertThat(slice).doesNotContain("-"))
                        .allSatisfy(slice -> assertThat(slice).containsOnlyDigits());
            }
        }

        @Test
        @DisplayName("Positions 5 and 8 of every stored date are the hyphens the slices skip")
        void positionsFiveAndEightAreTheHyphens() {
            for (String row : fixtureRows()) {
                AccountRecord record = AccountRecord.decode(row, ASCII);
                for (String date : List.of(record.getAcctOpenDate(),
                        record.getAcctExpiraionDate(),
                        record.getAcctReissueDate())) {
                    assertThat(date).hasSize(10);
                    assertThat(date.charAt(4)).as("position 5 of %s", date).isEqualTo('-');
                    assertThat(date.charAt(7)).as("position 8 of %s", date).isEqualTo('-');
                }
            }
        }

        @ParameterizedTest(name = "({1}:{2}) of \"{0}\" is \"{3}\"")
        @CsvSource({
            "2014-11-20, 1, 4, 2014",
            "2014-11-20, 6, 2, 11",
            "2014-11-20, 9, 2, 20",
            "2025-05-20, 1, 4, 2025",
            "2025-05-20, 6, 2, 05",
            "2025-05-20, 9, 2, 20",
        })
        @DisplayName("referenceModify converts one-based COBOL positions to Java substring bounds")
        void referenceModifyConvertsOneBasedPositions(String value,
                                                      int oneBasedStart,
                                                      int length,
                                                      String expected) {
            assertThat(AccountRecord.referenceModify(value, oneBasedStart, length))
                    .isEqualTo(expected)
                    .hasSize(length);
        }

        @Test
        @DisplayName("A blank or short date yields spaces rather than an index-out-of-bounds failure")
        void referenceModifyPadsRatherThanThrows() {
            assertThat(AccountRecord.referenceModify(null, 1, 4)).isEqualTo("    ");
            assertThat(AccountRecord.referenceModify("", 1, 4)).isEqualTo("    ");
            assertThat(AccountRecord.referenceModify("ab", 1, 4)).isEqualTo("ab  ");
            assertThat(AccountRecord.referenceModify("abcd", 9, 2)).isEqualTo("  ");
            assertThat(AccountRecord.referenceModify("2014-1", 6, 2)).isEqualTo("1 ");

            AccountRecord fresh = new AccountRecord(ASCII);
            assertThat(fresh.getAcctOpenDateYear()).isEqualTo("    ");
            assertThat(fresh.getAcctOpenDateMonth()).isEqualTo("  ");
            assertThat(fresh.getAcctOpenDateDay()).isEqualTo("  ");
            assertThat(fresh.getAcctExpiraionDateYear()).isEqualTo("    ");
            assertThat(fresh.getAcctReissueDateDay()).isEqualTo("  ");
        }

        @Test
        @DisplayName("A zero or negative one-based start is refused: COBOL positions begin at 1")
        void referenceModifyRejectsAZeroBasedStart() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> AccountRecord.referenceModify(ROW_1_OPEN_DATE, 0, 4))
                    .withMessageContaining("one-based");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> AccountRecord.referenceModify(ROW_1_OPEN_DATE, -1, 4))
                    .withMessageContaining("one-based");
        }

        @Test
        @DisplayName("A zero-width slice is refused: a reference-modified span covers at least one byte")
        void referenceModifyRejectsAZeroLength() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> AccountRecord.referenceModify(ROW_1_OPEN_DATE, 1, 0))
                    .withMessageContaining("at least 1");
        }

        @Test
        @DisplayName("Slices follow the stored value, so a date written through the setter re-slices")
        void slicesFollowTheStoredValue() {
            AccountRecord record = new AccountRecord(ASCII);
            record.setAcctOpenDate("1999-12-31");

            assertThat(record.getAcctOpenDateYear()).isEqualTo("1999");
            assertThat(record.getAcctOpenDateMonth()).isEqualTo("12");
            assertThat(record.getAcctOpenDateDay()).isEqualTo("31");
        }
    }

    @Nested
    @DisplayName("Raw zoned spans - the stored characters, overpunch and all")
    class RawZonedSpans {
        @Test
        @DisplayName("ACCT-CURR-BAL reads raw as 00000001940{ and NOT as 194.00")
        void currentBalanceReadsRawWithItsOverpunch() {
            AccountRecord row1 = decodedRow1();

            assertThat(row1.rawAcctCurrBal())
                    .isEqualTo(ROW_1_CURR_BAL_RAW)
                    .isEqualTo("00000001940{")
                    .hasSize(AccountRecord.ACCT_CURR_BAL_LENGTH)
                    .isNotEqualTo(row1.getAcctCurrBal().toString());
            assertThat(row1.getAcctCurrBal())
                    .as("and the same twelve bytes decode to the typed value")
                    .isEqualByComparingTo(ROW_1_CURR_BAL);
        }

        @Test
        @DisplayName("All five monetary fields of row 1 read raw with their stored overpunch")
        void allMonetaryFieldsReadRawFromRowOne() {
            AccountRecord row1 = decodedRow1();

            assertThat(row1.rawAcctCurrBal()).isEqualTo(ROW_1_CURR_BAL_RAW);
            assertThat(row1.rawAcctCreditLimit()).isEqualTo(ROW_1_CREDIT_LIMIT_RAW);
            assertThat(row1.rawAcctCashCreditLimit()).isEqualTo(ROW_1_CASH_CREDIT_LIMIT_RAW);
            assertThat(row1.rawAcctCurrCycCredit()).isEqualTo(ROW_1_CURR_CYC_CREDIT_RAW);
            assertThat(row1.rawAcctCurrCycDebit()).isEqualTo(ROW_1_CURR_CYC_DEBIT_RAW);
        }

        @Test
        @DisplayName("Every raw accessor returns exactly its declared width, nothing trimmed")
        void everyRawAccessorReturnsItsDeclaredWidth() {
            AccountRecord row1 = decodedRow1();

            assertThat(row1.rawAcctId()).hasSize(AccountRecord.ACCT_ID_LENGTH);
            assertThat(row1.rawAcctActiveStatus()).hasSize(AccountRecord.ACCT_ACTIVE_STATUS_LENGTH);
            assertThat(row1.rawAcctCurrBal()).hasSize(AccountRecord.ACCT_CURR_BAL_LENGTH);
            assertThat(row1.rawAcctCreditLimit()).hasSize(AccountRecord.ACCT_CREDIT_LIMIT_LENGTH);
            assertThat(row1.rawAcctCashCreditLimit())
                    .hasSize(AccountRecord.ACCT_CASH_CREDIT_LIMIT_LENGTH);
            assertThat(row1.rawAcctOpenDate()).hasSize(AccountRecord.ACCT_OPEN_DATE_LENGTH);
            assertThat(row1.rawAcctExpiraionDate()).hasSize(AccountRecord.ACCT_EXPIRAION_DATE_LENGTH);
            assertThat(row1.rawAcctReissueDate()).hasSize(AccountRecord.ACCT_REISSUE_DATE_LENGTH);
            assertThat(row1.rawAcctCurrCycCredit()).hasSize(AccountRecord.ACCT_CURR_CYC_CREDIT_LENGTH);
            assertThat(row1.rawAcctCurrCycDebit()).hasSize(AccountRecord.ACCT_CURR_CYC_DEBIT_LENGTH);
            assertThat(row1.rawAcctAddrZip()).hasSize(AccountRecord.ACCT_ADDR_ZIP_LENGTH);
            assertThat(row1.rawAcctGroupId())
                    .as("a blank group id is ten spaces, not an empty string - it is never trimmed")
                    .hasSize(AccountRecord.ACCT_GROUP_ID_LENGTH)
                    .isEqualTo(FIXTURE_GROUP_ID);
        }

        @Test
        @DisplayName("Each raw accessor equals the row bytes at that field's absolute offset")
        void eachRawAccessorEqualsTheRowBytesAtItsOffset() {
            String row = fixtureRow1();
            AccountRecord row1 = AccountRecord.decode(row, ASCII);

            assertThat(row1.rawAcctId()).isEqualTo(row.substring(0, 11));
            assertThat(row1.rawAcctActiveStatus()).isEqualTo(row.substring(11, 12));
            assertThat(row1.rawAcctCurrBal()).isEqualTo(row.substring(12, 24));
            assertThat(row1.rawAcctCreditLimit()).isEqualTo(row.substring(24, 36));
            assertThat(row1.rawAcctCashCreditLimit()).isEqualTo(row.substring(36, 48));
            assertThat(row1.rawAcctOpenDate()).isEqualTo(row.substring(48, 58));
            assertThat(row1.rawAcctExpiraionDate()).isEqualTo(row.substring(58, 68));
            assertThat(row1.rawAcctReissueDate()).isEqualTo(row.substring(68, 78));
            assertThat(row1.rawAcctCurrCycCredit()).isEqualTo(row.substring(78, 90));
            assertThat(row1.rawAcctCurrCycDebit()).isEqualTo(row.substring(90, 102));
            assertThat(row1.rawAcctAddrZip()).isEqualTo(row.substring(102, 112));
            assertThat(row1.rawAcctGroupId()).isEqualTo(row.substring(112, 122));
            assertThat(row1.getFiller()).isEqualTo(row.substring(122, 300));
        }

        @Test
        @DisplayName("A span may be read generically, as characters or as bytes")
        void aSpanMayBeReadGenerically() {
            AccountRecord row1 = decodedRow1();

            assertThat(row1.raw(AccountRecord.SPAN_ACCT_CURR_BAL)).isEqualTo(ROW_1_CURR_BAL_RAW);
            assertThat(row1.rawBytes(AccountRecord.SPAN_ACCT_CURR_BAL))
                    .hasSize(AccountRecord.ACCT_CURR_BAL_LENGTH)
                    .isEqualTo(ROW_1_CURR_BAL_RAW.getBytes(ASCII));
            assertThat(row1.rawBytes(AccountRecord.SPAN_ACCT_ID))
                    .as("US-ASCII is named explicitly at the byte boundary (practice B8)")
                    .isEqualTo(ROW_1_ACCT_ID_IMAGE.getBytes(ASCII));
        }

        @Test
        @DisplayName("Reading a span requires a descriptor; null is refused rather than defaulted")
        void readingASpanRequiresADescriptor() {
            AccountRecord row1 = decodedRow1();

            assertThatNullPointerException()
                    .isThrownBy(() -> row1.raw(null))
                    .withMessageContaining("field descriptor");
            assertThatNullPointerException()
                    .isThrownBy(() -> row1.rawBytes(null))
                    .withMessageContaining("field descriptor");
        }

        @Test
        @DisplayName("Offset guard: a span reaching past byte 300 is refused, not truncated")
        void aSpanReachingPastTheRecordIsRefused() {
            AccountRecord row1 = decodedRow1();
            FieldSpan overlong = FieldSpan.alphanumeric("ACCT-BEYOND-END", 295, 10);

            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> row1.raw(overlong))
                    .withMessageContaining("305")
                    .withMessageContaining("past the end of a record of length 300");
        }

        @Test
        @DisplayName("Offset guard: the last declared byte is readable and byte 300 is not")
        void theRecordBoundaryIsExact() {
            AccountRecord row1 = decodedRow1();

            assertThat(row1.raw(FieldSpan.alphanumeric("ACCT-LAST-BYTE", 299, 1)))
                    .as("byte 299 is the last declared byte and reads as a FILLER space")
                    .isEqualTo(" ");
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .as("byte 300 is one past the end")
                    .isThrownBy(() -> row1.raw(FieldSpan.alphanumeric("ACCT-PAST-END", 300, 1)));
        }
    }

    @Nested
    @DisplayName("FILLER X(178) at offset 122 - present and space-filled (G21)")
    class FillerSpan {
        @Test
        @DisplayName("The FILLER span is declared at [122, 300) and carries no VALUE literal")
        void fillerIsDeclaredAsASpan() {
            assertThat(AccountRecord.FILLER_OFFSET).isEqualTo(122);
            assertThat(AccountRecord.FILLER_LENGTH).isEqualTo(178);
            assertThat(AccountRecord.SPAN_FILLER.offset()).isEqualTo(122);
            assertThat(AccountRecord.SPAN_FILLER.length()).isEqualTo(178);
            assertThat(AccountRecord.SPAN_FILLER.endOffsetExclusive())
                    .as("the FILLER closes the record")
                    .isEqualTo(AccountRecord.RECORD_LENGTH);
            assertThat(AccountRecord.SPAN_FILLER.kind()).isEqualTo(PictureKind.FILLER);
            assertThat(AccountRecord.SPAN_FILLER.hasInitialValue())
                    .as("the copybook declares no VALUE clause, so none is invented")
                    .isFalse();
        }

        @Test
        @DisplayName("A decoded fixture record's FILLER is 178 spaces (G21)")
        void decodedFillerIsOneHundredSeventyEightSpaces() {
            AccountRecord row1 = decodedRow1();

            assertThat(row1.getFiller())
                    .hasSize(178)
                    .isEqualTo(FILLER_SPACES)
                    .isBlank();
        }

        @Test
        @DisplayName("Every one of the 50 fixture rows has an all-spaces FILLER (G21)")
        void everyFixtureRowHasASpaceFilledFiller() {
            List<String> rows = fixtureRows();
            assertThat(rows).hasSize(EXPECTED_FIXTURE_ROWS);

            for (int index = 0; index < rows.size(); index++) {
                AccountRecord record = AccountRecord.decode(rows.get(index), ASCII);
                assertThat(record.getFiller())
                        .as("row %d FILLER", index + 1)
                        .hasSize(178)
                        .isEqualTo(FILLER_SPACES);
            }
        }

        @Test
        @DisplayName("A freshly constructed record emits FILLER as 178 spaces (AAP 0.3.7)")
        void freshRecordEmitsFillerAsSpaces() {
            AccountRecord fresh = new AccountRecord(ASCII);

            assertThat(fresh.getFiller()).isEqualTo(FILLER_SPACES).hasSize(178);
            assertThat(fresh.toFixedWidthString())
                    .as("bytes [122, 300) of a fresh image")
                    .endsWith(FILLER_SPACES);
            assertThat(fresh.toFixedWidthString()).hasSize(AccountRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("FILLER is space-filled in the EBCDIC code page too - 0x40, not 0x20")
        void fillerIsSpaceFilledInEbcdicAsWell() {
            byte[] ebcdic = new AccountRecord(EBCDIC).toByteArray();

            assertThat(ebcdic).hasSize(AccountRecord.RECORD_LENGTH);
            for (int index = AccountRecord.FILLER_OFFSET; index < AccountRecord.RECORD_LENGTH; index++) {
                assertThat(ebcdic[index])
                        .as("EBCDIC FILLER byte at offset %d", index)
                        .isEqualTo((byte) 0x40);
            }
            assertThat(new AccountRecord(ASCII).toByteArray()[AccountRecord.FILLER_OFFSET])
                    .as("and 0x20 in US-ASCII")
                    .isEqualTo((byte) 0x20);
        }

        @Test
        @DisplayName("Omitting FILLER is impossible: it is what makes the 300-byte total provable")
        void omittingFillerIsImpossible() {
            int namedFieldBytes = 0;
            for (FieldSpan span : AccountRecord.LAYOUT.storageSpans()) {
                if (!span.kind().filler()) {
                    namedFieldBytes += span.length();
                }
            }

            assertThat(namedFieldBytes)
                    .as("CVACT01Y.cpy:L5-L16 declares 122 bytes of named fields")
                    .isEqualTo(122);
            assertThat(namedFieldBytes + AccountRecord.FILLER_LENGTH)
                    .as("122 + 178 = 300; drop the FILLER and the record is 122 bytes")
                    .isEqualTo(AccountRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("There is no FILLER setter: reserved storage is never assigned by a program")
        void thereIsNoFillerSetter() {
            assertThat(declaredMethods())
                    .extracting(Method::getName)
                    .contains("getFiller")
                    .as("no COBOL program assigns FILLER, so exposing a setter would invite a "
                            + "behaviour change")
                    .doesNotContain("setFiller");
        }

        @Test
        @DisplayName("A non-blank FILLER is retained verbatim, so re-encoding stays byte-identical")
        void aNonBlankFillerIsRetainedVerbatim() {
            String dirty = fixtureRow1().substring(0, AccountRecord.FILLER_OFFSET)
                    + "Z".repeat(AccountRecord.FILLER_LENGTH);
            AccountRecord record = AccountRecord.decode(dirty, ASCII);

            assertThat(record.getFiller()).isEqualTo("Z".repeat(178)).isNotBlank();
            assertThat(record.toFixedWidthString()).isEqualTo(dirty);
        }
    }

    @Nested
    @DisplayName("Fixture round trip - 50 rows of 300 bytes, decoded and re-encoded (G19)")
    class FixtureRoundTrip {
        @Test
        @DisplayName("The fixture holds 50 rows and every one is exactly 300 bytes")
        void everyRowIsExactlyThreeHundredBytes() {
            List<String> rows = fixtureRows();

            assertThat(rows).hasSize(EXPECTED_FIXTURE_ROWS);
            for (int index = 0; index < rows.size(); index++) {
                assertThat(rows.get(index))
                        .as("row %d width", index + 1)
                        .hasSize(AccountRecord.RECORD_LENGTH);
                assertThat(rows.get(index).getBytes(ASCII))
                        .as("row %d byte width in US-ASCII", index + 1)
                        .hasSize(AccountRecord.RECORD_LENGTH);
            }
        }

        @Test
        @DisplayName("Unlike cardxref, this fixture matches its copybook width - nothing is padded")
        void theFixtureNeedsNoWidening() {
            for (String row : fixtureRows()) {
                assertThat(row.length())
                        .as("no widening is required or permitted")
                        .isEqualTo(AccountRecord.RECORD_LENGTH);
            }
        }

        @Test
        @DisplayName("Row 1 decodes field by field to its measured values, all thirteen spans")
        void rowOneDecodesFieldByField() {
            AccountRecord row1 = decodedRow1();

            assertThat(row1.getAcctId()).as("CVACT01Y.cpy:L5").isEqualTo(1L);
            assertThat(row1.getAcctActiveStatus()).as("L6").isEqualTo(ROW_1_ACTIVE_STATUS);
            assertThat(row1.getAcctCurrBal()).as("L7").isEqualByComparingTo(ROW_1_CURR_BAL);
            assertThat(row1.getAcctCreditLimit()).as("L8").isEqualByComparingTo(ROW_1_CREDIT_LIMIT);
            assertThat(row1.getAcctCashCreditLimit())
                    .as("L9").isEqualByComparingTo(ROW_1_CASH_CREDIT_LIMIT);
            assertThat(row1.getAcctOpenDate()).as("L10").isEqualTo(ROW_1_OPEN_DATE);
            assertThat(row1.getAcctExpiraionDate()).as("L11, misspelled").isEqualTo(ROW_1_EXPIRAION_DATE);
            assertThat(row1.getAcctReissueDate()).as("L12").isEqualTo(ROW_1_REISSUE_DATE);
            assertThat(row1.getAcctCurrCycCredit()).as("L13").isEqualByComparingTo(SCALE_2_ZERO);
            assertThat(row1.getAcctCurrCycDebit()).as("L14").isEqualByComparingTo(SCALE_2_ZERO);
            assertThat(row1.getAcctAddrZip()).as("L15").isEqualTo(FIXTURE_ADDR_ZIP);
            assertThat(row1.getAcctGroupId()).as("L16").isEqualTo(FIXTURE_GROUP_ID);
            assertThat(row1.getFiller()).as("L17").isEqualTo(FILLER_SPACES);
        }

        @Test
        @DisplayName("Row 1 re-encodes byte-identically, through characters and through bytes")
        void rowOneReEncodesByteIdentically() {
            String row = fixtureRow1();
            AccountRecord row1 = AccountRecord.decode(row, ASCII);

            assertThat(row1.toFixedWidthString()).isEqualTo(row);
            assertThat(row1.toByteArray()).isEqualTo(row.getBytes(ASCII));
            assertThat(AccountRecord.decode(row.getBytes(ASCII), ASCII).toFixedWidthString())
                    .as("the byte[] and String factories agree")
                    .isEqualTo(row);
        }

        @Test
        @DisplayName("Every row: decode, assert all thirteen spans at their offsets, re-encode identically")
        void everyRowDecodesAndReEncodesIdentically() {
            List<String> rows = fixtureRows();
            assertThat(rows).hasSize(EXPECTED_FIXTURE_ROWS);

            for (int index = 0; index < rows.size(); index++) {
                String row = rows.get(index);
                int rowNumber = index + 1;
                AccountRecord record = AccountRecord.decode(row, ASCII);

                assertThat(record.rawAcctId()).as("row %d L5", rowNumber)
                        .isEqualTo(row.substring(0, 11));
                assertThat(record.getAcctActiveStatus()).as("row %d L6", rowNumber)
                        .isEqualTo(row.substring(11, 12));
                assertThat(record.rawAcctCurrBal()).as("row %d L7", rowNumber)
                        .isEqualTo(row.substring(12, 24));
                assertThat(record.rawAcctCreditLimit()).as("row %d L8", rowNumber)
                        .isEqualTo(row.substring(24, 36));
                assertThat(record.rawAcctCashCreditLimit()).as("row %d L9", rowNumber)
                        .isEqualTo(row.substring(36, 48));
                assertThat(record.getAcctOpenDate()).as("row %d L10", rowNumber)
                        .isEqualTo(row.substring(48, 58));
                assertThat(record.getAcctExpiraionDate()).as("row %d L11", rowNumber)
                        .isEqualTo(row.substring(58, 68));
                assertThat(record.getAcctReissueDate()).as("row %d L12", rowNumber)
                        .isEqualTo(row.substring(68, 78));
                assertThat(record.rawAcctCurrCycCredit()).as("row %d L13", rowNumber)
                        .isEqualTo(row.substring(78, 90));
                assertThat(record.rawAcctCurrCycDebit()).as("row %d L14", rowNumber)
                        .isEqualTo(row.substring(90, 102));
                assertThat(record.getAcctAddrZip()).as("row %d L15", rowNumber)
                        .isEqualTo(row.substring(102, 112));
                assertThat(record.getAcctGroupId()).as("row %d L16", rowNumber)
                        .isEqualTo(row.substring(112, 122));
                assertThat(record.getFiller()).as("row %d L17", rowNumber)
                        .isEqualTo(row.substring(122, 300));

                assertThat(record.toFixedWidthString()).as("row %d re-encoded", rowNumber)
                        .isEqualTo(row);
                assertThat(record.toByteArray()).as("row %d re-encoded bytes", rowNumber)
                        .isEqualTo(row.getBytes(ASCII));
            }
        }

        @Test
        @DisplayName("Every monetary value in the fixture decodes at scale exactly 2 (G23)")
        void everyMonetaryValueDecodesAtScaleTwo() {
            for (String row : fixtureRows()) {
                AccountRecord record = AccountRecord.decode(row, ASCII);
                List<BigDecimal> monetary = List.of(
                        record.getAcctCurrBal(),
                        record.getAcctCreditLimit(),
                        record.getAcctCashCreditLimit(),
                        record.getAcctCurrCycCredit(),
                        record.getAcctCurrCycDebit());

                assertThat(monetary)
                        .as("account %d monetary fields", record.getAcctId())
                        .hasSize(5)
                        .allSatisfy(value -> assertThat(value.scale())
                                .isEqualTo(AccountRecord.MONETARY_SCALE));
            }
        }

        @Test
        @DisplayName("PRESERVED QUIRK: ACCT-ADDR-ZIP is A000000000 in all 50 rows (practice B5)")
        void addrZipIsUniformAcrossEveryRow() {
            Set<String> distinct = new LinkedHashSet<>();
            for (String row : fixtureRows()) {
                distinct.add(AccountRecord.decode(row, ASCII).getAcctAddrZip());
            }

            assertThat(distinct).containsExactly(FIXTURE_ADDR_ZIP);
        }

        @Test
        @DisplayName("PRESERVED QUIRK: ACCT-GROUP-ID is ten spaces in all 50 rows (practice B5)")
        void groupIdIsBlankAcrossEveryRow() {
            Set<String> distinct = new LinkedHashSet<>();
            for (String row : fixtureRows()) {
                distinct.add(AccountRecord.decode(row, ASCII).getAcctGroupId());
            }

            assertThat(distinct).containsExactly(FIXTURE_GROUP_ID);
            assertThat(FIXTURE_GROUP_ID).hasSize(AccountRecord.ACCT_GROUP_ID_LENGTH).isBlank();
        }

        @Test
        @DisplayName("MEASURED: all 250 monetary overpunches in the fixture are '{', i.e. positive zero")
        void everyFixtureOverpunchIsPositiveZero() {
            Set<Character> distinct = new LinkedHashSet<>();
            int counted = 0;
            for (String row : fixtureRows()) {
                AccountRecord record = AccountRecord.decode(row, ASCII);
                for (String raw : List.of(record.rawAcctCurrBal(),
                        record.rawAcctCreditLimit(),
                        record.rawAcctCashCreditLimit(),
                        record.rawAcctCurrCycCredit(),
                        record.rawAcctCurrCycDebit())) {
                    distinct.add(raw.charAt(raw.length() - 1));
                    counted++;
                }
            }

            assertThat(counted).isEqualTo(250);
            assertThat(distinct).containsExactly('{');
        }

        @Test
        @DisplayName("A row that is not exactly 300 bytes is refused rather than silently widened")
        void aShortOrLongRowIsRefused() {
            String row = fixtureRow1();

            assertThatIllegalArgumentException()
                    .as("299 bytes")
                    .isThrownBy(() -> AccountRecord.decode(row.substring(0, 299), ASCII))
                    .withMessageContaining("299");
            assertThatIllegalArgumentException()
                    .as("301 bytes")
                    .isThrownBy(() -> AccountRecord.decode(row + " ", ASCII))
                    .withMessageContaining("301");
            assertThatIllegalArgumentException()
                    .as("empty")
                    .isThrownBy(() -> AccountRecord.decode("", ASCII));
            assertThatIllegalArgumentException()
                    .as("299 raw bytes")
                    .isThrownBy(() -> AccountRecord.decode(new byte[299], ASCII));
        }

        @Test
        @DisplayName("Decoding requires both the bytes and the charset; neither is defaulted")
        void decodingRequiresBothArguments() {
            assertThatNullPointerException()
                    .isThrownBy(() -> AccountRecord.decode((byte[]) null, ASCII))
                    .withMessageContaining("Stored bytes");
            assertThatNullPointerException()
                    .isThrownBy(() -> AccountRecord.decode(new byte[AccountRecord.RECORD_LENGTH], null))
                    .withMessageContaining("charset");
            assertThatNullPointerException()
                    .isThrownBy(() -> AccountRecord.decode((String) null, ASCII))
                    .withMessageContaining("Stored text");
            assertThatNullPointerException()
                    .isThrownBy(() -> AccountRecord.decode(fixtureRow1(), null))
                    .withMessageContaining("charset");
            assertThatNullPointerException()
                    .as("allocating a record needs an explicit code page too")
                    .isThrownBy(() -> new AccountRecord(null));
        }

        @Test
        @DisplayName("The record reports the charset it was built with, never a platform default")
        void theRecordReportsItsOwnCharset() {
            assertThat(new AccountRecord(ASCII).charset()).isEqualTo(ASCII);
            assertThat(new AccountRecord(EBCDIC).charset().name()).isEqualTo("IBM037");
            assertThat(decodedRow1().charset()).isEqualTo(StandardCharsets.US_ASCII);
        }

        @Test
        @DisplayName("A returned image is a copy, so mutating it cannot reach back into the record")
        void aReturnedImageIsACopy() {
            AccountRecord row1 = decodedRow1();
            byte[] image = row1.toByteArray();

            image[0] = (byte) '9';

            assertThat(row1.rawAcctId())
                    .as("the record is unchanged by a caller mutating the array it handed out")
                    .isEqualTo(ROW_1_ACCT_ID_IMAGE);
        }
    }

    @Nested
    @DisplayName("Numeric parity - BigDecimal at scale 2, truncating DOWN (G22, G23, G24)")
    class NumericParity {
        @Test
        @DisplayName("No field or accessor anywhere on the type is double, float, Double or Float (G22)")
        void noBinaryFloatingPointAnywhere() {
            List<Class<?>> forbidden = List.of(double.class, float.class, Double.class, Float.class);

            for (Field field : authoredFields(AccountRecord.class)) {
                assertThat(forbidden)
                        .as("field %s is declared %s", field.getName(), field.getType().getName())
                        .doesNotContain(field.getType());
            }
            for (Method method : declaredMethods()) {
                assertThat(forbidden)
                        .as("method %s returns %s", method.getName(), method.getReturnType().getName())
                        .doesNotContain(method.getReturnType());
                for (Class<?> parameter : method.getParameterTypes()) {
                    assertThat(forbidden)
                            .as("method %s takes a %s", method.getName(), parameter.getName())
                            .doesNotContain(parameter);
                }
            }
        }

        @Test
        @DisplayName("The five monetary accessors return BigDecimal, not a primitive")
        void monetaryAccessorsReturnBigDecimal() throws NoSuchMethodException {
            for (String accessor : List.of("getAcctCurrBal", "getAcctCreditLimit",
                    "getAcctCashCreditLimit", "getAcctCurrCycCredit", "getAcctCurrCycDebit")) {
                assertThat(AccountRecord.class.getMethod(accessor).getReturnType())
                        .as("%s must return BigDecimal (AAP rule R4)", accessor)
                        .isEqualTo(BigDecimal.class);
            }
        }

        @Test
        @DisplayName("Every monetary accessor reports scale exactly 2 on a fixture record (G23)")
        void monetaryScaleIsExactlyTwo() {
            AccountRecord row1 = decodedRow1();

            assertThat(AccountRecord.MONETARY_SCALE)
                    .as("the s in PIC S9(10)V99, and the one definition of monetary scale")
                    .isEqualTo(2)
                    .isEqualTo(CobolDecimal.MONETARY_SCALE);
            assertThat(row1.getAcctCurrBal().scale()).isEqualTo(2);
            assertThat(row1.getAcctCreditLimit().scale()).isEqualTo(2);
            assertThat(row1.getAcctCashCreditLimit().scale()).isEqualTo(2);
            assertThat(row1.getAcctCurrCycCredit().scale()).isEqualTo(2);
            assertThat(row1.getAcctCurrCycDebit().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("A zero value still reports scale 2, where a careless implementation yields 0 (G23)")
        void zeroStillReportsScaleTwo() {
            AccountRecord fresh = new AccountRecord(ASCII);
            assertThat(fresh.getAcctCurrBal().scale()).as("a fresh record's balance").isEqualTo(2);
            assertThat(fresh.getAcctCurrBal()).isEqualTo(SCALE_2_ZERO);

            AccountRecord written = new AccountRecord(ASCII);
            written.setAcctCurrBal(BigDecimal.ZERO);
            assertThat(written.getAcctCurrBal().scale())
                    .as("storing an unscaled zero must still read back at the declared scale")
                    .isEqualTo(2);
            assertThat(written.getAcctCurrBal()).isEqualTo(SCALE_2_ZERO);
            assertThat(written.rawAcctCurrBal()).isEqualTo("00000000000{");

            written.zeroAcctCurrCycCredit();
            written.zeroAcctCurrCycDebit();
            assertThat(written.getAcctCurrCycCredit()).isEqualTo(SCALE_2_ZERO);
            assertThat(written.getAcctCurrCycDebit()).isEqualTo(SCALE_2_ZERO);
            assertThat(written.rawAcctCurrCycCredit()).isEqualTo("00000000000{");
            assertThat(written.rawAcctCurrCycDebit()).isEqualTo("00000000000{");
        }

        @Test
        @DisplayName("The rounding policy is DOWN, because ROUNDED appears zero times in the COBOL (G24)")
        void theRoundingPolicyIsDown() {
            assertThat(CobolDecimal.COBOL_ROUNDING).isEqualTo(RoundingMode.DOWN);
        }

        @Test
        @DisplayName("A store truncates DOWN where HALF_UP would round up - the two differ visibly")
        void storeTruncatesWhereHalfUpWouldRound() {
            BigDecimal input = new BigDecimal("1.005");
            assertThat(input.setScale(2, RoundingMode.HALF_UP))
                    .as("the wrong answer, shown so the difference is explicit")
                    .isEqualByComparingTo(new BigDecimal("1.01"));

            AccountRecord record = new AccountRecord(ASCII);
            record.setAcctCurrBal(input);

            assertThat(record.getAcctCurrBal())
                    .as("DOWN truncates the third fraction digit away")
                    .isEqualByComparingTo(new BigDecimal("1.00"))
                    .isNotEqualByComparingTo(new BigDecimal("1.01"));
            assertThat(record.rawAcctCurrBal()).isEqualTo("00000000010{");
        }

        @ParameterizedTest(name = "{0} stores as {1} with raw image {2}")
        @CsvSource({
            "194.999,   194.99,  00000001949I",
            "-194.999,  -194.99, 00000001949R",
            "0.009,     0.00,    00000000000{",
            "-0.009,    0.00,    00000000000{",
            "2020.00,   2020.00, 00000020200{",
        })
        @DisplayName("Truncation toward zero applies to negative values as well as positive")
        void truncationIsTowardZeroForBothSigns(String input, String stored, String rawImage) {
            AccountRecord record = new AccountRecord(ASCII);
            record.setAcctCurrBal(new BigDecimal(input));

            assertThat(record.getAcctCurrBal()).isEqualByComparingTo(new BigDecimal(stored));
            assertThat(record.getAcctCurrBal().scale()).isEqualTo(2);
            assertThat(record.rawAcctCurrBal()).isEqualTo(rawImage);
        }

        @Test
        @DisplayName("More than ten integer digits: the HIGH-ORDER digits are dropped, and nothing throws")
        void integerOverflowTruncatesOnTheLeft() {
            AccountRecord record = new AccountRecord(ASCII);
            record.setAcctCurrBal(new BigDecimal("123456789012.34"));

            assertThat(record.getAcctCurrBal())
                    .isEqualByComparingTo(new BigDecimal("3456789012.34"));
            assertThat(record.rawAcctCurrBal())
                    .as("twelve stored bytes: ten integer digits, one fraction digit, one overpunch")
                    .isEqualTo("34567890123D")
                    .hasSize(12);
        }

        @Test
        @DisplayName("High-order truncation keeps the sign as well as the low-order digits")
        void integerOverflowKeepsTheSign() {
            AccountRecord record = new AccountRecord(ASCII);
            record.setAcctCurrBal(new BigDecimal("-123456789012.34"));

            assertThat(record.getAcctCurrBal())
                    .isEqualByComparingTo(new BigDecimal("-3456789012.34"))
                    .isNegative();
            assertThat(record.rawAcctCurrBal()).isEqualTo("34567890123M");
        }

        @Test
        @DisplayName("Excess fraction digits and excess integer digits are both discarded in one store")
        void bothTruncationsApplyTogether() {
            AccountRecord record = new AccountRecord(ASCII);
            record.setAcctCurrBal(new BigDecimal("99123456789012.999"));

            assertThat(record.getAcctCurrBal())
                    .isEqualByComparingTo(new BigDecimal("3456789012.99"));
            assertThat(CobolDecimal.storeAtPicture(new BigDecimal("99123456789012.999"),
                    AccountRecord.MONETARY_INTEGER_DIGITS, AccountRecord.MONETARY_SCALE))
                    .as("the record delegates to exactly this operation")
                    .isEqualByComparingTo(record.getAcctCurrBal());
        }

        @Test
        @DisplayName("The cycle amounts accept arbitrary signed values, not only zero")
        void cycleAmountsAcceptArbitrarySignedValues() {
            AccountRecord record = new AccountRecord(ASCII);

            record.setAcctCurrCycCredit(new BigDecimal("1234.56"));
            record.setAcctCurrCycDebit(new BigDecimal("-987.65"));

            assertThat(record.getAcctCurrCycCredit())
                    .isEqualByComparingTo(new BigDecimal("1234.56"));
            assertThat(record.getAcctCurrCycCredit().scale()).isEqualTo(2);
            assertThat(record.rawAcctCurrCycCredit())
                    .as("digits 000000123456 with the trailing 6 overpunched positive to F")
                    .isEqualTo("00000012345F");

            assertThat(record.getAcctCurrCycDebit())
                    .isEqualByComparingTo(new BigDecimal("-987.65"))
                    .isNegative();
            assertThat(record.getAcctCurrCycDebit().scale()).isEqualTo(2);
            assertThat(record.rawAcctCurrCycDebit())
                    .as("digits 000000098765 with the trailing 5 overpunched negative to N")
                    .isEqualTo("00000009876N");

            assertThat(record.rawAcctCurrCycCredit()).isEqualTo("00000012345F");
            assertThat(record.toFixedWidthString()).hasSize(AccountRecord.RECORD_LENGTH);

            record.zeroAcctCurrCycCredit();
            record.zeroAcctCurrCycDebit();
            assertThat(record.rawAcctCurrCycCredit()).isEqualTo("00000000000{");
            assertThat(record.rawAcctCurrCycDebit()).isEqualTo("00000000000{");
        }

        @Test
        @DisplayName("Truncation applies to the cycle amounts on the same terms as the balance")
        void cycleAmountsTruncateOnTheSameTerms() {
            AccountRecord record = new AccountRecord(ASCII);

            record.setAcctCurrCycCredit(new BigDecimal("10.009"));
            record.setAcctCurrCycDebit(new BigDecimal("123456789012.34"));

            assertThat(record.getAcctCurrCycCredit())
                    .as("DOWN, never HALF_UP")
                    .isEqualByComparingTo(new BigDecimal("10.00"));
            assertThat(record.getAcctCurrCycDebit())
                    .as("high-order digits dropped, no exception")
                    .isEqualByComparingTo(new BigDecimal("3456789012.34"));
        }

        @Test
        @DisplayName("A monetary field has no null representation; storing null is refused")
        void storingNullIntoAMonetaryFieldIsRefused() {
            AccountRecord record = new AccountRecord(ASCII);

            assertThatNullPointerException()
                    .isThrownBy(() -> record.setAcctCurrBal(null))
                    .withMessageContaining("acctCurrBal")
                    .withMessageContaining("PIC S9(10)V99");
            assertThatNullPointerException()
                    .isThrownBy(() -> record.setAcctCreditLimit(null))
                    .withMessageContaining("acctCreditLimit");
            assertThatNullPointerException()
                    .isThrownBy(() -> record.setAcctCashCreditLimit(null))
                    .withMessageContaining("acctCashCreditLimit");
            assertThatNullPointerException()
                    .isThrownBy(() -> record.setAcctCurrCycCredit(null))
                    .withMessageContaining("acctCurrCycCredit");
            assertThatNullPointerException()
                    .isThrownBy(() -> record.setAcctCurrCycDebit(null))
                    .withMessageContaining("acctCurrCycDebit");
        }

        @Test
        @DisplayName("A PIC X field has no null representation either; blank it with an empty string")
        void storingNullIntoACharacterFieldIsRefused() {
            AccountRecord record = new AccountRecord(ASCII);

            assertThatNullPointerException()
                    .isThrownBy(() -> record.setAcctActiveStatus(null))
                    .withMessageContaining("acctActiveStatus");
            assertThatNullPointerException()
                    .isThrownBy(() -> record.setAcctOpenDate(null))
                    .withMessageContaining("acctOpenDate");
            assertThatNullPointerException()
                    .isThrownBy(() -> record.setAcctExpiraionDate(null))
                    .withMessageContaining("acctExpiraionDate");
            assertThatNullPointerException()
                    .isThrownBy(() -> record.setAcctReissueDate(null))
                    .withMessageContaining("acctReissueDate");
            assertThatNullPointerException()
                    .isThrownBy(() -> record.setAcctAddrZip(null))
                    .withMessageContaining("acctAddrZip");
            assertThatNullPointerException()
                    .isThrownBy(() -> record.setAcctGroupId(null))
                    .withMessageContaining("acctGroupId");

            record.setAcctGroupId("");
            assertThat(record.getAcctGroupId())
                    .as("an empty string is how SPACES is expressed")
                    .isEqualTo(FIXTURE_GROUP_ID);
        }

        @Test
        @DisplayName("A PIC X receiver truncates on the RIGHT - the opposite of the numeric rule")
        void picXTruncatesOnTheRight() {
            AccountRecord record = new AccountRecord(ASCII);

            record.setAcctGroupId("ABCDEFGHIJKL");
            assertThat(record.getAcctGroupId())
                    .as("twelve characters into X(10) keeps the LEADING ten")
                    .isEqualTo("ABCDEFGHIJ");

            record.setAcctGroupId("AB");
            assertThat(record.getAcctGroupId())
                    .as("and short input is space-padded on the right")
                    .isEqualTo("AB        ");

            record.setAcctActiveStatus("YN");
            assertThat(record.getAcctActiveStatus())
                    .as("two characters into X(01) keeps the first")
                    .isEqualTo("Y");
        }

        @Test
        @DisplayName("The interest divisor and monetary scale come from CobolDecimal, not restated here")
        void numericPolicyLivesInOnePlace() {
            assertThat(AccountRecord.MONETARY_SCALE).isEqualTo(CobolDecimal.MONETARY_SCALE);
            assertThat(CobolDecimal.MONTHLY_INTEREST_DIVISOR).isEqualTo(1200L);
            assertThat(AccountRecord.MONETARY_INTEGER_DIGITS)
                    .as("the p in PIC S9(10)V99")
                    .isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("Sign overpunch - the branches the fixture cannot reach")
    class SignOverpunch {
        private static final String OVERPUNCH_NOTE =
                "positive {ABCDEFGHI = +0..+9, negative }JKLMNOPQR = -0..-9";

        @Test
        @DisplayName("The note above is a statement of record, and the table it states is the real one")
        void theZonedTableIsAsRecorded() {
            assertThat(OVERPUNCH_NOTE).contains("{ABCDEFGHI").contains("}JKLMNOPQR");

            assertThat(asciiCodec.decodeSignedScaled("00000000000{", 2))
                    .isEqualByComparingTo(SCALE_2_ZERO);
            assertThat(asciiCodec.decodeSignedScaled("00000000000D", 2))
                    .as("D is the fourth letter: +4 in the low-order digit position")
                    .isEqualByComparingTo(new BigDecimal("0.04"));
            assertThat(asciiCodec.decodeSignedScaled("00000000000E", 2))
                    .as("E is the fifth letter: +5, not +4")
                    .isEqualByComparingTo(new BigDecimal("0.05"));
            assertThat(asciiCodec.decodeSignedScaled("00000000000I", 2))
                    .isEqualByComparingTo(new BigDecimal("0.09"));
            assertThat(asciiCodec.decodeSignedScaled("00000000000M", 2))
                    .isEqualByComparingTo(new BigDecimal("-0.04"));
            assertThat(asciiCodec.decodeSignedScaled("00000000000N", 2))
                    .as("N is the fifth negative letter: -5")
                    .isEqualByComparingTo(new BigDecimal("-0.05"));
            assertThat(asciiCodec.decodeSignedScaled("00000000000R", 2))
                    .isEqualByComparingTo(new BigDecimal("-0.09"));
        }

        @ParameterizedTest(name = "stored {0} decodes to {1}")
        @CsvSource({
            "00000001940{, 194.00",
            "00000000000{, 0.00",
            "00000012345D, 1234.54",
            "00000012345E, 1234.55",
            "00000012345I, 1234.59",
            "00000012345A, 1234.51",
            "00000012345N, -1234.55",
            "00000012345J, -1234.51",
            "00000012345R, -1234.59",
            "00000000000}, 0.00",
            "000000000000, 0.00",
            "000000019407, 194.07",
        })
        @DisplayName("Decode: the trailing byte carries the low-order digit AND the sign")
        void decodeReadsTheTrailingByteAsDigitAndSign(String stored, String expected) {
            assertThat(stored).hasSize(AccountRecord.ACCT_CURR_BAL_LENGTH);

            BigDecimal decoded = asciiCodec.decodeSignedScaled(stored, AccountRecord.MONETARY_SCALE);

            assertThat(decoded).isEqualByComparingTo(new BigDecimal(expected));
            assertThat(decoded.scale()).as("always the declared scale").isEqualTo(2);
        }

        @ParameterizedTest(name = "{0} encodes to {1}")
        @CsvSource({
            "194.00,   00000001940{",
            "0.00,     00000000000{",
            "1234.54,  00000012345D",
            "1234.55,  00000012345E",
            "1234.59,  00000012345I",
            "-1234.55, 00000012345N",
            "-1234.51, 00000012345J",
            "-1234.59, 00000012345R",
        })
        @DisplayName("Encode: the same three sign cases in the write direction")
        void encodeWritesTheOverpunchIntoTheTrailingByte(String value, String expectedImage) {
            String encoded = asciiCodec.encodeSignedScaled(new BigDecimal(value),
                    AccountRecord.MONETARY_INTEGER_DIGITS, AccountRecord.MONETARY_SCALE);

            assertThat(encoded)
                    .isEqualTo(expectedImage)
                    .hasSize(AccountRecord.ACCT_CURR_BAL_LENGTH);
        }

        @ParameterizedTest(name = "{0} survives a decode-encode round trip")
        @ValueSource(strings = {
            "00000001940{",
            "00000000000{",
            "00000012345D",
            "00000012345E",
            "00000012345N",
            "00000012345R",
        })
        @DisplayName("Round trip: a stored image decodes and re-encodes to itself")
        void aStoredImageRoundTrips(String stored) {
            BigDecimal decoded = asciiCodec.decodeSignedScaled(stored, AccountRecord.MONETARY_SCALE);
            String reEncoded = asciiCodec.encodeSignedScaled(decoded,
                    AccountRecord.MONETARY_INTEGER_DIGITS, AccountRecord.MONETARY_SCALE);

            assertThat(reEncoded).isEqualTo(stored);
        }

        @ParameterizedTest(name = "a record storing {0} round trips through all 300 bytes")
        @CsvSource({
            "00000012345E, 1234.55",
            "00000012345N, -1234.55",
            "00000012345D, 1234.54",
            "00000000000}, 0.00",
            "000000000000, 0.00",
        })
        @DisplayName("Through the record type: a synthesised sign case decodes and re-encodes")
        void aSynthesisedSignCaseRoundTripsThroughTheRecord(String storedBalance, String expected) {
            String image = row1WithBalance(storedBalance);
            AccountRecord record = AccountRecord.decode(image, ASCII);

            assertThat(record.rawAcctCurrBal())
                    .as("the raw span keeps the synthesised overpunch verbatim")
                    .isEqualTo(storedBalance);
            assertThat(record.getAcctCurrBal()).isEqualByComparingTo(new BigDecimal(expected));
            assertThat(record.getAcctCurrBal().scale()).isEqualTo(2);
            assertThat(record.toFixedWidthString())
                    .as("and the whole 300-byte image is unchanged")
                    .isEqualTo(image);
        }

        @Test
        @DisplayName("A negative balance written through the setter stores a negative overpunch")
        void aNegativeBalanceWritesANegativeOverpunch() {
            AccountRecord record = new AccountRecord(ASCII);
            record.setAcctCurrBal(new BigDecimal("-1234.55"));

            assertThat(record.rawAcctCurrBal()).isEqualTo("00000012345N");
            assertThat(record.getAcctCurrBal())
                    .isEqualByComparingTo(new BigDecimal("-1234.55"))
                    .isNegative();
            assertThat(record.getAcctCurrBal().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("A freshly established monetary span carries a positive-zero overpunch")
        void aFreshMonetarySpanCarriesAPositiveZeroOverpunch() {
            AccountRecord fresh = new AccountRecord(ASCII);

            assertThat(fresh.rawAcctCurrBal()).isEqualTo("00000000000{");
            assertThat(fresh.getAcctCurrBal()).isEqualByComparingTo(SCALE_2_ZERO);

            fresh.setAcctCurrBal(SCALE_2_ZERO);
            assertThat(fresh.rawAcctCurrBal())
                    .as("writing zero over it changes nothing, which is the point")
                    .isEqualTo("00000000000{");
            assertThat(asciiCodec.decodeSignedScaled("000000000000", 2))
                    .as("the unsigned zone F form still decodes, because a row written by a program "
                            + "that treated the picture as unsigned has to remain readable")
                    .isEqualByComparingTo(SCALE_2_ZERO);
        }

        @Test
        @DisplayName("A trailing byte that is neither a digit nor an overpunch character is refused")
        void anUnrecognisedTrailingByteIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> asciiCodec.decodeSignedScaled("00000000000$", 2))
                    .withMessageContaining("neither a digit nor a sign overpunch");
        }
    }

    @Nested
    @DisplayName("Two typed views over one backing span, round-tripped (G34)")
    class RedefinesOverlay {
        @Test
        @DisplayName("The typed long view and the raw character view address the same eleven bytes")
        void theTypedAndRawViewsShareOneSpan() {
            AccountRecord row1 = decodedRow1();

            assertThat(row1.getAcctId()).isEqualTo(1L);
            assertThat(row1.rawAcctId()).isEqualTo("00000000001");
            assertThat(row1.rawBytes(AccountRecord.SPAN_ACCT_ID))
                    .isEqualTo(row1.rawAcctId().getBytes(ASCII));
        }

        @Test
        @DisplayName("Writing through the typed view is immediately visible through the raw view")
        void writingThroughTheTypedViewIsVisibleThroughTheRawView() {
            AccountRecord record = decodedRow1();
            FieldSpan asCharacters =
                    AccountRecord.SPAN_ACCT_ID.redefinedAs("ACCT-ID-X", PictureKind.ALPHANUMERIC);

            assertThat(record.raw(asCharacters)).isEqualTo("00000000001");

            record.setAcctId(42L);

            assertThat(record.raw(asCharacters))
                    .as("the overlay observes the write because it is the same storage")
                    .isEqualTo("00000000042");
            assertThat(record.rawAcctId()).isEqualTo("00000000042");
            assertThat(record.getAcctId()).isEqualTo(42L);
            assertThat(record.keyImage()).isEqualTo("00000000042");
        }

        @Test
        @DisplayName("An overlay descriptor keeps the offset and width of the span it redefines")
        void anOverlayKeepsTheGeometryOfTheSpanItRedefines() {
            FieldSpan asCharacters =
                    AccountRecord.SPAN_ACCT_ID.redefinedAs("ACCT-ID-X", PictureKind.ALPHANUMERIC);

            assertThat(asCharacters.offset()).isEqualTo(AccountRecord.ACCT_ID_OFFSET);
            assertThat(asCharacters.length()).isEqualTo(AccountRecord.ACCT_ID_LENGTH);
            assertThat(asCharacters.kind()).isEqualTo(PictureKind.ALPHANUMERIC);
            assertThat(asCharacters.redefinition()).isTrue();
            assertThat(asCharacters.describe())
                    .as("a failure message names the offending descriptor, not just an offset")
                    .contains("ACCT-ID-X")
                    .contains("REDEFINES overlay");
        }

        @Test
        @DisplayName("A narrower overlay may cover only the leading bytes of the span it redefines")
        void aNarrowerOverlayCoversTheLeadingBytes() {
            FieldSpan yearOverlay = AccountRecord.SPAN_ACCT_OPEN_DATE
                    .redefinedAs("ACCT-OPEN-DATE-YEAR", PictureKind.UNSIGNED_NUMERIC, 4);
            AccountRecord row1 = decodedRow1();

            assertThat(yearOverlay.offset()).isEqualTo(AccountRecord.ACCT_OPEN_DATE_OFFSET);
            assertThat(yearOverlay.length()).isEqualTo(4);
            assertThat(row1.raw(yearOverlay))
                    .isEqualTo("2014")
                    .isEqualTo(row1.getAcctOpenDateYear());
        }

        @Test
        @DisplayName("An overlay wider than the span it redefines is refused")
        void anOverlayWiderThanItsSpanIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> AccountRecord.SPAN_ACCT_OPEN_DATE
                            .redefinedAs("ACCT-OPEN-DATE-TOO-WIDE", PictureKind.ALPHANUMERIC, 11))
                    .withMessageContaining("must not exceed the span it redefines");
        }

        @Test
        @DisplayName("A monetary span viewed as characters yields the stored image; as a number, the value")
        void aMonetarySpanHasBothATypedAndACharacterView() {
            AccountRecord record = decodedRow1();
            FieldSpan balanceAsCharacters = AccountRecord.SPAN_ACCT_CURR_BAL
                    .redefinedAs("ACCT-CURR-BAL-X", PictureKind.ALPHANUMERIC);

            assertThat(record.raw(balanceAsCharacters)).isEqualTo(ROW_1_CURR_BAL_RAW);
            assertThat(record.getAcctCurrBal()).isEqualByComparingTo(ROW_1_CURR_BAL);

            record.setAcctCurrBal(new BigDecimal("-1234.55"));

            assertThat(record.raw(balanceAsCharacters))
                    .as("the character view follows the typed write, overpunch included")
                    .isEqualTo("00000012345N");
        }

        @Test
        @DisplayName("Writing every field then re-reading it round trips through the 300-byte image")
        void writingEveryFieldRoundTripsThroughTheImage() {
            AccountRecord written = new AccountRecord(ASCII);
            written.setAcctId(1L);
            written.setAcctActiveStatus(ROW_1_ACTIVE_STATUS);
            written.setAcctCurrBal(ROW_1_CURR_BAL);
            written.setAcctCreditLimit(ROW_1_CREDIT_LIMIT);
            written.setAcctCashCreditLimit(ROW_1_CASH_CREDIT_LIMIT);
            written.setAcctOpenDate(ROW_1_OPEN_DATE);
            written.setAcctExpiraionDate(ROW_1_EXPIRAION_DATE);
            written.setAcctReissueDate(ROW_1_REISSUE_DATE);
            written.zeroAcctCurrCycCredit();
            written.zeroAcctCurrCycDebit();
            written.setAcctAddrZip(FIXTURE_ADDR_ZIP);
            written.setAcctGroupId(FIXTURE_GROUP_ID);

            assertThat(written.toFixedWidthString()).isEqualTo(fixtureRow1());
            assertThat(written.toByteArray()).isEqualTo(fixtureRow1().getBytes(ASCII));
            assertThat(written).isEqualTo(decodedRow1());
        }
    }

    @Nested
    @DisplayName("Value semantics - equality over the complete 300-byte image")
    class ValueSemantics {
        @Test
        @DisplayName("Two records decoded from the same row are equal and hash equally")
        void equalByValueRecordsAreEqualAndHashEqually() {
            AccountRecord first = decodedRow1();
            AccountRecord second = decodedRow1();

            assertThat(first).isEqualTo(second);
            assertThat(second).isEqualTo(first);
            assertThat(first).hasSameHashCodeAs(second);
        }

        @Test
        @DisplayName("A record equals itself, and equals neither null nor another type")
        void reflexiveAndTypeSafe() {
            AccountRecord record = decodedRow1();

            assertThat(record.equals(record)).as("reflexive").isTrue();
            assertThat(record.equals(null)).as("never equal to null").isFalse();
            assertThat(record.equals(ROW_1_ACCT_ID_IMAGE))
                    .as("never equal to a String that merely looks like its key")
                    .isFalse();
        }

        @Test
        @DisplayName("Differing in a PIC 9 field alone makes two records unequal")
        void differingInAPicNineFieldMakesRecordsUnequal() {
            AccountRecord baseline = decodedRow1();
            AccountRecord altered = decodedRow1();
            altered.setAcctId(2L);

            assertThat(altered).isNotEqualTo(baseline);
            assertThat(altered.hashCode()).isNotEqualTo(baseline.hashCode());
        }

        @Test
        @DisplayName("Differing in a PIC X field alone makes two records unequal")
        void differingInAPicXFieldMakesRecordsUnequal() {
            AccountRecord baseline = decodedRow1();
            AccountRecord altered = decodedRow1();
            altered.setAcctActiveStatus("N");

            assertThat(altered).isNotEqualTo(baseline);
            assertThat(altered.getAcctActiveStatus()).isEqualTo("N");
            assertThat(baseline.getAcctActiveStatus()).isEqualTo(ROW_1_ACTIVE_STATUS);
        }

        @Test
        @DisplayName("Differing in a PIC S9(10)V99 field alone makes two records unequal")
        void differingInAMonetaryFieldMakesRecordsUnequal() {
            AccountRecord baseline = decodedRow1();
            AccountRecord altered = decodedRow1();
            altered.setAcctCurrBal(new BigDecimal("194.01"));

            assertThat(altered).isNotEqualTo(baseline);
            assertThat(altered.rawAcctCurrBal()).isEqualTo("00000001940A");
        }

        @Test
        @DisplayName("Differing only in the reserved FILLER still makes two records unequal")
        void differingOnlyInTheFillerMakesRecordsUnequal() {
            AccountRecord baseline = decodedRow1();
            AccountRecord dirty = AccountRecord.decode(
                    fixtureRow1().substring(0, AccountRecord.FILLER_OFFSET)
                            + "Z".repeat(AccountRecord.FILLER_LENGTH), ASCII);

            assertThat(dirty).isNotEqualTo(baseline);
            assertThat(dirty.getAcctId()).isEqualTo(baseline.getAcctId());
            assertThat(dirty.getAcctCurrBal()).isEqualByComparingTo(baseline.getAcctCurrBal());
        }

        @Test
        @DisplayName("TRAP: two BigDecimals of different scale for the same value store identically")
        void differentlyScaledInputsForTheSameValueStoreIdentically() {
            assertThat(new BigDecimal("194.0"))
                    .as("scale-sensitive equals - the trap itself")
                    .isNotEqualTo(new BigDecimal("194.00"))
                    .isEqualByComparingTo(new BigDecimal("194.00"));

            AccountRecord fromShortScale = new AccountRecord(ASCII);
            fromShortScale.setAcctCurrBal(new BigDecimal("194.0"));
            AccountRecord fromDeclaredScale = new AccountRecord(ASCII);
            fromDeclaredScale.setAcctCurrBal(new BigDecimal("194.00"));
            AccountRecord fromLongScale = new AccountRecord(ASCII);
            fromLongScale.setAcctCurrBal(new BigDecimal("194.000"));

            assertThat(fromShortScale).isEqualTo(fromDeclaredScale).isEqualTo(fromLongScale);
            assertThat(fromShortScale).hasSameHashCodeAs(fromDeclaredScale);
            assertThat(fromShortScale.getAcctCurrBal())
                    .as("and all three read back at the declared scale")
                    .isEqualTo(new BigDecimal("194.00"));
            assertThat(fromShortScale.getAcctCurrBal().scale()).isEqualTo(2);
            assertThat(fromLongScale.getAcctCurrBal().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("Records in different code pages are unequal, because their bytes differ")
        void recordsInDifferentCodePagesAreUnequal() {
            assertThat(new AccountRecord(EBCDIC))
                    .as("correct for a type whose contract is its bytes")
                    .isNotEqualTo(new AccountRecord(ASCII));
        }

        @Test
        @DisplayName("toString names every field and shows monetary values as their stored images")
        void toStringNamesEveryField() {
            String rendered = decodedRow1().toString();

            assertThat(rendered)
                    .startsWith("AccountRecord[")
                    .endsWith("]")
                    .contains(AccountRecord.ACCT_ID_NAME + "=")
                    .contains(AccountRecord.ACCT_ACTIVE_STATUS_NAME + "='" + ROW_1_ACTIVE_STATUS + "'")
                    .contains(AccountRecord.ACCT_CURR_BAL_NAME + "=")
                    .contains(AccountRecord.ACCT_CREDIT_LIMIT_NAME + "=")
                    .contains(AccountRecord.ACCT_CASH_CREDIT_LIMIT_NAME + "=")
                    .contains(AccountRecord.ACCT_OPEN_DATE_NAME + "=")
                    .contains(AccountRecord.ACCT_EXPIRAION_DATE_NAME + "=")
                    .contains(AccountRecord.ACCT_REISSUE_DATE_NAME + "=")
                    .contains(AccountRecord.ACCT_CURR_CYC_CREDIT_NAME + "=")
                    .contains(AccountRecord.ACCT_CURR_CYC_DEBIT_NAME + "=")
                    .contains(AccountRecord.ACCT_ADDR_ZIP_NAME + "=")
                    .contains(AccountRecord.ACCT_GROUP_ID_NAME + "=")
                    .contains("charset=US-ASCII");
        }

        @Test
        @DisplayName("toString identifies the record but discloses neither money nor postcode")
        void toStringIdentifiesWithoutDisclosing() {
            String rendered = decodedRow1().toString();

            assertThat(rendered).doesNotContain(ROW_1_ACCT_ID_IMAGE)
                    .contains(DiagnosticText.masked(ROW_1_ACCT_ID_IMAGE));
            assertThat(rendered)
                    .doesNotContain(ROW_1_CURR_BAL_RAW)
                    .doesNotContain(ROW_1_CREDIT_LIMIT_RAW)
                    .doesNotContain(ROW_1_CASH_CREDIT_LIMIT_RAW)
                    .doesNotContain(ROW_1_CURR_CYC_CREDIT_RAW)
                    .doesNotContain(ROW_1_CURR_CYC_DEBIT_RAW)
                    .doesNotContain(FIXTURE_ADDR_ZIP.trim());
            assertThat(rendered).contains(DiagnosticText.OMITTED + ":" + ROW_1_CURR_BAL_RAW.length());
        }

        @Test
        @DisplayName("toString summarises the 178-byte FILLER rather than printing it, both ways")
        void toStringSummarisesTheFiller() {
            assertThat(decodedRow1().toString())
                    .as("a blank FILLER is summarised, keeping a log line readable")
                    .contains("FILLER=178 spaces")
                    .doesNotContain(FILLER_SPACES);

            String dirty = fixtureRow1().substring(0, AccountRecord.FILLER_OFFSET)
                    + "Z".repeat(AccountRecord.FILLER_LENGTH);
            assertThat(AccountRecord.decode(dirty, ASCII).toString())
                    .as("and a non-blank FILLER is flagged rather than hidden")
                    .contains("FILLER=178 bytes, not blank");
        }

        @Test
        @DisplayName("toString is diagnostic only; the parity contract is the byte image")
        void toStringIsNotTheParityContract() {
            AccountRecord row1 = decodedRow1();

            assertThat(row1.toString())
                    .as("the rendering is longer and differently shaped than the record")
                    .hasSizeGreaterThan(AccountRecord.RECORD_LENGTH)
                    .isNotEqualTo(row1.toFixedWidthString());
            assertThat(row1.toFixedWidthString())
                    .as("the contract, which is exactly 300 characters")
                    .hasSize(AccountRecord.RECORD_LENGTH);
        }
    }

    @Nested
    @DisplayName("Schema integrity - no persistence mapping, no DDL, no static mutable state")
    class SchemaIntegrity {
        private static final List<String> FORBIDDEN_ANNOTATIONS =
                List.of("Entity", "Table", "Id", "Column", "Version", "GeneratedValue",
                        "EmbeddedId", "JoinColumn", "SequenceGenerator");

        private static final List<String> FORBIDDEN_DDL =
                List.of("CREATE TABLE", "ALTER TABLE", "DROP TABLE", "INSERT INTO", "CREATE INDEX");

        @Test
        @DisplayName("The type carries no persistence annotation of any kind (G44)")
        void theTypeCarriesNoPersistenceAnnotation() {
            assertForbiddenAnnotationsAbsent(AccountRecord.class.getAnnotations(),
                    "class AccountRecord");

            for (Field field : authoredFields(AccountRecord.class)) {
                assertForbiddenAnnotationsAbsent(field.getAnnotations(), "field " + field.getName());
            }
            for (Method method : declaredMethods()) {
                assertForbiddenAnnotationsAbsent(method.getAnnotations(), "method " + method.getName());
            }
        }

        private void assertForbiddenAnnotationsAbsent(Annotation[] annotations, String where) {
            for (Annotation annotation : annotations) {
                assertThat(FORBIDDEN_ANNOTATIONS)
                        .as("%s is annotated @%s", where, annotation.annotationType().getSimpleName())
                        .doesNotContain(annotation.annotationType().getSimpleName());
            }
        }

        @Test
        @DisplayName("No field name suggests a row version - optimistic locking stays where COBOL put it")
        void noFieldNameSuggestsARowVersion() {
            for (Field field : authoredFields(AccountRecord.class)) {
                String lower = field.getName().toLowerCase(Locale.ROOT);
                assertThat(lower)
                        .as("field %s", field.getName())
                        .doesNotContain("version")
                        .doesNotContain("rowver")
                        .doesNotContain("optlock")
                        .doesNotContain("etag");
            }
        }

        @Test
        @DisplayName("No constant holds a DDL statement; the dataset is never created, only read")
        void noConstantHoldsDdl() throws IllegalAccessException {
            for (Field field : authoredFields(AccountRecord.class)) {
                if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                    field.setAccessible(true);
                    String value = (String) field.get(null);
                    String upper = value == null ? "" : value.toUpperCase(Locale.ROOT);
                    for (String ddl : FORBIDDEN_DDL) {
                        assertThat(upper)
                                .as("constant %s must not hold DDL", field.getName())
                                .doesNotContain(ddl);
                    }
                }
            }
        }

        @Test
        @DisplayName("No constant embeds a dataset name; those live in application.yml (G46)")
        void noConstantEmbedsADatasetName() throws IllegalAccessException {
            for (Field field : authoredFields(AccountRecord.class)) {
                if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                    field.setAccessible(true);
                    String value = (String) field.get(null);
                    assertThat(value == null ? "" : value.toUpperCase(Locale.ROOT))
                            .as("constant %s must not name a dataset", field.getName())
                            .doesNotContain("AWS.M2.CARDDEMO");
                }
            }
        }

        @Test
        @DisplayName("Every static field on the type under test is final (G53)")
        void everyStaticFieldOnTheTypeIsFinal() {
            for (Field field : authoredFields(AccountRecord.class)) {
                if (Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("static field %s must be final - COBOL WORKING-STORAGE must never "
                                    + "become shared mutable Java state", field.getName())
                            .isTrue();
                }
            }
        }

        @Test
        @DisplayName("Every static field on the type under test is of a deeply immutable type (G53)")
        void everyStaticFieldOnTheTypeIsImmutable() {
            for (Field field : authoredFields(AccountRecord.class)) {
                if (Modifier.isStatic(field.getModifiers())) {
                    assertThat(isImmutableType(field.getType()))
                            .as("static field %s is of type %s, which is not provably immutable; a "
                                    + "mutable constant would be shared between every record",
                                    field.getName(), field.getType().getName())
                            .isTrue();
                }
            }
        }

        @Test
        @DisplayName("This test class itself declares no static mutable field (G53)")
        void thisTestClassDeclaresNoStaticMutableField() {
            assertStaticStateIsImmutable(AccountRecordTest.class);
            for (Class<?> nested : AccountRecordTest.class.getDeclaredClasses()) {
                assertStaticStateIsImmutable(nested);
            }
            assertThat(AccountRecordTest.class.getDeclaredClasses())
                    .as("the thirteen nested groups this sweep covers, one per area of the contract")
                    .hasSize(13);
        }

        private void assertStaticStateIsImmutable(Class<?> type) {
            for (Field field : authoredFields(type)) {
                if (Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("%s.%s must be final", type.getSimpleName(), field.getName())
                            .isTrue();
                    assertThat(isImmutableType(field.getType()))
                            .as("%s.%s is of type %s, which is not provably immutable",
                                    type.getSimpleName(), field.getName(), field.getType().getName())
                            .isTrue();
                }
            }
        }

        private boolean isImmutableType(Class<?> type) {
            if (type.isArray()) {
                return false;
            }
            if (type.isPrimitive() || type.isEnum() || type.isRecord()) {
                return true;
            }
            return type == String.class
                    || type == BigDecimal.class
                    || type == RoundingMode.class
                    || Charset.class.isAssignableFrom(type)
                    || type == List.class;
        }

        @Test
        @DisplayName("An array constant would be rejected by the immutability rule, final or not")
        void theImmutabilityRuleRejectsArrays() {
            assertThat(isImmutableType(byte[].class)).isFalse();
            assertThat(isImmutableType(String[].class)).isFalse();
            assertThat(isImmutableType(String.class)).isTrue();
            assertThat(isImmutableType(int.class)).isTrue();
            assertThat(isImmutableType(BigDecimal.class)).isTrue();
            assertThat(isImmutableType(RoundingMode.class)).isTrue();
            assertThat(isImmutableType(PictureKind.class)).as("an enum").isTrue();
            assertThat(isImmutableType(FieldSpan.class)).as("a record").isTrue();
            assertThat(isImmutableType(RecordLayout.class)).as("a record").isTrue();
            assertThat(isImmutableType(StringBuilder.class)).isFalse();
        }

        @Test
        @DisplayName("Collaborators are constructor-supplied; nothing is injected into a static hook")
        void collaboratorsAreConstructorSupplied() {
            assertThat(new AccountRecord(ASCII).charset()).isEqualTo(ASCII);
            assertThat(declaredMethods())
                    .extracting(Method::getName)
                    .doesNotContain("setCharset")
                    .contains("charset");
        }
    }

    @Nested
    @DisplayName("PRESERVED DEFECT: the record declares ACCT-ADDR-ZIP; CBACT01C never displays it")
    class NeverDisplayedZip {
        @Test
        @DisplayName("ACCT-ADDR-ZIP exists and decodes correctly at offset 102")
        void addrZipExistsAndDecodesAtItsOffset() {
            AccountRecord row1 = decodedRow1();

            assertThat(AccountRecord.ACCT_ADDR_ZIP_OFFSET).isEqualTo(102);
            assertThat(AccountRecord.ACCT_ADDR_ZIP_LENGTH).isEqualTo(10);
            assertThat(row1.getAcctAddrZip())
                    .isEqualTo(FIXTURE_ADDR_ZIP)
                    .isEqualTo(fixtureRow1().substring(102, 112));
            assertThat(row1.rawAcctAddrZip()).isEqualTo(FIXTURE_ADDR_ZIP);
        }

        @Test
        @DisplayName("The field is writable and readable like any other, despite having no consumer")
        void addrZipBehavesLikeAnyOtherCharacterField() {
            AccountRecord record = new AccountRecord(ASCII);

            assertThat(record.getAcctAddrZip())
                    .as("a fresh record's zip is ten spaces, like every other PIC X field")
                    .isEqualTo("          ");

            record.setAcctAddrZip("12345");
            assertThat(record.getAcctAddrZip()).isEqualTo("12345     ");
            assertThat(record.toFixedWidthString())
                    .as("writing it must not disturb the record width")
                    .hasSize(AccountRecord.RECORD_LENGTH);
            assertThat(record.getAcctGroupId())
                    .as("nor the neighbouring field at offset 112")
                    .isEqualTo("          ");
        }

        @Test
        @DisplayName("Dropping the never-displayed field would shorten the record to 290 bytes")
        void droppingTheNeverDisplayedFieldWouldBreakTheRecord() {
            assertThat(AccountRecord.RECORD_LENGTH - AccountRecord.ACCT_ADDR_ZIP_LENGTH)
                    .as("290, not 300 - which is why an unused field is still declared")
                    .isEqualTo(290);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(AccountRecord.RECORD_LENGTH,
                            AccountRecord.SPAN_ACCT_ID,
                            AccountRecord.SPAN_ACCT_ACTIVE_STATUS,
                            AccountRecord.SPAN_ACCT_CURR_BAL,
                            AccountRecord.SPAN_ACCT_CREDIT_LIMIT,
                            AccountRecord.SPAN_ACCT_CASH_CREDIT_LIMIT,
                            AccountRecord.SPAN_ACCT_OPEN_DATE,
                            AccountRecord.SPAN_ACCT_EXPIRAION_DATE,
                            AccountRecord.SPAN_ACCT_REISSUE_DATE,
                            AccountRecord.SPAN_ACCT_CURR_CYC_CREDIT,
                            AccountRecord.SPAN_ACCT_CURR_CYC_DEBIT,
                            AccountRecord.SPAN_ACCT_GROUP_ID,
                            AccountRecord.SPAN_FILLER))
                    .as("the ten bytes the zip occupied become an undeclared gap")
                    .withMessageContaining("gap of 10 byte(s)");
        }
    }

}
