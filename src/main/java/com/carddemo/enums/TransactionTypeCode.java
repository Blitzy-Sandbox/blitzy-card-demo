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
package com.carddemo.enums;

import java.util.HashMap;
import java.util.Map;

/**
 * Transaction type codes from COBOL TRAN-TYPE-RECORD (CVTRA03Y) @ 27d6c6f.
 *
 * <p>Each constant carries the legacy two-character {@code TRAN-TYPE}
 * ({@code PIC X(02)}) code and its description, seeded from
 * {@code app/data/ASCII/trantype.txt}. The two-character string width is
 * preserved exactly because the code participates in composite keys elsewhere
 * in the system.
 */
public enum TransactionTypeCode {

    PURCHASE("01", "Purchase"),
    PAYMENT("02", "Payment"),
    CREDIT("03", "Credit"),
    AUTHORIZATION("04", "Authorization"),
    REFUND("05", "Refund"),
    REVERSAL("06", "Reversal"),
    ADJUSTMENT("07", "Adjustment");

    /**
     * Immutable lookup index from two-character code to enum constant, built
     * once at class initialization for deterministic constant-time resolution.
     */
    private static final Map<String, TransactionTypeCode> BY_CODE = buildIndex();

    private final String code;
    private final String label;

    private TransactionTypeCode(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /**
     * Returns the legacy two-character {@code TRAN-TYPE} code (for example,
     * {@code "01"}).
     *
     * @return the two-character transaction type code
     */
    public String getCode() {
        return code;
    }

    /**
     * Returns the human-readable transaction type description (for example,
     * {@code "Purchase"}).
     *
     * @return the transaction type label
     */
    public String getLabel() {
        return label;
    }

    /**
     * Resolves a {@link TransactionTypeCode} from its two-character code.
     *
     * <p>Leading and trailing whitespace originating from fixed-width record
     * reads is trimmed before the exact two-character code is matched.
     *
     * @param code the two-character transaction type code to resolve
     * @return the matching {@link TransactionTypeCode}
     * @throws IllegalArgumentException if {@code code} is {@code null} or does
     *                                  not match a known transaction type code
     */
    public static TransactionTypeCode fromCode(String code) {
        if (code != null) {
            TransactionTypeCode match = BY_CODE.get(code.trim());
            if (match != null) {
                return match;
            }
        }
        throw new IllegalArgumentException(
                "Unknown transaction type code: '" + code + "'");
    }

    private static Map<String, TransactionTypeCode> buildIndex() {
        Map<String, TransactionTypeCode> index = new HashMap<>();
        for (TransactionTypeCode type : values()) {
            index.put(type.code, type);
        }
        return Map.copyOf(index);
    }
}
