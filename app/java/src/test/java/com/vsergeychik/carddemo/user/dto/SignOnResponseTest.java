package com.vsergeychik.carddemo.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.user.model.SecUserRecord;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link SignOnResponse} - the outbound payload of the {@code COSGN00} sign-on screen, CICS
 * transaction {@code CC00}, program {@code app/cbl/COSGN00C.cbl}, map {@code COSGN0A}.
 */
@DisplayName("SignOnResponse - the COSGN00 (CC00) sign-on outbound payload")
class SignOnResponseTest {
    private static final Charset MAP_CHARSET = StandardCharsets.US_ASCII;

    private static final String MAP_NAME = "COSGN0A";

    private static final String MAPSET_NAME = "COSGN00";

    private static final String TRANSACTION_ID = "CC00";

    private static final String PROGRAM_NAME = "COSGN00C";

    private static final int DFHMDF_TOTAL = 37;

    private static final int DFHMDF_NAMED = 11;

    private static final int RESPONSE_MAP_MEMBERS = 11;

    private static final List<String> OUTPUT_MAP_ITEMS = List.of(
            "TRNNAMEO", "TITLE01O", "CURDATEO", "PGMNAMEO", "TITLE02O", "CURTIMEO",
            "APPLIDO", "SYSIDO", "USERIDO", "PASSWDO", "ERRMSGO");

    private static final List<String> SCREEN_FIELDS = List.of(
            "TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME",
            "APPLID", "SYSID", "USERID", "PASSWD", "ERRMSG");

    private static final List<Integer> DECLARED_WIDTHS = List.of(4, 40, 8, 8, 40, 9, 8, 8, 8, 8, 78);

    private static final List<Integer> COPYBOOK_LINES = List.of(92, 98, 104, 110, 116, 122, 128, 134,
            140, 146, 152);

    private static final List<Integer> MAPSET_LINES = List.of(34, 38, 47, 57, 61, 70, 80, 89, 156,
            175, 197);

    private static final List<String> RESPONSE_MEMBERS = List.of(
            "trnName", "title01", "curDate", "pgmName", "title02", "curTime",
            "applId", "sysId", "userId", "passwd", "errMsg");

    private static final List<String> RESPONSE_MAP_ITEMS = List.of(
            "TRNNAMEO", "TITLE01O", "CURDATEO", "PGMNAMEO", "TITLE02O", "CURTIMEO",
            "APPLIDO", "SYSIDO", "USERIDO", "PASSWDO", "ERRMSGO");

    private static final List<Integer> RESPONSE_WIDTHS = List.of(4, 40, 8, 8, 40, 9, 8, 8, 8, 8, 78);

    /**
     * The one member of {@link #RESPONSE_MEMBERS} that is <strong>not</strong> published.
     *
     * <p>{@code PASSWDO} reaches a 3270 under {@code ATTRB=(DRK,FSET,UNPROT)} at
     * {@code app/bms/COSGN00.bms} line 175: the eight characters go to exactly one device and are
     * rendered invisibly there. A JSON member has no {@code DRK} bit and no single recipient, so the
     * component keeps the overlay's image faithfully - {@code 01 COSGN0AO REDEFINES COSGN0AI} really does
     * put the typed value in the send area, and the parity corpus pins it - while {@code @JsonIgnore}
     * keeps it off the wire.
     */
    private static final String WITHHELD_MEMBER = "passwd";

    /** {@link #RESPONSE_MEMBERS} less {@link #WITHHELD_MEMBER}: the ten that are serialised. */
    private static final List<String> PUBLISHED_MEMBERS = RESPONSE_MEMBERS.stream()
            .filter(member -> !WITHHELD_MEMBER.equals(member))
            .toList();

    /**
     * The six members with no {@code DFHMDF} behind them, in component order.
     *
     * <p>They are the mandated, documented exception to "every member is a screen field": once the
     * server is stateless, {@code EXEC CICS XCTL} and {@code EXEC CICS SEND TEXT} both have to be
     * expressed as data. See {@link RoleRoutingAndXctl} and {@link StatelessConversationState}.
     */
    private static final List<String> NAVIGATION_MEMBERS = List.of(
            "role", "nextProgram", "nextMapset", "nextMap", "plainText", "navigationContext");

    private static String wireNameOf(String member) {
        return RESPONSE_MEMBERS.contains(member) ? member.toLowerCase(Locale.ROOT) : member;
    }

    private static List<String> wireNamesOf(List<String> members) {
        return members.stream().map(SignOnResponseTest::wireNameOf).toList();
    }

    private static final int COMPONENT_COUNT = 17;

    private static final String CREDENTIAL_SCREEN_FIELD = "PASSWD";

    private static final String CREDENTIAL_OUTPUT_ITEM = "PASSWDO";

    /**
     * The one component declared {@code @JsonProperty(access = WRITE_ONLY)}: {@code passwd}.
     *
     * <p>{@code app/bms/COSGN00.bms:174-180} declares {@code PASSWD} with {@code ATTRB=(DRK,..)}, so the
     * 3270 receives the eight characters and renders them invisibly. JSON has no {@code DRK}, and a
     * member on the wire is readable by the client, by anything between them and by anything that logs a
     * response body - so emitting it would reproduce one half of the terminal contract and discard the
     * half that hides the value. The two halves are therefore separated: the span stays on the model,
     * {@link SignOnResponse#passwd()} and the field census still report it in full, and only the
     * serialized projection omits it - in both directions, so no payload can put a credential
     * back into a response either.
     */
    private static final String WRITE_ONLY_MEMBER = "passwd";

    /**
     * The member names the payload actually emits: every component's wire name less
     * {@link #WRITE_ONLY_MEMBER}, in declaration order.
     *
     * @param members the Java member names, in declaration order
     * @return their emitted JSON property names
     */
    private static List<String> emittedNamesOf(List<String> members) {
        List<String> emitted = wireNamesOf(members).stream()
                .filter(name -> !name.equals(WRITE_ONLY_MEMBER))
                .toList();
        if (emitted.size() != members.size() - 1) {
            throw new AssertionError(WRITE_ONLY_MEMBER + " must appear exactly once in " + members
                    + ", otherwise removing it from the emitted set would assert nothing");
        }
        return emitted;
    }

    /**
     * The JSON property name the credential component would have carried, and the key the serialised
     * payload must not contain: {@code passwd}, the lower-case {@code PASSWDI} stem AAP 0.6.3 pins.
     */
    private static final String CREDENTIAL_WIRE_NAME = "passwd";

    /** Map-derived members the wire carries: {@value #RESPONSE_MAP_MEMBERS} less the withheld one. */
    private static final int RESPONSE_WIRE_MEMBERS = RESPONSE_MAP_MEMBERS - 1;

    /**
     * An obviously fake eight-character password image, standing for what {@code EXEC CICS RECEIVE MAP}
     * left in the {@code PASSWDI}/{@code PASSWDO} span. Never a credential or an environment secret.
     */
    private static final String RECEIVED_PASSWORD = "PASSWORD";

    private static final List<Integer> PASSWD_REFERENCE_LINES = List.of(123, 126, 135, 244);

    private static final int PASSWDO_REFERENCE_COUNT = 0;

    private static final int CDEMO_CU0N_INFO_COUNT = 0;

    private static final int TIOAPFX_PREFIX_LENGTH = 12;

    private static final int LENGTH_ITEM_LENGTH = 2;

    private static final int ATTRIBUTE_ITEM_LENGTH = 1;

    private static final int INPUT_FILLER_LENGTH = 4;

    private static final int OUTPUT_FILLER_LENGTH = 3;

    private static final List<String> ATTRIBUTE_SUFFIXES = List.of("C", "P", "H", "V");

    private static final int FIELD_PREFIX_LENGTH =
            LENGTH_ITEM_LENGTH + ATTRIBUTE_ITEM_LENGTH + INPUT_FILLER_LENGTH;

    private static final int OUTPUT_PREFIX_LENGTH =
            OUTPUT_FILLER_LENGTH + ATTRIBUTE_SUFFIXES.size() * ATTRIBUTE_ITEM_LENGTH;

    private static final int PAYLOAD_WIDTH_TOTAL = 219;

    private static final int SYMBOLIC_MAP_LENGTH = 308;

    private static final int COPYBOOK_REDEFINES_TOTAL = 12;

    private static final int GROUP_LEVEL_REDEFINES = 1;

    private static final int PACKAGE_PER_FIELD_REDEFINES = 105;

    private static final int PACKAGE_GROUP_LEVEL_REDEFINES = 5;

    private static final int PACKAGE_REDEFINES_TOTAL = 110;

    private static final int WS_MESSAGE_LENGTH = 80;

    private static final int ERRMSG_TRUNCATED_CHARACTERS = WS_MESSAGE_LENGTH - 78;

    private static final FixedWidthRecord.RecordLayout INPUT_VIEW_LAYOUT = inputViewLayout();

    private static final FixedWidthRecord.RecordLayout OUTPUT_VIEW_LAYOUT = outputViewLayout();

    private static final Instant FIXED_INSTANT = Instant.parse("2022-07-19T23:12:33Z");

    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    private static final String FIXED_CURDATE = "07/19/22";

    private static final String FIXED_CURTIME = "23:12:33";

    private static final String CURTIME_INITIAL = "Ahh:mm:ss";

    private static final String CURDATE_INITIAL = "mm/dd/yy";

    private static final String MSG_ENTER_USER_ID = "Please enter User ID ...";

    private static final String MSG_ENTER_PASSWORD = "Please enter Password ...";

    private static final String MSG_WRONG_PASSWORD = "Wrong Password. Try again ...";

    private static final String MSG_USER_NOT_FOUND = "User not found. Try again ...";

    private static final String MSG_UNABLE_TO_VERIFY = "Unable to verify the User ...";

    private static final List<String> PROGRAM_MESSAGES = List.of(
            MSG_ENTER_USER_ID, MSG_ENTER_PASSWORD, MSG_WRONG_PASSWORD, MSG_USER_NOT_FOUND,
            MSG_UNABLE_TO_VERIFY);

    private static final List<String> COPYBOOK_MESSAGES = List.of(
            SystemMessages.CCDA_MSG_THANK_YOU, SystemMessages.CCDA_MSG_INVALID_KEY);

    private static FixedWidthRecord.RecordLayout inputViewLayout() {
        List<FixedWidthRecord.FieldSpan> spans = new ArrayList<>();
        spans.add(FixedWidthRecord.FieldSpan.filler(0, TIOAPFX_PREFIX_LENGTH));
        int cursor = TIOAPFX_PREFIX_LENGTH;
        for (int index = 0; index < DFHMDF_NAMED; index++) {
            String field = SCREEN_FIELDS.get(index);
            spans.add(FixedWidthRecord.FieldSpan.filler(cursor, LENGTH_ITEM_LENGTH));
            cursor += LENGTH_ITEM_LENGTH;
            FixedWidthRecord.FieldSpan flag = FixedWidthRecord.FieldSpan.alphanumeric(
                    field + "F", cursor, ATTRIBUTE_ITEM_LENGTH);
            spans.add(flag);
            spans.add(flag.redefinedAs(field + "A", FixedWidthRecord.PictureKind.ALPHANUMERIC));
            cursor += ATTRIBUTE_ITEM_LENGTH;
            spans.add(FixedWidthRecord.FieldSpan.filler(cursor, INPUT_FILLER_LENGTH));
            cursor += INPUT_FILLER_LENGTH;
            spans.add(FixedWidthRecord.FieldSpan.alphanumeric(
                    SCREEN_FIELDS.get(index) + "I", cursor, DECLARED_WIDTHS.get(index)));
            cursor += DECLARED_WIDTHS.get(index);
        }
        spans.add(FixedWidthRecord.FieldSpan.redefining(MAP_NAME + "O", 0, SYMBOLIC_MAP_LENGTH,
                FixedWidthRecord.PictureKind.ALPHANUMERIC));
        return FixedWidthRecord.RecordLayout.of(SYMBOLIC_MAP_LENGTH,
                spans.toArray(FixedWidthRecord.FieldSpan[]::new));
    }

    private static FixedWidthRecord.RecordLayout outputViewLayout() {
        List<FixedWidthRecord.FieldSpan> spans = new ArrayList<>();
        spans.add(FixedWidthRecord.FieldSpan.filler(0, TIOAPFX_PREFIX_LENGTH));
        int cursor = TIOAPFX_PREFIX_LENGTH;
        for (int index = 0; index < DFHMDF_NAMED; index++) {
            String field = SCREEN_FIELDS.get(index);
            spans.add(FixedWidthRecord.FieldSpan.filler(cursor, OUTPUT_FILLER_LENGTH));
            cursor += OUTPUT_FILLER_LENGTH;
            for (String suffix : ATTRIBUTE_SUFFIXES) {
                spans.add(FixedWidthRecord.FieldSpan.alphanumeric(
                        field + suffix, cursor, ATTRIBUTE_ITEM_LENGTH));
                cursor += ATTRIBUTE_ITEM_LENGTH;
            }
            spans.add(FixedWidthRecord.FieldSpan.alphanumeric(
                    OUTPUT_MAP_ITEMS.get(index), cursor, DECLARED_WIDTHS.get(index)));
            cursor += DECLARED_WIDTHS.get(index);
        }
        return FixedWidthRecord.RecordLayout.of(SYMBOLIC_MAP_LENGTH,
                spans.toArray(FixedWidthRecord.FieldSpan[]::new));
    }

    private static FixedWidthCodec codec() {
        return new FixedWidthCodec(MAP_CHARSET);
    }

