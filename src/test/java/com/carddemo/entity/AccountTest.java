package com.carddemo.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link Account} entity (COBOL {@code ACCOUNT-RECORD} /
 * copybook {@code CVACT01Y}, source commit {@code 27d6c6f}, read-only reference):
 * {@link java.math.BigDecimal} money-field scale fidelity
 * ({@code PIC S9(10)V99} &rarr; {@code NUMERIC(12,2)}, scale 2), the
 * {@code @Version} optimistic-lock accessor, and accessor round-trips.
 *
 * <p>This is the flagship of the entity test suite. Its <strong>primary
 * focus</strong> is proving that every one of the five monetary fields
 * ({@code acctCurrBal}, {@code acctCreditLimit}, {@code acctCashCreditLimit},
 * {@code acctCurrCycCredit}, {@code acctCurrCycDebit}) preserves the exact signed
 * decimal semantics of the legacy COBOL {@code PIC S9(10)V99} picture &mdash;
 * ten integer digits, two fraction digits, signed &mdash; without any
 * inexact binary substitution (migration decimal-precision rule, Gate 2).</p>
 *
 * <p>It is a framework-free plain-old-Java-object (POJO) test: no Spring
 * context, no persistence, no Testcontainers, and no mocks are involved.
 * Persistence-level round-trips belong to the sibling {@code repository}
 * package. Numeric equality is asserted with AssertJ
 * {@link org.assertj.core.api.AbstractBigDecimalAssert#isEqualByComparingTo(Object)
 * isEqualByComparingTo} (which compares via {@link BigDecimal#compareTo}, not
 * {@link BigDecimal#equals}) so that value equality is decoupled from scale,
 * while the scale itself is asserted separately with {@link BigDecimal#scale()}.
 * Only {@link BigDecimal} is used for monetary values here &mdash; never an
 * IEEE-754 binary primitive &mdash; mirroring the production prohibition on
 * inexact money representations.</p>
 */
class AccountTest {

    /**
     * The maximum-width positive value representable by COBOL {@code PIC
     * S9(10)V99}: ten integer digits and two fraction digits. Declared as an
     * exact {@link BigDecimal} string literal (never an IEEE-754 binary primitive).
     */
    private static final String MAX_WIDTH_VALUE = "1234567890.12";

    /**
     * The maximum-magnitude negative value representable by {@code PIC
     * S9(10)V99} (all-nines, signed). Exercises COBOL sign preservation.
     */
    private static final String MAX_NEGATIVE_VALUE = "-9999999999.99";

    /**
     * Exercises the decimal-fidelity contract of a single monetary field for
     * all four scenarios the migration decimal-precision rule requires:
     * maximum-width scale-2 round-trip, signed-value preservation, {@code
     * HALF_UP} rounding to scale 2, and the canonical zero representation.
     *
     * <p>The field is addressed indirectly through its setter and getter so the
     * identical battery of assertions can be reused for every money field with
     * no copy-paste drift. Value equality uses {@code compareTo}-based
     * {@code isEqualByComparingTo}; scale and sign are asserted explicitly.</p>
     *
     * @param setter the field's setter (for example {@code account::setAcctCurrBal})
     * @param getter the field's getter (for example {@code account::getAcctCurrBal})
     */
    private static void assertMoneyFieldFidelity(
            Consumer<BigDecimal> setter, Supplier<BigDecimal> getter) {

        // 1. Maximum-width value: scale 2 is preserved and the value survives
        //    the set/get round-trip exactly (ten integer + two fraction digits).
        setter.accept(new BigDecimal(MAX_WIDTH_VALUE));
        assertThat(getter.get())
                .as("maximum-width value must survive the set/get round-trip")
                .isEqualByComparingTo(new BigDecimal(MAX_WIDTH_VALUE));
        assertThat(getter.get().scale())
                .as("maximum-width value must retain COBOL V99 scale 2")
                .isEqualTo(2);

        // 2. Signed (negative) value: the 'S' in S9(10)V99 means the sign MUST be
        //    preserved end to end, with scale 2 intact.
        setter.accept(new BigDecimal(MAX_NEGATIVE_VALUE));
        assertThat(getter.get().signum())
                .as("negative value must preserve its sign (COBOL S = signed)")
                .isEqualTo(-1);
        assertThat(getter.get().scale())
                .as("negative value must retain scale 2")
                .isEqualTo(2);
        assertThat(getter.get())
                .as("negative value must survive the set/get round-trip")
                .isEqualByComparingTo(new BigDecimal(MAX_NEGATIVE_VALUE));

        // 3. HALF_UP rounding to scale 2: documents the convention callers MUST
        //    apply when reducing a higher-scale amount to a money field. The
        //    exact literal 100.005 rounds HALF_UP to 100.01. The entity stores
        //    whatever BigDecimal it is given, so the test constructs the correctly
        //    scaled value and confirms lossless storage fidelity.
        BigDecimal roundedHalfUp = new BigDecimal("100.005").setScale(2, RoundingMode.HALF_UP);
        setter.accept(roundedHalfUp);
        assertThat(getter.get())
                .as("HALF_UP rounding of 100.005 to scale 2 must yield 100.01")
                .isEqualByComparingTo(new BigDecimal("100.01"));
        assertThat(getter.get().scale())
                .as("rounded value must retain scale 2")
                .isEqualTo(2);

        // 4. Zero: the canonical scale-2 representation of zero round-trips and
        //    compares equal to BigDecimal.ZERO by value.
        setter.accept(new BigDecimal("0.00"));
        assertThat(getter.get().scale())
                .as("zero must retain canonical scale 2")
                .isEqualTo(2);
        assertThat(getter.get())
                .as("zero must compare equal to BigDecimal.ZERO by value")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("acctCurrBal preserves S9(10)V99 scale, sign, rounding, and zero fidelity")
    void acctCurrBalDecimalFidelity() {
        Account account = new Account();
        assertMoneyFieldFidelity(account::setAcctCurrBal, account::getAcctCurrBal);
    }

    @Test
    @DisplayName("acctCreditLimit preserves S9(10)V99 scale, sign, rounding, and zero fidelity")
    void acctCreditLimitDecimalFidelity() {
        Account account = new Account();
        assertMoneyFieldFidelity(account::setAcctCreditLimit, account::getAcctCreditLimit);
    }

    @Test
    @DisplayName("acctCashCreditLimit preserves S9(10)V99 scale, sign, rounding, and zero fidelity")
    void acctCashCreditLimitDecimalFidelity() {
        Account account = new Account();
        assertMoneyFieldFidelity(account::setAcctCashCreditLimit, account::getAcctCashCreditLimit);
    }

    @Test
    @DisplayName("acctCurrCycCredit preserves S9(10)V99 scale, sign, rounding, and zero fidelity")
    void acctCurrCycCreditDecimalFidelity() {
        Account account = new Account();
        assertMoneyFieldFidelity(account::setAcctCurrCycCredit, account::getAcctCurrCycCredit);
    }

    @Test
    @DisplayName("acctCurrCycDebit preserves S9(10)V99 scale, sign, rounding, and zero fidelity")
    void acctCurrCycDebitDecimalFidelity() {
        Account account = new Account();
        assertMoneyFieldFidelity(account::setAcctCurrCycDebit, account::getAcctCurrCycDebit);
    }

    @Test
    @DisplayName("acctId round-trips an 11-digit natural key as Long")
    void acctIdRoundTrip() {
        Account account = new Account();

        // COBOL ACCT-ID PIC 9(11): an eleven-digit application-assigned key.
        account.setAcctId(12345678901L);

        assertThat(account.getAcctId()).isEqualTo(12345678901L);
    }

    @Test
    @DisplayName("fixed-width string fields round-trip through their accessors")
    void stringFieldsRoundTrip() {
        Account account = new Account();

        account.setAcctActiveStatus("Y");
        account.setAcctAddrZip("12345-6789");
        account.setAcctGroupId("GROUP00001");

        assertThat(account.getAcctActiveStatus()).isEqualTo("Y");
        assertThat(account.getAcctAddrZip()).isEqualTo("12345-6789");
        assertThat(account.getAcctGroupId()).isEqualTo("GROUP00001");
    }

    @Test
    @DisplayName("LocalDate fields round-trip through their accessors")
    void localDateFieldsRoundTrip() {
        Account account = new Account();

        account.setAcctOpenDate(LocalDate.of(2020, 1, 15));
        account.setAcctExpirationDate(LocalDate.of(2025, 1, 14));
        account.setAcctReissueDate(LocalDate.of(2022, 6, 1));

        assertThat(account.getAcctOpenDate()).isEqualTo(LocalDate.of(2020, 1, 15));
        assertThat(account.getAcctExpirationDate()).isEqualTo(LocalDate.of(2025, 1, 14));
        assertThat(account.getAcctReissueDate()).isEqualTo(LocalDate.of(2022, 6, 1));
    }

    @Test
    @DisplayName("version optimistic-lock accessor round-trips (COACTUPC read-then-rewrite)")
    void versionAccessorRoundTrip() {
        Account account = new Account();

        // The @Version property is a normal accessible attribute; it backs the
        // COACTUPC read-then-rewrite optimistic-concurrency semantics.
        account.setVersion(0L);
        assertThat(account.getVersion()).isEqualTo(0L);

        account.setVersion(7L);
        assertThat(account.getVersion()).isEqualTo(7L);
    }

    @Test
    @DisplayName("a new Account has null money fields (no accidental numeric-primitive defaults)")
    void newInstanceHasNullMoneyFieldsByDefault() {
        Account account = new Account();

        // A null default (not a numeric zero) proves the money fields are object
        // BigDecimal references, not IEEE-754 binary primitives that would silently
        // default to zero.
        assertThat(account.getAcctCurrBal()).isNull();
        assertThat(account.getAcctCreditLimit()).isNull();
        assertThat(account.getAcctCashCreditLimit()).isNull();
        assertThat(account.getAcctCurrCycCredit()).isNull();
        assertThat(account.getAcctCurrCycDebit()).isNull();
    }
}
