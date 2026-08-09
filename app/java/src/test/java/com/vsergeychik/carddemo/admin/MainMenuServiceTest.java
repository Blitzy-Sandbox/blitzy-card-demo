package com.vsergeychik.carddemo.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.vsergeychik.carddemo.admin.MainMenuService.MainMenuInput;
import com.vsergeychik.carddemo.admin.MainMenuService.MainMenuOptionTable;
import com.vsergeychik.carddemo.admin.MainMenuService.MainMenuOutcome;
import com.vsergeychik.carddemo.admin.MainMenuService.OptionNormalisation;
import com.vsergeychik.carddemo.admin.MainMenuService.ReceiveOutcome;
import com.vsergeychik.carddemo.admin.model.MenuOptions;
import com.vsergeychik.carddemo.admin.model.MenuOptions.MenuOption;
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
import java.util.Collections;
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
 * Tests for {@link MainMenuService}, the decision core of {@code app/cbl/COMEN01C.cbl}.
 *
 * <h2>Deliberately no HTTP anywhere</h2>
 * Every assertion below drives the service by constructing it directly and calling a method. There is
 * no {@code MockMvc}, no {@code WebApplicationContext}, no {@code @SpringBootTest} and no
 * {@code JobLauncher}. That is the point of the class under test: the branches of {@code COMEN01C} are
 * reachable from a plain JUnit test, which is what makes the per-package branch-coverage gate
 * satisfiable and what makes a failure point at one statement of one paragraph.
 *
 * <h2>Every expected value is transcribed from the source, never read back off the class</h2>
 * The literals, widths and program names below are typed out from {@code app/cbl/COMEN01C.cbl},
 * {@code app/cpy/COMEN02Y.cpy}, {@code app/cpy/CSMSG01Y.cpy}, {@code app/cpy-bms/COMEN01.CPY} and
 * {@code app/bms/COMEN01.bms}, so a drift in either direction is caught. Asserting a constant against
 * itself would prove nothing.
 *
 * <h2>The authorisation filter gets the densest coverage</h2>
 * {@code COMEN01C}'s user-type filter at lines 136 to 143 is the one decision in this package with no
 * sibling counterpart, it is <em>not</em> guarded by the error flag, and it subscripts an
 * {@code OCCURS} table with a value the validation above it may already have rejected. It therefore
 * gets its own nested class, driven at subscripts 0, 1, 10, 11, 12 and 99, at both truth states of
 * {@code 88 CDEMO-USRTYP-USER}, and with a stub entry that makes its true arm fire.
 */
@DisplayName("MainMenuService - the decision core of COMEN01C")
class MainMenuServiceTest {

    /** {@code app/cbl/COMEN01C.cbl:38} - {@code WS-MESSAGE PIC X(80)}. */
    private static final int WS_MESSAGE_WIDTH = 80;

    /** {@code app/cpy-bms/COMEN01.CPY:182} - {@code OPTN001O PIC X(40)}. */
    private static final int OPTION_LINE_WIDTH = 40;

    /** {@code app/cpy/COCOM01Y.cpy:24} - {@code CDEMO-TO-PROGRAM PIC X(08)}. */
    private static final int PROGRAM_NAME_WIDTH = 8;

    /** {@code app/cpy/COMEN02Y.cpy:90} - {@code CDEMO-MENU-OPT-NAME PIC X(35)}. */
    private static final int OPTION_NAME_WIDTH = 35;

    /** Eighty spaces: {@code WS-MESSAGE} as {@code app/cbl/COMEN01C.cbl:79} leaves it. */
    private static final String BLANK_MESSAGE = " ".repeat(WS_MESSAGE_WIDTH);

    /** Forty spaces: an {@code OPTN00nO} line the program never writes. */
    private static final String BLANK_OPTION_LINE = " ".repeat(OPTION_LINE_WIDTH);

    /** Eight spaces: an {@code XCTL} target on a path that does not transfer. */
    private static final String NO_NEXT_PROGRAM = " ".repeat(PROGRAM_NAME_WIDTH);

    /** {@code app/cbl/COMEN01C.cbl:131}, transcribed - 37 characters. */
    private static final String INVALID_OPTION_TEXT = "Please enter a valid option number...";

    /**
     * {@code app/cbl/COMEN01C.cbl:140}, transcribed - 33 characters, and the trailing space is real.
     */
    private static final String NO_ACCESS_TEXT = "No access - Admin Only option... ";

    /** {@code app/cpy/COMEN02Y.cpy:26-27} - {@code PIC X(35)}, transcribed. */
    private static final String OPTION_1_NAME = "Account View                       ";

    /** {@code app/cpy/COMEN02Y.cpy:38-39} - {@code PIC X(35)}, transcribed. */
    private static final String OPTION_3_NAME = "Credit Card List                   ";

    /**
     * {@code app/cpy/COMEN02Y.cpy:70} - the <strong>live</strong> literal, {@code PIC X(35)}.
     *
     * <p>Line 69 immediately above it holds a commented-out
     * {@code 'Transaction Add (Admin Only)       '}, which is also 35 characters and would therefore
     * pass every width check. Only the live one is correct, and this constant is what proves the trap
     * was avoided.
     */
    private static final String OPTION_8_NAME = "Transaction Add                    ";

    /** {@code app/cpy/COMEN02Y.cpy:81-82} - {@code PIC X(35)}, transcribed. */
    private static final String OPTION_10_NAME = "Bill Payment                       ";

    /** The commented-out alternative at {@code app/cpy/COMEN02Y.cpy:69}, which must never appear. */
    private static final String OPTION_8_COMMENTED_NAME = "Transaction Add (Admin Only)       ";

    /** The composed {@code OPTN001O} line: {@code 01} then {@code '. '} then the 35-byte name. */
    private static final String OPTION_1_LINE = "01. " + OPTION_1_NAME + " ";

    /** The composed {@code OPTN010O} line - the last one the active count reaches. */
    private static final String OPTION_10_LINE = "10. " + OPTION_10_NAME + " ";

    /**
     * The "coming soon" text for option 1, composed exactly as
     * {@code app/cbl/COMEN01C.cbl:159-163} composes it.
     *
     * <p>Note what is <strong>not</strong> here: a space before {@code is}, and any word of the name
     * after the first. {@code DELIMITED BY SPACE} stops at the first space of
     * {@code 'Account View'} and {@code 'is coming soon ...'} has no leading space.
     */
    private static final String COMING_SOON_OPTION_1 = "This option Accountis coming soon ...";

    /** The same for option 3, whose name's first word is {@code Credit}. */
    private static final String COMING_SOON_OPTION_3 = "This option Creditis coming soon ...";

