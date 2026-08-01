/*
 * ******************************************************************
 * Program     : StatementTransactionTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Verifies the 350-byte statement record geometry, the
 *               two-byte processing-timestamp truncation the legacy
 *               sort projection introduces, the 133-byte report line
 *               layouts, and the two legacy quirks preserved here.
 * Source      : app/cpy/COSTM01.CPY        (32-byte TRNX-KEY, 350 total)
 *               app/cpy/CVTRA07Y.cpy       (133-byte report lines)
 *               app/jcl/CREASTMT.JCL:STEP010 (the OUTREC projection)
 *               app/jcl/CREASTMT.JCL:STEP040 (LRECL 80 and 100)
 *               app/cbl/CBTRN03C.cbl       (control break and totals)
 *               app/cbl/CBSTM03A.cbl:L225-L233 (the 51x10 ceiling)
 *               frozen at commit 7756d89
 * ******************************************************************
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 * ******************************************************************
 */
package com.cardemo.unit.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.cardemo.model.dto.StatementTransaction;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Executable specification for the statement record and the report lines drawn from it.
 *
 * <p>This type is where fixed-width geometry actually matters, because its values cross a byte-exact boundary:
 * the statement outputs are written at 80 and 100 bytes per line and the transaction report at 133, and a
 * width that is wrong by one shifts every subsequent field on the line. The widths are therefore asserted as
 * <em>arithmetic identities</em> rather than as isolated numbers - the fourteen component widths must sum to
 * the declared record length, the key must equal its two parts, the remainder must equal the record less the
 * key, and each totals line's label and dots must pad to the same figure. An identity catches a
 * compensating pair of errors that a list of independent equality checks would not.
 *
 * <p><strong>The truncation is the subtle part.</strong> The upstream sort projects with
 * {@code OUTREC FIELDS=(1:263,16,17:1,262,279:279,50)}, copying fifty bytes from offset 279 back to offset
 * 279. That span covers the whole twenty-six-byte originating timestamp and only the <em>first twenty-four</em>
 * of the twenty-six processing-timestamp bytes, and it drops the trailing twenty-byte filler entirely. So the
 * projected processing timestamp arrives two characters short. That is a property of the frozen job control,
 * not a defect in the Java, and it must be reproduced rather than repaired - a "fixed" implementation would
 * differ from the legacy baseline in a way that looks like a Java bug. {@link ProjectionTruncation} pins it.
 *
 * <p><strong>Two legacy quirks are preserved deliberately.</strong> The account totals label reads
 * {@code "Account Total"} even though the control break that emits it triggers on the card number, and the
 * legacy statement program's in-memory table held at most fifty-one cards of ten transactions - a hard
 * five-hundred-and-ten ceiling with no bounds check. The label is reproduced verbatim because the report text
 * is compared byte-for-byte against the baseline; the ceiling is recorded as a constant but deliberately not
 * enforced, because streaming removes a silent truncation hazard. Both are asserted here so that neither is
 * quietly "corrected" later.
 */
@DisplayName("StatementTransaction: 350-byte geometry, a two-byte truncation and two preserved quirks")
class StatementTransactionTest {

    /**
     * A legacy timestamp at its declared 26-character width: {@code YYYY-MM-DD-HH.MM.SS.NNNNNN}.
     *
     * <p>Ten date characters, a separator, eight time characters, a separator and six fractional digits is
     * exactly 26. Writing one character more - which is easy to do by hand - trips the record's own width
     * guard and reports a fixture defect as a contract failure, so the value is stated once here and sized
     * through {@link #trimTo26(String)} at every use.
     */
    private static final String LEGACY_TIMESTAMP = "2022-06-10-19.27.53.123000";

    @Nested
    @DisplayName("1. The 350-byte record geometry closes as an arithmetic identity")
    class RecordGeometry {

