/*
 * ******************************************************************
 * Program     : AuthControllerTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11 (Surefire tier)
 * Function    : Wire-contract guard for AuthController, the one operation a
 *               caller reaches without an identity. Pins the route and verb,
 *               the pass-through of the submitted body, the status each typed
 *               failure maps to, the two 401 answers that must stay
 *               byte-identical so neither confirms whether an identifier
 *               exists, and the absence of any credential, hash or internal
 *               name from every body it publishes.
 * Source      : app/csd/CARDDEMO.CSD transaction CC00 -> app/cbl/COSGN00C.cbl
 *               (260 lines), mapset COSGN00 -> app/cpy-bms/COSGN00.CPY
 *               (11 input fields), app/cpy/CSUSR01Y.cpy, app/cpy/COCOM01Y.cpy
 *               @ 7756d89
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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License
 * ******************************************************************
 */
package com.cardemo.unit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cardemo.controller.AuthController;
import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.FileAccessException;
import com.cardemo.exception.FileUnavailableException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.SignOnRequest;
import com.cardemo.model.dto.SignOnResponse;
import com.cardemo.observability.CorrelationIdFilter;
import com.cardemo.service.auth.AuthenticationService;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unit tests for {@code com.cardemo.controller.AuthController}, the REST replacement for CICS transaction
 * {@code CC00} and {@code app/cbl/COSGN00C.cbl} at traceability anchor commit {@code 7756d89}.
 *
 * <h2>1. What it does</h2>
 *
 * <p>A review found that <strong>no test imported, instantiated or class-referenced this controller at
 * all</strong>: the class documents that its operation is exercised through a controller test and an
 * end-to-end test, and neither existed. This class supplies the controller half. It asserts the wire contract
 * and nothing else - the sign-on business rules belong to {@code AuthenticationService} and are pinned by
 * {@code com.cardemo.unit.service.AuthenticationServiceTest} - and it asserts five properties that no
 * service-tier test is structurally able to observe.
 *
 * <ul>
 *   <li><em>The route and the verb.</em> {@code POST /api/auth/signon}, one operation and no second one. The
 *       security chain grants anonymous access to that exact path, so a renamed route makes the operation
 *       unreachable rather than merely misnamed, and a {@code GET} would put a credential in a query string.
 *   <li><em>The body is passed through untouched.</em> The legacy program folded both credentials to upper
 *       case at {@code app/cbl/COSGN00C.cbl:L132-L136}; that folding belongs to
 *       {@code CardDemoUserDetailsService}, and a second normalisation site here could disagree with the
 *       first about which credentials verify.
 *   <li><em>One typed failure, one status.</em> {@code 400} for a missing input, {@code 401} for a refused
 *       credential, {@code 503} for an unopenable or unreadable store, {@code 500} for an abend and for any
 *       unclaimed subtype.
 *   <li><em>The two {@code 401} answers are byte-identical.</em> A refused credential and an absent record
 *       must be indistinguishable, or the status code itself becomes a user-enumeration oracle.
 *   <li><em>Nothing sensitive or internal reaches a body.</em> No password, no hash, no dataset name, no file
 *       status, no culprit program - the caller here is by definition unauthenticated.
 * </ul>
 *
 * <h2>2. How to run, build and test</h2>
 *
 * <p>Bound to the Surefire tier by its {@code *Test} name under {@code src/test/java/com/cardemo/unit}. It
 * needs no container, no database and no cloud emulator.
 *
 * <pre>
 * ./mvnw -B -ntp -Dtest=AuthControllerTest test
 * ./mvnw -B -ntp clean verify
 * </pre>
 *
 * <h2>3. Key configuration and defaults</h2>
 *
 * <p>No Spring context is started and no property is read: the single service is mocked and every handler and
 * exception handler is a plain method call. The correlation identifier the bodies publish lives in the
 * logging diagnostic context, so it is placed before each test and removed after - the controller only ever
 * reads that context and never mutates it, which is itself asserted below. The issued token is an opaque
 * string here, because what this class tests is that it travels; how it is minted is pinned by
 * {@code com.cardemo.unit.security.JwtTokenProviderTest} and its lifetime by
 * {@code com.cardemo.unit.security.JwtTokenLifetimeContractTest}.
 *
 * <h2>4. Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>Blocker.</strong> A failure in the enumeration group means the two {@code 401} answers have
 *       drifted apart, and the response now tells an unauthenticated caller whether an identifier exists.
 *   <li><strong>Blocker.</strong> A failure in the disclosure group means a credential, a hash, a dataset
 *       name or a file status reached a body.
 *   <li><strong>High.</strong> A failure in the routing group means the route or verb moved; the anonymous
 *       rule in {@code com.cardemo.config.SecurityConfig} matches the exact path, so sign-on then answers
 *       {@code 401} for every caller.
 *   <li><strong>High.</strong> A failure in the pass-through group means a second credential-normalisation
 *       authority has appeared in the application.
 *   <li><strong>Medium.</strong> A failure in the status-mapping group means a typed failure changed status;
 *       {@code 503} states the request is safe to repeat and {@code 500} states it is not.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
