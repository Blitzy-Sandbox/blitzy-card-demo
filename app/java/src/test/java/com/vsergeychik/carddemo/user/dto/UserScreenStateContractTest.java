package com.vsergeychik.carddemo.user.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.user.dto.UserListRequest.UserListRow;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The three properties the {@code CU00}, {@code CC00} and {@code CU02} payloads have to hold on the wire:
 * an absent communication area stays absent, the ten screen rows travel under their own numbered names, and
 * a rejected value never appears in a diagnostic.
 */
@DisplayName("user/dto - cold-start absence, the fifty numbered row members, and redacted failures")
class UserScreenStateContractTest {
    private final ObjectMapper mapper =
            new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Nested
    @DisplayName("SignOnRequest - EIBCALEN = 0 is representable")
    class SignOnColdStart {
        private SignOnRequest coldStart() {
            return withContext(null);
        }

        private SignOnRequest withContext(NavigationContext context) {
            return new SignOnRequest("CC00", "t1", "07/18/22", "COSGN00C", "t2", "12:00:00",
                    "CICSAWS", "AWS1", "ADMIN001", "PASSWORD", "", context, "ENTER");
        }

        @Test
        @DisplayName("an absent communication area is carried as absent, not completed")
        void absenceIsPreserved() {
            SignOnRequest request = coldStart();

            assertThat(request.navigationContext()).isNull();
            assertThat(request.hasNavigationContext()).isFalse();
            assertThat(request.commareaLength()).isZero();
        }

        @Test
        @DisplayName("with no communication area, neither ENTER nor REENTER holds")
        void neitherStateHoldsOnAColdStart() {
            SignOnRequest request = coldStart();

            assertThat(request.inEnterState()).isFalse();
            assertThat(request.inReenterState()).isFalse();
        }

        @Test
        @DisplayName("a present area reports EIBCALEN 160 and its own context state")
        void aPresentAreaReportsItsState() {
            SignOnRequest enter = withContext(NavigationContext.empty());
            SignOnRequest reenter = withContext(NavigationContext.empty().withPgmReenter());

            assertThat(enter.hasNavigationContext()).isTrue();
            assertThat(enter.commareaLength()).isEqualTo(NavigationContext.COMMAREA_LENGTH);
            assertThat(enter.inEnterState()).isTrue();
            assertThat(enter.inReenterState()).isFalse();
            assertThat(reenter.inReenterState()).isTrue();
            assertThat(reenter.inEnterState()).isFalse();
        }

        @Test
        @DisplayName("a context holding neither 0 nor 1 satisfies neither predicate")
        void anUnknownContextDigitSatisfiesNeither() {
            SignOnRequest request = withContext(NavigationContext.empty().withPgmContext(9));

            assertThat(request.inEnterState()).isFalse();
            assertThat(request.inReenterState()).isFalse();
            assertThat(request.hasNavigationContext()).isTrue();
        }

        @Test
        @DisplayName("the absence survives a JSON round trip in both directions")
        void absenceSurvivesJson() throws Exception {
            String json = mapper.writeValueAsString(coldStart());

            assertThat(mapper.readTree(json).get("navigationContext").isNull()).isTrue();
            assertThat(mapper.readValue(json, SignOnRequest.class).hasNavigationContext()).isFalse();
        }

        @Test
        @DisplayName("an omitted member also reads as absent")
        void anOmittedMemberReadsAsAbsent() throws Exception {
            SignOnRequest bound =
                    mapper.readValue("{\"userid\":\"ADMIN001\"}", SignOnRequest.class);

            assertThat(bound.hasNavigationContext()).isFalse();
            assertThat(bound.commareaLength()).isZero();
        }

        @Test
        @DisplayName("the password is still withheld from the diagnostic rendering")
        void thePasswordIsStillWithheld() {
            assertThat(coldStart().toString()).doesNotContain("PASSWORD");
            assertThat(coldStart().passwd()).isEqualTo("PASSWORD");
        }
    }

