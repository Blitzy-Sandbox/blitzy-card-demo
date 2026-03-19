package com.cardemo.unit.model;

import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.ConcurrentModificationException;
import com.cardemo.exception.CreditLimitExceededException;
import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.exception.ExpiredCardException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comprehensive unit tests for the entire CardDemo exception hierarchy.
 * Exercises all constructors, getters, constants, and inheritance chains
 * for the seven exception classes mapped from COBOL FILE STATUS codes
 * and business validation rules.
 */
class ExceptionHierarchyTest {

    // ══════════════════════════════════════════════════════════════════════
    // CardDemoException (base class)
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("CardDemoException — base exception")
    class CardDemoExceptionTests {

        @Test
        @DisplayName("Single-arg constructor sets message and defaults")
        void testMessageConstructor() {
            CardDemoException ex = new CardDemoException("Something went wrong");
            assertThat(ex.getMessage()).isEqualTo("Something went wrong");
            assertThat(ex.getErrorCode()).isEqualTo(CardDemoException.DEFAULT_ERROR_CODE);
            assertThat(ex.getFileStatusCode()).isNull();
            assertThat(ex.getCause()).isNull();
        }

        @Test
        @DisplayName("Message + cause constructor")
        void testMessageCauseConstructor() {
            RuntimeException cause = new RuntimeException("root cause");
            CardDemoException ex = new CardDemoException("Wrapped", cause);
            assertThat(ex.getMessage()).isEqualTo("Wrapped");
            assertThat(ex.getCause()).isSameAs(cause);
            assertThat(ex.getErrorCode()).isEqualTo(CardDemoException.DEFAULT_ERROR_CODE);
        }

        @Test
        @DisplayName("Message + errorCode constructor")
        void testMessageErrorCodeConstructor() {
            CardDemoException ex = new CardDemoException("Bad data", "MYCODE");
            assertThat(ex.getMessage()).isEqualTo("Bad data");
            assertThat(ex.getErrorCode()).isEqualTo("MYCODE");
            assertThat(ex.getFileStatusCode()).isNull();
        }

        @Test
        @DisplayName("Message + errorCode + fileStatusCode constructor")
        void testThreeArgConstructor() {
            CardDemoException ex = new CardDemoException("IO error", "IO_ERR", "35");
            assertThat(ex.getMessage()).isEqualTo("IO error");
            assertThat(ex.getErrorCode()).isEqualTo("IO_ERR");
            assertThat(ex.getFileStatusCode()).isEqualTo("35");
        }

        @Test
        @DisplayName("Full constructor with cause")
        void testFullConstructor() {
            RuntimeException cause = new RuntimeException("disk full");
            CardDemoException ex = new CardDemoException("Write failed", "WR_FAIL", "48", cause);
            assertThat(ex.getMessage()).isEqualTo("Write failed");
            assertThat(ex.getErrorCode()).isEqualTo("WR_FAIL");
            assertThat(ex.getFileStatusCode()).isEqualTo("48");
            assertThat(ex.getCause()).isSameAs(cause);
        }

        @Test
        @DisplayName("DEFAULT_ERROR_CODE constant is set")
        void testDefaultErrorCode() {
            assertThat(CardDemoException.DEFAULT_ERROR_CODE).isEqualTo("CARDDEMO_ERROR");
        }

