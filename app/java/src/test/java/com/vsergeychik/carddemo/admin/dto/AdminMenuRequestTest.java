package com.vsergeychik.carddemo.admin.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.common.NavigationContext;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link AdminMenuRequest}, the inbound payload of the {@code CA00} admin menu screen.
 *
 * <p>The subject is a pure data projection of the {@code xxxI} items of {@code 01 COADM1AI} in
 * {@code app/cpy-bms/COADM01.CPY}, so these tests assert the three things such a projection can get
 * wrong - its geometry, what reaches the wire, and the guard order of its derived views - plus the
 * two behaviours the COBOL depends on it <em>not</em> having: it must not reject a blank option, and
 * it must not normalise one.
 */
@DisplayName("AdminMenuRequest - the CA00 admin menu request payload")
class AdminMenuRequestTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void openValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    /** A request with every screen field at exactly its declared width and no communication area. */
    private static AdminMenuRequest atDeclaredWidths(String option, NavigationContext context) {
        return new AdminMenuRequest("CA00",
                "x".repeat(AdminMenuRequest.TITLE_LENGTH),
                "08/08/26",
                "COADM01C",
                "y".repeat(AdminMenuRequest.TITLE_LENGTH),
                "09:41:07",
                "01. Account View",
                "02. Account Update",
                "03. Card View",
                "04. Card Update",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                option,
                "z".repeat(AdminMenuRequest.ERR_MSG_LENGTH),
                context,
                (byte) 0x7D);
    }

    private Set<String> violatedProperties(AdminMenuRequest request) {
        return validator.validate(request)
                .stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());
    }

    @Nested
    @DisplayName("Geometry, traced to the symbolic map")
    class Geometry {

        @Test
        @DisplayName("the twenty declared widths are 4, 40, 8, 8, 40, 8, twelve 40s, 2 and 78")
        void widthsMatchThePictureClauses() {
            assertThat(AdminMenuRequest.TRN_NAME_LENGTH).isEqualTo(4);
            assertThat(AdminMenuRequest.TITLE_LENGTH).isEqualTo(40);
            assertThat(AdminMenuRequest.CUR_DATE_LENGTH).isEqualTo(8);
            assertThat(AdminMenuRequest.PGM_NAME_LENGTH).isEqualTo(8);
            assertThat(AdminMenuRequest.CUR_TIME_LENGTH).isEqualTo(8);
            assertThat(AdminMenuRequest.OPTION_LINE_LENGTH).isEqualTo(40);
            assertThat(AdminMenuRequest.OPTION_LENGTH).isEqualTo(2);
            assertThat(AdminMenuRequest.ERR_MSG_LENGTH).isEqualTo(78);
            assertThat(AdminMenuRequest.OPTION_LINE_COUNT).isEqualTo(12);
            assertThat(AdminMenuRequest.MAPPED_FIELD_COUNT).isEqualTo(20);
        }

        @Test
        @DisplayName("the data widths sum to 668 and the whole image is 12 + 20x7 + 668 = 820")
        void theComputedTotalsAreSixHundredSixtyEightAndEightHundredTwenty() {
            assertThat(AdminMenuRequest.TIOAPFX_LENGTH).isEqualTo(12);
            assertThat(AdminMenuRequest.METADATA_BYTES_PER_FIELD).isEqualTo(7);

            // Both totals are computed in the subject from the individual widths, so pinning them here
            // is what turns a mistyped width into a failing test rather than a silently shifted offset.
            assertThat(AdminMenuRequest.PAYLOAD_DATA_LENGTH).isEqualTo(668);
            assertThat(AdminMenuRequest.SYMBOLIC_MAP_LENGTH).isEqualTo(820);
            assertThat(AdminMenuRequest.SYMBOLIC_MAP_LENGTH)
                    .isEqualTo(AdminMenuRequest.TIOAPFX_LENGTH
                            + AdminMenuRequest.MAPPED_FIELD_COUNT
                                    * AdminMenuRequest.METADATA_BYTES_PER_FIELD
                            + AdminMenuRequest.PAYLOAD_DATA_LENGTH);
        }

        @Test
        @DisplayName("the screen is identified as CA00 / COADM01C / COADM01 / COADM1A")
        void screenIdentityMatchesTheCsdAndTheProgram() {
            assertThat(AdminMenuRequest.TRANSACTION_ID).isEqualTo("CA00");
            assertThat(AdminMenuRequest.PROGRAM_NAME).isEqualTo("COADM01C");
            assertThat(AdminMenuRequest.MAPSET_NAME).isEqualTo("COADM01");
            assertThat(AdminMenuRequest.MAP_NAME).isEqualTo("COADM1A");

            // The map name is seven characters because the group items are that name plus a
            // one-character direction suffix: COADM1AI and COADM1AO.
            assertThat(AdminMenuRequest.MAP_NAME).hasSize(7);
            assertThat(AdminMenuRequest.PROGRAM_NAME).hasSize(AdminMenuRequest.PGM_NAME_LENGTH);
            assertThat(AdminMenuRequest.TRANSACTION_ID).hasSize(AdminMenuRequest.TRN_NAME_LENGTH);
        }

        @Test
        @DisplayName("every item-name constant is spelled as the copybook spells it, trailing I included")
        void itemNamesCarryTheInputSuffix() {
            assertThat(AdminMenuRequest.TRN_NAME_FIELD).isEqualTo("TRNNAMEI");
            assertThat(AdminMenuRequest.TITLE01_FIELD).isEqualTo("TITLE01I");
            assertThat(AdminMenuRequest.CUR_DATE_FIELD).isEqualTo("CURDATEI");
            assertThat(AdminMenuRequest.PGM_NAME_FIELD).isEqualTo("PGMNAMEI");
            assertThat(AdminMenuRequest.TITLE02_FIELD).isEqualTo("TITLE02I");
            assertThat(AdminMenuRequest.CUR_TIME_FIELD).isEqualTo("CURTIMEI");
            assertThat(AdminMenuRequest.OPTION_FIELD).isEqualTo("OPTIONI");
            assertThat(AdminMenuRequest.ERR_MSG_FIELD).isEqualTo("ERRMSGI");
            assertThat(AdminMenuRequest.OPTION_LINE_FIELDS)
                    .containsExactly("OPTN001I", "OPTN002I", "OPTN003I", "OPTN004I", "OPTN005I",
                            "OPTN006I", "OPTN007I", "OPTN008I", "OPTN009I", "OPTN010I", "OPTN011I",
                            "OPTN012I");
        }

        @Test
        @DisplayName("the item-name constant list is immutable and has one entry per option line")
        void itemNameListIsImmutable() {
            assertThat(AdminMenuRequest.OPTION_LINE_FIELDS).hasSize(AdminMenuRequest.OPTION_LINE_COUNT);
            assertThatThrownBy(() -> AdminMenuRequest.OPTION_LINE_FIELDS.set(0, "tampered"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("The twelve option lines")
    class OptionLines {

        @Test
        @DisplayName("are exposed in map order, OPTN001I first")
        void areExposedInMapOrder() {
            AdminMenuRequest request = atDeclaredWidths("04", null);

            assertThat(request.optionLines()).hasSize(AdminMenuRequest.OPTION_LINE_COUNT);
            assertThat(request.optionLines().subList(0, 4))
                    .containsExactly("01. Account View",
                            "02. Account Update",
                            "03. Card View",
                            "04. Card Update");
            assertThat(request.optionLines().get(0)).isEqualTo(request.optn001());
            assertThat(request.optionLines().get(11)).isEqualTo(request.optn012());
        }

        @Test
        @DisplayName("stay twelve long and tolerate nulls, because lines 11 and 12 are never populated")
        void tolerateUnpopulatedSlots() {
            // COADM01C can never fill lines 11 and 12: its EVALUATE covers WHEN 1 through WHEN 10, its
            // option table is OCCURS 9, and only four entries are active. The slots are still declared.
            AdminMenuRequest request = atDeclaredWidths("01", null);

            assertThat(request.optionLines()).hasSize(12).containsNull();
            assertThat(request.optn011()).isNull();
            assertThat(request.optn012()).isNull();
        }

        @Test
        @DisplayName("are returned as an unmodifiable view a caller cannot reach back through")
        void areUnmodifiable() {
            AdminMenuRequest request = atDeclaredWidths("01", null);
            List<String> lines = request.optionLines();

            assertThatThrownBy(() -> lines.set(0, "tampered"))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> lines.add("tampered"))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(lines::clear).isInstanceOf(UnsupportedOperationException.class);

            assertThat(request.optn001()).isEqualTo("01. Account View");
        }

        @Test
        @DisplayName("line up positionally with the item-name constants")
        void lineUpWithTheItemNames() {
            AdminMenuRequest request = atDeclaredWidths("01", null);

            assertThat(request.optionLines()).hasSameSizeAs(AdminMenuRequest.OPTION_LINE_FIELDS);
        }
    }

    @Nested
    @DisplayName("Statelessness: the payload carries the conversation")
    class Statelessness {

        @Test
        @DisplayName("an absent communication area encodes EIBCALEN = 0")
        void absentCommareaEncodesEibcalenZero() {
            AdminMenuRequest request = atDeclaredWidths("01", null);

            assertThat(request.navigationContext()).isNull();
            assertThat(request.isCommareaPresent()).isFalse();
        }

        @Test
        @DisplayName("a present communication area is carried in the payload and reported present")
        void presentCommareaIsCarried() {
            NavigationContext context = NavigationContext.empty().withUserTypeAdmin();
            AdminMenuRequest request = atDeclaredWidths("01", context);

            assertThat(request.isCommareaPresent()).isTrue();
            assertThat(request.navigationContext()).isSameAs(context);
            assertThat(request.navigationContext().isAdmin()).isTrue();
        }

        @Test
        @DisplayName("the enter and re-enter views read through to the communication area")
        void contextViewsReadThroughRatherThanDuplicate() {
            AdminMenuRequest onEnter = atDeclaredWidths("01", NavigationContext.empty().withPgmEnter());
            AdminMenuRequest onReenter =
                    atDeclaredWidths("01", NavigationContext.empty().withPgmReenter());

            assertThat(onEnter.isEnter()).isTrue();
            assertThat(onEnter.isReenter()).isFalse();
            assertThat(onReenter.isReenter()).isTrue();
            assertThat(onReenter.isEnter()).isFalse();

            // Read-through, not a second copy: the context remains the single source of truth.
            assertThat(onReenter.isReenter()).isEqualTo(onReenter.navigationContext().isReenter());
            assertThat(onEnter.isEnter()).isEqualTo(onEnter.navigationContext().isEnter());
        }

        @Test
        @DisplayName("neither view holds for a context digit that is neither 0 nor 1")
        void neitherViewHoldsForAnUnusedContextDigit() {
            // CDEMO-PGM-CONTEXT is PIC 9(01) and can hold any digit, so the two 88-levels are not
            // complementary. isReenter() must therefore not be written as !isEnter().
            AdminMenuRequest request = atDeclaredWidths("01", NavigationContext.empty().withPgmContext(9));

            assertThat(request.isEnter()).isFalse();
            assertThat(request.isReenter()).isFalse();
            assertThat(request.isCommareaPresent()).isTrue();
        }

        @Test
        @DisplayName("neither view holds when there is no communication area to consult")
        void neitherViewHoldsWithoutACommarea() {
            // COADM01C never reaches its context test when EIBCALEN = 0: it diverts to the sign-on
            // screen first. A caller must consult isCommareaPresent() before either view.
            AdminMenuRequest request = atDeclaredWidths("01", null);

            assertThat(request.isEnter()).isFalse();
            assertThat(request.isReenter()).isFalse();
        }

        @ParameterizedTest(name = "EIBAID 0x{0} survives unresolved")
        @CsvSource({"7D", "F3", "F1", "6D"})
        @DisplayName("the attention identifier byte is carried raw and unresolved")
        void aidByteIsCarriedRaw(String hex) {
            byte aid = (byte) Integer.parseInt(hex, 16);
            AdminMenuRequest request = new AdminMenuRequest(null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, "01", null, null,
                    aid);

            assertThat(request.eibAid()).isEqualTo(aid);
        }
    }

    @Nested
    @DisplayName("Bean Validation: widths are enforced, presence is not")
    class WidthValidation {

        @Test
        @DisplayName("a request with every field at exactly its declared width is valid")
        void exactlyAtTheDeclaredWidthsIsValid() {
            assertThat(violatedProperties(atDeclaredWidths("12", NavigationContext.empty()))).isEmpty();
        }

        @Test
        @DisplayName("a field one character over its declared width is rejected, naming that field")
        void oneCharacterTooLongIsRejected() {
            AdminMenuRequest tooLongTitle = new AdminMenuRequest("CA00",
                    "x".repeat(AdminMenuRequest.TITLE_LENGTH + 1),
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, "01", null, null, (byte) 0x7D);

            assertThat(violatedProperties(tooLongTitle)).containsExactly("title01");
        }

        @ParameterizedTest(name = "an over-long {0} is rejected")
        @ValueSource(strings = {"trnName", "curDate", "pgmName", "curTime", "option", "errMsg",
                "optn001", "optn012"})
        @DisplayName("every screen field enforces its own width independently")
        void everyFieldEnforcesItsOwnWidth(String property) {
            AdminMenuRequest request = switch (property) {
                case "trnName" -> requestWith(0, "x".repeat(AdminMenuRequest.TRN_NAME_LENGTH + 1));
                case "curDate" -> requestWith(2, "x".repeat(AdminMenuRequest.CUR_DATE_LENGTH + 1));
                case "pgmName" -> requestWith(3, "x".repeat(AdminMenuRequest.PGM_NAME_LENGTH + 1));
                case "curTime" -> requestWith(5, "x".repeat(AdminMenuRequest.CUR_TIME_LENGTH + 1));
                case "optn001" -> requestWith(6, "x".repeat(AdminMenuRequest.OPTION_LINE_LENGTH + 1));
                case "optn012" -> requestWith(17, "x".repeat(AdminMenuRequest.OPTION_LINE_LENGTH + 1));
                case "option" -> requestWith(18, "x".repeat(AdminMenuRequest.OPTION_LENGTH + 1));
                case "errMsg" -> requestWith(19, "x".repeat(AdminMenuRequest.ERR_MSG_LENGTH + 1));
                default -> throw new IllegalArgumentException(property);
            };

            assertThat(violatedProperties(request)).containsExactly(property);
        }

        /** Builds a request whose {@code index}-th screen field holds {@code value} and the rest null. */
        private AdminMenuRequest requestWith(int index, String value) {
            String[] fields = new String[AdminMenuRequest.MAPPED_FIELD_COUNT];
            fields[index] = value;
            return new AdminMenuRequest(fields[0], fields[1], fields[2], fields[3], fields[4],
                    fields[5], fields[6], fields[7], fields[8], fields[9], fields[10], fields[11],
                    fields[12], fields[13], fields[14], fields[15], fields[16], fields[17], fields[18],
                    fields[19], null, (byte) 0x7D);
        }

        @ParameterizedTest(name = "option [{0}] is accepted, not rejected")
        @ValueSource(strings = {"", " ", "  ", "0", "01", "99", "ab", " 1", "1 "})
        @DisplayName("a blank or non-numeric option is accepted, because COADM01C answers with a message")
        void blankOrNonNumericOptionIsAccepted(String option) {
            // app/cbl/COADM01C.cbl:127-134 answers an invalid option with
            // 'Please enter a valid option number...' and repaints the screen. It does not refuse the
            // input. A @NotBlank or @NotNull here would turn that message into a rejection and change
            // observable behaviour.
            assertThat(violatedProperties(atDeclaredWidths(option, NavigationContext.empty()))).isEmpty();
        }

        @Test
        @DisplayName("an entirely absent payload is valid: no field is mandatory")
        void anEntirelyAbsentPayloadIsValid() {
            AdminMenuRequest empty = new AdminMenuRequest(null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                    (byte) 0x00);

            assertThat(violatedProperties(empty)).isEmpty();
        }
    }

    @Nested
    @DisplayName("The option field is carried raw")
    class RawOptionPassThrough {

        @ParameterizedTest(name = "[{0}] is stored byte for byte")
        @ValueSource(strings = {"", " ", "  ", " 1", "1 ", "12", "0", "ab"})
        @DisplayName("no trimming, padding, normalising or parsing happens on the way in")
        void optionIsNeitherTrimmedNorNormalised(String option) {
            // The leading and trailing space pattern is the input that COADM01C.cbl:117-124 consumes:
            // it scans backwards for the last non-space, moves the prefix into a JUST RIGHT field, then
            // INSPECT ... REPLACING ALL ' ' BY '0'. Pre-trimming here would destroy that input.
            assertThat(atDeclaredWidths(option, null).option()).isEqualTo(option);
        }

        @Test
        @DisplayName("an all-spaces option is preserved rather than collapsed to empty or null")
        void allSpacesSurvives() {
            AdminMenuRequest request = atDeclaredWidths("  ", null);

            assertThat(request.option()).isEqualTo("  ").hasSize(AdminMenuRequest.OPTION_LENGTH);
        }
    }

    @Nested
    @DisplayName("JSON: the wire carries the screen fields and nothing else")
    class Serialisation {

        @Test
        @DisplayName("no symbolic-map metadata item reaches the wire")
        void metadataNeverReachesTheWire() throws Exception {
            String json = MAPPER.writeValueAsString(
                    atDeclaredWidths("01", NavigationContext.empty()));

            // xxxL, xxxF, xxxA on the input view and xxxC, xxxP, xxxH, xxxV on the output view are
            // length, flag and attribute metadata, not screen values. None of them is modelled, so none
            // of them can appear - under any capitalisation Jackson might choose.
            for (String item : List.of("TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02",
                    "CURTIME", "OPTN001", "OPTN012", "OPTION", "ERRMSG")) {
                for (String suffix : List.of("L", "F", "A", "C", "P", "H", "V")) {
                    assertThat(json).doesNotContain("\"" + item + suffix + "\"")
                            .doesNotContain("\"" + item.toLowerCase(java.util.Locale.ROOT) + suffix
                                    + "\"");
                }
            }
        }

        @Test
        @DisplayName("no derived view reaches the wire either")
        void derivedViewsAreNotSerialised() throws Exception {
            String json = MAPPER.writeValueAsString(
                    atDeclaredWidths("01", NavigationContext.empty().withPgmReenter()));

            assertThat(json).doesNotContain("optionLines")
                    .doesNotContain("commareaPresent")
                    .doesNotContain("\"enter\"")
                    .doesNotContain("\"reenter\"");
        }

        @Test
        @DisplayName("all twenty screen fields survive a deserialise-serialise round trip untouched")
        void roundTripPreservesEveryScreenField() throws Exception {
            AdminMenuRequest original = atDeclaredWidths("  ", NavigationContext.empty().withPgmReenter());

            AdminMenuRequest roundTripped =
                    MAPPER.readValue(MAPPER.writeValueAsString(original), AdminMenuRequest.class);

            assertThat(roundTripped).isEqualTo(original);
            assertThat(roundTripped.option()).isEqualTo("  ");
            assertThat(roundTripped.errMsg()).hasSize(AdminMenuRequest.ERR_MSG_LENGTH);
            assertThat(roundTripped.optionLines()).isEqualTo(original.optionLines());
            assertThat(roundTripped.navigationContext()).isEqualTo(original.navigationContext());
            assertThat(roundTripped.eibAid()).isEqualTo(original.eibAid());
        }

        @Test
        @DisplayName("an empty option round trips as empty, not as null")
        void emptyOptionRoundTripsAsEmpty() throws Exception {
            AdminMenuRequest original = atDeclaredWidths("", null);

            AdminMenuRequest roundTripped =
                    MAPPER.readValue(MAPPER.writeValueAsString(original), AdminMenuRequest.class);

            assertThat(roundTripped.option()).isEmpty();
            assertThat(roundTripped.isCommareaPresent()).isFalse();
        }

        @Test
        @DisplayName("a request body naming only the option binds, leaving the rest absent")
        void aSparseBodyBinds() throws Exception {
            AdminMenuRequest bound = MAPPER.readValue("{\"option\":\"04\"}", AdminMenuRequest.class);

            assertThat(bound.option()).isEqualTo("04");
            assertThat(bound.trnName()).isNull();
            assertThat(bound.isCommareaPresent()).isFalse();
            assertThat(bound.optionLines()).hasSize(AdminMenuRequest.OPTION_LINE_COUNT)
                    .containsOnlyNulls();
            assertThat(violatedProperties(bound)).isEmpty();
        }
    }
}
