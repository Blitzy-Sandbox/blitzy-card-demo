package com.carddemo.unit.service.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.model.enums.FileStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FileStatus} (REFERENCE-ONLY, COBOL not copied; source commit 27d6c6f).
 * Verifies the CBTRN02C FILE STATUS code mapping per AAP {@code §0.8.4}.
 */
@DisplayName("FileStatus enum — FILE STATUS code mapping (CBTRN02C)")
class FileStatusTest {

    @Test
    @DisplayName("getCode() preserves the two-character code including leading zeros")
    void getCodePreservesLeadingZeros() {
        assertThat(FileStatus.SUCCESS.getCode()).isEqualTo("00");
        assertThat(FileStatus.READ_LENGTH_MISMATCH.getCode()).isEqualTo("04");
        assertThat(FileStatus.END_OF_FILE.getCode()).isEqualTo("10");
        assertThat(FileStatus.DUPLICATE_KEY.getCode()).isEqualTo("22");
        assertThat(FileStatus.RECORD_NOT_FOUND.getCode()).isEqualTo("23");
    }

    @Test
    @DisplayName("exactly five constants exist (no feature expansion)")
    void hasExactlyFiveConstants() {
        assertThat(FileStatus.values()).hasSize(5);
    }

    @Test
    @DisplayName("fromCode maps known codes to the matching constant")
    void fromCodeMapsKnownCodes() {
        assertThat(FileStatus.fromCode("00")).isEqualTo(FileStatus.SUCCESS);
        assertThat(FileStatus.fromCode("22")).isEqualTo(FileStatus.DUPLICATE_KEY);
        assertThat(FileStatus.fromCode("23")).isEqualTo(FileStatus.RECORD_NOT_FOUND);
    }

    @Test
    @DisplayName("fromCode returns null for unknown code and for null input")
    void fromCodeReturnsNullForUnknownOrNull() {
        assertThat(FileStatus.fromCode("99")).isNull();
        assertThat(FileStatus.fromCode(null)).isNull();
    }
}
