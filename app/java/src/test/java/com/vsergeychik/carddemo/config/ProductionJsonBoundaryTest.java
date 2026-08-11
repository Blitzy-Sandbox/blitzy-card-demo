package com.vsergeychik.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.card.CardRepository;
import com.vsergeychik.carddemo.card.CardSelectController;
import com.vsergeychik.carddemo.config.WebConfig.CobolErrorHandler;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.user.SecUserRepository;
import com.vsergeychik.carddemo.user.SecUserRepository.WriteResult;
import com.vsergeychik.carddemo.user.UserAddController;
import com.vsergeychik.carddemo.user.dto.UserAddRequest;
import com.vsergeychik.carddemo.user.model.SecUserRecord;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The JSON boundary of the online screens, exercised with the converter a deployed request actually
 * meets.
 *
 * <h2>Why this class exists separately from the controller suites</h2>
 * Every screen's own test suite stands its controller up with {@code MockMvcBuilders.standaloneSetup},
 * which supplies a <strong>default</strong> {@code ObjectMapper}. That is the right call there - those
 * suites assert program behaviour, and a default mapper keeps the JSON out of the way - but it means the
 * settings {@code application.yml} and {@link WebConfig#carddemoJacksonCustomizer()} apply in production
 * are not exercised on any real route: unknown-property rejection, trailing-token rejection, plain
 * {@code BigDecimal} rendering and the deliberate refusal to coerce {@code ""} to {@code null} would
 * each survive being switched off without a single test going red. Runtime testing raised exactly that
 * gap.
 *
 * <p>So the converter here is built from the customizer itself, plus the one feature
 * {@code application.yml} turns on, and it is pointed at two real controllers - one {@code POST} whose
 * body is required and one {@code GET} whose body is optional. What is asserted is the boundary, never a
 * program branch: a body either binds whole or is refused, and nothing about a refusal names anything
 * internal.
 *
 * <p>{@code WebConfigErrorContractTest} covers the same envelopes against a purpose-built stand-in
 * controller; this class is the other half of the pair, and the halves are deliberately not merged -
 * that one proves the advice maps the failure, this one proves a screen a client actually calls provokes
 * it.
 */
@DisplayName("The production JSON converter, on real online routes")
final class ProductionJsonBoundaryTest {

    /** {@code FUNCTION CURRENT-DATE} pinned, so a screen header never depends on the wall clock. */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2022-07-19T23:12:33Z"), ZoneOffset.UTC);

    /** The card number {@code GET /api/cards/{cardNum}} is addressed with, {@code PIC X(16)}. */
    private static final String CARD_NUMBER = "4000000000000001";

    /**
     * An {@code ObjectMapper} carrying exactly what production carries: the customizer's own settings
     * plus {@code spring.jackson.deserialization.fail-on-unknown-properties} from
     * {@code application.yml}.
     */
    private static ObjectMapper productionMapper() {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new WebConfig().carddemoJacksonCustomizer().customize(builder);
        return builder.featuresToEnable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    }

    private static MappingJackson2HttpMessageConverter productionConverter() {
        return new MappingJackson2HttpMessageConverter(productionMapper());
    }

    /** The {@code USRSEC} dataset, stubbed so a successful add reports what CICS would report. */
    private final SecUserRepository secUserRepository = mock(SecUserRepository.class);

    /** The {@code CARDDAT} dataset. Never reached by a request that fails to bind. */
    private final CardRepository cardRepository = mock(CardRepository.class);

    private MockMvc addUserRoute() {
        when(secUserRepository.add(any(SecUserRecord.class))).thenReturn(WriteResult.written());
        return MockMvcBuilders
                .standaloneSetup(new UserAddController(secUserRepository, FIXED_CLOCK,
                        StandardCharsets.US_ASCII))
                .setControllerAdvice(new CobolErrorHandler())
                .setMessageConverters(productionConverter())
                .build();
    }

    private MockMvc cardDetailRoute() {
        return MockMvcBuilders
                .standaloneSetup(new CardSelectController(cardRepository, FIXED_CLOCK,
                        StandardCharsets.US_ASCII))
                .setControllerAdvice(new CobolErrorHandler())
                .setMessageConverters(productionConverter())
                .build();
    }

    /**
     * A {@code COUSR01} payload with the five data fields as given, carrying a re-entered communication
     * area - which is what reaches the key dispatch at {@code COUSR01C:90-103}.
     */
    private static UserAddRequest addRequest(String fName) {
        return addRequest(fName, "Doe");
    }

    /**
     * The same payload with the last name stated too, so a test can stop the guard chain of
     * {@code COUSR01C:117-151} on a chosen field and see what the program echoed back.
     */
    private static UserAddRequest addRequest(String fName, String lName) {
        return new UserAddRequest(null, null, null, null, null, null,
                fName, lName, "USR1", "PASS1234", "U", null,
                NavigationContext.empty().withPgmReenter(), null);
    }

    /**
     * Serialises a payload through the production mapper, so the body a test sends is the body a client
     * that echoes this module's own DTO would send - every member present, at its declared width.
     */
    private static String body(UserAddRequest request) throws Exception {
        return productionMapper().writeValueAsString(request);
    }

    @Nested
    @DisplayName("the settings are the deployed ones")
    class Settings {

        @Test
        @DisplayName("the customizer alone carries all four decisions, so nothing depends on yml order")
        void theCustomizerCarriesItsOwnDecisions() {
            Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
            new WebConfig().carddemoJacksonCustomizer().customize(builder);
            ObjectMapper mapper = builder.build();

            assertThat(mapper.isEnabled(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)).isTrue();
            assertThat(mapper.isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS))
                    .as("without it a body is read up to the end of its first value and the rest is "
                            + "discarded, and the caller is told 200")
                    .isTrue();
            assertThat(mapper.isEnabled(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT))
                    .as("an all-spaces or empty PIC X field is meaningful data, not an absent value")
                    .isFalse();
            assertThat(mapper.getFactory().isEnabled(
                    com.fasterxml.jackson.core.JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN))
                    .isTrue();
        }

        @Test
        @DisplayName("a scale-2 amount serialises plainly through this very converter")
        void amountsSerialisePlainly() throws Exception {
            assertThat(productionMapper().writeValueAsString(new BigDecimal("-100.00")))
                    .isEqualTo("-100.00");
            assertThat(productionMapper().writeValueAsString(new BigDecimal("1E+2")))
                    .as("a monetary field a fixed-width receiver reads can never be exponential")
                    .isEqualTo("100");
        }
    }

    @Nested
    @DisplayName("a body binds whole, or is refused")
    class BindOrRefuse {

        @Test
        @DisplayName("POST /api/users: a well-formed body still binds and the add reaches the dataset")
        void aWellFormedBodyBinds() throws Exception {
            addUserRoute().perform(post("/api/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(addRequest("John"))))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.errmsg").value(containsString("has been added")));

            verify(secUserRepository).add(any(SecUserRecord.class));
        }

        @Test
        @DisplayName("POST /api/users: a member tracing to no DFHMDF field is refused, and not echoed")
        void anUnknownMemberIsRefused() throws Exception {
            addUserRoute().perform(post("/api/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"fname\":\"John\",\"notAField\":\"x\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(CobolErrorHandler.MALFORMED_REQUEST_CODE))
                    // The refused member is named so a caller can act on the answer; its value is not.
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("notAField"))
                    .andExpect(content().string(not(containsString("John"))));

            verify(secUserRepository, never()).add(any(SecUserRecord.class));
        }

        @ParameterizedTest(name = "trailing content: {0}")
        @ValueSource(strings = {
            "{\"fname\":\"John\"} GARBAGE",
            "{\"fname\":\"John\"}{\"userid\":\"USR2\"}",
            "{\"fname\":\"John\"} 42",
            "{\"fname\":\"John\"} null"
        })
        @DisplayName("POST /api/users: content after the screen is refused rather than discarded")
        void trailingContentIsRefused(String body) throws Exception {
            // Before FAIL_ON_TRAILING_TOKENS these all answered 200: the first document bound, the rest
            // vanished, and nothing in the response distinguished that from a body read in full. A screen
            // whose second half was silently dropped is the same silent-loss shape this module refuses
            // everywhere else - an unknown member is refused, an over-width value is refused.
            addUserRoute().perform(post("/api/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(CobolErrorHandler.MALFORMED_REQUEST_CODE))
                    .andExpect(jsonPath("$.detail")
                            .value(CobolErrorHandler.MALFORMED_REQUEST_MESSAGE));

            verify(secUserRepository, never()).add(any(SecUserRecord.class));

            // The control, so the refusal above is attributable to the trailing content and to nothing
            // else about the body: the same document, with nothing after it, binds and answers 200.
            addUserRoute().perform(post("/api/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"fname\":\"John\"}"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("GET /api/cards/{cardNum}: trailing content in an optional body is refused too")
        void trailingContentIsRefusedOnAGetRouteAsWell() throws Exception {
            cardDetailRoute().perform(get("/api/cards/{cardNum}", CARD_NUMBER)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{} GARBAGE"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(CobolErrorHandler.MALFORMED_REQUEST_CODE));

            verifyNoInteractions(cardRepository);
        }

        @Test
        @DisplayName("GET /api/cards/{cardNum}: no body at all is still the cold start, not a refusal")
        void anAbsentBodyIsStillCleanlyTheColdStart() throws Exception {
            cardDetailRoute().perform(get("/api/cards/{cardNum}", CARD_NUMBER))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));

            verifyNoInteractions(cardRepository);
        }
    }

    @Nested
    @DisplayName("what a screen field means is not decided by Jackson's defaults")
    class FieldFidelity {

        @Test
        @DisplayName("an empty PIC X field binds as itself and is not coerced to null")
        void anEmptyFieldIsNotNull() throws Exception {
            // ACCEPT_EMPTY_STRING_AS_NULL_OBJECT is disabled explicitly. If it were on, a screen field
            // the operator cleared would arrive as absent, and the first-blank validation chain of
            // COUSR01C:117-151 would be reporting on a value the client never sent.
            addUserRoute().perform(post("/api/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(addRequest(""))))
                    .andExpect(status().isOk())
                    // The blank first name is what the program reports on, which is only possible if the
                    // empty string survived the converter.
                    .andExpect(jsonPath("$.errmsg").value(containsString("First Name")));

            verify(secUserRepository, never()).add(any(SecUserRecord.class));
        }

        @Test
        @DisplayName("a leading space survives, and the field comes back at its declared PIC width")
        void leadingSpaceSurvivesAndWidthIsPreserved() throws Exception {
            // The last name is left blank on purpose, so the guard chain stops there and the screen is
            // repainted with what was typed. A successful add would not do: COUSR01C:290-295 clears the
            // five input fields to SPACES, so the echoed first name would be blank by the program's own
            // decision and would say nothing about the converter.
            String painted = addUserRoute().perform(post("/api/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(addRequest(" John", ""))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.errmsg").value(containsString("Last Name")))
                    .andReturn().getResponse().getContentAsString();

            String firstName = productionMapper().readTree(painted).get("fname").asText();
            assertThat(firstName)
                    .as("FNAMEI is PIC X(20): space-padded on the right, and never trimmed on the left")
                    .startsWith(" John")
                    .hasSize(20);

            verify(secUserRepository, never()).add(any(SecUserRecord.class));
        }

        @Test
        @DisplayName("no response from either route carries an exponent or an internal name")
        void nothingInternalOrExponentialReachesTheWire() throws Exception {
            String added = addUserRoute().perform(post("/api/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(addRequest("John"))))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            String painted = cardDetailRoute().perform(get("/api/cards/{cardNum}", CARD_NUMBER))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            for (String body : new String[] {added, painted}) {
                assertThat(body).doesNotContain("com.vsergeychik", "java.lang", "org.springframework",
                        "Exception", "jdbc:", "SELECT ", "INSERT INTO");
                assertThat(body).doesNotMatch("(?s).*[0-9]E[+-][0-9].*");
            }
        }
    }
}
