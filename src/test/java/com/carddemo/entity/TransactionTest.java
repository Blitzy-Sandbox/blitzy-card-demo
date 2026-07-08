package com.carddemo.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pure-POJO unit tests for the {@link Transaction} JPA entity (the Java translation of
 * COBOL {@code TRAN-RECORD}, copybook {@code CVTRA05Y}, record length 350, source commit
 * {@code 27d6c6f} — read-only reference, not copied into this repository).
 *
 * <p>The primary focus is fidelity of the two migration-critical field groups:</p>
 * <ul>
 *   <li><b>Money field {@code tranAmt}</b> — {@code TRAN-AMT PIC S9(09)V99} maps to a
 *       {@link BigDecimal} of {@code NUMERIC(11,2)} (nine integer digits plus two
 *       fraction digits). These tests assert the value keeps <em>scale 2</em>, preserves
 *       its sign, survives {@link RoundingMode#HALF_UP} rounding, and normalises zero to a
 *       canonical scale-2 form. Numeric equality is asserted with
 *       {@link BigDecimal#compareTo(BigDecimal) compareTo} (scale-insensitive value
 *       equality) rather than {@link BigDecimal#equals(Object) equals}; no
 *       {@code float}/{@code double} appears anywhere, honouring the decimal-precision rule
 *       (AAP §0.8.2 / Gate 2).</li>
 *   <li><b>Timestamp fields {@code tranOrigTs} / {@code tranProcTs}</b> —
 *       {@code TRAN-ORIG-TS} / {@code TRAN-PROC-TS} ({@code PIC X(26)}) are preserved
 *       verbatim as 26-character {@link String} values, <em>not</em> parsed into a
 *       {@code java.time} temporal type, so the legacy
 *       {@code yyyy-mm-dd-hh.mm.ss.ffffff} text crosses the interface boundary unchanged
 *       (contract fidelity, Gate 5).</li>
 * </ul>
 *
 * <p>This is a framework-free test: no Spring context, no persistence, no Testcontainers,
 * and no mocks are involved. Every assertion is an in-memory getter/setter round-trip on a
 * plain object instance, so the suite runs fast and deterministically and contributes to
 * line coverage (JaCoCo).</p>
 */
class TransactionTest {

    // ---------------------------------------------------------------------
    // Phase 2 — Money-field (tranAmt) decimal-fidelity tests
    // ---------------------------------------------------------------------

    /**
     * A full-width {@code S9(09)V99} amount (nine integer digits, two fraction digits) must
     * round-trip through the accessor while keeping exactly scale 2, and its value must be
     * unchanged when compared with {@link BigDecimal#compareTo(BigDecimal)}.
     */
    @Test
    @DisplayName("tranAmt preserves BigDecimal scale 2 (TRAN-AMT PIC S9(09)V99)")
    void tranAmtPreservesScaleTwo() {
        Transaction transaction = new Transaction();
        // Maximum-width positive amount for S9(09)V99: 9 integer digits + 2 fraction digits.
        BigDecimal expected = new BigDecimal("123456789.12");

        transaction.setTranAmt(expected);

        BigDecimal actual = transaction.getTranAmt();
        assertThat(actual.scale()).isEqualTo(2);
        // compareTo == 0 is scale-insensitive value equality (never equals()).
        assertThat(actual.compareTo(expected)).isZero();
    }

    /**
     * The COBOL {@code S} (signed) picture means negative amounts are legal; the entity must
     * preserve the sign, the scale, and the numeric value of a negative amount.
     */
    @Test
    @DisplayName("tranAmt preserves a negative sign (COBOL S = signed)")
    void tranAmtPreservesSign() {
        Transaction transaction = new Transaction();
        BigDecimal expected = new BigDecimal("-4567890.12");

        transaction.setTranAmt(expected);

        BigDecimal actual = transaction.getTranAmt();
        assertThat(actual.signum()).isEqualTo(-1);
        assertThat(actual.scale()).isEqualTo(2);
        assertThat(actual.compareTo(expected)).isZero();
    }

    /**
     * A value rounded to scale 2 with {@link RoundingMode#HALF_UP} (reproducing COBOL
     * rounding at each computation) must round-trip unchanged: {@code 10.005} rounds up to
     * {@code 10.01}.
     */
    @Test
    @DisplayName("tranAmt round-trips a HALF_UP rounded value (10.005 -> 10.01)")
    void tranAmtRoundingHalfUp() {
        Transaction transaction = new Transaction();
        BigDecimal rounded = new BigDecimal("10.005").setScale(2, RoundingMode.HALF_UP);

        transaction.setTranAmt(rounded);

        BigDecimal actual = transaction.getTranAmt();
        assertThat(actual.scale()).isEqualTo(2);
        assertThat(actual.compareTo(new BigDecimal("10.01"))).isZero();
    }

    /**
     * A zero amount must retain its canonical scale-2 representation ({@code 0.00}) and
     * still compare equal to {@link BigDecimal#ZERO} by value.
     */
    @Test
    @DisplayName("tranAmt keeps a canonical scale-2 zero (0.00)")
    void tranAmtZeroCanonicalScale() {
        Transaction transaction = new Transaction();
        BigDecimal zero = new BigDecimal("0.00");

        transaction.setTranAmt(zero);

        BigDecimal actual = transaction.getTranAmt();
        assertThat(actual.scale()).isEqualTo(2);
        // BigDecimal.ZERO has scale 0; compareTo confirms value equality regardless of scale.
        assertThat(actual.compareTo(BigDecimal.ZERO)).isZero();
    }

    // ---------------------------------------------------------------------
    // Phase 3 — Timestamp-as-String contract tests (Gate 5 fidelity)
    // ---------------------------------------------------------------------

    /**
     * {@code tranOrigTs} and {@code tranProcTs} must be preserved as the raw 26-character
     * legacy timestamp text ({@code yyyy-mm-dd-hh.mm.ss.ffffff}) rather than parsed into a
     * temporal type: the getters return the exact same {@link String} that was set, and the
     * text is 26 characters wide ({@code PIC X(26)}). The {@link String} literal assignment
     * is itself a compile-time guardrail — were the production field a {@code LocalDateTime},
     * this test would fail to compile.
     */
    @Test
    @DisplayName("tranOrigTs/tranProcTs are preserved verbatim as 26-char Strings (not LocalDateTime)")
    void timestampsAreStringsPreservedVerbatim() {
        Transaction transaction = new Transaction();
        String origTs = "2022-07-19-23.16.01.000000";
        String procTs = "2022-07-19-23.16.02.123456";

        transaction.setTranOrigTs(origTs);
        transaction.setTranProcTs(procTs);

        // Verbatim preservation: no parsing, reformatting, or truncation.
        assertThat(transaction.getTranOrigTs()).isEqualTo(origTs);
        assertThat(transaction.getTranProcTs()).isEqualTo(procTs);
        // PIC X(26): the fixed-width text is exactly 26 characters.
        assertThat(transaction.getTranOrigTs().length()).isEqualTo(26);
        assertThat(transaction.getTranProcTs().length()).isEqualTo(26);
    }

    // ---------------------------------------------------------------------
    // Phase 4 — Remaining accessor round-trips
    // ---------------------------------------------------------------------

    /**
     * The natural key and every remaining scalar field must round-trip through the
     * getters/setters, with the {@code Integer} and {@code Long} wrapper fields returned as
     * their wrapper types.
     */
    @Test
    @DisplayName("natural key and scalar fields round-trip through getters/setters")
    void naturalKeyAndScalarFieldsRoundTrip() {
        Transaction transaction = new Transaction();

        transaction.setTranId("TXN0000000000001");
        transaction.setTranTypeCd("01");
        transaction.setTranCatCd(1234);
        transaction.setTranSource("POS");
        transaction.setTranDesc("Test purchase");
        transaction.setTranMerchantId(123456789L);
        transaction.setTranMerchantName("ACME");
        transaction.setTranMerchantCity("Seattle");
        transaction.setTranMerchantZip("98101");
        transaction.setTranCardNum("4111111111111111");

        assertThat(transaction.getTranId()).isEqualTo("TXN0000000000001");
        assertThat(transaction.getTranTypeCd()).isEqualTo("01");
        assertThat(transaction.getTranCatCd()).isEqualTo(Integer.valueOf(1234));
        assertThat(transaction.getTranSource()).isEqualTo("POS");
        assertThat(transaction.getTranDesc()).isEqualTo("Test purchase");
        assertThat(transaction.getTranMerchantId()).isEqualTo(Long.valueOf(123456789L));
        assertThat(transaction.getTranMerchantName()).isEqualTo("ACME");
        assertThat(transaction.getTranMerchantCity()).isEqualTo("Seattle");
        assertThat(transaction.getTranMerchantZip()).isEqualTo("98101");
        assertThat(transaction.getTranCardNum()).isEqualTo("4111111111111111");
    }
}
