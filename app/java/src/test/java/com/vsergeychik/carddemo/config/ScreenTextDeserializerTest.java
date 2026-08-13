package com.vsergeychik.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.ScreenInputRejectedException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
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
 * {@link WebConfig.ScreenTextDeserializer} - the one place every inbound JSON string is judged.
 */
@DisplayName("ScreenTextDeserializer - the screen-text judgement at the JSON boundary")
class ScreenTextDeserializerTest {
    private static final Charset TEST_PROFILE_CHARSET = StandardCharsets.US_ASCII;

    private static final ObjectMapper MAPPER = mapper();

    private static final String SENSITIVE = "4111111111111111";

    private static ObjectMapper mapper() {
        SimpleModule module = new SimpleModule();
        module.addDeserializer(String.class,
                new WebConfig.ScreenTextDeserializer(new FixedWidthCodec(TEST_PROFILE_CHARSET)));
        return new ObjectMapper().registerModule(module);
    }

    private static Map<String, String> readFields(String json) throws Exception {
        return MAPPER.readValue(json, MAPPER.getTypeFactory()
                .constructMapType(LinkedHashMap.class, String.class, String.class));
    }

    record Screen(String fname, Nested nested, List<String> rows) {
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
        @DisplayName("an empty string is accepted: there is no character to judge")
        void anEmptyStringIsAccepted() throws Exception {
            assertThat(readFields("{\"fname\":\"\"}")).containsEntry("fname", "");
        }
    }

    @Nested
    @DisplayName("A member that is not character data is refused rather than coerced")
    class NotCharacterData {
        @ParameterizedTest(name = "{0} is refused, not coerced")
        @ValueSource(strings = {"11", "1.5", "-1", "0", "true", "false"})
        @DisplayName("a number and a boolean are each refused: every payload member projects a PIC X(n) "
                + "item, so it is character data or it is nothing. An object or an array is refused too, "
                + "through Jackson's own unexpected-token path - see StructuredValues")
        void everyNonStringTokenIsRefused(String json) {
            assertThatExceptionOfType(ScreenInputRejectedException.class)
                    .isThrownBy(() -> unwrap("{\"acctsid\":" + json + "}"))
                    .satisfies(rejected -> {
                        assertThat(rejected.member()).contains("acctsid");
                        assertThat(rejected.reason())
                                .isEqualTo(ScreenInputRejectedException.Reason.NOT_CHARACTER_DATA);
                    });
        }

        @Test
        @DisplayName("the diagnostic names the token shape that arrived, and the published answer does "
                + "not - it says only that the member must be sent as a string")
        void theTokenShapeIsADiagnosticAndNotPublished() {
            assertThatExceptionOfType(ScreenInputRejectedException.class)
                    .isThrownBy(() -> unwrap("{\"acctsid\":11}"))
                    .satisfies(rejected -> {
                        assertThat(rejected.getMessage()).contains("VALUE_NUMBER_INT");
                        assertThat(rejected.publicDetail())
                                .contains("acctsid")
                                .contains("must be sent as a JSON string")
                                .doesNotContain("VALUE_NUMBER_INT")
                                .doesNotContain("PIC X");
                    });
        }

        @Test
        @DisplayName("a nested member is refused by the same rule and named by its own spelling")
        void aNestedNonStringIsRefused() {
            assertThatExceptionOfType(ScreenInputRejectedException.class)
                    .isThrownBy(() -> unwrapInto(
                            "{\"fname\":\"JOHN\",\"nested\":{\"acctid\":11}}", Screen.class))
                    .satisfies(rejected -> assertThat(rejected.member()).contains("acctid"));
        }

        @Test
        @DisplayName("an explicit null is still not a refusal: it is the untransmitted field every "
                + "screen already handles")
        void anExplicitNullIsStillAccepted() throws Exception {
            assertThat(readFields("{\"acctsid\":null}")).containsEntry("acctsid", null);
        }
    }

