/*
 * ******************************************************************
 * Program     : BodyFailureEnvelopeContractTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Proves that the two framework-level request-body failures -
 *               a bean-validation refusal and a body that could not be read
 *               at all - are answered by each of the seven body-bearing
 *               controllers with that controller's own fixed ProblemDetail
 *               envelope, that neither answer discloses a rejected value, a
 *               field name, a constraint message, a parser diagnostic or an
 *               exception class, that MenuController correctly declares
 *               neither handler, and that the envelope is produced without
 *               any @ControllerAdvice.
 * Source      : app/csd/CARDDEMO.CSD       (the CC00-CU03 transactions whose
 *                                          bodies these handlers guard)
 *               app/cpy/CSSETATY.cpy       (the two-state error marker the
 *                                          framework has no counterpart to)
 *               app/cbl/COUSR01C.cbl:L237  (the add-user password field whose
 *                                          submitted value a field error
 *                                          retains) @ 7756d89
 * ******************************************************************
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 * ******************************************************************
 */
package com.cardemo.unit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.cardemo.controller.AccountController;
import com.cardemo.controller.AdminController;
import com.cardemo.controller.AuthController;
import com.cardemo.controller.BillingController;
import com.cardemo.controller.CardController;
import com.cardemo.controller.MenuController;
import com.cardemo.controller.ReportController;
import com.cardemo.controller.TransactionController;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.UserCreateRequest;
import com.cardemo.model.dto.SignOnRequest;
import com.cardemo.observability.CorrelationIdFilter;
import com.cardemo.security.SnapshotTokenService;
import com.cardemo.service.account.AccountUpdateService;
import com.cardemo.service.account.AccountViewService;
import com.cardemo.service.admin.UserAddService;
import com.cardemo.service.admin.UserDeleteService;
import com.cardemo.service.admin.UserListService;
import com.cardemo.service.admin.UserUpdateService;
import com.cardemo.service.auth.AuthenticationService;
import com.cardemo.service.billing.BillPaymentService;
import com.cardemo.service.card.CardDetailService;
import com.cardemo.service.card.CardListService;
import com.cardemo.service.card.CardUpdateService;
import com.cardemo.service.report.ReportSubmissionService;
import com.cardemo.service.transaction.TransactionAddService;
import com.cardemo.service.transaction.TransactionDetailService;
import com.cardemo.service.transaction.TransactionListService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver;

/**
 * The two request-body failures the framework raises before a mapped method is ever entered.
 *
 * <p><strong>What was wrong.</strong> {@code @Valid @RequestBody} is enforced by Spring MVC's argument
 * resolver, not by the controller, so a malformed body, a body carrying an unknown property, a missing body and
 * a Jakarta constraint violation all became a framework exception that no {@code @ExceptionHandler} in this
 * package claimed. Each therefore escaped to the framework's default error handling and answered with a body
 * that carried no {@code title}, no {@code errorCode} and no {@code correlationId} - a different contract from
 * every other refusal the same route can produce. A review recorded that as a High-severity error-serialization
 * finding against the sign-on and user-administration controllers, and the identical defect existed on all five
 * remaining controllers that bind a body.
 *
 * <p><strong>What must now hold, and is asserted here.</strong> Each of the seven body-bearing controllers
 * answers both conditions itself, with {@code 400} and its own fixed envelope. Neither answer may disclose the
 * value the caller submitted, the field the violation names, the interpolated constraint message, the parser's
 * diagnostic or the exception class - the first of those being, on the add-user body, a plaintext password, and
 * on the account update body a social security number, because Spring's {@code FieldError} retains the rejected
 * value alongside its message.
 *
 * <p><strong>What must NOT have happened.</strong> The checkpoint prohibits a global advice class, so the fix
 * had to be per-controller. That is asserted directly: no source in the main tree declares
 * {@code @ControllerAdvice}, {@code @RestControllerAdvice} or extends {@code ResponseEntityExceptionHandler},
 * and no ninth class appeared in the controller package.
 *
 * <p>{@code MenuController} is asserted to declare <em>neither</em> handler. It is the one controller with no
 * {@code @RequestBody} parameter, so both conditions are unreachable on it and a handler there would be dead
 * code.
 */
@DisplayName("Controllers: a framework body failure answers with the controller's own fixed envelope")
class BodyFailureEnvelopeContractTest {

