/*
 * ******************************************************************
 * Program     : StatementAndRemainingDtoTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/model
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live endpoint
 * Function    : Pins the statement and report record geometries - the
 *               32-byte TRNX-KEY, the 350-byte record, the 133-byte
 *               report line, the 80 and 100 byte statement outputs - and
 *               the CREASTMT projection's 24-significant-character
 *               processing timestamp. Also pins the legacy 510
 *               transaction capacity ceiling as a recorded historical
 *               limit, and closes the remaining request DTOs.
 * Source      : app/cpy/COSTM01.CPY (32-byte TRNX-KEY, 350-byte record) @ 7756d89
 * Source      : app/cpy/CVTRA07Y.cpy (133-byte report lines) @ 7756d89
 * Source      : app/jcl/CREASTMT.JCL:STEP010 / STEP040 @ 7756d89
 * Source      : app/cbl/CBSTM03A.CBL:L225-L233 (51 x 10 table) @ 7756d89
 * Source      : app/cbl/CBTRN03C.cbl (control break, "Account Total") @ 7756d89
 * Source      : app/cpy-bms/COACTVW.CPY (36 fields) @ 7756d89
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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License
 * ******************************************************************
 */
package com.cardemo.unit.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.cardemo.model.dto.AccountDto;
import com.cardemo.model.dto.BillPaymentRequest;
import com.cardemo.model.dto.CardDto;
import com.cardemo.model.dto.CardUpdateRequest;
import com.cardemo.model.dto.CommArea;
import com.cardemo.model.dto.MenuResponse;
import com.cardemo.model.dto.StatementTransaction;
import com.cardemo.model.dto.TransactionAddRequest;
import com.cardemo.model.dto.TransactionDto;
import com.cardemo.model.enums.UserType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test for {@link StatementTransaction}, {@link AccountDto} and the remaining request DTOs.
 *
 * <h2>1. What this test does</h2>
 *
 * <p>{@link StatementTransaction} is the carrier for the statement and report pipeline, and it is where three
 * separate legacy geometries meet. Each is asserted by <strong>derivation</strong> so the arithmetic has to
 * close:
 *
 * <ul>
 *   <li>The 32-byte {@code TRNX-KEY} is {@code 16 card number + 16 transaction id}, which is exactly the
 *       {@code KEYS(32 0)} declared on the work cluster at {@code app/jcl/CREASTMT.JCL:DELDEF01}. Two
 *       independent sources agree.</li>
 *   <li>The 350-byte record is {@code 32 key + 318 remainder}.</li>
 *   <li>The projection writes its last byte at position {@code 16 + 262 + 50 = 328}, so 22 of the 350 input
 *       bytes are dropped - the 20-byte filler plus a 2-byte timestamp truncation.</li>
 *   </ul>
 *
 * <h3>The truncation is preserved, not corrected</h3>
 *
 * <p>Because {@code OUTREC FIELDS=(279:279,50)} copies 50 bytes across a 52-byte timestamp pair, the
 * processing timestamp survives with only <strong>24 significant characters</strong>. The DTO models this
 * explicitly rather than leaving it implicit, and {@code significantProcessingTimestamp()} is the accessor
 * that reproduces it. A Java projection that copied all 26 would differ from the legacy baseline by two bytes
 * per record.
 *
 * <h3>The capacity ceiling is recorded, not reimposed</h3>
 *
 * <p>{@code app/cbl/CBSTM03A.CBL:L225-L233} declares a fixed table of 51 card entries holding 10 transactions
 * each - a hard ceiling of 510 transactions per run, incremented with <strong>no bounds check whatsoever</strong>.
 * The Java implementation streams and therefore has no ceiling, which removes a silent storage-overrun hazard.
 * That is a deliberate, labelled deviation rather than parity, so the constants exist to <em>document</em> the
 * historical limit and this test asserts they record it faithfully - it does not assert that the limit is
 * enforced, because it deliberately is not.
 *
 * <h3>"Account Total" is a preserved mislabel</h3>
 *
 * <p>{@code app/cbl/CBTRN03C.cbl} breaks on the <strong>card number</strong> while emitting a label reading
 * "Account Total". The label is preserved verbatim because the report baseline contains it; correcting it
 * would be a behaviour change.
 *
 * <h2>2. How to run it</h2>
 *
 * <pre>{@code
 * ./mvnw -B -ntp -o test -Dtest=StatementAndRemainingDtoTest
 * }</pre>
 *
 * <h2>3. Configuration and defaults</h2>
 *
 * <p>None.
 *
 * <h2>4. Failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>A geometry derivation fails.</strong> A width changed; the sort specifications address these
 *       positions absolutely, so recompute against the copybook.</li>
 *   <li><strong>The totals-layout guard fails.</strong> A label or its width changed. All three totals lines
 *       must reach the same 97-character label-plus-dots width or the report columns misalign.</li>
 *   <li><strong>A row-count guard fails.</strong> A page was allowed to exceed its screen's row slots.</li>
 * </ul>
 */
class StatementAndRemainingDtoTest {

    private static final String TS_26 = "2024-01-01-12.30.45.120000";

    private static StatementTransaction statement() {
        return new StatementTransaction("4111999988887777", "0000000000000001", "01", "0005",
                "POS TERM", "Regular Sales Draft", new BigDecimal("100.00"), "000000009",
                "MERCHANT NAME", "MERCHANT CITY", "12345", TS_26, TS_26, " ".repeat(20));
    }

    /**
     * Builds a transaction list page carrying {@code rows} and nothing else.
     *
     * <p>{@link TransactionDto} has 24 components with {@code rows} at index 22 and {@code amountValue} at
     * index 23, both verified by reflection rather than counted by eye. Threading every construction through
     * one helper is what keeps a 24-argument call from silently drifting an argument out of position - a
     * mistake the compiler cannot catch when every component is a nullable reference type.
     *
     * @param rows the row list under test, possibly over-long or containing a null
     * @return the page
     */
    private static TransactionDto page(final List<TransactionDto.TransactionListRow> rows) {
        return new TransactionDto(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, rows, null);
    }

    @Nested
    @DisplayName("the statement record geometry: 32-byte key, 350-byte record, 328-byte projection")
    class StatementGeometry {

        @Test
        @DisplayName("the 32-byte TRNX-KEY is derived and agrees with the work cluster's KEYS(32 0)")
        void theKeyIsDerivedAndAgreesWithTheCluster() {
            assertThat(StatementTransaction.CARD_NUMBER_LENGTH
                    + StatementTransaction.TRANSACTION_ID_LENGTH)
                    .as("app/cpy/COSTM01.CPY composes TRNX-KEY from a 16-byte card number and a 16-byte "
                            + "transaction id. app/jcl/CREASTMT.JCL:DELDEF01 independently declares "
                            + "KEYS(32 0) on the work cluster, so the copybook and the job control AGREE "
                            + "- the key width is confirmed by two sources, not one")
                    .isEqualTo(32)
                    .isEqualTo(StatementTransaction.KEY_LENGTH);
        }

        @Test
        @DisplayName("the 350-byte record is derived as the 32-byte key plus a 318-byte remainder")
        void theRecordIsKeyPlusRemainder() {
            assertThat(StatementTransaction.KEY_LENGTH + StatementTransaction.REMAINDER_LENGTH)
                    .as("32 + 318 = 350, matching both the catalogued TRANSACT record length and the "
                            + "RECORDSIZE(350 350) on the statement work cluster")
                    .isEqualTo(350)
                    .isEqualTo(StatementTransaction.RECORD_LENGTH);
            assertThat(StatementTransaction.REMAINDER_LENGTH).isEqualTo(318);
        }

