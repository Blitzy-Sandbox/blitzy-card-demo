package com.vsergeychik.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;

import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.ScreenInputRejectedException;
import com.vsergeychik.carddemo.config.WebConfig.CobolErrorHandler;
import com.vsergeychik.carddemo.testsupport.ScreenFixtureController;
import com.vsergeychik.carddemo.user.SignOnController;
import com.vsergeychik.carddemo.user.UserAddController;
import com.vsergeychik.carddemo.user.UserUpdateController;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionOverrideException;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.MockHttpOutputMessage;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.handler.MappedInterceptor;
import org.springframework.web.util.ServletRequestPathUtils;

/**
 * {@link WebConfig}'s own contract: the JSON settings that keep a screen projection byte-faithful, the
 * {@link Clock} the screen header depends on, the global error mapping as the real dispatcher resolves it,
 * and - the highest-value part - everything this class deliberately refuses to contribute.
 */
@DisplayName("WebConfig - JSON fidelity, the Clock bean, the error mapping, and what it refuses")
class WebConfigTest {
    private static final Charset TEST_PROFILE_CHARSET = StandardCharsets.US_ASCII;

    private static final Instant FIXED_INSTANT = Instant.parse("2022-07-19T23:23:06Z");

    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    private static final String WITHHELD_PROGRAM = ScreenFixtureController.WITHHELD_PROGRAM;

    private static final String WITHHELD_REASON = ScreenFixtureController.WITHHELD_REASON;

    private static final String OBSERVED_RETURN_CODES = "0, 4, 8, 12, 16";

    private static final String ABEND_BODY = "{\"code\":\"" + CobolErrorHandler.ABEND_CODE
            + "\",\"error\":\"" + HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase()
            + "\",\"detail\":\"" + CobolErrorHandler.ABEND_MESSAGE + "\",\"fieldErrors\":[]}";

    private static ObjectMapper customizedMapper() {
        final Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        customizer().customize(builder);
        return builder.build();
    }

    private static Jackson2ObjectMapperBuilderCustomizer customizer() {
        return new WebConfig().carddemoJacksonCustomizer(TEST_PROFILE_CHARSET);
    }

