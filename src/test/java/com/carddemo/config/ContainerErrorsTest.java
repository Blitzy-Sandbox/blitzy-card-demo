package com.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Unit tests for {@link ContainerErrors}, the single source of truth for the machine-readable
 * {@code code} and the leak-free {@code message} of a container / servlet-tier error.
 *
 * <p>Both container-error renderers ({@code JsonErrorReportValve} in the Tomcat pipeline and
 * {@code controller.JsonErrorController} on the servlet {@code /error} dispatch) delegate here, so
 * pinning this mapping guarantees their {@code dto.ErrorResponse} bodies stay byte-identical for a
 * given status and that no message ever incorporates request-derived data.</p>
 */
@DisplayName("ContainerErrors — container/servlet error code & message mapping")
class ContainerErrorsTest {

    @Nested
    @DisplayName("codeFor(...)")
    class CodeFor {

        @Test
        @DisplayName("a resolved status yields its SCREAMING_SNAKE_CASE name")
        void resolvedStatusYieldsName() {
            assertThat(ContainerErrors.codeFor(400, HttpStatus.BAD_REQUEST)).isEqualTo("BAD_REQUEST");
            assertThat(ContainerErrors.codeFor(404, HttpStatus.NOT_FOUND)).isEqualTo("NOT_FOUND");
            assertThat(ContainerErrors.codeFor(405, HttpStatus.METHOD_NOT_ALLOWED))
                    .isEqualTo("METHOD_NOT_ALLOWED");
            assertThat(ContainerErrors.codeFor(500, HttpStatus.INTERNAL_SERVER_ERROR))
                    .isEqualTo("INTERNAL_SERVER_ERROR");
        }

        @Test
        @DisplayName("a non-standard status (null HttpStatus) yields a stable HTTP_<code> token")
        void unknownStatusYieldsHttpToken() {
            assertThat(ContainerErrors.codeFor(499, null)).isEqualTo("HTTP_499");
            assertThat(ContainerErrors.codeFor(0, null)).isEqualTo("HTTP_0");
        }
    }

    @Nested
    @DisplayName("messageFor(...)")
    class MessageFor {

        @Test
        @DisplayName("null status yields the generic message")
        void nullStatusYieldsGeneric() {
            assertThat(ContainerErrors.messageFor(null)).isEqualTo("The request could not be processed.");
        }

        @Test
        @DisplayName("400 explains a malformed request (covers connector-level URI rejections)")
        void badRequestMessage() {
            assertThat(ContainerErrors.messageFor(HttpStatus.BAD_REQUEST))
                    .isEqualTo("The request could not be processed because it was malformed.");
        }

        @Test
        @DisplayName("404 / 405 / 406 / 415 each map to their specific, leak-free message")
        void specificClientMessages() {
            assertThat(ContainerErrors.messageFor(HttpStatus.NOT_FOUND))
                    .isEqualTo("The requested resource was not found.");
            assertThat(ContainerErrors.messageFor(HttpStatus.METHOD_NOT_ALLOWED))
                    .isEqualTo("The request method is not supported for this resource.");
            assertThat(ContainerErrors.messageFor(HttpStatus.NOT_ACCEPTABLE))
                    .isEqualTo("The requested representation is not available.");
            assertThat(ContainerErrors.messageFor(HttpStatus.UNSUPPORTED_MEDIA_TYPE))
                    .isEqualTo("The request media type is not supported.");
        }

        @Test
        @DisplayName("any other 4xx falls back to the generic client message")
        void otherClientErrorsAreGeneric() {
            assertThat(ContainerErrors.messageFor(HttpStatus.FORBIDDEN))
                    .isEqualTo("The request could not be processed.");
            assertThat(ContainerErrors.messageFor(HttpStatus.I_AM_A_TEAPOT))
                    .isEqualTo("The request could not be processed.");
        }

        @Test
        @DisplayName("any 5xx maps to the generic server-error message")
        void serverErrorsAreGeneric() {
            assertThat(ContainerErrors.messageFor(HttpStatus.INTERNAL_SERVER_ERROR))
                    .isEqualTo("An unexpected error occurred while processing the request.");
            assertThat(ContainerErrors.messageFor(HttpStatus.SERVICE_UNAVAILABLE))
                    .isEqualTo("An unexpected error occurred while processing the request.");
        }

        @Test
        @DisplayName("messages never leak: no message contains a path/exception placeholder")
        void messagesAreLeakFree() {
            for (final HttpStatus status : HttpStatus.values()) {
                final String message = ContainerErrors.messageFor(status);
                assertThat(message).isNotNull();
                assertThat(message).doesNotContain("/", "%", "Exception", "\n");
            }
        }
    }
}
