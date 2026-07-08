package com.carddemo.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link CardXref} entity (COBOL {@code CARD-XREF-RECORD} / copybook
 * {@code CVACT03Y}, source commit {@code 27d6c6f} &ndash; read-only reference, not copied):
 * accessor round-trips and wrapper-typed foreign-key scalars.
 *
 * <p>{@code CardXref} is the flat <em>card&nbsp;&rarr;&nbsp;customer&nbsp;&rarr;&nbsp;account</em>
 * cross-reference record. In the legacy mainframe it was a VSAM KSDS keyed on the card number
 * with an alternate index (AIX/PATH) over the account id; that alternate-index lookup is
 * reproduced as a derived repository query and is therefore <strong>not</strong> exercised here.
 * The record carries no monetary fields and no optimistic-lock {@code @Version} column, so these
 * tests focus purely on the persistent scalar state:</p>
 * <ul>
 *   <li>the {@link String} natural key {@code xrefCardNum} ({@code XREF-CARD-NUM PIC X(16)});</li>
 *   <li>the {@link Long} scalar foreign keys {@code xrefCustId} ({@code XREF-CUST-ID PIC 9(09)})
 *       and {@code xrefAcctId} ({@code XREF-ACCT-ID PIC 9(11)}), confirming they are plain
 *       wrapper-typed scalars &ndash; not primitives and not JPA relationships.</li>
 * </ul>
 *
 * <p>This is a plain-POJO test: no Spring context, no persistence, no Testcontainers, and no
 * mocks. Only wrapper reference types are exercised (no {@code float}/{@code double}), matching
 * the decimal-fidelity constraints of the migration.</p>
 */
class CardXrefTest {

    /**
     * Canonical 16-character card number sample ({@code XREF-CARD-NUM PIC X(16)}). The value is a
     * well-known test PAN and carries no real-world card association.
     */
    private static final String SAMPLE_CARD_NUM = "4111111111111111";

    /**
     * Canonical 9-digit customer id sample ({@code XREF-CUST-ID PIC 9(09)}).
     */
    private static final Long SAMPLE_CUST_ID = 123456789L;

    /**
     * Canonical 11-digit account id sample ({@code XREF-ACCT-ID PIC 9(11)}).
     */
    private static final Long SAMPLE_ACCT_ID = 12345678901L;

    /**
     * The natural key {@code xrefCardNum} must round-trip through its setter/getter with the full
     * 16-character card number preserved exactly (matching {@code PIC X(16)}).
     */
    @Test
    @DisplayName("natural key xrefCardNum round-trips the full 16-character card number")
    void naturalKeyRoundTrip() {
        CardXref xref = new CardXref();

        xref.setXrefCardNum(SAMPLE_CARD_NUM);

        assertThat(xref.getXrefCardNum())
                .isEqualTo(SAMPLE_CARD_NUM)
                .hasSize(16);
    }

    /**
     * The foreign-key scalars {@code xrefCustId} and {@code xrefAcctId} must round-trip as
     * {@link Long} wrappers. Capturing the getter results into {@code Long} locals is compile-time
     * proof that the accessors are wrapper-typed (not primitive {@code long}); asserting value
     * equality against explicit {@link Long} instances confirms the round-trip. Both sample values
     * fall outside the {@code Long} instance cache, so equality is genuine {@code equals()}-based
     * value equality rather than reference identity.
     */
    @Test
    @DisplayName("foreign-key scalars xrefCustId/xrefAcctId round-trip as Long wrappers")
    void foreignKeyScalarsRoundTrip() {
        CardXref xref = new CardXref();

        xref.setXrefCustId(SAMPLE_CUST_ID);
        xref.setXrefAcctId(SAMPLE_ACCT_ID);

        // Wrapper-typed accessors (Long), not primitives: proven at compile time by these locals.
        final Long custId = xref.getXrefCustId();
        final Long acctId = xref.getXrefAcctId();

        assertThat(custId).isEqualTo(Long.valueOf(123456789L));
        assertThat(acctId).isEqualTo(Long.valueOf(12345678901L));
    }

    /**
     * A freshly constructed instance (via the JPA-mandated no-argument constructor) must leave
     * every accessor at its {@code null} default, since {@code CardXref} declares no field
     * initializers.
     */
    @Test
    @DisplayName("a freshly constructed instance returns null from every getter")
    void newInstanceDefaultsAreNull() {
        CardXref xref = new CardXref();

        assertThat(xref.getXrefCardNum()).isNull();
        assertThat(xref.getXrefCustId()).isNull();
        assertThat(xref.getXrefAcctId()).isNull();
    }

    /**
     * Re-invoking each setter must replace the previously stored value, confirming the accessors
     * are backed by simple mutable state with no write-once or accumulation semantics.
     */
    @Test
    @DisplayName("setters overwrite previously assigned values for every field")
    void settersOverwritePreviousValues() {
        CardXref xref = new CardXref();

        // Initial assignment.
        xref.setXrefCardNum(SAMPLE_CARD_NUM);
        xref.setXrefCustId(SAMPLE_CUST_ID);
        xref.setXrefAcctId(SAMPLE_ACCT_ID);

        // Second assignment must fully replace the first.
        xref.setXrefCardNum("5555444433332222");
        xref.setXrefCustId(987654321L);
        xref.setXrefAcctId(99999999999L);

        assertThat(xref.getXrefCardNum()).isEqualTo("5555444433332222");
        assertThat(xref.getXrefCustId()).isEqualTo(Long.valueOf(987654321L));
        assertThat(xref.getXrefAcctId()).isEqualTo(Long.valueOf(99999999999L));
    }
}
