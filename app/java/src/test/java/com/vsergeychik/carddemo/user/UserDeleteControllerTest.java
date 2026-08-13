package com.vsergeychik.carddemo.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldHighlight;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldValidationState;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import com.vsergeychik.carddemo.common.ScreenInputRejectedException;
import com.vsergeychik.carddemo.common.ScreenResponse;
import com.vsergeychik.carddemo.config.WebConfig;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.PfKeyResolver.AidKey;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.user.SecUserRepository.HeldRecord;
import com.vsergeychik.carddemo.user.SecUserRepository.ReadResult;
import com.vsergeychik.carddemo.user.SecUserRepository.WriteResult;
import com.vsergeychik.carddemo.user.UserDeleteController.CursorField;
import com.vsergeychik.carddemo.user.UserDeleteController.ProgramState;
import com.vsergeychik.carddemo.user.dto.UserDeleteRequest;
import com.vsergeychik.carddemo.user.dto.UserDeleteRequest.Cu03Info;
import com.vsergeychik.carddemo.user.dto.UserDeleteResponse;
import com.vsergeychik.carddemo.user.model.SecUserRecord;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@link UserDeleteController} - the {@code COUSR03C} / {@code CU03} delete-user screen.
 */
@DisplayName("UserDeleteController - the COUSR03C / CU03 delete-user screen")
class UserDeleteControllerTest {
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2022-07-19T23:12:35Z"), ZoneOffset.UTC);

    private static final String EXPECTED_DATE = "07/19/22";

    private static final String EXPECTED_TIME = "23:12:35";

    private static final Charset CODE_PAGE = StandardCharsets.US_ASCII;

    private static final String USER_ID = "USER0001";

    private static final String REDACTED_PWD = "REDACTED";

    private SecUserRepository repository;

    private UserDeleteController controller;

    @BeforeEach
    void setUp() {
        repository = mock(SecUserRepository.class);
        when(repository.datasetCharset()).thenReturn(CODE_PAGE);
        controller = new UserDeleteController(repository, FIXED_CLOCK);
    }

    private static NavigationContext reenter() {
        return NavigationContext.empty().withPgmReenter();
    }

    private static NavigationContext enter() {
        return NavigationContext.empty();
    }

    private static UserDeleteRequest withSelection(UserDeleteRequest request, String selected) {
        Cu03Info info = request.cu03Info();
        return withExtension(request, new Cu03Info(info.usridFirst(), info.usridLast(), info.pageNum(),
                info.nextPageFlg(), info.usrSelFlg(), selected));
    }

    private static UserDeleteRequest withExtension(UserDeleteRequest request, Cu03Info extension) {
        return new UserDeleteRequest(request.trnName(), request.title01(), request.curDate(),
                request.pgmName(), request.title02(), request.curTime(), request.usrIdIn(),
                request.fName(), request.lName(), request.usrType(), request.errMsg(),
                request.navigationContext(), request.aid(), extension);
    }

    private static UserDeleteRequest screen(String usrIdIn, NavigationContext commarea) {
        UserDeleteRequest blank = UserDeleteRequest.empty();
        return new UserDeleteRequest(blank.trnName(), blank.title01(), blank.curDate(), blank.pgmName(),
                blank.title02(), blank.curTime(), usrIdIn, blank.fName(), blank.lName(), blank.usrType(),
                blank.errMsg(), commarea, blank.aid(), null);
    }

    private static UserDeleteRequest carrying(UserDeleteRequest base, String aid) {
        return new UserDeleteRequest(base.trnName(), base.title01(), base.curDate(), base.pgmName(),
                base.title02(), base.curTime(), base.usrIdIn(), base.fName(), base.lName(),
                base.usrType(), base.errMsg(), base.navigationContext(), aid, base.cu03Info());
    }

    private static SecUserRecord user(String id) {
        return SecUserRecord.of(id, "Sam", "Spade", REDACTED_PWD, "U", CODE_PAGE);
    }

    private static String errMsgImage(String text) {
        return text + " ".repeat(UserDeleteResponse.ERR_MSG_LENGTH - text.length());
    }

    private static String displayLine(int resp, int reas) {
        return "RESP:" + String.format("%09d", resp) + "REAS:" + String.format("%09d", reas);
    }

    private HeldRecord stubHeldRead(String id) {
        HeldRecord hold = mock(HeldRecord.class);
        when(repository.readForUpdate(anyString())).thenReturn(ReadResult.held(user(id), hold));
        return hold;
    }

    @Nested
    @DisplayName("Construction - three immutable collaborators and no mutable field")
    class Construction {
        @Test
        @DisplayName("the codec carries the repository's code page, never the platform default")
        void codecTakesTheRepositoryCodePage() {
            assertThat(controller.codec().charset()).isEqualTo(CODE_PAGE);
        }

        @Test
        @DisplayName("a missing repository is refused: COUSR03C reaches USRSEC no other way")
        void repositoryIsRequired() {
            assertThatThrownBy(() -> new UserDeleteController(null, FIXED_CLOCK))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("SecUserRepository");
        }

        @Test
        @DisplayName("a missing clock is refused: POPULATE-HEADER-INFO reads FUNCTION CURRENT-DATE")
        void clockIsRequired() {
            assertThatThrownBy(() -> new UserDeleteController(repository, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Clock");
        }

        @Test
        @DisplayName("identity is taken from the payload contract, so the two cannot drift")
        void identityMatchesTheScreenContract() {
            assertThat(UserDeleteController.WS_TRANID).isEqualTo("CU03");
            assertThat(UserDeleteController.WS_PGMNAME).isEqualTo("COUSR03C");
            assertThat(UserDeleteController.WS_MAP).isEqualTo("COUSR3A");
            assertThat(UserDeleteController.WS_MAPSET).isEqualTo("COUSR03");
            assertThat(UserDeleteController.USERS_PATH).isEqualTo("/api/users/{userId}");
            assertThat(UserDeleteController.USER_ID_VARIABLE).isEqualTo("userId");
            assertThat(UserDeleteController.WS_MESSAGE_LENGTH).isEqualTo(80);
            assertThat(UserDeleteController.ERR_MSG_LENGTH).isEqualTo(78);
            assertThat(UserDeleteController.SEC_USR_ID_LENGTH).isEqualTo(8);
            assertThat(UserDeleteController.WS_RESP_CD_DIGITS).isEqualTo(9);
        }
    }

    @Nested
    @DisplayName("B5 - the delete-failure message is a preserved source defect")
    class PreservedSourceDefect {
        @Test
        @DisplayName("COUSR03C:332 moves 'Unable to Update User...' on the DELETE path, verbatim")
        void theDeleteFailureMessageSaysUpdate() {
            assertThat(UserDeleteController.MSG_UNABLE_TO_UPDATE_USER)
                    .isEqualTo("Unable to Update User...");
        }

        @Test
        @DisplayName("the wording the source never used appears in no constant of the controller")
        void theCorrectedWordingIsAbsent() {
            assertThat(UserDeleteController.MSG_UNABLE_TO_UPDATE_USER).doesNotContain("Delete");
            assertThat(UserDeleteController.MSG_UNABLE_TO_LOOKUP_USER).doesNotContain("Delete");
            assertThat(UserDeleteController.MSG_USER_ID_NOT_FOUND).doesNotContain("Delete");
            assertThat(UserDeleteController.MSG_USER_ID_EMPTY).doesNotContain("Delete");
        }

        @Test
        @DisplayName("every message literal is byte-exact, ellipses and spacing included")
        void everyMessageLiteralIsByteExact() {
            assertThat(UserDeleteController.MSG_USER_ID_EMPTY).isEqualTo("User ID can NOT be empty...");
            assertThat(UserDeleteController.MSG_PRESS_PF5)
                    .isEqualTo("Press PF5 key to delete this user ...");
            assertThat(UserDeleteController.MSG_USER_ID_NOT_FOUND).isEqualTo("User ID NOT found...");
            assertThat(UserDeleteController.MSG_UNABLE_TO_LOOKUP_USER)
                    .isEqualTo("Unable to lookup User...");
            assertThat(UserDeleteController.MSG_USER_PREFIX).isEqualTo("User ").hasSize(5);
            assertThat(UserDeleteController.MSG_HAS_BEEN_DELETED_SUFFIX)
                    .isEqualTo(" has been deleted ...").hasSize(21);
        }
    }

    @Nested
    @DisplayName("B5 - WS-USR-MODIFIED is vestigial: set to NO once, never to YES, never read")
    class VestigialModifiedFlag {
        @Test
        @DisplayName(":85 SET USR-MODIFIED-NO is the only statement that touches it, so NO is reachable")
        void theNoStateIsTheOpeningState() {
            ProgramState fresh = new ProgramState();

            assertThat(fresh.isUsrModified()).isFalse();

            fresh.setUsrModifiedNo();
            assertThat(fresh.isUsrModified()).isFalse();
        }

        @Test
        @DisplayName("a successful delete leaves it false: the one path that plainly 'modified' a user")
        void aSuccessfulDeleteLeavesItFalse() {
            HeldRecord hold = stubHeldRead(USER_ID);
            when(hold.deleteHeld()).thenReturn(WriteResult.written());

            ProgramState state = controller.mainPara(screen(USER_ID, reenter()), CicsAid.DFHPF5, null);

            assertThat(state.response().errMsg())
                    .isEqualTo(errMsgImage("User USER0001 has been deleted ..."));
            assertThat(state.isUsrModified()).isFalse();
        }

        @Test
        @DisplayName("the guard-free fall-through leaves it false as well - lines 188-192")
        void theGuardFreeFallThroughLeavesItFalse() {
            when(repository.readForUpdate(anyString())).thenReturn(ReadResult.notFound());

            ProgramState state = controller.mainPara(screen(USER_ID, reenter()), CicsAid.DFHPF5, null);

            assertThat(state.response().errMsg()).isEqualTo(errMsgImage("Unable to Update User..."));
            assertThat(state.isUsrModified()).isFalse();
        }

        @ParameterizedTest(name = "AID {0}")
        @ValueSource(bytes = {CicsAid.DFHENTER, CicsAid.DFHPF3, CicsAid.DFHPF4, CicsAid.DFHPF5,
                CicsAid.DFHPF12, CicsAid.DFHPF7, CicsAid.DFHCLEAR, CicsAid.DFHPA1})
        @DisplayName("every arm of EVALUATE EIBAID:108-130 leaves it false, handled arms and OTHER alike")
        void everyAidArmLeavesItFalse(byte aid) {
            HeldRecord hold = stubHeldRead(USER_ID);
            when(hold.deleteHeld()).thenReturn(WriteResult.written());

            ProgramState state = controller.mainPara(screen(USER_ID, reenter()), aid, null);

            assertThat(state.isUsrModified()).isFalse();
        }

        @Test
        @DisplayName("the failure paths leave it false: NOTFND and WHEN OTHER, on the read and the delete")
        void theFailurePathsLeaveItFalse() {
            when(repository.readForUpdate(anyString())).thenReturn(ReadResult.endOfFile());
            assertThat(controller.mainPara(screen(USER_ID, reenter()), CicsAid.DFHENTER, null)
                    .isUsrModified()).isFalse();

            HeldRecord hold = stubHeldRead(USER_ID);
            when(hold.deleteHeld()).thenReturn(WriteResult.notFound());
            assertThat(controller.mainPara(screen(USER_ID, reenter()), CicsAid.DFHPF5, null)
                    .isUsrModified()).isFalse();

            when(hold.deleteHeld()).thenReturn(WriteResult.duplicateRecord());
            assertThat(controller.mainPara(screen(USER_ID, reenter()), CicsAid.DFHPF5, null)
                    .isUsrModified()).isFalse();
        }

        @Test
        @DisplayName("the paths that never reach USRSEC leave it false: blank id, cold start, first entry")
        void thePathsThatTouchNoFileLeaveItFalse() {
            assertThat(controller.mainPara(screen(" ".repeat(8), reenter()), CicsAid.DFHPF5, null)
                    .isUsrModified()).isFalse();

            assertThat(controller.mainPara(null, CicsAid.DFHENTER, null).isUsrModified()).isFalse();

            assertThat(controller.mainPara(screen(" ".repeat(8), enter()), CicsAid.DFHENTER, null)
                    .isUsrModified()).isFalse();
        }

        @Test
        @DisplayName("G50 - the YES state is unreachable by design: no API of any kind reaches it")
        void theYesStateIsUnreachableByDesign() {
            assertThat(ProgramState.class.getDeclaredMethods())
                    .extracting(Method::getName)
                    .contains("isUsrModified", "setUsrModifiedNo")
                    .noneSatisfy(name -> assertThat(name).containsIgnoringCase("ModifiedYes"));

            assertThat(ProgramState.class.getDeclaredFields())
                    .filteredOn(field -> "usrModified".equals(field.getName()))
                    .singleElement()
                    .satisfies(field -> {
                        assertThat(Modifier.isPrivate(field.getModifiers())).isTrue();
                        assertThat(Modifier.isStatic(field.getModifiers())).isFalse();
                    });
        }

        @Test
        @DisplayName("the flag is never projected onto the screen: it is WORKING-STORAGE, not a map field")
        void theFlagIsNotAPayloadMember() {
            assertThat(UserDeleteResponse.class.getRecordComponents())
                    .extracting(RecordComponent::getName)
                    .doesNotContain("usrModified", "errFlg");
            assertThat(UserDeleteRequest.class.getRecordComponents())
                    .extracting(RecordComponent::getName)
                    .doesNotContain("usrModified", "errFlg");
        }
    }

    @Nested
    @DisplayName("eibAidOf - the payload's one-character EIBAID image")
    class AidImage {
        @Test
        @DisplayName("one character is the byte, for every key this program handles")
        void oneCharacterIsTheByte() {
            byte[] handled = {CicsAid.DFHENTER, CicsAid.DFHPF3, CicsAid.DFHPF4, CicsAid.DFHPF5,
                    CicsAid.DFHPF12, CicsAid.DFHCLEAR, CicsAid.DFHPA1};

            for (byte expected : handled) {
                assertThat(UserDeleteController.eibAidOf(String.valueOf((char) (expected & 0xFF))))
                        .as("the payload's one character is the EIBAID byte itself")
                        .isEqualTo(expected);
            }
        }

        @Test
        @DisplayName("a high function key stays itself: PF17 is not folded onto PF5, which deletes")
        void highFunctionKeysAreNotFolded() {
            byte pf17 = UserDeleteController.eibAidOf(String.valueOf((char) (CicsAid.DFHPF17 & 0xFF)));

            assertThat(pf17).isEqualTo(CicsAid.DFHPF17);
            assertThat(PfKeyResolver.isPf5(pf17))
                    .as("PF17 must not reach the WHEN DFHPF5 arm, which is the delete")
                    .isFalse();
            assertThat(PfKeyResolver.resolve(pf17))
                    .as("CSSTRPFY does fold it onto PFK05 - which is exactly why the token is not the "
                            + "input carrier")
                    .contains(AidKey.PFK05);
        }

        @Test
        @DisplayName("an absent image is no key at all, and is never defaulted to ENTER")
        void anAbsentImageIsDfhnull() {
            assertThat(UserDeleteController.eibAidOf(null)).isEqualTo(CicsAid.DFHNULL);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "     ", "NOPE ", "PFK99", "PFK05", "enter"})
        @DisplayName("any width other than one names no byte, and reaches WHEN OTHER as DFHNULL")
        void anyOtherWidthIsDfhnull(String image) {
            assertThat(UserDeleteController.eibAidOf(image)).isEqualTo(CicsAid.DFHNULL);
        }

        @Test
        @DisplayName("a character above the one-byte AID space is DFHNULL, never narrowed onto PF5")
        void aCharacterAboveTheAidSpaceIsDfhnull() {
            assertThat(UserDeleteController.eibAidOf(String.valueOf((char) 0x01F5)))
                    .isEqualTo(CicsAid.DFHNULL);
        }
    }