    /** The fixed detail every bean-validation refusal publishes, identical on all seven controllers. */
    private static final String EXPECTED_BIND_DETAIL =
            "One or more fields of the request body failed validation. Correct the body and resubmit.";

    /** The fixed detail every unreadable-body refusal publishes, identical on all seven controllers. */
    private static final String EXPECTED_UNREADABLE_DETAIL =
            "The request body could not be read as JSON matching this operation's schema.";

    /**
     * The code a bean-validation refusal publishes.
     *
     * <p>The same code the service-tier refusal publishes, deliberately: a caller sees one code per condition
     * rather than one code per layer that happened to notice it.
     */
    private static final String EXPECTED_BIND_CODE = "CARDDEMO-VALIDATION-REJECTED";

    /** The code an unreadable body publishes, distinct because a body that never parsed is a distinct event. */
    private static final String EXPECTED_UNREADABLE_CODE = "CARDDEMO-REQUEST-BODY-UNREADABLE";

    /** The value the caller submitted, which the framework's field error retains and no body may publish. */
    private static final String SUBMITTED_VALUE = "ZZPLAINTEXTPASSWORDVALUE";

    /** The interpolated constraint message, withheld because it describes the schema. */
    private static final String CONSTRAINT_MESSAGE = "ZZSIZEMUSTBEBETWEENONEANDEIGHT";

    /** The field the violation names, withheld because a violation set has no stable iteration order. */
    private static final String VIOLATED_FIELD = "zzViolatedField";

    /** The parser diagnostic, withheld because it names the deserialiser's own stream class and offset. */
    private static final String PARSER_DIAGNOSTIC =
            "ZZUNRECOGNIZEDFIELD at [Source: (ZZNonClosingInputStream); line: 1, column: 17]";

    /** Everything the two answers must never contain, in one place so no assertion can forget one. */
    private static final List<String> WITHHELD_VALUES = List.of(
            SUBMITTED_VALUE, CONSTRAINT_MESSAGE, VIOLATED_FIELD, PARSER_DIAGNOSTIC,
            "ZZNonClosingInputStream", "MethodArgumentNotValidException", "HttpMessageNotReadableException",
            "BeanPropertyBindingResult", "FieldError");

    /** The only two members either answer may carry beyond the four the problem type itself declares. */
    private static final List<String> PERMITTED_PROPERTIES = List.of("errorCode", "correlationId");

    /** The correlation identifier placed in the diagnostic context for the duration of each test. */
    private static final String CORRELATION_ID = "b0dy-f41lur3-c0rrel4t10n-1d";

    /** The name of the handler claiming the framework's bean-validation refusal. */
    private static final String BIND_HANDLER = "handleBodyBindFailure";

    /** The name of the handler claiming a body the framework could not read. */
    private static final String UNREADABLE_HANDLER = "handleUnreadableBody";

    /** The repository root, located by structure rather than assumed from the working directory. */
    private static final Path ROOT = repositoryRoot();

    @BeforeEach
    void placeCorrelationIdentifier() {
        MDC.put(CorrelationIdFilter.MDC_KEY_CORRELATION_ID, CORRELATION_ID);
    }

    @AfterEach
    void clearCorrelationIdentifier() {
        MDC.remove(CorrelationIdFilter.MDC_KEY_CORRELATION_ID);
    }

