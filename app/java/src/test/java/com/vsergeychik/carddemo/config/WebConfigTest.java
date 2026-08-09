package com.vsergeychik.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;

import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.config.WebConfig.CobolErrorHandler;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.http.MockHttpOutputMessage;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * {@link WebConfig}'s own contract: the JSON settings that keep a screen projection byte-faithful,
 * the {@link Clock} the screen header depends on, the global error mapping as the real dispatcher
 * resolves it, and - the highest-value part - everything this class deliberately refuses to
 * contribute.
 *
 * <h2>Why the JSON settings are behaviour and not preference</h2>
 * The 17 CICS online programs each drove a BMS mapset, and those mapsets are the presentation
 * contract of this migration. There is no design system and no component library anywhere in this
 * repository to defer to - a repository-wide scan for {@code package.json}, {@code *.tsx},
 * {@code *.css}, {@code tailwind.config*}, {@code theme*} and {@code tokens*} returns nothing, and
 * there are zero Figma attachments (AAP 0.6.1, 0.11.1) - so the mapsets are the whole of it.
 *
 * <p>The reconciliation that makes an <em>untransformed</em> JSON naming strategy mandatory was
 * re-verified against {@code app/bms} before this class was written, rather than taken on trust:
 * <pre>
 * for f in app/bms/*.bms; do grep -v "^\*" "$f" | grep -c "^[A-Z0-9][A-Z0-9]* *DFHMDF"; done
 * </pre>
 * totals <strong>441</strong> - COACTUP 54, COTRN00 59, COSGN00 11 - while the raw count of
 * non-comment {@code DFHMDF} occurrences is <strong>902</strong>. The 461-entry difference is
 * unnamed field definitions: static screen literals such as {@code INITIAL='Tran :'}, which have no
 * symbolic-map {@code xxxI} item and therefore no payload member at all. Only the 441 <em>named</em>
 * definitions become fields, and their identifiers are upper-case mainframe labels -
 * {@code TRNNAME}, {@code CURDATE}, {@code PGMNAME}, {@code ERRMSG}, {@code ACCTSID} - taken
 * verbatim from column 1 of the mapset. Any camelCase, snake_case or kebab-case strategy would
 * rewrite every one of them and destroy the 1:1 trace from payload field back to {@code DFHMDF}
 * definition, which is what the parity harness follows.
 *
 * <p>Within a symbolic map ({@code app/cpy-bms/COSGN00.CPY}, read directly) each field is five
 * items: {@code xxxL COMP PIC S9(4)}, {@code xxxF PICTURE X}, a {@code FILLER REDEFINES xxxF}
 * carrying {@code xxxA}, a 4-byte {@code FILLER}, and finally {@code xxxI PIC X(n)}. Only
 * {@code xxxI} is a payload member, and it is where a Bean Validation length constraint comes
 * from - {@code USERIDI PIC X(8)} and {@code PASSWDI PIC X(8)} on the sign-on screen, for instance.
 *
 * <h2>What this class does not do, and who does it instead</h2>
 * It stays a slice on purpose. {@code CardDemoApplicationTest} owns gate G3's whole-graph context
 * load, so nothing here is a {@code @SpringBootTest}: bean presence and absence go through
 * {@link ApplicationContextRunner}, the advice goes through a standalone {@code MockMvc} over a
 * throwaway controller declared at the foot of this file, and the Jackson settings go through a
 * plain {@link Jackson2ObjectMapperBuilder}. None of the 17 real controllers is touched - each has
 * its own test in its own domain package - and neither DTO field lists nor {@link DateHeader}'s
 * 58-byte layout is asserted here, because those belong to the {@code dto} packages and to
 * {@code common} respectively.
 *
 * <p>Two sibling suites already own parts of the error mapping and are not duplicated:
 * {@code WebConfigErrorContractTest} drives the body builders as static methods and asserts what
 * they withhold, and {@code WebConfigErrorBoundaryTest} drives the field-level projections and the
 * five {@link FileStatus.Outcome} constants directly. What is added here is the part neither can
 * reach: <em>handler selection by the real exception resolver</em>, including that a narrower
 * handler beats a broader one and that the catch-all preserves a status it did not choose.
 *
 * <h2>Determinism (practice B7)</h2>
 * {@link DateHeader} never calls {@code now()}; its only clock-consuming factory takes a
 * {@link Clock} and reads {@code instant()} and {@code getZone()} exactly once. {@link WebConfig}
 * supplies the module's single {@link Clock}, so a fixed clock in a test is not a convenience but
 * the mechanism by which 17 date-bearing controllers become assertable at all. Every assertion here
 * that touches time uses {@link #FIXED_CLOCK}; nothing reads wall-clock time, and no assertion
 * depends on the ambient time zone or the platform default charset, so the suite gives identical
 * results under {@code -Duser.timezone=America/Chicago}, {@code -Duser.timezone=UTC} and
 * {@code -Dfile.encoding=US-ASCII}.
 *
 * <h2>Gates enforced</h2>
 * G3 the {@link Clock} bean exists and is unique &middot; G22 and G24 no {@code double} and no
 * rounding at the JSON boundary &middot; G37 no server-side session state &middot; G41
 * authentication stays as {@code COSGN00C} performs it, with no security machinery introduced
 * &middot; G47 every {@link FileStatus} outcome reaches a status decision &middot; G49 branch
 * coverage of this package &middot; G52 no wildcard imports &middot; G53 no static mutable state
 * &middot; G54 non-interactive.
 *
 * <p><strong>User-specified rules:</strong> {@code review_rules} reports that no user rules were
 * provided - that one line is the whole document - so none are cited below. The AAP's
 * enterprise-practice set stands in their place and is named where it bites.
 */
