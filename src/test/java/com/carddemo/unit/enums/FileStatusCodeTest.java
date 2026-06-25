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
package com.carddemo.unit.enums;

import com.carddemo.enums.FileStatusCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests for {@link FileStatusCode}, the typed vocabulary that models the
 * COBOL VSAM {@code FILE STATUS} two-character codes used throughout the legacy
 * {@code app/cbl} corpus (@ commit {@code 27d6c6f}) during the AWS CardDemo
 * COBOL&#8594;Java migration.
 *
 * <p>The contract exercised here is derived from the legacy file-status idiom seen in
 * {@code CBTRN02C} (success {@code '00'}, the {@code '00' OR '23'} record-not-found
 * special case, and {@code '10'} end-of-file driving the {@code APPL-EOF} flag) and
 * {@code CBACT04C} (same VSAM idiom, with the {@code 9x} family denoting the
 * implementor-defined logic-error class). These programs are consulted as parity
 * references only; no COBOL is reproduced here.</p>
 *
 * <p>By design this suite is a <em>pure</em> JUnit&nbsp;5 + AssertJ test: it touches
 * no Spring context, no Mockito mocks, no Jakarta APIs and performs no I/O, so it runs
 * deterministically and instantly under Surefire (class name ends in {@code Test}).</p>
 */
class FileStatusCodeTest {

    /**
     * Verifies that every one of the nine declared constants exposes its verbatim
     * two-character COBOL {@code FILE STATUS} code via {@link FileStatusCode#getCode()}.
     * The leading-zero codes ({@code "00"}, {@code "02"}, {@code "04"}, {@code "05"})
     * must be preserved exactly as {@link String} values and never collapsed to integers.
     *
     * @param constantName the name of the {@link FileStatusCode} constant under test
     * @param expectedCode the two-character code that constant must report
     */
    @ParameterizedTest(name = "{0}.getCode() == \"{1}\"")
    @CsvSource({
            "SUCCESS,00",
            "DUPLICATE_ALTERNATE_KEY,02",
            "RECORD_LENGTH_MISMATCH,04",
            "FILE_CREATED,05",
            "END_OF_FILE,10",
            "DUPLICATE_KEY,22",
            "RECORD_NOT_FOUND,23",
            "FILE_NOT_FOUND,35",
            "LOGIC_ERROR,90"
    })
    void getCodeReturnsVerbatimTwoCharacterCode(String constantName, String expectedCode) {
        assertThat(FileStatusCode.valueOf(constantName).getCode()).isEqualTo(expectedCode);
    }

    /**
     * Verifies the success predicate across the entire enum in a single pass:
     * {@link FileStatusCode#isSuccess()} must return {@code true} for, and only for,
     * {@link FileStatusCode#SUCCESS}. Asserting equality against the identity
     * expression {@code (status == SUCCESS)} simultaneously proves {@code true} for
     * {@code SUCCESS} and {@code false} for the other eight constants.
     *
     * @param status each declared {@link FileStatusCode} constant, supplied by JUnit
     */
    @ParameterizedTest(name = "{0}.isSuccess()")
    @EnumSource(FileStatusCode.class)
    void isSuccessIsTrueOnlyForSuccess(FileStatusCode status) {
        assertThat(status.isSuccess()).isEqualTo(status == FileStatusCode.SUCCESS);
    }

    /**
     * Verifies that {@link FileStatusCode#fromCode(String)} resolves each of the nine
     * canonical two-character codes back to its matching constant. The {@code "90"}
     * row also confirms the boundary where an exact code coincides with the
     * {@code 9x} logic-error family.
     *
     * @param code                 the raw two-character status value to resolve
     * @param expectedConstantName the name of the constant {@code fromCode} must return
     */
    @ParameterizedTest(name = "fromCode(\"{0}\") == {1}")
    @CsvSource({
            "00,SUCCESS",
            "02,DUPLICATE_ALTERNATE_KEY",
            "04,RECORD_LENGTH_MISMATCH",
            "05,FILE_CREATED",
            "10,END_OF_FILE",
            "22,DUPLICATE_KEY",
            "23,RECORD_NOT_FOUND",
            "35,FILE_NOT_FOUND",
            "90,LOGIC_ERROR"
    })
    void fromCodeResolvesKnownCodesToConstants(String code, String expectedConstantName) {
        assertThat(FileStatusCode.fromCode(code)).isEqualTo(FileStatusCode.valueOf(expectedConstantName));
    }

    /**
     * Verifies the CRITICAL {@code 9x} classification rule: any code whose first
     * character is {@code '9'} (the implementor-defined logic-error family) must
     * resolve to {@link FileStatusCode#LOGIC_ERROR}, regardless of the second digit.
     * The spec example {@code "92"} is included explicitly alongside the family
     * boundaries {@code "90"} and {@code "99"}.
     *
     * @param code a member of the {@code 9x} family that must map to {@code LOGIC_ERROR}
     */
    @ParameterizedTest(name = "fromCode(\"{0}\") == LOGIC_ERROR")
    @ValueSource(strings = {"90", "91", "92", "98", "99"})
    void fromCodeMapsNineFamilyToLogicError(String code) {
        assertThat(FileStatusCode.fromCode(code)).isEqualTo(FileStatusCode.LOGIC_ERROR);
    }

    /**
     * Verifies that an unrecognized code that is <em>not</em> part of the {@code 9x}
     * family is rejected with {@link IllegalArgumentException}. None of the supplied
     * values begin with {@code '9'} and none match a declared code, so each must fail
     * lookup. The spec example {@code "77"} is included explicitly.
     *
     * @param code an unknown, non-{@code 9x} status value that must be rejected
     */
    @ParameterizedTest(name = "fromCode(\"{0}\") throws IllegalArgumentException")
    @ValueSource(strings = {"77", "11", "88", "ZZ"})
    void fromCodeRejectsUnknownNonNineCodes(String code) {
        assertThatThrownBy(() -> FileStatusCode.fromCode(code))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Verifies the enum cardinality contract: {@link FileStatusCode#values()} must
     * expose exactly the nine declared constants, guarding against accidental
     * addition or removal of a status code.
     */
    @Test
    void valuesContainsExactlyNineConstants() {
        assertThat(FileStatusCode.values()).hasSize(9);
    }
}
