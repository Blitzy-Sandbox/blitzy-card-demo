/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.unit.enums;

import com.carddemo.enums.TransactionSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure JUnit&nbsp;5 unit test for {@link TransactionSource}, the canonical
 * translation of the COBOL {@code TRAN-SOURCE} / {@code DALYTRAN-SOURCE} channel
 * field ({@code PIC X(10)}) defined in copybooks {@code CVTRA05Y} and
 * {@code CVTRA06Y} at source commit {@code 27d6c6f}.
 *
 * <p>The assertions encode 100% behavioral parity with the legacy fixed-width
 * record contract:</p>
 * <ul>
 *   <li>{@code 'POS TERM'} — moved to {@code TRAN-SOURCE} by {@code COBIL00C} (bill
 *       payment, online); the single embedded space between {@code POS} and
 *       {@code TERM} is significant and must never be collapsed.</li>
 *   <li>{@code 'OPERATOR'} — the operator-entered channel observed in the
 *       authoritative fixture {@code app/data/ASCII/dailytran.txt} (online).</li>
 *   <li>{@code 'System'} — moved to {@code TRAN-SOURCE} by {@code CBACT04C}
 *       (batch interest posting); the value is <em>mixed case</em> and matched
 *       case-sensitively.</li>
 * </ul>
 *
 * <p>Because the underlying field is space-padded to ten characters by the
 * fixed-width reader/writer, {@link TransactionSource#fromLabel(String)} must
 * strip <em>trailing</em> padding only (preserving any embedded space) and then
 * match case-sensitively, rejecting unknown values with
 * {@link IllegalArgumentException}.</p>
 *
 * <p>This is a dependency-free POJO test: no Mockito, Spring, Jakarta, or I/O is
 * involved. The COBOL fixtures inform the expected in-memory literals only.</p>
 */
@DisplayName("TransactionSource enum — COBOL TRAN-SOURCE / DALYTRAN-SOURCE parity")
class TransactionSourceTest {

    // ------------------------------------------------------------------
    // getLabel() — verbatim, case-sensitive source labels
    // ------------------------------------------------------------------

    @Test
    @DisplayName("getLabel(): POS_TERMINAL is 'POS TERM' with its embedded space preserved")
    void getLabelReturnsPosTermWithEmbeddedSpace() {
        assertThat(TransactionSource.POS_TERMINAL.getLabel()).isEqualTo("POS TERM");
    }

    @Test
    @DisplayName("getLabel(): OPERATOR is 'OPERATOR'")
    void getLabelReturnsOperator() {
        assertThat(TransactionSource.OPERATOR.getLabel()).isEqualTo("OPERATOR");
    }

    @Test
    @DisplayName("getLabel(): SYSTEM is mixed-case 'System'")
    void getLabelReturnsMixedCaseSystem() {
        assertThat(TransactionSource.SYSTEM.getLabel()).isEqualTo("System");
    }

    // ------------------------------------------------------------------
    // isBatchOrigin() — true only for the batch (system) channel
    // ------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(TransactionSource.class)
    @DisplayName("isBatchOrigin(): true only for SYSTEM, false for the online channels")
    void isBatchOriginIsTrueOnlyForSystem(final TransactionSource source) {
        assertThat(source.isBatchOrigin()).isEqualTo(source == TransactionSource.SYSTEM);
    }

    // ------------------------------------------------------------------
    // isOnlineOrigin() — exact logical complement of isBatchOrigin()
    // ------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(TransactionSource.class)
    @DisplayName("isOnlineOrigin(): is the logical complement of isBatchOrigin() for every constant")
    void isOnlineOriginIsComplementOfBatchOrigin(final TransactionSource source) {
        assertThat(source.isOnlineOrigin()).isEqualTo(!source.isBatchOrigin());
    }

    @Test
    @DisplayName("isOnlineOrigin(): POS_TERMINAL is online (true); SYSTEM is not online (false)")
    void isOnlineOriginExplicitCases() {
        assertThat(TransactionSource.POS_TERMINAL.isOnlineOrigin()).isTrue();
        assertThat(TransactionSource.SYSTEM.isOnlineOrigin()).isFalse();
    }

    // ------------------------------------------------------------------
    // fromLabel() — exact, unpadded labels resolve to their constant
    // ------------------------------------------------------------------

    @Test
    @DisplayName("fromLabel(): resolves the exact, unpadded source labels")
    void fromLabelResolvesExactLabels() {
        assertThat(TransactionSource.fromLabel("POS TERM")).isEqualTo(TransactionSource.POS_TERMINAL);
        assertThat(TransactionSource.fromLabel("OPERATOR")).isEqualTo(TransactionSource.OPERATOR);
        assertThat(TransactionSource.fromLabel("System")).isEqualTo(TransactionSource.SYSTEM);
    }

    // ------------------------------------------------------------------
    // fromLabel() — trims the trailing PIC X(10) space padding (CRITICAL)
    //
    // These padded literals are intentionally expressed as plain @Test
    // String literals rather than via @CsvSource: JUnit's @CsvSource trims
    // trailing whitespace by default (ignoreLeadingAndTrailingWhitespace),
    // which would silently strip the padding and defeat the assertion.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("fromLabel(): strips trailing 10-char padding while keeping the embedded space")
    void fromLabelTrimsTrailingPadding() {
        assertThat(TransactionSource.fromLabel("POS TERM  ")).isEqualTo(TransactionSource.POS_TERMINAL);
        assertThat(TransactionSource.fromLabel("OPERATOR  ")).isEqualTo(TransactionSource.OPERATOR);
        assertThat(TransactionSource.fromLabel("System    ")).isEqualTo(TransactionSource.SYSTEM);
    }

    // ------------------------------------------------------------------
    // fromLabel() — case-sensitive matching: only 'System' matches SYSTEM
    // ------------------------------------------------------------------

    @Test
    @DisplayName("fromLabel(): case-sensitive — 'system' and 'SYSTEM' do not match 'System'")
    void fromLabelIsCaseSensitive() {
        assertThatThrownBy(() -> TransactionSource.fromLabel("system"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TransactionSource.fromLabel("SYSTEM"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------
    // fromLabel() — unknown and empty labels are rejected
    // ------------------------------------------------------------------

    @Test
    @DisplayName("fromLabel(): unknown and empty labels throw IllegalArgumentException")
    void fromLabelRejectsUnknownAndEmpty() {
        assertThatThrownBy(() -> TransactionSource.fromLabel("FOO"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TransactionSource.fromLabel(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------
    // Cardinality — exactly the three modeled channels exist
    // ------------------------------------------------------------------

    @Test
    @DisplayName("values(): exactly three transaction sources are defined")
    void valuesHasExactlyThreeConstants() {
        assertThat(TransactionSource.values()).hasSize(3);
    }
}
