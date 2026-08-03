/*
 * ******************************************************************
 * Program     : StatementTransactionApiContractTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/model
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live AWS endpoint
 * Function    : Pins the 350-byte statement record geometry, the two
 *               report line layouts, and - in particular - the five
 *               diagnostic renderings that must not publish a card
 *               number, an account identifier or a monetary figure.
 *               A generated record rendering emits every component,
 *               so on these five types the override is a containment
 *               control rather than a convenience.
 * Source      : app/cpy/COSTM01.CPY:20-36 (TRNX-RECORD, 32-byte
 *               TRNX-KEY + 318-byte TRNX-REST) +
 *               app/cpy/CVTRA07Y.cpy:15-31,50-66 (detail line and
 *               the three totals layouts) @ 7756d89
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cardemo.model.dto.StatementTransaction;
import com.cardemo.model.dto.StatementTransaction.CardGroup;
import com.cardemo.model.dto.StatementTransaction.Key;
import com.cardemo.model.dto.StatementTransaction.ReportDetailLine;
import com.cardemo.model.dto.StatementTransaction.ReportTotalsLine;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Unit contract for {@link StatementTransaction} and its four nested layout types.
 *
 * <p>The emphasis is deliberately uneven. Width and geometry assertions exist because the record is emitted
 * into a fixed-width object at the storage boundary and a one-byte drift there is invisible in Java and fatal
 * to the parity comparison. The rendering assertions exist for a different reason: this type carries a card
 * number, a merchant name and a monetary amount, so the rendering a record generates for free is the wrong
 * rendering, and five overrides stand between it and a log line.
 */
@DisplayName("StatementTransaction - app/cpy/COSTM01.CPY:20-36 + app/cpy/CVTRA07Y.cpy:15-31,50-66")
class StatementTransactionApiContractTest {

    /** A synthetic card number at the declared width; deliberately not one of the seeded rows. */
    private static final String SYNTHETIC_CARD_NUMBER = "4111999988887777";

    /** A synthetic transaction identifier at the declared width. */
    private static final String SYNTHETIC_TRANSACTION_ID = "0000000000000042";

    /** A synthetic merchant name, distinctive enough that a rendering leak is unambiguous. */
    private static final String SYNTHETIC_MERCHANT_NAME = "ZZQQ-MERCHANT-NAME-LEAK-CANARY";

    /** A synthetic merchant city, distinctive for the same reason. */
    private static final String SYNTHETIC_MERCHANT_CITY = "ZZQQ-MERCHANT-CITY-LEAK-CANARY";

    /** A synthetic amount whose digits do not occur in any other fixture on this class. */
    private static final BigDecimal SYNTHETIC_AMOUNT = new BigDecimal("76543.21");

    /** A synthetic account identifier at the report line's declared width. */
    private static final String SYNTHETIC_ACCOUNT_ID = "00000000099";

    /**
     * The thirteen string components of {@code TRNX-RECORD} with their declared PIC widths.
     *
     * @return {@code componentName}, {@code cobolItem}, {@code sourceLine}, {@code width} tuples
     */
    static Stream<Arguments> stringFieldContracts() {
        return Stream.of(
                Arguments.of("cardNumber", "TRNX-CARD-NUM", 22, StatementTransaction.CARD_NUMBER_LENGTH),
                Arguments.of("transactionId", "TRNX-ID", 23, StatementTransaction.TRANSACTION_ID_LENGTH),
                Arguments.of("typeCode", "TRNX-TYPE-CD", 25, StatementTransaction.TYPE_CODE_LENGTH),
                Arguments.of("categoryCode", "TRNX-CAT-CD", 26, StatementTransaction.CATEGORY_CODE_LENGTH),
                Arguments.of("source", "TRNX-SOURCE", 27, StatementTransaction.SOURCE_LENGTH),
                Arguments.of("description", "TRNX-DESC", 28, StatementTransaction.DESCRIPTION_LENGTH),
                Arguments.of("merchantId", "TRNX-MERCHANT-ID", 30, StatementTransaction.MERCHANT_ID_LENGTH),
                Arguments.of("merchantName", "TRNX-MERCHANT-NAME", 31,
                        StatementTransaction.MERCHANT_NAME_LENGTH),
                Arguments.of("merchantCity", "TRNX-MERCHANT-CITY", 32,
                        StatementTransaction.MERCHANT_CITY_LENGTH),
                Arguments.of("merchantZip", "TRNX-MERCHANT-ZIP", 33, StatementTransaction.MERCHANT_ZIP_LENGTH),
                Arguments.of("originatingTimestamp", "TRNX-ORIG-TS", 34,
                        StatementTransaction.ORIGINATING_TIMESTAMP_LENGTH),
                Arguments.of("processingTimestamp", "TRNX-PROC-TS", 35,
                        StatementTransaction.PROCESSING_TIMESTAMP_LENGTH),
                Arguments.of("filler", "FILLER", 36, StatementTransaction.FILLER_LENGTH));
    }

    /**
     * The seven string components of {@code TRANSACTION-DETAIL-REPORT} with their declared PIC widths.
     *
     * @return {@code componentName}, {@code cobolItem}, {@code sourceLine}, {@code width} tuples
     */
    static Stream<Arguments> detailLineFieldContracts() {
        return Stream.of(
                Arguments.of("transactionId", "TRAN-REPORT-TRANS-ID", 16,
                        StatementTransaction.REPORT_TRANSACTION_ID_LENGTH),
                Arguments.of("accountId", "TRAN-REPORT-ACCOUNT-ID", 18,
                        StatementTransaction.REPORT_ACCOUNT_ID_LENGTH),
                Arguments.of("typeCode", "TRAN-REPORT-TYPE-CD", 20,
                        StatementTransaction.REPORT_TYPE_CODE_LENGTH),
                Arguments.of("typeDescription", "TRAN-REPORT-TYPE-DESC", 22,
                        StatementTransaction.REPORT_TYPE_DESCRIPTION_LENGTH),
                Arguments.of("categoryCode", "TRAN-REPORT-CAT-CD", 24,
                        StatementTransaction.REPORT_CATEGORY_CODE_LENGTH),
                Arguments.of("categoryDescription", "TRAN-REPORT-CAT-DESC", 26,
                        StatementTransaction.REPORT_CATEGORY_DESCRIPTION_LENGTH),
                Arguments.of("source", "TRAN-REPORT-SOURCE", 28, StatementTransaction.REPORT_SOURCE_LENGTH));
    }

