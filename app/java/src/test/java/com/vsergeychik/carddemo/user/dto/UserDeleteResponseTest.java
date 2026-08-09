package com.vsergeychik.carddemo.user.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.common.NavigationContext;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Verifies {@link UserDeleteResponse} against the three read-only sources that define it:
 * {@code app/cpy-bms/COUSR03.CPY}, {@code app/bms/COUSR03.bms} and {@code app/cbl/COUSR03C.cbl}.
 *
 * <p>The load-bearing assertion in this class is the field count. The {@code xxxI} count, the
 * {@code xxxO} count and the name-labelled {@code DFHMDF} count are all <strong>eleven</strong>, and
 * the eleventh is {@code ERRMSG} rather than a password: {@code grep -n 'PASSWD'
 * app/cbl/COUSR03C.cbl} returns nothing at all. {@code COUSR02} carries twelve fields for exactly
 * that one reason, so a test that lets this payload drift to twelve would let the whole parity
 * argument drift with it.
 */
@DisplayName("UserDeleteResponse - COUSR03 symbolic map, CU03 / COUSR03C")
class UserDeleteResponseTest {

    /** The eleven name-labelled {@code DFHMDF} definitions of {@code app/bms/COUSR03.bms}, in map order. */
    private static final List<String> DFHMDF_LABELS = List.of("TRNNAME",
            "TITLE01",
            "CURDATE",
            "PGMNAME",
            "TITLE02",
            "CURTIME",
            "USRIDIN",
            "FNAME",
            "LNAME",
            "USRTYPE",
            "ERRMSG");

    /** The eleven {@code xxxO} items of {@code 01 COUSR3AO}, in map order. */
    private static final List<String> XXXO_ITEMS = List.of("TRNNAMEO",
            "TITLE01O",
            "CURDATEO",
            "PGMNAMEO",
            "TITLE02O",
            "CURTIMEO",
            "USRIDINO",
            "FNAMEO",
            "LNAMEO",
            "USRTYPEO",
            "ERRMSGO");

    /** The eleven Java members that project those items, in the same order. */
    private static final List<String> MAP_MEMBERS = List.of("trnName",
            "title01",
            "curDate",
            "pgmName",
            "title02",
            "curTime",
            "usrIdIn",
            "fName",
            "lName",
            "usrType",
            "errMsg");

    /** The four members carrying the stateless navigation contract, which G37 and G40 mandate. */
    private static final List<String> NAV_MEMBERS =
            List.of("navigationContext", "nextProgram", "nextMapset", "nextMap");

    /** Every spelling of a credential that must not appear as a member, accessor or JSON key. */
    private static final List<String> CREDENTIAL_TOKENS =
            List.of("passwd", "password", "pwd", "secret", "credential");

    /** The single-byte control items the symbolic map surrounds each {@code xxxO} with. */
    private static final List<String> CONTROL_SUFFIXES =
            List.of("L", "F", "A", "C", "P", "H", "V");

