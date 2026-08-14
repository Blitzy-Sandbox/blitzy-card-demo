/*
 * ******************************************************************
 * Program     : TransactionSource.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 enumeration
 * Function    : The two literal TRAN-SOURCE values assigned in the
 *               COBOL corpus. TRAN-SOURCE is PIC X(10) occupying
 *               bytes 23-32 of the 350 byte transaction record. Only
 *               two of the four assignment sites move a literal; the
 *               remaining two pass arbitrary ten character text
 *               through unchanged, so this type names the two known
 *               literals and is deliberately NOT the persisted type
 *               of the column.
 * Source      : app/cpy/CVTRA05Y.cpy:L8 @ 7756d89
 * Source      : app/cpy/CVTRA06Y.cpy:L8 @ 7756d89
 * Source      : app/cbl/CBACT04C.cbl:L484 @ 7756d89
 * Source      : app/cbl/COBIL00C.cbl:L222 @ 7756d89
 * Source      : app/cbl/COTRN02C.cbl:L454 @ 7756d89
 * Source      : app/cbl/CBTRN02C.cbl:L428 @ 7756d89
 * ******************************************************************
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
 * language governing permissions and limitations under the License
 * ******************************************************************
 */
package com.cardemo.model.enums;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The two literal {@code TRAN-SOURCE} values assigned anywhere in the frozen CardDemo COBOL corpus.
 *
 * <p>This is a pure value type. It holds no mutable state, performs no I/O, reads no configuration, emits no
 * log output and depends on nothing outside {@code java.util}. Every method is a total, pure function of its
 * arguments and of the two immutable constants declared below.
 *
 * @see #getCode()
 * @see #getFixedWidthValue()
 * @see #fromCode(String)
 * @see #fromCodeIgnoreCase(String)
 */
public enum TransactionSource {

    /**
     * The source marker written on system generated interest transactions by the interest calculation batch
     * program. Assigned by {@code MOVE 'System' TO TRAN-SOURCE} at {@code app/cbl/CBACT04C.cbl:484}, inside
     * paragraph {@code 1300-B-WRITE-TX} which begins at {@code app/cbl/CBACT04C.cbl:473}.
     */
    SYSTEM("System"),

    /**
     * The source marker written on online bill payment transactions. Assigned by
     * {@code MOVE 'POS TERM' TO TRAN-SOURCE} at {@code app/cbl/COBIL00C.cbl:222}, and also the value staged on
     * the majority of the rows of {@code app/data/ASCII/dailytran.txt} as input data rather than by assignment.
     */
    POS_TERMINAL("POS TERM");

    /**
     * The declared external width of {@code TRAN-SOURCE} and of {@code DALYTRAN-SOURCE} in characters, taken
     * from {@code PIC X(10)} at {@code app/cpy/CVTRA05Y.cpy:L8} and {@code app/cpy/CVTRA06Y.cpy:L8} and
     * independently corroborated by the fixture census, in which every observed value of bytes 23-32 is exactly
     * this many characters wide.
     */
    public static final int FIELD_LENGTH = 10;

    /**
     * Case sensitive index from unpadded literal to constant, used by {@link #fromCode(String)}.
     */
    private static final Map<String, TransactionSource> BY_CODE;

    /**
     * Index from the upper cased literal to constant, used by {@link #fromCodeIgnoreCase(String)}. Keys are
     * folded with {@link Locale#ROOT} so the mapping cannot vary with the host default locale.
     */
    private static final Map<String, TransactionSource> BY_UPPER_CASE_CODE;

    static {
        final Map<String, TransactionSource> byCode = new LinkedHashMap<>();
        final Map<String, TransactionSource> byUpperCaseCode = new LinkedHashMap<>();
        for (final TransactionSource source : values()) {
            byCode.put(source.code, source);
            byUpperCaseCode.put(source.code.toUpperCase(Locale.ROOT), source);
        }
        BY_CODE = Map.copyOf(byCode);
        BY_UPPER_CASE_CODE = Map.copyOf(byUpperCaseCode);
    }

    /**
     * The COBOL literal exactly as spelled in the source, without the {@code X(10)} blank padding.
     */
    private final String code;

    /**
     * The {@link #code} right padded with spaces to exactly {@value #FIELD_LENGTH} characters.
     */
    private final String fixedWidthValue;

    /**
     * Binds a constant to its COBOL literal and derives the fixed width form from it.
     *
     * @param code the COBOL literal, verbatim and unpadded.
     */
    TransactionSource(final String code) {
        this.code = code;
        this.fixedWidthValue = code + " ".repeat(FIELD_LENGTH - code.length());
    }

    /**
     * Returns the COBOL literal exactly as the source spells it, with no blank padding applied.
     *
     * @return the unpadded COBOL literal.
     */
    public String getCode() {
        return code;
    }

    /**
     * Returns the literal right padded with spaces to exactly {@value #FIELD_LENGTH} characters, which is the
     * external form of the {@code PIC X(10)} field.
     *
     * @return the ten character, right padded external form of this source marker
     */
    public String getFixedWidthValue() {
        return fixedWidthValue;
    }

    /**
     * Resolves external text to the constant carrying that literal, matching case sensitively.
     *
     * @param rawValue the external text to resolve, padded or unpadded.
     * @return the matching constant, or an empty {@link Optional} if the text is absent, blank or not one of
     * the two known literals
     */
    public static Optional<TransactionSource> fromCode(final String rawValue) {
        if (rawValue == null) {
            return Optional.empty();
        }
        final String stripped = rawValue.strip();
        if (stripped.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_CODE.get(stripped));
    }

    /**
     * Resolves external text to the constant carrying that literal, ignoring case differences.
     *
     * @param rawValue the external text to resolve, in any casing, padded or unpadded.
     * @return the matching constant, or an empty {@link Optional} if the text is absent, blank or not one of
     * the two known literals
     */
    public static Optional<TransactionSource> fromCodeIgnoreCase(final String rawValue) {
        if (rawValue == null) {
            return Optional.empty();
        }
        final String stripped = rawValue.strip();
        if (stripped.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_UPPER_CASE_CODE.get(stripped.toUpperCase(Locale.ROOT)));
    }
}