    @Nested
    @DisplayName("A character the screen code page cannot represent is refused, on every request family")
    class Unrepresentable {
        @ParameterizedTest(name = "U+{0} is refused against US-ASCII")
        @ValueSource(strings = {"00D1", "00E9", "20AC", "4E2D"})
        @DisplayName("a character outside the stated single-byte code page is refused at the boundary, "
                + "so all seventeen request families are judged and not the three that sweep their map")
        void aCharacterOutsideTheCodePageIsRefused(String hex) {
            String body = "{\"acslnam\":\"SM\\u" + hex + "TH\"}";

            assertThatExceptionOfType(ScreenInputRejectedException.class)
                    .isThrownBy(() -> unwrap(body))
                    .satisfies(rejected -> {
                        assertThat(rejected.member()).contains("acslnam");
                        assertThat(rejected.reason())
                                .isEqualTo(ScreenInputRejectedException.Reason.UNSUPPORTED_CHARACTER);
                        assertThat(rejected.getMessage()).contains("U+" + hex).contains("US-ASCII");
                        assertThat(rejected.publicDetail())
                                .doesNotContain("U+" + hex)
                                .doesNotContain("US-ASCII");
                    });
        }

        @Test
        @DisplayName("the same character is accepted when the deployment states a code page that can "
                + "represent it, because the judgement is the deployment's code page and not ASCII")
        void aCodePageThatCanRepresentItAcceptsIt() throws Exception {
            SimpleModule module = new SimpleModule();
            module.addDeserializer(String.class,
                    new WebConfig.ScreenTextDeserializer(new FixedWidthCodec(Charset.forName("IBM037"))));
            ObjectMapper ebcdic = new ObjectMapper().registerModule(module);

            assertThat(ebcdic.readValue("\"SM\u00d1TH\"", String.class)).isEqualTo("SM\u00d1TH");
        }

        @Test
        @DisplayName("a nested communication-area member is judged too, which no controller sweep covered")
        void aNestedMemberIsJudged() {
            assertThatExceptionOfType(ScreenInputRejectedException.class)
                    .isThrownBy(() -> unwrapInto(
                            "{\"fname\":\"JOHN\",\"nested\":{\"acctid\":\"\\u00d1\"}}", Screen.class))
                    .satisfies(rejected -> assertThat(rejected.member()).contains("acctid"));
        }

        @Test
        @DisplayName("never echoes the rejected value, even when it is a card number")
        void neverEchoesTheValue() {
            assertThatExceptionOfType(ScreenInputRejectedException.class)
                    .isThrownBy(() -> unwrap("{\"cardsid\":\"" + SENSITIVE + "\\u00d1\"}"))
                    .satisfies(rejected -> {
                        assertThat(rejected.getMessage()).doesNotContain(SENSITIVE);
                        assertThat(rejected.publicDetail()).doesNotContain(SENSITIVE);
                    });
        }

