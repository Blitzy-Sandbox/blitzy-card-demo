package com.vsergeychik.carddemo.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonStreamContext;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;

import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.DiagnosticText;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.common.ScreenInputRejectedException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.metadata.ConstraintDescriptor;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.StringUtils;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.web.servlet.handler.MappedInterceptor;

/**
 * Web-layer configuration for the 17 CICS online transactions that became stateless REST controllers: JSON
 * mapping, one global error mapping, and the single {@link Clock} the screen header depends on.
 *
 * <p>Counting the label-bearing definitions across those 17 mapsets yields exactly 441 fields, for example
 * 11 in {@code COSGN00.bms}, 54 in {@code COACTUP.bms} and 59 in {@code COTRN00.bms}.
 */
@Configuration
@EnableConfigurationProperties(WebConfig.JobSubmissionProperties.class)
public class WebConfig implements WebMvcConfigurer {
    public WebConfig() {
    }

    // =================================================================================================
    // THE INBOUND RESOURCE BOUNDS. Jackson's own defaults leave two of these UNLIMITED, so an
    // unauthenticated caller could make the server allocate without bound from a single request.
    // =================================================================================================

    /**
     * Largest request document this API will read, in bytes: {@value #MAX_JSON_DOCUMENT_BYTES}.
     *
     * <p>Jackson's {@code StreamReadConstraints.DEFAULT_MAX_DOC_LEN} is {@code -1}, meaning
     * <strong>unlimited</strong>, and Tomcat's {@code maxPostSize} bounds only {@code
     * application/x-www-form-urlencoded} bodies - not the {@code application/json} bodies every route
     * of this API consumes. So before this constant existed, nothing anywhere bounded the size of a
     * request body, and a single unauthenticated {@code POST} could drive the parser for as long as the
     * caller kept sending (CWE-400).
     *
     * <p>The value is a measured bound rather than a round guess. The largest JSON document this module
     * ships anywhere - across all 560 parity case fixtures - is 183,166 bytes, and the largest
     * <em>legitimate request</em> is far smaller: the widest screen is {@code COACTUP} at 54 fields
     * whose declared widths are all {@code PIC X(n)} with {@code n} at most 78, so a fully populated
     * body with its communication area is a few kilobytes. One mebibyte therefore leaves better than
     * five times headroom over the largest artefact in the repository and two orders of magnitude over
     * anything a screen can produce, while replacing "unlimited" with a number.
     */
    public static final long MAX_JSON_DOCUMENT_BYTES = 1_048_576L;

    /**
     * Largest number of JSON tokens a request document may contain: {@value #MAX_JSON_TOKEN_COUNT}.
     *
     * <p>The second unlimited default ({@code DEFAULT_MAX_TOKEN_COUNT} is {@code -1}). A document can
     * stay well inside {@link #MAX_JSON_DOCUMENT_BYTES} and still be pathological if it is composed
     * entirely of tiny tokens, so the byte bound alone is not sufficient. The most token-dense document
     * this module ships is {@code parity/CBACT04C/case02.json} at roughly 9,077 tokens, so this is an
     * order of magnitude above the worst real case.
     */
    public static final long MAX_JSON_TOKEN_COUNT = 100_000L;

    /**
     * Longest single JSON string value this API will read: {@value #MAX_JSON_STRING_LENGTH} characters.
     *
     * <p>Jackson's default is 20,000,000, which is three orders of magnitude beyond anything a 3270 can
     * transmit: the widest symbolic-map item in the entire estate is {@code ERRMSGI PIC X(78)}, and a
     * whole 24x80 screen is 1,920 characters. The bound is nevertheless set generously at 64 KiB rather
     * than at the screen width, because the value that must not be refused here is a legitimate one and
     * the per-field width is enforced downstream by the DTO constructors, which report the offending
     * field by name. The longest string this module ships anywhere is 37,973 characters (a parity case
     * description), so even that is accepted.
     */
    public static final int MAX_JSON_STRING_LENGTH = 65_536;

    /**
     * Deepest object or array nesting a request document may reach: {@value #MAX_JSON_NESTING_DEPTH}.
     *
     * <p>Every payload in this API is flat by construction - screen fields at the top level, one nested
     * {@code NavigationContext}, and one nested screen-extension object - so real depth is three or
     * four. The deepest document the module ships is six levels. Jackson's default of 1,000 is set for
     * general-purpose use; 64 is generous here and still refuses the deeply-nested shape that exists
     * only to consume parser stack.
     */
    public static final int MAX_JSON_NESTING_DEPTH = 64;

    /**
     * Longest JSON property name this API will read: {@value #MAX_JSON_NAME_LENGTH} characters.
     *
     * <p>Every property name traces to a {@code DFHMDF} field or a communication-area member, and the
     * longest one anywhere in the module is 30 characters. Jackson's default is 50,000. An
     * over-long name cannot bind to anything - {@code
     * spring.jackson.deserialization.fail-on-unknown-properties} refuses it - but it is buffered before
     * that judgement is made, which is the cost this bound removes.
     */
    public static final int MAX_JSON_NAME_LENGTH = 256;

    /**
     * Longest JSON numeric token this API will read: {@value #MAX_JSON_NUMBER_LENGTH} characters.
     *
     * <p>The widest numeric picture in the 28 copybooks is {@code PIC S9(10)V99}, which is thirteen
     * digits and a sign. This bound is set two orders of magnitude above that, and its purpose is to cap
     * the cost of the {@code BigDecimal} conversion {@code USE_BIG_DECIMAL_FOR_FLOATS} performs on every
     * numeric token: an arbitrarily long digit string is cheap to send and expensive to convert.
     */
    public static final int MAX_JSON_NUMBER_LENGTH = 256;

    // =================================================================================================
    // THE OUTBOUND HEADERS. Two headers, applied to every response including the error path.
    // =================================================================================================

    /** Bean name of the response-header contributor, so a test can assert it by name. */
    public static final String SCREEN_HEADER_BEAN_NAME = "carddemoScreenResponseHeaders";

    /**
     * The {@code Cache-Control} value every response of this API carries: {@value #CACHE_CONTROL_VALUE}.
     *
     * <p>Every response body of all seventeen screens is a projection of a record that was read for one
     * caller: an account balance, a card number, a customer's address, a user's row in {@code USRSEC}.
     * A 3270 datastream has nowhere to be cached; an HTTP response does, and with no directive at all an
     * intermediary or a browser may store and later re-serve it to a different caller (CWE-525).
     * {@code no-store} is the strongest of the directives and the only one appropriate here, because no
     * response of this API is ever reusable: it is a screen painted for one request.
     *
     * <p><strong>Why this value carries three more directives than
     * {@link NoStoreOnCredentialScreens#NO_STORE} does.</strong> The two are not competing policies and
     * neither weakens the other. This one is set by a filter on <em>every</em> response including the
     * container's {@code ERROR} dispatch, so it is the value an unknown intermediary sees on any path -
     * and {@code no-cache}, {@code must-revalidate} and {@code max-age=0} are the HTTP/1.0-era
     * belt-and-braces companions of {@link #PRAGMA_VALUE} and {@link #EXPIRES_VALUE} beside it, kept for
     * an intermediary that predates {@code no-store}. The interceptor's bare {@code no-store} is scoped
     * to the three credential-bearing routes, where the only reader that matters is a modern cache and
     * one unambiguous directive is clearer than four.
     *
     * <p><strong>What the three credential routes actually receive.</strong> The filter runs first and
     * sets all four headers; the interceptor's {@code preHandle} then runs and <em>replaces</em>
     * {@code Cache-Control} with the bare {@code no-store}. So {@code POST /api/signon},
     * {@code POST /api/users} and {@code PUT /api/users/&#123;userId&#125;} are delivered
     * {@code Cache-Control: no-store} together with the {@code Pragma}, {@code Expires} and
     * {@code X-Content-Type-Options} the filter set, and the other fourteen routes are delivered this
     * value in full. Nothing is lost by the replacement: {@code no-store} already forbids storing any
     * part of the exchange, which is strictly stronger than the three directives it displaces.
     */
    public static final String CACHE_CONTROL_VALUE = "no-store, no-cache, must-revalidate, max-age=0";

    /** The legacy HTTP/1.0 companion to {@link #CACHE_CONTROL_VALUE}, for intermediaries that predate it. */
    public static final String PRAGMA_VALUE = "no-cache";

    /** An already-expired {@code Expires} value, the third of the legacy no-store triple. */
    public static final String EXPIRES_VALUE = "0";

