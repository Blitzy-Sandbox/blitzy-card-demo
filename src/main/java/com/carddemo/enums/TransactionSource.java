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
package com.carddemo.enums;

/**
 * Canonical {@code TRAN-SOURCE} / {@code DALYTRAN-SOURCE} values
 * ({@code PIC X(10)}) translated from copybooks {@code CVTRA05Y} and
 * {@code CVTRA06Y} at commit {@code 27d6c6f}.
 *
 * <p>Each constant pairs the verbatim, case-sensitive source label as stored in
 * the legacy fixed-width record with its transaction-entry origin (online vs.
 * batch). The label held here is the trimmed canonical value; the underlying
 * field is space-padded to ten characters by the fixed-width reader/writer.</p>
 */
public enum TransactionSource {

    /** Point-of-sale terminal entry (online); {@code 'POS TERM'} in COBIL00C. */
    POS_TERMINAL("POS TERM", false),

    /** Operator-entered transaction (online); {@code 'OPERATOR'} in the fixture. */
    OPERATOR("OPERATOR", false),

    /** System-generated transaction (batch); {@code 'System'} in CBACT04C. */
    SYSTEM("System", true);

    /** Verbatim source label as stored in the {@code PIC X(10)} field. */
    private final String label;

    /** {@code true} when the source originates from batch (system) processing. */
    private final boolean batchOrigin;

    TransactionSource(final String label, final boolean batchOrigin) {
        this.label = label;
        this.batchOrigin = batchOrigin;
    }

    /**
     * Returns the verbatim, case-sensitive source label.
     *
     * @return the canonical (trimmed) label stored in the {@code TRAN-SOURCE} field
     */
    public String getLabel() {
        return label;
    }

    /**
     * Indicates whether the transaction source originates from batch processing.
     *
     * @return {@code true} for batch (system) origin, {@code false} for online origin
     */
    public boolean isBatchOrigin() {
        return batchOrigin;
    }

    /**
     * Indicates whether the transaction source originates from online entry.
     *
     * @return {@code true} for online origin, {@code false} for batch origin
     */
    public boolean isOnlineOrigin() {
        return !batchOrigin;
    }

    /**
     * Resolves a fixed-width {@code TRAN-SOURCE} / {@code DALYTRAN-SOURCE} value to
     * its constant. Trailing space padding from the ten-character field is removed
     * before a case-sensitive match against the known labels.
     *
     * @param source the raw source value, space-padded to ten characters
     * @return the matching {@code TransactionSource} constant
     * @throws IllegalArgumentException if {@code source} is {@code null} or does not
     *                                  match a known label
     */
    public static TransactionSource fromLabel(final String source) {
        if (source == null) {
            throw new IllegalArgumentException("Unknown transaction source: null");
        }
        final String canonical = source.stripTrailing();
        for (final TransactionSource value : values()) {
            if (value.label.equals(canonical)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown transaction source: '" + source + "'");
    }
}