    @Nested
    @DisplayName("UserListRequest - the fifty numbered COUSR00 row members")
    class UserListRowNaming {
        private UserListRequest populated() {
            return UserListRequest.empty()
                    .withRow(1, new UserListRow("U", "ADMIN001", "JOHN", "PUBLIC", "A"))
                    .withRow(10, new UserListRow("D", "USER0010", "JANE", "DOE", "U"))
                    .withUsrIdIn("USER0001");
        }

        private Set<String> propertyNames(Object payload) throws Exception {
            Set<String> names = new TreeSet<>();
            mapper.readTree(mapper.writeValueAsString(payload)).fieldNames()
                    .forEachRemaining(names::add);
            return names;
        }

        @Test
        @DisplayName("all fifty numbered names appear, and the generic rows array does not")
        void theFiftyNumberedNamesAreOnTheWire() throws Exception {
            Set<String> names = propertyNames(populated());

            assertThat(names).doesNotContain("rows");
            for (int row = 1; row <= 10; row++) {
                assertThat(names).contains(String.format("sel%04d", row),
                        String.format("usrid%02d", row),
                        String.format("fname%02d", row),
                        String.format("lname%02d", row),
                        String.format("utype%02d", row));
            }
        }

        @Test
        @DisplayName("the members are emitted in copybook declaration order")
        void theOrderIsTheCopybookOrder() throws Exception {
            List<String> emitted = new ArrayList<>();
            Iterator<String> names =
                    mapper.readTree(mapper.writeValueAsString(populated())).fieldNames();
            names.forEachRemaining(emitted::add);

            List<String> expected = new ArrayList<>(List.of("trnname", "title01", "curdate",
                    "pgmname", "title02", "curtime", "pagenum", "usridin"));
            for (int row = 1; row <= 10; row++) {
                expected.add(String.format("sel%04d", row));
                expected.add(String.format("usrid%02d", row));
                expected.add(String.format("fname%02d", row));
                expected.add(String.format("lname%02d", row));
                expected.add(String.format("utype%02d", row));
            }
            expected.addAll(List.of("errmsg", "cdemoCu00UsrIdFirst", "cdemoCu00UsrIdLast",
                    "cdemoCu00PageNum", "cdemoCu00NextPageFlg", "cdemoCu00UsrSelFlg",
                    "cdemoCu00UsrSelected", "navigationContext", "aid"));

            assertThat(emitted).containsExactlyElementsOf(expected);
        }

        @Test
        @DisplayName("each numbered accessor reads its own row")
        void eachAccessorReadsItsOwnRow() {
            UserListRequest request = populated();

            assertThat(request.sel0001()).isEqualTo("U");
            assertThat(request.usrId01()).isEqualTo("ADMIN001");
            assertThat(request.fname01()).isEqualTo("JOHN");
            assertThat(request.lname01()).isEqualTo("PUBLIC");
            assertThat(request.utype01()).isEqualTo("A");
            assertThat(request.sel0010()).isEqualTo("D");
            assertThat(request.usrId10()).isEqualTo("USER0010");
            assertThat(request.fname10()).isEqualTo("JANE");
            assertThat(request.lname10()).isEqualTo("DOE");
            assertThat(request.utype10()).isEqualTo("U");
        }

        @Test
        @DisplayName("the numbered members bind back into the row table")
        void theNumberedMembersBindBack() throws Exception {
            UserListRequest bound = mapper.readValue(
                    "{\"sel0002\":\"U\",\"usrid02\":\"USER0002\",\"fname02\":\"AL\","
                            + "\"lname02\":\"BE\",\"utype02\":\"U\"}",
                    UserListRequest.class);

            assertThat(bound.row(2).sel()).isEqualTo("U");
            assertThat(bound.row(2).usrId()).isEqualTo("USER0002");
            assertThat(bound.row(2).fname()).isEqualTo("AL");
            assertThat(bound.rows()).hasSize(UserListRequest.ROW_COUNT);
        }