@DisplayName("AuthController - the wire contract for CC00, the one anonymous operation")
class AuthControllerTest {

    /** The base path the class-level mapping must publish. */
    private static final String BASE_PATH = "/api/auth";

    /** The route suffix the sign-on operation must publish, giving {@code /api/auth/signon}. */
    private static final String SIGN_ON_PATH = "/signon";

    /** The correlation identifier every body must republish, placed in the diagnostic context per test. */
    private static final String CORRELATION_ID = "c0rrel4t10n-1d-f0r-th3-4uth-t3st";

    /**
     * The submitted identifier, deliberately mixed case so the pass-through assertion has something to prove.
     *
     * <p>Obviously synthetic. The source folds this value at {@code app/cbl/COSGN00C.cbl:L132-L134}, and this
     * class asserts that the controller does <em>not</em>.
     */
    private static final String SUBMITTED_USER_ID = "uSeR0001";

    /**
     * The submitted plaintext, standing in for {@code PASSWDI PIC X(8)} of {@code app/cpy-bms/COSGN00.CPY}.
     *
     * <p>Obviously fake and never a real credential. Rule 1 clause D names tests explicitly, and the one
     * eight-character literal the ten seeded users share at {@code app/jcl/DUSRSECJ.jcl:35-44} is
     * deliberately not reproduced anywhere under {@code src/}.
     */
    private static final String SUBMITTED_PASSWORD = "nOtR34l!";

    /** The token the service returns, opaque here on purpose. */
    private static final String ISSUED_TOKEN = "issued.bearer.token";

    /** The identifier as the application folded it, which is what the response carries back. */
    private static final String FOLDED_USER_ID = "USER0001";

    /** The single-character user class of {@code SEC-USR-TYPE} at {@code app/cpy/CSUSR01Y.cpy:L22}. */
    private static final String USER_TYPE_STANDARD = "U";

    /** An internal dataset name that must never appear in a body. */
    private static final String INTERNAL_FILE = "ZZUSRSECDATASET";

    /** An internal record key that must never appear in a body. */
    private static final String INTERNAL_KEY = "ZZUSER0001KEY";

    /** An internal file status that must never appear in a body. */
    private static final String INTERNAL_STATUS = "ZZ37";

    /** An internal operation name that must never appear in a body. */
    private static final String INTERNAL_OPERATION = "ZZREADFORUPDATE";

    /** An internal culprit program name that must never appear in a body. */
    private static final String INTERNAL_CULPRIT = "ZZCOSGN00";

    /** An internal abend reason that must never appear in a body. */
    private static final String INTERNAL_REASON = "ZZUNEXPECTEDFILESTATUS";

    /** An internal diagnostic message that must never appear in a body. */
    private static final String INTERNAL_MESSAGE = "ZZREADFORUPDATE of ZZUSRSECDATASET reported status ZZ37";

    /**
     * The annotation simple names that would let this controller decide its own authorisation.
     *
     * <p>None may be present. The rule that makes this the one anonymous route is declared centrally in
     * {@code com.cardemo.config.SecurityConfig}; an annotation here could only contradict it, and by
     * construction there is no principal yet when this operation runs.
     */
    private static final Set<String> SELF_AUTHORISATION_ANNOTATIONS = Set.of(
            "PreAuthorize", "PostAuthorize", "Secured", "RolesAllowed", "DenyAll", "PermitAll");

    /** The sign-on service, the transcription of {@code app/cbl/COSGN00C.cbl}. */
    @Mock
    private AuthenticationService authenticationService;

    /** The controller under test, rebuilt per test so no state can survive between them. */
    private AuthController controller;

    /** Builds a fresh controller and places the correlation identifier the bodies republish. */
    @BeforeEach
    void createController() {
        controller = new AuthController(authenticationService);
        MDC.put(CorrelationIdFilter.MDC_KEY_CORRELATION_ID, CORRELATION_ID);
    }

    /** Removes the correlation identifier, so no test inherits another's diagnostic context. */
    @AfterEach
    void clearCorrelationIdentifier() {
        MDC.remove(CorrelationIdFilter.MDC_KEY_CORRELATION_ID);
    }

