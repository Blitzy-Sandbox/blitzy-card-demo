package com.carddemo.unit.service.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.model.entity.Card;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Card} (REFERENCE-ONLY, COBOL not copied; source commit 27d6c6f).
 * Verifies CVACT02Y round-trip (incl. misspelled cardExpiraionDate), @Version field, and identity
 * by cardNum per AAP {@code §0.8.4}.
 */
@DisplayName("Card entity — CVACT02Y mapping, identity by cardNum")
class CardTest {

    private Card card;

    @BeforeEach
    void setUp() {
        card = new Card();
    }

    @Test
    @DisplayName("fields round-trip through getters/setters (incl. misspelled cardExpiraionDate)")
    void fieldsRoundTrip() {
        card.setCardNum("4111111111111111");
        card.setCardAcctId(12345678901L);
        card.setCardCvvCd(123);
        card.setCardEmbossedName("JOHN Q PUBLIC");
        card.setCardExpiraionDate("2030-12-31");
        card.setCardActiveStatus("Y");
        card.setVersion(3L);

        assertThat(card.getCardNum()).isEqualTo("4111111111111111");
        assertThat(card.getCardAcctId()).isEqualTo(12345678901L);
        assertThat(card.getCardCvvCd()).isEqualTo(123);
        assertThat(card.getCardEmbossedName()).isEqualTo("JOHN Q PUBLIC");
        assertThat(card.getCardExpiraionDate()).isEqualTo("2030-12-31");
        assertThat(card.getCardActiveStatus()).isEqualTo("Y");
        assertThat(card.getVersion()).isEqualTo(3L);
    }

    @Test
    @DisplayName("identity is by cardNum only; equals(null)/equals(other type) are false")
    void identityByCardNum() {
        card.setCardNum("4111111111111111");
        card.setCardAcctId(1L);
        Card same = new Card();
        same.setCardNum("4111111111111111");
        same.setCardAcctId(2L);
        Card diff = new Card();
        diff.setCardNum("4222222222222222");

        assertThat(card).isEqualTo(same);
        assertThat(card).hasSameHashCodeAs(same);
        assertThat(card).isNotEqualTo(diff);
        assertThat(card.equals(null)).isFalse();
        assertThat(card.equals("nope")).isFalse();
    }
}