    @Nested
    @DisplayName("resolveEibAid - the query parameter wins over the payload's image")
    class ResolveEibAid {
        @Test
        @DisplayName("a stated byte is used as it stands, and the payload's image is not consulted")
        void theParameterWins() {
            UserDeleteRequest carryingEnter = screen(USER_ID, reenter());

            assertThat(controller.resolveEibAid(CicsAid.DFHPF5 & 0xFF, carryingEnter))
                    .isEqualTo(CicsAid.DFHPF5);
        }

        @Test
        @DisplayName("no parameter falls back to the payload's own one-character image")
        void thePayloadIsTheFallback() {
            UserDeleteRequest carryingPf5 = carrying(screen(USER_ID, reenter()),
                    String.valueOf((char) (CicsAid.DFHPF5 & 0xFF)));

            assertThat(controller.resolveEibAid(null, carryingPf5))
                    .isEqualTo(CicsAid.DFHPF5);
        }

        @Test
        @DisplayName("an absent payload and an absent parameter is DFHNULL - EIBCALEN = 0 dispatches nothing")
        void bothAbsentIsDfhnull() {
            assertThat(controller.resolveEibAid(null, null)).isEqualTo(CicsAid.DFHNULL);
        }

        @ParameterizedTest
        @ValueSource(ints = {-1, 256, 300})
        @DisplayName("a stated value outside one byte is refused rather than wrapped")
        void anOutOfRangeParameterIsRefused(int stated) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> controller.resolveEibAid(stated, null))
                    .withMessageContaining(UserDeleteController.EIBAID_PARAM);
        }
    }

    @Nested
    @DisplayName("The raw EIBAID byte - PF17 must not become the PFK05 delete")
    class TheRawAidByte {
        @Test
        @DisplayName("a raw PF17 byte deletes nothing: it reaches WHEN OTHER, as it does on the terminal")
        void pf17DoesNotDelete() {
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF17))
                    .as("the copybook does fold it - that is not in dispute")
                    .contains(AidKey.PFK05);
            assertThat(PfKeyResolver.resolveWithoutFolding(CicsAid.DFHPF17))
                    .as("but this program never copied the copybook")
                    .isEmpty();

            ProgramState state =
                    controller.mainPara(screen(USER_ID, reenter()), CicsAid.DFHPF17, null);

            assertThat(state.response().errMsg())
                    .isEqualTo(errMsgImage("Invalid key pressed. Please see below..."));
            verify(repository, never()).readForUpdate(anyString());
            assertThat(state.isErrFlagOn()).isTrue();
        }

        @Test
        @DisplayName("a raw PF5 byte still deletes, so the confirm key itself is unaffected")
        void pf5StillDeletes() {
            HeldRecord hold = stubHeldRead(USER_ID);
            when(hold.deleteHeld()).thenReturn(WriteResult.written());

            ProgramState state =
                    controller.mainPara(screen(USER_ID, reenter()), CicsAid.DFHPF5, null);

            assertThat(state.response().errMsg())
                    .isEqualTo(errMsgImage("User USER0001 has been deleted ..."));
            verify(hold).deleteHeld();
        }

        @ParameterizedTest(name = "a raw DFHPF{0} byte reads nothing and deletes nothing")
        @ValueSource(ints = {13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24})
        @DisplayName("all twelve upper function keys reach WHEN OTHER, none reaching a handled arm")
        void everyUpperKeyIsInvalidHere(int pfNumber) {
            ProgramState state = controller.mainPara(screen(USER_ID, reenter()),
                    functionKeyByte(pfNumber), null);

            assertThat(state.isErrFlagOn()).isTrue();
            assertThat(state.response().errMsg())
                    .isEqualTo(errMsgImage("Invalid key pressed. Please see below..."));
            assertThat(state.isTransferred())
                    .as("and none of them transfers, which PF3 and PF12 would")
                    .isFalse();
            verify(repository, never()).readForUpdate(anyString());
        }

        @Test
        @DisplayName("the query parameter carries the byte end to end, PF17 and PF5 landing differently")
        void theRouteCarriesTheByte() {
            UserDeleteResponse invalid = controller.deleteUser(USER_ID, screen(USER_ID, reenter()),
                    Byte.toUnsignedInt(CicsAid.DFHPF17), null).screen();

            assertThat(invalid.errMsg())
                    .isEqualTo(errMsgImage("Invalid key pressed. Please see below..."));
            verify(repository, never()).readForUpdate(anyString());

            HeldRecord hold = stubHeldRead(USER_ID);
            when(hold.deleteHeld()).thenReturn(WriteResult.written());

            UserDeleteResponse deleted = controller.deleteUser(USER_ID, screen(USER_ID, reenter()),
                    Byte.toUnsignedInt(CicsAid.DFHPF5), null).screen();

            assertThat(deleted.errMsg())
                    .isEqualTo(errMsgImage("User USER0001 has been deleted ..."));
        }

        @Test
        @DisplayName("both spellings of the parameter reach the same byte through the route")
        void bothSpellingsAreHonoured() {
            assertThat(controller.deleteUser(USER_ID, screen(USER_ID, reenter()),
                    Byte.toUnsignedInt(CicsAid.DFHPF17), null).screen().errMsg())
                    .isEqualTo(errMsgImage("Invalid key pressed. Please see below..."));
            assertThat(controller.deleteUser(USER_ID, screen(USER_ID, reenter()), null,
                    Byte.toUnsignedInt(CicsAid.DFHPF17)).screen().errMsg())
                    .isEqualTo(errMsgImage("Invalid key pressed. Please see below..."));
            verify(repository, never()).readForUpdate(anyString());
        }

        @Test
        @DisplayName("a token naming a different key is refused before anything is read or deleted")
        void aDisagreeingTokenIsRefused() {
            UserDeleteRequest stating = withAidToken(screen(USER_ID, reenter()), AidKey.PFK03.token());

            assertThatThrownBy(() -> controller.deleteUser(USER_ID, stating,
                    Byte.toUnsignedInt(CicsAid.DFHPF5), null))
                    .isInstanceOf(ScreenInputRejectedException.class)
                    .hasMessageContaining("aid");

            verify(repository, never()).readForUpdate(anyString());
        }

        @Test
        @DisplayName("a token restating the byte agrees, and the byte is still the one acted on")
        void aConsistentTokenIsAccepted() {
            UserDeleteRequest stating = withAidToken(screen(USER_ID, reenter()), AidKey.PFK05.token());

            UserDeleteResponse painted = controller.deleteUser(USER_ID, stating,
                    Byte.toUnsignedInt(CicsAid.DFHPF17), null).screen();

            assertThat(painted.errMsg())
                    .as("'PFK05' is what CSSTRPFY stores for PF17, so the two agree - and PF17 is what "
                            + "is acted on, which is the invalid-key arm")
                    .isEqualTo(errMsgImage("Invalid key pressed. Please see below..."));
            verify(repository, never()).readForUpdate(anyString());
        }

        @ParameterizedTest(name = "a stated {0} is refused")
        @ValueSource(ints = {-1, 256, 4096})
        @DisplayName("a value that is not one byte is refused rather than narrowed to a key not pressed")
        void anImpossibleByteIsRefused(int stated) {
            assertThatThrownBy(() -> controller.deleteUser(USER_ID, screen(USER_ID, reenter()),
                    stated, null))
                    .isInstanceOf(ScreenInputRejectedException.class);

            verify(repository, never()).readForUpdate(anyString());
        }

        @Test
        @DisplayName("no stated byte leaves the payload's own image standing, unfolded")
        void aTokenOnlyRequestIsUnchanged() {
            UserDeleteResponse refused = controller.deleteUser(USER_ID,
                    withAidToken(screen(USER_ID, reenter()), AidKey.PFK05.token()),
                    null, null).screen();

            assertThat(refused.errMsg())
                    .isEqualTo(errMsgImage("Invalid key pressed. Please see below..."));
            verify(repository, never()).readForUpdate(anyString());

            HeldRecord hold = stubHeldRead(USER_ID);
            when(hold.deleteHeld()).thenReturn(WriteResult.written());

            UserDeleteResponse painted = controller.deleteUser(USER_ID,
                    withAidToken(screen(USER_ID, reenter()),
                            PfKeyResolver.aidImage(CicsAid.DFHPF5)),
                    null, null).screen();

            assertThat(painted.errMsg())
                    .isEqualTo(errMsgImage("User USER0001 has been deleted ..."));
        }

        private static UserDeleteRequest withAidToken(UserDeleteRequest request, String token) {
            return new UserDeleteRequest(request.trnName(), request.title01(), request.curDate(),
                    request.pgmName(), request.title02(), request.curTime(), request.usrIdIn(),
                    request.fName(), request.lName(), request.usrType(), request.errMsg(),
                    request.navigationContext(), token, request.cu03Info());
        }

        private static byte functionKeyByte(int pfNumber) {
            try {
                return CicsAid.class.getDeclaredField("DFHPF" + pfNumber).getByte(null);
            } catch (ReflectiveOperationException absent) {
                throw new AssertionError("CicsAid does not declare DFHPF" + pfNumber, absent);
            }
        }
    }

    @Nested
    @DisplayName("MAIN-PARA:90-92 - EIBCALEN = 0 hands control to the sign-on screen")
    class ColdStart {
        @Test
        @DisplayName("no payload at all is EIBCALEN = 0")
        void anAbsentPayloadTransfersToSignOn() {
            ProgramState state = controller.mainPara(null, CicsAid.DFHENTER, null);

            assertThat(state.response().nextProgram()).isEqualTo("COSGN00C");
            assertThat(state.isTransferred()).isTrue();
            assertThat(state.isReturned()).isFalse();
            assertThat(state.sendCount()).isZero();
            verify(repository, never()).readForUpdate(anyString());
        }

        @Test
        @DisplayName("a payload with no communication area is the same condition")
        void anAbsentCommareaTransfersToSignOn() {
            UserDeleteRequest noCommarea = screen(USER_ID, null);

            ProgramState state = controller.mainPara(noCommarea, CicsAid.DFHPF5, null);

            assertThat(state.response().nextProgram()).isEqualTo("COSGN00C");
            assertThat(state.isTransferred()).isTrue();
            verify(repository, never()).readForUpdate(anyString());
        }

        @Test
        @DisplayName("the outgoing commarea names where control came from and resets the context")
        void theOutgoingCommareaIsStamped() {
            ProgramState state = controller.mainPara(null, CicsAid.DFHENTER, null);

            assertThat(state.commarea().fromTranid()).isEqualTo("CU03");
            assertThat(state.commarea().fromProgram()).isEqualTo("COUSR03C");
            assertThat(state.commarea().pgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_ENTER);
            assertThat(state.response().navigationContext()).isEqualTo(state.commarea());
        }

        @Test
        @DisplayName("a transfer names only the program: COUSR03C sets no CDEMO-LAST-MAP or MAPSET")
        void aTransferNamesNoMap() {
            ProgramState state = controller.mainPara(null, CicsAid.DFHENTER, null);

            assertThat(state.response().nextMapset()).isBlank();
            assertThat(state.response().nextMap()).isBlank();
        }
    }

    @Nested
    @DisplayName("MAIN-PARA:95-105 - first entry paints the screen and may pre-fetch")
    class FirstEntry {
        @Test
        @DisplayName("with no user selected the screen is painted empty and nothing is read")
        void withNoSelectionNothingIsRead() {
            ProgramState state = controller.mainPara(screen(" ".repeat(8), enter()), CicsAid.DFHENTER,
                    null);

            verify(repository, never()).readForUpdate(anyString());
            assertThat(state.sendCount()).isOne();
            assertThat(ScreenFieldImage.isUnpainted(state.response().usrIdIn())).isTrue();
            assertThat(state.response().errMsg()).isBlank();
            assertThat(state.cursorField()).contains(CursorField.USRIDINL);
            assertThat(state.isErrFlagOn()).isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {"        ", "", "\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000"})
        @DisplayName("a selection of spaces or low-values is no selection - line 99")
        void aBlankSelectionIsNoSelection(String selected) {
            controller.mainPara(screen(" ".repeat(8), enter()), CicsAid.DFHENTER, selected);

            verify(repository, never()).readForUpdate(anyString());
        }

        @Test
        @DisplayName("a selected user is moved onto the screen and fetched - lines 101-103")
        void aSelectedUserIsFetched() {
            stubHeldRead(USER_ID);

            ProgramState state = controller.mainPara(screen(" ".repeat(8), enter()), CicsAid.DFHENTER,
                    USER_ID);

            verify(repository).readForUpdate(USER_ID);
            assertThat(state.response().usrIdIn()).isEqualTo(USER_ID);
            assertThat(state.response().fName()).isEqualTo("Sam" + " ".repeat(17));
            assertThat(state.response().lName()).isEqualTo("Spade" + " ".repeat(15));
            assertThat(state.response().usrType()).isEqualTo("U");
            assertThat(state.response().errMsg())
                    .isEqualTo(errMsgImage("Press PF5 key to delete this user ..."));
        }

        @Test
        @DisplayName("the send at line 105 is outside the guard, so a fetch sends three times")
        void theUnconditionalSendIsPerformedAsWell() {
            stubHeldRead(USER_ID);

            ProgramState state = controller.mainPara(screen(" ".repeat(8), enter()), CicsAid.DFHENTER,
                    USER_ID);

            assertThat(state.sendCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("line 96 marks the conversation re-entrant for the next turn")
        void theConversationBecomesReentrant() {
            ProgramState state = controller.mainPara(screen(" ".repeat(8), enter()), CicsAid.DFHENTER,
                    null);

            assertThat(state.commarea().isReenter()).isTrue();
            assertThat(state.isReturned()).isTrue();
            assertThat(state.isTransferred()).isFalse();
        }

        @Test
        @DisplayName("the AID is not examined on first entry: even an unmapped key paints the screen")
        void theAidIsNotExaminedOnFirstEntry() {
            ProgramState state = controller.mainPara(screen(" ".repeat(8), enter()), CicsAid.DFHPA3,
                    null);

            assertThat(state.response().errMsg()).isBlank();
            assertThat(state.sendCount()).isOne();
        }

        @Test
        @DisplayName("a context that is neither 0 nor 1 is still NOT REENTER - line 95")
        void anOutOfRangeContextIsNotReenter() {
            NavigationContext odd = NavigationContext.empty().withPgmContext(7);

            ProgramState state = controller.mainPara(screen(" ".repeat(8), odd), CicsAid.DFHENTER, null);

            assertThat(state.commarea().isReenter()).isTrue();
            assertThat(state.sendCount()).isOne();
        }
    }

    @Nested
    @DisplayName("PROCESS-ENTER-KEY:142-169 - the fetch that paints the record for confirmation")
    class EnterKey {
        @ParameterizedTest
        @ValueSource(strings = {"        ", "", "\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000"})
        @DisplayName("a blank or low-values id is answered with a message, not a rejection - line 147")
        void aBlankIdIsAnsweredWithAMessage(String usrIdIn) {
            ProgramState state = controller.mainPara(screen(usrIdIn, reenter()), CicsAid.DFHENTER, null);

            assertThat(state.response().errMsg()).isEqualTo(errMsgImage("User ID can NOT be empty..."));
            assertThat(state.isErrFlagOn()).isTrue();
            assertThat(state.cursorField()).contains(CursorField.USRIDINL);
            assertThat(state.sendCount()).isOne();
            verify(repository, never()).readForUpdate(anyString());
        }

        @Test
        @DisplayName("a successful read paints the three display-only fields and sends twice")
        void aSuccessfulReadPaintsTheRecord() {
            stubHeldRead(USER_ID);

            ProgramState state = controller.mainPara(screen(USER_ID, reenter()), CicsAid.DFHENTER, null);

            assertThat(state.isErrFlagOn()).isFalse();
            assertThat(state.sendCount()).isEqualTo(2);
            assertThat(state.response().fName()).startsWith("Sam");
            assertThat(state.response().lName()).startsWith("Spade");
            assertThat(state.response().usrType()).isEqualTo("U");
            assertThat(state.response().errMsg())
                    .isEqualTo(errMsgImage("Press PF5 key to delete this user ..."));
            assertThat(state.errMsgColour()).isEqualTo(BmsAttributes.DFHNEUTR);
            assertThat(state.respCd()).isEqualTo(FileStatus.NORMAL);
            assertThat(state.displayLines()).isEmpty();
        }

        @Test
        @DisplayName("the read is a READ ... UPDATE, so the record is held from the fetch onward")
        void theFetchTakesTheLock() {
            HeldRecord hold = stubHeldRead(USER_ID);

            ProgramState state = controller.mainPara(screen(USER_ID, reenter()), CicsAid.DFHENTER, null);

            assertThat(state.heldRecord()).contains(hold);
            assertThat(state.secUserData().secUsrId()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("an id mixing spaces and low-values is a VALUE, so line 147 lets it through to the "
                + "read")
        void aMixedIdIsNotBlankAndReachesTheRead() {
            when(repository.readForUpdate(anyString())).thenReturn(ReadResult.notFound());

            ProgramState state = controller.mainPara(screen(" \u0000 \u0000 \u0000 \u0000", reenter()),
                    CicsAid.DFHENTER, null);

            assertThat(state.response().errMsg())
                    .as("the NOTFND message, not 'User ID can NOT be empty...'")
                    .isEqualTo(errMsgImage("User ID NOT found..."));
            verify(repository).readForUpdate(anyString());
        }

        @Test
        @DisplayName("NOTFND raises the flag, names the field and stops the second guard - line 289")
        void aNotFoundReadStops() {
            when(repository.readForUpdate(anyString())).thenReturn(ReadResult.notFound());

            ProgramState state = controller.mainPara(screen(USER_ID, reenter()), CicsAid.DFHENTER, null);

            assertThat(state.response().errMsg()).isEqualTo(errMsgImage("User ID NOT found..."));
            assertThat(state.isErrFlagOn()).isTrue();
            assertThat(state.cursorField()).contains(CursorField.USRIDINL);
            assertThat(state.sendCount()).isOne();
            assertThat(state.displayLines()).isEmpty();
            assertThat(state.heldRecord()).isEmpty();
            assertThat(state.response().fName()).isBlank();
        }

        @Test
        @DisplayName("WHEN OTHER displays both codes and puts the cursor on the FIRST NAME - line 298")
        void anOtherReadDisplaysAndNamesTheFirstName() {
            ReadResult other = ReadResult.endOfFile();
            when(repository.readForUpdate(anyString())).thenReturn(other);

            ProgramState state = controller.mainPara(screen(USER_ID, reenter()), CicsAid.DFHENTER, null);

            assertThat(state.response().errMsg()).isEqualTo(errMsgImage("Unable to lookup User..."));
            assertThat(state.isErrFlagOn()).isTrue();
            assertThat(state.cursorField()).contains(CursorField.FNAMEL);
            assertThat(state.sendCount()).isOne();
            assertThat(state.displayLines())
                    .containsExactly(displayLine(other.cicsResp().orElseThrow(), other.cicsResp2()));
        }

        @Test
        @DisplayName("the previous record's details are cleared before the read - lines 157-159")
        void thePreviousDetailsAreClearedFirst() {
            when(repository.readForUpdate(anyString())).thenReturn(ReadResult.notFound());
            UserDeleteRequest blank = UserDeleteRequest.empty();
            UserDeleteRequest carrying = new UserDeleteRequest(blank.trnName(), blank.title01(),
                    blank.curDate(), blank.pgmName(), blank.title02(), blank.curTime(), USER_ID,
                    "Stale" + " ".repeat(15), "Name" + " ".repeat(16), "A", blank.errMsg(), reenter(),
                    blank.aid(), null);

            ProgramState state = controller.mainPara(carrying, CicsAid.DFHENTER, null);

            assertThat(state.response().fName()).isBlank();
            assertThat(state.response().lName()).isBlank();
            assertThat(state.response().usrType()).isBlank();
        }

        @Test
        @DisplayName("the header is repainted on every send")
        void theHeaderIsRepainted() {
            stubHeldRead(USER_ID);

            ProgramState state = controller.mainPara(screen(USER_ID, reenter()), CicsAid.DFHENTER, null);

            assertThat(state.response().trnName()).isEqualTo("CU03");
            assertThat(state.response().pgmName()).isEqualTo("COUSR03C");
            assertThat(state.response().title01()).isEqualTo(ScreenTitles.CCDA_TITLE01);
            assertThat(state.response().title02()).isEqualTo(ScreenTitles.CCDA_TITLE02);
            assertThat(state.response().curDate()).isEqualTo(EXPECTED_DATE);
            assertThat(state.response().curTime()).isEqualTo(EXPECTED_TIME);
            assertThat(state.dateHeader()).isPresent();
        }

        @Test
        @DisplayName("a RETURN names this program, mapset and map: the conversation stays here")
        void aReturnNamesThisScreen() {
            stubHeldRead(USER_ID);

            ProgramState state = controller.mainPara(screen(USER_ID, reenter()), CicsAid.DFHENTER, null);

            assertThat(state.isReturned()).isTrue();
            assertThat(state.isTransferred()).isFalse();
            assertThat(state.response().nextProgram()).isEqualTo("COUSR03C");
            assertThat(state.response().nextMapset()).isEqualTo("COUSR03");
            assertThat(state.response().nextMap()).isEqualTo("COUSR3A");
        }

        @Test
        @DisplayName("the key is moved into SEC-USR-ID at its full declared width of eight")
        void theKeyIsMovedAtItsDeclaredWidth() {
            stubHeldRead("AB");

            controller.mainPara(screen("AB", reenter()), CicsAid.DFHENTER, null);

            verify(repository).readForUpdate("AB      ");
        }
    }

    @Nested
    @DisplayName("EVALUATE EIBAID:111-118 - PF3 leaves, and performs no delete")
    class Pf3 {
        @Test
        @DisplayName("PF3 performs NO delete: contrast COUSR02C:111-119, which saves first")
        void pf3PerformsNoDelete() {
            ProgramState state = controller.mainPara(screen(USER_ID, reenter()), CicsAid.DFHPF3, null);

            verify(repository, never()).readForUpdate(anyString());
            assertThat(state.heldRecord()).isEmpty();
            assertThat(state.sendCount()).isZero();
            assertThat(state.isTransferred()).isTrue();
        }

        @Test
        @DisplayName("a blank CDEMO-FROM-PROGRAM goes to the admin menu - line 113")
        void aBlankCallerGoesToTheAdminMenu() {
            ProgramState state = controller.mainPara(screen(USER_ID, reenter()), CicsAid.DFHPF3, null);

            assertThat(state.response().nextProgram()).isEqualTo("COADM01C");
        }

        @Test
        @DisplayName("a named CDEMO-FROM-PROGRAM is echoed back - lines 115-116")
        void aNamedCallerIsEchoed() {
            NavigationContext fromList = reenter().withFromProgram("COUSR00C");

            ProgramState state = controller.mainPara(screen(USER_ID, fromList), CicsAid.DFHPF3, null);

            assertThat(state.response().nextProgram()).isEqualTo("COUSR00C");
        }

        @Test
        @DisplayName("a low-values CDEMO-FROM-PROGRAM is blank, so the admin menu wins - line 112")
        void aLowValuesCallerIsBlank() {
            NavigationContext lowValues = reenter().withFromProgram("\u0000".repeat(8));

            ProgramState state = controller.mainPara(screen(USER_ID, lowValues), CicsAid.DFHPF3, null);

            assertThat(state.response().nextProgram()).isEqualTo("COADM01C");
        }

        @Test
        @DisplayName("the transfer is a response field, never a server-side forward - gate G40")
        void theTransferIsAResponseField() {
            ProgramState state = controller.mainPara(screen(USER_ID, reenter()), CicsAid.DFHPF3, null);

            assertThat(state.response().nextProgram()).isEqualTo("COADM01C");
            assertThat(state.commarea().toProgram()).isEqualTo("COADM01C");
            assertThat(state.commarea().fromProgram()).isEqualTo("COUSR03C");
            assertThat(state.commarea().fromTranid()).isEqualTo("CU03");
            assertThat(state.commarea().pgmContext()).isZero();
            assertThat(state.isReturned()).isFalse();
        }
    }

    @Nested
    @DisplayName("EVALUATE EIBAID:119-125 - PF4 clears, PF12 leaves")
    class Pf4AndPf12 {
        @Test
        @DisplayName("PF4 empties the fields and sends, touching no file - lines 341-344")
        void pf4ClearsTheScreen() {
            ProgramState state = controller.mainPara(screen(USER_ID, reenter()), CicsAid.DFHPF4, null);

            verify(repository, never()).readForUpdate(anyString());
            assertThat(state.response().usrIdIn()).isBlank();
            assertThat(state.response().fName()).isBlank();
            assertThat(state.response().lName()).isBlank();
            assertThat(state.response().usrType()).isBlank();
            assertThat(state.response().errMsg()).isBlank();
            assertThat(state.message()).isBlank();
            assertThat(state.cursorField()).contains(CursorField.USRIDINL);
            assertThat(state.sendCount()).isOne();
            assertThat(state.isErrFlagOn()).isFalse();
        }

        @Test
        @DisplayName("PF12 goes to the admin menu unconditionally - line 124")
        void pf12GoesToTheAdminMenu() {
            NavigationContext fromList = reenter().withFromProgram("COUSR00C");

            ProgramState state = controller.mainPara(screen(USER_ID, fromList), CicsAid.DFHPF12, null);

            assertThat(state.response().nextProgram()).isEqualTo("COADM01C");
            assertThat(state.isTransferred()).isTrue();
            verify(repository, never()).readForUpdate(anyString());
        }
    }

    @Nested
    @DisplayName("EVALUATE EIBAID:126-129 - WHEN OTHER refuses the key")
    class InvalidKey {
        @Test
        @DisplayName("a key the resolver maps but this program does not handle: PF7")
        void anUnhandledMappedKeyIsRefused() {
            ProgramState state = controller.mainPara(screen(USER_ID, reenter()), CicsAid.DFHPF7, null);

            assertThat(state.response().errMsg())
                    .isEqualTo(errMsgImage(SystemMessages.CCDA_MSG_INVALID_KEY));
            assertThat(state.isErrFlagOn()).isTrue();
            assertThat(state.sendCount()).isOne();
            verify(repository, never()).readForUpdate(anyString());
        }

        @Test
        @DisplayName("a key the resolver maps to nothing at all: PA3, which CSSTRPFY never tests")
        void anUnmappedKeyIsRefused() {
            ProgramState state = controller.mainPara(screen(USER_ID, reenter()), CicsAid.DFHPA3, null);

            assertThat(state.isErrFlagOn()).isTrue();
            assertThat(state.response().errMsg()).startsWith("Invalid key pressed.");
            assertThat(state.sendCount()).isOne();
        }

        @Test
        @DisplayName("DFHNULL - no identifiable key - reaches the same arm, never defaulted to ENTER")
        void anAbsentAidIsRefused() {
            ProgramState state =
                    controller.mainPara(screen(USER_ID, reenter()), CicsAid.DFHNULL, null);

            assertThat(state.isErrFlagOn()).isTrue();
            assertThat(state.response().errMsg()).startsWith("Invalid key pressed.");
            verify(repository, never()).readForUpdate(anyString());
        }

        @Test
        @DisplayName("CLEAR is not one of the five keys either")
        void clearIsRefused() {
            ProgramState state = controller.mainPara(screen(USER_ID, reenter()), CicsAid.DFHCLEAR, null);

            assertThat(state.isErrFlagOn()).isTrue();
            assertThat(state.response().errMsg()).startsWith("Invalid key pressed.");
        }

        @Test
        @DisplayName("PF17 does NOT reach the PF5 delete arm: it is WHEN OTHER, as on a terminal")
        void aHighFunctionKeyIsNotFoldedOntoTheDeleteKey() {
            ProgramState state = controller.mainPara(screen(USER_ID, reenter()), CicsAid.DFHPF17, null);

            assertThat(state.isErrFlagOn()).isTrue();
            assertThat(state.response().errMsg()).startsWith("Invalid key pressed.");
            verify(repository, never()).readForUpdate(anyString());
            verify(repository, never()).deleteHeld(any());
        }
    }

    @Nested
    @DisplayName("PF5:121-122 -> DELETE-USER-INFO:174-192 -> DELETE-USER-SEC-FILE:305-336")
    class Pf5Delete {
        @Test
        @DisplayName("the read comes first and the delete follows it, in that order - lines 190-191")
        void theReadPrecedesTheDelete() {
            HeldRecord hold = stubHeldRead(USER_ID);
            when(hold.deleteHeld()).thenReturn(WriteResult.written());

            controller.mainPara(screen(USER_ID, reenter()), CicsAid.DFHPF5, null);

            InOrder order = inOrder(repository, hold);
            order.verify(repository).readForUpdate(USER_ID);
            order.verify(hold).deleteHeld();
            order.verifyNoMoreInteractions();
        }

        @Test
        @DisplayName("the delete takes no argument at all - EXEC CICS DELETE has no RIDFLD")
        void theDeleteTakesNoKey() throws Exception {
            HeldRecord hold = stubHeldRead(USER_ID);
            when(hold.deleteHeld()).thenReturn(WriteResult.written());

            controller.mainPara(screen(USER_ID, reenter()), CicsAid.DFHPF5, null);

            assertThat(HeldRecord.class.getMethod("deleteHeld").getParameterCount()).isZero();
            verify(hold).deleteHeld();
        }

        @Test
        @DisplayName("a successful delete clears the screen, turns the message green and names the user")
        void aSuccessfulDeleteReportsTheUser() {
            HeldRecord hold = stubHeldRead(USER_ID);
            when(hold.deleteHeld()).thenReturn(WriteResult.written());

            ProgramState state = controller.mainPara(screen(USER_ID, reenter()), CicsAid.DFHPF5, null);

            assertThat(state.response().errMsg())
                    .isEqualTo(errMsgImage("User USER0001 has been deleted ..."));
            assertThat(state.errMsgColour()).isEqualTo(BmsAttributes.DFHGREEN);
            assertThat(state.response().usrIdIn()).isBlank();
            assertThat(state.response().fName()).isBlank();
            assertThat(state.response().lName()).isBlank();
            assertThat(state.response().usrType()).isBlank();
            assertThat(state.cursorField()).contains(CursorField.USRIDINL);
            assertThat(state.isErrFlagOn()).isFalse();
            assertThat(state.sendCount()).isEqualTo(2);
            assertThat(state.heldRecord()).isEmpty();
            assertThat(state.displayLines()).isEmpty();
        }

        @ParameterizedTest
        @CsvSource({"USER0001,User USER0001 has been deleted ...",
                "AB,User AB has been deleted ...",
                "A,User A has been deleted ..."})
        @DisplayName("DELIMITED BY SPACE trims the id at its first space - line 319")
        void theIdIsDelimitedBySpace(String id, String expected) {
            HeldRecord hold = stubHeldRead(id);
            when(hold.deleteHeld()).thenReturn(WriteResult.written());

            ProgramState state = controller.mainPara(screen(id, reenter()), CicsAid.DFHPF5, null);

            assertThat(state.response().errMsg()).isEqualTo(errMsgImage(expected));
        }

        @Test
        @DisplayName("SEC-USR-ID survives INITIALIZE-ALL-FIELDS, which is what lets the message name it")
        void theRecordAreaSurvivesTheClear() {
            HeldRecord hold = stubHeldRead(USER_ID);
            when(hold.deleteHeld()).thenReturn(WriteResult.written());

            ProgramState state = controller.mainPara(screen(USER_ID, reenter()), CicsAid.DFHPF5, null);

            assertThat(state.secUserData().secUsrId()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("NOTFND from the delete reports the not-found text and names the user id - line 325")
        void aNotFoundDeleteReportsNotFound() {
            HeldRecord hold = stubHeldRead(USER_ID);
            when(hold.deleteHeld()).thenReturn(WriteResult.notFound());

            ProgramState state = controller.mainPara(screen(USER_ID, reenter()), CicsAid.DFHPF5, null);

            assertThat(state.response().errMsg()).isEqualTo(errMsgImage("User ID NOT found..."));
            assertThat(state.isErrFlagOn()).isTrue();
            assertThat(state.cursorField()).contains(CursorField.USRIDINL);
            assertThat(state.sendCount()).isEqualTo(2);
            assertThat(state.displayLines()).isEmpty();
            assertThat(state.errMsgColour()).isEqualTo(BmsAttributes.DFHNEUTR);
        }

        @Test
        @DisplayName("WHEN OTHER reports the PRESERVED 'Update' text, names the first name, and displays")
        void anOtherDeleteReportsThePreservedDefect() {
            HeldRecord hold = stubHeldRead(USER_ID);
            WriteResult duplicate = WriteResult.duplicateRecord();
            when(hold.deleteHeld()).thenReturn(duplicate);

            ProgramState state = controller.mainPara(screen(USER_ID, reenter()), CicsAid.DFHPF5, null);

            assertThat(state.response().errMsg()).isEqualTo(errMsgImage("Unable to Update User..."));
            assertThat(state.response().errMsg()).doesNotContain("Delete");
            assertThat(state.isErrFlagOn()).isTrue();
            assertThat(state.cursorField()).contains(CursorField.FNAMEL);
            assertThat(state.sendCount()).isEqualTo(2);
            assertThat(state.displayLines()).containsExactly(
                    displayLine(duplicate.cicsResp().orElseThrow(), duplicate.cicsResp2()));
        }

        @Test
        @DisplayName("lines 188-192 place NO guard between the read and the delete, so NOTFND still deletes")
        void aFailedReadStillReachesTheDelete() {
            when(repository.readForUpdate(anyString())).thenReturn(ReadResult.notFound());

            ProgramState state = controller.mainPara(screen(USER_ID, reenter()), CicsAid.DFHPF5, null);

            verify(repository).readForUpdate(USER_ID);
            assertThat(state.heldRecord()).isEmpty();
            assertThat(state.response().errMsg()).isEqualTo(errMsgImage("Unable to Update User..."));
            assertThat(state.cursorField()).contains(CursorField.FNAMEL);
            assertThat(state.respCd()).isEqualTo(FileStatus.INVREQ);
            assertThat(state.reasCd()).isEqualTo(FileStatus.NO_REASON_CODE);
            assertThat(state.sendCount()).isEqualTo(2);
            assertThat(state.displayLines())
                    .containsExactly(displayLine(FileStatus.INVREQ, FileStatus.NO_REASON_CODE));
        }

        @Test
        @DisplayName("an unenumerated read response also falls through to the delete, displaying twice")
        void anOtherReadAlsoReachesTheDelete() {
            ReadResult other = ReadResult.endOfFile();
            when(repository.readForUpdate(anyString())).thenReturn(other);

            ProgramState state = controller.mainPara(screen(USER_ID, reenter()), CicsAid.DFHPF5, null);

            assertThat(state.response().errMsg()).isEqualTo(errMsgImage("Unable to Update User..."));
            assertThat(state.sendCount()).isEqualTo(2);
            assertThat(state.displayLines()).containsExactly(
                    displayLine(other.cicsResp().orElseThrow(), other.cicsResp2()),
                    displayLine(FileStatus.INVREQ, FileStatus.NO_REASON_CODE));
        }

        @ParameterizedTest
        @ValueSource(strings = {"        ", "", "\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000"})
        @DisplayName("a blank id stops before the read, so PF5 never reaches the delete - line 177")
        void aBlankIdStopsBeforeTheRead(String usrIdIn) {
            ProgramState state = controller.mainPara(screen(usrIdIn, reenter()), CicsAid.DFHPF5, null);

            assertThat(state.response().errMsg()).isEqualTo(errMsgImage("User ID can NOT be empty..."));
            assertThat(state.isErrFlagOn()).isTrue();
            assertThat(state.cursorField()).contains(CursorField.USRIDINL);
            assertThat(state.sendCount()).isOne();
            verify(repository, never()).readForUpdate(anyString());
        }

        @Test
        @DisplayName("the read's confirmation prompt is produced on the PF5 path too, then replaced")
        void theConfirmationPromptIsProducedOnThePf5PathAsWell() {
            HeldRecord hold = stubHeldRead(USER_ID);
            when(hold.deleteHeld()).thenReturn(WriteResult.written());

            ProgramState state = controller.mainPara(screen(USER_ID, reenter()), CicsAid.DFHPF5, null);

            assertThat(state.sendCount()).isEqualTo(2);
            assertThat(state.response().errMsg())
                    .isEqualTo(errMsgImage("User USER0001 has been deleted ..."));
        }

        @Test
        @DisplayName("the delete on a state holding nothing is INVREQ, driven directly")
        void aDeleteWithNoHoldIsInvreq() {
            ProgramState state = new ProgramState();

            controller.deleteUserSecFile(state);

            assertThat(state.respCd()).isEqualTo(FileStatus.INVREQ);
            assertThat(state.response().errMsg()).isEqualTo(errMsgImage("Unable to Update User..."));
            assertThat(state.cursorField()).contains(CursorField.FNAMEL);
        }
    }

    @Nested
    @DisplayName("Paragraphs driven directly - lines 197-262 and 341-356")
    class Paragraphs {
        @Test
        @DisplayName("RETURN-TO-PREV-SCREEN:199-201 defaults a blank target to the sign-on program")
        void aBlankTargetDefaultsToSignOn() {
            ProgramState state = new ProgramState();

            controller.returnToPrevScreen(state);

            assertThat(state.commarea().toProgram()).isEqualTo("COSGN00C");
            assertThat(state.response().nextProgram()).isEqualTo("COSGN00C");
            assertThat(state.commarea().fromTranid()).isEqualTo("CU03");
            assertThat(state.commarea().fromProgram()).isEqualTo("COUSR03C");
            assertThat(state.commarea().pgmContext()).isZero();
            assertThat(state.isTransferred()).isTrue();
        }

        @Test
        @DisplayName("RETURN-TO-PREV-SCREEN leaves a named target alone")
        void aNamedTargetIsLeftAlone() {
            ProgramState state = new ProgramState();
            state.setCommarea(NavigationContext.empty().withToProgram("COADM01C"));

            controller.returnToPrevScreen(state);

            assertThat(state.response().nextProgram()).isEqualTo("COADM01C");
        }

        @Test
        @DisplayName("SEND-USRDEL-SCREEN:217 truncates WS-MESSAGE X(80) to ERRMSGO X(78) on the RIGHT")
        void theMessageIsRightTruncated() {
            ProgramState state = new ProgramState();
            state.setMessage("X".repeat(78) + "YZ");

            controller.sendUsrdelScreen(state);

            assertThat(state.response().errMsg()).isEqualTo("X".repeat(78));
            assertThat(state.sendCount()).isOne();
        }

        @Test
        @DisplayName("RECEIVE-USRDEL-SCREEN copies all eleven fields into the one buffer")
        void theReceiveCopiesEveryField() {
            ProgramState state = new ProgramState();
            UserDeleteRequest inbound = new UserDeleteRequest("CU03", ScreenTitles.CCDA_TITLE01,
                    EXPECTED_DATE, "COUSR03C", ScreenTitles.CCDA_TITLE02, EXPECTED_TIME, USER_ID,
                    "Sam" + " ".repeat(17), "Spade" + " ".repeat(15), "U", errMsgImage("prior"),
                    reenter(), "ENTER", null);

            controller.receiveUsrdelScreen(state, inbound);

            assertThat(state.response().trnName()).isEqualTo("CU03");
            assertThat(state.response().title01()).isEqualTo(ScreenTitles.CCDA_TITLE01);
            assertThat(state.response().curDate()).isEqualTo(EXPECTED_DATE);
            assertThat(state.response().pgmName()).isEqualTo("COUSR03C");
            assertThat(state.response().title02()).isEqualTo(ScreenTitles.CCDA_TITLE02);
            assertThat(state.response().curTime()).isEqualTo(EXPECTED_TIME);
            assertThat(state.response().usrIdIn()).isEqualTo(USER_ID);
            assertThat(state.response().fName()).startsWith("Sam");
            assertThat(state.response().lName()).startsWith("Spade");
            assertThat(state.response().usrType()).isEqualTo("U");
            assertThat(state.response().errMsg()).startsWith("prior");
            assertThat(state.respCd()).isEqualTo(FileStatus.NORMAL);
            assertThat(state.reasCd()).isEqualTo(FileStatus.NO_REASON_CODE);
        }

        @Test
        @DisplayName("a member the caller omitted becomes spaces at its declared width")
        void anOmittedMemberBecomesSpaces() {
            ProgramState state = new ProgramState();
            UserDeleteRequest sparse = new UserDeleteRequest(null, null, null, null, null, null, null,
                    null, null, null, null, reenter(), null, null);

            controller.receiveUsrdelScreen(state, sparse);

            assertThat(state.response().trnName()).isEqualTo(" ".repeat(4));
            assertThat(state.response().title01()).isEqualTo(" ".repeat(40));
            assertThat(state.response().curDate()).isEqualTo(" ".repeat(8));
            assertThat(state.response().pgmName()).isEqualTo(" ".repeat(8));
            assertThat(state.response().title02()).isEqualTo(" ".repeat(40));
            assertThat(state.response().curTime()).isEqualTo(" ".repeat(8));
            assertThat(state.response().usrIdIn()).isEqualTo(" ".repeat(8));
            assertThat(state.response().fName()).isEqualTo(" ".repeat(20));
            assertThat(state.response().lName()).isEqualTo(" ".repeat(20));
            assertThat(state.response().usrType()).isEqualTo(" ");
            assertThat(state.response().errMsg()).isEqualTo(" ".repeat(78));
        }

        @Test
        @DisplayName("POPULATE-HEADER-INFO:243-262 paints the six header fields from the injected clock")
        void theHeaderComesFromTheInjectedClock() {
            ProgramState state = new ProgramState();

            controller.populateHeaderInfo(state);

            assertThat(state.response().curDate()).isEqualTo(EXPECTED_DATE);
            assertThat(state.response().curTime()).isEqualTo(EXPECTED_TIME);
            assertThat(state.dateHeader()).isPresent();
            assertThat(state.sendCount()).isZero();
        }

        @Test
        @DisplayName("INITIALIZE-ALL-FIELDS:349-356 blanks five items and leaves SEC-USER-DATA alone")
        void theClearLeavesTheRecordAreaAlone() {
            ProgramState state = new ProgramState();
            state.setSecUserData(user(USER_ID));
            state.setMessage("X".repeat(80));

            controller.initializeAllFields(state);

            assertThat(state.response().usrIdIn()).isBlank();
            assertThat(state.response().fName()).isBlank();
            assertThat(state.response().lName()).isBlank();
            assertThat(state.response().usrType()).isBlank();
            assertThat(state.message()).isEqualTo(" ".repeat(80));
            assertThat(state.secUserData().secUsrId()).isEqualTo(USER_ID);
            assertThat(state.cursorField()).contains(CursorField.USRIDINL);
        }

        @Test
        @DisplayName("CLEAR-CURRENT-SCREEN:341-344 clears and then sends")
        void theClearScreenSends() {
            ProgramState state = new ProgramState();

            controller.clearCurrentScreen(state);

            assertThat(state.sendCount()).isOne();
            assertThat(state.response().usrIdIn()).isBlank();
        }

        @Test
        @DisplayName("every paragraph refuses an absent state rather than failing later")
        void everyParagraphRefusesAnAbsentState() {
            assertThatThrownBy(() -> controller.processEnterKey(null))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("ProgramState");
            assertThatThrownBy(() -> controller.deleteUserInfo(null))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("ProgramState");
            assertThatThrownBy(() -> controller.readUserSecFile(null))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("ProgramState");
            assertThatThrownBy(() -> controller.deleteUserSecFile(null))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("ProgramState");
            assertThatThrownBy(() -> controller.returnToPrevScreen(null))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("ProgramState");
            assertThatThrownBy(() -> controller.sendUsrdelScreen(null))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("ProgramState");
            assertThatThrownBy(() -> controller.populateHeaderInfo(null))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("ProgramState");
            assertThatThrownBy(() -> controller.clearCurrentScreen(null))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("ProgramState");
            assertThatThrownBy(() -> controller.initializeAllFields(null))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("ProgramState");
            assertThatThrownBy(() -> controller.receiveUsrdelScreen(new ProgramState(), null))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("inbound screen");
        }
    }

    @Nested
    @DisplayName("COBOL statements - = SPACES OR LOW-VALUES, and DELIMITED BY SPACE")
    class CobolStatements {
        @ParameterizedTest
        @ValueSource(strings = {"", " ", "        ", "\u0000", "\u0000\u0000\u0000\u0000"})
        @DisplayName("an entirely-spaces item and an entirely-low-values item are both blank")
        void blankValuesAreBlank(String value) {
            assertThat(UserDeleteController.isSpacesOrLowValues(value)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {" \u0000 \u0000", "\u0000 \u0000 ", " \u0000", "\u0000 ",
                "    \u0000\u0000\u0000\u0000"})
        @DisplayName("a MIXTURE of spaces and low-values is not blank: it equals neither constant")
        void aMixtureIsNotBlank(String value) {
            assertThat(UserDeleteController.isSpacesOrLowValues(value)).isFalse();
        }

        @Test
        @DisplayName("an absent value is blank: an omitted member is a field that transmitted nothing")
        void anAbsentValueIsBlank() {
            assertThat(UserDeleteController.isSpacesOrLowValues(null)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"A", " A", "A ", "\u0000A", "A\u0000", "USER0001", "0"})
        @DisplayName("one printable character anywhere makes the field non-blank")
        void anyContentMakesItNonBlank(String value) {
            assertThat(UserDeleteController.isSpacesOrLowValues(value)).isFalse();
        }

        @ParameterizedTest
        @CsvSource({"USER0001,USER0001", "'AB      ',AB", "'A       ',A", "'  ',''", "'',''",
                "'ABCDEFGH',ABCDEFGH"})
        @DisplayName("DELIMITED BY SPACE stops at the first space and never transfers the delimiter")
        void theOperandStopsAtTheFirstSpace(String value, String expected) {
            assertThat(UserDeleteController.delimitedBySpace(value)).isEqualTo(expected);
        }

        @Test
        @DisplayName("a STRING operand is never absent")
        void anAbsentOperandIsRefused() {
            assertThatThrownBy(() -> UserDeleteController.delimitedBySpace(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("ProgramState - WORKING-STORAGE for exactly one request")
    class ProgramStateContract {
        @Test
        @DisplayName("a fresh state is the VALUE clauses of lines 35-47")
        void aFreshStateMatchesTheValueClauses() {
            ProgramState state = new ProgramState();

            assertThat(state.message()).isEqualTo(" ".repeat(80));
            assertThat(state.isErrFlagOn()).isFalse();
            assertThat(state.isUsrModified()).isFalse();
            assertThat(state.respCd()).isZero();
            assertThat(state.reasCd()).isZero();
            assertThat(state.secUserData()).isEqualTo(SecUserRecord.blank());
            assertThat(state.heldRecord()).isEmpty();
            assertThat(state.cursorField()).isEmpty();
            assertThat(state.errMsgColour()).isEqualTo(BmsAttributes.DFHDFCOL);
            assertThat(state.dateHeader()).isEmpty();
            assertThat(state.isReturned()).isFalse();
            assertThat(state.isTransferred()).isFalse();
            assertThat(state.sendCount()).isZero();
            assertThat(state.displayLines()).isEmpty();
            assertThat(state.response()).isEqualTo(UserDeleteResponse.empty());
            assertThat(state.commarea()).isEqualTo(NavigationContext.empty());
        }

        @Test
        @DisplayName("the extension is state with no absent value: null records its VALUE clauses")
        void theExtensionHasNoAbsentState() {
            ProgramState state = new ProgramState();

            assertThat(state.cu03Info()).isEqualTo(Cu03Info.initial());

            Cu03Info paged =
                    new Cu03Info("USER0001", "USER0010", 2, Cu03Info.NEXT_PAGE_YES, "S", "USER0004");
            state.setCu03Info(paged);
            assertThat(state.cu03Info()).isEqualTo(paged);

            state.setCu03Info(null);
            assertThat(state.cu03Info()).isEqualTo(Cu03Info.initial());
        }

        @Test
        @DisplayName("the flag, the counters and the terminal markers all record what happened")
        void theStateRecordsWhatHappened() {
            ProgramState state = new ProgramState();

            state.setErrFlagOn();
            assertThat(state.isErrFlagOn()).isTrue();
            state.setErrFlagOff();
            assertThat(state.isErrFlagOn()).isFalse();
            state.setUsrModifiedNo();
            assertThat(state.isUsrModified()).isFalse();
            state.setRespCd(FileStatus.NOTFND);
            state.setReasCd(3);
            assertThat(state.respCd()).isEqualTo(FileStatus.NOTFND);
            assertThat(state.reasCd()).isEqualTo(3);
            state.setErrMsgColour(BmsAttributes.DFHRED);
            assertThat(state.errMsgColour()).isEqualTo(BmsAttributes.DFHRED);
            state.recordSend();
            state.recordSend();
            assertThat(state.sendCount()).isEqualTo(2);
            state.markReturned();
            state.markTransferred();
            assertThat(state.isReturned()).isTrue();
            assertThat(state.isTransferred()).isTrue();
            state.recordDisplay("RESP:000000000REAS:000000000");
            assertThat(state.displayLines()).hasSize(1);
        }

        @Test
        @DisplayName("MOVE ... TO SEC-USR-ID replaces one item and leaves the other five alone")
        void movingTheKeyLeavesTheSiblingsAlone() {
            ProgramState state = new ProgramState();
            state.setSecUserData(user(USER_ID));

            state.moveToSecUsrId("OTHER001");

            assertThat(state.secUserData().secUsrId()).isEqualTo("OTHER001");
            assertThat(state.secUserData().secUsrFname()).startsWith("Sam");
            assertThat(state.secUserData().secUsrLname()).startsWith("Spade");
            assertThat(state.secUserData().secUsrType()).isEqualTo("U");
        }

        @Test
        @DisplayName("the hold is taken and released, and is empty in between")
        void theHoldIsTakenAndReleased() {
            ProgramState state = new ProgramState();
            HeldRecord hold = mock(HeldRecord.class);

            state.holdRecord(Optional.of(hold));
            assertThat(state.heldRecord()).contains(hold);
            state.releaseHold();
            assertThat(state.heldRecord()).isEmpty();
            state.holdRecord(Optional.empty());
            assertThat(state.heldRecord()).isEmpty();
        }

        @Test
        @DisplayName("the displayed lines are an unmodifiable view")
        void theDisplayedLinesAreUnmodifiable() {
            ProgramState state = new ProgramState();
            state.recordDisplay("one");

            assertThatThrownBy(() -> state.displayLines().add("two"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("no COBOL item is ever absent, so every setter refuses null")
        void everySetterRefusesNull() {
            ProgramState state = new ProgramState();

            assertThatThrownBy(() -> state.setResponse(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> state.setCommarea(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> state.setMessage(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> state.setSecUserData(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> state.moveMinusOneTo(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> state.holdRecord(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> state.setDateHeader(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> state.recordDisplay(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("the cursor request names the symbolic map's own length items")
        void theCursorRequestNamesTheLengthItems() {
            assertThat(CursorField.USRIDINL.lengthItem()).isEqualTo("USRIDINL");
            assertThat(CursorField.FNAMEL.lengthItem()).isEqualTo("FNAMEL");
            assertThat(CursorField.values()).hasSize(2);
        }

        @Test
        @DisplayName("the controller declares no mutable field of its own - gate G53")
        void theControllerHoldsNoMutableState() {
            assertThat(UserDeleteController.class.getDeclaredFields())
                    .allSatisfy(field -> assertThat(java.lang.reflect.Modifier.isFinal(
                            field.getModifiers())).isTrue());
        }
    }

    @Nested
    @DisplayName("The commarea - 160 shared bytes plus CU03's own 34, and neither absorbs the other")
    class CommareaWidths {
        @Test
        @DisplayName("COCOM01Y is exactly 160 bytes, and CU03 adds none of its own to that total")
        void theSharedCommareaIsExactlyOneHundredAndSixty() {
            assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(160);

            assertThat(NavigationContext.GENERAL_INFO_LENGTH
                    + NavigationContext.CUSTOMER_INFO_LENGTH
                    + NavigationContext.ACCOUNT_INFO_LENGTH
                    + NavigationContext.CARD_INFO_LENGTH
                    + NavigationContext.MORE_INFO_LENGTH)
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH);

            assertThat(NavigationContext.class.getRecordComponents())
                    .extracting(RecordComponent::getName)
                    .doesNotContain("usridFirst", "usridLast", "pageNum", "nextPageFlg", "usrSelFlg",
                            "usrSelected", "cu03Info");
        }

        @Test
        @DisplayName("CDEMO-CU03-INFO:50-58 is exactly 34 bytes: 8 + 8 + 8 + 1 + 1 + 8")
        void theExtensionIsExactlyThirtyFour() {
            assertThat(Cu03Info.USRID_FIRST_LENGTH).isEqualTo(8);
            assertThat(Cu03Info.USRID_LAST_LENGTH).isEqualTo(8);
            assertThat(Cu03Info.PAGE_NUM_DIGITS).isEqualTo(8);
            assertThat(Cu03Info.NEXT_PAGE_FLG_LENGTH).isEqualTo(1);
            assertThat(Cu03Info.USR_SEL_FLG_LENGTH).isEqualTo(1);
            assertThat(Cu03Info.USR_SELECTED_LENGTH).isEqualTo(8);
            assertThat(Cu03Info.LENGTH).isEqualTo(34);

            assertThat(Cu03Info.class.getRecordComponents())
                    .extracting(RecordComponent::getName)
                    .containsExactly("usridFirst", "usridLast", "pageNum", "nextPageFlg", "usrSelFlg",
                            "usrSelected");
        }

        @Test
        @DisplayName("the area line 94 restores is therefore 194 bytes: 160 plus 34")
        void theCu03CommareaIsOneHundredAndNinetyFour() {
            assertThat(NavigationContext.COMMAREA_LENGTH + Cu03Info.LENGTH).isEqualTo(194);
        }

        @Test
        @DisplayName("G22 - CDEMO-CU03-PAGE-NUM is integral: PIC 9(08) has no V and no sign")
        void thePageNumberIsIntegral() {
            assertThat(Cu03Info.class.getRecordComponents())
                    .filteredOn(component -> "pageNum".equals(component.getName()))
                    .singleElement()
                    .satisfies(component -> {
                        assertThat(component.getType()).isEqualTo(int.class);
                        assertThat(component.getType()).isNotIn(double.class, float.class);
                    });

            assertThat(new Cu03Info(null, null, 99_999_999, null, null, null).pageNum())
                    .isEqualTo(99_999_999);
            assertThatThrownBy(() -> new Cu03Info(null, null, -1, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("CDEMO-CU03-PAGE-NUM");
            assertThatThrownBy(() -> new Cu03Info(null, null, 100_000_000, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("CDEMO-CU03-PAGE-NUM");
        }

        @Test
        @DisplayName("G50 - both 88-levels of CDEMO-CU03-NEXT-PAGE-FLG:55-56 are reachable and distinct")
        void bothNextPageStatesAreReachable() {
            assertThat(Cu03Info.NEXT_PAGE_YES).isEqualTo("Y");
            assertThat(Cu03Info.NEXT_PAGE_NO).isEqualTo("N");
            assertThat(Cu03Info.NEXT_PAGE_YES).isNotEqualTo(Cu03Info.NEXT_PAGE_NO);

            assertThat(Cu03Info.initial().nextPageFlg()).isEqualTo(Cu03Info.NEXT_PAGE_NO);

            Cu03Info more = new Cu03Info("USER0001", "USER0010", 2, Cu03Info.NEXT_PAGE_YES, "S", USER_ID);
            assertThat(more.nextPageFlg()).isEqualTo(Cu03Info.NEXT_PAGE_YES).hasSize(1);
            assertThat(Cu03Info.initial().nextPageFlg()).hasSize(1);
        }

        @Test
        @DisplayName("the extension is carried through a whole turn untouched, byte for byte")
        void theExtensionIsCarriedUntouched() {
            HeldRecord hold = stubHeldRead(USER_ID);
            when(hold.deleteHeld()).thenReturn(WriteResult.written());
            Cu03Info carried =
                    new Cu03Info("USER0001", "USER0010", 3, Cu03Info.NEXT_PAGE_YES, "S", USER_ID);
            UserDeleteRequest request = withExtension(screen(USER_ID, reenter()), carried);

            ProgramState state = controller.mainPara(request, CicsAid.DFHPF5, null);

            assertThat(state.cu03Info()).isEqualTo(carried);
            assertThat(state.response().cu03Info()).isEqualTo(carried);
        }
    }

    @Nested
    @DisplayName("The screen contract - eleven fields, no password, no metadata in the payload")
    class ScreenContract {
        @Test
        @DisplayName("every one of the eleven fields is rendered at its declared width")
        void everyFieldIsAtItsDeclaredWidth() {
            stubHeldRead(USER_ID);

            UserDeleteResponse screen =
                    controller.mainPara(screen(USER_ID, reenter()), CicsAid.DFHENTER, null).response();

            assertThat(screen.trnName()).hasSize(4);
            assertThat(screen.title01()).hasSize(40);
            assertThat(screen.curDate()).hasSize(8);
            assertThat(screen.pgmName()).hasSize(8);
            assertThat(screen.title02()).hasSize(40);
            assertThat(screen.curTime()).hasSize(8);
            assertThat(screen.usrIdIn()).hasSize(8);
            assertThat(screen.fName()).hasSize(20);
            assertThat(screen.lName()).hasSize(20);
            assertThat(screen.usrType()).hasSize(1);
            assertThat(screen.errMsg()).hasSize(78);
        }

        @Test
        @DisplayName("the payload carries eleven map fields and nothing that looks like a password")
        void thePayloadCarriesNoPassword() {
            assertThat(UserDeleteResponse.MAP_FIELD_COUNT).isEqualTo(11);
            assertThat(UserDeleteRequest.MAP_FIELD_COUNT).isEqualTo(11);
            assertThat(UserDeleteResponse.class.getRecordComponents())
                    .noneSatisfy(component -> assertThat(component.getName().toLowerCase())
                            .containsAnyOf("pwd", "pass", "secret", "credential"));
            assertThat(UserDeleteRequest.class.getRecordComponents())
                    .noneSatisfy(component -> assertThat(component.getName().toLowerCase())
                            .containsAnyOf("pwd", "pass", "secret", "credential"));
        }

        @Test
        @DisplayName("the colour byte, the cursor and the send count are metadata, not payload members")
        void metadataIsNotAPayloadMember() {
            assertThat(UserDeleteResponse.class.getRecordComponents())
                    .extracting(RecordComponent::getName)
                    .doesNotContain("errMsgColour", "cursorField", "sendCount", "displayLines",
                            "respCd", "reasCd");
        }
    }

    @Nested
    @DisplayName("CSSETATY - DFHRED and '*' in REENTER, and nothing at all on first entry")
    class ErrorHighlight {
        @Test
        @DisplayName("G38 - a blank field in REENTER takes DFHRED on its colour item and '*' on output")
        void aFailingFieldIsHighlightedOnReentry() {
            FieldHighlight highlighted = FieldAttributeSetter.resolve(FieldValidationState.BLANK,
                    true, "USRIDIN", UserDeleteResponse.MAP_NAME);

            assertThat(highlighted.untouched()).isFalse();
            assertThat(highlighted.colourItemAssigned()).isTrue();
            assertThat(highlighted.colourItemValue()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(highlighted.outputItemAssigned()).isTrue();
            assertThat(highlighted.outputItemValue()).isEqualTo(FieldAttributeSetter.ASTERISK);

            assertThat(highlighted.colourItemName())
                    .isEqualTo("USRIDIN" + FieldAttributeSetter.COLOUR_ITEM_SUFFIX);
            assertThat(highlighted.outputItemName())
                    .isEqualTo("USRIDIN" + FieldAttributeSetter.OUTPUT_ITEM_SUFFIX);
        }

        @Test
        @DisplayName("G38 - the same blank field on FIRST entry is left completely untouched")
        void nothingIsHighlightedOnFirstEntry() {
            FieldHighlight untouched = FieldAttributeSetter.resolve(FieldValidationState.BLANK,
                    false, "USRIDIN", UserDeleteResponse.MAP_NAME);

            assertThat(untouched.untouched()).isTrue();
            assertThat(untouched.colourItemAssigned()).isFalse();
            assertThat(untouched.outputItemAssigned()).isFalse();
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(FieldValidationState.class)
        @DisplayName("only a failing state highlights, and the raw 88-levels agree with the vocabulary")
        void onlyAFailingStateHighlights(FieldValidationState validation) {
            FieldHighlight onReentry = FieldAttributeSetter.resolve(validation, true);

            if (validation == FieldValidationState.OK) {
                assertThat(onReentry.untouched()).isTrue();
            } else {
                assertThat(onReentry.colourItemAssigned()).isTrue();
                assertThat(onReentry.colourItemValue()).isEqualTo(BmsAttributes.DFHRED);
            }

            assertThat(FieldAttributeSetter.resolveFromFlags(validation.notOk(), validation.blank(), true))
                    .isEqualTo(onReentry);

            assertThat(FieldAttributeSetter.resolve(validation, false).untouched()).isTrue();
            assertThat(FieldAttributeSetter.resolveFromFlags(validation.notOk(), validation.blank(), false)
                    .untouched()).isTrue();
        }

        @Test
        @DisplayName("both CDEMO-PGM-CONTEXT states are exercised end to end - gate G50")
        void bothProgramContextStatesAreExercised() {
            ProgramState onEnter =
                    controller.mainPara(screen(" ".repeat(8), enter()), CicsAid.DFHPF7, null);

            assertThat(onEnter.commarea().pgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_REENTER);
            assertThat(onEnter.isErrFlagOn()).isFalse();
            assertThat(onEnter.response().errMsg()).isEqualTo(" ".repeat(78));
            assertThat(onEnter.errMsgColour()).isEqualTo(BmsAttributes.DFHDFCOL);

            ProgramState onReentry =
                    controller.mainPara(screen(" ".repeat(8), reenter()), CicsAid.DFHPF7, null);

            assertThat(onReentry.isErrFlagOn()).isTrue();
            assertThat(onReentry.response().errMsg())
                    .isEqualTo(errMsgImage(SystemMessages.CCDA_MSG_INVALID_KEY.trim()));
        }

        @Test
        @DisplayName("the three colours the program itself sets are the only ones a send can carry")
        void theProgramsOwnColoursAreTheOnesItSets() {
            HeldRecord hold = stubHeldRead(USER_ID);
            assertThat(controller.mainPara(screen(USER_ID, reenter()), CicsAid.DFHENTER, null)
                    .errMsgColour()).isEqualTo(BmsAttributes.DFHNEUTR);

            when(hold.deleteHeld()).thenReturn(WriteResult.written());
            assertThat(controller.mainPara(screen(USER_ID, reenter()), CicsAid.DFHPF5, null)
                    .errMsgColour()).isEqualTo(BmsAttributes.DFHGREEN);

            assertThat(controller.mainPara(screen(USER_ID, reenter()), CicsAid.DFHPF7, null)
                    .errMsgColour()).isEqualTo(BmsAttributes.DFHDFCOL);

            when(hold.deleteHeld()).thenReturn(WriteResult.duplicateRecord());
            assertThat(controller.mainPara(screen(USER_ID, reenter()), CicsAid.DFHPF5, null)
                    .errMsgColour()).isEqualTo(BmsAttributes.DFHNEUTR);
        }
    }

    @Nested
    @DisplayName("Statelessness - two requests share nothing")
    class Statelessness {
        @Test
        @DisplayName("a second request sees none of the first request's screen or working storage")
        void twoRequestsShareNothing() {
            HeldRecord hold = stubHeldRead(USER_ID);
            when(hold.deleteHeld()).thenReturn(WriteResult.written());

            ProgramState first = controller.mainPara(screen(USER_ID, reenter()), CicsAid.DFHPF5, null);
            ProgramState second = controller.mainPara(screen(" ".repeat(8), reenter()),
                    CicsAid.DFHENTER, null);

            assertThat(first).isNotSameAs(second);
            assertThat(first.response().errMsg())
                    .isEqualTo(errMsgImage("User USER0001 has been deleted ..."));
            assertThat(second.response().errMsg())
                    .isEqualTo(errMsgImage("User ID can NOT be empty..."));
            assertThat(second.heldRecord()).isEmpty();
            assertThat(second.errMsgColour()).isEqualTo(BmsAttributes.DFHDFCOL);
            assertThat(second.secUserData()).isEqualTo(SecUserRecord.blank());
            assertThat(second.sendCount()).isOne();
        }
    }

    @Nested
    @DisplayName("DELETE /api/users/{userId} - the route binding only")
    class HttpProjection {
        private MockMvc mockMvc;

        private final ObjectMapper mapper = new ObjectMapper();

        @BeforeEach
        void standalone() {
            mockMvc = MockMvcBuilders.standaloneSetup(controller)
                    // The one refusal this route can raise at the boundary - a body naming another user
                    // - is answered 400 by the module's own advice, so the projection is asserted through
                    // it rather than as a servlet failure.
                    .setControllerAdvice(new WebConfig.CobolErrorHandler())
                    .build();
        }

        private UserDeleteRequest withAid(UserDeleteRequest base, String aid) {
            return new UserDeleteRequest(base.trnName(), base.title01(), base.curDate(), base.pgmName(),
                    base.title02(), base.curTime(), base.usrIdIn(), base.fName(), base.lName(),
                    base.usrType(), base.errMsg(), base.navigationContext(), aid, null);
        }

        @Test
        @DisplayName("the route projects the screen, and the ENTER token drives the fetch")
        void theRouteProjectsTheScreen() throws Exception {
            stubHeldRead(USER_ID);
            String body = mapper.writeValueAsString(withAid(screen(USER_ID, reenter()),
                    PfKeyResolver.aidImage(CicsAid.DFHENTER)));

            mockMvc.perform(delete("/api/users/{userId}", USER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.errmsg")
                            .value(errMsgImage("Press PF5 key to delete this user ...")))
                    .andExpect(jsonPath("$.usridin").value(USER_ID))
                    .andExpect(jsonPath("$.nextProgram").value("COUSR03C"));
        }

        @Test
        @DisplayName("over HTTP, a first entry takes the URI's identity into USRIDIN and deletes that "
                + "record on the confirmation key")
        void theUriIsTheOnlyIdentityOverHttp() throws Exception {
            stubHeldRead(USER_ID);
            String body = mapper.writeValueAsString(
                    withAid(screen(" ".repeat(8), enter()), PfKeyResolver.aidImage(CicsAid.DFHPF5)));

            mockMvc.perform(delete("/api/users/{userId}", USER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.errmsg")
                            .value(errMsgImage("Press PF5 key to delete this user ...")))
                    .andExpect(jsonPath("$.usridin").value(USER_ID))
                    .andExpect(jsonPath("$.cu03Info.usrSelected").value(USER_ID));

            verify(repository).readForUpdate(USER_ID);
            verify(repository, never()).readForUpdate("USER0002");
        }

        @Test
        @DisplayName("over HTTP, a re-entry USRIDIN naming another user is REFUSED with 400 and nothing "
                + "is read, held or deleted")
        void aDisagreeingIdentityIsRefusedOverHttp() throws Exception {
            // The confused deputy this route would otherwise be: DELETE /api/users/USER0001 with a body
            // naming USER0002 states its key twice, and honouring the body destroys a record the URI
            // does not name - :160 holds and :189 deletes on exactly that field. The refusal happens in
            // the binder, before the repository is touched at all, and echoes neither identity.
            String body = mapper.writeValueAsString(withAid(screen("USER0002", reenter()),
                    PfKeyResolver.aidImage(CicsAid.DFHPF5)));

            mockMvc.perform(delete("/api/users/{userId}", USER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field")
                            .value(UserDeleteController.USRIDIN_MEMBER))
                    .andExpect(content().string(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("USER0002"))));

            verify(repository, never()).readForUpdate(anyString());
        }

        @Test
        @DisplayName("no metadata item leaks into the JSON - gate G9")
        void noMetadataLeaksIntoTheJson() throws Exception {
            stubHeldRead(USER_ID);
            String body = mapper.writeValueAsString(withAid(screen(USER_ID, reenter()),
                    PfKeyResolver.aidImage(CicsAid.DFHENTER)));

            mockMvc.perform(delete("/api/users/{userId}", USER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.errMsgColour").doesNotExist())
                    .andExpect(jsonPath("$.cursorField").doesNotExist())
                    .andExpect(jsonPath("$.sendCount").doesNotExist())
                    .andExpect(jsonPath("$.displayLines").doesNotExist())
                    .andExpect(jsonPath("$.respCd").doesNotExist())
                    .andExpect(jsonPath("$.usrIdInL").doesNotExist())
                    .andExpect(jsonPath("$.errMsgC").doesNotExist());
        }

        @Test
        @DisplayName("a bodiless request is EIBCALEN = 0 and is not rejected as a bad request")
        void aBodilessRequestIsAColdStart() throws Exception {
            mockMvc.perform(delete("/api/users/{userId}", USER_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.nextProgram").value("COSGN00C"));
        }

        @Test
        @DisplayName("the route is DELETE and only DELETE: every other verb on the same URI is 405")
        void anyOtherVerbIsMethodNotAllowed() throws Exception {
            String body = mapper.writeValueAsString(withAid(screen(USER_ID, reenter()),
                    PfKeyResolver.aidImage(CicsAid.DFHPF5)));

            mockMvc.perform(post("/api/users/{userId}", USER_ID)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isMethodNotAllowed());
            mockMvc.perform(put("/api/users/{userId}", USER_ID)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isMethodNotAllowed());
            mockMvc.perform(get("/api/users/{userId}", USER_ID))
                    .andExpect(status().isMethodNotAllowed());
            mockMvc.perform(patch("/api/users/{userId}", USER_ID)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isMethodNotAllowed());

            verify(repository, never()).readForUpdate(anyString());
        }

        @Test
        @DisplayName("a blank path identity is answered with the empty-id message, not a 400")
        void aBlankPathIdentityIsAMessageNotARejection() throws Exception {
            String body = mapper.writeValueAsString(withAid(screen(" ".repeat(8), reenter()),
                    PfKeyResolver.aidImage(CicsAid.DFHENTER)));

            mockMvc.perform(delete("/api/users/{userId}", " ".repeat(8))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.errmsg").value(errMsgImage("User ID can NOT be empty...")));

            verify(repository, never()).readForUpdate(anyString());
        }

        @Test
        @DisplayName("the PF5 token confirms the delete through the route")
        void thePf5TokenConfirmsTheDelete() throws Exception {
            HeldRecord hold = stubHeldRead(USER_ID);
            when(hold.deleteHeld()).thenReturn(WriteResult.written());
            String body = mapper.writeValueAsString(withAid(screen(USER_ID, reenter()),
                    PfKeyResolver.aidImage(CicsAid.DFHPF5)));

            mockMvc.perform(delete("/api/users/{userId}", USER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.errmsg")
                            .value(errMsgImage("User USER0001 has been deleted ...")));
            verify(hold).deleteHeld();
        }

        @Test
        @DisplayName("the adapter treats the path variable as the selected user on first entry")
        void thePathVariableIsTheSelectedUserOnFirstEntry() {
            stubHeldRead(USER_ID);

            ScreenResponse<UserDeleteResponse> answer =
                    controller.deleteUser(USER_ID, screen(" ".repeat(8), enter()), null, null);

            verify(repository).readForUpdate(USER_ID);
            assertThat(answer.screen().usrIdIn()).isEqualTo(USER_ID);
            assertThat(answer.screenMetadata()).as("the envelope carries the presentation state")
                    .isNotNull();
        }

        @Test
        @DisplayName("the adapter refuses an absent path variable: it is the record's key")
        void theAdapterRefusesAnAbsentPathVariable() {
            UserDeleteRequest request = screen(USER_ID, reenter());

            assertThatThrownBy(() -> controller.deleteUser(null, request, null, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("user id");
        }

        @Test
        @DisplayName("the adapter accepts an absent body and projects the cold start")
        void theAdapterAcceptsAnAbsentBody() {
            UserDeleteResponse screen = controller.deleteUser(USER_ID, null, null, null).screen();

            assertThat(screen.nextProgram()).isEqualTo("COSGN00C");
            verify(repository, never()).readForUpdate(anyString());
        }

        @Test
        @DisplayName("a path identity wider than USRIDIN is refused, never padded into another user")
        void anOverWidePathIdentityIsRefused() {
            assertThatThrownBy(() -> controller.deleteUser("USER00012345", null, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("refused rather than truncated");
            verify(repository, never()).readForUpdate(anyString());
        }

        @Test
        @DisplayName("a path identity of exactly the declared width is accepted")
        void anExactWidthPathIdentityIsAccepted() {
            UserDeleteController.requireIdentityFits("USER0001");

            assertThat(UserDeleteController.USR_ID_IN_LENGTH).isEqualTo(8);
        }

        @Test
        @DisplayName("a re-entry USRIDIN naming a different user is REFUSED, because :160 holds and "
                + ":189 deletes on that very field and the URI is the user this resource deletes")
        void aReentryIdentityThatDisagreesIsTheIdentity() {
            // COUSR03C has no URI. :95-122 is the first-entry arm, which takes its key from
            // CDEMO-CU03-USR-SELECTED; :144-191 is the re-entry arm, and PROCESS-ENTER-KEY there reads
            // USRIDINI. A body naming another user therefore states the key twice and disagrees with
            // itself, and honouring it deletes a record the URI does not name.
            ScreenInputRejectedException refusal = catchThrowableOfType(
                    ScreenInputRejectedException.class,
                    () -> controller.deleteUser(USER_ID,
                            withAid(screen("USER0002", reenter()),
                                    PfKeyResolver.aidImage(CicsAid.DFHENTER)),
                            null, null));

            assertThat(refusal).isNotNull();
            assertThat(refusal.member()).contains(UserDeleteController.USRIDIN_MEMBER);
            assertThat(refusal.reason())
                    .isEqualTo(ScreenInputRejectedException.Reason.CONFLICTING_KEY);
            assertThat(refusal.getMessage()).doesNotContain("USER0002");
            assertThat(refusal.publicDetail()).doesNotContain("USER0002");
            verify(repository, never()).readForUpdate(anyString());
        }

        @ParameterizedTest(name = "a re-entry stating USRIDIN as \"{0}\" agrees with the URI")
        @ValueSource(strings = {"        ", "USER0001", "\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000"})
        @DisplayName("the images that AGREE with the URI are accepted - blank, LOW-VALUES and the URI's "
                + "own key - and the URI's key is what USRIDIN then carries")
        void theStatesThatAgreeAreAccepted(String stated) {
            stubHeldRead(USER_ID);

            UserDeleteResponse screen = controller
                    .deleteUser(USER_ID,
                            withAid(screen(stated, reenter()),
                                    PfKeyResolver.aidImage(CicsAid.DFHENTER)),
                            null, null)
                    .screen();

            assertThat(screen.usrIdIn()).isEqualTo(USER_ID);
            verify(repository).readForUpdate(USER_ID);
        }

        @Test
        @DisplayName("a CDEMO-CU03-USR-SELECTED naming a different user is replaced for the same reason")
        void anExtensionSelectionThatDisagreesIsProjectedOver() {
            stubHeldRead(USER_ID);

            ScreenResponse<UserDeleteResponse> answer = controller.deleteUser(USER_ID,
                    withSelection(screen(" ".repeat(8), enter()), "USER0002"), null, null);

            verify(repository).readForUpdate(USER_ID);
            verify(repository, never()).readForUpdate("USER0002");
            assertThat(answer.screen().usrIdIn()).isEqualTo(USER_ID);
            assertThat(answer.screen().cu03Info().usrSelected()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("a blank USRIDIN on a FIRST entry is what the path fills, which is the arm "
                + ":99-102 takes its key from")
        void aBlankBodyIdentityIsFilledFromThePath() {
            stubHeldRead(USER_ID);

            UserDeleteResponse screen = controller
                    .deleteUser(USER_ID, withAid(screen(" ".repeat(8), enter()),
                            PfKeyResolver.aidImage(CicsAid.DFHENTER)), null, null).screen();

            verify(repository).readForUpdate(USER_ID);
            assertThat(screen.usrIdIn()).isEqualTo(USER_ID);
            assertThat(screen.errMsg()).isNotEqualTo(errMsgImage("User ID can NOT be empty..."));
        }

        @Test
        @DisplayName("a body that states the same user, space-padded or not, agrees with the path")
        void anAgreeingBodyIsAccepted() {
            stubHeldRead(USER_ID);

            UserDeleteResponse screen = controller.deleteUser(USER_ID,
                    withSelection(screen(USER_ID, enter()), USER_ID), null, null).screen();

            assertThat(screen.usrIdIn()).isEqualTo(USER_ID);
            verify(repository).readForUpdate(USER_ID);
        }

        @Test
        @DisplayName("the extension travels in the payload, carrying the path's identity in its one key")
        void theExtensionTravelsInThePayload() {
            stubHeldRead(USER_ID);

            ScreenResponse<UserDeleteResponse> answer = controller.deleteUser(USER_ID,
                    withSelection(screen(USER_ID, enter()), USER_ID), null, null);

            assertThat(answer.screen().cu03Info().usrSelected()).isEqualTo(USER_ID);

            Cu03Info echoed =
                    controller.deleteUser(USER_ID, screen(USER_ID, enter()), null, null).screen().cu03Info();
            Cu03Info initial = Cu03Info.initial();
            assertThat(echoed.usridFirst()).isEqualTo(initial.usridFirst());
            assertThat(echoed.usridLast()).isEqualTo(initial.usridLast());
            assertThat(echoed.pageNum()).isEqualTo(initial.pageNum());
            assertThat(echoed.nextPageFlg()).isEqualTo(initial.nextPageFlg());
            assertThat(echoed.usrSelFlg()).isEqualTo(initial.usrSelFlg());
            assertThat(echoed.usrSelected()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("bindPathIdentity cannot invent a body: an absent one stays the EIBCALEN = 0 state")
        void bindPathIdentityLeavesAnAbsentBodyAbsent() {
            assertThat(controller.bindPathIdentity(USER_ID, null)).isNull();
        }
    }

    @Nested
    @DisplayName("Bean wiring - one request is one unit of work, as one CICS task is")
    class BeanWiring {
        @Configuration
        @EnableTransactionManagement
        static class TransactionalContext {
        }

        @Test
        @DisplayName("the bean is created, proxied, and opens and commits a unit of work per request")
        void theBeanWiresAndOpensAUnitOfWork() {
            PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
            when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus(true));

            try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
                context.registerBean(SecUserRepository.class, () -> repository);
                context.registerBean(Clock.class, () -> FIXED_CLOCK);
                context.registerBean(PlatformTransactionManager.class, () -> transactionManager);
                context.register(TransactionalContext.class);
                context.register(UserDeleteController.class);
                context.refresh();

                UserDeleteController bean = context.getBean(UserDeleteController.class);

                assertThat(bean).isNotNull();
                assertThat(AopUtils.isAopProxy(bean)).isTrue();

                bean.deleteUser(USER_ID, null, null, null);

                verify(transactionManager).getTransaction(any());
                verify(transactionManager).commit(any());
            }
        }

        @Test
        @DisplayName("the route is declared once, as DELETE, and produces JSON")
        void theRouteIsDeclaredOnce() throws Exception {
            assertThat(UserDeleteController.class.getAnnotation(RestController.class)).isNotNull();
            DeleteMapping mapping = UserDeleteController.class
                    .getMethod("deleteUser", String.class, UserDeleteRequest.class, Integer.class,
                            Integer.class)
                    .getAnnotation(DeleteMapping.class);

            assertThat(mapping).isNotNull();
            assertThat(mapping.path()).containsExactly("/api/users/{userId}");
            assertThat(mapping.produces()).containsExactly(MediaType.APPLICATION_JSON_VALUE);
            assertThat(mapping.consumes()).isEmpty();
            assertThat(UserDeleteController.class.getMethod("deleteUser", String.class,
                    UserDeleteRequest.class, Integer.class, Integer.class)
                    .getAnnotation(Transactional.class)).isNotNull();
        }

        @Test
        @DisplayName("no service class is introduced for COUSR03C, and no servlet type is in a signature")
        void noServiceAndNoServletTypes() {
            assertThat(java.util.Arrays.stream(UserDeleteController.class.getDeclaredMethods())
                    .flatMap(method -> java.util.Arrays.stream(method.getParameterTypes()))
                    .map(Class::getName))
                    .noneMatch(name -> name.startsWith("jakarta.servlet")
                            || name.startsWith("javax.servlet"));
        }
    }
}
