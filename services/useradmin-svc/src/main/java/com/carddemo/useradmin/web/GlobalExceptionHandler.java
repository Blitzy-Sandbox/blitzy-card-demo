package com.carddemo.useradmin.web;

import java.util.stream.Collectors;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Global web-layer exception handler for {@code useradmin-svc} (CardDemo walking skeleton).
 *
 * <p><strong>Why this exists.</strong> The generated, contract-first API interfaces are
 * annotated {@code @Validated} and carry bean-validation constraints ({@code @Pattern},
 * {@code @Size}, {@code @Min}, {@code @Max}) directly on {@code @PathVariable} /
 * {@code @RequestParam} method parameters (e.g. {@code userId} is {@code @Size(max = 8)}).
 * When such a path/query parameter is invalid, Spring raises a
 * {@link ConstraintViolationException} that is <em>not</em> handled by the framework's
 * {@code ResponseEntityExceptionHandler}, so without this advice it surfaces as an unmapped
 * HTTP&nbsp;500 &mdash; even though the frozen OpenAPI contract declares a {@code 400}
 * response (with an {@code application/problem+json} body) for invalid input. This advice
 * maps that exception to the contract-declared {@code 400 Bad Request}, served as
 * RFC&nbsp;7807 problem details.</p>
 *
 * <p><strong>Relationship to request-body validation.</strong> Request-<em>body</em>
 * validation failures ({@code MethodArgumentNotValidException}) are already handled by
 * Spring MVC and, with {@code spring.mvc.problemdetails.enabled=true} (set in
 * {@code application.yml}), are likewise rendered as {@code application/problem+json}
 * {@code 400}s. This class closes the remaining gap for path/query-parameter
 * ({@link ConstraintViolationException}) failures so that <em>all</em> validation errors
 * are consistent {@code 400} problem-detail responses, matching the contract.</p>
 *
 * <p><strong>No sensitive leakage.</strong> The response carries only the RFC&nbsp;7807
 * envelope (type/title/status/detail): the detail lists the offending parameter names and
 * their human-readable constraint messages. No stack trace, no framework exception class,
 * and no filesystem path is exposed.</p>
 *
 * <p>Provenance: infrastructure/cross-cutting class (topology only) &mdash; not dictated by
 * any legacy COBOL program; no legacy behavior is ported.</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Translates path/query-parameter constraint violations into a contract-conformant
     * {@code 400 Bad Request} rendered as RFC&nbsp;7807 {@code application/problem+json}.
     *
     * @param ex the constraint-violation exception raised by method-parameter validation
     * @return a {@code 400} {@link ProblemDetail} response describing the invalid parameters
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex) {
        final String detail = (ex.getConstraintViolations() == null || ex.getConstraintViolations().isEmpty())
                ? "One or more request parameters are invalid."
                : ex.getConstraintViolations().stream()
                        .map(GlobalExceptionHandler::formatViolation)
                        .collect(Collectors.joining("; "));
        final ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle("Bad Request");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    /**
     * Renders a single violation as {@code <parameter>: <message>}, stripping the leading
     * {@code <method>.} segment of the property path (e.g. {@code getUser.userId} →
     * {@code userId}) so the message is caller-friendly and implementation-agnostic.
     *
     * @param violation a single constraint violation
     * @return a concise, non-sensitive {@code field: message} string
     */
    private static String formatViolation(ConstraintViolation<?> violation) {
        final String path = (violation.getPropertyPath() == null) ? "" : violation.getPropertyPath().toString();
        final int lastDot = path.lastIndexOf('.');
        final String field = (lastDot >= 0 && lastDot < path.length() - 1) ? path.substring(lastDot + 1) : path;
        return (field.isEmpty() ? "parameter" : field) + ": " + violation.getMessage();
    }
}