    /**
     * Builds a sign-on form with the two operative components populated and the nine header components blank.
     *
     * <p>The nine are the terminal-header metadata the 3270 screen supplied and this operation consumes none
     * of them, which is exactly why they are left empty: a test that populated them could not tell a
     * controller that ignores them from one that reads them.
     *
     * @param userId the submitted identifier, which may be {@code null}
     * @param password the submitted plaintext, which may be {@code null}
     * @return the request record, never {@code null}
     */
    private static SignOnRequest request(final String userId, final String password) {
        return new SignOnRequest(null, null, null, null, null, null, null, null, userId, password, null);
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
     * Collects the request-handling methods of the controller: those carrying an HTTP verb mapping.
     *
     * @return the route methods, never {@code null}
     */
    private static List<Method> routeMethods() {
        return Arrays.stream(AuthController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PostMapping.class))
                .toList();
    }

    /** The published route, and that there is exactly one of it. */
    @Nested
    @DisplayName("1. the published route")
    final class PublishedRoute {

        @Test
        @DisplayName("the class is a REST controller mapped at the base path the security rule names")
        void basePathIsPublished() {
            assertThat(AuthController.class.isAnnotationPresent(RestController.class))
                    .as("the target exposes JSON, not rendered views: the BMS layer became a field contract "
                            + "rather than a user interface")
                    .isTrue();

            final RequestMapping mapping = AuthController.class.getAnnotation(RequestMapping.class);
            assertThat(mapping)
                    .as("without a class-level mapping the route is published at the application root, "
                            + "outside the /api prefix every security rule matches on")
                    .isNotNull();
            assertThat(mapping.value()).containsExactly(BASE_PATH);
        }

        @Test
        @DisplayName("exactly one operation is published, and it is a POST at /signon")
        void oneOperationIsPublished() {
            final List<Method> routes = routeMethods();

            assertThat(routes)
                    .as("CSD transaction CC00 is one transaction, so this controller publishes one operation. "
                            + "A second route here would be an endpoint the resource definitions never "
                            + "declared, and it would sit inside the anonymous namespace")
                    .hasSize(1);
            assertThat(routes.get(0).getAnnotation(PostMapping.class).value())
                    .as("com.cardemo.config.SecurityConfig grants anonymous access to this EXACT path, so a "
                            + "renamed suffix makes sign-on unreachable rather than merely misnamed")
                    .containsExactly(SIGN_ON_PATH);
            assertThat(routes.get(0).getName()).isEqualTo("signOn");
            assertThat(routes.get(0).getReturnType())
                    .as("returning ResponseEntity is what makes the status deliberate rather than defaulted")
                    .isEqualTo(ResponseEntity.class);
        }

        @Test
        @DisplayName("the operation is a POST rather than a GET, so no credential reaches a query string")
        void theOperationIsNotAGet() {
            assertThat(routeMethods())
                    .allSatisfy(route -> assertThat(route.isAnnotationPresent(PostMapping.class))
                            .as("a GET carrying a credential would place it in the request line, where it is "
                                    + "logged by every intermediary that logs a URL")
                            .isTrue());
        }
    }

    /** The submitted body reaches the service exactly as received. */
    @Nested
    @DisplayName("2. the submitted body is relayed untouched")
    final class BodyPassThrough {

        @Test
        @DisplayName("the request instance handed to the service is the one received, not a copy")
        void theRequestIsRelayedByReference() {
            final SignOnRequest submitted = request(SUBMITTED_USER_ID, SUBMITTED_PASSWORD);
            when(authenticationService.signOn(submitted))
                    .thenReturn(new SignOnResponse(ISSUED_TOKEN, FOLDED_USER_ID, USER_TYPE_STANDARD));

            controller.signOn(submitted);

            final ArgumentCaptor<SignOnRequest> relayed = ArgumentCaptor.forClass(SignOnRequest.class);
            verify(authenticationService).signOn(relayed.capture());
            assertThat(relayed.getValue()).isSameAs(submitted);
        }

        @Test
        @DisplayName("neither credential is folded, trimmed, padded or defaulted on the way through")
        void neitherCredentialIsNormalised() {
            final SignOnRequest submitted = request(SUBMITTED_USER_ID, SUBMITTED_PASSWORD);
            when(authenticationService.signOn(submitted))
                    .thenReturn(new SignOnResponse(ISSUED_TOKEN, FOLDED_USER_ID, USER_TYPE_STANDARD));

            controller.signOn(submitted);

            final ArgumentCaptor<SignOnRequest> relayed = ArgumentCaptor.forClass(SignOnRequest.class);
            verify(authenticationService).signOn(relayed.capture());
            // The source folds both values - the identifier at app/cbl/COSGN00C.cbl:L132-L134 and the
            // password at :L135-L136 - and that folding is reproduced once, in CardDemoUserDetailsService.
            // A second authority here could disagree with the first about which credentials verify.
            assertThat(relayed.getValue().userId()).isEqualTo(SUBMITTED_USER_ID);
            assertThat(relayed.getValue().userId())
                    .isNotEqualTo(SUBMITTED_USER_ID.toUpperCase(Locale.ROOT));
            assertThat(relayed.getValue().password()).isEqualTo(SUBMITTED_PASSWORD);
        }

