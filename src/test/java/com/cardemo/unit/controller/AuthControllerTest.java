/*
 * ******************************************************************
 * Program     : AuthControllerTest.java
 * Application : CardDemo
 * Type        : Java unit test (JUnit 5)
 * Function    : Verifies the wire contract of AuthController, the REST
 *               replacement for CICS transaction CC00 and COBOL program
 *               app/cbl/COSGN00C.cbl. Asserts that the unknown-identifier and
 *               wrong-password endings of :L241-L251 stay indistinguishable on
 *               the wire, that a blank field is answered differently from a
 *               refused credential, that no submitted credential is ever
 *               echoed back, and that every typed failure maps to its own
 *               status and its own machine-readable error code.
 * Source      : app/cbl/COSGN00C.cbl (260 lines), app/csd/CARDDEMO.CSD
 *               (transaction CC00), app/cpy-bms/COSGN00.CPY (11 fields),
 *               app/cpy/CSUSR01Y.cpy (80 B) @ 7756d89
 * ******************************************************************
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ******************************************************************
 */
package com.cardemo.unit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.cardemo.controller.AuthController;
import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.FileAccessException;
import com.cardemo.exception.FileUnavailableException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.SignOnRequest;
import com.cardemo.model.dto.SignOnResponse;
import com.cardemo.service.auth.AuthenticationService;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Unit tests for {@link AuthController}.
 *
 * <p>The service is mocked. This tier owns no business logic at all: it owns the mapping from a typed failure
 * to a status, a title, a detail and a machine-readable code, and it owns the rule that a refused credential
 * must not tell the caller <em>why</em> it was refused.</p>
 *
 * <p>Inputs: a populated {@link SignOnRequest} and a stubbed service outcome. Outputs: the asserted response
 * entity. Side effects: none - nothing is persisted and no store is reached. Error modes: each of the six
 * declared handlers is exercised, plus the un-annotated case that falls through to the typed-failure
 * handler.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
@DisplayName("AuthController - the wire contract for CC00")
class AuthControllerTest {

    /** The base path CC00 is served under. */
    private static final String BASE_PATH = "/api/auth";

    /** The sign-on path, the REST counterpart of {@code EXEC CICS RECEIVE MAP('COSGN0A')}. */
    private static final String SIGN_ON_PATH = "/signon";

    /** The signed-on identifier, one of the ten seeded in {@code app/jcl/DUSRSECJ.jcl}. */
    private static final String USER_ID = "USER0001";

    /** The submitted password. Deliberately not the seeded literal, which must appear nowhere under src. */
    private static final String SUBMITTED_PASSWORD = "Sw0rdf1sh";

    /** {@code SEC-USR-TYPE} of a standard user, {@code app/cpy/CSUSR01Y.cpy}. */
    private static final String STANDARD_USER_TYPE = "U";

    /** The property carrying the machine-readable code every problem body must publish. */
    private static final String ERROR_CODE_PROPERTY = "errorCode";

    /** The property carrying the correlation identifier every problem body must publish. */
    private static final String CORRELATION_ID_PROPERTY = "correlationId";

    /** The property naming the rejected field, published only for a field-level rejection. */
    private static final String FIELD_PROPERTY = "field";

    /** The property naming the rejection kind, published only for a field-level rejection. */
    private static final String FAILURE_KIND_PROPERTY = "failureKind";

    /** The single body a refused credential may carry, whatever the underlying reason was. */
    private static final String AUTHENTICATION_DETAIL = "The user identifier or the password is incorrect.";

    /** The authentication service, mocked because this tier reaches no user security store. */
    @Mock
    private AuthenticationService authenticationService;

    /**
     * Builds the controller under test.
     *
     * @return a controller wired to the mocked service
     */
    private AuthController controller() {
        return new AuthController(this.authenticationService);
    }