    /**
     * A fully populated record whose every component sits at or inside its declared width.
     *
     * @return a valid record built only from this class's deterministic fixtures
     */
    private static StatementTransaction baseline() {
        return new StatementTransaction(SYNTHETIC_CARD_NUMBER, SYNTHETIC_TRANSACTION_ID, "01", "0001",
                "POS", "SYNTHETIC PURCHASE", SYNTHETIC_AMOUNT, "000000123", SYNTHETIC_MERCHANT_NAME,
                SYNTHETIC_MERCHANT_CITY, "0000012345", "2026-07-24-14.30.00.000000",
                "2026-07-24-14.30.00.0000", " ".repeat(20));
    }

    /**
     * Builds the baseline with exactly one string component replaced, resolved positionally from the
     * record's own component order so a reordering would be followed rather than silently mis-applied.
     *
     * @param componentName the component to replace
     * @param value         the replacement value, which may legitimately be over-wide
     * @return the baseline carrying {@code value} in {@code componentName} and its fixture elsewhere
     */
    private static StatementTransaction withComponent(final String componentName, final String value) {
        final String[] values = {SYNTHETIC_CARD_NUMBER, SYNTHETIC_TRANSACTION_ID, "01", "0001", "POS",
                "SYNTHETIC PURCHASE", "000000123", SYNTHETIC_MERCHANT_NAME, SYNTHETIC_MERCHANT_CITY,
                "0000012345", "2026-07-24-14.30.00.000000", "2026-07-24-14.30.00.0000", " ".repeat(20)};
        final List<String> names = Arrays.stream(StatementTransaction.class.getRecordComponents())
                .map(RecordComponent::getName)
                .filter(name -> !"amount".equals(name))
                .toList();
        final int index = names.indexOf(componentName);

        if (index < 0) {
            throw new AssertionError("StatementTransaction declares no string component " + componentName);
        }
        values[index] = value;

        return new StatementTransaction(values[0], values[1], values[2], values[3], values[4], values[5],
                SYNTHETIC_AMOUNT, values[6], values[7], values[8], values[9], values[10], values[11],
                values[12]);
    }

    /**
     * A fully populated report detail line.
     *
     * @return a valid detail line built only from this class's deterministic fixtures
     */
    private static ReportDetailLine detailLine() {
        return new ReportDetailLine(SYNTHETIC_TRANSACTION_ID, SYNTHETIC_ACCOUNT_ID, "01", "PURCHASE",
                "0001", "RETAIL SYNTHETIC CATEGORY", "POS", SYNTHETIC_AMOUNT);
    }

    /**
     * Builds a detail line with exactly one string component replaced.
     *
     * @param componentName the component to replace
     * @param value         the replacement value, which may legitimately be over-wide
     * @return the detail line carrying {@code value} in {@code componentName}
     */
    private static ReportDetailLine detailLineWith(final String componentName, final String value) {
        final String[] values = {SYNTHETIC_TRANSACTION_ID, SYNTHETIC_ACCOUNT_ID, "01", "PURCHASE", "0001",
                "RETAIL SYNTHETIC CATEGORY", "POS"};
        final List<String> names = Arrays.stream(ReportDetailLine.class.getRecordComponents())
                .map(RecordComponent::getName)
                .filter(name -> !"amount".equals(name))
                .toList();
        final int index = names.indexOf(componentName);

        if (index < 0) {
            throw new AssertionError("ReportDetailLine declares no string component " + componentName);
        }
        values[index] = value;

        return new ReportDetailLine(values[0], values[1], values[2], values[3], values[4], values[5],
                values[6], SYNTHETIC_AMOUNT);
    }

    @Nested
    @DisplayName("1. Record geometry - the 350-byte TRNX-RECORD of app/cpy/COSTM01.CPY:20-36")
    class RecordGeometry {

        @Test
        @DisplayName("resolves the 32-byte key, the 318-byte remainder and the 350-byte total exactly")
        void resolvesTheDeclaredRecordGeometry() {
            assertThat(StatementTransaction.KEY_LENGTH)
                    .as("TRNX-KEY is TRNX-CARD-NUM X(16) plus TRNX-ID X(16), and app/jcl/CREASTMT.JCL "
                            + "DELDEF01 independently declares KEYS(32 0) on the work cluster")
                    .isEqualTo(StatementTransaction.CARD_NUMBER_LENGTH
                            + StatementTransaction.TRANSACTION_ID_LENGTH)
                    .isEqualTo(32);
            assertThat(StatementTransaction.KEY_LENGTH + StatementTransaction.REMAINDER_LENGTH)
                    .as("key plus remainder must be the declared record length; the same JCL step declares "
                            + "RECORDSIZE(350 350)")
                    .isEqualTo(StatementTransaction.RECORD_LENGTH)
                    .isEqualTo(350);
        }

        @Test
        @DisplayName("its remainder widths sum to the declared 318 bytes, leaving no slack unaccounted for")
        void remainderWidthsSumToTheDeclaredLength() {
            final int summed = StatementTransaction.TYPE_CODE_LENGTH
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

            assertThat(summed)
                    .as("TRNX-REST at app/cpy/COSTM01.CPY:24-36 declares twelve items; if these constants "
                            + "sum to anything else, one of them is wrong and the fixed-width emission "
                            + "would drift against the baseline without any Java-visible symptom")
                    .isEqualTo(StatementTransaction.REMAINDER_LENGTH)
                    .isEqualTo(318);
        }

        @Test
        @DisplayName("declares exactly fourteen components, one per COSTM01 data item")
        void declaresExactlyFourteenComponents() {
            assertThat(StatementTransaction.class.getRecordComponents())
                    .as("COSTM01.CPY declares two key items and twelve remainder items, FILLER included, "
                            + "because the filler is part of the 350-byte image and must round-trip")
                    .hasSize(14);
        }

