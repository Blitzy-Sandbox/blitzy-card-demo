package com.vsergeychik.carddemo.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.vsergeychik.carddemo.admin.AdminMenuService.AdminMenuInput;
import com.vsergeychik.carddemo.admin.AdminMenuService.AdminMenuOptionTable;
import com.vsergeychik.carddemo.admin.AdminMenuService.AdminMenuOutcome;
import com.vsergeychik.carddemo.admin.AdminMenuService.OptionNormalisation;
import com.vsergeychik.carddemo.admin.AdminMenuService.ReceiveOutcome;
import com.vsergeychik.carddemo.admin.model.AdminMenuOptions;
import com.vsergeychik.carddemo.admin.model.AdminMenuOptions.AdminMenuOption;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.user.model.SecUserRecord;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link AdminMenuService}, the decision core of {@code app/cbl/COADM01C.cbl}.
 *
 * <h2>Deliberately no HTTP anywhere</h2>
 * Every assertion below drives the service by constructing it directly and calling a method. There is
 * no {@code MockMvc}, no {@code WebApplicationContext}, no {@code @SpringBootTest} and no
 * {@code JobLauncher}. That is the point of the class under test: the branches of {@code COADM01C} are
 * reachable from a plain JUnit test, which is what makes the per-package branch-coverage gate
 * satisfiable and what makes a failure point at one statement of one paragraph.
 *
 * <h2>Every expected value is transcribed from the source, never read back off the class</h2>
 * The literals, widths and program names below are typed out from {@code app/cbl/COADM01C.cbl},
 * {@code app/cpy/COADM02Y.cpy}, {@code app/cpy/CSMSG01Y.cpy}, {@code app/cpy-bms/COADM01.CPY} and
 * {@code app/bms/COADM01.bms}, so a drift in either direction is caught. Asserting a constant against
 * itself would prove nothing.
 */
@DisplayName("AdminMenuService - the decision core of COADM01C")
class AdminMenuServiceTest {

    /** {@code app/cbl/COADM01C.cbl:38} - {@code WS-MESSAGE PIC X(80)}. */
    private static final int WS_MESSAGE_WIDTH = 80;

    /** {@code app/cpy-bms/COADM01.CPY:182} - {@code OPTN001O PIC X(40)}. */
    private static final int OPTION_LINE_WIDTH = 40;

    /** {@code app/cpy/COCOM01Y.cpy:24} - {@code CDEMO-TO-PROGRAM PIC X(08)}. */
    private static final int PROGRAM_NAME_WIDTH = 8;

    /** Eighty spaces: {@code WS-MESSAGE} as {@code app/cbl/COADM01C.cbl:79} leaves it. */
    private static final String BLANK_MESSAGE = " ".repeat(WS_MESSAGE_WIDTH);

    /** Forty spaces: an {@code OPTN00nO} line the program never writes. */
    private static final String BLANK_OPTION_LINE = " ".repeat(OPTION_LINE_WIDTH);

    /** Eight spaces: an {@code XCTL} target on a path that does not transfer. */
    private static final String NO_NEXT_PROGRAM = " ".repeat(PROGRAM_NAME_WIDTH);

    /** {@code app/cbl/COADM01C.cbl:131}, transcribed - 37 characters. */
    private static final String INVALID_OPTION_TEXT = "Please enter a valid option number...";

    /** {@code app/cbl/COADM01C.cbl:149} and {@code :152} composed - 30 characters, with no name. */
    private static final String COMING_SOON_TEXT = "This option is coming soon ...";

    /** {@code app/cpy/COADM02Y.cpy:25-26} - {@code PIC X(35)}, transcribed. */
    private static final String OPTION_1_NAME = "User List (Security)               ";

    /** {@code app/cpy/COADM02Y.cpy:40-41} - {@code PIC X(35)}, transcribed. */
    private static final String OPTION_4_NAME = "User Delete (Security)             ";

    /** The composed {@code OPTN001O} line: {@code 01} then {@code '. '} then the 35-byte name. */
    private static final String OPTION_1_LINE = "01. " + OPTION_1_NAME + " ";

    /** The composed {@code OPTN004O} line. */
    private static final String OPTION_4_LINE = "04. " + OPTION_4_NAME + " ";

    /** An attention identifier {@code PfKeyResolver} maps to no key at all. */
    private static final byte UNRESOLVABLE_AID = (byte) 0x01;

    /**
     * The {@code carddemo.datasets} catalogue as {@code application.yml} declares {@code USRSEC}:
     * an indexed dataset of eighty-byte records keyed on the eight-byte {@code SEC-USR-ID}.
     *
     * @param recordLength the configured record width, so a disagreeing one can be driven too
     * @return a catalogue holding only that entry
     */
    private static DatasetBindings bindings(int recordLength) {
        DatasetBindings catalogue = new DatasetBindings();
        catalogue.put(AdminMenuService.USRSEC_DATASET_KEY,
                new DatasetBinding("CARDDEMO.TEST.USRSEC",
                        DatasetBinding.KSDS,
                        false,
                        "FB",
                        null,
                        recordLength,
                        "CSUSR01Y",
                        SecUserRecord.KEY_LENGTH,
                        null,
                        null,
                        null));
        return catalogue;
    }

    /** The catalogue as configured, with the copybook's eighty-byte record. */
    private static DatasetBindings bindings() {
        return bindings(SecUserRecord.RECORD_LENGTH);
    }

    /** A service wired the way the container wires it. */
    private static AdminMenuService service() {
        return new AdminMenuService(bindings());
    }

    /** A communication area in {@code CDEMO-PGM-REENTER} state, as a re-entry supplies it. */
    private static NavigationContext reenteredContext() {
        return NavigationContext.empty().withPgmReenter();
    }

    /**
     * A stub option table, so branches the real four-entry table cannot reach become reachable.
     *
     * @param activeCount  {@code CDEMO-ADMIN-OPT-COUNT}
     * @param programNames the target program name of each slot, in subscript order
     * @return the table
     */
    private static AdminMenuOptionTable stubTable(int activeCount, String... programNames) {
        List<Optional<AdminMenuOption>> slots = new ArrayList<>();
        for (int index = 0; index < programNames.length; index++) {
            slots.add(Optional.of(AdminMenuOption.of(index + 1,
                    "Stub option " + (index + 1),
                    programNames[index])));
        }
        return new AdminMenuOptionTable(slots, activeCount);
    }

    @Nested
    @DisplayName("Construction: the dead WS-USRSEC-FILE declaration, resolved from configuration")
    class Construction {

        @Test
        @DisplayName("WS-USRSEC-FILE is the eight-byte logical name 'USRSEC  ', with its two spaces")
        void usrSecFileNameIsTheEightByteLogicalName() {
            assertThat(service().usrSecFileName())
                    .as("app/cbl/COADM01C.cbl:39 declares PIC X(08) VALUE 'USRSEC  '")
                    .isEqualTo("USRSEC  ")
                    .hasSize(AdminMenuService.USRSEC_FILE_NAME_LENGTH);
        }

