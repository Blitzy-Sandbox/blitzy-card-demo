package com.vsergeychik.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.config.WebConfig.CobolErrorHandler;
import com.vsergeychik.carddemo.config.WebConfig.CobolErrorHandler.ErrorResponse;
import com.vsergeychik.carddemo.config.WebConfig.CobolErrorHandler.FieldMessage;
import com.vsergeychik.carddemo.config.WebConfig.CobolErrorHandler.ValidationResponse;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Size;
import jakarta.validation.metadata.ConstraintDescriptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The error contract of {@link WebConfig.CobolErrorHandler}: what a failure tells a caller, and -
 * far more importantly - what it does not.
 *
 * <h2>What these tests exist to hold</h2>
 * Two properties, and both are security properties rather than convenience ones:
 *
 * <ul>
 *   <li><strong>No internal state crosses the trust boundary.</strong> An abend body may not carry
 *       the abending COBOL program's name, the {@code RETURN-CODE} it set, or the composed
 *       {@code ABENDING PROGRAM} sentence - which can quote a repository's or a dataset's own reason
 *       text. An unreadable-body response may not carry the parser's message, the offending property,
 *       the Java type it failed to bind, or a byte position.</li>
 *   <li><strong>Every failure answers in the same shape.</strong> A malformed body must not fall
 *       through to Spring Boot's default error envelope, which is both a different shape and a
 *       leakier one.</li>
 * </ul>
 *
 * <p>Each assertion below is written against the withheld value itself - it takes a real abend
 * carrying a real program name and reason, then asserts that neither string appears anywhere in the
 * serialised body. That is stronger than asserting the body equals a constant, because it keeps
 * holding if the body ever gains a field.
 *
 * <p>Plain JUnit throughout: no Spring context, no {@code MockMvc} and no servlet container. Every
 * decision the advice makes is reachable through a static method, which is exactly why it can be
 * driven this way.
 */
@DisplayName("WebConfig.CobolErrorHandler - the failure bodies, and what they withhold")
class WebConfigErrorContractTest {

    /** The advice under test. It is stateless, so one instance serves every case here. */
    private final CobolErrorHandler handler = new CobolErrorHandler();

    /** A program name that must never appear in a response body. */
    private static final String PROGRAM = "CBACT04C";

    /** A reason string that must never appear in a response body. */
    private static final String REASON = "ACCTFILE VSAM open failed, dataset AWS.M2.ACCTDATA";

    @Nested
    @DisplayName("an abend answers 500 and withholds every internal value")
    class AbendMapping {

        @Test
        @DisplayName("the body is the stable code and the generic message")
        void bodyIsStableCodeAndGenericMessage() {
            AbendException abend = AbendException.standard(PROGRAM, 12, REASON);

            ErrorResponse body = CobolErrorHandler.abendResponse(abend);

            assertThat(body.code()).isEqualTo(CobolErrorHandler.ABEND_CODE);
            assertThat(body.message()).isEqualTo(CobolErrorHandler.ABEND_MESSAGE);
        }

        @Test
        @DisplayName("the body carries neither the program, the RETURN-CODE, nor the abend text")
        void bodyCarriesNoInternalState() {
            AbendException abend = AbendException.standard(PROGRAM, 12, REASON);

            ErrorResponse body = CobolErrorHandler.abendResponse(abend);
            String rendered = body.code() + '|' + body.message();

            assertThat(rendered).doesNotContain(PROGRAM);
            assertThat(rendered).doesNotContain(REASON);
            assertThat(rendered).doesNotContain(AbendException.ABEND_DISPLAY_TEXT);
            assertThat(rendered).doesNotContain("12");
            assertThat(rendered).doesNotContain(abend.getMessage());
        }

        @Test
        @DisplayName("the status is 500, because the unit of work did not complete")
        void statusIsInternalServerError() {
            ResponseEntity<ErrorResponse> answer =
                    handler.handleAbend(AbendException.standard(PROGRAM, 8));

            assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(answer.getBody()).isNotNull();
            assertThat(answer.getBody().code()).isEqualTo(CobolErrorHandler.ABEND_CODE);
        }

        @Test
        @DisplayName("the withheld detail is still readable from the exception itself")
        void detailRemainsOnTheException() {
            AbendException abend = AbendException.standard(PROGRAM, 12, REASON);

            assertThat(abend.getProgram()).isEqualTo(PROGRAM);
            assertThat(abend.getReturnCode()).isEqualTo(12);
            assertThat(abend.getMessage()).contains(AbendException.ABEND_DISPLAY_TEXT);
        }