        @Test
        @DisplayName("orders its components exactly as COSTM01 declares them")
        void ordersComponentsAsTheCopybookDeclaresThem() {
            assertThat(Arrays.stream(StatementTransaction.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList())
                    .as("the canonical constructor is positional and thirteen of the fourteen components "
                            + "are String, so a reordering would compile and silently transpose fields")
                    .containsExactly("cardNumber", "transactionId", "typeCode", "categoryCode", "source",
                            "description", "amount", "merchantId", "merchantName", "merchantCity",
                            "merchantZip", "originatingTimestamp", "processingTimestamp", "filler");
        }

        @Test
        @DisplayName("records the projection truncation rather than correcting it")
        void recordsTheProjectionTruncationRatherThanCorrectingIt() {
            assertThat(StatementTransaction.PROCESSING_TIMESTAMP_SIGNIFICANT_LENGTH
                    + StatementTransaction.PROCESSING_TIMESTAMP_PAD_LENGTH)
                    .as("the DFSORT OUTREC at app/jcl/CREASTMT.JCL STEP010 copies 50 bytes from offset "
                            + "279, which is the full 26-byte originating timestamp plus only the FIRST 24 "
                            + "bytes of the processing timestamp. The projected value therefore arrives 24 "
                            + "characters wide, padded to 26 - and that truncation is reproduced, not fixed")
                    .isEqualTo(StatementTransaction.PROCESSING_TIMESTAMP_LENGTH);
            assertThat(StatementTransaction.BASE_TAIL_OFFSET + StatementTransaction.BASE_TAIL_LENGTH - 1)
                    .as("the last position the projection writes is 328, so the 20-byte trailing filler at "
                            + "329-350 is dropped entirely")
                    .isEqualTo(StatementTransaction.PROJECTION_LAST_WRITTEN_POSITION);
        }

        @Test
        @DisplayName("records the legacy in-memory ceiling as history, without enforcing it")
        void recordsTheLegacyCeilingWithoutEnforcingIt() {
            assertThat(StatementTransaction.LEGACY_MAX_CARDS_PER_RUN
                    * StatementTransaction.LEGACY_MAX_TRANSACTIONS_PER_CARD)
                    .as("app/cbl/CBSTM03A.CBL:225-233 declares a 51-by-10 table with no bounds check, so "
                            + "510 was a hard and silently-overrun ceiling")
                    .isEqualTo(StatementTransaction.LEGACY_MAX_TRANSACTIONS_PER_RUN)
                    .isEqualTo(510);

            final List<StatementTransaction> beyondTheCeiling = new ArrayList<>();
            for (int index = 0; index <= StatementTransaction.LEGACY_MAX_TRANSACTIONS_PER_RUN; index++) {
                beyondTheCeiling.add(baseline());
            }

            assertThat(new CardGroup(SYNTHETIC_CARD_NUMBER, beyondTheCeiling).transactions())
                    .as("the ceiling is recorded as a documented deviation, not preserved: streaming "
                            + "removes a truncation-and-corruption hazard, so 511 transactions must be "
                            + "held rather than silently overrunning as the source did")
                    .hasSize(StatementTransaction.LEGACY_MAX_TRANSACTIONS_PER_RUN + 1);
        }
    }

    @Nested
    @DisplayName("2. HIGH: the five diagnostic renderings publish no card number, account or amount")
    class DiagnosticRenderings {

        @Test
        @DisplayName("all five layout types override toString rather than inheriting the generated one")
        void allFiveLayoutTypesOverrideToString() {
            assertThat(List.of(StatementTransaction.class, Key.class, CardGroup.class,
                            ReportDetailLine.class, ReportTotalsLine.class))
                    .as("each of these carries a card number, an account identifier or a monetary figure, "
                            + "so the generated rendering - which emits every component - is unsafe on all "
                            + "five. Losing any one override silently reopens the leak")
                    .allSatisfy(type -> assertThat(Arrays.stream(type.getDeclaredMethods())
                            .map(method -> method.getName())
                            .filter("toString"::equals)
                            .toList())
                            .as("%s must declare its own toString", type.getSimpleName())
                            .containsExactly("toString"));
        }

        @Test
        @DisplayName("the record rendering emits the transaction identifier and nothing else")
        void theRecordRenderingEmitsTheTransactionIdentifierOnly() {
            final String rendered = baseline().toString();

            assertThat(rendered)
                    .isEqualTo("StatementTransaction[transactionId=" + SYNTHETIC_TRANSACTION_ID + "]");
            assertThat(rendered)
                    .as("the card number, both merchant text fields and the amount are all customer data "
                            + "and none may reach a log line")
                    .doesNotContain(SYNTHETIC_CARD_NUMBER)
                    .doesNotContain(SYNTHETIC_MERCHANT_NAME)
                    .doesNotContain(SYNTHETIC_MERCHANT_CITY)
                    .doesNotContain("76543");
            assertThat(rendered)
                    .as("no masked or last-four fragment of the card number is acceptable either, since "
                            + "each still discloses part of a value that should not appear at all")
                    .doesNotContain("7777")
                    .doesNotContain("4111");
        }

        @Test
        @DisplayName("the key rendering emits no card number, even though the card number leads the key")
        void theKeyRenderingEmitsNoCardNumber() {
            final String rendered = new Key(SYNTHETIC_CARD_NUMBER, SYNTHETIC_TRANSACTION_ID).toString();

            assertThat(rendered)
                    .isEqualTo("StatementTransaction.Key[transactionId=" + SYNTHETIC_TRANSACTION_ID + "]");
            assertThat(rendered)
                    .as("the card number is the LEADING key component, so the generated rendering would "
                            + "have published it first")
                    .doesNotContain(SYNTHETIC_CARD_NUMBER)
                    .doesNotContain("7777");
        }

        @Test
        @DisplayName("the card group rendering emits a count, not a card number and not its transactions")
        void theCardGroupRenderingEmitsACountOnly() {
            final String rendered =
                    new CardGroup(SYNTHETIC_CARD_NUMBER, List.of(baseline(), baseline())).toString();

            assertThat(rendered).isEqualTo("StatementTransaction.CardGroup[transactionCount=2]");
            assertThat(rendered)
                    .as("the generated rendering would have published the card number and then expanded "
                            + "every transaction in the list, multiplying the disclosure by its size")
                    .doesNotContain(SYNTHETIC_CARD_NUMBER)
                    .doesNotContain(SYNTHETIC_MERCHANT_NAME);
        }

        @Test
        @DisplayName("the detail line rendering emits no account identifier and no amount")
        void theDetailLineRenderingEmitsNoAccountIdentifierAndNoAmount() {
            final String rendered = detailLine().toString();

            assertThat(rendered).isEqualTo(
                    "StatementTransaction.ReportDetailLine[transactionId=" + SYNTHETIC_TRANSACTION_ID + "]");
            assertThat(rendered)
                    .as("this line carries an account identifier and a monetary amount; the generated "
                            + "rendering would have emitted both, along with the classification that says "
                            + "what the customer bought")
                    .doesNotContain(SYNTHETIC_ACCOUNT_ID)
                    .doesNotContain("76543")
                    .doesNotContain("RETAIL SYNTHETIC CATEGORY");
        }

        @Test
        @DisplayName("the totals line rendering emits the layout label and never the accumulated total")
        void theTotalsLineRenderingEmitsTheLabelOnly() {
            final ReportTotalsLine line = new ReportTotalsLine(StatementTransaction.ACCOUNT_TOTAL_LABEL,
                    StatementTransaction.ACCOUNT_TOTAL_LABEL_LENGTH,
                    StatementTransaction.ACCOUNT_TOTAL_DOTS_LENGTH, SYNTHETIC_AMOUNT);

            assertThat(line.toString()).isEqualTo(
                    "StatementTransaction.ReportTotalsLine[label=" + StatementTransaction.ACCOUNT_TOTAL_LABEL
                            + "]");
            assertThat(line.toString())
                    .as("an accumulated total is the most sensitive figure on the whole report, being every "
                            + "transaction for a card summed together")
                    .doesNotContain("76543");
        }