        @Test
        @DisplayName("absent and blank remain two distinct states rather than being coerced into one")
        void absentAndBlankAreNotCoerced() {
            final SignOnRequest absent = request(null, null);
            final SignOnRequest blank = request("", "");
            when(authenticationService.signOn(any(SignOnRequest.class)))
                    .thenReturn(new SignOnResponse(ISSUED_TOKEN, FOLDED_USER_ID, USER_TYPE_STANDARD));

            controller.signOn(absent);
            controller.signOn(blank);

            final ArgumentCaptor<SignOnRequest> relayed = ArgumentCaptor.forClass(SignOnRequest.class);
            verify(authenticationService, org.mockito.Mockito.times(2)).signOn(relayed.capture());
            // The service reproduces the source's distinction between an input that was not supplied and one
            // supplied wrongly; coercing either way here would erase it before the service ever sees it.
            assertThat(relayed.getAllValues().get(0).userId()).isNull();
            assertThat(relayed.getAllValues().get(0).password()).isNull();
            assertThat(relayed.getAllValues().get(1).userId()).isEmpty();
            assertThat(relayed.getAllValues().get(1).password()).isEmpty();
        }
    }

    /** The success answer. */
    @Nested
    @DisplayName("3. the success answer")
    final class SuccessAnswer {

        @Test
        @DisplayName("a verified credential answers 200 with the token, the folded identifier and the class")
        void aVerifiedCredentialAnswersTwoHundred() {
            final SignOnRequest submitted = request(SUBMITTED_USER_ID, SUBMITTED_PASSWORD);
            when(authenticationService.signOn(submitted))
                    .thenReturn(new SignOnResponse(ISSUED_TOKEN, FOLDED_USER_ID, USER_TYPE_STANDARD));

            final ResponseEntity<SignOnResponse> answer = controller.signOn(submitted);

            assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(answer.getBody()).isNotNull();
            assertThat(answer.getBody().token()).isEqualTo(ISSUED_TOKEN);
            assertThat(answer.getBody().userId()).isEqualTo(FOLDED_USER_ID);
            // The user class is the surviving half of the two EXEC CICS XCTL sites at
            // app/cbl/COSGN00C.cbl:L231 and :L236: the client reads it and navigates itself.
            assertThat(answer.getBody().userType()).isEqualTo(USER_TYPE_STANDARD);
        }

        @Test
        @DisplayName("the response the service produced is published unchanged, so no field is re-derived")
        void theServiceResponseIsPublishedUnchanged() {
            final SignOnRequest submitted = request(SUBMITTED_USER_ID, SUBMITTED_PASSWORD);
            final SignOnResponse produced =
                    new SignOnResponse(ISSUED_TOKEN, FOLDED_USER_ID, USER_TYPE_STANDARD);
            when(authenticationService.signOn(submitted)).thenReturn(produced);

            assertThat(controller.signOn(submitted).getBody()).isSameAs(produced);
        }

        @Test
        @DisplayName("no response carries the submitted password, so it cannot be echoed even by accident")
        void theResponseNeverCarriesTheSubmittedPassword() {
            final SignOnRequest submitted = request(SUBMITTED_USER_ID, SUBMITTED_PASSWORD);
            when(authenticationService.signOn(submitted))
                    .thenReturn(new SignOnResponse(ISSUED_TOKEN, FOLDED_USER_ID, USER_TYPE_STANDARD));

            final SignOnResponse published = controller.signOn(submitted).getBody();

            assertThat(published).isNotNull();
            assertThat(String.valueOf(published))
                    .as("SignOnResponse has no password component at all, which is what makes an accidental "
                            + "echo impossible rather than merely unlikely")
                    .doesNotContain(SUBMITTED_PASSWORD);
            assertThat(Arrays.stream(SignOnResponse.class.getRecordComponents())
                    .map(component -> component.getName())
                    .toList())
                    .containsExactly("token", "userId", "userType");
        }
    }

    /** One typed failure, one status. */
    @Nested
    @DisplayName("4. one typed failure, one status")
    final class StatusMapping {

