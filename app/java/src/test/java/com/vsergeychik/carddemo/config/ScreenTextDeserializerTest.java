package com.vsergeychik.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.vsergeychik.carddemo.common.ScreenInputRejectedException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * {@link ScreenTextDeserializer} - the one place every inbound JSON string is judged.
 *
 * <p>The behaviour under test is what a caller may put into a {@code PIC X(n)} screen field over HTTP.
 * A 3270 sends the modified fields of a screen as graphic characters, so a TAB, a line feed, a DEL or a
 * null between data bytes describes a conversation that cannot have happened; before this class existed
 * such a value was stored verbatim into a parity-critical record, and a stored null became
 * indistinguishable from an unpainted field.
 *
 * <p>Two properties matter equally and both are asserted here: the values a real conversation
 * <em>does</em> produce are accepted - which is why a response full of {@code LOW-VALUES} remains a
 * legal next request - and the member named in the refusal is the one the caller spelled.
 */
@DisplayName("ScreenTextDeserializer - the screen-text judgement at the JSON boundary")
class ScreenTextDeserializerTest {

    /** A mapper carrying only this deserializer, so nothing else can account for an outcome. */
    private static final ObjectMapper MAPPER = mapper();

    /** A payload value carrying a card number, to prove it is never echoed. */
    private static final String SENSITIVE = "4111111111111111";

    private static ObjectMapper mapper() {
        SimpleModule module = new SimpleModule();
        module.addDeserializer(String.class, new ScreenTextDeserializer());
        return new ObjectMapper().registerModule(module);
    }

    /**
     * Reads a JSON object into a map of strings, which is what a screen payload is.
     *
     * @param json the body
     * @return the bound map
     * @throws Exception if the body cannot be read for a reason other than the judgement
     */
    private static Map<String, String> readFields(String json) throws Exception {
        return MAPPER.readValue(json, MAPPER.getTypeFactory()
                .constructMapType(LinkedHashMap.class, String.class, String.class));
    }

    /** A screen with a nested communication area, to prove nesting is judged and named. */
    record Screen(String fname, Nested nested, List<String> rows) {

        /** The nested part. */
        record Nested(String acctid) { }
    }

    @Nested
    @DisplayName("What a terminal could have sent is accepted, byte for byte")
    class Accepted {

        @Test
        @DisplayName("an ordinary value passes through unchanged, with no trimming and no padding")
        void ordinaryValuesPassThrough() throws Exception {
            assertThat(readFields("{\"fname\":\"  JOHN Q. PUBLIC-SMITH  \"}"))
                    .containsEntry("fname", "  JOHN Q. PUBLIC-SMITH  ");
        }

        @Test
        @DisplayName("a field that is entirely LOW-VALUES is accepted: BMS delivers an unmodified field "
                + "that way and this module renders an unpainted one that way, so a client echoing a "
                + "response it was given sends exactly this")
        void allLowValuesIsAccepted() throws Exception {
            String twenty = "\\u0000".repeat(20);

            assertThat(readFields("{\"fname\":\"" + twenty + "\"}"))
                    .containsEntry("fname", "\u0000".repeat(20));
        }

        @Test
        @DisplayName("data followed by a trailing run of LOW-VALUES is accepted: that is padding")
        void trailingLowValuesAreAccepted() throws Exception {
            assertThat(readFields("{\"fname\":\"JOHN" + "\\u0000".repeat(16) + "\"}"))
                    .containsEntry("fname", "JOHN" + "\u0000".repeat(16));
        }

        @Test
        @DisplayName("an explicit JSON null stays null rather than becoming a value")
        void anExplicitNullStaysNull() throws Exception {
            assertThat(readFields("{\"fname\":null}")).containsEntry("fname", null);
        }

        @Test
        @DisplayName("a number written where a string is expected still coerces, as Jackson's own "
                + "String deserializer does - only the judgement is added, never a conversion")
        void aNumberStillCoerces() throws Exception {
            assertThat(readFields("{\"acctsid\":11}")).containsEntry("acctsid", "11");
        }

        @Test
        @DisplayName("an empty string is accepted: there is no character to judge")
        void anEmptyStringIsAccepted() throws Exception {
            assertThat(readFields("{\"fname\":\"\"}")).containsEntry("fname", "");
        }
    }

    @Nested
    @DisplayName("What a terminal could not have sent is refused, naming the member")
    class Refused {

        @ParameterizedTest(name = "U+{0} among data is refused")
        @ValueSource(strings = {"0000", "0009", "000A", "000D", "001B", "007F", "0085"})
        @DisplayName("every C0 control, DEL and C1 is refused when it sits among data")
        void everyControlAmongDataIsRefused(String hex) {
            String body = "{\"fname\":\"A\\u" + hex + "B\"}";

            assertThatExceptionOfType(ScreenInputRejectedException.class)
                    .isThrownBy(() -> unwrap(body))
                    .satisfies(rejected -> {
                        assertThat(rejected.member()).contains("fname");
                        assertThat(rejected.getMessage())
                                .contains("U+" + hex)
                                .contains("control character");
                    });
        }

        @Test
        @DisplayName("the member named is the one at fault, not the first member of the body")
        void namesTheMemberAtFault() {
            assertThatExceptionOfType(ScreenInputRejectedException.class)
                    .isThrownBy(() -> unwrap("{\"fname\":\"JOHN\",\"lname\":\"D\\u0009E\"}"))
                    .satisfies(rejected -> assertThat(rejected.member()).contains("lname"));
        }

