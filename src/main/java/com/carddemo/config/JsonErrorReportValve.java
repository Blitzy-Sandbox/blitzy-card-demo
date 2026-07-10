package com.carddemo.config;

import com.carddemo.dto.ErrorResponse;
import com.carddemo.observability.CorrelationIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ErrorReportValve;
import org.apache.coyote.ActionCode;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/**
 * Tomcat {@link ErrorReportValve} replacement that renders <em>container-level</em> error responses
 * as the application's {@code dto.ErrorResponse} JSON instead of Tomcat's stock HTML error page.
 *
 * <p><strong>Why a valve and not a servlet {@code ErrorController} alone.</strong> Some requests are
 * rejected by Tomcat during connector/URI parsing &mdash; for example a percent-encoded path
 * separator ({@code ..%2F..}) or an embedded null byte ({@code %00}) &mdash; <em>before</em> the
 * request ever enters the servlet container's filter chain or {@code DispatcherServlet}. For those
 * rejections Tomcat sets a 400 status and hands the response to its pipeline
 * {@code ErrorReportValve}, which writes an HTML body ({@code text/html}); the request is never
 * dispatched to {@code /error}, so no {@code jakarta.servlet}-tier component (a global
 * {@code @RestControllerAdvice}, an {@code ErrorController}, or a servlet {@code Filter}) can
 * intercept it. Overriding the valve is the only place these bodyless, container-generated error
 * responses can be reshaped into the JSON contract the rest of the API speaks (QA finding: malformed
 * / encoded-path requests must return {@code ErrorResponse} JSON, never Tomcat HTML).</p>
 *
 * <p><strong>Scope of effect.</strong> The overridden {@link #report(Request, Response, Throwable)}
 * only acts on an error response (status &ge; 400) that has <em>no body written yet</em> and has not
 * already been reported &mdash; exactly the guard the stock valve uses. Any error that reached the
 * servlet tier and was rendered as JSON by {@code GlobalExceptionHandler}, by the security
 * {@code AuthenticationEntryPoint}/{@code AccessDeniedHandler}, or by {@code JsonErrorController}
 * has already written its body ({@code getContentWritten() > 0}), so this valve is a no-op for it.
 * In practice the valve therefore fires only for the connector-level rejections that would otherwise
 * leak HTML.</p>
 *
 * <p><strong>Contract fidelity.</strong> The emitted body is a real {@link ErrorResponse} serialized
 * with the application's Jackson {@link ObjectMapper}, so its field order, ISO-8601 timestamp
 * rendering and {@code NON_NULL} omission are byte-identical to every other error the API returns.
 * The {@code correlationId} is read from the SLF4J MDC; for a connector-level rejection the
 * {@link CorrelationIdFilter} never ran, so it is normally absent and is simply omitted from the
 * payload. The {@code message} is a fixed, leak-free string per status class &mdash; it never echoes
 * the offending raw request target &mdash; and the reflected {@code path}, when Tomcat exposes one,
 * is safe because the response is {@code application/json} (Jackson escapes control characters and
 * browsers do not execute JSON), matching how {@code GlobalExceptionHandler} reflects the request
 * URI.</p>
 *
 * <p>Design rationale is recorded in {@code docs/decision-log.md} (Explainability rule), not in code
 * comments; the class is package-private and instantiated only by
 * {@code WebConfig#errorReportValveCustomizer(ObjectMapper)}.</p>
 *
 * @see WebConfig
 * @see ErrorResponse
 * @see JsonErrorController
 */
final class JsonErrorReportValve extends ErrorReportValve {

    /** Application Jackson serializer (Boot-configured: {@code JavaTimeModule}, ISO dates, {@code NON_NULL}). */
    private final ObjectMapper objectMapper;