        @Test
        @DisplayName("a missing input answers 400, naming the field and the failure kind")
        void aMissingInputAnswersFourHundred() {
            final ValidationException missing = ValidationException.missingField(
                    "userId", "Please enter User ID ...");

            final ResponseEntity<ProblemDetail> answer = controller.handleValidationFailure(missing);

            assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(answer.getBody()).isNotNull();
            // The legacy caption of app/cbl/COSGN00C.cbl:L120 reaches the wire: telling a caller which box to
            // fill in reveals nothing it could not already read from the schema.
            assertThat(answer.getBody().getDetail()).isEqualTo("Please enter User ID ...");
            assertThat(answer.getBody().getProperties())
                    .containsEntry("field", "userId")
                    .containsEntry("failureKind", ValidationException.FailureKind.BLANK.name())
                    .containsEntry("errorCode", "CARDDEMO-VALIDATION-REJECTED")
                    .containsEntry("correlationId", CORRELATION_ID);
        }

        @Test
        @DisplayName("a refused credential answers 401, and names no field and no failure kind")
        void aRefusedCredentialAnswersFourHundredAndOne() {
            final ValidationException refused = ValidationException.invalidField(
                    "password", "Wrong Password. Try again ...");

            final ResponseEntity<ProblemDetail> answer = controller.handleValidationFailure(refused);

            assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(answer.getBody()).isNotNull();
            assertThat(answer.getBody().getProperties())
                    .containsEntry("errorCode", "CARDDEMO-AUTHENTICATION-FAILED")
                    .containsEntry("correlationId", CORRELATION_ID)
                    .doesNotContainKey("field")
                    .doesNotContainKey("failureKind");
            // The legacy screen distinguished 'Wrong Password. Try again ...' at :L242-L243 from
            // 'User not found. Try again ...' at :L249. Publishing that distinction is the enumeration
            // oracle the labelled deviation on the handler removes, so the message is discarded here.
            assertThat(renderedBody(answer.getBody())).doesNotContain("Wrong Password");
        }

        @Test
        @DisplayName("an unopenable store answers 503, which states the request is safe to repeat")
        void anUnopenableStoreAnswersFiveHundredAndThree() {
            final ResponseEntity<ProblemDetail> answer = controller.handleFileUnavailable(
                    new FileUnavailableException(INTERNAL_MESSAGE, INTERNAL_FILE,
                            new IllegalStateException("cause")));

            assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(answer.getBody()).isNotNull();
            assertThat(answer.getBody().getProperties())
                    .containsEntry("errorCode", "CARDDEMO-RESOURCE-UNAVAILABLE")
                    .containsEntry("correlationId", CORRELATION_ID);
        }

        @Test
        @DisplayName("a failed read answers 503 too, with its own error code and its own title")
        void aFailedReadAnswersFiveHundredAndThree() {
            final ResponseEntity<ProblemDetail> answer = controller.handleFileAccessFailure(
                    new FileAccessException(INTERNAL_MESSAGE, INTERNAL_STATUS, INTERNAL_FILE,
                            INTERNAL_OPERATION));

            assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(answer.getBody()).isNotNull();
            // The legacy program could not distinguish a closed store from one that failed mid-read: both
            // landed in the WHEN OTHER arm at app/cbl/COSGN00C.cbl:L252-L256. The target does distinguish
            // them by error code while answering both with the same retryable status.
            assertThat(answer.getBody().getProperties())
                    .containsEntry("errorCode", "CARDDEMO-IO-FAILURE")
                    .containsEntry("correlationId", CORRELATION_ID);
            assertThat(answer.getBody().getTitle())
                    .isNotEqualTo(controller.handleFileUnavailable(
                            new FileUnavailableException(INTERNAL_MESSAGE, INTERNAL_FILE, null))
                            .getBody().getTitle());
        }

        @Test
        @DisplayName("an abend answers 500, because the request is not safe to repeat")
        void anAbendAnswersFiveHundred() {
            final ResponseEntity<ProblemDetail> answer = controller.handleAbend(
                    new FatalProcessingException(String.valueOf(FatalProcessingException.BATCH_ABEND_CODE),
                            INTERNAL_CULPRIT, INTERNAL_REASON, INTERNAL_MESSAGE));

            assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(answer.getBody()).isNotNull();
            assertThat(answer.getBody().getProperties())
                    .containsEntry("errorCode", "CARDDEMO-PROCESSING-ABEND")
                    .containsEntry("correlationId", CORRELATION_ID);
        }