        @Test
        @DisplayName("no dataset name reaches the service - only the catalogue key does")
        void theConfiguredDatasetNameIsNeverRead() {
            assertThat(service().usrSecFileName())
                    .doesNotContain("CARDDEMO.TEST.USRSEC");
        }

        @Test
        @DisplayName("SEC-USER-DATA is declared and blank, and nothing reads it")
        void secUserDataIsADeclaredButUnusedRecord() {
            SecUserRecord declared = service().secUserData();
            assertThat(declared).isEqualTo(SecUserRecord.blank());
            assertThat(declared.secUsrId()).isBlank();
            assertThat(declared.secUsrPwd()).isBlank();
        }

        @Test
        @DisplayName("the codec is the one supplied, and is exposed for the controller's X(80)->X(78)")
        void theCodecIsExposed() {
            FixedWidthCodec supplied = new FixedWidthCodec(Charset.forName("IBM037"));
            assertThat(new AdminMenuService(bindings(), supplied).codec()).isSameAs(supplied);
            assertThat(service().codec().charset().name())
                    .isEqualTo(AdminMenuService.DEFAULT_MESSAGE_CHARSET_NAME);
        }

        @Test
        @DisplayName("a configured record width that disagrees with CSUSR01Y is refused")
        void aDisagreeingRecordWidthIsRefused() {
            DatasetBindings wrong = bindings(SecUserRecord.RECORD_LENGTH + 1);
            assertThatIllegalStateException()
                    .isThrownBy(() -> new AdminMenuService(wrong))
                    .withMessageContaining("SEC-USER-DATA")
                    .withMessageContaining("CSUSR01Y");
        }