        @Test
        @DisplayName("the fourteen component widths sum to the declared record length")
        void componentWidthsSumToTheRecord() {
            // 16+16+2+4+10+100+11+9+50+50+10+26+26+20 = 350. Summing rather than checking each in isolation is
            // what would catch two compensating errors, for instance a description widened by two and a filler
            // narrowed by two.
            final int sum = StatementTransaction.CARD_NUMBER_LENGTH
                    + StatementTransaction.TRANSACTION_ID_LENGTH
                    + StatementTransaction.TYPE_CODE_LENGTH
                    + StatementTransaction.CATEGORY_CODE_LENGTH
                    + StatementTransaction.SOURCE_LENGTH
                    + StatementTransaction.DESCRIPTION_LENGTH
                    + StatementTransaction.AMOUNT_LENGTH
                    + StatementTransaction.MERCHANT_ID_LENGTH
                    + StatementTransaction.MERCHANT_NAME_LENGTH
                    + StatementTransaction.MERCHANT_CITY_LENGTH
                    + StatementTransaction.MERCHANT_ZIP_LENGTH
                    + StatementTransaction.ORIGINATING_TIMESTAMP_LENGTH
                    + StatementTransaction.PROCESSING_TIMESTAMP_LENGTH
                    + StatementTransaction.FILLER_LENGTH;

            assertThat(sum).isEqualTo(StatementTransaction.RECORD_LENGTH).isEqualTo(350);
        }

        @Test
        @DisplayName("the composite key equals its two parts and matches the work cluster's declared key")
        void keyEqualsItsParts() {
            // CREASTMT.JCL's DELDEF01 defines the work cluster with KEYS(32 0), which is the independent
            // confirmation that the card number and transaction identifier together are the key.
            assertThat(StatementTransaction.CARD_NUMBER_LENGTH
                    + StatementTransaction.TRANSACTION_ID_LENGTH)
                    .isEqualTo(StatementTransaction.KEY_LENGTH)
                    .isEqualTo(32);
        }

        @Test
        @DisplayName("the remainder is the record less the key")
        void remainderIsRecordLessKey() {
            assertThat(StatementTransaction.RECORD_LENGTH - StatementTransaction.KEY_LENGTH)
                    .isEqualTo(StatementTransaction.REMAINDER_LENGTH)
                    .isEqualTo(318);
        }

        @Test
        @DisplayName("the amount precision decomposes into nine integer digits and a scale of two")
        void amountPrecisionDecomposes() {
            assertThat(StatementTransaction.AMOUNT_INTEGER_DIGITS
                    + StatementTransaction.AMOUNT_SCALE)
                    .isEqualTo(StatementTransaction.AMOUNT_PRECISION)
                    .isEqualTo(11);
            assertThat(StatementTransaction.AMOUNT_LENGTH)
                    .as("the character width and the digit precision coincide at 11 here")
                    .isEqualTo(StatementTransaction.AMOUNT_PRECISION);
        }

        @Test
        @DisplayName("the record leads with the card number, as the sort projection rearranged it")
        void recordLeadsWithTheCardNumber() {
            // The base transaction record carries the card number at offset 263; the projection moves it to
            // position 1. So the statement record's leading field is the card number, not the transaction
            // identifier, and the key's ordering follows from that rather than from the base layout.
            assertThat(StatementTransaction.BASE_CARD_NUMBER_OFFSET).isEqualTo(263);
            final StatementTransaction record = record();
            assertThat(record.cardNumber()).isEqualTo("4111111111111111");
            assertThat(new StatementTransaction.Key(record.cardNumber(), record.transactionId()).cardNumber())
                    .isEqualTo(record.cardNumber());
        }

        @Test
        @DisplayName("the two statement outputs keep their distinct record lengths")
        void statementOutputsKeepTheirLengths() {
            // CREASTMT.JCL:STEP040 declares STMTFILE at LRECL 80 and HTMLFILE at LRECL 100. The 100 is
            // independently corroborated by the legacy program's 100-character HTML emission field.
            assertThat(StatementTransaction.STATEMENT_TEXT_RECORD_LENGTH).isEqualTo(80);
            assertThat(StatementTransaction.STATEMENT_HTML_RECORD_LENGTH).isEqualTo(100);
            assertThat(StatementTransaction.STATEMENT_HTML_RECORD_LENGTH)
                    .isNotEqualTo(StatementTransaction.STATEMENT_TEXT_RECORD_LENGTH);
        }
    }