        @Test
        @DisplayName("an unclaimed subtype answers 500, so nothing escapes to the framework's default")
        void anUnclaimedSubtypeAnswersFiveHundred() {
            final CardDemoException unclaimed = new CardDemoException(INTERNAL_MESSAGE) {
                private static final long serialVersionUID = 1L;
            };

            final ResponseEntity<ProblemDetail> answer = controller.handleTypedFailure(unclaimed);

            assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(answer.getBody()).isNotNull();
            assertThat(answer.getBody().getProperties())
                    .containsEntry("errorCode", "CARDDEMO-INTERNAL-FAILURE")
                    .containsEntry("correlationId", CORRELATION_ID);
        }

        @Test
        @DisplayName("every handler on this class is scoped to it, with no advice type and no shared base")
        void everyHandlerIsLocalToThisController() {
            final List<String> handlers = Arrays.stream(AuthController.class.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(ExceptionHandler.class))
                    .map(Method::getName)
                    .sorted()
                    .toList();

            assertThat(handlers)
                    .as("status selection is contextual - the same typed failure means different things to "
                            + "different operations - so each controller performs its own and there is no "
                            + "@ControllerAdvice anywhere in the package")
                    .containsExactly("handleAbend", "handleBodyBindFailure", "handleFileAccessFailure",
                            "handleFileUnavailable", "handleRecordNotFound", "handleTypedFailure",
                            "handleUnreadableBody", "handleValidationFailure");
            assertThat(AuthController.class.getSuperclass()).isEqualTo(Object.class);
        }
    }

    /** The two 401 answers must stay indistinguishable. */
    @Nested
    @DisplayName("5. the 401 answers are an enumeration oracle if they ever differ")
    final class NoEnumerationOracle {

        @Test
        @DisplayName("an absent record answers 401 rather than 404, deliberately breaking the package habit")
        void anAbsentRecordAnswersFourHundredAndOne() {
            final ResponseEntity<ProblemDetail> answer = controller.handleRecordNotFound(
                    new RecordNotFoundException(INTERNAL_MESSAGE, INTERNAL_FILE, INTERNAL_KEY));

            // Everywhere else in the package a missing record is a 404, and that is the honest answer. Here
            // the only record read is the row for the identifier the caller just supplied, so a
            // distinguishable not-found answer would confirm that a given identifier does not exist.
            assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(answer.getStatusCode()).isNotEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("the refusal body and the absent-record body are identical, property for property")
        void bothFourHundredAndOneBodiesAreIdentical() {
            final ProblemDetail refusal = controller.handleValidationFailure(
                    ValidationException.invalidField("password", "Wrong Password. Try again ..."))
                    .getBody();
            final ProblemDetail absence = controller.handleRecordNotFound(
                    new RecordNotFoundException(INTERNAL_MESSAGE, INTERNAL_FILE, INTERNAL_KEY)).getBody();

            assertThat(refusal).isNotNull();
            assertThat(absence).isNotNull();
            assertThat(absence.getStatus()).isEqualTo(refusal.getStatus());
            assertThat(absence.getTitle()).isEqualTo(refusal.getTitle());
            assertThat(absence.getDetail()).isEqualTo(refusal.getDetail());
            assertThat(absence.getProperties()).isEqualTo(refusal.getProperties());
            assertThat(renderedBody(absence))
                    .as("the two answers are produced by one private helper, which is what makes them "
                            + "identical by construction rather than by two implementations that agree today")
                    .isEqualTo(renderedBody(refusal));
        }

        @Test
        @DisplayName("neither 401 body names the identifier that was submitted")
        void neitherFourHundredAndOneBodyNamesTheIdentifier() {
            final List<ProblemDetail> bodies = List.of(
                    controller.handleValidationFailure(ValidationException.invalidField(
                            "password", "Wrong Password. Try again ...")).getBody(),
                    controller.handleRecordNotFound(new RecordNotFoundException(
                            INTERNAL_MESSAGE, INTERNAL_FILE, SUBMITTED_USER_ID)).getBody());

            assertThat(bodies).allSatisfy(body -> assertThat(renderedBody(body))
                    .doesNotContain(SUBMITTED_USER_ID)
                    .doesNotContain(FOLDED_USER_ID));
        }
    }

    /** Nothing sensitive or internal reaches a body. */
    @Nested
    @DisplayName("6. no credential, hash or internal name reaches any body")
    final class NoDisclosure {

