package com.carddemo.controller;

import com.carddemo.exception.CardDemoException;
import com.carddemo.exception.ConcurrencyException;
import com.carddemo.exception.DuplicateRecordException;
import com.carddemo.exception.FileAccessException;
import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.TransactionPostingException;
import com.carddemo.exception.ValidationException;
import java.util.stream.Collectors;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Centralized translation of CardDemo domain exceptions and Spring MVC binding
 * errors into RFC 7807 ProblemDetail HTTP responses. Re-platforms the COBOL
 * FILE STATUS / ABEND handling surfaced by the CICS and batch programs
 * (reference only; lineage commit 27d6c6f). Registered at highest precedence so
 * domain-specific mappings win over framework defaults.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler {

    @ExceptionHandler(RecordNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleRecordNotFound(RecordNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "Record Not Found", ex.getMessage());
    }

    @ExceptionHandler(DuplicateRecordException.class)
    public ResponseEntity<ProblemDetail> handleDuplicateRecord(DuplicateRecordException ex) {
        return build(HttpStatus.CONFLICT, "Duplicate Record", ex.getMessage());
    }

    @ExceptionHandler(ConcurrencyException.class)
    public ResponseEntity<ProblemDetail> handleConcurrency(ConcurrencyException ex) {
        return build(HttpStatus.CONFLICT, "Concurrent Update Conflict", ex.getMessage());
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ProblemDetail> handleValidation(ValidationException ex) {
        return build(HttpStatus.BAD_REQUEST, "Validation Error", ex.getMessage());
    }

    @ExceptionHandler(TransactionPostingException.class)
    public ResponseEntity<ProblemDetail> handleTransactionPosting(TransactionPostingException ex) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "Transaction Posting Rejected", ex.getMessage());
    }

    @ExceptionHandler(FileAccessException.class)
    public ResponseEntity<ProblemDetail> handleFileAccess(FileAccessException ex) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "File Access Error", ex.getMessage());
    }

    @ExceptionHandler(CardDemoException.class)
    public ResponseEntity<ProblemDetail> handleCardDemo(CardDemoException ex) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Processing Error", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        if (detail.isEmpty()) {
            detail = "Request validation failed";
        }
        return build(HttpStatus.BAD_REQUEST, "Validation Error", detail);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String detail = "Parameter '" + ex.getName() + "' has an invalid value";
        return build(HttpStatus.BAD_REQUEST, "Invalid Request Parameter", detail);
    }

    private ResponseEntity<ProblemDetail> build(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail == null ? "" : detail);
        problem.setTitle(title);
        return ResponseEntity.status(status).body(problem);
    }
}