        @Test
        @DisplayName("none of the five discloses anything when interpolated into a message")
        void noneDisclosesAnythingWhenInterpolated() {
            final String interpolated = "statement run failed: " + baseline() + " "
                    + new Key(SYNTHETIC_CARD_NUMBER, SYNTHETIC_TRANSACTION_ID) + " "
                    + new CardGroup(SYNTHETIC_CARD_NUMBER, List.of(baseline())) + " " + detailLine() + " "
                    + new ReportTotalsLine(StatementTransaction.GRAND_TOTAL_LABEL,
                            StatementTransaction.GRAND_TOTAL_LABEL_LENGTH,
                            StatementTransaction.GRAND_TOTAL_DOTS_LENGTH, SYNTHETIC_AMOUNT);

            assertThat(interpolated)
                    .as("implicit toString through concatenation is exactly the path by which a generated "
                            + "rendering reaches a log without anyone deciding that it should")
                    .doesNotContain(SYNTHETIC_CARD_NUMBER)
                    .doesNotContain(SYNTHETIC_ACCOUNT_ID)
                    .doesNotContain(SYNTHETIC_MERCHANT_NAME)
                    .doesNotContain(SYNTHETIC_MERCHANT_CITY)
                    .doesNotContain("76543");
        }

        @Test
        @DisplayName("redacting the rendering does not redact the data behind the accessors")
        void redactingTheRenderingDoesNotRedactTheData() {
            final StatementTransaction record = baseline();

            assertThat(record.cardNumber()).isEqualTo(SYNTHETIC_CARD_NUMBER);
            assertThat(record.merchantName()).isEqualTo(SYNTHETIC_MERCHANT_NAME);
            assertThat(detailLine().accountId()).isEqualTo(SYNTHETIC_ACCOUNT_ID);
            assertThat(detailLine().amount())
                    .as("the batch writer still needs every value; only the rendering is narrowed")
                    .isEqualByComparingTo(SYNTHETIC_AMOUNT);
        }

        @Test
        @DisplayName("equality still spans every component, so redaction did not weaken identity")
        void equalityStillSpansEveryComponent() {
            assertThat(baseline())
                    .as("overriding toString must not disturb the generated equals and hashCode, which "
                            + "remain component-wise")
                    .isEqualTo(baseline())
                    .hasSameHashCodeAs(baseline());
            assertThat(withComponent("cardNumber", "4111000000000000"))
                    .as("two records differing only in a component the rendering omits must still be "
                            + "unequal - the rendering is narrowed, identity is not")
                    .isNotEqualTo(withComponent("cardNumber", "4111111111111111"));
        }
    }

    @Nested
    @DisplayName("3. Width guards - every string component bounded at its declared PIC width")
    class WidthGuards {

        @ParameterizedTest(name = "{1} PIC X({3}) at COSTM01.CPY:{2} -> {0}")
        @MethodSource("com.cardemo.unit.model.StatementTransactionApiContractTest#stringFieldContracts")
        @DisplayName("accepts a value at exactly the declared width and refuses one character more")
        void acceptsExactWidthAndRefusesOneMore(final String componentName, final String cobolItem,
                final int sourceLine, final int width) {
            assertThat(withComponent(componentName, "x".repeat(width)))
                    .as("%d characters is the largest legal value for %s at app/cpy/COSTM01.CPY:%d", width,
                            cobolItem, sourceLine)
                    .isNotNull();

            assertThatThrownBy(() -> withComponent(componentName, "x".repeat(width + 1)))
                    .as("one character more must be refused at construction, because a silent truncation "
                            + "here would corrupt the 350-byte image without any Java-visible symptom")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(componentName)
                    .hasMessageContaining(String.valueOf(width))
                    .hasMessageNotContaining("x".repeat(width + 1));
        }

        @Test
        @DisplayName("accepts null and blank, because absence and blankness are both source states")
        void acceptsNullAndBlank() {
            assertThat(new StatementTransaction(null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null))
                    .as("a width guard bounds length; it does not make a field mandatory, and the source "
                            + "distinguishes an absent value from a blank one")
                    .isNotNull();
            assertThat(withComponent("merchantName", " ".repeat(50)).merchantName())
                    .as("a fully blank fixed-width field is a legitimate image and must survive verbatim, "
                            + "neither trimmed nor collapsed to empty")
                    .isEqualTo(" ".repeat(50))
                    .hasSize(50);
        }

        @Test
        @DisplayName("preserves a leading-zero identifier and trailing padding verbatim")
        void preservesLeadingZerosAndTrailingPadding() {
            final StatementTransaction record = withComponent("merchantId", "000000001");

            assertThat(record.merchantId())
                    .as("TRNX-MERCHANT-ID is PIC 9(09) DISPLAY - a nine-byte zoned image, so its leading "
                            + "zeros are part of the value rather than an artefact of formatting")
                    .isEqualTo("000000001");
            assertThat(withComponent("filler", " ".repeat(20)).filler())
                    .as("the 20-byte filler is part of the record image and must round-trip untouched")
                    .hasSize(20);
        }
    }

    @Nested
    @DisplayName("4. Amount semantics - decimal only, scale 2, HALF_EVEN, sign never normalised")
    class AmountSemantics {

        @Test
        @DisplayName("carries no floating-point type in any monetary component")
        void carriesNoFloatingPointType() {
            final List<Class<?>> monetaryTypes = List.of(StatementTransaction.class,
                    ReportDetailLine.class, ReportTotalsLine.class);

            assertThat(monetaryTypes)
                    .allSatisfy(type -> assertThat(Arrays.stream(type.getRecordComponents())
                            .map(component -> component.getType().getName())
                            .filter(name -> name.contains("Float") || name.contains("Double")
                                    || "float".equals(name) || "double".equals(name))
                            .toList())
                            .as("%s must carry no float or double; the security gate asserts this by "
                                    + "inspection across every financial field", type.getSimpleName())
                            .isEmpty());
        }

        @Test
        @DisplayName("normalises to scale 2, which PIC S9(09)V99 fixes")
        void normalisesToScaleTwo() {
            assertThat(withComponent("typeCode", "01").amount().scale())
                    .as("TRNX-AMT is PIC S9(09)V99 at app/cpy/COSTM01.CPY:29, so the scale is two - not "
                            + "whatever scale the caller's literal happened to carry")
                    .isEqualTo(StatementTransaction.AMOUNT_SCALE);

            final StatementTransaction unscaled = new StatementTransaction(SYNTHETIC_CARD_NUMBER,
                    SYNTHETIC_TRANSACTION_ID, "01", "0001", "POS", "D", new BigDecimal("5"), "1", "M", "C",
                    "0", "T", "T", " ");

            assertThat(unscaled.amount())
                    .as("a scale-0 literal must be rescaled rather than stored as given, or the emitted "
                            + "image would be two bytes short")
                    .isEqualTo(new BigDecimal("5.00"));
        }