    /**
     * Walks upward from the working directory to the directory holding both {@code pom.xml} and {@code app/}.
     *
     * @return the repository root, never {@code null}
     */
    private static Path repositoryRoot() {
        final Path start = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path candidate = start; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("pom.xml"))
                    && Files.isDirectory(candidate.resolve("app"))) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "No directory from " + start + " upward holds both pom.xml and app/, so the controller "
                        + "package cannot be located. Run this test from the repository root or beneath it.");
    }

    /**
     * A stand-in for the mapped method whose body the framework was binding when the violation was raised.
     *
     * <p>Never invoked. {@code MethodArgumentNotValidException} requires the {@code MethodParameter} the
     * framework was working on, and only that parameter's reflective metadata is read. Declaring the stand-in
     * here keeps the fixture independent of which of the seven controllers is being driven, and none of the
     * fourteen handlers under test reads the parameter at all.
     *
     * @param body the stand-in request-body parameter, which exists so a {@code MethodParameter} can be built
     *             and is never read
     */
    private static void boundRequestBody(final String body) {
        // Intentionally empty: this method exists to be reflected on, not to be called. See the Javadoc.
    }

    /**
     * Builds the framework's bean-validation failure, carrying a field error with all three sentinels.
     *
     * @return a populated bind failure, never {@code null}
     */
    private static MethodArgumentNotValidException aBindFailure() {
        final Method bound;
        try {
            bound = BodyFailureEnvelopeContractTest.class
                    .getDeclaredMethod("boundRequestBody", String.class);
        } catch (final NoSuchMethodException absent) {
            throw new AssertionError("the stand-in bound method must exist for this fixture", absent);
        }

        final BindingResult binding = new BeanPropertyBindingResult(new Object(), "requestBody");
        binding.addError(new FieldError("requestBody", VIOLATED_FIELD, SUBMITTED_VALUE, false, null, null,
                CONSTRAINT_MESSAGE));

        return new MethodArgumentNotValidException(new MethodParameter(bound, 0), binding);
    }

    /**
     * Builds the framework's read failure, carrying the diagnostic shape a Jackson failure actually composes.
     *
     * @return a populated read failure, never {@code null}
     */
    private static HttpMessageNotReadableException anUnreadableBody() {
        return new HttpMessageNotReadableException(PARSER_DIAGNOSTIC,
                new ServletServerHttpRequest(new MockHttpServletRequest()));
    }

    /**
     * The seven controllers that bind a request body, each paired with the title its envelope publishes.
     *
     * <p>Instances rather than classes, because the assertion drives the handler and inspects the body it
     * builds. Every service is a mock and none is invoked: a handler is a plain method call.
     *
     * @return one entry per body-bearing controller, in a stable order
     */
    private static Map<Object, String> bodyBearingControllersAndTitles() {
        final Map<Object, String> expected = new LinkedHashMap<>();
        expected.put(new AccountController(mock(AccountViewService.class), mock(AccountUpdateService.class)),
                "Account request rejected");
        expected.put(new AdminController(mock(UserListService.class), mock(UserAddService.class),
                mock(UserUpdateService.class), mock(UserDeleteService.class)),
                "User administration request rejected");
        expected.put(new AuthController(mock(AuthenticationService.class)), "Sign-on rejected");
        expected.put(new BillingController(mock(BillPaymentService.class)), "Bill payment rejected");
        expected.put(new CardController(mock(CardListService.class), mock(CardDetailService.class),
                mock(CardUpdateService.class), mock(SnapshotTokenService.class)), "Card request rejected");
        expected.put(new ReportController(mock(ReportSubmissionService.class)), "Report submission rejected");
        expected.put(new TransactionController(mock(TransactionListService.class),
                mock(TransactionDetailService.class), mock(TransactionAddService.class)),
                "Transaction request rejected");
        return expected;
    }

    /**
     * Invokes one of the two handlers on a controller instance by name.
     *
     * <p>Reflective because the seven controllers are deliberately unrelated types - there is no shared base
     * class and no interface, which is the property the checkpoint requires - so no single static call site can
     * reach all seven.
     *
     * @param controller  the controller instance to drive
     * @param handlerName the handler to invoke
     * @param argument    the framework exception to hand it
     * @return the response the handler built, never {@code null}
     */
    @SuppressWarnings("unchecked")
    private static ResponseEntity<ProblemDetail> invoke(final Object controller, final String handlerName,
            final Object argument) {
        final Method handler;
        try {
            handler = controller.getClass().getDeclaredMethod(handlerName, handlerParameter(handlerName));
        } catch (final NoSuchMethodException absent) {
            throw new AssertionError(
                    controller.getClass().getSimpleName() + " must declare " + handlerName
                            + ": without it the framework answers this condition with its own body shape",
                    absent);
        }
        try {
            return (ResponseEntity<ProblemDetail>) handler.invoke(controller, argument);
        } catch (final IllegalAccessException | InvocationTargetException failure) {
            throw new AssertionError(
                    "invoking " + controller.getClass().getSimpleName() + '.' + handlerName + " failed",
                    failure);
        }
    }

    /**
     * The declared parameter type of each handler, so the reflective lookup is exact.
     *
     * @param handlerName the handler being looked up
     * @return the exception type the handler declares
     */
    private static Class<? extends Throwable> handlerParameter(final String handlerName) {
        return BIND_HANDLER.equals(handlerName)
                ? MethodArgumentNotValidException.class
                : HttpMessageNotReadableException.class;
    }

    /**
     * Renders a body the way a client would see it: title, detail, type, status and every property value.
     *
     * @param body the problem detail, never {@code null}
     * @return one string containing everything the body discloses, never {@code null}
     */
    private static String renderedBody(final ProblemDetail body) {
        final StringBuilder rendered = new StringBuilder(256);
        rendered.append(body.getTitle()).append('\n').append(body.getDetail()).append('\n')
                .append(body.getType()).append('\n').append(body.getStatus());
        final Map<String, Object> properties = body.getProperties();
        if (properties != null) {
            properties.forEach((name, value) ->
                    rendered.append('\n').append(name).append('=').append(value));
        }
        return rendered.toString();
    }

    /**
     * Reads one repository file as UTF-8 text.
     *
     * @param relativePath the path relative to the repository root
     * @return the file's whole content, never {@code null}
     */
    private static String read(final Path relativePath) {
        try {
            return Files.readString(relativePath, StandardCharsets.UTF_8);
        } catch (final IOException cause) {
            throw new UncheckedIOException("Cannot read " + relativePath, cause);
        }
    }

    /**
     * Every {@code .java} source in the main tree.
     *
     * @return the sources, in a stable order and never empty
     */
    private static List<Path> mainSources() {
        final Path base = ROOT.resolve("src/main/java/com/cardemo");
        try (Stream<Path> walk = Files.walk(base)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        } catch (final IOException cause) {
            throw new UncheckedIOException("Cannot walk " + base, cause);
        }
    }

    @Nested
    @DisplayName("1. Both conditions are claimed on every controller that binds a body, and nowhere else")
    class TheHandlersExistWhereTheyCanBeReached {

        @Test
        @DisplayName("each of the seven body-bearing controllers declares both handlers, annotated")
        void everyBodyBearingControllerDeclaresBoth() {
            for (final Object controller : bodyBearingControllersAndTitles().keySet()) {
                final Class<?> type = controller.getClass();
                for (final String handlerName : List.of(BIND_HANDLER, UNREADABLE_HANDLER)) {
                    final Method handler;
                    try {
                        handler = type.getDeclaredMethod(handlerName, handlerParameter(handlerName));
                    } catch (final NoSuchMethodException absent) {
                        throw new AssertionError(type.getSimpleName() + " does not declare " + handlerName
                                + ", so this condition still escapes to the framework's default body",
                                absent);
                    }
                    assertThat(handler.isAnnotationPresent(ExceptionHandler.class))
                            .as("%s.%s must carry @ExceptionHandler, or Spring never routes to it and the "
                                    + "method is unreachable code", type.getSimpleName(), handlerName)
                            .isTrue();
                    assertThat(handler.getAnnotation(ExceptionHandler.class).value())
                            .as("%s.%s must name the exception type it claims", type.getSimpleName(),
                                    handlerName)
                            .containsExactly(handlerParameter(handlerName));
                }
            }
        }

        @Test
        @DisplayName("MenuController declares neither, because it binds no body and both are unreachable")
        void theMenuControllerDeclaresNeither() {
            final List<String> declared = List.of(MenuController.class.getDeclaredMethods()).stream()
                    .map(Method::getName)
                    .filter(name -> BIND_HANDLER.equals(name) || UNREADABLE_HANDLER.equals(name))
                    .toList();

            assertThat(declared)
                    .as("MenuController has no @RequestBody parameter anywhere, so neither framework "
                            + "condition can arise on it. A handler there would be dead code, which is why "
                            + "the fix stopped at seven controllers rather than eight")
                    .isEmpty();
            assertThat(read(ROOT.resolve("src/main/java/com/cardemo/controller/MenuController.java")))
                    .as("the premise of the exclusion must hold: if MenuController ever binds a body it "
                            + "needs both handlers, and this assertion is what notices")
                    .doesNotContain("@RequestBody");
        }
    }

    @Nested
    @DisplayName("2. Every answer is 400 with that controller's own fixed envelope")
    class TheEnvelopeIsTheControllersOwn {

        @Test
        @DisplayName("a bean-validation refusal answers 400 with the controller's title and the fixed detail")
        void theBindRefusalCarriesTheEnvelope() {
            bodyBearingControllersAndTitles().forEach((controller, title) -> {
                final ResponseEntity<ProblemDetail> answer =
                        invoke(controller, BIND_HANDLER, aBindFailure());
                final String label = controller.getClass().getSimpleName();

                assertThat(answer.getStatusCode()).as("%s bind refusal status", label)
                        .isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(answer.getBody()).as("%s bind refusal body", label).isNotNull();
                assertThat(answer.getBody().getStatus()).as("%s bind refusal body status", label)
                        .isEqualTo(HttpStatus.BAD_REQUEST.value());
                assertThat(answer.getBody().getTitle())
                        .as("%s must publish its own resource title, which is exactly why the fix is "
                                + "per-controller and not a single advice class", label)
                        .isEqualTo(title);
                assertThat(answer.getBody().getDetail()).as("%s bind refusal detail", label)
                        .isEqualTo(EXPECTED_BIND_DETAIL);
                assertThat(answer.getBody().getProperties())
                        .as("%s bind refusal envelope", label)
                        .containsEntry("errorCode", EXPECTED_BIND_CODE)
                        .containsEntry("correlationId", CORRELATION_ID)
                        .containsOnlyKeys(PERMITTED_PROPERTIES.toArray(String[]::new));
            });
        }

        @Test
        @DisplayName("an unreadable body answers 400 with the same title and its own distinct code")
        void theUnreadableBodyCarriesTheEnvelope() {
            bodyBearingControllersAndTitles().forEach((controller, title) -> {
                final ResponseEntity<ProblemDetail> answer =
                        invoke(controller, UNREADABLE_HANDLER, anUnreadableBody());
                final String label = controller.getClass().getSimpleName();

                assertThat(answer.getStatusCode()).as("%s unreadable-body status", label)
                        .isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(answer.getBody()).as("%s unreadable-body body", label).isNotNull();
                assertThat(answer.getBody().getTitle()).as("%s unreadable-body title", label)
                        .isEqualTo(title);
                assertThat(answer.getBody().getDetail()).as("%s unreadable-body detail", label)
                        .isEqualTo(EXPECTED_UNREADABLE_DETAIL);
                assertThat(answer.getBody().getProperties())
                        .as("%s unreadable-body envelope. The code differs from the bind refusal's because "
                                + "a body that never parsed is not a body that parsed and failed a rule",
                                label)
                        .containsEntry("errorCode", EXPECTED_UNREADABLE_CODE)
                        .containsEntry("correlationId", CORRELATION_ID)
                        .containsOnlyKeys(PERMITTED_PROPERTIES.toArray(String[]::new));
            });
        }

        @Test
        @DisplayName("the framework refusal and the service refusal publish the same title and code")
        void theTwoLayersAgreeOnTheEnvelope() {
            // The finding was that one logical outcome - a rejected input - looked like two unrelated
            // failures depending on which layer noticed it. This is that property, asserted directly: the
            // framework-level answer and the service-level answer carry the same title and the same code, and
            // differ only where they must, in the detail each can honestly state.
            final AccountController account =
                    new AccountController(mock(AccountViewService.class), mock(AccountUpdateService.class));

            final ResponseEntity<ProblemDetail> fromFramework =
                    account.handleBodyBindFailure(aBindFailure());
            final ResponseEntity<ProblemDetail> fromService = account.handleValidationFailure(
                    new ValidationException("Credit Limit must be supplied", "creditLimit",
                            ValidationException.FailureKind.BLANK));

            assertThat(fromFramework.getStatusCode()).isEqualTo(fromService.getStatusCode());
            assertThat(fromFramework.getBody().getTitle()).isEqualTo(fromService.getBody().getTitle());
            assertThat(fromFramework.getBody().getProperties().get("errorCode"))
                    .isEqualTo(fromService.getBody().getProperties().get("errorCode"));
            assertThat(fromFramework.getBody().getProperties().get("correlationId"))
                    .isEqualTo(fromService.getBody().getProperties().get("correlationId"));
        }

        @Test
        @DisplayName("with no correlation identifier in context the property is present and states so")
        void anAbsentCorrelationIdentifierIsStated() {
            MDC.remove(CorrelationIdFilter.MDC_KEY_CORRELATION_ID);

            bodyBearingControllersAndTitles().forEach((controller, title) -> {
                assertThat(invoke(controller, BIND_HANDLER, aBindFailure())
                        .getBody().getProperties().get("correlationId"))
                        .as("%s bind refusal", controller.getClass().getSimpleName())
                        .isEqualTo("unavailable");
                assertThat(invoke(controller, UNREADABLE_HANDLER, anUnreadableBody())
                        .getBody().getProperties().get("correlationId"))
                        .as("%s unreadable body", controller.getClass().getSimpleName())
                        .isEqualTo("unavailable");
            });
        }
    }

    @Nested
    @DisplayName("3. Neither answer discloses the submitted value, the field, the message or the parser")
    class NothingIsDisclosed {

        @Test
        @DisplayName("no bind refusal publishes the rejected value, the field name or the constraint message")
        void theBindRefusalDisclosesNothing() {
            bodyBearingControllersAndTitles().forEach((controller, title) -> {
                final String rendered =
                        renderedBody(invoke(controller, BIND_HANDLER, aBindFailure()).getBody());
                for (final String withheld : WITHHELD_VALUES) {
                    assertThat(rendered)
                            .as("%s bind refusal must not disclose <%s>. Spring's FieldError retains the "
                                    + "submitted value, which on the add-user body is a plaintext password "
                                    + "and on the account update body a social security number",
                                    controller.getClass().getSimpleName(), withheld)
                            .doesNotContain(withheld);
                }
            });
        }

        @Test
        @DisplayName("no unreadable-body answer publishes the parser diagnostic or the exception class")
        void theUnreadableBodyDisclosesNothing() {
            bodyBearingControllersAndTitles().forEach((controller, title) -> {
                final String rendered =
                        renderedBody(invoke(controller, UNREADABLE_HANDLER, anUnreadableBody()).getBody());
                for (final String withheld : WITHHELD_VALUES) {
                    assertThat(rendered)
                            .as("%s unreadable-body answer must not disclose <%s>. The parser message names "
                                    + "the deserialiser's stream class, the byte offset and, for an unknown "
                                    + "property, every property the type accepts",
                                    controller.getClass().getSimpleName(), withheld)
                            .doesNotContain(withheld);
                }
            });
        }

        @Test
        @DisplayName("neither answer carries a field or a failureKind member")
        void noFieldOrDiscriminatorIsPublished() {
            // The service-tier refusal publishes both, because it knows the field from the source's own
            // validation and the discriminator from the CSSETATY two-state marker. The framework supplies
            // neither: a violation set has no stable order, so any field it named would vary between two
            // identical requests, and there is no counterpart to the marker at all. Both are omitted rather
            // than guessed.
            bodyBearingControllersAndTitles().forEach((controller, title) -> {
                assertThat(invoke(controller, BIND_HANDLER, aBindFailure()).getBody().getProperties())
                        .as("%s bind refusal", controller.getClass().getSimpleName())
                        .doesNotContainKeys("field", "failureKind", "rejectedValue", "errors",
                                "bindingErrors", "outcome", "changeAction");
                assertThat(invoke(controller, UNREADABLE_HANDLER, anUnreadableBody())
                        .getBody().getProperties())
                        .as("%s unreadable body", controller.getClass().getSimpleName())
                        .doesNotContainKeys("field", "failureKind", "rejectedValue", "errors",
                                "bindingErrors", "outcome", "changeAction");
            });
        }

        @Test
        @DisplayName("two different bodies produce byte-identical answers on the same controller")
        void theAnswerDoesNotVaryWithTheOffendingInput() {
            // Determinism, and the reason the field name is withheld rather than published: a caller cannot
            // learn anything about the schema by probing, because two violations on two different fields with
            // two different values produce the same answer.
            final AuthController auth = new AuthController(mock(AuthenticationService.class));

            final BindingResult first = new BeanPropertyBindingResult(new Object(), "requestBody");
            first.addError(new FieldError("requestBody", "userId", "ZZFIRSTVALUE", false, null, null,
                    "ZZFIRSTMESSAGE"));
            final BindingResult second = new BeanPropertyBindingResult(new Object(), "requestBody");
            second.addError(new FieldError("requestBody", "password", "ZZSECONDVALUE", false, null, null,
                    "ZZSECONDMESSAGE"));

            final Method bound;
            try {
                bound = BodyFailureEnvelopeContractTest.class
                        .getDeclaredMethod("boundRequestBody", String.class);
            } catch (final NoSuchMethodException absent) {
                throw new AssertionError("the stand-in bound method must exist", absent);
            }
            final MethodParameter parameter = new MethodParameter(bound, 0);

            final String fromFirst = renderedBody(auth.handleBodyBindFailure(
                    new MethodArgumentNotValidException(parameter, first)).getBody());
            final String fromSecond = renderedBody(auth.handleBodyBindFailure(
                    new MethodArgumentNotValidException(parameter, second)).getBody());

            assertThat(fromFirst).isEqualTo(fromSecond);
        }
    }

    @Nested
    @DisplayName("4. All three read conditions really do become the one exception the handler claims")
    class TheReadFailureTaxonomyHolds {

        /**
         * Reads a body the way the framework does, with unknown-property rejection enabled as the application
         * enables it.
         *
         * @param json        the body bytes as text
         * @param contentType the declared media type
         * @return the thrown failure, so the caller can assert its type
         */
        private Throwable readFailureOf(final String json, final String contentType) {
            final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/users");
            request.setContentType(contentType);
            request.setContent(json.getBytes(StandardCharsets.UTF_8));

            final MappingJackson2HttpMessageConverter converter =
                    new MappingJackson2HttpMessageConverter();
            converter.getObjectMapper()
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

            try {
                converter.read(UserCreateRequest.class, new ServletServerHttpRequest(request));
                return null;
            } catch (final IOException | RuntimeException thrown) {
                return thrown;
            }
        }

        @Test
        @DisplayName("a body that is not well-formed JSON becomes an unreadable-body failure")
        void malformedJsonBecomesTheClaimedException() {
            assertThat(readFailureOf("{\"userId\":\"USER0001\"", "application/json"))
                    .as("the first of the three conditions the finding names")
                    .isInstanceOf(HttpMessageNotReadableException.class);
        }

        @Test
        @DisplayName("a body carrying a property outside the schema becomes an unreadable-body failure")
        void anUnknownPropertyBecomesTheClaimedException() {
            // Two mechanisms could reject it and both land on the same type, which is the point: the
            // any-setter of UserCreateRequest refuses the property itself, and FAIL_ON_UNKNOWN_PROPERTIES
            // would refuse it even without one. Either way the framework hands the controller the exception
            // its unreadable-body handler claims, rather than something no handler covers.
            assertThat(readFailureOf("{\"userId\":\"USER0001\",\"zzUnknown\":\"x\"}", "application/json"))
                    .as("the second of the three conditions the finding names")
                    .isInstanceOf(HttpMessageNotReadableException.class);
        }

        @Test
        @DisplayName("an empty body becomes an unreadable-body failure rather than a null bind")
        void anEmptyBodyBecomesTheClaimedException() {
            assertThat(readFailureOf("", "application/json"))
                    .as("the third of the three conditions the finding names. A body that is absent must not "
                            + "bind to null and reach the service, and must not answer 500")
                    .isInstanceOf(HttpMessageNotReadableException.class);
        }

        @Test
        @DisplayName("no read failure carries the submitted body onward into the answer")
        void theParserMessageIsNotTheAnswer() {
            // The parser message names the deserialiser's stream class and offset, and for an unknown property
            // it lists every property the type accepts. This asserts the premise the handler's suppression
            // rests on: the framework really does put that text on the exception.
            final Throwable failure =
                    readFailureOf("{\"userId\":\"USER0001\",\"zzUnknown\":\"x\"}", "application/json");

            assertThat(failure).isNotNull();
            assertThat(failure.getMessage())
                    .as("if this ever stops being true the suppression is merely harmless rather than "
                            + "necessary, and this assertion is what notices")
                    .isNotBlank();

            final ResponseEntity<ProblemDetail> answer = new AdminController(mock(UserListService.class),
                    mock(UserAddService.class), mock(UserUpdateService.class), mock(UserDeleteService.class))
                    .handleUnreadableBody((HttpMessageNotReadableException) failure);

            assertThat(answer.getBody().getDetail()).isEqualTo(EXPECTED_UNREADABLE_DETAIL);
            assertThat(renderedBody(answer.getBody()))
                    .doesNotContain("zzUnknown")
                    .doesNotContain("UserCreateRequest")
                    .doesNotContain("Source:");
        }
    }

    @Nested
    @DisplayName("5. The fix is per-controller, as the checkpoint requires")
    class NoGlobalAdviceWasIntroduced {

        @Test
        @DisplayName("no main source declares advice or extends the framework's handler base")
        void thereIsNoAdviceAnywhere() {
            final List<String> offenders = new ArrayList<>();
            for (final Path source : mainSources()) {
                final String text = read(source);
                for (final String line : text.split("\n", -1)) {
                    final String trimmed = line.strip();
                    if (trimmed.startsWith("*") || trimmed.startsWith("//")) {
                        continue;
                    }
                    if (trimmed.contains("@ControllerAdvice") || trimmed.contains("@RestControllerAdvice")
                            || trimmed.contains("extends ResponseEntityExceptionHandler")) {
                        offenders.add(ROOT.relativize(source) + ": " + trimmed);
                    }
                }
            }

            assertThat(offenders)
                    .as("this checkpoint prohibits global advice, so the fourteen handlers had to be "
                            + "declared on the seven controllers themselves. A prose mention inside a "
                            + "comment is allowed and is skipped; a declaration is not")
                    .isEmpty();
            assertThat(mainSources()).as("the scan must not pass vacuously").hasSizeGreaterThan(100);
        }

        @Test
        @DisplayName("Spring's own resolver routes both framework failures to the controller's handlers")
        void theFrameworkReallyRoutesToTheseHandlers() throws Exception {
            // The one property a direct method call cannot prove, and the one the finding turns on: that
            // Spring consults a controller-local @ExceptionHandler for an exception the ARGUMENT RESOLVER
            // raised, before the mapped method was ever entered. Declaring the handlers would be worthless if
            // it did not. This uses the real ExceptionHandlerExceptionResolver - the component
            // DispatcherServlet delegates to - rather than MockMvc or a Spring context, so the routing is
            // measured against the framework itself while the tier's no-context convention holds.
            final ExceptionHandlerExceptionResolver resolver = new ExceptionHandlerExceptionResolver();
            // A bare resolver ships byte-array, string, source and form converters and no JSON converter at
            // all, so the body it would try to write has no representation and the resolver reports failure
            // rather than the handler's answer. Boot supplies the JSON converter in the application; supplying
            // it here is what makes the written bytes comparable to the real response.
            final List<HttpMessageConverter<?>> converters = new ArrayList<>();
            converters.add(new MappingJackson2HttpMessageConverter());
            resolver.setMessageConverters(converters);
            resolver.afterPropertiesSet();

            final AuthController auth = new AuthController(mock(AuthenticationService.class));
            final Method signOn;
            try {
                signOn = AuthController.class.getDeclaredMethod("signOn", SignOnRequest.class);
            } catch (final NoSuchMethodException absent) {
                throw new AssertionError("AuthController.signOn(SignOnRequest) must exist", absent);
            }
            final HandlerMethod mapped = new HandlerMethod(auth, signOn);

            for (final Exception raised : List.of(aBindFailure(), anUnreadableBody())) {
                final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/signon");
                request.addHeader("Accept", "application/problem+json");
                final MockHttpServletResponse response = new MockHttpServletResponse();

                assertThat(resolver.resolveException(request, response, mapped, raised))
                        .as("the resolver must claim %s rather than leaving it to the framework's default "
                                + "error handling, which answers without the envelope",
                                raised.getClass().getSimpleName())
                        .isNotNull();
                assertThat(response.getStatus())
                        .as("%s must be answered 400", raised.getClass().getSimpleName())
                        .isEqualTo(HttpStatus.BAD_REQUEST.value());

                final String written = response.getContentAsString();
                assertThat(written)
                        .as("the body the resolver wrote for %s must be this package's envelope",
                                raised.getClass().getSimpleName())
                        .contains("\"title\":\"Sign-on rejected\"")
                        .contains("\"status\":400")
                        .contains("\"correlationId\":\"" + CORRELATION_ID + '"')
                        .contains("\"errorCode\":\"CARDDEMO-");
                for (final String withheld : WITHHELD_VALUES) {
                    assertThat(written)
                            .as("the written body for %s must not disclose <%s>",
                                    raised.getClass().getSimpleName(), withheld)
                            .doesNotContain(withheld);
                }
            }
        }

        @Test
        @DisplayName("the controller package still holds exactly the eight controllers and its documentation")
        void noNinthClassAppearedInTheControllerPackage() {
            final Path directory = ROOT.resolve("src/main/java/com/cardemo/controller");
            try (Stream<Path> listing = Files.list(directory)) {
                assertThat(listing.map(path -> path.getFileName().toString()).sorted().toList())
                        .as("a mapper class, a DTO assembler or an advice type added here would break the "
                                + "package's declared shape, which is why the handlers are methods on the "
                                + "controllers rather than a new type")
                        .containsExactly("AccountController.java", "AdminController.java",
                                "AuthController.java", "BillingController.java", "CardController.java",
                                "MenuController.java", "ReportController.java",
                                "TransactionController.java", "package-info.java");
            } catch (final IOException cause) {
                throw new UncheckedIOException("Cannot list " + directory, cause);
            }
        }
    }
}
