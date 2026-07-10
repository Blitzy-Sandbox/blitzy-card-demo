package com.carddemo.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.dto.ErrorResponse;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Unit tests for {@link JsonErrorController}, the {@code ErrorController} that renders the servlet
 * {@code /error} dispatch as the shared {@code dto.ErrorResponse} JSON contract.
 *
 * <p>The tests drive {@link JsonErrorController#handleError(jakarta.servlet.http.HttpServletRequest)}
 * with a {@link MockHttpServletRequest} carrying the standard servlet error attributes, asserting the
 * resolved status, the JSON content type, and every {@link ErrorResponse} field &mdash; including the
 * status/URI defaulting and the MDC {@code correlationId} propagation.</p>
 */
@DisplayName("JsonErrorController — /error dispatch renders ErrorResponse JSON")
class JsonErrorControllerTest {

    private static final String MDC_KEY = "correlationId";

    private final JsonErrorController controller = new JsonErrorController();

    @BeforeEach
    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("404 dispatch → 404 JSON with NOT_FOUND code, echoed path, and MDC correlationId")
    void notFoundDispatch() {
        MDC.put(MDC_KEY, "corr-abc-123");
        final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 404);
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/api/cards/missing");

        final ResponseEntity<ErrorResponse> response = controller.handleError(request);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);

        final ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(404);
        assertThat(body.error()).isEqualTo("Not Found");
        assertThat(body.code()).isEqualTo("NOT_FOUND");
        assertThat(body.message()).isEqualTo("The requested resource was not found.");
        assertThat(body.path()).isEqualTo("/api/cards/missing");
        assertThat(body.correlationId()).isEqualTo("corr-abc-123");
        assertThat(body.fieldErrors()).isEmpty();
        assertThat(body.timestamp()).isNotNull();
    }

    @Test
    @DisplayName("400 dispatch → 400 JSON with BAD_REQUEST code and malformed-request message")
    void badRequestDispatch() {
        final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/error");
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 400);
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/api/transactions/bad");

        final ResponseEntity<ErrorResponse> response = controller.handleError(request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        final ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.code()).isEqualTo("BAD_REQUEST");
        assertThat(body.error()).isEqualTo("Bad Request");
        assertThat(body.message()).isEqualTo("The request could not be processed because it was malformed.");
        assertThat(body.path()).isEqualTo("/api/transactions/bad");
    }

    @Test
    @DisplayName("missing status attribute → defaults to 500 INTERNAL_SERVER_ERROR")
    void missingStatusDefaultsToServerError() {
        final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        // No ERROR_STATUS_CODE attribute set.
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/api/whatever");

        final ResponseEntity<ErrorResponse> response = controller.handleError(request);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        final ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(500);
        assertThat(body.code()).isEqualTo("INTERNAL_SERVER_ERROR");
        assertThat(body.message()).isEqualTo("An unexpected error occurred while processing the request.");
    }

    @Test
    @DisplayName("no request_uri attribute → path falls back to the current request URI")
    void pathFallsBackToRequestUri() {
        final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 404);
        // No ERROR_REQUEST_URI attribute: the controller falls back to request.getRequestURI().

        final ResponseEntity<ErrorResponse> response = controller.handleError(request);

        final ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.path()).isEqualTo("/error");
    }

    @Test
    @DisplayName("absent MDC → correlationId is null (omitted from JSON by NON_NULL)")
    void correlationIdNullWhenAbsent() {
        final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 404);

        final ResponseEntity<ErrorResponse> response = controller.handleError(request);

        final ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.correlationId()).isNull();
    }
}