    @Nested
    @DisplayName("2. The projection truncates the processing timestamp by two characters")
    class ProjectionTruncation {

        @Test
        @DisplayName("the projected span ends where the sort's fifty-byte tail ends")
        void projectedSpanEndsWhereTheTailEnds() {
            // 279 + 50 - 1 = 328. Deriving the last written position from the sort's own operands rather than
            // restating 328 is what ties this assertion to the job control.
            assertThat(StatementTransaction.BASE_TAIL_OFFSET
                    + StatementTransaction.BASE_TAIL_LENGTH - 1)
                    .isEqualTo(StatementTransaction.PROJECTION_LAST_WRITTEN_POSITION)
                    .isEqualTo(328);
        }

        @Test
        @DisplayName("the significant and pad lengths reconstitute the full timestamp width")
        void significantPlusPadIsTheFullWidth() {
            assertThat(StatementTransaction.PROCESSING_TIMESTAMP_SIGNIFICANT_LENGTH).isEqualTo(24);
            assertThat(StatementTransaction.PROCESSING_TIMESTAMP_PAD_LENGTH).isEqualTo(2);
            assertThat(StatementTransaction.PROCESSING_TIMESTAMP_SIGNIFICANT_LENGTH
                    + StatementTransaction.PROCESSING_TIMESTAMP_PAD_LENGTH)
                    .isEqualTo(StatementTransaction.PROCESSING_TIMESTAMP_LENGTH)
                    .isEqualTo(26);
        }

        @Test
        @DisplayName("the head length plus the card number is where the projection's tail begins")
        void headAndKeyReachTheTailOffset() {
            // OUTREC writes the 16-byte card number at 1, then 262 bytes of the original head at 17, so the
            // next untouched position is 17 + 262 = 279, which is exactly where the tail operand starts. The
            // three operands therefore tile without a gap, and that is worth proving rather than assuming.
            assertThat(StatementTransaction.CARD_NUMBER_LENGTH + 1
                    + StatementTransaction.BASE_HEAD_LENGTH)
                    .isEqualTo(StatementTransaction.BASE_TAIL_OFFSET)
                    .isEqualTo(279);
        }

        @Test
        @DisplayName("the significant timestamp is the first twenty-four characters of the stored value")
        void significantTimestampDropsTheLastTwoCharacters() {
            final StatementTransaction record = record();

            assertThat(record.processingTimestamp()).hasSize(26);
            assertThat(record.significantProcessingTimestamp())
                    .as("the projection copied only 24 of the 26 characters")
                    .hasSize(StatementTransaction.PROCESSING_TIMESTAMP_SIGNIFICANT_LENGTH)
                    .isEqualTo(record.processingTimestamp().substring(0, 24));
        }

        @Test
        @DisplayName("the dropped characters are the trailing two, not the leading two")
        void theDroppedCharactersAreTrailing() {
            // Which end is truncated decides whether the century or the sub-second digits are lost. Asserting
            // the surviving prefix rather than merely its length is what distinguishes the two.
            final StatementTransaction record = recordWithProcessingTimestamp(LEGACY_TIMESTAMP);

            assertThat(record.significantProcessingTimestamp()).startsWith("2022-06-10-19.27.53");
        }

        @Test
        @DisplayName("a null processing timestamp yields null rather than throwing")
        void nullTimestampYieldsNull() {
            assertThat(recordWithProcessingTimestamp(null).significantProcessingTimestamp()).isNull();
        }

        @Test
        @DisplayName("the twenty-byte filler is the part the projection drops entirely")
        void fillerIsDroppedEntirely() {
            // 331 through 350 lies wholly beyond the last written position of 328, so the filler is not
            // partially copied - it is absent. The record still models it, because the field exists in the
            // layout even when the projection does not populate it.
            assertThat(StatementTransaction.PROJECTION_LAST_WRITTEN_POSITION)
                    .isLessThan(StatementTransaction.RECORD_LENGTH
                            - StatementTransaction.FILLER_LENGTH + 1);
            assertThat(StatementTransaction.FILLER_LENGTH).isEqualTo(20);
        }
    }

