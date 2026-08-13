package com.vsergeychik.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.billing.dto.BillPaymentRequest;
import com.vsergeychik.carddemo.billing.dto.BillPaymentResponse;
import com.vsergeychik.carddemo.card.CardRepository;
import com.vsergeychik.carddemo.card.CardSelectController;
import com.vsergeychik.carddemo.card.dto.CardListRequest;
import com.vsergeychik.carddemo.card.dto.CardListResponse;
import com.vsergeychik.carddemo.card.dto.CardUpdateResponse;
import com.vsergeychik.carddemo.config.WebConfig.CobolErrorHandler;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenInputRejectedException;
import com.vsergeychik.carddemo.transaction.dto.TransactionAddResponse;
import com.vsergeychik.carddemo.user.SecUserRepository;
import com.vsergeychik.carddemo.user.SecUserRepository.WriteResult;
import com.vsergeychik.carddemo.user.UserAddController;
import com.vsergeychik.carddemo.user.dto.UserAddRequest;
import com.vsergeychik.carddemo.user.model.SecUserRecord;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The JSON boundary of the online screens, exercised with the converter a deployed request actually meets.
 */
@DisplayName("The production JSON converter, on real online routes")
final class ProductionJsonBoundaryTest {
    private static final Charset TEST_PROFILE_CHARSET = StandardCharsets.US_ASCII;

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2022-07-19T23:12:33Z"), ZoneOffset.UTC);

    private static final String CARD_NUMBER = "4000000000000001";

    private static ObjectMapper productionMapper() {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new WebConfig().carddemoJacksonCustomizer(TEST_PROFILE_CHARSET).customize(builder);
        return builder.featuresToEnable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    }

    private static MappingJackson2HttpMessageConverter productionConverter() {
        return new MappingJackson2HttpMessageConverter(productionMapper());
    }

    private final SecUserRepository secUserRepository = mock(SecUserRepository.class);

    private final CardRepository cardRepository = mock(CardRepository.class);

    private MockMvc addUserRoute() {
        when(secUserRepository.add(any(SecUserRecord.class))).thenReturn(WriteResult.written());
        when(secUserRepository.datasetCharset()).thenReturn(StandardCharsets.US_ASCII);
        return MockMvcBuilders
                .standaloneSetup(new UserAddController(secUserRepository, FIXED_CLOCK))
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

    private static UserAddRequest addRequest(String fName) {
        return addRequest(fName, "Doe");
    }

    private static UserAddRequest addRequest(String fName, String lName) {
        return new UserAddRequest(null, null, null, null, null, null,
                fName, lName, "USR1", "PASS1234", "U", null,
                NavigationContext.empty().withPgmReenter(), null);
    }

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
            new WebConfig().carddemoJacksonCustomizer(TEST_PROFILE_CHARSET).customize(builder);
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
            assertThat(mapper.getFactory().isEnabled(
                    com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION))
                    .as("Jackson's default is disabled, so a repeated member silently resolves "
                            + "last-wins and one of the two stated values disappears")
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
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("notAField"))
                    .andExpect(content().string(not(containsString("John"))));