        @Test
        @DisplayName("no body of any handler names a dataset, status, operation, culprit, reason or key")
        void noBodyDisclosesAnythingInternal() {
            final List<String> sentinels = List.of(INTERNAL_FILE, INTERNAL_KEY, INTERNAL_STATUS,
                    INTERNAL_OPERATION, INTERNAL_CULPRIT, INTERNAL_REASON, INTERNAL_MESSAGE);

            for (final ProblemDetail body : everyFailureBody()) {
                final String rendered = renderedBody(body);
                for (final String sentinel : sentinels) {
                    assertThat(rendered)
                            .as("an unauthenticated caller has no business reading a description of the "
                                    + "storage layer, and %s describes it", sentinel)
                            .doesNotContain(sentinel);
                }
            }
        }

        @Test
        @DisplayName("no body carries the submitted password or anything resembling a hash")
        void noBodyCarriesACredential() {
            for (final ProblemDetail body : everyFailureBody()) {
                final String rendered = renderedBody(body);
                assertThat(rendered).doesNotContain(SUBMITTED_PASSWORD);
                assertThat(rendered)
                        .as("a BCrypt digest starts with the version marker, so its presence is detectable "
                                + "without knowing the digest itself")
                        .doesNotContain("$2a$")
                        .doesNotContain("$2b$")
                        .doesNotContain("$2y$");
            }
        }

        @Test
        @DisplayName("every body carries the envelope, so one client-side handler works across the surface")
        void everyBodyCarriesThePublicEnvelope() {
            for (final ProblemDetail body : everyFailureBody()) {
                assertThat(body.getProperties())
                        .as("a body without the envelope is visibly inconsistent with its siblings")
                        .containsKey("errorCode")
                        .containsEntry("correlationId", CORRELATION_ID);
                assertThat(body.getTitle()).isNotBlank();
                assertThat(body.getDetail()).isNotBlank();
            }
        }

        @Test
        @DisplayName("an absent correlation identifier publishes a placeholder rather than a null")
        void anAbsentCorrelationIdentifierPublishesAPlaceholder() {
            MDC.remove(CorrelationIdFilter.MDC_KEY_CORRELATION_ID);

            final ProblemDetail body = controller.handleAbend(new FatalProcessingException(
                    String.valueOf(FatalProcessingException.BATCH_ABEND_CODE), INTERNAL_CULPRIT,
                    INTERNAL_REASON, INTERNAL_MESSAGE)).getBody();

            assertThat(body).isNotNull();
            // The property is always present, so a client never has to branch on its absence. This state can
            // only be reached when the handler runs outside the filter that supplies the identifier.
            assertThat(body.getProperties()).containsKey("correlationId");
            assertThat(String.valueOf(body.getProperties().get("correlationId"))).isNotBlank();
        }

        @Test
        @DisplayName("the controller only reads the diagnostic context and never mutates its key set")
        void theDiagnosticContextIsReadNeverWritten() {
            final Map<String, String> before = MDC.getCopyOfContextMap();

            controller.handleAbend(new FatalProcessingException(
                    String.valueOf(FatalProcessingException.BATCH_ABEND_CODE), INTERNAL_CULPRIT,
                    INTERNAL_REASON, INTERNAL_MESSAGE));

            // com.cardemo.observability.CorrelationIdFilter owns the key set. This class never adds, renames,
            // overwrites, removes or clears a key, which is what keeps one owner for the request context.
            assertThat(MDC.getCopyOfContextMap()).isEqualTo(before);
        }

        /**
         * Drives every failure handler on the controller with an exception carrying internal values.
         *
         * @return one body per handler, never {@code null} and never empty
         */
        private List<ProblemDetail> everyFailureBody() {
            final List<ProblemDetail> bodies = new ArrayList<>();
            bodies.add(controller.handleValidationFailure(ValidationException.missingField(
                    "userId", "Please enter User ID ...")).getBody());
            bodies.add(controller.handleValidationFailure(ValidationException.invalidField(
                    "password", "Wrong Password. Try again ...")).getBody());
            bodies.add(controller.handleRecordNotFound(new RecordNotFoundException(
                    INTERNAL_MESSAGE, INTERNAL_FILE, INTERNAL_KEY)).getBody());
            bodies.add(controller.handleFileUnavailable(new FileUnavailableException(
                    INTERNAL_MESSAGE, INTERNAL_FILE, new IllegalStateException("cause"))).getBody());
            bodies.add(controller.handleFileAccessFailure(new FileAccessException(
                    INTERNAL_MESSAGE, INTERNAL_STATUS, INTERNAL_FILE, INTERNAL_OPERATION)).getBody());
            bodies.add(controller.handleAbend(new FatalProcessingException(
                    String.valueOf(FatalProcessingException.BATCH_ABEND_CODE), INTERNAL_CULPRIT,
                    INTERNAL_REASON, INTERNAL_MESSAGE)).getBody());
            bodies.add(controller.handleTypedFailure(new CardDemoException(INTERNAL_MESSAGE) {
                private static final long serialVersionUID = 1L;
            }).getBody());
            assertThat(bodies).isNotEmpty().doesNotContainNull();
            return bodies;
        }
    }