@DisplayName("WebConfig - JSON fidelity, the Clock bean, the error mapping, and what it refuses")
class WebConfigTest {

    /**
     * The instant every time-dependent assertion is pinned to.
     *
     * <p>Not an arbitrary date: {@code 2022-07-19} is this repository's own source version footer,
     * which several files carry as {@code Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19} -
     * {@code app/cbl/CSUTLDTC.cbl}, {@code app/jcl/INTCALC.jcl} and {@code app/jcl/POSTTRAN.jcl}
     * among them. Choosing it means a failure message points at something traceable rather than at
     * a number somebody made up.
     */
    private static final Instant FIXED_INSTANT = Instant.parse("2022-07-19T23:23:06Z");

    /**
     * The fixed clock. Immutable and shared, so it is {@code static final} without introducing
     * mutable static state (practice B9, gate G53).
     *
     * <p>{@link ZoneOffset#UTC} rather than the platform zone, so a build run in any time zone sees
     * the same rendered date and time. That is the difference between a suite that passes and a
     * suite that passes <em>here</em>.
     */
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    /** A program name that must never appear in a response body. */
    private static final String WITHHELD_PROGRAM = "CBACT04C";

    /** A reason string that must never appear in a response body. */
    private static final String WITHHELD_REASON = "ERROR OPENING ACCTFILE";

    /**
     * The five {@code RETURN-CODE} values this estate actually produces, as
     * {@link AbendException} declares them: {@code 0} normal, {@code 4} warning, {@code 8} assumed
     * failure, {@code 12} I/O error, and {@code 16} for the {@code 88 APPL-EOF} condition.
     */
    private static final String OBSERVED_RETURN_CODES = "0, 4, 8, 12, 16";

    /**
     * The exact bytes an abend answers with, composed from the two constants the advice declares.
     *
     * <p>Held as a constant so that the byte-for-byte comparison is written once and every abend
     * assertion compares against the same expectation. If either constant changes, every abend
     * assertion changes with it - which is the correct coupling, because the pair is the contract.
     */
    private static final String ABEND_BODY = "{\"code\":\"" + CobolErrorHandler.ABEND_CODE
            + "\",\"message\":\"" + CobolErrorHandler.ABEND_MESSAGE + "\"}";

    /**
     * Builds an {@link ObjectMapper} the way Spring Boot would: a fresh builder, then
     * {@link WebConfig#carddemoJacksonCustomizer()} applied to it.
     *
     * <p>Applying the real customizer rather than hand-assembling a mapper is the whole point. A
     * hand-assembled mapper would assert that a particular set of features produces a particular
     * result, which is true of any mapper; going through the production bean asserts that
     * <em>this module's</em> mapper does.
     *
     * @return the customized mapper, exactly as configured for the wire
     */
    private static ObjectMapper customizedMapper() {
        final Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        customizer().customize(builder);
        return builder.build();
    }

    /**
     * The production customizer bean, obtained from a plain instance of the configuration class.
     *
     * @return the customizer under test
     */
    private static Jackson2ObjectMapperBuilderCustomizer customizer() {
        return new WebConfig().carddemoJacksonCustomizer();
    }

    /**
     * A context slice holding {@link WebConfig} and nothing else.
     *
     * <p>Returned fresh on every call rather than held in a field, so no two tests can perturb one
     * another (practice B7) and no mutable state is shared (practice B9, gate G53).
     *
     * <p>Two details in the property list are deliberate. {@code spring.profiles.active=} is stated
     * <em>empty</em> because an {@link ApplicationContextRunner} inherits the surrounding JVM's
     * system properties and process environment: a build invoked with
     * {@code -Dspring.profiles.active=test}, or a CI executor exporting
     * {@code SPRING_PROFILES_ACTIVE=test}, would otherwise activate a profile inside a slice that
     * asked for none. Stating the key installs it ahead of both sources. And the eight
     * {@code carddemo.job-submission.*} values are required rather than optional: {@link WebConfig}
     * declares {@code @EnableConfigurationProperties} for that record and registers a validator that
     * runs at context refresh, so a slice that omitted them would fail to start for a reason that
     * has nothing to do with what is being tested. The two paths are absolute, traversal-free and
     * outside every reference tree, and no filesystem access occurs - the validation is pure path
     * algebra.
     *
     * @return a runner that starts {@link WebConfig} in isolation
     */
    private static ApplicationContextRunner sliceRunner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(WebConfig.class)
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

