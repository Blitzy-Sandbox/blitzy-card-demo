package com.carddemo.transaction.web;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.MDC;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RFC&nbsp;7807 error endpoint for the servlet container's {@code /error} path (QA finding
 * P7-ERROR-999).
 *
 * <p><strong>Problem.</strong> Spring Boot's default {@code BasicErrorController} renders the
 * {@code /error} path through {@code DefaultErrorAttributes}. When {@code /error} is requested
 * directly (a plain {@code REQUEST} dispatch with no forwarded servlet error attributes), those
 * attributes default the body to the sentinel {@code status: 999} and {@code error: "None"} and emit
 * it as {@code application/json} &mdash; a non-RFC&nbsp;7807 body advertising a status code (999) that
 * does not exist. This controller replaces that behavior with a well-formed
 * {@link ProblemDetail} ({@code application/problem+json}) carrying a real HTTP status.</p>
 *
 * <p><strong>Back-off.</strong> Because this bean implements
 * {@link ErrorController}, Spring Boot's {@code ErrorMvcAutoConfiguration} backs off its
 * {@code BasicErrorController} (it is {@code @ConditionalOnMissingBean(ErrorController.class)}), so
 * this becomes the single handler for the error path.</p>
 *
 * <p><strong>Status resolution.</strong> The real status is taken from the
 * {@link RequestDispatcher#ERROR_STATUS_CODE} request attribute set by the container on a genuine
 * error dispatch (e.g. a propagated {@code 500}, or a {@code 404} for an unmapped path). When that
 * attribute is absent (a direct {@code GET /error}) or is not a resolvable {@link HttpStatus}, the
 * controller defaults to {@code 500 Internal Server Error} &mdash; a real status &mdash; never the
 * {@code 999} sentinel.</p>
 *
 * <p><strong>Correlation id.</strong> Consistent with the RFC&nbsp;7807 bodies produced elsewhere in
 * the CardDemo services, the correlation id is read from the SLF4J {@link MDC} under the key
 * {@code correlationId} (placed there by {@code CorrelationIdFilter} on the {@code REQUEST} dispatch)
 * and attached as the {@code correlationId} extension property when present. The response
 * {@code Content-Type} is pinned to {@code application/problem+json} so the body is always a problem
 * document regardless of the request's {@code Accept} header.</p>
 */
@RestController
public class ProblemDetailErrorController implements ErrorController {

    /** SLF4J MDC key under which {@code CorrelationIdFilter} stores the correlation id. */
    private static final String MDC_CORRELATION_KEY = "correlationId";

    /** RFC&nbsp;7807 extension-property name carrying the correlation id in the problem body. */
    private static final String CORRELATION_PROPERTY = "correlationId";

    /**
     * Handles the container error path (default {@code /error}) for every HTTP method, returning a
     * normalized RFC&nbsp;7807 problem body with a real HTTP status. The mapping expression mirrors
     * the one Spring Boot's {@code BasicErrorController} uses, so it honors a customized
     * {@code server.error.path} while defaulting to {@code /error}.
     *
     * @param request the current request; its {@link RequestDispatcher#ERROR_STATUS_CODE} attribute
     *                (when present) supplies the real status of the error being rendered
     * @return a {@link ProblemDetail} response ({@code application/problem+json}) whose status is the
     *         resolved container status, or {@code 500} for a direct/context-less {@code /error} hit
     */
    @RequestMapping("${server.error.path:${error.path:/error}}")
    public ResponseEntity<ProblemDetail> handleError(HttpServletRequest request) {
        HttpStatus status = resolveStatus(request);
        ProblemDetail body = ProblemDetail.forStatus(status);
        body.setTitle(status.getReasonPhrase());
        String correlationId = MDC.get(MDC_CORRELATION_KEY);
        if (correlationId != null && !correlationId.isBlank()) {
            body.setProperty(CORRELATION_PROPERTY, correlationId);
        }
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }

    /**
     * Resolves the HTTP status to report from the servlet {@link RequestDispatcher#ERROR_STATUS_CODE}
     * attribute, falling back to {@code 500 Internal Server Error} when the attribute is absent (a
     * direct {@code /error} request carries no forwarded status) or does not map to a known
     * {@link HttpStatus}. This is what guarantees a real status is always reported instead of the
     * {@code 999} sentinel.
     *
     * @param request the current request
     * @return the resolved {@link HttpStatus}; never {@code null}
     */
    private static HttpStatus resolveStatus(HttpServletRequest request) {
        Object statusAttribute = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (statusAttribute instanceof Integer statusCode) {
            HttpStatus resolved = HttpStatus.resolve(statusCode);
            if (resolved != null) {
                return resolved;
            }
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }
}