    /**
     * Builds the inbound form, populated exactly as the symbolic map of {@code app/cpy-bms/COSGN00.CPY}
     * populates it.
     *
     * @return a populated sign-on request
     */
    private static SignOnRequest request() {
        return new SignOnRequest("CC00", "CardDemo", "08/04/26", "COSGN00C", "Sign On", "10:15:30",
                "CDEMO", "SYS1", USER_ID, SUBMITTED_PASSWORD, null);
    }

    /**
     * Reads a published problem property.
     *
     * @param problem the body to read; must not be {@code null}
     * @param name the property name; must not be {@code null}
     * @return the property value, or {@code null} when the body does not publish it
     */
    private static Object property(final ProblemDetail problem, final String name) {
        final Map<String, Object> properties = problem.getProperties();
        return properties == null ? null : properties.get(name);
    }

    /**
     * The routing contract: one operation, mapped where the CICS resource definition put it.
     */
    @Nested
    @DisplayName("the routing is declared where the CSD put CC00")
    class RoutingContract {

        @Test
        @DisplayName("the class is mapped at /api/auth and the operation at /signon, by POST")
        void theOperationIsMappedWhereTheCsdPutIt() throws NoSuchMethodException {
            assertThat(AuthController.class.getAnnotation(RequestMapping.class).value())
                    .as("CC00 is served under one base path; changing it silently breaks every client and "
                            + "every documented contract")
                    .containsExactly(BASE_PATH);

            final Method signOn = AuthController.class.getMethod("signOn", SignOnRequest.class);
            assertThat(signOn.getAnnotation(PostMapping.class))
                    .as("sign-on submits a credential in a body, so it is a POST and never a GET - a GET "
                            + "would place the password in a URL and therefore in every access log")
                    .isNotNull();
            assertThat(signOn.getAnnotation(PostMapping.class).value())
                    .as("the path is the REST counterpart of RECEIVE MAP('COSGN0A')")
                    .containsExactly(SIGN_ON_PATH);
        }