    /**
     * Stands up the real Spring MVC dispatch machinery over {@link ScreenFixtureController}, with
     * {@link CobolErrorHandler} registered as the advice and the production Jackson settings on the
     * message converter.
     *
     * <p>This is the one thing a static drive of a body builder cannot do: it puts Spring's own
     * {@code ExceptionHandlerExceptionResolver} in the path, so the tests below assert which handler
     * the framework <em>selects</em> and not merely what a handler returns once chosen. Handler
     * selection is a real behaviour with a real failure mode - a broad {@code Exception} handler that
     * shadowed a narrower one would flatten every client mistake to {@code 500}.
     *
     * <p>Built per test method rather than shared, so no cached resolver state crosses a test
     * boundary. A fresh validator is created with it, because {@code standaloneSetup} does not
     * install one and {@code @Valid} would then be silently ignored - a test that appeared to pass
     * while asserting nothing.
     *
     * @return the configured dispatcher
     */
    private static MockMvc adviceDispatcher() {
        final LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return MockMvcBuilders.standaloneSetup(new ScreenFixtureController())
                .setControllerAdvice(new CobolErrorHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(customizedMapper()))
                .setValidator(validator)
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

    /**
     * Payload identifiers reach the wire exactly as the symbolic maps name them.
     *
     * <p>This is the group the 902-versus-441 reconciliation in this class's header exists to
     * justify. The 441 named {@code DFHMDF} labels are the payload identifiers, they are upper-case
     * mainframe labels, and any naming strategy at all would rewrite them.
     */
    @Nested
    @DisplayName("JSON naming is untransformed, so every payload field still traces to a DFHMDF")
    class JsonNamingIsUntransformed {

        @Test
        @DisplayName("upper-case BMS labels serialise character for character")
        void bmsLabelsSurviveSerialisationVerbatim() throws IOException {
            final String json = customizedMapper().writeValueAsString(new ScreenPayload(
                    "CC00", "07/19/22", "COSGN00C", "", "00000000011", new BigDecimal("100.00")));

            // Exact equality, and in declaration order, because both are part of what a reader
            // compares a payload against by eye when tracing a field back to its mapset.
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

            // Asserted rather than assumed (practice B8). Behaviour alone would also pass against a
            // mapper that happened to be configured with an identity strategy today and a different
            // one after an upgrade; pinning the strategy to absent says the naming of the wire is
            // owned by the DTOs, which is exactly the claim application.yml makes in prose.
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

            // Boot would apply spring.jackson.property-naming-strategy on top of the customizer, so
            // the customizer being clean is only half the guarantee.
            assertThat(document.getProperty("spring.jackson.property-naming-strategy")).isNull();
        }
    }

    /**
     * A monetary value keeps its type and its scale across the wire.
     *
     * <p>Every signed decimal picture in this estate is scale 2 - exhaustive extraction across the 28
     * programs found only {@code PIC S9(10)V99}, {@code PIC S9(09)V99} and {@code PIC S9(9)V99} - so
     * a scale change or a {@code double} at the JSON boundary is a parity defect and not a formatting
     * detail (gates G22, G24).
     */
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
            // Queried on the factory, not on the mapper: WRITE_BIGDECIMAL_AS_PLAIN is a
            // JsonGenerator.Feature, so it is carried by the JsonFactory the mapper writes through.
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
            // Not merely numerically equal: 100 and 100.0 both compare equal to 100.00 under
            // compareTo, and either would be a different byte sequence in a fixed-width receiver.
            assertThat(received.TAMT001().toPlainString()).isEqualTo("100.00");
        }

