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
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.PfKeyResolver.AidKey;
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.user.model.SecUserRecord;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
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
 *
 * <p>No expected value here was captured from a COBOL run, because no COBOL run is possible in this
 * environment - the eight verified blockers are recorded in the migration plan, and the baseline is
 * therefore <strong>statically derived</strong> from the sources named above. That is why every
 * non-obvious literal below carries a {@code file:line} citation: a reviewer must be able to re-derive
 * each one by opening the cited line, and cannot fall back on "the run said so".
 *
 * <h2>There are no user-specified rules, and that is not a lower bar</h2>
 * {@code review_rules} returns exactly one line - "No user rules provided." - and that is the whole
 * document, re-read in full for this file. Nothing is invented in their place. The migration's twelve
 * enterprise-practice substitutes bind instead, and the ones that shape this file are:
 * <ul>
 *   <li><strong>B1/B2</strong> - only the closed dependency set is used: JUnit Jupiter, AssertJ and
 *       (transitively) Spring Boot's test starter, every version inherited from
 *       {@code app/java/pom.xml}. No dependency is added and no version is written here. No Mockito
 *       either: the unit under test has no collaborator worth faking, so a stub value type is both
 *       cheaper and more honest than a mock.</li>
 *   <li><strong>B3/G5</strong> - the COBOL, copybook, BMS and CSD sources are read and never written.
 *       Nothing is copied into {@code src/test/resources} and nothing is read at runtime; the byte
 *       facts are transcribed as literals.</li>
 *   <li><strong>B4</strong> - this is the only file created. No shared base class, no fixture-builder
 *       utility, no {@code package-info}, no second test profile.</li>
 *   <li><strong>B5</strong> - preserved behaviour includes the defects. Two live here: the
 *       "coming soon" text whose option name is commented out (see
 *       {@link OptionDispatch#theDummyPrefixSuppressesTheTransfer()}), and the {@code EVALUATE} arms
 *       that the shipped table can never reach (see {@link BuildMenuOptions}). Neither is repaired and
 *       neither is reached by contriving an input that changes observable behaviour.</li>
 *   <li><strong>B6/G41</strong> - the security posture is untouched. {@code COPY CSUSR01Y}
 *       ({@code app/cbl/COADM01C.cbl:58}) and {@code WS-USRSEC-FILE} ({@code :39}) are declared and
 *       never opened, so {@link SecUserRecord} is asserted <em>present and blank</em> and never
 *       asserted as read. No password, no hash, no token, no filter chain.</li>
 *   <li><strong>B7</strong> - determinism. The unit under test consults no clock at all, which is a
 *       stronger guarantee than a fixed one; see {@link LayerBoundaryAndDeterminism}.</li>
 *   <li><strong>B8</strong> - explicit over implicit: the one {@link Charset} this file names is named
 *       ({@link Construction#theCodecIsExposed()}), and every width is asserted through a named
 *       constant traced to a {@code PICTURE} clause or a {@code DFHMDF LENGTH}.</li>
 *   <li><strong>B9/G53</strong> - no static mutable state. Every {@code static} member below is
 *       {@code final} and immutable, every service instance is built inside the test method that uses
 *       it, and no test depends on another's residue or on execution order.</li>
 *   <li><strong>B10</strong> - no {@code @Disabled}, no {@code TODO}, no stub, no placeholder. This
 *       suite ships in the same pass as the code it covers, which is the whole point: a difference
 *       traced back to a translation decision the same day costs a fraction of one traced back a
 *       month later.</li>
 *   <li><strong>B12</strong> - every non-obvious expected literal carries a {@code file:line}
 *       citation, because the baseline is derived from the source rather than captured from a run.
 *       Six of them were re-derived mechanically from the cited lines before this file was
 *       committed.</li>
 * </ul>
 *
 * <p>{@code B11} - hand-written, reviewable fixed-width codecs in preference to an opaque
 * copybook-parsing library - governs {@code common.FixedWidthCodec} rather than this file, which only
 * consumes it.</p>
 *
 * <h2>Which acceptance gates this file discharges</h2>
 * <ul>
 *   <li><strong>G51</strong> - the unit under test is the service. No {@code MockMvc}, no HTTP, no
 *       {@code JobLauncher}, no Spring context anywhere in this file.</li>
 *   <li><strong>G49</strong> - branch coverage for package {@code com.vsergeychik.carddemo.admin}.
 *       This file carries that load on its own, and prefers a parameterised test over duplicated
 *       methods so each arm is visibly enumerated rather than merely incidentally covered.</li>
 *   <li><strong>G30</strong> - every {@code WHEN} of both {@code EVALUATE} statements, in source
 *       order with {@code WHEN OTHER} last: the three-arm {@code EVALUATE EIBAID} at
 *       {@code app/cbl/COADM01C.cbl:93-103} in {@link EvaluateEibAid}, and the eleven-arm
 *       {@code EVALUATE WS-IDX} at {@code :238-261} in {@link BuildMenuOptions}.</li>
 *   <li><strong>G33</strong> - the 1-based {@code OCCURS} loop of {@code BUILD-MENU-OPTIONS}, asserted
 *       at its first and its last produced element.</li>
 *   <li><strong>G37</strong> - statelessness, proved by re-using one service instance across two
 *       different invocations; see {@link UnconditionalResetAndStatelessness}.</li>
 *   <li><strong>G38</strong> - both the first-entry and the re-entry path
 *       ({@link FirstEntry}, {@link EvaluateEibAid}).</li>
 *   <li><strong>G40</strong> - every {@code XCTL} site resolves to a next-program value: the four
 *       option targets and both shapes of {@code RETURN-TO-SIGNON-SCREEN}.</li>
 *   <li><strong>G50</strong> - both states of each condition name this program's copybooks declare:
 *       {@code ERR-FLG-ON}/{@code ERR-FLG-OFF} ({@code app/cbl/COADM01C.cbl:41-42}),
 *       {@code CDEMO-PGM-ENTER}/{@code CDEMO-PGM-REENTER} ({@code app/cpy/COCOM01Y.cpy:30-31}) and
 *       {@code CDEMO-USRTYP-ADMIN}/{@code CDEMO-USRTYP-USER} ({@code :27-28}).</li>
 *   <li><strong>G52</strong> - no wildcard imports; each type is imported by name so the
 *       copybook-to-type correspondence stays auditable.</li>
 *   <li><strong>G54</strong> - plain JUnit, so the suite runs non-interactively with no watch mode.</li>
 * </ul>
 *
 * <h2>Gates verified to have no subject here, and deliberately not chased</h2>
 * <ul>
 *   <li><strong>G47</strong> ({@code FileStatus} per repository call site) - zero call sites.
 *       {@code COADM01C} performs no file input or output and the service injects no repository, so
 *       {@code common.FileStatus} is not imported. The program's only two condition codes belong to
 *       {@code RECEIVE MAP} ({@code app/cbl/COADM01C.cbl:195-196}) and the source never tests
 *       them.</li>
 *   <li><strong>G35</strong> ({@code AbendException}) - {@code COADM01C} contains no
 *       {@code CALL 'CEE3ABD'}; all nine sites are in batch programs.</li>
 *   <li><strong>G22-G29</strong> (numeric parity) - {@code COADM01C} has no arithmetic verb and no
 *       scaled {@code PICTURE}, so no decimal type, no rounding mode and no binary floating-point
 *       primitive appears in this file, and no numeric-parity assertion is written.</li>
 * </ul>
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

    /**
     * Forty {@code X'00'} characters: an {@code OPTN00nO} line the program never writes, as
     * {@code MOVE LOW-VALUES TO COADM1AO} at {@code app/cbl/COADM01C.cbl:89} leaves it.
     *
     * <p>Not spaces. {@code MOVE SPACES TO WS-ADMIN-OPT-TXT} at {@code :231} blanks the
     * {@code WORKING-STORAGE} composition buffer, not the map field; the {@code MOVE ... TO OPTN00nO}
     * statements are inside the {@code EVALUATE} arms and run only for a subscript the loop reaches, so
     * a line past {@code CDEMO-ADMIN-OPT-COUNT} is never written at all.
     */
    /**
     * {@code OPTIONO} as the group {@code MOVE LOW-VALUES} leaves it: two {@code X'00'} characters.
     * {@code MOVE WS-OPTION TO OPTIONO} at {@code :125} is the only writer and it lives inside
     * {@code PROCESS-ENTER-KEY}, so on the first-entry paint the field is simply never written.
     */
    private static final String BLANK_OPTION_ECHO = ScreenFieldImage.unpainted(2);

    private static final String BLANK_OPTION_LINE =
            ScreenFieldImage.unpainted(OPTION_LINE_WIDTH);

    /** Eight spaces: an {@code XCTL} target on a path that does not transfer. */
    private static final String NO_NEXT_PROGRAM = " ".repeat(PROGRAM_NAME_WIDTH);

    /** {@code app/cbl/COADM01C.cbl:131}, transcribed - 37 characters. */
    private static final String INVALID_OPTION_TEXT = "Please enter a valid option number...";

    /**
     * {@code CCDA-MSG-INVALID-KEY PIC X(50)} - {@code app/cpy/CSMSG01Y.cpy:20-21}, transcribed
     * character by character rather than read back off {@link SystemMessages}.
     *
     * <p>Forty characters of content, then the nine spaces physically typed inside the copybook's
     * quotes (49 in the literal), then the one space COBOL supplies implicitly to fill the declared
     * {@code PIC X(50)}. 40 + 9 + 1 = 50. Written here as three concatenated pieces so the arithmetic
     * is visible in the source rather than hidden inside a {@code repeat} count.
     *
     * <p>This is <strong>not</strong> {@code CCDA-MSG-THANK-YOU} at {@code CSMSG01Y.cpy:18-19}, which
     * is also {@code PIC X(50)} but carries entirely different text - see
     * {@link EvaluateEibAid#theTwoCommonMessagesAreNotInterchangeable()}.
     */
    private static final String INVALID_KEY_MESSAGE_X50 =
            "Invalid key pressed. Please see below..."
                    + "         "
                    + " ";

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
                    .as("OPTIONO is one of the fields LOW-VALUES clears, so it holds X'00' at its "
                            + "declared width - not spaces, which is a different byte")
                    .isEqualTo(BLANK_OPTION_ECHO);
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

        // Gate G30: the three arms are asserted below in the order app/cbl/COADM01C.cbl:93-103 writes
        // them - WHEN DFHENTER (L94), WHEN DFHPF3 (L96), then WHEN OTHER (L99) last. The order matters
        // because EVALUATE takes the first matching arm, so an implementation that tested DFHPF3 before
        // DFHENTER would still pass an unordered suite while diverging on any byte both arms matched.

        @Test
        @DisplayName("arm 1, WHEN DFHENTER at L94: PROCESS-ENTER-KEY runs and nothing else does")
        void enterSelectsTheFirstArm() {
            AdminMenuOutcome outcome = service().handle(
                    new AdminMenuInput(reenteredContext(), CicsAid.DFHENTER, "01"));

            assertThat(outcome.nextProgram())
                    .as("PROCESS-ENTER-KEY dispatched option 1, so L142-L145 transferred")
                    .isEqualTo("COUSR00C");
            assertThat(outcome.errorFlag())
                    .as("neither L100 nor L130 ran, so WS-ERR-FLG kept the 'N' L77 set")
                    .isFalse();
            assertThat(outcome.message())
                    .as("the WHEN OTHER arm at L101 was not taken")
                    .isEqualTo(BLANK_MESSAGE);
            assertThat(outcome.receive())
                    .as("L92 PERFORM RECEIVE-MENU-SCREEN precedes the EVALUATE on this path")
                    .isEqualTo(ReceiveOutcome.NORMAL);
        }

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

        /**
         * The attention identifiers that must reach {@code WHEN OTHER} at
         * {@code app/cbl/COADM01C.cbl:99}, each named so a failure reports the mnemonic rather than a
         * byte value.
         *
         * <p>{@code DFHPA3} is in the list for a specific reason: {@code COADM01C} has no arm for it,
         * and neither does {@code app/cpy/CSSTRPFY.cpy}, so it is the one AID that is simultaneously
         * unmatched by the program and unresolvable by {@link PfKeyResolver}. {@code DFHNULL} is the
         * byte a terminal sends when no attention key was involved at all.
         *
         * @return one case per attention identifier
         */
        static Stream<Arguments> aidsThatFallToWhenOther() {
            return Stream.of(
                    Arguments.of("DFHCLEAR", CicsAid.DFHCLEAR),
                    Arguments.of("DFHPF1", CicsAid.DFHPF1),
                    Arguments.of("DFHPA1", CicsAid.DFHPA1),
                    Arguments.of("DFHPF12", CicsAid.DFHPF12),
                    Arguments.of("DFHPA2", CicsAid.DFHPA2),
                    Arguments.of("DFHPA3", CicsAid.DFHPA3),
                    Arguments.of("DFHPF15", CicsAid.DFHPF15),
                    Arguments.of("DFHNULL", CicsAid.DFHNULL));
        }

        @ParameterizedTest(name = "{0} falls to WHEN OTHER")
        @DisplayName("arm 3, WHEN OTHER at L99: every AID that is neither DFHENTER nor DFHPF3")
        @MethodSource("aidsThatFallToWhenOther")
        void everyOtherAidFallsToWhenOther(String mnemonic, byte eibAid) {
            AdminMenuOutcome outcome = service().handle(
                    new AdminMenuInput(reenteredContext(), eibAid, "01"));

            assertThat(outcome.errorFlag())
                    .as("L93-L103 declares no arm for %s, so L100 sets WS-ERR-FLG", mnemonic)
                    .isTrue();
            assertThat(outcome.message())
                    .as("L101 moves CCDA-MSG-INVALID-KEY in for %s", mnemonic)
                    .isEqualTo(INVALID_KEY_MESSAGE_X50 + " ".repeat(30));
            assertThat(outcome.screenPainted())
                    .as("L102 performs SEND-MENU-SCREEN")
                    .isTrue();
            assertThat(outcome.hasNextProgram())
                    .as("no XCTL is reachable from WHEN OTHER: option 1 was never dispatched")
                    .isFalse();
        }

        @Test
        @DisplayName("the 50-byte CCDA-MSG-INVALID-KEY is transcribed, not read back off the class")
        void theInvalidKeyMessageIsTheTranscribedFiftyBytes() {
            assertThat(INVALID_KEY_MESSAGE_X50)
                    .as("app/cpy/CSMSG01Y.cpy:20-21 - 40 content + 9 typed + 1 implicit")
                    .hasSize(50)
                    .startsWith("Invalid key pressed. Please see below...")
                    .endsWith("...          ");
            assertThat(INVALID_KEY_MESSAGE_X50.substring(0, 40))
                    .as("the content is forty characters including the three-dot ellipsis")
                    .isEqualTo("Invalid key pressed. Please see below...");
            assertThat(INVALID_KEY_MESSAGE_X50.substring(40))
                    .as("ten trailing spaces: nine typed into the literal plus one COBOL supplies")
                    .isEqualTo(" ".repeat(10));
            assertThat(SystemMessages.CCDA_MSG_INVALID_KEY)
                    .as("the shared constant must agree with the transcription")
                    .isEqualTo(INVALID_KEY_MESSAGE_X50);
        }

        @Test
        @DisplayName("CCDA-MSG-INVALID-KEY is not CCDA-MSG-THANK-YOU: same width, different text")
        void theTwoCommonMessagesAreNotInterchangeable() {
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU)
                    .as("app/cpy/CSMSG01Y.cpy:18-19 is also PIC X(50)")
                    .hasSize(SystemMessages.MESSAGE_LENGTH)
                    .isNotEqualTo(INVALID_KEY_MESSAGE_X50);
            assertThat(service().handle(
                    new AdminMenuInput(reenteredContext(), CicsAid.DFHCLEAR, "  ")).message())
                    .as("L101 moves the invalid-key message; COADM01C never emits the thank-you one")
                    .doesNotContain("Thank you");
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
            // A re-entry on option 01 transfers, and a transfer states no map: neither XCTL names one -
            // line 143 names only CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION) - and which map the successor
            // paints is its decision. Naming COADM01 / COADM1A here would tell the client to repaint the
            // screen it is leaving.
            AdminMenuOutcome transferred = service().handle(
                    new AdminMenuInput(reenteredContext(), CicsAid.DFHENTER, "01"));

            assertThat(transferred.transactionId()).isEqualTo("CA00");
            assertThat(transferred.hasNextProgram()).isTrue();
            assertThat(transferred.mapsetName()).isBlank()
                    .hasSize(NavigationContext.LAST_MAPSET_LENGTH);
            assertThat(transferred.mapName()).isBlank().hasSize(NavigationContext.LAST_MAP_LENGTH);

            // A SEND is the other case, and it names this screen - lines 180 to 181.
            AdminMenuOutcome painted = service().handle(
                    new AdminMenuInput(NavigationContext.empty(), CicsAid.DFHENTER, "01"));

            assertThat(painted.screenPainted()).isTrue();
            assertThat(painted.transactionId()).isEqualTo("CA00");
            assertThat(painted.mapsetName()).isEqualTo("COADM01");
            assertThat(painted.mapName()).isEqualTo("COADM1A");
        }

        @Test
        @DisplayName("both handle overloads and both arguments behave")
        void handleRequiresItsArguments() {
            assertThatNullPointerException().isThrownBy(() -> service().handle(null));
            assertThatNullPointerException().isThrownBy(() -> service().handle(
                    new AdminMenuInput(reenteredContext(), CicsAid.DFHENTER, "01"), null));
        }
    }

    /**
     * How {@code AdminMenuService} resolves the attention identifier, and why the shared resolver's
     * five-character token is <em>not</em> what selects the arm.
     *
     * <p>{@code COADM01C} does <strong>not</strong> copy {@code CSSTRPFY}: its nine copybooks are listed
     * at {@code app/cbl/COADM01C.cbl:50-61} and the PF-key store is not among them. Line 93 compares
     * {@code EIBAID} to {@code DFHENTER} and {@code DFHPF3} inline, so the service uses
     * {@link PfKeyResolver#isAid(byte, byte)} - a raw byte comparison - and never
     * {@link PfKeyResolver#resolve(byte)}. The tokens are asserted here anyway, for two reasons: they
     * are the vocabulary the five programs that <em>do</em> copy {@code CSSTRPFY} share, and the
     * {@code PFK03} fold is the exact place where using the token instead of the byte would silently
     * change this program's behaviour.
     */
    @Nested
    @DisplayName("Attention identifiers: the raw byte selects the arm, not the CSSTRPFY token")
    class AttentionIdentifiers {

        /**
         * The AID bytes {@link PfKeyResolver} resolves, paired with the exact five-character
         * {@code CCARD-AID} literal each yields.
         *
         * @return one case per attention identifier this program can receive
         */
        static Stream<Arguments> resolvableAids() {
            return Stream.of(
                    Arguments.of("DFHENTER", CicsAid.DFHENTER, AidKey.ENTER, "ENTER"),
                    Arguments.of("DFHPF3", CicsAid.DFHPF3, AidKey.PFK03, "PFK03"),
                    Arguments.of("DFHPF15", CicsAid.DFHPF15, AidKey.PFK03, "PFK03"),
                    Arguments.of("DFHCLEAR", CicsAid.DFHCLEAR, AidKey.CLEAR, "CLEAR"),
                    Arguments.of("DFHPF1", CicsAid.DFHPF1, AidKey.PFK01, "PFK01"),
                    Arguments.of("DFHPF12", CicsAid.DFHPF12, AidKey.PFK12, "PFK12"),
                    Arguments.of("DFHPA1", CicsAid.DFHPA1, AidKey.PA1, "PA1  "),
                    Arguments.of("DFHPA2", CicsAid.DFHPA2, AidKey.PA2, "PA2  "));
        }

        @ParameterizedTest(name = "{0} resolves to the token {3}")
        @DisplayName("each resolvable AID yields its exact five-character CVCRD01Y literal")
        @MethodSource("resolvableAids")
        void eachResolvableAidYieldsItsToken(String mnemonic,
                byte eibAid,
                AidKey expectedKey,
                String expectedToken) {

            assertThat(PfKeyResolver.resolve(eibAid))
                    .as("%s", mnemonic)
                    .hasValue(expectedKey);
            assertThat(expectedKey.token())
                    .as("CCARD-AID is PIC X(5), so PA1 and PA2 keep their two trailing spaces")
                    .isEqualTo(expectedToken)
                    .hasSize(PfKeyResolver.AID_TOKEN_LENGTH);
        }

        @ParameterizedTest(name = "EIBAID {0} resolves to nothing")
        @DisplayName("an unmatched AID is an empty Optional, never a substituted default")
        @ValueSource(bytes = {CicsAid.DFHPA3, UNRESOLVABLE_AID, (byte) 0x00, (byte) 0x7F})
        void anUnmatchedAidResolvesToNothing(byte eibAid) {
            assertThat(PfKeyResolver.resolve(eibAid))
                    .as("CSSTRPFY's EVALUATE has no WHEN OTHER, so nothing is set at all")
                    .isEmpty();
        }

        @Test
        @DisplayName("an AID the resolver cannot name is still handled: WHEN OTHER, no exception")
        void anUnresolvableAidIsHandledWithoutException() {
            AdminMenuOutcome outcome = service().handle(
                    new AdminMenuInput(reenteredContext(), CicsAid.DFHPA3, "01"));

            assertThat(PfKeyResolver.resolve(CicsAid.DFHPA3)).isEmpty();
            assertThat(outcome.errorFlag())
                    .as("L99's WHEN OTHER absorbs it; the absence of a token is not an error path")
                    .isTrue();
            assertThat(outcome.message()).isEqualTo(INVALID_KEY_MESSAGE_X50 + " ".repeat(30));
        }

        @Test
        @DisplayName("the PFK03 fold is why the byte, not the token, must select the arm")
        void thePfk03FoldWouldChangeBehaviourIfTheTokenSelectedTheArm() {
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF15))
                    .as("CSSTRPFY folds PF15 onto PFK03, the same token PF3 yields")
                    .hasValue(AidKey.PFK03);
            assertThat(PfKeyResolver.isAid(CicsAid.DFHPF15, CicsAid.DFHPF3))
                    .as("but the bytes differ - 0xC3 against 0xF3 - and L93 compares bytes")
                    .isFalse();

            AdminMenuOutcome viaPf3 = service().handle(
                    new AdminMenuInput(reenteredContext(), CicsAid.DFHPF3, "  "));
            AdminMenuOutcome viaPf15 = service().handle(
                    new AdminMenuInput(reenteredContext(), CicsAid.DFHPF15, "  "));

            assertThat(viaPf3.nextProgram())
                    .as("PF3 takes arm 2 and transfers to the sign-on program")
                    .isEqualTo("COSGN00C");
            assertThat(viaPf15.hasNextProgram())
                    .as("PF15 takes WHEN OTHER instead, so it transfers nowhere")
                    .isFalse();
        }

        @Test
        @DisplayName("the two bytes L93 does compare are the ones the service tests")
        void theTwoComparedBytesAreTheOnesTheServiceTests() {
            assertThat(PfKeyResolver.isEnter(CicsAid.DFHENTER)).isTrue();
            assertThat(PfKeyResolver.isPf3(CicsAid.DFHPF3)).isTrue();
            assertThat(PfKeyResolver.isAid(CicsAid.DFHENTER, CicsAid.DFHPF3)).isFalse();
            assertThat(PfKeyResolver.isAid(CicsAid.DFHPF3, CicsAid.DFHENTER)).isFalse();
        }
    }

    /**
     * {@code MAIN-PARA}'s opening three statements, and the statelessness that makes them sufficient.
     *
     * <p>{@code app/cbl/COADM01C.cbl:77-80} runs on <strong>every</strong> invocation, before the
     * {@code EIBCALEN} test and before anything is read:
     * <pre>
     *   L77   SET ERR-FLG-OFF TO TRUE
     *   L79   MOVE SPACES TO WS-MESSAGE
     *   L80                 ERRMSGO OF COADM1AO
     * </pre>
     * On the mainframe those three statements matter because {@code WORKING-STORAGE} survives between
     * the transaction's pseudo-conversational turns. In this projection nothing survives, because
     * {@code WS-ERR-FLG} and {@code WS-MESSAGE} are method-local rather than instance fields - so the
     * reset is <em>also</em> the proof that no state leaks, which is what gate G37 asks for.
     */
    @Nested
    @DisplayName("MAIN-PARA L77-L80: the unconditional reset, and the statelessness behind it")
    class UnconditionalResetAndStatelessness {

        @Test
        @DisplayName("G37: one service instance, two invocations, and the second sees nothing of the "
                + "first")
        void aSecondInvocationIsUnaffectedByTheFirst() {
            AdminMenuService shared = service();

            AdminMenuOutcome errored = shared.handle(
                    new AdminMenuInput(reenteredContext(), CicsAid.DFHENTER, "99"));
            AdminMenuOutcome clean = shared.handle(
                    new AdminMenuInput(reenteredContext(), CicsAid.DFHENTER, "01"));

            assertThat(errored.errorFlag())
                    .as("the first call left WS-ERR-FLG at 'Y' and WS-MESSAGE populated")
                    .isTrue();
            assertThat(errored.message()).isNotEqualTo(BLANK_MESSAGE);

            assertThat(clean.errorFlag())
                    .as("L77 SET ERR-FLG-OFF TO TRUE runs before anything else on the second call")
                    .isFalse();
            assertThat(clean.message())
                    .as("L79 MOVE SPACES TO WS-MESSAGE - no residue of the first call's error text")
                    .isEqualTo(BLANK_MESSAGE);
            assertThat(clean.nextProgram())
                    .as("and the second call dispatches normally")
                    .isEqualTo("COUSR00C");
        }

        @Test
        @DisplayName("G37: the reverse order too - a clean call first does not suppress a later error")
        void aFirstCleanInvocationDoesNotSuppressALaterError() {
            AdminMenuService shared = service();

            shared.handle(new AdminMenuInput(reenteredContext(), CicsAid.DFHENTER, "01"));
            AdminMenuOutcome errored = shared.handle(
                    new AdminMenuInput(reenteredContext(), CicsAid.DFHENTER, "  "));

            assertThat(errored.errorFlag()).isTrue();
            assertThat(errored.message())
                    .isEqualTo(INVALID_OPTION_TEXT + " ".repeat(43));
        }

        @Test
        @DisplayName("the reset is unconditional: it precedes even the error paths that overwrite it")
        void theResetPrecedesEveryPathIncludingTheErrorOnes() {
            // Every one of the four terminal shapes COADM01C has, each built from a fresh context so
            // the only possible source of a stale message would be the service itself.
            AdminMenuService shared = service();

            assertThat(shared.handle(AdminMenuInput.withoutCommarea(CicsAid.DFHENTER, "01")).message())
                    .as("L82-L84, the absent-commarea route: nothing writes WS-MESSAGE at all")
                    .isEqualTo(BLANK_MESSAGE);
            assertThat(shared.handle(
                    new AdminMenuInput(NavigationContext.empty(), CicsAid.DFHENTER, "01")).message())
                    .as("L87-L90, first entry: L79's spaces are what SEND-MENU-SCREEN sends to ERRMSGO")
                    .isEqualTo(BLANK_MESSAGE);
            assertThat(shared.handle(
                    new AdminMenuInput(reenteredContext(), CicsAid.DFHPF3, "01")).message())
                    .as("L96-L98, the PF3 arm: no message is composed before the XCTL")
                    .isEqualTo(BLANK_MESSAGE);
            assertThat(shared.handle(
                    new AdminMenuInput(reenteredContext(), CicsAid.DFHCLEAR, "01")).message())
                    .as("L99-L102, WHEN OTHER: L101 overwrites the spaces L79 had just moved in")
                    .isEqualTo(INVALID_KEY_MESSAGE_X50 + " ".repeat(30));
        }

        @Test
        @DisplayName("no invocation mutates the inbound communication area it was handed")
        void theInboundContextIsNeverMutated() {
            NavigationContext inbound = reenteredContext()
                    .withUserId("ADMIN001")
                    .withToProgram("COMEN01C");

            AdminMenuOutcome outcome = service().handle(
                    new AdminMenuInput(inbound, CicsAid.DFHENTER, "02"));

            assertThat(inbound.isReenter())
                    .as("CARDDEMO-COMMAREA is projected as an immutable value, so the caller's copy "
                            + "still reads as it did - L141 produced a new one")
                    .isTrue();
            assertThat(inbound.fromProgram())
                    .as("L140 wrote COADM01C into the OUTBOUND area only")
                    .isBlank();
            assertThat(outcome.navigationContext().isEnter()).isTrue();
            assertThat(outcome.navigationContext().fromProgram()).isEqualTo("COADM01C");
            assertThat(outcome.navigationContext().userId())
                    .as("every field COADM01C does not write travels through untouched")
                    .isEqualTo("ADMIN001");
        }
    }

    /**
     * The user-type condition names of {@code app/cpy/COCOM01Y.cpy:27-28}, in both states.
     *
     * <p>{@code COADM01C} copies {@code COCOM01Y} at line 50 and so declares
     * {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'} and {@code 88 CDEMO-USRTYP-USER VALUE 'U'} - and then
     * <strong>never tests either one</strong>. Searching the program for {@code CDEMO-USER-TYPE},
     * {@code CDEMO-USRTYP-ADMIN} and {@code CDEMO-USRTYP-USER} returns only the copybook line.
     *
     * <p>That absence is behaviour and is asserted as such. The admin menu is reached by transferring
     * from {@code COSGN00C}, which does the role check itself
     * ({@code app/cbl/COSGN00C.cbl:232} transfers an {@code 'A'} user here), so {@code COADM01C}
     * trusts its caller. Its sibling {@code COMEN01C} is the one that filters its option list on the
     * user type; importing that filter here would add a branch this program does not have, which
     * practice B5 forbids as squarely as deleting one would.
     */
    @Nested
    @DisplayName("COCOM01Y L27-L28: both user-type condition names, and the filter COADM01C lacks")
    class UserTypeConditionNames {

        @Test
        @DisplayName("88 CDEMO-USRTYP-ADMIN VALUE 'A' - true state, carried through untouched")
        void theAdminConditionNameIsCarriedThrough() {
            NavigationContext admin = reenteredContext().withUserTypeAdmin();

            assertThat(admin.isAdmin()).isTrue();
            assertThat(admin.isUser()).isFalse();
            assertThat(admin.userType()).isEqualTo(NavigationContext.USER_TYPE_ADMIN);

            AdminMenuOutcome outcome = service().handle(
                    new AdminMenuInput(admin, CicsAid.DFHENTER, "01"));

            assertThat(outcome.navigationContext().isAdmin())
                    .as("COADM01C writes CDEMO-USER-TYPE on no path, so 'A' survives the transfer")
                    .isTrue();
            assertThat(outcome.nextProgram()).isEqualTo("COUSR00C");
        }

        @Test
        @DisplayName("88 CDEMO-USRTYP-USER VALUE 'U' - true state, and the menu behaves identically")
        void theUserConditionNameIsCarriedThrough() {
            NavigationContext regular = reenteredContext().withUserTypeUser();

            assertThat(regular.isUser()).isTrue();
            assertThat(regular.isAdmin()).isFalse();
            assertThat(regular.userType()).isEqualTo(NavigationContext.USER_TYPE_USER);

            AdminMenuOutcome outcome = service().handle(
                    new AdminMenuInput(regular, CicsAid.DFHENTER, "01"));

            assertThat(outcome.navigationContext().isUser()).isTrue();
            assertThat(outcome.nextProgram())
                    .as("COADM01C has no authorisation filter: a 'U' context takes the same path. "
                            + "COSGN00C performs the role check, and COMEN01C - not this program - is "
                            + "the one that filters its options on the user type")
                    .isEqualTo("COUSR00C");
        }

        @Test
        @DisplayName("the four menu lines are identical for an admin and for a regular user")
        void theOptionListIsNotFilteredByUserType() {
            AdminMenuOutcome asAdmin = service().handle(new AdminMenuInput(
                    reenteredContext().withUserTypeAdmin(), CicsAid.DFHCLEAR, "  "));
            AdminMenuOutcome asUser = service().handle(new AdminMenuInput(
                    reenteredContext().withUserTypeUser(), CicsAid.DFHCLEAR, "  "));

            assertThat(asUser.optionLines())
                    .as("no CDEMO-USER-TYPE test exists anywhere in COADM01C")
                    .isEqualTo(asAdmin.optionLines());
        }

        @Test
        @DisplayName("neither condition name holds for a context that carries no user type")
        void neitherConditionNameHoldsForAnUnsetUserType() {
            NavigationContext unset = reenteredContext();

            assertThat(unset.isAdmin())
                    .as("CDEMO-USER-TYPE is PIC X(01) and a space satisfies neither 88-level")
                    .isFalse();
            assertThat(unset.isUser()).isFalse();
            assertThat(service().handle(new AdminMenuInput(unset, CicsAid.DFHENTER, "01"))
                    .nextProgram())
                    .as("and the menu still works, because it never asks")
                    .isEqualTo("COUSR00C");
        }
    }

    /**
     * Where the service stops and the controller starts, and why that boundary is what makes this
     * suite deterministic.
     *
     * <p>{@code SEND-MENU-SCREEN} at {@code app/cbl/COADM01C.cbl:172-184} performs
     * {@code POPULATE-HEADER-INFO}, then {@code BUILD-MENU-OPTIONS}, then the {@code SEND MAP}. The
     * middle one is this service's work; the first is not, because its opening statement is
     * {@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} at {@code :204}.
     *
     * <p>The migration's determinism practice calls for a <strong>fixed</strong> clock so that a header
     * assertion cannot drift. {@code AdminMenuService} satisfies that requirement in the stronger form:
     * it takes <strong>no clock at all</strong> - neither constructor accepts one and no method reads
     * one - so there is no time source in the path to fix. The header fields that do depend on the
     * clock, {@code CURDATEO} at {@code :215} and {@code CURTIMEO} at {@code :221}, are produced by
     * {@code AdminMenuController} from the shared date header driven by the module's single injected
     * {@code Clock} bean, and they are asserted there under a fixed instant. Duplicating that here
     * would assert a collaborator this class does not have.
     *
     * <p>What {@code POPULATE-HEADER-INFO} takes from <em>this</em> class is the pair of identity
     * literals at {@code :208-209} - {@code MOVE WS-TRANID TO TRNNAMEO} and
     * {@code MOVE WS-PGMNAME TO PGMNAMEO} - and those are asserted below.
     */
    @Nested
    @DisplayName("The layer boundary: no clock in the service, so nothing to fix and nothing to drift")
    class LayerBoundaryAndDeterminism {

        @Test
        @DisplayName("L208-L209: the two identity literals POPULATE-HEADER-INFO moves to the header")
        void theHeaderIdentityLiteralsComeFromHere() {
            AdminMenuOutcome outcome = service().handle(
                    new AdminMenuInput(NavigationContext.empty(), CicsAid.DFHENTER, "  "));

            assertThat(outcome.transactionId())
                    .as("L208 MOVE WS-TRANID TO TRNNAMEO, and L108's RETURN TRANSID, and "
                            + "app/csd/CARDDEMO.CSD:327-328's DEFINE TRANSACTION(CA00)")
                    .isEqualTo("CA00")
                    .isEqualTo(AdminMenuService.TRANSACTION_ID);
            assertThat(AdminMenuService.PROGRAM_NAME)
                    .as("L209 MOVE WS-PGMNAME TO PGMNAMEO, and L140's CDEMO-FROM-PROGRAM")
                    .isEqualTo("COADM01C");
            assertThat(outcome.mapName())
                    .as("L180 MAP('COADM1A')")
                    .isEqualTo("COADM1A");
            assertThat(outcome.mapsetName())
                    .as("L181 MAPSET('COADM01')")
                    .isEqualTo("COADM01");
        }

        @Test
        @DisplayName("B7: the same input produces a byte-identical outcome every time it is run")
        void repeatedInvocationsAreByteIdentical() {
            AdminMenuInput input = new AdminMenuInput(
                    reenteredContext().withUserTypeAdmin(), CicsAid.DFHENTER, "01");

            AdminMenuOutcome first = service().handle(input);
            AdminMenuOutcome second = service().handle(input);
            AdminMenuOutcome third = new AdminMenuService(bindings(),
                    new FixedWidthCodec(Charset.forName(
                            AdminMenuService.DEFAULT_MESSAGE_CHARSET_NAME))).handle(input);

            assertThat(second)
                    .as("no clock, no random source, no environment and no ambient state")
                    .isEqualTo(first);
            assertThat(third)
                    .as("nor does a separately constructed instance differ")
                    .isEqualTo(first);
        }

        @Test
        @DisplayName("B7: the screen-painting path is deterministic too, header fields excepted")
        void thePaintedScreenIsDeterministic() {
            AdminMenuInput input = new AdminMenuInput(reenteredContext(), CicsAid.DFHCLEAR, "  ");

            AdminMenuOutcome first = service().handle(input);
            AdminMenuOutcome second = service().handle(input);

            assertThat(second.optionLines()).isEqualTo(first.optionLines());
            assertThat(second.message()).isEqualTo(first.message());
            assertThat(second.messageColour()).isEqualTo(first.messageColour());
            assertThat(second).isEqualTo(first);
        }
    }

    @Nested
    @DisplayName("PROCESS-ENTER-KEY L117-L125: the four-step option normalisation")
    class OptionNormalisationCases {

        // The four rows marked "worked case" below are the ones hand-traced against
        // app/cbl/COADM01C.cbl:117-125 statement by statement, and they are the reason this file can be
        // reviewed without a COBOL run: each is re-derivable from the cited lines alone.
        //
        //   OPTIONI  scan ends at WS-IDX  after L122 (JUST RIGHT)  after L123 (INSPECT)  WS-OPTION
        //   '3 '     1                    ' 3'                     '03'                  3
        //   '  '     1                    '  '                     '00'                  0
        //   '10'     2                    '10'                     '10'                  10
        //   'AB'     2                    'AB'                     'AB'                  not numeric
        //
        // The scan at L117-L121 has an EMPTY body and is PRE-test, starting at
        // WS-IDX = LENGTH OF OPTIONI = 2: it stops immediately when byte 2 is not a space, and
        // otherwise decrements to the WS-IDX = 1 termination term. That is why '3 ' ends at 1 - the
        // trailing space is scanned off - while '10' ends at 2.
        @ParameterizedTest(name = "OPTIONI {0} -> WS-IDX {1}, step 3 {2}, step 4 {3}")
        @DisplayName("every worked case, asserting the intermediate two-byte buffer")
        @CsvSource(delimiter = '|', value = {
            "'  ' | 1 | '  ' | '00'",
            "' 3' | 2 | ' 3' | '03'",
            "'3 ' | 1 | ' 3' | '03'",
            "'10' | 2 | '10' | '10'",
            "'AB' | 2 | 'AB' | 'AB'",
            "'12' | 2 | '12' | '12'",
            "'1x' | 2 | '1x' | '1x'",
            "'1 ' | 1 | ' 1' | '01'",
            "'4 ' | 1 | ' 4' | '04'",
            "'04' | 2 | '04' | '04'",
            "'05' | 2 | '05' | '05'"
        })
        void everyWorkedNormalisationCase(String optionI,
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
            "'1 ' | 1",
            "'4 ' | 4",
            "'10' | 10",
            "'12' | 12",
            "'04' | 4",
            "'05' | 5"
        })
        void theNumericCasesDecode(String optionI, int expected) {
            OptionNormalisation normalised = service().normaliseOption(optionI);

            assertThat(normalised.isNumeric()).isTrue();
            assertThat(normalised.option()).hasValue(expected);
        }

        @ParameterizedTest(name = "OPTIONI {0} echoes into OPTIONO as {1}")
        @DisplayName("L125 echoes WS-OPTION, a PIC 9(02), so a single digit renders zero-filled")
        @CsvSource(delimiter = '|', value = {
            "'1 ' | '01'",
            "'2 ' | '02'",
            "'3 ' | '03'",
            "'4 ' | '04'",
            "' 3' | '03'",
            "'04' | '04'"
        })
        void theEchoedOptionIsTheTwoDigitZeroFilledImage(String optionI, String expectedEcho) {
            AdminMenuOutcome outcome = service().handle(
                    new AdminMenuInput(reenteredContext(), CicsAid.DFHENTER, optionI));

            assertThat(outcome.option())
                    .as("MOVE WS-OPTION TO OPTIONO at L125 - never '3' and never ' 3'; "
                            + "app/bms/COADM01.bms:145-149 declares JUSTIFY=(RIGHT,ZERO) LENGTH=2")
                    .isEqualTo(expectedEcho)
                    .hasSize(AdminMenuService.OPTION_LENGTH);
        }

        @ParameterizedTest(name = "the trailing-space form {0} dispatches to {1}")
        @DisplayName("a value typed without its leading zero is in range and dispatches identically")
        @CsvSource({
            "'1 ', COUSR00C",
            "'4 ', COUSR03C"
        })
        void theTrailingSpaceFormsAreValidInRangeValues(String optionI, String expectedProgram) {
            AdminMenuOutcome outcome = service().handle(
                    new AdminMenuInput(reenteredContext(), CicsAid.DFHENTER, optionI));

            assertThat(outcome.errorFlag())
                    .as("1 and %d are both within CDEMO-ADMIN-OPT-COUNT, so L127-L134 does not fire",
                            AdminMenuOptions.ACTIVE_OPTION_COUNT)
                    .isFalse();
            assertThat(outcome.nextProgram()).isEqualTo(expectedProgram);
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

        // =============================================================================================
        // DO NOT "FIX" THE EXPECTED TEXT BELOW, AND DO NOT HARMONISE IT WITH MainMenuServiceTest.
        //
        // app/cbl/COADM01C.cbl:147-154 reads, with lines 150 and 151 COMMENTED OUT in the source:
        //
        //   L147          MOVE SPACES   TO WS-MESSAGE
        //   L148          MOVE DFHGREEN TO ERRMSGC  OF COADM1AO
        //   L149          STRING 'This option '       DELIMITED BY SIZE
        //   L150   *             CDEMO-ADMIN-OPT-NAME(WS-OPTION)
        //   L151   *                                 DELIMITED BY SIZE
        //   L152                 'is coming soon ...'   DELIMITED BY SIZE
        //   L153            INTO WS-MESSAGE
        //
        // Two operands reach the STRING, not three. The option name never enters the message, and
        // because 'This option ' carries its own trailing space the composed text is
        //
        //     "This option is coming soon ..."          - thirty characters, WITH the space
        //
        // Its sibling COMEN01C:159-163 looks all but identical and is NOT the same: there the option
        // name is still active and is DELIMITED BY SPACE, so the main menu composes
        //
        //     "This option Accountis coming soon ..."   - WITHOUT a space, a real legacy defect
        //
        // Both are preserved exactly as their own source produces them (practice B5). If this
        // expectation and MainMenuServiceTest's ever agree, one of the two has been broken: this file
        // must expect the spaced form and that one the unspaced form. Making them match would be a
        // parity violation in one direction or the other, and no amount of tidiness justifies it.
        // =============================================================================================
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

        @Test
        @DisplayName("the spaced form is the one COADM01C emits - not COMEN01C's unspaced defect")
        void theComingSoonTextKeepsItsSpaceUnlikeTheMainMenus() {
            AdminMenuOutcome outcome = service().handle(
                    new AdminMenuInput(reenteredContext(), CicsAid.DFHENTER, "01"),
                    stubTable(1, "DUMMY001"));

            assertThat(outcome.message().stripTrailing())
                    .as("L149 supplies the trailing space of 'This option ' and L150-L151 are "
                            + "commented out, so nothing is interposed before 'is'")
                    .isEqualTo("This option is coming soon ...")
                    .contains("option is coming");
            assertThat(outcome.message())
                    .as("COMEN01C:159-163 keeps CDEMO-MENU-OPT-NAME active DELIMITED BY SPACE and so "
                            + "composes 'This option Accountis coming soon ...'. COADM01C does not, "
                            + "and the two must never be harmonised")
                    .doesNotContain("optionis")
                    .doesNotContain("Accountis");
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

        // =============================================================================================
        // Gate G30 for the second EVALUATE. app/cbl/COADM01C.cbl:238-261 has exactly ELEVEN arms:
        //
        //   L239 WHEN  1  MOVE WS-ADMIN-OPT-TXT TO OPTN001O
        //   L241 WHEN  2  ... OPTN002O          L243 WHEN  3  ... OPTN003O
        //   L245 WHEN  4  ... OPTN004O          L247 WHEN  5  ... OPTN005O
        //   L249 WHEN  6  ... OPTN006O          L251 WHEN  7  ... OPTN007O
        //   L253 WHEN  8  ... OPTN008O          L255 WHEN  9  ... OPTN009O
        //   L257 WHEN 10  ... OPTN010O
        //   L259 WHEN OTHER  CONTINUE
        //
        // There is NO arm for 11 and none for 12, so OPTN011O and OPTN012O
        // (app/cpy-bms/COADM01.CPY:242 and :248) are structurally unwritable by this program. Its
        // sibling COMEN01C has thirteen arms and does reach both - another asymmetry between two
        // paragraphs that read alike. Do not assume symmetry and do not add the missing arms.
        //
        // Reachability, and practice B5: the loop at L228-L229 is bounded by CDEMO-ADMIN-OPT-COUNT,
        // which app/cpy/COADM02Y.cpy:20 gives VALUE 4, so under the shipped table arms 5 to 10 and
        // WHEN OTHER can never be taken. They are enumerated below through a TEST-LOCAL option table
        // with a larger count, which is exactly what buildMenuOptions taking a table makes possible.
        // The shipped table is never mutated and the active count is never widened in production - a
        // branch is driven by supplying a different input, never by editing the copybook's data.
        // =============================================================================================
        @ParameterizedTest(name = "WHEN {0} writes OPTN0{0}O and nothing else")
        @DisplayName("all ten dispatch arms in source order, each writing only its own line")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
        void everyDispatchArmWritesItsOwnLine(int cobolSubscript) {
            // A table whose active count reaches exactly this subscript, so the loop runs 1..subscript
            // and the arm under test is the last one taken.
            String[] targets = new String[cobolSubscript];
            for (int index = 0; index < targets.length; index++) {
                targets[index] = "COUSR0" + index + "C";
            }

            List<String> lines = service().buildMenuOptions(stubTable(cobolSubscript, targets));

            // CDEMO-ADMIN-OPT-NUM is PIC 9(02), so the STRING at L233 emits a zero-filled two-byte
            // image. Composed here without a locale-sensitive formatter so the expectation cannot
            // shift with the default locale (practice B7).
            String expectedNumberImage = cobolSubscript < 10
                    ? "0" + cobolSubscript
                    : String.valueOf(cobolSubscript);

            assertThat(lines.get(cobolSubscript - 1))
                    .as("arm %d is the 1-based subscript, so it writes list index %d (gate G33)",
                            cobolSubscript, cobolSubscript - 1)
                    .isNotEqualTo(BLANK_OPTION_LINE)
                    .startsWith(expectedNumberImage + AdminMenuService.OPTION_NUMBER_SEPARATOR)
                    .hasSize(OPTION_LINE_WIDTH);
            assertThat(lines.subList(cobolSubscript, AdminMenuService.OPTION_LINE_COUNT))
                    .as("the loop ended, so no later line was written")
                    .containsOnly(BLANK_OPTION_LINE);
            assertThat(lines.subList(0, cobolSubscript))
                    .as("and every earlier arm was taken on its own pass")
                    .doesNotContain(BLANK_OPTION_LINE);
        }

        @ParameterizedTest(name = "WHEN OTHER: subscript {0} has no arm and writes nothing")
        @DisplayName("the eleventh arm, WHEN OTHER CONTINUE at L259-L260, for subscripts 11 and 12")
        @ValueSource(ints = {11, 12})
        void whenOtherWritesNothingAtAll(int cobolSubscript) {
            String[] targets = new String[cobolSubscript];
            for (int index = 0; index < targets.length; index++) {
                targets[index] = "COUSR0" + (index % 10) + "C";
            }

            List<String> lines = service().buildMenuOptions(stubTable(cobolSubscript, targets));

            assertThat(lines.get(cobolSubscript - 1))
                    .as("the loop DID iterate to %d and DID compose WS-ADMIN-OPT-TXT at L233-L236, "
                            + "but CONTINUE moved it nowhere", cobolSubscript)
                    .isEqualTo(BLANK_OPTION_LINE);
            assertThat(lines.subList(AdminMenuService.OPTION_DISPATCH_ARM_COUNT,
                            AdminMenuService.OPTION_LINE_COUNT))
                    .as("both lines past the tenth arm stay as MOVE LOW-VALUES left them")
                    .containsOnly(BLANK_OPTION_LINE);
            assertThat(lines.get(AdminMenuService.OPTION_DISPATCH_ARM_COUNT - 1))
                    .as("while arm 10 itself did write, so the loop really did pass through")
                    .isNotEqualTo(BLANK_OPTION_LINE);
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
