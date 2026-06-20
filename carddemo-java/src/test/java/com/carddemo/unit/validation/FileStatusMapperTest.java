package com.carddemo.unit.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.exception.CardDemoException;
import com.carddemo.exception.DuplicateRecordException;
import com.carddemo.exception.FileAccessException;
import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.model.enums.FileStatus;
import com.carddemo.service.shared.FileStatusMapper;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FileStatusMapper}, verifying the binding COBOL {@code FILE STATUS}
 * to exception / {@link FileStatus} mapping derived from {@code CBTRN02C} (source commit
 * {@code 27d6c6f}). Each test corresponds to a row of the canonical mapping table.
 */
class FileStatusMapperTest {

    private final FileStatusMapper mapper = new FileStatusMapper();

    @Test
    void isSuccessOnlyForCode00() {
        assertThat(mapper.isSuccess("00")).isTrue();
        assertThat(mapper.isSuccess("04")).isFalse();
        assertThat(mapper.isSuccess("10")).isFalse();
        assertThat(mapper.isSuccess("23")).isFalse();
        assertThat(mapper.isSuccess(null)).isFalse();
    }

    @Test
    void isEndOfFileOnlyForCode10() {
        assertThat(mapper.isEndOfFile("10")).isTrue();
        assertThat(mapper.isEndOfFile("00")).isFalse();
        assertThat(mapper.isEndOfFile(null)).isFalse();
    }

    @Test
    void isRecordNotFoundOnlyForCode23() {
        assertThat(mapper.isRecordNotFound("23")).isTrue();
        assertThat(mapper.isRecordNotFound("22")).isFalse();
        assertThat(mapper.isRecordNotFound(null)).isFalse();
    }

    @Test
    void isDuplicateOnlyForCode22() {
        assertThat(mapper.isDuplicate("22")).isTrue();
        assertThat(mapper.isDuplicate("23")).isFalse();
        assertThat(mapper.isDuplicate(null)).isFalse();
    }

    @Test
    void isAcceptableForSuccessAndLengthMismatchButNotEndOfFile() {
        assertThat(mapper.isAcceptable("00")).isTrue();
        assertThat(mapper.isAcceptable("04")).isTrue();
        assertThat(mapper.isAcceptable("10")).isFalse();
        assertThat(mapper.isAcceptable("23")).isFalse();
        assertThat(mapper.isAcceptable(null)).isFalse();
    }

    @Test
    void toFileStatusResolvesKnownCodesAndNullForUnmapped() {
        assertThat(mapper.toFileStatus("00")).isEqualTo(FileStatus.SUCCESS);
        assertThat(mapper.toFileStatus("04")).isEqualTo(FileStatus.READ_LENGTH_MISMATCH);
        assertThat(mapper.toFileStatus("10")).isEqualTo(FileStatus.END_OF_FILE);
        assertThat(mapper.toFileStatus("22")).isEqualTo(FileStatus.DUPLICATE_KEY);
        assertThat(mapper.toFileStatus("23")).isEqualTo(FileStatus.RECORD_NOT_FOUND);
        assertThat(mapper.toFileStatus("9A")).isNull();
        assertThat(mapper.toFileStatus(null)).isNull();
    }

    @Test
    void toExceptionReturnsNullForNonErrorStatuses() {
        assertThat(mapper.toException("00", "Account", 1L)).isNull();
        assertThat(mapper.toException("04", "Account", 1L)).isNull();
        assertThat(mapper.toException("10", "Account", 1L)).isNull();
    }

    @Test
    void toExceptionMapsRecordNotFoundForCode23() {
        CardDemoException ex = mapper.toException("23", "Account", 1L);
        assertThat(ex).isInstanceOf(RecordNotFoundException.class);
        assertThat(ex).hasMessageContaining("Account");
    }

    @Test
    void toExceptionMapsDuplicateForCode22() {
        CardDemoException ex = mapper.toException("22", "Transaction", 99L);
        assertThat(ex).isInstanceOf(DuplicateRecordException.class);
        assertThat(ex).hasMessageContaining("Transaction");
    }

    @Test
    void toExceptionMapsFileAccessForUnmappedCodePreservingRawStatus() {
        CardDemoException ex = mapper.toException("9A", "X", null);
        assertThat(ex).isInstanceOf(FileAccessException.class);
        assertThat(ex).hasMessageContaining("9A");
        assertThat(((FileAccessException) ex).getFileStatus()).isEqualTo("9A");
    }

    @Test
    void toExceptionMapsFileAccessForNullCode() {
        CardDemoException ex = mapper.toException(null, "X", null);
        assertThat(ex).isInstanceOf(FileAccessException.class);
        assertThat(((FileAccessException) ex).getFileStatus()).isNull();
    }

    @Test
    void throwOnErrorIsQuietForNonErrorStatuses() {
        assertThatCode(() -> mapper.throwOnError("00", "X")).doesNotThrowAnyException();
        assertThatCode(() -> mapper.throwOnError("04", "X", 1L)).doesNotThrowAnyException();
        assertThatCode(() -> mapper.throwOnError("10", "X")).doesNotThrowAnyException();
    }

    @Test
    void throwOnErrorThrowsRecordNotFoundForCode23() {
        assertThatThrownBy(() -> mapper.throwOnError("23", "Account", 1L))
                .isInstanceOf(RecordNotFoundException.class);
    }

    @Test
    void throwOnErrorThrowsDuplicateForCode22() {
        assertThatThrownBy(() -> mapper.throwOnError("22", "Transaction", 5L))
                .isInstanceOf(DuplicateRecordException.class);
    }

    @Test
    void throwOnErrorThrowsFileAccessForUnmappedCode() {
        assertThatThrownBy(() -> mapper.throwOnError("ZZ", "X"))
                .isInstanceOf(FileAccessException.class);
    }

    @Test
    void throwOnErrorTwoArgDelegatesAndStillThrows() {
        assertThatThrownBy(() -> mapper.throwOnError("23", "Account"))
                .isInstanceOf(RecordNotFoundException.class);
    }
}
