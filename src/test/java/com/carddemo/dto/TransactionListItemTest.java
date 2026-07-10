package com.carddemo.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pure, framework-free unit test for {@link TransactionListItem}, the immutable
 * row DTO of the legacy CardDemo Transaction List screen (BMS map
 * {@code COTRN00}, CICS transaction {@code CT00}).
 *
 * <p>These assertions lock down the COBOL&rarr;Java migration contract for a
 * single transaction-list row (source referenced by SHA {@code 27d6c6f}, not
 * copied into the target):</p>
 * <ul>
 *   <li>the JSON wire contract exposes exactly the four components
 *       {@code transactionId}, {@code transactionDate}, {@code description} and
 *       {@code amount}, and a value survives a serialize/deserialize round-trip
 *       unchanged;</li>
 *   <li>{@code transactionId} stays a {@link String}, preserving the fixed
 *       16-character {@code TRAN-ID} / {@code TRNIDnn} ({@code PIC X(16)}) width
 *       rather than collapsing into a number;</li>
 *   <li>{@code amount} maps {@code TRAN-AMT} ({@code PIC S9(09)V99}, copybook
 *       {@code CVTRA05Y}) to a scale-2 {@link BigDecimal} that always serializes
 *       as a <em>plain</em> decimal literal (for example {@code 100.00}, never
 *       {@code 1.0E2} or {@code 100.0}) — financial values never use
 *       {@code float}/{@code double}, including at the signed and
 *       maximum-magnitude boundaries.</li>
 * </ul>
 *
 * <p>The test loads no Spring context and touches no database, file, network, or
 * AWS resource; every check is an in-memory serialization or reflection
 * assertion built on the shared {@link DtoTestSupport} helpers, so the suite is
 * fast and deterministic. The rationale for the money-as-plain, scale-2 decision
 * lives in {@code docs/decision-log.md}, not in these comments.</p>
 */
@DisplayName("TransactionListItem — CT00 transaction-list row DTO")
class TransactionListItemTest {

    /** A representative 16-character {@code TRAN-ID} ({@code PIC X(16)}). */
    private static final String TRANSACTION_ID = "0000000000000123";

    /** An 8-character display date column ({@code TDATE PIC X(8)}, yy/mm/dd). */
    private static final String TRANSACTION_DATE = "24/01/31";

    /** A description within the 26-character {@code TDESC} list-row width. */
    private static final String DESCRIPTION = "POS PURCHASE - STORE #123";

    /**
     * The maximum magnitude representable by {@code TRAN-AMT PIC S9(09)V99}:
     * nine integer digits and two fraction digits.
     */
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999.99");

    // ---------------------------------------------------------------------
    // Phase 1 — JSON round-trip and wire contract
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("round-trips through JSON preserving every component (scale-2 money included)")
    void jsonRoundTripPreservesAllComponents() {
        TransactionListItem item = new TransactionListItem(
                TRANSACTION_ID, TRANSACTION_DATE, DESCRIPTION, DtoTestSupport.sampleMoney());

        TransactionListItem restored = DtoTestSupport.roundTrip(item, TransactionListItem.class);

        // Record value-equality is scale-sensitive for BigDecimal, so equality
        // here also proves the money scale (2) survives the round-trip.
        assertThat(restored).isEqualTo(item);
        assertThat(restored.transactionId()).isEqualTo(TRANSACTION_ID);
        assertThat(restored.transactionDate()).isEqualTo(TRANSACTION_DATE);
        assertThat(restored.description()).isEqualTo(DESCRIPTION);
        assertThat(restored.amount()).isEqualByComparingTo(DtoTestSupport.sampleMoney());
        DtoTestSupport.assertScale(restored.amount(), 2);
    }

    @Test
    @DisplayName("serializes exactly the four expected JSON keys")
    void serializesTheFourExpectedJsonKeys() {
        TransactionListItem item = new TransactionListItem(
                TRANSACTION_ID, TRANSACTION_DATE, DESCRIPTION, DtoTestSupport.sampleMoney());

        String json = DtoTestSupport.toJson(item);

        assertThat(json).contains(
                "\"transactionId\":",
                "\"transactionDate\":",
                "\"description\":",
                "\"amount\":");
        // The record's components are the single source of truth for those keys.
        assertThat(DtoTestSupport.componentNames(TransactionListItem.class))
                .containsExactlyInAnyOrder("transactionid", "transactiondate", "description", "amount");
    }

    @Test
    @DisplayName("transactionId stays a String preserving the 16-character TRAN-ID width")
    void transactionIdStaysStringPreserving16CharWidth() {
        TransactionListItem item = new TransactionListItem(
                TRANSACTION_ID, TRANSACTION_DATE, DESCRIPTION, DtoTestSupport.sampleMoney());

        assertThat(recordComponentType("transactionId")).isEqualTo(String.class);
        assertThat(item.transactionId()).hasSize(16);

        // Survives the wire round-trip as the identical 16-character String.
        TransactionListItem restored = DtoTestSupport.roundTrip(item, TransactionListItem.class);
        assertThat(restored.transactionId())
                .isInstanceOf(String.class)
                .isEqualTo(TRANSACTION_ID)
                .hasSize(16);
    }

