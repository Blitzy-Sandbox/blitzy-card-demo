package com.vsergeychik.carddemo.common;

import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FillerHandling;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.common.FixedWidthRecord.ValueHandling;
import com.vsergeychik.carddemo.common.FixedWidthRecord.ZonedSign;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link FixedWidthRecord}, the byte-span layer beneath the fixed-width codec.
 */
@DisplayName("FixedWidthRecord - the COBOL record area as an offset-addressed byte span")
class FixedWidthRecordTest {
    private static final Charset ASCII = Charset.forName("US-ASCII");
    private static final Charset EBCDIC = Charset.forName("IBM037");

    private static final byte ASCII_SPACE = 0x20;
    private static final byte ASCII_ZERO = 0x30;
    private static final byte EBCDIC_SPACE = 0x40;
    private static final byte EBCDIC_ZERO = (byte) 0xF0;

    private static final int TRAN_CAT_KEY_WIDTH = 17;

    private static final int DIS_GROUP_KEY_WIDTH = 16;

    private static RecordLayout accountRecordLayout() {
        return RecordLayout.of(300,
                FieldSpan.unsignedNumeric("ACCT-ID", 0, 11),
                FieldSpan.alphanumeric("ACCT-ACTIVE-STATUS", 11, 1),
                FieldSpan.signedScaled("ACCT-CURR-BAL", 12, 10, 2),
                FieldSpan.signedScaled("ACCT-CREDIT-LIMIT", 24, 10, 2),
                FieldSpan.signedScaled("ACCT-CASH-CREDIT-LIMIT", 36, 10, 2),
                FieldSpan.alphanumeric("ACCT-OPEN-DATE", 48, 10),
                FieldSpan.alphanumeric("ACCT-EXPIRAION-DATE", 58, 10),
                FieldSpan.alphanumeric("ACCT-REISSUE-DATE", 68, 10),
                FieldSpan.signedScaled("ACCT-CURR-CYC-CREDIT", 78, 10, 2),
                FieldSpan.signedScaled("ACCT-CURR-CYC-DEBIT", 90, 10, 2),
                FieldSpan.alphanumeric("ACCT-ADDR-ZIP", 102, 10),
                FieldSpan.alphanumeric("ACCT-GROUP-ID", 112, 10),
                FieldSpan.filler(122, 178));
    }

    private static RecordLayout tranRecordLayout() {
        return RecordLayout.of(350,
                FieldSpan.alphanumeric("TRAN-ID", 0, 16),
                FieldSpan.alphanumeric("TRAN-TYPE-CD", 16, 2),
                FieldSpan.unsignedNumeric("TRAN-CAT-CD", 18, 4),
                FieldSpan.alphanumeric("TRAN-SOURCE", 22, 10),
                FieldSpan.alphanumeric("TRAN-DESC", 32, 100),
                FieldSpan.signedScaled("TRAN-AMT", 132, 9, 2),
                FieldSpan.unsignedNumeric("TRAN-MERCHANT-ID", 143, 9),
                FieldSpan.alphanumeric("TRAN-MERCHANT-NAME", 152, 50),
                FieldSpan.alphanumeric("TRAN-MERCHANT-CITY", 202, 50),
                FieldSpan.alphanumeric("TRAN-MERCHANT-ZIP", 252, 10),
                FieldSpan.alphanumeric("TRAN-CARD-NUM", 262, 16),
                FieldSpan.alphanumeric("TRAN-ORIG-TS", 278, 26),
                FieldSpan.alphanumeric("TRAN-PROC-TS", 304, 26),
                FieldSpan.filler(330, 20));
    }

    private static RecordLayout cardRecordLayout() {
        return RecordLayout.of(150,
                FieldSpan.alphanumeric("CARD-NUM", 0, 16),
                FieldSpan.unsignedNumeric("CARD-ACCT-ID", 16, 11),
                FieldSpan.unsignedNumeric("CARD-CVV-CD", 27, 3),
                FieldSpan.alphanumeric("CARD-EMBOSSED-NAME", 30, 50),
                FieldSpan.alphanumeric("CARD-EXPIRAION-DATE", 80, 10),
                FieldSpan.alphanumeric("CARD-ACTIVE-STATUS", 90, 1),
                FieldSpan.filler(91, 59));
    }

    private static RecordLayout cardXrefLayout() {
        return RecordLayout.of(50,
                FieldSpan.alphanumeric("XREF-CARD-NUM", 0, 16),
                FieldSpan.unsignedNumeric("XREF-CUST-ID", 16, 9),
                FieldSpan.unsignedNumeric("XREF-ACCT-ID", 25, 11),
                FieldSpan.filler(36, 14));
    }

    private static RecordLayout tranCatBalLayout() {
        return RecordLayout.of(50,
                FieldSpan.unsignedNumeric("TRANCAT-ACCT-ID", 0, 11),
                FieldSpan.alphanumeric("TRANCAT-TYPE-CD", 11, 2),
                FieldSpan.unsignedNumeric("TRANCAT-CD", 13, 4),
                FieldSpan.signedScaled("TRAN-CAT-BAL", 17, 9, 2),
                FieldSpan.filler(28, 22));
    }

    private static RecordLayout disclosureGroupLayout() {
        return RecordLayout.of(50,
                FieldSpan.alphanumeric("DIS-ACCT-GROUP-ID", 0, 10),
                FieldSpan.alphanumeric("DIS-TRAN-TYPE-CD", 10, 2),
                FieldSpan.unsignedNumeric("DIS-TRAN-CAT-CD", 12, 4),
                FieldSpan.signedScaled("DIS-INT-RATE", 16, 4, 2),
                FieldSpan.filler(22, 28));
    }

    private static RecordLayout customerRecordLayout() {
        return RecordLayout.of(500,
                FieldSpan.unsignedNumeric("CUST-ID", 0, 9),
                FieldSpan.alphanumeric("CUST-FIRST-NAME", 9, 25),
                FieldSpan.alphanumeric("CUST-MIDDLE-NAME", 34, 25),
                FieldSpan.alphanumeric("CUST-LAST-NAME", 59, 25),
                FieldSpan.alphanumeric("CUST-ADDR-LINE-1", 84, 50),
                FieldSpan.alphanumeric("CUST-ADDR-LINE-2", 134, 50),
                FieldSpan.alphanumeric("CUST-ADDR-LINE-3", 184, 50),
                FieldSpan.alphanumeric("CUST-ADDR-STATE-CD", 234, 2),
                FieldSpan.alphanumeric("CUST-ADDR-COUNTRY-CD", 236, 3),
                FieldSpan.alphanumeric("CUST-ADDR-ZIP", 239, 10),
                FieldSpan.alphanumeric("CUST-PHONE-NUM-1", 249, 15),
                FieldSpan.alphanumeric("CUST-PHONE-NUM-2", 264, 15),
                FieldSpan.unsignedNumeric("CUST-SSN", 279, 9),
                FieldSpan.alphanumeric("CUST-GOVT-ISSUED-ID", 288, 20),
                FieldSpan.alphanumeric("CUST-DOB-YYYY-MM-DD", 308, 10),
                FieldSpan.alphanumeric("CUST-EFT-ACCOUNT-ID", 318, 10),
                FieldSpan.alphanumeric("CUST-PRI-CARD-HOLDER-IND", 328, 1),
                FieldSpan.unsignedNumeric("CUST-FICO-CREDIT-SCORE", 329, 3),
                FieldSpan.filler(332, 168));
    }

    private static RecordLayout curdateMmDdYyLayout() {
        return RecordLayout.of(8,
                FieldSpan.unsignedNumeric("WS-CURDATE-MM", 0, 2),
                FieldSpan.filler(2, 1, "/"),
                FieldSpan.unsignedNumeric("WS-CURDATE-DD", 3, 2),
                FieldSpan.filler(5, 1, "/"),
                FieldSpan.unsignedNumeric("WS-CURDATE-YY", 6, 2));
    }

    private static RecordLayout dateTimeLayout() {
        return RecordLayout.of(58,
                FieldSpan.unsignedNumeric("WS-CURDATE-YEAR", 0, 4),
                FieldSpan.unsignedNumeric("WS-CURDATE-MONTH", 4, 2),
                FieldSpan.unsignedNumeric("WS-CURDATE-DAY", 6, 2),
                FieldSpan.redefining("WS-CURDATE-N", 0, 8, PictureKind.UNSIGNED_NUMERIC),
                FieldSpan.unsignedNumeric("WS-CURTIME-HOURS", 8, 2),
                FieldSpan.unsignedNumeric("WS-CURTIME-MINUTE", 10, 2),
                FieldSpan.unsignedNumeric("WS-CURTIME-SECOND", 12, 2),
                FieldSpan.unsignedNumeric("WS-CURTIME-MILSEC", 14, 2),
                FieldSpan.redefining("WS-CURTIME-N", 8, 8, PictureKind.UNSIGNED_NUMERIC),
                FieldSpan.unsignedNumeric("WS-CURDATE-MM", 16, 2),
                FieldSpan.filler(18, 1, "/"),
                FieldSpan.unsignedNumeric("WS-CURDATE-DD", 19, 2),
                FieldSpan.filler(21, 1, "/"),
                FieldSpan.unsignedNumeric("WS-CURDATE-YY", 22, 2),
                FieldSpan.unsignedNumeric("WS-CURTIME-HH", 24, 2),
                FieldSpan.filler(26, 1, ":"),
                FieldSpan.unsignedNumeric("WS-CURTIME-MM", 27, 2),
                FieldSpan.filler(29, 1, ":"),
                FieldSpan.unsignedNumeric("WS-CURTIME-SS", 30, 2),
                FieldSpan.unsignedNumeric("WS-TIMESTAMP-DT-YYYY", 32, 4),
                FieldSpan.filler(36, 1, "-"),
                FieldSpan.unsignedNumeric("WS-TIMESTAMP-DT-MM", 37, 2),
                FieldSpan.filler(39, 1, "-"),
                FieldSpan.unsignedNumeric("WS-TIMESTAMP-DT-DD", 40, 2),
                FieldSpan.filler(42, 1, " "),
                FieldSpan.unsignedNumeric("WS-TIMESTAMP-TM-HH", 43, 2),
                FieldSpan.filler(45, 1, ":"),
                FieldSpan.unsignedNumeric("WS-TIMESTAMP-TM-MM", 46, 2),
                FieldSpan.filler(48, 1, ":"),
                FieldSpan.unsignedNumeric("WS-TIMESTAMP-TM-SS", 49, 2),
                FieldSpan.filler(51, 1, "."),
                FieldSpan.unsignedNumeric("WS-TIMESTAMP-TM-MS6", 52, 6));
    }

    private static RecordLayout ccWorkAreaLayout() {
        FieldSpan acctId = FieldSpan.alphanumeric("CC-ACCT-ID", 177, 11).withInitialValue(" ");
        FieldSpan cardNum = FieldSpan.alphanumeric("CC-CARD-NUM", 188, 16).withInitialValue(" ");
        FieldSpan custId = FieldSpan.alphanumeric("CC-CUST-ID", 204, 9).withInitialValue(" ");
        return RecordLayout.of(213,
                FieldSpan.alphanumeric("CCARD-AID", 0, 5),
                FieldSpan.alphanumeric("CCARD-NEXT-PROG", 5, 8),
                FieldSpan.alphanumeric("CCARD-NEXT-MAPSET", 13, 7),
                FieldSpan.alphanumeric("CCARD-NEXT-MAP", 20, 7),
                FieldSpan.alphanumeric("CCARD-ERROR-MSG", 27, 75),
                FieldSpan.alphanumeric("CCARD-RETURN-MSG", 102, 75),
                acctId,
                acctId.redefinedAs("CC-ACCT-ID-N", PictureKind.UNSIGNED_NUMERIC),
                cardNum,
                cardNum.redefinedAs("CC-CARD-NUM-N", PictureKind.UNSIGNED_NUMERIC),
                custId,
                custId.redefinedAs("CC-CUST-ID-N", PictureKind.UNSIGNED_NUMERIC));
    }

