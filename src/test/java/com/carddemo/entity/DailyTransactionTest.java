package com.carddemo.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plain-POJO unit tests for the {@link DailyTransaction} JPA entity — the Java
 * translation of the legacy COBOL {@code DALYTRAN-RECORD} (copybook
 * {@code CVTRA06Y}, fixed record length 350, source commit {@code 27d6c6f},
 * read-only reference).
 *
 * <p>The staging record is byte-parallel to the posted-transaction layout but
 * carries the distinct {@code DALYTRAN-} field prefix and is persisted to its own
 * {@code daily_transaction} table, so it is verified independently here.</p>
 *
 * <p><strong>Primary focus (decimal fidelity):</strong> the monetary field
 * {@code dalytranAmt} ({@code DALYTRAN-AMT PIC S9(09)V99}) must round-trip through
 * {@link BigDecimal} at <em>scale 2</em>, preserving sign and magnitude with no
 * {@code float}/{@code double} substitution anywhere (AAP §0.8.2 / Gate 2). Every
 * numeric equality is asserted with {@link BigDecimal#compareTo(BigDecimal)} (so
 * {@code 0.00} and {@code 0} compare equal) and the exact {@link BigDecimal#scale()
 * scale} is asserted explicitly.</p>
 *
 * <p><strong>Secondary focus (interface contract):</strong> the two timestamp
 * fields {@code dalytranOrigTs} / {@code dalytranProcTs}
 * ({@code DALYTRAN-ORIG-TS} / {@code DALYTRAN-PROC-TS PIC X(26)}) are retained as
 * fixed 26-character {@link String} values — never {@code java.time.LocalDateTime}
 * — to preserve the exact on-file textual format byte-for-byte (Gate 5).</p>
 *
 * <p>This is a framework-free test: no Spring context, no persistence, no
 * Testcontainers, and no mocks — only the entity's public no-arg constructor and
 * its generated accessors are exercised.</p>
 */
class DailyTransactionTest {

    // ---------------------------------------------------------------------
    // Phase 2 — Money-field (dalytranAmt) decimal-fidelity tests
    // ---------------------------------------------------------------------

    /**
     * The maximum-magnitude {@code S9(09)V99} value (nine integer digits plus two
     * fractional digits) must round-trip through the accessor unchanged: the stored
     * amount keeps scale 2 and compares equal to the value that was set.
     */
    @Test
    @DisplayName("dalytranAmt preserves scale 2 at the maximum S9(09)V99 magnitude")
    void dalytranAmtPreservesScaleTwo() {
        DailyTransaction tx = new DailyTransaction();
        BigDecimal maxAmount = new BigDecimal("123456789.12");

        tx.setDalytranAmt(maxAmount);

        BigDecimal stored = tx.getDalytranAmt();
        assertThat(stored.scale()).isEqualTo(2);
        assertThat(stored.compareTo(new BigDecimal("123456789.12"))).isZero();
    }

    /**
     * A negative amount must retain its sign and its scale-2 precision: the signed
     * packed-decimal semantics of {@code COMP-3}/{@code PIC S9(09)V99} are preserved
     * through {@link BigDecimal}.
     */
    @Test
    @DisplayName("dalytranAmt preserves a negative sign together with scale 2")
    void dalytranAmtPreservesSign() {
        DailyTransaction tx = new DailyTransaction();
        BigDecimal negativeAmount = new BigDecimal("-1000000.55");

        tx.setDalytranAmt(negativeAmount);

        BigDecimal stored = tx.getDalytranAmt();
        assertThat(stored.signum()).isEqualTo(-1);
        assertThat(stored.scale()).isEqualTo(2);
        assertThat(stored.compareTo(new BigDecimal("-1000000.55"))).isZero();
    }

    /**
     * A value normalised with an explicit {@link RoundingMode#HALF_UP} to scale 2
     * (reproducing COBOL rounding at each computation) must round-trip intact:
     * {@code 2.345} rounds half-up to {@code 2.35}, which is stored and read back at
     * scale 2.
     */
    @Test
    @DisplayName("dalytranAmt round-trips a HALF_UP-rounded value at scale 2")
    void dalytranAmtRoundingHalfUp() {
        DailyTransaction tx = new DailyTransaction();
        BigDecimal rounded = new BigDecimal("2.345").setScale(2, RoundingMode.HALF_UP);

        tx.setDalytranAmt(rounded);

        BigDecimal stored = tx.getDalytranAmt();
        assertThat(stored.scale()).isEqualTo(2);
        assertThat(stored.compareTo(new BigDecimal("2.35"))).isZero();
    }