        @Test
        @DisplayName("a code page is required rather than defaulted from the platform")
        void aCodePageIsRequired() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new WebConfig.ScreenTextDeserializer(null))
                    .withMessageContaining("code page");
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
                            .contains(WebConfig.ScreenTextDeserializer.UNNAMED_MEMBER));
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
                            .contains(WebConfig.ScreenTextDeserializer.UNNAMED_MEMBER));
        }
    }

    @Nested
    @DisplayName("A shape no screen field can carry is refused, not bound as an absent field")
    class RefusedShapes {
        @ParameterizedTest(name = "a screen field written as {0}")
        @ValueSource(strings = {"{}", "{\"a\":\"JOHN\"}", "[]", "[\"JOHN\"]", "[[\"JOHN\"]]"})
        @DisplayName("an object or an array where a PIC X(n) field belongs is refused")
        void aStructuredValueIsRefused(String shape) {
            assertThatExceptionOfType(MismatchedInputException.class)
                    .isThrownBy(() -> readFields("{\"fname\":" + shape + "}"))
                    .satisfies(mismatch -> assertThat(mismatch.getTargetType()).isEqualTo(String.class));
        }

        @Test
        @DisplayName("the refusal is a mapping failure, which is what makes it a 400 rather than a 500")
        void theRefusalIsAMappingFailure() {
            assertThatExceptionOfType(JsonMappingException.class)
                    .isThrownBy(() -> readFields("{\"fname\":{\"a\":\"JOHN\"}}"));
        }

        @Test
        @DisplayName("the refusal names the member's position and never the value it was given")
        void theRefusalNamesThePositionAndNotTheValue() {
            assertThatExceptionOfType(JsonMappingException.class)
                    .isThrownBy(() -> readFields("{\"fname\":\"JOHN\",\"lname\":{\"x\":\"" + SENSITIVE
                            + "\"}}"))
                    .satisfies(mapping -> {
                        assertThat(mapping.getPath()).isNotEmpty();
                        assertThat(mapping.getPath().get(mapping.getPath().size() - 1).getFieldName())
                                .isEqualTo("lname");
                    });
        }

        @Test
        @DisplayName("a nested member of the communication area is refused the same way")
        void aStructuredNestedMemberIsRefused() {
            assertThatExceptionOfType(MismatchedInputException.class)
                    .isThrownBy(() -> MAPPER.readValue(
                            "{\"fname\":\"JOHN\",\"nested\":{\"acctid\":[\"1\"]}}", Screen.class));
        }

        @Test
        @DisplayName("the application's own mapper refuses it too, so all seventeen routes are covered")
        void theApplicationMapperRefusesItAsWell() {
            Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
            new WebConfig().carddemoJacksonCustomizer(TEST_PROFILE_CHARSET).customize(builder);
            ObjectMapper judged = builder.build();

            assertThatExceptionOfType(MismatchedInputException.class)
                    .isThrownBy(() -> judged.readValue("{\"fname\":{}}", judged.getTypeFactory()
                            .constructMapType(LinkedHashMap.class, String.class, String.class)));
        }

        @Test
        @DisplayName("the quoted forms still bind, so nothing a terminal can send stops binding")
        void scalarsStillBind() throws Exception {
            assertThat(readFields("{\"acctsid\":\"11\",\"flag\":\"true\"}"))
                    .containsEntry("acctsid", "11")
                    .containsEntry("flag", "true");
        }
    }

    @Nested
    @DisplayName("What the configured code page cannot represent is refused, on every route")
    class CodePage {
        @Test
        @DisplayName("an accented letter US-ASCII cannot encode is refused, naming the member and the "
                + "code point rather than the value")
        void refusesAnUnrepresentableCharacter() {
            assertThatExceptionOfType(ScreenInputRejectedException.class)
                    .isThrownBy(() -> unwrap("{\"crdname\":\"JOS\u00C9\"}"))
                    .satisfies(rejected -> {
                        assertThat(rejected.member()).contains("crdname");
                        assertThat(rejected.getMessage())
                                .contains("U+00C9")
                                .contains("US-ASCII")
                                .contains("cannot represent")
                                .doesNotContain("JOS");
                    });
        }

        @ParameterizedTest(name = "U+{0} is refused as unrepresentable rather than transcoded")
        @ValueSource(strings = {"00D1", "20AC", "4E2D"})
        @DisplayName("an accented letter, a currency sign and a CJK ideograph are each refused, so no "
                + "value reaches a record that String.getBytes would have written as '?'")
        void refusesEveryUnrepresentableClass(String hex) {
            assertThatExceptionOfType(ScreenInputRejectedException.class)
                    .isThrownBy(() -> unwrap("{\"fname\":\"A\\u" + hex + "B\"}"))
                    .satisfies(rejected -> assertThat(rejected.getMessage()).contains("U+" + hex));
        }

        @Test
        @DisplayName("a member nested inside the communication area is judged too, so the code page "
                + "governs carried state as well as screen fields")
        void judgesNestedMembers() {
            assertThatExceptionOfType(ScreenInputRejectedException.class)
                    .isThrownBy(() -> unwrapInto(
                            "{\"fname\":\"JOHN\",\"nested\":{\"acctid\":\"1\u00E91\"}}", Screen.class))
                    .satisfies(rejected -> assertThat(rejected.member()).contains("acctid"));
        }

        @Test
        @DisplayName("the judgement follows the code page the deserializer was given: IBM037 represents "
                + "an accented letter US-ASCII does not, and the boundary must not refuse what the "
                + "write path would accept")
        void judgesAgainstTheCodePageItWasGiven() throws Exception {
            SimpleModule ebcdic = new SimpleModule();
            ebcdic.addDeserializer(String.class,
                    new WebConfig.ScreenTextDeserializer(new FixedWidthCodec(Charset.forName("IBM037"))));
            ObjectMapper underIbm037 = new ObjectMapper().registerModule(ebcdic);

            assertThat(underIbm037.readValue("\"JOS\u00C9\"", String.class)).isEqualTo("JOS\u00C9");
            assertThatExceptionOfType(ScreenInputRejectedException.class)
                    .isThrownBy(() -> unwrapInto("\"JOS\u00C9\"", String.class));
        }

        @Test
        @DisplayName("the deserializer the production customizer registers carries the profile's own "
                + "page, so all seventeen routes are judged against the page in force")
        void theRegisteredDeserializerCarriesTheActivePage() {
            Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
            new WebConfig().carddemoJacksonCustomizer(TEST_PROFILE_CHARSET).customize(builder);
            ObjectMapper judged = builder.build();

            assertThatExceptionOfType(ScreenInputRejectedException.class).isThrownBy(() -> {
                try {
                    judged.readValue("\"JOS\u00C9\"", String.class);
                } catch (Exception failure) {
                    throw rootScreenInput(failure);
                }
            });
        }
    }

    @Nested
    @DisplayName("The EIBAID member carries a byte, not screen text, so the code page does not judge it")
    class TheAttentionIdentifierMember {
        @Test
        @DisplayName("DFHPF3 travels as U+00F3 under US-ASCII, which cannot spell it - because it is a "
                + "byte of the exec interface block and not a PIC X(n) field")
        void theAidMemberIsNotJudgedAgainstTheCodePage() throws Exception {
            String image = PfKeyResolver.aidImage(CicsAid.DFHPF3);

            assertThat(readFields(bodyCarryingAid(image))).containsEntry("aid", image);
            assertThat(image.codePointAt(0)).isEqualTo(CicsAid.DFHPF3 & 0xFF);
        }

        @Test
        @DisplayName("every one of the 256 bytes survives the boundary, so no attention identifier is "
                + "unreachable over HTTP - including DFHTRIG at X'7F', which is a control code point")
        void everyAidByteSurvives() throws Exception {
            for (int unsigned = 0; unsigned <= 0xFF; unsigned++) {
                String image = PfKeyResolver.aidImage((byte) unsigned);

                assertThat(readFields(bodyCarryingAid(image)))
                        .as("EIBAID X'%02X' must reach the controller unchanged", unsigned)
                        .containsEntry("aid", image);
            }
            assertThat(CicsAid.DFHTRIG).isEqualTo((byte) 0x7F);
        }

        @Test
        @DisplayName("the exemption is by member name and does not leak to its neighbours in the body")
        void neighbouringMembersAreStillJudged() {
            assertThatExceptionOfType(ScreenInputRejectedException.class)
                    .isThrownBy(() -> unwrap(
                            "{\"aid\":\"" + PfKeyResolver.aidImage(CicsAid.DFHPF3)
                                    + "\",\"fname\":\"JOS\u00C9\"}"))
                    .satisfies(rejected -> assertThat(rejected.member()).contains("fname"));
        }

        private String bodyCarryingAid(String image) throws Exception {
            return MAPPER.writeValueAsString(Map.of("aid", image));
        }

        @Test
        @DisplayName("the exemption is by the one spelling every request record uses, and every one of "
                + "them still uses it")
        void everyRequestRecordSpellsItTheSameWay() {
            List<Class<?>> requests = List.of(
                    com.vsergeychik.carddemo.user.dto.SignOnRequest.class,
                    com.vsergeychik.carddemo.user.dto.UserAddRequest.class,
                    com.vsergeychik.carddemo.user.dto.UserListRequest.class,
                    com.vsergeychik.carddemo.user.dto.UserUpdateRequest.class,
                    com.vsergeychik.carddemo.user.dto.UserDeleteRequest.class,
                    com.vsergeychik.carddemo.transaction.dto.ReportRequestRequest.class,
                    com.vsergeychik.carddemo.transaction.dto.TransactionAddRequest.class,
                    com.vsergeychik.carddemo.transaction.dto.TransactionViewRequest.class);

            for (Class<?> request : requests) {
                assertThat(hasAidAccessor(request))
                        .as("%s must expose the EIBAID carrier as '%s', which is the name the boundary "
                                + "exempts from the code-page judgement", request.getSimpleName(),
                                WebConfig.ScreenTextDeserializer.ATTENTION_IDENTIFIER_MEMBER)
                        .isTrue();
            }
        }

        private boolean hasAidAccessor(Class<?> request) {
            String member = WebConfig.ScreenTextDeserializer.ATTENTION_IDENTIFIER_MEMBER;
            String getter = "get" + Character.toUpperCase(member.charAt(0)) + member.substring(1);
            for (java.lang.reflect.Method method : request.getMethods()) {
                if ((method.getName().equals(member) || method.getName().equals(getter))
                        && method.getParameterCount() == 0
                        && method.getReturnType() == String.class) {
                    return true;
                }
            }
            return false;
        }
    }

    @Nested
    @DisplayName("A structured value where a screen field belongs goes through Jackson's own path")
    class StructuredValues {
        @ParameterizedTest(name = "{0} where a PIC X field belongs is a mapping failure")
        @ValueSource(strings = {"{\"fname\":{\"a\":\"B\"}}", "{\"fname\":[\"A\"]}"})
        @DisplayName("an object or an array is refused by Jackson's own unexpected-token handling, "
                + "which names the member and the token rather than binding null")
        void aStructuredValueIsAMappingFailure(String body) {
            assertThatExceptionOfType(MismatchedInputException.class)
                    .isThrownBy(() -> readFields(body))
                    .satisfies(failure -> assertThat(failure.getMessage())
                            .contains("java.lang.String"));
        }

        @Test
        @DisplayName("a number and a boolean are refused rather than coerced, because a screen field is "
                + "character data or it is not a screen field")
        void scalarsAreRefusedRatherThanCoerced() {
            assertThatExceptionOfType(ScreenInputRejectedException.class)
                    .isThrownBy(() -> unwrap("{\"fname\":42}"))
                    .satisfies(rejected -> {
                        assertThat(rejected.member()).contains("fname");
                        assertThat(rejected.reason())
                                .isEqualTo(ScreenInputRejectedException.Reason.NOT_CHARACTER_DATA);
                    });
            assertThatExceptionOfType(ScreenInputRejectedException.class)
                    .isThrownBy(() -> unwrap("{\"lname\":true}"))
                    .satisfies(rejected -> assertThat(rejected.member()).contains("lname"));
        }
    }

    @Nested
    @DisplayName("The deserializer itself, driven directly")
    class DirectlyDriven {
        @Test
        @DisplayName("a JSON null token returns null rather than a value, so \"an absent field is spaces "
                + "on a terminal\" is left to the screen that owns the rule")
        void aNullTokenReturnsNull() throws Exception {
            try (com.fasterxml.jackson.core.JsonParser parser =
                    MAPPER.getFactory().createParser("null")) {
                parser.nextToken();

                assertThat(new WebConfig.ScreenTextDeserializer(
                        new FixedWidthCodec(TEST_PROFILE_CHARSET))
                        .deserialize(parser, null)).isNull();
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
            assertThatCode(() -> new Jackson2ObjectMapperBuilder().build()
                    .readValue("\"A\\u0009B\"", String.class))
                    .doesNotThrowAnyException();

            Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
            new WebConfig().carddemoJacksonCustomizer(TEST_PROFILE_CHARSET).customize(builder);
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

    private static void unwrapInto(String json, Class<?> type) {
        try {
            MAPPER.readValue(json, type);
        } catch (Exception failure) {
            throw rootScreenInput(failure);
        }
    }

    private static void unwrap(String json) {
        try {
            readFields(json);
        } catch (Exception failure) {
            throw rootScreenInput(failure);
        }
    }

    private static RuntimeException rootScreenInput(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ScreenInputRejectedException screenInput) {
                return screenInput;
            }
        }
        return new IllegalStateException("expected a ScreenInputRejectedException", failure);
    }
}
