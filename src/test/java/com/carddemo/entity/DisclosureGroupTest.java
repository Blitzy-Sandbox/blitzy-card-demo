package com.carddemo.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link DisclosureGroup} entity (COBOL {@code DIS-GROUP-RECORD} /
 * copybook {@code CVTRA02Y}, {@code RECLN = 50}, source commit {@code 27d6c6f},
 * read-only reference): {@code disIntRate} decimal-fidelity and {@code @EmbeddedId}
 * composite-key wiring.
 *
 * <p><strong>Primary focus &mdash; distinct precision.</strong> The single non-key
 * data field {@code disIntRate} is the Java translation of {@code DIS-INT-RATE
 * PIC S9(04)V99}: a <em>signed</em> value with four integer digits and two fraction
 * digits, mapped to {@link java.math.BigDecimal} of {@code scale = 2} and
 * {@code precision = 6} (SQL {@code NUMERIC(6,2)}). Note that this precision (6) is
 * deliberately <em>smaller</em> than the account/transaction monetary fields
 * (precision 11/12); a percentage-style rate is tempting to model with an inexact
 * {@code double}, so these tests prove it round-trips as an exact scale-2
 * {@link BigDecimal} with its sign preserved &mdash; never an IEEE-754 binary
 * primitive (migration decimal-precision rule, Gate 2, &sect;0.8.2).</p>
 *
 * <p>The three-part composite key {@code DIS-GROUP-KEY} is modeled as the
 * {@link jakarta.persistence.EmbeddedId @EmbeddedId} {@link DisclosureGroup#getId() id}
 * of type {@link DisclosureGroupId}. These tests verify the entity correctly holds and
 * returns that key; the key's own {@code equals}/{@code hashCode} and per-component
 * accessor contract is exercised exhaustively by the companion
 * {@code DisclosureGroupIdTest} and is not duplicated here.</p>
 *
 * <p>This is a framework-free plain-old-Java-object (POJO) test: no Spring context, no
 * persistence, no Testcontainers, and no mocks. Numeric equality is asserted with
 * AssertJ {@link org.assertj.core.api.AbstractBigDecimalAssert#isEqualByComparingTo(Object)
 * isEqualByComparingTo} (which compares via {@link BigDecimal#compareTo}, not
 * {@link BigDecimal#equals}) so value equality is decoupled from scale, while the scale
 * itself is asserted separately via {@link BigDecimal#scale()}. The sample rates fit the
 * {@code S9(04)V99} width (maximum magnitude {@code 9999.99}); the scale-2 rate semantics
 * mirror the {@code discgrp} reference fixture (for example {@code 15.00}/{@code 25.00}).</p>
 */
class DisclosureGroupTest {

    /**
     * The maximum-width value representable by COBOL {@code PIC S9(04)V99}: four
     * integer digits and two fraction digits. Declared as an exact
     * {@link BigDecimal} string literal (never an IEEE-754 binary primitive).
     */
    private static final String MAX_WIDTH_RATE = "1234.56";

    /**
     * A signed (negative) rate within the {@code S9(04)V99} width; exercises the
     * COBOL {@code S} sign-preservation guarantee at scale 2.
     */
    private static final String SIGNED_RATE = "-12.34";

    /**
     * A realistic disclosure-group interest rate at scale 2, consistent with the
     * scale-2 percentage rates in the {@code discgrp} reference fixture.
     */
    private static final String TYPICAL_RATE = "19.99";

    /**
     * The maximum-width positive rate ({@code DIS-INT-RATE PIC S9(04)V99}, four
     * integer plus two fraction digits) must survive the set/get round-trip and
     * retain COBOL {@code V99} scale 2.
     */
    @Test
    @DisplayName("disIntRate preserves S9(04)V99 scale 2 at maximum width (1234.56)")
    void disIntRatePreservesScaleTwo() {
        DisclosureGroup group = new DisclosureGroup();

        group.setDisIntRate(new BigDecimal(MAX_WIDTH_RATE));

        assertThat(group.getDisIntRate())
                .as("maximum-width rate must survive the set/get round-trip")
                .isEqualByComparingTo(new BigDecimal(MAX_WIDTH_RATE));
        assertThat(group.getDisIntRate().scale())
                .as("rate must retain COBOL V99 scale 2")
                .isEqualTo(2);
    }

    /**
     * The {@code S} in {@code S9(04)V99} means the sign MUST be preserved end to
     * end: a negative rate keeps its sign and scale 2 and round-trips exactly.
     */
    @Test
    @DisplayName("disIntRate preserves the COBOL sign for a negative rate (-12.34)")
    void disIntRatePreservesSign() {
        DisclosureGroup group = new DisclosureGroup();

        group.setDisIntRate(new BigDecimal(SIGNED_RATE));

        assertThat(group.getDisIntRate().signum())
                .as("negative rate must preserve its sign (COBOL S = signed)")
                .isEqualTo(-1);
        assertThat(group.getDisIntRate().scale())
                .as("negative rate must retain scale 2")
                .isEqualTo(2);
        assertThat(group.getDisIntRate())
                .as("negative rate must survive the set/get round-trip")
                .isEqualByComparingTo(new BigDecimal(SIGNED_RATE));
    }

    /**
     * Documents the {@code HALF_UP} rounding convention callers apply when reducing
     * a higher-scale rate to the scale-2 money field: the exact literal
     * {@code 9.995} rounds {@code HALF_UP} to {@code 10.00}. The entity stores
     * whatever {@link BigDecimal} it is given, so the correctly scaled value is
     * constructed and lossless storage fidelity (value and scale) is confirmed.
     */
    @Test
    @DisplayName("disIntRate stores a HALF_UP-rounded scale-2 value (9.995 -> 10.00)")
    void disIntRateRoundingHalfUp() {
        DisclosureGroup group = new DisclosureGroup();

        BigDecimal roundedHalfUp = new BigDecimal("9.995").setScale(2, RoundingMode.HALF_UP);
        group.setDisIntRate(roundedHalfUp);

        assertThat(group.getDisIntRate())
                .as("HALF_UP rounding of 9.995 to scale 2 must yield 10.00")
                .isEqualByComparingTo(new BigDecimal("10.00"));
        assertThat(group.getDisIntRate().scale())
                .as("rounded rate must retain scale 2")
                .isEqualTo(2);
    }

    /**
     * A realistic disclosure-group rate ({@code 19.99}) must round-trip through the
     * setter/getter and retain scale 2, mirroring the scale-2 rates recorded in the
     * {@code discgrp} reference fixture.
     */
    @Test
    @DisplayName("disIntRate round-trips a realistic 19.99 rate at scale 2")
    void disIntRateTypicalRate() {
        DisclosureGroup group = new DisclosureGroup();

        group.setDisIntRate(new BigDecimal(TYPICAL_RATE));

        assertThat(group.getDisIntRate())
                .as("typical rate must survive the set/get round-trip")
                .isEqualByComparingTo(new BigDecimal(TYPICAL_RATE));
        assertThat(group.getDisIntRate().scale())
                .as("typical rate must retain scale 2")
                .isEqualTo(2);
    }

    /**
     * The {@code @EmbeddedId} composite key ({@code DIS-GROUP-KEY}) must round-trip
     * through {@code setId}/{@code getId}: the entity returns the exact key it was
     * given, and each key component is observable through the key's accessors.
     */
    @Test
    @DisplayName("embedded id round-trips through setId/getId with component accessors")
    void embeddedIdRoundTrip() {
        DisclosureGroup group = new DisclosureGroup();
        DisclosureGroupId key = new DisclosureGroupId("GROUP00001", "01", 100);

        group.setId(key);

        assertThat(group.getId())
                .as("getId must return the same composite key that was set")
                .isEqualTo(key);
        assertThat(group.getId().getDisAcctGroupId())
                .as("DIS-ACCT-GROUP-ID component must round-trip")
                .isEqualTo("GROUP00001");
        assertThat(group.getId().getDisTranTypeCd())
                .as("DIS-TRAN-TYPE-CD component must round-trip")
                .isEqualTo("01");
        assertThat(group.getId().getDisTranCatCd())
                .as("DIS-TRAN-CAT-CD component must round-trip")
                .isEqualTo(Integer.valueOf(100));
    }

    /**
     * Record-assembly sanity check: the JPA-mandated no-arg constructor followed by
     * both setters must compose a complete {@code DIS-GROUP-RECORD} whose composite
     * key and scale-2 rate the getters return independently.
     */
    @Test
    @DisplayName("no-arg constructor plus setters compose a full disclosure-group record")
    void fullRecordCompose() {
        DisclosureGroup group = new DisclosureGroup();
        DisclosureGroupId key = new DisclosureGroupId("GROUP00002", "DB", 200);

        group.setId(key);
        group.setDisIntRate(new BigDecimal(TYPICAL_RATE));

        assertThat(group.getId())
                .as("composed record must return the composite key that was set")
                .isEqualTo(key);
        assertThat(group.getDisIntRate())
                .as("composed rate must round-trip by value")
                .isEqualByComparingTo(new BigDecimal(TYPICAL_RATE));
        assertThat(group.getDisIntRate().scale())
                .as("composed rate must retain scale 2")
                .isEqualTo(2);
    }

    /**
     * A freshly constructed instance has no seeded values. Crucially, the getter
     * for {@code disIntRate} returns {@code null} (not a numeric zero), proving the
     * rate is an object {@link BigDecimal} reference rather than an IEEE-754 binary
     * primitive that would silently default to {@code 0.0} &mdash; the exact
     * mistake the decimal-precision rule forbids for financial fields.
     */
    @Test
    @DisplayName("a new DisclosureGroup has null id and rate (rate is BigDecimal, not a primitive)")
    void newInstanceDefaultsAreNull() {
        DisclosureGroup group = new DisclosureGroup();

        assertThat(group.getId())
                .as("composite key is null until assigned")
                .isNull();
        assertThat(group.getDisIntRate())
                .as("rate is a null BigDecimal reference, not a primitive default of 0.0")
                .isNull();
    }
}