    /** The same for option 10, whose name's first word is {@code Bill}. */
    private static final String COMING_SOON_OPTION_10 = "This option Billis coming soon ...";

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
        catalogue.put(MainMenuService.USRSEC_DATASET_KEY,
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
    private static MainMenuService service() {
        return new MainMenuService(bindings());
    }

    /**
     * A communication area in {@code CDEMO-PGM-REENTER} state carrying user type {@code 'U'} - the
     * state {@code COSGN00C} hands a regular user to {@code COMEN01C} in, after one keystroke.
     */
    private static NavigationContext userReenteredContext() {
        return NavigationContext.empty().withPgmReenter().withUserTypeUser();
    }

    /** The same, but carrying user type {@code 'A'} so the filter's first conjunct is false. */
    private static NavigationContext adminReenteredContext() {
        return NavigationContext.empty().withPgmReenter().withUserTypeAdmin();
    }

    /**
     * A stub option table, so branches the real ten-entry all-{@code 'U'} table cannot reach become
     * reachable - the {@value MainMenuService#DUMMY_PROGRAM_PREFIX} prefix and the true arm of the
     * authorisation filter.
     *
     * @param activeCount  {@code CDEMO-MENU-OPT-COUNT}
     * @param slotCount    how many {@code OCCURS} slots the stub declares
     * @param programNames the target program name of each valued slot, in subscript order
     * @param usrTypes     the {@code CDEMO-MENU-OPT-USRTYPE} of each valued slot, in the same order
     * @return the table, with any slot beyond {@code programNames} left absent
     */
    private static MainMenuOptionTable stubTable(int activeCount,
            int slotCount,
            String[] programNames,
            String[] usrTypes) {
        List<Optional<MenuOption>> slots = new ArrayList<>();
        for (int index = 0; index < slotCount; index++) {
            if (index < programNames.length) {
                slots.add(Optional.of(MenuOption.of(index + 1,
                        "Stub option " + (index + 1),
                        programNames[index],
                        usrTypes[index])));
            } else {
                slots.add(Optional.empty());
            }
        }
        return new MainMenuOptionTable(slots, activeCount);
    }

    /** A one-slot stub whose single entry is a regular-user option with the given target program. */
    private static MainMenuOptionTable stubTable(String programName) {
        return stubTable(1, 1, new String[] {programName},
                new String[] {NavigationContext.USER_TYPE_USER});
    }

    @Nested
    @DisplayName("Construction: the dead WS-USRSEC-FILE declaration, resolved from configuration")
    class Construction {

        @Test
        @DisplayName("WS-USRSEC-FILE is the eight-byte logical name 'USRSEC  ', with its two spaces")
        void usrSecFileNameIsTheEightByteLogicalName() {
            assertThat(service().usrSecFileName())
                    .as("app/cbl/COMEN01C.cbl:39 declares PIC X(08) VALUE 'USRSEC  '")
                    .isEqualTo("USRSEC  ")
                    .hasSize(MainMenuService.USRSEC_FILE_NAME_LENGTH);
        }

        @Test
        @DisplayName("no dataset name reaches the service - only the catalogue key does")
        void theConfiguredDatasetNameIsNeverRead() {
            assertThat(service().usrSecFileName()).doesNotContain("CARDDEMO.TEST.USRSEC");
        }

        @Test
        @DisplayName("SEC-USER-DATA is declared and blank, and nothing reads it")
        void secUserDataIsADeclaredButUnusedRecord() {
            SecUserRecord declared = service().secUserData();
            assertThat(declared).isEqualTo(SecUserRecord.blank());
            assertThat(declared.secUsrId()).isBlank();
            assertThat(declared.secUsrPwd()).isBlank();
            assertThat(declared.secUsrType()).isBlank();
        }

        @Test
        @DisplayName("the codec is the one supplied, and is exposed for the controller's X(80)->X(78)")
        void theCodecIsExposed() {
            FixedWidthCodec supplied = new FixedWidthCodec(Charset.forName("IBM037"));
            assertThat(new MainMenuService(bindings(), supplied).codec()).isSameAs(supplied);
            assertThat(service().codec().charset().name())
                    .isEqualTo(MainMenuService.DEFAULT_MESSAGE_CHARSET_NAME);
        }

        @Test
        @DisplayName("a USRSEC binding of the wrong record width refuses to start")
        void aDisagreeingRecordWidthIsRejected() {
            DatasetBindings wrong = bindings(SecUserRecord.RECORD_LENGTH + 1);
            assertThatIllegalStateException()
                    .isThrownBy(() -> new MainMenuService(wrong))
                    .withMessageContaining("record length of 81")
                    .withMessageContaining("app/cpy/CSUSR01Y.cpy");
        }

        @Test
        @DisplayName("an unconfigured USRSEC key refuses to start")
        void anAbsentBindingIsRejected() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> new MainMenuService(new DatasetBindings()))
                    .withMessageContaining(MainMenuService.USRSEC_DATASET_KEY);
        }