        @Test
        @DisplayName("a round trip preserves every row and every carried member")
        void roundTripIsExact() throws Exception {
            UserListRequest before = populated()
                    .withCdemoCu00UsrIdFirst("USER0001")
                    .withCdemoCu00PageNum(3)
                    .withNavigationContext(NavigationContext.empty().withUserTypeAdmin());

            UserListRequest after =
                    mapper.readValue(mapper.writeValueAsString(before), UserListRequest.class);

            assertThat(after).isEqualTo(before);
        }

        @Test
        @DisplayName("a property tracing to no DFHMDF field is still refused")
        void anUnknownPropertyIsRefused() {
            assertThatThrownBy(() -> mapper.readValue("{\"sel0011\":\"U\"}", UserListRequest.class))
                    .hasMessageContaining("sel0011");
        }

        @ParameterizedTest(name = "{0} is refused when it exceeds its declared width")
        @ValueSource(strings = {"usrid01", "fname01", "lname01"})
        @DisplayName("an over-wide row member is refused rather than truncated")
        void anOverWideRowMemberIsRefused(String member) {
            String tooLong = "X".repeat(40);

            assertThatThrownBy(() -> mapper.readValue(
                    "{\"" + member + "\":\"" + tooLong + "\"}", UserListRequest.class))
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("every name the response emits is a name the request accepts")
        void thePairRoundTripsWithoutReshaping() throws Exception {
            Set<String> responseNames = propertyNames(UserListResponse.blank());
            Set<String> requestNames = propertyNames(UserListRequest.empty());

            Set<String> responseOnly = new TreeSet<>(responseNames);
            responseOnly.removeAll(requestNames);

            assertThat(responseOnly).containsExactly("nextMap", "nextMapset", "nextProgram");

            assertThat(requestNames).contains("cdemoCu00UsrIdFirst", "cdemoCu00UsrIdLast",
                    "cdemoCu00PageNum", "cdemoCu00NextPageFlg", "cdemoCu00UsrSelFlg",
                    "cdemoCu00UsrSelected");
            assertThat(responseNames).containsAll(List.of("cdemoCu00UsrIdFirst",
                    "cdemoCu00UsrIdLast", "cdemoCu00PageNum", "cdemoCu00NextPageFlg",
                    "cdemoCu00UsrSelFlg", "cdemoCu00UsrSelected"));
        }

        @Test
        @DisplayName("the CU00 cold start is representable, and reports EIBCALEN 0")
        void theColdStartIsRepresentable() throws Exception {
            UserListRequest cold = UserListRequest.empty().withoutNavigationContext();

            assertThat(cold.hasNavigationContext()).isFalse();
            assertThat(cold.commareaLength()).isZero();
            assertThat(UserListRequest.empty().commareaLength())
                    .isEqualTo(UserListRequest.CU00_COMMAREA_LENGTH)
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH + UserListRequest.CU00_INFO_LENGTH);

            JsonNode json = mapper.readTree(mapper.writeValueAsString(cold));
            assertThat(json.get("navigationContext").isNull()).isTrue();
            assertThat(mapper.readValue(json.toString(), UserListRequest.class)
                    .hasNavigationContext()).isFalse();
        }

        @Test
        @DisplayName("the fifty numbered members are still absent from the map-field view")
        void theMapFieldViewIsUnchanged() {
            assertThat(populated().mapFields()).hasSize(UserListRequest.MAP_FIELD_COUNT);
            assertThat(populated().mapFields()).containsKey("SEL0001");
            assertThat(populated().mapFields().get("USRID01")).isEqualTo("ADMIN001");
        }
    }

    @Nested
    @DisplayName("UserUpdateResponse - a width failure names the field, never the value")
    class WidthFailureRedaction {
        @Test
        @DisplayName("an over-wide password is refused without quoting it")
        void anOverWidePasswordIsRefusedWithoutQuotingIt() {
            String secret = "SECRET12345";

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> UserUpdateResponse.blank().withPasswd(secret))
                    .withMessageContaining(UserUpdateResponse.PASSWD_FIELD)
                    .withMessageContaining("11 character(s)")
                    .withMessageContaining("[REDACTED]")
                    .withMessageNotContaining(secret);
        }

