package com.carddemo.controller;

import com.carddemo.config.ContainerErrors;
import com.carddemo.dto.ErrorResponse;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Replacement for Spring Boot's default {@code BasicErrorController} that renders every
 * servlet-dispatched error at {@code /error} as the application's {@code dto.ErrorResponse} JSON.
 *
 * <p>The migrated service is a headless JSON API, so an error must never be answered with the
 * whitelabel HTML page or with Boot's default error map (which lacks the {@code code},
 * {@code correlationId} and {@code fieldErrors} fields of the shared contract). Declaring a bean that
 * implements {@link ErrorController} makes Boot back off its {@code BasicErrorController}
 * ({@code @ConditionalOnMissingBean(ErrorController.class)}) while keeping the container error-page
 * dispatch to {@code /error}; this controller then produces the identical {@link ErrorResponse}
 * shape used by {@code GlobalExceptionHandler} and the security filter chain.</p>
 *
 * <p><strong>Which errors arrive here.</strong> The servlet container forwards to {@code /error} for
 * failures that occur at or below the {@code DispatcherServlet} but are not converted by the global
 * {@code @RestControllerAdvice} &mdash; for example a request the container itself flags with an
 * error status after the URI was normalized. Connector-level rejections that never enter the servlet
 * pipeline (percent-encoded path separators, null bytes) are instead handled by
 * {@code config.JsonErrorReportValve}; the two renderers share {@link ContainerErrors} so their
 * bodies are byte-identical for the same status. This endpoint is reachable on the internal
 * {@code ERROR} dispatch without authentication because {@code config.SecurityConfig} permits the
 * {@code ERROR} dispatcher type, so the caller receives the true error status (for example 404)
 * rather than a masking 401.</p>
 *
 * <p><strong>Security.</strong> The body carries only a fixed, status-derived message from
 * {@link ContainerErrors}; it never echoes the exception, message attribute, or any other
 * request-derived text. The {@code path} reflects the original error request URI (safe as JSON), and
 * the {@code correlationId} is taken from the MDC when present. Rationale is recorded in
 * {@code docs/decision-log.md} (Explainability rule).</p>
 *
 * @see ContainerErrors
 * @see ErrorResponse
 * @see com.carddemo.config.JsonErrorReportValve
 */
@RestController
public class JsonErrorController implements ErrorController {

    /**
     * MDC key holding the per-request correlation id. Kept as a literal (rather than importing
     * {@code observability.CorrelationIdFilter.CORRELATION_ID_MDC_KEY}) to match the convention used
     * by {@code GlobalExceptionHandler} in this package; it must remain exactly {@code "correlationId"}
     * to stay aligned with the correlation-id filter and {@code logback-spring.xml}.
     */
    private static final String CORRELATION_ID_MDC_KEY = "correlationId";

    /**
     * Handles the servlet {@code ERROR} dispatch for every HTTP method and renders the shared JSON
     * error contract. The status is taken from the standard
     * {@link RequestDispatcher#ERROR_STATUS_CODE} attribute (defaulting to 500 if absent) and the
     * reflected {@code path} from {@link RequestDispatcher#ERROR_REQUEST_URI} (falling back to the
     * current request URI).
     *
     * @param request the current (error-dispatched) servlet request (never {@code null})
     * @return a {@link ResponseEntity} carrying the resolved status and an {@link ErrorResponse} body
     */
    @RequestMapping("/error")
    public ResponseEntity<ErrorResponse> handleError(final HttpServletRequest request) {
        final int statusCode = resolveStatusCode(request);
        final HttpStatus resolved = HttpStatus.resolve(statusCode);
        final String reasonPhrase = (resolved != null) ? resolved.getReasonPhrase() : "";
        final String code = ContainerErrors.codeFor(statusCode, resolved);
        final String message = ContainerErrors.messageFor(resolved);
        final String path = resolvePath(request);

        final ErrorResponse body = new ErrorResponse(
                OffsetDateTime.now(),
                statusCode,
                reasonPhrase,
                code,
                message,
                path,
                MDC.get(CORRELATION_ID_MDC_KEY),
                List.of());

        return ResponseEntity.status(statusCode)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    /**
     * Resolves the HTTP status of the error dispatch from the servlet error attribute, defaulting to
     * {@link HttpStatus#INTERNAL_SERVER_ERROR} when the container did not supply one.
     *
     * @param request the error-dispatched request (never {@code null})
     * @return the resolved HTTP status code
     */
    private static int resolveStatusCode(final HttpServletRequest request) {
        final Object attribute = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (attribute instanceof Integer statusCode && statusCode >= 400) {
            return statusCode;
        }
        return HttpStatus.INTERNAL_SERVER_ERROR.value();
    }

    /**
     * Resolves the request URI that originally errored, preferring the standard servlet error
     * attribute and falling back to the current request URI.
     *
     * @param request the error-dispatched request (never {@code null})
     * @return the original request URI, or the current one if the attribute is absent
     */
    private static String resolvePath(final HttpServletRequest request) {
        final Object attribute = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        return (attribute instanceof String uri) ? uri : request.getRequestURI();
    }
}
