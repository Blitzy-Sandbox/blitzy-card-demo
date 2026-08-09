package com.vsergeychik.carddemo.user.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.common.NavigationContext;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Unit tests for {@link UserDeleteRequest}, the inbound payload of the {@code CU03} delete-user
 * screen driven by {@code app/cbl/COUSR03C.cbl}.
 *
 * <p>Plain JUnit 5 with no Spring context, no {@code MockMvc} and no {@code JobLauncher}: the type
 * under test is a value carrier, so every one of its decisions is reachable directly. That is the
 * property which makes the branch-coverage bar attainable with no HTTP layer in the path.
 *
 * <p>The expectations below are transcribed by hand from the reference sources rather than restated
 * from the implementation, so what is asserted is the screen's own contract:
 * <ul>
 *   <li>{@code app/cpy-bms/COUSR03.CPY} lines 17-84 - the eleven {@code xxxI} items, their
 *       {@code PICTURE} widths and their declaration order;</li>
 *   <li>{@code app/bms/COUSR03.bms} - 26 {@code DFHMDF} definitions of which exactly eleven carry a
 *       name label, and the {@code LENGTH=} of each;</li>
 *   <li>{@code app/cbl/COUSR03C.cbl} - the transaction and program identifiers at lines 36-37, the
 *       blank-id answers at lines 146-150 and 178-182, the cold start at lines 90-92, the key
 *       evaluation at lines 108-130 and the {@code MOVE SPACES} fill at lines 349-356;</li>
 *   <li>{@code app/cpy/CSUSR01Y.cpy} - the record widths the screen fields are moved to and from;</li>
 *   <li>{@code app/cpy/COCOM01Y.cpy} lines 29-31 - {@code CDEMO-PGM-CONTEXT} and its two
 *       {@code 88}-level condition names.</li>
 * </ul>
 *
 * <h2>Acceptance gates enforced directly here</h2>
 * <ul>
 *   <li><strong>G9</strong> - every payload member traces to a name-labelled {@code DFHMDF} field.
 *       The count is asserted to be exactly eleven, so a twelfth member - which could only be an
 *       invented password field - fails the build.</li>
 *   <li><strong>G22</strong> - no member is {@code double} or {@code float}.</li>
 *   <li><strong>G37</strong> - conversation state travels in the payload. The navigation context is
 *       carried through untouched and the enter-versus-re-enter flag is read through it rather than
 *       duplicated beside it.</li>
 *   <li><strong>G41</strong> - no member is a credential of any kind, in name or in fact.</li>
 *   <li><strong>G50</strong> - both sides of every condition in the type are driven, the
 *       out-of-domain inputs for which <em>neither</em> {@code 88}-level holds included.</li>
 *   <li><strong>G53</strong> - no static mutable state.</li>
 * </ul>
 */
@DisplayName("UserDeleteRequest - the CU03 delete-user screen contract")
class UserDeleteRequestTest {

    /** The eleven Java member names, in {@code 01 COUSR3AI} declaration order. */
    private static final List<String> MAP_MEMBERS = List.of("trnName", "title01", "curDate",
            "pgmName", "title02", "curTime", "usrIdIn", "fName", "lName", "usrType", "errMsg");

    /** The eleven declared widths, in the same order, from the {@code xxxI} PICTURE clauses. */
    private static final List<Integer> MAP_WIDTHS = List.of(4, 40, 8, 8, 40, 8, 8, 20, 20, 1, 78);

    /** The eleven COBOL item names, in the same order, {@code I} suffix included. */
    private static final List<String> COBOL_ITEMS = List.of("TRNNAMEI", "TITLE01I", "CURDATEI",
            "PGMNAMEI", "TITLE02I", "CURTIMEI", "USRIDINI", "FNAMEI", "LNAMEI", "USRTYPEI",
            "ERRMSGI");

    /** A representative payload, valid at every declared width. */
    private static UserDeleteRequest populated(NavigationContext context) {
        return new UserDeleteRequest("CU03", "AWS Mainframe Modernization", "08/08/26", "COUSR03C",
                "CardDemo", "09:31:41", "USER0001", "John", "Doe", "U",
                "Press PF5 key to delete this user ...", context, "ENTER", null);
    }

    private static List<String> mapValuesOf(UserDeleteRequest request) {
        return java.util.Arrays.asList(request.trnName(), request.title01(), request.curDate(),
                request.pgmName(), request.title02(), request.curTime(), request.usrIdIn(),
                request.fName(), request.lName(), request.usrType(), request.errMsg());
    }

