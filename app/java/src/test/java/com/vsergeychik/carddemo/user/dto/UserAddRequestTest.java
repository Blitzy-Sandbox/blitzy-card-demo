package com.vsergeychik.carddemo.user.dto;

import com.vsergeychik.carddemo.common.SensitiveDiagnostics;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.common.NavigationContext;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link UserAddRequest}, the inbound payload of the Add User screen - CICS
 * transaction {@code CU01}, program {@code app/cbl/COUSR01C.cbl}.
 *
 * <p>Plain JUnit 5 with no Spring context, no {@code MockMvc} and no {@code JobLauncher}: the class
 * under test is a value type, so every one of its decisions is reachable by construction.
 *
 * <p>Several assertions here deliberately re-derive their expectations <strong>from the read-only
 * parity oracle at test time</strong> rather than from literals typed into this file. The field names
 * are parsed out of the {@code xxxI} items of {@code app/cpy-bms/COUSR01.CPY} and out of the
 * name-labelled {@code DFHMDF} definitions of {@code app/bms/COUSR01.bms}, and the widths come from
 * the same two places. A hand-copied literal can drift from the copybook silently; a parsed one
 * cannot, which is the whole point of holding the legacy sources as the contract.
 */
@DisplayName("UserAddRequest - the COUSR01 (CU01) Add User inbound payload")
class UserAddRequestTest {

    /** The twelve widths, in symbolic-map order, as {@code app/cpy-bms/COUSR01.CPY} declares them. */
    private static final List<Integer> DECLARED_WIDTHS =
            List.of(4, 40, 8, 8, 40, 8, 20, 20, 8, 8, 1, 78);

    /** The fourteen record components, in declaration order. */
    private static final List<String> COMPONENT_NAMES =
            List.of("trnName", "title01", "curDate", "pgmName", "title02", "curTime", "fName", "lName",
                    "userId", "passwd", "usrType", "errMsg", "navigationContext", "aid");

    // =================================================================================================
    // Shape: exactly twelve screen fields, plus the two mandated conversation-state members.
    // =================================================================================================

    @Nested
    @DisplayName("Shape")
    class Shape {

        @Test
        @DisplayName("is an immutable record of twelve screen fields plus two state members")
        void isARecordOfTwelveScreenFieldsPlusTwoStateMembers() {
            assertThat(UserAddRequest.class.isRecord()).isTrue();
            assertThat(componentNames()).isEqualTo(COMPONENT_NAMES);
            assertThat(UserAddRequest.MAP_FIELD_NAMES)
                    .as("one entry per name-labelled DFHMDF of app/bms/COUSR01.bms")
                    .hasSize(12);
        }

        @Test
        @DisplayName("declares no static mutable state")
        void declaresNoStaticMutableState() {
            for (var field : UserAddRequest.class.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    assertThat(java.lang.reflect.Modifier.isFinal(field.getModifiers()))
                            .as("static field %s must be final", field.getName())
                            .isTrue();
                }
            }
        }

        @Test
        @DisplayName("MAP_FIELD_NAMES is unmodifiable")
        void mapFieldNamesIsUnmodifiable() {
            List<String> names = UserAddRequest.MAP_FIELD_NAMES;
            assertThat(names).isUnmodifiable();
        }