        @Test
        @DisplayName("the same guard withholds every other field's value too")
        void everyFieldsValueIsWithheld() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> UserUpdateResponse.blank().withFName("A".repeat(30)))
                    .withMessageContaining("30 character(s)")
                    .withMessageNotContaining("AAAAAAAAAA");
        }

        @Test
        @DisplayName("a value that fits is still stored verbatim, padding included")
        void aValueThatFitsIsUnchanged() {
            UserUpdateResponse response = UserUpdateResponse.blank().withPasswd("PASSWD12");

            assertThat(response.passwd()).isEqualTo("PASSWD12");
            assertThat(response.toString()).doesNotContain("PASSWD12");
        }

        @Test
        @DisplayName("an absent communication area on the CU02 response is the initialised one, "
                + "because COUSR02C:90 treats EIBCALEN = 0 as a recognised cold start")
        void anAbsentCommareaOnTheResponseIsInitialised() {
            UserUpdateResponse cold = UserUpdateResponse.blank().withNavigationContext(null);
            UserUpdateResponse warm = UserUpdateResponse.blank()
                    .withNavigationContext(NavigationContext.empty().withToProgram("COUSR02C"));

            assertThat(cold.navigationContext())
                    .as("a response is what the program is about to send, so its area always exists")
                    .isEqualTo(NavigationContext.empty());
            assertThat(warm.navigationContext().toProgram()).isEqualTo("COUSR02C");
        }
    }

    @Nested
    @DisplayName("CDEMO-CU00-PAGE-NUM and the ten-row table - the two non-string guards")
    class NonStringGuards {
        private UserListRequest base() {
            return UserListRequest.empty();
        }

        @Test
        @DisplayName("PIC 9(08) is unsigned, so a negative page number has no representation")
        void aNegativePageNumberIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> base().withCdemoCu00PageNum(-1))
                    .withMessageContaining("CDEMO-CU00-PAGE-NUM")
                    .withMessageContaining("unsigned");
        }

        @Test
        @DisplayName("a ninth digit has nowhere to go in PIC 9(08)")
        void aNinthDigitIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> base()
                            .withCdemoCu00PageNum(UserListRequest.CU00_PAGE_NUM_MAX + 1))
                    .withMessageContaining("CDEMO-CU00-PAGE-NUM")
                    .withMessageContaining(String.valueOf(UserListRequest.CU00_PAGE_NUM_MAX));
        }

        @Test
        @DisplayName("zero and the largest representable value both fit, because neither bound is a "
                + "business rule")
        void bothBoundsAreRepresentable() {
            assertThat(base().withCdemoCu00PageNum(0).cdemoCu00PageNum()).isZero();
            assertThat(base().withCdemoCu00PageNum(UserListRequest.CU00_PAGE_NUM_MAX)
                    .cdemoCu00PageNum()).isEqualTo(UserListRequest.CU00_PAGE_NUM_MAX);
        }

        @Test
        @DisplayName("an absent row list becomes ten blank rows rather than an error")
        void anAbsentRowListBecomesTenBlankRows() {
            UserListRequest request = base().withRows(null);

            assertThat(request.rows()).hasSize(UserListRequest.ROW_COUNT);
            assertThat(request.rows()).allSatisfy(row -> assertThat(row.usrId()).isBlank());
        }

        @Test
        @DisplayName("a list of any other size is refused, because ten is the map's shape and not a "
                + "page-size setting")
        void aWrongSizedRowListIsRefused() {
            List<UserListRow> nine = new ArrayList<>();
            for (int index = 0; index < UserListRequest.ROW_COUNT - 1; index++) {
                nine.add(UserListRow.blank());
            }

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> base().withRows(nine))
                    .withMessageContaining(String.valueOf(UserListRequest.ROW_COUNT))
                    .withMessageContaining("9");
        }

        @Test
        @DisplayName("an absent element inside a correctly sized list becomes one blank row")
        void anAbsentElementBecomesOneBlankRow() {
            List<UserListRow> withHole = new ArrayList<>();
            for (int index = 0; index < UserListRequest.ROW_COUNT; index++) {
                withHole.add(index == 4 ? null : UserListRow.blank().withUsrId("USER" + index));
            }

            UserListRequest request = base().withRows(withHole);

            assertThat(request.rows()).hasSize(UserListRequest.ROW_COUNT);
            assertThat(request.row(5).usrId()).isBlank();
            assertThat(request.row(4).usrId()).startsWith("USER3");
        }

        @ParameterizedTest(name = "row number {0} is out of the one-based range 1 to 10")
        @ValueSource(ints = {0, -1, 11, 99})
        @DisplayName("a row number outside 1 to 10 is refused, counting from 1 as WS-IDX does")
        void anOutOfRangeRowNumberIsRefused(int rowNumber) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> base().row(rowNumber))
                    .withMessageContaining("between 1 and " + UserListRequest.ROW_COUNT);
        }

        @Test
        @DisplayName("row 1 and row 10 are both in range")
        void bothEndsOfTheRangeAreInRange() {
            assertThat(base().row(1)).isNotNull();
            assertThat(base().row(UserListRequest.ROW_COUNT)).isNotNull();
        }

        @Test
        @DisplayName("mapFieldNames lists the 8 header labels, the 50 numbered row labels and "
                + "ERRMSGI, in the symbolic map's own order")
        void mapFieldNamesFollowsTheCopybookOrder() {
            List<String> names = UserListRequest.mapFieldNames();

            assertThat(names).hasSize(UserListRequest.MAP_FIELD_COUNT);
            assertThat(names.subList(0, 8))
                    .as("the eight header labels come first, as COUSR00.CPY declares them")
                    .containsExactly("TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02",
                            "CURTIME", "PAGENUM", "USRIDIN");
            assertThat(names.subList(8, 13))
                    .as("then row 1's five labels, selection first")
                    .containsExactly("SEL0001", "USRID01", "FNAME01", "LNAME01", "UTYPE01");
            assertThat(names.subList(53, 58))
                    .as("then through to row 10, whose selection label still carries four digits")
                    .containsExactly("SEL0010", "USRID10", "FNAME10", "LNAME10", "UTYPE10");
            assertThat(names).endsWith("ERRMSG");
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> UserListRequest.mapFieldNames().clear());
        }
    }

    @Nested
    @DisplayName("CU02 field lookup - a misspelled xxxO name fails where the mistake is")
    class FieldLookup {
        @Test
        @DisplayName("every declared field name resolves to the value the response holds")
        void everyDeclaredNameResolves() {
            UserUpdateResponse response = UserUpdateResponse.blank().withFName("JOHN");

            for (String name : UserUpdateResponse.MAP_FIELD_NAMES) {
                assertThat(response.value(name)).isNotNull();
            }
            assertThat(response.value(UserUpdateResponse.FNAME_FIELD)).startsWith("JOHN");
        }

        @Test
        @DisplayName("the COUSR01 spelling USERIDO is refused, and the message says which name to use")
        void theCousr01SpellingIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> UserUpdateResponse.blank().value("USERIDO"))
                    .withMessageContaining("USERIDO")
                    .withMessageContaining(UserUpdateResponse.USR_ID_IN_FIELD);
        }

        @Test
        @DisplayName("an attribute item is not a field of this payload either")
        void anAttributeItemIsNotAField() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> UserUpdateResponse.blank().value("FNAMEC"))
                    .withMessageContaining("metadata");
        }

        @Test
        @DisplayName("a null name is refused rather than looked up")
        void aNullNameIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> UserUpdateResponse.blank().value(null));
        }
    }
}