        @Test
        @DisplayName("a member nested inside the communication area is named by its own spelling")
        void namesANestedMember() {
            assertThatExceptionOfType(ScreenInputRejectedException.class)
                    .isThrownBy(() -> unwrapInto(
                            "{\"fname\":\"JOHN\",\"nested\":{\"acctid\":\"1\\u000A1\"}}", Screen.class))
                    .satisfies(rejected -> assertThat(rejected.member()).contains("acctid"));
        }

        @Test
        @DisplayName("an array element is attributed to the array's own member rather than to nothing")
        void namesTheArrayMember() {
            assertThatExceptionOfType(ScreenInputRejectedException.class)
                    .isThrownBy(() -> unwrapInto(
                            "{\"rows\":[\"OK\",\"B\\u0009D\"]}", Screen.class))
                    .satisfies(rejected -> assertThat(rejected.member()).contains("rows"));
        }

        @Test
        @DisplayName("a bare string body, which no screen accepts, still names something rather than "
                + "composing a message with a null in it")
        void aBareStringBodyStillNamesSomething() {
            assertThatExceptionOfType(ScreenInputRejectedException.class)
                    .isThrownBy(() -> unwrapInto("\"A\\u0009B\"", String.class))
                    .satisfies(rejected -> assertThat(rejected.member())
                            .contains(ScreenTextDeserializer.UNNAMED_MEMBER));
        }

        @Test
        @DisplayName("never echoes the rejected value, even when it is a card number")
        void neverEchoesTheValue() {
            assertThatExceptionOfType(ScreenInputRejectedException.class)
                    .isThrownBy(() -> unwrap("{\"cardsid\":\"" + SENSITIVE + "\\u0009\"}"))
                    .satisfies(rejected ->
                            assertThat(rejected.getMessage()).doesNotContain(SENSITIVE));
        }

        @Test
        @DisplayName("a member whose name is blank - legal JSON, no screen - still names something "
                + "rather than composing a message with a blank in it")
        void aBlankMemberNameFallsBack() {
            assertThatExceptionOfType(ScreenInputRejectedException.class)
                    .isThrownBy(() -> unwrap("{\" \":\"A\\u0009B\"}"))
                    .satisfies(rejected -> assertThat(rejected.member())
                            .contains(ScreenTextDeserializer.UNNAMED_MEMBER));
        }
    }

    @Nested
    @DisplayName("The deserializer itself, driven directly")
    class DirectlyDriven {

        @Test
        @DisplayName("a JSON null token returns null rather than a value, so \"an absent field is spaces "
                + "on a terminal\" is left to the screen that owns the rule")
        void aNullTokenReturnsNull() throws Exception {
            // Driven directly because a container may answer a null token from its own null provider
            // without consulting the value deserializer at all; the documented behaviour of THIS method
            // is what is asserted here.
            try (com.fasterxml.jackson.core.JsonParser parser =
                    MAPPER.getFactory().createParser("null")) {
                parser.nextToken();

                assertThat(new ScreenTextDeserializer().deserialize(parser, null)).isNull();
            }
        }
    }

    @Nested
    @DisplayName("It is registered on the application's own mapper, not only on this test's")
    class Registration {

        @Test
        @DisplayName("the customizer WebConfig publishes installs it, so all seventeen routes are "
                + "judged rather than the three that judge a code page")
        void theApplicationMapperCarriesIt() {
            // A bare mapper accepts a control character, so the outcome below is attributable to the
            // customizer and to nothing else in Spring's default configuration.
            assertThatCode(() -> new Jackson2ObjectMapperBuilder().build()
                    .readValue("\"A\\u0009B\"", String.class))
                    .doesNotThrowAnyException();

            Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
            new WebConfig().carddemoJacksonCustomizer().customize(builder);
            ObjectMapper judged = builder.build();

            assertThatExceptionOfType(ScreenInputRejectedException.class).isThrownBy(() -> {
                try {
                    judged.readValue("\"A\\u0009B\"", String.class);
                } catch (Exception failure) {
                    throw rootScreenInput(failure);
                }
            });
        }
    }

    /**
     * Reads a body into the given type and rethrows the screen-input refusal Jackson wrapped, so a test
     * can assert on it directly.
     *
     * <p>Jackson wraps a deserializer's failure in a {@code JsonMappingException} carrying the original
     * as its cause, which is exactly the shape {@code CobolErrorHandler.screenInputCause} unwraps at the
     * boundary; unwrapping it here asserts the same relation from the other side.
     *
     * @param json the body
     * @param type the type to bind
     * @throws ScreenInputRejectedException when the body carries a value no terminal could have sent
     * @throws RuntimeException             if the failure is anything else
     */
    private static void unwrapInto(String json, Class<?> type) {
        try {
            MAPPER.readValue(json, type);
        } catch (Exception failure) {
            throw rootScreenInput(failure);
        }
    }

    /**
     * Reads a body as a screen's flat field map and rethrows the refusal Jackson wrapped.
     *
     * @param json the body
     * @throws ScreenInputRejectedException when the body carries a value no terminal could have sent
     */
    private static void unwrap(String json) {
        try {
            readFields(json);
        } catch (Exception failure) {
            throw rootScreenInput(failure);
        }
    }

    /**
     * The screen-input refusal inside a wrapped failure.
     *
     * @param failure the failure Jackson raised
     * @return the refusal, rethrown as itself
     */
    private static RuntimeException rootScreenInput(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ScreenInputRejectedException screenInput) {
                return screenInput;
            }
        }
        return new IllegalStateException("expected a ScreenInputRejectedException", failure);
    }
}
