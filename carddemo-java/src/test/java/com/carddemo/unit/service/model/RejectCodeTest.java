package com.carddemo.unit.service.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.model.enums.RejectCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RejectCode} (REFERENCE-ONLY, COBOL not copied; source commit 27d6c6f).
 * Verifies the CBTRN02C posting reject codes (100,101,102,103,109) per AAP {@code §0.5/§0.8}.
 */
@DisplayName("RejectCode enum — CBTRN02C posting reject codes")
class RejectCodeTest {

    @Test
    @DisplayName("getCode()/getDescription() round-trip for every constant")
    void codeAndDescriptionRoundTrip() {
        assertThat(RejectCode.INVALID_CARD_NUMBER.getCode()).isEqualTo(100);
        assertThat(RejectCode.INVALID_CARD_NUMBER.getDescription()).isEqualTo("INVALID CARD NUMBER FOUND");
        assertThat(RejectCode.ACCOUNT_NOT_FOUND.getCode()).isEqualTo(101);
        assertThat(RejectCode.OVERLIMIT_TRANSACTION.getCode()).isEqualTo(102);
        assertThat(RejectCode.OVERLIMIT_TRANSACTION.getDescription()).isEqualTo("OVERLIMIT TRANSACTION");
        assertThat(RejectCode.TRANSACTION_AFTER_EXPIRATION.getCode()).isEqualTo(103);
        assertThat(RejectCode.ACCOUNT_UPDATE_NOT_FOUND.getCode()).isEqualTo(109);
    }

    @Test
    @DisplayName("exactly five reject codes exist; 104-108 are absent (no feature expansion)")
    void hasExactlyFiveConstantsAndNoGapCodes() {
        assertThat(RejectCode.values()).hasSize(5);
        assertThat(RejectCode.fromCode(104)).isNull();
        assertThat(RejectCode.fromCode(105)).isNull();
        assertThat(RejectCode.fromCode(106)).isNull();
        assertThat(RejectCode.fromCode(107)).isNull();
        assertThat(RejectCode.fromCode(108)).isNull();
    }

    @Test
    @DisplayName("fromCode maps known numeric codes to the matching constant")
    void fromCodeMapsKnownCodes() {
        assertThat(RejectCode.fromCode(100)).isEqualTo(RejectCode.INVALID_CARD_NUMBER);
        assertThat(RejectCode.fromCode(102)).isEqualTo(RejectCode.OVERLIMIT_TRANSACTION);
        assertThat(RejectCode.fromCode(109)).isEqualTo(RejectCode.ACCOUNT_UPDATE_NOT_FOUND);
    }

    @Test
    @DisplayName("fromCode returns null for an unmapped code")
    void fromCodeReturnsNullForUnmapped() {
        assertThat(RejectCode.fromCode(999)).isNull();
    }

    @Test
    @DisplayName("101 and 109 share a description yet remain distinct constants")
    void sharedDescriptionButDistinctConstants() {
        assertThat(RejectCode.ACCOUNT_NOT_FOUND.getDescription())
                .isEqualTo(RejectCode.ACCOUNT_UPDATE_NOT_FOUND.getDescription());
        assertThat(RejectCode.ACCOUNT_NOT_FOUND).isNotEqualTo(RejectCode.ACCOUNT_UPDATE_NOT_FOUND);
    }
}
