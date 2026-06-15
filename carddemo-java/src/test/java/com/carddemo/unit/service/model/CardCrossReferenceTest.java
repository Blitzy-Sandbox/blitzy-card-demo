package com.carddemo.unit.service.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.model.entity.CardCrossReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CardCrossReference} (REFERENCE-ONLY, COBOL not copied; source commit 27d6c6f).
 * Verifies CVACT03Y card-account-customer linkage round-trip and identity by xrefCardNum per AAP {@code §0.5}.
 */
@DisplayName("CardCrossReference entity — CVACT03Y linkage, identity by xrefCardNum")
class CardCrossReferenceTest {

    private CardCrossReference xref;

    @BeforeEach
    void setUp() {
        xref = new CardCrossReference();
    }

    @Test
    @DisplayName("linkage fields round-trip through getters/setters")
    void fieldsRoundTrip() {
        xref.setXrefCardNum("4111111111111111");
        xref.setXrefCustId(123456789L);
        xref.setXrefAcctId(12345678901L);

        assertThat(xref.getXrefCardNum()).isEqualTo("4111111111111111");
        assertThat(xref.getXrefCustId()).isEqualTo(123456789L);
        assertThat(xref.getXrefAcctId()).isEqualTo(12345678901L);
    }

    @Test
    @DisplayName("identity is by xrefCardNum only; equals(null)/equals(other type) are false")
    void identityByXrefCardNum() {
        xref.setXrefCardNum("4111111111111111");
        xref.setXrefAcctId(1L);
        CardCrossReference same = new CardCrossReference();
        same.setXrefCardNum("4111111111111111");
        same.setXrefAcctId(2L);
        CardCrossReference diff = new CardCrossReference();
        diff.setXrefCardNum("4222222222222222");

        assertThat(xref).isEqualTo(same);
        assertThat(xref).hasSameHashCodeAs(same);
        assertThat(xref).isNotEqualTo(diff);
        assertThat(xref.equals(null)).isFalse();
        assertThat(xref.equals("nope")).isFalse();
    }
}
