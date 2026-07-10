package com.carddemo.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pure-POJO unit tests for the {@link TransactionCategoryBalance} JPA entity (the Java
 * translation of COBOL {@code TRAN-CAT-BAL-RECORD}, copybook {@code CVTRA01Y}, record
 * length 50, source commit {@code 27d6c6f} — read-only reference, not copied into this
 * repository).
 *
 * <p>Two aspects are exercised, both migration-critical for this record:</p>
 * <ul>
 *   <li><b>Money field {@code tranCatBal}</b> — {@code TRAN-CAT-BAL PIC S9(09)V99} maps to a
 *       {@link BigDecimal} of {@code NUMERIC(11,2)} (nine integer digits plus two fraction
 *       digits). These tests assert the value keeps <em>scale 2</em>, preserves its sign,
 *       survives {@link RoundingMode#HALF_UP} rounding, and normalises zero to a canonical
 *       scale-2 form. Numeric equality is asserted with
 *       {@link BigDecimal#compareTo(BigDecimal) compareTo} (scale-insensitive value equality)
 *       rather than {@link BigDecimal#equals(Object) equals}; no {@code float}/{@code double}
 *       appears anywhere, honouring the decimal-precision rule (AAP §0.8.2 / Gate 2).</li>
 *   <li><b>Composite key {@code id}</b> — the three-part {@code TRAN-CAT-KEY} group lives in
 *       the {@code @Embeddable} {@link TransactionCategoryBalanceId} and is exposed through the
 *       {@code @EmbeddedId} {@link TransactionCategoryBalance#getId() id} property. These tests
 *       verify the embedded key round-trips intact through the entity accessor and that each
 *       key component surfaces correctly. The key columns are intentionally not re-declared as
 *       separate entity fields, so they are validated only via the embedded id.</li>
 * </ul>
 *
 * <p>This is a framework-free test: no Spring context, no persistence, no Testcontainers, and
 * no mocks are involved. Every assertion is an in-memory getter/setter round-trip on a plain
 * object instance, so the suite runs fast and deterministically and contributes to line
 * coverage (JaCoCo).</p>
 */
class TransactionCategoryBalanceTest {

    // ---------------------------------------------------------------------
    // Phase 2 — Money-field (tranCatBal) decimal-fidelity tests
    // ---------------------------------------------------------------------

    /**
     * A full-width {@code S9(09)V99} balance (nine integer digits, two fraction digits) must
     * round-trip through the accessor while keeping exactly scale 2, and its value must be
     * unchanged when compared with {@link BigDecimal#compareTo(BigDecimal)}.
     */
    @Test
    @DisplayName("tranCatBal preserves BigDecimal scale 2 (TRAN-CAT-BAL PIC S9(09)V99)")
    void tranCatBalPreservesScaleTwo() {
        TransactionCategoryBalance balanceRecord = new TransactionCategoryBalance();
        // Maximum-width positive balance for S9(09)V99: 9 integer digits + 2 fraction digits.
        BigDecimal expected = new BigDecimal("123456789.12");

        balanceRecord.setTranCatBal(expected);

        BigDecimal actual = balanceRecord.getTranCatBal();
        assertThat(actual.scale()).isEqualTo(2);
        // compareTo == 0 is scale-insensitive value equality (never equals()).
        assertThat(actual.compareTo(expected)).isZero();
    }

    /**
     * The COBOL {@code S} (signed) picture means negative balances are legal; the entity must
     * preserve the sign, the scale, and the numeric value of a negative balance.
     */
    @Test
    @DisplayName("tranCatBal preserves a negative sign (COBOL S = signed)")
    void tranCatBalPreservesSign() {
        TransactionCategoryBalance balanceRecord = new TransactionCategoryBalance();
        BigDecimal expected = new BigDecimal("-250.75");

        balanceRecord.setTranCatBal(expected);

        BigDecimal actual = balanceRecord.getTranCatBal();
        assertThat(actual.signum()).isEqualTo(-1);
        assertThat(actual.scale()).isEqualTo(2);
        assertThat(actual.compareTo(expected)).isZero();
    }

    /**
     * A value rounded to scale 2 with {@link RoundingMode#HALF_UP} (reproducing COBOL rounding
     * at each computation) must round-trip unchanged: {@code 0.005} rounds up to {@code 0.01}.
     */
    @Test
    @DisplayName("tranCatBal round-trips a HALF_UP rounded value (0.005 -> 0.01)")
    void tranCatBalRoundingHalfUp() {
        TransactionCategoryBalance balanceRecord = new TransactionCategoryBalance();
        BigDecimal rounded = new BigDecimal("0.005").setScale(2, RoundingMode.HALF_UP);

        balanceRecord.setTranCatBal(rounded);

        BigDecimal actual = balanceRecord.getTranCatBal();
        assertThat(actual.scale()).isEqualTo(2);
        assertThat(actual.compareTo(new BigDecimal("0.01"))).isZero();
    }

    /**
     * A zero balance must retain its canonical scale-2 representation ({@code 0.00}) and still
     * compare equal to {@link BigDecimal#ZERO} by value.
     */
    @Test
    @DisplayName("tranCatBal keeps a canonical scale-2 zero (0.00)")
    void tranCatBalZeroCanonicalScale() {
        TransactionCategoryBalance balanceRecord = new TransactionCategoryBalance();
        BigDecimal zero = new BigDecimal("0.00");

        balanceRecord.setTranCatBal(zero);

        BigDecimal actual = balanceRecord.getTranCatBal();
        assertThat(actual.scale()).isEqualTo(2);
        // BigDecimal.ZERO has scale 0; compareTo confirms value equality regardless of scale.
        assertThat(actual.compareTo(BigDecimal.ZERO)).isZero();
    }

    // ---------------------------------------------------------------------
    // Phase 3 — Embedded-id (@EmbeddedId) wiring tests
    // ---------------------------------------------------------------------

    /**
     * The composite key set via {@link TransactionCategoryBalance#setId(TransactionCategoryBalanceId)}
     * must round-trip through {@link TransactionCategoryBalance#getId()} intact — the returned key
     * is value-equal to the one supplied, and each of its three components
     * ({@code trancatAcctId}, {@code trancatTypeCd}, {@code trancatCd}) surfaces correctly.
     */
    @Test
    @DisplayName("@EmbeddedId round-trips the composite key and exposes all three components")
    void embeddedIdRoundTrip() {
        TransactionCategoryBalance balanceRecord = new TransactionCategoryBalance();
        TransactionCategoryBalanceId key = new TransactionCategoryBalanceId(1001L, "01", 5);

        balanceRecord.setId(key);

        TransactionCategoryBalanceId actual = balanceRecord.getId();
        // Value-based equality: the embedded key is returned intact.
        assertThat(actual).isEqualTo(key);
        // Each key component surfaces through the entity's embedded id.
        assertThat(actual.getTrancatAcctId()).isEqualTo(Long.valueOf(1001L));
        assertThat(actual.getTrancatTypeCd()).isEqualTo("01");
        assertThat(actual.getTrancatCd()).isEqualTo(Integer.valueOf(5));
    }

    /**
     * Record-assembly sanity check: with both the embedded id and the money field populated,
     * the entity returns the composite key intact and the balance at scale 2 — the two halves
     * of the {@code TRAN-CAT-BAL-RECORD} coexist without interfering.
     */
    @Test
    @DisplayName("full record composes an embedded id and a scale-2 balance together")
    void fullRecordCompose() {
        TransactionCategoryBalance balanceRecord = new TransactionCategoryBalance();
        TransactionCategoryBalanceId key = new TransactionCategoryBalanceId(2002L, "CR", 99);
        BigDecimal balance = new BigDecimal("123.45");

        balanceRecord.setId(key);
        balanceRecord.setTranCatBal(balance);

        assertThat(balanceRecord.getId()).isEqualTo(key);
        BigDecimal actualBalance = balanceRecord.getTranCatBal();
        assertThat(actualBalance.scale()).isEqualTo(2);
        assertThat(actualBalance.compareTo(balance)).isZero();
    }
}
