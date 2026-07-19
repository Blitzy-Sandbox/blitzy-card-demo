package com.carddemo.card.web;

import java.util.Set;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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
 *   <li>{@link HttpRequestMethodNotSupportedException} &rarr; <strong>405</strong> (with an
 *       {@code Allow} header), {@link HttpMediaTypeNotSupportedException} &rarr;
 *       <strong>415</strong>, {@link HttpMediaTypeNotAcceptableException} &rarr;
 *       <strong>406</strong>, {@link NoResourceFoundException}/{@link NoHandlerFoundException}
 *       &rarr; <strong>404</strong>, and {@link MethodArgumentTypeMismatchException} /
 *       {@link HttpMessageNotReadableException} &rarr; <strong>400</strong> &mdash; the
 *       standard Spring MVC client-error conditions (unsupported method, unsupported /
 *       unacceptable media type, unmapped path, malformed parameter or request body).
 *       Handling these explicitly (a) returns the correct 4xx status with an accurate
 *       {@code application/problem+json} body instead of a misleading 500, and (b) keeps the
 *       tolerant correlation-ID design intact: because {@code X-Correlation-ID} is bound as a
 *       plain {@code String}, a non-canonical value no longer triggers a binding type
 *       mismatch, and the {@code config.CorrelationIdFilter} remains the sole owner of
 *       correlation-ID validation and minting.</li>
 *   <li>{@link Exception} (fallback) &rarr; <strong>500</strong> &mdash; the UI error state
 *       ("Error reading Card Data File"), covering any uncaught exception propagating from
 *       {@link CardController#getCardByNumber(String, String)} such as a data-access
 *       failure against the Oracle read. This branch is logged at {@code ERROR} with the
 *       stack trace (the specific 4xx branches above are logged at {@code WARN}), so a
 *       genuine server fault is diagnosable from the logs rather than silent. Because Spring
 *       dispatches to the MOST SPECIFIC handler, this fallback never shadows the
 *       404/400/405/406/415 handlers above.</li>
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
 * {@link ProblemDetail} on every mapped status, independent of framework defaults. For the
 * same reason, the standard Spring MVC client-error exceptions (405/415/406/404/400) are
 * mapped with dedicated {@code @ExceptionHandler} methods below rather than inherited from
 * {@code ResponseEntityExceptionHandler} (whose defaults would otherwise emit body-less or
 * non-{@code problem+json} responses); each explicit handler routes through the shared
 * {@link #buildProblem(HttpStatus, String, String)} helper so the correlation id and media
 * type are attached uniformly.</p>
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
     * SLF4J logger. Client-error branches (4xx) log at {@code WARN} (message only, no stack
     * trace &mdash; these are expected, client-caused conditions); the {@code Exception}
     * fallback (500) logs at {@code ERROR} with the full stack trace so a genuine server
     * fault is diagnosable from the logs rather than silent. Every log line is prefixed by
     * the {@code %X{correlationId:-}} MDC value via the {@code application.yml} log pattern.
     */
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

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
        log.warn("Card not found (tracer empty state): {}", detail);
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
        log.warn("Rejecting malformed card number: {}", detail);
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
        log.warn("Request validation failed: {}", ex.getMessage());
        return problem(HttpStatus.BAD_REQUEST, "Bad Request", "Request validation failed");
    }

    /**
     * Maps an unsupported HTTP method to <strong>405 Method Not Allowed</strong>
     * ({@code application/problem+json}) and sets the {@code Allow} response header to the
     * methods the matched handler actually supports. Covers, for example, a
     * {@code POST}/{@code DELETE}/{@code PATCH} against {@code /cards/{cardNumber}} (which
     * supports only {@code GET} and {@code PUT}). Without this handler the request would fall
     * through to the 500 fallback.
     *
     * @param ex the framework exception carrying the offending method and the supported set
     * @return a 405 {@link ProblemDetail} response with an {@code Allow} header and the
     *         correlation id
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        log.warn("Rejecting unsupported HTTP method: {}", ex.getMessage());
        ProblemDetail pd = buildProblem(HttpStatus.METHOD_NOT_ALLOWED, "Method Not Allowed",
                "Request method '" + ex.getMethod() + "' is not supported for this resource");
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON);
        Set<HttpMethod> supported = ex.getSupportedHttpMethods();
        if (supported != null && !supported.isEmpty()) {
            builder.allow(supported.toArray(new HttpMethod[0]));
        }
        return builder.body(pd);
    }

    /**
     * Maps an unsupported request {@code Content-Type} to <strong>415 Unsupported Media
     * Type</strong> ({@code application/problem+json}). Covers, for example, a {@code PUT}
     * with {@code Content-Type: text/plain} where the operation consumes
     * {@code application/json}.
     *
     * @param ex the framework exception (its content-type detail is intentionally not echoed
     *           into the body to avoid reflecting arbitrary client input)
     * @return a 415 {@link ProblemDetail} response enriched with the correlation id
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        log.warn("Rejecting unsupported media type: {}", ex.getMessage());
        return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported Media Type",
                "The request Content-Type is not supported");
    }

    /**
     * Maps a request whose {@code Accept} header cannot be satisfied to <strong>406 Not
     * Acceptable</strong> ({@code application/problem+json}). Covers, for example, a
     * {@code GET} with {@code Accept: application/xml} against a JSON-only resource. The
     * problem body itself is written as {@code application/problem+json} regardless of the
     * inbound {@code Accept} header: because the response {@code Content-Type} is set
     * explicitly (concrete), Spring's message-converter selection uses it directly and does
     * not re-run {@code Accept} negotiation on the error body (so this handler cannot recurse).
     *
     * @param ex the framework exception raised when no producible representation matches
     * @return a 406 {@link ProblemDetail} response enriched with the correlation id
     */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ProblemDetail> handleMediaTypeNotAcceptable(HttpMediaTypeNotAcceptableException ex) {
        log.warn("No acceptable representation for request: {}", ex.getMessage());
        return problem(HttpStatus.NOT_ACCEPTABLE, "Not Acceptable",
                "No acceptable representation is available for the requested Accept header");
    }

    /**
     * Maps an unmapped path (no matching handler or static resource) to <strong>404 Not
     * Found</strong> ({@code application/problem+json}). Covers, for example,
     * {@code GET /health} (the real probe is {@code /actuator/health}) or any
     * {@code /nonexistent} path. This is distinct from {@link CardController.NotFoundException}
     * (an existing route whose card is absent): the detail here is the generic
     * "Resource not found" rather than the card empty-state message.
     *
     * @param ex the framework not-found signal ({@link NoResourceFoundException} on Spring
     *           6.1+/Boot 3.2+, or {@link NoHandlerFoundException} when so configured)
     * @return a 404 {@link ProblemDetail} response enriched with the correlation id
     */
    @ExceptionHandler({ NoResourceFoundException.class, NoHandlerFoundException.class })
    public ResponseEntity<ProblemDetail> handleNoResource(Exception ex) {
        log.warn("No handler for request path: {}", ex.getMessage());
        return problem(HttpStatus.NOT_FOUND, "Not Found", "Resource not found");
    }

    /**
     * Maps a request-parameter type mismatch to <strong>400 Bad Request</strong>
     * ({@code application/problem+json}) &mdash; for example a non-integer {@code page} or
     * {@code size} query parameter on {@code GET /cards}. This is the optional hardening
     * anticipated by the design intent: a client-supplied value that cannot be bound to the
     * declared parameter type is a client error (400), never a server fault (500).
     *
     * @param ex the framework type-mismatch exception (the offending parameter name is
     *           logged but not echoed into the body)
     * @return a 400 {@link ProblemDetail} response enriched with the correlation id
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.warn("Request parameter type mismatch on parameter '{}'", ex.getName());
        return problem(HttpStatus.BAD_REQUEST, "Bad Request", "Request parameter type mismatch");
    }

    /**
     * Maps an unreadable or malformed request body to <strong>400 Bad Request</strong>
     * ({@code application/problem+json}). Covers, for example, syntactically invalid JSON or
     * a body whose {@code activeStatus} carries a value outside the {@code Y}/{@code N} enum
     * (rejected during JSON deserialization, before bean validation). A malformed body is a
     * client error (400), never a server fault (500).
     *
     * @param ex the framework message-not-readable exception (its parser detail is
     *           intentionally not echoed into the body)
     * @return a 400 {@link ProblemDetail} response enriched with the correlation id
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleNotReadable(HttpMessageNotReadableException ex) {
        log.warn("Malformed or unreadable request body: {}", ex.getMessage());
        return problem(HttpStatus.BAD_REQUEST, "Bad Request", "Malformed request body");
    }

    /**
     * Fallback handler mapping any otherwise-uncaught exception to
     * <strong>500 Internal Server Error</strong> ({@code application/problem+json}) &mdash;
     * the UI error state. This mirrors the legacy {@code OTHER} read-error branch and catches,
     * for instance, a data-access exception propagating from the Oracle tracer read. The
     * exception is logged at {@code ERROR} with its stack trace so a genuine server fault is
     * diagnosable from the logs (the specific 4xx handlers above log at {@code WARN}). Spring's
     * most-specific-handler selection ensures this never shadows the 404/400/405/406/415
     * handlers above.
     *
     * @param ex the uncaught exception (detail is intentionally fixed to avoid leaking
     *           internal error text to clients; the full cause is logged, not returned)
     * @return a 500 {@link ProblemDetail} response enriched with the correlation id
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unhandled exception mapped to 500 Internal Server Error", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                "Error reading Card Data File");
    }

    /**
     * Builds a normalized RFC&nbsp;7807 {@link ProblemDetail}: it records the status and
     * detail, applies the human-readable title, and attaches the {@code correlationId}
     * extension property when a non-blank value is present in the MDC. This is the single
     * point where the correlation id is woven into the body, shared by {@link #problem} and
     * by the 405 handler (which additionally sets an {@code Allow} header on the response).
     *
     * @param status the HTTP status to report (also recorded in the problem body)
     * @param title  the short, human-readable problem title
     * @param detail the human-readable explanation for this specific occurrence
     * @return a populated {@link ProblemDetail} (without the surrounding {@link ResponseEntity})
     */
    private ProblemDetail buildProblem(HttpStatus status, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        String correlationId = MDC.get(MDC_CORRELATION_KEY);
        if (correlationId != null && !correlationId.isBlank()) {
            pd.setProperty(CORRELATION_PROPERTY, correlationId);
        }
        return pd;
    }

    /**
     * Wraps {@link #buildProblem} in a {@link ResponseEntity} with the given status and the
     * {@code application/problem+json} media type pinned explicitly. Pinning a concrete
     * response {@code Content-Type} also guarantees the body is written as
     * {@code problem+json} even for the 406 (not-acceptable) case, since Spring uses a preset
     * concrete content type directly instead of re-running {@code Accept} negotiation.
     *
     * @param status the HTTP status to report (also recorded in the problem body)
     * @param title  the short, human-readable problem title
     * @param detail the human-readable explanation for this specific occurrence
     * @return a fully-populated {@link ResponseEntity} carrying the {@link ProblemDetail} body
     */
    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String title, String detail) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(buildProblem(status, title, detail));
    }
}
