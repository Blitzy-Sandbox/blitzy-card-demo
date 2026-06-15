package com.carddemo.unit.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.exception.DuplicateRecordException;
import com.carddemo.exception.FileAccessException;
import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.model.enums.FileStatus;
import com.carddemo.service.shared.FileStatusMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link FileStatusMapper}.
 *
 * <p>Traceability (REFERENCE-ONLY, COBOL not copied; source commit {@code 27d6c6f}):
 * the mapper replaces the repeated {@code IF xxx-STATUS = '00' ... ELSE PERFORM
 * 9999-ABEND-PROGRAM} I/O checks in {@code app/cbl/CBTRN02C.cbl} (and the {@code '00' OR '04'}
 * acceptable-status set from {@code app/cbl/CBSTM03A.CBL}). These tests assert the binding
 * canonical mapping (AAP section 0.8.4): {@code "00"}/{@code "04"}/{@code "10"} are non-error
 * statuses, {@code "22"} maps to {@link DuplicateRecordException}, {@code "23"} maps to
 * {@link RecordNotFoundException}, and every unmapped, {@code null}, or {@code '9x'} /
 * non-numeric code maps to the abend-path {@link FileAccessException}. The component is
 * stateless, so it is exercised directly without a Spring context.</p>
 */
@DisplayName("FileStatusMapper - COBOL FILE STATUS -> exception hierarchy / FileStatus enum")
class FileStatusMapperTest {