    @Nested
    @DisplayName("3. Amount comparison uses value equality, never representational equality")
    class AmountComparison {

        @ParameterizedTest(name = "{0} and {1} are the same amount")
        @CsvSource({
            "194.00, 194.00",
            "194.00, 194.0",
            "194.00, 194",
            "0.00,   0",
            "-194.00, -194.0",
        })
        @DisplayName("the same value at a different scale compares equal")
        void sameValueDifferentScaleIsEqual(final String stored, final String other) {
            // BigDecimal.equals() distinguishes 194.00 from 194.0; compareTo() does not. Money comparisons
            // must use the latter, and this is the case that tells which one the implementation used.
            assertThat(recordWithAmount(new BigDecimal(stored)).hasSameAmountAs(new BigDecimal(other)))
                    .isTrue();
        }

        @ParameterizedTest(name = "{0} and {1} are different amounts")
        @CsvSource({
            "194.00, 194.01",
            "194.00, -194.00",
            "0.00,   0.01",
        })
        @DisplayName("genuinely different values compare unequal")
        void differentValuesAreUnequal(final String stored, final String other) {
            assertThat(recordWithAmount(new BigDecimal(stored)).hasSameAmountAs(new BigDecimal(other)))
                    .isFalse();
        }

        @Test
        @DisplayName("a negative amount is carried unaltered, because the fixtures contain them")
        void negativeAmountIsCarried() {
            assertThat(recordWithAmount(new BigDecimal("-194.00")).amount().signum()).isNegative();
        }
    }

    @Nested
    @DisplayName("4. The 133-byte report lines and their totals geometry")
    class ReportLines {

        @Test
        @DisplayName("the report line is 133 bytes, as the report dataset declares")
        void reportLineIs133Bytes() {
            assertThat(StatementTransaction.REPORT_LINE_LENGTH).isEqualTo(133);
        }

        @ParameterizedTest(name = "the {0} line's label and dots pad to 97")
        @CsvSource({
            "page,    11, 86",
            "account, 13, 84",
            "grand,   11, 86",
        })
        @DisplayName("all three totals lines pad their label and dots to the same width")
        void totalsLabelsPadToTheSameWidth(final String which, final int labelWidth, final int dotsWidth) {
            // The three labels differ in length, so the dot runs differ to compensate; the sum is invariant.
            // That invariant is what keeps the amount column aligned across the three kinds of totals line.
            assertThat(labelWidth + dotsWidth)
                    .as("the %s line", which)
                    .isEqualTo(StatementTransaction.REPORT_TOTALS_LABEL_PLUS_DOTS_LENGTH)
                    .isEqualTo(97);
        }

        @Test
        @DisplayName("the totals line is the padded label and dots plus the amount mask")
        void totalsLineIsLabelPlusDotsPlusMask() {
            assertThat(StatementTransaction.REPORT_TOTALS_LABEL_PLUS_DOTS_LENGTH
                    + StatementTransaction.AMOUNT_MASK_LENGTH)
                    .isEqualTo(StatementTransaction.REPORT_TOTALS_LINE_LENGTH)
                    .isEqualTo(112);
        }

        @Test
        @DisplayName("each factory carries its own label and dot run")
        void factoriesCarryTheirOwnGeometry() {
            final BigDecimal total = new BigDecimal("1940.00");

            final StatementTransaction.ReportTotalsLine page = StatementTransaction.ReportTotalsLine.pageTotal(total);
            assertThat(page.label()).isEqualTo(StatementTransaction.PAGE_TOTAL_LABEL);
            assertThat(page.labelWidth()).isEqualTo(StatementTransaction.PAGE_TOTAL_LABEL_LENGTH);
            assertThat(page.dotsWidth()).isEqualTo(StatementTransaction.PAGE_TOTAL_DOTS_LENGTH);

            final StatementTransaction.ReportTotalsLine account =
                    StatementTransaction.ReportTotalsLine.accountTotal(total);
            assertThat(account.label()).isEqualTo(StatementTransaction.ACCOUNT_TOTAL_LABEL);
            assertThat(account.labelWidth()).isEqualTo(StatementTransaction.ACCOUNT_TOTAL_LABEL_LENGTH);
            assertThat(account.dotsWidth()).isEqualTo(StatementTransaction.ACCOUNT_TOTAL_DOTS_LENGTH);

            final StatementTransaction.ReportTotalsLine grand =
                    StatementTransaction.ReportTotalsLine.grandTotal(total);
            assertThat(grand.label()).isEqualTo(StatementTransaction.GRAND_TOTAL_LABEL);
            assertThat(grand.labelWidth()).isEqualTo(StatementTransaction.GRAND_TOTAL_LABEL_LENGTH);
            assertThat(grand.dotsWidth()).isEqualTo(StatementTransaction.GRAND_TOTAL_DOTS_LENGTH);
        }