            verify(secUserRepository, never()).add(any(SecUserRecord.class));
        }

        @ParameterizedTest(name = "a screen field written as {0}")
        @ValueSource(strings = {
            "{\"fname\":{}}",
            "{\"fname\":{\"a\":\"John\"}}",
            "{\"fname\":[]}",
            "{\"fname\":[\"John\"]}",
            "{\"fname\":\"John\",\"lname\":{\"a\":\"Doe\"}}",
            "{\"navigationContext\":{\"fromProgram\":[\"COUSR00C\"]}}"
        })
        @DisplayName("POST /api/users: an object or an array where a PIC X field belongs is refused, "
                + "rather than bound as a field the caller left unpainted")
        void aStructuredValueForAScreenFieldIsRefused(String body) throws Exception {
            addUserRoute().perform(post("/api/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.code").value(CobolErrorHandler.MALFORMED_REQUEST_CODE))
                    .andExpect(jsonPath("$.detail").value(CobolErrorHandler.MALFORMED_REQUEST_MESSAGE))
                    .andExpect(content().string(not(containsString("John"))))
                    .andExpect(content().string(not(containsString("java.lang.String"))));

            verify(secUserRepository, never()).add(any(SecUserRecord.class));
        }

        @Test
        @DisplayName("POST /api/users: the refusal names the member at fault, so the answer is actionable")
        void aStructuredValueRefusalNamesTheMember() throws Exception {
            addUserRoute().perform(post("/api/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"fname\":\"John\",\"lname\":{\"a\":\"Doe\"}}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("lname"))
                    .andExpect(content().string(not(containsString("Doe"))));

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
            addUserRoute().perform(post("/api/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(CobolErrorHandler.MALFORMED_REQUEST_CODE))
                    .andExpect(jsonPath("$.detail")
                            .value(CobolErrorHandler.MALFORMED_REQUEST_MESSAGE));

            verify(secUserRepository, never()).add(any(SecUserRecord.class));

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
    @DisplayName("a screen field is character data, or the request is refused before the program runs")
    class ScreenFieldsAreCharacterData {
        @ParameterizedTest(name = "fname as {0}")
        @ValueSource(strings = {"11", "1.5", "true", "false"})
        @DisplayName("POST /api/users: a number or a boolean where a screen field belongs is refused, "
                + "and no repository is touched")
        void aWrongTokenShapeIsRefusedOnARealRoute(String token) throws Exception {
            addUserRoute().perform(post("/api/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"fname\":" + token + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(CobolErrorHandler.REJECTED_VALUE_CODE))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("fname"))
                    .andExpect(jsonPath("$.detail")
                            .value(containsString("must be sent as a JSON string")));

            verify(secUserRepository, never()).add(any(SecUserRecord.class));
        }

        @ParameterizedTest(name = "fname as {0}")
        @ValueSource(strings = {"{\"a\":\"b\"}", "[\"John\"]", "{}", "[]"})
        @DisplayName("POST /api/users: an object or an array is refused through Jackson's own "
                + "unexpected-token path, which is the mapping-failure arm, and no repository is touched")
        void aStructuredTokenIsRefusedOnARealRoute(String token) throws Exception {
            addUserRoute().perform(post("/api/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"fname\":" + token + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(CobolErrorHandler.MALFORMED_REQUEST_CODE));

            verify(secUserRepository, never()).add(any(SecUserRecord.class));
        }

        @Test
        @DisplayName("GET /api/cards/{cardNum}: the same refusal on a route whose body is optional")
        void aWrongTokenShapeIsRefusedOnAGetRouteAsWell() throws Exception {
            cardDetailRoute().perform(get("/api/cards/{cardNum}", CARD_NUMBER)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"cardsid\":4000000000000001}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(CobolErrorHandler.REJECTED_VALUE_CODE))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("cardsid"));

            verifyNoInteractions(cardRepository);
        }

        @Test
        @DisplayName("POST /api/users: a character the screen code page cannot represent is refused on "
                + "this route too, which no controller sweep covered before")
        void anUnrepresentableCharacterIsRefusedOnARealRoute() throws Exception {
            addUserRoute().perform(post("/api/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"fname\":\"Jos\u00e9\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(CobolErrorHandler.REJECTED_VALUE_CODE))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("fname"))
                    .andExpect(content().string(not(containsString("US-ASCII"))))
                    .andExpect(content().string(not(containsString("U+00E9"))));

            verify(secUserRepository, never()).add(any(SecUserRecord.class));
        }

        @Test
        @DisplayName("GET /api/cards/{cardNum}: an unrepresentable character in a NESTED communication "
                + "area member is refused, which is the half a received-map sweep cannot reach")
        void anUnrepresentableNestedMemberIsRefused() throws Exception {
            cardDetailRoute().perform(get("/api/cards/{cardNum}", CARD_NUMBER)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"navigationContext\":{\"fromProgram\":\"CO\u00d1EN01C\"}}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(CobolErrorHandler.REJECTED_VALUE_CODE))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("fromProgram"));

            verifyNoInteractions(cardRepository);
        }
    }

    @Nested
    @DisplayName("the whole request surface is judged, all seventeen families of it")
    class WholeSurface {
        private static final List<Class<?>> REQUEST_FAMILIES = List.of(
                com.vsergeychik.carddemo.account.dto.AccountViewRequest.class,
                com.vsergeychik.carddemo.account.dto.AccountUpdateRequest.class,
                com.vsergeychik.carddemo.admin.dto.AdminMenuRequest.class,
                com.vsergeychik.carddemo.admin.dto.MainMenuRequest.class,
                com.vsergeychik.carddemo.billing.dto.BillPaymentRequest.class,
                com.vsergeychik.carddemo.card.dto.CardListRequest.class,
                com.vsergeychik.carddemo.card.dto.CardSelectRequest.class,
                com.vsergeychik.carddemo.card.dto.CardUpdateRequest.class,
                com.vsergeychik.carddemo.transaction.dto.ReportRequestRequest.class,
                com.vsergeychik.carddemo.transaction.dto.TransactionAddRequest.class,
                com.vsergeychik.carddemo.transaction.dto.TransactionListRequest.class,
                com.vsergeychik.carddemo.transaction.dto.TransactionViewRequest.class,
                com.vsergeychik.carddemo.user.dto.SignOnRequest.class,
                com.vsergeychik.carddemo.user.dto.UserAddRequest.class,
                com.vsergeychik.carddemo.user.dto.UserDeleteRequest.class,
                com.vsergeychik.carddemo.user.dto.UserListRequest.class,
                com.vsergeychik.carddemo.user.dto.UserUpdateRequest.class);

        static List<Class<?>> requestFamilies() {
            return REQUEST_FAMILIES;
        }

        @Test
        @DisplayName("there are exactly seventeen request families, one per CICS online program")
        void thereAreSeventeenFamilies() {
            assertThat(REQUEST_FAMILIES).hasSize(17).doesNotHaveDuplicates();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("requestFamilies")
        @DisplayName("every family refuses a character the screen code page cannot represent - the "
                + "judgement reaches all seventeen, not the three that sweep their received map")
        void everyFamilyRefusesAnUnrepresentableCharacter(final Class<?> family) throws Exception {
            String body = "{\"navigationContext\":{\"fromProgram\":\"CO\u00d1EN01C\"}}";

            assertThatThrownBy(() -> productionMapper().readValue(body, family))
                    .as("%s must refuse a character no RECEIVE MAP could deliver", family.getSimpleName())
                    .satisfies(failure -> assertThat(rootCauseOf(failure))
                            .isInstanceOf(ScreenInputRejectedException.class));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("requestFamilies")
        @DisplayName("every family refuses a member that is not JSON character data")
        void everyFamilyRefusesANonStringMember(final Class<?> family) {
            String body = "{\"navigationContext\":{\"fromProgram\":11}}";

            assertThatThrownBy(() -> productionMapper().readValue(body, family))
                    .as("%s must refuse a number where a PIC X member belongs", family.getSimpleName())
                    .satisfies(failure -> assertThat(rootCauseOf(failure))
                            .isInstanceOf(ScreenInputRejectedException.class));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("requestFamilies")
        @DisplayName("every family refuses a repeated member rather than keeping the last occurrence")
        void everyFamilyRefusesADuplicateMember(final Class<?> family) {
            String body = "{\"navigationContext\":{\"fromProgram\":\"COMEN01C\","
                    + "\"fromProgram\":\"COADM01C\"}}";

            assertThatThrownBy(() -> productionMapper().readValue(body, family))
                    .as("%s must refuse two values for one member", family.getSimpleName())
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Duplicate");
        }

        private static Throwable rootCauseOf(final Throwable failure) {
            Throwable cause = failure;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            return cause;
        }
    }

    @Nested
    @DisplayName("one member states one value: a repeated member is refused, never resolved last-wins")
    class DuplicateMembers {
        @Test
        @DisplayName("POST /api/users: two values for one screen field are refused, and neither is used")
        void aRepeatedTopLevelMemberIsRefused() throws Exception {
            addUserRoute().perform(post("/api/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"fname\":\"John\",\"fname\":\"Jane\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(CobolErrorHandler.MALFORMED_REQUEST_CODE))
                    .andExpect(content().string(not(containsString("Jane"))));

            verify(secUserRepository, never()).add(any(SecUserRecord.class));
        }

        @Test
        @DisplayName("POST /api/users: a repeated password member is refused, so the comparison cannot "
                + "run against a value the caller may not have meant")
        void aRepeatedPasswordMemberIsRefused() throws Exception {
            addUserRoute().perform(post("/api/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"fname\":\"John\",\"passwd\":\"PASS1234\","
                                    + "\"passwd\":\"OTHER999\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(CobolErrorHandler.MALFORMED_REQUEST_CODE))
                    .andExpect(content().string(not(containsString("OTHER999"))));

            verify(secUserRepository, never()).add(any(SecUserRecord.class));
        }

        @Test
        @DisplayName("GET /api/cards/{cardNum}: a repeated member NESTED in the communication area is "
                + "refused as well, so the strictness is not only top level")
        void aRepeatedNestedMemberIsRefused() throws Exception {
            cardDetailRoute().perform(get("/api/cards/{cardNum}", CARD_NUMBER)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"navigationContext\":{\"fromProgram\":\"COMEN01C\","
                                    + "\"fromProgram\":\"COADM01C\"}}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(CobolErrorHandler.MALFORMED_REQUEST_CODE));

            verifyNoInteractions(cardRepository);
        }

        @Test
        @DisplayName("the control: one member stated once still binds, so the refusals above are "
                + "attributable to the repetition and to nothing else")
        void oneMemberStatedOnceStillBinds() throws Exception {
            addUserRoute().perform(post("/api/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"fname\":\"John\",\"passwd\":\"PASS1234\"}"))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("what a screen field means is not decided by Jackson's defaults")
    class FieldFidelity {
        @Test
        @DisplayName("an empty PIC X field binds as itself and is not coerced to null")
        void anEmptyFieldIsNotNull() throws Exception {
            addUserRoute().perform(post("/api/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(addRequest(""))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.errmsg").value(containsString("First Name")));

            verify(secUserRepository, never()).add(any(SecUserRecord.class));
        }

        @Test
        @DisplayName("a leading space survives, and the field comes back at its declared PIC width")
        void leadingSpaceSurvivesAndWidthIsPreserved() throws Exception {
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

    @Nested
    @DisplayName("a screen projection reaches the wire in the order its BMS map declares")
    class MapOrderIsPublished {
        @ParameterizedTest(name = "{0} leads with its map, in app/cpy-bms order")
        @MethodSource(
                "com.vsergeychik.carddemo.config.ProductionJsonBoundaryTest#screenProjections")
        @DisplayName("the map projection is the leading run of members, in the copybook's own order")
        void theMapLeadsInCopybookOrder(String name, Object projection, List<String> mapFields) {
            JsonNode published = productionMapper().valueToTree(projection);
            List<String> members = new ArrayList<>();
            published.fieldNames().forEachRemaining(members::add);

            assertThat(members).as("%s must publish every map field", name)
                    .containsAll(mapFields);
            assertThat(members.subList(0, mapFields.size()))
                    .as("%s must lead with its map, in the order app/cpy-bms declares it", name)
                    .containsExactlyElementsOf(mapFields);
        }

        @ParameterizedTest(name = "{0} publishes no member twice")
        @MethodSource(
                "com.vsergeychik.carddemo.config.ProductionJsonBoundaryTest#screenProjections")
        @DisplayName("no member is published twice, so the order names each member exactly once")
        void noMemberIsPublishedTwice(String name, Object projection, List<String> mapFields) {
            JsonNode published = productionMapper().valueToTree(projection);
            List<String> members = new ArrayList<>();
            published.fieldNames().forEachRemaining(members::add);

            assertThat(members).as("%s (%d map fields)", name, mapFields.size())
                    .doesNotHaveDuplicates();
        }
    }

    static Stream<Arguments> screenProjections() {
        return Stream.of(
            Arguments.of("BillPaymentRequest", new BillPaymentRequest(), List.of(
                    "trnname", "title01", "curdate", "pgmname", "title02", "curtime", "actidin",
                    "curbal", "confirm", "errmsg")),
            Arguments.of("BillPaymentResponse", new BillPaymentResponse(), List.of(
                    "trnname", "title01", "curdate", "pgmname", "title02", "curtime", "actidin",
                    "curbal", "confirm", "errmsg")),
            Arguments.of("CardListRequest", new CardListRequest(), List.of(
                    "trnname", "title01", "curdate", "pgmname", "title02", "curtime", "pageno",
                    "acctsid", "cardsid", "crdsel1", "acctno1", "crdnum1", "crdsts1", "crdsel2",
                    "crdstp2", "acctno2", "crdnum2", "crdsts2", "crdsel3", "crdstp3", "acctno3",
                    "crdnum3", "crdsts3", "crdsel4", "crdstp4", "acctno4", "crdnum4", "crdsts4",
                    "crdsel5", "crdstp5", "acctno5", "crdnum5", "crdsts5", "crdsel6", "crdstp6",
                    "acctno6", "crdnum6", "crdsts6", "crdsel7", "crdstp7", "acctno7", "crdnum7",
                    "crdsts7", "infomsg", "errmsg")),
            Arguments.of("CardListResponse", new CardListResponse(), List.of(
                    "trnname", "title01", "curdate", "pgmname", "title02", "curtime", "pageno",
                    "acctsid", "cardsid", "crdsel1", "acctno1", "crdnum1", "crdsts1", "crdsel2",
                    "crdstp2", "acctno2", "crdnum2", "crdsts2", "crdsel3", "crdstp3", "acctno3",
                    "crdnum3", "crdsts3", "crdsel4", "crdstp4", "acctno4", "crdnum4", "crdsts4",
                    "crdsel5", "crdstp5", "acctno5", "crdnum5", "crdsts5", "crdsel6", "crdstp6",
                    "acctno6", "crdnum6", "crdsts6", "crdsel7", "crdstp7", "acctno7", "crdnum7",
                    "crdsts7", "infomsg", "errmsg")),
            Arguments.of("CardUpdateResponse", new CardUpdateResponse(), List.of(
                    "trnname", "title01", "curdate", "pgmname", "title02", "curtime", "acctsid",
                    "cardsid", "crdname", "crdstcd", "expmon", "expyear", "expday", "infomsg",
                    "errmsg", "fkeys", "fkeysc")),
            Arguments.of("TransactionAddResponse", new TransactionAddResponse(), List.of(
                    "trnname", "title01", "curdate", "pgmname", "title02", "curtime", "trnidin",
                    "trnid", "cardnum", "ttypcd", "tcatcd", "trnsrc", "tdesc", "trnamt",
                    "torigdt", "tprocdt", "mid", "mname", "mcity", "mzip", "errmsg")));
    }
}
