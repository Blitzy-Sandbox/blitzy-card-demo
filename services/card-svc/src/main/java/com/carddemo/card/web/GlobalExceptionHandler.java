package com.carddemo.card.web;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/**
 * Centralized RFC&nbsp;7807 error mapping for {@code card-svc} &mdash; the one live tracer
 * service of the CardDemo walking skeleton (Spring Boot 3.5 / Java 21, Oracle 23ai).
 *
 * <p>This {@code @RestControllerAdvice} translates the exceptions thrown by the sibling
 * {@link CardController} (and the Spring/Jakarta bean-validation exceptions raised by the
 * generated {@code CardsApi} interface constraints) into
 * <strong>{@code application/problem+json}</strong> responses using Spring's built-in
 * {@link ProblemDetail}. Producing a typed problem body (rather than a body-less
 * {@link ResponseEntity#notFound()}) is deliberate: it gives the React Card Detail screen a
 * concrete payload to render for its real <em>empty</em> and <em>error</em> states.</p>
 *
 * <p><strong>Status mapping.</strong></p>
 * <ul>
 *   <li>{@link CardController.NotFoundException} &rarr; <strong>404</strong> &mdash; the UI
 *       empty state ("Did not find this card").</li>
 *   <li>{@link CardController.BadRequestException} &rarr; <strong>400</strong> &mdash; a
 *       malformed (non-16-digit) card number rejected by the controller's defensive guard.</li>
 *   <li>{@link MethodArgumentNotValidException}, {@link HandlerMethodValidationException},
 *       {@link ConstraintViolationException} &rarr; <strong>400</strong> &mdash; framework
 *       bean-validation failures (e.g. the generated {@code @Pattern("^[0-9]{16}$")} on the
 *       {@code cardNumber} path parameter for a request such as {@code GET /cards/abc}). This
 *       is the safety net that keeps such requests at 400 even when method validation fires
 *       before the controller's own guard.</li>
 *   <li>{@link Exception} (fallback) &rarr; <strong>500</strong> &mdash; the UI error state
 *       ("Error reading Card Data File"), covering any uncaught exception propagating from
 *       {@link CardController#getCardByNumber(String, java.util.UUID)} such as a data-access
 *       failure against the Oracle read. Because Spring dispatches to the MOST SPECIFIC
 *       handler, this fallback never shadows the 404/400 handlers above.</li>
 * </ul>
 *
 * <p><strong>Correlation-ID enrichment.</strong> Every problem body is enriched with the
 * correlation id read from the SLF4J {@link MDC} under the key {@code correlationId} (placed
 * there by the sibling {@code config.CorrelationIdFilter} and also printed by the
 * {@code %X{correlationId:-}} log pattern). The value is attached as the RFC&nbsp;7807
 * extension property {@code correlationId}, matching the contract's {@code Error.correlationId}
 * field, so a client can correlate the failure with the {@code X-Correlation-ID} response
 * header. This is a runtime MDC read only &mdash; there is no compile-time dependency on the
 * filter.</p>
 *
 * <p>This advice deliberately does <strong>not</strong> extend
 * {@code ResponseEntityExceptionHandler}: the application does not enable
 * {@code spring.mvc.problemdetails.enabled}, so each body is built explicitly here to
 * guarantee the {@code application/problem+json} media type and a populated
 * {@link ProblemDetail} on every mapped status, independent of framework defaults.</p>
 *
 * <p>Provenance: {@code [SRC: COCRDSLC | CARDDAT]} &mdash; app/cbl/COCRDSLC.cbl (transaction
 * {@code CCDL}, file {@code CARDDAT}). The legacy Credit Card View program's VSAM read sets
 * exactly these outcomes: {@code NOTFND} ("Did not find this account in cards database")
 * &rarr; 404; {@code OTHER} read error ("Error reading Card Data File") &rarr; 500; and
 * {@code 2220-EDIT-CARD} (non-16-digit card number) &rarr; 400. The code itself is idiomatic
 * Spring Web infrastructure, not a port of COBOL content.</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** SLF4J MDC key under which {@code CorrelationIdFilter} stores the correlation id. */
    private static final String MDC_CORRELATION_KEY = "correlationId";

    /** RFC&nbsp;7807 extension-property name carrying the correlation id in the problem body. */
    private static final String CORRELATION_PROPERTY = "correlationId";

    /**
     * Maps the tracer's empty state to <strong>404 Not Found</strong>
     * ({@code application/problem+json}).
     *
     * @param ex the not-found signal thrown by {@link CardController}; its message (when
     *           present) becomes the problem {@code detail}, otherwise a default is used
     * @return a 404 {@link ProblemDetail} response enriched with the correlation id
     */
    @ExceptionHandler(CardController.NotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(CardController.NotFoundException ex) {
        String detail = (ex.getMessage() != null) ? ex.getMessage() : "Did not find this card";
        return problem(HttpStatus.NOT_FOUND, "Not Found", detail);
    }

    /**
     * Maps a malformed card number (the controller's own domain guard) to
     * <strong>400 Bad Request</strong> ({@code application/problem+json}).
     *
     * @param ex the bad-request signal thrown by {@link CardController}; its message (when
     *           present) becomes the problem {@code detail}, otherwise a default is used
     * @return a 400 {@link ProblemDetail} response enriched with the correlation id
     */
    @ExceptionHandler(CardController.BadRequestException.class)
    public ResponseEntity<ProblemDetail> handleBadRequest(CardController.BadRequestException ex) {
        String detail = (ex.getMessage() != null) ? ex.getMessage()
                : "Card number if supplied must be a 16 digit number";
        return problem(HttpStatus.BAD_REQUEST, "Bad Request", detail);
    }

    /**
     * Maps framework bean-validation failures to <strong>400 Bad Request</strong>
     * ({@code application/problem+json}). Covers the three Spring/Jakarta validation
     * exceptions so that a constraint violation on the generated
     * {@code @Pattern("^[0-9]{16}$")} card-number path parameter (for example
     * {@code GET /cards/abc}) yields a 400 problem body instead of falling through to the
     * 500 fallback.
     *
     * @param ex the raised validation exception (common supertype {@link Exception}); the
     *           handler emits a stable, non-leaking detail rather than echoing field errors
     * @return a 400 {@link ProblemDetail} response enriched with the correlation id
     */
    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            HandlerMethodValidationException.class,
            ConstraintViolationException.class
    })
    public ResponseEntity<ProblemDetail> handleValidation(Exception ex) {
        return problem(HttpStatus.BAD_REQUEST, "Bad Request", "Request validation failed");
    }

    /**
     * Fallback handler mapping any otherwise-uncaught exception to
     * <strong>500 Internal Server Error</strong> ({@code application/problem+json}) &mdash;
     * the UI error state. This mirrors the legacy {@code OTHER} read-error branch and catches,
     * for instance, a data-access exception propagating from the Oracle tracer read. Spring's
     * most-specific-handler selection ensures this never shadows the 404/400 handlers.
     *
     * @param ex the uncaught exception (detail is intentionally fixed to avoid leaking
     *           internal error text to clients)
     * @return a 500 {@link ProblemDetail} response enriched with the correlation id
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                "Error reading Card Data File");
    }

    /**
     * Builds a normalized RFC&nbsp;7807 {@link ProblemDetail} response: it sets the status and
     * detail, applies the human-readable title, attaches the {@code correlationId} extension
     * property when a non-blank value is present in the MDC, and pins the response media type
     * to {@code application/problem+json}.
     *
     * @param status the HTTP status to report (also recorded in the problem body)
     * @param title  the short, human-readable problem title
     * @param detail the human-readable explanation for this specific occurrence
     * @return a fully-populated {@link ResponseEntity} carrying the {@link ProblemDetail} body
     */
    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        String correlationId = MDC.get(MDC_CORRELATION_KEY);
        if (correlationId != null && !correlationId.isBlank()) {
            pd.setProperty(CORRELATION_PROPERTY, correlationId);
        }
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(pd);
    }
}
