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
package com.carddemo.service;

import com.carddemo.enums.FileStatusCode;
import com.carddemo.exception.DuplicateRecordException;
import com.carddemo.exception.FileAccessException;
import com.carddemo.exception.RecordNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link FileStatusMapper}.
 *
 * <p>The mapper centralizes every COBOL VSAM {@code FILE STATUS} outcome into the
 * typed exception hierarchy. These tests assert the principal status mappings,
 * the end-of-file and success signals, and that unrecognized, blank, and
 * {@code null} raw statuses surface a typed {@link FileAccessException} rather
 * than a raw {@link IllegalArgumentException} from status parsing.
 */
@DisplayName("FileStatusMapper — typed FILE STATUS translation")
class FileStatusMapperTest {

    private static final String OPERATION = "READ ACCTFILE";
    private static final Object KEY = "00000000010";

    private FileStatusMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new FileStatusMapper();
    }

    @Nested
    @DisplayName("check(FileStatusCode, ...) — enum overload")
    class CheckEnum {

        @ParameterizedTest(name = "{0} returns without throwing")
        @EnumSource(value = FileStatusCode.class,
                names = {"SUCCESS", "FILE_CREATED", "DUPLICATE_ALTERNATE_KEY", "END_OF_FILE"})
        @DisplayName("benign statuses return normally")
        void benignStatuses(FileStatusCode status) {
            assertThatCode(() -> mapper.check(status, OPERATION, KEY)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("RECORD_NOT_FOUND raises RecordNotFoundException")
        void recordNotFound() {
            assertThatThrownBy(() -> mapper.check(FileStatusCode.RECORD_NOT_FOUND, OPERATION, KEY))
                    .isInstanceOf(RecordNotFoundException.class);
        }

        @Test
        @DisplayName("DUPLICATE_KEY raises DuplicateRecordException")
        void duplicateKey() {
            assertThatThrownBy(() -> mapper.check(FileStatusCode.DUPLICATE_KEY, OPERATION, KEY))
                    .isInstanceOf(DuplicateRecordException.class);
        }

        @ParameterizedTest(name = "{0} raises FileAccessException")
        @EnumSource(value = FileStatusCode.class,
                names = {"RECORD_LENGTH_MISMATCH", "FILE_NOT_FOUND", "LOGIC_ERROR"})
        @DisplayName("logic and I/O statuses raise FileAccessException")
        void logicAndIoStatuses(FileStatusCode status) {
            assertThatThrownBy(() -> mapper.check(status, OPERATION, KEY))
                    .isInstanceOf(FileAccessException.class);
        }

        @Test
        @DisplayName("a null status raises FileAccessException")
        void nullStatus() {
            assertThatThrownBy(() -> mapper.check((FileStatusCode) null, OPERATION, KEY))
                    .isInstanceOf(FileAccessException.class);
        }
    }

    @Nested
    @DisplayName("check(String, ...) — raw status overload")
    class CheckRaw {

        @ParameterizedTest(name = "\"{0}\" returns without throwing")
        @ValueSource(strings = {"00", "05", "02", "10"})
        @DisplayName("benign raw statuses return normally")
        void benignRawStatuses(String status) {
            assertThatCode(() -> mapper.check(status, OPERATION, KEY)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("\"23\" raises RecordNotFoundException")
        void recordNotFound() {
            assertThatThrownBy(() -> mapper.check("23", OPERATION, KEY))
                    .isInstanceOf(RecordNotFoundException.class);
        }

        @Test
        @DisplayName("\"22\" raises DuplicateRecordException")
        void duplicateKey() {
            assertThatThrownBy(() -> mapper.check("22", OPERATION, KEY))
                    .isInstanceOf(DuplicateRecordException.class);
        }

        @ParameterizedTest(name = "\"{0}\" raises FileAccessException")
        @ValueSource(strings = {"04", "35"})
        @DisplayName("recognized I/O statuses raise FileAccessException")
        void recognizedIoStatuses(String status) {
            assertThatThrownBy(() -> mapper.check(status, OPERATION, KEY))
                    .isInstanceOf(FileAccessException.class);
        }

        @ParameterizedTest(name = "9x status \"{0}\" raises FileAccessException")
        @ValueSource(strings = {"90", "91", "99", "9A"})
        @DisplayName("the 9x logic-error family raises FileAccessException")
        void logicErrorFamily(String status) {
            assertThatThrownBy(() -> mapper.check(status, OPERATION, KEY))
                    .isInstanceOf(FileAccessException.class);
        }

        @ParameterizedTest(name = "unknown non-9x status \"{0}\" raises FileAccessException")
        @ValueSource(strings = {"46", "11", "07", "36"})
        @DisplayName("unknown non-9x statuses surface a typed FileAccessException, not IllegalArgumentException")
        void unknownStatusIsTyped(String status) {
            assertThatThrownBy(() -> mapper.check(status, OPERATION, KEY))
                    .isInstanceOf(FileAccessException.class)
                    .hasCauseInstanceOf(IllegalArgumentException.class)
                    .extracting(ex -> ((FileAccessException) ex).getFileStatus())
                    .isEqualTo(status);
        }

        @Test
        @DisplayName("a blank raw status surfaces a typed FileAccessException")
        void blankStatusIsTyped() {
            assertThatThrownBy(() -> mapper.check("  ", OPERATION, KEY))
                    .isInstanceOf(FileAccessException.class)
                    .hasCauseInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a null raw status surfaces a typed FileAccessException")
        void nullStatusIsTyped() {
            assertThatThrownBy(() -> mapper.check((String) null, OPERATION, KEY))
                    .isInstanceOf(FileAccessException.class)
                    .hasCauseInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("isEndOfFile")
    class EndOfFile {

        @Test
        @DisplayName("returns true only for END_OF_FILE")
        void endOfFile() {
            assertThat(mapper.isEndOfFile(FileStatusCode.END_OF_FILE)).isTrue();
        }

        @ParameterizedTest(name = "returns false for {0}")
        @EnumSource(value = FileStatusCode.class, names = {"SUCCESS", "RECORD_NOT_FOUND", "LOGIC_ERROR"})
        @NullSource
        @DisplayName("returns false for non-end-of-file and null statuses")
        void notEndOfFile(FileStatusCode status) {
            assertThat(mapper.isEndOfFile(status)).isFalse();
        }
    }

    @Nested
    @DisplayName("isSuccess")
    class Success {

        @Test
        @DisplayName("isSuccess(FileStatusCode) is true only for SUCCESS")
        void isSuccessEnum() {
            assertThat(mapper.isSuccess(FileStatusCode.SUCCESS)).isTrue();
            assertThat(mapper.isSuccess(FileStatusCode.RECORD_NOT_FOUND)).isFalse();
            assertThat(mapper.isSuccess((FileStatusCode) null)).isFalse();
        }

        @Test
        @DisplayName("isSuccess(String) resolves recognized codes without throwing")
        void isSuccessRawRecognized() {
            assertThat(mapper.isSuccess("00")).isTrue();
            assertThat(mapper.isSuccess("23")).isFalse();
            assertThat(mapper.isSuccess("90")).isFalse();
            assertThat(mapper.isSuccess("91")).isFalse();
        }

        @ParameterizedTest(name = "isSuccess(\"{0}\") surfaces a typed FileAccessException")
        @ValueSource(strings = {"46", "  "})
        @DisplayName("isSuccess(String) surfaces a typed FileAccessException for unknown or blank input")
        void isSuccessRawUnknown(String status) {
            assertThatThrownBy(() -> mapper.isSuccess(status))
                    .isInstanceOf(FileAccessException.class)
                    .hasCauseInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("isSuccess(String) surfaces a typed FileAccessException for null input")
        void isSuccessRawNull() {
            assertThatThrownBy(() -> mapper.isSuccess((String) null))
                    .isInstanceOf(FileAccessException.class)
                    .hasCauseInstanceOf(IllegalArgumentException.class);
        }
    }
}