        @Test
        @DisplayName("every factory's own label and dots satisfy the padding invariant")
        void everyFactorySatisfiesTheInvariant() {
            final BigDecimal total = new BigDecimal("1940.00");

            for (final StatementTransaction.ReportTotalsLine line : List.of(
                    StatementTransaction.ReportTotalsLine.pageTotal(total),
                    StatementTransaction.ReportTotalsLine.accountTotal(total),
                    StatementTransaction.ReportTotalsLine.grandTotal(total))) {
                assertThat(line.labelWidth() + line.dotsWidth())
                        .as("the %s line built by its factory", line.label())
                        .isEqualTo(StatementTransaction.REPORT_TOTALS_LABEL_PLUS_DOTS_LENGTH);
            }
        }

        @Test
        @DisplayName("the two amount masks are the same width but differ in their sign position")
        void masksDifferOnlyInSign() {
            // The detail mask leads with a minus and the totals mask with a plus, which is a real difference in
            // the legacy output rather than an inconsistency: a detail amount may be negative, a total is
            // rendered with an explicit sign either way.
            assertThat(StatementTransaction.DETAIL_AMOUNT_MASK)
                    .hasSize(StatementTransaction.AMOUNT_MASK_LENGTH).startsWith("-");
            assertThat(StatementTransaction.TOTALS_AMOUNT_MASK)
                    .hasSize(StatementTransaction.AMOUNT_MASK_LENGTH).startsWith("+");
            assertThat(StatementTransaction.DETAIL_AMOUNT_MASK)
                    .isNotEqualTo(StatementTransaction.TOTALS_AMOUNT_MASK);
            assertThat(StatementTransaction.DETAIL_AMOUNT_MASK.substring(1))
                    .as("the two masks are identical after the sign")
                    .isEqualTo(StatementTransaction.TOTALS_AMOUNT_MASK.substring(1));
        }

        @Test
        @DisplayName("a detail line carries its code-and-description pairs")
        void detailLineCarriesCodeAndDescription() {
            final StatementTransaction.ReportDetailLine line = new StatementTransaction.ReportDetailLine(
                    "TXN0000000000001", "00000000001", "01", "PURCHASE", "0001",
                    "GENERAL MERCHANDISE", "POS", new BigDecimal("194.00"));

            assertThat(line.transactionId()).isEqualTo("TXN0000000000001");
            assertThat(line.accountId()).isEqualTo("00000000001");
            assertThat(line.typeCode()).isEqualTo("01");
            assertThat(line.typeDescription()).isEqualTo("PURCHASE");
            assertThat(line.categoryCode()).isEqualTo("0001");
            assertThat(line.source()).isEqualTo("POS");
            assertThat(line.amount()).isEqualByComparingTo(new BigDecimal("194.00"));
        }

        @Test
        @DisplayName("the detail line normalises its amount to the declared scale")
        void detailLineNormalisesTheAmountScale() {
            // The report renders into a fixed two-decimal mask, so an amount arriving at another scale has to
            // be normalised before it can be written; leaving it alone would shift the decimal point.
            final StatementTransaction.ReportDetailLine line = new StatementTransaction.ReportDetailLine(
                    "TXN0000000000001", "00000000001", "01", "PURCHASE", "0001",
                    "GENERAL MERCHANDISE", "POS", new BigDecimal("194"));

            assertThat(line.amount().scale()).isEqualTo(StatementTransaction.AMOUNT_SCALE);
            assertThat(line.amount()).isEqualByComparingTo(new BigDecimal("194.00"));
        }