    /**
     * The {@code X-Content-Type-Options} value every response carries: {@value #CONTENT_TYPE_OPTIONS_VALUE}.
     *
     * <p>Every route of this API declares {@code produces = APPLICATION_JSON_VALUE}, and the values
     * inside those payloads are screen fields an operator typed - {@code TRAN-MERCHANT-NAME}, {@code
     * CUST-ADDR-LINE-1}, an error message - which this migration deliberately does not escape, because
     * the COBOL does not. Content-type sniffing is what turns that into a browser-side execution
     * hazard: a client that ignores the declared type and guesses from the bytes can treat a JSON body
     * as markup. This directive tells it not to guess (CWE-16). It is a mitigation for, and not a fix
     * of, the raw-value handling itself.
     */
    public static final String CONTENT_TYPE_OPTIONS_VALUE = "nosniff";

    /**
     * The {@code X-Content-Type-Options} header name. Spelled out here because {@link HttpHeaders}
     * defines constants for the standard headers only and this one is a de-facto extension.
     */
    public static final String CONTENT_TYPE_OPTIONS_HEADER = "X-Content-Type-Options";

    /**
     * Applies the Jackson settings that protect the migration's numeric parity and keep a request body from
     * being read as something other than what was sent.
     *
     * <p>Every monetary field in this estate derives from a {@code PIC S9(p)V99} clause, and a
     * {@code double} cannot represent a decimal fraction exactly, so binding one through a {@code double}
     * would corrupt the value before any business logic ran.
     *
     * @param datasetCharset the active dataset and screen code page, from
     *     {@code @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME)}; must not be {@code null}
     * @return the customizer Spring Boot applies to the shared {@code ObjectMapper} builder
     * @throws NullPointerException if {@code datasetCharset} is {@code null}
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer carddemoJacksonCustomizer(
            @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME) final Charset datasetCharset) {
        Objects.requireNonNull(datasetCharset, "A dataset charset is required: the inbound screen-text "
                + "boundary judges every value against a stated code page and never against the "
                + "platform default");
        final ScreenTextDeserializer screenText = new ScreenTextDeserializer(
                new FixedWidthCodec(datasetCharset));
        return builder -> builder
                .featuresToEnable(
                        DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,
                        DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                        JsonParser.Feature.STRICT_DUPLICATE_DETECTION,
                        JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN)
                .featuresToDisable(
                        DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT)
                // Every payload member of all seventeen screens projects a PIC X(n) item, so the rules
                // about what a RECEIVE MAP could have delivered are applied once, here, to every inbound
                // string - rather than seventeen times over per-route member lists, where a member left
                // off any one list is a silent hole, and rather than inside a flow whose own guards
                // decide whether a map is received at all. Three rules ride on this one registration:
                // the member must be character data at all, it must carry no character a terminal cannot
                // transmit, and it must carry no character the screen code page cannot represent.
                .deserializerByType(String.class, screenText)
                // The resource bounds. These are properties of the PARSER, not of the binder, so they
                // cannot be expressed as features and are applied to the factory the built mapper reads
                // through. Two of the six defaults are UNLIMITED, so this registration is what turns
                // "read until the caller stops sending" into a stated maximum. It is applied here rather
                // than in application.yml for the same reason FAIL_ON_TRAILING_TOKENS is: every
                // production-equivalent mapper a test builds from this customizer inherits it, so the
                // bound a deployment runs under is the bound the tests assert.
                .postConfigurer(mapper ->
                        mapper.getFactory().setStreamReadConstraints(screenReadConstraints()));
    }

    /**
     * The parser bounds every request body of this API is read under.
     *
     * <p>Built from the six published constants rather than from literals, so the value a test asserts
     * and the value a deployment runs under are the same value. Each bound's own constant documents why
     * it sits where it does and what the measured worst case in this repository is.
     *
     * @return the constraints, never {@code null}
     */
    static StreamReadConstraints screenReadConstraints() {
        return StreamReadConstraints.builder()
                .maxDocumentLength(MAX_JSON_DOCUMENT_BYTES)
                .maxTokenCount(MAX_JSON_TOKEN_COUNT)
                .maxStringLength(MAX_JSON_STRING_LENGTH)
                .maxNestingDepth(MAX_JSON_NESTING_DEPTH)
                .maxNameLength(MAX_JSON_NAME_LENGTH)
                .maxNumberLength(MAX_JSON_NUMBER_LENGTH)
                .build();
    }

    /**
     * Adds the two response headers this API owes every caller, on every path including the error path.
     *
     * <h4>Why a filter and not an interceptor</h4>
     * A {@link org.springframework.web.servlet.HandlerInterceptor} runs only where a handler was
     * selected. The responses that most need these headers are exactly the ones where that is not true:
     * the container's own {@code ERROR} dispatch, which {@link CobolErrorRoute} serves, and any
     * response produced before dispatch. A filter wraps all of them, and
     * {@link OncePerRequestFilter#shouldNotFilterErrorDispatch()} is overridden to {@code false} so the
     * headers are re-applied on the error dispatch rather than depending on the earlier pass surviving
     * whatever the container did to the response in between.
     *
     * <h4>What this is not</h4>
     * This is not authentication, not authorization and not a security filter chain. It sets two
     * response headers and reads nothing from the request. Spring Security, JWT and BCrypt remain named
     * exclusions from the migration's closed dependency set (gate G41), the Spring Security classpath is
     * asserted absent, and {@code spring-web} - which supplies {@link OncePerRequestFilter} - is already
     * one of the module's six declared dependencies, so no coordinate is added.
     *
     * <p>The headers are set <em>before</em> the chain proceeds, so a handler that deliberately states
     * its own caching policy could still override them. None does: no route of this API is cacheable.
     *
     * @return the header contributor, never {@code null}
     */
    @Bean(SCREEN_HEADER_BEAN_NAME)
    public OncePerRequestFilter carddemoScreenResponseHeaders() {
        return new ScreenResponseHeaderFilter();
    }

    /**
     * Sets {@link #CACHE_CONTROL_VALUE}, its two legacy companions and
     * {@link #CONTENT_TYPE_OPTIONS_VALUE} on every response.
     *
     * <p>Named rather than anonymous so the decision in {@link #shouldNotFilterErrorDispatch()} is
     * something a test can call and assert directly rather than infer.
     */
    static final class ScreenResponseHeaderFilter extends OncePerRequestFilter {

        /** Constructs the filter. It holds no state and is safe to share across requests. */
        ScreenResponseHeaderFilter() {
            // Intentionally empty: the header values are constants and nothing is read from the request.
        }

        @Override
        protected void doFilterInternal(final HttpServletRequest request,
                final HttpServletResponse response, final FilterChain chain)
                throws ServletException, IOException {
            response.setHeader(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_VALUE);
            response.setHeader(HttpHeaders.PRAGMA, PRAGMA_VALUE);
            response.setHeader(HttpHeaders.EXPIRES, EXPIRES_VALUE);
            response.setHeader(CONTENT_TYPE_OPTIONS_HEADER, CONTENT_TYPE_OPTIONS_VALUE);
            chain.doFilter(request, response);
        }

        /**
         * {@code false}, so this filter runs on the container's {@code ERROR} dispatch as well.
         *
         * <p>That dispatch is the path that needs these headers most: it is where a rejected request is
         * answered, and {@link CobolErrorRoute}'s JSON envelope is as uncacheable as any screen.
         * Spring's default is {@code true} - skip the error dispatch, on the reasoning that the original
         * pass already ran - which would leave the headers depending on whatever the container did to
         * the response in between.
         *
         * @return {@code false}, always
         */
        @Override
        protected boolean shouldNotFilterErrorDispatch() {
            return false;
        }
    }

    /**
     * Judges every inbound JSON string once, at the boundary where the request body is read, for characters
     * no {@code EXEC CICS RECEIVE MAP} could have delivered.
     *
     * <p>Every payload member projects a {@code PIC X(n)} item, which is {@code n} bytes in a single-byte
     * code page, so a character that page cannot encode is a value no {@code RECEIVE MAP} could have
     * delivered into the field - only a hand-built payload reaches it.
     */
    static final class ScreenTextDeserializer extends JsonDeserializer<String> {
        static final String UNNAMED_MEMBER = "requestBody";

        static final String ATTENTION_IDENTIFIER_MEMBER = "aid";

        static final String SCREEN_ITEM = "a PIC X(n) screen item";

        private final FixedWidthCodec codec;

        ScreenTextDeserializer(final FixedWidthCodec codec) {
            this.codec = Objects.requireNonNull(codec, "A FixedWidthCodec is required: the code page an "
                    + "inbound screen value is judged against is stated explicitly and never taken from the "
                    + "platform default");
        }

        FixedWidthCodec codec() {
            return codec;
        }

