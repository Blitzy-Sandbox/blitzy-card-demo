package com.vsergeychik.carddemo.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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

/**
 * Unit tests for {@link UserAddResponse} - the outbound payload of {@code POST /api/users}, CICS
 * transaction {@code CU01}, program {@code app/cbl/COUSR01C.cbl}, map {@code COUSR1A} of mapset
 * {@code COUSR01}: the screen the Add User transaction paints.
 */
@DisplayName("UserAddResponse - the COUSR01 (CU01) Add User outbound payload")
class UserAddResponseTest {
    private static final Charset MAP_CHARSET = StandardCharsets.US_ASCII;

    private static final int DFHMDF_NAMED = 12;

    private static final int DFHMDF_TOTAL = 28;

    private static final List<String> DFHMDF_LABELS = List.of("TRNNAME",
            "TITLE01",
            "CURDATE",
            "PGMNAME",
            "TITLE02",
            "CURTIME",
            "FNAME",
            "LNAME",
            "USERID",
            "PASSWD",
            "USRTYPE",
            "ERRMSG");

    private static final List<String> XXXI_ITEMS = List.of("TRNNAMEI",
            "TITLE01I",
            "CURDATEI",
            "PGMNAMEI",
            "TITLE02I",
            "CURTIMEI",
            "FNAMEI",
            "LNAMEI",
            "USERIDI",
            "PASSWDI",
            "USRTYPEI",
            "ERRMSGI");

    private static final List<String> XXXO_ITEMS = List.of("TRNNAMEO",
            "TITLE01O",
            "CURDATEO",
            "PGMNAMEO",
            "TITLE02O",
            "CURTIMEO",
            "FNAMEO",
            "LNAMEO",
            "USERIDO",
            "PASSWDO",
            "USRTYPEO",
            "ERRMSGO");

    private static final List<String> MAP_MEMBERS = List.of("trnName",
            "title01",
            "curDate",
            "pgmName",
            "title02",
            "curTime",
            "fName",
            "lName",
            "userId",
            "passwd",
            "usrType",
            "errMsg");

    private static final List<Integer> DECLARED_WIDTHS = List.of(4, 40, 8, 8, 40, 8, 20, 20, 8, 8, 1, 78);

    private static final List<String> NAV_MEMBERS =
            List.of("navigationContext", "nextProgram", "nextMapset", "nextMap");

    /**
     * The one member declared {@code @JsonProperty(access = WRITE_ONLY)}: {@code passwd}.
     *
     * <p>{@code app/bms/COUSR01.bms:126} declares {@code PASSWD} with {@code ATTRB=(DRK,FSET,UNPROT)},
     * so the 3270 receives whatever the span holds and renders it invisibly. JSON has no {@code DRK},
     * and a member on the wire is readable by the client, by anything between them and by anything that
     * logs a response body - so emitting it would reproduce one half of the terminal contract and
     * discard the half that hides the value. The two halves are therefore separated: the span stays on
     * the model, {@link UserAddResponse#passwd()} and {@link UserAddResponse#MAP_DERIVED_FIELD_NAMES}
     * both keep it so the twelve-of-twelve projection gate G9 requires stays complete, and only the
     * serialized projection omits it. The member is still accepted inbound.
     */
    private static final String WRITE_ONLY_MEMBER = "passwd";

    /**
     * A member's name <strong>on the wire</strong>.
     *
     * <p>A screen field answers to its {@code xxxI} item in lower case - that is what
     * {@code @JsonProperty} pins on the subject and what AAP 0.6.3 requires, "payload field names and
     * lengths derive from the xxxI items only". A carrier traces to no {@code DFHMDF} field, so no such
     * rule governs it and it keeps its own component name. Keeping the two apart is the point: a single
     * list serving both roles would silently assert that the Java identifier and the wire name coincide.
     *
     * @param member the Java member name
     * @return the JSON property name it is published under
     */
    private static String wireNameOf(String member) {
        return MAP_MEMBERS.contains(member) ? member.toLowerCase(Locale.ROOT) : member;
    }

    private static List<String> wireNamesOf(List<String> members) {
        return members.stream().map(UserAddResponseTest::wireNameOf).toList();
    }

    private static final int TIOAPFX_PREFIX_LENGTH = 12;

    private static final int LENGTH_ITEM_LENGTH = 2;

    private static final int FLAG_ITEM_LENGTH = 1;

    private static final int INPUT_RESERVED_LENGTH = 4;

    private static final int OUTPUT_RESERVED_LENGTH = 3;

    private static final int ATTRIBUTE_ITEM_LENGTH = 1;

    private static final int ATTRIBUTE_ITEM_COUNT = 4;

    private static final int FIELD_PREFIX_LENGTH = 7;

    private static final int PAYLOAD_WIDTH_TOTAL = 243;

    private static final int SYMBOLIC_MAP_LENGTH = 339;

    private static final String WS_TRANID = "CU01";

    private static final String WS_PGMNAME = "COUSR01C";

    private static final String MAP = "COUSR1A";

    private static final String MAPSET = "COUSR01";

    private static final int WS_MESSAGE_LENGTH = 80;

    private static final String SIGN_ON_PROGRAM = "COSGN00C";

    private static final String ADMIN_MENU_PROGRAM = "COADM01C";

    private static final int SEC_USR_ID_LENGTH = 8;

    private static final int SEC_USR_FNAME_LENGTH = 20;

    private static final int SEC_USR_LNAME_LENGTH = 20;

    private static final int SEC_USR_PWD_LENGTH = 8;

    private static final int SEC_USR_TYPE_LENGTH = 1;

    private static final String MSG_FIRST_NAME_EMPTY = "First Name can NOT be empty...";

    private static final String MSG_LAST_NAME_EMPTY = "Last Name can NOT be empty...";

    private static final String MSG_USER_ID_EMPTY = "User ID can NOT be empty...";

    private static final String MSG_PASSWORD_EMPTY = "Password can NOT be empty...";

    private static final String MSG_USER_TYPE_EMPTY = "User Type can NOT be empty...";

    private static final String MSG_DUPLICATE = "User ID already exist...";

    private static final String MSG_UNABLE_TO_ADD = "Unable to Add User...";

    private static final String SUCCESS_PREFIX = "User ";

    private static final String SUCCESS_SUFFIX = " has been added ...";

    private static final List<String> ORDERED_BLANK_FIELD_MESSAGES = List.of(MSG_FIRST_NAME_EMPTY,
            MSG_LAST_NAME_EMPTY,
            MSG_USER_ID_EMPTY,
            MSG_PASSWORD_EMPTY,
            MSG_USER_TYPE_EMPTY);

    private static final List<String> CREDENTIAL_MARKERS =
            List.of("PasswordEncoder", "BCrypt", "MessageDigest", "Hash", "Digest", "Cipher",
                    "SecretKey", "Encrypt", "Jwt", "org.springframework.security");

    private static final List<String> EXCLUDED_SECURITY_TYPES =
            List.of("org.springframework.security.crypto.password.PasswordEncoder",
                    "org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder",
                    "org.springframework.security.core.Authentication");

    private static final List<String> INPUT_CONTROL_SUFFIXES = List.of("L", "F", "A");

    private static final List<String> OUTPUT_CONTROL_SUFFIXES = List.of("C", "P", "H", "V");

    private static final int NAMED_ITEMS_PER_FIELD = 8;

    private static final FixedWidthRecord.RecordLayout SYMBOLIC_MAP_LAYOUT = symbolicMapLayout();

    private static final List<Integer> DATA_OFFSETS = dataOffsets();

    private static FixedWidthRecord.RecordLayout symbolicMapLayout() {
        List<FixedWidthRecord.FieldSpan> spans = new ArrayList<>();
        spans.add(FixedWidthRecord.FieldSpan.filler(0, TIOAPFX_PREFIX_LENGTH));
        int cursor = TIOAPFX_PREFIX_LENGTH;
        List<FixedWidthRecord.FieldSpan> overlays = new ArrayList<>();
        for (int index = 0; index < DFHMDF_NAMED; index++) {
            String label = DFHMDF_LABELS.get(index);
            int width = DECLARED_WIDTHS.get(index);
            int prefix = cursor;

            spans.add(FixedWidthRecord.FieldSpan.filler(prefix, LENGTH_ITEM_LENGTH));
            FixedWidthRecord.FieldSpan flag = FixedWidthRecord.FieldSpan.alphanumeric(
                    label + "F", prefix + LENGTH_ITEM_LENGTH, FLAG_ITEM_LENGTH);
            spans.add(flag);
            spans.add(flag.redefinedAs(label + "A", FixedWidthRecord.PictureKind.ALPHANUMERIC));
            spans.add(FixedWidthRecord.FieldSpan.filler(
                    prefix + LENGTH_ITEM_LENGTH + FLAG_ITEM_LENGTH, INPUT_RESERVED_LENGTH));
            int dataOffset = prefix + FIELD_PREFIX_LENGTH;
            spans.add(FixedWidthRecord.FieldSpan.alphanumeric(
                    XXXI_ITEMS.get(index), dataOffset, width));

            for (int attribute = 0; attribute < ATTRIBUTE_ITEM_COUNT; attribute++) {
                overlays.add(FixedWidthRecord.FieldSpan.redefining(
                        label + OUTPUT_CONTROL_SUFFIXES.get(attribute),
                        prefix + OUTPUT_RESERVED_LENGTH + attribute,
                        ATTRIBUTE_ITEM_LENGTH,
                        FixedWidthRecord.PictureKind.ALPHANUMERIC));
            }
            overlays.add(FixedWidthRecord.FieldSpan.redefining(
                    XXXO_ITEMS.get(index), dataOffset, width,
                    FixedWidthRecord.PictureKind.ALPHANUMERIC));

            cursor = dataOffset + width;
        }
        spans.addAll(overlays);
        return FixedWidthRecord.RecordLayout.of(SYMBOLIC_MAP_LENGTH,
                spans.toArray(FixedWidthRecord.FieldSpan[]::new));
    }

    private static List<Integer> dataOffsets() {
        List<Integer> offsets = new ArrayList<>(DFHMDF_NAMED);
        int cursor = TIOAPFX_PREFIX_LENGTH;
        for (int index = 0; index < DFHMDF_NAMED; index++) {
            offsets.add(cursor + FIELD_PREFIX_LENGTH);
            cursor += FIELD_PREFIX_LENGTH + DECLARED_WIDTHS.get(index);
        }
        return List.copyOf(offsets);
    }

    private static FixedWidthCodec codec() {
        return new FixedWidthCodec(MAP_CHARSET);
    }

    private static String spaces(int width) {
        return " ".repeat(width);
    }

    private static String lowValues(int width) {
        return "\u0000".repeat(width);
    }