        @Test
        @DisplayName("both constructor arguments are required")
        void bothConstructorArgumentsAreRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new MainMenuService(null))
                    .withMessageContaining("carddemo.datasets");
            assertThatNullPointerException()
                    .isThrownBy(() -> new MainMenuService(bindings(), null))
                    .withMessageContaining("fixed-width codec");
        }

        @Test
        @DisplayName("the program identity is COMEN01C / CM00 / COMEN1A / COMEN01")
        void theProgramIdentityIsTranscribedFromTheSource() {
            assertThat(MainMenuService.PROGRAM_NAME).isEqualTo("COMEN01C");
            assertThat(MainMenuService.TRANSACTION_ID).isEqualTo("CM00");
            assertThat(MainMenuService.MAP_NAME).isEqualTo("COMEN1A");
            assertThat(MainMenuService.MAPSET_NAME).isEqualTo("COMEN01");
            assertThat(MainMenuService.SIGNON_PROGRAM).isEqualTo("COSGN00C");
        }

        @Test
        @DisplayName("every emitted literal is exactly the length the source declares")
        void everyEmittedLiteralIsAtItsVerifiedLength() {
            assertThat(MainMenuService.INVALID_OPTION_MESSAGE).isEqualTo(INVALID_OPTION_TEXT)
                    .hasSize(37);
            assertThat(MainMenuService.NO_ACCESS_MESSAGE).isEqualTo(NO_ACCESS_TEXT).hasSize(33)
                    .endsWith(" ");
            assertThat(MainMenuService.COMING_SOON_PREFIX).isEqualTo("This option ").hasSize(12);
            assertThat(MainMenuService.COMING_SOON_SUFFIX).isEqualTo("is coming soon ...").hasSize(18)
                    .doesNotStartWith(" ");
            assertThat(MainMenuService.OPTION_NUMBER_SEPARATOR).isEqualTo(". ");
            assertThat(MainMenuService.DUMMY_PROGRAM_PREFIX).isEqualTo("DUMMY")
                    .hasSize(MainMenuService.DUMMY_PREFIX_LENGTH);
            assertThat(MainMenuService.ADMIN_ONLY_USRTYPE).isEqualTo("A");
            assertThat(MainMenuService.ERR_FLG_ON).isEqualTo("Y");
            assertThat(MainMenuService.ERR_FLG_OFF).isEqualTo("N");
            assertThat(MainMenuService.ZERO_OPTION).isZero();
        }

        @Test
        @DisplayName("the dispatch has TWELVE arms here, unlike COADM01C's ten")
        void theDispatchHasTwelveArms() {
            assertThat(MainMenuService.OPTION_DISPATCH_ARM_COUNT)
                    .as("app/cbl/COMEN01C.cbl:249-272 runs WHEN 1 through WHEN 12")
                    .isEqualTo(12)
                    .isEqualTo(MainMenuService.OPTION_LINE_COUNT);
            assertThat(AdminMenuService.OPTION_DISPATCH_ARM_COUNT)
                    .as("app/cbl/COADM01C.cbl stops at WHEN 10 - the two programs differ")
                    .isEqualTo(10);
        }

        @Test
        @DisplayName("the map's ERRMSG colour is red, and green is the program's only override")
        void theColourConstantsComeFromTheMapAndTheProgram() {
            assertThat(MainMenuService.MAP_MESSAGE_COLOUR)
                    .as("app/bms/COMEN01.bms:154-157 declares COLOR=RED")
                    .isEqualTo(BmsAttributes.DFHRED);
            assertThat(MainMenuService.COMING_SOON_MESSAGE_COLOUR)
                    .as("app/cbl/COMEN01C.cbl:158 MOVE DFHGREEN TO ERRMSGC")
                    .isEqualTo(BmsAttributes.DFHGREEN);
        }
    }

    @Nested
    @DisplayName("MAIN-PARA L82-L84: EIBCALEN = 0 sets CDEMO-FROM-PROGRAM, not TO")
    class AbsentCommarea {

        @Test
        @DisplayName("line 83 writes FROM-PROGRAM; the TO-PROGRAM default comes from line 173")
        void anAbsentCommareaWritesFromProgramAndDefaultsToProgram() {
            MainMenuOutcome outcome = service()
                    .handle(MainMenuInput.withoutCommarea(CicsAid.DFHENTER, "  "));

            assertThat(outcome.navigationContext().fromProgram())
                    .as("app/cbl/COMEN01C.cbl:83 - MOVE 'COSGN00C' TO CDEMO-FROM-PROGRAM")
                    .isEqualTo("COSGN00C");
            assertThat(outcome.nextProgram()).isEqualTo("COSGN00C");
            assertThat(outcome.hasNextProgram()).isTrue();
            assertThat(outcome.nextProgramCarriesCommarea())
                    .as("app/cbl/COMEN01C.cbl:175-177 specifies no COMMAREA")
                    .isFalse();
            assertThat(outcome.screenPainted()).isFalse();
            assertThat(outcome.resetAllOutputFields()).isFalse();
            assertThat(outcome.message()).isEqualTo(BLANK_MESSAGE);
            assertThat(outcome.errorFlag()).isFalse();
            assertThat(outcome.errFlgImage()).isEqualTo("N");
            assertThat(outcome.receive()).isEqualTo(ReceiveOutcome.NOT_PERFORMED);
            assertThat(outcome.optionLines()).containsOnly(BLANK_OPTION_LINE);
        }

        @Test
        @DisplayName("the attention identifier is never consulted on this path")
        void theAidIsImmaterialWithNoCommarea() {
            MainMenuOutcome viaEnter = service()
                    .handle(MainMenuInput.withoutCommarea(CicsAid.DFHENTER, "01"));
            MainMenuOutcome viaGarbage = service()
                    .handle(MainMenuInput.withoutCommarea(UNRESOLVABLE_AID, "01"));

            assertThat(viaEnter.nextProgram()).isEqualTo(viaGarbage.nextProgram());
            assertThat(viaEnter.errorFlag()).isEqualTo(viaGarbage.errorFlag());
        }

        @Test
        @DisplayName("an absent communication area is EIBCALEN = 0, and reports itself as such")
        void isCommareaPresentReportsTheAbsence() {
            MainMenuInput cold = MainMenuInput.withoutCommarea(CicsAid.DFHENTER, "  ");
            assertThat(cold.isCommareaPresent()).isFalse();
            assertThat(cold.isReenter()).isFalse();
            assertThat(cold.navigationContext()).isNull();
        }
    }

    @Nested
    @DisplayName("MAIN-PARA L87-L90: first entry paints, resets and flips to REENTER")
    class FirstEntry {

        @Test
        @DisplayName("context 0 paints the menu, clears every output field and reads no option")
        void firstEntryPaintsAndResets() {
            MainMenuOutcome outcome = service().handle(
                    new MainMenuInput(NavigationContext.empty(), CicsAid.DFHENTER, "07"));

            assertThat(outcome.screenPainted()).isTrue();
            assertThat(outcome.resetAllOutputFields())
                    .as("app/cbl/COMEN01C.cbl:89 - MOVE LOW-VALUES TO COMEN1AO")
                    .isTrue();
            assertThat(outcome.navigationContext().isReenter())
                    .as("app/cbl/COMEN01C.cbl:88 - SET CDEMO-PGM-REENTER TO TRUE")
                    .isTrue();
            assertThat(outcome.option())
                    .as("OPTIONO is one of the fields LOW-VALUES cleared, so the echo is blank")
                    .isEqualTo("  ");
            assertThat(outcome.message()).isEqualTo(BLANK_MESSAGE);
            assertThat(outcome.errorFlag()).isFalse();
            assertThat(outcome.hasNextProgram()).isFalse();
            assertThat(outcome.nextProgram()).isEqualTo(NO_NEXT_PROGRAM);
            assertThat(outcome.receive().performed()).isFalse();
            assertThat(outcome.transactionId()).isEqualTo("CM00");
            assertThat(outcome.mapName()).isEqualTo("COMEN1A");
            assertThat(outcome.mapsetName()).isEqualTo("COMEN01");
        }

        @ParameterizedTest(name = "CDEMO-PGM-CONTEXT = {0} takes the first-entry path")
        @ValueSource(ints = {0, 2, 5, 9})
        @DisplayName("IF NOT CDEMO-PGM-REENTER is not 'is enter': any non-1 digit paints")
        void anyContextOtherThanOneTakesTheFirstEntryPath(int pgmContext) {
            MainMenuOutcome outcome = service().handle(new MainMenuInput(
                    NavigationContext.empty().withPgmContext(pgmContext), CicsAid.DFHENTER, "01"));

            assertThat(outcome.screenPainted()).isTrue();
            assertThat(outcome.resetAllOutputFields()).isTrue();
            assertThat(outcome.hasNextProgram()).isFalse();
        }

        @Test
        @DisplayName("a re-entered context does not take the first-entry path")
        void aReenteredContextIsNotFirstEntry()
        {
            MainMenuInput input =
                    new MainMenuInput(userReenteredContext(), CicsAid.DFHENTER, "01");
            assertThat(input.isReenter()).isTrue();
            assertThat(service().handle(input).resetAllOutputFields()).isFalse();
        }
    }

    @Nested
    @DisplayName("MAIN-PARA L93-L103: the three arms of EVALUATE EIBAID, in source order")
    class AidEvaluate {

        @Test
        @DisplayName("WHEN DFHENTER performs PROCESS-ENTER-KEY")
        void enterProcessesTheOption() {
            MainMenuOutcome outcome = service().handle(
                    new MainMenuInput(userReenteredContext(), CicsAid.DFHENTER, "01"));

            assertThat(outcome.hasNextProgram()).isTrue();
            assertThat(outcome.nextProgram()).isEqualTo("COACTVWC");
            assertThat(outcome.receive().performed()).isTrue();
            assertThat(outcome.receive()).isEqualTo(ReceiveOutcome.NORMAL);
        }

        @Test
        @DisplayName("WHEN DFHPF3 writes CDEMO-TO-PROGRAM and leaves with no COMMAREA")
        void pf3ReturnsToSignon() {
            MainMenuOutcome outcome = service().handle(
                    new MainMenuInput(userReenteredContext(), CicsAid.DFHPF3, "01"));

            assertThat(outcome.navigationContext().toProgram())
                    .as("app/cbl/COMEN01C.cbl:97 - MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM")
                    .isEqualTo("COSGN00C");
            assertThat(outcome.nextProgram()).isEqualTo("COSGN00C");
            assertThat(outcome.nextProgramCarriesCommarea()).isFalse();
            assertThat(outcome.screenPainted()).isFalse();
            assertThat(outcome.errorFlag()).isFalse();
            assertThat(outcome.message()).isEqualTo(BLANK_MESSAGE);
            assertThat(outcome.option())
                    .as("OPTIONO is not written on this path; the client's value survives")
                    .isEqualTo("01");
        }

        @ParameterizedTest(name = "AID 0x{0} falls to WHEN OTHER")
        @CsvSource({"F4", "F5", "6D", "01", "00", "7A"})
        @DisplayName("WHEN OTHER raises the invalid-key message, widened X(50) -> X(80)")
        void anyOtherAidRaisesTheInvalidKeyMessage(String hexAid) {
            byte aid = (byte) Integer.parseInt(hexAid, 16);
            MainMenuOutcome outcome =
                    service().handle(new MainMenuInput(userReenteredContext(), aid, "01"));

            assertThat(outcome.errorFlag()).isTrue();
            assertThat(outcome.errFlgImage()).isEqualTo("Y");
            assertThat(outcome.message())
                    .as("app/cbl/COMEN01C.cbl:101 into WS-MESSAGE PIC X(80)")
                    .hasSize(WS_MESSAGE_WIDTH)
                    .startsWith("Invalid key pressed. Please see below...")
                    .isEqualTo(SystemMessages.CCDA_MSG_INVALID_KEY
                            + " ".repeat(WS_MESSAGE_WIDTH - SystemMessages.MESSAGE_LENGTH));
            assertThat(outcome.screenPainted()).isTrue();
            assertThat(outcome.messageColour()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(outcome.messageColourOverridden()).isFalse();
            assertThat(outcome.hasNextProgram()).isFalse();
        }

        @Test
        @DisplayName("the two arms are chosen by AID byte, so PF15 does not behave as PF3")
        void pf15IsNotPf3() {
            byte pf15 = (byte) 0xC3;
            MainMenuOutcome outcome =
                    service().handle(new MainMenuInput(userReenteredContext(), pf15, "01"));
            assertThat(outcome.errorFlag())
                    .as("COMEN01C does not copy CSSTRPFY, so PF15 is just another AID")
                    .isTrue();
            assertThat(outcome.hasNextProgram()).isFalse();
        }

        @Test
        @DisplayName("both handle overloads require their arguments")
        void handleRequiresItsArguments() {
            MainMenuService service = service();
            assertThatNullPointerException().isThrownBy(() -> service.handle(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> service.handle(null, MainMenuOptionTable.copybook()));
            assertThatNullPointerException().isThrownBy(() -> service.handle(
                    MainMenuInput.withoutCommarea(CicsAid.DFHENTER, "  "), null));
        }
    }

    @Nested
    @DisplayName("PROCESS-ENTER-KEY L117-L125: the four-step option normalisation")
    class Normalisation {

        @ParameterizedTest(name = "OPTIONI \"{0}\" -> WS-IDX {1}, step 3 \"{2}\", step 4 \"{3}\"")
        @CsvSource({
            "'  ', 1, '  ', '00', 0",
            "' 3', 2, ' 3', '03', 3",
            "'3 ', 1, ' 3', '03', 3",
            "'12', 2, '12', '12', 12",
        })
        @DisplayName("the four numeric cases, asserting every intermediate and not just the integer")
        void theFourNumericCases(String received, int wsIdx, String justified, String inspected,
                int option) {
            OptionNormalisation normalisation = service().normaliseOption(received);

            assertThat(normalisation.wsIdx()).isEqualTo(wsIdx);
            assertThat(normalisation.receivedOptionI()).isEqualTo(received);
            assertThat(normalisation.justifiedOptionX())
                    .as("app/cbl/COMEN01C.cbl:45 - WS-OPTION-X PIC X(02) JUST RIGHT")
                    .isEqualTo(justified);
            assertThat(normalisation.optionX())
                    .as("app/cbl/COMEN01C.cbl:123 - INSPECT REPLACING ALL ' ' BY '0'")
                    .isEqualTo(inspected);
            assertThat(normalisation.option()).hasValue(option);
            assertThat(normalisation.isNumeric()).isTrue();
            assertThat(normalisation.optionEcho()).isEqualTo(inspected);
        }

        @ParameterizedTest(name = "OPTIONI \"{0}\" is not numeric")
        @CsvSource({"'1x', '1x'", "'1!', '1!'", "'xx', 'xx'"})
        @DisplayName("a non-digit survives the MOVE, which is what line 127 detects")
        void aNonDigitIsReportedAsNotNumeric(String received, String expectedImage) {
            OptionNormalisation normalisation = service().normaliseOption(received);

            assertThat(normalisation.optionX()).isEqualTo(expectedImage);
            assertThat(normalisation.option()).isEmpty();
            assertThat(normalisation.isNumeric()).isFalse();
        }

        @Test
        @DisplayName("a value wider or narrower than PIC X(2) is moved into that width first")
        void theReceivedValueIsMovedIntoItsDeclaredWidth() {
            assertThat(service().normaliseOption("123").receivedOptionI())
                    .as("an alphanumeric MOVE truncates on the right")
                    .isEqualTo("12");
            assertThat(service().normaliseOption("").receivedOptionI())
                    .as("and pads on the right")
                    .isEqualTo("  ");
            assertThat(service().normaliseOption("").option()).hasValue(0);
        }

        @Test
        @DisplayName("OPTIONI is never null")
        void nullIsRejected() {
            MainMenuService service = service();
            assertThatNullPointerException().isThrownBy(() -> service.normaliseOption(null));
            assertThatNullPointerException().isThrownBy(
                    () -> new MainMenuInput(NavigationContext.empty(), CicsAid.DFHENTER, null));
        }
    }

    @Nested
    @DisplayName("PROCESS-ENTER-KEY L127-L134: the three validation terms, each on its own")
    class Validation {

        private void assertInvalidOption(MainMenuOutcome outcome) {
            assertThat(outcome.errorFlag()).isTrue();
            assertThat(outcome.message())
                    .hasSize(WS_MESSAGE_WIDTH)
                    .isEqualTo(INVALID_OPTION_TEXT
                            + " ".repeat(WS_MESSAGE_WIDTH - INVALID_OPTION_TEXT.length()));
            assertThat(outcome.screenPainted()).isTrue();
            assertThat(outcome.hasNextProgram()).isFalse();
            assertThat(outcome.messageColour()).isEqualTo(BmsAttributes.DFHRED);
        }

        @Test
        @DisplayName("term 1, L127: WS-OPTION IS NOT NUMERIC")
        void aNonNumericOptionIsRejected() {
            assertInvalidOption(service().handle(
                    new MainMenuInput(userReenteredContext(), CicsAid.DFHENTER, "1x")));
        }

        @Test
        @DisplayName("term 2, L128: WS-OPTION > CDEMO-MENU-OPT-COUNT")
        void anOptionAboveTheActiveCountIsRejected() {
            assertInvalidOption(service().handle(
                    new MainMenuInput(userReenteredContext(), CicsAid.DFHENTER, "11")));
        }

        @Test
        @DisplayName("term 3, L129: WS-OPTION = ZEROS")
        void zeroIsRejected() {
            assertInvalidOption(service().handle(
                    new MainMenuInput(userReenteredContext(), CicsAid.DFHENTER, "00")));
            assertInvalidOption(service().handle(
                    new MainMenuInput(userReenteredContext(), CicsAid.DFHENTER, "  ")));
        }

        @Test
        @DisplayName("the rejected option is still echoed to OPTIONO, normalised")
        void theRejectedOptionIsEchoed() {
            MainMenuOutcome outcome = service().handle(
                    new MainMenuInput(userReenteredContext(), CicsAid.DFHENTER, "11"));
            assertThat(outcome.option()).isEqualTo("11");
        }

        @ParameterizedTest(name = "option {0} passes validation against the copybook count of 10")
        @ValueSource(strings = {"01", "05", "10", " 1", "1 "})
        @DisplayName("1 through the active count pass, and are dispatched")
        void everyActiveOptionPassesValidation(String option) {
            MainMenuOutcome outcome = service().handle(
                    new MainMenuInput(userReenteredContext(), CicsAid.DFHENTER, option));
            assertThat(outcome.errorFlag()).isFalse();
            assertThat(outcome.hasNextProgram()).isTrue();
        }
    }

    @Nested
    @DisplayName("PROCESS-ENTER-KEY L136-L143: THE USER-TYPE AUTHORISATION FILTER")
    class AuthorisationFilter {

        @ParameterizedTest(name = "WS-OPTION = {0} evaluates the filter without throwing")
        @ValueSource(ints = {0, 1, 10, 11, 12, 99, 13, -1, Integer.MAX_VALUE})
        @DisplayName("the bounded subscript never throws, whatever value the ungated filter sees")
        void theBoundedSubscriptNeverThrows(int wsOption) {
            MainMenuService service = service();
            MainMenuOptionTable table = MainMenuOptionTable.copybook();

            assertThatNoException().isThrownBy(
                    () -> service.isAdminOnlyOption(table, OptionalInt.of(wsOption)));
            assertThat(service.isAdminOnlyOption(table, OptionalInt.of(wsOption)))
                    .as("every COMEN02Y entry carries 'U', 11 and 12 carry nothing, and 0 is below "
                            + "the table, so line 137 is false for every reachable subscript")
                    .isFalse();
        }

        @Test
        @DisplayName("a non-numeric WS-OPTION has no subscript at all, and the filter is false")
        void anAbsentSubscriptIsFalse() {
            assertThat(service().isAdminOnlyOption(MainMenuOptionTable.copybook(),
                    OptionalInt.empty())).isFalse();
        }

        @Test
        @DisplayName("an entry whose CDEMO-MENU-OPT-USRTYPE is 'A' makes the filter true")
        void anAdminOnlyEntryMakesTheFilterTrue() {
            MainMenuOptionTable adminOnly = stubTable(1, 1, new String[] {"COUSR00C"},
                    new String[] {NavigationContext.USER_TYPE_ADMIN});
            assertThat(service().isAdminOnlyOption(adminOnly, OptionalInt.of(1))).isTrue();
        }

        @Test
        @DisplayName("the filter's arguments are required")
        void theFilterRequiresItsArguments() {
            MainMenuService service = service();
            assertThatNullPointerException().isThrownBy(
                    () -> service.isAdminOnlyOption(null, OptionalInt.of(1)));
            assertThatNullPointerException().isThrownBy(
                    () -> service.isAdminOnlyOption(MainMenuOptionTable.copybook(), null));
        }

        @Test
        @DisplayName("88 CDEMO-USRTYP-USER true + an 'A' entry -> the 33-character No access message")
        void aRegularUserIsRefusedAnAdminOnlyOption() {
            MainMenuOptionTable adminOnly = stubTable(1, 1, new String[] {"COUSR00C"},
                    new String[] {NavigationContext.USER_TYPE_ADMIN});

            MainMenuOutcome outcome = service().handle(
                    new MainMenuInput(userReenteredContext(), CicsAid.DFHENTER, "01"), adminOnly);

            assertThat(outcome.errorFlag())
                    .as("app/cbl/COMEN01C.cbl:138 - SET ERR-FLG-ON TO TRUE")
                    .isTrue();
            assertThat(outcome.message())
                    .hasSize(WS_MESSAGE_WIDTH)
                    .isEqualTo(NO_ACCESS_TEXT
                            + " ".repeat(WS_MESSAGE_WIDTH - NO_ACCESS_TEXT.length()));
            assertThat(outcome.screenPainted()).isTrue();
            assertThat(outcome.hasNextProgram())
                    .as("the dispatch at line 145 is suppressed by the flag")
                    .isFalse();
            assertThat(outcome.messageColour()).isEqualTo(BmsAttributes.DFHRED);
        }

        @Test
        @DisplayName("88 CDEMO-USRTYP-USER false: an administrator is never refused")
        void anAdministratorIsNeverRefused() {
            MainMenuOptionTable adminOnly = stubTable(1, 1, new String[] {"COUSR00C"},
                    new String[] {NavigationContext.USER_TYPE_ADMIN});

            MainMenuOutcome outcome = service().handle(
                    new MainMenuInput(adminReenteredContext(), CicsAid.DFHENTER, "01"), adminOnly);

            assertThat(outcome.errorFlag()).isFalse();
            assertThat(outcome.hasNextProgram()).isTrue();
            assertThat(outcome.nextProgram()).isEqualTo("COUSR00C");
        }

        @Test
        @DisplayName("a blank user type satisfies neither 88, so the filter does not fire")
        void aBlankUserTypeSatisfiesNeitherCondition() {
            MainMenuOptionTable adminOnly = stubTable(1, 1, new String[] {"COUSR00C"},
                    new String[] {NavigationContext.USER_TYPE_ADMIN});
            NavigationContext blank = NavigationContext.empty().withPgmReenter();

            assertThat(blank.isUser()).isFalse();
            assertThat(blank.isAdmin()).isFalse();
            MainMenuOutcome outcome = service()
                    .handle(new MainMenuInput(blank, CicsAid.DFHENTER, "01"), adminOnly);
            assertThat(outcome.errorFlag()).isFalse();
            assertThat(outcome.hasNextProgram()).isTrue();
        }

        @Test
        @DisplayName("a regular user with a 'U' entry passes the filter and is dispatched")
        void aRegularUserIsAllowedARegularOption() {
            MainMenuOutcome outcome = service().handle(
                    new MainMenuInput(userReenteredContext(), CicsAid.DFHENTER, "01"),
                    stubTable("COACTVWC"));
            assertThat(outcome.errorFlag()).isFalse();
            assertThat(outcome.nextProgram()).isEqualTo("COACTVWC");
        }

        @Test
        @DisplayName("THE FILTER IS UNGATED: it runs even after validation already failed, and the "
                + "second paint wins")
        void theFilterRunsEvenWhenValidationAlreadyFailed() {
            // An active count of 1 rejects option 3, and slot 3 is nevertheless a valued entry
            // carrying 'A'. Both IF statements therefore fire in one pass: line 133 paints the
            // invalid-option message, control falls through to line 136, and line 142 repaints with
            // the No access message. Were the filter hoisted into the validation's ELSE, or moved
            // after the dispatch, this outcome would carry the invalid-option message instead.
            MainMenuOptionTable table = stubTable(1, 4,
                    new String[] {"COACTVWC", "COACTUPC", "COUSR00C", "COBIL00C"},
                    new String[] {NavigationContext.USER_TYPE_USER,
                        NavigationContext.USER_TYPE_USER,
                        NavigationContext.USER_TYPE_ADMIN,
                        NavigationContext.USER_TYPE_USER});

            MainMenuOutcome outcome = service().handle(
                    new MainMenuInput(userReenteredContext(), CicsAid.DFHENTER, "03"), table);

            assertThat(outcome.message())
                    .as("line 142's SEND overwrites line 133's")
                    .isEqualTo(NO_ACCESS_TEXT
                            + " ".repeat(WS_MESSAGE_WIDTH - NO_ACCESS_TEXT.length()));
            assertThat(outcome.errorFlag()).isTrue();
            assertThat(outcome.hasNextProgram()).isFalse();
        }

        @Test
        @DisplayName("validation failing with no admin-only entry keeps the invalid-option message")
        void validationAloneKeepsItsOwnMessage() {
            MainMenuOptionTable table = stubTable(1, 4,
                    new String[] {"COACTVWC", "COACTUPC", "COCRDLIC", "COBIL00C"},
                    new String[] {NavigationContext.USER_TYPE_USER,
                        NavigationContext.USER_TYPE_USER,
                        NavigationContext.USER_TYPE_USER,
                        NavigationContext.USER_TYPE_USER});

            MainMenuOutcome outcome = service().handle(
                    new MainMenuInput(userReenteredContext(), CicsAid.DFHENTER, "03"), table);

            assertThat(outcome.message()).isEqualTo(INVALID_OPTION_TEXT
                    + " ".repeat(WS_MESSAGE_WIDTH - INVALID_OPTION_TEXT.length()));
            assertThat(outcome.errorFlag()).isTrue();
        }

        @Test
        @DisplayName("no Spring Security is involved: the filter is a one-byte data comparison")
        void theFilterIsAPlainDataComparison() {
            assertThat(MainMenuService.ADMIN_ONLY_USRTYPE)
                    .isEqualTo(NavigationContext.USER_TYPE_ADMIN)
                    .as("the same character, but CDEMO-MENU-OPT-USRTYPE and CDEMO-USER-TYPE are "
                            + "different copybook fields")
                    .hasSize(MenuOptions.OPT_USRTYPE_LENGTH);
            assertThat(MenuOptions.activeOptions())
                    .allSatisfy(option -> assertThat(option.menuOptUsrType())
                            .isEqualTo(NavigationContext.USER_TYPE_USER));
        }
    }

    @Nested
    @DisplayName("PROCESS-ENTER-KEY L145-L156: dispatch, and the XCTL that never returns")
    class Dispatch {

        @ParameterizedTest(name = "option {0} transfers to {1}")
        @CsvSource({
            "01, COACTVWC", "02, COACTUPC", "03, COCRDLIC", "04, COCRDSLC", "05, COCRDUPC",
            "06, COTRN00C", "07, COTRN01C", "08, COTRN02C", "09, CORPT00C", "10, COBIL00C",
        })
        @DisplayName("every copybook option transfers to its own program, carrying the COMMAREA")
        void everyOptionTransfersToItsProgram(String option, String expectedProgram) {
            MainMenuOutcome outcome = service().handle(
                    new MainMenuInput(userReenteredContext(), CicsAid.DFHENTER, option));

            assertThat(outcome.nextProgram()).isEqualTo(expectedProgram);
            assertThat(outcome.nextProgramCarriesCommarea())
                    .as("app/cbl/COMEN01C.cbl:154 specifies COMMAREA(CARDDEMO-COMMAREA)")
                    .isTrue();
            assertThat(outcome.navigationContext().fromTranid()).isEqualTo("CM00");
            assertThat(outcome.navigationContext().fromProgram()).isEqualTo("COMEN01C");
            assertThat(outcome.navigationContext().isEnter())
                    .as("app/cbl/COMEN01C.cbl:151 - MOVE ZEROS TO CDEMO-PGM-CONTEXT")
                    .isTrue();
        }

        @Test
        @DisplayName("the transfer RETURNS IMMEDIATELY: no coming-soon message is attached")
        void aSuccessfulTransferDoesNotFallThrough() {
            MainMenuOutcome outcome = service().handle(
                    new MainMenuInput(userReenteredContext(), CicsAid.DFHENTER, "01"));

            assertThat(outcome.message())
                    .as("falling through would emit a message COMEN01C never emits")
                    .isEqualTo(BLANK_MESSAGE);
            assertThat(outcome.messageColourOverridden()).isFalse();
            assertThat(outcome.messageColour()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(outcome.screenPainted()).isFalse();
            assertThat(outcome.optionLines()).containsOnly(BLANK_OPTION_LINE);
        }

        @Test
        @DisplayName("CDEMO-USER-TYPE is never written: lines 149 and 150 stay commented out")
        void theUserTypeIsNeverWrittenByThisProgram() {
            NavigationContext inbound = userReenteredContext().withUserId("USER0001");
            MainMenuOutcome outcome = service()
                    .handle(new MainMenuInput(inbound, CicsAid.DFHENTER, "01"));

            assertThat(outcome.navigationContext().userType())
                    .as("line 150 is commented out, so the inbound value survives untouched")
                    .isEqualTo(NavigationContext.USER_TYPE_USER);
            assertThat(outcome.navigationContext().userId())
                    .as("line 149 is commented out - and WS-USER-ID is not even declared")
                    .isEqualTo("USER0001");
        }

        @Test
        @DisplayName("the option is echoed to OPTIONO on the transfer path too")
        void theOptionIsEchoedOnTransfer() {
            assertThat(service().handle(
                    new MainMenuInput(userReenteredContext(), CicsAid.DFHENTER, " 7")).option())
                    .isEqualTo("07");
        }
    }

    @Nested
    @DisplayName("PROCESS-ENTER-KEY L157-L164: the DUMMY prefix and the DELIMITED BY SPACE message")
    class ComingSoon {

        private MainMenuOutcome dummyOutcome(String name, String programName) {
            List<Optional<MenuOption>> slots = List.of(
                    Optional.of(new MenuOption(1, "01", name, programName,
                            NavigationContext.USER_TYPE_USER)));
            return service().handle(new MainMenuInput(userReenteredContext(), CicsAid.DFHENTER, "01"),
                    new MainMenuOptionTable(slots, 1));
        }

        @Test
        @DisplayName("a DUMMY-prefixed target suppresses the transfer and paints in green")
        void aDummyTargetSuppressesTheTransfer() {
            MainMenuOutcome outcome = dummyOutcome(OPTION_1_NAME, "DUMMY001");

            assertThat(outcome.hasNextProgram()).isFalse();
            assertThat(outcome.nextProgram()).isEqualTo(NO_NEXT_PROGRAM);
            assertThat(outcome.screenPainted()).isTrue();
            assertThat(outcome.messageColour())
                    .as("app/cbl/COMEN01C.cbl:158 overrides the map's COLOR=RED")
                    .isEqualTo(BmsAttributes.DFHGREEN);
            assertThat(outcome.messageColourOverridden()).isTrue();
            assertThat(outcome.errorFlag()).isFalse();
        }

        @ParameterizedTest(name = "\"{0}\" composes \"{1}\"")
        @CsvSource({
            "'Account View                       ', 'This option Accountis coming soon ...'",
            "'Credit Card List                   ', 'This option Creditis coming soon ...'",
            "'Bill Payment                       ', 'This option Billis coming soon ...'",
        })
        @DisplayName("DELIMITED BY SPACE keeps only the first word, and there is NO space before 'is'")
        void theComingSoonMessageIsComposedDelimitedBySpace(String name, String expected) {
            MainMenuOutcome outcome = dummyOutcome(name, "DUMMY001");

            assertThat(outcome.message())
                    .hasSize(WS_MESSAGE_WIDTH)
                    .isEqualTo(expected + " ".repeat(WS_MESSAGE_WIDTH - expected.length()));
            assertThat(outcome.message().stripTrailing())
                    .doesNotContain(" is coming soon")
                    .endsWith("is coming soon ...");
        }

        @Test
        @DisplayName("the three transcribed expectations are the ones the copybook names produce")
        void theTranscribedExpectationsAgree() {
            assertThat(dummyOutcome(OPTION_1_NAME, "DUMMY001").message().stripTrailing())
                    .isEqualTo(COMING_SOON_OPTION_1);
            assertThat(dummyOutcome(OPTION_3_NAME, "DUMMYXYZ").message().stripTrailing())
                    .isEqualTo(COMING_SOON_OPTION_3);
            assertThat(dummyOutcome(OPTION_10_NAME, "DUMMY   ").message().stripTrailing())
                    .isEqualTo(COMING_SOON_OPTION_10);
        }

        @Test
        @DisplayName("a name that is entirely spaces sends nothing, leaving prefix + suffix")
        void anAllSpaceNameSendsNothing() {
            String allSpaces = " ".repeat(OPTION_NAME_WIDTH);
            assertThat(dummyOutcome(allSpaces, "DUMMY001").message().stripTrailing())
                    .as("DELIMITED BY SPACE with the delimiter at position 1 sends an empty portion")
                    .isEqualTo("This option is coming soon ...");
        }

        @Test
        @DisplayName("a name with no space at all is sent whole")
        void aNameWithoutASpaceIsSentWhole() {
            String unbroken = "AccountView".concat(" ".repeat(OPTION_NAME_WIDTH - 11))
                    .replace(' ', '.');
            assertThat(dummyOutcome(unbroken, "DUMMY001").message().stripTrailing())
                    .isEqualTo("This option " + unbroken + "is coming soon ...");
        }

        @Test
        @DisplayName("the prefix test compares exactly five bytes, so DUMM0001 still transfers")
        void thePrefixTestComparesExactlyFiveBytes() {
            assertThat(dummyOutcome(OPTION_1_NAME, "DUMM0001").hasNextProgram())
                    .as("only the first five characters are compared, and these are not 'DUMMY'")
                    .isTrue();
            assertThat(dummyOutcome(OPTION_1_NAME, "DUMMYXYZ").hasNextProgram()).isFalse();
        }

        @Test
        @DisplayName("no copybook entry begins with DUMMY, so the branch is dead in production")
        void noCopybookEntryIsDummy() {
            assertThat(MenuOptions.activeOptions())
                    .noneSatisfy(option -> assertThat(option.menuOptPgmName())
                            .startsWith(MainMenuService.DUMMY_PROGRAM_PREFIX));
        }
    }

    @Nested
    @DisplayName("BUILD-MENU-OPTIONS L236-L277: OCCURS 12, active count 10, twelve dispatch arms")
    class BuildMenuOptions {

        @Test
        @DisplayName("the copybook table composes exactly ten 40-byte lines, 11 and 12 blank")
        void theCopybookTableComposesTenLines() {
            List<String> lines = service().buildMenuOptions(MainMenuOptionTable.copybook());

            assertThat(lines).hasSize(MainMenuService.OPTION_LINE_COUNT);
            assertThat(lines).allSatisfy(line -> assertThat(line).hasSize(OPTION_LINE_WIDTH));
            assertThat(lines.subList(MenuOptions.ACTIVE_OPTION_COUNT, MenuOptions.TABLE_SIZE))
                    .as("slots 11 and 12 are present but unvalued and are never trimmed away")
                    .containsOnly(BLANK_OPTION_LINE);
        }

        @Test
        @DisplayName("line 1 is '01. Account View' - the first populated entry, leading zero and all")
        void lineOneIsTranscribedCharacterForCharacter() {
            List<String> lines = service().buildMenuOptions(MainMenuOptionTable.copybook());
            assertThat(lines.get(0)).isEqualTo(OPTION_1_LINE);
        }

        @Test
        @DisplayName("line 10 is '10. Bill Payment' - the last populated entry")
        void lineTenIsTranscribedCharacterForCharacter() {
            List<String> lines = service().buildMenuOptions(MainMenuOptionTable.copybook());
            assertThat(lines.get(MenuOptions.ACTIVE_OPTION_COUNT - 1)).isEqualTo(OPTION_10_LINE);
        }

        @Test
        @DisplayName("option 8 carries the LIVE literal, never the commented '(Admin Only)' variant")
        void optionEightAvoidsTheCommentedLiteralTrap() {
            List<String> lines = service().buildMenuOptions(MainMenuOptionTable.copybook());
            assertThat(lines.get(7)).isEqualTo("08. " + OPTION_8_NAME + " ")
                    .doesNotContain("(Admin Only)");
            assertThat(OPTION_8_COMMENTED_NAME)
                    .as("the commented alternative is also 35 characters, so a width check "
                            + "alone would not have caught the substitution")
                    .hasSize(OPTION_NAME_WIDTH);
        }

        @Test
        @DisplayName("all twelve arms exist here: a twelve-slot stub writes every line")
        void allTwelveArmsWrite() {
            String[] programs = new String[MainMenuService.OPTION_LINE_COUNT];
            String[] types = new String[MainMenuService.OPTION_LINE_COUNT];
            for (int index = 0; index < programs.length; index++) {
                programs[index] = "COPGM0" + (char) ('A' + index);
                types[index] = NavigationContext.USER_TYPE_USER;
            }
            List<String> lines = service().buildMenuOptions(stubTable(
                    MainMenuService.OPTION_LINE_COUNT, MainMenuService.OPTION_LINE_COUNT,
                    programs, types));

            assertThat(lines).doesNotContain(BLANK_OPTION_LINE);
            assertThat(lines.get(10)).startsWith("11. ");
            assertThat(lines.get(11)).startsWith("12. ");
        }

        @Test
        @DisplayName("WHEN OTHER CONTINUE: a thirteenth subscript writes nothing")
        void theWhenOtherArmWritesNothing() {
            int slotCount = MainMenuService.OPTION_LINE_COUNT + 1;
            String[] programs = new String[slotCount];
            String[] types = new String[slotCount];
            for (int index = 0; index < slotCount; index++) {
                programs[index] = "COPGM0" + (char) ('A' + index);
                types[index] = NavigationContext.USER_TYPE_USER;
            }
            List<String> lines = service()
                    .buildMenuOptions(stubTable(slotCount, slotCount, programs, types));

            assertThat(lines)
                    .as("the map has only twelve lines, so subscript 13 falls to CONTINUE")
                    .hasSize(MainMenuService.OPTION_LINE_COUNT);
            assertThat(lines.get(11)).startsWith("12. ");
        }

        @Test
        @DisplayName("an active count of zero visits no slot and leaves every line blank")
        void anEmptyMenuLeavesEveryLineBlank() {
            assertThat(service().buildMenuOptions(stubTable(0, 1, new String[] {"COACTVWC"},
                    new String[] {NavigationContext.USER_TYPE_USER})))
                    .containsOnly(BLANK_OPTION_LINE);
        }

        @Test
        @DisplayName("a table is required")
        void aTableIsRequired() {
            MainMenuService service = service();
            assertThatNullPointerException().isThrownBy(() -> service.buildMenuOptions(null));
        }
    }

    @Nested
    @DisplayName("RETURN-TO-SIGNON-SCREEN L172-L174: the abbreviated combined relation")
    class SignonTarget {

        @Test
        @DisplayName("the LOW-VALUES half defaults to COSGN00C")
        void lowValuesDefaults() {
            assertThat(service().resolveSignonTarget("\u0000".repeat(PROGRAM_NAME_WIDTH)))
                    .isEqualTo("COSGN00C");
        }

        @Test
        @DisplayName("the SPACES half defaults to COSGN00C")
        void spacesDefaults() {
            assertThat(service().resolveSignonTarget(" ".repeat(PROGRAM_NAME_WIDTH)))
                    .isEqualTo("COSGN00C");
            assertThat(service().resolveSignonTarget("")).isEqualTo("COSGN00C");
        }

        @Test
        @DisplayName("a populated field is left alone, and a mixed field satisfies neither half")
        void aPopulatedFieldIsLeftAlone() {
            assertThat(service().resolveSignonTarget("COADM01C")).isEqualTo("COADM01C");
            assertThat(service().resolveSignonTarget("CO\u0000\u0000\u0000\u0000\u0000\u0000"))
                    .as("partly low-values and partly not satisfies neither half of the relation")
                    .isEqualTo("CO\u0000\u0000\u0000\u0000\u0000\u0000");
        }

        @Test
        @DisplayName("CDEMO-TO-PROGRAM is never null")
        void nullIsRejected() {
            MainMenuService service = service();
            assertThatNullPointerException().isThrownBy(() -> service.resolveSignonTarget(null));
        }
    }

    @Nested
    @DisplayName("The carriers: every declared width and every required component")
    class Carriers {

        @Test
        @DisplayName("MainMenuOptionTable rejects a count the table cannot address")
        void theTableRejectsAnUnaddressableCount() {
            List<Optional<MenuOption>> slots = MenuOptions.options();
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new MainMenuOptionTable(slots, -1))
                    .withMessageContaining("CDEMO-MENU-OPT-COUNT is -1");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new MainMenuOptionTable(slots, MenuOptions.TABLE_SIZE + 1))
                    .withMessageContaining("does not address a table");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new MainMenuOptionTable(slots, MenuOptions.TABLE_SIZE))
                    .withMessageContaining("is absent, yet CDEMO-MENU-OPT-COUNT is 12");
            assertThatNullPointerException()
                    .isThrownBy(() -> new MainMenuOptionTable(null, 0));
        }

        @Test
        @DisplayName("MainMenuOptionTable copies its slot list defensively")
        void theTableCopiesItsSlots() {
            List<Optional<MenuOption>> mutable = new ArrayList<>(MenuOptions.options());
            MainMenuOptionTable table =
                    new MainMenuOptionTable(mutable, MenuOptions.ACTIVE_OPTION_COUNT);
            mutable.clear();
            assertThat(table.slots()).hasSize(MenuOptions.TABLE_SIZE);
        }

        @Test
        @DisplayName("optionBySubscript throws outside 1..size; optionWithinTable never does")
        void theTwoAccessorsDifferAtTheBoundary() {
            MainMenuOptionTable table = MainMenuOptionTable.copybook();

            assertThat(table.optionBySubscript(1)).isPresent();
            assertThat(table.optionBySubscript(MenuOptions.TABLE_SIZE)).isEmpty();
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> table.optionBySubscript(0))
                    .withMessageContaining("COBOL has no subscript 0");
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> table.optionBySubscript(MenuOptions.TABLE_SIZE + 1));

            assertThat(table.optionWithinTable(0)).isEmpty();
            assertThat(table.optionWithinTable(MenuOptions.TABLE_SIZE + 1)).isEmpty();
            assertThat(table.optionWithinTable(MenuOptions.TABLE_SIZE)).isEmpty();
            assertThat(table.optionWithinTable(1)).isPresent();
        }

        @Test
        @DisplayName("OptionNormalisation checks the two widths the copybook fixes")
        void theNormalisationChecksItsWidths() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new OptionNormalisation(2, "123", " 3", "03",
                            OptionalInt.of(3)))
                    .withMessageContaining("OPTIONI is PIC X(2)");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new OptionNormalisation(2, "12", "3", "03",
                            OptionalInt.of(3)))
                    .withMessageContaining("JUST RIGHT");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new OptionNormalisation(2, "12", " 3", "003",
                            OptionalInt.of(3)))
                    .withMessageContaining("JUST RIGHT");
            assertThatNullPointerException()
                    .isThrownBy(() -> new OptionNormalisation(2, null, " 3", "03",
                            OptionalInt.of(3)));
            assertThatNullPointerException()
                    .isThrownBy(() -> new OptionNormalisation(2, "12", null, "03",
                            OptionalInt.of(3)));
            assertThatNullPointerException()
                    .isThrownBy(() -> new OptionNormalisation(2, "12", " 3", null,
                            OptionalInt.of(3)));
            assertThatNullPointerException()
                    .isThrownBy(() -> new OptionNormalisation(2, "12", " 3", "03", null));
            assertThatNoException()
                    .isThrownBy(() -> new OptionNormalisation(2, "12", "12", "12",
                            OptionalInt.of(12)));
        }

        @Test
        @DisplayName("MainMenuOutcome checks the line count and every declared width")
        void theOutcomeChecksItsWidths() {
            List<String> lines = Collections.nCopies(MainMenuService.OPTION_LINE_COUNT,
                    BLANK_OPTION_LINE);
            NavigationContext context = NavigationContext.empty();

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new MainMenuOutcome(List.of(BLANK_OPTION_LINE), BLANK_MESSAGE,
                            BmsAttributes.DFHRED, false, "  ", NO_NEXT_PROGRAM, false, true, false,
                            context, "CM00", "COMEN01", "COMEN1A", ReceiveOutcome.NORMAL))
                    .withMessageContaining("option lines");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new MainMenuOutcome(
                            Collections.nCopies(MainMenuService.OPTION_LINE_COUNT, "short"),
                            BLANK_MESSAGE, BmsAttributes.DFHRED, false, "  ", NO_NEXT_PROGRAM,
                            false, true, false, context, "CM00", "COMEN01", "COMEN1A",
                            ReceiveOutcome.NORMAL))
                    .withMessageContaining("Option line 1");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new MainMenuOutcome(lines, "too short",
                            BmsAttributes.DFHRED, false, "  ", NO_NEXT_PROGRAM, false, true, false,
                            context, "CM00", "COMEN01", "COMEN1A", ReceiveOutcome.NORMAL))
                    .withMessageContaining("WS-MESSAGE is PIC X(80)");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new MainMenuOutcome(lines, BLANK_MESSAGE,
                            BmsAttributes.DFHRED, false, "123", NO_NEXT_PROGRAM, false, true, false,
                            context, "CM00", "COMEN01", "COMEN1A", ReceiveOutcome.NORMAL))
                    .withMessageContaining("OPTIONO is PIC X(2)");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new MainMenuOutcome(lines, BLANK_MESSAGE,
                            BmsAttributes.DFHRED, false, "  ", "TOOLONGXX", false, true, false,
                            context, "CM00", "COMEN01", "COMEN1A", ReceiveOutcome.NORMAL))
                    .withMessageContaining("An XCTL target is PIC X(8)");
        }

        @Test
        @DisplayName("MainMenuOutcome requires every reference component")
        void theOutcomeRequiresItsComponents() {
            List<String> lines = Collections.nCopies(MainMenuService.OPTION_LINE_COUNT,
                    BLANK_OPTION_LINE);
            NavigationContext context = NavigationContext.empty();

            assertThatNullPointerException().isThrownBy(() -> new MainMenuOutcome(null,
                    BLANK_MESSAGE, BmsAttributes.DFHRED, false, "  ", NO_NEXT_PROGRAM, false, true,
                    false, context, "CM00", "COMEN01", "COMEN1A", ReceiveOutcome.NORMAL));
            assertThatNullPointerException().isThrownBy(() -> new MainMenuOutcome(lines, null,
                    BmsAttributes.DFHRED, false, "  ", NO_NEXT_PROGRAM, false, true, false,
                    context, "CM00", "COMEN01", "COMEN1A", ReceiveOutcome.NORMAL));
            assertThatNullPointerException().isThrownBy(() -> new MainMenuOutcome(lines,
                    BLANK_MESSAGE, BmsAttributes.DFHRED, false, null, NO_NEXT_PROGRAM, false, true,
                    false, context, "CM00", "COMEN01", "COMEN1A", ReceiveOutcome.NORMAL));
            assertThatNullPointerException().isThrownBy(() -> new MainMenuOutcome(lines,
                    BLANK_MESSAGE, BmsAttributes.DFHRED, false, "  ", null, false, true, false,
                    context, "CM00", "COMEN01", "COMEN1A", ReceiveOutcome.NORMAL));
            assertThatNullPointerException().isThrownBy(() -> new MainMenuOutcome(lines,
                    BLANK_MESSAGE, BmsAttributes.DFHRED, false, "  ", NO_NEXT_PROGRAM, false, true,
                    false, null, "CM00", "COMEN01", "COMEN1A", ReceiveOutcome.NORMAL));
            assertThatNullPointerException().isThrownBy(() -> new MainMenuOutcome(lines,
                    BLANK_MESSAGE, BmsAttributes.DFHRED, false, "  ", NO_NEXT_PROGRAM, false, true,
                    false, context, null, "COMEN01", "COMEN1A", ReceiveOutcome.NORMAL));
            assertThatNullPointerException().isThrownBy(() -> new MainMenuOutcome(lines,
                    BLANK_MESSAGE, BmsAttributes.DFHRED, false, "  ", NO_NEXT_PROGRAM, false, true,
                    false, context, "CM00", null, "COMEN1A", ReceiveOutcome.NORMAL));
            assertThatNullPointerException().isThrownBy(() -> new MainMenuOutcome(lines,
                    BLANK_MESSAGE, BmsAttributes.DFHRED, false, "  ", NO_NEXT_PROGRAM, false, true,
                    false, context, "CM00", "COMEN01", null, ReceiveOutcome.NORMAL));
            assertThatNullPointerException().isThrownBy(() -> new MainMenuOutcome(lines,
                    BLANK_MESSAGE, BmsAttributes.DFHRED, false, "  ", NO_NEXT_PROGRAM, false, true,
                    false, context, "CM00", "COMEN01", "COMEN1A", null));
        }

        @Test
        @DisplayName("optionLine addresses the map's twelve lines with a 1-based subscript")
        void optionLineIsOneBased() {
            MainMenuOutcome outcome = service().handle(
                    new MainMenuInput(NavigationContext.empty(), CicsAid.DFHENTER, "  "));

            assertThat(outcome.optionLine(1)).isEqualTo(OPTION_1_LINE);
            assertThat(outcome.optionLine(MenuOptions.ACTIVE_OPTION_COUNT))
                    .isEqualTo(OPTION_10_LINE);
            assertThat(outcome.optionLine(MainMenuService.OPTION_LINE_COUNT))
                    .isEqualTo(BLANK_OPTION_LINE);
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> outcome.optionLine(0));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> outcome.optionLine(MainMenuService.OPTION_LINE_COUNT + 1));
        }

        @Test
        @DisplayName("ReceiveOutcome carries RESP and RESP2 and no branch depends on them")
        void theReceiveOutcomeCarriesTheUntestedCodes() {
            assertThat(ReceiveOutcome.NOT_PERFORMED.performed()).isFalse();
            assertThat(ReceiveOutcome.NORMAL.performed()).isTrue();
            assertThat(ReceiveOutcome.NORMAL.respCode()).isEqualTo(ReceiveOutcome.RESP_NORMAL);
            assertThat(ReceiveOutcome.NORMAL.reasonCode()).isEqualTo(ReceiveOutcome.RESP2_NONE);

            // A non-normal pair changes nothing, because the source never tests either code - which
            // is also why the file-status gate has zero call sites in this package.
            ReceiveOutcome odd = new ReceiveOutcome(true, 13, 80);
            assertThat(odd.respCode()).isEqualTo(13);
            assertThat(odd.reasonCode()).isEqualTo(80);
        }
    }
}