        @Test
        @DisplayName("the report column widths sum within the 133-byte line")
        void reportColumnWidthsFitTheLine() {
            final int columns = StatementTransaction.REPORT_TRANSACTION_ID_LENGTH
                    + StatementTransaction.REPORT_ACCOUNT_ID_LENGTH
                    + StatementTransaction.REPORT_TYPE_CODE_LENGTH
                    + StatementTransaction.REPORT_TYPE_DESCRIPTION_LENGTH
                    + StatementTransaction.REPORT_CATEGORY_CODE_LENGTH
                    + StatementTransaction.REPORT_CATEGORY_DESCRIPTION_LENGTH
                    + StatementTransaction.REPORT_SOURCE_LENGTH
                    + StatementTransaction.AMOUNT_MASK_LENGTH;

            assertThat(columns)
                    .as("the eight detail columns must fit inside the 133-byte report line")
                    .isLessThanOrEqualTo(StatementTransaction.REPORT_LINE_LENGTH);
            assertThat(StatementTransaction.REPORT_DETAIL_LINE_LENGTH)
                    .isLessThanOrEqualTo(StatementTransaction.REPORT_LINE_LENGTH);
        }
    }

    @Nested
    @DisplayName("5. The two preserved legacy quirks")
    class PreservedQuirks {

        @Test
        @DisplayName("the account totals label reads 'Account Total' although the break is on the card number")
        void accountTotalLabelIsPreservedVerbatim() {
            // The control break in the report program triggers on the card number while the emitted label says
            // "Account Total". That mismatch is legacy behaviour and the report text is compared byte-for-byte
            // against the baseline, so the label is reproduced exactly. Renaming it to "Card Total" would be a
            // defensible correction and an unmistakable parity failure.
            assertThat(StatementTransaction.ACCOUNT_TOTAL_LABEL)
                    .isEqualTo("Account Total")
                    .doesNotContain("Card");
            assertThat(StatementTransaction.ReportTotalsLine.accountTotal(BigDecimal.ZERO).label())
                    .isEqualTo("Account Total");
        }

        @Test
        @DisplayName("the legacy in-memory ceiling is recorded and its arithmetic preserved")
        void legacyCeilingIsRecorded() {
            // 51 cards x 10 transactions = 510, with no bounds check anywhere in the legacy building loop.
            // The constant is kept as the historical capacity limit even though the Java streams instead, so
            // the removed hazard stays documented rather than becoming invisible.
            assertThat(StatementTransaction.LEGACY_MAX_CARDS_PER_RUN).isEqualTo(51);
            assertThat(StatementTransaction.LEGACY_MAX_TRANSACTIONS_PER_CARD).isEqualTo(10);
            assertThat(StatementTransaction.LEGACY_MAX_CARDS_PER_RUN
                    * StatementTransaction.LEGACY_MAX_TRANSACTIONS_PER_CARD)
                    .isEqualTo(StatementTransaction.LEGACY_MAX_TRANSACTIONS_PER_RUN)
                    .isEqualTo(510);
        }

        @Test
        @DisplayName("the ceiling is documented but not enforced, because the Java streams instead")
        void ceilingIsNotEnforced() {
            // The deliberate deviation: exceeding the legacy ceiling must not throw, because removing the
            // silent overrun is the point. Enforcing it here would reintroduce the very limitation the
            // migration set out to lift.
            final List<StatementTransaction> beyondCeiling = new java.util.ArrayList<>();
            for (int index = 0; index <= StatementTransaction.LEGACY_MAX_TRANSACTIONS_PER_CARD; index++) {
                beyondCeiling.add(record());
            }

            final StatementTransaction.CardGroup group =
                    new StatementTransaction.CardGroup("4111111111111111", beyondCeiling);

            assertThat(group.transactions())
                    .hasSize(StatementTransaction.LEGACY_MAX_TRANSACTIONS_PER_CARD + 1)
                    .as("one more than the legacy ceiling is accepted, not refused");
        }