    private static List<String> componentNames() {
        return Arrays.stream(UserAddResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    private static List<String> mapValuesOf(UserAddResponse response) {
        return Arrays.asList(response.trnName(), response.title01(), response.curDate(),
                response.pgmName(), response.title02(), response.curTime(), response.fName(),
                response.lName(), response.userId(), response.passwd(), response.usrType(),
                response.errMsg());
    }

    private static List<String> blankMapValues() {
        List<String> values = new ArrayList<>(DFHMDF_NAMED);
        for (int index = 0; index < DFHMDF_NAMED; index++) {
            values.add(spaces(DECLARED_WIDTHS.get(index)));
        }
        return values;
    }

    private static List<String> withMember(List<String> values, String member, String value) {
        List<String> replaced = new ArrayList<>(values);
        replaced.set(MAP_MEMBERS.indexOf(member), value);
        return replaced;
    }

    private static UserAddResponse responseOf(List<String> mapValues, NavigationContext context,
            String nextProgram, String nextMapset, String nextMap) {
        return new UserAddResponse(mapValues.get(0), mapValues.get(1), mapValues.get(2),
                mapValues.get(3), mapValues.get(4), mapValues.get(5), mapValues.get(6),
                mapValues.get(7), mapValues.get(8), mapValues.get(9), mapValues.get(10),
                mapValues.get(11), context, nextProgram, nextMapset, nextMap);
    }

    private static UserAddResponse blankResponse() {
        return responseOf(blankMapValues(), NavigationContext.empty(), spaces(8), MAPSET, MAP);
    }

    private static UserAddResponse sentScreen(String wsMessage) {
        DateHeader header = header();
        List<String> values = blankMapValues();
        values = withMember(values, "trnName", WS_TRANID);
        values = withMember(values, "title01", ScreenTitles.CCDA_TITLE01);
        values = withMember(values, "curDate", header.wsCurdateMmDdYy());
        values = withMember(values, "pgmName", WS_PGMNAME);
        values = withMember(values, "title02", ScreenTitles.CCDA_TITLE02);
        values = withMember(values, "curTime", header.wsCurtimeHhMmSs());
        values = withMember(values, "errMsg", narrowToErrMsg(wsMessage));
        return responseOf(values, NavigationContext.empty(), spaces(8), MAPSET, MAP);
    }

    private static DateHeader header() {
        return DateHeader.from(codec(),
                Clock.fixed(Instant.parse("2022-08-22T17:02:44Z"), ZoneOffset.UTC));
    }

    private static String wsMessageImage(String text) {
        return codec().movePicX(text, WS_MESSAGE_LENGTH);
    }

    private static String narrowToErrMsg(String text) {
        return codec().movePicX(wsMessageImage(text), UserAddResponse.ERR_MSG_LENGTH);
    }

    private static String composedSuccessMessage(String secUsrId) {
        int firstSpace = secUsrId.indexOf(' ');
        String delimitedBySpace = firstSpace < 0 ? secUsrId : secUsrId.substring(0, firstSpace);
        return codec().concatenateDelimitedBySize(SUCCESS_PREFIX, delimitedBySpace, SUCCESS_SUFFIX);
    }

    private static ObjectMapper webConfigEquivalentMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        mapper.configure(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN, true);
        mapper.disable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT);
        return mapper;
    }

    private static String serialise(UserAddResponse response) {
        try {
            return webConfigEquivalentMapper().writeValueAsString(response);
        } catch (IOException failure) {
            throw new UncheckedIOException("Serialising a UserAddResponse must not fail", failure);
        }
    }

    private static UserAddResponse deserialise(String json) {
        try {
            return webConfigEquivalentMapper().readValue(json, UserAddResponse.class);
        } catch (IOException failure) {
            throw new UncheckedIOException("Deserialising a UserAddResponse must not fail", failure);
        }
    }