        @Test
        @DisplayName("Extends RuntimeException")
        void testInheritance() {
            CardDemoException ex = new CardDemoException("test");
            assertThat(ex).isInstanceOf(RuntimeException.class);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // ExpiredCardException (REJECT_CODE = 103)
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ExpiredCardException — reject code 103")
    class ExpiredCardExceptionTests {

        @Test
        @DisplayName("REJECT_CODE constant is 103")
        void testRejectCode() {
            assertThat(ExpiredCardException.REJECT_CODE).isEqualTo(103);
        }

        @Test
        @DisplayName("Single-arg constructor sets message")
        void testMessageConstructor() {
            ExpiredCardException ex = new ExpiredCardException("Card expired");
            assertThat(ex.getMessage()).isEqualTo("Card expired");
            assertThat(ex.getCardNumber()).isNull();
            assertThat(ex.getAccountId()).isNull();
            assertThat(ex.getExpirationDate()).isNull();
            assertThat(ex.getTransactionDate()).isNull();
        }

        @Test
        @DisplayName("Full constructor with card number, account, dates")
        void testFullConstructor() {
            LocalDate expDate = LocalDate.of(2024, 12, 31);
            LocalDate txnDate = LocalDate.of(2025, 1, 15);
            ExpiredCardException ex = new ExpiredCardException(
                    "4000123456789012", "ACCT001", expDate, txnDate
            );
            assertThat(ex.getCardNumber()).isEqualTo("4000123456789012");
            assertThat(ex.getAccountId()).isEqualTo("ACCT001");
            assertThat(ex.getExpirationDate()).isEqualTo(expDate);
            assertThat(ex.getTransactionDate()).isEqualTo(txnDate);
            assertThat(ex.getMessage()).contains("4000123456789012");
        }

        @Test
        @DisplayName("Four-arg constructor with card number, account, expDate, txnDate")
        void testFourArgConstructor() {
            LocalDate expDate = LocalDate.of(2024, 6, 30);
            LocalDate txnDate = LocalDate.of(2025, 1, 15);
            ExpiredCardException ex = new ExpiredCardException(
                    "4111111111111111", "ACCT999", expDate, txnDate
            );
            assertThat(ex.getCardNumber()).isEqualTo("4111111111111111");
            assertThat(ex.getAccountId()).isEqualTo("ACCT999");
            assertThat(ex.getExpirationDate()).isEqualTo(expDate);
            assertThat(ex.getTransactionDate()).isEqualTo(txnDate);
        }

        @Test
        @DisplayName("Extends CardDemoException")
        void testInheritance() {
            ExpiredCardException ex = new ExpiredCardException("test");
            assertThat(ex).isInstanceOf(CardDemoException.class);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // CreditLimitExceededException (REJECT_CODE = 102)
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("CreditLimitExceededException — reject code 102")
    class CreditLimitExceededExceptionTests {

        @Test
        @DisplayName("REJECT_CODE constant is 102")
        void testRejectCode() {
            assertThat(CreditLimitExceededException.REJECT_CODE).isEqualTo(102);
        }

        @Test
        @DisplayName("Single-arg constructor sets message")
        void testMessageConstructor() {
            CreditLimitExceededException ex = new CreditLimitExceededException("Limit exceeded");
            assertThat(ex.getMessage()).isEqualTo("Limit exceeded");
            assertThat(ex.getAccountId()).isNull();
            assertThat(ex.getTransactionAmount()).isNull();
            assertThat(ex.getCreditLimit()).isNull();
            assertThat(ex.getCurrentBalance()).isNull();
        }

        @Test
        @DisplayName("Full constructor with account, amounts")
        void testFullConstructor() {
            BigDecimal txnAmt = new BigDecimal("500.00");
            BigDecimal limit = new BigDecimal("1000.00");
            BigDecimal balance = new BigDecimal("800.00");
            CreditLimitExceededException ex = new CreditLimitExceededException(
                    "ACCT001", txnAmt, limit, balance
            );
            assertThat(ex.getAccountId()).isEqualTo("ACCT001");
            assertThat(ex.getTransactionAmount()).isEqualByComparingTo(txnAmt);
            assertThat(ex.getCreditLimit()).isEqualByComparingTo(limit);
            assertThat(ex.getCurrentBalance()).isEqualByComparingTo(balance);
            assertThat(ex.getMessage()).contains("ACCT001");
        }

        @Test
        @DisplayName("Extends CardDemoException")
        void testInheritance() {
            CreditLimitExceededException ex = new CreditLimitExceededException("test");
            assertThat(ex).isInstanceOf(CardDemoException.class);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // ConcurrentModificationException
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ConcurrentModificationException — optimistic lock violation")
    class ConcurrentModificationExceptionTests {

        @Test
        @DisplayName("Single-arg constructor sets message")
        void testMessageConstructor() {
            ConcurrentModificationException ex = new ConcurrentModificationException("Conflict");
            assertThat(ex.getMessage()).isEqualTo("Conflict");
            assertThat(ex.getEntityName()).isNull();
            assertThat(ex.getEntityId()).isNull();
        }

        @Test
        @DisplayName("Entity name + ID constructor")
        void testEntityConstructor() {
            ConcurrentModificationException ex = new ConcurrentModificationException("Account", "ACCT001");
            assertThat(ex.getEntityName()).isEqualTo("Account");
            assertThat(ex.getEntityId()).isEqualTo("ACCT001");
            assertThat(ex.getMessage()).contains("Account");
        }

        @Test
        @DisplayName("Message + cause constructor")
        void testMessageCauseConstructor() {
            RuntimeException cause = new RuntimeException("version mismatch");
            ConcurrentModificationException ex = new ConcurrentModificationException("Stale", cause);
            assertThat(ex.getMessage()).isEqualTo("Stale");
            assertThat(ex.getCause()).isSameAs(cause);
        }

        @Test
        @DisplayName("Extends CardDemoException")
        void testInheritance() {
            ConcurrentModificationException ex = new ConcurrentModificationException("test");
            assertThat(ex).isInstanceOf(CardDemoException.class);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // DuplicateRecordException (FILE STATUS 22)
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("DuplicateRecordException — FILE STATUS 22")
    class DuplicateRecordExceptionTests {

        @Test
        @DisplayName("Constants are set correctly")
        void testConstants() {
            assertThat(DuplicateRecordException.ERROR_CODE).isEqualTo("DUP");
            assertThat(DuplicateRecordException.FILE_STATUS_CODE).isEqualTo("22");
        }

        @Test
        @DisplayName("Single-arg constructor sets message")
        void testMessageConstructor() {
            DuplicateRecordException ex = new DuplicateRecordException("Already exists");
            assertThat(ex.getMessage()).isEqualTo("Already exists");
            assertThat(ex.getEntityName()).isNull();
            assertThat(ex.getDuplicateId()).isNull();
        }

        @Test
        @DisplayName("Entity name + ID constructor")
        void testEntityConstructor() {
            DuplicateRecordException ex = new DuplicateRecordException("UserSecurity", "USR001");
            assertThat(ex.getEntityName()).isEqualTo("UserSecurity");
            assertThat(ex.getDuplicateId()).isEqualTo("USR001");
            assertThat(ex.getMessage()).contains("UserSecurity");
        }

        @Test
        @DisplayName("Message + cause constructor")
        void testMessageCauseConstructor() {
            RuntimeException cause = new RuntimeException("constraint violation");
            DuplicateRecordException ex = new DuplicateRecordException("Dup key", cause);
            assertThat(ex.getMessage()).isEqualTo("Dup key");
            assertThat(ex.getCause()).isSameAs(cause);
        }

        @Test
        @DisplayName("Extends CardDemoException")
        void testInheritance() {
            DuplicateRecordException ex = new DuplicateRecordException("test");
            assertThat(ex).isInstanceOf(CardDemoException.class);
            assertThat(ex.getErrorCode()).isEqualTo("DUP");
            assertThat(ex.getFileStatusCode()).isEqualTo("22");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // RecordNotFoundException (FILE STATUS 23)
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("RecordNotFoundException — FILE STATUS 23")
    class RecordNotFoundExceptionTests {

        @Test
        @DisplayName("Single-arg constructor sets message")
        void testMessageConstructor() {
            RecordNotFoundException ex = new RecordNotFoundException("Not found");
            assertThat(ex.getMessage()).isEqualTo("Not found");
            assertThat(ex.getEntityName()).isNull();
            assertThat(ex.getEntityId()).isNull();
        }

        @Test
        @DisplayName("Entity name + ID constructor")
        void testEntityConstructor() {
            RecordNotFoundException ex = new RecordNotFoundException("Account", "ACCT999");
            assertThat(ex.getEntityName()).isEqualTo("Account");
            assertThat(ex.getEntityId()).isEqualTo("ACCT999");
            assertThat(ex.getMessage()).contains("Account");
            assertThat(ex.getMessage()).contains("ACCT999");
        }

        @Test
        @DisplayName("Message + cause constructor")
        void testMessageCauseConstructor() {
            RuntimeException cause = new RuntimeException("no rows");
            RecordNotFoundException ex = new RecordNotFoundException("Missing", cause);
            assertThat(ex.getMessage()).isEqualTo("Missing");
            assertThat(ex.getCause()).isSameAs(cause);
        }

        @Test
        @DisplayName("Extends CardDemoException with RNF error code and FS 23")
        void testInheritance() {
            RecordNotFoundException ex = new RecordNotFoundException("Account", "A1");
            assertThat(ex).isInstanceOf(CardDemoException.class);
            assertThat(ex.getErrorCode()).isEqualTo("RNF");
            assertThat(ex.getFileStatusCode()).isEqualTo("23");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // ValidationException
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ValidationException — field-level validation errors")
    class ValidationExceptionTests {

        @Test
        @DisplayName("VALIDATION_ERROR_CODE constant")
        void testConstant() {
            assertThat(ValidationException.VALIDATION_ERROR_CODE).isEqualTo("VALID");
        }

        @Test
        @DisplayName("Single-arg constructor sets message")
        void testMessageConstructor() {
            ValidationException ex = new ValidationException("Invalid input");
            assertThat(ex.getMessage()).isEqualTo("Invalid input");
            assertThat(ex.getFieldErrors()).isEmpty();
        }

        @Test
        @DisplayName("Message + fieldErrors constructor")
        void testMessageFieldErrorsConstructor() {
            List<ValidationException.FieldError> errors = List.of(
                    new ValidationException.FieldError("stateCode", "XX", "Invalid state"),
                    new ValidationException.FieldError("zipCode", "00000", "Invalid ZIP")
            );
            ValidationException ex = new ValidationException("Validation failed", errors);
            assertThat(ex.getMessage()).isEqualTo("Validation failed");
            assertThat(ex.getFieldErrors()).hasSize(2);
            assertThat(ex.getFieldErrors().get(0).fieldName()).isEqualTo("stateCode");
            assertThat(ex.getFieldErrors().get(0).rejectedValue()).isEqualTo("XX");
            assertThat(ex.getFieldErrors().get(0).message()).isEqualTo("Invalid state");
            assertThat(ex.getFieldErrors().get(1).fieldName()).isEqualTo("zipCode");
        }

        @Test
        @DisplayName("FieldErrors-only constructor builds summary message")
        void testFieldErrorsOnlyConstructor() {
            List<ValidationException.FieldError> errors = List.of(
                    new ValidationException.FieldError("amount", "-5", "Must be positive")
            );
            ValidationException ex = new ValidationException(errors);
            assertThat(ex.getMessage()).isNotBlank();
            assertThat(ex.getFieldErrors()).hasSize(1);
        }

        @Test
        @DisplayName("Static of() factory creates single-field exception")
        void testOfFactory() {
            ValidationException ex = ValidationException.of("email", "bad@", "Invalid format");
            assertThat(ex.getFieldErrors()).hasSize(1);
            assertThat(ex.getFieldErrors().get(0).fieldName()).isEqualTo("email");
            assertThat(ex.getFieldErrors().get(0).rejectedValue()).isEqualTo("bad@");
            assertThat(ex.getFieldErrors().get(0).message()).isEqualTo("Invalid format");
        }

        @Test
        @DisplayName("FieldError record accessors work correctly")
        void testFieldErrorRecord() {
            ValidationException.FieldError fe = new ValidationException.FieldError("f1", "v1", "m1");
            assertThat(fe.fieldName()).isEqualTo("f1");
            assertThat(fe.rejectedValue()).isEqualTo("v1");
            assertThat(fe.message()).isEqualTo("m1");
            assertThat(fe.toString()).contains("f1");
        }

        @Test
        @DisplayName("Extends CardDemoException")
        void testInheritance() {
            ValidationException ex = new ValidationException("test");
            assertThat(ex).isInstanceOf(CardDemoException.class);
        }
    }
}
