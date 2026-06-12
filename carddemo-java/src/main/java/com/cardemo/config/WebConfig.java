package com.cardemo.config;

import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializationFeature;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.ConcurrentModificationException;
import com.cardemo.exception.CreditLimitExceededException;
import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.exception.ExpiredCardException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;

/**
 * Web-layer {@code @Configuration} for the greenfield Java&nbsp;25 LTS + Spring Boot 3.5.11
 * migration of the AWS CardDemo COBOL/CICS/VSAM/JCL/BMS mainframe application.
 *
 * <h2>Provenance &mdash; net-new cross-cutting web infrastructure (no single COBOL source)</h2>
 * <p>This class has <strong>no single COBOL source equivalent</strong>: it is foundational
 * technology-substitution infrastructure that establishes the HTTP concerns shared by all eight
 * migrated REST controllers (Auth, Account, Card, Transaction, Billing, Report, UserAdmin, Menu).
 * It is component-scanned by {@code CardDemoApplication} under the base package {@code com.cardemo}
 * (decision <strong>D-006</strong>). Per the <strong>Minimal Change Clause</strong> (AAP &sect;0.7.1)
 * it adds only what the migration requires &mdash; CORS, Jackson serialization tuning, and global
 * error handling &mdash; and introduces no speculative interceptors, filters or formatters.
 * Traceability to the frozen legacy baseline is by original COBOL repository commit SHA
 * {@code 27d6c6f}; the COBOL source is <em>never copied</em> into this repository
 * (AAP &sect;0.7.2 &mdash; Preservation Requirements). Authoritative blueprint:
 * {@code docs/technical-specifications.md} target tree L342
 * ({@code WebConfig.java (CORS, serialization, error handling)}).</p>
 *
 * <h2>Responsibility 1 &mdash; CORS</h2>
 * <p>{@link #addCorsMappings(CorsRegistry)} exposes the {@code /api/**} surface to browser clients.
 * Allowed origins are <strong>property-driven</strong> ({@code carddemo.web.cors.allowed-origins},
 * default {@code "*"} for local development) so that no production origin is committed to source
 * (AAP &sect;0.7.2 &mdash; no hardcoded secrets/origins). Credentials are intentionally left
 * disabled so the permissive wildcard default remains valid under the CORS specification (a wildcard
 * origin combined with {@code allowCredentials(true)} is forbidden).</p>
 *
 * <h2>Responsibility 2 &mdash; Jackson decimal &amp; date fidelity (&sect;0.7.3)</h2>
 * <p>{@link #jacksonCustomizer()} tunes the auto-configured {@code ObjectMapper} so the migrated
 * REST contracts reproduce the exact COBOL {@code PIC}/decimal and date field shapes &mdash; this is
 * a <em>runtime correctness guarantee</em>, not cosmetics:</p>
 * <ul>
 *   <li><strong>{@code WRITE_BIGDECIMAL_AS_PLAIN} enabled</strong> &mdash; every {@code BigDecimal}
 *       (the mandated mapping for every COBOL {@code COMP-3}/{@code COMP}/{@code PIC S9(n)V99} field,
 *       &sect;0.7.3) serializes in plain decimal notation, never in scientific/exponential notation
 *       (for example {@code 1234567.89}, never {@code 1.23456789E6}). Decimals are <em>never</em>
 *       converted to {@code float}/{@code double} anywhere in the migration.</li>
 *   <li><strong>{@code WRITE_DATES_AS_TIMESTAMPS} disabled</strong> &mdash; {@code java.time} types
 *       ({@code LocalDate}/{@code LocalDateTime}, the {@code CEEDAYS}&rarr;{@code java.time}
 *       substitution, transformation rule L90 / tech-spec L1063) serialize as ISO-8601 strings,
 *       never as numeric epoch timestamps.</li>
 * </ul>
 * <p>The {@code JavaTimeModule} that backs ISO-8601 {@code java.time} rendering ships transitively
 * with {@code spring-boot-starter-web} (via {@code spring-boot-starter-json} &rarr;
 * {@code jackson-datatype-jsr310}) and is <strong>auto-registered by Spring Boot</strong>; this
 * customizer therefore only flips the two serialization features above and deliberately does
 * <em>not</em> call {@code modulesToInstall(Module...)}, which would suppress Boot's autodetection of
 * the other well-known modules (Jdk8, ParameterNames) that DTO/record (de)serialization relies on.</p>
 *
 * <h2>Responsibility 3 &mdash; global error handling (&sect;0.7.5, tech-spec L1063)</h2>
 * <p>The nested {@link GlobalExceptionHandler} ({@code @RestControllerAdvice}) is the single,
 * centralized realization of the COBOL <em>{@code FILE STATUS} code &rarr; typed exception &rarr;
 * HTTP response</em> mapping. It is the modern equivalent of every legacy program formatting its own
 * error text into working storage and the 3270 screen. Per the AAP this advice lives <em>inside</em>
 * {@code WebConfig} as a nested type; there is intentionally <strong>no</strong> standalone
 * {@code GlobalExceptionHandler.java}.</p>
 *
 * @see WebMvcConfigurer
 * @see GlobalExceptionHandler
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * Browser pre-flight cache duration, in seconds, advertised on CORS responses for the
     * {@code /api/**} surface. One hour is a conventional, conservative default.
     */
    private static final long CORS_MAX_AGE_SECONDS = 3600L;

    /**
     * Origins permitted to call the {@code /api/**} surface, bound from the
     * {@code carddemo.web.cors.allowed-origins} property.
     *
     * <p>The value is a comma-separated list (Spring splits it into this array). The default
     * {@code "*"} is permissive for local development; production deployments MUST override the
     * property with an explicit allow-list. Because {@code allowCredentials(true)} is never set (see
     * {@link #addCorsMappings(CorsRegistry)}), the wildcard default is valid under the CORS
     * specification.</p>
     */
    private final String[] corsAllowedOrigins;

    /**
     * Creates the web configuration, binding the CORS allow-list from external configuration.
     *
     * @param corsAllowedOrigins the origins permitted to call {@code /api/**}, taken from the
     *                           {@code carddemo.web.cors.allowed-origins} property (default
     *                           {@code "*"} for local development); a defensive copy is stored
     */
    public WebConfig(
            @Value("${carddemo.web.cors.allowed-origins:*}") String[] corsAllowedOrigins) {
        // Defensive copy: never retain a reference to a caller-supplied mutable array.
        this.corsAllowedOrigins = corsAllowedOrigins.clone();
    }

    /**
     * Registers the CORS policy for the migrated REST surface.
     *
     * <p>Maps {@code /api/**} (the route prefix shared by all eight controllers), permitting the
     * standard CRUD methods plus {@code OPTIONS} pre-flight, all request headers, and the
     * property-driven {@linkplain #corsAllowedOrigins origin allow-list}. {@code allowCredentials} is
     * intentionally left at its default ({@code false}) so the permissive wildcard origin default
     * stays specification-valid; enabling credentials would require replacing the wildcard with an
     * explicit origin list.</p>
     *
     * @param registry the Spring MVC CORS registry to configure
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(corsAllowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(CORS_MAX_AGE_SECONDS);
    }

    /**
     * Tunes the Spring Boot auto-configured Jackson {@code ObjectMapper} for decimal and date
     * fidelity (&sect;0.7.3, external-interface fidelity &sect;0.7.2).
     *
     * <p>Spring Boot applies every {@link Jackson2ObjectMapperBuilderCustomizer} bean to the builder
     * that produces the application-wide {@code ObjectMapper}, so these two settings govern the JSON
     * shape of every REST response and request body:</p>
     * <ul>
     *   <li>{@code JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN} <strong>enabled</strong> so
     *       {@code BigDecimal} money/amount fields serialize as plain decimals (no scientific
     *       notation, no precision loss) &mdash; preserving the COBOL {@code PIC S9(n)V99} field
     *       contracts.</li>
     *   <li>{@code SerializationFeature.WRITE_DATES_AS_TIMESTAMPS} <strong>disabled</strong> so
     *       {@code java.time} values serialize as ISO-8601 strings rather than numeric timestamps.</li>
     * </ul>
     *
     * @return a customizer that enforces plain-decimal and ISO-8601 serialization
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        return builder -> builder
                // Decimal fidelity (§0.7.3): BigDecimal as plain text, never 1.23E6.
                .featuresToEnable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN)
                // Date fidelity: java.time as ISO-8601 strings, never epoch numbers. JavaTimeModule
                // is auto-registered by Boot (jackson-datatype-jsr310 on the classpath), so only the
                // feature flag is needed here.
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * Centralized global exception handler that maps the {@code com.cardemo.exception} hierarchy to
     * consistent JSON error responses with the correct HTTP status codes.
     *
     * <h2>COBOL {@code FILE STATUS} &rarr; typed exception &rarr; HTTP status (&sect;0.7.5, L1063)</h2>
     * <p>On the mainframe each program inspected VSAM {@code FILE STATUS} codes (and CICS
     * {@code DFHRESP(...)} conditions) after every file operation and formatted its own error text
     * into working storage for the 3270 screen. This advice is the single, idiomatic Java
     * replacement for that scattered handling: the migrated services and repositories throw the
     * typed domain exceptions, and this one place translates each to an HTTP status and a uniform
     * error envelope. The mapping preserves the legacy behaviour exactly; only the mechanism
     * changes (Minimal Change Clause, &sect;0.7.1):</p>
     * <ul>
     *   <li>{@link RecordNotFoundException} ({@code FILE STATUS '23'} / {@code INVALID KEY})
     *       &rarr; <strong>404 Not Found</strong>.</li>
     *   <li>{@link DuplicateRecordException} ({@code FILE STATUS '22'} /
     *       {@code DFHRESP(DUPKEY|DUPREC)}) &rarr; <strong>409 Conflict</strong>.</li>
     *   <li>{@link ConcurrentModificationException} (JPA {@code @Version} optimistic-lock conflict,
     *       the {@code COACTUPC}/{@code COCRDUPC} snapshot-mismatch sites, &sect;0.7.5/L1061)
     *       &rarr; <strong>409 Conflict</strong>. This is the project domain type, NOT
     *       {@link java.util.ConcurrentModificationException}.</li>
     *   <li>{@link CreditLimitExceededException} (batch reject code 102) &rarr;
     *       <strong>422 Unprocessable Entity</strong>.</li>
     *   <li>{@link ExpiredCardException} (batch reject code 103) &rarr;
     *       <strong>422 Unprocessable Entity</strong>.</li>
     *   <li>{@link ValidationException} (service/business-rule validation) &rarr;
     *       <strong>400 Bad Request</strong>.</li>
     *   <li>{@link MethodArgumentNotValidException} (Jakarta {@code @Valid} DTO failures) &rarr;
     *       <strong>400 Bad Request</strong>, with per-field errors extracted into the body.</li>
     *   <li>{@link CardDemoException} (abstract base) &rarr; <strong>500 Internal Server Error</strong>
     *       catch-all for any domain exception lacking a more specific handler. Declared last so the
     *       concrete subtypes above match their dedicated handlers first.</li>
     * </ul>
     *
     * <h2>Error envelope</h2>
     * <p>Every handler returns the same {@link ApiError} shape. Stack traces are never leaked to the
     * client; the 500 handler logs the full stack server-side only. The optional
     * {@code correlationId} is read from the SLF4J {@link MDC} under the key
     * {@value #CORRELATION_ID_MDC_KEY} &mdash; a character-exact contract with the request
     * correlation-ID filter &mdash; so a client response can be tied back to its server-side log
     * lines. It is read via the MDC (not by importing the observability component) to keep this
     * web-layer type loosely coupled.</p>
     *
     * <p>As a nested {@code @RestControllerAdvice} (meta-annotated {@code @Component}) it is detected
     * by component scanning under base package {@code com.cardemo}. Per the AAP, error handling lives
     * here inside {@code WebConfig}; there is no standalone {@code GlobalExceptionHandler.java}.</p>
     */
    @RestControllerAdvice
    public static class GlobalExceptionHandler {

        /** Logger for this advice; emits diagnostics without leaking sensitive data. */
        private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

        /**
         * SLF4J {@link MDC} key under which the per-request correlation ID is published.
         *
         * <p>This value MUST remain exactly {@code "correlationId"}: it is a character-exact
         * cross-file contract with the request correlation-ID filter and the structured-logging
         * configuration. It is duplicated here as a local constant (rather than imported) so this
         * web-layer type stays decoupled from the observability package.</p>
         */
        private static final String CORRELATION_ID_MDC_KEY = "correlationId";

        /**
         * Maps a missing keyed record to <strong>404 Not Found</strong> &mdash; the COBOL
         * {@code INVALID KEY} / VSAM {@code FILE STATUS '23'} condition.
         *
         * @param ex      the thrown not-found exception
         * @param request the current request, used to populate the error {@code path}
         * @return a 404 response carrying the standard {@link ApiError} body
         */
        @ExceptionHandler(RecordNotFoundException.class)
        public ResponseEntity<ApiError> handleRecordNotFound(
                RecordNotFoundException ex, HttpServletRequest request) {
            log.warn("Record not found (HTTP 404): {}", ex.getMessage());
            return buildResponse(HttpStatus.NOT_FOUND, "RECORD_NOT_FOUND", ex.getMessage(),
                    request, null);
        }

        /**
         * Maps a duplicate-key add to <strong>409 Conflict</strong> &mdash; the COBOL
         * {@code DFHRESP(DUPKEY|DUPREC)} / VSAM {@code FILE STATUS '22'} condition.
         *
         * @param ex      the thrown duplicate-record exception
         * @param request the current request, used to populate the error {@code path}
         * @return a 409 response carrying the standard {@link ApiError} body
         */
        @ExceptionHandler(DuplicateRecordException.class)
        public ResponseEntity<ApiError> handleDuplicateRecord(
                DuplicateRecordException ex, HttpServletRequest request) {
            log.warn("Duplicate record (HTTP 409): {}", ex.getMessage());
            return buildResponse(HttpStatus.CONFLICT, "DUPLICATE_RECORD", ex.getMessage(),
                    request, null);
        }

        /**
         * Maps an optimistic-locking conflict to <strong>409 Conflict</strong> &mdash; the JPA
         * {@code @Version} replacement for the {@code COACTUPC}/{@code COCRDUPC} before/after
         * snapshot comparison (&sect;0.7.5/L1061).
         *
         * <p>The parameter type is the project's {@code com.cardemo.exception}
         * {@link ConcurrentModificationException}, deliberately not
         * {@link java.util.ConcurrentModificationException}.</p>
         *
         * @param ex      the thrown optimistic-lock conflict exception
         * @param request the current request, used to populate the error {@code path}
         * @return a 409 response carrying the standard {@link ApiError} body
         */
        @ExceptionHandler(ConcurrentModificationException.class)
        public ResponseEntity<ApiError> handleConcurrentModification(
                ConcurrentModificationException ex, HttpServletRequest request) {
            log.warn("Concurrent modification / optimistic-lock conflict (HTTP 409): {}",
                    ex.getMessage());
            return buildResponse(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION", ex.getMessage(),
                    request, null);
        }

        /**
         * Maps a credit-limit breach to <strong>422 Unprocessable Entity</strong> &mdash; CardDemo
         * batch reject code 102 ({@code OVERLIMIT TRANSACTION}). The request is well-formed but
         * violates a business rule.
         *
         * @param ex      the thrown credit-limit exception
         * @param request the current request, used to populate the error {@code path}
         * @return a 422 response carrying the standard {@link ApiError} body
         */
        @ExceptionHandler(CreditLimitExceededException.class)
        public ResponseEntity<ApiError> handleCreditLimitExceeded(
                CreditLimitExceededException ex, HttpServletRequest request) {
            log.warn("Credit limit exceeded (HTTP 422): {}", ex.getMessage());
            return buildResponse(HttpStatus.UNPROCESSABLE_ENTITY, "CREDIT_LIMIT_EXCEEDED",
                    ex.getMessage(), request, null);
        }

        /**
         * Maps a transaction-after-expiration rejection to <strong>422 Unprocessable Entity</strong>
         * &mdash; CardDemo batch reject code 103
         * ({@code TRANSACTION RECEIVED AFTER ACCT EXPIRATION}).
         *
         * @param ex      the thrown expired-account/card exception
         * @param request the current request, used to populate the error {@code path}
         * @return a 422 response carrying the standard {@link ApiError} body
         */
        @ExceptionHandler(ExpiredCardException.class)
        public ResponseEntity<ApiError> handleExpiredCard(
                ExpiredCardException ex, HttpServletRequest request) {
            log.warn("Transaction after account expiration (HTTP 422): {}", ex.getMessage());
            return buildResponse(HttpStatus.UNPROCESSABLE_ENTITY, "EXPIRED_CARD",
                    ex.getMessage(), request, null);
        }

        /**
         * Maps a service/business-rule validation failure to <strong>400 Bad Request</strong>,
         * surfacing the aggregated {@link ValidationException#getValidationErrors() error
         * descriptions} as per-field entries in the body. (Annotation-constraint {@code @Valid}
         * failures are handled separately by
         * {@link #handleMethodArgumentNotValid(MethodArgumentNotValidException, HttpServletRequest)}.)
         *
         * @param ex      the thrown validation exception
         * @param request the current request, used to populate the error {@code path}
         * @return a 400 response carrying the standard {@link ApiError} body with field errors
         */
        @ExceptionHandler(ValidationException.class)
        public ResponseEntity<ApiError> handleValidation(
                ValidationException ex, HttpServletRequest request) {
            log.warn("Validation failed (HTTP 400): {}", ex.getMessage());
            // getValidationErrors() is never null (defaults to an empty list); the offending field,
            // when known, is shared by every entry.
            List<FieldValidationError> fieldErrors = ex.getValidationErrors().stream()
                    .map(message -> new FieldValidationError(ex.getFieldName(), message))
                    .toList();
            return buildResponse(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", ex.getMessage(),
                    request, fieldErrors);
        }

        /**
         * Maps Jakarta Bean Validation {@code @Valid} request-body failures to
         * <strong>400 Bad Request</strong>, extracting each rejected field and its message into the
         * error body. This is the framework-raised counterpart of {@link ValidationException}
         * (the {@code RECEIVE MAP} field-edit replacement, transformation rule
         * {@code COPY CSSETATY} &rarr; {@code @Valid}).
         *
         * @param ex      the framework-raised method-argument validation exception
         * @param request the current request, used to populate the error {@code path}
         * @return a 400 response carrying the standard {@link ApiError} body with field errors
         */
        @ExceptionHandler(MethodArgumentNotValidException.class)
        public ResponseEntity<ApiError> handleMethodArgumentNotValid(
                MethodArgumentNotValidException ex, HttpServletRequest request) {
            List<FieldValidationError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                    .map(this::toFieldValidationError)
                    .toList();
            log.warn("Request body validation failed (HTTP 400): {} field error(s)",
                    fieldErrors.size());
            return buildResponse(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "Validation failed for one or more request fields", request, fieldErrors);
        }

        /**
         * Catch-all for any {@link CardDemoException} subtype without a dedicated handler, mapped to
         * <strong>500 Internal Server Error</strong>. Declared last so the concrete subtypes above
         * resolve to their specific handlers first.
         *
         * <p>The full stack trace is logged server-side for diagnosis but is never leaked to the
         * client; the response carries a generic message.</p>
         *
         * @param ex      the unhandled domain exception
         * @param request the current request, used to populate the error {@code path}
         * @return a 500 response carrying the standard {@link ApiError} body
         */
        @ExceptionHandler(CardDemoException.class)
        public ResponseEntity<ApiError> handleCardDemo(
                CardDemoException ex, HttpServletRequest request) {
            log.error("Unhandled CardDemo domain exception (HTTP 500)", ex);
            return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                    "An unexpected error occurred while processing the request", request, null);
        }

        /**
         * Converts a Spring {@link FieldError} (from a {@code @Valid} binding result) into the
         * transport-level {@link FieldValidationError}.
         *
         * @param fieldError the Spring binding field error
         * @return the corresponding {@link FieldValidationError}
         */
        private FieldValidationError toFieldValidationError(FieldError fieldError) {
            return new FieldValidationError(fieldError.getField(), fieldError.getDefaultMessage());
        }

        /**
         * Assembles the uniform error response. Blank/empty optional members are normalized to
         * {@code null} so they are omitted from the JSON (the {@link ApiError} record is annotated
         * {@code @JsonInclude(NON_NULL)}).
         *
         * @param status      the HTTP status to return
         * @param code        the stable machine-readable domain error code
         * @param message     the human-readable detail message; falls back to the status reason
         *                    phrase when blank
         * @param request     the current request (may be {@code null}); supplies the {@code path}
         * @param fieldErrors per-field validation errors, or {@code null}/empty when not applicable
         * @return a {@link ResponseEntity} wrapping the populated {@link ApiError}
         */
        private ResponseEntity<ApiError> buildResponse(
                HttpStatus status, String code, String message,
                HttpServletRequest request, List<FieldValidationError> fieldErrors) {
            ApiError body = new ApiError(
                    OffsetDateTime.now(),
                    status.value(),
                    status.getReasonPhrase(),
                    code,
                    (message != null && !message.isBlank()) ? message : status.getReasonPhrase(),
                    (request != null) ? request.getRequestURI() : null,
                    (fieldErrors != null && !fieldErrors.isEmpty()) ? fieldErrors : null,
                    MDC.get(CORRELATION_ID_MDC_KEY));
            return ResponseEntity.status(status).body(body);
        }

        /**
         * Immutable, uniform REST error envelope returned by every handler in this advice.
         *
         * <p>Annotated {@code @JsonInclude(NON_NULL)} so absent optional members
         * ({@code path}, {@code fieldErrors}, {@code correlationId}) are omitted from the JSON.
         * {@code timestamp} is an {@link OffsetDateTime} and therefore serializes as an ISO-8601
         * string under this configuration &mdash; it also dog-foods the date-fidelity setting.</p>
         *
         * @param timestamp     when the error response was produced (ISO-8601)
         * @param status        the numeric HTTP status code (for example 404)
         * @param error         the HTTP status reason phrase (for example {@code "Not Found"})
         * @param code          a stable machine-readable domain error code (for example
         *                      {@code "RECORD_NOT_FOUND"})
         * @param message       a human-readable detail message (never a stack trace)
         * @param path          the request URI that produced the error, or {@code null}
         * @param fieldErrors   per-field validation errors, or {@code null} when not applicable
         * @param correlationId the per-request correlation ID from the MDC, or {@code null}
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public record ApiError(
                OffsetDateTime timestamp,
                int status,
                String error,
                String code,
                String message,
                String path,
                List<FieldValidationError> fieldErrors,
                String correlationId) {
        }

        /**
         * A single field-level validation error within an {@link ApiError}.
         *
         * <p>Annotated {@code @JsonInclude(NON_NULL)} so a {@code null} {@code field} (for a
         * validation error not tied to a specific input) is omitted.</p>
         *
         * @param field   the name of the offending input field, or {@code null} if not field-specific
         * @param message the human-readable reason the field was rejected
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public record FieldValidationError(String field, String message) {
        }
    }
}
