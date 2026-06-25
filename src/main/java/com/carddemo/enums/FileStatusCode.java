/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.enums;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * COBOL VSAM {@code FILE STATUS} codes used across {@code app/cbl} @ {@code 27d6c6f}.
 *
 * <p>Each constant models a two-character ANSI/VSAM file-status value
 * ({@code STAT1} + {@code STAT2}) exactly as the legacy programs test it (for
 * example {@code IF DALYTRAN-STATUS = '00'} or
 * {@code IF TCATBALF-STATUS = '00' OR '23'}). The value is preserved verbatim
 * as a two-character {@link String}; it is never converted to an integer
 * because {@code "04"} is distinct from the number {@code 4} and the leading
 * {@code '9'} byte denotes the implementor-defined logic-error family.</p>
 *
 * <p>This type is the typed vocabulary consumed by
 * {@code com.carddemo.service.FileStatusMapper}; translating a code into a
 * thrown exception is the mapper's responsibility, not this enum's.</p>
 */
public enum FileStatusCode {

    /** Successful completion (FILE STATUS {@code "00"}). */
    SUCCESS("00"),

    /** A read returned a duplicate alternate-index key, i.e. a non-unique AIX (FILE STATUS {@code "02"}). */
    DUPLICATE_ALTERNATE_KEY("02"),

    /** Record length conflict (FILE STATUS {@code "04"}). */
    RECORD_LENGTH_MISMATCH("04"),

    /** An optional file was created on OPEN (FILE STATUS {@code "05"}). */
    FILE_CREATED("05"),

    /** End-of-file reached on a sequential or browse read (FILE STATUS {@code "10"}). */
    END_OF_FILE("10"),

    /** Attempt to write a record with a duplicate primary key (FILE STATUS {@code "22"}). */
    DUPLICATE_KEY("22"),

    /** Record not found / no record for the requested key (FILE STATUS {@code "23"}). */
    RECORD_NOT_FOUND("23"),

    /** OPEN of a non-existent required file (FILE STATUS {@code "35"}). */
    FILE_NOT_FOUND("35"),

    /** Implementor-defined logic-error family representative (FILE STATUS {@code "9x"}, modeled as {@code "90"}). */
    LOGIC_ERROR("90");

    /** First character of any code that belongs to the {@code 9x} logic-error family. */
    private static final char LOGIC_ERROR_FAMILY_PREFIX = '9';

    /** Immutable lookup of canonical two-character code to its constant. */
    private static final Map<String, FileStatusCode> BY_CODE;

    static {
        Map<String, FileStatusCode> byCode = new HashMap<>();
        for (FileStatusCode value : values()) {
            byCode.put(value.code, value);
        }
        BY_CODE = Collections.unmodifiableMap(byCode);
    }

    /** The verbatim two-character COBOL FILE STATUS code. */
    private final String code;

    FileStatusCode(String code) {
        this.code = code;
    }

    /**
     * Returns the verbatim two-character COBOL {@code FILE STATUS} code.
     *
     * @return the two-character status code, never {@code null}
     */
    public String getCode() {
        return code;
    }

    /**
     * Indicates whether this constant is the success status ({@code "00"}).
     *
     * @return {@code true} only for {@link #SUCCESS}
     */
    public boolean isSuccess() {
        return this == SUCCESS;
    }

    /**
     * Resolves a raw COBOL {@code FILE STATUS} value to its constant.
     *
     * <p>Surrounding whitespace is trimmed. Any value whose first character is
     * {@code '9'} resolves to {@link #LOGIC_ERROR} (the {@code 9x} family).
     * Every other value must match a declared code exactly.</p>
     *
     * @param status the raw two-character status value
     * @return the matching constant
     * @throws IllegalArgumentException if {@code status} is {@code null}, blank,
     *         or not a recognized non-{@code 9x} code
     */
    public static FileStatusCode fromCode(String status) {
        if (status == null) {
            throw new IllegalArgumentException("COBOL FILE STATUS code must not be null");
        }
        String normalized = status.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("COBOL FILE STATUS code must not be blank");
        }
        if (normalized.charAt(0) == LOGIC_ERROR_FAMILY_PREFIX) {
            return LOGIC_ERROR;
        }
        FileStatusCode resolved = BY_CODE.get(normalized);
        if (resolved == null) {
            throw new IllegalArgumentException(
                    "Unrecognized COBOL FILE STATUS code: '" + normalized + "'");
        }
        return resolved;
    }
}