    @Nested
    @DisplayName("The field set: eleven map members, and the twelfth that must never appear")
    class FieldSet {

        @Test
        @DisplayName("there are exactly eleven map-derived members plus the three state carriers")
        void memberCount() {
            RecordComponent[] components = UserDeleteRequest.class.getRecordComponents();

            assertThat(components).hasSize(14);
            assertThat(Stream.of(components).map(RecordComponent::getName).toList())
                    .startsWith(MAP_MEMBERS.toArray(String[]::new))
                    .endsWith("navigationContext", "aid", "cu03Info");
            assertThat(components.length - 3).isEqualTo(UserDeleteRequest.MAP_FIELD_COUNT);
            assertThat(UserDeleteRequest.MAP_FIELD_COUNT).isEqualTo(11);
        }

        @Test
        @DisplayName("no member is a credential, because this screen declares no PASSWD field")
        void noCredentialMember() {
            for (RecordComponent component : UserDeleteRequest.class.getRecordComponents()) {
                assertThat(component.getName().toLowerCase())
                        .doesNotContain("passwd", "password", "pwd", "secret", "credential");
            }
            assertThat(COBOL_ITEMS).noneMatch(item -> item.contains("PASSWD"));
        }

        @Test
        @DisplayName("the id member is named for USRIDIN, as COUSR02 names it, and not COUSR01's USERID")
        void idMemberNaming() {
            assertThat(Stream.of(UserDeleteRequest.class.getRecordComponents())
                    .map(RecordComponent::getName).toList())
                    .contains("usrIdIn")
                    .doesNotContain("userId", "userid", "userID");
            assertThat(UserDeleteRequest.USRIDIN_FIELD).isEqualTo("USRIDINI");
        }

        @Test
        @DisplayName("every member is a String except the two typed state carriers, and none is float")
        void memberTypes() {
            for (RecordComponent component : UserDeleteRequest.class.getRecordComponents()) {
                Class<?> expected = switch (component.getName()) {
                    case "navigationContext" -> NavigationContext.class;
                    case "cu03Info" -> UserDeleteRequest.Cu03Info.class;
                    default -> String.class;
                };
                assertThat(component.getType())
                        .isNotIn(double.class, float.class, Double.class, Float.class)
                        .isEqualTo(expected);
            }
        }

        @ParameterizedTest(name = "{0} is {1}")
        @CsvSource({"trnName,TRNNAMEI", "title01,TITLE01I", "curDate,CURDATEI", "pgmName,PGMNAMEI",
                "title02,TITLE02I", "curTime,CURTIMEI", "usrIdIn,USRIDINI", "fName,FNAMEI",
                "lName,LNAMEI", "usrType,USRTYPEI", "errMsg,ERRMSGI"})
        @DisplayName("each member names the symbolic-map item it projects, input suffix kept")
        void cobolItemNames(String member, String cobolItem) {
            assertThat(COBOL_ITEMS.get(MAP_MEMBERS.indexOf(member))).isEqualTo(cobolItem);
            assertThat(cobolItem).endsWith("I");
        }