    private FileStatusMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new FileStatusMapper();
    }

    @Nested
    @DisplayName("predicate methods")
    class Predicates {

        @Test
        @DisplayName("isSuccess is true only for '00'")
        void isSuccess() {
            assertThat(mapper.isSuccess("00")).isTrue();
            assertThat(mapper.isSuccess("04")).isFalse();
            assertThat(mapper.isSuccess("10")).isFalse();
            assertThat(mapper.isSuccess("99")).isFalse();
            assertThat(mapper.isSuccess(null)).isFalse();
        }

        @Test
        @DisplayName("isEndOfFile is true only for '10'")
        void isEndOfFile() {
            assertThat(mapper.isEndOfFile("10")).isTrue();
            assertThat(mapper.isEndOfFile("00")).isFalse();
            assertThat(mapper.isEndOfFile(null)).isFalse();
        }

        @Test
        @DisplayName("isRecordNotFound is true only for '23'")
        void isRecordNotFound() {
            assertThat(mapper.isRecordNotFound("23")).isTrue();
            assertThat(mapper.isRecordNotFound("22")).isFalse();
            assertThat(mapper.isRecordNotFound(null)).isFalse();
        }

        @Test
        @DisplayName("isDuplicate is true only for '22'")
        void isDuplicate() {
            assertThat(mapper.isDuplicate("22")).isTrue();
            assertThat(mapper.isDuplicate("23")).isFalse();
            assertThat(mapper.isDuplicate(null)).isFalse();
        }

        @Test
        @DisplayName("isAcceptable covers '00' and '04' but not '10' or errors")
        void isAcceptable() {
            assertThat(mapper.isAcceptable("00")).isTrue();
            assertThat(mapper.isAcceptable("04")).isTrue();
            assertThat(mapper.isAcceptable("10")).isFalse();
            assertThat(mapper.isAcceptable("23")).isFalse();
            assertThat(mapper.isAcceptable("22")).isFalse();
            assertThat(mapper.isAcceptable(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("toFileStatus")
    class ToFileStatus {

        @Test
        @DisplayName("resolves each mapped code to its enum constant")
        void resolvesMappedCodes() {
            assertThat(mapper.toFileStatus("00")).isEqualTo(FileStatus.SUCCESS);
            assertThat(mapper.toFileStatus("04")).isEqualTo(FileStatus.READ_LENGTH_MISMATCH);
            assertThat(mapper.toFileStatus("10")).isEqualTo(FileStatus.END_OF_FILE);
            assertThat(mapper.toFileStatus("22")).isEqualTo(FileStatus.DUPLICATE_KEY);
            assertThat(mapper.toFileStatus("23")).isEqualTo(FileStatus.RECORD_NOT_FOUND);
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"9A", "99", "92", "ab", "0", "000"})
        @DisplayName("returns null for unmapped, null, or non-numeric codes")
        void returnsNullForUnmapped(String code) {
            assertThat(mapper.toFileStatus(code)).isNull();
        }
    }

    @Nested
    @DisplayName("toException")
    class ToException {

        @ParameterizedTest
        @ValueSource(strings = {"00", "04", "10"})
        @DisplayName("non-error statuses ('00','04','10') yield null")
        void nonErrorStatusesYieldNull(String code) {
            assertThat(mapper.toException(code, "Account", 1L)).isNull();
        }

        @Test
        @DisplayName("'23' maps to RecordNotFoundException carrying entity and key")
        void recordNotFound() {
            assertThat(mapper.toException("23", "Account", 1L))
                    .isInstanceOf(RecordNotFoundException.class)
                    .hasMessageContaining("Account")
                    .hasMessageContaining("1");
        }

        @Test
        @DisplayName("'22' maps to DuplicateRecordException carrying entity and key")
        void duplicate() {
            assertThat(mapper.toException("22", "Transaction", "TXN-9"))
                    .isInstanceOf(DuplicateRecordException.class)
                    .hasMessageContaining("Transaction")
                    .hasMessageContaining("TXN-9");
        }

        @Test
        @DisplayName("unmapped '9A' maps to FileAccessException preserving the raw code")
        void unmappedNonNumeric() {
            assertThat(mapper.toException("9A", "X", null))
                    .isInstanceOf(FileAccessException.class)
                    .hasMessageContaining("9A")
                    .hasMessageContaining("X");
            FileAccessException ex = (FileAccessException) mapper.toException("9A", "X", null);
            assertThat(ex.getFileStatus()).isEqualTo("9A");
        }

        @Test
        @DisplayName("null code maps to FileAccessException with a null retained status (no NPE)")
        void nullCode() {
            assertThat(mapper.toException(null, "X", null))
                    .isInstanceOf(FileAccessException.class);
            FileAccessException ex = (FileAccessException) mapper.toException(null, "X", null);
            assertThat(ex.getFileStatus()).isNull();
        }

        @Test
        @DisplayName("arbitrary unmapped numeric code maps to FileAccessException")
        void unmappedNumeric() {
            assertThat(mapper.toException("99", "Card", 7L))
                    .isInstanceOf(FileAccessException.class);
        }
    }

    @Nested
    @DisplayName("throwOnError")
    class ThrowOnError {

        @Test
        @DisplayName("three-arg: non-error statuses return quietly")
        void threeArgNonErrorIsQuiet() {
            assertThatCode(() -> {
                mapper.throwOnError("00", "X", 1L);
                mapper.throwOnError("04", "X", 1L);
                mapper.throwOnError("10", "X", 1L);
            }).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("three-arg: '23' throws RecordNotFoundException")
        void threeArgRecordNotFound() {
            assertThatThrownBy(() -> mapper.throwOnError("23", "Account", 1L))
                    .isInstanceOf(RecordNotFoundException.class);
        }

        @Test
        @DisplayName("three-arg: '22' throws DuplicateRecordException")
        void threeArgDuplicate() {
            assertThatThrownBy(() -> mapper.throwOnError("22", "Transaction", "k"))
                    .isInstanceOf(DuplicateRecordException.class);
        }

        @Test
        @DisplayName("three-arg: unmapped '9A' throws FileAccessException (abend path)")
        void threeArgFileAccess() {
            assertThatThrownBy(() -> mapper.throwOnError("9A", "X", null))
                    .isInstanceOf(FileAccessException.class);
        }

        @Test
        @DisplayName("two-arg: success is quiet, error throws (delegates with null key)")
        void twoArgDelegates() {
            assertThatCode(() -> mapper.throwOnError("00", "X")).doesNotThrowAnyException();
            assertThatThrownBy(() -> mapper.throwOnError("23", "Account"))
                    .isInstanceOf(RecordNotFoundException.class);
        }
    }
}
