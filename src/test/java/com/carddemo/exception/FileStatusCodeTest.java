package com.carddemo.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Pure, dependency-free unit test for {@link FileStatusCode}.
 *
 * <p>It validates the legacy COBOL {@code FILE STATUS} &rarr; typed-constant
 * mapping that the enum encodes: the two-character status literal, its
 * human-readable description, the {@link HttpStatus} surfaced at the REST
 * boundary, and the single end-of-file normal-termination flag. It also
 * exercises the null-safe {@link FileStatusCode#fromCode(String)} lookup
 * factory (which never returns {@code null}, falling back to
 * {@link FileStatusCode#UNKNOWN}) and the {@link FileStatusCode#isNormalTermination(String)}
 * batch-reader predicate.</p>
 *
 * <p>The test loads no Spring context and uses no external services, database,
 * file, or network I/O — every assertion is an in-memory enum check, so the
 * suite runs fast and deterministically and contributes to line coverage
 * (JaCoCo). The design rationale for the COBOL-to-{@link HttpStatus} choices
 * lives in {@code docs/decision-log.md}, not in these comments.</p>
 */
@DisplayName("FileStatusCode — COBOL FILE STATUS to HttpStatus mapping")
class FileStatusCodeTest {

    // ---------------------------------------------------------------------
    // Phase 1 — Exact-code lookups via fromCode(String)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("fromCode(\"00\") resolves to SUCCESS (HTTP 200, not EOF)")
    void fromCodeSuccess() {
        assertThat(FileStatusCode.fromCode("00")).isSameAs(FileStatusCode.SUCCESS);
        assertThat(FileStatusCode.SUCCESS.getCode()).isEqualTo("00");
        assertThat(FileStatusCode.SUCCESS.getHttpStatus()).isEqualTo(HttpStatus.OK);
        assertThat(FileStatusCode.SUCCESS.isEndOfFile()).isFalse();
    }

    @Test
    @DisplayName("fromCode(\"10\") resolves to END_OF_FILE (HTTP 200, EOF — normal termination)")
    void fromCodeEndOfFile() {
        assertThat(FileStatusCode.fromCode("10")).isSameAs(FileStatusCode.END_OF_FILE);
        assertThat(FileStatusCode.END_OF_FILE.isEndOfFile()).isTrue();
        // EOF is a clean reader stop, never an error: it maps to 200, not 5xx.
        assertThat(FileStatusCode.END_OF_FILE.getHttpStatus()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("fromCode(\"22\") resolves to DUPLICATE_KEY (HTTP 409)")
    void fromCodeDuplicateKey() {
        assertThat(FileStatusCode.fromCode("22")).isSameAs(FileStatusCode.DUPLICATE_KEY);
        assertThat(FileStatusCode.DUPLICATE_KEY.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("fromCode(\"23\") resolves to RECORD_NOT_FOUND (HTTP 404)")
    void fromCodeRecordNotFound() {
        assertThat(FileStatusCode.fromCode("23")).isSameAs(FileStatusCode.RECORD_NOT_FOUND);
        assertThat(FileStatusCode.RECORD_NOT_FOUND.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("fromCode(\"35\") resolves to FILE_NOT_FOUND (HTTP 404)")
    void fromCodeFileNotFound() {
        assertThat(FileStatusCode.fromCode("35")).isSameAs(FileStatusCode.FILE_NOT_FOUND);
        assertThat(FileStatusCode.FILE_NOT_FOUND.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("fromCode(\"30\") resolves to PERMANENT_IO_ERROR (HTTP 500, exact match)")
    void fromCodePermanentIoError() {
        assertThat(FileStatusCode.fromCode("30")).isSameAs(FileStatusCode.PERMANENT_IO_ERROR);
        assertThat(FileStatusCode.PERMANENT_IO_ERROR.getHttpStatus())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("fromCode(\"90\") resolves to LOGIC_ERROR via exact match (HTTP 500)")
    void fromCodeLogicErrorExactMatch() {
        assertThat(FileStatusCode.fromCode("90")).isSameAs(FileStatusCode.LOGIC_ERROR);
        assertThat(FileStatusCode.LOGIC_ERROR.getHttpStatus())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("fromCode(\"92\") falls back to LOGIC_ERROR via the '9x' class rule (HTTP 500)")
    void fromCodeLogicErrorNineClassFallback() {
        // "92" is not an exact-mapped literal, but its first character is '9',
        // so the factory maps it to the VSAM/logic error class.
        assertThat(FileStatusCode.fromCode("92")).isSameAs(FileStatusCode.LOGIC_ERROR);
        assertThat(FileStatusCode.fromCode("92").getHttpStatus())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // ---------------------------------------------------------------------
    // Phase 2 — Null / blank / unknown fallbacks
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("fromCode(null) resolves to UNKNOWN (never null)")
    void fromCodeNull() {
        assertThat(FileStatusCode.fromCode(null)).isSameAs(FileStatusCode.UNKNOWN);
    }

    @Test
    @DisplayName("fromCode of empty and blank input resolves to UNKNOWN")
    void fromCodeEmptyAndBlank() {
        assertThat(FileStatusCode.fromCode("")).isSameAs(FileStatusCode.UNKNOWN);
        assertThat(FileStatusCode.fromCode("  ")).isSameAs(FileStatusCode.UNKNOWN);
    }

    @Test
    @DisplayName("fromCode of an unmapped non-'9' code resolves to UNKNOWN")
    void fromCodeUnmappedNonNine() {
        assertThat(FileStatusCode.fromCode("07")).isSameAs(FileStatusCode.UNKNOWN);
    }

    @Test
    @DisplayName("UNKNOWN maps to HTTP 500")
    void unknownHttpStatus() {
        assertThat(FileStatusCode.UNKNOWN.getHttpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("fromCode trims input and compares on the first two characters")
    void fromCodeTrimAndTruncate() {
        // Leading/trailing whitespace is trimmed before comparison.
        assertThat(FileStatusCode.fromCode(" 23 ")).isSameAs(FileStatusCode.RECORD_NOT_FOUND);
        // Inputs longer than two characters are compared on their first two.
        assertThat(FileStatusCode.fromCode("00EXTRA")).isSameAs(FileStatusCode.SUCCESS);
    }

    // ---------------------------------------------------------------------
    // Phase 3 — The single end-of-file invariant (critical)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Exactly one constant is end-of-file, and it is END_OF_FILE")
    void exactlyOneEndOfFileConstant() {
        int eofCount = 0;
        FileStatusCode eofConstant = null;
        for (FileStatusCode status : FileStatusCode.values()) {
            if (status.isEndOfFile()) {
                eofCount++;
                eofConstant = status;
            }
        }
        // EOF ('10') is the sole normal-termination status; it is never an exception.
        assertThat(eofCount).isEqualTo(1);
        assertThat(eofConstant).isSameAs(FileStatusCode.END_OF_FILE);
        assertThat(FileStatusCode.END_OF_FILE.isEndOfFile()).isTrue();
    }

    // ---------------------------------------------------------------------
    // Phase 4 — Accessor coverage across all constants
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Every constant exposes a non-empty code, non-blank description, and non-null HttpStatus")
    void everyConstantHasCompleteMetadata() {
        for (FileStatusCode status : FileStatusCode.values()) {
            assertThat(status.getCode()).isNotNull().isNotEmpty();
            assertThat(status.getDescription()).isNotNull().isNotBlank();
            assertThat(status.getHttpStatus()).isNotNull();
        }
    }

    @Test
    @DisplayName("isSuccess() is true only for SUCCESS")
    void isSuccessOnlyForSuccess() {
        for (FileStatusCode status : FileStatusCode.values()) {
            assertThat(status.isSuccess()).isEqualTo(status == FileStatusCode.SUCCESS);
        }
    }

    @Test
    @DisplayName("isError() is false for SUCCESS and END_OF_FILE, true for every other constant")
    void isErrorSemantics() {
        for (FileStatusCode status : FileStatusCode.values()) {
            boolean expectedError =
                    status != FileStatusCode.SUCCESS && status != FileStatusCode.END_OF_FILE;
            assertThat(status.isError()).isEqualTo(expectedError);
        }
        // Explicit anchors for the two non-error and representative error constants.
        assertThat(FileStatusCode.SUCCESS.isError()).isFalse();
        assertThat(FileStatusCode.END_OF_FILE.isError()).isFalse();
        assertThat(FileStatusCode.LOGIC_ERROR.isError()).isTrue();
        assertThat(FileStatusCode.DUPLICATE_KEY.isError()).isTrue();
    }

    @Test
    @DisplayName("isNormalTermination() is true for '10' and false for '00'")
    void isNormalTermination() {
        assertThat(FileStatusCode.isNormalTermination("10")).isTrue();
        assertThat(FileStatusCode.isNormalTermination("00")).isFalse();
    }

    @Test
    @DisplayName("All documented constants resolve by name, guarding against accidental renames")
    void documentedConstantsResolveByName() {
        assertThat(FileStatusCode.valueOf("SUCCESS")).isSameAs(FileStatusCode.SUCCESS);
        assertThat(FileStatusCode.valueOf("END_OF_FILE")).isSameAs(FileStatusCode.END_OF_FILE);
        assertThat(FileStatusCode.valueOf("DUPLICATE_KEY")).isSameAs(FileStatusCode.DUPLICATE_KEY);
        assertThat(FileStatusCode.valueOf("RECORD_NOT_FOUND")).isSameAs(FileStatusCode.RECORD_NOT_FOUND);
        assertThat(FileStatusCode.valueOf("FILE_NOT_FOUND")).isSameAs(FileStatusCode.FILE_NOT_FOUND);
        assertThat(FileStatusCode.valueOf("PERMANENT_IO_ERROR")).isSameAs(FileStatusCode.PERMANENT_IO_ERROR);
        assertThat(FileStatusCode.valueOf("LOGIC_ERROR")).isSameAs(FileStatusCode.LOGIC_ERROR);
        assertThat(FileStatusCode.valueOf("UNKNOWN")).isSameAs(FileStatusCode.UNKNOWN);
    }
}