        @Test
        @DisplayName("the projection writes its last byte at 328, dropping 22 of the 350")
        void theProjectionWritesItsLastByteAt328() {
            final int derived = StatementTransaction.CARD_NUMBER_LENGTH
                    + StatementTransaction.BASE_HEAD_LENGTH
                    + StatementTransaction.BASE_TAIL_LENGTH;

            assertThat(StatementTransaction.BASE_CARD_NUMBER_OFFSET)
                    .as("OUTREC element 1:263,16 reads the card number from offset 263")
                    .isEqualTo(263);
            assertThat(StatementTransaction.BASE_HEAD_LENGTH)
                    .as("element 17:1,262 copies the first 262 bytes of the original record")
                    .isEqualTo(262);
            assertThat(StatementTransaction.BASE_TAIL_OFFSET).isEqualTo(279);
            assertThat(StatementTransaction.BASE_TAIL_LENGTH)
                    .as("element 279:279,50 copies FIFTY bytes across a 52-byte timestamp pair - which is "
                            + "where the truncation comes from")
                    .isEqualTo(50);
            assertThat(derived)
                    .as("16 + 262 + 50 = 328 is the last written position")
                    .isEqualTo(328)
                    .isEqualTo(StatementTransaction.PROJECTION_LAST_WRITTEN_POSITION);
            assertThat(StatementTransaction.RECORD_LENGTH - derived)
                    .as("350 - 328 = 22 dropped bytes, accounted for exactly by the 20-byte FILLER plus "
                            + "the 2-byte timestamp truncation. The two losses summing to the difference "
                            + "is what proves the projection is understood rather than guessed")
                    .isEqualTo(22)
                    .isEqualTo(StatementTransaction.FILLER_LENGTH
                            + StatementTransaction.PROCESSING_TIMESTAMP_PAD_LENGTH);
        }

        @Test
        @DisplayName("the processing timestamp keeps 24 significant characters plus a 2-byte pad")
        void theProcessingTimestampKeeps24Significant() {
            assertThat(StatementTransaction.PROCESSING_TIMESTAMP_SIGNIFICANT_LENGTH
                    + StatementTransaction.PROCESSING_TIMESTAMP_PAD_LENGTH)
                    .as("24 significant + 2 pad = the declared 26-byte field. The projected value arrives "
                            + "24 characters long and is padded back out to 26, so the field width is "
                            + "preserved while the CONTENT is two characters short")
                    .isEqualTo(26)
                    .isEqualTo(StatementTransaction.PROCESSING_TIMESTAMP_LENGTH);
            assertThat(StatementTransaction.ORIGINATING_TIMESTAMP_LENGTH)
                    .as("the ORIGINATING timestamp is untouched at a full 26 bytes, because it sits "
                            + "entirely within the 50 copied bytes")
                    .isEqualTo(26);
        }

        @Test
        @DisplayName("significantProcessingTimestamp truncates a 26-character value to 24")
        void significantProcessingTimestampTruncatesTo24() {
            assertThat(statement().significantProcessingTimestamp())
                    .as("this accessor reproduces the projection's loss explicitly. Leaving it implicit "
                            + "would mean a caller comparing against the legacy baseline would see a "
                            + "two-byte diff with no obvious cause")
                    .hasSize(24)
                    .isEqualTo(TS_26.substring(0, 24));
        }

        @Test
        @DisplayName("a shorter or absent timestamp passes through unchanged")
        void aShorterTimestampPassesThroughUnchanged() {
            final StatementTransaction shortTs = new StatementTransaction("4111999988887777",
                    "0000000000000001", "01", "0005", null, null, null, null, null, null, null, null,
                    "2024-01-01", null);

            assertThat(shortTs.significantProcessingTimestamp())
                    .as("a value already within 24 characters must not be padded or altered - the "
                            + "accessor only ever removes, never adds")
                    .isEqualTo("2024-01-01");

            final StatementTransaction nullTs = new StatementTransaction("4111999988887777",
                    "0000000000000001", null, null, null, null, null, null, null, null, null, null,
                    null, null);
            assertThat(nullTs.significantProcessingTimestamp())
                    .as("an absent timestamp stays absent rather than becoming an empty string")
                    .isNull();
        }

        @Test
        @DisplayName("the statement outputs are 80 and 100 bytes per line")
        void theStatementOutputsAre80And100() {
            assertThat(StatementTransaction.STATEMENT_TEXT_RECORD_LENGTH)
                    .as("app/jcl/CREASTMT.JCL:STEP040 declares STMTFILE at LRECL=80")
                    .isEqualTo(80);
            assertThat(StatementTransaction.STATEMENT_HTML_RECORD_LENGTH)
                    .as("and HTMLFILE at LRECL=100, independently confirmed by the 100-character field "
                            + "the HTML fragments are written through at app/cbl/CBSTM03A.CBL:L149. The "
                            + "80-versus-100 mismatch between that job's pre-delete and execution steps "
                            + "is a logged legacy defect, not a signal to change this width")
                    .isEqualTo(100);
        }

        @Test
        @DisplayName("all fourteen record components round-trip through their accessors")
        void allFourteenComponentsRoundTrip() {
            final StatementTransaction record = statement();

            assertThat(record.cardNumber()).isEqualTo("4111999988887777");
            assertThat(record.transactionId()).isEqualTo("0000000000000001");
            assertThat(record.typeCode()).isEqualTo("01");
            assertThat(record.categoryCode()).isEqualTo("0005");
            assertThat(record.source()).isEqualTo("POS TERM");
            assertThat(record.description()).isEqualTo("Regular Sales Draft");
            assertThat(record.amount()).isEqualByComparingTo("100.00");
            assertThat(record.merchantId()).isEqualTo("000000009");
            assertThat(record.merchantName()).isEqualTo("MERCHANT NAME");
            assertThat(record.merchantCity()).isEqualTo("MERCHANT CITY");
            assertThat(record.merchantZip()).isEqualTo("12345");
            assertThat(record.originatingTimestamp()).isEqualTo(TS_26);
            assertThat(record.processingTimestamp()).isEqualTo(TS_26);
            assertThat(record.filler())
                    .as("the 20-byte FILLER is modelled here, unlike on the entity, because the statement "
                            + "pipeline emits fixed-width records and must reproduce those bytes")
                    .hasSize(20);
        }

        @Test
        @DisplayName("the description is the full persisted 100 characters, not a screen width")
        void theDescriptionIsTheFullPersistedWidth() {
            assertThat(StatementTransaction.DESCRIPTION_LENGTH)
                    .as("this carrier holds the RECORD, so the description is the persisted PIC X(100) - "
                            + "not the 26 or 60 character screen projections")
                    .isEqualTo(100);
            assertThat(StatementTransaction.MERCHANT_NAME_LENGTH)
                    .as("likewise the merchant name is the persisted 50, not the screen's 30")
                    .isEqualTo(50);
            assertThat(StatementTransaction.MERCHANT_CITY_LENGTH).isEqualTo(50);
        }

        @Test
        @DisplayName("a component exceeding its declared width is rejected")
        void aComponentExceedingItsWidthIsRejected() {
            assertThatIllegalArgumentException()
                    .as("the card number is PIC X(16)")
                    .isThrownBy(() -> new StatementTransaction("1".repeat(17), null, null, null, null,
                            null, null, null, null, null, null, null, null, null))
                    .withMessageContaining("cardNumber");
        }

