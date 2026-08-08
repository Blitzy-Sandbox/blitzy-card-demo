package com.vsergeychik.carddemo.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;

import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.FileStatus;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;

import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
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
 * would be a framework this migration does not include. Equally, nothing here weakens the posture:
 * the error mapping below carries diagnostic text but never a stack trace and never internal
 * application state.
 *
 * @see CobolCharsetConfig for dataset encoding, which is a separate concern from HTTP encoding
 */
@Configuration
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
     * <h2>Two rules govern every handler below</h2>
     * <ol>
     *   <li><strong>Message text is observable behaviour.</strong> The COBOL programs report their
     *       own diagnostics - into {@code SYSOUT} for a batch program, into the screen's error field
     *       for an online one - and that text is exactly what the parity harness compares. No
     *       handler here translates, localises, rewords, prefixes, truncates or re-cases it.</li>
     *   <li><strong>Nothing internal escapes.</strong> No stack trace, no exception class name, no
     *       framework binding detail and no configuration value reaches a response body. This
     *       matches {@code server.error.include-stacktrace: never} in {@code application.yml}, and it
     *       is why the handlers read specific accessors rather than serialising an exception.</li>
     * </ol>
     *
     * <p>No handler swallows an exception, and none returns a success status for a failure. Every
     * decision a handler makes is also reachable through a small package-private or public static
     * method, so a plain JUnit test can drive each one directly, with no {@code MockMvc} and no
     * servlet container in the path.
     */
    @RestControllerAdvice
    public static class CobolErrorHandler {

        /**
         * Constructs the advice. It is stateless and holds no field, so one instance serves every
         * request; this is declared explicitly rather than left implicit to make that plain.
         */
        public CobolErrorHandler() {
            // Intentionally empty. The advice derives every response solely from its argument.
        }

        /**
         * Maps an abend - the Java form of {@code CALL 'CEE3ABD'}, which appears at nine sites
         * across the batch programs - onto {@code 500 Internal Server Error}.
         *
         * <p>{@code 500} is the honest status: the COBOL did not complete its unit of work, it
         * terminated abnormally. The response body carries the {@code RETURN-CODE} the program had
         * placed in {@code APPL-RESULT}, the abending program's {@code PROGRAM-ID}, and the
         * exception's message text exactly as composed.
         *
         * @param abend the abend raised by a service, job or repository
         * @return {@code 500} with the abend detail
         */
        @ExceptionHandler(AbendException.class)
        public ResponseEntity<AbendResponse> handleAbend(final AbendException abend) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(abendResponse(abend));
        }

        /**
         * Builds the abend body, and is the directly testable form of {@link
         * #handleAbend(AbendException)}.
         *
         * <p>The message is taken verbatim from {@link AbendException#getMessage()}, which already
         * reads {@code ABENDING PROGRAM <program> RETURN-CODE=<n>} followed by the {@code ABCODE}
         * and {@code TIMING} arguments and any reason text. Copying it unchanged is what preserves
         * the {@code ABENDING PROGRAM} literal and the program name byte for byte; composing a new
         * sentence here, however similar, would not. The program and return code are additionally
         * surfaced as their own fields so that a client need not parse the message to obtain them.
         *
         * @param abend the abend to describe; must not be {@code null}
         * @return the response body, with no text altered
         */
        static AbendResponse abendResponse(final AbendException abend) {
            return new AbendResponse(abend.getProgram(), abend.getReturnCode(), abend.getMessage());
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
         * The body of a {@code 500} raised by an abend.
         *
         * @param program    the abending program's COBOL {@code PROGRAM-ID}
         * @param returnCode the value the program placed in {@code APPL-RESULT}, preserved as the
         *                   COBOL set it - typically {@code 0}, {@code 4}, {@code 8}, {@code 12} or
         *                   {@code 16}
         * @param message    the abend text exactly as {@link AbendException#getMessage()} composed
         *                   it, including the {@code ABENDING PROGRAM} literal
         */
        public record AbendResponse(String program, int returnCode, String message) {
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
    }
}