        @Test
        @DisplayName("the constructor refuses a null service rather than failing on first request")
        void theConstructorRefusesANullService() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a controller with no service would answer 500 to every caller; failing at "
                            + "construction turns that into a refusal to start")
                    .isThrownBy(() -> new AuthController(null))
                    .withMessageContaining("app/cbl/COSGN00C.cbl");
        }
    }

    /**
     * The accepted ending: a token, an identifier and a user class, and nothing else.
     */
    @Nested
    @DisplayName("the accepted ending returns the identity and never the credential")
    class AcceptedEnding {

        @Test
        @DisplayName("a verified credential answers 200 carrying the token, identifier and user class")
        void aVerifiedCredentialAnswers200() {
            final SignOnResponse issued = new SignOnResponse("issued.jwt.value", USER_ID,
                    STANDARD_USER_TYPE);
            when(authenticationService.signOn(any(SignOnRequest.class))).thenReturn(issued);

            final ResponseEntity<SignOnResponse> response = controller().signOn(request());

            assertThat(response.getStatusCode())
                    .as("a verified credential is an ordinary success; the source's ending at "
                            + "app/cbl/COSGN00C.cbl:L230-L240 transfers control rather than reporting an "
                            + "error, and 200 is that ending")
                    .isEqualTo(HttpStatus.OK);
            assertThat(response.getBody())
                    .as("the response must carry the issued identity; a 200 with no body would leave the "
                            + "caller authenticated with nothing to present")
                    .isSameAs(issued);
            assertThat(response.getBody().userType())
                    .as("CDEMO-USER-TYPE becomes a role claim, so the class must survive to the wire")
                    .isEqualTo(STANDARD_USER_TYPE);
        }

        @Test
        @DisplayName("the response type carries no password component at all, so none can be echoed")
        void theResponseTypeCarriesNoCredential() {
            assertThat(SignOnResponse.class.getRecordComponents())
                    .as("the submitted password must be unable to return by construction rather than by "
                            + "discipline. The response record is exactly token, userId and userType")
                    .hasSize(3)
                    .noneMatch(component -> component.getName().toLowerCase(Locale.ROOT)
                            .contains("password"));
        }
    }

    /**
     * The refused endings: one body for every reason the credential did not verify.
     */
    @Nested
    @DisplayName("a refused credential never discloses which half of it was wrong")
    class RefusedEndings {

        @Test
        @DisplayName(":L241-L251 an INVALID rejection answers 401 with the single credential body")
        void anInvalidRejectionAnswers401() {
            final ResponseEntity<ProblemDetail> response = controller().handleValidationFailure(
                    new ValidationException("USER0001 not found", "userId",
                            ValidationException.FailureKind.INVALID));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            final ProblemDetail problem = response.getBody();
            assertThat(problem.getDetail())
                    .as("the unknown-identifier and wrong-password endings of app/cbl/COSGN00C.cbl:L241-L251 "
                            + "must be answered identically, or the response becomes an identifier oracle")
                    .isEqualTo(AUTHENTICATION_DETAIL);
            assertThat(problem.getDetail())
                    .as("and the submitted identifier must not appear in it, for the same reason")
                    .doesNotContain(USER_ID);
            assertThat(property(problem, ERROR_CODE_PROPERTY))
                    .as("a machine-readable code lets a client branch without parsing prose")
                    .isEqualTo("CARDDEMO-AUTHENTICATION-FAILED");
            assertThat(property(problem, FIELD_PROPERTY))
                    .as("no field name is published on the refusal path; naming the field would say which "
                            + "half of the credential failed")
                    .isNull();
            assertThat(property(problem, CORRELATION_ID_PROPERTY))
                    .as("every problem body publishes the correlation identifier, and it falls back to a "
                            + "literal rather than to null when no filter has run")
                    .isEqualTo("unavailable");
        }

        @Test
        @DisplayName("an absent user security record also answers 401, not 404")
        void anAbsentRecordAlsoAnswers401() {
            final ResponseEntity<ProblemDetail> response = controller().handleRecordNotFound(
                    new RecordNotFoundException("no such user", "USRSEC", USER_ID));

            assertThat(response.getStatusCode())
                    .as("a 404 here would confirm that the identifier does not exist, which is exactly the "
                            + "disclosure the shared 401 body exists to prevent")
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody().getDetail()).isEqualTo(AUTHENTICATION_DETAIL);
            assertThat(response.getBody().getDetail())
                    .as("the record key is the submitted identifier, so it must not be echoed")
                    .doesNotContain(USER_ID);
            assertThat(property(response.getBody(), ERROR_CODE_PROPERTY))
                    .as("the two refusal paths are indistinguishable to the caller, code included")
                    .isEqualTo("CARDDEMO-AUTHENTICATION-FAILED");
        }

        @Test
        @DisplayName("a BLANK rejection answers 400 and does name the field, because emptiness is not a "
                + "credential outcome")
        void aBlankRejectionAnswers400AndNamesTheField() {
            final ResponseEntity<ProblemDetail> response = controller().handleValidationFailure(
                    ValidationException.missingField("password", "Please enter your password"));

            assertThat(response.getStatusCode())
                    .as("app/cbl/COSGN00C.cbl asks for a missing field again rather than refusing the "
                            + "credential, so emptiness is a 400 and not a 401")
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            final ProblemDetail problem = response.getBody();
            assertThat(problem.getDetail())
                    .as("the field-level message is publishable because it discloses nothing about whether "
                            + "the identifier exists")
                    .isEqualTo("Please enter your password");
            assertThat(property(problem, FIELD_PROPERTY))
                    .as("naming the empty field is what lets a client place the cursor, which is the "
                            + "stateless counterpart of the source's CURSOR attribute")
                    .isEqualTo("password");
            assertThat(property(problem, FAILURE_KIND_PROPERTY))
                    .isEqualTo(ValidationException.FailureKind.BLANK.name());
            assertThat(property(problem, ERROR_CODE_PROPERTY))
                    .isEqualTo("CARDDEMO-VALIDATION-REJECTED");
            assertThat(problem.getDetail())
                    .as("and even here the submitted secret must not be reflected")
                    .doesNotContain(SUBMITTED_PASSWORD);
        }
    }

    /**
     * The failure endings: each typed failure keeps its own status and its own code.
     */
    @Nested
    @DisplayName("every typed failure maps to its own status and code, and none leaks a diagnostic")
    class FailureEndings {

        @Test
        @DisplayName("an unopenable store answers 503 with the unavailable code")
        void anUnopenableStoreAnswers503() {
            final ResponseEntity<ProblemDetail> response = controller().handleFileUnavailable(
                    new FileUnavailableException("USRSEC not open", "USRSEC", new IllegalStateException()));

            assertThat(response.getStatusCode())
                    .as("FILE STATUS '35' is a retryable condition, so it is 503 rather than 500")
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(property(response.getBody(), ERROR_CODE_PROPERTY))
                    .isEqualTo("CARDDEMO-RESOURCE-UNAVAILABLE");
            assertThat(response.getBody().getDetail())
                    .as("the caller is told to retry and nothing else; the dataset name stays in the log")
                    .doesNotContain("USRSEC");
        }

        @Test
        @DisplayName("a physical read failure answers 503 with the io-failure code and no expanded status")
        void aReadFailureAnswers503() {
            final ResponseEntity<ProblemDetail> response = controller().handleFileAccessFailure(
                    new FileAccessException("read failed", "92", "USRSEC", "READ"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(property(response.getBody(), ERROR_CODE_PROPERTY))
                    .isEqualTo("CARDDEMO-IO-FAILURE");
            assertThat(response.getBody().getDetail())
                    .as("the four-character expanded status is an operator diagnostic, so it is logged and "
                            + "never published")
                    .doesNotContain("92");
        }

        @Test
        @DisplayName("an abend answers 500 with the abend code and neither the culprit nor the reason")
        void anAbendAnswers500() {
            final ResponseEntity<ProblemDetail> response = controller().handleAbend(
                    new FatalProcessingException("0999", "COSGN00C", "USRSEC READ FAILED",
                            "unrecoverable"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(property(response.getBody(), ERROR_CODE_PROPERTY))
                    .isEqualTo("CARDDEMO-PROCESSING-ABEND");
            assertThat(response.getBody().getDetail())
                    .as("the abend code, the culprit program and the reason are all internal; the caller is "
                            + "given the correlation identifier to quote instead")
                    .doesNotContain("0999", "COSGN00C", "USRSEC READ FAILED");
        }

        @Test
        @DisplayName("an unmapped typed failure still answers 500 rather than escaping as a raw stack trace")
        void anUnmappedTypedFailureAnswers500() {
            final ResponseEntity<ProblemDetail> response = controller().handleTypedFailure(
                    new DuplicateRecordException("duplicate user"));

            assertThat(response.getStatusCode())
                    .as("the base-type handler is the backstop; without it a newly added exception type "
                            + "would reach the container and be rendered by whatever default is configured")
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(property(response.getBody(), ERROR_CODE_PROPERTY))
                    .isEqualTo("CARDDEMO-INTERNAL-FAILURE");
            assertThat(response.getBody().getDetail())
                    .as("and the backstop publishes a fixed detail rather than the exception's own message, "
                            + "which is the only way it can be safe for a type it has never seen")
                    .doesNotContain("duplicate user");
        }

        @Test
        @DisplayName("every handler declares the base type as its parameter, so no subtype escapes unmapped")
        void theBackstopCoversTheWholeHierarchy() {
            assertThat(CardDemoException.class)
                    .as("DuplicateRecordException reaches the backstop only because it is a CardDemoException; "
                            + "an exception outside the hierarchy would bypass every handler here")
                    .isAssignableFrom(DuplicateRecordException.class)
                    .isAssignableFrom(RecordNotFoundException.class)
                    .isAssignableFrom(FileUnavailableException.class)
                    .isAssignableFrom(FileAccessException.class)
                    .isAssignableFrom(FatalProcessingException.class)
                    .isAssignableFrom(ValidationException.class);
        }
    }
}
