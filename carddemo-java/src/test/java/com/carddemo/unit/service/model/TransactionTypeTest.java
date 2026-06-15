package com.carddemo.unit.service.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.model.entity.TransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TransactionType} (REFERENCE-ONLY, COBOL not copied; source commit 27d6c6f).
 * Verifies CVTRA03Y reference-table round-trip and identity by tranType per AAP {@code §0.5}.
 */
@DisplayName("TransactionType entity — CVTRA03Y reference, identity by tranType")
class TransactionTypeTest {

    private TransactionType type;

    @BeforeEach
    void setUp() {
        type = new TransactionType();
    }

    @Test
    @DisplayName("code and description round-trip through getters/setters")
    void fieldsRoundTrip() {
        type.setTranType("01");
        type.setTranTypeDesc("PURCHASE");

        assertThat(type.getTranType()).isEqualTo("01");
        assertThat(type.getTranTypeDesc()).isEqualTo("PURCHASE");
    }

    @Test
    @DisplayName("identity is by tranType only; equals(null)/equals(other type) are false")
    void identityByTranType() {
        type.setTranType("01");
        type.setTranTypeDesc("PURCHASE");
        TransactionType same = new TransactionType();
        same.setTranType("01");
        same.setTranTypeDesc("DIFFERENT DESC");
        TransactionType diff = new TransactionType();
        diff.setTranType("02");

        assertThat(type).isEqualTo(same);
        assertThat(type).hasSameHashCodeAs(same);
        assertThat(type).isNotEqualTo(diff);
        assertThat(type.equals(null)).isFalse();
        assertThat(type.equals("nope")).isFalse();
    }
}