    private static RecordLayout layoutFor(String copybook) {
        return switch (copybook) {
            case "CVACT01Y" -> accountRecordLayout();
            case "CVACT02Y" -> cardRecordLayout();
            case "CVACT03Y" -> cardXrefLayout();
            case "CVCUS01Y" -> customerRecordLayout();
            case "CVTRA01Y" -> tranCatBalLayout();
            case "CVTRA02Y" -> disclosureGroupLayout();
            case "CVTRA05Y" -> tranRecordLayout();
            default -> throw new IllegalArgumentException("No layout is transcribed for copybook '"
                    + copybook + "'; add it beside the others before naming it in a parameter row");
        };
    }

    @Nested
    @DisplayName("Layout self-check - gates G19 and G21 as a run-time assertion")
    class LayoutSelfCheck {
        @DisplayName("every copybook layout sums to exactly its documented RECLN")
        @ParameterizedTest(name = "{0} {1} totals {2} bytes")
        @CsvSource({
                "CVACT01Y, ACCOUNT-RECORD,      300",
                "CVTRA05Y, TRAN-RECORD,         350",
                "CVACT02Y, CARD-RECORD,         150",
                "CVACT03Y, CARD-XREF-RECORD,     50",
                "CVTRA01Y, TRAN-CAT-BAL-RECORD,  50",
                "CVTRA02Y, DIS-GROUP-RECORD,     50",
                "CVCUS01Y, CUSTOMER-RECORD,     500"
        })
        void copybookLayoutsTotalTheirDeclaredRecordLength(String copybook, String record,
                                                           int expected) {
            RecordLayout layout = layoutFor(copybook);

            assertThat(record).as("the copybook's 01-level record name").isNotBlank();

            int summed = 0;
            for (FieldSpan span : layout.storageSpans()) {
                summed += span.length();
            }

            assertThat(layout.recordLength())
                    .as("%s declared record length", copybook)
                    .isEqualTo(expected);
            assertThat(summed)
                    .as("%s storage spans must sum to the declared length", copybook)
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("CVACT01Y offsets match the copybook field for field, FILLER included")
        void accountLayoutOffsetsMatchTheCopybook() {
            RecordLayout layout = accountRecordLayout();

            assertThat(layout.span("ACCT-ID").offset()).isZero();
            assertThat(layout.span("ACCT-ACTIVE-STATUS").offset()).isEqualTo(11);
            assertThat(layout.span("ACCT-CURR-BAL").offset()).isEqualTo(12);
            assertThat(layout.span("ACCT-CREDIT-LIMIT").offset()).isEqualTo(24);
            assertThat(layout.span("ACCT-CASH-CREDIT-LIMIT").offset()).isEqualTo(36);
            assertThat(layout.span("ACCT-OPEN-DATE").offset()).isEqualTo(48);
            assertThat(layout.span("ACCT-EXPIRAION-DATE").offset()).isEqualTo(58);
            assertThat(layout.span("ACCT-REISSUE-DATE").offset()).isEqualTo(68);
            assertThat(layout.span("ACCT-CURR-CYC-CREDIT").offset()).isEqualTo(78);
            assertThat(layout.span("ACCT-CURR-CYC-DEBIT").offset()).isEqualTo(90);
            assertThat(layout.span("ACCT-ADDR-ZIP").offset()).isEqualTo(102);
            assertThat(layout.span("ACCT-GROUP-ID").offset()).isEqualTo(112);

            List<FieldSpan> spans = layout.storageSpans();
            FieldSpan trailingFiller = spans.get(spans.size() - 1);
            assertThat(trailingFiller.name()).isEqualTo("FILLER");
            assertThat(trailingFiller.offset()).isEqualTo(122);
            assertThat(trailingFiller.length()).isEqualTo(178);
            assertThat(trailingFiller.endOffsetExclusive()).isEqualTo(300);
        }

        @Test
        @DisplayName("the two 50-byte records have keys of 17 and 16 bytes - assert both, always")
        void theTwoFiftyByteRecordsHaveDifferentKeyWidths() {
            RecordLayout tranCatBal = tranCatBalLayout();
            RecordLayout disclosureGroup = disclosureGroupLayout();

            assertThat(tranCatBal.span("TRANCAT-ACCT-ID").offset()).isZero();
            assertThat(tranCatBal.span("TRANCAT-ACCT-ID").length()).isEqualTo(11);
            assertThat(tranCatBal.span("TRANCAT-TYPE-CD").offset()).isEqualTo(11);
            assertThat(tranCatBal.span("TRANCAT-TYPE-CD").length()).isEqualTo(2);
            assertThat(tranCatBal.span("TRANCAT-CD").offset()).isEqualTo(13);
            assertThat(tranCatBal.span("TRANCAT-CD").length()).isEqualTo(4);
            assertThat(tranCatBal.span("TRANCAT-CD").endOffsetExclusive())
                    .as("TRAN-CAT-KEY is 11 + 2 + 4 bytes of composite key")
                    .isEqualTo(TRAN_CAT_KEY_WIDTH)
                    .isEqualTo(17);
            assertThat(tranCatBal.span("TRAN-CAT-BAL").offset())
                    .as("the balance therefore starts one byte later than in CVTRA02Y")
                    .isEqualTo(17);
            assertThat(tranCatBal.span("TRAN-CAT-BAL").length())
                    .as("S9(09)V99 is 11 bytes; the sign is overpunched, not stored separately")
                    .isEqualTo(11);

            assertThat(disclosureGroup.span("DIS-ACCT-GROUP-ID").offset()).isZero();
            assertThat(disclosureGroup.span("DIS-ACCT-GROUP-ID").length()).isEqualTo(10);
            assertThat(disclosureGroup.span("DIS-TRAN-TYPE-CD").offset()).isEqualTo(10);
            assertThat(disclosureGroup.span("DIS-TRAN-TYPE-CD").length()).isEqualTo(2);
            assertThat(disclosureGroup.span("DIS-TRAN-CAT-CD").offset()).isEqualTo(12);
            assertThat(disclosureGroup.span("DIS-TRAN-CAT-CD").length()).isEqualTo(4);
            assertThat(disclosureGroup.span("DIS-TRAN-CAT-CD").endOffsetExclusive())
                    .as("DIS-GROUP-KEY is 10 + 2 + 4 = 16 bytes, one byte narrower")
                    .isEqualTo(DIS_GROUP_KEY_WIDTH)
                    .isEqualTo(16);
            assertThat(disclosureGroup.span("DIS-INT-RATE").offset()).isEqualTo(16);
            assertThat(disclosureGroup.span("DIS-INT-RATE").length())
                    .as("S9(04)V99 is 6 bytes")
                    .isEqualTo(6);

            assertThat(TRAN_CAT_KEY_WIDTH - DIS_GROUP_KEY_WIDTH)
                    .as("the keys differ by exactly one byte")
                    .isOne();
            assertThat(tranCatBal.recordLength())
                    .as("while the two parent records are identical in width")
                    .isEqualTo(disclosureGroup.recordLength())
                    .isEqualTo(50);
        }

        @Test
        @DisplayName("a 17-byte DIS-GROUP-KEY is caught even though both records are 50 bytes")
        void theSeventeenForSixteenKeySlipIsCaughtByTheTotal() {
            List<FieldSpan> seventeenByteKey = List.of(
                    FieldSpan.alphanumeric("DIS-ACCT-GROUP-ID", 0, 11),
                    FieldSpan.alphanumeric("DIS-TRAN-TYPE-CD", 11, 2),
                    FieldSpan.unsignedNumeric("DIS-TRAN-CAT-CD", 13, 4),
                    FieldSpan.signedScaled("DIS-INT-RATE", 17, 4, 2),
                    FieldSpan.filler(23, 28));

            int summed = 0;
            for (FieldSpan span : seventeenByteKey) {
                summed += span.length();
            }
            assertThat(summed).as("11 + 2 + 4 + 6 + 28").isEqualTo(51);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new RecordLayout(50, seventeenByteKey))
                    .withMessageContaining("declares 51 byte(s)")
                    .withMessageContaining("record length is 50")
                    .withMessageContaining("1 byte(s) too long");
        }

        @Test
        @DisplayName("CSDAT01Y declares ten FILLERs in 58 bytes with no name collision")
        void csdat01yDeclaresTenFillersWithoutCollision() {
            RecordLayout layout = dateTimeLayout();

            assertThat(layout.recordLength()).isEqualTo(58);
            assertThat(layout.storageSpans()).as("20 named items plus 10 separator FILLERs")
                    .hasSize(30);
            assertThat(layout.redefinitions()).as("WS-CURDATE-N and WS-CURTIME-N")
                    .hasSize(2);
            assertThat(layout.hasSpan("FILLER"))
                    .as("FILLER is a reserved span, never a referable field")
                    .isFalse();

            List<FieldSpan> fillers = new ArrayList<>();
            int summed = 0;
            for (FieldSpan span : layout.storageSpans()) {
                summed += span.length();
                if (span.kind().filler()) {
                    fillers.add(span);
                }
            }

            assertThat(summed).as("gate G19: the storage spans sum to the group's 58 bytes")
                    .isEqualTo(58);
            assertThat(fillers).hasSize(10).allMatch(FieldSpan::hasInitialValue);

            FixedWidthRecord record = layout.newRecord(ASCII);
            StringBuilder separators = new StringBuilder();
            for (FieldSpan filler : fillers) {
                assertThat(filler.length()).as("every CSDAT01Y separator is PIC X(01)").isOne();
                assertThat(record.readSpan(filler))
                        .as("the reserved range at offset %d holds its declared literal",
                                filler.offset())
                        .isEqualTo(filler.initialValue())
                        .isEqualTo(record.readString(filler.offset(), filler.length()));
                separators.append(filler.initialValue());
            }
            assertThat(separators.toString())
                    .as("the separators in declaration order: two slashes, two colons, then the "
                            + "timestamp's dashes, blank, colons and point")
                    .isEqualTo("//::-- ::.");
        }

        @Test
        @DisplayName("gate G21: dropping the trailing FILLER X(178) fails and names the shortfall")
        void droppingTheTrailingFillerIsRejected() {
            List<FieldSpan> withoutFiller = new ArrayList<>(accountRecordLayout().storageSpans());
            withoutFiller.remove(withoutFiller.size() - 1);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new RecordLayout(300, withoutFiller))
                    .withMessageContaining("declares 122 byte(s)")
                    .withMessageContaining("record length is 300")
                    .withMessageContaining("178 byte(s) short")
                    .withMessageContaining("dropped trailing FILLER");
        }

