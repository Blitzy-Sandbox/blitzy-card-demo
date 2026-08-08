package com.vsergeychik.carddemo.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;

import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.DiagnosticText;
import com.vsergeychik.carddemo.common.FileStatus;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.StringUtils;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
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
     * Applies the two Jackson settings that protect the migration's numeric parity.
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
     * <p>No date module and no date pattern is registered either: COBOL dates in this estate are
     * {@code PIC X(n)} character fields, and reformatting them through a {@code java.time}
     * serializer would change observable output.
     *
     * @return the customizer Spring Boot applies to the shared {@code ObjectMapper} builder
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer carddemoJacksonCustomizer() {
        return builder -> builder
                .featuresToEnable(
                        DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,
                        JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN)
                .featuresToDisable(
                        DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT);
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
     * {@code config} package is exactly four classes - {@link CobolCharsetConfig},
     * {@link DataSourceConfig}, {@code BatchConfig} and this one - and the error mapping belongs
     * with the rest of the web-layer configuration rather than becoming a fifth.
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

        public CobolErrorHandler() {
            // Intentionally empty. The advice derives every response solely from its argument.
        }

        /**
         * Maps an abend - the Java form of {@code CALL 'CEE3ABD'}, which appears at nine sites
         * across the batch programs - onto {@code 500 Internal Server Error}.
         *
         * <p>{@code 500} is the honest status: the COBOL did not complete its unit of work, it
         * terminated abnormally. The <em>body</em> says only that, through {@link #ABEND_CODE} and
         * {@link #ABEND_MESSAGE}. The abending program's {@code PROGRAM-ID}, the {@code RETURN-CODE}
         * it had placed in {@code APPL-RESULT} and the composed {@code ABENDING PROGRAM} sentence -
         * which can carry a repository's or a dataset's own reason text, and can nest a further
         * failure's context - are written to the server log by this method and go no further. They
         * describe the inside of the application and belong on the server's side of the trust
         * boundary.
         *
         * @param abend the abend raised by a service, job or repository
         * @return {@code 500} with the stable abend code and its generic message
         */
        @ExceptionHandler(AbendException.class)
        public ResponseEntity<ErrorResponse> handleAbend(final AbendException abend) {
            logAbend(abend);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(abendResponse(abend));
        }

        /**
         * Builds the abend body, and is the directly testable form of {@link
         * #handleAbend(AbendException)}.
         *
         * <p>It reads nothing at all from the argument. That is the point: the body is a constant, so
         * there is no path by which a program name, a return code or a reason string can reach a
         * client, however an abend was composed. The argument is still taken, because a body builder
         * that ignores its input is the only honest way to state that the input is deliberately
         * unused, and because {@link #handleAbend(AbendException)} must have exactly one place to
         * delegate to.
         *
         * @param abend the abend being answered; must not be {@code null}
         * @return the response body: {@link #ABEND_CODE} and {@link #ABEND_MESSAGE}, always
         */
        static ErrorResponse abendResponse(final AbendException abend) {
            Objects.requireNonNull(abend, "An abend is required to answer one");
            return new ErrorResponse(ABEND_CODE, ABEND_MESSAGE);
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
                    + " RETURN-CODE=" + abend.getReturnCode(), abend);
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
        public ResponseEntity<ErrorResponse> handleUnreadableRequestBody(
                final HttpMessageNotReadableException unreadable) {
            logUnreadableRequestBody(unreadable);
            return ResponseEntity.badRequest().body(malformedRequestResponse(unreadable));
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
        static ErrorResponse malformedRequestResponse(
                final HttpMessageNotReadableException unreadable) {
            Objects.requireNonNull(unreadable, "A binding failure is required to answer one");
            return new ErrorResponse(MALFORMED_REQUEST_CODE, MALFORMED_REQUEST_MESSAGE);
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
                // unescaped newline in a log line is a forged log entry (CWE-117).
                final String namedFields = unreadableBodyResponse(unreadable).fieldErrors().stream()
                        .map(FieldMessage::field)
                        .map(DiagnosticText::singleLine)
                        .collect(Collectors.joining(", "));
                LOG.debug("Request body rejected with HTTP " + HttpStatus.BAD_REQUEST.value()
                        + "; unreadable at field(s): "
                        + (namedFields.isEmpty() ? "none named - the document itself is unreadable"
                                : namedFields),
                        unreadable);
            }
        }

        /**
         * Maps a repository outcome onto the HTTP status that reports it, and is deliberately the
         * thinnest mapping that still distinguishes the one fatal case.
         *
         * <p>{@link FileStatus} funnels both worlds into a single five-valued vocabulary: a batch
         * program's two-character {@code FILE STATUS} through
         * {@link FileStatus#outcomeOfStatus(String)}, and an online program's CICS {@code RESP}
         * through {@link FileStatus#outcomeOfCicsResp(int)}. The five outcomes divide cleanly in two:
         *
         * <ul>
         *   <li>{@code OK} ({@code '00'}, {@code NORMAL}), {@code END_OF_FILE} ({@code '10'},
         *       {@code ENDFILE}), {@code NOT_FOUND} ({@code '23'}, {@code NOTFND}) and
         *       {@code DUPLICATE} ({@code '22'}, {@code DUPREC} and {@code DUPKEY}) are all outcomes
         *       the COBOL guard chains <em>handle in the program</em>. End of file terminates a
         *       browse normally; a missing or duplicate record makes the program move its own text
         *       into the screen's error field and re-send the map, and the transaction then completes
         *       normally. The HTTP request has succeeded in every one of those cases, so all four map
         *       to {@code 200 OK} and the outcome is reported in the payload.</li>
         *   <li>{@code OTHER} is the {@code WHEN OTHER} arm, which every guard chain in the estate
         *       treats as fatal - it displays the status and abends - so it maps to {@code 500}.</li>
         * </ul>
         *
         * <p>{@code app/cbl/CBSTM03A.CBL:353-359} states the division outright:
         * <pre>
         * EVALUATE WS-M03B-RC
         *     WHEN '00' CONTINUE
         *     WHEN '10' MOVE 'Y' TO END-OF-FILE
         *     WHEN OTHER  &lt;display then abend&gt;
         * END-EVALUATE
         * </pre>
         *
         * <p>Two mappings are therefore <strong>deliberately not</strong> used.
         * {@code NOT_FOUND} does not become {@code 404}: a bare {@code 404} carries no body, so it
         * would discard the very error text the COBOL painted, and that text is parity-relevant.
         * {@code DUPLICATE} does not become {@code 409} for the same reason - the COBOL reports a
         * rejected duplicate as a message on a normally-completing screen, not as a transport-level
         * conflict. Choosing either would invent an HTTP semantic the legacy system never had.
         *
         * <p>The wording of the message that accompanies an outcome is <em>not</em> decided here. It
         * belongs to the service or controller that knows which screen field it is filling and which
         * COBOL literal it must reproduce; this method decides only the status.
         *
         * <p>It is written as an exhaustive {@code switch} over the enumeration rather than as a
         * branch-free map lookup with a default, and that is a deliberate trade. A lookup would
         * report no branches at all, but it would also silently give any constant later added to
         * {@link FileStatus.Outcome} whatever the default happens to be. The {@code switch} makes the
         * compiler refuse to build until a human has decided the new outcome's status, which is worth
         * more on a migration judged on behavioural equivalence than the two branches it costs. Both
         * of those branches are reachable from a plain unit test that passes each enum constant.
         *
         * @param outcome the outcome a repository reported; must not be {@code null}
         * @return {@link HttpStatus#OK} for the four outcomes the COBOL handles in-program, and
         *         {@link HttpStatus#INTERNAL_SERVER_ERROR} for {@code OTHER}
         * @throws NullPointerException if {@code outcome} is {@code null}, because there is no
         *                              defensible status for an absent outcome
         */
        public static HttpStatus statusForOutcome(final FileStatus.Outcome outcome) {
            return switch (outcome) {
                // Handled in-program by the COBOL: the request itself succeeded, and the outcome
                // travels in the payload alongside the program's own message text.
                case OK, END_OF_FILE, NOT_FOUND, DUPLICATE -> HttpStatus.OK;
                // WHEN OTHER: the arm every guard chain in the estate displays and then abends on.
                case OTHER -> HttpStatus.INTERNAL_SERVER_ERROR;
            };
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
        public ResponseEntity<ValidationResponse> handleInvalidRequestBody(
                final MethodArgumentNotValidException invalid) {
            return ResponseEntity.badRequest().body(validationResponse(invalid));
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
        static ValidationResponse validationResponse(final MethodArgumentNotValidException invalid) {
            final List<FieldMessage> rejected = invalid.getBindingResult().getFieldErrors().stream()
                    .map(CobolErrorHandler::fieldMessage)
                    .sorted(Comparator.comparing(FieldMessage::field))
                    .toList();
            return new ValidationResponse(HttpStatus.BAD_REQUEST.getReasonPhrase(), rejected);
        }

        /**
         * Maps a constraint violation raised outside request-body binding - on a path variable, a
         * query parameter or a validated service argument - onto {@code 400 Bad Request}.
         *
         * @param violations the violations the validator collected
         * @return {@code 400} with one entry per violation
         */
        @ExceptionHandler(ConstraintViolationException.class)
        public ResponseEntity<ValidationResponse> handleConstraintViolation(
                final ConstraintViolationException violations) {
            return ResponseEntity.badRequest().body(validationResponse(violations));
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
        static ValidationResponse validationResponse(final ConstraintViolationException violations) {
            final List<FieldMessage> rejected = violations.getConstraintViolations().stream()
                    .map(CobolErrorHandler::fieldMessage)
                    .sorted(Comparator.comparing(FieldMessage::field))
                    .toList();
            return new ValidationResponse(HttpStatus.BAD_REQUEST.getReasonPhrase(), rejected);
        }

        /**
         * Maps an unreadable request body - malformed JSON, or a value of the wrong JSON type for the
         * field it was given to - onto {@code 400 Bad Request}.
         *
         * <p>Needed because Jackson's own message quotes the offending source text, so before this
         * handler existed the exception reached the framework error page and published a fragment of
         * the caller's payload. The path Jackson records is field <em>names</em> only, which is
         * exactly the part worth reporting.
         *
         * @param unreadable the failure the message converter raised
         * @return {@code 400} naming the field the body failed at, and the category of the failure
         */
        static ResponseEntity<ValidationResponse> unreadableBodyFieldReport(
                final HttpMessageNotReadableException unreadable) {
            return ResponseEntity.badRequest().body(unreadableBodyResponse(unreadable));
        }

        /**
         * Builds the body for an unreadable request body, and is the directly testable form of
         * {@link #handleUnreadableRequestBody(HttpMessageNotReadableException)}.
         *
         * <p>Only {@link JsonMappingException.Reference#getFieldName()} is read from the mapping path,
         * never {@link JsonMappingException#getMessage()} and never the source location, because the
         * message is where Jackson quotes the payload. A path element that names an array index rather
         * than a field contributes nothing, and a failure with no usable path at all - a body that is
         * not JSON, so Jackson never reached a field - yields an empty list, which is the honest
         * answer: no field is at fault, the document is.
         *
         * @param unreadable the failure; must not be {@code null}
         * @return the response body, carrying field names and a fixed category message only
         */
        static ValidationResponse unreadableBodyResponse(
                final HttpMessageNotReadableException unreadable) {
            final List<FieldMessage> rejected =
                    unreadable.getCause() instanceof JsonMappingException mapping
                            ? mapping.getPath().stream()
                                    .map(JsonMappingException.Reference::getFieldName)
                                    .filter(field -> field != null && !field.isBlank())
                                    .map(field -> new FieldMessage(field, UNREADABLE_FIELD_DETAIL))
                                    .sorted(Comparator.comparing(FieldMessage::field))
                                    .toList()
                            : List.of();
            return new ValidationResponse(HttpStatus.BAD_REQUEST.getReasonPhrase(), rejected);
        }

        /**
         * Maps a path variable or query parameter that could not be converted to its declared type
         * onto {@code 400 Bad Request}.
         *
         * <p>The parameter name and the type it had to become are reported; the value that failed to
         * become it is not. An account identifier arriving where an eleven-digit number was expected
         * is precisely the case, and precisely the value not to echo.
         *
         * @param mismatch the conversion failure Spring raised
         * @return {@code 400} naming the parameter and the type required
         */
        @ExceptionHandler(MethodArgumentTypeMismatchException.class)
        public ResponseEntity<ValidationResponse> handleArgumentTypeMismatch(
                final MethodArgumentTypeMismatchException mismatch) {
            return ResponseEntity.badRequest().body(typeMismatchResponse(mismatch));
        }

        /**
         * Builds the body for a type mismatch, and is the directly testable form of
         * {@link #handleArgumentTypeMismatch(MethodArgumentTypeMismatchException)}.
         *
         * <p>{@link MethodArgumentTypeMismatchException#getRequiredType()} is nullable, so the message
         * degrades to naming no type rather than failing while an error response is being built.
         * Reporting a validation failure imprecisely beats not reporting it.
         *
         * @param mismatch the conversion failure; must not be {@code null}
         * @return the response body, naming the parameter and the required type but no value
         */
        static ValidationResponse typeMismatchResponse(
                final MethodArgumentTypeMismatchException mismatch) {
            final Class<?> required = mismatch.getRequiredType();
            final String detail = required == null
                    ? "is not of the required type"
                    : "is not a valid " + required.getSimpleName();
            return new ValidationResponse(HttpStatus.BAD_REQUEST.getReasonPhrase(),
                    List.of(new FieldMessage(mismatch.getName(), detail)));
        }

        /**
         * Maps a value a domain guard rejected onto {@code 400 Bad Request}.
         *
         * <p>These are the width and shape guards in the record models, the request DTOs and
         * {@code FixedWidthCodec}: a {@code PIC X(16)} field handed seventeen characters, a signed
         * zoned span whose trailing byte is not a sign overpunch. The caller supplied the value, so
         * {@code 400} is the honest status - {@code 500} would blame the server for the caller's
         * input.
         *
         * <p>The exception's message is deliberately <strong>not</strong> copied. Those guards are
         * handed the value they are judging and their messages name the field, its declared width and
         * the category of the failure; a fixed sentence is returned here instead, because a guard added
         * later must not be able to widen this response by wording its message differently. The full
         * message stays in the server log, where the operator who needs it can see it and an
         * unauthenticated caller cannot.
         *
         * @param rejected the guard failure
         * @return {@code 400} with a fixed, value-free explanation
         */
        @ExceptionHandler(IllegalArgumentException.class)
        public ResponseEntity<FaultResponse> handleRejectedValue(
                final IllegalArgumentException rejected) {
            LOG.warn("Rejected a request because a field value did not fit its COBOL picture; "
                    + "responding " + HttpStatus.BAD_REQUEST.value() + " with no value echoed.",
                    rejected);
            return ResponseEntity.badRequest().body(rejectedValueResponse());
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
        static FaultResponse rejectedValueResponse() {
            return new FaultResponse(HttpStatus.BAD_REQUEST.getReasonPhrase(), REJECTED_VALUE_DETAIL);
        }

        /**
         * Maps a server-side state or configuration fault onto {@code 500 Internal Server Error}.
         *
         * <p>An {@link IllegalStateException} in this module means a dataset binding is missing or
         * contradictory, a repository handle was used after being closed, or a locking read was issued
         * outside a unit of work. None of those is anything the caller did, so {@code 500} is correct
         * and {@code 400} would be a lie. As with a rejected value the message is logged rather than
         * published, because these messages name dataset names and configuration keys.
         *
         * @param fault the state fault
         * @return {@code 500} with a fixed, value-free explanation
         */
        @ExceptionHandler(IllegalStateException.class)
        public ResponseEntity<FaultResponse> handleInternalState(final IllegalStateException fault) {
            LOG.error("A request could not be served because the server is not in a state to serve "
                    + "it; responding " + HttpStatus.INTERNAL_SERVER_ERROR.value()
                    + " with no configuration detail echoed.", fault);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(internalStateResponse());
        }

        /**
         * The body for a state fault, and the directly testable form of
         * {@link #handleInternalState(IllegalStateException)}.
         *
         * @return the response body, identical for every state fault
         */
        static FaultResponse internalStateResponse() {
            return new FaultResponse(HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(),
                    INTERNAL_STATE_DETAIL);
        }

        /**
         * Converts one rejected field into its response entry.
         *
         * @param error the field error Spring produced
         * @return the entry naming the field and the validator's message
         */
        private static FieldMessage fieldMessage(final FieldError error) {
            return new FieldMessage(error.getField(), error.getDefaultMessage());
        }

        /**
         * Converts one constraint violation into its response entry.
         *
         * <p>The property path is rendered through {@link String#valueOf(Object)} so that a
         * violation carrying no path yields the string {@code "null"} rather than throwing while an
         * error response is being built. Failing to report a validation failure would be worse than
         * reporting one imprecisely.
         *
         * @param violation the violation the validator produced
         * @return the entry naming the property path and the validator's message
         */
        private static FieldMessage fieldMessage(final ConstraintViolation<?> violation) {
            return new FieldMessage(String.valueOf(violation.getPropertyPath()), violation.getMessage());
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
        static ResponseEntity<FailureResponse> handleUnreadableBody(
                final HttpMessageNotReadableException unreadable) {
            return sanitized(HttpStatus.BAD_REQUEST, UNREADABLE_BODY_MESSAGE);
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
         * @param mismatch the conversion failure, whose message is intentionally discarded
         * @return {@code 400} carrying {@link #TYPE_MISMATCH_MESSAGE} and nothing else
         */
        @ExceptionHandler(TypeMismatchException.class)
        public ResponseEntity<FailureResponse> handleTypeMismatch(
                final TypeMismatchException mismatch) {
            return sanitized(HttpStatus.BAD_REQUEST, TYPE_MISMATCH_MESSAGE);
        }

        /**
         * Maps a data-access failure onto {@code 500 Internal Server Error}.
         *
         * <p>{@code 500} is correct and not a fallback: the unit of work did not complete. Note what
         * this handler is <em>not</em> for - a record that was simply absent or duplicate is an
         * outcome the COBOL guard chains handle in-program, and it travels as a {@link FileStatus}
         * outcome through {@link #statusForOutcome(FileStatus.Outcome)} on a {@code 200}, never as an
         * exception. Reaching here means the access itself failed.
         *
         * @param failure the data-access failure, whose message is intentionally discarded
         * @return {@code 500} carrying {@link #DATASET_ACCESS_MESSAGE} and nothing else
         */
        @ExceptionHandler(DataAccessException.class)
        public ResponseEntity<FailureResponse> handleDataAccessFailure(
                final DataAccessException failure) {
            return sanitized(HttpStatus.INTERNAL_SERVER_ERROR, DATASET_ACCESS_MESSAGE);
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
        public ResponseEntity<FailureResponse> handleUnexpectedFailure(final Exception failure) {
            return sanitized(statusForFailure(failure), UNEXPECTED_FAILURE_MESSAGE);
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
        static ResponseEntity<FailureResponse> sanitized(final HttpStatusCode status,
                final String message) {
            return ResponseEntity.status(status).body(sanitizedBody(status, message));
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
        static FailureResponse sanitizedBody(final HttpStatusCode status, final String message) {
            return new FailureResponse(status.value(), reasonPhraseOf(status), message);
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
         * The body of a {@code 500} raised by an abend.
         *
         * <p>Used for both a {@code 500} raised by an abend and a {@code 400} raised by an unreadable
         * request body. Two fields only, and both are constants this module authored - there is no
         * member for a program name, a return code, an exception message, a property path or a parse
         * position, so no such value can be added to a body by accident.
         *
         * @param code    the stable code a client branches on: {@link CobolErrorHandler#ABEND_CODE} or
         *                {@link CobolErrorHandler#MALFORMED_REQUEST_CODE}
         * @param message the generic sentence for that code, carrying no internal state
         */
        public record ErrorResponse(String code, String message) {
        }

        /**
         * The body of a {@code 400} raised by a validation failure.
         *
         * @param error       the standard HTTP reason phrase for the status. It is deliberately the
         *                    reason phrase and not invented prose: no COBOL literal is claimed here,
         *                    because the parity-relevant text is the per-field message and, on a
         *                    screen, the error field the controller fills
         * @param fieldErrors one entry per rejected field, ordered by field name
         */
        public record ValidationResponse(String error, List<FieldMessage> fieldErrors) {
        }

        /**
         * One rejected field within a validation failure.
         *
         * @param field   the field or property path that failed
         * @param message the validator's own message for that field
         */
        public record FieldMessage(String field, String message) {
        }

        /**
         * The body of a sanitized failure: a status, its reason phrase, and one of this class's fixed
         * message constants.
         *
         * <p>It is a separate shape from {@link ValidationResponse} rather than that record with an
         * empty {@code fieldErrors} list, and the distinction is meaningful. An empty list would
         * assert that the request was examined field by field and every field passed - which is
         * exactly what did not happen when a body could not be parsed at all.
         *
         * @param status  the numeric HTTP status, always the status of the response carrying this body
         * @param error   the registered reason phrase for that status, or the empty string where the
         *                status has none. Never invented prose
         * @param message one of {@link #UNREADABLE_BODY_MESSAGE},
         *                {@link #TYPE_MISMATCH_MESSAGE}, {@link #DATASET_ACCESS_MESSAGE} or
         *                {@link #UNEXPECTED_FAILURE_MESSAGE}. It is chosen by the handler and is
         *                never derived from the failure, so no exception message, class name, SQL
         *                fragment, filesystem path or configuration value can appear here
         */
        /**
         * The body of a failure that names no field: a value a domain guard rejected, or a
         * server-side state fault.
         *
         * <p>Two components and no third. There is deliberately nowhere here for an exception
         * message, a field value or a configuration value to go, so a guard added later cannot widen
         * what this boundary publishes merely by wording its message differently. Both builders that
         * produce one - {@link CobolErrorHandler#rejectedValueResponse()} and
         * {@link CobolErrorHandler#internalStateResponse()} - take no argument at all, which is the
         * structural form of that guarantee rather than a convention to be remembered.
         *
         * @param error  the standard HTTP reason phrase for the status carrying this body
         * @param detail a fixed sentence describing the category of the failure, identical for every
         *               occurrence of that category and carrying no value
         */
        public record FaultResponse(String error, String detail) {
        }

        public record FailureResponse(int status, String error, String message) {
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
     * @param queueName    the TDQ name, {@code JOBS} in the CSD. Carried so the port can name the
     *                     queue it is standing in for; required to be present
     * @param ddName       the {@code DDNAME}, {@code INREADER} in the CSD. Required to be present
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
            int recordLength,
            String recordFormat,
            String blockFormat,
            String disposition,
            String approvedRoot,
            String destination) {

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
            requireByteContract();
            Path root = requireUsablePath(approvedRoot, "approved-root");
            Path target = requireUsablePath(destination, "destination");
            requireContainment(root, target);
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