        @ParameterizedTest(name = "scale {1} arrives and leaves unchanged for {0}")
        @CsvSource({"1.5, 1", "1.50, 2", "1.500, 3", "1.5000, 4", "12, 0"})
        @DisplayName("no rounding mode is applied at the JSON boundary")
        void noRoundingHappensAtTheJsonBoundary(final String literal, final int scale)
                throws IOException {
            final ObjectMapper mapper = customizedMapper();

            final BigDecimal received = mapper.readValue(literal, BigDecimal.class);

            // The JSON boundary transports; it does not decide scale. RoundingMode.DOWN - the only
            // faithful mode, because the keyword ROUNDED appears zero times in all 28 programs -
            // belongs to common/CobolDecimal at the point of store, and duplicating it here would
            // truncate a value twice and in the wrong place.
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

    /**
     * Space padding, empty strings and nulls all survive the wire unaltered.
     *
     * <p>A {@code PIC X(n)} field is space-padded to its declared width and is not trimmed on read
     * unless the COBOL itself trims, so an all-spaces value is data rather than absence. Trimming or
     * null-coercion at the JSON boundary is the single most common silent-corruption path in a COBOL
     * migration precisely because it looks like tidying up.
     */
    @Nested
    @DisplayName("Space padding, empty strings and nulls survive the wire untouched")
    class SpacePaddingAndNullFidelity {

        @Test
        @DisplayName("a space-padded PIC X value round-trips with its trailing spaces intact")
        void trailingSpacesAreNotTrimmed() throws IOException {
            final ObjectMapper mapper = customizedMapper();
            // COSGN00.CPY declares USERIDI PIC X(8), so a three-character name occupies eight bytes.
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

            // The behavioural half of the feature check above, and the stronger of the two: with the
            // feature enabled this would quietly yield a NestingPayload holding null.
            assertThatExceptionOfType(MismatchedInputException.class).isThrownBy(
                    () -> mapper.readValue("{\"nested\":\"\"}", NestingPayload.class));
        }

        @Test
        @DisplayName("a null field is emitted rather than omitted, so no screen field disappears")
        void nullFieldsAreEmitted() throws IOException {
            final String json = customizedMapper().writeValueAsString(new ScreenPayload(
                    "CC00", null, null, null, null, null));

            // A missing key would break the 1:1 field trace just as surely as a renamed one: the
            // reader could not tell an absent field from a field that was never in the mapset.
            assertThat(json).isEqualTo("{\"TRNNAME\":\"CC00\",\"CURDATE\":null,\"PGMNAME\":null,"
                    + "\"ERRMSG\":null,\"ACCTSID\":null,\"TAMT001\":null}");
        }

        @Test
        @DisplayName("the shipped document sets default-property-inclusion to always")
        void theShippedDocumentIncludesEveryProperty() throws IOException {
            final PropertySource<?> document = shippedDocument("application.yml");

            // Stated in the document rather than in the customizer, so the customizer being silent
            // about inclusion is correct and this is where the guarantee actually lives.
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

            // IBM037 and US-ASCII belong exclusively to dataset input and output, where
            // CobolCharsetConfig selects them; applying either to an HTTP response would corrupt
            // every byte of it. So the assertion here is UTF-8, decoded and compared as such.
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

    /**
     * An abend answers {@code 500} with one fixed body, whatever the {@code RETURN-CODE} was.
     *
     * <p>{@code CALL 'CEE3ABD'} appears at nine sites across the batch programs, and
     * {@link AbendException} is its Java form. The status is {@code 500} because the COBOL did not
     * complete its unit of work; the body is a constant because the abending program's name, its
     * {@code RETURN-CODE} and its composed {@code ABENDING PROGRAM} sentence - which can quote a
     * dataset's own reason text - are server-side diagnostics rather than anything a caller outside
     * the trust boundary is entitled to.
     *
     * <p>So "preserve the message byte for byte" is asserted here as exact equality against
     * {@link CobolErrorHandler#ABEND_MESSAGE} together with the absence of every withheld value. A
     * {@code contains} assertion or a trimmed comparison would let a real leak through.
     */
    @Nested
    @DisplayName("An abend answers 500 with one invariant body across every RETURN-CODE")
    class AbendMappingOverHttp {

        @ParameterizedTest(name = "RETURN-CODE={0} answers 500 with the invariant abend body")
        @ValueSource(ints = {0, 4, 8, 12, 16})
        @DisplayName("every observed RETURN-CODE produces the same status and the same body")
        void everyObservedReturnCodeIsAnsweredIdentically(final int returnCode) throws Exception {
            adviceDispatcher().perform(get("/webconfig-fixture/abend/" + returnCode))
                    .andExpect(status().isInternalServerError())
                    // content().string, not content().json: a JSON comparison is a comparison of
                    // structure, and this assertion is deliberately a comparison of bytes.
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
            // Two members and no third: no program, no return code, no reason, no path, no timestamp.
            assertThat(body.chars().filter(character -> character == ':').count()).isEqualTo(2);
        }

        @Test
        @DisplayName("an abend carrying ABCODE and TIMING is answered like one carrying neither")
        void theAbendParameterBranchesAreIndistinguishableToACaller() throws Exception {
            // The standard sites set both LE arguments; app/cbl/CBSTM03A.CBL:923 sets neither. Both
            // shapes exist in the estate, so both are driven - and a caller must not be able to tell
            // them apart, because that difference is a server-side diagnostic.
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
                    .andExpect(jsonPath("$.message").value(CobolErrorHandler.ABEND_MESSAGE));
        }

        @Test
        @DisplayName("the withheld detail is still on the exception, for the server's own diagnostics")
        void theDetailRemainsAvailableServerSide() {
            // Withholding is not discarding. A production failure has to stay diagnosable, so the
            // values kept out of the body must still be on the exception the advice logged.
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
            // Keeps the @ValueSource above honest: it is the declared APPL-RESULT set, not a list
            // that happened to be typed in.
            assertThat(List.of(AbendException.RETURN_CODE_OK, AbendException.RETURN_CODE_WARNING,
                    AbendException.RETURN_CODE_ASSUMED_FAILURE, AbendException.RETURN_CODE_IO_ERROR,
                    AbendException.RETURN_CODE_END_OF_FILE))
                    .containsExactly(0, 4, 8, 12, 16);
            assertThat(OBSERVED_RETURN_CODES).isEqualTo("0, 4, 8, 12, 16");
        }
    }

    /**
     * A rejected field reports which field and why, and nothing more.
     *
     * <p>The constraints these tests trip are the symbolic maps' widths expressed as Bean Validation
     * annotations: {@code app/cpy-bms/COSGN00.CPY} declares {@code USERIDI PIC X(8)} and
     * {@code PASSWDI PIC X(8)}, and the matching {@code DFHMDF} definitions in
     * {@code app/bms/COSGN00.bms} are {@code LENGTH=8}. Reporting the field and the violation is what
     * lets a caller correct the input, and it mirrors the COBOL highlighting the offending field on
     * re-display. No specific production DTO's field list is asserted - that belongs to the domain
     * {@code dto} packages.
     */
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
                    // The declared width, and nothing else. The validator's own message is NOT what
                    // travels: on this module's DTOs it is maintainer prose naming the symbolic-map
                    // item, its PICTURE clause and the copybook path and line the width came from, and
                    // forwarding it would publish the copybook inventory one rejected field at a time.
                    // Neither is the constraint implementation's default wording - "size must be
                    // between 0 and 8" - because that is whatever the validator release happens to say.
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
                    // PASSWD before USERID: sorted, not in whatever order the binder produced, so the
                    // same failure always serialises the same way.
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

            // The values flowing through these DTOs are card numbers, account identifiers and
            // government-issued identifiers, so naming the field and its width is the reportable part
            // and the characters never are.
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

            // COSGN00C compares SEC-USR-PWD PIC X(08) against USRSEC in plaintext and this migration
            // preserves that, because hashing would be a behaviour change and would need a framework
            // that is out of scope (practice B6, gate G41). Preserving it is not licence to publish
            // it: the error boundary still refuses to put the value in a response.
            assertThat(body).doesNotContain(secret);
        }

        @Test
        @DisplayName("a valid payload passes the validator untouched")
        void aValidPayloadIsAccepted() throws Exception {
            // Quoted for the same reason as the path-variable case: the fixture returns a String and
            // Jackson is the only converter. USER0001 and PASSWORD are both exactly eight characters,
            // which is the width USERIDI and PASSWDI declare.
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
                    .andExpect(jsonPath("$.message")
                            .value(CobolErrorHandler.MALFORMED_REQUEST_MESSAGE));
        }
    }

    /**
     * The dispatcher selects the handler the advice intends, and the catch-all preserves a status it
     * did not choose.
     *
     * <p>This is the group that needs the real resolver. Spring resolves an
     * {@code @ExceptionHandler} by walking the thrown exception's own type hierarchy and choosing the
     * closest declared match, and because an advice is consulted <em>before</em> Spring's default
     * resolver, a naive {@code Exception} handler would silently turn an unknown path, a wrong method
     * and an unsupported media type into {@code 500 Internal Server Error}. Driving each through the
     * dispatcher is the only way to hold that.
     */
    @Nested
    @DisplayName("Handler selection is deterministic, and the catch-all preserves the status")
    class HandlerSelectionIsDeterministic {

        @Test
        @DisplayName("a value a domain guard rejected answers 400, not the catch-all's 500")
        void aRejectedValueStaysABadRequest() throws Exception {
            adviceDispatcher().perform(get("/webconfig-fixture/rejected-value"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(HttpStatus.BAD_REQUEST.getReasonPhrase()))
                    .andExpect(jsonPath("$.detail").exists())
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
                    .andExpect(jsonPath("$.status").value(500))
                    .andReturn().getResponse().getContentAsString();

            assertThat(body)
                    .isEqualTo("{\"status\":500,\"error\":\""
                            + HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase()
                            + "\",\"message\":\"" + CobolErrorHandler.DATASET_ACCESS_MESSAGE + "\"}")
                    .doesNotContain("AWS.M2.CARDDEMO")
                    .doesNotContain("connection");
        }

        @Test
        @DisplayName("a conversion failure answers the one fixed body, naming no Java type")
        void aConversionFailureNamesNoJavaType() throws Exception {
            // MethodArgumentTypeMismatchException extends TypeMismatchException and there is exactly
            // ONE handler for the family, so the same fixed sentence answers a path variable, a query
            // parameter and a conversion refused during binding alike. There used to be a narrower
            // handler for the MVC subclass which, because closest-match resolution preferred it,
            // reported the required type - "is not a valid long" - and so published the internal Java
            // type of a screen field to an unauthenticated caller.
            final String body = adviceDispatcher()
                    .perform(get("/webconfig-fixture/accounts/not-a-number"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.message").value(CobolErrorHandler.TYPE_MISMATCH_MESSAGE))
                    .andReturn().getResponse().getContentAsString();

            assertThat(body)
                    .doesNotContain("long")
                    .doesNotContain("Long")
                    .doesNotContain("accountId")
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
            // Quoted, because the fixture returns a String and the only converter installed is the
            // Jackson one, so the value is serialised as a JSON string. The point of the assertion is
            // that the request reached the handler at all rather than being claimed by the advice.
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

            assertThat(body).isEqualTo("{\"status\":500,\"error\":\""
                    + HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase() + "\",\"message\":\""
                    + CobolErrorHandler.UNEXPECTED_FAILURE_MESSAGE + "\"}");
        }

        @Test
        @DisplayName("an unknown path keeps its 404 rather than becoming a server error")
        void anUnknownPathKeepsItsStatus() throws Exception {
            adviceDispatcher().perform(get("/webconfig-fixture/no-such-screen"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.error").value(HttpStatus.NOT_FOUND.getReasonPhrase()))
                    .andExpect(jsonPath("$.message")
                            .value(CobolErrorHandler.UNEXPECTED_FAILURE_MESSAGE));
        }

        @Test
        @DisplayName("a wrong method keeps its 405")
        void aWrongMethodKeepsItsStatus() throws Exception {
            adviceDispatcher().perform(post("/webconfig-fixture/rejected-value")
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(jsonPath("$.status").value(405))
                    .andExpect(jsonPath("$.error")
                            .value(HttpStatus.METHOD_NOT_ALLOWED.getReasonPhrase()));
        }

        @Test
        @DisplayName("an unsupported content type keeps its 415")
        void anUnsupportedMediaTypeKeepsItsStatus() throws Exception {
            adviceDispatcher().perform(post("/webconfig-fixture/signon")
                            .contentType(MediaType.TEXT_PLAIN).content("USER0001"))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.status").value(415))
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

            // A timestamp would make a response body non-deterministic in a module whose responses
            // are compared byte for byte, and a path would echo caller-supplied text.
            assertThat(body)
                    .doesNotContain("timestamp")
                    .doesNotContain("\"path\"")
                    .doesNotContain("webconfig-fixture");
        }
    }

    /**
     * The module's single time source (gate G3, practice B7).
     *
     * <p>{@code common/DateHeader} is the Java form of the {@code WS-DATE-TIME} group in
     * {@code app/cpy/CSDAT01Y.cpy}, and it supplies the {@code CURDATE} and {@code CURTIME} fields
     * that sit in the top-right corner of all 17 screens. It never calls {@code now()} of its own:
     * its clock-consuming factory takes a {@link Clock} and reads {@code instant()} and
     * {@code getZone()} exactly once, so a test can pass a fixed clock and assert an exact rendered
     * header. That design needs exactly one {@link Clock} in the context, and {@link WebConfig}
     * supplies it - a missing one fails the whole graph, and a non-fixed one in a test is what makes
     * a suite flaky.
     *
     * <p>{@link DateHeader}'s own 58-byte layout is deliberately not asserted here; that belongs to
     * the {@code common} package's tests. What is asserted here is the wiring the layout depends on.
     */
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

            // Compared against systemDefaultZone rather than against a named zone, so the assertion
            // holds under -Duser.timezone=America/Chicago exactly as it does under UTC. A fixed clock
            // here would freeze the date on every screen.
            assertThat(clock).isEqualTo(Clock.systemDefaultZone());
            assertThat(clock.getZone()).isEqualTo(ZoneId.systemDefault());
            assertThat(clock).isNotEqualTo(FIXED_CLOCK);
        }

        @Test
        @DisplayName("a Clock is immutable, so one shared instance serves every concurrent request")
        void theClockBeanIsSafeToShare() {
            final WebConfig config = new WebConfig();

            // Two invocations produce equal values rather than a shared mutable object, which is what
            // makes an instance-method bean correct here and a static field unnecessary (practice B9).
            assertThat(config.clock()).isEqualTo(config.clock());
        }

        @Test
        @DisplayName("a fixed clock reads identically however many times it is read")
        void aFixedClockIsReproducible() {
            // The two accessors DateHeader reads. Freezing them is what makes a rendered header
            // comparable byte for byte against an expected parity image.
            assertThat(FIXED_CLOCK.instant()).isEqualTo(FIXED_INSTANT);
            assertThat(FIXED_CLOCK.instant()).isEqualTo(FIXED_CLOCK.instant());
            assertThat(FIXED_CLOCK.getZone()).isEqualTo(ZoneOffset.UTC);
            assertThat(FIXED_CLOCK.millis()).isEqualTo(FIXED_CLOCK.millis());
        }

        @Test
        @DisplayName("a second Clock definition fails the context rather than quietly winning")
        void aSecondClockDefinitionFailsTheContext() {
            // Measured, not assumed: Spring Boot disables bean-definition overriding, so a second
            // Clock is a startup failure. That is the property that makes "the single source of the
            // current instant" true by construction rather than by convention - two clocks in one
            // context would render two different times into one screen header.
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
            // The substitution a controller or a service test performs: WebConfig is absent, the
            // fixed clock is the context's clock, and two reads are identical. This is the mechanism
            // by which every date-bearing controller becomes deterministic (practice B7).
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
            // The structural form of "never calls now()": at least one public static factory declares
            // a java.time.Clock parameter, and no factory offers a no-argument form that could only
            // have obtained the time internally.
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

    /**
     * What {@link WebConfig} refuses to contribute, which defines it as much as what it declares.
     *
     * <p>A suite asserting only the positives would pass against an implementation that also switched
     * off Boot's MVC auto-configuration, replaced the shared {@code ObjectMapper}, or introduced a
     * session store or a filter chain. Each of those is a real regression with a real cost, and each
     * is asserted against here.
     */
    @Nested
    @DisplayName("The negative contract - no EnableWebMvc, no ObjectMapper, no session, no security")
    class TheNegativeContract {

        @Test
        @DisplayName("WebConfig is not annotated with EnableWebMvc")
        void enableWebMvcIsAbsent() {
            // @EnableWebMvc switches Boot's MVC auto-configuration off and hands the whole
            // configuration to the application, which would discard the auto-configured HTTP message
            // converters - including the Jackson converter this class spends its length configuring -
            // and every one of the 17 controllers would stop mapping JSON correctly (gate G3).
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
            // There is no web user interface, no component library and no design tokens in this
            // estate, so JSON is the only representation and an empty override set is the correct
            // outcome rather than an omission. Overriding the converter callbacks in particular would
            // be the way the UTF-8 guarantee above could be undone.
            assertThat(Arrays.stream(WebConfig.class.getDeclaredMethods())
                    .filter(method -> !method.isSynthetic())
                    .map(Method::getName)
                    .toList())
                    .doesNotContain(callback);
        }

        @Test
        @DisplayName("WebConfig declares only its three bean methods")
        void onlyTheThreeBeanMethodsAreDeclared() {
            assertThat(Arrays.stream(WebConfig.class.getDeclaredMethods())
                    .filter(method -> !method.isSynthetic())
                    .map(Method::getName)
                    .sorted()
                    .toList())
                    .containsExactly("carddemoJacksonCustomizer", "clock", "jobSubmissionValidator");
        }

        @Test
        @DisplayName("the Jackson contribution is a builder customizer and not a replacement mapper")
        void noReplacementObjectMapperIsContributed() {
            sliceRunner().run(context -> {
                assertThat(context).hasNotFailed();
                // A replacement ObjectMapper bean would silently discard Boot's own configuration -
                // the module's modules, its spring.jackson.* handling and its other converters - so
                // customizing the builder is the whole point.
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
                // Registration comes from the @RestControllerAdvice annotation alone. A @Bean factory
                // method for it as well would register it a second time under a second name, leaving
                // two identical instances in the exception resolver's cache.
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
            // Asserted as classpath absence, which is stronger than asserting no bean of the type: a
            // type that is not present cannot be introduced by a later edit without also changing
            // app/java/pom.xml, and that change is visible in review (practice B1, AAP 0.5.6).
            // Authentication stays the plaintext SEC-USR-PWD PIC X(08) comparison against USRSEC that
            // COSGN00C performs; hashing it would be a behaviour change (gate G41).
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

                // CICS is pseudo-conversational, and the conversation state of these 17 programs -
                // the CARDDEMO-COMMAREA fields, the EIBAID value and the screen's own field values -
                // travels in the request and response payloads through common/NavigationContext.
                // Server-side state would reintroduce exactly the session affinity the migration
                // removes (gate G37, AAP rule R6).
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
        @DisplayName("the whole slice is four contributed beans and nothing else")
        void theSliceContributesExactlyItsFourBeans() {
            sliceRunner().run(context -> {
                assertThat(context).hasNotFailed();
                // Named explicitly rather than counted, so an added bean has to be acknowledged here
                // rather than absorbed by a threshold. The three nested records carry no stereotype
                // annotation and are correctly not bean candidates; JobSubmissionProperties appears
                // only because @EnableConfigurationProperties binds it.
                assertThat(Arrays.stream(context.getBeanDefinitionNames())
                        .filter(name -> name.startsWith("com.vsergeychik")
                                || name.startsWith("carddemo")
                                || "webConfig".equals(name)
                                || "clock".equals(name)
                                || "carddemoJacksonCustomizer".equals(name)
                                || "jobSubmissionValidator".equals(name))
                        .sorted()
                        .toList())
                        .containsExactly(
                                "carddemo.job-submission-com.vsergeychik.carddemo.config.WebConfig"
                                        + "$JobSubmissionProperties",
                                "carddemoJacksonCustomizer",
                                "clock",
                                "com.vsergeychik.carddemo.config.WebConfig$CobolErrorHandler",
                                "jobSubmissionValidator",
                                "webConfig");
            });
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

        /** The logger the advice writes to. */
        private static final String ADVICE_LOGGER = CobolErrorHandler.class.getName();

        /**
         * Runs an action with {@code DEBUG} enabled on the advice's logger, restoring whatever was
         * configured before.
         *
         * @param action the assertion body to run at {@code DEBUG}
         * @throws Exception if the action does
         */
        private void atDebugLevel(final ThrowingAction action) throws Exception {
            final LoggingSystem logging = LoggingSystem.get(getClass().getClassLoader());
            final LoggerConfiguration previous = logging.getLoggerConfiguration(ADVICE_LOGGER);
            logging.setLogLevel(ADVICE_LOGGER, LogLevel.DEBUG);
            try {
                action.run();
            } finally {
                // Passing null restores inheritance, which is the correct restoration when the logger
                // had no explicit level of its own - as it does not here.
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
                        .as("and nowhere near the caller's response")
                        .doesNotContain("TAMT001")
                        .isEqualTo("{\"code\":\"" + CobolErrorHandler.MALFORMED_REQUEST_CODE
                                + "\",\"message\":\"" + CobolErrorHandler.MALFORMED_REQUEST_MESSAGE
                                + "\"}");
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

                // Jackson never reached a field, so the honest answer is that the document is at
                // fault rather than any member of it - the other arm of the same log statement.
                assertThat(output.getOut() + output.getErr())
                        .contains("none named - the document itself is unreadable");
                assertThat(body).isEqualTo("{\"code\":\""
                        + CobolErrorHandler.MALFORMED_REQUEST_CODE + "\",\"message\":\""
                        + CobolErrorHandler.MALFORMED_REQUEST_MESSAGE + "\"}");
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
                                        // A card-number-shaped value with a trailing non-digit, so it
                                        // genuinely fails to convert and Jackson's own message quotes
                                        // it back. A purely numeric string would be coerced happily
                                        // and the request would succeed, testing nothing.
                                        + "\"TAMT001\":\"4111111111111111X\"}"))
                        .andExpect(status().isBadRequest());

                final String logged = output.getOut() + output.getErr();

                assertThat(logged)
                        .contains(HttpMessageNotReadableException.class.getName());
                // The parser's message is where the payload is quoted, and a card number is exactly
                // the sort of value that would then sit in a log file (CWE-532).
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

                // The response is a function of the failure family alone, never of how the server
                // happens to be logging at the time.
                assertThat(verbose).isEqualTo(quiet);
            });
        }
    }

    /**
     * An action that may throw, so a test body can be handed to {@link
     * WithheldDiagnosticsReachTheServerLog#atDebugLevel}.
     */
    @FunctionalInterface
    private interface ThrowingAction {

        /**
         * Runs the action.
         *
         * @throws Exception if the action fails, which the calling test then reports
         */
        void run() throws Exception;
    }

    /**
     * A payload shaped like a real screen projection, used by the JSON tests.
     *
     * <p>The component names are upper-case on purpose: they are the actual named {@code DFHMDF}
     * labels of {@code app/bms/COSGN00.bms} ({@code TRNNAME}, {@code CURDATE}, {@code PGMNAME},
     * {@code ERRMSG}) and {@code app/bms/COACTUP.bms} ({@code ACCTSID}), so a naming strategy that
     * rewrote identifiers would be caught by a name this repository actually uses rather than by a
     * synthetic one.
     *
     * @param TRNNAME the {@code CC00} transaction identifier field, {@code TRNNAMEI PIC X(4)}
     * @param CURDATE the screen date field, {@code CURDATEI PIC X(8)}
     * @param PGMNAME the program-name field, {@code PGMNAMEI PIC X(8)}
     * @param ERRMSG  the error-message field, {@code ERRMSGI PIC X(78)}
     * @param ACCTSID the account-identifier field of the account-update screen
     * @param TAMT001 a monetary amount, standing for the {@code PIC S9(10)V99} family that every
     *                money field in this estate belongs to
     */
    private record ScreenPayload(
            String TRNNAME,
            String CURDATE,
            String PGMNAME,
            String ERRMSG,
            String ACCTSID,
            BigDecimal TAMT001) {
    }

    /**
     * A payload with one nested object, used to prove that an empty string is not silently turned
     * into a missing object.
     *
     * @param nested the nested screen projection
     */
    private record NestingPayload(ScreenPayload nested) {
    }

    /**
     * A request payload carrying the sign-on screen's own field width.
     *
     * <p>{@code @Size(max = 8)} is not an arbitrary bound: {@code app/cpy-bms/COSGN00.CPY} declares
     * {@code USERIDI PIC X(8)}, and the {@code USERID} field of {@code app/bms/COSGN00.bms} is
     * {@code LENGTH=8}. That is the rule stated in section 4.2 of this file's specification - a
     * validation length always traces to an {@code xxxI} picture clause - expressed as the smallest
     * payload that can demonstrate it.
     *
     * @param USERID the sign-on user identifier, at most eight characters
     * @param PASSWD the sign-on password, at most eight characters. It stays plaintext, exactly as
     *               {@code COSGN00C} compares {@code SEC-USR-PWD PIC X(08)} against {@code USRSEC};
     *               hashing it would be a behaviour change and would need a framework this
     *               migration excludes (practice B6, gate G41)
     */
    private record SignOnFixturePayload(
            @Size(max = 8) String USERID,
            @Size(max = 8) String PASSWD) {
    }

    /**
     * The throwaway controller the advice is driven over.
     *
     * <p>It exists so that every {@code @ExceptionHandler} in {@link CobolErrorHandler} can be
     * reached through the real dispatcher without importing any of the 17 production controllers -
     * each of which owns its own test in its own domain package, and each of which would drag a
     * service and a repository into a configuration test. Every endpoint does exactly one thing:
     * raise one failure, or accept one payload.
     *
     * <p>Path variables are annotated with an explicit name rather than relying on parameter-name
     * retention. The module's build does pass {@code -parameters}, so the implicit form would work
     * today; naming them makes this fixture independent of that setting, which matters because a
     * failure to resolve a parameter name surfaces as an {@code IllegalArgumentException} and would
     * therefore be answered by the very handler under test - a false pass that is genuinely hard to
     * see.
     */
    @RestController
    private static final class ScreenFixtureController {

        /** The path prefix every fixture endpoint shares. */
        private static final String BASE = "/webconfig-fixture";

        /**
         * Raises an abend carrying both {@code ABCODE} and {@code TIMING}, as the eight standard
         * {@code CALL 'CEE3ABD'} sites do.
         *
         * @param returnCode the {@code RETURN-CODE} the abending paragraph had set
         * @return never returns; the abend always propagates
         */
        @GetMapping(BASE + "/abend/{returnCode}")
        String abendWithParameters(@PathVariable("returnCode") final int returnCode) {
            throw AbendException.standard(WITHHELD_PROGRAM, returnCode, WITHHELD_REASON);
        }

        /**
         * Raises an abend carrying neither {@code ABCODE} nor {@code TIMING}, which is the shape of
         * the {@code CBSTM03A} site at {@code app/cbl/CBSTM03A.CBL:923} - it sets neither argument.
         *
         * @param returnCode the {@code RETURN-CODE} the abending paragraph had set
         * @return never returns; the abend always propagates
         */
        @GetMapping(BASE + "/abend-bare/{returnCode}")
        String abendWithoutParameters(@PathVariable("returnCode") final int returnCode) {
            throw AbendException.withoutAbendParameters(WITHHELD_PROGRAM, returnCode,
                    WITHHELD_REASON);
        }

        /**
         * Raises the failure a width or shape guard produces when a value does not fit its
         * {@code PICTURE} clause.
         *
         * @return never returns
         */
        @GetMapping(BASE + "/rejected-value")
        String rejectedValue() {
            throw new IllegalArgumentException(
                    "CARD-NUM is PIC X(16) but '4111111111111111X' is 17 characters");
        }

        /**
         * Raises the failure a missing or contradictory dataset binding produces.
         *
         * @return never returns
         */
        @GetMapping(BASE + "/state-fault")
        String stateFault() {
            throw new IllegalStateException(
                    "carddemo.datasets.acctdat is unbound; AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS");
        }

        /**
         * Raises a failure reaching a dataset, which is distinct from a record simply being absent.
         *
         * @return never returns
         */
        @GetMapping(BASE + "/dataset-failure")
        String datasetFailure() {
            throw new DataAccessResourceFailureException(
                    "could not obtain a connection for AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS");
        }

        /**
         * Raises a constraint violation from outside request-body binding, as a validated path
         * variable or service argument would.
         *
         * @return never returns
         */
        @GetMapping(BASE + "/violation")
        String violation() {
            throw new ConstraintViolationException("USERID exceeds PIC X(8)", Set.of());
        }

        /**
         * Raises a failure no handler declares, so the status-preserving catch-all claims it.
         *
         * @return never returns
         */
        @GetMapping(BASE + "/unclaimed")
        String unclaimed() {
            throw new UnsupportedOperationException(
                    "internal detail that must not reach a response body");
        }

        /**
         * Accepts a numeric path variable, so a non-numeric one produces a conversion failure.
         *
         * @param accountId the eleven-digit account identifier
         * @return the identifier, echoed, when conversion succeeds
         */
        @GetMapping(BASE + "/accounts/{accountId}")
        String typedPathVariable(@PathVariable("accountId") final long accountId) {
            return String.valueOf(accountId);
        }

        /**
         * Accepts a validated request body, so a width breach produces a validation failure.
         *
         * @param payload the sign-on payload
         * @return the accepted user identifier
         */
        @PostMapping(BASE + "/signon")
        String signOn(@Valid @RequestBody final SignOnFixturePayload payload) {
            return payload.USERID();
        }

        /**
         * Accepts a screen payload, so a malformed body produces a parse failure.
         *
         * @param payload the screen payload
         * @return the accepted transaction identifier
         */
        @PostMapping(BASE + "/screen")
        String screen(@RequestBody final ScreenPayload payload) {
            return payload.TRNNAME();
        }
    }
}