        @Test
        @DisplayName("the eleven screen fields are String and the state members keep their own types")
        void componentTypesFollowThePictureClauses() {
            RecordComponent[] components = UserAddRequest.class.getRecordComponents();
            for (int index = 0; index < 12; index++) {
                assertThat(components[index].getType())
                        .as("%s is PIC X(n), so String", components[index].getName())
                        .isEqualTo(String.class);
            }
            assertThat(components[12].getType()).isEqualTo(NavigationContext.class);
            assertThat(components[13].getType()).isEqualTo(String.class);
        }
    }

    // =================================================================================================
    // Traceability: every payload member back to the copybook and the mapset, name and width.
    // =================================================================================================

    @Nested
    @DisplayName("Traceability to the parity oracle")
    class Traceability {

        @Test
        @DisplayName("MAP_FIELD_NAMES equals the xxxI items of app/cpy-bms/COUSR01.CPY, in order")
        void mapFieldNamesEqualTheSymbolicMapItems() {
            assertThat(symbolicMapItemNames()).isEqualTo(UserAddRequest.MAP_FIELD_NAMES);
        }

        @Test
        @DisplayName("the declared widths equal the xxxI PICTURE clauses, in order")
        void declaredWidthsEqualTheSymbolicMapPictures() {
            assertThat(symbolicMapItemWidths()).isEqualTo(DECLARED_WIDTHS);
            assertThat(declaredWidths()).isEqualTo(DECLARED_WIDTHS);
        }

        @Test
        @DisplayName("every member traces to a name-labelled DFHMDF, and there are exactly twelve")
        void everyMemberTracesToANameLabelledDfhmdf() {
            List<String> labelled = nameLabelledMapFields();
            assertThat(labelled).hasSize(12);
            for (int index = 0; index < labelled.size(); index++) {
                assertThat(UserAddRequest.MAP_FIELD_NAMES.get(index))
                        .as("DFHMDF %s must back symbolic-map item %s", labelled.get(index),
                                labelled.get(index) + "I")
                        .isEqualTo(labelled.get(index) + "I");
            }
        }

        @Test
        @DisplayName("the mapset declares 28 DFHMDF fields, so 16 are unexposed screen furniture")
        void sixteenDfhmdfDefinitionsAreLiteralFurniture() {
            long total = oracleLines("app/bms/COUSR01.bms").stream()
                    .filter(line -> line.contains("DFHMDF"))
                    .count();
            assertThat(total).isEqualTo(28);
            assertThat(total - nameLabelledMapFields().size()).isEqualTo(16);
        }

        @ParameterizedTest(name = "{0} is {1} characters")
        @CsvSource({"TRNNAMEI,4", "TITLE01I,40", "CURDATEI,8", "PGMNAMEI,8", "TITLE02I,40",
                    "CURTIMEI,8", "FNAMEI,20", "LNAMEI,20", "USERIDI,8", "PASSWDI,8", "USRTYPEI,1",
                    "ERRMSGI,78"})
        @DisplayName("each field name pairs with its own declared width")
        void eachFieldNamePairsWithItsWidth(String cobolName, int width) {
            int position = UserAddRequest.MAP_FIELD_NAMES.indexOf(cobolName);
            assertThat(position).as("%s must be a payload field", cobolName).isNotNegative();
            assertThat(declaredWidths().get(position)).isEqualTo(width);
        }
    }

    // =================================================================================================
    // The three divergences that are preserved rather than tidied.
    // =================================================================================================

    @Nested
    @DisplayName("Preserved divergences")
    class PreservedDivergences {

        @Test
        @DisplayName("the identifier field is USERID, never the USRIDIN of COUSR02 and COUSR03")
        void theIdentifierFieldIsUseridAndNotUsridin() {
            assertThat(UserAddRequest.USERID_FIELD).isEqualTo("USERIDI");
            assertThat(UserAddRequest.MAP_FIELD_NAMES).contains("USERIDI").doesNotContain("USRIDIN");
            assertThat(nameLabelledMapFields()).contains("USERID").doesNotContain("USRIDIN");
            assertThat(componentNames()).contains("userId");
        }

        @Test
        @DisplayName("CURTIME is eight characters, not the nine of COSGN00")
        void curTimeIsEightCharacters() {
            assertThat(UserAddRequest.CURTIME_LENGTH).isEqualTo(8);
            assertThat("hh:mm:ss").hasSize(UserAddRequest.CURTIME_LENGTH);
        }

        @Test
        @DisplayName("ERRMSG is 78 characters even though WS-MESSAGE is 80")
        void errMsgIsSeventyEightCharacters() {
            assertThat(UserAddRequest.ERRMSG_LENGTH).isEqualTo(78);
            assertThat(UserAddRequest.ERRMSG_LENGTH).isLessThan(80);
        }

        @Test
        @DisplayName("this screen's field order is FNAME, LNAME, USERID, PASSWD, USRTYPE")
        void theInputFieldOrderIsThisScreensOwn() {
            assertThat(UserAddRequest.MAP_FIELD_NAMES.subList(6, 11))
                    .containsExactly("FNAMEI", "LNAMEI", "USERIDI", "PASSWDI", "USRTYPEI");
        }

        @Test
        @DisplayName("the widths of the five input fields match the USRSEC record they populate")
        void theFiveInputWidthsMatchTheUsrsecRecord() {
            assertThat(UserAddRequest.USERID_LENGTH).isEqualTo(8);
            assertThat(UserAddRequest.FNAME_LENGTH).isEqualTo(20);
            assertThat(UserAddRequest.LNAME_LENGTH).isEqualTo(20);
            assertThat(UserAddRequest.PASSWD_LENGTH).isEqualTo(8);
            assertThat(UserAddRequest.USRTYPE_LENGTH).isEqualTo(1);
        }

        @Test
        @DisplayName("the screen identity literals are byte-exact")
        void screenIdentityLiteralsAreByteExact() {
            assertThat(UserAddRequest.TRANSACTION_ID).isEqualTo("CU01")
                    .hasSize(UserAddRequest.TRNNAME_LENGTH);
            assertThat(UserAddRequest.PROGRAM_NAME).isEqualTo("COUSR01C")
                    .hasSize(UserAddRequest.PGMNAME_LENGTH);
            assertThat(UserAddRequest.MAP_NAME).isEqualTo("COUSR1A")
                    .as("must fit CDEMO-LAST-MAP PIC X(7)").hasSize(7);
            assertThat(UserAddRequest.MAPSET_NAME).isEqualTo("COUSR01")
                    .as("must fit CDEMO-LAST-MAPSET PIC X(7)").hasSize(7);
            assertThat(UserAddRequest.AID_LENGTH).isEqualTo(5);
        }
    }

    // =================================================================================================
    // Validation: @Size only. The ordered blank-field chain of COUSR01C:117-151 stays in the controller.
    // =================================================================================================

    @Nested
    @DisplayName("Validation constraints")
    class ValidationConstraints {

        @Test
        @DisplayName("carries thirteen @Size constraints and no other constraint at all")
        void carriesOnlySizeConstraints() {
            int sized = 0;
            for (RecordComponent component : UserAddRequest.class.getRecordComponents()) {
                for (Annotation annotation : component.getAccessor().getAnnotations()) {
                    String type = annotation.annotationType().getName();
                    assertThat(type)
                            .as("%s carries a constraint the COBOL does not perform",
                                    component.getName())
                            .doesNotContain("NotBlank")
                            .doesNotContain("NotNull")
                            .doesNotContain("NotEmpty")
                            .doesNotContain("Pattern")
                            .doesNotContain("Email");
                    if (annotation instanceof Size) {
                        sized++;
                    }
                }
            }
            assertThat(sized).as("twelve screen fields plus the AID token").isEqualTo(13);
        }

        @Test
        @DisplayName("each @Size(max) equals the field's declared width")
        void eachSizeMaxEqualsTheDeclaredWidth() {
            RecordComponent[] components = UserAddRequest.class.getRecordComponents();
            for (int index = 0; index < 12; index++) {
                Size size = components[index].getAccessor().getAnnotation(Size.class);
                assertThat(size).as("%s must be size-constrained", components[index].getName())
                        .isNotNull();
                assertThat(size.max()).isEqualTo(DECLARED_WIDTHS.get(index));
            }
            assertThat(components[13].getAccessor().getAnnotation(Size.class).max())
                    .isEqualTo(UserAddRequest.AID_LENGTH);
        }

        @Test
        @DisplayName("accepts every blank value, because COUSR01C answers each with its own message")
        void acceptsBlankValues() {
            UserAddRequest blank = new UserAddRequest("", "", "", "", "", "", "                    ",
                    "                    ", "        ", "        ", " ", "",
                    NavigationContext.empty(), "");
            assertThat(validate(blank)).isEmpty();
        }

        @Test
        @DisplayName("accepts a null in every position, so no framework rejection precedes the program")
        void acceptsNullValues() {
            assertThat(validate(nulls())).isEmpty();
        }

        @Test
        @DisplayName("refuses only a value wider than the screen field can hold")
        void refusesOnlyAnOverWideValue() {
            UserAddRequest tooWide = new UserAddRequest("CU010", "", "", "", "", "", "", "", "", "", "",
                    "", NavigationContext.empty(), "");
            Set<ConstraintViolation<UserAddRequest>> violations = validate(tooWide);
            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath()).hasToString("trnName");

            UserAddRequest twoTooWide = new UserAddRequest("", "", "", "", "", "", "", "", "", "", "AB",
                    "", NavigationContext.empty(), "ENTER!");
            assertThat(validate(twoTooWide)).hasSize(2);
        }
    }

    // =================================================================================================
    // Conversation state. Every branch of both read-through predicates is driven here.
    // =================================================================================================

    @Nested
    @DisplayName("Conversation state")
    class ConversationState {

        @Test
        @DisplayName("an ENTER context reads through as enter and not re-enter")
        void enterContextReadsThrough() {
            UserAddRequest request = withContext(NavigationContext.empty());
            assertThat(request.navigationContext().isEnter()).isTrue();
            assertThat(request.pgmEnter()).isTrue();
            assertThat(request.pgmReenter()).isFalse();
        }

        @Test
        @DisplayName("a REENTER context reads through as re-enter and not enter")
        void reenterContextReadsThrough() {
            UserAddRequest request = withContext(NavigationContext.empty().withPgmReenter());
            assertThat(request.pgmEnter()).isFalse();
            assertThat(request.pgmReenter()).isTrue();
        }

        @Test
        @DisplayName("the two predicates are not complements: any other digit satisfies neither")
        void theTwoPredicatesAreNotComplements() {
            UserAddRequest request = withContext(NavigationContext.empty().withPgmContext(9));
            assertThat(request.pgmEnter()).isFalse();
            assertThat(request.pgmReenter()).isFalse();
        }

        @Test
        @DisplayName("a missing communication area asserts no context - the EIBCALEN = 0 case")
        void aMissingCommunicationAreaAssertsNoContext() {
            UserAddRequest request = withContext(null);
            assertThat(request.navigationContext()).isNull();
            assertThat(request.pgmEnter()).isFalse();
            assertThat(request.pgmReenter()).isFalse();
        }

        @Test
        @DisplayName("nothing is duplicated: the context is read from the carried area on every call")
        void theContextIsReadFromTheCarriedAreaOnEveryCall() {
            UserAddRequest enter = withContext(NavigationContext.empty());
            UserAddRequest reenter = new UserAddRequest(enter.trnName(), enter.title01(),
                    enter.curDate(), enter.pgmName(), enter.title02(), enter.curTime(), enter.fName(),
                    enter.lName(), enter.userId(), enter.passwd(), enter.usrType(), enter.errMsg(),
                    enter.navigationContext().withPgmReenter(), enter.aid());
            assertThat(enter.pgmEnter()).isTrue();
            assertThat(reenter.pgmReenter()).isTrue();
            assertThat(componentNames()).as("no context flag is stored beside the area")
                    .doesNotContain("pgmContext", "pgmEnter", "pgmReenter");
        }

        @Test
        @DisplayName("the AID token is carried verbatim, at most five characters")
        void theAidTokenIsCarriedVerbatim() {
            assertThat(withAid("ENTER").aid()).isEqualTo("ENTER");
            assertThat(withAid("PF03").aid()).isEqualTo("PF03");
            assertThat(withAid("").aid()).isEmpty();
            assertThat(validate(withAid("PF12"))).isEmpty();
        }
    }

    // =================================================================================================
    // Serialisation: names untransformed, padding intact, nothing derived on the wire.
    // =================================================================================================

    @Nested
    @DisplayName("Serialisation")
    class Serialisation {

        private final ObjectMapper mapper = new ObjectMapper();

        @Test
        @DisplayName("emits exactly the fourteen components and no derived property")
        void emitsExactlyTheFourteenComponents() throws IOException {
            String json = mapper.writeValueAsString(populated());
            List<String> keys = new ArrayList<>();
            mapper.readTree(json).fieldNames().forEachRemaining(keys::add);
            assertThat(keys).containsExactlyInAnyOrderElementsOf(COMPONENT_NAMES);
            assertThat(keys).doesNotContain("pgmEnter", "pgmReenter");
        }

        @Test
        @DisplayName("emits no xxxL, xxxF, xxxA, xxxC, xxxP, xxxH or xxxV item")
        void emitsNoAttributeOrLengthItem() throws IOException {
            String json = mapper.writeValueAsString(populated());
            for (String field : nameLabelledMapFields()) {
                for (String suffix : List.of("L", "F", "A", "C", "P", "H", "V")) {
                    assertThat(json)
                            .as("%s must not reach the wire", field + suffix)
                            .doesNotContain("\"" + field + suffix + "\"");
                }
            }
        }

        @Test
        @DisplayName("a round trip preserves trailing spaces and returns an equal value")
        void aRoundTripPreservesPadding() throws IOException {
            UserAddRequest original = populated();
            UserAddRequest restored =
                    mapper.readValue(mapper.writeValueAsString(original), UserAddRequest.class);
            assertThat(restored).isEqualTo(original);
            assertThat(restored.hashCode()).isEqualTo(original.hashCode());
            assertThat(restored.fName()).isEqualTo("JANE                ")
                    .hasSize(UserAddRequest.FNAME_LENGTH);
            assertThat(restored.passwd()).isEqualTo("PWDFAKE ")
                    .hasSize(UserAddRequest.PASSWD_LENGTH);
            assertThat(restored.errMsg()).isEqualTo("  ");
        }

        @Test
        @DisplayName("an absent communication area survives a round trip as absent")
        void anAbsentCommunicationAreaSurvivesARoundTrip() throws IOException {
            UserAddRequest original = withContext(null);
            UserAddRequest restored =
                    mapper.readValue(mapper.writeValueAsString(original), UserAddRequest.class);
            assertThat(restored.navigationContext()).isNull();
            assertThat(restored).isEqualTo(original);
        }
    }

    // =================================================================================================
    // Diagnostics: the password never appears.
    // =================================================================================================

    @Nested
    @DisplayName("Diagnostics")
    class Diagnostics {

        @Test
        @DisplayName("toString masks the password and keeps every other value verbatim")
        void toStringMasksThePassword() {
            String rendered = populated().toString();
            assertThat(rendered).doesNotContain("PWDFAKE ")
                    .contains("passwd=" + UserAddRequest.PASSWORD_MASK)
                    .contains("trnName=CU01")
                    .contains("userId=NEWUSR01")
                    .as("a given name has no safely-revealable part, so only its length is reported")
                    .doesNotContain("JANE")
                    .contains("fName=[text len=" + UserAddRequest.FNAME_LENGTH + "]")
                    .contains("aid=ENTER")
                    .startsWith("UserAddRequest[")
                    .endsWith("]");
        }

        @Test
        @DisplayName("the mask is unconditional, so an absent password is masked too")
        void theMaskIsUnconditional() {
            assertThat(nulls().toString()).contains("passwd=" + UserAddRequest.PASSWORD_MASK);
            assertThat(withContext(null).toString())
                    .contains("passwd=" + UserAddRequest.PASSWORD_MASK)
                    .contains("navigationContext=null");
            assertThat(UserAddRequest.PASSWORD_MASK)
                    .as("the mask hides the length as well as the value, and is now the module's one "
                            + "marker: this file, SignOnRequest and UserUpdateRequest previously used "
                            + "three different ones")
                    .isEqualTo(SensitiveDiagnostics.REDACTED)
                    .doesNotContain("*");
        }

        @Test
        @DisplayName("equals and hashCode still consider the real password")
        void equalsStillConsidersTheRealPassword() {
            UserAddRequest one = withPassword("PWDFAKE1");
            UserAddRequest other = withPassword("PWDFAKE2");
            assertThat(one).isNotEqualTo(other).isEqualTo(withPassword("PWDFAKE1"));
            assertThat(one).isNotEqualTo(null).isNotEqualTo("not a request");
            assertThat(one.toString()).isEqualTo(other.toString());
        }
    }

    // =================================================================================================
    // Fixtures and oracle readers.
    // =================================================================================================

    private static UserAddRequest populated() {
        return new UserAddRequest(UserAddRequest.TRANSACTION_ID, "CardDemo", "01/02/26",
                UserAddRequest.PROGRAM_NAME, "Add User", "12:34:56", "JANE                ",
                "DOE                 ", "NEWUSR01", "PWDFAKE ", "U", "  ",
                NavigationContext.empty().withPgmReenter(), "ENTER");
    }

    private static UserAddRequest nulls() {
        return new UserAddRequest(null, null, null, null, null, null, null, null, null, null, null,
                null, null, null);
    }

    private static UserAddRequest withContext(NavigationContext context) {
        return new UserAddRequest("", "", "", "", "", "", "", "", "", "", "", "", context, "");
    }

    private static UserAddRequest withAid(String aid) {
        return new UserAddRequest("", "", "", "", "", "", "", "", "", "", "", "",
                NavigationContext.empty(), aid);
    }

    private static UserAddRequest withPassword(String passwd) {
        return new UserAddRequest("", "", "", "", "", "", "", "", "", passwd, "", "",
                NavigationContext.empty(), "");
    }

    private static Set<ConstraintViolation<UserAddRequest>> validate(UserAddRequest request) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(request);
        }
    }

    private static List<String> componentNames() {
        List<String> names = new ArrayList<>();
        for (RecordComponent component : UserAddRequest.class.getRecordComponents()) {
            names.add(component.getName());
        }
        return names;
    }

    private static List<Integer> declaredWidths() {
        return List.of(UserAddRequest.TRNNAME_LENGTH, UserAddRequest.TITLE01_LENGTH,
                UserAddRequest.CURDATE_LENGTH, UserAddRequest.PGMNAME_LENGTH,
                UserAddRequest.TITLE02_LENGTH, UserAddRequest.CURTIME_LENGTH,
                UserAddRequest.FNAME_LENGTH, UserAddRequest.LNAME_LENGTH,
                UserAddRequest.USERID_LENGTH, UserAddRequest.PASSWD_LENGTH,
                UserAddRequest.USRTYPE_LENGTH, UserAddRequest.ERRMSG_LENGTH);
    }

    /** The {@code xxxI} item names of {@code app/cpy-bms/COUSR01.CPY}, in copybook order. */
    private static List<String> symbolicMapItemNames() {
        List<String> names = new ArrayList<>();
        for (Matcher matcher : symbolicMapItems()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    /** The {@code xxxI} {@code PICTURE} widths of {@code app/cpy-bms/COUSR01.CPY}, in order. */
    private static List<Integer> symbolicMapItemWidths() {
        List<Integer> widths = new ArrayList<>();
        for (Matcher matcher : symbolicMapItems()) {
            widths.add(Integer.valueOf(matcher.group(2)));
        }
        return widths;
    }

    private static List<Matcher> symbolicMapItems() {
        Pattern item = Pattern.compile("^\\s*02\\s+([A-Z0-9]+I)\\s+PIC X\\((\\d+)\\)\\.");
        List<Matcher> matched = new ArrayList<>();
        for (String line : oracleLines("app/cpy-bms/COUSR01.CPY")) {
            Matcher matcher = item.matcher(line);
            if (matcher.find()) {
                matched.add(matcher);
            }
        }
        return matched;
    }

    /** The name-labelled {@code DFHMDF} field names of {@code app/bms/COUSR01.bms}, in order. */
    private static List<String> nameLabelledMapFields() {
        Pattern labelled = Pattern.compile("^([A-Z0-9]+)\\s+DFHMDF");
        List<String> names = new ArrayList<>();
        for (String line : oracleLines("app/bms/COUSR01.bms")) {
            Matcher matcher = labelled.matcher(line);
            if (matcher.find()) {
                names.add(matcher.group(1));
            }
        }
        return names;
    }

    /**
     * Reads a read-only parity-oracle file by walking up from the working directory until it is found,
     * so the tests do not depend on which directory the build was launched from.
     */
    private static List<String> oracleLines(String repositoryRelativePath) {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            Path target = candidate.resolve(repositoryRelativePath);
            if (Files.isRegularFile(target)) {
                try {
                    return Files.readAllLines(target);
                } catch (IOException failure) {
                    throw new UncheckedIOException("Could not read " + target, failure);
                }
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException(repositoryRelativePath + " was not found above "
                + Path.of("").toAbsolutePath() + "; it is the screen-contract parity oracle");
    }
}