        @Test
        @DisplayName("hasSameAmountAs compares by value, so scale differences do not register")
        void hasSameAmountAsComparesByValue() {
            final StatementTransaction record = statement();

            assertThat(record.hasSameAmountAs(new BigDecimal("100.00"))).isTrue();
            assertThat(record.hasSameAmountAs(new BigDecimal("100.0")))
                    .as("compareTo is scale-insensitive, so 100.0 matches 100.00. Using equals here would "
                            + "report a mismatch on a trailing zero, which is why the DTO offers this "
                            + "helper rather than leaving callers to pick a comparison")
                    .isTrue();
            assertThat(record.hasSameAmountAs(new BigDecimal("100.01"))).isFalse();
            assertThat(record.hasSameAmountAs(null))
                    .as("a present amount never matches an absent one")
                    .isFalse();

            final StatementTransaction noAmount = new StatementTransaction("4111999988887777",
                    "0000000000000001", null, null, null, null, null, null, null, null, null, null,
                    null, null);
            assertThat(noAmount.hasSameAmountAs(null))
                    .as("two absent amounts DO match, so the comparison is total rather than throwing")
                    .isTrue();
            assertThat(noAmount.hasSameAmountAs(BigDecimal.ZERO))
                    .as("but an absent amount is not zero - the distinction survives")
                    .isFalse();
        }

        @Test
        @DisplayName("the amount is NUMERIC(11,2) and a nine-integer-digit overflow is rejected")
        void theAmountIsNumeric11By2() {
            assertThat(StatementTransaction.AMOUNT_PRECISION)
                    .as("TRAN-AMT is PIC S9(09)V99, so 9 + 2 = 11")
                    .isEqualTo(StatementTransaction.AMOUNT_INTEGER_DIGITS
                            + StatementTransaction.AMOUNT_SCALE)
                    .isEqualTo(11);
            assertThat(StatementTransaction.AMOUNT_LENGTH)
                    .as("the field occupies 11 bytes in the fixed-width record")
                    .isEqualTo(11);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new StatementTransaction("4111999988887777",
                            "0000000000000001", null, null, null, null, new BigDecimal("1000000000.00"),
                            null, null, null, null, null, null, null))
                    .withMessageContaining("9 integer digits");
        }

        @Test
        @DisplayName("toString redacts the card number, which is the most sensitive field")
        void toStringRedactsTheCardNumber() {
            assertThat(statement().toString())
                    .as("a statement record carries a full card number; a DTO reaching a log must not "
                            + "take it along")
                    .doesNotContain("4111999988887777")
                    .contains("0000000000000001");
        }

