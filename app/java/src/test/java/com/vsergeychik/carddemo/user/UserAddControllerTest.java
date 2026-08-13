package com.vsergeychik.carddemo.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.ScreenMetadata;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.config.WebConfig;
import com.vsergeychik.carddemo.user.SecUserRepository.WriteResult;
import com.vsergeychik.carddemo.user.UserAddController.ProgramState;
import com.vsergeychik.carddemo.user.dto.UserAddRequest;
import com.vsergeychik.carddemo.user.dto.UserAddResponse;
import com.vsergeychik.carddemo.user.model.SecUserRecord;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
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
import org.mockito.Mockito;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Behavioural parity tests for {@link UserAddController} against {@code app/cbl/COUSR01C.cbl}, the CICS
 * program behind {@code DEFINE TRANSACTION(CU01) PROGRAM(COUSR01C)} [{@code app/csd/CARDDEMO.CSD:459-460}],
 * projected onto {@code POST /api/users}.
 */
@DisplayName("UserAddController - app/cbl/COUSR01C.cbl parity")
class UserAddControllerTest {
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2022-07-19T23:12:34Z"), ZoneOffset.UTC);

    private SecUserRepository repository;
    private UserAddController controller;

    @BeforeEach
    void setUp() {
        repository = mock(SecUserRepository.class);
        when(repository.add(any(SecUserRecord.class))).thenReturn(WriteResult.written());
        when(repository.datasetCharset()).thenReturn(StandardCharsets.US_ASCII);
        controller = new UserAddController(repository, FIXED_CLOCK);
    }

    private static NavigationContext reenterContext() {
        return NavigationContext.empty().withPgmReenter();
    }

    private static UserAddRequest request(String fName, String lName, String userId, String passwd,
            String usrType) {
        return new UserAddRequest(null, null, null, null, null, null,
                fName, lName, userId, passwd, usrType, null, reenterContext(), null);
    }

    private static UserAddRequest populatedRequest() {
        return request("John", "Doe", "USR1", "PASS1234", "U");
    }

    private ProgramState enter(UserAddRequest request) {
        return controller.mainPara(request, CicsAid.DFHENTER, NavigationContext.COMMAREA_LENGTH);
    }

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
            assertThat(response.nextMapset())
                    .as("no map named, at NEXT_MAPSET_LENGTH spaces rather than null")
                    .isEqualTo(" ".repeat(UserAddResponse.NEXT_MAPSET_LENGTH));
            assertThat(response.nextMap())
                    .isEqualTo(" ".repeat(UserAddResponse.NEXT_MAP_LENGTH));
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

    @Nested
    @DisplayName("No case normalisation - COUSR01C:153-158")
    class NoCaseNormalisation {
        private final FixedWidthCodec codec = new FixedWidthCodec(StandardCharsets.US_ASCII);

        @Test
        @DisplayName("B5: the brief's own lower-case case - newuser1/alice/smith/secret99/u stored as typed")
        void lowerCaseIsStoredExactlyAsTyped() {
            enter(request("alice", "smith", "newuser1", "secret99", "u"));

            SecUserRecord written = writtenRecord();
            assertThat(written.secUsrId()).isEqualTo("newuser1");
            assertThat(written.secUsrFname()).isEqualTo(codec.movePicX("alice",
                    SecUserRecord.SEC_USR_FNAME_LENGTH));
            assertThat(written.secUsrLname()).isEqualTo(codec.movePicX("smith",
                    SecUserRecord.SEC_USR_LNAME_LENGTH));
            assertThat(written.secUsrPwd()).isEqualTo("secret99");
            assertThat(written.secUsrType()).isEqualTo("u");

            assertThat(written.secUsrId()).isNotEqualTo("NEWUSER1");
            assertThat(written.secUsrFname()).isNotEqualTo(codec.movePicX("ALICE",
                    SecUserRecord.SEC_USR_FNAME_LENGTH));
            assertThat(written.secUsrLname()).isNotEqualTo(codec.movePicX("SMITH",
                    SecUserRecord.SEC_USR_LNAME_LENGTH));
            assertThat(written.secUsrPwd()).isNotEqualTo("SECRET99");
            assertThat(written.secUsrType()).isNotEqualTo("U");
        }

        @Test
        @DisplayName("B5: mixed case survives too - NewUser1 is stored as NewUser1, not NEWUSER1")
        void mixedCaseIsPreserved() {
            enter(request("Alice", "McSmith", "NewUser1", "Secret99", "A"));

            SecUserRecord written = writtenRecord();
            assertThat(written.secUsrId()).isEqualTo("NewUser1");
            assertThat(written.secUsrFname()).startsWith("Alice");
            assertThat(written.secUsrLname()).as("an interior capital is not a word boundary to a MOVE")
                    .startsWith("McSmith");
            assertThat(written.secUsrPwd()).isEqualTo("Secret99");
        }

        @Test
        @DisplayName("the id the confirmation message quotes is the id as typed, so the two agree")
        void theConfirmationQuotesTheStoredCase() {
            ProgramState state = enter(request("alice", "smith", "newuser1", "secret99", "u"));

            assertThat(state.message()).isEqualTo(pad("User newuser1 has been added ..."));
            assertThat(state.message()).doesNotContain("NEWUSER1");
        }

        @ParameterizedTest(name = "\"{0}\" is stored as \"{0}\"")
        @ValueSource(strings = {"a", "A", "z", "Z", "u", "U", "aB", "Ab", "mIxEdCa"})
        @DisplayName("no character class is folded in either direction")
        void noFoldingInEitherDirection(String userId) {
            enter(request("First", "Last", userId, "PASS1234", "U"));

            assertThat(writtenRecord().secUsrId())
                    .isEqualTo(codec.movePicX(userId, SecUserRecord.SEC_USR_ID_LENGTH));
        }

        @Test
        @DisplayName("PIC X pads on the RIGHT: alice fills SEC-USR-FNAME X(20) with fifteen trailing spaces")
        void picXPadsOnTheRight() {
            enter(request("alice", "smith", "newuser1", "secret99", "u"));

            SecUserRecord written = writtenRecord();
            assertThat(written.secUsrFname()).isEqualTo("alice" + " ".repeat(15));
            assertThat(written.secUsrFname()).hasSize(20);
            assertThat(written.secUsrFname()).as("left justified, so the value is at the front")
                    .startsWith("alice");
            assertThat(written.secUsrFname()).isEqualTo(codec.movePicX("alice", 20));
        }

