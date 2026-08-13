package com.vsergeychik.carddemo.user;

import static org.assertj.core.api.Assertions.assertThat;
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
 *
 * <p>Every test that asserts a <em>decision</em> instantiates the controller directly with a mocked
 * {@link SecUserRepository} and a fixed {@link Clock}. There is no Spring context and no
 * {@code MockMvc} in the decision path, which is gate <strong>G51</strong>: a guard chain is asserted
 * where it lives, and a failure names the COBOL paragraph rather than an HTTP status. Only the
 * {@link HttpProjection} group exercises the route binding, and it asserts nothing about behaviour that
 * is not already pinned below.
 *
 * <p>The delete carries <strong>no key</strong>: {@link HeldRecord#deleteHeld()} is reached from the hold
 * the read took, takes no argument, and is never replaced by a delete-by-key call. {@code EXEC CICS
 * DELETE} at {@code :307-311} names a {@code DATASET} and nothing else, so the record it removes is the
 * one {@code EXEC CICS READ ... UPDATE} at {@code :269-278} is still holding.
 *
 * <h2>The four preserved quirks - practice B5</h2>
 * This screen carries the densest preserved-defect surface in the {@code user} package. Each of these is
 * <em>asserted</em>, never repaired, because a like-for-like migration that quietly improves behaviour
 * has failed at exactly the thing it was for:
 *
 * <ol>
 *   <li><strong>The wrong verb.</strong> {@code :332} moves {@code 'Unable to Update User...'} on the
 *       <em>delete</em> failure path - "Update", not "Delete". Asserted verbatim, alongside the
 *       {@code DISPLAY 'RESP:' … 'REAS:' …} diagnostic that precedes it at {@code :330}. The word
 *       "Delete" appears in no expected message anywhere in this file. See
 *       {@link PreservedSourceDefect}.</li>
 *   <li><strong>The vestigial flag.</strong> {@code :45-47} declares {@code WS-USR-MODIFIED} with
 *       {@code 88 USR-MODIFIED-YES}/{@code -NO}, and {@code :85} sets it to NO. Nothing in the program
 *       ever sets it to YES and nothing ever reads it - it is a copy/paste remnant of {@code COUSR02C},
 *       which uses its copy for real. It is kept, and it is asserted false on every path. See
 *       {@link VestigialModifiedFlag}.</li>
 *   <li><strong>The fragile ordering.</strong> A successful delete performs {@code INITIALIZE-ALL-FIELDS}
 *       ({@code :315}, defined at {@code :349-356}) <em>first</em>, blanking the map fields and
 *       {@code WS-MESSAGE} but <em>not</em> {@code SEC-USR-ID}, and only then builds the success
 *       {@code STRING} at {@code :318-321} - which is why the message can still name the user just
 *       deleted. Reversing the two yields {@code 'User  has been deleted ...'}.</li>
 *   <li><strong>The missing guard.</strong> {@code DELETE-USER-INFO} at {@code :188-192} reads at
 *       {@code :190} and deletes at {@code :191} with <em>no</em> {@code ERR-FLG} re-check between them,
 *       so a failed lookup still falls through into a delete attempt. Asserted, so that anyone who
 *       "tidies" it by inserting the obvious guard is told immediately.</li>
 * </ol>
 *
 * <h2>Source of every expectation - practice B12</h2>
 * The expected values here are <strong>statically derived</strong> by reading the COBOL, its copybooks,
 * its mapset and its CSD definition. They were <em>not</em> captured from a legacy run: COBOL cannot be
 * executed in this environment, for the eight independently verified reasons in AAP {@code §0.7.6}
 * (no z/OS; the available compiler's indexed-file handler is disabled; no Language Environment
 * {@code CEE*} services; no CICS emulator; and {@code DFHAID}/{@code DFHBMSCA}/{@code DFHATTR} are absent
 * from the repository). That provenance is AAP risk <strong>R-A</strong>. Each assertion therefore cites
 * the line it was read from, so a reviewer can check the derivation against the source rather than trust
 * it.
 *
 * <h2>The sources these expectations were read from</h2>
 * <ul>
 *   <li>{@code app/csd/CARDDEMO.CSD:479-480} - {@code DEFINE TRANSACTION(CU03) GROUP(CARDDEMO)} with
 *       {@code PROGRAM(COUSR03C)}: the route's identity, mapped to {@code DELETE /api/users/{userId}} by
 *       AAP {@code §0.3.9}.</li>
 *   <li>{@code app/cbl/COUSR03C.cbl} - {@code :45-47} the vestigial flag; {@code :50-57} the 34-byte
 *       {@code CDEMO-CU03-INFO} extension; {@code :84-85} the two {@code SET}s that open every run;
 *       {@code :99-104} the auto-lookup on arrival from the list screen; {@code :108-130} the six-arm
 *       {@code EVALUATE EIBAID}; {@code :134-137} the {@code EXEC CICS RETURN}; {@code :142-169}
 *       {@code PROCESS-ENTER-KEY}; {@code :174-192} {@code DELETE-USER-INFO}; {@code :269-300} the
 *       locking read and its three outcomes; {@code :307-336} the keyless delete and its three outcomes;
 *       {@code :349-356} {@code INITIALIZE-ALL-FIELDS}.</li>
 *   <li>{@code app/cpy-bms/COUSR03.CPY} and {@code app/bms/COUSR03.bms} - the eleven payload fields and
 *       their declared widths. The {@code .CPY} carries 11 {@code xxxI} items; the {@code .bms} carries 26
 *       {@code DFHMDF} definitions of which 11 are name-labelled. Payload names and lengths come from the
 *       {@code xxxI} items only.</li>
 *   <li>{@code app/cpy/CSUSR01Y.cpy} - the 80-byte {@code SEC-USER-DATA} the read returns.</li>
 *   <li>{@code app/cpy/COCOM01Y.cpy} - the 160-byte {@code CARDDEMO-COMMAREA}.</li>
 *   <li>{@code app/jcl/DUSRSECJ.jcl:34-44} - the ten in-stream {@code USRSEC} seed records, 57 characters
 *       each. There is no {@code USRSEC} fixture file: {@code app/data/ASCII} holds nine files and none of
 *       them is this dataset.</li>
 * </ul>
 * All of the above are read-only parity oracles and are never written to (practice B3, gate G5).
 *
 * <h2>Two source inconsistencies recorded rather than reconciled - practice B4</h2>
 * <ul>
 *   <li>{@code COUSR03C} copies {@code DFHAID} and {@code DFHBMSCA} but <strong>not</strong>
 *       {@code DFHATTR}; it is {@code COSGN00C} and {@code COUSR01C} that carry that copybook. Nothing
 *       here compensates for the difference.</li>
 *   <li>Its {@code EXEC CICS RETURN} at {@code :134-137} passes {@code TRANSID} and {@code COMMAREA} but
 *       omits the {@code LENGTH} option that {@code COSGN00C:98-102} includes. Recorded, not corrected.</li>
 * </ul>
 * A third divergence belongs to the read itself: {@code :280-300} discriminates with the
 * {@code DFHRESP()} form where {@code COSGN00C} compares raw numerics. {@link FileStatus} unifies the two
 * spellings, so both programs' outcomes are named by the same constants.
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

    /** The same payload with {@code CDEMO-CU03-USR-SELECTED} naming a user. */
    private static UserDeleteRequest withSelection(UserDeleteRequest request, String selected) {
        Cu03Info info = request.cu03Info();
        return withExtension(request, new Cu03Info(info.usridFirst(), info.usridLast(), info.pageNum(),
                info.nextPageFlg(), info.usrSelFlg(), selected));
    }

    /** The same payload carrying a whole {@code CDEMO-CU03-INFO} extension - lines 50 to 58. */
    private static UserDeleteRequest withExtension(UserDeleteRequest request, Cu03Info extension) {
        return new UserDeleteRequest(request.trnName(), request.title01(), request.curDate(),
                request.pgmName(), request.title02(), request.curTime(), request.usrIdIn(),
                request.fName(), request.lName(), request.usrType(), request.errMsg(),
                request.navigationContext(), request.aid(), extension);
    }

    /** A screen carrying one user id and one communication area, everything else blank. */
    private static UserDeleteRequest screen(String usrIdIn, NavigationContext commarea) {
        UserDeleteRequest blank = UserDeleteRequest.empty();
        return new UserDeleteRequest(blank.trnName(), blank.title01(), blank.curDate(), blank.pgmName(),
                blank.title02(), blank.curTime(), usrIdIn, blank.fName(), blank.lName(), blank.usrType(),
                blank.errMsg(), commarea, blank.aid(), null);
    }

    /**
     * The same screen carrying a different {@code aid} member - the one-character {@code EIBAID} image.
     *
     * @param base the screen to copy
     * @param aid  the {@code aid} member the copy carries
     * @return the copy
     */
    private static UserDeleteRequest carrying(UserDeleteRequest base, String aid) {
        return new UserDeleteRequest(base.trnName(), base.title01(), base.curDate(), base.pgmName(),
                base.title02(), base.curTime(), base.usrIdIn(), base.fName(), base.lName(),
                base.usrType(), base.errMsg(), base.navigationContext(), aid, base.cu03Info());
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
    // The vestigial WS-USR-MODIFIED flag - practice B5, gate G50.
    // =================================================================================================

    /**
     * {@code WS-USR-MODIFIED} - declared, initialised, and then never touched again.
     *
     * <p>{@code app/cbl/COUSR03C.cbl:45-47} declares
     * {@code 05 WS-USR-MODIFIED PIC X(01) VALUE 'N'} with {@code 88 USR-MODIFIED-YES VALUE 'Y'} and
     * {@code 88 USR-MODIFIED-NO VALUE 'N'}, and {@code :85} executes {@code SET USR-MODIFIED-NO TO TRUE}
     * as the second statement of {@code MAIN-PARA}. Those four lines are the flag's <em>entire</em>
     * appearance in the program: searching the source for the name returns lines 45, 46, 47 and 85 and
     * nothing else. No statement sets it to {@code 'Y'}, and no {@code IF}, {@code EVALUATE} or
     * {@code WHEN} tests it.
     *
     * <p>It is a copy/paste remnant of {@code COUSR02C}, which declares the same flag and uses it for
     * real - that program has something to be modified. {@code COUSR01C}, which adds users, has no such
     * flag at all. The delete screen inherited the declaration without the logic.
     *
     * <h2>Why it is kept</h2>
     * Deleting the field would be tidier and would be wrong. Practice <strong>B5</strong> holds that dead
     * code is preserved rather than cleaned up, because removing it is an unrequested change to a program
     * whose observable behaviour is the specification. Wiring it to something would be worse still: it
     * would invent state the legacy screen never had. So the flag exists, it is reachable, and it is
     * pinned false.
     *
     * <h2>How gate G50 is satisfied</h2>
     * G50 asks for both states of every {@code 88}-level. For this pair the honest answer is asymmetric
     * and is asserted as such: {@code USR-MODIFIED-NO} is reachable and is the value on <em>every</em>
     * path through the program, and {@code USR-MODIFIED-YES} is <strong>unreachable by design</strong> -
     * not merely untested. {@link #theYesStateIsUnreachableByDesign()} pins that by proving no API exists
     * to reach it, which is a stronger and more durable statement than a contrived setter call would be.
     */
    @Nested
    @DisplayName("B5 - WS-USR-MODIFIED is vestigial: set to NO once, never to YES, never read")
    class VestigialModifiedFlag {

        @Test
        @DisplayName(":85 SET USR-MODIFIED-NO is the only statement that touches it, so NO is reachable")
        void theNoStateIsTheOpeningState() {
            ProgramState fresh = new ProgramState();

            // The VALUE 'N' clause of line 45, before MAIN-PARA has run at all.
            assertThat(fresh.isUsrModified()).isFalse();

            // And line 85's SET is idempotent against it, being the same value.
            fresh.setUsrModifiedNo();
            assertThat(fresh.isUsrModified()).isFalse();
        }

        @Test
        @DisplayName("a successful delete leaves it false: the one path that plainly 'modified' a user")
        void aSuccessfulDeleteLeavesItFalse() {
            HeldRecord hold = stubHeldRead(USER_ID);
            when(hold.deleteHeld()).thenReturn(WriteResult.written());

            ProgramState state = controller.mainPara(screen(USER_ID, reenter()), CicsAid.DFHPF5, null);

            // The record is gone - the message says so - and the flag still reads NO, because :313-322
            // never sets it. This is the assertion most likely to be "fixed" by a well-meaning hand.
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
            // A blank id stops in the two-arm EVALUATE at :177 and reaches no file.
            assertThat(controller.mainPara(screen(" ".repeat(8), reenter()), CicsAid.DFHPF5, null)
                    .isUsrModified()).isFalse();

            // EIBCALEN = 0 at :90-92 transfers before :85's sibling statements can matter.
            assertThat(controller.mainPara(null, CicsAid.DFHENTER, null).isUsrModified()).isFalse();

            // First entry at :95-105 paints the screen with no selection carried.
            assertThat(controller.mainPara(screen(" ".repeat(8), enter()), CicsAid.DFHENTER, null)
                    .isUsrModified()).isFalse();
        }

        @Test
        @DisplayName("G50 - the YES state is unreachable by design: no API of any kind reaches it")
        void theYesStateIsUnreachableByDesign() {
            // The flag's accessor pair is deliberately lopsided, mirroring the source: a reader, and a
            // setter for the ONE value :85 assigns. There is no setUsrModifiedYes because there is no
            // COBOL statement to translate into one, so the 'Y' state cannot be reached from any caller -
            // test or production. That is a property of the type, not an omission in this test, and it is
            // the honest way to satisfy G50 for a condition name the program never raises.
            assertThat(ProgramState.class.getDeclaredMethods())
                    .extracting(Method::getName)
                    .contains("isUsrModified", "setUsrModifiedNo")
                    .noneSatisfy(name -> assertThat(name).containsIgnoringCase("ModifiedYes"));

            // Nor is the backing field writable around the accessors: it is private, and no other member
            // of the enclosing controller names it.
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
            // WS-USR-MODIFIED is not a DFHMDF field, so it has no place in either payload - the same
            // reasoning that keeps WS-ERR-FLG, WS-RESP-CD and WS-REAS-CD out of them.
            assertThat(UserDeleteResponse.class.getRecordComponents())
                    .extracting(RecordComponent::getName)
                    .doesNotContain("usrModified", "errFlg");
            assertThat(UserDeleteRequest.class.getRecordComponents())
                    .extracting(RecordComponent::getName)
                    .doesNotContain("usrModified", "errFlg");
        }
    }

    // =================================================================================================
    // eibAidOf - the one-character EIBAID image the payload carries, read as the byte :108 evaluates.
    // =================================================================================================

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

    // =================================================================================================
    // The raw EIBAID byte. On this screen the fold is not an imprecision - PFK05 is the delete arm.
    // =================================================================================================

    @Nested
    @DisplayName("The raw EIBAID byte - PF17 must not become the PFK05 delete")
    class TheRawAidByte {

        @Test
        @DisplayName("a raw PF17 byte deletes nothing: it reaches WHEN OTHER, as it does on the terminal")
        void pf17DoesNotDelete() {
            // The finding that made this route the most serious of the six. COUSR03C tests EIBAID inline
            // at :108-130 and has no DFHPF15..DFHPF24 clause, so on the terminal PF17 paints "invalid
            // key". CSSTRPFY folds PF17 onto 'PFK05' - and :121-122 WHEN DFHPF5 is DELETE-USER-INFO. A
            // dispatch on the folded token therefore deletes a user record on a key the mainframe
            // rejects. The byte is resolved without the fold, so it does not.
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
            // With no byte on either spelling of the parameter the payload's aid member is the only
            // statement, and that member is the one-character image of EIBAID - not a CCARD-AID token.
            // 'PFK05' is five characters, so it states no byte at all: COUSR03C's EVALUATE EIBAID sees
            // DFHNULL and takes WHEN OTHER, the invalid-key arm. Folding the token back would have to
            // choose between the DFHPF5 and DFHPF17 that CSSTRPFY stores together, and choosing DFHPF5
            // would delete the record on a PF17 press the source refuses.
            UserDeleteResponse refused = controller.deleteUser(USER_ID,
                    withAidToken(screen(USER_ID, reenter()), AidKey.PFK05.token()),
                    null, null).screen();

            assertThat(refused.errMsg())
                    .isEqualTo(errMsgImage("Invalid key pressed. Please see below..."));
            verify(repository, never()).readForUpdate(anyString());

            // The one character that IS the byte reaches the delete arm, unchanged.
            HeldRecord hold = stubHeldRead(USER_ID);
            when(hold.deleteHeld()).thenReturn(WriteResult.written());

            UserDeleteResponse painted = controller.deleteUser(USER_ID,
                    withAidToken(screen(USER_ID, reenter()),
                            PfKeyResolver.aidImage(CicsAid.DFHPF5)),
                    null, null).screen();

            assertThat(painted.errMsg())
                    .isEqualTo(errMsgImage("User USER0001 has been deleted ..."));
        }

        /** The same screen carrying a stated {@code CCARD-AID} token. */
        private static UserDeleteRequest withAidToken(UserDeleteRequest request, String token) {
            return new UserDeleteRequest(request.trnName(), request.title01(), request.curDate(),
                    request.pgmName(), request.title02(), request.curTime(), request.usrIdIn(),
                    request.fName(), request.lName(), request.usrType(), request.errMsg(),
                    request.navigationContext(), token, request.cu03Info());
        }

        /** A {@link CicsAid} function-key constant by number, so the copybook name is the source. */
        private static byte functionKeyByte(int pfNumber) {
            try {
                return CicsAid.class.getDeclaredField("DFHPF" + pfNumber).getByte(null);
            } catch (ReflectiveOperationException absent) {
                throw new AssertionError("CicsAid does not declare DFHPF" + pfNumber, absent);
            }
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
            // Two different blanks, and this path produces both. USRIDINO is never written on it - no
            // receive, no INITIALIZE-ALL-FIELDS - so it keeps the LOW-VALUES image MOVE LOW-VALUES TO
            // COUSR3AO (:97) leaves, which is X'00' and therefore not Java-blank. ERRMSGO IS written, by
            // the MOVE SPACES at :88, so it is spaces at its declared width.
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

    // =================================================================================================
    // PROCESS-ENTER-KEY - lines 142-169, reached from EVALUATE EIBAID WHEN DFHENTER.
    // =================================================================================================

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
            // USRIDINI = SPACES OR LOW-VALUES at :147 is two whole-item comparisons, and a mixed image
            // equals neither - so the empty arm is NOT taken and the paragraph goes on to the READ. The
            // read is what then decides the outcome, exactly as it does for any other supplied id.
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
            // CSSTRPFY folds PF17 onto the token PFK05, so a token-driven dispatch would have deleted the
            // record here. COUSR03C compares EIBAID itself [:121], so PF17 is simply not one of its five.
            ProgramState state = controller.mainPara(screen(USER_ID, reenter()), CicsAid.DFHPF17, null);

            assertThat(state.isErrFlagOn()).isTrue();
            assertThat(state.response().errMsg()).startsWith("Invalid key pressed.");
            verify(repository, never()).readForUpdate(anyString());
            verify(repository, never()).deleteHeld(any());
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
            // = SPACES OR LOW-VALUES is COBOL's abbreviated combined relation and expands to two whole-
            // item comparisons. A mixed image is not all-spaces and not all-low-values, so it equals
            // neither and the condition is false - the field holds a value as far as the source is
            // concerned. Reporting it as blank would take the empty arm where the program takes the
            // populated one.
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

            assertThat(state.cu03Info()).isEqualTo(Cu03Info.initial());

            Cu03Info paged =
                    new Cu03Info("USER0001", "USER0010", 2, Cu03Info.NEXT_PAGE_YES, "S", "USER0004");
            state.setCu03Info(paged);
            assertThat(state.cu03Info()).isEqualTo(paged);

            // A communication area cannot arrive without these 34 bytes, so a null statement records the
            // state COUSR03C:50-58 declares rather than an absence the COBOL has no way to represent.
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

    // =================================================================================================
    // The screen contract - gate G9.
    // =================================================================================================

    /**
     * The communication area's arithmetic: 160 bytes of {@code COCOM01Y} plus 34 of CU03's own.
     *
     * <p>{@code app/cbl/COUSR03C.cbl:49} copies {@code COCOM01Y} and then {@code :50-58} extends it
     * <em>in place</em> with {@code 05 CDEMO-CU03-INFO}, a group of six items. That extension is this
     * screen's private business - it exists so the list screen can hand over which user was picked, and
     * which page it was on - so it belongs on the CU03 payload pair and must <strong>not</strong> be
     * folded into the shared {@link NavigationContext}. Sixteen other online programs copy
     * {@code COCOM01Y} and none of them has these fields.
     *
     * <p>The consequence is that {@code MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA} at {@code :94}
     * restores 194 bytes here where the shared area alone is 160. Both numbers are asserted, because a
     * shared type that quietly grew to 194 would corrupt every other screen's commarea offsets.
     */
    @Nested
    @DisplayName("The commarea - 160 shared bytes plus CU03's own 34, and neither absorbs the other")
    class CommareaWidths {

        @Test
        @DisplayName("COCOM01Y is exactly 160 bytes, and CU03 adds none of its own to that total")
        void theSharedCommareaIsExactlyOneHundredAndSixty() {
            assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(160);

            // 34 + 84 + 12 + 16 + 14 = 160, group by group as COCOM01Y declares them.
            assertThat(NavigationContext.GENERAL_INFO_LENGTH
                    + NavigationContext.CUSTOMER_INFO_LENGTH
                    + NavigationContext.ACCOUNT_INFO_LENGTH
                    + NavigationContext.CARD_INFO_LENGTH
                    + NavigationContext.MORE_INFO_LENGTH)
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH);

            // And the shared type carries no CU03 item: no reader here, no writer anywhere.
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

            // Six items, in the order lines 51 to 58 declare them, and no seventh.
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
                        // int, never double or float: PIC 9(08) is a scale-free unsigned integer, and a
                        // binary floating type could not represent its eight digits exactly anyway.
                        assertThat(component.getType()).isEqualTo(int.class);
                        assertThat(component.getType()).isNotIn(double.class, float.class);
                    });

            // The picture's bounds are enforced rather than documented: unsigned, and eight digits wide.
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

            // NEXT-PAGE-NO is the VALUE 'N' clause of line 54, so it is the state a cold start holds.
            assertThat(Cu03Info.initial().nextPageFlg()).isEqualTo(Cu03Info.NEXT_PAGE_NO);

            // NEXT-PAGE-YES is reachable, and both survive a round trip at the item's declared width of
            // one. COUSR03C never tests the flag - the list screen sets it and this screen carries it -
            // so "reachable and carried unchanged" is the whole of its contract here.
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

            // Lines 99 to 102 read usrSelected and lines 51 to 57 read nothing at all; the delete path
            // writes none of them. So every one of the six comes back exactly as it arrived.
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

    // =================================================================================================
    // CSSETATY - the error highlight, which is a function of the re-entry state - gate G38.
    // =================================================================================================

    /**
     * {@code CSSETATY} - {@code DFHRED} plus {@code '*'}, and only ever on re-entry.
     *
     * <p>The include moves {@code DFHRED} onto a field's colour item and {@code '*'} onto its output item
     * when that field failed validation <em>and</em> the program is in REENTER state. The second condition
     * is what makes it correct: on first entry ({@code CDEMO-PGM-CONTEXT = 0}) the operator has typed
     * nothing, so every field is legitimately blank and nothing has failed yet. A highlight painted then
     * would mark fields nobody has touched.
     *
     * <p>{@link FieldAttributeSetter} therefore takes the re-entry state as an explicit {@code boolean}
     * parameter rather than reading it from ambient state, and this class passes it explicitly at every
     * call. Gate <strong>G38</strong> requires both states to be exercised; both are, on the same field.
     *
     * <p>A note on scope. {@code COUSR03C} does not copy {@code CSSETATY} - it reports failures through
     * {@code WS-MESSAGE} and a cursor position, and it sets {@code ERRMSGC} directly at {@code :285},
     * {@code :317} and nowhere else. The shared resolver is still the component that decides highlighting
     * for the screen, so its two states are pinned here against this screen's own map name and field
     * names; what is asserted is that the ENTER/REENTER distinction holds, not that this program invokes
     * it. The three colours the program itself sets are asserted separately, below.
     */
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

            // The items it names are this map's own, spelled as app/cpy-bms/COUSR03.CPY spells them.
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

            // resolveFromFlags takes the two 88-level outcomes directly and must reach the same answer.
            assertThat(FieldAttributeSetter.resolveFromFlags(validation.notOk(), validation.blank(), true))
                    .isEqualTo(onReentry);

            // And on first entry no state highlights, failing or not.
            assertThat(FieldAttributeSetter.resolve(validation, false).untouched()).isTrue();
            assertThat(FieldAttributeSetter.resolveFromFlags(validation.notOk(), validation.blank(), false)
                    .untouched()).isTrue();
        }

        @Test
        @DisplayName("both CDEMO-PGM-CONTEXT states are exercised end to end - gate G50")
        void bothProgramContextStatesAreExercised() {
            // ENTER = 0 at :95: the screen is painted and the AID is never examined, so even a key this
            // program refuses on re-entry produces no error message at all.
            ProgramState onEnter =
                    controller.mainPara(screen(" ".repeat(8), enter()), CicsAid.DFHPF7, null);

            assertThat(onEnter.commarea().pgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_REENTER);
            assertThat(onEnter.isErrFlagOn()).isFalse();
            assertThat(onEnter.response().errMsg()).isEqualTo(" ".repeat(78));
            assertThat(onEnter.errMsgColour()).isEqualTo(BmsAttributes.DFHDFCOL);

            // REENTER = 1: the same key now reaches WHEN OTHER at :126-129 and is refused.
            ProgramState onReentry =
                    controller.mainPara(screen(" ".repeat(8), reenter()), CicsAid.DFHPF7, null);

            assertThat(onReentry.isErrFlagOn()).isTrue();
            assertThat(onReentry.response().errMsg())
                    .isEqualTo(errMsgImage(SystemMessages.CCDA_MSG_INVALID_KEY.trim()));
        }

        @Test
        @DisplayName("the three colours the program itself sets are the only ones a send can carry")
        void theProgramsOwnColoursAreTheOnesItSets() {
            // COUSR03C assigns ERRMSGC exactly twice - DFHNEUTR on a successful read (:285) and DFHGREEN
            // on a completed delete (:317). Every other send leaves the map's default in place. DFHRED is
            // never assigned by this program, which is consistent with it not copying CSSETATY.
            HeldRecord hold = stubHeldRead(USER_ID);
            assertThat(controller.mainPara(screen(USER_ID, reenter()), CicsAid.DFHENTER, null)
                    .errMsgColour()).isEqualTo(BmsAttributes.DFHNEUTR);

            when(hold.deleteHeld()).thenReturn(WriteResult.written());
            assertThat(controller.mainPara(screen(USER_ID, reenter()), CicsAid.DFHPF5, null)
                    .errMsgColour()).isEqualTo(BmsAttributes.DFHGREEN);

            // A refused key sets a message but no colour, so the default stands.
            assertThat(controller.mainPara(screen(USER_ID, reenter()), CicsAid.DFHPF7, null)
                    .errMsgColour()).isEqualTo(BmsAttributes.DFHDFCOL);

            // And a failed delete leaves the read's DFHNEUTR standing: :329-335 sets no colour.
            when(hold.deleteHeld()).thenReturn(WriteResult.duplicateRecord());
            assertThat(controller.mainPara(screen(USER_ID, reenter()), CicsAid.DFHPF5, null)
                    .errMsgColour()).isEqualTo(BmsAttributes.DFHNEUTR);
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
            // Only reachable through the HTTP binder. On a first entry - the arm :99-102 takes its key
            // from CDEMO-CU03-USR-SELECTED - the URI states the key, and the record deleted is the URI's.
            stubHeldRead(USER_ID);
            String body = mapper.writeValueAsString(
                    withAid(screen(" ".repeat(8), enter()), PfKeyResolver.aidImage(CicsAid.DFHPF5)));

            mockMvc.perform(delete("/api/users/{userId}", USER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    // :95-122 is the first-entry arm: it pre-fetches and paints the confirmation prompt.
                    // The EVALUATE EIBAID that acts on PF5 is on the re-entry arm at :144-191, so the
                    // attention identifier is not what this turn answers - the pre-fetch is.
                    .andExpect(jsonPath("$.errmsg")
                            .value(errMsgImage("Press PF5 key to delete this user ...")))
                    .andExpect(jsonPath("$.usridin").value(USER_ID))
                    .andExpect(jsonPath("$.cu03Info.usrSelected").value(USER_ID));

            verify(repository).readForUpdate(USER_ID);
            verify(repository, never()).readForUpdate("USER0002");
        }

        @Test
        @DisplayName("over HTTP, a re-entry USRIDIN naming another user is the record PF5 deletes, "
                + "because that is the field :179-191 holds and deletes on")
        void aDisagreeingIdentityIsRefusedOverHttp() throws Exception {
            // Only reachable through the HTTP binder, and the point of the route: the URI seeds a first
            // entry and is thereafter decorative, exactly as a 3270 has no URI at all. An operator who
            // types another user id over the painted screen and presses PF5 deletes that user, which is
            // what COUSR03C does - so the read, the hold and the delete all key on USER0002.
            HeldRecord hold = stubHeldRead("USER0002");
            when(hold.deleteHeld()).thenReturn(WriteResult.written());
            String body = mapper.writeValueAsString(withAid(screen("USER0002", reenter()),
                    PfKeyResolver.aidImage(CicsAid.DFHPF5)));

            mockMvc.perform(delete("/api/users/{userId}", USER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.errmsg")
                            .value(errMsgImage("User USER0002 has been deleted ...")));

            verify(repository).readForUpdate("USER0002");
            verify(repository, never()).readForUpdate(USER_ID);
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

            // CU03 deletes. CU01 adds and CU02 updates, and each owns its own verb on this URI, so the
            // dispatcher must refuse the other three here rather than route them into COUSR03C.
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

            // 405 is the dispatcher refusing the verb, so nothing behind it ran: no read was issued and,
            // the PF5 token notwithstanding, no record was deleted.
            verify(repository, never()).readForUpdate(anyString());
        }

        @Test
        @DisplayName("a blank path identity is answered with the empty-id message, not a 400")
        void aBlankPathIdentityIsAMessageNotARejection() throws Exception {
            String body = mapper.writeValueAsString(withAid(screen(" ".repeat(8), reenter()),
                    PfKeyResolver.aidImage(CicsAid.DFHENTER)));

            // %20 x 8 is USRIDINI = SPACES, which line 145 answers with a message on the screen. COUSR03C
            // has no concept of a malformed request, so an HTTP-level rejection would invent one.
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
        @DisplayName("a re-entry USRIDIN naming a different user is the identity, because :144-191 "
                + "validates, reads and deletes on the field the operator typed")
        void aReentryIdentityThatDisagreesIsTheIdentity() {
            // COUSR03C has no URI. :95-122 is the first-entry arm, which takes its key from
            // CDEMO-CU03-USR-SELECTED; :144-191 is the re-entry arm, and PROCESS-ENTER-KEY there reads
            // USRIDINI. Typing another user id over the painted screen and pressing ENTER - or PF5 to
            // delete that one - is the source-valid action, so the typed key is what the read uses.
            stubHeldRead("USER0002");

            UserDeleteResponse screen = controller
                    .deleteUser(USER_ID,
                            withAid(screen("USER0002", reenter()),
                                    PfKeyResolver.aidImage(CicsAid.DFHENTER)),
                            null, null)
                    .screen();

            verify(repository).readForUpdate("USER0002");
            verify(repository, never()).readForUpdate(USER_ID);
            assertThat(screen.usrIdIn()).isEqualTo("USER0002");
        }

        @ParameterizedTest(name = "a re-entry stating USRIDIN as \"{0}\" keeps exactly that")
        @ValueSource(strings = {"        ", "USER0001", "\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000"})
        @DisplayName("a re-entry's own field is authoritative: the source's own empty-field message at "
                + ":148-152 is the answer to a blank one, not a substituted key")
        void theStatesThatAgreeAreAccepted(String stated) {
            stubHeldRead(USER_ID);

            UserDeleteResponse screen = controller
                    .deleteUser(USER_ID,
                            withAid(screen(stated, reenter()),
                                    PfKeyResolver.aidImage(CicsAid.DFHENTER)),
                            null, null)
                    .screen();

            assertThat(screen.usrIdIn()).isEqualTo(stated);
            if (USER_ID.equals(stated)) {
                verify(repository).readForUpdate(USER_ID);
            } else {
                // SPACES and LOW-VALUES are both 'not supplied' to :148, whose arm is the message and
                // no read at all.
                verify(repository, never()).readForUpdate(anyString());
                assertThat(screen.errMsg()).isEqualTo(errMsgImage("User ID can NOT be empty..."));
            }
        }

        @Test
        @DisplayName("a CDEMO-CU03-USR-SELECTED naming a different user is replaced for the same reason")
        void anExtensionSelectionThatDisagreesIsProjectedOver() {
            // app/cbl/COUSR03C.cbl:99-102 reads the extension, not the screen field, on first entry - so
            // an unprojected extension would be the identity that wins on exactly that arm.
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

            // The other five items of the 34-byte group are carried untouched - only the selected id is
            // the URI's to state - so a payload naming none of them still round-trips its VALUE clauses.
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
            // No consumes: a bodiless first-entry call must not be refused with 415.
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