        @Test
        @DisplayName("the report short and long names are carried verbatim with their declared widths")
        void reportNamesAreVerbatim() {
            assertThat(StatementTransaction.REPORT_SHORT_NAME).isEqualTo("DALYREPT");
            assertThat(StatementTransaction.REPORT_LONG_NAME).isEqualTo("Daily Transaction Report");
            assertThat(StatementTransaction.REPORT_DATE_HEADER).isEqualTo("Date Range: ");
            assertThat(StatementTransaction.REPORT_DATE_SEPARATOR).isEqualTo(" to ");
            assertThat(StatementTransaction.REPORT_DATE_LENGTH).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("6. The nested key and group types")
    class NestedTypes {

        @Test
        @DisplayName("the key carries its two parts and refuses an over-width component")
        void keyCarriesItsParts() {
            final StatementTransaction.Key key =
                    new StatementTransaction.Key("4111111111111111", "TXN0000000000001");

            assertThat(key.cardNumber()).hasSize(StatementTransaction.CARD_NUMBER_LENGTH);
            assertThat(key.transactionId()).hasSize(StatementTransaction.TRANSACTION_ID_LENGTH);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new StatementTransaction.Key(
                            "x".repeat(StatementTransaction.CARD_NUMBER_LENGTH + 1), "TXN0000000000001"));
        }

        @Test
        @DisplayName("two keys with the same parts are equal, as a record's value semantics require")
        void keysWithSamePartsAreEqual() {
            assertThat(new StatementTransaction.Key("4111111111111111", "TXN0000000000001"))
                    .isEqualTo(new StatementTransaction.Key("4111111111111111", "TXN0000000000001"))
                    .hasSameHashCodeAs(new StatementTransaction.Key("4111111111111111", "TXN0000000000001"));
        }

        @Test
        @DisplayName("a card group exposes an unmodifiable transaction list")
        void cardGroupListIsUnmodifiable() {
            final List<StatementTransaction> transactions =
                    new StatementTransaction.CardGroup("4111111111111111", List.of(record())).transactions();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> transactions.add(record()));
        }

        @Test
        @DisplayName("a card group accepts an empty transaction list")
        void cardGroupAcceptsNoTransactions() {
            // A card with no transactions in the period is an ordinary outcome, so it must not be an error.
            assertThat(new StatementTransaction.CardGroup("4111111111111111", List.of()).transactions())
                    .isEmpty();
        }

        @ParameterizedTest(name = "the {0} factory accepts a null total")
        @ValueSource(strings = {"page", "account", "grand"})
        @DisplayName("a null total is accepted, because a period with no activity still prints a totals line")
        void totalsLineAcceptsANullTotal(final String which) {
            // Refusing null here would make an empty reporting period unprintable, so it is permitted and
            // carried through normalisation unchanged rather than coerced to zero - zero and "no activity" are
            // different statements about the period.
            final StatementTransaction.ReportTotalsLine line = switch (which) {
                case "page" -> StatementTransaction.ReportTotalsLine.pageTotal(null);
                case "account" -> StatementTransaction.ReportTotalsLine.accountTotal(null);
                default -> StatementTransaction.ReportTotalsLine.grandTotal(null);
            };

            assertThat(line.total()).isNull();
            assertThat(line.labelWidth() + line.dotsWidth())
                    .isEqualTo(StatementTransaction.REPORT_TOTALS_LABEL_PLUS_DOTS_LENGTH);
        }

