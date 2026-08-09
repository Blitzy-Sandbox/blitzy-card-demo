package com.vsergeychik.carddemo.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.CicsResponse;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver.AidKey;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.user.SecUserRepository.HeldRecord;
import com.vsergeychik.carddemo.user.SecUserRepository.ReadResult;
import com.vsergeychik.carddemo.user.SecUserRepository.WriteResult;
import com.vsergeychik.carddemo.user.UserUpdateController.Cu02Info;
import com.vsergeychik.carddemo.user.UserUpdateController.ProgramState;
import com.vsergeychik.carddemo.user.UserUpdateController.ScreenField;
import com.vsergeychik.carddemo.user.UserUpdateController.Send;
import com.vsergeychik.carddemo.user.dto.UserUpdateRequest;
import com.vsergeychik.carddemo.user.dto.UserUpdateResponse;
import com.vsergeychik.carddemo.user.model.SecUserRecord;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link UserUpdateController} - the {@code COUSR02C} / {@code CU02} update-user screen.
 *
 * <p>Every test that asserts a <em>decision</em> constructs the controller directly with a mocked
 * {@link SecUserRepository} and a fixed {@link Clock} and calls
 * {@link UserUpdateController#handle(UserUpdateRequest, int, byte, Cu02Info)}. There is no HTTP layer,
 * no {@code MockMvc} and no Spring context in the decision path, which is gate <strong>G51</strong>: a
 * guard chain is asserted where it lives, so a failure names the COBOL paragraph rather than a status
 * code. Only {@link HttpAdapter} exercises the {@code PUT} adapter, and only for the projection rules
 * that live in it - the path variable binding and the {@code EIBCALEN} inference.
 *
 * <h2>Every expected value here is STATICALLY DERIVED, not captured from a run</h2>
 *
 * <p>The COBOL cannot be executed in this environment (open risk <strong>R-A</strong>: no z/OS runtime,
 * GnuCOBOL's indexed file handler reports {@code disabled}, no Language Environment {@code CEE*}
 * services, no CICS emulator, and {@code DFHAID} / {@code DFHBMSCA} / {@code DFHATTR} are absent from
 * the repository). Every value below was therefore read out of {@code app/cbl/COUSR02C.cbl},
 * {@code app/cpy-bms/COUSR02.CPY}, {@code app/bms/COUSR02.bms}, {@code app/cpy/CSUSR01Y.cpy} and
 * {@code app/csd/CARDDEMO.CSD}, and is cited at its point of use. A reader must not mistake any of them
 * for a captured value.
 *
 * <h2>The three things this class exists to pin</h2>
 *
 * <ol>
 *   <li><strong>{@code PF3} saves and {@code PF12} does not.</strong> {@code :111-119} calls
 *       {@code UPDATE-USER-INFO} before it transfers; {@code :124-126} transfers without calling it.
 *       Treating the two keys as interchangeable "exit" keys would silently drop a rewrite.</li>
 *   <li><strong>The five-way empty-field guard chain is ordered and short-circuits</strong>
 *       ({@code :180-211}), so a screen that is blank in two fields reports only the first.</li>
 *   <li><strong>An unmodified record is refused with {@code 'Please modify to update ...'}</strong>
 *       ({@code :236-242}) rather than rewritten, and the password is compared in <em>plaintext</em>
 *       ({@code :227}) exactly as the legacy program compares it - hashing would be a behaviour
 *       change.</li>
 * </ol>
 */
@DisplayName("UserUpdateController - the COUSR02C / CU02 update-user screen")
class UserUpdateControllerTest {

    /**
     * The clock every test runs against: {@code 2022-07-19T23:12:34Z} at UTC, taken from the version
     * footer date the sources carry. Fixed, so {@code POPULATE-HEADER-INFO} at {@code :296-315} is
     * reproducible.
     */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2022-07-19T23:12:34Z"), ZoneOffset.UTC);

    /** {@code CURDATEO} for {@link #FIXED_CLOCK}: {@code MM/DD/YY}. */
    private static final String EXPECTED_DATE = "07/19/22";

    /** {@code CURTIMEO} for {@link #FIXED_CLOCK}: {@code HH:MM:SS}. */
    private static final String EXPECTED_TIME = "23:12:34";

    /** The code page the fixtures are built in; single byte, and never the platform default. */
    private static final Charset CODE_PAGE = StandardCharsets.US_ASCII;

    /** A user id at the full declared width of {@code SEC-USR-ID PIC X(08)}. */
    private static final String USER_ID = "USER0001";

    /**
     * A stand-in for {@code SEC-USR-PWD PIC X(08)}. Deliberately an obvious placeholder: the field is
     * compared in clear text by {@code :227}, and no test here needs a credential-shaped value.
     */
    private static final String STORED_PWD = "PWDAAAAA";

    private SecUserRepository repository;

    private UserUpdateController controller;

    @BeforeEach
    void setUp() {
        repository = mock(SecUserRepository.class);
        controller = new UserUpdateController(repository, FIXED_CLOCK);
    }

    // =================================================================================================
    // Fixtures
    // =================================================================================================

    /** {@code CDEMO-PGM-CONTEXT = 1}: the operator is typing into the map, so the map is received. */
    private static NavigationContext reenter() {
        return NavigationContext.empty().withPgmReenter();
    }

    /** A terminal input area carrying the five typed items and a communication area. */
    private static UserUpdateRequest screen(String usrIdIn,
                                            String fName,
                                            String lName,
                                            String passwd,
                                            String usrType,
                                            NavigationContext commarea) {
        return new UserUpdateRequest(null, null, null, null, null, null,
                usrIdIn, fName, lName, passwd, usrType, null, commarea, null);
    }

    /** The screen a fully populated, valid update carries. */
    private static UserUpdateRequest populated(NavigationContext commarea) {
        return screen(USER_ID, "Sam", "Spade", STORED_PWD, "U", commarea);
    }

    /** An 80-byte {@code SEC-USER-DATA} for the given key, with the fixture's names and password. */
    private static SecUserRecord storedUser() {
        return SecUserRecord.of(USER_ID, "Sam", "Spade", STORED_PWD, "U", CODE_PAGE);
    }

    /** The commarea extension a cold-started list hand-off would leave, selecting nobody. */
    private static Cu02Info noSelection() {
        return Cu02Info.initial();
    }

    /** {@code MOVE WS-MESSAGE TO ERRMSGO} - the text as the 78-character screen field holds it. */
    private static String errMsgImage(String text) {
        return text + " ".repeat(UserUpdateResponse.ERR_MSG_LENGTH - text.length());
    }

    /** A value as an {@code xxxI} item of the given width holds it: padded on the right with spaces. */
    private static String padded(String text, int length) {
        return text + " ".repeat(length - text.length());
    }

    /** The rendering of {@code DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD} at {@code :347}. */
    private static String displayLine(int resp, int reas) {
        return "RESP:" + String.format("%09d", resp) + "REAS:" + String.format("%09d", reas);
    }

    /** Stub {@code :322-331} as a successful locking read of the stored record. */
    private void stubFoundRead() {
        when(repository.readForUpdate(anyString())).thenReturn(ReadResult.found(storedUser()));
    }

    /** Drive {@code MAIN-PARA} on the re-entry path for the given key. */
    private ProgramState reentryWith(UserUpdateRequest request, byte aid) {
        return controller.handle(request, UserUpdateController.PASSED_COMMAREA_LENGTH, aid,
                noSelection());
    }

    // =================================================================================================
    // Construction and identity
    // =================================================================================================

    @Nested
    @DisplayName("Construction - two collaborators, both required, no mutable static")
    class Construction {

        @Test
        @DisplayName("a missing repository is refused: COUSR02C reaches USRSEC no other way")
        void repositoryIsRequired() {
            assertThatThrownBy(() -> new UserUpdateController(null, FIXED_CLOCK))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("SecUserRepository");
        }

        @Test
        @DisplayName("a missing clock is refused: POPULATE-HEADER-INFO reads FUNCTION CURRENT-DATE")
        void clockIsRequired() {
            assertThatThrownBy(() -> new UserUpdateController(repository, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Clock");
        }

        @Test
        @DisplayName("identity matches app/csd/CARDDEMO.CSD: transaction CU02, program COUSR02C")
        void identityIsPinned() {
            assertThat(UserUpdateController.WS_TRANID).isEqualTo("CU02");
            assertThat(UserUpdateController.WS_PGMNAME).isEqualTo("COUSR02C");
            assertThat(UserUpdateController.LIT_SIGNON_PGM).isEqualTo("COSGN00C");
            assertThat(UserUpdateController.LIT_ADMIN_PGM).isEqualTo("COADM01C");
            assertThat(UserUpdateController.WS_MESSAGE_LENGTH).isEqualTo(80);
            assertThat(UserUpdateController.WS_RESP_CD_DIGITS).isEqualTo(9);
            assertThat(UserUpdateController.WS_USRSEC_FILE)
                    .isEqualTo(SecUserRepository.CICS_FILE_NAME_IMAGE);
            assertThat(UserUpdateController.WORKING_STORAGE_CHARSET).isEqualTo(CODE_PAGE);
            assertThat(UserUpdateController.PASSED_COMMAREA_LENGTH)
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH + Cu02Info.LENGTH);
            assertThat(UserUpdateController.NO_COMMAREA_LENGTH).isZero();
        }

        @Test
        @DisplayName("G53: every static field is final, so no request can see another's WORKING-STORAGE")
        void noStaticMutableState() {
            for (Field field : UserUpdateController.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("static field %s must be final", field.getName())
                            .isTrue();
                }
            }
        }
    }

    // =================================================================================================
    // MAIN-PARA - the cold start and the two entry arms - :82-138
    // =================================================================================================

    @Nested
    @DisplayName("MAIN-PARA :82-138 - the cold start and the two entry arms")
    class MainPara {

        @Test
        @DisplayName(":90-92 EIBCALEN = 0 transfers to COSGN00C and runs nothing else")
        void coldStartTransfersToSignOn() {
            ProgramState state = controller.handle(populated(reenter()),
                    UserUpdateController.NO_COMMAREA_LENGTH, CicsAid.DFHENTER, noSelection());

            assertThat(state.transferred()).isTrue();
            assertThat(state.nextProgram()).isEqualTo(UserUpdateController.LIT_SIGNON_PGM);
            assertThat(state.termination()).isEqualTo(UserUpdateController.TERMINATION_XCTL);
            assertThat(state.screenSent()).isFalse();
            assertThat(state.commarea().fromTranid()).isEqualTo("CU02");
            assertThat(state.commarea().fromProgram()).isEqualTo(padded("COUSR02C", 8));
            assertThat(state.commarea().pgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_ENTER);
            assertThat(state.returnTransid()).isEqualTo("CU02");
            verify(repository, never()).readForUpdate(anyString());
            verify(repository, never()).rewrite(any(SecUserRecord.class));
        }

        @Test
        @DisplayName(":95-105 first entry with no row selected: blank the buffer, ask for the cursor, send")
        void firstEntryWithoutSelectionOnlyPaints() {
            ProgramState state = controller.handle(populated(NavigationContext.empty()),
                    UserUpdateController.PASSED_COMMAREA_LENGTH, CicsAid.DFHENTER, noSelection());

            assertThat(state.commarea().isReenter()).isTrue();
            assertThat(state.cursorRequestedOn(ScreenField.USRIDIN)).isTrue();
            assertThat(state.sendCount()).isEqualTo(1);
            assertThat(state.transferred()).isFalse();
            assertThat(state.termination())
                    .isEqualTo(UserUpdateController.TERMINATION_RETURN_TRANSID);
            // :97 blanked the buffer, so the user id the payload carried is gone.
            assertThat(state.usrIdIn()).isEqualTo("\u0000".repeat(8));
            verify(repository, never()).readForUpdate(anyString());
        }

        @Test
        @DisplayName(":99-103 first entry WITH a selected row looks that user up before sending")
        void firstEntryWithSelectionLooksTheUserUp() {
            stubFoundRead();
            Cu02Info selected = new Cu02Info(null, null, 3, Cu02Info.NEXT_PAGE_YES, "S", USER_ID);

            ProgramState state = controller.handle(populated(NavigationContext.empty()),
                    UserUpdateController.PASSED_COMMAREA_LENGTH, CicsAid.DFHENTER, selected);

            verify(repository).readForUpdate(USER_ID);
            assertThat(state.usrIdIn()).isEqualTo(USER_ID);
            assertThat(state.fName()).isEqualTo(padded("Sam", 20));
            assertThat(state.lName()).isEqualTo(padded("Spade", 20));
            assertThat(state.passwd()).isEqualTo(STORED_PWD);
            assertThat(state.usrType()).isEqualTo("U");
            // PROCESS-ENTER-KEY sent at :171, then MAIN-PARA sent again at :105.
            assertThat(state.sendCount()).isEqualTo(3);
            assertThat(state.cu02Info().pageNum()).isEqualTo(3);
        }

        @Test
        @DisplayName(":95 is NOT CDEMO-PGM-REENTER, so a context of 9 takes the first-entry arm")
        void aContextThatIsNeitherEnterNorReenterTakesTheFirstEntryArm() {
            NavigationContext neither = NavigationContext.empty().withPgmContext(9);
            assertThat(neither.isEnter()).isFalse();
            assertThat(neither.isReenter()).isFalse();

            ProgramState state = controller.handle(populated(neither),
                    UserUpdateController.PASSED_COMMAREA_LENGTH, CicsAid.DFHENTER, noSelection());

            assertThat(state.commarea().isReenter()).isTrue();
            assertThat(state.sendCount()).isEqualTo(1);
            verify(repository, never()).readForUpdate(anyString());
        }

        @Test
        @DisplayName(":107 the re-entry arm receives the map, which reports RESP NORMAL")
        void reentryReceivesTheMap() {
            stubFoundRead();
            ProgramState state = reentryWith(populated(reenter()), CicsAid.DFHENTER);

            assertThat(state.wsReasCd()).isEqualTo(FileStatus.NO_REASON_CODE);
            assertThat(state.aidKey()).contains(AidKey.ENTER);
        }

        @Test
        @DisplayName(":127-130 WHEN OTHER flags the error and paints CCDA-MSG-INVALID-KEY")
        void anUnhandledKeyIsAnInvalidKey() {
            ProgramState state = reentryWith(populated(reenter()), CicsAid.DFHPF7);

            assertThat(state.errFlgOn()).isTrue();
            assertThat(state.wsErrFlg()).isEqualTo(UserUpdateController.ERR_FLG_ON);
            assertThat(state.wsMessage())
                    .isEqualTo(padded(SystemMessages.CCDA_MSG_INVALID_KEY, 80));
            assertThat(state.errMsg()).isEqualTo(errMsgImage(SystemMessages.CCDA_MSG_INVALID_KEY));
            assertThat(state.sendCount()).isEqualTo(1);
            verify(repository, never()).readForUpdate(anyString());
        }

        @Test
        @DisplayName("an AID PfKeyResolver cannot resolve at all also reaches WHEN OTHER")
        void anUnresolvableAidAlsoReachesWhenOther() {
            ProgramState state = reentryWith(populated(reenter()), CicsAid.DFHNULL);

            assertThat(state.aidKey()).isEmpty();
            assertThat(state.errFlgOn()).isTrue();
            assertThat(state.wsMessage())
                    .isEqualTo(padded(SystemMessages.CCDA_MSG_INVALID_KEY, 80));
        }

        @Test
        @DisplayName(":120-121 PF4 clears every field and repaints, touching no dataset")
        void pf4ClearsTheScreen() {
            ProgramState state = reentryWith(populated(reenter()), CicsAid.DFHPF4);

            assertThat(state.usrIdIn()).isEqualTo(" ".repeat(8));
            assertThat(state.fName()).isEqualTo(" ".repeat(20));
            assertThat(state.lName()).isEqualTo(" ".repeat(20));
            assertThat(state.passwd()).isEqualTo(" ".repeat(8));
            assertThat(state.usrType()).isEqualTo(" ");
            assertThat(state.wsMessage()).isEqualTo(" ".repeat(80));
            assertThat(state.cursorRequestedOn(ScreenField.USRIDIN)).isTrue();
            assertThat(state.sendCount()).isEqualTo(1);
            assertThat(state.errFlgOn()).isFalse();
            verify(repository, never()).readForUpdate(anyString());
            verify(repository, never()).rewrite(any(SecUserRecord.class));
        }

        @Test
        @DisplayName(":124-126 PF12 transfers to COADM01C and does NOT save")
        void pf12CancelsWithoutSaving() {
            ProgramState state = reentryWith(populated(reenter()), CicsAid.DFHPF12);

            assertThat(state.transferred()).isTrue();
            assertThat(state.nextProgram()).isEqualTo(padded("COADM01C", 8));
            verify(repository, never()).readForUpdate(anyString());
            verify(repository, never()).rewrite(any(SecUserRecord.class));
        }

        @Test
        @DisplayName(":111-119 PF3 SAVES first, then echoes CDEMO-FROM-PROGRAM as the target")
        void pf3SavesThenReturnsToTheCallingProgram() {
            stubFoundRead();
            when(repository.rewrite(any(SecUserRecord.class))).thenReturn(WriteResult.written());
            NavigationContext from = reenter().withFromProgram("COUSR00C");

            ProgramState state = reentryWith(screen(USER_ID, "Sammy", "Spade", STORED_PWD, "U", from),
                    CicsAid.DFHPF3);

            verify(repository).rewrite(any(SecUserRecord.class));
            assertThat(state.transferred()).isTrue();
            assertThat(state.nextProgram()).isEqualTo(padded("COUSR00C", 8));
        }

        @Test
        @DisplayName(":113-114 PF3 with a blank CDEMO-FROM-PROGRAM falls back to COADM01C")
        void pf3WithoutACallerFallsBackToTheAdminMenu() {
            stubFoundRead();
            when(repository.rewrite(any(SecUserRecord.class))).thenReturn(WriteResult.written());

            ProgramState state = reentryWith(
                    screen(USER_ID, "Sammy", "Spade", STORED_PWD, "U", reenter()), CicsAid.DFHPF3);

            verify(repository).rewrite(any(SecUserRecord.class));
            assertThat(state.nextProgram()).isEqualTo(padded("COADM01C", 8));
        }

        @Test
        @DisplayName(":112 PF3 transfers even when validation failed, because the source has no guard")
        void pf3TransfersEvenWhenValidationFails() {
            ProgramState state = reentryWith(screen("   ", "Sam", "Spade", STORED_PWD, "U", reenter()),
                    CicsAid.DFHPF3);

            assertThat(state.errFlgOn()).isTrue();
            assertThat(state.wsMessage())
                    .isEqualTo(padded(UserUpdateController.MSG_USER_ID_EMPTY, 80));
            assertThat(state.transferred()).isTrue();
            verify(repository, never()).rewrite(any(SecUserRecord.class));
        }

        @Test
        @DisplayName(":135-138 EXEC CICS RETURN TRANSID(CU02) is recorded on every path")
        void everyPathRecordsTheReturnTransid() {
            stubFoundRead();
            assertThat(reentryWith(populated(reenter()), CicsAid.DFHENTER).returnTransid())
                    .isEqualTo("CU02");
            assertThat(reentryWith(populated(reenter()), CicsAid.DFHPF12).returnTransid())
                    .isEqualTo("CU02");
        }

        @Test
        @DisplayName("a null request or a null Cu02Info is refused")
        void nullArgumentsAreRefused() {
            assertThatThrownBy(() -> controller.handle(null, 0, CicsAid.DFHENTER, noSelection()))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> controller.handle(populated(reenter()), 0, CicsAid.DFHENTER, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // =================================================================================================
    // PROCESS-ENTER-KEY - :143-172
    // =================================================================================================

    @Nested
    @DisplayName("PROCESS-ENTER-KEY :143-172 - look the user up and paint the record")
    class ProcessEnterKey {

        @Test
        @DisplayName(":146-151 a blank user id is 'User ID can NOT be empty...' with the cursor on USRIDIN")
        void aBlankUserIdIsRefused() {
            ProgramState state = reentryWith(screen("        ", "Sam", "Spade", STORED_PWD, "U",
                    reenter()), CicsAid.DFHENTER);

            assertThat(state.errFlgOn()).isTrue();
            assertThat(state.wsMessage())
                    .isEqualTo(padded(UserUpdateController.MSG_USER_ID_EMPTY, 80));
            assertThat(state.cursorRequestedOn(ScreenField.USRIDIN)).isTrue();
            assertThat(state.sendCount()).isEqualTo(1);
            verify(repository, never()).readForUpdate(anyString());
        }

        @Test
        @DisplayName(":157-171 a found user paints the four fields and sends twice")
        void aFoundUserIsPaintedOnTheScreen() {
            stubFoundRead();

            ProgramState state = reentryWith(screen(USER_ID, "typed", "over", "TYPEDPWD", "A",
                    reenter()), CicsAid.DFHENTER);

            verify(repository).readForUpdate(USER_ID);
            assertThat(state.fName()).isEqualTo(padded("Sam", 20));
            assertThat(state.lName()).isEqualTo(padded("Spade", 20));
            assertThat(state.passwd()).isEqualTo(STORED_PWD);
            assertThat(state.usrType()).isEqualTo("U");
            // :339 sent once inside READ-USER-SEC-FILE, then :171 sent again.
            assertThat(state.sendCount()).isEqualTo(2);
            assertThat(state.errFlgOn()).isFalse();
            assertThat(state.hold()).isEmpty();
        }

        @Test
        @DisplayName(":340-345 NOTFND is 'User ID NOT found...' and stops before the paint")
        void aMissingUserIsReported() {
            when(repository.readForUpdate(anyString())).thenReturn(ReadResult.notFound());

            ProgramState state = reentryWith(populated(reenter()), CicsAid.DFHENTER);

            assertThat(state.errFlgOn()).isTrue();
            assertThat(state.wsMessage())
                    .isEqualTo(padded(UserUpdateController.MSG_USER_NOT_FOUND, 80));
            assertThat(state.cursorRequestedOn(ScreenField.USRIDIN)).isTrue();
            // :158-161 blanked the fields and the paint at :167-170 was never reached.
            assertThat(state.fName()).isEqualTo(" ".repeat(20));
            assertThat(state.sendCount()).isEqualTo(1);
        }

        @Test
        @DisplayName(":346-352 WHEN OTHER displays RESP/REAS and says 'Unable to lookup User...'")
        void anUnexpectedResponseIsDisplayedAndReported() {
            when(repository.readForUpdate(anyString()))
                    .thenReturn(ReadResult.of(FileStatus.END_OF_FILE, CicsResponse.reported(17, 42)));

            ProgramState state = reentryWith(populated(reenter()), CicsAid.DFHENTER);

            assertThat(state.wsRespCd()).isEqualTo(17);
            assertThat(state.wsReasCd()).isEqualTo(42);
            assertThat(state.displayLines()).containsExactly(displayLine(17, 42));
            assertThat(state.wsMessage())
                    .isEqualTo(padded(UserUpdateController.MSG_UNABLE_TO_LOOKUP, 80));
            assertThat(state.cursorRequestedOn(ScreenField.FNAME)).isTrue();
            assertThat(state.errFlgOn()).isTrue();
        }

        @Test
        @DisplayName(":334-339 a successful read reports 'Press PF5 key...' in DFHNEUTR")
        void aSuccessfulReadPromptsForPf5() {
            stubFoundRead();

            ProgramState state = reentryWith(populated(reenter()), CicsAid.DFHENTER);

            assertThat(state.sends().get(0).value("ERRMSGO"))
                    .isEqualTo(errMsgImage(UserUpdateController.MSG_PRESS_PF5));
            assertThat(state.sends().get(0).errMsgColour()).isEqualTo(BmsAttributes.DFHNEUTR);
        }

        @Test
        @DisplayName("a locking read hands back the hold the task took")
        void aHeldReadCarriesItsHold() {
            HeldRecord hold = mock(HeldRecord.class);
            when(repository.readForUpdate(anyString()))
                    .thenReturn(ReadResult.held(storedUser(), hold));

            ProgramState state = reentryWith(populated(reenter()), CicsAid.DFHENTER);

            assertThat(state.hold()).contains(hold);
        }
    }

    // =================================================================================================
    // UPDATE-USER-INFO - :176-243
    // =================================================================================================

    @Nested
    @DisplayName("UPDATE-USER-INFO :176-243 - the ordered guard chain and the modification test")
    class UpdateUserInfo {

        @ParameterizedTest(name = "{0} empty -> \"{1}\", cursor on {2}")
        @CsvSource({
            "usrIdIn, User ID can NOT be empty..., USRIDIN",
            "fName,   First Name can NOT be empty..., FNAME",
            "lName,   Last Name can NOT be empty..., LNAME",
            "passwd,  Password can NOT be empty..., PASSWD",
            "usrType, User Type can NOT be empty..., USRTYPE"
        })
        @DisplayName(":180-209 the chain is ordered and reports only the first empty field")
        void theGuardChainIsOrdered(String blankField, String message, ScreenField cursor) {
            String id = "usrIdIn".equals(blankField) ? "  " : USER_ID;
            String first = "fName".equals(blankField) ? "  " : "Sam";
            String last = "lName".equals(blankField) ? "  " : "Spade";
            String pwd = "passwd".equals(blankField) ? "  " : STORED_PWD;
            String type = "usrType".equals(blankField) ? " " : "U";

            ProgramState state = reentryWith(screen(id, first, last, pwd, type, reenter()),
                    CicsAid.DFHPF5);

            assertThat(state.errFlgOn()).isTrue();
            assertThat(state.wsMessage()).isEqualTo(padded(message, 80));
            assertThat(state.cursorRequestedOn(cursor)).isTrue();
            assertThat(state.sendCount()).isEqualTo(1);
            verify(repository, never()).readForUpdate(anyString());
            verify(repository, never()).rewrite(any(SecUserRecord.class));
        }

        @Test
        @DisplayName("a screen blank in two fields reports only the earlier one: the chain short-circuits")
        void theChainShortCircuits() {
            ProgramState state = reentryWith(screen(USER_ID, "  ", "  ", STORED_PWD, "U", reenter()),
                    CicsAid.DFHPF5);

            assertThat(state.wsMessage())
                    .isEqualTo(padded(UserUpdateController.MSG_FIRST_NAME_EMPTY, 80));
            assertThat(state.cursorRequestedOn(ScreenField.FNAME)).isTrue();
            assertThat(state.cursorRequestedOn(ScreenField.LNAME)).isFalse();
        }

        @Test
        @DisplayName(":210-211 a fully populated screen puts the cursor on FNAME and proceeds")
        void aValidScreenProceeds() {
            stubFoundRead();
            when(repository.rewrite(any(SecUserRecord.class))).thenReturn(WriteResult.written());

            ProgramState state = reentryWith(
                    screen(USER_ID, "Sammy", "Spade", STORED_PWD, "U", reenter()), CicsAid.DFHPF5);

            assertThat(state.cursorRequestedOn(ScreenField.FNAME)).isTrue();
            assertThat(state.errFlgOn()).isFalse();
            verify(repository).rewrite(any(SecUserRecord.class));
        }

        @ParameterizedTest(name = "changing {0} sets USR-MODIFIED-YES")
        @ValueSource(strings = {"fName", "lName", "passwd", "usrType"})
        @DisplayName(":219-233 each of the four comparisons independently marks the record modified")
        void everyFieldComparisonMarksTheRecordModified(String changed) {
            stubFoundRead();
            when(repository.rewrite(any(SecUserRecord.class))).thenReturn(WriteResult.written());

            ProgramState state = reentryWith(screen(USER_ID,
                    "fName".equals(changed) ? "Samuel" : "Sam",
                    "lName".equals(changed) ? "Spadey" : "Spade",
                    "passwd".equals(changed) ? "PWDBBBBB" : STORED_PWD,
                    "usrType".equals(changed) ? "A" : "U",
                    reenter()), CicsAid.DFHPF5);

            assertThat(state.usrModifiedYes()).isTrue();
            assertThat(state.wsUsrModified()).isEqualTo(UserUpdateController.USR_MODIFIED_YES);
            verify(repository).rewrite(any(SecUserRecord.class));
        }

        @Test
        @DisplayName(":236-242 an unmodified record is refused with 'Please modify to update ...' in red")
        void anUnmodifiedRecordIsNotRewritten() {
            stubFoundRead();

            ProgramState state = reentryWith(populated(reenter()), CicsAid.DFHPF5);

            assertThat(state.usrModifiedYes()).isFalse();
            assertThat(state.wsUsrModified()).isEqualTo(UserUpdateController.USR_MODIFIED_NO);
            assertThat(state.wsMessage())
                    .isEqualTo(padded(UserUpdateController.MSG_PLEASE_MODIFY, 80));
            assertThat(state.errMsgColour()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(state.errMsgColourMnemonic())
                    .isEqualTo(BmsAttributes.colourMnemonic(BmsAttributes.DFHRED));
            verify(repository, never()).rewrite(any(SecUserRecord.class));
        }

        @Test
        @DisplayName(":227 the password is compared in PLAINTEXT, exactly as the legacy program does")
        void thePasswordComparisonIsPlaintext() {
            stubFoundRead();
            when(repository.rewrite(any(SecUserRecord.class))).thenReturn(WriteResult.written());

            reentryWith(screen(USER_ID, "Sam", "Spade", "PWDBBBBB", "U", reenter()), CicsAid.DFHPF5);

            org.mockito.ArgumentCaptor<SecUserRecord> captor =
                    org.mockito.ArgumentCaptor.forClass(SecUserRecord.class);
            verify(repository).rewrite(captor.capture());
            assertThat(captor.getValue().secUsrPwd()).isEqualTo("PWDBBBBB");
        }

        @Test
        @DisplayName(":217-237 a failed read does NOT stop the rewrite, because :219 carries no guard")
        void aFailedReadStillReachesTheRewrite() {
            // READ-USER-SEC-FILE reports NOTFND and sets ERR-FLG-ON, but lines 219-233 are not inside
            // an IF NOT ERR-FLG-ON block: they compare the typed fields against the record area, which
            // after a failed read is still SEC-USER-DATA's initialised value. Every comparison
            // therefore differs, USR-MODIFIED-YES is set, and line 237 issues the REWRITE. Adding the
            // guard the code reads as if it had would be a behaviour change, so it is pinned here.
            when(repository.readForUpdate(anyString())).thenReturn(ReadResult.notFound());
            when(repository.rewrite(any(SecUserRecord.class))).thenReturn(WriteResult.notFound());

            ProgramState state = reentryWith(populated(reenter()), CicsAid.DFHPF5);

            assertThat(state.errFlgOn()).isTrue();
            assertThat(state.usrModifiedYes()).isTrue();
            assertThat(state.wsMessage())
                    .isEqualTo(padded(UserUpdateController.MSG_USER_NOT_FOUND, 80));
            verify(repository).rewrite(any(SecUserRecord.class));
        }
    }

    // =================================================================================================
    // UPDATE-USER-SEC-FILE - :356-390
    // =================================================================================================

    @Nested
    @DisplayName("UPDATE-USER-SEC-FILE :356-390 - the three rewrite outcomes")
    class UpdateUserSecFile {

        @BeforeEach
        void stubLookup() {
            stubFoundRead();
        }

        private ProgramState save() {
            return reentryWith(screen(USER_ID, "Samuel", "Spade", STORED_PWD, "U", reenter()),
                    CicsAid.DFHPF5);
        }

        @Test
        @DisplayName(":369-376 a written record confirms 'User USER0001 has been updated ...' in green")
        void aWrittenRecordIsConfirmed() {
            when(repository.rewrite(any(SecUserRecord.class))).thenReturn(WriteResult.written());

            ProgramState state = save();

            assertThat(state.wsMessage())
                    .isEqualTo(padded("User " + USER_ID + " has been updated ...", 80));
            assertThat(state.errMsgColour()).isEqualTo(BmsAttributes.DFHGREEN);
            assertThat(state.errFlgOn()).isFalse();
        }

        @Test
        @DisplayName(":377-382 NOTFND on the rewrite is 'User ID NOT found...' with the cursor on USRIDIN")
        void aMissingRecordIsReported() {
            when(repository.rewrite(any(SecUserRecord.class))).thenReturn(WriteResult.notFound());

            ProgramState state = save();

            assertThat(state.errFlgOn()).isTrue();
            assertThat(state.wsMessage())
                    .isEqualTo(padded(UserUpdateController.MSG_USER_NOT_FOUND, 80));
            assertThat(state.cursorRequestedOn(ScreenField.USRIDIN)).isTrue();
        }

        @Test
        @DisplayName(":383-389 WHEN OTHER displays RESP/REAS and says 'Unable to Update User...'")
        void anUnexpectedResponseIsReported() {
            when(repository.rewrite(any(SecUserRecord.class)))
                    .thenReturn(WriteResult.of(FileStatus.DUPLICATE, CicsResponse.reported(15, 8)));

            ProgramState state = save();

            assertThat(state.wsRespCd()).isEqualTo(15);
            assertThat(state.wsReasCd()).isEqualTo(8);
            assertThat(state.displayLines()).contains(displayLine(15, 8));
            assertThat(state.wsMessage())
                    .isEqualTo(padded(UserUpdateController.MSG_UNABLE_TO_UPDATE, 80));
            assertThat(state.cursorRequestedOn(ScreenField.FNAME)).isTrue();
            assertThat(state.errFlgOn()).isTrue();
        }

        @Test
        @DisplayName("a rewrite outcome that reports no CICS response defaults WS-RESP-CD to NORMAL")
        void anAbsentCicsResponseDefaultsToNormal() {
            when(repository.rewrite(any(SecUserRecord.class))).thenReturn(WriteResult.written());

            ProgramState state = save();

            assertThat(state.wsRespCd()).isEqualTo(FileStatus.NORMAL);
        }
    }

    // =================================================================================================
    // RETURN-TO-PREV-SCREEN - :250-261
    // =================================================================================================

    @Nested
    @DisplayName("RETURN-TO-PREV-SCREEN :250-261")
    class ReturnToPrevScreen {

        @Test
        @DisplayName(":252-253 a blank CDEMO-TO-PROGRAM becomes COSGN00C")
        void aBlankTargetBecomesSignOn() {
            ProgramState state = reentryWith(populated(reenter()), CicsAid.DFHPF12);
            // PF12 sets COADM01C at :125, so reach the blank arm through the cold start instead.
            assertThat(state.nextProgram()).isEqualTo(padded("COADM01C", 8));

            ProgramState cold = controller.handle(populated(NavigationContext.empty()),
                    UserUpdateController.NO_COMMAREA_LENGTH, CicsAid.DFHENTER, noSelection());
            assertThat(cold.nextProgram()).isEqualTo(UserUpdateController.LIT_SIGNON_PGM);
        }

        @Test
        @DisplayName(":255-257 the transfer stamps CU02, COUSR02C and CDEMO-PGM-ENTER")
        void theTransferStampsTheCommarea() {
            ProgramState state = reentryWith(populated(reenter()), CicsAid.DFHPF12);

            assertThat(state.commarea().fromTranid()).isEqualTo("CU02");
            assertThat(state.commarea().fromProgram()).isEqualTo(padded("COUSR02C", 8));
            assertThat(state.commarea().pgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_ENTER);
            assertThat(state.transferred()).isTrue();
        }
    }

    // =================================================================================================
    // POPULATE-HEADER-INFO - :296-315
    // =================================================================================================

    @Nested
    @DisplayName("POPULATE-HEADER-INFO :296-315 - the header every send carries")
    class HeaderInfo {

        @Test
        @DisplayName("the titles, transaction, program, date and time are painted from the fixed clock")
        void theHeaderIsPaintedOnEverySend() {
            ProgramState state = reentryWith(populated(reenter()), CicsAid.DFHPF4);

            assertThat(state.title01())
                    .isEqualTo(padded(ScreenTitles.CCDA_TITLE01, UserUpdateResponse.TITLE01_LENGTH));
            assertThat(state.title02())
                    .isEqualTo(padded(ScreenTitles.CCDA_TITLE02, UserUpdateResponse.TITLE02_LENGTH));
            assertThat(state.trnName()).isEqualTo("CU02");
            assertThat(state.pgmName()).isEqualTo(padded("COUSR02C", 8));
            assertThat(state.curDate()).isEqualTo(EXPECTED_DATE);
            assertThat(state.curTime()).isEqualTo(EXPECTED_TIME);
        }

        @Test
        @DisplayName(":270 WS-MESSAGE is eighty and ERRMSGO is seventy-eight, so the move truncates")
        void theMessageIsTruncatedIntoTheScreenField() {
            ProgramState state = reentryWith(populated(reenter()), CicsAid.DFHPF7);

            assertThat(state.wsMessage()).hasSize(80);
            assertThat(state.errMsg()).hasSize(UserUpdateResponse.ERR_MSG_LENGTH);
            assertThat(state.errMsg()).isEqualTo(state.wsMessage().substring(0, 78));
        }
    }

    // =================================================================================================
    // resolveAttentionIdentifier - the token-to-EIBAID inverse
    // =================================================================================================

    @Nested
    @DisplayName("resolveAttentionIdentifier - the AID token inverse")
    class AttentionIdentifier {

        @Test
        @DisplayName("an absent token is DFHENTER, because a bare transmit is ENTER")
        void anAbsentTokenIsEnter() {
            assertThat(UserUpdateController.resolveAttentionIdentifier(null))
                    .isEqualTo(CicsAid.DFHENTER);
        }

        @ParameterizedTest(name = "{0} -> the byte this screen branches on")
        @CsvSource({"ENTER", "PFK03", "PFK04", "PFK05", "PFK12"})
        @DisplayName("the five keys the screen names round-trip to their own byte")
        void theFiveNamedKeysRoundTrip(String token) {
            byte expected = switch (token) {
                case "ENTER" -> CicsAid.DFHENTER;
                case "PFK03" -> CicsAid.DFHPF3;
                case "PFK04" -> CicsAid.DFHPF4;
                case "PFK05" -> CicsAid.DFHPF5;
                default -> CicsAid.DFHPF12;
            };
            assertThat(UserUpdateController.resolveAttentionIdentifier(token)).isEqualTo(expected);
            assertThat(UserUpdateController.resolveAttentionIdentifier(
                    AidKey.valueOf(token).token())).isEqualTo(expected);
        }

        @ParameterizedTest(name = "\"{0}\" has no WHEN clause, so it is DFHNULL")
        @ValueSource(strings = {"PFK01", "CLEAR", "PA1  ", "nope", ""})
        @DisplayName("every other token is DFHNULL, which reaches WHEN OTHER at :127")
        void everyOtherTokenIsNull(String token) {
            assertThat(UserUpdateController.resolveAttentionIdentifier(token))
                    .isEqualTo(CicsAid.DFHNULL);
        }
    }

    // =================================================================================================
    // The figurative-constant rules and the images
    // =================================================================================================

    @Nested
    @DisplayName("The COBOL figurative-constant rules, stated once each")
    class FigurativeConstants {

        @ParameterizedTest(name = "\"{0}\" is SPACES or LOW-VALUES")
        @ValueSource(strings = {"        ", " ", "\u0000\u0000", "\u0000"})
        @DisplayName("a field of all spaces or all LOW-VALUES tests true")
        void spacesAndLowValuesTestTrue(String image) {
            assertThat(UserUpdateController.isSpacesOrLowValues(image)).isTrue();
        }

        @ParameterizedTest(name = "\"{0}\" is neither")
        @ValueSource(strings = {"A", " A ", "\u0000A", "A\u0000"})
        @DisplayName("a field with any other byte tests false, whichever end it sits at")
        void anythingElseTestsFalse(String image) {
            assertThat(UserUpdateController.isSpacesOrLowValues(image)).isFalse();
        }

        @Test
        @DisplayName("an empty image is vacuously both, and an absent one is refused")
        void theDegenerateCases() {
            assertThat(UserUpdateController.isSpacesOrLowValues("")).isTrue();
            assertThatThrownBy(() -> UserUpdateController.isSpacesOrLowValues(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("an absent received value is LOW-VALUES; a present one is a PIC X move")
        void receivedImageRendersAbsenceAsLowValues() {
            assertThat(UserUpdateController.receivedImage(null, 4)).isEqualTo("\u0000".repeat(4));
            assertThat(UserUpdateController.receivedImage("AB", 4)).isEqualTo("AB  ");
            assertThat(UserUpdateController.receivedImage("ABCDE", 4)).isEqualTo("ABCD");
        }

        @Test
        @DisplayName("spaces(n) is exactly n spaces")
        void spacesIsExact() {
            assertThat(UserUpdateController.spaces(3)).isEqualTo("   ");
            assertThat(UserUpdateController.spaces(0)).isEmpty();
        }

        @Test
        @DisplayName("STRING ... DELIMITED BY SPACE stops at the first space, or takes the whole field")
        void delimitedBySpaceStopsAtTheFirstSpace() {
            assertThat(UserUpdateController.delimitedBySpace("USER0001")).isEqualTo("USER0001");
            assertThat(UserUpdateController.delimitedBySpace("AB  CD")).isEqualTo("AB");
            assertThat(UserUpdateController.delimitedBySpace(" X")).isEmpty();
            assertThatThrownBy(() -> UserUpdateController.delimitedBySpace(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName(":372-375 the confirmation trims the key at its first space and fits eighty")
        void theConfirmationIsComposedFromTheTrimmedKey() {
            assertThat(UserUpdateController.updatedConfirmation("USER0001"))
                    .isEqualTo(padded("User USER0001 has been updated ...", 80));
            assertThat(UserUpdateController.updatedConfirmation("AB      "))
                    .isEqualTo(padded("User AB has been updated ...", 80));
        }

        @Test
        @DisplayName(":347 the DISPLAY line zero-fills to nine digits and keeps a sign")
        void theDisplayLineIsNineDigitsWide() {
            assertThat(UserUpdateController.displayLine(0, 0))
                    .isEqualTo("RESP:000000000REAS:000000000");
            assertThat(UserUpdateController.displayLine(13, 8))
                    .isEqualTo("RESP:000000013REAS:000000008");
            assertThat(UserUpdateController.displayLine(-1, 123456789))
                    .isEqualTo("RESP:-000000001REAS:123456789");
        }
    }

    // =================================================================================================
    // CDEMO-CU02-INFO - :50-58
    // =================================================================================================

    @Nested
    @DisplayName("Cu02Info :50-58 - this program's own 34-byte commarea extension")
    class Cu02InfoTests {

        @Test
        @DisplayName("the declared widths sum to thirty-four")
        void theWidthsSumToThirtyFour() {
            assertThat(Cu02Info.LENGTH).isEqualTo(34);
            assertThat(Cu02Info.USRID_FIRST_LENGTH).isEqualTo(8);
            assertThat(Cu02Info.USRID_LAST_LENGTH).isEqualTo(8);
            assertThat(Cu02Info.PAGE_NUM_DIGITS).isEqualTo(8);
            assertThat(Cu02Info.NEXT_PAGE_FLG_LENGTH).isEqualTo(1);
            assertThat(Cu02Info.USR_SEL_FLG_LENGTH).isEqualTo(1);
            assertThat(Cu02Info.USR_SELECTED_LENGTH).isEqualTo(8);
        }

        @Test
        @DisplayName("initial() is the area a cold start sees: spaces, page zero and NEXT-PAGE-FLG 'N'")
        void initialIsTheInitialisedArea() {
            Cu02Info initial = Cu02Info.initial();

            assertThat(initial.usridFirst()).isEqualTo(" ".repeat(8));
            assertThat(initial.usridLast()).isEqualTo(" ".repeat(8));
            assertThat(initial.pageNum()).isZero();
            assertThat(initial.nextPageFlg()).isEqualTo(Cu02Info.NEXT_PAGE_NO);
            assertThat(initial.usrSelFlg()).isEqualTo(" ");
            assertThat(initial.usrSelected()).isEqualTo(" ".repeat(8));
        }

        @Test
        @DisplayName("an absent alphanumeric becomes spaces at its declared width, never null")
        void absentValuesBecomeSpaces() {
            Cu02Info info = new Cu02Info(null, null, 0, null, null, null);

            assertThat(info.usridFirst()).isEqualTo(" ".repeat(8));
            assertThat(info.usridLast()).isEqualTo(" ".repeat(8));
            assertThat(info.nextPageFlg()).isEqualTo(" ");
            assertThat(info.usrSelFlg()).isEqualTo(" ");
            assertThat(info.usrSelected()).isEqualTo(" ".repeat(8));
        }

        @Test
        @DisplayName("a longer value is truncated on the right, as an alphanumeric MOVE truncates")
        void longerValuesAreTruncatedOnTheRight() {
            Cu02Info info = new Cu02Info("USER00019", "USER00029", 1, "YES", "SEL", "USER00039");

            assertThat(info.usridFirst()).isEqualTo("USER0001");
            assertThat(info.usridLast()).isEqualTo("USER0002");
            assertThat(info.nextPageFlg()).isEqualTo("Y");
            assertThat(info.usrSelFlg()).isEqualTo("S");
            assertThat(info.usrSelected()).isEqualTo("USER0003");
        }

        @Test
        @DisplayName("PIC 9(08) is unsigned, so a negative page number has no representation")
        void aNegativePageNumberIsRefused() {
            assertThatThrownBy(() -> new Cu02Info(null, null, -1, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unsigned");
        }

        @Test
        @DisplayName("PIC 9(08) holds eight digits, so a ninth would be silently dropped and is refused")
        void anOverwidePageNumberIsRefused() {
            assertThatThrownBy(() -> new Cu02Info(null, null, 100_000_000, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("high-order");
            assertThat(new Cu02Info(null, null, 99_999_999, null, null, null).pageNum())
                    .isEqualTo(99_999_999);
        }

        @Test
        @DisplayName("fieldImages() names every item as the copybook spells it, page number zero-filled")
        void fieldImagesAreNamedAndZeroFilled() {
            Map<String, String> images =
                    new Cu02Info("A", "B", 7, "Y", "S", "C").fieldImages();

            assertThat(images).containsEntry("CDEMO-CU02-USRID-FIRST", padded("A", 8))
                    .containsEntry("CDEMO-CU02-USRID-LAST", padded("B", 8))
                    .containsEntry("CDEMO-CU02-PAGE-NUM", "00000007")
                    .containsEntry("CDEMO-CU02-NEXT-PAGE-FLG", "Y")
                    .containsEntry("CDEMO-CU02-USR-SEL-FLG", "S")
                    .containsEntry("CDEMO-CU02-USR-SELECTED", padded("C", 8));
            assertThatThrownBy(() -> images.put("X", "Y"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    // =================================================================================================
    // ScreenField, Send and ProgramState projections
    // =================================================================================================

    @Nested
    @DisplayName("ScreenField, Send and the ProgramState projection")
    class Projections {

        @Test
        @DisplayName("each ScreenField names its xxxL item as app/cpy-bms/COUSR02.CPY spells it")
        void screenFieldsNameTheirLengthItems() {
            assertThat(ScreenField.USRIDIN.cobolName()).isEqualTo("USRIDINL");
            assertThat(ScreenField.FNAME.cobolName()).isEqualTo("FNAMEL");
            assertThat(ScreenField.LNAME.cobolName()).isEqualTo("LNAMEL");
            assertThat(ScreenField.PASSWD.cobolName()).isEqualTo("PASSWDL");
            assertThat(ScreenField.USRTYPE.cobolName()).isEqualTo("USRTYPEL");
            assertThat(ScreenField.values()).hasSize(5);
        }

        @Test
        @DisplayName("a send carries an unmodifiable copy of what it painted plus the colour attribute")
        void aSendIsAnImmutableSnapshot() {
            Send send = new Send(Map.of("ERRMSGO", "x"), BmsAttributes.DFHRED);

            assertThat(send.value("ERRMSGO")).isEqualTo("x");
            assertThat(send.value("ABSENT")).isNull();
            assertThat(send.attributes())
                    .containsEntry(UserUpdateController.ERR_MSG_COLOUR_ITEM,
                            BmsAttributes.colourMnemonic(BmsAttributes.DFHRED));
            assertThat(send.errMsgColourMnemonic())
                    .isEqualTo(BmsAttributes.colourMnemonic(BmsAttributes.DFHRED));
            assertThatThrownBy(() -> send.fields().put("K", "V"))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> new Send(null, BmsAttributes.DFHRED))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("no cursor is requested until a paragraph asks for one")
        void theCursorIsAbsentUntilRequested() {
            ProgramState cold = controller.handle(populated(NavigationContext.empty()),
                    UserUpdateController.NO_COMMAREA_LENGTH, CicsAid.DFHENTER, noSelection());

            assertThat(cold.cursorField()).isEmpty();
            assertThat(cold.cursorFieldName()).isNull();
            assertThat(cold.cursorRequestedOn(ScreenField.USRIDIN)).isFalse();
        }

        @Test
        @DisplayName("a requested cursor is reported by name")
        void aRequestedCursorIsReportedByName() {
            ProgramState state = reentryWith(populated(reenter()), CicsAid.DFHPF4);

            assertThat(state.cursorField()).contains(ScreenField.USRIDIN);
            assertThat(state.cursorFieldName()).isEqualTo("USRIDINL");
        }

        @Test
        @DisplayName("response() projects the twelve map items, the navigation triple and the commarea")
        void theResponseIsTheWholeScreen() {
            stubFoundRead();
            ProgramState state = reentryWith(populated(reenter()), CicsAid.DFHENTER);
            UserUpdateResponse response = state.response();

            assertThat(response.trnName()).isEqualTo("CU02");
            assertThat(response.pgmName()).isEqualTo(padded("COUSR02C", 8));
            assertThat(response.curDate()).isEqualTo(EXPECTED_DATE);
            assertThat(response.curTime()).isEqualTo(EXPECTED_TIME);
            assertThat(response.usrIdIn()).isEqualTo(USER_ID);
            assertThat(response.fName()).isEqualTo(padded("Sam", 20));
            assertThat(response.lName()).isEqualTo(padded("Spade", 20));
            assertThat(response.passwd()).isEqualTo(STORED_PWD);
            assertThat(response.usrType()).isEqualTo("U");
            assertThat(response.errMsg()).hasSize(UserUpdateResponse.ERR_MSG_LENGTH);
            assertThat(response.navigationContext()).isEqualTo(state.commarea());
            assertThat(response.nextMapset()).isEqualTo(state.commarea().lastMapset());
            assertThat(response.nextMap()).isEqualTo(state.commarea().lastMap());
        }

        @Test
        @DisplayName("the record area starts blank, and the key is set before the read")
        void theRecordAreaStartsBlank() {
            when(repository.readForUpdate(anyString())).thenReturn(ReadResult.notFound());

            ProgramState state = reentryWith(populated(reenter()), CicsAid.DFHENTER);

            assertThat(state.secUserData().secUsrId()).isEqualTo(USER_ID);
            assertThat(state.secUserData().secUsrFname()).isEqualTo(" ".repeat(20));
            assertThat(state.eibAid()).isEqualTo(CicsAid.DFHENTER);
        }

        @Test
        @DisplayName("the send list and the display list are unmodifiable views")
        void theRecordedListsAreUnmodifiable() {
            ProgramState state = reentryWith(populated(reenter()), CicsAid.DFHPF7);

            assertThatThrownBy(() -> state.sends().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> state.displayLines().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("toString redacts a populated password and leaves a blank one legible")
        void toStringRedactsThePassword() {
            stubFoundRead();
            ProgramState populatedState = reentryWith(populated(reenter()), CicsAid.DFHENTER);

            assertThat(populatedState.toString())
                    .contains(NavigationContext.REDACTED)
                    .doesNotContain(STORED_PWD);

            ProgramState clearedState = reentryWith(populated(reenter()), CicsAid.DFHPF4);
            assertThat(clearedState.toString()).contains("passwd=        ");
        }

        @Test
        @DisplayName("the default colour before any paragraph sets one is DFHDFCOL")
        void theDefaultColourIsTheMapDefault() {
            ProgramState cold = controller.handle(populated(NavigationContext.empty()),
                    UserUpdateController.NO_COMMAREA_LENGTH, CicsAid.DFHENTER, noSelection());

            assertThat(cold.errMsgColour()).isEqualTo(BmsAttributes.DFHDFCOL);
        }

        @Test
        @DisplayName("a payload with no communication area is the initialised area, never null")
        void anAbsentCommareaIsTheInitialisedArea() {
            ProgramState state = controller.handle(
                    screen(USER_ID, "Sam", "Spade", STORED_PWD, "U", null),
                    UserUpdateController.NO_COMMAREA_LENGTH, CicsAid.DFHENTER, noSelection());

            assertThat(state.commarea()).isNotNull();
            assertThat(state.commarea().pgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_ENTER);
        }
    }

    // =================================================================================================
    // The HTTP adapter - the two rules that live in it
    // =================================================================================================

    @Nested
    @DisplayName("PUT /api/users/{userId} - the adapter's own two rules")
    class HttpAdapter {

        @Test
        @DisplayName("the path variable is the identity and lands in USRIDIN, truncated to eight")
        void thePathVariableIsTheIdentity() {
            stubFoundRead();

            UserUpdateResponse response = controller.updateUser("USER00019",
                    screen("IGNORED1", "Sam", "Spade", STORED_PWD, "U", reenter()),
                    null, null, null, null, null, null, null);

            verify(repository).readForUpdate("USER0001");
            assertThat(response.usrIdIn()).isEqualTo("USER0001");
        }

        @Test
        @DisplayName("an absent EIBCALEN is inferred from whether the payload carried a commarea")
        void eibcalenIsInferred() {
            // No communication area -> the cold start at :90.
            UserUpdateResponse cold = controller.updateUser(USER_ID,
                    screen(USER_ID, "Sam", "Spade", STORED_PWD, "U", null),
                    null, null, null, null, null, null, null);
            assertThat(cold.nextProgram()).isEqualTo(UserUpdateController.LIT_SIGNON_PGM);

            // A communication area -> the transaction runs.
            stubFoundRead();
            UserUpdateResponse warm = controller.updateUser(USER_ID,
                    screen(USER_ID, "Sam", "Spade", STORED_PWD, "U", reenter()),
                    null, null, null, null, null, null, null);
            assertThat(warm.fName()).isEqualTo(padded("Sam", 20));
        }

        @Test
        @DisplayName("an explicit EIBCALEN of zero forces the cold start even with a commarea present")
        void anExplicitZeroEibcalenForcesTheColdStart() {
            UserUpdateResponse response = controller.updateUser(USER_ID,
                    populated(reenter()), 0, null, null, null, null, null, null);

            assertThat(response.nextProgram()).isEqualTo(UserUpdateController.LIT_SIGNON_PGM);
            verify(repository, never()).readForUpdate(anyString());
        }

        @Test
        @DisplayName("the six optional commarea-extension parameters default the way their VALUE clauses do")
        void theExtensionParametersDefault() {
            stubFoundRead();

            UserUpdateResponse response = controller.updateUser(USER_ID,
                    populated(reenter()), UserUpdateController.PASSED_COMMAREA_LENGTH,
                    "USER0001", "USER0050", 2, Cu02Info.NEXT_PAGE_YES, "S", USER_ID);

            assertThat(response).isNotNull();
            verify(repository).readForUpdate(USER_ID);
        }

        @Test
        @DisplayName("a null path variable or a null payload is refused before anything runs")
        void nullArgumentsAreRefused() {
            assertThatThrownBy(() -> controller.updateUser(null, populated(reenter()),
                    null, null, null, null, null, null, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("user id");
            assertThatThrownBy(() -> controller.updateUser(USER_ID, null,
                    null, null, null, null, null, null, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("request");
            verify(repository, never()).readForUpdate(anyString());
        }
    }

    // =================================================================================================
    // The optional hold, for completeness of the ProgramState surface
    // =================================================================================================

    @Test
    @DisplayName("a read that reports no hold leaves the state holding nothing")
    void aReadWithoutAHoldLeavesNoHold() {
        stubFoundRead();

        ProgramState state = reentryWith(populated(reenter()), CicsAid.DFHENTER);

        assertThat(state.hold()).isEqualTo(Optional.empty());
    }
}