    private static ApplicationContextRunner sliceRunner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(WebConfig.class)
                .withBean(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME, Charset.class,
                        () -> TEST_PROFILE_CHARSET)
                .withPropertyValues(
                        "spring.profiles.active=",
                        "carddemo.job-submission.queue-name=JOBS",
                        "carddemo.job-submission.dd-name=INREADER",
                        "carddemo.job-submission.charset=US-ASCII",
                        "carddemo.job-submission.record-length=80",
                        "carddemo.job-submission.record-format=FIXED",
                        "carddemo.job-submission.block-format=UNBLOCKED",
                        "carddemo.job-submission.disposition=MOD",
                        "carddemo.job-submission.approved-root=/var/carddemo",
                        "carddemo.job-submission.destination=/var/carddemo/inreader/JOBS");
    }

    private static MockMvc adviceDispatcher() {
        final LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return MockMvcBuilders.standaloneSetup(new ScreenFixtureController())
                .setControllerAdvice(new CobolErrorHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(customizedMapper()))
                .setValidator(validator)
                .build();
    }

    /** The typed-path-variable fixture route, which answers 200 with a plain body. */
    private static final String FIXTURE_ACCOUNT = ScreenFixtureController.BASE + "/accounts/42";

    /** The screen-payload fixture route, which answers 400 when the body cannot be read. */
    private static final String FIXTURE_SCREEN = ScreenFixtureController.BASE + "/screen";

    /**
     * {@link #adviceDispatcher()} with the response-header contributor in front of it.
     *
     * <p>A standalone dispatcher installs no filters of its own, which is exactly what makes this the
     * right harness: the headers can only appear because the contributor put them there, so the
     * assertion cannot pass on something the framework would have done anyway.
     *
     * @return the configured dispatcher
     */
    private static MockMvc headerDispatcher() {
        final LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return MockMvcBuilders.standaloneSetup(new ScreenFixtureController())
                .setControllerAdvice(new CobolErrorHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(customizedMapper()))
                .setValidator(validator)
                .addFilters(new WebConfig().carddemoScreenResponseHeaders())
                .build();
    }

    /**
     * Loads a shipped configuration document from the classpath so its keys can be asserted as
     * written.
     *
     * <p>Three of {@link WebConfig}'s stated JSON decisions live in {@code application.yml} rather
     * than in the customizer - the inclusion policy, the unknown-property strictness, and the
     * deliberate absence of a naming strategy - so a test of the JSON contract that read only the
     * customizer would miss them. Reading the document itself is also the only way to assert an
     * <em>absent</em> key, which is what several of the negative assertions below turn on.
     *
     * @param name the resource name, for example {@code application.yml}
     * @return the loaded property source
     * @throws IOException if the document cannot be read, which would itself be a defect
     */
    private static PropertySource<?> shippedDocument(final String name) throws IOException {
        final List<PropertySource<?>> loaded =
                new YamlPropertySourceLoader().load(name, new ClassPathResource(name));
        assertThat(loaded)
                .as("%s must be present on the classpath and must parse as YAML", name)
                .isNotEmpty();
        return loaded.get(0);
    }

    @Nested
    @DisplayName("JSON naming is untransformed, so every payload field still traces to a DFHMDF")
    class JsonNamingIsUntransformed {
        @Test
        @DisplayName("upper-case BMS labels serialise character for character")
        void bmsLabelsSurviveSerialisationVerbatim() throws IOException {
            final String json = customizedMapper().writeValueAsString(new ScreenPayload(
                    "CC00", "07/19/22", "COSGN00C", "", "00000000011", new BigDecimal("100.00")));

            assertThat(json).isEqualTo("{\"TRNNAME\":\"CC00\",\"CURDATE\":\"07/19/22\","
                    + "\"PGMNAME\":\"COSGN00C\",\"ERRMSG\":\"\",\"ACCTSID\":\"00000000011\","
                    + "\"TAMT001\":100.00}");
        }

        @ParameterizedTest(name = "{0} is emitted as {0}, not rewritten")
        @ValueSource(strings = {"TRNNAME", "CURDATE", "PGMNAME", "ERRMSG", "ACCTSID"})
        @DisplayName("each named DFHMDF label appears as its own key")
        void eachLabelAppearsUnchanged(final String label) throws IOException {
            final String json = customizedMapper().writeValueAsString(new ScreenPayload(
                    "CC00", "07/19/22", "COSGN00C", "", "00000000011", BigDecimal.ZERO));

            assertThat(json).contains("\"" + label + "\":");
        }

        @ParameterizedTest(name = "no key is rewritten to {0}")
        @ValueSource(strings = {"trnName", "trnname", "trn_name", "trn-name", "TRN_NAME",
            "curDate", "cur_date", "errMsg", "err_msg", "pgmName", "acctsId", "accts_id"})
        @DisplayName("no camel, snake, kebab or lower-cased form of a label is ever produced")
        void noRewrittenFormIsProduced(final String rewritten) throws IOException {
            final String json = customizedMapper().writeValueAsString(new ScreenPayload(
                    "CC00", "07/19/22", "COSGN00C", "", "00000000011", BigDecimal.ZERO));

            assertThat(json).doesNotContain("\"" + rewritten + "\"");
        }

        @Test
        @DisplayName("a payload round-trips with every value intact")
        void everyValueRoundTrips() throws IOException {
            final ObjectMapper mapper = customizedMapper();
            final ScreenPayload sent = new ScreenPayload("CC00", "07/19/22", "COSGN00C",
                    "Account not found...", "00000000011", new BigDecimal("1234.56"));

            final ScreenPayload received =
                    mapper.readValue(mapper.writeValueAsString(sent), ScreenPayload.class);

            assertThat(received).isEqualTo(sent);
        }

        @Test
        @DisplayName("deserialisation accepts the upper-case keys a controller will actually receive")
        void upperCaseKeysDeserialise() throws IOException {
            final ScreenPayload received = customizedMapper().readValue(
                    "{\"TRNNAME\":\"CC00\",\"CURDATE\":\"07/19/22\",\"PGMNAME\":\"COSGN00C\","
                            + "\"ERRMSG\":\"\",\"ACCTSID\":\"00000000011\",\"TAMT001\":0.00}",
                    ScreenPayload.class);

            assertThat(received.TRNNAME()).isEqualTo("CC00");
            assertThat(received.CURDATE()).isEqualTo("07/19/22");
            assertThat(received.PGMNAME()).isEqualTo("COSGN00C");
            assertThat(received.ACCTSID()).isEqualTo("00000000011");
        }

        @Test
        @DisplayName("the naming strategy is absent by intent on both directions, not merely by luck")
        void noNamingStrategyIsInstalledOnEitherDirection() {
            final ObjectMapper mapper = customizedMapper();

            assertThat(mapper.getSerializationConfig().getPropertyNamingStrategy())
                    .as("a serialisation naming strategy would rewrite all 441 field identifiers")
                    .isNull();
            assertThat(mapper.getDeserializationConfig().getPropertyNamingStrategy())
                    .as("a deserialisation naming strategy would stop accepting the mapset's own "
                            + "field names")
                    .isNull();
        }

        @Test
        @DisplayName("the shipped document declares no property-naming-strategy either")
        void theShippedDocumentInstallsNoNamingStrategy() throws IOException {
            final PropertySource<?> document = shippedDocument("application.yml");

            assertThat(document.getProperty("spring.jackson.property-naming-strategy")).isNull();
        }
    }

    @Nested
    @DisplayName("BigDecimal fidelity on the wire - no double, no exponent, no lost scale")
    class BigDecimalWireFidelity {
        @Test
        @DisplayName("USE_BIG_DECIMAL_FOR_FLOATS is enabled")
        void useBigDecimalForFloatsIsEnabled() {
            assertThat(customizedMapper().isEnabled(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS))
                    .as("without it Jackson materialises a fractional JSON number as a double, and a "
                            + "double cannot represent a decimal fraction exactly - the value would "
                            + "be corrupt before any business logic ran")
                    .isTrue();
        }

        @Test
        @DisplayName("an inbound JSON decimal binds as BigDecimal and never as Double")
        void inboundDecimalIsABigDecimal() throws IOException {
            final Map<?, ?> bound =
                    customizedMapper().readValue("{\"TAMT001\":123.45}", Map.class);

            assertThat(bound.get("TAMT001"))
                    .isInstanceOf(BigDecimal.class)
                    .isNotInstanceOf(Double.class)
                    .isEqualTo(new BigDecimal("123.45"));
        }

        @Test
        @DisplayName("WRITE_BIGDECIMAL_AS_PLAIN is enabled on the generator factory")
        void writeBigDecimalAsPlainIsEnabled() {
            assertThat(customizedMapper().getFactory()
                    .isEnabled(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN))
                    .as("scientific notation on the wire would make a monetary field unreadable to a "
                            + "fixed-width receiver")
                    .isTrue();
        }

        @ParameterizedTest(name = "{0} serialises as {1}, in plain notation")
        @CsvSource({
            "100.00, 100.00",
            "0.00, 0.00",
            "1E+2, 100",
            "1.00E+3, 1000",
            "-2500.75, -2500.75",
            "9999999999.99, 9999999999.99"
        })
        @DisplayName("no amount is ever emitted in scientific notation")
        void amountsAreEmittedInPlainNotation(final String amount, final String expected)
                throws IOException {
            final String json = customizedMapper().writeValueAsString(new BigDecimal(amount));

            assertThat(json).isEqualTo(expected);
            assertThat(json).doesNotContain("E").doesNotContain("e");
        }

        @Test
        @DisplayName("a scale-2 amount reads back at scale 2, because the scale is part of the value")
        void scaleTwoSurvivesTheRoundTrip() throws IOException {
            final ObjectMapper mapper = customizedMapper();
            final ScreenPayload sent = new ScreenPayload("CT02", "07/19/22", "COTRN02C", "",
                    "00000000011", new BigDecimal("100.00"));

            final String json = mapper.writeValueAsString(sent);
            final ScreenPayload received = mapper.readValue(json, ScreenPayload.class);

            assertThat(json).contains("\"TAMT001\":100.00");
            assertThat(received.TAMT001().scale()).isEqualTo(2);
            assertThat(received.TAMT001()).isEqualTo(new BigDecimal("100.00"));
            assertThat(received.TAMT001().toPlainString()).isEqualTo("100.00");
        }

        @ParameterizedTest(name = "scale {1} arrives and leaves unchanged for {0}")
        @CsvSource({"1.5, 1", "1.50, 2", "1.500, 3", "1.5000, 4", "12, 0"})
        @DisplayName("no rounding mode is applied at the JSON boundary")
        void noRoundingHappensAtTheJsonBoundary(final String literal, final int scale)
                throws IOException {
            final ObjectMapper mapper = customizedMapper();

            final BigDecimal received = mapper.readValue(literal, BigDecimal.class);

            assertThat(received.scale()).isEqualTo(scale);
            assertThat(mapper.writeValueAsString(received)).isEqualTo(literal);
        }

        @Test
        @DisplayName("a negative amount keeps its sign and its scale")
        void negativeAmountsKeepSignAndScale() throws IOException {
            final ObjectMapper mapper = customizedMapper();

            final BigDecimal received = mapper.readValue("-0.01", BigDecimal.class);

            assertThat(received).isEqualTo(new BigDecimal("-0.01"));
            assertThat(received.scale()).isEqualTo(2);
            assertThat(mapper.writeValueAsString(received)).isEqualTo("-0.01");
        }
    }

    @Nested
    @DisplayName("One member states one value, and a screen field is character data")
    class StrictInboundShape {
        @Test
        @DisplayName("STRICT_DUPLICATE_DETECTION is enabled on the parser factory")
        void strictDuplicateDetectionIsEnabled() {
            assertThat(customizedMapper().getFactory()
                    .isEnabled(JsonParser.Feature.STRICT_DUPLICATE_DETECTION))
                    .as("the resolved Jackson default is DISABLED, so without this a repeated key, "
                            + "password, attention identifier or navigation member silently discards "
                            + "one of the two values the caller stated")
                    .isTrue();
        }

        @ParameterizedTest(name = "duplicate member in {0}")
        @ValueSource(strings = {
            "{\"TRNNAME\":\"CT02\",\"TRNNAME\":\"CT01\"}",
            "{\"TRNNAME\":\"CT02\",\"CURDATE\":\"07/19/22\",\"CURDATE\":\"07/20/22\"}"
        })
        @DisplayName("a repeated member is refused rather than resolved last-wins")
        void aRepeatedMemberIsRefused(final String body) {
            assertThatExceptionOfType(IOException.class)
                    .isThrownBy(() -> customizedMapper().readValue(body, ScreenPayload.class))
                    .withMessageContaining("Duplicate");
        }

        @Test
        @DisplayName("the same document with each member stated once binds, so the refusal above is "
                + "attributable to the repetition alone")
        void oneOccurrenceEachStillBinds() throws IOException {
            final ScreenPayload bound = customizedMapper()
                    .readValue("{\"TRNNAME\":\"CT02\",\"CURDATE\":\"07/19/22\"}",
                            ScreenPayload.class);

            assertThat(bound.TRNNAME()).isEqualTo("CT02");
            assertThat(bound.CURDATE()).isEqualTo("07/19/22");
        }

        @ParameterizedTest(name = "a screen field sent as {0}")
        @ValueSource(strings = {"11", "1.5", "true"})
        @DisplayName("a member that is not JSON character data is refused, not coerced into a field image")
        void aNonStringScreenFieldIsRefused(final String token) {
            assertThatExceptionOfType(IOException.class)
                    .isThrownBy(() -> customizedMapper()
                            .readValue("{\"TRNNAME\":" + token + "}", ScreenPayload.class))
                    .withRootCauseInstanceOf(ScreenInputRejectedException.class);
        }

        @ParameterizedTest(name = "a screen field sent as {0}")
        @ValueSource(strings = {"{}", "[]"})
        @DisplayName("a structured member is refused through Jackson's own unexpected-token handling, "
                + "which names the member and the token rather than binding null")
        void aStructuredScreenFieldIsRefused(final String token) {
            assertThatExceptionOfType(MismatchedInputException.class)
                    .isThrownBy(() -> customizedMapper()
                            .readValue("{\"TRNNAME\":" + token + "}", ScreenPayload.class))
                    .satisfies(failure -> assertThat(failure.getMessage()).contains("java.lang.String"));
        }

        @Test
        @DisplayName("the screen judgement is applied with the code page the deployment states, so an "
                + "EBCDIC deployment and an ASCII one judge the same value differently and correctly")
        void theCodePageIsTheDeploymentsOwn() throws IOException {
            final Jackson2ObjectMapperBuilder ebcdicBuilder = new Jackson2ObjectMapperBuilder();
            new WebConfig().carddemoJacksonCustomizer(Charset.forName("IBM037"))
                    .customize(ebcdicBuilder);

            assertThat(ebcdicBuilder.build().readValue("\"SM\u00d1TH\"", String.class))
                    .isEqualTo("SM\u00d1TH");
            assertThatExceptionOfType(ScreenInputRejectedException.class)
                    .isThrownBy(() -> customizedMapper().readValue("\"SM\u00d1TH\"", String.class));
        }

        @Test
        @DisplayName("a code page is required rather than defaulted, so no deployment can silently "
                + "judge screen text against the platform default")
        void aCodePageIsRequired() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new WebConfig().carddemoJacksonCustomizer(null)
                            .customize(new Jackson2ObjectMapperBuilder()))
                    .withMessageContaining("code page");
        }
    }

    @Nested
    @DisplayName("Space padding, empty strings and nulls survive the wire untouched")
    class SpacePaddingAndNullFidelity {
        @Test
        @DisplayName("a space-padded PIC X value round-trips with its trailing spaces intact")
        void trailingSpacesAreNotTrimmed() throws IOException {
            final ObjectMapper mapper = customizedMapper();
            final String padded = "BOB     ";

            final ScreenPayload received = mapper.readValue(
                    "{\"TRNNAME\":\"CC00\",\"CURDATE\":\"07/19/22\",\"PGMNAME\":\"COSGN00C\","
                            + "\"ERRMSG\":\"\",\"ACCTSID\":\"" + padded + "\",\"TAMT001\":0.00}",
                    ScreenPayload.class);

            assertThat(received.ACCTSID()).isEqualTo(padded).hasSize(8);
            assertThat(mapper.writeValueAsString(received))
                    .contains("\"ACCTSID\":\"" + padded + "\"");
        }

        @Test
        @DisplayName("an all-spaces field is carried as spaces, not as an empty value")
        void anAllSpacesFieldIsPreserved() throws IOException {
            final ObjectMapper mapper = customizedMapper();
            final String spaces = "        ";

            final ScreenPayload received = mapper.readValue(
                    "{\"TRNNAME\":\"CC00\",\"CURDATE\":\"" + spaces + "\",\"PGMNAME\":\"COSGN00C\","
                            + "\"ERRMSG\":\"\",\"ACCTSID\":\"00000000011\",\"TAMT001\":0.00}",
                    ScreenPayload.class);

            assertThat(received.CURDATE()).isEqualTo(spaces).hasSize(8);
            assertThat(mapper.writeValueAsString(received))
                    .contains("\"CURDATE\":\"" + spaces + "\"");
        }

        @Test
        @DisplayName("ACCEPT_EMPTY_STRING_AS_NULL_OBJECT is disabled")
        void acceptEmptyStringAsNullObjectIsDisabled() {
            assertThat(customizedMapper()
                    .isEnabled(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT))
                    .as("an empty or all-spaces screen field is meaningful data; coercing it to null "
                            + "would discard a real DFHMDF field")
                    .isFalse();
        }

        @Test
        @DisplayName("an empty string stays an empty string on a character field")
        void anEmptyStringIsNotCoercedToNull() throws IOException {
            final ScreenPayload received = customizedMapper().readValue(
                    "{\"TRNNAME\":\"CC00\",\"CURDATE\":\"07/19/22\",\"PGMNAME\":\"COSGN00C\","
                            + "\"ERRMSG\":\"\",\"ACCTSID\":\"00000000011\",\"TAMT001\":0.00}",
                    ScreenPayload.class);

            assertThat(received.ERRMSG()).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("an empty string offered for an object is refused rather than becoming null")
        void anEmptyStringForAnObjectIsRefused() {
            final ObjectMapper mapper = customizedMapper();

            assertThatExceptionOfType(MismatchedInputException.class).isThrownBy(
                    () -> mapper.readValue("{\"nested\":\"\"}", NestingPayload.class));
        }

        @Test
        @DisplayName("a null field is emitted rather than omitted, so no screen field disappears")
        void nullFieldsAreEmitted() throws IOException {
            final String json = customizedMapper().writeValueAsString(new ScreenPayload(
                    "CC00", null, null, null, null, null));

            assertThat(json).isEqualTo("{\"TRNNAME\":\"CC00\",\"CURDATE\":null,\"PGMNAME\":null,"
                    + "\"ERRMSG\":null,\"ACCTSID\":null,\"TAMT001\":null}");
        }

        @Test
        @DisplayName("the shipped document sets default-property-inclusion to always")
        void theShippedDocumentIncludesEveryProperty() throws IOException {
            final PropertySource<?> document = shippedDocument("application.yml");

            assertThat(document.getProperty("spring.jackson.default-property-inclusion"))
                    .isEqualTo("always");
        }

        @Test
        @DisplayName("JSON reaches the wire as UTF-8")
        void jsonIsWrittenAsUtf8() throws IOException {
            final MappingJackson2HttpMessageConverter converter =
                    new MappingJackson2HttpMessageConverter(customizedMapper());
            final MockHttpOutputMessage response = new MockHttpOutputMessage();
            final String beyondAscii = "CAF\u00c9 \u00a3 \u20ac";

            converter.write(new ScreenPayload("CC00", "07/19/22", "COSGN00C", beyondAscii,
                    "00000000011", new BigDecimal("100.00")), MediaType.APPLICATION_JSON, response);
            final byte[] written = response.getBodyAsBytes();

            assertThat(new String(written, StandardCharsets.UTF_8)).contains(beyondAscii);
            assertThat(written).isEqualTo(
                    new String(written, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8));
            assertThat(new String(written, StandardCharsets.US_ASCII))
                    .as("a US-ASCII reading must differ, which is what proves the bytes are UTF-8 "
                            + "rather than merely ASCII-safe")
                    .doesNotContain(beyondAscii);
            assertThat(response.getHeaders().getContentType())
                    .isEqualTo(MediaType.APPLICATION_JSON);
        }

        @Test
        @DisplayName("the shipped document overrides no HTTP encoding, leaving the UTF-8 default")
        void theShippedDocumentNamesNoHttpCharset() throws IOException {
            final PropertySource<?> document = shippedDocument("application.yml");

            assertThat(document.getProperty("server.servlet.encoding.charset")).isNull();
            assertThat(document.getProperty("server.servlet.encoding.force")).isNull();
            assertThat(document.getProperty("spring.mandatory-file-encoding")).isNull();
        }
    }

    @Nested
    @DisplayName("An abend answers 500 with one invariant body across every RETURN-CODE")
    class AbendMappingOverHttp {
        @ParameterizedTest(name = "RETURN-CODE={0} answers 500 with the invariant abend body")
        @ValueSource(ints = {0, 4, 8, 12, 16})
        @DisplayName("every observed RETURN-CODE produces the same status and the same body")
        void everyObservedReturnCodeIsAnsweredIdentically(final int returnCode) throws Exception {
            adviceDispatcher().perform(get("/webconfig-fixture/abend/" + returnCode))
                    .andExpect(status().isInternalServerError())
                    .andExpect(content().string(ABEND_BODY));
        }

        @ParameterizedTest(name = "RETURN-CODE={0} leaks neither the program nor the reason")
        @ValueSource(ints = {0, 4, 8, 12, 16})
        @DisplayName("no return code, program name or reason text reaches the body")
        void nothingInternalReachesTheBody(final int returnCode) throws Exception {
            final String body = adviceDispatcher()
                    .perform(get("/webconfig-fixture/abend/" + returnCode))
                    .andReturn().getResponse().getContentAsString();

            assertThat(body)
                    .doesNotContain(WITHHELD_PROGRAM)
                    .doesNotContain(WITHHELD_REASON)
                    .doesNotContain(AbendException.ABEND_DISPLAY_TEXT)
                    .doesNotContain("RETURN-CODE")
                    .doesNotContain("ABCODE")
                    .doesNotContain("TIMING")
                    .doesNotContain(AbendException.class.getName());
        }

        @Test
        @DisplayName("the body is byte-identical to the two declared constants and carries nothing else")
        void theBodyIsExactlyTheTwoConstants() throws Exception {
            final String body = adviceDispatcher().perform(get("/webconfig-fixture/abend/12"))
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).isEqualTo(ABEND_BODY);
            assertThat(body.chars().filter(character -> character == ':').count()).isEqualTo(4);
        }

        @Test
        @DisplayName("an abend carrying ABCODE and TIMING is answered like one carrying neither")
        void theAbendParameterBranchesAreIndistinguishableToACaller() throws Exception {
            final String withParameters = adviceDispatcher()
                    .perform(get("/webconfig-fixture/abend/12"))
                    .andReturn().getResponse().getContentAsString();
            final String withoutParameters = adviceDispatcher()
                    .perform(get("/webconfig-fixture/abend-bare/12"))
                    .andReturn().getResponse().getContentAsString();

            assertThat(withoutParameters).isEqualTo(withParameters);
        }

        @Test
        @DisplayName("the bare-parameter site is answered 500 as well")
        void theBareAbendSiteIsAlsoInternalServerError() throws Exception {
            adviceDispatcher().perform(get("/webconfig-fixture/abend-bare/16"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value(CobolErrorHandler.ABEND_CODE))
                    .andExpect(jsonPath("$.detail").value(CobolErrorHandler.ABEND_MESSAGE));
        }

        @Test
        @DisplayName("the withheld detail is still on the exception, for the server's own diagnostics")
        void theDetailRemainsAvailableServerSide() {
            final AbendException abend =
                    AbendException.standard(WITHHELD_PROGRAM, 12, WITHHELD_REASON);

            assertThat(abend.getProgram()).isEqualTo(WITHHELD_PROGRAM);
            assertThat(abend.getReturnCode()).isEqualTo(12);
            assertThat(abend.getReason()).contains(WITHHELD_REASON);
            assertThat(abend.getMessage()).isEqualTo(AbendException.ABEND_DISPLAY_TEXT + " "
                    + WITHHELD_PROGRAM + " RETURN-CODE=12 ABCODE="
                    + AbendException.STANDARD_ABEND_CODE + " TIMING="
                    + AbendException.STANDARD_TIMING + " - " + WITHHELD_REASON);
        }

        @Test
        @DisplayName("the five observed return codes are the ones AbendException declares")
        void theDrivenReturnCodesAreTheDeclaredOnes() {
            assertThat(List.of(AbendException.RETURN_CODE_OK, AbendException.RETURN_CODE_WARNING,
                    AbendException.RETURN_CODE_ASSUMED_FAILURE, AbendException.RETURN_CODE_IO_ERROR,
                    AbendException.RETURN_CODE_END_OF_FILE))
                    .containsExactly(0, 4, 8, 12, 16);
            assertThat(OBSERVED_RETURN_CODES).isEqualTo("0, 4, 8, 12, 16");
        }
    }

    @Nested
    @DisplayName("A validation failure names the field and the violation, and echoes no value")
    class ValidationFailuresOverHttp {
        @Test
        @DisplayName("a width breach of an xxxI PIC X(8) field answers 400 naming that field")
        void aWidthBreachNamesTheField() throws Exception {
            adviceDispatcher().perform(post("/webconfig-fixture/signon")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"USERID\":\"NINECHARS\",\"PASSWD\":\"PASS\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(HttpStatus.BAD_REQUEST.getReasonPhrase()))
                    .andExpect(jsonPath("$.fieldErrors.length()").value(1))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("USERID"))
                    .andExpect(jsonPath("$.fieldErrors[0].message")
                            .value("must be at most 8 characters"));
        }

        @Test
        @DisplayName("two rejected fields are reported in field-name order, so the body is deterministic")
        void severalRejectedFieldsAreOrdered() throws Exception {
            adviceDispatcher().perform(post("/webconfig-fixture/signon")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"USERID\":\"NINECHARS\",\"PASSWD\":\"TOOLONGPASSWORD\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors.length()").value(2))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("PASSWD"))
                    .andExpect(jsonPath("$.fieldErrors[1].field").value("USERID"));
        }

        @Test
        @DisplayName("the rejected value itself is never echoed")
        void theRejectedValueIsNotEchoed() throws Exception {
            final String overWide = "SUPERUSERNAME";

            final String body = adviceDispatcher().perform(post("/webconfig-fixture/signon")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"USERID\":\"" + overWide + "\",\"PASSWD\":\"PASS\"}"))
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).doesNotContain(overWide);
        }

        @Test
        @DisplayName("a password is never echoed, even though it is compared in plaintext")
        void thePasswordIsNotEchoed() throws Exception {
            final String secret = "PLAINTEXTPASSWORD";

            final String body = adviceDispatcher().perform(post("/webconfig-fixture/signon")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"USERID\":\"USER0001\",\"PASSWD\":\"" + secret + "\"}"))
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).doesNotContain(secret);
        }

        @Test
        @DisplayName("a valid payload passes the validator untouched")
        void aValidPayloadIsAccepted() throws Exception {
            adviceDispatcher().perform(post("/webconfig-fixture/signon")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"USERID\":\"USER0001\",\"PASSWD\":\"PASSWORD\"}"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("\"USER0001\""));
        }

        @Test
        @DisplayName("a constraint violation raised outside body binding answers 400 in the same shape")
        void aConstraintViolationOutsideBindingIsAnsweredIdentically() throws Exception {
            adviceDispatcher().perform(get("/webconfig-fixture/violation"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(HttpStatus.BAD_REQUEST.getReasonPhrase()))
                    .andExpect(jsonPath("$.fieldErrors").isArray())
                    .andExpect(jsonPath("$.fieldErrors.length()").value(0));
        }

        @Test
        @DisplayName("a malformed body answers 400 with the stable malformed-request pair")
        void aMalformedBodyIsAnsweredWithTheStablePair() throws Exception {
            adviceDispatcher().perform(post("/webconfig-fixture/screen")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"TRNNAME\":"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code")
                            .value(CobolErrorHandler.MALFORMED_REQUEST_CODE))
                    .andExpect(jsonPath("$.detail")
                            .value(CobolErrorHandler.MALFORMED_REQUEST_MESSAGE));
        }
    }

    @Nested
    @DisplayName("Handler selection is deterministic, and the catch-all preserves the status")
    class HandlerSelectionIsDeterministic {
        @Test
        @DisplayName("a value a domain guard rejected answers 400, not the catch-all's 500")
        void aRejectedValueStaysABadRequest() throws Exception {
            adviceDispatcher().perform(get("/webconfig-fixture/rejected-value"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(CobolErrorHandler.REJECTED_VALUE_CODE))
                    .andExpect(jsonPath("$.error").value(HttpStatus.BAD_REQUEST.getReasonPhrase()))
                    .andExpect(jsonPath("$.detail").exists())
                    .andExpect(jsonPath("$.fieldErrors").isArray())
                    .andExpect(jsonPath("$.status").doesNotExist());
        }

        @Test
        @DisplayName("a server-side state fault answers 500 and names no dataset")
        void aStateFaultIsAnInternalServerError() throws Exception {
            final String body = adviceDispatcher().perform(get("/webconfig-fixture/state-fault"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.error")
                            .value(HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase()))
                    .andReturn().getResponse().getContentAsString();

            assertThat(body)
                    .doesNotContain("AWS.M2.CARDDEMO")
                    .doesNotContain("carddemo.datasets");
        }

        @Test
        @DisplayName("a dataset access failure answers 500 and names no SQL, dataset or driver detail")
        void aDatasetFailureIsAnInternalServerError() throws Exception {
            final String body = adviceDispatcher().perform(get("/webconfig-fixture/dataset-failure"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value(CobolErrorHandler.DATASET_ACCESS_CODE))
                    .andReturn().getResponse().getContentAsString();

            assertThat(body)
                    .isEqualTo("{\"code\":\"" + CobolErrorHandler.DATASET_ACCESS_CODE
                            + "\",\"error\":\""
                            + HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase()
                            + "\",\"detail\":\"" + CobolErrorHandler.DATASET_ACCESS_MESSAGE
                            + "\",\"fieldErrors\":[]}")
                    .doesNotContain("AWS.M2.CARDDEMO")
                    .doesNotContain("connection");
        }

        @Test
        @DisplayName("a conversion failure names the parameter and no Java type")
        void aConversionFailureNamesTheParameterAndNoJavaType() throws Exception {
            final String body = adviceDispatcher()
                    .perform(get("/webconfig-fixture/accounts/not-a-number"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(CobolErrorHandler.TYPE_MISMATCH_CODE))
                    .andExpect(jsonPath("$.detail").value(CobolErrorHandler.TYPE_MISMATCH_MESSAGE))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("accountId"))
                    .andReturn().getResponse().getContentAsString();

            assertThat(body)
                    .doesNotContain("long")
                    .doesNotContain("Long")
                    .doesNotContain("not-a-number");
        }

        @Test
        @DisplayName("a conversion failure does not echo the value that failed to convert")
        void aConversionFailureDoesNotEchoTheValue() throws Exception {
            final String body = adviceDispatcher()
                    .perform(get("/webconfig-fixture/accounts/00000000011X"))
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).doesNotContain("00000000011X");
        }

        @Test
        @DisplayName("a convertible path variable is still served normally")
        void aConvertiblePathVariableIsServed() throws Exception {
            adviceDispatcher().perform(get("/webconfig-fixture/accounts/11"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("\"11\""));
        }

        @Test
        @DisplayName("an unclaimed failure answers 500 with the one fixed sentence")
        void anUnclaimedFailureAnswersFiveHundred() throws Exception {
            final String body = adviceDispatcher().perform(get("/webconfig-fixture/unclaimed"))
                    .andExpect(status().isInternalServerError())
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).isEqualTo("{\"code\":\""
                    + CobolErrorHandler.REQUEST_NOT_COMPLETED_CODE + "\",\"error\":\""
                    + HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase() + "\",\"detail\":\""
                    + CobolErrorHandler.UNEXPECTED_FAILURE_MESSAGE + "\",\"fieldErrors\":[]}");
        }

        @Test
        @DisplayName("an unknown path keeps its 404 rather than becoming a server error")
        void anUnknownPathKeepsItsStatus() throws Exception {
            adviceDispatcher().perform(get("/webconfig-fixture/no-such-screen"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code")
                            .value(CobolErrorHandler.REQUEST_NOT_COMPLETED_CODE))
                    .andExpect(jsonPath("$.error").value(HttpStatus.NOT_FOUND.getReasonPhrase()))
                    .andExpect(jsonPath("$.detail")
                            .value(CobolErrorHandler.UNEXPECTED_FAILURE_MESSAGE));
        }

        @Test
        @DisplayName("a wrong method keeps its 405")
        void aWrongMethodKeepsItsStatus() throws Exception {
            adviceDispatcher().perform(post("/webconfig-fixture/rejected-value")
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(jsonPath("$.code")
                            .value(CobolErrorHandler.REQUEST_NOT_COMPLETED_CODE))
                    .andExpect(jsonPath("$.error")
                            .value(HttpStatus.METHOD_NOT_ALLOWED.getReasonPhrase()));
        }

        @Test
        @DisplayName("an unsupported content type keeps its 415")
        void anUnsupportedMediaTypeKeepsItsStatus() throws Exception {
            adviceDispatcher().perform(post("/webconfig-fixture/signon")
                            .contentType(MediaType.TEXT_PLAIN).content("USER0001"))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.code")
                            .value(CobolErrorHandler.REQUEST_NOT_COMPLETED_CODE))
                    .andExpect(jsonPath("$.error")
                            .value(HttpStatus.UNSUPPORTED_MEDIA_TYPE.getReasonPhrase()));
        }

        @ParameterizedTest(name = "no body from {0} carries a stack frame or a class name")
        @ValueSource(strings = {
            "/webconfig-fixture/abend/12",
            "/webconfig-fixture/rejected-value",
            "/webconfig-fixture/state-fault",
            "/webconfig-fixture/dataset-failure",
            "/webconfig-fixture/violation",
            "/webconfig-fixture/unclaimed",
            "/webconfig-fixture/no-such-screen",
            "/webconfig-fixture/accounts/not-a-number"
        })
        @DisplayName("no failure body leaks a stack trace, a class name or a package name")
        void noFailureBodyLeaksInternals(final String path) throws Exception {
            final String body = adviceDispatcher().perform(get(path))
                    .andReturn().getResponse().getContentAsString();

            assertThat(body)
                    .doesNotContain("java.lang.")
                    .doesNotContain("java.util.")
                    .doesNotContain("org.springframework.")
                    .doesNotContain("com.vsergeychik.")
                    .doesNotContain("Exception")
                    .doesNotContain("\tat ")
                    .doesNotContain("trace");
        }

        @ParameterizedTest(name = "the body from {0} carries no timestamp and no request path")
        @ValueSource(strings = {
            "/webconfig-fixture/abend/12",
            "/webconfig-fixture/unclaimed",
            "/webconfig-fixture/state-fault"
        })
        @DisplayName("no failure body carries a timestamp or a path, which would be non-deterministic")
        void noFailureBodyCarriesATimestampOrPath(final String path) throws Exception {
            final String body = adviceDispatcher().perform(get(path))
                    .andReturn().getResponse().getContentAsString();

            assertThat(body)
                    .doesNotContain("timestamp")
                    .doesNotContain("\"path\"")
                    .doesNotContain("webconfig-fixture");
        }
    }

    @Nested
    @DisplayName("The container's error path answers this API's one JSON envelope")
    class TheErrorEndpoint {

        /** The route under test, on the framework's default error path; it holds no mutable state. */
        private final WebConfig.CobolErrorRoute route = new WebConfig.CobolErrorRoute("/error");

        @Test
        @DisplayName("a forwarded failure keeps the status the container recorded")
        void aForwardedFailureKeepsItsStatus() {
            final MockHttpServletRequest forwarded = new MockHttpServletRequest();
            forwarded.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 406);

            final ServerResponse answer = route.render(forwarded);
            final CobolErrorHandler.CobolErrorResponse body =
                    WebConfig.CobolErrorRoute.bodyFor(HttpStatus.NOT_ACCEPTABLE);

            assertThat(answer.statusCode()).isEqualTo(HttpStatus.NOT_ACCEPTABLE);
            assertThat(answer.headers().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
            assertThat(body.code()).isEqualTo(CobolErrorHandler.REQUEST_NOT_COMPLETED_CODE);
            assertThat(body.error()).isEqualTo(HttpStatus.NOT_ACCEPTABLE.getReasonPhrase());
            assertThat(body.detail()).isEqualTo(CobolErrorHandler.UNEXPECTED_FAILURE_MESSAGE);
            assertThat(body.fieldErrors()).isEmpty();
            assertThat(body.abendData()).isNull();
        }

        @Test
        @DisplayName("the body carries no timestamp, no path and no invented status member")
        void theBodyCarriesNothingFromTheRequest() throws Exception {
            final String rendered = customizedMapper()
                    .writeValueAsString(WebConfig.CobolErrorRoute.bodyFor(HttpStatus.NOT_FOUND));

            assertThat(rendered).isEqualTo("{\"code\":\""
                    + CobolErrorHandler.REQUEST_NOT_COMPLETED_CODE + "\",\"error\":\""
                    + HttpStatus.NOT_FOUND.getReasonPhrase() + "\",\"detail\":\""
                    + CobolErrorHandler.UNEXPECTED_FAILURE_MESSAGE + "\",\"fieldErrors\":[]}");
            assertThat(rendered).doesNotContain("no-such-screen");
        }

        @ParameterizedTest(name = "a request whose recorded status is {0} answers 500")
        @DisplayName("a request that was not forwarded by a failure invents no failure status")
        @ValueSource(strings = {"absent", "not-an-integer", "999"})
        void aRequestWithNoUsableStatusAnswersFiveHundred(final String recorded) {
            final MockHttpServletRequest request = new MockHttpServletRequest();
            if ("not-an-integer".equals(recorded)) {
                request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, "404");
            } else if ("999".equals(recorded)) {
                request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 999);
            }

            assertThat(WebConfig.CobolErrorRoute.statusOf(request))
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(route.render(request).statusCode())
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        @DisplayName("it refuses to render without a request rather than inventing one")
        void itRefusesToRenderWithoutARequest() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> route.render((HttpServletRequest) null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> route.render((ServerRequest) null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> route.route(null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> WebConfig.CobolErrorRoute.statusOf(null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> WebConfig.CobolErrorRoute.bodyFor(null));
        }

        @Test
        @DisplayName("it is an ErrorController - which suppresses Boot's whitelabel page - and is NOT "
                + "a controller, which is what keeps the online surface at seventeen")
        void itIsAnErrorControllerButNotAController() {
            // BasicErrorController is registered @ConditionalOnMissingBean(ErrorController.class), so
            // implementing the interface is not decoration: it is the mechanism that replaces Boot's
            // HTML-producing mapping with this JSON-only one. ErrorController is a marker with no
            // methods in Boot 3, which is exactly why a non-controller can carry it.
            assertThat(org.springframework.boot.web.servlet.error.ErrorController.class)
                    .isAssignableFrom(WebConfig.CobolErrorRoute.class);

            // And the other half, which is the reason this is a RouterFunction at all: the
            // @RestController inventory is the seventeen translated CICS online programs and nothing
            // else (gate G3), so the error boundary must map its path without a controller stereotype.
            assertThat(WebConfig.CobolErrorRoute.class
                    .isAnnotationPresent(org.springframework.web.bind.annotation.RestController.class))
                    .isFalse();
            assertThat(WebConfig.CobolErrorRoute.class
                    .isAnnotationPresent(org.springframework.stereotype.Controller.class))
                    .isFalse();
            assertThat(org.springframework.web.servlet.function.RouterFunction.class)
                    .isAssignableFrom(WebConfig.CobolErrorRoute.class);
        }

        @Test
        @DisplayName("the route matches the configured error path and nothing else")
        void theRouteMatchesOnlyTheConfiguredPath() {
            assertThat(route.errorPath()).isEqualTo("/error");
            assertThat(route.route(serverRequestFor("/error"))).isPresent();
            assertThat(route.route(serverRequestFor("/api/accounts/00000000001"))).isEmpty();
            assertThat(route.route(serverRequestFor("/errors"))).isEmpty();

            // A deployment that relocates the error path relocates the route with it, because the path
            // is bound rather than written in Java.
            final WebConfig.CobolErrorRoute relocated = new WebConfig.CobolErrorRoute("/failure");
            assertThat(relocated.route(serverRequestFor("/failure"))).isPresent();
            assertThat(relocated.route(serverRequestFor("/error"))).isEmpty();
        }

        @Test
        @DisplayName("a route with no path is refused, because it would match nothing and hand the "
                + "dispatch back to the whitelabel page")
        void aRouteWithNoPathIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new WebConfig.CobolErrorRoute(null));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new WebConfig.CobolErrorRoute(" "))
                    .withMessageContaining("server.error.path");
        }

        /**
         * A {@link ServerRequest} over a mock servlet request, which is all the routing predicate reads.
         *
         * @param path the request path
         * @return the request
         */
        private ServerRequest serverRequestFor(final String path) {
            final MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            return ServerRequest.create(request, List.of(new MappingJackson2HttpMessageConverter()));
        }

        @Test
        @DisplayName("the whitelabel page is disabled in configuration as well as replaced in code")
        void theWhitelabelPageIsDisabledInConfigurationToo() throws IOException {
            final List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                    .load("application.yml", new ClassPathResource("application.yml"));

            assertThat(sources).isNotEmpty();
            assertThat(sources.get(0).getProperty("server.error.whitelabel.enabled"))
                    .isEqualTo(false);
        }
    }

    @Nested
    @DisplayName("The Clock bean - one of them, system-zoned, and the reason 17 screens are testable")
    class TheClockBean {
        @Test
        @DisplayName("the slice contributes exactly one Clock bean")
        void exactlyOneClockBeanIsContributed() {
            sliceRunner().run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBeanNamesForType(Clock.class))
                        .as("DateHeader requires a Clock and no other file in the module supplies "
                                + "one, so zero would fail the context and two would fail it as well "
                                + "- Spring Boot disables bean-definition overriding")
                        .containsExactly("clock");
                assertThat(context).hasSingleBean(Clock.class);
            });
        }

        @Test
        @DisplayName("the production clock is the system clock, never a frozen one")
        void theProductionClockIsTheSystemClock() {
            final Clock clock = new WebConfig().clock();

            assertThat(clock).isEqualTo(Clock.systemDefaultZone());
            assertThat(clock.getZone()).isEqualTo(ZoneId.systemDefault());
            assertThat(clock).isNotEqualTo(FIXED_CLOCK);
        }

        @Test
        @DisplayName("a Clock is immutable, so one shared instance serves every concurrent request")
        void theClockBeanIsSafeToShare() {
            final WebConfig config = new WebConfig();

            assertThat(config.clock()).isEqualTo(config.clock());
        }

        @Test
        @DisplayName("a fixed clock reads identically however many times it is read")
        void aFixedClockIsReproducible() {
            assertThat(FIXED_CLOCK.instant()).isEqualTo(FIXED_INSTANT);
            assertThat(FIXED_CLOCK.instant()).isEqualTo(FIXED_CLOCK.instant());
            assertThat(FIXED_CLOCK.getZone()).isEqualTo(ZoneOffset.UTC);
            assertThat(FIXED_CLOCK.millis()).isEqualTo(FIXED_CLOCK.millis());
        }

        @Test
        @DisplayName("a second Clock definition fails the context rather than quietly winning")
        void aSecondClockDefinitionFailsTheContext() {
            sliceRunner().withBean(Clock.class, () -> FIXED_CLOCK).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                        .isInstanceOf(BeanDefinitionOverrideException.class);
                assertThat(context.getStartupFailure()).hasMessageContaining("'clock'");
            });
        }

        @Test
        @DisplayName("a fixed clock is what a consumer receives when a test supplies one")
        void aFixedClockIsInjectableIntoAConsumer() {
            new ApplicationContextRunner()
                    .withBean(Clock.class, () -> FIXED_CLOCK)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        final Clock injected = context.getBean(Clock.class);

                        assertThat(injected.instant()).isEqualTo(FIXED_INSTANT);
                        assertThat(injected.instant()).isEqualTo(injected.instant());
                        assertThat(injected.getZone()).isEqualTo(ZoneOffset.UTC);
                    });
        }

        @Test
        @DisplayName("DateHeader takes its time source as a parameter rather than reading it itself")
        void dateHeaderTakesAnInjectedClock() {
            final List<Method> clockConsumers = Arrays.stream(DateHeader.class.getMethods())
                    .filter(method -> Modifier.isStatic(method.getModifiers()))
                    .filter(method -> Arrays.asList(method.getParameterTypes()).contains(Clock.class))
                    .toList();

            assertThat(clockConsumers)
                    .as("DateHeader must accept a Clock, because that is the only seam by which the "
                            + "17 date-bearing controllers become deterministic")
                    .isNotEmpty();
            assertThat(Arrays.stream(DateHeader.class.getMethods())
                    .filter(method -> Modifier.isStatic(method.getModifiers()))
                    .filter(method -> method.getReturnType() == DateHeader.class)
                    .filter(method -> method.getParameterCount() == 0)
                    .toList())
                    .as("a no-argument factory could only have read the wall clock internally")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("The negative contract - no EnableWebMvc, no ObjectMapper, no session, no security")
    class TheNegativeContract {
        @Test
        @DisplayName("WebConfig is not annotated with EnableWebMvc")
        void enableWebMvcIsAbsent() {
            assertThat(WebConfig.class.getAnnotation(EnableWebMvc.class)).isNull();
            assertThat(WebConfig.class.isAnnotationPresent(EnableWebMvc.class)).isFalse();
        }

        @Test
        @DisplayName("WebConfig implements WebMvcConfigurer, so it augments rather than replaces")
        void webMvcConfigurerIsImplemented() {
            assertThat(WebMvcConfigurer.class).isAssignableFrom(WebConfig.class);
            assertThat(new WebConfig()).isInstanceOf(WebMvcConfigurer.class);
        }

        @ParameterizedTest(name = "no {0} override is contributed")
        @ValueSource(strings = {"addViewControllers", "addResourceHandlers", "addCorsMappings",
            "addInterceptors", "configureViewResolvers", "configureContentNegotiation",
            "configureMessageConverters", "extendMessageConverters", "addFormatters",
            "configurePathMatch", "addArgumentResolvers"})
        @DisplayName("no WebMvcConfigurer callback is overridden - there is nothing for one to do")
        void noWebMvcConfigurerCallbackIsOverridden(final String callback) {
            assertThat(Arrays.stream(WebConfig.class.getDeclaredMethods())
                    .filter(method -> !method.isSynthetic())
                    .map(Method::getName)
                    .toList())
                    .doesNotContain(callback);
        }

        @Test
        @DisplayName("WebConfig declares only its six bean methods and one package-visible helper")
        void onlyTheSixBeanMethodsAreDeclared() {
            // Named rather than counted, so an added method has to be acknowledged here rather than
            // absorbed by a threshold - a bean added here is a change to the web boundary.
            // noStoreOnCredentialScreens is one of the six, added so the three routes that accept a
            // password say so at the transport level as well as in their bodies;
            // carddemoScreenResponseHeaders is another, which puts the no-store, nosniff pair on every
            // response including the error dispatch; and cobolErrorRoute publishes the container error
            // path as a RouterFunction rather than as an eighteenth @RestController, so the online
            // inventory stays exactly the seventeen translated CICS programs (gate G3).
            // screenReadConstraints is deliberately NOT a bean - it is the parser bound the customizer
            // installs, exposed package-visibly only so a test can assert the same value the wire runs
            // under rather than a restatement of it.
            assertThat(Arrays.stream(WebConfig.class.getDeclaredMethods())
                    .filter(method -> !method.isSynthetic())
                    .map(Method::getName)
                    .sorted()
                    .toList())
                    .containsExactly("carddemoJacksonCustomizer", "carddemoScreenResponseHeaders",
                            "clock", "cobolErrorRoute", "jobSubmissionValidator",
                            "noStoreOnCredentialScreens", "screenReadConstraints");
            assertThat(Arrays.stream(WebConfig.class.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(Bean.class))
                    .map(Method::getName)
                    .sorted()
                    .toList())
                    .as("the contributed surface is six beans, and the bound helper is not one of them")
                    .containsExactly("carddemoJacksonCustomizer", "carddemoScreenResponseHeaders",
                            "clock", "cobolErrorRoute", "jobSubmissionValidator",
                            "noStoreOnCredentialScreens");
        }

        /**
         * A request whose path has been parsed and cached, as the dispatcher would have left it.
         *
         * <p>{@code MappedInterceptor.matches} resolves the lookup path from the request rather than
         * re-deriving it, and refuses a request that carries neither a parsed {@code RequestPath} nor a
         * resolved lookup path. Parsing here is what the {@code DispatcherServlet} does before any
         * handler mapping runs, so these assertions see the same input production does.
         *
         * @param method the HTTP method
         * @param path   the request URI
         * @return the request, ready for a path match
         */
        private MockHttpServletRequest routed(final String method, final String path) {
            MockHttpServletRequest request = new MockHttpServletRequest(method, path);
            ServletRequestPathUtils.parseAndCache(request);
            return request;
        }

        @Test
        @DisplayName("the no-store interceptor is a MappedInterceptor, so no callback override is needed")
        void theNoStoreInterceptorNeedsNoCallbackOverride() {
            MappedInterceptor interceptor = new WebConfig().noStoreOnCredentialScreens();

            assertThat(interceptor)
                    .as("AbstractHandlerMapping detects MappedInterceptor beans itself, which is what "
                            + "lets this class keep its empty WebMvcConfigurer override set")
                    .isNotNull();
            assertThat(interceptor.getInterceptor()).isInstanceOf(HandlerInterceptor.class);
            assertThat(interceptor.getExcludePathPatterns()).isNull();
        }

        @ParameterizedTest(name = "{0} is marked no-store")
        @ValueSource(strings = {"/api/signon", "/api/users", "/api/users/USER0001"})
        @DisplayName("each credential-bearing route matches the interceptor and gets the directive")
        void eachCredentialRouteIsMarkedNoStore(final String path) throws Exception {
            MappedInterceptor interceptor = new WebConfig().noStoreOnCredentialScreens();
            MockHttpServletRequest request = routed("POST", path);
            MockHttpServletResponse response = new MockHttpServletResponse();

            assertThat(interceptor.matches(request))
                    .as("%s accepts a password in its body, so it must not be cached anywhere", path)
                    .isTrue();
            assertThat(interceptor.getInterceptor().preHandle(request, response, new Object()))
                    .as("the chain continues - the interceptor adds a header and judges nothing")
                    .isTrue();
            assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL))
                    .isEqualTo("no-store");
        }

        @ParameterizedTest(name = "{0} is delivered bare no-store, and keeps the filter''s other headers")
        @ValueSource(strings = {"/api/signon", "/api/users", "/api/users/USER0001"})
        @DisplayName("the filter and the interceptor stack in that order, so the credential routes end up "
                + "with one directive and the other three headers")
        void theTwoCachePoliciesStackRatherThanCompete(final String path) throws Exception {
            MockHttpServletRequest request = routed("POST", path);
            MockHttpServletResponse response = new MockHttpServletResponse();

            // Production order: the servlet filter wraps the dispatch, so it writes first; the handler
            // interceptor's preHandle runs inside that dispatch, so it writes second and wins on the one
            // header they share.
            new WebConfig.ScreenResponseHeaderFilter()
                    .doFilter(request, response, new MockFilterChain());
            new WebConfig().noStoreOnCredentialScreens().getInterceptor()
                    .preHandle(request, response, new Object());

            assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL))
                    .as("the interceptor REPLACES the filter's four directives with one; nothing is lost, "
                            + "because no-store already forbids storing any part of the exchange")
                    .isEqualTo(WebConfig.NoStoreOnCredentialScreens.NO_STORE);
            assertThat(response.getHeader(HttpHeaders.PRAGMA))
                    .as("the legacy companions are the filter's and survive, so an intermediary that "
                            + "predates no-store is still told")
                    .isEqualTo(WebConfig.PRAGMA_VALUE);
            assertThat(response.getHeader(HttpHeaders.EXPIRES)).isEqualTo(WebConfig.EXPIRES_VALUE);
            assertThat(response.getHeader(WebConfig.CONTENT_TYPE_OPTIONS_HEADER))
                    .as("and so does nosniff, which has nothing to do with caching")
                    .isEqualTo(WebConfig.CONTENT_TYPE_OPTIONS_VALUE);
        }

        @ParameterizedTest(name = "{0} is left alone")
        @ValueSource(strings = {"/api/accounts/00000000011", "/api/cards", "/api/transactions",
            "/api/menu", "/api/billpay", "/api/reports"})
        @DisplayName("a route that carries no credential is left exactly as the framework leaves it")
        void aRouteWithoutACredentialIsNotIntercepted(final String path) {
            assertThat(new WebConfig().noStoreOnCredentialScreens()
                    .matches(routed("GET", path)))
                    .as("%s carries no password, so there is nothing here for this rule to do", path)
                    .isFalse();
        }

        @Test
        @DisplayName("the patterns are the paths the three controllers actually declare")
        void thePatternsMatchTheControllersOwnMappings() {
            // Written against the controllers' own constants and mapping annotations, so a route that
            // moved would fail the build here rather than silently lose its header. The patterns
            // themselves stay literals in WebConfig, which must not depend on the user package.
            MappedInterceptor interceptor = new WebConfig().noStoreOnCredentialScreens();

            assertThat(interceptor.matches(routed("POST", SignOnController.SIGNON_PATH)))
                    .as("POST %s", SignOnController.SIGNON_PATH)
                    .isTrue();
            assertThat(interceptor.matches(routed("POST", UserAddController.USERS_PATH)))
                    .as("POST %s", UserAddController.USERS_PATH)
                    .isTrue();

            String declaredUpdatePath = Arrays.stream(UserUpdateController.class.getDeclaredMethods())
                    .map(method -> method.getAnnotation(PutMapping.class))
                    .filter(Objects::nonNull)
                    .flatMap(mapping -> Arrays.stream(mapping.path()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "UserUpdateController declares no @PutMapping path"));
            assertThat(declaredUpdatePath)
                    .as("the third route, read from the controller's own annotation")
                    .isEqualTo("/api/users/{userId}");
            assertThat(interceptor.matches(routed("PUT", declaredUpdatePath.replace("{userId}", "USER0001"))))
                    .as("matched by the /api/users/* pattern")
                    .isTrue();
        }

        @Test
        @DisplayName("the Jackson contribution is a builder customizer and not a replacement mapper")
        void noReplacementObjectMapperIsContributed() {
            sliceRunner().run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBeanNamesForType(ObjectMapper.class)).isEmpty();
                assertThat(context.getBeanNamesForType(
                        Jackson2ObjectMapperBuilderCustomizer.class))
                        .containsExactly("carddemoJacksonCustomizer");
            });
        }

        @Test
        @DisplayName("the advice is registered exactly once")
        void theAdviceIsRegisteredOnce() {
            sliceRunner().run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBeanNamesForType(CobolErrorHandler.class)).hasSize(1);
                assertThat(context).hasSingleBean(CobolErrorHandler.class);
            });
        }

        @ParameterizedTest(name = "{0} is not on the classpath")
        @ValueSource(strings = {
            "org.springframework.security.web.SecurityFilterChain",
            "org.springframework.security.config.annotation.web.WebSecurityConfigurer",
            "org.springframework.security.crypto.password.PasswordEncoder",
            "org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder",
            "org.springframework.web.reactive.config.WebFluxConfigurer",
            "org.springdoc.core.models.GroupedOpenApi",
            "io.jsonwebtoken.Jwts"
        })
        @DisplayName("no excluded framework is present, so none can be wired in by accident")
        void noExcludedFrameworkIsOnTheClasspath(final String type) {
            assertThatExceptionOfType(ClassNotFoundException.class)
                    .isThrownBy(() -> Class.forName(type));
        }

        @Test
        @DisplayName("no bean in the slice is a security, filter-chain or authentication component")
        void theSliceContributesNoSecurityBean() {
            sliceRunner().run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(Arrays.stream(context.getBeanDefinitionNames())
                        .filter(name -> name.toLowerCase(Locale.ROOT).contains("security")
                                || name.toLowerCase(Locale.ROOT).contains("filterchain")
                                || name.toLowerCase(Locale.ROOT).contains("authentication")
                                || name.toLowerCase(Locale.ROOT).contains("passwordencoder"))
                        .toList())
                        .isEmpty();
            });
        }

        @Test
        @DisplayName("no bean in the slice is session-scoped")
        void theSliceContributesNoSessionScopedBean() {
            sliceRunner().run(context -> {
                assertThat(context).hasNotFailed();
                final ConfigurableListableBeanFactory factory =
                        context.getSourceApplicationContext().getBeanFactory();

                assertThat(Arrays.stream(context.getBeanDefinitionNames())
                        .filter(name -> "session".equals(factory.getBeanDefinition(name).getScope())
                                || "globalSession".equals(
                                        factory.getBeanDefinition(name).getScope()))
                        .toList())
                        .isEmpty();
            });
        }

        @Test
        @DisplayName("the shipped document configures no session store and no session timeout")
        void theShippedDocumentConfiguresNoSession() throws IOException {
            final PropertySource<?> document = shippedDocument("application.yml");

            assertThat(document.getProperty("server.servlet.session.timeout")).isNull();
            assertThat(document.getProperty("server.servlet.session.cookie.name")).isNull();
            assertThat(document.getProperty("server.servlet.session.persistent")).isNull();
            assertThat(document.getProperty("spring.session.store-type")).isNull();
        }

        @Test
        @DisplayName("the slice contributes no view resolver and no static-resource handler")
        void theSliceContributesNoViewOrResourceHandling() {
            sliceRunner().run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(Arrays.stream(context.getBeanDefinitionNames())
                        .filter(name -> name.toLowerCase(Locale.ROOT).contains("viewresolver")
                                || name.toLowerCase(Locale.ROOT).contains("resourcehandler")
                                || name.toLowerCase(Locale.ROOT).contains("templateengine"))
                        .toList())
                        .isEmpty();
            });
        }

        @Test
        @DisplayName("the whole slice is six contributed beans and nothing else")
        void theSliceContributesExactlyItsSixBeans() {
            sliceRunner().run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(Arrays.stream(context.getBeanDefinitionNames())
                        .filter(name -> name.startsWith("com.vsergeychik")
                                || name.startsWith("carddemo")
                                || "webConfig".equals(name)
                                || "clock".equals(name)
                                || "carddemoJacksonCustomizer".equals(name)
                                || "cobolErrorRoute".equals(name)
                                || WebConfig.SCREEN_HEADER_BEAN_NAME.equals(name)
                                || "jobSubmissionValidator".equals(name)
                                || "noStoreOnCredentialScreens".equals(name))
                        .sorted()
                        .toList())
                        .containsExactly(
                                "carddemo.job-submission-com.vsergeychik.carddemo.config.WebConfig"
                                        + "$JobSubmissionProperties",
                                CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME,
                                "carddemoJacksonCustomizer",
                                // The response-header contributor. Acknowledged here rather than
                                // absorbed: it sets Cache-Control: no-store and
                                // X-Content-Type-Options: nosniff on every response including the
                                // container's error dispatch. It is not a security filter chain - it
                                // reads nothing from the request and decides nothing about the caller.
                                WebConfig.SCREEN_HEADER_BEAN_NAME,
                                "clock",
                                "cobolErrorRoute",
                                "com.vsergeychik.carddemo.config.WebConfig$CobolErrorHandler",
                                "jobSubmissionValidator",
                                "noStoreOnCredentialScreens",
                                "webConfig");
            });
        }
    }

    // =================================================================================================
    // F-SM9 and F-SM10 - the inbound resource bounds and the outbound headers.
    // =================================================================================================

    /**
     * The two properties of the HTTP boundary that are about cost and caching rather than fidelity.
     *
     * <p>Everything else in this file asks whether a value survives the wire unchanged. This group asks
     * two different questions: how much work a single unauthenticated request can make the server do,
     * and what a caller is told about storing what comes back. Both were unanswered before - two of
     * Jackson's six stream-read defaults are {@code -1}, meaning unlimited, and no response carried a
     * caching directive or a sniffing directive at all.
     */
    @Nested
    @DisplayName("The request bounds and the response headers - cost and caching, not fidelity")
    class TheRequestBoundsAndResponseHeaders {

        /** A screen payload whose {@code TRNNAME} is the given value, as a document. */
        private static String screenDocument(final String trnName) {
            return "{\"TRNNAME\":\"" + trnName + "\"}";
        }

        @Test
        @DisplayName("all six parser bounds are stated, and none is left unlimited")
        void everyParserBoundIsStatedAndNoneIsUnlimited() {
            final StreamReadConstraints bounds = WebConfig.screenReadConstraints();

            assertThat(bounds.getMaxDocumentLength()).isEqualTo(WebConfig.MAX_JSON_DOCUMENT_BYTES);
            assertThat(bounds.getMaxTokenCount()).isEqualTo(WebConfig.MAX_JSON_TOKEN_COUNT);
            assertThat(bounds.getMaxStringLength()).isEqualTo(WebConfig.MAX_JSON_STRING_LENGTH);
            assertThat(bounds.getMaxNestingDepth()).isEqualTo(WebConfig.MAX_JSON_NESTING_DEPTH);
            assertThat(bounds.getMaxNameLength()).isEqualTo(WebConfig.MAX_JSON_NAME_LENGTH);
            assertThat(bounds.getMaxNumberLength()).isEqualTo(WebConfig.MAX_JSON_NUMBER_LENGTH);

            // The point of the whole registration: Jackson leaves the document length and the token
            // count at -1, so before this every request body was read until the caller stopped sending
            // (CWE-400). A negative value here would mean the bound had been removed again.
            assertThat(StreamReadConstraints.DEFAULT_MAX_DOC_LEN)
                    .as("the default this replaces really is unlimited")
                    .isNegative();
            assertThat(StreamReadConstraints.DEFAULT_MAX_TOKEN_COUNT).isNegative();
            assertThat(bounds.getMaxDocumentLength()).isPositive();
            assertThat(bounds.getMaxTokenCount()).isPositive();

            // And each of the four that had a default is genuinely tighter than it, so none of these is
            // a restatement of what Jackson already did.
            assertThat(bounds.getMaxStringLength())
                    .isLessThan(StreamReadConstraints.DEFAULT_MAX_STRING_LEN);
            assertThat(bounds.getMaxNestingDepth()).isLessThan(StreamReadConstraints.DEFAULT_MAX_DEPTH);
            assertThat(bounds.getMaxNameLength()).isLessThan(StreamReadConstraints.DEFAULT_MAX_NAME_LEN);
            assertThat(bounds.getMaxNumberLength()).isLessThan(StreamReadConstraints.DEFAULT_MAX_NUM_LEN);
        }

        @Test
        @DisplayName("the mapper the wire actually uses carries them, not just the factory method")
        void theCustomizedMapperCarriesTheBounds() {
            final StreamReadConstraints installed =
                    customizedMapper().getFactory().streamReadConstraints();

            assertThat(installed.getMaxDocumentLength()).isEqualTo(WebConfig.MAX_JSON_DOCUMENT_BYTES);
            assertThat(installed.getMaxTokenCount()).isEqualTo(WebConfig.MAX_JSON_TOKEN_COUNT);
            assertThat(installed.getMaxStringLength()).isEqualTo(WebConfig.MAX_JSON_STRING_LENGTH);
            assertThat(installed.getMaxNestingDepth()).isEqualTo(WebConfig.MAX_JSON_NESTING_DEPTH);
            assertThat(installed.getMaxNameLength()).isEqualTo(WebConfig.MAX_JSON_NAME_LENGTH);
            assertThat(installed.getMaxNumberLength()).isEqualTo(WebConfig.MAX_JSON_NUMBER_LENGTH);
        }

        @Test
        @DisplayName("a string past the bound is refused rather than buffered")
        void anOverLongStringIsRefused() {
            final String tooLong = "A".repeat(WebConfig.MAX_JSON_STRING_LENGTH + 1);

            assertThatExceptionOfType(StreamConstraintsException.class)
                    .isThrownBy(() -> customizedMapper()
                            .readTree(screenDocument(tooLong)));
        }

        @Test
        @DisplayName("nesting past the bound is refused rather than descended")
        void anOverDeepDocumentIsRefused() {
            final int depth = WebConfig.MAX_JSON_NESTING_DEPTH + 1;
            final String tooDeep = "[".repeat(depth) + "]".repeat(depth);

            assertThatExceptionOfType(StreamConstraintsException.class)
                    .isThrownBy(() -> customizedMapper().readTree(tooDeep));
        }

        @Test
        @DisplayName("a property name past the bound is refused rather than buffered")
        void anOverLongNameIsRefused() {
            final String tooLong = "N".repeat(WebConfig.MAX_JSON_NAME_LENGTH + 1);

            assertThatExceptionOfType(StreamConstraintsException.class)
                    .isThrownBy(() -> customizedMapper()
                            .readTree("{\"" + tooLong + "\":\"CC00\"}"));
        }

        @Test
        @DisplayName("a document past the bound is refused part-way through, not read to its end")
        void anOverLongDocumentIsRefused() {
            // Built from many short strings rather than one long one, so what is exceeded is the
            // DOCUMENT bound and not the string bound: this asserts the limit that was unlimited.
            final StringBuilder document = new StringBuilder("{\"TRNNAME\":\"CC00\"");
            final String filler = "F".repeat(1_000);
            for (int index = 0; document.length() <= WebConfig.MAX_JSON_DOCUMENT_BYTES; index++) {
                document.append(",\"F").append(index).append("\":\"").append(filler).append('"');
            }
            document.append('}');
            assertThat(document.length()).isGreaterThan((int) WebConfig.MAX_JSON_DOCUMENT_BYTES);

            assertThatExceptionOfType(StreamConstraintsException.class)
                    .isThrownBy(() -> customizedMapper().readTree(document.toString()));
        }

        @Test
        @DisplayName("the widest thing this repository actually ships is still accepted")
        void theLargestLegitimateInputIsAccepted() throws IOException {
            // 37,973 characters is the longest string in any of the 560 shipped parity case files, and
            // six is the deepest nesting any of them reaches. A bound that refused either would be a
            // bound set below the module's own data. The widest SCREEN value is far smaller still - the
            // broadest symbolic-map item in the estate is ERRMSGI PIC X(78).
            final String longestShippedString = "L".repeat(37_973);
            assertThat(customizedMapper().readTree(screenDocument(longestShippedString))
                    .get("TRNNAME").asText())
                    .hasSize(37_973);

            final String sixDeep = "{\"a\":{\"b\":{\"c\":{\"d\":{\"e\":{\"f\":\"CC00\"}}}}}}";
            assertThat(customizedMapper().readTree(sixDeep).at("/a/b/c/d/e/f").asText())
                    .isEqualTo("CC00");
        }

        @Test
        @DisplayName("an over-long body is answered as a malformed request, not as a server fault")
        void anOverLongBodyIsAnsweredAsAMalformedRequest() throws Exception {
            final String tooLong = "A".repeat(WebConfig.MAX_JSON_STRING_LENGTH + 1);

            // The bound is reached inside the parser, so it arrives at the advice as an unreadable body.
            // CobolErrorHandler already claims that family, and it answers 400 with the same invariant
            // envelope every other refusal uses - so exceeding a bound is a caller error, and the
            // response quotes nothing of what was sent.
            adviceDispatcher().perform(post(FIXTURE_SCREEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(screenDocument(tooLong)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(CobolErrorHandler.MALFORMED_REQUEST_CODE));
        }

        @Test
        @DisplayName("every response carries no-store and nosniff, on the success path")
        void aSuccessfulResponseCarriesBothHeaders() throws Exception {
            headerDispatcher().perform(get(FIXTURE_ACCOUNT))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, WebConfig.CACHE_CONTROL_VALUE))
                    .andExpect(header().string(HttpHeaders.PRAGMA, WebConfig.PRAGMA_VALUE))
                    .andExpect(header().string(HttpHeaders.EXPIRES, WebConfig.EXPIRES_VALUE))
                    .andExpect(header().string(WebConfig.CONTENT_TYPE_OPTIONS_HEADER,
                            WebConfig.CONTENT_TYPE_OPTIONS_VALUE));
        }

        @Test
        @DisplayName("and on the failure path, where the body is a refusal rather than a screen")
        void aRefusedResponseCarriesBothHeaders() throws Exception {
            headerDispatcher().perform(post(FIXTURE_SCREEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{"))
                    .andExpect(status().isBadRequest())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, WebConfig.CACHE_CONTROL_VALUE))
                    .andExpect(header().string(WebConfig.CONTENT_TYPE_OPTIONS_HEADER,
                            WebConfig.CONTENT_TYPE_OPTIONS_VALUE));
        }

        @Test
        @DisplayName("no-store is stated rather than a weaker directive, and nosniff exactly so")
        void theHeaderValuesAreTheStrongestOnesAvailable() {
            // A screen is painted for one request and is never reusable, so the directive has to be
            // no-store and not max-age=0 or no-cache alone (CWE-525). The legacy pair is carried too,
            // for intermediaries that predate Cache-Control.
            assertThat(WebConfig.CACHE_CONTROL_VALUE)
                    .startsWith("no-store")
                    .contains("no-cache", "must-revalidate", "max-age=0");
            assertThat(WebConfig.PRAGMA_VALUE).isEqualTo("no-cache");
            assertThat(WebConfig.EXPIRES_VALUE).isEqualTo("0");
            assertThat(WebConfig.CONTENT_TYPE_OPTIONS_VALUE).isEqualTo("nosniff");
            assertThat(WebConfig.CONTENT_TYPE_OPTIONS_HEADER).isEqualTo("X-Content-Type-Options");
        }

        @Test
        @DisplayName("the contributor runs on the container's ERROR dispatch too, unlike the default")
        void theContributorRunsOnTheErrorDispatch() {
            // Spring's default is to skip the error dispatch. That is the one dispatch where
            // CobolErrorRoute answers, so skipping it would leave the JSON refusal envelope with no
            // caching directive at all. Callable directly because the filter is a named class in this
            // package and the override is protected.
            assertThat(new WebConfig.ScreenResponseHeaderFilter().shouldNotFilterErrorDispatch())
                    .as("false means: run on the ERROR dispatch as well")
                    .isFalse();
        }

        @Test
        @DisplayName("the contributor reads nothing from the request: it is not authentication")
        void theContributorIsNotASecurityComponent() {
            // Gate G41. This is two response headers and nothing else - no principal, no credential, no
            // decision about the caller - so it is not the filter chain the migration excludes. The
            // assertion is structural: the filter declares no field, so it can hold no policy and no
            // state, and it cannot be carrying an authentication decision.
            assertThat(WebConfig.ScreenResponseHeaderFilter.class.getDeclaredFields())
                    .as("a header contributor has nothing to remember")
                    .isEmpty();
            assertThat(WebConfig.SCREEN_HEADER_BEAN_NAME.toLowerCase(Locale.ROOT))
                    .doesNotContain("security", "filterchain", "authentication", "passwordencoder");
            assertThat(new WebConfig().carddemoScreenResponseHeaders())
                    .isInstanceOf(WebConfig.ScreenResponseHeaderFilter.class);
        }
    }

    /**
     * The diagnostics the advice withholds from a caller are still emitted to the server log.
     *
     * <p>Withholding is not discarding, and this group is where that distinction is actually proved.
     * When an unreadable body is rejected, the field names Jackson recorded on the mapping path go to
     * the server's own diagnostics at {@code DEBUG} - a malformed request is a caller error rather
     * than a server fault, so a higher level would fill an operator's log - and nothing about them
     * reaches the response. The parser's own message, which is the one place the payload is quoted, is
     * never logged either: only the exception's type is.
     *
     * <p>Reaching this code needs {@code DEBUG} enabled, which is why it is the one place in this
     * class that changes anything outside itself. It changes exactly one logger, through Spring Boot's
     * backend-agnostic {@link LoggingSystem} rather than by importing a logging implementation, and it
     * restores the previous configuration in a {@code finally} block. JUnit runs sequentially here -
     * the module configures no parallel execution and ships no
     * {@code junit-platform.properties} - so the window cannot overlap another test (practice B7).
     */
    @Nested
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("The withheld field names still reach the server log, and only the server log")
    class WithheldDiagnosticsReachTheServerLog {
        private static final String ADVICE_LOGGER = CobolErrorHandler.class.getName();

        private void atDebugLevel(final ThrowingAction action) throws Exception {
            final LoggingSystem logging = LoggingSystem.get(getClass().getClassLoader());
            final LoggerConfiguration previous = logging.getLoggerConfiguration(ADVICE_LOGGER);
            logging.setLogLevel(ADVICE_LOGGER, LogLevel.DEBUG);
            try {
                action.run();
            } finally {
                logging.setLogLevel(ADVICE_LOGGER,
                        previous == null ? null : previous.getConfiguredLevel());
            }
        }

        @Test
        @DisplayName("a field-level parse failure names the field in the log and not in the response")
        void aNamedFieldReachesTheLogOnly(final CapturedOutput output) throws Exception {
            atDebugLevel(() -> {
                final String body = adviceDispatcher().perform(post("/webconfig-fixture/screen")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"TRNNAME\":\"CC00\",\"CURDATE\":\"07/19/22\","
                                        + "\"PGMNAME\":\"COSGN00C\",\"ERRMSG\":\"\","
                                        + "\"ACCTSID\":\"00000000011\","
                                        + "\"TAMT001\":\"not-a-number\"}"))
                        .andExpect(status().isBadRequest())
                        .andReturn().getResponse().getContentAsString();

                assertThat(output.getOut() + output.getErr())
                        .as("the field the converter stopped at belongs in the operator's log")
                        .contains("unreadable at field(s): TAMT001");
                assertThat(body)
                        .as("and the caller is told which member was refused, and nothing else")
                        .isEqualTo("{\"code\":\"" + CobolErrorHandler.MALFORMED_REQUEST_CODE
                                + "\",\"error\":\"" + HttpStatus.BAD_REQUEST.getReasonPhrase()
                                + "\",\"detail\":\"" + CobolErrorHandler.MALFORMED_REQUEST_MESSAGE
                                + "\",\"fieldErrors\":[{\"field\":\"TAMT001\",\"message\":\""
                                + "could not be read from the request body\"}]}")
                        .as("the parser's own message, which quotes the payload, still never travels")
                        .doesNotContain("not-a-number");
            });
        }

        @Test
        @DisplayName("a body that is not JSON names no field, because none is at fault")
        void anUnparseableDocumentNamesNoField(final CapturedOutput output) throws Exception {
            atDebugLevel(() -> {
                final String body = adviceDispatcher().perform(post("/webconfig-fixture/screen")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("this is not a JSON document at all"))
                        .andExpect(status().isBadRequest())
                        .andReturn().getResponse().getContentAsString();

                assertThat(output.getOut() + output.getErr())
                        .contains("none named - the document itself is unreadable");
                assertThat(body).isEqualTo("{\"code\":\""
                        + CobolErrorHandler.MALFORMED_REQUEST_CODE + "\",\"error\":\""
                        + HttpStatus.BAD_REQUEST.getReasonPhrase() + "\",\"detail\":\""
                        + CobolErrorHandler.MALFORMED_REQUEST_MESSAGE + "\",\"fieldErrors\":[]}");
            });
        }

        @Test
        @DisplayName("the log carries the exception type but never the parser's own message")
        void theLogCarriesTheTypeAndNotTheParserMessage(final CapturedOutput output)
                throws Exception {
            atDebugLevel(() -> {
                adviceDispatcher().perform(post("/webconfig-fixture/screen")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"TRNNAME\":\"CC00\",\"CURDATE\":\"07/19/22\","
                                        + "\"PGMNAME\":\"COSGN00C\",\"ERRMSG\":\"\","
                                        + "\"ACCTSID\":\"00000000011\","
                                        + "\"TAMT001\":\"4111111111111111X\"}"))
                        .andExpect(status().isBadRequest());

                final String logged = output.getOut() + output.getErr();

                assertThat(logged)
                        .contains(HttpMessageNotReadableException.class.getName());
                assertThat(logged).doesNotContain("4111111111111111");
            });
        }

        @Test
        @DisplayName("turning the diagnostics on does not widen what the caller is told")
        void enablingDebugDoesNotChangeTheResponse() throws Exception {
            final String quiet = adviceDispatcher().perform(post("/webconfig-fixture/screen")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"TRNNAME\":"))
                    .andReturn().getResponse().getContentAsString();

            atDebugLevel(() -> {
                final String verbose = adviceDispatcher().perform(post("/webconfig-fixture/screen")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"TRNNAME\":"))
                        .andReturn().getResponse().getContentAsString();

                assertThat(verbose).isEqualTo(quiet);
            });
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }

    private record ScreenPayload(
            String TRNNAME,
            String CURDATE,
            String PGMNAME,
            String ERRMSG,
            String ACCTSID,
            BigDecimal TAMT001) {
    }

    private record NestingPayload(ScreenPayload nested) {
    }
}