        @Test
        @DisplayName("PIC X truncates on the RIGHT: a 25-character name keeps its first 20 characters")
        void picXTruncatesOnTheRight() {
            String twentyFive = "ABCDEFGHIJKLMNOPQRSTUVWXY";
            assertThat(twentyFive).hasSize(25);

            enter(request(twentyFive, twentyFive, "newuser1", "secret99", "u"));

            SecUserRecord written = writtenRecord();
            assertThat(written.secUsrFname()).isEqualTo("ABCDEFGHIJKLMNOPQRST");
            assertThat(written.secUsrFname()).isEqualTo(codec.movePicX(twentyFive, 20));
            assertThat(written.secUsrFname()).as("the LEADING characters survive, never the trailing ones")
                    .doesNotContain("Y")
                    .startsWith("A");
            assertThat(written.secUsrLname()).isEqualTo(codec.movePicX(twentyFive, 20));
        }

        @Test
        @DisplayName("movePicX is the rule for all five receivers, at each declared width")
        void everyReceiverUsesItsDeclaredWidth() {
            enter(request("alice", "smith", "newuser1", "secret99", "u"));

            SecUserRecord written = writtenRecord();
            assertThat(written.secUsrId()).isEqualTo(codec.movePicX("newuser1", 8));
            assertThat(written.secUsrFname()).isEqualTo(codec.movePicX("alice", 20));
            assertThat(written.secUsrLname()).isEqualTo(codec.movePicX("smith", 20));
            assertThat(written.secUsrPwd()).isEqualTo(codec.movePicX("secret99", 8));
            assertThat(written.secUsrType()).isEqualTo(codec.movePicX("u", 1));
            assertThat(new int[] {SecUserRecord.SEC_USR_ID_LENGTH, SecUserRecord.SEC_USR_FNAME_LENGTH,
                SecUserRecord.SEC_USR_LNAME_LENGTH, SecUserRecord.SEC_USR_PWD_LENGTH,
                SecUserRecord.SEC_USR_TYPE_LENGTH})
                    .containsExactly(8, 20, 20, 8, 1);
        }

        @Test
        @DisplayName("G41, B6: the password is stored in the clear, byte for byte, with no hashing")
        void passwordIsStoredByteForByte() {
            String submitted = "s3cr#t9";

            enter(request("alice", "smith", "newuser1", submitted, "u"));

            String stored = writtenRecord().secUsrPwd();
            assertThat(stored).isEqualTo(codec.movePicX(submitted, 8));
            assertThat(stored.strip()).isEqualTo(submitted);
            assertThat(stored).hasSize(8);
            assertThat(stored).doesNotContain("$");
            assertThat(Integer.toHexString(submitted.hashCode())).isNotEqualTo(stored.strip());
        }

        @Test
        @DisplayName("a password that exactly fills PIC X(08) is stored with no padding at all")
        void anExactlyFittingPasswordIsUnpadded() {
            enter(request("alice", "smith", "newuser1", "PASSWORD", "u"));

            assertThat(writtenRecord().secUsrPwd()).isEqualTo("PASSWORD");
            assertThat(writtenRecord().secUsrPwd()).doesNotContain(" ");
        }

        private SecUserRecord writtenRecord() {
            org.mockito.ArgumentCaptor<SecUserRecord> captor =
                    org.mockito.ArgumentCaptor.forClass(SecUserRecord.class);
            verify(repository).add(captor.capture());
            return captor.getValue();
        }
    }

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

    @Nested
    @DisplayName("Parameter resolution")
    class ParameterResolution {
        @Test
        @DisplayName("the explicit eibAid parameter wins over the payload's own image")
        void explicitParameterWins() {
            UserAddRequest carryingPf3 = new UserAddRequest(null, null, null, null, null, null,
                    "John", "Doe", "USR1", "PASS1234", "U", null, reenterContext(),
                    PfKeyResolver.aidImage(CicsAid.DFHPF3));

            assertThat(controller.resolveEibAid(0x7D, carryingPf3)).isEqualTo(CicsAid.DFHENTER);
            assertThat(controller.resolveEibAid(null, carryingPf3)).isEqualTo(CicsAid.DFHPF3);
        }

        @ParameterizedTest(name = "the byte {0} in the payload is read back as itself")
        @ValueSource(ints = {0x40, 0x6C, 0x6D, 0x6E, 0x7D, 0xC1, 0xC3, 0xF1, 0xF3, 0xF4, 0x7C, 0xFF})
        @DisplayName("the payload's one character IS the EIBAID byte, and nothing is folded")
        void everyTokenRoundTrips(int unsigned) {
            byte stated = (byte) unsigned;
            UserAddRequest req = new UserAddRequest(null, null, null, null, null, null,
                    "John", "Doe", "USR1", "PASS1234", "U", null, reenterContext(),
                    PfKeyResolver.aidImage(stated));

            assertThat(controller.resolveEibAid(null, req))
                    .as("PF15 stays PF15 and does not reach the WHEN DFHPF3 arm that transfers")
                    .isEqualTo(stated);
        }

        @Test
        @DisplayName("a CCARD-AID token is not one byte, so it names no key: DFHNULL, and WHEN OTHER")
        void unknownTokenBecomesNoMatch() {
            for (String token : new String[] {"ZZZZZ", "PFK03", "ENTER", "PA1  ", "PA1"}) {
                UserAddRequest req = new UserAddRequest(null, null, null, null, null, null,
                        "John", "Doe", "USR1", "PASS1234", "U", null, reenterContext(), token);

                byte resolved = controller.resolveEibAid(null, req);

                assertThat(resolved).as("'%s' is not one byte", token).isEqualTo(CicsAid.DFHNULL);
                assertThat(PfKeyResolver.resolve(resolved)).isEmpty();
            }
        }

        @Test
        @DisplayName("a blank or LOW-VALUES image states no key, which keeps the ENTER default")
        void aBlankImageStatesNoKey() {
            for (String image : new String[] {"", " ", "\u0000", "     "}) {
                UserAddRequest req = new UserAddRequest(null, null, null, null, null, null,
                        "John", "Doe", "USR1", "PASS1234", "U", null, reenterContext(), image);

                assertThat(controller.resolveEibAid(null, req)).isEqualTo(CicsAid.DFHENTER);
            }
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
        @DisplayName("eibcalen resolution: the carrier decides, and a statement must agree with it")
        void eibcalenResolution() {
            assertThat(UserAddController.resolveEibcalen(null, null)).isZero();
            assertThat(UserAddController.resolveEibcalen(null, populatedRequest()))
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH);
            assertThat(UserAddController.resolveEibcalen(0, null)).isZero();
            assertThat(UserAddController.resolveEibcalen(NavigationContext.COMMAREA_LENGTH,
                    populatedRequest())).isEqualTo(NavigationContext.COMMAREA_LENGTH);
        }