        @Test
        @DisplayName("gate G19: reserving a sign byte on the five S9(10)V99 fields makes 305 and fails")
        void reservingASignByteForEverySignedFieldIsRejected() {
            List<FieldSpan> withSignBytes = List.of(
                    FieldSpan.unsignedNumeric("ACCT-ID", 0, 11),
                    FieldSpan.alphanumeric("ACCT-ACTIVE-STATUS", 11, 1),
                    new FieldSpan("ACCT-CURR-BAL", 12, 13, PictureKind.SIGNED_SCALED, null, false),
                    new FieldSpan("ACCT-CREDIT-LIMIT", 25, 13, PictureKind.SIGNED_SCALED, null, false),
                    new FieldSpan("ACCT-CASH-CREDIT-LIMIT", 38, 13, PictureKind.SIGNED_SCALED, null,
                            false),
                    FieldSpan.alphanumeric("ACCT-OPEN-DATE", 51, 10),
                    FieldSpan.alphanumeric("ACCT-EXPIRAION-DATE", 61, 10),
                    FieldSpan.alphanumeric("ACCT-REISSUE-DATE", 71, 10),
                    new FieldSpan("ACCT-CURR-CYC-CREDIT", 81, 13, PictureKind.SIGNED_SCALED, null,
                            false),
                    new FieldSpan("ACCT-CURR-CYC-DEBIT", 94, 13, PictureKind.SIGNED_SCALED, null,
                            false),
                    FieldSpan.alphanumeric("ACCT-ADDR-ZIP", 107, 10),
                    FieldSpan.alphanumeric("ACCT-GROUP-ID", 117, 10),
                    FieldSpan.filler(127, 178));

            int summed = 0;
            for (FieldSpan span : withSignBytes) {
                summed += span.length();
            }
            assertThat(summed).as("a reserved sign byte on each of the five signed fields")
                    .isEqualTo(305);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new RecordLayout(300, withSignBytes))
                    .withMessageContaining("declares 305 byte(s)")
                    .withMessageContaining("5 byte(s) too long")
                    .withMessageContaining("sign byte reserved for a PIC S9 field");
        }

        @Test
        @DisplayName("signedScaled derives p + s, so no sign byte can be reintroduced by hand")
        void signedScaledWidthIsTheSumOfTheDigitCounts() {
            assertThat(FieldSpan.signedScaled("ACCT-CURR-BAL", 12, 10, 2).length()).isEqualTo(12);
            assertThat(FieldSpan.signedScaled("TRAN-AMT", 132, 9, 2).length()).isEqualTo(11);
            assertThat(FieldSpan.signedScaled("DIS-INT-RATE", 0, 4, 2).length()).isEqualTo(6);
            assertThat(FieldSpan.signedScaled("SCALELESS", 0, 5, 0).length()).isEqualTo(5);
        }

        @Test
        @DisplayName("an overlapping non-REDEFINES pair is rejected")
        void overlappingStorageSpansAreRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(20,
                            FieldSpan.alphanumeric("FIRST", 0, 12),
                            FieldSpan.alphanumeric("SECOND", 8, 12)))
                    .withMessageContaining("Layout overlap at SECOND")
                    .withMessageContaining("overlaps 4 byte(s)")
                    .withMessageContaining("REDEFINES");
        }

        @Test
        @DisplayName("a gap between descriptors is rejected and must be declared as FILLER")
        void gapsBetweenStorageSpansAreRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(20,
                            FieldSpan.alphanumeric("FIRST", 0, 8),
                            FieldSpan.alphanumeric("SECOND", 12, 8)))
                    .withMessageContaining("Layout gap of 4 byte(s) before SECOND")
                    .withMessageContaining("declared as FILLER");
        }

        @Test
        @DisplayName("a layout that starts after offset 0 is a gap, not an implicit prefix")
        void aLayoutMustStartAtOffsetZero() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(10, FieldSpan.alphanumeric("ONLY", 2, 8)))
                    .withMessageContaining("Layout gap of 2 byte(s) before ONLY");
        }

        @Test
        @DisplayName("a repeated referable name is rejected, but FILLER may repeat freely")
        void duplicateReferableNamesAreRejectedWhileFillerMayRepeat() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(8,
                            FieldSpan.alphanumeric("SAME", 0, 4),
                            FieldSpan.alphanumeric("SAME", 4, 4)))
                    .withMessageContaining("more than once")
                    .withMessageContaining("only FILLER may repeat");

            RecordLayout manyFillers = curdateMmDdYyLayout();
            assertThat(manyFillers.storageSpans()).hasSize(5);
        }

        @Test
        @DisplayName("an empty span list and a non-positive record length are both rejected")
        void degenerateLayoutsAreRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new RecordLayout(10, List.of()))
                    .withMessageContaining("declares no spans");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(0, FieldSpan.alphanumeric("ONLY", 0, 1)))
                    .withMessageContaining("not a valid record width");

            assertThatNullPointerException()
                    .isThrownBy(() -> new RecordLayout(10, null))
                    .withMessageContaining("requires its span list");
            assertThatNullPointerException()
                    .isThrownBy(() -> RecordLayout.of(10, (FieldSpan[]) null))
                    .withMessageContaining("requires its span list");
        }

        @Test
        @DisplayName("span lookup finds referable names, skips FILLER and reports the unknown")
        void spanLookupBehaviour() {
            RecordLayout layout = cardXrefLayout();

            assertThat(layout.hasSpan("XREF-ACCT-ID")).isTrue();
            assertThat(layout.span("XREF-ACCT-ID").length()).isEqualTo(11);
            assertThat(layout.hasSpan("FILLER")).as("FILLER is not a referable COBOL name").isFalse();
            assertThat(layout.hasSpan("xref-acct-id")).as("names are case-sensitive").isFalse();

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> layout.span("NO-SUCH-FIELD"))
                    .withMessageContaining("declares no field named 'NO-SUCH-FIELD'")
                    .withMessageContaining("FILLER is not referable");

            assertThatNullPointerException().isThrownBy(() -> layout.span(null));
        }

        @Test
        @DisplayName("the layout's span list is immutable and defensively copied")
        void layoutSpanListIsDefensivelyCopied() {
            List<FieldSpan> mutable = new ArrayList<>(List.of(
                    FieldSpan.alphanumeric("ONLY", 0, 4)));
            RecordLayout layout = new RecordLayout(4, mutable);

            mutable.clear();

            assertThat(layout.spans()).hasSize(1);
            assertThat(layout.storageSpans()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("FieldSpan declaration guards")
    class FieldSpanGuards {
        @Test
        @DisplayName("null name and null kind are both rejected with an explanatory message")
        void nullReferencesAreRejected() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new FieldSpan(null, 0, 1, PictureKind.ALPHANUMERIC, null, false))
                    .withMessageContaining("copybook item name verbatim");
            assertThatNullPointerException()
                    .isThrownBy(() -> new FieldSpan("NAME", 0, 1, null, null, false))
                    .withMessageContaining("PICTURE category");
            assertThatNullPointerException()
                    .isThrownBy(() -> FieldSpan.filler(0, 1, null))
                    .withMessageContaining("FILLER VALUE literal is required");
            assertThatNullPointerException()
                    .isThrownBy(() -> FieldSpan.alphanumeric("NAME", 0, 1).withInitialValue(null))
                    .withMessageContaining("VALUE literal is required");
        }

        @ParameterizedTest(name = "a blank name [{0}] is rejected")
        @ValueSource(strings = {"", " ", "\t"})
        void blankNamesAreRejected(String blank) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FieldSpan.alphanumeric(blank, 0, 1))
                    .withMessageContaining("must not be blank");
        }

        @Test
        @DisplayName("a negative offset and a length below one are rejected")
        void geometryGuards() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FieldSpan.alphanumeric("NAME", -1, 4))
                    .withMessageContaining("negative offset -1");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FieldSpan.alphanumeric("NAME", 0, 0))
                    .withMessageContaining("declares length 0");
        }

        @Test
        @DisplayName("a VALUE literal wider than its span is rejected at declaration time")
        void anOverWideLiteralIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FieldSpan.filler(0, 1, "//"))
                    .withMessageContaining("VALUE literal of 2 character(s)")
                    .withMessageContaining("only 1 byte(s) wide");
        }

        @Test
        @DisplayName("signedScaled rejects p below one and a negative s")
        void signedScaledDigitCountGuards() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FieldSpan.signedScaled("NAME", 0, 0, 2))
                    .withMessageContaining("p of at least 1");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FieldSpan.signedScaled("NAME", 0, 9, -1))
                    .withMessageContaining("must not be negative");
        }

        @Test
        @DisplayName("hasInitialValue distinguishes a declared literal from none")
        void initialValuePresence() {
            assertThat(FieldSpan.filler(0, 4).hasInitialValue()).isFalse();
            assertThat(FieldSpan.filler(0, 1, "/").hasInitialValue()).isTrue();
            assertThat(FieldSpan.alphanumeric("CC-ACCT-ID", 0, 11).withInitialValue("   ")
                    .hasInitialValue()).isTrue();
        }

        @Test
        @DisplayName("describe names the field, kind, offset, length and overlay status")
        void describeIsPrecise() {
            assertThat(FieldSpan.signedScaled("ACCT-CURR-BAL", 12, 10, 2).describe())
                    .isEqualTo("ACCT-CURR-BAL (SIGNED_SCALED, offset 12, length 12)");
            assertThat(FieldSpan.alphanumeric("CC-ACCT-ID", 0, 11)
                    .redefinedAs("CC-ACCT-ID-N", PictureKind.UNSIGNED_NUMERIC).describe())
                    .isEqualTo("CC-ACCT-ID-N (UNSIGNED_NUMERIC, offset 0, length 11, REDEFINES overlay)");
        }

        @Test
        @DisplayName("PictureKind states its own alignment and filler status")
        void pictureKindProperties() {
            assertThat(PictureKind.ALPHANUMERIC.numericDisplay()).isFalse();
            assertThat(PictureKind.FILLER.numericDisplay()).isFalse();
            assertThat(PictureKind.UNSIGNED_NUMERIC.numericDisplay()).isTrue();
            assertThat(PictureKind.SIGNED_SCALED.numericDisplay()).isTrue();

            assertThat(PictureKind.ALPHANUMERIC.leftJustified()).isTrue();
            assertThat(PictureKind.UNSIGNED_NUMERIC.leftJustified()).isFalse();

            assertThat(PictureKind.FILLER.filler()).isTrue();
            assertThat(PictureKind.ALPHANUMERIC.filler()).isFalse();
            assertThat(PictureKind.values()).hasSize(4);
        }
    }

    @Nested
    @DisplayName("Construction, charset handling and the defensive copy")
    class ConstructionAndCharset {
        @Test
        @DisplayName("a fresh area is space-filled with the charset's own space byte")
        void freshAreaIsSpaceFilledPerCharset() {
            byte[] ascii = new FixedWidthRecord(50, ASCII).toByteArray();
            byte[] ebcdic = new FixedWidthRecord(50, EBCDIC).toByteArray();

            assertThat(ascii).hasSize(50).containsOnly(ASCII_SPACE);
            assertThat(ebcdic).hasSize(50).containsOnly(EBCDIC_SPACE);
        }

        @Test
        @DisplayName("the pad bytes are derived from the charset, never hard-coded")
        void padBytesComeFromTheCharset() {
            FixedWidthRecord ascii = new FixedWidthRecord(4, ASCII);
            FixedWidthRecord ebcdic = new FixedWidthRecord(4, EBCDIC);

            assertThat(ascii.spacePadByte()).isEqualTo(ASCII_SPACE);
            assertThat(ascii.zeroPadByte()).isEqualTo(ASCII_ZERO);
            assertThat(ebcdic.spacePadByte()).isEqualTo(EBCDIC_SPACE);
            assertThat(ebcdic.zeroPadByte()).isEqualTo(EBCDIC_ZERO);

            assertThat(ebcdic.padByteFor(PictureKind.ALPHANUMERIC)).isEqualTo(EBCDIC_SPACE);
            assertThat(ebcdic.padByteFor(PictureKind.FILLER)).isEqualTo(EBCDIC_SPACE);
            assertThat(ebcdic.padByteFor(PictureKind.UNSIGNED_NUMERIC)).isEqualTo(EBCDIC_ZERO);
            assertThat(ebcdic.padByteFor(PictureKind.SIGNED_SCALED)).isEqualTo(EBCDIC_ZERO);

            assertThatNullPointerException().isThrownBy(() -> ascii.padByteFor(null))
                    .withMessageContaining("PICTURE kind is required");
        }

        @Test
        @DisplayName("recordLength and charset are exposed exactly as supplied")
        void geometryAndCharsetAreExposed() {
            FixedWidthRecord record = new FixedWidthRecord(300, EBCDIC);

            assertThat(record.recordLength()).isEqualTo(300);
            assertThat(record.charset()).isEqualTo(EBCDIC);
            assertThat(record).hasToString("FixedWidthRecord[recordLength=300, charset=IBM037]");
        }

        @Test
        @DisplayName("a null charset and a non-positive record length are rejected")
        void constructionGuards() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new FixedWidthRecord(10, null))
                    .withMessageContaining("charset must be supplied explicitly");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new FixedWidthRecord(0, ASCII))
                    .withMessageContaining("not a valid record width");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new FixedWidthRecord(-1, ASCII))
                    .withMessageContaining("Record length -1")
                    .withMessageContaining("at least 1 byte");
        }

        @Test
        @DisplayName("the same span decodes differently under each charset, so the parameter is used")
        void theSameSpanDecodesDifferentlyUnderEachCharset() {
            byte[] identicalBytes = {0x40, 0x5A, 0x7B};

            FixedWidthRecord asEbcdic = FixedWidthRecord.copyOf(identicalBytes, 3, EBCDIC);
            FixedWidthRecord asAscii = FixedWidthRecord.copyOf(identicalBytes, 3, ASCII);

            assertThat(asEbcdic.readString(0, 3)).isEqualTo(" !#");
            assertThat(asAscii.readString(0, 3)).isEqualTo("@Z{");
            assertThat(asEbcdic.readString(0, 3)).isNotEqualTo(asAscii.readString(0, 3));
            assertThat(asEbcdic.toByteArray())
                    .as("the bytes are identical; only the interpretation differs")
                    .isEqualTo(asAscii.toByteArray());

            byte[] ebcdicAbc = {(byte) 0xC1, (byte) 0xC2, (byte) 0xC3};

            assertThat(FixedWidthRecord.copyOf(ebcdicAbc, 3, EBCDIC).readString(0, 3))
                    .isEqualTo("ABC");
            assertThatIllegalStateException()
                    .isThrownBy(() -> FixedWidthRecord.copyOf(ebcdicAbc, 3, ASCII).readString(0, 3))
                    .as("the very same bytes are not characters at all under US-ASCII, and are "
                            + "refused rather than decoded to replacement characters that would "
                            + "look like data")
                    .withMessageContaining("not valid code page US-ASCII data")
                    .withMessageNotContaining("\uFFFD");
        }

        @Test
        @DisplayName("a multi-byte code page is rejected: an offset-addressed area cannot use one")
        void multiByteCharsetsAreRejected() {
            Charset utf16 = Charset.forName("UTF-16");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new FixedWidthRecord(10, utf16))
                    .withMessageContaining("encodes ' ' to")
                    .withMessageContaining("exactly one byte");
        }

        @Test
        @DisplayName("copyOf round-trips the supplied bytes identically")
        void copyOfRoundTripsBytes() {
            byte[] source = "1234567890A".getBytes(ASCII);

            FixedWidthRecord record = FixedWidthRecord.copyOf(source, 11, ASCII);

            assertThat(record.toByteArray()).isEqualTo(source);
            assertThat(record.readString(0, 11)).isEqualTo("1234567890A");
        }

        @Test
        @DisplayName("copyOf rejects a row that is not exactly the declared width")
        void copyOfRejectsAWrongWidthRow() {
            byte[] shortRow = new byte[36];

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FixedWidthRecord.copyOf(shortRow, 50, ASCII))
                    .withMessageContaining("Supplied 36 byte(s)")
                    .withMessageContaining("declared as 50 byte(s)");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FixedWidthRecord.copyOf(new byte[51], 50, ASCII))
                    .withMessageContaining("Supplied 51 byte(s)");

            assertThatNullPointerException()
                    .isThrownBy(() -> FixedWidthRecord.copyOf(null, 50, ASCII))
                    .withMessageContaining("Record bytes are required");
        }

        @Test
        @DisplayName("mutating the array returned by toByteArray does not change the record")
        void toByteArrayReturnsADefensiveCopy() {
            FixedWidthRecord record = new FixedWidthRecord(4, ASCII);
            record.writeString(0, 4, "ABCD");

            byte[] exported = record.toByteArray();
            exported[0] = (byte) 'Z';

            assertThat(record.readString(0, 4)).isEqualTo("ABCD");
            assertThat(record.toByteArray()[0]).isEqualTo((byte) 'A');
        }

        @Test
        @DisplayName("mutating the array returned by readBytes does not change the record")
        void readBytesReturnsADefensiveCopy() {
            FixedWidthRecord record = new FixedWidthRecord(4, ASCII);
            record.writeString(0, 4, "ABCD");

            byte[] slice = record.readBytes(1, 2);
            assertThat(slice).containsExactly((byte) 'B', (byte) 'C');
            slice[0] = (byte) 'Z';

            assertThat(record.readString(0, 4)).isEqualTo("ABCD");
        }

        @Test
        @DisplayName("two charsets produce different bytes for the same character")
        void theCharsetIsGenuinelyHonoured() {
            FixedWidthRecord asciiRecord = new FixedWidthRecord(1, ASCII);
            FixedWidthRecord ebcdicRecord = new FixedWidthRecord(1, EBCDIC);
            asciiRecord.writeString(0, 1, "A");
            ebcdicRecord.writeString(0, 1, "A");

            byte[] asciiBytes = asciiRecord.toByteArray();
            byte[] ebcdicBytes = ebcdicRecord.toByteArray();

            assertThat(asciiBytes).as("'A' is 0x41 under US-ASCII").containsExactly((byte) 0x41);
            assertThat(ebcdicBytes).as("and 0xC1 under IBM037").containsExactly((byte) 0xC1);
            assertThat(ebcdicBytes).isNotEqualTo(asciiBytes);
        }

        @Test
        @DisplayName("two charsets produce different bytes for the same non-ASCII character")
        void theCharsetIsGenuinelyHonouredForANonAsciiCharacter() {
            String accented = "\u00e9";

            FixedWidthRecord ebcdicRecord = new FixedWidthRecord(1, EBCDIC);
            ebcdicRecord.writeString(0, 1, accented);

            assertThat(ebcdicRecord.toByteArray()).as("IBM037 maps it to a real code point")
                    .containsExactly((byte) 0x51);

            FixedWidthRecord asciiRecord = new FixedWidthRecord(1, ASCII);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> asciiRecord.writeString(0, 1, accented))
                    .withMessageContaining("US-ASCII")
                    .as("the code page is named and the refusal says the character cannot be "
                            + "represented in it - reported rather than replaced")
                    .withMessageContaining("cannot represent")
                    .satisfies(rejected -> assertThat(rejected.getMessage())
                            .as("the offending characters are withheld: an exception message reaches "
                                    + "logs and HTTP error bodies")
                            .doesNotContain(accented));

            assertThat(asciiRecord.toByteArray())
                    .as("a rejected write leaves the span at its initial pad byte, unmodified")
                    .containsExactly((byte) 0x20);
        }

        @Test
        @DisplayName("a character one code page cannot map is refused there and written in the other")
        void anUnmappableCharacterIsRefusedRatherThanSubstituted() {
            String accented = "\u00e9";

            FixedWidthRecord ebcdicRecord = new FixedWidthRecord(1, EBCDIC);
            ebcdicRecord.writeString(0, 1, accented);

            assertThat(ebcdicRecord.toByteArray()).as("IBM037 maps it to a real code point")
                    .containsExactly((byte) 0x51);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new FixedWidthRecord(1, ASCII).writeString(0, 1, accented))
                    .as("US-ASCII cannot map it. Substituting '?' - which is what "
                            + "String.getBytes(Charset) does - would write a value into the dataset "
                            + "that no COBOL program could have produced, and it would then be "
                            + "indistinguishable from a genuine question mark for ever after")
                    .withMessageContaining("cannot represent");
        }
    }

    @Nested
    @DisplayName("Raw span primitives and their bounds")
    class RawPrimitives {
        @Test
        @DisplayName("a short value is left justified and space-padded to the full span")
        void writeStringPadsOnTheRight() {
            FixedWidthRecord record = new FixedWidthRecord(10, ASCII);

            record.writeString(0, 10, "AB");

            assertThat(record.readString(0, 10))
                    .as("padding is part of a PIC X value and is never trimmed away")
                    .isEqualTo("AB        ")
                    .hasSize(10);
        }

        @Test
        @DisplayName("the explicit form right justifies and zero-pads for a numeric span")
        void writeStringCanRightJustify() {
            FixedWidthRecord record = new FixedWidthRecord(11, ASCII);

            record.writeString(0, 11, "42", false, record.zeroPadByte());

            assertThat(record.readString(0, 11)).isEqualTo("00000000042");
        }

        @Test
        @DisplayName("an exactly-fitting value neither pads nor shifts")
        void writeStringHandlesAnExactFit() {
            FixedWidthRecord record = new FixedWidthRecord(4, ASCII);

            record.writeString(0, 4, "WXYZ");

            assertThat(record.readString(0, 4)).isEqualTo("WXYZ");
        }

        @Test
        @DisplayName("an over-wide value is rejected, never silently truncated")
        void writeStringRefusesToTruncate() {
            FixedWidthRecord record = new FixedWidthRecord(4, ASCII);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> record.writeString(0, 4, "TOO-LONG"))
                    .withMessageContaining("encodes to 8 byte(s)")
                    .withMessageContaining("only 4 byte(s) wide")
                    .withMessageContaining("never truncates");

            assertThat(record.readString(0, 4)).as("the failed write left no partial state")
                    .isEqualTo("    ");
        }

        @Test
        @DisplayName("writeBytes replaces a sub-span and readBytes extracts one")
        void subSpanReplacementAndExtraction() {
            FixedWidthRecord record = new FixedWidthRecord(8, ASCII);
            record.writeString(0, 8, "ABCDEFGH");

            record.writeBytes(2, "xy".getBytes(ASCII));

            assertThat(record.readString(0, 8)).isEqualTo("ABxyEFGH");
            assertThat(record.readBytes(2, 2)).isEqualTo("xy".getBytes(ASCII));
        }

        @Test
        @DisplayName("fill blanks a span with a chosen byte")
        void fillOverwritesASpan() {
            FixedWidthRecord record = new FixedWidthRecord(6, ASCII);
            record.writeString(0, 6, "ABCDEF");

            record.fill(1, 4, record.zeroPadByte());

            assertThat(record.readString(0, 6)).isEqualTo("A0000F");
        }

        @Test
        @DisplayName("null values and null byte arrays are rejected")
        void nullPayloadsAreRejected() {
            FixedWidthRecord record = new FixedWidthRecord(4, ASCII);

            assertThatNullPointerException()
                    .isThrownBy(() -> record.writeString(0, 4, null))
                    .withMessageContaining("A value is required");
            assertThatNullPointerException()
                    .isThrownBy(() -> record.writeBytes(0, null))
                    .withMessageContaining("Replacement bytes are required");
        }

        @Test
        @DisplayName("a negative offset is rejected and names the record length")
        void negativeOffsetsAreRejected() {
            FixedWidthRecord record = new FixedWidthRecord(300, ASCII);

            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> record.readBytes(-1, 4))
                    .withMessageContaining("Offset -1 is negative")
                    .withMessageContaining("record length 300");
        }

        @Test
        @DisplayName("a span of zero or negative length is rejected")
        void nonPositiveLengthsAreRejected() {
            FixedWidthRecord record = new FixedWidthRecord(300, ASCII);

            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> record.readBytes(0, 0))
                    .withMessageContaining("Length 0 at offset 0")
                    .withMessageContaining("record length 300");
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> record.writeBytes(0, new byte[0]))
                    .withMessageContaining("Length 0 at offset 0");
        }

        @Test
        @DisplayName("an offset past the end, and a length running past it, are both rejected")
        void spansOutsideTheRecordAreRejected() {
            FixedWidthRecord record = new FixedWidthRecord(50, ASCII);

            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> record.readString(50, 1))
                    .withMessageContaining("offset 50 of length 1")
                    .withMessageContaining("reaches byte 51")
                    .withMessageContaining("length 50");

            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> record.readString(45, 10))
                    .withMessageContaining("reaches byte 55");

            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> record.fill(Integer.MAX_VALUE, 4, ASCII_SPACE))
                    .as("the bound is written so integer overflow cannot defeat it")
                    .withMessageContaining("past the end");
        }

        @Test
        @DisplayName("the last byte of the record is addressable")
        void theFinalByteIsReachable() {
            FixedWidthRecord record = new FixedWidthRecord(50, ASCII);

            record.writeString(49, 1, "Z");

            assertThat(record.readString(49, 1)).isEqualTo("Z");
        }

        @Test
        @DisplayName("CVACT01Y's first and last spans are both writable to the exact byte")
        void theFirstAndFinalSpansOfARealRecordAreWritable() {
            FixedWidthRecord record = new FixedWidthRecord(300, ASCII);

            record.writeString(0, 11, "10000000001", false, record.zeroPadByte());
            record.writeBytes(122, "F".repeat(178).getBytes(ASCII));

            assertThat(record.readString(0, 11)).isEqualTo("10000000001");
            assertThat(record.readString(122, 178))
                    .as("the write ended exactly on byte 300")
                    .isEqualTo("F".repeat(178))
                    .hasSize(178);
            assertThat(record.toByteArray()).as("no byte was added or lost").hasSize(300);

            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> record.writeBytes(123, new byte[178]))
                    .withMessageContaining("reaches byte 301")
                    .withMessageContaining("length 300");
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> record.readBytes(122, 179))
                    .withMessageContaining("reaches byte 301");
        }

        @Test
        @DisplayName("a write touches its own span and leaves every other byte untouched")
        void aWriteLeavesEveryOtherByteUntouched() {
            FixedWidthRecord record = new FixedWidthRecord(50, ASCII);

            record.writeString(16, 9, "000000042", false, record.zeroPadByte());

            assertThat(record.readString(0, 16))
                    .as("the bytes before the written span are still the allocation's spaces")
                    .isEqualTo(" ".repeat(16));
            assertThat(record.readString(16, 9)).isEqualTo("000000042");
            assertThat(record.readString(25, 25))
                    .as("and the bytes after it likewise")
                    .isEqualTo(" ".repeat(25));
            assertThat(record.readBytes(0, 16)).containsOnly(ASCII_SPACE);
            assertThat(record.readBytes(25, 25)).containsOnly(ASCII_SPACE);
        }
    }

    @Nested
    @DisplayName("Descriptor-driven access")
    class DescriptorDrivenAccess {
        @Test
        @DisplayName("writeSpan takes its alignment and pad from the descriptor's kind")
        void writeSpanUsesTheDescriptorsAlignment() {
            RecordLayout layout = accountRecordLayout();
            FixedWidthRecord record = layout.newRecord(ASCII);

            record.writeSpan(layout.span("ACCT-GROUP-ID"), "GRP1");
            record.writeSpan(layout.span("ACCT-ID"), "42");

            assertThat(record.readSpan(layout.span("ACCT-GROUP-ID")))
                    .as("PIC X is left justified and space-padded")
                    .isEqualTo("GRP1      ");
            assertThat(record.readSpan(layout.span("ACCT-ID")))
                    .as("PIC 9 is right justified and zero-padded")
                    .isEqualTo("00000000042");
        }

        @Test
        @DisplayName("readSpanBytes and writeSpanBytes move a span's raw bytes")
        void spanBytesRoundTrip() {
            RecordLayout layout = cardXrefLayout();
            FixedWidthRecord record = layout.newRecord(ASCII);
            FieldSpan cardNum = layout.span("XREF-CARD-NUM");

            record.writeSpanBytes(cardNum, "4111111111111111".getBytes(ASCII));

            assertThat(record.readSpanBytes(cardNum)).isEqualTo("4111111111111111".getBytes(ASCII));
            assertThat(record.readSpan(cardNum)).isEqualTo("4111111111111111");
        }

        @Test
        @DisplayName("a descriptor-driven byte write must match the span width exactly")
        void spanBytesWriteRequiresAnExactWidth() {
            RecordLayout layout = cardXrefLayout();
            FixedWidthRecord record = layout.newRecord(ASCII);
            FieldSpan cardNum = layout.span("XREF-CARD-NUM");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> record.writeSpanBytes(cardNum, new byte[15]))
                    .withMessageContaining("Supplied 15 byte(s)")
                    .withMessageContaining("XREF-CARD-NUM (ALPHANUMERIC, offset 0, length 16)")
                    .withMessageContaining("match the span width exactly");
        }

        @Test
        @DisplayName("null descriptors are rejected on every descriptor-driven entry point")
        void nullDescriptorsAreRejected() {
            FixedWidthRecord record = new FixedWidthRecord(10, ASCII);

            assertThatNullPointerException().isThrownBy(() -> record.readSpan(null))
                    .withMessageContaining("field descriptor is required");
            assertThatNullPointerException().isThrownBy(() -> record.writeSpan(null, "A"))
                    .withMessageContaining("field descriptor is required");
            assertThatNullPointerException().isThrownBy(() -> record.readSpanBytes(null))
                    .withMessageContaining("field descriptor is required");
            assertThatNullPointerException().isThrownBy(() -> record.writeSpanBytes(null, new byte[1]))
                    .withMessageContaining("field descriptor is required");
            assertThatNullPointerException()
                    .isThrownBy(() -> record.writeSpanBytes(FieldSpan.alphanumeric("A", 0, 1), null))
                    .withMessageContaining("Replacement bytes are required for span 'A'");
        }

        @Test
        @DisplayName("a descriptor that overruns the record is rejected by the bounds check")
        void aDescriptorBeyondTheRecordIsRejected() {
            FixedWidthRecord record = new FixedWidthRecord(10, ASCII);

            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> record.readSpan(FieldSpan.alphanumeric("WIDE", 4, 12)))
                    .withMessageContaining("reaches byte 16")
                    .withMessageContaining("length 10");
        }
    }

    @Nested
    @DisplayName("Initialisation - a FILLER emits its VALUE, not a blanket space")
    class Initialisation {
        @Test
        @DisplayName("CSDAT01Y's WS-CURDATE-MM-DD-YY initialises to 00/00/00, separators intact")
        void fillerLiteralsSurviveInitialisation() {
            RecordLayout layout = curdateMmDdYyLayout();

            FixedWidthRecord record = layout.newRecord(ASCII);

            assertThat(record.recordLength()).isEqualTo(8);
            assertThat(record.readString(0, 8))
                    .as("a blanket space-fill would have produced '  /  /  ' or blanked the slashes")
                    .isEqualTo("00/00/00");
            assertThat(record.readSpan(layout.span("WS-CURDATE-MM"))).isEqualTo("00");
            assertThat(record.readBytes(2, 1)).containsExactly((byte) '/');
            assertThat(record.readBytes(5, 1)).containsExactly((byte) '/');
        }

        @Test
        @DisplayName("the same group initialises correctly under EBCDIC, with EBCDIC pad bytes")
        void fillerLiteralsSurviveInitialisationUnderEbcdic() {
            FixedWidthRecord record = curdateMmDdYyLayout().newRecord(EBCDIC);

            assertThat(record.readString(0, 8)).isEqualTo("00/00/00");
            assertThat(record.toByteArray()[0])
                    .as("the EBCDIC zero byte, not the ASCII one")
                    .isEqualTo(EBCDIC_ZERO);
            assertThat(record.toByteArray()[2]).isEqualTo("/".getBytes(EBCDIC)[0]);
        }

        @Test
        @DisplayName("the whole 58-byte WS-DATE-TIME group initialises with every separator in place")
        void theWholeDateTimeGroupInitialisesWithAllTenSeparators() {
            FixedWidthRecord record = dateTimeLayout().newRecord(ASCII);

            assertThat(record.readString(0, 58))
                    .isEqualTo("0000000000000000"
                            + "00/00/00"
                            + "00:00:00"
                            + "0000-00-00 00:00:00.000000")
                    .hasSize(58);
            assertThat(record.readSpan(dateTimeLayout().span("WS-CURDATE-N")))
                    .as("the overlay reads the eight bytes the group it redefines was filled with")
                    .isEqualTo("00000000");
            assertThat(record.toByteArray()).hasSize(58);
        }

        @Test
        @DisplayName("a FILLER with no VALUE initialises to spaces")
        void aFillerWithoutALiteralIsSpaceFilled() {
            RecordLayout layout = accountRecordLayout();

            FixedWidthRecord record = layout.newRecord(ASCII);

            assertThat(record.readString(122, 178)).isEqualTo(" ".repeat(178)).hasSize(178);
            assertThat(record.readBytes(122, 178)).containsOnly(ASCII_SPACE);
        }

        @Test
        @DisplayName("category defaults: zoned zeros with a sign where signed, spaces elsewhere")
        void theInitializeConventionIsApplied() {
            RecordLayout layout = accountRecordLayout();

            FixedWidthRecord record = layout.newRecord(ASCII);

            assertThat(record.readSpan(layout.span("ACCT-ID")))
                    .as("an unsigned PIC 9 span has no sign position, so it is all zone-F zeros")
                    .isEqualTo("00000000000");
            assertThat(record.readSpan(layout.span("ACCT-CURR-BAL")))
                    .as("a signed span's zero carries a positive-zero overpunch in its trailing "
                            + "byte, exactly as every zero-valued signed field in "
                            + "app/data/ASCII/acctdata.txt is stored - the fixture's first record "
                            + "renders both zero cycle amounts as 00000000000{")
                    .isEqualTo("00000000000{");
            assertThat(record.readSpan(layout.span("ACCT-OPEN-DATE"))).isEqualTo("          ");
            assertThat(record.recordLength()).isEqualTo(300);
            assertThat(record.toByteArray()).hasSize(300);
        }

        @Test
        @DisplayName("a named field's VALUE literal is honoured too, right-justified when numeric")
        void namedFieldLiteralsAreHonoured() {
            RecordLayout layout = RecordLayout.of(13,
                    FieldSpan.alphanumeric("CC-ACCT-ID", 0, 11).withInitialValue(" "),
                    new FieldSpan("FILLER", 11, 2, PictureKind.UNSIGNED_NUMERIC, "1", false));

            FixedWidthRecord record = layout.newRecord(ASCII);

            assertThat(record.readString(0, 11)).isEqualTo("           ");
            assertThat(record.readString(11, 2))
                    .as("a numeric literal is right justified and zero-padded, giving 01 not 1 ")
                    .isEqualTo("01");
        }

        @Test
        @DisplayName("both operations refuse a layout of a different declared length")
        void initialiseRejectsAMismatchedLayout() {
            FixedWidthRecord record = new FixedWidthRecord(50, ASCII);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> record.initialize(accountRecordLayout(),
                            FillerHandling.WITH_FILLER, ValueHandling.CATEGORY_DEFAULTS))
                    .withMessageContaining("Layout declares a record length of 300")
                    .withMessageContaining("this record is 50 byte(s) wide");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> record.loadDeclaredValues(accountRecordLayout()))
                    .withMessageContaining("Layout declares a record length of 300");

            assertThatNullPointerException()
                    .isThrownBy(() -> record.initialize(null, FillerHandling.WITH_FILLER,
                            ValueHandling.CATEGORY_DEFAULTS))
                    .withMessageContaining("record layout is required");
            assertThatNullPointerException().isThrownBy(() -> record.loadDeclaredValues(null))
                    .withMessageContaining("record layout is required");
            assertThatNullPointerException()
                    .isThrownBy(() -> record.initialize(tranCatBalLayout(), null,
                            ValueHandling.CATEGORY_DEFAULTS))
                    .withMessageContaining("whether FILLER participates");
            assertThatNullPointerException()
                    .isThrownBy(() -> record.initialize(tranCatBalLayout(),
                            FillerHandling.WITH_FILLER, null))
                    .withMessageContaining("what INITIALIZE moves");
            assertThatNullPointerException()
                    .isThrownBy(() -> FixedWidthRecord.forLayout(null, ASCII))
                    .withMessageContaining("record layout is required");
        }

        @Test
        @DisplayName("INITIALIZE resets a dirty area and skips REDEFINES overlays")
        void initialiseIsIdempotentAndSkipsOverlays() {
            FieldSpan acctId = FieldSpan.alphanumeric("CC-ACCT-ID", 0, 11);
            RecordLayout layout = RecordLayout.of(11,
                    acctId,
                    acctId.redefinedAs("CC-ACCT-ID-N", PictureKind.UNSIGNED_NUMERIC));

            FixedWidthRecord record = layout.newRecord(ASCII);
            assertThat(record.readString(0, 11))
                    .as("the character span decides the fill; the overlay is skipped, "
                            + "so the span is not re-filled with zeros")
                    .isEqualTo("           ");

            record.writeString(0, 11, "DIRTY");
            record.initialize(layout, FillerHandling.WITH_FILLER, ValueHandling.CATEGORY_DEFAULTS);

            assertThat(record.readString(0, 11)).isEqualTo("           ");
            assertThat(layout.redefinitions()).hasSize(1);
            assertThat(layout.storageSpans()).hasSize(1);
        }

        @Test
        @DisplayName("every signed span's category default ends in a positive-zero overpunch")
        void signedSpansInitialiseWithASignOverpunch() {
            FixedWidthRecord tranCatBal = tranCatBalLayout().newRecord(ASCII);
            FixedWidthRecord disclosure = disclosureGroupLayout().newRecord(ASCII);

            assertThat(tranCatBal.readSpan(tranCatBalLayout().span("TRAN-CAT-BAL")))
                    .as("CVTRA01Y's S9(09)V99 is 11 bytes: ten zoned zeros and a signed zero")
                    .isEqualTo("0000000000" + ZonedSign.POSITIVE_ZERO)
                    .hasSize(11);
            assertThat(disclosure.readSpan(disclosureGroupLayout().span("DIS-INT-RATE")))
                    .as("CVTRA02Y's S9(04)V99 is 6 bytes")
                    .isEqualTo("00000" + ZonedSign.POSITIVE_ZERO)
                    .hasSize(6);
            assertThat(tranCatBal.readSpan(tranCatBalLayout().span("TRANCAT-ACCT-ID")))
                    .as("an unsigned span is untouched by the sign rule")
                    .isEqualTo("00000000000");
        }

        @Test
        @DisplayName("the sign overpunch is a code-page byte, not a hard-coded 0x7B")
        void theSignOverpunchIsResolvedThroughTheCodePage() {
            FixedWidthRecord ascii = tranCatBalLayout().newRecord(ASCII);
            FixedWidthRecord ebcdic = tranCatBalLayout().newRecord(EBCDIC);

            assertThat(ascii.readBytes(27, 1))
                    .as("'{' is 0x7B under US-ASCII")
                    .containsExactly((byte) 0x7B);
            assertThat(ebcdic.readBytes(27, 1))
                    .as("'{' is 0xC0 under IBM037, so a hard-coded ASCII byte would corrupt every "
                            + "signed field of an EBCDIC record")
                    .containsExactly((byte) 0xC0);
            assertThat(ebcdic.readSpan(tranCatBalLayout().span("TRAN-CAT-BAL")))
                    .as("decoded under its own code page the image is identical either way")
                    .isEqualTo("0000000000{");
        }

        @Test
        @DisplayName("loadDeclaredValues applies literals only and leaves unvalued spans alone")
        void loadDeclaredValuesLeavesUnvaluedStorageAlone() {
            RecordLayout layout = curdateMmDdYyLayout();
            FixedWidthRecord record = new FixedWidthRecord(layout.recordLength(), ASCII);
            record.fill(0, layout.recordLength(), (byte) 'Z');

            record.loadDeclaredValues(layout);

            assertThat(record.readString(0, 8))
                    .as("the two FILLER VALUE '/' spans are applied; the three numeric spans declare "
                            + "no literal and so keep whatever the storage held - this operation "
                            + "never invents a value a declaration did not state")
                    .isEqualTo("ZZ/ZZ/ZZ");
        }

        @Test
        @DisplayName("INITIALIZE without WITH FILLER leaves FILLER literals standing")
        void initializeWithoutFillerSkipsFillerSpans() {
            RecordLayout layout = curdateMmDdYyLayout();
            FixedWidthRecord record = layout.newRecord(ASCII);
            record.writeSpan(layout.span("WS-CURDATE-MM"), "12");

            record.initialize(layout, FillerHandling.WITHOUT_FILLER,
                    ValueHandling.CATEGORY_DEFAULTS);

            assertThat(record.readString(0, 8))
                    .as("COBOL's INITIALIZE skips FILLER unless WITH FILLER is written, so the "
                            + "separators survive and only the named spans are blanked")
                    .isEqualTo("00/00/00");
        }

        @Test
        @DisplayName("INITIALIZE WITH FILLER blanks the separators; TO VALUE restores them")
        void withFillerBlanksSeparatorsAndToValueRestoresThem() {
            RecordLayout layout = curdateMmDdYyLayout();
            FixedWidthRecord record = layout.newRecord(ASCII);

            record.initialize(layout, FillerHandling.WITH_FILLER, ValueHandling.CATEGORY_DEFAULTS);
            assertThat(record.readString(0, 8))
                    .as("WITH FILLER treats a FILLER like any other span, so its literal is gone")
                    .isEqualTo("00 00 00");

            record.initialize(layout, FillerHandling.WITH_FILLER, ValueHandling.TO_VALUE);
            assertThat(record.readString(0, 8))
                    .as("ALL TO VALUE moves each declared literal and leaves a span that declares "
                            + "none exactly as it was, which is why the digits are untouched here")
                    .isEqualTo("00/00/00");
        }

        @Test
        @DisplayName("forLayout is the composition of the two, and says so")
        void forLayoutComposesTheTwoOperations() {
            RecordLayout layout = curdateMmDdYyLayout();

            FixedWidthRecord established = FixedWidthRecord.forLayout(layout, ASCII);

            FixedWidthRecord byHand = new FixedWidthRecord(layout.recordLength(), ASCII);
            byHand.initialize(layout, FillerHandling.WITH_FILLER, ValueHandling.CATEGORY_DEFAULTS);
            byHand.loadDeclaredValues(layout);

            assertThat(established.toByteArray()).isEqualTo(byHand.toByteArray());
        }
    }

    @Nested
    @DisplayName("Strict transcoding - a coding failure is refused, never substituted")
    class StrictTranscoding {
        @Test
        @DisplayName("an unrepresentable character is refused instead of becoming a question mark")
        void anUnrepresentableCharacterIsRefused() {
            FixedWidthRecord record = new FixedWidthRecord(10, ASCII);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> record.writeString(0, 10, "CAF\u00c9"))
                    .as("String.getBytes(US-ASCII) would have written 'CAF?' - a value no COBOL "
                            + "program could have produced, and one that compares equal to a "
                            + "genuine question mark for ever after")
                    .withMessageContaining("cannot represent")
                    .withMessageContaining("character position 3")
                    .withMessageNotContaining("CAF");
        }

        @Test
        @DisplayName("a malformed stored byte is refused instead of becoming U+FFFD")
        void aMalformedStoredByteIsRefused() {
            byte[] stored = {(byte) 0x41, (byte) 0xFF, (byte) 0x43};
            FixedWidthRecord record = FixedWidthRecord.copyOf(stored, 3, ASCII);

            assertThatIllegalStateException()
                    .isThrownBy(() -> record.readString(0, 3))
                    .withMessageContaining("not valid code page US-ASCII data")
                    .withMessageContaining("withheld deliberately");
        }

        @Test
        @DisplayName("a coding failure names the field it happened in, and never its content")
        void aCodingFailureNamesTheFieldNotTheContent() {
            FixedWidthRecord record = new FixedWidthRecord(16, ASCII);
            FieldSpan cardNumber = FieldSpan.alphanumeric("CARD-NUM", 0, 16);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> record.writeSpan(cardNumber, "4111\u00a011112222333"))
                    .withMessageContaining("CARD-NUM")
                    .withMessageNotContaining("4111");
        }

        @Test
        @DisplayName("the static seam serves callers holding an image but no record")
        void theStaticSeamTranscodesWholeImages() {
            byte[] encoded = FixedWidthRecord.encodeText("ABC", ASCII, "a test image");

            assertThat(encoded).containsExactly((byte) 'A', (byte) 'B', (byte) 'C');
            assertThat(FixedWidthRecord.decodeText(encoded, ASCII, "a test image")).isEqualTo("ABC");
            assertThatIllegalStateException()
                    .isThrownBy(() -> FixedWidthRecord.decodeText(new byte[]{(byte) 0x80}, ASCII,
                            "a test image"))
                    .withMessageContaining("US-ASCII");
            assertThatNullPointerException()
                    .isThrownBy(() -> FixedWidthRecord.decodeText(null, ASCII, "a test image"))
                    .withMessageContaining("Stored bytes are required");
            assertThatNullPointerException()
                    .isThrownBy(() -> FixedWidthRecord.encodeText("A", ASCII, null))
                    .withMessageContaining("subject is required");
            assertThatNullPointerException()
                    .isThrownBy(() -> FixedWidthRecord.encodeText(null, ASCII, "a test image"))
                    .withMessageContaining("Text is required");
        }

        @Test
        @DisplayName("a multi-byte code page is refused by the width assertion, not silently widened")
        void aMultiByteCodePageIsRefusedByTheWidthAssertion() {
            FixedWidthRecord.Transcoder utf8 =
                    new FixedWidthRecord.Transcoder(java.nio.charset.StandardCharsets.UTF_8);

            assertThat(utf8.encode("ABC", "a test image")).hasSize(3);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> utf8.encode("\u00c9", "a test image"))
                    .as("UTF-8 can represent it, but in two bytes, which would shift every "
                            + "subsequent offset in the record")
                    .withMessageContaining("produced 2 byte(s)")
                    .withMessageContaining("exactly one byte");
            assertThatIllegalStateException()
                    .isThrownBy(() -> utf8.decode("\u00c9".getBytes(
                            java.nio.charset.StandardCharsets.UTF_8), 0, 2, "a test image"))
                    .as("and decoding is refused for the mirror-image reason: two stored bytes that "
                            + "are one character would shorten every span they appear in")
                    .withMessageContaining("produced 1 character(s)")
                    .withMessageContaining("single-byte code page");
            assertThat(utf8.measuredWidthOf('A'))
                    .as("the measurement path reports a width rather than throwing, so a codec can "
                            + "name the offending character itself")
                    .isEqualTo(1);
            assertThat(utf8.measuredWidthOf('\u00c9')).isEqualTo(2);
            assertThat(new FixedWidthRecord.Transcoder(ASCII).measuredWidthOf('\u00c9'))
                    .as("zero means the code page cannot represent it at all")
                    .isZero();
            assertThat(utf8.charset()).isEqualTo(java.nio.charset.StandardCharsets.UTF_8);
            assertThatNullPointerException()
                    .isThrownBy(() -> new FixedWidthRecord.Transcoder(null))
                    .withMessageContaining("charset must be supplied explicitly");
        }

        @Test
        @DisplayName("the zoned sign alphabet is one table, shared with the layer above")
        void theZonedSignAlphabetIsShared() {
            assertThat(ZonedSign.POSITIVE_DIGITS).isEqualTo("{ABCDEFGHI");
            assertThat(ZonedSign.NEGATIVE_DIGITS).isEqualTo("}JKLMNOPQR");
            assertThat(ZonedSign.POSITIVE_ZERO).isEqualTo('{');
            assertThat(ZonedSign.NEGATIVE_ZERO).isEqualTo('}');
            assertThat(ZonedSign.overpunch(0, false)).isEqualTo('{');
            assertThat(ZonedSign.overpunch(7, false)).isEqualTo('G');
            assertThat(ZonedSign.overpunch(0, true)).isEqualTo('}');
            assertThat(ZonedSign.overpunch(9, true)).isEqualTo('R');
            assertThat(ZonedSign.digitOf('{')).isZero();
            assertThat(ZonedSign.digitOf('}')).isZero();
            assertThat(ZonedSign.digitOf('G')).isEqualTo(7);
            assertThat(ZonedSign.digitOf('R')).isEqualTo(9);
            assertThat(ZonedSign.digitOf('4')).isEqualTo(4);
            assertThat(ZonedSign.digitOf('*')).isEqualTo(-1);
            assertThat(ZonedSign.isNegative('}')).isTrue();
            assertThat(ZonedSign.isNegative('R')).isTrue();
            assertThat(ZonedSign.isNegative('{')).isFalse();
            assertThat(ZonedSign.isNegative('0')).isFalse();
            assertThatIllegalArgumentException().isThrownBy(() -> ZonedSign.overpunch(10, false))
                    .withMessageContaining("cannot be sign-overpunched");
            assertThatIllegalArgumentException().isThrownBy(() -> ZonedSign.overpunch(-1, true))
                    .withMessageContaining("cannot be sign-overpunched");
        }
    }

    @Nested
    @DisplayName("REDEFINES - two views over one backing span")
    class Redefines {
        @Test
        @DisplayName("CVCRD01Y's CC-ACCT-ID / CC-ACCT-ID-N pair reads the same eleven bytes")
        void anOverlayReadsTheSameBytes() {
            FieldSpan acctId = FieldSpan.alphanumeric("CC-ACCT-ID", 0, 11);
            FieldSpan acctIdN = acctId.redefinedAs("CC-ACCT-ID-N", PictureKind.UNSIGNED_NUMERIC);
            RecordLayout layout = RecordLayout.of(11, acctId, acctIdN);
            FixedWidthRecord record = layout.newRecord(ASCII);

            record.writeSpan(acctId, "00000000042");

            assertThat(acctIdN.offset()).isEqualTo(acctId.offset());
            assertThat(acctIdN.length()).isEqualTo(acctId.length());
            assertThat(acctIdN.redefinition()).isTrue();
            assertThat(acctId.redefinition()).isFalse();
            assertThat(record.readSpan(acctIdN)).isEqualTo(record.readSpan(acctId));
        }

        @Test
        @DisplayName("a write through either view is visible through the other - one span, not a copy")
        void writesArrivedThroughBothViews() {
            FieldSpan acctId = FieldSpan.alphanumeric("CC-ACCT-ID", 0, 11);
            FieldSpan acctIdN = acctId.redefinedAs("CC-ACCT-ID-N", PictureKind.UNSIGNED_NUMERIC);
            FixedWidthRecord record = RecordLayout.of(11, acctId, acctIdN).newRecord(ASCII);

            record.writeSpan(acctIdN, "7");

            assertThat(record.readSpan(acctIdN))
                    .as("written through the numeric view: right justified, zero-padded")
                    .isEqualTo("00000000007");
            assertThat(record.readSpan(acctId))
                    .as("and immediately visible through the character view")
                    .isEqualTo("00000000007");

            record.writeSpan(acctId, "ABC");

            assertThat(record.readSpan(acctIdN)).isEqualTo("ABC        ");
        }

        @Test
        @DisplayName("a narrower overlay is allowed; a wider one is rejected")
        void overlayWidthIsValidated() {
            FieldSpan acctId = FieldSpan.alphanumeric("CC-ACCT-ID", 0, 11);

            assertThat(acctId.redefinedAs("PREFIX", PictureKind.UNSIGNED_NUMERIC, 4).length())
                    .isEqualTo(4);
            assertThat(acctId.redefinedAs("WHOLE", PictureKind.UNSIGNED_NUMERIC, 11).length())
                    .isEqualTo(11);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> acctId.redefinedAs("TOO-WIDE", PictureKind.ALPHANUMERIC, 12))
                    .withMessageContaining("overlay 'TOO-WIDE' is 12 byte(s) wide")
                    .withMessageContaining("only 11 byte(s) wide")
                    .withMessageContaining("must not exceed the span it redefines");
        }

        @Test
        @DisplayName("CSDAT01Y's WS-CURDATE-N redefines a whole group of three elementary items")
        void aGroupRedefinitionIsAccepted() {
            RecordLayout layout = RecordLayout.of(8,
                    FieldSpan.unsignedNumeric("WS-CURDATE-YEAR", 0, 4),
                    FieldSpan.unsignedNumeric("WS-CURDATE-MONTH", 4, 2),
                    FieldSpan.unsignedNumeric("WS-CURDATE-DAY", 6, 2),
                    FieldSpan.redefining("WS-CURDATE-N", 0, 8, PictureKind.UNSIGNED_NUMERIC));

            FixedWidthRecord record = layout.newRecord(ASCII);
            record.writeSpan(layout.span("WS-CURDATE-YEAR"), "2022");
            record.writeSpan(layout.span("WS-CURDATE-MONTH"), "7");
            record.writeSpan(layout.span("WS-CURDATE-DAY"), "18");

            assertThat(record.readSpan(layout.span("WS-CURDATE-N"))).isEqualTo("20220718");
            assertThat(layout.storageSpans()).hasSize(3);
            assertThat(layout.redefinitions()).hasSize(1);
        }

        @Test
        @DisplayName("an overlay outside already-declared storage is rejected")
        void anOverlayMustRedefineExistingStorage() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(8,
                            FieldSpan.redefining("EARLY", 0, 8, PictureKind.UNSIGNED_NUMERIC),
                            FieldSpan.unsignedNumeric("REAL", 0, 8)))
                    .withMessageContaining("REDEFINES overlay EARLY")
                    .withMessageContaining("only 0 byte(s) of storage are declared ahead of it");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(8,
                            FieldSpan.unsignedNumeric("REAL", 0, 4),
                            FieldSpan.redefining("BEYOND", 0, 8, PictureKind.UNSIGNED_NUMERIC),
                            FieldSpan.unsignedNumeric("SECOND", 4, 4)))
                    .withMessageContaining("reaches byte 8 but only 4 byte(s)");
        }

        @Test
        @DisplayName("gate G34: three overlays view 36 bytes and add none to the record total")
        void overlaysContributeNoBytesToTheRecordTotal() {
            RecordLayout layout = ccWorkAreaLayout();

            int storage = 0;
            for (FieldSpan span : layout.storageSpans()) {
                storage += span.length();
            }
            int overlaid = 0;
            for (FieldSpan span : layout.redefinitions()) {
                overlaid += span.length();
            }

            assertThat(layout.storageSpans()).hasSize(9);
            assertThat(layout.redefinitions()).hasSize(3);
            assertThat(storage).as("the nine declared items sum to the record length").isEqualTo(213);
            assertThat(overlaid).as("the three overlays view 11 + 16 + 9 bytes between them")
                    .isEqualTo(36);
            assertThat(layout.recordLength())
                    .as("the record is 213 bytes, not 249 - an overlay is a view, never storage")
                    .isEqualTo(213)
                    .isNotEqualTo(storage + overlaid);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(11,
                            FieldSpan.alphanumeric("CC-ACCT-ID", 0, 11),
                            FieldSpan.unsignedNumeric("CC-ACCT-ID-N", 0, 11)))
                    .withMessageContaining("Layout overlap at CC-ACCT-ID-N")
                    .withMessageContaining("overlaps 11 byte(s)")
                    .withMessageContaining("Declare it as a REDEFINES overlay if the overlap is "
                            + "intended");
        }

        @Test
        @DisplayName("CVCRD01Y's CC-CUST-ID / CC-CUST-ID-N pair round-trips through both views")
        void theCustIdPairSharesOneNineByteSpan() {
            RecordLayout layout = ccWorkAreaLayout();
            FieldSpan text = layout.span("CC-CUST-ID");
            FieldSpan digits = layout.span("CC-CUST-ID-N");
            FixedWidthRecord record = layout.newRecord(ASCII);

            assertThat(digits.offset()).as("one backing span, two views")
                    .isEqualTo(text.offset())
                    .isEqualTo(204);
            assertThat(digits.length()).isEqualTo(text.length()).isEqualTo(9);
            assertThat(digits.redefinition()).isTrue();
            assertThat(text.redefinition()).isFalse();

            record.writeSpan(digits, "42");
            assertThat(record.readSpan(digits)).isEqualTo("000000042");
            assertThat(record.readSpan(text))
                    .as("and immediately visible through the character view")
                    .isEqualTo("000000042");

            record.writeSpan(text, "AB");
            assertThat(record.readSpan(text)).isEqualTo("AB       ");
            assertThat(record.readSpan(digits))
                    .as("and immediately visible through the numeric view")
                    .isEqualTo("AB       ");
            assertThat(record.readSpanBytes(digits)).isEqualTo(record.readBytes(204, 9));
        }

        @Test
        @DisplayName("CSDAT01Y's WS-CURTIME-N redefines four two-digit items as one PIC 9(08)")
        void theCurtimeGroupRedefinitionCoversFourItems() {
            RecordLayout layout = dateTimeLayout();
            FieldSpan whole = layout.span("WS-CURTIME-N");
            FixedWidthRecord record = layout.newRecord(ASCII);

            assertThat(whole.offset()).isEqualTo(8);
            assertThat(whole.length()).isEqualTo(8);
            assertThat(whole.redefinition()).isTrue();

            record.writeSpan(layout.span("WS-CURTIME-HOURS"), "23");
            record.writeSpan(layout.span("WS-CURTIME-MINUTE"), "15");
            record.writeSpan(layout.span("WS-CURTIME-SECOND"), "59");
            record.writeSpan(layout.span("WS-CURTIME-MILSEC"), "7");

            assertThat(record.readSpan(whole))
                    .as("four elementary items read as one eight-digit value; MILSEC zero-pads to 07")
                    .isEqualTo("23155907");

            record.writeSpan(whole, "01020304");

            assertThat(record.readSpan(layout.span("WS-CURTIME-HOURS"))).isEqualTo("01");
            assertThat(record.readSpan(layout.span("WS-CURTIME-MINUTE"))).isEqualTo("02");
            assertThat(record.readSpan(layout.span("WS-CURTIME-SECOND"))).isEqualTo("03");
            assertThat(record.readSpan(layout.span("WS-CURTIME-MILSEC"))).isEqualTo("04");
            assertThat(record.readSpan(layout.span("WS-CURDATE-N")))
                    .as("the sibling overlay over bytes 0..7 is untouched by either write")
                    .isEqualTo("00000000");
        }

        @Test
        @DisplayName("VALUE SPACES seen through a PIC 9(11) view stays spaces - COBOL permits it")
        void anAllSpacesSpanReadThroughTheNumericViewIsNotCorrected() {
            RecordLayout layout = ccWorkAreaLayout();
            FieldSpan text = layout.span("CC-ACCT-ID");
            FieldSpan digits = layout.span("CC-ACCT-ID-N");
            FixedWidthRecord record = layout.newRecord(ASCII);

            assertThat(text.hasInitialValue()).as("the copybook declares VALUE SPACES").isTrue();
            assertThat(digits.hasInitialValue())
                    .as("an overlay carries no literal; the storage span it redefines owns the fill")
                    .isFalse();

            assertThat(record.readSpan(text)).isEqualTo(" ".repeat(11));
            assertThat(record.readSpan(digits))
                    .as("read back through the numeric view unchanged - not zeroed, not trimmed")
                    .isEqualTo(" ".repeat(11))
                    .hasSize(11);
            assertThat(record.readSpanBytes(digits)).containsOnly(ASCII_SPACE);
            assertThat(record.readSpan(digits)).isEqualTo(record.readSpan(text));
        }

        @Test
        @DisplayName("an overlay is skipped by initialisation but still resolvable by name")
        void overlaysAreAddressableButNotInitialised() {
            FieldSpan text = FieldSpan.alphanumeric("CC-CARD-NUM", 0, 16);
            RecordLayout layout = RecordLayout.of(16, text,
                    text.redefinedAs("CC-CARD-NUM-N", PictureKind.UNSIGNED_NUMERIC));

            assertThat(layout.hasSpan("CC-CARD-NUM-N")).isTrue();
            assertThat(layout.span("CC-CARD-NUM-N").redefinition()).isTrue();
            assertThat(layout.newRecord(ASCII).readString(0, 16))
                    .as("only the storage span drives the fill")
                    .isEqualTo("                ");
        }
    }

    @Nested
    @DisplayName("OCCURS - COBOL subscripts are 1-based")
    class Occurs {
        @Test
        @DisplayName("gate G33: index 1 is the first element and index n the last")
        void firstAndLastElementsResolveCorrectly() {
            int base = 40;
            int elementLength = 12;
            int occurs = 9;

            assertThat(FixedWidthRecord.occursElementOffsetOneBased(base, elementLength, occurs, 1))
                    .as("the first element sits at the table's base offset")
                    .isEqualTo(40);
            assertThat(FixedWidthRecord.occursElementOffsetOneBased(base, elementLength, occurs, 9))
                    .as("the last element sits at base + (n - 1) * elementLength")
                    .isEqualTo(40 + 8 * 12);
        }

        @DisplayName("every subscript in range maps to base + (index - 1) * length")
        @ParameterizedTest(name = "subscript {0} resolves to offset {1}")
        @CsvSource({"1, 0", "2, 5", "3, 10", "4, 15", "5, 20"})
        void everySubscriptInRangeResolves(int oneBasedIndex, int expectedOffset) {
            assertThat(FixedWidthRecord.occursElementOffsetOneBased(0, 5, 5, oneBasedIndex))
                    .isEqualTo(expectedOffset);
        }

        @Test
        @DisplayName("index 0 and index n+1 are both rejected")
        void outOfRangeSubscriptsAreRejected() {
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> FixedWidthRecord.occursElementOffsetOneBased(0, 12, 9, 0))
                    .withMessageContaining("subscript 0 is outside 1..9")
                    .withMessageContaining("there is no index 0");

            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> FixedWidthRecord.occursElementOffsetOneBased(0, 12, 9, 10))
                    .withMessageContaining("subscript 10 is outside 1..9");
        }

        @Test
        @DisplayName("a negative base, a zero element width and a zero count are rejected")
        void tableGeometryIsValidated() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FixedWidthRecord.occursElementOffsetOneBased(-1, 12, 9, 1))
                    .withMessageContaining("base offset -1 is negative");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FixedWidthRecord.occursElementOffsetOneBased(0, 0, 9, 1))
                    .withMessageContaining("element length 0");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FixedWidthRecord.occursElementOffsetOneBased(0, 12, 0, 1))
                    .withMessageContaining("count 0");
        }

        @Test
        @DisplayName("occursElementSpan derives an element descriptor for the first and last entry")
        void elementSpansAreDerivedFromTheTableSpan() {
            FieldSpan table = FieldSpan.alphanumeric("CDEMO-ADMIN-OPT", 4, 36);

            FieldSpan first = FixedWidthRecord.occursElementSpan(table, 9, 1,
                    "CDEMO-ADMIN-OPT-NUM", PictureKind.UNSIGNED_NUMERIC);
            FieldSpan last = FixedWidthRecord.occursElementSpan(table, 9, 9,
                    "CDEMO-ADMIN-OPT-NUM", PictureKind.UNSIGNED_NUMERIC);

            assertThat(first.offset()).isEqualTo(4);
            assertThat(first.length()).isEqualTo(4);
            assertThat(last.offset()).isEqualTo(4 + 8 * 4);
            assertThat(last.endOffsetExclusive()).isEqualTo(40);
            assertThat(first.redefinition()).isFalse();
        }

        @Test
        @DisplayName("an element of an overlay table is itself an overlay")
        void overlayTablesPropagateTheOverlayFlag() {
            FieldSpan overlayTable = FieldSpan.redefining("TABLE-N", 0, 20,
                    PictureKind.UNSIGNED_NUMERIC);

            FieldSpan element = FixedWidthRecord.occursElementSpan(overlayTable, 4, 2, "ELEM",
                    PictureKind.UNSIGNED_NUMERIC);

            assertThat(element.redefinition()).isTrue();
            assertThat(element.offset()).isEqualTo(5);
            assertThat(element.length()).isEqualTo(5);
        }

        @Test
        @DisplayName("a table width that is not a whole multiple of the count is rejected")
        void anInexactTableWidthIsRejected() {
            FieldSpan table = FieldSpan.alphanumeric("TABLE", 0, 37);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FixedWidthRecord.occursElementSpan(table, 9, 1, "ELEM",
                            PictureKind.ALPHANUMERIC))
                    .withMessageContaining("37 byte(s) wide")
                    .withMessageContaining("not a whole multiple of its 9 occurrence(s)");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FixedWidthRecord.occursElementSpan(table, 0, 1, "ELEM",
                            PictureKind.ALPHANUMERIC))
                    .withMessageContaining("count 0");

            assertThatNullPointerException()
                    .isThrownBy(() -> FixedWidthRecord.occursElementSpan(null, 9, 1, "ELEM",
                            PictureKind.ALPHANUMERIC))
                    .withMessageContaining("whole OCCURS table span is required");
        }
    }

    @Nested
    @DisplayName("End-to-end: a real record serialises back to its exact declared width")
    class EndToEnd {
        @Test
        @DisplayName("an account record round-trips through 300 bytes with FILLER intact")
        void accountRecordRoundTrip() {
            RecordLayout layout = accountRecordLayout();
            FixedWidthRecord record = layout.newRecord(ASCII);

            record.writeSpan(layout.span("ACCT-ID"), "10000000001");
            record.writeSpan(layout.span("ACCT-ACTIVE-STATUS"), "Y");
            record.writeSpan(layout.span("ACCT-CURR-BAL"), "000000123456");
            record.writeSpan(layout.span("ACCT-OPEN-DATE"), "2022-07-19");
            record.writeSpan(layout.span("ACCT-EXPIRAION-DATE"), "2025-07-19");
            record.writeSpan(layout.span("ACCT-GROUP-ID"), "ZEROPCT");

            byte[] serialised = record.toByteArray();
            assertThat(serialised).as("gate G19: the record is exactly its copybook width")
                    .hasSize(300);

            FixedWidthRecord reread = FixedWidthRecord.copyOf(serialised, 300, ASCII);
            assertThat(reread.readSpan(layout.span("ACCT-ID"))).isEqualTo("10000000001");
            assertThat(reread.readSpan(layout.span("ACCT-ACTIVE-STATUS"))).isEqualTo("Y");
            assertThat(reread.readSpan(layout.span("ACCT-CURR-BAL"))).isEqualTo("000000123456");
            assertThat(reread.readSpan(layout.span("ACCT-OPEN-DATE"))).isEqualTo("2022-07-19");
            assertThat(reread.readSpan(layout.span("ACCT-EXPIRAION-DATE"))).isEqualTo("2025-07-19");
            assertThat(reread.readSpan(layout.span("ACCT-GROUP-ID"))).isEqualTo("ZEROPCT   ");
            assertThat(reread.readString(122, 178))
                    .as("gate G21: the trailing FILLER is emitted, space-filled")
                    .isEqualTo(" ".repeat(178));
            assertThat(reread.toByteArray()).isEqualTo(serialised);
        }

        @Test
        @DisplayName("a transaction record round-trips through 350 bytes")
        void tranRecordRoundTrip() {
            RecordLayout layout = tranRecordLayout();
            FixedWidthRecord record = layout.newRecord(EBCDIC);

            record.writeSpan(layout.span("TRAN-ID"), "0000000000000001");
            record.writeSpan(layout.span("TRAN-AMT"), "00000012345");
            record.writeSpan(layout.span("TRAN-DESC"), "Interest for a/c 10000000001");

            assertThat(record.toByteArray()).hasSize(350);
            assertThat(record.readSpan(layout.span("TRAN-AMT")))
                    .as("11 bytes, because S9(09)V99 overpunches its sign")
                    .isEqualTo("00000012345")
                    .hasSize(11);
            assertThat(layout.span("TRAN-AMT").length()).isEqualTo(11);
            assertThat(record.readString(330, 20)).isEqualTo(" ".repeat(20));
        }
    }

    @Nested
    @DisplayName("Strict coding boundary and exact geometry - F08 and F18")
    class StrictCodingAndGeometry {
        private static final String UNMAPPABLE_UNDER_ASCII = "\u00e9";

        @Test
        @DisplayName("encodeStrictly rejects an unmappable character and withholds it")
        void encodeStrictlyRejectsAnUnmappableCharacter() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FixedWidthRecord.encodeStrictly(UNMAPPABLE_UNDER_ASCII, ASCII,
                            "CUST-FIRST-NAME"))
                    .withMessageContaining("US-ASCII")
                    .withMessageContaining("CUST-FIRST-NAME")
                    .withMessageContaining("0-based position 0")
                    .satisfies(rejected -> assertThat(rejected.getMessage())
                            .doesNotContain(UNMAPPABLE_UNDER_ASCII));
        }

        @Test
        @DisplayName("encodeStrictly returns the code page's own bytes when every character maps")
        void encodeStrictlyReturnsTheCodePagesOwnBytes() {
            assertThat(FixedWidthRecord.encodeStrictly("A", EBCDIC, "a span"))
                    .containsExactly((byte) 0xC1);
            assertThat(FixedWidthRecord.encodeStrictly("A", ASCII, "a span"))
                    .containsExactly((byte) 0x41);
            assertThat(FixedWidthRecord.encodeStrictly("", ASCII, "an empty span")).isEmpty();
        }

        @Test
        @DisplayName("decodeStrictly rejects a sequence the code page does not define")
        void decodeStrictlyRejectsAnUndefinedSequence() {
            byte[] ebcdicAbc = {(byte) 0xC1, (byte) 0xC2, (byte) 0xC3};

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FixedWidthRecord.decodeStrictly(ebcdicAbc, ASCII,
                            "a CVACT02Y row image"))
                    .withMessageContaining("US-ASCII")
                    .withMessageContaining("a CVACT02Y row image")
                    .satisfies(rejected -> assertThat(rejected.getMessage())
                            .doesNotContain("\uFFFD"));

            assertThat(FixedWidthRecord.decodeStrictly(ebcdicAbc, EBCDIC, "the same row image"))
                    .isEqualTo("ABC");
        }

        @Test
        @DisplayName("decodeStrictly reports the offending byte's absolute position within the array")
        void decodeStrictlyReportsTheAbsolutePosition() {
            byte[] withBadTail = {0x41, 0x42, (byte) 0xC3};

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FixedWidthRecord.decodeStrictly(withBadTail, 1, 2, ASCII,
                            "a two-byte span at offset 1"))
                    .withMessageContaining("0-based position 2");
        }

        @Test
        @DisplayName("decodeStrictly bounds-checks its span before it decodes anything")
        void decodeStrictlyBoundsChecksItsSpan() {
            byte[] three = {0x41, 0x42, 0x43};

            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> FixedWidthRecord.decodeStrictly(three, 2, 2, ASCII, "a span"));
        }

        @Test
        @DisplayName("both helpers refuse a null charset rather than reaching for a platform default")
        void bothHelpersRefuseANullCharset() {
            assertThatNullPointerException().isThrownBy(
                    () -> FixedWidthRecord.encodeStrictly("A", null, "a span"));
            assertThatNullPointerException().isThrownBy(
                    () -> FixedWidthRecord.decodeStrictly(new byte[] {0x41}, null, "a span"));
        }

        @Test
        @DisplayName("F18 - a FieldSpan whose end offset would overflow an int is refused")
        void aFieldSpanWhoseEndOffsetWouldOverflowIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FieldSpan.alphanumeric("WIDE", Integer.MAX_VALUE, 1))
                    .withMessageContaining("2147483648")
                    .withMessageContaining("addressing limit");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FieldSpan.alphanumeric("WIDER", Integer.MAX_VALUE - 1,
                            Integer.MAX_VALUE - 1))
                    .withMessageContaining("addressing limit");
        }

        @Test
        @DisplayName("F18 - the largest representable span is accepted and reports its exact end")
        void theLargestRepresentableSpanIsAccepted() {
            FieldSpan atTheLimit = FieldSpan.alphanumeric("EDGE", Integer.MAX_VALUE - 1, 1);

            assertThat(atTheLimit.endOffsetExclusive()).isEqualTo(Integer.MAX_VALUE);
        }

        @Test
        @DisplayName("F18 - an OCCURS element whose offset would overflow an int is refused")
        void anOccursElementWhoseOffsetWouldOverflowIsRefused() {
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> FixedWidthRecord.occursElementOffsetOneBased(0, 1_500_000_000,
                            3, 3))
                    .withMessageContaining("addressing limit");

            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> FixedWidthRecord.occursElementOffsetOneBased(
                            Integer.MAX_VALUE - 10, 100, 2, 1))
                    .withMessageContaining("addressing limit");
        }

        @Test
        @DisplayName("F18 - ordinary OCCURS arithmetic is unchanged, at both ends of the table")
        void ordinaryOccursArithmeticIsUnchanged() {
            assertThat(FixedWidthRecord.occursElementOffsetOneBased(10, 4, 9, 1)).isEqualTo(10);
            assertThat(FixedWidthRecord.occursElementOffsetOneBased(10, 4, 9, 9)).isEqualTo(42);
        }
    }
}