    /**
     * A canonical zero must be held at scale 2 ({@code 0.00}) yet still compare equal
     * to the scaleless {@link BigDecimal#ZERO}, confirming numeric equality is checked
     * with {@code compareTo} rather than {@link BigDecimal#equals(Object) equals}.
     */
    @Test
    @DisplayName("dalytranAmt keeps a canonical zero at scale 2")
    void dalytranAmtZeroCanonicalScale() {
        DailyTransaction tx = new DailyTransaction();
        BigDecimal zeroAmount = new BigDecimal("0.00");

        tx.setDalytranAmt(zeroAmount);

        BigDecimal stored = tx.getDalytranAmt();
        assertThat(stored.scale()).isEqualTo(2);
        assertThat(stored.compareTo(BigDecimal.ZERO)).isZero();
    }

    // ---------------------------------------------------------------------
    // Phase 3 — Timestamp-as-String contract tests (Gate 5 fidelity)
    // ---------------------------------------------------------------------

    /**
     * Both timestamp fields must be preserved verbatim as fixed 26-character strings,
     * confirming they are modelled as {@link String} (not {@code LocalDateTime}) so the
     * exact on-file textual format survives the round-trip.
     */
    @Test
    @DisplayName("timestamps are preserved verbatim as fixed 26-character strings")
    void timestampsAreStringsPreservedVerbatim() {
        DailyTransaction tx = new DailyTransaction();
        String origTs = "2022-07-19-23.16.01.000000";
        String procTs = "2022-07-19-23.16.02.654321";

        tx.setDalytranOrigTs(origTs);
        tx.setDalytranProcTs(procTs);

        assertThat(tx.getDalytranOrigTs()).isEqualTo("2022-07-19-23.16.01.000000");
        assertThat(tx.getDalytranProcTs()).isEqualTo("2022-07-19-23.16.02.654321");
        assertThat(tx.getDalytranOrigTs().length()).isEqualTo(26);
        assertThat(tx.getDalytranProcTs().length()).isEqualTo(26);
    }

    // ---------------------------------------------------------------------
    // Phase 4 — Remaining accessor round-trips
    // ---------------------------------------------------------------------

    /**
     * The natural-key primary key plus every scalar (non-monetary, non-timestamp)
     * field must round-trip through its accessor: what is set is exactly what the
     * corresponding getter returns, in the declared COBOL field order.
     */
    @Test
    @DisplayName("natural key and scalar fields round-trip through the accessors")
    void naturalKeyAndScalarFieldsRoundTrip() {
        DailyTransaction tx = new DailyTransaction();

        tx.setDalytranId("DTX0000000000001");
        tx.setDalytranTypeCd("02");
        tx.setDalytranCatCd(5678);
        tx.setDalytranSource("ONLINE");
        tx.setDalytranDesc("Daily test");
        tx.setDalytranMerchantId(987654321L);
        tx.setDalytranMerchantName("STORE");
        tx.setDalytranMerchantCity("Denver");
        tx.setDalytranMerchantZip("80202");
        tx.setDalytranCardNum("4222222222222222");

        assertThat(tx.getDalytranId()).isEqualTo("DTX0000000000001");
        assertThat(tx.getDalytranTypeCd()).isEqualTo("02");
        assertThat(tx.getDalytranCatCd()).isEqualTo(Integer.valueOf(5678));
        assertThat(tx.getDalytranSource()).isEqualTo("ONLINE");
        assertThat(tx.getDalytranDesc()).isEqualTo("Daily test");
        assertThat(tx.getDalytranMerchantId()).isEqualTo(Long.valueOf(987654321L));
        assertThat(tx.getDalytranMerchantName()).isEqualTo("STORE");
        assertThat(tx.getDalytranMerchantCity()).isEqualTo("Denver");
        assertThat(tx.getDalytranMerchantZip()).isEqualTo("80202");
        assertThat(tx.getDalytranCardNum()).isEqualTo("4222222222222222");
    }
}