    // ---------------------------------------------------------------------
    // Phase 2 — money serialized PLAIN, scale 2 (CRITICAL)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("amount 100.00 serializes as the plain literal 100.00 with scale 2")
    void amountOfOneHundredSerializesAsPlainScale2() {
        TransactionListItem item = new TransactionListItem(
                TRANSACTION_ID, TRANSACTION_DATE, DESCRIPTION, new BigDecimal("100.00"));

        String json = DtoTestSupport.toJson(item);

        // Never 1.0E2 and never 100.0 — the exact fixed-scale COBOL money form.
        DtoTestSupport.assertJsonNumberIsPlain(json, "amount", "100.00");
        DtoTestSupport.assertScale(item.amount(), 2);
    }

    @Test
    @DisplayName("maximum S9(09)V99 amount 999999999.99 serializes plain with scale 2")
    void maxAmountSerializesAsPlainScale2() {
        TransactionListItem item = new TransactionListItem(
                TRANSACTION_ID, TRANSACTION_DATE, DESCRIPTION, MAX_AMOUNT);

        String json = DtoTestSupport.toJson(item);

        DtoTestSupport.assertJsonNumberIsPlain(json, "amount", "999999999.99");
        DtoTestSupport.assertScale(item.amount(), 2);

        // The boundary value also survives the round-trip with scale intact.
        TransactionListItem restored = DtoTestSupport.roundTrip(item, TransactionListItem.class);
        assertThat(restored.amount()).isEqualByComparingTo(MAX_AMOUNT);
        DtoTestSupport.assertScale(restored.amount(), 2);
    }

    @Test
    @DisplayName("amount component type is java.math.BigDecimal, never float/double")
    void amountComponentTypeIsBigDecimalNotFloatingPoint() {
        Class<?> amountType = recordComponentType("amount");

        assertThat(amountType).isEqualTo(BigDecimal.class);
        assertThat(amountType)
                .isNotEqualTo(double.class)
                .isNotEqualTo(Double.class)
                .isNotEqualTo(float.class)
                .isNotEqualTo(Float.class);
    }

    @Test
    @DisplayName("@Digits money contract accepts the S9(09)V99 max and rejects over-width")
    void amountDigitsContractAcceptsMaxAndRejectsOverWidth() {
        TransactionListItem validMax = new TransactionListItem(
                TRANSACTION_ID, TRANSACTION_DATE, DESCRIPTION, MAX_AMOUNT);
        assertThat(DtoTestSupport.validate(validMax)).isEmpty();

        // Ten integer digits exceeds PIC S9(09) -> a bean-validation violation on amount.
        TransactionListItem overWidth = new TransactionListItem(
                TRANSACTION_ID, TRANSACTION_DATE, DESCRIPTION, new BigDecimal("1000000000.00"));
        var violations = DtoTestSupport.validate(overWidth);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anySatisfy(
                violation -> assertThat(violation.getPropertyPath().toString()).isEqualTo("amount"));
    }

    // ---------------------------------------------------------------------
    // Phase 3 — negative (signed) amounts
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("negative amount -50.00 round-trips and serializes plain with scale 2")
    void negativeAmountRoundTripsAndSerializesPlainScale2() {
        BigDecimal negative = new BigDecimal("-50.00");
        TransactionListItem item = new TransactionListItem(
                TRANSACTION_ID, TRANSACTION_DATE, DESCRIPTION, negative);

        String json = DtoTestSupport.toJson(item);
        DtoTestSupport.assertJsonNumberIsPlain(json, "amount", "-50.00");

        TransactionListItem restored = DtoTestSupport.roundTrip(item, TransactionListItem.class);
        assertThat(restored).isEqualTo(item);
        assertThat(restored.amount()).isEqualByComparingTo(negative);
        DtoTestSupport.assertScale(restored.amount(), 2);
    }

    // ---------------------------------------------------------------------
    // Reflection helper
    // ---------------------------------------------------------------------

    /**
     * Returns the declared type of the named {@link TransactionListItem} record
     * component, failing the test if no such component exists.
     *
     * @param componentName the record component name to resolve
     * @return the component's declared {@link Class}
     */
    private static Class<?> recordComponentType(String componentName) {
        for (RecordComponent component : TransactionListItem.class.getRecordComponents()) {
            if (component.getName().equals(componentName)) {
                return component.getType();
            }
        }
        throw new AssertionError(
                "TransactionListItem has no record component named '" + componentName + "'");
    }
}
