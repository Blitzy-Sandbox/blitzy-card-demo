package com.cardemo.unit.shared;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.service.shared.FileStatusMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure-JVM behavioural-parity unit test for {@link FileStatusMapper}, the single typed translation
 * point that replaces the scattered COBOL two-byte {@code FILE STATUS} comparisons of
 * {@code app/cbl/CBTRN02C.cbl} (frozen baseline commit SHA {@code 27d6c6f}).
 *
 * <p>The mapper realizes the {@code FILE STATUS} code &rarr; typed exception &rarr; HTTP-status
 * contract (AAP &sect;0.7.5, tech-spec L1063), preserving the legacy behaviour exactly while
 * changing only the mechanism (Minimal Change Clause, &sect;0.7.1):</p>
 * <ul>
 *   <li>{@code "00"} (APPL-AOK) and {@code "10"} (APPL-EOF) &rarr; normal flow, no throw;</li>
 *   <li>{@code "23"} (INVALID KEY) &rarr; {@link RecordNotFoundException} (advice &rarr; 404);</li>
 *   <li>{@code "22"} (duplicate key on WRITE) &rarr; {@link DuplicateRecordException} (advice &rarr; 409);</li>
 *   <li>{@code "35"} (dataset not available) and any unmapped/{@code null} code &rarr;
 *       {@link IllegalStateException} mirroring {@code 9999-ABEND-PROGRAM} (advice &rarr; 500).</li>
 * </ul>
 *
 * <p>This is a fast, isolated unit test: {@link FileStatusMapper} depends only on the JDK, the
 * {@code FileStatus} enum and the {@code com.cardemo.exception} hierarchy, so there is no Spring
 * context, database or Testcontainers. The COBOL source is read-only reference and never copied
 * (AAP &sect;0.7.2); only its behaviour is asserted.</p>
 */
class FileStatusMapperTest {

    /** The system under test. Stateless, immutable, thread-safe; a single instance suffices. */
    private final FileStatusMapper mapper = new FileStatusMapper();

    @Nested
    @DisplayName("isSuccess(String) — IF *-STATUS = '00'")
    class IsSuccess {

        @Test
        @DisplayName("'00' is the only success code")
        void onlyDoubleZeroIsSuccess() {
            assertThat(mapper.isSuccess("00")).isTrue();
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"10", "22", "23", "35", "99", "0", ""})
        @DisplayName("every non-'00' (and null) code is not success")
        void otherCodesAreNotSuccess(String code) {
            assertThat(mapper.isSuccess(code)).isFalse();
        }
    }

    @Nested
    @DisplayName("isEndOfFile(String) — IF DALYTRAN-STATUS = '10'")
    class IsEndOfFile {

        @Test
        @DisplayName("'10' is the only end-of-file code")
        void onlyTenIsEof() {
            assertThat(mapper.isEndOfFile("10")).isTrue();
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"00", "22", "23", "35", "99", "1", ""})
        @DisplayName("every non-'10' (and null) code is not end-of-file")
        void otherCodesAreNotEof(String code) {
            assertThat(mapper.isEndOfFile(code)).isFalse();
        }
    }

    @Nested
    @DisplayName("verify(code, entityType, key) — status translation with diagnostic context")
    class VerifyWithContext {

        @Test
        @DisplayName("'00' (success) and '10' (EOF) return normally — neither is an error")
        void successAndEofDoNotThrow() {
            assertThatCode(() -> mapper.verify("00", "Account", "1")).doesNotThrowAnyException();
            assertThatCode(() -> mapper.verify("10", "Transaction", "1")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("'23' throws RecordNotFoundException carrying entityType + key")
        void recordNotFoundPropagatesContext() {
            assertThatThrownBy(() -> mapper.verify("23", "Account", "123"))
                    .isInstanceOfSatisfying(RecordNotFoundException.class, ex -> {
                        assertThat(ex.getMessage()).isEqualTo("Account not found for key: 123");
                        assertThat(ex.getEntityType()).isEqualTo("Account");
                        assertThat(ex.getKey()).isEqualTo("123");
                    });
        }

        @Test
        @DisplayName("'22' throws DuplicateRecordException carrying entityType + key")
        void duplicateRecordPropagatesContext() {
            assertThatThrownBy(() -> mapper.verify("22", "Transaction", "TX1"))
                    .isInstanceOfSatisfying(DuplicateRecordException.class, ex -> {
                        assertThat(ex.getMessage()).isEqualTo("Transaction already exists for key: TX1");
                        assertThat(ex.getEntityType()).isEqualTo("Transaction");
                        assertThat(ex.getKey()).isEqualTo("TX1");
                    });
        }

        @Test
        @DisplayName("'35' (file not available) abends as IllegalStateException with no cause")
        void fileNotFoundAbends() {
            assertThatThrownBy(() -> mapper.verify("35", "Account", "1"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("'35'")
                    .hasMessageContaining("File not found")
                    .hasMessageContaining("mirrors COBOL 9999-ABEND-PROGRAM")
                    .hasNoCause();
        }

        @ParameterizedTest
        @ValueSource(strings = {"99", "0", "ZZ"})
        @DisplayName("an unrecognised code abends as IllegalStateException wrapping IllegalArgumentException")
        void unknownCodeAbendsWithCause(String unknown) {
            assertThatThrownBy(() -> mapper.verify(unknown, "Account", "1"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("mirrors COBOL 9999-ABEND-PROGRAM")
                    .hasCauseInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a null code abends as IllegalStateException wrapping IllegalArgumentException")
        void nullCodeAbendsWithCause() {
            assertThatThrownBy(() -> mapper.verify(null, "Account", "1"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("mirrors COBOL 9999-ABEND-PROGRAM")
                    .hasCauseInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("verify(code) — no-context overload uses description-based messages")
    class VerifyNoContext {

        @Test
        @DisplayName("'00' returns normally")
        void successDoesNotThrow() {
            assertThatCode(() -> mapper.verify("00")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("'23' throws RecordNotFoundException with a non-sensitive description message")
        void recordNotFoundMessageOnly() {
            assertThatThrownBy(() -> mapper.verify("23"))
                    .isInstanceOfSatisfying(RecordNotFoundException.class, ex -> {
                        assertThat(ex.getMessage()).isEqualTo("Record not found (file status '23')");
                        assertThat(ex.getEntityType()).isNull();
                        assertThat(ex.getKey()).isNull();
                    });
        }

        @Test
        @DisplayName("'22' throws DuplicateRecordException with a non-sensitive description message")
        void duplicateRecordMessageOnly() {
            assertThatThrownBy(() -> mapper.verify("22"))
                    .isInstanceOfSatisfying(DuplicateRecordException.class, ex -> {
                        assertThat(ex.getMessage()).isEqualTo("Duplicate key (file status '22')");
                        assertThat(ex.getEntityType()).isNull();
                        assertThat(ex.getKey()).isNull();
                    });
        }
    }
}
