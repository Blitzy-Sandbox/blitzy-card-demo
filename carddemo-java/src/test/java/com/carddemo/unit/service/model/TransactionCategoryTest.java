package com.carddemo.unit.service.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.model.entity.TransactionCategory;
import com.carddemo.model.key.TransactionCategoryId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TransactionCategory} (REFERENCE-ONLY, COBOL not copied; source commit 27d6c6f).
 * Verifies CVTRA04Y @EmbeddedId (2-part) wiring and identity by composite id per AAP {@code §0.4.3}.
 */
@DisplayName("TransactionCategory entity — CVTRA04Y embedded id, description")
class TransactionCategoryTest {

    @Test
    @DisplayName("embedded id and description round-trip")
    void roundTrip() {
        TransactionCategoryId id = new TransactionCategoryId("01", 5);
        TransactionCategory cat = new TransactionCategory();
        cat.setId(id);
        cat.setTranCatTypeDesc("REGULAR SALES DRAFT");

        assertThat(cat.getId()).isEqualTo(id);
        assertThat(cat.getTranCatTypeDesc()).isEqualTo("REGULAR SALES DRAFT");
    }

    @Test
    @DisplayName("identity is by embedded id; equals(null)/equals(other type) are false")
    void identityByEmbeddedId() {
        TransactionCategory a = new TransactionCategory();
        a.setId(new TransactionCategoryId("01", 5));
        a.setTranCatTypeDesc("ONE");
        TransactionCategory b = new TransactionCategory();
        b.setId(new TransactionCategoryId("01", 5));
        b.setTranCatTypeDesc("TWO");
        TransactionCategory c = new TransactionCategory();
        c.setId(new TransactionCategoryId("02", 5));

        assertThat(a).isEqualTo(b);
        assertThat(a).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
        assertThat(a.equals(null)).isFalse();
        assertThat(a.equals("nope")).isFalse();
    }
}