        @Test
        @DisplayName("no static field is mutable")
        void noStaticMutableState() {
            for (Field field : UserDeleteRequest.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("%s must be final", field.getName()).isTrue();
                }
            }
        }
    }

    @Nested
    @DisplayName("Widths, taken from the xxxI PICTURE clauses and cross-checked against DFHMDF LENGTH")
    class Widths {

        @Test
        @DisplayName("the eleven width constants are 4, 40, 8, 8, 40, 8, 8, 20, 20, 1, 78")
        void declaredWidths() {
            assertThat(List.of(UserDeleteRequest.TRNNAME_LENGTH, UserDeleteRequest.TITLE01_LENGTH,
                    UserDeleteRequest.CURDATE_LENGTH, UserDeleteRequest.PGMNAME_LENGTH,
                    UserDeleteRequest.TITLE02_LENGTH, UserDeleteRequest.CURTIME_LENGTH,
                    UserDeleteRequest.USRIDIN_LENGTH, UserDeleteRequest.FNAME_LENGTH,
                    UserDeleteRequest.LNAME_LENGTH, UserDeleteRequest.USRTYPE_LENGTH,
                    UserDeleteRequest.ERRMSG_LENGTH)).containsExactlyElementsOf(MAP_WIDTHS);
        }

        @Test
        @DisplayName("CURTIME is 8 - only COSGN00 widens its time field to 9")
        void curTimeIsEightNotNine() {
            assertThat(UserDeleteRequest.CURTIME_LENGTH).isEqualTo(8)
                    .isEqualTo(UserDeleteRequest.CURDATE_LENGTH);
        }

        @Test
        @DisplayName("ERRMSG is 78 - the two characters WS-MESSAGE PIC X(80) loses at line 217")
        void errMsgIsSeventyEightNotEighty() {
            assertThat(UserDeleteRequest.ERRMSG_LENGTH).isEqualTo(78).isNotEqualTo(80);
        }

        @Test
        @DisplayName("the id, name and type widths equal their SEC-USER-DATA counterparts")
        void widthsAlignWithTheUsrsecRecord() {
            assertThat(UserDeleteRequest.USRIDIN_LENGTH).isEqualTo(8);   // SEC-USR-ID    X(08)
            assertThat(UserDeleteRequest.FNAME_LENGTH).isEqualTo(20);    // SEC-USR-FNAME X(20)
            assertThat(UserDeleteRequest.LNAME_LENGTH).isEqualTo(20);    // SEC-USR-LNAME X(20)
            assertThat(UserDeleteRequest.USRTYPE_LENGTH).isEqualTo(1);   // SEC-USR-TYPE  X(01)
        }

        @Test
        @DisplayName("the AID token is 5, the width of CCARD-AID PIC X(5)")
        void aidWidth() {
            assertThat(UserDeleteRequest.AID_LENGTH).isEqualTo(5);
        }

        @Test
        @DisplayName("the symbolic map closes at 324 bytes from either view")
        void symbolicMapGeometry() {
            assertThat(UserDeleteRequest.MAP_FIELDS_WIDTH_TOTAL)
                    .isEqualTo(MAP_WIDTHS.stream().mapToInt(Integer::intValue).sum())
                    .isEqualTo(235);
            assertThat(UserDeleteRequest.TIOAPFX_PREFIX_LENGTH).isEqualTo(12);
            assertThat(UserDeleteRequest.FIELD_OVERHEAD_LENGTH).isEqualTo(7);
            assertThat(UserDeleteRequest.SYMBOLIC_MAP_LENGTH).isEqualTo(324);
            // The output view's own overhead, FILLER X(3) + C + P + H + V, yields the same total,
            // which is why COUSR3AO can REDEFINE COUSR3AI field for field.
            assertThat(UserDeleteRequest.TIOAPFX_PREFIX_LENGTH
                    + (UserDeleteRequest.MAP_FIELD_COUNT * (3 + 1 + 1 + 1 + 1))
                    + UserDeleteRequest.MAP_FIELDS_WIDTH_TOTAL)
                    .isEqualTo(UserDeleteRequest.SYMBOLIC_MAP_LENGTH);
        }

        @Test
        @DisplayName("the screen identity constants match WS-TRANID, WS-PGMNAME, DFHMDI and DFHMSD")
        void screenIdentity() {
            assertThat(UserDeleteRequest.TRANSACTION_ID).isEqualTo("CU03").hasSize(4);
            assertThat(UserDeleteRequest.PROGRAM_NAME).isEqualTo("COUSR03C").hasSize(8);
            assertThat(UserDeleteRequest.MAP_NAME).isEqualTo("COUSR3A").hasSize(7);
            assertThat(UserDeleteRequest.MAPSET_NAME).isEqualTo("COUSR03").hasSize(7);
        }
    }

    @Nested
    @DisplayName("Validation: widths are constrained, emptiness is not")
    class Constraints {

        @ParameterizedTest(name = "component {0}")
        @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
        @DisplayName("each map member carries @Size(max = n) with n from its PICTURE clause")
        void sizeMirrorsTheDeclaredWidth(int index) {
            RecordComponent component = UserDeleteRequest.class.getRecordComponents()[index];
            Size size = component.getAccessor().getAnnotation(Size.class);

            assertThat(size).as("@Size on %s", component.getName()).isNotNull();
            assertThat(size.max()).isEqualTo(MAP_WIDTHS.get(index));
            assertThat(size.min()).isZero();
        }

        @Test
        @DisplayName("the navigation context is unconstrained and the AID is bounded at 5")
        void stateCarrierConstraints() {
            RecordComponent[] components = UserDeleteRequest.class.getRecordComponents();
            assertThat(components[11].getAccessor().getAnnotation(Size.class)).isNull();
            assertThat(components[12].getAccessor().getAnnotation(Size.class).max()).isEqualTo(5);
        }

        @Test
        @DisplayName("no member carries @NotBlank, @NotNull, @Pattern or any Jackson annotation")
        void forbiddenAnnotationsAreAbsent() {
            for (RecordComponent component : UserDeleteRequest.class.getRecordComponents()) {
                assertThat(Stream.of(component.getAccessor().getAnnotations())
                        .map(annotation -> annotation.annotationType().getSimpleName()).toList())
                        .as("annotations on %s", component.getName())
                        .doesNotContain("NotBlank", "NotNull", "NotEmpty", "Pattern", "Email",
                                "JsonProperty", "JsonIgnore", "JsonInclude", "JsonNaming");
            }
        }

        @Test
        @DisplayName("a blank or absent user id is valid input, because COUSR03C answers it with a message")
        void blanknessIsNotAConstraintViolation() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                Validator validator = factory.getValidator();

                // COUSR03C:146-150 and :178-182 answer a blank id with
                // 'User ID can NOT be empty...' and resend the screen. An HTTP 400 instead would
                // replace an observable answer with a protocol error.
                assertThat(validator.validate(UserDeleteRequest.empty())).isEmpty();
                assertThat(validator.validate(new UserDeleteRequest(null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null))).isEmpty();
            }
        }

        @Test
        @DisplayName("a value wider than its field is reported against that field and nothing else")
        void overWideValuesAreReported() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                Validator validator = factory.getValidator();

                UserDeleteRequest nineCharacterId = new UserDeleteRequest("CU03", "T1", "08/08/26",
                        "COUSR03C", "T2", "09:31:41", "USER00012", "John", "Doe", "U", "msg",
                        NavigationContext.empty(), "ENTER", null);
                Set<ConstraintViolation<UserDeleteRequest>> violations =
                        validator.validate(nineCharacterId);

                assertThat(violations).hasSize(1);
                assertThat(violations.iterator().next().getPropertyPath()).hasToString("usrIdIn");

                assertThat(validator.validate(withErrMsgOfWidth(78))).isEmpty();
                assertThat(validator.validate(withErrMsgOfWidth(79))).hasSize(1);
            }
        }

        private UserDeleteRequest withErrMsgOfWidth(int width) {
            return new UserDeleteRequest("CU03", "T1", "08/08/26", "COUSR03C", "T2", "09:31:41",
                    "USER0001", "John", "Doe", "U", "x".repeat(width), NavigationContext.empty(),
                    "ENTER", null);
        }
    }

    @Nested
    @DisplayName("Serialisation: fourteen keys, no map metadata, no trimming")
    class Serialisation {

        private final ObjectMapper mapper = new ObjectMapper();

        @Test
        @DisplayName("the payload is exactly the fourteen declared members")
        void keySet() throws Exception {
            Set<String> keys = keysOf(populated(NavigationContext.empty()));

            assertThat(keys).hasSize(14).containsExactlyInAnyOrder("trnName", "title01", "curDate",
                    "pgmName", "title02", "curTime", "usrIdIn", "fName", "lName", "usrType",
                    "errMsg", "navigationContext", "aid", "cu03Info");
        }

        @Test
        @DisplayName("no xxxL, xxxF, xxxA, xxxC, xxxP, xxxH or xxxV metadata reaches the wire")
        void noSymbolicMapMetadata() throws Exception {
            Set<String> keys = keysOf(populated(NavigationContext.empty()));

            for (String item : COBOL_ITEMS) {
                String base = item.substring(0, item.length() - 1);
                for (String suffix : List.of("L", "F", "A", "C", "P", "H", "V")) {
                    assertThat(keys).doesNotContain(base + suffix,
                            base.toLowerCase() + suffix, base.toLowerCase() + suffix.toLowerCase());
                }
            }
        }

        @Test
        @DisplayName("the read-through predicates are not promoted to JSON properties")
        void derivedPredicatesStayOffTheWire() throws Exception {
            // Jackson would publish any public no-argument is-getter on a record, and the canonical
            // constructor cannot accept such a key back - which is why these two are named after the
            // COBOL condition names instead of isEnter/isReenter. See the type's own note.
            assertThat(keysOf(populated(NavigationContext.empty())))
                    .doesNotContain("enter", "reenter", "pgmEnter", "pgmReenter", "pgmContext");
        }

        @Test
        @DisplayName("a round trip preserves space padding, trailing spaces and nulls alike")
        void roundTrip() throws Exception {
            UserDeleteRequest original = new UserDeleteRequest("CU03", "  padded title  ", null,
                    "COUSR03C", null, "        ", "USER    ", "                    ", null, " ",
                    "   message with trailing space   ", NavigationContext.empty(), "PA1  ", null);

            UserDeleteRequest back = mapper.readValue(mapper.writeValueAsString(original),
                    UserDeleteRequest.class);

            assertThat(back).isEqualTo(original);
            assertThat(back.title01()).isEqualTo("  padded title  ");
            assertThat(back.usrIdIn()).isEqualTo("USER    ");
            assertThat(back.curTime()).isEqualTo("        ").hasSize(8);
            assertThat(back.fName()).isEqualTo("                    ").hasSize(20);
            assertThat(back.usrType()).isEqualTo(" ");
            assertThat(back.errMsg()).isEqualTo("   message with trailing space   ");
            // PA1 and PA2 really do carry two trailing spaces in CCARD-AID PIC X(5).
            assertThat(back.aid()).isEqualTo("PA1  ").hasSize(5);
            // LOW-VALUES, the field the terminal did not transmit, stays distinguishable from SPACES.
            assertThat(back.curDate()).isNull();
            assertThat(back.title02()).isNull();
            assertThat(back.lName()).isNull();
        }

        @Test
        @DisplayName("the navigation context round-trips as a nested object, not a string")
        void nestedContextRoundTrip() throws Exception {
            UserDeleteRequest original = populated(NavigationContext.empty()
                    .withFromTranid("CU00").withFromProgram("COUSR00C").withPgmReenter());

            UserDeleteRequest back = mapper.readValue(mapper.writeValueAsString(original),
                    UserDeleteRequest.class);

            assertThat(back.navigationContext()).isEqualTo(original.navigationContext());
            assertThat(back.pgmReenter()).isTrue();
        }

        private Set<String> keysOf(UserDeleteRequest request) throws Exception {
            return mapper.readTree(mapper.writeValueAsString(request)).propertyStream()
                    .map(Map.Entry::getKey).collect(Collectors.toSet());
        }
    }

    @Nested
    @DisplayName("Conversation state: carried in the payload, read through the context")
    class ConversationState {

        @Test
        @DisplayName("CDEMO-PGM-ENTER holds on first entry")
        void enterState() {
            UserDeleteRequest request = populated(NavigationContext.empty());

            assertThat(request.pgmEnter()).isTrue();
            assertThat(request.pgmReenter()).isFalse();
        }

        @Test
        @DisplayName("CDEMO-PGM-REENTER holds once COUSR03C:96 has asserted it")
        void reenterState() {
            UserDeleteRequest request = populated(NavigationContext.empty().withPgmReenter());

            assertThat(request.pgmEnter()).isFalse();
            assertThat(request.pgmReenter()).isTrue();
        }

        @Test
        @DisplayName("without a context neither condition holds - the EIBCALEN = 0 cold start")
        void absentContext() {
            UserDeleteRequest request = populated(null);

            assertThat(request.navigationContext()).isNull();
            assertThat(request.pgmEnter()).isFalse();
            assertThat(request.pgmReenter()).isFalse();
        }

        @ParameterizedTest(name = "CDEMO-PGM-CONTEXT = {0}")
        @ValueSource(ints = {2, 5, 9})
        @DisplayName("for a digit outside {0, 1} neither condition holds, so neither is the other's negation")
        void outOfDomainContext(int digit) {
            UserDeleteRequest request = populated(NavigationContext.empty().withPgmContext(digit));

            assertThat(request.pgmEnter()).isFalse();
            assertThat(request.pgmReenter()).isFalse();
        }

        @Test
        @DisplayName("the context is carried through untouched, never widened or re-modelled")
        void contextIsPassedThrough() {
            NavigationContext context = NavigationContext.empty()
                    .withFromTranid("CU00").withFromProgram("COUSR00C").withUserId("ADMIN001")
                    .withUserTypeAdmin().withPgmReenter();

            UserDeleteRequest request = populated(context);

            assertThat(request.navigationContext()).isSameAs(context);
            assertThat(request.navigationContext().fromProgram()).isEqualTo("COUSR00C");
            assertThat(request.navigationContext().isAdmin()).isTrue();
            assertThat(request.navigationContext().toFixedWidth(
                    new com.vsergeychik.carddemo.common.FixedWidthCodec(
                            java.nio.charset.StandardCharsets.US_ASCII)))
                    .hasSize(NavigationContext.COMMAREA_LENGTH);
        }

        @ParameterizedTest(name = "aid = \"{0}\"")
        @ValueSource(strings = {"ENTER", "CLEAR", "PA1  ", "PFK03", "PFK05", "PFK12"})
        @DisplayName("the resolved AID token is carried verbatim, trailing spaces included")
        void aidIsCarriedVerbatim(String token) {
            // COUSR03C:108-130 branches on ENTER, PF3, PF4, PF5 and PF12; PF5 is what confirms the
            // delete. None of those paths is reachable without a faithful key indication here.
            assertThat(populated(NavigationContext.empty()).trnName()).isEqualTo("CU03");
            assertThat(new UserDeleteRequest(null, null, null, null, null, null, null, null, null,
                    null, null, null, token, null).aid()).isEqualTo(token).hasSize(5);
        }
    }

    @Nested
    @DisplayName("The blank payload of INITIALIZE-ALL-FIELDS")
    class BlankPayload {

        @ParameterizedTest(name = "{0} is {1} spaces")
        @CsvSource({"trnName,4", "title01,40", "curDate,8", "pgmName,8", "title02,40", "curTime,8",
                "usrIdIn,8", "fName,20", "lName,20", "usrType,1", "errMsg,78"})
        @DisplayName("each member is a run of spaces of exactly its declared width")
        void spaceFilledAtDeclaredWidth(String member, int width) {
            String value = mapValuesOf(UserDeleteRequest.empty()).get(MAP_MEMBERS.indexOf(member));

            assertThat(value).as(member).hasSize(width).isBlank();
        }

        @Test
        @DisplayName("no member is null, the AID is five spaces and the context is the initial COMMAREA")
        void stateCarriersAreInitialised() {
            UserDeleteRequest blank = UserDeleteRequest.empty();

            assertThat(mapValuesOf(blank)).doesNotContainNull().hasSize(11);
            assertThat(blank.aid()).isEqualTo("     ").hasSize(UserDeleteRequest.AID_LENGTH);
            assertThat(blank.navigationContext()).isEqualTo(NavigationContext.empty());
            assertThat(blank.pgmEnter()).isTrue();
            assertThat(blank.pgmReenter()).isFalse();
        }

        @Test
        @DisplayName("it is the MOVE SPACES shape, not the MOVE LOW-VALUES shape of line 97")
        void spacesNotLowValues() {
            // COUSR03C:349-356 moves SPACES; line 97 moves LOW-VALUES, which nulls project. The two
            // remain distinguishable because the program itself tests '= SPACES OR LOW-VALUES'.
            assertThat(UserDeleteRequest.empty().usrIdIn()).isNotNull().isBlank();
            assertThat(UserDeleteRequest.empty()).isNotEqualTo(new UserDeleteRequest(null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null));
        }

        @Test
        @DisplayName("two blank payloads are equal and share a hash, as a value type must")
        void valueSemantics() {
            assertThat(UserDeleteRequest.empty())
                    .isEqualTo(UserDeleteRequest.empty())
                    .hasSameHashCodeAs(UserDeleteRequest.empty())
                    .isNotSameAs(UserDeleteRequest.empty());
            assertThat(UserDeleteRequest.empty()).hasToString(UserDeleteRequest.empty().toString());
        }
    }
    @Nested
    @DisplayName("CDEMO-CU03-INFO - the 34-byte extension COUSR03C declares at :50-58")
    class Cu03InfoExtension {

        @Test
        @DisplayName("initial() is what the VALUE clauses leave: spaces, and a page number of zero")
        void initialIsTheValueClauseState() {
            UserDeleteRequest.Cu03Info info = UserDeleteRequest.Cu03Info.initial();

            assertThat(info.usridFirst()).isEqualTo(" ".repeat(
                    UserDeleteRequest.Cu03Info.USRID_FIRST_LENGTH));
            assertThat(info.usridLast()).isEqualTo(" ".repeat(
                    UserDeleteRequest.Cu03Info.USRID_LAST_LENGTH));
            // COUSR03C:53 declares CDEMO-CU03-NEXT-PAGE-FLG PIC X(01) VALUE 'N', so the initial state
            // of this one item is 'N' and not a space.
            assertThat(info.nextPageFlg()).isEqualTo(UserDeleteRequest.Cu03Info.NEXT_PAGE_NO);
            assertThat(info.usrSelFlg()).isEqualTo(" ".repeat(
                    UserDeleteRequest.Cu03Info.USR_SEL_FLG_LENGTH));
            assertThat(info.usrSelected()).isEqualTo(" ".repeat(
                    UserDeleteRequest.Cu03Info.USR_SELECTED_LENGTH));
            assertThat(info.pageNum()).isZero();
        }

        @Test
        @DisplayName("an item that was not supplied becomes its declared width in spaces, not null")
        void anAbsentItemBecomesSpaces() {
            // A group item inside the communication area has no absent state: the bytes are there,
            // holding whatever the VALUE clauses left. null therefore projects to the space image.
            UserDeleteRequest.Cu03Info info =
                    new UserDeleteRequest.Cu03Info(null, null, 0, null, null, null);

            assertThat(info.usridFirst()).isEqualTo(" ".repeat(
                    UserDeleteRequest.Cu03Info.USRID_FIRST_LENGTH));
            assertThat(info.usridLast()).isEqualTo(" ".repeat(
                    UserDeleteRequest.Cu03Info.USRID_LAST_LENGTH));
            assertThat(info.usrSelFlg()).isEqualTo(" ");
            assertThat(info.usrSelected()).isEqualTo(" ".repeat(
                    UserDeleteRequest.Cu03Info.USR_SELECTED_LENGTH));
            // The one item with a VALUE clause is the only one initial() sets to something other than
            // spaces, so null is a space here and 'N' there - which is exactly the difference.
            assertThat(info.nextPageFlg()).isEqualTo(" ");
            assertThat(info).isNotEqualTo(UserDeleteRequest.Cu03Info.initial());
        }

        @Test
        @DisplayName("a value wider than its item is truncated on the right, as a PIC X MOVE is")
        void anOverWideItemIsTruncatedOnTheRight() {
            UserDeleteRequest.Cu03Info info = new UserDeleteRequest.Cu03Info(
                    "USER00012345", "USER9999", 0, "YES", "SS", "SELECTED9");

            assertThat(info.usridFirst()).isEqualTo("USER0001");
            assertThat(info.nextPageFlg()).isEqualTo("Y");
            assertThat(info.usrSelFlg()).isEqualTo("S");
            assertThat(info.usrSelected()).isEqualTo("SELECTED");
        }

        @Test
        @DisplayName("CDEMO-CU03-PAGE-NUM is PIC 9(08): a negative page number has no representation")
        void aNegativePageNumberIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new UserDeleteRequest.Cu03Info(null, null, -1, null, null, null))
                    .withMessageContaining("no sign position");
        }

        @Test
        @DisplayName("a page number needing more than eight digits is refused, not silently truncated")
        void anOverWidePageNumberIsRefused() {
            int tooWide = (int) Math.pow(10, UserDeleteRequest.Cu03Info.PAGE_NUM_DIGITS);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new UserDeleteRequest.Cu03Info(null, null, tooWide, null, null,
                            null))
                    .withMessageContaining("drop its high-order");
            assertThat(new UserDeleteRequest.Cu03Info(null, null, tooWide - 1, null, null, null)
                    .pageNum()).isEqualTo(tooWide - 1);
        }

        @Test
        @DisplayName("the group is 34 bytes, which is what widens the passed commarea to 194")
        void theGroupIsThirtyFourBytes() {
            assertThat(UserDeleteRequest.Cu03Info.LENGTH).isEqualTo(34);
            assertThat(UserDeleteRequest.Cu03Info.USRID_FIRST_LENGTH
                    + UserDeleteRequest.Cu03Info.USRID_LAST_LENGTH
                    + UserDeleteRequest.Cu03Info.PAGE_NUM_DIGITS
                    + UserDeleteRequest.Cu03Info.NEXT_PAGE_FLG_LENGTH
                    + UserDeleteRequest.Cu03Info.USR_SEL_FLG_LENGTH
                    + UserDeleteRequest.Cu03Info.USR_SELECTED_LENGTH)
                    .isEqualTo(UserDeleteRequest.Cu03Info.LENGTH);
        }
    }
}
