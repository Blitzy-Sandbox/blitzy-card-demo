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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FileStatusMapper}.
 *
 * <p>Traceability (REFERENCE-ONLY, COBOL not copied; source commit {@code 27d6c6f}):
 * the COBOL batch posting program {@code app/cbl/CBTRN02C.cbl} declares six FILE STATUS
 * fields (L29-L61) and reacts to the two-character status codes with the repeated
 * {@code IF status = '00' / '10' / else ABEND} pattern. {@code FileStatusMapper} consolidates
 * that logic: {@code '00'} success, {@code '04'} read-length mismatch, {@code '10'} end-of-file,
 * {@code '22'} duplicate key, {@code '23'} record-not-found, and every other (unmapped) code an
 * unrecoverable {@link FileAccessException}. These tests prove the exhaustive, correct mapping
 * to the {@link FileStatus} enum and the exception hierarchy (AAP sections 0.7/0.8).</p>
 */
@DisplayName("FileStatusMapper - CBTRN02C FILE STATUS to enum + exception hierarchy")
class FileStatusMapperTest {

    private FileStatusMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new FileStatusMapper();
    }

    @Test
    @DisplayName("isSuccess recognises only '00'")
    void isSuccessRecognisesOnlyZeroZero() {
        assertThat(mapper.isSuccess(FileStatus.SUCCESS.getCode())).isTrue();
        assertThat(mapper.isSuccess("00")).isTrue();
        assertThat(mapper.isSuccess("04")).isFalse();
        assertThat(mapper.isSuccess("10")).isFalse();
        assertThat(mapper.isSuccess("23")).isFalse();
        assertThat(mapper.isSuccess(null)).isFalse();
    }

    @Test
    @DisplayName("isEndOfFile recognises only '10'")
    void isEndOfFileRecognisesOnlyTen() {
        assertThat(mapper.isEndOfFile(FileStatus.END_OF_FILE.getCode())).isTrue();
        assertThat(mapper.isEndOfFile("10")).isTrue();
        assertThat(mapper.isEndOfFile("00")).isFalse();
        assertThat(mapper.isEndOfFile("23")).isFalse();
        assertThat(mapper.isEndOfFile(null)).isFalse();
    }

    @Test
    @DisplayName("isRecordNotFound recognises only '23'")
    void isRecordNotFoundRecognisesOnlyTwentyThree() {
        assertThat(mapper.isRecordNotFound(FileStatus.RECORD_NOT_FOUND.getCode())).isTrue();
        assertThat(mapper.isRecordNotFound("23")).isTrue();
        assertThat(mapper.isRecordNotFound("22")).isFalse();
        assertThat(mapper.isRecordNotFound("00")).isFalse();
        assertThat(mapper.isRecordNotFound(null)).isFalse();
    }

    @Test
    @DisplayName("isDuplicate recognises only '22'")
    void isDuplicateRecognisesOnlyTwentyTwo() {
        assertThat(mapper.isDuplicate(FileStatus.DUPLICATE_KEY.getCode())).isTrue();
        assertThat(mapper.isDuplicate("22")).isTrue();
        assertThat(mapper.isDuplicate("23")).isFalse();
        assertThat(mapper.isDuplicate("00")).isFalse();
        assertThat(mapper.isDuplicate(null)).isFalse();
    }

    @Test
    @DisplayName("isAcceptable covers '00' and '04' but not '10'/'23'")
    void isAcceptableCoversSuccessAndLengthMismatch() {
        assertThat(mapper.isAcceptable("00")).isTrue();
        assertThat(mapper.isAcceptable("04")).isTrue();
        assertThat(mapper.isAcceptable("10")).isFalse();
        assertThat(mapper.isAcceptable("23")).isFalse();
        assertThat(mapper.isAcceptable(null)).isFalse();
    }

    @Test
    @DisplayName("toFileStatus maps known codes and yields null for unmapped/null")
    void toFileStatusMapsKnownCodes() {
        assertThat(mapper.toFileStatus("00")).isEqualTo(FileStatus.SUCCESS);
        assertThat(mapper.toFileStatus("04")).isEqualTo(FileStatus.READ_LENGTH_MISMATCH);
        assertThat(mapper.toFileStatus("10")).isEqualTo(FileStatus.END_OF_FILE);
        assertThat(mapper.toFileStatus("22")).isEqualTo(FileStatus.DUPLICATE_KEY);
        assertThat(mapper.toFileStatus("23")).isEqualTo(FileStatus.RECORD_NOT_FOUND);
        assertThat(mapper.toFileStatus("9A")).isNull();
        assertThat(mapper.toFileStatus(null)).isNull();
    }

    @Test
    @DisplayName("toException returns null for success / length-mismatch / end-of-file")
    void toExceptionReturnsNullForNonErrorCodes() {
        assertThat(mapper.toException("00", "Account", 1L)).isNull();
        assertThat(mapper.toException("04", "Account", 1L)).isNull();
        assertThat(mapper.toException("10", "Account", 1L)).isNull();
    }

    @Test
    @DisplayName("toException('23') yields RecordNotFoundException with forKey message + status")
    void toExceptionMapsRecordNotFound() {
        CardDemoException ex = mapper.toException("23", "Account", 1L);
        assertThat(ex).isInstanceOf(RecordNotFoundException.class);
        assertThat(ex).hasMessage("Account not found for key: 1");
        RecordNotFoundException rnfe = (RecordNotFoundException) ex;
        assertThat(rnfe.getFileStatus()).isEqualTo(RecordNotFoundException.FILE_STATUS);
        assertThat(rnfe.getFileStatus()).isEqualTo("23");
    }

    @Test
    @DisplayName("toException('22') yields DuplicateRecordException with forKey message + status")
    void toExceptionMapsDuplicate() {
        CardDemoException ex = mapper.toException("22", "Transaction", 5L);
        assertThat(ex).isInstanceOf(DuplicateRecordException.class);
        assertThat(ex).hasMessage("Transaction already exists for key: 5");
        DuplicateRecordException dre = (DuplicateRecordException) ex;
        assertThat(dre.getFileStatus()).isEqualTo(DuplicateRecordException.FILE_STATUS);
        assertThat(dre.getFileStatus()).isEqualTo("22");
    }

    @Test
    @DisplayName("toException(unmapped) yields FileAccessException carrying the raw code")
    void toExceptionMapsUnmappedToFileAccess() {
        CardDemoException ex = mapper.toException("9A", "Xref", 7L);
        assertThat(ex).isInstanceOf(FileAccessException.class);
        assertThat(ex).hasMessage("Unrecoverable file access error for Xref (FILE STATUS=9A)");
        FileAccessException fae = (FileAccessException) ex;
        assertThat(fae.getFileStatus()).isEqualTo("9A");
    }

    @Test
    @DisplayName("toException(null code) yields FileAccessException with null file status")
    void toExceptionMapsNullCodeToFileAccess() {
        CardDemoException ex = mapper.toException(null, "Xref", 7L);
        assertThat(ex).isInstanceOf(FileAccessException.class);
        assertThat(ex).hasMessage("Unrecoverable file access error for Xref (FILE STATUS=null)");
        FileAccessException fae = (FileAccessException) ex;
        assertThat(fae.getFileStatus()).isNull();
    }

    @Test
    @DisplayName("throwOnError stays silent for acceptable / non-error codes")
    void throwOnErrorSilentForNonErrors() {
        assertThatCode(() -> mapper.throwOnError("00", "Account", 1L)).doesNotThrowAnyException();
        assertThatCode(() -> mapper.throwOnError("04", "Account", 1L)).doesNotThrowAnyException();
        assertThatCode(() -> mapper.throwOnError("10", "Account", 1L)).doesNotThrowAnyException();
        assertThatCode(() -> mapper.throwOnError("00", "Account")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("throwOnError('23') throws RecordNotFoundException")
    void throwOnErrorThrowsRecordNotFound() {
        assertThatThrownBy(() -> mapper.throwOnError("23", "Account", 1L))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage("Account not found for key: 1");
    }

    @Test
    @DisplayName("throwOnError('22') throws DuplicateRecordException")
    void throwOnErrorThrowsDuplicate() {
        assertThatThrownBy(() -> mapper.throwOnError("22", "Transaction", 5L))
                .isInstanceOf(DuplicateRecordException.class)
                .hasMessage("Transaction already exists for key: 5");
    }

    @Test
    @DisplayName("throwOnError(unmapped) throws FileAccessException")
    void throwOnErrorThrowsFileAccessForUnmapped() {
        assertThatThrownBy(() -> mapper.throwOnError("9A", "Xref", 7L))
                .isInstanceOf(FileAccessException.class)
                .hasMessage("Unrecoverable file access error for Xref (FILE STATUS=9A)");
    }

    @Test
    @DisplayName("two-arg throwOnError delegates with a null key")
    void throwOnErrorTwoArgDelegatesWithNullKey() {
        assertThatThrownBy(() -> mapper.throwOnError("23", "Account"))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage("Account not found for key: null");
    }
}