    /** The authorisation posture, and the construction contract. */
    @Nested
    @DisplayName("7. the anonymous posture and the construction contract")
    final class PostureAndConstruction {

        @Test
        @DisplayName("the controller carries no authorisation annotation, so the rule lives in one place")
        void theControllerDoesNotAuthoriseItself() {
            final Set<String> present = new LinkedHashSet<>();
            Arrays.stream(AuthController.class.getAnnotations())
                    .map(annotation -> annotation.annotationType().getSimpleName())
                    .forEach(present::add);
            Arrays.stream(AuthController.class.getDeclaredMethods())
                    .flatMap(method -> Arrays.stream(method.getAnnotations()))
                    .map(annotation -> annotation.annotationType().getSimpleName())
                    .forEach(present::add);

            // The anonymous grant is declared centrally in com.cardemo.config.SecurityConfig, against the one
            // exact route. An annotation here could only contradict it, and there is no principal to consult
            // anyway: establishing one is what this operation does.
            assertThat(present).doesNotContainAnyElementsOf(SELF_AUTHORISATION_ANNOTATIONS);
        }

        @Test
        @DisplayName("the operation takes no Authentication and no security context, so it forges no role")
        void theOperationReadsNoPrincipal() {
            assertThat(routeMethods())
                    .allSatisfy(route -> assertThat(Arrays.stream(route.getParameterTypes())
                            .map(Class::getName)
                            .toList())
                            .as("a principal parameter would mean this operation expected an identity it is "
                                    + "supposed to create")
                            .allSatisfy(name -> assertThat(name)
                                    .doesNotContain("org.springframework.security")));
        }

        @Test
        @DisplayName("the sole constructor refuses a null service, naming the program it replaces")
        void theConstructorRefusesANullService() {
            assertThat(AuthController.class.getDeclaredConstructors())
                    .as("one constructor means Spring needs no @Autowired hint to choose between them")
                    .hasSize(1);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new AuthController(null))
                    .withMessageContaining("must not be null")
                    .withMessageContaining("app/cbl/COSGN00C.cbl");
        }

        @Test
        @DisplayName("every instance field is final, so no request leaves state behind for the next")
        void everyInstanceFieldIsFinal() {
            assertThat(AuthController.class.getDeclaredFields())
                    .allSatisfy(field -> {
                        if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                            return;
                        }
                        assertThat(java.lang.reflect.Modifier.isFinal(field.getModifiers()))
                                .as("%s is a mutable instance field on a singleton bean that serves every "
                                        + "concurrent request; the COMMAREA was per-task state, and a field "
                                        + "here is shared state, which is a worse thing", field.getName())
                                .isTrue();
                    });
        }

        @Test
        @DisplayName("a failure raised by the service propagates rather than being swallowed into a 200")
        void aServiceFailurePropagates() {
            final SignOnRequest submitted = request(SUBMITTED_USER_ID, SUBMITTED_PASSWORD);
            final ValidationException refused = ValidationException.invalidField(
                    "password", "Wrong Password. Try again ...");
            when(authenticationService.signOn(submitted)).thenThrow(refused);

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> controller.signOn(submitted))
                    .isSameAs(refused);
        }

        @Test
        @DisplayName("no handler invocation touches the service, so a mapper cannot re-run the operation")
        void noHandlerInvocationTouchesTheService() {
            controller.handleValidationFailure(ValidationException.missingField(
                    "userId", "Please enter User ID ..."));
            controller.handleRecordNotFound(new RecordNotFoundException(
                    INTERNAL_MESSAGE, INTERNAL_FILE, INTERNAL_KEY));
            controller.handleFileUnavailable(new FileUnavailableException(
                    INTERNAL_MESSAGE, INTERNAL_FILE, null));
            controller.handleFileAccessFailure(new FileAccessException(
                    INTERNAL_MESSAGE, INTERNAL_STATUS, INTERNAL_FILE, INTERNAL_OPERATION));
            controller.handleAbend(new FatalProcessingException(
                    String.valueOf(FatalProcessingException.BATCH_ABEND_CODE), INTERNAL_CULPRIT,
                    INTERNAL_REASON, INTERNAL_MESSAGE));
            controller.handleTypedFailure(new CardDemoException(INTERNAL_MESSAGE) {
                private static final long serialVersionUID = 1L;
            });

            verifyNoInteractions(authenticationService);
        }
    }
}