    private static Map<String, Object> jsonKeys(UserAddResponse response) {
        try {
            return webConfigEquivalentMapper()
                    .readValue(serialise(response), new TypeReference<Map<String, Object>>() { });
        } catch (IOException failure) {
            throw new UncheckedIOException("Reading a serialised UserAddResponse must not fail",
                    failure);
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
     * three and absent from the component itself. Every surface that carries one is required to agree.
     *
     * @param componentName the component to inspect
     * @return the annotation that governs it, or {@code null} if none is applied
     */
    private static JsonProperty jsonPropertyOn(String componentName) {
        List<JsonProperty> found = new ArrayList<>();
        RecordComponent component = componentNamed(componentName);
        addIfPresent(found, component.getAnnotation(JsonProperty.class));
        addIfPresent(found, component.getAccessor().getAnnotation(JsonProperty.class));
        try {
            addIfPresent(found, UserAddResponse.class.getDeclaredField(componentName)
                    .getAnnotation(JsonProperty.class));
        } catch (NoSuchFieldException neverHappensForARecordComponent) {
            throw new AssertionError("a record component always has a backing field",
                    neverHappensForARecordComponent);
        }
        for (Constructor<?> constructor : UserAddResponse.class.getDeclaredConstructors()) {
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
        RecordComponent component = componentNamed(componentName);
        collectSimpleNames(names, component.getAnnotations());
        collectSimpleNames(names, component.getAccessor().getAnnotations());
        try {
            collectSimpleNames(names,
                    UserAddResponse.class.getDeclaredField(componentName).getAnnotations());
        } catch (NoSuchFieldException neverHappensForARecordComponent) {
            throw new AssertionError("a record component always has a backing field",
                    neverHappensForARecordComponent);
        }
        for (Constructor<?> constructor : UserAddResponse.class.getDeclaredConstructors()) {
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

    private static RecordComponent componentNamed(String componentName) {
        return Arrays.stream(UserAddResponse.class.getRecordComponents())
                .filter(candidate -> candidate.getName().equals(componentName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        componentName + " is not a component of UserAddResponse"));
    }

    /**
     * The member the payload declares and does <strong>not</strong> publish: the credential.
     *
     * <p>{@code app/bms/COUSR01.bms} line 126 declares {@code PASSWD ATTRB=(DRK,FSET,UNPROT)}. The 3270
     * receives the eight characters and renders them invisibly, on the one device that keyed them; JSON
     * reproduces neither guarantee. The component stays - {@code 01 COUSR1AO REDEFINES COUSR1AI} means
     * the receive at lines 112-115 fills it and the send at 184-196 transmits it, and six of this
     * program's twenty parity cases pin exactly that - and {@code @JsonIgnore} keeps it off the wire.
     */
    private static final String WITHHELD_MEMBER = "passwd";

    /** The fifteen property names the wire form carries: eleven published map members plus the four nav. */
    private static Set<String> expectedJsonMembers() {
        Set<String> members = new LinkedHashSet<>(wireNamesOf(MAP_MEMBERS));
        members.remove(WITHHELD_MEMBER);
        members.addAll(NAV_MEMBERS);
        return Set.copyOf(members);
    }

    /**
     * The fifteen property names the wire form actually carries, guarded so that it keeps asserting
     * something: {@link #expectedJsonMembers()} is composed by withholding {@link #WRITE_ONLY_MEMBER},
     * so a mechanism that stopped withholding it fails here rather than passing quietly.
     *
     * @return the emitted property names
     */
    private static Set<String> emittedJsonMembers() {
        Set<String> members = new LinkedHashSet<>(expectedJsonMembers());
        if (members.contains(WRITE_ONLY_MEMBER)) {
            throw new AssertionError(WRITE_ONLY_MEMBER + " is published, so the emitted set no longer "
                    + "asserts that the credential is withheld");
        }
        return Set.copyOf(members);
    }

    /**
     * Serialise then deserialise, putting the write-only credential back before the inbound leg.
     *
     * <p>{@code passwd} is {@code @JsonIgnore}d, because {@code app/bms/COUSR01.bms:126} declares the
     * field {@code ATTRB=(DRK,FSET,UNPROT)} and JSON has no {@code DRK} - so neither leg carries the
     * member. Putting the value back into the tree models the round trip a client performs, since it
     * holds the value it sent; the inbound leg withholds it again and the canonical constructor
     * normalises the absent member to the blank image. That the wire really did omit it is asserted
     * separately, by {@link PasswordDeclaredButNeverEchoed#blankSurvivesTheRoundTrip()}.
     *
     * <p>A {@code null} password is left out of the tree rather than written as a JSON {@code null}:
     * the canonical constructor normalises an absent credential to the blank image at the declared
     * width, which is what an unpainted {@code PIC X(8)} holds.
     *
     * @param response the response to round trip
     * @return the response the inbound leg reconstructed
     */
    private static UserAddResponse roundTrip(UserAddResponse response) {
        try {
            ObjectMapper mapper = webConfigEquivalentMapper();
            ObjectNode wire = (ObjectNode) mapper.readTree(serialise(response));
            if (response.passwd() != null) {
                wire.put(WRITE_ONLY_MEMBER, response.passwd());
            }
            return mapper.treeToValue(wire, UserAddResponse.class);
        } catch (IOException failure) {
            throw new UncheckedIOException("Round tripping a UserAddResponse must not fail", failure);
        }
    }

    /**
     * Every type name reachable from the declaration of {@link UserAddResponse}: its components, its
     * public methods' returns and parameters, and its declared fields. Enough to show that no hashing,
     * encoding or security type has been introduced anywhere in the type's surface.
     */
    private static Set<String> reachableTypeNames() {
        Set<String> names = new LinkedHashSet<>();
        for (RecordComponent component : UserAddResponse.class.getRecordComponents()) {
            names.add(component.getType().getName());
        }
        for (Method method : UserAddResponse.class.getDeclaredMethods()) {
            names.add(method.getReturnType().getName());
            for (Class<?> parameter : method.getParameterTypes()) {
                names.add(parameter.getName());
            }
        }
        for (Field field : UserAddResponse.class.getDeclaredFields()) {
            names.add(field.getType().getName());
        }
        return Set.copyOf(names);
    }

    @Nested
    @DisplayName("Map projection - twelve members, in COUSR1AO order, at declared widths")
    class MapProjection {
        @Test
        @DisplayName("28 DFHMDF definitions, 12 of them labelled, and 12 payload members")
        void theTwentyEightVersusTwelveSplit() {
            assertThat(DFHMDF_TOTAL - DFHMDF_LABELS.size())
                    .as("16 of the 28 DFHMDF definitions are unlabelled screen literals")
                    .isEqualTo(16);
            assertThat(DFHMDF_LABELS).hasSize(DFHMDF_NAMED);
            assertThat(XXXI_ITEMS).hasSize(DFHMDF_NAMED);
            assertThat(XXXO_ITEMS).hasSize(DFHMDF_NAMED);
            assertThat(MAP_MEMBERS).hasSize(DFHMDF_NAMED);
            assertThat(DECLARED_WIDTHS).hasSize(DFHMDF_NAMED);
            assertThat(UserAddResponse.MAP_DERIVED_FIELD_COUNT).isEqualTo(DFHMDF_NAMED);
        }

        @Test
        @DisplayName("the record declares 16 components: 12 map-derived then 4 navigation")
        void componentCountAndOrder() {
            assertThat(componentNames())
                    .as("twelve map-derived members and the four the stateless contract adds")
                    .hasSize(DFHMDF_NAMED + NAV_MEMBERS.size());
            assertThat(componentNames().subList(0, DFHMDF_NAMED))
                    .as("the map-derived members come first, in COUSR1AO declaration order")
                    .containsExactlyElementsOf(MAP_MEMBERS);
            assertThat(componentNames().subList(DFHMDF_NAMED, componentNames().size()))
                    .as("then the navigation contract, in its declared order")
                    .containsExactlyElementsOf(NAV_MEMBERS);
        }

        @Test
        @DisplayName("each member's xxxO item is its DFHMDF label with the output suffix appended")
        void everyMemberTracesToADfhmdfDefinition() {
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                String label = DFHMDF_LABELS.get(index);
                assertThat(XXXO_ITEMS.get(index))
                        .as("the output item of DFHMDF %s", label)
                        .isEqualTo(label + FieldAttributeSetter.OUTPUT_ITEM_SUFFIX);
                assertThat(XXXI_ITEMS.get(index))
                        .as("and its input partner differs only in the final letter")
                        .isEqualTo(label + "I");
            }
            assertThat(FieldAttributeSetter.OUTPUT_ITEM_SUFFIX)
                    .as("common.FieldAttributeSetter names the suffix the copybook uses")
                    .isEqualTo("O");
        }

        @Test
        @DisplayName("the type publishes the same twelve item names, labels and widths")
        void thePublishedListsMatchTheTranscription() {
            assertThat(UserAddResponse.MAP_DERIVED_FIELD_NAMES)
                    .as("the xxxO item names, verbatim as COUSR01.CPY spells them")
                    .containsExactlyElementsOf(XXXO_ITEMS);
            assertThat(UserAddResponse.DFHMDF_FIELD_NAMES)
                    .as("the name-labelled DFHMDF labels, in mapset order")
                    .containsExactlyElementsOf(DFHMDF_LABELS);
            assertThat(UserAddResponse.MAP_DERIVED_FIELD_LENGTHS)
                    .as("and the declared PICTURE widths, index for index")
                    .containsExactlyElementsOf(DECLARED_WIDTHS);
        }

        @ParameterizedTest(name = "[{index}] {0} is PIC X({1})")
        @CsvSource({"TRNNAMEO,4", "TITLE01O,40", "CURDATEO,8", "PGMNAMEO,8", "TITLE02O,40",
                    "CURTIMEO,8", "FNAMEO,20", "LNAMEO,20", "USERIDO,8", "PASSWDO,8", "USRTYPEO,1",
                    "ERRMSGO,78"})
        @DisplayName("every declared width is the one its PICTURE clause states")
        void eachDeclaredWidth(String item, int width) {
            int index = XXXO_ITEMS.indexOf(item);
            assertThat(index).as("%s is one of the twelve output items", item).isNotNegative();
            assertThat(DECLARED_WIDTHS.get(index)).isEqualTo(width);
            assertThat(UserAddResponse.MAP_DERIVED_FIELD_LENGTHS.get(index))
                    .as("the type publishes the same width for %s", item)
                    .isEqualTo(width);
        }

        @Test
        @DisplayName("every member is a String: a screen field is characters, never a typed value")
        void everyMapMemberIsAString() {
            RecordComponent[] components = UserAddResponse.class.getRecordComponents();
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                assertThat(components[index].getType())
                        .as("%s projects PIC X(%d) and so is a String",
                                MAP_MEMBERS.get(index), DECLARED_WIDTHS.get(index))
                        .isEqualTo(String.class);
            }
            for (RecordComponent component : components) {
                assertThat(component.getType())
                        .as("%s must not be a binary floating-point type", component.getName())
                        .isNotEqualTo(double.class)
                        .isNotEqualTo(float.class)
                        .isNotEqualTo(Double.class)
                        .isNotEqualTo(Float.class);
            }
        }

        @Test
        @DisplayName("the screen identity is CU01 / COUSR01C / COUSR1A of COUSR01")
        void theScreenIdentity() {
            assertThat(UserAddResponse.TRANSACTION_ID)
                    .as("WS-TRANID at COUSR01C.cbl:37, and TRANSACTION(CU01) at CARDDEMO.CSD:459")
                    .isEqualTo(WS_TRANID)
                    .hasSize(UserAddResponse.TRN_NAME_LENGTH);
            assertThat(UserAddResponse.PROGRAM_NAME)
                    .as("WS-PGMNAME at COUSR01C.cbl:36, and PROGRAM(COUSR01C) at CARDDEMO.CSD:460")
                    .isEqualTo(WS_PGMNAME)
                    .hasSize(UserAddResponse.PGM_NAME_LENGTH);
            assertThat(UserAddResponse.MAP_NAME)
                    .as("MAP('COUSR1A') at COUSR01C.cbl:191")
                    .isEqualTo(MAP);
            assertThat(UserAddResponse.MAPSET_NAME)
                    .as("MAPSET('COUSR01') at COUSR01C.cbl:192")
                    .isEqualTo(MAPSET);
            assertThat(MAP + FieldAttributeSetter.OUTPUT_MAP_SUFFIX).isEqualTo("COUSR1AO");
            assertThat(MAP.length()).isEqualTo(NavigationContext.LAST_MAP_LENGTH).isEqualTo(7);
            assertThat(MAPSET.length()).isEqualTo(NavigationContext.LAST_MAPSET_LENGTH).isEqualTo(7);
        }

        @Test
        @DisplayName("a response carries what it was handed: no padding, no trimming, no validation")
        void thePayloadIsAPassiveCarrier() {
            String shorter = "Jo";
            String longer = "A".repeat(UserAddResponse.F_NAME_LENGTH + 5);
            UserAddResponse response = responseOf(
                    withMember(withMember(blankMapValues(), "fName", shorter), "lName", longer),
                    NavigationContext.empty(), spaces(8), MAPSET, MAP);
            assertThat(response.fName())
                    .as("a short value is neither padded to PIC X(20) nor rejected")
                    .isEqualTo(shorter)
                    .hasSize(2);
            assertThat(response.lName())
                    .as("and an over-wide value is neither truncated nor rejected by the payload")
                    .isEqualTo(longer)
                    .hasSize(UserAddResponse.L_NAME_LENGTH + 5);
        }

        @Test
        @DisplayName("no jakarta.validation constraint is declared on a response member")
        void noValidationConstraintIsDeclared() {
            for (RecordComponent component : UserAddResponse.class.getRecordComponents()) {
                for (Annotation annotation : component.getAnnotations()) {
                    assertThat(annotation.annotationType().getName())
                            .as("%s carries %s; a response is not validated, and a presence "
                                    + "constraint here would change observable behaviour",
                                    component.getName(), annotation.annotationType().getName())
                            .doesNotStartWith("jakarta.validation");
                }
                for (Annotation annotation : component.getAccessor().getAnnotations()) {
                    assertThat(annotation.annotationType().getName())
                            .as("the accessor for %s carries %s", component.getName(),
                                    annotation.annotationType().getName())
                            .doesNotStartWith("jakarta.validation");
                }
            }
        }
    }

    @Nested
    @DisplayName("Cross-screen traps - USERID, names before id, curTime 8, errMsg 78")
    class CrossScreenTraps {
        @Test
        @DisplayName("the identity member is userId, from USERID - not usrIdIn, from USRIDIN")
        void theIdentityMemberIsNamedForUserid() {
            assertThat(componentNames().get(MAP_MEMBERS.indexOf("userId")))
                    .isEqualTo("userId")
                    .isNotEqualTo("usrIdIn");
            assertThat(UserAddResponse.USER_ID_FIELD)
                    .as("COUSR01.CPY:146 declares USERIDO, not USRIDINO")
                    .isEqualTo("USERIDO")
                    .isNotEqualTo("USRIDINO");
            assertThat(DFHMDF_LABELS.get(MAP_MEMBERS.indexOf("userId")))
                    .isEqualTo("USERID")
                    .isNotEqualTo("USRIDIN");
        }

        @Test
        @DisplayName("the names come BEFORE the identifier, which is the inverse of COUSR02/COUSR03")
        void namesComeBeforeTheIdentifier() {
            int fName = MAP_MEMBERS.indexOf("fName");
            int lName = MAP_MEMBERS.indexOf("lName");
            int userId = MAP_MEMBERS.indexOf("userId");
            int passwd = MAP_MEMBERS.indexOf("passwd");
            int usrType = MAP_MEMBERS.indexOf("usrType");
            assertThat(fName).isLessThan(lName);
            assertThat(lName)
                    .as("both names precede the identifier on this screen")
                    .isLessThan(userId);
            assertThat(userId).isLessThan(passwd);
            assertThat(passwd).isLessThan(usrType);
            assertThat(MAP_MEMBERS.subList(fName, usrType + 1))
                    .as("the five collected fields, in this screen's own order")
                    .containsExactly("fName", "lName", "userId", "passwd", "usrType");
            assertThat(componentNames().subList(fName, usrType + 1))
                    .as("and the record declares them in exactly that order")
                    .containsExactly("fName", "lName", "userId", "passwd", "usrType");
        }

        @Test
        @DisplayName("curTime is PIC X(8) here, not the PIC X(9) of the sign-on screen")
        void curTimeIsEightNotNine() {
            assertThat(UserAddResponse.CUR_TIME_LENGTH).isEqualTo(8).isNotEqualTo(9);
            assertThat(DECLARED_WIDTHS.get(MAP_MEMBERS.indexOf("curTime"))).isEqualTo(8);
            assertThat(UserAddResponse.CUR_DATE_LENGTH)
                    .as("CURDATE is eight as well - mm/dd/yy, bms:47-51")
                    .isEqualTo(8);
            assertThat(header().wsCurtimeHhMmSs())
                    .as("hh:mm:ss occupies exactly the declared width")
                    .hasSize(UserAddResponse.CUR_TIME_LENGTH);
        }

        @Test
        @DisplayName("errMsg is PIC X(78) while WS-MESSAGE is PIC X(80): two characters are lost")
        void errMsgIsSeventyEightAndTheMoveTruncatesOnTheRight() {
            assertThat(UserAddResponse.ERR_MSG_LENGTH).isEqualTo(78).isNotEqualTo(80);
            assertThat(UserAddResponse.WS_MESSAGE_LENGTH).isEqualTo(WS_MESSAGE_LENGTH).isEqualTo(80);
            assertThat(UserAddResponse.WS_MESSAGE_LENGTH - UserAddResponse.ERR_MSG_LENGTH)
                    .as("two characters, every time a message fills the sender")
                    .isEqualTo(2);

            String full = "X".repeat(UserAddResponse.ERR_MSG_LENGTH) + "YZ";
            assertThat(full).hasSize(WS_MESSAGE_LENGTH);
            String narrowed = codec().movePicX(full, UserAddResponse.ERR_MSG_LENGTH);
            assertThat(narrowed)
                    .as("the surviving characters are the leading 78")
                    .hasSize(UserAddResponse.ERR_MSG_LENGTH)
                    .isEqualTo("X".repeat(UserAddResponse.ERR_MSG_LENGTH))
                    .doesNotContain("Y")
                    .doesNotContain("Z");

            UserAddResponse response = responseOf(withMember(blankMapValues(), "errMsg", narrowed),
                    NavigationContext.empty(), spaces(8), MAPSET, MAP);
            assertThat(response.errMsg())
                    .as("and the payload carries the narrowed image, unchanged")
                    .isEqualTo(narrowed)
                    .hasSize(UserAddResponse.ERR_MSG_LENGTH);
            assertThat(deserialise(serialise(response)).errMsg()).isEqualTo(narrowed);
        }

        @Test
        @DisplayName("a message shorter than 78 loses only spaces, so the same MOVE is lossless")
        void aShortMessageLosesNothingVisible() {
            assertThat(MSG_DUPLICATE.length()).isLessThan(UserAddResponse.ERR_MSG_LENGTH);
            String image = wsMessageImage(MSG_DUPLICATE);
            assertThat(image).hasSize(WS_MESSAGE_LENGTH).startsWith(MSG_DUPLICATE);
            assertThat(image.substring(UserAddResponse.ERR_MSG_LENGTH))
                    .as("the two characters line 188 discards")
                    .isEqualTo("  ");
            assertThat(narrowToErrMsg(MSG_DUPLICATE))
                    .hasSize(UserAddResponse.ERR_MSG_LENGTH)
                    .isEqualTo(codec().movePicX(MSG_DUPLICATE, UserAddResponse.ERR_MSG_LENGTH))
                    .startsWith(MSG_DUPLICATE);
        }

        @Test
        @DisplayName("errMsg's map-declared colour is RED; DFHGREEN at line 254 is an override")
        void redIsTheDeclaredColourAndGreenIsTheOverride() {
            assertThat(BmsAttributes.colourMnemonic(BmsAttributes.DFHRED)).isEqualTo("DFHRED");
            assertThat(BmsAttributes.colourMnemonic(BmsAttributes.DFHGREEN)).isEqualTo("DFHGREEN");
            assertThat(BmsAttributes.DFHGREEN)
                    .as("the override is a different byte from the declared colour")
                    .isNotEqualTo(BmsAttributes.DFHRED);
            assertThat(DFHMDF_LABELS.get(MAP_MEMBERS.indexOf("errMsg"))
                    + FieldAttributeSetter.COLOUR_ITEM_SUFFIX)
                    .as("COUSR01C.cbl:254 names ERRMSGC, which COUSR01.CPY:160 declares")
                    .isEqualTo("ERRMSGC");
            assertThat(componentNames())
                    .as("and no colour item is a member of this payload")
                    .doesNotContain("errMsgC", "errMsgColour", "errMsgColor");
        }
    }

    @Nested
    @DisplayName("Header literals - titles at PIC X(40), identity, and a fixed clock")
    class HeaderLiterals {
        @Test
        @DisplayName("title01 and title02 are the CCDA titles, each exactly 40 characters")
        void theTitlesAreTheScreenTitleLiterals() {
            assertThat(ScreenTitles.CCDA_TITLE01)
                    .hasSize(UserAddResponse.TITLE01_LENGTH)
                    .hasSize(ScreenTitles.TITLE_LENGTH)
                    .contains("AWS Mainframe Modernization")
                    .startsWith(" ")
                    .endsWith(" ");
            assertThat(ScreenTitles.CCDA_TITLE02)
                    .hasSize(UserAddResponse.TITLE02_LENGTH)
                    .contains("CardDemo")
                    .startsWith(" ")
                    .endsWith(" ");
            UserAddResponse response = sentScreen(spaces(WS_MESSAGE_LENGTH));
            assertThat(response.title01()).isEqualTo(ScreenTitles.CCDA_TITLE01);
            assertThat(response.title02()).isEqualTo(ScreenTitles.CCDA_TITLE02);
            assertThat(deserialise(serialise(response)).title01())
                    .as("the padding survives the wire, or the screen is no longer centred")
                    .isEqualTo(ScreenTitles.CCDA_TITLE01);
        }

        @Test
        @DisplayName("CCDA_THANK_YOU and CCDA_MSG_THANK_YOU are different things, and neither is used here")
        void theTwoThankYouLiteralsAreNotInterchangeable() {
            assertThat(ScreenTitles.CCDA_THANK_YOU).hasSize(ScreenTitles.TITLE_LENGTH).hasSize(40);
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU)
                    .hasSize(SystemMessages.MESSAGE_LENGTH)
                    .hasSize(50);
            assertThat(ScreenTitles.CCDA_THANK_YOU.strip())
                    .isNotEqualTo(SystemMessages.CCDA_MSG_THANK_YOU.strip());
            assertThat(mapValuesOf(sentScreen(spaces(WS_MESSAGE_LENGTH))))
                    .doesNotContain(ScreenTitles.CCDA_THANK_YOU)
                    .doesNotContain(SystemMessages.CCDA_MSG_THANK_YOU);
        }

        @Test
        @DisplayName("the invalid-key message is the one system message this screen does use")
        void theInvalidKeyMessageFitsErrMsg() {
            assertThat(SystemMessages.CCDA_MSG_INVALID_KEY)
                    .hasSize(SystemMessages.MESSAGE_LENGTH)
                    .contains("Invalid key pressed");
            assertThat(SystemMessages.MESSAGE_LENGTH).isLessThan(UserAddResponse.ERR_MSG_LENGTH);
            String errMsg = narrowToErrMsg(SystemMessages.CCDA_MSG_INVALID_KEY);
            assertThat(errMsg)
                    .hasSize(UserAddResponse.ERR_MSG_LENGTH)
                    .startsWith(SystemMessages.CCDA_MSG_INVALID_KEY);
            assertThat(sentScreen(SystemMessages.CCDA_MSG_INVALID_KEY).errMsg()).isEqualTo(errMsg);
        }

        @Test
        @DisplayName("trnName and pgmName are the working-storage literals, at their declared widths")
        void theTransactionAndProgramNames() {
            UserAddResponse response = sentScreen(spaces(WS_MESSAGE_LENGTH));
            assertThat(response.trnName())
                    .as("COUSR01C.cbl:220 moves WS-TRANID into TRNNAMEO")
                    .isEqualTo(WS_TRANID)
                    .hasSize(UserAddResponse.TRN_NAME_LENGTH);
            assertThat(response.pgmName())
                    .as("COUSR01C.cbl:221 moves WS-PGMNAME into PGMNAMEO")
                    .isEqualTo(WS_PGMNAME)
                    .hasSize(UserAddResponse.PGM_NAME_LENGTH);
        }

        @Test
        @DisplayName("curDate and curTime come from a fixed clock, never from now()")
        void theDateAndTimeAreDrivenFromAFixedClock() {
            UserAddResponse response = sentScreen(spaces(WS_MESSAGE_LENGTH));
            assertThat(response.curDate())
                    .as("mm/dd/yy for the instant stamped in app/bms/COUSR01.bms:163")
                    .isEqualTo("08/22/22")
                    .hasSize(UserAddResponse.CUR_DATE_LENGTH);
            assertThat(response.curTime())
                    .as("hh:mm:ss for the same instant")
                    .isEqualTo("17:02:44")
                    .hasSize(UserAddResponse.CUR_TIME_LENGTH);
            assertThat(sentScreen(spaces(WS_MESSAGE_LENGTH)).curDate())
                    .as("and the same fixed clock yields the same image every time it is read")
                    .isEqualTo(response.curDate());
        }
    }

    @Nested
    @DisplayName("Stored widths - the five collected fields against CSUSR01Y and SecUserRecord")
    class StoredWidths {
        @ParameterizedTest(name = "[{index}] {0} on the screen is {1} bytes in SEC-USER-DATA")
        @CsvSource({"fName,20", "lName,20", "userId,8", "passwd,8", "usrType,1"})
        @DisplayName("each collected field is the same width on the screen as in the record")
        void screenWidthEqualsStoredWidth(String member, int storedWidth) {
            assertThat(DECLARED_WIDTHS.get(MAP_MEMBERS.indexOf(member)))
                    .as("%s: the DFHMDF LENGTH and the CSUSR01Y PICTURE must agree", member)
                    .isEqualTo(storedWidth);
        }

        @Test
        @DisplayName("the five CSUSR01Y widths are transcribed exactly, in the record's own order")
        void theCopybookWidths() {
            assertThat(SEC_USR_ID_LENGTH).isEqualTo(SecUserRecord.SEC_USR_ID_LENGTH);
            assertThat(SEC_USR_FNAME_LENGTH).isEqualTo(SecUserRecord.SEC_USR_FNAME_LENGTH);
            assertThat(SEC_USR_LNAME_LENGTH).isEqualTo(SecUserRecord.SEC_USR_LNAME_LENGTH);
            assertThat(SEC_USR_PWD_LENGTH).isEqualTo(SecUserRecord.SEC_USR_PWD_LENGTH);
            assertThat(SEC_USR_TYPE_LENGTH).isEqualTo(SecUserRecord.SEC_USR_TYPE_LENGTH);
            assertThat(SEC_USR_ID_LENGTH + SEC_USR_FNAME_LENGTH + SEC_USR_LNAME_LENGTH
                    + SEC_USR_PWD_LENGTH + SEC_USR_TYPE_LENGTH
                    + SecUserRecord.SEC_USR_FILLER_LENGTH)
                    .as("the six items account for every byte of the 80-byte record")
                    .isEqualTo(SecUserRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("the stored offsets are 0, 8, 28, 48, 56 and 57")
        void theStoredOffsets() {
            assertThat(SecUserRecord.SEC_USR_ID_OFFSET).isZero();
            assertThat(SecUserRecord.SEC_USR_FNAME_OFFSET).isEqualTo(8);
            assertThat(SecUserRecord.SEC_USR_LNAME_OFFSET).isEqualTo(28);
            assertThat(SecUserRecord.SEC_USR_PWD_OFFSET).isEqualTo(48);
            assertThat(SecUserRecord.SEC_USR_TYPE_OFFSET).isEqualTo(56);
            assertThat(SecUserRecord.SEC_USR_FILLER_OFFSET).isEqualTo(57);
            assertThat(SecUserRecord.RECORD_LENGTH).isEqualTo(80);
            assertThat(SecUserRecord.KEY_OFFSET).isEqualTo(SecUserRecord.SEC_USR_ID_OFFSET);
            assertThat(SecUserRecord.KEY_LENGTH)
                    .isEqualTo(SEC_USR_ID_LENGTH)
                    .isEqualTo(UserAddResponse.USER_ID_LENGTH);
        }

        @Test
        @DisplayName("what the screen collects survives an encode/decode at the named code page")
        void theCollectedValuesRoundTripThroughTheRecord() {
            SecUserRecord record = SecUserRecord.of("NEWUSR01", "Jane", "Roe", "PLAINTXT", "U",
                    MAP_CHARSET);
            byte[] image = SecUserRecord.encode(record, MAP_CHARSET);
            assertThat(image).hasSize(SecUserRecord.RECORD_LENGTH);
            SecUserRecord restored = SecUserRecord.decode(image, MAP_CHARSET);
            assertThat(restored.image(SecUserRecord.FIELD_SEC_USR_ID))
                    .hasSize(UserAddResponse.USER_ID_LENGTH);
            assertThat(restored.image(SecUserRecord.FIELD_SEC_USR_FNAME))
                    .hasSize(UserAddResponse.F_NAME_LENGTH);
            assertThat(restored.image(SecUserRecord.FIELD_SEC_USR_LNAME))
                    .hasSize(UserAddResponse.L_NAME_LENGTH);
            assertThat(restored.image(SecUserRecord.FIELD_SEC_USR_PWD))
                    .hasSize(UserAddResponse.PASSWD_LENGTH);
            assertThat(restored.image(SecUserRecord.FIELD_SEC_USR_TYPE))
                    .hasSize(UserAddResponse.USR_TYPE_LENGTH);
        }
    }

    @Nested
    @DisplayName("The password - declared at PIC X(8), and only ever spaces on the way out")
    class PasswordDeclaredButNeverEchoed {
        @Test
        @DisplayName("passwd exists, is the tenth member, and is PIC X(8)")
        void thePasswordMemberExists() {
            assertThat(MAP_MEMBERS.indexOf("passwd"))
                    .as("tenth of the twelve, between userId and usrType")
                    .isEqualTo(9);
            assertThat(componentNames()).contains("passwd");
            assertThat(UserAddResponse.PASSWD_FIELD).isEqualTo("PASSWDO");
            assertThat(UserAddResponse.PASSWD_LENGTH)
                    .as("PASSWDO PIC X(8), matching SEC-USR-PWD PIC X(08) at CSUSR01Y.cpy:21")
                    .isEqualTo(8)
                    .isEqualTo(SEC_USR_PWD_LENGTH);
            assertThat(UserAddResponse.MAP_DERIVED_FIELD_NAMES)
                    .as("omitting it would make the projection 11 of 12 and break gate G9")
                    .contains("PASSWDO")
                    .hasSize(DFHMDF_NAMED);
        }

        @Test
        @DisplayName("the value it carries is spaces, not a password read back from USRSEC")
        void theValueCarriedIsBlank() {
            UserAddResponse response = sentScreen(composedSuccessMessage("NEWUSR01"));
            assertThat(response.passwd())
                    .as("eight spaces, exactly as INITIALIZE-ALL-FIELDS leaves the field")
                    .isEqualTo(spaces(UserAddResponse.PASSWD_LENGTH))
                    .isBlank()
                    .hasSize(UserAddResponse.PASSWD_LENGTH);
            assertThat(response.errMsg())
                    .as("while the message line does report the addition")
                    .contains("has been added");
        }

        @Test
        @DisplayName("the credential is not published, and comes back as eight spaces rather than null")
        void blankSurvivesTheRoundTrip() {
            UserAddResponse response = sentScreen(spaces(WS_MESSAGE_LENGTH));
            String json = serialise(response);
            assertThat(json)
                    .as("no property under any spelling, so nothing to trim, coerce or omit")
                    .doesNotContain("passwd")
                    .doesNotContain("password");
            assertThat(jsonKeys(response)).doesNotContainKey("passwd");

            UserAddResponse restored = roundTrip(response);
            assertThat(restored.passwd())
                    .as("an absent member deserialises to null, which the canonical constructor "
                            + "normalises to the declared width in spaces - so nothing downstream has "
                            + "to test for a null")
                    .isNotNull()
                    .isNotEmpty()
                    .isEqualTo(spaces(UserAddResponse.PASSWD_LENGTH))
                    .hasSize(UserAddResponse.PASSWD_LENGTH);
            assertThat(restored)
                    .as("and on the successful-add path the field really is blank, so nothing is lost")
                    .isEqualTo(response);
        }

        @Test
        @DisplayName("LOW-VALUES and SPACES are different byte images, and line 85 writes the first")
        void lowValuesAndSpacesAreNotTheSameImage() {
            String low = lowValues(UserAddResponse.PASSWD_LENGTH);
            String blank = spaces(UserAddResponse.PASSWD_LENGTH);
            assertThat(low).hasSize(UserAddResponse.PASSWD_LENGTH).isNotEqualTo(blank);
            assertThat(low.getBytes(MAP_CHARSET)).containsOnly((byte) 0x00);
            assertThat(blank.getBytes(MAP_CHARSET)).containsOnly((byte) 0x20);
            assertThat(low.isBlank())
                    .as("a NUL is not whitespace to Java, so isBlank() is not the COBOL predicate")
                    .isFalse();

            UserAddResponse response = responseOf(withMember(blankMapValues(), "passwd", low),
                    NavigationContext.empty(), spaces(8), MAPSET, MAP);
            assertThat(response.passwd())
                    .as("the payload carries whichever blank form it was given, unaltered")
                    .isEqualTo(low)
                    .isNotEqualTo(blank);
        }

        @Test
        @DisplayName("if a value ever is placed here it is carried verbatim - no hash, no mask, no fold")
        void aValuePlacedHereIsCarriedVerbatim() {
            String keyed = "plaintxt";
            UserAddResponse response = responseOf(withMember(blankMapValues(), "passwd", keyed),
                    NavigationContext.empty(), spaces(8), MAPSET, MAP);
            assertThat(response.passwd())
                    .isEqualTo(keyed)
                    .isNotEqualTo(keyed.toUpperCase(Locale.ROOT))
                    .hasSize(UserAddResponse.PASSWD_LENGTH);
            assertThat(serialise(response))
                    .as("carried verbatim in-process and published nowhere: the error repaint really "
                            + "does re-transmit what was keyed, and a JSON body is not a 3270 under DRK")
                    .doesNotContain(keyed)
                    .doesNotContain("passwd");
        }

        @Test
        @DisplayName("the DRK attribute on PASSWD is terminal non-display, never storage masking")
        void theDarkAttributeIsPresentationOnly() {
            assertThat(BmsAttributes.isNonDisplay(BmsAttributes.DFHBMDAR))
                    .as("DFHBMDAR is the unprotected non-display attribute - DRK")
                    .isTrue();
            assertThat(BmsAttributes.isNonDisplay(BmsAttributes.DFHBMUNP))
                    .as("while a plain unprotected field displays what it holds")
                    .isFalse();
            assertThat(componentNames()).doesNotContain("passwdA", "passwdF", "passwdC", "passwdL");
        }

        @Test
        @DisplayName("the only suppression is the credential's ignore, and only passwd carries it")
        void theOnlySuppressionIsTheDocumentedIgnore() {
            // DRK is what the terminal does; withholding the member is how a wire with no DRK reproduces
            // it. This pins the exact mechanism so anything broader or subtler fails the build as loudly
            // as removing it would: a masking serialiser or a rename would change what a caller sees
            // under a name that traces to no symbolic-map item, and a narrowed access mode on any other
            // member would shrink a projection the client is entitled to read back in full.
            assertThat(annotationSimpleNamesOn(WRITE_ONLY_MEMBER))
                    .as("the credential answers to a @JsonIgnore, which is what withholds it")
                    .contains("JsonIgnore")
                    .doesNotContain("JsonSerialize", "JsonRawValue", "JsonView");
            assertThat(jsonPropertyOn(WRITE_ONLY_MEMBER))
                    .as("and by suppression alone - not renamed into the payload under another spelling")
                    .isNull();

            List<String> suppressed = new ArrayList<>();
            for (RecordComponent component : UserAddResponse.class.getRecordComponents()) {
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
                            + "twelve-of-twelve projection gate G9 requires stays complete")
                    .containsExactly(WRITE_ONLY_MEMBER);
        }
    }

    @Nested
    @DisplayName("The COUSR1AO overlay - 339 bytes, one storage, two views")
    class SymbolicMapOverlay {
        @Test
        @DisplayName("the two seven-byte prefixes are built from different items of the same size")
        void bothPrefixesAreSevenBytes() {
            assertThat(LENGTH_ITEM_LENGTH + FLAG_ITEM_LENGTH + INPUT_RESERVED_LENGTH)
                    .as("input side: xxxL(2) + xxxF(1) + FILLER X(4)")
                    .isEqualTo(FIELD_PREFIX_LENGTH);
            assertThat(OUTPUT_RESERVED_LENGTH + ATTRIBUTE_ITEM_COUNT * ATTRIBUTE_ITEM_LENGTH)
                    .as("output side: FILLER X(3) + xxxC + xxxP + xxxH + xxxV")
                    .isEqualTo(FIELD_PREFIX_LENGTH);
            assertThat(FIELD_PREFIX_LENGTH)
                    .as("this single equality is what makes the group-level REDEFINES align")
                    .isEqualTo(7);
            assertThat(SYMBOLIC_MAP_LAYOUT.span("TRNNAMEA").offset())
                    .isEqualTo(SYMBOLIC_MAP_LAYOUT.span("TRNNAMEF").offset());
            assertThat(SYMBOLIC_MAP_LAYOUT.span("TRNNAMEA").length())
                    .isEqualTo(FLAG_ITEM_LENGTH);
        }

        @Test
        @DisplayName("the declared widths sum to 243 and the map is 339 bytes in both views")
        void theTotalWidth() {
            assertThat(DECLARED_WIDTHS.stream().mapToInt(Integer::intValue).sum())
                    .as("4+40+8+8+40+8+20+20+8+8+1+78")
                    .isEqualTo(PAYLOAD_WIDTH_TOTAL)
                    .isEqualTo(243);
            assertThat(TIOAPFX_PREFIX_LENGTH + DFHMDF_NAMED * FIELD_PREFIX_LENGTH
                    + PAYLOAD_WIDTH_TOTAL)
                    .as("12 + 12x7 + 243")
                    .isEqualTo(SYMBOLIC_MAP_LENGTH)
                    .isEqualTo(339);
            assertThat(SYMBOLIC_MAP_LAYOUT.recordLength()).isEqualTo(SYMBOLIC_MAP_LENGTH);
            assertThat(SYMBOLIC_MAP_LAYOUT.storageSpans().stream()
                    .mapToInt(FixedWidthRecord.FieldSpan::length)
                    .sum())
                    .as("the storage spans of 01 COUSR1AI account for every byte exactly once")
                    .isEqualTo(SYMBOLIC_MAP_LENGTH);
        }

        @Test
        @DisplayName("every xxxO item sits at the identical offset as its xxxI partner")
        void thetwoViewsAlignFieldForField() {
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                FixedWidthRecord.FieldSpan input = SYMBOLIC_MAP_LAYOUT.span(XXXI_ITEMS.get(index));
                FixedWidthRecord.FieldSpan output = SYMBOLIC_MAP_LAYOUT.span(XXXO_ITEMS.get(index));
                assertThat(output.offset())
                        .as("%s and %s must describe the same bytes",
                                XXXI_ITEMS.get(index), XXXO_ITEMS.get(index))
                        .isEqualTo(input.offset())
                        .isEqualTo(DATA_OFFSETS.get(index));
                assertThat(output.length())
                        .as("and the same width")
                        .isEqualTo(input.length())
                        .isEqualTo(DECLARED_WIDTHS.get(index));
                assertThat(output.redefinition())
                        .as("the output item is the overlay; the input item is the storage")
                        .isTrue();
                assertThat(input.redefinition()).isFalse();
            }
        }

        @Test
        @DisplayName("the derived data offsets are 19, 30, 77, 92, 107, 154, 169, 196, 223, 238, 253, 261")
        void theDerivedOffsets() {
            assertThat(DATA_OFFSETS)
                    .containsExactly(19, 30, 77, 92, 107, 154, 169, 196, 223, 238, 253, 261);
            assertThat(DATA_OFFSETS.get(0))
                    .as("the first data item begins after the 12-byte prefix and one 7-byte control set")
                    .isEqualTo(TIOAPFX_PREFIX_LENGTH + FIELD_PREFIX_LENGTH);
            int last = DFHMDF_NAMED - 1;
            assertThat(DATA_OFFSETS.get(last) + DECLARED_WIDTHS.get(last))
                    .as("and ERRMSGO's 78 bytes run to the very end of the map")
                    .isEqualTo(SYMBOLIC_MAP_LENGTH);
        }

        @Test
        @DisplayName("the prefixes are the same size but not the same shape: xxxF is at +2, xxxC at +3")
        void theAttributeItemsDoNotOverlayEachOther() {
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                String label = DFHMDF_LABELS.get(index);
                int prefix = DATA_OFFSETS.get(index) - FIELD_PREFIX_LENGTH;
                assertThat(SYMBOLIC_MAP_LAYOUT.span(label + "F").offset())
                        .as("%sF follows the two-byte length item", label)
                        .isEqualTo(prefix + LENGTH_ITEM_LENGTH);
                assertThat(SYMBOLIC_MAP_LAYOUT.span(label + "C").offset())
                        .as("%sC follows the three-byte output filler", label)
                        .isEqualTo(prefix + OUTPUT_RESERVED_LENGTH);
                assertThat(SYMBOLIC_MAP_LAYOUT.span(label + "C").offset())
                        .as("so the colour byte and the flag byte are different bytes: only the DATA "
                                + "items correspond across the two views")
                        .isNotEqualTo(SYMBOLIC_MAP_LAYOUT.span(label + "F").offset());
                for (int attribute = 0; attribute < ATTRIBUTE_ITEM_COUNT; attribute++) {
                    FixedWidthRecord.FieldSpan span = SYMBOLIC_MAP_LAYOUT.span(
                            label + OUTPUT_CONTROL_SUFFIXES.get(attribute));
                    assertThat(span.length()).isEqualTo(ATTRIBUTE_ITEM_LENGTH);
                    assertThat(span.offset())
                            .isEqualTo(prefix + OUTPUT_RESERVED_LENGTH + attribute);
                    assertThat(span.endOffsetExclusive())
                            .as("every attribute item stays inside the seven-byte prefix")
                            .isLessThanOrEqualTo(DATA_OFFSETS.get(index));
                }
            }
        }

        @ParameterizedTest(name = "[{index}] written through {0}, read back through {1}")
        @CsvSource({"TRNNAMEI,TRNNAMEO,CU01", "USERIDI,USERIDO,NEWUSR01",
                    "PASSWDI,PASSWDO,plaintxt", "ERRMSGI,ERRMSGO,User ID already exist..."})
        @DisplayName("a value written through one view reads back through the other")
        void aValueWrittenThroughOneViewReadsBackThroughTheOther(String inputItem, String outputItem,
                String value) {
            FixedWidthCodec codec = codec();
            byte[] image = codec.serialise(SYMBOLIC_MAP_LAYOUT, Map.of(inputItem, value));
            assertThat(image).hasSize(SYMBOLIC_MAP_LENGTH);

            Map<String, String> images = codec.deserialise(SYMBOLIC_MAP_LAYOUT, image);
            FixedWidthRecord.FieldSpan span = SYMBOLIC_MAP_LAYOUT.span(outputItem);
            String expected = codec.movePicX(value, span.length());
            assertThat(images.get(outputItem))
                    .as("%s reads the bytes %s wrote", outputItem, inputItem)
                    .isEqualTo(expected)
                    .isEqualTo(images.get(inputItem));

            byte[] reverse = codec.serialise(SYMBOLIC_MAP_LAYOUT, Map.of(outputItem, value));
            assertThat(codec.deserialise(SYMBOLIC_MAP_LAYOUT, reverse).get(inputItem))
                    .as("%s reads the bytes %s wrote", inputItem, outputItem)
                    .isEqualTo(expected);
            assertThat(reverse)
                    .as("both directions produce the identical 339-byte image")
                    .isEqualTo(image);
        }

        @Test
        @DisplayName("writing one field disturbs no other, so the overlay is not a shared buffer bug")
        void writingOneFieldDisturbsNoOther() {
            FixedWidthCodec codec = codec();
            byte[] image = codec.serialise(SYMBOLIC_MAP_LAYOUT, Map.of("USERIDO", "NEWUSR01"));
            Map<String, String> images = codec.deserialise(SYMBOLIC_MAP_LAYOUT, image);
            assertThat(images.get("USERIDO")).isEqualTo("NEWUSR01");
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                if ("USERIDO".equals(XXXO_ITEMS.get(index))) {
                    continue;
                }
                assertThat(images.get(XXXO_ITEMS.get(index)))
                        .as("%s was not written, so it holds its initialised spaces",
                                XXXO_ITEMS.get(index))
                        .isEqualTo(spaces(DECLARED_WIDTHS.get(index)));
            }
        }

        @Test
        @DisplayName("no filler is exposed: not the 12-byte prefix, not the per-field reserved spans")
        void noFillerIsExposed() {
            Map<String, String> images = codec().deserialise(SYMBOLIC_MAP_LAYOUT,
                    codec().serialise(SYMBOLIC_MAP_LAYOUT, Map.of()));
            assertThat(images.keySet())
                    .as("FILLER is storage, never a named field")
                    .doesNotContain("FILLER")
                    .hasSize(DFHMDF_NAMED * NAMED_ITEMS_PER_FIELD);
            assertThat(SYMBOLIC_MAP_LAYOUT.hasSpan("FILLER"))
                    .as("and it is not addressable by name either")
                    .isFalse();
            for (String name : componentNames()) {
                assertThat(name.toUpperCase(Locale.ROOT))
                        .doesNotContain("FILLER")
                        .doesNotContain("TIOAPFX");
            }
            assertThat(jsonKeys(blankResponse()).keySet())
                    .allSatisfy(key -> assertThat(key.toUpperCase(Locale.ROOT))
                            .doesNotContain("FILLER")
                            .doesNotContain("TIOAPFX"));
        }

        @Test
        @DisplayName("the control items are metadata: none of xxxL/xxxF/xxxA/xxxC/xxxP/xxxH/xxxV is a member")
        void theControlItemsAreMetadataNotPayload() {
            assertThat(FieldAttributeSetter.COLOUR_ITEM_SUFFIX).isEqualTo("C");
            assertThat(OUTPUT_CONTROL_SUFFIXES).containsExactly("C", "P", "H", "V");
            assertThat(INPUT_CONTROL_SUFFIXES).containsExactly("L", "F", "A");

            Set<String> jsonMembers = jsonKeys(blankResponse()).keySet();
            List<String> allSuffixes = new ArrayList<>(INPUT_CONTROL_SUFFIXES);
            allSuffixes.addAll(OUTPUT_CONTROL_SUFFIXES);
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                String member = MAP_MEMBERS.get(index);
                for (String suffix : allSuffixes) {
                    assertThat(componentNames())
                            .as("%s%s is a control item, not a member", member, suffix)
                            .doesNotContain(member + suffix);
                    assertThat(jsonMembers)
                            .as("%s%s must not reach the wire", member, suffix)
                            .doesNotContain(member + suffix);
                    assertThat(jsonMembers)
                            .doesNotContain(DFHMDF_LABELS.get(index) + suffix);
                }
            }
        }
    }

