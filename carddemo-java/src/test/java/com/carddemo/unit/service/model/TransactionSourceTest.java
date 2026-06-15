package com.carddemo.unit.service.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.model.enums.TransactionSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TransactionSource} (REFERENCE-ONLY, COBOL not copied; source commit 27d6c6f).
 * Verifies the dailytran.txt source classification (trim-tolerant) per AAP {@code §0.5}.
 */
@DisplayName("TransactionSource enum — dailytran source classification")
class TransactionSourceTest {

    @Test
    @DisplayName("getValue() returns the COBOL source token, single internal space preserved")
    void getValueReturnsSourceToken() {
        assertThat(TransactionSource.POS_TERMINAL.getValue()).isEqualTo("POS TERM");
        assertThat(TransactionSource.OPERATOR.getValue()).isEqualTo("OPERATOR");
    }

    @Test
    @DisplayName("exactly two constants exist (no feature expansion)")
    void hasExactlyTwoConstants() {
        assertThat(TransactionSource.values()).containsExactly(
                TransactionSource.POS_TERMINAL, TransactionSource.OPERATOR);
    }

    @Test
    @DisplayName("fromValue maps exact values to the matching constant")
    void fromValueMapsExactValues() {
        assertThat(TransactionSource.fromValue("POS TERM")).isEqualTo(TransactionSource.POS_TERMINAL);
        assertThat(TransactionSource.fromValue("OPERATOR")).isEqualTo(TransactionSource.OPERATOR);
    }

    @Test
    @DisplayName("fromValue trims surrounding whitespace before matching")
    void fromValueTrimsWhitespace() {
        assertThat(TransactionSource.fromValue("POS TERM  ")).isEqualTo(TransactionSource.POS_TERMINAL);
        assertThat(TransactionSource.fromValue("  OPERATOR  ")).isEqualTo(TransactionSource.OPERATOR);
    }

    @Test
    @DisplayName("fromValue returns null for unknown value and for null input")
    void fromValueReturnsNullForUnknownOrNull() {
        assertThat(TransactionSource.fromValue("ATM")).isNull();
        assertThat(TransactionSource.fromValue(null)).isNull();
    }
}