        /**
         * Reads one JSON string and judges it before it becomes a payload value.
         *
         * @param parser the parser, positioned on the value; must not be {@code null}
         * @param context the deserialization context; must not be {@code null}
         * @return the string as sent, unchanged, or {@code null} for a JSON {@code null}
         * @throws IOException if the underlying parser fails, or if a structured token is refused through
         *     Jackson's unexpected-token handling
         * @throws ScreenInputRejectedException if the value carries a character no terminal could transmit,
         *     or one the configured code page cannot represent; neither is asked of
         *     {@link #ATTENTION_IDENTIFIER_MEMBER}
         */
        @Override
        public String deserialize(final JsonParser parser, final DeserializationContext context)
                throws IOException {
            if (parser.hasToken(JsonToken.VALUE_NULL)) {
                return null;
            }

            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                if (context != null
                        && (parser.hasToken(JsonToken.START_OBJECT) || parser.hasToken(JsonToken.START_ARRAY))) {
                    context.handleUnexpectedToken(String.class, parser);
                }
                throw ScreenInputRejectedException.notCharacterData(memberName(parser),
                        String.valueOf(parser.currentToken()));
            }

            final String value = parser.getText();
            final String member = memberName(parser);
            if (ATTENTION_IDENTIFIER_MEMBER.equals(member)) {
                return value;
            }
            ScreenInputRejectedException.requireDeliverable(member, value);
            ScreenInputRejectedException.requireRepresentable(member, SCREEN_ITEM, value, codec);
            return value;
        }