    @Nested
    @DisplayName("Outcome messages - eight texts, one PIC X(78) field, one narrowing rule")
    class OutcomeMessages {
        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {"First Name can NOT be empty...",
                               "Last Name can NOT be empty...",
                               "User ID can NOT be empty...",
                               "Password can NOT be empty...",
                               "User Type can NOT be empty...",
                               "User ID already exist...",
                               "Unable to Add User..."})
        @DisplayName("every message fits PIC X(78) and round-trips at the declared width")
        void everyMessageFitsAndRoundTrips(String text) {
            String sender = wsMessageImage(text);
            assertThat(sender)
                    .as("the WS-MESSAGE image is always 80 bytes, space-padded on the right")
                    .hasSize(WS_MESSAGE_LENGTH)
                    .startsWith(text);

            String receiver = narrowToErrMsg(text);
            assertThat(receiver)
                    .as("and the ERRMSGO image is always 78")
                    .hasSize(UserAddResponse.ERR_MSG_LENGTH)
                    .startsWith(text);
            assertThat(receiver)
                    .as("the leading 78 characters of the sender survive, and the trailing two do not")
                    .isEqualTo(sender.substring(0, UserAddResponse.ERR_MSG_LENGTH));

            UserAddResponse response = sentScreen(text);
            assertThat(response.errMsg()).isEqualTo(receiver);
            assertThat(deserialise(serialise(response)).errMsg())
                    .as("including its trailing padding, which is part of the rendered line")
                    .isEqualTo(receiver);
        }