        @ParameterizedTest(name = "{0} rounds half-even to {1}")
        @CsvSource({"1.005, 1.00", "1.015, 1.02", "1.025, 1.02", "1.035, 1.04", "-1.005, -1.00",
            "-1.015, -1.02"})
        @DisplayName("rounds half to even, so a repeated .xx5 does not accumulate a bias")
        void roundsHalfToEven(final String supplied, final String expected) {
            final StatementTransaction record = new StatementTransaction(SYNTHETIC_CARD_NUMBER,
                    SYNTHETIC_TRANSACTION_ID, "01", "0001", "POS", "D", new BigDecimal(supplied), "1", "M",
                    "C", "0", "T", "T", " ");

            assertThat(record.amount())
                    .as("HALF_EVEN is the mandated mode; HALF_UP would round every .xx5 away from zero and "
                            + "drift against the baseline over a 300-record run")
                    .isEqualTo(new BigDecimal(expected));
        }

        @Test
        @DisplayName("preserves a negative amount rather than normalising its sign away")
        void preservesANegativeAmount() {
            final StatementTransaction record = new StatementTransaction(SYNTHETIC_CARD_NUMBER,
                    SYNTHETIC_TRANSACTION_ID, "01", "0001", "POS", "D", new BigDecimal("-42.50"), "1", "M",
                    "C", "0", "T", "T", " ");

            assertThat(record.amount())
                    .as("app/data/ASCII/dailytran.txt carries both { and } overpunches, so genuinely "
                            + "negative amounts exist and drive the cycle-debit branch. No absolute-value "
                            + "normalisation is permitted anywhere on this path")
                    .isEqualByComparingTo(new BigDecimal("-42.50"))
                    .isNegative();
        }

        @Test
        @DisplayName("refuses a magnitude beyond the nine integer digits the PIC clause permits")
        void refusesAMagnitudeBeyondNineIntegerDigits() {
            assertThat(new BigDecimal("999999999.99"))
                    .as("the largest value PIC S9(09)V99 can hold must be accepted")
                    .isEqualByComparingTo(new StatementTransaction(SYNTHETIC_CARD_NUMBER,
                            SYNTHETIC_TRANSACTION_ID, "01", "0001", "POS", "D",
                            new BigDecimal("999999999.99"), "1", "M", "C", "0", "T", "T", " ").amount());

            assertThatThrownBy(() -> new StatementTransaction(SYNTHETIC_CARD_NUMBER,
                    SYNTHETIC_TRANSACTION_ID, "01", "0001", "POS", "D", new BigDecimal("1000000000.00"),
                    "1", "M", "C", "0", "T", "T", " "))
                    .as("one more than the ceiling cannot be represented in the emitted image, so it must "
                            + "be refused rather than silently wrapped or truncated")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(String.valueOf(StatementTransaction.AMOUNT_INTEGER_DIGITS));
        }

        @Test
        @DisplayName("bounds the magnitude symmetrically, so a large negative is refused too")
        void boundsTheMagnitudeSymmetrically() {
            assertThatThrownBy(() -> new StatementTransaction(SYNTHETIC_CARD_NUMBER,
                    SYNTHETIC_TRANSACTION_ID, "01", "0001", "POS", "D", new BigDecimal("-1000000000.00"),
                    "1", "M", "C", "0", "T", "T", " "))
                    .as("the sign occupies an overpunch position rather than a byte of its own, so the "
                            + "negative range is the mirror of the positive one and must be bounded alike")
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("accepts a null amount, since the source distinguishes absent from zero")
        void acceptsANullAmount() {
            assertThat(new StatementTransaction(SYNTHETIC_CARD_NUMBER, SYNTHETIC_TRANSACTION_ID, "01",
                    "0001", "POS", "D", null, "1", "M", "C", "0", "T", "T", " ").amount())
                    .as("normalising null to zero would erase the distinction between a field that was "
                            + "never supplied and one that genuinely holds zero")
                    .isNull();
        }

        @Test
        @DisplayName("declares the two report masks exactly as CVTRA07Y spells them")
        void declaresTheTwoReportMasksExactly() {
            assertThat(StatementTransaction.DETAIL_AMOUNT_MASK)
                    .as("TRAN-REPORT-AMT at app/cpy/CVTRA07Y.cpy:30 is a MINUS-leading mask, so a positive "
                            + "detail amount renders with a leading space rather than a plus")
                    .isEqualTo("-ZZZ,ZZZ,ZZZ.ZZ")
                    .hasSize(StatementTransaction.AMOUNT_MASK_LENGTH);
            assertThat(StatementTransaction.TOTALS_AMOUNT_MASK)
                    .as("the three totals items at app/cpy/CVTRA07Y.cpy:54,:60,:66 are PLUS-leading, which "
                            + "is a different rendering of the same magnitude and must not be unified")
                    .isEqualTo("+ZZZ,ZZZ,ZZZ.ZZ")
                    .hasSize(StatementTransaction.AMOUNT_MASK_LENGTH);
            assertThat(StatementTransaction.DETAIL_AMOUNT_MASK)
                    .isNotEqualTo(StatementTransaction.TOTALS_AMOUNT_MASK);
        }
    }

    @Nested
    @DisplayName("5. Report detail line - app/cpy/CVTRA07Y.cpy:15-31, 114 bytes")
    class ReportDetailLineContract {

        @Test
        @DisplayName("its field and separator widths sum to the declared 114-byte line")
        void widthsSumToTheDeclaredLineLength() {
            final int fields = StatementTransaction.REPORT_TRANSACTION_ID_LENGTH
                    + StatementTransaction.REPORT_ACCOUNT_ID_LENGTH
                    + StatementTransaction.REPORT_TYPE_CODE_LENGTH
                    + StatementTransaction.REPORT_TYPE_DESCRIPTION_LENGTH
                    + StatementTransaction.REPORT_CATEGORY_CODE_LENGTH
                    + StatementTransaction.REPORT_CATEGORY_DESCRIPTION_LENGTH
                    + StatementTransaction.REPORT_SOURCE_LENGTH
                    + StatementTransaction.AMOUNT_MASK_LENGTH;

            assertThat(fields)
                    .as("CVTRA07Y declares 16+11+2+15+4+29+10 data bytes and a 15-byte mask, which is 102; "
                            + "the remaining 12 bytes of the 114-byte line are the seven FILLER separators "
                            + "at :17,:19,:21,:23,:25,:27,:29 plus the 2-byte trailer at :31")
                    .isEqualTo(102);
            assertThat(StatementTransaction.REPORT_DETAIL_LINE_LENGTH)
                    .as("the detail line is 114 bytes and the report line is 133, the difference being the "
                            + "19-byte left margin the report writer supplies")
                    .isEqualTo(114)
                    .isLessThan(StatementTransaction.REPORT_LINE_LENGTH);
        }