        @Test
        @DisplayName("an unconfigured USRSEC key is refused")
        void anUnconfiguredKeyIsRefused() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> new AdminMenuService(new DatasetBindings()))
                    .withMessageContaining(AdminMenuService.USRSEC_DATASET_KEY);
        }

        @Test
        @DisplayName("both arguments are required")
        void bothArgumentsAreRequired() {
            assertThatNullPointerException().isThrownBy(() -> new AdminMenuService(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> new AdminMenuService(bindings(), null));
        }
    }

    @Nested
    @DisplayName("MAIN-PARA L82: IF EIBCALEN = 0 sets CDEMO-FROM-PROGRAM, not CDEMO-TO-PROGRAM")
    class AbsentCommarea {

        @Test
        @DisplayName("an absent communication area routes to the sign-on screen")
        void anAbsentCommareaRoutesToSignon() {
            AdminMenuOutcome outcome = service().handle(
                    AdminMenuInput.withoutCommarea(CicsAid.DFHENTER, "  "));

            assertThat(outcome.hasNextProgram()).isTrue();
            assertThat(outcome.nextProgram()).isEqualTo("COSGN00C");
            assertThat(outcome.screenPainted())
                    .as("no SEND MAP precedes the XCTL at L165-L167")
                    .isFalse();
        }

        @Test
        @DisplayName("L83 moves 'COSGN00C' into CDEMO-FROM-PROGRAM - FROM, not TO")
        void theLiteralLandsInFromProgram() {
            AdminMenuOutcome outcome = service().handle(
                    AdminMenuInput.withoutCommarea(CicsAid.DFHENTER, "  "));

            assertThat(outcome.navigationContext().fromProgram()).isEqualTo("COSGN00C");
            assertThat(outcome.navigationContext().toProgram())
                    .as("RETURN-TO-SIGNON-SCREEN then defaults CDEMO-TO-PROGRAM at L163")
                    .isEqualTo("COSGN00C");
        }

        @Test
        @DisplayName("the XCTL at L165-L167 carries no COMMAREA, unlike the one at L142-L145")
        void theSignonTransferCarriesNoCommarea() {
            AdminMenuOutcome outcome = service().handle(
                    AdminMenuInput.withoutCommarea(CicsAid.DFHPF3, "  "));

            assertThat(outcome.nextProgramCarriesCommarea()).isFalse();
        }

        @Test
        @DisplayName("no option is read and no menu line is composed on this path")
        void noOptionIsReadAndNoLineComposed() {
            AdminMenuOutcome outcome = service().handle(
                    AdminMenuInput.withoutCommarea(CicsAid.DFHENTER, "01"));

            assertThat(outcome.optionLines()).containsOnly(BLANK_OPTION_LINE);
            assertThat(outcome.message()).isEqualTo(BLANK_MESSAGE);
            assertThat(outcome.errorFlag()).isFalse();
            assertThat(outcome.receive()).isEqualTo(ReceiveOutcome.NOT_PERFORMED);
        }

        @Test
        @DisplayName("isCommareaPresent distinguishes the two branches of L82")
        void isCommareaPresentDistinguishesTheBranches() {
            assertThat(AdminMenuInput.withoutCommarea(CicsAid.DFHENTER, "  ").isCommareaPresent())
                    .isFalse();
            assertThat(new AdminMenuInput(NavigationContext.empty(), CicsAid.DFHENTER, "  ")
                    .isCommareaPresent()).isTrue();
        }
    }

    @Nested
    @DisplayName("MAIN-PARA L87: IF NOT CDEMO-PGM-REENTER paints the screen and reads nothing")
    class FirstEntry {

        @Test
        @DisplayName("first entry flips the context to re-enter and paints")
        void firstEntryFlipsTheContextAndPaints() {
            AdminMenuOutcome outcome = service().handle(
                    new AdminMenuInput(NavigationContext.empty(), CicsAid.DFHENTER, "01"));

            assertThat(outcome.screenPainted()).isTrue();
            assertThat(outcome.hasNextProgram())
                    .as("no option is read on this path, so no XCTL can occur")
                    .isFalse();
            assertThat(outcome.nextProgram()).isEqualTo(NO_NEXT_PROGRAM);
            assertThat(outcome.navigationContext().isReenter()).isTrue();
            assertThat(outcome.navigationContext().pgmContext())
                    .isEqualTo(NavigationContext.PGM_CONTEXT_REENTER);
        }

        @Test
        @DisplayName("L89 MOVE LOW-VALUES TO COADM1AO clears every output field first")
        void lowValuesClearsEveryOutputField() {
            AdminMenuOutcome outcome = service().handle(
                    new AdminMenuInput(NavigationContext.empty(), CicsAid.DFHENTER, "04"));

            assertThat(outcome.resetAllOutputFields()).isTrue();
            assertThat(outcome.option())
                    .as("OPTIONO is one of the fields LOW-VALUES clears")
                    .isEqualTo("  ");
            assertThat(outcome.message()).isEqualTo(BLANK_MESSAGE);
            assertThat(outcome.errorFlag()).isFalse();
            assertThat(outcome.errFlgImage()).isEqualTo(AdminMenuService.ERR_FLG_OFF);
        }

        @Test
        @DisplayName("the map is not received, so RESP and RESP2 are not captured")
        void theMapIsNotReceived() {
            AdminMenuOutcome outcome = service().handle(
                    new AdminMenuInput(NavigationContext.empty(), CicsAid.DFHENTER, "  "));

            assertThat(outcome.receive().performed()).isFalse();
        }

        @ParameterizedTest(name = "CDEMO-PGM-CONTEXT = {0} takes the first-entry path")
        @DisplayName("IF NOT CDEMO-PGM-REENTER is not 'is enter': any non-1 digit takes that path")
        @ValueSource(ints = {0, 2, 5, 9})
        void anyNonReenterContextTakesTheFirstEntryPath(int pgmContext) {
            NavigationContext context = NavigationContext.empty().withPgmContext(pgmContext);
            AdminMenuOutcome outcome = service().handle(
                    new AdminMenuInput(context, CicsAid.DFHPF3, "  "));

            assertThat(outcome.screenPainted())
                    .as("PIC 9(01) admits 0 and 2..9, and none of them satisfies 88 CDEMO-PGM-REENTER")
                    .isTrue();
            assertThat(outcome.hasNextProgram()).isFalse();
        }

        @Test
        @DisplayName("isReenter is false for an absent area and true only for context 1")
        void isReenterCoversItsThreeStates() {
            assertThat(AdminMenuInput.withoutCommarea(CicsAid.DFHENTER, "  ").isReenter()).isFalse();
            assertThat(new AdminMenuInput(NavigationContext.empty(), CicsAid.DFHENTER, "  ")
                    .isReenter()).isFalse();
            assertThat(new AdminMenuInput(reenteredContext(), CicsAid.DFHENTER, "  ")
                    .isReenter()).isTrue();
        }

        @Test
        @DisplayName("OPTIONI is never null: a COBOL field always holds bytes")
        void optionIsRequired() {
            assertThatNullPointerException().isThrownBy(
                    () -> new AdminMenuInput(NavigationContext.empty(), CicsAid.DFHENTER, null));
        }
    }

    @Nested
    @DisplayName("MAIN-PARA L93: EVALUATE EIBAID, all three arms in source order")
    class EvaluateEibAid {

        @Test
        @DisplayName("WHEN DFHPF3 moves 'COSGN00C' into CDEMO-TO-PROGRAM - TO, not FROM")
        void pf3SetsToProgram() {
            AdminMenuOutcome outcome = service().handle(
                    new AdminMenuInput(reenteredContext(), CicsAid.DFHPF3, "  "));

            assertThat(outcome.navigationContext().toProgram()).isEqualTo("COSGN00C");
            assertThat(outcome.navigationContext().fromProgram())
                    .as("L97 touches CDEMO-TO-PROGRAM only")
                    .isBlank();
            assertThat(outcome.nextProgram()).isEqualTo("COSGN00C");
            assertThat(outcome.nextProgramCarriesCommarea()).isFalse();
            assertThat(outcome.screenPainted()).isFalse();
            assertThat(outcome.receive()).isEqualTo(ReceiveOutcome.NORMAL);
        }

        @Test
        @DisplayName("WHEN OTHER sets the error flag and widens CCDA-MSG-INVALID-KEY from 50 to 80")
        void otherRaisesTheInvalidKeyMessage() {
            AdminMenuOutcome outcome = service().handle(
                    new AdminMenuInput(reenteredContext(), CicsAid.DFHPF12, "  "));

            assertThat(outcome.errorFlag()).isTrue();
            assertThat(outcome.errFlgImage()).isEqualTo(AdminMenuService.ERR_FLG_ON);
            assertThat(SystemMessages.CCDA_MSG_INVALID_KEY).hasSize(SystemMessages.MESSAGE_LENGTH);
            assertThat(outcome.message())
                    .hasSize(WS_MESSAGE_WIDTH)
                    .startsWith("Invalid key pressed. Please see below...")
                    .isEqualTo(SystemMessages.CCDA_MSG_INVALID_KEY + " ".repeat(30));
            assertThat(outcome.screenPainted()).isTrue();
            assertThat(outcome.messageColour()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(outcome.messageColourOverridden()).isFalse();
        }

        @Test
        @DisplayName("a byte PfKeyResolver cannot resolve at all lands on WHEN OTHER")
        void anUnresolvableAidLandsOnWhenOther() {
            AdminMenuOutcome outcome = service().handle(
                    new AdminMenuInput(reenteredContext(), UNRESOLVABLE_AID, "  "));

            assertThat(outcome.errorFlag()).isTrue();
            assertThat(outcome.message()).startsWith("Invalid key pressed.");
        }

        @Test
        @DisplayName("PF15 lands on WHEN OTHER: COADM01C does not copy CSSTRPFY")
        void pf15IsNotPf3Here() {
            AdminMenuOutcome outcome = service().handle(
                    new AdminMenuInput(reenteredContext(), CicsAid.DFHPF15, "  "));

            assertThat(outcome.errorFlag())
                    .as("CSSTRPFY folds PF15 onto PFK03, but line 93 compares EIBAID to DFHPF3")
                    .isTrue();
            assertThat(outcome.hasNextProgram()).isFalse();
        }

        @Test
        @DisplayName("WHEN OTHER leaves OPTIONO as the client re-supplied it")
        void otherEchoesTheReceivedOption() {
            AdminMenuOutcome outcome = service().handle(
                    new AdminMenuInput(reenteredContext(), CicsAid.DFHCLEAR, "07"));

            assertThat(outcome.option()).isEqualTo("07");
        }

        @Test
        @DisplayName("every outcome carries the RETURN TRANSID, the mapset and the map")
        void everyOutcomeCarriesTheScreenIdentity() {
            AdminMenuOutcome outcome = service().handle(
                    new AdminMenuInput(reenteredContext(), CicsAid.DFHENTER, "01"));

            assertThat(outcome.transactionId()).isEqualTo("CA00");
            assertThat(outcome.mapsetName()).isEqualTo("COADM01");
            assertThat(outcome.mapName()).isEqualTo("COADM1A");
        }

        @Test
        @DisplayName("both handle overloads and both arguments behave")
        void handleRequiresItsArguments() {
            assertThatNullPointerException().isThrownBy(() -> service().handle(null));
            assertThatNullPointerException().isThrownBy(() -> service().handle(
                    new AdminMenuInput(reenteredContext(), CicsAid.DFHENTER, "01"), null));
        }
    }

    @Nested
    @DisplayName("PROCESS-ENTER-KEY L117-L125: the four-step option normalisation")
    class OptionNormalisationCases {

        @ParameterizedTest(name = "OPTIONI {0} -> WS-IDX {1}, step 3 {2}, step 4 {3}")
        @DisplayName("all five worked cases, asserting the intermediate two-byte buffer")
        @CsvSource(delimiter = '|', value = {
            "'  ' | 1 | '  ' | '00'",
            "' 3' | 2 | ' 3' | '03'",
            "'3 ' | 1 | ' 3' | '03'",
            "'12' | 2 | '12' | '12'",
            "'1x' | 2 | '1x' | '1x'"
        })
        void theFiveWorkedCases(String optionI,
                int expectedWsIdx,
                String afterJustifyRight,
                String afterInspect) {

            OptionNormalisation normalised = service().normaliseOption(optionI);

            assertThat(normalised.receivedOptionI()).isEqualTo(optionI);
            assertThat(normalised.wsIdx()).isEqualTo(expectedWsIdx);
            assertThat(normalised.justifiedOptionX()).isEqualTo(afterJustifyRight);
            assertThat(normalised.optionX()).isEqualTo(afterInspect);
            assertThat(normalised.optionEcho()).isEqualTo(afterInspect);
        }

        @ParameterizedTest(name = "OPTIONI {0} denotes WS-OPTION {1}")
        @DisplayName("the numeric cases decode, and the non-numeric one does not")
        @CsvSource(delimiter = '|', value = {
            "'  ' | 0",
            "' 3' | 3",
            "'3 ' | 3",
            "'12' | 12",
            "'04' | 4"
        })
        void theNumericCasesDecode(String optionI, int expected) {
            OptionNormalisation normalised = service().normaliseOption(optionI);

            assertThat(normalised.isNumeric()).isTrue();
            assertThat(normalised.option()).hasValue(expected);
        }

        @Test
        @DisplayName("a non-digit is reported as absent rather than thrown, so L127 has work to do")
        void aNonDigitIsReportedNotThrown() {
            OptionNormalisation normalised = service().normaliseOption("1x");

            assertThat(normalised.isNumeric()).isFalse();
            assertThat(normalised.option()).isEmpty();
        }

        @ParameterizedTest(name = "OPTIONI {0} is not numeric")
        @DisplayName("a character on either side of the digit range is non-numeric, not just above it")
        @CsvSource(delimiter = '|', value = {
            "'1x' | '1x'",
            "'1-' | '1-'",
            "'1/' | '1/'",
            "'a1' | 'a1'",
            "'!!' | '!!'"
        })
        void bothSidesOfTheDigitRangeAreNonNumeric(String optionI, String expectedImage) {
            OptionNormalisation normalised = service().normaliseOption(optionI);

            assertThat(normalised.optionX())
                    .as("the INSPECT replaces spaces only, so any other character survives")
                    .isEqualTo(expectedImage);
            assertThat(normalised.isNumeric())
                    .as("a zoned DISPLAY field holds one code page's ten digits and nothing else")
                    .isFalse();
        }

        @Test
        @DisplayName("a value wider or narrower than OPTIONI is moved into PIC X(2) first")
        void theReceivedValueIsMovedIntoItsDeclaredWidth() {
            assertThat(service().normaliseOption("").receivedOptionI()).isEqualTo("  ");
            assertThat(service().normaliseOption("9").justifiedOptionX()).isEqualTo(" 9");
            assertThat(service().normaliseOption("123").receivedOptionI())
                    .as("a PIC X receiver truncates on the right")
                    .isEqualTo("12");
        }

        @Test
        @DisplayName("OPTIONI is required")
        void optionIsRequired() {
            assertThatNullPointerException().isThrownBy(() -> service().normaliseOption(null));
        }

        @Test
        @DisplayName("the carrier refuses an image that is not at its declared width")
        void theCarrierChecksItsWidths() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new OptionNormalisation(2, "123", "03", "03",
                            OptionalInt.of(3)))
                    .withMessageContaining("OPTIONI is PIC X(2)");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new OptionNormalisation(2, "03", "3", "03",
                            OptionalInt.of(3)))
                    .withMessageContaining("JUST RIGHT");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new OptionNormalisation(2, "03", "03", "3",
                            OptionalInt.of(3)))
                    .withMessageContaining("WS-OPTION is PIC 9(02)");
            assertThatNullPointerException()
                    .isThrownBy(() -> new OptionNormalisation(2, null, "03", "03",
                            OptionalInt.of(3)));
            assertThatNullPointerException()
                    .isThrownBy(() -> new OptionNormalisation(2, "03", null, "03",
                            OptionalInt.of(3)));
            assertThatNullPointerException()
                    .isThrownBy(() -> new OptionNormalisation(2, "03", "03", null,
                            OptionalInt.of(3)));
            assertThatNullPointerException()
                    .isThrownBy(() -> new OptionNormalisation(2, "03", "03", "03", null));
        }
    }

    @Nested
    @DisplayName("PROCESS-ENTER-KEY L127-L134: the three-term validation, each term driven alone")
    class OptionValidation {

        @Test
        @DisplayName("term 1 alone: WS-OPTION IS NOT NUMERIC")
        void termOneNotNumeric() {
            AdminMenuOutcome outcome = service().handle(
                    new AdminMenuInput(reenteredContext(), CicsAid.DFHENTER, "1x"));

            assertInvalidOption(outcome);
            assertThat(outcome.option()).isEqualTo("1x");
        }

        @Test
        @DisplayName("term 2 alone: WS-OPTION > CDEMO-ADMIN-OPT-COUNT, numeric and non-zero")
        void termTwoAboveTheActiveCount() {
            AdminMenuOutcome outcome = service().handle(
                    new AdminMenuInput(reenteredContext(), CicsAid.DFHENTER, "05"));

            assertInvalidOption(outcome);
            assertThat(AdminMenuOptions.ACTIVE_OPTION_COUNT).isEqualTo(4);
        }

        @Test
        @DisplayName("term 3 alone: WS-OPTION = ZEROS, numeric and not above the count")
        void termThreeZero() {
            AdminMenuOutcome outcome = service().handle(
                    new AdminMenuInput(reenteredContext(), CicsAid.DFHENTER, "  "));

            assertInvalidOption(outcome);
            assertThat(outcome.option())
                    .as("the INSPECT has already turned the two spaces into zeros")
                    .isEqualTo("00");
        }

        @Test
        @DisplayName("all three terms false: the guard does not fire")
        void allThreeTermsFalse() {
            AdminMenuOutcome outcome = service().handle(
                    new AdminMenuInput(reenteredContext(), CicsAid.DFHENTER, "01"));

            assertThat(outcome.errorFlag()).isFalse();
            assertThat(outcome.message()).isEqualTo(BLANK_MESSAGE);
        }

        /** The shared expectation of the error path: L130 to L133. */
        private void assertInvalidOption(AdminMenuOutcome outcome) {
            assertThat(outcome.errorFlag()).isTrue();
            assertThat(outcome.errFlgImage()).isEqualTo("Y");
            assertThat(INVALID_OPTION_TEXT).hasSize(37);
            assertThat(outcome.message())
                    .hasSize(WS_MESSAGE_WIDTH)
                    .isEqualTo(INVALID_OPTION_TEXT + " ".repeat(43));
            assertThat(outcome.screenPainted())
                    .as("L133 performs SEND-MENU-SCREEN, so the menu is repainted with the error")
                    .isTrue();
            assertThat(outcome.hasNextProgram())
                    .as("L137's IF NOT ERR-FLG-ON suppresses the dispatch entirely")
                    .isFalse();
            assertThat(outcome.messageColour())
                    .as("L148 is never reached on the error path, so ERRMSGC keeps the map's red")
                    .isEqualTo(BmsAttributes.DFHRED);
            assertThat(outcome.optionLine(1)).isEqualTo(OPTION_1_LINE);
        }
    }

    @Nested
    @DisplayName("PROCESS-ENTER-KEY L137-L155: dispatch, and the XCTL that never returns")
    class OptionDispatch {

        @ParameterizedTest(name = "option {0} transfers to {1}")
        @DisplayName("each of the four options names its own target program")
        @CsvSource({
            "01, COUSR00C",
            "02, COUSR01C",
            "03, COUSR02C",
            "04, COUSR03C"
        })
        void eachOptionTransfersToItsTarget(String optionI, String expectedProgram) {
            AdminMenuOutcome outcome = service().handle(
                    new AdminMenuInput(reenteredContext(), CicsAid.DFHENTER, optionI));

            assertThat(outcome.nextProgram()).isEqualTo(expectedProgram);
            assertThat(outcome.hasNextProgram()).isTrue();
        }

        @Test
        @DisplayName("L139-L141 stamp the commarea, and L142-L145 pass it to the target")
        void theTransferStampsAndCarriesTheCommarea() {
            AdminMenuOutcome outcome = service().handle(
                    new AdminMenuInput(reenteredContext(), CicsAid.DFHENTER, "02"));

            assertThat(outcome.navigationContext().fromTranid()).isEqualTo("CA00");
            assertThat(outcome.navigationContext().fromProgram()).isEqualTo("COADM01C");
            assertThat(outcome.navigationContext().isEnter())
                    .as("L141 MOVE ZEROS TO CDEMO-PGM-CONTEXT resets the target to first entry")
                    .isTrue();
            assertThat(outcome.nextProgramCarriesCommarea()).isTrue();
        }

        @Test
        @DisplayName("the method returns at the XCTL: no 'coming soon' message leaks out")
        void theTransferReturnsImmediately() {
            AdminMenuOutcome outcome = service().handle(
                    new AdminMenuInput(reenteredContext(), CicsAid.DFHENTER, "03"));

            assertThat(outcome.message())
                    .as("L147-L153 are reachable only when L138 found 'DUMMY'")
                    .isEqualTo(BLANK_MESSAGE);
            assertThat(outcome.messageColourOverridden())
                    .as("L148 MOVE DFHGREEN is on the same unreachable path")
                    .isFalse();
            assertThat(outcome.screenPainted())
                    .as("an XCTL is not preceded by a SEND")
                    .isFalse();
            assertThat(outcome.optionLines()).containsOnly(BLANK_OPTION_LINE);
            assertThat(outcome.errorFlag()).isFalse();
        }

        @Test
        @DisplayName("the 'DUMMY' prefix suppresses the transfer and emits the 30-character message")
        void theDummyPrefixSuppressesTheTransfer() {
            AdminMenuOptionTable dummy = stubTable(1, "DUMMY001");

            AdminMenuOutcome outcome = service().handle(
                    new AdminMenuInput(reenteredContext(), CicsAid.DFHENTER, "01"), dummy);

            assertThat(outcome.hasNextProgram()).isFalse();
            assertThat(outcome.nextProgram()).isEqualTo(NO_NEXT_PROGRAM);
            assertThat(COMING_SOON_TEXT).hasSize(30);
            assertThat(outcome.message())
                    .hasSize(WS_MESSAGE_WIDTH)
                    .isEqualTo(COMING_SOON_TEXT + " ".repeat(50))
                    .as("L150-L151 comment the option name out of the STRING")
                    .doesNotContain("Stub option");
            assertThat(outcome.messageColour()).isEqualTo(BmsAttributes.DFHGREEN);
            assertThat(outcome.messageColourOverridden()).isTrue();
            assertThat(outcome.screenPainted()).isTrue();
            assertThat(outcome.errorFlag()).isFalse();
        }

        @Test
        @DisplayName("only the first five bytes are compared: 'DUMMYXYZ' matches, 'DUMM0001' does not")
        void onlyFiveBytesAreCompared() {
            AdminMenuOutcome matched = service().handle(
                    new AdminMenuInput(reenteredContext(), CicsAid.DFHENTER, "01"),
                    stubTable(1, "DUMMYXYZ"));
            assertThat(matched.hasNextProgram()).isFalse();

            AdminMenuOutcome unmatched = service().handle(
                    new AdminMenuInput(reenteredContext(), CicsAid.DFHENTER, "01"),
                    stubTable(1, "DUMM0001"));
            assertThat(unmatched.nextProgram()).isEqualTo("DUMM0001");
        }

        @Test
        @DisplayName("the composed message is built from the two operands, not from the constant")
        void theComingSoonConstantMatchesTheComposition() {
            assertThat(AdminMenuService.COMING_SOON_PREFIX).hasSize(12);
            assertThat(AdminMenuService.COMING_SOON_SUFFIX).hasSize(18);
            assertThat(AdminMenuService.COMING_SOON_MESSAGE).isEqualTo(COMING_SOON_TEXT);
        }
    }

    @Nested
    @DisplayName("RETURN-TO-SIGNON-SCREEN L162: both halves of the combined relation")
    class SignonDefaulting {

        @Test
        @DisplayName("a CDEMO-TO-PROGRAM of LOW-VALUES defaults to 'COSGN00C'")
        void lowValuesDefaults() {
            NavigationContext lowValues = reenteredContext()
                    .withToProgram("\u0000".repeat(PROGRAM_NAME_WIDTH));

            AdminMenuOutcome outcome = service().handle(
                    new AdminMenuInput(lowValues, CicsAid.DFHPF3, "  "));

            assertThat(outcome.nextProgram()).isEqualTo("COSGN00C");
            assertThat(outcome.navigationContext().toProgram()).isEqualTo("COSGN00C");
        }

        @Test
        @DisplayName("a CDEMO-TO-PROGRAM of SPACES defaults to 'COSGN00C'")
        void spacesDefaults() {
            NavigationContext spaces = reenteredContext().withToProgram(NO_NEXT_PROGRAM);

            AdminMenuOutcome outcome = service().handle(
                    new AdminMenuInput(spaces, CicsAid.DFHPF3, "  "));

            assertThat(outcome.nextProgram()).isEqualTo("COSGN00C");
        }

        @Test
        @DisplayName("PF3 always overwrites CDEMO-TO-PROGRAM first, so L162 defaults nothing")
        void pf3AlwaysSuppliesTheTarget() {
            NavigationContext elsewhere = reenteredContext().withToProgram("COMEN01C");

            AdminMenuOutcome outcome = service().handle(
                    new AdminMenuInput(elsewhere, CicsAid.DFHPF3, "  "));

            assertThat(outcome.nextProgram())
                    .as("L97 moves 'COSGN00C' in before RETURN-TO-SIGNON-SCREEN examines it")
                    .isEqualTo("COSGN00C");
        }

        @Test
        @DisplayName("half one: an all-low-values CDEMO-TO-PROGRAM defaults")
        void theLowValuesHalfDefaults() {
            assertThat(service().resolveSignonTarget("\u0000".repeat(PROGRAM_NAME_WIDTH)))
                    .isEqualTo("COSGN00C");
        }

        @Test
        @DisplayName("half two: an all-spaces CDEMO-TO-PROGRAM defaults, and so does a short one")
        void theSpacesHalfDefaults() {
            assertThat(service().resolveSignonTarget(NO_NEXT_PROGRAM)).isEqualTo("COSGN00C");
            assertThat(service().resolveSignonTarget(""))
                    .as("a short value is padded to PIC X(08) before the comparison")
                    .isEqualTo("COSGN00C");
        }

        @Test
        @DisplayName("neither half: a populated name is transferred to unchanged")
        void aPopulatedNameIsLeftAlone() {
            assertThat(service().resolveSignonTarget("COMEN01C")).isEqualTo("COMEN01C");
            assertThat(service().resolveSignonTarget("COUSR00C")).isEqualTo("COUSR00C");
        }

        @Test
        @DisplayName("a mixture of low-values and spaces satisfies neither half")
        void aMixtureSatisfiesNeitherHalf() {
            String mixed = "\u0000\u0000\u0000\u0000    ";

            assertThat(service().resolveSignonTarget(mixed))
                    .as("= LOW-VALUES needs every byte to be zero and = SPACES every byte a space")
                    .isEqualTo(mixed);
        }

        @Test
        @DisplayName("CDEMO-TO-PROGRAM is required")
        void theFieldIsRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> service().resolveSignonTarget(null));
        }
    }

    @Nested
    @DisplayName("BUILD-MENU-OPTIONS L226-L263: the 1-based OCCURS loop and the ten dispatch arms")
    class BuildMenuOptions {

        @Test
        @DisplayName("exactly four lines are composed, each 40 characters, with leading-zero numbers")
        void fourLinesAreComposed() {
            List<String> lines = service().buildMenuOptions(AdminMenuOptionTable.copybook());

            assertThat(lines).hasSize(AdminMenuService.OPTION_LINE_COUNT);
            assertThat(lines).allSatisfy(line -> assertThat(line).hasSize(OPTION_LINE_WIDTH));
            assertThat(lines.get(0))
                    .as("first populated entry - CDEMO-ADMIN-OPT-NUM is PIC 9(02), so '01' not '1'")
                    .isEqualTo("01. User List (Security)                ");
            assertThat(lines.get(3))
                    .as("last populated entry")
                    .isEqualTo("04. User Delete (Security)              ");
        }

        @Test
        @DisplayName("the transcribed literals match character for character")
        void theTranscribedLiteralsMatch() {
            List<String> lines = service().buildMenuOptions(AdminMenuOptionTable.copybook());

            assertThat(OPTION_1_NAME).hasSize(AdminMenuOptions.OPT_NAME_LENGTH);
            assertThat(OPTION_1_LINE).hasSize(OPTION_LINE_WIDTH);
            assertThat(lines.get(0)).isEqualTo(OPTION_1_LINE);
            assertThat(lines.get(3)).isEqualTo(OPTION_4_LINE);

            // The STRING writes 2 + 2 + 35 = 39 characters and stops; character 40 is the space that
            // MOVE SPACES at L231 had already put there.
            int composed = AdminMenuOptions.OPT_NUM_LENGTH
                    + AdminMenuService.OPTION_NUMBER_SEPARATOR.length()
                    + AdminMenuOptions.OPT_NAME_LENGTH;
            assertThat(composed).isEqualTo(39);
            assertThat(lines.get(0).substring(0, composed))
                    .isEqualTo("01" + AdminMenuService.OPTION_NUMBER_SEPARATOR + OPTION_1_NAME);
            assertThat(lines.get(0).charAt(composed))
                    .as("STRING overwrites only what it writes, so character 40 stays a space")
                    .isEqualTo(' ');
        }

        @ParameterizedTest(name = "line {0} is never written by COADM01C")
        @DisplayName("lines 5 to 12 stay blank: the count is 4 and there is no arm past 10")
        @ValueSource(ints = {5, 6, 7, 8, 9, 10, 11, 12})
        void linesBeyondTheActiveCountStayBlank(int cobolSubscript) {
            List<String> lines = service().buildMenuOptions(AdminMenuOptionTable.copybook());

            assertThat(lines.get(cobolSubscript - 1)).isEqualTo(BLANK_OPTION_LINE);
        }

        @Test
        @DisplayName("a subscript past the tenth arm falls to WHEN OTHER CONTINUE and writes nothing")
        void theEleventhAndTwelfthArmsDoNotExist() {
            String[] targets = new String[AdminMenuService.OPTION_LINE_COUNT];
            for (int index = 0; index < targets.length; index++) {
                targets[index] = "COUSR0" + (index % 10) + "C";
            }
            AdminMenuOptionTable wide = stubTable(AdminMenuService.OPTION_LINE_COUNT, targets);

            List<String> lines = service().buildMenuOptions(wide);

            assertThat(lines.get(AdminMenuService.OPTION_DISPATCH_ARM_COUNT - 1))
                    .as("arm 10 exists at L257-L258")
                    .isNotEqualTo(BLANK_OPTION_LINE);
            assertThat(lines.get(AdminMenuService.OPTION_DISPATCH_ARM_COUNT))
                    .as("there is no WHEN 11 in COADM01C, unlike COMEN01C")
                    .isEqualTo(BLANK_OPTION_LINE);
            assertThat(lines.get(AdminMenuService.OPTION_LINE_COUNT - 1))
                    .as("nor a WHEN 12")
                    .isEqualTo(BLANK_OPTION_LINE);
        }

        @Test
        @DisplayName("an active count of zero composes nothing at all")
        void anEmptyMenuComposesNothing() {
            List<String> lines = service().buildMenuOptions(stubTable(0, "COUSR00C"));

            assertThat(lines).containsOnly(BLANK_OPTION_LINE);
        }

        @Test
        @DisplayName("an option table is required")
        void aTableIsRequired() {
            assertThatNullPointerException().isThrownBy(() -> service().buildMenuOptions(null));
        }
    }

    @Nested
    @DisplayName("The option table: nine slots and an active count are two separate facts")
    class OptionTable {

        @Test
        @DisplayName("the copybook table has nine slots and an active count of four")
        void theCopybookTableKeepsBothNumbers() {
            AdminMenuOptionTable table = AdminMenuOptionTable.copybook();

            assertThat(table.slots()).hasSize(AdminMenuOptions.TABLE_SIZE);
            assertThat(table.activeCount()).isEqualTo(AdminMenuOptions.ACTIVE_OPTION_COUNT);
            assertThat(table.slots().subList(4, AdminMenuOptions.TABLE_SIZE))
                    .as("slots 5 to 9 carry no VALUE clause")
                    .allSatisfy(slot -> assertThat(slot).isEmpty());
        }

        @Test
        @DisplayName("a 1-based subscript addresses the entry COBOL addresses")
        void subscriptsAreOneBased() {
            AdminMenuOptionTable table = AdminMenuOptionTable.copybook();

            assertThat(table.optionBySubscript(1)).map(AdminMenuOption::adminOptPgmName)
                    .hasValue("COUSR00C");
            assertThat(table.optionBySubscript(4)).map(AdminMenuOption::adminOptPgmName)
                    .hasValue("COUSR03C");
            assertThat(table.optionBySubscript(AdminMenuOptions.TABLE_SIZE)).isEmpty();
        }

        @ParameterizedTest(name = "subscript {0} is rejected")
        @DisplayName("COBOL has no subscript 0, and none past the table")
        @ValueSource(ints = {0, -1, 10, 99})
        void outOfRangeSubscriptsAreRejected(int subscript) {
            AdminMenuOptionTable table = AdminMenuOptionTable.copybook();

            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> table.optionBySubscript(subscript));
        }

        @Test
        @DisplayName("an active count outside the table is refused, in both directions")
        void anActiveCountOutsideTheTableIsRefused() {
            List<Optional<AdminMenuOption>> slots = AdminMenuOptions.options();

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new AdminMenuOptionTable(slots, -1))
                    .withMessageContaining("CDEMO-ADMIN-OPT-COUNT");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new AdminMenuOptionTable(slots,
                            AdminMenuOptions.TABLE_SIZE + 1))
                    .withMessageContaining("does not address a table");
        }

        @Test
        @DisplayName("an active count that offers an unvalued slot is refused")
        void anOfferedButUnvaluedSlotIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new AdminMenuOptionTable(AdminMenuOptions.options(),
                            AdminMenuOptions.TABLE_SIZE))
                    .withMessageContaining("is absent, yet CDEMO-ADMIN-OPT-COUNT");
        }

        @Test
        @DisplayName("an active count of zero is accepted and the slot list is required")
        void degenerateTablesBehave() {
            assertThat(new AdminMenuOptionTable(AdminMenuOptions.options(), 0).activeCount())
                    .isZero();
            assertThatNullPointerException()
                    .isThrownBy(() -> new AdminMenuOptionTable(null, 0));
        }

        @Test
        @DisplayName("the slot list is copied, so a later change to the caller's list is not seen")
        void theSlotListIsCopied() {
            List<Optional<AdminMenuOption>> mutable = new ArrayList<>(AdminMenuOptions.options());
            AdminMenuOptionTable table = new AdminMenuOptionTable(mutable, 4);

            mutable.clear();

            assertThat(table.slots()).hasSize(AdminMenuOptions.TABLE_SIZE);
        }
    }

    @Nested
    @DisplayName("The outcome carrier: widths, derived views and the dead RESP/RESP2 pair")
    class OutcomeCarrier {

        /** A well-formed outcome to mutate one component of at a time. */
        private AdminMenuOutcome valid() {
            return service().handle(
                    new AdminMenuInput(reenteredContext(), CicsAid.DFHENTER, "01"));
        }

        @Test
        @DisplayName("optionLine is 1-based and rejects anything outside the map")
        void optionLineIsOneBased() {
            AdminMenuOutcome outcome = service().handle(
                    new AdminMenuInput(NavigationContext.empty(), CicsAid.DFHENTER, "  "));

            assertThat(outcome.optionLine(1)).isEqualTo(OPTION_1_LINE);
            assertThat(outcome.optionLine(AdminMenuService.OPTION_LINE_COUNT))
                    .isEqualTo(BLANK_OPTION_LINE);
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> outcome.optionLine(0));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> outcome.optionLine(AdminMenuService.OPTION_LINE_COUNT + 1));
        }

        @Test
        @DisplayName("the line count and every declared width are enforced")
        void theDeclaredWidthsAreEnforced() {
            AdminMenuOutcome template = valid();
            List<String> lines = template.optionLines();

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> rebuild(template, lines.subList(0, 11), template.message(),
                            template.option(), template.nextProgram()))
                    .withMessageContaining("option lines");
            List<String> shortLine = new ArrayList<>(lines);
            shortLine.set(0, "too short");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> rebuild(template, shortLine, template.message(),
                            template.option(), template.nextProgram()))
                    .withMessageContaining("OPTN00nO");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> rebuild(template, lines, "short", template.option(),
                            template.nextProgram()))
                    .withMessageContaining("WS-MESSAGE is PIC X(80)");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> rebuild(template, lines, template.message(), "123",
                            template.nextProgram()))
                    .withMessageContaining("OPTIONO is PIC X(2)");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> rebuild(template, lines, template.message(),
                            template.option(), "TOOLONGNAME"))
                    .withMessageContaining("XCTL target");
        }

        @Test
        @DisplayName("every reference component is required")
        void everyReferenceComponentIsRequired() {
            AdminMenuOutcome template = valid();
            List<String> lines = template.optionLines();

            assertThatNullPointerException().isThrownBy(
                    () -> rebuild(template, null, template.message(), template.option(),
                            template.nextProgram()));
            assertThatNullPointerException().isThrownBy(
                    () -> rebuild(template, lines, null, template.option(),
                            template.nextProgram()));
            assertThatNullPointerException().isThrownBy(
                    () -> rebuild(template, lines, template.message(), null,
                            template.nextProgram()));
            assertThatNullPointerException().isThrownBy(
                    () -> rebuild(template, lines, template.message(), template.option(), null));
            assertThatNullPointerException().isThrownBy(
                    () -> new AdminMenuOutcome(lines, template.message(), template.messageColour(),
                            false, template.option(), template.nextProgram(), false, false, false,
                            null, "CA00", "COADM01", "COADM1A", ReceiveOutcome.NORMAL));
            assertThatNullPointerException().isThrownBy(
                    () -> new AdminMenuOutcome(lines, template.message(), template.messageColour(),
                            false, template.option(), template.nextProgram(), false, false, false,
                            NavigationContext.empty(), null, "COADM01", "COADM1A",
                            ReceiveOutcome.NORMAL));
            assertThatNullPointerException().isThrownBy(
                    () -> new AdminMenuOutcome(lines, template.message(), template.messageColour(),
                            false, template.option(), template.nextProgram(), false, false, false,
                            NavigationContext.empty(), "CA00", null, "COADM1A",
                            ReceiveOutcome.NORMAL));
            assertThatNullPointerException().isThrownBy(
                    () -> new AdminMenuOutcome(lines, template.message(), template.messageColour(),
                            false, template.option(), template.nextProgram(), false, false, false,
                            NavigationContext.empty(), "CA00", "COADM01", null,
                            ReceiveOutcome.NORMAL));
            assertThatNullPointerException().isThrownBy(
                    () -> new AdminMenuOutcome(lines, template.message(), template.messageColour(),
                            false, template.option(), template.nextProgram(), false, false, false,
                            NavigationContext.empty(), "CA00", "COADM01", "COADM1A", null));
        }

        @Test
        @DisplayName("errFlgImage reports the 88-level literal in both states")
        void errFlgImageReportsBothStates() {
            assertThat(service().handle(
                    new AdminMenuInput(reenteredContext(), CicsAid.DFHENTER, "01")).errFlgImage())
                    .isEqualTo("N");
            assertThat(service().handle(
                    new AdminMenuInput(reenteredContext(), CicsAid.DFHENTER, "99")).errFlgImage())
                    .isEqualTo("Y");
        }

        @Test
        @DisplayName("the receive outcome carries RESP and RESP2 and no branch depends on them")
        void theReceiveOutcomeCarriesTheDeadCodes() {
            assertThat(ReceiveOutcome.NORMAL.performed()).isTrue();
            assertThat(ReceiveOutcome.NORMAL.respCode()).isEqualTo(ReceiveOutcome.RESP_NORMAL);
            assertThat(ReceiveOutcome.NORMAL.reasonCode()).isEqualTo(ReceiveOutcome.RESP2_NONE);
            assertThat(ReceiveOutcome.NOT_PERFORMED.performed()).isFalse();
            assertThat(new ReceiveOutcome(true, 13, 26).respCode()).isEqualTo(13);
        }

        /** Rebuilds an outcome with four components replaced, to drive one guard at a time. */
        private AdminMenuOutcome rebuild(AdminMenuOutcome template,
                List<String> optionLines,
                String message,
                String option,
                String nextProgram) {
            return new AdminMenuOutcome(optionLines,
                    message,
                    template.messageColour(),
                    template.errorFlag(),
                    option,
                    nextProgram,
                    template.nextProgramCarriesCommarea(),
                    template.screenPainted(),
                    template.resetAllOutputFields(),
                    template.navigationContext(),
                    template.transactionId(),
                    template.mapsetName(),
                    template.mapName(),
                    template.receive());
        }
    }

    @Nested
    @DisplayName("Program identity, transcribed from the source and the CSD")
    class ProgramIdentity {

        @Test
        @DisplayName("the five names match COADM01C, COADM01.bms and CARDDEMO.CSD")
        void theNamesMatchTheSource() {
            assertThat(AdminMenuService.PROGRAM_NAME).isEqualTo("COADM01C");
            assertThat(AdminMenuService.TRANSACTION_ID).isEqualTo("CA00");
            assertThat(AdminMenuService.MAPSET_NAME).isEqualTo("COADM01");
            assertThat(AdminMenuService.MAP_NAME).isEqualTo("COADM1A");
            assertThat(AdminMenuService.SIGNON_PROGRAM).isEqualTo("COSGN00C");
        }

        @Test
        @DisplayName("the widths match their PICTURE clauses and DFHMDF lengths")
        void theWidthsMatchTheirDeclarations() {
            assertThat(AdminMenuService.MESSAGE_LENGTH).isEqualTo(WS_MESSAGE_WIDTH);
            assertThat(AdminMenuService.OPTION_TEXT_LENGTH).isEqualTo(OPTION_LINE_WIDTH);
            assertThat(AdminMenuService.OPTION_X_LENGTH).isEqualTo(2);
            assertThat(AdminMenuService.OPTION_DIGITS).isEqualTo(2);
            assertThat(AdminMenuService.OPTION_LENGTH).isEqualTo(2);
            assertThat(AdminMenuService.OPTION_LINE_COUNT).isEqualTo(12);
            assertThat(AdminMenuService.OPTION_DISPATCH_ARM_COUNT).isEqualTo(10);
            assertThat(AdminMenuService.DUMMY_PREFIX_LENGTH).isEqualTo(5);
            assertThat(AdminMenuService.USRSEC_FILE_NAME_LENGTH).isEqualTo(PROGRAM_NAME_WIDTH);
        }

        @Test
        @DisplayName("WS-MESSAGE at 80 is none of CCDA at 50 nor ERRMSGO at 78")
        void theThreeMessageWidthsAreDistinct() {
            assertThat(AdminMenuService.MESSAGE_LENGTH)
                    .isNotEqualTo(SystemMessages.MESSAGE_LENGTH)
                    .isNotEqualTo(78);
            assertThat(SystemMessages.MESSAGE_LENGTH).isEqualTo(50);
        }

        @Test
        @DisplayName("the two colours are the map's declared red and the L148 green override")
        void theTwoColoursAreTheMapsAndTheOverride() {
            assertThat(AdminMenuService.MAP_MESSAGE_COLOUR).isEqualTo(BmsAttributes.DFHRED);
            assertThat(AdminMenuService.COMING_SOON_MESSAGE_COLOUR)
                    .isEqualTo(BmsAttributes.DFHGREEN);
            assertThat(AdminMenuService.ERR_FLG_ON).isEqualTo("Y");
            assertThat(AdminMenuService.ERR_FLG_OFF).isEqualTo("N");
            assertThat(AdminMenuService.ZERO_OPTION).isZero();
            assertThat(AdminMenuService.DUMMY_PROGRAM_PREFIX)
                    .isEqualTo("DUMMY")
                    .hasSize(AdminMenuService.DUMMY_PREFIX_LENGTH);
            assertThat(AdminMenuService.INVALID_OPTION_MESSAGE).isEqualTo(INVALID_OPTION_TEXT);
            assertThat(AdminMenuService.OPTION_NUMBER_SEPARATOR).isEqualTo(". ");
            assertThat(AdminMenuService.USRSEC_DATASET_KEY).isEqualTo("USRSEC");
        }
    }
}
