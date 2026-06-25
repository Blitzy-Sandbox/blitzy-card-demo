/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.carddemo.unit.enums;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.enums.TransactionTypeCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pure JUnit 5 unit tests for {@link TransactionTypeCode}.
 *
 * <p>This is the fastest test tier in the AWS CardDemo COBOL&#8594;Java
 * migration: it exercises a single in-memory enum with <em>no</em> Mockito,
 * Spring context, Jakarta wiring, or disk/file I/O. Every expected literal is
 * hard-coded here from the frozen parity sources read at authoring time
 * (commit SHA {@code 27d6c6f}); nothing is read from disk at runtime.
 *
 * <p>Parity sources (REFERENCE only, never copied into the codebase):
 * <ul>
 *   <li>{@code app/cpy/CVTRA03Y.cpy} &mdash; {@code TRAN-TYPE PIC X(02)},
 *       {@code TRAN-TYPE-DESC PIC X(50)}, {@code FILLER PIC X(08)}
 *       (record length 60), confirming the code is a two-character string.</li>
 *   <li>{@code app/data/ASCII/trantype.txt} &mdash; seven rows
 *       ({@code 01Purchase} &hellip; {@code 07Adjustment}), each trailing the
 *       eight-character COBOL FILLER {@code 00000000}. That trailer is the
 *       record FILLER, <strong>not</strong> an eighth transaction type, so
 *       there are exactly seven constants.</li>
 * </ul>
 *
 * <p>The contract under test:
 * {@link TransactionTypeCode#getCode()},
 * {@link TransactionTypeCode#getLabel()},
 * {@link TransactionTypeCode#fromCode(String)} (matching constant, or
 * {@link IllegalArgumentException} for an unknown/blank code), and the inherited
 * {@link TransactionTypeCode#values()} cardinality.
 */
@DisplayName("TransactionTypeCode enum")
class TransactionTypeCodeTest {

    /**
     * Verifies that every constant exposes the exact two-character legacy
     * {@code TRAN-TYPE} code and the human-readable description seeded from
     * {@code trantype.txt}. The {@code name} column resolves the constant via
     * {@link TransactionTypeCode#valueOf(String)} so the constant ordering is
     * irrelevant to the assertions.
     */
    @ParameterizedTest(name = "{0} -> code [{1}], label [{2}]")
    @CsvSource({
        "PURCHASE,01,Purchase",
        "PAYMENT,02,Payment",
        "CREDIT,03,Credit",
        "AUTHORIZATION,04,Authorization",
        "REFUND,05,Refund",
        "REVERSAL,06,Reversal",
        "ADJUSTMENT,07,Adjustment"
    })
    @DisplayName("exposes the legacy TRAN-TYPE code and description for every constant")
    void exposesCodeAndLabelForEveryConstant(String name, String expectedCode, String expectedLabel) {
        TransactionTypeCode type = TransactionTypeCode.valueOf(name);

        assertThat(type.getCode()).isEqualTo(expectedCode);
        assertThat(type.getLabel()).isEqualTo(expectedLabel);
    }

    /**
     * Verifies the {@link TransactionTypeCode#fromCode(String)} round-trip: each
     * known two-character code ({@code "01"} &hellip; {@code "07"}) resolves to
     * the same constant produced by {@link TransactionTypeCode#valueOf(String)}.
     */
    @ParameterizedTest(name = "fromCode([{1}]) -> {0}")
    @CsvSource({
        "PURCHASE,01",
        "PAYMENT,02",
        "CREDIT,03",
        "AUTHORIZATION,04",
        "REFUND,05",
        "REVERSAL,06",
        "ADJUSTMENT,07"
    })
    @DisplayName("fromCode resolves each two-character code to its constant")
    void fromCodeResolvesEveryKnownCode(String name, String expectedCode) {
        assertThat(TransactionTypeCode.fromCode(expectedCode))
            .isEqualTo(TransactionTypeCode.valueOf(name));
    }

    /**
     * Verifies that an unknown code is rejected with
     * {@link IllegalArgumentException}. Note that {@code "00"} is intentionally
     * invalid: it is the {@code trantype.txt} FILLER prefix
     * ({@code 00000000}), not a transaction type code. The empty string covers
     * the blank-input edge case.
     */
    @ParameterizedTest(name = "fromCode([{0}]) throws IllegalArgumentException")
    @ValueSource(strings = {"99", "00", "08", "XX", ""})
    @DisplayName("fromCode rejects unknown codes with IllegalArgumentException")
    void fromCodeRejectsUnknownCodes(String unknownCode) {
        assertThatThrownBy(() -> TransactionTypeCode.fromCode(unknownCode))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Guards the cardinality invariant: there are exactly seven transaction
     * types. The eight-character {@code 00000000} trailer on each
     * {@code trantype.txt} row is the COBOL record FILLER
     * ({@code CVTRA03Y} record length 60), <strong>not</strong> an eighth
     * transaction type.
     */
    @Test
    @DisplayName("defines exactly seven transaction types")
    void definesExactlySevenTransactionTypes() {
        assertThat(TransactionTypeCode.values()).hasSize(7);
    }
}