    /**
     * Creates the valve.
     *
     * @param objectMapper the application's configured Jackson {@link ObjectMapper} used to render
     *                     the {@link ErrorResponse} body (never {@code null})
     */
    JsonErrorReportValve(final ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Renders the current error response as {@link ErrorResponse} JSON, replacing Tomcat's default
     * HTML error page.
     *
     * <p>The guard conditions mirror the stock {@link ErrorReportValve#report(Request, Response,
     * Throwable)}: nothing is written for a non-error status ({@literal <} 400), when a body has
     * already been produced ({@link Response#getContentWritten()} &gt; 0), when the error has already
     * been reported ({@link Response#setErrorReported()} returns {@code false}), or when the
     * connector can no longer perform I/O. When all guards pass, a JSON body is written and the
     * response is finished.</p>
     *
     * @param request   the Tomcat request being reported on (never {@code null})
     * @param response  the Tomcat response to write the JSON error body to (never {@code null})
     * @param throwable the associated error, if any (unused; the body is intentionally generic and
     *                  never leaks exception detail)
     */
    @Override
    protected void report(final Request request, final Response response, final Throwable throwable) {
        final int statusCode = response.getStatus();

        // Only reshape genuine, not-yet-written, not-yet-reported error responses. setErrorReported()
        // atomically claims the single report opportunity, exactly as the stock valve does.
        if (statusCode < 400 || response.getContentWritten() > 0 || !response.setErrorReported()) {
            return;
        }

        // If the connector can no longer perform I/O there is no point producing a body no one can read.
        final AtomicBoolean ioAllowed = new AtomicBoolean(false);
        response.getCoyoteResponse().action(ActionCode.IS_IO_ALLOWED, ioAllowed);
        if (!ioAllowed.get()) {
            return;
        }

        final String body = buildBody(statusCode, safeRequestUri(request));

        try {
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            final PrintWriter writer = response.getReporter();
            if (writer != null) {
                writer.write(body);
                response.finishResponse();
            }
        } catch (final IOException | IllegalStateException ignored) {
            // Best-effort: the client disconnected or the response is already committed. There is
            // nothing safe to do here and nothing to log without a request context, so swallow it
            // exactly as the stock ErrorReportValve does for the same failure modes.
        }
    }

    /**
     * Builds the JSON {@link ErrorResponse} body for a container-level error.
     *
     * @param statusCode the HTTP status of the error response
     * @param path       the (safe) request URI to reflect, or {@code null} if Tomcat exposed none
     * @return the serialized JSON body, or a minimal hand-built fallback if serialization fails
     */
    private String buildBody(final int statusCode, final String path) {
        final HttpStatus status = HttpStatus.resolve(statusCode);
        final String reasonPhrase = (status != null) ? status.getReasonPhrase() : "";
        final String code = ContainerErrors.codeFor(statusCode, status);
        final String message = ContainerErrors.messageFor(status);
        final String correlationId = MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY);

        final ErrorResponse error = new ErrorResponse(
                OffsetDateTime.now(), statusCode, reasonPhrase, code, message, path, correlationId, List.of());
        try {
            return objectMapper.writeValueAsString(error);
        } catch (final RuntimeException | com.fasterxml.jackson.core.JsonProcessingException serializationFailure) {
            // Extremely defensive: the ErrorResponse record is trivially serializable, but never let a
            // serialization fault degrade a valve back to an empty/HTML body. Emit a minimal, valid,
            // static JSON envelope (no dynamic values, so it needs no escaping).
            return "{\"status\":" + statusCode + ",\"error\":\"" + reasonPhrase + "\",\"code\":\"" + code + "\"}";
        }
    }

    /**
     * Returns the request URI if Tomcat exposed one for the (possibly malformed) request, otherwise
     * {@code null}. The value is reflected verbatim into the JSON {@code path}; it is safe because the
     * response is {@code application/json} and Jackson escapes any control characters.
     *
     * @param request the Tomcat request (never {@code null})
     * @return the request URI, or {@code null} when unavailable
     */
    private static String safeRequestUri(final Request request) {
        try {
            return request.getRequestURI();
        } catch (final RuntimeException unavailable) {
            return null;
        }
    }
}
