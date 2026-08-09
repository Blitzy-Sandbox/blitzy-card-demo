package com.vsergeychik.carddemo.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.user.SecUserRepository.WriteResult;
import com.vsergeychik.carddemo.user.UserAddController.ProgramState;
import com.vsergeychik.carddemo.user.dto.UserAddRequest;
import com.vsergeychik.carddemo.user.dto.UserAddResponse;
import com.vsergeychik.carddemo.user.model.SecUserRecord;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behavioural parity tests for {@link UserAddController} against {@code app/cbl/COUSR01C.cbl}.
 *
 * <p>Every assertion cites the source line it pins. The controller's decisions all live in
 * {@link UserAddController#mainPara(UserAddRequest, byte, int)}, which mentions no servlet type, so
 * these tests call it as a plain Java method - no HTTP layer, no {@code MockMvc} and no Spring context
 * in the path. That is what makes every branch reachable, including the ones an HTTP test could not
 * reach at all, such as a repository reporting no CICS response code.
 */
@DisplayName("UserAddController - app/cbl/COUSR01C.cbl parity")
class UserAddControllerTest {

    /** A pinned instant so the header bytes are deterministic: 2022-07-19 23:12:34 UTC. */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2022-07-19T23:12:34Z"), ZoneOffset.UTC);

    private SecUserRepository repository;
    private UserAddController controller;

    @BeforeEach
    void setUp() {
        repository = mock(SecUserRepository.class);
        when(repository.add(any(SecUserRecord.class))).thenReturn(WriteResult.written());
        controller = new UserAddController(repository, FIXED_CLOCK, StandardCharsets.US_ASCII);
    }

    // =================================================================================================
    // Helpers.
    // =================================================================================================

    /** A communication area already in the re-enter state, which is what reaches the key dispatch. */
    private static NavigationContext reenterContext() {
        return NavigationContext.empty().withPgmReenter();
    }

    /** A payload carrying a re-entered context and the five data fields as given. */
    private static UserAddRequest request(String fName, String lName, String userId, String passwd,
            String usrType) {
        return new UserAddRequest(null, null, null, null, null, null,
                fName, lName, userId, passwd, usrType, null, reenterContext(), null);
    }

    /** A payload with every data field populated, so the blank chain falls through to the write. */
    private static UserAddRequest populatedRequest() {
        return request("John", "Doe", "USR1", "PASS1234", "U");
    }

    private ProgramState enter(UserAddRequest request) {
        return controller.mainPara(request, CicsAid.DFHENTER, NavigationContext.COMMAREA_LENGTH);
    }

    // =================================================================================================
    // MAIN-PARA entry conditions - COUSR01C:73-87.
    // =================================================================================================

    @Nested
    @DisplayName("MAIN-PARA entry - COUSR01C:71-110")
    class MainParaEntry {

        @Test
        @DisplayName("L78-80: EIBCALEN = 0 transfers to COSGN00C and never touches the dataset")
        void noCommareaTransfersToSignOn() {
            ProgramState state = controller.mainPara(null, CicsAid.DFHENTER, 0);

            assertThat(state.transferred()).isTrue();
            assertThat(state.returned()).as("XCTL does not come back, so EXEC CICS RETURN is not "
                    + "reached").isFalse();
            assertThat(state.response().nextProgram()).isEqualTo(UserAddController.SIGNON_PROGRAM);
            assertThat(state.screenSent()).isFalse();
            verify(repository, never()).add(any(SecUserRecord.class));
        }

        @Test
        @DisplayName("L78: a null navigation context also means EIBCALEN = 0 via the derived overload")
        void derivedEibcalenOfMissingContext() {
            UserAddRequest bare = new UserAddRequest(null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null);

            ProgramState state = controller.mainPara(bare);

            assertThat(state.eibcalen()).isZero();
            assertThat(state.transferred()).isTrue();
            assertThat(state.response().nextProgram()).isEqualTo(UserAddController.SIGNON_PROGRAM);
        }

        @Test
        @DisplayName("L83-87: first entry paints a blank screen, validates nothing and writes nothing")
        void firstEntryPaintsBlankScreen() {
            UserAddRequest first = new UserAddRequest(null, null, null, null, null, null,
                    null, null, null, null, null, null, NavigationContext.empty(), null);

            ProgramState state = controller.mainPara(first, CicsAid.DFHENTER,
                    NavigationContext.COMMAREA_LENGTH);

            assertThat(state.commarea().isReenter()).as("L84: SET CDEMO-PGM-REENTER TO TRUE").isTrue();
            assertThat(state.screenSent()).isTrue();
            assertThat(state.sendCount()).isEqualTo(1);
            assertThat(state.returned()).isTrue();
            assertThat(state.transferred()).isFalse();
            assertThat(state.errFlgOff()).as("no error on a first entry").isTrue();
            assertThat(state.cursorField()).contains(UserAddController.CURSOR_FNAME);
            verify(repository, never()).add(any(SecUserRecord.class));
        }

        @Test
        @DisplayName("L85: MOVE LOW-VALUES TO COUSR1AO leaves the five data fields at LOW-VALUES")
        void firstEntryLeavesDataFieldsAtLowValues() {
            UserAddRequest first = new UserAddRequest(null, null, null, null, null, null,
                    "typed", "typed", "typed", "typed", "X", null, NavigationContext.empty(), null);

            UserAddResponse response = controller.mainPara(first, CicsAid.DFHENTER,
                    NavigationContext.COMMAREA_LENGTH).response();

            assertThat(response.fName()).isEqualTo("\u0000".repeat(UserAddResponse.F_NAME_LENGTH));
            assertThat(response.lName()).isEqualTo("\u0000".repeat(UserAddResponse.L_NAME_LENGTH));
            assertThat(response.userId()).isEqualTo("\u0000".repeat(UserAddResponse.USER_ID_LENGTH));
            assertThat(response.passwd()).isEqualTo("\u0000".repeat(UserAddResponse.PASSWD_LENGTH));
            assertThat(response.usrType()).isEqualTo("\u0000".repeat(UserAddResponse.USR_TYPE_LENGTH));
            assertThat(response.errMsg()).as("L188 repopulates ERRMSGO from the blank WS-MESSAGE")
                    .isEqualTo(" ".repeat(UserAddResponse.ERR_MSG_LENGTH));
        }

        @Test
        @DisplayName("L186, L218-233: the header is repopulated even after LOW-VALUES blanked the map")
        void headerIsPopulatedOnEverySend() {
            UserAddResponse response = enter(populatedRequest()).response();

            assertThat(response.trnName()).isEqualTo("CU01");
            assertThat(response.pgmName()).isEqualTo("COUSR01C");
            assertThat(response.title01()).isEqualTo(ScreenTitles.CCDA_TITLE01);
            assertThat(response.title02()).isEqualTo(ScreenTitles.CCDA_TITLE02);
            assertThat(response.curDate()).as("mm/dd/yy from WS-CURDATE-YEAR(3:2)").isEqualTo("07/19/22");
            assertThat(response.curTime()).isEqualTo("23:12:34");
            assertThat(response.curTime()).as("CURTIME is X(8) on this screen, not X(9)").hasSize(8);
        }

        @Test
        @DisplayName("L107-110: EXEC CICS RETURN keeps the conversation on CU01 / COUSR1A / COUSR01")
        void returnKeepsTheConversationHere() {
            UserAddResponse response = enter(populatedRequest()).response();

            assertThat(response.nextProgram()).isEqualTo("COUSR01C");
            assertThat(response.nextMapset()).isEqualTo("COUSR01");
            assertThat(response.nextMap()).isEqualTo("COUSR1A");
        }

        @Test
        @DisplayName("negative EIBCALEN is refused rather than silently treated as zero")
        void negativeEibcalenIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> controller.mainPara(populatedRequest(), CicsAid.DFHENTER, -1))
                    .withMessageContaining("EIBCALEN");
        }
    }

    // =================================================================================================
    // EVALUATE EIBAID - COUSR01C:90-103.
    // =================================================================================================

    @Nested
    @DisplayName("EVALUATE EIBAID - COUSR01C:90-103")
    class KeyDispatch {

        @Test
        @DisplayName("L93-95: DFHPF3 transfers to COADM01C with no map named")
        void pf3TransfersToAdminMenu() {
            ProgramState state = controller.mainPara(populatedRequest(), CicsAid.DFHPF3,
                    NavigationContext.COMMAREA_LENGTH);

            assertThat(state.transferred()).isTrue();
            assertThat(state.returned()).isFalse();
            assertThat(state.screenSent()).isFalse();
            UserAddResponse response = state.response();
            assertThat(response.nextProgram()).isEqualTo(UserAddController.ADMIN_MENU_PROGRAM);
            assertThat(response.nextMapset()).as("XCTL passes PROGRAM and COMMAREA only").isNull();
            assertThat(response.nextMap()).isNull();
            verify(repository, never()).add(any(SecUserRecord.class));
        }

        @Test
        @DisplayName("L170-174: the transfer stamps FROM-TRANID, FROM-PROGRAM and resets the context")
        void transferStampsTheCommarea() {
            ProgramState state = controller.mainPara(populatedRequest(), CicsAid.DFHPF3,
                    NavigationContext.COMMAREA_LENGTH);

            assertThat(state.commarea().fromTranid()).isEqualTo("CU01");
            assertThat(state.commarea().fromProgram()).isEqualTo("COUSR01C");
            assertThat(state.commarea().isEnter()).as("L174: MOVE ZEROS TO CDEMO-PGM-CONTEXT").isTrue();
        }

        @Test
        @DisplayName("L172-173 are commented out, so CDEMO-USER-ID and USER-TYPE are left untouched")
        void commentedOutMovesDoNotExecute() {
            NavigationContext incoming = NavigationContext.empty()
                    .withPgmReenter()
                    .withUserId("CALLER01")
                    .withUserType(NavigationContext.USER_TYPE_ADMIN);
            UserAddRequest req = new UserAddRequest(null, null, null, null, null, null,
                    "John", "Doe", "usr1", "pass", "U", null, incoming, null);

            ProgramState state = controller.mainPara(req, CicsAid.DFHPF3,
                    NavigationContext.COMMAREA_LENGTH);

            assertThat(state.commarea().userId()).as("WS-USER-ID move at L172 is commented out")
                    .isEqualTo("CALLER01");
            assertThat(state.commarea().userType()).as("SEC-USR-TYPE move at L173 is commented out")
                    .isEqualTo(NavigationContext.USER_TYPE_ADMIN);
        }

        @Test
        @DisplayName("L167-168: a transfer with no named target falls back to COSGN00C")
        void transferFallsBackToSignOnWhenNoTargetNamed() {
            ProgramState state = controller.mainPara(populatedRequest(), CicsAid.DFHPF3,
                    NavigationContext.COMMAREA_LENGTH);

            assertThat(state.response().nextProgram()).isEqualTo(UserAddController.ADMIN_MENU_PROGRAM);

            ProgramState signOn = controller.mainPara(null, CicsAid.DFHENTER, 0);
            assertThat(signOn.response().nextProgram()).isEqualTo(UserAddController.SIGNON_PROGRAM);
        }

        @Test
        @DisplayName("L96-97: DFHPF4 clears the five fields to SPACES and blanks the message")
        void pf4ClearsTheScreen() {
            ProgramState state = controller.mainPara(populatedRequest(), CicsAid.DFHPF4,
                    NavigationContext.COMMAREA_LENGTH);

            assertThat(state.errFlgOff()).as("PF4 raises no error").isTrue();
            assertThat(state.screenSent()).isTrue();
            assertThat(state.cursorField()).contains(UserAddController.CURSOR_FNAME);
            UserAddResponse response = state.response();
            assertThat(response.fName()).isEqualTo(" ".repeat(UserAddResponse.F_NAME_LENGTH));
            assertThat(response.lName()).isEqualTo(" ".repeat(UserAddResponse.L_NAME_LENGTH));
            assertThat(response.userId()).isEqualTo(" ".repeat(UserAddResponse.USER_ID_LENGTH));
            assertThat(response.passwd()).isEqualTo(" ".repeat(UserAddResponse.PASSWD_LENGTH));
            assertThat(response.usrType()).isEqualTo(" ".repeat(UserAddResponse.USR_TYPE_LENGTH));
            assertThat(state.message()).as("L295 blanks WS-MESSAGE too")
                    .isEqualTo(" ".repeat(UserAddController.WS_MESSAGE_LENGTH));
            verify(repository, never()).add(any(SecUserRecord.class));
        }

        @ParameterizedTest(name = "EIBAID {0} reaches WHEN OTHER")
        @ValueSource(ints = {0x6D, 0x6C, 0x6E, 0xF1, 0xF2, 0xF5, 0xF8, 0x7C})
        @DisplayName("L98-102: every key the EVALUATE does not name answers with the invalid-key message")
        void unhandledKeysReachWhenOther(int aid) {
            ProgramState state = controller.mainPara(populatedRequest(), (byte) aid,
                    NavigationContext.COMMAREA_LENGTH);

            assertThat(state.errFlgOn()).isTrue();
            assertThat(state.message()).startsWith(SystemMessages.CCDA_MSG_INVALID_KEY);
            assertThat(state.cursorField()).as("L100 also moves the cursor to first-name")
                    .contains(UserAddController.CURSOR_FNAME);
            assertThat(state.screenSent()).isTrue();
            verify(repository, never()).add(any(SecUserRecord.class));
        }

        @Test
        @DisplayName("PfKeyResolver's no-match outcome joins WHEN OTHER")
        void resolverNoMatchJoinsWhenOther() {
            byte unknown = CicsAid.DFHNULL;
            assertThat(PfKeyResolver.resolve(unknown)).as("precondition: this AID resolves to nothing")
                    .isEmpty();

            ProgramState state = controller.mainPara(populatedRequest(), unknown,
                    NavigationContext.COMMAREA_LENGTH);

            assertThat(state.aidKey()).isEmpty();
            assertThat(state.errFlgOn()).isTrue();
            assertThat(state.message()).startsWith(SystemMessages.CCDA_MSG_INVALID_KEY);
            verify(repository, never()).add(any(SecUserRecord.class));
        }

        @Test
        @DisplayName("L91-92: DFHENTER reaches PROCESS-ENTER-KEY")
        void enterReachesProcessEnterKey() {
            ProgramState state = enter(populatedRequest());

            assertThat(state.aidKey()).contains(PfKeyResolver.AidKey.ENTER);
            verify(repository).add(any(SecUserRecord.class));
        }
    }

    // =================================================================================================
    // PROCESS-ENTER-KEY, the ordered blank chain - COUSR01C:117-151.
    // =================================================================================================

    @Nested
    @DisplayName("PROCESS-ENTER-KEY blank chain - COUSR01C:117-151")
    class BlankFieldChain {

        @Test
        @DisplayName("L118-123: arm one - a blank first name")
        void blankFirstName() {
            ProgramState state = enter(request("   ", "Doe", "USR1", "PASS1234", "U"));
            assertArm(state, UserAddController.MSG_FIRST_NAME_EMPTY, UserAddController.CURSOR_FNAME);
        }

        @Test
        @DisplayName("L124-129: arm two - a blank last name")
        void blankLastName() {
            ProgramState state = enter(request("John", "   ", "USR1", "PASS1234", "U"));
            assertArm(state, UserAddController.MSG_LAST_NAME_EMPTY, UserAddController.CURSOR_LNAME);
        }

        @Test
        @DisplayName("L130-135: arm three - a blank user id")
        void blankUserId() {
            ProgramState state = enter(request("John", "Doe", "   ", "PASS1234", "U"));
            assertArm(state, UserAddController.MSG_USER_ID_EMPTY, UserAddController.CURSOR_USERID);
        }

        @Test
        @DisplayName("L136-141: arm four - a blank password")
        void blankPassword() {
            ProgramState state = enter(request("John", "Doe", "USR1", "   ", "U"));
            assertArm(state, UserAddController.MSG_PASSWORD_EMPTY, UserAddController.CURSOR_PASSWD);
        }

        @Test
        @DisplayName("L142-147: arm five - a blank user type")
        void blankUserType() {
            ProgramState state = enter(request("John", "Doe", "USR1", "PASS1234", " "));
            assertArm(state, UserAddController.MSG_USER_TYPE_EMPTY, UserAddController.CURSOR_USRTYPE);
        }

        @ParameterizedTest(name = "arm {0} also matches when the field is LOW-VALUES")
        @CsvSource({
            "0, First Name can NOT be empty..., FNAMEL",
            "1, Last Name can NOT be empty..., LNAMEL",
            "2, User ID can NOT be empty..., USERIDL",
            "3, Password can NOT be empty..., PASSWDL",
            "4, User Type can NOT be empty..., USRTYPEL"
        })
        @DisplayName("each test is = SPACES OR LOW-VALUES, so a never-typed field matches too")
        void lowValuesVariantOfEachArm(int armIndex, String expectedMessage, String expectedCursor) {
            String[] values = {"John", "Doe", "USR1", "PASS1234", "U"};
            values[armIndex] = null;

            ProgramState state = enter(request(values[0], values[1], values[2], values[3], values[4]));

            assertArm(state, expectedMessage, expectedCursor);
        }

        @Test
        @DisplayName("the FIRST blank wins: with first and last name both blank, arm one answers")
        void earlierArmWinsWhenTwoAreBlank() {
            ProgramState state = enter(request("  ", "  ", "USR1", "PASS1234", "U"));

            assertThat(state.message()).startsWith(UserAddController.MSG_FIRST_NAME_EMPTY);
            assertThat(state.cursorField()).contains(UserAddController.CURSOR_FNAME);
        }

        @Test
        @DisplayName("the FIRST blank wins: with user id and password both blank, arm three answers")
        void earlierArmWinsAcrossLaterPair() {
            ProgramState state = enter(request("John", "Doe", "  ", "  ", "U"));

            assertThat(state.message()).startsWith(UserAddController.MSG_USER_ID_EMPTY);
            assertThat(state.cursorField()).contains(UserAddController.CURSOR_USERID);
        }

        @Test
        @DisplayName("every field blank still answers with arm one only")
        void allBlankAnswersWithArmOne() {
            ProgramState state = enter(request(null, null, null, null, null));

            assertThat(state.message()).startsWith(UserAddController.MSG_FIRST_NAME_EMPTY);
            assertThat(state.sendCount()).as("exactly one SEND per execution").isEqualTo(1);
        }

        @Test
        @DisplayName("L148-150: WHEN OTHER moves the cursor to first-name and then continues")
        void whenOtherMovesCursorAndContinues() {
            ProgramState state = enter(populatedRequest());

            assertThat(state.cursorField()).as("L149: MOVE -1 TO FNAMEL before CONTINUE")
                    .contains(UserAddController.CURSOR_FNAME);
            assertThat(state.errFlgOff()).as("WHEN OTHER raises no error flag").isTrue();
            verify(repository).add(any(SecUserRecord.class));
        }

        @ParameterizedTest(name = "user type {0} is accepted - there is no whitelist")
        @ValueSource(strings = {"A", "U", "Z", "1", "-", "a", "u"})
        @DisplayName("any non-blank single character is a valid user type")
        void anyNonBlankUserTypeIsAccepted(String usrType) {
            ProgramState state = enter(request("John", "Doe", "USR1", "PASS1234", usrType));

            assertThat(state.errFlgOff()).isTrue();
            assertThat(state.secUserData()).isPresent();
            assertThat(state.secUserData().orElseThrow().secUsrType()).isEqualTo(usrType);
        }

        @Test
        @DisplayName("a field of mixed spaces and nulls equals neither figurative constant")
        void mixedSpacesAndNullsIsNotBlank() {
            assertThat(UserAddController.isSpacesOrLowValues(" \u0000 ")).isFalse();
            assertThat(UserAddController.isSpacesOrLowValues("   ")).isTrue();
            assertThat(UserAddController.isSpacesOrLowValues("\u0000\u0000")).isTrue();
            assertThat(UserAddController.isSpacesOrLowValues(null)).isTrue();
            assertThat(UserAddController.isSpacesOrLowValues("")).isTrue();
            assertThat(UserAddController.isSpacesOrLowValues(" x ")).isFalse();
        }

        private void assertArm(ProgramState state, String expectedMessage, String expectedCursor) {
            assertThat(state.errFlgOn()).isTrue();
            assertThat(state.message()).isEqualTo(pad(expectedMessage));
            assertThat(state.cursorField()).contains(expectedCursor);
            assertThat(state.screenSent()).isTrue();
            assertThat(state.response().errMsg()).as("L188 narrows WS-MESSAGE onto ERRMSGO")
                    .isEqualTo(expectedMessage
                            + " ".repeat(UserAddResponse.ERR_MSG_LENGTH - expectedMessage.length()));
            assertThat(state.errMsgColour()).as("only a successful add turns the message green")
                    .isEqualTo(BmsAttributes.DFHDFCOL);
            verify(repository, never()).add(any(SecUserRecord.class));
        }
    }

    // =================================================================================================
    // WRITE-USER-SEC-FILE - COUSR01C:238-274.
    // =================================================================================================

    @Nested
    @DisplayName("WRITE-USER-SEC-FILE - COUSR01C:238-274")
    class WriteOutcomes {

        @Test
        @DisplayName("L251-259: NORMAL confirms in green with the id trimmed at its first space")
        void normalConfirmsInGreen() {
            ProgramState state = enter(request("John", "Doe", "USR1", "PASS1234", "U"));

            assertThat(state.errFlgOff()).isTrue();
            assertThat(state.wroteRecord()).isTrue();
            assertThat(state.errMsgColour()).as("L254: MOVE DFHGREEN TO ERRMSGC")
                    .isEqualTo(BmsAttributes.DFHGREEN);
            assertThat(state.message()).isEqualTo(pad("User USR1 has been added ..."));
            assertThat(state.respCd()).isEqualTo(FileStatus.NORMAL);
        }

        @Test
        @DisplayName("L256: an eight-character id has no space, so the whole operand is contributed")
        void fullWidthIdIsNotTrimmed() {
            ProgramState state = enter(request("John", "Doe", "ADMIN001", "PASS1234", "A"));

            assertThat(state.message()).isEqualTo(pad("User ADMIN001 has been added ..."));
        }

        @Test
        @DisplayName("L252-258: the screen is blanked BEFORE the message is built, yet the id survives")
        void successBlanksTheScreenButKeepsTheRecordId() {
            ProgramState state = enter(request("John", "Doe", "USR1", "PASS1234", "U"));

            UserAddResponse response = state.response();
            assertThat(response.fName()).isEqualTo(" ".repeat(UserAddResponse.F_NAME_LENGTH));
            assertThat(response.userId()).as("the SCREEN id is blanked at L290")
                    .isEqualTo(" ".repeat(UserAddResponse.USER_ID_LENGTH));
            assertThat(state.message()).as("but the RECORD id, in WORKING-STORAGE, survives")
                    .contains("USR1");
            assertThat(state.cursorField()).contains(UserAddController.CURSOR_FNAME);
        }

        @Test
        @DisplayName("L260, L266: DUPKEY answers 'User ID already exist...' with the cursor on the id")
        void duplicateKeyIsRejected() {
            when(repository.add(any(SecUserRecord.class))).thenReturn(WriteResult.duplicateKey());

            ProgramState state = enter(populatedRequest());

            assertThat(state.respCd()).isEqualTo(FileStatus.DUPKEY);
            assertDuplicateArm(state);
        }

        @Test
        @DisplayName("L261, L266: DUPREC shares the very same action")
        void duplicateRecordIsRejected() {
            when(repository.add(any(SecUserRecord.class))).thenReturn(WriteResult.duplicateRecord());

            ProgramState state = enter(populatedRequest());

            assertThat(state.respCd()).isEqualTo(FileStatus.DUPREC);
            assertDuplicateArm(state);
        }

        @Test
        @DisplayName("DUPKEY and DUPREC stay distinguishable while sharing one answer")
        void duplicateOutcomesRemainDistinguishable() {
            when(repository.add(any(SecUserRecord.class))).thenReturn(WriteResult.duplicateKey());
            int dupKeyResp = enter(populatedRequest()).respCd();

            setUp();
            when(repository.add(any(SecUserRecord.class))).thenReturn(WriteResult.duplicateRecord());
            int dupRecResp = enter(populatedRequest()).respCd();

            assertThat(dupKeyResp).isNotEqualTo(dupRecResp);
            assertThat(dupKeyResp).isEqualTo(FileStatus.DUPKEY);
            assertThat(dupRecResp).isEqualTo(FileStatus.DUPREC);
        }

        @Test
        @DisplayName("L267-273: WHEN OTHER answers 'Unable to Add User...' with the cursor on first-name")
        void otherOutcomeIsRejected() {
            when(repository.add(any(SecUserRecord.class))).thenReturn(WriteResult.notFound());

            ProgramState state = enter(populatedRequest());

            assertThat(state.errFlgOn()).isTrue();
            assertThat(state.wroteRecord()).isFalse();
            assertThat(state.message()).isEqualTo(pad(UserAddController.MSG_UNABLE_TO_ADD));
            assertThat(state.cursorField()).as("L272 targets FNAMEL, not USERIDL")
                    .contains(UserAddController.CURSOR_FNAME);
            assertThat(state.errMsgColour()).isEqualTo(BmsAttributes.DFHDFCOL);
            assertThat(state.screenSent()).isTrue();
        }

        @Test
        @DisplayName("a permanent error reporting no RESP at all also lands on WHEN OTHER")
        void unreportedRespLandsOnWhenOther() {
            when(repository.add(any(SecUserRecord.class)))
                    .thenReturn(WriteResult.of("90", com.vsergeychik.carddemo.common.CicsResponse.none()));

            ProgramState state = enter(populatedRequest());

            assertThat(state.respCd()).as("no RESP reported, so never mistaken for NORMAL").isEqualTo(-1);
            assertThat(state.message()).isEqualTo(pad(UserAddController.MSG_UNABLE_TO_ADD));
            assertThat(state.writeStatus()).contains("90");
        }

        @Test
        @DisplayName("L240-248: the record written is exactly eighty bytes with the FILLER space-filled")
        void writtenRecordIsEightyBytes() {
            enter(request("John", "Doe", "USR1", "PASS1234", "U"));

            SecUserRecord written = captureWrittenRecord();
            assertThat(written.secUsrId()).hasSize(SecUserRecord.SEC_USR_ID_LENGTH);
            assertThat(written.secUsrFname()).hasSize(SecUserRecord.SEC_USR_FNAME_LENGTH);
            assertThat(written.secUsrLname()).hasSize(SecUserRecord.SEC_USR_LNAME_LENGTH);
            assertThat(written.secUsrPwd()).hasSize(SecUserRecord.SEC_USR_PWD_LENGTH);
            assertThat(written.secUsrType()).hasSize(SecUserRecord.SEC_USR_TYPE_LENGTH);
            assertThat(written.secUsrFiller())
                    .isEqualTo(" ".repeat(SecUserRecord.SEC_USR_FILLER_LENGTH));
            assertThat(SecUserRecord.encode(written, StandardCharsets.US_ASCII))
                    .hasSize(SecUserRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("L154-158: the five values are stored in the source's own order")
        void recordFieldsComeFromTheRightScreenFields() {
            enter(request("John", "Doe", "USR1", "PASS1234", "U"));

            SecUserRecord written = captureWrittenRecord();
            assertThat(written.secUsrId()).startsWith("USR1");
            assertThat(written.secUsrFname()).startsWith("John");
            assertThat(written.secUsrLname()).startsWith("Doe");
            assertThat(written.secUsrPwd()).startsWith("PASS1234");
            assertThat(written.secUsrType()).isEqualTo("U");
        }

        @Test
        @DisplayName("B5: a lower-case id and password are stored VERBATIM, never upper-cased")
        void lowerCaseValuesAreStoredVerbatim() {
            enter(request("john", "doe", "usr1", "pass1234", "u"));

            SecUserRecord written = captureWrittenRecord();
            assertThat(written.secUsrId()).isEqualTo("usr1    ");
            assertThat(written.secUsrPwd()).isEqualTo("pass1234");
            assertThat(written.secUsrFname()).startsWith("john");
            assertThat(written.secUsrLname()).startsWith("doe");
            assertThat(written.secUsrType()).isEqualTo("u");
        }

        @Test
        @DisplayName("G41: the password is stored in the clear, exactly as COUSR01C:157 stores it")
        void passwordIsStoredInTheClear() {
            enter(request("John", "Doe", "USR1", "s3cret!!", "U"));

            assertThat(captureWrittenRecord().secUsrPwd()).isEqualTo("s3cret!!");
        }

        private void assertDuplicateArm(ProgramState state) {
            assertThat(state.errFlgOn()).isTrue();
            assertThat(state.wroteRecord()).isFalse();
            assertThat(state.message()).isEqualTo(pad(UserAddController.MSG_USER_ID_EXISTS));
            assertThat(state.cursorField()).contains(UserAddController.CURSOR_USERID);
            assertThat(state.errMsgColour()).isEqualTo(BmsAttributes.DFHDFCOL);
            assertThat(state.screenSent()).isTrue();
        }

        private SecUserRecord captureWrittenRecord() {
            org.mockito.ArgumentCaptor<SecUserRecord> captor =
                    org.mockito.ArgumentCaptor.forClass(SecUserRecord.class);
            verify(repository).add(captor.capture());
            return captor.getValue();
        }
    }

    // =================================================================================================
    // STRING ... DELIMITED BY SPACE - COUSR01C:256.
    // =================================================================================================

    @Nested
    @DisplayName("STRING ... DELIMITED BY SPACE - COUSR01C:256")
    class DelimitedBySpace {

        @ParameterizedTest(name = "\"{0}\" contributes \"{1}\"")
        @CsvSource({
            "ADMIN001, ADMIN001",
            "'USR1    ', USR1",
            "'A       ', A",
            "'AB CD   ', AB"
        })
        @DisplayName("the operand contributes up to, not including, the first space")
        void contributesUpToFirstSpace(String sendingItem, String expected) {
            assertThat(UserAddController.stringDelimitedBySpace(sendingItem)).isEqualTo(expected);
        }

        @Test
        @DisplayName("an operand beginning with a space contributes nothing")
        void leadingSpaceContributesNothing() {
            assertThat(UserAddController.stringDelimitedBySpace("   ")).isEmpty();
            assertThat(UserAddController.stringDelimitedBySpace(" X ")).isEmpty();
        }

        @Test
        @DisplayName("it is not trim(): text after an interior space is discarded, not retained")
        void isNotTrim() {
            assertThat(UserAddController.stringDelimitedBySpace("AB CD")).isEqualTo("AB");
            assertThat("AB CD".trim()).as("trim would have kept the whole value").isEqualTo("AB CD");
        }
    }

    // =================================================================================================
    // Parameter resolution.
    // =================================================================================================

    @Nested
    @DisplayName("Parameter resolution")
    class ParameterResolution {

        @Test
        @DisplayName("the explicit eibAid parameter wins")
        void explicitParameterWins() {
            UserAddRequest withToken = new UserAddRequest(null, null, null, null, null, null,
                    "John", "Doe", "USR1", "PASS1234", "U", null, reenterContext(), "PFK03");

            assertThat(controller.resolveEibAid(0x7D, withToken)).isEqualTo(CicsAid.DFHENTER);
        }

        @ParameterizedTest(name = "token {0} resolves to the matching AID")
        @ValueSource(strings = {"ENTER", "CLEAR", "PA1", "PA2", "PFK01", "PFK02", "PFK03", "PFK04",
            "PFK05", "PFK06", "PFK07", "PFK08", "PFK09", "PFK10", "PFK11", "PFK12"})
        @DisplayName("every AidKey token round-trips back to an attention identifier the resolver knows")
        void everyTokenRoundTrips(String token) {
            UserAddRequest req = new UserAddRequest(null, null, null, null, null, null,
                    "John", "Doe", "USR1", "PASS1234", "U", null, reenterContext(), token);

            byte resolved = controller.resolveEibAid(null, req);

            assertThat(PfKeyResolver.resolve(resolved)).isPresent();
            assertThat(PfKeyResolver.resolve(resolved).orElseThrow().token())
                    .isEqualTo(token + " ".repeat(PfKeyResolver.AID_TOKEN_LENGTH - token.length()));
        }

        @Test
        @DisplayName("an unrecognised token becomes an AID the resolver does not know, reaching WHEN OTHER")
        void unknownTokenBecomesNoMatch() {
            UserAddRequest req = new UserAddRequest(null, null, null, null, null, null,
                    "John", "Doe", "USR1", "PASS1234", "U", null, reenterContext(), "ZZZZZ");

            byte resolved = controller.resolveEibAid(null, req);

            assertThat(resolved).isEqualTo(CicsAid.DFHNULL);
            assertThat(PfKeyResolver.resolve(resolved)).isEmpty();
        }

        @Test
        @DisplayName("with neither parameter nor token, ENTER is the default")
        void enterIsTheDefault() {
            assertThat(controller.resolveEibAid(null, null)).isEqualTo(CicsAid.DFHENTER);
            assertThat(controller.resolveEibAid(null, populatedRequest()))
                    .isEqualTo(CicsAid.DFHENTER);
        }

        @Test
        @DisplayName("an empty token also falls through to the default")
        void emptyTokenFallsThroughToDefault() {
            UserAddRequest req = new UserAddRequest(null, null, null, null, null, null,
                    "John", "Doe", "USR1", "PASS1234", "U", null, reenterContext(), "");

            assertThat(controller.resolveEibAid(null, req)).isEqualTo(CicsAid.DFHENTER);
        }

        @ParameterizedTest(name = "eibAid {0} is refused")
        @ValueSource(ints = {-1, 256, 1000})
        @DisplayName("an eibAid outside 0-255 is refused rather than narrowed")
        void outOfRangeAidIsRefused(int aid) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> controller.resolveEibAid(aid, null))
                    .withMessageContaining(UserAddController.EIBAID_PARAM);
        }

        @Test
        @DisplayName("eibcalen resolution: explicit wins, then the payload's context decides")
        void eibcalenResolution() {
            assertThat(UserAddController.resolveEibcalen(160, null)).isEqualTo(160);
            assertThat(UserAddController.resolveEibcalen(0, populatedRequest())).isZero();
            assertThat(UserAddController.resolveEibcalen(null, null)).isZero();
            assertThat(UserAddController.resolveEibcalen(null, populatedRequest()))
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH);
        }

        @Test
        @DisplayName("a negative eibcalen parameter is refused")
        void negativeEibcalenParameterIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> UserAddController.resolveEibcalen(-5, null))
                    .withMessageContaining(UserAddController.EIBCALEN_PARAM);
        }
    }

    // =================================================================================================
    // The HTTP adapter and the field contract.
    // =================================================================================================

    @Nested
    @DisplayName("HTTP adapter and field contract")
    class Adapter {

        @Test
        @DisplayName("addUser delegates and projects, producing the same response as mainPara")
        void adapterDelegates() {
            UserAddResponse viaAdapter = controller.addUser(populatedRequest(), 0x7D,
                    NavigationContext.COMMAREA_LENGTH);

            assertThat(viaAdapter.errMsg()).contains("has been added");
            assertThat(viaAdapter.nextProgram()).isEqualTo("COUSR01C");
        }

        @Test
        @DisplayName("addUser tolerates a null body, which is how EIBCALEN = 0 is reached over HTTP")
        void adapterToleratesNullBody() {
            UserAddResponse response = controller.addUser(null, null, null);

            assertThat(response.nextProgram()).isEqualTo(UserAddController.SIGNON_PROGRAM);
        }

        @Test
        @DisplayName("G9: the serialised response carries exactly the twelve map fields plus navigation")
        void responseCarriesOnlyPayloadFields() throws Exception {
            UserAddResponse response = enter(populatedRequest()).response();

            ObjectMapper mapper = new ObjectMapper();
            List<String> members = new ArrayList<>();
            mapper.readTree(mapper.writeValueAsString(response)).fieldNames()
                    .forEachRemaining(members::add);

            assertThat(members).containsExactly("trnName", "title01", "curDate", "pgmName", "title02",
                    "curTime", "fName", "lName", "userId", "passwd", "usrType", "errMsg",
                    "navigationContext", "nextProgram", "nextMapset", "nextMap");
        }

        @Test
        @DisplayName("G9: the twelve declared widths are the symbolic map's own")
        void declaredWidthsMatchTheSymbolicMap() {
            assertThat(UserAddResponse.MAP_DERIVED_FIELD_LENGTHS)
                    .containsExactly(4, 40, 8, 8, 40, 8, 20, 20, 8, 8, 1, 78);
            assertThat(UserAddResponse.MAP_DERIVED_FIELD_COUNT).isEqualTo(12);
        }

        @Test
        @DisplayName("L188: WS-MESSAGE X(80) is narrowed onto ERRMSG X(78) by discarding the right")
        void messageIsNarrowedOnTheRight() {
            ProgramState state = enter(request("  ", "Doe", "USR1", "PASS1234", "U"));

            assertThat(state.message()).hasSize(UserAddController.WS_MESSAGE_LENGTH);
            assertThat(state.response().errMsg()).hasSize(UserAddResponse.ERR_MSG_LENGTH);
            assertThat(state.response().errMsg())
                    .isEqualTo(state.message().substring(0, UserAddResponse.ERR_MSG_LENGTH));
        }
    }

    // =================================================================================================
    // Statelessness and construction.
    // =================================================================================================

    @Nested
    @DisplayName("Statelessness and construction")
    class Statelessness {

        @Test
        @DisplayName("G53: the controller declares no static mutable field")
        void noStaticMutableState() {
            for (Field field : UserAddController.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("static field %s must be final", field.getName())
                            .isTrue();
                }
            }
            for (Field field : ProgramState.class.getDeclaredFields()) {
                assertThat(Modifier.isStatic(field.getModifiers()))
                        .as("ProgramState field %s must be per-instance", field.getName())
                        .isFalse();
            }
        }

        @Test
        @DisplayName("G37: two successive executions share no state")
        void successiveCallsShareNothing() {
            ProgramState first = enter(request("  ", "Doe", "USR1", "PASS1234", "U"));
            assertThat(first.errFlgOn()).isTrue();

            ProgramState second = enter(populatedRequest());

            assertThat(second.errFlgOn()).as("the previous error must not leak forward").isFalse();
            assertThat(second.errMsgColour()).isEqualTo(BmsAttributes.DFHGREEN);
            assertThat(second.sendCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("the constructors reject a missing collaborator rather than failing later")
        void constructorsValidateCollaborators() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new UserAddController(null, FIXED_CLOCK));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new UserAddController(repository, null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new UserAddController(repository, FIXED_CLOCK, null));
        }

        @Test
        @DisplayName("the bean constructor applies the declared code page rather than the platform's")
        void beanConstructorUsesDeclaredCharset() {
            UserAddController bean = new UserAddController(repository, FIXED_CLOCK);

            assertThat(UserAddController.DEFAULT_WORKING_STORAGE_CHARSET)
                    .isEqualTo(StandardCharsets.US_ASCII);
            assertThat(bean.addUser(populatedRequest(), 0x7D, NavigationContext.COMMAREA_LENGTH)
                    .errMsg()).contains("has been added");
        }

        @Test
        @DisplayName("the cursor set is closed and named as the copybook names it")
        void cursorFieldsAreCopybookNames() {
            assertThat(UserAddController.CURSOR_FIELDS)
                    .containsExactly("FNAMEL", "LNAMEL", "USERIDL", "PASSWDL", "USRTYPEL");
            assertThat(UserAddController.CURSOR_REQUESTED).isEqualTo(-1);
        }

        @Test
        @DisplayName("no record is built when the blank chain stopped the write")
        void noRecordBuiltOnAFailedArm() {
            ProgramState state = enter(request("  ", "Doe", "USR1", "PASS1234", "U"));

            assertThat(state.secUserData()).isEmpty();
            assertThat(state.writeStatus()).isEmpty();
            assertThat(state.dateHeader()).as("a screen was painted, so the date was captured")
                    .isPresent();
        }

        @Test
        @DisplayName("the resolved attention identifier and entry conditions are recorded")
        void entryConditionsAreRecorded() {
            ProgramState state = controller.mainPara(populatedRequest(), CicsAid.DFHPF4,
                    NavigationContext.COMMAREA_LENGTH);

            assertThat(state.eibAid()).isEqualTo(CicsAid.DFHPF4);
            assertThat(state.eibcalen()).isEqualTo(NavigationContext.COMMAREA_LENGTH);
            assertThat(state.aidKey()).contains(PfKeyResolver.AidKey.PFK04);
            assertThat(state.reasCd()).isZero();
        }
    }

    // =================================================================================================
    // Defensive paths the source itself carries. COUSR01C guards conditions that its own two call sites
    // make unreachable; those guards are preserved rather than tidied away, so they are exercised here
    // directly to prove the guard works - which is what "both sides of every condition" requires.
    // =================================================================================================

    @Nested
    @DisplayName("Defensive guards preserved from the source")
    class DefensiveGuards {

        @Test
        @DisplayName("G50: both sides of the WS-ERR-FLG 88-levels are driven")
        void bothSidesOfTheErrorFlag() {
            ProgramState failed = enter(request("  ", "Doe", "USR1", "PASS1234", "U"));
            assertThat(failed.errFlgOn()).isTrue();
            assertThat(failed.errFlgOff()).as("ERR-FLG-OFF is false once the flag is raised").isFalse();

            ProgramState succeeded = enter(populatedRequest());
            assertThat(succeeded.errFlgOn()).isFalse();
            assertThat(succeeded.errFlgOff()).isTrue();
        }

        @Test
        @DisplayName("L82: a payload carrying no communication area yields an empty one")
        void payloadWithoutContextYieldsAnEmptyCommarea() {
            UserAddRequest noContext = new UserAddRequest(null, null, null, null, null, null,
                    "John", "Doe", "USR1", "PASS1234", "U", null, null, null);

            ProgramState state = controller.mainPara(noContext, CicsAid.DFHENTER,
                    NavigationContext.COMMAREA_LENGTH);

            assertThat(state.commarea().isReenter()).as("an empty commarea is not re-entered, so this "
                    + "is a first entry").isTrue();
            assertThat(state.screenSent()).isTrue();
            verify(repository, never()).add(any(SecUserRecord.class));
        }

        @Test
        @DisplayName("L82: a non-zero EIBCALEN with no payload at all still yields an empty commarea")
        void nonZeroEibcalenWithNoPayload() {
            ProgramState state = controller.mainPara(null, CicsAid.DFHENTER,
                    NavigationContext.COMMAREA_LENGTH);

            assertThat(state.commarea().isReenter()).as("an empty commarea is a first entry").isTrue();
            assertThat(state.screenSent()).isTrue();
            assertThat(state.transferred()).isFalse();
            assertThat(state.returned()).isTrue();
            verify(repository, never()).add(any(SecUserRecord.class));
        }

        @Test
        @DisplayName("L167-168: the blank-target fallback resolves to COSGN00C")
        void blankTransferTargetFallsBackToSignOn() throws Exception {
            ProgramState state = enter(populatedRequest());
            assertThat(UserAddController.isSpacesOrLowValues(state.commarea().toProgram()))
                    .as("precondition: no transfer target was named on this path").isTrue();

            java.lang.reflect.Method returnToPrev = UserAddController.class
                    .getDeclaredMethod("returnToPrevScreen", ProgramState.class);
            returnToPrev.setAccessible(true);
            returnToPrev.invoke(controller, state);

            assertThat(state.commarea().toProgram()).isEqualTo(UserAddController.SIGNON_PROGRAM);
            assertThat(state.transferred()).isTrue();
            assertThat(state.response().nextProgram()).isEqualTo(UserAddController.SIGNON_PROGRAM);
        }

        @Test
        @DisplayName("L203-209: receiving no map at all leaves every field at LOW-VALUES")
        void receivingNoMapLeavesLowValues() throws Exception {
            ProgramState state = enter(populatedRequest());

            java.lang.reflect.Method receive = UserAddController.class
                    .getDeclaredMethod("receiveUsraddScreen", ProgramState.class, UserAddRequest.class);
            receive.setAccessible(true);
            receive.invoke(controller, state, (UserAddRequest) null);

            assertThat(state.fName()).isEqualTo("\u0000".repeat(UserAddResponse.F_NAME_LENGTH));
            assertThat(state.userId()).isEqualTo("\u0000".repeat(UserAddResponse.USER_ID_LENGTH));
            assertThat(state.errMsg()).isEqualTo("\u0000".repeat(UserAddResponse.ERR_MSG_LENGTH));
        }

        @Test
        @DisplayName("L153/L159: reaching the write with no record built is refused, not written blank")
        void writeWithoutARecordIsRefused() {
            ProgramState failed = enter(request("  ", "Doe", "USR1", "PASS1234", "U"));
            assertThat(failed.secUserData()).isEmpty();

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(failed::requireSecUserData)
                    .withMessageContaining("SEC-USER-DATA");
        }

        @Test
        @DisplayName("the record is returned once the five moves have run")
        void requireRecordReturnsItOnceBuilt() {
            ProgramState succeeded = enter(populatedRequest());

            assertThat(succeeded.requireSecUserData().secUsrId()).isEqualTo("USR1    ");
        }
    }

    /** {@code MOVE '<literal>' TO WS-MESSAGE PIC X(80)} - right-padded to eighty. */
    private static String pad(String literal) {
        return literal + " ".repeat(UserAddController.WS_MESSAGE_LENGTH - literal.length());
    }
}