        private static String memberName(final JsonParser parser) {
            for (JsonStreamContext context = parser.getParsingContext();
                    context != null;
                    context = context.getParent()) {
                final String name = context.getCurrentName();
                if (name != null && !name.isBlank()) {
                    return name;
                }
            }
            return UNNAMED_MEMBER;
        }
    }

    /**
     * The application's single source of the current instant.
     *
     * @return the system clock in the platform's default time zone
     */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }

    /**
     * Validates the job-submission port's contract once, at context refresh, so an unusable destination is
     * a startup failure rather than a surprise at the moment a report is requested.
     *
     * @param properties the bound port contract
     * @return the initializing bean whose only job is to run the validation
     */
    @Bean
    public JobSubmissionValidator jobSubmissionValidator(final JobSubmissionProperties properties) {
        return new JobSubmissionValidator(properties);
    }

    /**
     * Marks the three credential-bearing screens uncacheable, with
     * {@code Cache-Control: }{@value NoStoreOnCredentialScreens#NO_STORE}.
     *
     * <p>Three routes accept a password in the request body - {@code POST /api/signon}
     * ({@code COSGN00C}), {@code POST /api/users} ({@code COUSR01C}) and
     * {@code PUT /api/users/&#123;userId&#125;} ({@code COUSR02C}) - and on all three the field is
     * declared {@code ATTRB=(DRK,FSET,UNPROT)} on its mapset, which is the 3270's own statement that the
     * value is not for keeping. None of the three response bodies carries a credential: the response
     * types withhold the member and {@code COUSR02}'s publishes a fixed non-secret marker. This header
     * is the transport-level half of the same statement, and it is deliberately kept even so, because
     * the body is not the only place a credential-bearing exchange can be retained - a shared proxy, a
     * browser's back-forward cache or a diagnostic capture retains the whole exchange, request included.
     *
     * <p><strong>A {@code MappedInterceptor} bean rather than an {@code addInterceptors} override.</strong>
     * {@code AbstractHandlerMapping} detects every {@code MappedInterceptor} in the context and applies
     * it by path, so the registration needs no {@link WebMvcConfigurer} callback - which matters,
     * because this class implements that interface with no overrides at all and that emptiness is an
     * asserted property rather than an accident. Scoping by path is the point: the other fourteen online
     * routes carry no credential and are left exactly as the framework leaves them.
     *
     * <p><strong>Nothing beyond {@code no-store} is set here, and this runs after the filter.</strong>
     * {@code Pragma: no-cache} is obsolete under RFC 9111, and {@code no-store} alone already forbids any
     * part of the exchange from being kept in any cache - adding more would be noise a reader has to
     * evaluate. Because {@code preHandle} runs after {@link ScreenResponseHeaderFilter}, it does not add
     * to {@link #CACHE_CONTROL_VALUE} but replaces it: on these three routes the delivered
     * {@code Cache-Control} is the bare {@code no-store}, while the {@code Pragma}, {@code Expires} and
     * {@code X-Content-Type-Options} headers the filter set remain. That is deliberate and loses nothing
     * - {@code no-store} is strictly stronger than the directives it displaces - and it is why the two
     * rationales differ without disagreeing: see {@link #CACHE_CONTROL_VALUE} for the same statement from
     * the filter's side, and {@code WebConfigTest} for the assertion that pins the stacked outcome.
     *
     * @return the path-scoped interceptor, applied to the three credential screens and nowhere else
     */
    @Bean
    public MappedInterceptor noStoreOnCredentialScreens() {
        return new MappedInterceptor(NoStoreOnCredentialScreens.CREDENTIAL_SCREEN_PATTERNS,
                null,
                new NoStoreOnCredentialScreens());
    }

    /**
     * Sets {@code Cache-Control: }{@value #NO_STORE} on a response, before the handler runs.
     *
     * <p>{@code preHandle} rather than {@code postHandle}: a header has to be written before the
     * response is committed, and a body large enough to flush would commit it inside the handler. Setting
     * it up front means the guarantee does not depend on the size of what is being returned.
     *
     * <p>It holds no state - not even a field - so it is safe as a singleton and there is nothing for a
     * test to isolate.
     */
    static final class NoStoreOnCredentialScreens implements HandlerInterceptor {

        /**
         * The directive: no part of the exchange may be stored in any cache, shared or private.
         *
         * <p>Not {@code no-cache}, which permits storage and only requires revalidation, and not
         * {@code private}, which permits a browser to keep it.
         */
        static final String NO_STORE = "no-store";

        /**
         * The three paths that accept a credential, and only those three.
         *
         * <p>They are written as literals rather than read from the controllers, so that this
         * configuration does not depend on the {@code user} package. {@code WebConfigTest} asserts each
         * pattern against the controller's own declared mapping, so a route that moved would fail the
         * build rather than silently lose its header.
         */
        static final String[] CREDENTIAL_SCREEN_PATTERNS = {
            "/api/signon",
            "/api/users",
            "/api/users/*"
        };

        /** Constructs the interceptor. Stateless, so nothing is initialised. */
        NoStoreOnCredentialScreens() {
            // Intentionally empty.
        }

        @Override
        public boolean preHandle(final HttpServletRequest request,
                                 final HttpServletResponse response,
                                 final Object handler) {
            response.setHeader(HttpHeaders.CACHE_CONTROL, NO_STORE);
            return true;
        }
    }

    /**
     * Runs {@link JobSubmissionProperties#validate()} at context refresh.
     */
    static final class JobSubmissionValidator implements InitializingBean {
        private final JobSubmissionProperties properties;

        JobSubmissionValidator(final JobSubmissionProperties properties) {
            this.properties = properties;
        }

        /**
         * Validates the contract, failing the context on the first violation found.
         *
         * @throws IllegalStateException if any part of the contract or either path is unusable
         */
        @Override
        public void afterPropertiesSet() {
            properties.validate();
        }
    }

    /**
     * The global error mapping: it turns an abend and a validation failure into an HTTP response without
     * altering any text the COBOL would have produced.
     *
     * <p>The COBOL programs report their own diagnostics - into {@code SYSOUT} for a batch program, into
     * the screen's error field for an online one - and that text is exactly what the parity harness
     * compares.
     */
    @RestControllerAdvice
    public static class CobolErrorHandler {
        public static final String ABEND_CODE = "ABEND";

        public static final String ABEND_MESSAGE =
                "The transaction ended abnormally and no work was committed.";

        public static final String MALFORMED_REQUEST_CODE = "MALFORMED_REQUEST";

        private static final int MAX_CAUSE_DEPTH = 8;

        public static final String VALIDATION_FAILED_CODE = "VALIDATION_FAILED";

        public static final String VALIDATION_FAILED_DETAIL =
                "One or more request fields do not match this screen's payload contract. Each "
                        + "rejected field is named in fieldErrors.";

        public static final String REJECTED_VALUE_CODE = "REJECTED_VALUE";

        public static final String TYPE_MISMATCH_CODE = "TYPE_MISMATCH";

        public static final String DATASET_ACCESS_CODE = "DATASET_ACCESS";

        public static final String INTERNAL_STATE_CODE = "INTERNAL_STATE";

        public static final String REQUEST_NOT_COMPLETED_CODE = "REQUEST_NOT_COMPLETED";

        public static final String MALFORMED_REQUEST_MESSAGE =
                "The request body could not be read. Send a JSON body whose fields match this "
                        + "screen's payload contract.";

        private static final Log LOG = LogFactory.getLog(CobolErrorHandler.class);

        private static final String UNREADABLE_FIELD_DETAIL =
                "could not be read from the request body";

        private static final String REJECTED_VALUE_DETAIL = "A field value does not fit the COBOL "
                + "picture declared for it. Check each field's width and, for a numeric field, that "
                + "it holds only digits with the sign overpunched into the trailing character. The "
                + "rejected value is not echoed here.";

        private static final String INTERNAL_STATE_DETAIL = "The server is not in a state to serve "
                + "this request. The detail is in the server log rather than in this response.";

        private static final String SIZE_CONSTRAINT = "Size";

        private static final String PATTERN_CONSTRAINT = "Pattern";

        private static final Set<String> PRESENCE_CONSTRAINTS =
                Set.of("NotNull", "NotBlank", "NotEmpty");

        private static final Set<String> RANGE_CONSTRAINTS = Set.of("Min",
                "Max",
                "DecimalMin",
                "DecimalMax",
                "Digits",
                "Positive",
                "PositiveOrZero",
                "Negative",
                "NegativeOrZero");

        private static final String LENGTH_DETAIL =
                "does not satisfy the length declared for its screen field";

        private static final String PRESENCE_DETAIL = "is required";

        private static final String RANGE_DETAIL = "is outside the range declared for its screen field";

        private static final String PATTERN_DETAIL =
                "does not match the form declared for its screen field";

        private static final String CONSTRAINT_DETAIL =
                "is not valid for its screen field";

        public CobolErrorHandler() {
        }

        /**
         * Maps an abend - the Java form of {@code CALL 'CEE3ABD'}, which appears at nine sites across the
         * batch programs - onto {@code 500 Internal Server Error}.
         *
         * @param abend the abend raised by a service, job or repository
         * @return {@code 500} with the stable abend code, its generic message, and the source's own
         *     transmitted diagnostic where there is one
         */
        @ExceptionHandler(AbendException.class)
        public ResponseEntity<CobolErrorResponse> handleAbend(final AbendException abend) {
            logAbend(abend);
            return json(HttpStatus.INTERNAL_SERVER_ERROR, abendResponse(abend));
        }

        static CobolErrorResponse abendResponse(final AbendException abend) {
            Objects.requireNonNull(abend, "An abend is required to answer one");
            return new CobolErrorResponse(ABEND_CODE,
                    reasonPhraseOf(HttpStatus.INTERNAL_SERVER_ERROR),
                    ABEND_MESSAGE,
                    List.of(),
                    abend.getSourceDiagnostic().orElse(null));
        }

        static void logAbend(final AbendException abend) {
            Objects.requireNonNull(abend, "An abend is required to log one");
            LOG.error("Abend answered with HTTP " + HttpStatus.INTERNAL_SERVER_ERROR.value()
                    + ": program=" + abend.getProgram()
                    + " RETURN-CODE=" + abend.getReturnCode()
                    + ", raised as " + abend.getClass().getName());
        }

        /**
         * Maps a request body that could not be read onto {@code 400 Bad Request}.
         *
         * @param unreadable the binding or parse failure Spring raised
         * @return {@code 400} with the stable malformed-request code and its generic message
         */
        @ExceptionHandler(HttpMessageNotReadableException.class)
        public ResponseEntity<CobolErrorResponse> handleUnreadableRequestBody(
                final HttpMessageNotReadableException unreadable) {
            final ScreenInputRejectedException screenInput = screenInputCause(unreadable);
            if (screenInput != null) {
                return handleRejectedValue(screenInput);
            }
            logUnreadableRequestBody(unreadable);
            return json(HttpStatus.BAD_REQUEST, malformedRequestResponse(unreadable));
        }

        static ScreenInputRejectedException screenInputCause(
                final HttpMessageNotReadableException unreadable) {
            Objects.requireNonNull(unreadable, "A binding failure is required to inspect one");
            Throwable cause = unreadable.getCause();
            for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++) {
                if (cause instanceof ScreenInputRejectedException screenInput) {
                    return screenInput;
                }
                cause = cause.getCause();
            }
            return null;
        }

        static CobolErrorResponse malformedRequestResponse(
                final HttpMessageNotReadableException unreadable) {
            Objects.requireNonNull(unreadable, "A binding failure is required to answer one");
            return CobolErrorResponse.of(MALFORMED_REQUEST_CODE,
                    HttpStatus.BAD_REQUEST,
                    MALFORMED_REQUEST_MESSAGE,
                    unreadableBodyFields(unreadable));
        }

        static void logUnreadableRequestBody(final HttpMessageNotReadableException unreadable) {
            Objects.requireNonNull(unreadable, "A binding failure is required to log one");
            if (LOG.isDebugEnabled()) {
                // Only field NAMES are taken from the mapping path - never the parser's message, which is
                // where the payload is quoted - and each is escaped, because a JSON property name is
                // caller-supplied text and an unescaped newline in a log line is a forged log entry
                // (CWE-117).
                final String namedFields = unreadableBodyFields(unreadable).stream()
                        .map(FieldMessage::field)
                        .map(DiagnosticText::singleLine)
                        .collect(Collectors.joining(", "));
                LOG.debug("Request body rejected with HTTP " + HttpStatus.BAD_REQUEST.value()
                        + "; unreadable at field(s): "
                        + (namedFields.isEmpty() ? "none named - the document itself is unreadable"
                                : namedFields)
                        + "; raised as " + unreadable.getClass().getName());
            }
        }

        /**
         * Maps a rejected request body onto {@code 400 Bad Request}, carrying the field-level messages.
         *
         * @param invalid the binding failure Spring raised for an {@code @Valid} request body
         * @return {@code 400} with one entry per rejected field
         */
        @ExceptionHandler(MethodArgumentNotValidException.class)
        public ResponseEntity<CobolErrorResponse> handleInvalidRequestBody(
                final MethodArgumentNotValidException invalid) {
            return json(HttpStatus.BAD_REQUEST, validationResponse(invalid));
        }

        static CobolErrorResponse validationResponse(final MethodArgumentNotValidException invalid) {
            final Object bound = invalid.getBindingResult().getTarget();
            final List<FieldMessage> rejected = invalid.getBindingResult().getFieldErrors().stream()
                    .map(error -> fieldMessage(bound, error))
                    .sorted(Comparator.comparing(FieldMessage::field))
                    .toList();
            return CobolErrorResponse.of(VALIDATION_FAILED_CODE,
                    HttpStatus.BAD_REQUEST,
                    VALIDATION_FAILED_DETAIL,
                    rejected);
        }

        /**
         * Maps a constraint violation raised outside request-body binding - on a path variable, a query
         * parameter or a validated service argument - onto {@code 400 Bad Request}.
         *
         * @param violations the violations the validator collected
         * @return {@code 400} with one entry per violation
         */
        @ExceptionHandler(ConstraintViolationException.class)
        public ResponseEntity<CobolErrorResponse> handleConstraintViolation(
                final ConstraintViolationException violations) {
            return json(HttpStatus.BAD_REQUEST, validationResponse(violations));
        }

        static CobolErrorResponse validationResponse(final ConstraintViolationException violations) {
            final List<FieldMessage> rejected = violations.getConstraintViolations().stream()
                    .map(CobolErrorHandler::fieldMessage)
                    .sorted(Comparator.comparing(FieldMessage::field))
                    .toList();
            return CobolErrorResponse.of(VALIDATION_FAILED_CODE,
                    HttpStatus.BAD_REQUEST,
                    VALIDATION_FAILED_DETAIL,
                    rejected);
        }

        static List<FieldMessage> unreadableBodyFields(
                final HttpMessageNotReadableException unreadable) {
            Objects.requireNonNull(unreadable, "A binding failure is required to read field names from");
            return unreadable.getCause() instanceof JsonMappingException mapping
                    ? mapping.getPath().stream()
                            .map(JsonMappingException.Reference::getFieldName)
                            .filter(field -> field != null && !field.isBlank())
                            .map(field -> new FieldMessage(field, UNREADABLE_FIELD_DETAIL))
                            .sorted(Comparator.comparing(FieldMessage::field))
                            .toList()
                    : List.of();
        }

        /**
         * Maps a value a domain guard rejected onto {@code 400 Bad Request}.
         *
         * @param rejected the guard failure
         * @return {@code 400} with a fixed, value-free explanation
         */
        @ExceptionHandler(IllegalArgumentException.class)
        public ResponseEntity<CobolErrorResponse> handleRejectedValue(
                final IllegalArgumentException rejected) {
            if (rejected instanceof ScreenInputRejectedException screenInput) {
                // It names the code page, the symbolic-map item, the PICTURE width and the Unicode code
                // point - the facts an engineer holding the copybook open needs, and the facts an
                // unauthenticated caller must not be handed.
                LOG.warn("Rejected a request because a screen value could not have arrived through a "
                        + "RECEIVE MAP; responding " + HttpStatus.BAD_REQUEST.value()
                        + " with the fixed " + screenInput.reason() + " answer, naming the member and "
                        + "echoing no value; raised as " + rejected.getClass().getName()
                        + "; diagnostic: " + DiagnosticText.singleLine(screenInput.getMessage()));
                return json(HttpStatus.BAD_REQUEST, screenInputRejectedResponse(screenInput));
            }
            LOG.warn("Rejected a request because a field value did not fit its COBOL picture; "
                    + "responding " + HttpStatus.BAD_REQUEST.value() + " with no value echoed"
                    + "; raised as " + rejected.getClass().getName() + ".");
            return json(HttpStatus.BAD_REQUEST, rejectedValueResponse());
        }

        static CobolErrorResponse screenInputRejectedResponse(
                final ScreenInputRejectedException rejected) {
            String publicDetail = rejected.publicDetail();
            List<FieldMessage> fieldErrors = rejected.member()
                    .map(member -> List.of(new FieldMessage(member, publicDetail)))
                    .orElseGet(List::of);
            return CobolErrorResponse.of(REJECTED_VALUE_CODE,
                    HttpStatus.BAD_REQUEST,
                    publicDetail,
                    fieldErrors);
        }

        static CobolErrorResponse rejectedValueResponse() {
            return CobolErrorResponse.of(REJECTED_VALUE_CODE,
                    HttpStatus.BAD_REQUEST,
                    REJECTED_VALUE_DETAIL);
        }

        /**
         * Maps a server-side state or configuration fault onto {@code 500 Internal Server Error}.
         *
         * @param fault the state fault
         * @return {@code 500} with a fixed, value-free explanation
         */
        @ExceptionHandler(IllegalStateException.class)
        public ResponseEntity<CobolErrorResponse> handleInternalState(
                final IllegalStateException fault) {
            LOG.error("A request could not be served because the server is not in a state to serve "
                    + "it; responding " + HttpStatus.INTERNAL_SERVER_ERROR.value()
                    + " with no configuration detail echoed; raised as "
                    + fault.getClass().getName() + ".");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(internalStateResponse());
        }

        static CobolErrorResponse internalStateResponse() {
            return CobolErrorResponse.of(INTERNAL_STATE_CODE,
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    INTERNAL_STATE_DETAIL);
        }

        private static FieldMessage fieldMessage(final Object bound, final FieldError error) {
            return new FieldMessage(jsonMemberOf(bound, error.getField()),
                    publicConstraintText(error.getCode(), constraintAttributes(error)));
        }

        static String jsonMemberOf(final Object bound, final String path) {
            if (bound == null || path == null || path.isEmpty()) {
                return path;
            }
            final String[] segments = path.split("\\.", -1);
            final StringBuilder resolved = new StringBuilder(path.length());
            Class<?> declaring = bound.getClass();
            for (int index = 0; index < segments.length; index++) {
                if (index > 0) {
                    resolved.append('.');
                }
                final String segment = segments[index];
                final int subscript = segment.indexOf('[');
                final String name = subscript < 0 ? segment : segment.substring(0, subscript);
                final String suffix = subscript < 0 ? "" : segment.substring(subscript);
                final Field member = declaring == null ? null : declaredMember(declaring, name);
                if (member == null) {
                    resolved.append(segment);
                    for (int rest = index + 1; rest < segments.length; rest++) {
                        resolved.append('.').append(segments[rest]);
                    }
                    return resolved.toString();
                }
                final JsonProperty renamed = member.getAnnotation(JsonProperty.class);
                final boolean named = renamed != null && !renamed.value().isEmpty()
                        && !JsonProperty.USE_DEFAULT_NAME.equals(renamed.value());
                resolved.append(named ? renamed.value() : name).append(suffix);
                declaring = nextDeclaringType(member, !suffix.isEmpty());
            }
            return resolved.toString();
        }

        private static Field declaredMember(final Class<?> declaring, final String name) {
            for (Class<?> type = declaring; type != null && type != Object.class;
                    type = type.getSuperclass()) {
                try {
                    return type.getDeclaredField(name);
                } catch (NoSuchFieldException notHere) {
                    continue;
                }
            }
            return null;
        }

        private static Class<?> nextDeclaringType(final Field member, final boolean subscript) {
            if (!subscript) {
                return member.getType();
            }
            if (member.getType().isArray()) {
                return member.getType().getComponentType();
            }
            final java.lang.reflect.Type generic = member.getGenericType();
            if (generic instanceof java.lang.reflect.ParameterizedType parameterized) {
                final java.lang.reflect.Type[] arguments = parameterized.getActualTypeArguments();
                if (arguments.length > 0 && arguments[arguments.length - 1] instanceof Class<?> element) {
                    return element;
                }
            }
            return null;
        }

        private static FieldMessage fieldMessage(final ConstraintViolation<?> violation) {
            final ConstraintDescriptor<?> descriptor = violation.getConstraintDescriptor();
            final String code = descriptor == null || descriptor.getAnnotation() == null
                    ? null
                    : descriptor.getAnnotation().annotationType().getSimpleName();
            final Map<String, Object> attributes =
                    descriptor == null ? Map.of() : descriptor.getAttributes();
            return new FieldMessage(String.valueOf(violation.getPropertyPath()),
                    publicConstraintText(code, attributes));
        }

        private static Map<String, Object> constraintAttributes(final FieldError error) {
            if (!error.contains(ConstraintViolation.class)) {
                return Map.of();
            }
            final ConstraintDescriptor<?> descriptor =
                    error.unwrap(ConstraintViolation.class).getConstraintDescriptor();
            return descriptor == null ? Map.of() : descriptor.getAttributes();
        }

        static String publicConstraintText(final String code, final Map<String, Object> attributes) {
            if (code == null) {
                return CONSTRAINT_DETAIL;
            }
            if (SIZE_CONSTRAINT.equals(code)) {
                final Object max = attributes.get("max");
                if (max instanceof Integer declared && declared != Integer.MAX_VALUE) {
                    return "must be at most " + declared + " characters";
                }
                return LENGTH_DETAIL;
            }
            if (PRESENCE_CONSTRAINTS.contains(code)) {
                return PRESENCE_DETAIL;
            }
            if (RANGE_CONSTRAINTS.contains(code)) {
                return RANGE_DETAIL;
            }
            if (PATTERN_CONSTRAINT.equals(code)) {
                return PATTERN_DETAIL;
            }
            return CONSTRAINT_DETAIL;
        }

        static final String UNREADABLE_BODY_MESSAGE =
                "The request body could not be read as JSON.";

        static final String TYPE_MISMATCH_MESSAGE =
                "A request value could not be converted to the type its field declares.";

        static final String DATASET_ACCESS_MESSAGE =
                "The request could not be completed because a dataset could not be accessed.";

        static final String UNEXPECTED_FAILURE_MESSAGE =
                "The request could not be completed.";

        static ResponseEntity<CobolErrorResponse> handleUnreadableBody(
                final HttpMessageNotReadableException unreadable) {
            return sanitized(MALFORMED_REQUEST_CODE, HttpStatus.BAD_REQUEST, UNREADABLE_BODY_MESSAGE);
        }

        /**
         * Maps a value that could not be converted to its declared type onto {@code 400 Bad Request}.
         *
         * @param mismatch the conversion failure, whose message is intentionally discarded
         * @return {@code 400} carrying {@link #TYPE_MISMATCH_MESSAGE} and, where the framework supplies
         *     one, the name of the parameter that could not be converted
         */
        @ExceptionHandler(TypeMismatchException.class)
        public ResponseEntity<CobolErrorResponse> handleTypeMismatch(
                final TypeMismatchException mismatch) {
            return json(HttpStatus.BAD_REQUEST, typeMismatchResponse(mismatch));
        }

        static CobolErrorResponse typeMismatchResponse(final TypeMismatchException mismatch) {
            final String parameter = mismatch instanceof MethodArgumentTypeMismatchException named
                    ? named.getName()
                    : null;
            if (parameter == null || parameter.isBlank()) {
                return sanitizedBody(TYPE_MISMATCH_CODE, HttpStatus.BAD_REQUEST, TYPE_MISMATCH_MESSAGE);
            }
            return CobolErrorResponse.of(TYPE_MISMATCH_CODE,
                    HttpStatus.BAD_REQUEST,
                    TYPE_MISMATCH_MESSAGE,
                    List.of(new FieldMessage(parameter, TYPE_MISMATCH_MESSAGE)));
        }

        /**
         * Maps a data-access failure onto {@code 500 Internal Server Error}.
         *
         * @param failure the data-access failure, whose message is intentionally discarded
         * @return {@code 500} carrying {@link #DATASET_ACCESS_MESSAGE} and nothing else
         */
        @ExceptionHandler(DataAccessException.class)
        public ResponseEntity<CobolErrorResponse> handleDataAccessFailure(
                final DataAccessException failure) {
            logDataAccessFailure(failure);
            return sanitized(DATASET_ACCESS_CODE, HttpStatus.INTERNAL_SERVER_ERROR,
                    DATASET_ACCESS_MESSAGE);
        }

        static void logDataAccessFailure(final DataAccessException failure) {
            Objects.requireNonNull(failure, "A data-access failure is required to log one");
            LOG.error("A dataset access did not complete, so the unit of work is abandoned; responding "
                    + HttpStatus.INTERNAL_SERVER_ERROR.value()
                    + " with no dataset name, statement text or bound value echoed"
                    + "; raised as " + failure.getClass().getName() + ".");
        }

        /**
         * The status-preserving catch-all: it withholds the message of any remaining failure without
         * altering the status that failure already carries.
         *
         * @param failure the unclaimed failure; its status is honoured, its message is not published
         * @return the status {@link #statusForFailure(Exception)} determines, carrying
         *     {@link #UNEXPECTED_FAILURE_MESSAGE}
         */
        @ExceptionHandler(Exception.class)
        public ResponseEntity<CobolErrorResponse> handleUnexpectedFailure(final Exception failure) {
            final HttpStatusCode status = statusForFailure(failure);
            logUnexpectedFailure(failure, status);
            return sanitized(REQUEST_NOT_COMPLETED_CODE, status, UNEXPECTED_FAILURE_MESSAGE);
        }

        static void logUnexpectedFailure(final Exception failure, final HttpStatusCode status) {
            Objects.requireNonNull(failure, "A failure is required to log one");
            Objects.requireNonNull(status, "A status is required to log a failure at");
            final String sentence = "A request was not completed; responding " + status.value()
                    + " with no failure detail echoed"
                    + "; raised as " + failure.getClass().getName() + ".";
            if (status.is5xxServerError()) {
                LOG.error(sentence);
            } else {
                LOG.debug(sentence);
            }
        }

        static HttpStatusCode statusForFailure(final Exception failure) {
            if (failure instanceof org.springframework.web.ErrorResponse carriesItsOwnStatus) {
                return carriesItsOwnStatus.getStatusCode();
            }
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }

        static ResponseEntity<CobolErrorResponse> sanitized(final String code,
                final HttpStatusCode status,
                final String message) {
            return json(status, sanitizedBody(code, status, message));
        }

        static ResponseEntity<CobolErrorResponse> json(final HttpStatusCode status,
                final CobolErrorResponse body) {
            return ResponseEntity.status(status)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body);
        }

        static CobolErrorResponse sanitizedBody(final String code,
                final HttpStatusCode status,
                final String message) {
            return CobolErrorResponse.of(code, status, message);
        }

        static String reasonPhraseOf(final HttpStatusCode status) {
            final HttpStatus standard = HttpStatus.resolve(status.value());
            return standard == null ? "" : standard.getReasonPhrase();
        }

        @JsonInclude(JsonInclude.Include.NON_NULL)
        public record CobolErrorResponse(String code,
                                         String error,
                                         String detail,
                                         List<FieldMessage> fieldErrors,
                                         String abendData) {
            public CobolErrorResponse {
                fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
            }

            static CobolErrorResponse of(final String code,
                    final HttpStatusCode status,
                    final String detail) {
                return new CobolErrorResponse(code, reasonPhraseOf(status), detail, List.of(), null);
            }

            static CobolErrorResponse of(final String code,
                    final HttpStatusCode status,
                    final String detail,
                    final List<FieldMessage> fieldErrors) {
                return new CobolErrorResponse(code, reasonPhraseOf(status), detail, fieldErrors, null);
            }
        }

        /**
         * One field a failure identified, within {@link CobolErrorResponse#fieldErrors()}.
         *
         * @param field the field or property path that failed - the name the caller sent, never a Java
         *     identifier invented by the framework
         * @param message what is wrong with it: the validator's own message, or one of this class's fixed
         *     field details
         */
        public record FieldMessage(String field, String message) {
        }

    }

    /**
     * The container's error path, answered as JSON by this API rather than as a framework page.
     *
     * <h2>What this replaces, and why</h2>
     * Spring Boot registers {@code BasicErrorController} for {@code /error} and gives it two mappings:
     * one that produces JSON and one that produces {@code text/html} backed by the whitelabel view. A
     * servlet container reaches that path by <em>forwarding</em> to it whenever a response is completed
     * with an error status and no handler produced a body, so an API that speaks only JSON still ended
     * up answering {@code Accept: text/html} with
     * {@code <html><body><h1>Whitelabel Error Page</h1>...}, and a direct
     * {@code GET /error} - the path is a real, registered mapping - answered {@code 500} with
     * {@code {"timestamp":...,"status":999,"error":"None"}}, a third body shape carrying a status code
     * that does not exist.
     *
     * <p>Declaring an {@link ErrorController} bean suppresses Boot's own: its auto-configuration is
     * conditional on no such bean being present. So this class is not an addition alongside the
     * whitelabel page, it is a replacement for it, and {@code server.error.whitelabel.enabled=false} in
     * {@code application.yml} states the same intent from the configuration side.
     *
     * <h2>Why the route declares no {@code Accept} restriction</h2>
     * An {@code accept} predicate would make this route unmatchable for exactly the request that needs
     * it most - the one whose {@code Accept} header the API cannot satisfy. With no restriction the
     * route always matches on its path, and the response pins {@code application/json} itself so the
     * body is written whatever was asked for.
     *
     * <h2>What it publishes</h2>
     * The same envelope every other failure uses, carrying
     * {@link CobolErrorHandler#REQUEST_NOT_COMPLETED_CODE} and
     * {@link CobolErrorHandler#UNEXPECTED_FAILURE_MESSAGE} - and nothing from the request. No timestamp
     * (non-deterministic output in a module whose responses are compared byte for byte), no path, no
     * exception, no trace: the detail belongs in the server log, which is the same boundary
     * {@link CobolErrorHandler} draws.
     *
     * <p>The status is the one the container recorded for the failure it is rendering, taken from the
     * {@code jakarta.servlet.error.status_code} request attribute. A request that arrives at this path
     * <em>without</em> that attribute was not forwarded here by an error - it asked for this path
     * directly - and is answered {@code 500} rather than with an invented code, because no failure
     * status exists to report.
     *
     * <h2>Why this is a {@link RouterFunction} and deliberately NOT a {@code @RestController}</h2>
     * This is infrastructure, not a screen. The online surface is <strong>exactly seventeen</strong>
     * {@code @RestController} beans, one per CICS online program, and that count is a contract the
     * migration plan gates on (G3): every controller stereotype in the graph has to be a translated
     * transaction, or the count stops meaning anything. An eighteenth controller declared here would
     * have made the assertion "seventeen screens plus one that is not a screen", which is a count that
     * can absorb the next accidental addition without noticing.
     *
     * <p>Spring MVC's functional routing gives the same mapping with no stereotype: this class is
     * published by {@link WebConfig#cobolErrorRoute(String)} as a {@link RouterFunction} bean, and
     * {@code RouterFunctionMapping} composes every such bean into one {@code HandlerMapping} alongside
     * the annotated one. The error dispatch is an ordinary servlet forward, so it resolves through that
     * mapping exactly as it resolved through {@code RequestMappingHandlerMapping} before.
     *
     * <p>It still implements {@link ErrorController}, and that is not decoration:
     * {@code ErrorMvcAutoConfiguration.basicErrorController} is declared
     * {@code @ConditionalOnMissingBean(ErrorController.class)}, so the presence of a bean of this type
     * is the entire mechanism that switches Boot's HTML-producing controller off. The interface is a
     * marker with no methods in Boot 3, which is why a non-controller can carry it.
     */
    public static final class CobolErrorRoute
            implements ErrorController, RouterFunction<ServerResponse> {

        /**
         * The one path this route answers, resolved from configuration exactly as the annotated mapping
         * resolved it: {@code server.error.path}, then {@code error.path}, then {@code /error}.
         */
        private final String errorPath;

        /**
         * Constructs the route for a configured error path.
         *
         * @param errorPath the path the container forwards a failed response to; must not be
         *                  {@code null} and must hold text
         * @throws NullPointerException     if {@code errorPath} is {@code null}
         * @throws IllegalArgumentException if {@code errorPath} holds no text
         */
        public CobolErrorRoute(final String errorPath) {
            Objects.requireNonNull(errorPath, "An error path is required: it is the path the container "
                    + "forwards a failed response to, and a route that matched nothing would hand the "
                    + "dispatch back to Boot's whitelabel page");
            if (!StringUtils.hasText(errorPath)) {
                throw new IllegalArgumentException("The configured error path holds no text. "
                        + "server.error.path (or error.path) must name the path the container forwards "
                        + "to; the framework default is '/error'.");
            }
            this.errorPath = errorPath;
        }

        /**
         * Matches the configured error path and nothing else.
         *
         * <p>No method and no {@code Accept} restriction, for the same reason the annotated mapping
         * declared no {@code produces}: the request that most needs this route is the one whose
         * {@code Accept} header the API cannot satisfy, and a restriction would make the route
         * unmatchable for exactly that request. The dispatch also arrives with whatever method the
         * original request used, so pinning one would leave a failed {@code PUT} unanswered.
         *
         * @param request the request being routed; must not be {@code null}
         * @return the handler when the path is the configured error path, and
         *         {@link Optional#empty()} otherwise
         * @throws NullPointerException if {@code request} is {@code null}
         */
        @Override
        public Optional<HandlerFunction<ServerResponse>> route(final ServerRequest request) {
            Objects.requireNonNull(request, "A request is required to route it");
            return errorPath.equals(request.requestPath().value())
                    ? Optional.of(this::render)
                    : Optional.empty();
        }

        /**
         * Renders the container's error dispatch as this API's one error envelope.
         *
         * @param request the forwarded request, read only for {@code jakarta.servlet.error.status_code};
         *     must not be {@code null}
         * @return the envelope, always {@code application/json}, carrying the recorded status
         * @throws NullPointerException if {@code request} is {@code null}
         */
        public ServerResponse render(final ServerRequest request) {
            Objects.requireNonNull(request, "A request is required to render its error status");
            return render(request.servletRequest());
        }

        /**
         * Renders the envelope for a servlet request, which is the form a unit test can supply.
         *
         * @param request the forwarded request; must not be {@code null}
         * @return the envelope, always {@code application/json}, carrying the recorded status
         * @throws NullPointerException if {@code request} is {@code null}
         */
        public ServerResponse render(final HttpServletRequest request) {
            final HttpStatusCode status = statusOf(request);
            return ServerResponse.status(status)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(bodyFor(status));
        }

        /**
         * The body this route publishes: the shared code and message, and nothing from the request.
         *
         * @param status the status the body travels with, which supplies its reason phrase; must not be
         *               {@code null}
         * @return the envelope body
         * @throws NullPointerException if {@code status} is {@code null}
         */
        public static CobolErrorHandler.CobolErrorResponse bodyFor(final HttpStatusCode status) {
            Objects.requireNonNull(status, "A status is required: it supplies the body's reason phrase");
            return CobolErrorHandler.CobolErrorResponse.of(
                    CobolErrorHandler.REQUEST_NOT_COMPLETED_CODE,
                    status,
                    CobolErrorHandler.UNEXPECTED_FAILURE_MESSAGE);
        }

        /**
         * The status the container recorded for the failure being rendered.
         *
         * @param request the forwarded request; must not be {@code null}
         * @return the recorded status when the attribute is present and is a valid HTTP status, and
         *         {@link HttpStatus#INTERNAL_SERVER_ERROR} otherwise
         */
        public static HttpStatusCode statusOf(final HttpServletRequest request) {
            Objects.requireNonNull(request, "A request is required to read its error status");
            final Object recorded = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
            if (recorded instanceof Integer status && HttpStatus.resolve(status) != null) {
                return HttpStatus.valueOf(status);
            }
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }

        /**
         * The configured error path this route answers.
         *
         * @return the path; never {@code null} and never blank
         */
        public String errorPath() {
            return errorPath;
        }
    }

    /**
     * Publishes {@link CobolErrorRoute} as the module's error-path route.
     *
     * <p>Two things happen because this bean exists, and both are load-bearing.
     * {@code RouterFunctionMapping} collects every {@link RouterFunction} bean in the context and
     * composes them into one {@code HandlerMapping}, so the container's forward to the error path finds
     * a handler that answers JSON. And the bean's type implements {@link ErrorController}, which is the
     * condition {@code ErrorMvcAutoConfiguration} backs off on - so Boot's {@code BasicErrorController},
     * with its {@code text/html} whitelabel mapping, is never registered.
     *
     * <p>The path is bound rather than hard-coded so a deployment that relocates the error path in
     * configuration relocates this route with it. The placeholder chain mirrors Boot's own:
     * {@code server.error.path}, then {@code error.path}, then {@code /error}.
     *
     * @param errorPath the configured error path
     * @return the route; never {@code null}
     */
    @Bean
    public CobolErrorRoute cobolErrorRoute(
            @Value("${server.error.path:${error.path:/error}}") final String errorPath) {
        return new CobolErrorRoute(errorPath);
    }

    /**
     * The {@code CORPT00C} job-submission port's contract, bound from {@code carddemo.job-submission} and
     * validated at startup.
     *
     * @param queueName the TDQ name; required to be exactly {@link #TDQ_QUEUE_NAME}, which is what
     *     {@code app/csd/CARDDEMO.CSD:L499} declares
     * @param ddName the {@code DDNAME}; required to be exactly {@link #TDQ_DD_NAME}, which is what
     *     {@code app/csd/CARDDEMO.CSD:L501} declares
     * @param charset the code page the 80-byte records are encoded in
     * @param recordLength the {@code RECORDSIZE}; must be exactly {@value #TDQ_RECORD_LENGTH}
     * @param recordFormat the {@code RECORDFORMAT}; must be {@code FIXED}
     * @param blockFormat the {@code BLOCKFORMAT}; must be {@code UNBLOCKED}
     * @param disposition the {@code DISPOSITION}; must be {@code MOD}, which is what makes the writer
     *     append rather than truncate
     * @param approvedRoot the one directory tree beneath which this module will write job-submission output
     * @param destination the file the port appends 80-byte records to
     */
    @ConfigurationProperties(prefix = "carddemo.job-submission", ignoreUnknownFields = false)
    public record JobSubmissionProperties(
            String queueName,
            String ddName,
            String charset,
            int recordLength,
            String recordFormat,
            String blockFormat,
            String disposition,
            String approvedRoot,
            String destination) {
        /**
         * The queue this port stands in for: {@code DEFINE TDQUEUE(JOBS)}
         * ({@code app/csd/CARDDEMO.CSD:L499}), and the only name permitted.
         */
        public static final String TDQ_QUEUE_NAME = "JOBS";

        /**
         * The dataset the queue is bound to: {@code DDNAME(INREADER)} ({@code app/csd/CARDDEMO.CSD:L501}),
         * and the only name permitted.
         */
        public static final String TDQ_DD_NAME = "INREADER";

        public static final int TDQ_RECORD_LENGTH = 80;

        public static final String TDQ_RECORD_FORMAT = "FIXED";

        public static final String TDQ_BLOCK_FORMAT = "UNBLOCKED";

        public static final String TDQ_DISPOSITION = "MOD";

        static final Set<String> REFERENCE_TREES = Set.of(
                "app/cbl", "app/cpy", "app/cpy-bms", "app/bms", "app/jcl",
                "app/proc", "app/csd", "app/ctl", "app/catlg", "app/data");

        private static final String TRAVERSAL_SEGMENT = "..";

        /**
         * The whole validity contract of the port, as one method a unit test can drive directly with no
         * application context in the picture.
         *
         * @throws IllegalStateException on the first violation found, naming the property key at fault, the
         *     rejected value and what is required instead
         */
        public void validate() {
            requirePresent(queueName, "queue-name",
                    "It is the CICS transient data queue this port stands in for - TDQUEUE(JOBS) in "
                            + "app/csd/CARDDEMO.CSD - and it is what a diagnostic names when a "
                            + "submission fails.");
            requirePresent(ddName, "dd-name",
                    "It is the DDNAME the CSD binds the queue to - DDNAME(INREADER) - and it is the "
                            + "dataset the region's internal reader consumes.");
            requireCsdIdentity();
            requireQueueCharset();
            requireByteContract();
            if (isUnconfigured()) {
                return;
            }
            Path root = requireUsablePath(approvedRoot, "approved-root");
            Path target = requireUsablePath(destination, "destination");
            requireContainment(root, target);
        }

        private boolean isUnconfigured() {
            return !StringUtils.hasText(approvedRoot) && !StringUtils.hasText(destination);
        }

        /**
         * The destination as an absolute, normalized path, for the writer that opens it.
         *
         * @return the normalized destination path
         * @throws InvalidPathException if called on an instance that was never validated and whose
         *     destination is not a syntactically valid path
         */
        public Path destinationPath() {
            return Paths.get(destination).normalize();
        }

        /**
         * The approved root as an absolute, normalized path, for the writer that opens beneath it.
         *
         * @return the normalized approved-root path
         * @throws InvalidPathException if called on an instance that was never validated and whose approved
         *     root is not a syntactically valid path
         */
        public Path approvedRootPath() {
            return Paths.get(approvedRoot).normalize();
        }

        private static void requirePresent(String value, String key, String purpose) {
            if (!StringUtils.hasText(value)) {
                throw new IllegalStateException(invalid(key)
                        + " it declares no value. " + purpose);
            }
        }

        /**
         * Requires the queue and DD names to be the ones the CSD defines, not merely present.
         *
         * @throws IllegalStateException if either name is not the CSD's
         */
        private void requireCsdIdentity() {
            if (!TDQ_QUEUE_NAME.equalsIgnoreCase(queueName)) {
                throw new IllegalStateException(invalid("queue-name") + " it is '" + queueName
                        + "', but app/csd/CARDDEMO.CSD:L499 defines TDQUEUE(" + TDQ_QUEUE_NAME
                        + ") and app/cbl/CORPT00C.cbl:L517-L523 writes to that queue by literal. This "
                        + "port stands in for exactly one queue; renaming it here would not repoint "
                        + "anything, it would only misdescribe where the records go.");
            }
            if (!TDQ_DD_NAME.equalsIgnoreCase(ddName)) {
                throw new IllegalStateException(invalid("dd-name") + " it is '" + ddName
                        + "', but app/csd/CARDDEMO.CSD:L501 binds TDQUEUE(" + TDQ_QUEUE_NAME
                        + ") to DDNAME(" + TDQ_DD_NAME + "). TYPE(EXTRA) means the records leave CICS "
                        + "onto that dataset, so the DD name is the internal reader's own; it is not a "
                        + "label a deployment chooses.");
            }
        }

        private void requireQueueCharset() {
            requirePresent(charset, "charset",
                    "It is the code page the 80-byte records are encoded in, and it cannot be inferred: "
                            + "a region whose internal reader consumes EBCDIC needs IBM037 where one "
                            + "reading ASCII needs US-ASCII, and guessing would emit the right bytes on "
                            + "one deployment and the wrong ones on the next.");
            Charset resolved;
            try {
                resolved = Charset.forName(charset.trim());
            } catch (IllegalArgumentException unknownCharset) {
                throw new IllegalStateException(invalid("charset") + " '" + charset + "' is not a code "
                        + "page this platform provides (" + unknownCharset.getClass().getSimpleName()
                        + "). Name one the JVM supports - US-ASCII for an ASCII internal reader, "
                        + "IBM037 for an EBCDIC one.", unknownCharset);
            }
            try {
                RecordImageForm.requireSingleByteCodePage(resolved);
            } catch (IllegalArgumentException notSingleByte) {
                throw new IllegalStateException(invalid("charset") + " " + notSingleByte.getMessage()
                        + " TDQUEUE(" + TDQ_QUEUE_NAME + ") declares RECORDFORMAT("
                        + TDQ_RECORD_FORMAT + ") with RECORDSIZE(" + TDQ_RECORD_LENGTH + "), so "
                        + TDQ_RECORD_LENGTH + " characters must encode to exactly " + TDQ_RECORD_LENGTH
                        + " bytes.", notSingleByte);
            }
        }

        /**
         * The code page the records are encoded in, resolved.
         *
         * @return the charset
         * @throws IllegalArgumentException if called on an instance that was never validated and whose
         *     charset names nothing this platform provides
         */
        public Charset queueCharset() {
            return Charset.forName(charset.trim());
        }

        private void requireByteContract() {
            if (recordLength != TDQ_RECORD_LENGTH) {
                throw new IllegalStateException(invalid("record-length") + " it is " + recordLength
                        + ", but TDQUEUE(JOBS) declares RECORDSIZE(" + TDQ_RECORD_LENGTH + ") in "
                        + "app/csd/CARDDEMO.CSD:L499-L505. CORPT00C composes JCL skeleton records of "
                        + "exactly that width, so any other value would emit records the CICS write "
                        + "never produced (gate G42).");
            }
            if (!TDQ_RECORD_FORMAT.equalsIgnoreCase(recordFormat)) {
                throw new IllegalStateException(invalid("record-format") + " it is '" + recordFormat
                        + "', but TDQUEUE(JOBS) declares RECORDFORMAT(" + TDQ_RECORD_FORMAT + "). A "
                        + "variable-length record would make the 80-byte width advisory rather than "
                        + "structural.");
            }
            if (!TDQ_BLOCK_FORMAT.equalsIgnoreCase(blockFormat)) {
                throw new IllegalStateException(invalid("block-format") + " it is '" + blockFormat
                        + "', but TDQUEUE(JOBS) declares BLOCKFORMAT(" + TDQ_BLOCK_FORMAT + "). "
                        + "Blocking would insert block descriptors between records, so the output "
                        + "would no longer be a plain sequence of 80-byte records.");
            }
            if (!TDQ_DISPOSITION.equalsIgnoreCase(disposition)) {
                throw new IllegalStateException(invalid("disposition") + " it is '" + disposition
                        + "', but TDQUEUE(JOBS) declares DISPOSITION(" + TDQ_DISPOSITION + "). MOD "
                        + "appends; anything else would discard records a previous submission had "
                        + "already queued for the internal reader.");
            }
        }

        private static Path requireUsablePath(String raw, String key) {
            if (!StringUtils.hasText(raw)) {
                throw new IllegalStateException(invalid(key) + " it declares no path. Neither the "
                        + "approved root nor the destination is defaulted inside Java: a port that "
                        + "appends 80-byte records to a file must be told which file, by "
                        + "configuration, every time.");
            }
            Path path;
            try {
                path = Paths.get(raw);
            } catch (InvalidPathException malformed) {
                throw new IllegalStateException(invalid(key) + " '" + raw + "' is not a valid path on "
                        + "this platform. An unresolved environment placeholder or a stray control "
                        + "character is the usual cause.", malformed);
            }
            if (containsTraversal(path)) {
                throw new IllegalStateException(invalid(key) + " '" + raw + "' contains a '"
                        + TRAVERSAL_SEGMENT + "' segment. Upward traversal is refused before the path "
                        + "is normalized, because normalizing resolves it away and would leave the "
                        + "configuration reading as though output went somewhere it does not. State "
                        + "the intended location directly.");
            }
            if (!path.isAbsolute()) {
                throw new IllegalStateException(invalid(key) + " '" + raw + "' is relative. It would "
                        + "resolve against the process working directory, which is set by whoever "
                        + "launches the process rather than by this configuration - so the same "
                        + "configuration would write to different files on different machines.");
            }
            Path normalized = path.normalize();
            if (namesReferenceTree(normalized)) {
                throw new IllegalStateException(invalid(key) + " '" + raw + "' resolves inside a "
                        + "read-only reference tree. The COBOL programs, copybooks, BMS mapsets, JCL, "
                        + "procs, the CSD, the control and catalogue files and the data fixtures are "
                        + "the only oracle this migration has for behavioural equivalence, and this "
                        + "port appends to whatever it is given (practice B3). Rejected trees: "
                        + sorted(REFERENCE_TREES) + ".");
            }
            return normalized;
        }

        private static void requireContainment(Path root, Path target) {
            if (target.equals(root)) {
                throw new IllegalStateException(invalid("destination") + " it is the approved root "
                        + "itself, '" + root + "'. The root names the directory tree output may go "
                        + "in; the destination names the file inside it that records are appended to, "
                        + "so the two cannot be the same path.");
            }
            if (!target.startsWith(root)) {
                throw new IllegalStateException(invalid("destination") + " it resolves to '" + target
                        + "', which is outside the approved root '" + root + "'. Relocating "
                        + "job-submission output is a deliberate change of approved-root, not "
                        + "something a destination may do on its own - that is what stops an "
                        + "externally supplied value from choosing which file receives appended "
                        + "records.");
            }
        }

        private static boolean containsTraversal(Path path) {
            for (Path element : path) {
                if (TRAVERSAL_SEGMENT.equals(element.toString())) {
                    return true;
                }
            }
            return false;
        }

        private static boolean namesReferenceTree(Path normalized) {
            int elements = normalized.getNameCount();
            for (int index = 0; index + 1 < elements; index++) {
                String pair = normalized.getName(index) + "/" + normalized.getName(index + 1);
                if (REFERENCE_TREES.contains(pair)) {
                    return true;
                }
            }
            return false;
        }

        private static String invalid(String key) {
            return "The carddemo.job-submission." + key + " value is invalid:";
        }

        private static List<String> sorted(Set<String> values) {
            return values.stream().sorted().toList();
        }
    }
}