        @Test
        @DisplayName("a stated eibcalen that contradicts the carrier is refused, in either direction")
        void aContradictingEibcalenIsRefusedDirectly() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> UserAddController.resolveEibcalen(
                            NavigationContext.COMMAREA_LENGTH, null))
                    .withMessageContaining("no communication area");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> UserAddController.resolveEibcalen(0, populatedRequest()))
                    .withMessageContaining("a communication area");
        }

        @ParameterizedTest(name = "eibcalen = {0} is preserved when a commarea did arrive")
        @ValueSource(ints = {1, 159, 160, 161, 194, 2000})
        @DisplayName("every non-zero length is carried through unchanged: line 78 tests EIBCALEN "
                + "against zero and against nothing else")
        void anImpossibleEibcalenIsRefusedDirectly(int stated) {
            assertThat(UserAddController.resolveEibcalen(stated, populatedRequest())).isEqualTo(stated);
        }

        @Test
        @DisplayName("a negative eibcalen parameter is refused")
        void negativeEibcalenParameterIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> UserAddController.resolveEibcalen(-5, null))
                    .withMessageContaining(UserAddController.EIBCALEN_PARAM);
        }
    }

    @Nested
    @DisplayName("HTTP adapter and field contract")
    class Adapter {
        @Test
        @DisplayName("addUser delegates and projects, producing the same response as mainPara")
        void adapterDelegates() {
            var answer = controller.addUser(populatedRequest(), 0x7D,
                    NavigationContext.COMMAREA_LENGTH, null);

            UserAddResponse viaAdapter = answer.screen();
            assertThat(viaAdapter.errMsg()).contains("has been added");
            assertThat(viaAdapter.nextProgram()).isEqualTo("COUSR01C");
            assertThat(answer.screenMetadata()).isNotNull();
            assertThat(answer.screenMetadata().fields())
                    .as("COUSR01 declares no attribute quads this program writes")
                    .isEmpty();
            assertThat(answer.screenMetadata().messageColour()).isNotNull();
        }

        @Test
        @DisplayName("addUser tolerates a null body, which is how EIBCALEN = 0 is reached over HTTP")
        void adapterToleratesNullBody() {
            UserAddResponse response = controller.addUser(null, null, null, null).screen();

            assertThat(response.nextProgram()).isEqualTo(UserAddController.SIGNON_PROGRAM);
        }

        @Test
        @DisplayName("either accepted spelling of the AID parameter reaches the key, and a contradiction "
                + "between them is refused")
        void eitherAidSpellingReachesTheKey() {
            int pf4 = Byte.toUnsignedInt(CicsAid.DFHPF4);

            UserAddResponse throughAlternate = controller
                    .addUser(populatedRequest(), pf4, NavigationContext.COMMAREA_LENGTH, null).screen();
            UserAddResponse throughCanonical = controller
                    .addUser(populatedRequest(), null, NavigationContext.COMMAREA_LENGTH, pf4).screen();
            UserAddResponse throughBoth = controller
                    .addUser(populatedRequest(), pf4, NavigationContext.COMMAREA_LENGTH, pf4).screen();
            UserAddResponse throughEnter = controller
                    .addUser(populatedRequest(), null, NavigationContext.COMMAREA_LENGTH, null).screen();

            assertThat(throughCanonical).isEqualTo(throughAlternate);
            assertThat(throughBoth).isEqualTo(throughAlternate);
            assertThat(throughEnter).isNotEqualTo(throughAlternate);

            assertThatThrownBy(() -> controller.addUser(populatedRequest(), pf4,
                    NavigationContext.COMMAREA_LENGTH, Byte.toUnsignedInt(CicsAid.DFHPF3)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(UserAddController.EIBAID_PARAM)
                    .hasMessageContaining(UserAddController.EIBAID_PARAM_ALIAS);
            assertThatThrownBy(() -> controller.addUser(populatedRequest(), null,
                    NavigationContext.COMMAREA_LENGTH, 300))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(UserAddController.EIBAID_PARAM);
        }

        @Test
        @DisplayName("an EIBCALEN that contradicts the carrier is refused, in either direction")
        void aContradictingEibcalenIsRefused() {
            assertThatThrownBy(() -> controller.addUser(populatedRequest(), 0x7D, 0, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("a communication area");
            assertThatThrownBy(() -> controller.addUser(null, null,
                    NavigationContext.COMMAREA_LENGTH, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no communication area");
        }

        @ParameterizedTest(name = "eibcalen = {0} reaches the non-zero arm over HTTP")
        @ValueSource(ints = {1, 159, 160, 161, 194, 2000})
        @DisplayName("Over the route, every non-zero length is accepted and takes line 78's non-zero "
                + "arm, so a client echoing a real handoff is never answered 400")
        void anImpossibleEibcalenIsRefused(int stated) {
            assertThatCode(() -> controller.addUser(populatedRequest(), 0x7D, stated, null))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("A negative EIBCALEN is refused over the route: it is not a length at all")
        void aNegativeEibcalenIsRefusedOverTheRoute() {
            assertThatThrownBy(() -> controller.addUser(populatedRequest(), 0x7D, -1, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(UserAddController.EIBCALEN_PARAM)
                    .hasMessageContaining("cannot be negative");
        }

        @Test
        @DisplayName("G9: the serialised response carries eleven of the twelve map fields plus navigation")
        void responseCarriesOnlyPayloadFields() throws Exception {
            UserAddResponse response = enter(populatedRequest()).response();

            ObjectMapper mapper = new ObjectMapper();
            String body = mapper.writeValueAsString(response);
            List<String> members = new ArrayList<>();
            mapper.readTree(body).fieldNames().forEachRemaining(members::add);

            assertThat(members).containsExactly("trnname", "title01", "curdate", "pgmname", "title02",
                    "curtime", "fname", "lname", "userid", "usrtype", "errmsg",
                    "navigationContext", "nextProgram", "nextMapset", "nextMap");
            assertThat(members).doesNotContain("passwd", "password");
        }

        @Test
        @DisplayName("no path publishes the keyed credential, and the DRK fact is published instead")
        void noPathPublishesTheKeyedCredential() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            // Two endings: the successful add, where INITIALIZE-ALL-FIELDS at :289-295 has already
            // blanked the field, and an error repaint, where the REDEFINES overlay still holds what was
            // keyed and the SEND at :184-196 really does re-transmit it.
            ProgramState added = enter(populatedRequest());
            ProgramState refused = enter(request("  ", "Doe", "USR1", "PASS1234", "U"));

            assertThat(refused.response().passwd())
                    .as("in-process the overlay keeps the keyed value, as the parity corpus pins")
                    .startsWith("PASS1234")
                    .hasSize(UserAddResponse.PASSWD_LENGTH);
            for (ProgramState state : List.of(added, refused)) {
                assertThat(mapper.writeValueAsString(state.response()))
                        .as("and no payload carries it, under any name")
                        .doesNotContain("PASS1234")
                        .doesNotContain("passwd");
            }
            assertThat(refused.screenMetadata().nonDisplayFields())
                    .as("a 3270 reads DRK off the mapset; a REST client has no mapset, so it is told")
                    .containsExactly(ScreenMetadata.PASSWORD_FIELD_LABEL);
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

            SecUserRepository pageless = mock(SecUserRepository.class);
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new UserAddController(pageless, FIXED_CLOCK));
        }

        @Test
        @DisplayName("the code page is the dataset's own, not a constant and not the platform's")
        void theCodePageComesFromTheRepository() {
            Charset ebcdic = Charset.forName("IBM037");
            SecUserRepository ebcdicRepository = mock(SecUserRepository.class);
            when(ebcdicRepository.datasetCharset()).thenReturn(ebcdic);
            when(ebcdicRepository.add(any(SecUserRecord.class))).thenReturn(WriteResult.written());

            UserAddController onEbcdic = new UserAddController(ebcdicRepository, FIXED_CLOCK);

            assertThat(onEbcdic.addUser(populatedRequest(), 0x7D, NavigationContext.COMMAREA_LENGTH,
                    null).screen().errMsg()).contains("has been added");
            assertThat(controller.addUser(populatedRequest(), 0x7D, NavigationContext.COMMAREA_LENGTH,
                    null).screen().errMsg()).contains("has been added");
            Mockito.verify(ebcdicRepository).datasetCharset();
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

    @Nested
    @DisplayName("Seed identities - app/jcl/DUSRSECJ.jcl:34-44")
    class SeedIdentities {
        private static final String UNSEEDED_ID = "NEWUSER1";

        private static final String SEEDED_ADMIN_ID = "ADMIN001";

        private static final String SEED_PASSWORD = "PASSWORD";

        @Test
        @DisplayName("L251-259: an unseeded id is added and confirmed by name")
        void anUnseededIdIsAdded() {
            ProgramState state = enter(request("Newton", "Newman", UNSEEDED_ID, SEED_PASSWORD, "U"));

            assertThat(state.wroteRecord()).isTrue();
            assertThat(state.errFlgOff()).isTrue();
            assertThat(state.message()).isEqualTo(pad("User NEWUSER1 has been added ..."));
            assertThat(state.errMsgColour()).as("L254: MOVE DFHGREEN TO ERRMSGC")
                    .isEqualTo(BmsAttributes.DFHGREEN);
            assertThat(SEED_PASSWORD).as("the seed literal fills SEC-USR-PWD PIC X(08) exactly")
                    .hasSize(SecUserRecord.SEC_USR_PWD_LENGTH);
        }

        @Test
        @DisplayName("L260-266: a seeded id earns the duplicate answer, whichever RESP reports it")
        void aSeededIdIsRefusedAsADuplicate() {
            when(repository.add(any(SecUserRecord.class))).thenReturn(WriteResult.duplicateRecord());

            ProgramState state = enter(request("Margaret", "Gold", SEEDED_ADMIN_ID, SEED_PASSWORD, "A"));

            assertThat(state.wroteRecord()).isFalse();
            assertThat(state.errFlgOn()).isTrue();
            assertThat(state.message()).isEqualTo(pad(UserAddController.MSG_USER_ID_EXISTS));
            assertThat(state.cursorField()).contains(UserAddController.CURSOR_USERID);
        }

        @Test
        @DisplayName("L256: a two-character id yields 'User AB has been added ...' with no interior padding")
        void aShortIdCarriesNoInteriorPadding() {
            ProgramState state = enter(request("Ann", "Bell", "AB", SEED_PASSWORD, "U"));

            assertThat(state.message()).isEqualTo(pad("User AB has been added ..."));
            assertThat(state.message()).doesNotContain("AB  ");
            assertThat(state.message().strip()).isEqualTo("User AB has been added ...");
            assertThat(writtenRecord().secUsrId()).as("while the RECORD keeps its full declared width")
                    .isEqualTo("AB      ");
        }

        @ParameterizedTest(name = "id \"{0}\" is quoted as \"{1}\"")
        @CsvSource({
            "A, User A has been added ...",
            "AB, User AB has been added ...",
            "USR1, User USR1 has been added ...",
            "NEWUSER1, User NEWUSER1 has been added ...",
            "ADMIN005, User ADMIN005 has been added ..."
        })
        @DisplayName("every id width from one to eight is quoted with single spaces around it")
        void everyIdWidthIsQuotedWithSingleSpaces(String userId, String expectedMessage) {
            ProgramState state = enter(request("First", "Last", userId, SEED_PASSWORD, "U"));

            assertThat(state.message()).isEqualTo(pad(expectedMessage));
        }

        @Test
        @DisplayName("L252, L287-295: a successful add blanks every screen field INCLUDING the password")
        void successBlanksEveryScreenFieldIncludingThePassword() {
            ProgramState state = enter(request("Newton", "Newman", UNSEEDED_ID, SEED_PASSWORD, "U"));

            UserAddResponse response = state.response();
            assertThat(response.passwd()).isEqualTo(" ".repeat(UserAddResponse.PASSWD_LENGTH));
            assertThat(response.userId()).isEqualTo(" ".repeat(UserAddResponse.USER_ID_LENGTH));
            assertThat(response.fName()).isEqualTo(" ".repeat(UserAddResponse.F_NAME_LENGTH));
            assertThat(response.lName()).isEqualTo(" ".repeat(UserAddResponse.L_NAME_LENGTH));
            assertThat(response.usrType()).isEqualTo(" ".repeat(UserAddResponse.USR_TYPE_LENGTH));
            assertThat(response.passwd()).doesNotContain(SEED_PASSWORD);
            assertThat(response.errMsg()).doesNotContain(SEED_PASSWORD);
            assertThat(state.cursorField()).contains(UserAddController.CURSOR_FNAME);
        }

        @Test
        @DisplayName("L96-97, L287-295: PF4 blanks the same five fields, password included")
        void clearBlanksTheSameFiveFields() {
            ProgramState state = controller.mainPara(
                    request("Newton", "Newman", UNSEEDED_ID, SEED_PASSWORD, "U"),
                    CicsAid.DFHPF4, NavigationContext.COMMAREA_LENGTH);

            UserAddResponse response = state.response();
            assertThat(response.passwd()).isEqualTo(" ".repeat(UserAddResponse.PASSWD_LENGTH));
            assertThat(response.userId()).isEqualTo(" ".repeat(UserAddResponse.USER_ID_LENGTH));
            assertThat(response.fName()).isEqualTo(" ".repeat(UserAddResponse.F_NAME_LENGTH));
            assertThat(response.lName()).isEqualTo(" ".repeat(UserAddResponse.L_NAME_LENGTH));
            assertThat(response.usrType()).isEqualTo(" ".repeat(UserAddResponse.USR_TYPE_LENGTH));
            assertThat(state.message()).as("L295 blanks WS-MESSAGE too, so PF4 says nothing")
                    .isEqualTo(" ".repeat(UserAddController.WS_MESSAGE_LENGTH));
            verify(repository, never()).add(any(SecUserRecord.class));
        }

        private SecUserRecord writtenRecord() {
            org.mockito.ArgumentCaptor<SecUserRecord> captor =
                    org.mockito.ArgumentCaptor.forClass(SecUserRecord.class);
            verify(repository).add(captor.capture());
            return captor.getValue();
        }
    }

    @Nested
    @DisplayName("Preserved absences - COUSR01C:57, :172-173, and the un-extended commarea")
    class PreservedAbsences {
        @Test
        @DisplayName("B4: DFHATTR is commented out at :57, so no DFHATTR attribute is in play")
        void dfhattrContributesNothing() {
            assertThat(CicsAid.DFHENTER).as("DFHAID is copied, so DFHENTER is reachable").isNotZero();
            assertThat(CicsAid.DFHPF3).isNotZero();
            assertThat(CicsAid.DFHPF4).isNotZero();
            assertThat(BmsAttributes.DFHGREEN).as("DFHBMSCA is copied, so DFHGREEN is reachable")
                    .isNotZero();
            assertThat(BmsAttributes.DFHRED).isNotZero();

            ProgramState added = enter(populatedRequest());
            assertThat(added.errMsgColour()).isEqualTo(BmsAttributes.DFHGREEN);
            setUp();
            ProgramState refused = enter(request("  ", "Doe", "USR1", "PASS1234", "U"));
            assertThat(refused.errMsgColour()).isEqualTo(BmsAttributes.DFHDFCOL);
            assertThat(BmsAttributes.DFHGREEN).isNotEqualTo(BmsAttributes.DFHDFCOL);
        }

        @Test
        @DisplayName("B5: :172-173 are commented out, so a transfer leaves the identity fields alone")
        void theCommentedMovesStayAbsent() {
            NavigationContext signedOn = NavigationContext.empty()
                    .withUserId("ADMIN001")
                    .withUserTypeAdmin()
                    .withPgmReenter();
            UserAddRequest leaving = new UserAddRequest(null, null, null, null, null, null,
                    "Newton", "Newman", "NEWUSER1", "PASSWORD", "U", null, signedOn, null);

            ProgramState state = controller.mainPara(leaving, CicsAid.DFHPF3,
                    NavigationContext.COMMAREA_LENGTH);

            assertThat(state.transferred()).isTrue();
            assertThat(state.commarea().userId()).as(":172 is commented, so the operator's id survives")
                    .isEqualTo("ADMIN001");
            assertThat(state.commarea().userType()).as(":173 is commented, so the operator's type survives")
                    .isEqualTo("A");
            assertThat(state.commarea().isAdmin()).isTrue();
            assertThat(state.commarea().userId()).isNotEqualTo("NEWUSER1");
            assertThat(state.commarea().userType()).isNotEqualTo("U");
            assertThat(state.commarea().fromTranid()).isEqualTo("CU01");
            assertThat(state.commarea().fromProgram()).isEqualTo("COUSR01C");
            assertThat(state.commarea().pgmContext()).isZero();
            assertThat(state.commarea().toProgram()).isEqualTo(UserAddController.ADMIN_MENU_PROGRAM);
        }

        @Test
        @DisplayName("B5: the identity fields are equally untouched by a plain add, which never transfers")
        void anAddDoesNotTouchTheIdentityEither() {
            NavigationContext signedOn = NavigationContext.empty()
                    .withUserId("USER0001")
                    .withUserTypeUser()
                    .withPgmReenter();
            UserAddRequest adding = new UserAddRequest(null, null, null, null, null, null,
                    "Newton", "Newman", "NEWUSER1", "PASSWORD", "A", null, signedOn, null);

            ProgramState state = controller.mainPara(adding, CicsAid.DFHENTER,
                    NavigationContext.COMMAREA_LENGTH);

            assertThat(state.wroteRecord()).isTrue();
            assertThat(state.commarea().userId()).isEqualTo("USER0001");
            assertThat(state.commarea().userType()).as("adding an administrator does not promote the "
                    + "operator who added them").isEqualTo("U");
            assertThat(state.commarea().isUser()).isTrue();
        }

        @Test
        @DisplayName("B5: the commarea is the plain 160-byte COCOM01Y, with no inline extension")
        void theCommareaCarriesNoInlineExtension() {
            ProgramState state = enter(populatedRequest());

            assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(160);
            assertThat(NavigationContext.GENERAL_INFO_LENGTH + NavigationContext.CUSTOMER_INFO_LENGTH
                    + NavigationContext.ACCOUNT_INFO_LENGTH + NavigationContext.CARD_INFO_LENGTH
                    + NavigationContext.MORE_INFO_LENGTH)
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH);
            assertThat(state.eibcalen()).isEqualTo(NavigationContext.COMMAREA_LENGTH);
            assertThat(UserAddController.resolveEibcalen(
                    NavigationContext.COMMAREA_LENGTH + NavigationContext.GENERAL_INFO_LENGTH,
                    populatedRequest()))
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH
                            + NavigationContext.GENERAL_INFO_LENGTH);
        }

        @Test
        @DisplayName("B5: neither the request nor the response declares a paging or selection member")
        void noPagingOrSelectionMemberIsDeclared() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            UserAddResponse response = enter(populatedRequest()).response();

            String requestJson = mapper.writeValueAsString(populatedRequest());
            String responseJson = mapper.writeValueAsString(response);

            for (String absent : List.of("cu01Info", "cu00Info", "pageNum", "nextPageFlg", "usrSelFlg",
                    "usrSelected", "usridFirst", "usridLast")) {
                assertThat(requestJson).as("the request must not carry %s", absent)
                        .doesNotContain(absent);
                assertThat(responseJson).as("the response must not carry %s", absent)
                        .doesNotContain(absent);
            }
            verify(repository, never()).read(any());
        }

        @Test
        @DisplayName("B5: the only dataset call this program makes is the WRITE at :240")
        void theOnlyDatasetCallIsTheWrite() {
            enter(populatedRequest());

            verify(repository).add(any(SecUserRecord.class));
            verify(repository).datasetCharset();
            org.mockito.Mockito.verifyNoMoreInteractions(repository);
        }
    }

    @Nested
    @DisplayName("Error highlighting - CSSETATY, reenter-gated")
    class ErrorHighlighting {
        @Test
        @DisplayName("G38: on ENTER nothing is highlighted, even with a blank field on the screen")
        void firstEntryNeverHighlights() {
            FieldAttributeSetter.FieldHighlight highlight = FieldAttributeSetter.resolve(
                    FieldAttributeSetter.FieldValidationState.of(false, true), false,
                    "FNAME", UserAddResponse.MAP_NAME);

            assertThat(highlight.untouched()).isTrue();
            assertThat(highlight.colourItemAssigned()).isFalse();
            assertThat(highlight.outputItemAssigned()).isFalse();
        }

        @Test
        @DisplayName("G38: on REENTER a blank field takes DFHRED on its colour item and '*' on its output")
        void reenterHighlightsABlankField() {
            FieldAttributeSetter.FieldHighlight highlight = FieldAttributeSetter.resolve(
                    FieldAttributeSetter.FieldValidationState.of(false, true), true,
                    "FNAME", UserAddResponse.MAP_NAME);

            assertThat(highlight.colourItemAssigned()).isTrue();
            assertThat(highlight.colourItemValue()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(highlight.outputItemAssigned()).isTrue();
            assertThat(highlight.outputItemValue()).isEqualTo(FieldAttributeSetter.ASTERISK);
            assertThat(highlight.colourItemName()).isEqualTo("FNAMEC");
            assertThat(highlight.outputItemName()).isEqualTo("FNAMEO");
            assertThat(highlight.untouched()).isFalse();
        }

        @Test
        @DisplayName("on REENTER a not-ok but non-blank field takes the colour only, never the asterisk")
        void reenterHighlightsANotOkFieldWithoutTheAsterisk() {
            FieldAttributeSetter.FieldHighlight highlight = FieldAttributeSetter.resolve(
                    FieldAttributeSetter.FieldValidationState.of(true, false), true,
                    "USERID", UserAddResponse.MAP_NAME);

            assertThat(highlight.colourItemAssigned()).as("the outer action is unconditional").isTrue();
            assertThat(highlight.outputItemAssigned()).as("the inner test is FLG-BLANK only").isFalse();
            assertThat(highlight.colourItemName()).isEqualTo("USERIDC");
        }

        @ParameterizedTest(name = "notOk={0} blank={1} reenter={2} -> colour={3} asterisk={4}")
        @CsvSource({
            "false, false, false, false, false",
            "false, false, true,  false, false",
            "false, true,  false, false, false",
            "false, true,  true,  true,  true",
            "true,  false, false, false, false",
            "true,  false, true,  true,  false",
            "true,  true,  false, false, false",
            "true,  true,  true,  true,  true"
        })
        @DisplayName("G50: the complete truth table of the copybook's two nested tests")
        void theCompleteTruthTable(boolean notOk, boolean blank, boolean reenter, boolean colour,
                boolean asterisk) {
            FieldAttributeSetter.FieldHighlight highlight =
                    FieldAttributeSetter.resolveFromFlags(notOk, blank, reenter, "FNAME",
                            UserAddResponse.MAP_NAME);

            assertThat(highlight.colourItemAssigned()).isEqualTo(colour);
            assertThat(highlight.outputItemAssigned()).isEqualTo(asterisk);
        }

        @ParameterizedTest(name = "{0} is highlighted on reenter when it is the blank one")
        @ValueSource(strings = {"FNAME", "LNAME", "USERID", "PASSWD", "USRTYPE"})
        @DisplayName("each of the five data fields names its own colour and output items")
        void everyDataFieldNamesItsOwnItems(String prefix) {
            FieldAttributeSetter.FieldHighlight highlight = FieldAttributeSetter.resolve(
                    FieldAttributeSetter.FieldValidationState.of(false, true), true, prefix,
                    UserAddResponse.MAP_NAME);

            assertThat(highlight.colourItemName()).isEqualTo(prefix + "C");
            assertThat(highlight.outputItemName()).isEqualTo(prefix + "O");
            assertThat(highlight.colourItemValue()).isEqualTo(BmsAttributes.DFHRED);
        }

        @Test
        @DisplayName("the program's own first entry is the ENTER state, so it paints without highlighting")
        void theProgramsFirstEntryIsNotHighlighted() {
            UserAddRequest first = new UserAddRequest(null, null, null, null, null, null,
                    null, null, null, null, null, null, NavigationContext.empty(), null);
            assertThat(NavigationContext.empty().isEnter()).isTrue();

            ProgramState state = controller.mainPara(first, CicsAid.DFHENTER,
                    NavigationContext.COMMAREA_LENGTH);

            assertThat(state.errFlgOff()).isTrue();
            assertThat(state.errMsgColour()).isEqualTo(BmsAttributes.DFHDFCOL);
            assertThat(state.screenMetadata().fields())
                    .as("no field carries a highlight on a first entry").isEmpty();
        }
    }

    @Nested
    @DisplayName("The HTTP contract - POST /api/users")
    class HttpContract {
        private static final List<String> PAYLOAD_FIELDS = List.of(
            "trnname", "title01", "curdate", "pgmname", "title02", "curtime", "fname", "lname",
            "userid", "passwd", "usrtype", "errmsg");

        private final ObjectMapper mapper = new ObjectMapper();

        private MockMvc http() {
            return MockMvcBuilders.standaloneSetup(controller)
                    .setControllerAdvice(new WebConfig.CobolErrorHandler())
                    .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                    .build();
        }

        private String body(UserAddRequest payload) throws Exception {
            return mapper.writeValueAsString(payload);
        }

        private ResultMatcher errMsgStartsWith(String expectedPrefix) {
            return result -> assertThat(mapper.readTree(result.getResponse().getContentAsString())
                            .path("errmsg").asText())
                    .startsWith(expectedPrefix);
        }

        @Test
        @DisplayName("the mapping is POST /api/users and it answers 200 JSON with the screen unwrapped")
        void theMappingRoutesAndAnswers() throws Exception {
            http().perform(post(UserAddController.USERS_PATH)
                            .param(UserAddController.EIBCALEN_PARAM,
                                    String.valueOf(NavigationContext.COMMAREA_LENGTH))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(populatedRequest())))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(errMsgStartsWith("User USR1 has been added ..."))
                    .andExpect(jsonPath("$.trnname").value("CU01"))
                    .andExpect(jsonPath("$.pgmname").value("COUSR01C"))
                    .andExpect(jsonPath("$.nextProgram").value("COUSR01C"))
                    .andExpect(jsonPath("$.nextMapset").value("COUSR01"))
                    .andExpect(jsonPath("$.nextMap").value("COUSR1A"));

            verify(repository).add(any(SecUserRecord.class));
        }

        @Test
        @DisplayName("the path is exactly /api/users, the projection CU01 was assigned")
        void thePathIsTheOneTheTransactionWasMappedTo() {
            assertThat(UserAddController.USERS_PATH).isEqualTo("/api/users");
            assertThat(UserAddController.WS_TRANID).isEqualTo("CU01");
            assertThat(UserAddController.WS_PGMNAME).isEqualTo("COUSR01C");
        }

        @ParameterizedTest(name = "{0} /api/users earns 405")
        @ValueSource(strings = {"GET", "PUT", "DELETE", "PATCH", "HEAD"})
        @DisplayName("only POST is mapped on this path, so any other verb earns 405")
        void anyOtherVerbEarns405(String verb) throws Exception {
            http().perform(MockMvcRequestBuilders.request(
                            HttpMethod.valueOf(verb), UserAddController.USERS_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(populatedRequest())))
                    .andExpect(status().isMethodNotAllowed());

            verify(repository, never()).add(any(SecUserRecord.class));
        }

        @Test
        @DisplayName("G9: the request body carries the twelve xxxI members and no xxxL, xxxF or xxxA")
        void theRequestCarriesOnlyTheTwelvePayloadFields() throws Exception {
            List<String> members = new ArrayList<>();
            mapper.readTree(body(populatedRequest())).fieldNames().forEachRemaining(members::add);

            assertThat(members).containsExactly("trnname", "title01", "curdate", "pgmname", "title02",
                    "curtime", "fname", "lname", "userid", "passwd", "usrtype", "errmsg",
                    "navigationContext", "aid");

            String json = body(populatedRequest());
            for (String prefix : PAYLOAD_FIELDS) {
                String screenName = prefix.substring(0, 1).toUpperCase(java.util.Locale.ROOT)
                        + prefix.substring(1);
                assertThat(json).as("%sL must not be a member", prefix)
                        .doesNotContain("\"" + prefix + "L\"").doesNotContain("\"" + screenName + "L\"");
                assertThat(json).as("%sF must not be a member", prefix)
                        .doesNotContain("\"" + prefix + "F\"").doesNotContain("\"" + screenName + "F\"");
                assertThat(json).as("%sA must not be a member", prefix)
                        .doesNotContain("\"" + prefix + "A\"").doesNotContain("\"" + screenName + "A\"");
            }
        }

        @Test
        @DisplayName("G9: the id member is userId, from USERIDI - never usrIdIn, which is COUSR02/03's")
        void theIdMemberIsTheOneThisMapDeclares() throws Exception {
            String json = body(populatedRequest());

            assertThat(json).contains("\"userid\"");
            assertThat(json).as("USRIDIN belongs to COUSR02 and COUSR03, not to COUSR01")
                    .doesNotContain("usrIdIn").doesNotContain("usridIn").doesNotContain("USRIDIN");
            assertThat(UserAddRequest.USERID_FIELD).isEqualTo("USERIDI");
            assertThat(UserAddRequest.USERID_LENGTH).isEqualTo(8);
        }

        @Test
        @DisplayName("G9: CURTIME is X(8) on this map, not COSGN00's X(9)")
        void curTimeIsEightOnThisMap() {
            assertThat(UserAddRequest.CURTIME_LENGTH).isEqualTo(8);
            assertThat(UserAddResponse.MAP_DERIVED_FIELD_LENGTHS.get(5)).isEqualTo(8);
        }

        @Test
        @DisplayName("a blank field is answered by the program with 200 and a message, NOT refused as 400")
        void aBlankFieldIsAnsweredNotRefused() throws Exception {
            http().perform(post(UserAddController.USERS_PATH)
                            .param(UserAddController.EIBCALEN_PARAM,
                                    String.valueOf(NavigationContext.COMMAREA_LENGTH))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(request(null, "Doe", "USR1", "PASS1234", "U"))))
                    .andExpect(status().isOk())
                    .andExpect(errMsgStartsWith(UserAddController.MSG_FIRST_NAME_EMPTY));

            http().perform(post(UserAddController.USERS_PATH)
                            .param(UserAddController.EIBCALEN_PARAM,
                                    String.valueOf(NavigationContext.COMMAREA_LENGTH))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(request("   ", "   ", "   ", "   ", " "))))
                    .andExpect(status().isOk())
                    .andExpect(errMsgStartsWith(UserAddController.MSG_FIRST_NAME_EMPTY));

            verify(repository, never()).add(any(SecUserRecord.class));
        }

        @ParameterizedTest(name = "a blank {0} still answers 200")
        @CsvSource({
            "first name, First Name can NOT be empty...",
            "last name, Last Name can NOT be empty...",
            "user id, User ID can NOT be empty...",
            "password, Password can NOT be empty...",
            "user type, User Type can NOT be empty..."
        })
        @DisplayName("each of the five arms travels over HTTP as 200 with its own text")
        void eachArmTravelsAsTwoHundred(String which, String expected) throws Exception {
            String[] values = {"John", "Doe", "USR1", "PASS1234", "U"};
            int blankIndex =
                    List.of("first name", "last name", "user id", "password", "user type").indexOf(which);
            values[blankIndex] = blankIndex == 4 ? " " : "  ";

            http().perform(post(UserAddController.USERS_PATH)
                            .param(UserAddController.EIBCALEN_PARAM,
                                    String.valueOf(NavigationContext.COMMAREA_LENGTH))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(request(values[0], values[1], values[2], values[3],
                                    values[4]))))
                    .andExpect(status().isOk())
                    .andExpect(errMsgStartsWith(expected));
        }

        @Test
        @DisplayName("@Size is the one constraint that does apply: an over-width field earns 400")
        void anOverWidthFieldIsRefused() throws Exception {
            String twentyOne = "A".repeat(UserAddRequest.FNAME_LENGTH + 1);

            http().perform(post(UserAddController.USERS_PATH)
                            .param(UserAddController.EIBCALEN_PARAM,
                                    String.valueOf(NavigationContext.COMMAREA_LENGTH))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(request(twentyOne, "Doe", "USR1", "PASS1234", "U"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("fname"));

            verify(repository, never()).add(any(SecUserRecord.class));
        }

        @ParameterizedTest(name = "an over-width {0} earns 400")
        @CsvSource({"fname, 21", "lname, 21", "userid, 9", "passwd, 9", "usrtype, 2"})
        @DisplayName("every data field's @Size maximum is the width its xxxI item declares")
        void everyDataFieldEnforcesItsDeclaredWidth(String member, int overWidth) throws Exception {
            String tooWide = "X".repeat(overWidth);
            UserAddRequest payload = switch (member) {
                case "fname" -> request(tooWide, "Doe", "USR1", "PASS1234", "U");
                case "lname" -> request("John", tooWide, "USR1", "PASS1234", "U");
                case "userid" -> request("John", "Doe", tooWide, "PASS1234", "U");
                case "passwd" -> request("John", "Doe", "USR1", tooWide, "U");
                default -> request("John", "Doe", "USR1", "PASS1234", tooWide);
            };

            http().perform(post(UserAddController.USERS_PATH)
                            .param(UserAddController.EIBCALEN_PARAM,
                                    String.valueOf(NavigationContext.COMMAREA_LENGTH))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(payload)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field").value(member));

            verify(repository, never()).add(any(SecUserRecord.class));
        }

        @Test
        @DisplayName("a value at exactly the declared width is accepted, so the boundary is inclusive")
        void aValueAtTheDeclaredWidthIsAccepted() throws Exception {
            http().perform(post(UserAddController.USERS_PATH)
                            .param(UserAddController.EIBCALEN_PARAM,
                                    String.valueOf(NavigationContext.COMMAREA_LENGTH))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(request("A".repeat(UserAddRequest.FNAME_LENGTH),
                                    "B".repeat(UserAddRequest.LNAME_LENGTH), "NEWUSER1", "PASSWORD",
                                    "U"))))
                    .andExpect(status().isOk())
                    .andExpect(errMsgStartsWith("User NEWUSER1 has been added ..."));
        }

        @Test
        @DisplayName("G37: no session, no cookie, no redirect and no forward - the state is in the payload")
        void nothingIsPinnedToTheClient() throws Exception {
            MvcResult result = http().perform(post(UserAddController.USERS_PATH)
                            .param(UserAddController.EIBCALEN_PARAM,
                                    String.valueOf(NavigationContext.COMMAREA_LENGTH))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(populatedRequest())))
                    .andExpect(status().isOk())
                    .andReturn();

            assertThat(result.getRequest().getSession(false))
                    .as("no HttpSession was created, so nothing is held between calls").isNull();
            assertThat(result.getResponse().getCookies())
                    .as("no cookie, so nothing is pinned to a client").isEmpty();
            assertThat(result.getResponse().getRedirectedUrl()).as("no sendRedirect").isNull();
            assertThat(result.getResponse().getForwardedUrl()).as("no server-side forward").isNull();
            assertThat(result.getResponse().getContentAsString()).contains("navigationContext");
        }

        @Test
        @DisplayName("G40: a PF3 transfer names its target in the body, never in a Location header")
        void aTransferIsAResponseFieldNotARedirect() throws Exception {
            MvcResult result = http().perform(post(UserAddController.USERS_PATH)
                            .param(UserAddController.EIBAID_PARAM,
                                    String.valueOf(Byte.toUnsignedInt(CicsAid.DFHPF3)))
                            .param(UserAddController.EIBCALEN_PARAM,
                                    String.valueOf(NavigationContext.COMMAREA_LENGTH))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(populatedRequest())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.nextProgram")
                            .value(UserAddController.ADMIN_MENU_PROGRAM))
                    .andReturn();

            assertThat(result.getResponse().getRedirectedUrl()).isNull();
            assertThat(result.getResponse().getForwardedUrl()).isNull();
            assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION))
                    .as("XCTL is not a redirect: the client reads nextProgram and calls it").isNull();
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            verify(repository, never()).add(any(SecUserRecord.class));
        }

        @Test
        @DisplayName("G37: two independent requests over one dispatcher do not interfere")
        void independentRequestsDoNotInterfere() throws Exception {
            MockMvc dispatcher = http();

            MvcResult refused = dispatcher.perform(post(UserAddController.USERS_PATH)
                            .param(UserAddController.EIBCALEN_PARAM,
                                    String.valueOf(NavigationContext.COMMAREA_LENGTH))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(request("  ", "Doe", "USR1", "PASS1234", "U"))))
                    .andExpect(status().isOk())
                    .andReturn();
            assertThat(refused.getResponse().getContentAsString())
                    .contains(UserAddController.MSG_FIRST_NAME_EMPTY);

            MvcResult accepted = dispatcher.perform(post(UserAddController.USERS_PATH)
                            .param(UserAddController.EIBCALEN_PARAM,
                                    String.valueOf(NavigationContext.COMMAREA_LENGTH))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(request("Newton", "Newman", "NEWUSER1", "PASSWORD", "U"))))
                    .andExpect(status().isOk())
                    .andReturn();

            assertThat(accepted.getResponse().getContentAsString())
                    .as("the previous request's error must not leak into this one")
                    .doesNotContain(UserAddController.MSG_FIRST_NAME_EMPTY)
                    .contains("User NEWUSER1 has been added ...");
            assertThat(accepted.getRequest().getSession(false)).isNull();
        }

        @Test
        @DisplayName("an unhandled key over HTTP lands on WHEN OTHER with the invalid-key message")
        void anUnhandledKeyLandsOnWhenOther() throws Exception {
            http().perform(post(UserAddController.USERS_PATH)
                            .param(UserAddController.EIBAID_PARAM,
                                    String.valueOf(Byte.toUnsignedInt(CicsAid.DFHPF9)))
                            .param(UserAddController.EIBCALEN_PARAM,
                                    String.valueOf(NavigationContext.COMMAREA_LENGTH))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(populatedRequest())))
                    .andExpect(status().isOk())
                    .andExpect(errMsgStartsWith(SystemMessages.CCDA_MSG_INVALID_KEY.strip()));

            verify(repository, never()).add(any(SecUserRecord.class));
        }

        @Test
        @DisplayName("no body at all is EIBCALEN = 0, which transfers to the sign-on screen")
        void noBodyIsTheColdStart() throws Exception {
            http().perform(post(UserAddController.USERS_PATH))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.nextProgram").value(UserAddController.SIGNON_PROGRAM));

            verify(repository, never()).add(any(SecUserRecord.class));
        }

        @Test
        @DisplayName("a malformed body is refused by the advice, not answered as a screen")
        void aMalformedBodyIsRefused() throws Exception {
            http().perform(post(UserAddController.USERS_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"fname\": "))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code")
                            .value(WebConfig.CobolErrorHandler.MALFORMED_REQUEST_CODE));

            verify(repository, never()).add(any(SecUserRecord.class));
        }

        @Test
        @DisplayName("the response is JSON only - no HTML, no template, no view name")
        void theResponseIsJsonOnly() throws Exception {
            MvcResult result = http().perform(post(UserAddController.USERS_PATH)
                            .param(UserAddController.EIBCALEN_PARAM,
                                    String.valueOf(NavigationContext.COMMAREA_LENGTH))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(populatedRequest())))
                    .andExpect(status().isOk())
                    .andReturn();

            assertThat(result.getModelAndView()).as("a REST controller resolves no view").isNull();
            assertThat(result.getResponse().getContentAsString()).startsWith("{").endsWith("}");
        }
    }

    private static String pad(String literal) {
        return literal + " ".repeat(UserAddController.WS_MESSAGE_LENGTH - literal.length());
    }
}
