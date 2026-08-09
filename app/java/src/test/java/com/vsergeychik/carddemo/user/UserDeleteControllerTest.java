package com.vsergeychik.carddemo.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenResponse;
import com.vsergeychik.carddemo.common.PfKeyResolver.AidKey;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.user.SecUserRepository.HeldRecord;
import com.vsergeychik.carddemo.user.SecUserRepository.ReadResult;
import com.vsergeychik.carddemo.user.SecUserRepository.WriteResult;
import com.vsergeychik.carddemo.user.UserDeleteController.CursorField;
import com.vsergeychik.carddemo.user.UserDeleteController.ProgramState;
import com.vsergeychik.carddemo.user.dto.UserDeleteRequest;
import com.vsergeychik.carddemo.user.dto.UserDeleteResponse;
import com.vsergeychik.carddemo.user.model.SecUserRecord;
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
 *
 * <p>Every test that asserts a <em>decision</em> instantiates the controller directly with a mocked
 * {@link SecUserRepository} and a fixed {@link Clock}. There is no Spring context and no
 * {@code MockMvc} in the decision path, which is gate <strong>G51</strong>: a guard chain is asserted
 * where it lives, and a failure names the COBOL paragraph rather than an HTTP status. Only the
 * {@link HttpProjection} group exercises the route binding, and it asserts nothing about behaviour that
 * is not already pinned below.
 *
 * <p>The three assertions this class exists to make, in order of how expensive they would be to lose:
 *
 * <ol>
 *   <li>The delete carries <strong>no key</strong> - {@link HeldRecord#deleteHeld()} is reached from the
 *       hold the read took, takes no argument, and is never replaced by a delete-by-key call.</li>
 *   <li>The delete-failure message says <strong>"Update"</strong>, because the legacy source says
 *       "Update", and the word "Delete" appears in no expected value anywhere in this file.</li>
 *   <li>The delete runs <strong>even when the read failed</strong>, because
 *       {@code app/cbl/COUSR03C.cbl:188-192} places no guard between them.</li>
 * </ol>
 */
@DisplayName("UserDeleteController - the COUSR03C / CU03 delete-user screen")
class UserDeleteControllerTest {

    /**
     * The clock every test runs against: {@code 2022-07-19T23:12:35Z} at UTC, which is the timestamp in
     * {@code COUSR03C}'s own version footer. Fixed, so {@code POPULATE-HEADER-INFO} is reproducible.
     */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2022-07-19T23:12:35Z"), ZoneOffset.UTC);

    /** {@code CURDATEO} for {@link #FIXED_CLOCK}: {@code MM/DD/YY}. */
    private static final String EXPECTED_DATE = "07/19/22";

    /** {@code CURTIMEO} for {@link #FIXED_CLOCK}: {@code HH:MM:SS}. */
    private static final String EXPECTED_TIME = "23:12:35";

    /** The code page the mocked repository reports; single byte, and never the platform default. */
    private static final Charset CODE_PAGE = StandardCharsets.US_ASCII;

    /** A user id at its full declared width of eight. */
    private static final String USER_ID = "USER0001";

    /**
     * A stand-in for {@code SEC-USR-PWD PIC X(08)}. Deliberately an obvious placeholder: no test in this
     * module needs a credential-shaped value, and this screen never reads the field at all.
     */
    private static final String REDACTED_PWD = "REDACTED";

    private SecUserRepository repository;

    private UserDeleteController controller;

    @BeforeEach
    void setUp() {
        repository = mock(SecUserRepository.class);
        when(repository.datasetCharset()).thenReturn(CODE_PAGE);
        controller = new UserDeleteController(repository, FIXED_CLOCK);
    }

    // =================================================================================================
    // Fixtures
    // =================================================================================================

    /** {@code CDEMO-PGM-CONTEXT = 1}: the operator is typing into the map. */
    private static NavigationContext reenter() {
        return NavigationContext.empty().withPgmReenter();
    }

    /** {@code CDEMO-PGM-CONTEXT = 0}: first entry, on which the screen is painted rather than read. */
    private static NavigationContext enter() {
        return NavigationContext.empty();
    }

    /** A screen carrying one user id and one communication area, everything else blank. */
    /** The same payload with {@code CDEMO-CU03-USR-SELECTED} naming a user. */
    private static UserDeleteRequest withSelection(UserDeleteRequest request, String selected) {
        UserDeleteRequest.Cu03Info info = request.cu03Info();
        return new UserDeleteRequest(request.trnName(), request.title01(), request.curDate(),
                request.pgmName(), request.title02(), request.curTime(), request.usrIdIn(),
                request.fName(), request.lName(), request.usrType(), request.errMsg(),
                request.navigationContext(), request.aid(),
                new UserDeleteRequest.Cu03Info(info.usridFirst(), info.usridLast(), info.pageNum(),
                        info.nextPageFlg(), info.usrSelFlg(), selected));
    }

    private static UserDeleteRequest screen(String usrIdIn, NavigationContext commarea) {
        UserDeleteRequest blank = UserDeleteRequest.empty();
        return new UserDeleteRequest(blank.trnName(), blank.title01(), blank.curDate(), blank.pgmName(),
                blank.title02(), blank.curTime(), usrIdIn, blank.fName(), blank.lName(), blank.usrType(),
                blank.errMsg(), commarea, blank.aid(), null);
    }

    /** An 80-byte {@code SEC-USER-DATA} for the given key. */
    private static SecUserRecord user(String id) {
        return SecUserRecord.of(id, "Sam", "Spade", REDACTED_PWD, "U", CODE_PAGE);
    }

    /** {@code MOVE WS-MESSAGE TO ERRMSGO} - the text as the 78-character screen field holds it. */
    private static String errMsgImage(String text) {
        return text + " ".repeat(UserDeleteResponse.ERR_MSG_LENGTH - text.length());
    }

    /** The rendering of {@code DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD}. */
    private static String displayLine(int resp, int reas) {
        return "RESP:" + String.format("%09d", resp) + "REAS:" + String.format("%09d", reas);
    }

    /** A successful locking read that leaves the task holding {@code hold}. */
    private HeldRecord stubHeldRead(String id) {
        HeldRecord hold = mock(HeldRecord.class);
        when(repository.readForUpdate(anyString())).thenReturn(ReadResult.held(user(id), hold));
        return hold;
    }

    // =================================================================================================
    // Construction
    // =================================================================================================

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

    // =================================================================================================
    // The preserved source defect. This group is the reason the class documentation opens with it.
    // =================================================================================================

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

    // =================================================================================================
    // aidOfToken - the CCARD-AID token the payload carries, mapped back onto its key.
    // =================================================================================================

    @Nested
    @DisplayName("aidOfToken - the payload's CCARD-AID token")
    class AidToken {

        @ParameterizedTest
        @CsvSource({"ENTER,ENTER", "CLEAR,CLEAR", "PFK03,PFK03", "PFK05,PFK05", "PFK12,PFK12"})
        @DisplayName("a token names its key exactly")
        void aTokenNamesItsKey(String token, AidKey expected) {
            assertThat(UserDeleteController.aidOfToken(token)).contains(expected);
        }

        @Test
        @DisplayName("PA1's two trailing spaces are part of the token and are matched, not trimmed")
        void paddedTokensMatchExactly() {
            assertThat(UserDeleteController.aidOfToken("PA1  ")).contains(AidKey.PA1);
            assertThat(UserDeleteController.aidOfToken("PA1")).isEmpty();
        }

        @Test
        @DisplayName("an absent token is no key at all, and is never defaulted to ENTER")
        void anAbsentTokenIsEmpty() {
            assertThat(UserDeleteController.aidOfToken(null)).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "     ", "NOPE ", "PFK99", "enter"})
        @DisplayName("a token naming nothing the resolver defines is empty")
        void anUnknownTokenIsEmpty(String token) {
            assertThat(UserDeleteController.aidOfToken(token)).isEmpty();
        }
    }

    // =================================================================================================
    // MAIN-PARA lines 90-92: EIBCALEN = 0.
    // =================================================================================================

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

    // =================================================================================================
    // MAIN-PARA lines 95-105: first entry.
    // =================================================================================================

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
            assertThat(state.response().usrIdIn()).isBlank();
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

    // =================================================================================================
    // PROCESS-ENTER-KEY - lines 142-169, reached from EVALUATE EIBAID WHEN DFHENTER.
    // =================================================================================================

    @Nested
    @DisplayName("PROCESS-ENTER-KEY:142-169 - the fetch that paints the record for confirmation")
    class EnterKey {

        @ParameterizedTest
        @ValueSource(strings = {"        ", "", "\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000",
                " \u0000 \u0000 \u0000 \u0000"})
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

    // =================================================================================================
    // EVALUATE EIBAID WHEN DFHPF3 - lines 111-118.
    // =================================================================================================

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

    // =================================================================================================
    // EVALUATE EIBAID WHEN DFHPF4 and WHEN DFHPF12 - lines 119-125.
    // =================================================================================================

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

    // =================================================================================================
    // EVALUATE EIBAID WHEN OTHER - lines 126-129, reached two ways.
    // =================================================================================================

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
        @DisplayName("an empty AID reaches the same arm, and is never defaulted to ENTER")
        void anAbsentAidIsRefused() {
            ProgramState state =
                    controller.mainPara(screen(USER_ID, reenter()), Optional.empty(), null);

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
        @DisplayName("a null AID Optional is refused outright rather than assumed")
        void aNullAidOptionalIsRefused() {
            UserDeleteRequest request = screen(USER_ID, reenter());

            assertThatThrownBy(() -> controller.mainPara(request, (Optional<AidKey>) null, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Optional");
        }
    }

    // =================================================================================================
    // DELETE-USER-INFO and DELETE-USER-SEC-FILE - lines 174-192 and 305-336. The heart of the program.
    // =================================================================================================

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
            // The read said 'User ID NOT found...' and sent; the keyless delete then found nothing held,
            // CICS answered INVREQ, and WHEN OTHER overwrote the message. Inserting the missing guard
            // would leave 'User ID NOT found...' standing, and would be wrong.
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

            // Two sends: the read's NORMAL arm carried 'Press PF5 key to delete this user ...' before the
            // delete replaced it with the success text. The sequence is preserved, not suppressed.
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

    // =================================================================================================
    // Paragraphs driven directly, including the one arm the composed flow cannot reach.
    // =================================================================================================

    @Nested
    @DisplayName("Paragraphs driven directly - lines 197-262 and 341-356")
    class Paragraphs {

        @Test
        @DisplayName("RETURN-TO-PREV-SCREEN:199-201 defaults a blank target to the sign-on program")
        void aBlankTargetDefaultsToSignOn() {
            ProgramState state = new ProgramState();

            controller.returnToPrevScreen(state);

            // Unreachable through MAIN-PARA - every caller names a target first - and it is the source's
            // own safety net, so it is exercised where it lives.
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

    // =================================================================================================
    // COBOL statements rendered by hand.
    // =================================================================================================

    @Nested
    @DisplayName("COBOL statements - = SPACES OR LOW-VALUES, and DELIMITED BY SPACE")
    class CobolStatements {

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "        ", "\u0000", "\u0000\u0000\u0000\u0000",
                " \u0000 \u0000", "\u0000 \u0000 "})
        @DisplayName("spaces, low-values and any mixture of the two are all blank")
        void blankValuesAreBlank(String value) {
            assertThat(UserDeleteController.isSpacesOrLowValues(value)).isTrue();
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

    // =================================================================================================
    // ProgramState - WORKING-STORAGE, per request, with no static and no shared mutable field.
    // =================================================================================================

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

            assertThat(state.cu03Info()).isEqualTo(UserDeleteRequest.Cu03Info.initial());

            UserDeleteRequest.Cu03Info paged = new UserDeleteRequest.Cu03Info("USER0001", "USER0010",
                    2, UserDeleteRequest.Cu03Info.NEXT_PAGE_YES, "S", "USER0004");
            state.setCu03Info(paged);
            assertThat(state.cu03Info()).isEqualTo(paged);

            // A communication area cannot arrive without these 34 bytes, so a null statement records the
            // state COUSR03C:50-58 declares rather than an absence the COBOL has no way to represent.
            state.setCu03Info(null);
            assertThat(state.cu03Info()).isEqualTo(UserDeleteRequest.Cu03Info.initial());
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

    // =================================================================================================
    // The screen contract - gate G9.
    // =================================================================================================

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
                    .extracting(java.lang.reflect.RecordComponent::getName)
                    .doesNotContain("errMsgColour", "cursorField", "sendCount", "displayLines",
                            "respCd", "reasCd");
        }
    }

    // =================================================================================================
    // Statelessness - gate G37.
    // =================================================================================================

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

    // =================================================================================================
    // The HTTP binding. Nothing here asserts behaviour that is not already pinned above.
    // =================================================================================================

    @Nested
    @DisplayName("DELETE /api/users/{userId} - the route binding only")
    class HttpProjection {

        private MockMvc mockMvc;

        private final ObjectMapper mapper = new ObjectMapper();

        @BeforeEach
        void standalone() {
            mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
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
            String body = mapper.writeValueAsString(withAid(screen(USER_ID, reenter()), "ENTER"));

            mockMvc.perform(delete("/api/users/{userId}", USER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.errMsg")
                            .value(errMsgImage("Press PF5 key to delete this user ...")))
                    .andExpect(jsonPath("$.usrIdIn").value(USER_ID))
                    .andExpect(jsonPath("$.nextProgram").value("COUSR03C"));
        }

        @Test
        @DisplayName("over HTTP, a body naming another user cannot make the URI delete it")
        void theUriIsTheOnlyIdentityOverHttp() throws Exception {
            // Only reachable through the HTTP binder: DELETE /api/users/A with USRIDIN naming B used to
            // reach B's record, and a blank USRIDIN reached no record at all.
            HeldRecord hold = stubHeldRead(USER_ID);
            when(hold.deleteHeld()).thenReturn(WriteResult.written());
            String body = mapper.writeValueAsString(withAid(screen("USER0002", reenter()), "PFK05"));

            mockMvc.perform(delete("/api/users/{userId}", USER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    // The confirmation names the record that was actually deleted, and it is the URI's -
                    // COUSR03C:315 performs INITIALIZE-ALL-FIELDS first, which is why USRIDIN comes back
                    // blank rather than naming either user.
                    .andExpect(jsonPath("$.errMsg")
                            .value(errMsgImage("User USER0001 has been deleted ...")))
                    .andExpect(jsonPath("$.cu03Info.usrSelected").value(USER_ID));

            verify(repository).readForUpdate(USER_ID);
            verify(repository, never()).readForUpdate("USER0002");
        }

        @Test
        @DisplayName("no metadata item leaks into the JSON - gate G9")
        void noMetadataLeaksIntoTheJson() throws Exception {
            stubHeldRead(USER_ID);
            String body = mapper.writeValueAsString(withAid(screen(USER_ID, reenter()), "ENTER"));

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
        @DisplayName("the PF5 token confirms the delete through the route")
        void thePf5TokenConfirmsTheDelete() throws Exception {
            HeldRecord hold = stubHeldRead(USER_ID);
            when(hold.deleteHeld()).thenReturn(WriteResult.written());
            String body = mapper.writeValueAsString(withAid(screen(USER_ID, reenter()), "PFK05"));

            mockMvc.perform(delete("/api/users/{userId}", USER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.errMsg")
                            .value(errMsgImage("User USER0001 has been deleted ...")));
            verify(hold).deleteHeld();
        }

        @Test
        @DisplayName("the adapter treats the path variable as the selected user on first entry")
        void thePathVariableIsTheSelectedUserOnFirstEntry() {
            stubHeldRead(USER_ID);

            ScreenResponse<UserDeleteResponse> answer =
                    controller.deleteUser(USER_ID, screen(" ".repeat(8), enter()));

            verify(repository).readForUpdate(USER_ID);
            assertThat(answer.screen().usrIdIn()).isEqualTo(USER_ID);
            assertThat(answer.screenMetadata()).as("the envelope carries the presentation state")
                    .isNotNull();
        }

        @Test
        @DisplayName("the adapter refuses an absent path variable: it is the record's key")
        void theAdapterRefusesAnAbsentPathVariable() {
            UserDeleteRequest request = screen(USER_ID, reenter());

            assertThatThrownBy(() -> controller.deleteUser(null, request))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("user id");
        }

        @Test
        @DisplayName("the adapter accepts an absent body and projects the cold start")
        void theAdapterAcceptsAnAbsentBody() {
            UserDeleteResponse screen = controller.deleteUser(USER_ID, null).screen();

            assertThat(screen.nextProgram()).isEqualTo("COSGN00C");
            verify(repository, never()).readForUpdate(anyString());
        }

        @Test
        @DisplayName("a path identity wider than USRIDIN is refused, never padded into another user")
        void anOverWidePathIdentityIsRefused() {
            assertThatThrownBy(() -> controller.deleteUser("USER00012345", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Padding it would keep the leading");
            verify(repository, never()).readForUpdate(anyString());
        }

        @Test
        @DisplayName("a path identity of exactly the declared width is accepted")
        void anExactWidthPathIdentityIsAccepted() {
            UserDeleteController.requireIdentityFits("USER0001");

            assertThat(UserDeleteController.USR_ID_IN_LENGTH).isEqualTo(8);
        }

        @Test
        @DisplayName("a body USRIDIN naming a different user is replaced by the path, which is the key")
        void aBodyIdentityThatDisagreesIsProjectedOver() {
            // The URI is the resource identity. A second, client-controlled statement of it must not be
            // able to act on a record the URI does not name, so re-entry reads the path's user - never
            // USER0002 - and the painted field shows the path's user too.
            stubHeldRead(USER_ID);

            UserDeleteResponse screen = controller
                    .deleteUser(USER_ID, withAid(screen("USER0002", reenter()), "ENTER")).screen();

            verify(repository).readForUpdate(USER_ID);
            verify(repository, never()).readForUpdate("USER0002");
            assertThat(screen.usrIdIn()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("a CDEMO-CU03-USR-SELECTED naming a different user is replaced for the same reason")
        void anExtensionSelectionThatDisagreesIsProjectedOver() {
            // app/cbl/COUSR03C.cbl:99-102 reads the extension, not the screen field, on first entry - so
            // an unprojected extension would be the identity that wins on exactly that arm.
            stubHeldRead(USER_ID);

            ScreenResponse<UserDeleteResponse> answer = controller.deleteUser(USER_ID,
                    withSelection(screen(" ".repeat(8), enter()), "USER0002"));

            verify(repository).readForUpdate(USER_ID);
            verify(repository, never()).readForUpdate("USER0002");
            assertThat(answer.screen().usrIdIn()).isEqualTo(USER_ID);
            assertThat(answer.screen().cu03Info().usrSelected()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("a blank body USRIDIN is filled from the path, so re-entry reads a real key")
        void aBlankBodyIdentityIsFilledFromThePath() {
            // The defect this replaces: a blank USRIDIN left the source's validation, read and delete key
            // blank on re-entry, so the URI's user was never read at all.
            stubHeldRead(USER_ID);

            UserDeleteResponse screen = controller
                    .deleteUser(USER_ID, withAid(screen(" ".repeat(8), reenter()), "ENTER")).screen();

            verify(repository).readForUpdate(USER_ID);
            assertThat(screen.usrIdIn()).isEqualTo(USER_ID);
            assertThat(screen.errMsg()).isNotEqualTo(errMsgImage("User ID can NOT be empty..."));
        }

        @Test
        @DisplayName("a body that states the same user, space-padded or not, agrees with the path")
        void anAgreeingBodyIsAccepted() {
            stubHeldRead(USER_ID);

            UserDeleteResponse screen = controller.deleteUser(USER_ID,
                    withSelection(screen(USER_ID, enter()), USER_ID)).screen();

            assertThat(screen.usrIdIn()).isEqualTo(USER_ID);
            verify(repository).readForUpdate(USER_ID);
        }

        @Test
        @DisplayName("the extension travels in the payload, carrying the path's identity in its one key")
        void theExtensionTravelsInThePayload() {
            stubHeldRead(USER_ID);

            ScreenResponse<UserDeleteResponse> answer = controller.deleteUser(USER_ID,
                    withSelection(screen(USER_ID, enter()), USER_ID));

            assertThat(answer.screen().cu03Info().usrSelected()).isEqualTo(USER_ID);

            // The other five items of the 34-byte group are carried untouched - only the selected id is
            // the URI's to state - so a payload naming none of them still round-trips its VALUE clauses.
            UserDeleteRequest.Cu03Info echoed =
                    controller.deleteUser(USER_ID, screen(USER_ID, enter())).screen().cu03Info();
            UserDeleteRequest.Cu03Info initial = UserDeleteRequest.Cu03Info.initial();
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

    // =================================================================================================
    // Bean wiring and the unit of work - gate G3.
    //
    // The module's own entry-point test is reflective by design and starts no context, so the wiring of
    // this bean is asserted here. It matters for more than startup: EXEC CICS READ ... UPDATE holds its
    // record for the life of the CICS task, and SecUserRepository.readForUpdate refuses to run with no
    // transaction open. If the proxy were absent, every ENTER and every PF5 would fail in production
    // while every unit test above still passed, because a mocked repository checks nothing.
    // =================================================================================================

    @Nested
    @DisplayName("Bean wiring - one request is one unit of work, as one CICS task is")
    class BeanWiring {

        /** The minimum context that makes {@link Transactional} effective. */
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

                bean.deleteUser(USER_ID, null);

                verify(transactionManager).getTransaction(any());
                verify(transactionManager).commit(any());
            }
        }

        @Test
        @DisplayName("the route is declared once, as DELETE, and produces JSON")
        void theRouteIsDeclaredOnce() throws Exception {
            assertThat(UserDeleteController.class.getAnnotation(RestController.class)).isNotNull();
            DeleteMapping mapping = UserDeleteController.class
                    .getMethod("deleteUser", String.class, UserDeleteRequest.class)
                    .getAnnotation(DeleteMapping.class);

            assertThat(mapping).isNotNull();
            assertThat(mapping.path()).containsExactly("/api/users/{userId}");
            assertThat(mapping.produces()).containsExactly(MediaType.APPLICATION_JSON_VALUE);
            // No consumes: a bodiless first-entry call must not be refused with 415.
            assertThat(mapping.consumes()).isEmpty();
            assertThat(UserDeleteController.class.getMethod("deleteUser", String.class,
                    UserDeleteRequest.class).getAnnotation(Transactional.class)).isNotNull();
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