        @Test
        @DisplayName("logging the detail neither throws nor alters the exception")
        void loggingIsSideEffectFree() {
            AbendException abend = AbendException.standard(PROGRAM, 4, REASON);

            CobolErrorHandler.logAbend(abend);

            assertThat(abend.getProgram()).isEqualTo(PROGRAM);
            assertThat(abend.getReturnCode()).isEqualTo(4);
        }

        @Test
        @DisplayName("an absent abend is refused rather than answered")
        void nullAbendIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> CobolErrorHandler.abendResponse(null));
            assertThatNullPointerException().isThrownBy(() -> CobolErrorHandler.logAbend(null));
        }
    }

    @Nested
    @DisplayName("an unreadable request body answers 400 in the same shape")
    class UnreadableBodyMapping {

        /** A parse message of the shape Jackson produces, naming a property and a type. */
        private static final String PARSER_TEXT =
                "Cannot deserialize value of type `long` from String \"x\": "
                        + "at [Source: (String)\"{\"cardNum\":\"x\"}\"; line: 1, column: 13]";

        @Test
        @DisplayName("the body is the stable code and the generic message")
        void bodyIsStableCodeAndGenericMessage() {
            ErrorResponse body = CobolErrorHandler.malformedRequestResponse(unreadable());

            assertThat(body.code()).isEqualTo(CobolErrorHandler.MALFORMED_REQUEST_CODE);
            assertThat(body.message()).isEqualTo(CobolErrorHandler.MALFORMED_REQUEST_MESSAGE);
        }

        @Test
        @DisplayName("the body carries no parser text, property name, type or position")
        void bodyCarriesNoParserDetail() {
            ErrorResponse body = CobolErrorHandler.malformedRequestResponse(unreadable());
            String rendered = body.code() + '|' + body.message();

            assertThat(rendered).doesNotContain(PARSER_TEXT);
            assertThat(rendered).doesNotContain("cardNum");
            assertThat(rendered).doesNotContain("long");
            assertThat(rendered).doesNotContain("column");
        }

        @Test
        @DisplayName("the status is 400, because the caller sent something unreadable")
        void statusIsBadRequest() {
            ResponseEntity<ErrorResponse> answer = handler.handleUnreadableRequestBody(unreadable());

            assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(answer.getBody()).isNotNull();
            assertThat(answer.getBody().code())
                    .isEqualTo(CobolErrorHandler.MALFORMED_REQUEST_CODE);
        }

        @Test
        @DisplayName("a payload guard's own refusal arrives here too, and is answered the same way")
        void aPayloadGuardRefusalIsAnsweredIdentically() {
            HttpMessageNotReadableException wrapped = new HttpMessageNotReadableException(
                    PARSER_TEXT,
                    new IllegalArgumentException("CC-CARD-NUM is declared PIC X(16) but was given 20"),
                    null);

            ResponseEntity<ErrorResponse> answer = handler.handleUnreadableRequestBody(wrapped);

            assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(answer.getBody()).isNotNull();
            assertThat(answer.getBody().message())
                    .isEqualTo(CobolErrorHandler.MALFORMED_REQUEST_MESSAGE);
            assertThat(answer.getBody().message()).doesNotContain("CC-CARD-NUM");
        }

        @Test
        @DisplayName("logging the parse detail neither throws nor is required to be enabled")
        void loggingIsSideEffectFree() {
            CobolErrorHandler.logUnreadableRequestBody(unreadable());
        }

        @Test
        @DisplayName("an absent failure is refused rather than answered")
        void nullFailureIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> CobolErrorHandler.malformedRequestResponse(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> CobolErrorHandler.logUnreadableRequestBody(null));
        }

        private HttpMessageNotReadableException unreadable() {
            return new HttpMessageNotReadableException(PARSER_TEXT, null, null);
        }
    }

    @Nested
    @DisplayName("a rejected field still reports which field and why")
    class ValidationMapping {

        @Test
        @DisplayName("a constraint violation becomes one entry naming the property and the width")
        void constraintViolationBecomesFieldEntry() {
            ValidationResponse body =
                    CobolErrorHandler.validationResponse(violationsOf(new Screen("CU00", "123456789")));

            assertThat(body.error()).isEqualTo(HttpStatus.BAD_REQUEST.getReasonPhrase());
            assertThat(body.fieldErrors()).hasSize(1);
            FieldMessage rejected = body.fieldErrors().get(0);
            assertThat(rejected.field()).isEqualTo("usrIdIn");
            assertThat(rejected.message()).isEqualTo("must be at most 8 characters");
        }

        @Test
        @DisplayName("the violation's own message never reaches the caller")
        void theViolationMessageIsNotForwarded() {
            // The DTOs in this module annotate every @Size with maintainer prose that names the
            // symbolic-map item, its PICTURE clause and the copybook path and LINE NUMBER the width was
            // read from. Forwarding it would publish the copybook inventory to an unauthenticated
            // caller, one rejected field at a time.
            Provenance payload = new Provenance("NINECHARS");

            ValidationResponse body = CobolErrorHandler.validationResponse(violationsOf(payload));

            assertThat(body.fieldErrors()).singleElement().satisfies(entry -> {
                assertThat(entry.field()).isEqualTo("usrIdIn");
                assertThat(entry.message())
                        .isEqualTo("must be at most 8 characters")
                        .doesNotContain("app/cpy-bms")
                        .doesNotContain("PIC X(")
                        .doesNotContain(":72");
            });
        }

        @Test
        @DisplayName("each constraint family maps to its own fixed public sentence")
        void eachConstraintFamilyHasFixedText() {
            assertThat(CobolErrorHandler.publicConstraintText("Size", Map.of("max", 11)))
                    .isEqualTo("must be at most 11 characters");
            // A @Size that states only a minimum leaves max at Integer.MAX_VALUE, which is not a width
            // worth quoting back at a caller.
            assertThat(CobolErrorHandler.publicConstraintText("Size",
                    Map.of("max", Integer.MAX_VALUE)))
                    .isEqualTo("does not satisfy the length declared for its screen field");
            assertThat(CobolErrorHandler.publicConstraintText("Size", Map.of()))
                    .isEqualTo("does not satisfy the length declared for its screen field");
            assertThat(CobolErrorHandler.publicConstraintText("NotNull", Map.of()))
                    .isEqualTo("is required");
            assertThat(CobolErrorHandler.publicConstraintText("NotBlank", Map.of()))
                    .isEqualTo("is required");
            assertThat(CobolErrorHandler.publicConstraintText("Max", Map.of("value", 9L)))
                    .isEqualTo("is outside the range declared for its screen field");
            assertThat(CobolErrorHandler.publicConstraintText("Digits", Map.of()))
                    .isEqualTo("is outside the range declared for its screen field");
            assertThat(CobolErrorHandler.publicConstraintText("Pattern", Map.of()))
                    .isEqualTo("does not match the form declared for its screen field");
            // Total by construction: a constraint annotation introduced later publishes the default
            // rather than its own wording, so no future annotation can leak through this seam.
            assertThat(CobolErrorHandler.publicConstraintText("SomeFutureConstraint", Map.of()))
                    .isEqualTo("is not valid for its screen field");
            assertThat(CobolErrorHandler.publicConstraintText(null, Map.of()))
                    .isEqualTo("is not valid for its screen field");
        }

        @Test
        @DisplayName("entries are ordered by field, so the same failure serialises the same way")
        void entriesAreOrderedByField() {
            ValidationResponse body = CobolErrorHandler
                    .validationResponse(violationsOf(new Screen("CU0000", "123456789")));

            assertThat(body.fieldErrors()).extracting(FieldMessage::field)
                    .containsExactly("trnName", "usrIdIn");
        }

        @Test
        @DisplayName("the status is 400")
        void statusIsBadRequest() {
            ResponseEntity<ValidationResponse> answer = handler
                    .handleConstraintViolation(violationsOf(new Screen("CU00", "123456789")));

            assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        /**
         * The mapping is total over what a validator may hand it, and that matters here more than it
         * usually would: this method runs while an error response is being built, so a failure inside it
         * turns a {@code 400} the caller could act on into a {@code 500} that names an internal class.
         *
         * <p>A {@link ConstraintViolation} created programmatically rather than by a
         * {@code @Size}-style annotation can carry no descriptor at all - the interface permits it - and
         * a descriptor can carry no annotation. Both are answered with the same fixed default sentence
         * as an unrecognised constraint.
         */
        @Test
        @DisplayName("a violation carrying no descriptor, or none carrying an annotation, still answers")
        void aViolationWithNoConstraintMetadataIsStillAnswered() {
            ConstraintViolation<?> noDescriptor = mock(ConstraintViolation.class);

            ValidationResponse withoutDescriptor = CobolErrorHandler
                    .validationResponse(new ConstraintViolationException(Set.of(noDescriptor)));

            assertThat(withoutDescriptor.fieldErrors()).singleElement().satisfies(entry ->
                    assertThat(entry.message()).isEqualTo("is not valid for its screen field"));

            ConstraintDescriptor<?> withoutAnnotation = mock(ConstraintDescriptor.class);
            when(withoutAnnotation.getAttributes()).thenReturn(Map.of());
            ConstraintViolation<?> violation = mock(ConstraintViolation.class);
            doReturn(withoutAnnotation).when(violation).getConstraintDescriptor();

            ValidationResponse withoutCode = CobolErrorHandler
                    .validationResponse(new ConstraintViolationException(Set.of(violation)));

            assertThat(withoutCode.fieldErrors()).singleElement().satisfies(entry ->
                    assertThat(entry.message()).isEqualTo("is not valid for its screen field"));
        }

        /**
         * A field error that did not come from Bean Validation at all - a plain binding or conversion
         * failure - has no constraint to read a bound from, and answers the default rather than reaching
         * for arguments that are not there.
         */
        @Test
        @DisplayName("a plain binding error carries no constraint, and is answered without one")
        void aPlainBindingErrorHasNoConstraintToRead() throws Exception {
            FieldError conversionFailure = new FieldError("screen", "acctsid", null, false,
                    new String[] {"typeMismatch"}, null, "Failed to convert value of type ...");

            ValidationResponse body =
                    CobolErrorHandler.validationResponse(bindingFailure(conversionFailure));

            assertThat(body.fieldErrors()).singleElement().satisfies(entry -> {
                assertThat(entry.field()).isEqualTo("acctsid");
                assertThat(entry.message())
                        .as("no constraint, so no width to quote - and the framework's own text, which "
                                + "names the Java type, is never forwarded")
                        .isEqualTo("is not valid for its screen field");
            });
        }

        /**
         * The other half of the same guard: a field error that <em>does</em> wrap a violation, but one
         * whose descriptor is absent, must not fail while the bound is being read.
         */
        @Test
        @DisplayName("a field error wrapping a violation with no descriptor reads no bound and answers")
        void aWrappedViolationWithNoDescriptorReadsNoBound() throws Exception {
            FieldError error = new FieldError("screen", "acctsid", null, false,
                    new String[] {"Size"}, null, "irrelevant");
            error.wrap(mock(ConstraintViolation.class));

            ValidationResponse body = CobolErrorHandler.validationResponse(bindingFailure(error));

            assertThat(body.fieldErrors()).singleElement().satisfies(entry -> assertThat(entry.message())
                    .as("the code is Size but there is no max to quote, so the length default answers")
                    .isEqualTo("does not satisfy the length declared for its screen field"));
        }

        /** A body-binding failure carrying exactly the errors given, as Spring would raise it. */
        private MethodArgumentNotValidException bindingFailure(FieldError... errors) throws Exception {
            BeanPropertyBindingResult binding =
                    new BeanPropertyBindingResult(new Screen("CU00", "1"), "screen");
            for (FieldError error : errors) {
                binding.addError(error);
            }
            MethodParameter parameter = new MethodParameter(
                    BindingHost.class.getDeclaredMethod("handle", Screen.class), 0);
            return new MethodArgumentNotValidException(parameter, binding);
        }

        /** Supplies a real {@link MethodParameter}; never invoked. */
        private static final class BindingHost {
            void handle(Screen screen) {
                throw new UnsupportedOperationException(
                        "This method exists only so a MethodParameter can be constructed");
            }
        }

        private ConstraintViolationException violationsOf(Object payload) {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                Validator validator = factory.getValidator();
                Set<? extends ConstraintViolation<?>> violations = validator.validate(payload);
                return new ConstraintViolationException(violations);
            }
        }

        /**
         * A minimal stand-in for a screen payload: two maximum-width character fields, the shape
         * every request DTO in this module uses. Field-level constraints, so the property path the
         * advice reports is the field name.
         *
         * @param trnName a {@code PIC X(4)} field
         * @param usrIdIn a {@code PIC X(8)} field
         */
        private record Screen(@Size(max = 4) String trnName, @Size(max = 8) String usrIdIn) {
        }

        /**
         * A stand-in whose constraint message is written the way every request DTO in this module
         * writes one: copybook path, line number and {@code PICTURE} clause, for the engineer
         * maintaining the field rather than for a caller.
         *
         * @param usrIdIn a {@code PIC X(8)} field carrying provenance in its message
         */
        private record Provenance(
                @Size(max = 8,
                        message = "USRIDIN is USRIDINI PIC X(8) at app/cpy-bms/COUSR02.CPY:72 and "
                                + "holds at most 8 characters") String usrIdIn) {
        }
    }

    @Nested
    @DisplayName("over HTTP - the advice actually intercepts a real binding failure")
    class OverHttp {

        /**
         * A one-route stand-in for the screen controllers that arrive in a later checkpoint. It exists
         * only so a request body can be bound by the real message converter and the resulting failure
         * can be seen to reach the real advice.
         */
        @RestController
        static class ScreenController {

            @PostMapping(path = "/screen", consumes = MediaType.APPLICATION_JSON_VALUE)
            String accept(@RequestBody Screen screen) {
                return screen.trnName();
            }
        }

        /**
         * The payload of {@link ScreenController}, deliberately holding a numeric member so a type
         * mismatch can be provoked.
         *
         * @param trnName a {@code PIC X(4)} character field
         * @param pageNum a {@code PIC 9(8)} numeric field
         */
        record Screen(String trnName, int pageNum) {
        }

        private MockMvc mockMvc() {
            MappingJackson2HttpMessageConverter converter =
                    new MappingJackson2HttpMessageConverter(productionLikeMapper());
            return MockMvcBuilders.standaloneSetup(new ScreenController())
                    .setControllerAdvice(new CobolErrorHandler())
                    .setMessageConverters(converter)
                    .build();
        }

        /**
         * An {@code ObjectMapper} carrying the two settings {@code application.yml} and
         * {@link WebConfig#carddemoJacksonCustomizer()} apply in production, so the failures provoked
         * here are the same failures a deployed request would provoke.
         */
        private ObjectMapper productionLikeMapper() {
            Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
            new WebConfig().carddemoJacksonCustomizer().customize(builder);
            return builder.featuresToEnable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .build();
        }

        @Test
        @DisplayName("malformed JSON answers 400 with the stable body, not Boot's default envelope")
        void malformedJsonIsAnsweredByTheAdvice() throws Exception {
            mockMvc().perform(post("/screen").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"trnName\":"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(CobolErrorHandler.MALFORMED_REQUEST_CODE))
                    .andExpect(jsonPath("$.message")
                            .value(CobolErrorHandler.MALFORMED_REQUEST_MESSAGE))
                    .andExpect(jsonPath("$.error").doesNotExist())
                    .andExpect(jsonPath("$.path").doesNotExist());
        }

        @Test
        @DisplayName("a property tracing to no DFHMDF field answers 400 and names nothing")
        void unknownPropertyIsAnsweredByTheAdvice() throws Exception {
            mockMvc().perform(post("/screen").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"trnName\":\"CU00\",\"pageNum\":1,\"notAField\":\"x\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(CobolErrorHandler.MALFORMED_REQUEST_CODE))
                    .andExpect(content().string(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("notAField"))));
        }

        @Test
        @DisplayName("a value of the wrong JSON type answers 400 and names no Java type")
        void typeMismatchIsAnsweredByTheAdvice() throws Exception {
            mockMvc().perform(post("/screen").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"trnName\":\"CU00\",\"pageNum\":\"not-a-number\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(CobolErrorHandler.MALFORMED_REQUEST_CODE))
                    .andExpect(content().string(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("int"))));
        }

        @Test
        @DisplayName("a well-formed body still binds, so the handler intercepts only failures")
        void wellFormedBodyStillBinds() throws Exception {
            mockMvc().perform(post("/screen").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"trnName\":\"CU00\",\"pageNum\":1}"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("\"CU00\""));
        }
    }

    @Nested
    @DisplayName("a repository outcome maps to the status that reports it")
    class OutcomeMapping {

        @Test
        @DisplayName("the four outcomes the COBOL handles in-program are 200")
        void handledOutcomesAreOk() {
            assertThat(List.of(FileStatus.Outcome.OK,
                            FileStatus.Outcome.END_OF_FILE,
                            FileStatus.Outcome.NOT_FOUND,
                            FileStatus.Outcome.DUPLICATE)
                    .stream()
                    .map(CobolErrorHandler::statusForOutcome))
                    .containsOnly(HttpStatus.OK);
        }

        @Test
        @DisplayName("WHEN OTHER - the arm every guard chain abends on - is 500")
        void otherIsInternalServerError() {
            assertThat(CobolErrorHandler.statusForOutcome(FileStatus.Outcome.OTHER))
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
