package com.vsergeychik.carddemo.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonStreamContext;
import com.fasterxml.jackson.core.JsonToken;
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

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
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
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web-layer configuration for the 17 CICS online transactions that became stateless REST
 * controllers: JSON mapping, one global error mapping, and the single {@link Clock} the screen
 * header depends on.
 *
 * <h2>What this class is for</h2>
 * The 17 programs in {@code app/cbl} that contain {@code EXEC CICS} statements each drove a BMS
 * mapset. Those mapsets are the presentation contract of this migration, and they are far stricter
 * than a component library would be: every mapset declares a single
 * {@code DFHMDI COLUMN=1, LINE=1, SIZE=(24,80)} - verified in exactly 17 files under
 * {@code app/bms} - and every screen field is a {@code DFHMDF} carrying {@code ATTRB},
 * {@code COLOR}, {@code LENGTH}, {@code POS} and optionally {@code INITIAL}. Counting the
 * label-bearing definitions across those 17 mapsets yields exactly <strong>441</strong> fields,
 * for example 11 in {@code COSGN00.bms}, 54 in {@code COACTUP.bms} and 59 in {@code COTRN00.bms}.
 *
 * <p>Every request and response field of every controller is a 1:1 projection of one of those 441
 * definitions, with its name and length taken from the corresponding {@code xxxI PIC X(n)} item in
 * the symbolic map under {@code app/cpy-bms}. The {@code xxxL COMP PIC S9(4)} length item, the
 * {@code xxxF} flag byte and the {@code xxxA} attribute redefinition are validation and
 * field-highlight <em>metadata</em>; they are never JSON payload members. Every Jackson setting
 * below exists to keep that projection byte-faithful.
 *
 * <h2>Why there is deliberately no {@code @EnableWebMvc}</h2>
 * {@code @EnableWebMvc} switches Spring Boot's MVC auto-configuration <em>off</em> and hands the
 * whole configuration over to the application. That would discard the auto-configured HTTP message
 * converters - including the Jackson converter this class spends its length configuring - and every
 * one of the 17 controllers would stop mapping JSON correctly. This class therefore implements
 * {@link WebMvcConfigurer} to <em>augment</em> the auto-configuration and never to replace it. It
 * is discovered through the component scan of the {@code config} package.
 *
 * <p>For the same reason, {@link WebMvcConfigurer} is implemented with <strong>no overrides at
 * all</strong>. There is no view resolver, no static-resource handler, no CORS registry and no
 * content-negotiation override, because there is nothing in this repository for any of them to do:
 * the estate contains no web user interface, no component library and no design tokens, so the only
 * representation is JSON. An empty override set is the correct outcome here, not an omission.
 *
 * <h2>The division of labour with {@code application.yml}</h2>
 * Three Jackson decisions are expressed in {@code src/main/resources/application.yml}, which names
 * this class as their consumer, and are deliberately <em>not</em> repeated here:
 * <ul>
 *   <li>{@code default-property-inclusion: always} - a fixed-width screen projection must emit
 *       every field, including the empty and the all-spaces ones. A global {@code NON_NULL} or
 *       {@code NON_EMPTY} inclusion would silently drop a {@code DFHMDF} field from a payload.</li>
 *   <li>No property-naming strategy, deliberately. Payload names stay exactly as the DTOs declare
 *       them, which is exactly as the symbolic maps name them. Rewriting a name to snake or kebab
 *       case would break the 1:1 traceability from payload field back to {@code DFHMDF}
 *       definition.</li>
 *   <li>{@code fail-on-unknown-properties: true} - a field tracing to no {@code DFHMDF} definition
 *       is an error rather than something to ignore. That is an explicit, deliberate choice rather
 *       than a framework default, so this class does not touch the feature and does not quietly
 *       change request-parsing strictness.</li>
 * </ul>
 *
 * <p>What remains, and what {@link #carddemoJacksonCustomizer()} therefore sets, are the two
 * numeric settings that guard the migration's arithmetic. See that method for the detail.
 *
 * <h2>Character encoding: HTTP is UTF-8, and nothing here says otherwise</h2>
 * This is worth stating explicitly, because the mistake is an inviting one in a mainframe
 * migration. {@code IBM037} and {@code US-ASCII} belong <strong>exclusively</strong> to dataset
 * input and output, where they are selected by {@link CobolCharsetConfig}. They must never be
 * applied to an HTTP request, an HTTP response, the servlet layer or a message converter: doing so
 * would corrupt every byte of every response. JSON on the wire stays UTF-8, and neither code page
 * is named anywhere in this file.
 *
 * <h2>Statelessness</h2>
 * CICS is pseudo-conversational, and the conversation state of these 17 programs is exactly three
 * things: the {@code CARDDEMO-COMMAREA} fields from {@code COCOM01Y}, the {@code EIBAID} value
 * naming the key that was pressed, and the screen's own field values. All three travel in the
 * request and response payloads - through {@code common/NavigationContext} and
 * {@code card/dto/CardScreenState} - and none of them is held on the server. Accordingly this class
 * registers no {@code HttpSession} usage, no session-scoped bean, no session-backed interceptor and
 * no server-side screen-state cache. Reintroducing server-side state would reintroduce exactly the
 * session affinity the migration removes.
 *
 * <h2>Security posture</h2>
 * No authentication or authorization machinery appears here. Sign-on remains the plaintext
 * {@code SEC-USR-PWD PIC X(08)} comparison against {@code USRSEC} that {@code COSGN00C} performs,
 * implemented in the sign-on service; hashing it would be a behaviour change, and a filter chain
 * would be a framework this migration does not include. Equally, nothing here weakens the posture,
 * and two decisions below actively hold it:
 * <ul>
 *   <li><strong>No failure discloses anything internal.</strong> {@code application.yml} sets all
 *       three of {@code server.error.include-message}, {@code include-binding-errors} and
 *       {@code include-stacktrace} to {@code never}, so Boot's generic {@code /error} endpoint
 *       reveals nothing, and {@link CobolErrorHandler} claims the failure families that would
 *       otherwise reach it - answering each with fixed text rather than with an exception's
 *       message. The distinction that makes this safe rather than lossy is that an online
 *       program's parity-relevant text is painted by the controller into its own response payload
 *       and never sourced from a {@code Throwable}.</li>
 *   <li><strong>The one externally supplied path is validated before it can be used.</strong>
 *       {@link JobSubmissionProperties} is the {@code CORPT00C} transient-data-queue port's
 *       contract, and its destination is the only value in this module an operator hands in from
 *       outside the process. It is checked at context refresh for absoluteness, traversal,
 *       reference-tree targets and containment in an approved root, because the writer that
 *       eventually opens it appends in {@code MOD}.</li>
 * </ul>
 *
 * @see CobolCharsetConfig for dataset encoding, which is a separate concern from HTTP encoding
 */
@Configuration
@EnableConfigurationProperties(WebConfig.JobSubmissionProperties.class)
public class WebConfig implements WebMvcConfigurer {

    /**
     * Constructs the configuration. Declared explicitly to document that it takes no arguments and
     * holds no state: this class contributes bean definitions and nothing else, and it keeps no
     * mutable field, static or otherwise.
     */
    public WebConfig() {
        // Intentionally empty. All configuration is expressed by the @Bean methods below.
    }

    /**
     * Applies the Jackson settings that protect the migration's numeric parity and keep a request body
     * from being read as something other than what was sent.
     *
     * <p>Customising the builder rather than replacing the {@code ObjectMapper} bean is deliberate:
     * it leaves Spring Boot's other message converters and its own {@code spring.jackson.*} handling
     * intact, and it keeps both decisions readable in one place. A customizer bean that declares no
     * order is applied after Boot's own, so these two settings are the last word on the features
     * they name.
     *
     * <h4>{@code USE_BIG_DECIMAL_FOR_FLOATS} - the most consequential setting in this file</h4>
     * Without it, Jackson materialises a JSON number with a fractional part as a {@code double}.
     * Every monetary field in this estate derives from a {@code PIC S9(p)V99} clause, and a
     * {@code double} cannot represent a decimal fraction exactly, so binding one through a
     * {@code double} would corrupt the value before any business logic ran. Enabling this feature
     * makes the deserializer produce {@link java.math.BigDecimal}, which is the only representation
     * the migration permits for a value derived from a {@code PIC 9...V...} field.
     *
     * <h4>{@code WRITE_BIGDECIMAL_AS_PLAIN} - scale 2 must survive the round trip</h4>
     * Exhaustive extraction of the signed decimal pictures across all 28 programs found only three
     * forms - {@code PIC S9(10)V99}, {@code PIC S9(09)V99} and {@code PIC S9(9)V99} - so every
     * monetary field is scale 2. This feature forces plain decimal notation on output, so a value
     * serialises as {@code 100.00} and never as {@code 1.0E+2}. Nothing here strips trailing zeros:
     * a scale-2 amount must still read back at scale 2, because the scale is part of the value.
     *
     * <h4>{@code ACCEPT_EMPTY_STRING_AS_NULL_OBJECT} - disabled, and stated rather than assumed</h4>
     * A {@code PIC X(n)} field is space-padded to its declared width, and it is not trimmed on read
     * unless the COBOL itself trims. An empty or all-spaces value is therefore meaningful data, not
     * an absent value. Coercing {@code ""} to {@code null} would discard a real screen field, so the
     * feature is disabled explicitly instead of being left to a default. For the same reason no
     * trimming deserializer and no string converter is registered anywhere in this module.
     *
     * <h4>{@code FAIL_ON_TRAILING_TOKENS} - a body is one screen, not a screen and then something else</h4>
     * Jackson stops reading at the end of the root value by default and discards whatever follows, so
     * {@code {"fName":"AA"} DROP TABLE} and {@code {} {"userId":"USER0009"}} were both accepted: the
     * first document bound, the remainder vanished, and the caller was told {@code 200}. Nothing in the
     * response distinguished that from a body the server had read in full, which is the same silent-loss
     * shape this module refuses everywhere else - a member that traces to no {@code DFHMDF} field is
     * refused rather than ignored ({@code spring.jackson.deserialization.fail-on-unknown-properties}),
     * and a value wider than its {@code PICTURE} is refused rather than truncated. Enabling this feature
     * makes trailing content a {@code 400} through {@link CobolErrorHandler}, so a request either bound
     * whole or was refused. It is enabled here rather than in {@code application.yml} so that every
     * production-equivalent mapper a test builds from this customizer inherits it.
     *
     * <h4>{@code STRICT_DUPLICATE_DETECTION} - one member states one value, or the request is refused</h4>
     * A BMS map declares exactly one storage item per named field, and a communication area has one of
     * each of its members, so {@code {"userid":"USER0001","userid":"ADMIN001"}} describes a screen that
     * cannot exist. Jackson's default is to accept it and keep the last occurrence, which discards the
     * other value with nothing in the response saying so - and on a password, a record key, an attention
     * identifier or a navigation member, which of the two survived decides what the request does
     * (CWE-20). Enabling this feature makes a repeated member a {@code 400} through
     * {@link CobolErrorHandler}, at both the top level and inside a nested object. It is the same
     * setting the parity harness already hardens its own mapper with for exactly the same reason - a
     * fixture stating two expectations - and it belongs on the production mapper for the stronger one:
     * silent loss of the caller's own input is what this module refuses everywhere else.
     *
     * <p>No date module and no date pattern is registered either: COBOL dates in this estate are
     * {@code PIC X(n)} character fields, and reformatting them through a {@code java.time}
     * serializer would change observable output.
     *
     * <h4>The inbound string boundary carries the active code page</h4>
     * {@link ScreenTextDeserializer} judges every inbound string - is it character data at all, could a
     * terminal have transmitted it, and can the configured code page represent it - and the last
     * question needs the page the deployment actually named. It is therefore injected here, qualified by
     * bean name, and handed to the deserializer as a {@link FixedWidthCodec}: {@code CobolCharsetConfig}
     * publishes three {@link Charset} beans and deliberately marks none of them primary, so an
     * unqualified injection point would fail the context rather than silently receive the wrong one.
     * Naming {@code US-ASCII} or {@code IBM037} in this file instead would put a code page in a place no
     * profile can override, which is the one thing that class exists to prevent (practice
     * <strong>B8</strong>). The page belongs to dataset input and output: it is used for one purpose
     * here - judging whether an inbound screen value is representable in the {@code PIC X(n)} bytes it
     * will occupy - and never for the encoding of the request or the response.
     *
     * @param datasetCharset the active dataset and screen code page, from
     *                       {@code @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME)}; must not
     *                       be {@code null}
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
                .deserializerByType(String.class, screenText);
    }

    /**
     * Judges every inbound JSON string once, at the boundary where the request body is read, for
     * characters no {@code EXEC CICS RECEIVE MAP} could have delivered.
     *
     * <h2>Why the judgement belongs here and not in seventeen controllers</h2>
     * Every payload member of all seventeen screens projects a {@code PIC X(n)} symbolic-map item, and the
     * rules that govern them are two rules that hold for every one of those members. Applied at each
     * controller they would have to be repeated seventeen times over a per-route list of members, and a
     * member left off any one list would be a silent hole - which is precisely the shape of the gap this
     * closes: the code-page judgement used to be wired into three routes and therefore judged nothing on
     * the other fourteen.
     *
     * <p>There is a second reason the controllers are the wrong place, and it is a parity reason. A screen
     * program decides for itself whether it performs an {@code EXEC CICS RECEIVE MAP} at all: a cold start,
     * a first entry from a menu and a function-key transfer all reach {@code SEND} without ever looking at
     * the map's input area. A sweep placed inside the flow ran ahead of that decision and could refuse a
     * field the program was about to ignore. Asked here, the question is the transport-level one it always
     * was - "is this a payload a 3270 could have sent" - and it is settled before the program starts, so no
     * judgement of this kind sits between the program's own guards.
     *
     * <p>Placed here it also names the member with <strong>the caller's own JSON spelling</strong>, because
     * that is the only name in scope at this point: the parser is positioned on the field it just read, so
     * there is no Java property name and no COBOL label to be tempted into the answer. That is the same
     * vocabulary every other arm of {@link CobolErrorHandler} uses.
     *
     * <h2>Why it is a nested type of this file</h2>
     * The {@code config} package's configuration owners are fixed at four - {@link CobolCharsetConfig},
     * {@link DataSourceConfig}, {@code BatchConfig} and this one - so a collaborator that exists only to be
     * registered on the object mapper this file configures belongs inside it, exactly as
     * {@link CobolErrorHandler} and {@link JobSubmissionValidator} do. It is registered by
     * {@link #carddemoJacksonCustomizer(java.nio.charset.Charset)} and by nothing else; it is not a bean.
     *
     * <h2>What is judged and what is not</h2>
     * Strings anywhere in the body - a top-level screen field, a member of {@code navigationContext}, a
     * member of a communication-area extension, an element of an array. An explicit {@code null} token is
     * returned as {@code null} rather than coerced, so the "absent field is spaces on a terminal" behaviour
     * of every screen is untouched.
     *
     * <p><strong>Three questions are asked of every value, and they are different questions.</strong>
     *
     * <ol>
     *   <li><strong>Is it character data at all?</strong> Every payload member projects a {@code PIC X(n)}
     *       item, so a JSON number or boolean is not a screen field with an unusual value - it is not a
     *       screen field. Coercing it would fabricate a field image no {@code RECEIVE MAP} delivered:
     *       {@code 11} written into a {@code PIC X(11)} account filter arrives as two characters where the
     *       screen carries eleven, silently dropping the nine leading zeros that identify the record. Such a
     *       token is refused through
     *       {@link ScreenInputRejectedException#notCharacterData(String, String)}, which names the token
     *       shape in the diagnostic and never in the published answer. A <em>structured</em> token - an
     *       object or an array - is instead handed to
     *       {@link DeserializationContext#handleUnexpectedToken(Class, JsonParser)}, Jackson's own refusal
     *       path, which names this member and this location and surfaces as a mapping failure rather than
     *       inventing a second answer for the same fault.</li>
     *   <li><strong>Could a terminal have transmitted it?</strong> The rule lives in
     *       {@link ScreenInputRejectedException#requireDeliverable(String, String)} rather than here, so it
     *       is one statement, unit-testable without a parser, and shared with any caller that has a value
     *       and a member name. In short: a control character is refused unless it is {@code U+0000} in a
     *       trailing run, which is the one shape a real conversation produces, because BMS delivers an
     *       unmodified field as all-nulls and this module renders an unpainted field the same way.</li>
     *   <li><strong>Can the configured code page represent it?</strong> Every payload member projects a
     *       {@code PIC X(n)} item, which is {@code n} <em>bytes</em> in a single-byte code page, so a
     *       character that page cannot encode is a value no {@code RECEIVE MAP} could have delivered into
     *       the field - only a hand-built payload reaches it. The code page is the active dataset page
     *       {@link CobolCharsetConfig} publishes, injected as a {@link FixedWidthCodec} rather than assumed
     *       (practice <strong>B8</strong>), and the judgement is
     *       {@link FixedWidthCodec#firstUnrepresentableCodePoint(String)}.</li>
     * </ol>
     *
     * <p>The second question used to be asked by three of the seventeen controllers over their own
     * per-route field lists, and by the other fourteen not at all - so fourteen screens accepted Unicode
     * their configured 3270 code page cannot deliver, and two of the three asked it against a hard-coded
     * {@code US-ASCII} rather than the page actually in force. Both halves of that gap close by asking it
     * here: once, over every string of every body, against the one code page the deployment named.
     *
     * <h2>The one member neither question is asked of, and why</h2>
     * {@value #ATTENTION_IDENTIFIER_MEMBER} is not a screen field. It is {@code EIBAID}, one byte of the
     * CICS exec interface block, which CICS reports <em>alongside</em> the map and not inside it - every
     * request record in this module says so where it declares the member, and none of the 441
     * {@code DFHMDF} definitions across the seventeen mapsets declares a field by that name. The
     * code-page question is therefore not applicable to it rather than merely inconvenient: the value is a
     * byte carried as the one character whose code point <em>is</em> that byte
     * [{@code common.PfKeyResolver#aidImage(byte)}], so its code point is a byte value in the range
     * {@code U+0000}-{@code U+00FF} and not text the deployment's page has to be able to spell. Asking the
     * question anyway made {@code DFHPF3} - {@code X'F3'}, carried as {@code U+00F3} - unsendable wherever
     * the configured page is single-byte ASCII, which would have made every function-key arm of every
     * screen unreachable over HTTP on that configuration.
     *
     * <p>The first question is not asked of it either, and for the same reason rather than a different one:
     * "could a terminal have transmitted this into a {@code PIC X} field" is a question about a field, and
     * two of the AIDs this application reproduces are themselves control code points - {@code DFHTRIG} is
     * {@code X'7F'} and {@code DFHSTRF} is {@code X'88'}. No program in {@code app/cbl} tests either, so both
     * belong on {@code WHEN OTHER}, and refusing the request outright with a {@code 400} instead of answering
     * with the screen's own invalid-key message would be a failure mode no terminal can produce.
     *
     * <p>What governs the member instead is the rule each controller applies to it - exactly one character,
     * within the one-byte AID space, and anything else is {@code DFHNULL} and therefore {@code WHEN OTHER} -
     * which is a stricter statement than either question here, not a weaker one. So nothing is unjudged: the
     * member is judged by the program that reads it, in the terms that program uses.
     *
     * <h2>How the refusal reaches the caller</h2>
     * A {@link JsonDeserializer} may only fail through Jackson, so the refusal is wrapped in a mapping
     * failure and surfaces to Spring as {@code HttpMessageNotReadableException}.
     * {@link CobolErrorHandler} unwraps it and answers with the same
     * {@code 400 REJECTED_VALUE} body, naming the same member, that a value refused inside a controller
     * produces - so the envelope has one shape however deep the refusal was raised.
     *
     * <h2>Why this does not narrow the API</h2>
     * A route's own {@code 200} response must remain a legal next request, and those responses are full of
     * {@code U+0000}: an unpainted field is rendered as {@code LOW-VALUES} at its declared width. Those
     * values are accepted, by the trailing-run exemption, and that property was re-verified across all
     * seventeen routes after this class was introduced. What is refused is a shape no response of this API
     * ever produces and no terminal can send.
     *
     * <p>Stateless and immutable - its one field is an immutable codec - so the single instance registered
     * on the object mapper is safe for concurrent use.
     *
     * @see ScreenInputRejectedException#requireDeliverable(String, String)
     * @see ScreenInputRejectedException#requireRepresentable(String, String, String, FixedWidthCodec)
     */
    static final class ScreenTextDeserializer extends JsonDeserializer<String> {

        /** The member name used when the parser is positioned somewhere that has no field name. */
        static final String UNNAMED_MEMBER = "requestBody";

        /**
         * The one member whose value is a byte rather than screen text: the {@code EIBAID} carrier.
         *
         * <p>Spelled the same way by all seventeen request records, which is what lets the exemption be
         * stated once here rather than annotated seventeen times. {@code ScreenTextDeserializerTest} holds
         * that spelling against every request record, so a screen that renamed it would fail rather than
         * quietly start being judged as text.
         */
        static final String ATTENTION_IDENTIFIER_MEMBER = "aid";

        /**
         * The symbolic-map item name reported in the server-side diagnostic.
         *
         * <p>A generic name rather than a per-member one, because at this point the only name in scope is
         * the caller's own JSON spelling: the parser is positioned on a field, not on a copybook. The
         * per-item name is still reported where it is known - the controller sweeps pass the label with
         * {@code I} appended - and it is a diagnostic either way, never part of the published answer.
         */
        static final String SCREEN_ITEM = "a PIC X(n) screen item";

        /**
         * The code page every value is judged against - the active dataset page, injected, never assumed.
         *
         * <p>Immutable and stateless, like the instance holding it, so the single deserializer registered on
         * the shared object mapper stays safe for concurrent use.
         */
        private final FixedWidthCodec codec;

        /**
         * @param codec the codec carrying the active screen and dataset code page, published by
         *              {@link CobolCharsetConfig} under
         *              {@link CobolCharsetConfig#DATASET_CHARSET_BEAN_NAME} and passed in by
         *              {@link #carddemoJacksonCustomizer(java.nio.charset.Charset)}; must not be
         *              {@code null}
         * @throws NullPointerException if {@code codec} is {@code null}
         */
        ScreenTextDeserializer(final FixedWidthCodec codec) {
            this.codec = Objects.requireNonNull(codec, "A FixedWidthCodec is required: the code page an "
                    + "inbound screen value is judged against is stated explicitly and never taken from the "
                    + "platform default");
        }

        /**
         * The code page this boundary judges against.
         *
         * <p>Exposed package-visibly so a test can assert that the deserializer the production customizer
         * registered carries the profile's own page rather than a lookalike.
         *
         * @return the codec, never {@code null}
         */
        FixedWidthCodec codec() {
            return codec;
        }

        /**
         * Reads one JSON string and judges it before it becomes a payload value.
         *
         * <p>A token that is not a JSON string is refused rather than coerced: a structured value - an
         * object or an array - through {@link DeserializationContext#handleUnexpectedToken(Class,
         * JsonParser)}, and any other non-string scalar through
         * {@link ScreenInputRejectedException#notCharacterData(String, String)}. Nothing about an accepted
         * value is altered; the three judgements are all that is added.
         *
         * <p>The deliverability and representability judgements are skipped for
         * {@value #ATTENTION_IDENTIFIER_MEMBER}, which carries a byte of
         * the exec interface block rather than a {@code PIC X(n)} screen field, and is judged instead by the
         * controller that reads it - see the class comment.
         *
         * @param parser  the parser, positioned on the value; must not be {@code null}
         * @param context the deserialization context; must not be {@code null}
         * @return the string as sent, unchanged, or {@code null} for a JSON {@code null}
         * @throws IOException                  if the underlying parser fails, or if a structured token is
         *                                      refused through Jackson's unexpected-token handling
         * @throws ScreenInputRejectedException if the value carries a character no terminal could transmit, or
         *                                      one the configured code page cannot represent; neither is
         *                                      asked of {@value #ATTENTION_IDENTIFIER_MEMBER}
         */
        @Override
        public String deserialize(final JsonParser parser, final DeserializationContext context)
                throws IOException {

            if (parser.hasToken(JsonToken.VALUE_NULL)) {
                return null;
            }

            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                // A screen field is character data or it is not a screen field. Refused rather than coerced:
                // getValueAsString would turn 11 into "11" and true into "true", fabricating a field image no
                // RECEIVE MAP delivered. A structured token goes to Jackson's own unexpected-token handling
                // instead, which names this member and this location and answers through the mapping-failure
                // arm rather than inventing a second answer for one fault; anything else is refused here,
                // with the token shape named in the diagnostic only.
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
                // EIBAID, not a screen field: a byte carried as one character. Judged by the controller that
                // reads it - one character inside the one-byte AID space, anything else DFHNULL - and not by
                // either of the two PIC X(n) questions this class asks of screen text. See the class comment.
                return value;
            }
            ScreenInputRejectedException.requireDeliverable(member, value);
            ScreenInputRejectedException.requireRepresentable(member, SCREEN_ITEM, value, codec);
            return value;
        }

        /**
         * The name of the member being read, as the caller spelled it in the request body.
         *
         * <p>Walks outwards from the parser's current context to the nearest named field, so an element of
         * an array is attributed to the array's own member rather than to nothing. A body that is a bare
         * string, with no field name anywhere, falls back to {@link #UNNAMED_MEMBER}: no screen accepts such
         * a body, so this is a name for the unreachable case rather than a name a caller will see.
         *
         * @param parser the parser, positioned on the value; must not be {@code null}
         * @return the member name, never {@code null} and never blank
         */
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
     * <p>{@code common/DateHeader} is the Java form of the {@code WS-DATE-TIME} group in
     * {@code CSDAT01Y}, which supplies the {@code CURDATE} and {@code CURTIME} fields that sit in
     * the top-right corner of all 17 screens. It never reads the clock of its own accord: its
     * factory takes a {@link Clock} and reads it exactly once, so that a test can pass
     * {@code Clock.fixed(...)} and assert an exact rendered header. That design needs one
     * {@link Clock} in the context, and this is it.
     *
     * <p>It returns {@link Clock#systemDefaultZone()} and never a fixed instant: a fixed clock in
     * the production context would freeze the date on every screen. The bean is an instance method
     * returning a new value, not a static field - this module holds no static mutable state - and a
     * {@link Clock} is immutable and thread-safe, so one shared instance serves every concurrent
     * request safely.
     *
     * <p>There must be exactly one definition of this bean in the module. Spring Boot disables
     * bean-definition overriding by default, so a second one would fail the context at startup
     * rather than quietly win; this class owns it because the header it feeds is what renders the
     * online screens.
     *
     * @return the system clock in the platform's default time zone
     */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }

    /**
     * Validates the job-submission port's contract once, at context refresh, so an unusable
     * destination is a startup failure rather than a surprise at the moment a report is requested.
     *
     * <h4>Why a separate bean rather than an init method on the properties type</h4>
     * {@link JobSubmissionProperties} is a {@code record}, and keeping it one is deliberate: it is a
     * bound value object with no setter, no mutable field and no framework interface, so a unit test
     * can construct any combination of values directly and drive {@link
     * JobSubmissionProperties#validate()} with no application context in the picture. Having the
     * record itself implement {@link InitializingBean} would work, but it would tie the value object
     * to the container for no gain. This bean supplies the startup hook instead, exactly as
     * {@code BatchConfig} does for the job-contract graph.
     *
     * <h4>Why validation happens now and not at first write</h4>
     * The destination is externally supplied - {@code CARDDEMO_JOB_SUBMISSION_DESTINATION} - and the
     * writer that will use it appends in {@code MOD}, per {@code DISPOSITION(MOD)} in
     * {@code app/csd/CARDDEMO.CSD:L499-L505}. A wrong value therefore does not fail, it succeeds
     * against the wrong file: 80 bytes are appended to whatever was named. Checking at refresh means
     * a deployment that names an unsafe path never reaches the point of writing to it.
     *
     * @param properties the bound port contract
     * @return the initializing bean whose only job is to run the validation
     */
    @Bean
    public JobSubmissionValidator jobSubmissionValidator(final JobSubmissionProperties properties) {
        return new JobSubmissionValidator(properties);
    }

    /**
     * Runs {@link JobSubmissionProperties#validate()} at context refresh.
     *
     * <p>It holds the properties in a {@code final} field and does nothing else - no state is
     * mutated, nothing is cached and nothing is written - so it is safe as a singleton and carries no
     * behaviour a test would need to isolate.
     */
    static final class JobSubmissionValidator implements InitializingBean {

        /** The bound port contract to validate. */
        private final JobSubmissionProperties properties;

        /**
         * Captures the contract to validate.
         *
         * @param properties the bound {@code carddemo.job-submission} contract; must not be
         *                   {@code null}
         */
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
     * The global error mapping: it turns an abend and a validation failure into an HTTP response
     * without altering any text the COBOL would have produced.
     *
     * <h2>Why this is a nested type, and how it is registered</h2>
     * It is nested inside {@code WebConfig.java} rather than being a file of its own because the
     * {@code config} package's configuration owners are fixed at four - {@link CobolCharsetConfig},
     * {@link DataSourceConfig}, {@code BatchConfig} and {@link WebConfig} - and the error mapping
     * belongs with the rest of the web-layer configuration rather than becoming a fifth. The same
     * reasoning keeps {@link JobSubmissionValidator} and {@link ScreenTextDeserializer} inside this
     * file. The package's one other member, {@code DatasetUnitOfWork}, is not a configuration owner
     * at all: it is the {@code @Component} that carries the transaction boundary a locking read runs
     * inside, and it is named here so that this statement describes the package as it actually is.
     *
     * <p>Registration comes from the {@code @RestControllerAdvice} annotation alone, and it is
     * reliable by two independent routes, so it does not depend on which one applies:
     * <ul>
     *   <li>When the {@code config} package is component-scanned - what the application does -
     *       {@code @RestControllerAdvice} is meta-annotated with {@code @Component} and a
     *       <em>static</em> nested class is an independent candidate, so the scanner registers this
     *       type under the name {@code webConfig.CobolErrorHandler}.</li>
     *   <li>When {@link WebConfig} is registered directly instead, with no scan at all, Spring's
     *       configuration-class parser processes the member classes of a {@code @Configuration}
     *       class and registers this one because it is itself a component candidate.</li>
     * </ul>
     *
     * <p>Both routes together still yield <strong>one</strong> bean, because the scanner's
     * definition takes precedence over the member-class one rather than adding to it. All of this
     * was measured rather than assumed: a context built with default scan filters over this package
     * reports exactly one definition of this type, one {@code Clock}, and - importantly - zero beans
     * for the three nested records, which carry no stereotype annotation and are therefore correctly
     * ignored as bean candidates.
     *
     * <p>A {@code @Bean} factory method on {@link WebConfig} was written first and then deliberately
     * removed, because it registered the advice a <strong>second</strong> time under a second bean
     * name, leaving two identical advice instances in the exception resolver's cache - redundant, and
     * misleading to anyone reading the factory method as the sole registration. Renaming that method
     * to collide with the scan name would have been worse still: Spring Boot disables
     * bean-definition overriding, so the duplicate name would fail the context at startup. One
     * annotation, one bean.
     *
     * <p>Two consequences worth knowing when editing. This type must stay {@code public static}: an
     * inner, non-static class is not an independent candidate and would silently stop being
     * registered. And the nested records must stay free of stereotype annotations, or the scanner
     * would try to instantiate them as beans and fail on their constructor arguments.
     *
     * <h2>Three rules govern every handler below</h2>
     * <ol>
     *   <li><strong>Message text is observable behaviour.</strong> The COBOL programs report their
     *       own diagnostics - into {@code SYSOUT} for a batch program, into the screen's error field
     *       for an online one - and that text is exactly what the parity harness compares. No
     *       handler here translates, localises, rewords, prefixes, truncates or re-cases it.</li>
     *   <li><strong>Nothing internal escapes, and no rejected value is echoed.</strong> No stack
     *       trace, no exception class name, no framework binding detail and no configuration value
     *       reaches a response body - and neither does the field value that was rejected. A width or
     *       shape guard is handed the value it is judging, so its message names the field, its
     *       declared width and the category of the failure, and never the characters: the values
     *       flowing through these DTOs are card numbers, account identifiers and government-issued
     *       identifiers. This is why {@code application.yml} sets all three of
     *       {@code server.error.include-message}, {@code include-binding-errors} and
     *       {@code include-stacktrace} to {@code never} - the framework's own error attributes would
     *       publish exception text this advice never chose to publish - and it is why every handler
     *       below reads specific accessors rather than serialising an exception or copying its
     *       message.</li>
     * </ol>
     *
     * <p>Rule 1 and rule 2 do not collide, because they describe different text. The text a parity
     * case compares is the COBOL's own - the screen's error field, or a {@code SYSOUT} line - and a
     * controller puts that in its response payload as a modelled field. The text rule 2 withholds is
     * a Java exception's, which no COBOL program has an equivalent of and which no parity case reads.
     *
     * <p>Because the framework attributes are silent, this advice has to answer <em>every</em> shape a
     * request can fail in, not merely the interesting ones: an unreadable body, a path variable of the
     * wrong type, and a value a domain guard rejected all have handlers below. Without them a caller
     * would receive an empty {@code 500} and learn nothing at all, which is its own kind of defect.
     *
     * <p>No handler swallows an exception, and none returns a success status for a failure. Every
     * decision a handler makes is also reachable through a small package-private or public static
     * method, so a plain JUnit test can drive each one directly, with no {@code MockMvc} and no
     * servlet container in the path.
     *
     * <h2>Where the two rules pull in opposite directions, and how that is resolved</h2>
     * The first rule copies text verbatim; the second forbids disclosing internals. They only
     * conflict if one supposes that an arbitrary {@code Throwable}'s message is parity text. It is
     * not. Exactly <strong>two</strong> exception families carry text this estate is judged on, and
     * both are text this module composed itself:
     * <ul>
     *   <li>{@link AbendException}, whose message is the {@code ABENDING PROGRAM ...} line the nine
     *       {@code CALL 'CEE3ABD'} sites produce; and</li>
     *   <li>a Bean Validation failure, whose per-field messages are this module's own rendering of a
     *       symbolic map's field width.</li>
     * </ul>
     * Everything else - a Jackson parse error, a type conversion failure, a JDBC exception, a
     * configuration fault, an unexpected runtime failure - carries a message written by a library or
     * a driver, describing internal state that no COBOL program ever emitted. Those messages are not
     * parity text and must not be published, so the handlers for them answer with a
     * <strong>fixed</strong> sentence declared as a constant on this class and never derived from the
     * exception. Nothing is swallowed: the exception still propagates through the servlet container's
     * own logging, where a diagnosis belongs.
     *
     * <h2>Why a catch-all is present, and why it is status-preserving rather than blanket 500</h2>
     * Without a catch-all, a failure no handler claims falls through to Boot's generic
     * {@code /error} endpoint. That endpoint's body is governed by {@code server.error.*} in
     * {@code application.yml} - now all {@code never} - so it discloses nothing either; the catch-all
     * exists so that such a failure still answers with this module's own body shape rather than
     * Boot's, and so the decision is visible in code rather than resting on three YAML keys.
     *
     * <p>It must not, however, flatten every failure to {@code 500}. Spring MVC signals several
     * ordinary client mistakes as exceptions that already carry their own status - an unknown path
     * ({@code 404}), a wrong method ({@code 405}), an unsupported content type ({@code 415}) - and
     * because an {@code @ExceptionHandler} in an advice is consulted <em>before</em> Spring's default
     * resolver, a naive {@code Exception} handler would silently turn all of them into
     * {@code 500 Internal Server Error}. {@link #statusForFailure(Exception)} therefore reads
     * {@link ErrorResponse#getStatusCode()} when the exception implements that interface and falls
     * back to {@code 500} only when it does not. The status is preserved; only the message is
     * withheld.
     *
     * <h2>One rule for {@code fieldErrors[].field}: the name the caller used</h2>
     * A caller told "a field is wrong" without being told <em>which</em> field cannot act on the answer,
     * and a caller told a name it never sent cannot map it back. So every arm that identifies an input
     * names it, and names it the way the caller spelled it:
     *
     * <ul>
     *   <li><strong>a request-body member</strong> is named by its JSON member - the lowercase
     *       {@code xxxI}-derived name this module publishes. Bean Validation reports the Java property
     *       path instead ({@code userId} for the member {@code userid}), so
     *       {@link #jsonMemberOf(Object, String)} resolves it back through {@code @JsonProperty} before
     *       it is published;</li>
     *   <li><strong>a query parameter</strong> is named by its parameter name, taken from the framework's
     *       own {@link MethodArgumentTypeMismatchException#getName()} or from the constant the guard
     *       tests - {@code eibaid}, {@code eibAid}, {@code eibcalen}. That is a name the caller typed
     *       into the URL, not a Java identifier;</li>
     *   <li><strong>a path variable</strong> is named by the URI template variable the route publishes -
     *       {@code acctId}, {@code cardNum}, {@code userId}, {@code tranId} - or, where the value binds
     *       a screen field, by that field's JSON member.</li>
     * </ul>
     *
     * <p><strong>Naming a field is not the same as quoting a value, and the second is still refused.</strong>
     * The name came from the caller, so returning it discloses nothing; the value may be a card number, a
     * government identifier or a password (CWE-532). Only two exception families name a field here, and
     * both are value-free <em>by construction</em>: Bean Validation, whose entries are built from a
     * constraint code and its declared bound and never from the rejected value, and
     * {@link ScreenInputRejectedException}, which is {@code final} with private constructors and
     * factories that compose their message from a member name, a code page, a code point and a width.
     * The open-ended {@link IllegalArgumentException} family - an arbitrary guard that may have quoted
     * what it was handed - still publishes neither its message nor a field name.
     */
    @RestControllerAdvice
    public static class CobolErrorHandler {

        /**
         * The stable public code of an abend: the Java form of {@code CALL 'CEE3ABD'}.
         *
         * <p>A code rather than prose, so a client can branch on it without parsing English, and a
         * constant rather than a literal, so the value is asserted from one place.
         */
        public static final String ABEND_CODE = "ABEND";

        /**
         * The generic public message of an abend. Deliberately says only what a caller outside the
         * trust boundary is entitled to know: the unit of work did not complete. The program name,
         * the {@code RETURN-CODE} and any underlying reason are server-side diagnostics.
         */
        public static final String ABEND_MESSAGE =
                "The transaction ended abnormally and no work was committed.";

        /** The stable public code of a request body that could not be read at all. */
        public static final String MALFORMED_REQUEST_CODE = "MALFORMED_REQUEST";

        /**
         * How far {@link #screenInputCause(HttpMessageNotReadableException)} follows a cause chain.
         *
         * <p>Jackson wraps a deserializer's failure once or twice - a mapping failure, sometimes inside a
         * value-instantiation failure - so this is generous rather than tight, and it exists to bound the
         * walk rather than to express a real depth.
         */
        private static final int MAX_CAUSE_DEPTH = 8;

        /**
         * The stable public code of a request whose fields were read but did not satisfy the widths and
         * forms the symbolic map declares. Every entry of
         * {@link CobolErrorResponse#fieldErrors()} names one of them.
         */
        public static final String VALIDATION_FAILED_CODE = "VALIDATION_FAILED";

        /**
         * The generic public detail of a validation failure. The per-field messages carry what is
         * actually wrong, so this sentence only says where to look.
         */
        public static final String VALIDATION_FAILED_DETAIL =
                "One or more request fields do not match this screen's payload contract. Each "
                        + "rejected field is named in fieldErrors.";

        /** The stable public code of a value a domain guard refused. */
        public static final String REJECTED_VALUE_CODE = "REJECTED_VALUE";

        /** The stable public code of a value that could not be converted to its declared type. */
        public static final String TYPE_MISMATCH_CODE = "TYPE_MISMATCH";

        /** The stable public code of a failure reaching a dataset. */
        public static final String DATASET_ACCESS_CODE = "DATASET_ACCESS";

        /** The stable public code of a server-side state or configuration fault. */
        public static final String INTERNAL_STATE_CODE = "INTERNAL_STATE";

        /**
         * The stable public code of every other failure - an unknown path, a wrong method, an
         * unsupported media type, an unacceptable {@code Accept} header - whose own status is honoured
         * and whose detail is withheld.
         */
        public static final String REQUEST_NOT_COMPLETED_CODE = "REQUEST_NOT_COMPLETED";

        /**
         * The generic public message of an unreadable request body. It names no property, no parse
         * position, no Java type and no parser: those are the details that would describe the
         * server's internals rather than the caller's mistake.
         */
        public static final String MALFORMED_REQUEST_MESSAGE =
                "The request body could not be read. Send a JSON body whose fields match this "
                        + "screen's payload contract.";

        /**
         * Logger for the diagnostics that are deliberately kept out of every response body.
         *
         * <p>Apache Commons Logging through {@code spring-jcl}, which is the convention the
         * repositories in this module already follow, so no second logging facade enters the build.
         * {@code static final} and a reference to an immutable logger, so it adds no shared mutable
         * state (practice <strong>B9</strong>). It exists because withholding the abending program
         * and its return code from the client must not mean discarding them: a production failure has
         * to stay diagnosable from the server side.
         */
        private static final Log LOG = LogFactory.getLog(CobolErrorHandler.class);

        /**
         * Constructs the advice. It is stateless and holds no instance field, so one instance serves
         * every request; this is declared explicitly rather than left implicit to make that plain.
         */
        /**
         * What is said about a field an unreadable body failed at. Fixed text: the field name is the
         * variable part, and Jackson's own message - which quotes the payload - is never used.
         */
        private static final String UNREADABLE_FIELD_DETAIL =
                "could not be read from the request body";

        /**
         * What is said about a value a domain guard rejected. Fixed text, identical for every
         * occurrence, naming the shape of the fault and no value.
         */
        private static final String REJECTED_VALUE_DETAIL = "A field value does not fit the COBOL "
                + "picture declared for it. Check each field's width and, for a numeric field, that "
                + "it holds only digits with the sign overpunched into the trailing character. The "
                + "rejected value is not echoed here.";

        /**
         * What is said about a server-side state or configuration fault. Fixed text, naming neither
         * the dataset nor the configuration key involved.
         */
        private static final String INTERNAL_STATE_DETAIL = "The server is not in a state to serve "
                + "this request. The detail is in the server log rather than in this response.";

        /**
         * The code Bean Validation reports for {@code jakarta.validation.constraints.Size}, which is
         * every width constraint on every request DTO in this module: a {@code DFHMDF} field declared
         * {@code LENGTH=8} is a {@code @Size(max = 8)}.
         */
        private static final String SIZE_CONSTRAINT = "Size";

        /** The code Bean Validation reports for {@code jakarta.validation.constraints.Pattern}. */
        private static final String PATTERN_CONSTRAINT = "Pattern";

        /** The codes that mean a value had to be there and was not. */
        private static final Set<String> PRESENCE_CONSTRAINTS =
                Set.of("NotNull", "NotBlank", "NotEmpty");

        /** The codes that mean a value was outside a declared numeric range. */
        private static final Set<String> RANGE_CONSTRAINTS = Set.of("Min",
                "Max",
                "DecimalMin",
                "DecimalMax",
                "Digits",
                "Positive",
                "PositiveOrZero",
                "Negative",
                "NegativeOrZero");

        /**
         * What is said about a {@code @Size} breach whose declared maximum is not a quotable width -
         * a constraint that states only a minimum.
         */
        private static final String LENGTH_DETAIL =
                "does not satisfy the length declared for its screen field";

        /** What is said about a value that had to be present. */
        private static final String PRESENCE_DETAIL = "is required";

        /** What is said about a value outside its declared numeric range. */
        private static final String RANGE_DETAIL = "is outside the range declared for its screen field";

        /** What is said about a value that did not match its declared form. */
        private static final String PATTERN_DETAIL =
                "does not match the form declared for its screen field";

        /**
         * What is said about any other violated constraint. This is the default of a total mapping, so
         * a constraint annotation introduced later publishes this rather than its own wording.
         */
        private static final String CONSTRAINT_DETAIL =
                "is not valid for its screen field";

        public CobolErrorHandler() {
            // Intentionally empty. The advice derives every response solely from its argument.
        }

        /**
         * Maps an abend - the Java form of {@code CALL 'CEE3ABD'}, which appears at nine sites
         * across the batch programs - onto {@code 500 Internal Server Error}.
         *
         * <p>{@code 500} is the honest status: the COBOL did not complete its unit of work, it
         * terminated abnormally. The <em>body</em> says that through {@link #ABEND_CODE} and
         * {@link #ABEND_MESSAGE}, and adds one thing more when the abending paragraph produced it:
         * the fixed-width diagnostic the source itself transmitted. The abending program's
         * {@code PROGRAM-ID}, the {@code RETURN-CODE} it had placed in {@code APPL-RESULT} and the
         * composed {@code ABENDING PROGRAM} sentence - which can carry a repository's or a dataset's
         * own reason text, and can nest a further failure's context - are written to the server log
         * by this method and go no further. They describe the inside of the application and belong
         * on the server's side of the trust boundary.
         *
         * @param abend the abend raised by a service, job or repository
         * @return {@code 500} with the stable abend code, its generic message, and the source's own
         *         transmitted diagnostic where there is one
         */
        @ExceptionHandler(AbendException.class)
        public ResponseEntity<CobolErrorResponse> handleAbend(final AbendException abend) {
            logAbend(abend);
            return json(HttpStatus.INTERNAL_SERVER_ERROR, abendResponse(abend));
        }

        /**
         * Builds the abend body, and is the directly testable form of {@link
         * #handleAbend(AbendException)}.
         *
         * <p>Exactly one thing is read from the argument: {@link AbendException#getSourceDiagnostic()},
         * the fixed-width area the COBOL itself transmitted before abending. Nothing else is, and that
         * boundary is the contract. The program name, the {@code RETURN-CODE}, the {@code ABCODE}, the
         * {@code TIMING}, the composed {@code getMessage()} and the whole cause chain are all withheld,
         * so there is no path by which a Java exception message, a JDBC or driver message, a SQLSTATE, a
         * stack frame or a record image can reach a client, however an abend was composed.
         *
         * <p>Why the diagnostic is published rather than withheld with the rest:
         * {@code app/cbl/COCRDSLC.cbl:865-869} issues {@code EXEC CICS SEND FROM(ABEND-DATA)} and only
         * then {@code EXEC CICS ABEND ABCODE('9999')}, so on a terminal the operator reads those 134
         * bytes. Replacing them with a constant would change observable behaviour. They are safe to
         * publish because every field of {@code ABEND-DATA} is source-authored - a {@code CSMSG02Y}
         * literal, a {@code PROGRAM-ID} literal, or spaces - which is the standard
         * {@link AbendException#withSourceDiagnostic(String)} states and which the only producer in the
         * estate honours.
         *
         * <p>Where a paragraph transmitted nothing - the nine {@code CALL 'CEE3ABD'} sites, which
         * {@code DISPLAY} to {@code SYSOUT} and terminate - the member is {@code null} and
         * {@link ErrorResponse}'s {@code NON_NULL} inclusion omits it, so those bodies remain exactly
         * the two constants.
         *
         * @param abend the abend being answered; must not be {@code null}
         * @return the response body: {@link #ABEND_CODE}, {@link #ABEND_MESSAGE}, and the transmitted
         *         diagnostic when the abending paragraph transmitted one
         */
        static CobolErrorResponse abendResponse(final AbendException abend) {
            Objects.requireNonNull(abend, "An abend is required to answer one");
            return new CobolErrorResponse(ABEND_CODE,
                    reasonPhraseOf(HttpStatus.INTERNAL_SERVER_ERROR),
                    ABEND_MESSAGE,
                    List.of(),
                    abend.getSourceDiagnostic().orElse(null));
        }

        /**
         * Writes the withheld abend detail to the server log, at {@code ERROR} because an abend is by
         * definition a failed unit of work.
         *
         * <p>Separated from {@link #handleAbend(AbendException)} so the response mapping and the
         * diagnostic emission can each be read - and tested - on their own.
         *
         * @param abend the abend whose detail is being recorded; must not be {@code null}
         */
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
         * <p>{@link HttpMessageNotReadableException} is the single exception Spring raises for every
         * failure that happens <em>before</em> a payload object exists: malformed JSON, a property
         * tracing to no {@code DFHMDF} field (rejected because
         * {@code fail-on-unknown-properties: true} in {@code application.yml} makes an unmapped
         * property an error rather than something to ignore), a value of the wrong JSON type for its
         * member, and any exception a payload's own constructor or setter throws while binding - which
         * includes every screen field guard that refuses a value wider than its {@code PICTURE}
         * clause declares.
         *
         * <p>Without this handler those failures bypassed the two bodies above entirely and were
         * answered by Spring Boot's default error envelope, which is both a different shape and a
         * leakier one: its {@code message} can quote the parser's own text, naming the Java type, the
         * property and the byte position it stopped at. This handler answers them in the same shape as
         * every other failure and says none of that. The parser's message is written to the log
         * instead, at {@code DEBUG}, because a malformed request is a caller error rather than a
         * server fault and must not fill an operator's log at a higher level.
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

        /**
         * The screen-input refusal inside a parse failure, when the body was well-formed JSON that
         * carried a value no {@code RECEIVE MAP} could have delivered.
         *
         * <p>A {@link com.fasterxml.jackson.databind.JsonDeserializer} can only fail through Jackson, so
         * {@link ScreenTextDeserializer}'s refusal arrives wrapped - typically as a mapping failure
         * inside {@link HttpMessageNotReadableException}. Answering that as
         * {@link #MALFORMED_REQUEST_CODE} would be wrong twice over: the body parsed perfectly well, and
         * the caller would be told to check its shape rather than which member carries the offending
         * character. Unwrapping it restores the one answer this module gives for a screen value it
         * refuses, from wherever it was raised.
         *
         * <p>The walk is bounded and follows {@link Throwable#getCause()} only, so a cause cycle - which
         * a well-behaved chain does not have but a hand-built one could - cannot spin here.
         *
         * @param unreadable the parse failure; must not be {@code null}
         * @return the refusal found in the cause chain, or {@code null} when the failure is a genuine
         *         parse error
         */
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

        /**
         * Builds the unreadable-body response, and is the directly testable form of {@link
         * #handleUnreadableRequestBody(HttpMessageNotReadableException)}.
         *
         * <p>A constant body, for the same reason {@link #abendResponse(AbendException)} is one: no
         * parser text, no property name, no Java type and no offset can reach a client through a value
         * that is never read from the argument.
         *
         * @param unreadable the failure being answered; must not be {@code null}
         * @return the response body: {@link #MALFORMED_REQUEST_CODE} and
         *         {@link #MALFORMED_REQUEST_MESSAGE}, always
         */
        static CobolErrorResponse malformedRequestResponse(
                final HttpMessageNotReadableException unreadable) {
            Objects.requireNonNull(unreadable, "A binding failure is required to answer one");
            return CobolErrorResponse.of(MALFORMED_REQUEST_CODE,
                    HttpStatus.BAD_REQUEST,
                    MALFORMED_REQUEST_MESSAGE,
                    unreadableBodyFields(unreadable));
        }

        /**
         * Records the withheld parse detail at {@code DEBUG}.
         *
         * @param unreadable the failure whose detail is being recorded; must not be {@code null}
         */
        static void logUnreadableRequestBody(final HttpMessageNotReadableException unreadable) {
            Objects.requireNonNull(unreadable, "A binding failure is required to log one");
            if (LOG.isDebugEnabled()) {
                // The field-scoped projection goes to the server's own diagnostics, which is where a
                // field name belongs: it tells an operator which member of the payload the converter
                // stopped at without any of it reaching the caller. Only field NAMES are taken from
                // the mapping path - never the parser's message, which is where the payload is quoted -
                // and each is escaped, because a JSON property name is caller-supplied text and an
                // unescaped newline in a log line is a forged log entry (CWE-117). The exception is not
                // handed to the logger either, which is what makes the sentence above true: passing it
                // emits the parser's message - the one place the payload IS quoted - along with its whole
                // cause chain. Its type is logged instead, which says what refused without saying what
                // it was reading. The caller now receives these same names in the response envelope,
                // which is what makes a MALFORMED_REQUEST answer actionable; the log keeps them too,
                // because the log is where an operator looks when a caller reports one.
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
         * Maps a rejected request body onto {@code 400 Bad Request}, carrying the field-level
         * messages.
         *
         * <p>These constraints are the symbolic maps' field widths expressed as Bean Validation
         * annotations: a {@code DFHMDF} field declared {@code LENGTH=8} cannot accept nine
         * characters. Reporting which field failed, and why, is what lets a caller correct the input,
         * and it mirrors the COBOL highlighting the offending field on re-display.
         *
         * @param invalid the binding failure Spring raised for an {@code @Valid} request body
         * @return {@code 400} with one entry per rejected field
         */
        @ExceptionHandler(MethodArgumentNotValidException.class)
        public ResponseEntity<CobolErrorResponse> handleInvalidRequestBody(
                final MethodArgumentNotValidException invalid) {
            return json(HttpStatus.BAD_REQUEST, validationResponse(invalid));
        }

        /**
         * Builds the validation body for a rejected request body, and is the directly testable form
         * of {@link #handleInvalidRequestBody(MethodArgumentNotValidException)}.
         *
         * <p>Only the field name and the validator's own message are copied across. The exception's
         * own {@code getMessage()} is deliberately not used: it embeds framework binding detail,
         * which is internal state rather than observable behaviour. Entries are sorted by field name
         * so that the body is deterministic for a given failure, which matters when a test or a
         * parity case compares it.
         *
         * @param invalid the binding failure; must not be {@code null}
         * @return the response body, never {@code null} and never carrying framework detail
         */
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
         * Maps a constraint violation raised outside request-body binding - on a path variable, a
         * query parameter or a validated service argument - onto {@code 400 Bad Request}.
         *
         * @param violations the violations the validator collected
         * @return {@code 400} with one entry per violation
         */
        @ExceptionHandler(ConstraintViolationException.class)
        public ResponseEntity<CobolErrorResponse> handleConstraintViolation(
                final ConstraintViolationException violations) {
            return json(HttpStatus.BAD_REQUEST, validationResponse(violations));
        }

        /**
         * Builds the validation body for a set of constraint violations, and is the directly testable
         * form of {@link #handleConstraintViolation(ConstraintViolationException)}.
         *
         * <p>Sorted by property path for the same determinism reason as the request-body form. A
         * violation set has no defined iteration order, so without the sort the same failure could
         * serialise two different ways.
         *
         * @param violations the violations; must not be {@code null}
         * @return the response body, never {@code null}
         */
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

        /**
         * Names the fields an unreadable request body failed at, which is what makes a
         * {@code MALFORMED_REQUEST} answer actionable rather than merely honest.
         *
         * <p>Before this was wired into {@link #malformedRequestResponse(HttpMessageNotReadableException)}
         * the envelope named nothing, so a caller who echoed a 54-field screen back and had one member
         * refused was told only that "the request body could not be read" - and had to bisect the payload
         * to find out which member. The names were already being computed for the {@code DEBUG} log line;
         * they now travel to the caller as well.
         *
         * <p>Only {@link JsonMappingException.Reference#getFieldName()} is read from the mapping path,
         * never {@link JsonMappingException#getMessage()} and never the source location, because the
         * message is where Jackson quotes the payload. A path element that names an array index rather
         * than a field contributes nothing, and a failure with no usable path at all - a body that is
         * not JSON, so Jackson never reached a field - yields an empty list, which is the honest
         * answer: no field is at fault, the document is.
         *
         * @param unreadable the failure; must not be {@code null}
         * @return the identified fields, ordered by name and each carrying a fixed detail, or an empty
         *         list when the failure identifies none
         */
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

        // There is still deliberately NO handler for MethodArgumentTypeMismatchException here.
        //
        // It is a subclass of TypeMismatchException, so Spring's closest-match resolution would give
        // it precedence over handleTypeMismatch(TypeMismatchException) below - and the narrower
        // handler this class used to declare answered with the required type's simple name, so a
        // caller who put a word where a number belonged was told the field is "not a valid Integer"
        // or "not a valid long". That is the internal Java type of a screen field, published to an
        // unauthenticated caller, and it defeated the whole point of the fixed
        // TYPE_MISMATCH_MESSAGE the broader handler already returns: whichever handler wins must say
        // the same value-free, type-free sentence.
        //
        // Keeping ONE handler for the whole family is what stops the two from drifting: a future
        // reader adding a "helpful" detail to a narrower arm would silently reintroduce the
        // disclosure. So the family's one handler covers a path variable, a query parameter and a
        // conversion refused during binding alike.
        //
        // What that one handler DOES now do is name the parameter, by narrowing INSIDE itself rather
        // than by declaring a second @ExceptionHandler. Withholding the name was over-broad: a caller
        // told only "a request value could not be converted" on a route with several numeric
        // parameters cannot tell which one it mistyped. MethodArgumentTypeMismatchException#getName()
        // is not a Java identifier - it is the @RequestParam/@PathVariable name the route publishes
        // and the caller literally typed into the URL - so returning it discloses nothing the caller
        // did not already write. The required TYPE is still withheld, and so is the value.

        /**
         * Maps a value a domain guard rejected onto {@code 400 Bad Request}.
         *
         * <p>These are the width and shape guards in the record models, the request DTOs and
         * {@code FixedWidthCodec}: a {@code PIC X(16)} field handed seventeen characters, a signed
         * zoned span whose trailing byte is not a sign overpunch. The caller supplied the value, so
         * {@code 400} is the honest status - {@code 500} would blame the server for the caller's
         * input.
         *
         * <p>The exception's message is deliberately <strong>not</strong> copied, and it does not reach
         * the log either. Those guards are handed the value they are judging, and several of them quote it
         * back - {@code UserListRequest} names the over-long value, {@code SignOnResponse} names the
         * over-long field - so the message is caller-supplied content, which is precisely what a log line
         * must not carry (CWE-532) and precisely what a control character inside it could use to forge a
         * second entry (CWE-117). A fixed sentence is returned to the caller, because a guard added later
         * must not be able to widen this response by wording its message differently, and the log records
         * the exception's TYPE and the status - which says which class of guard refused without saying
         * what it was handed.
         *
         * @param rejected the guard failure
         * @return {@code 400} with a fixed, value-free explanation
         */
        @ExceptionHandler(IllegalArgumentException.class)
        public ResponseEntity<CobolErrorResponse> handleRejectedValue(
                final IllegalArgumentException rejected) {
            if (rejected instanceof ScreenInputRejectedException screenInput) {
                // The DIAGNOSTIC goes here and only here. It names the code page, the symbolic-map item,
                // the PICTURE width and the Unicode code point - the facts an engineer holding the
                // copybook open needs, and the facts an unauthenticated caller must not be handed. It
                // never carries the value, and it is rendered through DiagnosticText.singleLine because
                // the member name inside it is caller-supplied and a carriage return in a log line lets
                // whoever supplied it write a second line of their own choosing (CWE-117).
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

        /**
         * The body for a screen value a received map could not have carried: the member at fault, and a
         * fixed sentence saying what kind of thing is wrong with it.
         *
         * <p><strong>The exception's own message is not published.</strong> It is the server-side
         * diagnostic, and it names this module's internals one rejected field at a time - the code page
         * this deployment decodes datasets in, the symbolic-map item a member projects, its
         * {@code PIC X(n)} width, the Unicode code point that offended, and the mechanics of a 3270
         * {@code RECEIVE MAP}. None of that is anything a caller can act on, and all of it describes the
         * inside of a system whose endpoints are unauthenticated, so it goes to the log in
         * {@link #handleRejectedValue(IllegalArgumentException)} and no further.
         *
         * <p>What is published instead is {@link ScreenInputRejectedException#publicDetail()}: a
         * <em>fixed</em> sentence per {@link ScreenInputRejectedException.Reason}, composed inside that
         * type from the member name and nothing else. Fixed is what makes it safe as the module grows -
         * a factory added later cannot widen this response by wording its diagnostic differently, which
         * is the same property {@link #rejectedValueResponse()} relies on for the open-ended
         * {@link IllegalArgumentException} family. The value is never carried by either text.
         *
         * <p>Naming the member is the point of the type, and it is why this arm is reached from two
         * directions: a refusal raised inside a controller arrives here as the
         * {@link IllegalArgumentException} it is, and one raised at the JSON boundary by
         * {@link ScreenTextDeserializer} arrives wrapped in a parse failure and is unwrapped by
         * {@link #screenInputCause(HttpMessageNotReadableException)}. Both produce this body, so the
         * envelope has one shape however deep the refusal was raised. A caller told only that "a field
         * value does not fit" has no way to find which of 54 fields it was.
         *
         * @param rejected the refusal, carrying the member name and its reason
         * @return {@code 400} with the member named in {@code fieldErrors}, the fixed public detail, and
         *         neither the value nor any internal fact echoed
         */
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

        /**
         * The body for a rejected value, and the directly testable form of
         * {@link #handleRejectedValue(IllegalArgumentException)}.
         *
         * <p>Takes no argument, which is the point: there is no path by which the exception could
         * contribute text to it.
         *
         * @return the response body, identical for every rejected value
         */
        static CobolErrorResponse rejectedValueResponse() {
            return CobolErrorResponse.of(REJECTED_VALUE_CODE,
                    HttpStatus.BAD_REQUEST,
                    REJECTED_VALUE_DETAIL);
        }

        /**
         * Maps a server-side state or configuration fault onto {@code 500 Internal Server Error}.
         *
         * <p>An {@link IllegalStateException} in this module means a dataset binding is missing or
         * contradictory, a repository handle was used after being closed, or a locking read was issued
         * outside a unit of work. None of those is anything the caller did, so {@code 500} is correct
         * and {@code 400} would be a lie. As with a rejected value the message is neither published nor
         * logged: these messages name dataset names and configuration keys, and one of the three cases -
         * a handle used after closing - is raised from a path that has just been handed a record, so the
         * class of exception is not a reliable guarantee about its text. The log records the status and
         * the exception's type, which is what an operator pages on; the fault's own detail reaches the
         * server's startup and configuration diagnostics, which are not request-scoped.
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

        /**
         * The body for a state fault, and the directly testable form of
         * {@link #handleInternalState(IllegalStateException)}.
         *
         * @return the response body, identical for every state fault
         */
        static CobolErrorResponse internalStateResponse() {
            return CobolErrorResponse.of(INTERNAL_STATE_CODE,
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    INTERNAL_STATE_DETAIL);
        }

        /**
         * Converts one rejected field into its response entry.
         *
         * <p><strong>The validator's own message is not copied.</strong> Every {@code @Size} in this
         * module is annotated with prose written for the engineer maintaining the DTO, and that prose
         * names the symbolic-map item, its {@code PICTURE} clause and the copybook path and line the
         * width was read from - for example {@code "CARDSID is CARDSIDI PIC X(16) at
         * app/cpy-bms/COCRDSL.CPY:66 and holds at most 16 characters"}. Forwarding it would publish
         * this module's copybook inventory, its line numbers and its internal naming to an
         * unauthenticated caller, one rejected field at a time. Nor is the annotation's own default
         * text ({@code "size must be between 0 and 8"}) a safe substitute in general, because it is
         * whatever the constraint implementation happens to word it as and can change under a
         * validator upgrade.
         *
         * <p>{@link #publicConstraintText(String, Map)} therefore derives the message from the
         * constraint's <em>code and its declared bound</em>, which are the two facts a caller needs
         * and the two that are already part of the published wire contract. The field name is still
         * reported: the caller supplied that member itself, so naming it discloses nothing and is
         * what lets the caller correct the input.
         *
         * <p>The field is named as the <strong>caller</strong> spelled it. Bean Validation reports the
         * Java property path - {@code userId} for the JSON member {@code userid}, {@code usrIdIn} for
         * {@code usridin} - and a caller handed a name it never sent cannot map it back to anything in its
         * own request. {@link #jsonMemberOf(Object, String)} therefore resolves the path through
         * {@code @JsonProperty} first, so one lowercase {@code xxxI}-derived vocabulary is used on every
         * arm of this class.
         *
         * @param bound the object the body bound to, used only to resolve the member's JSON name; may be
         *              {@code null}, in which case the Java property path is reported unchanged
         * @param error the field error Spring produced
         * @return the entry naming the field and a stable, public description of the violation
         */
        private static FieldMessage fieldMessage(final Object bound, final FieldError error) {
            return new FieldMessage(jsonMemberOf(bound, error.getField()),
                    publicConstraintText(error.getCode(), constraintAttributes(error)));
        }

        /**
         * Resolves a Bean Validation property path onto the JSON member names the caller actually sent.
         *
         * <p>Walks the path one segment at a time from the bound object's type, mapping each segment to
         * its {@code @JsonProperty} value when the declaring type carries one and descending into that
         * member's type for the next segment. A segment that names an element - {@code rows[3]} - keeps
         * its subscript, and resolution descends into the element type when the member is a collection or
         * an array.
         *
         * <p><strong>Total by construction.</strong> This runs while an error response is being built, so
         * it can never be the thing that fails: any segment it cannot resolve - an unknown name, a type
         * with no such member, a member whose type cannot be introspected - is emitted unchanged and
         * resolution of the remaining segments stops. Reporting a name imprecisely is strictly better
         * than failing to report a validation failure at all.
         *
         * <p>Reflection reads annotations only; no member value is read, so no caller-supplied value can
         * reach the response through this method. {@code @JsonProperty} on a record component propagates
         * to the field, the accessor and the constructor parameter, so a record DTO and a
         * getter/setter DTO both resolve through the declared field.
         *
         * @param bound the object the body bound to, or {@code null} when the failure names no target
         * @param path  the Java property path Bean Validation reported; must not be {@code null}
         * @return the same path with every segment it could resolve replaced by its JSON member name
         */
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
                    // Unresolvable: emit this segment and everything after it exactly as it arrived.
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

        /**
         * The declared member of a type or of any of its supertypes, by name.
         *
         * @param declaring the type to search; must not be {@code null}
         * @param name      the member's Java name; must not be {@code null}
         * @return the field, or {@code null} when no type in the hierarchy declares it
         */
        private static Field declaredMember(final Class<?> declaring, final String name) {
            for (Class<?> type = declaring; type != null && type != Object.class;
                    type = type.getSuperclass()) {
                try {
                    return type.getDeclaredField(name);
                } catch (NoSuchFieldException notHere) {
                    // Try the supertype: a DTO may inherit the member. Nothing is logged, because a
                    // property path that names no field is a framework detail, not a fault.
                    continue;
                }
            }
            return null;
        }

        /**
         * The type the next path segment is declared on.
         *
         * @param member    the member the current segment resolved to; must not be {@code null}
         * @param subscript whether the segment carried an element subscript
         * @return the element type for a subscripted collection or array, the member's own type
         *         otherwise, or {@code null} when it cannot be determined
         */
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

        /**
         * Converts one constraint violation into its response entry.
         *
         * <p>The property path is rendered through {@link String#valueOf(Object)} so that a
         * violation carrying no path yields the string {@code "null"} rather than throwing while an
         * error response is being built. Failing to report a validation failure would be worse than
         * reporting one imprecisely.
         *
         * <p>{@link ConstraintViolation#getMessage()} is deliberately not copied, for the reason given
         * on {@link #fieldMessage(FieldError)}: on this module's DTOs that message is copybook
         * provenance written for a maintainer.
         *
         * @param violation the violation the validator produced
         * @return the entry naming the property path and a stable, public description of the violation
         */
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

        /**
         * The declared attributes of the constraint a field error came from, or an empty map when the
         * error did not come from Bean Validation.
         *
         * <p>Spring's {@code SpringValidatorAdapter} wraps each {@link ConstraintViolation} inside the
         * {@link FieldError} it creates, so the constraint's declared attributes - {@code max} for a
         * {@code @Size} - are reachable without depending on the order of
         * {@link FieldError#getArguments()}, which is an implementation detail of the adapter.
         *
         * @param error the field error; must not be {@code null}
         * @return the constraint's attributes, or {@link Map#of()} for a plain binding error
         */
        private static Map<String, Object> constraintAttributes(final FieldError error) {
            if (!error.contains(ConstraintViolation.class)) {
                return Map.of();
            }
            final ConstraintDescriptor<?> descriptor =
                    error.unwrap(ConstraintViolation.class).getConstraintDescriptor();
            return descriptor == null ? Map.of() : descriptor.getAttributes();
        }

        /**
         * The client-facing text for one violated constraint, derived from the constraint's code and
         * its declared bound and from nothing else.
         *
         * <p>Total by construction: an unrecognised or absent code answers
         * {@value #CONSTRAINT_DETAIL}, so a constraint added later cannot leak its own wording by
         * default. Nothing here reads the rejected value, the annotation's message, the DTO's class
         * name or any Java type.
         *
         * @param code       the constraint's code, which for Bean Validation is the annotation's
         *                   simple name - {@code "Size"}, {@code "Pattern"}, {@code "NotNull"} - and
         *                   may be {@code null}
         * @param attributes the constraint's declared attributes; must not be {@code null}
         * @return the text to publish, never {@code null} and never carrying internal detail
         */
        static String publicConstraintText(final String code, final Map<String, Object> attributes) {
            // An absent code is answered first, because Set.of(...).contains(null) throws - and a
            // response being built is the last place to raise a second failure.
            if (code == null) {
                return CONSTRAINT_DETAIL;
            }
            if (SIZE_CONSTRAINT.equals(code)) {
                final Object max = attributes.get("max");
                // A @Size that declares only a minimum leaves max at Integer.MAX_VALUE, which is not
                // a width worth quoting back.
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

        /**
         * The fixed reply to a request body that could not be read at all.
         *
         * <p>Deliberately says nothing about <em>why</em>. Jackson's own message for this failure
         * names the target class, the offending property, the byte offset and, when
         * {@code fail-on-unknown-properties: true} rejects a field, the full list of properties the
         * DTO does accept - which is an inventory of the payload contract, published to an
         * unauthenticated caller. A caller with a legitimately malformed body needs to know that the
         * body was rejected; it does not need this module's field inventory to find that out.
         */
        static final String UNREADABLE_BODY_MESSAGE =
                "The request body could not be read as JSON.";

        /**
         * The fixed reply to a request value that could not be converted to its declared type.
         *
         * <p>This is the path-variable and query-parameter counterpart of the above: an account id
         * that is not numeric, for example. Spring's own message for this failure names the source and
         * target Java types - {@code Failed to convert value of type 'java.lang.String' to required
         * type ...} - which publishes the internal type of a screen field to a caller. It does not
         * echo the offending value back, which is worth stating precisely rather than assuming the
         * worse case: the reason this handler exists is the type disclosure, not a value disclosure.
         */
        static final String TYPE_MISMATCH_MESSAGE =
                "A request value could not be converted to the type its field declares.";

        /**
         * The fixed reply to a failure reaching a dataset.
         *
         * <p>{@link DataAccessException} messages routinely embed the SQL statement, the dataset or
         * table name, the driver's vendor code and sometimes the connection URL. Publishing any of
         * that would hand a caller the shape of the data layer, and none of it is behaviour any COBOL
         * program exhibited: a program that could not read a file displayed its own two-character
         * {@code FILE STATUS} and abended, and that path is {@link AbendException}'s, not this one's.
         */
        static final String DATASET_ACCESS_MESSAGE =
                "The request could not be completed because a dataset could not be accessed.";

        /**
         * The fixed reply to any other failure, used by the status-preserving catch-all.
         *
         * <p>One sentence for every remaining case, on purpose. Varying the wording by exception
         * family would itself disclose which family occurred, which is the same leak in a smaller
         * form.
         */
        static final String UNEXPECTED_FAILURE_MESSAGE =
                "The request could not be completed.";

        /**
         * Maps a request body that could not be parsed onto {@code 400 Bad Request}.
         *
         * <p>The argument is deliberately <strong>not read</strong>. That is the entire point of this
         * handler: without it, the exception falls through to the generic error endpoint, where its
         * message - Jackson's, naming classes, properties and offsets - would be what a caller sees.
         * The parameter is still declared because it is what binds this method to its exception type
         * and makes the mapping legible to the next reader.
         *
         * @param unreadable the parse failure, whose message is intentionally discarded
         * @return {@code 400} carrying {@link #UNREADABLE_BODY_MESSAGE} and nothing else
         */
        static ResponseEntity<CobolErrorResponse> handleUnreadableBody(
                final HttpMessageNotReadableException unreadable) {
            return sanitized(MALFORMED_REQUEST_CODE, HttpStatus.BAD_REQUEST, UNREADABLE_BODY_MESSAGE);
        }

        /**
         * Maps a value that could not be converted to its declared type onto {@code 400 Bad
         * Request}.
         *
         * <p>Declared against {@link TypeMismatchException} rather than against Spring MVC's
         * {@code MethodArgumentTypeMismatchException} on purpose: the MVC form is a subclass, so one
         * handler covers a path variable, a query parameter and a conversion refused during binding
         * alike, and no case is left to fall through because a narrower type was chosen.
         *
         * <p>The parameter is <strong>named</strong> when the framework knows its name, which it does for
         * the MVC form: {@link MethodArgumentTypeMismatchException#getName()} carries the
         * {@code @RequestParam} or {@code @PathVariable} name the route publishes and the caller typed
         * into the URL. Naming it is what makes the answer actionable on a route with more than one
         * numeric parameter. The required Java type is still withheld, the rejected value is still
         * withheld, and a plain {@link TypeMismatchException} - which carries no such name - still names
         * nothing.
         *
         * @param mismatch the conversion failure, whose message is intentionally discarded
         * @return {@code 400} carrying {@link #TYPE_MISMATCH_MESSAGE} and, where the framework supplies
         *         one, the name of the parameter that could not be converted
         */
        @ExceptionHandler(TypeMismatchException.class)
        public ResponseEntity<CobolErrorResponse> handleTypeMismatch(
                final TypeMismatchException mismatch) {
            return json(HttpStatus.BAD_REQUEST, typeMismatchResponse(mismatch));
        }

        /**
         * The body for a conversion failure, and the directly testable form of
         * {@link #handleTypeMismatch(TypeMismatchException)}.
         *
         * @param mismatch the conversion failure; may be {@code null}
         * @return the response body, naming the parameter when the framework supplies its name
         */
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
         * <p>{@code 500} is correct and not a fallback: the unit of work did not complete. Note what
         * this handler is <em>not</em> for - a record that was simply absent or duplicate is an
         * outcome the COBOL guard chains handle in-program. Such an outcome reaches the controller as
         * a {@link FileStatus} outcome, is answered by the arm the source program wrote for it, and
         * leaves as a painted screen on a {@code 200}; it never becomes an exception and never reaches
         * this handler. Reaching here means the access itself failed.
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

        /**
         * Writes the withheld data-access detail to the server log, at {@code ERROR} because a unit of
         * work that did not complete is an operational event.
         *
         * <p>Without this the failure left no trace anywhere. An {@code @ExceptionHandler} that returns
         * a response <em>handles</em> the exception, so Spring's own resolver never logs it either: the
         * caller received a five-word sentence and the operator received nothing at all - no dataset
         * outage, no exhausted pool, no revoked credential, nothing to correlate with the {@code 500}
         * a caller reports.
         *
         * <p>What is recorded is the status, the exception's own type and the fact that a dataset access
         * did not complete. What is <strong>not</strong> recorded is anything the failure carries: a
         * {@link DataAccessException}'s message quotes the SQL it was executing, and the SQL of this
         * module's repositories names the dataset and carries the record image as a bound parameter -
         * account numbers, card numbers and customer records among it (CWE-532). The exception object is
         * not handed to the logger either, because passing it emits that message and its whole cause
         * chain, including the driver's own text. The type alone says which class of access failed, and
         * the deployment's own driver and pool diagnostics say why.
         *
         * @param failure the failure whose detail is being withheld; must not be {@code null}
         */
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
         * <p>Ordering is not a concern here even though this handler's type is the broadest possible.
         * Spring resolves an {@code @ExceptionHandler} by walking the thrown exception's own type
         * hierarchy and choosing the <em>closest</em> declared match, so {@link AbendException}, a
         * validation failure, a parse failure, a conversion failure and a data-access failure all
         * continue to reach their own handlers above; only what none of them declares arrives here.
         *
         * @param failure the unclaimed failure; its status is honoured, its message is not published
         * @return the status {@link #statusForFailure(Exception)} determines, carrying
         *         {@link #UNEXPECTED_FAILURE_MESSAGE}
         */
        @ExceptionHandler(Exception.class)
        public ResponseEntity<CobolErrorResponse> handleUnexpectedFailure(final Exception failure) {
            final HttpStatusCode status = statusForFailure(failure);
            logUnexpectedFailure(failure, status);
            return sanitized(REQUEST_NOT_COMPLETED_CODE, status, UNEXPECTED_FAILURE_MESSAGE);
        }

        /**
         * Records the withheld detail of an unclaimed failure, at the level its status deserves.
         *
         * <p>The level is chosen from the status rather than fixed, and that distinction is the whole
         * point of the method. A failure that arrives here carrying {@code 404}, {@code 405},
         * {@code 415} or {@code 503} is an ordinary client mistake - a typo in a path, the wrong verb,
         * an {@code Accept} header this API does not speak - and an estate that logged every one of them
         * at {@code ERROR} would page an operator for a caller's typo and bury the failures that matter.
         * Those are recorded at {@code DEBUG}, where an investigation can still find them. Anything that
         * resolves to a server status did not complete for a reason the caller could not have caused, so
         * it is recorded at {@code ERROR}: it is the only trace of a request this module answered with a
         * fixed sentence and no detail.
         *
         * <p>Value-free either way. The status and the exception's type are recorded; the message is
         * not, and neither is the exception object - an unclaimed failure is by definition of unknown
         * provenance, so nothing may be assumed about what its text quotes. That is precisely why the
         * response withholds it too.
         *
         * @param failure the failure whose detail is being withheld; must not be {@code null}
         * @param status  the status being answered with; must not be {@code null}
         */
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

        /**
         * Determines the status of an otherwise unclaimed failure, honouring one it already carries.
         *
         * <p>Spring MVC reports several ordinary client mistakes as exceptions that implement
         * {@link ErrorResponse} and therefore already know their own status - {@code 404} for an
         * unknown path, {@code 405} for a wrong method, {@code 415} for an unsupported media type,
         * {@code 503} for an async timeout. Because an advice's handlers are consulted before Spring's
         * default resolver, answering {@code 500} unconditionally here would replace every one of
         * those statuses with a server error and make an ordinary typo in a URL look like an outage.
         *
         * @param failure the failure to classify; must not be {@code null}
         * @return the status the failure carries when it carries one, and
         *         {@link HttpStatus#INTERNAL_SERVER_ERROR} otherwise
         */
        static HttpStatusCode statusForFailure(final Exception failure) {
            if (failure instanceof org.springframework.web.ErrorResponse carriesItsOwnStatus) {
                return carriesItsOwnStatus.getStatusCode();
            }
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }

        /**
         * Builds a sanitized response, and is the one place a sanitized status and body are paired.
         *
         * @param status  the status to answer with
         * @param message one of this class's fixed message constants - never text taken from an
         *                exception
         * @return the response entity carrying {@link #sanitizedBody(HttpStatusCode, String)}
         */
        static ResponseEntity<CobolErrorResponse> sanitized(final String code,
                final HttpStatusCode status,
                final String message) {
            return json(status, sanitizedBody(code, status, message));
        }

        /**
         * Answers with the one error envelope and <strong>pins the response content type to JSON</strong>,
         * which is what stops content negotiation from turning an error into something this API does not
         * speak.
         *
         * <p>The pin is load-bearing rather than decorative. Spring writes a {@code @ResponseBody} value
         * through the media type the {@code Accept} header negotiated <em>unless</em> the response already
         * carries a concrete {@code Content-Type}, in which case that type is used and no negotiation
         * happens. Without the pin, a caller sending {@code Accept: text/html} to a JSON-only API got no
         * error body at all: the negotiation failed, the container's error dispatch took over, and Boot's
         * whitelabel page answered a REST call with HTML. With it, every failure on every route is
         * answered by this envelope, whatever the caller asked to be given.
         *
         * @param status the status to answer with
         * @param body   the envelope to carry
         * @return the response entity, always {@code application/json}
         */
        static ResponseEntity<CobolErrorResponse> json(final HttpStatusCode status,
                final CobolErrorResponse body) {
            return ResponseEntity.status(status)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body);
        }

        /**
         * Builds a sanitized body, and is the directly testable form of {@link
         * #sanitized(HttpStatusCode, String)}.
         *
         * <p>Three fields, and every one of them is either a constant of this class or derived from
         * the status alone. There is deliberately no field for a cause, a trace, a path, a timestamp
         * or a correlation identifier: the first three are disclosure, and the last two would be
         * non-deterministic output in a module whose responses are compared byte for byte.
         *
         * @param status  the status being answered with
         * @param message the fixed message to carry
         * @return the body, which cannot contain anything the failure supplied
         */
        static CobolErrorResponse sanitizedBody(final String code,
                final HttpStatusCode status,
                final String message) {
            return CobolErrorResponse.of(code, status, message);
        }

        /**
         * Renders the standard reason phrase of a status, and invents nothing for a status that has
         * none.
         *
         * <p>{@link HttpStatusCode} permits a non-standard code, for which no registered phrase
         * exists. Returning the empty string in that case is deliberate: composing a plausible phrase
         * would put invented text in a response body, which is precisely what the rest of this class
         * exists to prevent. The numeric status is carried separately and is never lost.
         *
         * @param status the status to describe; must not be {@code null}
         * @return the registered reason phrase, or the empty string for an unregistered code
         */
        static String reasonPhraseOf(final HttpStatusCode status) {
            final HttpStatus standard = HttpStatus.resolve(status.value());
            return standard == null ? "" : standard.getReasonPhrase();
        }

        /**
         * <strong>The one error body this API answers with.</strong>
         *
         * <h2>Why one shape and not five</h2>
         * This boundary previously published four different record shapes plus Spring Boot's own
         * {@code /error} body, so a client had to recognise {@code {code,message}},
         * {@code {error,fieldErrors}}, {@code {status,error,message}}, {@code {error,detail}} and
         * {@code {timestamp,status,error}} to handle the failures of a single API - and three of those
         * shapes named no field, so a caller could not tell which member of a 54-field screen had been
         * refused. One shape removes both problems: every failure is described the same way, and every
         * failure that concerns a field names it.
         *
         * <h2>The members, and what each is for</h2>
         * <ul>
         *   <li>{@link #code} - the stable token a client branches on, never prose to be parsed. One of
         *       {@link CobolErrorHandler#ABEND_CODE}, {@link CobolErrorHandler#MALFORMED_REQUEST_CODE},
         *       {@link CobolErrorHandler#VALIDATION_FAILED_CODE},
         *       {@link CobolErrorHandler#REJECTED_VALUE_CODE},
         *       {@link CobolErrorHandler#TYPE_MISMATCH_CODE},
         *       {@link CobolErrorHandler#DATASET_ACCESS_CODE},
         *       {@link CobolErrorHandler#INTERNAL_STATE_CODE} or
         *       {@link CobolErrorHandler#REQUEST_NOT_COMPLETED_CODE}.</li>
         *   <li>{@link #error} - the registered HTTP reason phrase of the status this body travels with,
         *       and the empty string for a status that has none. Never invented prose, and never a COBOL
         *       literal: the parity-relevant text of an online program is the message the program moved
         *       into its own {@code ERRMSG} field, which travels in that screen's payload on a
         *       {@code 200} and never here.</li>
         *   <li>{@link #detail} - one of this class's fixed sentences, chosen by the handler from the
         *       failure's <em>type</em> and never derived from its message. No exception text, no
         *       property path, no parse position, no SQL fragment, no filesystem path and no
         *       configuration value can reach it (practice <strong>B6</strong>).</li>
         *   <li>{@link #fieldErrors} - one entry per field the failure identified, ordered by field
         *       name so the body is deterministic for a given failure. <strong>Always present, and
         *       empty means "this failure identified no field"</strong> - not "every field passed".
         *       {@link #code} is what says which family refused; the empty list is not evidence that a
         *       field-by-field examination happened.</li>
         *   <li>{@link #abendData} - the <em>only</em> optional member, and the only member carrying
         *       anything from the failure: the fixed-width area the COBOL itself transmitted before
         *       abending, per {@link AbendException#getSourceDiagnostic()}. It is published because
         *       {@code app/cbl/COCRDSLC.cbl:865-869} issues {@code EXEC CICS SEND FROM(ABEND-DATA)}
         *       before {@code EXEC CICS ABEND ABCODE('9999')}, so on a terminal the operator reads those
         *       bytes and replacing them with a constant would change observable behaviour. It is safe
         *       to publish because every field of {@code ABEND-DATA} is source-authored - a
         *       {@code CSMSG02Y} literal, a {@code PROGRAM-ID} literal, or spaces. It is not a
         *       general-purpose detail slot; see
         *       {@link CobolErrorHandler#abendResponse(AbendException)}.</li>
         * </ul>
         *
         * <h2>Why {@code NON_NULL} here and {@code always} for the screens</h2>
         * The record is annotated {@code NON_NULL} rather than relying on
         * {@code spring.jackson.default-property-inclusion}, which this module sets to {@code always}
         * because an all-spaces screen field is meaningful and must not be dropped. That reasoning is
         * about payload projections; an error body is not one. Here an absent member is meaningful
         * instead: the nine {@code CALL 'CEE3ABD'} sites transmit no {@code ABEND-DATA}, and a body
         * carrying {@code "abendData": null} would claim they transmitted an empty one.
         *
         * @param code        the stable code a client branches on
         * @param error       the registered reason phrase of the status, or the empty string
         * @param detail      the fixed sentence for the failure's family
         * @param fieldErrors the fields this failure identified, possibly empty, never {@code null}
         * @param abendData   the source-authored fixed-width diagnostic an abending paragraph
         *                    transmitted, or {@code null} when there was none - in which case the
         *                    member does not appear in the body at all
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public record CobolErrorResponse(String code,
                                         String error,
                                         String detail,
                                         List<FieldMessage> fieldErrors,
                                         String abendData) {

            /**
             * Normalises {@code fieldErrors} to an immutable list, accepting {@code null} as "no field
             * was identified" so that no builder has to pass {@link List#of()} to say nothing.
             *
             * @throws NullPointerException if {@code fieldErrors} contains a {@code null} entry
             */
            public CobolErrorResponse {
                fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
            }

            /**
             * A body that identifies no field.
             *
             * @param code   the stable code a client branches on
             * @param status the status this body travels with, which supplies the reason phrase
             * @param detail the fixed sentence for the failure's family
             * @return the body, with an empty {@code fieldErrors} and no {@code abendData}
             */
            static CobolErrorResponse of(final String code,
                    final HttpStatusCode status,
                    final String detail) {
                return new CobolErrorResponse(code, reasonPhraseOf(status), detail, List.of(), null);
            }

            /**
             * A body that names the fields the failure identified.
             *
             * @param code        the stable code a client branches on
             * @param status      the status this body travels with, which supplies the reason phrase
             * @param detail      the fixed sentence for the failure's family
             * @param fieldErrors the identified fields, already ordered by name
             * @return the body, with no {@code abendData}
             */
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
         * @param field   the field or property path that failed - the name the caller sent, never a
         *                Java identifier invented by the framework
         * @param message what is wrong with it: the validator's own message, or one of this class's
         *                fixed field details. Never the rejected value
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
     * <h2>Why the mapping declares no {@code produces}</h2>
     * A {@code produces} restriction would make this mapping unmatchable for exactly the request that
     * needs it most - the one whose {@code Accept} header the API cannot satisfy. With no restriction the
     * mapping always matches, and {@link CobolErrorHandler#json(HttpStatusCode, CobolErrorHandler.CobolErrorResponse)}
     * pins the response to {@code application/json} so the body is written whatever was asked for.
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
     */
    @RestController
    public static class CobolErrorEndpoint implements ErrorController {

        /**
         * Constructs the endpoint. It is stateless and holds no field, so one instance serves every
         * request.
         */
        public CobolErrorEndpoint() {
            // Intentionally empty. Every value in the response comes from the request attribute the
            // container set, or from a constant on CobolErrorHandler.
        }

        /**
         * Renders the container's error dispatch as this API's one error envelope.
         *
         * @param request the forwarded request, read only for
         *                {@code jakarta.servlet.error.status_code}; must not be {@code null}
         * @return the envelope, always {@code application/json}, carrying the recorded status
         * @throws NullPointerException if {@code request} is {@code null}
         */
        @RequestMapping("${server.error.path:${error.path:/error}}")
        public ResponseEntity<CobolErrorHandler.CobolErrorResponse> renderError(
                final HttpServletRequest request) {
            Objects.requireNonNull(request, "A request is required to render its error status");
            return CobolErrorHandler.json(statusOf(request),
                    CobolErrorHandler.CobolErrorResponse.of(
                            CobolErrorHandler.REQUEST_NOT_COMPLETED_CODE,
                            statusOf(request),
                            CobolErrorHandler.UNEXPECTED_FAILURE_MESSAGE));
        }

        /**
         * The status the container recorded for the failure being rendered.
         *
         * @param request the forwarded request; must not be {@code null}
         * @return the recorded status when the attribute is present and is a valid HTTP status, and
         *         {@link HttpStatus#INTERNAL_SERVER_ERROR} otherwise
         */
        static HttpStatusCode statusOf(final HttpServletRequest request) {
            Objects.requireNonNull(request, "A request is required to read its error status");
            final Object recorded = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
            if (recorded instanceof Integer status && HttpStatus.resolve(status) != null) {
                return HttpStatus.valueOf(status);
            }
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
    }


    /**
     * The {@code CORPT00C} job-submission port's contract, bound from
     * {@code carddemo.job-submission} and validated at startup.
     *
     * <h2>What the port is</h2>
     * {@code CORPT00C} composes 80-byte JCL skeleton records and writes them to the CICS transient
     * data queue {@code JOBS}, which the region holds open on the internal reader. Java has no TDQ,
     * so that write becomes an outbound port, and this record is its contract - transcribed from
     * {@code app/csd/CARDDEMO.CSD:L499-L505}:
     * <pre>
     * DEFINE TDQUEUE(JOBS) GROUP(CARDDEMO)
     * DESCRIPTION(SUBMIT JOBS FROM CICS)
     *        TYPE(EXTRA) DATABUFFERS(1) DDNAME(INREADER) ERROROPTION(IGNORE)
     *        OPENTIME(INITIAL) TYPEFILE(OUTPUT) RECORDSIZE(80)
     *        RECORDFORMAT(FIXED) BLOCKFORMAT(UNBLOCKED) DISPOSITION(MOD)
     * </pre>
     *
     * <h2>Why the byte contract is pinned rather than merely read</h2>
     * {@code RECORDSIZE(80)}, {@code RECORDFORMAT(FIXED)}, {@code BLOCKFORMAT(UNBLOCKED)} and
     * {@code DISPOSITION(MOD)} are what make the emitted bytes identical to the CICS write: 80 bytes
     * exactly, space padded, one record after another, appended rather than truncating (gate G42).
     * They are transcribed from a source file, so a deployment has nothing to decide about them - and
     * a value that could be changed by configuration is a value that could silently stop matching the
     * CSD. {@link #validate()} therefore requires each of the four to equal what the CSD declares,
     * and the only genuinely deployment-specific values here are the two paths.
     *
     * <h2>Why the destination is validated, and why validation is pure path algebra</h2>
     * {@code destination} is the single value in this whole module that an operator supplies from
     * outside the process, through {@code CARDDEMO_JOB_SUBMISSION_DESTINATION}. Combined with
     * {@code DISPOSITION(MOD)} that has a specific consequence: a wrong value does not fail, it
     * succeeds against the wrong file. Whatever path is named receives 80-byte records appended to
     * its existing content. Pointed at {@code app/cbl}, {@code app/cpy}, {@code app/cpy-bms},
     * {@code app/bms}, {@code app/jcl}, {@code app/proc}, {@code app/csd}, {@code app/ctl},
     * {@code app/catlg} or {@code app/data} it would corrupt the only oracle this migration has for
     * behavioural equivalence (practice B3), and there would be no error to show for it.
     *
     * <p>So five things are required of each path, and {@link #validate()} checks them before any
     * writer exists to use one: it must be present, syntactically valid, <strong>absolute</strong> -
     * a relative path resolves against a working directory nobody here controls - free of any
     * {@code ".."} segment, and not naming a reference tree. The destination must additionally
     * resolve <strong>strictly inside</strong> {@code approvedRoot}, so relocating output is a
     * deliberate change of the root and cannot be arrived at by traversal.
     *
     * <p>Note what containment does <em>instead of</em> a list of forbidden locations. There is no
     * denylist of {@code /etc}, {@code /root}, an SSH directory or a system configuration path here,
     * because an allowlisted root already excludes every one of them and everything else nobody
     * thought to enumerate - a denylist can only ever be as complete as its author's imagination,
     * while containment in a single named tree is complete by construction. The reference-tree check
     * remains alongside it, because that one is not about what is outside the root: it stops a
     * deployment from choosing a <em>root</em> inside the repository's read-only trees in the first
     * place.
     *
     * <p>Every check is path algebra on the normalized paths and touches the filesystem
     * <strong>not at all</strong> - no existence test, no writability test, no directory creation.
     * That is deliberate on three counts: a validation that consulted the filesystem would make
     * startup depend on machine state, would give different verdicts on two machines holding the same
     * configuration, and would confuse "this path is not permitted", which is a configuration defect,
     * with "this path does not exist yet", which is the writer's ordinary first-run condition.
     *
     * <h2>Why path algebra is only half the defence</h2>
     * Precisely because it never touches the filesystem, everything above is blind to a
     * <strong>symbolic link</strong>. A link planted at the destination, or at a directory on the way
     * to it, satisfies every test here - it is absolute, carries no {@code ".."}, names no reference
     * tree and lies inside {@code approvedRoot} - while sending eighty-byte records wherever it points
     * (CWE-59). Nothing that runs once at startup can close that, either, because a link can be
     * planted after startup and swapped between a check and an open (CWE-367).
     *
     * <p>So the defence is in two halves, and this record is explicitly the first of them.
     * {@code ReportRequestController.InternalReaderJobSubmissionPort} performs the second on
     * <em>every</em> write: it resolves {@link #approvedRootPath()} through
     * {@link Path#toRealPath(java.nio.file.LinkOption...)}, descends to the destination one name
     * element at a time refusing any component that is a symbolic link, re-verifies the completed
     * parent against the real root, and opens the record's own file with
     * {@code LinkOption.NOFOLLOW_LINKS} so the platform refuses a linked leaf atomically with the
     * open. Read the two together: this record decides which tree output may go in, and the port
     * proves that the file it is about to append to is really in it.
     *
     * <p>That division is also why {@code approvedRoot} must be a directory the operator provisions
     * and controls rather than a path this module defaults for them. A root under a world-writable
     * shared temporary directory would let any local user plant the very links the port then has to
     * refuse, turning a defence in depth into the only defence; neither key is defaulted in
     * {@code application.yml} for that reason.
     *
     * <h2>Why the {@code ".."} check runs before normalization</h2>
     * {@link Path#normalize()} resolves {@code ".."} away, so a check made afterwards can only ever
     * see the result and never the intent. A configured
     * {@code /var/carddemo/inreader/../../../etc/passwd} normalizes to {@code /etc/passwd}, which the
     * containment check would reject - but with a message about containment rather than about the
     * traversal that caused it. Worse, a traversal that happens to land back inside the approved root
     * would pass containment silently while still telling every reader of the configuration something
     * untrue about where output goes. Rejecting the segment itself, before normalizing, reports the
     * actual defect.
     *
     * @param queueName    the TDQ name; required to be exactly {@value #TDQ_QUEUE_NAME}, which is what
     *                     {@code app/csd/CARDDEMO.CSD:L499} declares
     * @param ddName       the {@code DDNAME}; required to be exactly {@value #TDQ_DD_NAME}, which is
     *                     what {@code app/csd/CARDDEMO.CSD:L501} declares
     * @param charset      the code page the 80-byte records are encoded in. Required, and required to
     *                     be a total single-byte code page, because a fixed-width record is addressed
     *                     by absolute byte offset
     * @param recordLength the {@code RECORDSIZE}; must be exactly {@value #TDQ_RECORD_LENGTH}
     * @param recordFormat the {@code RECORDFORMAT}; must be {@code FIXED}
     * @param blockFormat  the {@code BLOCKFORMAT}; must be {@code UNBLOCKED}
     * @param disposition  the {@code DISPOSITION}; must be {@code MOD}, which is what makes the
     *                     writer append rather than truncate
     * @param approvedRoot the one directory tree beneath which this module will write
     *                     job-submission output. Absolute, traversal-free and outside every reference
     *                     tree
     * @param destination  the file the port appends 80-byte records to. Absolute, traversal-free,
     *                     outside every reference tree, and strictly inside {@code approvedRoot}
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
         *
         * <p>Required to be exactly this rather than merely present. The name is not decoration: it is
         * the queue {@code app/cbl/CORPT00C.cbl:L517-L523} writes to by literal, so a configuration
         * naming any other queue describes a destination the program never writes to while the port
         * goes on appending to the same file - a diagnostic that would name the wrong queue for the
         * lifetime of the deployment.
         */
        public static final String TDQ_QUEUE_NAME = "JOBS";

        /**
         * The dataset the queue is bound to: {@code DDNAME(INREADER)}
         * ({@code app/csd/CARDDEMO.CSD:L501}), and the only name permitted.
         *
         * <p>{@code TYPE(EXTRA)} makes {@code JOBS} an extrapartition queue, which means the records
         * leave CICS entirely and land on the dataset this DD names - the region's internal reader.
         * Any other DD name claims the records go somewhere they do not.
         */
        public static final String TDQ_DD_NAME = "INREADER";

        /** The {@code RECORDSIZE(80)} of {@code TDQUEUE(JOBS)}, and the only length permitted. */
        public static final int TDQ_RECORD_LENGTH = 80;

        /** The {@code RECORDFORMAT(FIXED)} of {@code TDQUEUE(JOBS)}. */
        public static final String TDQ_RECORD_FORMAT = "FIXED";

        /** The {@code BLOCKFORMAT(UNBLOCKED)} of {@code TDQUEUE(JOBS)}. */
        public static final String TDQ_BLOCK_FORMAT = "UNBLOCKED";

        /**
         * The {@code DISPOSITION(MOD)} of {@code TDQUEUE(JOBS)}: records are appended to whatever the
         * destination already holds, never written over it. This is the value that makes an
         * unconstrained destination consequential rather than merely untidy.
         */
        public static final String TDQ_DISPOSITION = "MOD";

        /**
         * The ten repository trees that are the parity oracle, expressed as the two leading path
         * segments that identify each one.
         *
         * <p>Held as {@code "app/<name>"} pairs rather than as bare directory names because a bare
         * {@code data} or {@code proc} segment appears in ordinary filesystem paths all the time and
         * would reject destinations that have nothing to do with this repository. Matching the pair
         * identifies the tree.
         */
        static final Set<String> REFERENCE_TREES = Set.of(
                "app/cbl", "app/cpy", "app/cpy-bms", "app/bms", "app/jcl",
                "app/proc", "app/csd", "app/ctl", "app/catlg", "app/data");

        /** The path element that walks upwards, and is refused before any normalization occurs. */
        private static final String TRAVERSAL_SEGMENT = "..";

        /**
         * The whole validity contract of the port, as one method a unit test can drive directly with
         * no application context in the picture.
         *
         * <p>Checked in this order: the queue and DD names are present; the four byte-contract values
         * equal what the CSD declares; each path is individually usable; and finally the destination
         * lies strictly inside the approved root. The order matters for the diagnostics - a path is
         * proven usable in isolation before it is compared with the other, so a message about
         * containment is never the first thing a reader sees about a path that was malformed anyway.
         *
         * @throws IllegalStateException on the first violation found, naming the property key at
         *                               fault, the rejected value and what is required instead
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

        /**
         * Whether the deployment has declared no job-submission location at all - neither the approved
         * root nor the destination.
         *
         * <h4>Why this is a permitted state and not a configuration error</h4>
         * Neither path is defaulted in {@code application.yml}: they used to fall back to
         * {@code ${java.io.tmpdir}/carddemo}, a shared, world-writable, predictably named location
         * that is precisely where a planted symbolic link does the most damage, so the fallbacks were
         * removed rather than replaced. A deployment that has not yet provisioned a root therefore
         * arrives here with both keys empty, and there are two possible answers to that.
         *
         * <p>Refusing to start is one, and it is the wrong one. Sixteen of this module's seventeen
         * screens have nothing to do with the internal reader, and an unconfigured outbound port is
         * not a reason to take the account, card and user screens down with it. The other answer is
         * the one CICS itself gives: {@code ERROROPTION(IGNORE)} on {@code TDQUEUE(JOBS)} means a queue
         * that cannot be written does not abend its caller, it reports a condition -
         * {@code DFHRESP(NOTOPEN)} - and {@code app/cbl/CORPT00C.cbl:525-535} handles exactly that by
         * displaying {@code 'Unable to write TDQ (JOBS)'} and re-painting the screen.
         *
         * <p>So an unconfigured port starts, and fails <strong>closed</strong> at the moment of use:
         * the writer finds no destination, reports {@code NOTOPEN} per write, and the operator sees
         * the program's own message. A <em>partially</em> or <em>unsafely</em> configured port is a
         * different thing entirely and still refuses at startup - someone stated an intention there,
         * and stating it wrongly is a defect worth failing on.
         *
         * @return {@code true} when neither path carries text
         */
        private boolean isUnconfigured() {
            return !StringUtils.hasText(approvedRoot) && !StringUtils.hasText(destination);
        }

        /**
         * The destination as an absolute, normalized path, for the writer that opens it.
         *
         * <p>Exposed so that the writer uses the very path {@link #validate()} approved rather than
         * re-deriving one from the raw string and risking a different result. It performs no
         * validation of its own and no filesystem access.
         *
         * @return the normalized destination path
         * @throws InvalidPathException if called on an instance that was never validated and whose
         *                              destination is not a syntactically valid path
         */
        public Path destinationPath() {
            return Paths.get(destination).normalize();
        }

        /**
         * The approved root as an absolute, normalized path, for the writer that opens beneath it.
         *
         * <p>Exposed for the same reason {@link #destinationPath()} is, and used for more. Containment
         * checked here is <strong>lexical</strong>: {@link #validate()} compares name elements of two
         * normalized paths, which settles that the configuration <em>names</em> a destination inside the
         * root. It cannot settle where those names <em>lead</em> - a symbolic link anywhere along either
         * path redirects the write while both strings still read as contained, and normalizing does not
         * resolve links. So the writer resolves this root to its real path and opens beneath it without
         * following links; see {@code InternalReaderJobSubmissionPort}.
         *
         * @return the normalized approved-root path
         * @throws InvalidPathException if called on an instance that was never validated and whose
         *                              approved root is not a syntactically valid path
         */
        public Path approvedRootPath() {
            return Paths.get(approvedRoot).normalize();
        }

        /**
         * Requires a value to be present and non-blank.
         *
         * <p>A blank value is treated as absence rather than as a value, because in this file that is
         * what it invariably is: an environment placeholder that resolved to nothing.
         *
         * @param value   the configured value, possibly {@code null}
         * @param key     the property key, quoted in the diagnostic
         * @param purpose what the value is for, so the message says why it cannot be omitted
         * @throws IllegalStateException if {@code value} holds no text
         */
        private static void requirePresent(String value, String key, String purpose) {
            if (!StringUtils.hasText(value)) {
                throw new IllegalStateException(invalid(key)
                        + " it declares no value. " + purpose);
            }
        }

        /**
         * Requires the queue and DD names to be the ones the CSD defines, not merely present.
         *
         * <p>Present-but-wrong was the gap: two values that named a different queue and a different
         * dataset passed every check while the port kept writing 80-byte records to the same
         * destination, so every diagnostic, every log line and every operator reading the
         * configuration was told about a queue this application does not stand in for.
         *
         * <p>Compared case-insensitively, for the same reason the three textual byte-contract values
         * are: the CSD writes them in upper case and the case of a name is not part of the contract.
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

        /**
         * Requires the configured code page to exist on this platform and to be a total single-byte one.
         *
         * <p>The code page is <strong>required</strong> rather than defaulted, and that is the point of
         * the property: a region whose internal reader consumes EBCDIC needs {@code IBM037}, and a port
         * that hard-wired {@code US-ASCII} would emit 80 bytes of the wrong encoding while reporting
         * success. Which one it is cannot be inferred from anything inside this process, so it is
         * declared - and declared once, here, rather than chosen by whichever constructor happened to
         * be wired.
         *
         * <p>Single-byte is not a preference either: {@code RECORDFORMAT(FIXED)} with
         * {@code RECORDSIZE(80)} means 80 characters must encode to exactly 80 bytes, so a code page
         * that emits two bytes for some character cannot satisfy the queue's own definition.
         *
         * @throws IllegalStateException if the code page is absent, unknown to this platform, or not a
         *                               total single-byte code page
         */
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
         * <p>Exposed so the port encodes in the very code page {@link #validate()} approved rather than
         * resolving the string again, and so a test can assert which one reached the runtime.
         *
         * @return the charset
         * @throws IllegalArgumentException if called on an instance that was never validated and whose
         *                                  charset names nothing this platform provides
         */
        public Charset queueCharset() {
            return Charset.forName(charset.trim());
        }

        /**
         * Requires the four transcribed CSD values to be exactly what the CSD declares.
         *
         * <p>Compared case-insensitively for the three textual values, because the CSD writes them in
         * upper case while a YAML author might not, and the case of the word is not part of the byte
         * contract. The length is compared exactly, because it is a byte count.
         *
         * @throws IllegalStateException if any of the four differs from the CSD
         */
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

        /**
         * Requires one configured path to be present, syntactically valid, absolute, free of any
         * upward traversal and not naming a reference tree - and returns it normalized.
         *
         * @param raw the configured path string, possibly {@code null}
         * @param key the property key, quoted in every diagnostic
         * @return the same path, normalized, ready to be compared with the other
         * @throws IllegalStateException if the path is absent, malformed, relative, traversing, or
         *                               names one of the read-only reference trees
         */
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

        /**
         * Requires the destination to resolve strictly inside the approved root.
         *
         * <p><strong>This check is lexical, and it is not the whole of containment.</strong> It settles
         * that the configuration names a destination inside the root, which is what can be settled without
         * touching the filesystem - and it is checked here, at startup, so a misconfiguration is refused
         * before any record is written. What it cannot settle is where those names lead: a symbolic link
         * at the root, at the destination, or at any directory between them redirects the write while both
         * strings still read as contained, and {@link Path#normalize()} does not resolve links. The real
         * containment check therefore happens where the file is opened, against real paths and without
         * following links; see {@code ReportRequestController.InternalReaderJobSubmissionPort}.
         *
         * <p>Uses {@link Path#startsWith(Path)}, which compares whole name elements, so a sibling
         * directory whose name merely begins with the root's name - {@code /var/carddemo-backup}
         * against a root of {@code /var/carddemo} - is correctly outside. A string prefix comparison
         * would have accepted it.
         *
         * <p>Equality is rejected separately: the root is a directory and the destination is the file
         * written within it, so a destination equal to the root asks for a file at a directory's path.
         *
         * @param root   the normalized approved root
         * @param target the normalized destination
         * @throws IllegalStateException if the destination is the root itself or lies outside it
         */
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

        /**
         * Reports whether any element of a path is the upward-traversal segment.
         *
         * <p>Iterates the path's own name elements rather than searching the string, so a legitimate
         * directory whose name merely contains two dots - {@code my..dir} - is not mistaken for
         * traversal.
         *
         * @param path the path to inspect, not normalized
         * @return {@code true} if any element is exactly {@code ".."}
         */
        private static boolean containsTraversal(Path path) {
            for (Path element : path) {
                if (TRAVERSAL_SEGMENT.equals(element.toString())) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Reports whether a normalized path passes through one of the read-only reference trees.
         *
         * <p>Looks for two consecutive name elements forming one of the {@link #REFERENCE_TREES}
         * pairs, at any depth, so it holds for an absolute checkout path as well as for a path
         * relative to the repository root.
         *
         * @param normalized the normalized path to inspect
         * @return {@code true} if the path enters a reference tree
         */
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

        /**
         * Opens every diagnostic the same way, naming the property key at fault.
         *
         * @param key the {@code carddemo.job-submission} sub-key whose value is invalid
         * @return the opening clause of an invalid-value message
         */
        private static String invalid(String key) {
            return "The carddemo.job-submission." + key + " value is invalid:";
        }

        /**
         * Renders a set in a stable order, so a diagnostic reads the same on every run.
         *
         * @param values the values to render
         * @return the values sorted lexicographically
         */
        private static List<String> sorted(Set<String> values) {
            return values.stream().sorted().toList();
        }
    }
}