        @Test
        @DisplayName("declares exactly the eight components CVTRA07Y declares, in declaration order")
        void declaresExactlyEightComponentsInOrder() {
            assertThat(Arrays.stream(ReportDetailLine.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList())
                    .as("01 TRANSACTION-DETAIL-REPORT declares seven data items plus the amount; the "
                            + "FILLER separators are layout, not data, and are supplied by the writer")
                    .containsExactly("transactionId", "accountId", "typeCode", "typeDescription",
                            "categoryCode", "categoryDescription", "source", "amount");
        }

        @ParameterizedTest(name = "{1} PIC X({3}) at CVTRA07Y.cpy:{2} -> {0}")
        @MethodSource("com.cardemo.unit.model.StatementTransactionApiContractTest#detailLineFieldContracts")
        @DisplayName("accepts a value at exactly the declared width and refuses one character more")
        void acceptsExactWidthAndRefusesOneMore(final String componentName, final String cobolItem,
                final int sourceLine, final int width) {
            assertThat(detailLineWith(componentName, "x".repeat(width)))
                    .as("%d characters is the largest legal value for %s at app/cpy/CVTRA07Y.cpy:%d", width,
                            cobolItem, sourceLine)
                    .isNotNull();

            assertThatThrownBy(() -> detailLineWith(componentName, "x".repeat(width + 1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(componentName)
                    .hasMessageNotContaining("x".repeat(width + 1));
        }

        @Test
        @DisplayName("holds the code-to-description separator CVTRA07Y spells as a literal hyphen")
        void holdsTheCodeToDescriptionSeparator() {
            assertThat(StatementTransaction.REPORT_CODE_DESCRIPTION_SEPARATOR)
                    .as("the FILLER items at app/cpy/CVTRA07Y.cpy:21 and :25 both carry VALUE '-', which "
                            + "joins a code to its description on the rendered line")
                    .isEqualTo("-");
        }
    }

    @Nested
    @DisplayName("6. Report totals lines - exactly the three layouts CVTRA07Y:50-66 declares")
    class ReportTotalsLineContract {

        @Test
        @DisplayName("all three declared layouts satisfy the label-plus-dots invariant")
        void allThreeDeclaredLayoutsSatisfyTheInvariant() {
            assertThat(StatementTransaction.PAGE_TOTAL_LABEL_LENGTH
                    + StatementTransaction.PAGE_TOTAL_DOTS_LENGTH)
                    .isEqualTo(StatementTransaction.REPORT_TOTALS_LABEL_PLUS_DOTS_LENGTH);
            assertThat(StatementTransaction.ACCOUNT_TOTAL_LABEL_LENGTH
                    + StatementTransaction.ACCOUNT_TOTAL_DOTS_LENGTH)
                    .isEqualTo(StatementTransaction.REPORT_TOTALS_LABEL_PLUS_DOTS_LENGTH);
            assertThat(StatementTransaction.GRAND_TOTAL_LABEL_LENGTH
                    + StatementTransaction.GRAND_TOTAL_DOTS_LENGTH)
                    .as("all three totals layouts pad to the same 97 bytes before the mask, which is why "
                            + "one type can serve all three without inventing a fourth")
                    .isEqualTo(StatementTransaction.REPORT_TOTALS_LABEL_PLUS_DOTS_LENGTH);
            assertThat(StatementTransaction.REPORT_TOTALS_LABEL_PLUS_DOTS_LENGTH
                    + StatementTransaction.AMOUNT_MASK_LENGTH)
                    .isEqualTo(StatementTransaction.REPORT_TOTALS_LINE_LENGTH);
        }

        @Test
        @DisplayName("accepts each of the three declared label and width triples")
        void acceptsEachDeclaredTriple() {
            assertThat(new ReportTotalsLine(StatementTransaction.PAGE_TOTAL_LABEL,
                    StatementTransaction.PAGE_TOTAL_LABEL_LENGTH,
                    StatementTransaction.PAGE_TOTAL_DOTS_LENGTH, SYNTHETIC_AMOUNT).label())
                    .isEqualTo("Page Total");
            assertThat(new ReportTotalsLine(StatementTransaction.ACCOUNT_TOTAL_LABEL,
                    StatementTransaction.ACCOUNT_TOTAL_LABEL_LENGTH,
                    StatementTransaction.ACCOUNT_TOTAL_DOTS_LENGTH, SYNTHETIC_AMOUNT).label())
                    .as("the label reads 'Account Total' even though app/cbl/CBTRN03C.cbl breaks control "
                            + "on the CARD NUMBER; the mismatch is a preserved legacy quirk")
                    .isEqualTo("Account Total");
            assertThat(new ReportTotalsLine(StatementTransaction.GRAND_TOTAL_LABEL,
                    StatementTransaction.GRAND_TOTAL_LABEL_LENGTH,
                    StatementTransaction.GRAND_TOTAL_DOTS_LENGTH, SYNTHETIC_AMOUNT).label())
                    .isEqualTo("Grand Total");
        }

        @Test
        @DisplayName("refuses a label and width pair that no declared layout uses")
        void refusesAnUndeclaredLayout() {
            assertThatThrownBy(() -> new ReportTotalsLine("Card Total", 10, 87, SYNTHETIC_AMOUNT))
                    .as("CVTRA07Y declares exactly three totals layouts, so a fourth would emit a line the "
                            + "legacy report never produced - and the widths alone would not catch it, "
                            + "since 10 plus 87 also sums to 97")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("app/cpy/CVTRA07Y.cpy:50-66");
        }

        @Test
        @DisplayName("refuses a declared label carried at the wrong width")
        void refusesADeclaredLabelAtTheWrongWidth() {
            assertThatThrownBy(() -> new ReportTotalsLine(StatementTransaction.PAGE_TOTAL_LABEL,
                    StatementTransaction.ACCOUNT_TOTAL_LABEL_LENGTH,
                    StatementTransaction.ACCOUNT_TOTAL_DOTS_LENGTH, SYNTHETIC_AMOUNT))
                    .as("'Page Total' is declared at 11 bytes; carrying it at 13 would shift the dot run "
                            + "and misalign the amount column against every other page")
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("refuses widths that do not sum to the declared 97 bytes")
        void refusesWidthsThatDoNotSum() {
            assertThatThrownBy(() -> new ReportTotalsLine(StatementTransaction.PAGE_TOTAL_LABEL,
                    StatementTransaction.PAGE_TOTAL_LABEL_LENGTH,
                    StatementTransaction.PAGE_TOTAL_DOTS_LENGTH + 1, SYNTHETIC_AMOUNT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(
                            String.valueOf(StatementTransaction.REPORT_TOTALS_LABEL_PLUS_DOTS_LENGTH));
        }

        @Test
        @DisplayName("refuses a null label, which would render as the four characters 'null'")
        void refusesANullLabel() {
            assertThatThrownBy(() -> new ReportTotalsLine(null, 11, 86, SYNTHETIC_AMOUNT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("label");
        }

        @Test
        @DisplayName("normalises the total to scale 2 exactly as the detail amount is normalised")
        void normalisesTheTotalToScaleTwo() {
            assertThat(new ReportTotalsLine(StatementTransaction.GRAND_TOTAL_LABEL,
                    StatementTransaction.GRAND_TOTAL_LABEL_LENGTH,
                    StatementTransaction.GRAND_TOTAL_DOTS_LENGTH, new BigDecimal("7")).total())
                    .as("a totals line and a detail line render through masks of the same width, so their "
                            + "scales must agree or the columns would not line up")
                    .isEqualTo(new BigDecimal("7.00"));
        }
    }

    @Nested
    @DisplayName("7. Card group - defensive copy in both directions, order preserved")
    class CardGroupContract {

        @Test
        @DisplayName("preserves encounter order, which the upstream sort guarantees")
        void preservesEncounterOrder() {
            final StatementTransaction first = withComponent("transactionId", "0000000000000001");
            final StatementTransaction second = withComponent("transactionId", "0000000000000002");

            assertThat(new CardGroup(SYNTHETIC_CARD_NUMBER, List.of(first, second)).transactions())
                    .as("app/cbl/CBSTM03A.CBL:416-456 exits its linear scan early when the stored card "
                            + "number exceeds the sought one, which is only correct while the ordering the "
                            + "upstream sort established still holds")
                    .containsExactly(first, second);
        }

        @Test
        @DisplayName("takes a defensive copy on construction, so a later caller mutation cannot reach it")
        void takesADefensiveCopyOnConstruction() {
            final List<StatementTransaction> supplied = new ArrayList<>(List.of(baseline()));
            final CardGroup group = new CardGroup(SYNTHETIC_CARD_NUMBER, supplied);

            supplied.add(baseline());

            assertThat(group.transactions())
                    .as("without a copy on construction, a caller retaining the list could grow the "
                            + "group's contents after it was validated")
                    .hasSize(1);
        }

        @Test
        @DisplayName("returns an unmodifiable list, so a caller cannot mutate it through the accessor")
        void returnsAnUnmodifiableList() {
            final CardGroup group = new CardGroup(SYNTHETIC_CARD_NUMBER, List.of(baseline()));

            assertThatThrownBy(() -> group.transactions().add(baseline()))
                    .as("the guarantee must hold on access as well as on construction")
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("accepts an empty group, since a card may legitimately have no transactions")
        void acceptsAnEmptyGroup() {
            assertThat(new CardGroup(SYNTHETIC_CARD_NUMBER, List.of()).transactions()).isEmpty();
        }

        @Test
        @DisplayName("refuses a null list and a null element, which would fail later and further away")
        void refusesNullListAndNullElement() {
            assertThatThrownBy(() -> new CardGroup(SYNTHETIC_CARD_NUMBER, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("transactions");

            final List<StatementTransaction> withNull = new ArrayList<>();
            withNull.add(baseline());
            withNull.add(null);

            assertThatThrownBy(() -> new CardGroup(SYNTHETIC_CARD_NUMBER, withNull))
                    .as("a null element would survive construction and fail inside the statement writer, "
                            + "where the cause would be far harder to attribute")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("null element");
        }

        @Test
        @DisplayName("bounds the card number at its declared width, naming the field and not the value")
        void boundsTheCardNumberWidth() {
            assertThatThrownBy(() -> new CardGroup("4".repeat(17), List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cardNumber")
                    .hasMessageNotContaining("4".repeat(17));
        }
    }

    @Nested
    @DisplayName("8. Statement output geometry - the 80 and 100 byte records CREASTMT declares")
    class StatementOutputGeometry {

        @Test
        @DisplayName("declares the text and HTML record lengths the JCL declares, and keeps them distinct")
        void declaresBothStatementRecordLengths() {
            assertThat(StatementTransaction.STATEMENT_TEXT_RECORD_LENGTH)
                    .as("STMTFILE is declared LRECL=80 at app/jcl/CREASTMT.JCL STEP040")
                    .isEqualTo(80);
            assertThat(StatementTransaction.STATEMENT_HTML_RECORD_LENGTH)
                    .as("HTMLFILE is declared LRECL=100 at the same step, and the 100-character work field "
                            + "at app/cbl/CBSTM03A.CBL:149 independently confirms it. The 80-versus-100 "
                            + "mismatch against that job's pre-delete step is a logged legacy defect, so "
                            + "the width is taken from the execution step rather than reconciled")
                    .isEqualTo(100)
                    .isNotEqualTo(StatementTransaction.STATEMENT_TEXT_RECORD_LENGTH);
        }

        @Test
        @DisplayName("declares the report name headers at the widths CVTRA07Y implies")
        void declaresTheReportNameHeaders() {
            assertThat(StatementTransaction.REPORT_SHORT_NAME)
                    .isEqualTo("DALYREPT")
                    .hasSizeLessThanOrEqualTo(StatementTransaction.REPORT_SHORT_NAME_LENGTH);
            assertThat(StatementTransaction.REPORT_LONG_NAME)
                    .isEqualTo("Daily Transaction Report")
                    .hasSizeLessThanOrEqualTo(StatementTransaction.REPORT_LONG_NAME_LENGTH);
            assertThat(StatementTransaction.REPORT_DATE_HEADER)
                    .as("app/cpy/CVTRA07Y.cpy:9-10 spells the date range header as the literal "
                            + "'Date Range: ', twelve bytes including the trailing space")
                    .isEqualTo("Date Range: ")
                    .hasSize(12);
            assertThat(StatementTransaction.REPORT_DATE_SEPARATOR)
                    .as("the FILLER between the two dates at app/cpy/CVTRA07Y.cpy:12 is PIC X(04) "
                            + "VALUE ' to ', so it carries a leading and a trailing space")
                    .isEqualTo(" to ")
                    .hasSize(4);
            assertThat(StatementTransaction.REPORT_DATE_LENGTH)
                    .as("both REPT-START-DATE and REPT-END-DATE are PIC X(10)")
                    .isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("9. Derived readings and the three named totals factories")
    class DerivedReadingsAndNamedFactories {

        @Test
        @DisplayName("significantProcessingTimestamp reproduces the two-byte truncation CREASTMT STEP010 imposes")
        void significantProcessingTimestampReproducesTheProjectionTruncation() {
            assertThat(withComponent("processingTimestamp", "2026-07-24-14.30.00.000000")
                    .significantProcessingTimestamp())
                    .as("the OUTREC projection of app/jcl/CREASTMT.JCL:STEP010 copies 50 bytes from offset "
                            + "279, which is the whole 26-byte originating timestamp plus only the first 24 "
                            + "of the 26 processing-timestamp bytes. The truncation is reproduced, never repaired")
                    .isEqualTo("2026-07-24-14.30.00.0000")
                    .hasSize(StatementTransaction.PROCESSING_TIMESTAMP_SIGNIFICANT_LENGTH);
        }

        @Test
        @DisplayName("significantProcessingTimestamp returns a value at or below the significant length unchanged")
        void significantProcessingTimestampLeavesAShorterValueUntouched() {
            assertThat(baseline().significantProcessingTimestamp())
                    .as("the fixture is already exactly 24 characters, so nothing is removed and nothing "
                            + "is padded")
                    .isEqualTo("2026-07-24-14.30.00.0000");
            assertThat(withComponent("processingTimestamp", "2026-07-24").significantProcessingTimestamp())
                    .as("a shorter value is never padded up to the significant length")
                    .isEqualTo("2026-07-24");
        }

        @Test
        @DisplayName("significantProcessingTimestamp keeps an absent timestamp absent rather than blank")
        void significantProcessingTimestampKeepsAbsenceDistinct() {
            assertThat(withComponent("processingTimestamp", null).significantProcessingTimestamp())
                    .as("absence stays distinguishable from a blank-padded value")
                    .isNull();
        }

        @Test
        @DisplayName("hasSameAmountAs compares by numeric value, so differing scale is still equal")
        void hasSameAmountAsComparesByValueRatherThanScale() {
            final StatementTransaction transaction = withComponent("transactionId", SYNTHETIC_TRANSACTION_ID);
            assertThat(transaction.hasSameAmountAs(transaction.amount().setScale(6)))
                    .as("BigDecimal.equals is scale-sensitive and would report these unequal, which is never "
                            + "the intended business comparison. compareTo is the sanctioned path")
                    .isTrue();
            assertThat(transaction.hasSameAmountAs(transaction.amount().add(new BigDecimal("0.01"))))
                    .as("a genuinely different value is still reported as different")
                    .isFalse();
        }

        @Test
        @DisplayName("hasSameAmountAs treats two absent amounts as equal and one absent amount as unequal")
        void hasSameAmountAsHandlesAbsenceOnEitherSide() {
            final StatementTransaction present = baseline();
            final StatementTransaction absent = new StatementTransaction(SYNTHETIC_CARD_NUMBER,
                    SYNTHETIC_TRANSACTION_ID, "01", "0001", "POS", "SYNTHETIC PURCHASE", null,
                    "000000123", SYNTHETIC_MERCHANT_NAME, SYNTHETIC_MERCHANT_CITY, "0000012345",
                    "2026-07-24-14.30.00.000000", "2026-07-24-14.30.00.0000", " ".repeat(20));

            assertThat(absent.hasSameAmountAs(null))
                    .as("both absent is the one case that compares equal")
                    .isTrue();
            assertThat(absent.hasSameAmountAs(present.amount()))
                    .as("exactly one absent is never equal")
                    .isFalse();
            assertThat(present.hasSameAmountAs(null))
                    .as("exactly one absent is never equal, whichever side it is on")
                    .isFalse();
        }

        @Test
        @DisplayName("pageTotal builds the CVTRA07Y:50-54 triple")
        void pageTotalBuildsItsDeclaredTriple() {
            final ReportTotalsLine line = ReportTotalsLine.pageTotal(new BigDecimal("12.34"));
            assertThat(line.label()).isEqualTo(StatementTransaction.PAGE_TOTAL_LABEL);
            assertThat(line.labelWidth()).isEqualTo(StatementTransaction.PAGE_TOTAL_LABEL_LENGTH);
            assertThat(line.dotsWidth()).isEqualTo(StatementTransaction.PAGE_TOTAL_DOTS_LENGTH);
            assertThat(line.total()).isEqualByComparingTo("12.34");
        }

        @Test
        @DisplayName("accountTotal builds the CVTRA07Y:56-60 triple, keeping the mislabelled legacy literal")
        void accountTotalBuildsItsDeclaredTripleAndKeepsTheLegacyLabel() {
            final ReportTotalsLine line = ReportTotalsLine.accountTotal(new BigDecimal("56.78"));
            assertThat(line.label())
                    .as("the control break that emits this line fires on the card number rather than the "
                            + "account, per app/cbl/CBTRN03C.cbl:181 and :306-310, yet the emitted literal "
                            + "still reads Account Total. The quirk is preserved, never corrected")
                    .isEqualTo(StatementTransaction.ACCOUNT_TOTAL_LABEL)
                    .isEqualTo("Account Total");
            assertThat(line.labelWidth()).isEqualTo(StatementTransaction.ACCOUNT_TOTAL_LABEL_LENGTH);
            assertThat(line.dotsWidth()).isEqualTo(StatementTransaction.ACCOUNT_TOTAL_DOTS_LENGTH);
            assertThat(line.total()).isEqualByComparingTo("56.78");
        }

        @Test
        @DisplayName("grandTotal builds the CVTRA07Y:62-66 triple")
        void grandTotalBuildsItsDeclaredTriple() {
            final ReportTotalsLine line = ReportTotalsLine.grandTotal(new BigDecimal("90.12"));
            assertThat(line.label()).isEqualTo(StatementTransaction.GRAND_TOTAL_LABEL);
            assertThat(line.labelWidth()).isEqualTo(StatementTransaction.GRAND_TOTAL_LABEL_LENGTH);
            assertThat(line.dotsWidth()).isEqualTo(StatementTransaction.GRAND_TOTAL_DOTS_LENGTH);
            assertThat(line.total()).isEqualByComparingTo("90.12");
        }

        @Test
        @DisplayName("each named factory accepts an absent total, leaving it absent")
        void eachNamedFactoryAcceptsAnAbsentTotal() {
            assertThat(ReportTotalsLine.pageTotal(null).total()).isNull();
            assertThat(ReportTotalsLine.accountTotal(null).total()).isNull();
            assertThat(ReportTotalsLine.grandTotal(null).total()).isNull();
        }
    }
}