        @Test
        @DisplayName("a totals line refuses a null label")
        void totalsLineRefusesANullLabel() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new StatementTransaction.ReportTotalsLine(
                            null, 11, 86, BigDecimal.ZERO))
                    .withMessageContaining("label");
        }

        @Test
        @DisplayName("a totals line enforces the padding invariant rather than merely documenting it")
        void totalsLineEnforcesThePaddingInvariant() {
            // The label-plus-dots sum is a constructor precondition, so a layout that would misalign the amount
            // column cannot be built at all. Off by one in either direction is refused.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new StatementTransaction.ReportTotalsLine(
                            StatementTransaction.PAGE_TOTAL_LABEL,
                            StatementTransaction.PAGE_TOTAL_LABEL_LENGTH,
                            StatementTransaction.PAGE_TOTAL_DOTS_LENGTH + 1, BigDecimal.ZERO))
                    .withMessageContaining(String.valueOf(
                            StatementTransaction.REPORT_TOTALS_LABEL_PLUS_DOTS_LENGTH));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new StatementTransaction.ReportTotalsLine(
                            StatementTransaction.PAGE_TOTAL_LABEL,
                            StatementTransaction.PAGE_TOTAL_LABEL_LENGTH,
                            StatementTransaction.PAGE_TOTAL_DOTS_LENGTH - 1, BigDecimal.ZERO));
        }

        @Test
        @DisplayName("only the three declared label-and-width layouts are accepted, even if the sum is right")
        void onlyDeclaredLayoutsAreAccepted() {
            // The stronger of the two guards: satisfying the arithmetic is not sufficient. Pairing the page
            // label with the account label's width sums to 97 and is still refused, because that combination
            // appears nowhere in the copybook. Without this check an invented layout would pass silently.
            assertThat(StatementTransaction.ACCOUNT_TOTAL_LABEL_LENGTH
                    + StatementTransaction.ACCOUNT_TOTAL_DOTS_LENGTH)
                    .as("the substituted pair does satisfy the arithmetic")
                    .isEqualTo(StatementTransaction.REPORT_TOTALS_LABEL_PLUS_DOTS_LENGTH);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new StatementTransaction.ReportTotalsLine(
                            StatementTransaction.PAGE_TOTAL_LABEL,
                            StatementTransaction.ACCOUNT_TOTAL_LABEL_LENGTH,
                            StatementTransaction.ACCOUNT_TOTAL_DOTS_LENGTH, BigDecimal.ZERO))
                    .withMessageContaining("totals layout");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("an entirely invented label is refused too")
                    .isThrownBy(() -> new StatementTransaction.ReportTotalsLine(
                            "Card Total", 11, 86, BigDecimal.ZERO));
        }
    }

    // ----------------------------------------------------------------------------------------------------
    // Fixtures
    // ----------------------------------------------------------------------------------------------------

    /** @return a fully populated statement record with a 26-character processing timestamp. */
    private static StatementTransaction record() {
        return build(new BigDecimal("194.00"), LEGACY_TIMESTAMP);
    }

    /**
     * @param amount the amount to carry
     * @return a statement record carrying it
     */
    private static StatementTransaction recordWithAmount(final BigDecimal amount) {
        return build(amount, LEGACY_TIMESTAMP);
    }

    /**
     * @param processingTimestamp the processing timestamp to carry
     * @return a statement record carrying it
     */
    private static StatementTransaction recordWithProcessingTimestamp(final String processingTimestamp) {
        return build(new BigDecimal("194.00"), processingTimestamp);
    }

    private static StatementTransaction build(final BigDecimal amount, final String processingTimestamp) {
        return new StatementTransaction("4111111111111111", "TXN0000000000001", "01", "0001", "POS",
                "PURCHASE AT MERCHANT ONE", amount, "000000001", "MERCHANT ONE", "SEATTLE", "98101",
                trimTo26(LEGACY_TIMESTAMP),
                processingTimestamp == null ? null : trimTo26(processingTimestamp), null);
    }

    /**
     * Returns a timestamp forced to the declared 26-character width.
     *
     * <p>The fixtures are written out in full for legibility, and a stray character would otherwise trip the
     * record's own width guard and report a fixture defect as a contract failure.
     *
     * @param timestamp the timestamp to size
     * @return exactly {@link StatementTransaction#PROCESSING_TIMESTAMP_LENGTH} characters
     */
    private static String trimTo26(final String timestamp) {
        final int width = StatementTransaction.PROCESSING_TIMESTAMP_LENGTH;
        return timestamp.length() >= width
                ? timestamp.substring(0, width)
                : timestamp + " ".repeat(width - timestamp.length());
    }
}
