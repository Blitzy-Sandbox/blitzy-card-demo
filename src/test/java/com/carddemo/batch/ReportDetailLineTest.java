package com.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ReportDetailLine}, the immutable value object that carries the display
 * fields for one line of the CardDemo transaction-detail report (the Java translation of the
 * {@code TRANSACTION-DETAIL-REPORT} group emitted by the {@code 1120-WRITE-DETAIL} paragraph of
 * the legacy batch program {@code CBTRN03C.CBL}, source SHA {@code 27d6c6f}).
 *
 * <p><strong>Why this test exists.</strong> The critical invariant of the record is
 * <em>decimal fidelity</em> (AAP&nbsp;G2 / §0.8.2): its {@code amount} component mirrors the COBOL
 * {@code TRAN-AMT PIC S9(09)V99} field (signed, two fractional digits), so the canonical
 * constructor eagerly rejects a {@code null} amount and re-scales every supplied value to scale
 * {@code 2} using {@link RoundingMode#HALF_UP}. Because {@code CBTRN03C} accumulates the page,
 * per-account and grand totals from these amounts, holding every {@link ReportDetailLine#amount()}
 * at scale {@code 2} is precisely what lets {@code TransactionReportItemWriter} reconcile the grand
 * total against the sum of the detail amounts byte-for-byte. This record is the smallest unit of
 * that contract, and these tests pin it down.</p>
 *
 * <p><strong>How the numeric assertions are written.</strong> Monetary values are always compared
 * with {@link BigDecimal#compareTo(BigDecimal)} (asserted {@code == 0}, expressed here as
 * {@code isZero()}) rather than {@link Object#equals(Object)}, because {@code BigDecimal.equals}
 * also compares scale and would report {@code 123.4} and {@code 123.40} as unequal — a false
 * failure that hides, rather than proves, the fidelity guarantee. Scale is asserted separately and
 * explicitly via {@link BigDecimal#scale()}. Together these two assertions prove both the numeric
 * value and the exact fractional precision.</p>
 *
 * <p>The tests are deliberately pure POJO unit tests: no Spring context, persistence, mocks, or
 * containers are involved, so they run in milliseconds and contribute fast line coverage toward the
 * Gate&nbsp;8 (&ge;80%) JaCoCo threshold. No COBOL source is reproduced here; rationale lives in
 * {@code docs/decision-log.md}.</p>
 */
@DisplayName("ReportDetailLine record — scale-2 normalization + accessors")
class ReportDetailLineTest {

    // ------------------------------------------------------------------
    // Realistic CBTRN03C-style sample components reused across the
    // accessor round-trip test. Values mirror the enrichment performed in
    // 1120-WRITE-DETAIL (transaction id, resolved account id, type/category
    // codes and descriptions, source, and the signed amount).
    // ------------------------------------------------------------------
    private static final String TRANSACTION_ID = "0000000000683580";
    private static final Long ACCOUNT_ID = 11111111111L;
    private static final String TYPE_CODE = "01";
    private static final String TYPE_DESCRIPTION = "DEBIT";
    private static final Integer CATEGORY_CODE = 5;
    private static final String CATEGORY_DESCRIPTION = "PURCHASE";
    private static final String SOURCE = "POS";

    // ------------------------------------------------------------------
    // Phase 2 — scale normalization (decimal fidelity, CRITICAL). Each test
    // proves BOTH the resulting value (via compareTo) AND the scale (via
    // scale()), reproducing the COBOL PIC S9(09)V99 two-digit contract.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an amount with fewer than two fractional digits is padded to scale 2")
    void amountWithFewerFractionalDigitsIsPaddedToScaleTwo() {
        ReportDetailLine line = newLineWithAmount(new BigDecimal("123.4"));

        assertThat(line.amount().scale()).isEqualTo(2);
        assertThat(line.amount().compareTo(new BigDecimal("123.40"))).isZero();
    }

    @Test
    @DisplayName("a third fractional digit of 5 rounds HALF_UP: 10.005 -> 10.01 at scale 2")
    void amountIsRoundedHalfUpToScaleTwo() {
        ReportDetailLine line = newLineWithAmount(new BigDecimal("10.005"));

        assertThat(line.amount().scale()).isEqualTo(2);
        assertThat(line.amount().compareTo(new BigDecimal("10.01"))).isZero();
    }

    @Test
    @DisplayName("an amount already at scale 2 is preserved unchanged")
    void amountAlreadyAtScaleTwoIsUnchanged() {
        ReportDetailLine line = newLineWithAmount(new BigDecimal("50.00"));

        assertThat(line.amount().scale()).isEqualTo(2);
        assertThat(line.amount().compareTo(new BigDecimal("50.00"))).isZero();
    }

    @Test
    @DisplayName("a negative amount rounds HALF_UP away from zero: -9.999 -> -10.00 at scale 2")
    void negativeAmountIsRoundedHalfUpAwayFromZero() {
        ReportDetailLine line = newLineWithAmount(new BigDecimal("-9.999"));

        assertThat(line.amount().scale()).isEqualTo(2);
        assertThat(line.amount().compareTo(new BigDecimal("-10.00"))).isZero();
    }

    // ------------------------------------------------------------------
    // Phase 3 — accessor round-trip. All eight components are returned
    // exactly as supplied, except amount, which is scale-normalized.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("all eight components round-trip through the record accessors")
    void allComponentsRoundTripThroughAccessors() {
        ReportDetailLine line = new ReportDetailLine(
                TRANSACTION_ID,
                ACCOUNT_ID,
                TYPE_CODE,
                TYPE_DESCRIPTION,
                CATEGORY_CODE,
                CATEGORY_DESCRIPTION,
                SOURCE,
                new BigDecimal("1234.5"));

        assertThat(line.transactionId()).isEqualTo(TRANSACTION_ID);
        assertThat(line.accountId()).isEqualTo(11111111111L);
        assertThat(line.typeCode()).isEqualTo(TYPE_CODE);
        assertThat(line.typeDescription()).isEqualTo(TYPE_DESCRIPTION);
        assertThat(line.categoryCode()).isEqualTo(5);
        assertThat(line.categoryDescription()).isEqualTo(CATEGORY_DESCRIPTION);
        assertThat(line.source()).isEqualTo(SOURCE);
        // amount is normalized to scale 2 while preserving its numeric value.
        assertThat(line.amount().scale()).isEqualTo(2);
        assertThat(line.amount().compareTo(new BigDecimal("1234.50"))).isZero();
    }

    // ------------------------------------------------------------------
    // Phase 4 — null-amount guard. The canonical constructor calls
    // Objects.requireNonNull(amount); a null amount would corrupt the
    // running totals, so it is rejected eagerly.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a null amount is rejected with NullPointerException")
    void nullAmountIsRejected() {
        NullPointerException ex = assertThrows(
                NullPointerException.class,
                () -> new ReportDetailLine(
                        TRANSACTION_ID,
                        ACCOUNT_ID,
                        TYPE_CODE,
                        TYPE_DESCRIPTION,
                        CATEGORY_CODE,
                        CATEGORY_DESCRIPTION,
                        SOURCE,
                        null));

        assertThat(ex).hasMessageContaining("amount");
    }

    @Test
    @DisplayName("only amount is guarded — the other seven components accept null")
    void nonAmountComponentsAreNotNullGuarded() {
        // Production guards ONLY the amount component; the remaining seven are nullable by
        // contract. Constructing with them all null (and a non-null amount) must succeed, and
        // each accessor must return null. This documents the contract and guards against a
        // future, unintended requireNonNull being added to a non-amount component.
        ReportDetailLine line = new ReportDetailLine(
                null, null, null, null, null, null, null, new BigDecimal("0.00"));

        assertThat(line.transactionId()).isNull();
        assertThat(line.accountId()).isNull();
        assertThat(line.typeCode()).isNull();
        assertThat(line.typeDescription()).isNull();
        assertThat(line.categoryCode()).isNull();
        assertThat(line.categoryDescription()).isNull();
        assertThat(line.source()).isNull();
        assertThat(line.amount().scale()).isEqualTo(2);
        assertThat(line.amount().compareTo(new BigDecimal("0.00"))).isZero();
    }

    // ------------------------------------------------------------------
    // Helper — build a line whose non-amount components are fixed sample
    // values so each scale test isolates the amount-normalization behaviour.
    // ------------------------------------------------------------------
    private static ReportDetailLine newLineWithAmount(BigDecimal amount) {
        return new ReportDetailLine(
                TRANSACTION_ID,
                ACCOUNT_ID,
                TYPE_CODE,
                TYPE_DESCRIPTION,
                CATEGORY_CODE,
                CATEGORY_DESCRIPTION,
                SOURCE,
                amount);
    }
}