        @Test
        @DisplayName("the five blank-field messages keep their EVALUATE order, first match winning")
        void theFiveBlankFieldMessagesAreOrdered() {
            assertThat(ORDERED_BLANK_FIELD_MESSAGES)
                    .containsExactly(MSG_FIRST_NAME_EMPTY,
                            MSG_LAST_NAME_EMPTY,
                            MSG_USER_ID_EMPTY,
                            MSG_PASSWORD_EMPTY,
                            MSG_USER_TYPE_EMPTY);
            List<String> collectedMembers = List.of("fName", "lName", "userId", "passwd", "usrType");
            for (int index = 0; index < collectedMembers.size(); index++) {
                int position = MAP_MEMBERS.indexOf(collectedMembers.get(index));
                assertThat(position)
                        .as("the arm for %s tests the field at member position %d",
                                collectedMembers.get(index), position)
                        .isEqualTo(MAP_MEMBERS.indexOf("fName") + index);
            }
            assertThat(ORDERED_BLANK_FIELD_MESSAGES).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("the WHEN OTHER arm sets no message at all, so errMsg stays blank")
        void theWhenOtherArmSetsNoMessage() {
            UserAddResponse response = sentScreen(spaces(WS_MESSAGE_LENGTH));
            assertThat(response.errMsg())
                    .as("78 spaces: an empty message line, not a null and not an empty string")
                    .isEqualTo(spaces(UserAddResponse.ERR_MSG_LENGTH))
                    .isBlank()
                    .hasSize(UserAddResponse.ERR_MSG_LENGTH);
            assertThat(deserialise(serialise(response)).errMsg())
                    .isEqualTo(spaces(UserAddResponse.ERR_MSG_LENGTH));
        }

        @ParameterizedTest(name = "[{index}] the identifier {0} composes a {1}-character message")
        @CsvSource({"NEWUSR01,32", "AB,26", "A,25", "USER0001,32"})
        @DisplayName("the success text is variable in length, because DELIMITED BY SPACE stops early")
        void theSuccessTextIsVariableInLength(String keyedId, int composedLength) {
            String secUsrId = codec().movePicX(keyedId, SEC_USR_ID_LENGTH);
            assertThat(secUsrId).hasSize(SEC_USR_ID_LENGTH).startsWith(keyedId);
            String composed = composedSuccessMessage(secUsrId);
            assertThat(composed)
                    .hasSize(composedLength)
                    .startsWith(SUCCESS_PREFIX)
                    .endsWith(SUCCESS_SUFFIX)
                    .doesNotContain("  ");
            assertThat(SUCCESS_PREFIX.length() + secUsrId.strip().length() + SUCCESS_SUFFIX.length())
                    .as("prefix + the identifier up to its first space + suffix")
                    .isEqualTo(composedLength);
        }

        @Test
        @DisplayName("STRING does not pad, which is why line 253 blanks WS-MESSAGE first")
        void theStringVerbDoesNotPadItsReceiver() {
            String composed = composedSuccessMessage("NEWUSR01");
            String image = wsMessageImage(composed);
            assertThat(image)
                    .hasSize(WS_MESSAGE_LENGTH)
                    .startsWith(composed)
                    .isEqualTo(composed + spaces(WS_MESSAGE_LENGTH - composed.length()));
            assertThat(image.substring(composed.length()))
                    .as("the tail is spaces because line 253 put them there, not because STRING did")
                    .isBlank();
            assertThat(narrowToErrMsg(composed))
                    .hasSize(UserAddResponse.ERR_MSG_LENGTH)
                    .isEqualTo(codec().movePicX(composed, UserAddResponse.ERR_MSG_LENGTH));
        }

        @Test
        @DisplayName("the duplicate-key and fallback texts are the write's other two outcomes")
        void theWriteOutcomeTexts() {
            assertThat(MSG_DUPLICATE).isEqualTo("User ID already exist...");
            assertThat(MSG_UNABLE_TO_ADD).isEqualTo("Unable to Add User...");
            assertThat(sentScreen(MSG_DUPLICATE).errMsg())
                    .hasSize(UserAddResponse.ERR_MSG_LENGTH)
                    .startsWith(MSG_DUPLICATE);
            assertThat(sentScreen(MSG_UNABLE_TO_ADD).errMsg())
                    .hasSize(UserAddResponse.ERR_MSG_LENGTH)
                    .startsWith(MSG_UNABLE_TO_ADD);
            UserAddResponse success = sentScreen(composedSuccessMessage("NEWUSR01"));
            assertThat(List.of(success.fName(), success.lName(), success.userId(), success.passwd(),
                    success.usrType()))
                    .as("all five blank, at their own declared widths")
                    .allSatisfy(value -> assertThat(value).isBlank());
        }
    }