        @Test
        @DisplayName("the record is a value type with equals and hashCode")
        void theRecordIsAValueType() {
            assertThat(statement()).isEqualTo(statement()).hasSameHashCodeAs(statement());
        }
    }

    @Nested
    @DisplayName("the 133-byte report line, its three totals layouts and the preserved mislabel")
    class ReportLineGeometry {

        @Test
        @DisplayName("the report line is 133 bytes, matching CVTRA07Y and the DD LRECL")
        void theReportLineIs133Bytes() {
            assertThat(StatementTransaction.REPORT_LINE_LENGTH)
                    .as("app/cpy/CVTRA07Y.cpy declares the report line at 133 bytes, and "
                            + "app/proc/TRANREPT.prc:STEP10R declares the output DD at LRECL=133 - the "
                            + "copybook and the job control agree")
                    .isEqualTo(133);
        }

        @ParameterizedTest
        @CsvSource({
            "Page Total,    11, 86, 1",
            "Account Total, 13, 84, 0",
            "Grand Total,   11, 86, 0",
        })
        @DisplayName("all three totals labels reach the same 97-character label-plus-dots width")
        void allThreeTotalsLabelsReach97(final String label, final int labelWidth, final int dotsWidth,
                final int expectedPadding) {
            assertThat(labelWidth + dotsWidth)
                    .as("every totals line pads label plus dots to the SAME 97 characters so the amount "
                            + "column aligns across all three. A wider label gets fewer dots - which is "
                            + "why 'Account Total' at 13 bytes has 84 dots where the two 11-byte labels "
                            + "have 86")
                    .isEqualTo(97)
                    .isEqualTo(StatementTransaction.REPORT_TOTALS_LABEL_PLUS_DOTS_LENGTH);

            assertThat(labelWidth - label.length())
                    .as("THE WIDTH IS THE DECLARED FIELD WIDTH, NOT THE LITERAL'S LENGTH. "
                            + "app/cpy/CVTRA07Y.cpy:50-66 declares 'Page Total' inside a PIC X(11) field, "
                            + "so COBOL space-pads the 10-character literal to 11 bytes; 'Account Total' "
                            + "at PIC X(13) and 'Grand Total' at PIC X(11) happen to fit their fields "
                            + "exactly. An earlier draft of this test asserted labelWidth equals "
                            + "label.length() and FAILED on 'Page Total' - which is precisely the bug "
                            + "worth guarding, because an implementation that emitted label + dots using "
                            + "label.length() would build a 96-character prefix for the page-total line "
                            + "while the other two came out at 97, misaligning the amount column on ONE "
                            + "LINE IN THREE - the kind of defect a spot check passes over")
                    .isEqualTo(expectedPadding);
        }

        @Test
        @DisplayName("only the page-total label is space-padded to its declared field width")
        void onlyThePageTotalLabelIsSpacePadded() {
            assertThat(StatementTransaction.PAGE_TOTAL_LABEL.length())
                    .as("the literal is ten characters")
                    .isEqualTo(10);
            assertThat(StatementTransaction.PAGE_TOTAL_LABEL_LENGTH)
                    .as("but its field is PIC X(11), so one trailing space is part of the emitted record")
                    .isEqualTo(11)
                    .isGreaterThan(StatementTransaction.PAGE_TOTAL_LABEL.length());

            assertThat(StatementTransaction.ACCOUNT_TOTAL_LABEL_LENGTH)
                    .as("'Account Total' fills its PIC X(13) field exactly, needing no padding")
                    .isEqualTo(StatementTransaction.ACCOUNT_TOTAL_LABEL.length())
                    .isEqualTo(13);
            assertThat(StatementTransaction.GRAND_TOTAL_LABEL_LENGTH)
                    .as("'Grand Total' fills its PIC X(11) field exactly")
                    .isEqualTo(StatementTransaction.GRAND_TOTAL_LABEL.length())
                    .isEqualTo(11);

            assertThat(StatementTransaction.PAGE_TOTAL_DOTS_LENGTH).isEqualTo(86);
            assertThat(StatementTransaction.ACCOUNT_TOTAL_DOTS_LENGTH)
                    .as("the longest label gets the fewest dots, which is what holds the sum at 97")
                    .isEqualTo(84);
            assertThat(StatementTransaction.GRAND_TOTAL_DOTS_LENGTH).isEqualTo(86);
        }

        @Test
        @DisplayName("the totals line is derived as 97 label-plus-dots characters plus a 15-byte amount")
        void theTotalsLineIsDerived() {
            assertThat(StatementTransaction.REPORT_TOTALS_LABEL_PLUS_DOTS_LENGTH
                    + StatementTransaction.AMOUNT_MASK_LENGTH)
                    .as("97 + 15 = 112, the declared totals line length")
                    .isEqualTo(112)
                    .isEqualTo(StatementTransaction.REPORT_TOTALS_LINE_LENGTH);
        }

        @Test
        @DisplayName("the 'Account Total' label is preserved even though the break is on the card number")
        void theAccountTotalLabelIsPreserved() {
            assertThat(StatementTransaction.ACCOUNT_TOTAL_LABEL)
                    .as("PRESERVED LEGACY MISLABEL: app/cbl/CBTRN03C.cbl performs its control break on "
                            + "the CARD NUMBER while emitting a label that reads 'Account Total'. The "
                            + "label is reproduced verbatim because the report baseline contains it; "
                            + "correcting it to 'Card Total' would be a behaviour change and would fail "
                            + "the parity comparison. Do NOT 'fix' this assertion")
                    .isEqualTo("Account Total");
            assertThat(StatementTransaction.PAGE_TOTAL_LABEL).isEqualTo("Page Total");
            assertThat(StatementTransaction.GRAND_TOTAL_LABEL).isEqualTo("Grand Total");
        }

        @Test
        @DisplayName("the detail mask floats a minus while the totals mask forces a sign")
        void theDetailMaskFloatsAMinusWhileTotalsForcesASign() {
            assertThat(StatementTransaction.DETAIL_AMOUNT_MASK)
                    .as("a DETAIL line uses a floating minus, so a positive amount shows no sign at all")
                    .isEqualTo("-ZZZ,ZZZ,ZZZ.ZZ")
                    .hasSize(StatementTransaction.AMOUNT_MASK_LENGTH);
            assertThat(StatementTransaction.TOTALS_AMOUNT_MASK)
                    .as("a TOTALS line uses a MANDATORY plus, so a positive total is explicitly signed. "
                            + "The two masks are the same width but differ in the sign character - a "
                            + "distinction that would be easy to unify and would change every line of the "
                            + "report")
                    .isEqualTo("+ZZZ,ZZZ,ZZZ.ZZ")
                    .hasSize(StatementTransaction.AMOUNT_MASK_LENGTH);
            assertThat(StatementTransaction.DETAIL_AMOUNT_MASK)
                    .isNotEqualTo(StatementTransaction.TOTALS_AMOUNT_MASK);
        }

        @Test
        @DisplayName("the three totals factories each produce their declared layout")
        void theThreeTotalsFactoriesProduceTheirLayout() {
            final BigDecimal total = new BigDecimal("1234.56");

            final StatementTransaction.ReportTotalsLine page =
                    StatementTransaction.ReportTotalsLine.pageTotal(total);
            assertThat(page.label()).isEqualTo("Page Total");
            assertThat(page.labelWidth()).isEqualTo(11);
            assertThat(page.dotsWidth()).isEqualTo(86);
            assertThat(page.total()).isEqualByComparingTo(total);

            final StatementTransaction.ReportTotalsLine account =
                    StatementTransaction.ReportTotalsLine.accountTotal(total);
            assertThat(account.label()).isEqualTo("Account Total");
            assertThat(account.labelWidth()).isEqualTo(13);
            assertThat(account.dotsWidth()).isEqualTo(84);

            final StatementTransaction.ReportTotalsLine grand =
                    StatementTransaction.ReportTotalsLine.grandTotal(total);
            assertThat(grand.label()).isEqualTo("Grand Total");
            assertThat(grand.labelWidth()).isEqualTo(11);
            assertThat(grand.dotsWidth()).isEqualTo(86);

            assertThat(List.of(page, account, grand))
                    .as("the three lines are distinct values, so a report cannot accidentally emit the "
                            + "wrong one")
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("an undeclared label-and-width combination is rejected")
        void anUndeclaredLabelAndWidthIsRejected() {
            assertThatIllegalArgumentException()
                    .as("only the three declared layouts exist. Accepting an arbitrary label would let a "
                            + "caller emit a totals line whose dots do not reach column 97, silently "
                            + "misaligning the amount column for that row only")
                    .isThrownBy(() -> new StatementTransaction.ReportTotalsLine(
                            "Invented Total", 14, 83, BigDecimal.ONE));

            assertThatIllegalArgumentException()
                    .as("a declared label with the WRONG width is equally invalid")
                    .isThrownBy(() -> new StatementTransaction.ReportTotalsLine(
                            "Page Total", 13, 84, BigDecimal.ONE));

            assertThatIllegalArgumentException()
                    .as("the SUM is checked before the layout, so a pair that does not reach 97 is "
                            + "rejected on arithmetic first. Both negative cases above deliberately sum "
                            + "to 97 in order to reach the layout guard, which is why this third case is "
                            + "needed to exercise the arithmetic guard at all")
                    .isThrownBy(() -> new StatementTransaction.ReportTotalsLine(
                            "Page Total", 11, 85, BigDecimal.ONE))
                    .withMessageContaining("must equal 97")
                    .withMessageContaining("app/cpy/CVTRA07Y.cpy:50-66");

            assertThatIllegalArgumentException()
                    .as("an absent label cannot be padded to any width")
                    .isThrownBy(() -> new StatementTransaction.ReportTotalsLine(
                            null, 11, 86, BigDecimal.ONE))
                    .withMessageContaining("label must not be null");

            assertThatCode(() -> new StatementTransaction.ReportTotalsLine(
                    "Account Total", 13, 84, BigDecimal.ONE))
                    .as("the matching pair is accepted")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the detail line is derived from its seven column widths plus the amount")
        void theDetailLineIsDerivedFromItsColumns() {
            assertThat(StatementTransaction.REPORT_TRANSACTION_ID_LENGTH).isEqualTo(16);
            assertThat(StatementTransaction.REPORT_ACCOUNT_ID_LENGTH).isEqualTo(11);
            assertThat(StatementTransaction.REPORT_TYPE_CODE_LENGTH).isEqualTo(2);
            assertThat(StatementTransaction.REPORT_TYPE_DESCRIPTION_LENGTH).isEqualTo(15);
            assertThat(StatementTransaction.REPORT_CATEGORY_CODE_LENGTH).isEqualTo(4);
            assertThat(StatementTransaction.REPORT_CATEGORY_DESCRIPTION_LENGTH).isEqualTo(29);
            assertThat(StatementTransaction.REPORT_SOURCE_LENGTH).isEqualTo(10);
            assertThat(StatementTransaction.REPORT_CODE_DESCRIPTION_SEPARATOR)
                    .as("a code and its description are joined by a hyphen on the report line")
                    .isEqualTo("-");
            assertThat(StatementTransaction.REPORT_DETAIL_LINE_LENGTH)
                    .as("the detail and column-header lines share a width so the columns align")
                    .isEqualTo(StatementTransaction.REPORT_COLUMN_HEADER_LENGTH)
                    .isEqualTo(114);
        }

        @Test
        @DisplayName("the report headers carry the literal names the legacy program emits")
        void theReportHeadersCarryTheLegacyLiterals() {
            assertThat(StatementTransaction.REPORT_SHORT_NAME).isEqualTo("DALYREPT");
            assertThat(StatementTransaction.REPORT_LONG_NAME).isEqualTo("Daily Transaction Report");
            assertThat(StatementTransaction.REPORT_DATE_HEADER)
                    .as("the date-range header literal, including its trailing space, is part of the "
                            + "byte-comparable output")
                    .isEqualTo("Date Range: ");
            assertThat(StatementTransaction.REPORT_DATE_SEPARATOR).isEqualTo(" to ");
            assertThat(StatementTransaction.REPORT_DATE_LENGTH)
                    .as("each date renders as ten characters, matching the filter prefix width")
                    .isEqualTo(10);
        }

        @Test
        @DisplayName("a detail line round-trips all eight of its components")
        void aDetailLineRoundTripsAllEightComponents() {
            final StatementTransaction.ReportDetailLine line =
                    new StatementTransaction.ReportDetailLine("0000000000000001", "00000000001", "01",
                            "Purchase", "0005", "Regular Sales Draft", "POS TERM",
                            new BigDecimal("100.00"));

            assertThat(line.transactionId()).isEqualTo("0000000000000001");
            assertThat(line.accountId()).isEqualTo("00000000001");
            assertThat(line.typeCode()).isEqualTo("01");
            assertThat(line.typeDescription()).isEqualTo("Purchase");
            assertThat(line.categoryCode()).isEqualTo("0005");
            assertThat(line.categoryDescription()).isEqualTo("Regular Sales Draft");
            assertThat(line.source()).isEqualTo("POS TERM");
            assertThat(line.amount()).isEqualByComparingTo("100.00");
            assertThat(line).isEqualTo(line).hasSameHashCodeAs(line);
            assertThat(line.toString()).contains("0000000000000001");
        }
    }

    @Nested
    @DisplayName("the legacy capacity ceiling, recorded as history rather than reimposed")
    class LegacyCapacityCeiling {

        @Test
        @DisplayName("the ceiling is derived as 51 cards times 10 transactions")
        void theCeilingIsDerivedAs51Times10() {
            assertThat(StatementTransaction.LEGACY_MAX_CARDS_PER_RUN)
                    .as("app/cbl/CBSTM03A.CBL:L225-L233 declares 51 card entries")
                    .isEqualTo(51);
            assertThat(StatementTransaction.LEGACY_MAX_TRANSACTIONS_PER_CARD)
                    .as("each holding 10 transaction entries")
                    .isEqualTo(10);
            assertThat(StatementTransaction.LEGACY_MAX_CARDS_PER_RUN
                    * StatementTransaction.LEGACY_MAX_TRANSACTIONS_PER_CARD)
                    .as("51 x 10 = 510 transactions per run, and the building loop increments BOTH "
                            + "indices with no bounds check whatsoever - a latent storage-overrun defect "
                            + "in the source")
                    .isEqualTo(510)
                    .isEqualTo(StatementTransaction.LEGACY_MAX_TRANSACTIONS_PER_RUN);
        }

        @Test
        @DisplayName("the ceiling is documentation only: a larger group is accepted")
        void theCeilingIsDocumentationOnly() {
            final List<StatementTransaction> many = new ArrayList<>();
            for (int i = 0; i <= StatementTransaction.LEGACY_MAX_TRANSACTIONS_PER_CARD; i++) {
                many.add(statement());
            }

            assertThatCode(() ->
                    new StatementTransaction.CardGroup("4111999988887777", many))
                    .as("DELIBERATE LABELLED DEVIATION: the Java implementation streams and therefore has "
                            + "NO ceiling, which removes the silent truncation and corruption hazard the "
                            + "unguarded legacy table carried. The constants exist to RECORD the "
                            + "historical limit, not to reimpose it - so a group larger than 10 must be "
                            + "accepted. Asserting the ceiling were enforced would be asserting a "
                            + "behaviour that was deliberately not implemented")
                    .doesNotThrowAnyException();
            assertThat(new StatementTransaction.CardGroup("4111999988887777", many).transactions())
                    .hasSize(StatementTransaction.LEGACY_MAX_TRANSACTIONS_PER_CARD + 1);
        }

        @Test
        @DisplayName("a card group copies its transactions defensively and exposes them unmodifiably")
        void aCardGroupCopiesDefensively() {
            final List<StatementTransaction> mutable = new ArrayList<>();
            mutable.add(statement());

            final StatementTransaction.CardGroup group =
                    new StatementTransaction.CardGroup("4111999988887777", mutable);
            mutable.add(statement());

            assertThat(group.transactions())
                    .as("the group captured a copy, so the caller's later mutation must not leak in")
                    .hasSize(1);
            assertThat(group.cardNumber()).isEqualTo("4111999988887777");
            assertThat(group).isEqualTo(new StatementTransaction.CardGroup(
                    "4111999988887777", List.of(statement())));
            assertThat(group.toString()).doesNotContain("4111999988887777");
        }

        @Test
        @DisplayName("a card group rejects an absent list and a null element")
        void aCardGroupRejectsAnAbsentListAndANullElement() {
            assertThatIllegalArgumentException()
                    .as("an absent transaction list is a programming error; an EMPTY list is the correct "
                            + "way to represent a card with no transactions, and the guard's own message "
                            + "says so rather than leaving the caller to guess")
                    .isThrownBy(() -> new StatementTransaction.CardGroup("4111999988887777", null))
                    .withMessageContaining("must not be null")
                    .withMessageContaining("use an empty list instead");

            final List<StatementTransaction> withNull = new ArrayList<>();
            withNull.add(statement());
            withNull.add(null);

            assertThatIllegalArgumentException()
                    .as("the explicit loop rejects a null element before List.copyOf would raise a bare "
                            + "NullPointerException, so the failure names the field")
                    .isThrownBy(() -> new StatementTransaction.CardGroup("4111999988887777", withNull))
                    .withMessageContaining("must not contain a null element");

            assertThatCode(() -> new StatementTransaction.CardGroup("4111999988887777", List.of()))
                    .as("an empty group is legal - a card may appear in the cross-reference with no "
                            + "transactions in the period")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the statement key is a two-component value type usable as a map key")
        void theStatementKeyIsAValueType() {
            final StatementTransaction.Key key =
                    new StatementTransaction.Key("4111999988887777", "0000000000000001");
            final StatementTransaction.Key same =
                    new StatementTransaction.Key("4111999988887777", "0000000000000001");
            final StatementTransaction.Key other =
                    new StatementTransaction.Key("4111999988887777", "0000000000000002");

            assertThat(key.cardNumber()).isEqualTo("4111999988887777");
            assertThat(key.transactionId()).isEqualTo("0000000000000001");
            assertThat(key)
                    .as("the key must behave as a value so the statement pipeline can index by it; the "
                            + "upstream sort orders on exactly these two components in this order")
                    .isEqualTo(same)
                    .hasSameHashCodeAs(same)
                    .isNotEqualTo(other);
            assertThat(key.toString()).doesNotContain("4111999988887777");
        }
    }

    @Nested
    @DisplayName("AccountDto: the 37 verified COACTVW input fields and the display-to-amount conversion")
    class AccountViewProjection {

        @Test
        @DisplayName("the field count and money tier match COACTVW.CPY and PIC S9(10)V99")
        void theFieldCountAndMoneyTierMatch() {
            assertThat(AccountDto.FIELD_COUNT)
                    .as("SOURCE-GOVERNED FIGURE. The specification states 36 input fields for this map. "
                            + "Counting the '02 <name>I PIC' declarations lying strictly inside the "
                            + "'01 CACTVWAI.' group of app/cpy-bms/COACTVW.CPY - which opens at line 17 "
                            + "and ends where '01 CACTVWAO REDEFINES CACTVWAI.' opens at line 241 - yields "
                            + "THIRTY-SEVEN, from TRNNAMEI at line 24 through ERRMSGI at line 240. The "
                            + "copybook is the authority over the prose, so 37 is correct and the stated "
                            + "36 is a specification defect. The constant exists so that the wrong figure "
                            + "cannot be reintroduced silently by a later edit")
                    .isEqualTo(37);
            assertThat(AccountDto.class.getRecordComponents())
                    .as("and the record carries exactly one component per declared input field, so the "
                            + "count is not merely asserted but structurally enforced")
                    .hasSize(AccountDto.FIELD_COUNT);
            assertThat(AccountDto.MONEY_PRECISION)
                    .as("the ACCOUNT money fields are PIC S9(10)V99, i.e. NUMERIC(12,2) - the WIDEST of "
                            + "the three decimal tiers")
                    .isEqualTo(12);
            assertThat(AccountDto.MONEY_SCALE).isEqualTo(2);
            assertThat(AccountDto.MONEY_ROUNDING).isEqualTo(RoundingMode.HALF_EVEN);
            assertThat(AccountDto.MONEY_DISPLAY_LENGTH)
                    .as("the screen renders the amount in a 15-byte display field")
                    .isEqualTo(15);
        }

        @Test
        @DisplayName("the account view record round-trips its identity fields and redacts the rest")
        void theAccountViewRecordRoundTripsAndRedacts() {
            final String[] values = new String[AccountDto.FIELD_COUNT];
            // Positional construction over the reflectively verified component order: index 6 is
            // accountId, 7 accountStatus, 3 programName, 18 customerSsn, 19 customerDateOfBirth.
            values[3] = "COACTVWC";
            values[6] = "00000000001";
            values[7] = "Y";
            values[18] = "123456789";
            values[19] = "1980-01-01";

            final AccountDto account = new AccountDto(values[0], values[1], values[2], values[3],
                    values[4], values[5], values[6], values[7], values[8], values[9], values[10],
                    values[11], values[12], values[13], values[14], values[15], values[16], values[17],
                    values[18], values[19], values[20], values[21], values[22], values[23], values[24],
                    values[25], values[26], values[27], values[28], values[29], values[30], values[31],
                    values[32], values[33], values[34], values[35], values[36]);

            assertThat(account.accountId()).isEqualTo("00000000001");
            assertThat(account.accountStatus()).isEqualTo("Y");
            assertThat(account.programName()).isEqualTo("COACTVWC");
            assertThat(account.customerSsn())
                    .as("the view screen carries the social security number, so the DTO must hold it")
                    .isEqualTo("123456789");
            assertThat(account.customerDateOfBirth())
                    .as("CUST-DOB-YYYY-MM-DD is PIC X(10) stored DASH-SEPARATED on the record. The "
                            + "COACTUPC snapshot holds the same date COMPACT as PIC X(08), which is why "
                            + "the update request compares it component-wise rather than whole-string")
                    .isEqualTo("1980-01-01")
                    .hasSize(10);

            assertThat(account.toString())
                    .as("the account view carries a social security number and a date of birth; the "
                            + "rendering must identify the record without publishing either")
                    .contains("00000000001")
                    .doesNotContain("123456789")
                    .doesNotContain("1980-01-01");

            assertThat(account)
                    .as("the record is a value type")
                    .isEqualTo(new AccountDto(values[0], values[1], values[2], values[3], values[4],
                            values[5], values[6], values[7], values[8], values[9], values[10],
                            values[11], values[12], values[13], values[14], values[15], values[16],
                            values[17], values[18], values[19], values[20], values[21], values[22],
                            values[23], values[24], values[25], values[26], values[27], values[28],
                            values[29], values[30], values[31], values[32], values[33], values[34],
                            values[35], values[36]));
        }

        @Test
        @DisplayName("a well-formed display amount converts to a scaled BigDecimal")
        void aWellFormedDisplayAmountConverts() {
            assertThat(AccountDto.toAmount("1234.56", "currentBalance"))
                    .as("the conversion returns an Optional so the absent case needs no sentinel")
                    .contains(new BigDecimal("1234.56"));
            assertThat(AccountDto.toAmount("  -42.5  ", "currentBalance"))
                    .as("surrounding whitespace is stripped and the value is scaled to 2, so -42.5 "
                            + "becomes -42.50. The SIGN survives, because an account balance is "
                            + "legitimately negative")
                    .contains(new BigDecimal("-42.50"));
            assertThat(AccountDto.toAmount("1234.565", "currentBalance").orElseThrow())
                    .as("1234.565 at scale 2 under HALF_EVEN goes to 1234.56, because the retained digit "
                            + "6 is even; HALF_UP would give 1234.57")
                    .isEqualByComparingTo("1234.56");
        }

        @Test
        @DisplayName("an absent, blank or low-values display amount converts to an empty Optional")
        void anAbsentOrBlankAmountConvertsToEmpty() {
            assertThat(AccountDto.toAmount(null, "currentBalance"))
                    .as("a null display field means the screen never supplied it")
                    .isEmpty();
            assertThat(AccountDto.toAmount("", "currentBalance")).isEmpty();
            assertThat(AccountDto.toAmount("      ", "currentBalance"))
                    .as("a space-filled field is the normal BMS representation of 'nothing entered'")
                    .isEmpty();
            assertThat(AccountDto.toAmount("\u0000\u0000\u0000", "currentBalance"))
                    .as("LOW-VALUES is binary zeros, which is what an uninitialised COBOL field holds - "
                            + "distinct from SPACES and equally meaning absent. Treating NUL as a digit "
                            + "or throwing on it would break every unpopulated screen")
                    .isEmpty();
        }

        @Test
        @DisplayName("a non-numeric display amount is rejected with its cause preserved")
        void aNonNumericAmountIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> AccountDto.toAmount("not-a-number", "currentBalance"))
                    .withMessageContaining("currentBalance")
                    .withCauseInstanceOf(NumberFormatException.class);
        }

        @Test
        @DisplayName("an over-long or over-precise display amount is rejected")
        void anOverLongOrOverPreciseAmountIsRejected() {
            assertThatIllegalArgumentException()
                    .as("a value longer than the 15-byte display field cannot have come from this screen")
                    .isThrownBy(() -> AccountDto.toAmount("1".repeat(16), "creditLimit"))
                    .withMessageContaining("15");

            assertThatIllegalArgumentException()
                    .as("13 significant digits exceed the 12 that PIC S9(10)V99 allows")
                    .isThrownBy(() -> AccountDto.toAmount("99999999999.99", "creditLimit"))
                    .withMessageContaining("12");
        }

        @Test
        @DisplayName("the field name is mandatory, so a failure can always name the offending field")
        void theFieldNameIsMandatory() {
            assertThatIllegalArgumentException()
                    .as("the conversion refuses to run without a field name, because a diagnostic that "
                            + "cannot identify the field would have to quote the VALUE instead - and these "
                            + "are monetary fields on a screen carrying personally identifiable data")
                    .isThrownBy(() -> AccountDto.toAmount("1.00", null))
                    .withMessageContaining("fieldName is required");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> AccountDto.toAmount("1.00", "  "))
                    .withMessageContaining("fieldName is required");
        }
    }

    @Nested
    @DisplayName("the remaining request DTOs and the row-count guards")
    class RemainingRequestDtos {

        @Test
        @DisplayName("the request DTOs carry exactly their symbolic maps' field counts")
        void theRequestDtosCarryTheirMapFieldCounts() {
            assertThat(BillPaymentRequest.class.getRecordComponents())
                    .as("app/cpy-bms/COBIL00.CPY generates 10 input fields")
                    .hasSize(10);
            assertThat(TransactionAddRequest.class.getRecordComponents())
                    .as("app/cpy-bms/COTRN02.CPY generates 21")
                    .hasSize(21);
            assertThat(CardUpdateRequest.class.getRecordComponents())
                    .as("app/cpy-bms/COCRDUP.CPY generates 17 input fields, and the payload carries two "
                            + "further components - the SEALED as-displayed snapshot that COCRDUPC.cbl:291 "
                            + "declares, and the submitted group of :303 - because a stateless request "
                            + "cannot otherwise reproduce the change-detection comparison the program "
                            + "performs against them")
                    .hasSize(19);
            assertThat(Arrays.stream(CardUpdateRequest.class.getRecordComponents())
                            .map(java.lang.reflect.RecordComponent::getName)
                            .toList())
                    .as("and the two additions are exactly those, at the tail, so the first seventeen "
                            + "still correspond to the map in copybook order. The as-displayed one is an "
                            + "opaque token rather than a group: a group the caller could rewrite would "
                            + "make the comparison at :1503-1508 unconditionally true")
                    .endsWith("snapshot", "newDetails");
            assertThat(Arrays.stream(CardUpdateRequest.class.getRecordComponents())
                            .filter(component -> "snapshot".equals(component.getName()))
                            .findFirst()
                            .orElseThrow()
                            .getType())
                    .isEqualTo(String.class);
        }

        @Test
        @DisplayName("the transaction-add amount bounds distinguish the mask from the field")
        void theTransactionAddAmountBoundsDistinguishMaskFromField() {
            assertThat(TransactionAddRequest.AMOUNT_ROUNDING_MODE)
                    .as("HALF_EVEN, never a default")
                    .isEqualTo(RoundingMode.HALF_EVEN);
            assertThat(TransactionAddRequest.AMOUNT_MASK_MAX)
                    .as("the EDITED display mask holds eight integer digits, so its maximum is "
                            + "99999999.99")
                    .isEqualByComparingTo("99999999.99");
            assertThat(TransactionAddRequest.AMOUNT_VALUE_MAX)
                    .as("but the NUMERIC field is S9(09)V99, so its maximum is 999999999.99 - one digit "
                            + "wider. Both bounds exist because a value can be storable yet not "
                            + "displayable, and the two limits must not be conflated")
                    .isEqualByComparingTo("999999999.99");
            assertThat(TransactionAddRequest.AMOUNT_VALUE_MAX)
                    .isGreaterThan(TransactionAddRequest.AMOUNT_MASK_MAX);
        }

        @Test
        @DisplayName("the request DTOs redact their identifying fields in toString")
        void theRequestDtosRedactInToString() {
            final CardUpdateRequest card = new CardUpdateRequest("CCUP", null, null, "COCRDUPC", null,
                    null, "00000000001", "4111999988887777", null, null, null, null, null, null, null,
                    null, null, null, null);

            assertThat(card.toString())
                    .as("the update request carries a full card number, so toString must not print it")
                    .contains("00000000001")
                    .doesNotContain("4111999988887777");

            final TransactionAddRequest add = new TransactionAddRequest("CT02", null, null,
                    "COTRN02C", null, null, "00000000001", "4111999988887777", null, null, null, null,
                    null, null, null, null, null, null, null, null, null);
            assertThat(add.toString())
                    .contains("00000000001")
                    .doesNotContain("4111999988887777");
        }

        @Test
        @DisplayName("the comm area resolves its user type code to the enum, tolerating an unknown")
        void theCommAreaResolvesItsUserType() {
            // Component order verified by reflection, not inferred: the nine components are
            // userId, userType, customerId, customerFirstName, customerMiddleName,
            // customerLastName, accountId, accountStatus, cardNumber. The card number is the
            // LAST component, so an argument list that placed it earlier would compile and quietly
            // store it in a name field. The two identifiers are String and not Long: a display
            // numeric is a fixed-width character field, CDEMO-CUST-ID PIC 9(09) and CDEMO-ACCT-ID
            // PIC 9(11) at app/cpy/COCOM01Y.cpy, whose leading zeros are part of the value.
            final CommArea admin = new CommArea("ADMNUSR1", "A", "000000009", "FNAMEAA1", "M", "LNM1",
                    "00000000011", "Y", "4111999988887777");

            assertThat(admin.userType())
                    .as("the raw single-character code is preserved as read from the COMMAREA")
                    .isEqualTo("A");
            assertThat(admin.resolvedUserType())
                    .as("app/cpy/COCOM01Y.cpy declares 88-levels for 'A' and 'U'; the resolver maps the "
                            + "code onto the typed enum that drives role-based access control")
                    .isEqualTo(UserType.ADMIN);

            final CommArea unknown = new CommArea("STDUSR01", "Z", "000000001", null, null, null, null, null,
                    null);
            assertThat(unknown.cardNumber())
                    .as("confirming the ninth component really is the card number, so the redaction "
                            + "assertion below is testing what it claims to test")
                    .isNull();
            assertThat(unknown.resolvedUserType())
                    .as("an unrecognised code resolves to null rather than throwing: the COMMAREA is "
                            + "legacy data that may hold anything, and the caller decides how to treat an "
                            + "unknown type")
                    .isNull();

            assertThat(admin.toString())
                    .as("the comm area carries a card number and must redact it")
                    .contains("ADMNUSR1")
                    .doesNotContain("4111999988887777");
        }

        @Test
        @DisplayName("a bill payment request round-trips its ten screen fields")
        void aBillPaymentRequestRoundTripsItsTenFields() {
            final BillPaymentRequest request = new BillPaymentRequest("CB00", "T1", "01/01/24",
                    "COBIL00C", "T2", "12.30.45", "00000000001", "1234.56", "Y", null);

            assertThat(request.transactionName()).isEqualTo("CB00");
            assertThat(request.programName()).isEqualTo("COBIL00C");
            assertThat(request)
                    .as("the request is a value type, so two identical submissions compare equal")
                    .isEqualTo(new BillPaymentRequest("CB00", "T1", "01/01/24", "COBIL00C", "T2",
                            "12.30.45", "00000000001", "1234.56", "Y", null));
        }

        @Test
        @DisplayName("a transaction page holding more than ten rows is rejected")
        void aTransactionPageHoldingMoreThanTenRowsIsRejected() {
            final List<TransactionDto.TransactionListRow> eleven = new ArrayList<>();
            for (int i = 0; i <= TransactionDto.PAGE_SIZE; i++) {
                eleven.add(new TransactionDto.TransactionListRow(
                        "S", String.format("%016d", i), "20240101", "D", "+00000100.00"));
            }

            assertThatIllegalArgumentException()
                    .as("the screen has exactly ten physical row slots, so an eleventh row could never be "
                            + "displayed. Rejecting it at construction beats silently dropping it")
                    .isThrownBy(() -> page(eleven))
                    .withMessageContaining("rows holds 11")
                    .withMessageContaining("10 row slots");
        }

        @Test
        @DisplayName("a transaction page containing a null row is rejected")
        void aTransactionPageContainingANullRowIsRejected() {
            final List<TransactionDto.TransactionListRow> withNull = new ArrayList<>();
            withNull.add(null);

            assertThatIllegalArgumentException()
                    .as("a null row would surface as a NullPointerException inside the projection loop, "
                            + "far from the caller that supplied it")
                    .isThrownBy(() -> page(withNull))
                    .withMessageContaining("must not contain a null element, but index 0");

            assertThatCode(() -> page(null))
                    .as("an absent row list is legal - the detail screen has no rows at all, and the "
                            + "guards must distinguish 'no list' from 'a list containing nothing valid'")
                    .doesNotThrowAnyException();
            assertThat(page(List.of()).rows())
                    .as("an empty page is likewise legal and stays empty rather than becoming null")
                    .isNotNull()
                    .isEmpty();
        }

        @Test
        @DisplayName("a card page holding more than seven rows is rejected")
        void aCardPageHoldingMoreThanSevenRowsIsRejected() {
            final List<CardDto.CardListRow> eight = new ArrayList<>();
            for (int i = 1; i <= CardDto.CARD_LIST_PAGE_SIZE; i++) {
                eight.add(new CardDto.CardListRow(i, "S",
                        i == CardDto.ROW_NUMBER_WITHOUT_SELECTOR_TYPE ? null : "T",
                        "00000000001", "4111999988887777", "Y"));
            }
            eight.add(new CardDto.CardListRow(7, "S", "T", "00000000001", "4111999988887777", "Y"));

            assertThatIllegalArgumentException()
                    .as("the card list screen declares seven row groups, and the size check fires "
                            + "BEFORE the positional check, so an eighth row is rejected on capacity "
                            + "rather than on ordering")
                    .isThrownBy(() -> CardDto.list("CCLI", null, null, null, null, null, null, null,
                            null, eight, null, null))
                    .withMessageContaining("at most 7 per page")
                    .withMessageContaining("app/cbl/COCRDLIC.cbl:177-178");
        }

        @Test
        @DisplayName("a card page containing a null row is rejected")
        void aCardPageContainingANullRowIsRejected() {
            final List<CardDto.CardListRow> withNull = new ArrayList<>();
            withNull.add(null);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CardDto.list("CCLI", null, null, null, null, null, null, null,
                            null, withNull, null, null))
                    .withMessageContaining("must not contain a null element, but index 0");
        }

        @Test
        @DisplayName("a card page whose rows are out of order is rejected")
        void aCardPageWhoseRowsAreOutOfOrderIsRejected() {
            final List<CardDto.CardListRow> misordered = new ArrayList<>();
            misordered.add(new CardDto.CardListRow(2, "S", "T", "00000000001",
                    "4111999988887777", "Y"));

            assertThatIllegalArgumentException()
                    .as("BMS row numbers are ONE-based and positional: the row at index 0 must be row 1, "
                            + "or the screen would render a row's data into the wrong physical slot")
                    .isThrownBy(() -> CardDto.list("CCLI", null, null, null, null, null, null, null,
                            null, misordered, null, null))
                    .withMessageContaining("must be positional")
                    .withMessageContaining("index 0 must carry row number 1 but carries 2");
        }

        @Test
        @DisplayName("all sixteen card detail accessors return what the factory supplied")
        void allSixteenCardDetailAccessorsReturnWhatWasSupplied() {
            final CardDto detail = CardDto.detail("CCDL", "Title One", "01/01/24", "COCRDSLC",
                    "Title Two", "12.30.45", "00000000001", "4111999988887777", "CARDHOLDER NAME",
                    "Y", "12", "2030", "Info line", "Error line", "F3=Exit");

            assertThat(detail.getTransactionName()).isEqualTo("CCDL");
            assertThat(detail.getTitle01()).isEqualTo("Title One");
            assertThat(detail.getCurrentDate()).isEqualTo("01/01/24");
            assertThat(detail.getProgramName()).isEqualTo("COCRDSLC");
            assertThat(detail.getTitle02()).isEqualTo("Title Two");
            assertThat(detail.getCurrentTime()).isEqualTo("12.30.45");
            assertThat(detail.getAccountId()).isEqualTo("00000000001");
            assertThat(detail.getCardNumber()).isEqualTo("4111999988887777");
            assertThat(detail.getCardholderName()).isEqualTo("CARDHOLDER NAME");
            assertThat(detail.getCardStatusCode()).isEqualTo("Y");
            assertThat(detail.getExpiryMonth())
                    .as("the expiry is split into a 2-character month and a 4-character year, matching "
                            + "the screen's two separate input fields")
                    .isEqualTo("12");
            assertThat(detail.getExpiryYear()).isEqualTo("2030");
            assertThat(detail.getInformationMessage()).isEqualTo("Info line");
            assertThat(detail.getErrorMessage()).isEqualTo("Error line");
            assertThat(detail.getFunctionKeys()).isEqualTo("F3=Exit");
            assertThat(detail.getPageNumber())
                    .as("the DETAIL screen has no pagination, so its page number is absent")
                    .isNull();
        }

        @ParameterizedTest
        @ValueSource(strings = {"MAIN", "ADMIN"})
        @DisplayName("both menus expose their populated option tables")
        void bothMenusExposeTheirOptionTables(final String which) {
            final MenuResponse<?> menu =
                    "MAIN".equals(which) ? MenuResponse.mainMenu() : MenuResponse.adminMenu();

            assertThat(menu.getOptions())
                    .as("app/cpy/COMEN02Y.cpy declares 10 populated main-menu slots and "
                            + "app/cpy/COADM02Y.cpy declares 4 admin slots; the option list is bounded by "
                            + "its own count field, never by the array's declared size")
                    .isNotEmpty()
                    .hasSize(menu.getOptionCount())
                    .doesNotContainNull();
            assertThat(menu.getMenuType()).isNotNull();
            assertThat(menu.toString()).isNotBlank();
        }

        @Test
        @DisplayName("the main menu has ten options and the admin menu four")
        void theMainMenuHasTenOptionsAndAdminFour() {
            assertThat(MenuResponse.MAIN_MENU_OPTIONS)
                    .as("app/cpy/COMEN02Y.cpy populates 10 of its menu slots")
                    .hasSize(10);
            assertThat(MenuResponse.ADMIN_MENU_OPTIONS)
                    .as("app/cpy/COADM02Y.cpy populates 4, and CDEMO-ADMIN-OPT-COUNT is literally 4")
                    .hasSize(4);
            assertThat(MenuResponse.mainMenu().getOptionCount()).isEqualTo(10);
            assertThat(MenuResponse.adminMenu().getOptionCount()).isEqualTo(4);
        }

        @Test
        @DisplayName("a menu rejects a null option list and a null element")
        void aMenuRejectsNullInputs() {
            assertThatIllegalArgumentException()
                    .as("an empty list represents a menu offering nothing; null is a programming error "
                            + "and the two must stay distinguishable")
                    .isThrownBy(() -> MenuResponse.ofMainMenu(null))
                    .withMessageContaining("options must not be null");

            final List<MenuResponse.MainMenuOption> withNull = new ArrayList<>();
            withNull.add(null);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> MenuResponse.ofMainMenu(withNull))
                    .withMessageContaining("null");

            assertThatCode(() -> MenuResponse.ofMainMenu(List.of()))
                    .as("an empty menu is legal")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("an account view amount converts through the documented Optional contract")
        void anAccountViewAmountConvertsThroughOptional() {
            final Optional<BigDecimal> converted = AccountDto.toAmount("0.00", "currentBalance");

            assertThat(converted).isPresent();
            assertThat(converted.orElseThrow())
                    .as("zero is a legitimate balance and must convert rather than being treated as "
                            + "absent - the bill payment path rejects a balance at or below zero, which "
                            + "requires being able to represent exactly zero")
                    .isEqualByComparingTo("0.00");
            assertThat(converted.orElseThrow().scale()).isEqualTo(AccountDto.MONEY_SCALE);
        }
    }
}