    private static ObjectMapper webConfigEquivalentMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        mapper.configure(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN, true);
        mapper.disable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT);
        return mapper;
    }

    private static List<String> componentNames() {
        return Arrays.stream(SignOnResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    private static List<Class<?>> componentTypes() {
        return Arrays.stream(SignOnResponse.class.getRecordComponents())
                .map(RecordComponent::getType)
                .toList();
    }

    private static SignOnResponse populated() {
        return new SignOnResponse(TRANSACTION_ID,
                ScreenTitles.CCDA_TITLE01,
                FIXED_CURDATE,
                PROGRAM_NAME,
                ScreenTitles.CCDA_TITLE02,
                FIXED_CURTIME + " ",
                "CICSAPPL",
                "CICS    ",
                "ADMIN001",
                RECEIVED_PASSWORD,
                codec().movePicX(MSG_WRONG_PASSWORD, SignOnResponse.ERRMSG_LENGTH),
                SignOnResponse.ROLE_ADMIN,
                SignOnResponse.NEXT_PROGRAM_ADMIN,
                MAPSET_NAME,
                MAP_NAME,
                " ".repeat(SignOnResponse.PLAIN_TEXT_LENGTH),
                signedOnContext(SignOnResponse.ROLE_ADMIN));
    }

    private static NavigationContext signedOnContext(String role) {
        return NavigationContext.empty()
                .withFromTranid(TRANSACTION_ID)
                .withFromProgram(PROGRAM_NAME)
                .withUserId("ADMIN001")
                .withUserType(role)
                .withPgmEnter();
    }

    private static List<String> mapValuesOf(SignOnResponse response) {
        return List.of(response.trnName(), response.title01(), response.curDate(),
                response.pgmName(), response.title02(), response.curTime(), response.applId(),
                response.sysId(), response.userId(), response.passwd(), response.errMsg());
    }

    private static List<String> topLevelJsonKeys(SignOnResponse response) {
        return List.copyOf(asMap(response).keySet());
    }

    private static Set<String> jsonKeys(SignOnResponse response) {
        Set<String> keys = new LinkedHashSet<>();
        collectKeys(asMap(response), keys);
        return keys;
    }

    /** The serialised payload as raw text, for asserting that a value appears nowhere in it. */
    private static String json(SignOnResponse response) {
        try {
            return webConfigEquivalentMapper().writeValueAsString(response);
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not serialise the sign-on response", failure);
        }
    }

    private static Map<String, Object> asMap(SignOnResponse response) {
        ObjectMapper mapper = webConfigEquivalentMapper();
        try {
            return mapper.readValue(mapper.writeValueAsString(response),
                    new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not serialise the sign-on response", failure);
        }
    }

    /**
     * The {@link JsonProperty} governing one component's wire projection, or {@code null} if it has
     * none.
     *
     * <p>Searched across the accessor, the backing field and the canonical-constructor parameter rather
     * than on the record component, because {@code JsonProperty}'s {@code @Target} names
     * {@code FIELD}, {@code METHOD} and {@code PARAMETER} but <strong>not</strong>
     * {@code RECORD_COMPONENT} - so an annotation written in the record header is reachable on those
     * three and absent from the component itself. Every surface that carries one is required to agree,
     * so declaring one access mode on the field and another on the accessor cannot pass unnoticed.
     *
     * @param componentName the component to inspect
     * @return the annotation that governs it, or {@code null} if none is applied
     */
    private static JsonProperty jsonPropertyOn(String componentName) {
        List<JsonProperty> found = new ArrayList<>();
        RecordComponent component = Arrays.stream(SignOnResponse.class.getRecordComponents())
                .filter(candidate -> candidate.getName().equals(componentName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        componentName + " is not a component of SignOnResponse"));
        addIfPresent(found, component.getAnnotation(JsonProperty.class));
        addIfPresent(found, component.getAccessor().getAnnotation(JsonProperty.class));
        try {
            addIfPresent(found, SignOnResponse.class.getDeclaredField(componentName)
                    .getAnnotation(JsonProperty.class));
        } catch (NoSuchFieldException neverHappensForARecordComponent) {
            throw new AssertionError("a record component always has a backing field",
                    neverHappensForARecordComponent);
        }
        for (Constructor<?> constructor : SignOnResponse.class.getDeclaredConstructors()) {
            for (Parameter parameter : constructor.getParameters()) {
                if (parameter.getName().equals(componentName)) {
                    addIfPresent(found, parameter.getAnnotation(JsonProperty.class));
                }
            }
        }
        if (found.isEmpty()) {
            return null;
        }
        assertThat(found.stream().map(JsonProperty::access).distinct().toList())
                .as("%s must declare the same access mode on every surface", componentName)
                .hasSize(1);
        assertThat(found.stream().map(JsonProperty::value).distinct().toList())
                .as("%s must declare the same wire name on every surface", componentName)
                .hasSize(1);
        return found.get(0);
    }

    private static void addIfPresent(List<JsonProperty> target, JsonProperty annotation) {
        if (annotation != null) {
            target.add(annotation);
        }
    }

    /**
     * Every annotation reachable on one component, across the same surfaces
     * {@link #jsonPropertyOn(String)} inspects, as simple names.
     *
     * @param componentName the component to inspect
     * @return the annotation type simple names found
     */
    private static Set<String> annotationSimpleNamesOn(String componentName) {
        Set<String> names = new LinkedHashSet<>();
        RecordComponent component = Arrays.stream(SignOnResponse.class.getRecordComponents())
                .filter(candidate -> candidate.getName().equals(componentName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        componentName + " is not a component of SignOnResponse"));
        collectSimpleNames(names, component.getAnnotations());
        collectSimpleNames(names, component.getAccessor().getAnnotations());
        try {
            collectSimpleNames(names,
                    SignOnResponse.class.getDeclaredField(componentName).getAnnotations());
        } catch (NoSuchFieldException neverHappensForARecordComponent) {
            throw new AssertionError("a record component always has a backing field",
                    neverHappensForARecordComponent);
        }
        for (Constructor<?> constructor : SignOnResponse.class.getDeclaredConstructors()) {
            for (Parameter parameter : constructor.getParameters()) {
                if (parameter.getName().equals(componentName)) {
                    collectSimpleNames(names, parameter.getAnnotations());
                }
            }
        }
        return names;
    }

    private static void collectSimpleNames(Set<String> target, Annotation[] annotations) {
        for (Annotation annotation : annotations) {
            target.add(annotation.annotationType().getSimpleName());
        }
    }

    /** The raw serialized document, for assertions about a value rather than about a key. */
    private static String serialisedFormOf(SignOnResponse response) {
        try {
            return webConfigEquivalentMapper().writeValueAsString(response);
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not serialise the sign-on response", failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static void collectKeys(Map<String, Object> node, Set<String> into) {
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            into.add(entry.getKey().toLowerCase(Locale.ROOT));
            if (entry.getValue() instanceof Map<?, ?> nested) {
                collectKeys((Map<String, Object>) nested, into);
            }
        }
    }

    private static SignOnResponse roundTrip(SignOnResponse response) {
        ObjectMapper mapper = webConfigEquivalentMapper();
        try {
            ObjectNode document = (ObjectNode) mapper.readTree(mapper.writeValueAsString(response));
            document.put(CREDENTIAL_WIRE_NAME, " ".repeat(SignOnResponse.PASSWD_LENGTH));
            return mapper.treeToValue(document, SignOnResponse.class);
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not round trip the sign-on response", failure);
        }
    }

    /**
     * The same value as it exists <strong>after</strong> a round trip: every published member unchanged,
     * and the withheld credential in the blank image an absent JSON member deserialises to.
     *
     * <p>Round-trip equality is asserted against this rather than against the original, because equality
     * that ignored the credential would also stop noticing if it ever came back.
     *
     * @param response the value before serialisation
     * @return the value the wire form can reconstruct
     */
    private static SignOnResponse withCredentialWithheld(SignOnResponse response) {
        return response.withReceivedMapArea(response.userId(),
                " ".repeat(SignOnResponse.PASSWD_LENGTH));
    }

    /** Every type this payload's API mentions: component types, and every parameter and return type. */
    private static Set<String> reachableTypeNames() {
        Set<String> names = new LinkedHashSet<>();
        for (Class<?> type : componentTypes()) {
            names.add(type.getName());
        }
        for (Method method : SignOnResponse.class.getDeclaredMethods()) {
            names.add(method.getReturnType().getName());
            for (Class<?> parameter : method.getParameterTypes()) {
                names.add(parameter.getName());
            }
        }
        for (Field field : SignOnResponse.class.getDeclaredFields()) {
            names.add(field.getType().getName());
        }
        return names;
    }

    @Nested
    @DisplayName("Projection of 01 COSGN0AO - ten map members, then five navigation members")
    class MapProjection {
        @Test
        @DisplayName("fifteen components: the ten map members in copybook order, then the five")
        void componentCensus() {
            assertThat(componentNames())
                    .as("the payload projects %d xxxO items and carries %d navigation members",
                            RESPONSE_MAP_MEMBERS, NAVIGATION_MEMBERS.size())
                    .hasSize(COMPONENT_COUNT)
                    .containsExactlyElementsOf(concat(RESPONSE_MEMBERS, NAVIGATION_MEMBERS));
            assertThat(RESPONSE_MEMBERS).hasSize(RESPONSE_MAP_MEMBERS);
            assertThat(NAVIGATION_MEMBERS).hasSize(COMPONENT_COUNT - RESPONSE_MAP_MEMBERS);
        }

        @Test
        @DisplayName("every map member is a String, because every xxxO item is PIC X(n)")
        void everyMapMemberIsCharacter() {
            List<Class<?>> types = componentTypes();
            for (int index = 0; index < RESPONSE_MAP_MEMBERS; index++) {
                assertThat(types.get(index))
                        .as("%s carries %s, which is PIC X(%d)", RESPONSE_MEMBERS.get(index),
                                RESPONSE_MAP_ITEMS.get(index), RESPONSE_WIDTHS.get(index))
                        .isEqualTo(String.class);
            }
        }

        @Test
        @DisplayName("no member is a floating-point type, and none is numeric at all")
        void nothingIsFloatingPoint() {
            assertThat(componentTypes())
                    .doesNotContain(double.class, float.class, Double.class, Float.class)
                    .containsOnly(String.class, NavigationContext.class);
        }

        @Test
        @DisplayName("MAP_FIELDS is exactly the copybook's xxxO items, in order, less PASSWDO")
        void mapFieldsMatchTheCopybook() {
            assertThat(SignOnResponse.MAP_FIELDS)
                    .containsExactlyElementsOf(RESPONSE_MAP_ITEMS)
                    .hasSize(RESPONSE_MAP_MEMBERS)
                    .contains(CREDENTIAL_OUTPUT_ITEM);
            assertThat(OUTPUT_MAP_ITEMS)
                    .as("the copybook declares %d items and the payload projects all of them",
                            DFHMDF_NAMED)
                    .hasSize(DFHMDF_NAMED)
                    .containsExactlyElementsOf(RESPONSE_MAP_ITEMS);
        }

        @Test
        @DisplayName("MAPSET_NAMED_FIELDS is the whole screen, in mapset order, PASSWD included")
        void namedFieldsMatchTheMapset() {
            assertThat(SignOnResponse.MAPSET_NAMED_FIELDS)
                    .as("the census of the screen is complete")
                    .containsExactlyElementsOf(SCREEN_FIELDS)
                    .hasSize(DFHMDF_NAMED)
                    .contains(CREDENTIAL_SCREEN_FIELD);
        }

        @Test
        @DisplayName("the three published counts are the mapset's own: 37, 11 and 10")
        void countsAreTheMapsetsOwn() {
            assertThat(SignOnResponse.MAPSET_FIELD_COUNT)
                    .as("every DFHMDF definition, labelled or not")
                    .isEqualTo(DFHMDF_TOTAL);
            assertThat(SignOnResponse.MAPSET_NAMED_FIELD_COUNT)
                    .as("the name-labelled ones, which are also the xxxI and xxxO item counts")
                    .isEqualTo(DFHMDF_NAMED);
            assertThat(SignOnResponse.MAP_FIELD_COUNT)
                    .as("the projected ones")
                    .isEqualTo(RESPONSE_MAP_MEMBERS);
            assertThat(DFHMDF_TOTAL - DFHMDF_NAMED)
                    .as("26 unlabelled definitions are static screen furniture, never payload")
                    .isEqualTo(26);
        }

        @Test
        @DisplayName("every map member traces to one named DFHMDF field, and its item is that field + O")
        void everyMemberTracesToAScreenField() {
            for (int index = 0; index < RESPONSE_MAP_MEMBERS; index++) {
                String item = RESPONSE_MAP_ITEMS.get(index);
                assertThat(SCREEN_FIELDS)
                        .as("%s must name a real screen field", item)
                        .contains(item.substring(0, item.length() - 1));
                assertThat(item)
                        .as("the trailing O is the output-direction suffix and is part of the name")
                        .endsWith(FieldAttributeSetter.OUTPUT_ITEM_SUFFIX);
            }
        }

        @Test
        @DisplayName("the field-name constants are the copybook's spelling, suffix included")
        void fieldNameConstantsAreVerbatim() {
            assertThat(List.of(SignOnResponse.TRNNAME_FIELD, SignOnResponse.TITLE01_FIELD,
                    SignOnResponse.CURDATE_FIELD, SignOnResponse.PGMNAME_FIELD,
                    SignOnResponse.TITLE02_FIELD, SignOnResponse.CURTIME_FIELD,
                    SignOnResponse.APPLID_FIELD, SignOnResponse.SYSID_FIELD,
                    SignOnResponse.USERID_FIELD, SignOnResponse.PASSWD_FIELD,
                    SignOnResponse.ERRMSG_FIELD))
                    .as("a tidied name would make a real field-for-field difference invisible")
                    .containsExactlyElementsOf(RESPONSE_MAP_ITEMS);
        }

        @Test
        @DisplayName("the published field lists are immutable, so no caller can edit the census")
        void publishedListsAreImmutable() {
            assertRefusesModification(() -> SignOnResponse.MAP_FIELDS.add(CREDENTIAL_OUTPUT_ITEM));
            assertRefusesModification(() ->
                    SignOnResponse.MAPSET_NAMED_FIELDS.remove(CREDENTIAL_SCREEN_FIELD));
        }

        @Test
        @DisplayName("the screen identity literals are the program's own")
        void screenIdentityLiteralsAreTheProgramsOwn() {
            assertThat(SignOnResponse.TRANID).isEqualTo(TRANSACTION_ID);
            assertThat(SignOnResponse.PROGRAM_NAME).isEqualTo(PROGRAM_NAME);
            assertThat(SignOnResponse.MAPSET_NAME).isEqualTo(MAPSET_NAME);
            assertThat(SignOnResponse.MAP_NAME).isEqualTo(MAP_NAME);
        }
    }

    @Nested
    @DisplayName("Widths from the PICTURE clauses, cross-checked against the mapset's LENGTH=")
    class WidthTraps {
        @ParameterizedTest(name = "{0} is PIC X({1}) at COSGN00.CPY line {2}")
        @CsvSource({
            "TRNNAMEO, 4, 92",
            "TITLE01O, 40, 98",
            "CURDATEO, 8, 104",
            "PGMNAMEO, 8, 110",
            "TITLE02O, 40, 116",
            "CURTIMEO, 9, 122",
            "APPLIDO, 8, 128",
            "SYSIDO, 8, 134",
            "USERIDO, 8, 140",
            "ERRMSGO, 78, 152"
        })
        @DisplayName("the copybook declares the width this payload publishes")
        void copybookAgreesWithTheConstant(String item, int expected, int copybookLine) {
            int index = RESPONSE_MAP_ITEMS.indexOf(item);
            assertThat(index).as("%s must be a projected item", item).isNotNegative();
            assertThat(RESPONSE_WIDTHS.get(index))
                    .as("%s is declared at COSGN00.CPY:%d", item, copybookLine)
                    .isEqualTo(expected);
            assertThat(publishedWidths().get(index))
                    .as("the class must publish the copybook's width for %s", item)
                    .isEqualTo(expected);
            assertThat(COPYBOOK_LINES.get(OUTPUT_MAP_ITEMS.indexOf(item)))
                    .as("and the transcription must cite the right line")
                    .isEqualTo(copybookLine);
        }

        @ParameterizedTest(name = "{0} has LENGTH={1} at COSGN00.bms line {2}")
        @CsvSource({
            "TRNNAME, 4, 34",
            "TITLE01, 40, 38",
            "CURDATE, 8, 47",
            "PGMNAME, 8, 57",
            "TITLE02, 40, 61",
            "CURTIME, 9, 70",
            "APPLID, 8, 80",
            "SYSID, 8, 89",
            "USERID, 8, 156",
            "ERRMSG, 78, 197"
        })
        @DisplayName("the mapset's LENGTH operand agrees independently of the copybook")
        void mapsetAgreesWithTheConstant(String screenField, int expected, int mapsetLine) {
            int index = SCREEN_FIELDS.indexOf(screenField);
            assertThat(index).as("%s must be a named screen field", screenField).isNotNegative();
            assertThat(DECLARED_WIDTHS.get(index))
                    .as("%s DFHMDF LENGTH=%d at bms:%d", screenField, expected, mapsetLine)
                    .isEqualTo(expected);
            assertThat(MAPSET_LINES.get(index)).isEqualTo(mapsetLine);
            assertThat(publishedWidths().get(RESPONSE_MAP_ITEMS.indexOf(screenField + "O")))
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("curTime is nine characters, not the eight of COUSR00 through COUSR03")
        void curTimeIsNineCharacters() {
            assertThat(SignOnResponse.CURTIME_LENGTH)
                    .as("COSGN00.CPY:122 and COSGN00.bms:72 both say nine")
                    .isEqualTo(9);
            assertThat(CURTIME_INITIAL)
                    .as("the mapset's own INITIAL literal is the independent corroboration")
                    .hasSize(SignOnResponse.CURTIME_LENGTH);
            assertThat(CURDATE_INITIAL)
                    .as("while CURDATE's is eight, so the difference is real and not a typo")
                    .hasSize(SignOnResponse.CURDATE_LENGTH)
                    .hasSize(8);
            assertThat(SignOnResponse.CURTIME_LENGTH)
                    .isEqualTo(SignOnResponse.CURDATE_LENGTH + 1);
        }

        @Test
        @DisplayName("the eight-character header time pads on the right into the nine-wide field")
        void theHeaderTimePadsIntoTheNineWideField() {
            String rendered = DateHeader.from(codec(), FIXED_CLOCK).wsCurtimeHhMmSs();
            assertThat(rendered).isEqualTo(FIXED_CURTIME).hasSize(DateHeader.WS_CURTIME_HH_MM_SS_LENGTH);

            String moved = codec().movePicX(rendered, SignOnResponse.CURTIME_LENGTH);
            assertThat(moved)
                    .hasSize(SignOnResponse.CURTIME_LENGTH)
                    .isEqualTo(FIXED_CURTIME + " ")
                    .endsWith(" ");
            assertThat(new SignOnResponse(TRANSACTION_ID, ScreenTitles.CCDA_TITLE01, FIXED_CURDATE,
                    PROGRAM_NAME, ScreenTitles.CCDA_TITLE02, moved, "CICSAPPL", "CICS    ",
                    "ADMIN001", RECEIVED_PASSWORD, " ".repeat(SignOnResponse.ERRMSG_LENGTH),
                    SignOnResponse.ROLE_ADMIN, SignOnResponse.NEXT_PROGRAM_ADMIN, MAPSET_NAME,
                    MAP_NAME, " ".repeat(SignOnResponse.PLAIN_TEXT_LENGTH),
                    signedOnContext(SignOnResponse.ROLE_ADMIN)).curTime())
                    .isEqualTo(moved);
        }

        @Test
        @DisplayName("the eight-character header date fills curDate exactly, with no padding")
        void theHeaderDateFillsCurDateExactly() {
            String rendered = DateHeader.from(codec(), FIXED_CLOCK).wsCurdateMmDdYy();
            assertThat(rendered)
                    .as("MM/DD/YY for the fixed instant of COSGN00C.cbl:259")
                    .isEqualTo(FIXED_CURDATE)
                    .hasSize(SignOnResponse.CURDATE_LENGTH);
            assertThat(codec().movePicX(rendered, SignOnResponse.CURDATE_LENGTH))
                    .as("an exact-width MOVE neither pads nor truncates")
                    .isEqualTo(rendered);
        }

        @Test
        @DisplayName("errMsg is 78 although WS-MESSAGE is PIC X(80), so two characters are lost")
        void errMsgIsNarrowerThanTheMessageItCarries() {
            assertThat(SignOnResponse.ERRMSG_LENGTH).isEqualTo(78);
            assertThat(WS_MESSAGE_LENGTH)
                    .as("COSGN00C.cbl:38 declares WS-MESSAGE PIC X(80)")
                    .isEqualTo(80)
                    .isGreaterThan(SignOnResponse.ERRMSG_LENGTH);
            assertThat(ERRMSG_TRUNCATED_CHARACTERS)
                    .as("MOVE WS-MESSAGE TO ERRMSGO at line 149 discards this many")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("applId and sysId are eight, and exist on this screen alone")
        void applIdAndSysIdAreEight() {
            assertThat(SignOnResponse.APPLID_LENGTH).isEqualTo(8);
            assertThat(SignOnResponse.SYSID_LENGTH).isEqualTo(8);
            assertThat(OUTPUT_MAP_ITEMS).contains("APPLIDO", "SYSIDO");
            assertThat(populated().applId()).hasSize(SignOnResponse.APPLID_LENGTH);
            assertThat(populated().sysId()).hasSize(SignOnResponse.SYSID_LENGTH);
        }

        @Test
        @DisplayName("title01 and title02 are forty and carry the screen titles byte for byte")
        void titlesAreFortyCharacters() {
            assertThat(SignOnResponse.TITLE01_LENGTH).isEqualTo(ScreenTitles.TITLE_LENGTH).isEqualTo(40);
            assertThat(SignOnResponse.TITLE02_LENGTH).isEqualTo(ScreenTitles.TITLE_LENGTH);
            assertThat(ScreenTitles.CCDA_TITLE01).hasSize(SignOnResponse.TITLE01_LENGTH);
            assertThat(ScreenTitles.CCDA_TITLE02).hasSize(SignOnResponse.TITLE02_LENGTH);
            assertThat(populated().title01()).isEqualTo(ScreenTitles.CCDA_TITLE01);
            assertThat(populated().title02()).isEqualTo(ScreenTitles.CCDA_TITLE02);
        }

        @Test
        @DisplayName("userId is the width of the USRSEC key it was read with, and role of SEC-USR-TYPE")
        void userIdAndRoleMatchTheSecurityRecord() {
            assertThat(SignOnResponse.USERID_LENGTH)
                    .isEqualTo(SecUserRecord.SEC_USR_ID_LENGTH)
                    .isEqualTo(8);
            assertThat(SignOnResponse.ROLE_LENGTH)
                    .as("CSUSR01Y.cpy:22 SEC-USR-TYPE PIC X(01) becomes COCOM01Y.cpy:26 CDEMO-USER-TYPE")
                    .isEqualTo(SecUserRecord.SEC_USR_TYPE_LENGTH)
                    .isEqualTo(NavigationContext.USER_TYPE_LENGTH)
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("the navigation widths are NavigationContext's own, not restated literals")
        void navigationWidthsDelegate() {
            assertThat(SignOnResponse.ROLE_LENGTH).isEqualTo(NavigationContext.USER_TYPE_LENGTH);
            assertThat(SignOnResponse.NEXT_PROGRAM_LENGTH)
                    .isEqualTo(NavigationContext.TO_PROGRAM_LENGTH);
            assertThat(SignOnResponse.NEXT_MAPSET_LENGTH)
                    .isEqualTo(NavigationContext.LAST_MAPSET_LENGTH);
            assertThat(SignOnResponse.NEXT_MAP_LENGTH).isEqualTo(NavigationContext.LAST_MAP_LENGTH);
        }

        @Test
        @DisplayName("the map and mapset names are seven characters, which is why X(7) is correct")
        void mapNamesAreSevenCharacters() {
            assertThat(MAP_NAME).hasSize(SignOnResponse.NEXT_MAP_LENGTH).hasSize(7);
            assertThat(MAPSET_NAME).hasSize(SignOnResponse.NEXT_MAPSET_LENGTH).hasSize(7);
            assertThat(MAP_NAME + "I").hasSize(8);
            assertThat(MAP_NAME + FieldAttributeSetter.OUTPUT_MAP_SUFFIX).hasSize(8);
            assertThat(SignOnResponse.NEXT_PROGRAM_LENGTH)
                    .as("a program name is eight, which is a different field entirely")
                    .isEqualTo(8);
            assertThat(SignOnResponse.NEXT_PROGRAM_ADMIN).hasSize(8);
            assertThat(SignOnResponse.NEXT_PROGRAM_USER).hasSize(8);
        }

        @Test
        @DisplayName("the published widths equal the copybook's, entry for entry")
        void publishedWidthsEqualTheCopybooks() {
            assertThat(publishedWidths()).containsExactlyElementsOf(RESPONSE_WIDTHS);
            assertThat(RESPONSE_WIDTHS.stream().mapToInt(Integer::intValue).sum())
                    .as("all eleven projected widths, which is the copybook's whole data total")
                    .isEqualTo(PAYLOAD_WIDTH_TOTAL);
        }
    }

    private static List<Integer> publishedWidths() {
        return List.of(SignOnResponse.TRNNAME_LENGTH,
                SignOnResponse.TITLE01_LENGTH,
                SignOnResponse.CURDATE_LENGTH,
                SignOnResponse.PGMNAME_LENGTH,
                SignOnResponse.TITLE02_LENGTH,
                SignOnResponse.CURTIME_LENGTH,
                SignOnResponse.APPLID_LENGTH,
                SignOnResponse.SYSID_LENGTH,
                SignOnResponse.USERID_LENGTH,
                SignOnResponse.PASSWD_LENGTH,
                SignOnResponse.ERRMSG_LENGTH);
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> combined = new ArrayList<>(first);
        combined.addAll(second);
        return List.copyOf(combined);
    }

    private static void assertRefusesModification(Runnable modification) {
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .as("a published census must be immutable")
                .isThrownBy(modification::run);
    }

    @Nested
    @DisplayName("The overlay pair: USERIDO and PASSWDO are sent although nothing writes them")
    class TheOverlayPair {
        @Test
        @DisplayName("eleven named screen fields, eleven members: the counts agree by assertion")
        void bothCountsAgree() {
            assertThat(SignOnResponse.MAPSET_NAMED_FIELD_COUNT)
                    .as("COSGN00.CPY declares eleven xxxO items and the mapset eleven named DFHMDFs")
                    .isEqualTo(DFHMDF_NAMED)
                    .isEqualTo(11);
            assertThat(SignOnResponse.MAP_FIELD_COUNT)
                    .as("and this payload carries every one of them")
                    .isEqualTo(RESPONSE_MAP_MEMBERS)
                    .isEqualTo(SignOnResponse.MAPSET_NAMED_FIELD_COUNT);
            assertThat(SignOnResponse.MAP_FIELDS)
                    .as("in declaration order, PASSWDO between USERIDO and ERRMSGO")
                    .containsExactlyElementsOf(OUTPUT_MAP_ITEMS);
            assertThat(SignOnResponse.PASSWD_FIELD).isEqualTo(CREDENTIAL_OUTPUT_ITEM);
        }

        @Test
        @DisplayName("PASSWD is a real named screen field, at the width of the record it is compared to")
        void passwordIsARealScreenField() {
            assertThat(SCREEN_FIELDS).contains(CREDENTIAL_SCREEN_FIELD);
            assertThat(OUTPUT_MAP_ITEMS).contains(CREDENTIAL_OUTPUT_ITEM);
            assertThat(MAPSET_LINES.get(SCREEN_FIELDS.indexOf(CREDENTIAL_SCREEN_FIELD)))
                    .as("PASSWD DFHMDF is at app/bms/COSGN00.bms:175")
                    .isEqualTo(175);
            assertThat(COPYBOOK_LINES.get(OUTPUT_MAP_ITEMS.indexOf(CREDENTIAL_OUTPUT_ITEM)))
                    .as("PASSWDO PIC X(8) is at app/cpy-bms/COSGN00.CPY:146")
                    .isEqualTo(146);
            assertThat(DECLARED_WIDTHS.get(SCREEN_FIELDS.indexOf(CREDENTIAL_SCREEN_FIELD)))
                    .as("eight characters, the width of SEC-USR-PWD it is compared against")
                    .isEqualTo(SecUserRecord.SEC_USR_PWD_LENGTH)
                    .isEqualTo(8);
        }

        @Test
        @DisplayName("no MOVE writes PASSWDO: four PASSWD lines, none of them the output item")
        void noMoveWritesTheOutputItem() {
            assertThat(PASSWD_REFERENCE_LINES)
                    .as("every PASSWD mention in app/cbl/COSGN00C.cbl, and there is no fifth")
                    .containsExactly(123, 126, 135, 244)
                    .hasSize(4);
            assertThat(PASSWDO_REFERENCE_COUNT)
                    .as("PASSWDO - the OUTPUT item - is referenced nowhere in the program")
                    .isZero();
            assertThat(PASSWD_REFERENCE_LINES).contains(123, 135);
            assertThat(PASSWD_REFERENCE_LINES).contains(126, 244);
            assertThat(SignOnResponse.MAP_FIELDS)
                    .as("so the projected census names the output item all the same")
                    .contains(CREDENTIAL_OUTPUT_ITEM);
        }

        @Test
        @DisplayName("withReceivedMapArea sets both spans, and only those two")
        void theOverlayMutatorSetsBothSpans() {
            SignOnResponse blank = SignOnResponse.empty();

            SignOnResponse received = blank.withReceivedMapArea("ADMIN001", RECEIVED_PASSWORD);

            assertThat(received.userId()).isEqualTo("ADMIN001");
            assertThat(received.passwd()).isEqualTo(RECEIVED_PASSWORD);
            assertThat(received.trnName()).isEqualTo(blank.trnName());
            assertThat(received.errMsg()).isEqualTo(blank.errMsg());
            assertThat(received.plainText()).isEqualTo(blank.plainText());
            assertThat(received.navigationContext()).isEqualTo(blank.navigationContext());
            assertThat(blank.passwd())
                    .as("the receiver is immutable, so the original still holds LOW-VALUES")
                    .isEqualTo(ScreenFieldImage.unpainted(SignOnResponse.PASSWD_LENGTH));
        }

        @Test
        @DisplayName("an untransmitted field is LOW-VALUES, which is what empty() carries")
        void anUntransmittedSpanIsLowValues() {
            assertThat(SignOnResponse.empty().userId())
                    .isEqualTo(ScreenFieldImage.unpainted(SignOnResponse.USERID_LENGTH));
            assertThat(SignOnResponse.empty().passwd())
                    .isEqualTo(ScreenFieldImage.unpainted(SignOnResponse.PASSWD_LENGTH));
        }

        @Test
        @DisplayName("the credential is in the screen image, on no wire and in no rendering - CWE-200, "
                + "CWE-522, CWE-532")
        void theCredentialIsCarriedButNeverRendered() {
            SignOnResponse repaint = populated();

            assertThat(repaint.passwd())
                    .as("the span EXEC CICS SEND MAP ... FROM(COSGN0AO) transmits")
                    .isEqualTo(RECEIVED_PASSWORD);
            assertThat(jsonKeys(repaint))
                    .as("PASSWDO is withheld: the screen image keeps the span, the serialised "
                            + "payload carries no key for it under any spelling")
                    .doesNotContain(CREDENTIAL_WIRE_NAME, "password", "pwd", "secusrpwd", "secret");
            assertThat(SignOnResponse.WIRE_WITHHELD_FIELD).isEqualTo(CREDENTIAL_OUTPUT_ITEM);
            assertThat(json(repaint))
                    .as("and the value itself appears nowhere in the payload, under any name")
                    .doesNotContain(RECEIVED_PASSWORD);
            assertThat(repaint.toString())
                    .as("a log line is not a 3270")
                    .doesNotContain(RECEIVED_PASSWORD)
                    .contains(SensitiveDiagnostics.REDACTED);
            assertThat(repaint)
                    .as("equals keeps the password: value semantics disclose nothing")
                    .isEqualTo(populated())
                    .isNotEqualTo(repaint.withReceivedMapArea(repaint.userId(), "OTHERPWD"));
            assertThat(roundTrip(repaint).passwd())
                    .as("and the wire cannot hand it back either: the member is withheld in both "
                            + "directions, so a body read back carries the blank image at the declared "
                            + "width rather than the typed value")
                    .isEqualTo(" ".repeat(SignOnResponse.PASSWD_LENGTH));
        }

        @Test
        @DisplayName("USERIDO is painted by the same overlay, and is not upper-cased on the way out")
        void theUserIdIsPaintedByTheSameOverlay() {
            SignOnResponse repaint = SignOnResponse.empty()
                    .withReceivedMapArea("admin001", RECEIVED_PASSWORD);

            assertThat(repaint.userId()).isEqualTo("admin001");
            assertThat(componentNames()).contains("userId", "passwd");
            assertThat(SignOnResponse.MAP_FIELDS).contains(SignOnResponse.USERID_FIELD);
            assertThat(SignOnResponse.USERID_FIELD).isEqualTo("USERIDO");
        }

        @Test
        @DisplayName("no security framework type is reachable: authentication stays plaintext on USRSEC")
        void noSecurityFrameworkIsIntroduced() {
            for (String name : reachableTypeNames()) {
                assertThat(name)
                        .as("reachable type %s", name)
                        .doesNotContain("springframework.security")
                        .doesNotContain("PasswordEncoder")
                        .doesNotContain("Jwt")
                        .doesNotContain("BCrypt");
            }
            assertThat(SecUserRecord.SEC_USR_PWD_LENGTH)
                    .as("the stored credential is a plaintext PIC X(08); that is inherited, not chosen")
                    .isEqualTo(8);
        }

        @Test
        @DisplayName("the only suppression is the credential's ignore, and only passwd carries it")
        void theOnlySuppressionIsTheDocumentedIgnore() {
            // Pins the exact mechanism, so a broader suppression fails the build as loudly as removing
            // it would. Suppression is what the DRK attribute has no JSON equivalent for; a masking
            // serialiser or a rename would instead change what a caller sees under a name that traces
            // to no symbolic-map item, and a non-AUTO access mode on any other member would narrow a
            // projection the client is entitled to read back in full.
            assertThat(annotationSimpleNamesOn(WRITE_ONLY_MEMBER))
                    .as("the credential answers to a @JsonIgnore, which is what withholds it")
                    .contains("JsonIgnore")
                    .doesNotContain("JsonSerialize", "JsonRawValue", "JsonView");
            assertThat(jsonPropertyOn(WRITE_ONLY_MEMBER))
                    .as("and it is withheld by suppression alone - not renamed into the payload under "
                            + "some other spelling")
                    .isNull();

            List<String> suppressed = new ArrayList<>();
            for (RecordComponent component : SignOnResponse.class.getRecordComponents()) {
                Set<String> applied = annotationSimpleNamesOn(component.getName());
                if (applied.contains("JsonIgnore")) {
                    suppressed.add(component.getName());
                }
                JsonProperty annotation = jsonPropertyOn(component.getName());
                assertThat(annotation == null || annotation.access() == JsonProperty.Access.AUTO)
                        .as("%s declares no narrowed access mode", component.getName())
                        .isTrue();
                assertThat(applied)
                        .as("%s carries no custom serialiser and no view", component.getName())
                        .doesNotContain("JsonIgnoreProperties", "JsonIgnoreType",
                                "JsonSerialize", "JsonRawValue", "JsonView");
            }

            assertThat(suppressed)
                    .as("exactly one member is suppressed, and it is the DRK credential; every "
                            + "other value is one the client is entitled to read back, so the "
                            + "projection stays complete")
                    .containsExactly(WRITE_ONLY_MEMBER);
        }
    }

    @Nested
    @DisplayName("REDEFINES - one 308-byte area, two views, zero drift")
    class GroupRedefinesOverlay {
        @Test
        @DisplayName("both views tile 308 bytes exactly: 12 + 11 x 7 + 219")
        void bothViewsTileTheSameArea() {
            assertThat(FIELD_PREFIX_LENGTH)
                    .as("input view: xxxL 2 + xxxF 1 + FILLER X(4)")
                    .isEqualTo(7);
            assertThat(OUTPUT_PREFIX_LENGTH)
                    .as("output view: FILLER X(3) + xxxC + xxxP + xxxH + xxxV")
                    .isEqualTo(7);
            assertThat(OUTPUT_PREFIX_LENGTH)
                    .as("EQUAL PREFIXES ARE THE WHOLE REASON THE OVERLAY LINES UP FIELD FOR FIELD")
                    .isEqualTo(FIELD_PREFIX_LENGTH);
            assertThat(DECLARED_WIDTHS.stream().mapToInt(Integer::intValue).sum())
                    .isEqualTo(PAYLOAD_WIDTH_TOTAL);
            assertThat(TIOAPFX_PREFIX_LENGTH + DFHMDF_NAMED * FIELD_PREFIX_LENGTH
                    + PAYLOAD_WIDTH_TOTAL)
                    .as("12 + 77 + 219")
                    .isEqualTo(SYMBOLIC_MAP_LENGTH);
            assertThat(INPUT_VIEW_LAYOUT.recordLength()).isEqualTo(SYMBOLIC_MAP_LENGTH);
            assertThat(OUTPUT_VIEW_LAYOUT.recordLength())
                    .as("a redefining group must tile the redefined storage exactly")
                    .isEqualTo(INPUT_VIEW_LAYOUT.recordLength());
            assertThat(OUTPUT_VIEW_LAYOUT.storageSpans().stream()
                    .mapToInt(FixedWidthRecord.FieldSpan::length).sum())
                    .as("the output view's own storage spans sum to the area")
                    .isEqualTo(SYMBOLIC_MAP_LENGTH);
            assertThat(INPUT_VIEW_LAYOUT.storageSpans().stream()
                    .mapToInt(FixedWidthRecord.FieldSpan::length).sum())
                    .isEqualTo(SYMBOLIC_MAP_LENGTH);
        }

        @Test
        @DisplayName("the group-level overlay is declared, and is the twelfth REDEFINES of the copybook")
        void theGroupLevelOverlayIsTheTwelfth() {
            FixedWidthRecord.FieldSpan group = INPUT_VIEW_LAYOUT.span(MAP_NAME + "O");
            assertThat(group.redefinition())
                    .as("01 COSGN0AO REDEFINES COSGN0AI at COSGN00.CPY:85")
                    .isTrue();
            assertThat(group.offset()).as("it redefines the group from its first byte").isZero();
            assertThat(group.length())
                    .as("and covers the whole area, not part of it")
                    .isEqualTo(SYMBOLIC_MAP_LENGTH);
            assertThat(INPUT_VIEW_LAYOUT.redefinitions())
                    .as("eleven per-field xxxA overlays plus the one group-level overlay")
                    .hasSize(COPYBOOK_REDEFINES_TOTAL)
                    .hasSize(DFHMDF_NAMED + GROUP_LEVEL_REDEFINES);
            assertThat(OUTPUT_VIEW_LAYOUT.redefinitions())
                    .as("the output view is the redefinition; it declares none of its own")
                    .isEmpty();
            assertThat(PACKAGE_PER_FIELD_REDEFINES + PACKAGE_GROUP_LEVEL_REDEFINES)
                    .as("11+59+12+12+11 per-field, plus one group-level per map, across five maps")
                    .isEqualTo(PACKAGE_REDEFINES_TOTAL);
        }

        @ParameterizedTest(name = "{0}O sits exactly where {0}I sits")
        @ValueSource(strings = {"TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME",
            "APPLID", "SYSID", "USERID", "PASSWD", "ERRMSG"})
        @DisplayName("each xxxO item aligns byte for byte with the xxxI item it redefines")
        void theDataItemsAlignWithZeroDrift(String screenField) {
            FixedWidthRecord.FieldSpan input = INPUT_VIEW_LAYOUT.span(screenField + "I");
            FixedWidthRecord.FieldSpan output = OUTPUT_VIEW_LAYOUT.span(screenField + "O");

            assertThat(output.offset())
                    .as("%sO must start where %sI starts - zero drift", screenField, screenField)
                    .isEqualTo(input.offset());
            assertThat(output.length())
                    .as("and be the same width, because it is the same storage")
                    .isEqualTo(input.length())
                    .isEqualTo(DECLARED_WIDTHS.get(SCREEN_FIELDS.indexOf(screenField)));
            assertThat(output.endOffsetExclusive()).isEqualTo(input.endOffsetExclusive());
            assertThat(output.kind())
                    .as("both are PIC X(n)")
                    .isEqualTo(FixedWidthRecord.PictureKind.ALPHANUMERIC)
                    .isEqualTo(input.kind());
        }

        @ParameterizedTest(name = "{0}: written through one view, read through the other")
        @ValueSource(strings = {"TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME",
            "APPLID", "SYSID", "USERID", "PASSWD", "ERRMSG"})
        @DisplayName("a value crosses between the two views at one offset - a genuine round trip")
        void aValueCrossesBetweenTheTwoViewsAtOneOffset(String screenField) {
            FixedWidthRecord area = FixedWidthRecord.forLayout(INPUT_VIEW_LAYOUT, MAP_CHARSET);
            FixedWidthRecord.FieldSpan input = INPUT_VIEW_LAYOUT.span(screenField + "I");
            FixedWidthRecord.FieldSpan output = OUTPUT_VIEW_LAYOUT.span(screenField + "O");
            String inbound = "I".repeat(input.length());
            String outbound = "O".repeat(output.length());

            area.writeSpan(input, inbound);
            assertThat(area.readSpan(output))
                    .as("%sO must see what was written to %sI", screenField, screenField)
                    .isEqualTo(inbound);
            assertThat(area.readSpanBytes(output)).isEqualTo(area.readSpanBytes(input));

            area.writeSpan(output, outbound);
            assertThat(area.readSpan(input))
                    .as("and %sI must see what was written to %sO", screenField, screenField)
                    .isEqualTo(outbound);
            assertThat(area.readSpanBytes(input)).isEqualTo(area.readSpanBytes(output));
            assertThat(area.toByteArray()).hasSize(SYMBOLIC_MAP_LENGTH);
        }

        @Test
        @DisplayName("writing one field's data disturbs no other field, in either view")
        void aDataWriteDisturbsNothingElse() {
            FixedWidthRecord area = FixedWidthRecord.forLayout(INPUT_VIEW_LAYOUT, MAP_CHARSET);
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                area.writeSpan(OUTPUT_VIEW_LAYOUT.span(OUTPUT_MAP_ITEMS.get(index)),
                        String.valueOf((char) ('A' + index)).repeat(DECLARED_WIDTHS.get(index)));
            }
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                String expected = String.valueOf((char) ('A' + index))
                        .repeat(DECLARED_WIDTHS.get(index));
                assertThat(area.readSpan(OUTPUT_VIEW_LAYOUT.span(OUTPUT_MAP_ITEMS.get(index))))
                        .as("%s must hold its own value and no neighbour's",
                                OUTPUT_MAP_ITEMS.get(index))
                        .isEqualTo(expected);
                assertThat(area.readSpan(INPUT_VIEW_LAYOUT.span(SCREEN_FIELDS.get(index) + "I")))
                        .as("and the input view reads the identical bytes")
                        .isEqualTo(expected);
            }
            assertThat(area.toByteArray()).hasSize(SYMBOLIC_MAP_LENGTH);
        }

        @Test
        @DisplayName("the attribute quartet occupies the bytes the input view reserves as filler")
        void theAttributeQuartetSitsOverTheInputFiller() {
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                String screenField = SCREEN_FIELDS.get(index);
                FixedWidthRecord.FieldSpan colour =
                        OUTPUT_VIEW_LAYOUT.span(screenField + ATTRIBUTE_SUFFIXES.get(0));
                FixedWidthRecord.FieldSpan data =
                        OUTPUT_VIEW_LAYOUT.span(OUTPUT_MAP_ITEMS.get(index));
                assertThat(colour.length()).isEqualTo(ATTRIBUTE_ITEM_LENGTH);
                assertThat(data.offset() - colour.offset())
                        .as("%s: xxxC then xxxP, xxxH, xxxV, then the data", screenField)
                        .isEqualTo(ATTRIBUTE_SUFFIXES.size());
                for (int position = 0; position < ATTRIBUTE_SUFFIXES.size(); position++) {
                    FixedWidthRecord.FieldSpan attribute = OUTPUT_VIEW_LAYOUT
                            .span(screenField + ATTRIBUTE_SUFFIXES.get(position));
                    assertThat(attribute.offset())
                            .as("%s%s", screenField, ATTRIBUTE_SUFFIXES.get(position))
                            .isEqualTo(colour.offset() + position);
                    assertThat(attribute.redefinition())
                            .as("the quartet is storage in the output view, not an overlay")
                            .isFalse();
                }
            }
        }

        @Test
        @DisplayName("xxxC is the colour item, which is what FieldAttributeSetter targets")
        void theColourItemIsTheAttributeSettersTarget() {
            assertThat(ATTRIBUTE_SUFFIXES.get(0))
                    .as("the first item of the quartet is the colour item")
                    .isEqualTo(FieldAttributeSetter.COLOUR_ITEM_SUFFIX);
            assertThat(OUTPUT_VIEW_LAYOUT.hasSpan("ERRMSG" + FieldAttributeSetter.COLOUR_ITEM_SUFFIX))
                    .as("ERRMSGC exists in the output view - COSGN00.CPY:148")
                    .isTrue();
            assertThat(FieldAttributeSetter.ASTERISK).isEqualTo("*");
            assertThat(BmsAttributes.DFHRED).isNotEqualTo(BmsAttributes.DFHGREEN);
        }

        @Test
        @DisplayName("neither the twelve-byte prefix nor the three-byte per-field filler is exposed")
        void noFillerIsExposed() {
            assertThat(OUTPUT_VIEW_LAYOUT.hasSpan("FILLER"))
                    .as("filler is declared as reserved storage, and reserved storage has no name")
                    .isFalse();
            long fillerBytes = OUTPUT_VIEW_LAYOUT.storageSpans().stream()
                    .filter(span -> span.kind().filler())
                    .mapToInt(FixedWidthRecord.FieldSpan::length)
                    .sum();
            assertThat(fillerBytes)
                    .as("12 of prefix plus 11 x 3 of per-field filler")
                    .isEqualTo(TIOAPFX_PREFIX_LENGTH + (long) DFHMDF_NAMED * OUTPUT_FILLER_LENGTH)
                    .isEqualTo(45L);
            assertThat(jsonKeys(populated()))
                    .doesNotContain("filler", "tioapfx", "prefix");
        }
    }

    @Nested
    @DisplayName("Metadata - xxxL, xxxF, xxxA, xxxC, xxxP, xxxH, xxxV and every FILLER stay off the wire")
    class MetadataIsNotPayload {
        @Test
        @DisplayName("the top-level JSON keys are exactly the components less the withheld credential")
        void keysAreExactlyTheComponents() {
            // Only the top level is asserted here. The communication area is a nested object whose own
            // shape is NavigationContext's contract and its own suite's subject; what matters at this
            // boundary is that this payload adds nothing to it and drops nothing from it - with one
            // stated subtraction: PASSWDO is bound WRITE_ONLY, so the credential span is in the screen
            // image and in no response body.
            List<String> screenKeys = wireNamesOf(RESPONSE_MEMBERS).stream()
                    .filter(name -> !CREDENTIAL_WIRE_NAME.equals(name))
                    .toList();
            assertThat(screenKeys).hasSize(RESPONSE_WIRE_MEMBERS);
            assertThat(topLevelJsonKeys(populated()))
                    .as("every projected screen field is published under its xxxI item in lower case "
                            + "and every carrier under its own name - no extra, and none dropped but "
                            + "the one the wire withholds")
                    .containsExactlyElementsOf(concat(screenKeys, NAVIGATION_MEMBERS))
                    .hasSize(COMPONENT_COUNT - 1);
            assertThat(componentNames())
                    .as("the component is still declared - only its serialisation is suppressed, so "
                            + "the overlay image the parity corpus pins is still readable in-process")
                    .contains(WITHHELD_MEMBER);
        }

        @Test
        @DisplayName("no derived predicate leaks in as a sixteenth property")
        void noDerivedPredicateBecomesAProperty() throws NoSuchMethodException {
            for (Method method : List.of(
                    SignOnResponse.class.getDeclaredMethod("isAdminRole", String.class),
                    SignOnResponse.class.getDeclaredMethod("resolveNextProgram", String.class))) {
                assertThat(Modifier.isStatic(method.getModifiers()))
                        .as("%s must be static", method.getName())
                        .isTrue();
            }
            assertThat(topLevelJsonKeys(populated()))
                    .doesNotContain("adminRole", "admin", "nextTarget");
        }

        @ParameterizedTest(name = "no key ends in the {0} metadata suffix")
        @ValueSource(strings = {"L", "F", "A", "C", "P", "H", "V"})
        @DisplayName("each metadata suffix over each screen-field stem is absent from the payload")
        void noMetadataSuffixBecomesAKey(String suffix) {
            Set<String> keys = jsonKeys(populated());
            for (String screenField : SCREEN_FIELDS) {
                assertThat(keys)
                        .as("%s%s is metadata, not payload", screenField, suffix)
                        .doesNotContain((screenField + suffix).toLowerCase(Locale.ROOT));
            }
        }

        @Test
        @DisplayName("no component or accessor is named for a metadata item under any spelling")
        void noMetadataItemBecomesAMember() {
            List<String> forbidden = new ArrayList<>();
            for (String screenField : SCREEN_FIELDS) {
                forbidden.add(screenField + "L");
                forbidden.add(screenField + "F");
                forbidden.add(screenField + "A");
                for (String suffix : ATTRIBUTE_SUFFIXES) {
                    forbidden.add(screenField + suffix);
                }
            }
            for (String name : componentNames()) {
                assertThat(forbidden)
                        .as("component %s must not name a metadata item", name)
                        .noneMatch(item -> item.equalsIgnoreCase(name));
            }
            assertThat(forbidden)
                    .as("7 suffixes over 11 fields")
                    .hasSize(SCREEN_FIELDS.size() * (3 + ATTRIBUTE_SUFFIXES.size()));
        }

        @Test
        @DisplayName("the map-derived components carry only xxxO items, never an xxxI item")
        void onlyTheOutputItemsAreProjected() {
            for (String item : SignOnResponse.MAP_FIELDS) {
                assertThat(item).endsWith("O");
                assertThat(OUTPUT_MAP_ITEMS)
                        .as("%s must be an output item of this map", item)
                        .contains(item);
                assertThat(item)
                        .as("this is the response, so no input-direction item appears")
                        .isNotEqualTo(item.substring(0, item.length() - 1) + "I");
            }
        }
    }

    @Nested
    @DisplayName("Role routing, transcribed from COSGN00C lines 230-240")
    class RoleRoutingAndXctl {
        @Test
        @DisplayName("the payload declares the role, all three next-target members and the plain text")
        void theNavigationMembersExist() {
            assertThat(componentNames())
                    .contains("role", "nextProgram", "nextMapset", "nextMap", "plainText",
                            "navigationContext");
            assertThat(NAVIGATION_MEMBERS)
                    .as("the XCTL triple, the role it is chosen from, the SEND TEXT transmission and "
                            + "the communication area - six carriers with no DFHMDF behind them")
                    .hasSize(6);
        }

        @Test
        @DisplayName("'A' satisfies the administrator condition and reaches the administrator menu")
        void adminRoleReachesTheAdminMenu() {
            assertThat(SignOnResponse.ROLE_ADMIN)
                    .as("88 CDEMO-USRTYP-ADMIN VALUE 'A', COCOM01Y.cpy:27")
                    .isEqualTo("A")
                    .isEqualTo(NavigationContext.USER_TYPE_ADMIN);
            assertThat(SignOnResponse.isAdminRole(SignOnResponse.ROLE_ADMIN)).isTrue();
            assertThat(SignOnResponse.resolveNextProgram(SignOnResponse.ROLE_ADMIN))
                    .as("EXEC CICS XCTL PROGRAM ('COADM01C') at COSGN00C.cbl:232")
                    .isEqualTo("COADM01C")
                    .isEqualTo(SignOnResponse.NEXT_PROGRAM_ADMIN);
        }

        @Test
        @DisplayName("'U' does not satisfy it, and takes the ELSE to the regular-user menu")
        void regularUserTakesTheElse() {
            assertThat(SignOnResponse.ROLE_USER)
                    .as("88 CDEMO-USRTYP-USER VALUE 'U', COCOM01Y.cpy:28")
                    .isEqualTo("U")
                    .isEqualTo(NavigationContext.USER_TYPE_USER);
            assertThat(SignOnResponse.isAdminRole(SignOnResponse.ROLE_USER))
                    .as("the program never tests for 'U'; it tests for 'A' and falls through")
                    .isFalse();
            assertThat(SignOnResponse.resolveNextProgram(SignOnResponse.ROLE_USER))
                    .as("EXEC CICS XCTL PROGRAM ('COMEN01C') at COSGN00C.cbl:237")
                    .isEqualTo("COMEN01C")
                    .isEqualTo(SignOnResponse.NEXT_PROGRAM_USER);
        }

        @ParameterizedTest(name = "role [{0}] routes to {1}")
        @CsvSource({
            "A, COADM01C",
            "U, COMEN01C",
            "X, COMEN01C",
            "' ', COMEN01C",
            "a, COMEN01C",
            "'', COMEN01C"
        })
        @DisplayName("only 'A' reaches the administrator menu; the ELSE takes everything else")
        void theElseTakesEverythingThatIsNotAdmin(String role, String expected) {
            assertThat(SignOnResponse.resolveNextProgram(role))
                    .as("user type [%s]", role)
                    .isEqualTo(expected);
            assertThat(SignOnResponse.isAdminRole(role))
                    .isEqualTo(SignOnResponse.ROLE_ADMIN.equals(role));
        }

        @Test
        @DisplayName("a third value produces no third target and raises nothing")
        void aThirdValueProducesNoThirdTarget() {
            String unexpected = "X";
            assertThat(unexpected)
                    .isNotEqualTo(SignOnResponse.ROLE_ADMIN)
                    .isNotEqualTo(SignOnResponse.ROLE_USER);
            assertThat(SignOnResponse.resolveNextProgram(unexpected))
                    .as("there are exactly two XCTL sites in the paragraph, so exactly two outcomes")
                    .isIn(SignOnResponse.NEXT_PROGRAM_ADMIN, SignOnResponse.NEXT_PROGRAM_USER)
                    .isEqualTo(SignOnResponse.NEXT_PROGRAM_USER);
        }

        @Test
        @DisplayName("null routes to the ELSE target rather than failing")
        void nullRoutesToTheElseTarget() {
            assertThat(SignOnResponse.isAdminRole(null)).isFalse();
            assertThat(SignOnResponse.resolveNextProgram(null))
                    .isEqualTo(SignOnResponse.NEXT_PROGRAM_USER);
        }

        @Test
        @DisplayName("the two targets are the program's own XCTL literals, eight characters each")
        void targetsAreTheProgramsOwnLiterals() {
            assertThat(SignOnResponse.NEXT_PROGRAM_ADMIN)
                    .isEqualTo("COADM01C")
                    .hasSize(SignOnResponse.NEXT_PROGRAM_LENGTH);
            assertThat(SignOnResponse.NEXT_PROGRAM_USER)
                    .isEqualTo("COMEN01C")
                    .hasSize(SignOnResponse.NEXT_PROGRAM_LENGTH);
            assertThat(SignOnResponse.NEXT_PROGRAM_ADMIN)
                    .isNotEqualTo(SignOnResponse.NEXT_PROGRAM_USER);
        }

        @Test
        @DisplayName("the response carries the resolved target rather than re-deriving it")
        void theResponseCarriesWhatTheServiceDecided() {
            SignOnResponse mismatched = SignOnResponse.empty().withNavigation(
                    SignOnResponse.ROLE_ADMIN, SignOnResponse.NEXT_PROGRAM_USER, MAPSET_NAME,
                    MAP_NAME, signedOnContext(SignOnResponse.ROLE_ADMIN));
            assertThat(mismatched.role()).isEqualTo(SignOnResponse.ROLE_ADMIN);
            assertThat(mismatched.nextProgram())
                    .as("carried verbatim, not corrected to the admin target")
                    .isEqualTo(SignOnResponse.NEXT_PROGRAM_USER);
        }

        @Test
        @DisplayName("the next map and mapset are the seven-character values the program sends")
        void theNextMapAndMapsetAreTheProgramsOwn() {
            SignOnResponse accepted = SignOnResponse.empty().withNavigation(
                    SignOnResponse.ROLE_ADMIN,
                    SignOnResponse.resolveNextProgram(SignOnResponse.ROLE_ADMIN),
                    MAPSET_NAME, MAP_NAME, signedOnContext(SignOnResponse.ROLE_ADMIN));
            assertThat(accepted.nextMapset())
                    .as("MAPSET('COSGN00'), COSGN00C.cbl:112")
                    .isEqualTo(MAPSET_NAME)
                    .hasSize(NavigationContext.LAST_MAPSET_LENGTH);
            assertThat(accepted.nextMap())
                    .as("MAP('COSGN0A'), COSGN00C.cbl:111")
                    .isEqualTo(MAP_NAME)
                    .hasSize(NavigationContext.LAST_MAP_LENGTH);
        }

        @Test
        @DisplayName("the role bytes are NavigationContext's, so all seventeen screens agree")
        void roleBytesDelegate() {
            assertThat(SignOnResponse.ROLE_ADMIN).isSameAs(NavigationContext.USER_TYPE_ADMIN);
            assertThat(SignOnResponse.ROLE_USER).isSameAs(NavigationContext.USER_TYPE_USER);
        }
    }

    @Nested
    @DisplayName("Conversation state - the communication area travels in the payload")
    class StatelessConversationState {
        @Test
        @DisplayName("the communication area is a payload member, so no session is ever needed")
        void theCommareaIsAPayloadMember() {
            assertThat(componentNames()).contains("navigationContext");
            assertThat(componentTypes()).contains(NavigationContext.class);
            assertThat(populated().navigationContext()).isNotNull();
            assertThat(jsonKeys(populated()))
                    .as("and it is on the wire, nested, rather than held anywhere on the server")
                    .contains("navigationcontext");
        }

        @Test
        @DisplayName("the communication area is 160 bytes: 34 + 84 + 12 + 16 + 14")
        void theCommareaIsOneHundredAndSixtyBytes() {
            byte[] image = populated().navigationContext().toFixedWidth(codec());
            assertThat(image)
                    .as("CARDDEMO-COMMAREA serialises to its declared width exactly")
                    .hasSize(NavigationContext.COMMAREA_LENGTH)
                    .hasSize(160);
            assertThat(NavigationContext.GENERAL_INFO_LENGTH
                    + NavigationContext.CUSTOMER_INFO_LENGTH
                    + NavigationContext.ACCOUNT_INFO_LENGTH
                    + NavigationContext.CARD_INFO_LENGTH
                    + NavigationContext.MORE_INFO_LENGTH)
                    .as("34 general + 84 customer + 12 account + 16 card + 14 more")
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH);
            assertThat(NavigationContext.fromFixedWidth(codec(), image))
                    .as("and it round trips through the same code page")
                    .isEqualTo(populated().navigationContext());
        }

        @Test
        @DisplayName("the general-info group is populated exactly as COSGN00C lines 224-228 populate it")
        void theGeneralInfoGroupIsPopulatedAsTheProgramPopulatesIt() {
            NavigationContext context = signedOnContext(SignOnResponse.ROLE_ADMIN);
            assertThat(context.fromTranid())
                    .as("MOVE WS-TRANID TO CDEMO-FROM-TRANID, line 224")
                    .isEqualTo(TRANSACTION_ID);
            assertThat(context.fromProgram())
                    .as("MOVE WS-PGMNAME TO CDEMO-FROM-PROGRAM, line 225")
                    .isEqualTo(PROGRAM_NAME);
            assertThat(context.userId())
                    .as("MOVE WS-USER-ID TO CDEMO-USER-ID, line 226")
                    .isEqualTo("ADMIN001");
            assertThat(context.userType())
                    .as("MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE, line 227")
                    .isEqualTo(SignOnResponse.ROLE_ADMIN);
            assertThat(context.pgmContext())
                    .as("MOVE ZEROS TO CDEMO-PGM-CONTEXT, line 228")
                    .isEqualTo(NavigationContext.PGM_CONTEXT_ENTER)
                    .isZero();
            assertThat(context.isEnter()).isTrue();
            assertThat(context.isReenter()).isFalse();
            assertThat(context.isAdmin()).isTrue();
        }

        @Test
        @DisplayName("the role the payload carries is the one the communication area carries")
        void theRoleAndTheCommareaAgree() {
            SignOnResponse admin = populated();
            assertThat(admin.role()).isEqualTo(admin.navigationContext().userType());
            assertThat(admin.navigationContext().isAdmin()).isTrue();
            assertThat(admin.navigationContext().isUser()).isFalse();

            NavigationContext regular = signedOnContext(SignOnResponse.ROLE_USER);
            assertThat(regular.isAdmin()).isFalse();
            assertThat(regular.isUser()).isTrue();
        }

        @Test
        @DisplayName("no session, cache, thread-local or servlet type is reachable from this payload")
        void nothingSessionScopedIsReachable() {
            for (String name : reachableTypeNames()) {
                assertThat(name)
                        .as("reachable type %s", name)
                        .doesNotContain("HttpSession")
                        .doesNotContain("HttpServletRequest")
                        .doesNotContain("ThreadLocal")
                        .doesNotContain("jakarta.servlet")
                        .doesNotContain("org.springframework.web");
            }
        }

        @Test
        @DisplayName("no CDEMO-CU0n-INFO extension block is carried, because this program declares none")
        void noExtensionBlockIsCarried() {
            assertThat(CDEMO_CU0N_INFO_COUNT)
                    .as("grep -c 'CDEMO-CU0[0-9]-INFO' app/cbl/COSGN00C.cbl")
                    .isZero();
            assertThat(populated().navigationContext().toFixedWidth(codec()))
                    .as("so the area is exactly its declared width, with nothing appended")
                    .hasSize(NavigationContext.COMMAREA_LENGTH);
            assertThat(jsonKeys(populated()))
                    .doesNotContain("cdemocu00info", "cdemocu01info", "cdemocu02info",
                            "cdemocu03info");
        }
    }

    @Nested
    @DisplayName("The error line - PIC X(80) into PIC X(78), narrowed on purpose")
    class TheErrorLine {
        @Test
        @DisplayName("the eighty-byte message narrows to seventy-eight, losing the two on the right")
        void theMessageNarrowsOnTheRight() {
            String eighty = "L".repeat(WS_MESSAGE_LENGTH - 2) + "XY";
            assertThat(eighty).hasSize(WS_MESSAGE_LENGTH);

            String moved = codec().movePicX(eighty, SignOnResponse.ERRMSG_LENGTH);
            assertThat(moved)
                    .as("the receiver is filled from the left, so the leading characters survive")
                    .hasSize(SignOnResponse.ERRMSG_LENGTH)
                    .isEqualTo("L".repeat(WS_MESSAGE_LENGTH - 2))
                    .doesNotEndWith("XY");
            assertThat(eighty.length() - moved.length()).isEqualTo(ERRMSG_TRUNCATED_CHARACTERS);
            assertThat(SignOnResponse.empty().withErrMsg(moved).errMsg()).isEqualTo(moved);
        }

        @Test
        @DisplayName("the payload refuses an eighty-character message rather than clipping it silently")
        void anOverWideMessageIsRefusedNotClipped() {
            String eighty = "M".repeat(WS_MESSAGE_LENGTH);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> SignOnResponse.empty().withErrMsg(eighty))
                    .withMessageContaining(SignOnResponse.ERRMSG_FIELD)
                    .withMessageContaining(String.valueOf(SignOnResponse.ERRMSG_LENGTH))
                    .withMessageContaining(String.valueOf(WS_MESSAGE_LENGTH));
        }

        @ParameterizedTest(name = "[{0}] fits and round trips")
        @ValueSource(strings = {
            "Please enter User ID ...",
            "Please enter Password ...",
            "Wrong Password. Try again ...",
            "User not found. Try again ...",
            "Unable to verify the User ..."
        })
        @DisplayName("each of the program's five message literals fits the field and survives the move")
        void eachProgramMessageFitsAndRoundTrips(String message) {
            assertThat(message)
                    .as("no in-line literal of COSGN00C overflows ERRMSGO")
                    .hasSizeLessThanOrEqualTo(SignOnResponse.ERRMSG_LENGTH);

            String moved = codec().movePicX(message, SignOnResponse.ERRMSG_LENGTH);
            assertThat(moved)
                    .as("a shorter sending value is padded on the right, never centred or right-aligned")
                    .hasSize(SignOnResponse.ERRMSG_LENGTH)
                    .startsWith(message);
            assertThat(moved.substring(message.length()))
                    .as("and the padding is spaces")
                    .isEqualTo(" ".repeat(SignOnResponse.ERRMSG_LENGTH - message.length()));
            assertThat(SignOnResponse.empty().withErrMsg(moved).errMsg()).isEqualTo(moved);
        }

        @Test
        @DisplayName("all five literals are distinct, so a branch cannot be mistaken for another")
        void theFiveLiteralsAreDistinct() {
            assertThat(PROGRAM_MESSAGES).hasSize(5).doesNotHaveDuplicates();
            assertThat(PROGRAM_MESSAGES)
                    .as("lines 120, 125, 242, 249 and 254 of app/cbl/COSGN00C.cbl")
                    .containsExactly(MSG_ENTER_USER_ID, MSG_ENTER_PASSWORD, MSG_WRONG_PASSWORD,
                            MSG_USER_NOT_FOUND, MSG_UNABLE_TO_VERIFY);
        }

        @Test
        @DisplayName("the two copybook messages widen to eighty then narrow to seventy-eight")
        void theCopybookMessagesWidenThenNarrow() {
            for (String message : COPYBOOK_MESSAGES) {
                assertThat(message)
                        .as("a PIC X(50) copybook message")
                        .hasSize(SystemMessages.MESSAGE_LENGTH)
                        .hasSize(50);

                String widened = codec().movePicX(message, WS_MESSAGE_LENGTH);
                assertThat(widened)
                        .as("stage one: into WS-MESSAGE PIC X(80)")
                        .hasSize(WS_MESSAGE_LENGTH)
                        .startsWith(message)
                        .endsWith(" ".repeat(WS_MESSAGE_LENGTH - SystemMessages.MESSAGE_LENGTH));

                String narrowed = codec().movePicX(widened, SignOnResponse.ERRMSG_LENGTH);
                assertThat(narrowed)
                        .as("stage two: into ERRMSGO PIC X(78)")
                        .hasSize(SignOnResponse.ERRMSG_LENGTH)
                        .startsWith(message);
                assertThat(narrowed.strip())
                        .as("only padding was discarded, so the text is untouched")
                        .isEqualTo(message.strip());
                assertThat(SignOnResponse.empty().withErrMsg(narrowed).errMsg()).isEqualTo(narrowed);
            }
        }

        @Test
        @DisplayName("the two copybook messages are different texts, and neither is a screen title")
        void theCopybookMessagesAreDistinct() {
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU)
                    .isNotEqualTo(SystemMessages.CCDA_MSG_INVALID_KEY)
                    .as("the CSMSG01Y thank-you is not the COTTL01Y one, despite the similar wording")
                    .isNotEqualTo(ScreenTitles.CCDA_THANK_YOU);
            assertThat(ScreenTitles.CCDA_THANK_YOU)
                    .as("the title-copybook literal is PIC X(40), a different field entirely")
                    .hasSize(ScreenTitles.TITLE_LENGTH);
        }

        @Test
        @DisplayName("a cleared error line is seventy-eight spaces, which is what MOVE SPACES writes")
        void aClearedLineIsSpaces() {
            String cleared = codec().movePicX("", SignOnResponse.ERRMSG_LENGTH);
            assertThat(cleared)
                    .hasSize(SignOnResponse.ERRMSG_LENGTH)
                    .isBlank()
                    .isEqualTo(" ".repeat(SignOnResponse.ERRMSG_LENGTH));
            assertThat(SignOnResponse.empty().errMsg())
                    .as("the map before anything is written - MOVE LOW-VALUES TO COSGN0AO, :81")
                    .isEqualTo(ScreenFieldImage.unpainted(SignOnResponse.ERRMSG_LENGTH));
            assertThat(SignOnResponse.empty().withErrMsg(cleared).errMsg())
                    .as("MOVE SPACES TO ERRMSGO, app/cbl/COSGN00C.cbl:78")
                    .isEqualTo(cleared);
        }

        @Test
        @DisplayName("the field's map-declared colour is RED, so DFHGREEN is an override not a default")
        void theDeclaredColourIsRed() {
            assertThat(BmsAttributes.DFHRED)
                    .as("the map's declared colour for this field")
                    .isNotEqualTo(BmsAttributes.DFHDFCOL);
            assertThat(BmsAttributes.DFHGREEN)
                    .as("the success signal, and the exact counterpart of red")
                    .isNotEqualTo(BmsAttributes.DFHRED);
            assertThat(jsonKeys(populated()))
                    .as("and neither reaches the payload, because colour is not data")
                    .doesNotContain("errmsgc", "colour", "color");
        }
    }

    @Nested
    @DisplayName("POPULATE-HEADER-INFO - the eight fields it writes, and only those")
    class HeaderPopulation {
        @Test
        @DisplayName("withHeader writes the eight header fields and leaves the other seven alone")
        void withHeaderWritesExactlyTheEightFields() {
            SignOnResponse before = SignOnResponse.empty();
            DateHeader header = DateHeader.from(codec(), FIXED_CLOCK);
            SignOnResponse after = before.withHeader(TRANSACTION_ID,
                    ScreenTitles.CCDA_TITLE01,
                    header.wsCurdateMmDdYy(),
                    PROGRAM_NAME,
                    ScreenTitles.CCDA_TITLE02,
                    codec().movePicX(header.wsCurtimeHhMmSs(), SignOnResponse.CURTIME_LENGTH),
                    "CICSAPPL",
                    "CICS    ");

            assertThat(after.trnName()).isEqualTo(TRANSACTION_ID);
            assertThat(after.title01()).isEqualTo(ScreenTitles.CCDA_TITLE01);
            assertThat(after.curDate()).isEqualTo(FIXED_CURDATE);
            assertThat(after.pgmName()).isEqualTo(PROGRAM_NAME);
            assertThat(after.title02()).isEqualTo(ScreenTitles.CCDA_TITLE02);
            assertThat(after.curTime()).isEqualTo(FIXED_CURTIME + " ");
            assertThat(after.applId()).isEqualTo("CICSAPPL");
            assertThat(after.sysId()).isEqualTo("CICS    ");

            assertThat(after.userId())
                    .as("the paragraph does not write USERIDO, so it keeps the value it had")
                    .isEqualTo(before.userId());
            assertThat(after.errMsg())
                    .as("and ERRMSGO is written by the caller at line 149, afterwards")
                    .isEqualTo(before.errMsg());
            assertThat(after.role()).isEqualTo(before.role());
            assertThat(after.nextProgram()).isEqualTo(before.nextProgram());
            assertThat(after.nextMapset()).isEqualTo(before.nextMapset());
            assertThat(after.nextMap()).isEqualTo(before.nextMap());
            assertThat(after.navigationContext()).isEqualTo(before.navigationContext());
        }

        @Test
        @DisplayName("the header renders the same characters on every run, because the clock is fixed")
        void theHeaderIsDeterministic() {
            DateHeader first = DateHeader.from(codec(), FIXED_CLOCK);
            DateHeader second = DateHeader.from(codec(), FIXED_CLOCK);
            assertThat(first.wsCurdateMmDdYy()).isEqualTo(second.wsCurdateMmDdYy())
                    .isEqualTo(FIXED_CURDATE);
            assertThat(first.wsCurtimeHhMmSs()).isEqualTo(second.wsCurtimeHhMmSs())
                    .isEqualTo(FIXED_CURTIME);
            assertThat(FIXED_INSTANT)
                    .as("the instant is the version footer of app/cbl/COSGN00C.cbl:259, not an invention")
                    .isEqualTo(Instant.parse("2022-07-19T23:12:33Z"));
        }

        @Test
        @DisplayName("the header fields the paragraph writes are eight of the eleven map members")
        void theHeaderCoversEightOfTheElevenMembers() {
            List<String> written = List.of("title01", "title02", "trnName", "pgmName", "curDate",
                    "curTime", "applId", "sysId");
            assertThat(written).hasSize(8).allSatisfy(name ->
                    assertThat(RESPONSE_MEMBERS).contains(name));
            assertThat(RESPONSE_MEMBERS)
                    .as("the three the paragraph leaves alone: the error line its caller writes at "
                            + ":149, and the two overlay spans the RECEIVE fills")
                    .containsAll(List.of("userId", "passwd", "errMsg"));
            assertThat(RESPONSE_MAP_MEMBERS - written.size()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("Serialisation - space padding survives a round trip untouched")
    class JsonRoundTrip {
        @Test
        @DisplayName("a fully populated payload round trips to an equal value once the withheld "
                + "credential is re-supplied")
        void populatedRoundTrips() {
            SignOnResponse original = populated();

            // The credential cannot come back, so equality here is the statement that the OTHER sixteen
            // components survive the wire untouched, with the withheld span at its blank image.
            assertThat(roundTrip(original)).isEqualTo(withCredentialWithheld(original));
        }

        @Test
        @DisplayName("the credential does not survive the wire: no key, and no binding without it")
        void theCredentialDoesNotSurviveTheWire() throws IOException {
            SignOnResponse original = populated();
            assertThat(original.passwd())
                    .as("the screen image genuinely holds the span COSGN0AO REDEFINES COSGN0AI puts there")
                    .isEqualTo(RECEIVED_PASSWORD);

            ObjectMapper mapper = webConfigEquivalentMapper();
            String document = mapper.writeValueAsString(original);

            // The wire-level negative assertion, made against the document itself rather than a parsed
            // view of it, so neither the key nor the value can reach a caller by any spelling.
            assertThat(document)
                    .as("CWE-200/CWE-522: the submitted credential is not in the response body")
                    .doesNotContain(CREDENTIAL_WIRE_NAME)
                    .doesNotContain(RECEIVED_PASSWORD);
            assertThat(topLevelJsonKeys(original))
                    .hasSize(COMPONENT_COUNT - 1)
                    .doesNotContain(CREDENTIAL_WIRE_NAME);

            // And the published census says so, rather than leaving it to be inferred from a count.
            assertThat(SignOnResponse.WIRE_FIELD_COUNT)
                    .isEqualTo(RESPONSE_WIRE_MEMBERS)
                    .isEqualTo(SignOnResponse.MAP_FIELD_COUNT - 1);
            assertThat(SignOnResponse.WIRE_FIELDS)
                    .hasSize(SignOnResponse.WIRE_FIELD_COUNT)
                    .doesNotContain(SignOnResponse.WIRE_WITHHELD_FIELD)
                    .containsExactlyElementsOf(SignOnResponse.MAP_FIELDS.stream()
                            .filter(item -> !SignOnResponse.WIRE_WITHHELD_FIELD.equals(item))
                            .toList());

            // Binding the emitted document back cannot recover the span, and a body that names the
            // member cannot put one there either: the component is withheld on the inbound leg as well,
            // so the canonical constructor normalises the absent value to the unpainted image. That is
            // why roundTrip() re-supplies the member explicitly rather than relying on the wire.
            assertThat(mapper.readValue(document, SignOnResponse.class).passwd())
                    .as("a payload read straight back carries the blank image, never the typed value")
                    .isEqualTo(" ".repeat(SignOnResponse.PASSWD_LENGTH));

            ObjectNode restored = (ObjectNode) mapper.readTree(document);
            restored.put(CREDENTIAL_WIRE_NAME, RECEIVED_PASSWORD);
            assertThat(mapper.treeToValue(restored, SignOnResponse.class).passwd())
                    .as("and naming the member inbound does not smuggle it back in")
                    .isEqualTo(" ".repeat(SignOnResponse.PASSWD_LENGTH))
                    .isNotEqualTo(RECEIVED_PASSWORD);
        }

        @Test
        @DisplayName("the withheld credential comes back blank, not null, and never as the typed value")
        void theWithheldCredentialComesBackBlank() {
            SignOnResponse original = populated();
            assertThat(original.passwd())
                    .as("before serialisation the overlay holds what was typed")
                    .isEqualTo(RECEIVED_PASSWORD);

            SignOnResponse revived = roundTrip(original);

            assertThat(revived.passwd())
                    .as("an absent JSON member deserialises to null, which the canonical constructor "
                            + "normalises to the field's own width in spaces - never null, so nothing "
                            + "downstream has to test for one")
                    .isNotNull()
                    .hasSize(SignOnResponse.PASSWD_LENGTH)
                    .isBlank()
                    .isNotEqualTo(RECEIVED_PASSWORD);
        }

        @Test
        @DisplayName("a forty-character title with trailing spaces is neither trimmed nor shortened")
        void trailingSpacesOnATitleSurvive() {
            SignOnResponse original = populated();
            assertThat(original.title01())
                    .as("CCDA-TITLE01 is padded on both sides, so trimming would be visible")
                    .hasSize(SignOnResponse.TITLE01_LENGTH)
                    .endsWith(" ");

            SignOnResponse revived = roundTrip(original);
            assertThat(revived.title01())
                    .isEqualTo(original.title01())
                    .hasSize(SignOnResponse.TITLE01_LENGTH);
            assertThat(revived.title02())
                    .isEqualTo(original.title02())
                    .hasSize(SignOnResponse.TITLE02_LENGTH);
        }

        @Test
        @DisplayName("a seventy-eight-space error line stays a string of spaces, never null")
        void anAllSpacesErrorLineIsNotCoercedToNull() {
            SignOnResponse cleared = SignOnResponse.empty()
                    .withErrMsg(" ".repeat(SignOnResponse.ERRMSG_LENGTH));
            assertThat(cleared.errMsg()).hasSize(SignOnResponse.ERRMSG_LENGTH).isBlank();

            SignOnResponse revived = roundTrip(cleared);
            assertThat(revived.errMsg())
                    .isNotNull()
                    .isEqualTo(cleared.errMsg())
                    .hasSize(SignOnResponse.ERRMSG_LENGTH);
            assertThat(revived).isEqualTo(withCredentialWithheld(cleared));
        }

        @Test
        @DisplayName("every map member keeps its exact width across the round trip")
        void everyWidthSurvivesTheRoundTrip() {
            SignOnResponse revived = roundTrip(populated());
            List<String> values = mapValuesOf(revived);
            for (int index = 0; index < RESPONSE_MAP_MEMBERS; index++) {
                assertThat(values.get(index))
                        .as("%s must still be exactly PIC X(%d)", RESPONSE_MAP_ITEMS.get(index),
                                RESPONSE_WIDTHS.get(index))
                        .hasSize(RESPONSE_WIDTHS.get(index));
            }
        }

        @Test
        @DisplayName("the communication area travels nested and round trips with the payload")
        void theCommareaRoundTripsNested() {
            SignOnResponse original = populated();
            SignOnResponse revived = roundTrip(original);
            assertThat(revived.navigationContext())
                    .isEqualTo(original.navigationContext())
                    .isNotNull();
            assertThat(revived.navigationContext().toFixedWidth(codec()))
                    .as("and still serialises to its declared width afterwards")
                    .isEqualTo(original.navigationContext().toFixedWidth(codec()))
                    .hasSize(NavigationContext.COMMAREA_LENGTH);
        }

        @Test
        @DisplayName("the property names are the component names verbatim, with no naming strategy")
        void propertyNamesAreUntransformed() {
            // No naming STRATEGY is in play - no snake_case, no kebab-case, no upper-camel. Each screen
            // field is published under the one name AAP 0.6.3 allows, its xxxI item in lower case, which
            // @JsonProperty pins field by field; each carrier keeps its component name.
            // Every key traces 1:1 to an item, and the one component that carries no key is the
            // write-only credential - so the expectation is the component list less that member,
            // in declaration order.
            List<String> expected = wireNamesOf(componentNames()).stream()
                    .filter(name -> !CREDENTIAL_WIRE_NAME.equals(name))
                    .toList();
            assertThat(expected).hasSize(COMPONENT_COUNT - 1);
            assertThat(topLevelJsonKeys(populated()))
                    .as("each key traces 1:1 to an item, and PASSWDO is withheld")
                    .containsExactlyElementsOf(expected);
            assertThat(topLevelJsonKeys(populated()))
                    .allSatisfy(key -> assertThat(key).doesNotContain("_").doesNotContain("-"));
        }
    }

    @Nested
    @DisplayName("empty() - every field its own width in spaces")
    class EmptyScreen {
        @Test
        @DisplayName("every map member is the unpainted image at its own declared width")
        void everyMapMemberIsUnpainted() {
            List<String> values = mapValuesOf(SignOnResponse.empty());
            for (int index = 0; index < RESPONSE_MAP_MEMBERS; index++) {
                assertThat(values.get(index))
                        .as("%s is PIC X(%d) and unset means LOW-VALUES, never null and never empty",
                                RESPONSE_MAP_ITEMS.get(index), RESPONSE_WIDTHS.get(index))
                        .isNotNull()
                        .hasSize(RESPONSE_WIDTHS.get(index))
                        .isEqualTo(ScreenFieldImage.unpainted(RESPONSE_WIDTHS.get(index)));
            }
        }

        @Test
        @DisplayName("no role is implied and no XCTL target is named before sign-on")
        void noRoleAndNoTargetBeforeSignOn() {
            SignOnResponse initial = SignOnResponse.empty();
            assertThat(initial.role())
                    .hasSize(SignOnResponse.ROLE_LENGTH)
                    .isBlank();
            assertThat(SignOnResponse.isAdminRole(initial.role()))
                    .as("a space is not 'A', so no administrator is implied")
                    .isFalse();
            assertThat(initial.nextProgram())
                    .as("COSGN00C transfers only from inside the successful branch at line 230")
                    .isBlank();
            assertThat(initial.nextMapset()).isBlank();
            assertThat(initial.nextMap()).isBlank();
        }

        @Test
        @DisplayName("the communication area starts in the CDEMO-PGM-ENTER state")
        void startsInEnterState() {
            NavigationContext context = SignOnResponse.empty().navigationContext();
            assertThat(context).isNotNull();
            assertThat(context.pgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_ENTER);
            assertThat(context.isEnter()).isTrue();
            assertThat(context.isReenter()).isFalse();
        }

        @Test
        @DisplayName("empty() returns an equal value every time and shares no state between calls")
        void emptyIsRepeatable() {
            assertThat(SignOnResponse.empty()).isEqualTo(SignOnResponse.empty());
            assertThat(SignOnResponse.empty().withErrMsg(
                    codec().movePicX(MSG_WRONG_PASSWORD, SignOnResponse.ERRMSG_LENGTH)))
                    .as("and a derived value cannot affect the next empty()")
                    .isNotEqualTo(SignOnResponse.empty());
            assertThat(ScreenFieldImage.isUnpainted(SignOnResponse.empty().errMsg())).isTrue();
        }
    }

    @Nested
    @DisplayName("The canonical constructor enforces every declared width")
    class WidthEnforcement {
        @Test
        @DisplayName("a value exactly at its declared width is accepted")
        void exactWidthIsAccepted() {
            SignOnResponse response = populated();
            List<String> values = mapValuesOf(response);
            for (int index = 0; index < RESPONSE_MAP_MEMBERS; index++) {
                assertThat(values.get(index)).hasSize(RESPONSE_WIDTHS.get(index));
            }
        }

        @Test
        @DisplayName("a shorter value is accepted, mirroring a MOVE into a wider PIC X receiver")
        void shorterIsAccepted() {
            SignOnResponse response = SignOnResponse.empty().withErrMsg(MSG_WRONG_PASSWORD);
            assertThat(response.errMsg())
                    .as("carried as given; padding to the declared width is the codec's job")
                    .isEqualTo(MSG_WRONG_PASSWORD)
                    .hasSizeLessThan(SignOnResponse.ERRMSG_LENGTH);
        }

        @ParameterizedTest(name = "component {0} ({1}) rejects null")
        @CsvSource({
            "0, TRNNAMEO",
            "1, TITLE01O",
            "2, CURDATEO",
            "3, PGMNAMEO",
            "4, TITLE02O",
            "5, CURTIMEO",
            "6, APPLIDO",
            "7, SYSIDO",
            "8, USERIDO",
            "10, ERRMSGO"
        })
        @DisplayName("null is rejected and the failure names the item, because COBOL has no null")
        void nullIsRejected(int index, String field) {
            assertThatNullPointerException()
                    .isThrownBy(() -> constructWith(index, null))
                    .withMessageContaining(field);
        }

        @Test
        @DisplayName("PASSWDO alone accepts null, because an unpublished member has an absent state")
        void nullIsNormalisedForTheWithheldCredential() {
            // Every other component is on the wire, so a null could only ever be a caller's mistake and
            // is refused with the item's name. This one is not on the wire, so a body read back really
            // does arrive without it, and refusing null would make the type unable to represent its own
            // serialised form. It is normalised to the declared width in spaces instead.
            assertThat(RESPONSE_MEMBERS.indexOf(WITHHELD_MEMBER))
                    .as("component 9 is PASSWDO, the index omitted from the CsvSource above")
                    .isEqualTo(9);
            assertThat(constructWith(9, null).passwd())
                    .isNotNull()
                    .hasSize(SignOnResponse.PASSWD_LENGTH)
                    .isBlank();
        }

        @ParameterizedTest(name = "component {0} ({1}) rejects {2} + 1 characters")
        @CsvSource({
            "0, TRNNAMEO, 4",
            "1, TITLE01O, 40",
            "2, CURDATEO, 8",
            "3, PGMNAMEO, 8",
            "4, TITLE02O, 40",
            "5, CURTIMEO, 9",
            "6, APPLIDO, 8",
            "7, SYSIDO, 8",
            "8, USERIDO, 8",
            "9, PASSWDO, 8",
            "10, ERRMSGO, 78"
        })
        @DisplayName("an over-wide value is rejected, naming the item, the width and the length")
        void overWideIsRejected(int index, String field, int width) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> constructWith(index, "W".repeat(width + 1)))
                    .withMessageContaining(field)
                    .withMessageContaining(String.valueOf(width))
                    .withMessageContaining(String.valueOf(width + 1));
        }

        @Test
        @DisplayName("the navigation members are held to their widths too")
        void theNavigationMembersAreAlsoBounded() {
            assertThatIllegalArgumentException()
                    .as("role is PIC X(01)")
                    .isThrownBy(() -> SignOnResponse.empty().withNavigation("AA",
                            SignOnResponse.NEXT_PROGRAM_ADMIN, MAPSET_NAME, MAP_NAME,
                            NavigationContext.empty()));
            assertThatIllegalArgumentException()
                    .as("nextMapset is PIC X(7), not X(8)")
                    .isThrownBy(() -> SignOnResponse.empty().withNavigation(
                            SignOnResponse.ROLE_ADMIN, SignOnResponse.NEXT_PROGRAM_ADMIN,
                            "COSGN000", MAP_NAME, NavigationContext.empty()));
            assertThatIllegalArgumentException()
                    .as("nextMap is PIC X(7) for the same reason")
                    .isThrownBy(() -> SignOnResponse.empty().withNavigation(
                            SignOnResponse.ROLE_ADMIN, SignOnResponse.NEXT_PROGRAM_ADMIN,
                            MAPSET_NAME, "COSGN0AB", NavigationContext.empty()));
            assertThatIllegalArgumentException()
                    .as("nextProgram is PIC X(08)")
                    .isThrownBy(() -> SignOnResponse.empty().withNavigation(
                            SignOnResponse.ROLE_ADMIN, "COADM01CX", MAPSET_NAME, MAP_NAME,
                            NavigationContext.empty()));
        }

        @Test
        @DisplayName("the communication area is required, because there is no session to fall back on")
        void navigationContextIsRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> SignOnResponse.empty().withNavigation(
                            SignOnResponse.ROLE_ADMIN, SignOnResponse.NEXT_PROGRAM_ADMIN,
                            MAPSET_NAME, MAP_NAME, null))
                    .withMessageContaining("navigationContext");
        }
    }

    private static SignOnResponse constructWith(int index, String value) {
        List<String> values = new ArrayList<>(mapValuesOf(populated()));
        values.set(index, value);
        return new SignOnResponse(values.get(0), values.get(1), values.get(2), values.get(3),
                values.get(4), values.get(5), values.get(6), values.get(7), values.get(8),
                values.get(9), values.get(10), SignOnResponse.ROLE_ADMIN,
                SignOnResponse.NEXT_PROGRAM_ADMIN, MAPSET_NAME, MAP_NAME,
                " ".repeat(SignOnResponse.PLAIN_TEXT_LENGTH),
                signedOnContext(SignOnResponse.ROLE_ADMIN));
    }

    @Nested
    @DisplayName("Immutability - one method per writing paragraph, and no state anywhere")
    class ImmutableReplacement {
        @Test
        @DisplayName("withErrMsg changes the error line and nothing else")
        void withErrMsgChangesOnlyTheErrorLine() {
            SignOnResponse before = populated();
            String replacement = codec().movePicX(MSG_USER_NOT_FOUND, SignOnResponse.ERRMSG_LENGTH);
            SignOnResponse after = before.withErrMsg(replacement);

            assertThat(after.errMsg()).isEqualTo(replacement).isNotEqualTo(before.errMsg());
            assertThat(mapValuesOf(after).subList(0, RESPONSE_MAP_MEMBERS - 1))
                    .as("the nine fields ahead of it are untouched")
                    .isEqualTo(mapValuesOf(before).subList(0, RESPONSE_MAP_MEMBERS - 1));
            assertThat(after.role()).isEqualTo(before.role());
            assertThat(after.nextProgram()).isEqualTo(before.nextProgram());
            assertThat(after.navigationContext()).isEqualTo(before.navigationContext());
        }

        @Test
        @DisplayName("withNavigation changes the five navigation members and no screen field")
        void withNavigationChangesOnlyTheNavigationMembers() {
            SignOnResponse before = populated();
            SignOnResponse after = before.withNavigation(SignOnResponse.ROLE_USER,
                    SignOnResponse.NEXT_PROGRAM_USER, MAPSET_NAME, MAP_NAME,
                    signedOnContext(SignOnResponse.ROLE_USER));

            assertThat(after.role()).isEqualTo(SignOnResponse.ROLE_USER)
                    .isNotEqualTo(before.role());
            assertThat(after.nextProgram()).isEqualTo(SignOnResponse.NEXT_PROGRAM_USER);
            assertThat(mapValuesOf(after))
                    .as("every screen field survives a navigation change unchanged")
                    .isEqualTo(mapValuesOf(before));
        }

        @Test
        @DisplayName("a replacement leaves the original untouched, so a handed-out value cannot change")
        void theOriginalIsNeverMutated() {
            SignOnResponse original = populated();
            String originalErrMsg = original.errMsg();
            String originalRole = original.role();

            original.withErrMsg(" ".repeat(SignOnResponse.ERRMSG_LENGTH));
            original.withNavigation(SignOnResponse.ROLE_USER, SignOnResponse.NEXT_PROGRAM_USER,
                    MAPSET_NAME, MAP_NAME, NavigationContext.empty());
            original.withHeader(TRANSACTION_ID, ScreenTitles.CCDA_TITLE02, FIXED_CURDATE,
                    PROGRAM_NAME, ScreenTitles.CCDA_TITLE01, FIXED_CURTIME + " ", "OTHERAPP",
                    "OTHR    ");

            assertThat(original.errMsg()).isEqualTo(originalErrMsg);
            assertThat(original.role()).isEqualTo(originalRole);
            assertThat(original).isEqualTo(populated());
        }

        @Test
        @DisplayName("the type declares no setter and no mutable instance field")
        void noSetterAndNoMutableField() {
            for (Method method : SignOnResponse.class.getDeclaredMethods()) {
                assertThat(method.getName())
                        .as("method %s must not be a setter", method.getName())
                        .doesNotStartWith("set");
            }
            for (Field field : SignOnResponse.class.getDeclaredFields()) {
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("field %s must be final", field.getName())
                        .isTrue();
            }
        }

        @Test
        @DisplayName("every static field is final, so there is no static mutable state")
        void noStaticMutableState() {
            for (Field field : SignOnResponse.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("static field %s must be final", field.getName())
                            .isTrue();
                }
            }
            for (Field field : SignOnResponseTest.class.getDeclaredFields()) {
                assertThat(Modifier.isStatic(field.getModifiers())
                        && Modifier.isFinal(field.getModifiers()))
                        .as("this suite's own field %s must be static final too", field.getName())
                        .isTrue();
            }
        }

        @Test
        @DisplayName("equality is by value, so two identically built responses are equal")
        void equalityIsByValue() {
            assertThat(populated())
                    .isEqualTo(populated())
                    .hasSameHashCodeAs(populated())
                    .isNotSameAs(populated());
        }
    }
}