    @Nested
    @DisplayName("Stateless navigation - XCTL becomes three fields and the commarea travels")
    class StatelessNavigation {
        @Test
        @DisplayName("nextProgram, nextMapset and nextMap replace EXEC CICS XCTL")
        void theTransferBecomesThreeResponseFields() {
            assertThat(componentNames()).contains("nextProgram", "nextMapset", "nextMap");
            assertThat(UserAddResponse.NEXT_PROGRAM_LENGTH)
                    .as("CDEMO-TO-PROGRAM PIC X(08) at COCOM01Y.cpy:24")
                    .isEqualTo(NavigationContext.TO_PROGRAM_LENGTH)
                    .isEqualTo(8);
            assertThat(UserAddResponse.NEXT_MAP_LENGTH)
                    .as("CDEMO-LAST-MAP PIC X(7) at COCOM01Y.cpy:43 - seven, not eight")
                    .isEqualTo(NavigationContext.LAST_MAP_LENGTH)
                    .isEqualTo(7);
            assertThat(UserAddResponse.NEXT_MAPSET_LENGTH)
                    .as("CDEMO-LAST-MAPSET PIC X(7) at COCOM01Y.cpy:44")
                    .isEqualTo(NavigationContext.LAST_MAPSET_LENGTH)
                    .isEqualTo(7);
        }

