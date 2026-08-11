package com.vsergeychik.carddemo.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
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
 * Behavioural parity tests for {@link UserAddController} against {@code app/cbl/COUSR01C.cbl}, the
 * CICS program behind {@code DEFINE TRANSACTION(CU01) PROGRAM(COUSR01C)}
 * [{@code app/csd/CARDDEMO.CSD:459-460}], projected onto {@code POST /api/users}.
 *
 * <p>Every assertion cites the source line it pins. The paragraphs under test are
 * {@code MAIN-PARA} [{@code :71-110}], {@code PROCESS-ENTER-KEY}'s ordered blank chain
 * [{@code :117-151}] and its five plain {@code MOVE}s [{@code :153-158}],
 * {@code RETURN-TO-PREV-SCREEN} [{@code :165-178}], {@code WRITE-USER-SEC-FILE} [{@code :238-274}]
 * and {@code INITIALIZE-ALL-FIELDS} [{@code :286-294}].
 *
 * <h2>Two levels, deliberately</h2>
 * The controller's decisions all live in {@link UserAddController#mainPara(UserAddRequest, byte, int)},
 * which mentions no servlet type, so most tests here call it as a plain Java method - no HTTP layer and
 * no Spring context in the path. That is what makes every branch reachable, including the ones an HTTP
 * test could not reach at all, such as a repository reporting no CICS response code.
 *
 * <p>A second, smaller group raises a real dispatcher through
 * {@link org.springframework.test.web.servlet.setup.MockMvcBuilders#standaloneSetup}, because a handful
 * of rules belong to the adapter rather than to the program and exist nowhere else: that the mapping is
 * registered on {@code POST} and on that path, the {@code 405} a wrong verb earns, which members the
 * wire format actually carries, whether a blank field is answered by the program or refused by the
 * binder, and that nothing is pinned to a session, a cookie, a redirect or a forward. Standalone rather
 * than {@code @WebMvcTest}: exactly one handler is registered, so a wrong-verb request has nowhere else
 * to land and the status is the dispatcher's own answer rather than another controller's mapping.
 *
 * <h2>Copybooks in play, including the one that is not</h2>
 * {@code COUSR01C:55-56} copies {@code DFHAID} and {@code DFHBMSCA}, so {@link CicsAid} and
 * {@link BmsAttributes} constants are legitimately asserted here. {@code COUSR01C:57} reads
 * {@code *COPY DFHATTR.} - <strong>commented out</strong> - so {@code DFHATTR} contributes nothing at
 * compile time and nothing here asserts a {@code DFHATTR}-specific attribute. The migration plan's
 * "DFHATTR: 2 consumers" is a textual count that includes this commented line.
 *
 * <h2>Where the expected values come from</h2>
 * They are <strong>statically derived</strong> by reading {@code COUSR01C.cbl} paragraph by paragraph
 * and cross-checking against {@code app/cpy-bms/COUSR01.CPY} (field names and widths),
 * {@code app/bms/COUSR01.bms} (the {@code DFHMDF} definitions), {@code app/cpy/CSUSR01Y.cpy} (the
 * eighty-byte record) and {@code app/cpy/COCOM01Y.cpy} (the hundred-and-sixty-byte communication area).
 * They are <em>not</em> captured from a live COBOL run: this environment cannot execute the program, for
 * the reasons the migration plan records, so no execution baseline exists to capture. Nothing here was
 * written by observing the Java and calling the observation the expectation.
 *
 * <h2>Seed identities</h2>
 * {@code USRSEC} has no fixture under {@code app/data/ASCII}; it is seeded in-stream by
 * {@code app/jcl/DUSRSECJ.jcl}, whose {@code //SYSUT1 DD *} at line 34 carries ten records of
 * fifty-seven characters on lines 35-44 - {@code ADMIN001}-{@code ADMIN005} as type {@code A} and
 * {@code USER0001}-{@code USER0005} as type {@code U}, every one of them with the password literal
 * {@code PASSWORD}, and every one of them three bytes short of {@code SEC-USER-DATA}'s eighty because
 * the stream omits {@code SEC-USR-FILLER X(23)}. A duplicate is therefore modelled on {@code ADMIN001},
 * an addition on an identity the seed does not hold such as {@code NEWUSER1}.
 *
 * @see SignOnServiceTest for the other half of the case asymmetry: {@code COSGN00C:132-137} upper-cases
 *     its input unconditionally, whereas {@code COUSR01C:153-158} does not normalise at all
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
    // No case normalisation - COUSR01C:153-158.
    //
    // The five statements are plain MOVEs. There is no FUNCTION UPPER-CASE anywhere in the program -
    // grep across app/cbl/COUSR01C.cbl returns zero occurrences - so whatever the operator typed is what
    // reaches SEC-USER-DATA, in the case they typed it in.
    //
    // This is one half of a genuine asymmetry, and the half this class owns. COSGN00C:132-137 upper-cases
    // its user id and password UNCONDITIONALLY before it looks either up, which SignOnServiceTest pins.
    // Put the two halves together and a user added here as 'newuser1' cannot sign on as 'newuser1': the
    // sign-on program upper-cases the key to 'NEWUSER1', reads USRSEC for that, and does not find the
    // record this program stored under the lower-case key. That is the legacy system's behaviour.
    // Asserting it is this migration's job; correcting it would be a new business rule and is not.
    // =================================================================================================

    @Nested
    @DisplayName("No case normalisation - COUSR01C:153-158")
    class NoCaseNormalisation {

        /**
         * The codec the five moves are performed through, at the code page the controller declares.
         *
         * <p>Constructed here rather than reached through the controller because the point of the
         * assertions below is that {@code movePicX} - the explicit alphanumeric {@code MOVE} - is what
         * produces the stored image, so the expectation has to be computed by the same rule the
         * implementation is claimed to follow rather than by a hand-written literal that could agree
         * with the implementation and disagree with COBOL.
         */
        private final FixedWidthCodec codec = new FixedWidthCodec(StandardCharsets.US_ASCII);

        @Test
        @DisplayName("B5: the brief's own lower-case case - newuser1/alice/smith/secret99/u stored as typed")
        void lowerCaseIsStoredExactlyAsTyped() {
            enter(request("alice", "smith", "newuser1", "secret99", "u"));

            SecUserRecord written = writtenRecord();
            // Not toUpperCase() anywhere: the id is the eight characters typed, in the case typed.
            assertThat(written.secUsrId()).isEqualTo("newuser1");
            assertThat(written.secUsrFname()).isEqualTo(codec.movePicX("alice",
                    SecUserRecord.SEC_USR_FNAME_LENGTH));
            assertThat(written.secUsrLname()).isEqualTo(codec.movePicX("smith",
                    SecUserRecord.SEC_USR_LNAME_LENGTH));
            assertThat(written.secUsrPwd()).isEqualTo("secret99");
            assertThat(written.secUsrType()).isEqualTo("u");

            // Stated the other way round, so a future upper-casing "fix" fails here rather than passing
            // silently: none of the five stored values equals its own upper-cased form.
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

            // L255-258 builds the message from SEC-USR-ID, the record field, so the operator is told
            // exactly which key was written - lower case and all.
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
            // The same image the codec's explicit helper produces, which is the rule being claimed.
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
            // The direction is the whole point. A left-truncating receiver would have kept the tail, and
            // the defect would be invisible at the call site - which is why the move goes through the
            // codec's named helper and never through a bare Java assignment.
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
            // And the widths themselves are CSUSR01Y's, not this test's opinion.
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
            // COUSR01C:157 is MOVE PASSWDI TO SEC-USR-PWD. Nothing is applied to it: no digest, no salt,
            // no encoder. SEC-USR-PWD is PIC X(08), so the only transformation is the PIC X pad.
            assertThat(stored).isEqualTo(codec.movePicX(submitted, 8));
            assertThat(stored.strip()).isEqualTo(submitted);
            assertThat(stored).hasSize(8);
            // A hash of any kind would neither round-trip nor fit, which is what these two pin.
            assertThat(stored).doesNotContain("$");
            assertThat(Integer.toHexString(submitted.hashCode())).isNotEqualTo(stored.strip());
        }

        @Test
        @DisplayName("a password that exactly fills PIC X(08) is stored with no padding at all")
        void anExactlyFittingPasswordIsUnpadded() {
            // The literal every DUSRSECJ seed record carries, and it fills the field precisely.
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
        @DisplayName("eibcalen resolution: the carrier decides, and a statement must agree with it")
        void eibcalenResolution() {
            // Absent: EIBCALEN is derived from what actually arrived, which is what CICS would have set.
            assertThat(UserAddController.resolveEibcalen(null, null)).isZero();
            assertThat(UserAddController.resolveEibcalen(null, populatedRequest()))
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH);
            // Stated and in agreement: taken as given.
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

        @ParameterizedTest(name = "eibcalen = {0} is not a length CICS could have set")
        @ValueSource(ints = {-5, 1, 159, 161, 194, 2000})
        @DisplayName("only 0 and the copybook length are accepted: COUSR01C declares no extension")
        void anImpossibleEibcalenIsRefusedDirectly(int stated) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> UserAddController.resolveEibcalen(stated, populatedRequest()))
                    .withMessageContaining(UserAddController.EIBCALEN_PARAM);
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
            var answer = controller.addUser(populatedRequest(), 0x7D,
                    NavigationContext.COMMAREA_LENGTH, null);

            UserAddResponse viaAdapter = answer.screen();
            assertThat(viaAdapter.errMsg()).contains("has been added");
            assertThat(viaAdapter.nextProgram()).isEqualTo("COUSR01C");
            // The presentation metadata travels beside the screen rather than not travelling at all.
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

            // This route declared only the alternate spelling while GET /api/users - the same path,
            // a different verb - declared only the canonical one, so a client that used one name for both
            // calls had its key discarded by Spring on one of them and the request executed as ENTER.
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
            // PF4 clears the screen (COUSR01C:96-98) while an absent key is ENTER, which adds the user -
            // so the two answers genuinely differ and the equality above is meaningful.
            assertThat(throughEnter).isNotEqualTo(throughAlternate);

            assertThatThrownBy(() -> controller.addUser(populatedRequest(), pf4,
                    NavigationContext.COMMAREA_LENGTH, Byte.toUnsignedInt(CicsAid.DFHPF3)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(UserAddController.EIBAID_PARAM)
                    .hasMessageContaining(UserAddController.EIBAID_PARAM_ALIAS);
            // The range guard applies to whichever spelling carried the value.
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

        @ParameterizedTest(name = "eibcalen = {0} is refused")
        @ValueSource(ints = {-1, 1, 159, 161, 194, 2000})
        @DisplayName("EIBCALEN can only be one of the two lengths CICS could have set")
        void anImpossibleEibcalenIsRefused(int stated) {
            assertThatThrownBy(() -> controller.addUser(populatedRequest(), 0x7D, stated, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(UserAddController.EIBCALEN_PARAM);
        }

        @Test
        @DisplayName("G9: the serialised response carries exactly the twelve map fields plus navigation")
        void responseCarriesOnlyPayloadFields() throws Exception {
            UserAddResponse response = enter(populatedRequest()).response();

            ObjectMapper mapper = new ObjectMapper();
            List<String> members = new ArrayList<>();
            mapper.readTree(mapper.writeValueAsString(response)).fieldNames()
                    .forEachRemaining(members::add);

            // Screen fields under their xxxI item in lower case (AAP 0.6.3); the four carriers, which
            // trace to no DFHMDF field, under their own names.
            assertThat(members).containsExactly("trnname", "title01", "curdate", "pgmname", "title02",
                    "curtime", "fname", "lname", "userid", "passwd", "usrtype", "errmsg",
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
            assertThat(bean.addUser(populatedRequest(), 0x7D, NavigationContext.COMMAREA_LENGTH, null)
                    .screen().errMsg()).contains("has been added");
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

    // =================================================================================================
    // The seed identities of app/jcl/DUSRSECJ.jcl, and the two answers they distinguish.
    //
    // USRSEC has no fixture under app/data/ASCII. It is seeded in-stream: //SYSUT1 DD * at DUSRSECJ.jcl:34
    // with ten records on lines 35-44, each fifty-seven characters - eight for the id, twenty for the
    // first name, twenty for the last, eight for the password and one for the type - which is
    // SEC-USER-DATA less its trailing SEC-USR-FILLER X(23), so a loader right-pads fifty-seven to eighty.
    // Five ids are administrators and five are regular users, and all ten carry the password literal
    // PASSWORD. An id the seed already holds is what makes a duplicate reachable; one it does not hold is
    // what makes an addition reachable.
    // =================================================================================================

    @Nested
    @DisplayName("Seed identities - app/jcl/DUSRSECJ.jcl:34-44")
    class SeedIdentities {

        /** An id the in-stream seed does not hold, so adding it is the NORMAL path. */
        private static final String UNSEEDED_ID = "NEWUSER1";

        /** The first id the seed holds, so adding it again is the duplicate path. */
        private static final String SEEDED_ADMIN_ID = "ADMIN001";

        /** The password literal every one of the ten seed records carries, filling PIC X(08) exactly. */
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

            // The record field is 'AB      ' - PIC X(08), six trailing spaces - but DELIMITED BY SPACE
            // stops the sending operand at the first space, so the message carries 'AB' and the two words
            // either side of it are separated by exactly one space each. A naive concatenation of the
            // whole eight-byte field would have produced 'User AB        has been added ...'.
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
            // INITIALIZE-ALL-FIELDS moves SPACES into USERIDI, FNAMEI, LNAMEI, PASSWDI and USRTYPEI.
            // The password is in that list, which is why UserAddResponse declares a passwd member at all:
            // it carries a blank, never a value read back out of USRSEC. COUSR01C only ever reads PASSWDI
            // and stores it at :157 - it never reads a password from the dataset onto the screen.
            assertThat(response.passwd()).isEqualTo(" ".repeat(UserAddResponse.PASSWD_LENGTH));
            assertThat(response.userId()).isEqualTo(" ".repeat(UserAddResponse.USER_ID_LENGTH));
            assertThat(response.fName()).isEqualTo(" ".repeat(UserAddResponse.F_NAME_LENGTH));
            assertThat(response.lName()).isEqualTo(" ".repeat(UserAddResponse.L_NAME_LENGTH));
            assertThat(response.usrType()).isEqualTo(" ".repeat(UserAddResponse.USR_TYPE_LENGTH));
            // The submitted password does not survive anywhere on the response.
            assertThat(response.passwd()).doesNotContain(SEED_PASSWORD);
            assertThat(response.errMsg()).doesNotContain(SEED_PASSWORD);
            // L289: the cursor goes back to the first field of the now-empty form.
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

    // =================================================================================================
    // Preserved absences. Three things COUSR01C does NOT do, and which the Java must go on not doing.
    // Each is verified against the source rather than assumed, because "absent" is the one property a
    // reader cannot confirm by looking at the Java alone.
    // =================================================================================================

    @Nested
    @DisplayName("Preserved absences - COUSR01C:57, :172-173, and the un-extended commarea")
    class PreservedAbsences {

        @Test
        @DisplayName("B4: DFHATTR is commented out at :57, so no DFHATTR attribute is in play")
        void dfhattrContributesNothing() {
            // app/cbl/COUSR01C.cbl:55-57 reads, in order:
            //     COPY DFHAID.
            //     COPY DFHBMSCA.
            //    *COPY DFHATTR.
            // The third is commented, so the compiler never expanded it and the program cannot name a
            // DFHATTR item. What it CAN name is DFHAID's attention identifiers and DFHBMSCA's attributes,
            // and those are exactly the two constant sets this class asserts against. This test documents
            // the boundary rather than asserting a DFHATTR-specific behaviour, because there is none to
            // assert: the migration plan's "DFHATTR: 2 consumers" counts this commented line textually.
            assertThat(CicsAid.DFHENTER).as("DFHAID is copied, so DFHENTER is reachable").isNotZero();
            assertThat(CicsAid.DFHPF3).isNotZero();
            assertThat(CicsAid.DFHPF4).isNotZero();
            assertThat(BmsAttributes.DFHGREEN).as("DFHBMSCA is copied, so DFHGREEN is reachable")
                    .isNotZero();
            assertThat(BmsAttributes.DFHRED).isNotZero();

            // And the two colours the program actually moves are DFHBMSCA's, distinct from one another.
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
            // RETURN-TO-PREV-SCREEN, verbatim:
            //     MOVE WS-TRANID    TO CDEMO-FROM-TRANID     <- runs
            //     MOVE WS-PGMNAME   TO CDEMO-FROM-PROGRAM    <- runs
            //    *MOVE WS-USER-ID   TO CDEMO-USER-ID         <- does NOT run
            //    *MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE       <- does NOT run
            //     MOVE ZEROS        TO CDEMO-PGM-CONTEXT     <- runs
            // Restoring either commented line would overwrite the signed-on operator's identity with the
            // identity of the user just added - which is precisely the behaviour change the comment
            // averted, and the reason the lines must stay inert.
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
            // The id of the user being added never reaches the identity field.
            assertThat(state.commarea().userId()).isNotEqualTo("NEWUSER1");
            assertThat(state.commarea().userType()).isNotEqualTo("U");
            // While the three lines that are NOT commented did run.
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
            // COUSR00C, COUSR02C and COUSR03C each redefine the tail of CARDDEMO-COMMAREA with a 34-byte
            // CDEMO-CU0n-INFO block carrying the paging cursor and the selection flag. COUSR01C does not:
            // grep -c 'CDEMO-CU0[0-9]-INFO' app/cbl/COUSR01C.cbl returns 0. So this screen has no page
            // number, no next-page flag, no selection flag and no auto-lookup-from-selection branch - it
            // is a blank form that is filled in and written, and nothing more.
            ProgramState state = enter(populatedRequest());

            // COCOM01Y's own arithmetic: 34 general + 84 customer + 12 account + 16 card + 14 more = 160.
            assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(160);
            assertThat(NavigationContext.GENERAL_INFO_LENGTH + NavigationContext.CUSTOMER_INFO_LENGTH
                    + NavigationContext.ACCOUNT_INFO_LENGTH + NavigationContext.CARD_INFO_LENGTH
                    + NavigationContext.MORE_INFO_LENGTH)
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH);
            // An extension would have made the accepted length 160 + 34 = 194. It is not accepted.
            assertThat(state.eibcalen()).isEqualTo(NavigationContext.COMMAREA_LENGTH);
            assertThatThrownBy(() -> controller.addUser(populatedRequest(), null,
                    NavigationContext.COMMAREA_LENGTH + NavigationContext.GENERAL_INFO_LENGTH, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(UserAddController.EIBCALEN_PARAM);
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
            // Nor is there any read path at all on this screen: nothing is ever looked up, only written.
            verify(repository, never()).read(any());
        }

        @Test
        @DisplayName("B5: the only dataset call this program makes is the WRITE at :240")
        void theOnlyDatasetCallIsTheWrite() {
            enter(populatedRequest());

            verify(repository).add(any(SecUserRecord.class));
            // No STARTBR, no READNEXT, no READ, no REWRITE, no DELETE. COUSR01C declares one EXEC CICS
            // file command and this is it, so an auto-lookup branch could not exist without adding one.
            org.mockito.Mockito.verifyNoMoreInteractions(repository);
        }
    }

    // =================================================================================================
    // Error highlighting - CSSETATY through common.FieldAttributeSetter.
    //
    // The copybook's outer test is (FLG-NOT-OK OR FLG-BLANK) AND CDEMO-PGM-REENTER, so first entry never
    // highlights however wrong the screen is; the inner test refines it to FLG-BLANK, which is what adds
    // the asterisk on top of the colour.
    // =================================================================================================

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
            // COUSR01C:83-87. The screen is painted from LOW-VALUES with the cursor on FNAMEL and no
            // validation has run at all, so there is nothing to highlight and CDEMO-PGM-CONTEXT is still
            // the ENTER value when the decision would have been taken.
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

    // =================================================================================================
    // The HTTP contract - POST /api/users, transaction CU01 [app/csd/CARDDEMO.CSD:459-460].
    //
    // A standalone dispatcher over this one controller. The rules here are the adapter's own and live
    // nowhere else: the verb and the path, the status a wrong verb earns, which members the wire format
    // carries, whether a blank field is answered by the program or refused by the binder, and that
    // nothing at all is pinned to a session, a cookie, a redirect or a forward.
    // =================================================================================================

    @Nested
    @DisplayName("The HTTP contract - POST /api/users")
    class HttpContract {

        /** The twelve wire names, in the order app/cpy-bms/COUSR01.CPY declares their xxxI items. */
        // The twelve screen fields as they appear on the WIRE: each one's xxxI item in lower case,
        // which @JsonProperty pins per AAP 0.6.3. Not the Java component names, which keep camel case.
        private static final List<String> PAYLOAD_FIELDS = List.of(
            "trnname", "title01", "curdate", "pgmname", "title02", "curtime", "fname", "lname",
            "userid", "passwd", "usrtype", "errmsg");

        private final ObjectMapper mapper = new ObjectMapper();

        /**
         * A dispatcher carrying only {@code POST /api/users}, with the application's own advice attached.
         *
         * <p>The advice is the real {@link WebConfig.CobolErrorHandler} rather than the dispatcher's
         * default handling, because the status and body a rejected request earns are that class's
         * decision and asserting the default would assert nothing about this application.
         *
         * @return the dispatcher; never {@code null}
         */
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
            // ERRMSGI is published as "errmsg" - @JsonProperty pins every screen field's wire name to
            // its xxxI item in lower case (AAP 0.6.3), so path("errMsg") would silently read nothing.
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

            // The twelve payload members, then the two that carry conversation rather than screen state.
            assertThat(members).containsExactly("trnname", "title01", "curdate", "pgmname", "title02",
                    "curtime", "fname", "lname", "userid", "passwd", "usrtype", "errmsg",
                    "navigationContext", "aid");

            // The symbolic map declares a quad per field. Only the last of the four is a payload member:
            // xxxL is the length CICS reports, xxxF the attribute byte and xxxA its REDEFINES view, and
            // all three are metadata the transport has no business carrying.
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
            // The request DTO declares no @NotBlank and no @NotNull, deliberately: COUSR01C:117-151
            // answers each blank field with its OWN message, and a binder rejection would replace five
            // distinct COBOL answers with one framework error the operator was never shown.
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
            // A value wider than the symbolic map's own PIC X(n) could not have arrived from a 3270 at
            // all, so refusing it is not a new rule - it is the field's declared width, enforced.
            String twentyOne = "A".repeat(UserAddRequest.FNAME_LENGTH + 1);

            http().perform(post(UserAddController.USERS_PATH)
                            .param(UserAddController.EIBCALEN_PARAM,
                                    String.valueOf(NavigationContext.COMMAREA_LENGTH))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(request(twentyOne, "Doe", "USR1", "PASS1234", "U"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("fName"));

            verify(repository, never()).add(any(SecUserRecord.class));
        }

        @ParameterizedTest(name = "an over-width {0} earns 400")
        @CsvSource({"fName, 21", "lName, 21", "userId, 9", "passwd, 9", "usrType, 2"})
        @DisplayName("every data field's @Size maximum is the width its xxxI item declares")
        void everyDataFieldEnforcesItsDeclaredWidth(String member, int overWidth) throws Exception {
            String tooWide = "X".repeat(overWidth);
            UserAddRequest payload = switch (member) {
                case "fName" -> request(tooWide, "Doe", "USR1", "PASS1234", "U");
                case "lName" -> request("John", tooWide, "USR1", "PASS1234", "U");
                case "userId" -> request("John", "Doe", tooWide, "PASS1234", "U");
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
            // The conversation comes back in the body instead, which is rule R6 in one assertion.
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

    /** {@code MOVE '<literal>' TO WS-MESSAGE PIC X(80)} - right-padded to eighty. */
    private static String pad(String literal) {
        return literal + " ".repeat(UserAddController.WS_MESSAGE_LENGTH - literal.length());
    }
}
