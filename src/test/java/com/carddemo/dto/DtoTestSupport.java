package com.carddemo.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonFactoryBuilder;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Shared, framework-free test support for the DTO unit-test package
 * ({@code com.carddemo.dto}).
 *
 * <p>This is the foundational helper on which every DTO unit test in this package
 * builds. It centralises three concerns so that individual tests stay small and
 * their assertions stay trustworthy:</p>
 * <ol>
 *   <li>a Jackson {@link ObjectMapper} configured to <strong>mirror the production
 *       serialization contract exactly</strong> (see {@link #OBJECT_MAPPER}), so
 *       JSON assertions in tests match what the running application emits;</li>
 *   <li>a shared Jakarta Bean-Validation {@link Validator} (see {@link #VALIDATOR})
 *       for exercising the {@code @NotBlank}/{@code @Size}/etc. constraints declared
 *       on the DTO records;</li>
 *   <li>reflection and security assertion helpers that prove sensitive fields
 *       (password, CVV) never appear on a response DTO and that Primary Account
 *       Numbers (PANs) are masked to their last four characters.</li>
 * </ol>
 *
 * <p><strong>This class is deliberately NOT a JUnit test.</strong> It contains no
 * {@code @Test} methods, is not named {@code *Test}/{@code *Tests}, and carries no
 * Spring or test-slice annotations, so Surefire never executes it and it does not
 * distort coverage semantics. It loads no Spring context: the mapper and validator
 * are built by hand from the same building blocks Spring Boot uses, which keeps the
 * helper fast and self-contained.</p>
 *
 * <p>The field widths and decimal scales enforced by these helpers derive from the
 * read-only legacy COBOL copybooks (referenced by source SHA {@code 27d6c6f}, not
 * copied into the target): {@code CVACT01Y} account money fields are
 * {@code PIC S9(10)V99} (scale&nbsp;2), {@code CVTRA05Y} {@code TRAN-AMT} is
 * {@code PIC S9(09)V99} (scale&nbsp;2), and {@code CSUSR01Y} {@code SEC-USR-PWD}
 * ({@code PIC X(08)}) is the plaintext password that response DTOs must never
 * expose.</p>
 *
 * <p>The type is a final utility class with a private constructor; it holds only
 * immutable, thread-safe shared state and is never instantiated.</p>
 */
public final class DtoTestSupport {

    /**
     * Shared, immutable Jackson {@link ObjectMapper} configured to mirror the
     * production serialization contract.
     *
     * <p>Production {@code com.carddemo.config.WebConfig} augments Spring Boot's
     * auto-configured mapper via a {@code Jackson2ObjectMapperBuilderCustomizer}
     * that writes {@code BigDecimal} values in plain (non-scientific) notation. A
     * bare {@code @JsonTest} slice does not load {@code WebConfig}, so this helper
     * reproduces the production mapper directly. Building it through
     * {@link Jackson2ObjectMapperBuilder#json()} registers the well-known modules
     * present on the classpath — including {@code JavaTimeModule} — so
     * {@code java.time} values are understood. To match the production contract
     * (Spring Boot disables date-as-timestamp output on its auto-configured
     * mapper), a per-type {@link JsonFormat} config-override pins
     * {@link LocalDate}, {@link LocalDateTime}, and {@link OffsetDateTime} to
     * {@link JsonFormat.Shape#STRING}, so they serialize as ISO-8601 strings
     * (for example {@code "2024-01-31"}) rather than numeric timestamp arrays.
     * This uses only non-deprecated public API: in Jackson&nbsp;2.19 the former
     * {@code SerializationFeature.WRITE_DATES_AS_TIMESTAMPS} toggle is deprecated
     * and its {@code DateTimeFeature} replacement is not yet shipped, so the
     * config-override is the warning-free way to obtain the same behaviour.
     * Enabling {@link StreamWriteFeature#WRITE_BIGDECIMAL_AS_PLAIN} on the
     * underlying {@link JsonFactory} guarantees money serializes as
     * {@code 100.00} rather than {@code 1.0E2}.</p>
     */
    public static final ObjectMapper OBJECT_MAPPER = buildObjectMapper();

    /**
     * Shared Jakarta Bean-Validation {@link Validator} used to exercise the
     * constraint annotations declared on the DTO records.
     */
    public static final Validator VALIDATOR = buildValidator();

    /** Number of trailing characters a masked PAN leaves visible. */
    private static final int VISIBLE_PAN_CHARACTERS = 4;

    /**
     * A canonical, scale-2 monetary sample value ({@code 100.00}) matching the
     * {@code PIC S9(n)V99} money precision of the legacy copybooks. Shared so DTO
     * tests can construct valid money components without repeating the literal.
     */
    private static final String SAMPLE_MONEY_LITERAL = "100.00";

    private DtoTestSupport() {
        // Utility class: prevent instantiation.
    }

    /**
     * Builds the production-mirroring {@link ObjectMapper}. See
     * {@link #OBJECT_MAPPER} for the rationale behind each setting.
     *
     * @return a fully configured mapper instance
     */
    private static ObjectMapper buildObjectMapper() {
        // The BigDecimal-as-plain behaviour is applied at the streaming-generator
        // level via StreamWriteFeature (the non-deprecated home of this setting in
        // Jackson 2.19+, replacing SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN).
        // It produces byte-identical output to the production WebConfig mapper,
        // which enables the very same underlying feature.
        JsonFactory factory = new JsonFactoryBuilder()
                .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
                .build();
        ObjectMapper mapper = Jackson2ObjectMapperBuilder.json()
                .factory(factory)
                .build();
        // Spring Boot's auto-configured mapper emits java.time values as ISO-8601
        // strings (date-as-timestamp output disabled). Reproduce that here with a
        // per-type JsonFormat config-override pinning STRING shape, which forces the
        // JavaTimeModule serializers to their ISO textual form without relying on the
        // deprecated SerializationFeature.WRITE_DATES_AS_TIMESTAMPS toggle.
        JsonFormat.Value isoString = JsonFormat.Value.forShape(JsonFormat.Shape.STRING);
        mapper.configOverride(LocalDate.class).setFormat(isoString);
        mapper.configOverride(LocalDateTime.class).setFormat(isoString);
        mapper.configOverride(OffsetDateTime.class).setFormat(isoString);
        return mapper;
    }

    /**
     * Builds the shared {@link Validator} from the default provider. The
     * {@link ValidatorFactory} is closed in a try-with-resources block to avoid a
     * resource-leak warning; the returned {@link Validator} remains usable after
     * the factory is closed.
     *
     * @return the default bean-validation validator
     */
    private static Validator buildValidator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            return factory.getValidator();
        }
    }

    /**
     * Returns the shared production-mirroring {@link ObjectMapper}.
     *
     * @return the shared mapper (the same instance as {@link #OBJECT_MAPPER})
     */
    public static ObjectMapper objectMapper() {
        return OBJECT_MAPPER;
    }

    /**
     * Validates the supplied object with the shared {@link #VALIDATOR}.
     *
     * @param object the object to validate; must not be {@code null}
     * @param <T>    the type of the validated object
     * @return the (possibly empty) set of constraint violations
     */
    public static <T> Set<ConstraintViolation<T>> validate(T object) {
        return VALIDATOR.validate(object);
    }

    /**
     * Serializes a value to its JSON representation using {@link #OBJECT_MAPPER}.
     *
     * <p>Any {@link JsonProcessingException} is wrapped in an {@link AssertionError}
     * so callers in test code need no checked-exception handling.</p>
     *
     * @param value the value to serialize (may be {@code null}, which serializes to
     *              the JSON literal {@code null})
     * @return the JSON string
     */
    public static String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new AssertionError("Failed to serialize value to JSON: " + value, e);
        }
    }

    /**
     * Deserializes JSON into an instance of the given (non-generic) type using
     * {@link #OBJECT_MAPPER}. Checked exceptions are wrapped in an
     * {@link AssertionError}.
     *
     * @param json the JSON to deserialize; must not be {@code null}
     * @param type the target type
     * @param <T>  the target type parameter
     * @return the deserialized instance
     */
    public static <T> T fromJson(String json, Class<T> type) {
        try {
            return OBJECT_MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new AssertionError("Failed to deserialize JSON to " + type.getName() + ": " + json, e);
        }
    }

    /**
     * Deserializes JSON into an instance of a generic type using a
     * {@link TypeReference} (for example {@code PageResponse<CardListItem>}). Using
     * a {@link TypeReference} preserves the full generic type so no unchecked cast
     * is required. Checked exceptions are wrapped in an {@link AssertionError}.
     *
     * @param json    the JSON to deserialize; must not be {@code null}
     * @param typeRef the target type token
     * @param <T>     the target type parameter
     * @return the deserialized instance
     */
    public static <T> T fromJson(String json, TypeReference<T> typeRef) {
        try {
            return OBJECT_MAPPER.readValue(json, typeRef);
        } catch (JsonProcessingException e) {
            throw new AssertionError("Failed to deserialize JSON to " + typeRef.getType() + ": " + json, e);
        }
    }

    /**
     * Serializes then deserializes a value, returning the round-tripped instance.
     * This is the core building block of every DTO JSON round-trip test.
     *
     * @param value the value to round-trip
     * @param type  the target type
     * @param <T>   the target type parameter
     * @return the value after a serialize/deserialize cycle
     */
    public static <T> T roundTrip(T value, Class<T> type) {
        return fromJson(toJson(value), type);
    }

    /**
     * Returns the set of component names of a record type, lower-cased with
     * {@link Locale#ROOT} for case-insensitive comparison.
     *
     * <p>All production DTOs are Java records, so this reads
     * {@link Class#getRecordComponents()}. If the supplied type is not a record it
     * falls back to the type's declared instance fields (static and synthetic
     * fields are excluded so that constants and compiler-generated members do not
     * pollute the result).</p>
     *
     * @param type the DTO type to inspect
     * @return an insertion-ordered set of lower-cased component (or field) names
     */
    public static Set<String> componentNames(Class<?> type) {
        Set<String> names = new LinkedHashSet<>();
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                names.add(component.getName().toLowerCase(Locale.ROOT));
            }
        } else {
            for (Field field : type.getDeclaredFields()) {
                if (!field.isSynthetic() && !Modifier.isStatic(field.getModifiers())) {
                    names.add(field.getName().toLowerCase(Locale.ROOT));
                }
            }
        }
        return names;
    }

    /**
     * Asserts that a DTO type exposes no component whose name contains (case
     * insensitively) any of the forbidden tokens. Use it to prove that response
     * DTOs never surface secrets such as {@code password}/{@code passwordHash}/
     * {@code pwd} or {@code cvv}.
     *
     * @param type      the DTO type to inspect
     * @param forbidden the forbidden name tokens (matched as case-insensitive
     *                  substrings of each component name)
     */
    public static void assertNoComponentNamed(Class<?> type, String... forbidden) {
        Set<String> names = componentNames(type);
        for (String name : names) {
            for (String token : forbidden) {
                String forbiddenToken = token.toLowerCase(Locale.ROOT);
                assertThat(name.contains(forbiddenToken))
                        .as("DTO %s must not expose a component matching the forbidden token '%s' "
                                + "(found component '%s')", type.getSimpleName(), token, name)
                        .isFalse();
            }
        }
    }

    /**
     * Asserts that a card-number value is masked so that only its last four
     * characters — expected to equal {@code expectedLast4} — remain visible, with
     * no cleartext PAN digit leaking into the masked prefix.
     *
     * <p>The check is tolerant of the exact mask character (production uses
     * {@code '*'}) but strict that the portion before the last four characters
     * contains no digit at all.</p>
     *
     * @param value         the masked card number
     * @param expectedLast4 the four characters expected to remain visible
     */
    public static void assertPanMaskedLast4(String value, String expectedLast4) {
        assertThat(value).as("masked PAN").isNotNull().isNotBlank();
        assertThat(expectedLast4).as("expected visible last-4").isNotNull();
        assertThat(value.length())
                .as("masked PAN '%s' must be at least %d characters long", value, VISIBLE_PAN_CHARACTERS)
                .isGreaterThanOrEqualTo(VISIBLE_PAN_CHARACTERS);
        assertThat(value)
                .as("masked PAN must end with the visible last-4 '%s'", expectedLast4)
                .endsWith(expectedLast4);
        String maskedPrefix = value.substring(0, value.length() - VISIBLE_PAN_CHARACTERS);
        assertThat(maskedPrefix)
                .as("masked PAN prefix must contain no cleartext digit (only mask characters), but was '%s'",
                        maskedPrefix)
                .matches("[^0-9]*");
    }

    /**
     * Asserts the general shape of a masked card number when the caller does not
     * know the expected last four: a run of one or more mask characters (any
     * non-digit) followed by exactly four trailing digits.
     *
     * @param value the masked card number
     */
    public static void assertPanMaskedLast4(String value) {
        assertThat(value).as("masked PAN").isNotNull().isNotBlank();
        assertThat(value)
                .as("masked PAN '%s' must be a run of mask characters followed by exactly 4 trailing digits",
                        value)
                .matches("[^0-9]+\\d{4}");
    }

    /**
     * Asserts that a numeric JSON field serialized as a plain (non-scientific),
     * fixed-scale literal — for example {@code 100.00} — thereby enforcing the
     * money-as-PLAIN-scale-2 rule.
     *
     * <p>The raw numeric literal is read straight from the token stream with a
     * streaming {@link JsonParser}, because that is the only representation that
     * survives round-tripping. Re-reading the value into a {@code JsonNode} would
     * not work: a {@code DecimalNode} normalizes away trailing zeros by default in
     * Jackson&nbsp;2.19 (the {@code JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES}
     * behaviour), so the plainly-serialized {@code 100.00} would be reported as
     * {@code 1E+2}. {@link JsonParser#getText()} on the number token instead returns
     * the exact characters emitted by the serializer, which is precisely what this
     * assertion is about. The shared {@link #OBJECT_MAPPER} is left unmodified.</p>
     *
     * @param json                 the JSON document to inspect
     * @param jsonFieldName        the name of the numeric field to check
     * @param expectedPlainLiteral the exact plain literal the field must equal
     *                             (for example {@code "100.00"})
     */
    public static void assertJsonNumberIsPlain(String json, String jsonFieldName, String expectedPlainLiteral) {
        String literal = rawNumberLiteral(json, jsonFieldName);
        assertThat(literal)
                .as("money field '%s' must serialize as the plain literal '%s'", jsonFieldName, expectedPlainLiteral)
                .isEqualTo(expectedPlainLiteral);
        assertThat(literal)
                .as("money field '%s' must not use scientific notation (was '%s')", jsonFieldName, literal)
                .doesNotContainIgnoringCase("e");
    }

    /**
     * Reads the verbatim numeric literal of a top-level JSON field by walking the
     * document with a streaming {@link JsonParser}. The value token must be a JSON
     * number (integer or floating point); otherwise an {@link AssertionError} is
     * raised. Returning {@link JsonParser#getText()} yields the exact source
     * characters (for example {@code 100.00}), never a re-normalized form.
     *
     * @param json          the JSON document to inspect
     * @param jsonFieldName the name of the field whose numeric literal is required
     * @return the raw numeric literal exactly as it appears in {@code json}
     */
    private static String rawNumberLiteral(String json, String jsonFieldName) {
        try (JsonParser parser = OBJECT_MAPPER.getFactory().createParser(json)) {
            int depth = 0;
            while (parser.nextToken() != null) {
                JsonToken token = parser.currentToken();
                if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
                    depth++;
                } else if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) {
                    depth--;
                } else if (token == JsonToken.FIELD_NAME
                        && depth == 1
                        && jsonFieldName.equals(parser.currentName())) {
                    JsonToken valueToken = parser.nextToken();
                    assertThat(valueToken)
                            .as("JSON field '%s' must be a number node", jsonFieldName)
                            .isIn(JsonToken.VALUE_NUMBER_FLOAT, JsonToken.VALUE_NUMBER_INT);
                    return parser.getText();
                }
            }
        } catch (IOException e) {
            throw new AssertionError("Failed to parse JSON while asserting field '" + jsonFieldName + "': " + json, e);
        }
        throw new AssertionError("JSON must contain a numeric field named '" + jsonFieldName + "': " + json);
    }

    /**
     * Asserts that a {@link BigDecimal} has the expected scale, reproducing the
     * fixed {@code Vnn} scale of the source COBOL picture clause (money fields are
     * scale 2).
     *
     * @param value         the value to inspect; must not be {@code null}
     * @param expectedScale the required scale
     */
    public static void assertScale(BigDecimal value, int expectedScale) {
        assertThat(value).as("BigDecimal value").isNotNull();
        assertThat(value.scale()).as("scale of BigDecimal %s", value).isEqualTo(expectedScale);
    }

    /**
     * Returns a canonical, scale-2 monetary sample value ({@code 100.00}) suitable
     * for populating money components in DTO tests without repeating the literal.
     *
     * @return a {@link BigDecimal} equal to {@code 100.00} with scale 2
     */
    public static BigDecimal sampleMoney() {
        return new BigDecimal(SAMPLE_MONEY_LITERAL);
    }
}