        @Test
        @DisplayName("this screen's own map and mapset fit the PIC X(7) carriers exactly")
        void theMapNamesFitTheSevenByteCarriers() {
            UserAddResponse response = blankResponse();
            assertThat(response.nextMap())
                    .isEqualTo(MAP)
                    .hasSize(UserAddResponse.NEXT_MAP_LENGTH);
            assertThat(response.nextMapset())
                    .isEqualTo(MAPSET)
                    .hasSize(UserAddResponse.NEXT_MAPSET_LENGTH);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> NavigationContext.empty().withLastMap(MAP + "X"))
                    .withMessageContaining(NavigationContext.LAST_MAP_FIELD);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> NavigationContext.empty().withLastMapset(MAPSET + "X"))
                    .withMessageContaining(NavigationContext.LAST_MAPSET_FIELD);
        }

        @ParameterizedTest(name = "[{index}] a {0} target resolves to {1}")
        @CsvSource({"SPACES,COSGN00C",
                    "LOW_VALUES,COSGN00C",
                    "PF3_TARGET,COADM01C",
                    "MENU_TARGET,COMEN01C"})
        @DisplayName("a blank target defaults to COSGN00C; a populated one is preserved")
        void theBlankTargetDefault(String form, String expected) {
            String target = switch (form) {
                case "SPACES" -> spaces(NavigationContext.TO_PROGRAM_LENGTH);
                case "LOW_VALUES" -> lowValues(NavigationContext.TO_PROGRAM_LENGTH);
                case "PF3_TARGET" -> ADMIN_MENU_PROGRAM;
                case "MENU_TARGET" -> "COMEN01C";
                default -> throw new IllegalArgumentException("Unhandled target form " + form);
            };
            String resolved = resolveTransferTarget(target);
            assertThat(resolved).isEqualTo(expected);

            UserAddResponse response = responseOf(blankMapValues(), NavigationContext.empty(),
                    resolved, MAPSET, MAP);
            assertThat(response.nextProgram())
                    .as("the payload carries the resolved target verbatim")
                    .isEqualTo(expected)
                    .hasSize(UserAddResponse.NEXT_PROGRAM_LENGTH);
            assertThat(deserialise(serialise(response)).nextProgram()).isEqualTo(expected);
        }

        @Test
        @DisplayName("the two blank forms are different bytes, which is why the source names both")
        void theTwoBlankFormsAreDistinct() {
            String blank = spaces(NavigationContext.TO_PROGRAM_LENGTH);
            String low = lowValues(NavigationContext.TO_PROGRAM_LENGTH);
            assertThat(blank).isNotEqualTo(low).hasSameSizeAs(low);
            assertThat(resolveTransferTarget(blank)).isEqualTo(SIGN_ON_PROGRAM);
            assertThat(resolveTransferTarget(low))
                    .as("an all-LOW-VALUES target takes the default just as an all-spaces one does")
                    .isEqualTo(SIGN_ON_PROGRAM);
            assertThat(resolveTransferTarget(ADMIN_MENU_PROGRAM))
                    .as("and a populated one is left exactly as the caller set it")
                    .isEqualTo(ADMIN_MENU_PROGRAM);
        }

        @Test
        @DisplayName("the outbound context carries CU01, COUSR01C and a zeroed program context")
        void theOutboundContextFields() {
            NavigationContext outbound = returnToPrevScreenContext(NavigationContext.empty()
                    .withToProgram(ADMIN_MENU_PROGRAM)
                    .withPgmReenter());
            assertThat(outbound.fromTranid())
                    .isEqualTo(WS_TRANID)
                    .hasSize(NavigationContext.FROM_TRANID_LENGTH);
            assertThat(outbound.fromProgram())
                    .isEqualTo(WS_PGMNAME)
                    .hasSize(NavigationContext.FROM_PROGRAM_LENGTH);
            assertThat(outbound.pgmContext())
                    .as("MOVE ZEROS leaves the next program to paint its screen for the first time")
                    .isEqualTo(NavigationContext.PGM_CONTEXT_ENTER)
                    .isZero();
            assertThat(outbound.isEnter()).isTrue();
            assertThat(outbound.isReenter()).isFalse();
            assertThat(outbound.toProgram())
                    .as("the target the caller set is untouched by these three moves")
                    .isEqualTo(ADMIN_MENU_PROGRAM);
        }

        @Test
        @DisplayName("CDEMO-USER-ID and CDEMO-USER-TYPE are NOT populated: lines 172-173 are commented out")
        void theUserIdentityIsNotPopulated() {
            NavigationContext outbound = returnToPrevScreenContext(NavigationContext.empty());
            assertThat(outbound.userId())
                    .as("nothing on this path writes an identifier into the commarea")
                    .isEqualTo(spaces(NavigationContext.USER_ID_LENGTH))
                    .isBlank();
            assertThat(outbound.userType())
                    .as("and nothing writes a user type either")
                    .isEqualTo(spaces(NavigationContext.USER_TYPE_LENGTH))
                    .isBlank();
            assertThat(outbound.isAdmin()).isFalse();
            assertThat(outbound.isUser()).isFalse();

            NavigationContext carried = returnToPrevScreenContext(NavigationContext.empty()
                    .withUserId("ADMIN001")
                    .withUserTypeAdmin());
            assertThat(carried.userId()).isEqualTo("ADMIN001");
            assertThat(carried.userType()).isEqualTo(NavigationContext.USER_TYPE_ADMIN);
            assertThat(carried.isAdmin()).isTrue();
            assertThat(carried.isUser()).isFalse();
        }

        @Test
        @DisplayName("the 160-byte commarea travels in the payload, so no session is needed")
        void theCommareaTravelsInThePayload() {
            assertThat(NavigationContext.GENERAL_INFO_LENGTH
                    + NavigationContext.CUSTOMER_INFO_LENGTH
                    + NavigationContext.ACCOUNT_INFO_LENGTH
                    + NavigationContext.CARD_INFO_LENGTH
                    + NavigationContext.MORE_INFO_LENGTH)
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH)
                    .isEqualTo(160);

            NavigationContext context = returnToPrevScreenContext(NavigationContext.empty()
                    .withToProgram(ADMIN_MENU_PROGRAM)
                    .withLastMap(MAP)
                    .withLastMapset(MAPSET));
            FixedWidthCodec codec = codec();
            byte[] image = context.toFixedWidth(codec);
            assertThat(image)
                    .as("and it is exactly 160 bytes on the wire, at the named code page (B8)")
                    .hasSize(NavigationContext.COMMAREA_LENGTH);
            assertThat(NavigationContext.fromFixedWidth(codec, image))
                    .as("byte-for-byte recoverable, which is what replacing a session requires")
                    .isEqualTo(context);

            UserAddResponse response = responseOf(blankMapValues(), context, ADMIN_MENU_PROGRAM,
                    MAPSET, MAP);
            assertThat(response.navigationContext()).isEqualTo(context);
            assertThat(deserialise(serialise(response)).navigationContext())
                    .as("and it survives the JSON round trip too")
                    .isEqualTo(context);
            assertThat(jsonKeys(response)).containsKey("navigationContext");
        }

        @Test
        @DisplayName("the ENTER and REENTER states are both reachable through the commarea")
        void bothProgramContextStatesAreDriven() {
            NavigationContext enter = NavigationContext.empty();
            assertThat(enter.pgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_ENTER).isZero();
            assertThat(enter.isEnter()).isTrue();
            assertThat(enter.isReenter()).isFalse();

            NavigationContext reenter = enter.withPgmReenter();
            assertThat(reenter.pgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_REENTER)
                    .isEqualTo(1);
            assertThat(reenter.isReenter()).isTrue();
            assertThat(reenter.isEnter()).isFalse();

            for (NavigationContext state : List.of(enter, reenter)) {
                UserAddResponse response = responseOf(blankMapValues(), state, SIGN_ON_PROGRAM,
                        MAPSET, MAP);
                assertThat(deserialise(serialise(response)).navigationContext().pgmContext())
                        .as("the context byte crosses the wire in both states")
                        .isEqualTo(state.pgmContext());
            }
            assertThat(componentNames())
                    .as("and there is no second flag beside the commarea")
                    .doesNotContain("pgmContext", "reenter", "enter", "pgmEnter", "pgmReenter");
        }

        @Test
        @DisplayName("the AID is an inbound concern: this response declares no aid member")
        void theAidTravelsOnTheRequestNotTheResponse() {
            assertThat(componentNames()).doesNotContain("aid", "aidToken", "eibAid");
            assertThat(UserAddRequest.AID_LENGTH)
                    .as("the inbound carrier is PIC X(5), matching the resolver's token width")
                    .isEqualTo(PfKeyResolver.AID_TOKEN_LENGTH)
                    .isEqualTo(5);
            for (PfKeyResolver.AidKey key : PfKeyResolver.AidKey.values()) {
                assertThat(key.token())
                        .as("%s renders as a five-character token", key)
                        .hasSize(PfKeyResolver.AID_TOKEN_LENGTH);
            }
            Optional<PfKeyResolver.AidKey> enter = PfKeyResolver.resolve(CicsAid.DFHENTER);
            Optional<PfKeyResolver.AidKey> pf3 = PfKeyResolver.resolve(CicsAid.DFHPF3);
            Optional<PfKeyResolver.AidKey> pf4 = PfKeyResolver.resolve(CicsAid.DFHPF4);
            assertThat(enter).contains(PfKeyResolver.AidKey.ENTER);
            assertThat(pf3).contains(PfKeyResolver.AidKey.PFK03);
            assertThat(pf4).contains(PfKeyResolver.AidKey.PFK04);
            assertThat(PfKeyResolver.isEnter(CicsAid.DFHENTER)).isTrue();
            assertThat(PfKeyResolver.isPf3(CicsAid.DFHPF3)).isTrue();
            assertThat(PfKeyResolver.isPf3(CicsAid.DFHENTER)).isFalse();
        }

        @Test
        @DisplayName("no CDEMO-CU0n-INFO extension block: this screen declares none")
        void noCommareaExtensionBlock() {
            assertThat(componentNames())
                    .hasSize(DFHMDF_NAMED + NAV_MEMBERS.size())
                    .doesNotContain("cu01Info", "cu00Info", "cu02Info", "cu03Info");
            for (String name : componentNames()) {
                assertThat(name.toLowerCase(Locale.ROOT))
                        .as("%s must not be a commarea extension block", name)
                        .doesNotContain("cu0");
            }
            assertThat(jsonKeys(blankResponse()).keySet())
                    .containsExactlyInAnyOrderElementsOf(emittedJsonMembers());
        }

        @Test
        @DisplayName("no session, cache or thread-bound storage is reachable from this type")
        void nothingHoldsServerSideState() {
            for (String name : reachableTypeNames()) {
                assertThat(name.toLowerCase(Locale.ROOT))
                        .as("%s must not be a session, cache or thread-local carrier", name)
                        .doesNotContain("session")
                        .doesNotContain("httpservlet")
                        .doesNotContain("threadlocal")
                        .doesNotContain("cache");
            }
            assertThat(UserAddResponse.class.isRecord())
                    .as("an immutable record cannot change underneath a caller that holds it")
                    .isTrue();
        }
    }

    private static String resolveTransferTarget(String cdemoToProgram) {
        boolean blank = cdemoToProgram.isEmpty()
                || cdemoToProgram.chars().allMatch(character -> character == ' ' || character == 0);
        return blank ? SIGN_ON_PROGRAM : cdemoToProgram;
    }

    private static NavigationContext returnToPrevScreenContext(NavigationContext inbound) {
        return inbound.withFromTranid(WS_TRANID)
                .withFromProgram(WS_PGMNAME)
                .withPgmContext(NavigationContext.PGM_CONTEXT_ENTER);
    }

    @Nested
    @DisplayName("JSON - sixteen properties, padding intact, metadata absent")
    class JsonRoundTrip {
        @Test
        @DisplayName("the mapper this suite uses is configured as config.WebConfig configures the shared one")
        void theMapperMirrorsTheApplicationPolicy() {
            ObjectMapper mapper = webConfigEquivalentMapper();
            assertThat(mapper.getDeserializationConfig()
                    .isEnabled(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS))
                    .isTrue();
            assertThat(mapper.getDeserializationConfig()
                    .isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS))
                    .isTrue();
            assertThat(mapper.getFactory().isEnabled(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN))
                    .isTrue();
            assertThat(mapper.getDeserializationConfig()
                    .isEnabled(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT))
                    .as("an all-space PIC X field must never arrive as null")
                    .isFalse();
            assertThat(mapper.getSerializationConfig().getPropertyNamingStrategy())
                    .as("no naming strategy: the property names are the member names")
                    .isNull();
        }

        @Test
        @DisplayName("the wire form names eleven of the twelve map members and the four navigation ones")
        void theWireFormNamesSixteenProperties() {
            Map<String, Object> properties = jsonKeys(sentScreen(MSG_DUPLICATE));
            assertThat(properties.keySet())
                    .containsExactlyInAnyOrderElementsOf(expectedJsonMembers())
                    .as("twelve map members less the withheld credential, plus the four carriers")
                    .hasSize(DFHMDF_NAMED - 1 + NAV_MEMBERS.size());
            assertThat(properties.keySet())
                    .as("the member names are carried through untransformed - no snake_case, no kebab")
                    .allSatisfy(key -> assertThat(key).doesNotContain("_").doesNotContain("-"));
        }

        @Test
        @DisplayName("a fully padded instance survives serialise then deserialise byte for byte")
        void aPaddedInstanceSurvivesTheRoundTrip() {
            List<String> values = blankMapValues();
            values = withMember(values, "trnName", WS_TRANID);
            values = withMember(values, "title01", ScreenTitles.CCDA_TITLE01);
            values = withMember(values, "curDate", "08/22/22");
            values = withMember(values, "pgmName", WS_PGMNAME);
            values = withMember(values, "title02", ScreenTitles.CCDA_TITLE02);
            values = withMember(values, "curTime", "17:02:44");
            values = withMember(values, "fName",
                    codec().movePicX("Johnathan", UserAddResponse.F_NAME_LENGTH));
            values = withMember(values, "lName",
                    codec().movePicX("Rutherford", UserAddResponse.L_NAME_LENGTH));
            values = withMember(values, "userId", "NEWUSR01");
            values = withMember(values, "usrType", NavigationContext.USER_TYPE_USER);
            values = withMember(values, "errMsg", narrowToErrMsg(composedSuccessMessage("NEWUSR01")));

            UserAddResponse original = responseOf(values,
                    returnToPrevScreenContext(NavigationContext.empty().withLastMap(MAP)),
                    SIGN_ON_PROGRAM, MAPSET, MAP);
            UserAddResponse restored = roundTrip(original);

            assertThat(restored).isEqualTo(original);
            assertThat(restored.hashCode()).isEqualTo(original.hashCode());
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                assertThat(mapValuesOf(restored).get(index))
                        .as("%s survives at its declared width", MAP_MEMBERS.get(index))
                        .isEqualTo(mapValuesOf(original).get(index));
            }
            assertThat(restored.fName())
                    .as("twenty characters, nine of them significant and eleven of them padding")
                    .hasSize(UserAddResponse.F_NAME_LENGTH)
                    .startsWith("Johnathan")
                    .endsWith(" ");
            assertThat(restored.passwd())
                    .as("and the blank password is still eight spaces")
                    .isEqualTo(spaces(UserAddResponse.PASSWD_LENGTH));
        }

        @Test
        @DisplayName("a null member is carried as null - except the unpublished credential, normalised")
        void aNullMemberIsCarriedAsNull() {
            UserAddResponse response = responseOf(Arrays.asList(null, null, null, null, null, null,
                    null, null, null, null, null, null), NavigationContext.empty(), null, null, null);
            assertThat(response.trnName()).isNull();
            assertThat(response.nextProgram()).isNull();
            assertThat(response.passwd())
                    .isNotNull()
                    .isEqualTo(spaces(UserAddResponse.PASSWD_LENGTH));

            UserAddResponse restored = deserialise(serialise(response));
            assertThat(restored).isEqualTo(response);
            assertThat(restored.errMsg())
                    .as("null is not coerced to an empty string on the way back either")
                    .isNull();
            assertThat(restored.passwd())
                    .as("and the normalised credential survives the trip unchanged")
                    .isEqualTo(spaces(UserAddResponse.PASSWD_LENGTH));
        }

        @Test
        @DisplayName("trailing content is refused, so a truncated or doubled body cannot pass silently")
        void trailingTokensAreRefused() {
            String doubled = serialise(blankResponse()) + serialise(blankResponse());
            assertThatExceptionOfType(UncheckedIOException.class)
                    .isThrownBy(() -> deserialise(doubled));
        }
    }

    @Nested
    @DisplayName("Security posture - plaintext by parity, and nothing stronger smuggled in")
    class SecurityPosture {
        @Test
        @DisplayName("passwd is a plaintext String at the width CSUSR01Y declares")
        void thePasswordIsAPlaintextStringOfEight() {
            RecordComponent[] components = UserAddResponse.class.getRecordComponents();
            RecordComponent component = components[MAP_MEMBERS.indexOf("passwd")];
            assertThat(component.getName()).isEqualTo("passwd");
            assertThat(component.getType())
                    .as("a String - not a char[], not a wrapper, not an encoded form")
                    .isEqualTo(String.class);
            assertThat(UserAddResponse.PASSWD_LENGTH).isEqualTo(SEC_USR_PWD_LENGTH);
        }

        @Test
        @DisplayName("no hashing, encoding, cipher or security-framework type is reachable")
        void noSecurityFrameworkTypeIsReachable() {
            for (String name : reachableTypeNames()) {
                for (String marker : CREDENTIAL_MARKERS) {
                    assertThat(name)
                            .as("%s suggests %s; strengthening the comparison would change observable "
                                    + "behaviour and is out of scope", name, marker)
                            .doesNotContain(marker);
                }
            }
        }

        @ParameterizedTest(name = "[{index}] {0} is absent from the classpath")
        @ValueSource(strings = {"org.springframework.security.crypto.password.PasswordEncoder",
                               "org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder",
                               "org.springframework.security.core.Authentication"})
        @DisplayName("Spring Security is not on the classpath at all, so it cannot be reached")
        void springSecurityIsNotOnTheClasspath(String type) {
            assertThat(EXCLUDED_SECURITY_TYPES).contains(type);
            assertThatExceptionOfType(ClassNotFoundException.class)
                    .isThrownBy(() -> Class.forName(type));
        }

        @Test
        @DisplayName("the record declares no field of its own beyond its components and constants")
        void noMutableStateIsDeclared() {
            for (Field field : UserAddResponse.class.getDeclaredFields()) {
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("%s must be final", field.getName())
                        .isTrue();
                if (!Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isPrivate(field.getModifiers()))
                            .as("the component field %s must be private final", field.getName())
                            .isTrue();
                }
            }
            for (Field field : UserAddResponseTest.class.getDeclaredFields()) {
                assertThat(Modifier.isStatic(field.getModifiers()) && Modifier.isFinal(
                        field.getModifiers()))
                        .as("%s in the test class must be static final", field.getName())
                        .isTrue();
            }
        }
    }

    @Nested
    @DisplayName("Diagnostics - the credential is withheld, the identifier is not")
    class Diagnostics {
        @Test
        @DisplayName("toString withholds the password unconditionally, value and length alike")
        void toStringWithholdsThePassword() {
            String keyed = "plaintxt";
            UserAddResponse response = responseOf(withMember(blankMapValues(), "passwd", keyed),
                    NavigationContext.empty(), SIGN_ON_PROGRAM, MAPSET, MAP);
            assertThat(response.toString())
                    .as("the redaction marker stands in for the value")
                    .contains(SensitiveDiagnostics.REDACTED)
                    .doesNotContain(keyed)
                    .doesNotContain(keyed.toUpperCase(Locale.ROOT));
            assertThat(response.passwd())
                    .as("while the payload itself still carries it, because parity requires that")
                    .isEqualTo(keyed);
        }

        @Test
        @DisplayName("the two names are described by length, and the identifier is shown in full")
        void toStringDescribesNamesAndShowsTheIdentifier() {
            String fName = codec().movePicX("Johnathan", UserAddResponse.F_NAME_LENGTH);
            UserAddResponse response = responseOf(
                    withMember(withMember(blankMapValues(), "fName", fName), "userId", "NEWUSR01"),
                    NavigationContext.empty(), SIGN_ON_PROGRAM, MAPSET, MAP);
            assertThat(response.toString())
                    .contains(SensitiveDiagnostics.describeText(fName))
                    .doesNotContain("Johnathan")
                    .contains("NEWUSR01")
                    .startsWith("UserAddResponse[");
        }

        @Test
        @DisplayName("a blank name is described as blank, not as an eight-character secret")
        void toStringDescribesABlankName() {
            UserAddResponse response = blankResponse();
            assertThat(response.toString())
                    .contains(SensitiveDiagnostics.describeText(
                            spaces(UserAddResponse.F_NAME_LENGTH)))
                    .contains(SensitiveDiagnostics.REDACTED);
            assertThat(SensitiveDiagnostics.describeText(spaces(UserAddResponse.F_NAME_LENGTH)))
                    .as("the blank marker carries no length, so it cannot leak one")
                    .doesNotContain(String.valueOf(UserAddResponse.F_NAME_LENGTH));
        }

        @Test
        @DisplayName("a null name is described as absent rather than rendered as \"null\" text")
        void toStringDescribesAnAbsentName() {
            UserAddResponse response = responseOf(withMember(blankMapValues(), "fName", null),
                    NavigationContext.empty(), SIGN_ON_PROGRAM, MAPSET, MAP);
            assertThat(response.toString())
                    .contains(SensitiveDiagnostics.describeText(null))
                    .contains(SensitiveDiagnostics.REDACTED);
        }
    }

    @Nested
    @DisplayName("Immutable copies - one member replaced, fifteen carried through")
    class ImmutableCopies {
        @Test
        @DisplayName("each of the twelve map members has a copy method that changes only itself")
        void eachMapMemberHasAnIsolatedCopyMethod() {
            UserAddResponse base = sentScreen(MSG_DUPLICATE);
            List<UserAddResponse> copies = List.of(base.withTrnName("CU99"),
                    base.withTitle01("changed title one"),
                    base.withCurDate("01/02/03"),
                    base.withPgmName("COUSR99C"),
                    base.withTitle02("changed title two"),
                    base.withCurTime("04:05:06"),
                    base.withFName("Changed"),
                    base.withLName("Altered"),
                    base.withUserId("OTHER001"),
                    base.withPasswd("changed1"),
                    base.withUsrType(NavigationContext.USER_TYPE_ADMIN),
                    base.withErrMsg(narrowToErrMsg(MSG_UNABLE_TO_ADD)));
            assertThat(copies).hasSize(DFHMDF_NAMED);
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                UserAddResponse copy = copies.get(index);
                assertThat(copy)
                        .as("%s must actually change", MAP_MEMBERS.get(index))
                        .isNotEqualTo(base);
                for (int other = 0; other < DFHMDF_NAMED; other++) {
                    if (other == index) {
                        assertThat(mapValuesOf(copy).get(other))
                                .as("%s is the member that changed", MAP_MEMBERS.get(other))
                                .isNotEqualTo(mapValuesOf(base).get(other));
                    } else {
                        assertThat(mapValuesOf(copy).get(other))
                                .as("%s must be carried through untouched", MAP_MEMBERS.get(other))
                                .isEqualTo(mapValuesOf(base).get(other));
                    }
                }
                assertThat(copy.navigationContext()).isEqualTo(base.navigationContext());
                assertThat(copy.nextProgram()).isEqualTo(base.nextProgram());
                assertThat(copy.nextMapset()).isEqualTo(base.nextMapset());
                assertThat(copy.nextMap()).isEqualTo(base.nextMap());
            }
        }

        @Test
        @DisplayName("the four navigation members have copy methods that leave the screen alone")
        void theNavigationCopyMethods() {
            UserAddResponse base = sentScreen(MSG_DUPLICATE);
            NavigationContext other = returnToPrevScreenContext(NavigationContext.empty()
                    .withToProgram(ADMIN_MENU_PROGRAM));

            assertThat(base.withNavigationContext(other).navigationContext()).isEqualTo(other);
            assertThat(base.withNextProgram(ADMIN_MENU_PROGRAM).nextProgram())
                    .isEqualTo(ADMIN_MENU_PROGRAM);
            assertThat(base.withNextMapset("COADM01").nextMapset()).isEqualTo("COADM01");
            assertThat(base.withNextMap("COADM1A").nextMap()).isEqualTo("COADM1A");

            for (UserAddResponse copy : List.of(base.withNavigationContext(other),
                    base.withNextProgram(ADMIN_MENU_PROGRAM),
                    base.withNextMapset("COADM01"),
                    base.withNextMap("COADM1A"))) {
                assertThat(mapValuesOf(copy))
                        .as("no navigation copy touches a screen field")
                        .isEqualTo(mapValuesOf(base));
            }
        }

        @Test
        @DisplayName("a copy that changes nothing equals the original, and equality is by value")
        void anIdempotentCopyEqualsTheOriginal() {
            UserAddResponse base = sentScreen(MSG_UNABLE_TO_ADD);
            assertThat(base.withTrnName(base.trnName()))
                    .isEqualTo(base)
                    .hasSameHashCodeAs(base);
            assertThat(base.withNavigationContext(base.navigationContext())).isEqualTo(base);
            assertThat(base)
                    .as("a record's equality is componentwise, so two independent builds match")
                    .isEqualTo(sentScreen(MSG_UNABLE_TO_ADD))
                    .isNotEqualTo(sentScreen(MSG_DUPLICATE))
                    .isNotEqualTo(null)
                    .isNotEqualTo("UserAddResponse");
        }

        @Test
        @DisplayName("there is one copy method per component, and no other public mutator")
        void thereIsOneCopyMethodPerComponent() {
            List<String> copyMethods = Arrays.stream(UserAddResponse.class.getDeclaredMethods())
                    .filter(method -> Modifier.isPublic(method.getModifiers()))
                    .map(Method::getName)
                    .filter(name -> name.startsWith("with"))
                    .sorted()
                    .toList();
            assertThat(copyMethods)
                    .as("sixteen components, sixteen copy methods")
                    .hasSize(DFHMDF_NAMED + NAV_MEMBERS.size());
            for (String component : componentNames()) {
                String expected = "with" + Character.toUpperCase(component.charAt(0))
                        + component.substring(1);
                assertThat(copyMethods)
                        .as("%s must have a copy method", component)
                        .contains(expected);
            }
            assertThat(Arrays.stream(UserAddResponse.class.getDeclaredMethods())
                    .map(Method::getName)
                    .filter(name -> name.startsWith("set"))
                    .toList())
                    .as("a fixed-width payload has no setter")
                    .isEmpty();
        }
    }
}