    private static List<String> componentNames() {
        return Arrays.stream(UserDeleteResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    private static UserDeleteResponse populated() {
        return UserDeleteResponse.empty()
                .withTrnName(UserDeleteResponse.TRANSACTION_ID)
                .withTitle01("AWS Mainframe Modernization")
                .withCurDate("08/22/22")
                .withPgmName(UserDeleteResponse.PROGRAM_NAME)
                .withTitle02("CardDemo")
                .withCurTime("17:02:44")
                .withUsrIdIn("USER0001")
                .withFName("John")
                .withLName("Doe")
                .withUsrType("U")
                .withErrMsg("Press PF5 key to delete this user ...")
                .withNextProgram("COADM01C")
                .withNextMapset(UserDeleteResponse.MAPSET_NAME)
                .withNextMap(UserDeleteResponse.MAP_NAME);
    }

    @Nested
    @DisplayName("Map projection - gate G9, every member traces to a DFHMDF")
    class MapProjection {

        @Test
        @DisplayName("there are exactly 11 map-derived members and 16 components in all")
        void componentCount() {
            assertThat(UserDeleteResponse.MAP_FIELD_COUNT).isEqualTo(11);
            assertThat(componentNames()).hasSize(16);
            assertThat(componentNames().subList(0, 11))
                    .containsExactlyElementsOf(MAP_MEMBERS);
            assertThat(componentNames().subList(11, 15))
                    .containsExactlyElementsOf(NAV_MEMBERS);
            // The sixteenth is the commarea extension COUSR03C declares for itself at :50-58; it is
            // conversation state, not a DFHMDF, so it sits outside the map-derived count.
            assertThat(componentNames().get(15)).isEqualTo("cu03Info");
        }

        @Test
        @DisplayName("the 11 members are declared in map order, one per name-labelled DFHMDF")
        void mapOrder() {
            assertThat(DFHMDF_LABELS).hasSize(UserDeleteResponse.MAP_FIELD_COUNT);
            assertThat(XXXO_ITEMS).hasSize(UserDeleteResponse.MAP_FIELD_COUNT);
            assertThat(MAP_MEMBERS).hasSize(UserDeleteResponse.MAP_FIELD_COUNT);
            for (int i = 0; i < DFHMDF_LABELS.size(); i++) {
                assertThat(XXXO_ITEMS.get(i)).isEqualTo(DFHMDF_LABELS.get(i) + "O");
            }
        }

        @Test
        @DisplayName("each published COBOL field name is its xxxO item")
        void cobolFieldNames() {
            assertThat(UserDeleteResponse.TRN_NAME_FIELD).isEqualTo("TRNNAMEO");
            assertThat(UserDeleteResponse.TITLE01_FIELD).isEqualTo("TITLE01O");
            assertThat(UserDeleteResponse.CUR_DATE_FIELD).isEqualTo("CURDATEO");
            assertThat(UserDeleteResponse.PGM_NAME_FIELD).isEqualTo("PGMNAMEO");
            assertThat(UserDeleteResponse.TITLE02_FIELD).isEqualTo("TITLE02O");
            assertThat(UserDeleteResponse.CUR_TIME_FIELD).isEqualTo("CURTIMEO");
            assertThat(UserDeleteResponse.USR_ID_IN_FIELD).isEqualTo("USRIDINO");
            assertThat(UserDeleteResponse.F_NAME_FIELD).isEqualTo("FNAMEO");
            assertThat(UserDeleteResponse.L_NAME_FIELD).isEqualTo("LNAMEO");
            assertThat(UserDeleteResponse.USR_TYPE_FIELD).isEqualTo("USRTYPEO");
            assertThat(UserDeleteResponse.ERR_MSG_FIELD).isEqualTo("ERRMSGO");
        }

        @Test
        @DisplayName("the 11 declared widths are 4, 40, 8, 8, 40, 8, 8, 20, 20, 1 and 78")
        void declaredWidths() {
            assertThat(UserDeleteResponse.TRN_NAME_LENGTH).isEqualTo(4);
            assertThat(UserDeleteResponse.TITLE01_LENGTH).isEqualTo(40);
            assertThat(UserDeleteResponse.CUR_DATE_LENGTH).isEqualTo(8);
            assertThat(UserDeleteResponse.PGM_NAME_LENGTH).isEqualTo(8);
            assertThat(UserDeleteResponse.TITLE02_LENGTH).isEqualTo(40);
            assertThat(UserDeleteResponse.CUR_TIME_LENGTH).isEqualTo(8);
            assertThat(UserDeleteResponse.USR_ID_IN_LENGTH).isEqualTo(8);
            assertThat(UserDeleteResponse.F_NAME_LENGTH).isEqualTo(20);
            assertThat(UserDeleteResponse.L_NAME_LENGTH).isEqualTo(20);
            assertThat(UserDeleteResponse.USR_TYPE_LENGTH).isEqualTo(1);
            assertThat(UserDeleteResponse.ERR_MSG_LENGTH).isEqualTo(78);
        }

        @Test
        @DisplayName("CURTIMEO is 8 wide, not the 9 that COSGN00 alone uses")
        void curTimeIsEight() {
            assertThat(UserDeleteResponse.CUR_TIME_LENGTH).isEqualTo(8).isNotEqualTo(9);
            assertThat(populated().curTime()).hasSize(UserDeleteResponse.CUR_TIME_LENGTH);
        }

        @Test
        @DisplayName("ERRMSGO is 78 wide while WS-MESSAGE is 80 - the COUSR03C:217 narrowing")
        void errMsgIsSeventyEight() {
            assertThat(UserDeleteResponse.ERR_MSG_LENGTH).isEqualTo(78).isNotEqualTo(80);
            assertThat(UserDeleteResponse.WS_MESSAGE_LENGTH).isEqualTo(80);
            assertThat(UserDeleteResponse.WS_MESSAGE_LENGTH - UserDeleteResponse.ERR_MSG_LENGTH)
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("the id member is named for USRIDIN, never for COUSR01's USERID")
        void idMemberIsUsrIdIn() {
            assertThat(componentNames()).contains("usrIdIn").doesNotContain("userId", "userid");
            assertThat(UserDeleteResponse.USR_ID_IN_FIELD).isEqualTo("USRIDINO")
                    .isNotEqualTo("USERIDO");
        }

        @Test
        @DisplayName("the screen identity literals are quoted from COUSR03C and the mapset")
        void screenIdentity() {
            assertThat(UserDeleteResponse.TRANSACTION_ID).isEqualTo("CU03")
                    .hasSize(UserDeleteResponse.TRN_NAME_LENGTH);
            assertThat(UserDeleteResponse.PROGRAM_NAME).isEqualTo("COUSR03C")
                    .hasSize(UserDeleteResponse.PGM_NAME_LENGTH);
            assertThat(UserDeleteResponse.MAP_NAME).isEqualTo("COUSR3A").hasSize(7);
            assertThat(UserDeleteResponse.MAPSET_NAME).isEqualTo("COUSR03").hasSize(7);
        }

        @Test
        @DisplayName("no control item - xxxL, xxxF, xxxA, xxxC, xxxP, xxxH, xxxV - is a member")
        void noControlItemIsAMember() {
            for (String label : DFHMDF_LABELS) {
                for (String suffix : CONTROL_SUFFIXES) {
                    String control = (label + suffix).toLowerCase(Locale.ROOT);
                    assertThat(componentNames()).noneMatch(n -> n.toLowerCase(Locale.ROOT)
                            .equals(control));
                }
            }
        }
    }

    @Nested
    @DisplayName("The absent password - 11 and not 12")
    class AbsentPassword {

        @Test
        @DisplayName("no component name spells a credential in any form")
        void noCredentialComponent() {
            for (String name : componentNames()) {
                String lower = name.toLowerCase(Locale.ROOT);
                assertThat(CREDENTIAL_TOKENS).noneMatch(lower::contains);
            }
        }

        @Test
        @DisplayName("no declared method spells a credential either")
        void noCredentialAccessor() {
            for (Method method : UserDeleteResponse.class.getDeclaredMethods()) {
                String lower = method.getName().toLowerCase(Locale.ROOT);
                assertThat(CREDENTIAL_TOKENS).noneMatch(lower::contains);
            }
        }

        @Test
        @DisplayName("the map-derived count stays at 11; a 12th would be a field with no DFHMDF")
        void countStaysEleven() {
            assertThat(UserDeleteResponse.MAP_FIELD_COUNT).isEqualTo(11);
            assertThat(componentNames()).hasSize(UserDeleteResponse.MAP_FIELD_COUNT
                    + NAV_MEMBERS.size() + 1);
        }
    }

    @Nested
    @DisplayName("Stateless navigation - gates G37 and G40")
    class Navigation {

        @Test
        @DisplayName("the navigation widths come from COCOM01Y: 8, 7 and 7")
        void navigationWidths() {
            assertThat(UserDeleteResponse.NEXT_PROGRAM_LENGTH)
                    .isEqualTo(8)
                    .isEqualTo(NavigationContext.TO_PROGRAM_LENGTH);
            assertThat(UserDeleteResponse.NEXT_MAPSET_LENGTH)
                    .isEqualTo(7)
                    .isEqualTo(NavigationContext.LAST_MAPSET_LENGTH);
            assertThat(UserDeleteResponse.NEXT_MAP_LENGTH)
                    .isEqualTo(7)
                    .isEqualTo(NavigationContext.LAST_MAP_LENGTH);
        }

        @Test
        @DisplayName("the mapset and map are X(7), so the literals fit exactly")
        void mapsetAndMapAreSeven() {
            assertThat(UserDeleteResponse.MAPSET_NAME)
                    .hasSize(UserDeleteResponse.NEXT_MAPSET_LENGTH);
            assertThat(UserDeleteResponse.MAP_NAME).hasSize(UserDeleteResponse.NEXT_MAP_LENGTH);
        }

        @Test
        @DisplayName("the communication area travels in the payload and is never null")
        void commareaTravelsInThePayload() {
            assertThat(populated().navigationContext()).isNotNull();
            assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(160);
        }

        @Test
        @DisplayName("the XCTL target is carried as a response field the client follows")
        void xctlBecomesAResponseField() {
            assertThat(populated().nextProgram()).isEqualTo("COADM01C");
            assertThat(populated().withNextProgram("COSGN00C").nextProgram()).isEqualTo("COSGN00C");
        }

        @Test
        @DisplayName("no member holds server-side session state")
        void noSessionState() {
            assertThat(componentNames()).noneMatch(n -> n.toLowerCase(Locale.ROOT)
                    .contains("session"));
            assertThat(componentNames()).noneMatch(n -> n.toLowerCase(Locale.ROOT)
                    .contains("token"));
        }
    }

    @Nested
    @DisplayName("Width enforcement - a shorter value fits, a longer one is refused")
    class WidthEnforcement {

        static Stream<Arguments> everyField() {
            return Stream.of(Arguments.of("TRNNAMEO", UserDeleteResponse.TRN_NAME_LENGTH,
                            (java.util.function.BiFunction<UserDeleteResponse, String,
                                    UserDeleteResponse>) UserDeleteResponse::withTrnName),
                    Arguments.of("TITLE01O", UserDeleteResponse.TITLE01_LENGTH,
                            (java.util.function.BiFunction<UserDeleteResponse, String,
                                    UserDeleteResponse>) UserDeleteResponse::withTitle01),
                    Arguments.of("CURDATEO", UserDeleteResponse.CUR_DATE_LENGTH,
                            (java.util.function.BiFunction<UserDeleteResponse, String,
                                    UserDeleteResponse>) UserDeleteResponse::withCurDate),
                    Arguments.of("PGMNAMEO", UserDeleteResponse.PGM_NAME_LENGTH,
                            (java.util.function.BiFunction<UserDeleteResponse, String,
                                    UserDeleteResponse>) UserDeleteResponse::withPgmName),
                    Arguments.of("TITLE02O", UserDeleteResponse.TITLE02_LENGTH,
                            (java.util.function.BiFunction<UserDeleteResponse, String,
                                    UserDeleteResponse>) UserDeleteResponse::withTitle02),
                    Arguments.of("CURTIMEO", UserDeleteResponse.CUR_TIME_LENGTH,
                            (java.util.function.BiFunction<UserDeleteResponse, String,
                                    UserDeleteResponse>) UserDeleteResponse::withCurTime),
                    Arguments.of("USRIDINO", UserDeleteResponse.USR_ID_IN_LENGTH,
                            (java.util.function.BiFunction<UserDeleteResponse, String,
                                    UserDeleteResponse>) UserDeleteResponse::withUsrIdIn),
                    Arguments.of("FNAMEO", UserDeleteResponse.F_NAME_LENGTH,
                            (java.util.function.BiFunction<UserDeleteResponse, String,
                                    UserDeleteResponse>) UserDeleteResponse::withFName),
                    Arguments.of("LNAMEO", UserDeleteResponse.L_NAME_LENGTH,
                            (java.util.function.BiFunction<UserDeleteResponse, String,
                                    UserDeleteResponse>) UserDeleteResponse::withLName),
                    Arguments.of("USRTYPEO", UserDeleteResponse.USR_TYPE_LENGTH,
                            (java.util.function.BiFunction<UserDeleteResponse, String,
                                    UserDeleteResponse>) UserDeleteResponse::withUsrType),
                    Arguments.of("ERRMSGO", UserDeleteResponse.ERR_MSG_LENGTH,
                            (java.util.function.BiFunction<UserDeleteResponse, String,
                                    UserDeleteResponse>) UserDeleteResponse::withErrMsg),
                    Arguments.of("CDEMO-TO-PROGRAM", UserDeleteResponse.NEXT_PROGRAM_LENGTH,
                            (java.util.function.BiFunction<UserDeleteResponse, String,
                                    UserDeleteResponse>) UserDeleteResponse::withNextProgram),
                    Arguments.of("CDEMO-LAST-MAPSET", UserDeleteResponse.NEXT_MAPSET_LENGTH,
                            (java.util.function.BiFunction<UserDeleteResponse, String,
                                    UserDeleteResponse>) UserDeleteResponse::withNextMapset),
                    Arguments.of("CDEMO-LAST-MAP", UserDeleteResponse.NEXT_MAP_LENGTH,
                            (java.util.function.BiFunction<UserDeleteResponse, String,
                                    UserDeleteResponse>) UserDeleteResponse::withNextMap));
        }

        @ParameterizedTest(name = "{0} PIC X({1}) accepts a value of its exact width")
        @MethodSource("everyField")
        @DisplayName("a value of exactly the declared width is accepted")
        void exactWidthAccepted(String cobolName,
                int width,
                java.util.function.BiFunction<UserDeleteResponse, String, UserDeleteResponse> with) {
            String value = "X".repeat(width);
            assertThat(with.apply(UserDeleteResponse.empty(), value)).isNotNull();
        }

        @ParameterizedTest(name = "{0} PIC X({1}) accepts a shorter value unchanged")
        @MethodSource("everyField")
        @DisplayName("a shorter value is accepted unchanged, as a MOVE into a wider receiver is")
        void shorterAccepted(String cobolName,
                int width,
                java.util.function.BiFunction<UserDeleteResponse, String, UserDeleteResponse> with) {
            assertThat(with.apply(UserDeleteResponse.empty(), "")).isNotNull();
        }

        @ParameterizedTest(name = "{0} PIC X({1}) refuses one character too many")
        @MethodSource("everyField")
        @DisplayName("an over-long value is refused rather than silently truncated")
        void overWidthRefused(String cobolName,
                int width,
                java.util.function.BiFunction<UserDeleteResponse, String, UserDeleteResponse> with) {
            String tooLong = "X".repeat(width + 1);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> with.apply(UserDeleteResponse.empty(), tooLong))
                    .withMessageContaining(cobolName)
                    .withMessageContaining("PIC X(" + width + ")")
                    .withMessageContaining("movePicX");
        }

        @ParameterizedTest(name = "{0} refuses null")
        @MethodSource("everyField")
        @DisplayName("null is refused; there is no null in a COBOL screen field")
        void nullRefused(String cobolName,
                int width,
                java.util.function.BiFunction<UserDeleteResponse, String, UserDeleteResponse> with) {
            assertThatNullPointerException()
                    .isThrownBy(() -> with.apply(UserDeleteResponse.empty(), null))
                    .withMessageContaining(cobolName);
        }

        @Test
        @DisplayName("a null communication area is refused, naming gate G37")
        void nullCommareaRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> UserDeleteResponse.empty().withNavigationContext(null))
                    .withMessageContaining("CARDDEMO-COMMAREA");
        }

        @Test
        @DisplayName("an 80-character message is refused because ERRMSGO holds 78")
        void wsMessageWidthIsRefused() {
            String eighty = "M".repeat(UserDeleteResponse.WS_MESSAGE_LENGTH);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> UserDeleteResponse.empty().withErrMsg(eighty))
                    .withMessageContaining("ERRMSGO")
                    .withMessageContaining("80 character(s)");
        }
    }

    @Nested
    @DisplayName("Factory and immutable copies")
    class FactoryAndCopies {

        @Test
        @DisplayName("empty() fills every field with spaces to its declared width")
        void emptyIsSpaceFilled() {
            UserDeleteResponse empty = UserDeleteResponse.empty();
            assertThat(empty.trnName()).isEqualTo(" ".repeat(4));
            assertThat(empty.title01()).isEqualTo(" ".repeat(40));
            assertThat(empty.curDate()).isEqualTo(" ".repeat(8));
            assertThat(empty.pgmName()).isEqualTo(" ".repeat(8));
            assertThat(empty.title02()).isEqualTo(" ".repeat(40));
            assertThat(empty.curTime()).isEqualTo(" ".repeat(8));
            assertThat(empty.usrIdIn()).isEqualTo(" ".repeat(8));
            assertThat(empty.fName()).isEqualTo(" ".repeat(20));
            assertThat(empty.lName()).isEqualTo(" ".repeat(20));
            assertThat(empty.usrType()).isEqualTo(" ");
            assertThat(empty.errMsg()).isEqualTo(" ".repeat(78));
            assertThat(empty.nextProgram()).isEqualTo(" ".repeat(8));
            assertThat(empty.nextMapset()).isEqualTo(" ".repeat(7));
            assertThat(empty.nextMap()).isEqualTo(" ".repeat(7));
            assertThat(empty.navigationContext()).isEqualTo(NavigationContext.empty());
        }

        @Test
        @DisplayName("empty() does not pre-populate the header; POPULATE-HEADER-INFO does that")
        void emptyDoesNotPrePopulateHeader() {
            assertThat(UserDeleteResponse.empty().trnName())
                    .isNotEqualTo(UserDeleteResponse.TRANSACTION_ID);
            assertThat(UserDeleteResponse.empty().pgmName())
                    .isNotEqualTo(UserDeleteResponse.PROGRAM_NAME);
        }

        @Test
        @DisplayName("each with* replaces only its own field and leaves the original untouched")
        void withReplacesOnlyItsOwnField() {
            UserDeleteResponse original = populated();
            UserDeleteResponse changed = original.withErrMsg("User ID NOT found...");

            assertThat(changed.errMsg()).isEqualTo("User ID NOT found...");
            assertThat(original.errMsg()).isEqualTo("Press PF5 key to delete this user ...");
            assertThat(changed.trnName()).isEqualTo(original.trnName());
            assertThat(changed.title01()).isEqualTo(original.title01());
            assertThat(changed.curDate()).isEqualTo(original.curDate());
            assertThat(changed.pgmName()).isEqualTo(original.pgmName());
            assertThat(changed.title02()).isEqualTo(original.title02());
            assertThat(changed.curTime()).isEqualTo(original.curTime());
            assertThat(changed.usrIdIn()).isEqualTo(original.usrIdIn());
            assertThat(changed.fName()).isEqualTo(original.fName());
            assertThat(changed.lName()).isEqualTo(original.lName());
            assertThat(changed.usrType()).isEqualTo(original.usrType());
            assertThat(changed.navigationContext()).isEqualTo(original.navigationContext());
            assertThat(changed.nextProgram()).isEqualTo(original.nextProgram());
            assertThat(changed.nextMapset()).isEqualTo(original.nextMapset());
            assertThat(changed.nextMap()).isEqualTo(original.nextMap());
        }

        @Test
        @DisplayName("the lookup results land in fName, lName and usrType, as COUSR03C:165-167 does")
        void lookupResultsLandOnTheScreen() {
            UserDeleteResponse afterLookup = UserDeleteResponse.empty()
                    .withFName("John")
                    .withLName("Doe")
                    .withUsrType("A");
            assertThat(afterLookup.fName()).isEqualTo("John");
            assertThat(afterLookup.lName()).isEqualTo("Doe");
            assertThat(afterLookup.usrType()).isEqualTo("A");
        }

        @Test
        @DisplayName("the communication area can be swapped wholesale")
        void navigationContextCanBeReplaced() {
            NavigationContext admin = NavigationContext.empty().withUserTypeAdmin();
            assertThat(populated().withNavigationContext(admin).navigationContext()).isEqualTo(admin);
        }

        @Test
        @DisplayName("equality is by value and toString names the type")
        void valueSemantics() {
            assertThat(populated()).isEqualTo(populated()).hasSameHashCodeAs(populated());
            assertThat(UserDeleteResponse.empty()).isNotEqualTo(populated());
            assertThat(populated().toString()).contains("UserDeleteResponse");
        }
    }

    @Nested
    @DisplayName("Serialisation - the JSON key set is exactly the declared components")
    class Serialisation {

        private final ObjectMapper mapper = new ObjectMapper();

        @Test
        @DisplayName("the JSON keys are the 16 components and nothing else")
        void keySetIsExactlyTheComponents() throws Exception {
            @SuppressWarnings("unchecked")
            Map<String, Object> json =
                    mapper.readValue(mapper.writeValueAsString(populated()), Map.class);
            assertThat(json.keySet()).containsExactlyInAnyOrderElementsOf(componentNames());
            assertThat(json).hasSize(16);
        }

        @Test
        @DisplayName("no control item and no credential appears as a JSON key")
        void noControlOrCredentialKey() throws Exception {
            @SuppressWarnings("unchecked")
            Map<String, Object> json =
                    mapper.readValue(mapper.writeValueAsString(populated()), Map.class);
            for (String key : json.keySet()) {
                String lower = key.toLowerCase(Locale.ROOT);
                assertThat(CREDENTIAL_TOKENS).noneMatch(lower::contains);
                for (String label : DFHMDF_LABELS) {
                    for (String suffix : CONTROL_SUFFIXES) {
                        assertThat(lower).isNotEqualTo((label + suffix).toLowerCase(Locale.ROOT));
                    }
                }
            }
        }

        @Test
        @DisplayName("member names are serialised untransformed - no rename, no snake_case")
        void namesAreUntransformed() throws Exception {
            String json = mapper.writeValueAsString(populated());
            assertThat(json).contains("\"usrIdIn\"", "\"errMsg\"", "\"nextMapset\"")
                    .doesNotContain("usr_id_in", "err_msg", "USRIDINO");
        }

        @Test
        @DisplayName("space padding survives a round trip - no trimming, no null coercion")
        void spacePaddingSurvives() throws Exception {
            UserDeleteResponse padded = UserDeleteResponse.empty().withUsrIdIn("USER1   ");
            UserDeleteResponse back = mapper.readValue(mapper.writeValueAsString(padded),
                    UserDeleteResponse.class);

            assertThat(back.usrIdIn()).isEqualTo("USER1   ").hasSize(8);
            assertThat(back.errMsg()).isEqualTo(" ".repeat(78)).hasSize(78);
            assertThat(back.title01()).hasSize(40);
            assertThat(back).isEqualTo(padded);
        }

        @Test
        @DisplayName("a fully populated payload round-trips unchanged")
        void populatedRoundTrips() throws Exception {
            UserDeleteResponse original = populated();
            assertThat(mapper.readValue(mapper.writeValueAsString(original),
                    UserDeleteResponse.class)).isEqualTo(original);
        }
    }
    @Nested
    @DisplayName("CDEMO-CU03-INFO on the way out - the extension the reply must carry back")
    class Cu03InfoOnTheReply {

        @Test
        @DisplayName("empty() carries the extension in its VALUE-clause state, never absent")
        void emptyCarriesTheInitialExtension() {
            assertThat(UserDeleteResponse.empty().cu03Info())
                    .isEqualTo(UserDeleteRequest.Cu03Info.initial());
        }

        @Test
        @DisplayName("an absent extension normalises to its VALUE-clause state: the bytes always exist")
        void anAbsentExtensionNormalises() {
            UserDeleteResponse response = new UserDeleteResponse(" ".repeat(4), " ".repeat(40),
                    " ".repeat(8), " ".repeat(8), " ".repeat(40), " ".repeat(8), " ".repeat(8),
                    " ".repeat(20), " ".repeat(20), " ", " ".repeat(78), NavigationContext.empty(),
                    " ".repeat(8), " ".repeat(7), " ".repeat(7), null);

            assertThat(response.cu03Info()).isEqualTo(UserDeleteRequest.Cu03Info.initial());
        }

        @Test
        @DisplayName("a supplied extension is carried through unchanged, byte for byte")
        void aSuppliedExtensionIsCarriedThrough() {
            UserDeleteRequest.Cu03Info paged = new UserDeleteRequest.Cu03Info("USER0001", "USER0010",
                    3, UserDeleteRequest.Cu03Info.NEXT_PAGE_YES, "S", "USER0004");

            UserDeleteResponse response = UserDeleteResponse.empty().withCu03Info(paged);

            assertThat(response.cu03Info()).isEqualTo(paged);
            assertThat(response.withCu03Info(null).cu03Info())
                    .isEqualTo(UserDeleteRequest.Cu03Info.initial());
        }
    }
}
